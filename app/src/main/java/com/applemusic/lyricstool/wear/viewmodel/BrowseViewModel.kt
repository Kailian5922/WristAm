package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.lyricstool.wear.data.DownloadRepository
import com.applemusic.lyricstool.wear.data.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BrowseUiState(
    val librarySongs: List<AppleLibrarySync.SongRow> = emptyList(),
    val playlists: List<AppleLibrarySync.PlaylistRow> = emptyList(),
    val syncing: Boolean = false,
    val syncNote: String = "",
    val downloadedCount: Int = 0,
    val activeDownloadCount: Int = 0
)

class BrowseViewModel(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository
) : ViewModel() {

    val uiState: StateFlow<BrowseUiState> = combine(
        libraryRepository.state,
        downloadRepository.state
    ) { lib, dl ->
        BrowseUiState(
            librarySongs = lib.songs,
            playlists = lib.playlists,
            syncing = lib.syncing,
            syncNote = lib.syncNote,
            downloadedCount = dl.entries.size,
            activeDownloadCount = dl.activeDownloads.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    fun syncLibrary(onToast: (String, Boolean) -> Unit) {
        if (uiState.value.syncing) return
        viewModelScope.launch {
            val (ok, msg) = libraryRepository.syncAll()
            if (ok) {
                onToast(msg.ifBlank { "全库同步完成" }, false)
            } else {
                onToast(msg.ifBlank { "同步失败" }, true)
            }
        }
    }
}
