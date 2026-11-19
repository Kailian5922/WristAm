package com.applemusic.lyricstool.data

import android.content.Context
import android.util.LruCache
import com.applemusic.android.lyrics.LyricLine
import com.applemusic.android.lyrics.LyricWord
import com.applemusic.android.lyrics.Lyrics
import com.applemusic.android.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 线程安全、双层（内存 LRU + 磁盘 `cacheDir/lyrics-cache`）歌词缓存：
 * - 支持按 locale 隔离、30 天 TTL 淘汰、拒绝永久缓存降级失败条目；
 * - 落盘剔除原始 TTML/QRC 调试文本冗余，节省手表端闪存；
 * - 支持 [clearExcept] 在清理临时缓存时保留已下载离线歌曲的歌词。
 */
class LyricsCache(context: Context) {

    private val appContext = context.applicationContext
    private val directory = File(appContext.cacheDir, "lyrics-cache")

    data class CacheItem(
        val songId: String,
        val sources: Set<String>,
        val lastAccess: Long
    )

    suspend fun get(source: String, songId: String): FetchedLyrics? =
        withContext(Dispatchers.IO) {
            val cleanId = songId.removePrefix("apple:").trim()
            if (cleanId.isEmpty() || source !in SOURCES) return@withContext null
            val memKey = memoryKey(source, cleanId)
            synchronized(memoryCache) {
                memoryCache.get(memKey)?.let { return@withContext it }
            }

            synchronized(ioLock) {
                val file = cacheFile(source, cleanId)
                if (!file.isFile) return@withContext null
                try {
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    val cachedAt = json.optLong("cachedAt", 0L)
                    if (cachedAt > 0L && System.currentTimeMillis() - cachedAt > TTL_MS) {
                        file.delete()
                        return@withContext null
                    }
                    val fetched = decode(json)
                    file.setLastModified(System.currentTimeMillis())
                    synchronized(memoryCache) { memoryCache.put(memKey, fetched) }
                    fetched
                } catch (error: Exception) {
                    Logx.w(TAG, "歌词缓存读取失败 $source/$cleanId：${error.message}")
                    file.delete()
                    null
                }
            }
        }

    suspend fun put(source: String, songId: String, lyrics: FetchedLyrics) =
        withContext(Dispatchers.IO) {
            val cleanId = songId.removePrefix("apple:").trim()
            if (cleanId.isEmpty() || source !in SOURCES) return@withContext
            // 拒绝永久缓存空歌词或临时翻译拉取降级结果
            if (lyrics.lyrics.lines.isEmpty() || !lyrics.translationWarning.isNullOrBlank()) {
                return@withContext
            }
            val memKey = memoryKey(source, cleanId)
            val compact = lyrics.copy(ttml = null, qqQrcDebugText = null)
            synchronized(memoryCache) { memoryCache.put(memKey, compact) }

            synchronized(ioLock) {
                val target = cacheFile(source, cleanId)
                val temporary = File(directory, "${target.name}.${UUID.randomUUID()}.tmp")
                try {
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw IOException("无法创建歌词缓存目录")
                    }
                    val encoded = encode(source, cleanId, compact).toByteArray(Charsets.UTF_8)
                    FileOutputStream(temporary).use { output ->
                        output.write(encoded)
                    }
                    if (!temporary.renameTo(target)) {
                        temporary.copyTo(target, overwrite = true)
                        temporary.delete()
                    }
                    if (writesSinceTrim.incrementAndGet() >= TRIM_INTERVAL) {
                        writesSinceTrim.set(0)
                        trimOldEntriesLocked()
                    }
                } catch (error: Exception) {
                    Logx.w(TAG, "歌词缓存写入失败 $source/$cleanId：${error.message}")
                    if (temporary.exists()) temporary.delete()
                }
            }
        }

    suspend fun cachedItems(): List<CacheItem> = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            directory.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        val json = JSONObject(file.readText(Charsets.UTF_8))
                        val songId = json.optString("songId").takeIf(String::isNotBlank)
                            ?: return@mapNotNull null
                        val source = json.optString("source").takeIf(String::isNotBlank)
                            ?: return@mapNotNull null
                        CacheItem(songId, setOf(source), file.lastModified())
                    } catch (_: Exception) {
                        null
                    }
                }
                ?.groupBy(CacheItem::songId)
                ?.map { (songId, entries) ->
                    CacheItem(
                        songId = songId,
                        sources = entries.flatMap(CacheItem::sources).toSet(),
                        lastAccess = entries.maxOf(CacheItem::lastAccess)
                    )
                }
                ?.sortedByDescending(CacheItem::lastAccess)
                .orEmpty()
        }
    }

    suspend fun removeForSong(songId: String): Int = withContext(Dispatchers.IO) {
        val cleanId = songId.removePrefix("apple:").trim()
        if (cleanId.isEmpty()) return@withContext 0
        synchronized(ioLock) {
            var removed = 0
            for (source in SOURCES) {
                synchronized(memoryCache) { memoryCache.remove(memoryKey(source, cleanId)) }
                val file = cacheFile(source, cleanId)
                if (file.exists() && file.delete()) removed++
            }
            removed
        }
    }

    /** 清理全部临时歌词缓存，但保留 [pinnedSongIds] 对应已下载歌曲的离线歌词。 */
    suspend fun clearExcept(pinnedSongIds: Set<String>): Int = withContext(Dispatchers.IO) {
        val normalizedPinned = pinnedSongIds.mapTo(HashSet()) { it.removePrefix("apple:").trim() }
        val keepFiles = HashSet<String>()
        for (id in normalizedPinned) {
            if (id.isEmpty()) continue
            for (source in SOURCES) {
                keepFiles.add(cacheFile(source, id).name)
            }
        }
        synchronized(ioLock) {
            synchronized(memoryCache) { memoryCache.evictAll() }
            var removed = 0
            directory.listFiles()?.forEach { file ->
                if (file.isFile && file.name !in keepFiles) {
                    if (file.delete() || !file.exists()) removed++
                }
            }
            // 同时清理旧版本遗留在 filesDir/lyrics-cache 的大体积缓存目录
            val legacyDir = File(appContext.filesDir, "lyrics-cache")
            if (legacyDir.isDirectory) {
                legacyDir.listFiles()?.forEach { it.delete() }
                legacyDir.delete()
            }
            removed
        }
    }

    suspend fun clearAll(): Int = clearExcept(emptySet())

    private fun localeKey(): String = Locale.getDefault().toLanguageTag()

    private fun memoryKey(source: String, songId: String): String =
        "$source:$songId:${localeKey()}"

    private fun cacheFile(source: String, songId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$source:$songId:${localeKey()}".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(directory, "$digest.json")
    }

    private fun encode(source: String, songId: String, fetched: FetchedLyrics): String {
        val lines = JSONArray()
        fetched.lyrics.lines.forEach { line ->
            val words = JSONArray()
            line.words.forEach { word ->
                words.put(
                    JSONObject()
                        .put("start", word.startMs)
                        .put("end", word.endMs)
                        .put("charStart", word.charStart)
                        .put("charEnd", word.charEnd)
                )
            }
            lines.put(
                JSONObject()
                    .put("start", line.startMs)
                    .put("end", line.endMs)
                    .put("text", line.text)
                    .put("translation", line.translation ?: JSONObject.NULL)
                    .put("key", line.key ?: JSONObject.NULL)
                    .put("words", words)
            )
        }
        return JSONObject()
            .put("version", SCHEMA_VERSION)
            .put("source", source)
            .put("songId", songId)
            .put("cachedAt", System.currentTimeMillis())
            .put("synced", fetched.lyrics.synced)
            .put("kind", fetched.lyrics.kind)
            .put("lines", lines)
            .toString()
    }

    private fun decode(json: JSONObject): FetchedLyrics {
        require(json.optInt("version") == SCHEMA_VERSION) { "缓存格式版本不匹配" }
        val cachedLines = json.getJSONArray("lines")
        val lines = List(cachedLines.length()) { index ->
            val line = cachedLines.getJSONObject(index)
            val cachedWords = line.getJSONArray("words")
            val words = List(cachedWords.length()) { wordIndex ->
                val word = cachedWords.getJSONObject(wordIndex)
                LyricWord(
                    startMs = word.getLong("start"),
                    endMs = word.getLong("end"),
                    charStart = word.getInt("charStart"),
                    charEnd = word.getInt("charEnd")
                )
            }
            LyricLine(
                startMs = line.getLong("start"),
                endMs = line.getLong("end"),
                text = line.getString("text"),
                words = words,
                translation = line.optionalString("translation"),
                key = line.optionalString("key")
            )
        }
        return FetchedLyrics(
            lyrics = Lyrics(lines, json.getBoolean("synced"), json.optString("kind")),
            ttml = null,
            translationWarning = null,
            qqQrcDebugText = null
        )
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun trimOldEntriesLocked() {
        val entries = directory.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?: return
        if (entries.size <= MAX_ENTRIES) return
        entries.sortedByDescending(File::lastModified)
            .drop(MAX_ENTRIES)
            .forEach { file -> file.delete() }
    }

    companion object {
        private const val TAG = "LyricsCache"
        private const val SCHEMA_VERSION = 2
        private const val MAX_ENTRIES = 300
        private const val TRIM_INTERVAL = 20
        private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        private val SOURCES = setOf("apple", "qq")

        private val ioLock = Any()
        private val memoryCache = LruCache<String, FetchedLyrics>(24)
        private val writesSinceTrim = AtomicInteger(0)
    }
}
