package com.music.bitchord.playback.smart

import java.util.concurrent.locks.ReentrantLock

/**
 * Optional foreground stems never wait for the analysis model, nor sleep through
 * playback's background-analysis yield. Check again after acquisition. onStep is
 * a bounded scalar recorder only; telemetry/I/O is flushed by the build worker.
 */
internal fun <T> tryTransitionModel(
    modelLock: ReentrantLock,
    declineReason: () -> String?,
    onStep: ((String) -> Unit)? = null,
    model: () -> T?,
): T? {
    fun declined(): String? = if (Thread.currentThread().isInterrupted) "interrupted" else declineReason()
    declined()?.let { onStep?.invoke("stem-skip:$it"); return null }
    onStep?.invoke("model-lock")
    if (!modelLock.tryLock()) {
        onStep?.invoke("stem-skip:model-lock-unavailable")
        return null
    }
    var ranModel = false
    try {
        declined()?.let { onStep?.invoke("stem-skip:$it"); return null }
        onStep?.invoke("model")
        ranModel = true
        return model()
    } finally {
        modelLock.unlock()
        if (ranModel) onStep?.invoke("model-return")
    }
}
