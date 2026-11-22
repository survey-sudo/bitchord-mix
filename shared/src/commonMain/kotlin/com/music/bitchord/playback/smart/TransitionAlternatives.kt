package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Muffle, drop-in, and beatless-bridge transition families (vc69 P0).
 * Ported from Orchard pairTransitionGates.js, muffle.js, beatlessBridge.js,
 * and transitionArrangement.js: when a pair would currently be declined or
 * produce a weak blend, offer one of these instead of falling back to raw
 * overlap or silence. Every family requires the bass curve evidence the
 * planner already stores: pairs with no measured curves behave exactly as
 * they did before vc69, so decline behavior never moves without evidence.
 *
 * Family gates mirror Orchard where BitChord has the same scalars, and
 * diverge deliberately where BitChord declines on tempo distance rather
 * than on needing a filter: a muffle here covers the 8..10 percent band
 * Orchard would exclude on stretch, because the plan keeps both decks at
 * native tempo and lets the low-pass cover do the denial. Cadence and span
 * numbers below are Orchard constants unless marked BitChord.
 */
object TransitionAlternatives {

    /** One fitted alternative: the plan plus the gates and ranking behind it. */
    data class Candidate(
        val plan: TransitionPlan,
        val kind: String,
        val confidence: Double,
        val rankScore: Double,
        val gates: Map<String, String>,
        val flow: BeatFlow.FlowResult,
    )

    // -- muffle.js constants --
    const val MUFFLE_LEAD_BEATS = 8
    const val MUFFLE_MIN_LEAD_BEATS = 4
    const val MUFFLE_EXIT_BEATS = 2
    const val MUFFLE_MUTED_FRACTION = 0.75
    const val MUFFLE_MAX_ENERGY_DROP_DB = 5.0
    const val MUFFLE_MAX_SKIP_SECONDS = 40.0
    const val MUFFLE_MIN_OPENING_SECONDS = 2.0
    const val MUFFLE_MIN_BODY = 0.3
    const val MUFFLE_SWAP_IN_VOCAL = 0.7
    const val MUFFLE_SWAP_OUT_VOCAL = 0.5
    /** BitChord: declined pairs span 8..10 percent, inside the slowdown stretch window. */
    const val MUFFLE_MAX_DELTA = 0.10

    // -- transitionArrangement.js drop-in --
    const val DROP_IN_OVERLAP_BEATS = 8.0
    const val DROP_IN_OUT_SPAN = 0.5
    const val DROP_IN_ECHO_TAIL_SECONDS = 0.75
    const val DROP_IN_MIN_ENERGY_RATIO = 0.5

    // -- beatlessBridge.js constants --
    const val BRIDGE_BEATLESS_PRESENCE = 0.3
    const val BRIDGE_STEP = 0.25
    const val BRIDGE_MIN_SECONDS = 4.0
    const val BRIDGE_MAX_SECONDS = 12.0
    const val BRIDGE_MAX_SKIP_SECONDS = 16.0
    const val BRIDGE_VOICE_MEAN = 0.25
    const val BRIDGE_IN_VOICE_RISK = 0.5
    const val BRIDGE_BASE_CONFIDENCE = 0.62
    /** Return-leg beats for the bridge park: the 1500 Hz ambient hold leaves over this trailing span. */
    const val BRIDGE_EXIT_BEATS = 4
    /** Full-band cutoff: every return leg ends here. Mirrors the DSP open point. */
    const val FULL_BAND_HZ = 20000.0
    /** Muffle park floor. Mirrors the mixer muffle floor. */
    const val MUFFLE_FLOOR_HZ_SHARED = 700.0
    /** Bridge park cutoff. Mirrors the mixer bridge park. */
    const val BRIDGE_PARK_HZ_SHARED = 1500.0

    /** Trusted harmonic reading, as pairTransitionGates.js uses it. */
    const val TRUSTED_HARMONIC_CONFIDENCE = 0.65
    /** Dual-vocal probability threshold (TransitionPlanner private, mirrored here). */
    const val DUAL_VOCAL = 0.62

    // -- shared helpers --

    fun beatSecOf(analysis: TrackAnalysis): Double {
        val interval = analysis.beatInterval
        if (interval.isFinite() && interval > 0.05) return interval
        val bpm = analysis.bpm
        return if (bpm.isFinite() && bpm > 1.0) 60.0 / bpm else 0.0
    }

    fun incomingCueOf(analysis: TrackAnalysis): Double {
        val legacy = listOfNotNull(analysis.audibleStartTime, analysis.pickupTime, analysis.firstBeat.takeIf { it > 0.0 })
            .firstOrNull { it.isFinite() && it >= 0.0 } ?: 0.0
        val curveAudible = LoopMix.firstAudibleTime(analysis)
        val explicit = analysis.audibleStartTime?.takeIf { it.isFinite() && it > 0.05 }
        val floor = maxOf(explicit ?: 0.0, curveAudible)
        return maxOf(legacy, floor)
    }

    fun foldedDelta(out: TrackAnalysis, inn: TrackAnalysis): Double {
        val outBpm = out.bpm
        val inBpm = inn.bpm
        if (outBpm <= 0.0 || inBpm <= 0.0) return Double.POSITIVE_INFINITY
        val folded = alignTempoOctave(outBpm, inBpm)
        return abs(folded / outBpm - 1.0)
    }

    fun dualVocal(out: TrackAnalysis, inn: TrackAnalysis): Boolean =
        out.vocalProbability >= DUAL_VOCAL && inn.vocalProbability >= DUAL_VOCAL

    fun harmonicSteps(out: TrackAnalysis, inn: TrackAnalysis): Int? = regionalCamelotSteps(out, inn)

    fun harmonicSevereClash(out: TrackAnalysis, inn: TrackAnalysis): Boolean {
        val steps = harmonicSteps(out, inn) ?: return false
        if (steps <= 2) return false
        val leftConf = exitKeyConfidence(out)
        val rightConf = entryKeyConfidence(inn)
        return leftConf >= TRUSTED_HARMONIC_CONFIDENCE && rightConf >= TRUSTED_HARMONIC_CONFIDENCE
    }

    /** Bass level divided by its own reference, so two masters compare in dB honestly. */
    fun bassRatio(analysis: TrackAnalysis, from: Double, until: Double): Double? {
        val reference = BeatFlow.bassReference(analysis) ?: return null
        val level = BeatFlow.meanCurve(analysis.lowEnergyCurve, from, until) ?: return null
        return level / reference
    }

    fun bassMeans(analysis: TrackAnalysis, from: Double, until: Double): Boolean =
        bassRatio(analysis, from, until) != null

    /** Simultaneous vocal fraction, or the dual-vocal scalar when unmeasured. */
    fun simultaneous(out: TrackAnalysis, inn: TrackAnalysis, outStart: Double, outEnd: Double, inStart: Double): Double? =
        simultaneousVocalFraction(outgoing = out, incoming = inn, outStart = outStart, outEnd = outEnd, inStart = inStart, rate = 1.0)

    fun voiceMean(analysis: TrackAnalysis, from: Double, until: Double): Double? =
        vocalActivityBetween(analysis, from, until)

    /** Seconds of unbroken beatless audio from from, walking by step (negative walks back). */
    fun beatlessRun(analysis: TrackAnalysis, from: Double, limit: Double, step: Double): Double {
        if (analysis.lowEnergyCurve.isEmpty() || !from.isFinite() || !limit.isFinite() || step == 0.0) return 0.0
        var run = 0.0
        var time = from
        val cap = 40.0
        while (if (step > 0.0) time < limit else time > limit) {
            if (run > cap) break
            val presence = BeatFlow.beatPresenceAt(analysis, time) ?: break
            if (presence >= BRIDGE_BEATLESS_PRESENCE) break
            run += abs(step)
            time += step
        }
        return run
    }

    /** Kick-locked incoming anchor: first downbeat at or after the first beat, snapped to 1/8 beat. */
    fun incomingAnchor(inn: TrackAnalysis, beatSec: Double): Double {
        val cue = incomingCueOf(inn)
        val downbeats = inn.downbeats.filter { it.isFinite() }
        val first = inn.firstBeat.takeIf { it > 0.0 } ?: cue
        val anchor = downbeats.firstOrNull { it >= first - 1e-6 } ?: cue
        if (beatSec <= 0.0 || !beatSec.isFinite()) return anchor
        val origin = first.takeIf { first.isFinite() } ?: anchor
        return KickLock.snapToKickLock(anchor, beatSec, downbeats, origin)
    }

    private fun overlapBeats(beatSec: Double, want: Int, minimum: Int): Int {
        if (beatSec <= 0.0 || !beatSec.isFinite()) return 0
        val capped = minOf(want, maxOf(minimum, floor(12.0 / beatSec).toInt()))
        return if (capped < minimum) 0 else capped
    }

    // -- muffle --

    /**
     * Muffle gates, shared by the TransitionPlan path and LoopMix. Returns
     * the verdict map plus the lead beats on fit, null when nothing fits.
     */
    fun muffleGates(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outAnchor: Double,
        beatSec: Double,
    ): Pair<Map<String, String>, Int>? {
        val gates = mutableMapOf<String, String>()
        if (out.lowEnergyCurve.isEmpty() || inn.lowEnergyCurve.isEmpty() || beatSec <= 0.0) return null
        if (out.beatConfidence < MIN_BEATMATCH_CONFIDENCE || inn.beatConfidence < MIN_BEATMATCH_CONFIDENCE) {
            gates["beat-confidence"] = "below " + MIN_BEATMATCH_CONFIDENCE
            return null
        }
        val delta = foldedDelta(out, inn)
        if (delta > MUFFLE_MAX_DELTA) {
            gates["tempo-distance"] = "delta " + delta.toString()
            return null
        }
        val leadBeats = overlapBeats(beatSec, MUFFLE_LEAD_BEATS, MUFFLE_MIN_LEAD_BEATS)
        if (leadBeats == 0) return null
        val overlapSec = leadBeats * beatSec
        val closeFrom = outAnchor - overlapSec
        if (closeFrom < 0.0) return null
        val swapFraction = (leadBeats - MUFFLE_EXIT_BEATS).toDouble() / leadBeats.toDouble()
        val swapSrc = closeFrom + swapFraction * overlapSec
        val cue = incomingCueOf(inn)
        val simult = simultaneous(out, inn, closeFrom, outAnchor, cue)
        val needsFilter = dualVocal(out, inn) || (simult ?: 0.0) > BRIDGE_VOICE_MEAN
        if (!needsFilter) {
            gates["needs-filter"] = "no collision to hide"
            return null
        }
        // Orchard compares absolute levels; BitChord raw bass shares one analyzer scale.
        val beforeRaw = BeatFlow.meanCurve(out.lowEnergyCurve, outAnchor - 8.0, outAnchor)
        val afterRaw = BeatFlow.meanCurve(inn.lowEnergyCurve, cue, cue + 8.0)
        if (beforeRaw == null || afterRaw == null || beforeRaw <= 0.0 || afterRaw <= 0.0) {
            gates["energy-drop"] = "unmeasured"
            return null
        }
        val dropDb = 20.0 * log10(beforeRaw / afterRaw)
        if (dropDb > MUFFLE_MAX_ENERGY_DROP_DB) {
            gates["energy-drop"] = "drop " + dropDb.toString()
            return null
        }
        val startRegion = inn.audibleStartTime ?: 0.0
        val heard = cue
        if (heard - startRegion < MUFFLE_MIN_OPENING_SECONDS - 1e-6) {
            gates["opening"] = "buried"
            return null
        }
        val closeSpan = (outAnchor - closeFrom) * MUFFLE_MUTED_FRACTION
        val body = bassRatio(out, maxOf(closeFrom, outAnchor - closeSpan), outAnchor)
        if (body != null && body < MUFFLE_MIN_BODY) {
            gates["min-body"] = "thin " + body.toString()
            return null
        }
        val leaving = voiceMean(out, closeFrom - beatSec, closeFrom) ?: 0.0
        val arriving = voiceMean(inn, heard, heard + (swapSrc - closeFrom)) ?: 0.0
        if (leaving >= MUFFLE_SWAP_OUT_VOCAL && arriving >= MUFFLE_SWAP_IN_VOCAL) {
            gates["swap-voice"] = "trades singers"
            return null
        }
        gates["muffle"] = "fits"
        return gates to leadBeats
    }

    fun muffleFits(out: TrackAnalysis, inn: TrackAnalysis, beatSec: Double, outAnchor: Double): Boolean =
        muffleGates(out, inn, outAnchor, beatSec) != null

    private fun muffleMatchedRate(out: TrackAnalysis, inn: TrackAnalysis): Double {
        if (out.beatConfidence < MIN_BEATMATCH_CONFIDENCE || inn.beatConfidence < MIN_BEATMATCH_CONFIDENCE) return 1.0
        val rate = incomingRateBetween(out.bpm, inn.bpm)
        return if (rate.isFinite() && rate in AUDIBLE_MIN_RATE..AUDIBLE_MAX_RATE) rate else 1.0
    }

    fun planMuffle(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outAnchor: Double,
        nextLength: Double,
        playbackTime: Double,
        beatSec: Double,
    ): Candidate? {
        val (gates, leadBeats) = muffleGates(out, inn, outAnchor, beatSec) ?: return null
        val overlapSec = leadBeats * beatSec
        val transitionStart = outAnchor - overlapSec
        val swapFraction = (leadBeats - MUFFLE_EXIT_BEATS).toDouble() / leadBeats.toDouble()
        val cue = incomingCueOf(inn)
        val simult = simultaneous(out, inn, transitionStart, outAnchor, cue) ?: 0.0
        val started = playbackTime >= transitionStart
        val plan = TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = outAnchor,
            fadeSeconds = overlapSec,
            transitionStyle = TransitionStyle.MUFFLE,
            incomingCueTime = cue,
            incomingHandoffTime = cue,
            incomingPlaybackRate = muffleMatchedRate(out, inn),
            bassSwap = true,
            bassSwapFraction = swapFraction,
            filterSweep = FILTER_SWEEP,
            beatSeconds = beatSec,
            muffleLeadBeats = leadBeats,
            exitBeats = MUFFLE_EXIT_BEATS,
            vocalOverlap = maxOf(simult, if (dualVocal(out, inn)) 0.85 else 0.0),
            policyReasons = listOf("alternative-muffle"),
            mixSummary = "muffle",
            alternativeKind = "muffle",
            reason = if (started) "smart-muffle" else "before-muffle",
        )
        val flow = BeatFlow.beatFlow(out, inn, transitionStart, cue, overlapSec, anchorTime = cue, matched = true)
        return Candidate(plan, "muffle", 0.72, BeatFlow.rankScore(0.72, flow), gates, flow)
    }

    // -- drop-in --

    fun dropInGates(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outAnchor: Double,
        beatSec: Double,
    ): Map<String, String>? {
        val gates = mutableMapOf<String, String>()
        if (out.lowEnergyCurve.isEmpty() || inn.lowEnergyCurve.isEmpty() || beatSec <= 0.0) return null
        if (out.beatConfidence < MIN_BEATMATCH_CONFIDENCE || inn.beatConfidence < MIN_BEATMATCH_CONFIDENCE) {
            gates["beat-confidence"] = "low for a hard cut"
            return null
        }
        val delta = foldedDelta(out, inn)
        if (delta > MUFFLE_MAX_DELTA) {
            gates["tempo-distance"] = "delta " + delta.toString()
            return null
        }
        val overlapBeats = overlapBeats(beatSec, DROP_IN_OVERLAP_BEATS.toInt(), MUFFLE_MIN_LEAD_BEATS)
        if (overlapBeats == 0) return null
        val overlapSec = overlapBeats * beatSec
        val start = outAnchor - overlapSec
        if (start < 0.0) return null
        val cue = incomingAnchor(inn, beatSec)
        val simult = simultaneous(out, inn, start, outAnchor, cue)
        if ((simult ?: 0.0) > BRIDGE_VOICE_MEAN) {
            gates["vocal-collision"] = "two voices on the cut"
            return null
        }
        val entryRatio = bassRatio(inn, cue, cue + 2.0 * beatSec) ?: 0.0
        if (entryRatio < DROP_IN_MIN_ENERGY_RATIO) {
            gates["entry-energy"] = "entry " + entryRatio.toString()
            return null
        }
        gates["drop-in"] = "fits"
        return gates
    }

    fun dropInFits(out: TrackAnalysis, inn: TrackAnalysis, beatSec: Double, outAnchor: Double): Boolean =
        dropInGates(out, inn, outAnchor, beatSec) != null

    fun planDropIn(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outAnchor: Double,
        nextLength: Double,
        playbackTime: Double,
        beatSec: Double,
    ): Candidate? {
        val gates = dropInGates(out, inn, outAnchor, beatSec) ?: return null
        val overlapBeats = overlapBeats(beatSec, DROP_IN_OVERLAP_BEATS.toInt(), MUFFLE_MIN_LEAD_BEATS)
        val overlapSec = overlapBeats * beatSec
        val transitionStart = outAnchor - overlapSec
        val cue = incomingAnchor(inn, beatSec)
        val simult = simultaneous(out, inn, transitionStart, outAnchor, cue) ?: 0.0
        val started = playbackTime >= transitionStart
        val plan = TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = outAnchor,
            fadeSeconds = overlapSec,
            transitionStyle = TransitionStyle.DROP_IN,
            incomingCueTime = cue,
            incomingHandoffTime = cue,
            incomingPlaybackRate = muffleMatchedRate(out, inn),
            bassSwap = false,
            filterSweep = 0.0,
            beatSeconds = beatSec,
            echoSeconds = DROP_IN_ECHO_TAIL_SECONDS,
            vocalOverlap = simult,
            policyReasons = listOf("alternative-drop-in"),
            mixSummary = "drop-in",
            alternativeKind = "drop-in",
            reason = if (started) "smart-drop-in" else "before-drop-in",
        )
        val flow = BeatFlow.beatFlow(out, inn, transitionStart, cue, overlapSec, anchorTime = cue, matched = true)
        return Candidate(plan, "drop-in", 0.66, BeatFlow.rankScore(0.66, flow), gates, flow)
    }

    // -- beatless bridge --

    fun beatlessBridgeGates(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outAnchor: Double,
    ): Map<String, String>? {
        val gates = mutableMapOf<String, String>()
        if (out.lowEnergyCurve.isEmpty() || inn.lowEnergyCurve.isEmpty()) return null
        // Severe harmonic clash promotes the bridge rather than vetoing it:
        // the bridge parks both decks so no two keys ever sound together,
        // which is exactly the tritone case you cannot beatmatch through.
        // Recorded as an informative gate, not a refusal; the physical
        // preconditions below (bass curves, beatless edges) are what can
        // still refuse it.
        if (harmonicSevereClash(out, inn)) {
            gates["harmonic-clash"] = "severe clash prefers the bridge"
        }
        val start = maxOf((inn.audibleStartTime ?: inn.firstBeat), LoopMix.firstAudibleTime(inn))
        if (!(start >= 0.0)) return null
        val inRun = beatlessRun(inn, start + BRIDGE_STEP / 2.0, inn.duration, BRIDGE_STEP)
        val outRun = beatlessRun(out, outAnchor - BRIDGE_STEP / 2.0, maxOf(0.0, outAnchor - BRIDGE_MAX_SECONDS), -BRIDGE_STEP)
        if (minOf(outRun, inRun) < BRIDGE_MIN_SECONDS) {
            gates["no-beatless-edge"] = "outro " + outRun.toString() + " intro " + inRun.toString()
            return null
        }
        gates["beatless-bridge"] = "fits"
        return gates
    }

    fun beatlessBridgeFits(out: TrackAnalysis, inn: TrackAnalysis, outAnchor: Double): Boolean =
        beatlessBridgeGates(out, inn, outAnchor) != null

    fun planBeatlessBridge(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outAnchor: Double,
        nextLength: Double,
        playbackTime: Double,
        beatSec: Double,
    ): Candidate? {
        val gates = beatlessBridgeGates(out, inn, outAnchor)?.toMutableMap() ?: return null
        if (beatSec <= 0.0) return null
        val start = maxOf((inn.audibleStartTime ?: inn.firstBeat), LoopMix.firstAudibleTime(inn))
        val inRun = beatlessRun(inn, start + BRIDGE_STEP / 2.0, inn.duration, BRIDGE_STEP)
        val outRun = beatlessRun(out, outAnchor - BRIDGE_STEP / 2.0, maxOf(0.0, outAnchor - BRIDGE_MAX_SECONDS), -BRIDGE_STEP)
        val duration = BeatFlow.wholeBeats(minOf(outRun, inRun, BRIDGE_MAX_SECONDS), beatSec, BRIDGE_MIN_SECONDS)
        if (duration < BRIDGE_MIN_SECONDS) return null
        val skip = minOf(maxOf(0.0, inRun - duration), BRIDGE_MAX_SKIP_SECONDS)
        val transitionStart = outAnchor - duration
        if (transitionStart < 0.0) return null
        val cue = start + skip
        val simult = simultaneous(out, inn, transitionStart, outAnchor, cue)
        if ((simult ?: 0.0) > BRIDGE_VOICE_MEAN) {
            gates["voice-over-voice"] = "voices meet over the bridge"
            return null
        }
        val inVoice = voiceMean(inn, cue, cue + 1.0)
        if ((inVoice ?: 0.0) >= BRIDGE_IN_VOICE_RISK) {
            gates["voice-cut"] = "the new song would fade in mid-line"
            return null
        }
        // Severe clash no longer penalises the bridge confidence: it is the
        // clash that promotes the bridge (see select's precedence). The base
        // rank is unchanged for clash-free pairs.
        val confidence = BRIDGE_BASE_CONFIDENCE + 0.04
        // Return leg owns the trailing span the park leaves over. Clamped to
        // at most half the bridge so the entry close keeps the other half.
        val totalBridgeBeats = (duration / beatSec).roundToInt().coerceAtLeast(2)
        val exitBeats = minOf(BRIDGE_EXIT_BEATS, maxOf(1, totalBridgeBeats / 2))
        val started = playbackTime >= transitionStart
        val plan = TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = outAnchor,
            fadeSeconds = duration,
            transitionStyle = TransitionStyle.BEATLESS_BRIDGE,
            incomingCueTime = cue,
            incomingHandoffTime = cue,
            incomingPlaybackRate = muffleMatchedRate(out, inn),
            bassSwap = false,
            filterSweep = 0.25,
            beatSeconds = beatSec,
            exitBeats = exitBeats,
            vocalHandoff = true,
            vocalOverlap = simult ?: 0.0,
            policyReasons = listOf("alternative-beatless-bridge"),
            mixSummary = "beatless bridge",
            alternativeKind = "beatless-bridge",
            reason = if (started) "smart-beatless-bridge" else "before-beatless-bridge",
        )
        val flow = BeatFlow.beatFlow(out, inn, transitionStart, cue, duration, anchorTime = cue)
        return Candidate(plan, "beatless-bridge", confidence, BeatFlow.rankScore(confidence, flow), gates, flow)
    }

    /**
     * Best-fitting alternative for a pair, ranked by its own confidence
     * blended with its beat-flow score (Orchard weights flow 0.18). Null
     * means no family fits and the bounded decline must play. nextLength is
     * unused on purpose: candidates cue near the incoming first beat and
     * never read the tail, so the incoming length cannot reject them.
     */
    fun select(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outAnchor: Double,
        nextLength: Double,
        playbackTime: Double,
        beatSec: Double,
    ): Candidate? {
        val candidates = FAMILIES.mapNotNull { it.planCandidate(out, inn, outAnchor, nextLength, playbackTime, beatSec) }
        if (candidates.isEmpty()) return null
        // Severe harmonic clash inverts the rescue order: the bridge parks
        // both decks (no two keys ever sound together), the muffle ducks
        // one, and a hard drop-in cut keeps both keys audible through the
        // overlap. Clash never vetoes a family here (gates already decided
        // what can physically run); it only reorders what can. Clash-free
        // pairs keep the rankScore order unchanged.
        if (harmonicSevereClash(out, inn)) {
            return candidates.sortedWith(
                compareBy<Candidate> { severeClashRank(it.kind) }
                    .thenByDescending { it.rankScore }
            ).first()
        }
        return candidates.sortedWith(
            compareByDescending<Candidate> { it.rankScore }
                .thenByDescending { it.confidence }
                .thenBy { priorityOf(it.kind) }
        ).first()
    }

    private fun priorityOf(kind: String): Int = specForKind(kind)?.priority ?: 2

    /** Rescue precedence for severe harmonic clash: park both decks first. */
    private fun severeClashRank(kind: String): Int = when (kind) {
        "beatless-bridge" -> 0
        "muffle" -> 1
        // Drop-in: physically possible, but a hard cut is the weakest answer
        // through a tritone, so it stays the last resort among rescues.
        else -> 2
    }

    // -- family registry (additivity) --
    //
    // One place to register a transition family: its style, rung name,
    // gates, planner and loop-plan mapping live together. A new family adds
    // one [FamilySpec] entry plus its gate/plan functions; [DjMix], the
    // classic rider and the native envelope writer consult this registry
    // instead of listing families inline, so no per-family edit is needed
    // there. Full strategy abstraction (render callbacks across modules)
    // would be more churn than this data registry buys, so the registry
    // owns planning + envelope data while the audio riders keep their
    // mechanics and read the family's parameters from here.

    data class FamilySpec(
        val kind: String,
        val style: TransitionStyle,
        val priority: Int,
        val rung: String,
        val usesDropBase: Boolean = false,
        val fits: (TrackAnalysis, TrackAnalysis, Double, Double) -> Boolean,
        val planCandidate: (TrackAnalysis, TrackAnalysis, Double, Double, Double, Double) -> Candidate?,
        val applyLoop: (LoopMix.Plan) -> LoopMix.Plan,
    )

    val FAMILIES: List<FamilySpec> = listOf(
        FamilySpec(
            kind = "muffle",
            style = TransitionStyle.MUFFLE,
            priority = 0,
            rung = "Muffle",
            fits = { out, inn, anchor, beatSec -> muffleFits(out, inn, beatSec, anchor) },
            planCandidate = { out, inn, anchor, nl, pt, bs -> planMuffle(out, inn, anchor, nl, pt, bs) },
            applyLoop = { it.copy(muffle = true, alternativeKind = "muffle", rung = "Muffle") },
        ),
        FamilySpec(
            kind = "beatless-bridge",
            style = TransitionStyle.BEATLESS_BRIDGE,
            priority = 1,
            rung = "Bridge",
            fits = { out, inn, anchor, _ -> beatlessBridgeFits(out, inn, anchor) },
            planCandidate = { out, inn, anchor, nl, pt, bs -> planBeatlessBridge(out, inn, anchor, nl, pt, bs) },
            applyLoop = { it.copy(beatlessBridge = true, alternativeKind = "beatless-bridge", rung = "Bridge") },
        ),
        FamilySpec(
            kind = "drop-in",
            style = TransitionStyle.DROP_IN,
            priority = 2,
            rung = "Drop",
            usesDropBase = true,
            fits = { out, inn, anchor, beatSec -> dropInFits(out, inn, beatSec, anchor) },
            planCandidate = { out, inn, anchor, nl, pt, bs -> planDropIn(out, inn, anchor, nl, pt, bs) },
            applyLoop = { it.copy(dropIn = true, alternativeKind = "drop-in", rung = "Drop") },
        ),
    )

    fun isAlternativeStyle(style: TransitionStyle): Boolean =
        FAMILIES.any { it.style == style }

    fun isAlternativeKind(kind: String): Boolean =
        FAMILIES.any { it.kind == kind }

    fun specForKind(kind: String): FamilySpec? = FAMILIES.firstOrNull { it.kind == kind }

    fun specForStyle(style: TransitionStyle): FamilySpec? = FAMILIES.firstOrNull { it.style == style }

    // -- two-phase render math (pure, shared so it is testable) --
    //
    // Geometric glide shared by every ride. [amount] 0 gives [from], 1 gives
    // [to]. Geometric because pitch is logarithmic.
    fun glideHz(from: Double, to: Double, amount: Double): Double =
        from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    /**
     * C1 raised-cosine weight s(u) = 0.5*(1-cos(pi*u)) for u in 0..1: 0 at
     * both ends, 1 in the middle transition, derivative zero at both ends.
     * Every LP leg samples this same shape in the log-Hz domain, so the
     * slope is continuous (flat) at the turnaround and at full-band on
     * either side: no p^1.4-to-linear kink.
     */
    fun raisedCosine(u: Double): Double =
        0.5 * (1.0 - cos(PI * u.coerceIn(0.0, 1.0)))

    /**
     * C1 log-Hz glide between [from] and [to] with raised-cosine weight:
     * geometric interpolation whose log slope is zero at both ends.
     * Use for both close and reopen legs so the turnaround stays flat.
     */
    fun c1LogGlideHz(from: Double, to: Double, u: Double): Double =
        glideHz(from, to, raisedCosine(u))

    /**
     * Proportional exit leg for a [beats]-beat transition:
     * max(3, round(beats*0.4)) clamped to [2, beats-1] (1 for a 2-beat
     * clock). Replaces the fixed 2-beat MUFFLE_EXIT_BEATS reopen so a
     * long transition reopens proportionally instead of snatching.
     */
    fun proportionalExitBeats(beats: Int): Int {
        if (beats <= 2) return 1
        val raw = maxOf(3, (beats * 0.4).roundToInt())
        return raw.coerceIn(2, beats - 1)
    }

    /** Entry fraction implied by the proportional exit leg (for live riders). */
    fun proportionalEntryFraction(beats: Int): Double {
        val b = beats.coerceAtLeast(2)
        val exit = proportionalExitBeats(b)
        return ((b - exit).toDouble() / b.toDouble()).coerceIn(0.05, 0.95)
    }

    /** Incoming LP reopen start (Hz): 500-700 Hz, never the old 300 Hz snatch base. */
    const val IN_LP_START_HZ = 600.0
    /** Incoming LP open span in beats (2.5-4.0): 3 beats keeps the open gradual. */
    const val IN_LP_OPEN_BEATS = 3.0
    /** Perceptual makeup ceiling for a surviving filtered deck (dB). */
    const val SURVIVOR_MAKEUP_MAX_DB = 1.5

    /**
     * Gentle perceptual makeup for a SURVIVING filtered deck only (never a
     * dying OUT deck): +0 dB at full-band, up to [SURVIVOR_MAKEUP_MAX_DB]
     * at [floorHz], linear in log closure depth. The controller lane
     * applies this to the survivor's gain while its LP is closed; the
     * dying deck gets no makeup so it cannot blast as it fades.
     */
    fun survivorMakeupDb(
        cutoffHz: Double,
        openHz: Double = FULL_BAND_HZ,
        floorHz: Double = MUFFLE_FLOOR_HZ_SHARED,
    ): Double {
        if (!cutoffHz.isFinite() || cutoffHz <= 0.0) return SURVIVOR_MAKEUP_MAX_DB
        if (cutoffHz >= openHz) return 0.0
        if (cutoffHz <= floorHz) return SURVIVOR_MAKEUP_MAX_DB
        val num = log10(openHz / cutoffHz)
        val den = log10(openHz / floorHz)
        if (!den.isFinite() || den <= 0.0) return 0.0
        return (SURVIVOR_MAKEUP_MAX_DB * (num / den).coerceIn(0.0, 1.0))
    }

    /** Linear-gain form of [survivorMakeupDb] (1.0 at full-band). */
    fun survivorMakeupGain(
        cutoffHz: Double,
        openHz: Double = FULL_BAND_HZ,
        floorHz: Double = MUFFLE_FLOOR_HZ_SHARED,
    ): Double = 10.0.pow(survivorMakeupDb(cutoffHz, openHz, floorHz) / 20.0)

    /**
     * Muffle low-pass at one overlap [progress] (0..1) with a real return leg.
     * Both legs sample the same C1 raised-cosine in log-Hz (see
     * [c1LogGlideHz]): entry closes open->floor over 0..[entryFraction],
     * return reopens floor->open over [entryFraction]..1, with zero log
     * slope at the turnaround on both sides so the old p^1.4-vs-linear
     * kink (2.14x/3.0x) is gone. At progress 1 the cutoff is full-band
     * (20000 Hz), so teardown never needs the open() glide. [sweep]
     * scales the floor the same way the live rider does. Live-rider only:
     * the native dying-OUT envelope is monotonic (see [muffleEnvelope]).
     */
    fun muffleCutoffAt(
        progress: Double,
        sweep: Double,
        entryFraction: Double,
        openHz: Double = FULL_BAND_HZ,
        floorHz: Double = MUFFLE_FLOOR_HZ_SHARED,
    ): Double {
        val floor = glideHz(openHz, floorHz, sweep.coerceIn(0.0, 1.0))
        val ef = entryFraction.coerceIn(0.05, 0.95)
        val p = progress.coerceIn(0.0, 1.0)
        return if (p < ef) {
            val entryProg = (p / ef).coerceIn(0.0, 1.0)
            c1LogGlideHz(openHz, floor, entryProg)
        } else {
            val ret = ((p - ef) / (1.0 - ef)).coerceIn(0.0, 1.0)
            c1LogGlideHz(floor, openHz, ret)
        }
    }

    /** Shape exponent for the muffle entry close (mirrors the mixer). */
    const val MUFFLE_SHAPE = 1.4

    /**
     * Bridge return leg: park->open over [returnStartFraction]..1 with the
     * shared C1 raised-cosine in log-Hz (zero slope at park and at
     * full-band). Before the return the caller holds [parkHz]; this
     * function only describes the trailing reopen, ending at full-band
     * at progress 1. Survivor (incoming) deck only; see
     * [bridgeDeadOutEnvelope] for the dying OUT deck.
     */
    fun bridgeReopenCutoffAt(
        progress: Double,
        returnStartFraction: Double,
        parkHz: Double,
        openHz: Double = FULL_BAND_HZ,
    ): Double {
        val rs = returnStartFraction.coerceIn(0.05, 0.95)
        val p = progress.coerceIn(0.0, 1.0)
        if (p < rs) return parkHz
        val ret = ((p - rs) / (1.0 - rs)).coerceIn(0.0, 1.0)
        return c1LogGlideHz(parkHz, openHz, ret)
    }

    /**
     * Native muffle low-pass envelope for the DYING OUT deck: monotonic
     * open->floor close over the whole 0..releaseAt span, sampled at each
     * beat with the shared C1 raised-cosine in log-Hz (zero slope at both
     * ends), ending at the 700 Hz muffle floor at [LoopMix.Clock.releaseAt]
     * while the OUT fader falls to zero. No V-turnaround: the dying deck
     * never reopens to 20 kHz right before it dies (the unity-gain
     * brightness blast). Times are the beat grid (strictly increasing,
     * no enterEnd==rampEnd collapse); the survivor (live rider) keeps its
     * smoothed V via [muffleCutoffAt], but the rendered OUT is monotonic.
     */
    fun muffleEnvelope(clock: LoopMix.Clock): Pair<DoubleArray, FloatArray> {
        val beats = clock.beats.coerceAtLeast(2)
        val times = DoubleArray(beats + 1) { clock.beatTimes[it] }
        val values = FloatArray(beats + 1) { k ->
            c1LogGlideHz(FULL_BAND_HZ, MUFFLE_FLOOR_HZ_SHARED, k.toDouble() / beats.toDouble()).toFloat()
        }
        return times to values
    }

    /**
     * Native bridge low-pass envelope for the SURVIVING (incoming) deck:
     * open->park->open, sampled at each beat with the shared C1
     * raised-cosine in log-Hz on both legs (flat at park turnaround and
     * at both ends). The return leg is proportional
     * ([proportionalExitBeats], 2.5-4.0-beat class) so the survivor
     * reopens gradually to full-band at releaseAt. Strictly increasing
     * beat-grid times. The dying OUT deck uses [bridgeDeadOutEnvelope]
     * (monotonic, no reopen).
     */
    fun bridgeEnvelope(clock: LoopMix.Clock): Pair<DoubleArray, FloatArray> {
        val beats = clock.beats.coerceAtLeast(2)
        val exit = proportionalExitBeats(beats)
        val keep = (beats - exit).coerceIn(1, beats - 1)
        val times = DoubleArray(beats + 1) { clock.beatTimes[it] }
        val values = FloatArray(beats + 1) { k ->
            if (k <= keep) {
                c1LogGlideHz(FULL_BAND_HZ, BRIDGE_PARK_HZ_SHARED, k.toDouble() / keep.toDouble()).toFloat()
            } else {
                c1LogGlideHz(BRIDGE_PARK_HZ_SHARED, FULL_BAND_HZ, (k - keep).toDouble() / exit.toDouble()).toFloat()
            }
        }
        return times to values
    }

    /**
     * Native bridge low-pass envelope for the DYING OUT deck: monotonic
     * open->park close over the whole 0..releaseAt span, sampled with the
     * shared C1 raised-cosine in log-Hz and ending at the 1500 Hz park
     * while the OUT fader falls to zero. No 20 kHz reopen before death.
     */
    fun bridgeDeadOutEnvelope(clock: LoopMix.Clock): Pair<DoubleArray, FloatArray> {
        val beats = clock.beats.coerceAtLeast(2)
        val times = DoubleArray(beats + 1) { clock.beatTimes[it] }
        val values = FloatArray(beats + 1) { k ->
            c1LogGlideHz(FULL_BAND_HZ, BRIDGE_PARK_HZ_SHARED, k.toDouble() / beats.toDouble()).toFloat()
        }
        return times to values
    }

    // -- bounded-decline carve envelope (pure, shared so it is testable) --
    //
    // The generic bounded decline (4 s DJ_FILTER) used to render through
    // rideFilterSweep, whose 1500 Hz floor and equal-power faders left both
    // decks wide-band and high-gain at the midpoint: 4500 Hz out / 174 Hz in
    // at gains 0.57/0.53, over two raw tempi. The carve below is a different
    // gesture with one purpose: never let both decks be audible in the same
    // band at the same time. Verified by a sweep test: no progress point
    // exists where both gains exceed 0.5 while outLp exceeds inHp.
    // Numbers (verified against the measured 164/90 behaviour):
    // - engage at 7000 Hz immediately: same hand-on-the-filter entry the
    //   sweep uses, so the gesture reads from the first instant.
    // - floor 400 Hz, not 1500: below the vocal body, so the leaving track
    //   is muffled (kick bed only) while the incoming is still held high.
    //   A 4 s exit is dismissed, not kept recognisable; the sweep's
    //   keep-it-recognisable floor is wrong for a carve.
    // - sweep shape 0.4 (front-loaded): a back-loaded shape spends the
    //   travel past the midpoint, where both tracks are loudest together.
    //   0.4 puts the body below 1000 Hz by the midpoint (800 Hz at p=0.5).
    // - incoming HP 2800 Hz held to 0.82: presence only until the leaving
    //   track is muffled; opens only as it is gone. Higher and later than
    //   the vocal-safe sweep, because a decline is for pairs with no
    //   matchable relationship at all.
    // - gains: out falls with exponent 0.5 (below 0.5 by p=0.44), in rises
    //   with 1.9 (above 0.5 only past p=0.81). The loud regions never
    //   coincide, so the raw-tempo flam rides at whisper level on one deck.
    //   No stretch is applied on the decline (rate 1.0 by construction);
    //   the separation, not the stretcher, is what hides the flam.
    const val DECLINE_ENTRY_HZ = 7000.0
    const val DECLINE_FLOOR_HZ = 400.0
    const val DECLINE_SWEEP_SHAPE = 0.4
    const val DECLINE_IN_HP_HZ = 2800.0
    const val DECLINE_IN_OPEN_BY = 0.82
    const val DECLINE_IN_SHAPE = 0.5
    const val DECLINE_OUT_FALL_SHAPE = 0.5
    const val DECLINE_IN_RISE_SHAPE = 1.9
    /** High-pass floor of the carve's entry glide; mirrors the renderer's OFF_HZ. */
    const val DECLINE_OFF_HZ = 20.0

    /** Outgoing low-pass of the bounded-decline carve at [progress], [sweep]-scaled like the riders. */
    fun declineOutLpAt(progress: Double, sweep: Double): Double {
        val open = FULL_BAND_HZ
        val entry = glideHz(open, DECLINE_ENTRY_HZ, sweep.coerceIn(0.0, 1.0))
        val floor = glideHz(open, DECLINE_FLOOR_HZ, sweep.coerceIn(0.0, 1.0))
        return glideHz(entry, floor, progress.coerceIn(0.0, 1.0).pow(DECLINE_SWEEP_SHAPE))
    }

    /** Incoming high-pass of the bounded-decline carve at [progress], [sweep]-scaled like entryHighPass. */
    fun declineInHpAt(progress: Double, sweep: Double): Double {
        val remaining = (1.0 - progress.coerceIn(0.0, 1.0) / DECLINE_IN_OPEN_BY).coerceIn(0.0, 1.0)
        return glideHz(DECLINE_OFF_HZ, DECLINE_IN_HP_HZ, sweep.coerceIn(0.0, 1.0) * remaining.pow(DECLINE_IN_SHAPE))
    }

    /** Incoming fader of the bounded-decline carve: the late rise keeps loud regions disjoint. */
    fun declineInGainAt(progress: Double): Double =
        MixGain.rise(progress.coerceIn(0.0, 1.0).pow(DECLINE_IN_RISE_SHAPE).toFloat()).toDouble()

    /** Outgoing fader of the bounded-decline carve: the early fall keeps loud regions disjoint. */
    fun declineOutGainAt(progress: Double): Double =
        MixGain.fall(progress.coerceIn(0.0, 1.0).pow(DECLINE_OUT_FALL_SHAPE).toFloat()).toDouble()

    /**
     * Octave-relationship gate string for evidence, or null when the pair's
     * raw tempo ratio is not near a factor 2 either way. Detection only;
     * see [detectOctaveRelationship].
     */
    fun octaveGateDescription(out: TrackAnalysis, inn: TrackAnalysis): String? {
        val rel = detectOctaveRelationship(out.bpm, inn.bpm) ?: return null
        return "fold=%.2f rate=%.3f %s".format(rel.fold, rel.rate, if (rel.inAudibleBand) "in-band" else "report-only")
    }

    /** Evidence record for the chosen candidate (Orchard mix dump shape). */
    fun evidenceFor(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        candidate: Candidate,
        outAnchor: Double,
    ): TransitionEvidenceCache.Evidence {
        val gates = candidate.gates.toMutableMap()
        octaveGateDescription(out, inn)?.let { gates["octave-relationship"] = it }
        return TransitionEvidenceCache.Evidence(
            outId = out.trackId,
            inId = inn.trackId,
            style = candidate.kind,
            reason = candidate.plan.reason,
            gates = gates,
            scores = mapOf(
                "confidence" to candidate.confidence,
                "flow" to candidate.flow.score,
                "sharedBeat" to candidate.flow.sharedBeat,
                "waitSeconds" to candidate.flow.waitSeconds,
                "skip" to candidate.flow.skip,
                "tempoDelta" to foldedDelta(out, inn),
                "vocalOverlap" to candidate.plan.vocalOverlap,
            ),
            confidence = candidate.confidence,
            flowScore = candidate.flow.score,
        )
    }
}
