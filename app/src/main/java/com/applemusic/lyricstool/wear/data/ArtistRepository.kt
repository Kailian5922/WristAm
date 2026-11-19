package com.applemusic.lyricstool.wear.data

import android.util.LruCache
import com.applemusic.android.apple.AppleAlbum
import com.applemusic.android.apple.AppleArtistPage
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.android.apple.AppleSong
import com.applemusic.android.apple.ArtistAliasTable
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.apple.PageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 智能多艺人拆分工具：
 * 保留常见包含 `&` / `/` / `,` / `+` 的乐团与艺人全称不被误拆（如 AC/DC、Earth, Wind & Fire、Simon & Garfunkel 等）。
 */
object ArtistNameParser {
    private val PROTECTED_BAND_NAMES = setOf(
        "ac/dc",
        "b'z",
        "simon & garfunkel",
        "earth, wind & fire",
        "florence + the machine",
        "hall & oates",
        "daryl hall & john oates",
        "tyler, the creator",
        "panic! at the disco",
        "above & beyond",
        "crosby, stills & nash",
        "crosby, stills, nash & young",
        "peter, paul & mary",
        "peter, paul and mary",
        "blood, sweat & tears",
        "emerson, lake & palmer",
        "tegan and sara",
        "macklemore & ryan lewis",
        "brooks & dunn",
        "kool & the gang",
        "sly & the family stone",
        "tom petty & the heartbreakers",
        "bruce springsteen & the e street band",
        "mumford & sons",
        "of monsters and men",
        "foster the people",
        "guns n' roses",
        "l'arc-en-ciel"
    )

    private val DELIMITER_REGEX = Regex(
        """(?:\s*(?:/|&|、|,|，|\+)\s*|\s+(?:[fF][tT]\.?|[fF][eE][aA][tT]\.?|[wW][iI][tT][hH]|[xX])\s+)"""
    )

    fun splitArtists(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.lowercase() in PROTECTED_BAND_NAMES) {
            return listOf(trimmed)
        }
        val parts = trimmed.split(DELIMITER_REGEX)
            .map { it.trim().trim('\"') }
            .filter { it.isNotBlank() }
            .distinct()
        return if (parts.isEmpty()) listOf(trimmed) else parts
    }

    /** 清理 Apple 艺人简介中的 HTML 标签与常见 HTML 实体。 */
    fun cleanHtmlBio(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val noTags = raw
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>\\s*<p[^>]*>"), "\n\n")
            .replace(Regex("<[^>]*>"), "")
        return noTags
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
            }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}

data class ArtistLoadResult(
    val resolvedArtistId: String,
    val page: AppleArtistPage?,
    val localSongs: List<AppleLibrarySync.SongRow>,
    val errorMessage: String? = null
)

class ArtistRepository {
    private val pageCache = LruCache<String, AppleArtistPage>(8)

    suspend fun loadArtist(
        artistName: String,
        preferredArtistId: String = "",
        sourceSongId: String = "",
        sourceCatalogId: String = ""
    ): ArtistLoadResult = withContext(Dispatchers.IO) {
        val localSongs = MusicRepository.songsOfArtist(artistName)
        val api = MusicRepository.api
        val cleanArtistName = artistName.trim()

        fun isSingleArtistMatch(rowArtist: String): Boolean {
            val parts = ArtistNameParser.splitArtists(rowArtist)
            return parts.size == 1 && ArtistAliasTable.matchesArtistName(parts.first(), cleanArtistName)
        }

        fun pickMatchingCatalogArtistId(
            songArtists: List<com.applemusic.android.apple.AppleArtist>,
            targetName: String
        ): String {
            val valid = songArtists.filter { api.isCatalogArtistId(it.id) }
            if (valid.isEmpty()) return ""
            if (valid.size == 1) {
                val only = valid.first()
                if (targetName.isBlank() ||
                    ArtistAliasTable.matchesArtistName(only.name, targetName) ||
                    ArtistNameParser.splitArtists(targetName).size == 1
                ) {
                    return only.id
                }
            }
            valid.firstOrNull { ArtistAliasTable.matchesArtistName(it.name, targetName) }?.let {
                return it.id
            }
            // 多艺人合作曲目可能存在中英区服名差异，批量核验中/美/日三区官方名匹配所选艺人
            val ids = valid.map { it.id }.distinct()
            val probes = listOf("cn" to "zh-Hans-CN", "us" to "en-US", "jp" to "ja-JP")
            for ((probeSf, probeLang) in probes) {
                val localized = api.getCatalogArtistsByIds(ids, lang = probeLang, targetStorefront = probeSf)
                for (id in ids) {
                    val locName = localized[id]?.name.orEmpty()
                    if (locName.isNotBlank() && ArtistAliasTable.matchesArtistName(locName, targetName)) {
                        ArtistAliasTable.registerDynamicAliases(listOf(targetName, locName))
                        return id
                    }
                }
            }
            return if (valid.size == 1) valid.first().id else ""
        }

        fun resolveArtistIdFromSong(songId: String, catalogId: String): String {
            val cleanCat = catalogId.removePrefix("apple:").trim()
            val cleanSong = songId.removePrefix("apple:").trim()
            val resolvedCatalogSongId = when {
                cleanCat.isNotBlank() && !cleanCat.startsWith("i.") -> cleanCat
                cleanSong.isNotBlank() && !cleanSong.startsWith("i.") -> cleanSong
                cleanSong.startsWith("i.") -> api.librarySongCatalogId(cleanSong)
                else -> ""
            }
            if (resolvedCatalogSongId.isBlank()) return ""
            val songArtists = api.getCatalogSongArtists(resolvedCatalogSongId)
            return pickMatchingCatalogArtistId(songArtists, cleanArtistName)
        }

        var resolvedId = preferredArtistId.trim().takeIf { api.isCatalogArtistId(it) }.orEmpty()
        var resolvedFromSearch = false

        // 1) 优先从当前播放歌曲的目录关联艺人精确解析（解决非资料库曲目与跨语种艺人名问题）
        if (resolvedId.isBlank() && (sourceCatalogId.isNotBlank() || sourceSongId.isNotBlank())) {
            resolvedId = resolveArtistIdFromSong(sourceSongId, sourceCatalogId)
        }

        // 2) 检查本地资料库曲目中已有的纯数字目录艺人 ID（严格过滤旧缓存中的 r.xxx 资料库艺人 ID！）
        if (resolvedId.isBlank()) {
            val singleArtistSongs = localSongs.filter { isSingleArtistMatch(it.artist) }
            resolvedId = singleArtistSongs
                .firstOrNull { api.isCatalogArtistId(it.artistId) }
                ?.artistId
                .orEmpty()
        }

        // 3) 若本地曲目只有 catalogId / i.xxx，经由歌曲的目录 artists 关系反查真实数字目录艺人 ID
        if (resolvedId.isBlank() && localSongs.isNotEmpty()) {
            val candidateRow = localSongs.firstOrNull {
                isSingleArtistMatch(it.artist) && it.catalogId.isNotBlank()
            } ?: localSongs.firstOrNull {
                isSingleArtistMatch(it.artist) && it.appleId.isNotBlank()
            } ?: localSongs.firstOrNull { it.catalogId.isNotBlank() }
            if (candidateRow != null) {
                resolvedId = resolveArtistIdFromSong(candidateRow.appleId, candidateRow.catalogId)
            }
        }

        // 4) 若旧资料库快照仅保存了 r.xxx 资料库艺人 ID，尝试通过 /v1/me/library/artists/{id}?include=catalog 换取目录 ID
        if (resolvedId.isBlank() && localSongs.isNotEmpty()) {
            val libArtistId = localSongs.firstOrNull {
                isSingleArtistMatch(it.artist) && it.artistId.startsWith("r.")
            }?.artistId.orEmpty()
            if (libArtistId.isNotBlank()) {
                resolvedId = api.libraryArtistCatalogId(libArtistId)
            }
        }

        // 5) 动态跨区服多语言搜索精准解析目录艺人 ID（不依赖静态字典）
        fun resolveViaDynamicSearch(): String {
            val exact = api.resolveExactArtists(cleanArtistName, maxResults = 1)
            val exactId = exact.firstOrNull { api.isCatalogArtistId(it.id) }?.id.orEmpty()
            if (exactId.isNotBlank()) return exactId
            val broad = api.searchArtistsOnly(cleanArtistName, 5)
            return broad.firstOrNull {
                api.isCatalogArtistId(it.id) && ArtistAliasTable.matchesArtistName(it.name, cleanArtistName)
            }?.id.orEmpty().ifBlank {
                broad.firstOrNull { api.isCatalogArtistId(it.id) }?.id.orEmpty()
            }
        }

        if (resolvedId.isBlank()) {
            resolvedId = resolveViaDynamicSearch()
            resolvedFromSearch = true
        }

        if (resolvedId.isBlank()) {
            return@withContext ArtistLoadResult(
                resolvedArtistId = "",
                page = null,
                localSongs = localSongs,
                errorMessage = if (localSongs.isEmpty()) "未在 Apple Music 找到艺人：$artistName" else null
            )
        }

        val cached = synchronized(pageCache) { pageCache.get(resolvedId) }
        if (cached != null) {
            return@withContext ArtistLoadResult(
                resolvedArtistId = resolvedId,
                page = cached,
                localSongs = localSongs
            )
        }

        return@withContext try {
            // 直接要求 5 首初始热门单曲，消除旧版重复请求 top-songs 的额外延迟
            val page = api.getCatalogArtistPage(resolvedId, initialTopSongsLimit = 5)
            synchronized(pageCache) { pageCache.put(resolvedId, page) }
            ArtistLoadResult(
                resolvedArtistId = resolvedId,
                page = page,
                localSongs = localSongs
            )
        } catch (e: Exception) {
            // 若来源于歌曲/本地关系的 ID 失效，兜底用动态搜索重新解析一次目录艺人 ID
            if (!resolvedFromSearch) {
                val fallbackId = runCatching { resolveViaDynamicSearch() }.getOrDefault("")
                if (api.isCatalogArtistId(fallbackId) && fallbackId != resolvedId) {
                    val fallbackCached = synchronized(pageCache) { pageCache.get(fallbackId) }
                    if (fallbackCached != null) {
                        return@withContext ArtistLoadResult(
                            resolvedArtistId = fallbackId,
                            page = fallbackCached,
                            localSongs = localSongs
                        )
                    }
                    val retryPage = runCatching {
                        api.getCatalogArtistPage(fallbackId, initialTopSongsLimit = 5)
                    }.getOrNull()
                    if (retryPage != null) {
                        synchronized(pageCache) { pageCache.put(fallbackId, retryPage) }
                        return@withContext ArtistLoadResult(
                            resolvedArtistId = fallbackId,
                            page = retryPage,
                            localSongs = localSongs
                        )
                    }
                }
            }
            ArtistLoadResult(
                resolvedArtistId = resolvedId,
                page = null,
                localSongs = localSongs,
                errorMessage = if (localSongs.isEmpty()) {
                    "加载歌手页失败：${e.message ?: "网络超时"}"
                } else {
                    null
                }
            )
        }
    }

    suspend fun loadMoreTopSongs(
        artistId: String,
        offset: Int,
        limit: Int = 5
    ): Result<PageResult<AppleSong>> = withContext(Dispatchers.IO) {
        runCatching {
            MusicRepository.api.getCatalogArtistTopSongsPage(
                artistId = artistId,
                limit = limit,
                offset = offset
            )
        }
    }

    suspend fun loadAlbumTracks(album: AppleAlbum): Result<List<AppleSong>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sf = album.catalogStorefront.ifBlank { MusicRepository.api.effectiveStorefront() }
                MusicRepository.api.getCatalogAlbumTracks(album.id, sf)
            }
        }
}
