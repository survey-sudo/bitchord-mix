package com.music.bitchord.playback

import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.CatchUpPlanner
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.DeckBudget
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.PhrasePlanner
import com.music.bitchord.playback.smart.SeekAction
import com.music.bitchord.playback.loopmix.DeckKnots
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.EntryCue
import com.music.bitchord.playback.smart.EntryKind
import com.music.bitchord.playback.smart.ExitCue
import com.music.bitchord.playback.smart.ExitKind
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.PhraseChoice
import com.music.bitchord.playback.smart.SectionFn
import com.music.bitchord.playback.smart.SectionRole
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.planTransition
import com.music.bitchord.playback.smart.nativeTiming
import com.music.bitchord.playback.loopmix.LoopRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/**
 * djmix26 `:app` suite (Task 2A countdown/scrub + Task 2B envelope geometry):
 * Video 1 fallback markability + stem-skip near splice, Video 2 countdown
 * stabilization across HEAD -> FULL, spurious seek & deferred disarm invariants,
 * plus pure echo/scoop spec geometry without the native renderer.
 */
class Djmix26AppTest {

    private fun track(
        id: String,
        bpm: Double,
        duration: Double = 180.0,
        quality: String = TrackAnalysis.QUALITY_FULL,
        key: String = "D minor",
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val bar = 4.0 * beat
        val downbeats = buildList {
            var t = 0.5
            while (t <= duration) { add(t); t += bar }
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.92,
            downbeats = downbeats,
            firstBeat = 0.5,
            key = key,
            keyConfidence = 0.9,
            contentEndTime = duration - 3.0,
            energyCurve = if (quality == TrackAnalysis.QUALITY_FULL) {
                listOf(EnergySample(0.0, 0.7), EnergySample(duration - 4.0, 0.3))
            } else {
                emptyList()
            },
            quality = quality,
        )
    }

    @Test
    fun video1LoopFallbackKeepsWindowAliveWithoutNextStreamReadyOrFullAnalysis() {
        // Video 1 (Technologic -> Guess, 127->130): after loopFallBack at <= 12s,
        // smartTransitionWindow must stay alive even if !nextStreamReady and current is REFINING.
        assertTrue(
            isClassicMarkable(
                blocked = false,
                markerVisible = true,
                durationMs = 180_000L,
                currentState = TrackAnalysisState.REFINING,
                nextState = TrackAnalysisState.REFINING,
                reason = "before-mix-window",
                alreadyCommittedClassic = false,
                fallbackAdopted = true,
            ),
        )
        assertTrue(
            isClassicMarkable(
                blocked = false,
                markerVisible = true,
                durationMs = 180_000L,
                currentState = TrackAnalysisState.ANALYSED,
                nextState = TrackAnalysisState.WAITING,
                reason = "smart-duration:catch-up",
                alreadyCommittedClassic = true,
                fallbackAdopted = false,
            ),
        )
        // Blind fallback while REFINING before commit must NOT prematurely lock.
        assertFalse(
            isClassicMarkable(
                blocked = false,
                markerVisible = true,
                durationMs = 180_000L,
                currentState = TrackAnalysisState.REFINING,
                nextState = TrackAnalysisState.ANALYSING,
                reason = "smart-analysis-fallback",
                alreadyCommittedClassic = false,
                fallbackAdopted = false,
            ),
        )
        // Actionable non-fallback HEAD plan (e.g. <= 45s late rescue) IS markable.
        assertTrue(
            isClassicMarkable(
                blocked = false,
                markerVisible = true,
                durationMs = 180_000L,
                currentState = TrackAnalysisState.REFINING,
                nextState = TrackAnalysisState.ANALYSED,
                reason = "before-mix-window",
                alreadyCommittedClassic = false,
                fallbackAdopted = false,
            ),
        )
    }

    @Test
    fun video1FallbackReanchorsActionablyWithinRemaining12s() {
        val tech = track("tech", 127.0, duration = 180.0, key = "D minor")
        val guess = track("guess", 130.0, duration = 180.0, key = "D minor")
        val outInfo = TransitionTrackInfo("tech", 180_000L, "Technologic", "Daft Punk")
        val inInfo = TransitionTrackInfo("guess", 180_000L, "Guess", "Charli xcx")

        val stabilizer = CountdownStabilizer()
        val pair = "tech->guess"
        val epoch = 0L
        stabilizer.consider(
            CountdownStabilizer.Candidate(pair, epoch, CountdownStabilizer.Family.LOOP, "Beat blend", 168_000L, 180_000L),
            currentPosMs = 165_000L,
        )
        stabilizer.markShown(pair, epoch)

        // Loop misses splice at 168_000ms (12s remaining in track).
        stabilizer.onRungFailed(pair, epoch, CountdownStabilizer.Family.LOOP, suppressBridgeToo = true)
        val posMs = 168_100L
        val raw = planTransition(
            analysis = tech,
            nextAnalysis = guess,
            currentTrack = outInfo,
            nextTrack = inInfo,
            currentTime = posMs / 1000.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        val plan = if (posMs / 1000.0 > raw.transitionStart + 0.25) {
            CatchUpPlanner.reanchor(raw, tech, guess, posMs / 1000.0, 180.0) ?: raw
        } else raw

        val stabilized = stabilizer.stabilizeClassicPlan(
            pair, epoch, plan, null, isFullQuality = true, currentPosMs = posMs, durationSec = 180.0,
        )
        assertFalse("fallback plan must not be blocked: ${stabilized.reason}", stabilized.blocked)
        assertTrue(stabilized.markerVisible)
        assertTrue(stabilized.fadeSeconds >= 1.2)
        assertTrue(stabilized.transitionEnd <= 180.0 + 1e-6)
        assertTrue(
            isClassicMarkable(
                stabilized.blocked, stabilized.markerVisible, 180_000L,
                TrackAnalysisState.ANALYSED, TrackAnalysisState.ANALYSED,
                stabilized.reason, alreadyCommittedClassic = false, fallbackAdopted = true,
            ),
        )
    }

    @Test
    fun stemSeparationSkippedWithinTwoSecondsOfSplice() {
        val now = 50_000L
        assertTrue(shouldSkipStemNearSplice(remainingToSpliceMs = 2_000L, deadlineMs = DeckBudget.NO_DEADLINE_MS, nowMs = now))
        assertTrue(shouldSkipStemNearSplice(remainingToSpliceMs = 2_500L, deadlineMs = DeckBudget.NO_DEADLINE_MS, nowMs = now))
        assertTrue(shouldSkipStemNearSplice(remainingToSpliceMs = 8_000L, deadlineMs = now + 1_900L, nowMs = now))
        assertFalse(shouldSkipStemNearSplice(remainingToSpliceMs = 8_000L, deadlineMs = now + 5_000L, nowMs = now))
        assertFalse(shouldSkipStemNearSplice(remainingToSpliceMs = 8_000L, deadlineMs = DeckBudget.NO_DEADLINE_MS, nowMs = now))
    }

    @Test
    fun video2HeadToFullTransitionNeverJumpsCommittedCountdown() {
        val kgbHead = track("kgb", 120.0, duration = 180.0, quality = TrackAnalysis.QUALITY_HEAD)
        val kgbFull = track("kgb", 120.0, duration = 180.0, quality = TrackAnalysis.QUALITY_FULL).copy(contentEndTime = 148.0)
        val tech = track("tech", 127.0, duration = 200.0, quality = TrackAnalysis.QUALITY_FULL)
        val outInfo = TransitionTrackInfo("kgb", 180_000L, "KGB")
        val inInfo = TransitionTrackInfo("tech", 200_000L, "Technologic")

        val stabilizer = CountdownStabilizer()
        val pair = "kgb->tech"

        // Under HEAD at 142s (38s left <= 45s), late HEAD rescue produces an actionable plan.
        val headPlan = planTransition(
            analysis = kgbHead, nextAnalysis = tech, currentTrack = outInfo, nextTrack = inInfo,
            currentTime = 142.0, duration = 180.0, mode = CrossfadeMode.SMART, advanced = true,
        )
        assertFalse(headPlan.blocked)
        val committed = stabilizer.stabilizeClassicPlan(
            pair, 0L, headPlan, null, isFullQuality = false, currentPosMs = 142_000L, durationSec = 180.0,
        )
        stabilizer.markShown(pair, 0L)
        val committedStartMs = (committed.transitionStart * 1000.0).roundToLong()

        // FULL arrives with contentEndTime = 148.0 (much earlier bar): must NOT jump committed bar!
        val fullPlan = planTransition(
            analysis = kgbFull, nextAnalysis = tech, currentTrack = outInfo, nextTrack = inInfo,
            currentTime = 144.0, duration = 180.0, mode = CrossfadeMode.SMART, advanced = true,
        )
        val stabilized = stabilizer.stabilizeClassicPlan(
            pair, 0L, fullPlan, committed, isFullQuality = true, currentPosMs = 144_000L, durationSec = 180.0,
        )
        assertEquals(committedStartMs, (stabilized.transitionStart * 1000.0).roundToLong())
        assertTrue(CountdownStabilizer.displaySeconds(committedStartMs, 144_000L) >= 1)
    }

    @Test
    fun spuriousSeekAndDeferredLoopDisarmContractsHold() {
        assertTrue(SeekAction.shouldIgnoreSeek(SeekAction.Phase.IDLE, 0L, 0L))
        assertTrue(SeekAction.shouldIgnoreSeek(SeekAction.Phase.IDLE, 42_000L, 42_800L, sameMediaItem = true))
        assertFalse(SeekAction.shouldIgnoreSeek(SeekAction.Phase.IDLE, 42_000L, 90_000L, sameMediaItem = true))
        assertTrue(SeekAction.shouldDeferLoopDisarm(deferRequested = true, isLoopRunning = true))
        assertFalse(SeekAction.shouldDeferLoopDisarm(deferRequested = true, isLoopRunning = false))
    }

    // ---- Task 2B tests ----

    @Test
    fun vocalMidCarveUsesPositiveMixClockSecondsAndGatesOnVocalPresence() {
        val choiceInst = PhraseChoice(
            template = MixTemplate.M1,
            rung = "Beat blend",
            score = 0.9,
            exit = ExitCue(ExitKind.OUTRO_PHRASE, 70, 170.0, 8, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE, 0.4f, 4, true, 0, 0),
            entry = EntryCue(EntryKind.HEAD, 0, 0.0, 8, SectionFn.INTRO, SectionRole.GROOVE, 8, 0f, true, 0.4f, 4, 0, 0f),
            overlapBars = 8, swapBar = -4.0, tailBeats = 1.0, loopK = 0, echoGated = false,
            vocalProduct = 0f, tempoPrepBars = 0, echoDelayBeats = 0.75, echoDelayName = "ECHO_DELAY_BEATS",
            swapRampMs = 120, swapRampName = "SWAP_RAMP_BLEND_MS", tempoPolicy = "TEMPO_POLICY=b",
            vocalOverlapBeats = 0.0, energyDelta = 0f, struct = 0.9, vocalScore = 0.9, phraseScore = 0.9,
            energyScore = 0.9, lengthScore = 0.9, keyScore = 0.9, tempoScore = 0.9, cost = 0.0,
        )
        // 1. Instrumental pair: inMid.beats is non-empty (lane(0.0, 0f)), must NOT falsely carve!
        val instScript = TransitionMoves.compile(choiceInst, outBpm = 120.0, inBpm = 120.0)
        val instClock = LoopMix.Clock(instScript.plan)
        val noCarve = LoopMixSession.computeVocalMidCarve(instScript.plan, instClock, instScript, inAnalysis = null, sungIn = null)
        assertFalse("instrumental pair must NOT trigger vocal mid carve", noCarve.apply)

        // 2. M1 with leadInVocal >= 0.2f already writes -6dB via applyPhraseLanes; must NOT clobber.
        val choiceVocal = choiceInst.copy(entry = choiceInst.entry.copy(leadInVocal = 0.7f))
        val vocalScript = TransitionMoves.compile(choiceVocal, outBpm = 120.0, inBpm = 120.0)
        val vocalClock = LoopMix.Clock(vocalScript.plan)
        val noClobber = LoopMixSession.computeVocalMidCarve(vocalScript.plan, vocalClock, vocalScript, inAnalysis = null, sungIn = null)
        assertFalse("must not clobber applyPhraseLanes when script.outMid already carves", noClobber.apply)

        // 3. Bridge Beat blend (script == null) with incoming vocals: must carve in POSITIVE mix-clock seconds!
        val out = track("out", 124.0, duration = 200.0)
        val inn = track("in", 124.0, duration = 200.0)
        val bridgePlan = LoopMix.bridge(out, out.duration, inn, inn.duration, earliestOutSec = 100.0, sungOut = emptyList(), sungIn = emptyList())!!
        val bridgeClock = LoopMix.Clock(bridgePlan)
        val sungIn = listOf(LoopMix.Span(start = inn.downbeats.first() + 3.0, end = inn.downbeats.first() + 8.0))
        val carve = LoopMixSession.computeVocalMidCarve(bridgePlan, bridgeClock, script = null, inAnalysis = inn, sungIn = sungIn)
        assertTrue(carve.apply)
        assertTrue("all outTimes must be >= 0.0 (never negative beats -8/-4/0)", carve.outTimes.all { it >= 0.0 })
        for (i in 1 until carve.outTimes.size) {
            assertTrue("outTimes must be strictly non-decreasing", carve.outTimes[i] > carve.outTimes[i - 1])
        }
        assertEquals(0f, carve.outDb.first(), 1e-6f)
        assertEquals(-6f, carve.outDb.last(), 1e-6f)
        assertTrue("incoming mid stays at 0 dB", carve.inDb.all { it == 0f })
    }

    @Test
    fun echoOutHonorsTheSelectedCutAndPreservesTheColdIncomingAttack() {
        val out = track("out-100", 100.0, duration = 200.0).copy(key = "C major", keyConfidence = 0.9,
            vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(4000)))).withMeasuredEchoTestEvidence()
        val inn = track("in-135", 135.0, duration = 200.0).copy(key = "Eb major", keyConfidence = 0.9,
            vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(4000)))).withMeasuredEchoTestEvidence()
        val plan = LoopMix.bridge(out, out.duration, inn, inn.duration, earliestOutSec = 150.0)!!
        assertEquals("Echo", plan.rung)
        val clock = LoopMix.Clock(plan)
        val outStopAtSec = clock.releaseAt + 0.05 + plan.out.beatSec * 8 + plan.exitTailSeconds
        val env = LoopMixSession.computeEchoOutEnvelopes(plan, clock, outStopAtSec)
        val h = requireNotNull(plan.handoff)
        assertEquals(h.outDryFallStart.mixSec, env.swapAtSec, 1e-9)

        assertTrue(env.feedback in 0.50f..0.58f)
        val sendTimes = doubleArrayOf(0.0, h.outCaptureStart.mixSec,
            h.outDryFallStart.mixSec, h.outSendClose.mixSec)
        assertArrayEquals(sendTimes, env.echoSendTimes, 1e-9)
        assertArrayEquals(sendTimes, env.reverbSendTimes, 1e-9)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.90f, 0f), env.echoSendValues, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.28f, 0f), env.reverbSendValues, 0f)

        val outStart = env.outGainTimes.indexOfFirst { kotlin.math.abs(it - h.outDryFallStart.mixSec) < 1e-9 }
        val outEnd = env.outGainTimes.indexOfLast { kotlin.math.abs(it - h.outDryEnd.mixSec) < 1e-9 }
        assertTrue(outStart >= 1 && outEnd > outStart)
        assertEquals(1f, env.outGainValues[outStart], 0f)
        assertTrue(env.outGainValues.drop(outEnd).all { it == 0f })
        assertEquals(outStopAtSec, env.outGainTimes.last(), 1e-9)
        val fadeTimes = env.outGainTimes.copyOfRange(outStart, outEnd + 1)
        val fadeGains = env.outGainValues.copyOfRange(outStart, outEnd + 1)
        assertArrayEquals(fadeTimes, env.outLowTimes, 0.0)
        assertTrue(fadeGains.asList().zipWithNext().all { (a, b) -> a >= b })
        assertEquals(0f, env.outLowValues.first(), 0f)
        assertEquals(LoopMix.BASS_CUT_DB.toFloat(), env.outLowValues.last(), 0f)
        assertTrue(env.outLowValues.asList().zipWithNext().all { (a, b) -> a >= b })
        val firstIncoming = env.inGainValues.indexOfFirst { it == 1f }
        assertEquals(h.inAudibleStart.mixSec, env.inGainTimes[firstIncoming], 1e-9)
        assertTrue(env.inGainValues.take(firstIncoming).all { it == 0f })
        assertTrue(env.inLpValues.all { it == 20000f })
        assertTrue(120f in env.outHpValues.toList())
        assertEquals(1600f, env.outHpValues.last(), 1e-3f)

        // Contract envelopes never adapt a protected cut to a short decoder result.
        val pcmEnd = h.outDryEnd.mixSec - 0.10
        val short = LoopMixSession.computeEchoOutEnvelopes(plan, clock, outStopAtSec, pcmEnd)
        assertArrayEquals(env.outGainTimes, short.outGainTimes, 0.0)
        assertArrayEquals(env.outGainValues, short.outGainValues, 0f)
        assertArrayEquals(env.inGainTimes, short.inGainTimes, 0.0)
        assertArrayEquals(env.inGainValues, short.inGainValues, 0f)
        assertArrayEquals(env.echoSendTimes, short.echoSendTimes, 0.0)
        assertArrayEquals(env.reverbSendTimes, short.reverbSendTimes, 0.0)
    }

    @Test
    fun contractedKnotsKeepExactSourceEventsDespiteTrackedGridDriftAndRefuseShortPcm() {
        val h = requireNotNull(nativeTiming(100.0, 100.8, 102.0, 102.4,
            0.5, 0.5, 1.4, 3.3, 0.0, 3.0))
        val plan = LoopMix.Plan(out = pick2B(100.0).copy(beatSec = 0.6, bpm = 100.0),
            inn = pick2B(0.5), fold = 1.0, enterBars = 1, rampBars = 0, exitBars = 1,
            totalBars = 2, outLoops = false, inLoops = false, nativeIncoming = true,
            echoTail = true, rung = "Echo", targetBpmOverride = 100.0,
            incomingHoldSec = h.inHoldMixSec, handoff = h)
        val clock = LoopMix.Clock(plan)
        val drift = TrackAnalysis(tailBeats = BeatSide(List(40) { 100.0 + it * 0.624 }, emptyList(), 96.15, 0.9))
        val knots = LoopMixSession.buildDeckKnots(plan, clock, 48000, drift, null, true, 8.0, 1.0)
        assertEquals(null, LoopMixSession.qualifyHandoffKnots(plan, clock, 48000, knots))
        assertEquals(maxOf(h.outDryEnd.mixSec, h.inFullLevelMixSec), clock.handoffAt, 1e-9)
        assertTrue("Adoption must not wait for a later musical landing", clock.handoffAt < h.inLanding.mixSec)
        assertEquals(h.outDryEnd.sourceSec, LoopMixSession.knotSourceAt(knots.outFrames,
            knots.outPositionsSec, h.outDryEnd.mixSec, 48000)!!, 1e-9)
        assertEquals(h.inLanding.sourceSec, LoopMixSession.knotSourceAt(knots.inFrames,
            knots.inPositionsSec, h.inLanding.mixSec, 48000)!!, 1e-9)
        fun region(start: Double, end: Double) = LoopRegion(FloatArray(((end - start) * 48000).roundToLong().toInt()),
            FloatArray(((end - start) * 48000).roundToLong().toInt()), start)
        val incoming = region(0.0, 20.0)
        assertFalse(LoopMixSession.coversHandoffPcm(plan, 48000, knots, region(99.0, 102.3), incoming))
        assertTrue(LoopMixSession.coversHandoffPcm(plan, 48000, knots, region(99.0, 102.5), incoming))
        val bad = DeckKnots(knots.outFrames, knots.outPositionsSec.map { it + 0.04 }.toDoubleArray(),
            knots.inFrames, knots.inPositionsSec)
        assertEquals("handoff_out_knot_mapping", LoopMixSession.qualifyHandoffKnots(plan, clock, 48000, bad))
    }

    @Test
    fun deckKnotsAnchorToRealAnalyzedBeatGridAndRelaxPostHandoffTempo() {
        // Real beats drift slightly (0.51s per beat vs 0.50s nominal) + 15ms splice phase offset.
        val realOutBeats = List(50) { 100.015 + it * 0.51 }
        val realInBeats = List(60) { 0.50 + it * 0.49 }
        val out = track("out-drift", 120.0, duration = 200.0).copy(
            downbeats = List(13) { 100.015 + it * 2.04 },
            tailBeats = BeatSide(realOutBeats, List(13) { 100.015 + it * 2.04 }, 117.65, 0.9),
        )
        val inn = track("in-drift", 122.45, duration = 200.0).copy(
            downbeats = List(15) { 0.50 + it * 1.96 },
            headBeats = BeatSide(realInBeats, List(15) { 0.50 + it * 1.96 }, 122.45, 0.9),
        )
        val plan = LoopMix.Plan(
            out = LoopMix.Pick(100.0, 100.0, 4, 0.50, 120.0, 0.0, LoopMix.Check.MASK),
            inn = LoopMix.Pick(0.50, 0.50, 4, 0.49, 122.45, 0.0, LoopMix.Check.MASK),
            fold = 1.0, enterBars = 1, rampBars = 2, exitBars = 1, totalBars = 4,
            outLoops = false, inLoops = false, rung = "Beat blend",
        )
        val clock = LoopMix.Clock(plan)
        val knots = LoopMixSession.buildDeckKnots(
            plan = plan,
            clock = clock,
            sampleRate = 48000,
            outAnalysis = out,
            inAnalysis = inn,
            outHasTailKnot = true,
            outStopAtSec = clock.releaseAt + 1.0,
            tailRateEarly = 1.0,
        )
        // Beat 0 anchors to exact splice point, beats 1..beats follow real analyzed grid.
        assertEquals(plan.out.loopStart, knots.outPositionsSec[0], 1e-9)
        assertEquals(realOutBeats[8], knots.outPositionsSec[8], 1e-6)
        assertEquals(realOutBeats[16], knots.outPositionsSec[16], 1e-6)

        // IN knots extend through beats + settleBeats and relax smoothly to 1.000x at handover.
        assertEquals(clock.beats + clock.settleBeats + 1, knots.inFrames.size)
        val n = knots.inPositionsSec.size - 1
        val dtFinal = (knots.inFrames[n] - knots.inFrames[n - 1]) / 48000.0
        val finalRate = (knots.inPositionsSec[n] - knots.inPositionsSec[n - 1]) / dtFinal
        assertEquals(1.0, finalRate, 1e-3)
    }

    @Test
    fun adaptivePrimeLeadArmsBetween35And45Seconds() {
        assertEquals(45_000L, adaptivePrimeLeadMs(nextStreamReady = true, blendArmLeadMs = 20_000L))
        assertEquals(35_000L, adaptivePrimeLeadMs(nextStreamReady = false, blendArmLeadMs = 20_000L))
        assertEquals(38_000L, adaptivePrimeLeadMs(nextStreamReady = false, blendArmLeadMs = 38_000L))
    }

    // ---- Task 2B (main) envelope geometry without the native renderer ----

    private fun pick2B(start: Double) = LoopMix.Pick(start, start, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK)

    private fun echoPlan2B() = LoopMix.Plan(
        out = pick2B(150.0), inn = pick2B(0.5), fold = 1.0, enterBars = 1, rampBars = 0, exitBars = 1,
        totalBars = 2, outLoops = false, inLoops = false, nativeIncoming = true,
        echoTail = true, rung = "Echo", targetBpmOverride = 120.0, faderSwapBeats = 1.0,
        exitTailSeconds = 0.8,
    )

    @Test
    fun echoSessionEnvelopeGateAndOverlap() {
        val plan = echoPlan2B()
        val clock = LoopMix.Clock(plan)
        val outStop = clock.releaseAt + 0.05 + plan.out.beatSec * 8 + plan.exitTailSeconds
        val spec = LoopMix.echoSpec(clock, plan, outStopAtSec = outStop)
        val gate = spec.outGainValues.last()
        assertTrue("gain@gate $gate", gate <= 0.001f)
        assertEquals(outStop, spec.outGainTimes.last(), 1e-6)
        assertEquals(0f, spec.inGainValues[1], 1e-6f)
        assertEquals(spec.cutAt, spec.inGainTimes[1], 1e-9)
        val bounds = LoopMix.regionBounds(plan)
        assertTrue("outTo ${bounds.outTo} < $outStop", bounds.outTo >= outStop - 1e-6)
        val outPos = LoopMix.knotTrackPositions(plan, clock, LoopMix.Side.OUT)
        assertTrue(outPos.indices.all { outPos[it] <= outPos[(it + 1).coerceAtMost(outPos.lastIndex)] })
        val (startAt, _) = LoopMixSession.incomingStartFor(clock)
        assertTrue("startAt $startAt", startAt <= clock.elementAt - LoopMixSession.STARTUP_LEAD + 1e-9)
    }

    @Test
    fun vocalScoopTimesArePositiveAndMonotonic() {
        val p = LoopMix.Plan(
            out = pick2B(10.0), inn = pick2B(0.5), fold = 1.0, enterBars = 2, rampBars = 2, exitBars = 2,
            totalBars = 6, outLoops = true, inLoops = true, rung = "Loop", faderSwapBeats = 1.0,
        )
        val clock = LoopMix.Clock(p)
        val scoop = LoopMix.scoopSpec(clock, p, leadInVocal = 0.9f)
        assertNotNull(scoop)
        assertTrue("times=${scoop!!.outMidTimes.contentToString()}", scoop.outMidTimes.all { it >= 0.0 })
        assertTrue(scoop.outMidTimes[0] < scoop.outMidTimes[1] && scoop.outMidTimes[1] < scoop.outMidTimes[2])
    }
}
