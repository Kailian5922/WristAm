package com.applemusic.lyricstool.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.applemusic.android.apple.AppleApi
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.player.PlaybackEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal data class WearRadioStartBatch(
    val firstResponse: AppleApi.StationProbeResponse,
    val rows: List<AppleLibrarySync.SongRow>,
    val warning: String? = null
)

internal suspend fun startWearRadio(
    context: Context,
    api: AppleApi,
    stationId: String
): WearRadioStartBatch {
    val appCtx = context.applicationContext
    val startGen = WearRadioPlayback.prepareNewStart(appCtx)
    val started = withContext(Dispatchers.IO) {
        val firstResponse = api.probeStationNextTracks(stationId)
        if (firstResponse.statusCode !in 200..299) {
            throw IllegalStateException("电台首批请求失败 (${firstResponse.statusCode})")
        }
        val firstTracks = parseRadioTracks(firstResponse.body)
        if (firstTracks.isEmpty()) {
            throw IllegalStateException("电台没有返回可播放歌曲")
        }
        val rows = ArrayList<AppleLibrarySync.SongRow>()
        val seen = HashSet<String>()
        for (track in firstTracks) {
            if (!WearRadioPlayback.isGenerationCurrent(startGen)) {
                throw CancellationException("电台启动已被新请求取代")
            }
            if (!seen.add(track.id)) continue
            val song = try {
                api.getCatalogSongLocalized(track.id)
            } catch (_: Exception) {
                null
            } ?: continue
            rows.add(AppleLibrarySync.toRow(song).also { it.isLibrary = false })
        }
        if (rows.isEmpty()) {
            throw IllegalStateException("无法读取电台歌曲详情，请稍后重试")
        }

        var duplicateResponses = 0
        var requests = 0
        var warning: String? = null
        while (
            rows.size < MIN_RADIO_QUEUE_TRACKS &&
            requests < MAX_RADIO_PREFETCH_REQUESTS &&
            WearRadioPlayback.isGenerationCurrent(startGen)
        ) {
            requests++
            val requested = (MIN_RADIO_QUEUE_TRACKS - rows.size).coerceAtLeast(1)
            val nextResponse = try {
                api.probeStationNextTracks(stationId, limit = requested)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warning = "后续预取失败 (${e.message ?: "网络异常"})"
                break
            }
            if (nextResponse.statusCode !in 200..299) {
                warning = "后续预取失败 (${nextResponse.statusCode})"
                break
            }
            val nextTracks = parseRadioTracks(nextResponse.body)
            if (nextTracks.isEmpty()) {
                warning = "Apple 没有返回更多歌曲"
                break
            }
            var addedFromResponse = 0
            for (nextTrack in nextTracks) {
                if (!WearRadioPlayback.isGenerationCurrent(startGen)) {
                    throw CancellationException("电台启动已被新请求取代")
                }
                if (!seen.add(nextTrack.id)) continue
                val song = try {
                    api.getCatalogSongLocalized(nextTrack.id)
                } catch (_: Exception) {
                    null
                } ?: continue
                rows.add(AppleLibrarySync.toRow(song).also { it.isLibrary = false })
                addedFromResponse++
                if (rows.size >= MIN_RADIO_QUEUE_TRACKS) break
            }
            if (addedFromResponse == 0) {
                duplicateResponses++
                if (duplicateResponses >= MAX_RADIO_DUPLICATE_RESPONSES) {
                    warning = "连续返回重复歌曲"
                    break
                }
            } else {
                duplicateResponses = 0
            }
        }
        WearRadioStartBatch(firstResponse, rows, warning)
    }

    if (!WearRadioPlayback.isGenerationCurrent(startGen)) {
        throw CancellationException("电台启动已被取消")
    }
    WearRadioPlayback.start(
        context = appCtx,
        stationApi = api,
        stationId = stationId,
        initialRows = started.rows,
        enablePrefetch = true,
        expectedGeneration = startGen
    )
    return started
}

private data class RadioStationTrack(val id: String)

private fun parseRadioTracks(body: String): List<RadioStationTrack> {
    val root = try {
        JSONObject(body)
    } catch (_: Exception) {
        return emptyList()
    }
    val found = LinkedHashMap<String, RadioStationTrack>()
    fun visit(value: Any?) {
        when (value) {
            is JSONObject -> {
                val type = value.optString("type")
                val attributes = value.optJSONObject("attributes")
                if (attributes != null && (
                        type.contains("song", ignoreCase = true) ||
                            (attributes.has("artistName") &&
                                (attributes.has("durationInMillis") ||
                                    attributes.has("playParams")))
                        )
                ) {
                    val title = attributes.optString("name")
                    val playParams = attributes.optJSONObject("playParams")
                    val id = playParams?.optString("catalogId").orEmpty()
                        .ifBlank { value.optString("id") }
                    if (title.isNotBlank() && id.isNotBlank()) {
                        found.putIfAbsent(id, RadioStationTrack(id))
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) visit(value.opt(keys.next()))
            }
            is JSONArray -> {
                for (index in 0 until value.length()) visit(value.opt(index))
            }
        }
    }
    visit(root)
    return found.values.toList()
}

/**
 * Wear OS 个性电台续播控制器：
 * - 严格世代令牌（generation）校验，杜绝快速切换电台或切回普通专辑时串队；
 * - 单通道可取消协程预取，杜绝全局 AtomicBoolean 死锁或并发网络风暴；
 * - 有界 LRU 去重表（上限 150 首目录 ID），兼顾防重与长会话不耗尽；
 * - 暴露响应式 [activeStationIdFlow] 供 UI 实时高亮正在播放的电台。
 */
internal object WearRadioPlayback {
    private const val TAG = "WearRadioPlayback"
    private const val RADIO_PREFERENCES = "wear_native_radio"
    private const val RADIO_STATION_KEY = "station_id"
    private const val RADIO_PREFETCH_KEY = "prefetch_enabled"
    private const val MAX_SEEN_IDS = 150

    private val radioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val fetchLock = Any()

    @Volatile
    private var activeFetchJob: Job? = null

    /** 有界 LRU 已播曲目集合（按 catalogId 归一化）。 */
    private val seenSongIds = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > MAX_SEEN_IDS
        }
    }

    private val _activeStationIdFlow = MutableStateFlow("")
    val activeStationIdFlow: StateFlow<String> = _activeStationIdFlow.asStateFlow()

    @Volatile
    private var activeStationId = ""

    @Volatile
    private var api: AppleApi? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prefetchEnabled = false

    fun isRadioActive(): Boolean = activeStationId.isNotEmpty()
    val currentStationId: String get() = activeStationId

    fun isGenerationCurrent(gen: Long): Boolean = generation.get() == gen

    /** 停掉旧电台并分配新启动世代号，保证启动期间若被切歌或再点别的电台能立即作废。 */
    fun prepareNewStart(context: Context): Long {
        appContext = context.applicationContext
        stopInternal(context.applicationContext)
        return generation.get()
    }

    fun start(
        context: Context,
        stationApi: AppleApi,
        stationId: String,
        initialRows: List<AppleLibrarySync.SongRow>,
        enablePrefetch: Boolean = true,
        expectedGeneration: Long? = null
    ) {
        require(initialRows.isNotEmpty()) { "电台没有可播放歌曲" }
        val appCtx = context.applicationContext
        appContext = appCtx

        synchronized(fetchLock) {
            if (expectedGeneration != null && generation.get() != expectedGeneration) {
                Log.i(TAG, "忽略过期电台启动请求：$stationId")
                return
            }
            activeFetchJob?.cancel()
            activeFetchJob = null
            synchronized(seenSongIds) {
                seenSongIds.clear()
                initialRows.forEach { row ->
                    val id = row.catalogId.ifBlank { row.appleId }
                    if (id.isNotBlank()) seenSongIds[id] = true
                }
            }
            api = stationApi
            activeStationId = stationId
            _activeStationIdFlow.value = stationId
            prefetchEnabled = enablePrefetch
            persistRadioState()
            generation.incrementAndGet()
        }

        MusicRepository.registerTransient(initialRows)
        PlaybackEngine.onQueueLow = if (enablePrefetch) {
            { requestNextBatch(MIN_RADIO_UPCOMING_TRACKS) }
        } else {
            null
        }
        PlaybackEngine.playQueue(initialRows.map(AppleLibrarySync.SongRow::uri), 0)
    }

    fun stop(context: Context? = null) {
        if (context != null) appContext = context.applicationContext
        stopInternal(context?.applicationContext ?: appContext)
    }

    private fun stopInternal(ctx: Context?) {
        synchronized(fetchLock) {
            generation.incrementAndGet()
            activeFetchJob?.cancel()
            activeFetchJob = null
            activeStationId = ""
            _activeStationIdFlow.value = ""
            prefetchEnabled = false
        }
        ctx?.getSharedPreferences(RADIO_PREFERENCES, Context.MODE_PRIVATE)
            ?.edit()
            ?.clear()
            ?.apply()
        if (PlaybackEngine.onQueueLow != null) {
            PlaybackEngine.onQueueLow = null
        }
    }

    fun restore(context: Context, stationApi: AppleApi) {
        val appCtx = context.applicationContext
        appContext = appCtx
        api = stationApi

        val preferences = appCtx.getSharedPreferences(RADIO_PREFERENCES, Context.MODE_PRIVATE)
        val stationId = preferences.getString(RADIO_STATION_KEY, "").orEmpty()
        if (stationId.isBlank() || !preferences.getBoolean(RADIO_PREFETCH_KEY, false)) return
        if (PlaybackEngine.queueSnapshot().isEmpty()) {
            // 队列为空说明之前已清空，不再恢复脏电台状态
            preferences.edit().clear().apply()
            return
        }
        if (activeStationId == stationId && prefetchEnabled) {
            return
        }

        synchronized(fetchLock) {
            activeStationId = stationId
            _activeStationIdFlow.value = stationId
            prefetchEnabled = true
            synchronized(seenSongIds) {
                seenSongIds.clear()
                PlaybackEngine.queueSnapshot().forEach { uri ->
                    MusicRepository.findSong(uri)?.let { row ->
                        val id = row.catalogId.ifBlank { row.appleId }
                        if (id.isNotBlank()) seenSongIds[id] = true
                    }
                }
            }
        }
        PlaybackEngine.onQueueLow = { requestNextBatch(MIN_RADIO_UPCOMING_TRACKS) }
        if (PlaybackEngine.queueRemaining() <= MIN_RADIO_UPCOMING_TRACKS) {
            requestNextBatch(MIN_RADIO_UPCOMING_TRACKS)
        }
        Log.i(TAG, "已恢复个性电台预取：$stationId")
    }

    private fun persistRadioState() {
        val context = appContext ?: return
        context.getSharedPreferences(RADIO_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(RADIO_STATION_KEY, activeStationId)
            .putBoolean(RADIO_PREFETCH_KEY, prefetchEnabled)
            .apply()
    }

    private fun requestNextBatch(minimumUpcoming: Int = MIN_RADIO_UPCOMING_TRACKS) {
        val stationId: String
        val stationApi: AppleApi
        val requestGeneration: Long
        synchronized(fetchLock) {
            stationId = activeStationId
            stationApi = api ?: return
            if (!prefetchEnabled || stationId.isBlank()) return
            if (activeFetchJob?.isActive == true) return
            requestGeneration = generation.get()

            activeFetchJob = radioScope.launch {
                var failureReason: String? = null
                try {
                    val freshRows = ArrayList<AppleLibrarySync.SongRow>()
                    val knownRemaining = PlaybackEngine.queueRemaining()
                    var duplicateResponses = 0
                    var requests = 0
                    while (
                        isActive &&
                        prefetchEnabled &&
                        generation.get() == requestGeneration &&
                        knownRemaining + freshRows.size < minimumUpcoming &&
                        requests < MAX_RADIO_PREFETCH_REQUESTS
                    ) {
                        requests++
                        val requested = (
                            minimumUpcoming - knownRemaining - freshRows.size
                        ).coerceAtLeast(1)
                        val response = stationApi.probeStationNextTracks(
                            stationId,
                            limit = requested
                        )
                        if (response.statusCode !in 200..299) {
                            failureReason = "电台续播请求失败 (${response.statusCode})"
                            break
                        }
                        val tracks = parseRadioTracks(response.body)
                        if (tracks.isEmpty()) {
                            failureReason = "电台暂无更多歌曲"
                            break
                        }
                        var addedFromResponse = 0
                        for (track in tracks) {
                            if (!isActive || generation.get() != requestGeneration) return@launch
                            val isNew = synchronized(seenSongIds) {
                                if (seenSongIds.containsKey(track.id)) {
                                    false
                                } else {
                                    seenSongIds[track.id] = true
                                    true
                                }
                            }
                            if (!isNew) continue
                            val song = try {
                                stationApi.getCatalogSongLocalized(track.id)
                            } catch (_: Exception) {
                                null
                            } ?: continue
                            freshRows.add(
                                AppleLibrarySync.toRow(song).also { it.isLibrary = false }
                            )
                            addedFromResponse++
                            if (knownRemaining + freshRows.size >= minimumUpcoming) break
                        }
                        if (addedFromResponse == 0) {
                            duplicateResponses++
                            if (duplicateResponses >= MAX_RADIO_DUPLICATE_RESPONSES) {
                                failureReason = "电台连续返回重复歌曲"
                                break
                            }
                        } else {
                            duplicateResponses = 0
                        }
                    }

                    if (
                        isActive &&
                        generation.get() == requestGeneration &&
                        activeStationId == stationId
                    ) {
                        if (freshRows.isNotEmpty()) {
                            mainHandler.post {
                                // 在主线程追加前再次校验世代号，防止竞态污染新播放队列
                                if (generation.get() != requestGeneration || activeStationId != stationId) {
                                    return@post
                                }
                                MusicRepository.registerTransient(freshRows)
                                val appended = PlaybackEngine.appendToQueue(
                                    freshRows.map(AppleLibrarySync.SongRow::uri)
                                )
                                Log.i(TAG, "个性电台自动续入 $appended 首")
                            }
                        } else if (!failureReason.isNullOrBlank()) {
                            PlaybackEngine.onQueueContinuationFailed(failureReason)
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (error: Exception) {
                    Log.e(TAG, "个性电台续播失败", error)
                    if (generation.get() == requestGeneration && activeStationId == stationId) {
                        PlaybackEngine.onQueueContinuationFailed("电台续播失败：${error.message ?: "网络异常"}")
                    }
                }
            }
        }
    }
}

private const val MIN_RADIO_UPCOMING_TRACKS = 5
private const val MIN_RADIO_QUEUE_TRACKS = MIN_RADIO_UPCOMING_TRACKS + 1
private const val MAX_RADIO_PREFETCH_REQUESTS = 20
private const val MAX_RADIO_DUPLICATE_RESPONSES = 3
