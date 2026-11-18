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
import kotlin.test.assertFalse

/**
 * djmix20 (vc62) shared regression suite:
 * P0: decline gate, analyse-ahead (HEAD next usable), loop-build drop.
 * P1: outro-loop drop, phrase extension, echo-past-EOF lane.
 * Flanger: per-pair deterministic, low-vocal only, never on echo gap.
 * Showcase fixtures: Calvin Harris x2 (124/124) and Technologic x Guess.
 */
class Djmix20RegressionTest {

    // ---- helpers ----
    private fun window(duration: Double, value: Float, from: Double = 0.0, to: Double? = null): CurveWindow {
        val end = to ?: duration
        val n = ((end - from) * 10.0).toInt()
        val values = FloatArray(n) { value }
        return CurveWindow(from, 10.0, values)
    }

    private fun track(
        id: String, bpm: Double, key: String = "C major", vocal: Double = 0.3,
        duration: Double = 180.0, contentEnd: Double? = null, audibleStart: Double = 0.5,
        quality: String = TrackAnalysis.QUALITY_FULL, mixInTime: Double = 0.0,
        keyConfidence: Double = 0.9,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = List(((duration / (4 * beat)) + 1).toInt()) { it * 4 * beat + 0.5 }
        val energy = List((duration).toInt()) { EnergySample(it.toDouble(), 1.0) }
        val mask = List((duration).toInt()) { vocal }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id, duration = duration, bpm = bpm, beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { i, _ -> i % 8 == 0 },
            firstBeat = 0.5,
            key = key, keyConfidence = keyConfidence,
            audibleStartTime = audibleStart,
            introEndTime = 10.0,
            contentEndTime = contentEnd ?: (duration - 2),
            mixInTime = mixInTime,
            energyCurve = energy,
            vocalActivityMask = mask,
            vocalProbability = vocal,
            quality = quality,
        )
    }

    private fun plan(out: TrackAnalysis, inn: TrackAnalysis, advanced: Boolean = true, at: Double = 10.0) = planTransition(
        analysis = out, nextAnalysis = inn,
        currentTrack = TransitionTrackInfo(out.trackId, (out.duration * 1000).toLong()),
        nextTrack = TransitionTrackInfo(inn.trackId, (inn.duration * 1000).toLong()),
        currentTime = at, duration = out.duration, mode = CrossfadeMode.SMART, advanced = advanced,
    )

    // ---- P0 decline gate ----
    @Test
    fun declineFiresOnUnmixablePair() {
        val out = track("a", 120.0, "C major", vocal = 0.9)
        val inn = track("b", 94.0, "F\u266F major", vocal = 0.9)
        assertTrue(shouldDeclineToMix(out, inn), "unmixable must decline")
        assertNotNull(declineToMixDetail(out, inn))
        val p = plan(out, inn)
        assertEquals(TransitionStyle.DJ_FILTER, p.transitionStyle, "reason=${p.reason}")
        assertTrue(p.fadeSeconds in 3.5..4.5, "fade=${p.fadeSeconds}")
        assertTrue(p.reason.contains("decline-to-mix"))
        assertEquals(inn.audibleStartTime!!, p.incomingCueTime, 1e-6, "cue at audible start")
        assertTrue(p.transitionEnd <= out.contentEndTime + 1e-6, "ends at content_end")
    }

    @Test
    fun declineDoesNotFireOnBeautifulPair() {
        val out = track("beautiful", 99.93, "C major", vocal = 0.8)
        val inn = track("never-let-go", 104.50, "C major", vocal = 0.75)
        assertFalse(shouldDeclineToMix(out, inn))
        val p = plan(out, inn)
        assertTrue(p.transitionStyle == TransitionStyle.DJ_BLEND || p.transitionStyle == TransitionStyle.DJ_FILTER, "style=${p.transitionStyle} reason=${p.reason}")
        assertFalse(p.reason.contains("decline"), "reason=${p.reason}")
    }

    @Test
    fun declineDoesNotFireOnFoldedDripToUPPair() {
        // Drip 78.89 -> UP 162.01, fold 2 => 81.0 folded, delta ~2.6% <8%
        val out = track("drip", 78.89, "A minor", vocal = 0.7)
        val inn = track("up", 162.01, "A minor", vocal = 0.7)
        assertFalse(shouldDeclineToMix(out, inn))
        val p = plan(out, inn)
        assertFalse(p.reason.contains("decline"), "reason=${p.reason} style=${p.transitionStyle}")
        assertTrue(p.transitionStyle == TransitionStyle.DJ_BLEND || p.transitionStyle == TransitionStyle.DJ_FILTER, "style=${p.transitionStyle}")
    }

    // ---- showcase fixtures (user-cited Apple wins, no device proof) ----
    @Test
    fun showcaseCalvinHarrisPairBeatmixesNotDeclines() {
        val out = track("this-is-what-you-came-for", 124.0, "C major", vocal = 0.3)
        val inn = track("one-kiss", 124.0, "C major", vocal = 0.3)
        assertFalse(shouldDeclineToMix(out, inn))
        val p = plan(out, inn)
        assertEquals(TransitionStyle.DJ_BLEND, p.transitionStyle, "124/124 same producer must beatmix; reason=${p.reason}")
        assertFalse(p.transitionStyle == TransitionStyle.DJ_FILTER, "must be DJ_BLEND not filter")
    }

    @Test
    fun showcaseTechnologicToGuessDoesNotDeclineAndStaysShort() {
        // Technologic ~124, Guess ~130 (folded delta ~4.8% <8%)
        val out = track("technologic", 124.0, "C major", vocal = 0.8)
        val inn = track("guess", 130.0, "C major", vocal = 0.8)
        assertFalse(shouldDeclineToMix(out, inn))
        val p = plan(out, inn)
        assertFalse(p.reason.contains("decline"), "reason=${p.reason}")
        assertTrue(p.transitionStyle == TransitionStyle.DJ_BLEND || p.transitionStyle == TransitionStyle.DJ_FILTER, "style=${p.transitionStyle}")
        val barSec = 4 * 60.0 / 124.0
        val bars = p.fadeSeconds / barSec
        assertTrue(bars <= 8.05, "robot-vocal pair must not take a long vocal-clash blend; bars=$bars fade=${p.fadeSeconds}")
    }

    // ---- P0-1 analyse-ahead at planner level: HEAD next is usable ----
    @Test
    fun headAnalysisOfNextIsUsableForEntry() {
        val out = track("a", 120.0, "C major", vocal = 0.3)
        val innHead = track("b", 121.0, "C major", vocal = 0.3, quality = TrackAnalysis.QUALITY_HEAD)
        assertEquals(TrackAnalysis.QUALITY_HEAD, innHead.quality)
        val p = plan(out, innHead)
        assertFalse(p.reason.contains("smart-analysis-fallback"), "HEAD next must not fallback; reason=${p.reason}")
        assertEquals(TransitionStyle.DJ_BLEND, p.transitionStyle, "reason=${p.reason}")
    }

    // ---- outro-loop -> incoming drop ----
    @Test
    fun outroLoopDropBridgeOutLoopsInStraight() {
        val out = track("a", 124.0, "C major", vocal = 0.05).copy(
            vocalWindows = listOf(window(180.0, 0.02f)),
            levelWindows = emptyList(),
        )
        val inn = track("b", 124.0, "C major", vocal = 0.05).copy(
            vocalWindows = listOf(window(180.0, 0.02f)),
        )
        val b = LoopMix.bridge(out, out.duration, inn, inn.duration, earliestOutSec = 120.0)
        assertNotNull(b, "instrumental outro should bridge")
        // Prefer Drop rung when available; otherwise a beat blend is also a
        // valid bridge but must NOT decline. Assert Drop specifically.
        assertEquals("Drop", b.rung, "rung=${b.rung} outLoops=${b.outLoops} inLoops=${b.inLoops}")
        assertTrue(b.outLoops, "OUT must loop")
        assertFalse(b.inLoops, "IN plays straight into drop landing")
        assertTrue(b.nativeIncoming, "incoming native")
    }

    @Test
    fun sungOutroIsNotLoopableSoDropDoesNotFire() {
        // Beautiful-like sung outro (vocal 0.55) must not be loop-cut.
        val out = track("beautiful", 99.93, "C major", vocal = 0.8).copy(
            vocalWindows = listOf(window(180.0, 0.55f)),
            vocalActivityMask = List(180) { 0.55 },
        )
        val inn = track("never-let-go", 104.50, "C major", vocal = 0.3).copy(
            vocalWindows = listOf(window(180.0, 0.02f)),
        )
        val b = LoopMix.bridge(out, out.duration, inn, inn.duration, earliestOutSec = 120.0)
        // Sung outro: Drop must not be chosen; blend/echo may still be.
        if (b != null) {
            assertFalse(b.rung == "Drop" && b.outLoops, "sung outro looped: $b")
        }
    }

    // ---- phrase extension via LoopMix.Clock loop counts ----
    @Test
    fun clockLoopsShortOutroPhraseToBridgeLongLeadIn() {
        val out = track("a", 120.0, "C major", vocal = 0.02).copy(
            vocalWindows = listOf(window(180.0, 0.02f)),
        )
        val inn = track("b", 120.0, "C major", vocal = 0.02).copy(
            vocalWindows = listOf(window(180.0, 0.02f)),
        )
        // Force a long plan via bridge Drop (outLoops) with extra bars: build a
        // manual Plan equivalent to M2-style extension and check Clock wraps.
        val outPick = LoopMix.Pick(loopStart = 100.0, loopEnd = 102.0, bars = 4, beatSec = 0.5, bpm = 120.0, vocal = 0.0, checkedBy = LoopMix.Check.MASK)
        val inPick = LoopMix.Pick(loopStart = 0.5, loopEnd = 0.5, bars = 4, beatSec = 0.5, bpm = 120.0, vocal = 0.0, checkedBy = LoopMix.Check.MASK)
        val plan = LoopMix.Plan(out = outPick, inn = inPick, fold = 1.0, enterBars = 1, rampBars = 1, exitBars = 26, totalBars = 28, outLoops = true, inLoops = false, nativeIncoming = true, rung = "Drop")
        val clock = LoopMix.Clock(plan)
        // Out depth: positionAt wraps the 4-bar (16-beat) loop, so t=0 and
        // t=beatTimes[16] (16 beats, 8 s) are the same track position, while
        // the incoming deck (inLoops=false) strictly advances by 8 s.
        val t16 = clock.beatTimes[16]
        val out0 = LoopMix.positionAt(clock, LoopMix.Side.OUT, 0.0)!!
        val out16 = LoopMix.positionAt(clock, LoopMix.Side.OUT, t16)!!
        assertEquals(out0, out16, 1e-9)
        val in0 = LoopMix.positionAt(clock, LoopMix.Side.IN, 0.0)!!
        // Incoming uses nativeIncoming in this fixture (position = loopStart + t)
        val in16 = LoopMix.positionAt(clock, LoopMix.Side.IN, t16)!!
        assertEquals(8.0, in16 - in0, 1e-9)
        assertTrue(clock.beats >= 28 * 4, "beats=${clock.beats}")
        assertTrue(clock.beats - 16 >= 0, "short phrase repeats to bridge long lead-in")
    }

    // ---- echo past EOF lane ----
    @Test
    fun echoLaneRingsPastLastBeatWithExitTail() {
        val exit = ExitCue(ExitKind.OUTRO_PHRASE, 72, 170.0, 8, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE, 0.4f, 4, true, 0, 0)
        val entry = EntryCue(EntryKind.HEAD, 0, 0.0, 8, SectionFn.INTRO, SectionRole.GROOVE, 8, 0f, true, 0.4f, 4, 0, 0f)
        val choice = PhraseChoice(
            template = MixTemplate.M6, rung = "Echo", score = 0.9, exit = exit, entry = entry,
            overlapBars = 0, swapBar = 0.0, tailBeats = 0.0, loopK = 0, echoGated = true,
            vocalProduct = 0f, tempoPrepBars = 0, echoDelayBeats = 0.75, echoDelayName = "ECHO_DELAY_BEATS",
            swapRampMs = 120, swapRampName = "SWAP_RAMP_BLEND_MS", tempoPolicy = "TEMPO_POLICY=b",
            vocalOverlapBeats = 0.0, energyDelta = 0f, struct = 0.9, vocalScore = 0.9, phraseScore = 0.9,
            energyScore = 0.9, lengthScore = 0.9, keyScore = 0.9, tempoScore = 0.9, cost = 0.0,
            vocalClashEnergy = 0.0,
        )
        val script = TransitionMoves.compile(choice, 120.0, 120.0)
        assertTrue(script.plan.exitTailSeconds >= 0.8, "exitTail=${script.plan.exitTailSeconds}")
        assertEquals(TransitionMoves.MUTE_DB, script.outGain.values.last(), 1e-6f)
        assertTrue(script.outGain.beats.last() >= script.outGain.beats.first(), "lane must extend")
        // At least 2 beats of dissolve past the last content knot.
        val tailBeats = script.plan.exitTailSeconds / 0.5
        assertTrue(script.outGain.beats.last() >= 2.0, "mute beat=${script.outGain.beats.last()} tailBeats=$tailBeats")
    }

    @Test
    fun loopMixEchoPlanCarriesExitTail() {
        val out = track("a", 120.0, "C major", vocal = 0.02)
        val inn = track("b", 140.0, "C major", vocal = 0.02)
        val b = LoopMix.bridge(out, out.duration, inn, inn.duration, earliestOutSec = 100.0)
        // Far tempo 120->140 (16% >8%) but same key + instrumental => not decline;
        // bridge may be Drop/blend/echo. If echo, its tail must be >0.
        if (b != null && b.rung == "Echo") {
            assertTrue(b.exitTailSeconds >= 0.8, "echo tail ${b.exitTailSeconds}")
            val bounds = LoopMix.regionBounds(b)
            assertTrue(bounds.outTo >= b.out.loopStart + 2 * b.out.beatSec, "bounds cover tail")
        } else if (b != null) {
            assertFalse(b.rung == "Echo" && b.exitTailSeconds < 0.8, "echo without tail")
        }
    }

    // ---- flanger ----
    private fun findIds(slot: Int, limit: Int = 500): Pair<String, String> {
        for (i in 0 until limit) {
            val a = "flanger-out-$i"; val b = "flanger-in"
            if (pairHash(a, b) % 4 == slot) return a to b
        }
        for (i in 0 until limit) {
            val a = "flanger-out"; val b = "flanger-in-$i"
            if (pairHash(a, b) % 4 == slot) return a to b
        }
        throw AssertionError("no pairHash slot $slot")
    }

    @Test
    fun flangerFiresOnEligibleLowVocalBeatmixForRightPairHash() {
        val (outId, inId) = findIds(0)
        assertTrue(pairHash(outId, inId) % 4 == 0)
        assertEquals(DjEffect.FLANGER, blendColour(0.05, outId, inId, measured = true, buildup = true))
        val settings = effectSettings(DjEffect.FLANGER)
        assertTrue(settings.wet <= 0.42f, "wet=${settings.wet}")
        assertEquals(8f, settings.beats, 1e-6f)
        assertEquals(8.0, PhraseConfig.FLANGER_LFO_BEATS)
        // Annotate a low-vocal DJ_BLEND: colour must be FLANGER.
        val raw = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 140.0, transitionEnd = 176.0, fadeSeconds = 36.0,
            beatSeconds = 0.5, vocalOverlap = 0.05, bassSwap = true, reason = "smart-duration",
        )
        val out = track("a", 120.0, "C major", vocal = 0.05)
        val inn = track("b", 120.0, "C major", vocal = 0.05)
        val ann = DjMix.annotate(raw, out, inn, outgoingId = outId, incomingId = inId)
        assertEquals(DjEffect.NONE, ann.djEffect, "flat arrangement is not a buildup")
        // Loop path: TransitionMoves M1 with same ids must set flanger.
        val exit = ExitCue(ExitKind.OUTRO_PHRASE, 72, 170.0, 8, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE, 0.4f, 4, true, 0, 0)
        val entry = EntryCue(EntryKind.HEAD, 0, 0.0, 8, SectionFn.INTRO, SectionRole.GROOVE, 8, 0f, true, 0.4f, 4, 0, 0f)
        val choice = PhraseChoice(
            template = MixTemplate.M1, rung = "Beat blend", score = 0.9, exit = exit, entry = entry,
            overlapBars = 8, swapBar = -4.0, tailBeats = 1.0, loopK = 0, echoGated = false,
            vocalProduct = 0f, tempoPrepBars = 0, echoDelayBeats = 0.75, echoDelayName = "ECHO_DELAY_BEATS",
            swapRampMs = 120, swapRampName = "SWAP_RAMP_BLEND_MS", tempoPolicy = "TEMPO_POLICY=b",
            vocalOverlapBeats = 0.0, energyDelta = 0f, struct = 0.9, vocalScore = 0.9, phraseScore = 0.9,
            energyScore = 0.9, lengthScore = 0.9, keyScore = 0.9, tempoScore = 0.9, cost = 0.0,
        )
        val buildupChoice = choice.copy(vocalClashEnergy = 0.0, fxGridUsable = true,
            exit = choice.exit.copy(sectionFn = SectionFn.BUILD))
        val script = TransitionMoves.compile(buildupChoice, 120.0, 120.0, outgoingId = outId, incomingId = inId)
        assertTrue(script.flanger, "M1 low-vocal slot 0 must flange on loop path")
        assertEquals(script.plan.flanger, script.flanger)
        assertFalse(script.plan.buildupRoll, "selected flanger must not silently add a roll")
        assertFalse(script.plan.reverbWash, "selected flanger must not silently add reverb")
        assertFalse(TransitionMoves.compile(buildupChoice.copy(fxGridUsable = false), 120.0, 120.0,
            outgoingId = outId, incomingId = inId).flanger)
    }

    @Test
    fun flangerNeverOnDualVocalClash() {
        val (outId, inId) = findIds(0)
        assertTrue(blendColour(0.85, outId, inId, measured = true) != DjEffect.FLANGER, "dual vocal must not flange")
        val raw = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 140.0, transitionEnd = 176.0, fadeSeconds = 36.0,
            beatSeconds = 0.5, vocalOverlap = 0.85, bassSwap = true, reason = "smart-duration",
        )
        val out = track("a", 120.0, "C major", vocal = 0.9)
        val inn = track("b", 120.0, "C major", vocal = 0.9)
        val ann = DjMix.annotate(raw, out, inn, outgoingId = outId, incomingId = inId)
        assertTrue(ann.djEffect != DjEffect.FLANGER, "dual vocal got ${ann.djEffect}")
        val exit = ExitCue(ExitKind.OUTRO_PHRASE, 72, 170.0, 8, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE, 0.4f, 4, true, 0, 0)
        val entry = EntryCue(EntryKind.HEAD, 0, 0.0, 8, SectionFn.INTRO, SectionRole.GROOVE, 8, 0f, true, 0.4f, 4, 0, 0f)
        val choice = PhraseChoice(
            template = MixTemplate.M1, rung = "Beat blend", score = 0.9, exit = exit, entry = entry,
            overlapBars = 8, swapBar = -4.0, tailBeats = 1.0, loopK = 0, echoGated = false,
            vocalProduct = 0.9f, tempoPrepBars = 0, echoDelayBeats = 0.75, echoDelayName = "ECHO_DELAY_BEATS",
            swapRampMs = 120, swapRampName = "SWAP_RAMP_BLEND_MS", tempoPolicy = "TEMPO_POLICY=b",
            vocalOverlapBeats = 4.0, energyDelta = 0f, struct = 0.9, vocalScore = 0.4, phraseScore = 0.9,
            energyScore = 0.9, lengthScore = 0.9, keyScore = 0.9, tempoScore = 0.9, cost = 0.0,
        )
        val script = TransitionMoves.compile(choice, 120.0, 120.0, outgoingId = outId, incomingId = inId)
        assertFalse(script.flanger, "M1 high vocalProduct must not flange")
    }

    @Test
    fun flangerNeverReplacesTempoGapEchoOrReverb() {
        val (outId, inId) = findIds(0)
        val gap = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_FILTER,
            filterVariant = FilterTransitionVariant.ECHO_RIDE, echoSeconds = 0.6,
            transitionStart = 160.0, transitionEnd = 176.0, fadeSeconds = 8.0, beatSeconds = 0.6,
        )
        val out = track("a", 120.0, "C major", vocal = 0.02)
        val inn = track("b", 140.0, "C major", vocal = 0.02)
        val ann = DjMix.annotate(gap, out, inn, outgoingId = outId, incomingId = inId)
        assertTrue(ann.djEffect == DjEffect.ECHO || ann.djEffect == DjEffect.REVERB, "echo gap got ${ann.djEffect}")
        assertTrue(ann.djEffect != DjEffect.FLANGER)
        // M6 (Echo template) on loop path must never flange even with flanger ids.
        val exit = ExitCue(ExitKind.OUTRO_PHRASE, 72, 170.0, 8, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE, 0.4f, 4, true, 0, 0)
        val entry = EntryCue(EntryKind.HEAD, 0, 0.0, 8, SectionFn.INTRO, SectionRole.GROOVE, 8, 0f, true, 0.4f, 4, 0, 0f)
        val echoChoice = PhraseChoice(
            template = MixTemplate.M6, rung = "Echo", score = 0.9, exit = exit, entry = entry,
            overlapBars = 0, swapBar = 0.0, tailBeats = 0.0, loopK = 0, echoGated = true,
            vocalProduct = 0f, tempoPrepBars = 0, echoDelayBeats = 0.75, echoDelayName = "ECHO_DELAY_BEATS",
            swapRampMs = 120, swapRampName = "SWAP_RAMP_BLEND_MS", tempoPolicy = "TEMPO_POLICY=b",
            vocalOverlapBeats = 0.0, energyDelta = 0f, struct = 0.9, vocalScore = 0.9, phraseScore = 0.9,
            energyScore = 0.9, lengthScore = 0.9, keyScore = 0.9, tempoScore = 0.9, cost = 0.0,
        )
        val script = TransitionMoves.compile(echoChoice, 120.0, 140.0, outgoingId = outId, incomingId = inId)
        assertFalse(script.flanger, "M6 echo must not flange")
    }
}
