package com.applemusic.lyricstool.wear.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusic.lyricstool.wear.data.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val tokenInput: String = "",
    val showManual: Boolean = false,
    val status: String = "",
    val busy: Boolean = false
)

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AuthUiState(status = authRepository.state.value.transferError.orEmpty())
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.state.collect { auth ->
                val err = auth.transferError
                if (!auth.signedIn && !err.isNullOrBlank()) {
                    _uiState.update { it.copy(status = err) }
                }
            }
        }
    }

    fun onTokenInputChanged(value: String) {
        _uiState.update { it.copy(tokenInput = value) }
    }

    fun toggleManualInput() {
        _uiState.update { it.copy(showManual = !it.showManual) }
    }

    fun onFilePickerUnavailable() {
        _uiState.update {
            it.copy(
                showManual = true,
                status = "手表无文件选择器，请手动输入或从手机端一键发送"
            )
        }
    }

    fun importTokenFile(uri: Uri) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, status = "正在导入令牌…") }
        viewModelScope.launch {
            val result = authRepository.importTokenFromUri(uri)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(busy = false, status = "账号令牌已保存") }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(busy = false, status = "导入失败：${e.message ?: "请检查文件"}")
                    }
                }
            )
        }
    }

    fun saveManualToken() {
        if (_uiState.value.busy) return
        val input = _uiState.value.tokenInput.trim()
        _uiState.update { it.copy(busy = true, status = "正在验证并加密保存…") }
        viewModelScope.launch {
            val result = authRepository.saveManualToken(input)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(busy = false, tokenInput = "", status = "账号令牌已保存") }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(busy = false, status = "保存失败：${e.message ?: "请检查令牌格式"}")
                    }
                }
            )
        }
    }
}
