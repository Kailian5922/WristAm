package com.applemusic.lyricstool.wear.data

import android.content.Context
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.apple.ShelfItem
import com.applemusic.android.util.Logx
import com.applemusic.lyricstool.wear.WearRadioPlayback
import com.applemusic.lyricstool.wear.startWearRadio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class PresetStation(
    val id: String,
    val title: String,
    val subtitle: String
)

data class RadioRecommendationsState(
    val stations: List<ShelfItem> = emptyList(),
    val loading: Boolean = false,
    val loadedOnce: Boolean = false
)

class RadioRepository(
    private val appContext: Context
) {
    val activeStationIdFlow: StateFlow<String> = WearRadioPlayback.activeStationIdFlow

    private val _recommendations = MutableStateFlow(RadioRecommendationsState())
    val recommendations: StateFlow<RadioRecommendationsState> = _recommendations.asStateFlow()

    val presetStations: List<PresetStation> = CorePresetStations

    suspend fun loadRecommendations(force: Boolean = false) {
        val current = _recommendations.value
        if (!force && (current.loading || current.loadedOnce)) return
        if (!MusicRepository.isInitialized() || !MusicRepository.api.isAuthorized()) return

        _recommendations.value = current.copy(loading = true)
        try {
            val items = withContext(Dispatchers.IO) {
                val shelves = MusicRepository.api.getRecommendations()
                val stationShelves = MusicRepository.api.stationsFrom(shelves)
                val out = ArrayList<ShelfItem>()
                val seen = HashSet<String>()
                for (shelf in stationShelves) {
                    for (item in shelf.items) {
                        if (item.isStation && seen.add(item.id)) {
                            out.add(item)
                        }
                    }
                }
                out
            }
            _recommendations.value = RadioRecommendationsState(
                stations = items,
                loading = false,
                loadedOnce = true
            )
        } catch (ce: CancellationException) {
            _recommendations.value = _recommendations.value.copy(loading = false)
            throw ce
        } catch (e: Exception) {
            Logx.w(TAG, "拉取推荐电台失败：${e.message}")
            _recommendations.value = _recommendations.value.copy(
                loading = false,
                loadedOnce = true
            )
        }
    }

    suspend fun startStation(stationId: String): Result<Int> {
        return try {
            val batch = startWearRadio(appContext, MusicRepository.api, stationId)
            Result.success(batch.rows.size)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "RadioRepository"

        val CorePresetStations = listOf(
            PresetStation("ra.u-personal", "我的个性电台", "根据你的音乐喜好量身定制"),
            PresetStation("ra.u-discovery", "新发现电台", "探索未曾听过的宝藏新歌"),
            PresetStation("ra.985499298", "华语流行电台", "经典与当红华语流行热歌"),
            PresetStation("ra.985499304", "粤语流行电台", "经典金曲与新生代粤语流行"),
            PresetStation("ra.985484166", "Apple Music 1", "全球旗舰流行音乐电台"),
            PresetStation("ra.1466858884", "Apple Music Hits", "80/90/00年代经典热门"),
            PresetStation("ra.1466858885", "Apple Music Country", "经典与现代乡村音乐"),
            PresetStation("ra.985499285", "轻松放松电台", "放空心绪，伴你惬意放松"),
            PresetStation("ra.985499278", "经典老歌电台", "跨越时代的黄金时代旋律"),
            PresetStation("ra.985499280", "运动能量电台", "律动节拍，为运动注入能量")
        )
    }
}
