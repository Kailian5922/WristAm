package com.applemusic.lyricstool.wear.ui.screens

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.applemusic.lyricstool.R
import com.applemusic.lyricstool.wear.ui.components.HorologistIcons
import com.applemusic.lyricstool.wear.ui.components.HorologistPlayPauseProgressButton
import com.applemusic.lyricstool.wear.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.ceil

private val Configuration.isLargeScreen: Boolean
    get() = screenHeightDp > 224

private fun Configuration.getScreenSizeInDpFromPercentage(percent: Float): Dp =
    ceil(screenHeightDp * percent / 100f).dp

/**
 * 严格保持 Google Horologist 播放器视觉布局：
 * - TopSection：跑马灯歌名 + 可点击进入歌手主页的强调色艺人名；
 * - MiddleSection：上一首 + 10-scallop 波浪形变进度播放/暂停按钮 + 下一首；
 * - BottomSection：缓冲状态 或 播放队列 & 收藏快捷按钮；
 * - 表冠旋转调节媒体音量 + 零多余重组的绘制期进度更新。
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    isVisible: Boolean,
    onOpenQueue: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onToast: (String, Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val context = LocalContext.current

    val middleSectionMinimumHeight = remember(configuration) {
        if (configuration.isLargeScreen) 84.dp else 78.dp
    }
    val topSectionTopPadding = remember(configuration) {
        configuration.getScreenSizeInDpFromPercentage(
            if (configuration.isLargeScreen) 10f else 8f
        )
    }
    val topSectionBottomPadding = remember(configuration) {
        if (configuration.isLargeScreen) 8.dp else 6.dp
    }
    val middleSectionHeight = remember(configuration) {
        max(middleSectionMinimumHeight, configuration.screenHeightDp.dp * 0.36f)
    }
    val topSectionHeight = remember(configuration) {
        max(66.dp, (configuration.screenHeightDp.dp - middleSectionHeight) / 2f)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isAppResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 进度值仅在绘制 lambda（progressProvider）中被读取，避免 PlayerScreen 高频重组
    val progressState = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(
        state.isPlaying,
        state.durationMs,
        state.currentSong?.id,
        state.seekRevision,
        isVisible,
        isAppResumed
    ) {
        fun computeProgress(): Float {
            val dur = viewModel.durationMs().takeIf { it > 0L } ?: state.durationMs
            if (dur <= 0L) return 0f
            return (viewModel.positionMs().toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        }
        progressState.floatValue = computeProgress()
        if (!isAppResumed || !isVisible || !state.isPlaying) return@LaunchedEffect

        while (true) {
            delay(500L)
            progressState.floatValue = computeProgress()
        }
    }

    // 播放器页表冠 / 表圈旋转调节媒体音量
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }

    ScreenScaffold {
        val focusRequester = rememberActiveFocusRequester()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { event ->
                    val am = audioManager ?: return@onRotaryScrollEvent false
                    val delta = if (event.verticalScrollPixels != 0f) {
                        event.verticalScrollPixels
                    } else {
                        event.horizontalScrollPixels
                    }
                    rotaryAccumulator += delta
                    val threshold = 48f
                    if (rotaryAccumulator >= threshold) {
                        rotaryAccumulator = 0f
                        am.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_SHOW_UI
                        )
                    } else if (rotaryAccumulator <= -threshold) {
                        rotaryAccumulator = 0f
                        am.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI
                        )
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Section (MediaInfoDisplay)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(topSectionHeight)
                        .padding(top = topSectionTopPadding, bottom = topSectionBottomPadding)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        val song = state.currentSong
                        Text(
                            text = song?.title ?: "未在播放",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(iterations = Int.MAX_VALUE, velocity = 28.dp)
                        )
                        val artist = song?.artist
                        if (!artist.isNullOrBlank()) {
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onOpenArtist(artist) }
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                                    .basicMarquee(iterations = Int.MAX_VALUE, velocity = 24.dp)
                            )
                        } else {
                            Text(
                                text = "请在右侧页面点播",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .basicMarquee(iterations = Int.MAX_VALUE, velocity = 24.dp)
                            )
                        }
                    }
                }

                // Middle Section (Prev / Scallop Play-Pause / Next)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(middleSectionHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = viewModel::prev,
                            modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.rounded_skip_previous_24),
                                contentDescription = "上一首",
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        HorologistPlayPauseProgressButton(
                            isPlaying = state.isPlaying,
                            isPreparing = state.isPreparing,
                            progressProvider = { progressState.floatValue },
                            onToggle = viewModel::togglePlayPause
                        )

                        FilledIconButton(
                            onClick = viewModel::next,
                            modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryDim,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.rounded_skip_next_24),
                                contentDescription = "下一首",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Bottom Section (Preparing note / Queue & Favorite)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val showNote = state.isPreparing ||
                        (!state.isPlaying && state.preparingNote.isNotBlank())
                    if (showNote) {
                        val note = state.preparingNote.ifBlank { "正在下载音频缓存…" }
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (note.startsWith("无法播放") || note.contains("失败")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalIconButton(
                                onClick = onOpenQueue,
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_baseline_queue_music_24),
                                    contentDescription = "播放列表",
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { viewModel.toggleFavorite(onToast) },
                                enabled = !state.favoritePending && state.currentSong != null,
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = if (state.isFavorited) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                    contentColor = if (state.isFavorited) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            ) {
                                if (state.favoritePending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        colors = ProgressIndicatorDefaults.colors(
                                            indicatorColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (state.isFavorited) {
                                            HorologistIcons.Favorite
                                        } else {
                                            HorologistIcons.FavoriteBorder
                                        },
                                        contentDescription = if (state.isFavorited) "已收藏" else "收藏",
                                        tint = if (state.isFavorited) {
                                            Color(0xFFFF4081)
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.size(22.dp)
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
