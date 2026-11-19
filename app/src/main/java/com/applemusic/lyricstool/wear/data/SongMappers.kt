package com.applemusic.lyricstool.wear.data

import com.applemusic.android.apple.AppleCache
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.android.apple.AppleSong
import com.applemusic.android.apple.MusicRepository
import com.applemusic.lyricstool.data.SongHit

fun SongHit.toSongRow(): AppleLibrarySync.SongRow {
    val cleanId = id.removePrefix("apple:").trim()
    val existing = MusicRepository.findSong(cleanId) ?: MusicRepository.findSong("apple:$cleanId")
    if (existing != null) {
        if (existing.coverUrl.isBlank() && artworkUrl.isNotBlank()) existing.coverUrl = artworkUrl
        if (existing.durationMs <= 0L && durationMs > 0L) existing.durationMs = durationMs
        return existing
    }
    return AppleLibrarySync.SongRow().also { row ->
        row.appleId = cleanId
        row.uri = "apple:$cleanId"
        row.title = title
        row.artist = artist
        row.album = album
        row.durationMs = durationMs
        row.coverUrl = artworkUrl
        row.catalogId = providerSongId.orEmpty().removePrefix("apple:").ifBlank {
            if (cleanId.startsWith("i.")) "" else cleanId
        }
        row.isrc = isrc.orEmpty()
        row.isLibrary = cleanId.startsWith("i.")
    }
}

fun AppleLibrarySync.SongRow.toSongHit(): SongHit =
    SongHit(
        id = appleId.ifBlank { catalogId },
        title = title.ifBlank { "未知歌曲" },
        artist = artist,
        album = album,
        artworkUrl = coverUrl,
        durationMs = durationMs,
        providerSongId = catalogId.takeIf(String::isNotBlank),
        isrc = isrc.takeIf(String::isNotBlank)
    )

fun AppleSong.toSongHit(): SongHit =
    SongHit(
        id = id,
        title = name.ifBlank { "未知歌曲" },
        artist = artistName,
        album = albumName,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
        providerSongId = catalogId.ifBlank { id },
        isrc = isrc.takeIf(String::isNotBlank)
    )

/** 将离线缓存条目转换为 SongHit，自动从资料库补齐封面 URL 与时长元数据。 */
fun AppleCache.Entry.toSongHit(): SongHit {
    val cleanId = songId.removePrefix("apple:").trim()
    val known = if (MusicRepository.isInitialized()) {
        MusicRepository.findSong(cleanId) ?: MusicRepository.findSong("apple:$cleanId")
    } else {
        null
    }
    return SongHit(
        id = cleanId,
        title = title.ifBlank { known?.title.orEmpty() }.ifBlank { "未知歌曲" },
        artist = artist.ifBlank { known?.artist.orEmpty() }.ifBlank { "未知歌手" },
        album = album.ifBlank { known?.album.orEmpty() },
        artworkUrl = known?.coverUrl.orEmpty(),
        durationMs = known?.durationMs ?: 0L,
        providerSongId = known?.catalogId?.takeIf(String::isNotBlank)
            ?: cleanId.takeUnless { it.startsWith("i.") },
        isrc = known?.isrc?.takeIf(String::isNotBlank)
    )
}

fun SongHit.resolveFavoriteCatalogId(): String {
    val cleanId = id.removePrefix("apple:").trim()
    val row = if (MusicRepository.isInitialized()) {
        MusicRepository.findSong("apple:$cleanId") ?: MusicRepository.findSong(cleanId)
    } else {
        null
    }
    return row?.catalogId?.takeIf(String::isNotBlank)
        ?: providerSongId?.removePrefix("apple:")?.takeIf { it.isNotBlank() && !it.startsWith("i.") }
        ?: cleanId.takeUnless { it.startsWith("i.") }.orEmpty()
}
