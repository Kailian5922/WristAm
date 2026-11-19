package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.applemusic.lyricstool.wear.di.WearAppContainer

class WearViewModelFactory(
    private val container: WearAppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        AppViewModel::class.java -> AppViewModel(
            authRepository = container.authRepository,
            playbackRepository = container.playbackRepository,
            sleepTimerRepository = container.sleepTimerRepository,
            favoritesRepository = container.favoritesRepository,
            downloadRepository = container.downloadRepository,
            libraryRepository = container.libraryRepository
        ) as T
        AuthViewModel::class.java -> AuthViewModel(container.authRepository) as T
        PlayerViewModel::class.java -> PlayerViewModel(
            playbackRepository = container.playbackRepository,
            favoritesRepository = container.favoritesRepository,
            sleepTimerRepository = container.sleepTimerRepository
        ) as T
        LyricsViewModel::class.java -> LyricsViewModel(
            playbackRepository = container.playbackRepository,
            lyricsRepository = container.lyricsRepository
        ) as T
        BrowseViewModel::class.java -> BrowseViewModel(
            libraryRepository = container.libraryRepository,
            downloadRepository = container.downloadRepository
        ) as T
        SearchViewModel::class.java -> SearchViewModel(
            qqMusicClient = container.qqMusicClient,
            downloadRepository = container.downloadRepository,
            playbackRepository = container.playbackRepository
        ) as T
        DownloadsViewModel::class.java -> DownloadsViewModel(
            downloadRepository = container.downloadRepository,
            playbackRepository = container.playbackRepository
        ) as T
        RadioViewModel::class.java -> RadioViewModel(
            radioRepository = container.radioRepository
        ) as T
        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class PlaylistDetailViewModelFactory(
    private val container: WearAppContainer,
    private val playlistId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass == PlaylistDetailViewModel::class.java) {
            return PlaylistDetailViewModel(
                playlistId = playlistId,
                libraryRepository = container.libraryRepository,
                playbackRepository = container.playbackRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class ArtistViewModelFactory(
    private val container: WearAppContainer,
    private val rawArtistParam: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass == ArtistViewModel::class.java) {
            return ArtistViewModel(
                rawArtistParam = rawArtistParam,
                artistRepository = container.artistRepository,
                playbackRepository = container.playbackRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
