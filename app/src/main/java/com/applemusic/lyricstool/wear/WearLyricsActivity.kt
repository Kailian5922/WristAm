package com.applemusic.lyricstool.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.player.PlaybackEngine
import com.applemusic.android.util.Logx
import com.applemusic.lyricstool.wear.ui.navigation.WearAppRoot
import com.applemusic.lyricstool.wear.ui.theme.HorologistColors

/**
 * 极简 Wear OS 入口 Activity：
 * - 仅承担生命周期桥接、通知权限申请与宿主 Compose 根视图；
 * - 所有状态、业务逻辑与仓库均托管于 Application 级 [WristAmApplication.container] 与 Jetpack ViewModel。
 */
class WearLyricsActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 权限结果无需弹窗阻塞，媒体控件与通知自动生效 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)

        val app = WristAmApplication.from(this)
        val startupError = app.startupError

        if (startupError == null) {
            PlaybackEngine.init(applicationContext, WearMusicService::class.java)
            try {
                startService(Intent(this, WearMusicService::class.java))
            } catch (e: Exception) {
                Logx.w(TAG, "无法拉起 WearMusicService：${e.message}")
            }
            maybeRequestNotificationsPermission()
        }

        setContent {
            if (startupError != null) {
                MaterialTheme(colorScheme = HorologistColors) {
                    AppScaffold {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "应用启动错误：\n$startupError",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                WearAppRoot(container = app.container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = WristAmApplication.from(this)
        if (app.startupError == null) {
            app.container.authRepository.onExternalTokenReceived()
            if (MusicRepository.isInitialized()) {
                WearRadioPlayback.restore(applicationContext, MusicRepository.api)
            }
        }
    }

    private fun maybeRequestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private companion object {
        const val TAG = "WearLyricsActivity"
    }
}
