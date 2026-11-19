package com.applemusic.lyricstool.wear.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import com.applemusic.lyricstool.wear.data.toSongHit
import com.applemusic.lyricstool.wear.ui.components.HorologistIcons
import com.applemusic.lyricstool.wear.ui.components.WearBackHeader
import com.applemusic.lyricstool.wear.viewmodel.ArtistViewModel

@Composable
fun ArtistDetailScreen(
    viewModel: ArtistViewModel,
    onClose: () -> Unit,
    onPlayed: () -> Unit,
    onOpenSimilarArtist: (String) -> Unit,
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
            item(key = "artist_nav") {
                WearBackHeader(title = "歌手", onBack = onClose)
            }

            if (state.parsedArtists.size > 1) {
                item(key = "artist_switcher") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        state.parsedArtists.forEach { artist ->
                            val isSelected = artist.equals(state.currentArtist, ignoreCase = true)
                            FilledTonalButton(
                                onClick = { viewModel.selectArtistTab(artist) },
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .height(32.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                    contentColor = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            ) {
                                Text(
                                    text = artist,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            item(key = "artist_hero") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val avatarUrl = state.artistPage?.artist?.artworkUrl?.ifBlank { null }
                        ?: state.localSongs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl
                    if (!avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = state.artistPage?.artist?.name ?: state.currentArtist,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val genre = state.artistPage?.artist?.genreNames?.take(2)?.joinToString(" / ")
                    if (!genre.isNullOrBlank()) {
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (state.isLoading) {
                item(key = "artist_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "正在载入歌手精选…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (state.errorMessage != null && state.localSongs.isEmpty()) {
                item(key = "artist_error") {
                    Text(
                        text = state.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                    )
                }
            }

            val allPlayable = if (state.topSongs.isNotEmpty()) {
                state.topSongs.map { it.toSongHit() }
            } else {
                state.localSongs.map { it.toSongHit() }
            }

            if (allPlayable.isNotEmpty()) {
                item(key = "artist_play_actions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                viewModel.playQueue(allPlayable, 0)
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
                                viewModel.playQueue(allPlayable.shuffled(), 0)
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

            // 热门歌曲（每页 5 首 + 显式点击加载更多，确保精选专辑与歌手简介始终容易滑动到达）
            if (state.topSongs.isNotEmpty()) {
                item(key = "artist_top_hdr") {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("热门歌曲 (${state.topSongs.size})", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(
                    items = state.topSongs,
                    key = { index, song -> "top_${song.id}_$index" }
                ) { index, song ->
                    val hit = song.toSongHit()
                    Card(
                        onClick = {
                            val hits = state.topSongs.map { it.toSongHit() }
                            viewModel.playQueue(hits, index)
                            onPlayed()
                        },
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
                            Text(
                                text = "%02d".format(index + 1),
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
                                if (song.albumName.isNotBlank()) {
                                    Text(
                                        text = song.albumName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            FilledIconButton(
                                onClick = {
                                    viewModel.playSong(hit)
                                    onPlayed()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "播放",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                if (state.hasMoreTopSongs) {
                    item(key = "artist_load_more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.loadingMoreTopSongs) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    colors = ProgressIndicatorDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.loadMoreTopSongs(onToast) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp)
                                ) {
                                    Text("点击加载更多 (5首)", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            // 精选专辑
            val albums = state.artistPage?.albums.orEmpty()
            if (albums.isNotEmpty()) {
                item(key = "artist_albums_hdr") {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("精选专辑 (${albums.size})", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(
                    items = albums.take(10),
                    key = { _, album -> "album_${album.id}" }
                ) { _, album ->
                    val isLoadingAlbum = state.loadingAlbumId == album.id
                    Card(
                        onClick = { viewModel.playAlbum(album, onPlayed, onToast) },
                        enabled = state.loadingAlbumId == null,
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
                            if (album.artworkUrl.isNotBlank()) {
                                AsyncImage(
                                    model = album.artworkUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = album.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val yearStr = if (album.year > 0) "${album.year}年" else ""
                                val trackStr = if (album.trackCount > 0) " · ${album.trackCount}首" else ""
                                val sub = "$yearStr$trackStr".trimStart(' ', '·')
                                if (sub.isNotBlank()) {
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (isLoadingAlbum) {
                                Spacer(modifier = Modifier.width(6.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    colors = ProgressIndicatorDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 资料库已有曲目（当目录热门为空时兜底展示）
            if (state.localSongs.isNotEmpty() && state.topSongs.isEmpty()) {
                item(key = "artist_local_hdr") {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("资料库曲目 (${state.localSongs.size})", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(
                    items = state.localSongs,
                    key = { index, row -> "local_${row.uri}_$index" }
                ) { index, row ->
                    val hit = row.toSongHit()
                    Card(
                        onClick = {
                            val hits = state.localSongs.map { it.toSongHit() }
                            viewModel.playQueue(hits, index)
                            onPlayed()
                        },
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = row.album,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            FilledIconButton(
                                onClick = {
                                    viewModel.playSong(hit)
                                    onPlayed()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "播放",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 歌手简介（已清理 HTML 标签与实体）
            if (state.cleanBio.isNotBlank()) {
                item(key = "artist_bio_hdr") {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("歌手简介", style = MaterialTheme.typography.labelMedium)
                    }
                }
                item(key = "artist_bio") {
                    Card(
                        onClick = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = state.cleanBio,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            // 相似艺人（压入导航栈，右滑可逐级回退）
            val similar = state.artistPage?.similarArtists.orEmpty()
            if (similar.isNotEmpty()) {
                item(key = "artist_sim_hdr") {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("相似艺人", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(
                    items = similar.take(6),
                    key = { _, sim -> "sim_${sim.id}_${sim.name}" }
                ) { _, simArtist ->
                    Card(
                        onClick = { onOpenSimilarArtist(simArtist.name) },
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
                            if (simArtist.artworkUrl.isNotBlank()) {
                                AsyncImage(
                                    model = simArtist.artworkUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = simArtist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
