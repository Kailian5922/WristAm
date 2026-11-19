package com.applemusic.android.apple

import android.content.Context
import com.applemusic.android.util.Logx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 资料库仓库：线程安全、单锁保护的进程内唯一数据源。
 */
object MusicRepository {

    private const val TAG = "MusicRepository"
    private const val MAX_TRANSIENT_SONGS = 600

    private val initLock = Any()
    private val dataLock = Any()
    private val initialized = AtomicBoolean(false)
    private val syncingFlag = AtomicBoolean(false)

    private lateinit var appContext: Context
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "repo-io").apply { isDaemon = true } }

    lateinit var api: AppleApi
        private set
    lateinit var cache: AppleCache
        private set

    fun isInitialized(): Boolean = initialized.get()

    val syncing: Boolean get() = syncingFlag.get()

    @Volatile
    var syncNote = ""
        private set

    @Volatile
    var syncPercent = 0
        private set

    @Volatile
    var lastSyncAt = 0L
        private set

    /** 原始资料库数据（全部由 dataLock 统一保护）。 */
    private val songs = ArrayList<AppleLibrarySync.SongRow>()
    private val playlists = ArrayList<AppleLibrarySync.PlaylistRow>()
    private val playlistTracks = HashMap<String, List<String>>()
    private val playlistSongRows = HashMap<String, AppleLibrarySync.SongRow>()
    private val avatars = HashMap<String, String>()
    private val songByUri = HashMap<String, AppleLibrarySync.SongRow>()

    /** 有界 LRU 临时目录歌曲索引（避免长电台会话无限增长）。 */
    private val transientSongs = object : LinkedHashMap<String, AppleLibrarySync.SongRow>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AppleLibrarySync.SongRow>?): Boolean {
            return size > MAX_TRANSIENT_SONGS
        }
    }

    /** 聚合结果快照（只读拷贝供外部安全读取）。 */
    @Volatile
    var albumList: List<AlbumAgg> = emptyList()
        private set

    @Volatile
    var artistList: List<ArtistAgg> = emptyList()
        private set

    class AlbumAgg {
        var name = ""
        var artist = ""
        var year = 0
        var coverUrl = ""
        var trackCount = 0
        var songs = ArrayList<AppleLibrarySync.SongRow>()
    }

    class ArtistAgg {
        var name = ""
        var songCount = 0
        var albumCount = 0
        var genre = ""
        var coverUrl = ""
    }

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    var listener: (() -> Unit)? = null

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** 幂等、线程安全初始化（供 Application、Activity 及后台 Service 共享调用）。 */
    fun init(context: Context, tokenStore: TokenStore? = null) {
        if (initialized.get()) return
        synchronized(initLock) {
            if (initialized.get()) return
            appContext = context.applicationContext
            api = AppleApi(tokenStore ?: PrefTokenStore(appContext))
            api.restore()
            cache = AppleCache(appContext, api)
            cache.init()
            loadSnapshot()
            initialized.set(true)
        }
    }

    private fun snapshotFile(): File = File(appContext.filesDir, "library.json")

    private fun loadSnapshot() {
        val snap = AppleLibrarySync.load(snapshotFile()) ?: return
        synchronized(dataLock) {
            songs.clear()
            songs.addAll(snap.songs.filter { it.isLibrary })
            playlists.clear()
            playlists.addAll(snap.playlists)
            playlistTracks.clear()
            playlistTracks.putAll(snap.playlistTracks)
            playlistSongRows.clear()
            for (s in snap.songs) {
                if (!s.isLibrary && s.uri.isNotEmpty()) {
                    playlistSongRows[s.uri] = s
                }
            }
            avatars.clear()
            avatars.putAll(snap.avatars)
            lastSyncAt = snap.syncedAt
            rebuildLocked()
        }
    }

    fun songCount(): Int = synchronized(dataLock) { songs.size }
    fun playlistCount(): Int = synchronized(dataLock) { playlists.size }

    fun allSongs(): List<AppleLibrarySync.SongRow> = synchronized(dataLock) { songs.toList() }

    fun songByUri(uri: String): AppleLibrarySync.SongRow? = synchronized(dataLock) {
        songByUri[uri] ?: playlistSongRows[uri] ?: transientSongs[uri]
    }

    fun registerTransient(rows: List<AppleLibrarySync.SongRow>) {
        if (rows.isEmpty()) return
        synchronized(dataLock) {
            for (r in rows) {
                if (r.uri.isEmpty()) continue
                transientSongs[r.uri] = r
            }
        }
    }

    /** 按 uri 或裸 id（appleId / catalogId）查找歌曲（全锁保护，无竞态）。 */
    fun findSong(uriOrId: String): AppleLibrarySync.SongRow? {
        if (uriOrId.isEmpty()) return null
        val bare = uriOrId.removePrefix("apple:")
        synchronized(dataLock) {
            songByUri[uriOrId]?.let { return it }
            songByUri["apple:$bare"]?.let { return it }
            songs.firstOrNull { it.appleId == bare || it.catalogId == bare }?.let { return it }
            playlistSongRows[uriOrId]?.let { return it }
            playlistSongRows["apple:$bare"]?.let { return it }
            playlistSongRows.values.firstOrNull { it.appleId == bare || it.catalogId == bare }?.let { return it }
            transientSongs[uriOrId]?.let { return it }
            transientSongs["apple:$bare"]?.let { return it }
            return transientSongs.values.firstOrNull { it.appleId == bare || it.catalogId == bare }
        }
    }

    fun playlistById(id: String): AppleLibrarySync.PlaylistRow? =
        synchronized(dataLock) { playlists.firstOrNull { it.id == id } }

    fun allPlaylists(): List<AppleLibrarySync.PlaylistRow> =
        synchronized(dataLock) { playlists.toList() }

    fun playlistSongs(id: String): List<AppleLibrarySync.SongRow> {
        val uris = synchronized(dataLock) { playlistTracks[id]?.toList() } ?: return emptyList()
        return uris.mapNotNull { findSong(it) }
    }

    fun hasPlaylistTracks(id: String): Boolean =
        synchronized(dataLock) { playlistTracks.containsKey(id) }

    /**
     * 拉取歌单曲目并单独缓存于 [playlistSongRows]，绝不把非资料库曲目混入 [songs] 污染资料库/专辑/艺人。
     */
    fun loadPlaylistTracks(id: String): List<AppleLibrarySync.SongRow> {
        val rows = AppleLibrarySync.loadPlaylistSongs(api, id)
        val uris = ArrayList<String>(rows.size)
        synchronized(dataLock) {
            for (r in rows) {
                uris.add(r.uri)
                if (!songByUri.containsKey(r.uri)) {
                    playlistSongRows[r.uri] = r
                }
            }
            playlistTracks[id] = uris
            persistLocked()
        }
        notifyChanged()
        return rows
    }

    /** 退出登录时彻底清空内存资料库与本地快照，杜绝切换账号串号。 */
    fun clearForLogout() {
        synchronized(dataLock) {
            songs.clear()
            playlists.clear()
            playlistTracks.clear()
            playlistSongRows.clear()
            avatars.clear()
            songByUri.clear()
            transientSongs.clear()
            albumList = emptyList()
            artistList = emptyList()
            lastSyncAt = 0L
            syncNote = ""
            syncPercent = 0
            try {
                if (::appContext.isInitialized) {
                    snapshotFile().delete()
                }
            } catch (_: Throwable) {
            }
        }
        notifyChanged()
    }

    /** 全量同步（账号区服 + 资料库歌曲 + 艺人官方头像 + 歌单元数据）。 */
    fun syncAll(onDone: ((Boolean, String) -> Unit)? = null) {
        if (!syncingFlag.compareAndSet(false, true)) {
            onDone?.invoke(false, "正在同步中，请稍候")
            return
        }
        syncPercent = 0
        syncNote = "开始同步"
        notifyChanged()
        io.execute {
            var ok = true
            var msg = ""
            try {
                api.getAccountInfo()
                val (newSongs, newAvatars) = AppleLibrarySync.fetchSongsWithAvatars(api) { p, n ->
                    syncPercent = (p * 0.85f).toInt()
                    syncNote = n
                }
                val newPlaylists = AppleLibrarySync.fetchPlaylists(api) { p, n ->
                    syncPercent = minOf(99, 85 + (p * 0.14f).toInt())
                    syncNote = n
                }
                val validPlaylistIds = newPlaylists.mapTo(HashSet()) { it.id }
                synchronized(dataLock) {
                    songs.clear()
                    songs.addAll(newSongs)
                    playlists.clear()
                    playlists.addAll(newPlaylists)
                    // 清理已删除的歌单曲目缓存
                    playlistTracks.keys.retainAll(validPlaylistIds)
                    // 必须在 rebuildLocked() 之前更新 avatars，否则官方头像无法立刻生效
                    avatars.clear()
                    avatars.putAll(newAvatars)
                    lastSyncAt = System.currentTimeMillis()
                    rebuildLocked()
                    persistLocked()
                }
                msg = "同步完成：${newSongs.size} 首 / ${newPlaylists.size} 个歌单"
                Logx.i(TAG, msg)
            } catch (e: Throwable) {
                ok = false
                msg = e.message ?: e.toString()
                Logx.e(TAG, "同步失败: $msg", e)
            } finally {
                syncingFlag.set(false)
                syncPercent = if (ok) 100 else 0
                syncNote = msg
                notifyChanged()
                onDone?.invoke(ok, msg)
            }
        }
    }

    fun searchCatalog(term: String, limit: Int = 25): AppleSearchResult = api.search(term, limit)

    fun searchLocal(term: String): AppleSearchResult {
        val rawTerm = term.trim()
        val t = rawTerm.lowercase(Locale.ROOT)
        val out = AppleSearchResult()
        if (t.isBlank()) return out
        val targetKeys = ArtistAliasTable.exactTargetKeys(rawTerm)
        val songsSnap: List<AppleLibrarySync.SongRow>
        val albumsSnap: List<AlbumAgg>
        val artistsSnap: List<ArtistAgg>
        val playlistsSnap: List<AppleLibrarySync.PlaylistRow>
        synchronized(dataLock) {
            songsSnap = songs.toList()
            albumsSnap = albumList
            artistsSnap = artistList
            playlistsSnap = playlists.toList()
        }
        val artistIdByName = HashMap<String, String>()
        for (s in songsSnap) {
            if (s.artist.isNotBlank() &&
                s.artistId.isNotBlank() &&
                s.artistId.all { it in '0'..'9' } &&
                s.artist !in artistIdByName
            ) {
                artistIdByName[s.artist] = s.artistId
            }
            if (s.title.lowercase(Locale.ROOT).contains(t) ||
                s.artist.lowercase(Locale.ROOT).contains(t) ||
                s.album.lowercase(Locale.ROOT).contains(t) ||
                ArtistAliasTable.matchesTargetKeys(s.artist, targetKeys)
            ) {
                out.songs.add(songFromRow(s))
            }
        }
        for (a in albumsSnap) {
            if (a.name.lowercase(Locale.ROOT).contains(t) ||
                a.artist.lowercase(Locale.ROOT).contains(t) ||
                ArtistAliasTable.matchesTargetKeys(a.artist, targetKeys)
            ) {
                out.albums.add(
                    AppleAlbum(
                        name = a.name,
                        artistName = a.artist,
                        artistId = artistIdByName[a.artist].orEmpty(),
                        artworkUrl = a.coverUrl,
                        trackCount = a.trackCount,
                        year = a.year,
                        isLibrary = true
                    )
                )
            }
        }
        for (ar in artistsSnap) {
            if (ar.name.lowercase(Locale.ROOT).contains(t) ||
                ArtistAliasTable.matchesTargetKeys(ar.name, targetKeys)
            ) {
                out.artists.add(
                    AppleArtist(
                        id = artistIdByName[ar.name].orEmpty(),
                        name = ar.name,
                        artworkUrl = ar.coverUrl,
                        songCount = ar.songCount,
                        albumCount = ar.albumCount,
                        genreNames = ar.genre.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
                    )
                )
            }
        }
        for (p in playlistsSnap) {
            if (p.name.lowercase(Locale.ROOT).contains(t)) {
                out.playlists.add(
                    ApplePlaylist(
                        id = p.id,
                        name = p.name,
                        artworkUrl = p.coverUrl,
                        trackCount = p.trackCount
                    )
                )
            }
        }
        return out
    }

    /** 按精准艺名或双语别名从本地资料库查找已缓存的艺人头像 URL。 */
    fun findLocalArtistAvatar(artistName: String): String {
        val targetKeys = ArtistAliasTable.exactTargetKeys(artistName)
        if (targetKeys.isEmpty()) return ""
        synchronized(dataLock) {
            for ((name, url) in avatars) {
                if (url.isNotBlank() && ArtistAliasTable.matchesTargetKeys(name, targetKeys)) {
                    return url
                }
            }
            for (ar in artistList) {
                if (ar.coverUrl.isNotBlank() && ArtistAliasTable.matchesTargetKeys(ar.name, targetKeys)) {
                    return ar.coverUrl
                }
            }
        }
        return ""
    }

    fun urisOf(rows: List<AppleLibrarySync.SongRow>): List<String> = rows.map { it.uri }

    fun remapQueue(uris: List<String>): List<String> {
        val out = ArrayList<String>(uris.size)
        for (u in uris) {
            val s = findSong(u)
            if (s != null) out.add(s.uri)
        }
        return out
    }

    fun songsOfAlbum(name: String, artist: String): List<AppleLibrarySync.SongRow> {
        val exact = albumList.firstOrNull { it.name == name && it.artist == artist }?.songs?.toList()
        if (!exact.isNullOrEmpty()) return exact
        val cleanName = name.trim()
        val cleanArtist = artist.trim()
        return albumList.firstOrNull {
            it.name.equals(cleanName, ignoreCase = true) &&
                (cleanArtist.isBlank() || it.artist.equals(cleanArtist, ignoreCase = true))
        }?.songs?.toList() ?: emptyList()
    }

    /** 查找资料库中该艺人的曲目（同时匹配单艺人、双语别名及合作曲目）。 */
    fun songsOfArtist(name: String): List<AppleLibrarySync.SongRow> {
        val target = name.trim()
        if (target.isEmpty()) return emptyList()
        val targetKeys = ArtistAliasTable.exactTargetKeys(target)
        return synchronized(dataLock) {
            songs.filter { row ->
                row.artist.equals(target, ignoreCase = true) ||
                    row.artist.contains(target, ignoreCase = true) ||
                    ArtistAliasTable.matchesTargetKeys(row.artist, targetKeys)
            }.sortedWith(compareBy({ it.album }, { it.discNumber }, { it.trackNumber }))
        }
    }

    private fun persistLocked() {
        val snap = AppleLibrarySync.Snapshot()
        snap.songs = ArrayList(songs)
        snap.playlists = ArrayList(playlists)
        snap.playlistTracks = HashMap(playlistTracks)
        snap.avatars = HashMap(avatars)
        snap.syncedAt = if (lastSyncAt > 0) lastSyncAt else System.currentTimeMillis()
        AppleLibrarySync.save(snapshotFile(), snap)
    }

    private fun rebuildLocked() {
        songByUri.clear()
        val albumMap = LinkedHashMap<String, AlbumAgg>()
        val artistMap = LinkedHashMap<String, ArtistAgg>()
        val genreVotes = HashMap<String, HashMap<String, Int>>()

        for (s in songs) {
            songByUri[s.uri] = s
            val key = s.album + "\u0000" + s.artist
            val agg = albumMap.getOrPut(key) {
                AlbumAgg().apply {
                    name = s.album
                    artist = s.artist
                    year = s.year
                    coverUrl = s.coverUrl
                }
            }
            agg.songs.add(s)
            if (agg.year == 0 && s.year > 0) agg.year = s.year
            if (agg.coverUrl.isEmpty() && s.coverUrl.isNotEmpty()) agg.coverUrl = s.coverUrl

            val ar = artistMap.getOrPut(s.artist) {
                ArtistAgg().apply {
                    name = s.artist
                    coverUrl = avatars[s.artist] ?: s.coverUrl
                }
            }
            ar.songCount++
            if (s.genre.isNotEmpty()) {
                val votes = genreVotes.getOrPut(s.artist) { HashMap() }
                for (g in s.genre.split("|")) {
                    if (g.isNotBlank()) votes[g] = (votes[g] ?: 0) + 1
                }
            }
        }

        for (agg in albumMap.values) {
            agg.trackCount = agg.songs.size
            agg.songs.sortWith(compareBy({ it.discNumber }, { it.trackNumber }))
            artistMap[agg.artist]?.let { it.albumCount++ }
        }
        for ((name, votes) in genreVotes) {
            val top = votes.maxByOrNull { it.value }?.key ?: ""
            artistMap[name]?.genre = top
        }
        for (ar in artistMap.values) {
            if (ar.coverUrl.isEmpty()) {
                ar.coverUrl = songs.firstOrNull { it.artist == ar.name }?.coverUrl ?: ""
            }
        }

        albumList = albumMap.values.sortedBy { it.name.lowercase(Locale.ROOT) }
        artistList = artistMap.values.sortedBy { it.name.lowercase(Locale.ROOT) }
        Logx.i(TAG, "聚合完成：${songs.size} 首 / ${albumList.size} 张专辑 / ${artistList.size} 位艺人")
    }

    fun notifyChanged() {
        listener?.invoke()
        for (l in listeners) {
            try {
                l()
            } catch (_: Throwable) {
            }
        }
    }

    fun syncTimeText(): String {
        if (lastSyncAt <= 0) return "尚未同步"
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return "上次同步 " + fmt.format(Date(lastSyncAt))
    }

    private fun songFromRow(s: AppleLibrarySync.SongRow): AppleSong = AppleSong(
        id = s.appleId,
        name = s.title,
        artistName = s.artist,
        albumName = s.album,
        isrc = s.isrc,
        artistId = s.artistId,
        durationMs = s.durationMs,
        trackNumber = s.trackNumber,
        discNumber = s.discNumber,
        year = s.year,
        artworkUrl = s.coverUrl,
        isLibrary = s.isLibrary,
        genre = s.genre,
        catalogId = s.catalogId
    )

    fun rowOf(s: AppleSong): AppleLibrarySync.SongRow = AppleLibrarySync.toRow(s)

    fun transientRow(
        catalogId: String,
        title: String,
        artist: String,
        artwork: String
    ): AppleLibrarySync.SongRow = AppleLibrarySync.SongRow().apply {
        appleId = catalogId
        this.title = title.ifEmpty { "未知歌曲" }
        this.artist = artist.ifEmpty { "未知艺人" }
        uri = "apple:$catalogId"
        coverUrl = artwork
        isLibrary = false
        this.catalogId = catalogId
    }
}

/** SharedPreferences 实现的 token 存储。 */
class PrefTokenStore(context: Context) : TokenStore {
    private val sp = context.getSharedPreferences("apple_music_auth", Context.MODE_PRIVATE)
    override fun getString(key: String, def: String): String = sp.getString(key, def) ?: def
    override fun putString(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        sp.edit().remove(key).apply()
    }
}
