package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.android.apple.AppleAlbum
import com.applemusic.android.apple.AppleArtistPage
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.android.apple.AppleSong
import com.applemusic.lyricstool.data.SongHit
import com.applemusic.lyricstool.wear.data.ActiveDownload
import com.applemusic.lyricstool.wear.data.ArtistNameParser
import com.applemusic.lyricstool.wear.data.ArtistRepository
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

data class ArtistUiState(
    val parsedArtists: List<String> = emptyList(),
    val currentArtist: String = "",
    val resolvedArtistId: String = "",
    val artistPage: AppleArtistPage? = null,
    val topSongs: List<AppleSong> = emptyList(),
    val localSongs: List<AppleLibrarySync.SongRow> = emptyList(),
    val hasMoreTopSongs: Boolean = false,
    val loadingMoreTopSongs: Boolean = false,
    val loadingAlbumId: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val cleanBio: String = "",
    val downloadedIds: Set<String> = emptySet(),
    val activeDownloads: Map<String, ActiveDownload> = emptyMap()
)

class ArtistViewModel(
    rawArtistParam: String,
    private val artistRepository: ArtistRepository,
    private val playbackRepository: PlaybackRepository,
    private val downloadRepository: DownloadRepository,
    private val directArtistId: String = ""
) : ViewModel() {

    private data class LocalArtistState(
        val parsedArtists: List<String> = emptyList(),
        val currentArtist: String = "",
        val resolvedArtistId: String = "",
        val artistPage: AppleArtistPage? = null,
        val topSongs: List<AppleSong> = emptyList(),
        val localSongs: List<AppleLibrarySync.SongRow> = emptyList(),
        val hasMoreTopSongs: Boolean = false,
        val loadingMoreTopSongs: Boolean = false,
        val loadingAlbumId: String? = null,
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val cleanBio: String = ""
    )

    private val parsed = if (directArtistId.isNotBlank()) {
        listOf(rawArtistParam.trim())
    } else {
        ArtistNameParser.splitArtists(rawArtistParam).ifEmpty {
            listOf(rawArtistParam.trim())
        }
    }
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var albumJob: Job? = null
    private var topSongsOffset: Int = 0

    private val localState = MutableStateFlow(
        LocalArtistState(
            parsedArtists = parsed,
            currentArtist = parsed.first()
        )
    )

    val uiState: StateFlow<ArtistUiState> = combine(
        localState,
        downloadRepository.state
    ) { local, dl ->
        ArtistUiState(
            parsedArtists = local.parsedArtists,
            currentArtist = local.currentArtist,
            resolvedArtistId = local.resolvedArtistId,
            artistPage = local.artistPage,
            topSongs = local.topSongs,
            localSongs = local.localSongs,
            hasMoreTopSongs = local.hasMoreTopSongs,
            loadingMoreTopSongs = local.loadingMoreTopSongs,
            loadingAlbumId = local.loadingAlbumId,
            isLoading = local.isLoading,
            errorMessage = local.errorMessage,
            cleanBio = local.cleanBio,
            downloadedIds = dl.downloadedIds,
            activeDownloads = dl.activeDownloads
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ArtistUiState(parsedArtists = parsed, currentArtist = parsed.first())
    )

    init {
        loadArtist(parsed.first(), preferredArtistId = directArtistId)
    }

    fun selectArtistTab(artistName: String) {
        if (artistName.equals(localState.value.currentArtist, ignoreCase = true) && !localState.value.isLoading) {
            return
        }
        loadArtist(artistName)
    }

    private fun loadArtist(artistName: String, preferredArtistId: String = "") {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        albumJob?.cancel()
        topSongsOffset = 0

        localState.update {
            it.copy(
                currentArtist = artistName,
                resolvedArtistId = "",
                artistPage = null,
                topSongs = emptyList(),
                localSongs = emptyList(),
                hasMoreTopSongs = false,
                loadingMoreTopSongs = false,
                loadingAlbumId = null,
                isLoading = true,
                errorMessage = null,
                cleanBio = ""
            )
        }

        loadJob = viewModelScope.launch {
            try {
                val res = artistRepository.loadArtist(artistName, preferredArtistId = preferredArtistId)
                val initialTop = res.page?.topSongs.orEmpty()
                topSongsOffset = initialTop.size
                localState.update {
                    it.copy(
                        resolvedArtistId = res.resolvedArtistId,
                        artistPage = res.page,
                        topSongs = initialTop,
                        localSongs = res.localSongs,
                        hasMoreTopSongs = res.resolvedArtistId.isNotBlank() && initialTop.size >= PAGE_SIZE,
                        isLoading = false,
                        errorMessage = res.errorMessage,
                        cleanBio = ArtistNameParser.cleanHtmlBio(res.page?.artist?.editorialDescription)
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                localState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "加载歌手页失败：${e.message ?: "网络超时"}"
                    )
                }
            }
        }
    }

    /** 保留用户明确的「点击加载更多 (5首)」显式分页，确保精选专辑和歌手简介随时可达。 */
    fun loadMoreTopSongs(onToast: (String, Boolean) -> Unit) {
        val state = localState.value
        if (state.loadingMoreTopSongs || !state.hasMoreTopSongs || state.resolvedArtistId.isBlank()) {
            return
        }
        localState.update { it.copy(loadingMoreTopSongs = true) }
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            try {
                val res = artistRepository.loadMoreTopSongs(
                    artistId = state.resolvedArtistId,
                    offset = topSongsOffset,
                    limit = PAGE_SIZE
                )
                res.fold(
                    onSuccess = { page ->
                        val existingIds = localState.value.topSongs.mapTo(HashSet()) { it.id }
                        val fresh = page.items.filter { existingIds.add(it.id) }
                        topSongsOffset += page.items.size
                        val moreAvailable = page.next.isNotBlank() || page.items.size >= PAGE_SIZE
                        localState.update {
                            it.copy(
                                topSongs = it.topSongs + fresh,
                                hasMoreTopSongs = moreAvailable && page.items.isNotEmpty(),
                                loadingMoreTopSongs = false
                            )
                        }
                    },
                    onFailure = { e ->
                        localState.update { it.copy(loadingMoreTopSongs = false) }
                        onToast("加载更多失败：${e.message ?: "网络超时"}", true)
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    fun playSong(song: SongHit) {
        playbackRepository.playSongs(listOf(song), 0)
    }

    fun playQueue(songs: List<SongHit>, index: Int) {
        playbackRepository.playSongs(songs, index)
    }

    fun downloadSong(song: SongHit) {
        downloadRepository.startDownload(song)
    }

    fun playAlbum(
        album: AppleAlbum,
        onPlayed: () -> Unit,
        onToast: (String, Boolean) -> Unit
    ) {
        if (localState.value.loadingAlbumId != null) return
        localState.update { it.copy(loadingAlbumId = album.id) }
        albumJob?.cancel()
        albumJob = viewModelScope.launch {
            try {
                val res = artistRepository.loadAlbumTracks(album)
                res.fold(
                    onSuccess = { tracks ->
                        localState.update { it.copy(loadingAlbumId = null) }
                        if (tracks.isEmpty()) {
                            onToast("该专辑暂无可播放曲目", true)
                        } else {
                            playbackRepository.playSongs(tracks.map { it.toSongHit() }, 0)
                            onPlayed()
                        }
                    },
                    onFailure = { e ->
                        localState.update { it.copy(loadingAlbumId = null) }
                        onToast("加载专辑失败：${e.message ?: "网络超时"}", true)
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 5
    }
}
