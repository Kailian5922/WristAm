package com.applemusic.lyricstool.wear.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.applemusic.lyricstool.wear.data.ArtistNameParser
import com.applemusic.lyricstool.wear.di.WearAppContainer
import com.applemusic.lyricstool.wear.ui.components.WearStatusToastOverlay
import com.applemusic.lyricstool.wear.ui.screens.ArtistDetailScreen
import com.applemusic.lyricstool.wear.ui.screens.ArtistPickerScreen
import com.applemusic.lyricstool.wear.ui.screens.AuthSetupScreen
import com.applemusic.lyricstool.wear.ui.screens.BrowseMenuScreen
import com.applemusic.lyricstool.wear.ui.screens.DownloadsScreen
import com.applemusic.lyricstool.wear.ui.screens.LibrarySongsScreen
import com.applemusic.lyricstool.wear.ui.screens.LyricsScreen
import com.applemusic.lyricstool.wear.ui.screens.PlayerScreen
import com.applemusic.lyricstool.wear.ui.screens.PlaylistDetailScreen
import com.applemusic.lyricstool.wear.ui.screens.PlaylistsScreen
import com.applemusic.lyricstool.wear.ui.screens.QueueScreen
import com.applemusic.lyricstool.wear.ui.screens.RadioStationsScreen
import com.applemusic.lyricstool.wear.ui.screens.SearchScreen
import com.applemusic.lyricstool.wear.ui.theme.HorologistArtworkBackground
import com.applemusic.lyricstool.wear.ui.theme.HorologistColors
import com.applemusic.lyricstool.wear.ui.theme.rememberDynamicColorScheme
import com.applemusic.lyricstool.wear.viewmodel.AppViewModel
import com.applemusic.lyricstool.wear.viewmodel.ArtistViewModel
import com.applemusic.lyricstool.wear.viewmodel.ArtistViewModelFactory
import com.applemusic.lyricstool.wear.viewmodel.AuthViewModel
import com.applemusic.lyricstool.wear.viewmodel.BrowseViewModel
import com.applemusic.lyricstool.wear.viewmodel.DownloadsViewModel
import com.applemusic.lyricstool.wear.viewmodel.LyricsViewModel
import com.applemusic.lyricstool.wear.viewmodel.PlayerViewModel
import com.applemusic.lyricstool.wear.viewmodel.PlaylistDetailViewModel
import com.applemusic.lyricstool.wear.viewmodel.PlaylistDetailViewModelFactory
import com.applemusic.lyricstool.wear.viewmodel.RadioViewModel
import com.applemusic.lyricstool.wear.viewmodel.SearchViewModel
import com.applemusic.lyricstool.wear.viewmodel.WearViewModelFactory
import kotlinx.coroutines.launch

@Composable
fun WearAppRoot(
    container: WearAppContainer
) {
    val factory = remember(container) { WearViewModelFactory(container) }
    val appViewModel: AppViewModel = viewModel(factory = factory)
    val authState by appViewModel.authState.collectAsStateWithLifecycle()

    if (!authState.signedIn) {
        val authViewModel: AuthViewModel = viewModel(factory = factory)
        MaterialTheme(colorScheme = HorologistColors) {
            AppScaffold {
                Box(modifier = Modifier.fillMaxSize()) {
                    AuthSetupScreen(viewModel = authViewModel)
                    WearStatusToastOverlay(toastEvents = appViewModel.toastEvents)
                }
            }
        }
        return
    }

    val playerViewModel: PlayerViewModel = viewModel(factory = factory)
    val lyricsViewModel: LyricsViewModel = viewModel(factory = factory)
    val browseViewModel: BrowseViewModel = viewModel(factory = factory)

    val artworkUrl by appViewModel.currentArtworkUrl.collectAsStateWithLifecycle()
    val dynamicColorScheme = rememberDynamicColorScheme(artworkUrl)

    val navController = rememberSwipeDismissableNavController()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: WearRoutes.MAIN

    val onLyricsPage = currentRoute == WearRoutes.MAIN &&
        (pagerState.currentPage == 1 || pagerState.targetPage == 1)

    val returnToPlayer: () -> Unit = {
        navController.popBackStack(WearRoutes.MAIN, inclusive = false)
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    val openArtistFlow: (String) -> Unit = { rawArtist ->
        val split = ArtistNameParser.splitArtists(rawArtist)
        if (split.size > 1) {
            navController.navigate(WearRoutes.artistPicker(rawArtist))
        } else {
            navController.navigate(WearRoutes.artistDetail(split.firstOrNull() ?: rawArtist))
        }
    }

    MaterialTheme(colorScheme = dynamicColorScheme) {
        AppScaffold {
            Box(modifier = Modifier.fillMaxSize()) {
                // 全局共享动态高斯模糊专辑封面背景（所有子页均继承同一主题与封面，歌词页自动叠加柔和暗化护目遮罩）
                HorologistArtworkBackground(
                    artworkUrl = artworkUrl,
                    scrimAlpha = if (onLyricsPage) 0.24f else 0f
                )

                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = WearRoutes.MAIN,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(WearRoutes.MAIN) {
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
                                0 -> PlayerScreen(
                                    viewModel = playerViewModel,
                                    isVisible = currentRoute == WearRoutes.MAIN && pagerState.currentPage == 0,
                                    onOpenQueue = { navController.navigate(WearRoutes.QUEUE) },
                                    onOpenArtist = openArtistFlow,
                                    onToast = appViewModel::showToast
                                )
                                1 -> LyricsScreen(
                                    viewModel = lyricsViewModel,
                                    isVisible = onLyricsPage
                                )
                                2 -> BrowseMenuScreen(
                                    viewModel = browseViewModel,
                                    onOpenRadio = { navController.navigate(WearRoutes.RADIO) },
                                    onOpenQueue = { navController.navigate(WearRoutes.QUEUE) },
                                    onOpenLibrarySongs = { navController.navigate(WearRoutes.LIBRARY_SONGS) },
                                    onOpenPlaylists = { navController.navigate(WearRoutes.PLAYLISTS) },
                                    onOpenSearch = { navController.navigate(WearRoutes.SEARCH) },
                                    onOpenDownloads = { navController.navigate(WearRoutes.DOWNLOADS) },
                                    onClearCache = appViewModel::clearTemporaryCache,
                                    onSignOut = appViewModel::signOut,
                                    onToast = appViewModel::showToast
                                )
                            }
                        }
                    }

                    composable(WearRoutes.QUEUE) {
                        QueueScreen(
                            playerViewModel = playerViewModel,
                            onClose = { navController.popBackStack() }
                        )
                    }

                    composable(WearRoutes.RADIO) {
                        val radioVm: RadioViewModel = viewModel(factory = factory)
                        RadioStationsScreen(
                            viewModel = radioVm,
                            onBack = { navController.popBackStack() },
                            onStarted = returnToPlayer,
                            onToast = appViewModel::showToast
                        )
                    }

                    composable(WearRoutes.LIBRARY_SONGS) {
                        LibrarySongsScreen(
                            browseViewModel = browseViewModel,
                            onBack = { navController.popBackStack() },
                            onPlaySong = { song ->
                                container.playbackRepository.playSongs(listOf(song), 0)
                                returnToPlayer()
                            },
                            onPlayQueue = { rows, idx ->
                                container.playbackRepository.playSongRows(rows, idx)
                                returnToPlayer()
                            }
                        )
                    }

                    composable(WearRoutes.PLAYLISTS) {
                        PlaylistsScreen(
                            browseViewModel = browseViewModel,
                            onBack = { navController.popBackStack() },
                            onSelectPlaylist = { pl ->
                                navController.navigate(WearRoutes.playlistDetail(pl.id))
                            }
                        )
                    }

                    composable(
                        route = WearRoutes.PLAYLIST_DETAIL_PATTERN,
                        arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
                    ) { entry ->
                        val encodedId = entry.arguments?.getString("playlistId").orEmpty()
                        val playlistId = remember(encodedId) { Uri.decode(encodedId) }
                        val detailFactory = remember(container, playlistId) {
                            PlaylistDetailViewModelFactory(container, playlistId)
                        }
                        val detailVm: PlaylistDetailViewModel = viewModel(
                            viewModelStoreOwner = entry,
                            factory = detailFactory
                        )
                        PlaylistDetailScreen(
                            viewModel = detailVm,
                            onBack = { navController.popBackStack() },
                            onPlayed = returnToPlayer
                        )
                    }

                    composable(WearRoutes.SEARCH) { entry ->
                        val searchVm: SearchViewModel = viewModel(
                            viewModelStoreOwner = entry,
                            factory = factory
                        )
                        SearchScreen(
                            viewModel = searchVm,
                            onBack = { navController.popBackStack() },
                            onPlayed = returnToPlayer,
                            onToast = appViewModel::showToast
                        )
                    }

                    composable(WearRoutes.DOWNLOADS) { entry ->
                        val downloadsVm: DownloadsViewModel = viewModel(
                            viewModelStoreOwner = entry,
                            factory = factory
                        )
                        DownloadsScreen(
                            viewModel = downloadsVm,
                            onBack = { navController.popBackStack() },
                            onPlayed = returnToPlayer,
                            onToast = appViewModel::showToast
                        )
                    }

                    composable(
                        route = WearRoutes.ARTIST_PICKER_PATTERN,
                        arguments = listOf(navArgument("rawArtists") { type = NavType.StringType })
                    ) { entry ->
                        val encoded = entry.arguments?.getString("rawArtists").orEmpty()
                        val rawArtists = remember(encoded) { Uri.decode(encoded) }
                        ArtistPickerScreen(
                            rawArtistString = rawArtists,
                            onSelectArtist = { _, reorderedRaw ->
                                navController.navigate(WearRoutes.artistDetail(reorderedRaw)) {
                                    popUpTo(WearRoutes.MAIN) { inclusive = false }
                                }
                            },
                            onDismiss = { navController.popBackStack() }
                        )
                    }

                    composable(
                        route = WearRoutes.ARTIST_DETAIL_PATTERN,
                        arguments = listOf(navArgument("rawArtists") { type = NavType.StringType })
                    ) { entry ->
                        val encoded = entry.arguments?.getString("rawArtists").orEmpty()
                        val rawArtists = remember(encoded) { Uri.decode(encoded) }
                        val artistFactory = remember(container, rawArtists) {
                            ArtistViewModelFactory(container, rawArtists)
                        }
                        val artistVm: ArtistViewModel = viewModel(
                            viewModelStoreOwner = entry,
                            factory = artistFactory
                        )
                        ArtistDetailScreen(
                            viewModel = artistVm,
                            onClose = { navController.popBackStack() },
                            onPlayed = returnToPlayer,
                            onOpenSimilarArtist = { simName ->
                                navController.navigate(WearRoutes.artistDetail(simName))
                            },
                            onToast = appViewModel::showToast
                        )
                    }
                }

                WearStatusToastOverlay(toastEvents = appViewModel.toastEvents)
            }
        }
    }
}
