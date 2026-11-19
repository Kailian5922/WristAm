package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.android.apple.AppleSong
import com.applemusic.lyricstool.data.SongHit
import com.applemusic.lyricstool.wear.data.ActiveDownload
import com.applemusic.lyricstool.wear.data.AlbumRepository
import com.applemusic.lyricstool.wear.data.DownloadRepository
import com.applemusic.lyricstool.wear.data.PlaybackRepository
import com.applemusic.lyricstool.wear.data.toSongHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumDetailUiState(
    val albumId: String = "",
    val albumName: String = "",
    val artistName: String = "",
    val artistId: String = "",
    val artworkUrl: String = "",
    val year: Int = 0,
    val trackCount: Int = 0,
    val tracks: List<AppleSong> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val downloadedIds: Set<String> = emptySet(),
    val activeDownloads: Map<String, ActiveDownload> = emptyMap()
)

class AlbumDetailViewModel(
    private val albumId: String,
    private val storefront: String = "",
    private val fallbackName: String = "",
    private val fallbackArtist: String = "",
    private val fallbackArtwork: String = "",
    private val fallbackYear: Int = 0,
    private val isLibrary: Boolean = false,
    private val albumRepository: AlbumRepository,
    private val playbackRepository: PlaybackRepository,
    private val downloadRepository: DownloadRepository
) : ViewModel() {

    private data class LocalAlbumState(
        val albumId: String = "",
        val albumName: String = "",
        val artistName: String = "",
        val artistId: String = "",
        val artworkUrl: String = "",
        val year: Int = 0,
        val trackCount: Int = 0,
        val tracks: List<AppleSong> = emptyList(),
        val isLoading: Boolean = true,
        val errorMessage: String? = null
    )

    private val localState = MutableStateFlow(
        LocalAlbumState(
            albumId = albumId,
            albumName = fallbackName.ifBlank { "专辑" },
            artistName = fallbackArtist,
            artworkUrl = fallbackArtwork,
            year = fallbackYear,
            isLoading = true
        )
    )
    private var loadJob: Job? = null

    val uiState: StateFlow<AlbumDetailUiState> = combine(
        localState,
        downloadRepository.state
    ) { local, dl ->
        AlbumDetailUiState(
            albumId = local.albumId,
            albumName = local.albumName,
            artistName = local.artistName,
            artistId = local.artistId,
            artworkUrl = local.artworkUrl,
            year = local.year,
            trackCount = local.trackCount,
            tracks = local.tracks,
            isLoading = local.isLoading,
            errorMessage = local.errorMessage,
            downloadedIds = dl.downloadedIds,
            activeDownloads = dl.activeDownloads
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AlbumDetailUiState(
            albumId = albumId,
            albumName = fallbackName.ifBlank { "专辑" },
            artistName = fallbackArtist,
            artworkUrl = fallbackArtwork,
            year = fallbackYear,
            isLoading = true
        )
    )

    init {
        loadAlbum()
    }

    fun loadAlbum() {
        loadJob?.cancel()
        localState.update { it.copy(isLoading = true, errorMessage = null) }
        loadJob = viewModelScope.launch {
            try {
                val res = albumRepository.loadAlbum(
                    albumId = albumId,
                    storefront = storefront,
                    fallbackName = fallbackName,
                    fallbackArtist = fallbackArtist,
                    fallbackArtwork = fallbackArtwork,
                    fallbackYear = fallbackYear,
                    isLibrary = isLibrary
                )
                val resolvedArtistId = res.album.artistId.takeIf { id ->
                    id.isNotBlank() && id.all { it in '0'..'9' }
                } ?: res.tracks.firstOrNull { track ->
                    track.artistId.isNotBlank() && track.artistId.all { it in '0'..'9' }
                }?.artistId.orEmpty()

                localState.update {
                    it.copy(
                        albumId = res.album.id.ifBlank { it.albumId },
                        albumName = res.album.name.ifBlank { it.albumName },
                        artistName = res.album.artistName.ifBlank { it.artistName },
                        artistId = resolvedArtistId,
                        artworkUrl = res.album.artworkUrl.ifBlank { it.artworkUrl },
                        year = if (res.album.year > 0) res.album.year else it.year,
                        trackCount = if (res.album.trackCount > 0) res.album.trackCount else res.tracks.size,
                        tracks = res.tracks,
                        isLoading = false,
                        errorMessage = res.errorMessage
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                localState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "加载专辑失败：${e.message ?: "网络超时"}"
                    )
                }
            }
        }
    }

    fun playAll(shuffle: Boolean = false) {
        val tracks = localState.value.tracks
        if (tracks.isEmpty()) return
        val hits = tracks.map { it.toSongHit() }
        playbackRepository.playSongs(if (shuffle) hits.shuffled() else hits, 0)
    }

    fun playTrackAt(index: Int) {
        val tracks = localState.value.tracks
        if (index !in tracks.indices) return
        playbackRepository.playSongs(tracks.map { it.toSongHit() }, index)
    }

    fun downloadSong(song: SongHit) {
        downloadRepository.startDownload(song)
    }
}
