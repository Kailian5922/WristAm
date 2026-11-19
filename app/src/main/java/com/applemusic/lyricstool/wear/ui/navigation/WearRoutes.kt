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
    const val ARTIST_PICKER_PATTERN = "artist_picker/{rawArtists}"
    const val ARTIST_DETAIL_PATTERN = "artist_detail/{rawArtists}?artistId={artistId}"

    fun playlistDetail(playlistId: String): String =
        "playlist_detail/${Uri.encode(playlistId)}"

    fun artistPicker(rawArtists: String): String =
        "artist_picker/${Uri.encode(rawArtists)}"

    fun artistDetail(rawArtists: String, artistId: String = ""): String =
        if (artistId.isBlank()) {
            "artist_detail/${Uri.encode(rawArtists)}"
        } else {
            "artist_detail/${Uri.encode(rawArtists)}?artistId=${Uri.encode(artistId)}"
        }
}
