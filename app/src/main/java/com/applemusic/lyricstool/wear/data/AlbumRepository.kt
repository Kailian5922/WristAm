package com.applemusic.lyricstool.wear.data

import android.util.LruCache
import com.applemusic.android.apple.AppleAlbum
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.android.apple.AppleSong
import com.applemusic.android.apple.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AlbumDetailLoadResult(
    val album: AppleAlbum,
    val tracks: List<AppleSong>,
    val errorMessage: String? = null
)

class AlbumRepository {
    private val detailCache = LruCache<String, AlbumDetailLoadResult>(12)

    suspend fun loadAlbum(
        albumId: String,
        storefront: String = "",
        fallbackName: String = "",
        fallbackArtist: String = "",
        fallbackArtwork: String = "",
        fallbackYear: Int = 0,
        isLibrary: Boolean = false
    ): AlbumDetailLoadResult = withContext(Dispatchers.IO) {
        val cleanId = albumId.trim().takeUnless { it == "local" }.orEmpty()
        val cleanSf = storefront.trim()
        val cacheKey = "$cleanId|$cleanSf|$fallbackName|$fallbackArtist|$isLibrary"

        val cached = synchronized(detailCache) { detailCache.get(cacheKey) }
        if (cached != null && cached.tracks.isNotEmpty()) {
            return@withContext cached
        }

        val fallbackAlbum = AppleAlbum(
            id = cleanId,
            name = fallbackName.ifBlank { "专辑" },
            artistName = fallbackArtist,
            artworkUrl = fallbackArtwork,
            year = fallbackYear,
            isLibrary = isLibrary,
            catalogStorefront = cleanSf
        )

        val localTracks = if (fallbackName.isNotBlank()) {
            MusicRepository.songsOfAlbum(fallbackName, fallbackArtist).map { it.toAppleSong() }
        } else {
            emptyList()
        }

        if (isLibrary || cleanId.isBlank()) {
            if (localTracks.isNotEmpty()) {
                val localAlbum = fallbackAlbum.copy(
                    artistId = localTracks.firstOrNull {
                        MusicRepository.api.isCatalogArtistId(it.artistId)
                    }?.artistId.orEmpty(),
                    artworkUrl = fallbackAlbum.artworkUrl.ifBlank {
                        localTracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()
                    },
                    year = if (fallbackAlbum.year > 0) {
                        fallbackAlbum.year
                    } else {
                        localTracks.firstOrNull { it.year > 0 }?.year ?: 0
                    },
                    trackCount = localTracks.size
                )
                return@withContext AlbumDetailLoadResult(
                    album = localAlbum,
                    tracks = localTracks
                )
            }
            return@withContext AlbumDetailLoadResult(
                album = fallbackAlbum,
                tracks = emptyList(),
                errorMessage = "未找到该专辑的曲目"
            )
        }

        return@withContext try {
            val detail = MusicRepository.api.getCatalogAlbumDetail(
                albumId = cleanId,
                catalogStorefront = cleanSf.ifBlank { MusicRepository.api.effectiveStorefront() }
            )
            val mergedAlbum = detail.album.copy(
                name = detail.album.name.ifBlank { fallbackAlbum.name },
                artistName = detail.album.artistName.ifBlank { fallbackAlbum.artistName },
                artworkUrl = detail.album.artworkUrl.ifBlank { fallbackAlbum.artworkUrl },
                year = if (detail.album.year > 0) detail.album.year else fallbackAlbum.year,
                trackCount = if (detail.album.trackCount > 0) detail.album.trackCount else detail.tracks.size
            )
            val result = AlbumDetailLoadResult(
                album = mergedAlbum,
                tracks = detail.tracks
            )
            if (result.tracks.isNotEmpty()) {
                synchronized(detailCache) { detailCache.put(cacheKey, result) }
            }
            result
        } catch (e: Exception) {
            if (localTracks.isNotEmpty()) {
                AlbumDetailLoadResult(
                    album = fallbackAlbum.copy(trackCount = localTracks.size),
                    tracks = localTracks
                )
            } else {
                AlbumDetailLoadResult(
                    album = fallbackAlbum,
                    tracks = emptyList(),
                    errorMessage = "加载专辑失败：${e.message ?: "网络超时"}"
                )
            }
        }
    }

    private fun AppleLibrarySync.SongRow.toAppleSong(): AppleSong = AppleSong(
        id = appleId.ifBlank { catalogId },
        name = title,
        artistName = artist,
        albumName = album,
        isrc = isrc,
        artistId = artistId.takeIf { it.isNotBlank() && it.all { ch -> ch in '0'..'9' } }.orEmpty(),
        durationMs = durationMs,
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = year,
        artworkUrl = coverUrl,
        isLibrary = isLibrary,
        genre = genre,
        catalogId = catalogId
    )
}
