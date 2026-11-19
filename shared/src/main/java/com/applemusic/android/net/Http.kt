package com.applemusic.android.net

import com.applemusic.android.util.Logx
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 极简 HTTP 客户端（java.net.HttpURLConnection，保持连接池 Keep-Alive 复用）。
 */
object Http {

    private const val TAG = "Http"

    class Response(
        val code: Int,
        val body: ByteArray,
        val headers: Map<String, List<String>>
    ) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    /**
     * 发起一次 HTTP 请求。
     *
     * @param rangeStart >=0 时带 Range 头；rangeEnd >=0 时是 `bytes=start-end`，否则 `bytes=start-`
     * @param retryNonIdempotent POST 等非幂等方法是否允许网络瞬断重试（默认 false）
     */
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeoutMs: Int = 15000,
        readTimeoutMs: Int = 30000,
        rangeStart: Int = -1,
        rangeEnd: Int = -1,
        retryNonIdempotent: Boolean = false
    ): Response {
        val upperMethod = method.uppercase()
        val canRetry = retryNonIdempotent ||
            upperMethod == "GET" || upperMethod == "HEAD" || upperMethod == "DELETE" || upperMethod == "PUT"
        val maxAttempts = if (canRetry) 3 else 1
        var attempts = 0

        while (true) {
            attempts++
            var conn: HttpURLConnection? = null
            try {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("HTTP request cancelled before start")
                }
                conn = (URL(url).openConnection() as HttpURLConnection)
                conn.requestMethod = upperMethod
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.instanceFollowRedirects = true
                conn.useCaches = false
                for ((k, v) in headers) {
                    conn.setRequestProperty(k, v)
                }
                if (rangeStart >= 0) {
                    conn.setRequestProperty(
                        "Range",
                        if (rangeEnd >= 0) "bytes=$rangeStart-$rangeEnd" else "bytes=$rangeStart-"
                    )
                }
                if (body != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                } else if (upperMethod == "POST" || upperMethod == "PUT") {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(0)
                    conn.outputStream.use { }
                }

                val code = conn.responseCode
                val stream: InputStream? = try {
                    conn.inputStream
                } catch (_: Exception) {
                    conn.errorStream
                }
                // 使用 .use 归还底层连接到 Keep-Alive 连接池，绝不盲目 disconnect()
                val bytes = stream?.use { it.readBytesSafe() } ?: ByteArray(0)

                val hs = HashMap<String, List<String>>()
                for ((k, v) in conn.headerFields) {
                    if (k != null) hs[k.lowercase()] = v ?: emptyList()
                }

                // 针对瞬态限流/网关错误（429/502/503/504）自动退避重试
                if (canRetry && attempts < maxAttempts &&
                    (code == 429 || code == 502 || code == 503 || code == 504)
                ) {
                    val retryAfterSec = hs["retry-after"]?.firstOrNull()?.trim()?.toLongOrNull()
                    val delayMs = ((retryAfterSec?.times(1000L)) ?: (500L * attempts)).coerceIn(300L, 5000L)
                    Logx.w(TAG, "$upperMethod $url -> HTTP $code，${delayMs}ms 后重试 ($attempts/$maxAttempts)")
                    sleepInterruptibly(delayMs)
                    continue
                }

                if (code >= 400) {
                    Logx.w(TAG, "$upperMethod $url -> HTTP $code body=${String(bytes, Charsets.UTF_8).take(160)}")
                }
                return Response(code, bytes, hs)
            } catch (t: Throwable) {
                try {
                    conn?.disconnect()
                } catch (_: Throwable) {
                }
                if (t is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw t
                }
                val isTransient = t is UnknownHostException ||
                    t is SocketTimeoutException ||
                    t is ConnectException ||
                    t is SocketException ||
                    t is EOFException ||
                    t is SSLException
                if (isTransient && canRetry && attempts < maxAttempts) {
                    val delayMs = 400L * attempts
                    Logx.w(TAG, "$upperMethod $url 网络重试 ($attempts/$maxAttempts): ${t.javaClass.simpleName}: ${t.message}")
                    sleepInterruptibly(delayMs)
                    continue
                }
                throw t
            }
        }
    }

    private fun sleepInterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ie
        }
    }

    /** GET 文本，非 2xx 抛异常。 */
    fun getText(url: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 25000): String {
        val r = request(url, "GET", headers, null, 15000, readTimeoutMs)
        if (r.code !in 200..299) throw RuntimeException("HTTP ${r.code} @ $url")
        return r.text
    }

    /** GET Range，返回含状态码的完整 Response（调用方可区分 206 与 200）。 */
    fun getRange(
        url: String,
        headers: Map<String, String> = emptyMap(),
        rangeStart: Int = -1,
        rangeEnd: Int = -1,
        readTimeoutMs: Int = 60000
    ): Response {
        val r = request(url, "GET", headers, null, 15000, readTimeoutMs, rangeStart, rangeEnd)
        if (r.code != 200 && r.code != 206) {
            throw RuntimeException("HTTP ${r.code} @ $url")
        }
        return r
    }

    /** GET 字节，接受 200/206。 */
    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        rangeStart: Int = -1,
        rangeEnd: Int = -1,
        readTimeoutMs: Int = 60000
    ): ByteArray = getRange(url, headers, rangeStart, rangeEnd, readTimeoutMs).body

    /** POST JSON，期望 2xx。 */
    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
        readTimeoutMs: Int = 30000
    ): String {
        val r = request(
            url, "POST", headers, jsonBody.toByteArray(Charsets.UTF_8), 15000, readTimeoutMs
        )
        if (r.code !in 200..299) {
            throw RuntimeException("HTTP ${r.code} @ $url body=${r.text.take(200)}")
        }
        return r.text
    }

    /** POST，返回原始 code（资料库写入要区分 200/202/204）。 */
    fun postRaw(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
        readTimeoutMs: Int = 20000
    ): Response = request(url, "POST", headers, jsonBody.toByteArray(Charsets.UTF_8), 15000, readTimeoutMs)

    private fun InputStream.readBytesSafe(): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            if (n > 0) out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
