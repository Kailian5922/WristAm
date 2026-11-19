package com.applemusic.lyricstool.wear.di

import android.content.Context
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.player.PlaybackEngine
import com.applemusic.lyricstool.data.AppleLyricsClient
import com.applemusic.lyricstool.data.LyricsCache
import com.applemusic.lyricstool.data.QQMusicLyricsClient
import com.applemusic.lyricstool.wear.EncryptedTokenStore
import com.applemusic.lyricstool.wear.WearAppleApiTokenStore
import com.applemusic.lyricstool.wear.WearMusicService
import com.applemusic.lyricstool.wear.data.ArtistRepository
import com.applemusic.lyricstool.wear.data.AuthRepository
import com.applemusic.lyricstool.wear.data.DownloadRepository
import com.applemusic.lyricstool.wear.data.FavoritesRepository
import com.applemusic.lyricstool.wear.data.LibraryRepository
import com.applemusic.lyricstool.wear.data.LyricsRepository
import com.applemusic.lyricstool.wear.data.PlaybackRepository
import com.applemusic.lyricstool.wear.data.RadioRepository
import com.applemusic.lyricstool.wear.data.SleepTimerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 轻量级手写依赖注入容器（Application 生命周期单例）：
 * 统一装配安全存储、网络客户端与全部数据仓库，避免在 Composable 内散落创建有状态对象。
 */
class WearAppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val tokenStore: EncryptedTokenStore = EncryptedTokenStore(appContext)
    val appleLyricsClient: AppleLyricsClient = AppleLyricsClient(appContext, tokenStore)
    val qqMusicClient: QQMusicLyricsClient = QQMusicLyricsClient()
    val lyricsCache: LyricsCache = LyricsCache(appContext)

    init {
        if (!MusicRepository.isInitialized()) {
            MusicRepository.init(
                appContext,
                WearAppleApiTokenStore(appContext, tokenStore)
            )
        }
        // 仅在存在有效令牌时同步，绝不用空串覆写以保护 KEY_TRANSFER_ERROR
        val savedToken = appleLyricsClient.userToken
        if (savedToken.length >= EncryptedTokenStore.MIN_TOKEN_LENGTH) {
            try {
                MusicRepository.api.persist(savedToken)
            } catch (_: Exception) {
            }
        }
        PlaybackEngine.init(appContext, WearMusicService::class.java)
    }

    val favoritesRepository: FavoritesRepository = FavoritesRepository(appContext, appScope)
    val authRepository: AuthRepository = AuthRepository(
        appContext = appContext,
        appScope = appScope,
        tokenStore = tokenStore,
        lyricsClient = appleLyricsClient,
        favoritesRepository = favoritesRepository
    )
    val lyricsRepository: LyricsRepository = LyricsRepository(
        appScope = appScope,
        appleLyricsClient = appleLyricsClient,
        qqMusicClient = qqMusicClient,
        lyricsCache = lyricsCache
    )
    val playbackRepository: PlaybackRepository = PlaybackRepository(
        appContext = appContext,
        lyricsRepository = lyricsRepository
    )
    val sleepTimerRepository: SleepTimerRepository = SleepTimerRepository(
        appScope = appScope,
        playbackRepository = playbackRepository
    )
    val downloadRepository: DownloadRepository = DownloadRepository(
        appContext = appContext,
        appScope = appScope,
        lyricsCache = lyricsCache
    )
    val libraryRepository: LibraryRepository = LibraryRepository()
    val radioRepository: RadioRepository = RadioRepository(appContext)
    val artistRepository: ArtistRepository = ArtistRepository()
}
