package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Admission checks that decide whether a planned transition may happen at all:
 * whether an Echo has positive musical evidence, whether a loop mix must duck a
 * stem, whether a bar is a reusable interpolation, and whether a swap lands on
 * the kick.
 *
 * All four are pure scalar predicates over already-measured evidence; none of
 * them synthesise a grid or touch audio.
 */

/**
 * Positive musical evidence for an automatic Echo, in addition to handoff
 * safety. A clean vocal mask or a plausible whole-track BPM is not a musical
 * exit. This does not promise listening quality or replace
 * qualifyMusicalHandoff.
 */
object EchoAdmission {
    fun musicalRefusal(handoff: MusicalHandoff, out: TrackAnalysis?, inn: TrackAnalysis?): String? {
        if (out == null || inn == null) return "echo-missing-local-analysis"
        val a = localGrid(out.tailBeats, handoff.outCaptureStart.sourceSec,
            handoff.outDryEnd.sourceSec) ?: return "echo-unmeasured-exit-grid"
        val b = localGrid(inn.headBeats, handoff.inAudibleStart.sourceSec,
            max(handoff.inLanding.sourceSec, handoff.inAudibleStart.sourceSec + .25))
            ?: return "echo-unmeasured-entry-grid"
        // The renderer's delay must agree with the measured local pulse, not
        // merely with an unrelated track-wide tempo estimate.
        if (!tempoAgrees(out.bpm, a) || !tempoAgrees(inn.bpm, b)) return "echo-local-tempo-mismatch"
        val exitAnchors = out.phraseBoundaries + out.mixOutCandidates.map { it.time } +
            listOf(out.mixOutTime) + out.tailBeats?.downbeats.orEmpty()
        if (!anchored(handoff.outDryEnd.sourceSec, exitAnchors, a)) return "echo-no-measured-exit"
        val entryAnchors = inn.headBeats?.downbeats.orEmpty() +
            listOfNotNull(inn.pickupTime, inn.audibleStartTime)
        if (!anchored(handoff.inAudibleStart.sourceSec, entryAnchors, b) &&
            !anchored(handoff.inLanding.sourceSec, inn.headBeats?.downbeats.orEmpty(), b)) {
            return "echo-no-measured-entry"
        }
        return null
    }

    private fun tempoAgrees(bpm: Double, beat: Double): Boolean =
        bpm.isFinite() && bpm > 1.0 && abs((60.0 / bpm) / beat - 1.0) <= .12

    private fun anchored(time: Double, anchors: List<Double>, beat: Double): Boolean =
        time.isFinite() && time >= 0.0 && anchors.any {
            it.isFinite() && it >= 0.0 && abs(it - time) <= min(.12, .2 * beat)
        }

    /** Returns the local measured beat length; never synthesizes a grid. */
    private fun localGrid(side: BeatSide?, from: Double, to: Double): Double? {
        side ?: return null
        if (!from.isFinite() || !to.isFinite() || from < 0.0 || to < from ||
            !side.confidence.isFinite() || side.confidence < MIN_BEATMATCH_CONFIDENCE ||
            !side.bpm.isFinite() || side.bpm <= 1.0 || side.beats.size < 4 ||
            side.beats.any { !it.isFinite() || it < 0.0 } ||
            side.beats.zipWithNext().any { (a, b) -> b <= a }) return null
        val expected = 60.0 / side.bpm
        // A cold entry can have an instantaneous landing. Retain enough
        // adjacent measured beats to prove its pulse without inventing a grid.
        val local = side.beats.filter { it >= max(0.0, from - 2 * expected) && it <= to + 4 * expected }
        if (local.size < 4 || local.first() > from + expected + 1e-8 ||
            local.last() < to - expected - 1e-8) return null
        val gaps = local.zipWithNext { a, b -> b - a }.sorted()
        val median = gaps[gaps.size / 2]
        if (median <= 0.0 || abs(median / expected - 1.0) > .12 ||
            gaps.any { abs(it / median - 1.0) > .20 }) return null
        return median
    }
}

/**
 * Pure stem-duck decision for the loop-mix renderer.
 *
 * The measured energy-weighted collision ([LoopMixDuck.decide]'s `clashE`)
 * decides where it was measured; the legacy boolean gate (`outHasVocal` /
 * `inHasEarlyVocal` / `fallbackTrigger`) is the fallback where it was not.
 * A duck decision taken from the boolean alone would re-litigate the veto
 * the planner already made with better data.
 */
object LoopMixDuck {

    const val TRIGGER = 0.12
    const val MIN_FLOOR = 0.15f
    const val SEVERE_INCOMING = 0.60
    const val DUCK_TARGET = 0.05

    data class Decision(
        val wantDuck: Boolean,
        val duckIncoming: Boolean,
        val outFloor: Float,
        val inFloor: Float,
        val clashE: Double?,
    )

    fun decide(
        clashE: Double?,
        outHasVocal: Boolean,
        inHasEarlyVocal: Boolean,
        fallbackTrigger: Boolean,
        trigger: Double = TRIGGER,
        minFloor: Float = MIN_FLOOR,
        severeIncoming: Double = SEVERE_INCOMING,
    ): Decision {
        val e = clashE?.takeIf { it.isFinite() }
        val wantDuck = if (e != null) e > trigger else (outHasVocal && inHasEarlyVocal) || fallbackTrigger
        val duckIncoming = if (!wantDuck) false else if (e != null) e > severeIncoming || inHasEarlyVocal else inHasEarlyVocal && outHasVocal
        val floor = if (wantDuck && e != null) {
            StemClash.duckGainFor(e, target = DUCK_TARGET, floor = minFloor.toDouble()).toFloat()
        } else {
            minFloor
        }
        val outFloor = floor
        val inFloor = if (duckIncoming) floor else minFloor
        return Decision(
            wantDuck = wantDuck,
            duckIncoming = duckIncoming,
            outFloor = outFloor,
            inFloor = inFloor,
            clashE = e,
        )
    }
}

/**
 * Interpolation (phrase reuse / mashup awareness).
 *
 * Scalars only, patent-safe: per-bar fingerprints from [SongMap] scalars
 * (energy level + sung bit) with no cross-track audio comparison. Two bars
 * with matching fingerprints are "interchangeable": an exit at a repeated
 * bar can be looped/extended seamlessly, and a clean outro phrase can be
 * replayed under the next track's intro.
 *
 * Fingerprint: quantize [PhraseSection.energyLevel] (1..10) to 0..10 and
 * combine with the sung bit: `energyLevel * 2 + (sung ? 1 : 0)`. Bars with
 * identical fingerprints match; adjacent levels within tolerance 1 also
 * count as a weak match.
 */
object InterpolationAnalyzer {

    /** Fingerprint of [bar] in [map]; -1 when there is no map evidence. */
    fun fingerprint(map: SongMap, bar: Int): Int {
        if (bar < 0 || bar >= map.sungBars.size) return -1
        val section = map.sectionAt(bar) ?: return -1
        val sung = if (map.sungBars[bar]) 1 else 0
        return section.energyLevel.coerceIn(1, 10) * 2 + sung
    }

    /** Full fingerprint array for [map] (empty when the map has no bars). */
    fun fingerprints(map: SongMap): IntArray {
        val n = map.sungBars.size
        if (n <= 0) return intArrayOf()
        return IntArray(n) { fingerprint(map, it) }
    }

    /**
     * How many other bars share [bar]'s fingerprint (exact), beyond
     * [minDistance] bars away. 0 means the bar is unique.
     */
    fun repeatedBars(map: SongMap, bar: Int, minDistance: Int = 8): Int {
        val fp = fingerprint(map, bar)
        if (fp < 0) return 0
        var count = 0
        for (other in 0 until map.sungBars.size) {
            if (other == bar) continue
            if (abs(other - bar) < minDistance) continue
            if (fingerprint(map, other) == fp) count++
        }
        return count
    }

    /**
     * First bar with the same fingerprint as [bar], or [bar] itself when
     * the bar is unique. `firstOccurrence(bar) = bar - matchedDistance`.
     */
    fun firstOccurrence(map: SongMap, bar: Int): Int {
        val fp = fingerprint(map, bar)
        if (fp < 0) return bar
        for (other in 0 until bar) {
            if (fingerprint(map, other) == fp) return other
        }
        return bar
    }

    /**
     * Bars that repeat an earlier bar (reuse opportunities): "run this
     * chorus again under B's intro" or "extend a clean outro phrase by
     * replaying an earlier clean bar". Returned in bar order.
     */
    fun interpolationOpportunities(map: SongMap): List<Int> {
        val n = map.sungBars.size
        if (n < 8) return emptyList()
        val seen = HashMap<Int, Int>()
        val out = ArrayList<Int>()
        for (bar in 0 until n) {
            val fp = fingerprint(map, bar)
            if (fp < 0) continue
            if (seen.containsKey(fp)) out.add(bar) else seen[fp] = bar
        }
        return out
    }

    /** Whether [bar] can be loop-extended (it has a clean repeat elsewhere). */
    fun isExtensible(map: SongMap, bar: Int): Boolean = repeatedBars(map, bar) > 0
}

/**
 * Kick-lock beat alignment: transitions must meet within an eighth of a beat
 * of the nearest kick transient, not just inside the bar.
 *
 * Orchard anchors candidates on measured downbeats (earmark planner
 * candidate.rs pick: downbeats where available, plain beats otherwise) without
 * sub-beat locking. BitChord tightens that to 1/8 of a beat: after snapping to
 * the nearest tracked beat, any remainder larger than the tolerance is
 * quantised to the nearest 1/8-beat grid point, so the swap never lands up to
 * half a beat off the kick it is meant to hit.
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
