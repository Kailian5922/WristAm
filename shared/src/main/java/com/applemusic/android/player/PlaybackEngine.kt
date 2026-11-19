package com.applemusic.android.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.applemusic.android.apple.AppleCache
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.util.Logx
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/**
 * 播放引擎（单例）：
 * - 单通道可取消准备线程池（快速连切秒取消旧下载，杜绝手表 OOM）
 * - ExoPlayer WakeMode + 后台准备期定时 Partial WakeLock（灭屏切歌不中断）
 * - 响应式 [stateFlow] + 多订阅者监听（Activity 重建不丢监听）
 * - 后台线程异步持久化队列快照（消除主线程 5s 周期 JSON 序列化卡顿）
 */
object PlaybackEngine {

    private const val TAG = "PlaybackEngine"
    const val ACTION_OPEN_NOW_PLAYING = "com.applemusic.android.action.OPEN_NOW_PLAYING"
    private const val QUEUE_LOW_WATER = 4
    private const val PLAYBACK_PREFERENCES = "playback_state"
    private const val PLAYBACK_STATE_KEY = "queue_snapshot"
    private const val PERSIST_INTERVAL_MS = 5_000L
    private const val MAX_PERSISTED_WINDOW = 60

    data class Snapshot(
        val queue: List<String> = emptyList(),
        val queueIndex: Int = -1,
        val playingIndex: Int = -1,
        val currentSong: AppleLibrarySync.SongRow? = null,
        val targetSong: AppleLibrarySync.SongRow? = null,
        val isPlaying: Boolean = false,
        val playbackState: Int = Player.STATE_IDLE,
        val preparing: Boolean = false,
        val preparingNote: String = "",
        val durationMs: Long = 0L,
        val seekRevision: Long = 0L,
        val revision: Long = 0L
    )

    @Volatile
    var player: ExoPlayer? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val prepareExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "audio-prepare").apply { isDaemon = true }
    }
    private val maintenanceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "playback-maint").apply { isDaemon = true }
    }

    @Volatile
    private var activePrepareFuture: Future<*>? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var restorePositionMs = 0L

    @Volatile
    private var restorePlayWhenReady = false

    @Volatile
    private var pendingPlaybackRestore = false

    private val playGeneration = AtomicLong(0L)
    private val seekCounter = AtomicLong(0L)
    private val stateRevision = AtomicLong(0L)

    private val persistSnapshotRunnable = object : Runnable {
        override fun run() {
            persistQueueState()
            if (isPlaying()) main.postDelayed(this, PERSIST_INTERVAL_MS)
        }
    }

    private val queue = ArrayList<String>()

    @Volatile
    var queueIndex = -1
        private set

    @Volatile
    private var skipPendingForQueueContinuation = false

    @Volatile
    private var playingIndex = -1

    @Volatile
    var preparing = false
        private set

    @Volatile
    var preparingNote = ""

    private val badUris = HashSet<String>()

    @Volatile
    private var lastIoRetryUri: String? = null

    private val _stateFlow = MutableStateFlow(Snapshot())
    val stateFlow: StateFlow<Snapshot> = _stateFlow.asStateFlow()

    private val stateListeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    var onStateChanged: (() -> Unit)? = null

    @Volatile
    var onQueueLow: ((remaining: Int) -> Unit)? = null

    @Volatile
    var onQueueEnded: (() -> Boolean)? = null

    @Volatile
    var onPrefetchLyrics: ((String) -> Unit)? = null

    private var mediaSession: MediaSession? = null
    private var selfControllerFuture: ListenableFuture<MediaController>? = null
    private var selfController: MediaController? = null
    private var mediaSessionServiceClass: Class<out MediaSessionService>? = null

    fun addStateListener(listener: () -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: () -> Unit) {
        stateListeners.remove(listener)
    }

    fun session(): MediaSession? = mediaSession

    private fun dispatchStateChanged() {
        val emit = Runnable {
            val snap = buildSnapshot()
            _stateFlow.value = snap
            try {
                onStateChanged?.invoke()
            } catch (_: Throwable) {
            }
            for (l in stateListeners) {
                try {
                    l()
                } catch (_: Throwable) {
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            emit.run()
        } else {
            main.post(emit)
        }
    }

    private fun buildSnapshot(): Snapshot {
        val qCopy: List<String>
        val qIdx: Int
        val pIdx: Int
        val playingUri: String?
        val targetUri: String?
        synchronized(queue) {
            qCopy = ArrayList(queue)
            qIdx = queueIndex
            pIdx = playingIndex
            playingUri = queue.getOrNull(pIdx)
            targetUri = queue.getOrNull(qIdx)
        }
        val curSong = playingUri?.let { MusicRepository.findSong(it) }
            ?: targetUri?.let { MusicRepository.findSong(it) }
        val tgtSong = targetUri?.let { MusicRepository.findSong(it) }
        val p = player
        val exoDur = p?.duration?.takeIf { it > 0L } ?: 0L
        val dur = if (exoDur > 0L) exoDur else (curSong?.durationMs ?: 0L)
        return Snapshot(
            queue = qCopy,
            queueIndex = qIdx,
            playingIndex = pIdx,
            currentSong = curSong,
            targetSong = tgtSong,
            isPlaying = p?.isPlaying == true,
            playbackState = p?.playbackState ?: Player.STATE_IDLE,
            preparing = preparing,
            preparingNote = preparingNote,
            durationMs = dur,
            seekRevision = seekCounter.get(),
            revision = stateRevision.incrementAndGet()
        )
    }

    fun init(
        context: Context,
        serviceClass: Class<out MediaSessionService>? = null
    ) {
        appContext = context.applicationContext
        if (serviceClass != null) {
            mediaSessionServiceClass = serviceClass
        } else if (mediaSessionServiceClass == null) {
            mediaSessionServiceClass = try {
                @Suppress("UNCHECKED_CAST")
                Class.forName("com.applemusic.lyricstool.wear.WearMusicService") as Class<out MediaSessionService>
            } catch (_: Throwable) {
                MusicService::class.java
            }
        }
        if (player != null) {
            dispatchStateChanged()
            return
        }

        val p = ExoPlayer.Builder(context.applicationContext).build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true
        )
        p.setHandleAudioBecomingNoisy(true)
        // 灭屏播放保活，避免 Wear OS Doze 中断音频解码
        p.setWakeMode(C.WAKE_MODE_LOCAL)

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Logx.i(TAG, "playbackState=$playbackState (1=IDLE 2=BUFFERING 3=READY 4=ENDED)")
                if (playbackState == Player.STATE_READY) {
                    preparingNote = ""
                    lastIoRetryUri = null
                }
                if (playbackState == Player.STATE_ENDED) {
                    next()
                }
                persistQueueState()
                dispatchStateChanged()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Logx.i(TAG, "isPlaying=$isPlaying")
                main.removeCallbacks(persistSnapshotRunnable)
                persistQueueState()
                if (isPlaying) {
                    main.postDelayed(persistSnapshotRunnable, PERSIST_INTERVAL_MS)
                }
                dispatchStateChanged()
            }

            override fun onPlayerError(error: PlaybackException) {
                Logx.e(TAG, "播放器错误：${error.errorCodeName} ${error.message}", error)
                var cause: Throwable? = error.cause
                var depth = 0
                while (cause != null && depth < 8) {
                    Logx.e(TAG, "  因果[$depth] ${cause.javaClass.simpleName}: ${cause.message}")
                    cause = cause.cause
                    depth++
                }
                val curUri = currentUri()
                val expectedGen = playGeneration.get()
                val expectedIndex = playingIndex
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCodeName.contains("IO")
                ) {
                    if (curUri != null && lastIoRetryUri != curUri) {
                        lastIoRetryUri = curUri
                        Logx.w(TAG, "检测到 IO 文件错误，后台清理失效缓存后重载：$curUri")
                        val cleanId = curUri.removePrefix("apple:")
                        maintenanceExecutor.execute {
                            try {
                                MusicRepository.cache.invalidate(cleanId, includePinned = false)
                            } catch (_: Throwable) {
                            }
                            main.postDelayed({
                                if (playGeneration.get() == expectedGen && currentUri() == curUri) {
                                    queueIndex = expectedIndex.coerceAtLeast(0)
                                    prepareAndPlay(curUri, resumePositionMs = 0L, playWhenReady = true)
                                }
                            }, 500L)
                        }
                        return
                    }
                }
                preparing = false
                preparingNote = "播放失败：${error.errorCodeName}"
                dispatchStateChanged()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                dispatchStateChanged()
            }
        })
        player = p
        restoreQueueState()
        Logx.i(TAG, "ExoPlayer 已初始化")
        registerMediaSession(context.applicationContext, p)
        dispatchStateChanged()
    }

    private fun registerMediaSession(ctx: Context, p: Player) {
        if (mediaSession != null) return
        try {
            val builder = MediaSession.Builder(ctx, RadioPlayer(p))
            val appIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            if (appIntent != null) {
                appIntent.action = ACTION_OPEN_NOW_PLAYING
                appIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val openApp = PendingIntent.getActivity(
                    ctx,
                    0,
                    appIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.setSessionActivity(openApp)
            }
            mediaSession = builder.build()
            Logx.i(TAG, "MediaSession 已注册")
            connectSelfController(ctx)
        } catch (t: Throwable) {
            Logx.w(TAG, "MediaSession 注册失败：${t.message}")
        }
    }

    private fun connectSelfController(ctx: Context) {
        if (mediaSession == null || selfControllerFuture != null) return
        try {
            val targetService = mediaSessionServiceClass ?: MusicService::class.java
            val token = SessionToken(ctx, ComponentName(ctx, targetService))
            val future = MediaController.Builder(ctx, token).buildAsync()
            selfControllerFuture = future
            future.addListener(
                {
                    try {
                        selfController = future.get()
                        Logx.i(TAG, "已连接自身 MediaController（经 ${targetService.simpleName}）")
                    } catch (t: Throwable) {
                        Logx.w(TAG, "自身 MediaController 连接失败：${t.message}")
                    }
                },
                ContextCompat.getMainExecutor(ctx)
            )
        } catch (t: Throwable) {
            Logx.w(TAG, "构造自身 MediaController 失败：${t.message}")
        }
    }

    /** 服务准备 stopSelf 前先解绑 selfController，消除自绑定泄漏。 */
    fun releaseSelfController() {
        val future = selfControllerFuture
        selfControllerFuture = null
        if (future != null) {
            try {
                MediaController.releaseFuture(future)
            } catch (_: Throwable) {
            }
        }
        try {
            selfController?.release()
        } catch (_: Throwable) {
        }
        selfController = null
    }

    private class RadioPlayer(player: Player) : ForwardingPlayer(player) {
        private val extra = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_PLAY_PAUSE
        )

        override fun getAvailableCommands(): Player.Commands {
            var b = super.getAvailableCommands().buildUpon()
            for (c in extra) b = b.add(c)
            return b.build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (extra.contains(command)) return true
            return super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true

        override fun play() {
            val p = wrappedPlayer
            if (pendingPlaybackRestore || p.mediaItemCount == 0 || p.playbackState == Player.STATE_IDLE ||
                p.playbackState == Player.STATE_ENDED
            ) {
                PlaybackEngine.playOrResume()
            } else {
                super.play()
            }
        }

        override fun seekToNext() {
            Logx.i(TAG, "媒体按键：下一首")
            PlaybackEngine.next()
        }

        override fun seekToNextMediaItem() = seekToNext()

        override fun seekToPrevious() {
            Logx.i(TAG, "媒体按键：上一首")
            PlaybackEngine.prev()
        }

        override fun seekToPreviousMediaItem() = seekToPrevious()
    }

    fun appendToQueue(uris: List<String>): Int {
        if (uris.isEmpty()) return 0
        var added = 0
        var pendingIndex = -1
        var totalSize = 0
        synchronized(queue) {
            val existing = HashSet(queue)
            for (u in uris) {
                if (u.isEmpty() || !existing.add(u)) continue
                queue.add(u)
                added++
            }
            totalSize = queue.size
            if (added > 0 && skipPendingForQueueContinuation) {
                skipPendingForQueueContinuation = false
                val cursor = if (queueIndex >= 0) queueIndex else playingIndex
                val candidate = cursor + 1
                if (candidate in 0 until totalSize) {
                    pendingIndex = candidate
                }
            }
        }
        if (added > 0) {
            persistQueueState()
            Logx.i(TAG, "队列续杯 +$added 首（现共 $totalSize 首）")
            dispatchStateChanged()
            if (pendingIndex >= 0) {
                playAt(pendingIndex)
            }
        }
        return added
    }

    /** 通知引擎电台续杯失败或暂时无新曲，避免永久卡在等待续歌状态。 */
    fun onQueueContinuationFailed(reason: String) {
        val wasPending = synchronized(queue) {
            val v = skipPendingForQueueContinuation
            skipPendingForQueueContinuation = false
            v
        }
        if (wasPending) {
            preparing = false
            preparingNote = reason
            dispatchStateChanged()
        }
    }

    fun queueRemaining(): Int = synchronized(queue) {
        val cursor = if (queueIndex >= 0) queueIndex else playingIndex
        (queue.size - cursor - 1).coerceAtLeast(0)
    }

    private fun notifyIfQueueLow() {
        val cb = onQueueLow ?: return
        val remaining = queueRemaining()
        if (remaining <= QUEUE_LOW_WATER) {
            Logx.i(TAG, "队列只剩 $remaining 首，请求续杯")
            cb(remaining)
        }
    }

    fun currentUri(): String? = synchronized(queue) {
        queue.getOrNull(playingIndex) ?: queue.getOrNull(queueIndex)
    }

    fun currentSong(): AppleLibrarySync.SongRow? = currentUri()?.let { MusicRepository.findSong(it) }

    fun targetUri(): String? = synchronized(queue) { queue.getOrNull(queueIndex) }

    fun targetSong(): AppleLibrarySync.SongRow? = targetUri()?.let { MusicRepository.findSong(it) }

    fun queueSnapshot(): List<String> = synchronized(queue) { ArrayList(queue) }

    fun currentQueueIndex(): Int = synchronized(queue) {
        if (playingIndex in 0 until queue.size) playingIndex else queueIndex
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun resumeRestoredPlayback() {
        if (!pendingPlaybackRestore) return
        val uri = synchronized(queue) { queue.getOrNull(queueIndex) } ?: return
        pendingPlaybackRestore = false
        restorePlayWhenReady = false
        prepareAndPlay(uri, restorePositionMs, playWhenReady = false)
    }

    fun pauseForTaskRemoval() {
        restorePlayWhenReady = false
        player?.pause()
        persistQueueState()
        dispatchStateChanged()
    }

    /** 退出账号时彻底停止播放并清理队列与持久化快照。 */
    fun stopAndClearForLogout() {
        playGeneration.incrementAndGet()
        activePrepareFuture?.cancel(true)
        activePrepareFuture = null
        main.removeCallbacks(persistSnapshotRunnable)
        synchronized(queue) {
            queue.clear()
            queueIndex = -1
            playingIndex = -1
            skipPendingForQueueContinuation = false
        }
        synchronized(badUris) { badUris.clear() }
        pendingPlaybackRestore = false
        restorePositionMs = 0L
        restorePlayWhenReady = false
        preparing = false
        preparingNote = ""
        lastIoRetryUri = null
        onQueueLow = null
        onQueueEnded = null
        player?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
        appContext?.getSharedPreferences(PLAYBACK_PREFERENCES, Context.MODE_PRIVATE)
            ?.edit()?.remove(PLAYBACK_STATE_KEY)?.apply()
        dispatchStateChanged()
    }

    /** 用一列 uri 播放下标 index 的歌。 */
    fun playQueue(uris: List<String>, index: Int) {
        // 先推进代际并取消在途准备任务，防止旧队列异步任务覆盖新队列
        playGeneration.incrementAndGet()
        activePrepareFuture?.cancel(true)
        activePrepareFuture = null

        val safeIndex = if (uris.isEmpty()) -1 else index.coerceIn(0, uris.lastIndex)
        synchronized(queue) {
            queue.clear()
            queue.addAll(uris)
            queueIndex = safeIndex
            playingIndex = -1
            skipPendingForQueueContinuation = false
        }
        pendingPlaybackRestore = false
        restorePositionMs = 0L
        restorePlayWhenReady = false
        preparing = false
        preparingNote = ""
        lastIoRetryUri = null
        synchronized(badUris) { badUris.clear() }

        player?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
        if (safeIndex < 0) {
            persistQueueState()
            dispatchStateChanged()
            return
        }
        playAt(safeIndex)
    }

    fun playSingle(uri: String) = playQueue(listOf(uri), 0)

    /** 播放队列里的第 index 首（清理所有挂起恢复/续杯标志）。 */
    fun playAt(index: Int) {
        val uri = synchronized(queue) {
            val u = queue.getOrNull(index) ?: return
            queueIndex = index
            skipPendingForQueueContinuation = false
            u
        }
        pendingPlaybackRestore = false
        restorePositionMs = 0L
        restorePlayWhenReady = false

        player?.pause()
        persistQueueState()
        dispatchStateChanged()
        notifyIfQueueLow()
        prepareAndPlay(uri)
    }

    private fun markBad(uri: String?) {
        if (uri.isNullOrEmpty()) return
        synchronized(badUris) { badUris.add(uri) }
    }

    private fun isBad(uri: String?): Boolean {
        if (uri.isNullOrEmpty()) return true
        return synchronized(badUris) { badUris.contains(uri) }
    }

    /** 仅严格向后扫描可播曲目（不回绕），找不到返回 -1。 */
    private fun findPlayableForward(from: Int, n: Int): Int {
        for (idx in (from + 1) until n) {
            val u = synchronized(queue) { queue.getOrNull(idx) }
            if (!isBad(u)) return idx
        }
        return -1
    }

    /** 环形查找可播曲目，全部为坏条目时返回 -1。 */
    private fun findPlayableWrapped(from: Int, step: Int, n: Int): Int {
        if (n <= 0) return -1
        var idx = ((from + step) % n + n) % n
        var guard = 0
        while (guard < n) {
            val u = synchronized(queue) { queue.getOrNull(idx) }
            if (!isBad(u)) return idx
            idx = ((idx + step) % n + n) % n
            guard++
        }
        return -1
    }

    fun next() {
        val n = synchronized(queue) { queue.size }
        if (n == 0) return
        val cursor = if (queueIndex >= 0) queueIndex else playingIndex
        val forward = findPlayableForward(cursor, n)
        if (forward >= 0) {
            playAt(forward)
            val continuation = onQueueLow
            if (continuation != null) {
                val remaining = queueRemaining()
                if (remaining <= QUEUE_LOW_WATER + 2) {
                    continuation(remaining)
                }
            }
            return
        }
        // 后方已无可用曲目，先交给电台/外部续杯回调处理
        if (onQueueEnded?.invoke() == true) return
        val continuation = onQueueLow
        if (continuation != null) {
            synchronized(queue) { skipPendingForQueueContinuation = true }
            Logx.i(TAG, "电台队列暂无下一首，等待 Apple 续歌后跳转")
            continuation(0)
            dispatchStateChanged()
            return
        }
        // 非电台模式：单曲播完直接停止不死循环；多曲列表回绕到首首可播曲
        if (n == 1 && player?.playbackState == Player.STATE_ENDED) {
            dispatchStateChanged()
            return
        }
        val wrapped = findPlayableWrapped(cursor, 1, n)
        if (wrapped >= 0) {
            playAt(wrapped)
        } else {
            preparing = false
            preparingNote = "队列中没有可播放的歌曲"
            dispatchStateChanged()
        }
    }

    fun prev() {
        val n = synchronized(queue) { queue.size }
        if (n == 0) return
        val cursor = if (queueIndex >= 0) queueIndex else playingIndex
        val target = findPlayableWrapped(cursor, -1, n)
        if (target >= 0) {
            playAt(target)
        }
    }

    fun playOrResume() {
        if (player == null) {
            appContext?.let { init(it, mediaSessionServiceClass) }
        }
        val p = player ?: return
        val queueSize = synchronized(queue) { queue.size }
        if (queueSize == 0) return

        val needsPrepare = pendingPlaybackRestore ||
            p.mediaItemCount == 0 ||
            (p.playbackState == Player.STATE_IDLE && playingIndex != queueIndex)
        if (needsPrepare) {
            val idx = when {
                queueIndex in 0 until queueSize -> queueIndex
                playingIndex in 0 until queueSize -> playingIndex
                else -> 0
            }
            val uri = synchronized(queue) { queue.getOrNull(idx) } ?: return
            val resumePos = if (pendingPlaybackRestore) restorePositionMs else 0L
            pendingPlaybackRestore = false
            restorePlayWhenReady = true
            synchronized(queue) { queueIndex = idx }
            prepareAndPlay(uri, resumePos, playWhenReady = true)
            return
        }

        when (p.playbackState) {
            Player.STATE_IDLE -> {
                p.prepare()
                p.play()
            }
            Player.STATE_ENDED -> {
                p.seekTo(0L)
                p.play()
            }
            else -> p.play()
        }
        persistQueueState()
        dispatchStateChanged()
    }

    fun toggle() {
        val p = player
        if (p != null && p.isPlaying) {
            p.pause()
            persistQueueState()
            dispatchStateChanged()
        } else {
            playOrResume()
        }
    }

    fun seekTo(ms: Long) {
        val targetMs = ms.coerceAtLeast(0L)
        if (pendingPlaybackRestore) {
            restorePositionMs = targetMs
            seekCounter.incrementAndGet()
            persistQueueState()
            dispatchStateChanged()
            return
        }
        val p = player ?: return
        p.seekTo(targetMs)
        seekCounter.incrementAndGet()
        persistQueueState()
        dispatchStateChanged()
    }

    fun positionMs(): Long {
        if (pendingPlaybackRestore && restorePositionMs > 0L) return restorePositionMs
        return player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    }

    fun durationMs(): Long {
        val d = player?.duration ?: 0L
        if (d > 0L) return d
        return currentSong()?.durationMs ?: 0L
    }

    private fun prepareAndPlay(
        uri: String,
        resumePositionMs: Long = 0L,
        playWhenReady: Boolean = true
    ) {
        if (player == null) {
            appContext?.let { init(it, mediaSessionServiceClass) }
        }
        val currentGen = playGeneration.incrementAndGet()
        activePrepareFuture?.cancel(true)

        Logx.i(TAG, "prepareAndPlay [gen=$currentGen] 收到 uri=$uri")
        val song = MusicRepository.findSong(uri)
        if (song == null) {
            Logx.w(TAG, "找不到这首歌：$uri（本地库 ${MusicRepository.songCount()} 首）")
            preparingNote = "找不到这首歌"
            preparing = false
            dispatchStateChanged()
            return
        }
        val rawId = if (song.appleId.isNotEmpty()) song.appleId else uri.removePrefix("apple:")
        val wantIndex = queueIndex
        preparing = true
        preparingNote = "准备中…"
        dispatchStateChanged()

        activePrepareFuture = prepareExecutor.submit {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            // 灭屏切换下一首时短暂持有 Partial WakeLock，避免 Doze 挂起解密下载线程
            val wakeLock = acquireTempWakeLock()
            try {
                if (currentGen != playGeneration.get() || Thread.currentThread().isInterrupted) return@submit
                val path = MusicRepository.cache.ensure(
                    songId = rawId,
                    isLibrary = song.isLibrary,
                    onProgress = { _, percent, note ->
                        if (currentGen != playGeneration.get()) return@ensure
                        main.post {
                            if (currentGen == playGeneration.get()) {
                                preparingNote = "$note $percent%"
                                dispatchStateChanged()
                            }
                        }
                    },
                    metadata = AppleCache.SongMetadata(
                        title = song.title,
                        artist = song.artist,
                        album = song.album
                    )
                )
                if (currentGen != playGeneration.get() || Thread.currentThread().isInterrupted) {
                    Logx.i(TAG, "gen=$currentGen 已被新请求覆盖，丢弃就绪结果")
                    return@submit
                }
                Logx.i(TAG, "缓存就绪：$path")
                main.post {
                    if (currentGen != playGeneration.get()) return@post
                    preparing = false
                    try {
                        val local = File(path)
                        if (!local.exists() || local.length() < AppleCache.MIN_VALID_BYTES) {
                            failPlayback(wantIndex, "缓存文件异常（${local.length()}B）")
                            return@post
                        }
                        if (player == null) {
                            appContext?.let { init(it, mediaSessionServiceClass) }
                        }
                        val p = player
                        if (p == null) {
                            failPlayback(wantIndex, "播放器未初始化")
                            return@post
                        }
                        val fileUri = Uri.fromFile(local)
                        val md = MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setAlbumTitle(song.album)
                            .apply {
                                if (song.coverUrl.isNotEmpty()) {
                                    setArtworkUri(Uri.parse(song.coverUrl))
                                }
                            }
                            .build()
                        p.setMediaItem(
                            MediaItem.Builder().setUri(fileUri).setMediaMetadata(md).build()
                        )
                        p.prepare()
                        if (resumePositionMs > 0L) p.seekTo(resumePositionMs)
                        p.playWhenReady = playWhenReady
                        synchronized(queue) {
                            playingIndex = wantIndex
                        }
                        restorePlayWhenReady = false
                        restorePositionMs = 0L
                        preparingNote = ""
                        persistQueueState()
                        dispatchStateChanged()
                        prefetchNextSongInQueue(wantIndex)
                    } catch (t: Throwable) {
                        Logx.e(TAG, "启动播放失败：${t.message}", t)
                        failPlayback(wantIndex, "启动播放失败：${t.message}")
                    }
                }
            } catch (e: Throwable) {
                if (e is InterruptedException || currentGen != playGeneration.get()) {
                    Logx.i(TAG, "gen=$currentGen 准备任务已取消")
                    return@submit
                }
                Logx.e(TAG, "准备失败 $rawId: ${e.message}", e)
                main.post {
                    if (currentGen != playGeneration.get()) return@post
                    failPlayback(wantIndex, e.message ?: "准备失败")
                }
            } finally {
                releaseTempWakeLock(wakeLock)
            }
        }
    }

    private fun acquireTempWakeLock(): PowerManager.WakeLock? {
        return try {
            val pm = appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristAm:AudioPrepare").apply {
                setReferenceCounted(false)
                acquire(90_000L)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun releaseTempWakeLock(wl: PowerManager.WakeLock?) {
        try {
            if (wl?.isHeld == true) wl.release()
        } catch (_: Throwable) {
        }
    }

    fun pruneCacheAround(index: Int) {
        val allowedIds = HashSet<String>()
        synchronized(queue) {
            val start = (index - 15).coerceAtLeast(0)
            val end = (index + 15).coerceAtMost(queue.size - 1)
            for (i in start..end) {
                val uri = queue.getOrNull(i) ?: continue
                val song = MusicRepository.findSong(uri)
                val rawId = song?.appleId?.takeIf { it.isNotEmpty() } ?: uri.removePrefix("apple:")
                if (rawId.isNotEmpty()) allowedIds.add(rawId)
                song?.catalogId?.takeIf { it.isNotEmpty() }?.let { allowedIds.add(it) }
            }
        }
        currentUri()?.let { uri ->
            val song = MusicRepository.findSong(uri)
            val rawId = song?.appleId?.takeIf { it.isNotEmpty() } ?: uri.removePrefix("apple:")
            if (rawId.isNotEmpty()) allowedIds.add(rawId)
            song?.catalogId?.takeIf { it.isNotEmpty() }?.let { allowedIds.add(it) }
        }
        if (allowedIds.isEmpty()) return
        maintenanceExecutor.execute {
            try {
                MusicRepository.cache.retainOnly(allowedIds)
            } catch (e: Throwable) {
                Logx.w(TAG, "清理多余缓存异常: ${e.message}")
            }
        }
    }

    private fun prefetchNextSongInQueue(currentIndex: Int) {
        // 即使当前是队列最后一首，也要修剪窗口外旧临时缓存
        pruneCacheAround(currentIndex)

        val nextUri = synchronized(queue) {
            val nextIndex = currentIndex + 1
            if (nextIndex in 0 until queue.size) queue[nextIndex] else null
        } ?: return

        val song = MusicRepository.findSong(nextUri)
        val rawId = song?.appleId?.takeIf { it.isNotEmpty() } ?: nextUri.removePrefix("apple:")
        val isLibrary = song?.isLibrary ?: rawId.startsWith("i.")
        val metadata = song?.let {
            AppleCache.SongMetadata(title = it.title, artist = it.artist, album = it.album)
        }
        Logx.i(TAG, "正在后台预加载下一首: $nextUri (${song?.title ?: ""})")
        onPrefetchLyrics?.invoke(nextUri)
        MusicRepository.cache.preload(rawId, isLibrary, metadata)
    }

    private fun failPlayback(wantIndex: Int, reason: String) {
        preparing = false
        preparingNote = "无法播放：$reason"
        val badUri = synchronized(queue) { queue.getOrNull(wantIndex) }
        markBad(badUri)
        synchronized(queue) {
            queueIndex = if (playingIndex >= 0) playingIndex else wantIndex
        }
        player?.stop()
        Logx.w(TAG, "播放失败，当前曲目回退到 index=$queueIndex（原因：$reason）")
        persistQueueState()
        dispatchStateChanged()
    }

    private fun restoreQueueState() {
        val context = appContext ?: return
        val encoded = context.getSharedPreferences(PLAYBACK_PREFERENCES, Context.MODE_PRIVATE)
            .getString(PLAYBACK_STATE_KEY, null) ?: return
        try {
            val snapshot = JSONObject(encoded)
            val uris = ArrayList<String>()
            val rows = ArrayList<AppleLibrarySync.SongRow>()
            val entries = snapshot.optJSONArray("items") ?: JSONArray()
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val uri = entry.optString("uri", "")
                if (uri.isEmpty()) continue
                uris.add(uri)
                entry.optJSONObject("song")?.let { rows.add(AppleLibrarySync.SongRow.fromJson(it)) }
            }
            if (uris.isEmpty()) return

            if (MusicRepository.isInitialized()) {
                MusicRepository.registerTransient(rows)
            }
            val selectedIndex = snapshot.optInt("queueIndex", -1).coerceIn(0, uris.lastIndex)
            val restoredPlayingIndex = snapshot.optInt("playingIndex", selectedIndex)
                .takeIf { it in uris.indices } ?: selectedIndex
            synchronized(queue) {
                queue.clear()
                queue.addAll(uris)
                queueIndex = selectedIndex
                playingIndex = restoredPlayingIndex
            }
            restorePositionMs = snapshot.optLong("positionMs", 0L).coerceAtLeast(0L)
            restorePlayWhenReady = false
            pendingPlaybackRestore = true
            Logx.i(TAG, "恢复播放队列 ${uris.size} 首，当前位置=$selectedIndex")
        } catch (error: Exception) {
            Logx.e(TAG, "恢复播放队列失败：${error.message}", error)
        }
    }

    /** 仅在主线程抓轻量变量，序列化与落盘全部卸载到后台单线程执行器（零主线程卡顿）。 */
    private fun persistQueueState() {
        val context = appContext ?: return
        val urisSnap: List<String>
        val qIdx: Int
        val pIdx: Int
        synchronized(queue) {
            urisSnap = ArrayList(queue)
            qIdx = queueIndex
            pIdx = playingIndex
        }
        val currentPosition = if (pendingPlaybackRestore && restorePositionMs > 0L) {
            restorePositionMs
        } else {
            player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        }
        val playing = player?.playWhenReady ?: restorePlayWhenReady

        maintenanceExecutor.execute {
            try {
                val preferences = context.getSharedPreferences(PLAYBACK_PREFERENCES, Context.MODE_PRIVATE)
                if (urisSnap.isEmpty()) {
                    preferences.edit().remove(PLAYBACK_STATE_KEY).apply()
                    return@execute
                }
                // 裁剪超长电台队列窗口，防止 SharedPreferences JSON 无限膨胀
                val anchor = if (pIdx in urisSnap.indices) pIdx else qIdx.coerceIn(0, urisSnap.lastIndex)
                val start = (anchor - MAX_PERSISTED_WINDOW / 2).coerceAtLeast(0)
                val end = (start + MAX_PERSISTED_WINDOW).coerceAtMost(urisSnap.size)
                val sliced = urisSnap.subList(start, end)
                val adjQ = (qIdx - start).coerceIn(0, sliced.lastIndex)
                val adjP = (pIdx - start).coerceIn(0, sliced.lastIndex)

                val items = JSONArray()
                for (uri in sliced) {
                    val entry = JSONObject().put("uri", uri)
                    if (MusicRepository.isInitialized()) {
                        MusicRepository.findSong(uri)?.let { entry.put("song", it.toJson()) }
                    }
                    items.put(entry)
                }
                val snapshot = JSONObject()
                    .put("items", items)
                    .put("queueIndex", adjQ)
                    .put("playingIndex", adjP)
                    .put("positionMs", currentPosition)
                    .put("isPlaying", playing)
                preferences.edit().putString(PLAYBACK_STATE_KEY, snapshot.toString()).apply()
            } catch (t: Throwable) {
                Logx.w(TAG, "持久化播放队列失败: ${t.message}")
            }
        }
    }

    fun release() {
        playGeneration.incrementAndGet()
        activePrepareFuture?.cancel(true)
        activePrepareFuture = null
        preparing = false
        main.removeCallbacks(persistSnapshotRunnable)
        persistQueueState()
        releaseSelfController()
        try {
            mediaSession?.release()
        } catch (_: Throwable) {
        }
        mediaSession = null
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        dispatchStateChanged()
    }
}
