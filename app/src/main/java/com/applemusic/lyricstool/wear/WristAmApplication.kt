package com.applemusic.lyricstool.wear

import android.app.Application
import android.content.Context
import android.util.Log
import com.applemusic.android.util.Logx
import com.applemusic.lyricstool.wear.di.WearAppContainer

class WristAmApplication : Application() {

    lateinit var container: WearAppContainer
        private set

    @Volatile
    var startupError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        Logx.init(filesDir)

        // 链式保存崩溃日志并交还给系统默认处理器（不再吞掉崩溃导致界面假死）
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
                Logx.e(TAG, "Crash on ${thread.name}: ${throwable.message}", throwable)
            } catch (_: Throwable) {
            }
            previousHandler?.uncaughtException(thread, throwable)
        }

        try {
            container = WearAppContainer(this)
        } catch (t: Throwable) {
            Log.e(TAG, "Application startup failed", t)
            startupError = "${t.javaClass.simpleName}: ${t.message}\n" +
                t.stackTrace.take(5).joinToString("\n")
        }
    }

    companion object {
        private const val TAG = "WristAmApp"

        fun from(context: Context): WristAmApplication =
            context.applicationContext as WristAmApplication
    }
}
