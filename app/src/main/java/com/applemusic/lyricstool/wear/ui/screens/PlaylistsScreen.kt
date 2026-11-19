package com.applemusic.lyricstool.wear.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.lyricstool.wear.ui.components.WearBackHeader
import com.applemusic.lyricstool.wear.viewmodel.BrowseViewModel

@Composable
fun PlaylistsScreen(
    browseViewModel: BrowseViewModel,
    onBack: () -> Unit,
    onSelectPlaylist: (AppleLibrarySync.PlaylistRow) -> Unit
) {
    val state by browseViewModel.uiState.collectAsStateWithLifecycle()
    val playlists = state.playlists
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
        ) {
            item(key = "pl_header") {
                WearBackHeader(title = "我的歌单 (${playlists.size})", onBack = onBack)
            }

            if (playlists.isEmpty()) {
                item(key = "pl_empty") {
                    Text(
                        text = "暂无歌单，可在上一页点击“全库刷新同步”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp)
                    )
                }
            } else {
                itemsIndexed(
                    items = playlists,
                    key = { index, pl -> "${pl.id}_$index" }
                ) { _, playlist ->
                    Card(
                        onClick = { onSelectPlaylist(playlist) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "点击查看曲目",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
