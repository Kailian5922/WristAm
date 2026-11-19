package com.applemusic.lyricstool.wear.data

import android.util.LruCache
import com.applemusic.android.apple.AppleAlbum
import com.applemusic.android.apple.AppleArtistPage
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.android.apple.AppleSong
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

    suspend fun loadArtist(artistName: String): ArtistLoadResult = withContext(Dispatchers.IO) {
        val localSongs = MusicRepository.songsOfArtist(artistName)
        val api = MusicRepository.api

        var resolvedId = localSongs.firstOrNull { it.artistId.isNotBlank() }?.artistId.orEmpty()

        if (resolvedId.isBlank()) {
            val anyCatalogId = localSongs.firstOrNull { it.catalogId.isNotBlank() }?.catalogId.orEmpty()
            if (anyCatalogId.isNotBlank()) {
                resolvedId = api.getCatalogSongArtistId(anyCatalogId)
            }
        }

        if (resolvedId.isBlank()) {
            val searchArtists = api.searchArtistsOnly(artistName, 5)
            resolvedId = searchArtists.firstOrNull {
                it.name.equals(artistName, ignoreCase = true)
            }?.id.orEmpty().ifBlank {
                searchArtists.firstOrNull()?.id.orEmpty()
            }
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
