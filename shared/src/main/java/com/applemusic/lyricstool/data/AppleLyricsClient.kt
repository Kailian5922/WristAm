package com.applemusic.lyricstool.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.applemusic.android.lyrics.Lyrics
import com.applemusic.android.lyrics.LyricsParser
import com.applemusic.android.util.ChineseText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class SongHit(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val durationMs: Long,
    val providerSongId: String? = null,
    val isrc: String? = null
)

data class FetchedLyrics(
    val lyrics: Lyrics,
    val ttml: String?,
    val translationWarning: String? = null,
    val qqQrcDebugText: String? = null
)

class AppleLyricsClient(
    context: Context,
    private val secureUserTokenStorage: AppleMusicUserTokenStorage? = null
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("apple_music_auth", Context.MODE_PRIVATE).also { shared ->
            if (secureUserTokenStorage == null) {
                val previous = context.getSharedPreferences("lyrics_tool_auth", Context.MODE_PRIVATE)
                if (shared.getString("media_user_token", null) == null) {
                    val oldToken = previous.getString("media_user_token", null)
                    if (!oldToken.isNullOrBlank()) {
                        shared.edit()
                            .putString("media_user_token", oldToken.trim())
                            .putString("developer_token", previous.getString("developer_token", ""))
                            .putString("storefront", previous.getString("storefront", "cn"))
                            .apply()
                    }
                }
            }
        }

    private val devTokenLock = Any()

    @Volatile
    var developerToken: String = preferences.getString("developer_token", "").orEmpty().trim()
        private set

    @Volatile
    var userToken: String = (secureUserTokenStorage?.load()
        ?: preferences.getString("media_user_token", "").orEmpty()).trim()
        private set

    @Volatile
    var storefront: String = (preferences.getString("storefront", "cn") ?: "cn").trim().lowercase(Locale.ROOT)

    @Volatile
    var lastSearchLocalizationWarning: String? = null
        private set

    /** 统一 >= 50 长度判定标准（与 EncryptedTokenStore / AppleApi 对齐）。 */
    val isSignedIn: Boolean
        get() = userToken.trim().length >= 50

    fun reloadSavedSession() {
        developerToken = preferences.getString("developer_token", "").orEmpty().trim()
        userToken = (secureUserTokenStorage?.load()
            ?: preferences.getString("media_user_token", "").orEmpty()).trim()
        val sf = (preferences.getString("storefront", "cn") ?: "cn").trim().lowercase(Locale.ROOT)
        storefront = if (sf.matches(Regex("[a-z]{2}"))) sf else "cn"
    }

    fun updateStorefront(region: String) {
        val clean = region.trim().lowercase(Locale.ROOT)
        if (clean.matches(Regex("[a-z]{2}"))) {
            storefront = clean
            preferences.edit().putString("storefront", clean).apply()
        }
    }

    fun saveUserToken(token: String) {
        val clean = token.trim()
        userToken = clean
        if (secureUserTokenStorage != null) {
            secureUserTokenStorage.save(clean)
        } else {
            preferences.edit().putString("media_user_token", clean).apply()
        }
    }

    fun logout() {
        userToken = ""
        storefront = "cn"
        if (secureUserTokenStorage != null) {
            secureUserTokenStorage.clear()
        } else {
            preferences.edit().remove("media_user_token").apply()
        }
        preferences.edit().remove("storefront").apply()
    }

    fun extractUserToken(cookie: String): String =
        Regex("media-user-token=([^;\\s]+)").find(cookie)?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.length >= 50 }
            .orEmpty()

    suspend fun prepare() = withContext(Dispatchers.IO) {
        ensureDeveloperToken()
    }

    suspend fun refreshStorefront() = withContext(Dispatchers.IO) {
        ensureDeveloperToken()
        var region = try {
            val response = JSONObject(request("/v1/me/account?meta=subscription"))
            response.optJSONObject("meta")
                ?.optJSONObject("subscription")
                ?.optString("storefront")
                ?.trim()
                ?.lowercase(Locale.ROOT)
                .orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        if (!region.matches(Regex("[a-z]{2}"))) {
            region = try {
                val sfResp = JSONObject(request("/v1/me/storefront"))
                sfResp.optJSONArray("data")?.optJSONObject(0)
                    ?.optString("id")
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    .orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ""
            }
        }
        if (region.matches(Regex("[a-z]{2}"))) {
            updateStorefront(region)
        }
    }

    suspend fun searchSongs(term: String): List<SongHit> = withContext(Dispatchers.IO) {
        ensureDeveloperToken()
        lastSearchLocalizationWarning = null
        val sf = effectiveStorefront()
        val storefrontLanguages = when (sf) {
            "cn", "zh" -> listOf("zh-Hans-CN", "en-US")
            "jp" -> listOf("ja-JP", "en-US")
            "kr" -> listOf("ko-KR", "en-US")
            "tw", "hk" -> listOf("zh-Hant-TW", "en-US")
            "tr" -> listOf("tr-TR", "en-US")
            else -> listOf("en-US")
        }
        val languages = (listOf("zh-Hans-CN") + storefrontLanguages).distinct()
        val results = LinkedHashMap<String, SongHit>()
        var successfulRequests = 0
        var firstError: Throwable? = null

        for (language in languages) {
            try {
                val encodedTerm = URLEncoder.encode(term.trim(), Charsets.UTF_8.name()).replace("+", "%20")
                val response = request(
                    "/v1/catalog/$sf/search?term=$encodedTerm&types=songs&limit=30&l=$language"
                )
                val root = JSONObject(response)
                successfulRequests++
                val songs = root.optJSONObject("results")
                    ?.optJSONObject("songs")?.optJSONArray("data") ?: continue
                for (i in 0 until songs.length()) {
                    val song = songs.optJSONObject(i) ?: continue
                    val attributes = song.optJSONObject("attributes") ?: continue
                    val id = song.optString("id")
                    if (id.isBlank()) continue
                    val artwork = attributes.optJSONObject("artwork")?.optString("url").orEmpty()
                        .replace("{w}", "160")
                        .replace("{h}", "160")
                        .replace("{f}", "jpg")
                    results.putIfAbsent(
                        id,
                        SongHit(
                            id = id,
                            title = ChineseText.toSimplified(attributes.optString("name", "未知歌曲")),
                            artist = ChineseText.toSimplified(attributes.optString("artistName", "未知艺人")),
                            album = ChineseText.toSimplified(attributes.optString("albumName")),
                            artworkUrl = artwork,
                            durationMs = attributes.optLong("durationInMillis"),
                            isrc = attributes.optString("isrc").takeIf { it.isNotBlank() }
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (firstError == null) firstError = error
            }
        }

        if (successfulRequests == 0) {
            throw firstError ?: IllegalStateException("Apple Music 搜索暂时不可用")
        }
        localizeSearchResults(term, results.values.toList())
    }

    suspend fun searchSongsByLyrics(
        term: String,
        qqMusicClient: QQMusicLyricsClient
    ): List<SongHit> = withContext(Dispatchers.IO) {
        ensureDeveloperToken()
        val lyricCandidates = qqMusicClient.searchSongsByLyrics(term).take(6)
        val matches = LinkedHashMap<String, Pair<Int, SongHit>>()
        var firstSearchError: Exception? = null

        for ((rank, candidate) in lyricCandidates.withIndex()) {
            val query = listOf(
                candidate.title,
                candidate.artist.takeUnless { it == "未知艺人" }
            ).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
            if (query.isBlank()) continue
            try {
                val appleSongs = searchSongs(query)
                val expectedTitle = normalizeSongText(candidate.title)
                val expectedArtists = candidate.artist
                    .split(Regex("[,/&、，;]+"))
                    .map(::normalizeSongText)
                    .filter { it.isNotEmpty() }
                for (song in appleSongs) {
                    val actualTitle = normalizeSongText(song.title)
                    val titleMatches = expectedTitle.isNotBlank() &&
                        actualTitle.isNotBlank() &&
                        (actualTitle == expectedTitle ||
                            (expectedTitle.length >= 3 && actualTitle.contains(expectedTitle)) ||
                            (actualTitle.length >= 3 && expectedTitle.contains(actualTitle)))
                    val actualArtist = normalizeSongText(song.artist)
                    val artistMatches = actualArtist.isNotBlank() &&
                        expectedArtists.any { artist ->
                            actualArtist == artist ||
                                (artist.length >= 2 && actualArtist.contains(artist)) ||
                                (actualArtist.length >= 2 && artist.contains(actualArtist))
                        }
                    val durationDifference = if (candidate.durationMs > 0L && song.durationMs > 0L) {
                        kotlin.math.abs(candidate.durationMs - song.durationMs)
                    } else {
                        Long.MAX_VALUE
                    }
                    val durationMatches = durationDifference <= 8_000L
                    val credibleMatch =
                        (titleMatches && (artistMatches || durationMatches || expectedArtists.isEmpty())) ||
                            (artistMatches && durationMatches)
                    if (!credibleMatch) continue
                    val score = (if (titleMatches) 100 else 0) +
                        (if (artistMatches) 40 else 0) +
                        when {
                            durationDifference <= 2_000L -> 30
                            durationDifference <= 8_000L -> 15
                            else -> 0
                        } - rank
                    val previous = matches[song.id]
                    if (previous == null || score > previous.first) {
                        matches[song.id] = score to song
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (firstSearchError == null) firstSearchError = error
            }
        }

        if (matches.isEmpty() && firstSearchError != null) throw firstSearchError
        matches.values.sortedByDescending { it.first }.map { it.second }
    }

    private fun normalizeSongText(text: String): String =
        text.lowercase(Locale.US)
            .replace(Regex("""\([^)]*\)|\[[^\]]*]|\{[^}]*}"""), "")
            .replace(Regex("""[\p{P}\p{S}\s]+"""), "")

    private suspend fun localizeSearchResults(
        term: String,
        songs: List<SongHit>
    ): List<SongHit> {
        if (songs.isEmpty() || songs.all { it.hasChineseMetadata() }) return songs

        return try {
            val encodedTerm = URLEncoder.encode(term.trim(), Charsets.UTF_8.name()).replace("+", "%20")
            val response = request(
                "/v1/catalog/cn/search?term=$encodedTerm&types=songs&limit=50&l=zh-Hans-CN"
            )
            val data = JSONObject(response).optJSONObject("results")
                ?.optJSONObject("songs")?.optJSONArray("data")
                ?: run {
                    lastSearchLocalizationWarning =
                        "国区搜索没有返回本地化曲目信息，当前显示原地区信息"
                    return songs
                }
            val localizedByIsrc = buildMap {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val attributes = item.optJSONObject("attributes") ?: continue
                    val isrc = attributes.optString("isrc")
                        .takeIf { it.isNotBlank() }?.uppercase(Locale.US) ?: continue
                    val title = attributes.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val artist = attributes.optString("artistName")
                        .takeIf { it.isNotBlank() } ?: continue
                    if (title.none { it.isCjkIdeograph() } &&
                        artist.none { it.isCjkIdeograph() }
                    ) continue
                    put(
                        isrc,
                        LocalizedSongMetadata(
                            title = title,
                            artist = artist,
                            album = attributes.optString("albumName")
                        )
                    )
                }
            }
            songs.map { song ->
                val isrc = song.isrc?.uppercase(Locale.US)
                val localized = isrc?.let(localizedByIsrc::get) ?: return@map song
                song.copy(
                    title = ChineseText.toSimplified(localized.title),
                    artist = ChineseText.toSimplified(localized.artist),
                    album = ChineseText.toSimplified(localized.album.ifBlank { song.album })
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastSearchLocalizationWarning =
                "国区中文曲目信息暂时不可用，当前显示原地区信息（${error.message ?: "请求失败"}）"
            songs
        }
    }

    private data class LocalizedSongMetadata(
        val title: String,
        val artist: String,
        val album: String
    )

    private fun SongHit.hasChineseMetadata(): Boolean =
        title.any { it.isCjkIdeograph() } && artist.any { it.isCjkIdeograph() }

    suspend fun fetchChineseSongMetadata(song: SongHit): SongHit? = withContext(Dispatchers.IO) {
        val candidateCatalogId = song.providerSongId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: song.id.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        val directLookup = candidateCatalogId?.let { id ->
            requestOrNull("/v1/catalog/cn/songs/$id?l=zh-Hans-CN")
                ?.let { localizedSongFromResponse(it, song, id) }
        }
        if (directLookup != null) return@withContext directLookup

        val isrc = song.isrc?.takeIf { it.isNotBlank() } ?: return@withContext null
        val encodedIsrc = URLEncoder.encode(isrc, Charsets.UTF_8.name())
        val response = requestOrNull(
            "/v1/catalog/cn/songs?filter%5Bisrc%5D=$encodedIsrc&l=zh-Hans-CN&limit=5"
        ) ?: return@withContext null
        val data = JSONObject(response).optJSONArray("data") ?: return@withContext null
        val exactTrack = (0 until data.length()).asSequence()
            .mapNotNull(data::optJSONObject)
            .firstOrNull {
                it.optJSONObject("attributes")?.optString("isrc")
                    ?.equals(isrc, ignoreCase = true) == true
            } ?: return@withContext null
        localizedSongFromItem(exactTrack, song)
    }

    private fun localizedSongFromResponse(
        response: String,
        original: SongHit,
        expectedId: String
    ): SongHit? {
        val data = JSONObject(response).optJSONArray("data") ?: return null
        val matchingId = (0 until data.length())
            .asSequence()
            .mapNotNull(data::optJSONObject)
            .firstOrNull { it.optString("id") == expectedId }
            ?: return null
        return localizedSongFromItem(matchingId, original)
    }

    private fun localizedSongFromItem(item: JSONObject, original: SongHit): SongHit? {
        val attributes = item.optJSONObject("attributes") ?: return null
        val localizedTitle = attributes.optString("name").takeIf { it.isNotBlank() } ?: return null
        val localizedArtist = attributes.optString("artistName").takeIf { it.isNotBlank() } ?: return null
        if (localizedTitle.none { it.isCjkIdeograph() } &&
            localizedArtist.none { it.isCjkIdeograph() }
        ) return null
        return SongHit(
            id = item.optString("id", original.id),
            title = ChineseText.toSimplified(localizedTitle),
            artist = ChineseText.toSimplified(localizedArtist),
            album = ChineseText.toSimplified(attributes.optString("albumName", original.album)),
            artworkUrl = original.artworkUrl,
            durationMs = attributes.optLong("durationInMillis", original.durationMs),
            providerSongId = original.providerSongId,
            isrc = original.isrc
        )
    }

    private fun Char.isCjkIdeograph(): Boolean =
        code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF

    /**
     * 获取 Apple Music 歌词（优先逐字，仅在确实缺失译文且非本语种歌曲时补充请求 `/lyrics`）。
     * 资料库 ID (`i.xxx`) 自动跳过目录请求返回 null，避免触发 HTTP 400 中断 QQ 音乐兜底。
     */
    suspend fun fetchLyrics(songId: String): FetchedLyrics? = withContext(Dispatchers.IO) {
        val cleanId = songId.removePrefix("apple:").trim()
        if (cleanId.isBlank() || cleanId.startsWith("i.")) {
            return@withContext null
        }
        ensureDeveloperToken()
        val sf = effectiveStorefront()
        val locale = lyricsLocale()
        val base = "/v1/catalog/$sf/songs/$cleanId"
        val localizedQuery = lyricsLocalizationQuery()

        val syllableJson = try {
            requestOrNull("$base/syllable-lyrics?$localizedQuery")
                ?: requestOrNull("$base/syllable-lyrics")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            try {
                requestOrNull("$base/syllable-lyrics")
            } catch (e2: CancellationException) {
                throw e2
            } catch (_: Exception) {
                null
            }
        }

        val syllableLyrics = syllableJson?.let { LyricsParser.parse(it, locale) }
        val alreadyHasTranslation = syllableLyrics?.lines?.any { !it.translation.isNullOrBlank() } == true
        val looksAlreadyNativeChinese = locale.startsWith("zh") && syllableLyrics != null &&
            syllableLyrics.lines.count { it.text.any { c -> c.isCjkIdeograph() } } >=
            (syllableLyrics.lines.count { !it.isBlank } / 2).coerceAtLeast(1)

        var translationWarning: String? = null
        val standardLyricsJsons = LinkedHashSet<String>()
        var standardLyricsError: Exception? = null

        // 仅在没有逐字歌词，或者逐字歌词缺少译文且不是本语种歌曲时，才额外请求 /lyrics
        if (syllableLyrics == null || (!alreadyHasTranslation && !looksAlreadyNativeChinese)) {
            val paths = if (syllableLyrics == null) {
                listOf("$base/lyrics?$localizedQuery", "$base/lyrics?l%5Blyrics%5D=ttml")
            } else {
                listOf("$base/lyrics?$localizedQuery")
            }
            for (path in paths) {
                try {
                    val response = requestOrNull(path) ?: continue
                    standardLyricsJsons.add(response)
                    val current = LyricsParser.parse(response, locale)
                    if (current != null && (syllableLyrics == null || current.lines.any { !it.translation.isNullOrBlank() })) {
                        break
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    standardLyricsError = error
                }
            }
        }

        if (standardLyricsJsons.isEmpty() && standardLyricsError != null) {
            if (syllableLyrics == null) throw standardLyricsError
            translationWarning = standardLyricsError.message ?: "暂时无法获取翻译歌词"
        }

        val primaryJson = syllableJson ?: standardLyricsJsons.firstOrNull() ?: return@withContext null
        val primaryLyrics = syllableLyrics ?: LyricsParser.parse(primaryJson, locale) ?: return@withContext null
        val mergedLyrics = if (syllableLyrics == null || standardLyricsJsons.isEmpty()) {
            primaryLyrics
        } else {
            standardLyricsJsons.fold(primaryLyrics) { current, json ->
                LyricsParser.mergeTranslations(current, json, locale)
            }
        }
        val ttml = runCatching {
            val attributes = JSONObject(primaryJson).optJSONArray("data")
                ?.optJSONObject(0)?.optJSONObject("attributes")
            attributes?.optString("ttmlLocalizations")?.takeIf { it.isNotBlank() }
                ?: attributes?.optString("ttml")?.takeIf { it.isNotBlank() }
        }.getOrNull()
        FetchedLyrics(mergedLyrics, ttml, translationWarning)
    }

    private fun effectiveStorefront(): String {
        val sf = storefront.trim().lowercase(Locale.ROOT)
        return if (sf.matches(Regex("[a-z]{2}"))) sf else "cn"
    }

    private fun lyricsLocalizationQuery(): String {
        val locale = lyricsLocale()
        val script = when {
            locale.startsWith("zh-Hant") -> "zh-Hant,zh-Latn"
            locale.startsWith("zh") -> "zh-Hans,zh-Latn"
            locale.startsWith("ja") -> "ja-Jpan,ja-Latn"
            locale.startsWith("ko") -> "ko-Kore,ko-Latn"
            locale.startsWith("en") -> "en-Latn"
            else -> "$locale-Latn"
        }
        return listOf(
            "l%5Blyrics%5D=${URLEncoder.encode(locale, Charsets.UTF_8.name())}",
            "l%5Bscript%5D=${URLEncoder.encode(script, Charsets.UTF_8.name())}",
            "extend=ttmlLocalizations"
        ).joinToString("&")
    }

    private fun lyricsLocale(): String {
        val deviceLocale = Locale.getDefault()
        return when {
            deviceLocale.language == "zh" && deviceLocale.country in setOf("TW", "HK", "MO") ->
                "zh-Hant-TW"
            deviceLocale.language == "zh" -> "zh-Hans-CN"
            deviceLocale.language == "ja" -> "ja-JP"
            deviceLocale.language == "ko" -> "ko-KR"
            else -> deviceLocale.toLanguageTag().takeIf { it.isNotBlank() } ?: "en-US"
        }
    }

    private fun isJwtExpired(jwt: String): Boolean {
        return try {
            val parts = jwt.split('.')
            if (parts.size < 2) return false
            val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val expSec = JSONObject(String(payloadBytes, Charsets.UTF_8)).optLong("exp", 0L)
            if (expSec <= 0L) false else (System.currentTimeMillis() / 1000L) >= (expSec - 300L)
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureDeveloperToken() {
        if (developerToken.length <= 50 || isJwtExpired(developerToken)) {
            refreshDeveloperToken(force = false)
        }
    }

    private fun refreshDeveloperToken(force: Boolean) {
        val before = developerToken
        synchronized(devTokenLock) {
            if (!force && developerToken.length > 50 && !isJwtExpired(developerToken)) return
            if (force && developerToken.isNotEmpty() && developerToken != before && !isJwtExpired(developerToken)) return
            val html = getText("https://music.apple.com/us/browse")
            val assetPath = Regex("/assets/index[~-][^/\"']+\\.js")
                .find(html)?.value
                ?: throw IllegalStateException("Apple Music 页面未找到认证脚本，请稍后重试")
            val script = getText("https://music.apple.com$assetPath")
            developerToken = Regex("['\"](eyJ[A-Za-z0-9_\\-]+\\.eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+)['\"]")
                .find(script)?.groupValues?.get(1)
                ?: throw IllegalStateException("未能获取 Apple Music 访问令牌")
            preferences.edit().putString("developer_token", developerToken).apply()
        }
    }

    private fun requestOrNull(path: String): String? {
        ensureDeveloperToken()
        var response = doRequest("https://amp-api.music.apple.com$path")
        if (response.code == 401) {
            runCatching { refreshDeveloperToken(force = true) }
            response = doRequest("https://amp-api.music.apple.com$path")
        }
        return when (response.code) {
            200 -> response.body
            400, 404 -> null
            401, 403 -> throw IllegalStateException("Apple Music 拒绝了请求，请先登录 Apple Music 后重试")
            else -> throw IllegalStateException("获取歌词失败（HTTP ${response.code}）")
        }
    }

    private fun request(path: String): String {
        ensureDeveloperToken()
        var response = doRequest("https://amp-api.music.apple.com$path")
        if (response.code == 401) {
            runCatching { refreshDeveloperToken(force = true) }
            response = doRequest("https://amp-api.music.apple.com$path")
        }
        if (response.code !in 200..299) {
            val message = if (response.code == 401 || response.code == 403) {
                "Apple Music 拒绝了请求，请检查登录状态后重试"
            } else {
                "Apple Music 请求失败（HTTP ${response.code}）"
            }
            throw IllegalStateException(message)
        }
        return response.body
    }

    private fun doRequest(url: String): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Origin", "https://music.apple.com")
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
            connection.setRequestProperty("Authorization", "Bearer $developerToken")
            if (userToken.isNotEmpty()) {
                connection.setRequestProperty("Cookie", "media-user-token=$userToken")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Response(code, body)
        } catch (t: Throwable) {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    private fun getText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
            if (connection.responseCode != 200) {
                throw IllegalStateException("连接 Apple Music 失败（HTTP ${connection.responseCode}）")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    private data class Response(val code: Int, val body: String)
}
