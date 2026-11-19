package com.applemusic.android.apple

import com.applemusic.android.util.Logx
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 资料库同步：拉取歌曲、艺人官方头像与歌单元数据，原子落盘 `filesDir/library.json`。
 */
object AppleLibrarySync {

    private const val TAG = "AppleLibrarySync"
    const val PAGE = 100

    class Result {
        var ok = false
        var songs = 0
        var message = ""
    }

    class SongRow {
        var appleId = ""
        var uri = ""
        var title = ""
        var artist = ""
        var album = ""
        var durationMs = 0L
        var year = 0
        var genre = ""
        var coverUrl = ""
        var trackNumber = 0
        var discNumber = 0
        var isLibrary = true
        var catalogId = ""
        var artistId = ""
        var isrc = ""

        fun toJson(): JSONObject = JSONObject().apply {
            put("appleId", appleId)
            put("uri", uri)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("durationMs", durationMs)
            put("year", year)
            put("genre", genre)
            put("coverUrl", coverUrl)
            put("trackNumber", trackNumber)
            put("discNumber", discNumber)
            put("isLibrary", isLibrary)
            put("catalogId", catalogId)
            put("artistId", artistId)
            put("isrc", isrc)
        }

        companion object {
            fun fromJson(o: JSONObject): SongRow = SongRow().apply {
                appleId = o.optString("appleId", "")
                uri = o.optString("uri", "")
                title = o.optString("title", "")
                artist = o.optString("artist", "")
                album = o.optString("album", "")
                durationMs = o.optLong("durationMs", 0)
                year = o.optInt("year", 0)
                genre = o.optString("genre", "")
                coverUrl = o.optString("coverUrl", "")
                trackNumber = o.optInt("trackNumber", 0)
                discNumber = o.optInt("discNumber", 0)
                isLibrary = o.optBoolean("isLibrary", true)
                catalogId = o.optString("catalogId", "")
                artistId = o.optString("artistId", "")
                isrc = o.optString("isrc", "")
            }
        }
    }

    class PlaylistRow {
        var id = ""
        var name = ""
        var description = ""
        var coverUrl = ""
        var trackCount = 0

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("coverUrl", coverUrl)
            put("trackCount", trackCount)
        }

        companion object {
            fun fromJson(o: JSONObject): PlaylistRow = PlaylistRow().apply {
                id = o.optString("id", "")
                name = o.optString("name", "")
                description = o.optString("description", "")
                coverUrl = o.optString("coverUrl", "")
                trackCount = o.optInt("trackCount", 0)
            }
        }
    }

    fun toRow(s: AppleSong): SongRow = SongRow().apply {
        appleId = s.id
        title = s.name.ifEmpty { "未知歌曲" }
        artist = s.artistName.ifEmpty { "未知艺人" }
        album = s.albumName.ifEmpty { "未知专辑" }
        durationMs = s.durationMs
        year = s.year
        uri = "apple:" + s.id
        coverUrl = s.artworkUrl
        trackNumber = s.trackNumber
        discNumber = s.discNumber
        genre = s.genre
        isLibrary = s.isLibrary
        catalogId = s.catalogId
        artistId = s.artistId
        isrc = s.isrc
    }

    /**
     * 全量同步资料库歌曲并同时返回官方艺人头像（消除重复拉取）。
     */
    fun fetchSongsWithAvatars(
        api: AppleApi,
        onProgress: ((Int, String) -> Unit)? = null
    ): Pair<List<SongRow>, Map<String, String>> {
        val report: (Int, String) -> Unit = { p, n -> onProgress?.invoke(p, n) }
        api.ensureReady()
        report(2, "读取资料库")
        val all = ArrayList<SongRow>()
        val avatarProbe = LinkedHashMap<String, String>()
        var offset = 0
        var page = api.getLibrarySongs(PAGE, offset)
        val declaredTotal = page.total
        var guard = 0
        while (page.items.isNotEmpty() && guard < 200) {
            runCatching { api.localizeCatalogSongs(page.items, "cn") }
            for (song in page.items) {
                all.add(toRow(song))
                if (song.catalogId.isNotEmpty() && song.artistName.isNotEmpty() &&
                    !avatarProbe.containsKey(song.artistName)
                ) {
                    avatarProbe[song.artistName] = song.catalogId
                }
            }
            offset += page.items.size
            report(2 + minOf(80, offset / 10), "已读取 $offset 首")
            if (page.next.isBlank() || page.items.size < PAGE) break
            page = try {
                api.getLibrarySongs(PAGE, offset)
            } catch (e: Throwable) {
                if (e.message?.contains("404") == true) break
                throw e
            }
            guard++
        }
        Logx.i(TAG, "拉取完成，共 ${all.size} 首（Apple 声明 total=$declaredTotal）")
        report(92, "更新艺人头像")
        val avatars = fetchArtistAvatars(api, avatarProbe)
        report(100, "同步完成，共 ${all.size} 首")
        return all to avatars
    }

    fun fetchSongs(api: AppleApi, onProgress: ((Int, String) -> Unit)? = null): List<SongRow> =
        fetchSongsWithAvatars(api, onProgress).first

    fun fetchArtistAvatars(api: AppleApi, probe: Map<String, String>): Map<String, String> {
        val avatars = HashMap<String, String>()
        if (probe.isEmpty()) return avatars
        val songIds = ArrayList<String>(probe.keys.size)
        val bySongId = HashMap<String, String>()
        for ((artistName, songId) in probe) {
            if (songId.isBlank() || songId.startsWith("i.")) continue
            songIds.add(songId)
            bySongId[songId] = artistName
        }
        var i = 0
        while (i < songIds.size) {
            val slice = songIds.subList(i, minOf(i + 50, songIds.size))
            val found = api.catalogArtistsForSongs(slice)
            for ((songId, artist) in found) {
                val name = bySongId[songId] ?: continue
                if (name.isNotEmpty() && artist.artworkUrl.isNotEmpty()) {
                    avatars[name] = artist.artworkUrl
                }
            }
            i += 50
        }
        Logx.i(TAG, "艺人官方头像已更新：${avatars.size} 位")
        return avatars
    }

    fun fetchPlaylists(api: AppleApi, onProgress: ((Int, String) -> Unit)? = null): List<PlaylistRow> {
        val report: (Int, String) -> Unit = { p, n -> onProgress?.invoke(p, n) }
        api.ensureReady()
        val all = ArrayList<PlaylistRow>()
        var offset = 0
        var page = api.getLibraryPlaylists(PAGE, offset)
        var guard = 0
        while (page.items.isNotEmpty() && guard < 50) {
            for (p in page.items) {
                all.add(
                    PlaylistRow().apply {
                        id = p.id
                        name = p.name.ifEmpty { "未命名歌单" }
                        description = p.description
                        coverUrl = p.artworkUrl
                        trackCount = p.trackCount
                    }
                )
            }
            offset += page.items.size
            report(minOf(95, 10 + offset), "已读取 $offset 个歌单")
            if (page.next.isBlank() || page.items.size < PAGE) break
            page = try {
                api.getLibraryPlaylists(PAGE, offset)
            } catch (e: Throwable) {
                if (e.message?.contains("404") == true) break
                throw e
            }
            guard++
        }
        Logx.i(TAG, "歌单同步完成: ${all.size}")
        return all
    }

    fun loadPlaylistSongs(api: AppleApi, playlistId: String): List<SongRow> {
        val rows = ArrayList<SongRow>()
        var offset = 0
        var page: PageResult<AppleSong>
        try {
            page = api.getPlaylistTracks(playlistId, PAGE, offset)
        } catch (e: Throwable) {
            val msg = e.message ?: ""
            if (msg.contains("404")) {
                Logx.i(TAG, "loadPlaylistSongs $playlistId: 404（Apple 未开放该歌单曲目）")
                throw RuntimeException("Apple 没有开放这个歌单的曲目")
            }
            throw e
        }
        var guard = 0
        while (page.items.isNotEmpty() && guard < 100) {
            runCatching { api.localizeCatalogSongs(page.items, "cn") }
            for (s in page.items) {
                val row = toRow(s)
                row.isLibrary = false
                rows.add(row)
            }
            offset += page.items.size
            if (page.next.isBlank() || page.items.size < PAGE) break
            page = try {
                api.getPlaylistTracks(playlistId, PAGE, offset)
            } catch (e: Throwable) {
                if (e.message?.contains("404") == true) break
                throw e
            }
            guard++
        }
        Logx.i(TAG, "歌单 $playlistId 曲目 ${rows.size} 首")
        return rows
    }

    class Snapshot {
        var songs = ArrayList<SongRow>()
        var playlists = ArrayList<PlaylistRow>()
        var playlistTracks = HashMap<String, List<String>>()
        var avatars = HashMap<String, String>()
        var syncedAt = 0L
    }

    /** 原子落盘 library.json（tmp + fsync + renameTo），避免并发写或强杀导致快照损坏。 */
    fun save(file: File, snap: Snapshot) {
        val root = JSONObject()
        root.put("syncedAt", snap.syncedAt)
        val songsArr = JSONArray()
        for (s in snap.songs) songsArr.put(s.toJson())
        root.put("songs", songsArr)
        val plArr = JSONArray()
        for (p in snap.playlists) plArr.put(p.toJson())
        root.put("playlists", plArr)
        val ptObj = JSONObject()
        for ((k, v) in snap.playlistTracks) ptObj.put(k, JSONArray(v))
        root.put("playlistTracks", ptObj)
        val avObj = JSONObject()
        for ((k, v) in snap.avatars) avObj.put(k, v)
        root.put("avatars", avObj)

        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            try {
                fos.fd.sync()
            } catch (_: Throwable) {
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        Logx.i(TAG, "快照已保存：${snap.songs.size} 首 / ${snap.playlists.size} 个歌单 → ${file.absolutePath}")
    }

    fun load(file: File): Snapshot? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val snap = Snapshot()
            snap.syncedAt = root.optLong("syncedAt", 0)
            val songsArr = root.optJSONArray("songs") ?: JSONArray()
            for (i in 0 until songsArr.length()) {
                val obj = songsArr.optJSONObject(i) ?: continue
                snap.songs.add(SongRow.fromJson(obj))
            }
            val plArr = root.optJSONArray("playlists") ?: JSONArray()
            for (i in 0 until plArr.length()) {
                val obj = plArr.optJSONObject(i) ?: continue
                snap.playlists.add(PlaylistRow.fromJson(obj))
            }
            root.optJSONObject("playlistTracks")?.let { o ->
                for (k in o.keys()) {
                    val arr = o.optJSONArray(k) ?: continue
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        val u = arr.optString(i, "")
                        if (u.isNotEmpty()) list.add(u)
                    }
                    snap.playlistTracks[k] = list
                }
            }
            root.optJSONObject("avatars")?.let { o ->
                for (k in o.keys()) snap.avatars[k] = o.optString(k, "")
            }
            Logx.i(TAG, "快照已载入：${snap.songs.size} 首 / ${snap.playlists.size} 个歌单")
            snap
        } catch (e: Throwable) {
            Logx.e(TAG, "载入快照失败: ${e.message}")
            null
        }
    }
}
