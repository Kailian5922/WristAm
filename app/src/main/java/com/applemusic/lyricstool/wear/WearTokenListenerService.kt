package com.applemusic.lyricstool.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearTokenListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != TOKEN_PATH) {
            super.onMessageReceived(messageEvent)
            return
        }

        val store = EncryptedTokenStore(applicationContext)
        try {
            val token = messageEvent.data.toString(Charsets.UTF_8)
            store.save(token)
            Log.i(TAG, "Received and encrypted the account token from the paired phone")
        } catch (error: Exception) {
            store.setTransferError(error.message ?: "令牌无法安全保存")
            Log.e(TAG, "Unable to store the transferred account token", error)
        }
    }

    private companion object {
        const val TAG = "WearTokenTransfer"
        const val TOKEN_PATH = "/account-token"
    }
}
