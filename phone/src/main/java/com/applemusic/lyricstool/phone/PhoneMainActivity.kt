package com.applemusic.lyricstool.phone

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.applemusic.lyricstool.data.AppleLyricsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class PhoneMainActivity : ComponentActivity() {
    private val client by lazy { AppleLyricsClient(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
    var pendingExportToken by rememberSaveable { mutableStateOf<String?>(null) }
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
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                                writer.write(token)
                            }
                        } ?: error("无法打开目标文件")
                    }
                    snackbarHostState.showSnackbar("密钥文件已成功导出保存！")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("导出文件失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    LaunchedEffect(sessionRevision) {
        client.reloadSavedSession()
    }

    val signedIn = remember(sessionRevision) { client.isSignedIn }
    val token = remember(sessionRevision) { client.userToken }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "WristAM 手机同步伴侣",
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
                .verticalScroll(rememberScrollState())
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
                        imageVector = if (signedIn) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.Login,
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
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("登录 Apple Music", fontSize = 16.sp)
                }
            } else {
                OutlinedButton(
                    onClick = {
                        client.logout()
                        // 同时清理 WebView Cookie 与存储，确保下次登录可真正切换 Apple ID 账号
                        try {
                            val cm = CookieManager.getInstance()
                            cm.removeAllCookies(null)
                            cm.flush()
                            WebStorage.getInstance().deleteAllData()
                        } catch (_: Exception) {
                        }
                        sessionRevision++
                        scope.launch {
                            snackbarHostState.showSnackbar("已退出登录并清理网页登录缓存")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("退出登录 / 切换账号")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "手表密钥导出与同步",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // 核心功能 1：一键无线同步至手表（实时 ACK + 离线 DataLayer 队列兜底）
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
                        createDocumentLauncher.launch("wristam-account-token.txt")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出为密钥文件 (wristam-account-token.txt)")
                }

                // 备用功能 3：复制密钥到剪贴板（标记为敏感内容防止明文预览弹窗）
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Apple Music Token", token).apply {
                            description.extras = PersistableBundle().apply {
                                val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    ClipDescription.EXTRA_IS_SENSITIVE
                                } else {
                                    "android.content.extra.IS_SENSITIVE"
                                }
                                putBoolean(key, true)
                            }
                        }
                        clipboard.setPrimaryClip(clip)
                        scope.launch {
                            snackbarHostState.showSnackbar("密钥文本已安全复制到剪贴板！")
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

            Spacer(modifier = Modifier.height(8.dp))

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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "1. 在手机上登录 Apple Music 账号；\n" +
                            "2. 确认手表已安装同一签名的 WristAM 并处于蓝牙/Wi-Fi 连接状态；\n" +
                            "3. 点击【一键发送密钥到手表】，手表端收到后会立即用 KeyStore 加密保存并自动进入主界面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
