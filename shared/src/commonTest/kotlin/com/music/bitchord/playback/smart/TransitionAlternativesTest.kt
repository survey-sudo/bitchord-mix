package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * vc69 P0 muffle, drop-in, and beatless-bridge families: each decline
 * rescue must win only on measured evidence, and curve-less pairs must
 * keep the bounded 4 s decline exactly as before.
 */
class TransitionAlternativesTest {

    private fun curves(duration: Double, valueAt: (Double) -> Double, step: Double = 1.0): List<EnergySample> {
        val count = (duration / step).toInt() + 1
        return (0 until count).map { EnergySample(it * step, valueAt(it * step)) }
    }

    private fun build(
        id: String,
        bpm: Double,
        key: String,
        keyConfidence: Double = 0.9,
        vocalProbability: Double = 0.9,
        bassAt: (Double) -> Double = { 1.0 },
        maskAt: (Double) -> Double = { 0.9 },
        audibleStart: Double? = null,
        firstBeat: Double = 2.5,
        beatConfidence: Double = 0.9,
    ): TrackAnalysis {
        val duration = 200.0
        val beat = 60.0 / bpm
        val downbeats = buildList {
            var t = 0.5
            while (t <= duration) {
                add(t)
                t += 4.0 * beat
            }
        }
        val energy = curves(duration, { it -> 0.8 })
        val mask = energy.map { maskAt(it.time) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = beatConfidence,
            downbeats = downbeats,
            firstBeat = firstBeat,
            key = key,
            keyConfidence = keyConfidence,
            audibleStartTime = audibleStart,
            contentEndTime = duration - 4.0,
            energyCurve = energy,
            vocalActivityMask = mask,
            vocalProbability = vocalProbability,
            lowEnergyCurve = curves(duration, bassAt),
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    private fun plan(out: TrackAnalysis, inn: TrackAnalysis): TransitionPlan {
        return planTransition(
            analysis = out,
            nextAnalysis = inn,
            currentTrack = TransitionTrackInfo(out.trackId, 200_000L),
            nextTrack = TransitionTrackInfo(inn.trackId, 200_000L),
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
        )
    }

    /** Loud both sides, incoming quiet until the swap, dual vocal decline in the 8..10 band. */
    private fun mufflePair(): Pair<TrackAnalysis, TrackAnalysis> {
        val out = build("out", 120.0, "C major")
        val inn = build("inn", 109.0, "C major", maskAt = { if (it < 5.5) 0.1 else 0.9 })
        return out to inn
    }

    /** Key-clash decline, silent masks, high energy both sides. */
    private fun dropPair(): Pair<TrackAnalysis, TrackAnalysis> {
        val out = build("out", 120.0, "C major", vocalProbability = 0.1, maskAt = { 0.0 })
        val inn = build("inn", 109.0, "Eb major", vocalProbability = 0.1, maskAt = { 0.0 })
        return out to inn
    }

    /** Dip bass on both beatless edges, far tempo, dual vocal decline. */
    private fun bridgePair(): Pair<TrackAnalysis, TrackAnalysis> {
        val out = build("out", 120.0, "C major", bassAt = { if (it >= 180.0) 0.05 else 1.0 }, maskAt = { 0.0 })
        val inn = build("inn", 94.0, "C major", bassAt = { if (it < 8.0) 0.05 else 1.0 }, maskAt = { 0.0 })
        return out to inn
    }

    @Test
    fun muffleRescuesTheBandDecline() {
        val (out, inn) = mufflePair()
        assertTrue(shouldDeclineToMix(out, inn), "fixture must decline first")
        val plan = plan(out, inn)
        assertEquals(TransitionStyle.MUFFLE, plan.transitionStyle, "reason=" + plan.reason)
        assertTrue(plan.reason.contains("muffle"))
        assertEquals(8, plan.muffleLeadBeats)
        assertTrue(plan.bassSwap)
        // 0.75 is the phase boundary (entry 6 / total 8); the return leg is
        // the explicit 2 beats below. Before vc70 this assertion encoded a
        // missing return (exit beats only as a fraction); now the return
        // is first-class data and the ride leaves over it.
        assertEquals(0.75, plan.bassSwapFraction, 1e-9)
        assertEquals(2, plan.exitBeats)
        assertTrue(plan.exitBeats > 0, "muffle must carry a return span")
        assertEquals(20000.0, TransitionAlternatives.muffleCutoffAt(1.0, plan.filterSweep, plan.bassSwapFraction), 1e-6)
        assertEquals(4.0, plan.fadeSeconds, 1e-9)
        assertEquals(192.0, plan.transitionStart, 1e-9)
        assertEquals(196.0, plan.transitionEnd, 1e-9)
    }

    @Test
    fun dropInRescuesHighEnergyKeyClash() {
        val (out, inn) = dropPair()
        val plan = plan(out, inn)
        assertEquals(TransitionStyle.DROP_IN, plan.transitionStyle, "reason=" + plan.reason)
        assertTrue(plan.reason.contains("drop-in"))
        assertTrue(plan.echoSeconds > 0.0, "drop-in keeps its echo tail")
        assertFalse(plan.bassSwap)
        // Kick-locked anchor: first downbeat at or after the first beat.
        val error = KickLock.kickLockErrorMs(plan.incomingCueTime, 60.0 / 109.0, inn.downbeats)
        assertTrue(error < 1.0, "anchor must be kick locked: " + plan.incomingCueTime)
    }

    @Test
    fun beatlessBridgeRescuesBeatlessEdges() {
        val (out, inn) = bridgePair()
        val plan = plan(out, inn)
        assertEquals(TransitionStyle.BEATLESS_BRIDGE, plan.transitionStyle, "reason=" + plan.reason)
        assertTrue(plan.reason.contains("beatless-bridge"))
        assertTrue(plan.fadeSeconds in 4.0..12.0, "bridge seconds=" + plan.fadeSeconds)
        assertTrue(plan.vocalHandoff)
        assertEquals(0.25, plan.filterSweep, 1e-9)
        assertTrue(plan.exitBeats > 0, "bridge must carry a return span, got " + plan.exitBeats)
    }

    @Test
    fun curveLessDeclineKeepsBoundedFade() {
        // No low-band curves anywhere: no family may answer.
        val bareOut = build("o", 120.0, "C major").copy(lowEnergyCurve = emptyList())
        val bareIn = build("i", 94.0, "F major", vocalProbability = 0.9).copy(lowEnergyCurve = emptyList())
        val c = TransitionAlternatives.select(bareOut, bareIn, 196.0, 200.0, 10.0, 0.5)
        assertNull(c, "no curves, no alternatives")
        val plan = plan(bareOut, bareIn)
        assertEquals(TransitionStyle.DJ_FILTER, plan.transitionStyle, "reason=" + plan.reason)
        assertEquals(4.0, plan.fadeSeconds, 1e-9)
    }

    @Test
    fun muffleRefusesVoicesThatTradePlaces() {
        // Incoming already loud at the swap: a muffle would trade one
        // singer for another, so it refuses (muffle.js swapVoice).
        val out = build("out", 120.0, "C major")
        val inn = build("inn", 109.0, "C major")
        assertNull(TransitionAlternatives.planMuffle(out, inn, 196.0, 200.0, 10.0, 0.5))
        assertFalse(TransitionAlternatives.muffleFits(out, inn, 0.5, 196.0))
    }

    @Test
    fun muffleRefusesEnergyCollapse() {
        // A nine dB energy drop past the muffle: the new beat would sound
        // weak after the old one.
        val out = build("out", 120.0, "C major", bassAt = { 1.0 })
        val inn = build("inn", 109.0, "C major", bassAt = { 0.35 }, maskAt = { if (it < 5.5) 0.1 else 0.9 })
        assertNull(TransitionAlternatives.planMuffle(out, inn, 196.0, 200.0, 10.0, 0.5),
            "a 9 dB drop must refuse the muffle")
    }

    @Test
    fun bridgePromotesClashingKeys() {
        // REWRITTEN (vc71 gate fix): severe clash used to veto the bridge
        // ("keys refuse the bridge"). That was backwards — a tritone is
        // precisely the case the bridge exists for, since it parks both
        // decks so no two keys ever sound together. Clash now promotes.
        val out = build("out", 120.0, "C major", vocalProbability = 0.9, bassAt = { if (it >= 180.0) 0.05 else 1.0 }, maskAt = { 0.0 })
        val inn = build("inn", 94.0, "Eb major", vocalProbability = 0.9, bassAt = { if (it < 8.0) 0.05 else 1.0 }, maskAt = { 0.0 })
        assertTrue(TransitionAlternatives.harmonicSevereClash(out, inn), "fixture must be a severe clash")
        assertTrue(TransitionAlternatives.beatlessBridgeFits(out, inn, 196.0))
        assertEquals(
            "severe clash prefers the bridge",
            TransitionAlternatives.beatlessBridgeGates(out, inn, 196.0)!!["harmonic-clash"],
        )
    }

    /** The 164/90 tritone: outgoing 10A, incoming 5A, beatless edges, high confidence. */
    private fun tritonePair(): Pair<TrackAnalysis, TrackAnalysis> {
        val out = build(
            "crescendo", 164.0, "B minor", vocalProbability = 0.9,
            bassAt = { if (it >= 180.0) 0.05 else 1.0 }, maskAt = { 0.0 },
        )
        val inn = build(
            "bubble-tea", 90.0, "C minor", vocalProbability = 0.9,
            bassAt = { if (it < 8.0) 0.05 else 1.0 }, maskAt = { 0.0 },
        )
        return out to inn
    }

    @Test
    fun severeClashWithTempoGapRoutesToBridgeNotDecline() {
        // (a) severe clash + tempo gap: the pair the forensics measured
        // (164 BPM / 10A -> 90 BPM / 5A). Post-fold delta 9.76% declines,
        // and the bridge must rescue it instead of the generic decline.
        val (out, inn) = tritonePair()
        assertTrue(shouldDeclineToMix(out, inn), "fixture must decline first")
        assertTrue(TransitionAlternatives.harmonicSevereClash(out, inn), "fixture must clash severely")
        val candidate = TransitionAlternatives.select(out, inn, 196.0, 200.0, 10.0, 60.0 / 164.0)
        assertNotNull(candidate, "a severe-clash pair with beatless edges must fit a rescue")
        assertEquals("beatless-bridge", candidate.kind)
        val plan = plan(out, inn)
        assertEquals(TransitionStyle.BEATLESS_BRIDGE, plan.transitionStyle, "reason=" + plan.reason)
        assertEquals(1.0, plan.incomingPlaybackRate, 1e-9)
    }

    @Test
    fun severeClashWithTempoGapAndNoBeatlessEdgesRoutesToMuffle() {
        // (a) with no beatless edges the bridge physically cannot run, so
        // the muffle — the other clash-promoted family — takes the pair.
        val out = build("crescendo", 164.0, "B minor", vocalProbability = 0.9)
        val inn = build(
            "bubble-tea", 90.0, "C minor", vocalProbability = 0.9,
            maskAt = { if (it < 5.5) 0.1 else 0.9 },
        )
        assertTrue(shouldDeclineToMix(out, inn), "fixture must decline first")
        assertFalse(
            TransitionAlternatives.beatlessBridgeFits(out, inn, 196.0),
            "constant bass has no beatless edges",
        )
        val plan = plan(out, inn)
        assertEquals(TransitionStyle.MUFFLE, plan.transitionStyle, "reason=" + plan.reason)
    }

    @Test
    fun severeClashWithTempoMatchStaysOnBlend() {
        // (b) severe clash + tempo match: no decline fires (the veto needs a
        // tempo gap), so the rescue nest never competes and the pair stays
        // on the beatmatched pedal. Parking both decks would waste a
        // matchable pair.
        val out = build("out", 120.0, "B minor", vocalProbability = 0.9)
        val inn = build("inn", 120.0, "C minor", vocalProbability = 0.9)
        assertTrue(TransitionAlternatives.harmonicSevereClash(out, inn))
        assertFalse(shouldDeclineToMix(out, inn), "matching tempo must not decline")
        val plan = plan(out, inn)
        assertTrue(
            plan.transitionStyle == TransitionStyle.DJ_BLEND || plan.transitionStyle == TransitionStyle.DJ_FILTER,
            "reason=" + plan.reason + " style=" + plan.transitionStyle,
        )
        assertFalse(plan.reason.contains("decline-to-mix"), "reason=" + plan.reason)
    }

    @Test
    fun mildClashWithTempoGapKeepsExistingOrder() {
        // (c) mild clash (1 step) + tempo gap + dual vocal: declines via
        // dual-vocal, and the rankScore order is untouched by the severe
        // inversion (muffle still wins on score).
        val out = build("out", 120.0, "C major", vocalProbability = 0.9)
        val inn = build(
            "inn", 109.0, "F major", vocalProbability = 0.9,
            maskAt = { if (it < 5.5) 0.1 else 0.9 },
        )
        assertTrue(shouldDeclineToMix(out, inn), "dual-vocal gap must decline")
        assertFalse(TransitionAlternatives.harmonicSevereClash(out, inn), "1 step is mild")
        val plan = plan(out, inn)
        assertEquals(TransitionStyle.MUFFLE, plan.transitionStyle, "reason=" + plan.reason)
    }

    @Test
    fun curveLessNoClashDeclineKeepsBoundedCarve() {
        // (d) no clash, no curves: no family can physically run, so the
        // bounded decline plays — now the DECLINE carve, still at native
        // tempo (rate 1.0) with the existing filter envelopes.
        TransitionEvidenceCache.clear()
        val bareOut = build("o", 120.0, "C major")
        val bareIn = build("i", 109.0, "C major").copy(lowEnergyCurve = emptyList())
        val bareOutCut = bareOut.copy(lowEnergyCurve = emptyList())
        assertTrue(shouldDeclineToMix(bareOutCut, bareIn), "dual-vocal gap must decline")
        val plan = plan(bareOutCut, bareIn)
        assertEquals(TransitionStyle.DJ_FILTER, plan.transitionStyle, "reason=" + plan.reason)
        assertEquals(FilterTransitionVariant.DECLINE, plan.filterVariant)
        assertEquals(4.0, plan.fadeSeconds, 1e-9)
        assertEquals(1.0, plan.incomingPlaybackRate, 1e-9)
        assertTrue(plan.bassSwap, "the carve performs the one-kick handoff")
        assertEquals("decline", TransitionEvidenceCache.recent(1)[0].style)
        TransitionEvidenceCache.clear()
    }

    @Test
    fun declineEnvelopeHasComplementaryGainsAndUnchangedFilters() {
        // Positive fader overlap and constant squared-gain sum are policy
        // contracts; the overlapping filter corners do not prove listening quality.
        val outLp = { p: Double -> TransitionAlternatives.declineOutLpAt(p, 1.0) }
        val inHp = { p: Double -> TransitionAlternatives.declineInHpAt(p, 1.0) }
        val outGain = { p: Double -> TransitionAlternatives.declineOutGainAt(p) }
        val inGain = { p: Double -> TransitionAlternatives.declineInGainAt(p) }
        assertEquals(7000.0, outLp(0.0), 1e-6)
        assertTrue(outLp(0.5) < 1000.0, "midpoint LP=" + outLp(0.5))
        assertEquals(400.0, outLp(1.0), 1e-6)
        assertEquals(2800.0, inHp(0.0), 1e-6)
        assertTrue(inHp(0.5) > 300.0, "midpoint HP=" + inHp(0.5))
        assertEquals(20.0, inHp(1.0), 1e-6)
        assertTrue(outLp(0.5) > inHp(0.5), "midpoint filter bands overlap")
        assertEquals(1.0, outGain(0.0), 1e-6)
        assertEquals(0.0, inGain(0.0), 1e-6)
        assertEquals(0.0, outGain(1.0), 1e-6)
        assertEquals(1.0, inGain(1.0), 1e-6)
        assertEquals(kotlin.math.sqrt(0.5), outGain(0.5), 1e-6)
        assertEquals(kotlin.math.sqrt(0.5), inGain(0.5), 1e-6)
        var previousOut = outGain(0.0)
        var previousIn = inGain(0.0)
        var overlapSamples = 0
        for (i in 0..100) {
            val p = i / 100.0
            val outgoing = outGain(p)
            val incoming = inGain(p)
            // Float sin/cos permits a tiny negative outgoing endpoint.
            assertTrue(outgoing in -1e-6..1.0 && incoming in 0.0..1.0, "bounded gains at p=$p")
            assertTrue(outgoing <= previousOut && incoming >= previousIn, "monotonic gains at p=$p")
            assertEquals(1.0, outgoing * outgoing + incoming * incoming, 2e-6, "power at p=$p")
            if (outgoing >= 0.5 && incoming >= 0.5) overlapSamples++
            previousOut = outgoing
            previousIn = incoming
        }
        assertTrue(overlapSamples >= 30, "both gains >= 0.5 over at least 30% of the sampled ride")
    }

    @Test
    fun octaveRatioNearTwoIsFlaggedInBand() {
        val rel = detectOctaveRelationship(164.0, 82.0)
        assertNotNull(rel)
        assertEquals(0.5, rel.fold, 1e-9)
        assertEquals(1.0, rel.rate, 1e-9)
        assertTrue(rel.inAudibleBand)
    }

    @Test
    fun octaveRatioOutsideAudibleBandIsReportOnly() {
        // Raw 0.539 is within 8% of a half, but the fold demands 0.928 —
        // outside 0.94..1.09 — so no correction is applied, only reported.
        val rel = detectOctaveRelationship(100.0, 53.9)
        assertNotNull(rel)
        assertFalse(rel.inAudibleBand)
        assertEquals(107.8, alignTempoOctave(100.0, 53.9), 1e-9)
        assertEquals(0.9278, rel.rate, 1e-3)
    }

    @Test
    fun nonOctavePairIsNotFlagged() {
        // The forensics pair folds to a 9.8% gap; raw 0.549 is not an
        // octave ratio, so the detector stays silent.
        assertNull(detectOctaveRelationship(164.0, 90.0))
        assertNull(detectOctaveRelationship(120.0, 109.0))
        assertNull(detectOctaveRelationship(0.0, 90.0))
    }

    @Test
    fun octaveGateDescribesBothOutcomes() {
        val inBand = TransitionAlternatives.octaveGateDescription(
            build("o", 164.0, "B minor"), build("i", 82.0, "C minor"),
        )
        assertNotNull(inBand)
        assertTrue(inBand.startsWith("fold=") && inBand.endsWith("in-band"), inBand)
        val reportOnly = TransitionAlternatives.octaveGateDescription(
            build("o", 100.0, "C major"), build("i", 53.9, "C major"),
        )
        assertNotNull(reportOnly)
        assertTrue(reportOnly.endsWith("report-only"), reportOnly)
        assertNull(
            TransitionAlternatives.octaveGateDescription(
                build("o", 120.0, "C major"), build("i", 109.0, "C major"),
            ),
        )
    }

    @Test
    fun selectRanksByConfidenceAndFlow() {
        val (out, inn) = dropPair()
        val candidate = TransitionAlternatives.select(out, inn, 196.0, 200.0, 10.0, 60.0 / 120.0)
        assertNotNull(candidate)
        assertEquals("drop-in", candidate.kind)
        assertTrue(candidate.rankScore >= candidate.confidence * 0.82 - 1e-9)
        assertTrue(candidate.flow.coverage >= 0.0)
    }

    @Test
    fun alternativesRecordEvidence() {
        TransitionEvidenceCache.clear()
        val (out, inn) = mufflePair()
        val plan = plan(out, inn)
        assertEquals(TransitionStyle.MUFFLE, plan.transitionStyle)
        val recent = TransitionEvidenceCache.recent(1)
        assertTrue(recent.isNotEmpty(), "a decline rescue must record evidence")
        assertEquals("muffle", recent[0].style)
        TransitionEvidenceCache.clear()
    }

    @Test
    fun kickLockedAnchorUsesDownbeats() {
        val inn = build("inn", 109.0, "C major")
        val anchor = TransitionAlternatives.incomingAnchor(inn, 60.0 / 109.0)
        assertTrue(anchor >= 2.5 - 1e-9, "anchor=" + anchor)
        assertTrue(KickLock.isKickLocked(anchor, 60.0 / 109.0, inn.downbeats))
    }

    @Test
    fun weakRegionalKeyFallsBackToConfidentGlobalKeyForDeclineAndClash() {
        val out = build("out", 120.0, "Ab major", keyConfidence = 0.78, vocalProbability = 0.1, maskAt = { 0.0 })
            .copy(keyTail = "C minor", keyTailConfidence = 0.12)
        val inn = build("inn", 109.0, "E major", keyConfidence = 0.85, vocalProbability = 0.1, maskAt = { 0.0 })
            .copy(keyHead = "A minor", keyHeadConfidence = 0.10)
        assertEquals(0.78, exitKeyConfidence(out), 1e-9)
        assertEquals(0.85, entryKeyConfidence(inn), 1e-9)
        assertTrue(shouldDeclineToMix(out, inn), "confident global clash must decline")
        val detail = declineToMixDetail(out, inn)
        assertNotNull(detail)
        assertTrue(detail.contains("steps="), "detail=$detail")
        assertFalse(detail.contains("steps=?"), "detail=$detail must carry numeric steps")
        assertTrue(TransitionAlternatives.harmonicSevereClash(out, inn))
    }
}
