package com.applemusic.lyricstool.wear.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.applemusic.android.apple.AppleCache
import com.applemusic.lyricstool.wear.ui.components.HorologistIcons
import com.applemusic.lyricstool.wear.ui.components.WearBackHeader
import com.applemusic.lyricstool.wear.ui.components.WearConfirmDialog
import com.applemusic.lyricstool.wear.viewmodel.DownloadCategory
import com.applemusic.lyricstool.wear.viewmodel.DownloadsViewModel

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    onPlayed: () -> Unit,
    onToast: (String, Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    var pendingDelete by remember { mutableStateOf<AppleCache.Entry?>(null) }

    BackHandler(enabled = state.selectedArtist != null || state.selectedAlbum != null) {
        viewModel.navigateBackLevel()
    }

    val formatSize: (Long) -> String = { bytes ->
        if (bytes <= 0L) "0 MB"
        else "%.1f MB".format(bytes / (1024f * 1024f))
    }

    val headerTitle = when {
        state.selectedArtist != null -> state.selectedArtist!!
        state.selectedAlbum != null -> state.selectedAlbum!!
        else -> "已下载歌曲"
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
        ) {
            item(key = "dl_header") {
                WearBackHeader(
                    title = headerTitle,
                    onBack = {
                        if (!viewModel.navigateBackLevel()) onBack()
                    }
                )
            }

            if (state.selectedArtist == null && state.selectedAlbum == null) {
                item(key = "dl_tabs") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            DownloadCategory.All to "全部 (${state.entries.size})",
                            DownloadCategory.ByArtist to "按歌手",
                            DownloadCategory.ByAlbum to "按专辑"
                        ).forEach { (cat, label) ->
                            val isSelected = state.category == cat
                            FilledTonalButton(
                                onClick = { viewModel.selectCategory(cat) },
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .height(30.dp),
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
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // 正在下载中的实时任务卡片
                if (state.activeDownloads.isNotEmpty()) {
                    item(key = "dl_active_hdr") {
                        ListHeader(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "正在下载 (${state.activeDownloads.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    itemsIndexed(
                        items = state.activeDownloads,
                        key = { _, dl -> "active_${dl.song.id}" }
                    ) { _, dl ->
                        Card(
                            onClick = {},
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
                                        text = dl.song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${dl.stageLabel} · ${dl.percent}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Box(
                                    modifier = Modifier.size(30.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        progress = { (dl.percent / 100f).coerceIn(0.05f, 1f) },
                                        modifier = Modifier.fillMaxSize(),
                                        strokeWidth = 2.dp,
                                        colors = ProgressIndicatorDefaults.colors(
                                            indicatorColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                    Text(
                                        text = "${dl.percent}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.entries.isEmpty()) {
                item(key = "dl_empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = HorologistIcons.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "暂无已下载歌曲\n在搜索页可点击下载图标离线保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                when {
                    state.selectedArtist != null -> {
                        val artistSongs = state.entries.filter {
                            (it.artist.ifBlank { "未知歌手" }) == state.selectedArtist
                        }
                        item(key = "dl_artist_actions") {
                            DownloadPlayButtonGroup(
                                onPlayAll = {
                                    viewModel.playQueue(artistSongs, shuffle = false)
                                    onPlayed()
                                },
                                onShuffle = {
                                    viewModel.playQueue(artistSongs, shuffle = true)
                                    onPlayed()
                                }
                            )
                        }
                        itemsIndexed(
                            items = artistSongs,
                            key = { _, e -> "artist_song_${e.songId}" }
                        ) { _, entry ->
                            DownloadedSongItem(
                                entry = entry,
                                formatSize = formatSize,
                                onPlay = {
                                    viewModel.playEntry(entry)
                                    onPlayed()
                                },
                                onDelete = { pendingDelete = entry }
                            )
                        }
                    }
                    state.selectedAlbum != null -> {
                        val albumSongs = state.entries.filter {
                            (it.album.ifBlank { "未知专辑" }) == state.selectedAlbum
                        }
                        item(key = "dl_album_actions") {
                            DownloadPlayButtonGroup(
                                onPlayAll = {
                                    viewModel.playQueue(albumSongs, shuffle = false)
                                    onPlayed()
                                },
                                onShuffle = {
                                    viewModel.playQueue(albumSongs, shuffle = true)
                                    onPlayed()
                                }
                            )
                        }
                        itemsIndexed(
                            items = albumSongs,
                            key = { _, e -> "album_song_${e.songId}" }
                        ) { _, entry ->
                            DownloadedSongItem(
                                entry = entry,
                                formatSize = formatSize,
                                onPlay = {
                                    viewModel.playEntry(entry)
                                    onPlayed()
                                },
                                onDelete = { pendingDelete = entry }
                            )
                        }
                    }
                    state.category == DownloadCategory.ByArtist -> {
                        val artistGroups = state.entries.groupBy { it.artist.ifBlank { "未知歌手" } }.entries.toList()
                        itemsIndexed(
                            items = artistGroups,
                            key = { _, (artist, _) -> "grp_artist_$artist" }
                        ) { _, (artist, songs) ->
                            Card(
                                onClick = { viewModel.openArtist(artist) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = artist,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${songs.size} 首歌曲",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                    state.category == DownloadCategory.ByAlbum -> {
                        val albumGroups = state.entries.groupBy { it.album.ifBlank { "未知专辑" } }.entries.toList()
                        itemsIndexed(
                            items = albumGroups,
                            key = { _, (album, _) -> "grp_album_$album" }
                        ) { _, (album, songs) ->
                            Card(
                                onClick = { viewModel.openAlbum(album) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = album,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${songs.size} 首歌曲",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        item(key = "dl_all_actions") {
                            DownloadPlayButtonGroup(
                                onPlayAll = {
                                    viewModel.playQueue(state.entries, shuffle = false)
                                    onPlayed()
                                },
                                onShuffle = {
                                    viewModel.playQueue(state.entries, shuffle = true)
                                    onPlayed()
                                }
                            )
                        }
                        item(key = "dl_all_summary") {
                            ListHeader(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "全部离线 (${state.entries.size}首 · ${formatSize(state.totalBytes)})",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        itemsIndexed(
                            items = state.entries,
                            key = { _, e -> "all_song_${e.songId}" }
                        ) { _, entry ->
                            DownloadedSongItem(
                                entry = entry,
                                formatSize = formatSize,
                                onPlay = {
                                    viewModel.playEntry(entry)
                                    onPlayed()
                                },
                                onDelete = { pendingDelete = entry }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        val title = target.title.ifBlank { "该歌曲" }
        WearConfirmDialog(
            title = "删除离线歌曲",
            message = "确定删除《$title》的本地音频文件吗？",
            isDestructive = true,
            onConfirm = {
                pendingDelete = null
                viewModel.deleteDownload(target.songId, title, onToast)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun DownloadPlayButtonGroup(
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onPlayAll,
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
                Text("播放全部", style = MaterialTheme.typography.labelMedium)
            }
        }
        FilledTonalButton(
            onClick = onShuffle,
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

@Composable
private fun DownloadedSongItem(
    entry: AppleCache.Entry,
    formatSize: (Long) -> String,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onPlay,
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
                    text = entry.title.ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.artist.ifBlank { "未知歌手" }} · ${formatSize(entry.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalIconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除下载",
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            FilledIconButton(
                onClick = onPlay,
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
