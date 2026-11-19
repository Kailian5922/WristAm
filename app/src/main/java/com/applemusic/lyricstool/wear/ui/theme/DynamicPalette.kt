package com.applemusic.lyricstool.wear.ui.theme

import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import androidx.wear.compose.material3.ColorScheme
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val dynamicColorCache = LruCache<String, ColorScheme>(24)

/**
 * 从歌曲封面提取动态 Material 3 强调色方案：
 * - 命中 LRU 缓存时同步初始化，杜绝切歌闪变；
 * - 48x48 低分辨率 Coil 解码（IO 线程）+ Palette 聚类（Default 线程）；
 * - 切歌配色平滑过渡动画。
 */
@Composable
fun rememberDynamicColorScheme(artworkUrl: String?): ColorScheme {
    val context = LocalContext.current
    var targetScheme by remember(artworkUrl) {
        val cached = artworkUrl?.takeIf(String::isNotBlank)?.let {
            synchronized(dynamicColorCache) { dynamicColorCache.get(it) }
        }
        mutableStateOf(cached ?: HorologistColors)
    }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) {
            targetScheme = HorologistColors
            return@LaunchedEffect
        }
        val existing = synchronized(dynamicColorCache) { dynamicColorCache.get(artworkUrl) }
        if (existing != null) {
            targetScheme = existing
            return@LaunchedEffect
        }
        val extracted = try {
            val bitmap = withContext(Dispatchers.IO) {
                val request = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .size(48, 48)
                    .allowHardware(false)
                    .build()
                val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                (drawable as? BitmapDrawable)?.bitmap
            }
            if (bitmap != null && !bitmap.isRecycled) {
                withContext(Dispatchers.Default) {
                    buildSchemeFromBitmap(bitmap)
                }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
        if (extracted != null) {
            synchronized(dynamicColorCache) { dynamicColorCache.put(artworkUrl, extracted) }
            targetScheme = extracted
        }
    }

    val spec = remember { tween<Color>(durationMillis = 360, easing = FastOutSlowInEasing) }
    val primary by animateColorAsState(targetScheme.primary, spec, label = "dynPrimary")
    val primaryDim by animateColorAsState(targetScheme.primaryDim, spec, label = "dynPrimaryDim")
    val primaryContainer by animateColorAsState(targetScheme.primaryContainer, spec, label = "dynPrimaryContainer")
    val onPrimary by animateColorAsState(targetScheme.onPrimary, spec, label = "dynOnPrimary")
    val surfaceContainer by animateColorAsState(targetScheme.surfaceContainer, spec, label = "dynSurface")
    val surfaceContainerHigh by animateColorAsState(targetScheme.surfaceContainerHigh, spec, label = "dynSurfaceHigh")

    return remember(primary, primaryDim, primaryContainer, onPrimary, surfaceContainer, surfaceContainerHigh) {
        HorologistColors.copy(
            primary = primary,
            primaryDim = primaryDim,
            primaryContainer = primaryContainer,
            onPrimary = onPrimary,
            onPrimaryContainer = Color.White,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            onSurface = Color(0xFFE6E1E5),
            onSurfaceVariant = Color(0xFFCAC4D0),
            background = Color.Black
        )
    }
}

private fun buildSchemeFromBitmap(bitmap: android.graphics.Bitmap): ColorScheme {
    val palette = Palette.from(bitmap).clearFilters().generate()
    val accentSwatch = palette.vibrantSwatch
        ?: palette.dominantSwatch
        ?: palette.lightVibrantSwatch
        ?: palette.darkVibrantSwatch
    val baseSwatch = palette.darkMutedSwatch
        ?: palette.mutedSwatch
        ?: palette.darkVibrantSwatch

    val accentArgb = accentSwatch?.rgb ?: 0xFFD0BCFF.toInt()
    val baseArgb = baseSwatch?.rgb ?: 0xFF2A2831.toInt()

    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(accentArgb, hsl)
    // 确保高对比度与饱满饱和度，在黑色 OLED 表盘上清晰醒目
    if (hsl[2] < 0.62f) hsl[2] = 0.66f
    if (hsl[1] < 0.42f) hsl[1] = 0.56f
    val brightAccentArgb = ColorUtils.HSLToColor(hsl)

    val primaryColor = Color(brightAccentArgb)
    val primaryDim = lerp(primaryColor, Color.Black, 0.20f)
    val baseColor = Color(baseArgb)
    val surfaceTone = lerp(baseColor, Color.Black, 0.45f)

    return HorologistColors.copy(
        primary = primaryColor,
        primaryDim = primaryDim,
        primaryContainer = lerp(surfaceTone, primaryColor, 0.35f),
        onPrimary = if (ColorUtils.calculateLuminance(brightAccentArgb) > 0.5) Color.Black else Color.White,
        onPrimaryContainer = Color.White,
        surfaceContainer = lerp(surfaceTone, Color.White, 0.12f),
        surfaceContainerHigh = lerp(surfaceTone, Color.White, 0.22f),
        onSurface = Color(0xFFE6E1E5),
        onSurfaceVariant = Color(0xFFCAC4D0),
        background = Color.Black
    )
}
