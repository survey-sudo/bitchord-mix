package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * djmix19 (vc61) regression suite (shared):
 * 1. DjColour roll advances 1.0x (kills 2x chipmunk) and tempoGap never picks LOOP_ROLL.
 * 2. LoopMix.regionBounds is the single source of truth for straight-deck stopAt / hold.
 * 3. MixGain / RegionalLoudness floors raised (super-low mix volume).
 * 4. TransitionMoves M1/M3/M9 inGain/inHigh raised.
 */
class Djmix19RegressionTest {

    // ---- 1. DjColour 1.0x roll + tempoGap ----

    @Test
    fun rollReadPointerAdvancesAtOneTimesSpeed() {
        val sr = 48000
        val ch = 2
        val colour = DjColour()
        colour.configure(sr, ch)
        colour.set(DjEffect.LOOP_ROLL, 1f, 0.5f, 0f)
        val blockFrames = 4800
        val block = FloatArray(blockFrames * ch)
        var frame = 0
        // Warmup 4 s (192k frames) so the 2 s ring wraps and the loop is live.
        repeat(40) {
            for (i in 0 until blockFrames) {
                val v = frame.toFloat()
                block[2 * i] = v
                block[2 * i + 1] = v
                frame++
            }
            colour.process(block, blockFrames, ch)
        }
        // Test block: a fresh ramp; within one roll window the output must be
        // the same ramp delayed, i.e. step 1 per frame, never 2.
        for (i in 0 until blockFrames) {
            val v = frame.toFloat()
            block[2 * i] = v
            block[2 * i + 1] = v
            frame++
        }
        colour.process(block, blockFrames, ch)
        assertTrue(block.all { it.isFinite() })
        var oneCount = 0
        var twoCount = 0
        for (i in 1 until blockFrames) {
            val d = block[2 * i] - block[2 * (i - 1)]
            if (abs(d - 1f) < 1e-3f) oneCount++
            if (abs(d - 2f) < 1e-3f) twoCount++
        }
        assertEquals(0, twoCount, "roll must never step 2 frames per frame (2x chipmunk)")
        assertTrue(oneCount > 1000, "roll must step 1 frame per frame, got $oneCount/$blockFrames")
    }

    @Test
    fun tempoGapNeverPicksLoopRoll() {
        val out = track(100.0).let { it.copy(vocalActivityMask = List(it.energyCurve.size) { 0.02 }) }
        val inn = track(140.0).let { it.copy(vocalActivityMask = List(it.energyCurve.size) { 0.02 }) }
        for (i in 0..40) {
            val plan = DjMix.annotate(gap(), out, inn, outgoingId = "a", incomingId = "id-$i")
            assertTrue(plan.djEffect != DjEffect.LOOP_ROLL, "id-$i picked LOOP_ROLL")
            assertEquals(DjEffect.NONE, plan.djEffect, "no ornamental Echo or substitute wash for id-$i")
            assertEquals(0.0, plan.echoSeconds)
        }
    }

    // ---- 2. regionBounds single source of truth ----

    private fun exitCue(bar: Int, sec: Double, room: Int = 8) = ExitCue(
        kind = ExitKind.OUTRO_PHRASE,
        releaseBar = bar,
        releaseSec = sec,
        roomBars = room,
        vocalRoomBars = 0,
        tailBars = 0,
        sectionFn = SectionFn.OUTRO,
        role = SectionRole.GROOVE,
        energyAtRelease = 0.4f,
        energyLevel = 4,
        loopableRoom = true,
        cutsSungBars = 0,
        cutsBars = 0,
    )

    private fun entryCue(startBar: Int = 0, startSec: Double = 0.0, landingBar: Int = 8) = EntryCue(
        kind = EntryKind.HEAD,
        startBar = startBar,
        startSec = startSec,
        landingBar = landingBar,
        landingFn = SectionFn.INTRO,
        landingRole = SectionRole.GROOVE,
        leadInBars = landingBar - startBar,
        leadInVocal = 0f,
        leadInLoopable = true,
        energyAtLanding = 0.4f,
        energyLevel = 4,
        trimmedBars = startBar,
        pickupBeats = 0f,
    )

    private fun choiceFor(
        template: MixTemplate,
        overlapBars: Int = 8,
        prepBars: Int = 0,
        releaseSec: Double = 170.0,
    ) = PhraseChoice(
        template = template,
        rung = PhrasePlanner.rungOf(template),
        score = 0.85,
        exit = exitCue(bar = 72, sec = releaseSec, room = overlapBars),
        entry = entryCue(landingBar = overlapBars),
        overlapBars = overlapBars,
        swapBar = -overlapBars / 2.0,
        tailBeats = 1.0,
        loopK = 0,
        echoGated = template == MixTemplate.M6,
        vocalProduct = 0f,
        tempoPrepBars = prepBars,
        echoDelayBeats = 0.75,
        echoDelayName = "ECHO_DELAY_BEATS",
        swapRampMs = 120,
        swapRampName = "SWAP_RAMP_BLEND_MS",
        tempoPolicy = "TEMPO_POLICY=b",
        vocalOverlapBeats = 0.0,
        energyDelta = 0f,
        struct = 0.9,
        vocalScore = 0.9,
        phraseScore = 0.9,
        energyScore = 0.9,
        lengthScore = 0.9,
        keyScore = 0.9,
        tempoScore = 0.9,
        cost = 0.0,
    )

    @Test
    fun regionBoundsCoversStraightDeckStopAt() {
        for (template in listOf(MixTemplate.M1, MixTemplate.M9, MixTemplate.RELAXED)) {
            val script = TransitionMoves.compile(choiceFor(template, overlapBars = 8, prepBars = 0), outBpm = 120.0, inBpm = 121.0)
            val plan = script.plan
            assertEquals(false, plan.outLoops, "$template must be straight")
            val clock = LoopMix.Clock(plan)
            val bounds = LoopMix.regionBounds(plan)
            // Replicate LoopMixSession.build's required outTo (pre-djmix19 it
            // extended bounds.outTo here; djmix19 moved that into regionBounds).
            val echoHold = if (plan.echoTail) plan.out.beatSec * 8 else 0.0
            val stopAt = clock.releaseAt + 0.05 + echoHold + plan.exitTailSeconds
            val tailRate = if (clock.beats >= 1) {
                val b0 = clock.beatTimes[clock.beats - 1]
                val b1 = clock.beatTimes[clock.beats]
                (plan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
            } else 1.0
            val lastPos = plan.out.loopStart + clock.beats * plan.out.beatSec
            val stopPos = lastPos + (stopAt - clock.releaseAt) * tailRate
            assertTrue(
                bounds.outTo >= stopPos - 1e-6,
                "bounds.outTo ${bounds.outTo} must cover stopPos $stopPos ($template)",
            )
            assertTrue(
                bounds.outTo >= plan.out.loopStart + clock.elementAt + maxOf(echoHold, plan.exitTailSeconds) - 1e-6,
                "bounds.outTo ${bounds.outTo} must cover elementAt+tail ($template)",
            )
            // Exact cache consistency: isLoopFastReady decodes [outFrom, outTo].
            assertTrue(bounds.outFrom == plan.out.loopStart - 1.0)
            assertTrue(bounds.inFrom == plan.inn.loopStart - 1.0)
        }
    }

    @Test
    fun regionBoundsInToCoversIncomingHold() {
        // 120 BPM: bar 2 s, 9 prep bars hold B for 18 s (djmix19 hold case).
        val script = TransitionMoves.compile(choiceFor(MixTemplate.M1, overlapBars = 8, prepBars = 9), outBpm = 120.0, inBpm = 120.0)
        val plan = script.plan
        assertEquals(18.0, script.incomingHoldSec, 1e-6)
        val clock = LoopMix.Clock(plan)
        val bounds = LoopMix.regionBounds(plan)
        // Handover is 1:1 after the hold, never loopStart + elementAt.
        val expectedHandover = plan.inn.loopStart + (clock.elementAt - 18.0)
        assertEquals(expectedHandover, clock.elementTrackTime, 0.001)
        val inTail = if (plan.echoTail) 3.0 + plan.out.beatSec * 8.0 else 3.0
        assertEquals(expectedHandover + inTail, bounds.inTo, 1e-9)
        assertTrue(bounds.inTo > clock.elementTrackTime)
    }

    // ---- 3. MixGain / RegionalLoudness floors ----

    @Test
    fun headroomFloorIs94AndLoudnessCutFloorIsMinus35() {
        assertTrue(MixGain.HEADROOM_FLOOR >= 0.94f, "HEADROOM_FLOOR was ${MixGain.HEADROOM_FLOOR}")
        // 1/sqrt(2) = 0.707 uncapped, floored at 0.94.
        assertEquals(0.94f, MixGain.headroomTrim(1f, 1f), 1e-6f)
        // Relaxed filter fall (exponent 1.20, not 1.6): at 0.5 the old value
        // was ~0.868, the new is ~0.775.
        val ff = MixGain.filterFall(0.5f)
        assertTrue(ff > 0.70f && ff < 0.82f, "filterFall(0.5)=$ff must be relaxed ~0.775")
        // -20 dB difference clamps to -3.5 dB => 10^(-3.5/20) = 0.668.
        val rate = 48000
        val n = rate * 2
        val quiet = tone(n, rate, 0.1)
        val loud = tone(n, rate, 0.8)
        val cut = assertNotNull(RegionalLoudness.matchGain(quiet, quiet, 0, n, loud, loud, 0, n, rate))
        assertTrue(cut >= 0.66f, "matchGain floor was $cut, must be >= 0.66")
        assertTrue(cut < 0.70f, "matchGain $cut must be ~0.668")
    }

    // ---- 4. TransitionMoves gains ----

    @Test
    fun transitionMovesM1HighsAndM9InGainAreRaised() {
        val m1 = TransitionMoves.compile(choiceFor(MixTemplate.M1, overlapBars = 8, prepBars = 0), outBpm = 120.0, inBpm = 120.0)
        // M1 inGain midpoint (beat half = -16) is -1.5 dB (was -3).
        assertTrue(m1.inGain.values.any { it == -1.5f }, "M1 inGain values=${m1.inGain.values.toList()}")
        // M1 inHigh start is -2.5 dB (was -6), mid -1.0 dB.
        assertEquals(-2.5f, m1.inHigh.values.first(), 1e-6f)
        assertTrue(m1.inHigh.values.any { it == -1.0f }, "M1 inHigh values=${m1.inHigh.values.toList()}")
        val m9 = TransitionMoves.compile(choiceFor(MixTemplate.M9, overlapBars = 8, prepBars = 0), outBpm = 120.0, inBpm = 120.0)
        assertTrue(m9.inGain.values.any { it == -3.5f }, "M9 inGain values=${m9.inGain.values.toList()}")
        assertTrue(m9.inGain.values.any { it == -1.5f }, "M9 inGain values=${m9.inGain.values.toList()}")
    }

    private fun gap() = TransitionPlan(
        transitionStyle = TransitionStyle.DJ_FILTER,
        filterVariant = FilterTransitionVariant.ECHO_RIDE,
        echoSeconds = 0.6,
        transitionStart = 160.0,
        transitionEnd = 176.0,
        fadeSeconds = 8.0,
        beatSeconds = 0.6,
    )

    private fun track(bpm: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        duration = 180.0,
        bpm = bpm,
        beatInterval = 60.0 / bpm,
        beatConfidence = 0.9,
        key = "C major",
        keyConfidence = 0.9,
        contentEndTime = 176.0,
        audibleStartTime = 0.4,
        energyCurve = List(90) { EnergySample(it * 2.0, if (it < 4 || it > 80) 0.2 else 1.0) },
    )

    // ---- 5. Beautiful -> Never Let Go mix window & CuePoints/PhrasePlanner ----

    private fun beautifulAnalysis(): TrackAnalysis {
        val bpm = 99.93
        val beat = 60.0 / bpm
        val bar = beat * 4.0
        val duration = 178.0
        val contentEnd = 175.5
        val downbeats = List(75) { it * bar }
        val phraseBoundaries = downbeats.filterIndexed { i, _ -> i % 4 == 0 }
        val energyCurve = List(178) { i ->
            val t = i.toDouble()
            val e = if (t > contentEnd) 0.02 else if (t >= 156.0) 0.25 else 0.9
            EnergySample(t, e)
        }
        val vocalMask = List(178) { i ->
            val t = i.toDouble()
            when {
                t in 12.0..155.0 -> 0.55
                t in 156.0..175.0 -> 0.35
                else -> 0.02
            }
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "beautiful",
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.92,
            downbeats = downbeats,
            phraseBoundaries = phraseBoundaries,
            key = "8B",
            keyConfidence = 0.85,
            audibleStartTime = 0.2,
            introEndTime = 10.0,
            contentEndTime = contentEnd,
            outroStartTime = 156.0,
            mixOutTime = 156.0,
            energyCurve = energyCurve,
            vocalActivityMask = vocalMask,
            vocalProbability = 0.8,
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    private fun neverLetGoAnalysis(): TrackAnalysis {
        val bpm = 104.50
        val beat = 60.0 / bpm
        val bar = beat * 4.0
        val duration = 193.6
        val downbeats = List(84) { it * bar }
        val phraseBoundaries = downbeats.filterIndexed { i, _ -> i % 4 == 0 }
        val energyCurve = List(194) { i ->
            val t = i.toDouble()
            val e = if (t < 18.37) 0.4 else 0.95
            EnergySample(t, e)
        }
        val vocalMask = List(194) { i ->
            val t = i.toDouble()
            if (t < 18.37) 0.02 else 0.65
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "never-let-go",
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.94,
            downbeats = downbeats,
            phraseBoundaries = phraseBoundaries,
            key = "8B",
            keyConfidence = 0.88,
            audibleStartTime = 0.0,
            introEndTime = 18.37,
            contentEndTime = 191.0,
            mixInTime = 18.37,
            mixInCandidates = listOf(MixCandidate(time = 18.37, score = 0.92, type = "intro_drop")),
            energyCurve = energyCurve,
            vocalActivityMask = vocalMask,
            vocalProbability = 0.75,
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    @Test
    fun beautifulToNeverLetGoMixWindowMatchesIdealEightBarsNotTooEarly() {
        val out = beautifulAnalysis()
        val inn = neverLetGoAnalysis()
        val plan = planTransition(
            analysis = out,
            nextAnalysis = inn,
            currentTrack = TransitionTrackInfo(id = "beautiful", durationMs = 178_000L),
            nextTrack = TransitionTrackInfo(id = "never-let-go", durationMs = 193_600L),
            currentTime = 100.0,
            duration = 178.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle, "expected DJ_BLEND, reason=${plan.reason}")
        val barSec = 4.0 * plan.beatSeconds
        val bars = kotlin.math.round(plan.fadeSeconds / barSec).toInt()
        assertEquals(8, bars, "expected 8 bars (not 9!), fadeSeconds=${plan.fadeSeconds}, barSec=$barSec")
        assertTrue(plan.transitionEnd >= 172.0 && plan.transitionEnd <= 175.6, "transitionEnd=${plan.transitionEnd} should sit near contentEnd (175.5), not cut 12s early")
        assertTrue(plan.incomingCueTime <= 0.5, "incomingCueTime=${plan.incomingCueTime} should start near 0.0")
        val inEnd = plan.incomingCueTime + plan.fadeSeconds * plan.incomingPlaybackRate
        assertTrue(inEnd <= 19.0, "inEnd=$inEnd must end near actual song starts (18.37), not +8 beats past it")
    }

    @Test
    fun cuePointsExitsAndPhrasePlannerSurviveSungOutro() {
        val out = beautifulAnalysis()
        val inn = neverLetGoAnalysis()
        val outMap = assertNotNull(SongMaps.fromAnalysis("beautiful", out))
        val inMap = assertNotNull(SongMaps.fromAnalysis("never-let-go", inn))
        val exits = CuePoints.exits(outMap, analysis = out)
        assertTrue(exits.isNotEmpty(), "CuePoints.exits must not return emptyList on sung outro")
        val entries = CuePoints.entries(inMap)
        assertTrue(entries.isNotEmpty())
        assertEquals(0.0, entries.first().startSec, 1e-6, "entry startSec must be 0.0 (not 18.37s skipping intro)")
        val choice = PhrasePlanner.select(outMap, inMap, camelotSteps = 0, outBpm = out.bpm, inBpm = inn.bpm)
        assertTrue(
            choice == null || choice.template != MixTemplate.M6,
            "unknown vocal evidence cannot qualify an M6 handoff; choice=${choice?.template} veto=${PhrasePlanner.lastVetoSummary}",
        )
    }

    private fun tone(n: Int, rate: Int, amplitude: Double): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = (sin(2.0 * PI * 440.0 * i / rate) * amplitude).toFloat()
        return out
    }
}
