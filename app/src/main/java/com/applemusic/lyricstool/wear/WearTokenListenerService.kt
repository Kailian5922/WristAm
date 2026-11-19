package com.applemusic.lyricstool.wear

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets

/**
 * 接收手机伴侣端发送的 Apple Music 账号令牌：
 * - [onRequest]：支持显式 "OK" / "ERR:..." 回执，手机端能确认手表是否真正加密保存成功；
 * - [onMessageReceived]：兼容无回执的普通消息通道；
 * - [onDataChanged]：支持手表离线重连后的 DataLayer 自动同步，并立即擦除临时 DataItem。
 */
class WearTokenListenerService : WearableListenerService() {

    override fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? {
        if (path != TOKEN_PATH) {
            return super.onRequest(nodeId, path, data)
        }
        val token = data.toString(StandardCharsets.UTF_8).trim()
        val ack = handleIncomingToken(token)
        return Tasks.forResult(ack.toByteArray(StandardCharsets.UTF_8))
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != TOKEN_PATH) {
            super.onMessageReceived(messageEvent)
            return
        }
        val token = messageEvent.data.toString(StandardCharsets.UTF_8).trim()
        handleIncomingToken(token)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                if (item.uri.path != TOKEN_PATH) continue
                val dataMap = DataMapItem.fromDataItem(item).dataMap
                val token = dataMap.getString(DATA_KEY_TOKEN).orEmpty().trim()
                if (token.isNotEmpty()) {
                    handleIncomingToken(token)
                }
                // 收到后立即擦除 Data Layer 中的敏感令牌
                try {
                    Wearable.getDataClient(applicationContext).deleteDataItems(item.uri)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to wipe consumed token DataItem: ${e.message}")
                }
            }
        } finally {
            dataEvents.release()
        }
    }

    private fun handleIncomingToken(token: String): String {
        val app = applicationContext as? WristAmApplication
        val store = app?.container?.tokenStore ?: EncryptedTokenStore(applicationContext)
        return try {
            store.save(token)
            app?.container?.authRepository?.onExternalTokenReceived()
            Log.i(TAG, "Received and encrypted the account token from the paired phone")
            "OK"
        } catch (error: Exception) {
            val msg = error.message ?: "令牌无法安全保存"
            store.setTransferError(msg)
            Log.e(TAG, "Unable to store the transferred account token", error)
            "ERR:$msg"
        }
    }

    private companion object {
        const val TAG = "WearTokenTransfer"
        const val TOKEN_PATH = "/account-token"
        const val DATA_KEY_TOKEN = "token"
    }
}
