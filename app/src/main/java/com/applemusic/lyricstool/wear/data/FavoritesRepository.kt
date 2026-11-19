package com.applemusic.lyricstool.wear.data

import android.content.Context
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.util.Logx
import com.applemusic.lyricstool.data.SongHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FavoritesState(
    val favoritedCatalogIds: Set<String> = emptySet(),
    val pendingSongId: String? = null
)

/**
 * 收藏歌曲仓库：
 * - 修复空集合不刷新本地缓存的问题（仅网络异常时保留旧缓存）；
 * - 精准匹配 `pendingSongId == currentCatalogId` 防止误锁其他歌曲按钮；
 * - 支持将资料库 `i.xxx` 歌曲解析为 catalogId 以正确展示和切换收藏态。
 */
class FavoritesRepository(
    appContext: Context,
    private val appScope: CoroutineScope
) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        FavoritesState(
            favoritedCatalogIds = prefs.getStringSet(KEY_IDS, emptySet())?.toSet().orEmpty()
        )
    )
    val state: StateFlow<FavoritesState> = _state.asStateFlow()

    fun refreshFromServer() {
        if (!MusicRepository.isInitialized() || !MusicRepository.api.isAuthorized()) return
        appScope.launch(Dispatchers.IO) {
            try {
                val ids = MusicRepository.api.getFavoriteCatalogSongIds()
                // 服务端明确成功返回（哪怕为空集）即同步覆盖本地缓存
                prefs.edit().putStringSet(KEY_IDS, ids).apply()
                _state.value = _state.value.copy(favoritedCatalogIds = ids)
            } catch (e: Exception) {
                Logx.w(TAG, "获取收藏歌曲列表失败：${e.message}")
            }
        }
    }

    /** 若歌曲只有资料库 ID（i.xxx），解析其 catalogId 并回写到 MusicRepository。 */
    suspend fun ensureCatalogIdResolved(song: SongHit): String = withContext(Dispatchers.IO) {
        val direct = song.resolveFavoriteCatalogId()
        if (direct.isNotBlank()) return@withContext direct

        val cleanId = song.id.removePrefix("apple:").trim()
        if (!cleanId.startsWith("i.") || !MusicRepository.isInitialized()) return@withContext ""
        try {
            val resolved = MusicRepository.api.librarySongCatalogId(cleanId)
            if (resolved.isNotBlank()) {
                val row = MusicRepository.findSong("apple:$cleanId")
                    ?: MusicRepository.findSong(cleanId)
                    ?: song.toSongRow()
                row.catalogId = resolved
                MusicRepository.registerTransient(listOf(row))
            }
            resolved
        } catch (e: Exception) {
            Logx.w(TAG, "解析资料库歌曲 catalogId 失败 ($cleanId)：${e.message}")
            ""
        }
    }

    suspend fun toggleFavorite(song: SongHit): Result<Boolean> {
        val catalogId = ensureCatalogIdResolved(song)
        if (catalogId.isBlank()) {
            return Result.failure(IllegalStateException("无法确定歌曲曲库 ID"))
        }
        val current = _state.value
        if (current.pendingSongId == catalogId) {
            return Result.failure(IllegalStateException("正在处理中，请稍候"))
        }
        val nextFav = catalogId !in current.favoritedCatalogIds
        _state.value = current.copy(pendingSongId = catalogId)
        return try {
            withContext(Dispatchers.IO) {
                MusicRepository.api.setCatalogSongFavorite(catalogId, nextFav)
                if (nextFav) {
                    try {
                        MusicRepository.api.addCatalogSongToLibrary(catalogId)
                    } catch (_: Exception) {
                    }
                }
            }
            val updated = if (nextFav) {
                _state.value.favoritedCatalogIds + catalogId
            } else {
                _state.value.favoritedCatalogIds - catalogId
            }
            prefs.edit().putStringSet(KEY_IDS, updated).apply()
            _state.value = _state.value.copy(
                favoritedCatalogIds = updated,
                pendingSongId = null
            )
            Result.success(nextFav)
        } catch (e: Exception) {
            _state.value = _state.value.copy(pendingSongId = null)
            Result.failure(e)
        }
    }

    fun clearForLogout() {
        prefs.edit().clear().apply()
        _state.value = FavoritesState()
    }

    private companion object {
        const val TAG = "FavoritesRepository"
        const val PREFS_NAME = "favorite_songs_cache"
        const val KEY_IDS = "song_ids"
    }
}
