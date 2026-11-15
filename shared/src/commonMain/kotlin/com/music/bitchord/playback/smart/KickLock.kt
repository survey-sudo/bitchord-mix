package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Kick-lock beat alignment (vc69 P1): transitions must meet within an eighth
 * of a beat of the nearest kick transient, not just inside the bar.
 *
 * Orchard anchors candidates on measured downbeats (earmark planner
 * candidate.rs pick: downbeats where available, plain beats otherwise)
 * without sub-beat locking. BitChord tightens that to 1/8 of a beat: after
 * snapping to the nearest tracked beat, any remainder larger than the
 * tolerance is quantised to the nearest 1/8-beat grid point, so the swap
 * never lands up to half a beat off the kick it is meant to hit.
 */
object KickLock {
    /** One eighth of a beat -- the locking tolerance. */
    const val EIGHTH_BEAT = 0.125

    /**
     * Snaps time (track seconds) to the nearest kick within EIGHTH_BEAT of a
     * beat, or to the nearest eighth-beat grid point when no tracked beat is
     * within tolerance. The grid is anchored on gridOrigin (a downbeat or
     * the first beat); when beats itself is empty and no origin is usable
     * the phase of time is preserved.
     */
    fun snapToKickLock(
        time: Double,
        beatSec: Double,
        beats: List<Double> = emptyList(),
        gridOrigin: Double = 0.0,
    ): Double {
        if (!time.isFinite()) return time
        if (beatSec <= 0.0 || !beatSec.isFinite()) return time
        val tolerance = EIGHTH_BEAT * beatSec
        var best: Double? = null
        for (beat in beats) {
            if (!beat.isFinite()) continue
            val d = abs(beat - time)
            if (d <= tolerance && (best == null || d < abs(best!! - time))) best = beat
        }
        if (best != null) return best
        val origin = if (gridOrigin.isFinite()) gridOrigin else 0.0
        val phase = time - origin
        val eighth = beatSec * EIGHTH_BEAT
        val steps = floor(phase / eighth + 0.5).roundToLong()
        // Never lock backwards past the grid origin: a cue before the first
        // downbeat must not wrap to a negative eighth.
        return (origin + steps * eighth).coerceAtLeast(if (origin.isFinite()) origin else time)
    }

    /** Distance from time to its kick-locked position, in milliseconds. */
    fun kickLockErrorMs(time: Double, beatSec: Double, beats: List<Double> = emptyList(), gridOrigin: Double = 0.0): Double {
        if (!time.isFinite() || beatSec <= 0.0) return 0.0
        return abs(snapToKickLock(time, beatSec, beats, gridOrigin) - time) * 1000.0
    }

    /** True when time is already within an eighth of a beat of a tracked kick. */
    fun isKickLocked(time: Double, beatSec: Double, beats: List<Double>): Boolean {
        if (!time.isFinite() || beatSec <= 0.0 || !beatSec.isFinite()) return false
        val tolerance = EIGHTH_BEAT * beatSec
        return beats.any { it.isFinite() && abs(it - time) <= tolerance }
    }

    /** Grid point nearest time on the eighth-beat lattice anchored at gridOrigin. */
    fun nearestEighth(time: Double, beatSec: Double, gridOrigin: Double): Double {
        if (!time.isFinite() || beatSec <= 0.0 || !beatSec.isFinite()) return time
        val eighth = beatSec * EIGHTH_BEAT
        val steps = floor((time - gridOrigin) / eighth + 0.5).roundToLong()
        return (gridOrigin + steps * eighth).coerceAtLeast(min(gridOrigin, time))
    }
}
