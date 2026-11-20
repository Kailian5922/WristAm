package com.applemusic.lyricstool.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import com.applemusic.lyricstool.wear.data.containsSong
import com.applemusic.lyricstool.wear.data.findFor
import com.applemusic.lyricstool.wear.ui.components.SongDownloadActionButton
import com.applemusic.lyricstool.wear.ui.components.WearBackHeader
import com.applemusic.lyricstool.wear.ui.components.WearDownloadLinearProgressBar
import com.applemusic.lyricstool.wear.viewmodel.SearchViewModel
import com.applemusic.lyricstool.wear.viewmodel.SearchedAlbum

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onPlayed: () -> Unit,
    onOpenArtist: (artistName: String, artistId: String) -> Unit,
    onOpenAlbum: (SearchedAlbum) -> Unit
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
            item(key = "search_header") {
                WearBackHeader(title = "搜索", onBack = onBack)
            }

            item(key = "search_input") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (state.searchByLyrics) "输入歌词片段" else "歌名/歌手/专辑") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.performSearch() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedContainerColor = Color(0xFF1E1E1E),
                        unfocusedContainerColor = Color(0xFF1E1E1E)
                    ),
                    singleLine = true
                )
            }

            item(key = "search_actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalButton(
                        onClick = viewModel::performSearch,
                        enabled = !state.searching && state.query.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.searching) "搜索中…" else "搜索")
                    }
                    OutlinedButton(
                        onClick = viewModel::toggleSearchByLyrics,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (state.searchByLyrics) "搜歌词:开" else "搜歌词:关",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (state.status.isNotBlank()) {
                item(key = "search_status") {
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.status.contains("失败")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }
            }

            if (!state.searchByLyrics && state.artists.isNotEmpty()) {
                item(key = "search_artists_header") {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("歌手", style = MaterialTheme.typography.labelMedium)
                    }
                }

                itemsIndexed(
                    items = state.artists,
                    key = { index, artist -> "artist_${artist.id}_${artist.name}_$index" }
                ) { _, artist ->
                    Card(
                        onClick = { onOpenArtist(artist.name, artist.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                                if (artist.artworkUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = artist.artworkUrl,
                                        contentDescription = artist.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = artist.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "进入歌手页",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (!state.searchByLyrics && state.albums.isNotEmpty()) {
                item(key = "search_albums_header") {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("专辑", style = MaterialTheme.typography.labelMedium)
                    }
                }

                itemsIndexed(
                    items = state.albums,
                    key = { index, album -> "album_${album.id}_${album.name}_$index" }
                ) { _, album ->
                    Card(
                        onClick = { onOpenAlbum(album) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Album,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                if (album.artworkUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = album.artworkUrl,
                                        contentDescription = album.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = album.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val yearPart = if (album.year > 0) "${album.year}年" else ""
                                val subtitle = listOf(album.artistName, yearPart)
                                    .filter(String::isNotBlank)
                                    .joinToString(" · ")
                                    .ifBlank { "专辑" }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "打开专辑",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (state.results.isNotEmpty() && !state.searchByLyrics &&
                (state.artists.isNotEmpty() || state.albums.isNotEmpty())
            ) {
                item(key = "search_songs_header") {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("歌曲", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            itemsIndexed(
                items = state.results,
                key = { index, song -> "${song.id}_$index" }
            ) { _, song ->
                val isDownloaded = state.downloadedIds.containsSong(song)
                val activeDownload = state.activeDownloads.findFor(song)

                Card(
                    onClick = {
                        viewModel.playSong(song)
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (activeDownload != null) {
                                        "${activeDownload.stageLabel} · ${activeDownload.percent.coerceIn(0, 100)}%"
                                    } else {
                                        song.artist
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (activeDownload != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val lyricSnippet = if (state.searchByLyrics && activeDownload == null) {
                                    state.lyricSnippets[song.id].orEmpty()
                                } else {
                                    ""
                                }
                                if (lyricSnippet.isNotBlank()) {
                                    Text(
                                        text = "「$lyricSnippet」",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            SongDownloadActionButton(
                                activeDownload = activeDownload,
                                isDownloaded = isDownloaded,
                                onDownload = { viewModel.downloadSong(song) }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            FilledIconButton(
                                onClick = {
                                    viewModel.playSong(song)
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
