package com.applemusic.lyricstool.wear.data

import android.content.Context
import android.net.Uri
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.player.PlaybackEngine
import com.applemusic.android.util.Logx
import com.applemusic.lyricstool.data.AppleLyricsClient
import com.applemusic.lyricstool.wear.EncryptedTokenStore
import com.applemusic.lyricstool.wear.WearRadioPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AuthState(
    val signedIn: Boolean = false,
    val transferError: String? = null,
    val storefront: String = "cn"
)

/**
 * 账号与授权状态仓库：
 * - 响应式监听 [EncryptedTokenStore.tokenFlow]，手机端传输到达瞬间自动完成登录并同步区服；
 * - 完整退出登录：停止电台 + 停止清空播放队列 + 清空收藏缓存 + 清空资料库快照 + 撤销令牌。
 */
class AuthRepository(
    private val appContext: Context,
    private val appScope: CoroutineScope,
    private val tokenStore: EncryptedTokenStore,
    private val lyricsClient: AppleLyricsClient,
    private val favoritesRepository: FavoritesRepository
) {
    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        appScope.launch {
            tokenStore.tokenFlow.collect { snapshot ->
                lyricsClient.reloadSavedSession()
                if (snapshot.isSignedIn && MusicRepository.isInitialized()) {
                    try {
                        MusicRepository.api.persist(snapshot.token)
                    } catch (e: Exception) {
                        Logx.w(TAG, "同步令牌到 MusicRepository 失败：${e.message}")
                    }
                    refreshStorefrontInBackground()
                }
                _state.value = AuthState(
                    signedIn = snapshot.isSignedIn,
                    transferError = snapshot.transferError,
                    storefront = effectiveStorefront()
                )
            }
        }
    }

    private fun buildState(): AuthState {
        val snap = tokenStore.tokenFlow.value
        return AuthState(
            signedIn = snap.isSignedIn,
            transferError = snap.transferError,
            storefront = effectiveStorefront()
        )
    }

    private fun effectiveStorefront(): String {
        val apiSf = if (MusicRepository.isInitialized()) MusicRepository.api.storefront else ""
        return apiSf.ifBlank { lyricsClient.storefront }.ifBlank { "cn" }
    }

    /** 供 WearTokenListenerService 或 Activity.onResume 调用：立刻重新同步会话状态。 */
    fun onExternalTokenReceived() {
        tokenStore.refreshSnapshot()
        lyricsClient.reloadSavedSession()
        val token = tokenStore.load().orEmpty().trim()
        if (token.length >= EncryptedTokenStore.MIN_TOKEN_LENGTH && MusicRepository.isInitialized()) {
            try {
                MusicRepository.api.persist(token)
            } catch (e: Exception) {
                Logx.w(TAG, "外部令牌写入 AppleApi 失败：${e.message}")
            }
            refreshStorefrontInBackground()
        }
        _state.value = buildState()
    }

    suspend fun saveManualToken(rawToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = rawToken.trim()
            lyricsClient.saveUserToken(clean)
            if (MusicRepository.isInitialized()) {
                MusicRepository.api.persist(clean)
            }
            refreshStorefrontInBackground()
            _state.value = buildState()
        }
    }

    suspend fun importTokenFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = appContext.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText().trim() }
                ?: error("无法读取令牌文件")
            lyricsClient.saveUserToken(token)
            if (MusicRepository.isInitialized()) {
                MusicRepository.api.persist(token)
            }
            refreshStorefrontInBackground()
            _state.value = buildState()
        }
    }

    private fun refreshStorefrontInBackground() {
        appScope.launch(Dispatchers.IO) {
            try {
                if (MusicRepository.isInitialized() && MusicRepository.api.isAuthorized()) {
                    MusicRepository.api.getAccountInfo()
                    val sf = MusicRepository.api.effectiveStorefront()
                    lyricsClient.updateStorefront(sf)
                    _state.value = _state.value.copy(storefront = sf)
                } else if (lyricsClient.isSignedIn) {
                    lyricsClient.refreshStorefront()
                    _state.value = _state.value.copy(storefront = lyricsClient.storefront)
                }
            } catch (e: Exception) {
                Logx.w(TAG, "后台刷新区服失败：${e.message}")
            }
        }
    }

    /** 彻底退出登录，防止切换账号后残留上个账号的队列、电台、收藏和资料库。 */
    fun signOut() {
        try {
            WearRadioPlayback.stop(appContext)
        } catch (_: Throwable) {
        }
        try {
            PlaybackEngine.stopAndClearForLogout()
        } catch (_: Throwable) {
        }
        favoritesRepository.clearForLogout()
        if (MusicRepository.isInitialized()) {
            try {
                MusicRepository.clearForLogout()
            } catch (_: Throwable) {
            }
            try {
                MusicRepository.api.logout()
            } catch (_: Throwable) {
            }
        }
        lyricsClient.logout()
        tokenStore.clear()
        _state.value = AuthState(signedIn = false, transferError = null, storefront = "cn")
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
