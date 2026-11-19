package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.lyricstool.wear.data.AuthRepository
import com.applemusic.lyricstool.wear.data.AuthState
import com.applemusic.lyricstool.wear.data.DownloadEvent
import com.applemusic.lyricstool.wear.data.DownloadRepository
import com.applemusic.lyricstool.wear.data.FavoritesRepository
import com.applemusic.lyricstool.wear.data.LibraryRepository
import com.applemusic.lyricstool.wear.data.PlaybackRepository
import com.applemusic.lyricstool.wear.data.SleepTimerRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ToastKind {
    Info,
    Success,
    Error
}

data class ToastMessage(
    val text: String,
    val isError: Boolean = false,
    val kind: ToastKind = if (isError) ToastKind.Error else ToastKind.Info,
    val id: Long = System.nanoTime()
)

class AppViewModel(
    private val authRepository: AuthRepository,
    playbackRepository: PlaybackRepository,
    private val sleepTimerRepository: SleepTimerRepository,
    private val favoritesRepository: FavoritesRepository,
    private val downloadRepository: DownloadRepository,
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.state

    val currentArtworkUrl: StateFlow<String?> = playbackRepository.stateFlow
        .map { snap -> (snap.currentSong ?: snap.targetSong)?.coverUrl?.ifBlank { null } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _toastEvents = MutableSharedFlow<ToastMessage>(extraBufferCapacity = 16)
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
        viewModelScope.launch {
            sleepTimerRepository.expiredEvents.collect {
                showToast("定时播放已结束，已暂停播放")
            }
        }
        viewModelScope.launch {
            downloadRepository.events.collect { event ->
                when (event) {
                    is DownloadEvent.Started ->
                        showStatusToast("开始下载《${event.songTitle}》", ToastKind.Info)
                    is DownloadEvent.AlreadyDownloading ->
                        showStatusToast("《${event.songTitle}》正在下载 (${event.percent}%)", ToastKind.Info)
                    is DownloadEvent.AlreadyDownloaded ->
                        showStatusToast("《${event.songTitle}》已下载", ToastKind.Success)
                    DownloadEvent.InvalidSong ->
                        showStatusToast("歌曲 ID 无效，无法下载", ToastKind.Error)
                    is DownloadEvent.Succeeded ->
                        showStatusToast("下载完成：《${event.songTitle}》", ToastKind.Success)
                    is DownloadEvent.Failed ->
                        showStatusToast("下载失败：《${event.songTitle}》${event.reason}", ToastKind.Error)
                    is DownloadEvent.Cancelled ->
                        showStatusToast("已取消下载《${event.songTitle}》", ToastKind.Info)
                }
            }
        }
    }

    fun showToast(message: String, isError: Boolean = false) {
        showStatusToast(message, if (isError) ToastKind.Error else ToastKind.Info)
    }

    fun showStatusToast(message: String, kind: ToastKind) {
        if (message.isBlank()) return
        _toastEvents.tryEmit(
            ToastMessage(
                text = message,
                isError = kind == ToastKind.Error,
                kind = kind
            )
        )
    }

    fun clearTemporaryCache() {
        viewModelScope.launch {
            val result = downloadRepository.clearTemporaryCache()
            result.fold(
                onSuccess = { removed ->
                    showStatusToast("已清理 $removed 首临时缓存，保留全部已下载歌曲", ToastKind.Success)
                },
                onFailure = { err ->
                    showStatusToast("清理缓存失败：${err.message ?: "未知错误"}", ToastKind.Error)
                }
            )
        }
    }

    fun signOut() {
        sleepTimerRepository.cancel()
        authRepository.signOut()
        showToast("已退出当前账号")
    }
}
