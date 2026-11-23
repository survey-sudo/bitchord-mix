package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeEchoCueTest {
    private fun analysis(bpm: Double = 100.0, vocal: (Double) -> Float = { 0f }) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY, duration = 100.0, bpm = bpm, beatInterval = 60.0 / bpm,
        beatConfidence = 0.9, downbeats = List(42) { it * 2.4 }, contentEndTime = 98.0,
        vocalProbability = 0.9, vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(2000) { vocal(it / 20.0) })),
    )

    private fun timing() = assertNotNull(nativeTiming(90.0, 90.8, 92.0, 92.4,
        0.0, 0.0, 0.0, 2.4, 0.0, 4.0))

    @Test fun unityCoordinatesAndColdCueAreExact() {
        val h = timing()
        assertEquals(2.4, h.inHoldMixSec)
        assertEquals(h.inHoldMixSec, h.inAudibleStart.mixSec)
        assertEquals(h.inAudibleStart.mixSec, h.inFullLevelMixSec)
        assertEquals(h.outDryEnd, h.outSendClose)
        assertEquals(2.0, h.outDryFallStart.mixSec)
        assertNull(nativeTiming(90.0, 91.0, 92.0, 92.4, 1.0, 0.0, 2.0, 2.4, 0.0, 4.0))
        assertNull(nativeTiming(90.0, 91.0, 92.0, Double.NaN, 0.0, 0.0, 0.0, 2.4, 0.0, 4.0))
    }

    @Test fun contractBoundsClampPhysicalPrerollWithoutMovingColdCuesOrLegacyBounds() {
        val h = assertNotNull(nativeTiming(0.25, 0.5, 1.8, 2.25,
            0.0, 0.0, 0.0, 2.0, 0.0, 4.0))
        val plan = LoopMix.Plan(
            out = LoopMix.Pick(0.25, 0.25, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
            inn = LoopMix.Pick(0.0, 0.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
            fold = 1.0, enterBars = 1, rampBars = 0, exitBars = 1, totalBars = 2,
            outLoops = false, inLoops = false, nativeIncoming = true, echoTail = true,
            rung = "Echo", incomingHoldSec = h.inHoldMixSec, handoff = h,
        )
        val bounds = LoopMix.regionBounds(plan)
        assertEquals(0.0, bounds.outFrom)
        assertEquals(0.0, bounds.inFrom)
        assertEquals(0.0, plan.inn.loopStart)
        assertEquals(0.0, h.inCueSourceSec)
        assertEquals(0.0, LoopMix.Clock(plan).inTrackTimeAt(h.inHoldMixSec))
        val legacy = LoopMix.regionBounds(plan.copy(handoff = null))
        assertEquals(-0.75, legacy.outFrom)
        assertEquals(-1.0, legacy.inFrom)
        assertEquals(legacy.outTo, bounds.outTo)
        assertEquals(legacy.inTo, bounds.inTo)
        val cache = LoopRegionCache()
        cache.put("cold", 10, 0.0, bounds.inTo,
            FloatArray((bounds.inTo * 10).toInt()), FloatArray((bounds.inTo * 10).toInt()))
        assertTrue(cache.contains("cold", 10, bounds.inFrom, bounds.inTo))
    }

    @Test fun lyricAcrossCutRejectsEvenWhenSetupIsClean() {
        val q = qualifyMusicalHandoff(timing(), analysis(), analysis(), listOf(LoopMix.Span(91.9, 92.6)), emptyList())
        assertEquals(HandoffStatus.UNSAFE, q.status)
        assertEquals("out-cut-certain-lyric", q.reason)
    }

    @Test fun aContractCannotHideAProtectedAttackUnderAnOpeningFade() {
        val h = assertNotNull(nativeTiming(90.0, 90.8, 92.0, 92.4,
            0.0, 0.0, 1.0, 3.4, 0.2, 4.0))
        val q = qualifyMusicalHandoff(h, analysis(), analysis().copy(audibleStartTime = 0.0), emptyList(), emptyList())
        assertEquals(HandoffStatus.UNSAFE, q.status)
        assertEquals("in-entry-protected-attack", q.reason)
    }

    @Test fun completedSungCaptureIsAllowedAndUnknownIsNotClean() {
        val a = analysis { if (it in 90.8..91.4) 0.8f else 0f }
        assertEquals(HandoffStatus.QUALIFIED, qualifyMusicalHandoff(timing(), a, analysis(),
            listOf(LoopMix.Span(90.8, 91.4)), emptyList()).status)
        val missing = qualifyMusicalHandoff(timing(), a.copy(vocalWindows = emptyList()), analysis(), emptyList(), emptyList())
        assertEquals(HandoffStatus.UNKNOWN, missing.status)
        assertNull(missing.outCutMaskCoverage)
        val nonFinite = a.copy(vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(2000) { Float.NaN })))
        assertEquals(HandoffStatus.UNKNOWN, qualifyMusicalHandoff(timing(), nonFinite, analysis(), emptyList(), emptyList()).status)
    }

    @Test fun disjointBurstsAreNotSimultaneousVocals() {
        val h = assertNotNull(nativeTiming(90.0, 90.2, 92.0, 92.4, 0.0, 0.0, 1.9, 2.4, 0.0, 4.0))
        val out = analysis { if (it >= 90.6 && it < 91.0) 0.8f else 0f }
        val inn = analysis { if (it >= 0.8 && it < 1.2) 0.8f else 0f }
        assertEquals(HandoffStatus.QUALIFIED, qualifyMusicalHandoff(h, out, inn, emptyList(), emptyList()).status)
        val simultaneous = analysis { if (it >= 0.1 && it < 0.5) 0.8f else 0f }
        assertEquals("handoff-dual-vocal", qualifyMusicalHandoff(h, out, simultaneous, emptyList(), emptyList()).reason)
        val lyricsOnlyClash = qualifyMusicalHandoff(h, analysis(), analysis(),
            listOf(LoopMix.Span(90.6, 91.0)), listOf(LoopMix.Span(0.1, 0.5)))
        assertEquals("handoff-dual-vocal", lyricsOnlyClash.reason)
    }

    @Test fun nativePlannerMovesCutPastProtectedLyricAndKeepsPickup() {
        val out = analysis().copy(phraseBoundaries = listOf(95.6, 97.0)).withMeasuredEchoEvidence()
        val inn = analysis(135.0).copy(pickupTime = 0.2, downbeats = List(56) { 0.5 + it * 240.0 / 135.0 }).withMeasuredEchoEvidence()
        val sung = listOf(LoopMix.Span(95.0, 96.0))
        val plan = assertNotNull(LoopMix.bridge(out, 100.0, inn, 100.0, 80.0, sung, emptyList()))
        assertEquals("Echo", plan.rung)
        val h = assertNotNull(plan.handoff)
        assertTrue(h.outDryFallStart.sourceSec >= 96.08 - 1e-8)
        assertEquals(0.2, h.inCueSourceSec)
        assertEquals(HandoffStatus.QUALIFIED, qualifyMusicalHandoff(h, out, inn, sung, emptyList()).status)
        val cold = inn.copy(pickupTime = null, audibleStartTime = 0.125)
        val coldPlan = assertNotNull(LoopMix.bridge(out, 100.0, cold, 100.0, 80.0, sung, emptyList()))
        assertEquals(0.125, coldPlan.handoff!!.inCueSourceSec)
        assertEquals(coldPlan.handoff!!.inAudibleStart.mixSec, coldPlan.handoff!!.inFullLevelMixSec)
    }

    @Test fun noMeasuredExitPlaysToPhysicalEndWithoutClaimingCleanCoverage() {
        val out = analysis().copy(vocalWindows = emptyList())
        val inn = analysis(135.0).copy(vocalWindows = emptyList())
        val plan = assertNotNull(LoopMix.bridge(out, 100.0, inn, 100.0, 80.0))
        val h = assertNotNull(plan.handoff)
        // vc92: natural cut honours contentEndTime (98.0) when narrower than
        // container EOF, instead of playing to 100.0.
        assertEquals(98.0, h.outDryEnd.sourceSec)
        val clock = LoopMix.Clock(plan)
        assertTrue(clock.releaseAt + 1e-9 >= h.outDryEnd.mixSec,
            "Natural cut must be inside reserved straight playback")
        assertTrue(clock.releaseAt + 1e-9 >= h.inLanding.mixSec,
            "Incoming landing must be inside reserved clock")
        assertTrue(clock.releaseAt + 1e-9 >= h.wetRetireMixSec,
            "Natural route must not reserve an unmeasured wet tail")
        assertTrue(clock.elementAt <= PhraseConfig.O_MAX_SEC + 1e-6,
            "Natural handoff plus settle stays bounded")
        assertEquals("Natural handoff", plan.rung)
        assertEquals("natural-end", plan.alternativeKind)
        assertEquals(false, plan.echoTail)
        assertEquals(false, plan.reverbWash)
        assertEquals(h.outDryEnd, h.outDryFallStart)
        assertTrue(h.wetRetireMixSec <= h.outDryEnd.mixSec + 1e-9,
            "Natural fallback must not reserve ornamental effect drain")
        assertEquals(HandoffStatus.UNKNOWN, qualifyMusicalHandoff(h, out, inn, null, null).status)
        assertEquals(1.0, qualifyMusicalHandoff(h, out, inn, null, null).overlapMaskCoverage)
    }

    @Test fun qualifiedEchoReservesClockThroughWetRetirement() {
        val out = analysis().copy(vocalWindows = listOf(CurveWindow(0.0, 20.0,
            FloatArray(2000) { if (it / 20.0 < 95.0) 0.8f else 0f }))).withMeasuredEchoEvidence()
        val inn = analysis(135.0).copy(audibleStartTime = 0.0,
            downbeats = List(56) { it * 240.0 / 135.0 },
            vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(2000)))).withMeasuredEchoEvidence()
        val plan = assertNotNull(LoopMix.bridge(out, out.duration, inn, inn.duration,
            earliestOutSec = 80.0, sungOut = emptyList(), sungIn = emptyList()))
        assertEquals("Echo", plan.rung)
        assertEquals(true, plan.echoTail)
        val handoff = assertNotNull(plan.handoff)
        assertEquals(HandoffStatus.QUALIFIED,
            qualifyMusicalHandoff(handoff, out, inn, emptyList(), emptyList()).status)
        val clock = LoopMix.Clock(plan)
        val reservedBars = kotlin.math.ceil(maxOf(handoff.outDryEnd.mixSec,
            handoff.inLanding.mixSec, handoff.wetRetireMixSec) / plan.out.beatSec / 4.0 - 1e-9).toInt()
        assertEquals(reservedBars, plan.totalBars)
        assertTrue(clock.releaseAt + 1e-9 >= handoff.outDryEnd.mixSec,
            "dry=${handoff.outDryEnd.mixSec} release=${clock.releaseAt}")
        assertTrue(clock.releaseAt + 1e-9 >= handoff.inLanding.mixSec,
            "landing=${handoff.inLanding.mixSec} release=${clock.releaseAt}")
        assertTrue(clock.releaseAt + 1e-9 >= handoff.wetRetireMixSec,
            "wet=${handoff.wetRetireMixSec} release=${clock.releaseAt}")
        assertTrue(clock.elementAt <= PhraseConfig.O_MAX_SEC + 1e-6)
    }
}
