package com.applemusic.lyricstool.phone

import android.content.Context
import com.google.android.gms.wearable.Wearable
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

object WearableTokenTransfer {
    private const val TOKEN_PATH = "/account-token"

    fun export(context: Context, token: String, onResult: (String) -> Unit) {
        if (token.length < 50) {
            onResult("Apple Music 登录凭据无效，请重新登录后再试")
            return
        }

        val appContext = context.applicationContext
        Wearable.getNodeClient(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    onResult("未找到已连接的 Wear OS 手表，请确认手表与手机蓝牙/WIFI已连接并打开手表端应用")
                    return@addOnSuccessListener
                }

                val remaining = AtomicInteger(nodes.size)
                val sent = AtomicInteger()
                val failed = AtomicInteger()
                nodes.forEach { node ->
                    Wearable.getMessageClient(appContext)
                        .sendMessage(
                            node.id,
                            TOKEN_PATH,
                            token.toByteArray(StandardCharsets.UTF_8)
                        )
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) sent.incrementAndGet() else failed.incrementAndGet()
                            if (remaining.decrementAndGet() == 0) {
                                onResult(
                                    when {
                                        sent.get() > 0 ->
                                            "已成功将密钥同步到 ${sent.get()} 台手表；手表已自动完成账号导入！"
                                        failed.get() > 0 ->
                                            "密钥传输失败，请确认手表端已打开并重试"
                                        else -> "未能将密钥发送到手表"
                                    }
                                )
                            }
                        }
                }
            }
            .addOnFailureListener { error ->
                onResult("查找已连接手表失败：${error.message ?: "请重试"}")
            }
    }
}
