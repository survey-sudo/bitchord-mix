package com.music.bitchord.playback.djmix

import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.alignTempoOctave
import com.music.bitchord.playback.smart.choreo.DjLevel
import kotlin.math.abs

/** Explicit execution policy, not a change to the stored musical evidence. */
enum class StraightRateMode { BEAT_MATCH, RATE_ONE }

/** Production straight-deck rate policy from measured beat evidence. */
object StraightRatePolicy {
    fun rateMode(out: TrackAnalysis?, inn: TrackAnalysis?, level: DjLevel): StraightRateMode {
        val a = out?.tailBeats?.bpm?.takeIf { it.isFinite() && it > 1.0 }
            ?: out?.bpm?.takeIf { it.isFinite() && it > 1.0 }
        val b = inn?.headBeats?.bpm?.takeIf { it.isFinite() && it > 1.0 }
            ?: inn?.bpm?.takeIf { it.isFinite() && it > 1.0 }
        val ca = out?.tailBeats?.confidence ?: out?.beatConfidence ?: 0.0
        val cb = inn?.headBeats?.confidence ?: inn?.beatConfidence ?: 0.0
        if (a == null || b == null || !ca.isFinite() || !cb.isFinite() || minOf(ca, cb) < 0.4) {
            return StraightRateMode.RATE_ONE
        }
        val folded = alignTempoOctave(a, b)
        val ratio = folded / a
        // Both decks, including the initial inverse rate, must fit the level's cap.
        return if (ratio.isFinite() && ratio > 0.0 &&
            abs(ratio - 1.0) <= level.maxStretch + 1e-9 &&
            abs(1.0 / ratio - 1.0) <= level.maxStretch + 1e-9
        ) StraightRateMode.BEAT_MATCH else StraightRateMode.RATE_ONE
    }
}
