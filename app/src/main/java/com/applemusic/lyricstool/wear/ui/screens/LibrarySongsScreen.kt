package com.applemusic.lyricstool.wear.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.lyricstool.data.SongHit
import com.applemusic.lyricstool.wear.data.toSongHit
import com.applemusic.lyricstool.wear.ui.components.WearBackHeader
import com.applemusic.lyricstool.wear.viewmodel.BrowseViewModel

@Composable
fun LibrarySongsScreen(
    browseViewModel: BrowseViewModel,
    onBack: () -> Unit,
    onPlaySong: (SongHit) -> Unit,
    onPlayQueue: (List<AppleLibrarySync.SongRow>, Int) -> Unit
) {
    val state by browseViewModel.uiState.collectAsStateWithLifecycle()
    val songs = state.librarySongs
    val listState = rememberTransformingLazyColumnState()
    var visibleLimit by remember { mutableIntStateOf(20) }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
        ) {
            item(key = "lib_header") {
                WearBackHeader(title = "资料库 (${songs.size})", onBack = onBack)
            }

            if (songs.isEmpty()) {
                item(key = "lib_empty") {
                    Text(
                        text = "资料库为空，可在上一页点击“全库刷新同步”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp)
                    )
                }
            } else {
                val displayed = songs.take(visibleLimit)
                itemsIndexed(
                    items = displayed,
                    key = { index, row -> "${row.uri.ifBlank { row.appleId }}_$index" }
                ) { index, row ->
                    Card(
                        onClick = {
                            // 传入完整资料库列表而非截断后的 10/20 首，防止队列被莫名截断
                            onPlayQueue(songs, index)
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
                                    text = row.artist,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            FilledIconButton(
                                onClick = { onPlaySong(row.toSongHit()) },
                                modifier = Modifier.size(34.dp)
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

                if (songs.size > visibleLimit) {
                    item(key = "lib_more") {
                        OutlinedButton(
                            onClick = { visibleLimit += 25 },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) {
                            Text("加载更多 (${songs.size - visibleLimit})")
                        }
                    }
                }
            }
        }
    }
}
