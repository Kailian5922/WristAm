package com.applemusic.android.apple

import com.applemusic.android.util.Logx

/**
 * 「智能电台」—— 线程安全、带容器曲目缓冲的个性化推荐续杯引擎。
 */
class SmartRadio(private val api: AppleApi) {

    private companion object {
        const val TAG = "SmartRadio"
        const val MAX_SEEN_URIS = 300

        @Volatile
        private var lastRadioOpeningUri = ""

        fun looksLikeSongId(id: String): Boolean {
            if (id.isEmpty()) return false
            if (id.startsWith("pl.")) return false
            if (id.startsWith("p.")) return false
            if (id.startsWith("ra.")) return false
            if (id.startsWith("r.")) return false
            return true
        }

        private fun shelfPriority(title: String): Int {
            val normalized = title.lowercase()
            return when {
                normalized.contains("专属推荐") || normalized.contains("made for you") ||
                    normalized.contains("personalized") -> 100
                normalized.contains("最近播放") || normalized.contains("recently played") -> 80
                normalized.contains("推荐") || normalized.contains("精选") ||
                    normalized.contains("recommended") -> 60
                else -> 20
            }
        }
    }

    /** 有界 LRU 去重集合，防止长电台会话耗尽所有可播曲目。 */
    private val seen = object : LinkedHashSet<String>() {
        fun addBounded(uri: String): Boolean {
            if (!add(uri)) return false
            if (size > MAX_SEEN_URIS) {
                val it = iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            return true
        }
    }

    private val containers = ArrayDeque<ShelfItem>()

    /** 已展开但尚未消耗完的当前容器曲目缓存（避免每次 nextBatch 重复 HTTP 请求同一歌单）。 */
    private val bufferedSongs = ArrayDeque<AppleSong>()

    private var libPool = ArrayList<AppleLibrarySync.SongRow>()
    private var libCursor = 0
    private var openingBatchPending = true

    @Volatile
    var stationName: String = ""
        private set

    @Volatile
    var artistHit: String? = null
        private set

    val pendingContainers: Int
        @Synchronized get() = containers.size

    @Synchronized
    fun prepare(name: String, library: List<AppleLibrarySync.SongRow>, artists: List<String>) {
        stationName = name
        seen.clear()
        containers.clear()
        bufferedSongs.clear()
        libCursor = 0
        openingBatchPending = true

        val base = name
            .replace("& Artists", "", ignoreCase = true)
            .replace("’s Station", "", ignoreCase = true)
            .replace("'s Station", "", ignoreCase = true)
            .replace("的电台", "")
            .trim()
        artistHit = if (base.isNotEmpty()) {
            artists.filter { it.isNotEmpty() && (base.contains(it) || it.contains(base)) }
                .maxByOrNull { it.length }
        } else {
            null
        }

        val shuffled = library.shuffled()
        libPool = if (artistHit != null) {
            val hit = shuffled.filter { it.artist.contains(artistHit!!, ignoreCase = true) }
            ArrayList(hit + shuffled.filterNot { it.artist.contains(artistHit!!, ignoreCase = true) })
        } else {
            ArrayList(shuffled)
        }

        val recs = runCatching { api.getRecommendations() }.getOrNull() ?: emptyList()
        val rankedShelves = recs.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Shelf>> { shelfPriority(it.value.title) }
                    .thenBy { it.index }
            )
            .map { it.value }
        for (shelf in rankedShelves) {
            containers.addAll(shelf.items.filter { it.isPlaylist || it.isAlbum })
        }

        val charts = runCatching { api.getCharts() }.getOrNull() ?: emptyList()
        containers.addAll(charts.flatMap { it.items }.filter { it.isAlbum || it.isPlaylist }.shuffled())

        Logx.i(
            TAG,
            "电台「$name」准备就绪：容器 ${containers.size} 个，资料库 ${libPool.size} 首，优先艺人=$artistHit"
        )
    }

    @Synchronized
    fun nextBatch(target: Int): List<AppleLibrarySync.SongRow> {
        val out = ArrayList<AppleLibrarySync.SongRow>(target)
        val batchUris = HashSet<String>(target * 2)
        var guard = 0

        fun drainBuffer() {
            while (out.size < target && bufferedSongs.isNotEmpty()) {
                val s = bufferedSongs.removeFirst()
                if (!looksLikeSongId(s.id)) continue
                val key = "apple:" + s.id
                if (!seen.addBounded(key) || !batchUris.add(key)) continue
                out.add(AppleLibrarySync.toRow(s))
                if (out.size < target && out.size % 3 == 0) {
                    takeLibrary(batchUris)?.let {
                        batchUris.add(it.uri)
                        out.add(it)
                    }
                }
            }
        }

        drainBuffer()

        while (out.size < target && containers.isNotEmpty() && guard < 20) {
            val c = containers.removeFirst()
            guard++
            val songs = runCatching {
                if (c.isAlbum) {
                    api.getRecommendedAlbumTracks(
                        albumId = c.id,
                        relatedCatalogId = c.relatedCatalogId,
                        isLibraryAlbum = c.isLibraryAlbum,
                        catalogStorefront = c.catalogStorefront.ifBlank { api.storefront }
                    )
                } else {
                    api.getRecommendedPlaylistTracks(
                        playlistId = c.id,
                        relatedCatalogId = c.relatedCatalogId,
                        isLibraryPlaylist = c.isLibraryPlaylist,
                        limit = 60,
                        offset = 0
                    ).items
                }
            }.getOrNull()?.let { orderSongs(it) } ?: continue

            bufferedSongs.addAll(songs)
            drainBuffer()
        }

        var guard2 = 0
        while (out.size < target && guard2 < target * 4) {
            guard2++
            val r = takeLibrary(batchUris) ?: break
            batchUris.add(r.uri)
            out.add(r)
        }

        if (openingBatchPending && out.isNotEmpty()) {
            val previousOpening = lastRadioOpeningUri
            if (previousOpening.isNotEmpty() && out.first().uri == previousOpening) {
                val alternateIndex = out.indexOfFirst { it.uri != previousOpening }
                if (alternateIndex > 0) {
                    val alternate = out.removeAt(alternateIndex)
                    out.add(0, alternate)
                }
            }
            lastRadioOpeningUri = out.first().uri
            openingBatchPending = false
        }

        Logx.i(
            TAG,
            "电台续杯：本批 ${out.size} 首，缓冲 ${bufferedSongs.size} 首，剩余容器 ${containers.size}"
        )
        return out
    }

    private fun takeLibrary(batchUris: Set<String>): AppleLibrarySync.SongRow? {
        if (libPool.isEmpty()) return null
        val tries = libPool.size
        repeat(tries) {
            if (libCursor >= libPool.size) {
                libPool = ArrayList(libPool.shuffled())
                libCursor = 0
            }
            val candidate = libPool[libCursor++]
            if (candidate.uri !in batchUris) return candidate
        }
        return null
    }

    private fun orderSongs(songs: List<AppleSong>): List<AppleSong> {
        val shuffled = songs.shuffled()
        val preferredArtist = artistHit ?: return shuffled
        return shuffled.sortedBy {
            if (it.artistName.contains(preferredArtist, ignoreCase = true)) 0 else 1
        }
    }
}
