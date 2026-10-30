package com.applemusic.android.util

import android.icu.text.Transliterator

object ChineseText {
    private val traditionalToSimplified =
        Transliterator.getInstance("Traditional-Simplified")

    fun toSimplified(text: String): String =
        synchronized(traditionalToSimplified) {
            traditionalToSimplified.transliterate(text)
        }
}
