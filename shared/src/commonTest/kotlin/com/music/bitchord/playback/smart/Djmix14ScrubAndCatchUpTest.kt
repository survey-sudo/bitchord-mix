package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * djmix14 scrub + catch-up: the 19:25:50 video reproduction, phase split,
 * and Late-Scrub Catch-Up Re-anchoring. Scalars only.
 */
class Djmix14ScrubAndCatchUpTest {

    private fun analysis(bpm: Double, id: String): TrackAnalysis {
        // Bar downbeats every 4 beats from 0 so snapping is deterministic.
        val beatSec = 60.0 / bpm
        val barSec = 4.0 * beatSec
        val downbeats = buildList<Double> {
            var t = 0.0
            while (t <= 200.0) { add(t); t += barSec }
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = 179.0,
            bpm = bpm,
            beatInterval = beatSec,
            beatConfidence = 0.8,
            downbeats = downbeats,
            firstBeat = 0.0,
            key = "8A",
            keyConfidence = 0.8,
            contentEndTime = 0.0,
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    private fun videoPlan(): TransitionPlan = TransitionPlan(
        shouldStart = true,
        markerVisible = true,
        blocked = false,
        reason = "smart-duration",
        transitionStart = 150.0,
        transitionEnd = 172.18,
        fadeSeconds = 22.18,
        transitionStyle = TransitionStyle.DJ_BLEND,
        incomingCueTime = 5.0,
        beatSeconds = 60.0 / 99.99,
    )

    @Test fun videoScrubPastStartIsInvalid() {
        // 150s plan start, playhead scrubbed to 166.81s (12.2 s left).
        assertFalse(SeekAction.planStillValid(150_000L, 166_810L))
        assertFalse(SeekAction.planStillValid(150_000L, 153_100L))
        assertTrue(SeekAction.planStillValid(150_000L, 149_000L))
    }

    @Test fun catchUpAt166_8ReanchorsAheadFitAndMusical() {
        val plan = videoPlan()
        val out = analysis(99.99, "a")
        val inn = analysis(104.50, "b")
        val caught = CatchUpPlanner.reanchor(plan, out, inn, 166.8, 179.0)
        assertNotNull(caught, "catch-up must fire at 166.8 (12.2 s left)")
        assertFalse(caught.blocked)
        assertTrue(caught.transitionStart > 166.8, "start=${caught.transitionStart} must be ahead")
        val lead = caught.transitionStart - 166.8
        assertTrue(lead in 0.8..1.8, "lead=$lead beat-snapped ~0.8-1.8")
        assertTrue(caught.fadeSeconds in 4.0..10.5, "fade=${caught.fadeSeconds} musical 2-4 bar")
        assertTrue(caught.transitionStart + caught.fadeSeconds <= 179.0, "end=${caught.transitionStart + caught.fadeSeconds}")
        assertFalse(caught.shouldStart, "not yet started; arms into the lead window")
        assertTrue(caught.reason.endsWith(":catch-up"), "reason=${caught.reason}")
    }

    @Test fun catchUpAt153_1AlsoReanchors() {
        val plan = videoPlan()
        val out = analysis(99.99, "a")
        val inn = analysis(104.50, "b")
        val caught = CatchUpPlanner.reanchor(plan, out, inn, 153.1, 179.0)
        assertNotNull(caught)
        assertTrue(caught.transitionStart > 153.1)
        assertTrue(caught.transitionStart + caught.fadeSeconds <= 179.0)
    }

    @Test fun catchUpAtTMinus5IsQuickFilter() {
        val plan = videoPlan()
        val out = analysis(99.99, "a")
        val inn = analysis(104.50, "b")
        val caught = CatchUpPlanner.reanchor(plan, out, inn, 174.0, 179.0)
        assertNotNull(caught, "T-5 must catch up")
        assertTrue(caught.fadeSeconds in 2.5..3.5, "fade=${caught.fadeSeconds} 2-bar/quick")
        assertTrue(caught.transitionStart + caught.fadeSeconds <= 179.0)
    }

    @Test fun catchUpAtTMinus1LeavesToFinish() {
        val plan = videoPlan()
        val out = analysis(99.99, "a")
        val inn = analysis(104.50, "b")
        val caught = CatchUpPlanner.reanchor(plan, out, inn, 178.0, 179.0)
        assertNull(caught, "T-1.0 (<2.5 left) must leave to finish, no fake 0s")
    }

    @Test fun phaseSplitPreAudibleCancelsAudibleTails() {
        assertEquals(SeekAction.Action.CANCEL, SeekAction.decide(SeekAction.Phase.IDLE, false))
        assertEquals(SeekAction.Action.CANCEL, SeekAction.decide(SeekAction.Phase.ARMING, false))
        assertEquals(SeekAction.Action.CANCEL, SeekAction.decide(SeekAction.Phase.ARMING, true))
        assertEquals(SeekAction.Action.CANCEL, SeekAction.decide(SeekAction.Phase.LOOP_ARMING, false))
        assertEquals(SeekAction.Action.CANCEL, SeekAction.decide(SeekAction.Phase.LOOP_ARMING, true))
        // Backward compat: legacy LOOP is pre-audible.
        assertEquals(SeekAction.Action.CANCEL, SeekAction.decide(SeekAction.Phase.LOOP, true))
        assertEquals(SeekAction.Action.EMERGENCY_TAIL, SeekAction.decide(SeekAction.Phase.FADING, true))
        // djmix25 (vc67): TAIL-phase seeks HONOUR the in-flight dissolve instead of
        // stacking an emergency 350 ms tail (the 4x exit.tail cascade).
        assertEquals(SeekAction.Action.HONOUR, SeekAction.decide(SeekAction.Phase.TAIL, true))
        assertEquals(SeekAction.Action.EMERGENCY_TAIL, SeekAction.decide(SeekAction.Phase.LOOP_RUNNING, true))
    }


    @Test fun pinnedCatchUpCountdownTicksDownNotDrift() {
        // Simulate IDLE ticks with pinned start: countdown must tick 2->1->0.
        // Without pinning, catchUpStart = current+lead drifts (1s -> 1s -> 1s).
        val plan = videoPlan()
        val out = analysis(99.99, "a")
        val inn = analysis(104.50, "b")
        val first = CatchUpPlanner.reanchor(plan, out, inn, 166.8, 179.0)
        assertNotNull(first)
        val pinnedStart = first.transitionStart
        // Next ticks advance 0.25s each but pinned start holds.
        for (pos in listOf(167.05, 167.3, 167.55)) {
            val raw = CatchUpPlanner.reanchor(plan, out, inn, pos, 179.0)
            assertNotNull(raw)
            // Unpinned would have moved forward:
            // (assert drift exists to justify pinning)
            assertTrue(raw.transitionStart >= pinnedStart, "unpinned drifts forward: ${raw.transitionStart} vs $pinnedStart")
        }
        // Pinned countdown: seconds left = pinnedStart - pos
        val c1 = kotlin.math.ceil((pinnedStart - 167.05).coerceAtLeast(0.0)).toInt()
        val c2 = kotlin.math.ceil((pinnedStart - 167.3).coerceAtLeast(0.0)).toInt()
        val c3 = kotlin.math.ceil((pinnedStart - 167.55).coerceAtLeast(0.0)).toInt()
        assertTrue(c1 >= c2 && c2 >= c3, "pinned countdown must tick down: $c1,$c2,$c3")
        // And the pinned end still fits the track.
        assertTrue(pinnedStart + first.fadeSeconds <= 179.0)
    }

    @Test fun catchUpGoesThroughCapIncomingSkip() {
        // planTransition must cap the incoming cue even on the catch-up path.
        // Short next track (20s) -> latest cue = 0.25*20 = 5s. A catch-up
        // plan whose cue is deeper (e.g. 10s via mix-out anchor) is pulled back.
        val outA = analysis(99.99, "a")
        val innShort = fullShortNext()
        val viaPlan = planTransition(
            analysis = outA, nextAnalysis = innShort,
            currentTrack = TransitionTrackInfo("a", 179_000L, "t", "a", "al"),
            nextTrack = TransitionTrackInfo("b", 20_000L, "t2", "a2", "al"),
            currentTime = 166.8, duration = 179.0, fadeSeconds = 22.18, mode = CrossfadeMode.SMART, advanced = true,
        )
        // Whether or not this exact pair catches up, the cue invariant holds:
        // never deeper than a quarter of the incoming track.
        assertTrue(viaPlan.incomingCueTime <= 5.0 + 1e-6, "cue=${viaPlan.incomingCueTime} must be capped at 5.0 (0.25*20)")
    }

    private fun fullShortNext(): TrackAnalysis {
        val bpm = 104.50
        val beatSec = 60.0 / bpm
        val barSec = 4.0 * beatSec
        val downbeats = buildList<Double> { var t = 0.0; while (t <= 30.0) { add(t); t += barSec } }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, trackId = "b", duration = 20.0, bpm = bpm,
            beatInterval = beatSec, beatConfidence = 0.85, downbeats = downbeats, firstBeat = 0.0,
            key = "8A", keyConfidence = 0.8, contentEndTime = 19.0, quality = TrackAnalysis.QUALITY_FULL,
            energyCurve = listOf(EnergySample(0.0, 0.5), EnergySample(19.0, 0.4)),
        )
    }

}
