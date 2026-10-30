package com.applemusic.android.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 极简网络封面加载器。
 *
 * 对应鸿蒙版里「coverPath 直接放 Apple 的封面 URL，由系统的网络图片缓存兜住」——
 * 安卓这边没有现成的等价物，所以自己写一个：有内存上限的 LRU + 磁盘缓存
 * （`cacheDir/artwork/`，最多 400 张，单张 ≤3MB，超出按修改时间淘汰）。
 */
object ImageLoader {

    private const val TAG = "ImageLoader"
    private const val MAX_DISK_FILES = 400
    private const val MAX_BYTES_PER_FILE = 3 * 1024 * 1024
    private val maxMemoryCacheKb =
        (Runtime.getRuntime().maxMemory() / 8 / 1024).coerceIn(2 * 1024, 8 * 1024).toInt()

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "img").apply { isDaemon = true } }

    private val memCache = object : LruCache<String, Bitmap>(maxMemoryCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    private val inFlightLock = Any()
    private val inFlight = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    @Volatile
    private var diskDir: File? = null

    fun init(cacheDir: File) {
        val d = File(cacheDir, "artwork")
        if (!d.exists()) d.mkdirs()
        diskDir = d
    }

    fun load(view: ImageView, url: String, circle: Boolean = false) {
        view.tag = url
        if (url.isEmpty()) {
            view.setImageDrawable(null)
            return
        }
        memCache.get(url)?.let {
            apply(view, it, circle)
            return
        }
        loadAsync(url) { bitmap ->
            if (view.tag == url && bitmap != null) {
                apply(view, bitmap, circle)
            }
        }
    }

    fun loadBitmap(url: String, onLoaded: (Bitmap?) -> Unit) {
        if (url.isEmpty()) {
            main.post { onLoaded(null) }
            return
        }
        memCache.get(url)?.let { bitmap ->
            main.post { onLoaded(bitmap) }
            return
        }
        loadAsync(url, onLoaded)
    }

    private fun loadAsync(url: String, onLoaded: (Bitmap?) -> Unit) {
        var cachedBitmap: Bitmap? = null
        val shouldStart = synchronized(inFlightLock) {
            cachedBitmap = memCache.get(url)
            if (cachedBitmap != null) {
                false
            } else {
                val callbacks = inFlight[url]
                if (callbacks != null) {
                    callbacks.add(onLoaded)
                    false
                } else {
                    inFlight[url] = mutableListOf(onLoaded)
                    true
                }
            }
        }
        cachedBitmap?.let { bitmap ->
            main.post { onLoaded(bitmap) }
            return
        }
        if (!shouldStart) return

        pool.execute {
            val bitmap = try {
                loadBitmap(url)
            } catch (e: Exception) {
                Logx.w(TAG, "load failed $url: ${e.message}")
                null
            }
            if (bitmap != null) memCache.put(url, bitmap)
            val callbacks = synchronized(inFlightLock) {
                inFlight.remove(url).orEmpty()
            }
            main.post {
                for (callback in callbacks) {
                    callback(bitmap)
                }
            }
        }
    }

    private fun apply(view: ImageView, bmp: Bitmap, circle: Boolean) {
        if (circle) {
            val d = RoundedBitmapDrawableFactory.create(view.resources, bmp)
            d.isCircular = true
            view.setImageDrawable(d)
        } else {
            view.setImageBitmap(bmp)
        }
    }

    private fun loadBitmap(url: String): Bitmap? {
        val dir = diskDir
        val file = if (dir != null) File(dir, hash(url)) else null
        if (file != null && file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
            } catch (_: Throwable) {
            }
            file.delete()
        }
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_BYTES_PER_FILE) return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null && file != null) {
                try {
                    file.writeBytes(bytes)
                    trimDisk(dir!!)
                } catch (_: Throwable) {
                }
            }
            return bmp
        } catch (e: Throwable) {
            Logx.w(TAG, "load failed $url: ${e.message}")
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun trimDisk(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_DISK_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_FILES)
            .forEach { it.delete() }
    }

    private fun hash(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val d = md.digest(s.toByteArray())
        val hex = "0123456789abcdef"
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val value = b.toInt() and 0xff
            sb.append(hex[value ushr 4]).append(hex[value and 0x0f])
        }
        return sb.toString()
    }
}
