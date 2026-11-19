package com.applemusic.lyricstool.wear.data

import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.android.apple.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class LibraryState(
    val songs: List<AppleLibrarySync.SongRow> = emptyList(),
    val playlists: List<AppleLibrarySync.PlaylistRow> = emptyList(),
    val syncing: Boolean = false,
    val syncNote: String = "",
    val syncPercent: Int = 0
)

class LibraryRepository {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val repoListener: () -> Unit = {
        _state.value = snapshot()
    }

    init {
        MusicRepository.addListener(repoListener)
    }

    private fun snapshot(): LibraryState {
        if (!MusicRepository.isInitialized()) return LibraryState()
        return LibraryState(
            songs = MusicRepository.allSongs(),
            playlists = MusicRepository.allPlaylists(),
            syncing = MusicRepository.syncing,
            syncNote = MusicRepository.syncNote,
            syncPercent = MusicRepository.syncPercent
        )
    }

    fun refreshSnapshot() {
        _state.value = snapshot()
    }

    suspend fun syncAll(): Pair<Boolean, String> = suspendCancellableCoroutine { cont ->
        if (!MusicRepository.isInitialized()) {
            cont.resume(false to "资料库尚未初始化")
            return@suspendCancellableCoroutine
        }
        _state.value = _state.value.copy(syncing = true)
        MusicRepository.syncAll { ok, msg ->
            _state.value = snapshot()
            if (cont.isActive) {
                cont.resume(ok to msg)
            }
        }
    }

    fun getPlaylistById(playlistId: String): AppleLibrarySync.PlaylistRow? =
        if (MusicRepository.isInitialized()) MusicRepository.playlistById(playlistId) else null

    suspend fun loadPlaylistTracks(playlistId: String): Result<List<AppleLibrarySync.SongRow>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val local = MusicRepository.playlistSongs(playlistId)
                if (local.isNotEmpty()) {
                    local
                } else {
                    MusicRepository.loadPlaylistTracks(playlistId)
                }
            }
        }
}
