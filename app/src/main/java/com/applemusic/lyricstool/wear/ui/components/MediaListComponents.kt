package com.applemusic.lyricstool.wear.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.Text
import com.applemusic.lyricstool.wear.data.ActiveDownload

@Composable
fun WearBackHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(
            onClick = onBack,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 平滑过渡的圆角横向下载进度条，适配圆形手表屏幕卡片。 */
@Composable
fun WearDownloadLinearProgressBar(
    percent: Int,
    modifier: Modifier = Modifier,
    barHeight: Dp = 4.dp
) {
    val targetFraction = (percent / 100f).coerceIn(0.03f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 200),
        label = "DownloadBarProgress"
    )
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedFraction)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** 统一歌曲下载操作图标（未下载按钮 / 实时环形进度 / 已下载标识）。 */
@Composable
fun SongDownloadActionButton(
    activeDownload: ActiveDownload?,
    isDownloaded: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 32.dp
) {
    if (activeDownload != null) {
        val percent = activeDownload.percent.coerceIn(0, 100)
        Box(
            modifier = modifier
                .size(buttonSize)
                .clip(CircleShape)
                .clickable(onClick = onDownload),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { (percent / 100f).coerceIn(0.05f, 1f) },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 2.dp,
                colors = ProgressIndicatorDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primary
                )
            )
            Text(
                text = if (percent >= 100) "100" else "$percent%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }
    } else if (isDownloaded) {
        Box(
            modifier = modifier
                .size(buttonSize)
                .clip(CircleShape)
                .clickable(onClick = onDownload),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = HorologistIcons.DownloadDone,
                contentDescription = "已下载",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    } else {
        FilledTonalIconButton(
            onClick = onDownload,
            modifier = modifier.size(buttonSize),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                imageVector = HorologistIcons.Download,
                contentDescription = "下载",
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
