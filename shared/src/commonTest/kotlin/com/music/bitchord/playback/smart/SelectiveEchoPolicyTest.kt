package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelectiveEchoPolicyTest {
    private fun measured(bpmValue: Double, outgoing: Boolean): TrackAnalysis {
        val beat = 60.0 / bpmValue
        val start = if (outgoing) 160.0 else 0.0
        val beats = List(if (outgoing) 41 else 33) { start + it * beat }
        val side = BeatSide(beats, beats.filterIndexed { index, _ -> index % 4 == 0 }, bpmValue, .9)
        return TrackAnalysis(
            bpm = bpmValue, beatInterval = beat, beatConfidence = .9,
            duration = 180.0, status = "ready", quality = TrackAnalysis.QUALITY_FULL,
            contentEndTime = 180.0, audibleStartTime = 0.0,
            phraseBoundaries = if (outgoing) listOf(178.0) else emptyList(),
            tailBeats = if (outgoing) side else null,
            headBeats = if (outgoing) null else side,
            vocalWindows = listOf(CurveWindow(0.0, 1.0, FloatArray(181))),
        )
    }

    private fun bridge(out: TrackAnalysis, inn: TrackAnalysis) =
        assertNotNull(LoopMix.bridge(out, 180.0, inn, 180.0, 150.0))

    @Test fun cleanMasksAndConfidentBpmAloneDoNotEarnEcho() {
        val out = measured(120.0, true).copy(tailBeats = null)
        val inn = measured(80.0, false).copy(headBeats = null)
        val plan = bridge(out, inn)
        assertEquals("Natural handoff", plan.rung)
        assertEquals("natural-end", plan.alternativeKind)
        assertFalse(plan.echoTail)
        assertFalse(plan.reverbWash)
        // vc92: Bug 1a clamps natural cut 60 ms inside container EOF.
        assertEquals(179.94, assertNotNull(plan.handoff).outDryEnd.sourceSec, 1e-8)
    }

    @Test fun measuredLocalGridsAndPhraseExitKeepMusicalEcho() {
        val plan = bridge(measured(120.0, true), measured(80.0, false))
        assertEquals("Echo", plan.rung)
        assertTrue(plan.echoTail)
        assertEquals(178.0, assertNotNull(plan.handoff).outDryEnd.sourceSec, 1e-8)
    }

    @Test fun remoteTrackedBeatsDoNotCertifyAnUnmeasuredExit() {
        val out = measured(120.0, true).copy(
            tailBeats = BeatSide(List(20) { it * .5 }, listOf(0.0, 2.0), 120.0, .9),
        )
        assertEquals("Natural handoff", bridge(out, measured(80.0, false)).rung)
    }

    @Test fun unknownAnalysisUsesNormalEndRatherThanAutomaticCrossfade() {
        val plan = planTransition(duration = 180.0, currentTime = 170.0,
            mode = CrossfadeMode.SMART, advanced = true)
        assertEquals("NATURAL", plan.transitionStyle.name)
        assertFalse(plan.blocked)
        assertFalse(plan.shouldStart)
        assertFalse(plan.markerVisible)
        assertEquals(180.0, plan.transitionStart, 0.0)
        assertEquals(180.0, plan.transitionEnd, 0.0)
        assertEquals(0.0, plan.fadeSeconds, 0.0)
        assertEquals(0.0, plan.incomingCueTime, 0.0)
        assertEquals(1.0, plan.incomingPlaybackRate, 0.0)
        assertEquals(1.0, plan.outgoingPlaybackRate, 0.0)
        assertEquals(DjEffect.NONE, plan.djEffect)
        assertEquals(0.0, plan.echoSeconds, 0.0)
    }

    @Test fun explicitStandardCrossfadeRemainsAvailable() {
        val plan = planTransition(duration = 180.0, currentTime = 100.0,
            fadeSeconds = 6.0, mode = CrossfadeMode.STANDARD)
        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertEquals(6.0, plan.fadeSeconds, 1e-8)
    }

    @Test fun missingEvidenceDoesNotTurnIntoACatchUpCrossfadeNearTheEnd() {
        val plan = planTransition(duration = 180.0, currentTime = 175.0,
            mode = CrossfadeMode.SMART, advanced = true)
        assertEquals("NATURAL", plan.transitionStyle.name)
        assertEquals(0.0, plan.fadeSeconds, 0.0)
        assertEquals(180.0, plan.transitionEnd, 0.0)
    }
}
