package com.applemusic.android.apple

import android.content.Context
import com.applemusic.android.util.Logx
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Apple Music 播放缓存与离线下载存储管理器。
 *
 * 目录隔离：
 * - 临时播放缓存：`cacheDir/apple_music/<id>.m4a`（受容量上限和 LRU/窗口修剪管理）
 * - 用户离线下载：`filesDir/apple_music_downloads/<id>.m4a`（永久保护，绝不受系统清缓存或临时缓存清理影响）
 */
class AppleCache(private val context: Context, private val api: AppleApi) {

    companion object {
        private const val TAG = "AppleCache"
        private const val PREF = "apple_music_cache"
        private const val KEY_INDEX = "index_json"
        private const val KEY_SCHEMA = "schema_version"
        private const val KEY_MAX_BYTES = "max_cache_bytes"
        private const val DEFAULT_MAX_BYTES = 3L * 1024 * 1024 * 1024
        private const val MIN_MAX_BYTES = 100L * 1024 * 1024
        private const val MAX_MAX_BYTES = 20L * 1024 * 1024 * 1024
        private const val CACHE_SCHEMA = 6
        private const val MAX_MOOV_INSPECT_BYTES = 4 * 1024 * 1024
        private const val LAST_ACCESS_PERSIST_INTERVAL_MS = 60_000L
        private const val TARGET_RATIO = 0.9
        const val MIN_VALID_BYTES = 128L * 1024

        private val CONTAINER_TYPES = setOf(
            "moov", "trak", "mdia", "minf", "stbl", "mvex", "edts", "dinf", "stsd"
        )
        private val FORBIDDEN_DRM_TYPES = setOf("enca", "enct", "sinf", "pssh")

        /**
         * MP4 播放健康检查（完整支持 >6 分钟长音频且绝不误扫 mdat）：
         * 1. 通过 RandomAccessFile 顺序遍历顶层 box 头，确认包含 ftyp 与 moov；
         * 2. 读取完整 moov（支持最大 4MB，覆盖 1 小时以上长曲目）；
         * 3. 递归验证 moov 内部 box 树长度自洽，且确认不含 enca/enct/sinf/pssh 加密残留。
         */
        fun looksPlayable(file: File): Boolean {
            return try {
                val len = file.length()
                if (len < MIN_VALID_BYTES) return false
                RandomAccessFile(file, "r").use { raf ->
                    var hasFtyp = false
                    var moovOffset = -1L
                    var moovSize = 0L
                    var off = 0L
                    var guard = 0
                    val hdr = ByteArray(16)

                    while (off + 8 <= len && guard < 256) {
                        raf.seek(off)
                        raf.readFully(hdr, 0, 8)
                        var boxSize = u32(hdr, 0)
                        val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
                        var headerLen = 8L
                        if (boxSize == 1L) {
                            if (off + 16 > len) return false
                            raf.readFully(hdr, 8, 8)
                            boxSize = u64(hdr, 8)
                            headerLen = 16L
                        } else if (boxSize == 0L) {
                            boxSize = len - off
                        }
                        if (boxSize < headerLen || off + boxSize > len) return false
                        when (type) {
                            "ftyp" -> hasFtyp = true
                            "moov" -> {
                                moovOffset = off + headerLen
                                moovSize = boxSize - headerLen
                            }
                        }
                        off += boxSize
                        guard++
                    }

                    if (!hasFtyp || moovOffset < 0L || moovSize <= 0L) return false
                    if (moovSize > MAX_MOOV_INSPECT_BYTES) {
                        // 超长特例仅校验顶层 box 连续性
                        return off == len
                    }

                    val moovBody = ByteArray(moovSize.toInt())
                    raf.seek(moovOffset)
                    raf.readFully(moovBody)
                    verifyMoovTree(moovBody, 0, moovBody.size, 0)
                }
            } catch (_: Throwable) {
                false
            }
        }

        /** 递归校验 moov 内部 box 树：长度严格自洽 + 绝无未清理的 DRM 标识。 */
        private fun verifyMoovTree(d: ByteArray, start: Int, end: Int, depth: Int): Boolean {
            if (depth > 8) return true
            var off = start
            while (off + 8 <= end) {
                val size = be32(d, off)
                if (size < 8 || off + size > end) return false
                val type = String(d, off + 4, 4, Charsets.ISO_8859_1)
                if (type in FORBIDDEN_DRM_TYPES) return false
                if (type == "stsd") {
                    if (!verifyStsdEntries(d, off + 16, off + size)) return false
                } else if (type in CONTAINER_TYPES) {
                    if (!verifyMoovTree(d, off + 8, off + size, depth + 1)) return false
                }
                off += size
            }
            return off == end
        }

        private fun verifyStsdEntries(d: ByteArray, start: Int, end: Int): Boolean {
            var off = start
            while (off + 8 <= end) {
                val size = be32(d, off)
                if (size < 8 || off + size > end) return false
                val type = String(d, off + 4, 4, Charsets.ISO_8859_1)
                if (type in FORBIDDEN_DRM_TYPES) return false
                // 检查采样条目内部是否残留 sinf
                val entryEnd = off + size
                var p = off + 8
                while (p + 4 <= entryEnd) {
                    if (d[p] == 's'.code.toByte() && d[p + 1] == 'i'.code.toByte() &&
                        d[p + 2] == 'n'.code.toByte() && d[p + 3] == 'f'.code.toByte()
                    ) {
                        return false
                    }
                    p++
                }
                off += size
            }
            return off == end
        }

        private fun be32(d: ByteArray, off: Int): Int {
            if (off < 0 || off + 4 > d.size) return -1
            return ((d[off].toInt() and 0xFF) shl 24) or
                ((d[off + 1].toInt() and 0xFF) shl 16) or
                ((d[off + 2].toInt() and 0xFF) shl 8) or
                (d[off + 3].toInt() and 0xFF)
        }

        private fun u32(d: ByteArray, off: Int): Long = be32(d, off).toLong() and 0xFFFFFFFFL

        private fun u64(d: ByteArray, off: Int): Long =
            (u32(d, off) shl 32) or u32(d, off + 4)
    }

    class Entry {
        var songId = ""
        var fileName = ""
        var title = ""
        var artist = ""
        var album = ""
        var size = 0L
        var lastAccess = 0L
        var pinned = false
    }

    data class CacheItem(
        val songId: String,
        val title: String,
        val artist: String,
        val album: String,
        val size: Long,
        val lastAccess: Long,
        val pinned: Boolean,
        val downloading: Boolean
    )

    data class SongMetadata(
        val title: String,
        val artist: String,
        val album: String
    )

    /** single-flight 任务，支持 CountDownLatch 等待与多订阅者进度广播。 */
    private class Task {
        val latch = CountDownLatch(1)
        val progressListeners = CopyOnWriteArrayList<(AppleDownloader.Stage, Int, String) -> Unit>()

        @Volatile
        var path: String? = null

        @Volatile
        var error: Throwable? = null

        @Volatile
        var pinRequested: Boolean = false

        @Volatile
        var lastStage: AppleDownloader.Stage = AppleDownloader.Stage.RESOLVING

        @Volatile
        var lastPercent: Int = 0

        @Volatile
        var lastNote: String = ""
    }

    /** 临时流式缓存目录（受容量淘汰与清理约束）。 */
    private val tempDir: File = File(context.cacheDir, "apple_music")

    /** 永久离线下载目录（独立于系统 cacheDir，不受系统自动清缓存影响）。 */
    private val downloadsDir: File = File(context.filesDir, "apple_music_downloads")

    private val lock = Any()
    private val entries = ConcurrentHashMap<String, Entry>()
    private val inflight = ConcurrentHashMap<String, Task>()
    private val scheduledPreloads = ConcurrentHashMap.newKeySet<String>()

    private val preloadExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "audio-preload").apply { isDaemon = true } },
        { runnable, executor ->
            val dropped = executor.queue.poll()
            if (dropped is PreloadTask) {
                scheduledPreloads.remove(dropped.songId)
                Logx.i(TAG, "预加载队列已替换旧请求：${dropped.songId}")
            }
            if (!executor.queue.offer(runnable)) {
                if (runnable is PreloadTask) scheduledPreloads.remove(runnable.songId)
                Logx.w(TAG, "预加载任务未能排队")
            }
        }
    )

    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Volatile
    private var maxBytes = prefs.getLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES)

    @Volatile
    private var lastAccessPersistAt = 0L

    fun maxCacheBytes(): Long = maxBytes

    fun setMaxCacheBytes(bytes: Long): Int {
        val boundedBytes = bytes.coerceIn(MIN_MAX_BYTES, MAX_MAX_BYTES)
        maxBytes = boundedBytes
        prefs.edit().putLong(KEY_MAX_BYTES, boundedBytes).apply()
        return evictIfNeeded()
    }

    fun init() {
        synchronized(lock) {
            if (!tempDir.exists()) tempDir.mkdirs()
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            loadIndexLocked()
            migratePinnedFilesLocked()

            val schema = prefs.getInt(KEY_SCHEMA, 0)
            if (schema != CACHE_SCHEMA) {
                // 升级缓存格式时只清理临时缓存，绝不误删用户已下载歌曲
                clearAllLocked(keepPinned = true)
                prefs.edit().putInt(KEY_SCHEMA, CACHE_SCHEMA).apply()
                Logx.w(TAG, "缓存格式升级 $schema → $CACHE_SCHEMA，已清理旧临时缓存并保留离线下载")
            }
            sweepCorruptedLocked()
            sweepTempFilesLocked()
            evictIfNeededLocked()
            Logx.i(
                TAG,
                "init: temp=${tempDir.absolutePath} downloads=${downloadsDir.absolutePath} " +
                    "count=${entries.size} pinned=${entries.values.count { it.pinned }} bytes=${statsBytes()}"
            )
        }
    }

    private fun fileFor(entry: Entry): File =
        File(if (entry.pinned) downloadsDir else tempDir, entry.fileName)

    /** 把旧版本存在 cacheDir 的已下载歌曲迁移到受保护的 filesDir/apple_music_downloads。 */
    private fun migratePinnedFilesLocked() {
        var migrated = 0
        for (e in entries.values) {
            if (!e.pinned) continue
            val oldFile = File(tempDir, e.fileName)
            val newFile = File(downloadsDir, e.fileName)
            if (oldFile.exists() && !newFile.exists()) {
                if (oldFile.renameTo(newFile)) {
                    migrated++
                } else {
                    try {
                        oldFile.copyTo(newFile, overwrite = true)
                        oldFile.delete()
                        migrated++
                    } catch (t: Throwable) {
                        Logx.w(TAG, "迁移已下载歌曲失败 ${e.songId}: ${t.message}")
                    }
                }
            }
        }
        if (migrated > 0) {
            Logx.i(TAG, "已将 $migrated 首已下载歌曲迁移至永久存储目录")
        }
    }

    private fun loadIndexLocked() {
        try {
            val json = prefs.getString(KEY_INDEX, "[]") ?: "[]"
            val arr = JSONArray(json)
            entries.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val e = Entry()
                e.songId = o.optString("songId", "")
                e.fileName = o.optString("fileName", "")
                e.title = o.optString("title", "")
                e.artist = o.optString("artist", "")
                e.album = o.optString("album", "")
                e.size = o.optLong("size", 0)
                e.lastAccess = o.optLong("lastAccess", 0)
                e.pinned = o.optBoolean("pinned", false)
                if (e.songId.isNotEmpty() && e.fileName.isNotEmpty()) {
                    entries[e.songId] = e
                }
            }
        } catch (e: Throwable) {
            Logx.e(TAG, "loadIndex failed: ${e.message}")
        }
    }

    private fun persistLocked() {
        val arr = JSONArray()
        for (e in entries.values) {
            arr.put(
                JSONObject().apply {
                    put("songId", e.songId)
                    put("fileName", e.fileName)
                    put("title", e.title)
                    put("artist", e.artist)
                    put("album", e.album)
                    put("size", e.size)
                    put("lastAccess", e.lastAccess)
                    put("pinned", e.pinned)
                }
            )
        }
        prefs.edit().putString(KEY_INDEX, arr.toString()).apply()
    }

    private fun sweepCorruptedLocked() {
        val bad = ArrayList<String>()
        for ((songId, e) in entries) {
            val f = fileFor(e)
            if (!f.exists() || f.length() < MIN_VALID_BYTES) {
                bad.add(songId)
                try {
                    f.delete()
                } catch (_: Throwable) {
                }
            }
        }
        if (bad.isNotEmpty()) {
            for (id in bad) entries.remove(id)
            persistLocked()
            Logx.w(TAG, "sweepCorrupted: 清理 ${bad.size} 个损坏条目")
        }
    }

    private fun sweepTempFilesLocked() {
        var removed = 0
        for (d in listOf(tempDir, downloadsDir)) {
            d.listFiles()?.forEach { f ->
                if (f.name.endsWith(".part") && f.delete()) removed++
            }
        }
        if (removed > 0) Logx.w(TAG, "sweepTempFiles: 清理 $removed 个 .part")
    }

    /**
     * 命中则返回本地文件绝对路径，未命中/损坏返回 null。
     * @param touchAccess 仅实际播放命中时更新 lastAccess，纯查询不写 SharedPreferences。
     */
    fun getCachedPath(songId: String, touchAccess: Boolean = true): String? {
        val entryAndFile = synchronized(lock) {
            val e = entries[songId] ?: return null
            val f = fileFor(e)
            if (!f.exists()) {
                entries.remove(songId)
                persistLocked()
                return null
            }
            if (f.length() < MIN_VALID_BYTES) {
                Logx.w(TAG, "缓存已损坏 $songId size=${f.length()}，作废")
                f.delete()
                entries.remove(songId)
                persistLocked()
                return null
            }
            e to f
        }
        val (e, f) = entryAndFile
        // 健康检查在锁外执行，避免阻塞其他线程
        if (!looksPlayable(f)) {
            Logx.w(TAG, "缓存健康检查未通过，作废重下：$songId")
            synchronized(lock) {
                if (entries[songId] === e && !inflight.containsKey(songId)) {
                    f.delete()
                    entries.remove(songId)
                    persistLocked()
                }
            }
            return null
        }
        if (touchAccess) {
            val now = System.currentTimeMillis()
            if (now - e.lastAccess > LAST_ACCESS_PERSIST_INTERVAL_MS) {
                synchronized(lock) {
                    e.lastAccess = now
                    if (now - lastAccessPersistAt > LAST_ACCESS_PERSIST_INTERVAL_MS) {
                        lastAccessPersistAt = now
                        persistLocked()
                    }
                }
            }
        }
        return f.absolutePath
    }

    /** 轻量只读检查（绝不触发 SharedPreferences 写入或完整 moov 重读）。 */
    fun has(songId: String): Boolean {
        val e = entries[songId] ?: return false
        val f = fileFor(e)
        return f.isFile && f.length() >= MIN_VALID_BYTES
    }

    fun isPreloading(songId: String): Boolean = inflight.containsKey(songId)

    fun isPinned(songId: String): Boolean = entries[songId]?.pinned == true

    fun pinnedSongIds(): Set<String> = synchronized(lock) {
        entries.values.filter { it.pinned }.mapTo(HashSet()) { it.songId }
    }

    fun setPinned(songId: String, pinned: Boolean) {
        synchronized(lock) {
            val e = entries[songId] ?: return
            if (e.pinned == pinned) return
            val oldFile = fileFor(e)
            e.pinned = pinned
            val newFile = fileFor(e)
            if (oldFile.exists() && oldFile.absolutePath != newFile.absolutePath) {
                newFile.parentFile?.mkdirs()
                if (!oldFile.renameTo(newFile)) {
                    try {
                        oldFile.copyTo(newFile, overwrite = true)
                        oldFile.delete()
                    } catch (t: Throwable) {
                        Logx.w(TAG, "移动缓存文件失败 ${e.songId}: ${t.message}")
                        e.pinned = !pinned
                        return
                    }
                }
            }
            persistLocked()
        }
    }

    private fun rememberMetadata(songId: String, metadata: SongMetadata?) {
        if (metadata == null) return
        synchronized(lock) {
            val entry = entries[songId] ?: return
            var changed = false
            if (metadata.title.isNotBlank() && entry.title != metadata.title) {
                entry.title = metadata.title
                changed = true
            }
            if (metadata.artist.isNotBlank() && entry.artist != metadata.artist) {
                entry.artist = metadata.artist
                changed = true
            }
            if (metadata.album.isNotBlank() && entry.album != metadata.album) {
                entry.album = metadata.album
                changed = true
            }
            if (changed) persistLocked()
        }
    }

    fun cachedItems(): List<CacheItem> = synchronized(lock) {
        var mutated = false
        val out = entries.values.toList().mapNotNull { entry ->
            val file = fileFor(entry)
            if (!file.isFile) {
                entries.remove(entry.songId, entry)
                mutated = true
                null
            } else {
                CacheItem(
                    songId = entry.songId,
                    title = entry.title,
                    artist = entry.artist,
                    album = entry.album,
                    size = file.length(),
                    lastAccess = entry.lastAccess,
                    pinned = entry.pinned,
                    downloading = inflight.containsKey(entry.songId)
                )
            }
        }.sortedByDescending { it.lastAccess }
        if (mutated) persistLocked()
        out
    }

    fun removeCached(songId: String): Boolean = synchronized(lock) {
        if (inflight.containsKey(songId)) return false
        val entry = entries[songId] ?: return false
        val file = fileFor(entry)
        if (file.exists() && !file.delete()) {
            throw IOException("无法删除缓存文件：${file.name}")
        }
        if (!entries.remove(songId, entry)) return false
        persistLocked()
        true
    }

    /**
     * 穿透修剪临时缓存：只保留播放窗口内曲目（上一首/当前首/下一首），
     * 严格按真实文件名匹配（修复 i.xxx 去掉点号后误删曲库歌曲的缺陷），绝不触碰 downloadsDir。
     */
    fun retainOnly(allowedSongIds: Set<String>) {
        if (allowedSongIds.isEmpty()) return
        val normalizedAllowed = allowedSongIds.map { it.removePrefix("apple:") }.toSet()
        val allowedFileNames = normalizedAllowed.mapTo(HashSet()) { fileNameFor(it) }
        val now = System.currentTimeMillis()
        val gracePeriodMs = 15 * 60 * 1000L

        synchronized(lock) {
            var changed = false
            entries.keys.toList().forEach { songId ->
                val entry = entries[songId] ?: return@forEach
                if (entry.pinned || inflight.containsKey(songId)) return@forEach
                val cleanSongId = songId.removePrefix("apple:")
                val isAllowed = cleanSongId in normalizedAllowed || entry.fileName in allowedFileNames
                if (!isAllowed) {
                    val file = File(tempDir, entry.fileName)
                    val age = now - file.lastModified()
                    if (file.exists() && age > gracePeriodMs) {
                        if (file.delete()) {
                            Logx.i(TAG, "修剪缓存：已删除过期临时文件 $songId (${entry.title})")
                            entries.remove(songId)
                            changed = true
                        }
                    } else if (!file.exists()) {
                        entries.remove(songId)
                        changed = true
                    }
                }
            }

            val knownTempFiles = entries.values
                .filter { !it.pinned }
                .mapTo(HashSet()) { it.fileName }
            val inflightFiles = inflight.keys.flatMapTo(HashSet()) { id ->
                val name = fileNameFor(id)
                listOf(name, "$name.part")
            }

            tempDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".m4a")) {
                    val isProtected = file.name in knownTempFiles ||
                        file.name in allowedFileNames ||
                        file.name in inflightFiles
                    val age = now - file.lastModified()
                    if (!isProtected && age > gracePeriodMs) {
                        if (file.delete()) {
                            Logx.i(TAG, "修剪缓存：已删除孤立临时缓存 ${file.name}")
                        }
                    }
                }
            }
            if (changed) persistLocked()
        }
    }

    /**
     * 清理临时缓存：
     * - 严格保留 downloadsDir 中的所有已下载歌曲；
     * - 严格保护进行中的下载（含 .part 临时文件）与当前正在播放的歌曲 [protectedSongIds]。
     */
    fun clearTemporaryCacheOnly(protectedSongIds: Set<String> = emptySet()): Int = synchronized(lock) {
        val protectedIds = protectedSongIds.mapTo(HashSet()) { it.removePrefix("apple:") }
        val protectedFileNames = HashSet<String>()
        for (id in protectedIds) {
            val name = fileNameFor(id)
            protectedFileNames.add(name)
            protectedFileNames.add("$name.part")
        }
        for (id in inflight.keys) {
            val name = fileNameFor(id)
            protectedFileNames.add(name)
            protectedFileNames.add("$name.part")
        }

        var removed = 0
        entries.values.toList().forEach { entry ->
            val cleanId = entry.songId.removePrefix("apple:")
            if (!entry.pinned && !inflight.containsKey(entry.songId) && cleanId !in protectedIds) {
                val file = File(tempDir, entry.fileName)
                if (!file.exists() || file.delete()) {
                    if (entries.remove(entry.songId, entry)) removed++
                }
            } else if (!entry.pinned) {
                protectedFileNames.add(entry.fileName)
            }
        }

        tempDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in protectedFileNames) {
                if (file.delete()) removed++
            }
        }
        persistLocked()
        removed
    }

    fun isSongDownloaded(songId: String): Boolean {
        val entry = entries[songId] ?: return false
        return entry.pinned && fileFor(entry).isFile
    }

    fun getDownloadedEntries(): List<Entry> = synchronized(lock) {
        entries.values
            .filter { it.pinned && fileFor(it).isFile }
            .sortedByDescending { it.lastAccess }
    }

    fun deleteDownloadedSong(songId: String): Boolean = synchronized(lock) {
        if (inflight.containsKey(songId)) return false
        val entry = entries[songId] ?: return false
        val file = fileFor(entry)
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            entries.remove(songId)
            persistLocked()
        }
        deleted
    }

    fun clearAllCached(): Int = clearTemporaryCacheOnly()

    /** 目标写入路径。 */
    fun targetPathFor(songId: String, pinned: Boolean = false): String =
        File(if (pinned) downloadsDir else tempDir, fileNameFor(songId)).absolutePath

    private fun fileNameFor(songId: String): String {
        val s = songId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        return (if (s.isNotEmpty()) s else "song") + ".m4a"
    }

    /**
     * 确保某首歌已在本地就绪，返回可直接交给 ExoPlayer 的路径。
     * single-flight：并发调用共享同一个下载任务、同步接收进度并支持线程中断取消。
     */
    fun ensure(
        songId: String,
        isLibrary: Boolean,
        onProgress: ((AppleDownloader.Stage, Int, String) -> Unit)? = null,
        metadata: SongMetadata? = null,
        pinOnComplete: Boolean = false
    ): String {
        require(songId.isNotEmpty()) { "歌曲 ID 为空" }
        getCachedPath(songId, touchAccess = true)?.let { existingPath ->
            rememberMetadata(songId, metadata)
            if (pinOnComplete && !isPinned(songId)) {
                setPinned(songId, true)
                onProgress?.invoke(AppleDownloader.Stage.DONE, 100, "完成")
                return getCachedPath(songId, touchAccess = false) ?: existingPath
            }
            onProgress?.invoke(AppleDownloader.Stage.DONE, 100, "完成")
            return existingPath
        }

        var mine = false
        val task: Task
        synchronized(lock) {
            val existing = inflight[songId]
            if (existing != null) {
                task = existing
                if (pinOnComplete) task.pinRequested = true
                if (onProgress != null) task.progressListeners.add(onProgress)
            } else {
                task = Task().apply {
                    pinRequested = pinOnComplete
                    if (onProgress != null) progressListeners.add(onProgress)
                }
                inflight[songId] = task
                mine = true
            }
        }

        if (!mine) {
            Logx.i(TAG, "ensure: 复用进行中的任务 $songId")
            if (onProgress != null && task.lastPercent > 0) {
                try {
                    onProgress(task.lastStage, task.lastPercent, task.lastNote)
                } catch (_: Throwable) {
                }
            }
            try {
                task.latch.await()
            } catch (ie: InterruptedException) {
                if (onProgress != null) task.progressListeners.remove(onProgress)
                Thread.currentThread().interrupt()
                throw ie
            } finally {
                if (onProgress != null) task.progressListeners.remove(onProgress)
            }
            task.error?.let { throw RuntimeException(it.message ?: "下载失败", it) }
            getCachedPath(songId, touchAccess = true)?.let { p ->
                rememberMetadata(songId, metadata)
                if (pinOnComplete && !isPinned(songId)) {
                    setPinned(songId, true)
                    return getCachedPath(songId, touchAccess = false) ?: p
                }
                return p
            }
            task.path?.let { return it }
            throw RuntimeException("下载失败（无结果）")
        }

        try {
            Logx.i(TAG, "ensure: 发起下载 $songId isLibrary=$isLibrary pin=$pinOnComplete")
            val broadcastProgress: (AppleDownloader.Stage, Int, String) -> Unit = { st, pct, note ->
                task.lastStage = st
                task.lastPercent = pct
                task.lastNote = note
                for (listener in task.progressListeners) {
                    try {
                        listener(st, pct, note)
                    } catch (_: Throwable) {
                    }
                }
            }
            val path = runDownload(songId, isLibrary, broadcastProgress, metadata, task)
            task.path = path
            return path
        } catch (t: Throwable) {
            task.error = t
            throw t
        } finally {
            synchronized(lock) {
                if (inflight[songId] === task) inflight.remove(songId)
            }
            task.latch.countDown()
        }
    }

    private fun runDownload(
        songId: String,
        isLibrary: Boolean,
        onProgress: ((AppleDownloader.Stage, Int, String) -> Unit)?,
        metadata: SongMetadata?,
        task: Task
    ): String {
        val fileName = fileNameFor(songId)
        val initialPin = task.pinRequested
        val targetFile = File(if (initialPin) downloadsDir else tempDir, fileName)
        val nonFailedProgress: ((AppleDownloader.Stage, Int, String) -> Unit)? = onProgress?.let { cb ->
            { st, pct, note ->
                if (st != AppleDownloader.Stage.FAILED) {
                    cb(st, pct, note)
                }
            }
        }
        var res = AppleDownloader.downloadSong(api, songId, isLibrary, targetFile.absolutePath, nonFailedProgress)
        if (!res.ok && !Thread.currentThread().isInterrupted) {
            val altId = if (isLibrary) api.librarySongCatalogId(songId) else ""
            if (altId.isNotEmpty() && altId != songId) {
                Logx.w(TAG, "ensure: universalLibraryId 失败（${res.message}），改用 catalogId=$altId 重试 $songId")
                res = AppleDownloader.downloadSong(api, altId, false, targetFile.absolutePath, nonFailedProgress)
            } else if (!isLibrary) {
                val libId = MusicRepository.findSong(songId)?.takeIf { it.isLibrary }?.appleId
                    ?.takeIf { it.startsWith("i.") }
                if (!libId.isNullOrEmpty()) {
                    Logx.w(TAG, "ensure: salableAdamId 失败（${res.message}），改用资料库标识重试 $libId")
                    res = AppleDownloader.downloadSong(api, libId, true, targetFile.absolutePath, nonFailedProgress)
                }
            }
        }
        if (!res.ok) {
            onProgress?.invoke(AppleDownloader.Stage.FAILED, 0, res.message.ifEmpty { "下载失败" })
            throw RuntimeException(res.message.ifEmpty { "下载失败" })
        }

        val finalPinned = task.pinRequested
        var finalFile = targetFile
        if (finalPinned && !initialPin) {
            val dest = File(downloadsDir, fileName)
            dest.parentFile?.mkdirs()
            if (targetFile.renameTo(dest)) {
                finalFile = dest
            } else {
                targetFile.copyTo(dest, overwrite = true)
                targetFile.delete()
                finalFile = dest
            }
        }

        synchronized(lock) {
            val e = Entry().apply {
                this.songId = songId
                this.fileName = fileName
                this.title = metadata?.title.orEmpty()
                this.artist = metadata?.artist.orEmpty()
                this.album = metadata?.album.orEmpty()
                this.size = res.bytes.toLong()
                this.lastAccess = System.currentTimeMillis()
                this.pinned = finalPinned
            }
            entries[songId] = e
            persistLocked()
            evictIfNeededLocked()
        }
        Logx.i(TAG, "ensure: 就绪 $songId (${res.bytes} bytes, pinned=$finalPinned)")
        return finalFile.absolutePath
    }

    /** 后台预取（fire-and-forget）。 */
    fun preload(
        songId: String,
        isLibrary: Boolean,
        metadata: SongMetadata? = null
    ) {
        if (songId.isEmpty() || entries.containsKey(songId) || inflight.containsKey(songId)) return
        if (!scheduledPreloads.add(songId)) return
        preloadExecutor.execute(PreloadTask(songId, isLibrary, metadata))
    }

    private inner class PreloadTask(
        val songId: String,
        private val isLibrary: Boolean,
        private val metadata: SongMetadata?
    ) : Runnable {
        override fun run() {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                ensure(songId, isLibrary, metadata = metadata)
            } catch (e: Exception) {
                Logx.w(TAG, "preload 失败 $songId: ${e.message}")
            } finally {
                scheduledPreloads.remove(songId)
            }
        }
    }

    /** 播放 IO 失败时作废损坏的临时缓存，但不剥离/误删正常的用户下载条目。 */
    fun invalidate(songId: String, includePinned: Boolean = false) {
        synchronized(lock) {
            if (inflight.containsKey(songId)) return
            val e = entries[songId] ?: return
            if (e.pinned && !includePinned) {
                // 仅在文件确实损坏不可播时才删，否则保留下载标记
                val f = fileFor(e)
                if (f.exists() && looksPlayable(f)) return
            }
            fileFor(e).delete()
            entries.remove(songId)
            persistLocked()
        }
    }

    fun remove(songId: String) {
        synchronized(lock) {
            if (inflight.containsKey(songId)) return
            val e = entries.remove(songId) ?: return
            fileFor(e).delete()
            persistLocked()
        }
    }

    fun clearAll(keepPinned: Boolean = true): Int = synchronized(lock) {
        clearAllLocked(keepPinned)
    }

    private fun clearAllLocked(keepPinned: Boolean): Int {
        val ids = entries.values
            .filter { (!keepPinned || !it.pinned) && !inflight.containsKey(it.songId) }
            .map { it.songId }
        var removed = 0
        for (id in ids) {
            val e = entries.remove(id) ?: continue
            if (!fileFor(e).exists() || fileFor(e).delete()) {
                removed++
            }
        }
        persistLocked()
        return removed
    }

    fun statsBytes(): Long = entries.values.sumOf { it.size }
    fun statsCount(): Int = entries.size

    fun evictIfNeeded(): Int = synchronized(lock) { evictIfNeededLocked() }

    private fun evictIfNeededLocked(): Int {
        var total = entries.values.filter { !it.pinned }.sumOf { it.size }
        val limit = maxBytes
        if (total <= limit) return 0
        val target = (limit * TARGET_RATIO).toLong()
        val list = entries.values
            .filter { !it.pinned && !inflight.containsKey(it.songId) }
            .sortedBy { it.lastAccess }
        var removed = 0
        for (e in list) {
            if (total <= target) break
            val file = fileFor(e)
            if (file.exists() && !file.delete()) {
                Logx.w(TAG, "无法淘汰播放缓存：${file.name}")
                continue
            }
            entries.remove(e.songId)
            total -= e.size
            removed++
        }
        if (removed > 0) {
            persistLocked()
            Logx.i(TAG, "evicted $removed files, now temp=$total bytes")
        }
        return removed
    }

    fun cacheDir(): File = tempDir
}
