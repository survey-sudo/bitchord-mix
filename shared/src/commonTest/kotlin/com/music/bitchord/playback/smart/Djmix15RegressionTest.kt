package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * djmix15 regression: the five vc56 live-device faults.
 *
 * Scalars only: BPM, Camelot, LUFS, vocal counts, deadlines and positions.
 * No audio tensors cross decks.
 */
class Djmix15RegressionTest {

    private fun analysis(bpm: Double, id: String, durationSec: Double = 179.0, contentEnd: Double = 0.0): TrackAnalysis {
        val beatSec = 60.0 / bpm
        val barSec = 4.0 * beatSec
        val downbeats = buildList<Double> {
            var t = 0.0
            while (t <= 220.0) { add(t); t += barSec }
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = durationSec,
            bpm = bpm,
            beatInterval = beatSec,
            beatConfidence = 0.8,
            downbeats = downbeats,
            firstBeat = 0.0,
            key = "8A",
            keyConfidence = 0.8,
            contentEndTime = contentEnd,
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    // ---- Bug 3: lateStepReason can never report wall-minus-media epochs ----

    @Test fun lateStepReasonNeverReportsWallMinusMedia374320() {
        // Device: "outgoing splice 374320 ms over" / "388005 ms over"
        // growing 1:1 with wall clock. The deadline was 0 (uninitialized
        // DeckTiming) or stale from a prior track — a wall epoch subtracted
        // from zero/a dead deadline, not build lateness.
        val now = 374_320L
        // Uninitialized 0 deadline with a bogus span ending at wall now.
        val zeroSpan = LoopMix.PrepSpan("outgoing splice", 0L, now, 0L)
        assertEquals(0L, DeckBudget.provenOverrunMs(zeroSpan.endedMs, zeroSpan.deadlineMs))
        assertFalse(DeckBudget.isProvablyLate(zeroSpan))
        val zeroReason = LoopMix.lateStepReason(listOf(zeroSpan), now, 0L)
        assertFalse(zeroReason.contains("374320"), "reason=$zeroReason must not subtract boot uptime")

        // Stale wall deadline from a prior track: 374320 ms behind now.
        val staleDeadline = now - 374_320L
        val staleReason = LoopMix.lateStepReason(emptyList(), now, staleDeadline)
        assertFalse(staleReason.contains("374320"), "reason=$staleReason must not report stale wall")
        assertTrue(staleReason.contains("paused"), "reason=$staleReason")

        // Media deadline passed as wall (loopTargetStartMs 170120 vs wall
        // 500000): the media epoch is ~330000 behind wall, beyond the budget
        // horizon — also no budget, never a wall-minus-media number.
        val mediaAsWall = 170_120L
        val mediaReason = LoopMix.lateStepReason(emptyList(), 500_000L, mediaAsWall)
        assertFalse(mediaReason.contains("329880"), "reason=$mediaReason")
        assertTrue(mediaReason.contains("paused"), "reason=$mediaReason")

        // Genuine, bounded lateness still reports (17 ms over, DeckBudgetTest).
        val genuineDeadline = DeckBudget.deadlineMs(0L, true, 1_000L, 0L)
        val genuineSpan = LoopMix.PrepSpan("outgoing splice", 0L, genuineDeadline + 17L, genuineDeadline)
        assertEquals("outgoing splice 17 ms over", LoopMix.lateStepReason(listOf(genuineSpan), genuineDeadline + 17L, genuineDeadline))

        // Paused/no-deadline stays paused.
        assertTrue(LoopMix.lateStepReason(listOf(LoopMix.PrepSpan("x", 0L, now, DeckBudget.NO_DEADLINE_MS)), now, DeckBudget.NO_DEADLINE_MS).contains("paused"))
    }

    @Test fun provenOverrunTreatsZeroDeadlineAsNoBudget() {
        assertEquals(0L, DeckBudget.provenOverrunMs(999_999L, 0L))
        assertEquals(0L, DeckBudget.provenOverrunMs(999_999L, DeckBudget.NO_DEADLINE_MS))
        // A real deadline still measures.
        assertEquals(100L, DeckBudget.provenOverrunMs(1_100L, 1_000L))
    }

    // ---- Bug 5a: CatchUpPlanner quick-blend floor lowered to 2.0 / 1.2 ----

    @Test fun catchUpQuickBlendAt2Point2Seconds() {
        val plan = TransitionPlan(
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
        val out = analysis(99.99, "a", 180.0)
        val inn = analysis(104.50, "b", 180.0)
        // 2.2 s remain (current 177.8 / 180): before djmix15 this returned
        // null (<2.5) and played dry; now 0.6 s lead + 1.2-1.6 s dissolve.
        val caught = CatchUpPlanner.reanchor(plan, out, inn, 177.8, 180.0)
        assertNotNull(caught, "2.2 s room must catch up")
        assertTrue(caught.transitionStart > 177.8, "start=${caught.transitionStart}")
        val lead = caught.transitionStart - 177.8
        assertEquals(0.6, lead, 0.01, "near-EOF lead is 0.6, was ${lead}")
        assertTrue(caught.fadeSeconds in 1.2..1.6, "fade=${caught.fadeSeconds} quick dissolve")
        assertTrue(caught.transitionStart + caught.fadeSeconds <= 180.0 + 1e-6)
        assertFalse(caught.shouldStart)
        assertTrue(caught.reason.endsWith(":catch-up"))
    }

    @Test fun catchUpRefusesBelow2Point0() {
        val plan = TransitionPlan(
            shouldStart = true, markerVisible = true, blocked = false, reason = "smart-duration",
            transitionStart = 140.0, transitionEnd = 148.021, fadeSeconds = 8.021,
            transitionStyle = TransitionStyle.DJ_FILTER, incomingCueTime = 2.0, beatSeconds = 0.6,
        )
        val out = analysis(100.0, "a", 148.021)
        val inn = analysis(104.0, "b", 148.021)
        // 1.9 s remain: no lead + dissolve can breathe — null so planner blocks.
        assertNull(CatchUpPlanner.reanchor(plan, out, inn, 146.121, 148.021))
        // 0.18 s before EOF (seq169): also null.
        assertNull(CatchUpPlanner.reanchor(plan, out, inn, 147.841, 148.021))
    }

    // ---- Bug 5b: planTransition applies catch-up to standard plans ----

    @Test fun standardLateScrubReanchorsInsteadOfDeadWindow() {
        // S.A.D shape: stale start 178046 ms, playhead 22.7 s past it. Force
        // the standard early return (blank analysis → smart-analysis-fallback)
        // and require a re-anchored start ahead of the playhead — never the
        // 22.563 s stale window with deltaMs=+22717.
        val out = analysis(104.495, "out", 200.609)
        val inn = analysis(66.484, "inn", 200.609)
        // Blank analyses force standardTransition; BPMs on the tracks above
        // still give reanchor a grid via out/in analyses passed to reanchor?
        // planTransitionInner passes the blank TrackAnalysis pair to
        // reanchor, which falls back to 120 BPM — still must re-anchor.
        // Use blank directly to mirror the device's qualityB=none fallback.
        val blankOut = TrackAnalysis()
        val blankIn = TrackAnalysis()
        val plan = planTransition(
            analysis = blankOut, nextAnalysis = blankIn,
            currentTrack = TransitionTrackInfo("out", 180_000L, "t", "a", "al"),
            nextTrack = TransitionTrackInfo("inn", 180_000L, "t2", "a2", "al"),
            currentTime = 176.5, duration = 180.0, fadeSeconds = 8.0, mode = CrossfadeMode.SMART, advanced = false,
            allowRecoveryCrossfade = true, // exercise the last-resort recovery contract, not the default policy
        )
        // Standard start was length-fade = 172.0; playhead 176.5 is 4.5 s late
        // with 3.5 s left — must be re-anchored ahead, not the dead window.
        assertFalse(plan.blocked)
        assertTrue(plan.transitionStart > 176.5, "start=${plan.transitionStart} must re-anchor ahead of 176.5 (stale was 172.0)")
        assertTrue(plan.reason.endsWith(":catch-up"), "reason=${plan.reason}")
        assertTrue(plan.transitionStart + plan.fadeSeconds <= 180.0 + 1e-6)
        // Direct stale-standard replica: a base window starting at 178.046
        // with the playhead 22.7 s past it (device: 200.696) must re-anchor
        // ahead instead of emitting the dead 22.563 s window verbatim.
        val staleBase = TransitionPlan(
            shouldStart = true, markerVisible = true, blocked = false, reason = "smart-analysis-fallback",
            transitionStart = 178.046, transitionEnd = 200.609, fadeSeconds = 22.563,
            transitionStyle = TransitionStyle.EQUAL_POWER, incomingCueTime = 0.0,
        )
        val staleOut = analysis(104.495, "out", 222.6)
        val staleIn = analysis(66.484, "inn", 222.6)
        val staleCaught = CatchUpPlanner.reanchor(staleBase, staleOut, staleIn, 200.696, 222.6)
        assertNotNull(staleCaught, "stale 22.7 s window must re-anchor when room allows")
        assertTrue(staleCaught.transitionStart > 200.696, "start=${staleCaught.transitionStart}")
        assertTrue(staleCaught.reason.endsWith(":catch-up"), "reason=${staleCaught.reason}")
        // The companion analyses mirror the device BPMs; the planner's
        // scalars do not cross (patent) — this asserts the helper kept them.
        assertEquals(104.495, out.bpm, 1e-9)
        assertEquals(66.484, inn.bpm, 1e-9)
    }

    @Test fun catchUpViaPlanTransitionPreservesDjAnnotation() {
        // Pilot Check 2: skipping DjMix.annotate would strip :catch-up plans
        // of DJ metadata (djEffect / vocalHandoff / buildup / Camelot /
        // mixSummary for renderFor + describeAutomix). annotate MUST run,
        // with timing guarded inside DjMix (placeOnStructure / clashFilter /
        // ensureOverlap), never by skipping annotate.
        val blankOut = TrackAnalysis()
        val blankIn = TrackAnalysis()
        val plan = planTransition(
            analysis = blankOut, nextAnalysis = blankIn,
            currentTrack = TransitionTrackInfo("out", 180_000L, "t", "a", "al"),
            nextTrack = TransitionTrackInfo("inn", 180_000L, "t2", "a2", "al"),
            currentTime = 176.5, duration = 180.0, fadeSeconds = 8.0, mode = CrossfadeMode.SMART, advanced = false,
            allowRecoveryCrossfade = true,
        )
        // Timing preserved: still re-anchored ahead, not inflated back to 171.6.
        assertFalse(plan.blocked)
        assertTrue(plan.transitionStart > 176.5, "start=${plan.transitionStart} must stay re-anchored")
        assertTrue(plan.reason.endsWith(":catch-up"), "reason=${plan.reason}")
        assertTrue(plan.transitionStart + plan.fadeSeconds <= 180.0 + 1e-6)
        // DjMix annotation preserved: mixSummary is the display line for
        // describeAutomix; blank-key pair annotates to the style alone (e.g.
        // "filter mix...") rather than "" (un-annotated volume fade).
        assertTrue(plan.mixSummary.isNotBlank(), "mixSummary='${plan.mixSummary}' must be DjMix-annotated, not stripped")
        assertTrue(plan.mixSummary.contains("filter", ignoreCase = true) || plan.mixSummary.contains("crossfade", ignoreCase = true) || plan.mixSummary.contains("mix", ignoreCase = true), "mixSummary='${plan.mixSummary}'")
    }

    // ---- Bug 5c: planTransition blocks <2.0 s near-EOF instead of micro-fade ----

    @Test fun planTransitionBlocksTooLateNearEof() {
        // LAST GOODBYE shape: seek to 147.841 / 148.021 (0.18 s left), stale
        // transitionStart ~20 s earlier. Before: shouldStart=true fired a
        // 0.322 s micro-fade. Now: blocked("too-late-near-eof").
        val out = analysis(100.0, "out", 148.021)
        val inn = analysis(104.0, "inn", 148.021)
        val plan = planTransition(
            analysis = out, nextAnalysis = inn,
            currentTrack = TransitionTrackInfo("out", 148_021L, "t", "a", "al"),
            nextTrack = TransitionTrackInfo("inn", 148_021L, "t2", "a2", "al"),
            currentTime = 147.841, duration = 148.021, fadeSeconds = 8.0, mode = CrossfadeMode.SMART, advanced = true,
        )
        assertTrue(plan.blocked, "reason=${plan.reason} start=${plan.transitionStart} fade=${plan.fadeSeconds}")
        assertEquals("too-late-near-eof", plan.reason)
        assertFalse(plan.shouldStart, "must not fire a stale micro-fade at EOF")
    }

    @Test fun planTransitionStillFiresWhenCatchUpHasRoom() {
        // Same pair with room (current 140.0, >8 s left): must NOT block —
        // either a normal future plan or a catch-up 2-4 bar blend.
        val out = analysis(100.0, "out", 148.021)
        val inn = analysis(104.0, "inn", 148.021)
        val plan = planTransition(
            analysis = out, nextAnalysis = inn,
            currentTrack = TransitionTrackInfo("out", 148_021L, "t", "a", "al"),
            nextTrack = TransitionTrackInfo("inn", 148_021L, "t2", "a2", "al"),
            currentTime = 140.0, duration = 148.021, fadeSeconds = 8.0, mode = CrossfadeMode.SMART, advanced = true,
        )
        assertFalse(plan.blocked, "reason=${plan.reason}")
    }

    // ---- Bugs 2 & 4 invariants (pure halves) ----

    @Test fun retireGuardLoopScalarsNeverReportZeroWhenPlanKnown() {
        // finishLoop: outgoing volume 1.0 but renderer at -60 dB
        // (violations=0) is silenced before finish(), so RetireGuard does
        // not trigger; BPMs fall back to the plan.
        assertTrue(RetireGuard.audible(1.0f))
        assertFalse(RetireGuard.audible(0.0f))
        assertTrue(RetireGuard.canRetire(0.0f))
        assertFalse(RetireGuard.canRetire(1.0f))
        // Plan fallback: blank analyses (0.0) never report 0.0 when known.
        assertEquals(104.495, RetireGuard.loopOutBpm(0.0, 104.495), 1e-9)
        assertEquals(66.484, RetireGuard.loopInBpm(0.0, 66.484), 1e-9)
        // Analysed wins when present.
        assertEquals(120.0, RetireGuard.loopOutBpm(120.0, 104.495), 1e-9)
        // Tail follows the plan's graceful-exit seconds.
        assertEquals(2500L, RetireGuard.loopTailMs(2.5))
        assertEquals(800L, RetireGuard.loopTailMs(0.8))
        // Emergency 350 ms floor is unchanged for genuine audible audio.
        assertEquals(350L, RetireGuard.dissolveMs(planned = false))
    }

    @Test fun queueEditAfterHandoffIsNeverAnEdit() {
        // Bug 4: after handoff the session IS track i+1, so next=i+2 must not
        // be judged against armed expected=i+1 (PIN-locked false positive).
        assertFalse(SeekAction.isNextEdit(handedOff = true, nextId = "i+2", expected = "i+1"))
        assertFalse(SeekAction.isNextEdit(handedOff = true, nextId = null, expected = "i+1"))
        // Before handoff a changed upcoming track is still a genuine edit.
        assertTrue(SeekAction.isNextEdit(handedOff = false, nextId = "i+2", expected = "i+1"))
        assertTrue(SeekAction.isNextEdit(handedOff = false, nextId = null, expected = "i+1"))
        // Unchanged next is never an edit either side.
        assertFalse(SeekAction.isNextEdit(handedOff = false, nextId = "i+1", expected = "i+1"))
        assertFalse(SeekAction.isNextEdit(handedOff = true, nextId = "i+1", expected = "i+1"))
        // No expectation, no edit verdict.
        assertFalse(SeekAction.isNextEdit(handedOff = false, nextId = "i+2", expected = null))
    }
}
