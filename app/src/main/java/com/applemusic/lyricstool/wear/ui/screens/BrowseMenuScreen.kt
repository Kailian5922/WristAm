package com.applemusic.lyricstool.wear.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.applemusic.lyricstool.R
import com.applemusic.lyricstool.wear.ui.components.HorologistIcons
import com.applemusic.lyricstool.wear.ui.components.WearConfirmDialog
import com.applemusic.lyricstool.wear.viewmodel.BrowseViewModel

@Composable
fun BrowseMenuScreen(
    viewModel: BrowseViewModel,
    onOpenRadio: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLibrarySongs: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDownloads: () -> Unit,
    onClearCache: () -> Unit,
    onSignOut: () -> Unit,
    onToast: (String, Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
        ) {
            item(key = "browse_header") {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "媒体库",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            item(key = "menu_radio") {
                FilledTonalButton(
                    onClick = onOpenRadio,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(HorologistIcons.Radio, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("个性电台", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item(key = "menu_queue") {
                FilledTonalButton(
                    onClick = onOpenQueue,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_baseline_queue_music_24),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("当前播放列表", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item(key = "menu_library") {
                FilledTonalButton(
                    onClick = onOpenLibrarySongs,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("资料库歌曲", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item(key = "menu_playlists") {
                FilledTonalButton(
                    onClick = onOpenPlaylists,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("我的歌单", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item(key = "menu_search") {
                FilledTonalButton(
                    onClick = onOpenSearch,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("在线搜索", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item(key = "menu_downloads") {
                FilledTonalButton(
                    onClick = onOpenDownloads,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            HorologistIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val label = when {
                            state.activeDownloadCount > 0 ->
                                "已下载 (${state.downloadedCount} · 下载中 ${state.activeDownloadCount})"
                            state.downloadedCount > 0 ->
                                "已下载歌曲 (${state.downloadedCount})"
                            else -> "已下载歌曲"
                        }
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item(key = "menu_sync") {
                OutlinedButton(
                    enabled = !state.syncing,
                    onClick = { viewModel.syncLibrary(onToast) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val text = if (state.syncing) {
                        state.syncNote.ifBlank { "同步中…" }
                    } else {
                        "全库刷新同步"
                    }
                    Text(text)
                }
            }

            item(key = "menu_clear_cache") {
                OutlinedButton(
                    onClick = { showClearCacheConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("清理临时缓存", style = MaterialTheme.typography.labelSmall)
                }
            }

            item(key = "menu_sign_out") {
                TextButton(
                    onClick = { showSignOutConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("退出账号", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showClearCacheConfirm) {
        WearConfirmDialog(
            title = "清理临时缓存",
            message = "将清理试听临时缓存，完整保留所有已下载的离线歌曲。",
            isDestructive = false,
            onConfirm = {
                showClearCacheConfirm = false
                onClearCache()
            },
            onDismiss = { showClearCacheConfirm = false }
        )
    }

    if (showSignOutConfirm) {
        WearConfirmDialog(
            title = "退出账号",
            message = "将停止当前播放并清除手表登录凭据与本地资料库快照。",
            isDestructive = true,
            onConfirm = {
                showSignOutConfirm = false
                onSignOut()
            },
            onDismiss = { showSignOutConfirm = false }
        )
    }
}
