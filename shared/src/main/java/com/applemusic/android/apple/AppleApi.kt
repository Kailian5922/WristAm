package com.applemusic.android.apple

import android.util.Base64
import com.applemusic.android.net.Http
import com.applemusic.android.util.ChineseText
import com.applemusic.android.util.Logx
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Apple Music API 客户端 —— 对应鸿蒙版 `AppleApi.ets`（全项目最核心的一个文件）。
 *
 *  1) 认证：developer token（从 music.apple.com 首页的 index.js 里正则抓 JWT）
 *           + media-user-token（WebView 登录后从 cookie 抓，代表「你的账号身份」）
 *  2) 请求：统一走 ampGet()，头是 Origin + Chrome UA + Bearer + Cookie
 *  3) 资料库：/v1/me/library/{songs,albums,playlists,artists}（分页）
 *  4) 目录：/v1/catalog/{storefront}/{search,songs,albums,artists}
 *  5) 播放：getWebPlayback() 拿资产清单 → getPlayAssets() 拿 HLS + license 服务器
 *  6) 字节流：getBytes() 支持 Range（HLS 分片按字节区间拉）
 *
 * ⚠ dev token 靠正则抓前端资源，Apple 换前端结构就失效（表现为所有接口 401），
 *   此时更新 `refreshDeveloperToken()` 里的正则即可。
 */
class AppleApi(private val prefs: TokenStore) {

    companion object {
        private const val TAG = "AppleApi"
        private const val UA = AppleConst.UA
    }

    data class StationProbeResponse(
        val path: String,
        val statusCode: Int,
        val body: String
    )

    class FavoritePlaylistNotFoundException(
        val playlists: List<ApplePlaylist>
    ) : IllegalStateException(
        buildString {
            append("Apple Music 返回了 ${playlists.size} 个资料库歌单，但没有识别到 Favorite Songs")
            val names = playlists.map { it.name.trim() }.filter(String::isNotBlank)
            if (names.isNotEmpty()) {
                append("。歌单名称：")
                append(names.take(20).joinToString("、"))
                if (names.size > 20) append(" 等（共 ${names.size} 个有名称的歌单）")
            }
        }
    )

    @Volatile
    var devToken: String = ""
        private set

    @Volatile
    var userToken: String = ""
        private set

    @Volatile
    var storefront: String = "cn"
        private set

    private val devTokenLock = Any()

    /** 是否已具备调用条件（统一 >= 50 长度标准）。 */
    fun isAuthorized(): Boolean = userToken.trim().length >= 50

    fun hasDeveloperToken(): Boolean = devToken.length > 50 && !isJwtExpired(devToken)

    fun effectiveStorefront(): String {
        val sf = storefront.trim().lowercase(Locale.ROOT)
        return if (sf.matches(Regex("[a-z]{2}"))) sf else "cn"
    }

    // ─────────────────────────── 认证 ───────────────────────────

    /** 从本地偏好恢复登录态。 */
    fun restore() {
        devToken = prefs.getString("developer_token", "").trim()
        userToken = prefs.getString("media_user_token", "").trim()
        val savedSf = prefs.getString("storefront", "cn").trim().lowercase(Locale.ROOT)
        storefront = if (savedSf.matches(Regex("[a-z]{2}"))) savedSf else "cn"
        Logx.i(TAG, "restore: dev=${devToken.length} user=${userToken.length} sf=$storefront")
    }

    /** 持久化 token（自动 trim，且空值绝不清空错误状态或已有凭据；退出请显式调用 logout）。 */
    fun persist(userToken: String) {
        val clean = userToken.trim()
        this.userToken = clean
        if (devToken.isNotEmpty()) {
            prefs.putString("developer_token", devToken)
        }
        if (clean.length >= 50) {
            prefs.putString("media_user_token", clean)
        }
        prefs.putString("storefront", effectiveStorefront())
    }

    /** 退出登录并清理关联账号的歌单与区服缓存。 */
    fun logout() {
        userToken = ""
        storefront = "cn"
        prefs.remove("media_user_token")
        prefs.remove("favorite_playlist_id")
        prefs.remove("storefront")
    }

    /**
     * 线程安全刷新 developer token（支持单/双引号 JWT 与强制刷新）。
     */
    fun refreshDeveloperToken(force: Boolean = false): String {
        val beforeToken = devToken
        synchronized(devTokenLock) {
            if (!force && hasDeveloperToken()) return devToken
            if (force && devToken.isNotEmpty() && devToken != beforeToken && !isJwtExpired(devToken)) {
                return devToken
            }
            val home = Http.getText(AppleConst.HOME + "/us/browse", headers(false), 25000)
            val m = Regex("/assets/index[~-][^/\"']+\\.js").find(home)
                ?: throw RuntimeException("首页未找到 index.js（Apple 可能改了结构）")
            val js = Http.getText(AppleConst.HOME + m.value, headers(false), 25000)
            val tok = Regex("['\"](eyJ[A-Za-z0-9\\-_]+\\.eyJ[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+)['\"]").find(js)
                ?: throw RuntimeException("index.js 中未找到 developer token")
            devToken = tok.groupValues[1]
            prefs.putString("developer_token", devToken)
            Logx.i(TAG, "developer token refreshed, len=${devToken.length}")
            return devToken
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

    /** 确保 developer token 就绪（缺失或已过期时刷新）。 */
    fun ensureReady() {
        if (!hasDeveloperToken()) refreshDeveloperToken(force = false)
    }

    /** 从 WebView cookie 中提取 media-user-token（自动 trim，长度 >= 50）。 */
    fun extractUserToken(cookie: String): String {
        val m = Regex("media-user-token=([^;\\s]+)").find(cookie) ?: return ""
        val v = m.groupValues[1].trim()
        return if (v.length >= 50) v else ""
    }

    // ─────────────────────────── HTTP ───────────────────────────

    private fun headers(withAuth: Boolean): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Origin"] = AppleConst.HOME
        h["User-Agent"] = UA
        if (withAuth) {
            h["Authorization"] = "Bearer $devToken"
            h["Cookie"] = "media-user-token=$userToken"
        }
        return h
    }

    /** 媒体 CDN 分片下载用最小请求头（不向外部 CDN 泄漏账号令牌）。 */
    fun mediaHeaders(): Map<String, String> = mapOf(
        "Origin" to AppleConst.HOME,
        "User-Agent" to UA
    )

    /** license 请求用的 headers（小写名 + 不带 UA）。 */
    fun licenseHeaders(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["authorization"] = "Bearer $devToken"
        h["origin"] = "https://music.apple.com"
        h["cookie"] = "media-user-token=$userToken"
        h["content-type"] = "application/json"
        return h
    }

    private fun ampRequest(
        path: String,
        method: String = "GET",
        extraHeaders: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        readTimeoutMs: Int = 30000
    ): Http.Response {
        ensureReady()
        val url = if (path.startsWith("http://") || path.startsWith("https://")) path else AppleConst.AMP + path
        fun buildHeaders(): Map<String, String> = LinkedHashMap(headers(true)).apply { putAll(extraHeaders) }

        var r = Http.request(url, method, buildHeaders(), body, 15000, readTimeoutMs)
        if (r.code == 401) {
            Logx.w(TAG, "$method $path -> HTTP 401，刷新 developer token 并重试一次")
            try {
                refreshDeveloperToken(force = true)
                r = Http.request(url, method, buildHeaders(), body, 15000, readTimeoutMs)
            } catch (e: Throwable) {
                Logx.w(TAG, "401 自动刷新 developer token 失败: ${e.message}")
            }
        }
        return r
    }

    private fun ampGet(path: String): String {
        val r = ampRequest(path, "GET")
        if (r.code != 200) {
            throw RuntimeException("HTTP ${r.code} @ $path body=${r.text.take(200)}")
        }
        return r.text
    }

    // ─────────────────────────── 账号 ───────────────────────────

    /** 账号信息（校验订阅并解析真实的 2 字母 storefront，缺失时回落 `/v1/me/storefront`）。 */
    fun getAccountInfo(): String {
        var resolvedSf = ""
        val txt = try {
            ampGet("/v1/me/account?meta=subscription")
        } catch (e: Throwable) {
            Logx.w(TAG, "/v1/me/account 请求失败，尝试 /v1/me/storefront: ${e.message}")
            ""
        }
        if (txt.isNotEmpty()) {
            try {
                val sub = JSONObject(txt).optJSONObject("meta")?.optJSONObject("subscription")
                resolvedSf = sub?.optString("storefront", "").orEmpty().trim().lowercase(Locale.ROOT)
            } catch (e: Throwable) {
                Logx.w(TAG, "解析 account 失败：${e.message}")
            }
        }
        if (!resolvedSf.matches(Regex("[a-z]{2}"))) {
            try {
                val sfRaw = ampGet("/v1/me/storefront")
                val id = JSONObject(sfRaw).optJSONArray("data")?.optJSONObject(0)
                    ?.optString("id", "").orEmpty().trim().lowercase(Locale.ROOT)
                if (id.matches(Regex("[a-z]{2}"))) resolvedSf = id
            } catch (e: Throwable) {
                Logx.w(TAG, "/v1/me/storefront 回落失败: ${e.message}")
            }
        }
        if (resolvedSf.matches(Regex("[a-z]{2}"))) {
            storefront = resolvedSf
            prefs.putString("storefront", resolvedSf)
            Logx.i(TAG, "账号区服已确认: $resolvedSf")
        }
        return txt
    }

    // ─────────────────────────── 资料库 ───────────────────────────

    /** 资料库歌曲（分页）。 */
    fun getLibrarySongs(limit: Int, offset: Int): PageResult<AppleSong> {
        val path = "/v1/me/library/songs?limit=$limit&offset=$offset" +
            "&extend=artistUrl&include=albums,artists&l=${langTag()}"
        return parseSongs(ampGet(path), true)
    }

    /** 资料库专辑。 */
    fun getLibraryAlbums(limit: Int, offset: Int): PageResult<AppleAlbum> {
        return parseAlbums(ampGet("/v1/me/library/albums?limit=$limit&offset=$offset&l=${langTag()}"), true)
    }

    /** 资料库歌单。 */
    fun getLibraryPlaylists(limit: Int, offset: Int): PageResult<ApplePlaylist> {
        return parsePlaylists(ampGet("/v1/me/library/playlists?limit=$limit&offset=$offset&l=${langTag()}"))
    }

    fun getAllLibraryPlaylists(): List<ApplePlaylist> {
        val playlists = ArrayList<ApplePlaylist>()
        var offset = 0
        var guard = 0
        while (guard < 50) {
            val page = getLibraryPlaylists(100, offset)
            playlists.addAll(page.items)
            offset += page.items.size
            if (page.next.isBlank() || page.items.size < 100) break
            guard++
        }
        return playlists
    }

    /** 歌单内的歌曲（自动歌单会 404，属正常）。 */
    fun getPlaylistTracks(playlistId: String, limit: Int, offset: Int): PageResult<AppleSong> {
        val path = "/v1/me/library/playlists/$playlistId/tracks?limit=$limit&offset=$offset&l=${langTag()}"
        return parseSongs(ampGet(path), true)
    }

    fun favoritePlaylistId(): String = prefs.getString("favorite_playlist_id", "")

    fun setFavoritePlaylistId(playlistId: String) {
        require(playlistId.matches(Regex("[A-Za-z0-9._-]+"))) { "无效的歌单 ID" }
        prefs.putString("favorite_playlist_id", playlistId)
    }

    /** 自动绑定 Favorite Songs 智能歌单（支持多区服语言），并读取其全部曲目目录 ID。 */
    fun getFavoriteCatalogSongIds(): Set<String> {
        val favoritePlaylistNames = setOf(
            "favorite songs",
            "favourite songs",
            "favorites",
            "favourites",
            "liked songs",
            "喜爱歌曲",
            "喜爱的歌曲",
            "喜欢的歌曲",
            "喜欢的音乐",
            "喜爱的音乐",
            "最爱歌曲",
            "最爱音乐",
            "最喜欢的歌曲",
            "最喜欢的音乐",
            "favori parçalar",
            "favori şarkılar",
            "お気に入りの曲",
            "즐겨찾는 노래",
            "lieblingstitel",
            "morceaux favoris",
            "canciones favoritas",
            "brani preferiti",
            "músicas favoritas"
        ).mapTo(HashSet(), ::normalizeFavoritePlaylistName)

        fun findFavoritePlaylist(): ApplePlaylist? {
            val discovered = getAllLibraryPlaylists()
            return discovered.firstOrNull { playlist ->
                normalizeFavoritePlaylistName(playlist.name) in favoritePlaylistNames
            }
        }

        val configuredPlaylistId = favoritePlaylistId()
        var targetPlaylistId = configuredPlaylistId.takeIf(String::isNotBlank)
        if (targetPlaylistId == null) {
            val found = runCatching { findFavoritePlaylist() }.getOrNull()
            if (found != null) {
                targetPlaylistId = found.id
                setFavoritePlaylistId(found.id)
                Logx.i(TAG, "已自动绑定 Favorite Songs 歌单：${found.id}")
            }
        }

        if (targetPlaylistId != null) {
            try {
                return getFavoritePlaylistCatalogSongIds(targetPlaylistId)
            } catch (configuredError: Exception) {
                Logx.w(TAG, "已绑定喜爱歌单不可读取，尝试备用方案：${configuredError.message}")
                val discoveredPlaylist = runCatching { findFavoritePlaylist() }
                    .getOrNull()?.takeIf { it.id != configuredPlaylistId }
                if (discoveredPlaylist != null) {
                    setFavoritePlaylistId(discoveredPlaylist.id)
                    try {
                        return getFavoritePlaylistCatalogSongIds(discoveredPlaylist.id)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return getDirectFavoriteSongIds()
    }

    /** 分页读取 `/v1/me/favorites?types=songs`（完整分页 + media-user-token + platform=web）。 */
    private fun getDirectFavoriteSongIds(): Set<String> {
        return try {
            val ids = LinkedHashSet<String>()
            var nextPath: String? = "/v1/me/favorites?types=songs&limit=100&l=${langTag()}&platform=web"
            var guard = 0
            while (!nextPath.isNullOrBlank() && guard < 50) {
                val r = ampRequest(nextPath, "GET", mapOf("media-user-token" to userToken))
                if (r.code != 200) break
                val json = JSONObject(r.text)
                val data = json.optJSONArray("data") ?: break
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isNotBlank()) ids.add(id)
                }
                val next = json.optString("next", "")
                nextPath = if (next.isNotBlank()) {
                    if (next.contains("platform=")) next else "$next&platform=web"
                } else {
                    null
                }
                guard++
            }
            ids
        } catch (e: Exception) {
            Logx.w(TAG, "getDirectFavoriteSongIds 备用读取失败: ${e.message}")
            emptySet()
        }
    }

    private fun getFavoritePlaylistCatalogSongIds(playlistId: String): Set<String> {
        val songIds = LinkedHashSet<String>()
        val seenPageSignatures = HashSet<String>()
        var offset = 0
        var songPage = getPlaylistTracks(playlistId, 100, offset)
        var guard = 0
        while (songPage.items.isNotEmpty() && guard < 100) {
            val pageIds = songPage.items.map { song ->
                song.catalogId.ifBlank { song.id.takeUnless { it.startsWith("i.") }.orEmpty() }
            }.filter(String::isNotBlank)
            if (!seenPageSignatures.add(pageIds.joinToString(","))) break
            pageIds.forEach { songIds.add(it) }
            offset += songPage.items.size
            if (songPage.next.isBlank() || songPage.items.size < 100) break
            songPage = try {
                getPlaylistTracks(playlistId, 100, offset)
            } catch (e: Throwable) {
                if (e.message?.contains("404") == true) break
                throw e
            }
            guard++
        }
        return songIds
    }

    private fun normalizeFavoritePlaylistName(name: String): String =
        name.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

    /**
     * **目录歌单**的曲目。
     *
     * 主页/新发现里的「专属推荐歌单」「热门歌单」全是**目录歌单**（id 形如 `pl.xxx`），
     * 用 [getPlaylistTracks] 那条 `/v1/me/library/playlists/...` 路径取**永远是空的**
     * （资料库歌单才走那条）。必须用 `/v1/catalog/{sf}/playlists/...`。
     */
    fun getCatalogPlaylistTracks(playlistId: String, limit: Int, offset: Int): PageResult<AppleSong> {
        val sf = if (storefront.isNotEmpty()) storefront else "us"
        val path =
            "/v1/catalog/$sf/playlists/$playlistId/tracks?limit=$limit&offset=$offset&l=${langTag()}"
        return parseSongs(ampGet(path), false).also { page ->
            localizeCatalogSongs(page.items)
        }
    }

    /**
     * 按 id 前缀自动选路径取歌单曲目。
     *
     *  - `p.xxx`  → 资料库歌单 → `/v1/me/library/playlists/...`
     *  - `pl.xxx` → 目录歌单   → `/v1/catalog/{sf}/playlists/...`
     *
     * 注意判断顺序：`pl.` 也以 `p` 开头，所以必须用 `startsWith("p.")` 来区分。
     */
    fun getAnyPlaylistTracks(
        playlistId: String,
        limit: Int,
        offset: Int,
        isLibraryPlaylist: Boolean = playlistId.startsWith("p.")
    ): PageResult<AppleSong> {
        return if (isLibraryPlaylist) {
            getPlaylistTracks(playlistId, limit, offset).also { page ->
                runCatching { localizeCatalogSongs(page.items, "cn") }
            }
        } else {
            getCatalogPlaylistTracks(playlistId, limit, offset)
        }
    }

    /** 从主页推荐资源读取歌单曲目，保留资料库歌单与目录歌单的真实资源类型。 */
    fun getRecommendedPlaylistTracks(
        playlistId: String,
        relatedCatalogId: String,
        isLibraryPlaylist: Boolean,
        limit: Int,
        offset: Int
    ): PageResult<AppleSong> {
        return when {
            !isLibraryPlaylist ->
                getCatalogPlaylistTracks(playlistId, limit, offset)
            relatedCatalogId.isNotBlank() ->
                getCatalogPlaylistTracks(relatedCatalogId, limit, offset)
            else ->
                getPlaylistTracks(playlistId, limit, offset)
        }
    }

    /** Read a recommended album's tracks from its catalog resource or library relationship. */
    fun getRecommendedAlbumTracks(
        albumId: String,
        relatedCatalogId: String,
        isLibraryAlbum: Boolean,
        resolvedCatalogId: String = "",
        catalogStorefront: String = storefront
    ): List<AppleSong> {
        val catalogId = resolvedCatalogId.ifBlank { relatedCatalogId }
        if (catalogId.isNotBlank()) {
            return getCatalogAlbumTracks(catalogId, catalogStorefront)
        }
        if (!isLibraryAlbum) return getCatalogAlbumTracks(albumId, catalogStorefront)
        require(albumId.matches(Regex("[A-Za-z0-9._-]+"))) { "无效的资料库专辑 ID" }
        val root = JSONObject(
            ampGet("/v1/me/library/albums/$albumId/tracks?limit=100&l=${langTag()}")
        )
        val tracks = root.optJSONArray("data")
            ?: throw IllegalStateException("Apple Music 没有返回资料库专辑曲目")
        return pickSongs(JSONObject().put("data", tracks), "data", true)
    }

    /** Load complete collection metadata, including the responsive portrait motion-art assets. */
    fun getRecommendedCollectionMetadata(
        item: ShelfItem,
        preferSquareArtwork: Boolean = false
    ): CollectionMetadata {
        require(item.isAlbum || item.isPlaylist) { "不支持获取此类型的详情：${item.type}" }

        val resourceType = if (item.isAlbum) "albums" else "playlists"
        val libraryType = if (item.isAlbum) "albums" else "playlists"
        var libraryAttributes = JSONObject()
        var catalogId = item.relatedCatalogId

        if (item.type.contains("library", ignoreCase = true) && catalogId.isBlank()) {
            require(item.id.matches(Regex("[A-Za-z0-9._-]+"))) { "无效的资料库内容 ID" }
            val libraryResource = JSONObject(
                ampGet(
                    "/v1/me/library/$libraryType/${item.id}" +
                        "?include=catalog&l=${langTag()}"
                )
            ).optJSONArray("data")?.optJSONObject(0)
                ?: throw IllegalStateException("Apple Music 没有返回《${item.title}》的详情")
            libraryAttributes = libraryResource.optJSONObject("attributes") ?: JSONObject()
            catalogId = libraryResource.optJSONObject("relationships")
                ?.optJSONObject("catalog")
                ?.optJSONArray("data")
                ?.optJSONObject(0)
                ?.optString("id", "")
                .orEmpty()
        }

        val catalogStorefront = item.catalogStorefront.ifBlank { storefront }
        val catalogLanguage = if (catalogStorefront == "cn") "zh-Hans-CN" else langTag()
        val catalogResource: JSONObject? =
            if (catalogId.isNotBlank() || !item.type.contains("library", true)) {
            val resolvedCatalogId = catalogId.ifBlank { item.id }
            require(resolvedCatalogId.matches(Regex("[A-Za-z0-9._-]+"))) {
                "无效的目录内容 ID"
            }
            catalogId = resolvedCatalogId
            val includeCurator = if (item.isPlaylist) "&include=curator" else "&include=artists"
            JSONObject(
                ampGet(
                    "/v1/catalog/$catalogStorefront/$resourceType/$resolvedCatalogId" +
                        "?extend=editorialArtwork%2CeditorialVideo$includeCurator" +
                        "&l=$catalogLanguage"
                )
            ).optJSONArray("data")?.optJSONObject(0)
                ?: throw IllegalStateException("Apple Music 没有返回《${item.title}》的目录详情")
        } else {
            null
        }
        val catalogAttributes = catalogResource?.optJSONObject("attributes") ?: JSONObject()

        val artwork = catalogAttributes.optJSONObject("editorialArtwork")
            ?.optJSONObject("staticDetailTall")
        val editorialVideo = catalogAttributes.optJSONObject("editorialVideo")
        val motion = if (preferSquareArtwork) {
            editorialVideo?.optJSONObject("motionDetailSquare")
                ?: editorialVideo?.optJSONObject("motionDetailTall")
                ?: editorialVideo?.optJSONObject("motionTallVideo3x4")
        } else {
            editorialVideo?.optJSONObject("motionDetailTall")
                ?: editorialVideo?.optJSONObject("motionTallVideo3x4")
                ?: editorialVideo?.optJSONObject("motionDetailSquare")
        }
        val preview = motion?.optJSONObject("previewFrame")
        val description = collectionDescription(catalogAttributes)
            .ifBlank { collectionDescription(libraryAttributes) }
            .ifBlank { item.collectionDescription }
        val creator = catalogAttributes.optString("curatorName", "")
            .ifBlank {
                catalogAttributes.optJSONObject("editorialNotes")
                    ?.optString("name", "")
                    .orEmpty()
            }
            .ifBlank { catalogAttributes.optString("artistName", "") }
            .ifBlank { libraryAttributes.optString("curatorName", "") }
            .ifBlank { item.creator }
        val creatorId = catalogResource?.optJSONObject("relationships")
            ?.optJSONObject("artists")
            ?.optJSONArray("data")
            ?.optJSONObject(0)
            ?.optString("id", "")
            .orEmpty()
        val ratioSource = preview ?: artwork
        val width = ratioSource?.optDouble("width", 0.0) ?: 0.0
        val height = ratioSource?.optDouble("height", 0.0) ?: 0.0
        val aspectRatio = if (width > 0.0 && height > 0.0) {
            (width / height).toFloat().coerceIn(0.5f, 2f)
        } else {
            item.artworkAspectRatio
        }

        return CollectionMetadata(
            title = catalogAttributes.optString("name", "").ifBlank { item.title },
            catalogId = catalogId,
            creator = creator,
            creatorId = creatorId,
            description = description,
            trackCount = catalogAttributes.optInt("trackCount", 0)
                .takeIf { it > 0 }
                ?: libraryAttributes.optInt("trackCount", 0)
                .takeIf { it > 0 }
                ?: item.trackCount,
            updatedAt = catalogAttributes.optString("lastModifiedDate", "")
                .ifBlank { catalogAttributes.optString("releaseDate", "") }
                .ifBlank { libraryAttributes.optString("lastModifiedDate", "") }
                .ifBlank { item.updatedAt },
            artworkUrl = motionArtworkUrl(
                preview,
                targetWidth = 600,
                targetHeight = if (preferSquareArtwork) 600 else 800
            )
                .ifBlank { artwork?.optString("url", "").orEmpty() }
                .ifBlank {
                    catalogAttributes.optJSONObject("artwork")?.optString("url", "").orEmpty()
                }
                .ifBlank { item.artwork },
            ambientVideoUrl = motion?.optString("video", "").orEmpty()
                .ifBlank { item.ambientVideoUrl },
            ambientPreviewUrl = if (preferSquareArtwork) {
                item.ambientPreviewUrl
            } else {
                motionArtworkUrl(preview, 600, 800)
            }
                .ifBlank { item.ambientPreviewUrl },
            ambientSquareVideoUrl = editorialVideo
                ?.optJSONObject("motionDetailSquare")
                ?.optString("video", "")
                .orEmpty()
                .ifBlank { item.ambientSquareVideoUrl },
            ambientSquarePreviewUrl = motionArtworkUrl(
                editorialVideo?.optJSONObject("motionDetailSquare")?.optJSONObject("previewFrame"),
                600,
                600
            ).ifBlank { item.ambientSquarePreviewUrl },
            artworkAspectRatio = aspectRatio
        )
    }

    private fun motionArtworkUrl(
        frame: JSONObject?,
        targetWidth: Int,
        targetHeight: Int
    ): String {
        val url = frame?.optString("url", "").orEmpty()
        if (url.isBlank()) return ""
        return url.replace("{w}", targetWidth.toString())
            .replace("{h}", targetHeight.toString())
            .replace("{f}", "jpg")
    }

    private fun collectionDescription(attributes: JSONObject): String {
        val description = attributes.opt("description")
        return when (description) {
            is JSONObject -> description.optString(
                "standard",
                description.optString("stringForDisplay", "")
            )
            is String -> description
            else -> attributes.optJSONObject("editorialNotes")
                ?.optString("standard", "")
                .orEmpty()
        }
    }

    fun langTag(sf: String = storefront): String = when (sf.trim().lowercase(Locale.ROOT)) {
        "cn", "zh" -> "zh-Hans-CN"
        "jp" -> "ja-JP"
        "kr" -> "ko-KR"
        "tw", "hk" -> "zh-Hant-TW"
        "tr" -> "tr-TR"
        else -> "en-US"
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")

    // ─────────────────────────── 目录 / 搜索 ───────────────────────────

    private fun searchOnce(term: String, limit: Int, lang: String, sf: String = effectiveStorefront()): AppleSearchResult {
        val effectiveSf = sf.ifBlank { effectiveStorefront() }
        val safeLimit = limit.coerceIn(1, 25)
        val enc = encodeQuery(term)
        val path = "/v1/catalog/$effectiveSf/search?term=$enc" +
            "&types=songs,albums,artists&limit=$safeLimit&l=$lang"
        val root = JSONObject(ampGet(path))
        val results = root.optJSONObject("results") ?: JSONObject()
        val out = AppleSearchResult()
        out.songs = pickSongs(results.optJSONObject("songs") ?: JSONObject(), "data", false)
        out.albums = pickAlbums(results.optJSONObject("albums") ?: JSONObject(), "data", false)
        out.artists = pickArtists(results.optJSONObject("artists") ?: JSONObject(), "data")
        return out
    }

    /**
     * 搜索（歌曲/专辑/艺人）：按账号区服语言优先检索，含中文词时跨区批量 ISRC 精准映射置顶（保持国区相关度顺序）。
     */
    fun search(term: String, limit: Int): AppleSearchResult {
        val query = term.trim()
        if (query.isEmpty()) return AppleSearchResult()

        val sf = effectiveStorefront()
        val lang = langTag(sf)
        val merged = AppleSearchResult()
        val seenArtist = HashSet<String>()
        val seenAlbum = HashSet<String>()
        val seenSong = HashSet<String>()

        val langs = LinkedHashSet<String>().apply {
            add(lang)
            if (ArtistAliasTable.hasJapaneseKana(query)) add("ja-JP")
            if (hasHanCharacters(query) || sf == "cn") add("zh-Hans-CN")
            add("en-US")
        }

        var succeeded = 0
        var firstError: Throwable? = null
        for ((i, lg) in langs.withIndex()) {
            try {
                val part = searchOnce(query, limit, lg, sf)
                succeeded++
                for (a in part.artists) if (seenArtist.add(a.id)) merged.artists.add(a)
                for (a in part.albums) if (seenAlbum.add(a.id)) merged.albums.add(a)
                for (a in part.songs) if (seenSong.add(a.id)) merged.songs.add(a)
                if (i == 0 && merged.songs.size >= 10 && merged.artists.isNotEmpty()) break
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                Logx.w(TAG, "search with lang=$lg failed: ${e.message}")
            }
        }

        // 跨国区批量 ISRC 智能匹配：单次 filter[isrc] 批量查询，按国区原始相关性顺序置顶（修复 15x 串行风暴与排名倒置）
        if (sf != "cn" && hasHanCharacters(query)) {
            try {
                val cnPart = searchOnce(query, limit = 15, lang = "zh-Hans-CN", sf = "cn")
                succeeded++
                for (cnArtist in cnPart.artists) {
                    if (cnArtist.id.isNotBlank() && seenArtist.add(cnArtist.id)) {
                        merged.artists.add(cnArtist)
                    }
                }
                val isrcs = cnPart.songs.mapNotNull { it.isrc.takeIf(String::isNotBlank) }.distinct()
                if (isrcs.isNotEmpty()) {
                    val byIsrc = getCatalogSongsByIsrcs(isrcs, sf)
                    val orderedPromotions = ArrayList<AppleSong>()
                    val promotedIds = HashSet<String>()
                    for (cnSong in cnPart.songs) {
                        val key = cnSong.isrc.uppercase(Locale.ROOT)
                        val matched = byIsrc[key] ?: continue
                        if (!promotedIds.add(matched.id)) continue
                        matched.name = ChineseText.toSimplified(cnSong.name)
                        matched.artistName = ChineseText.toSimplified(cnSong.artistName)
                        matched.albumName = ChineseText.toSimplified(cnSong.albumName)
                        if (!seenSong.add(matched.id)) {
                            // 已存在较低位置：移除旧项后按国区顺位置顶并保留中文化名称
                            merged.songs.removeAll { it.id == matched.id }
                        }
                        orderedPromotions.add(matched)
                    }
                    if (orderedPromotions.isNotEmpty()) {
                        merged.songs.addAll(0, orderedPromotions)
                    }
                }
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                Logx.w(TAG, "cross-storefront ISRC search failed: ${e.message}")
            }
        }

        val primaryHasExactArtist = merged.artists.any {
            ArtistAliasTable.matchesArtistName(it.name, query)
        }
        val alias = ArtistAliasTable.lookup(query).ifBlank {
            if (!primaryHasExactArtist || merged.songs.size < 5) {
                ArtistAliasTable.alternateSearchTerms(query).firstOrNull().orEmpty()
            } else {
                ""
            }
        }
        if (alias.isNotEmpty() && !alias.equals(query, ignoreCase = true)) {
            val aliasLang = when {
                ArtistAliasTable.hasHanCharacters(alias) -> "zh-Hans-CN"
                ArtistAliasTable.hasJapaneseKana(alias) -> "ja-JP"
                else -> "en-US"
            }
            try {
                val mapped = searchOnce(alias, limit, aliasLang, sf)
                succeeded++
                val fa = mapped.artists.filter { seenArtist.add(it.id) }
                val fb = mapped.albums.filter { seenAlbum.add(it.id) }
                val fs = mapped.songs.filter { seenSong.add(it.id) }
                if (primaryHasExactArtist) {
                    merged.artists.addAll(fa)
                } else {
                    val (exactFa, otherFa) = fa.partition {
                        ArtistAliasTable.matchesArtistName(it.name, query)
                    }
                    merged.artists.addAll(0, exactFa)
                    merged.artists.addAll(otherFa)
                }
                merged.albums.addAll(fb)
                if (merged.songs.size < 5) {
                    merged.songs.addAll(0, fs)
                } else {
                    merged.songs.addAll(fs)
                }
                Logx.i(TAG, "alias search: $query -> $alias ($aliasLang, artists=${fa.size})")
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                Logx.w(TAG, "alias search failed $alias: ${e.message}")
            }
        }

        if (succeeded == 0 && firstError != null) {
            throw RuntimeException("搜索失败：${firstError.message}", firstError)
        }

        localizeCatalogSongs(merged.songs, "cn", resolveMissingByIsrc = false)
        merged.playlists = ArrayList()
        return merged
    }

    /** 批量按目录艺人 ID 拉取指定语言下的艺人名称、曲风与官方头像。 */
    fun getCatalogArtistsByIds(
        artistIds: List<String>,
        lang: String = langTag(),
        targetStorefront: String = effectiveStorefront()
    ): Map<String, AppleArtist> {
        val cleanIds = artistIds.map { it.trim() }
            .filter { it.isNotBlank() && it.matches(Regex("[A-Za-z0-9._-]+")) }
            .distinct()
        if (cleanIds.isEmpty()) return emptyMap()
        val sf = targetStorefront.ifBlank { effectiveStorefront() }
        val out = LinkedHashMap<String, AppleArtist>()
        for (chunk in cleanIds.chunked(25)) {
            try {
                val raw = ampGet("/v1/catalog/$sf/artists?ids=${chunk.joinToString(",")}&l=$lang")
                val parsed = pickArtists(JSONObject(raw), "data")
                for (artist in parsed) {
                    if (artist.id.isNotBlank()) {
                        out[artist.id] = artist
                    }
                }
            } catch (e: Throwable) {
                Logx.w(TAG, "getCatalogArtistsByIds failed ($sf/$lang): ${e.message}")
            }
        }
        return out
    }

    /** 只搜艺人（含双向中英对照表、日语假名与跨区兜底，适配跨区账号）。 */
    fun searchArtistsOnly(term: String, limit: Int): List<AppleArtist> {
        val query = term.trim()
        if (query.isEmpty()) return emptyList()
        val safeLimit = limit.coerceIn(1, 25)
        val sf = effectiveStorefront()
        val primaryLang = langTag(sf)
        val out = ArrayList<AppleArtist>()
        val seen = HashSet<String>()

        fun queryOnce(q: String, lang: String, targetSf: String = sf) {
            try {
                val enc = encodeQuery(q)
                val path = "/v1/catalog/$targetSf/search?term=$enc&types=artists&limit=$safeLimit&l=$lang"
                val root = JSONObject(ampGet(path))
                val results = root.optJSONObject("results") ?: JSONObject()
                for (artist in pickArtists(results.optJSONObject("artists") ?: JSONObject(), "data")) {
                    if (artist.id.isNotBlank() && seen.add(artist.id)) out.add(artist)
                }
            } catch (e: Throwable) {
                Logx.w(TAG, "searchArtistsOnly failed $q ($targetSf/$lang): ${e.message}")
            }
        }

        queryOnce(query, primaryLang)
        if (ArtistAliasTable.hasJapaneseKana(query) && primaryLang != "ja-JP" &&
            out.none { ArtistAliasTable.matchesArtistName(it.name, query) }
        ) {
            queryOnce(query, "ja-JP")
        }
        if (sf != "cn" && hasHanCharacters(query) &&
            out.none { ArtistAliasTable.matchesArtistName(it.name, query) }
        ) {
            queryOnce(query, "zh-Hans-CN", "cn")
        }

        val altTerm = ArtistAliasTable.alternateSearchTerms(query).firstOrNull().orEmpty()
        if (altTerm.isNotEmpty() && !altTerm.equals(query, ignoreCase = true) &&
            out.none { ArtistAliasTable.matchesArtistName(it.name, query) }
        ) {
            val altLang = when {
                ArtistAliasTable.hasHanCharacters(altTerm) -> "zh-Hans-CN"
                ArtistAliasTable.hasJapaneseKana(altTerm) -> "ja-JP"
                else -> "en-US"
            }
            queryOnce(altTerm, altLang)
        } else if (out.isEmpty() && primaryLang != "en-US") {
            queryOnce(query, "en-US")
        }
        return out
    }

    /**
     * 精准匹配歌手（支持中文名与英文名双向精准匹配、日语歌手原名精准匹配），并补全官方头像。
     */
    fun resolveExactArtists(
        term: String,
        initialCandidates: List<AppleArtist> = emptyList(),
        maxResults: Int = 3
    ): List<AppleArtist> {
        val query = term.trim()
        val targetKeys = ArtistAliasTable.exactTargetKeys(query)
        if (targetKeys.isEmpty()) return emptyList()

        val sf = effectiveStorefront()
        val primaryLang = langTag(sf)

        val orderedCandidates = ArrayList<AppleArtist>()
        val candidateById = LinkedHashMap<String, AppleArtist>()

        fun ingestCandidates(list: List<AppleArtist>) {
            for (artist in list) {
                if (artist.name.isBlank()) continue
                if (artist.id.isNotBlank()) {
                    val existing = candidateById[artist.id]
                    if (existing == null) {
                        val copy = artist.copy()
                        candidateById[artist.id] = copy
                        orderedCandidates.add(copy)
                    } else {
                        if (existing.artworkUrl.isBlank() && artist.artworkUrl.isNotBlank()) {
                            existing.artworkUrl = artist.artworkUrl
                        }
                        if (existing.genreNames.isEmpty() && artist.genreNames.isNotEmpty()) {
                            existing.genreNames = artist.genreNames
                        }
                    }
                } else {
                    orderedCandidates.add(artist.copy())
                }
            }
        }

        ingestCandidates(initialCandidates)

        val matchedByKey = LinkedHashMap<String, AppleArtist>()

        fun collectDirectMatches(pool: List<AppleArtist>) {
            for (artist in pool) {
                val dedupKey = artist.id.ifBlank { "name:${ArtistAliasTable.normalizeKey(artist.name)}" }
                if (dedupKey in matchedByKey) continue
                if (ArtistAliasTable.matchesTargetKeys(artist.name, targetKeys)) {
                    matchedByKey[dedupKey] = artist
                }
            }
        }

        // Pass 1：直接匹配当前候选列表（覆盖原名 + ArtistAliasTable 双向中英映射 + 简繁/新字体/标点归一化）
        collectDirectMatches(orderedCandidates)

        // Pass 2：若尚无精准命中，仅在候选为空或存在跨语种别名词时补充一次艺人专用搜索
        if (matchedByKey.isEmpty()) {
            val altTerms = ArtistAliasTable.alternateSearchTerms(query)
            if (orderedCandidates.isEmpty()) {
                ingestCandidates(searchArtistsOnly(query, 8))
                collectDirectMatches(orderedCandidates)
            }
            if (matchedByKey.isEmpty() && altTerms.isNotEmpty()) {
                val alt = altTerms.first()
                val altLang = when {
                    ArtistAliasTable.hasHanCharacters(alt) -> "zh-Hans-CN"
                    ArtistAliasTable.hasJapaneseKana(alt) -> "ja-JP"
                    else -> "en-US"
                }
                try {
                    val enc = encodeQuery(alt)
                    val path = "/v1/catalog/$sf/search?term=$enc&types=artists&limit=8&l=$altLang"
                    val root = JSONObject(ampGet(path))
                    val results = root.optJSONObject("results") ?: JSONObject()
                    ingestCandidates(pickArtists(results.optJSONObject("artists") ?: JSONObject(), "data"))
                    collectDirectMatches(orderedCandidates)
                } catch (e: Throwable) {
                    Logx.w(TAG, "resolveExactArtists alt search failed $alt: ${e.message}")
                }
            }
        }

        // Pass 3：未收录在别名表中的歌手，利用 Apple Music 多语言目录批量校验候选艺人 ID 的真实双语/日语名
        if (matchedByKey.isEmpty() && orderedCandidates.isNotEmpty()) {
            val queryHasHan = ArtistAliasTable.hasHanCharacters(query)
            val queryHasKana = ArtistAliasTable.hasJapaneseKana(query)
            val queryHasLatin = ArtistAliasTable.hasLatinLetters(query)

            val probeIds = orderedCandidates
                .asSequence()
                .filter { it.id.isNotBlank() }
                .take(8)
                .filter { candidate ->
                    val candHasHan = ArtistAliasTable.hasHanCharacters(candidate.name)
                    val candHasKana = ArtistAliasTable.hasJapaneseKana(candidate.name)
                    val candHasLatin = ArtistAliasTable.hasLatinLetters(candidate.name)
                    when {
                        // 查日语假名：校验 ja-JP（例如国区返回中文译名「宇多田光」，ja-JP 返回「宇多田ヒカル」）
                        queryHasKana -> primaryLang != "ja-JP" || !candHasKana
                        // 查英文/拉丁名，而候选含汉字或假名（例如国区返回「告五人」，en-US 返回「Accusefive」）
                        queryHasLatin && !queryHasHan && !queryHasKana -> candHasHan || candHasKana
                        // 查汉字（中文名或纯汉字日语名），而候选不含汉字（例如美区返回「Accusefive」/「Fujii Kaze」）
                        queryHasHan -> !candHasHan || (primaryLang != "zh-Hans-CN" && candHasLatin)
                        else -> false
                    }
                }
                .map { it.id }
                .distinct()
                .take(6)
                .toList()

            if (probeIds.isNotEmpty()) {
                val probeLangs = LinkedHashSet<String>()
                when {
                    queryHasKana -> probeLangs.add("ja-JP")
                    queryHasLatin && !queryHasHan -> probeLangs.add("en-US")
                    queryHasHan -> {
                        if (primaryLang != "zh-Hans-CN") probeLangs.add("zh-Hans-CN")
                        probeLangs.add("ja-JP")
                    }
                }
                for (probeLang in probeLangs) {
                    if (matchedByKey.isNotEmpty()) break
                    val localizedMap = getCatalogArtistsByIds(probeIds, lang = probeLang, targetStorefront = sf)
                    for (id in probeIds) {
                        val localized = localizedMap[id] ?: continue
                        if (ArtistAliasTable.matchesTargetKeys(localized.name, targetKeys)) {
                            val base = candidateById[id] ?: localized
                            if (base.artworkUrl.isBlank() && localized.artworkUrl.isNotBlank()) {
                                base.artworkUrl = localized.artworkUrl
                            }
                            if (base.genreNames.isEmpty() && localized.genreNames.isNotEmpty()) {
                                base.genreNames = localized.genreNames
                            }
                            matchedByKey[id] = base
                        }
                    }
                }
            }
        }

        if (matchedByKey.isEmpty()) return emptyList()

        // Pass 4：为精准命中的艺人补齐缺失的官方头像与元数据
        val missingMetaIds = matchedByKey.values
            .filter { it.id.isNotBlank() && (it.artworkUrl.isBlank() || it.name.isBlank()) }
            .map { it.id }
            .distinct()
        if (missingMetaIds.isNotEmpty()) {
            val enriched = getCatalogArtistsByIds(missingMetaIds, lang = primaryLang, targetStorefront = sf)
            for (artist in matchedByKey.values) {
                val fresh = enriched[artist.id] ?: continue
                if (artist.artworkUrl.isBlank() && fresh.artworkUrl.isNotBlank()) {
                    artist.artworkUrl = fresh.artworkUrl
                }
                if (artist.genreNames.isEmpty() && fresh.genreNames.isNotEmpty()) {
                    artist.genreNames = fresh.genreNames
                }
                if (artist.name.isBlank() && fresh.name.isNotBlank()) {
                    artist.name = fresh.name
                }
            }
        }

        return matchedByKey.values
            .sortedWith(
                compareByDescending<AppleArtist> { it.artworkUrl.isNotBlank() }
                    .thenByDescending { it.id.isNotBlank() }
            )
            .take(maxResults)
    }

    /** 目录艺人详情（使用当前账号真实区服，避免非国区 404）。 */
    fun getCatalogArtist(artistId: String): AppleArtist? {
        if (artistId.isEmpty()) return null
        val sf = effectiveStorefront()
        return try {
            fun fetchArtistResource(
                targetSf: String,
                language: String,
                includeMotionArtwork: Boolean = true
            ): JSONObject {
                val motionExtension = if (includeMotionArtwork) "%2CeditorialVideo" else ""
                return JSONObject(
                    ampGet(
                        "/v1/catalog/$targetSf/artists/$artistId" +
                            "?extend=artistBio%2CbornOrFormed%2Corigin%2CeditorialArtwork%2C" +
                            "hero%2ChasBiographyContent%2CisGroup%2CplainEditorialNotes" +
                            motionExtension +
                            "&include=artist-biography-canvas" +
                            "&format%5Bresources%5D=map&l=$language&platform=web"
                    )
                )
            }

            fun textContent(value: Any?): String = when (value) {
                is String -> value.trim()
                is JSONObject -> sequenceOf(
                    "standard",
                    "text",
                    "value",
                    "content",
                    "description",
                    "paragraphs",
                    "data"
                )
                    .map { key -> textContent(value.opt(key)) }
                    .firstOrNull(String::isNotBlank)
                    .orEmpty()
                is JSONArray -> (0 until value.length())
                    .asSequence()
                    .map { index -> textContent(value.opt(index)) }
                    .filter(String::isNotBlank)
                    .joinToString("\n\n")
                else -> ""
            }

            fun editorialDescription(attributes: JSONObject): String {
                return sequenceOf("artistBio", "plainEditorialNotes", "editorialNotes")
                    .map { key -> textContent(attributes.opt(key)) }
                    .firstOrNull(String::isNotBlank)
                    .orEmpty()
            }

            fun canvasBiography(response: JSONObject): String {
                val paragraphs = LinkedHashSet<String>()
                fun collect(value: Any?) {
                    when (value) {
                        is JSONObject -> {
                            val attributes = value.optJSONObject("attributes")
                            val style = attributes?.optJSONObject("display")
                                ?.optString("style", "")
                            if (style == "artist-about-text") {
                                textContent(attributes.opt("text"))
                                    .takeIf(String::isNotBlank)
                                    ?.let(paragraphs::add)
                            }
                            val keys = value.keys()
                            while (keys.hasNext()) collect(value.opt(keys.next()))
                        }
                        is JSONArray -> {
                            for (index in 0 until value.length()) collect(value.opt(index))
                        }
                    }
                }
                collect(response)
                return paragraphs.joinToString("\n\n")
            }

            val primaryLang = if (sf == "cn") "zh-Hans-CN" else langTag(sf)
            val root = try {
                fetchArtistResource(sf, primaryLang)
            } catch (e: Throwable) {
                if (sf != "cn") throw e
                fetchArtistResource("us", "en-US")
            }
            val data = root.optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val artistReference = data.optJSONObject(0) ?: return null
            val artistResource = resolveResource(
                artistReference,
                root.optJSONObject("resources") ?: JSONObject()
            )
            val a = AppleArtist()
            a.id = artistResource.optString("id", artistReference.optString("id", ""))
            val attrs = artistResource.optJSONObject("attributes") ?: JSONObject()
            a.name = ChineseText.toSimplified(attrs.optString("name", ""))
            a.artworkUrl = extractArtistArtwork(attrs)
            a.bornOrFormed = attrs.optString("bornOrFormed", "")
            a.isGroup = attrs.optBoolean("isGroup", false)
            a.genreNames = attrs.optJSONArray("genreNames")?.let { genres ->
                (0 until genres.length())
                    .map { index -> genres.optString(index, "").trim() }
                    .filter(String::isNotBlank)
            }.orEmpty()
            val editorialVideo = attrs.optJSONObject("editorialVideo")
            val motion = editorialVideo?.optJSONObject("motionArtistFullscreen16x9")
                ?: editorialVideo?.optJSONObject("motionArtistWide16x9")
                ?: editorialVideo?.optJSONObject("motionArtistSquare1x1")
            val preview = motion?.optJSONObject("previewFrame")
            a.motionVideoUrl = motion?.optString("video", "").orEmpty()
            a.motionPreviewUrl = preview?.optString("url", "").orEmpty()
                .replace("{w}", "2400")
                .replace("{h}", "1350")
                .replace("{f}", "jpg")
            a.motionBackgroundColor = preview?.optString("bgColor", "").orEmpty()
            a.motionTextColor = preview?.optString("textColor1", "").orEmpty()
            a.editorialDescription = canvasBiography(root).ifBlank { editorialDescription(attrs) }
            if (a.editorialDescription.isBlank() && primaryLang != "zh-Hans-CN") {
                try {
                    val zhRoot = fetchArtistResource(sf, "zh-Hans-CN", includeMotionArtwork = false)
                    val zhRef = zhRoot.optJSONArray("data")?.optJSONObject(0)
                    val zhArtist = zhRef?.let { resolveResource(it, zhRoot.optJSONObject("resources") ?: JSONObject()) }
                    val zhAttrs = zhArtist?.optJSONObject("attributes") ?: JSONObject()
                    a.editorialDescription = canvasBiography(zhRoot).ifBlank { editorialDescription(zhAttrs) }
                } catch (_: Exception) {
                }
            }
            if (a.editorialDescription.isBlank() && primaryLang != "en-US") {
                try {
                    val enRoot = fetchArtistResource(sf, "en-US", includeMotionArtwork = false)
                    val enRef = enRoot.optJSONArray("data")?.optJSONObject(0)
                    val enArtist = enRef?.let { resolveResource(it, enRoot.optJSONObject("resources") ?: JSONObject()) }
                    val enAttrs = enArtist?.optJSONObject("attributes") ?: JSONObject()
                    a.editorialDescription = editorialDescription(enAttrs).ifBlank { canvasBiography(enRoot) }
                } catch (_: Exception) {
                }
            }
            if (a.name.isEmpty()) null else a
        } catch (e: Throwable) {
            Logx.w(TAG, "getCatalogArtist failed $artistId: ${e.message}")
            null
        }
    }

    /** 某位目录艺人的专辑列表（使用当前账号真实区服，保证专辑曲目可播放）。 */
    fun getCatalogArtistAlbums(artistId: String, limit: Int, offset: Int): List<AppleAlbum> {
        val sf = effectiveStorefront()
        val path = "/v1/catalog/$sf/artists/$artistId/albums?limit=$limit&offset=$offset&l=${langTag(sf)}"
        return pickAlbums(JSONObject(ampGet(path)), "data", false).onEach {
            it.catalogStorefront = sf
        }
    }

    fun getCatalogArtistTopSongsPage(
        artistId: String,
        limit: Int = 20,
        offset: Int = 0
    ): PageResult<AppleSong> {
        require(artistId.matches(Regex("[A-Za-z0-9._-]+"))) { "无效的目录艺人 ID" }
        val sf = effectiveStorefront()
        val raw = ampGet(
            "/v1/catalog/$sf/artists/$artistId/view/top-songs" +
                "?l=${langTag(sf)}&limit=$limit&offset=$offset"
        )
        return parseSongs(raw, false).also { page ->
            page.items.forEach { it.artistId = artistId }
        }
    }

    fun getCatalogArtistTopSongs(
        artistId: String,
        limit: Int = 20,
        offset: Int = 0
    ): List<AppleSong> = getCatalogArtistTopSongsPage(artistId, limit, offset).items

    fun getFavoriteCatalogArtistIds(): Set<String>? {
        return try {
            val ids = LinkedHashSet<String>()
            var nextPath: String? = "/v1/me/favorites?types=artists&limit=100&l=${langTag()}&platform=web"
            var guard = 0
            while (!nextPath.isNullOrBlank() && guard < 50) {
                val r = ampRequest(nextPath, "GET", mapOf("media-user-token" to userToken))
                if (r.code != 200) break
                val root = JSONObject(r.text)
                val data = root.optJSONArray("data") ?: break
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.optString("id")
                        ?.takeIf(String::isNotBlank)
                        ?.let(ids::add)
                }
                val next = root.optString("next", "")
                nextPath = if (next.isNotBlank()) {
                    if (next.contains("platform=")) next else "$next&platform=web"
                } else {
                    null
                }
                guard++
            }
            ids
        } catch (error: Exception) {
            Logx.w(TAG, "无法读取已收藏艺人（此视图可能不受 storefront 支持）：${error.message}")
            null
        }
    }

    fun setCatalogArtistFavorite(artistId: String, favorite: Boolean) {
        require(artistId.matches(Regex("[A-Za-z0-9._-]+"))) { "无效的目录艺人 ID" }
        val encodedId = URLEncoder.encode(artistId, "UTF-8")
        val path = "/v1/me/favorites?art%5Burl%5D=f&ids%5Bartists%5D=$encodedId" +
            "&l=${langTag()}&platform=web"
        val response = ampRequest(
            path = path,
            method = if (favorite) "POST" else "DELETE",
            extraHeaders = mapOf("media-user-token" to userToken)
        )
        if (response.code !in 200..299) {
            throw IllegalStateException(
                "${if (favorite) "收藏" else "取消收藏"}艺人失败，HTTP " +
                    "${response.code}：${response.text.take(300)}"
            )
        }
        Logx.i(TAG, "catalog artist ${if (favorite) "favorited" else "unfavorited"} (${response.code}): $artistId")
    }

    fun getCatalogArtistPage(artistId: String, initialTopSongsLimit: Int = 5): AppleArtistPage {
        require(artistId.matches(Regex("[A-Za-z0-9._-]+"))) { "无效的目录艺人 ID" }
        val sf = effectiveStorefront()
        val lang = langTag(sf)
        val artist = getCatalogArtist(artistId)
            ?: throw IllegalStateException("Apple Music 没有找到此艺人")
        fun view(viewName: String, limit: Int? = null): JSONObject {
            val limitQuery = limit?.let { "&limit=$it" }.orEmpty()
            return JSONObject(
                ampGet("/v1/catalog/$sf/artists/$artistId/view/$viewName?l=$lang$limitQuery")
            )
        }
        fun optionalView(viewName: String, limit: Int? = null): JSONObject? = try {
            view(viewName, limit)
        } catch (error: Exception) {
            Logx.w(TAG, "艺人 $artistId 的 $viewName 视图不可用：${error.message}")
            null
        }

        val topSongs = getCatalogArtistTopSongs(artistId, limit = initialTopSongsLimit, offset = 0)
        val albums = getCatalogArtistAlbums(artistId, limit = 20, offset = 0)
            .filterNot { it.isSingle }
        val singles = optionalView("singles", 20)
            ?.let { pickAlbums(it, "data", false) }
            .orEmpty()
            .onEach { it.catalogStorefront = sf }
        val latestRelease = (albums + singles).maxByOrNull { it.releaseDate }
        val videos = optionalView("music-videos", 12)
            ?.let { pickAlbums(it, "data", false) }
            .orEmpty()
            .onEach { it.catalogStorefront = sf }
        val playlists = optionalView("artist-playlists", 12)
            ?.let { pickPlaylists(it, "data") }
            .orEmpty()
            .ifEmpty {
                optionalView("playlists", 12)
                    ?.let { pickPlaylists(it, "data") }
                    .orEmpty()
            }
        val similarArtists = optionalView("similar-artists", 12)
            ?.let { pickArtists(it, "data") }
            .orEmpty()

        topSongs.forEach { it.artistId = artist.id }
        albums.forEach { it.artistId = artist.id }
        singles.forEach { it.artistId = artist.id }
        videos.forEach { it.artistId = artist.id }
        return AppleArtistPage(
            artist = artist,
            topSongs = topSongs,
            latestRelease = latestRelease?.also { it.artistId = artist.id },
            albums = albums,
            singles = singles,
            musicVideos = videos,
            playlists = playlists,
            similarArtists = similarArtists
        )
    }

    /** 目录歌曲所属的专辑 id。 */
    fun getCatalogSongAlbumId(songId: String, catalogStorefront: String = storefront): String {
        if (songId.isEmpty()) return ""
        return try {
            val language = if (catalogStorefront == "cn") "zh-Hans-CN" else langTag()
            val data = JSONObject(
                ampGet(
                    "/v1/catalog/$catalogStorefront/songs/$songId" +
                        "?include=albums&l=$language"
                )
            )
                .optJSONArray("data") ?: return ""
            if (data.length() == 0) return ""
            data.getJSONObject(0).optJSONObject("relationships")
                ?.optJSONObject("albums")?.optJSONArray("data")?.optJSONObject(0)?.optString("id", "") ?: ""
        } catch (e: Throwable) {
            Logx.w(TAG, "getCatalogSongAlbumId failed $songId: ${e.message}")
            ""
        }
    }

    /** Fetch the catalog album represented by a song's albums relationship. */
    fun getCatalogAlbumForSong(
        songId: String,
        catalogStorefront: String = storefront
    ): AppleAlbum? {
        val albumId = getCatalogSongAlbumId(songId, catalogStorefront)
        if (albumId.isBlank()) return null
        return getCatalogAlbum(albumId, catalogStorefront)
    }

    fun getCatalogAlbum(albumId: String, catalogStorefront: String = storefront): AppleAlbum? {
        if (albumId.isBlank()) return null
        val language = if (catalogStorefront == "cn") "zh-Hans-CN" else langTag()
        val data = JSONObject(
            ampGet("/v1/catalog/$catalogStorefront/albums/$albumId?l=$language")
        ).optJSONArray("data") ?: return null
        return pickAlbums(JSONObject().put("data", data), "data", false).firstOrNull()
            ?.also { it.catalogStorefront = catalogStorefront }
    }

    /**
     * 目录专辑的曲目。
     * 注意：Apple 的 `albums/{id}?include=tracks` **不允许带 limit**
     * （会返回 400 "Limit may not be supplied on this request"）。
     */
    fun getCatalogAlbumTracks(
        albumId: String,
        catalogStorefront: String = storefront
    ): List<AppleSong> {
        if (albumId.isEmpty()) return emptyList()
        val language = if (catalogStorefront == "cn") "zh-Hans-CN" else langTag()
        val data = JSONObject(
            ampGet(
                "/v1/catalog/$catalogStorefront/albums/$albumId" +
                    "?include=tracks&l=$language"
            )
        ).optJSONArray("data") ?: error("Apple Music 没有返回专辑数据")
        check(data.length() > 0) { "Apple Music 没有找到专辑：$albumId" }
        val tracks = data.getJSONObject(0).optJSONObject("relationships")
            ?.optJSONObject("tracks") ?: error("Apple Music 没有返回专辑曲目")
        return pickSongs(tracks, "data", false).also {
            localizeCatalogSongs(it, catalogStorefront)
        }
    }

    fun localizeCatalogSongs(
        songs: MutableList<AppleSong>,
        catalogStorefront: String = "cn",
        resolveMissingByIsrc: Boolean = false
    ) {
        if (songs.isEmpty()) return
        val songsToLocalize = songs.filter {
            !hasHanCharacters(it.name) || !hasHanCharacters(it.artistName)
        }
        if (songsToLocalize.isEmpty()) return
        // 严格过滤掉 i.xxx 资料库 ID，避免整批 400；按每批最多 50 个分块请求
        val catalogIds = songsToLocalize.map { it.catalogId.ifBlank { it.id } }
            .filter { it.isNotBlank() && !it.startsWith("i.") }
            .distinct()
        val localized = HashMap<String, AppleSong>()
        val sf = catalogStorefront.ifBlank { "cn" }
        for (chunk in catalogIds.chunked(50)) {
            try {
                val data = JSONObject(
                    ampGet(
                        "/v1/catalog/$sf/songs?ids=${chunk.joinToString(",")}" +
                            "&include=artists&l=zh-Hans-CN"
                    )
                ).optJSONArray("data") ?: continue
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val attributes = item.optJSONObject("attributes") ?: continue
                    val id = item.optString("id", "")
                    if (id.isBlank()) continue
                    localized[id] = AppleSong(
                        id = id,
                        name = ChineseText.toSimplified(attributes.optString("name", "")),
                        artistName = ChineseText.toSimplified(attributes.optString("artistName", "")),
                        albumName = ChineseText.toSimplified(attributes.optString("albumName", "")),
                        isrc = attributes.optString("isrc", ""),
                        artistId = item.optJSONObject("relationships")
                            ?.optJSONObject("artists")
                            ?.optJSONArray("data")
                            ?.optJSONObject(0)
                            ?.optString("id", "")
                            .orEmpty()
                    )
                }
            } catch (error: Exception) {
                Logx.w(TAG, "catalog song ID localization chunk failed: ${error.message}")
            }
        }
        songsToLocalize.forEach { song ->
            var localizedSong = localized[song.catalogId.ifBlank { song.id }]
            if (
                (localizedSong == null || !hasHanCharacters(localizedSong.name + localizedSong.artistName)) &&
                resolveMissingByIsrc &&
                song.isrc.isNotBlank()
            ) {
                localizedSong = try {
                    getChineseCatalogSongByIsrc(song.isrc)
                } catch (error: Exception) {
                    Logx.w(TAG, "catalog song ISRC localization failed for ${song.isrc}: ${error.message}")
                    null
                }
            }
            if (
                localizedSong == null ||
                !hasHanCharacters(localizedSong.name + localizedSong.artistName)
            ) return@forEach
            if (localizedSong.name.isNotBlank()) {
                song.name = ChineseText.toSimplified(localizedSong.name)
            }
            if (localizedSong.artistName.isNotBlank()) {
                song.artistName = ChineseText.toSimplified(localizedSong.artistName)
            }
            if (localizedSong.albumName.isNotBlank()) {
                song.albumName = ChineseText.toSimplified(localizedSong.albumName)
            }
            if (song.artistId.isBlank()) song.artistId = localizedSong.artistId
        }
    }

    /** 批量按 ISRC 解析目标区服的目录歌曲（返回 UPPERCASE ISRC -> AppleSong）。 */
    fun getCatalogSongsByIsrcs(isrcs: List<String>, targetStorefront: String = storefront): Map<String, AppleSong> {
        val clean = isrcs.map { it.trim() }.filter(String::isNotBlank).distinct()
        if (clean.isEmpty()) return emptyMap()
        val sf = targetStorefront.ifBlank { effectiveStorefront() }
        val lang = langTag(sf)
        val out = LinkedHashMap<String, AppleSong>()
        for (chunk in clean.chunked(25)) {
            try {
                val encoded = URLEncoder.encode(chunk.joinToString(","), "UTF-8")
                val raw = ampGet("/v1/catalog/$sf/songs?filter%5Bisrc%5D=$encoded&l=$lang")
                val root = JSONObject(raw)
                val songs = pickSongs(root, "data", false)
                for (song in songs) {
                    val key = song.isrc.uppercase(Locale.ROOT)
                    if (key.isNotBlank() && !out.containsKey(key)) {
                        out[key] = song
                    }
                }
            } catch (e: Throwable) {
                Logx.w(TAG, "getCatalogSongsByIsrcs failed ($sf): ${e.message}")
            }
        }
        return out
    }

    fun getCatalogSongByIsrc(isrc: String, targetStorefront: String = storefront): AppleSong? {
        if (isrc.isBlank()) return null
        return getCatalogSongsByIsrcs(listOf(isrc), targetStorefront)[isrc.trim().uppercase(Locale.ROOT)]
    }

    private fun getChineseCatalogSongByIsrc(isrc: String): AppleSong? =
        getCatalogSongByIsrc(isrc, "cn")

    /** 云资料库歌曲 id（i.xxxx）→ 曲库 catalogId。 */
    fun librarySongCatalogId(libraryId: String): String {
        if (libraryId.isEmpty()) return ""
        return try {
            val root = JSONObject(
                ampGet("/v1/me/library/songs/$libraryId?include=catalog&l=en-US")
            )
            val data = root.optJSONArray("data") ?: return ""
            if (data.length() == 0) return ""
            val song = data.getJSONObject(0)
            val attributes = song.optJSONObject("attributes")
            val playParamsId = attributes?.optJSONObject("playParams")
                ?.optString("catalogId", "")
                .orEmpty()
            if (playParamsId.isNotBlank()) return playParamsId

            val relationships = song.optJSONObject("relationships")
            for (key in listOf("catalog", "catalog-songs")) {
                val related = relationships?.optJSONObject(key)?.optJSONArray("data")
                    ?.optJSONObject(0)
                    ?.optString("id", "")
                    .orEmpty()
                if (related.isNotBlank()) return related
            }
            ""
        } catch (e: Throwable) {
            Logx.w(TAG, "librarySongCatalogId failed $libraryId: ${e.message}")
            ""
        }
    }

    /**
     * 取歌词原始 JSON（拿不到返回 null）。
     *
     * ## 逐字歌词是**独立的资源路径**，不是查询参数
     *
     * 这一点踩过坑：一直以为要靠 `l[lyrics]=...` 换取值，于是把
     * `ttml` / `syllable` / `syllable-lyrics` / `syllableLyrics` / 数组形式
     * 全试了一遍 —— 响应**逐字节完全一样**（服务端直接忽略这些值）。
     *
     * 真正的两个资源是：
     * ```
     * GET /v1/catalog/{storefront}/songs/{id}/syllable-lyrics   → 逐字
     *      type=syllable-lyrics、itunes:timing="Word"、<p> 里带 <span begin end>
     * GET /v1/catalog/{storefront}/songs/{id}/lyrics?l[lyrics]=ttml  → 逐行
     *      type=lyrics、itunes:timing="Line"、<p> 里只有整行文本
     * ```
     * 所以先请求 `syllable-lyrics`，它 404（该曲没有逐字）时再退回行级。
     *
     * 资料库歌曲（`i.xxx`）内部接口不认，先用 [librarySongCatalogId] 换成
     * catalog id；万一换不到，再退回 `/v1/me/library/songs/{id}/lyrics`。
     *
     * 纯音乐/未收录歌词会 404 —— 返回 null，不抛异常。
     */
    fun getLyricsJson(songId: String, isLibrary: Boolean): String? {
        if (songId.isEmpty()) return null
        val sf = if (storefront.isNotEmpty()) storefront else "us"

        val catalogId = if (isLibrary) librarySongCatalogId(songId) else songId
        if (catalogId.isNotEmpty()) {
            // ① 逐字（卡拉OK）—— 优先，能拿到就用它
            tryAmpGet("/v1/catalog/$sf/songs/$catalogId/syllable-lyrics")?.let { return it }
            // ② 退回逐行
            tryAmpGet("/v1/catalog/$sf/songs/$catalogId/lyrics?l%5Blyrics%5D=ttml")?.let { return it }
        }
        // 【不要再退回 /v1/me/library/songs/{id}/...】
        // 实测资料库资源根本没有这两个 relationship，只会白打两次 400：
        //   /v1/me/library/songs/{id}/syllable-lyrics → 400 No relationship found
        //   /v1/me/library/songs/{id}/lyrics          → 400 No relationship found
        // 拿不到 catalog id 就直接认为没有歌词，交给上层显示「暂无歌词」。
        return null
    }

    /** 和 [ampGet] 一样，但非 200 时返回 null 而不是抛异常（歌词缺失是常态）。 */
    private fun tryAmpGet(path: String): String? {
        return try {
            val r = ampRequest(path, "GET")
            if (r.code != 200) {
                Logx.w(TAG, "歌词接口 HTTP ${r.code} @ $path")
                null
            } else {
                r.text
            }
        } catch (e: Throwable) {
            Logx.w(TAG, "歌词接口异常 @ $path: ${e.message}")
            null
        }
    }

    /** 目录歌曲 id → 其主艺人（目录艺人 id）。 */
    fun getCatalogSongArtistId(catalogSongId: String): String {
        if (catalogSongId.isEmpty()) return ""
        return try {
            val data = JSONObject(ampGet("/v1/catalog/$storefront/songs/$catalogSongId?include=artists&l=en-US"))
                .optJSONArray("data") ?: return ""
            if (data.length() == 0) return ""
            data.getJSONObject(0).optJSONObject("relationships")?.optJSONObject("artists")
                ?.optJSONArray("data")?.optJSONObject(0)?.optString("id", "") ?: ""
        } catch (e: Throwable) {
            Logx.w(TAG, "getCatalogSongArtistId failed $catalogSongId: ${e.message}")
            ""
        }
    }

    /**
     * 批量：目录歌曲 id → 这首歌的主艺人（含官方头像）。
     *
     * 用途：资料库歌曲里有 `playParams.catalogId`，但资料库接口不返回艺人头像；
     * 一次请求最多 100 个目录歌曲 id，返回的 `relationships.artists` 就带
     * `attributes.artwork`（Apple Music 官方艺人图），整库只要二三十次请求。
     */
    fun catalogArtistsForSongs(songIds: List<String>): Map<String, AppleArtist> {
        val out = HashMap<String, AppleArtist>()
        if (songIds.isEmpty()) return out
        return try {
            val ids = songIds.joinToString(",")
            val data = JSONObject(
                ampGet("/v1/catalog/$storefront/songs?ids=$ids&include=artists&l=${langTag()}")
            ).optJSONArray("data") ?: return out
            for (i in 0 until data.length()) {
                val it = data.getJSONObject(i)
                val id = it.optString("id", "")
                if (id.isEmpty()) continue
                val a = it.optJSONObject("relationships")?.optJSONObject("artists")
                    ?.optJSONArray("data")?.optJSONObject(0) ?: continue
                val attrs = a.optJSONObject("attributes") ?: JSONObject()
                val artist = AppleArtist()
                artist.id = a.optString("id", "")
                artist.name = ChineseText.toSimplified(attrs.optString("name", ""))
                artist.artworkUrl = bigArtwork(attrs.optJSONObject("artwork")?.optString("url", "") ?: "")
                out[id] = artist
            }
            out
        } catch (e: Throwable) {
            Logx.w(TAG, "catalogArtistsForSongs failed: ${e.message}")
            out
        }
    }

    /** 一次请求同时拿到专辑主艺人的 id / 名称 / 头像。 */
    fun catalogAlbumArtist(albumId: String): AppleArtist? {
        if (albumId.isEmpty()) return null
        return try {
            val data = JSONObject(ampGet("/v1/catalog/$storefront/albums/$albumId?include=artists&l=${langTag()}"))
                .optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val a = data.getJSONObject(0).optJSONObject("relationships")?.optJSONObject("artists")
                ?.optJSONArray("data")?.optJSONObject(0) ?: return null
            val attrs = a.optJSONObject("attributes") ?: JSONObject()
            val out = AppleArtist()
            out.id = a.optString("id", "")
            out.name = ChineseText.toSimplified(attrs.optString("name", ""))
            out.artworkUrl = bigArtwork(attrs.optJSONObject("artwork")?.optString("url", "") ?: "")
            if (out.id.isEmpty()) null else out
        } catch (e: Throwable) {
            Logx.w(TAG, "catalogAlbumArtist failed $albumId: ${e.message}")
            null
        }
    }

    /** 目录歌曲详情（补全元数据）。 */
    fun getCatalogSong(songId: String): AppleSong? {
        val txt = ampGet("/v1/catalog/$storefront/songs/$songId?l=en-US&include=albums,artists")
        val page = parseSongs(txt, false)
        return page.items.firstOrNull()
    }

    /** 电台曲目显示元数据优先使用国区简体中文，没有本地化时退回当前 storefront 语言。 */
    fun getCatalogSongLocalized(songId: String): AppleSong? {
        if (songId.isBlank()) return null
        val sourceSong = try {
            parseSongs(
                ampGet(
                    "/v1/catalog/$storefront/songs/$songId" +
                        "?l=${langTag()}&include=albums,artists"
                ),
                false
            ).items.firstOrNull()
        } catch (error: Exception) {
            Logx.w(TAG, "catalog song lookup failed for $songId: ${error.message}")
            null
        }
        if (sourceSong == null) {
            val cnSong = try {
                parseSongs(
                    ampGet(
                        "/v1/catalog/cn/songs/$songId" +
                            "?l=zh-Hans-CN&include=albums,artists"
                    ),
                    false
                ).items.firstOrNull()
            } catch (error: Exception) {
                Logx.w(TAG, "Chinese catalog song lookup failed for $songId: ${error.message}")
                null
            }
            return cnSong?.takeIf {
                storefront == "cn" || hasHanCharacters(it.name + it.artistName)
            }
        }
        if (storefront == "cn") return sourceSong
        localizeCatalogSongs(
            mutableListOf(sourceSong),
            catalogStorefront = "cn",
            resolveMissingByIsrc = true
        )
        return sourceSong
    }

    private fun hasHanCharacters(text: String): Boolean =
        text.any { it in '\u3400'..'\u9fff' }

    /** 把一首目录歌曲加入用户的 Apple Music 资料库。 */
    fun addCatalogSongToLibrary(songId: String): Boolean {
        if (songId.isEmpty()) return false
        val enc = URLEncoder.encode(songId, "UTF-8")
        val path = "/v1/me/library?ids%5Bsongs%5D=$enc"
        return try {
            val r = ampRequest(
                path = path,
                method = "POST",
                extraHeaders = mapOf(
                    "Content-Type" to "application/json",
                    "media-user-token" to userToken
                ),
                body = "{}".toByteArray(Charsets.UTF_8),
                readTimeoutMs = 20000
            )
            if (r.code in 200..299) {
                Logx.i(TAG, "song added to library (${r.code}): $songId")
                true
            } else {
                Logx.w(TAG, "add song failed HTTP ${r.code}: ${r.text.take(200)}")
                false
            }
        } catch (e: Throwable) {
            Logx.w(TAG, "add song exception: ${e.message}")
            false
        }
    }

    /** 使用 Apple Music 网页端接口切换目录歌曲的收藏状态（含 401 自动刷新重试）。 */
    fun setCatalogSongFavorite(songId: String, favorite: Boolean) {
        require(songId.matches(Regex("[A-Za-z0-9._-]+"))) { "无效的歌曲 ID" }
        val encodedId = URLEncoder.encode(songId, "UTF-8")
        val path = "/v1/me/favorites?art%5Burl%5D=f&ids%5Bsongs%5D=$encodedId" +
            "&l=${langTag()}&platform=web"
        val response = ampRequest(
            path = path,
            method = if (favorite) "POST" else "DELETE",
            extraHeaders = mapOf("media-user-token" to userToken)
        )
        if (response.code !in 200..299) {
            throw IllegalStateException(
                "${if (favorite) "收藏" else "取消收藏"}失败，HTTP " +
                    "${response.code}：${response.text.take(300)}"
            )
        }
        Logx.i(TAG, "catalog song ${if (favorite) "favorited" else "unfavorited"} (${response.code}): $songId")
    }

    fun addCatalogAlbumToLibrary(albumId: String): Boolean {
        if (albumId.isEmpty()) return false
        val enc = URLEncoder.encode(albumId, "UTF-8")
        val path = "/v1/me/library?ids%5Balbums%5D=$enc"
        return try {
            val r = ampRequest(
                path = path,
                method = "POST",
                extraHeaders = mapOf(
                    "Content-Type" to "application/json",
                    "media-user-token" to userToken
                ),
                body = "{}".toByteArray(Charsets.UTF_8),
                readTimeoutMs = 20000
            )
            if (r.code in 200..299) {
                Logx.i(TAG, "album added to library (${r.code}): $albumId")
                true
            } else {
                Logx.w(TAG, "add album failed HTTP ${r.code}: ${r.text.take(200)}")
                false
            }
        } catch (e: Throwable) {
            Logx.w(TAG, "add album exception: ${e.message}")
            false
        }
    }

    /** 结构化解析资料库专辑目录 ID（不再夹带 include=tracks 导致把曲目 ID 误判为专辑 ID）。 */
    fun libraryAlbumCatalogIds(): Set<String> {
        val ids = HashSet<String>()
        var nextPath: String? = "/v1/me/library/albums?limit=100&offset=0&l=en-US"
        var guard = 0
        while (!nextPath.isNullOrBlank() && guard < 50) {
            val raw = try {
                ampGet(nextPath)
            } catch (_: Throwable) {
                break
            }
            val root = JSONObject(raw)
            val data = root.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val cid = item.optJSONObject("attributes")
                    ?.optJSONObject("playParams")
                    ?.optString("catalogId", "")
                    .orEmpty()
                if (cid.isNotBlank()) ids.add(cid)
            }
            val next = root.optString("next", "")
            nextPath = next.takeIf { it.isNotBlank() }
            guard++
        }
        Logx.i(TAG, "library album catalogIds=${ids.size}")
        return ids
    }

    // ─────────────────────────── 播放资源 ───────────────────────────

    fun getWebPlayback(songId: String, isLibrary: Boolean): WebPlaybackInfo {
        ensureReady()
        val body = JSONObject().apply {
            if (isLibrary) put("universalLibraryId", songId) else put("salableAdamId", songId)
            put("language", "en-US")
        }.toString().toByteArray(Charsets.UTF_8)

        fun postOnce(): Http.Response {
            val h = LinkedHashMap(headers(true))
            h["Content-Type"] = "application/json"
            return Http.request(AppleConst.WEBPLAYBACK, "POST", h, body, 15000, 30000)
        }

        var r = postOnce()
        if (r.code == 401) {
            Logx.w(TAG, "webPlayback HTTP 401，刷新 developer token 并重试")
            refreshDeveloperToken(force = true)
            r = postOnce()
        }
        if (r.code != 200) {
            throw RuntimeException("webPlayback HTTP ${r.code}: ${r.text.take(200)}")
        }
        val root = JSONObject(r.text)
        val list = root.optJSONArray("songList") ?: JSONArray()
        val info = WebPlaybackInfo()
        info.songId = songId

        if (list.length() == 0) {
            val fail = root.optInt("failureType", 0)
            val msg = root.optString("customerMessage", "")
            val friendly = when (fail) {
                3077 -> "这首歌已从 Apple Music 下架，无法播放"
                1010 -> "这首歌没有可用的目录 ID（可能未在你所在区服上架）"
                0 -> msg.ifEmpty { "该歌曲没有可用的音频资源" }
                else -> msg.ifEmpty { "该歌曲没有可用的音频资源（failureType=$fail）" }
            }
            throw RuntimeException(friendly)
        }

        val first = list.optJSONObject(0)
        if (first != null) {
            info.songId = first.optString("songId", songId)
            val assets = first.optJSONArray("assets") ?: JSONArray()
            val out = ArrayList<AppleAsset>(assets.length())
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                out.add(AppleAsset(a.optString("flavor", ""), a.optString("URL", "")))
            }
            info.assets = out
        }
        return info
    }

    fun getPlayAssets(songId: String): PlayAssetInfo {
        val enc = URLEncoder.encode(songId, "UTF-8")
        val path = "/v1/play/assets?id=$enc&kind=song&includeLicenseUrls=true" +
            "&hlsEncryption=CBC&hlsProfile=enhancedHls"
        val info = PlayAssetInfo()
        try {
            val results = JSONObject(ampGet(path)).optJSONObject("results") ?: return info
            val arr = results.optJSONArray("assets") ?: return info
            val a = arr.optJSONObject(0) ?: return info
            info.hlsUrl = a.optString("url", "")
            info.keyServerUrl = a.optString("keyServerUrl", "")
            info.widevineCertUrl = a.optString("widevineKeyCertificateUrl", "")
            info.fairPlayCertUrl = a.optString("fairPlayKeyCertificateUrl", "")
        } catch (e: Throwable) {
            Logx.w(TAG, "getPlayAssets failed: ${e.message}")
        }
        return info
    }

    /** 下载任意 URL 为字节（HLS 清单 / 分片）。 */
    fun downloadBytes(url: String): ByteArray = downloadRange(url, -1, -1)

    /** 带 Range 的下载（不向媒体 CDN 泄漏账号令牌）。 */
    fun downloadRange(
        url: String,
        start: Int,
        end: Int,
        onBytesRead: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): ByteArray = Http.getBytes(url, mediaHeaders(), start, end, 60000, onBytesRead)

    /** 下载文本（m3u8）。 */
    fun downloadText(url: String): String = String(downloadBytes(url), Charsets.UTF_8)

    fun getLicense(challengeB64: String, uri: String, adamId: String): String {
        ensureReady()
        val body = JSONObject().apply {
            put("challenge", challengeB64)
            put("key-system", "com.widevine.alpha")
            put("uri", uri)
            put("adamId", adamId)
            put("isLibrary", false)
            put("user-initiated", true)
        }.toString().toByteArray(Charsets.UTF_8)
        var r = Http.request(AppleConst.LICENSE, "POST", licenseHeaders(), body, 15000, 30000)
        if (r.code == 401) {
            refreshDeveloperToken(force = true)
            r = Http.request(AppleConst.LICENSE, "POST", licenseHeaders(), body, 15000, 30000)
        }
        Logx.i(TAG, "license http=${r.code} resp=${r.text.take(200)}")
        val lic = try {
            JSONObject(r.text).optString("license", "")
        } catch (_: Throwable) {
            ""
        }
        if (lic.isEmpty()) throw RuntimeException("license 响应异常: ${r.text.take(200)}")
        return lic
    }

    // ─────────────────────────── JSON → 模型 ───────────────────────────

    private fun parseSongs(txt: String, isLibrary: Boolean): PageResult<AppleSong> {
        val root = JSONObject(txt)
        val page = PageResult<AppleSong>()
        page.items = pickSongs(root, "data", isLibrary)
        val meta = root.optJSONObject("meta")
        if (meta != null) page.total = meta.optInt("total", 0)
        page.next = root.optString("next", "")
        return page
    }

    private fun parseAlbums(txt: String, isLibrary: Boolean): PageResult<AppleAlbum> {
        val root = JSONObject(txt)
        val page = PageResult<AppleAlbum>()
        page.items = pickAlbums(root, "data", isLibrary)
        root.optJSONObject("meta")?.let { page.total = it.optInt("total", 0) }
        page.next = root.optString("next", "")
        return page
    }

    private fun parsePlaylists(txt: String): PageResult<ApplePlaylist> {
        val root = JSONObject(txt)
        val page = PageResult<ApplePlaylist>()
        page.items = pickPlaylists(root, "data")
        root.optJSONObject("meta")?.let { page.total = it.optInt("total", 0) }
        page.next = root.optString("next", "")
        return page
    }

    private fun pickSongs(root: JSONObject, key: String, isLibrary: Boolean): MutableList<AppleSong> {
        val out = ArrayList<AppleSong>()
        val data = root.optJSONArray(key) ?: return out
        for (i in 0 until data.length()) {
            val it = data.optJSONObject(i) ?: continue
            val attrs = it.optJSONObject("attributes") ?: JSONObject()
            val s = AppleSong()
            s.id = it.optString("id", "")
            s.name = ChineseText.toSimplified(attrs.optString("name", ""))
            s.artistName = ChineseText.toSimplified(attrs.optString("artistName", ""))
            s.albumName = ChineseText.toSimplified(attrs.optString("albumName", ""))
            s.isrc = attrs.optString("isrc", "")
            val relationships = it.optJSONObject("relationships")
            s.albumId = relationships?.optJSONObject("albums")
                ?.optJSONArray("data")?.optJSONObject(0)?.optString("id", "").orEmpty()
            val artistsArr = relationships?.optJSONObject("artists")?.optJSONArray("data")
            if (artistsArr != null && artistsArr.length() > 0) {
                val parsedArtists = ArrayList<Pair<String, String>>(artistsArr.length())
                for (ai in 0 until artistsArr.length()) {
                    val aObj = artistsArr.optJSONObject(ai) ?: continue
                    val aid = aObj.optString("id", "")
                    val aname = ChineseText.toSimplified(
                        aObj.optJSONObject("attributes")?.optString("name", "").orEmpty()
                    )
                    if (aid.isNotBlank() || aname.isNotBlank()) {
                        parsedArtists.add(aid to aname)
                    }
                }
                s.artists = parsedArtists
                s.artistId = parsedArtists.firstOrNull { it.first.isNotBlank() }?.first.orEmpty()
            }
            s.trackNumber = attrs.optInt("trackNumber", 0)
            s.discNumber = attrs.optInt("discNumber", 0)
            s.isLibrary = isLibrary
            s.durationMs = attrs.optLong("durationInMillis", 0)
            val rd = attrs.optString("releaseDate", "")
            if (rd.length >= 4) s.year = rd.substring(0, 4).toIntOrNull() ?: 0
            s.artworkUrl = bigArtwork(attrs.optJSONObject("artwork")?.optString("url", "") ?: "")
            val genres = attrs.optJSONArray("genreNames")
            if (genres != null && genres.length() > 0) {
                val parts = ArrayList<String>()
                for (g in 0 until genres.length()) {
                    val v = genres.optString(g, "")
                    if (v.isNotEmpty()) parts.add(v)
                }
                s.genre = parts.joinToString("|")
            }
            val traits = attrs.optJSONArray("audioTraits")
            if (traits != null) {
                val t = ArrayList<String>()
                for (g in 0 until traits.length()) t.add(traits.optString(g, ""))
                s.audioTraits = t
            }
            val pp = attrs.optJSONObject("playParams")
            if (pp != null) {
                val cid = pp.optString("catalogId", "")
                if (cid.isNotEmpty()) {
                    s.catalogId = cid
                    if (!isLibrary) s.id = cid
                }
            }
            out.add(s)
        }
        return out
    }

    private fun pickAlbums(root: JSONObject, key: String, isLibrary: Boolean): MutableList<AppleAlbum> {
        val out = ArrayList<AppleAlbum>()
        val data = root.optJSONArray(key) ?: return out
        for (i in 0 until data.length()) {
            val it = data.optJSONObject(i) ?: continue
            val attrs = it.optJSONObject("attributes") ?: JSONObject()
            val a = AppleAlbum()
            a.id = it.optString("id", "")
            a.name = ChineseText.toSimplified(attrs.optString("name", ""))
            a.artistName = ChineseText.toSimplified(attrs.optString("artistName", ""))
            a.isLibrary = isLibrary
            a.trackCount = attrs.optInt("trackCount", 0)
            a.isSingle = attrs.optBoolean("isSingle", false)
            a.artworkUrl = bigArtwork(attrs.optJSONObject("artwork")?.optString("url", "") ?: "")
            val rd = attrs.optString("releaseDate", "")
            a.releaseDate = rd
            if (rd.length >= 4) a.year = rd.substring(0, 4).toIntOrNull() ?: 0
            out.add(a)
        }
        return out
    }

    private fun pickPlaylists(root: JSONObject, key: String): MutableList<ApplePlaylist> {
        val out = ArrayList<ApplePlaylist>()
        val data = root.optJSONArray(key) ?: return out
        for (i in 0 until data.length()) {
            val it = data.optJSONObject(i) ?: continue
            val attrs = it.optJSONObject("attributes") ?: JSONObject()
            val p = ApplePlaylist()
            p.id = it.optString("id", "")
            p.name = ChineseText.toSimplified(attrs.optString("name", ""))
            p.description = attrs.optJSONObject("description")?.optString("standard", "") ?: ""
            p.trackCount = attrs.optInt("trackCount", 0)
            p.artworkUrl = bigArtwork(attrs.optJSONObject("artwork")?.optString("url", "") ?: "")
            out.add(p)
        }
        return out
    }

    private fun pickArtists(root: JSONObject, key: String): MutableList<AppleArtist> {
        val out = ArrayList<AppleArtist>()
        val data = root.optJSONArray(key) ?: return out
        for (i in 0 until data.length()) {
            val it = data.optJSONObject(i) ?: continue
            val attrs = it.optJSONObject("attributes") ?: JSONObject()
            val a = AppleArtist()
            a.id = it.optString("id", "")
            a.name = ChineseText.toSimplified(attrs.optString("name", ""))
            a.artworkUrl = extractArtistArtwork(attrs)
            a.genreNames = attrs.optJSONArray("genreNames")?.let { genres ->
                (0 until genres.length())
                    .map { index -> genres.optString(index, "").trim() }
                    .filter(String::isNotBlank)
            }.orEmpty()
            out.add(a)
        }
        return out
    }

    private fun extractArtistArtwork(attrs: JSONObject): String {
        val primary = attrs.optJSONObject("artwork")?.optString("url", "").orEmpty()
        if (primary.isNotBlank()) return bigArtwork(primary)
        val editorial = attrs.optJSONObject("editorialArtwork") ?: return ""
        for (slot in listOf(
            "storeFlowcase",
            "centeredFullscreenBackground",
            "subscriptionHero",
            "bannerUber",
            "vip"
        )) {
            val url = editorial.optJSONObject(slot)?.optString("url", "").orEmpty()
            if (url.isNotBlank()) return bigArtwork(url)
        }
        return ""
    }

    /** Apple 的封面 URL 模板替换成 600x600。 */
    fun bigArtwork(url: String): String {
        if (url.isEmpty()) return ""
        return url.replace("{w}", "600").replace("{h}", "600").replace("{f}", "jpg")
    }

    // ═══════════════════ 主页 / 新发现 / 广播 ═══════════════════

    /**
     * **主页**：个性化推荐货架。
     *
     * 参数与网页版 listen-now 请求保持一致；资源以 JSON:API resource map 返回，
     * 卡片内容及推荐理由由 [parseShelves] 从 resources 中解析。
     */
    private fun formatTimezoneOffset(): String {
        val offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val sign = if (offsetMs >= 0) "+" else "-"
        val totalMinutes = abs(offsetMs) / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return String.format(Locale.US, "%s%02d:%02d", sign, hours, minutes)
    }

    fun getRecommendations(): List<Shelf> {
        val timezone = formatTimezoneOffset()
        val types = listOf(
            "activities", "albums", "apple-curators", "artists", "concerts", "curators",
            "editorial-items", "library-albums", "library-playlists", "music-movies",
            "music-videos", "playlists", "social-profiles", "social-upsells", "songs",
            "stations", "tv-episodes", "tv-shows", "uploaded-audios", "uploaded-videos"
        ).joinToString("%2C")
        val path = "/v1/me/recommendations?art%5Burl%5D=f" +
            "&displayFilter%5Bkind%5D=" +
            "MusicCircleCoverShelf%2CMusicConcertsEmptyShelf%2CMusicCoverGrid%2C" +
            "MusicCoverShelf%2CMusicNotesHeroShelf%2CMusicSocialCardShelf%2C" +
            "MusicSuperHeroShelf" +
            "&extend=editorialArtwork%2CeditorialVideo%2CplainEditorialCard%2CplainEditorialNotes" +
            "&extend%5Bplaylists%5D=artistNames" +
            "&extend%5Bstations%5D=airTime%2CsupportsAirTimeUpdates" +
            "&fields%5Bartists%5D=name%2Cartwork%2Curl" +
            "&format%5Bresources%5D=map" +
            "&include%5Balbums%5D=artists" +
            "&include%5Blibrary-playlists%5D=catalog" +
            "&include%5Bpersonal-recommendation%5D=primary-content" +
            "&include%5Bstations%5D=radio-show" +
            "&l=${langTag()}" +
            "&meta%5Bstations%5D=inflectionPoints" +
            "&name=listen-now" +
            "&omit%5Bresource%5D=autos" +
            "&platform=web" +
            "&timezone=${URLEncoder.encode(timezone, "UTF-8")}" +
            "&types=$types" +
            "&with=library"
        val txt = ampGet(path)
        return parseShelves(JSONObject(txt))
    }

    /** 请求网页版实际使用的个性电台 next-tracks 接口（含 401 自动刷新重试）。 */
    fun probeStationNextTracks(stationId: String, limit: Int? = null): StationProbeResponse {
        require(stationId.matches(Regex("[A-Za-z0-9._-]+"))) { "无效的电台 ID" }
        require(limit == null || limit in 1..20) { "电台歌曲数量必须在 1 到 20 之间" }
        val path = "/v1/me/stations/next-tracks/$stationId" +
            (limit?.let { "?limit=$it" } ?: "")
        val response = ampRequest(
            path = path,
            method = "POST",
            extraHeaders = mapOf("media-user-token" to userToken)
        )
        return StationProbeResponse(path, response.code, response.text)
    }

    fun getCharts(): List<Shelf> {
        val sf = effectiveStorefront()
        val txt = ampGet("/v1/catalog/$sf/charts?types=songs,albums,playlists&limit=20&l=${langTag(sf)}")
        val results = JSONObject(txt).optJSONObject("results") ?: return emptyList()
        val out = ArrayList<Shelf>(3)
        for (type in listOf("songs", "albums", "playlists")) {
            val arr = results.optJSONArray(type) ?: continue
            if (arr.length() == 0) continue
            val chart = arr.optJSONObject(0) ?: continue
            val items = parseItems(chart.optJSONArray("data"))
            if (items.isEmpty()) continue
            out.add(
                Shelf(
                    id = "chart-$type",
                    title = chart.optString("name", "").ifEmpty { chartTitle(type) },
                    subtitle = "排行榜",
                    kind = "MusicCoverShelf",
                    items = items
                )
            )
        }
        return out
    }

    private fun chartTitle(type: String) = when (type) {
        "songs" -> "热门歌曲"
        "albums" -> "热门专辑"
        "playlists" -> "热门歌单"
        else -> "排行榜"
    }

    private fun parseShelves(root: JSONObject): List<Shelf> {
        val data = root.optJSONArray("data") ?: return emptyList()
        val resources = root.optJSONObject("resources") ?: JSONObject()
        val out = ArrayList<Shelf>(data.length())
        for (i in 0 until data.length()) {
            val reference = data.optJSONObject(i) ?: continue
            val s = resolveResource(reference, resources)
            val attrs = s.optJSONObject("attributes") ?: JSONObject()

            // 标题：网页版是 {"stringForDisplay":"..."}，有时直接是字符串。
            // 【坑】不能直接用 optString("title")：当 title 是对象时，
            // org.json 会把整个对象 toString() 出来，界面上就会显示
            // {"stringForDisplay":"专属推荐歌单"} 这种原始 JSON。
            val titleRaw = attrs.opt("title")
            val title = when (titleRaw) {
                is JSONObject -> titleRaw.optString("stringForDisplay", "")
                is String -> titleRaw
                else -> attrs.optString("name", "")
            }
            if (title.isEmpty()) continue

            val items = parseItems(
                s.optJSONObject("relationships")?.optJSONObject("contents")?.optJSONArray("data"),
                resources
            )
            if (items.isEmpty()) continue

            // display.kind（MusicCoverShelf / MusicNotesHeroShelf…）只是内部版式标记，
            // 不要当副标题显示给用户。副标题留空，界面会自动隐藏。
            val kind = attrs.optJSONObject("display")?.optString("kind", "") ?: ""
            out.add(
                Shelf(
                    id = s.optString("id", "shelf$i"),
                    title = title,
                    subtitle = "",
                    kind = kind,
                    items = items
                )
            )
        }
        return out
    }

    /** 把货架上的原始条目转成 [ShelfItem]（封面统一换成 600x600）。 */
    private fun parseItems(
        arr: JSONArray?,
        resources: JSONObject = JSONObject()
    ): List<ShelfItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<ShelfItem>(arr.length())
        for (i in 0 until arr.length()) {
            val reference = arr.optJSONObject(i) ?: continue
            val it = resolveResource(reference, resources)
            val attrs = it.optJSONObject("attributes") ?: continue
            val type = it.optString("type", reference.optString("type", ""))
            val name = attrs.optString("name", "")
            if (name.isEmpty()) continue
            val isContainerType = type.contains("album", ignoreCase = true) ||
                type.contains("playlist", ignoreCase = true) ||
                type.equals("stations", ignoreCase = true) ||
                type.equals("artists", ignoreCase = true)
            val songLike = !isContainerType && attrs.has("artistName") &&
                (attrs.has("durationInMillis") || attrs.has("albumName"))
            val catalogId = if (isContainerType) {
                ""
            } else {
                attrs.optJSONObject("playParams")
                    ?.optString("catalogId", "")
                    .orEmpty()
                    .ifBlank {
                        it.optJSONObject("relationships")
                            ?.optJSONObject("catalog")
                            ?.optJSONArray("data")
                            ?.optJSONObject(0)
                            ?.optString("id", "")
                            .orEmpty()
                    }
            }
            val relatedCatalogId = it.optJSONObject("relationships")
                ?.optJSONObject("catalog")
                ?.optJSONArray("data")
                ?.optJSONObject(0)
                ?.optString("id", "")
                .orEmpty()
            val descriptionValue = attrs.opt("description")
            val collectionDescription = when (descriptionValue) {
                is JSONObject -> descriptionValue.optString(
                    "standard",
                    descriptionValue.optString("stringForDisplay", "")
                )
                is String -> descriptionValue
                else -> ""
            }
            val curatorId = it.optJSONObject("relationships")
                ?.optJSONObject("curator")
                ?.optJSONArray("data")
                ?.optJSONObject(0)
                ?.optString("id", "")
                .orEmpty()
            val relatedCuratorName = resources.optJSONObject("curators")
                ?.optJSONObject(curatorId)
                ?.optJSONObject("attributes")
                ?.optString("name", "")
                .orEmpty()
            val creator = attrs.optString("curatorName", "").ifBlank {
                relatedCuratorName
            }.ifBlank {
                attrs.optString("artistName", "")
            }
            val editorialVideo = attrs.optJSONObject("editorialVideo")
            val motionArtwork = editorialVideo?.optJSONObject("motionDetailTall")
                ?: editorialVideo?.optJSONObject("motionTallVideo3x4")
                ?: editorialVideo?.optJSONObject("motionDetailSquare")
            val squareMotionArtwork = editorialVideo?.optJSONObject("motionDetailSquare")
            val motionPreview = motionArtwork?.optJSONObject("previewFrame")
            val staticDetailArtwork = attrs.optJSONObject("editorialArtwork")
                ?.optJSONObject("staticDetailTall")
            val ratioSource = motionPreview ?: staticDetailArtwork
            val artworkAspectRatio = if (ratioSource != null) {
                val width = ratioSource.optDouble("width", 0.0)
                val height = ratioSource.optDouble("height", 0.0)
                if (width > 0.0 && height > 0.0) {
                    (width / height).toFloat().coerceIn(0.5f, 2f)
                } else {
                    0.75f
                }
            } else {
                0.75f
            }
            val reason = reference.optJSONObject("meta")
                ?.optJSONObject("reason")
                ?.optString("stringForDisplay", "")
                .orEmpty()
            val subtitle = when (type) {
                "albums", "songs", "library-songs" -> attrs.optString("artistName", "")
                "playlists", "library-playlists" -> attrs.optJSONArray("artistNames")
                    ?.let { names -> (0 until names.length()).map { names.optString(it) }.joinToString(", ") }
                    ?.ifBlank { attrs.optString("curatorName", "") }
                    ?: attrs.optString("curatorName", "")
                "stations" -> attrs.optString("kind", "").let { k ->
                    when (k) {
                        "user" -> "个性化电台"
                        "live" -> "直播电台"
                        "editorial" -> "编辑电台"
                        else -> "电台"
                    }
                }

                else -> ""
            }
            out.add(
                ShelfItem(
                    id = it.optString("id", ""),
                    type = type,
                    title = name,
                    subtitle = subtitle,
                    artwork = bigArtwork(attrs.optJSONObject("artwork")?.optString("url", "") ?: ""),
                    reason = reason,
                    heroArtwork = tallArtwork(
                        attrs.optJSONObject("editorialArtwork")
                            ?.optJSONObject("superHeroTall")
                            ?.optString("url", "")
                            .orEmpty()
                    ),
                    catalogId = catalogId,
                    songLike = songLike,
                    relatedCatalogId = relatedCatalogId,
                    collectionDescription = collectionDescription,
                    creator = creator,
                    trackCount = attrs.optInt("trackCount", 0),
                    updatedAt = attrs.optString("lastModifiedDate", "")
                        .ifBlank { attrs.optString("releaseDate", "") },
                    ambientVideoUrl = motionArtwork?.optString("video", "").orEmpty(),
                    ambientPreviewUrl = motionArtworkUrl(motionPreview, 600, 800),
                    ambientSquareVideoUrl = squareMotionArtwork
                        ?.optString("video", "")
                        .orEmpty(),
                    ambientSquarePreviewUrl = motionArtworkUrl(
                        squareMotionArtwork?.optJSONObject("previewFrame"),
                        600,
                        600
                    ),
                    artworkAspectRatio = artworkAspectRatio
                )
            )
        }
        return out
    }

    private fun resolveResource(reference: JSONObject, resources: JSONObject): JSONObject {
        val type = reference.optString("type", "")
        val id = reference.optString("id", "")
        return resources.optJSONObject(type)?.optJSONObject(id) ?: reference
    }

    private fun tallArtwork(url: String): String {
        if (url.isEmpty()) return ""
        return url.replace("{w}", "600")
            .replace("{h}", "800")
            .replace("{f}", "jpg")
    }

    /**
     * 把主页货架里的「电台」挑出来，给**广播**页用。
     *
     * 个性电台曲目由服务端按需生成，常规目录关系接口未必能读取。
     * 候选端点可从独立的电台诊断页按账号实测；此列表仍只用于展示。
     */
    fun stationsFrom(shelves: List<Shelf>): List<Shelf> =
        shelves.filter { sh -> sh.items.any { it.isStation } }
            .map { sh -> Shelf(sh.id, sh.title, sh.subtitle, sh.kind, sh.items.filter { it.isStation }) }
            .filter { it.items.isNotEmpty() }
}

/** token 持久化的最小抽象（实现见 TokenStore，用 SharedPreferences）。 */
interface TokenStore {
    fun getString(key: String, def: String): String
    fun putString(key: String, value: String)
    fun remove(key: String)
}
