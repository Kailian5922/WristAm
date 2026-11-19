package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.lyricstool.data.SongHit
import com.applemusic.lyricstool.wear.data.ActiveDownload
import com.applemusic.lyricstool.wear.data.DownloadRepository
import com.applemusic.lyricstool.wear.data.LibraryRepository
import com.applemusic.lyricstool.wear.data.PlaybackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistDetailUiState(
    val playlist: AppleLibrarySync.PlaylistRow? = null,
    val tracks: List<AppleLibrarySync.SongRow> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val downloadedIds: Set<String> = emptySet(),
    val activeDownloads: Map<String, ActiveDownload> = emptyMap()
)

class PlaylistDetailViewModel(
    private val playlistId: String,
    private val libraryRepository: LibraryRepository,
    private val playbackRepository: PlaybackRepository,
    private val downloadRepository: DownloadRepository
) : ViewModel() {

    private data class LocalPlaylistState(
        val playlist: AppleLibrarySync.PlaylistRow? = null,
        val tracks: List<AppleLibrarySync.SongRow> = emptyList(),
        val isLoading: Boolean = true,
        val errorMessage: String? = null
    )

    private val initialPlaylist = libraryRepository.getPlaylistById(playlistId)
    private val localState = MutableStateFlow(
        LocalPlaylistState(
            playlist = initialPlaylist,
            isLoading = true
        )
    )

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        localState,
        downloadRepository.state
    ) { local, dl ->
        PlaylistDetailUiState(
            playlist = local.playlist,
            tracks = local.tracks,
            isLoading = local.isLoading,
            errorMessage = local.errorMessage,
            downloadedIds = dl.downloadedIds,
            activeDownloads = dl.activeDownloads
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlaylistDetailUiState(playlist = initialPlaylist, isLoading = true)
    )

    init {
        loadTracks()
    }

    fun loadTracks() {
        viewModelScope.launch {
            localState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val res = libraryRepository.loadPlaylistTracks(playlistId)
                res.fold(
                    onSuccess = { list ->
                        localState.update {
                            it.copy(
                                tracks = list,
                                isLoading = false,
                                errorMessage = null
                            )
                        }
                    },
                    onFailure = { e ->
                        localState.update {
                            it.copy(
                                tracks = emptyList(),
                                isLoading = false,
                                errorMessage = "加载歌单失败：${e.message ?: "网络超时"}"
                            )
                        }
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    /** 播放完整歌单队列（不再截断到前 10 首）。 */
    fun playFromIndex(index: Int) {
        val list = localState.value.tracks
        if (list.isEmpty()) return
        playbackRepository.playSongRows(list, index.coerceIn(0, list.lastIndex))
    }

    fun downloadSong(song: SongHit) {
        downloadRepository.startDownload(song)
    }
}
