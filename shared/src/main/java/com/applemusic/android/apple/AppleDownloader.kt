package com.applemusic.android.apple

import com.applemusic.android.cenc.CencDecryptor
import com.applemusic.android.drm.KeyFetcher
import com.applemusic.android.drm.MediaDrmKeyFetcher
import com.applemusic.android.drm.WvdKeyFetcher
import com.applemusic.android.hls.HlsByteRange
import com.applemusic.android.hls.HlsMediaInfo
import com.applemusic.android.hls.HlsParser
import com.applemusic.android.mp4.Mp4Cleaner
import com.applemusic.android.mp4.Mp4Remuxer
import com.applemusic.android.net.Http
import com.applemusic.android.util.ByteOps
import com.applemusic.android.util.Logx
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Apple Music 下载器（单曲六步流水线）。
 *
 *   ① getWebPlayback  取资产清单（flavor 挑一条）
 *   ② 下子清单（m3u8）→ HlsParser 解析：keyUri / init 段 / 分片列表
 *   ③ fetchKey        Widevine license 交换（必须用 catalog id）
 *   ④ downloadMedia   按 Range 并发拉取整段音频字节（严格校验 HTTP 206 与分片字节长度）
 *   ⑤ CencDecryptor   逐 sample AES-CTR 解密
 *   ⑥ Mp4Cleaner/Remuxer 去 DRM 标记 + 重封装为标准 MP4 + 原子落盘
 */
object AppleDownloader {

    private const val TAG = "AppleDownloader"

    enum class Stage { RESOLVING, LICENSING, DOWNLOADING, DECRYPTING, WRITING, DONE, FAILED }

    class Result {
        var ok = false
        var filePath = ""
        var message = ""
        var bytes = 0
        var keyHex = ""
    }

    /** 并发分片下载的并发度（1 = 整段单请求）。 */
    @Volatile
    var parallelChunks = 4

    /** license 交换用哪条路：优先内置 WVD（无需 MediaDrm streaming 限制），失败回落系统 MediaDrm。 */
    @Volatile
    var useBuiltinCdmFirst = true

    /** 最近一次实际使用的密钥获取方式（界面显示用）。 */
    @Volatile
    var lastKeySource = ""

    /**
     * 下载并解密一首歌。
     */
    fun downloadSong(
        api: AppleApi,
        songId: String,
        isLibrary: Boolean,
        outPath: String,
        onProgress: ((Stage, Int, String) -> Unit)? = null
    ): Result {
        val res = Result()
        val lastPct = AtomicInteger(-1)
        val report: (Stage, Int, String) -> Unit = { s, p, n ->
            val clamped = p.coerceIn(0, 100)
            if (s == Stage.FAILED || s == Stage.DONE) {
                onProgress?.invoke(s, clamped, n)
            } else {
                // 保证同一首歌进度单调递增，避免 4 线程并发导致进度条倒退闪烁
                var prev = lastPct.get()
                while (clamped > prev && !lastPct.compareAndSet(prev, clamped)) {
                    prev = lastPct.get()
                }
                if (clamped >= prev) {
                    onProgress?.invoke(s, clamped, n)
                }
            }
        }
        try {
            checkNotInterrupted()
            // ① 拿播放资产
            report(Stage.RESOLVING, 2, "解析播放地址")
            val wb = api.getWebPlayback(songId, isLibrary)
            val target = pickAsset(wb.assets)
            if (target == null || target.url.isEmpty()) {
                throw RuntimeException("该歌曲没有可用的音频资源（可能需要订阅）")
            }
            val catalogId = if (wb.songId.isNotEmpty()) wb.songId else songId
            Logx.i(TAG, "asset flavor=${target.flavor} libraryId=$songId catalogId=$catalogId")

            checkNotInterrupted()
            // ② 子清单
            report(Stage.RESOLVING, 6, "解析音频清单")
            val subText = api.downloadText(target.url)
            val media = HlsParser.parseMedia(subText, target.url)
            if (media.keyUri.isEmpty()) throw RuntimeException("清单里没有密钥信息")
            Logx.i(
                TAG,
                "子清单: method=${media.keyMethod} init=${media.initUri} segs=${media.segments.size} " +
                    "kid=${com.applemusic.android.drm.Pssh.kidHex(media.keyUri)}"
            )

            checkNotInterrupted()
            // ③ 取密钥
            report(Stage.LICENSING, 12, "获取解密密钥")
            val licenseServer = runCatching { api.getPlayAssets(catalogId).keyServerUrl }.getOrDefault("")
            val key = fetchKey(api, catalogId, media.keyUri, licenseServer)
            res.keyHex = ByteOps.toHex(key)
            Logx.i(TAG, "content key = ${res.keyHex}")

            checkNotInterrupted()
            // ④ 下载整段音频
            report(Stage.DOWNLOADING, 20, "下载音频数据")
            val mediaUrl = HlsParser.absolute(media.baseUrl, media.mediaUri)
            val totalLen = totalLength(media)
            val data = downloadMedia(api, mediaUrl, media, totalLen) { p ->
                report(Stage.DOWNLOADING, 20 + (p * 45f).toInt(), "下载音频数据")
            }

            checkNotInterrupted()
            // ⑤ 解密
            report(Stage.DECRYPTING, 65, "解密音频数据")
            val probeBefore = probeFirstSample(data)
            val stats = CencDecryptor.decryptWholeFile(key, data)
            val probeAfter = probeFirstSample(data)
            Logx.i(TAG, "解密完成: fragments=${stats.fragments} samples=${stats.samples} bytes=${stats.bytes}")
            Logx.i(TAG, "解密前首个样本: $probeBefore")
            Logx.i(TAG, "解密后首个样本: $probeAfter")
            if (stats.samples == 0) {
                throw RuntimeException("没有解出任何音频数据（格式可能变了）")
            }

            checkNotInterrupted()
            // ⑥ 写文件：清 DRM 残留 → 重封装成普通 MP4 → ftyp 归一化 → 原子落盘
            report(Stage.WRITING, 85, "优化音频格式")
            val cleaned = Mp4Cleaner.stripDrmHeaders(data)
            report(Stage.WRITING, 92, "封装音频流")
            val remuxed = Mp4Remuxer.remux(cleaned)
            val final = remuxed ?: cleaned
            if (remuxed == null) {
                Logx.w(TAG, "重封装未生效，保留清理后的 fMP4")
            }
            report(Stage.WRITING, 96, "写入本地缓存")
            Mp4Cleaner.normalizeFtyp(final)
            writeFileAtomic(outPath, final)
            report(Stage.WRITING, 100, "准备就绪")

            res.ok = true
            res.filePath = outPath
            res.bytes = final.size
            res.message = "frags=${stats.fragments} samples=${stats.samples} decBytes=${stats.bytes} " +
                "key-source=$lastKeySource remux=${remuxed != null}"
            report(Stage.DONE, 100, "完成")
            Logx.i(TAG, "已写出 $outPath (${final.size} bytes, remux=${remuxed != null})")
        } catch (e: Throwable) {
            val msg = e.message ?: e.toString()
            res.ok = false
            res.message = msg
            report(Stage.FAILED, 0, msg)
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
                Logx.i(TAG, "downloadSong cancelled: $songId")
            } else {
                Logx.e(TAG, "downloadSong failed: $msg", e)
            }
            try {
                File("$outPath.part").delete()
            } catch (_: Throwable) {
            }
        }
        return res
    }

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("下载已取消")
        }
    }

    private fun probeFirstSample(data: ByteArray): String {
        return try {
            var off = 0
            var moofStart = -1
            while (off + 8 <= data.size) {
                val size = com.applemusic.android.mp4.Mp4.u32(data, off).toInt()
                if (size < 8) break
                if (com.applemusic.android.mp4.Mp4.fourcc(data, off + 4) == "moof") {
                    moofStart = off
                    break
                }
                off += size
            }
            if (moofStart < 0) return "找不到 moof"
            val info = CencDecryptor.parseFragment(data, moofStart) ?: return "parseFragment 失败"
            val n = minOf(info.sizes.size, info.ivs.size)
            if (n == 0) return "没有样本"
            val sb = StringBuilder()
            sb.append("sizes[0..2]=")
            for (i in 0 until minOf(3, n)) sb.append(info.sizes[i]).append(",")
            sb.append(" iv0=").append(ByteOps.toHex(info.ivs[0], 0, minOf(16, info.ivs[0].size)))
            sb.append(" subs0=")
            val subs = info.subsamples.getOrNull(0)
            if (subs == null || subs.isEmpty()) {
                sb.append("无")
            } else {
                for (s in subs) sb.append("(clear=${s.clear},enc=${s.encrypted})")
            }
            sb.append(" dataOffset=").append(info.dataOffset)
            if (info.dataOffset + 8 <= data.size) {
                sb.append(" head=").append(ByteOps.toHex(data, info.dataOffset, 8))
                val b0 = data[info.dataOffset].toInt() and 0xFF
                sb.append(" 首元素id=").append((b0 shr 5) and 7)
            }
            sb.toString()
        } catch (t: Throwable) {
            "probe 异常: ${t.message}"
        }
    }

    /** 从 assets 里挑 256kbps 的 aac-web（28:ctrp256），退而求其次选任意 256 / 第一条。 */
    fun pickAsset(assets: List<AppleAsset>): AppleAsset? {
        if (assets.isEmpty()) return null
        assets.firstOrNull { it.flavor.contains("ctrp256") }?.let { return it }
        assets.firstOrNull { it.flavor.contains("256") }?.let { return it }
        return assets[0]
    }

    /**
     * 走 Widevine 取 content key：优先内置 WVD，失败再回落系统 MediaDrm。
     * License POST 严格检查 HTTP 2xx，遇到 401 自动刷新开发者令牌并重试一次。
     */
    private fun fetchKey(api: AppleApi, catalogId: String, keyUri: String, licenseServer: String): ByteArray {
        val targetUrl = licenseServer.ifEmpty { com.applemusic.android.drm.DEFAULT_LICENSE_SERVER }
        val post: (String) -> String = { body ->
            api.ensureReady()
            var r = Http.request(
                url = targetUrl,
                method = "POST",
                headers = api.licenseHeaders(),
                body = body.toByteArray(Charsets.UTF_8),
                connectTimeoutMs = 15000,
                readTimeoutMs = 30000
            )
            if (r.code == 401) {
                Logx.w(TAG, "license HTTP 401，刷新 developer token 后重试")
                api.refreshDeveloperToken(force = true)
                r = Http.request(
                    url = targetUrl,
                    method = "POST",
                    headers = api.licenseHeaders(),
                    body = body.toByteArray(Charsets.UTF_8),
                    connectTimeoutMs = 15000,
                    readTimeoutMs = 30000
                )
            }
            Logx.i(TAG, "license http=${r.code} len=${r.body.size}")
            if (r.code !in 200..299) {
                throw RuntimeException("License 服务器拒绝 (HTTP ${r.code}): ${r.text.take(120)}")
            }
            r.text
        }
        val mediaDrm: KeyFetcher = MediaDrmKeyFetcher(post)
        val wvd: KeyFetcher = WvdKeyFetcher(post)
        val order: List<KeyFetcher> =
            if (useBuiltinCdmFirst) listOf(wvd, mediaDrm) else listOf(mediaDrm, wvd)

        var lastError: Throwable? = null
        for (f in order) {
            checkNotInterrupted()
            try {
                val key = f.fetchKey(keyUri, catalogId, licenseServer)
                lastKeySource = f.name
                return key
            } catch (t: Throwable) {
                if (t is InterruptedException) throw t
                lastError = t
                Logx.w(TAG, "密钥获取失败（${f.name}）：${t.message}")
            }
        }
        throw RuntimeException("密钥获取失败：${lastError?.message ?: "未知原因"}", lastError)
    }

    /** 计算整段媒体的总长度（init + 全部分片）。 */
    fun totalLength(media: HlsMediaInfo): Int {
        var max = media.initRange.offset + media.initRange.length
        for (s in media.segments) {
            val end = s.offset + s.length
            if (end > max) max = end
        }
        return max
    }

    private fun rangesCover(init: HlsByteRange, segs: List<HlsByteRange>, totalLen: Int): Boolean {
        if (totalLen <= 0) return false
        var expect = init.offset + init.length
        for (seg in segs) {
            if (seg.length <= 0) return false
            if (seg.offset != expect) return false
            expect = seg.offset + seg.length
        }
        return expect == totalLen
    }

    /**
     * 下载媒体：按 HLS 分片并发 Range 拉取并严格校验字节完整性；
     * 若 CDN 对 Range 返回完整 200 文件则直接复用，绝不产出零填充损坏音频。
     */
    private fun downloadMedia(
        api: AppleApi,
        url: String,
        media: HlsMediaInfo,
        totalLen: Int,
        onProgress: (Float) -> Unit
    ): ByteArray {
        val segs = media.segments
        val distinctUris = segs.any { it.uri.isNotEmpty() && it.uri != media.mediaUri }
        if (parallelChunks <= 1 || segs.size <= 1 || distinctUris || !rangesCover(media.initRange, segs, totalLen)) {
            Logx.i(TAG, "downloadMedia: 回退整段单请求")
            onProgress(0.05f)
            val whole = api.downloadRange(url, -1, -1)
            if (whole.isEmpty()) throw IOException("整段音频下载为空")
            onProgress(1f)
            return whole
        }

        val buf = ByteArray(totalLen)
        val counter = AtomicInteger(0)
        val done = AtomicInteger(0)
        val failed = AtomicBoolean(false)

        // 先拉取 init segment
        val initLen = media.initRange.length
        if (initLen > 0) {
            checkNotInterrupted()
            val initResp = Http.getRange(
                url = url,
                headers = api.mediaHeaders(),
                rangeStart = media.initRange.offset,
                rangeEnd = media.initRange.offset + initLen - 1,
                readTimeoutMs = 60000
            )
            if (initResp.code == 200 && initResp.body.size == totalLen) {
                // CDN 直接返回整首歌，无需再并发拉取分片
                onProgress(1f)
                return initResp.body
            }
            if (initResp.code != 206 || initResp.body.size != initLen) {
                throw IOException("init 分片不完整: http=${initResp.code}, expected=$initLen, actual=${initResp.body.size}")
            }
            System.arraycopy(initResp.body, 0, buf, media.initRange.offset, initLen)
            done.addAndGet(initLen)
            onProgress(minOf(1f, done.get().toFloat() / totalLen))
        }

        val workers = minOf(parallelChunks, segs.size)
        val threads = ArrayList<Thread>(workers)
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val fullFileResult = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)

        for (w in 0 until workers) {
            val t = Thread({
                while (!failed.get() && !Thread.currentThread().isInterrupted && fullFileResult.get() == null) {
                    val i = counter.getAndIncrement()
                    if (i >= segs.size) return@Thread
                    val seg = segs[i]
                    try {
                        val resp = Http.getRange(
                            url = url,
                            headers = api.mediaHeaders(),
                            rangeStart = seg.offset,
                            rangeEnd = seg.offset + seg.length - 1,
                            readTimeoutMs = 60000
                        )
                        if (resp.code == 200 && resp.body.size == totalLen) {
                            fullFileResult.compareAndSet(null, resp.body)
                            return@Thread
                        }
                        if (resp.code != 206 || resp.body.size != seg.length) {
                            throw IOException(
                                "分片 #$i 不完整: http=${resp.code}, expected=${seg.length}, actual=${resp.body.size}"
                            )
                        }
                        System.arraycopy(resp.body, 0, buf, seg.offset, seg.length)
                        val d = done.addAndGet(seg.length)
                        onProgress(minOf(1f, d.toFloat() / totalLen))
                    } catch (t2: Throwable) {
                        if (failed.compareAndSet(false, true)) {
                            errors.add(t2)
                            threads.forEach { other -> if (other !== Thread.currentThread()) other.interrupt() }
                        }
                        return@Thread
                    }
                }
            }, "am-dl-$w")
            t.isDaemon = true
            threads.add(t)
        }
        threads.forEach { it.start() }

        val deadline = System.currentTimeMillis() + 180_000L
        try {
            for (t in threads) {
                val remain = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                t.join(remain)
            }
        } catch (ie: InterruptedException) {
            failed.set(true)
            threads.forEach { it.interrupt() }
            Thread.currentThread().interrupt()
            throw ie
        }

        val stillAlive = threads.any { it.isAlive }
        if (stillAlive) {
            failed.set(true)
            threads.forEach { it.interrupt() }
            throw IOException("分片下载超时（超过 180 秒）")
        }

        fullFileResult.get()?.let { whole ->
            onProgress(1f)
            return whole
        }

        if (errors.isNotEmpty()) {
            throw RuntimeException("分片下载失败：${errors[0].message}", errors[0])
        }
        if (done.get() != totalLen) {
            throw IOException("音频下载不完整：expected=$totalLen, actual=${done.get()}")
        }
        return buf
    }

    /**
     * 原子写入：先写 `<path>.part` 并 fsync，再直接 renameTo 原子替换目标路径（绝不先 delete 目标）。
     */
    private fun writeFileAtomic(path: String, data: ByteArray) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File("$path.part")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            raf.write(data)
            try {
                raf.fd.sync()
            } catch (_: Throwable) {
            }
        }
        if (!tmp.renameTo(target)) {
            // 跨卷/极端情况退回复制，但失败时绝不留下半截目标文件
            try {
                tmp.copyTo(target, overwrite = true)
            } catch (t: Throwable) {
                try {
                    target.delete()
                } catch (_: Throwable) {
                }
                throw t
            } finally {
                tmp.delete()
            }
        }
    }
}
