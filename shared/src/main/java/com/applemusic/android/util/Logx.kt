package com.applemusic.android.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 线程安全的极简日志 + 落盘诊断日志。
 * 同时写一份到 `filesDir/diag.log`（超过 2MB 截断重来）。
 */
object Logx {

    private const val TAG = "AppleMusic"
    private const val MAX_LOG_BYTES = 2L * 1024 * 1024

    @Volatile
    var logFile: File? = null

    private val fileLock = Any()

    private val fmtThreadLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun init(filesDir: File) {
        synchronized(fileLock) {
            if (logFile == null) {
                logFile = File(filesDir, "diag.log")
            }
        }
    }

    fun d(tag: String, msg: String) {
        Log.d("$TAG/$tag", msg)
        write("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i("$TAG/$tag", msg)
        write("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w("$TAG/$tag", msg)
        write("W", tag, msg)
    }

    fun e(tag: String, msg: String) {
        Log.e("$TAG/$tag", msg)
        write("E", tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable) {
        Log.e("$TAG/$tag", msg, t)
        write("E", tag, "$msg\n${Log.getStackTraceString(t)}")
    }

    private fun write(level: String, tag: String, msg: String) {
        val f = logFile ?: return
        val ts = fmtThreadLocal.get()?.format(Date()).orEmpty()
        synchronized(fileLock) {
            try {
                if (f.exists() && f.length() > MAX_LOG_BYTES) {
                    f.delete()
                }
                f.appendText("$ts $level/$tag $msg\n")
            } catch (_: Throwable) {
                // 日志本身不抛异常
            }
        }
    }
}
