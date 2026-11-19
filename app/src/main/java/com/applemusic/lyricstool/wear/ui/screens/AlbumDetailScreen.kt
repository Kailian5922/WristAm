package com.applemusic.lyricstool.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import com.applemusic.lyricstool.wear.data.containsSong
import com.applemusic.lyricstool.wear.data.findFor
import com.applemusic.lyricstool.wear.data.toSongHit
import com.applemusic.lyricstool.wear.ui.components.HorologistIcons
import com.applemusic.lyricstool.wear.ui.components.SongDownloadActionButton
import com.applemusic.lyricstool.wear.ui.components.WearBackHeader
import com.applemusic.lyricstool.wear.ui.components.WearDownloadLinearProgressBar
import com.applemusic.lyricstool.wear.viewmodel.AlbumDetailViewModel

@Composable
fun AlbumDetailScreen(
    viewModel: AlbumDetailViewModel,
    onBack: () -> Unit,
    onPlayed: () -> Unit,
    onOpenArtist: (artistName: String, artistId: String, songId: String, catalogId: String) -> Unit
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
            item(key = "album_nav") {
                WearBackHeader(title = "专辑", onBack = onBack)
            }

            item(key = "album_hero") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(30.dp)
                        )
                        if (state.artworkUrl.isNotBlank()) {
                            AsyncImage(
                                model = state.artworkUrl,
                                contentDescription = state.albumName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = state.albumName.ifBlank { "专辑" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.artistName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = state.artistName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    val firstTrack = state.tracks.firstOrNull()
                                    onOpenArtist(
                                        state.artistName,
                                        state.artistId,
                                        firstTrack?.id.orEmpty(),
                                        firstTrack?.catalogId.orEmpty()
                                    )
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    val yearStr = if (state.year > 0) "${state.year}年" else ""
                    val count = if (state.tracks.isNotEmpty()) state.tracks.size else state.trackCount
                    val countStr = if (count > 0) "${count}首" else ""
                    val meta = listOf(yearStr, countStr).filter(String::isNotBlank).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (state.tracks.isNotEmpty()) {
                item(key = "album_play_actions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                viewModel.playAll(shuffle = false)
                                onPlayed()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("播放", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                viewModel.playAll(shuffle = true)
                                onPlayed()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = HorologistIcons.Shuffle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("随机", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            when {
                state.isLoading && state.tracks.isEmpty() -> {
                    item(key = "album_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "正在加载专辑曲目…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                state.errorMessage != null && state.tracks.isEmpty() -> {
                    item(key = "album_error") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        ) {
                            Text(
                                text = state.errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(onClick = viewModel::loadAlbum) {
                                Text("重试")
                            }
                        }
                    }
                }
                state.tracks.isEmpty() -> {
                    item(key = "album_empty") {
                        Text(
                            text = "该专辑暂无可播放曲目",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        )
                    }
                }
                else -> {
                    item(key = "album_tracks_hdr") {
                        ListHeader(modifier = Modifier.fillMaxWidth()) {
                            Text("曲目 (${state.tracks.size})", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    itemsIndexed(
                        items = state.tracks,
                        key = { index, song -> "alb_trk_${song.id}_$index" }
                    ) { index, song ->
                        val hit = song.toSongHit()
                        val isDownloaded = state.downloadedIds.containsSong(hit)
                        val activeDownload = state.activeDownloads.findFor(hit)
                        val trackNum = if (song.trackNumber > 0) song.trackNumber else index + 1

                        Card(
                            onClick = {
                                viewModel.playTrackAt(index)
                                onPlayed()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "%02d".format(trackNum),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(22.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val subText = if (activeDownload != null) {
                                            "${activeDownload.stageLabel} · ${activeDownload.percent.coerceIn(0, 100)}%"
                                        } else if (song.artistName.isNotBlank() &&
                                            !song.artistName.equals(state.artistName, ignoreCase = true)
                                        ) {
                                            song.artistName
                                        } else {
                                            ""
                                        }
                                        if (subText.isNotBlank()) {
                                            Text(
                                                text = subText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (activeDownload != null) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    SongDownloadActionButton(
                                        activeDownload = activeDownload,
                                        isDownloaded = isDownloaded,
                                        onDownload = { viewModel.downloadSong(hit) },
                                        buttonSize = 30.dp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    FilledIconButton(
                                        onClick = {
                                            viewModel.playTrackAt(index)
                                            onPlayed()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "播放",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (activeDownload != null) {
                                    WearDownloadLinearProgressBar(
                                        percent = activeDownload.percent,
                                        barHeight = 3.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
