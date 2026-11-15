package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * djmix18 (vc60) regression suite:
 * 1. No hyperspeed / end-of-song tempo warps on TransitionMoves.compile.
 * 2. Exact incomingHoldSec handover position on LoopMix.Clock.
 * 3. No 60-70% eaten songs on partial or vocal-heavy SongMaps.
 * 4. Graceful RELAXED / VOCAL_OVER_OUTRO envelopes & pre-filled M6/M10 echo.
 * 5. Beautiful -> Never Let Go unlocks full 8-bar beatmatched mix.
 * 6. Rendition duration clamp prevents loopStart past playableOutDurationSec.
 */
class Djmix18RegressionTest {

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

    private fun evalLane(lane: Lane, beat: Double): Float {
        if (lane.beats.isEmpty()) return 0f
        if (beat <= lane.beats.first()) return lane.values.first()
        if (beat >= lane.beats.last()) return lane.values.last()
        var i = 1
        while (i < lane.beats.size && lane.beats[i] < beat) i++
        val t0 = lane.beats[i - 1]
        val t1 = lane.beats[i]
        val f = if (t1 > t0) ((beat - t0) / (t1 - t0)).toFloat() else 1f
        return lane.values[i - 1] + (lane.values[i] - lane.values[i - 1]) * f
    }

    // ---- 1. No hyperspeed on TransitionMoves.compile ----

    @Test
    fun nonBeatmatchedTemplatesKeepNaturalOutgoingTempoAndM1FoldsTwoToOne() {
        for (template in listOf(MixTemplate.M6, MixTemplate.M10, MixTemplate.CUT_ON_ONE)) {
            val s1 = TransitionMoves.compile(choiceFor(template, overlapBars = 0), outBpm = 104.5, inBpm = 66.5)
            assertEquals(104.5, s1.plan.targetBpm, 1e-6, "$template must keep outBpm 104.5")
            assertEquals(1.0, s1.plan.fold, 1e-6, "$template fold must be 1.0")
            assertEquals(0, s1.plan.rampBars, "$template must not ramp tempo")

            val s2 = TransitionMoves.compile(choiceFor(template, overlapBars = 0), outBpm = 66.5, inBpm = 128.0)
            assertEquals(66.5, s2.plan.targetBpm, 1e-6, "$template must keep outBpm 66.5")
            assertEquals(1.0, s2.plan.fold, 1e-6)
            assertEquals(0, s2.plan.rampBars)
        }

        // 70 -> 142 BPM on M1 folds 2:1 (+1.43%, never 2.03x) and ramps >= 4 bars.
        val m1 = TransitionMoves.compile(choiceFor(MixTemplate.M1, overlapBars = 8, prepBars = 0), outBpm = 70.0, inBpm = 142.0)
        assertEquals(2.0, m1.plan.fold, 1e-9, "70->142 must fold 2:1")
        assertEquals(71.0, m1.plan.targetBpm, 1e-6, "70->142 targetBpm must be 71.0 (+1.4%)")
        assertTrue(m1.plan.rampBars >= 4, "beatmatched ramp must be >= 4 bars, got ${m1.plan.rampBars}")

        // >8% mismatch on M1 (even if manually compiled) forces unity 1.000x instead of warping.
        val farM1 = TransitionMoves.compile(choiceFor(MixTemplate.M1, overlapBars = 8), outBpm = 100.0, inBpm = 135.0)
        assertEquals(100.0, farM1.plan.targetBpm, 1e-6)
        assertEquals(0, farM1.plan.rampBars)
    }

    // ---- 2. Exact incomingHoldSec handover position ----

    @Test
    fun clockWithIncomingHoldSecComputesExactHandoverTrackTime() {
        val choice = choiceFor(MixTemplate.M1, overlapBars = 8, prepBars = 4)
        // 100 -> 102 BPM: Task 2B syncs incomingHoldSec to LoopMix.Clock.beatTimes[rampTo] (~9.5055s).
        val script = TransitionMoves.compile(choice, outBpm = 100.0, inBpm = 102.0)
        val clock = LoopMix.Clock(script.plan)
        assertEquals(clock.beatTimes[clock.rampTo], script.incomingHoldSec, 1e-6)
        assertEquals(clock.beatTimes[clock.rampTo], script.plan.incomingHoldSec, 1e-6)

        val expectedHandover = script.plan.inn.loopStart + (clock.elementAt - clock.rampEnd)
        assertEquals(expectedHandover, clock.handoverTrackSec, 0.001, "handover must match within 1 ms")
        assertEquals(expectedHandover, clock.elementTrackTime, 0.001)
        // During the hold (0..rampEnd), IN stays at inn.loopStart.
        assertEquals(script.plan.inn.loopStart, clock.inTrackTimeAt(0.0), 1e-9)
        assertEquals(script.plan.inn.loopStart, clock.inTrackTimeAt(5.0), 1e-9)
        assertEquals(script.plan.inn.loopStart, clock.inTrackTimeAt(clock.rampEnd), 1e-9)
        // After hold, IN advances 1:1 from loopStart.
        assertEquals(script.plan.inn.loopStart + 4.0, clock.inTrackTimeAt(clock.rampEnd + 4.0), 1e-9)
    }

    // ---- 3. No 60-70% eaten song ----

    @Test
    fun partial120sMapOn210sTrackNeverEmitsEarlyExitsAndVocalTrackProtectsChorus() {
        // Synthetic 120s head PCM for a 210s track at 120 BPM.
        val sr = 22050
        val headSec = 120.0
        val fullSec = 210.0
        val pcm = FloatArray((headSec * sr).toInt()) { i ->
            (0.4 * sin(2 * PI * 110.0 * i / sr)).toFloat()
        }
        val partialMap = StructureAnalyzer.fromPcm(
            trackKey = "partial-210",
            pcm = pcm,
            sampleRate = sr,
            bpm = 120.0,
            fullTrackDurationSec = fullSec,
        )
        assertTrue(partialMap.partial, "partial flag must be true when fullTrackDurationSec > pcmDurationSec")
        assertEquals(fullSec, partialMap.durationSec, 0.1)

        val partialExits = CuePoints.exits(partialMap)
        assertTrue(partialExits.isNotEmpty(), "partial map must synthesize valid tail exits")
        val minAllowed = fullSec * 0.78 // 163.8s
        for (exit in partialExits) {
            assertTrue(
                exit.releaseSec >= minAllowed - 1e-6,
                "partial exit at ${exit.releaseSec}s would eat >22% of 210s song (min $minAllowed)",
            )
        }

        // Full 210s song with vocals/chorus through 170s (bar 85 at 120 BPM, bar = 2.0s).
        val totalBars = 105
        val sung = BooleanArray(totalBars) { it < 85 }
        val fullMap = SongMap(
            trackKey = "vocal-210",
            durationSec = 210.0,
            bpm = 120.0,
            beatsPerBar = 4,
            downbeats = DoubleArray(totalBars + 1) { it * 2.0 },
            gridConfidence = 0.9f,
            tempoStable = true,
            phraseAnchor = 0,
            phraseLen = 8,
            sections = listOf(
                PhraseSection(0, 8, SectionFn.INTRO, SectionRole.GROOVE, 0.8f, 0, 0, 0.4f, 4, 0f, 0f, 0f, 0.3f, false),
                PhraseSection(8, 44, SectionFn.VERSE, SectionRole.NARRATIVE, 0.8f, 1, 0, 0.6f, 6, 0f, 0f, 0.8f, 0.4f, false),
                PhraseSection(44, 64, SectionFn.BREAK, SectionRole.AMBIENT, 0.7f, 2, 0, 0.3f, 3, 0f, 0f, 0f, 0.2f, false),
                PhraseSection(64, 85, SectionFn.CHORUS, SectionRole.RELEASE, 0.9f, 3, 0, 0.9f, 9, 0f, 0f, 0.9f, 0.5f, false),
                PhraseSection(85, 105, SectionFn.OUTRO, SectionRole.GROOVE, 0.8f, 4, 0, 0.3f, 3, 0f, 0f, 0f, 0.3f, true),
            ),
            sungBars = sung,
            firstVocalBar = 8,
            lastVocalBar = 84,
            pickupBeats = 0f,
            startKind = StartKind.INTRO_INSTRUMENTAL,
            endingKind = EndingKind.OUTRO_INSTRUMENTAL,
            contentEndSec = 210.0,
            labelConfidence = 0.85f,
            structMs = 10,
            chorusCount = 1,
        )
        val innMap = fullMap.copy(
            trackKey = "inn-180",
            durationSec = 180.0,
            sections = listOf(
                PhraseSection(0, 16, SectionFn.INTRO, SectionRole.GROOVE, 0.8f, 0, 0, 0.4f, 4, 0f, 0f, 0f, 0.3f, false),
                PhraseSection(16, 90, SectionFn.VERSE, SectionRole.NARRATIVE, 0.8f, 1, 0, 0.7f, 7, 0f, 0f, 0.8f, 0.4f, false),
            ),
            sungBars = BooleanArray(90) { it >= 16 },
            firstVocalBar = 16,
            lastVocalBar = 89,
            contentEndSec = 180.0,
            chorusCount = 0,
        )
        val fullExits = CuePoints.exits(fullMap)
        for (exit in fullExits) {
            assertTrue(exit.releaseSec >= 170.0 - 1e-6, "vocal song exit ${exit.releaseSec}s cut vocals before 170s")
        }
        val selected = assertNotNull(PhrasePlanner.select(fullMap, innMap, 0, 120.0, 120.0))
        assertTrue(selected.exit.releaseSec >= 170.0 - 1e-6, "selected exit ${selected.exit.releaseSec}s must not cut before 170s")
    }

    // ---- 4. Graceful RELAXED / VOCAL_OVER_OUTRO & pre-filled M6 echo ----

    @Test
    fun relaxedAndVocalOverOutroFadeBeforeZeroAndM6PrefillsEcho() {
        for (template in listOf(MixTemplate.RELAXED, MixTemplate.VOCAL_OVER_OUTRO)) {
            val script = TransitionMoves.compile(choiceFor(template, overlapBars = 8), outBpm = 106.0, inBpm = 109.0)
            val dbAtZero = evalLane(script.outGain, 0.0)
            val linearAtZero = 10.0.pow(dbAtZero / 20.0)
            assertTrue(
                linearAtZero <= 0.35,
                "$template outGain(0.0) linear=$linearAtZero ($dbAtZero dB) must have ramped down <= 0.35 before landing",
            )
            assertEquals(TransitionMoves.MUTE_DB, script.outGain.values.last(), 1e-6f)
        }

        val m6 = TransitionMoves.compile(choiceFor(MixTemplate.M6, overlapBars = 0), outBpm = 104.5, inBpm = 66.5)
        val sendTwoBeatsBefore = evalLane(m6.echoSend, -2.0)
        assertTrue(sendTwoBeatsBefore > 0.1f, "M6 echoSend at -2.0 beats must be > 0, got $sendTwoBeatsBefore")
        assertTrue(evalLane(m6.outGain, -2.0) > -1.0f, "M6 dry outGain must still be audible while pre-filling echo at -2.0 beats")
    }

    // ---- 5. Beautiful -> Never Let Go unlocks 8-bar beatmatched mix ----

    @Test
    fun beautifulToNeverLetGoProducesFull8BarBeatmatchedMix() {
        val beautifulBpm = 99.99
        val beautifulDur = 180.0
        val beautifulBar = 240.0 / beautifulBpm // ~2.40024s
        val beautifulDownbeats = generateSequence(0.0) { it + beautifulBar }.takeWhile { it < beautifulDur }.toList()
        val framesOut = (beautifulDur * LEVEL_FRAME_RATE).roundToInt()
        val levelOut = FloatArray(framesOut) { idx ->
            val t = idx / LEVEL_FRAME_RATE
            // Loud body 0..158s (0.35 RMS), quiet atmospheric outro break 158..178s (0.10 RMS -> ~0.28x typical).
            if (t >= 158.0 && t < 179.0) levelFromRms(0.10).toFloat() else levelFromRms(0.35).toFloat()
        }
        val vocalMaskOut = FloatArray(framesOut) { idx ->
            val t = idx / LEVEL_FRAME_RATE
            // Sung chorus/verse up to 156s, instrumental outro break 158..180s with one snare spike 0.26 at 165s.
            when {
                t < 156.0 -> 0.7f
                abs(t - 165.0) < 0.05 -> 0.26f
                else -> 0.03f
            }
        }
        val curveOut = (0 until 360).map { i ->
            val t = i * 0.5
            EnergySample(t, if (t >= 158.0) 0.18 else 0.85)
        }
        val maskCurveOut = curveOut.map { s -> if (s.time < 156.0) 0.7 else 0.03 }

        val beautiful = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "beautiful",
            duration = beautifulDur,
            bpm = beautifulBpm,
            beatInterval = 60.0 / beautifulBpm,
            beatConfidence = 0.88,
            downbeats = beautifulDownbeats,
            firstBeat = 0.0,
            key = "8A",
            keyConfidence = 0.8,
            audibleStartTime = 0.0,
            contentEndTime = 179.2,
            outroStartTime = 158.4,
            energyCurve = curveOut,
            vocalActivityMask = maskCurveOut,
            vocalProbability = 0.72,
            vocalWindows = listOf(CurveWindow(0.0, LEVEL_FRAME_RATE, vocalMaskOut)),
            levelWindows = listOf(CurveWindow(0.0, LEVEL_FRAME_RATE, levelOut)),
            quality = TrackAnalysis.QUALITY_FULL,
        )

        val neverBpm = 104.50
        val neverDur = 193.6
        val neverBar = 240.0 / neverBpm // ~2.29665s
        val neverDownbeats = generateSequence(0.0) { it + neverBar }.takeWhile { it < neverDur }.toList()
        val framesIn = (neverDur * LEVEL_FRAME_RATE).roundToInt()
        val levelIn = FloatArray(framesIn) { levelFromRms(0.32).toFloat() }
        val vocalMaskIn = FloatArray(framesIn) { idx ->
            val t = idx / LEVEL_FRAME_RATE
            // Clean 8-bar intro (~18.4s), then vocals.
            if (t < 19.0) 0.04f else 0.75f
        }
        val curveIn = (0 until 387).map { i -> EnergySample(i * 0.5, 0.7) }
        val maskCurveIn = curveIn.map { s -> if (s.time < 19.0) 0.04 else 0.75 }

        val neverLetGo = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "never-let-go",
            duration = neverDur,
            bpm = neverBpm,
            beatInterval = 60.0 / neverBpm,
            beatConfidence = 0.42, // below 0.55 & 0.45, rescued via steady downbeat grid within ±8%!
            downbeats = neverDownbeats,
            firstBeat = 0.0,
            key = "8A",
            keyConfidence = 0.8,
            audibleStartTime = 0.0,
            contentEndTime = 191.5,
            introEndTime = 18.4,
            energyCurve = curveIn,
            vocalActivityMask = maskCurveIn,
            vocalProbability = 0.75,
            vocalWindows = listOf(CurveWindow(0.0, LEVEL_FRAME_RATE, vocalMaskIn)),
            levelWindows = listOf(CurveWindow(0.0, LEVEL_FRAME_RATE, levelIn)),
            quality = TrackAnalysis.QUALITY_FULL,
        )

        // Policy tier must rescue to BEATMATCHED despite 0.42 confidence & 4.5% BPM delta.
        val verdict = assessTransitionTier(beautiful, neverLetGo, advanced = true)
        assertEquals(TransitionTier.BEATMATCHED, verdict.tier, "expected BEATMATCHED, reasons=${verdict.reasons}")

        // LoopMix.plan qualifies with the relaxed outro break loudness & transient tolerance.
        val loopPlan = LoopMix.plan(beautiful, beautifulDur, neverLetGo, neverDur, 0.0)
        assertNotNull(loopPlan, "LoopMix.plan must qualify on Beautiful's quiet outro break")
        assertTrue(loopPlan.out.loopStart >= 140.4, "loopStart=${loopPlan.out.loopStart} must sit in outro >= 140.4s")

        // LoopMix.bridge produces an 8-bar (~19.2s) beatmatched blend.
        val bridgePlan = assertNotNull(LoopMix.bridge(beautiful, beautifulDur, neverLetGo, neverDur, 0.0))
        assertEquals("Beat blend", bridgePlan.rung)
        assertEquals(8, bridgePlan.totalBars, "bridge must produce 8-bar blend, got ${bridgePlan.totalBars}")
        assertTrue(bridgePlan.out.loopStart >= 140.4, "bridge out=${bridgePlan.out.loopStart} must sit in outro >= 140.4s")

        // PhrasePlanner.select produces an 8-bar M1 beatmatched mix in Beautiful's outro (>= 140.4s).
        val outMap = assertNotNull(SongMaps.fromAnalysis("beautiful", beautiful))
        val inMap = assertNotNull(SongMaps.fromAnalysis("never-let-go", neverLetGo))
        val phrase = assertNotNull(PhrasePlanner.select(outMap, inMap, 0, beautifulBpm, neverBpm, outAnalysis = beautiful))
        // Room 7 < lead 8 at the phrase-aligned outro bar requires a loop extend,
        // so the correct 8-bar beatmatch is M2 (Loop mix extend); M1 BassSwap is
        // the same family. Either 8-bar beatmatched mix satisfies the invariant.
        assertTrue(phrase.template == MixTemplate.M1 || phrase.template == MixTemplate.M2, "expected 8-bar M1/M2 beatmatch, got ${phrase.template} rung=${phrase.rung}")
        assertTrue(phrase.overlapBars >= 8, "expected >= 8 overlap bars, got ${phrase.overlapBars}")
        assertTrue(phrase.exit.releaseSec >= 140.4, "releaseSec=${phrase.exit.releaseSec} must be >= 140.4s")
        val overlapSec = phrase.overlapBars * beautifulBar
        assertTrue(overlapSec in 18.0..39.0, "overlapSec=$overlapSec must be >= 18s (not a 1-4s EOF blip)")
    }

    // ---- 6. Rendition duration clamp ----

    @Test
    fun renditionDurationClampPreventsLoopStartPastPlayableOutDuration() {
        val dur = 215.0
        val frames = (dur * LEVEL_FRAME_RATE).roundToInt()
        val levels = listOf(CurveWindow(0.0, LEVEL_FRAME_RATE, FloatArray(frames) { levelFromRms(0.3).toFloat() }))
        val out = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = dur,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.85,
            downbeats = listOf(0.5),
            firstBeat = 0.5,
            levelWindows = levels,
        )
        val inn = out.copy(duration = 190.0)
        val clear = emptyList<LoopMix.Span>()
        val plan = assertNotNull(
            LoopMix.plan(
                out = out,
                outDuration = out.duration,
                inn = inn,
                inDuration = inn.duration,
                earliestOutSec = 0.0,
                sungOut = clear,
                sungIn = clear,
                playableOutDurationSec = 180.0,
            ),
        )
        assertTrue(plan.out.loopStart < 179.0, "loopStart=${plan.out.loopStart} must be < 179.0 when playableOutDurationSec=180.0")
        assertTrue(plan.out.loopEnd <= 179.5 + 1e-6, "loopEnd=${plan.out.loopEnd} must be <= 179.5")
    }
}
