package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NaturalHandoffPolicyTest {
    private fun analysis(bpm: Double, outgoing: Boolean): TrackAnalysis {
        val beat = 60.0 / bpm
        val times = if (outgoing) List(41) { 160.0 + it * beat } else List(33) { it * beat }
        val side = BeatSide(times, times.filterIndexed { i, _ -> i % 4 == 0 }, bpm, .9)
        return TrackAnalysis(bpm = bpm, beatInterval = beat, beatConfidence = .9,
            duration = 180.0, audibleStartTime = 0.0, phraseBoundaries = listOf(178.0),
            headBeats = if (outgoing) null else side, tailBeats = if (outgoing) side else null,
            vocalWindows = listOf(CurveWindow(0.0, 1.0, FloatArray(181))))
    }

    private fun handoff() = assertNotNull(nativeTiming(176.0, 176.75, 177.625, 178.0,
        0.0, 0.0, 0.0, 2.0, 0.0, 4.0))

    @Test fun musicalEvidenceIsSeparateFromVocalSafety() {
        val h = handoff()
        val out = analysis(120.0, true)
        val inn = analysis(80.0, false)
        assertEquals(HandoffStatus.QUALIFIED, qualifyMusicalHandoff(h, out, inn, null, null).status)
        assertNull(EchoAdmission.musicalRefusal(h, out, inn))
        assertNotNull(EchoAdmission.musicalRefusal(h, out.copy(tailBeats = null), inn))
        assertEquals(HandoffStatus.QUALIFIED,
            qualifyMusicalHandoff(h, out.copy(tailBeats = null), inn, null, null).status)
    }

    @Test fun invalidOrDiscontinuousLocalGridsAreNotCleanEvidence() {
        val out = analysis(120.0, true)
        val side = assertNotNull(out.tailBeats)
        val invalid = listOf(
            side.copy(confidence = Double.NaN),
            side.copy(beats = side.beats + Double.NaN),
            side.copy(beats = listOf(175.0, 175.5, 175.5, 176.0, 176.5, 177.0, 177.5, 178.0)),
            side.copy(beats = listOf(175.0, 175.5, 176.0, 177.5, 178.0, 178.5)),
            side.copy(beats = side.beats.take(4)),
        )
        for (grid in invalid) assertNotNull(EchoAdmission.musicalRefusal(handoff(),
            out.copy(tailBeats = grid), analysis(80.0, false)))
    }

    @Test fun coveredPickupBeforeItsFirstMeasuredBeatKeepsTheSelectedCue() {
        val out = analysis(120.0, true)
        val inn = analysis(80.0, false).copy(
            headBeats = BeatSide(List(12) { .75 + it * .75 }, listOf(.75, 3.75, 6.75), 80.0, .9),
            pickupTime = 0.0,
        )
        val h = assertNotNull(nativeTiming(176.0, 176.75, 177.625, 178.0,
            0.0, 0.0, .75, 2.75, 0.0, 4.0))
        assertNull(EchoAdmission.musicalRefusal(h, out, inn))
        assertEquals(0.0, h.inCueSourceSec)
    }

    @Test fun cleanLocalPulseWithoutAnExitAnchorStillDoesNotEarnEcho() {
        val out = analysis(120.0, true)
        val noAnchor = out.copy(phraseBoundaries = emptyList(), mixOutTime = 0.0,
            tailBeats = assertNotNull(out.tailBeats).copy(downbeats = emptyList()))
        assertEquals("echo-no-measured-exit", EchoAdmission.musicalRefusal(handoff(), noAnchor, analysis(80.0, false)))
    }

    @Test fun protectedLyricsRemainAVetoEvenWithMusicalEvidence() {
        val h = handoff()
        val out = analysis(120.0, true)
        val inn = analysis(80.0, false)
        assertNull(EchoAdmission.musicalRefusal(h, out, inn))
        assertEquals(HandoffStatus.UNSAFE,
            qualifyMusicalHandoff(h, out, inn, listOf(LoopMix.Span(177.0, 179.0, 179.0)), null).status)
    }

    @Test fun recoveryCrossfadeRequiresExplicitOptInAndCarriesNoEcho() {
        val natural = planTransition(duration = 180.0, currentTime = 170.0, mode = CrossfadeMode.SMART)
        assertEquals(TransitionStyle.NATURAL, natural.transitionStyle)
        val recovery = planTransition(duration = 180.0, currentTime = 170.0,
            mode = CrossfadeMode.SMART, allowRecoveryCrossfade = true)
        assertEquals(TransitionStyle.EQUAL_POWER, recovery.transitionStyle)
        assertTrue(recovery.fadeSeconds > 0.0)
        assertEquals(DjEffect.NONE, recovery.djEffect)
        assertEquals(0.0, recovery.echoSeconds, 0.0)
    }

    @Test fun naturalAdvanceCannotBeDecoratedOrResurrectedAsACrossfade() {
        val natural = naturalHandoffPlan(180.0, "insufficient-evidence")
        val annotated = DjMix.annotate(natural, analysis(120.0, true), analysis(80.0, false),
            outgoingId = "out", incomingId = "in")
        assertEquals(natural, annotated)
        val stabilizer = CountdownStabilizer()
        val old = TransitionPlan(markerVisible = true, transitionStart = 174.0,
            transitionEnd = 180.0, fadeSeconds = 6.0)
        stabilizer.consider(CountdownStabilizer.Candidate("a->b", 0,
            CountdownStabilizer.Family.CLASSIC, "fade", 174_000L, 180_000L), 170_000L)
        val kept = stabilizer.stabilizeClassicPlan("a->b", 0, natural, old, true, 173_000L, 180.0)
        assertEquals(TransitionStyle.NATURAL, kept.transitionStyle)
        assertEquals(0.0, kept.fadeSeconds, 0.0)
        assertFalse(kept.markerVisible)
        assertFalse(kept.shouldStart)
    }

    @Test fun recoveryRequiresAnActualCommittedNativeFailureForThisOpportunity() {
        val stabilizer = CountdownStabilizer()
        stabilizer.onRungFailed("a->b", 0L, CountdownStabilizer.Family.NATIVE_BRIDGE)
        assertFalse(stabilizer.hasFailedCommittedNative("a->b", 0L))
        stabilizer.reset()
        stabilizer.consider(CountdownStabilizer.Candidate("a->b", 0L,
            CountdownStabilizer.Family.NATIVE_BRIDGE, "Echo", 175_000L, 180_000L), 170_000L)
        stabilizer.onRungFailed("a->b", 0L, CountdownStabilizer.Family.NATIVE_BRIDGE)
        assertTrue(stabilizer.hasFailedCommittedNative("a->b", 0L))
        assertFalse(stabilizer.hasFailedCommittedNative("a->b", 1L))
        assertFalse(stabilizer.hasFailedCommittedNative("c->d", 0L))
        stabilizer.clearFailedNativeRecovery()
        assertFalse(stabilizer.hasFailedCommittedNative("a->b", 0L))
        stabilizer.reset()
        assertFalse(stabilizer.hasFailedCommittedNative("a->b", 0L))
    }
}
