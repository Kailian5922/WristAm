package com.applemusic.android.cenc

import com.applemusic.android.mp4.Box
import com.applemusic.android.mp4.Mp4
import com.applemusic.android.util.AesCtr
import com.applemusic.android.util.ByteOps
import com.applemusic.android.util.Logx
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * CENC（ISO/IEC 23001-7）解密器 —— Apple Music 音频。
 *
 * 核心解密算法与思路参考：
 *   酷安动态：https://www.coolapk.com/feed/73737066?s=NjdhODM2MDAzN2JlMDhnNmFlNDg3OGN6a1670
 *
 * 对应鸿蒙版 `CencDecryptor.ets`。实测格式：
 *   schm = 'cenc'（AES-CTR）
 *   tenc.IVSize = 8
 *   senc 里每个 sample 存 16 字节 IV，实际使用前 8 字节（补 8 个 0 作为 128 位计数器）
 *   无 subsample 时：mdat 中每个 sample 的全部字节都是密文
 *
 * 【移植时特别留意原项目踩过的坑】
 *  1) senc 记录长度**不能写死 16 字节**。有 subsample 时每 sample 是
 *     `IV + subsample_count(u16) + n×6`；没有 subsample 时只有 `IV`。
 *     写死步长会让一半样本读到错位/全零的 IV，那些样本根本没被解密，
 *     结果就是「有的歌能播、有的歌 AAC 解码全错」。
 *  2) 只解 encrypted 子样本，clear 子样本必须保持原样（早期整段 CTR 会把
 *     clear 段也加密 → 音频花掉）。
 *  3) 按 box 结构遍历 moof，不要用字节搜索 "moof"（mdat 里可能出现假标记）。
 */
class SubSample {
    var clear = 0
    var encrypted = 0
}

class FragmentInfo {
    /** 每个 sample 的密文长度（trun 里的 sample_size 之和口径） */
    val sizes = ArrayList<Int>()

    /** 每个 sample 的 IV（原样，最多 16 字节） */
    val ivs = ArrayList<ByteArray>()

    /** 每个 sample 的子样本表（可能为空 = 整 sample 密文） */
    val subsamples = ArrayList<List<SubSample>>()

    /** sample 数据在整段 buffer 里的起始偏移 */
    var dataOffset = 0

    /** moof box 的起始偏移 */
    var moofStart = 0
}

class DecryptStats {
    var fragments = 0
    var samples = 0
    var bytes = 0L
}

object CencDecryptor {

    private const val TAG = "CencDecryptor"

    /** 解密并发度：每个 worker 自己持有一个 Cipher，靠 init 换 IV。 */
    @Volatile
    private var workers = 4

    private val decryptExecutor = Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "cenc-decrypt").apply { isDaemon = true }
    }

    fun setWorkers(n: Int) {
        workers = n.coerceIn(1, 8)
    }

    /**
     * 解析紧跟在 moofStart 处的一个 fragment，返回解密信息。
     * @return null 表示该处没有可解析的 moof。
     */
    fun parseFragment(buf: ByteArray, moofStart: Int): FragmentInfo? {
        val moof = Mp4.find(buf, "moof", moofStart, buf.size) ?: return null
        if (moof.start != moofStart) return null
        val traf = Mp4.find(buf, "traf", moof.bodyStart, moof.bodyEnd) ?: return null

        // ── tfhd / trex 缺省样本大小 ──
        var defaultSampleSize = findTrexDefaultSampleSize(buf)
        Mp4.find(buf, "tfhd", traf.bodyStart, traf.bodyEnd)?.let { tfhd ->
            if (tfhd.bodyStart + 8 <= tfhd.bodyEnd) {
                val tfFlags = (Mp4.u32(buf, tfhd.bodyStart) and 0xFFFFFFL).toInt()
                var fp = tfhd.bodyStart + 8
                if (tfFlags and 0x000001 != 0) fp += 8
                if (tfFlags and 0x000002 != 0) fp += 4
                if (tfFlags and 0x000008 != 0) fp += 4
                if (tfFlags and 0x000010 != 0 && fp + 4 <= tfhd.bodyEnd) {
                    defaultSampleSize = Mp4.u32(buf, fp).toInt()
                }
            }
        }

        // ── trun：每个 sample 的大小 ──
        val trun = Mp4.find(buf, "trun", traf.bodyStart, traf.bodyEnd) ?: return null
        val info = FragmentInfo()
        info.moofStart = moof.start

        val tOff = trun.bodyStart
        if (tOff + 8 > trun.bodyEnd) return null
        val tFlags = (Mp4.u32(buf, tOff) and 0xFFFFFFL).toInt()
        var p = tOff + 4
        val sampleCountLong = Mp4.u32(buf, p)
        if (sampleCountLong > 2_000_000L) return null
        val sampleCount = sampleCountLong.toInt()
        p += 4

        var dataOffsetRel = -1
        if (tFlags and 0x000001 != 0) {
            if (p + 4 > trun.bodyEnd) return null
            dataOffsetRel = Mp4.i32(buf, p)
            p += 4
        }
        if (tFlags and 0x000004 != 0) {
            if (p + 4 > trun.bodyEnd) return null
            p += 4 // first_sample_flags
        }

        val perEntryBytes = (if (tFlags and 0x000100 != 0) 4 else 0) +
            (if (tFlags and 0x000200 != 0) 4 else 0) +
            (if (tFlags and 0x000400 != 0) 4 else 0) +
            (if (tFlags and 0x000800 != 0) 4 else 0)
        if (perEntryBytes > 0 && sampleCount.toLong() * perEntryBytes > (trun.bodyEnd - p).toLong()) {
            return null
        }

        val sizes = ArrayList<Int>(sampleCount.coerceAtMost(16_384))
        for (i in 0 until sampleCount) {
            var size = defaultSampleSize
            if (tFlags and 0x000100 != 0) p += 4 // sample_duration
            if (tFlags and 0x000200 != 0) {
                if (p + 4 > trun.bodyEnd) break
                size = Mp4.u32(buf, p).toInt()
                p += 4
            }
            if (tFlags and 0x000400 != 0) p += 4 // sample_flags
            if (tFlags and 0x000800 != 0) p += 4 // composition time offset
            sizes.add(size)
        }
        info.sizes.addAll(sizes)

        // ── senc：每个 sample 的 IV（+ 可选子样本表）──
        val senc = Mp4.find(buf, "senc", traf.bodyStart, traf.bodyEnd) ?: return null
        val sOff = senc.bodyStart
        if (sOff + 8 > senc.bodyEnd) return null
        val sFlags = (Mp4.u32(buf, sOff) and 0xFFFFFFL).toInt()
        var sp = sOff + 4
        val sencCountLong = Mp4.u32(buf, sp)
        if (sencCountLong > 2_000_000L) return null
        val sencCount = sencCountLong.toInt()
        sp += 4

        val hasSubsample = (sFlags and 0x000002) != 0
        val ivSize: Int = if (hasSubsample) {
            tencIvSize(buf)
        } else {
            val per = if (sencCount > 0) (senc.bodyEnd - sp) / sencCount else 0
            if (per == 8 || per == 16) per else tencIvSize(buf)
        }

        for (i in 0 until sencCount) {
            if (sp + ivSize > senc.bodyEnd) break
            val iv = ByteArray(16)
            System.arraycopy(buf, sp, iv, 0, ivSize)
            sp += ivSize
            val subs = ArrayList<SubSample>()
            if (hasSubsample && sp + 2 <= senc.bodyEnd) {
                val n = Mp4.u16(buf, sp)
                sp += 2
                for (k in 0 until n) {
                    if (sp + 6 > senc.bodyEnd) break
                    val ss = SubSample()
                    ss.clear = Mp4.u16(buf, sp)
                    val encLong = Mp4.u32(buf, sp + 2)
                    ss.encrypted = if (encLong > Int.MAX_VALUE) 0 else encLong.toInt()
                    sp += 6
                    subs.add(ss)
                }
            }
            info.ivs.add(iv)
            info.subsamples.add(subs)
        }

        // ── sample 数据起始位置 ──
        info.dataOffset = if (dataOffsetRel >= 0) {
            moof.start + dataOffsetRel
        } else {
            val mdat = Mp4.find(buf, "mdat", moof.bodyEnd, buf.size)
            mdat?.bodyStart ?: moof.bodyEnd
        }
        return info
    }

    /**
     * 就地解密一个 fragment 的所有 sample。
     * @param key 16 字节内容密钥
     * @param buf 整段 buffer（含 moof 与 mdat），**原地改写**
     * @return 解密的字节数
     */
    fun decryptFragment(key: ByteArray, buf: ByteArray, info: FragmentInfo): Long {
        var cursor = info.dataOffset
        val n = minOf(info.sizes.size, info.ivs.size)
        var total = 0L

        // 收集每个 sample 的实际解密区间（考虑子样本），便于分片并行
        class Job(val offset: Int, val encLen: Int, val iv: ByteArray)

        val jobs = ArrayList<Job>(n)
        for (i in 0 until n) {
            val size = info.sizes[i]
            if (size <= 0) continue
            if (cursor < 0 || size > buf.size - cursor) break
            val iv = expandIv(info.ivs[i])
            val subs = info.subsamples.getOrNull(i) ?: emptyList()
            if (subs.isEmpty()) {
                jobs.add(Job(cursor, size, iv))
            } else {
                var inner = cursor
                var consumed = 0
                val sampleEnd = cursor + size
                for (ss in subs) {
                    val clear = ss.clear.coerceAtLeast(0)
                    val enc = ss.encrypted.coerceAtLeast(0)
                    if (clear > sampleEnd - inner) break
                    val encStart = inner + clear
                    val clampedEnc = enc.coerceAtMost(sampleEnd - encStart)
                    if (clampedEnc > 0) {
                        jobs.add(Job(encStart, clampedEnc, iv))
                    }
                    inner = encStart + clampedEnc
                    consumed = inner - cursor
                }
                if (consumed < size && inner < sampleEnd) {
                    jobs.add(Job(inner, sampleEnd - inner, iv))
                }
            }
            cursor += size
        }

        if (jobs.isEmpty()) return 0

        val activeWorkers = minOf(workers, jobs.size)
        if (activeWorkers == 1) {
            val decryptor = AesCtr.Decryptor(key)
            for (job in jobs) {
                val plain = decryptor.decrypt(job.iv, buf, job.offset, job.encLen)
                System.arraycopy(plain, 0, buf, job.offset, plain.size)
                total += job.encLen.toLong()
            }
            return total
        }
        val chunk = (jobs.size + activeWorkers - 1) / activeWorkers
        val futures = ArrayList<Future<*>>(activeWorkers)
        for (w in 0 until activeWorkers) {
            val from = w * chunk
            if (from >= jobs.size) break
            val to = minOf(from + chunk, jobs.size)
            futures.add(decryptExecutor.submit {
                val decryptor = AesCtr.Decryptor(key)
                for (j in from until to) {
                    val job = jobs[j]
                    val plain = decryptor.decrypt(job.iv, buf, job.offset, job.encLen)
                    System.arraycopy(plain, 0, buf, job.offset, plain.size)
                }
            })
        }
        try {
            for (future in futures) future.get(60, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            futures.forEach { it.cancel(true) }
            Thread.currentThread().interrupt()
            throw RuntimeException("CENC 解密被中断", e)
        } catch (e: Exception) {
            futures.forEach { it.cancel(true) }
            val cause = e.cause ?: e
            throw RuntimeException("CENC 解密失败: ${cause.message}", cause)
        }
        for (j in jobs) total += j.encLen.toLong()
        return total
    }

    /**
     * 整个文件解密：按 MP4 box 结构顺序遍历所有 moof，逐个解密对应 mdat。
     */
    fun decryptWholeFile(key: ByteArray, buf: ByteArray): DecryptStats {
        val stats = DecryptStats()
        val top: List<Box> = Mp4.boxes(buf, 0, buf.size)
        for (b in top) {
            if (b.type != "moof") continue
            val info = parseFragment(buf, b.start)
            if (info == null) {
                Logx.w(TAG, "moof@${b.start} 解析失败（trun/senc 缺失）")
                continue
            }
            val bytes = decryptFragment(key, buf, info)
            stats.fragments++
            stats.samples += info.sizes.size
            stats.bytes += bytes
        }
        return stats
    }

    /**
     * 读 moov/mvex/trex 的 default_sample_size（供缺少 0x200 的 trun 兜底）。
     */
    private fun findTrexDefaultSampleSize(buf: ByteArray): Int {
        val moov = Mp4.findTop(buf, "moov") ?: return 0
        val mvex = Mp4.find(buf, "mvex", moov.bodyStart, moov.bodyEnd) ?: return 0
        val trex = Mp4.find(buf, "trex", mvex.bodyStart, mvex.bodyEnd) ?: return 0
        if (trex.bodyStart + 20 > trex.bodyEnd) return 0
        return Mp4.u32(buf, trex.bodyStart + 16).toInt()
    }

    /**
     * 读 moov 内 tenc FullBox 的 default_Per_Sample_IV_Size（body 偏移 7）。
     *
     * tenc FullBox body: version(1) flags(3) reserved(1) crypt/skip(1) isProtected(1) ivSize(1) KID(16)
     * 仅在 moov 内搜索，避免扫到 mdat 里的巧合字节；取不到时回退 8（Apple HLS 常见值）。
     */
    private fun tencIvSize(buf: ByteArray): Int {
        val moov = Mp4.findTop(buf, "moov") ?: return 8
        val end = moov.bodyEnd
        var i = moov.bodyStart
        while (i + 12 <= end) {
            if (buf[i] == 't'.code.toByte() && buf[i + 1] == 'e'.code.toByte() &&
                buf[i + 2] == 'n'.code.toByte() && buf[i + 3] == 'c'.code.toByte()
            ) {
                val body = i + 4
                if (body + 8 <= end) {
                    val size = buf[body + 7].toInt() and 0xFF
                    if (size == 8 || size == 16) return size
                }
            }
            i++
        }
        return 8
    }

    /** senc 里的 IV → 128 位 CTR 计数器（前 8 字节 + 8 个 0）。 */
    fun expandIv(iv: ByteArray): ByteArray {
        val out = ByteArray(16)
        System.arraycopy(iv, 0, out, 0, minOf(8, iv.size))
        return out
    }

    fun hexN(d: ByteArray, from: Int = 0, len: Int = d.size - from): String =
        ByteOps.toHex(d, from, len)
}
