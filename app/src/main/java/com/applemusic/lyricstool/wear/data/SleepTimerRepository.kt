package com.applemusic.lyricstool.wear.data

import android.os.SystemClock
import com.applemusic.android.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class SleepTimerState(
    val endElapsedRealtimeMs: Long? = null,
    val durationMs: Long = 0L
) {
    val isActive: Boolean
        get() = endElapsedRealtimeMs != null

    fun remainingMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        val end = endElapsedRealtimeMs ?: return 0L
        return (end - nowElapsedMs).coerceAtLeast(0L)
    }
}

/**
 * 睡眠定时 / 定时播放仓库（Application 级单例）：
 * - 基于 [SystemClock.elapsedRealtime] 维护绝对截止时间，不受系统校时或界面销毁影响；
 * - 使用有界分片等待 + 播放状态变更校准，灭屏播放期间 WakeLock 保活精准到点暂停，
 *   暂停深睡后唤醒也可立刻对齐真实流失时间；
 * - 到点时自动调用 [PlaybackRepository.pause] 并广播到期提示事件。
 */
class SleepTimerRepository(
    private val appScope: CoroutineScope,
    private val playbackRepository: PlaybackRepository
) {
    private val lock = Any()
    private var timerJob: Job? = null

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private val _expiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val expiredEvents: SharedFlow<Unit> = _expiredEvents.asSharedFlow()

    init {
        // 当播放器状态改变（如暂停深睡后重新唤醒恢复播放）时立刻校准定时器，避免遗漏已到期的定时
        appScope.launch(Dispatchers.Main.immediate) {
            playbackRepository.stateFlow.collect {
                val endMs = _state.value.endElapsedRealtimeMs ?: return@collect
                if (SystemClock.elapsedRealtime() >= endMs) {
                    expireNow(expectedEndMs = endMs)
                }
            }
        }
    }

    fun start(durationMs: Long) {
        if (durationMs <= 0L) {
            cancel()
            return
        }
        val endMs = SystemClock.elapsedRealtime() + durationMs
        synchronized(lock) {
            timerJob?.cancel()
            _state.value = SleepTimerState(
                endElapsedRealtimeMs = endMs,
                durationMs = durationMs
            )
            timerJob = appScope.launch(Dispatchers.Main.immediate) {
                while (true) {
                    val activeEnd = _state.value.endElapsedRealtimeMs
                    if (activeEnd == null || activeEnd != endMs) break
                    val remaining = activeEnd - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) {
                        expireNow(expectedEndMs = endMs)
                        break
                    }
                    delay(minOf(remaining, CHECK_SLICE_MS))
                }
            }
        }
        Logx.i(TAG, "已启动睡眠定时：${durationMs / 1000L}s")
    }

    fun cancel() {
        val hadActive: Boolean
        synchronized(lock) {
            hadActive = _state.value.isActive
            timerJob?.cancel()
            timerJob = null
            _state.value = SleepTimerState()
        }
        if (hadActive) {
            Logx.i(TAG, "已关闭睡眠定时")
        }
    }

    private fun expireNow(expectedEndMs: Long) {
        val triggered = synchronized(lock) {
            if (_state.value.endElapsedRealtimeMs != expectedEndMs) {
                false
            } else {
                timerJob?.cancel()
                timerJob = null
                _state.value = SleepTimerState()
                true
            }
        }
        if (!triggered) return
        Logx.i(TAG, "睡眠定时结束，自动暂停播放")
        playbackRepository.pause()
        _expiredEvents.tryEmit(Unit)
    }

    companion object {
        private const val TAG = "SleepTimerRepository"
        private const val CHECK_SLICE_MS = 15_000L
    }
}

/** 格式化剩余倒计时（HH:mm:ss 或 mm:ss，向上取整秒）。 */
fun formatSleepTimerCountdown(remainingMs: Long): String {
    val totalSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** 格式化定时播放提示文案（如“30 分钟”或“1 小时 15 分钟”）。 */
fun formatSleepTimerDuration(hours: Int, minutes: Int): String = when {
    hours > 0 && minutes > 0 -> "${hours} 小时 ${minutes} 分钟"
    hours > 0 -> "${hours} 小时"
    else -> "${minutes} 分钟"
}
