package com.applemusic.lyricstool.wear.ui.screens

import android.os.Build
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.applemusic.android.lyrics.LyricLine
import com.applemusic.lyricstool.data.SongHit
import com.applemusic.lyricstool.wear.ui.theme.PlusJakartaSansFontFamily
import com.applemusic.lyricstool.wear.viewmodel.LyricsViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 平滑播放时钟（零单向漂移 + 支持暂停期立即响应 seek）：
 * - 正常播放每 33ms 推进，温和向 ExoPlayer 真实时钟靠拢，不再因 `maxOf` 棘轮效应永久超前音频；
 * - 显式 seek 或时钟偏差 > 400ms 时立即重锚；
 * - 不可见或息屏时零轮询耗电。
 */
@Composable
private fun rememberSmoothPlaybackPositionMs(
    isPlaying: Boolean,
    isAppResumed: Boolean,
    isVisible: Boolean,
    playbackKey: String?,
    seekRevision: Long,
    positionReader: () -> Long
): State<Long> {
    val positionState = remember { mutableLongStateOf(positionReader()) }

    LaunchedEffect(isPlaying, isAppResumed, isVisible, playbackKey, seekRevision) {
        if (!isAppResumed) return@LaunchedEffect
        val initialPos = positionReader()
        positionState.longValue = initialPos
        if (!isPlaying || !isVisible) return@LaunchedEffect

        var baseExoPos = initialPos
        var baseRealtime = SystemClock.elapsedRealtime()

        while (true) {
            delay(33L)
            val now = SystemClock.elapsedRealtime()
            val rawExoPos = positionReader()
            val elapsed = now - baseRealtime
            val estimatedPos = baseExoPos + elapsed
            val drift = rawExoPos - estimatedPos

            if (abs(drift) > 400L) {
                baseExoPos = rawExoPos
                baseRealtime = now
                positionState.longValue = rawExoPos
            } else {
                // 轻微平滑纠偏（每帧收敛 20% 偏差），既顺滑又永不越跑越快
                val corrected = estimatedPos + (drift / 5L)
                positionState.longValue = corrected
                if (elapsed >= 500L) {
                    baseExoPos = corrected
                    baseRealtime = now
                }
            }
        }
    }

    return positionState
}

private val LyricLine.isInterlude: Boolean
    get() = text.isBlank() || text == "•••" || text == "..." || text.equals("[间奏]", ignoreCase = true)

private fun LyricLine.lineProgress(positionMs: Long): Float {
    if (endMs <= startMs) return if (positionMs >= startMs) 1f else 0f
    return ((positionMs - startMs).toFloat() / (endMs - startMs).toFloat()).coerceIn(0f, 1f)
}

private data class GraphemeTiming(
    val startIndex: Int,
    val endIndex: Int,
    val startMs: Long,
    val endMs: Long
)

/**
 * 按字素簇（Grapheme Cluster）切分并计算严格单调时间轴：
 * - 使用 [BreakIterator.getCharacterInstance] 完整保护 Emoji / 代理对 / 组合变音符号；
 * - 强制单调递增，修复 TTML 偶发重叠或乱序音节导致的扫光跳闪。
 */
private fun LyricLine.computeGraphemeTimings(): List<GraphemeTiming> {
    if (text.isEmpty()) return emptyList()

    val rawStarts = LongArray(text.length) { -1L }
    val rawEnds = LongArray(text.length) { -1L }

    if (words.isNotEmpty()) {
        val sortedWords = words.sortedWith(compareBy({ it.charStart }, { it.startMs }))
        for (w in sortedWords) {
            val cs = w.charStart.coerceIn(0, text.length)
            val ce = w.charEnd.coerceIn(cs, text.length)
            val len = ce - cs
            if (len <= 0) continue
            val wStart = w.startMs.coerceAtLeast(0L)
            val wEnd = w.endMs.coerceAtLeast(wStart + 1L)
            val span = (wEnd - wStart).coerceAtLeast(len.toLong())
            for (i in 0 until len) {
                val cStart = wStart + (span * i) / len
                val cEnd = if (i == len - 1) wEnd else wStart + (span * (i + 1)) / len
                rawStarts[cs + i] = cStart
                rawEnds[cs + i] = cEnd.coerceAtLeast(cStart + 1L)
            }
        }
    } else {
        val lineStart = startMs.coerceAtLeast(0L)
        val effectiveEnd = (endMs - 200L).coerceAtLeast(lineStart + 400L)
        val totalSpan = (effectiveEnd - lineStart).coerceAtLeast(text.length.toLong())
        for (i in text.indices) {
            val cStart = lineStart + (totalSpan * i) / text.length
            val cEnd = if (i == text.lastIndex) effectiveEnd else lineStart + (totalSpan * (i + 1)) / text.length
            rawStarts[i] = cStart
            rawEnds[i] = cEnd.coerceAtLeast(cStart + 1L)
        }
    }

    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val result = ArrayList<GraphemeTiming>(text.length)
    var lastEnd = startMs.coerceAtLeast(0L)

    var gStart = iterator.first()
    var gEnd = iterator.next()
    while (gEnd != BreakIterator.DONE) {
        val cs = gStart.coerceIn(0, text.length - 1)
        val ce = gEnd.coerceIn(cs + 1, text.length)
        var s = -1L
        var e = -1L
        for (i in cs until ce) {
            if (rawStarts[i] >= 0L) {
                if (s < 0L || rawStarts[i] < s) s = rawStarts[i]
                if (rawEnds[i] > e) e = rawEnds[i]
            }
        }
        val monoStart = if (s >= 0L) max(s, lastEnd) else lastEnd
        val monoEnd = if (e > monoStart) e else (monoStart + 20L)
        result.add(GraphemeTiming(cs, ce, monoStart, monoEnd))
        lastEnd = monoEnd
        gStart = gEnd
        gEnd = iterator.next()
    }
    return result
}

/** 二分查找当前时间命中的歌词行索引（O(log N)）。 */
private fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return 0
    var low = 0
    var high = lines.lastIndex
    var best = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].startMs <= positionMs) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

@Stable
private class WearLyricsPageState(
    val listState: LazyListState
) {
    var activeItemHeightPx by mutableIntStateOf(120)
        internal set

    suspend fun smoothCenterOnItem(listItemIndex: Int, totalItems: Int) {
        if (totalItems <= 0 || listItemIndex !in 0 until totalItems) return

        withFrameNanos { }

        val layoutInfo = listState.layoutInfo
        val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == listItemIndex }

        if (visibleItem != null) {
            val targetCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
            val itemCenter = visibleItem.offset + (visibleItem.size / 2f)
            val scrollDelta = itemCenter - targetCenter

            if (abs(scrollDelta) > 1.5f) {
                listState.animateScrollBy(
                    value = scrollDelta,
                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
                )
            }
        } else {
            val roughOffset = (activeItemHeightPx * 0.5f).toInt()
            listState.scrollToItem(index = listItemIndex, scrollOffset = roughOffset)
            withFrameNanos { }
            val updatedLayout = listState.layoutInfo
            val nowVisible = updatedLayout.visibleItemsInfo.firstOrNull { it.index == listItemIndex }
            if (nowVisible != null) {
                val targetCenter = (updatedLayout.viewportStartOffset + updatedLayout.viewportEndOffset) / 2f
                val itemCenter = nowVisible.offset + (nowVisible.size / 2f)
                val scrollDelta = itemCenter - targetCenter
                if (abs(scrollDelta) > 1.5f) {
                    listState.animateScrollBy(
                        value = scrollDelta,
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberWearLyricsPageState(
    initialFirstVisibleItemIndex: Int = 0
): WearLyricsPageState {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialFirstVisibleItemIndex)
    return remember(listState) { WearLyricsPageState(listState) }
}

/**
 * 节能交错呼吸间奏圆点：仅在激活且正在播放时才创建无限过渡动画，并按 3 颗圆点错峰律动。
 */
@Composable
private fun AppleMusicInterludeDots(
    line: LyricLine,
    isActive: Boolean,
    isPlaying: Boolean,
    positionProvider: () -> Long,
    accentColor: Color
) {
    val phaseState = if (isActive && isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "interludePulse")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotWave"
        )
    } else {
        null
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        repeat(3) { dotIndex ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        val scale = if (phaseState != null) {
                            val d = abs(phaseState.value - dotIndex.toFloat()).coerceIn(0f, 1.5f)
                            1.18f - (d * 0.16f)
                        } else {
                            1f
                        }
                        scaleX = scale
                        scaleY = scale
                        val dotFill = if (!isActive) {
                            0.40f
                        } else {
                            val prog = line.lineProgress(positionProvider())
                            ((prog - dotIndex / 3f) * 3f).coerceIn(0.28f, 1f)
                        }
                        alpha = dotFill
                    }
                    .clip(CircleShape)
                    .background(if (isActive) accentColor else Color.White)
            )
        }
    }
}

/**
 * Apple Music 逐字平滑扫光文字组件（绘制期驱动，零重组）：
 * - 采用字素簇切分与单调时间戳，兼容 Emoji 与多语言（含 RTL 双向文本）；
 * - 精准按字符边界羽化剪裁，消除未起唱提早亮边与下行部裁切；
 * - 遵循用户偏好：高亮使用主题强调色（accentColor）。
 */
@Composable
private fun AppleMusicKaraokeSweepText(
    line: LyricLine,
    positionProvider: () -> Long,
    baseFontSize: TextUnit,
    lineHeight: TextUnit,
    fontFamily: FontFamily,
    accentColor: Color
) {
    val graphemeTimings = remember(line.text, line.startMs, line.endMs, line.words) {
        line.computeGraphemeTimings()
    }
    var textLayoutResult by remember(line.text, baseFontSize) {
        mutableStateOf<TextLayoutResult?>(null)
    }

    val sharedTextStyle = remember(fontFamily, baseFontSize, lineHeight) {
        TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = baseFontSize,
            lineHeight = lineHeight,
            letterSpacing = (-0.2).sp,
            textAlign = TextAlign.Center
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 底层：未唱到部分的柔和半透明文字
        Text(
            text = line.text,
            textAlign = TextAlign.Center,
            style = sharedTextStyle.copy(color = Color.White.copy(alpha = 0.42f)),
            onTextLayout = { textLayoutResult = it },
            modifier = Modifier.fillMaxWidth()
        )

        // 顶层：强调色逐字扫光层（positionProvider 仅在绘制阶段读取）
        Text(
            text = line.text,
            textAlign = TextAlign.Center,
            style = sharedTextStyle.copy(color = accentColor),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    val layout = textLayoutResult
                    if (layout == null || graphemeTimings.isEmpty() || size.width <= 0f) {
                        return@drawWithContent
                    }

                    val currentPositionMs = positionProvider()
                    drawContent()

                    val totalLines = layout.lineCount
                    val textLen = line.text.length

                    for (lineIdx in 0 until totalLines) {
                        val stripTop = if (lineIdx == 0) {
                            -18f
                        } else {
                            (layout.getLineBottom(lineIdx - 1) + layout.getLineTop(lineIdx)) * 0.5f
                        }
                        val stripBottom = if (lineIdx == totalLines - 1) {
                            size.height + 18f
                        } else {
                            (layout.getLineBottom(lineIdx) + layout.getLineTop(lineIdx + 1)) * 0.5f
                        }
                        val stripHeight = (stripBottom - stripTop).coerceAtLeast(1f)

                        val rawStartChar = layout.getLineStart(lineIdx)
                            .coerceIn(0, (textLen - 1).coerceAtLeast(0))
                        val rawEndExclusive = layout.getLineEnd(lineIdx, visibleEnd = true)
                            .coerceIn(rawStartChar + 1, textLen)

                        val lineClusters = graphemeTimings.filter {
                            it.startIndex < rawEndExclusive && it.endIndex > rawStartChar
                        }
                        if (lineClusters.isEmpty()) continue

                        val lineStartMs = lineClusters.first().startMs
                        val lineEndMs = lineClusters.last().endMs

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
                                // 本行已唱完，完整保留强调色
                            }

                            else -> {
                                var activeCluster = lineClusters.first()
                                for (cluster in lineClusters) {
                                    activeCluster = cluster
                                    if (currentPositionMs < cluster.endMs) break
                                }

                                val span = (activeCluster.endMs - activeCluster.startMs).coerceAtLeast(1L)
                                val charProgress = ((currentPositionMs - activeCluster.startMs).toFloat() /
                                    span.toFloat()).coerceIn(0f, 1f)

                                val anchorChar = activeCluster.startIndex.coerceIn(0, textLen - 1)
                                val box = layout.getBoundingBox(anchorChar)
                                val charLeft = min(box.left, box.right)
                                val charRight = max(box.left, box.right)
                                val charWidth = (charRight - charLeft).coerceAtLeast(2f)
                                val isRtl = layout.getBidiRunDirection(anchorChar) == ResolvedTextDirection.Rtl

                                val featherPx = if (charProgress <= 0.005f) {
                                    0f
                                } else {
                                    (charWidth * 0.24f).coerceIn(2f, 8f)
                                }

                                if (!isRtl) {
                                    val sweepX = charLeft + (charWidth * charProgress)
                                    val solidStop = ((sweepX - featherPx * 0.5f) / size.width).coerceIn(0f, 1f)
                                    val transStop = ((sweepX + featherPx * 0.5f) / size.width).coerceIn(
                                        (solidStop + 0.001f).coerceAtMost(1f),
                                        1f
                                    )
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            0.0f to Color.Black,
                                            solidStop to Color.Black,
                                            transStop to Color.Transparent,
                                            1.0f to Color.Transparent,
                                            startX = 0f,
                                            endX = size.width
                                        ),
                                        topLeft = Offset(0f, stripTop),
                                        size = Size(size.width, stripHeight),
                                        blendMode = BlendMode.DstIn
                                    )
                                } else {
                                    val sweepX = charRight - (charWidth * charProgress)
                                    val transStop = ((sweepX - featherPx * 0.5f) / size.width).coerceIn(0f, 1f)
                                    val solidStop = ((sweepX + featherPx * 0.5f) / size.width).coerceIn(
                                        (transStop + 0.001f).coerceAtMost(1f),
                                        1f
                                    )
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            0.0f to Color.Transparent,
                                            transStop to Color.Transparent,
                                            solidStop to Color.Black,
                                            1.0f to Color.Black,
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
                }
        )
    }
}

/**
 * 计算歌词行距当前播放行的景深模糊半径（单位 dp）：
 * - 非同步歌词、用户滑动/旋转表冠浏览期间、或当前播放行保持 0dp 完全清晰；
 * - 从当前播放行向上下逐行递增模糊（距离越远越模糊），并设上限兼顾手表 GPU 帧率。
 */
private fun computeLyricBlurRadiusDp(
    isSynced: Boolean,
    isUserBrowsingLyrics: Boolean,
    distanceFromActive: Int
): Float {
    if (!isSynced || isUserBrowsingLyrics || distanceFromActive <= 0) return 0f
    return when (distanceFromActive) {
        1 -> 1.4f
        2 -> 2.8f
        3 -> 4.2f
        4 -> 5.4f
        else -> 6.0f
    }
}

/**
 * 单行歌词条目：
 * - 单次 alpha 渲染（彻底修复 graphicsLayer 与 Text 颜色双重相乘导致邻近行暗淡的问题）；
 * - 以当前播放行为焦点向上下逐行递增景深模糊（API 31+ GPU RenderEffect），手动滑动/表冠浏览时即时解除模糊；
 * - 动画值延迟至 graphicsLayer 绘制期读取，避免逐帧重组；
 * - 保持紧凑间距（当前行 3.dp，非当前行 1.dp）与强调色主题风格；
 * - 非时间轴歌词（!isSynced）下全行清晰展示且禁用无效跳转。
 */
@Composable
private fun AppleMusicLyricLineItem(
    line: LyricLine,
    isSynced: Boolean,
    isActive: Boolean,
    isPast: Boolean,
    distanceFromActive: Int,
    isPlaying: Boolean,
    positionProvider: () -> Long,
    fontScaleFactor: Float,
    fontFamily: FontFamily,
    showTranslation: Boolean,
    isUserBrowsingLyrics: Boolean,
    accentColor: Color,
    onActiveItemMeasured: (Int) -> Unit,
    onClick: () -> Unit
) {
    val targetScale = if (!isSynced || isActive) 1.0f else 0.90f
    val scaleState = animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "lyricScale"
    )

    val targetAlpha = when {
        !isSynced -> 0.92f
        isUserBrowsingLyrics -> 0.96f
        isActive -> 1.0f
        distanceFromActive == 1 -> if (isPast) 0.62f else 0.72f
        distanceFromActive == 2 -> 0.48f
        distanceFromActive == 3 -> 0.36f
        else -> 0.26f
    }
    val alphaState = animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(
            durationMillis = if (isUserBrowsingLyrics) 160 else 300,
            easing = FastOutSlowInEasing
        ),
        label = "lyricAlpha"
    )

    val targetBlurDp = computeLyricBlurRadiusDp(
        isSynced = isSynced,
        isUserBrowsingLyrics = isUserBrowsingLyrics,
        distanceFromActive = distanceFromActive
    )
    val blurDpState = animateFloatAsState(
        targetValue = targetBlurDp,
        animationSpec = tween(
            durationMillis = if (isUserBrowsingLyrics) 160 else 360,
            easing = FastOutSlowInEasing
        ),
        label = "lyricBlur"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                if (isActive && size.height > 0) {
                    onActiveItemMeasured(size.height)
                }
            }
            .graphicsLayer {
                scaleX = scaleState.value
                scaleY = scaleState.value
                alpha = alphaState.value
                transformOrigin = TransformOrigin.Center
                val blurPx = blurDpState.value * density
                renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurPx > 0.5f) {
                    BlurEffect(
                        radiusX = blurPx,
                        radiusY = blurPx,
                        edgeTreatment = TileMode.Decal
                    )
                } else {
                    null
                }
            }
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                enabled = isSynced && line.startMs >= 0L,
                interactionSource = interactionSource,
                indication = ripple(color = accentColor),
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = if (isActive) 3.dp else 1.dp)
    ) {
        if (line.isInterlude) {
            AppleMusicInterludeDots(
                line = line,
                isActive = isSynced && isActive,
                isPlaying = isPlaying,
                positionProvider = positionProvider,
                accentColor = accentColor
            )
        } else {
            val baseFontSize = (18.0f * fontScaleFactor).sp
            val lineHeight = (23.5f * fontScaleFactor).sp

            if (isSynced && isActive) {
                AppleMusicKaraokeSweepText(
                    line = line,
                    positionProvider = positionProvider,
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
                        fontWeight = if (isSynced && isPast) FontWeight.Bold else FontWeight.Medium,
                        fontSize = baseFontSize,
                        lineHeight = lineHeight,
                        letterSpacing = (-0.2).sp,
                        color = when {
                            !isSynced -> Color.White
                            isUserBrowsingLyrics -> Color.White
                            isPast -> accentColor
                            else -> Color.White
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
                        fontWeight = if (isSynced && isActive) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = (12.0f * fontScaleFactor).sp,
                        lineHeight = (16.0f * fontScaleFactor).sp,
                        textAlign = TextAlign.Center,
                        color = if (isSynced && isActive) {
                            accentColor.copy(alpha = 0.92f)
                        } else {
                            Color.White.copy(alpha = 0.85f)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun WearLyricsPage(
    song: SongHit?,
    lyrics: List<LyricLine>,
    isSynced: Boolean,
    isPlaying: Boolean,
    isVisible: Boolean,
    positionProvider: () -> Long,
    activeLineIndex: Int,
    isUserBrowsingLyrics: Boolean,
    showTranslation: Boolean,
    fontScaleFactor: Float,
    fontFamily: FontFamily,
    accentColor: Color,
    onLyricLineClick: (LyricLine) -> Unit,
    onDragStateChanged: (Boolean) -> Unit,
    pageState: WearLyricsPageState,
    modifier: Modifier = Modifier
) {
    val isDragged by pageState.listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        onDragStateChanged(isDragged)
    }

    val hasHeader = song != null && song.title.isNotBlank()
    val headerCount = if (hasHeader) 1 else 0

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize()
    ) {
        val viewportHeightDp = maxHeight
        // 上下各留 50% 视口高度，确保首句、中间句与末句（含歌名标题头时）均可精准停靠在垂直正中央
        val verticalCenterPadding = (viewportHeightDp * 0.50f).coerceAtLeast(96.dp)
        val topPadding = if (isSynced) verticalCenterPadding else (viewportHeightDp * 0.18f).coerceAtLeast(36.dp)
        val bottomPadding = verticalCenterPadding

        LaunchedEffect(
            isVisible,
            activeLineIndex,
            lyrics,
            isSynced,
            isUserBrowsingLyrics,
            viewportHeightDp,
            fontScaleFactor,
            showTranslation
        ) {
            if (isVisible && isSynced && !isUserBrowsingLyrics && lyrics.isNotEmpty() && activeLineIndex >= 0) {
                pageState.smoothCenterOnItem(
                    listItemIndex = activeLineIndex + headerCount,
                    totalItems = lyrics.size + headerCount
                )
            }
        }

        LazyColumn(
            state = pageState.listState,
            flingBehavior = ScrollableDefaults.flingBehavior(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = topPadding,
                bottom = bottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.16f to Color.Black,
                            0.84f to Color.Black,
                            1.0f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            if (hasHeader) {
                val headerSong = song!!
                item(key = "lyrics_song_header_${headerSong.id}") {
                    val headerDistance = if (isSynced && activeLineIndex >= 0) activeLineIndex + 1 else 0
                    val headerTargetBlurDp = computeLyricBlurRadiusDp(
                        isSynced = isSynced,
                        isUserBrowsingLyrics = isUserBrowsingLyrics,
                        distanceFromActive = headerDistance
                    )
                    val headerBlurDpState = animateFloatAsState(
                        targetValue = headerTargetBlurDp,
                        animationSpec = tween(
                            durationMillis = if (isUserBrowsingLyrics) 160 else 360,
                            easing = FastOutSlowInEasing
                        ),
                        label = "headerBlur"
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                val blurPx = headerBlurDpState.value * density
                                renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurPx > 0.5f) {
                                    BlurEffect(
                                        radiusX = blurPx,
                                        radiusY = blurPx,
                                        edgeTreatment = TileMode.Decal
                                    )
                                } else {
                                    null
                                }
                            }
                            .padding(bottom = 10.dp)
                    ) {
                        Text(
                            text = headerSong.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            color = accentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        if (headerSong.artist.isNotBlank()) {
                            Text(
                                text = headerSong.artist,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = fontFamily,
                                    fontSize = 12.sp
                                ),
                                color = Color.White.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            itemsIndexed(
                items = lyrics,
                key = { index, line -> "${line.startMs}_$index" }
            ) { index, line ->
                val isCurrentActive = isSynced && index == activeLineIndex
                val isPastLine = isSynced && index < activeLineIndex
                val distanceFromActive = if (isSynced && activeLineIndex >= 0) abs(index - activeLineIndex) else 0

                AppleMusicLyricLineItem(
                    line = line,
                    isSynced = isSynced,
                    isActive = isCurrentActive,
                    isPast = isPastLine,
                    distanceFromActive = distanceFromActive,
                    isPlaying = isPlaying,
                    positionProvider = positionProvider,
                    fontScaleFactor = fontScaleFactor,
                    fontFamily = fontFamily,
                    showTranslation = showTranslation,
                    isUserBrowsingLyrics = isUserBrowsingLyrics,
                    accentColor = accentColor,
                    onActiveItemMeasured = { heightPx ->
                        if (heightPx > 0) pageState.activeItemHeightPx = heightPx
                    },
                    onClick = { onLyricLineClick(line) }
                )
            }
        }
    }
}

/**
 * 完整级现代 Wear OS 逐字歌词界面：
 * - 响应式播放状态与 seek 修订监听；
 * - 绘制期 30fps 平滑逐字扫光，列表零重组；
 * - 以当前播放行为焦点向上下逐行递增景深模糊，触摸拖动或表冠/表圈滑动时即时解除模糊；
 * - 播放行精确居中停靠于屏幕垂直正中央（50% 视口中点）；
 * - 原生 Wear OS 表冠 / 三星模拟与物理表圈滚动（rotaryScrollable + 惯性滑动 + 三星触觉反馈 + 分层焦点协同）；
 * - 拖动或表冠惯性滚动完全停止后再计时 3.5 秒自动回正；
 * - 保持用户偏好的紧凑行距、强调色高亮与动态专辑模糊背景。
 */
@Composable
fun LyricsScreen(
    viewModel: LyricsViewModel,
    isVisible: Boolean,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isAppResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val smoothPositionState = rememberSmoothPlaybackPositionMs(
        isPlaying = state.isPlaying,
        isAppResumed = isAppResumed,
        isVisible = isVisible,
        playbackKey = state.song?.id,
        seekRevision = state.seekRevision,
        positionReader = viewModel::positionMs
    )

    val lyricsObj = state.lyrics
    val lines = lyricsObj?.lines.orEmpty()
    val isSynced = lyricsObj?.synced == true

    val activeLineIndex by remember(lines, isSynced) {
        derivedStateOf {
            if (!isSynced || lines.isEmpty()) -1
            else findActiveLineIndex(lines, smoothPositionState.value)
        }
    }

    val pageState = rememberWearLyricsPageState(initialFirstVisibleItemIndex = 0)
    var isUserBrowsingLyrics by remember { mutableStateOf(false) }
    var isDraggingList by remember { mutableStateOf(false) }
    var autoResumeJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            autoResumeJob?.cancel()
            isUserBrowsingLyrics = false
        }
    }

    // 切歌时复位手动浏览状态
    LaunchedEffect(state.song?.id) {
        autoResumeJob?.cancel()
        isUserBrowsingLyrics = false
    }

    fun scheduleAutoResume() {
        autoResumeJob?.cancel()
        autoResumeJob = coroutineScope.launch {
            while (true) {
                // 等待触摸拖动及表冠/表圈惯性滚动完全静止后再开始 3.5 秒回正倒计时
                delay(120L)
                snapshotFlow { isDraggingList || pageState.listState.isScrollInProgress }
                    .first { !it }
                delay(3500L)
                if (!isDraggingList && !pageState.listState.isScrollInProgress) {
                    isUserBrowsingLyrics = false
                    break
                }
            }
        }
    }

    fun handleDragChanged(dragging: Boolean) {
        isDraggingList = dragging
        if (dragging) {
            isUserBrowsingLyrics = true
            autoResumeJob?.cancel()
        } else if (isUserBrowsingLyrics) {
            scheduleAutoResume()
        }
    }

    fun handleRotaryScroll() {
        isUserBrowsingLyrics = true
        scheduleAutoResume()
    }

    fun resumeTracking() {
        autoResumeJob?.cancel()
        isUserBrowsingLyrics = false
    }

    val rotaryBehavior = RotaryScrollableDefaults.behavior(
        scrollableState = pageState.listState,
        flingBehavior = ScrollableDefaults.flingBehavior()
    )

    ScreenScaffold(scrollState = pageState.listState) { _ ->
        val focusRequester = rememberActiveFocusRequester()
        Box(
            modifier = modifier
                .fillMaxSize()
                .onPreRotaryScrollEvent {
                    if (lines.isNotEmpty()) {
                        handleRotaryScroll()
                    }
                    false
                }
                .rotaryScrollable(
                    behavior = rotaryBehavior,
                    focusRequester = focusRequester
                )
        ) {
            when {
                state.isLoading -> {
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
                lines.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = state.status.ifBlank { "暂无歌词" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (state.song != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(onClick = viewModel::retry) {
                                Text("重试加载")
                            }
                        }
                    }
                }
                else -> {
                    val accentColor = MaterialTheme.colorScheme.primary
                    WearLyricsPage(
                        song = state.song,
                        lyrics = lines,
                        isSynced = isSynced,
                        isPlaying = state.isPlaying,
                        isVisible = isVisible,
                        positionProvider = { smoothPositionState.value },
                        activeLineIndex = activeLineIndex,
                        isUserBrowsingLyrics = isUserBrowsingLyrics,
                        showTranslation = true,
                        fontScaleFactor = 1.15f,
                        fontFamily = PlusJakartaSansFontFamily,
                        accentColor = accentColor,
                        onLyricLineClick = { line ->
                            if (isSynced && line.startMs >= 0L) {
                                viewModel.seekTo(line.startMs)
                                resumeTracking()
                            }
                        },
                        onDragStateChanged = ::handleDragChanged,
                        pageState = pageState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 手动翻看同步歌词时，在页码指示器上方呈现回正胶囊（不遮挡底部三点）
            if (isSynced && isUserBrowsingLyrics && lines.isNotEmpty() && activeLineIndex >= 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 24.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val headerOffset = if (state.song?.title?.isNotBlank() == true) 1 else 0
                    FilledTonalButton(
                        onClick = {
                            resumeTracking()
                            coroutineScope.launch {
                                pageState.smoothCenterOnItem(
                                    listItemIndex = activeLineIndex + headerOffset,
                                    totalItems = lines.size + headerOffset
                                )
                            }
                        },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("回到正在播放", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
