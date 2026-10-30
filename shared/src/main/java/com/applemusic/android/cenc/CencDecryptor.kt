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

        // ── trun：每个 sample 的大小 ──
        val trun = Mp4.find(buf, "trun", traf.bodyStart, traf.bodyEnd) ?: return null
        val info = FragmentInfo()
        info.moofStart = moof.start

        val tOff = trun.bodyStart
        val tFlags = (Mp4.u32(buf, tOff) and 0xFFFFFFL).toInt()
        var p = tOff + 4
        val sampleCount = Mp4.u32(buf, p).toInt()
        p += 4

        var dataOffsetRel = -1
        if (tFlags and 0x000001 != 0) {
            dataOffsetRel = Mp4.i32(buf, p)
            p += 4
        }
        if (tFlags and 0x000004 != 0) {
            p += 4 // first_sample_flags
        }

        val sizes = ArrayList<Int>(sampleCount)
        for (i in 0 until sampleCount) {
            var size = 0
            if (tFlags and 0x000100 != 0) p += 4 // sample_duration
            if (tFlags and 0x000200 != 0) {
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
        val sFlags = (Mp4.u32(buf, sOff) and 0xFFFFFFL).toInt()
        var sp = sOff + 4
        val sencCount = Mp4.u32(buf, sp).toInt()
        sp += 4

        val hasSubsample = (sFlags and 0x000002) != 0
        val ivSize: Int = if (hasSubsample) {
            // 有 subsample 时必须靠 tenc 的 default_Per_Sample_IV_Size 才知道步长
            tencIvSize(buf)
        } else {
            // 无 subsample：整块就是 count × ivSize，直接按剩余长度反推最稳
            val per = if (sencCount > 0) (senc.bodyEnd - sp) / sencCount else 0
            if (per == 8 || per == 16) per else tencIvSize(buf)
        }

        for (i in 0 until sencCount) {
            val iv = ByteArray(16)
            if (sp + ivSize <= senc.bodyEnd) {
                System.arraycopy(buf, sp, iv, 0, ivSize)
            }
            sp += ivSize
            val subs = ArrayList<SubSample>()
            if (hasSubsample && sp + 2 <= senc.bodyEnd) {
                val n = Mp4.u16(buf, sp)
                sp += 2
                for (k in 0 until n) {
                    if (sp + 6 > senc.bodyEnd) break
                    val ss = SubSample()
                    ss.clear = Mp4.u16(buf, sp)
                    ss.encrypted = Mp4.u32(buf, sp + 2).toInt()
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
            if (cursor + size > buf.size) break
            val iv = expandIv(info.ivs[i])
            val subs = info.subsamples.getOrNull(i) ?: emptyList()
            if (subs.isEmpty()) {
                jobs.add(Job(cursor, size, iv))
            } else {
                var inner = cursor
                // 子样本口径的总长（clear + encrypted 累加），通常等于 sample size
                var consumed = 0
                for (ss in subs) {
                    if (ss.encrypted > 0) {
                        jobs.add(Job(inner + ss.clear, ss.encrypted, iv))
                    }
                    inner += ss.clear + ss.encrypted
                    consumed += ss.clear + ss.encrypted
                }
                // 子样本表没铺满 sample 时，剩余部分按密文处理（防御性）
                if (consumed < size) {
                    jobs.add(Job(inner, size - consumed, iv))
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
     * 读 init segment 里 tenc 盒子的 default_Per_Sample_IV_Size（偏移 3）。
     *
     * tenc body: reserved(1) crypt/skip(1) isProtected(1) ivSize(1) KID(16)
     * 取不到时返回 8（Apple HLS 的实测值）。
     */
    private fun tencIvSize(buf: ByteArray): Int {
        val idx = Mp4.indexOf(buf, "tenc", 0)
        if (idx < 4) return 8
        val body = idx + 4
        if (body + 4 > buf.size) return 8
        val size = buf[body + 3].toInt() and 0xFF
        return if (size == 8 || size == 16) size else 8
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
