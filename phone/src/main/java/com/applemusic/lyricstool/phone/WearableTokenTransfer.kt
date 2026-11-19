package com.applemusic.lyricstool.phone

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机端 -> 手表端账号密钥同步器：
 * 1. 优先通过 CapabilityClient 定位安装了 WristAM 的可达手表节点（回退至 connectedNodes）；
 * 2. 使用 MessageClient.sendRequest 发送密钥并等待手表端显式 ACK（"OK" / "ERR:..."），绝不假报成功；
 * 3. 若手表离线或实时请求超时，自动通过 DataClient.putDataItem(urgent) 排队投递（手表收到后立即擦除 DataItem）。
 */
object WearableTokenTransfer {
    const val CAPABILITY_TOKEN_RECEIVER = "wristam_token_receiver"
    const val TOKEN_PATH = "/account-token"
    const val DATA_KEY_TOKEN = "token"
    const val DATA_KEY_TS = "ts"

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun postResult(onResult: (String) -> Unit, message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onResult(message)
        } else {
            mainHandler.post { onResult(message) }
        }
    }

    fun export(context: Context, rawToken: String, onResult: (String) -> Unit) {
        val token = rawToken.trim()
        if (token.length < 50) {
            postResult(onResult, "Apple Music 登录凭据无效，请重新登录后再试")
            return
        }

        val appContext = context.applicationContext
        Wearable.getCapabilityClient(appContext)
            .getCapability(CAPABILITY_TOKEN_RECEIVER, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                val capNodes = info.nodes.toList()
                if (capNodes.isNotEmpty()) {
                    sendToNodes(appContext, capNodes.map { it.id }, token, onResult)
                } else {
                    fallbackConnectedNodes(appContext, token, onResult)
                }
            }
            .addOnFailureListener {
                fallbackConnectedNodes(appContext, token, onResult)
            }
    }

    private fun fallbackConnectedNodes(
        appContext: Context,
        token: String,
        onResult: (String) -> Unit
    ) {
        Wearable.getNodeClient(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    queueViaDataLayer(appContext, token) { queued ->
                        if (queued) {
                            postResult(
                                onResult,
                                "未检测到在线手表，已写入待同步队列；手表重新连接蓝牙/Wi-Fi 并打开 WristAM 后将自动导入"
                            )
                        } else {
                            postResult(
                                onResult,
                                "未找到已连接的 Wear OS 手表，请确认蓝牙/Wi-Fi 已连接，且手机端与手表端已安装同一版本的 WristAM"
                            )
                        }
                    }
                } else {
                    sendToNodes(appContext, nodes.map { it.id }, token, onResult)
                }
            }
            .addOnFailureListener { error ->
                postResult(onResult, "查找已连接手表失败：${error.message ?: "请重试"}")
            }
    }

    private fun sendToNodes(
        appContext: Context,
        nodeIds: List<String>,
        token: String,
        onResult: (String) -> Unit
    ) {
        val payload = token.toByteArray(StandardCharsets.UTF_8)
        val messageClient = Wearable.getMessageClient(appContext)
        val remaining = AtomicInteger(nodeIds.size)
        val confirmed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val watchError = AtomicReference<String?>(null)

        nodeIds.forEach { nodeId ->
            messageClient.sendRequest(nodeId, TOKEN_PATH, payload)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val ack = task.result?.toString(StandardCharsets.UTF_8)?.trim().orEmpty()
                        when {
                            ack == "OK" -> confirmed.incrementAndGet()
                            ack.startsWith("ERR:") -> {
                                failed.incrementAndGet()
                                watchError.compareAndSet(null, ack.removePrefix("ERR:").trim())
                            }
                            else -> {
                                // 兼容不返回显式 ACK 的场景，仍回退走一次 DataLayer 确保落盘
                                failed.incrementAndGet()
                            }
                        }
                    } else {
                        failed.incrementAndGet()
                    }

                    if (remaining.decrementAndGet() == 0) {
                        val okCount = confirmed.get()
                        val errMsg = watchError.get()
                        if (okCount > 0) {
                            clearPendingDataItem(appContext)
                            postResult(
                                onResult,
                                "已成功将密钥同步到 $okCount 台手表，手表已确认安全加密保存！"
                            )
                        } else if (!errMsg.isNullOrBlank()) {
                            postResult(onResult, "手表端保存密钥失败：$errMsg")
                        } else {
                            // 实时 RPC 未送达（如手表息屏/休眠或旧版），降级使用紧急 DataItem 投递
                            queueViaDataLayer(appContext, token) { queued ->
                                if (queued) {
                                    postResult(
                                        onResult,
                                        "手表未即时响应，已通过后台数据通道下发密钥；请唤醒手表并打开 WristAM 完成自动导入"
                                    )
                                } else {
                                    postResult(
                                        onResult,
                                        "密钥传输失败，请确认手表端已安装并打开新版 WristAM（请先卸载旧包名版本）后重试"
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun queueViaDataLayer(
        appContext: Context,
        token: String,
        onDone: (Boolean) -> Unit
    ) {
        try {
            val request = PutDataMapRequest.create(TOKEN_PATH).apply {
                dataMap.putString(DATA_KEY_TOKEN, token)
                dataMap.putLong(DATA_KEY_TS, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(appContext)
                .putDataItem(request)
                .addOnSuccessListener { onDone(true) }
                .addOnFailureListener { onDone(false) }
        } catch (_: Exception) {
            onDone(false)
        }
    }

    private fun clearPendingDataItem(appContext: Context) {
        try {
            val uri = Uri.Builder()
                .scheme("wear")
                .path(TOKEN_PATH)
                .build()
            Wearable.getDataClient(appContext).deleteDataItems(uri)
        } catch (_: Exception) {
        }
    }
}
