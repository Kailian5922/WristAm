package com.applemusic.lyricstool.wear.ui.navigation

import android.net.Uri

object WearRoutes {
    const val MAIN = "main"
    const val QUEUE = "queue"
    const val SLEEP_TIMER = "sleep_timer"
    const val RADIO = "radio"
    const val LIBRARY_SONGS = "library_songs"
    const val PLAYLISTS = "playlists"
    const val PLAYLIST_DETAIL_PATTERN = "playlist_detail/{playlistId}"
    const val SEARCH = "search"
    const val DOWNLOADS = "downloads"
    const val ARTIST_PICKER_PATTERN = "artist_picker/{rawArtists}?songId={songId}&catalogId={catalogId}"
    const val ARTIST_DETAIL_PATTERN =
        "artist_detail/{rawArtists}?artistId={artistId}&songId={songId}&catalogId={catalogId}"
    const val ALBUM_DETAIL_PATTERN =
        "album_detail/{albumId}?name={name}&artist={artist}&artwork={artwork}&storefront={storefront}&year={year}&isLibrary={isLibrary}"

    fun playlistDetail(playlistId: String): String =
        "playlist_detail/${Uri.encode(playlistId)}"

    fun artistPicker(
        rawArtists: String,
        songId: String = "",
        catalogId: String = ""
    ): String {
        val params = buildList {
            if (songId.isNotBlank()) add("songId=${Uri.encode(songId)}")
            if (catalogId.isNotBlank()) add("catalogId=${Uri.encode(catalogId)}")
        }
        val base = "artist_picker/${Uri.encode(rawArtists)}"
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    fun artistDetail(
        rawArtists: String,
        artistId: String = "",
        songId: String = "",
        catalogId: String = ""
    ): String {
        val params = buildList {
            if (artistId.isNotBlank()) add("artistId=${Uri.encode(artistId)}")
            if (songId.isNotBlank()) add("songId=${Uri.encode(songId)}")
            if (catalogId.isNotBlank()) add("catalogId=${Uri.encode(catalogId)}")
        }
        val base = "artist_detail/${Uri.encode(rawArtists)}"
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    fun albumDetail(
        albumId: String,
        name: String = "",
        artist: String = "",
        artwork: String = "",
        storefront: String = "",
        year: Int = 0,
        isLibrary: Boolean = false
    ): String {
        val safeId = albumId.trim().ifBlank { "local" }
        val params = buildList {
            if (name.isNotBlank()) add("name=${Uri.encode(name)}")
            if (artist.isNotBlank()) add("artist=${Uri.encode(artist)}")
            if (artwork.isNotBlank()) add("artwork=${Uri.encode(artwork)}")
            if (storefront.isNotBlank()) add("storefront=${Uri.encode(storefront)}")
            if (year > 0) add("year=$year")
            if (isLibrary) add("isLibrary=true")
        }
        val base = "album_detail/${Uri.encode(safeId)}"
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }
}
