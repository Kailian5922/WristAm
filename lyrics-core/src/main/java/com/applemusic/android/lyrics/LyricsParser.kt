package com.applemusic.android.lyrics

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** 一个字/词的时间轴片段，以及它在整行文本里的字符区间（用于逐字染色）。 */
class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val charStart: Int,
    val charEnd: Int
)

/** 一行歌词（带行级时间轴，可能还带逐字时间轴）。 */
class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** 逐字/逐词时间轴；为空表示这行只能整行高亮。 */
    val words: List<LyricWord> = emptyList(),
    /** Apple TTML 提供的对应翻译；没有时为空。 */
    val translation: String? = null,
    /** Apple TTML 行标识，可用于关联独立返回的译文。 */
    val key: String? = null,
    internal val isTranslationParagraph: Boolean = false,
    internal val agentId: String? = null
) {
    val isBlank: Boolean get() = text.isBlank()
    val hasWordTiming: Boolean get() = words.isNotEmpty()

    /**
     * [ms] 时刻这行已经「唱到」第几个字符 —— 逐字染色就靠它。
     */
    fun sungChars(ms: Long): Int {
        if (text.isEmpty()) return 0
        if (words.isEmpty()) {
            return when {
                ms >= endMs -> text.length
                ms <= startMs -> 0
                else -> if (ms - startMs >= (endMs - startMs) / 2) text.length else 0
            }
        }
        var n = 0
        for (w in words) {
            if (ms >= w.endMs) {
                n = maxOf(n, w.charEnd)
            } else if (ms >= w.startMs) {
                val span = (w.endMs - w.startMs).coerceAtLeast(1L)
                val frac = ((ms - w.startMs).toDouble() / span).coerceIn(0.0, 1.0)
                val wordChars = (w.charEnd - w.charStart).coerceAtLeast(0)
                val charsIntoWord = ((wordChars * frac).toInt() + 1).coerceAtMost(wordChars)
                n = maxOf(n, w.charStart + charsIntoWord)
                break
            } else {
                break
            }
        }
        return n.coerceIn(0, text.length)
    }
}

/** 一首歌的歌词。 */
class Lyrics(
    val lines: List<LyricLine>,
    /** 是否带时间轴（false = 纯文本，只能整段展示）。 */
    val synced: Boolean,
    /** Apple 的 data[0].type，例如 syllable-lyrics / lyrics。 */
    val kind: String = ""
) {
    /** 二分找出 [ms] 时刻应该高亮哪一行；没有匹配返回 -1。 */
    fun indexAt(ms: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= ms) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    /** 有逐字时间轴的行数。 */
    fun wordTimedLines(): Int = lines.count { it.hasWordTiming }

    /** 逐字片段总数。 */
    fun wordCount(): Int = lines.sumOf { it.words.size }
}

/**
 * Apple Music 歌词解析（支持 TTML 逐字/逐行时间轴与标准 LRC）。
 *
 * 使用 Android 原生 [XmlPullParser] 解析 TTML，正确处理：
 * - 任意深度的嵌套 `<span>`（`ttm:role="x-bg"`、`x-translation`、`x-roman`）；
 * - 所有 TTML 时间格式（`HH:MM:SS.mmm`、`MM:SS.mmm`、`s`/`ms`/`m`/`h`/`f`/`t`、`dur`）；
 * - 按语言优先级选择 `<translation xml:lang="...">` 译文块；
 * - 完整规范 LRC 解析（`[offset:]`、元数据过滤、多时间戳行展开、按时排序、双语合并）。
 */
object LyricsParser {

    private val WHITESPACE_RE = Regex("\\s+")
    private val XML_ENTITY_RE = Regex("&(#x[0-9A-Fa-f]+|#\\d+|[a-zA-Z]+);")
    private val LRC_TAG_RE = Regex("^\\[([a-zA-Z]+):([^\\]]*)]$")
    private val LRC_TIME_PREFIX_RE = Regex("^\\[(?:(\\d+):)?(\\d{1,3}):(\\d{1,2})(?:[.:](\\d+))?]")
    private val OFFSET_TIME_RE = Regex("^([+-]?\\d+(?:\\.\\d+)?)(ms|s|m|h|f|t)$", RegexOption.IGNORE_CASE)

    private val LRC_METADATA_TAGS = setOf(
        "ti", "ar", "al", "au", "by", "re", "ve", "length",
        "offset", "id", "hash", "sign", "qq", "total", "language", "lr"
    )

    private data class SpanFrame(
        val isTranslation: Boolean,
        val isRoman: Boolean,
        val isBg: Boolean,
        val timed: Boolean,
        val startMs: Long,
        val endMs: Long,
        val textStartIndex: Int,
        var hasTimedChild: Boolean = false
    )

    /** 从歌词接口的原始 JSON 里解析。解析不出返回 null。 */
    fun parse(json: String, preferredLocale: String = Locale.getDefault().toLanguageTag()): Lyrics? {
        val ttml: String
        val plain: String
        val kind: String
        try {
            val data = JSONObject(json).optJSONArray("data")
            if (data == null || data.length() == 0) return null
            val first = data.optJSONObject(0) ?: return null
            kind = first.optString("type", "")
            val attrs = first.optJSONObject("attributes") ?: return null
            ttml = attrs.optString("ttmlLocalizations")
                .takeIf { it.isNotBlank() }
                ?: attrs.optString("ttml", "")
            plain = attrs.optString("lyrics", "")
        } catch (_: Throwable) {
            return null
        }

        if (ttml.isNotEmpty()) {
            parseTtml(ttml, kind, preferredLocale)?.let { if (it.lines.isNotEmpty()) return it }
        }
        if (plain.isNotEmpty()) {
            return parsePlain(plain, kind)
        }
        return null
    }

    /** 使用 XmlPullParser 解析 TTML（正确支持嵌套 span、dur、时间单位与语言匹配）。 */
    fun parseTtml(
        ttml: String,
        kind: String = "",
        preferredLocale: String = Locale.getDefault().toLanguageTag()
    ): Lyrics? {
        if (ttml.isBlank()) return null

        val paragraphs = ArrayList<LyricLine>(64)
        val translationAgents = HashSet<String>()
        val metadataTranslations = LinkedHashMap<String, String>()
        val metadataTranslationScores = HashMap<String, Int>()

        var tickRate = 1000.0
        var frameRate = 30.0

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(ttml))

            var inTranslationBlock = false
            var currentTranslationScore = 0
            var currentTextForKey: String? = null
            val currentMetadataText = StringBuilder()

            var inParagraph = false
            var pBeginMs = -1L
            var pEndMs = -1L
            var pKey: String? = null
            var pAgent: String? = null
            var pIsTranslation = false

            val mainText = StringBuilder()
            val mainWords = ArrayList<LyricWord>()
            val bgText = StringBuilder()
            val bgWords = ArrayList<LyricWord>()
            val inlineTransText = StringBuilder()
            val spanStack = ArrayList<SpanFrame>()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = localName(parser.name)
                        when (tag) {
                            "tt" -> {
                                attrValue(parser, "tickrate")?.toDoubleOrNull()
                                    ?.takeIf { it > 0.0 }?.let { tickRate = it }
                                attrValue(parser, "framerate")?.toDoubleOrNull()
                                    ?.takeIf { it > 0.0 }?.let { frameRate = it }
                            }

                            "agent" -> {
                                val id = attrValue(parser, "id")
                                val role = attrValue(parser, "role").orEmpty()
                                if (!id.isNullOrBlank() && role.contains("translation", ignoreCase = true)) {
                                    translationAgents.add(id)
                                }
                            }

                            "translation" -> {
                                val type = attrValue(parser, "type")
                                if (type == null || type.equals("subtitle", ignoreCase = true)) {
                                    inTranslationBlock = true
                                    val lang = attrValue(parser, "lang").orEmpty()
                                    currentTranslationScore = scoreLocaleMatch(lang, preferredLocale)
                                }
                            }

                            "text" -> {
                                if (inTranslationBlock) {
                                    currentTextForKey = attrValue(parser, "for")
                                    currentMetadataText.setLength(0)
                                }
                            }

                            "p" -> {
                                val beginRaw = attrValue(parser, "begin")
                                val beginMs = beginRaw?.let { parseTime(it, tickRate, frameRate) } ?: -1L
                                if (beginMs >= 0L) {
                                    val endRaw = attrValue(parser, "end")
                                    val durRaw = attrValue(parser, "dur")
                                    val parsedEnd = endRaw?.let { parseTime(it, tickRate, frameRate) } ?: -1L
                                    val parsedDur = durRaw?.let { parseTime(it, tickRate, frameRate) } ?: -1L
                                    val endMs = when {
                                        parsedEnd > beginMs -> parsedEnd
                                        parsedDur > 0L -> beginMs + parsedDur
                                        else -> beginMs
                                    }
                                    inParagraph = true
                                    pBeginMs = beginMs
                                    pEndMs = endMs
                                    pKey = attrValue(parser, "key")
                                    pAgent = attrValue(parser, "agent")
                                    val pRole = attrValue(parser, "role").orEmpty()
                                    pIsTranslation = pRole.contains("x-translation", ignoreCase = true) ||
                                        (pAgent != null && pAgent in translationAgents)
                                    mainText.setLength(0)
                                    mainWords.clear()
                                    bgText.setLength(0)
                                    bgWords.clear()
                                    inlineTransText.setLength(0)
                                    spanStack.clear()
                                }
                            }

                            "span" -> {
                                if (inParagraph) {
                                    val role = attrValue(parser, "role").orEmpty()
                                    val parent = spanStack.lastOrNull()
                                    val isTrans = (parent?.isTranslation == true) ||
                                        role.contains("x-translation", ignoreCase = true)
                                    val isRoman = (parent?.isRoman == true) ||
                                        role.contains("x-roman", ignoreCase = true) ||
                                        role.contains("translit", ignoreCase = true)
                                    val isBg = (parent?.isBg == true) ||
                                        role.contains("x-bg", ignoreCase = true)

                                    val sBegin = attrValue(parser, "begin")
                                        ?.let { parseTime(it, tickRate, frameRate) } ?: -1L
                                    val sEndRaw = attrValue(parser, "end")
                                        ?.let { parseTime(it, tickRate, frameRate) } ?: -1L
                                    val sDurRaw = attrValue(parser, "dur")
                                        ?.let { parseTime(it, tickRate, frameRate) } ?: -1L
                                    val timed = sBegin >= 0L && !isTrans && !isRoman
                                    val sEnd = when {
                                        !timed -> sBegin
                                        sEndRaw > sBegin -> sEndRaw
                                        sDurRaw > 0L -> sBegin + sDurRaw
                                        else -> sBegin
                                    }
                                    if (timed) {
                                        parent?.hasTimedChild = true
                                    }
                                    val targetBuilder = if (isBg) bgText else mainText
                                    spanStack.add(
                                        SpanFrame(
                                            isTranslation = isTrans,
                                            isRoman = isRoman,
                                            isBg = isBg,
                                            timed = timed,
                                            startMs = sBegin,
                                            endMs = sEnd,
                                            textStartIndex = targetBuilder.length
                                        )
                                    )
                                }
                            }

                            "br" -> {
                                if (inParagraph) {
                                    val frame = spanStack.lastOrNull()
                                    when {
                                        frame?.isTranslation == true -> appendNormalizedSpace(inlineTransText)
                                        frame?.isRoman == true -> Unit
                                        frame?.isBg == true -> appendNormalizedSpace(bgText)
                                        else -> appendNormalizedSpace(mainText)
                                    }
                                } else if (inTranslationBlock && currentTextForKey != null) {
                                    appendNormalizedSpace(currentMetadataText)
                                }
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val raw = parser.text.orEmpty()
                        if (raw.isNotEmpty()) {
                            if (inParagraph) {
                                val frame = spanStack.lastOrNull()
                                when {
                                    frame?.isTranslation == true -> appendNormalizedText(inlineTransText, raw)
                                    frame?.isRoman == true -> Unit
                                    frame?.isBg == true -> appendNormalizedText(bgText, raw)
                                    else -> appendNormalizedText(mainText, raw)
                                }
                            } else if (inTranslationBlock && currentTextForKey != null) {
                                appendNormalizedText(currentMetadataText, raw)
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tag = localName(parser.name)
                        when (tag) {
                            "translation" -> {
                                inTranslationBlock = false
                                currentTranslationScore = 0
                                currentTextForKey = null
                            }

                            "text" -> {
                                val key = currentTextForKey
                                if (inTranslationBlock && !key.isNullOrBlank()) {
                                    val value = currentMetadataText.toString().trim()
                                    val prevScore = metadataTranslationScores[key] ?: -1
                                    if (value.isNotBlank() && currentTranslationScore >= prevScore) {
                                        metadataTranslations[key] = value
                                        metadataTranslationScores[key] = currentTranslationScore
                                    }
                                }
                                currentTextForKey = null
                                currentMetadataText.setLength(0)
                            }

                            "span" -> {
                                if (inParagraph && spanStack.isNotEmpty()) {
                                    val frame = spanStack.removeAt(spanStack.lastIndex)
                                    if (frame.timed && !frame.hasTimedChild) {
                                        val builder = if (frame.isBg) bgText else mainText
                                        val wordsList = if (frame.isBg) bgWords else mainWords
                                        var startIdx = frame.textStartIndex
                                        var endIdx = builder.length
                                        while (startIdx < endIdx && builder[startIdx].isWhitespace()) startIdx++
                                        while (endIdx > startIdx && builder[endIdx - 1].isWhitespace()) endIdx--
                                        if (endIdx > startIdx) {
                                            wordsList.add(
                                                LyricWord(
                                                    startMs = frame.startMs,
                                                    endMs = maxOf(frame.endMs, frame.startMs),
                                                    charStart = startIdx,
                                                    charEnd = endIdx
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            "p" -> {
                                if (inParagraph) {
                                    val useBgFallback = mainText.toString().isBlank() && bgText.toString().isNotBlank()
                                    val rawText = (if (useBgFallback) bgText else mainText).toString()
                                    val trimmedText = rawText.trim()
                                    val leadingTrim = rawText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                                    val rawWords = if (useBgFallback) bgWords else mainWords
                                    val normalizedWords = normalizeWords(rawWords, leadingTrim, trimmedText.length)
                                    val inlineTrans = inlineTransText.toString().trim().ifBlank { null }

                                    paragraphs.add(
                                        LyricLine(
                                            startMs = pBeginMs,
                                            endMs = pEndMs,
                                            text = trimmedText,
                                            words = normalizedWords,
                                            translation = inlineTrans,
                                            key = pKey,
                                            isTranslationParagraph = pIsTranslation,
                                            agentId = pAgent
                                        )
                                    )
                                    inParagraph = false
                                    spanStack.clear()
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Throwable) {
            if (paragraphs.isEmpty()) return null
        }

        if (paragraphs.isEmpty()) return null

        // 推断未显式标记 x-translation 的译文段落（仅在同 key 或非不同人声 agent 的双行间推断）
        val inferredTranslationIndices = HashSet<Int>()
        val unmarked = paragraphs.withIndex().filterNot { it.value.isTranslationParagraph }
        val groups = unmarked.groupBy { indexed ->
            indexed.value.key?.let { "key:$it" } ?: "time:${indexed.value.startMs}"
        }
        for (group in groups.values) {
            if (group.size < 2) continue
            val distinctAgents = group.mapNotNull { it.value.agentId?.takeIf(String::isNotBlank) }.toSet()
            // 同时间段不同人声代理（如 v1/v2 合唱）不应误判为译文并删除
            val sameKeyGroup = group.first().value.key != null
            if (!sameKeyGroup && distinctAgents.size >= 2) continue
            val hasWordTimedAndPlainMix = group.any { it.value.hasWordTiming } && group.any { !it.value.hasWordTiming }
            if (!sameKeyGroup && !hasWordTimedAndPlainMix) continue

            val primaryIndex = group.firstOrNull { it.value.hasWordTiming }?.index ?: group.first().index
            group.filter { it.index != primaryIndex }.forEach {
                inferredTranslationIndices.add(it.index)
            }
        }

        val translationIndices = paragraphs.indices.filter {
            paragraphs[it].isTranslationParagraph || it in inferredTranslationIndices
        }.toSet()
        val original = paragraphs.filterIndexed { index, _ -> index !in translationIndices }
        if (original.isEmpty()) return null

        val translationsByKey = LinkedHashMap<String, String>()
        paragraphs.filterIndexed { index, _ -> index in translationIndices }
            .filter { !it.key.isNullOrBlank() && it.text.isNotBlank() }
            .forEach { translationsByKey[it.key!!] = it.text }
        translationsByKey.putAll(metadataTranslations)

        val unmatchedTranslations = paragraphs.withIndex()
            .filter { it.index in translationIndices }
            .map { it.value }
            .filter { it.key.isNullOrBlank() && it.text.isNotBlank() }
            .toMutableList()

        val merged = original.map { line ->
            val existingInline = line.translation?.takeIf { it.isNotBlank() && it != line.text }
            val resolvedTranslation = existingInline
                ?: line.key?.let(translationsByKey::get)?.takeIf { it != line.text }
                ?: run {
                    val idx = unmatchedTranslations.indexOfFirst {
                        it.startMs == line.startMs && it.text != line.text
                    }
                    if (idx >= 0) unmatchedTranslations.removeAt(idx).text else null
                }
            if (resolvedTranslation == line.translation) {
                line
            } else {
                LyricLine(
                    startMs = line.startMs,
                    endMs = line.endMs,
                    text = line.text,
                    words = line.words,
                    translation = resolvedTranslation,
                    key = line.key,
                    agentId = line.agentId
                )
            }
        }.sortedBy { it.startMs }

        // 补齐缺失的 endMs（例如自闭合间奏 <p begin="15.0"/> 或无 end 属性的行）
        val finalized = backfillMissingEndMs(merged)
        return Lyrics(finalized, true, kind)
    }

    /**
     * 将 Apple 单独 `/lyrics` 接口返回的译文合并到逐字歌词资源中。
     */
    fun mergeTranslations(
        original: Lyrics,
        translationJson: String,
        preferredLocale: String = Locale.getDefault().toLanguageTag()
    ): Lyrics {
        val attributes = try {
            JSONObject(translationJson).optJSONArray("data")
                ?.optJSONObject(0)?.optJSONObject("attributes")
        } catch (_: Exception) {
            null
        } ?: return original

        val ttml = attributes.optString("ttmlLocalizations")
            .takeIf { it.isNotBlank() }
            ?: attributes.optString("ttml").takeIf { it.isNotBlank() }
            ?: return original

        val parsed = parseTtml(ttml, preferredLocale = preferredLocale) ?: return original
        val translationByKey = HashMap<String, String>()
        val candidates = ArrayList<LyricLine>()
        for (line in parsed.lines) {
            val trans = line.translation?.takeIf { it.isNotBlank() } ?: continue
            if (!line.key.isNullOrBlank()) translationByKey[line.key] = trans
            candidates.add(LyricLine(line.startMs, line.endMs, trans, key = line.key))
        }
        if (translationByKey.isEmpty() && candidates.isEmpty()) {
            return original
        }

        val usedCandidates = BooleanArray(candidates.size)
        val merged = original.lines.map { line ->
            if (!line.translation.isNullOrBlank()) return@map line
            val byKey = line.key?.let(translationByKey::get)?.takeIf { it != line.text }
            val resolved = byKey ?: run {
                var bestIdx = -1
                var bestDiff = Long.MAX_VALUE
                for (i in candidates.indices) {
                    if (usedCandidates[i]) continue
                    val cand = candidates[i]
                    if (cand.text == line.text) continue
                    val diff = abs(cand.startMs - line.startMs)
                    if (diff <= 1_500L && diff < bestDiff) {
                        bestDiff = diff
                        bestIdx = i
                    }
                }
                if (bestIdx >= 0) {
                    usedCandidates[bestIdx] = true
                    candidates[bestIdx].text
                } else {
                    null
                }
            }
            if (resolved == null) {
                line
            } else {
                LyricLine(
                    startMs = line.startMs,
                    endMs = line.endMs,
                    text = line.text,
                    words = line.words,
                    translation = resolved,
                    key = line.key,
                    agentId = line.agentId
                )
            }
        }
        return Lyrics(merged, original.synced, original.kind)
    }

    /** 解析 `attributes.lyrics` 或标准 LRC；无任何时间戳时退化为纯文本。 */
    fun parsePlain(plain: String, kind: String = ""): Lyrics? {
        val raw = plain.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\r\n", "\n").replace('\r', '\n')
        val rawLines = raw.split('\n')

        var offsetMs = 0L
        var anyTimestamp = false
        val timedEntries = ArrayList<Pair<Long, String>>(rawLines.size)
        val plainEntries = ArrayList<String>(rawLines.size)

        for (ln in rawLines) {
            val t = ln.trim()
            if (t.isEmpty()) continue

            // 检查 LRC 元数据标签（如 [ti:...]、[offset:500]）
            val metaMatch = LRC_TAG_RE.matchEntire(t)
            if (metaMatch != null) {
                val tagName = metaMatch.groupValues[1].lowercase(Locale.ROOT)
                if (tagName == "offset") {
                    offsetMs = metaMatch.groupValues[2].trim().toLongOrNull() ?: 0L
                    continue
                }
                if (tagName in LRC_METADATA_TAGS) {
                    continue
                }
            }

            // 提取行首全部时间戳（支持多时间戳行如 [00:12.34][01:23.45]副歌）
            var cursor = 0
            val lineTimestamps = ArrayList<Long>(2)
            while (cursor < t.length) {
                val match = LRC_TIME_PREFIX_RE.find(t.substring(cursor)) ?: break
                val hours = match.groupValues[1].toLongOrNull() ?: 0L
                val minutes = match.groupValues[2].toLongOrNull() ?: 0L
                val seconds = match.groupValues[3].toLongOrNull() ?: 0L
                val fracRaw = match.groupValues[4]
                val millis = parseFractionMillis(fracRaw)
                val totalMs = hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
                lineTimestamps.add(totalMs)
                cursor += match.value.length
            }

            if (lineTimestamps.isNotEmpty()) {
                anyTimestamp = true
                val content = unescape(t.substring(cursor).trim())
                for (ts in lineTimestamps) {
                    timedEntries.add(ts to content)
                }
            } else {
                val cleaned = unescape(t)
                if (cleaned.isNotEmpty()) {
                    plainEntries.add(cleaned)
                }
            }
        }

        if (anyTimestamp) {
            if (timedEntries.isEmpty()) return null
            // 应用 [offset:]（正值表示歌词提前）并稳定排序
            val adjusted = timedEntries
                .map { (ts, text) -> (ts - offsetMs).coerceAtLeast(0L) to text }
                .sortedBy { it.first }

            // 合并同一时间戳的相邻双语行（仅合并非空且文本不同的连续双行）
            val mergedLines = ArrayList<LyricLine>(adjusted.size)
            var i = 0
            while (i < adjusted.size) {
                val (startMs, text) = adjusted[i]
                var translation: String? = null
                if (i + 1 < adjusted.size &&
                    adjusted[i + 1].first == startMs &&
                    text.isNotBlank() &&
                    adjusted[i + 1].second.isNotBlank() &&
                    adjusted[i + 1].second != text
                ) {
                    // 仅当同一时间戳恰好两行时视作原词+译文
                    val hasThirdSameTs = i + 2 < adjusted.size && adjusted[i + 2].first == startMs
                    if (!hasThirdSameTs) {
                        translation = adjusted[i + 1].second
                        i++
                    }
                }
                mergedLines.add(LyricLine(startMs, startMs, text, translation = translation))
                i++
            }

            val fixed = backfillMissingEndMs(mergedLines)
            return Lyrics(fixed, true, kind)
        }

        if (plainEntries.isEmpty()) return null
        return Lyrics(plainEntries.map { LyricLine(-1L, -1L, it) }, false, kind)
    }

    /**
     * 解析 TTML 时间：
     * - 纯数字秒数：`12.345`
     * - 带单位偏移：`12.345s`、`500ms`、`1.5m`、`0.5h`、`30f`、`1000t`
     * - 时钟格式：`HH:MM:SS.mmm`、`MM:SS.mmm`、`HH:MM:SS:FF`
     * 解析失败返回 -1。
     */
    fun parseTime(s: String, tickRate: Double = 1000.0, frameRate: Double = 30.0): Long {
        val t = s.trim()
        if (t.isEmpty()) return -1L

        // 带单位偏移时间
        val offsetMatch = OFFSET_TIME_RE.matchEntire(t)
        if (offsetMatch != null) {
            val value = offsetMatch.groupValues[1].toDoubleOrNull() ?: return -1L
            if (value < 0.0) return -1L
            val unit = offsetMatch.groupValues[2].lowercase(Locale.ROOT)
            val ms = when (unit) {
                "ms" -> value
                "s" -> value * 1000.0
                "m" -> value * 60_000.0
                "h" -> value * 3_600_000.0
                "f" -> value * (1000.0 / frameRate.coerceAtLeast(1.0))
                "t" -> value * (1000.0 / tickRate.coerceAtLeast(1.0))
                else -> return -1L
            }
            return ms.roundToLong()
        }

        // 纯数字（秒）
        t.toDoubleOrNull()?.let { sec ->
            return if (sec >= 0.0) (sec * 1000.0).roundToLong() else -1L
        }

        // 时钟格式
        val parts = t.split(':')
        if (parts.size == 4) {
            // HH:MM:SS:FF
            val h = parts[0].toLongOrNull() ?: return -1L
            val m = parts[1].toLongOrNull() ?: return -1L
            val sec = parts[2].toDoubleOrNull() ?: return -1L
            val frames = parts[3].toDoubleOrNull() ?: return -1L
            if (h < 0 || m < 0 || sec < 0.0 || frames < 0.0) return -1L
            val totalMs = ((h * 3600L + m * 60L) * 1000.0) + sec * 1000.0 +
                frames * (1000.0 / frameRate.coerceAtLeast(1.0))
            return totalMs.roundToLong()
        }
        if (parts.isEmpty() || parts.size > 3) return -1L

        var totalSec = 0.0
        for (p in parts) {
            val v = p.toDoubleOrNull() ?: return -1L
            if (v < 0.0) return -1L
            totalSec = totalSec * 60.0 + v
        }
        return (totalSec * 1000.0).roundToLong()
    }

    /** 单遍还原 XML/HTML 字符实体（杜绝 `&amp;#39;` 双重反转义与非法代理码点）。 */
    fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return XML_ENTITY_RE.replace(s) { m ->
            val token = m.groupValues[1]
            when {
                token.startsWith("#x", ignoreCase = true) -> {
                    decodeCodePoint(token.substring(2).toIntOrNull(16), m.value)
                }
                token.startsWith("#") -> {
                    decodeCodePoint(token.substring(1).toIntOrNull(10), m.value)
                }
                else -> when (token.lowercase(Locale.ROOT)) {
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "amp" -> "&"
                    "nbsp" -> " "
                    "rsquo", "lsquo" -> "'"
                    "rdquo", "ldquo" -> "\""
                    "mdash" -> "—"
                    "ndash" -> "–"
                    "hellip" -> "…"
                    else -> m.value
                }
            }
        }
    }

    private fun decodeCodePoint(code: Int?, fallback: String): String {
        if (code == null || code !in 1..0x10FFFF || code in 0xD800..0xDFFF) return fallback
        return String(Character.toChars(code))
    }

    private fun localName(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return raw.substringAfter(':').lowercase(Locale.ROOT)
    }

    private fun attrValue(parser: XmlPullParser, localAttrName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (localName(parser.getAttributeName(i)) == localAttrName) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun scoreLocaleMatch(xmlLang: String, preferredLocale: String): Int {
        val candidate = xmlLang.trim().replace('_', '-').lowercase(Locale.ROOT)
        val preferred = preferredLocale.trim().replace('_', '-').lowercase(Locale.ROOT)
        if (candidate.isEmpty()) return 1
        if (candidate == preferred) return 4
        val candLang = candidate.substringBefore('-')
        val prefLang = preferred.substringBefore('-')
        if (candLang == prefLang) {
            // 简体中文偏好加权
            if (prefLang == "zh" &&
                (preferred.contains("hans") || preferred.contains("cn") || preferred.contains("sg")) &&
                (candidate.contains("hans") || candidate.contains("cn") || candidate.contains("sg"))
            ) {
                return 3
            }
            return 2
        }
        return 1
    }

    private fun appendNormalizedText(sb: StringBuilder, text: String) {
        val collapsed = WHITESPACE_RE.replace(text, " ")
        if (collapsed.isEmpty()) return
        if (collapsed == " ") {
            appendNormalizedSpace(sb)
            return
        }
        var start = 0
        if (collapsed[0] == ' ' && (sb.isEmpty() || sb[sb.length - 1] == ' ')) {
            start = 1
        }
        if (start < collapsed.length) {
            sb.append(collapsed, start, collapsed.length)
        }
    }

    private fun appendNormalizedSpace(sb: StringBuilder) {
        if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') {
            sb.append(' ')
        }
    }

    private fun normalizeWords(
        words: List<LyricWord>,
        leadingTrim: Int,
        textLength: Int
    ): List<LyricWord> {
        if (words.isEmpty() || textLength <= 0) return emptyList()
        val out = ArrayList<LyricWord>(words.size)
        var lastStart = 0L
        for (w in words) {
            val cs = (w.charStart - leadingTrim).coerceIn(0, textLength)
            val ce = (w.charEnd - leadingTrim).coerceIn(cs, textLength)
            if (ce <= cs) continue
            val st = maxOf(w.startMs, lastStart)
            val en = maxOf(w.endMs, st)
            out.add(LyricWord(st, en, cs, ce))
            lastStart = st
        }
        return out
    }

    private fun backfillMissingEndMs(sorted: List<LyricLine>): List<LyricLine> {
        if (sorted.isEmpty()) return sorted
        return sorted.mapIndexed { idx, cur ->
            if (cur.endMs > cur.startMs) {
                cur
            } else {
                var nextStart = -1L
                for (j in idx + 1 until sorted.size) {
                    if (sorted[j].startMs > cur.startMs) {
                        nextStart = sorted[j].startMs
                        break
                    }
                }
                val fallbackDuration = if (cur.isBlank) 3_000L else 5_000L
                val inferredEnd = if (nextStart > cur.startMs) nextStart else cur.startMs + fallbackDuration
                LyricLine(
                    startMs = cur.startMs,
                    endMs = inferredEnd,
                    text = cur.text,
                    words = cur.words,
                    translation = cur.translation,
                    key = cur.key,
                    agentId = cur.agentId
                )
            }
        }
    }

    private fun parseFractionMillis(fracRaw: String): Long {
        if (fracRaw.isEmpty()) return 0L
        return when (fracRaw.length) {
            1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
            2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
            3 -> fracRaw.toLongOrNull() ?: 0L
            else -> fracRaw.substring(0, 3).toLongOrNull() ?: 0L
        }
    }
}
