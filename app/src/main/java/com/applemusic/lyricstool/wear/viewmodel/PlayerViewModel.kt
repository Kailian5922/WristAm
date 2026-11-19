package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.lyricstool.data.SongHit
import com.applemusic.lyricstool.wear.data.FavoritesRepository
import com.applemusic.lyricstool.wear.data.PlaybackRepository
import com.applemusic.lyricstool.wear.data.resolveFavoriteCatalogId
import com.applemusic.lyricstool.wear.data.toSongHit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentSong: SongHit? = null,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val preparingNote: String = "",
    val durationMs: Long = 0L,
    val queueUris: List<String> = emptyList(),
    val currentQueueIndex: Int = -1,
    val isFavorited: Boolean = false,
    val favoritePending: Boolean = false,
    val seekRevision: Long = 0L
)

class PlayerViewModel(
    private val playbackRepository: PlaybackRepository,
    private val favoritesRepository: FavoritesRepository
) : ViewModel() {

    private val resolvedCatalogIds = MutableStateFlow<Map<String, String>>(emptyMap())

    val uiState: StateFlow<PlayerUiState> = combine(
        playbackRepository.stateFlow,
        favoritesRepository.state,
        resolvedCatalogIds
    ) { snap, favState, resolvedMap ->
        val row = snap.currentSong ?: snap.targetSong
        val song = row?.toSongHit()
        val catId = song?.let { s ->
            s.resolveFavoriteCatalogId().ifBlank { resolvedMap[s.id].orEmpty() }
        }.orEmpty()

        PlayerUiState(
            currentSong = song,
            isPlaying = snap.isPlaying,
            isPreparing = snap.preparing,
            preparingNote = snap.preparingNote,
            durationMs = snap.durationMs,
            queueUris = snap.queue,
            currentQueueIndex = if (snap.playingIndex >= 0) snap.playingIndex else snap.queueIndex,
            isFavorited = catId.isNotBlank() && catId in favState.favoritedCatalogIds,
            favoritePending = catId.isNotBlank() && favState.pendingSongId == catId,
            seekRevision = snap.seekRevision
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    init {
        viewModelScope.launch {
            playbackRepository.stateFlow
                .map { (it.currentSong ?: it.targetSong)?.toSongHit() }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collect { song ->
                    if (song != null && song.id.removePrefix("apple:").startsWith("i.")) {
                        val resolved = favoritesRepository.ensureCatalogIdResolved(song)
                        if (resolved.isNotBlank()) {
                            resolvedCatalogIds.value = resolvedCatalogIds.value + (song.id to resolved)
                        }
                    }
                }
        }
    }

    fun positionMs(): Long = playbackRepository.positionMs()
    fun durationMs(): Long = playbackRepository.durationMs()

    fun togglePlayPause() {
        playbackRepository.toggle()
    }

    fun next() {
        playbackRepository.next()
    }

    fun prev() {
        playbackRepository.prev()
    }

    fun playAt(index: Int) {
        playbackRepository.playAt(index)
    }

    fun toggleFavorite(onToast: (String, Boolean) -> Unit) {
        val song = uiState.value.currentSong ?: return
        viewModelScope.launch {
            val result = favoritesRepository.toggleFavorite(song)
            result.fold(
                onSuccess = { added ->
                    onToast(if (added) "已添加到收藏与资料库" else "已从收藏中移除", false)
                },
                onFailure = { e ->
                    onToast(e.message ?: "收藏操作失败", true)
                }
            )
        }
    }
}
