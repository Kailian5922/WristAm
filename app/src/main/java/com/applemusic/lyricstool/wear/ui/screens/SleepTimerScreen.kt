package com.applemusic.lyricstool.wear.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.PickerGroup
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.rememberPickerState
import com.applemusic.lyricstool.wear.data.formatSleepTimerCountdown
import com.applemusic.lyricstool.wear.data.formatSleepTimerDuration
import com.applemusic.lyricstool.wear.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import java.util.Locale

private const val COLUMN_HOURS = 0
private const val COLUMN_MINUTES = 1
private const val COLUMN_CONFIRM = 2

/**
 * Google Horologist 风格定时播放（睡眠定时）时间选择器：
 * - 基于 Wear Compose Material 3 [PickerGroup] + [rememberPickerState] 双滚轮（小时 : 分钟）；
 * - 严格复用 Horologist TimePicker 的大号数字排版、动态文本宽度测算、表冠/表圈旋转刻度吸附与平滑入场；
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

    val hourState = rememberPickerState(
        initialNumberOfOptions = 24,
        initiallySelectedIndex = initialDuration.first,
        shouldRepeatOptions = true
    )
    val minuteState = rememberPickerState(
        initialNumberOfOptions = 60,
        initiallySelectedIndex = initialDuration.second,
        shouldRepeatOptions = true
    )

    var selectedColumn by remember { mutableIntStateOf(COLUMN_MINUTES) }
    val focusRequesterConfirm = remember { FocusRequester() }

    val hoursContentDescription by remember {
        derivedStateOf { "${hourState.selectedOptionIndex} 小时" }
    }
    val minutesContentDescription by remember {
        derivedStateOf { "${minuteState.selectedOptionIndex} 分钟" }
    }
    val isZeroDuration by remember {
        derivedStateOf {
            hourState.selectedOptionIndex == 0 && minuteState.selectedOptionIndex == 0
        }
    }

    val fullyDrawn = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fullyDrawn.animateTo(1f, animationSpec = tween(durationMillis = 150))
    }

    val configuration = LocalConfiguration.current
    val isLargeScreen = configuration.screenWidthDp > 225
    val currentDensity = LocalDensity.current

    ScreenScaffold(timeText = {}) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .alpha(fullyDrawn.value)
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
                    val fixedDensity = LocalDensity.current
                    val optionTextStyle = MaterialTheme.typography.numeralMedium.copy(
                        textAlign = TextAlign.Center
                    )
                    val optionCellHeight = if (isLargeScreen) 44.dp else 38.dp
                    val separatorPadding = if (isLargeScreen) 10.dp else 6.dp
                    val sectionPadding = if (isLargeScreen) 6.dp else 3.dp

                    val textMeasurer = rememberTextMeasurer()
                    val twoDigitsWidthPx = remember(
                        fixedDensity.density,
                        configuration.screenWidthDp,
                        optionTextStyle
                    ) {
                        val measured = textMeasurer.measure(
                            text = "0123456789",
                            style = optionTextStyle,
                            density = fixedDensity
                        )
                        (0..9).maxOf { measured.getBoundingBox(it).width } * 2f
                    }
                    val optionWidth = max(
                        with(fixedDensity) { twoDigitsWidthPx.toDp() } + 2.dp,
                        48.dp
                    )

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
                            text = when (selectedColumn) {
                                COLUMN_HOURS -> "小时"
                                COLUMN_MINUTES -> "分钟"
                                else -> "确认"
                            },
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
                        PickerGroup(
                            selectedPickerIndex = selectedColumn,
                            onPickerSelected = { selectedColumn = it },
                            modifier = Modifier.fillMaxWidth(),
                            autoCenter = false,
                            separator = {
                                Text(
                                    text = ":",
                                    style = optionTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(horizontal = separatorPadding)
                                        .clearAndSetSemantics {}
                                )
                            }
                        ) {
                            pickerGroupItem(
                                pickerState = hourState,
                                modifier = Modifier
                                    .width(optionWidth)
                                    .fillMaxHeight(),
                                onSelected = {
                                    selectedColumn = if (selectedColumn != COLUMN_HOURS) {
                                        COLUMN_HOURS
                                    } else {
                                        COLUMN_MINUTES
                                    }
                                },
                                contentDescription = hoursContentDescription,
                                option = { optionIndex, pickerSelected ->
                                    SleepTimerPickerOption(
                                        text = String.format(Locale.US, "%02d", optionIndex),
                                        textStyle = optionTextStyle,
                                        selected = pickerSelected,
                                        cellHeight = optionCellHeight
                                    )
                                }
                            )

                            pickerGroupItem(
                                pickerState = minuteState,
                                modifier = Modifier
                                    .width(optionWidth)
                                    .fillMaxHeight(),
                                onSelected = {
                                    if (selectedColumn != COLUMN_MINUTES) {
                                        selectedColumn = COLUMN_MINUTES
                                    } else {
                                        selectedColumn = COLUMN_CONFIRM
                                        focusRequesterConfirm.requestFocus()
                                    }
                                },
                                contentDescription = minutesContentDescription,
                                option = { optionIndex, pickerSelected ->
                                    SleepTimerPickerOption(
                                        text = String.format(Locale.US, "%02d", optionIndex),
                                        textStyle = optionTextStyle,
                                        selected = pickerSelected,
                                        cellHeight = optionCellHeight
                                    )
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(sectionPadding))
                }

                // 底部确认 / 关闭按钮区
                val buttonSize = if (isLargeScreen) 48.dp else 44.dp
                val iconSize = if (isLargeScreen) 24.dp else 22.dp
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (timerState.isActive) {
                        FilledTonalIconButton(
                            onClick = {
                                playerViewModel.cancelSleepTimer()
                                onToast("已关闭定时播放", false)
                                onClose()
                            },
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
                            val h = hourState.selectedOptionIndex
                            val m = minuteState.selectedOptionIndex
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
                        },
                        enabled = !isZeroDuration || timerState.isActive,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .size(buttonSize)
                            .semantics {
                                focused = (selectedColumn == COLUMN_CONFIRM)
                            }
                            .focusRequester(focusRequesterConfirm)
                            .focusable()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "确认定时播放",
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
            }
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
