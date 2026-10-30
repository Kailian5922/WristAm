package com.applemusic.lyricstool.data

interface AppleMusicUserTokenStorage {
    fun load(): String?
    fun save(token: String)
    fun clear()
}
