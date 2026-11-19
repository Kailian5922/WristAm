package com.applemusic.android.widevine

import android.util.Base64
import com.applemusic.android.util.ByteOps
import com.applemusic.android.util.Logx
import com.applemusic.android.util.PbReader
import com.applemusic.android.util.PbWriter

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 一条内容密钥。
 */
class ContentKey {
    var id: ByteArray = ByteArray(0)
    var key: ByteArray = ByteArray(0)
    var type = 0

    companion object {
        const val TYPE_CONTENT = 2
    }
}

/**
 * 内置 Widevine L3 CDM（纯 Kotlin 实现）。
 *
 * 对应鸿蒙版 `widevine/WidevineCdm.ets`，流程对齐 pywidevine：
 *   1. buildChallenge: 构造 LicenseRequest → RSA-PSS(SHA1) 签名 → SignedMessage
 *   2. parseLicense  : RSA-OAEP 解 session_key → AES-CMAC 派生 enc/mac key
 *                      → HMAC-SHA256 校验签名 → AES-CBC 解出 content key
 *
 * 这是**备用路径**：优先用系统 MediaDrm（见 MediaDrmKeyFetcher），
 * 它在多数机器上更省事；MediaDrm 失败时才用这条完全自包含的路径。
 */
class WidevineCdm(private val device: WvdDevice) {

    private val priKey by lazy { device.privateKey() }

    private class Ctx {
        lateinit var requestId: ByteArray
        lateinit var encContext: ByteArray
        lateinit var macContext: ByteArray
        lateinit var requestBody: ByteArray
    }

    /** challenge 会话上下文（internal 是为了让单元测试能扮演 key server 做完整往返）。 */
    internal class Session(
        val requestId: ByteArray,
        val encContext: ByteArray,
        val macContext: ByteArray,
        val requestBody: ByteArray
    )

    /** 测试辅助：从 challenge 字节里取回该次会话的上下文。 */
    internal fun sessionOf(challenge: ByteArray): Session? = companionSession(challenge)

    private val pending = ConcurrentHashMap<String, Ctx>()

    private val sessionCounter = AtomicLong(0L)

    /** 最近一次 challenge 的调试信息。 */
    @Volatile
    var lastDebug = ""

    companion object {
        private const val TAG = "WidevineCdm"
        private const val MAX_PENDING_SESSIONS = 64

        /** requestId(hex) → 上下文（并发安全）；`parseLicense` 靠它取回派生密钥所需的上下文。 */
        private val pendingSessions = ConcurrentHashMap<String, Session>()

        private fun extractRequestIdHex(challenge: ByteArray): String? {
            return try {
                var msg = ByteArray(0)
                val r = PbReader(challenge)
                while (!r.eof()) {
                    val f = r.next() ?: break
                    if (f.field == 2) msg = f.bytes
                }
                var contentId = ByteArray(0)
                val lr = PbReader(msg)
                while (!lr.eof()) {
                    val f = lr.next() ?: break
                    if (f.field == 2) contentId = f.bytes
                }
                var psshMsg = ByteArray(0)
                val cr = PbReader(contentId)
                while (!cr.eof()) {
                    val f = cr.next() ?: break
                    if (f.field == 1) psshMsg = f.bytes
                }
                var requestId = ByteArray(0)
                val pr = PbReader(psshMsg)
                while (!pr.eof()) {
                    val f = pr.next() ?: break
                    if (f.field == 3) requestId = f.bytes
                }
                if (requestId.isEmpty()) null else ByteOps.toHex(requestId)
            } catch (_: Throwable) {
                null
            }
        }

        internal fun companionSession(challenge: ByteArray): Session? {
            val key = extractRequestIdHex(challenge) ?: return null
            return pendingSessions[key]
        }

        private fun encContext(msg: ByteArray): ByteArray =
            ByteOps.concat("ENCRYPTION".toByteArray(Charsets.UTF_8), byteArrayOf(0), msg, u32be(128))

        private fun macContext(msg: ByteArray): ByteArray =
            ByteOps.concat("AUTHENTICATION".toByteArray(Charsets.UTF_8), byteArrayOf(0), msg, u32be(512))

        private fun u32be(v: Int): ByteArray = byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
        )

        /**
         * Widevine 的密钥派生（内联 pywidevine 的 `_derive`）：
         * `CMAC(key, counter_byte || context)`。
         */
        internal fun derive(key: ByteArray, context: ByteArray, counter: Int): ByteArray =
            Crypto.aesCmac(key, ByteOps.concat(byteArrayOf(counter.toByte()), context))
    }

    /**
     * 生成 license challenge。
     *
     * @param psshData Widevine PSSH **payload**（不是整个 pssh box）
     * @param licenseType 1=STREAMING 2=OFFLINE 3=AUTOMATIC
     */
    fun buildChallenge(psshData: ByteArray, licenseType: Int = 1): ByteArray {
        val ctx = Ctx()

        // Android 设备（WVD type=2）的 request_id 规则：random(4) + zeros(4) + session_number(8, LE)，
        // 然后整体转成 32 字节的大写 HEX 字符串（pywidevine 就是这么做的），否则服务端会拒。
        val ridRaw = ByteArray(16)
        val rnd = ByteArray(4)
        java.security.SecureRandom().nextBytes(rnd)
        System.arraycopy(rnd, 0, ridRaw, 0, 4)
        var num = sessionCounter.incrementAndGet()
        for (bi in 0 until 8) {
            ridRaw[8 + bi] = (num % 256).toByte()
            num /= 256
        }
        ctx.requestId = ByteOps.toHex(ridRaw).uppercase().toByteArray(Charsets.US_ASCII)
        val nonce = (Math.random() * 0x7FFFFFFF).toLong() + 1

        // WidevinePsshData { pssh_data=1, license_type=2, request_id=3 }
        val psshMsg = PbWriter()
            .bytes(1, psshData)
            .uint(2, licenseType)
            .bytes(3, ctx.requestId)
            .toByteArray()

        // ContentIdentification { widevine_pssh_data=1 }
        val contentId = PbWriter().message(1, psshMsg).toByteArray()

        // LicenseRequest
        val request = PbWriter()
            .bytes(1, device.clientId)
            .message(2, contentId)
            .uint(3, 1)
            .int64(4, System.currentTimeMillis() / 1000)
            .uint(6, 21)
            .uint(7, nonce.toInt())
            .toByteArray()

        ctx.requestBody = request
        ctx.encContext = encContext(request)
        ctx.macContext = macContext(request)

        val signature = Crypto.rsaPssSha1Sign(priKey, request)

        // SignedMessage { type=1, msg=2, signature=3 }
        val signed = PbWriter()
            .uint(1, 1)
            .bytes(2, request)
            .bytes(3, signature)
            .toByteArray()

        if (pending.size > MAX_PENDING_SESSIONS) {
            pending.clear()
        }
        if (pendingSessions.size > MAX_PENDING_SESSIONS) {
            pendingSessions.clear()
        }
        val hexKey = ByteOps.toHex(ctx.requestId)
        pending[hexKey] = ctx
        pendingSessions[hexKey] = Session(
            ctx.requestId, ctx.encContext, ctx.macContext, ctx.requestBody
        )
        lastDebug = "rsaBits=${device.rsaBits} requestIdLen=${ctx.requestId.size} reqLen=${request.size} " +
                "sigLen=${signature.size} challengeLen=${signed.size}"
        Logx.i(TAG, "challenge: $lastDebug")
        return signed
    }

    /** 释放未完成/已失败的 challenge 上下文，防止内存泄漏。 */
    fun discardChallenge(challenge: ByteArray) {
        val hexKey = extractRequestIdHex(challenge) ?: return
        pending.remove(hexKey)
        pendingSessions.remove(hexKey)
    }

    /**
     * 解析 license 响应，返回所有密钥。
     */
    fun parseLicense(licenseMessage: ByteArray): List<ContentKey> {
        // ---- 解析外层 SignedMessage ----
        var type = 0
        var msg = ByteArray(0)
        var signature = ByteArray(0)
        var sessionKey = ByteArray(0)
        var oemCrypto = ByteArray(0)

        val r = PbReader(licenseMessage)
        while (!r.eof()) {
            val f = r.next() ?: break
            when (f.field) {
                1 -> type = f.varint.toInt()
                2 -> msg = f.bytes
                3 -> signature = f.bytes
                4 -> sessionKey = f.bytes
                9 -> oemCrypto = f.bytes
            }
        }
        if (type != 2) throw IllegalStateException("不是 LICENSE 消息，type=$type")
        if (msg.isEmpty()) throw IllegalStateException("license 消息为空")

        // ---- 解析 License 头，取出 request_id ----
        var requestId = ByteArray(0)
        val kr = PbReader(msg)
        while (!kr.eof()) {
            val f = kr.next() ?: break
            if (f.field == 1) {
                // LicenseIdentification { request_id = 1 }
                val ir = PbReader(f.bytes)
                while (!ir.eof()) {
                    val g = ir.next() ?: break
                    if (g.field == 1) requestId = g.bytes
                }
            }
        }

        val ctxKey = ByteOps.toHex(requestId)
        val ctx = pending.remove(ctxKey)
            ?: throw IllegalStateException("找不到该 license 对应的 challenge 上下文（requestId=${ctxKey.take(16)}…）")
        pendingSessions.remove(ctxKey)

        // ---- 解密 session_key，派生密钥 ----
        val sessionKeyPlain = Crypto.rsaOaepSha1Decrypt(priKey, sessionKey)
        val encKey = derive(sessionKeyPlain, ctx.encContext, 1)
        val mac1 = derive(sessionKeyPlain, ctx.macContext, 1)
        val mac2 = derive(sessionKeyPlain, ctx.macContext, 2)
        val macKeyServer = ByteOps.concat(mac1, mac2)
        // ---- 校验 license 签名（HMAC-SHA256）----
        val signedPart = if (oemCrypto.isNotEmpty()) ByteOps.concat(oemCrypto, msg) else msg
        val computed = Crypto.hmacSha256(macKeyServer, signedPart)
        if (!ByteOps.equal(computed, signature)) {
            throw IllegalStateException("license 签名校验失败（凭证可能不被服务端接受）")
        }

        // ---- 解出每个 KeyContainer 的内容密钥 ----
        val result = ArrayList<ContentKey>()
        val br = PbReader(msg)
        while (!br.eof()) {
            val f = br.next() ?: break
            if (f.field != 3) continue
            val (id, iv, key, ktype) = parseKeyContainer(f.bytes)
            if (key.isEmpty() || iv.isEmpty()) continue
            val padded = Crypto.aesCbcDecrypt(encKey, iv, key)
            val plain = Crypto.stripPkcs7(padded)
            val ck = ContentKey()
            ck.id = id
            ck.key = plain
            ck.type = ktype
            result.add(ck)
        }

        return result
    }

    private data class RawKeyContainer(
        val id: ByteArray, val iv: ByteArray, val key: ByteArray, val type: Int
    )

    private fun parseKeyContainer(data: ByteArray): RawKeyContainer {
        var id = ByteArray(0)
        var iv = ByteArray(0)
        var key = ByteArray(0)
        var type = 0
        val r = PbReader(data)
        while (!r.eof()) {
            val f = r.next() ?: break
            when (f.field) {
                1 -> id = f.bytes
                2 -> iv = f.bytes
                3 -> key = f.bytes
                4 -> type = f.varint.toInt()
            }
        }
        return RawKeyContainer(id, iv, key, type)
    }

    /** 只取 CONTENT 类型的密钥。 */
    fun contentKeys(keys: List<ContentKey>): List<ContentKey> =
        keys.filter { it.type == ContentKey.TYPE_CONTENT }

    /** base64 编码（helper，便于和鸿蒙版对照）。 */
    fun b64encode(d: ByteArray): String = Base64.encodeToString(d, Base64.NO_WRAP)
}
