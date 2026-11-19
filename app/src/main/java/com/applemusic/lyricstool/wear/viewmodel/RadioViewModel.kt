package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.android.apple.ShelfItem
import com.applemusic.lyricstool.wear.data.PresetStation
import com.applemusic.lyricstool.wear.data.RadioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RadioUiState(
    val recommendedStations: List<ShelfItem> = emptyList(),
    val presetStations: List<PresetStation> = RadioRepository.CorePresetStations,
    val loadingRecommendations: Boolean = false,
    val activeStationId: String = "",
    val startingStationId: String? = null
) {
    val isStarting: Boolean get() = startingStationId != null
}

class RadioViewModel(
    private val radioRepository: RadioRepository
) : ViewModel() {

    private val startingStationId = MutableStateFlow<String?>(null)
    private var startJob: Job? = null

    val uiState: StateFlow<RadioUiState> = combine(
        radioRepository.recommendations,
        radioRepository.activeStationIdFlow,
        startingStationId
    ) { rec, activeId, startingId ->
        val recIds = rec.stations.mapTo(HashSet()) { it.id }
        val presets = radioRepository.presetStations.filter { it.id !in recIds }
        RadioUiState(
            recommendedStations = rec.stations,
            presetStations = presets,
            loadingRecommendations = rec.loading,
            activeStationId = activeId,
            startingStationId = startingId
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadioUiState())

    init {
        viewModelScope.launch {
            radioRepository.loadRecommendations()
        }
    }

    fun startStation(
        stationId: String,
        stationTitle: String,
        onStarted: () -> Unit,
        onToast: (String, Boolean) -> Unit
    ) {
        if (startingStationId.value == stationId) return
        startJob?.cancel()
        startingStationId.value = stationId
        onToast("正在启动${stationTitle}…", false)

        startJob = viewModelScope.launch {
            try {
                val res = radioRepository.startStation(stationId)
                res.fold(
                    onSuccess = {
                        startingStationId.value = null
                        onStarted()
                    },
                    onFailure = { e ->
                        startingStationId.value = null
                        onToast("电台启动失败：${e.message ?: "网络超时"}", true)
                    }
                )
            } catch (ce: CancellationException) {
                if (startingStationId.value == stationId) {
                    startingStationId.value = null
                }
                throw ce
            }
        }
    }
}
