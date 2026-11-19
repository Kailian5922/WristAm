package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.android.lyrics.Lyrics
import com.applemusic.lyricstool.data.SongHit
import com.applemusic.lyricstool.wear.data.LyricsRepository
import com.applemusic.lyricstool.wear.data.PlaybackRepository
import com.applemusic.lyricstool.wear.data.toSongHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LyricsUiState(
    val song: SongHit? = null,
    val lyrics: Lyrics? = null,
    val isLoading: Boolean = false,
    val status: String = "",
    val isPlaying: Boolean = false,
    val seekRevision: Long = 0L
)

class LyricsViewModel(
    private val playbackRepository: PlaybackRepository,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    private val retryCounter = MutableStateFlow(0L)
    private var loadJob: Job? = null

    private val _uiState = MutableStateFlow(LyricsUiState())
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    init {
        // 实时同步播放态与 seekRevision（修复 HorologistLyricsView 静态读取 isPlaying 导致歌词冻结）
        viewModelScope.launch {
            playbackRepository.stateFlow
                .map { Triple(it.isPlaying, it.seekRevision, (it.currentSong ?: it.targetSong)?.toSongHit()) }
                .distinctUntilChanged { a, b ->
                    a.first == b.first && a.second == b.second && a.third?.id == b.third?.id
                }
                .collect { (playing, seekRev, song) ->
                    _uiState.update { cur ->
                        cur.copy(
                            song = song,
                            isPlaying = playing,
                            seekRevision = seekRev
                        )
                    }
                }
        }

        // 歌曲切换或点击重试时单飞加载歌词
        viewModelScope.launch {
            combine(
                playbackRepository.stateFlow
                    .map { (it.currentSong ?: it.targetSong)?.toSongHit() }
                    .distinctUntilChanged { a, b -> a?.id == b?.id },
                retryCounter
            ) { song, rev -> song to rev }
                .collect { (song, rev) ->
                    loadLyricsFor(song, forceRefresh = rev > 0L)
                }
        }
    }

    private fun loadLyricsFor(song: SongHit?, forceRefresh: Boolean) {
        loadJob?.cancel()
        if (song == null) {
            _uiState.update {
                it.copy(song = null, lyrics = null, isLoading = false, status = "暂无播放歌曲")
            }
            return
        }
        _uiState.update {
            it.copy(
                song = song,
                lyrics = null,
                isLoading = true,
                status = "正在加载歌词…"
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val res = lyricsRepository.loadForSong(song, forceRefresh = forceRefresh)
                _uiState.update {
                    it.copy(
                        lyrics = res.lyrics,
                        isLoading = false,
                        status = if (res.lyrics == null) (res.errorMessage ?: "暂无歌词") else ""
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        lyrics = null,
                        isLoading = false,
                        status = "歌词加载失败：${e.message ?: "网络超时"}"
                    )
                }
            }
        }
    }

    fun retry() {
        retryCounter.value += 1L
    }

    fun seekTo(ms: Long) {
        if (ms >= 0L) {
            playbackRepository.seekTo(ms)
        }
    }

    fun positionMs(): Long = playbackRepository.positionMs()
}
