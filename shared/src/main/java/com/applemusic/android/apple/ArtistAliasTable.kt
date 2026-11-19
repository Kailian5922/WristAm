// 动态艺人名归一化与运行时跨语种别名缓存（不再依赖静态硬编码歌手字典）。
package com.applemusic.android.apple

import com.applemusic.android.util.ChineseText
import java.text.Normalizer

object ArtistAliasTable {
    /** 常见日语新字体汉字 → 简体字折叠表（补齐 ICU Traditional-Simplified 不转换的日语新字体差异）。 */
    private val SHINJITAI_TO_SIMPLIFIED: Map<Char, Char> = mapOf(
        '亜' to '亚', '円' to '圆', '応' to '应', '仮' to '假', '価' to '价',
        '絵' to '绘', '覚' to '觉', '観' to '观', '顔' to '颜', '帰' to '归',
        '気' to '气', '響' to '响', '暁' to '晓', '剣' to '剑', '険' to '险',
        '顕' to '显', '験' to '验', '厳' to '严', '広' to '广', '黒' to '黑',
        '歳' to '岁', '桜' to '樱', '雑' to '杂', '産' to '产', '児' to '儿',
        '実' to '实', '釈' to '释', '従' to '从', '渋' to '涩', '獣' to '兽',
        '縦' to '纵', '処' to '处', '焼' to '烧', '浄' to '净', '譲' to '让',
        '図' to '图', '酔' to '醉', '穂' to '穗', '瀬' to '濑', '斉' to '齐',
        '斎' to '斋', '専' to '专', '戦' to '战', '繊' to '纤', '総' to '总',
        '騒' to '骚', '増' to '增', '臓' to '脏', '蔵' to '藏', '続' to '续',
        '対' to '对', '帯' to '带', '滝' to '泷', '択' to '择', '沢' to '泽',
        '単' to '单', '団' to '团', '弾' to '弹', '遅' to '迟', '聴' to '听',
        '鉄' to '铁', '転' to '转', '伝' to '传', '闘' to '斗', '徳' to '德',
        '読' to '读', '縄' to '绳', '悩' to '恼', '脳' to '脑', '拝' to '拜',
        '廃' to '废', '売' to '卖', '発' to '发', '抜' to '拔', '晩' to '晚',
        '浜' to '滨', '仏' to '佛', '並' to '并', '変' to '变', '辺' to '边',
        '舗' to '铺', '歩' to '步', '豊' to '丰', '毎' to '每', '満' to '满',
        '黙' to '默', '薬' to '药', '訳' to '译', '揺' to '摇', '様' to '样',
        '頼' to '赖', '覧' to '览', '竜' to '龙', '両' to '两', '緑' to '绿',
        '涙' to '泪', '霊' to '灵', '齢' to '龄', '歴' to '历', '労' to '劳',
        '恵' to '惠', '楽' to '乐', '関' to '关', '栄' to '荣', '蛍' to '萤',
        '亀' to '龟'
    )

    private val PAREN_BILINGUAL_REGEX = Regex("""^(.+?)\s*[(（]([^()（）]+)[)）]\s*$""")
    private const val MAX_DYNAMIC_KEYS = 512

    private val dynamicLock = Any()
    private val dynamicGroupsByKey = LinkedHashMap<String, LinkedHashSet<String>>(64, 0.75f, true)

    /**
     * 记录由在线接口动态解析出的跨语种等价艺名组（如 Apple Music 同 ID 在中/美区返回的名称），
     * 供同会话内本地资料库关联与二次检索复用，完全摆脱静态硬编码字典。
     */
    @JvmStatic
    fun registerDynamicAliases(names: Collection<String>) {
        val cleaned = names.mapNotNull { raw ->
            val t = raw.trim()
            val k = normalizeKey(t)
            if (t.isNotEmpty() && k.isNotEmpty()) k to t else null
        }
        if (cleaned.size < 2) return
        synchronized(dynamicLock) {
            val merged = LinkedHashSet<String>()
            val affectedKeys = LinkedHashSet<String>()
            for ((key, display) in cleaned) {
                affectedKeys.add(key)
                merged.add(display)
                dynamicGroupsByKey[key]?.let { existing ->
                    merged.addAll(existing)
                    for (item in existing) {
                        val ek = normalizeKey(item)
                        if (ek.isNotEmpty()) affectedKeys.add(ek)
                    }
                }
            }
            if (merged.size < 2) return
            for (key in affectedKeys) {
                dynamicGroupsByKey[key] = LinkedHashSet(merged)
            }
            while (dynamicGroupsByKey.size > MAX_DYNAMIC_KEYS) {
                val eldestKey = dynamicGroupsByKey.keys.firstOrNull() ?: break
                dynamicGroupsByKey.remove(eldestKey)
            }
        }
    }

    @JvmStatic
    fun hasHanCharacters(text: String): Boolean =
        text.any { it in '\u3400'..'\u9FFF' || it in '\uF900'..'\uFAFF' }

    @JvmStatic
    fun hasJapaneseKana(text: String): Boolean =
        text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' || it in '\uFF66'..'\uFF9F' }

    @JvmStatic
    fun hasLatinLetters(text: String): Boolean =
        text.any { it in 'A'..'Z' || it in 'a'..'z' || it in '\u00C0'..'\u024F' }

    /**
     * 精准匹配用归一化键：
     * - 繁转简 + 常见日语新字体折叠；
     * - 仅剥离拉丁组合重音符（保留日语浊音/半浊音假名不变）；
     * - 忽略大小写、空白、中点及常见装饰标点。
     */
    @JvmStatic
    fun normalizeKey(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val simplified = ChineseText.toSimplified(trimmed)
        val nfkc = Normalizer.normalize(simplified, Normalizer.Form.NFKC)
        val nfkd = Normalizer.normalize(nfkc, Normalizer.Form.NFKD)
        val strippedAccents = StringBuilder(nfkd.length)
        for (ch in nfkd) {
            // 只移除拉丁组合重音符（U+0300..U+036F），保留日语浊点/半浊点（U+3099..U+309A）
            if (ch in '\u0300'..'\u036F') continue
            strippedAccents.append(ch)
        }
        val composed = Normalizer.normalize(strippedAccents.toString(), Normalizer.Form.NFC)
        val sb = StringBuilder(composed.length)
        for (rawCh in composed) {
            val mapped = SHINJITAI_TO_SIMPLIFIED[rawCh] ?: rawCh
            val ch = mapped.lowercaseChar()
            when (ch) {
                'ø' -> {
                    sb.append('o')
                    continue
                }
                'ł' -> {
                    sb.append('l')
                    continue
                }
                'đ' -> {
                    sb.append('d')
                    continue
                }
                'æ' -> {
                    sb.append("ae")
                    continue
                }
                'œ' -> {
                    sb.append("oe")
                    continue
                }
                'ß' -> {
                    sb.append("ss")
                    continue
                }
            }
            if (Character.isWhitespace(ch) ||
                ch == '·' || ch == '・' || ch == '•' || ch == '‧' || ch == '･' ||
                ch == '.' || ch == ',' || ch == '-' || ch == '_' ||
                ch == '~' || ch == '～' || ch == '—' || ch == '–' ||
                ch == '\'' || ch == '’' || ch == '‘' || ch == '`' ||
                ch == '。' || ch == '、' || ch == '，' ||
                ch == '!' || ch == '！'
            ) {
                continue
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    /** 查询运行时动态缓存中该艺人对应的另一语种名称，未缓存返回空串。 */
    @JvmStatic
    fun lookup(term: String): String = alternateSearchTerms(term).firstOrNull().orEmpty()

    /** 查询运行时动态缓存的等价艺名组（中文名 ↔ 英文名）。 */
    @JvmStatic
    fun aliasesOf(term: String): List<String> {
        val key = normalizeKey(term)
        if (key.isEmpty()) return emptyList()
        return synchronized(dynamicLock) {
            dynamicGroupsByKey[key]?.toList().orEmpty()
        }
    }

    /** 返回运行时动态缓存中与当前输入不同规范键的跨语种候选名（优先返回另一语种）。 */
    @JvmStatic
    fun alternateSearchTerms(term: String): List<String> {
        val queryKey = normalizeKey(term)
        if (queryKey.isEmpty()) return emptyList()
        val aliases = aliasesOf(term)
        if (aliases.isEmpty()) return emptyList()
        val queryHasHan = hasHanCharacters(term)
        val queryHasLatin = hasLatinLetters(term) && !queryHasHan

        val preferred = ArrayList<String>()
        val fallback = ArrayList<String>()
        val seenKeys = HashSet<String>().apply { add(queryKey) }

        for (alias in aliases) {
            val aKey = normalizeKey(alias)
            if (aKey.isEmpty() || !seenKeys.add(aKey)) continue
            val isOppositeScript = when {
                queryHasHan -> hasLatinLetters(alias) && !hasHanCharacters(alias)
                queryHasLatin -> hasHanCharacters(alias)
                else -> true
            }
            if (isOppositeScript) preferred.add(alias) else fallback.add(alias)
        }
        return preferred + fallback
    }

    /** 计算输入查询词允许精准命中的所有归一化键集合（含运行时动态解析缓存）。 */
    @JvmStatic
    fun exactTargetKeys(query: String): Set<String> {
        val queryKey = normalizeKey(query)
        if (queryKey.isEmpty()) return emptySet()
        val keys = LinkedHashSet<String>()
        keys.add(queryKey)
        for (alias in aliasesOf(query)) {
            val aKey = normalizeKey(alias)
            if (aKey.isNotEmpty()) keys.add(aKey)
        }
        return keys
    }

    /** 提取艺人显示名的可精准比对变体（含跨语种括号合写，如「周杰伦 (Jay Chou)」或「Adele (阿黛尔)」）。 */
    @JvmStatic
    fun extractCandidateNameVariants(name: String): List<String> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return emptyList()
        val variants = ArrayList<String>(3)
        variants.add(trimmed)
        val match = PAREN_BILINGUAL_REGEX.matchEntire(trimmed)
        if (match != null) {
            val outer = match.groupValues[1].trim()
            val inner = match.groupValues[2].trim()
            val outerCjk = hasHanCharacters(outer) || hasJapaneseKana(outer)
            val innerCjk = hasHanCharacters(inner) || hasJapaneseKana(inner)
            val outerLatin = hasLatinLetters(outer) && !outerCjk
            val innerLatin = hasLatinLetters(inner) && !innerCjk
            if ((outerCjk && innerLatin) || (outerLatin && innerCjk)) {
                if (outer.isNotEmpty()) variants.add(outer)
                if (inner.isNotEmpty()) variants.add(inner)
            }
        }
        return variants
    }

    /** 纯规范化直接精准匹配（不含别名缓存）。 */
    @JvmStatic
    fun matchesDirectName(candidateName: String, targetName: String): Boolean {
        val targetKey = normalizeKey(targetName)
        if (targetKey.isEmpty() || candidateName.isBlank()) return false
        return extractCandidateNameVariants(candidateName).any { normalizeKey(it) == targetKey }
    }

    /** 判断候选艺人名是否精准匹配 [targetKeys]（支持简繁/新字体/括号合写与运行时动态缓存）。 */
    @JvmStatic
    fun matchesTargetKeys(candidateName: String, targetKeys: Set<String>): Boolean {
        if (targetKeys.isEmpty() || candidateName.isBlank()) return false
        for (variant in extractCandidateNameVariants(candidateName)) {
            val key = normalizeKey(variant)
            if (key.isEmpty()) continue
            if (key in targetKeys) return true
            val group = synchronized(dynamicLock) { dynamicGroupsByKey[key]?.toList() }
            if (group != null) {
                for (alias in group) {
                    val aKey = normalizeKey(alias)
                    if (aKey.isNotEmpty() && aKey in targetKeys) return true
                }
            }
        }
        return false
    }

    @JvmStatic
    fun matchesArtistName(candidateName: String, query: String): Boolean {
        val targetKeys = exactTargetKeys(query)
        return targetKeys.isNotEmpty() && matchesTargetKeys(candidateName, targetKeys)
    }

    fun size(): Int = synchronized(dynamicLock) { dynamicGroupsByKey.size }
}
