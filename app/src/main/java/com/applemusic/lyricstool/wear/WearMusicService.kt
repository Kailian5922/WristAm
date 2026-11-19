package com.applemusic.lyricstool.wear

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.player.PlaybackEngine
import com.applemusic.android.util.Logx

/**
 * Wear OS 后台媒体播放服务：
 * - 支持冷启动（系统媒体控件/蓝牙耳机恢复播放时自动确保仓库与播放器初始化）；
 * - 播放或缓冲期间划掉最近任务不杀死音乐进程；
 * - 捕获 Android 12+/Wear OS 后台前台服务通知受限异常防止崩溃。
 */
class WearMusicService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as? WristAmApplication
        if (!MusicRepository.isInitialized()) {
            val store = app?.container?.tokenStore ?: EncryptedTokenStore(applicationContext)
            MusicRepository.init(
                applicationContext,
                WearAppleApiTokenStore(applicationContext, store)
            )
        }
        PlaybackEngine.init(this, WearMusicService::class.java)
        Logx.i(TAG, "Wear OS playback service created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        PlaybackEngine.session()

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        try {
            super.onUpdateNotification(session, startInForegroundRequired)
        } catch (t: Throwable) {
            Logx.w(TAG, "更新媒体通知受限（安全忽略）：${t.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 正在播放或缓冲时保留后台播放；仅在完全空闲暂停时清理自建控制器并退出服务
        if (!PlaybackEngine.isPlaying() && !PlaybackEngine.preparing) {
            PlaybackEngine.pauseForTaskRemoval()
            PlaybackEngine.releaseSelfController()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        PlaybackEngine.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WearMusicService"
    }
}
