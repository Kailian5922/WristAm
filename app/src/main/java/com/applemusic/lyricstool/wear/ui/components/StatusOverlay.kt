package com.applemusic.lyricstool.wear.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.applemusic.lyricstool.wear.viewmodel.ToastKind
import com.applemusic.lyricstool.wear.viewmodel.ToastMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 手表端全局悬浮反馈胶囊：
 * - 队列式顺序展示，避免连续“下载完成 / 下载失败”提示互相覆盖丢失；
 * - 对成功 / 失败 / 普通提示区分图标、配色、边框与轻触觉反馈。
 */
@Composable
fun WearStatusToastOverlay(
    toastEvents: SharedFlow<ToastMessage>,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var activeToast by remember { mutableStateOf<ToastMessage?>(null) }
    var renderedToast by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(toastEvents) {
        val pendingQueue = ArrayDeque<ToastMessage>()
        val wakeSignal = Channel<Unit>(Channel.CONFLATED)

        launch {
            toastEvents.collect { msg ->
                // 折叠短时间内完全相同的连续提示，避免重复刷屏
                if (activeToast?.text == msg.text && pendingQueue.isEmpty()) {
                    return@collect
                }
                if (pendingQueue.lastOrNull()?.text == msg.text) {
                    return@collect
                }
                if (pendingQueue.size >= 6) {
                    pendingQueue.removeFirst()
                }
                pendingQueue.addLast(msg)
                wakeSignal.trySend(Unit)
            }
        }

        launch {
            while (true) {
                if (pendingQueue.isEmpty()) {
                    wakeSignal.receive()
                    continue
                }
                val next = pendingQueue.removeFirst()
                renderedToast = next
                activeToast = next
                if (next.kind == ToastKind.Success || next.kind == ToastKind.Error) {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } catch (_: Throwable) {
                    }
                }
                val durationMs = when (next.kind) {
                    ToastKind.Error -> 3400L
                    ToastKind.Success -> 2600L
                    ToastKind.Info -> 1900L
                }
                val stepMs = 150L
                var elapsed = 0L
                while (elapsed < durationMs) {
                    delay(stepMs)
                    elapsed += stepMs
                    // 若当前只是普通 Info 提示且队列中已有下载成功/失败结果，提前切到结果提示
                    if (next.kind == ToastKind.Info &&
                        elapsed >= 450L &&
                        pendingQueue.any { it.kind != ToastKind.Info }
                    ) {
                        break
                    }
                }
                activeToast = null
                if (pendingQueue.isNotEmpty()) {
                    delay(180L)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 22.dp, start = 14.dp, end = 14.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = activeToast != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            val toast = activeToast ?: renderedToast ?: return@AnimatedVisibility
            val (bg, borderColor, fg, iconTint) = when (toast.kind) {
                ToastKind.Error -> ToastPalette(
                    bg = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
                    border = MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
                    fg = MaterialTheme.colorScheme.onErrorContainer,
                    iconTint = MaterialTheme.colorScheme.onErrorContainer
                )
                ToastKind.Success -> ToastPalette(
                    bg = Color(0xEE142A1E),
                    border = Color(0x994ADE80),
                    fg = Color(0xFFF0FDF4),
                    iconTint = Color(0xFF4ADE80)
                )
                ToastKind.Info -> ToastPalette(
                    bg = Color(0xEC22202A),
                    border = Color(0x55FFFFFF),
                    fg = Color.White,
                    iconTint = Color.White
                )
            }
            val icon = when (toast.kind) {
                ToastKind.Success -> Icons.Default.CheckCircle
                ToastKind.Error -> Icons.Default.ErrorOutline
                ToastKind.Info -> null
            }

            Row(
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = toast.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                    textAlign = if (icon != null) TextAlign.Start else TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class ToastPalette(
    val bg: Color,
    val border: Color,
    val fg: Color,
    val iconTint: Color
)
