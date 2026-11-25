package com.music.bitchord.playback.djmix

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Process-wide admission gate shared by queue analysis and background pair/stem preparation. */
internal object PrepThermalGate {
    private const val RECHECK_MS = 1_000L
    private var state = PrepThermalState()

    @Synchronized
    fun shouldPause(thermalStatus: Int?): Boolean {
        state = state.update(thermalStatus)
        return state.paused
    }

    /** No invented sensor value on old APIs, absent services, or an unavailable thermal service. */
    fun currentStatus(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
        }.getOrNull()
    }

    suspend fun awaitReady(status: () -> Int?, isAborted: () -> Boolean): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (isAborted()) return false
            if (!shouldPause(status())) return true
            delay(RECHECK_MS)
        }
    }

    /** Only for dedicated prep/stem executors: no main/audio thread or active-session controls. */
    fun awaitReadyBlocking(status: () -> Int?, isAborted: () -> Boolean): Boolean {
        while (!Thread.currentThread().isInterrupted && !isAborted()) {
            if (!shouldPause(status())) return true
            try {
                Thread.sleep(RECHECK_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }
}
