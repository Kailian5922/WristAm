package com.applemusic.lyricstool.wear.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.applemusic.lyricstool.wear.EncryptedTokenStore
import com.applemusic.lyricstool.wear.viewmodel.AuthViewModel

@Composable
fun AuthSetupScreen(
    viewModel: AuthViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()

    val importTokenFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importTokenFile(uri)
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            item(key = "header") {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "登录与连接",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            item(key = "guide") {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "已连手机？在手机 WristAM 同步伴侣登录 Apple Music，点击“一键发送密钥到手表”即可自动导入。",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            if (state.status.isNotBlank()) {
                item(key = "status") {
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.status.contains("失败") || state.status.contains("无效")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
            }

            item(key = "import_file") {
                FilledTonalButton(
                    enabled = !state.busy,
                    onClick = {
                        try {
                            importTokenFile.launch(arrayOf("text/plain", "application/octet-stream"))
                        } catch (_: ActivityNotFoundException) {
                            viewModel.onFilePickerUnavailable()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择令牌文件")
                }
            }

            item(key = "toggle_manual") {
                OutlinedButton(
                    enabled = !state.busy,
                    onClick = { viewModel.toggleManualInput() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.showManual) "收起手动输入" else "手动输入令牌")
                }
            }

            if (state.showManual) {
                item(key = "manual_input") {
                    OutlinedTextField(
                        value = state.tokenInput,
                        onValueChange = viewModel::onTokenInputChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("粘贴令牌文本") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedContainerColor = Color(0xFF1E1E1E),
                            unfocusedContainerColor = Color(0xFF1E1E1E)
                        ),
                        singleLine = true
                    )
                }
                item(key = "manual_save") {
                    Button(
                        enabled = !state.busy && state.tokenInput.trim().length >= EncryptedTokenStore.MIN_TOKEN_LENGTH,
                        onClick = viewModel::saveManualToken,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.busy) "保存中…" else "保存并连接")
                    }
                }
            }
        }
    }
}
