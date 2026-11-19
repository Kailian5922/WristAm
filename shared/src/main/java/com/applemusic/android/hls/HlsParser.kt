package com.applemusic.android.hls

import java.net.URI

/**
 * HLS（m3u8）解析 —— Apple Music 音频清单。
 */
class HlsByteRange {
    var length = 0
    var offset = 0
    var uri: String = ""
}

class HlsMediaInfo {
    /** 密钥 URI（优先 Widevine：`data:;base64,...`） */
    var keyUri = ""

    /** 密钥方法（ISO-23001-7 = CENC） */
    var keyMethod = ""

    /** init segment 的文件名 */
    var initUri = ""

    /** init segment 的字节范围 */
    var initRange = HlsByteRange()

    /** 分片所属媒体文件名（通常和 init 同文件） */
    var mediaUri = ""

    /** 各分片的字节范围 */
    val segments = ArrayList<HlsByteRange>()

    /** 清单所在基地址（用于解析相对/根相对/协议相对地址） */
    var baseUrl = ""
}

class HlsMasterInfo {
    val variants = ArrayList<String>()
    var widevinePssh = ""
    val skdUris = ArrayList<String>()
    var baseUrl = ""
}

object HlsParser {

    private fun dirOf(url: String): String {
        val clean = url.substringBefore('#').substringBefore('?')
        val i = clean.lastIndexOf('/')
        return if (i >= 0) clean.substring(0, i + 1) else clean
    }

    private fun abs(base: String, uri: String): String {
        if (uri.isEmpty()) return uri
        if (uri.startsWith("http://", ignoreCase = true) ||
            uri.startsWith("https://", ignoreCase = true) ||
            uri.startsWith("data:", ignoreCase = true) ||
            uri.startsWith("skd://", ignoreCase = true)
        ) {
            return uri
        }
        return try {
            URI(base).resolve(uri).toString()
        } catch (_: Throwable) {
            base + uri
        }
    }

    private fun parseRange(s: String, prevEnd: Int): HlsByteRange {
        val r = HlsByteRange()
        val parts = s.split("@")
        r.length = parts[0].trim().toIntOrNull() ?: 0
        r.offset = if (parts.size > 1) (parts[1].trim().toIntOrNull() ?: prevEnd) else prevEnd
        return r
    }

    private val uriRe = Regex("URI=\"([^\"]+)\"")
    private val keyFormatRe = Regex("KEYFORMAT=\"([^\"]+)\"")
    private val methodRe = Regex("METHOD=([^,\\s]+)")
    private val byterangeRe = Regex("BYTERANGE=\"([^\"]+)\"")

    /** 解析主清单。 */
    fun parseMaster(text: String, url: String): HlsMasterInfo {
        val info = HlsMasterInfo()
        info.baseUrl = dirOf(url)
        for (raw in text.split('\n')) {
            val l = raw.trim()
            if (l.startsWith("#EXT-X-SESSION-KEY")) {
                val m = uriRe.find(l) ?: continue
                val uri = m.groupValues[1]
                val fmt = keyFormatRe.find(l)?.groupValues?.get(1).orEmpty()
                if (fmt.contains("edef8ba9", ignoreCase = true)) {
                    info.widevinePssh = uri
                } else if (uri.startsWith("skd://", ignoreCase = true)) {
                    info.skdUris.add(uri)
                }
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                info.variants.add(abs(url, l))
            }
        }
        return info
    }

    /** 解析子清单（音频清单）。 */
    fun parseMedia(text: String, url: String): HlsMediaInfo {
        val info = HlsMediaInfo()
        info.baseUrl = dirOf(url)
        var pendingRange: HlsByteRange? = null
        var prevEnd = 0
        var hasPreferredWidevineKey = false

        for (raw in text.split('\n')) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-KEY:") -> {
                    val method = methodRe.find(l)?.groupValues?.get(1)?.trim().orEmpty()
                    val uri = uriRe.find(l)?.groupValues?.get(1).orEmpty()
                    val fmt = keyFormatRe.find(l)?.groupValues?.get(1).orEmpty()
                    if (method.equals("NONE", ignoreCase = true)) continue

                    val isWidevine = fmt.contains("edef8ba9", ignoreCase = true) ||
                        method.equals("ISO-23001-7", ignoreCase = true) ||
                        uri.startsWith("data:", ignoreCase = true)
                    // 优先锁定 Widevine/CENC 密钥，绝不被后续 FairPlay (skd://) 覆盖
                    if (isWidevine) {
                        info.keyMethod = method
                        if (uri.isNotEmpty()) info.keyUri = uri
                        hasPreferredWidevineKey = true
                    } else if (!hasPreferredWidevineKey && info.keyUri.isEmpty()) {
                        if (method.isNotEmpty()) info.keyMethod = method
                        if (uri.isNotEmpty()) info.keyUri = uri
                    }
                }

                l.startsWith("#EXT-X-MAP:") -> {
                    uriRe.find(l)?.let {
                        info.initUri = it.groupValues[1]
                        if (info.mediaUri.isEmpty()) info.mediaUri = it.groupValues[1]
                    }
                    byterangeRe.find(l)?.let {
                        info.initRange = parseRange(it.groupValues[1], 0)
                        prevEnd = info.initRange.offset + info.initRange.length
                    }
                }

                l.startsWith("#EXT-X-BYTERANGE:") -> {
                    val v = l.substring("#EXT-X-BYTERANGE:".length)
                    pendingRange = parseRange(v, prevEnd)
                }

                l.isNotEmpty() && !l.startsWith("#") -> {
                    if (info.mediaUri.isEmpty()) info.mediaUri = l
                    val pr = pendingRange
                    if (pr != null) {
                        pr.uri = l
                        info.segments.add(pr)
                        prevEnd = pr.offset + pr.length
                        pendingRange = null
                    } else {
                        info.segments.add(HlsByteRange().apply { uri = l })
                    }
                }
            }
        }
        return info
    }

    /** 把相对 URI 解析成绝对地址。 */
    fun absolute(baseUrl: String, uri: String): String = abs(baseUrl, uri)
}
