package com.applemusic.lyricstool.wear.data

import android.content.Context
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.player.PlaybackEngine
import com.applemusic.lyricstool.data.SongHit
import com.applemusic.lyricstool.wear.WearRadioPlayback
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放控制仓库：统一封装 [PlaybackEngine]，确保普通点播始终先干净停止电台并持久化清理电台状态。
 */
class PlaybackRepository(
    private val appContext: Context,
    private val lyricsRepository: LyricsRepository
) {
    val stateFlow: StateFlow<PlaybackEngine.Snapshot> = PlaybackEngine.stateFlow

    init {
        PlaybackEngine.onPrefetchLyrics = { nextUri ->
            lyricsRepository.prefetchForUri(nextUri)
        }
    }

    fun playSongs(queue: List<SongHit>, index: Int) {
        if (queue.isEmpty()) return
        WearRadioPlayback.stop(appContext)
        val rows = queue.map(SongHit::toSongRow)
        MusicRepository.registerTransient(rows)
        val safeIdx = index.coerceIn(0, rows.lastIndex)
        PlaybackEngine.playQueue(rows.map(AppleLibrarySync.SongRow::uri), safeIdx)
    }

    fun playSongRows(rows: List<AppleLibrarySync.SongRow>, index: Int) {
        if (rows.isEmpty()) return
        WearRadioPlayback.stop(appContext)
        MusicRepository.registerTransient(rows)
        val safeIdx = index.coerceIn(0, rows.lastIndex)
        PlaybackEngine.playQueue(rows.map(AppleLibrarySync.SongRow::uri), safeIdx)
    }

    fun playAt(index: Int) {
        PlaybackEngine.playAt(index)
    }

    fun toggle() {
        PlaybackEngine.toggle()
    }

    fun next() {
        PlaybackEngine.next()
    }

    fun prev() {
        PlaybackEngine.prev()
    }

    fun seekTo(ms: Long) {
        PlaybackEngine.seekTo(ms)
    }

    fun positionMs(): Long = PlaybackEngine.positionMs()
    fun durationMs(): Long = PlaybackEngine.durationMs()
    fun queueSnapshot(): List<String> = PlaybackEngine.queueSnapshot()
}
