package com.applemusic.lyricstool.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.applemusic.android.apple.AppleApi
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.player.PlaybackEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    WearRadioPlayback.stop(context.applicationContext)
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
        val seen = firstTracks.mapTo(HashSet<String>()) { it.id }
        firstTracks.forEach { track ->
            val song = api.getCatalogSongLocalized(track.id)
                ?: throw IllegalStateException("无法读取歌曲详情：${track.id}")
            rows.add(AppleLibrarySync.toRow(song).also { it.isLibrary = false })
        }
        var duplicateResponses = 0
        var requests = 0
        var warning: String? = null
        while (rows.size < MIN_RADIO_QUEUE_TRACKS && requests < MAX_RADIO_PREFETCH_REQUESTS) {
            requests++
            val requested = (MIN_RADIO_QUEUE_TRACKS - rows.size).coerceAtLeast(1)
            val nextResponse = api.probeStationNextTracks(stationId, limit = requested)
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
                if (!seen.add(nextTrack.id)) continue
                val song = api.getCatalogSongLocalized(nextTrack.id) ?: continue
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
    WearRadioPlayback.start(
        context.applicationContext,
        api,
        stationId,
        started.rows,
        enablePrefetch = true
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

internal object WearRadioPlayback {
    private const val TAG = "WearRadioPlayback"
    private const val RADIO_PREFERENCES = "wear_native_radio"
    private const val RADIO_STATION_KEY = "station_id"
    private const val RADIO_PREFETCH_KEY = "prefetch_enabled"

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wear-radio-next").apply { isDaemon = true }
    }
    private val fetching = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val seenSongIds = HashSet<String>()

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

    fun start(
        context: Context,
        stationApi: AppleApi,
        stationId: String,
        initialRows: List<AppleLibrarySync.SongRow>,
        enablePrefetch: Boolean = true
    ) {
        require(initialRows.isNotEmpty()) { "电台没有可播放歌曲" }
        stop(context)
        synchronized(seenSongIds) {
            seenSongIds.clear()
            initialRows.forEach { seenSongIds.add(it.appleId.ifBlank { it.catalogId }) }
        }
        appContext = context.applicationContext
        api = stationApi
        activeStationId = stationId
        prefetchEnabled = enablePrefetch
        persistRadioState()
        generation.incrementAndGet()
        MusicRepository.registerTransient(initialRows)
        PlaybackEngine.onQueueLow = if (enablePrefetch) {
            { requestNextBatch(MIN_RADIO_UPCOMING_TRACKS) }
        } else {
            null
        }
        PlaybackEngine.playQueue(initialRows.map(AppleLibrarySync.SongRow::uri), 0)
    }

    fun stop(context: Context? = null) {
        activeStationId = ""
        prefetchEnabled = false
        (context?.applicationContext ?: appContext)
            ?.getSharedPreferences(RADIO_PREFERENCES, Context.MODE_PRIVATE)
            ?.edit()
            ?.clear()
            ?.apply()
        generation.incrementAndGet()
        if (PlaybackEngine.onQueueLow != null) {
            PlaybackEngine.onQueueLow = null
        }
    }

    fun restore(context: Context, stationApi: AppleApi) {
        val preferences = context.getSharedPreferences(RADIO_PREFERENCES, Context.MODE_PRIVATE)
        val stationId = preferences.getString(RADIO_STATION_KEY, "").orEmpty()
        if (stationId.isBlank() || !preferences.getBoolean(RADIO_PREFETCH_KEY, false)) return
        if (PlaybackEngine.queueSnapshot().isEmpty()) return

        appContext = context.applicationContext
        api = stationApi
        activeStationId = stationId
        prefetchEnabled = true
        synchronized(seenSongIds) {
            seenSongIds.clear()
            PlaybackEngine.queueSnapshot().forEach { uri ->
                MusicRepository.findSong(uri)?.let { row ->
                    seenSongIds.add(row.appleId.ifBlank { row.catalogId })
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
        val stationId = activeStationId
        val stationApi = api
        if (
            !prefetchEnabled ||
            stationId.isBlank() ||
            stationApi == null ||
            !fetching.compareAndSet(false, true)
        ) {
            return
        }
        val requestGeneration = generation.get()
        executor.execute {
            try {
                val freshRows = ArrayList<AppleLibrarySync.SongRow>()
                val knownRemaining = PlaybackEngine.queueRemaining()
                var duplicateResponses = 0
                var requests = 0
                while (
                    prefetchEnabled &&
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
                    if (response.statusCode !in 200..299) break
                    val tracks = parseRadioTracks(response.body)
                    if (tracks.isEmpty()) break
                    var addedFromResponse = 0
                    for (track in tracks) {
                        val isNew = synchronized(seenSongIds) { seenSongIds.add(track.id) }
                        if (!isNew) continue
                        val song = stationApi.getCatalogSongLocalized(track.id) ?: continue
                        freshRows.add(
                            AppleLibrarySync.toRow(song).also { it.isLibrary = false }
                        )
                        addedFromResponse++
                        if (knownRemaining + freshRows.size >= minimumUpcoming) break
                    }
                    if (addedFromResponse == 0) {
                        duplicateResponses++
                        if (duplicateResponses >= MAX_RADIO_DUPLICATE_RESPONSES) break
                    } else {
                        duplicateResponses = 0
                    }
                }
                if (
                    generation.get() == requestGeneration &&
                    activeStationId == stationId &&
                    freshRows.isNotEmpty()
                ) {
                    mainHandler.post {
                        MusicRepository.registerTransient(freshRows)
                        val appended = PlaybackEngine.appendToQueue(
                            freshRows.map(AppleLibrarySync.SongRow::uri)
                        )
                        Log.i(TAG, "个性电台自动续入 $appended 首")
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "个性电台续播失败", error)
            } finally {
                fetching.set(false)
            }
        }
    }
}

private const val MIN_RADIO_UPCOMING_TRACKS = 5
private const val MIN_RADIO_QUEUE_TRACKS = MIN_RADIO_UPCOMING_TRACKS + 1
private const val MAX_RADIO_PREFETCH_REQUESTS = 20
private const val MAX_RADIO_DUPLICATE_RESPONSES = 3
