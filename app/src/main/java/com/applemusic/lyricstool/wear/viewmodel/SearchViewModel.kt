package com.applemusic.lyricstool.wear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.android.apple.AppleAlbum
import com.applemusic.android.apple.AppleArtist
import com.applemusic.android.apple.AppleLyricSearchHit
import com.applemusic.android.apple.AppleSearchResult
import com.applemusic.android.apple.ArtistAliasTable
import com.applemusic.android.apple.MusicRepository
import com.applemusic.lyricstool.data.LyricSearchCandidate
import com.applemusic.lyricstool.data.QQMusicLyricsClient
import com.applemusic.lyricstool.data.SongHit
import com.applemusic.lyricstool.wear.data.ActiveDownload
import com.applemusic.lyricstool.wear.data.DownloadRepository
import com.applemusic.lyricstool.wear.data.PlaybackRepository
import com.applemusic.lyricstool.wear.data.toSongHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class SearchedArtist(
    val id: String,
    val name: String,
    val artworkUrl: String = "",
    val subtitle: String = "歌手"
)

data class SearchedAlbum(
    val id: String,
    val name: String,
    val artistName: String,
    val artistId: String = "",
    val artworkUrl: String = "",
    val year: Int = 0,
    val trackCount: Int = 0,
    val storefront: String = "",
    val isLibrary: Boolean = false
)

data class SearchUiState(
    val query: String = "",
    val searchByLyrics: Boolean = false,
    val artists: List<SearchedArtist> = emptyList(),
    val albums: List<SearchedAlbum> = emptyList(),
    val results: List<SongHit> = emptyList(),
    val lyricSnippets: Map<String, String> = emptyMap(),
    val searching: Boolean = false,
    val status: String = "",
    val downloadedIds: Set<String> = emptySet(),
    val activeDownloads: Map<String, ActiveDownload> = emptyMap()
)

class SearchViewModel(
    private val qqMusicClient: QQMusicLyricsClient,
    private val downloadRepository: DownloadRepository,
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    private data class LocalSearchState(
        val query: String = "",
        val searchByLyrics: Boolean = false,
        val artists: List<SearchedArtist> = emptyList(),
        val albums: List<SearchedAlbum> = emptyList(),
        val results: List<SongHit> = emptyList(),
        val lyricSnippets: Map<String, String> = emptyMap(),
        val searching: Boolean = false,
        val status: String = ""
    )

    private data class CombinedSearchResult(
        val artists: List<SearchedArtist>,
        val albums: List<SearchedAlbum>,
        val songs: List<SongHit>,
        val lyricSnippets: Map<String, String> = emptyMap(),
        val note: String
    )

    private val localState = MutableStateFlow(LocalSearchState())
    private var searchJob: Job? = null

    val uiState: StateFlow<SearchUiState> = combine(
        localState,
        downloadRepository.state
    ) { local, dl ->
        SearchUiState(
            query = local.query,
            searchByLyrics = local.searchByLyrics,
            artists = local.artists,
            albums = local.albums,
            results = local.results,
            lyricSnippets = local.lyricSnippets,
            searching = local.searching,
            status = local.status,
            downloadedIds = dl.downloadedIds,
            activeDownloads = dl.activeDownloads
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChanged(value: String) {
        localState.update { it.copy(query = value) }
    }

    fun toggleSearchByLyrics() {
        localState.update {
            it.copy(
                searchByLyrics = !it.searchByLyrics,
                status = "",
                lyricSnippets = emptyMap()
            )
        }
    }

    fun performSearch() {
        val trimmed = localState.value.query.trim()
        if (trimmed.isEmpty()) return

        searchJob?.cancel()
        val byLyrics = localState.value.searchByLyrics
        localState.update { it.copy(searching = true, status = "") }

        searchJob = viewModelScope.launch {
            try {
                val combined = withContext(Dispatchers.IO) {
                    if (byLyrics) {
                        searchByLyricsParallel(trimmed)
                    } else {
                        searchCombinedWithOfflineFallback(trimmed)
                    }
                }
                val statusMsg = when {
                    combined.artists.isEmpty() && combined.albums.isEmpty() && combined.songs.isEmpty() ->
                        combined.note.ifBlank {
                            if (byLyrics) "没有找到匹配歌曲" else "没有找到匹配歌手、专辑或歌曲"
                        }
                    else -> combined.note
                }
                localState.update {
                    it.copy(
                        artists = combined.artists,
                        albums = combined.albums,
                        results = combined.songs,
                        lyricSnippets = combined.lyricSnippets,
                        searching = false,
                        status = statusMsg
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                localState.update {
                    it.copy(
                        searching = false,
                        status = "搜索失败：${e.message ?: "网络超时"}"
                    )
                }
            }
        }
    }

    /**
     * 双通道并行歌词搜歌：
     * 1. Apple Music 原生歌词索引检索（`with=lyricHighlights,lyrics,naturalLanguage,serverBubbles`），
     *    直接返回可播放曲目与官方歌词高亮片段，支持中/英/日/韩全语种；
     * 2. QQ 音乐现代桌面歌词搜索（`DoSearchForQQMusicDesktop search_type=7`）+ 网易云 `1006` + 酷狗兜底，
     *    再并行回查 Apple Music 目录匹配最贴合曲目；
     * 任一通道异常互不影响，避免单点接口波动导致整个歌词搜索不可用。
     */
    private suspend fun searchByLyricsParallel(trimmed: String): CombinedSearchResult = coroutineScope {
        val appleNativeDeferred = async(Dispatchers.IO) {
            try {
                Result.success(MusicRepository.api.searchSongsByLyrics(trimmed, limit = 15))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure<List<AppleLyricSearchHit>>(e)
            }
        }

        val externalDeferred = async(Dispatchers.IO) {
            try {
                Result.success(qqMusicClient.searchSongsByLyricsWithSnippets(trimmed).take(6))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure<List<LyricSearchCandidate>>(e)
            }
        }

        val appleResult = appleNativeDeferred.await()
        val externalResult = externalDeferred.await()
        val appleNativeHits = appleResult.getOrDefault(emptyList())
        val externalCandidates = externalResult.getOrDefault(emptyList())

        val resolvedExternalBatches = externalCandidates.map { candidate ->
            async(Dispatchers.IO) {
                try {
                    val artistPart = candidate.song.artist
                        .takeUnless { it.isBlank() || it == "未知艺人" }
                        .orEmpty()
                    val queryTerm = listOf(candidate.song.title, artistPart)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .trim()
                    if (queryTerm.isEmpty()) return@async emptyList()
                    val catalogSongs = MusicRepository.searchCatalog(queryTerm, 5)
                        .songs
                        .map { it.toSongHit() }
                    selectBestAppleMatches(candidate.song, catalogSongs)
                        .map { it to candidate.lyricSnippet }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll()

        val merged = ArrayList<SongHit>()
        val snippets = LinkedHashMap<String, String>()
        val seenIds = HashSet<String>()
        val seenSig = HashSet<String>()

        fun addSong(song: SongHit, snippet: String) {
            if (merged.size >= 15) return
            val rawId = song.id.trim()
            val primaryId = song.providerSongId?.trim()?.ifBlank { rawId } ?: rawId
            val sigKey = normalizeSignature(song.title, song.artist)
            if ((rawId.isNotEmpty() && rawId in seenIds) ||
                (primaryId.isNotEmpty() && primaryId in seenIds) ||
                sigKey in seenSig
            ) {
                if (snippet.isNotBlank() && rawId.isNotEmpty() && snippets[rawId].isNullOrBlank()) {
                    snippets[rawId] = snippet
                }
                return
            }
            if (rawId.isNotEmpty()) seenIds.add(rawId)
            if (primaryId.isNotEmpty()) seenIds.add(primaryId)
            seenSig.add(sigKey)
            merged.add(song)
            if (snippet.isNotBlank() && rawId.isNotEmpty()) {
                snippets[rawId] = snippet
            }
        }

        // 1) Apple Music 官方明确命中歌词高亮的曲目最优先
        for (hit in appleNativeHits) {
            if (hit.hasLyricSnippet) {
                addSong(hit.song.toSongHit(), hit.lyricSnippet)
            }
        }
        // 2) QQ / 网易云 / 酷狗歌词搜歌命中并已映射到 Apple Music 目录的曲目
        for (batch in resolvedExternalBatches) {
            for ((song, snippet) in batch) {
                addSong(song, snippet)
            }
        }
        // 3) 其余 Apple Music 自然语言/歌词索引召回的曲目
        for (hit in appleNativeHits) {
            if (!hit.hasLyricSnippet) {
                addSong(hit.song.toSongHit(), hit.lyricSnippet)
            }
        }

        val appleErr = appleResult.exceptionOrNull()
        val externalErr = externalResult.exceptionOrNull()
        if (merged.isEmpty() && appleErr != null && externalErr != null) {
            throw appleErr
        }

        CombinedSearchResult(
            artists = emptyList(),
            albums = emptyList(),
            songs = merged,
            lyricSnippets = snippets,
            note = ""
        )
    }

    private fun selectBestAppleMatches(expected: SongHit, candidates: List<SongHit>): List<SongHit> {
        if (candidates.isEmpty()) return emptyList()
        val expectedTitle = ArtistAliasTable.normalizeKey(expected.title)
        val expectedArtists = expected.artist
            .takeUnless { it == "未知艺人" }
            .orEmpty()
            .split(Regex("[,/&、，;]+"))
            .map { ArtistAliasTable.normalizeKey(it) }
            .filter { it.isNotEmpty() }

        val scored = candidates.mapIndexedNotNull { idx, song ->
            val actualTitle = ArtistAliasTable.normalizeKey(song.title)
            val titleScore = when {
                expectedTitle.isBlank() || actualTitle.isBlank() -> 0
                actualTitle == expectedTitle -> 100
                actualTitle.contains(expectedTitle) || expectedTitle.contains(actualTitle) -> 70
                else -> 0
            }
            val actualArtist = ArtistAliasTable.normalizeKey(song.artist)
            val artistMatched = expectedArtists.isNotEmpty() && actualArtist.isNotBlank() &&
                expectedArtists.any { exp ->
                    actualArtist == exp ||
                        (exp.length >= 2 && actualArtist.contains(exp)) ||
                        (actualArtist.length >= 2 && exp.contains(actualArtist))
                }
            val score = titleScore + (if (artistMatched) 40 else 0) - idx * 3
            if (titleScore > 0 || artistMatched) song to score else null
        }.sortedByDescending { it.second }

        return if (scored.isNotEmpty()) {
            scored.take(2).map { it.first }
        } else {
            candidates.take(1)
        }
    }

    private fun searchCombinedWithOfflineFallback(trimmed: String): CombinedSearchResult {
        val localResult = try {
            MusicRepository.searchLocal(trimmed)
        } catch (_: Exception) {
            AppleSearchResult()
        }

        var catalogError: Throwable? = null
        val catalogResult = try {
            MusicRepository.searchCatalog(trimmed, 25)
        } catch (e: Exception) {
            catalogError = e
            null
        }

        val catalogExactArtists = if (catalogResult != null) {
            try {
                MusicRepository.api.resolveExactArtists(
                    term = trimmed,
                    initialCandidates = catalogResult.artists,
                    maxResults = MAX_EXACT_ARTISTS
                )
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val targetKeys = ArtistAliasTable.exactTargetKeys(trimmed)
        val localExactArtists = localResult.artists.filter {
            ArtistAliasTable.matchesTargetKeys(it.name, targetKeys)
        }

        val mergedArtists = mergeExactArtists(
            query = trimmed,
            catalogArtists = catalogExactArtists,
            localArtists = localExactArtists
        )

        val mergedAlbums = mergeAlbums(
            query = trimmed,
            catalogAlbums = catalogResult?.albums.orEmpty(),
            localAlbums = localResult.albums
        )

        val mergedSongs = ArrayList<SongHit>()
        val seenIds = HashSet<String>()
        val seenSignatures = HashSet<String>()

        fun addIfUnique(hit: SongHit) {
            val rawId = hit.id.trim()
            val primaryId = hit.providerSongId?.trim()?.ifBlank { rawId } ?: rawId
            val sig = normalizeSignature(hit.title, hit.artist)
            if ((rawId.isNotEmpty() && rawId in seenIds) ||
                (primaryId.isNotEmpty() && primaryId in seenIds) ||
                sig in seenSignatures
            ) {
                return
            }
            if (rawId.isNotEmpty()) seenIds.add(rawId)
            if (primaryId.isNotEmpty()) seenIds.add(primaryId)
            seenSignatures.add(sig)
            mergedSongs.add(hit)
        }

        for (s in localResult.songs) addIfUnique(s.toSongHit())
        for (s in catalogResult?.songs.orEmpty()) addIfUnique(s.toSongHit())

        val hasAnyMatch = mergedArtists.isNotEmpty() || mergedAlbums.isNotEmpty() || mergedSongs.isNotEmpty()
        val note = when {
            catalogError != null && hasAnyMatch ->
                "网络异常，已展示本地资料库匹配"
            catalogError != null && !hasAnyMatch ->
                throw catalogError
            else -> ""
        }
        return CombinedSearchResult(
            artists = mergedArtists,
            albums = mergedAlbums,
            songs = mergedSongs,
            note = note
        )
    }

    private fun mergeAlbums(
        query: String,
        catalogAlbums: List<AppleAlbum>,
        localAlbums: List<AppleAlbum>
    ): List<SearchedAlbum> {
        val candidates = ArrayList<SearchedAlbum>()
        val seenIds = HashSet<String>()
        val seenSignatures = HashSet<String>()

        fun addAlbum(album: AppleAlbum) {
            val cleanName = album.name.trim()
            if (cleanName.isEmpty()) return
            val cleanId = album.id.trim()
            val cleanArtist = album.artistName.trim()
            val sig = normalizeSignature(cleanName, cleanArtist)
            if (cleanId.isNotEmpty() && cleanId in seenIds) return
            if (sig in seenSignatures) return

            if (cleanId.isNotEmpty()) seenIds.add(cleanId)
            seenSignatures.add(sig)
            candidates.add(
                SearchedAlbum(
                    id = cleanId,
                    name = cleanName,
                    artistName = cleanArtist,
                    artistId = album.artistId.trim(),
                    artworkUrl = album.artworkUrl.trim(),
                    year = album.year,
                    trackCount = album.trackCount,
                    storefront = album.catalogStorefront.trim(),
                    isLibrary = album.isLibrary || cleanId.isEmpty()
                )
            )
        }

        // 目录专辑优先，确保进入专辑页后能完整加载所有曲目
        for (a in catalogAlbums) addAlbum(a)
        for (a in localAlbums) addAlbum(a)

        val normQuery = ArtistAliasTable.normalizeKey(query)
        val ranked = candidates.withIndex().sortedWith(
            compareBy<IndexedValue<SearchedAlbum>>(
                { albumMatchRank(it.value, normQuery) },
                { if (it.value.isLibrary) 1 else 0 },
                { it.index }
            )
        )
        return ranked.map { it.value }.take(MAX_ALBUMS)
    }

    private fun albumMatchRank(album: SearchedAlbum, normQuery: String): Int {
        if (normQuery.isEmpty()) return 3
        val normName = ArtistAliasTable.normalizeKey(album.name)
        return when {
            normName == normQuery -> 0
            normName.startsWith(normQuery) -> 1
            normName.contains(normQuery) -> 2
            else -> 3
        }
    }

    private fun mergeExactArtists(
        query: String,
        catalogArtists: List<AppleArtist>,
        localArtists: List<AppleArtist>
    ): List<SearchedArtist> {
        val out = ArrayList<SearchedArtist>()
        val seenIds = HashSet<String>()
        val seenNameKeys = HashSet<String>()

        fun addArtist(artist: AppleArtist, allowDuplicateNameWithDistinctId: Boolean) {
            val cleanName = artist.name.trim()
            if (cleanName.isEmpty()) return
            val cleanId = artist.id.trim()
            val nameKey = ArtistAliasTable.normalizeKey(cleanName)
            if (cleanId.isNotEmpty() && cleanId in seenIds) return
            if (!allowDuplicateNameWithDistinctId && nameKey.isNotEmpty() && nameKey in seenNameKeys) return
            if (cleanId.isEmpty() && nameKey.isNotEmpty() && nameKey in seenNameKeys) return

            if (cleanId.isNotEmpty()) seenIds.add(cleanId)
            if (nameKey.isNotEmpty()) seenNameKeys.add(nameKey)

            val avatarUrl = artist.artworkUrl.trim().ifBlank {
                MusicRepository.findLocalArtistAvatar(cleanName)
            }
            out.add(
                SearchedArtist(
                    id = cleanId,
                    name = cleanName,
                    artworkUrl = avatarUrl,
                    subtitle = buildArtistSubtitle(artist, query)
                )
            )
        }

        for (a in catalogArtists) {
            addArtist(a, allowDuplicateNameWithDistinctId = true)
            if (out.size >= MAX_EXACT_ARTISTS) return out
        }
        for (a in localArtists) {
            addArtist(a, allowDuplicateNameWithDistinctId = false)
            if (out.size >= MAX_EXACT_ARTISTS) return out
        }
        return out
    }

    private fun buildArtistSubtitle(artist: AppleArtist, query: String): String {
        val displayKey = ArtistAliasTable.normalizeKey(artist.name)
        val queryKey = ArtistAliasTable.normalizeKey(query)
        return when {
            queryKey.isNotEmpty() && displayKey != queryKey -> "歌手 · $query"
            artist.genreNames.isNotEmpty() -> "歌手 · ${artist.genreNames.first()}"
            else -> "歌手"
        }
    }

    private fun normalizeSignature(title: String, artist: String): String =
        "${title.trim().lowercase(Locale.ROOT)}|${artist.trim().lowercase(Locale.ROOT)}"

    fun playSong(song: SongHit) {
        playbackRepository.playSongs(listOf(song), 0)
    }

    fun downloadSong(song: SongHit) {
        downloadRepository.startDownload(song)
    }

    private companion object {
        const val MAX_EXACT_ARTISTS = 3
        const val MAX_ALBUMS = 6
    }
}
