package com.applemusic.lyricstool.wear.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.applemusic.lyricstool.wear.viewmodel.ToastMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

/**
 * 手表端轻量悬浮反馈胶囊（展示收藏、下载、电台启动、缓存清理与网络报错提示）。
 */
@Composable
fun WearStatusToastOverlay(
    toastEvents: SharedFlow<ToastMessage>,
    modifier: Modifier = Modifier
) {
    var activeToast by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(toastEvents) {
        toastEvents.collect { msg ->
            activeToast = msg
        }
    }

    LaunchedEffect(activeToast?.id) {
        val current = activeToast ?: return@LaunchedEffect
        delay(if (current.isError) 3200L else 2200L)
        if (activeToast?.id == current.id) {
            activeToast = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 20.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = activeToast != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            val toast = activeToast ?: return@AnimatedVisibility
            val bg = if (toast.isError) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f)
            } else {
                Color(0xE622202A)
            }
            val fg = if (toast.isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                Color.White
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 190.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = toast.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
