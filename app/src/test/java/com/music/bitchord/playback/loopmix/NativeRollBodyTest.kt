package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.DjEffect
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionMoves
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class NativeRollBodyTest {
    private val rate = 8_000
    private fun region(from: Double, to: Double) = ceil((to - from) * rate).toInt().let {
        LoopRegion(FloatArray(it) { 0.1f }, FloatArray(it) { -0.1f }, from)
    }
    private fun knots(plan: LoopMix.Plan, analysis: TrackAnalysis? = null): LoopMixSession.Companion.DeckKnots {
        val clock = LoopMix.Clock(plan)
        return LoopMixSession.buildDeckKnots(plan, clock, rate, analysis, null, false, clock.releaseAt, 1.0)
    }

    @Test fun realStraightM1AndM3BuildupsHaveFourBeatNativeBoundsWithoutMovingTheTakeover() {
        for (template in listOf(MixTemplate.M1, MixTemplate.M3)) for (bpm in listOf(110.0, 128.0, 150.0)) {
            val script = TransitionMoves.compile(soundPolishChoice(template, vocal = 0f), bpm, bpm)
            val plan = script.plan
            assertTrue("EDM $template should request its roll", plan.buildupRoll)
            assertFalse(plan.outLoops)
            assertEquals("straight pick is not itself a fake loop", plan.out.loopStart, plan.out.loopEnd, 1e-9)
            val clock = LoopMix.Clock(plan)
            val k = knots(plan)
            val bounds = LoopMix.regionBounds(plan)
            val body = LoopMixSession.outgoingRollBody(plan, clock, k, region(bounds.outFrom, bounds.outTo), rate)
            assertEquals(DjEffect.LOOP_ROLL, body.effect)
            assertEquals(4.0, (body.endTrack - body.startTrack) / body.beatSec, 1e-8)
            assertEquals(clock.beatTimes[clock.beats - 16], body.startMixSec, 1e-9)
            assertEquals(k.outPositionsSec[clock.beats - 16], body.startTrack, 1e-9)
            assertEquals(k.outPositionsSec[clock.beats - 12], body.endTrack, 1e-9)
            assertTrue("a late body leaves pre-roll straight", body.startTrack > plan.out.loopStart)
            assertTrue(body.endTrack < k.outPositionsSec[clock.beats])
            assertSame(plan, LoopMixSession.planForRollBody(plan, body))
            assertEquals("no takeover/cue rewrite", script.choice.exit.releaseSec,
                plan.out.loopStart + clock.beats * plan.out.beatSec, 1e-8)
        }
    }

    @Test fun rollBodyUsesTheActualBeatKnotsRatherThanSyntheticAdvertisedBounds() {
        val plan = TransitionMoves.compile(soundPolishChoice(MixTemplate.M3, vocal = 0f), 128.0, 128.0).plan
        val clock = LoopMix.Clock(plan)
        val localBeat = plan.out.beatSec + 0.003
        val grid = List(clock.beats + 1) { plan.out.loopStart + it * localBeat }
        val k = knots(plan, TrackAnalysis(tailBeats = BeatSide(grid, emptyList(), 60 / localBeat, .9)))
        val body = LoopMixSession.outgoingRollBody(plan, clock, k, region(plan.out.loopStart - 1, grid.last() + 1), rate)
        assertEquals(DjEffect.LOOP_ROLL, body.effect)
        assertEquals(grid[clock.beats - 16], body.startTrack, 1e-9)
        assertEquals(grid[clock.beats - 12], body.endTrack, 1e-9)
        assertEquals(localBeat, body.beatSec, 1e-9)
    }

    @Test fun absentTruncatedMalformedOrNonBeatAlignedBodiesBecomeExplicitNoneOnTheActualSessionPlan() {
        val plan = TransitionMoves.compile(soundPolishChoice(MixTemplate.M1, vocal = 0f), 120.0, 120.0).plan
        val clock = LoopMix.Clock(plan)
        val k = knots(plan)
        val startBeat = clock.beats - 16
        val from = plan.out.loopStart - 1
        val end = k.outPositionsSec[startBeat + 4]
        val truncated = region(from, end - 0.1)
        // Straight dry/FX drain is still legal. Only the unsupported roll is
        // downgraded; never manufacture a body by repeating incomplete PCM.
        assertTrue(LoopMixSession.coversOutgoingStartAndLoop(truncated, rate, plan))
        val good = region(from, end + 0.05)
        val invalid = listOf(
            LoopRegion(FloatArray(0), FloatArray(0), from), truncated,
            region(from, end), // no 8 ms seam continuation
            LoopRegion(good.left, good.right.copyOf(good.right.size - 1), from),
            region(k.outPositionsSec[startBeat] + 0.01, end + 0.05),
        )
        for (pcm in invalid) {
            val body = LoopMixSession.outgoingRollBody(plan, clock, k, pcm, rate)
            assertEquals(DjEffect.NONE, body.effect)
            val effective = LoopMixSession.planForRollBody(plan, body)
            assertFalse("published session must not advertise an unarmed roll", effective.buildupRoll)
            assertEquals(plan.out, effective.out)
            assertEquals(plan.inn, effective.inn)
            assertSame(plan.outLp, effective.outLp)
            assertEquals(plan.incomingHoldSec, effective.incomingHoldSec, 1e-9)
        }
        for (bad in listOf(Double.NaN, k.outPositionsSec[startBeat + 1], k.outPositionsSec[startBeat + 2] + .06)) {
            val positions = k.outPositionsSec.copyOf().also { it[startBeat + 2] = bad }
            val malformed = LoopMixSession.Companion.DeckKnots(k.outFrames, positions, k.inFrames, k.inPositionsSec)
            assertEquals(DjEffect.NONE, LoopMixSession.outgoingRollBody(plan, clock, malformed, good, rate).effect)
        }
        assertEquals(DjEffect.NONE, LoopMixSession.outgoingRollBody(plan.copy(buildupRoll = false), clock, k, good, rate).effect)
        println("Native straight roll fallback: empty/truncated/seam-less/unequal/late/NaN/irregular bodies -> NONE; cue, clock and LP retained")
    }
}
