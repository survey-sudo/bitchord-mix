package com.music.bitchord.playback.smart

/**
 * How early the next deck is armed, from NullMusic's adaptive pre-arm.
 *
 * The base is 20 seconds. A preparation that took longer than 8 seconds
 * pushes the next one out, up to 35 seconds. A fast preparation walks it
 * back toward the base. Decode of a loop mix still starts as soon as the
 * plan exists; this only moves the moment the sinks are armed.
 */
object PrepLead {
    const val BASE_MS = 20_000L
    // 2B readiness: plausible 35–45 s pre-arming. The worst-case cold prime +
    // classic standby must fully buffer inside this; forcing a larger lead
    // than 45 s would thrash cold builds with no extra benefit.
    const val MAX_MS = 45_000L
    const val SLOW_MS = 8_000L

    fun next(previousLeadMs: Long, previousPrepMs: Long): Long {
        val prev = previousLeadMs.coerceIn(BASE_MS, MAX_MS)
        val prep = previousPrepMs.coerceAtLeast(0L)
        if (prep > SLOW_MS) return (prev + (prep - SLOW_MS)).coerceAtMost(MAX_MS)
        val room = SLOW_MS - prep
        val step = if (room > 2_000L) 2_000L else room
        val lowered = prev - step
        return if (lowered < BASE_MS) BASE_MS else lowered
    }
}
