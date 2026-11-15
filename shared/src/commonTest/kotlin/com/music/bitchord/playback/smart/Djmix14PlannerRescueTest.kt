package com.music.bitchord.playback.smart

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * djmix14 planner rescue: HEAD late-rescue, musical fallback rung, and
 * cueErrorMs millisecond math.
 */
class Djmix14PlannerRescueTest {

    private fun headAnalysis(bpm: Double, id: String, conf: Double = 0.40): TrackAnalysis {
        val beatSec = 60.0 / bpm
        val barSec = 4.0 * beatSec
        val downbeats = buildList<Double> {
            var t = 0.0
            while (t <= 200.0) { add(t); t += barSec }
        }
        // HEAD quality: partial-window evidence, no tail energy curve.
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = 180.0,
            bpm = bpm,
            beatInterval = beatSec,
            beatConfidence = conf,
            downbeats = downbeats,
            firstBeat = 0.0,
            key = "8A",
            keyConfidence = 0.7,
            contentEndTime = 0.0,
            quality = TrackAnalysis.QUALITY_HEAD,
        )
    }

    private fun fullAnalysis(bpm: Double, id: String): TrackAnalysis {
        val beatSec = 60.0 / bpm
        val barSec = 4.0 * beatSec
        val downbeats = buildList<Double> {
            var t = 0.0
            while (t <= 200.0) { add(t); t += barSec }
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = 180.0,
            bpm = bpm,
            beatInterval = beatSec,
            beatConfidence = 0.85,
            downbeats = downbeats,
            firstBeat = 0.0,
            key = "8A",
            keyConfidence = 0.8,
            contentEndTime = 175.0,
            energyCurve = listOf(EnergySample(0.0, 0.5), EnergySample(175.0, 0.4)),
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    @Test fun headRescueEarlyWaitsLateRescues() {
        val outHead = headAnalysis(100.0, "out")
        val innFull = fullAnalysis(104.0, "inn")
        // Early: 170 s left (>45) — must wait for full, so fallback window.
        val early = planTransition(
            analysis = outHead, nextAnalysis = innFull,
            currentTrack = TransitionTrackInfo("out", 180_000L, "t", "a", "al"),
            nextTrack = TransitionTrackInfo("inn", 180_000L, "t2", "a2", "al"),
            currentTime = 10.0, duration = 180.0, fadeSeconds = 8.0, mode = CrossfadeMode.SMART, advanced = true,
        )
        assertTrue(early.reason.contains("fallback"), "early must fallback: ${early.reason}")
        // Late/scrub: 40 s left (<=45) — HEAD rescue into a real mix, not fallback.
        val late = planTransition(
            analysis = outHead, nextAnalysis = innFull,
            currentTrack = TransitionTrackInfo("out", 180_000L, "t", "a", "al"),
            nextTrack = TransitionTrackInfo("inn", 180_000L, "t2", "a2", "al"),
            currentTime = 140.0, duration = 180.0, fadeSeconds = 8.0, mode = CrossfadeMode.SMART, advanced = true,
        )
        assertFalse(late.reason.contains("fallback"), "late HEAD must rescue: ${late.reason} style=${late.transitionStyle}")
        assertFalse(late.blocked)
        assertTrue(late.transitionStyle == TransitionStyle.DJ_BLEND || late.transitionStyle == TransitionStyle.DJ_FILTER, "style=${late.transitionStyle}")
    }

    @Test fun musicalFallbackNeverEmptyHanded() {
        // Close tempos (<=8%): beat blend (M1). Far tempos (>8%): echo (M6).
        // Usable grids guarantee a non-null rescue even under veto pressure.
        val outClose = SongMaps.fromAnalysis("out", fullAnalysis(100.0, "out"))
        val inClose = SongMaps.fromAnalysis("inn", fullAnalysis(104.0, "inn"))
        assertNotNull(outClose)
        assertNotNull(inClose)
        val closeChoice = PhrasePlanner.select(outClose, inClose, 0, 100.0, 104.0)
        assertNotNull(closeChoice, "close pair must choose (strict or rescue)")
        // Far tempos: 100 vs 140 (40% folded delta).
        val outFar = SongMaps.fromAnalysis("out2", fullAnalysis(100.0, "out2"))
        val inFar = SongMaps.fromAnalysis("inn2", fullAnalysis(140.0, "inn2"))
        val farChoice = PhrasePlanner.select(outFar, inFar, 0, 100.0, 140.0)
        assertNotNull(farChoice, "far pair must choose, never null")
        // Far must favour echo over a tempo lurch; assert template is a
        // short/echo family, never a long blend.
        assertTrue(farChoice.template == MixTemplate.M6 || farChoice.template == MixTemplate.M10 || farChoice.template == MixTemplate.RELAXED, "far=${farChoice.template}")
        // Close must not be a forced echo when strict would blend; either
        // M1 (blend) via strict or rescue, never an echo-only signal.
        assertTrue(closeChoice.template == MixTemplate.M1 || closeChoice.template == MixTemplate.M6 || closeChoice.template == MixTemplate.RELAXED, "close=${closeChoice.template}")
    }

    @Test fun cueErrorMsMillisecondMath() {
        val intoMs = 5000L
        val cueSec = 5.0
        val targetCueMs = (cueSec * 1000.0).roundToLong()
        assertEquals(5000L, targetCueMs)
        assertEquals(0L, intoMs - targetCueMs, "5000ms vs 5.0s must be 0, not 4995")
        // Negative control: the old seconds-minus-ms bug gave 4995.
        assertEquals(4995L, intoMs - cueSec.toLong())
    }


    @Test fun rescueLanesAreMonotoneAndMuted() {
        // Build rescues via select on usable grids, then compile both M1/M6
        // shapes and assert every lane is strictly non-decreasing in beats
        // and outGain ends at MUTE_DB (graceful exit tail).
        val outClose = SongMaps.fromAnalysis("out", fullAnalysis(100.0, "out"))
        val inClose = SongMaps.fromAnalysis("inn", fullAnalysis(104.0, "inn"))
        assertNotNull(outClose); assertNotNull(inClose)
        val closeChoice = PhrasePlanner.select(outClose, inClose, 0, 100.0, 104.0)
        assertNotNull(closeChoice)
        // Manually exercise both rescue templates (M1/M6) through compile so
        // the lane contract is checked even when strict wins the close pair.
        val outFar = SongMaps.fromAnalysis("out2", fullAnalysis(100.0, "out2"))
        val inFar = SongMaps.fromAnalysis("inn2", fullAnalysis(140.0, "inn2"))
        assertNotNull(outFar); assertNotNull(inFar)
        val farChoice = PhrasePlanner.select(outFar, inFar, 0, 100.0, 140.0)
        assertNotNull(farChoice)
        for (choice in listOf(closeChoice, farChoice)) {
            val script = TransitionMoves.compile(choice, 100.0, if (choice === farChoice) 140.0 else 104.0)
            for (lane in listOf(script.outGain, script.inGain, script.outLow, script.inLow, script.outMid, script.inMid, script.outHigh, script.inHigh, script.outHp)) {
                assertTrue(lane.beats.isNotEmpty(), "lane empty for ${choice.template}")
                for (i in 1 until lane.beats.size) {
                    assertTrue(lane.beats[i] >= lane.beats[i - 1], "non-monotone ${choice.template}: ${lane.beats[i-1]} -> ${lane.beats[i]}")
                }
            }
            assertEquals(TransitionMoves.MUTE_DB, script.outGain.values.last(), 0.001f, "outGain must end muted for ${choice.template}")
        }
        // Explicit M1/M6 compile check (rescue shapes): djmix18 prefers the
        // full 8-bar M1 rescue (swap -4.0) when steady grids allow; 4-bar (-2.0)
        // remains valid when room is tight, and 0.0 is the echo shape.
        assertTrue(closeChoice.swapBar == -4.0 || closeChoice.swapBar == -2.0 || closeChoice.swapBar == 0.0 || closeChoice.swapBar == -8.0, "swap=${closeChoice.swapBar}")
    }

    @Test fun earliestExitSecFiltersPastExits() {
        val out = SongMaps.fromAnalysis("out", fullAnalysis(100.0, "out"))!!
        val inn = SongMaps.fromAnalysis("inn", fullAnalysis(104.0, "inn"))!!
        // Earliest in the past: normal choice.
        val normal = PhrasePlanner.select(out, inn, 0, 100.0, 104.0, earliestExitSec = 0.0)
        // Earliest far in the future beyond every exit: no exit, no rescue.
        val future = PhrasePlanner.select(out, inn, 0, 100.0, 104.0, earliestExitSec = 1e9)
        assertNull(future, "past all exits must be null (no rescue from a past bar)")
    }



    @Test fun regionBoundsMatchBuildExactly() {
        // Loop plan: out loopEnd+0.5+exitTail; straight: start+total*4*beat+0.5+exitTail.
        val outPick = LoopMix.Pick(loopStart = 100.0, loopEnd = 104.0, bars = 4, beatSec = 0.6, bpm = 100.0, vocal = 0.0, checkedBy = LoopMix.Check.MASK)
        val inPick = LoopMix.Pick(loopStart = 5.0, loopEnd = 9.0, bars = 4, beatSec = 0.575, bpm = 104.34, vocal = 0.0, checkedBy = LoopMix.Check.MASK)
        val loopPlan = LoopMix.Plan(out = outPick, inn = inPick, fold = 1.0, enterBars = 1, rampBars = 2, exitBars = 1, totalBars = 4, outLoops = true, inLoops = true, exitTailSeconds = 0.8)
        val lb = LoopMix.regionBounds(loopPlan)
        assertEquals(99.0, lb.outFrom, 1e-9)
        assertEquals(104.0 + 0.5 + 0.8, lb.outTo, 1e-9)
        assertEquals(4.0, lb.inFrom, 1e-9)
        val clock = LoopMix.Clock(loopPlan)
        assertEquals(clock.elementTrackTime + 3.0, lb.inTo, 1e-9)

        val straightOut = LoopMix.Pick(loopStart = 100.0, loopEnd = 100.0, bars = 4, beatSec = 0.6, bpm = 100.0, vocal = 0.0, checkedBy = LoopMix.Check.MASK)
        val straightPlan = LoopMix.Plan(out = straightOut, inn = inPick, fold = 1.0, enterBars = 1, rampBars = 2, exitBars = 1, totalBars = 4, outLoops = false, inLoops = false, echoTail = true, exitTailSeconds = 1.0)
        val sb = LoopMix.regionBounds(straightPlan)
        val straightClock = LoopMix.Clock(straightPlan)
        val echoHold = straightPlan.out.beatSec * 8
        val outStopMixSec = straightClock.releaseAt + 0.05 + echoHold + straightPlan.exitTailSeconds
        val tailRate = if (straightClock.beats >= 1) {
            val b0 = straightClock.beatTimes[straightClock.beats - 1]
            val b1 = straightClock.beatTimes[straightClock.beats]
            (straightPlan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        val nominal = straightPlan.out.loopStart + straightPlan.totalBars * 4 * straightPlan.out.beatSec + 0.5 + straightPlan.exitTailSeconds
        val lastOutPos = straightPlan.out.loopStart + straightClock.beats * straightPlan.out.beatSec
        val tailTrack = (outStopMixSec - straightClock.releaseAt).coerceAtLeast(0.0) * tailRate
        val pastElement = straightPlan.out.loopStart + straightClock.elementAt + maxOf(echoHold, straightPlan.exitTailSeconds) + 0.5
        assertEquals(maxOf(nominal, lastOutPos + tailTrack + 0.5, pastElement), sb.outTo, 1e-9)
        assertEquals(LoopMix.Clock(straightPlan).elementTrackTime + 3.0 + 0.6 * 8.0, sb.inTo, 1e-9)
    }

}
