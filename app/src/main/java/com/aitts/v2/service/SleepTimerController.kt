package com.aitts.v2.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Doze-proof monotonic clock sleep timer with smooth 15-second fade-out before stopping playback.
 */
class SleepTimerController(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null
    private var endElapsedMs: Long = 0L

    private val _remainingSecondsFlow = MutableStateFlow(0)
    val remainingSecondsFlow: StateFlow<Int> = _remainingSecondsFlow.asStateFlow()

    private val _isActiveFlow = MutableStateFlow(false)
    val isActiveFlow: StateFlow<Boolean> = _isActiveFlow.asStateFlow()

    private val _expiredFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expiredFlow: SharedFlow<Unit> = _expiredFlow.asSharedFlow()

    fun start(minutes: Int) {
        stop()
        if (minutes <= 0) return

        val totalSeconds = minutes * 60
        endElapsedMs = SystemClock.elapsedRealtime() + totalSeconds * 1000L
        _remainingSecondsFlow.value = totalSeconds
        _isActiveFlow.value = true

        timerJob = scope.launch {
            while (isActive) {
                val remMs = endElapsedMs - SystemClock.elapsedRealtime()
                if (remMs <= 0L) break
                _remainingSecondsFlow.value = ((remMs + 999L) / 1000L).toInt()
                delay(1000L)
            }
            _isActiveFlow.value = false
            _remainingSecondsFlow.value = 0
            _expiredFlow.tryEmit(Unit)
            val stopIntent = Intent(PlaybackNotificationController.ACTION_STOP_TTS).setPackage(appContext.packageName)
            appContext.sendBroadcast(stopIntent)
        }
    }

    fun stop() {
        timerJob?.cancel()
        timerJob = null
        endElapsedMs = 0L
        _isActiveFlow.value = false
        _remainingSecondsFlow.value = 0
    }

    fun getFadeVolumeFactor(): Float {
        if (!_isActiveFlow.value) return 1.0f
        val remSec = (endElapsedMs - SystemClock.elapsedRealtime()) / 1000.0f
        return when {
            remSec <= 0f -> 0f
            remSec <= 15f -> (remSec / 15f).coerceIn(0f, 1f)
            else -> 1f
        }
    }
}
