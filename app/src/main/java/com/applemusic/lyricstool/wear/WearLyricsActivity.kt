package com.applemusic.lyricstool.wear

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import android.graphics.Path as AndroidPath
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.pillStar
import androidx.graphics.shapes.toPath
import com.applemusic.lyricstool.R
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CircularProgressIndicatorDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.OutlinedIconButton
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.applemusic.android.apple.AppleAlbum
import com.applemusic.android.apple.AppleArtist
import com.applemusic.android.apple.AppleArtistPage
import com.applemusic.android.apple.AppleCache
import com.applemusic.android.apple.AppleDownloader
import com.applemusic.android.apple.AppleLibrarySync
import com.applemusic.android.apple.AppleSong
import com.applemusic.android.apple.MusicRepository
import com.applemusic.android.apple.ShelfItem
import com.applemusic.android.lyrics.Lyrics
import com.applemusic.android.player.PlaybackEngine
import com.applemusic.android.util.Logx
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.applemusic.lyricstool.data.AppleLyricsClient
import com.applemusic.lyricstool.data.LyricsCache
import com.applemusic.lyricstool.data.QQMusicLyricsClient
import com.applemusic.lyricstool.data.SongHit
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 依照 Google Horologist (media/ui-material3 & media/sample) 架构重构的 Wear OS 媒体播放器。
 */
public val HorologistColors: ColorScheme = ColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color.Black,
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F)
)

private val Configuration.isLargeScreen: Boolean
    get() = screenHeightDp > 224

private fun Configuration.getScreenSizeInDpFromPercentage(percent: Float): Dp =
    ceil(screenHeightDp * percent / 100f).dp

class WearLyricsActivity : ComponentActivity() {
    private lateinit var tokenStore: EncryptedTokenStore
    private lateinit var client: AppleLyricsClient
    private var sessionRevision by mutableStateOf(0)
    private var startupError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("WearLyricsCrash", "Uncaught exception on thread $thread", throwable)
            Logx.e("WearLyricsCrash", "Crash: ${throwable.message}", throwable)
        }
        try {
            Logx.init(filesDir)
            tokenStore = EncryptedTokenStore(applicationContext)
            client = AppleLyricsClient(applicationContext, tokenStore)
            MusicRepository.init(
                applicationContext,
                WearAppleApiTokenStore(applicationContext, tokenStore)
            )
            try {
                MusicRepository.api.persist(client.userToken)
            } catch (e: Exception) {
                Logx.w("WearLyrics", "MusicRepository persist error: ${e.message}")
            }
            PlaybackEngine.init(applicationContext, WearMusicService::class.java)
            try {
                startService(Intent(this, WearMusicService::class.java))
            } catch (e: Exception) {
                Logx.w("WearLyrics", "Could not start service: ${e.message}")
            }
        } catch (e: Throwable) {
            android.util.Log.e("WearLyrics", "Startup error", e)
            startupError = "${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(5).joinToString("\n")}"
        }

        setContent {
            MaterialTheme(colorScheme = HorologistColors) {
                AppScaffold {
                    val error = startupError
                    if (error != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "应用启动错误：\n$error",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        HorologistWearApp(
                            client = client,
                            signedIn = client.isSignedIn,
                            transferError = tokenStore.transferError(),
                            sessionRevision = sessionRevision,
                            onSignOut = {
                                client.logout()
                                MusicRepository.api.logout()
                                sessionRevision++
                            },
                            onSessionChanged = {
                                MusicRepository.api.persist(client.userToken)
                                sessionRevision++
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::client.isInitialized) {
            client.reloadSavedSession()
            if (::tokenStore.isInitialized) MusicRepository.api.persist(client.userToken)
            WearRadioPlayback.restore(applicationContext, MusicRepository.api)
            sessionRevision++
        }
    }

    override fun onDestroy() {
        PlaybackEngine.onStateChanged = null
        PlaybackEngine.onPrefetchLyrics = null
        super.onDestroy()
    }
}

private enum class BrowseSubPage {
    Main,
    CurrentQueue,
    LibrarySongs,
    PlaylistsList,
    PlaylistDetail,
    Search,
    RadioStations,
    DownloadedSongs
}

private val dynamicColorCache = android.util.LruCache<String, ColorScheme>(16)

/**
 * 从歌曲封面图提取主强调色与背景色，动态生成 Material 3 配色方案。
 * 配备 LRU 内存缓存与 48x48 极低分辨率采样，杜绝切歌与滑动时的二次整树重组。
 */
@Composable
private fun rememberDynamicColorScheme(artworkUrl: String?): ColorScheme {
    val context = LocalContext.current
    val cached = artworkUrl?.let { dynamicColorCache.get(it) }
    var colorScheme by remember { mutableStateOf(cached ?: HorologistColors) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) {
            colorScheme = HorologistColors
            return@LaunchedEffect
        }
        val existing = dynamicColorCache.get(artworkUrl)
        if (existing != null) {
            colorScheme = existing
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            try {
                val loader = context.imageLoader
                val request = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .size(48, 48)
                    .allowHardware(false)
                    .build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    val palette = Palette.from(bitmap).clearFilters().generate()
                    val vibrant = palette.vibrantSwatch
                    val dominant = palette.dominantSwatch
                    val lightVibrant = palette.lightVibrantSwatch
                    val darkVibrant = palette.darkVibrantSwatch
                    val muted = palette.mutedSwatch
                    val darkMuted = palette.darkMutedSwatch

                    val accentSwatch = vibrant ?: dominant ?: lightVibrant ?: darkVibrant
                    val baseSwatch = darkMuted ?: muted ?: darkVibrant

                    val accentArgb = accentSwatch?.rgb ?: 0xFFD0BCFF.toInt()
                    val baseArgb = baseSwatch?.rgb ?: 0xFF2A2831.toInt()

                    val hsl = FloatArray(3)
                    ColorUtils.colorToHSL(accentArgb, hsl)
                    // 确保对比度与鲜艳度适合智能手表黑色底盘
                    if (hsl[2] < 0.62f) hsl[2] = 0.65f
                    if (hsl[1] < 0.40f) hsl[1] = 0.55f
                    val brightAccentArgb = ColorUtils.HSLToColor(hsl)

                    val primaryColor = Color(brightAccentArgb)
                    val primaryDim = lerp(primaryColor, Color.Black, 0.20f)
                    val baseColor = Color(baseArgb)
                    val surfaceTone = lerp(baseColor, Color.Black, 0.45f)

                    val newScheme = HorologistColors.copy(
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
                    dynamicColorCache.put(artworkUrl, newScheme)
                    withContext(Dispatchers.Main) {
                        colorScheme = newScheme
                    }
                }
            } catch (_: Throwable) {
                // 优雅保持现有方案
            }
        }
    }

    return colorScheme
}

/**
 * 遵循 Google Horologist media/ui-material3 的 ArtworkImageBackground 规范：
 * 采用 48x48 低分辨率采样 + GPU 硬件双线性插值自然羽化 + 径向暗角渐变（外圈深黑融入表圈）+ 动态色彩叠层。
 * 彻底移除高开销 GPU 高斯模糊滤镜（Modifier.blur），实现零掉帧 60fps 丝滑滑动手感。
 */
@Composable
internal fun HorologistArtworkBackground(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    colorScheme: ColorScheme = MaterialTheme.colorScheme
) {
    val context = LocalContext.current
    val imageRequest = remember(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) null
        else {
            ImageRequest.Builder(context)
                .data(artworkUrl)
                .size(48, 48) // 极低分辨率硬件纹理采样本，零内存与零解码压力
                .crossfade(true)
                .build()
        }
    }

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
                filterQuality = FilterQuality.Low, // GPU 硬件双线性插值自然柔化，零着色器开销
                alpha = 0.45f,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val radialBrush = Brush.radialGradient(
                            0.50f to Color.Transparent,
                            1f to colorScheme.background
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(color = colorScheme.primaryContainer, alpha = 0.20f)
                            drawRect(radialBrush)
                        }
                    }
            )
        }
    }
}

/**
 * 依照 Horologist 规范定制的全屏播放队列列表（Queue / Playlist）
 * 支持点击任意曲目即刻切歌、高亮当前播放歌曲（带强调色背景与指示），
 * 并提供快捷关闭返回播放器。
 */
@Composable
private fun HorologistQueueScreen(
    currentSong: SongHit?,
    onClose: () -> Unit,
    onPlayTrack: (Int) -> Unit
) {
    val queueUris = remember { PlaybackEngine.queueSnapshot() }
    val currentIndex = PlaybackEngine.currentQueueIndex()
    val listState = rememberTransformingLazyColumnState()

    BackHandler(onBack = onClose)

    Box(modifier = Modifier.fillMaxSize()) {
        HorologistArtworkBackground(artworkUrl = currentSong?.artworkUrl)

        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = onClose,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "返回",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "播放列表",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "共 ${queueUris.size} 首",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.size(36.dp))
                    }
                }

                if (queueUris.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "当前播放队列为空",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    itemsIndexed(queueUris) { index, uri ->
                        val row = MusicRepository.findSong(uri)
                        val title = row?.title?.ifBlank { null }
                            ?: (if (uri == currentSong?.id || uri == "apple:${currentSong?.id}") currentSong?.title else null)
                            ?: "歌曲 · ${uri.removePrefix("apple:")}"
                        val artist = row?.artist ?: (if (uri == currentSong?.id || uri == "apple:${currentSong?.id}") currentSong?.artist else "") ?: ""
                        val isCurrent = index == currentIndex

                        FilledTonalButton(
                            onClick = {
                                onPlayTrack(index)
                                onClose()
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isCurrent) "▶" else "%02d".format(index + 1),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.width(26.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (artist.isNotBlank()) {
                                        Text(
                                            text = artist,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HorologistWearApp(
    client: AppleLyricsClient,
    signedIn: Boolean,
    transferError: String?,
    sessionRevision: Int,
    onSignOut: () -> Unit,
    onSessionChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lyricsCache = remember(context) { LyricsCache(context) }
    val qqMusicClient = remember { QQMusicLyricsClient() }

    var librarySongs by remember { mutableStateOf(MusicRepository.allSongs().toList()) }
    var playlists by remember { mutableStateOf(MusicRepository.allPlaylists().toList()) }
    var selectedPlaylist by remember { mutableStateOf<AppleLibrarySync.PlaylistRow?>(null) }
    var playlistSongs by remember { mutableStateOf<List<AppleLibrarySync.SongRow>?>(null) }
    var playlistLoading by remember { mutableStateOf(false) }

    var selectedSong by remember { mutableStateOf<SongHit?>(null) }
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyricsStatus by remember { mutableStateOf("") }
    var lyricsRetryRevision by mutableLongStateOf(0L)

    var playbackRevision by remember { mutableLongStateOf(0L) }
    var statusMessage by remember { mutableStateOf(transferError.orEmpty()) }
    var syncing by remember { mutableStateOf(false) }
    var radioStarting by remember { mutableStateOf(false) }
    var viewingArtistName by remember { mutableStateOf<String?>(null) }
    var candidateArtists by remember { mutableStateOf<List<String>>(emptyList()) }

    val favoriteSongsPrefs = remember {
        context.getSharedPreferences("favorite_songs_cache", android.content.Context.MODE_PRIVATE)
    }
    var favoritedCatalogIds by remember {
        mutableStateOf(favoriteSongsPrefs.getStringSet("song_ids", emptySet())?.toSet() ?: emptySet())
    }
    var favoritePendingSongId by remember { mutableStateOf<String?>(null) }
    var downloadingSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var downloadProgressMap by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var downloadedRevision by remember { mutableIntStateOf(0) }

    var browseSubPage by remember { mutableStateOf(BrowseSubPage.Main) }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })

    LaunchedEffect(Unit) {
        PlaybackEngine.onStateChanged = { playbackRevision++ }
        PlaybackEngine.onPrefetchLyrics = { nextUri ->
            scope.launch(Dispatchers.IO) {
                try {
                    val row = MusicRepository.findSong(nextUri)
                    val rawId = row?.appleId?.takeIf { it.isNotEmpty() } ?: nextUri.removePrefix("apple:")
                    if (rawId.isBlank()) return@launch
                    val hit = row?.toSongHit() ?: SongHit(
                        id = rawId,
                        title = "",
                        artist = "",
                        album = "",
                        artworkUrl = "",
                        durationMs = 0L
                    )
                    if (lyricsCache.get("apple", hit.id) == null && lyricsCache.get("qq", hit.id) == null) {
                        val appleLyrics = client.fetchLyrics(hit.id)?.also {
                            lyricsCache.put("apple", hit.id, it)
                        }?.lyrics
                        if (appleLyrics == null || appleLyrics.wordTimedLines() == 0) {
                            val localized = try { client.fetchChineseSongMetadata(hit) } catch (_: Exception) { null }
                            qqMusicClient.fetchMatchingLyrics(
                                title = hit.title,
                                artist = hit.artist,
                                durationMs = hit.durationMs,
                                album = hit.album,
                                localizedAliases = listOfNotNull(localized)
                            )?.also { lyricsCache.put("qq", hit.id, it) }
                        }
                    }
                } catch (_: Throwable) {
                    // 后台预加载静默失败，不打扰主流程
                }
            }
        }
    }

    LaunchedEffect(sessionRevision, transferError) {
        if (!signedIn && !transferError.isNullOrBlank()) statusMessage = transferError
        librarySongs = MusicRepository.allSongs().toList()
        playlists = MusicRepository.allPlaylists().toList()
        val sf = MusicRepository.api.storefront
        if (sf.isNotBlank()) {
            client.storefront = sf
        }
    }

    LaunchedEffect(signedIn) {
        if (signedIn) {
            withContext(Dispatchers.IO) {
                try {
                    val ids = MusicRepository.api.getFavoriteCatalogSongIds()
                    if (ids.isNotEmpty()) {
                        favoritedCatalogIds = ids
                        favoriteSongsPrefs.edit().putStringSet("song_ids", ids).apply()
                    }
                } catch (e: Exception) {
                    Logx.w("WearLyrics", "获取收藏歌曲列表失败: ${e.message}")
                }
            }
        }
    }

    LaunchedEffect(selectedSong?.id) {
        val song = selectedSong ?: return@LaunchedEffect
        val cleanId = song.id.removePrefix("apple:")
        if (cleanId.startsWith("i.")) {
            withContext(Dispatchers.IO) {
                try {
                    val row = MusicRepository.findSong("apple:$cleanId") ?: MusicRepository.findSong(cleanId)
                    if (row != null && row.catalogId.isBlank()) {
                        val resolvedCatId = MusicRepository.api.librarySongCatalogId(cleanId)
                        if (resolvedCatId.isNotBlank()) {
                            row.catalogId = resolvedCatId
                            MusicRepository.registerTransient(listOf(row))
                            playbackRevision++
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val toggleSongFavorite: (SongHit) -> Unit = { song ->
        val catalogId = song.favoriteCatalogId()
        if (catalogId.isBlank()) {
            statusMessage = "无法确定歌曲曲库 ID"
        } else if (favoritePendingSongId == null) {
            val isFav = catalogId in favoritedCatalogIds
            val nextFav = !isFav
            favoritePendingSongId = catalogId
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        MusicRepository.api.setCatalogSongFavorite(catalogId, nextFav)
                        if (nextFav) {
                            try {
                                MusicRepository.api.addCatalogSongToLibrary(catalogId)
                            } catch (_: Exception) {}
                        }
                    }
                    val updatedIds = if (nextFav) favoritedCatalogIds + catalogId else favoritedCatalogIds - catalogId
                    favoritedCatalogIds = updatedIds
                    favoriteSongsPrefs.edit().putStringSet("song_ids", updatedIds).apply()
                    statusMessage = if (nextFav) "已添加到收藏与资料库" else "已从收藏中移除"
                } catch (e: Exception) {
                    statusMessage = "${if (nextFav) "收藏" else "取消收藏"}失败：${e.message ?: "网络超时"}"
                } finally {
                    favoritePendingSongId = null
                }
            }
        }
    }

    val downloadSong: (SongHit) -> Unit = { song ->
        if (downloadingSongIds.contains(song.id)) {
            val curProgress = downloadProgressMap[song.id] ?: 0
            statusMessage = "《${song.title}》正在下载中 ($curProgress%)"
        } else if (MusicRepository.cache.isSongDownloaded(song.id)) {
            statusMessage = "《${song.title}》已完成下载"
        } else {
            scope.launch {
                downloadingSongIds = downloadingSongIds + song.id
                downloadProgressMap = downloadProgressMap + (song.id to 0)
                statusMessage = "开始下载《${song.title}》…"
                try {
                    withContext(Dispatchers.IO) {
                        val isLib = song.id.startsWith("i.")
                        val meta = AppleCache.SongMetadata(
                            title = song.title,
                            artist = song.artist,
                            album = song.album
                        )
                        MusicRepository.cache.ensure(
                            song.id,
                            isLib,
                            onProgress = { stage, percent, _ ->
                                val stageLabel = when (stage) {
                                    AppleDownloader.Stage.RESOLVING -> "解析"
                                    AppleDownloader.Stage.LICENSING -> "鉴权"
                                    AppleDownloader.Stage.DOWNLOADING -> "下载"
                                    AppleDownloader.Stage.DECRYPTING -> "解密"
                                    AppleDownloader.Stage.WRITING -> "存储"
                                    AppleDownloader.Stage.DONE -> "完成"
                                    else -> "下载"
                                }
                                scope.launch(Dispatchers.Main) {
                                    downloadProgressMap = downloadProgressMap + (song.id to percent)
                                    statusMessage = "下载《${song.title}》: $stageLabel $percent%"
                                }
                            },
                            metadata = meta
                        )
                        MusicRepository.cache.setPinned(song.id, true)
                    }
                    downloadedRevision++
                    statusMessage = "《${song.title}》下载完成"
                } catch (e: Exception) {
                    statusMessage = "下载失败：${e.message ?: "网络超时"}"
                } finally {
                    downloadingSongIds = downloadingSongIds - song.id
                    downloadProgressMap = downloadProgressMap - song.id
                }
            }
        }
    }

    LaunchedEffect(playbackRevision) {
        val current = PlaybackEngine.currentSong() ?: return@LaunchedEffect
        val hit = current.toSongHit()
        if (selectedSong == null || selectedSong?.id != hit.id) {
            selectedSong = hit
        }
    }

    // 歌词加载逻辑
    LaunchedEffect(selectedSong?.id, lyricsRetryRevision) {
        val song = selectedSong
        if (song == null) {
            lyrics = null
            lyricsStatus = ""
            return@LaunchedEffect
        }
        lyrics = null
        lyricsLoading = true
        lyricsStatus = "正在加载歌词…"
        try {
            lyrics = withContext(Dispatchers.IO) {
                val cachedApple = lyricsCache.get("apple", song.id)?.lyrics
                if (cachedApple != null && cachedApple.wordTimedLines() > 0) return@withContext cachedApple
                val appleLyrics = cachedApple ?: client.fetchLyrics(song.id)?.also {
                    lyricsCache.put("apple", song.id, it)
                }?.lyrics
                if (appleLyrics != null && appleLyrics.wordTimedLines() > 0) return@withContext appleLyrics

                val cachedQq = lyricsCache.get("qq", song.id)?.lyrics
                if (cachedQq != null) return@withContext cachedQq
                val localized = try { client.fetchChineseSongMetadata(song) } catch (_: Exception) { null }
                val qqLyrics = try {
                    qqMusicClient.fetchMatchingLyrics(
                        title = song.title,
                        artist = song.artist,
                        durationMs = song.durationMs,
                        album = song.album,
                        localizedAliases = listOfNotNull(localized)
                    )?.also { lyricsCache.put("qq", song.id, it) }?.lyrics
                } catch (_: Exception) { null }
                qqLyrics ?: appleLyrics
            }
            lyricsStatus = if (lyrics == null) "暂无歌词" else ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lyricsStatus = "歌词加载失败：${e.message ?: "网络超时"}"
        } finally {
            lyricsLoading = false
        }
    }

    if (!signedIn) {
        AuthSetupScreen(
            client = client,
            transferError = transferError,
            onSessionChanged = onSessionChanged
        )
        return
    }

    if (candidateArtists.isNotEmpty()) {
        ArtistPickerDialog(
            artists = candidateArtists,
            onSelectArtist = { selected ->
                candidateArtists = emptyList()
                viewingArtistName = selected
            },
            onDismiss = {
                candidateArtists = emptyList()
            }
        )
        return
    }

    if (viewingArtistName != null) {
        HorologistArtistScreen(
            artistName = viewingArtistName!!,
            onClose = { viewingArtistName = null },
            onPlaySong = { song ->
                selectedSong = song
                playSongs(listOf(song), 0)
                viewingArtistName = null
                scope.launch { pagerState.animateScrollToPage(0) }
            },
            onPlayQueue = { queue, idx ->
                selectedSong = queue.getOrNull(idx)
                playSongs(queue, idx)
                viewingArtistName = null
                scope.launch { pagerState.animateScrollToPage(0) }
            },
            onOpenArtist = { newArtist ->
                viewingArtistName = newArtist
            }
        )
        return
    }

    val dynamicColorScheme = rememberDynamicColorScheme(selectedSong?.artworkUrl)

    MaterialTheme(colorScheme = dynamicColorScheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorologistArtworkBackground(artworkUrl = selectedSong?.artworkUrl)

            HorizontalPagerScaffold(
                pagerState = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageIndicator = {
                    HorizontalPageIndicator(
                        pagerState = pagerState,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            ) { page ->
                when (page) {
                0 -> HorologistPlayerScreen(
                    currentSong = selectedSong,
                    isFavorited = selectedSong?.let { song ->
                        val catId = song.favoriteCatalogId()
                        catId.isNotBlank() && catId in favoritedCatalogIds
                    } ?: false,
                    favoritePending = favoritePendingSongId != null,
                    onToggleFavorite = {
                        selectedSong?.let { song ->
                            toggleSongFavorite(song)
                        }
                    },
                    onOpenArtist = { artist ->
                        val list = splitArtists(artist)
                        if (list.size > 1) {
                            candidateArtists = list
                        } else {
                            viewingArtistName = list.firstOrNull() ?: artist
                        }
                    }
                )
            1 -> ModernHorologistLyricsScreen(
                song = selectedSong,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                lyricsStatus = lyricsStatus,
                onRetryLyrics = { lyricsRetryRevision++ },
                isVisible = (pagerState.currentPage == 1 || pagerState.targetPage == 1)
            )
            2 -> HorologistBrowseScreen(
                subPage = browseSubPage,
                onSubPageChange = { browseSubPage = it },
                client = client,
                librarySongs = librarySongs,
                playlists = playlists,
                selectedPlaylist = selectedPlaylist,
                playlistSongs = playlistSongs,
                playlistLoading = playlistLoading,
                radioStarting = radioStarting,
                onStartRadioStation = { stationId, stationTitle ->
                    if (!radioStarting) {
                        radioStarting = true
                        statusMessage = "正在启动${stationTitle}…"
                        scope.launch {
                            try {
                                val batch = startWearRadio(context, MusicRepository.api, stationId)
                                val firstSong = batch.rows.firstOrNull()?.toSongHit()
                                    ?: PlaybackEngine.currentSong()?.toSongHit()
                                if (firstSong != null) selectedSong = firstSong
                                browseSubPage = BrowseSubPage.Main
                                pagerState.animateScrollToPage(0)
                            } catch (e: Exception) {
                                statusMessage = "电台启动失败：${e.message ?: "网络超时"}"
                            } finally {
                                radioStarting = false
                            }
                        }
                    }
                },
                syncing = syncing,
                onSync = {
                    syncing = true
                    MusicRepository.syncAll { _, _ ->
                        Handler(Looper.getMainLooper()).post {
                            syncing = false
                            librarySongs = MusicRepository.allSongs().toList()
                            playlists = MusicRepository.allPlaylists().toList()
                        }
                    }
                },
                onSelectPlaylist = { pl ->
                    selectedPlaylist = pl
                    val local = MusicRepository.playlistSongs(pl.id)
                    if (local.isNotEmpty()) {
                        playlistSongs = local
                        playlistLoading = false
                    } else {
                        playlistSongs = null
                        playlistLoading = true
                        scope.launch {
                            try {
                                val tracks = withContext(Dispatchers.IO) {
                                    MusicRepository.api.getAnyPlaylistTracks(
                                        pl.id, 100, 0, pl.id.startsWith("p.")
                                    ).items.map(MusicRepository::rowOf)
                                }
                                playlistSongs = tracks
                            } catch (_: Exception) {
                                playlistSongs = emptyList()
                            } finally {
                                playlistLoading = false
                            }
                        }
                    }
                    browseSubPage = BrowseSubPage.PlaylistDetail
                },
                onPlaySong = { song ->
                    selectedSong = song
                    playSongs(listOf(song), 0)
                    browseSubPage = BrowseSubPage.Main
                    scope.launch { pagerState.animateScrollToPage(0) }
                },
                onPlayQueue = { queue, idx ->
                    selectedSong = queue.getOrNull(idx)
                    playSongs(queue, idx)
                    scope.launch { pagerState.animateScrollToPage(0) }
                },
                onDownloadSong = downloadSong,
                downloadingSongIds = downloadingSongIds,
                downloadProgressMap = downloadProgressMap,
                downloadedRevision = downloadedRevision,
                onClearCache = {
                    scope.launch {
                        try {
                            val removed = withContext(Dispatchers.IO) {
                                val count = MusicRepository.cache.clearTemporaryCacheOnly()
                                try { LyricsCache(context).clearAll() } catch (_: Exception) {}
                                try { context.imageLoader.memoryCache?.clear() } catch (_: Exception) {}
                                try { context.imageLoader.diskCache?.clear() } catch (_: Exception) {}
                                count
                            }
                            downloadedRevision++
                            statusMessage = "已清理 ${removed} 首临时缓存，已保留全部下载歌曲"
                        } catch (e: Exception) {
                            statusMessage = "清理缓存失败：${e.message}"
                        }
                    }
                },
                onSignOut = onSignOut
            )
        }
    }
}
}
}

/**
 * 严格遵照 Horologist media/ui-material3 的 PlayerScreen 布局规范：
 * - 顶部 TopSection：MediaInfoDisplay（单行 basicMarquee 歌名、副标题歌手）
 * - 中间 MiddleSection：HorologistPlayPauseProgressButton 居中，环绕外圈进度（播放/暂停均完整环绕），波浪花瓣变圆，两侧上下一首
 * - 底部 BottomSection：音频缓冲提示 或 官方 Material 3 风格快捷入口（歌词/曲库）
 */
@Composable
private fun HorologistPlayerScreen(
    currentSong: SongHit?,
    isFavorited: Boolean,
    favoritePending: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenArtist: (String) -> Unit
) {
    var showQueue by remember { mutableStateOf(false) }

    if (showQueue) {
        HorologistQueueScreen(
            currentSong = currentSong,
            onClose = { showQueue = false },
            onPlayTrack = { index ->
                PlaybackEngine.playAt(index)
                showQueue = false
            }
        )
        return
    }

    val configuration = LocalConfiguration.current
    val middleSectionMinimumHeight = remember(configuration) {
        if (configuration.isLargeScreen) 84.dp else 78.dp
    }
    val topSectionTopPadding = remember(configuration) {
        configuration.getScreenSizeInDpFromPercentage(
            if (configuration.isLargeScreen) 10f else 8f
        )
    }
    val topSectionBottomPadding = remember(configuration) {
        if (configuration.isLargeScreen) 8.dp else 6.dp
    }
    val middleSectionHeight = remember(configuration) {
        max(middleSectionMinimumHeight, configuration.screenHeightDp.dp * 0.36f)
    }
    val topSectionHeight = remember(configuration) {
        max(66.dp, (configuration.screenHeightDp.dp - middleSectionHeight) / 2f)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(PlaybackEngine.positionMs()) }
    var durationMs by remember { mutableLongStateOf(PlaybackEngine.durationMs()) }
    var isPlaying by remember { mutableStateOf(PlaybackEngine.isPlaying()) }
    var isPreparing by remember { mutableStateOf(PlaybackEngine.preparing) }
    var preparingNote by remember { mutableStateOf(PlaybackEngine.preparingNote) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isAppResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isAppResumed) {
        if (!isAppResumed) return@LaunchedEffect
        while (true) {
            val newPos = PlaybackEngine.positionMs()
            val newDur = PlaybackEngine.durationMs()
            val newPlaying = PlaybackEngine.isPlaying()
            val newPrep = PlaybackEngine.preparing
            val newNote = PlaybackEngine.preparingNote
            // 仅当值真正变化时才写入 State，避免无意义重组
            if (newPos != positionMs) positionMs = newPos
            if (newDur != durationMs) durationMs = newDur
            if (newPlaying != isPlaying) isPlaying = newPlaying
            if (newPrep != isPreparing) isPreparing = newPrep
            if (newNote != preparingNote) preparingNote = newNote
            delay(if (newPlaying || newPrep) 500L else 1000L)
        }
    }

    val progress = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    ScreenScaffold {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Section (Horologist MediaInfoDisplay)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(topSectionHeight)
                        .padding(top = topSectionTopPadding, bottom = topSectionBottomPadding)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = currentSong?.title ?: "未在播放",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(iterations = Int.MAX_VALUE, velocity = 28.dp)
                        )
                        val artist = currentSong?.artist
                        if (!artist.isNullOrBlank() && currentSong != null) {
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onOpenArtist(artist) }
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                                    .basicMarquee(iterations = Int.MAX_VALUE, velocity = 24.dp)
                            )
                        } else {
                            Text(
                                text = "请在右侧页面点播",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .basicMarquee(iterations = Int.MAX_VALUE, velocity = 24.dp)
                            )
                        }
                    }
                }

                // Middle Section (Horologist ButtonGroupLayout + HorologistPlayPauseProgressButton)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(middleSectionHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Previous Button (Horologist Rounded skip previous)
                        FilledTonalIconButton(
                            onClick = { PlaybackEngine.prev() },
                            modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.rounded_skip_previous_24),
                                contentDescription = "上一首",
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Play/Pause with Progress Ring & Scallop Flower (Both playing & paused have progress ring)
                        HorologistPlayPauseProgressButton(
                            isPlaying = isPlaying,
                            isPreparing = isPreparing,
                            progress = progress,
                            onToggle = {
                                PlaybackEngine.toggle()
                                isPlaying = PlaybackEngine.isPlaying()
                            }
                        )

                        // Next Button (Horologist Rounded skip next - PrimaryDim lavender accent)
                        FilledIconButton(
                            onClick = { PlaybackEngine.next() },
                            modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryDim,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.rounded_skip_next_24),
                                contentDescription = "下一首",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Bottom Section (Horologist Status / Material 3 Circular Quick Actions)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPreparing || (!isPlaying && preparingNote.isNotBlank())) {
                        Text(
                            text = preparingNote.ifBlank { "正在下载音频缓存…" },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (preparingNote.startsWith("无法播放") || preparingNote.contains("失败")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalIconButton(
                                onClick = { showQueue = true },
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_baseline_queue_music_24),
                                    contentDescription = "播放列表",
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            FilledTonalIconButton(
                                onClick = onToggleFavorite,
                                enabled = !favoritePending,
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = if (isFavorited) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                    contentColor = if (isFavorited) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                if (favoritePending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        colors = ProgressIndicatorDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary)
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isFavorited) HorologistIcons.Favorite else HorologistIcons.FavoriteBorder,
                                        contentDescription = if (isFavorited) "已收藏" else "收藏",
                                        tint = if (isFavorited) Color(0xFFFF4081) else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * Horologist 浏览与媒体库页（BrowseScreen / EntityScreen）
 * 采用 SwipeToDismissBox 支持系统级右滑回退。
 */
@Composable
private fun HorologistBrowseScreen(
    subPage: BrowseSubPage,
    onSubPageChange: (BrowseSubPage) -> Unit,
    client: AppleLyricsClient,
    librarySongs: List<AppleLibrarySync.SongRow>,
    playlists: List<AppleLibrarySync.PlaylistRow>,
    selectedPlaylist: AppleLibrarySync.PlaylistRow?,
    playlistSongs: List<AppleLibrarySync.SongRow>?,
    playlistLoading: Boolean,
    radioStarting: Boolean,
    onStartRadioStation: (String, String) -> Unit,
    syncing: Boolean,
    onSync: () -> Unit,
    onSelectPlaylist: (AppleLibrarySync.PlaylistRow) -> Unit,
    onPlaySong: (SongHit) -> Unit,
    onPlayQueue: (List<SongHit>, Int) -> Unit,
    onDownloadSong: (SongHit) -> Unit,
    downloadingSongIds: Set<String>,
    downloadProgressMap: Map<String, Int>,
    downloadedRevision: Int,
    onClearCache: () -> Unit,
    onSignOut: () -> Unit
) {
    when (subPage) {
        BrowseSubPage.Main -> BrowseMainMenu(
            onNavigate = onSubPageChange,
            radioStarting = radioStarting,
            syncing = syncing,
            onSync = onSync,
            downloadedCount = MusicRepository.cache.getDownloadedEntries().size,
            onClearCache = onClearCache,
            onSignOut = onSignOut
        )
        BrowseSubPage.RadioStations -> RadioStationsScreen(
            currentStationId = WearRadioPlayback.currentStationId,
            radioStarting = radioStarting,
            onBack = { onSubPageChange(BrowseSubPage.Main) },
            onSelectStation = onStartRadioStation
        )
        BrowseSubPage.CurrentQueue -> HorologistQueueScreen(
            currentSong = PlaybackEngine.currentSong()?.toSongHit(),
            onClose = { onSubPageChange(BrowseSubPage.Main) },
            onPlayTrack = { index ->
                PlaybackEngine.playAt(index)
                onSubPageChange(BrowseSubPage.Main)
            }
        )
        BrowseSubPage.LibrarySongs -> LibrarySongsList(
            songs = librarySongs,
            onBack = { onSubPageChange(BrowseSubPage.Main) },
            onPlaySong = onPlaySong,
            onPlayQueue = onPlayQueue
        )
        BrowseSubPage.PlaylistsList -> PlaylistsScreen(
            playlists = playlists,
            onBack = { onSubPageChange(BrowseSubPage.Main) },
            onSelect = onSelectPlaylist
        )
        BrowseSubPage.PlaylistDetail -> PlaylistDetailScreen(
            playlist = selectedPlaylist,
            tracks = playlistSongs,
            loading = playlistLoading,
            onBack = { onSubPageChange(BrowseSubPage.PlaylistsList) },
            onPlayQueue = onPlayQueue
        )
        BrowseSubPage.Search -> SearchScreen(
            client = client,
            onBack = { onSubPageChange(BrowseSubPage.Main) },
            onPlaySong = onPlaySong,
            onDownloadSong = onDownloadSong,
            downloadingSongIds = downloadingSongIds,
            downloadProgressMap = downloadProgressMap,
            downloadedRevision = downloadedRevision
        )
        BrowseSubPage.DownloadedSongs -> DownloadedSongsScreen(
            onBack = { onSubPageChange(BrowseSubPage.Main) },
            onPlaySong = onPlaySong,
            onPlayQueue = onPlayQueue
        )
    }
}

@Composable
private fun BrowseMainMenu(
    onNavigate: (BrowseSubPage) -> Unit,
    radioStarting: Boolean,
    syncing: Boolean,
    onSync: () -> Unit,
    downloadedCount: Int,
    onClearCache: () -> Unit,
    onSignOut: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)
        ) {
            item {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text("媒体库", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }

            item {
                FilledTonalButton(
                    onClick = { onNavigate(BrowseSubPage.RadioStations) },
                    enabled = !radioStarting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(HorologistIcons.Radio, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (radioStarting) "电台启动中…" else "个性电台", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                FilledTonalButton(
                    onClick = { onNavigate(BrowseSubPage.CurrentQueue) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_baseline_queue_music_24),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("当前播放列表", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                FilledTonalButton(
                    onClick = { onNavigate(BrowseSubPage.LibrarySongs) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("资料库歌曲", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                FilledTonalButton(
                    onClick = { onNavigate(BrowseSubPage.PlaylistsList) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.QueueMusic, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("我的歌单", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                FilledTonalButton(
                    onClick = { onNavigate(BrowseSubPage.Search) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("在线搜索", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                FilledTonalButton(
                    onClick = { onNavigate(BrowseSubPage.DownloadedSongs) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(HorologistIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (downloadedCount > 0) "已下载歌曲 ($downloadedCount)" else "已下载歌曲",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item {
                OutlinedButton(
                    enabled = !syncing,
                    onClick = onSync,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (syncing) "同步中…" else "全库刷新同步")
                }
            }

            item {
                OutlinedButton(
                    onClick = onClearCache,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("清理临时缓存", style = MaterialTheme.typography.labelSmall)
                }
            }

            item {
                TextButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("退出账号", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun LibrarySongsList(
    songs: List<AppleLibrarySync.SongRow>,
    onBack: () -> Unit,
    onPlaySong: (SongHit) -> Unit,
    onPlayQueue: (List<SongHit>, Int) -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    var visibleLimit by remember { mutableIntStateOf(10) }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("资料库 (${songs.size})", style = MaterialTheme.typography.titleSmall)
                }
            }

            val displayed = songs.take(visibleLimit)
            itemsIndexed(displayed) { index, row ->
                val songHit = row.toSongHit()
                Card(
                    onClick = {
                        val hits = displayed.map { it.toSongHit() }
                        onPlayQueue(hits, index)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(row.artist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledIconButton(
                            onClick = { onPlaySong(songHit) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "播放", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (songs.size > visibleLimit) {
                item {
                    OutlinedButton(
                        onClick = { visibleLimit += 15 },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Text("加载更多")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsScreen(
    playlists: List<AppleLibrarySync.PlaylistRow>,
    onBack: () -> Unit,
    onSelect: (AppleLibrarySync.PlaylistRow) -> Unit
) {
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("我的歌单 (${playlists.size})", style = MaterialTheme.typography.titleSmall)
                }
            }

            if (playlists.isEmpty()) {
                item {
                    Text(
                        "暂无歌单，可在上一页点击“全库刷新同步”",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                    )
                }
            }

            itemsIndexed(playlists) { _, playlist ->
                Card(
                    onClick = { onSelect(playlist) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("点击查看曲目", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailScreen(
    playlist: AppleLibrarySync.PlaylistRow?,
    tracks: List<AppleLibrarySync.SongRow>?,
    loading: Boolean,
    onBack: () -> Unit,
    onPlayQueue: (List<SongHit>, Int) -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    var visibleLimit by remember { mutableIntStateOf(10) }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(playlist?.name ?: "歌单", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        Text("正在加载歌单内容…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else if (tracks.isNullOrEmpty()) {
                item {
                    Text("歌单内暂无可播放歌曲", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp))
                }
            } else {
                val displayed = tracks.take(visibleLimit)
                itemsIndexed(displayed) { index, row ->
                    Card(
                        onClick = {
                            val hits = displayed.map { it.toSongHit() }
                            onPlayQueue(hits, index)
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(row.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(row.artist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                if (tracks.size > visibleLimit) {
                    item {
                        OutlinedButton(
                            onClick = { visibleLimit += 15 },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text("加载更多")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    client: AppleLyricsClient,
    onBack: () -> Unit,
    onPlaySong: (SongHit) -> Unit,
    onDownloadSong: (SongHit) -> Unit,
    downloadingSongIds: Set<String>,
    downloadProgressMap: Map<String, Int>,
    downloadedRevision: Int
) {
    val listState = rememberTransformingLazyColumnState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchByLyrics by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SongHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchStatus by remember { mutableStateOf("") }

    val onPerformSearch: () -> Unit = {
        if (query.isNotBlank()) {
            searching = true
            searchStatus = ""
            scope.launch {
                try {
                    results = withContext(Dispatchers.IO) {
                        val trimmed = query.trim()
                        if (searchByLyrics) {
                            val qqHits = QQMusicLyricsClient().searchSongsByLyrics(trimmed).take(6)
                            val merged = ArrayList<SongHit>()
                            val seenIds = HashSet<String>()
                            for (hit in qqHits) {
                                val term = "${hit.title} ${hit.artist}".trim()
                                val catalog = MusicRepository.searchCatalog(term, 5)
                                for (s in catalog.songs) {
                                    if (seenIds.add(s.id)) {
                                        merged.add(
                                            SongHit(
                                                id = s.id,
                                                title = s.name,
                                                artist = s.artistName,
                                                album = s.albumName,
                                                artworkUrl = s.artworkUrl,
                                                durationMs = s.durationMs,
                                                providerSongId = s.id
                                            )
                                        )
                                    }
                                }
                                if (merged.size >= 15) break
                            }
                            merged
                        } else {
                            val catalog = MusicRepository.searchCatalog(trimmed, 30)
                            val local = MusicRepository.searchLocal(trimmed)
                            val merged = ArrayList<SongHit>()
                            val seenIds = HashSet<String>()
                            for (s in local.songs) {
                                if (seenIds.add(s.id)) {
                                    merged.add(
                                        SongHit(
                                            id = s.id,
                                            title = s.name,
                                            artist = s.artistName,
                                            album = s.albumName,
                                            artworkUrl = s.artworkUrl,
                                            durationMs = s.durationMs,
                                            providerSongId = s.id
                                        )
                                    )
                                }
                            }
                            for (s in catalog.songs) {
                                if (seenIds.add(s.id)) {
                                    merged.add(
                                        SongHit(
                                            id = s.id,
                                            title = s.name,
                                            artist = s.artistName,
                                            album = s.albumName,
                                            artworkUrl = s.artworkUrl,
                                            durationMs = s.durationMs,
                                            providerSongId = s.id
                                        )
                                    )
                                }
                            }
                            merged
                        }
                    }
                    if (results.isEmpty()) searchStatus = "没有找到匹配歌曲"
                } catch (e: Exception) {
                    searchStatus = "搜索失败：${e.message ?: "网络超时"}"
                } finally {
                    searching = false
                }
            }
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("搜索", style = MaterialTheme.typography.titleSmall)
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("输入歌名/歌手") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedContainerColor = Color(0xFF1E1E1E),
                        unfocusedContainerColor = Color(0xFF1E1E1E)
                    ),
                    singleLine = true
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalButton(
                        onClick = onPerformSearch,
                        enabled = !searching && query.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (searching) "搜索中…" else "搜索")
                    }
                    OutlinedButton(
                        onClick = { searchByLyrics = !searchByLyrics },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (searchByLyrics) "搜歌词:开" else "搜歌词:关", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (searchStatus.isNotBlank()) {
                item {
                    Text(
                        searchStatus,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                }
            }

            itemsIndexed(results) { _, song ->
                val isDownloaded = remember(song.id, downloadedRevision) {
                    MusicRepository.cache.isSongDownloaded(song.id)
                }
                val isDownloading = downloadingSongIds.contains(song.id)

                Card(
                    onClick = { onPlaySong(song) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        if (isDownloading) {
                            val percent = downloadProgressMap[song.id] ?: 0
                            Box(
                                modifier = Modifier.size(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = { (percent / 100f).coerceIn(0.05f, 1f) },
                                    modifier = Modifier.fillMaxSize(),
                                    strokeWidth = 2.dp,
                                    colors = ProgressIndicatorDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary)
                                )
                                Text(
                                    text = "$percent%",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (isDownloaded) {
                            Icon(
                                imageVector = HorologistIcons.DownloadDone,
                                contentDescription = "已下载",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            FilledTonalIconButton(
                                onClick = { onDownloadSong(song) },
                                modifier = Modifier.size(28.dp),
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
                        Spacer(modifier = Modifier.width(4.dp))
                        FilledIconButton(
                            onClick = { onPlaySong(song) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "播放", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

private enum class DownloadCategory {
    All,
    ByArtist,
    ByAlbum
}

@Composable
private fun DownloadedSongsScreen(
    onBack: () -> Unit,
    onPlaySong: (SongHit) -> Unit,
    onPlayQueue: (List<SongHit>, Int) -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    var category by remember { mutableStateOf(DownloadCategory.All) }
    var downloadedEntries by remember { mutableStateOf<List<AppleCache.Entry>>(emptyList()) }
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) {
            downloadedEntries = MusicRepository.cache.getDownloadedEntries()
        }
    }

    val formatSize: (Long) -> String = { bytes ->
        if (bytes <= 0L) "0 MB"
        else "%.1f MB".format(bytes / (1024f * 1024f))
    }

    val totalBytes = remember(downloadedEntries) {
        downloadedEntries.sumOf { it.size }
    }

    val entryToHit: (AppleCache.Entry) -> SongHit = { e ->
        SongHit(
            id = e.songId,
            title = e.title.ifBlank { "未知歌曲" },
            artist = e.artist.ifBlank { "未知歌手" },
            album = e.album,
            artworkUrl = "",
            durationMs = 0L,
            providerSongId = e.songId
        )
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            if (selectedArtist != null) {
                                selectedArtist = null
                            } else if (selectedAlbum != null) {
                                selectedAlbum = null
                            } else {
                                onBack()
                            }
                        },
                        modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            selectedArtist != null -> selectedArtist!!
                            selectedAlbum != null -> selectedAlbum!!
                            else -> "已下载歌曲"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (selectedArtist == null && selectedAlbum == null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            DownloadCategory.All to "全部 (${downloadedEntries.size})",
                            DownloadCategory.ByArtist to "按歌手",
                            DownloadCategory.ByAlbum to "按专辑"
                        ).forEach { (cat, label) ->
                            val isSelected = category == cat
                            FilledTonalButton(
                                onClick = { category = cat },
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .height(30.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            if (downloadedEntries.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = HorologistIcons.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "暂无已下载歌曲\n在搜索页可点击下载图标离线保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                when {
                    selectedArtist != null -> {
                        val artistSongs = downloadedEntries.filter { (it.artist.ifBlank { "未知歌手" }) == selectedArtist }
                        val hits = artistSongs.map(entryToHit)
                        item {
                            DownloadPlayButtonGroup(
                                hits = hits,
                                onPlayQueue = onPlayQueue
                            )
                        }
                        itemsIndexed(artistSongs) { _, entry ->
                            DownloadedSongItem(
                                entry = entry,
                                formatSize = formatSize,
                                onPlay = { onPlaySong(entryToHit(entry)) },
                                onDelete = {
                                    MusicRepository.cache.deleteDownloadedSong(entry.songId)
                                    refreshKey++
                                }
                            )
                        }
                    }
                    selectedAlbum != null -> {
                        val albumSongs = downloadedEntries.filter { (it.album.ifBlank { "未知专辑" }) == selectedAlbum }
                        val hits = albumSongs.map(entryToHit)
                        item {
                            DownloadPlayButtonGroup(
                                hits = hits,
                                onPlayQueue = onPlayQueue
                            )
                        }
                        itemsIndexed(albumSongs) { _, entry ->
                            DownloadedSongItem(
                                entry = entry,
                                formatSize = formatSize,
                                onPlay = { onPlaySong(entryToHit(entry)) },
                                onDelete = {
                                    MusicRepository.cache.deleteDownloadedSong(entry.songId)
                                    refreshKey++
                                }
                            )
                        }
                    }
                    category == DownloadCategory.ByArtist -> {
                        val artistsMap = downloadedEntries.groupBy { it.artist.ifBlank { "未知歌手" } }
                        artistsMap.forEach { (artist, songs) ->
                            item {
                                Card(
                                    onClick = { selectedArtist = artist },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${songs.size} 首歌曲", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                    category == DownloadCategory.ByAlbum -> {
                        val albumsMap = downloadedEntries.groupBy { it.album.ifBlank { "未知专辑" } }
                        albumsMap.forEach { (album, songs) ->
                            item {
                                Card(
                                    onClick = { selectedAlbum = album },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(album, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${songs.size} 首歌曲", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val allHits = downloadedEntries.map(entryToHit)
                        item {
                            DownloadPlayButtonGroup(
                                hits = allHits,
                                onPlayQueue = onPlayQueue
                            )
                        }
                        item {
                            ListHeader(modifier = Modifier.fillMaxWidth()) {
                                Text("全部离线 (${downloadedEntries.size}首 · ${formatSize(totalBytes)})", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        itemsIndexed(downloadedEntries) { _, entry ->
                            DownloadedSongItem(
                                entry = entry,
                                formatSize = formatSize,
                                onPlay = { onPlaySong(entryToHit(entry)) },
                                onDelete = {
                                    MusicRepository.cache.deleteDownloadedSong(entry.songId)
                                    refreshKey++
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadPlayButtonGroup(
    hits: List<SongHit>,
    onPlayQueue: (List<SongHit>, Int) -> Unit
) {
    if (hits.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = { onPlayQueue(hits, 0) },
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("播放全部", style = MaterialTheme.typography.labelMedium)
            }
        }
        FilledTonalButton(
            onClick = { onPlayQueue(hits.shuffled(), 0) },
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = HorologistIcons.Shuffle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("随机", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DownloadedSongItem(
    entry: AppleCache.Entry,
    formatSize: (Long) -> String,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onPlay,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title.ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.artist.ifBlank { "未知歌手" }} · ${formatSize(entry.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalIconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = "删除下载", modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "播放", modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * 智能拆分多艺人字符串。
 * 兼容处理常见中英文艺人连接符：
 * 英文斜杠 "/"、中文顿号 "、"、英文逗号 ","、中文逗号 "，"、和号 "&"、加号 "+"、
 * 以及 "feat." / "ft." / "with" / "x" 等。
 * 保留如 "AC/DC" 等特例乐团名称不拆分。
 */
private fun splitArtists(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val trimmed = raw.trim()
    if (trimmed.equals("AC/DC", ignoreCase = true)) {
        return listOf(trimmed)
    }
    val delimiterRegex = Regex("""(?:\s*(?:/|&|、|,|，|\+)\s*|\s+(?:[fF][tT]\.?|[fF][eE][aA][tT]\.?|[wW][iI][tT][hH]|[xX])\s+)""")
    val parts = trimmed.split(delimiterRegex)
        .map { it.trim().trim('\"', '\'') }
        .filter { it.isNotBlank() }
        .distinct()
    return if (parts.isEmpty()) listOf(trimmed) else parts
}

/**
 * 当歌曲包含多位艺人时弹出的选择对话框 (遵循 Horologist 规范)
 */
@Composable
private fun ArtistPickerDialog(
    artists: List<String>,
    onSelectArtist: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "取消")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("选择艺人", style = MaterialTheme.typography.titleSmall)
                }
            }

            itemsIndexed(artists) { _, artist ->
                FilledTonalButton(
                    onClick = { onSelectArtist(artist) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Audiotrack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * 歌手主页 (严格遵循 Google Horologist Material 3 规范与圆形表盘布局)
 */
@Composable
private fun HorologistArtistScreen(
    artistName: String,
    onClose: () -> Unit,
    onPlaySong: (SongHit) -> Unit,
    onPlayQueue: (List<SongHit>, Int) -> Unit,
    onOpenArtist: (String) -> Unit
) {
    BackHandler(onBack = onClose)

    val scope = rememberCoroutineScope()
    val listState = rememberTransformingLazyColumnState()
    val parsedArtists = remember(artistName) { splitArtists(artistName) }
    var currentArtist by remember(artistName) { mutableStateOf(parsedArtists.firstOrNull() ?: artistName) }
    var artistPage by remember { mutableStateOf<AppleArtistPage?>(null) }
    var topSongs by remember { mutableStateOf<List<AppleSong>>(emptyList()) }
    var topSongsOffset by remember { mutableIntStateOf(0) }
    var hasMoreTopSongs by remember { mutableStateOf(true) }
    var loadingMoreTopSongs by remember { mutableStateOf(false) }
    var resolvedArtistId by remember { mutableStateOf("") }
    var localSongs by remember { mutableStateOf<List<AppleLibrarySync.SongRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val loadMoreTopSongs: () -> Unit = {
        if (!loadingMoreTopSongs && hasMoreTopSongs && resolvedArtistId.isNotBlank()) {
            loadingMoreTopSongs = true
            scope.launch {
                try {
                    val more = withContext(Dispatchers.IO) {
                        MusicRepository.api.getCatalogArtistTopSongs(
                            resolvedArtistId,
                            limit = 5,
                            offset = topSongsOffset
                        )
                    }
                    if (more.isEmpty()) {
                        hasMoreTopSongs = false
                    } else {
                        topSongs = topSongs + more
                        topSongsOffset += more.size
                        if (more.size < 5) {
                            hasMoreTopSongs = false
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    loadingMoreTopSongs = false
                }
            }
        }
    }

    LaunchedEffect(currentArtist) {
        loading = true
        errorMessage = null
        topSongs = emptyList()
        topSongsOffset = 0
        hasMoreTopSongs = true
        withContext(Dispatchers.IO) {
            try {
                // 1. 本地资料库中的歌曲
                localSongs = MusicRepository.songsOfArtist(currentArtist)

                // 2. 解析 Artist ID
                val api = MusicRepository.api
                val localSongWithArtistId = localSongs.firstOrNull { it.artistId.isNotBlank() }
                var resolvedId = localSongWithArtistId?.artistId.orEmpty()

                if (resolvedId.isBlank()) {
                    val anyCatalogId = localSongs.firstOrNull { it.catalogId.isNotBlank() }?.catalogId.orEmpty()
                    if (anyCatalogId.isNotBlank()) {
                        resolvedId = api.getCatalogSongArtistId(anyCatalogId)
                    }
                }

                if (resolvedId.isBlank()) {
                    val searchArtists = api.searchArtistsOnly(currentArtist, 5)
                    resolvedId = searchArtists.firstOrNull {
                        it.name.equals(currentArtist, ignoreCase = true)
                    }?.id.orEmpty().ifBlank {
                        searchArtists.firstOrNull()?.id.orEmpty()
                    }
                }

                resolvedArtistId = resolvedId
                if (resolvedId.isNotBlank()) {
                    val page = api.getCatalogArtistPage(resolvedId)
                    artistPage = page
                    val initialSongs = api.getCatalogArtistTopSongs(resolvedId, limit = 5, offset = 0)
                    topSongs = if (initialSongs.isNotEmpty()) initialSongs else page.topSongs.take(5)
                    topSongsOffset = topSongs.size
                    hasMoreTopSongs = topSongs.size >= 5
                } else if (localSongs.isEmpty()) {
                    errorMessage = "未在 Apple Music 找到艺人：$currentArtist"
                }
            } catch (e: Exception) {
                if (localSongs.isEmpty()) {
                    errorMessage = "加载歌手页失败：${e.message ?: "网络超时"}"
                }
            } finally {
                loading = false
            }
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)
        ) {
            // 顶部导航栏
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = onClose,
                        modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("歌手", style = MaterialTheme.typography.titleSmall)
                }
            }

            // 多艺人快速切换栏 (当歌曲包含多位艺人时，可在顶部一键快速切换各艺人主页)
            if (parsedArtists.size > 1) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        parsedArtists.forEach { artist ->
                            val isSelected = artist.equals(currentArtist, ignoreCase = true)
                            FilledTonalButton(
                                onClick = { currentArtist = artist },
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .height(32.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Text(
                                    text = artist,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // 艺人官方头像与标题 (Horologist Entity Hero Header)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val avatarUrl = artistPage?.artist?.artworkUrl?.ifBlank { null }
                        ?: localSongs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl
                    if (!avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = artistPage?.artist?.name ?: currentArtist,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val genre = artistPage?.artist?.genreNames?.take(2)?.joinToString(" / ")
                    if (!genre.isNullOrBlank()) {
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 加载中提示
            if (loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "正在载入歌手精选…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 错误提示
            if (errorMessage != null && localSongs.isEmpty()) {
                item {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                    )
                }
            }

            // 播放全部与随机播放按钮 (Horologist Media Button Group)
            val allPlayable = if (topSongs.isNotEmpty()) {
                topSongs.map { it.toSongHit() }
            } else {
                localSongs.map { it.toSongHit() }
            }

            if (allPlayable.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                onPlayQueue(allPlayable, 0)
                                onClose()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("播放", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                onPlayQueue(allPlayable.shuffled(), 0)
                                onClose()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = HorologistIcons.Shuffle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("随机", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // 热门单曲 (Top Songs)
            if (topSongs.isNotEmpty()) {
                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("热门歌曲 (${topSongs.size})", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(topSongs) { index, song ->
                    val hit = song.toSongHit()
                    Card(
                        onClick = {
                            val hits = topSongs.map { it.toSongHit() }
                            onPlayQueue(hits, index)
                            onClose()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "%02d".format(index + 1),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (song.albumName.isNotBlank()) {
                                    Text(
                                        text = song.albumName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            FilledIconButton(
                                onClick = {
                                    onPlaySong(hit)
                                    onClose()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "播放",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                if (hasMoreTopSongs) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (loadingMoreTopSongs) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    colors = ProgressIndicatorDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary)
                                )
                            } else {
                                OutlinedButton(
                                    onClick = loadMoreTopSongs,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                ) {
                                    Text("点击加载更多 (5首)", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            // 精选专辑 (Albums)
            val albums = artistPage?.albums.orEmpty()
            if (albums.isNotEmpty()) {
                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("精选专辑 (${albums.size})", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(albums.take(10)) { _, album ->
                    Card(
                        onClick = {
                            loading = true
                            scope.launch {
                                try {
                                    val tracks = withContext(Dispatchers.IO) {
                                        MusicRepository.api.getCatalogAlbumTracks(album.id)
                                    }
                                    if (tracks.isNotEmpty()) {
                                        val hits = tracks.map { it.toSongHit() }
                                        onPlayQueue(hits, 0)
                                        onClose()
                                    }
                                } catch (_: Exception) {
                                } finally {
                                    loading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (album.artworkUrl.isNotBlank()) {
                                AsyncImage(
                                    model = album.artworkUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = album.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val yearStr = if (album.year > 0) "${album.year}年" else ""
                                val trackStr = if (album.trackCount > 0) " · ${album.trackCount}首" else ""
                                Text(
                                    text = "$yearStr$trackStr".trimStart(' ', '·'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 资料库已有曲目 (In Library)
            if (localSongs.isNotEmpty() && topSongs.isEmpty()) {
                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("资料库曲目 (${localSongs.size})", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(localSongs) { index, row ->
                    val hit = row.toSongHit()
                    Card(
                        onClick = {
                            val hits = localSongs.map { it.toSongHit() }
                            onPlayQueue(hits, index)
                            onClose()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(row.album, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FilledIconButton(
                                onClick = {
                                    onPlaySong(hit)
                                    onClose()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "播放", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // 歌手简介
            val desc = artistPage?.artist?.editorialDescription?.ifBlank { null }
            if (!desc.isNullOrBlank()) {
                val cleanDesc = desc.replace(Regex("<[^>]*>"), "").trim()
                if (cleanDesc.isNotBlank()) {
                    item {
                        ListHeader(modifier = Modifier.fillMaxWidth()) {
                            Text("歌手简介", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    item {
                        Card(
                            onClick = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = cleanDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }

            // 相似艺人
            val similar = artistPage?.similarArtists.orEmpty()
            if (similar.isNotEmpty()) {
                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("相似艺人", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(similar.take(6)) { _, simArtist ->
                    Card(
                        onClick = {
                            currentArtist = simArtist.name
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (simArtist.artworkUrl.isNotBlank()) {
                                AsyncImage(
                                    model = simArtist.artworkUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = simArtist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class PresetStation(
    val id: String,
    val title: String,
    val subtitle: String
)

private val CorePresetStations = listOf(
    PresetStation("ra.u-personal", "我的个性电台", "根据你的音乐喜好量身定制"),
    PresetStation("ra.u-discovery", "新发现电台", "探索未曾听过的宝藏新歌"),
    PresetStation("ra.985499298", "华语流行电台", "经典与当红华语流行热歌"),
    PresetStation("ra.985499304", "粤语流行电台", "经典金曲与新生代粤语流行"),
    PresetStation("ra.985484166", "Apple Music 1", "全球旗舰流行音乐电台"),
    PresetStation("ra.1466858884", "Apple Music Hits", "80/90/00年代经典热门"),
    PresetStation("ra.1466858885", "Apple Music Country", "经典与现代乡村音乐"),
    PresetStation("ra.985499285", "轻松放松电台", "放空心绪，伴你惬意放松"),
    PresetStation("ra.985499278", "经典老歌电台", "跨越时代的黄金时代旋律"),
    PresetStation("ra.985499280", "运动能量电台", "律动节拍，为运动注入能量")
)

/**
 * 个性电台与推荐电台列表界面 (遵照 Horologist Material 3 规范)
 */
@Composable
private fun RadioStationsScreen(
    currentStationId: String,
    radioStarting: Boolean,
    onBack: () -> Unit,
    onSelectStation: (String, String) -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    var recommendedStations by remember { mutableStateOf<List<ShelfItem>>(emptyList()) }
    var loadingRecommendations by remember { mutableStateOf(true) }
    var selectedStationId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val shelves = MusicRepository.api.getRecommendations()
                val stationShelves = MusicRepository.api.stationsFrom(shelves)
                val items = ArrayList<ShelfItem>()
                val seen = HashSet<String>()
                for (shelf in stationShelves) {
                    for (item in shelf.items) {
                        if (item.isStation && seen.add(item.id)) {
                            items.add(item)
                        }
                    }
                }
                recommendedStations = items
            } catch (_: Exception) {
                // 网络异常时不影响精选预设电台
            } finally {
                loadingRecommendations = false
            }
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("个性电台", style = MaterialTheme.typography.titleSmall)
                }
            }

            if (radioStarting && selectedStationId != null) {
                item {
                    Text(
                        text = "正在启动电台…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // 1. 专属推荐电台（动态从 Apple Music API recommendations 获取）
            if (loadingRecommendations) {
                item {
                    Text(
                        text = "加载推荐电台中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else if (recommendedStations.isNotEmpty()) {
                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("专属推荐", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(recommendedStations) { _, station ->
                    val isPlaying = station.id == currentStationId
                    val isStarting = radioStarting && station.id == selectedStationId
                    Card(
                        onClick = {
                            selectedStationId = station.id
                            onSelectStation(station.id, station.title)
                        },
                        enabled = !radioStarting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (station.artwork.isNotBlank()) {
                                AsyncImage(
                                    model = station.artwork,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = station.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (station.subtitle.isNotBlank()) {
                                    Text(
                                        text = station.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (isPlaying) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "播放中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (isStarting) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "启动中…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // 2. 精选与风格电台（CorePresetStations，去重）
            val recIds = recommendedStations.map { it.id }.toSet()
            val availablePresets = CorePresetStations.filter { it.id !in recIds }

            if (availablePresets.isNotEmpty()) {
                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("精选风格", style = MaterialTheme.typography.labelMedium)
                    }
                }
                itemsIndexed(availablePresets) { _, preset ->
                    val isPlaying = preset.id == currentStationId
                    val isStarting = radioStarting && preset.id == selectedStationId
                    Card(
                        onClick = {
                            selectedStationId = preset.id
                            onSelectStation(preset.id, preset.title)
                        },
                        enabled = !radioStarting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = HorologistIcons.Radio,
                                contentDescription = null,
                                tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = preset.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isPlaying) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "播放中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (isStarting) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "启动中…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 账号登录与令牌引导界面 (Horologist Material 3 规范)
 */
@Composable
private fun AuthSetupScreen(
    client: AppleLyricsClient,
    transferError: String?,
    onSessionChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tokenInput by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(transferError.orEmpty()) }
    val listState = rememberTransformingLazyColumnState()

    val importTokenFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val token = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText().trim() }
                            ?: error("无法读取令牌文件")
                    }
                    client.saveUserToken(token)
                    status = "账号令牌已保存"
                    onSessionChanged()
                } catch (e: Exception) {
                    status = "导入失败：${e.message ?: "请检查文件"}"
                }
            }
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
        ) {
            item {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text("登录与连接", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }

            item {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "已连手机？在手机 Lyrics 设置 → 独立使用 Wear OS 版 → 发送令牌",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            if (status.isNotBlank()) {
                item {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                FilledTonalButton(
                    onClick = {
                        try {
                            importTokenFile.launch(arrayOf("text/plain", "application/octet-stream"))
                        } catch (_: ActivityNotFoundException) {
                            showManual = true
                            status = "手表无文件选择器，请手动输入或从手机发送"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择令牌文件")
                }
            }

            item {
                OutlinedButton(
                    onClick = { showManual = !showManual },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showManual) "收起手动输入" else "手动输入令牌")
                }
            }

            if (showManual) {
                item {
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("粘贴令牌文本") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedContainerColor = Color(0xFF1E1E1E),
                            unfocusedContainerColor = Color(0xFF1E1E1E)
                        ),
                        singleLine = true
                    )
                }
                item {
                    Button(
                        enabled = tokenInput.length >= 50,
                        onClick = {
                            try {
                                client.saveUserToken(tokenInput)
                                tokenInput = ""
                                onSessionChanged()
                            } catch (e: Exception) {
                                status = "保存失败：${e.message}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存并连接")
                    }
                }
            }
        }
    }
}

private fun playSongs(queue: List<SongHit>, index: Int) {
    WearRadioPlayback.stop()
    val rows = queue.map(SongHit::toSongRow)
    MusicRepository.registerTransient(rows)
    PlaybackEngine.playQueue(rows.map(AppleLibrarySync.SongRow::uri), index.coerceAtLeast(0))
}

private fun SongHit.toSongRow(): AppleLibrarySync.SongRow {
    val cleanId = id.removePrefix("apple:")
    val existing = MusicRepository.findSong(cleanId) ?: MusicRepository.findSong("apple:$cleanId")
    if (existing != null) return existing
    return AppleLibrarySync.SongRow().also { row ->
        row.appleId = cleanId
        row.uri = "apple:$cleanId"
        row.title = title
        row.artist = artist
        row.album = album
        row.durationMs = durationMs
        row.coverUrl = artworkUrl
        row.catalogId = providerSongId.orEmpty().removePrefix("apple:").ifBlank { cleanId }
        row.isLibrary = false
    }
}

private fun AppleLibrarySync.SongRow.toSongHit(): SongHit =
    SongHit(
        id = appleId.ifBlank { catalogId },
        title = title,
        artist = artist,
        album = album,
        artworkUrl = coverUrl,
        durationMs = durationMs,
        providerSongId = catalogId.takeIf(String::isNotBlank)
    )

private fun AppleSong.toSongHit(): SongHit =
    SongHit(
        id = id,
        title = name,
        artist = artistName,
        album = albumName,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
        providerSongId = catalogId.ifBlank { id }
    )

private fun SongHit.favoriteCatalogId(): String {
    val cleanId = id.removePrefix("apple:")
    val row = MusicRepository.findSong("apple:$cleanId") ?: MusicRepository.findSong(cleanId)
    return row?.catalogId?.takeIf(String::isNotBlank)
        ?: providerSongId?.removePrefix("apple:")?.takeIf(String::isNotBlank)
        ?: cleanId.takeUnless { it.startsWith("i.") }.orEmpty()
}
