package com.music.bitchord.djmix.core.recipes

import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.EnvelopeKind
import com.music.bitchord.djmix.core.contracts.EnvelopeLane
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.ScriptFxParams
import com.music.bitchord.djmix.core.contracts.StemKind
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Runtime choreography invariant verifier for [TransitionScriptV1].
 *
 * Enforces numerical finiteness, strictly monotonic knot and automation timestamps,
 * splice/handover boundary neutrality, non-doubled low frequencies (`<= 1 beat` crossover),
 * and per-[com.music.bitchord.djmix.core.contracts.DjLevel] FX slot budgets before handoff to the native renderer.
 */
object ChoreoGuard {

    /** Linear low-end threshold (-6 dB) above which dual bass may not linger past one beat. */
    const val BASS_PRESENT_LINEAR: Float = 0.5f

    /** Linear low-end threshold (~ -0.9 dB) above which simultaneous bass is never allowed. */
    const val BASS_FULL_LINEAR: Float = 0.9f

    /** Step size (seconds) used when scanning bass/low-end overlap across the transition. */
    const val BASS_STEP_SEC: Double = 0.005

    /** Maps a stem [EnvelopeKind] to its [StemKind], or null for non-stem lanes. */
    fun stemKindFor(kind: EnvelopeKind): StemKind? = when (kind) {
        EnvelopeKind.STEM_VOCALS -> StemKind.VOCALS
        EnvelopeKind.STEM_DRUMS -> StemKind.DRUMS
        EnvelopeKind.STEM_BASS -> StemKind.BASS
        EnvelopeKind.STEM_OTHER -> StemKind.OTHER
        else -> null
    }

    /** True when [value] represents the bypassed/neutral state for [kind]. */
    fun isNeutral(kind: EnvelopeKind, value: Float): Boolean {
        if (!value.isFinite()) return false
        return when (kind) {
            EnvelopeKind.GAIN -> abs(value - 1f) <= 0.02f
            EnvelopeKind.LOW_DB, EnvelopeKind.MID_DB, EnvelopeKind.HIGH_DB -> abs(value) <= 0.25f
            EnvelopeKind.HIGH_PASS_HZ -> value <= 25f
            EnvelopeKind.LOW_PASS_HZ -> value >= 19_000f
            EnvelopeKind.FILTER_RES -> abs(value - 1f) <= 0.05f
            EnvelopeKind.FILTER_KNOB -> abs(value) <= 0.02f
            EnvelopeKind.ECHO_SEND,
            EnvelopeKind.REVERB_SEND,
            EnvelopeKind.FLANGER_WET,
            EnvelopeKind.PHASER_WET,
            EnvelopeKind.GATE_DEPTH,
            EnvelopeKind.RISER_LEVEL,
            EnvelopeKind.PUMP_DEPTH,
            EnvelopeKind.BRAKE,
            -> value in -1e-4f..0.02f
            EnvelopeKind.STEM_VOCALS,
            EnvelopeKind.STEM_DRUMS,
            EnvelopeKind.STEM_BASS,
            EnvelopeKind.STEM_OTHER,
            -> abs(value - 1f) <= 0.02f
        }
    }

    /** Evaluates [lane] at mix time [tSec] mirroring native `bitchord::loopmix::Envelope::at`. */
    fun evaluateLaneAt(lane: EnvelopeLane, tSec: Double): Float {
        val ts = lane.timesSec
        val vs = lane.values
        if (ts.isEmpty()) return lane.kind.neutralValue
        if (tSec <= ts.first()) return vs.first()
        if (tSec >= ts.last()) return vs.last()
        var lo = 0
        var hi = ts.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] <= tSec) lo = mid else hi = mid
        }
        val dt = ts[hi] - ts[lo]
        if (dt <= 1e-12) return vs[hi]
        val u = ((tSec - ts[lo]) / dt).coerceIn(0.0, 1.0)
        val v0 = vs[lo]
        val v1 = vs[hi]
        return if (lane.exponential && v0 > 0f && v1 > 0f) {
            (v0.toDouble() * exp(ln(v1.toDouble() / v0.toDouble()) * u)).toFloat()
        } else {
            v0 + (v1 - v0) * u.toFloat()
        }
    }

    /** Estimates effective linear low-end energy (0..1+) for a deck at [tSec]. */
    fun lowLevelAt(
        lanes: Map<EnvelopeKind, EnvelopeLane>,
        useBassStem: Boolean,
        tSec: Double,
    ): Float {
        val gain = lanes[EnvelopeKind.GAIN]?.let { evaluateLaneAt(it, tSec) } ?: 1f
        if (gain <= 0.01f) return 0f
        val lowEq = lanes[EnvelopeKind.LOW_DB]?.let { dbToLinear(evaluateLaneAt(it, tSec)) } ?: 1f
        val lowGain = if (useBassStem) {
            val stemBass = lanes[EnvelopeKind.STEM_BASS]?.let { evaluateLaneAt(it, tSec) } ?: 1f
            stemBass.coerceIn(0f, 1.5f) * lowEq.coerceIn(0f, 1.5f)
        } else {
            lowEq.coerceIn(0f, 1.5f)
        }
        return gain.coerceIn(0f, 1.5f) * lowGain
    }

    /** Counts enabled [ScriptFxParams] slots counted against `DjLevel.maxFx`. */
    fun activeFxCount(fx: ScriptFxParams): Int {
        var count = 0
        if (fx.echo?.enabled == true) count++
        if (fx.reverb?.enabled == true) count++
        if (fx.flanger?.enabled == true) count++
        if (fx.phaser?.enabled == true) count++
        if (fx.gate?.enabled == true) count++
        if (fx.pump?.enabled == true) count++
        if (fx.brake?.enabled == true || fx.tapeStop?.enabled == true || fx.spinback?.enabled == true) count++
        if (fx.riser?.enabled == true || fx.impact?.enabled == true) count++
        return count
    }

    /**
     * Validates [script] against [plan] and returns null when valid, or an explanatory
     * error string pinpointing the first violated invariant.
     */
    fun check(
        script: TransitionScriptV1,
        plan: TransitionPlanV1,
        swapsBass: Boolean = !plan.recipeId.equals("S5", ignoreCase = true),
        requireStemsOnStemLanes: Boolean = true,
    ): String? {
        if (script.schema != 1) return "TransitionScriptV1 schema must be 1, got ${script.schema}"
        if (plan.overlapDurationMs <= 0L) return "overlapDurationMs must be positive"
        if (plan.barDurationMs <= 0L) return "barDurationMs must be positive"
        if (!script.outRegion.levelGain.isFinite() || script.outRegion.levelGain <= 0f) {
            return "Invalid outRegion levelGain=${script.outRegion.levelGain}"
        }
        if (!script.inRegion.levelGain.isFinite() || script.inRegion.levelGain <= 0f) {
            return "Invalid inRegion levelGain=${script.inRegion.levelGain}"
        }
        if (!script.loudness.targetLufs.isFinite() ||
            !script.loudness.truePeakCeilingDbtp.isFinite() ||
            !script.loudness.dropBumpLu.isFinite()
        ) {
            return "Non-finite loudness settings"
        }

        checkKnots(script)?.let { return it }
        checkLoopBounds(script)?.let { return it }
        checkFxParams(script.fxParams, plan)?.let { return it }

        val releaseSec = plan.overlapDurationMs / 1000.0
        val outStopSec = (script.outRegion.stopAtMixMs ?: plan.overlapDurationMs) / 1000.0
        if (outStopSec < releaseSec - 1e-6) {
            return "OUT stopAtMix (${outStopSec}s) precedes release (${releaseSec}s)"
        }

        val seenKeys = HashSet<Pair<DeckTarget, EnvelopeKind>>(script.envelopes.size)
        val outLanes = LinkedHashMap<EnvelopeKind, EnvelopeLane>()
        val inLanes = LinkedHashMap<EnvelopeKind, EnvelopeLane>()
        for (lane in script.envelopes) {
            if (!seenKeys.add(lane.deck to lane.kind)) {
                return "Duplicate envelope lane (${lane.deck}, ${lane.kind})"
            }
            checkLaneShape(lane, requireStemsOnStemLanes, script)?.let { return it }
            if (lane.deck == DeckTarget.OUT) outLanes[lane.kind] = lane else inLanes[lane.kind] = lane
        }

        val outGain = outLanes[EnvelopeKind.GAIN] ?: return "Missing OUT GAIN envelope lane"
        val inGain = inLanes[EnvelopeKind.GAIN] ?: return "Missing IN GAIN envelope lane"

        checkBoundaryNeutrality(outLanes, inLanes, outGain, inGain, releaseSec, outStopSec)?.let {
            return it
        }

        if (swapsBass) {
            checkBassClash(outLanes, inLanes, releaseSec, plan.barDurationMs / 4000.0)?.let {
                return it
            }
        }
        return null
    }

    /** Wraps [check] into a typed [MixOutcome]. */
    fun validate(
        script: TransitionScriptV1,
        plan: TransitionPlanV1,
        swapsBass: Boolean = !plan.recipeId.equals("S5", ignoreCase = true),
        requireStemsOnStemLanes: Boolean = true,
    ): MixOutcome<TransitionScriptV1> {
        val err = check(script, plan, swapsBass, requireStemsOnStemLanes)
            ?: return MixOutcome.Ok(script)
        val reason = if (err.startsWith("Bass clash")) RefusalReason.BASS_CLASH else RefusalReason.CHOREOGRAPHY_UNAVAILABLE
        return MixOutcome.Refused(reason, err)
    }

    private fun checkKnots(script: TransitionScriptV1): String? {
        for (i in script.knots.indices) {
            val k = script.knots[i]
            if (!k.bpm.isFinite() || k.bpm < 0.0) return "Non-finite knot bpm=${k.bpm} at index $i"
            if (k.exactMixSec?.isFinite() == false || k.exactTrackPosSec?.isFinite() == false) {
                return "Non-finite exact knot timestamp at index $i"
            }
            if (i > 0) {
                val prev = script.knots[i - 1]
                if (k.mixTimeMs <= prev.mixTimeMs) {
                    return "Tempo knots must have strictly increasing mixTimeMs (${prev.mixTimeMs} -> ${k.mixTimeMs})"
                }
                if (k.trackPosMs < prev.trackPosMs) {
                    return "Tempo knots trackPosMs regressed (${prev.trackPosMs} -> ${k.trackPosMs})"
                }
            }
        }
        return null
    }

    private fun checkLoopBounds(script: TransitionScriptV1): String? {
        val lb = script.loopBounds ?: return null
        val outS = lb.outLoopStartMs
        val outE = lb.outLoopEndMs
        if (outS != null && outE != null && outE <= outS) {
            return "Invalid out loop bounds: $outS..$outE"
        }
        val inS = lb.inLoopStartMs
        val inE = lb.inLoopEndMs
        if (inS != null && inE != null && inE <= inS) {
            return "Invalid in loop bounds: $inS..$inE"
        }
        return null
    }

    private fun checkLaneShape(
        lane: EnvelopeLane,
        requireStemsOnStemLanes: Boolean,
        script: TransitionScriptV1,
    ): String? {
        val ts = lane.timesSec
        val vs = lane.values
        for (i in ts.indices) {
            if (!ts[i].isFinite()) return "Non-finite time in ${lane.deck} ${lane.kind} at index $i"
            if (!vs[i].isFinite()) return "Non-finite value in ${lane.deck} ${lane.kind} at index $i"
            if (lane.kind == EnvelopeKind.FILTER_RES && vs[i] > 6.0f) {
                return "Filter resonance exceeds safe cap (6.0): ${vs[i]} on ${lane.deck}"
            }
            if (i > 0 && ts[i] <= ts[i - 1]) {
                return "Envelope times must be strictly increasing on ${lane.deck} ${lane.kind}: ${ts[i - 1]} -> ${ts[i]}"
            }
        }
        if (requireStemsOnStemLanes && lane.kind.isStem) {
            val stemKind = stemKindFor(lane.kind)
            if (stemKind == null || script.stems.none { it.deck == lane.deck && it.stem == stemKind }) {
                return "Stem lane ${lane.deck} ${lane.kind} lacks attached StemRegionRef"
            }
        }
        return null
    }

    private fun checkBoundaryNeutrality(
        outLanes: Map<EnvelopeKind, EnvelopeLane>,
        inLanes: Map<EnvelopeKind, EnvelopeLane>,
        outGain: EnvelopeLane,
        inGain: EnvelopeLane,
        releaseSec: Double,
        outStopSec: Double,
    ): String? {
        for ((kind, lane) in outLanes) {
            val atZero = evaluateLaneAt(lane, 0.0)
            if (!isNeutral(kind, atZero)) {
                return "OUT $kind not neutral at splice t=0.0 (got $atZero, expected ${kind.neutralValue})"
            }
            if (kind == EnvelopeKind.BRAKE && lane.values.any { it < -1e-4f || it > 1.02f }) {
                return "OUT BRAKE progress out of [0,1] bounds"
            }
        }

        val outAtRelease = evaluateLaneAt(outGain, releaseSec)
        if (outAtRelease > 1e-3f || outGain.values.last() > 1e-3f) {
            return "OUT GAIN does not cut to zero by release (atRelease=$outAtRelease, last=${outGain.values.last()})"
        }
        if (outGain.timesSec.last() > outStopSec + 1e-6) {
            return "OUT GAIN extends past outStopSec (${outGain.timesSec.last()} > $outStopSec)"
        }

        for (wetKind in WET_SEND_KINDS) {
            val lane = outLanes[wetKind] ?: continue
            val atStop = evaluateLaneAt(lane, outStopSec)
            if (atStop > 0.02f || lane.values.last() > 0.02f) {
                return "OUT $wetKind tail not retired by outStop=${outStopSec}s (atStop=$atStop, last=${lane.values.last()})"
            }
            if (lane.timesSec.last() > outStopSec + 1e-6) {
                return "OUT $wetKind extends past outStop=${outStopSec}s"
            }
        }

        val inAtStart = evaluateLaneAt(inGain, 0.0)
        if (inAtStart > 1e-3f) {
            return "IN GAIN not muted at splice t=0.0 (got $inAtStart)"
        }

        for ((kind, lane) in inLanes) {
            val atRelease = evaluateLaneAt(lane, releaseSec)
            val lastVal = lane.values.last()
            if (!isNeutral(kind, atRelease) || !isNeutral(kind, lastVal)) {
                return "IN $kind not neutral at handover release=${releaseSec}s (atRelease=$atRelease, last=$lastVal)"
            }
            if (lane.timesSec.last() > releaseSec + 1e-6) {
                return "IN $kind extends past release=${releaseSec}s (${lane.timesSec.last()})"
            }
        }
        return null
    }

    private fun checkBassClash(
        outLanes: Map<EnvelopeKind, EnvelopeLane>,
        inLanes: Map<EnvelopeKind, EnvelopeLane>,
        releaseSec: Double,
        rawBeatSec: Double,
    ): String? {
        val useOutStem = outLanes.containsKey(EnvelopeKind.STEM_BASS)
        val useInStem = inLanes.containsKey(EnvelopeKind.STEM_BASS)
        val beatLimit = max(0.1, rawBeatSec) + BASS_STEP_SEC
        var dualPresentSec = 0.0
        var t = 0.0
        while (t <= releaseSec + 1e-6) {
            val lo = lowLevelAt(outLanes, useOutStem, t)
            val li = lowLevelAt(inLanes, useInStem, t)
            if (lo >= BASS_FULL_LINEAR && li >= BASS_FULL_LINEAR) {
                return "Bass clash: simultaneous full low end at ${t}s (out=$lo, in=$li)"
            }
            if (lo > BASS_PRESENT_LINEAR && li > BASS_PRESENT_LINEAR) {
                dualPresentSec += BASS_STEP_SEC
                if (dualPresentSec > beatLimit) {
                    return "Bass clash: dual low end > -6 dB lasted ${dualPresentSec}s (> 1 beat limit ${beatLimit}s)"
                }
            } else {
                dualPresentSec = 0.0
            }
            t += BASS_STEP_SEC
        }
        return null
    }

    private fun checkFxParams(fx: ScriptFxParams, plan: TransitionPlanV1): String? {
        val fxCount = activeFxCount(fx)
        if (fxCount > plan.djLevel.maxFx) {
            return "Active FX count $fxCount exceeds ${plan.djLevel} maxFx=${plan.djLevel.maxFx}"
        }
        fx.echo?.takeIf { it.enabled }?.let { e ->
            if (!e.wet.isFinite() || e.wet !in 0f..1.25f) return "Echo wet out of range: ${e.wet}"
            if (!e.feedback.isFinite() || e.feedback !in 0f..0.98f) return "Echo feedback out of range: ${e.feedback}"
            if (!e.delayBeats.isFinite() || !e.delaySec.isFinite()) return "Non-finite echo delay"
        }
        fx.reverb?.takeIf { it.enabled }?.let { r ->
            if (!r.wet.isFinite() || r.wet !in 0f..1.25f) return "Reverb wet out of range: ${r.wet}"
            if (!r.roomSize.isFinite() || !r.damping.isFinite()) return "Non-finite reverb parameter"
        }
        fx.flanger?.takeIf { it.enabled }?.let { f ->
            if (!f.wetPeak.isFinite() || f.wetPeak !in 0f..1.25f) return "Flanger wetPeak out of range: ${f.wetPeak}"
            if (!f.feedback.isFinite() || f.feedback !in 0f..0.98f) return "Flanger feedback out of range: ${f.feedback}"
        }
        fx.phaser?.takeIf { it.enabled }?.let { p ->
            if (!p.wetPeak.isFinite() || p.wetPeak !in 0f..1.25f) return "Phaser wetPeak out of range: ${p.wetPeak}"
            if (!p.depth.isFinite() || p.depth !in 0f..1.25f) return "Phaser depth out of range: ${p.depth}"
            if (!p.feedback.isFinite() || p.feedback !in 0f..0.98f) return "Phaser feedback out of range: ${p.feedback}"
        }
        fx.gate?.takeIf { it.enabled }?.let { g ->
            if (!g.depthPeak.isFinite() || g.depthPeak !in 0f..1.05f) return "Gate depth out of range: ${g.depthPeak}"
        }
        fx.riser?.takeIf { it.enabled }?.let { r ->
            if (!r.noiseLevelPeak.isFinite() || r.noiseLevelPeak !in 0f..1.5f) return "Riser level out of range: ${r.noiseLevelPeak}"
        }
        fx.brake?.takeIf { it.enabled }?.let { b ->
            if (!b.lengthSec.isFinite() || b.lengthSec <= 0.0) return "Invalid brake lengthSec=${b.lengthSec}"
        }
        fx.spinback?.takeIf { it.enabled }?.let { s ->
            if (!s.lengthSec.isFinite() || s.lengthSec <= 0.0) return "Invalid spinback lengthSec=${s.lengthSec}"
        }
        return null
    }

    private fun dbToLinear(db: Float): Float =
        if (!db.isFinite() || db <= -48f) 0f else 10.0.pow(db / 20.0).toFloat()

    private val WET_SEND_KINDS: List<EnvelopeKind> = listOf(
        EnvelopeKind.ECHO_SEND,
        EnvelopeKind.REVERB_SEND,
        EnvelopeKind.FLANGER_WET,
        EnvelopeKind.PHASER_WET,
        EnvelopeKind.GATE_DEPTH,
        EnvelopeKind.RISER_LEVEL,
        EnvelopeKind.PUMP_DEPTH,
    )
}
