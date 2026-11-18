package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.EntryCue
import com.music.bitchord.playback.smart.EntryKind
import com.music.bitchord.playback.smart.ExitCue
import com.music.bitchord.playback.smart.ExitKind
import com.music.bitchord.playback.smart.Lane
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.PhraseChoice
import com.music.bitchord.playback.smart.PhrasePlanner
import com.music.bitchord.playback.smart.SectionFn
import com.music.bitchord.playback.smart.SectionRole
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionMoves
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/** Actual applyPhraseLanes wire arrays, not the JVM renderer's unavailable JNI readout. */
class SoundPolishPhraseLanesTest {
    private val probes = doubleArrayOf(-8.0, -4.0, -2.0, 0.0, 2.0)

    // Independent beat-domain oracle: the native API receives amplitudes for
    // gain, dB for shelves, Hz for filters. Equal-power is not linear amplitude.
    private fun scoreAt(lane: Lane, beat: Double, gain: Boolean): Double {
        val points = lane.beats.indices.sortedBy { lane.beats[it] }.associate {
            lane.beats[it] to if (gain) {
                if (lane.values[it] <= -80f) 0.0 else 10.0.pow(lane.values[it] / 20.0)
            } else lane.values[it].toDouble()
        }.entries.toList()
        if (beat <= points.first().key) return points.first().value
        if (beat >= points.last().key) return points.last().value
        val hi = points.indexOfFirst { it.key >= beat }
        val (t0, a) = points[hi - 1]
        val (t1, b) = points[hi]
        val p = (beat - t0) / (t1 - t0)
        val weight = if (lane.shape == "ep" || lane.shape == "cos") (1.0 - cos(PI * p)) / 2 else p
        return when {
            gain && lane.shape == "ep" -> sqrt(a * a * (1 - weight) + b * b * weight)
            lane.exponential && !gain && a > 0 && b > 0 -> a * (b / a).pow(weight)
            else -> a + (b - a) * weight
        }
    }

    private fun wireAt(env: LoopMixSession.Companion.PhraseEnvelope, time: Double): Double {
        if (time <= env.times.first()) return env.values.first().toDouble()
        if (time >= env.times.last()) return env.values.last().toDouble()
        val hi = env.times.indexOfFirst { it >= time }
        val p = (time - env.times[hi - 1]) / (env.times[hi] - env.times[hi - 1])
        val a = env.values[hi - 1].toDouble()
        val b = env.values[hi].toDouble()
        return if (env.exponential && a > 0 && b > 0) a * (b / a).pow(p) else a + (b - a) * p
    }

    private fun at(clock: LoopMix.Clock, beat: Double): Double {
        val absolute = clock.beats + beat
        if (absolute < 0) return absolute * clock.plan.out.beatSec
        val whole = absolute.toInt()
        if (whole >= clock.beatTimes.lastIndex) {
            return clock.beatTimes.last() + (absolute - clock.beatTimes.lastIndex) * 60 / clock.tempo(clock.beatTimes.lastIndex)
        }
        return clock.beatTimes[whole] + (clock.beatTimes[whole + 1] - clock.beatTimes[whole]) * (absolute - whole)
    }

    @Test fun everyPhraseTemplateGainBassVocalAndFilterMatchesTheSheetAtMinus8Minus4Minus2LandingAndPlus2() {
        var comparisons = 0
        var largestGainError = 0.0
        for (template in MixTemplate.entries) for ((outBpm, inBpm) in listOf(70.0 to 70.0, 100.0 to 108.0, 150.0 to 150.0)) {
            val script = TransitionMoves.compile(soundPolishChoice(template), outBpm, inBpm)
            val clock = LoopMix.Clock(script.plan)
            val envelopes = LoopMixSession.computePhraseEnvelopes(clock, script.plan, script)
            val lanes = listOf(
                Triple(LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN, script.outGain),
                Triple(LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN, script.inGain),
                Triple(LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB, script.outLow),
                Triple(LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB, script.inLow),
                Triple(LoopMixRenderer.OUT, LoopMixRenderer.KIND_MID_DB, script.outMid),
                Triple(LoopMixRenderer.IN, LoopMixRenderer.KIND_MID_DB, script.inMid),
                Triple(LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_DB, script.outHigh),
                Triple(LoopMixRenderer.IN, LoopMixRenderer.KIND_HIGH_DB, script.inHigh),
                Triple(LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ, script.outHp),
                Triple(LoopMixRenderer.IN, LoopMixRenderer.KIND_HIGH_PASS_HZ, script.inHp),
                Triple(LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ, script.outLp),
            ) + if (script.plan.echoTail) listOf(Triple(LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND, script.echoSend)) else emptyList()
            assertSame("Plan must carry the authored LP", script.outLp, script.plan.outLp)
            assertEquals(clock.releaseAt, at(clock, 0.0), 1e-10)
            assertEquals(clock.beats.toDouble(), TransitionMoves.landingBeat(script), 1e-10)
            for ((deck, kind, lane) in lanes) {
                val env = envelopes.single { it.deck == deck && it.kind == kind }
                assertTrue("native times are strictly increasing: $template/$kind",
                    env.times.asList().zipWithNext().all { (a, b) -> b > a })
                val gain = kind == LoopMixRenderer.KIND_GAIN
                for (beat in probes) {
                    val expected = scoreAt(lane, beat, gain)
                    val actual = wireAt(env, at(clock, beat))
                    val error = abs(actual - expected)
                    if (gain) largestGainError = maxOf(largestGainError, error)
                    assertEquals("$template $outBpm->$inBpm deck=$deck kind=$kind L${if (beat < 0) "" else "+"}$beat",
                        expected, actual, maxOf(0.0004, abs(expected) * 0.0005))
                    comparisons++
                }
            }
        }
        println("PHRASE sound-polish: $comparisons gain/bass/vocal/filter samples at ${probes.toList()}; maxGainError=$largestGainError")
    }

    @Test fun shapedLanesUseEqualPowerAndCosineRatherThanLinearAmplitudeOrIgnoredLabels() {
        val script = TransitionMoves.compile(soundPolishChoice(MixTemplate.M1), 120.0, 120.0)
        val clock = LoopMix.Clock(script.plan)
        for (shape in listOf("lin", "ep", "cos")) {
            val lane = Lane(doubleArrayOf(-8.0, 0.0), floatArrayOf(0f, -80f), shape)
            val (times, values) = TransitionMoves.laneEnvelope(lane, clock, asGain = true)
            val env = LoopMixSession.Companion.PhraseEnvelope(0, 0, times, values, false)
            val expected = when (shape) { "ep" -> cos(PI / 8); "cos" -> (1 + cos(PI / 4)) / 2; else -> 0.75 }
            assertEquals("$shape at 1/4 of the span", expected, wireAt(env, at(clock, -6.0)), 0.0004)
            assertEquals("original knot must survive", 0.0, wireAt(env, clock.releaseAt), 1e-8)
        }
        val scriptM6 = TransitionMoves.compile(soundPolishChoice(MixTemplate.M6), 120.0, 90.0)
        val c6 = LoopMix.Clock(scriptM6.plan)
        val send = LoopMixSession.computePhraseEnvelopes(c6, scriptM6.plan, scriptM6).single {
            it.kind == LoopMixRenderer.KIND_ECHO_SEND
        }
        assertEquals("M6's authored cosine echo attack", scoreAt(scriptM6.echoSend, -3.125, false),
            wireAt(send, at(c6, -3.125)), 0.0004)
    }

    @Test fun phraseLpAndVocalLanesAreNotClobberedByTheGenericCarveEvenWhenTheSheetIsFlat() {
        for (template in listOf(MixTemplate.M1, MixTemplate.M3, MixTemplate.M4, MixTemplate.M5, MixTemplate.VOCAL_OVER_OUTRO, MixTemplate.M6)) {
            val script = TransitionMoves.compile(soundPolishChoice(template, vocal = 0.7f), 120.0, 120.0)
            val clock = LoopMix.Clock(script.plan)
            val envs = LoopMixSession.computePhraseEnvelopes(clock, script.plan, script)
            val lp = envs.single { it.deck == LoopMixRenderer.OUT && it.kind == LoopMixRenderer.KIND_LOW_PASS_HZ }
            assertTrue("$template authored full-band LP must replace generic 5500 Hz carve", lp.values.all { it == 20_000f })
            val analysis = TrackAnalysis(vocalProbability = 0.95)
            val sung = listOf(LoopMix.Span(script.plan.inn.loopStart, script.plan.inn.loopStart + 8))
            assertFalse("$template owns its vocal mid lanes even with external vocal evidence",
                LoopMixSession.computeVocalMidCarve(script.plan, clock, script, analysis, sung).apply)
            val mid = envs.single { it.deck == LoopMixRenderer.IN && it.kind == LoopMixRenderer.KIND_MID_DB }
            assertTrue("incoming vocal must stay crisp", mid.values.all { it == 0f })
        }
    }

    @Test fun actualNativeDeckKnotsLandWithin25MillisecondsAtUnityRampedFoldedAndNonBeatmatchedTempos() {
        var cases = 0
        var maxErrorMs = 0.0
        for (template in listOf(MixTemplate.M1, MixTemplate.M3, MixTemplate.M4, MixTemplate.M5, MixTemplate.M6,
            MixTemplate.M7, MixTemplate.M10, MixTemplate.M11, MixTemplate.CUT_ON_ONE, MixTemplate.RELAXED))
        for ((outBpm, inBpm) in listOf(70.0 to 70.0, 100.0 to 108.0, 70.0 to 140.0,
            140.0 to 70.0, 120.0 to 90.0, 150.0 to 150.0))
        for (lead in listOf(0, 4, 8)) {
            val choice = soundPolishChoice(template, leadInBars = lead, pickup = if (lead == 0) 1f else 0f)
            val script = TransitionMoves.compile(choice, outBpm, inBpm)
            val plan = script.plan
            val clock = LoopMix.Clock(plan)
            val outBeat = plan.out.beatSec
            val inBeat = plan.inn.beatSec
            // This is the actual session helper with real BeatSide evidence,
            // which used to overwrite native B's solved cue/hold with A's beat indices.
            val outGrid = TrackAnalysis(tailBeats = BeatSide(List(clock.beats + 1) { plan.out.loopStart + it * outBeat },
                emptyList(), outBpm, 0.9))
            val inGrid = TrackAnalysis(headBeats = BeatSide(List((clock.beats + clock.settleBeats) * 2 + 10) {
                plan.inn.loopStart + it * inBeat
            }, emptyList(), inBpm, 0.9))
            val knots = LoopMixSession.buildDeckKnots(plan, clock, 48_000, outGrid, inGrid, false, clock.releaseAt, 1.0)
            val expectedInLanding = choice.entry.startSec + lead * 4 * inBeat + if (lead == 0) choice.entry.pickupBeats * inBeat else 0.0
            val errorMs = 1000 * abs(knots.inPositionsSec[clock.beats] - expectedInLanding)
            maxErrorMs = maxOf(maxErrorMs, errorMs)
            assertTrue("$template $outBpm->$inBpm lead=$lead landing error=${errorMs}ms", errorMs <= 25.0)
            assertEquals("outgoing selected landing", choice.exit.releaseSec, knots.outPositionsSec[clock.beats], 0.025)
            assertTrue(knots.inPositionsSec.asList().zipWithNext().all { (a, b) -> b >= a })
            assertEquals(clock.releaseAt, TransitionMoves.laneSeconds(clock, 0.0), 1e-10)
            cases++
        }
        // A legacy PHRASE sheet includes enterBars in L too. The old writer
        // silently placed L two bars early by using rampBars + exitBars only.
        val base = TransitionMoves.compile(soundPolishChoice(MixTemplate.M1), 120.0, 120.0).plan
        val legacy = base.copy(enterBars = 2, rampBars = 4, exitBars = 2, totalBars = 8)
        val scriptedLegacy = TransitionMoves.compile(soundPolishChoice(MixTemplate.M2).copy(legacyLoop = legacy), 120.0, 120.0)
        val legacyClock = LoopMix.Clock(scriptedLegacy.plan)
        val atLanding = LoopMixSession.computePhraseEnvelopes(legacyClock, scriptedLegacy.plan, scriptedLegacy)
            .single { it.deck == LoopMixRenderer.IN && it.kind == LoopMixRenderer.KIND_GAIN }
        assertEquals(scoreAt(scriptedLegacy.inGain, 0.0, true), wireAt(atLanding, legacyClock.releaseAt), 0.0004)
        assertEquals(legacyClock.beats.toDouble(), TransitionMoves.landingBeat(scriptedLegacy), 1e-10)
        println("PHRASE landing proof: $cases actual buildDeckKnots cases; maxErrorMs=$maxErrorMs (gate=25ms); legacy L includes enterBars")
    }
}

internal fun soundPolishChoice(template: MixTemplate, leadInBars: Int = 8, pickup: Float = 0f,
    vocal: Float = 0.6f): PhraseChoice = PhraseChoice(
    template = template, rung = PhrasePlanner.rungOf(template), score = 0.9,
    exit = ExitCue(ExitKind.OUTRO_PHRASE, 80, 160.0, 64, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE,
        0.5f, 5, true, 0, 0),
    entry = EntryCue(EntryKind.HEAD, 0, 0.375, leadInBars, SectionFn.INTRO, SectionRole.GROOVE,
        leadInBars, vocal, true, 0.5f, 5, 0, pickup),
    overlapBars = 8, swapBar = -4.0, tailBeats = 2.0, loopK = 0, echoGated = false,
    vocalProduct = 0f, tempoPrepBars = 0, echoDelayBeats = 0.75, echoDelayName = "ECHO_DELAY_BEATS",
    swapRampMs = 120, swapRampName = "SWAP_RAMP_BLEND_MS", tempoPolicy = "TEMPO_POLICY=b",
    vocalOverlapBeats = 0.0, energyDelta = 0f, struct = 0.9, vocalScore = 0.9, phraseScore = 0.9,
    energyScore = 0.9, lengthScore = 0.9, keyScore = 0.9, tempoScore = 0.9, cost = 0.0,
)
