package com.applemusic.lyricstool.wear.data

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.applemusic.android.apple.AppleCache
import com.applemusic.android.apple.AppleDownloader
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.player.PlaybackEngine
import com.applemusic.android.util.Logx
import com.applemusic.lyricstool.data.LyricsCache
import com.applemusic.lyricstool.data.SongHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

fun SongHit.downloadCandidateIds(): List<String> {
    val primary = id.removePrefix("apple:").trim()
    val provider = providerSongId.orEmpty().removePrefix("apple:").trim()
    return buildList(2) {
        if (primary.isNotEmpty()) add(primary)
        if (provider.isNotEmpty() && provider != primary) add(provider)
    }
}

data class ActiveDownload(
    val song: SongHit,
    val percent: Int = 0,
    val stageLabel: String = "准备中",
    val startedAtMs: Long = System.currentTimeMillis(),
    val taskId: String = song.downloadCandidateIds().firstOrNull().orEmpty()
)

data class FailedDownload(
    val song: SongHit,
    val reason: String,
    val failedAtMs: Long = System.currentTimeMillis(),
    val taskId: String = song.downloadCandidateIds().firstOrNull().orEmpty()
)

sealed interface DownloadEvent {
    data class Started(val songTitle: String) : DownloadEvent
    data class AlreadyDownloading(val songTitle: String, val percent: Int) : DownloadEvent
    data class AlreadyDownloaded(val songTitle: String) : DownloadEvent
    data object InvalidSong : DownloadEvent
    data class Succeeded(val songTitle: String) : DownloadEvent
    data class Failed(val songTitle: String, val reason: String) : DownloadEvent
    data class Cancelled(val songTitle: String) : DownloadEvent
}

fun Map<String, ActiveDownload>.findFor(song: SongHit): ActiveDownload? {
    val candidates = song.downloadCandidateIds()
    if (candidates.isEmpty()) return null
    candidates.firstNotNullOfOrNull { this[it] }?.let { return it }
    return values.firstOrNull { active ->
        active.song.downloadCandidateIds().any { it in candidates }
    }
}

fun Set<String>.containsSong(song: SongHit): Boolean =
    song.downloadCandidateIds().any { it in this }

data class DownloadState(
    val entries: List<AppleCache.Entry> = emptyList(),
    val activeDownloads: Map<String, ActiveDownload> = emptyMap(),
    val failedDownloads: Map<String, FailedDownload> = emptyMap(),
    val downloadedIds: Set<String> = emptySet(),
    val totalBytes: Long = 0L
) {
    fun isSongDownloaded(song: SongHit): Boolean = downloadedIds.containsSong(song)

    fun activeDownloadFor(song: SongHit): ActiveDownload? = activeDownloads.findFor(song)
}

/**
 * 离线下载与缓存仓库（App 生命周期作用域）：
 * - 切离当前页不中断后台下载；
 * - 使用 `AppleCache.ensure(..., pinOnComplete = true)` 直接落盘永久下载目录，避免先写缓存被误删；
 * - 通过全局 [events] 广播下载开始/完成/失败/取消事件，任意页面均可及时弹出状态提示；
 * - 清理临时缓存时自动保护当前正在播放歌曲、进行中下载与全部已下载歌曲及其离线歌词。
 */
class DownloadRepository(
    private val appContext: Context,
    private val appScope: CoroutineScope,
    private val lyricsCache: LyricsCache
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    fun refreshDownloaded() {
        if (!MusicRepository.isInitialized()) return
        appScope.launch(Dispatchers.IO) {
            reloadLocked()
        }
    }

    private fun reloadLocked() {
        val entries = MusicRepository.cache.getDownloadedEntries()
        val ids = HashSet<String>(entries.size * 2)
        for (e in entries) {
            ids.add(e.songId)
            val known = MusicRepository.findSong(e.songId)
            if (known != null) {
                if (known.appleId.isNotBlank()) ids.add(known.appleId)
                if (known.catalogId.isNotBlank()) ids.add(known.catalogId)
            }
        }
        _state.update {
            it.copy(
                entries = entries,
                downloadedIds = ids,
                totalBytes = entries.sumOf { e -> e.size }
            )
        }
    }

    fun isDownloaded(songId: String): Boolean {
        val cleanId = songId.removePrefix("apple:").trim()
        if (cleanId.isEmpty()) return false
        if (cleanId in _state.value.downloadedIds) return true
        return MusicRepository.isInitialized() && MusicRepository.cache.isSongDownloaded(cleanId)
    }

    fun startDownload(song: SongHit) {
        val candidateIds = song.downloadCandidateIds()
        val cleanId = candidateIds.firstOrNull().orEmpty()
        if (cleanId.isEmpty()) {
            _events.tryEmit(DownloadEvent.InvalidSong)
            return
        }
        val title = song.title.ifBlank { "未知歌曲" }
        val existingActive = _state.value.activeDownloadFor(song)
        if (existingActive != null) {
            _events.tryEmit(DownloadEvent.AlreadyDownloading(title, existingActive.percent))
            return
        }
        if (candidateIds.any { isDownloaded(it) }) {
            _events.tryEmit(DownloadEvent.AlreadyDownloaded(title))
            return
        }

        cancelledIds.remove(cleanId)
        val candidateSet = candidateIds.toSet()
        _state.update { cur ->
            val cleanedFailed = cur.failedDownloads.filterNot { (key, item) ->
                key in candidateSet || item.song.downloadCandidateIds().any { it in candidateSet }
            }
            cur.copy(
                activeDownloads = cur.activeDownloads + (
                    cleanId to ActiveDownload(
                        song = song,
                        percent = 0,
                        stageLabel = "准备中",
                        taskId = cleanId
                    )
                ),
                failedDownloads = cleanedFailed
            )
        }
        _events.tryEmit(DownloadEvent.Started(title))

        val job = appScope.launch(Dispatchers.IO) {
            try {
                // 先在 MusicRepository 登记元数据，便于离线列表补齐封面与时长
                MusicRepository.registerTransient(listOf(song.toSongRow()))
                val isLib = cleanId.startsWith("i.")
                val meta = AppleCache.SongMetadata(
                    title = title,
                    artist = song.artist,
                    album = song.album
                )
                var lastReportedPercent = -1
                var lastReportedStage: AppleDownloader.Stage? = null
                runInterruptible(Dispatchers.IO) {
                    MusicRepository.cache.ensure(
                        songId = cleanId,
                        isLibrary = isLib,
                        onProgress = { stage, percent, _ ->
                            if (stage == AppleDownloader.Stage.FAILED) return@ensure
                            val stageLabel = when (stage) {
                                AppleDownloader.Stage.RESOLVING -> "解析中"
                                AppleDownloader.Stage.LICENSING -> "鉴权中"
                                AppleDownloader.Stage.DOWNLOADING -> "下载中"
                                AppleDownloader.Stage.DECRYPTING -> "解密中"
                                AppleDownloader.Stage.WRITING -> "保存中"
                                AppleDownloader.Stage.DONE -> "已完成"
                                else -> "下载中"
                            }
                            if (percent != lastReportedPercent || stage != lastReportedStage) {
                                lastReportedPercent = percent
                                lastReportedStage = stage
                                _state.update { cur ->
                                    val prev = cur.activeDownloads[cleanId] ?: return@update cur
                                    cur.copy(
                                        activeDownloads = cur.activeDownloads + (
                                            cleanId to prev.copy(
                                                percent = percent.coerceIn(0, 100),
                                                stageLabel = stageLabel
                                            )
                                        )
                                    )
                                }
                            }
                        },
                        metadata = meta,
                        pinOnComplete = true
                    )
                }
                reloadLocked()
                _events.tryEmit(DownloadEvent.Succeeded(title))
            } catch (ce: CancellationException) {
                if (cancelledIds.remove(cleanId)) {
                    _events.tryEmit(DownloadEvent.Cancelled(title))
                }
                throw ce
            } catch (e: Exception) {
                if (cancelledIds.remove(cleanId) || e is InterruptedException || e.cause is InterruptedException) {
                    _events.tryEmit(DownloadEvent.Cancelled(title))
                } else {
                    val reason = formatFailureReason(e)
                    Logx.w(TAG, "下载失败 ($cleanId): ${e.message}")
                    _state.update { cur ->
                        cur.copy(
                            failedDownloads = cur.failedDownloads + (
                                cleanId to FailedDownload(song = song, reason = reason)
                            )
                        )
                    }
                    _events.tryEmit(DownloadEvent.Failed(title, reason))
                }
            } finally {
                activeJobs.remove(cleanId)
                cancelledIds.remove(cleanId)
                _state.update { it.copy(activeDownloads = it.activeDownloads - cleanId) }
            }
        }
        activeJobs[cleanId] = job
    }

    fun cancelDownload(songId: String) {
        val cleanId = songId.removePrefix("apple:").trim()
        if (cleanId.isEmpty()) return
        val active = _state.value.activeDownloads[cleanId]
        cancelledIds.add(cleanId)
        val job = activeJobs.remove(cleanId)
        _state.update { it.copy(activeDownloads = it.activeDownloads - cleanId) }
        if (job != null) {
            job.cancel()
        } else if (active != null) {
            cancelledIds.remove(cleanId)
            _events.tryEmit(DownloadEvent.Cancelled(active.song.title.ifBlank { "未知歌曲" }))
        }
    }

    fun retryFailed(songId: String) {
        val cleanId = songId.removePrefix("apple:").trim()
        val failed = _state.value.failedDownloads[cleanId] ?: return
        startDownload(failed.song)
    }

    fun dismissFailed(songId: String) {
        val cleanId = songId.removePrefix("apple:").trim()
        if (cleanId.isEmpty()) return
        _state.update { it.copy(failedDownloads = it.failedDownloads - cleanId) }
    }

    suspend fun deleteDownload(songId: String): Boolean = withContext(Dispatchers.IO) {
        if (!MusicRepository.isInitialized()) return@withContext false
        val ok = MusicRepository.cache.deleteDownloadedSong(songId)
        reloadLocked()
        ok
    }

    /** 清理临时音频/歌词/图片缓存，严格保留所有已下载歌曲及其歌词和当前播放曲目。 */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearTemporaryCache(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val protectedIds = HashSet<String>()
            PlaybackEngine.currentSong()?.let { row ->
                if (row.appleId.isNotBlank()) protectedIds.add(row.appleId)
                if (row.catalogId.isNotBlank()) protectedIds.add(row.catalogId)
            }
            val removedAudio = MusicRepository.cache.clearTemporaryCacheOnly(protectedIds)
            val pinnedIds = MusicRepository.cache.pinnedSongIds()
            val keepLyricsIds = HashSet<String>(pinnedIds).apply { addAll(protectedIds) }
            try {
                lyricsCache.clearExcept(keepLyricsIds)
            } catch (e: Exception) {
                Logx.w(TAG, "清理歌词临时缓存失败：${e.message}")
            }
            try {
                appContext.imageLoader.memoryCache?.clear()
                appContext.imageLoader.diskCache?.clear()
            } catch (_: Exception) {
            }
            reloadLocked()
            removedAudio
        }
    }

    private fun formatFailureReason(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.lastOrNull() ?: e
        val raw = e.message?.trim().orEmpty()
        return when {
            root is UnknownHostException -> "无网络连接"
            root is SocketTimeoutException || raw.contains("timeout", ignoreCase = true) -> "网络超时"
            raw.contains("HTTP 401") || raw.contains("HTTP 403") -> "登录过期或无权限"
            raw.isBlank() -> "网络异常"
            else -> raw.substringBefore(" @ http").trim().take(36).ifBlank { "下载出错" }
        }
    }

    private companion object {
        const val TAG = "DownloadRepository"
    }
}
