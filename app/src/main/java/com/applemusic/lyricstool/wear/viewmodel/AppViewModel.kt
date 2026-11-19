package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.lyricstool.wear.data.AuthRepository
import com.applemusic.lyricstool.wear.data.AuthState
import com.applemusic.lyricstool.wear.data.DownloadRepository
import com.applemusic.lyricstool.wear.data.FavoritesRepository
import com.applemusic.lyricstool.wear.data.LibraryRepository
import com.applemusic.lyricstool.wear.data.PlaybackRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ToastMessage(
    val text: String,
    val isError: Boolean = false,
    val id: Long = System.nanoTime()
)

class AppViewModel(
    private val authRepository: AuthRepository,
    playbackRepository: PlaybackRepository,
    private val favoritesRepository: FavoritesRepository,
    private val downloadRepository: DownloadRepository,
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.state

    val currentArtworkUrl: StateFlow<String?> = playbackRepository.stateFlow
        .map { snap -> (snap.currentSong ?: snap.targetSong)?.coverUrl?.ifBlank { null } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _toastEvents = MutableSharedFlow<ToastMessage>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<ToastMessage> = _toastEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            authRepository.state
                .map { it.signedIn }
                .distinctUntilChanged()
                .collect { signedIn ->
                    if (signedIn) {
                        favoritesRepository.refreshFromServer()
                        downloadRepository.refreshDownloaded()
                        libraryRepository.refreshSnapshot()
                    }
                }
        }
    }

    fun showToast(message: String, isError: Boolean = false) {
        if (message.isBlank()) return
        _toastEvents.tryEmit(ToastMessage(text = message, isError = isError))
    }

    fun clearTemporaryCache() {
        viewModelScope.launch {
            val result = downloadRepository.clearTemporaryCache()
            result.fold(
                onSuccess = { removed ->
                    showToast("已清理 $removed 首临时缓存，保留全部已下载歌曲")
                },
                onFailure = { err ->
                    showToast("清理缓存失败：${err.message ?: "未知错误"}", isError = true)
                }
            )
        }
    }

    fun signOut() {
        authRepository.signOut()
        showToast("已退出当前账号")
    }
}
