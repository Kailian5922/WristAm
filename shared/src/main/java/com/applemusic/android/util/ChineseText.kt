package com.applemusic.android.util

import android.icu.text.Transliterator
import android.os.Build

object ChineseText {
    private val traditionalToSimplified: Any? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Transliterator.getInstance("Traditional-Simplified")
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return text
        val transliterator = traditionalToSimplified as? Transliterator ?: return text
        return synchronized(transliterator) {
            try {
                transliterator.transliterate(text)
            } catch (_: Throwable) {
                text
            }
        }
    }
}
