package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.android.apple.MusicRepository
import com.applemusic.lyricstool.data.QQMusicLyricsClient
import com.applemusic.lyricstool.data.SongHit
import com.applemusic.lyricstool.wear.data.ActiveDownload
import com.applemusic.lyricstool.wear.data.DownloadRepository
import com.applemusic.lyricstool.wear.data.PlaybackRepository
import com.applemusic.lyricstool.wear.data.toSongHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class SearchUiState(
    val query: String = "",
    val searchByLyrics: Boolean = false,
    val results: List<SongHit> = emptyList(),
    val searching: Boolean = false,
    val status: String = "",
    val downloadedIds: Set<String> = emptySet(),
    val activeDownloads: Map<String, ActiveDownload> = emptyMap()
)

class SearchViewModel(
    private val qqMusicClient: QQMusicLyricsClient,
    private val downloadRepository: DownloadRepository,
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    private data class LocalSearchState(
        val query: String = "",
        val searchByLyrics: Boolean = false,
        val results: List<SongHit> = emptyList(),
        val searching: Boolean = false,
        val status: String = ""
    )

    private val localState = MutableStateFlow(LocalSearchState())
    private var searchJob: Job? = null

    val uiState: StateFlow<SearchUiState> = combine(
        localState,
        downloadRepository.state
    ) { local, dl ->
        SearchUiState(
            query = local.query,
            searchByLyrics = local.searchByLyrics,
            results = local.results,
            searching = local.searching,
            status = local.status,
            downloadedIds = dl.downloadedIds,
            activeDownloads = dl.activeDownloads
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChanged(value: String) {
        localState.update { it.copy(query = value) }
    }

    fun toggleSearchByLyrics() {
        localState.update { it.copy(searchByLyrics = !it.searchByLyrics) }
    }

    fun performSearch() {
        val trimmed = localState.value.query.trim()
        if (trimmed.isEmpty()) return

        searchJob?.cancel()
        val byLyrics = localState.value.searchByLyrics
        localState.update { it.copy(searching = true, status = "") }

        searchJob = viewModelScope.launch {
            try {
                val (hits, infoNote) = withContext(Dispatchers.IO) {
                    if (byLyrics) {
                        searchByLyricsParallel(trimmed) to ""
                    } else {
                        searchCombinedWithOfflineFallback(trimmed)
                    }
                }
                val statusMsg = when {
                    hits.isEmpty() -> infoNote.ifBlank { "没有找到匹配歌曲" }
                    else -> infoNote
                }
                localState.update {
                    it.copy(results = hits, searching = false, status = statusMsg)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                localState.update {
                    it.copy(
                        searching = false,
                        status = "搜索失败：${e.message ?: "网络超时"}"
                    )
                }
            }
        }
    }

    private suspend fun searchByLyricsParallel(trimmed: String): List<SongHit> = coroutineScope {
        val qqHits = qqMusicClient.searchSongsByLyrics(trimmed).take(6)
        if (qqHits.isEmpty()) return@coroutineScope emptyList()

        val perHitLists = qqHits.map { hit ->
            async(Dispatchers.IO) {
                try {
                    val term = "${hit.title} ${hit.artist}".trim()
                    MusicRepository.searchCatalog(term, 4).songs.map { it.toSongHit() }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll()

        val merged = ArrayList<SongHit>()
        val seenIds = HashSet<String>()
        val seenSig = HashSet<String>()
        for (batch in perHitLists) {
            for (song in batch) {
                val rawId = song.id.trim()
                val primaryId = song.providerSongId?.trim()?.ifBlank { rawId } ?: rawId
                val sigKey = normalizeSignature(song.title, song.artist)
                if ((rawId.isNotEmpty() && rawId in seenIds) ||
                    (primaryId.isNotEmpty() && primaryId in seenIds) ||
                    sigKey in seenSig
                ) {
                    continue
                }
                if (rawId.isNotEmpty()) seenIds.add(rawId)
                if (primaryId.isNotEmpty()) seenIds.add(primaryId)
                seenSig.add(sigKey)
                merged.add(song)
                if (merged.size >= 15) return@coroutineScope merged
            }
        }
        merged
    }

    private fun searchCombinedWithOfflineFallback(trimmed: String): Pair<List<SongHit>, String> {
        val localSongs = try {
            MusicRepository.searchLocal(trimmed).songs
        } catch (_: Exception) {
            emptyList()
        }

        var catalogError: Throwable? = null
        val catalogSongs = try {
            MusicRepository.searchCatalog(trimmed, 25).songs
        } catch (e: Exception) {
            catalogError = e
            emptyList()
        }

        val merged = ArrayList<SongHit>()
        val seenIds = HashSet<String>()
        val seenSignatures = HashSet<String>()

        fun addIfUnique(hit: SongHit) {
            val rawId = hit.id.trim()
            val primaryId = hit.providerSongId?.trim()?.ifBlank { rawId } ?: rawId
            val sig = normalizeSignature(hit.title, hit.artist)
            if ((rawId.isNotEmpty() && rawId in seenIds) ||
                (primaryId.isNotEmpty() && primaryId in seenIds) ||
                sig in seenSignatures
            ) {
                return
            }
            if (rawId.isNotEmpty()) seenIds.add(rawId)
            if (primaryId.isNotEmpty()) seenIds.add(primaryId)
            seenSignatures.add(sig)
            merged.add(hit)
        }

        for (s in localSongs) addIfUnique(s.toSongHit())
        for (s in catalogSongs) addIfUnique(s.toSongHit())

        val note = when {
            catalogError != null && merged.isNotEmpty() -> "网络异常，已展示本地资料库匹配"
            catalogError != null && merged.isEmpty() -> throw catalogError
            else -> ""
        }
        return merged to note
    }

    private fun normalizeSignature(title: String, artist: String): String =
        "${title.trim().lowercase(Locale.ROOT)}|${artist.trim().lowercase(Locale.ROOT)}"

    fun playSong(song: SongHit) {
        playbackRepository.playSongs(listOf(song), 0)
    }

    fun downloadSong(song: SongHit, onToast: (String, Boolean) -> Unit) {
        downloadRepository.startDownload(song) { msg ->
            onToast(msg, msg.contains("失败") || msg.contains("无效"))
        }
    }
}
