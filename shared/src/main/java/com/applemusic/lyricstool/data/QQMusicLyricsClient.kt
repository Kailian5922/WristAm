package com.applemusic.lyricstool.data

import android.util.Base64
import com.applemusic.android.lyrics.LyricLine
import com.applemusic.android.lyrics.LyricWord
import com.applemusic.android.lyrics.Lyrics
import com.applemusic.android.lyrics.LyricsParser
import com.applemusic.android.util.ChineseText
import com.applemusic.lyricstool.data.qq.DESHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.InflaterInputStream
import kotlin.math.abs

data class LyricSearchCandidate(
    val song: SongHit,
    val lyricSnippet: String = ""
)

class QQMusicLyricsClient {
    private data class SearchMetadata(
        val title: String,
        val artist: String,
        val album: String
    )

    suspend fun searchSongs(term: String): List<SongHit> = withContext(Dispatchers.IO) {
        val clean = term.trim()
        if (clean.isEmpty()) return@withContext emptyList()

        var firstError: Exception? = null
        val desktopSongs = try {
            val block = requestDesktopSearch(clean, searchType = 0, pageSize = 25)
            parseDesktopSongCandidates(block, query = clean, extractLyricSnippet = false)
                .map { it.song }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            firstError = e
            emptyList()
        }
        if (desktopSongs.isNotEmpty()) return@withContext desktopSongs

        // 兜底旧 CGI（无 new_json=1）
        try {
            val legacy = parseLegacySongSearch(requestLegacySearch(clean, searchType = 0))
            if (legacy.isNotEmpty()) return@withContext legacy
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (firstError == null) firstError = e
        }

        firstError?.let { throw it }
        emptyList()
    }

    suspend fun searchSongsByLyrics(term: String): List<SongHit> =
        searchSongsByLyricsWithSnippets(term).map { it.song }

    /**
     * 按歌词片段搜歌（多引擎容灾）：
     * 1. 主通道：QQ 音乐 `music.search.SearchCgiService.DoSearchForQQMusicDesktop` (`search_type = 7`)，
     *    先走带 `zzc` 签名的 `musics.fcg`，失败时退回无 `comm` 的 `musicu.fcg`（自动对偶发 2001 重试）；
     * 2. 备用通道 A：网易云音乐 `type = 1006` 歌词搜索（`cloudsearch/pc` → `search/get/web`）；
     * 3. 备用通道 B：酷狗音乐 `songsearch.kugou.com/lyric_search`。
     */
    suspend fun searchSongsByLyricsWithSnippets(term: String): List<LyricSearchCandidate> = withContext(Dispatchers.IO) {
        val clean = term.trim()
        if (clean.isEmpty()) return@withContext emptyList()

        var firstError: Exception? = null

        // 1) QQ 音乐现代桌面搜索接口（search_type = 7）
        try {
            val block = requestDesktopSearch(clean, searchType = 7, pageSize = 10)
            val qqHits = parseDesktopSongCandidates(block, query = clean, extractLyricSnippet = true)
            if (qqHits.isNotEmpty()) return@withContext qqHits
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            firstError = e
        }

        // 2) 网易云音乐公开歌词检索（type = 1006）
        try {
            val neteaseHits = searchNeteaseByLyrics(clean, limit = 8)
            if (neteaseHits.isNotEmpty()) return@withContext neteaseHits
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (firstError == null) firstError = e
        }

        // 3) 酷狗音乐公开歌词检索
        try {
            val kugouHits = searchKugouByLyrics(clean, limit = 8)
            if (kugouHits.isNotEmpty()) return@withContext kugouHits
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (firstError == null) firstError = e
        }

        firstError?.let { throw it }
        emptyList()
    }

    private suspend fun requestDesktopSearch(
        term: String,
        searchType: Int,
        pageSize: Int
    ): JSONObject {
        val searchId = generateSearchId()
        val param = JSONObject()
            .put("grp", 1)
            .put("num_per_page", pageSize)
            .put("page_num", 1)
            .put("query", term)
            .put("remoteplace", "txt.newclient.top")
            .put("search_type", searchType)
            .put("searchid", searchId)

        val moduleBlock = JSONObject()
            .put("module", SEARCH_MODULE)
            .put("method", "DoSearchForQQMusicDesktop")
            .put("param", param)

        var lastError: Exception? = null

        // 通道 1：签名 musics.fcg（带桌面 comm，官方客户端 2026 现用协议）
        try {
            val signedBody = JSONObject()
                .put(
                    "comm",
                    JSONObject()
                        .put("ct", 19)
                        .put("cv", 2151)
                        .put("v", 2151)
                        .put("_platform", "yqq.json")
                        .put("guid", DEFAULT_GUID)
                        .put("uin", "0")
                )
                .put(SEARCH_MODULE, moduleBlock)
                .toString()
            val sign = zzcSign(signedBody)
            val root = JSONObject(request("$SIGNED_API_URL?sign=$sign", signedBody))
            val block = root.optJSONObject(SEARCH_MODULE)
            val code = root.optInt("code", 0).takeIf { it != 0 } ?: (block?.optInt("code", 0) ?: -1)
            if (code == 0 && block != null && hasSearchList(block)) {
                return block
            }
            if (code != 0) {
                lastError = IllegalStateException("QQ 音乐搜索失败（错误码 $code）")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            lastError = e
        }

        // 通道 2：无 comm 的 musicu.fcg（Web 公开通道要求信封键与模块名一致且不带 comm，偶发 2001 自动重试）
        val unsignedBody = JSONObject()
            .put(SEARCH_MODULE, moduleBlock)
            .toString()
        for (attempt in 0 until 4) {
            try {
                val root = JSONObject(request(API_URL, unsignedBody))
                val block = root.optJSONObject(SEARCH_MODULE)
                val code = root.optInt("code", 0).takeIf { it != 0 } ?: (block?.optInt("code", 0) ?: -1)
                if (code == 0 && block != null) {
                    return block
                }
                if (code == 2001 && attempt < 3) {
                    delay(280L)
                    continue
                }
                lastError = IllegalStateException("QQ 音乐搜索失败（错误码 $code）")
                break
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e
                break
            }
        }

        throw lastError ?: IllegalStateException("QQ 音乐搜索暂时不可用")
    }

    private fun hasSearchList(block: JSONObject): Boolean {
        val body = block.optJSONObject("data")?.optJSONObject("body") ?: return false
        val songList = body.optJSONObject("song")?.optJSONArray("list")
        if (songList != null && songList.length() > 0) return true
        val lyricList = body.optJSONObject("lyric")?.optJSONArray("list")
        return lyricList != null && lyricList.length() > 0
    }

    private fun parseDesktopSongCandidates(
        block: JSONObject,
        query: String,
        extractLyricSnippet: Boolean
    ): List<LyricSearchCandidate> {
        val body = block.optJSONObject("data")?.optJSONObject("body") ?: return emptyList()
        val list = body.optJSONObject("song")?.optJSONArray("list")
            ?.takeIf { it.length() > 0 }
            ?: body.optJSONObject("lyric")?.optJSONArray("list")
            ?: return emptyList()

        return buildList {
            for (index in 0 until list.length()) {
                val raw = list.optJSONObject(index) ?: continue
                val item = raw.optJSONObject("songInfo") ?: raw
                val mid = item.optString("mid").ifBlank { item.optString("songmid") }.trim()
                val songId = item.optString("id")
                    .takeIf { it.isNotBlank() && it != "0" }
                    ?: item.optString("songid").takeIf { it.isNotBlank() && it != "0" }
                val effectiveId = mid.ifBlank { songId.orEmpty() }
                if (effectiveId.isBlank()) continue

                val title = item.optString("name")
                    .ifBlank { item.optString("title") }
                    .ifBlank { item.optString("songname") }
                    .trim()
                if (title.isBlank()) continue

                val artist = extractQqSingers(item)
                val albumObj = item.optJSONObject("album")
                val albumName = albumObj?.let {
                    it.optString("name").ifBlank { it.optString("title") }
                }.orEmpty().ifBlank { item.optString("albumname") }
                val albumMid = albumObj?.let {
                    it.optString("mid").ifBlank { it.optString("pmid") }
                }.orEmpty().ifBlank { item.optString("albummid") }

                val snippet = if (extractLyricSnippet) {
                    extractMatchedLyricSnippet(
                        highlight = raw.optString("lyric_hilight").ifBlank { item.optString("lyric_hilight") },
                        lyric = raw.optString("lyric").ifBlank { item.optString("lyric") },
                        content = raw.optString("content").ifBlank { item.optString("content") },
                        query = query
                    )
                } else {
                    ""
                }

                add(
                    LyricSearchCandidate(
                        song = SongHit(
                            id = effectiveId,
                            title = ChineseText.toSimplified(title),
                            artist = ChineseText.toSimplified(artist.ifBlank { "未知艺人" }),
                            album = ChineseText.toSimplified(albumName),
                            artworkUrl = albumMid.takeIf { it.isNotBlank() }?.let {
                                "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg"
                            }.orEmpty(),
                            durationMs = item.optLong("interval") * 1000L,
                            providerSongId = songId
                        ),
                        lyricSnippet = snippet
                    )
                )
            }
        }.distinctBy { it.song.id }
    }

    private fun extractQqSingers(item: JSONObject): String {
        val singersArr = item.optJSONArray("singer")
        if (singersArr != null && singersArr.length() > 0) {
            val joined = (0 until singersArr.length())
                .mapNotNull { singersArr.optJSONObject(it)?.optString("name")?.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            if (joined.isNotBlank()) return joined
        }
        val rawSinger = item.optString("singer").trim()
        if (rawSinger.isNotBlank() && !rawSinger.startsWith("[") && !rawSinger.startsWith("{")) {
            return rawSinger
        }
        return item.optString("content")
            .lineSequence()
            .firstOrNull()
            ?.let { line ->
                Regex("""^\s*.+?\s+-\s+(.+?)\s*(?:\([^)]*\))?\s*$""")
                    .find(line)?.groupValues?.get(1)
                    ?.replace(Regex("""\s*\([^)]*\)\s*$"""), "")
                    ?.trim()
            }
            .orEmpty()
    }

    private fun searchNeteaseByLyrics(term: String, limit: Int): List<LyricSearchCandidate> {
        val encoded = URLEncoder.encode(term, Charsets.UTF_8.name())
        val urls = listOf(
            "https://music.163.com/api/cloudsearch/pc?s=$encoded&type=1006&offset=0&limit=$limit",
            "https://music.163.com/api/search/get/web?s=$encoded&type=1006&offset=0&limit=$limit"
        )
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val text = request(url, referer = "https://music.163.com/")
                val root = JSONObject(text)
                if (root.optInt("code", -1) != 200) continue
                val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: continue
                val parsed = parseNeteaseSongs(songs, term)
                if (parsed.isNotEmpty()) return parsed
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        return emptyList()
    }

    private fun parseNeteaseSongs(songs: JSONArray, query: String): List<LyricSearchCandidate> = buildList {
        for (i in 0 until songs.length()) {
            val item = songs.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val title = item.optString("name").trim()
            if (id.isBlank() || title.isBlank()) continue

            val artistsArr = item.optJSONArray("ar") ?: item.optJSONArray("artists")
            val artist = (0 until (artistsArr?.length() ?: 0))
                .mapNotNull { artistsArr?.optJSONObject(it)?.optString("name")?.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" / ")
                .ifBlank { "未知艺人" }

            val albumObj = item.optJSONObject("al") ?: item.optJSONObject("album")
            val albumName = albumObj?.optString("name").orEmpty().trim()
            val coverUrl = albumObj?.optString("picUrl").orEmpty().replace("http://", "https://")
            val durationMs = item.optLong("dt").takeIf { it > 0L } ?: item.optLong("duration")

            val lyricsObj = item.optJSONObject("lyrics")
            val rawLyricsText = when {
                lyricsObj != null -> {
                    val arr = lyricsObj.optJSONArray("txt")
                    if (arr != null) {
                        (0 until arr.length())
                            .mapNotNull { arr.optString(it) }
                            .joinToString("\n")
                    } else {
                        lyricsObj.optString("txt")
                    }
                }
                else -> {
                    val arr = item.optJSONArray("lyrics")
                    (0 until (arr?.length() ?: 0))
                        .mapNotNull { arr?.optString(it) }
                        .joinToString("\n")
                }
            }
            val snippet = extractMatchedLyricSnippet(
                highlight = rawLyricsText,
                lyric = "",
                content = "",
                query = query
            )

            add(
                LyricSearchCandidate(
                    song = SongHit(
                        id = "wy:$id",
                        title = ChineseText.toSimplified(title),
                        artist = ChineseText.toSimplified(artist),
                        album = ChineseText.toSimplified(albumName),
                        artworkUrl = coverUrl,
                        durationMs = durationMs
                    ),
                    lyricSnippet = snippet
                )
            )
        }
    }.distinctBy { "${it.song.title}|${it.song.artist}" }

    private fun searchKugouByLyrics(term: String, limit: Int): List<LyricSearchCandidate> {
        val encoded = URLEncoder.encode(term, Charsets.UTF_8.name())
        val url = "https://songsearch.kugou.com/lyric_search?keyword=$encoded&page=1&pagesize=$limit"
        val root = JSONObject(request(url, referer = "https://www.kugou.com/"))
        val lists = root.optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
        return buildList {
            for (i in 0 until lists.length()) {
                val item = lists.optJSONObject(i) ?: continue
                val hash = item.optString("FileHash").trim()
                val title = stripHtmlTags(item.optString("SongName")).trim()
                if (title.isBlank()) continue
                val artist = stripHtmlTags(item.optString("SingerName")).trim().ifBlank { "未知艺人" }
                val album = stripHtmlTags(item.optString("AlbumName")).trim()
                val lyric = item.optString("Lyric")
                val snippet = extractMatchedLyricSnippet(
                    highlight = "",
                    lyric = lyric.replace("  ", "\n"),
                    content = "",
                    query = term
                )
                add(
                    LyricSearchCandidate(
                        song = SongHit(
                            id = if (hash.isNotBlank()) "kg:$hash" else "kg:$title|$artist",
                            title = ChineseText.toSimplified(title),
                            artist = ChineseText.toSimplified(artist),
                            album = ChineseText.toSimplified(album),
                            artworkUrl = "",
                            durationMs = item.optLong("TimeLength") * 1000L
                        ),
                        lyricSnippet = snippet
                    )
                )
            }
        }.distinctBy { "${it.song.title}|${it.song.artist}" }
    }

    private fun extractMatchedLyricSnippet(
        highlight: String,
        lyric: String,
        content: String,
        query: String
    ): String {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        val candidates = listOf(highlight, lyric, content)
        for (raw in candidates) {
            if (raw.isBlank()) continue
            val lines = raw.replace("\r\n", "\n").split('\n')
            // 1. 优先命中带有高亮标签（<em> / <b>）的行
            val emLine = lines.firstOrNull {
                it.contains("<em", ignoreCase = true) || it.contains("<b", ignoreCase = true)
            }
            if (emLine != null) {
                val cleaned = stripHtmlTags(emLine).trim()
                if (cleaned.isNotBlank() && !isLyricCreditLine(cleaned)) {
                    return ChineseText.toSimplified(cleaned)
                }
            }
            // 2. 次选包含查询词的正文行（跳过“歌名 - 歌手”和“词/曲/编曲”抬头行）
            if (cleanQuery.isNotEmpty()) {
                val matched = lines.map { stripHtmlTags(it).trim() }.firstOrNull { line ->
                    line.isNotBlank() &&
                        !isLyricCreditLine(line) &&
                        line.lowercase(Locale.ROOT).contains(cleanQuery)
                }
                if (matched != null) return ChineseText.toSimplified(matched)
            }
            // 3. 回退到第一条非抬头正文行
            val firstLyric = lines.map { stripHtmlTags(it).trim() }.firstOrNull { line ->
                line.isNotBlank() && !isLyricCreditLine(line) && !line.contains(" - ")
            }
            if (firstLyric != null) return ChineseText.toSimplified(firstLyric)
        }
        return ""
    }

    private fun isLyricCreditLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return true
        return Regex("""^(作词|作曲|词|曲|编曲|制作人|演唱|歌手|混音|母带|出品|发行|原唱|Lyricist|Composer|Arranger)\s*[:：]""", RegexOption.IGNORE_CASE)
            .containsMatchIn(t)
    }

    private fun stripHtmlTags(text: String): String =
        text.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")

    private fun generateSearchId(): String =
        (UUID.randomUUID().toString().replace("-", "") + "12345")
            .take(37)
            .uppercase(Locale.ROOT)

    private fun zzcSign(text: String): String {
        val p1Idx = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
        val p2Idx = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
        val scramble = intArrayOf(
            89, 39, 179, 150, 218, 82, 58, 252, 177, 52,
            186, 123, 120, 64, 242, 133, 143, 161, 121, 179
        )
        val sha1Bytes = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val hex = buildString(sha1Bytes.size * 2) {
            for (b in sha1Bytes) {
                val v = b.toInt() and 0xFF
                append(HEX_CHARS[v ushr 4])
                append(HEX_CHARS[v and 0x0F])
            }
        }
        val p1 = buildString {
            for (idx in p1Idx) if (idx < hex.length) append(hex[idx])
        }
        val p2 = buildString {
            for (idx in p2Idx) if (idx < hex.length) append(hex[idx])
        }
        val xored = ByteArray(scramble.size) { i ->
            (scramble[i] xor (sha1Bytes[i].toInt() and 0xFF)).toByte()
        }
        val b64 = Base64.encodeToString(xored, Base64.NO_WRAP)
            .replace("+", "")
            .replace("/", "")
            .replace("=", "")
        return "zzc$p1$b64$p2".lowercase(Locale.ROOT)
    }

    private fun requestLegacySearch(term: String, searchType: Int): JSONObject {
        val encodedTerm = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
        val pageSize = if (searchType == 7) 10 else 25
        val root = JSONObject(
            request("$SEARCH_URL?format=json&p=1&n=$pageSize&w=$encodedTerm&t=$searchType")
        )
        if (root.optInt("code", 0) != 0) {
            throw IllegalStateException("QQ 音乐搜索失败（错误码 ${root.optInt("code")}）")
        }
        return root
    }

    private fun parseLegacySongSearch(root: JSONObject): List<SongHit> {
        val songs = root.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list")
            ?: return emptyList()
        return buildList {
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val mid = song.optString("songmid").takeIf { it.isNotBlank() } ?: continue
                val singers = song.optJSONArray("singer")
                val artist = (0 until (singers?.length() ?: 0))
                    .mapNotNull { singers?.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString(" / ")
                    .ifBlank { "未知艺人" }
                val albumMid = song.optString("albummid")
                add(
                    SongHit(
                        id = mid,
                        title = ChineseText.toSimplified(song.optString("songname", "未知歌曲")),
                        artist = ChineseText.toSimplified(artist),
                        album = ChineseText.toSimplified(song.optString("albumname")),
                        artworkUrl = albumMid.takeIf { it.isNotBlank() }?.let {
                            "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg"
                        }.orEmpty(),
                        durationMs = song.optLong("interval") * 1000L,
                        providerSongId = song.optString("songid").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    private suspend fun fetchLyrics(song: SongHit): FetchedLyrics? = withContext(Dispatchers.IO) {
        fetchModernLyrics(song)
    }

    suspend fun fetchMatchingLyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String = "",
        localizedAliases: List<SongHit> = emptyList()
    ): FetchedLyrics? = withContext(Dispatchers.IO) {
        val normalizedTitle = normalizeMatchText(title)
        if (normalizedTitle.isBlank()) return@withContext null
        val metadata = (listOf(SearchMetadata(title, artist, album)) +
            localizedAliases.map { SearchMetadata(it.title, it.artist, it.album) }).distinctBy {
            listOf(it.title, it.artist, it.album).joinToString("|") { value ->
                normalizeMatchText(value)
            }
        }
        val titles = metadata.map { normalizeMatchText(it.title) }.filter { it.isNotBlank() }
        val artistGroups = metadata.map { splitArtists(it.artist) }.filter { it.isNotEmpty() }
        val albums = metadata.map { normalizeMatchText(it.album) }.filter { it.isNotBlank() }
        var firstError: Exception? = null
        val triedIds = HashSet<String>()
        var lineTimedFallback: FetchedLyrics? = null
        var fetchAttempts = 0

        // 优先用“歌名 歌手”提高一次命中率，避免低效多次串行网络请求
        val queries = metadata
            .flatMap { listOf("${it.title} ${it.artist}".trim(), it.title.trim()) }
            .filter { it.isNotBlank() }
            .distinctBy(::normalizeMatchText)
            .take(3)

        for ((queryIndex, query) in queries.withIndex()) {
            if (fetchAttempts >= MAX_LYRIC_FETCH_ATTEMPTS) break
            val matches = try {
                searchSongs(query)
                    .mapIndexedNotNull { rank, candidate ->
                        rankCandidate(
                            candidate = candidate,
                            rank = rank,
                            queryIndex = queryIndex,
                            titles = titles,
                            artistGroups = artistGroups,
                            albums = albums,
                            durationMs = durationMs
                        )
                    }
                    .sortedWith(
                        compareByDescending<RankedCandidate> { it.score }
                            .thenBy { it.durationDifferenceMs }
                            .thenBy { it.rank }
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (firstError == null) firstError = error
                emptyList()
            }

            for (match in matches.take(2)) {
                if (fetchAttempts >= MAX_LYRIC_FETCH_ATTEMPTS) break
                if (!triedIds.add(match.song.id)) continue
                fetchAttempts++
                try {
                    fetchLyrics(match.song)?.let { lyrics ->
                        if (lyrics.lyrics.wordTimedLines() > 0) return@withContext lyrics
                        if (lineTimedFallback == null) lineTimedFallback = lyrics
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (firstError == null) firstError = error
                }
            }
            // 已命中高置信度行级歌词且已尝试足够次数则提前结束
            if (lineTimedFallback != null && matches.firstOrNull()?.score?.let { it >= 145 } == true) {
                break
            }
        }
        lineTimedFallback?.let { return@withContext it }
        firstError?.let { throw it }
        null
    }

    private fun rankCandidate(
        candidate: SongHit,
        rank: Int,
        queryIndex: Int,
        titles: List<String>,
        artistGroups: List<List<String>>,
        albums: List<String>,
        durationMs: Long
    ): RankedCandidate? {
        val candidateTitle = normalizeMatchText(candidate.title)
        val titleMatches = candidateTitle.isNotBlank() && titles.any { title ->
            candidateTitle == title ||
                (title.length > 3 && candidateTitle.contains(title)) ||
                (candidateTitle.length > 3 && title.contains(candidateTitle))
        }
        val candidateArtists = splitArtists(candidate.artist)
        val artistMatches = artistGroups.any { artists ->
            candidateArtists.any { candidateArtist ->
                artists.any {
                    it == candidateArtist ||
                        (candidateArtist.length >= 2 && it.contains(candidateArtist)) ||
                        (it.length >= 2 && candidateArtist.contains(it))
                }
            }
        }
        val candidateAlbum = normalizeMatchText(candidate.album)
        val albumMatches = candidateAlbum.isNotBlank() && albums.any { album ->
            album == candidateAlbum ||
                (candidateAlbum.length > 3 && album.contains(candidateAlbum)) ||
                (album.length > 3 && candidateAlbum.contains(album))
        }
        val durationDifference = if (durationMs > 0L && candidate.durationMs > 0L) {
            abs(candidate.durationMs - durationMs)
        } else {
            Long.MAX_VALUE
        }
        if (durationDifference != Long.MAX_VALUE && durationDifference > 45_000L) return null

        val artistAndDurationMatch = !titleMatches &&
            artistMatches &&
            durationDifference <= TITLE_ONLY_DURATION_TOLERANCE_MS
        val albumAndDurationMatch = albumMatches &&
            durationDifference <= ALBUM_MATCH_DURATION_TOLERANCE_MS
        if (!titleMatches && !artistAndDurationMatch && !albumAndDurationMatch) {
            return null
        }
        if (titleMatches && artistGroups.isNotEmpty() && !artistMatches &&
            durationDifference > TITLE_ONLY_DURATION_TOLERANCE_MS
        ) {
            return null
        }

        val score = (if (titleMatches) 100 else 0) +
            (if (artistMatches && artistGroups.isNotEmpty()) 45 else 0) +
            (if (albumMatches) 25 else 0) +
            when {
                durationDifference <= 1_500L -> 45
                durationDifference <= 5_000L -> 35
                durationDifference <= 15_000L -> 20
                durationDifference <= 45_000L -> 5
                else -> 0
            } +
            maxOf(0, 12 - rank * 2) -
            queryIndex * 5

        return RankedCandidate(
            song = candidate,
            score = score,
            durationDifferenceMs = durationDifference,
            rank = rank
        )
    }

    private fun normalizeMatchText(text: String): String =
        text.lowercase()
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\[[\s\S]*?\]"""), "")
            .replace(Regex("""\{[^}]*\}"""), "")
            .replace(Regex("""[\p{P}\p{S}\s]+"""), "")

    /** 支持单字符艺名（如 "V"、"K"）。 */
    private fun splitArtists(text: String): List<String> =
        Regex("[,/&、，;]+")
            .split(text)
            .map(::normalizeMatchText)
            .filter { it.isNotEmpty() }

    private data class RankedCandidate(
        val song: SongHit,
        val score: Int,
        val durationDifferenceMs: Long,
        val rank: Int
    )

    private fun fetchModernLyrics(song: SongHit): FetchedLyrics? {
        fun base64(value: String): String =
            Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val param = JSONObject()
            .put("songMID", song.id)
            .put("songName", base64(song.title))
            .put("albumName", base64(song.album))
            .put("singerName", base64(song.artist))
            .put("crypt", 1)
            .put("qrc", 1)
            .put("trans", 1)
            .put("roma", 1)
            .put("cv", 2111)
            .put("ct", 19)
            .put("lrc_t", 0)
            .put("qrc_t", 0)
            .put("roma_t", 0)
            .put("trans_t", 0)
            .put("type", 0)
            .put("interval", song.durationMs / 1000L)
        song.providerSongId?.toLongOrNull()?.let { param.put("songID", it) }
        val request = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("ct", 11)
                    .put("cv", 1003006)
                    .put("v", 1003006)
                    .put("format", "json")
                    .put("tmeAppID", "qqmusiclight")
                    .put("uin", "0")
            )
            .put(
                "req_0",
                JSONObject()
                    .put("module", "music.musichallSong.PlayLyricInfo")
                    .put("method", "GetPlayLyricInfo")
                    .put("param", param)
            )
        val root = JSONObject(postJson(request.toString()))
        val response = root.optJSONObject("req_0")
            ?: throw IllegalStateException("QQ 音乐歌词接口返回了无效数据")
        if (response.optInt("code", 0) != 0) {
            throw IllegalStateException("QQ 音乐暂时无法提供这首歌的歌词（错误码 ${response.optInt("code")}）")
        }
        val data = response.optJSONObject("data")
            ?: throw IllegalStateException("QQ 音乐歌词接口没有返回歌词数据")
        val originalPayload = firstPayload(data, "lyric") ?: return null
        val original = decryptPayload(originalPayload) ?: return null
        val lyrics = parseLyrics(original, "qq-qrc") ?: return null

        var translationWarning: String? = null
        val translation = firstPayload(data, "trans")?.let { payload ->
            try {
                decryptPayload(payload)
            } catch (error: Exception) {
                translationWarning = error.message ?: "QQ 音乐译文无法解码"
                null
            }
        }
        val translatedLyrics = translation?.let { parseLyrics(it, "qq-translation") }
        return FetchedLyrics(
            lyrics = mergeQqTranslations(lyrics, translatedLyrics),
            ttml = null,
            translationWarning = translationWarning,
            qqQrcDebugText = null
        )
    }

    /**
     * 对齐 QQ 译文：
     * 1. 先剔除空白行、`"//"` 占位符与无效时间戳行（`startMs < 0`）；
     * 2. 优先匹配精确/近似（≤400ms），再放宽到 ≤1000ms，避免信用行抢占唱词译文。
     */
    private fun mergeQqTranslations(original: Lyrics, translated: Lyrics?): Lyrics {
        if (translated == null) return original
        val translatedLines = translated.lines
            .filter { line ->
                line.startMs >= 0L &&
                    line.text.isNotBlank() &&
                    line.text.trim() != "//"
            }
            .sortedBy { it.startMs }
        if (translatedLines.isEmpty()) return original

        val usedTranslations = BooleanArray(translatedLines.size)
        val assigned = arrayOfNulls<String>(original.lines.size)

        fun matchPass(maxToleranceMs: Long) {
            for (origIdx in original.lines.indices) {
                if (assigned[origIdx] != null) continue
                val line = original.lines[origIdx]
                if (line.isBlank || line.startMs < 0L) continue
                val matchIndex = translatedLines.indices
                    .asSequence()
                    .filter { idx ->
                        !usedTranslations[idx] &&
                            abs(translatedLines[idx].startMs - line.startMs) <= maxToleranceMs
                    }
                    .minWithOrNull(
                        compareBy<Int> { idx -> abs(translatedLines[idx].startMs - line.startMs) }
                            .thenBy { idx -> translatedLines[idx].startMs }
                    )
                if (matchIndex != null) {
                    usedTranslations[matchIndex] = true
                    assigned[origIdx] = translatedLines[matchIndex].text
                }
            }
        }

        matchPass(400L)
        matchPass(TRANSLATION_TIME_TOLERANCE_MS)

        val mergedLines = original.lines.mapIndexed { idx, line ->
            val trans = assigned[idx]?.takeIf { it.isNotBlank() && it != line.text }
            if (trans == null) {
                line
            } else {
                LyricLine(
                    line.startMs,
                    line.endMs,
                    line.text,
                    line.words,
                    translation = trans,
                    key = line.key
                )
            }
        }
        return Lyrics(mergedLines, original.synced, original.kind)
    }

    private fun firstPayload(data: JSONObject, vararg keys: String): String? =
        keys.asSequence()
            .map { data.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() && it != "0" && it != "null" }

    /**
     * 解密 QRC 密文，并自动兼容 QQ 音乐偶尔返回的明文 LRC 或 Base64 载荷。
     */
    private fun decryptPayload(payload: String): String? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null
        // 明文 LRC 或明文 XML 直接提取
        if (trimmed.startsWith("[") || trimmed.startsWith("<")) {
            return extractXmlLyricContent(trimmed)
        }
        val digits = trimmed.filterNot(Char::isWhitespace)
        val isHexQrc = digits.isNotEmpty() && digits.length % 16 == 0 &&
            digits.all { it.digitToIntOrNull(16) != null }
        if (!isHexQrc) {
            // 尝试 Base64 明文回落
            val decoded = runCatching {
                Base64.decode(trimmed, Base64.DEFAULT).toString(Charsets.UTF_8).trimStart('\uFEFF')
            }.getOrNull()
            if (!decoded.isNullOrBlank() && (decoded.contains("[") || decoded.contains("<Lyric_1"))) {
                return extractXmlLyricContent(decoded)
            }
            return null
        }

        val encrypted = ByteArray(digits.length / 2) { index ->
            digits.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val schedule = Array(3) { Array(16) { ByteArray(6) } }
        DESHelper.tripleDESKeySetup(
            "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII),
            schedule,
            DESHelper.DECRYPT
        )
        val decrypted = ByteArray(encrypted.size)
        var offset = 0
        while (offset < encrypted.size) {
            val block = encrypted.copyOfRange(offset, offset + 8)
            DESHelper.tripleDESCrypt(block, block, schedule)
            block.copyInto(decrypted, offset)
            offset += 8
        }
        val output = ByteArrayOutputStream()
        try {
            InflaterInputStream(ByteArrayInputStream(decrypted)).use { inflater ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = try {
                        inflater.read(buffer)
                    } catch (eof: EOFException) {
                        // DES 尾部补零可能触发流末尾 EOFException；若已解出有效字节则接受
                        if (output.size() > 0) break else throw eof
                    }
                    if (count < 0) break
                    if (output.size() + count > MAX_DECOMPRESSED_BYTES) {
                        throw IllegalStateException("QQ 音乐歌词解压后过大")
                    }
                    output.write(buffer, 0, count)
                }
            }
        } catch (error: Exception) {
            if (output.size() == 0) {
                throw IllegalStateException("QQ 音乐 QRC 歌词解密失败", error)
            }
        }
        val decryptedText = output.toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF')
        return extractXmlLyricContent(decryptedText)
    }

    /**
     * 安全提取 `<Lyric_1 ... LyricContent="...">`：贪婪定位最后一个关闭引号，
     * 防止歌词内未转义双引号截断整首歌歌词。
     */
    private fun extractXmlLyricContent(text: String): String {
        val markerIdx = text.indexOf("LyricContent=\"", ignoreCase = true)
        if (markerIdx >= 0) {
            val start = markerIdx + "LyricContent=\"".length
            val closeTagIdx = text.lastIndexOf("/>")
            val searchLimit = if (closeTagIdx > start) closeTagIdx else text.length
            val endQuote = text.lastIndexOf('"', searchLimit - 1)
            if (endQuote > start) {
                return LyricsParser.unescape(text.substring(start, endQuote))
            }
        }
        return text
    }

    private fun parseLyrics(content: String, kind: String): Lyrics? {
        val linePattern = Regex("""^\[(\d+),(\d+)](.*)$""")
        if (content.lineSequence().none { linePattern.matches(it.trim()) }) {
            return LyricsParser.parsePlain(content, kind)
        }
        val wordPattern = Regex("""\((\d+),(\d+)\)""")
        val lines = content.lineSequence().mapNotNull { rawLine ->
            val match = linePattern.matchEntire(rawLine.trim()) ?: return@mapNotNull null
            val lineStart = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val lineDuration = match.groupValues[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val body = match.groupValues[3]
            val text = StringBuilder()
            val words = ArrayList<LyricWord>()
            var cursor = 0
            for (marker in wordPattern.findAll(body)) {
                val segment = body.substring(cursor, marker.range.first)
                val charStart = text.length
                text.append(segment)
                val trimmedStart = segment.indexOfFirst { !it.isWhitespace() }
                val trimmedEnd = segment.indexOfLast { !it.isWhitespace() }
                val startMs = marker.groupValues[1].toLongOrNull() ?: lineStart
                val durationMs = marker.groupValues[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                if (trimmedStart >= 0) {
                    words.add(
                        LyricWord(
                            startMs,
                            startMs + durationMs,
                            charStart + trimmedStart,
                            charStart + trimmedEnd + 1
                        )
                    )
                }
                cursor = marker.range.last + 1
            }
            text.append(body.substring(cursor))
            val lyricText = text.toString().trim()
            if (lyricText.isBlank()) {
                // 保留明确的间奏空行（>=3s）供呼吸点渲染
                if (lineDuration >= 3_000L) {
                    return@mapNotNull LyricLine(lineStart, lineStart + lineDuration, "", emptyList())
                }
                return@mapNotNull null
            }
            val trimStart = text.indexOf(lyricText)
            val trimEnd = trimStart + lyricText.length
            val adjustedWords = words.mapNotNull { word ->
                val start = maxOf(word.charStart, trimStart)
                val end = minOf(word.charEnd, trimEnd)
                if (end <= start) null else {
                    LyricWord(word.startMs, word.endMs, start - trimStart, end - trimStart)
                }
            }
            LyricLine(
                lineStart,
                lineStart + lineDuration,
                lyricText,
                adjustedWords
            )
        }.sortedBy { it.startMs }.toList()
        return if (lines.isEmpty()) LyricsParser.parsePlain(content, kind)
        else Lyrics(lines, synced = true, kind = kind)
    }

    private fun postJson(body: String): String = request(API_URL, body)

    private fun request(
        url: String,
        body: String? = null,
        referer: String = "https://y.qq.com/"
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (body == null) {
                connection.requestMethod = "GET"
            } else {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            connection.setRequestProperty("Referer", referer)
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
            if (body != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("请求失败（HTTP $code）")
            }
            return response
        } catch (t: Throwable) {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    private companion object {
        const val API_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val SIGNED_API_URL = "https://u.y.qq.com/cgi-bin/musics.fcg"
        const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        const val SEARCH_MODULE = "music.search.SearchCgiService"
        const val DEFAULT_GUID = "1F70E520B2EAA7D25E11760783C53CA9"
        val HEX_CHARS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
        )
        const val MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024
        const val MAX_LYRIC_FETCH_ATTEMPTS = 4
        const val TRANSLATION_TIME_TOLERANCE_MS = 1_000L
        const val ALBUM_MATCH_DURATION_TOLERANCE_MS = 5_000L
        const val TITLE_ONLY_DURATION_TOLERANCE_MS = 5_000L
    }
}
