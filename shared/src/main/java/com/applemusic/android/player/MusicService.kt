package com.applemusic.android.player

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.util.Logx

/**
 * 通用媒体后台服务（手机/兼容端）。
 */
class MusicService : MediaSessionService() {

    private companion object {
        const val TAG = "MusicService"
    }

    override fun onCreate() {
        super.onCreate()
        Logx.init(filesDir)
        MusicRepository.init(applicationContext)
        PlaybackEngine.init(this, MusicService::class.java)
        LiveLyricsNotification.start(this)
        Logx.i(TAG, "媒体服务已创建")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val s = PlaybackEngine.session()
        Logx.i(
            TAG,
            "onGetSession：package=${controllerInfo.packageName} 会话=${if (s != null) "有" else "无"}"
        )
        return s
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        try {
            super.onUpdateNotification(session, startInForegroundRequired)
        } catch (t: Throwable) {
            Logx.e(TAG, "投递媒体通知失败：${t.message}", t)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!PlaybackEngine.isPlaying() && !PlaybackEngine.preparing) {
            PlaybackEngine.releaseSelfController()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Logx.i(TAG, "媒体服务已销毁")
        LiveLyricsNotification.stop()
        PlaybackEngine.release()
        super.onDestroy()
    }
}
