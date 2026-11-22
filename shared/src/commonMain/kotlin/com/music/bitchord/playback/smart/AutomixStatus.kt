package com.music.bitchord.playback.smart

/**
 * What the now-playing Automix line draws.
 *
 * [headline] is the one line that stays up: style, countdown, tempo and key.
 * [detail] is the diagnostic text (loop counts, what cleared them, a late
 * step and by how many milliseconds). The screen reads [headline] on its own
 * so the countdown does not rebuild the player, and reads [detail] only
 * while the line is expanded.
 *
 * [progress] runs from 0 when the countdown starts toward 1 as the mix
 * begins. Negative means there is no countdown to draw.
 */
data class AutomixStatus(
    val headline: String,
    val detail: String,
    val style: String,
    val progress: Float,
    /** Controller phase: idle/arm/mixing/echo/crossfade. */
    val state: String = "",
    /** Plan kind shown: Loop / Phrase 8-16 bars / Echo / Crossfade / Blend. */
    val planType: String = "",
    /** Overlap length in bars (0 when not phrase sized). */
    val bars: Int = 0,
    /** Tempo match of the pair in percent (100 = matched). */
    val tempoPct: Float = 0f,
    /** True once the shown countdown froze the rung. */
    val locked: Boolean = false,
    /** Planner rung behind this status, e.g. "Natural handoff" or "Beat blend". */
    val rung: String = "",
    /** Planner reason for that rung, e.g. "natural-end:before-smart-analysis-fallback-window". */
    val reason: String = "",
    /** Overlap length in bars; 0 means no overlap (a natural handoff). */
    val overlapBars: Int = 0,
)
