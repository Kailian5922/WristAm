package com.applemusic.lyricstool.wear

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.applemusic.android.player.PlaybackEngine
import com.applemusic.android.util.Logx

class WearMusicService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        PlaybackEngine.init(this)
        Logx.i(TAG, "Wear OS playback service created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        PlaybackEngine.session()

    override fun onTaskRemoved(rootIntent: Intent?) {
        PlaybackEngine.pauseForTaskRemoval()
        stopSelf()
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
