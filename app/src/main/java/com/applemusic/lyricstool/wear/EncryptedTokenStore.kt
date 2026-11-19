package com.applemusic.lyricstool.wear

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.applemusic.android.apple.TokenStore
import com.applemusic.android.util.Logx
import com.applemusic.lyricstool.data.AppleMusicUserTokenStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TokenSnapshot(
    val token: String = "",
    val transferError: String? = null
) {
    val isSignedIn: Boolean get() = token.trim().length >= EncryptedTokenStore.MIN_TOKEN_LENGTH
}

/**
 * 基于 AndroidKeyStore AES-GCM 的手表端安全令牌存储：
 * - 同步 KeyStore 锁 + 密钥损坏/失效自动重建恢复（杜绝 KeyStore 永久卡死）；
 * - 严格 `.trim()` 与 >= 50 长度校验，保存失败显式抛异常供上游回执；
 * - 区分 [clearTokenOnly] 与 [clear]，避免清空令牌时误删 `KEY_TRANSFER_ERROR`；
 * - 暴露响应式 [tokenFlow]，手机端同步到达后 UI 自动无感切入主界面。
 */
class EncryptedTokenStore(context: Context) : AppleMusicUserTokenStorage {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _tokenFlow = MutableStateFlow(readSnapshot())
    val tokenFlow: StateFlow<TokenSnapshot> = _tokenFlow.asStateFlow()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _tokenFlow.value = readSnapshot()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun readSnapshot(): TokenSnapshot {
        val t = load().orEmpty().trim()
        val err = preferences.getString(KEY_TRANSFER_ERROR, null)
        return TokenSnapshot(token = t, transferError = err)
    }

    override fun load(): String? = synchronized(keyLock) {
        val encodedIv = preferences.getString(KEY_IV, null) ?: return null
        val encodedToken = preferences.getString(KEY_TOKEN, null) ?: return null
        try {
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            val ciphertext = Base64.decode(encodedToken, Base64.NO_WRAP)
            val key = getOrCreateKeyLocked()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8).trim()
                .takeIf { it.length >= MIN_TOKEN_LENGTH }
        } catch (e: Exception) {
            Logx.w(TAG, "令牌解密失败（${e.javaClass.simpleName}: ${e.message}），清理损坏密文")
            if (isKeyCorruption(e)) {
                resetKeyStoreLocked()
                preferences.edit()
                    .remove(KEY_IV)
                    .remove(KEY_TOKEN)
                    .apply()
            }
            null
        }
    }

    override fun save(token: String) {
        val clean = token.trim()
        require(clean.length >= MIN_TOKEN_LENGTH) {
            "令牌格式无效（长度不足 $MIN_TOKEN_LENGTH 位）"
        }
        synchronized(keyLock) {
            try {
                encryptAndCommitLocked(clean)
            } catch (firstError: Exception) {
                if (isKeyCorruption(firstError)) {
                    Logx.w(TAG, "检测到 KeyStore 密钥失效，尝试重置后重新加密：${firstError.message}")
                    resetKeyStoreLocked()
                    try {
                        encryptAndCommitLocked(clean)
                        return
                    } catch (retryError: Exception) {
                        val msg = retryError.message ?: "加密存储失败"
                        setTransferError(msg)
                        throw IllegalStateException(msg, retryError)
                    }
                } else {
                    val msg = firstError.message ?: "加密存储失败"
                    setTransferError(msg)
                    throw IllegalStateException(msg, firstError)
                }
            }
        }
    }

    private fun encryptAndCommitLocked(cleanToken: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeyLocked())
        val ciphertext = cipher.doFinal(cleanToken.toByteArray(StandardCharsets.UTF_8))
        val ok = preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_TOKEN, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .remove(KEY_TRANSFER_ERROR)
            .commit()
        if (!ok) {
            throw IllegalStateException("写入令牌存储失败")
        }
        _tokenFlow.value = TokenSnapshot(token = cleanToken, transferError = null)
    }

    /** 仅清除密文，保留传输错误提示（供 AppleApi 同步空 token 时调用，防止抹掉错误原因）。 */
    fun clearTokenOnly() {
        synchronized(keyLock) {
            preferences.edit()
                .remove(KEY_IV)
                .remove(KEY_TOKEN)
                .apply()
            _tokenFlow.value = TokenSnapshot(
                token = "",
                transferError = preferences.getString(KEY_TRANSFER_ERROR, null)
            )
        }
    }

    /** 主动退出登录时完整清空令牌与错误状态。 */
    override fun clear() {
        synchronized(keyLock) {
            preferences.edit()
                .remove(KEY_IV)
                .remove(KEY_TOKEN)
                .remove(KEY_TRANSFER_ERROR)
                .apply()
            _tokenFlow.value = TokenSnapshot()
        }
    }

    fun transferError(): String? = preferences.getString(KEY_TRANSFER_ERROR, null)

    fun setTransferError(message: String) {
        synchronized(keyLock) {
            preferences.edit().putString(KEY_TRANSFER_ERROR, message).apply()
            _tokenFlow.value = _tokenFlow.value.copy(transferError = message)
        }
    }

    fun clearTransferError() {
        synchronized(keyLock) {
            preferences.edit().remove(KEY_TRANSFER_ERROR).apply()
            _tokenFlow.value = _tokenFlow.value.copy(transferError = null)
        }
    }

    fun refreshSnapshot() {
        _tokenFlow.value = readSnapshot()
    }

    private fun isKeyCorruption(e: Throwable): Boolean =
        e is KeyPermanentlyInvalidatedException ||
            e is UnrecoverableKeyException ||
            e is AEADBadTagException ||
            e is InvalidKeyException ||
            e is KeyStoreException

    private fun resetKeyStoreLocked() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            Logx.w(TAG, "删除损坏 KeyStore 别名失败：${e.message}")
        }
    }

    private fun getOrCreateKeyLocked(): SecretKey {
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

    companion object {
        private const val TAG = "EncryptedTokenStore"
        private const val PREFERENCES = "wear_account"
        private const val KEY_ALIAS = "lyrics_wear_account_token"
        private const val KEY_IV = "token_iv"
        private const val KEY_TOKEN = "token_ciphertext"
        private const val KEY_TRANSFER_ERROR = "transfer_error"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        const val MIN_TOKEN_LENGTH = 50
        private val keyLock = Any()
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
            val clean = value.trim()
            if (clean.isBlank()) {
                // 不擦除 KEY_TRANSFER_ERROR，避免登录页看不到手机传输错误提示
                encryptedTokenStore.clearTokenOnly()
            } else if (clean.length >= EncryptedTokenStore.MIN_TOKEN_LENGTH) {
                encryptedTokenStore.save(clean)
            }
        } else {
            preferences.edit().putString(key, value).apply()
        }
    }

    override fun remove(key: String) {
        if (key == USER_TOKEN_KEY) {
            encryptedTokenStore.clearTokenOnly()
        } else {
            preferences.edit().remove(key).apply()
        }
    }

    private companion object {
        const val USER_TOKEN_KEY = "media_user_token"
    }
}
