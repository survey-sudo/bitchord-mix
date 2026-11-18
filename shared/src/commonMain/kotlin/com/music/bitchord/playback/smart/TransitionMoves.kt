package com.music.bitchord.playback.smart

import kotlin.math.min

/**
 * One automation lane. Times are master beats relative to the landing L = 0.
 * Values are dB for gains and band shelves, Hz for filters, or 0..1 for sends.
 */
class Lane(
    val beats: DoubleArray,
    val values: FloatArray,
    val shape: String = "lin",
    val exponential: Boolean = false,
)

/**
 * The score sheet for one chosen move, plus the [LoopMix.Plan] the renderer
 * already knows how to play. Lanes replace the generic blend envelopes.
 */
class PhraseScript(
    val choice: PhraseChoice,
    val plan: LoopMix.Plan,
    val outGain: Lane,
    val inGain: Lane,
    val outLow: Lane,
    val inLow: Lane,
    val outMid: Lane,
    val inMid: Lane,
    val outHigh: Lane,
    val inHigh: Lane,
    val outHp: Lane,
    val inHp: Lane,
    val outLp: Lane,
    val echoSend: Lane,
    val echoDelaySec: Double,
    val echoFeedback: Float,
    /** Mix seconds, on the plan's clock, before which B stays at its cue. */
    val incomingHoldSec: Double,
    val flanger: Boolean,
    val bTrimDb: Float,
    val phaseMs: Double,
)

/**
 * Compiles a [PhraseChoice] into a plan and the lanes of its score sheet.
 * Policy b (the default) ramps A toward B before B is heard. B is at 1.000×
 * from the moment it is audible, which is at or before two beats prior to L.
 */
object TransitionMoves {
    const val MUTE_DB = -80f
    const val KILL_DB = -40f

    /**
     * djmix24 Rung 0 consecutive-album guard (scalars only): same non-blank
     * album title/id AND current trackNumber > 0 AND next == current + 1.
     * Singles (blank album) and non-consecutive album siblings still AutoMix.
     */
    fun isConsecutiveAlbumPair(
        albumA: String, albumIdA: String, trackNumA: Int,
        albumB: String, albumIdB: String, trackNumB: Int,
    ): Boolean {
        val sameAlbum = if (albumIdA.isNotBlank() || albumIdB.isNotBlank()) {
            albumIdA.isNotBlank() && albumIdA == albumIdB
        } else {
            albumA.isNotBlank() && albumA == albumB
        }
        if (!sameAlbum) return false
        return trackNumA > 0 && trackNumB == trackNumA + 1
    }

    /** djmix24: deterministic EDM dance-pair check (scalars only, patent-safe). */
    fun isEdmDancePair(outBpm: Double, inBpm: Double): Boolean {
        if (outBpm !in 110.0..150.0 || inBpm !in 110.0..150.0) return false
        if (outBpm <= 1.0 || inBpm <= 1.0) return false
        val fold = alignedFold(outBpm, inBpm)
        val folded = inBpm / fold
        val delta = kotlin.math.abs(folded / outBpm - 1.0)
        return delta <= PhraseConfig.TEMPO_HARD_DELTA + 1e-9
    }

    /** djmix24 FX rotation slot: 0=FLANGER, 1=CLEAN_SWAP, 2=ECHO, 3=REVERB. Deterministic per pair. */
    fun fxSlot(outId: String, inId: String): Int = ((pairHash(outId, inId) % 4) + 4) % 4

    /**
     * djmix25 P1.6 acapella-over-instrumental gate (scalars only): same
     * Camelot, both key confidences >= 0.6, incoming starts instrumental
     * (energy-based lead: low whole-track vocal share), outgoing carries a
     * vocal to ride over it.
     */
    fun shouldAcapella(out: TrackAnalysis, inn: TrackAnalysis): Boolean {
        val left = camelotCode(exitKeyForMix(out)) ?: return false
        val right = camelotCode(entryKeyForMix(inn)) ?: return false
        if (left != right && !camelotCompatible(left, right)) return false
        if (left != right) return false
        val conf = minOf(exitKeyConfidence(out), entryKeyConfidence(inn))
        if (conf < 0.6) return false
        if (inn.vocalProbability >= 0.45) return false
        if (out.vocalProbability < 0.45) return false
        return true
    }

    /**
     * djmix25 P1.6: acapella-over-instrumental move. The outgoing vocal
     * (from sung bars / the umxhq mask) rides over the incoming
     * instrumental intro for up to 8 bars. Compiles via [compile] with the
     * carve lanes forced and the acapella carrier set, so the renderer
     * honours it without a new native path.
     */
    fun acapellaOverInstrumental(
        choice: PhraseChoice,
        outBpm: Double,
        inBpm: Double,
        flanger: Boolean = false,
        outgoingId: String = "",
        incomingId: String = "",
    ): PhraseScript {
        val capped = choice.copy(overlapBars = minOf(choice.overlapBars, 8))
        return compile(capped, outBpm, inBpm, flanger, outgoingId, incomingId, acapella = true)
    }

    /** djmix24: 8-beat LFO helper (Bug A2). One sweep per 8 beats. */
    fun lfoHzForBeatSec(beatSec: Double): Double =
        if (beatSec > 1e-6 && beatSec.isFinite()) 1.0 / (8.0 * beatSec) else 0.0

    fun compile(
        choice: PhraseChoice,
        outBpm: Double,
        inBpm: Double,
        flanger: Boolean = false,
        outgoingId: String = "",
        incomingId: String = "",
        /** djmix25 P1.2: extend the original outro loop by N bars (phrase reuse). */
        interpolationExtendBars: Int = 0,
        /** djmix25 P1.6: acapella-over-instrumental carrier. */
        acapella: Boolean = false,
    ): PhraseScript {
        // djmix24 Bug A4: deterministic EDM buildup + pair-hash FX rotation.
        // EDM dance pairs (110..150 BPM post-fold within 8%, low dual-vocal)
        // ALWAYS get flanger + tightening roll (Bug A1 scalar carrier in
        // Plan). Non-EDM eligible blends use deterministic pair-hash rotation
        // FLANGER / CLEAN_SWAP / ECHO / REVERB — never flanging over
        // dual-vocal overlap (vocalOverlapBeats >= 1.0). Tempo-gap templates
        // (M6/M10/CUT/M11/RELAXED) never flange (echo/reverb own them).
        val eligibleTemplate = choice.template == MixTemplate.M1 || choice.template == MixTemplate.M3 ||
            choice.template == MixTemplate.M7 || choice.template == MixTemplate.M8 ||
            choice.template == MixTemplate.ROLLING || choice.template == MixTemplate.M2
        val notTempoGap = choice.template != MixTemplate.M6 &&
            choice.template != MixTemplate.M10 && choice.template != MixTemplate.CUT_ON_ONE &&
            choice.template != MixTemplate.M11 && choice.template != MixTemplate.RELAXED
        val lowVocal = choice.vocalProduct < 0.18f && choice.vocalOverlapBeats < 1.0
        val edmPair = isEdmDancePair(outBpm, inBpm)
        val edmBuildup = edmPair && lowVocal && notTempoGap && eligibleTemplate
        val hasIds = outgoingId.isNotEmpty() || incomingId.isNotEmpty()
        val slot = if (hasIds) fxSlot(outgoingId, incomingId) else 3
        // Non-EDM rotation: slot 0 -> FLANGER (colour), 1 -> CLEAN_SWAP,
        // 2 -> ECHO (echoGated path), 3 -> REVERB (wash). Anti-repeat is
        // inherent (hash of the pair, not time), so non-EDM queues stay
        // varied without ever flanging vocal clash.
        val rotationFlanger = slot == 0 && lowVocal && notTempoGap && eligibleTemplate &&
            !(outBpm > 1.0 && inBpm > 1.0 && kotlin.math.abs((inBpm / alignedFold(outBpm, inBpm)) / outBpm - 1.0) > PhraseConfig.TEMPO_HARD_DELTA)
        val rotationEcho = slot == 2 && !edmBuildup
        val rotationReverb = slot == 3 && !edmBuildup
        val legacyAuto = if (!hasIds) flanger && (choice.template == MixTemplate.M7 || choice.template == MixTemplate.M8) else false
        val wantFlanger = flanger || edmBuildup || rotationFlanger || legacyAuto
        val legacy = choice.legacyLoop
        if (legacy != null) {
            // Bug A1: legacy crown plans (LoopMix.plan) already carry the
            // deterministic FX in Plan scalars. Merge so sheet() never
            // drops a plan-flagged flanger/buildup.
            val mergedFlanger = wantFlanger || legacy.flanger
            return sheet(choice, legacy, mergedFlanger, holdSec = 0.0, echoDelaySec = 0.0, echoFeedback = 0f)
        }
        val outBeat = if (outBpm > 1.0) 60.0 / outBpm else 0.5
        val inBeat = if (inBpm > 1.0) 60.0 / inBpm else outBeat
        val bar = outBeat * 4.0
        val inBar = inBeat * 4.0
        val prep = choice.tempoPrepBars.coerceAtLeast(0)
        val overlap = choice.overlapBars.coerceAtLeast(if (choice.template == MixTemplate.M6 || choice.template == MixTemplate.CUT_ON_ONE || choice.template == MixTemplate.M10) 0 else 1)
        var exit = if (overlap == 0) 1 else overlap
        val needsExtensionForCap = choice.exit.loopableRoom && choice.exit.roomBars < overlap && choice.exit.roomBars >= 1
        val loopingForCap = choice.template == MixTemplate.M2 || needsExtensionForCap || interpolationExtendBars > 0

        val nonBeatmatched = choice.template == MixTemplate.M6 ||
            choice.template == MixTemplate.M10 ||
            choice.template == MixTemplate.CUT_ON_ONE ||
            choice.template == MixTemplate.M11
        val rawFold = if (outBpm > 1.0 && inBpm > 1.0) alignedFold(outBpm, inBpm) else 1.0
        val foldedInBpm = if (inBpm > 1.0) inBpm / rawFold else outBpm
        val tempoDelta = if (outBpm > 1.0 && inBpm > 1.0) kotlin.math.abs(foldedInBpm / outBpm - 1.0) else Double.POSITIVE_INFINITY
        val forceUnity = nonBeatmatched || outBpm <= 1.0 || inBpm <= 1.0 || tempoDelta > PhraseConfig.TEMPO_HARD_DELTA

        val effectiveFold = if (forceUnity) 1.0 else rawFold
        val targetBpmOverride = if (forceUnity) outBpm else null
        var ramp = if (forceUnity) {
            0
        } else if (tempoDelta > 0.005) {
            val pctBars = kotlin.math.ceil((tempoDelta - 1e-9).coerceAtLeast(0.0) / PhraseConfig.MAX_BPM_PCT_PER_BAR).toInt()
                .coerceAtLeast(4)
                .coerceAtMost(PhraseConfig.TEMPO_PREP_BARS)
            maxOf(prep, pctBars)
        } else {
            if (prep > 0) prep else if (overlap == 0) 0 else 1
        }
        // vc73 A4: ramp + exit must not pull start 45-60s early before
        // CONTENT_END. Cap ramp down (keeping >=1 when tempoDelta) and
        // exit to 8 so start >= release - 28.0.
        if (choice.exit.kind == ExitKind.CONTENT_END && !loopingForCap && (ramp + exit) * bar > 28.0) {
            exit = minOf(exit, 8)
            ramp = minOf(ramp, maxOf(0, 8 - exit).coerceAtLeast(if (tempoDelta > 0.005) 1 else 0))
            ramp = minOf(ramp, 6)
        }
        val total = (ramp + exit).coerceAtLeast(1)
        val release = choice.exit.releaseSec
        val start = (release - total * bar).coerceAtLeast(0.0)
        // djmix24 outro loop extension: a clean loopable outro phrase
        // shorter than the planned overlap loop-extends A's outro
        // underneath B's intro/buildup (Clock seamlessly loops) instead
        // of rushing past EOF. Music-end protection (cold hits vs
        // fade-out) is respected by CuePoints' release choice — this
        // only extends when loopableRoom is already true.
        val needsExtension = choice.exit.loopableRoom && choice.exit.roomBars < overlap && choice.exit.roomBars >= 1
        // djmix25 P1.2: "extend the original loop by N bars" instead of the
        // hard 4-bar cap, when a repeated phrase lets the outro replay.
        val requestedLoopBars = if (choice.exit.roomBars in 1..2) 2 else 4 + interpolationExtendBars.coerceAtLeast(0)
        val availableBars = (release / bar).toInt().coerceAtLeast(0)
        val loopBars = minOf(requestedLoopBars, availableBars, choice.exit.roomBars.coerceAtLeast(0))
        val looping = (choice.template == MixTemplate.M2 || needsExtension || interpolationExtendBars > 0) && loopBars > 0
        val loopLen = loopBars.toDouble() * bar
        val outPick = if (looping) {
            LoopMix.Pick(
                loopStart = (release - loopLen).coerceAtLeast(0.0),
                loopEnd = release,
                bars = loopBars,
                beatSec = outBeat,
                bpm = outBpm,
                vocal = 0.0,
                checkedBy = LoopMix.Check.MASK,
            )
        } else {
            LoopMix.Pick(start, start, 4, outBeat, outBpm, 0.0, LoopMix.Check.MASK)
        }
        val echo = choice.echoGated || choice.template == MixTemplate.M6 || rotationEcho
        val delayBeats = PhraseConfig.echoDelayBeats(inBpm, choice.template == MixTemplate.ROLLING).second
        val delaySec = if (echo) delayBeats * inBeat else 0.0
        // djmix25 P1.11: per-template echo density (M6 full tail; M10/CUT half).
        val fadeSecForEcho = total * bar
        val echoFeedbackForPlan = PhraseConfig.echoFeedbackFor(choice.template.name, fadeSecForEcho)
        val exitTail = PhraseConfig.exitTailSeconds(outBeat)
        val inStartSec = choice.entry.startSec + (choice.entry.leadInBars - exit).coerceAtLeast(0) * inBar
        val inPick = LoopMix.Pick(inStartSec, inStartSec, 4, inBeat, inBpm, 0.0, LoopMix.Check.MASK)
        val keyClash = choice.keyClash && !forceUnity
        // djmix24 Bug A1 scalar FX carrier: plan.flanger/buildupRoll/
        // reverbWash/hpSweepEndHz carry the deterministic decision so
        // LoopMixSession.build honours it even when loopScript is null
        // (loopPlanFor / bridge paths). Slip-roll (Rung 4) reuses
        // buildupRoll; its 1->1/2->1/4 schedule is native-side.
        val planFlanger = (wantFlanger || legacyAuto) && !echo && !keyClash && eligibleTemplate
        val planRoll = edmBuildup && !keyClash && (looping || choice.template == MixTemplate.M1 || choice.template == MixTemplate.M3 || choice.template == MixTemplate.M7 || choice.template == MixTemplate.M8 || choice.template == MixTemplate.ROLLING)
        val planReverb = (edmBuildup || rotationReverb) && !echo && !keyClash
        val planHpEnd = if (planRoll) LoopMix.BUILDUP_HP_END_HZ else if (planFlanger) 420f else 0f
        val basePlan = LoopMix.Plan(
            out = outPick,
            inn = inPick,
            fold = effectiveFold,
            enterBars = 0,
            rampBars = ramp,
            exitBars = exit,
            totalBars = total,
            outLoops = looping,
            inLoops = false,
            nativeIncoming = true,
            echoTail = echo,
            carve = choice.template == MixTemplate.M4 || choice.template == MixTemplate.M5 || choice.template == MixTemplate.VOCAL_OVER_OUTRO || acapella || keyClash,
            clash = keyClash,
            rung = choice.rung,
            exitTailSeconds = exitTail,
            targetBpmOverride = targetBpmOverride,
            incomingHoldSec = 0.0,
            flanger = planFlanger,
            buildupRoll = planRoll,
            reverbWash = planReverb,
            hpSweepEndHz = planHpEnd,
            acapella = acapella,
            // djmix25 P1.12: per-template bass swap (club 1 beat legacy).
            faderSwapBeats = if (keyClash) 1.0 else when (choice.template) {
                MixTemplate.M7, MixTemplate.ENERGY_LIFT -> 0.5
                MixTemplate.M9, MixTemplate.RELAXED -> 2.0
                MixTemplate.M4, MixTemplate.M5, MixTemplate.VOCAL_OVER_OUTRO -> 0.5
                else -> 1.0
            },
        )
        // Sync incomingHoldSec to LoopMix.Clock's exact ramped beat-time integral
        // beatTimes[rampTo] so Song B enters on the exact beat/downbeat when
        // outBpm != inBpm (constant ramp * bar drifted by up to ~1.5 beats).
        val hold = if (ramp > 0) {
            val clock = LoopMix.Clock(basePlan)
            clock.beatTimes[clock.rampTo]
        } else {
            0.0
        }
        val plan = basePlan.copy(incomingHoldSec = hold)
        return sheet(choice, plan, wantFlanger && !keyClash, hold, delaySec, echoFeedbackForPlan)
    }

    /** Beats from t0 to the landing, for a compiled plan: the ramp, then the overlap. */
    fun landingBeat(script: PhraseScript): Double {
        val plan = script.plan
        return (plan.rampBars + plan.exitBars).coerceAtLeast(1) * 4.0
    }

    private fun sheet(
        choice: PhraseChoice,
        plan: LoopMix.Plan,
        flanger: Boolean,
        holdSec: Double,
        echoDelaySec: Double,
        echoFeedback: Float,
    ): PhraseScript {
        val o = choice.overlapBars.coerceAtLeast(0)
        val half = -o / 2.0 * 4.0
        val start = -o * 4.0
        val kill = KILL_DB
        val mute = MUTE_DB
        // djmix21 incoming-vocal crisp rule: when B's own lead-in carries
        // vocal (scalar presence from its own sung bars), the incoming mid/
        // high lanes stay at unity through the landing and the separation
        // work happens on the OUTGOING mid instead. Instrumental pairs
        // (leadInVocal < 0.2) keep today's lanes untouched.
        val incomingSinging = choice.entry.leadInVocal >= 0.2f
        val (outGain, inGain, outLow, inLow, outMid, inMid, outHigh, inHigh, outHp, echoSend) = when (choice.template) {
            MixTemplate.M1, MixTemplate.M3 -> lanes(
                outGain = lane(start, 0f, half, 0f, -o.toDouble(), -9f, 1.0, mute, shape = "ep"),
                inGain = lane(start, mute, start + o.toDouble(), -9f, half, -1.5f, -o.toDouble(), 0f, shape = "ep"),
                outLow = lane(start, 0f, half, 0f, half + 0.01, kill),
                inLow = lane(start, kill, half, kill, half + 0.01, 0f),
                // Crisp incoming vocal: B mid stays 0 dB, A mid carves away.
                outMid = if (incomingSinging) lane(start, 0f, half, -3f, 0.0, -6f) else lane(start, 0f, 0.0, 0f),
                inMid = lane(start, 0f, 0.0, 0f),
                outHigh = lane(start, 0f, 0.0, 0f),
                inHigh = if (incomingSinging) lane(start, -1.0f, half, 0f) else lane(start, -2.5f, start / 2, -1.0f, half, 0f),
                outHp = lane(half, 20f, -2.0, 180f, 0.0, 250f, exponential = true),
                echoSend = lane(start, 0f, 0.0, 0f),
            )
            MixTemplate.M4 -> lanes(
                outGain = lane(-choice.entry.leadInBars * 4.0, 0f, 4.0, mute, shape = "ep"),
                inGain = lane(-choice.entry.leadInBars * 4.0, -6f, 0.0, 0f, shape = "ep"),
                outLow = lane(-4.0, 0f, 0.0, kill),
                inLow = lane(-choice.entry.leadInBars * 4.0, kill, 0.0, 0f),
                outMid = lane(0.0, -4.5f, 2.0, -9f, 4.0, mute),
                inMid = lane(0.0, 0f),
                outHigh = lane(0.0, -3f, 2.0, -9f, 4.0, mute),
                inHigh = lane(0.0, 0f),
                outHp = lane(0.0, 150f, 2.0, 300f, 4.0, 400f, exponential = true),
                echoSend = lane(0.0, 0f),
            )
            MixTemplate.M6 -> lanes(
                outGain = lane(-4.0, 0f, -1.0, 0f, 0.0, mute, shape = "ep"),
                inGain = lane(-choice.entry.pickupBeats.toDouble(), 0f, 0.0, 0f),
                outLow = lane(0.0, 0f),
                inLow = lane(0.0, 0f),
                outMid = lane(0.0, 0f),
                inMid = lane(0.0, 0f),
                outHigh = lane(0.0, 0f),
                inHigh = lane(0.0, 0f),
                outHp = lane(0.0, 20f),
                echoSend = lane(-4.0, 0f, -3.5, 0f, -2.0, PhraseConfig.ECHO_SEND_PEAK, 0.0, PhraseConfig.ECHO_SEND_PEAK, 0.25, 0f, shape = "cos"),
            )
            MixTemplate.M10, MixTemplate.CUT_ON_ONE -> lanes(
                outGain = lane(-4.0, 0f, -1.0, 0f, 0.0, mute, shape = "ep"),
                inGain = lane(-1.0, mute, 0.0, 0f, shape = "ep"),
                outLow = lane(0.0, 0f),
                inLow = lane(0.0, 0f),
                outMid = lane(0.0, 0f),
                inMid = lane(0.0, 0f),
                outHigh = lane(0.0, 0f),
                inHigh = lane(0.0, 0f),
                outHp = lane(0.0, 20f),
                echoSend = lane(-4.0, 0f, -3.5, 0f, -2.0, PhraseConfig.ECHO_SEND_PEAK, 0.0, PhraseConfig.ECHO_SEND_PEAK, 0.25, 0f, shape = "cos"),
            )
            MixTemplate.M7, MixTemplate.ENERGY_LIFT -> lanes(
                outGain = lane(-16.0, 0f, 0.0, 0f, 4.0, mute, shape = "ep"),
                inGain = lane(-8.0, mute, 0.0, 0f, shape = "ep"),
                outLow = lane(-2.0, 0f, 0.0, kill),
                inLow = lane(-8.0, kill, 0.0, 0f),
                outMid = lane(0.0, 0f, 4.0, mute),
                inMid = if (incomingSinging) lane(-8.0, -1.5f, 0.0, 0f) else lane(-8.0, -8f, 0.0, 0f),
                outHigh = lane(0.0, 0f, 4.0, mute),
                inHigh = lane(0.0, 0f),
                outHp = lane(-16.0, 20f, 0.0, 400f, exponential = true),
                echoSend = lane(-1.0, 0f, 0.0, 0.2f),
            )
            MixTemplate.M9, MixTemplate.RELAXED -> run {
                val startBeat = -o.coerceAtLeast(1) * 4.0
                val midBeat = startBeat / 2.0
                lanes(
                    outGain = lane(startBeat, 0f, midBeat, 0f, midBeat * 0.66, -2.5f, midBeat * 0.33, -6.5f, 0.0, -14f, shape = "ep"),
                    inGain = lane(-min(o.coerceAtLeast(1), 16) * 4.0, mute, midBeat, -3.5f, -4.0, -1.5f, 0.0, 0f, shape = "ep"),
                    outLow = lane(0.0, 0f),
                    inLow = lane(-min(o.coerceAtLeast(1), 16) * 4.0, kill, 0.0, 0f),
                    outMid = lane(0.0, 0f),
                    inMid = lane(0.0, 0f),
                    outHigh = lane(0.0, 0f),
                    inHigh = lane(0.0, 0f),
                    outHp = lane(startBeat, 20f, 0.0, 200f, exponential = true),
                    echoSend = lane(0.0, 0f),
                )
            }
            MixTemplate.ROLLING -> lanes(
                outGain = lane(-8.0 * 4, 0f, 0.0, mute, shape = "ep"),
                inGain = lane(-8.0 * 4, mute, 0.0, 0f, shape = "ep"),
                outLow = lane(-4.0, 0f, 0.0, kill),
                inLow = lane(-8.0 * 4, kill, 0.0, 0f),
                outMid = lane(0.0, 0f),
                inMid = if (incomingSinging) lane(-8.0 * 4, -1.5f, 0.0, 0f) else lane(-8.0 * 4, -8f, 0.0, 0f),
                outHigh = lane(0.0, 0f),
                inHigh = lane(0.0, 0f),
                outHp = lane(0.0, 20f),
                echoSend = lane(-1.0, 0f, 0.0, PhraseConfig.ECHO_SEND_PEAK, shape = "cos"),
            )
            MixTemplate.VOCAL_OVER_OUTRO -> lanes(
                outGain = lane(-8.0, 0f, -4.0, 0f, -2.0, -5f, 0.0, -14f, shape = "ep"),
                inGain = lane(-8.0, -6f, 0.0, 0f),
                outLow = lane(0.0, 0f),
                inLow = lane(-8.0, kill, 0.0, 0f),
                outMid = lane(-8.0, -4f, 0.0, -4f),
                inMid = lane(0.0, 0f),
                outHigh = lane(0.0, 0f),
                inHigh = lane(0.0, 0f),
                outHp = lane(0.0, 20f),
                echoSend = lane(0.0, 0f),
            )
            else -> lanes(
                outGain = lane(-4.0, 0f, 0.0, mute, shape = "ep"),
                inGain = lane(-4.0, mute, 0.0, 0f, shape = "ep"),
                outLow = lane(0.0, 0f),
                inLow = lane(0.0, 0f),
                outMid = lane(0.0, 0f),
                inMid = lane(0.0, 0f),
                outHigh = lane(0.0, 0f),
                inHigh = lane(0.0, 0f),
                outHp = lane(0.0, 20f),
                echoSend = lane(0.0, 0f),
            )
        }
        val phase = if (o >= PhraseConfig.PHASE_GATE_LONG_BARS) PhraseConfig.PHASE_GATE_LONG_MS.toDouble()
        else PhraseConfig.PHASE_GATE_SHORT_MS.toDouble()
        val tailBeats = PhraseConfig.exitTailSeconds(plan.out.beatSec) / plan.out.beatSec
        return PhraseScript(
            choice = choice,
            plan = plan,
            outGain = withExitTail(outGain, tailBeats),
            inGain = inGain,
            outLow = outLow,
            inLow = inLow,
            outMid = outMid,
            inMid = inMid,
            outHigh = outHigh,
            inHigh = inHigh,
            outHp = outHp,
            inHp = lane(0.0, 20f),
            outLp = lane(0.0, 20000f),
            echoSend = echoSend,
            echoDelaySec = echoDelaySec,
            echoFeedback = echoFeedback,
            incomingHoldSec = holdSec,
            // djmix20: eligible beatmix templates only (M1/M3/M7/M8/ROLLING/M2
            // legacy). Echo/cut/crossfade/relaxed/vocal-blend never flange.
            flanger = flanger && (choice.template == MixTemplate.M1 || choice.template == MixTemplate.M3 ||
                choice.template == MixTemplate.M7 || choice.template == MixTemplate.M8 ||
                choice.template == MixTemplate.ROLLING || choice.template == MixTemplate.M2),
            bTrimDb = 0f,
            phaseMs = phase,
        )
    }

    /**
     * Graceful-exit guarantee (djmix11): the outgoing deck may never end
     * audible. The lane's final mute knot is placed at its last knot plus
     * the planned dissolve, giving it a real equal-power fall instead of a
     * chop at unity (RELAXED/VOCAL_OVER_OUTRO used to ride flat at 0 dB).
     * Lanes already ending muted get flat silence — harmless.
     */
    /**
     * Graceful-exit guarantee (djmix11, shaped djmix12): the outgoing deck
     * may never end audible. Lanes already ending muted get flat silence.
     * Otherwise 16 equal-power samples from [last] to the mute knot so the
     * fall is monotone and no segment jumps between loud shorts.
     */
    private fun withExitTail(lane: Lane, tailBeats: Double): Lane {
        if (lane.beats.isEmpty()) return lane
        val mute = MUTE_DB
        val tail = if (tailBeats > 0.0 && tailBeats.isFinite()) tailBeats else 0.0
        val last = lane.values.last()
        if (last <= mute) {
            return Lane(lane.beats + (lane.beats.last() + tail), lane.values + last, lane.shape, lane.exponential)
        }
        val steps = 16
        val beats = lane.beats.toMutableList()
        val values = lane.values.toMutableList()
        val t0 = lane.beats.last()
        for (i in 1..steps) {
            val p = i.toFloat() / steps
            val g = MixGain.fall(p).toDouble()
            val db = (last + 20.0 * kotlin.math.log10(g.coerceAtLeast(1e-6))).toFloat().coerceAtLeast(mute - 6f)
            beats.add(t0 + tail * p)
            values.add(if (i == steps) mute else db)
        }
        return Lane(beats.toDoubleArray(), values.toFloatArray(), lane.shape, lane.exponential)
    }

    private data class Pack(
        val outGain: Lane, val inGain: Lane, val outLow: Lane, val inLow: Lane,
        val outMid: Lane, val inMid: Lane, val outHigh: Lane, val inHigh: Lane,
        val outHp: Lane, val echoSend: Lane,
    )

    private fun lanes(
        outGain: Lane, inGain: Lane, outLow: Lane, inLow: Lane,
        outMid: Lane, inMid: Lane, outHigh: Lane, inHigh: Lane,
        outHp: Lane, echoSend: Lane,
    ) = Pack(outGain, inGain, outLow, inLow, outMid, inMid, outHigh, inHigh, outHp, echoSend)

    private fun lane(vararg pairs: Number, shape: String = "lin", exponential: Boolean = false): Lane {
        val beats = DoubleArray(pairs.size / 2)
        val values = FloatArray(pairs.size / 2)
        var i = 0
        while (i + 1 < pairs.size) {
            beats[i / 2] = pairs[i].toDouble()
            values[i / 2] = pairs[i + 1].toFloat()
            i += 2
        }
        return Lane(beats, values, shape, exponential)
    }

    fun phraseLine(choice: PhraseChoice): String {
        val exit = choice.exit.sectionFn.name.lowercase()
        val entry = choice.entry.landingFn.name.lowercase()
        return "phrase · ${choice.template.name} ${choice.rung} ${choice.overlapBars}b · " +
            "A $exit @bar ${choice.exit.releaseBar} · B $entry @bar ${choice.entry.landingBar} · " +
            "swap @${choice.swapBar.toInt()} · ${choice.tempoPolicy} · ${choice.swapRampName} ${choice.swapRampMs} ms · " +
            "${choice.echoDelayName} ${choice.echoDelayBeats}"
    }

    /** Section text the expanded diagnostics already show, as "outro → intro". */
    fun sectionLine(choice: PhraseChoice): String =
        "${choice.exit.sectionFn.name.lowercase()} → ${choice.entry.landingFn.name.lowercase()}"

    fun scriptJson(
        script: PhraseScript,
        u: Int,
        g: Int,
        l: Int,
        bpmA: Double = 0.0,
        bpmB: Double = 0.0,
        camelot: Int? = null,
        lockedAt: Double? = null,
        slides: Int = 0,
    ): String {
        val c = script.choice
        val barB = if (bpmB > 1.0) 4.0 * 60.0 / bpmB else 0.0
        val landingB = c.entry.startSec + (c.entry.landingBar - c.entry.startBar) * barB
        return buildString {
            append("{\"template\":\"${c.template.name}\",\"rung\":\"${c.rung}\"")
            append(",\"score\":${fmt(c.score)}")
            append(",\"conf\":{\"struct\":${fmt(c.struct)},\"vocal\":${fmt(c.vocalScore)},\"phrase\":${fmt(c.phraseScore)}")
            append(",\"energy\":${fmt(c.energyScore)},\"length\":${fmt(c.lengthScore)},\"key\":${fmt(c.keyScore)},\"tempo\":${fmt(c.tempoScore)}}")
            append(",\"exit\":{\"type\":\"${c.exit.kind}\",\"section\":\"${c.exit.sectionFn}\",\"bar\":${c.exit.releaseBar}")
            append(",\"sec\":${fmt(c.exit.releaseSec)},\"energy\":${c.exit.energyLevel}}")
            append(",\"entry\":{\"type\":\"${c.entry.kind}\",\"section\":\"${c.entry.landingFn}\",\"bar\":${c.entry.landingBar}")
            append(",\"sec\":${fmt(landingB)},\"energy\":${c.entry.energyLevel},\"pickup\":${fmt(c.entry.pickupBeats.toDouble())}}")
            append(",\"L\":{\"aSec\":${fmt(c.exit.releaseSec)},\"bSec\":${fmt(landingB)}}")
            append(",\"P\":${c.overlapBars},\"T\":${fmt(c.tailBeats)}")
            append(",\"bpm\":{\"a\":${fmt(bpmA)},\"b\":${fmt(bpmB)}}")
            append(",\"aRampBars\":${c.tempoPrepBars},\"camelot\":${camelot ?: -1}")
            append(",\"tempoPolicy\":\"${c.tempoPolicy}\",\"prepBars\":${c.tempoPrepBars}")
            append(",\"swapRamp\":\"${c.swapRampName}\",\"swapRampMs\":${c.swapRampMs}")
            append(",\"echo\":\"${c.echoDelayName}\",\"echoBeats\":${fmt(c.echoDelayBeats)}")
            append(",\"vocalOverlapBeats\":${fmt(c.vocalOverlapBeats)},\"bTrimDb\":${fmt(script.bTrimDb.toDouble())}")
            append(",\"phaseMs\":${fmt(script.phaseMs)},\"lockedAt\":${fmt(lockedAt ?: -1.0)},\"slides\":$slides")
            append(",\"u\":$u,\"g\":$g,\"l\":$l,\"v\":${fmt(c.vocalProduct.toDouble())}}")
        }
    }

    private fun fmt(v: Double): String = ((v * 1000.0).toLong() / 1000.0).toString()
}
