package com.music.bitchord.playback.djmix

import androidx.media3.exoplayer.ExoPlayer

/**
 * Tracks player position between ExoPlayer position reports, extrapolating
 * smoothly across ticks at the player's current speed (M9 C-7).
 */
internal class PositionClock(
    private val halfTickNanos: Long = DEFAULT_HALF_TICK_NANOS,
    private val maxExtrapolationNanos: Long = DEFAULT_MAX_EXTRAPOLATION_NANOS,
) {
    companion object {
        const val DEFAULT_HALF_TICK_NANOS = 25_000_000L
        const val DEFAULT_MAX_EXTRAPOLATION_NANOS = 600_000_000L
    }

    private var player: ExoPlayer? = null
    private var reported = 0L
    private var seenAt = 0L

    fun reset() {
        player = null
    }

    /** [p]'s position now, in ms. */
    fun read(p: ExoPlayer): Double {
        val now = System.nanoTime()
        val position = p.currentPosition
        if (p !== player || position != reported) {
            seenAt = if (p === player) now - halfTickNanos else now
            player = p
            reported = position
        }
        if (!p.isPlaying) return position.toDouble()
        val since = (now - seenAt).coerceIn(0L, maxExtrapolationNanos)
        return position + since / 1e6 * p.playbackParameters.speed
    }
}
