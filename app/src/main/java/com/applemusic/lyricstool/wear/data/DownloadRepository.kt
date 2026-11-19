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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class ActiveDownload(
    val song: SongHit,
    val percent: Int = 0,
    val stageLabel: String = "准备中"
)

data class DownloadState(
    val entries: List<AppleCache.Entry> = emptyList(),
    val activeDownloads: Map<String, ActiveDownload> = emptyMap(),
    val downloadedIds: Set<String> = emptySet(),
    val totalBytes: Long = 0L
)

/**
 * 离线下载与缓存仓库（App 生命周期作用域）：
 * - 切离搜索页不中断后台下载；
 * - 使用 `AppleCache.ensure(..., pinOnComplete = true)` 直接落盘永久下载目录，避免先写缓存被误删；
 * - 清理临时缓存时自动保护当前正在播放歌曲、进行中下载与全部已下载歌曲及其离线歌词。
 */
class DownloadRepository(
    private val appContext: Context,
    private val appScope: CoroutineScope,
    private val lyricsCache: LyricsCache
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    fun refreshDownloaded() {
        if (!MusicRepository.isInitialized()) return
        appScope.launch(Dispatchers.IO) {
            reloadLocked()
        }
    }

    private fun reloadLocked() {
        val entries = MusicRepository.cache.getDownloadedEntries()
        _state.update {
            it.copy(
                entries = entries,
                downloadedIds = entries.mapTo(HashSet()) { e -> e.songId },
                totalBytes = entries.sumOf { e -> e.size }
            )
        }
    }

    fun isDownloaded(songId: String): Boolean {
        if (songId in _state.value.downloadedIds) return true
        return MusicRepository.isInitialized() && MusicRepository.cache.isSongDownloaded(songId)
    }

    fun startDownload(
        song: SongHit,
        onStatus: (String) -> Unit
    ) {
        val cleanId = song.id.removePrefix("apple:").trim()
        if (cleanId.isEmpty()) {
            onStatus("歌曲 ID 无效")
            return
        }
        val existingActive = _state.value.activeDownloads[cleanId]
        if (existingActive != null) {
            onStatus("《${song.title}》正在下载中 (${existingActive.percent}%)")
            return
        }
        if (isDownloaded(cleanId)) {
            onStatus("《${song.title}》已完成下载")
            return
        }

        _state.update {
            it.copy(
                activeDownloads = it.activeDownloads + (cleanId to ActiveDownload(song, 0, "解析"))
            )
        }
        onStatus("开始下载《${song.title}》…")

        val job = appScope.launch(Dispatchers.IO) {
            try {
                // 先在 MusicRepository 登记元数据，便于离线列表补齐封面与时长
                MusicRepository.registerTransient(listOf(song.toSongRow()))
                val isLib = cleanId.startsWith("i.")
                val meta = AppleCache.SongMetadata(
                    title = song.title,
                    artist = song.artist,
                    album = song.album
                )
                var lastReportedPercent = -1
                MusicRepository.cache.ensure(
                    songId = cleanId,
                    isLibrary = isLib,
                    onProgress = { stage, percent, _ ->
                        val stageLabel = when (stage) {
                            AppleDownloader.Stage.RESOLVING -> "解析"
                            AppleDownloader.Stage.LICENSING -> "鉴权"
                            AppleDownloader.Stage.DOWNLOADING -> "下载"
                            AppleDownloader.Stage.DECRYPTING -> "解密"
                            AppleDownloader.Stage.WRITING -> "存储"
                            AppleDownloader.Stage.DONE -> "完成"
                            else -> "下载"
                        }
                        if (percent != lastReportedPercent || stage == AppleDownloader.Stage.DECRYPTING) {
                            lastReportedPercent = percent
                            _state.update { cur ->
                                val prev = cur.activeDownloads[cleanId] ?: return@update cur
                                cur.copy(
                                    activeDownloads = cur.activeDownloads + (
                                        cleanId to prev.copy(percent = percent, stageLabel = stageLabel)
                                    )
                                )
                            }
                        }
                    },
                    metadata = meta,
                    pinOnComplete = true
                )
                reloadLocked()
                onStatus("《${song.title}》下载完成")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Logx.w(TAG, "下载失败 ($cleanId): ${e.message}")
                onStatus("下载失败：${e.message ?: "网络超时"}")
            } finally {
                activeJobs.remove(cleanId)
                _state.update { it.copy(activeDownloads = it.activeDownloads - cleanId) }
            }
        }
        activeJobs[cleanId] = job
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

    private companion object {
        const val TAG = "DownloadRepository"
    }
}
