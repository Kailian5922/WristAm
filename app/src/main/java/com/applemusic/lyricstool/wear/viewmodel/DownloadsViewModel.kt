package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.android.apple.AppleCache
import com.applemusic.lyricstool.data.SongHit
import com.applemusic.lyricstool.wear.data.ActiveDownload
import com.applemusic.lyricstool.wear.data.DownloadRepository
import com.applemusic.lyricstool.wear.data.PlaybackRepository
import com.applemusic.lyricstool.wear.data.toSongHit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DownloadCategory {
    All,
    ByArtist,
    ByAlbum
}

data class DownloadsUiState(
    val category: DownloadCategory = DownloadCategory.All,
    val entries: List<AppleCache.Entry> = emptyList(),
    val activeDownloads: List<ActiveDownload> = emptyList(),
    val totalBytes: Long = 0L,
    val selectedArtist: String? = null,
    val selectedAlbum: String? = null
)

class DownloadsViewModel(
    private val downloadRepository: DownloadRepository,
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    private data class NavFilter(
        val category: DownloadCategory = DownloadCategory.All,
        val selectedArtist: String? = null,
        val selectedAlbum: String? = null
    )

    private val filter = MutableStateFlow(NavFilter())

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloadRepository.state,
        filter
    ) { dl, f ->
        // 如果当前浏览的歌手/专辑下最后一首歌已被删除，自动退回上级分类避免空屏
        val validArtist = f.selectedArtist?.takeIf { artist ->
            dl.entries.any { (it.artist.ifBlank { "未知歌手" }) == artist }
        }
        val validAlbum = f.selectedAlbum?.takeIf { album ->
            dl.entries.any { (it.album.ifBlank { "未知专辑" }) == album }
        }
        DownloadsUiState(
            category = f.category,
            entries = dl.entries,
            activeDownloads = dl.activeDownloads.values.toList(),
            totalBytes = dl.totalBytes,
            selectedArtist = validArtist,
            selectedAlbum = validAlbum
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    init {
        downloadRepository.refreshDownloaded()
    }

    fun selectCategory(category: DownloadCategory) {
        filter.update { it.copy(category = category, selectedArtist = null, selectedAlbum = null) }
    }

    fun openArtist(artist: String) {
        filter.update { it.copy(selectedArtist = artist, selectedAlbum = null) }
    }

    fun openAlbum(album: String) {
        filter.update { it.copy(selectedAlbum = album, selectedArtist = null) }
    }

    /** 返回 true 表示已退回一级；false 表示已在顶层，应关闭当前页。 */
    fun navigateBackLevel(): Boolean {
        val cur = uiState.value
        return if (cur.selectedArtist != null || cur.selectedAlbum != null) {
            filter.update { it.copy(selectedArtist = null, selectedAlbum = null) }
            true
        } else {
            false
        }
    }

    fun playEntry(entry: AppleCache.Entry) {
        playbackRepository.playSongs(listOf(entry.toSongHit()), 0)
    }

    fun playQueue(entries: List<AppleCache.Entry>, shuffle: Boolean) {
        if (entries.isEmpty()) return
        val hits: List<SongHit> = entries.map { it.toSongHit() }
        val finalQueue = if (shuffle) hits.shuffled() else hits
        playbackRepository.playSongs(finalQueue, 0)
    }

    fun deleteDownload(songId: String, title: String, onToast: (String, Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = downloadRepository.deleteDownload(songId)
            if (ok) {
                onToast("已删除《$title》", false)
            } else {
                onToast("删除失败或该曲正在下载中", true)
            }
        }
    }
}
