package com.applemusic.lyricstool.wear.ui.screens

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.ScalingParams
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.RotarySnapLayoutInfoProvider
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.applemusic.lyricstool.wear.data.formatSleepTimerCountdown
import com.applemusic.lyricstool.wear.data.formatSleepTimerDuration
import com.applemusic.lyricstool.wear.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

private const val COLUMN_HOURS = 0
private const val COLUMN_MINUTES = 1
private const val WHEEL_REPEAT_CYCLES = 1000
private const val PICKER_GRADIENT_RATIO = 0.33f
private const val HAPTIC_MIN_INTERVAL_MS = 24L
private const val SAMSUNG_ROTARY_ITEM_FOCUS_HAPTIC = 102
private const val WEAR4_ROTARY_ITEM_FOCUS_HAPTIC = 19

private val TWO_DIGIT_LABELS = Array(60) { index ->
    if (index < 10) "0$index" else index.toString()
}

private fun optionIndexFromCenter(centerIndex: Int, optionCount: Int): Int {
    val mod = centerIndex % optionCount
    return if (mod < 0) mod + optionCount else mod
}

/**
 * 轻量高性能 Horologist 风格滚轮刻度震动：
 * - 直接在主线程响应 [ScalingLazyListState.centerItemIndex] 真实刻度跳变，彻底解决 Wear Compose
 *   1.5.0-alpha08 内置旋转震动与刻度脱节、丢帧、慢转无震动以及滑着滑着震动消失的问题；
 * - 同时覆盖表冠/表圈旋转与手指滑动/惯性甩动，并兼容 Wear OS 3.5 / 4 / 5+ 与三星 Galaxy Watch。
 */
private fun performPickerTickHaptic(view: View) {
    val performed = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        }
        Build.MANUFACTURER.contains("Samsung", ignoreCase = true) -> {
            view.performHapticFeedback(SAMSUNG_ROTARY_ITEM_FOCUS_HAPTIC)
        }
        Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU -> {
            view.performHapticFeedback(WEAR4_ROTARY_ITEM_FOCUS_HAPTIC)
        }
        else -> false
    }
    if (!performed) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

private class PickerHapticController(private val view: View) {
    private var lastHapticUptimeMs = 0L

    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHapticUptimeMs >= HAPTIC_MIN_INTERVAL_MS) {
            lastHapticUptimeMs = now
            performPickerTickHaptic(view)
        }
    }
}

private fun sleepTimerWheelScalingParams(): ScalingParams =
    ScalingLazyColumnDefaults.scalingParams(
        edgeScale = 0.45f,
        edgeAlpha = 1.0f,
        minElementHeight = 0.0f,
        maxElementHeight = 0.0f,
        minTransitionArea = 0.45f,
        maxTransitionArea = 0.45f,
        scaleInterpolator = CubicBezierEasing(0.25f, 0.00f, 0.75f, 1.00f),
        viewportVerticalOffsetResolver = { (it.maxHeight / 5f).toInt() }
    )

private class WheelRotarySnapLayoutInfoProvider(
    private val listState: ScalingLazyListState,
    private val fallbackItemSizePx: Float
) : RotarySnapLayoutInfoProvider {
    override val averageItemSize: Float
        get() = listState.layoutInfo.visibleItemsInfo
            .firstOrNull()
            ?.unadjustedSize
            ?.toFloat()
            ?.takeIf { it > 0f }
            ?: fallbackItemSizePx

    override val currentItemIndex: Int
        get() = listState.centerItemIndex

    override val currentItemOffset: Float
        get() = listState.centerItemScrollOffset.toFloat()

    override val totalItemCount: Int
        get() = listState.layoutInfo.totalItemsCount
}

/**
 * Google Horologist 风格定时播放（睡眠定时）时间选择器：
 * - 基于 Wear Compose Foundation [ScalingLazyColumn] + [HierarchicalFocusCoordinator] 轻量化双滚轮；
 * - 移除首帧同步文本测量、全屏透明度动画重组与每项离屏 GPU 图层，让长按呼出入场动画全程流畅；
 * - 禁用存在刻度脱节与丢震问题的内置旋转震动，改由 `centerItemIndex` 一对一驱动稳定的表冠/触控刻度反馈；
 * - 未定时默认定位 00:30 并聚焦分钟轮，旋转表冠即可快速微调；已定时则实时展示剩余倒计时并提供一键关闭按钮。
 */
@Composable
fun SleepTimerScreen(
    playerViewModel: PlayerViewModel,
    onClose: () -> Unit,
    onToast: (String, Boolean) -> Unit
) {
    val timerState by playerViewModel.sleepTimerState.collectAsStateWithLifecycle()

    // 仅在进入页面时计算一次初始刻度，防止活跃倒计时读秒期间重置用户正在滚动的滚轮
    val initialDuration = remember {
        val snapshot = playerViewModel.sleepTimerState.value
        if (snapshot.isActive) {
            val remMs = snapshot.remainingMs()
            val totalMinutes = ((remMs + 59_999L) / 60_000L).toInt().coerceIn(1, 23 * 60 + 59)
            (totalMinutes / 60) to (totalMinutes % 60)
        } else {
            0 to 30
        }
    }

    val hourListState = rememberScalingLazyListState(
        initialCenterItemIndex = 24 * (WHEEL_REPEAT_CYCLES / 2) + initialDuration.first
    )
    val minuteListState = rememberScalingLazyListState(
        initialCenterItemIndex = 60 * (WHEEL_REPEAT_CYCLES / 2) + initialDuration.second
    )

    var selectedColumn by remember { mutableIntStateOf(COLUMN_MINUTES) }

    val view = LocalView.current
    val hapticController = remember(view) { PickerHapticController(view) }
    val configuration = LocalConfiguration.current
    val isLargeScreen = configuration.screenWidthDp > 225
    val currentDensity = LocalDensity.current

    val selectColumn: (Int) -> Unit = remember(hapticController) {
        { targetColumn ->
            if (selectedColumn != targetColumn) {
                selectedColumn = targetColumn
                hapticController.tick()
            }
        }
    }

    ScreenScaffold(timeText = {}) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(if (isLargeScreen) 14.dp else 10.dp))

                // 锁定字体缩放（同 Horologist / Wear M3 TimePicker），避免系统大字号导致滚轮数字截断
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = currentDensity.density,
                        fontScale = 1f
                    )
                ) {
                    val baseNumeralStyle = MaterialTheme.typography.numeralMedium
                    val optionTextStyle = remember(baseNumeralStyle) {
                        baseNumeralStyle.copy(
                            textAlign = TextAlign.Center,
                            fontFeatureSettings = "tnum"
                        )
                    }
                    val optionWidth = if (isLargeScreen) 64.dp else 58.dp
                    val optionCellHeight = if (isLargeScreen) 44.dp else 40.dp
                    val separatorPadding = if (isLargeScreen) 8.dp else 5.dp
                    val sectionPadding = if (isLargeScreen) 6.dp else 3.dp

                    // 顶部状态与当前聚焦列标签
                    Column(
                        modifier = Modifier.fillMaxWidth(0.76f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val activeEndMs = timerState.endElapsedRealtimeMs
                        if (activeEndMs != null) {
                            ActiveTimerSubtitle(endElapsedRealtimeMs = activeEndMs)
                        } else {
                            Text(
                                text = "定时播放",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )
                        }
                        Text(
                            text = if (selectedColumn == COLUMN_HOURS) "小时" else "分钟",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontSize = if (isLargeScreen) 15.sp else 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(sectionPadding))

                    // 核心双滚轮时间选择区域（小时 : 分钟）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        SleepTimerWheelPicker(
                            listState = hourListState,
                            optionCount = 24,
                            selected = selectedColumn == COLUMN_HOURS,
                            onSelect = { selectColumn(COLUMN_HOURS) },
                            hapticController = hapticController,
                            optionTextStyle = optionTextStyle,
                            cellHeight = optionCellHeight,
                            unitLabel = "小时",
                            modifier = Modifier
                                .width(optionWidth)
                                .fillMaxHeight()
                        )

                        Text(
                            text = ":",
                            style = optionTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = separatorPadding)
                                .clearAndSetSemantics {}
                        )

                        SleepTimerWheelPicker(
                            listState = minuteListState,
                            optionCount = 60,
                            selected = selectedColumn == COLUMN_MINUTES,
                            onSelect = { selectColumn(COLUMN_MINUTES) },
                            hapticController = hapticController,
                            optionTextStyle = optionTextStyle,
                            cellHeight = optionCellHeight,
                            unitLabel = "分钟",
                            modifier = Modifier
                                .width(optionWidth)
                                .fillMaxHeight()
                        )
                    }

                    Spacer(Modifier.height(sectionPadding))
                }

                // 底部确认 / 关闭按钮区（独立作用域，滚动滚轮时不触发外层重组）
                SleepTimerActionButtons(
                    hourListState = hourListState,
                    minuteListState = minuteListState,
                    isTimerActive = timerState.isActive,
                    isLargeScreen = isLargeScreen,
                    onCancelTimer = {
                        playerViewModel.cancelSleepTimer()
                        onToast("已关闭定时播放", false)
                        onClose()
                    },
                    onConfirmTimer = { h, m ->
                        if (h == 0 && m == 0) {
                            if (timerState.isActive) {
                                playerViewModel.cancelSleepTimer()
                                onToast("已关闭定时播放", false)
                                onClose()
                            }
                        } else {
                            playerViewModel.startSleepTimer(hours = h, minutes = m)
                            onToast("已开启定时播放：${formatSleepTimerDuration(h, m)}", false)
                            onClose()
                        }
                    }
                )

                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun SleepTimerWheelPicker(
    listState: ScalingLazyListState,
    optionCount: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    hapticController: PickerHapticController,
    optionTextStyle: TextStyle,
    cellHeight: Dp,
    unitLabel: String,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val backgroundColor = MaterialTheme.colorScheme.background
    val cellHeightPx = with(density) { cellHeight.toPx() }
    val currentOnSelect by rememberUpdatedState(onSelect)

    val scalingParams = remember { sleepTimerWheelScalingParams() }
    val autoCentering = remember { AutoCenteringParams(itemIndex = 0) }
    val snapLayoutInfoProvider = remember(listState, cellHeightPx) {
        WheelRotarySnapLayoutInfoProvider(
            listState = listState,
            fallbackItemSizePx = cellHeightPx
        )
    }
    val rotaryBehavior = RotaryScrollableDefaults.snapBehavior(
        scrollableState = listState,
        layoutInfoProvider = snapLayoutInfoProvider,
        snapOffset = 0.dp,
        hapticFeedbackEnabled = false
    )
    val flingBehavior = ScalingLazyColumnDefaults.snapFlingBehavior(
        state = listState,
        snapOffset = 0.dp
    )

    // 真实刻度变更一对一震动：表冠快慢旋转、松手回弹与手指拖曳甩动均保持稳定清晰触感
    LaunchedEffect(listState, hapticController) {
        snapshotFlow { listState.centerItemIndex }
            .distinctUntilChanged()
            .drop(1)
            .collect {
                hapticController.tick()
            }
    }

    // 切离当前滚轮时立即将选中数字归中对齐，避免未聚焦遮罩裁切半个数字
    LaunchedEffect(selected) {
        if (!selected && (listState.isScrollInProgress || listState.centerItemScrollOffset != 0)) {
            listState.scrollToItem(listState.centerItemIndex, 0)
        }
    }

    val totalCount = optionCount * WHEEL_REPEAT_CYCLES

    HierarchicalFocusCoordinator(requiresFocus = { selected }) {
        Box(
            modifier = modifier
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        currentOnSelect()
                    }
                }
                .semantics {
                    contentDescription = unitLabel
                },
            contentAlignment = Alignment.Center
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val gradientHeight = size.height * PICKER_GRADIENT_RATIO
                        val topGradient = Brush.verticalGradient(
                            colors = listOf(backgroundColor, Color.Transparent),
                            startY = 0f,
                            endY = gradientHeight
                        )
                        val bottomGradient = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, backgroundColor),
                            startY = size.height - gradientHeight,
                            endY = size.height
                        )
                        val shimHeight = ((size.height - cellHeightPx) / 2f).coerceAtLeast(0f)

                        onDrawWithContent {
                            drawContent()
                            if (selected) {
                                if (gradientHeight > 0f) {
                                    drawRect(
                                        brush = topGradient,
                                        size = Size(size.width, gradientHeight)
                                    )
                                    drawRect(
                                        brush = bottomGradient,
                                        topLeft = Offset(0f, size.height - gradientHeight),
                                        size = Size(size.width, gradientHeight)
                                    )
                                }
                            } else if (shimHeight > 0f) {
                                drawRect(
                                    color = backgroundColor,
                                    size = Size(size.width, shimHeight)
                                )
                                drawRect(
                                    color = backgroundColor,
                                    topLeft = Offset(0f, size.height - shimHeight),
                                    size = Size(size.width, shimHeight)
                                )
                            }
                        }
                    },
                contentPadding = PaddingValues(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = flingBehavior,
                userScrollEnabled = true,
                scalingParams = scalingParams,
                autoCentering = autoCentering,
                rotaryScrollableBehavior = rotaryBehavior
            ) {
                items(totalCount) { index ->
                    SleepTimerPickerOption(
                        text = TWO_DIGIT_LABELS[index % optionCount],
                        textStyle = optionTextStyle,
                        selected = selected,
                        cellHeight = cellHeight
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepTimerActionButtons(
    hourListState: ScalingLazyListState,
    minuteListState: ScalingLazyListState,
    isTimerActive: Boolean,
    isLargeScreen: Boolean,
    onCancelTimer: () -> Unit,
    onConfirmTimer: (hours: Int, minutes: Int) -> Unit
) {
    val isZeroDuration by remember(hourListState, minuteListState) {
        derivedStateOf {
            optionIndexFromCenter(hourListState.centerItemIndex, 24) == 0 &&
                optionIndexFromCenter(minuteListState.centerItemIndex, 60) == 0
        }
    }
    val buttonSize = if (isLargeScreen) 48.dp else 44.dp
    val iconSize = if (isLargeScreen) 24.dp else 22.dp

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isTimerActive) {
            FilledTonalIconButton(
                onClick = onCancelTimer,
                modifier = Modifier.size(buttonSize),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭定时播放",
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        FilledIconButton(
            onClick = {
                val h = optionIndexFromCenter(hourListState.centerItemIndex, 24)
                val m = optionIndexFromCenter(minuteListState.centerItemIndex, 60)
                onConfirmTimer(h, m)
            },
            enabled = !isZeroDuration || isTimerActive,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "确认定时播放",
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun ActiveTimerSubtitle(endElapsedRealtimeMs: Long) {
    var remainingMs by remember(endElapsedRealtimeMs) {
        mutableLongStateOf((endElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }
    LaunchedEffect(endElapsedRealtimeMs) {
        while (true) {
            val rem = (endElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            remainingMs = rem
            if (rem <= 0L) break
            delay(1000L)
        }
    }
    Text(
        text = "剩余 ${formatSleepTimerCountdown(remainingMs)}",
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            lineHeight = 12.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun SleepTimerPickerOption(
    text: String,
    textStyle: TextStyle,
    selected: Boolean,
    cellHeight: Dp
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(cellHeight),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            style = textStyle,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .align(Alignment.Center)
                .wrapContentSize()
        )
    }
}
