package com.applemusic.lyricstool.wear.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import com.applemusic.lyricstool.wear.ui.components.HorologistIcons
import com.applemusic.lyricstool.wear.ui.components.WearBackHeader
import com.applemusic.lyricstool.wear.viewmodel.RadioViewModel

@Composable
fun RadioStationsScreen(
    viewModel: RadioViewModel,
    onBack: () -> Unit,
    onStarted: () -> Unit,
    onToast: (String, Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
        ) {
            item(key = "radio_header") {
                WearBackHeader(title = "个性电台", onBack = onBack)
            }

            if (state.isStarting) {
                item(key = "radio_starting") {
                    Text(
                        text = "正在启动电台…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            if (state.loadingRecommendations) {
                item(key = "radio_loading_rec") {
                    Text(
                        text = "加载推荐电台中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else if (state.recommendedStations.isNotEmpty()) {
                item(key = "radio_rec_hdr") {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("专属推荐", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(
                    items = state.recommendedStations,
                    key = { _, st -> "rec_${st.id}" }
                ) { _, station ->
                    val isPlaying = station.id == state.activeStationId
                    val isStarting = station.id == state.startingStationId
                    Card(
                        onClick = {
                            viewModel.startStation(station.id, station.title, onStarted, onToast)
                        },
                        enabled = !state.isStarting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (station.artwork.isNotBlank()) {
                                AsyncImage(
                                    model = station.artwork,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = station.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (station.subtitle.isNotBlank()) {
                                    Text(
                                        text = station.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (isPlaying) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "播放中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (isStarting) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "启动中…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            if (state.presetStations.isNotEmpty()) {
                item(key = "radio_preset_hdr") {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("精选风格", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(
                    items = state.presetStations,
                    key = { _, preset -> "preset_${preset.id}" }
                ) { _, preset ->
                    val isPlaying = preset.id == state.activeStationId
                    val isStarting = preset.id == state.startingStationId
                    Card(
                        onClick = {
                            viewModel.startStation(preset.id, preset.title, onStarted, onToast)
                        },
                        enabled = !state.isStarting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = HorologistIcons.Radio,
                                contentDescription = null,
                                tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = preset.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isPlaying) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "播放中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (isStarting) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "启动中…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
