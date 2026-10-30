package com.applemusic.lyricstool.phone

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.applemusic.lyricstool.data.AppleLyricsClient
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class PhoneMainActivity : ComponentActivity() {
    private val client by lazy { AppleLyricsClient(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6750A4),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFEADDFF),
                    onPrimaryContainer = Color(0xFF21005D),
                    surface = Color(0xFFFFF8F7),
                    background = Color(0xFFFFF8F7)
                )
            ) {
                PhoneTokenExportScreen(client = client)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneTokenExportScreen(client: AppleLyricsClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var sessionRevision by remember { mutableIntStateOf(0) }
    var pendingExportToken by remember { mutableStateOf<String?>(null) }
    var isSendingToWatch by remember { mutableStateOf(false) }

    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            client.reloadSavedSession()
            sessionRevision++
            scope.launch {
                snackbarHostState.showSnackbar("Apple Music 登录成功！")
            }
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val token = pendingExportToken
        pendingExportToken = null
        if (uri != null && !token.isNullOrBlank()) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                        writer.write(token)
                    }
                }
                scope.launch {
                    snackbarHostState.showSnackbar("密钥文件已成功导出保存！")
                }
            } catch (e: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar("导出文件失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    LaunchedEffect(sessionRevision) {
        client.reloadSavedSession()
    }

    val signedIn = client.isSignedIn
    val token = client.userToken

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "手表密钥同步伴侣",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (signedIn) Color(0xFFEDF7ED) else Color(0xFFFFF4E5)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (signedIn) Icons.Default.CheckCircle else Icons.Default.Login,
                        contentDescription = null,
                        tint = if (signedIn) Color(0xFF2E7D32) else Color(0xFFED6C02),
                        modifier = Modifier.size(32.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (signedIn) "已登录 Apple Music 账号" else "尚未登录 Apple Music",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = if (signedIn) Color(0xFF1E4620) else Color(0xFF663C00)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (signedIn)
                                "账号密钥已就绪，可随时通过下方按钮一键同步到手表。"
                            else
                                "请先登录 Apple Music 账号获取播放授权密钥。",
                            fontSize = 13.sp,
                            color = if (signedIn) Color(0xFF2E7D32) else Color(0xFF8A5300)
                        )
                    }
                }
            }

            if (!signedIn) {
                Button(
                    onClick = {
                        loginLauncher.launch(Intent(context, AppleMusicLoginActivity::class.java))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Login, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("登录 Apple Music", fontSize = 16.sp)
                }
            } else {
                OutlinedButton(
                    onClick = {
                        client.logout()
                        sessionRevision++
                        scope.launch {
                            snackbarHostState.showSnackbar("已退出登录")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("退出登录 / 切换账号")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "手表密钥导出与同步",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // 核心功能 1：一键无线同步至手表
                Button(
                    onClick = {
                        if (isSendingToWatch) return@Button
                        isSendingToWatch = true
                        WearableTokenTransfer.export(context, token) { message ->
                            isSendingToWatch = false
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    enabled = !isSendingToWatch
                ) {
                    if (isSendingToWatch) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("正在发送给手表…", fontSize = 16.sp)
                    } else {
                        Icon(Icons.Default.Watch, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("一键发送密钥到手表 (推荐)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // 备用功能 2：导出为 txt 文件（可通过文件传输发给手表）
                FilledTonalButton(
                    onClick = {
                        pendingExportToken = token
                        createDocumentLauncher.launch("lyrics-account-token.txt")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出为密钥文件 (lyrics-account-token.txt)")
                }

                // 备用功能 3：复制密钥到剪贴板
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Apple Music Token", token)
                        clipboard.setPrimaryClip(clip)
                        scope.launch {
                            snackbarHostState.showSnackbar("密钥文本已复制到剪贴板！")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("复制密钥文本")
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "使用说明：",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "1. 在手机上登录 Apple Music 账号；\n" +
                        "2. 保持手表端“逐字歌词”处于打开状态；\n" +
                        "3. 点击【一键发送密钥到手表】，手表将秒级自动接收并完成授权登录，无需繁琐输入！",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
