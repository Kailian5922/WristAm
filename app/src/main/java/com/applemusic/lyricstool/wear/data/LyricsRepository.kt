package com.applemusic.lyricstool.wear.data

import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.lyrics.Lyrics
import com.applemusic.android.util.Logx
import com.applemusic.lyricstool.data.AppleLyricsClient
import com.applemusic.lyricstool.data.LyricsCache
import com.applemusic.lyricstool.data.QQMusicLyricsClient
import com.applemusic.lyricstool.data.SongHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class LyricsLoadResult(
    val lyrics: Lyrics?,
    val errorMessage: String? = null
)

/**
 * 单飞（single-flight）歌词仓库：
 * - UI 加载与切歌后台预取共享同一个挂起任务，绝不重复发起双倍网络请求；
 * - 资料库歌曲（`i.xxx`）自动先映射为 `catalogId` 再查 Apple 歌词，避免必败 HTTP 400；
 * - Apple 歌词接口异常独立捕获，保证 QQ 音乐兜底通道必定正常触发；
 * - 支持 `forceRefresh = true` 让「重试加载」绕过空结果重试。
 */
class LyricsRepository(
    private val appScope: CoroutineScope,
    private val appleLyricsClient: AppleLyricsClient,
    private val qqMusicClient: QQMusicLyricsClient,
    private val lyricsCache: LyricsCache
) {
    private val inflight = ConcurrentHashMap<String, Deferred<LyricsLoadResult>>()

    suspend fun loadForSong(
        song: SongHit,
        forceRefresh: Boolean = false
    ): LyricsLoadResult {
        val cacheKey = song.id.removePrefix("apple:").trim()
        if (cacheKey.isEmpty()) return LyricsLoadResult(null, "暂无歌词")

        if (forceRefresh) {
            inflight.remove(cacheKey)?.cancel()
        }

        val deferred = inflight.computeIfAbsent(cacheKey) {
            appScope.async(Dispatchers.IO) {
                try {
                    fetchInternal(song, cacheKey, forceRefresh)
                } finally {
                    inflight.remove(cacheKey)
                }
            }
        }
        return deferred.await()
    }

    fun prefetchForUri(nextUri: String) {
        if (nextUri.isBlank() || !MusicRepository.isInitialized()) return
        appScope.launch(Dispatchers.IO) {
            try {
                val row = MusicRepository.findSong(nextUri)
                val rawId = row?.appleId?.takeIf { it.isNotEmpty() } ?: nextUri.removePrefix("apple:")
                if (rawId.isBlank()) return@launch
                val hit = row?.toSongHit() ?: SongHit(
                    id = rawId,
                    title = "",
                    artist = "",
                    album = "",
                    artworkUrl = "",
                    durationMs = 0L
                )
                loadForSong(hit, forceRefresh = false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // 后台预加载静默失败，不干扰播放
            }
        }
    }

    private suspend fun fetchInternal(
        song: SongHit,
        cacheKey: String,
        forceRefresh: Boolean
    ): LyricsLoadResult = withContext(Dispatchers.IO) {
        val catalogId = resolveCatalogIdForLyrics(song, cacheKey)

        // 1. 优先读双源本地缓存（非强制重试）
        var cachedApple: Lyrics? = null
        if (!forceRefresh) {
            cachedApple = lyricsCache.get("apple", cacheKey)?.lyrics
                ?: if (catalogId.isNotBlank() && catalogId != cacheKey) {
                    lyricsCache.get("apple", catalogId)?.lyrics
                } else {
                    null
                }
            if (cachedApple != null && cachedApple.wordTimedLines() > 0) {
                return@withContext LyricsLoadResult(cachedApple)
            }
            val cachedQq = lyricsCache.get("qq", cacheKey)?.lyrics
                ?: if (catalogId.isNotBlank() && catalogId != cacheKey) {
                    lyricsCache.get("qq", catalogId)?.lyrics
                } else {
                    null
                }
            if (cachedQq != null) {
                return@withContext LyricsLoadResult(cachedQq)
            }
        }

        var appleError: Exception? = null
        var appleLyrics: Lyrics? = cachedApple

        // 2. 请求 Apple Music 歌词（仅使用合法 catalogId，避免对 i.xxx 打 400）
        if (appleLyrics == null && catalogId.isNotBlank() && !catalogId.startsWith("i.")) {
            try {
                val fetched = appleLyricsClient.fetchLyrics(catalogId)
                if (fetched != null) {
                    lyricsCache.put("apple", cacheKey, fetched)
                    appleLyrics = fetched.lyrics
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                appleError = e
                Logx.w(TAG, "Apple 歌词拉取失败 ($catalogId)：${e.message}")
            }
        }

        if (appleLyrics != null && appleLyrics.wordTimedLines() > 0) {
            return@withContext LyricsLoadResult(appleLyrics)
        }

        // 3. Apple 无逐字歌词或拉取异常时，独立尝试 QQ 音乐匹配兜底
        var qqError: Exception? = null
        var qqLyrics: Lyrics? = null
        if (song.title.isNotBlank()) {
            val localized = try {
                if (catalogId.isNotBlank() && !catalogId.startsWith("i.")) {
                    appleLyricsClient.fetchChineseSongMetadata(song.copy(id = catalogId))
                } else {
                    null
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            try {
                val fetchedQq = qqMusicClient.fetchMatchingLyrics(
                    title = song.title,
                    artist = song.artist,
                    durationMs = song.durationMs,
                    album = song.album,
                    localizedAliases = listOfNotNull(localized)
                )
                if (fetchedQq != null) {
                    lyricsCache.put("qq", cacheKey, fetchedQq)
                    qqLyrics = fetchedQq.lyrics
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                qqError = e
                Logx.w(TAG, "QQ 音乐歌词兜底失败 (${song.title})：${e.message}")
            }
        }

        val best = qqLyrics ?: appleLyrics
        if (best != null) {
            LyricsLoadResult(best)
        } else {
            val err = appleError ?: qqError
            val msg = if (err != null) {
                "歌词加载失败：${err.message ?: "网络超时"}"
            } else {
                "暂无歌词"
            }
            LyricsLoadResult(null, msg)
        }
    }

    private fun resolveCatalogIdForLyrics(song: SongHit, cacheKey: String): String {
        val direct = song.resolveFavoriteCatalogId()
        if (direct.isNotBlank() && !direct.startsWith("i.")) return direct
        if (cacheKey.startsWith("i.") && MusicRepository.isInitialized()) {
            try {
                val resolved = MusicRepository.api.librarySongCatalogId(cacheKey)
                if (resolved.isNotBlank()) {
                    MusicRepository.findSong("apple:$cacheKey")?.let { row ->
                        row.catalogId = resolved
                        MusicRepository.registerTransient(listOf(row))
                    }
                    return resolved
                }
            } catch (e: Exception) {
                Logx.w(TAG, "映射资料库歌词 ID 失败 ($cacheKey)：${e.message}")
            }
        }
        return cacheKey
    }

    private companion object {
        const val TAG = "LyricsRepository"
    }
}
