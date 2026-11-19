package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.lyricstool.wear.data.LibraryRepository
import com.applemusic.lyricstool.wear.data.PlaybackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaylistDetailUiState(
    val playlist: AppleLibrarySync.PlaylistRow? = null,
    val tracks: List<AppleLibrarySync.SongRow> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class PlaylistDetailViewModel(
    private val playlistId: String,
    private val libraryRepository: LibraryRepository,
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PlaylistDetailUiState(
            playlist = libraryRepository.getPlaylistById(playlistId),
            isLoading = true
        )
    )
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    init {
        loadTracks()
    }

    fun loadTracks() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val res = libraryRepository.loadPlaylistTracks(playlistId)
                res.fold(
                    onSuccess = { list ->
                        _uiState.value = _uiState.value.copy(
                            tracks = list,
                            isLoading = false,
                            errorMessage = null
                        )
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            tracks = emptyList(),
                            isLoading = false,
                            errorMessage = "加载歌单失败：${e.message ?: "网络超时"}"
                        )
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    /** 播放完整歌单队列（不再截断到前 10 首）。 */
    fun playFromIndex(index: Int) {
        val list = _uiState.value.tracks
        if (list.isEmpty()) return
        playbackRepository.playSongRows(list, index.coerceIn(0, list.lastIndex))
    }
}
