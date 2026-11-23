package com.music.bitchord.playback.smart

/**
 * Diagnostics and external-facing status shapes for Automix.
 *
 * This file gathers the three pieces that sit at the edge of the automix
 * engine: where the analyser writes its warnings ([AnalysisLog]), which
 * rendition analysis is allowed to read ([AutomixAnalysisSource]), and the
 * model the now-playing line draws ([AutomixStatus]). None of them decide
 * musical policy; they only carry evidence out to the platform.
 */

/** Where the analyser's warnings go, without it having to know the platform. */
object AnalysisLog {

    private var sink: ((String, Throwable?) -> Unit)? = null

    fun install(sink: (String, Throwable?) -> Unit) {
        this.sink = sink
    }

    fun warn(message: String, error: Throwable? = null) {
        sink?.invoke(message, error)
    }
}

/**
 * Keeps Automix analysis on the canonical YouTube Opus rendition.
 *
 * Playback may deliberately use a source substitute (including JioSaavn), but
 * its cache entry is not interchangeable with the YouTube recording that the
 * analysis fetcher owns. The plain video id is the cache key pinned to that
 * YouTube copy; suffixed keys (`#alt`, `#hifi`, ...) belong to substitutes or
 * upgrades.
 */
object AutomixAnalysisSource {
    const val OPUS_QUERY_PARAMETER = "automix_opus"

    fun isCanonicalYouTubeRendition(videoId: String?, cacheKey: String): Boolean =
        videoId == null || cacheKey == videoId

    /**
     * A private playback URI for analysis-only reads.
     *
     * A normal `watch?v=` URI is allowed to reuse the source race already
     * pinned for audible playback. That is exactly the wrong thing for
     * Automix: its independently cached copy must always be YouTube Opus,
     * even while playback is JioSaavn or a lossless upgrade.
     */
    fun opusUri(videoId: String): String = "bitchord://watch?v=$videoId&$OPUS_QUERY_PARAMETER=1"

    fun requestsYouTubeOpus(marker: String?): Boolean = marker == "1"
}

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
