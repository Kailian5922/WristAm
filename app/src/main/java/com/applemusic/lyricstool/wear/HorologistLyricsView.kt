package com.applemusic.lyricstool.wear

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.applemusic.android.lyrics.LyricLine
import com.applemusic.android.lyrics.Lyrics
import com.applemusic.android.player.PlaybackEngine
import com.applemusic.lyricstool.R
import com.applemusic.lyricstool.data.SongHit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 字体定义：优先使用 Wear OS 定制的 Outfit 和 Plus Jakarta Sans 字体。
 */
val OutfitFontFamily = FontFamily(
    Font(R.font.outfit, FontWeight.Normal),
    Font(R.font.outfit, FontWeight.Medium),
    Font(R.font.outfit, FontWeight.Bold),
    Font(R.font.outfit, FontWeight.ExtraBold)
)

val PlusJakartaSansFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans, FontWeight.ExtraBold)
)

/**
 * 严格单调平滑时间发生器（彻底杜绝时间回退与跳针抖动）：
 * 1. 采用本地单调时钟线性推进，在正常播放期间绝不允许毫秒时间戳出现回退；
 * 2. 只有当用户显式点击进度条/跳转（时钟差距 > 1200ms）才执行重新定锚；
 * 3. 33ms 适度帧率对 Wear OS 低功耗 CPU 极其温和，稳帧且不发热。
 */
@Composable
fun rememberSmoothPlaybackPositionMs(
    isPlaying: Boolean,
    isAppResumed: Boolean,
    isVisible: Boolean = true,
    playbackKey: String? = null
): State<Long> {
    val positionState = remember { mutableLongStateOf(PlaybackEngine.positionMs()) }

    LaunchedEffect(isPlaying, isAppResumed, isVisible, playbackKey) {
        if (!isAppResumed) return@LaunchedEffect
        if (!isPlaying || !isVisible) {
            positionState.longValue = PlaybackEngine.positionMs()
            return@LaunchedEffect
        }

        var baseExoPos = PlaybackEngine.positionMs()
        var baseRealtime = SystemClock.elapsedRealtime()
        var lastMaxReported = baseExoPos
        positionState.longValue = baseExoPos

        while (true) {
            delay(33L)
            val now = SystemClock.elapsedRealtime()
            val rawExoPos = PlaybackEngine.positionMs()

            val elapsed = now - baseRealtime
            val estimatedPos = baseExoPos + elapsed

            if (abs(rawExoPos - estimatedPos) > 1200L) {
                baseExoPos = rawExoPos
                baseRealtime = now
                lastMaxReported = rawExoPos
                positionState.longValue = rawExoPos
            } else {
                val monotonicPos = maxOf(lastMaxReported, estimatedPos)
                lastMaxReported = monotonicPos
                positionState.longValue = monotonicPos

                if (elapsed >= 600L) {
                    baseExoPos = maxOf(rawExoPos, lastMaxReported)
                    baseRealtime = now
                }
            }
        }
    }

    return positionState
}

/**
 * 判断是否为纯音乐/间奏（••• 或 [间奏]）。
 */
val LyricLine.isInterlude: Boolean
    get() = text.isBlank() || text == "•••" || text == "..." || text.equals("[间奏]", ignoreCase = true)

/**
 * 获取当前行进度（0f..1f）。
 */
fun LyricLine.lineProgress(positionMs: Long): Float {
    if (endMs <= startMs) return if (positionMs >= startMs) 1f else 0f
    return ((positionMs - startMs).toFloat() / (endMs - startMs).toFloat()).coerceIn(0f, 1f)
}

/**
 * 计算行内每个字符的精确 (startMs, endMs) 时间跨度，供 Apple Music 单字平滑扫光。
 */
fun LyricLine.computeCharTimings(): List<Pair<Long, Long>> {
    if (text.isEmpty()) return emptyList()
    val result = ArrayList<Pair<Long, Long>>(text.length)
    if (words.isNotEmpty()) {
        val array = arrayOfNulls<Pair<Long, Long>>(text.length)
        for (w in words) {
            val cs = w.charStart.coerceIn(0, text.length)
            val ce = w.charEnd.coerceIn(cs, text.length)
            val len = ce - cs
            if (len == 1) {
                array[cs] = w.startMs to w.endMs.coerceAtLeast(w.startMs + 1L)
            } else if (len > 1) {
                val span = (w.endMs - w.startMs).coerceAtLeast(len.toLong())
                for (i in 0 until len) {
                    val cStart = w.startMs + (span * i) / len
                    val cEnd = if (i == len - 1) w.endMs else w.startMs + (span * (i + 1)) / len
                    array[cs + i] = cStart to cEnd.coerceAtLeast(cStart + 1L)
                }
            }
        }
        var lastEnd = startMs
        for (i in text.indices) {
            val timing = array[i]
            if (timing != null) {
                result.add(timing)
                lastEnd = timing.second
            } else {
                result.add(lastEnd to (lastEnd + 20L))
            }
        }
        return result
    }

    val effectiveEnd = (endMs - 250L).coerceAtLeast(startMs + 400L)
    val totalSpan = (effectiveEnd - startMs).coerceAtLeast(text.length.toLong())
    for (i in text.indices) {
        val cStart = startMs + (totalSpan * i) / text.length
        val cEnd = if (i == text.lastIndex) effectiveEnd else startMs + (totalSpan * (i + 1)) / text.length
        result.add(cStart to cEnd.coerceAtLeast(cStart + 1L))
    }
    return result
}

/**
 * 歌词页滚动控制器：
 * 精准将正在演唱的歌词行滚动至表盘视觉光学黄金中心点（视口 45% 高度）。
 */
@Stable
class WearLyricsPageState(
    val listState: LazyListState
) {
    var activeItemHeightPx by mutableIntStateOf(140)
        internal set

    suspend fun smoothCenterOnLine(activeIndex: Int, totalCount: Int) {
        if (totalCount <= 0 || activeIndex !in 0 until totalCount) return

        withFrameNanos { }

        val layoutInfo = listState.layoutInfo
        val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }

        if (visibleItem != null) {
            val viewportStart = layoutInfo.viewportStartOffset.toFloat()
            val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
            val viewportHeight = viewportEnd - viewportStart
            val targetCenter = viewportStart + (viewportHeight * 0.45f)
            val itemCenter = visibleItem.offset + (visibleItem.size / 2f)
            val scrollDelta = itemCenter - targetCenter

            if (abs(scrollDelta) > 1f) {
                listState.animateScrollBy(
                    value = scrollDelta,
                    animationSpec = tween(
                        durationMillis = 400,
                        easing = FastOutSlowInEasing
                    )
                )
            }
        } else {
            val positiveCenterOffset = (activeItemHeightPx * 0.65f).toInt()
            listState.scrollToItem(
                index = activeIndex,
                scrollOffset = positiveCenterOffset
            )
            withFrameNanos { }
            val updatedLayout = listState.layoutInfo
            val nowVisible = updatedLayout.visibleItemsInfo.firstOrNull { it.index == activeIndex }
            if (nowVisible != null) {
                val viewportStart = updatedLayout.viewportStartOffset.toFloat()
                val viewportEnd = updatedLayout.viewportEndOffset.toFloat()
                val viewportHeight = viewportEnd - viewportStart
                val targetCenter = viewportStart + (viewportHeight * 0.45f)
                val itemCenter = nowVisible.offset + (nowVisible.size / 2f)
                val scrollDelta = itemCenter - targetCenter
                if (abs(scrollDelta) > 1f) {
                    listState.animateScrollBy(
                        value = scrollDelta,
                        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                    )
                }
            }
        }
    }
}

@Composable
fun rememberWearLyricsPageState(
    initialFirstVisibleItemIndex: Int = 0
): WearLyricsPageState {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialFirstVisibleItemIndex)
    return remember(listState) {
        WearLyricsPageState(listState)
    }
}

/**
 * Apple Music 风格 3 颗呼吸间奏跳动圆点 (`• • •`)。
 */
@Composable
private fun AppleMusicInterludeDots(
    isActive: Boolean,
    progress: Float,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "interludePulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotScale"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        repeat(3) { dotIndex ->
            val dotThreshold = dotIndex / 3f
            val dotFill = if (!isActive) {
                0.35f
            } else {
                ((progress - dotThreshold) * 3f).coerceIn(0.25f, 1f)
            }
            val scale = if (isActive) pulse else 1f
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(
                        if (isActive) accentColor.copy(alpha = dotFill)
                        else Color.White.copy(alpha = dotFill)
                    )
            )
        }
    }
}

/**
 * Apple Music 逐字/音节平滑扫光文字组件：
 * 采用 GPU 硬件加速剪裁，单个字符从左向右（charLeft -> charRight）连续羽化填入强调色（accentColor）。
 */
@Composable
private fun AppleMusicKaraokeSweepText(
    line: LyricLine,
    currentPositionMs: Long,
    baseFontSize: TextUnit,
    lineHeight: TextUnit,
    fontFamily: FontFamily,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val charTimings = remember(line.text, line.startMs) { line.computeCharTimings() }
    var textLayoutResult by remember(line.text, baseFontSize) {
        mutableStateOf<TextLayoutResult?>(null)
    }

    val sharedTextStyle = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = baseFontSize,
        lineHeight = lineHeight,
        letterSpacing = (-0.2).sp,
        textAlign = TextAlign.Center
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 1. 底层：未唱到部分的暗调强调色文字
        Text(
            text = line.text,
            textAlign = TextAlign.Center,
            style = sharedTextStyle.copy(
                color = accentColor.copy(alpha = 0.35f)
            ),
            onTextLayout = { textLayoutResult = it },
            modifier = Modifier.fillMaxWidth()
        )

        // 2. 顶层：正在唱的高亮强调色扫光层，通过 BlendMode.DstIn 实现字内连续渐变羽化剪裁
        Text(
            text = line.text,
            textAlign = TextAlign.Center,
            style = sharedTextStyle.copy(
                color = accentColor
            ),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    val layout = textLayoutResult
                    if (layout == null || charTimings.isEmpty() || size.width <= 0f) {
                        return@drawWithContent
                    }

                    drawContent()

                    val totalLines = layout.lineCount
                    val textLen = line.text.length

                    for (lineIdx in 0 until totalLines) {
                        val stripTop = if (lineIdx == 0) {
                            -16f
                        } else {
                            (layout.getLineBottom(lineIdx - 1) + layout.getLineTop(lineIdx)) * 0.5f
                        }
                        val stripBottom = if (lineIdx == totalLines - 1) {
                            size.height + 16f
                        } else {
                            (layout.getLineBottom(lineIdx) + layout.getLineTop(lineIdx + 1)) * 0.5f
                        }
                        val stripHeight = (stripBottom - stripTop).coerceAtLeast(1f)

                        val rawStartChar = layout.getLineStart(lineIdx)
                            .coerceIn(0, (textLen - 1).coerceAtLeast(0))
                        val rawEndExclusive = layout.getLineEnd(lineIdx, visibleEnd = true)
                            .coerceIn(rawStartChar + 1, textLen)
                        val lastCharOnLine = (rawEndExclusive - 1).coerceAtLeast(rawStartChar)

                        if (rawStartChar !in charTimings.indices || lastCharOnLine !in charTimings.indices) {
                            continue
                        }

                        val lineStartMs = charTimings[rawStartChar].first
                        val lineEndMs = charTimings[lastCharOnLine].second

                        when {
                            currentPositionMs <= lineStartMs -> {
                                drawRect(
                                    color = Color.Transparent,
                                    topLeft = Offset(0f, stripTop),
                                    size = Size(size.width, stripHeight),
                                    blendMode = BlendMode.DstIn
                                )
                            }

                            currentPositionMs >= lineEndMs -> {
                                // 已经唱完的行 -> 100% 保持强调色高亮
                            }

                            else -> {
                                var activeChar = rawStartChar
                                for (c in rawStartChar..lastCharOnLine) {
                                    val (_, cEnd) = charTimings[c]
                                    if (currentPositionMs < cEnd) {
                                        activeChar = c
                                        break
                                    }
                                    activeChar = c
                                }

                                val (cStart, cEnd) = charTimings[activeChar]
                                val charProgress = ((currentPositionMs - cStart).toFloat() /
                                    (cEnd - cStart).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

                                val charBox = layout.getBoundingBox(activeChar)
                                val charLeft = charBox.left
                                val charWidth = (charBox.right - charBox.left).coerceAtLeast(2f)

                                val sweepX = charLeft + (charWidth * charProgress)
                                val featherPx = (charWidth * 0.24f).coerceIn(3f, 10f)

                                val solidStop = ((sweepX - featherPx * 0.5f) / size.width).coerceIn(0f, 1f)
                                val transparentStop = ((sweepX + featherPx) / size.width).coerceIn(
                                    (solidStop + 0.002f).coerceAtMost(1f),
                                    1f
                                )

                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0.0f to Color.Black,
                                        solidStop to Color.Black,
                                        transparentStop to Color.Transparent,
                                        1.0f to Color.Transparent,
                                        startX = 0f,
                                        endX = size.width
                                    ),
                                    topLeft = Offset(0f, stripTop),
                                    size = Size(size.width, stripHeight),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                        }
                    }
                }
        )
    }
}

/**
 * 单行歌词 Item：
 * - 紧凑行间距与动态展开（当前行 3.dp 内边距，非当前行 1.dp）
 * - 动态 Spring 弹性缩放（当前行 1.0f，非当前行 0.88f）
 * - 距离渐变透明度与强调色区分
 */
@Composable
private fun AppleMusicLyricLineItem(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    distanceFromActive: Int,
    currentPositionMs: Long,
    fontScaleFactor: Float,
    fontFamily: FontFamily,
    showTranslation: Boolean,
    isUserBrowsingLyrics: Boolean,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onActiveItemMeasured: (Int) -> Unit,
    onClick: () -> Unit
) {
    val targetScale = if (isActive) 1.0f else 0.88f
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "lyricScale"
    )

    val targetAlpha = when {
        isActive -> 1.0f
        distanceFromActive == 1 -> if (isPast) 0.40f else 0.48f
        distanceFromActive == 2 -> 0.25f
        else -> 0.14f
    }
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "lyricAlpha"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                if (isActive) {
                    onActiveItemMeasured(size.height)
                }
            }
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                alpha = if (isUserBrowsingLyrics) 1f else animatedAlpha
                transformOrigin = TransformOrigin.Center
            }
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = accentColor),
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = if (isActive) 3.dp else 1.dp)
    ) {
        if (line.isInterlude) {
            AppleMusicInterludeDots(
                isActive = isActive,
                progress = line.lineProgress(currentPositionMs),
                accentColor = accentColor
            )
        } else {
            val baseFontSize = (18.0f * fontScaleFactor).sp
            val lineHeight = (23.5f * fontScaleFactor).sp

            if (isActive) {
                AppleMusicKaraokeSweepText(
                    line = line,
                    currentPositionMs = currentPositionMs,
                    baseFontSize = baseFontSize,
                    lineHeight = lineHeight,
                    fontFamily = fontFamily,
                    accentColor = accentColor
                )
            } else {
                Text(
                    text = line.text,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontFamily = fontFamily,
                        fontWeight = if (isPast) FontWeight.Bold else FontWeight.Medium,
                        fontSize = baseFontSize,
                        lineHeight = lineHeight,
                        letterSpacing = (-0.2).sp,
                        color = if (isUserBrowsingLyrics) {
                            Color.White
                        } else if (isPast) {
                            accentColor.copy(alpha = (animatedAlpha * 0.9f).coerceIn(0.25f, 0.65f))
                        } else {
                            Color.White.copy(alpha = animatedAlpha)
                        },
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val translation = line.translation
            if (showTranslation && !translation.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = translation,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontFamily = fontFamily,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = (12.0f * fontScaleFactor).sp,
                        lineHeight = (16.0f * fontScaleFactor).sp,
                        textAlign = TextAlign.Center,
                        color = if (isActive) {
                            accentColor.copy(alpha = 0.90f)
                        } else if (isUserBrowsingLyrics) {
                            Color.White.copy(alpha = 0.90f)
                        } else {
                            Color.White.copy(alpha = (animatedAlpha - 0.08f).coerceAtLeast(0.20f))
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 核心独立歌词滚动视图（WearLyricsPage）：
 * - 纯透明容器，完美展现底层动态高斯模糊封面背景；
 * - 紧凑行间距（Arrangement.spacedBy(4.dp)）；
 * - 顶部与底部对称羽化边缘（BlendMode.DstIn），实现歌词滑入滑出的丝滑暗角；
 * - 垂向精确居中于 45% 光学黄金中心。
 */
@Composable
fun WearLyricsPage(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    activeLineIndex: Int,
    isUserBrowsingLyrics: Boolean = false,
    showTranslation: Boolean = true,
    fontScaleFactor: Float = 1.15f,
    fontFamily: FontFamily = PlusJakartaSansFontFamily,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onLyricLineClick: (LyricLine) -> Unit = {},
    onUserManualScroll: () -> Unit = {},
    modifier: Modifier = Modifier,
    pageState: WearLyricsPageState = rememberWearLyricsPageState(initialFirstVisibleItemIndex = activeLineIndex)
) {
    val isDragged by pageState.listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(isDragged) {
        if (isDragged) {
            onUserManualScroll()
        }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize()
    ) {
        val viewportHeightDp = maxHeight
        val halfScreenVerticalPadding = (viewportHeightDp * 0.45f).coerceAtLeast(100.dp)

        LaunchedEffect(
            activeLineIndex,
            lyrics,
            isUserBrowsingLyrics,
            viewportHeightDp,
            fontScaleFactor,
            showTranslation
        ) {
            if (!isUserBrowsingLyrics && lyrics.isNotEmpty()) {
                pageState.smoothCenterOnLine(
                    activeIndex = activeLineIndex,
                    totalCount = lyrics.size
                )
            }
        }

        LazyColumn(
            state = pageState.listState,
            flingBehavior = ScrollableDefaults.flingBehavior(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = halfScreenVerticalPadding,
                bottom = halfScreenVerticalPadding
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // 顶底双向对称边缘羽化，文字柔和隐入背景
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.22f to Color.Black,
                            0.78f to Color.Black,
                            1.0f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            itemsIndexed(
                items = lyrics,
                key = { index, line -> "${line.startMs}_$index" }
            ) { index, line ->
                val distanceFromActive = abs(index - activeLineIndex)
                val isCurrentActive = index == activeLineIndex
                val isPastLine = index < activeLineIndex

                AppleMusicLyricLineItem(
                    line = line,
                    isActive = isCurrentActive,
                    isPast = isPastLine,
                    distanceFromActive = distanceFromActive,
                    currentPositionMs = currentPositionMs,
                    fontScaleFactor = fontScaleFactor,
                    fontFamily = fontFamily,
                    showTranslation = showTranslation,
                    isUserBrowsingLyrics = isUserBrowsingLyrics,
                    accentColor = accentColor,
                    onActiveItemMeasured = { heightPx ->
                        if (heightPx > 0) {
                            pageState.activeItemHeightPx = heightPx
                        }
                    },
                    onClick = { onLyricLineClick(line) }
                )
            }
        }
    }
}

/**
 * 完整级 Horologist 现代歌词界面（ModernHorologistLyricsScreen）：
 * - 严格遵循 Apple Music 原版视觉与交互规范；
 * - 采用主题强调色（accentColor = primary）高亮歌词；
 * - 间距紧凑自然，避免行距过散；
 * - 手动浏览歌词时提高所有歌词行的可读性。
 */
@Composable
fun ModernHorologistLyricsScreen(
    song: SongHit?,
    lyrics: Lyrics?,
    lyricsLoading: Boolean,
    lyricsStatus: String,
    onRetryLyrics: () -> Unit,
    isVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }
    val isPlaying = PlaybackEngine.isPlaying()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isAppResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val smoothPositionState = rememberSmoothPlaybackPositionMs(
        isPlaying = isPlaying,
        isAppResumed = isAppResumed,
        isVisible = isVisible,
        playbackKey = song?.id
    )

    val lines = lyrics?.lines ?: emptyList()
    val activeLineIndex by remember(lines) {
        derivedStateOf {
            if (lines.isEmpty()) 0
            else {
                val ms = smoothPositionState.value
                val idx = lines.indexOfLast { ms >= it.startMs }
                if (idx >= 0) idx else 0
            }
        }
    }

    val pageState = rememberWearLyricsPageState(initialFirstVisibleItemIndex = activeLineIndex)
    var isUserBrowsingLyrics by remember { mutableStateOf(false) }
    var autoResumeJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    fun handleUserManualScroll() {
        isUserBrowsingLyrics = true
        autoResumeJob?.cancel()
        autoResumeJob = coroutineScope.launch {
            delay(3500L)
            isUserBrowsingLyrics = false
        }
    }

    fun resumeTracking() {
        autoResumeJob?.cancel()
        isUserBrowsingLyrics = false
    }

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            resumeTracking()
        }
    }

    ScreenScaffold(scrollState = pageState.listState) { _ ->
        // 背景保持透明，使外层 HorologistArtworkBackground 的封面透出
        Box(
            modifier = modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onRotaryScrollEvent { event ->
                    handleUserManualScroll()
                    coroutineScope.launch {
                        pageState.listState.scrollBy(event.verticalScrollPixels)
                    }
                    true
                }
        ) {
            when {
                lyricsLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "正在加载歌词…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                lyrics == null || lyrics.lines.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            lyricsStatus.ifBlank { "暂无歌词" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(onClick = onRetryLyrics) {
                            Text("重试加载")
                        }
                    }
                }
                else -> {
                    val accentColor = MaterialTheme.colorScheme.primary

                    WearLyricsPage(
                        lyrics = lines,
                        currentPositionMs = smoothPositionState.value,
                        activeLineIndex = activeLineIndex,
                        isUserBrowsingLyrics = isUserBrowsingLyrics,
                        fontScaleFactor = 1.15f,
                        fontFamily = PlusJakartaSansFontFamily,
                        accentColor = accentColor,
                        onLyricLineClick = { line ->
                            PlaybackEngine.seekTo(line.startMs)
                            resumeTracking()
                        },
                        onUserManualScroll = {
                            handleUserManualScroll()
                        },
                        pageState = pageState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 用户手动翻看歌词时，底部展现极简回正胶囊
            if (isUserBrowsingLyrics && lines.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 12.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    FilledTonalButton(
                        onClick = {
                            resumeTracking()
                            coroutineScope.launch {
                                pageState.smoothCenterOnLine(
                                    activeIndex = activeLineIndex,
                                    totalCount = lines.size
                                )
                            }
                        },
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("回到正在播放", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
