package com.applemusic.lyricstool.wear

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.applemusic.android.apple.TokenStore
import com.applemusic.lyricstool.data.AppleMusicUserTokenStorage
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedTokenStore(context: Context) : AppleMusicUserTokenStorage {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): String? {
        return try {
            val encodedIv = preferences.getString(KEY_IV, null) ?: return null
            val encodedToken = preferences.getString(KEY_TOKEN, null) ?: return null
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            val ciphertext = Base64.decode(encodedToken, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override fun save(token: String) {
        if (token.length < MIN_TOKEN_LENGTH) return
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
            preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_TOKEN, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .remove(KEY_TRANSFER_ERROR)
                .apply()
        } catch (e: Exception) {
            setTransferError(e.message ?: "加密存储失败")
        }
    }

    override fun clear() {
        preferences.edit()
            .remove(KEY_IV)
            .remove(KEY_TOKEN)
            .remove(KEY_TRANSFER_ERROR)
            .apply()
    }

    fun transferError(): String? = preferences.getString(KEY_TRANSFER_ERROR, null)

    fun setTransferError(message: String) {
        preferences.edit().putString(KEY_TRANSFER_ERROR, message).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "wear_account"
        const val KEY_ALIAS = "lyrics_wear_account_token"
        const val KEY_IV = "token_iv"
        const val KEY_TOKEN = "token_ciphertext"
        const val KEY_TRANSFER_ERROR = "transfer_error"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val MIN_TOKEN_LENGTH = 50
    }
}

class WearAppleApiTokenStore(
    context: Context,
    private val encryptedTokenStore: EncryptedTokenStore
) : TokenStore {
    private val preferences =
        context.applicationContext.getSharedPreferences("wear_apple_api", Context.MODE_PRIVATE)

    override fun getString(key: String, def: String): String =
        if (key == USER_TOKEN_KEY) {
            encryptedTokenStore.load() ?: def
        } else {
            preferences.getString(key, def) ?: def
        }

    override fun putString(key: String, value: String) {
        if (key == USER_TOKEN_KEY) {
            if (value.isBlank()) encryptedTokenStore.clear() else encryptedTokenStore.save(value)
        } else {
            preferences.edit().putString(key, value).apply()
        }
    }

    override fun remove(key: String) {
        if (key == USER_TOKEN_KEY) {
            encryptedTokenStore.clear()
        } else {
            preferences.edit().remove(key).apply()
        }
    }

    private companion object {
        const val USER_TOKEN_KEY = "media_user_token"
    }
}
