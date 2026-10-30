package com.applemusic.lyricstool.wear

import android.graphics.Matrix
import android.graphics.Path as AndroidPath
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.MutableCubic
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.pillStar
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import kotlin.math.min

/**
 * 官方 Google Horologist 专用矢量素材集合
 * 提取自 Horologist Material 3 规范与 LottiePlaceholders
 */
object HorologistIcons {
    val Play: ImageVector by lazy {
        materialIcon(name = "HorologistPlay") {
            materialPath {
                moveTo(18.072f, 13.04f)
                lineToRelative(-8.336f, 5.272f)
                curveToRelative(-1.3760f, 0.8160f, -2.1040f, 0.1760f, -2.0880f, -0.7680f)
                verticalLineTo(6.24f)
                curveToRelative(00f, -1.2720f, 0.680f, -1.6320f, 1.840f, -1.0080f)
                curveToRelative(0.160f, 0.0640f, 1.760f, 1.0960f, 3.5680f, 2.2160f)
                lineToRelative(4.984f, 3.112f)
                curveToRelative(1.1520f, 0.7920f, 1.1840f, 1.6640f, 0.0320f, 2.4960f)
                verticalLineToRelative(-0.016f)
                close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        materialIcon(name = "HorologistPause") {
            materialPath {
                moveTo(7.68f, 4.416f)
                horizontalLineToRelative(-0.008f)
                arcToRelative(2.16f, 2.16f, 0.0f, false, false, -2.16f, 2.16f)
                verticalLineToRelative(10.8f)
                arcToRelative(2.16f, 2.16f, 0.0f, false, false, 2.16f, 2.16f)
                horizontalLineToRelative(0.008f)
                arcToRelative(2.16f, 2.16f, 0.0f, false, false, 2.16f, -2.16f)
                verticalLineToRelative(-10.8f)
                arcToRelative(2.16f, 2.16f, 0.0f, false, false, -2.16f, -2.16f)
                close()
                moveTo(16.328f, 4.416f)
                horizontalLineToRelative(-0.008f)
                arcToRelative(2.16f, 2.16f, 0.0f, false, false, -2.16f, 2.16f)
                verticalLineToRelative(10.8f)
                arcToRelative(2.16f, 2.16f, 0.0f, false, false, 2.16f, 2.16f)
                horizontalLineToRelative(0.008f)
                arcToRelative(2.16f, 2.16f, 0.0f, false, false, 2.16f, -2.16f)
                verticalLineToRelative(-10.8f)
                arcToRelative(2.16f, 2.16f, 0.0f, false, false, -2.16f, -2.16f)
                close()
            }
        }
    }

    val Radio: ImageVector by lazy {
        materialIcon(name = "HorologistRadio") {
            materialPath {
                moveTo(3.24f, 6.15f)
                curveTo(2.51f, 6.43f, 2.0f, 7.17f, 2.0f, 8.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(8.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineTo(8.3f)
                lineToRelative(8.26f, -3.34f)
                curveToRelative(0.47f, -0.19f, 0.71f, -0.73f, 0.52f, -1.21f)
                curveToRelative(-0.19f, -0.48f, -0.73f, -0.71f, -1.21f, -0.52f)
                lineTo(3.24f, 6.15f)
                close()
                moveTo(7.0f, 20.0f)
                curveToRelative(-1.66f, 0.0f, -3.0f, -1.34f, -3.0f, -3.0f)
                reflectiveCurveToRelative(1.34f, -3.0f, 3.0f, -3.0f)
                reflectiveCurveToRelative(3.0f, 1.34f, 3.0f, 3.0f)
                reflectiveCurveToRelative(-1.34f, 3.0f, -3.0f, 3.0f)
                close()
                moveTo(20.0f, 12.0f)
                horizontalLineTo(4.0f)
                verticalLineTo(8.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(4.0f)
                close()
            }
        }
    }

    val Shuffle: ImageVector by lazy {
        materialIcon(name = "HorologistShuffle") {
            materialPath {
                moveTo(10.59f, 9.17f)
                lineTo(5.41f, 4.0f)
                lineTo(4.0f, 5.41f)
                lineToRelative(5.17f, 5.17f)
                lineToRelative(1.42f, -1.41f)
                close()
                moveTo(14.5f, 4.0f)
                lineToRelative(2.04f, 2.04f)
                lineTo(4.0f, 18.59f)
                lineTo(5.41f, 20.0f)
                lineTo(17.96f, 7.46f)
                lineTo(20.0f, 9.5f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-5.5f)
                close()
                moveTo(14.83f, 13.41f)
                lineToRelative(-1.41f, 1.41f)
                lineToRelative(3.13f, 3.13f)
                lineTo(14.5f, 20.0f)
                horizontalLineTo(20.0f)
                verticalLineToRelative(-5.5f)
                lineToRelative(-2.04f, 2.04f)
                lineToRelative(-3.13f, -3.13f)
                close()
            }
        }
    }

    val Favorite: ImageVector by lazy {
        materialIcon(name = "HorologistFavorite") {
            materialPath {
                moveTo(12.0f, 21.35f)
                lineToRelative(-1.45f, -1.32f)
                curveTo(5.4f, 15.36f, 2.0f, 12.28f, 2.0f, 8.5f)
                curveTo(2.0f, 5.42f, 4.42f, 3.0f, 7.5f, 3.0f)
                curveToRelative(1.74f, 0.0f, 3.41f, 0.81f, 4.5f, 2.09f)
                curveTo(13.09f, 3.81f, 14.76f, 3.0f, 16.5f, 3.0f)
                curveTo(19.58f, 3.0f, 22.0f, 5.42f, 22.0f, 8.5f)
                curveToRelative(0.0f, 3.78f, -3.4f, 6.86f, -8.55f, 11.54f)
                lineTo(12.0f, 21.35f)
                close()
            }
        }
    }

    val FavoriteBorder: ImageVector by lazy {
        materialIcon(name = "HorologistFavoriteBorder") {
            materialPath {
                moveTo(16.5f, 3.0f)
                curveToRelative(-1.74f, 0.0f, -3.41f, 0.81f, -4.5f, 2.09f)
                curveTo(10.91f, 3.81f, 9.24f, 3.0f, 7.5f, 3.0f)
                curveTo(4.42f, 3.0f, 2.0f, 5.42f, 2.0f, 8.5f)
                curveToRelative(0.0f, 3.78f, 3.4f, 6.86f, 8.55f, 11.54f)
                lineTo(12.0f, 21.35f)
                lineToRelative(1.45f, -1.32f)
                curveTo(18.6f, 15.36f, 22.0f, 12.28f, 22.0f, 8.5f)
                curveTo(22.0f, 5.42f, 19.58f, 3.0f, 16.5f, 3.0f)
                close()
                moveTo(12.1f, 18.55f)
                lineToRelative(-0.1f, 0.1f)
                lineToRelative(-0.1f, -0.1f)
                curveTo(7.14f, 14.24f, 4.0f, 11.39f, 4.0f, 8.5f)
                curveTo(4.0f, 6.5f, 5.5f, 5.0f, 7.5f, 5.0f)
                curveToRelative(1.54f, 0.0f, 3.04f, 0.99f, 3.57f, 2.36f)
                horizontalLineToRelative(1.87f)
                curveTo(13.46f, 5.99f, 14.96f, 5.0f, 16.5f, 5.0f)
                curveToRelative(2.0f, 0.0f, 3.5f, 1.5f, 3.5f, 3.5f)
                curveToRelative(0.0f, 2.89f, -3.14f, 5.74f, -7.9f, 10.05f)
                close()
            }
        }
    }

    val Download: ImageVector by lazy {
        materialIcon(name = "HorologistDownload") {
            materialPath {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(13.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
                moveTo(17.0f, 13.0f)
                lineToRelative(-5.0f, 5.0f)
                lineToRelative(-5.0f, -5.0f)
                horizontalLineToRelative(3.0f)
                verticalLineTo(9.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(3.0f)
                close()
            }
        }
    }

    val DownloadDone: ImageVector by lazy {
        materialIcon(name = "HorologistDownloadDone") {
            materialPath {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(13.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
                moveTo(10.0f, 17.0f)
                lineToRelative(-3.5f, -3.5f)
                lineToRelative(1.41f, -1.41f)
                lineTo(10.0f, 14.17f)
                lineToRelative(6.59f, -6.59f)
                lineTo(18.0f, 9.0f)
                lineToRelative(-8.0f, 8.0f)
                close()
            }
        }
    }
}

/**
 * 官方 Google Horologist 规范的 10-scallop 多边形与几何形生成器
 */
object HorologistShapes {
    fun pillPolygon(width: Float, height: Float): RoundedPolygon =
        RoundedPolygon(
            vertices = floatArrayOf(
                0f, -height / 2f,
                width / 2f, -height / 2f,
                width / 2f, 0f,
                width / 2f, height / 2f,
                0f, height / 2f,
                -width / 2f, height / 2f,
                -width / 2f, 0f,
                -width / 2f, -height / 2f
            ),
            rounding = CornerRounding(min(width / 2f, height / 2f)),
            centerX = 0f,
            centerY = 0f
        )

    fun outerScallopPolygon(density: Density, size: Dp): RoundedPolygon {
        with(density) {
            val cornerRadius = 10.dp.toPx()
            return RoundedPolygon.pillStar(
                numVerticesPerRadius = 10,
                width = size.toPx() / 2f,
                height = size.toPx() / 2f,
                innerRadiusRatio = 0.85f,
                rounding = CornerRounding(cornerRadius)
            )
        }
    }

    fun innerScallopPolygon(density: Density, size: Dp): RoundedPolygon {
        with(density) {
            val cornerRadius = 8.dp.toPx()
            return RoundedPolygon.pillStar(
                numVerticesPerRadius = 10,
                width = size.toPx() / 2f,
                height = size.toPx() / 2f,
                innerRadiusRatio = 0.82f,
                rounding = CornerRounding(cornerRadius)
            )
        }
    }

    fun RoundedPolygon.scaleToSize(sizePx: Float): RoundedPolygon {
        val bounds = calculateMaxBounds()
        val polyHeight = bounds[3] - bounds[1]
        val scaleFactor = if (polyHeight > 0f) sizePx / polyHeight else 1f
        return transformed { x, y -> TransformResult(x * scaleFactor, y * scaleFactor) }
    }
}

/**
 * 零临时分配的高性能 Morph Cubic Spline 路径提取扩展
 */
internal fun Morph.toPath(
    progress: Float,
    path: AndroidPath = AndroidPath(),
    mutableCubic: MutableCubic = MutableCubic(),
): AndroidPath {
    path.rewind()
    var firstX = 0f
    var firstY = 0f
    var prevControl0X = 0f
    var prevControl0Y = 0f
    var prevControl1X = 0f
    var prevControl1Y = 0f
    var prevAnchor1X = 0f
    var prevAnchor1Y = 0f
    var first = true

    forEachCubic(progress, mutableCubic) {
        if (first) {
            path.moveTo(it.anchor0X, it.anchor0Y)
            firstX = it.anchor0X
            firstY = it.anchor0Y
            first = false
        } else {
            path.cubicTo(
                prevControl0X, prevControl0Y,
                prevControl1X, prevControl1Y,
                prevAnchor1X, prevAnchor1Y
            )
        }
        prevControl0X = it.control0X
        prevControl0Y = it.control0Y
        prevControl1X = it.control1X
        prevControl1Y = it.control1Y
        prevAnchor1X = it.anchor1X
        prevAnchor1Y = it.anchor1Y
    }
    if (!first) {
        path.cubicTo(prevControl0X, prevControl0Y, prevControl1X, prevControl1Y, firstX, firstY)
    }
    path.close()
    return path
}

/**
 * 支持从圆形平滑渐变到 10 齿花瓣的动态 Shape
 */
class MorphedScallopShape(
    private val morph: Morph,
    private val morphProgress: State<Float>
) : Shape {
    private val matrix = Matrix()
    private val path = AndroidPath()
    private val mutableCubic = MutableCubic()

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        matrix.reset()
        matrix.postTranslate(size.width / 2f, size.height / 2f)
        val morphedPath = morph.toPath(morphProgress.value, path, mutableCubic)
        morphedPath.transform(matrix)
        return Outline.Generic(morphedPath.asComposePath())
    }
}

/**
 * Google Horologist 官方规约：变形波浪环形进度条（RotatingWavyProgressIndicator）
 * - 播放时：跟随中心按钮无缝同步变形为 10-scallop 波浪多边形，轨迹与已走进度严格围绕波浪花瓣外缘；
 * - 暂停时：平滑变形回正圆进度环；
 * - 缓冲准备时：呈现波浪环形流转光弧。
 */
@Composable
fun HorologistWavyProgressIndicator(
    progress: Float,
    isPreparing: Boolean,
    morphProgress: State<Float>,
    strokeWidth: Dp,
    indicatorColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val stroke = remember(density, strokeWidth) {
        Stroke(with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round)
    }

    val morph = remember(density) {
        val outerSizePx = with(density) { 68.dp.toPx() }
        val circlePolygon = HorologistShapes.pillPolygon(outerSizePx, outerSizePx)
        val outerScallop = HorologistShapes.outerScallopPolygon(density, 68.dp)
        val scaledScallop = with(HorologistShapes) { outerScallop.scaleToSize(outerSizePx) }
        Morph(circlePolygon, scaledScallop)
    }

    val infiniteTransition = rememberInfiniteTransition("PreparingSpin")
    val spinProgress = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Spin"
    )

    val indicatorPath = remember { Path() }
    val trackPath = remember { Path() }
    val androidPathInstance = remember { AndroidPath() }
    val mutableCubic = remember { MutableCubic() }
    val pathMeasurer = remember { PathMeasure() }

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val p = morphProgress.value
                val path = morph
                    .toPath(p, androidPathInstance, mutableCubic)
                    .asComposePath()
                    .apply { translate(Offset(size.width / 2f, size.height / 2f)) }

                pathMeasurer.setPath(path, true)
                val length = pathMeasurer.length
                val gap = (length * 0.008f) + stroke.width

                indicatorPath.reset()
                trackPath.reset()

                if (isPreparing) {
                    val spin = spinProgress.value
                    val arc = length * 0.28f
                    val start = (length * spin) % length
                    val stop = start + arc
                    if (stop <= length) {
                        pathMeasurer.getSegment(start, stop, indicatorPath)
                    } else {
                        pathMeasurer.getSegment(start, length, indicatorPath)
                        pathMeasurer.getSegment(0f, stop - length, indicatorPath)
                    }
                } else {
                    val coerced = progress.coerceIn(0f, 1f)
                    if (coerced <= 0.005f) {
                        pathMeasurer.getSegment(0f, length, trackPath)
                    } else if (coerced >= 0.995f) {
                        pathMeasurer.getSegment(0f, length, indicatorPath)
                    } else {
                        val stop = (length * coerced) - (gap / 2f)
                        pathMeasurer.getSegment(
                            startDistance = gap / 2f,
                            stopDistance = stop.coerceAtLeast(gap / 2f + 0.1f),
                            destination = indicatorPath
                        )
                        val start = (length * coerced) + (gap / 2f)
                        pathMeasurer.getSegment(
                            startDistance = start,
                            stopDistance = length - (gap / 2f),
                            destination = trackPath
                        )
                    }
                }

                onDrawBehind {
                    drawPath(path = trackPath, color = trackColor, style = stroke)
                    drawPath(path = indicatorPath, color = indicatorColor, style = stroke)
                }
            }
    )
}

/**
 * Google Horologist 官方规约播放/暂停按钮与变形多边形进度条组件：
 * 1. 播放状态下：中心按钮与外圈进度条同步平滑变形为 10 齿花瓣波浪形（Scallop Flower）；
 * 2. 暂停状态下：中心按钮与外圈进度条同步平滑收回为正圆形；
 * 3. 采用官方加粗圆角矢量 Play / Pause 图标，零掉帧 60fps 丝滑运行。
 */
@Composable
fun HorologistPlayPauseProgressButton(
    isPlaying: Boolean,
    isPreparing: Boolean,
    progress: Float,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    containerSize: Dp = 76.dp,
    buttonSize: Dp = 58.dp,
    strokeWidth: Dp = 3.5.dp
) {
    val density = LocalDensity.current

    val morphProgress = animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "ShapeMorphProgress"
    )

    val buttonMorph = remember(density) {
        val buttonSizePx = with(density) { buttonSize.toPx() }
        val circle = HorologistShapes.pillPolygon(buttonSizePx, buttonSizePx)
        val scallop = HorologistShapes.innerScallopPolygon(density, buttonSize)
        val scaledScallop = with(HorologistShapes) { scallop.scaleToSize(buttonSizePx) }
        Morph(circle, scaledScallop)
    }

    val buttonShape = remember(buttonMorph) {
        MorphedScallopShape(buttonMorph, morphProgress)
    }

    Box(
        modifier = modifier.size(containerSize),
        contentAlignment = Alignment.Center
    ) {
        // 外圈多边形变形进度条：播放时变形为 10 齿花瓣波浪形，暂停时变形为正圆形，与中心按钮完全谐调
        HorologistWavyProgressIndicator(
            progress = progress,
            isPreparing = isPreparing,
            morphProgress = morphProgress,
            strokeWidth = strokeWidth,
            indicatorColor = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
            modifier = Modifier.fillMaxSize()
        )

        // 中心主操作按钮：播放时同步变形为 10 齿花瓣，暂停时平滑变圆
        FilledIconButton(
            onClick = onToggle,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier
                .size(buttonSize)
                .clip(buttonShape)
        ) {
            Icon(
                imageVector = if (isPlaying) HorologistIcons.Pause else HorologistIcons.Play,
                contentDescription = if (isPlaying) "暂停" else "播放",
                modifier = Modifier.size(IconButtonDefaults.LargeIconSize)
            )
        }
    }
}
