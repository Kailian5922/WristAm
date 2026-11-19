package com.applemusic.android.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.lyrics.Lyrics
import com.applemusic.android.util.ImageLoader
import com.applemusic.lyricstool.data.AppleLyricsClient
import com.applemusic.lyricstool.data.FetchedLyrics
import com.applemusic.lyricstool.data.LyricsCache
import com.applemusic.lyricstool.data.QQMusicLyricsClient
import com.applemusic.lyricstool.data.SongHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object LiveLyricsNotification {
    private const val TAG = "LiveLyricsNotification"
    private const val CHANNEL_ID = "live_lyrics_spotlight"
    private const val NOTIFICATION_ID = 4102
    private const val UPDATE_INTERVAL_MS = 750L
    private const val LYRIC_RETRY_DELAY_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false
    @Volatile private var lyricsSongId: String? = null
    @Volatile private var lyrics: Lyrics? = null
    @Volatile private var enabled = true
    @Volatile private var lastSignature: String? = null
    @Volatile private var artworkBitmap: Bitmap? = null
    @Volatile private var artworkIconBitmap: Bitmap? = null

    private var appContext: Context? = null
    private var appleLyricsClient: AppleLyricsClient? = null
    private val qqLyricsClient = QQMusicLyricsClient()
    private var lyricsCache: LyricsCache? = null
    private var lyricsRequestJob: Job? = null
    private var requestedLyricsSongId: String? = null
    private var lyricsRequestStartedAtMs = 0L
    private var requestedArtworkUrl: String? = null

    private val playbackListener: () -> Unit = {
        handler.post { refreshScheduleAndUpdate() }
    }

    private val updateTask = object : Runnable {
        override fun run() {
            if (!started) return
            updateNotification()
            if (started && enabled && PlaybackEngine.isPlaying()) {
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        appleLyricsClient = AppleLyricsClient(context.applicationContext)
        lyricsCache = LyricsCache(context.applicationContext)
        enabled = context.getSharedPreferences("lyrics_appearance", Context.MODE_PRIVATE)
            .getBoolean("status_bar_lyrics_enabled", true)
        createChannel(context.applicationContext)
        started = true
        PlaybackEngine.addStateListener(playbackListener)
        handler.post { refreshScheduleAndUpdate() }
    }

    fun stop() {
        started = false
        PlaybackEngine.removeStateListener(playbackListener)
        handler.removeCallbacks(updateTask)
        lyricsRequestJob?.cancel()
        lyricsRequestJob = null
        lastSignature = null
        appContext?.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.applicationContext
            .getSharedPreferences("lyrics_appearance", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("status_bar_lyrics_enabled", value)
            .apply()
        handler.post { refreshScheduleAndUpdate() }
    }

    fun setLyrics(songId: String, value: Lyrics?) {
        lyricsSongId = songId
        lyrics = value
        if (value != null) {
            lyricsRequestJob?.cancel()
            requestedLyricsSongId = songId
            lyricsRequestStartedAtMs = System.currentTimeMillis()
        }
        lastSignature = null
        handler.post { refreshScheduleAndUpdate() }
    }

    private fun refreshScheduleAndUpdate() {
        handler.removeCallbacks(updateTask)
        if (!started) return
        updateNotification()
        if (enabled && PlaybackEngine.isPlaying()) {
            handler.postDelayed(updateTask, UPDATE_INTERVAL_MS)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "正在播放的歌词",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "显示当前播放歌曲的歌词"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun updateNotification() {
        val context = appContext ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!enabled) {
            if (lastSignature != null) {
                manager.cancel(NOTIFICATION_ID)
                lastSignature = null
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled()) {
            return
        }
        val song = PlaybackEngine.currentSong()
        val player = PlaybackEngine.player
        if (song == null || player == null || !PlaybackEngine.isPlaying()) {
            if (lastSignature != null) {
                manager.cancel(NOTIFICATION_ID)
                lastSignature = null
            }
            return
        }

        val songId = song.catalogId.ifBlank {
            song.appleId.ifBlank { song.uri.removePrefix("apple:") }
        }
        val currentLyrics = lyrics?.takeIf { lyricsSongId == songId }
        if (currentLyrics == null && songId.isNotBlank()) {
            requestLyricsIfNeeded(songId, song)
        }
        val artworkUrl = song.coverUrl
        if (artworkUrl != requestedArtworkUrl) {
            requestedArtworkUrl = artworkUrl
            artworkBitmap = null
            artworkIconBitmap = null
            if (artworkUrl.isNotBlank()) {
                ImageLoader.loadBitmap(artworkUrl) { bitmap ->
                    if (requestedArtworkUrl == artworkUrl) {
                        artworkBitmap = bitmap
                        artworkIconBitmap = bitmap?.let(::createCircularArtworkIcon)
                        lastSignature = null
                        handler.post { updateNotification() }
                    }
                }
            }
        }
        val position = PlaybackEngine.positionMs()
        val currentLine = currentLyrics?.let { value ->
            value.lines.getOrNull(value.indexAt(position))
        }
        val lyricText = currentLine?.text?.takeIf { it.isNotBlank() } ?: "正在获取歌词…"
        val translationText = currentLine?.translation?.takeIf { it.isNotBlank() }
        val signature = "$songId|$lyricText|$translationText|$artworkUrl|${artworkBitmap != null}"
        if (signature == lastSignature) return
        lastSignature = signature

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                action = PlaybackEngine.ACTION_OPEN_NOW_PLAYING
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val fallbackResId = context.resources.getIdentifier(
            "ic_notification_transparent",
            "drawable",
            context.packageName
        ).let { if (it != 0) it else android.R.drawable.ic_media_play }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(
                    artworkIconBitmap?.let { Icon.createWithBitmap(it) }
                        ?: Icon.createWithResource(context, fallbackResId)
                )
                .setWhen(System.currentTimeMillis())
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentTitle(lyricText)
                .setSubText(null)
                .setContentText(translationText)
                .setLargeIcon(artworkBitmap)
            if (contentIntent != null) builder.setContentIntent(contentIntent)
            // Android 16 (API 36) Live Update 特性严格做版本保护，避免低版本 NoSuchMethodError 崩溃
            if (Build.VERSION.SDK_INT >= 36) {
                applyApi36LiveUpdate(builder, lyricText.take(12))
            }
            builder.build()
        } else {
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(fallbackResId)
                .setWhen(System.currentTimeMillis())
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentTitle(lyricText)
                .setContentText(translationText)
                .setLargeIcon(artworkBitmap)
                .apply { if (contentIntent != null) setContentIntent(contentIntent) }
                .build()
        }

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "投递状态栏歌词通知失败: ${t.message}")
        }
    }

    private fun applyApi36LiveUpdate(builder: Notification.Builder, shortText: String) {
        try {
            builder.javaClass
                .getMethod("setShortCriticalText", CharSequence::class.java)
                .invoke(builder, shortText)
            builder.javaClass
                .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
        } catch (_: Throwable) {
            // 某些预览版/ROM 可能未暴露此 API，安全降级
        }
    }

    private fun createCircularArtworkIcon(source: Bitmap): Bitmap? {
        if (source.width <= 0 || source.height <= 0 || source.isRecycled) return null
        val size = 96
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = size.toFloat() / minOf(source.width, source.height)
        val scaledWidth = source.width * scale
        val scaledHeight = source.height * scale
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((size - scaledWidth) / 2f, (size - scaledHeight) / 2f)
        }
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        Canvas(output).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return output
    }

    private fun requestLyricsIfNeeded(
        songId: String,
        song: com.applemusic.android.apple.AppleLibrarySync.SongRow
    ) {
        if (
            requestedLyricsSongId == songId &&
            (lyricsRequestJob?.isActive == true ||
                System.currentTimeMillis() - lyricsRequestStartedAtMs < LYRIC_RETRY_DELAY_MS)
        ) {
            return
        }
        lyricsRequestJob?.cancel()
        requestedLyricsSongId = songId
        lyricsRequestStartedAtMs = System.currentTimeMillis()
        val appleClient = appleLyricsClient ?: return
        val cache = lyricsCache ?: return
        val songHit = SongHit(
            id = songId,
            title = song.title,
            artist = song.artist,
            album = song.album,
            artworkUrl = song.coverUrl,
            durationMs = song.durationMs,
            providerSongId = song.catalogId.takeIf { it.isNotBlank() },
            isrc = song.isrc.takeIf { it.isNotBlank() }
        )
        lyricsRequestJob = lyricsScope.launch {
            try {
                appleClient.reloadSavedSession()
                val fetched = fetchLyrics(appleClient, cache, songHit)
                val currentSong = PlaybackEngine.currentSong()
                val currentSongId = currentSong?.catalogId
                    ?.ifBlank { currentSong.appleId }
                    ?.ifBlank { currentSong.uri.removePrefix("apple:") }
                if (currentSongId == songId && fetched != null) {
                    lyricsSongId = songId
                    lyrics = fetched.lyrics
                    lastSignature = null
                    handler.post { updateNotification() }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "无法获取《${song.title}》的状态栏歌词", error)
            }
        }
    }

    private suspend fun fetchLyrics(
        appleClient: AppleLyricsClient,
        cache: LyricsCache,
        song: SongHit
    ): FetchedLyrics? {
        val resolvedCatalogId = song.providerSongId?.takeIf { it.isNotBlank() && !it.startsWith("i.") }
            ?: song.id.takeUnless { it.startsWith("i.") }
            ?: if (MusicRepository.isInitialized()) {
                MusicRepository.api.librarySongCatalogId(song.id).takeIf { it.isNotBlank() }
            } else {
                null
            }
        val cacheKeyId = resolvedCatalogId ?: song.id

        val appleCached = cache.get("apple", cacheKeyId)
        if ((appleCached?.lyrics?.wordTimedLines() ?: 0) > 0) return appleCached
        val qqCached = cache.get("qq", cacheKeyId)
        if (qqCached != null) return qqCached

        val appleLyrics = appleCached ?: resolvedCatalogId?.let { cid ->
            try {
                appleClient.fetchLyrics(cid)?.also { cache.put("apple", cacheKeyId, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if ((appleLyrics?.lyrics?.wordTimedLines() ?: 0) > 0) return appleLyrics

        val localizedMetadata = try {
            appleClient.fetchChineseSongMetadata(song)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return try {
            val qqLyrics = qqLyricsClient.fetchMatchingLyrics(
                title = song.title,
                artist = song.artist,
                durationMs = song.durationMs,
                album = song.album,
                localizedAliases = listOfNotNull(localizedMetadata)
            )
            if (qqLyrics != null) {
                cache.put("qq", cacheKeyId, qqLyrics)
                qqLyrics
            } else {
                appleLyrics
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appleLyrics ?: throw error
        }
    }
}
