package com.applemusic.lyricstool.wear.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 动态高斯柔化专辑封面背景（遵循 Google Horologist 规范）：
 * - 48x48 低分辨率硬件纹理采样 + GPU 双线性插值自然柔化（零高斯着色器开销）；
 * - 径向暗角渐变融入圆形表圈；
 * - 支持歌词页柔和暗化遮罩 [scrimAlpha]，让强调色逐字歌词在亮色封面上依然通透易读。
 */
@Composable
fun HorologistArtworkBackground(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    scrimAlpha: Float = 0f
) {
    val context = LocalContext.current
    val imageRequest = remember(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) null
        else {
            ImageRequest.Builder(context)
                .data(artworkUrl)
                .size(48, 48)
                .crossfade(true)
                .build()
        }
    }
    val animatedScrim by animateFloatAsState(
        targetValue = scrimAlpha.coerceIn(0f, 0.65f),
        animationSpec = tween(durationMillis = 260),
        label = "artworkScrim"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                alpha = 0.45f,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val radialBrush = Brush.radialGradient(
                            0.48f to Color.Transparent,
                            1f to colorScheme.background
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(color = colorScheme.primaryContainer, alpha = 0.20f)
                            drawRect(radialBrush)
                            if (animatedScrim > 0.005f) {
                                drawRect(color = Color.Black, alpha = animatedScrim)
                            }
                        }
                    }
            )
        }
    }
}
