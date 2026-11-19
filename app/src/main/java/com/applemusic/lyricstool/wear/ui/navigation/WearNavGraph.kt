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
import com.applemusic.lyricstool.wear.ui.screens.AlbumDetailScreen
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
import com.applemusic.lyricstool.wear.ui.screens.SleepTimerScreen
import com.applemusic.lyricstool.wear.ui.theme.HorologistArtworkBackground
import com.applemusic.lyricstool.wear.ui.theme.HorologistColors
import com.applemusic.lyricstool.wear.ui.theme.rememberDynamicColorScheme
import com.applemusic.lyricstool.wear.viewmodel.AlbumDetailViewModel
import com.applemusic.lyricstool.wear.viewmodel.AlbumDetailViewModelFactory
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
import kotlinx.coroutines.flow.StateFlow
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
    val backStackEntryState = navController.currentBackStackEntryAsState()

    val returnToPlayer: () -> Unit = {
        navController.popBackStack(WearRoutes.MAIN, inclusive = false)
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    val openArtistFlow: (String, String, String) -> Unit = { rawArtist, songId, catalogId ->
        val split = ArtistNameParser.splitArtists(rawArtist)
        if (split.size > 1) {
            navController.navigate(
                WearRoutes.artistPicker(
                    rawArtists = rawArtist,
                    songId = songId,
                    catalogId = catalogId
                )
            )
        } else {
            navController.navigate(
                WearRoutes.artistDetail(
                    rawArtists = split.firstOrNull() ?: rawArtist,
                    songId = songId,
                    catalogId = catalogId
                )
            )
        }
    }

    MaterialTheme(colorScheme = dynamicColorScheme) {
        AppScaffold {
            Box(modifier = Modifier.fillMaxSize()) {
                // SwipeDismissableNavHost 内部 BasicSwipeToDismissBox 会为每个目标页铺一层不透明底色，
                // 因此背景必须渲染在每个 composable 目标内部（既不被遮挡，侧滑返回时也不会透底重影）。
                // 同时通过 StateFlow / State 在目标内部读取，避免被 SwipeDismissableNavHost 记住的 builder 捕获旧值。
                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = WearRoutes.MAIN,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(WearRoutes.MAIN) {
                        val isMainRoute = (backStackEntryState.value?.destination?.route ?: WearRoutes.MAIN) == WearRoutes.MAIN
                        val isPlayerVisible = isMainRoute && pagerState.currentPage == 0
                        val isLyricsVisible = isMainRoute && (pagerState.currentPage == 1 || pagerState.targetPage == 1)

                        WearDestinationContainer(artworkUrlFlow = appViewModel.currentArtworkUrl) {
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
                                        isVisible = isPlayerVisible,
                                        onOpenQueue = { navController.navigate(WearRoutes.QUEUE) },
                                        onOpenArtist = openArtistFlow,
                                        onOpenSleepTimer = {
                                            navController.navigate(WearRoutes.SLEEP_TIMER) {
                                                launchSingleTop = true
                                            }
                                        },
                                        onToast = appViewModel::showToast
                                    )
                                    1 -> LyricsScreen(
                                        viewModel = lyricsViewModel,
                                        isVisible = isLyricsVisible
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
                    }

                    composable(WearRoutes.QUEUE) {
                        WearDestinationContainer(artworkUrlFlow = appViewModel.currentArtworkUrl) {
                            QueueScreen(
                                playerViewModel = playerViewModel,
                                onClose = { navController.popBackStack() }
                            )
                        }
                    }

                    composable(WearRoutes.SLEEP_TIMER) {
                        SleepTimerScreen(
                            playerViewModel = playerViewModel,
                            onClose = { navController.popBackStack() },
                            onToast = appViewModel::showToast
                        )
                    }

                    composable(WearRoutes.RADIO) {
                        val radioVm: RadioViewModel = viewModel(factory = factory)
                        WearDestinationContainer(artworkUrlFlow = appViewModel.currentArtworkUrl) {
                            RadioStationsScreen(
                                viewModel = radioVm,
                                onBack = { navController.popBackStack() },
                                onStarted = returnToPlayer,
                                onToast = appViewModel::showToast
                            )
                        }
                    }

                    composable(WearRoutes.LIBRARY_SONGS) {
                        WearDestinationContainer(artworkUrlFlow = appViewModel.currentArtworkUrl) {
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
                    }

                    composable(WearRoutes.PLAYLISTS) {
                        WearDestinationContainer(artworkUrlFlow = appViewModel.currentArtworkUrl) {
                            PlaylistsScreen(
                                browseViewModel = browseViewModel,
                                onBack = { navController.popBackStack() },
                                onSelectPlaylist = { pl ->
                                    navController.navigate(WearRoutes.playlistDetail(pl.id))
                                }
                            )
                        }
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
                        WearDestinationContainer(artworkUrlFlow = appViewModel.currentArtworkUrl) {
                            PlaylistDetailScreen(
                                viewModel = detailVm,
                                onBack = { navController.popBackStack() },
                                onPlayed = returnToPlayer
                            )
                        }
                    }

                    composable(WearRoutes.SEARCH) { entry ->
                        val searchVm: SearchViewModel = viewModel(
                            viewModelStoreOwner = entry,
                            factory = factory
                        )
                        WearDestinationContainer(artworkUrlFlow = appViewModel.currentArtworkUrl) {
                            SearchScreen(
                                viewModel = searchVm,
                                onBack = { navController.popBackStack() },
                                onPlayed = returnToPlayer,
                                onOpenArtist = { artistName, artistId ->
                                    navController.navigate(WearRoutes.artistDetail(artistName, artistId))
                                },
                                onOpenAlbum = { album ->
                                    navController.navigate(
                                        WearRoutes.albumDetail(
                                            albumId = album.id,
                                            name = album.name,
                                            artist = album.artistName,
                                            artwork = album.artworkUrl,
                                            storefront = album.storefront,
                                            year = album.year,
                                            isLibrary = album.isLibrary
                                        )
                                    )
                                }
                            )
                        }
                    }

                    composable(WearRoutes.DOWNLOADS) { entry ->
                        val downloadsVm: DownloadsViewModel = viewModel(
                            viewModelStoreOwner = entry,
                            factory = factory
                        )
                        WearDestinationContainer(artworkUrlFlow = appViewModel.currentArtworkUrl) {
                            DownloadsScreen(
                                viewModel = downloadsVm,
                                onBack = { navController.popBackStack() },
                                onPlayed = returnToPlayer,
                                onToast = appViewModel::showToast
                            )
                        }
                    }

                    composable(
                        route = WearRoutes.ARTIST_PICKER_PATTERN,
                        arguments = listOf(
                            navArgument("rawArtists") { type = NavType.StringType },
                            navArgument("songId") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("catalogId") {
                                type = NavType.StringType
                                defaultValue = ""
                            }
                        )
                    ) { entry ->
                        val encoded = entry.arguments?.getString("rawArtists").orEmpty()
                        val rawArtists = remember(encoded) { Uri.decode(encoded) }
                        val encodedSongId = entry.arguments?.getString("songId").orEmpty()
                        val songId = remember(encodedSongId) { Uri.decode(encodedSongId) }
                        val encodedCatalogId = entry.arguments?.getString("catalogId").orEmpty()
                        val catalogId = remember(encodedCatalogId) { Uri.decode(encodedCatalogId) }
                        WearDestinationContainer(artworkUrlFlow = appViewModel.currentArtworkUrl) {
                            ArtistPickerScreen(
                                rawArtistString = rawArtists,
                                onSelectArtist = { _, reorderedRaw ->
                                    navController.navigate(
                                        WearRoutes.artistDetail(
                                            rawArtists = reorderedRaw,
                                            songId = songId,
                                            catalogId = catalogId
                                        )
                                    ) {
                                        popUpTo(WearRoutes.ARTIST_PICKER_PATTERN) { inclusive = true }
                                    }
                                },
                                onDismiss = { navController.popBackStack() }
                            )
                        }
                    }

                    composable(
                        route = WearRoutes.ARTIST_DETAIL_PATTERN,
                        arguments = listOf(
                            navArgument("rawArtists") { type = NavType.StringType },
                            navArgument("artistId") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("songId") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("catalogId") {
                                type = NavType.StringType
                                defaultValue = ""
                            }
                        )
                    ) { entry ->
                        val encoded = entry.arguments?.getString("rawArtists").orEmpty()
                        val rawArtists = remember(encoded) { Uri.decode(encoded) }
                        val encodedArtistId = entry.arguments?.getString("artistId").orEmpty()
                        val directArtistId = remember(encodedArtistId) { Uri.decode(encodedArtistId) }
                        val encodedSongId = entry.arguments?.getString("songId").orEmpty()
                        val sourceSongId = remember(encodedSongId) { Uri.decode(encodedSongId) }
                        val encodedCatalogId = entry.arguments?.getString("catalogId").orEmpty()
                        val sourceCatalogId = remember(encodedCatalogId) { Uri.decode(encodedCatalogId) }
                        val artistFactory = remember(
                            container,
                            rawArtists,
                            directArtistId,
                            sourceSongId,
                            sourceCatalogId
                        ) {
                            ArtistViewModelFactory(
                                container = container,
                                rawArtistParam = rawArtists,
                                directArtistId = directArtistId,
                                sourceSongId = sourceSongId,
                                sourceCatalogId = sourceCatalogId
                            )
                        }
                        val artistVm: ArtistViewModel = viewModel(
                            viewModelStoreOwner = entry,
                            factory = artistFactory
                        )
                        WearDestinationContainer(artworkUrlFlow = appViewModel.currentArtworkUrl) {
                            ArtistDetailScreen(
                                viewModel = artistVm,
                                onClose = { navController.popBackStack() },
                                onPlayed = returnToPlayer,
                                onOpenAlbum = { album ->
                                    navController.navigate(
                                        WearRoutes.albumDetail(
                                            albumId = album.id,
                                            name = album.name,
                                            artist = album.artistName,
                                            artwork = album.artworkUrl,
                                            storefront = album.catalogStorefront,
                                            year = album.year,
                                            isLibrary = album.isLibrary
                                        )
                                    )
                                },
                                onOpenSimilarArtist = { simName, simId ->
                                    navController.navigate(WearRoutes.artistDetail(simName, simId))
                                },
                                onToast = appViewModel::showToast
                            )
                        }
                    }

                    composable(
                        route = WearRoutes.ALBUM_DETAIL_PATTERN,
                        arguments = listOf(
                            navArgument("albumId") { type = NavType.StringType },
                            navArgument("name") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("artist") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("artwork") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("storefront") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("year") {
                                type = NavType.IntType
                                defaultValue = 0
                            },
                            navArgument("isLibrary") {
                                type = NavType.BoolType
                                defaultValue = false
                            }
                        )
                    ) { entry ->
                        val rawAlbumId = entry.arguments?.getString("albumId").orEmpty()
                        val albumId = remember(rawAlbumId) { Uri.decode(rawAlbumId) }
                        val rawName = entry.arguments?.getString("name").orEmpty()
                        val rawArtist = entry.arguments?.getString("artist").orEmpty()
                        val rawArtwork = entry.arguments?.getString("artwork").orEmpty()
                        val artwork = remember(rawArtwork) {
                            if (rawArtwork.startsWith("http%3A", ignoreCase = true) ||
                                rawArtwork.startsWith("https%3A", ignoreCase = true)
                            ) {
                                Uri.decode(rawArtwork)
                            } else {
                                rawArtwork
                            }
                        }
                        val storefront = entry.arguments?.getString("storefront").orEmpty()
                        val year = entry.arguments?.getInt("year") ?: 0
                        val isLibrary = entry.arguments?.getBoolean("isLibrary") ?: false

                        val albumFactory = remember(
                            container,
                            albumId,
                            storefront,
                            rawName,
                            rawArtist,
                            artwork,
                            year,
                            isLibrary
                        ) {
                            AlbumDetailViewModelFactory(
                                container = container,
                                albumId = albumId,
                                storefront = storefront,
                                fallbackName = rawName,
                                fallbackArtist = rawArtist,
                                fallbackArtwork = artwork,
                                fallbackYear = year,
                                isLibrary = isLibrary
                            )
                        }
                        val albumVm: AlbumDetailViewModel = viewModel(
                            viewModelStoreOwner = entry,
                            factory = albumFactory
                        )
                        WearDestinationContainer(artworkUrlFlow = appViewModel.currentArtworkUrl) {
                            AlbumDetailScreen(
                                viewModel = albumVm,
                                onBack = { navController.popBackStack() },
                                onPlayed = returnToPlayer,
                                onOpenArtist = { artistName, artistId, songId, catalogId ->
                                    if (artistId.isNotBlank()) {
                                        navController.navigate(
                                            WearRoutes.artistDetail(
                                                rawArtists = artistName,
                                                artistId = artistId,
                                                songId = songId,
                                                catalogId = catalogId
                                            )
                                        )
                                    } else {
                                        openArtistFlow(artistName, songId, catalogId)
                                    }
                                }
                            )
                        }
                    }
                }

                WearStatusToastOverlay(toastEvents = appViewModel.toastEvents)
            }
        }
    }
}

@Composable
private fun WearDestinationContainer(
    artworkUrlFlow: StateFlow<String?>,
    scrimAlpha: Float = 0f,
    content: @Composable () -> Unit
) {
    val artworkUrl by artworkUrlFlow.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize()) {
        HorologistArtworkBackground(
            artworkUrl = artworkUrl,
            scrimAlpha = scrimAlpha
        )
        content()
    }
}
