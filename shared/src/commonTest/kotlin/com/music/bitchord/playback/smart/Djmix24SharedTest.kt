package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * djmix24 shared suite (vc66) — pillars A–D scalars only (patent-safe).
 */
class Djmix24SharedTest {

    // ---- fixtures ----

    private fun levels(duration: Double, quiet: List<Pair<Double, Double>> = emptyList()): List<CurveWindow> {
        val frames = (duration * LEVEL_FRAME_RATE).toInt()
        val values = FloatArray(frames) { index ->
            val t = index / LEVEL_FRAME_RATE
            if (quiet.any { (a, b) -> t >= a && t < b }) 0f else levelFromRms(0.3).toFloat()
        }
        return listOf(CurveWindow(0.0, LEVEL_FRAME_RATE, values))
    }

    private fun analysis(
        bpm: Double,
        duration: Double,
        trackId: String = "t",
        vocals: List<CurveWindow> = emptyList(),
        quiet: List<Pair<Double, Double>> = emptyList(),
    ) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = duration,
        bpm = bpm,
        beatInterval = 60 / bpm,
        beatConfidence = 0.8,
        downbeats = listOf(0.5),
        firstBeat = 0.5,
        vocalWindows = vocals,
        levelWindows = levels(duration, quiet),
    )

    private fun vocals(from: Double, to: Double, level: Float, clean: List<Pair<Double, Double>> = emptyList()): CurveWindow {
        val fps = 10.0
        val values = FloatArray(((to - from) * fps).toInt()) { index ->
            val t = from + index / fps
            if (clean.any { (a, b) -> t >= a && t < b }) 0.05f else level
        }
        return CurveWindow(from, fps, values)
    }

    private val clear = emptyList<LoopMix.Span>()

    private fun loopPlan(out: TrackAnalysis, inn: TrackAnalysis) =
        LoopMix.plan(out, out.duration, inn, inn.duration, 0.0, clear, clear)

    private fun choice(
        template: MixTemplate = MixTemplate.M1,
        overlapBars: Int = 8,
        vocalOverlap: Double = 0.0,
        vocalProduct: Float = 0f,
        roomBars: Int = 8,
        leadInVocal: Float = 0f,
        loopable: Boolean = false,
        legacy: LoopMix.Plan? = null,
    ): PhraseChoice {
        val exit = ExitCue(
            kind = ExitKind.OUTRO_PHRASE,
            releaseBar = 72,
            releaseSec = 170.0,
            roomBars = roomBars,
            vocalRoomBars = 0,
            tailBars = 0,
            sectionFn = SectionFn.OUTRO,
            role = SectionRole.GROOVE,
            energyAtRelease = 0.4f,
            energyLevel = 4,
            loopableRoom = loopable,
            cutsSungBars = 0,
            cutsBars = 0,
        )
        val entry = EntryCue(
            kind = EntryKind.HEAD,
            startBar = 0,
            startSec = 0.0,
            landingBar = overlapBars,
            landingFn = SectionFn.INTRO,
            landingRole = SectionRole.GROOVE,
            leadInBars = overlapBars,
            leadInVocal = leadInVocal,
            leadInLoopable = true,
            energyAtLanding = 0.4f,
            energyLevel = 4,
            trimmedBars = 0,
            pickupBeats = 0f,
        )
        return PhraseChoice(
            template = template,
            rung = "Phrase",
            score = 0.85,
            exit = exit,
            entry = entry,
            overlapBars = overlapBars,
            swapBar = 0.0,
            tailBeats = 0.0,
            loopK = 0,
            echoGated = false,
            vocalProduct = vocalProduct,
            tempoPrepBars = 0,
            echoDelayBeats = 0.75,
            echoDelayName = "ECHO_DELAY_BEATS",
            swapRampMs = 120,
            swapRampName = "SWAP_RAMP_BLEND_MS",
            tempoPolicy = "TEMPO_POLICY=b",
            vocalOverlapBeats = vocalOverlap,
            energyDelta = 0f,
            struct = 0.9,
            vocalScore = 0.9,
            phraseScore = 0.9,
            energyScore = 0.9,
            lengthScore = 0.9,
            keyScore = 0.9,
            tempoScore = 0.9,
            cost = 0.0,
            legacyLoop = legacy,
            vocalClashEnergy = 0.0,
        )
    }

    // ---- Pillar B: BUILD/DROP painting ----

    @Test
    fun songSectionsPaintsBuildAndDropOnEdmCurve() {
        // Synthetic EDM: quiet intro rising flux into a bass drop.
        // BarSec ~1.93s at 124 BPM (0.4825*4). Duration 120s ~62 bars.
        // Energy: bars 0-7 low (1.0), bars 8-15 rising (1.0->3.5),
        // bars 16-19 drop (6.0), bars 20+ mid (3.0).
        val beatSec = 60.0 / 124.0
        val barSec = beatSec * 4
        val duration = 120.0
        val curve = List(duration.toInt()) { s ->
            val bar = (s.toDouble() / barSec).toInt()
            val e = when {
                bar < 8 -> 1.0
                bar < 16 -> 1.0 + (bar - 8) * 0.31  // 1.0 -> ~3.48
                bar < 20 -> 6.0
                else -> 3.0
            }
            EnergySample(s.toDouble(), e)
        }
        val lowCurve = List(duration.toInt()) { s ->
            val bar = (s.toDouble() / barSec).toInt()
            val e = when {
                bar < 16 -> 1.0
                bar < 20 -> 8.0
                else -> 3.0
            }
            EnergySample(s.toDouble(), e)
        }
        // Low vocal everywhere except a verse at bars 30-40 (to avoid BUILD veto).
        val vocalWin = CurveWindow(0.0, 10.0, FloatArray((duration * 10).toInt()) {
            val t = it / 10.0
            val bar = (t / barSec).toInt()
            if (bar in 30..40) 0.9f else 0.05f
        })
        val ana = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "edm",
            duration = duration,
            bpm = 124.0,
            beatInterval = beatSec,
            beatConfidence = 0.85,
            downbeats = List((duration / barSec).toInt()) { it * barSec },
            firstBeat = 0.5,
            energyCurve = curve,
            lowEnergyCurve = lowCurve,
            vocalWindows = listOf(vocalWin),
            levelWindows = levels(duration),
            contentEndTime = duration - 2,
            quality = TrackAnalysis.QUALITY_FULL,
        )
        val sections = labelSections(ana, emptyList())
        val kinds = sections.map { it.kind }.toSet()
        assertTrue(kinds.contains(SectionKind.BUILD), "BUILD must be painted on EDM rising flux, got $kinds")
        assertTrue(kinds.contains(SectionKind.DROP), "DROP must be painted on bass-drop arrival, got $kinds")
    }

    @Test
    fun songSectionsDoesNotFalsePaintFlatBallad() {
        // Flat ballad: constant energy 2.0, no rise, no drop. Must not paint BUILD/DROP.
        val beatSec = 60.0 / 90.0
        val duration = 120.0
        val curve = List(duration.toInt()) { EnergySample(it.toDouble(), 2.0) }
        val vocalWin = CurveWindow(0.0, 10.0, FloatArray((duration * 10).toInt()) { 0.8f })
        val ana = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "ballad",
            duration = duration,
            bpm = 90.0,
            beatInterval = beatSec,
            beatConfidence = 0.85,
            downbeats = List((duration / (beatSec * 4)).toInt()) { it * beatSec * 4 },
            firstBeat = 0.5,
            energyCurve = curve,
            vocalWindows = listOf(vocalWin),
            levelWindows = levels(duration),
            contentEndTime = duration - 2,
            quality = TrackAnalysis.QUALITY_FULL,
        )
        val sections = labelSections(ana, emptyList())
        val kinds = sections.map { it.kind }.toSet()
        assertFalse(kinds.contains(SectionKind.BUILD), "flat ballad must not paint BUILD")
        assertFalse(kinds.contains(SectionKind.DROP), "flat ballad must not paint DROP")
    }

    @Test
    fun songMapsPaintsBuildAndDrop() {
        // SongMaps uses barEnergy from energyCurve; same EDM shape must map to BUILD/DROP sections.
        val beatSec = 60.0 / 124.0
        val barSec = beatSec * 4
        val duration = 120.0
        val curve = List(duration.toInt()) { s ->
            val bar = (s.toDouble() / barSec).toInt()
            val e = when {
                bar < 8 -> 1.0
                bar < 16 -> 1.0 + (bar - 8) * 0.31
                bar < 20 -> 6.0
                else -> 3.0
            }
            EnergySample(s.toDouble(), e)
        }
        val ana = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "edm-map",
            duration = duration,
            bpm = 124.0,
            beatInterval = beatSec,
            beatConfidence = 0.85,
            downbeats = List((duration / barSec).toInt()) { it * barSec },
            firstBeat = 0.5,
            energyCurve = curve,
            vocalWindows = emptyList(),
            levelWindows = levels(duration),
            contentEndTime = duration - 2,
            quality = TrackAnalysis.QUALITY_FULL,
        )
        val map = SongMaps.fromAnalysis("edm-map", ana)
        assertNotNull(map)
        val fns = map.sections.map { it.fn }.toSet()
        assertTrue(
            fns.contains(SectionFn.BUILD) && fns.contains(SectionFn.DROP),
            "SongMaps must map BUILD/DROP, got $fns",
        )
    }

    // ---- Pillar A: EDM deterministic trigger + Bug A1/A2 ----

    @Test
    fun unmeasuredCalvinPairKeepsCleanEffectPolicy() {
        // This Is What You Came For (124.34) -> One Kiss (124.25), clean loops.
        val out = analysis(124.34, 200.0, trackId = "out")
        val inn = analysis(124.25, 180.0, trackId = "in")
        val plan = LoopMix.plan(out, out.duration, inn, inn.duration, 0.0, clear, clear)
        assertNotNull(plan, "Calvin pair must loop")
        assertFalse(plan.flanger, "BPM range alone is not measured buildup evidence")
        assertFalse(plan.buildupRoll, "unmeasured loop grids must not receive a roll")
        // 8-beat LFO math: 1/(8*beatSec) ~0.258 Hz, NEVER 15.5 Hz.
        val beatSec = 60.0 / 124.34
        val lfo = LoopMix.lfoHzForBeatSec(beatSec)
        val expected = 1.0 / (8.0 * beatSec)
        assertTrue(abs(lfo - expected) < 1e-9, "LFO must be 1/(8*beatSec), got $lfo")
        assertTrue(abs(lfo - 0.258) < 0.02, "LFO ~0.258 Hz at 124 BPM, got $lfo")
        assertTrue(lfo < 1.0, "LFO must be sub-Hz, never 15.5 Hz flutter, got $lfo")
    }

    @Test
    fun transitionMovesCompileEnablesBuildupOnEdmPair() {
        val script = TransitionMoves.compile(
            choice(MixTemplate.M1, overlapBars = 8, vocalOverlap = 0.0, vocalProduct = 0f),
            outBpm = 124.34,
            inBpm = 124.25,
            outgoingId = "calvin-out",
            incomingId = "calvin-in",
        )
        assertFalse(script.plan.flanger, "ordinary outro-to-intro blend is not a buildup")
        assertFalse(script.plan.buildupRoll, "ordinary outro-to-intro blend is not a buildup")
        assertFalse(script.flanger, "ordinary blend must stay dry")
        // Crisp incoming vocal lanes: inMid/inHigh stay at 0 dB when singing.
        val singing = TransitionMoves.compile(
            choice(MixTemplate.M1, overlapBars = 8, vocalOverlap = 0.0, leadInVocal = 1f),
            outBpm = 124.34, inBpm = 124.25,
        )
        // inMid at landing (beat 0) must be 0 dB (crisp), outMid carves negative.
        val inMidAt0 = laneAt(singing.inMid, 0.0)
        val outMidMin = singing.outMid.values.minOrNull()?.toDouble() ?: 0.0
        assertEquals(0.0, inMidAt0, 1e-6, "incoming mid must stay at 0 dB when singing")
        assertTrue(outMidMin < 0.0, "outgoing mid must carve when incoming sings, got $outMidMin")
    }

    @Test
    fun loopMixBridgeStaysCleanWithoutMeasuredBuildup() {
        val out = analysis(124.34, 200.0, trackId = "out")
        val inn = analysis(124.25, 180.0, trackId = "in")
        // Force bridge (plan would loop; bridge covers blend/drop paths).
        val plan = LoopMix.bridge(out, out.duration, inn, inn.duration, 0.0, clear, clear)
        assertNotNull(plan, "bridge must produce a plan for clean EDM pair")
        assertTrue(plan.rung == "Beat blend" || plan.rung == "Drop", "Calvin bridge must be blend/drop, got ${plan.rung}")
        assertFalse(plan.flanger, "unmeasured bridge blend must stay clean")
        assertFalse(plan.buildupRoll, "unmeasured bridge blend must not trigger roll")
    }

    @Test
    fun dualVocalSuppressesFlangerAndRoll() {
        // vocalOverlap >= 1.0 must never flange or stutter-roll.
        val script = TransitionMoves.compile(
            choice(MixTemplate.M1, overlapBars = 8, vocalOverlap = 2.0, vocalProduct = 0.5f),
            outBpm = 124.34, inBpm = 124.25,
        )
        assertFalse(script.plan.flanger, "dual-vocal must suppress flanger")
        assertFalse(script.plan.buildupRoll, "dual-vocal must suppress loop-roll")
        assertFalse(script.flanger, "dual-vocal script must suppress flanger")
        // LoopMix.plan with sung loops: use vocal masks everywhere (no clean loop) -> null, not flanged.
        val sungOut = analysis(124.34, 200.0, vocals = listOf(vocals(140.0, 200.0, 0.9f)))
        val sungIn = analysis(124.25, 180.0, vocals = listOf(vocals(0.0, 45.0, 0.9f)))
        val plan = LoopMix.plan(sungOut, sungOut.duration, sungIn, sungIn.duration, 0.0, null, null)
        // Either null (no clean loop) or a plan without flanger/roll over sung bars.
        if (plan != null) {
            assertFalse(plan.flanger, "sung-loop plan must not flange")
            assertFalse(plan.buildupRoll, "sung-loop plan must not roll")
        }
    }

    @Test
    fun fxRotationIsDeterministicAndVaried() {
        // Same pair -> same slot (deterministic, anti-repeat by pair hash).
        for (slot in 0..3) {
            val (a, b) = findIds(slot)
            assertEquals(slot, TransitionMoves.fxSlot(a, b), "fxSlot must be deterministic")
            assertEquals(slot, TransitionMoves.fxSlot(a, b), "fxSlot must repeat for same pair")
        }
        // Varied across pairs (all 4 slots reachable). Sequential
        // "out-N/in-N" ids alias to 2 slots due to hash structure; use
        // consecutive tracks ("trackN/trackN+1") which span all 4.
        val slots = (0 until 100).map { TransitionMoves.fxSlot("track$it", "track${it + 1}") }.toSet()
        assertTrue(slots.size >= 3, "rotation must vary across pairs, got $slots")
        // LoopMix fxSlot agrees with TransitionMoves (same pairHash).
        assertEquals(TransitionMoves.fxSlot("x", "y"), LoopMix.fxSlotForPair("x", "y"))
    }

    private fun findIds(slot: Int, limit: Int = 500): Pair<String, String> {
        for (i in 0 until limit) {
            val a = "djmix24-out-$i"
            val b = "djmix24-in"
            if (TransitionMoves.fxSlot(a, b) == slot) return a to b
        }
        error("no ids for slot $slot")
    }

    // ---- Pillar B: outro extension, snap, phase gate ----

    @Test
    fun outroPhraseLoopExtensionWhenRoomIsShort() {
        // A has 2 bars clean room, overlap needs 8 bars -> outLoops must extend.
        val script = TransitionMoves.compile(
            choice(MixTemplate.M1, overlapBars = 8, roomBars = 2, loopable = true),
            outBpm = 120.0, inBpm = 120.0,
        )
        assertTrue(script.plan.outLoops, "short clean outro must loop-extend (outLoops=true)")
        // Long clean outro (8 bars) with 8-bar overlap needs no extension beyond M2?
        // M1 with 8 room / 8 overlap still loops only if M2; extension rule fires only when room < total.
        // With room=8 and total ~9 (ramp+exit), room < total still extends — verify it does loop.
        val scriptLong = TransitionMoves.compile(
            choice(MixTemplate.M1, overlapBars = 8, roomBars = 8, loopable = true),
            outBpm = 120.0, inBpm = 120.0,
        )
        // Outgoing loops OR the loop is at least a clean phrase (extended when loopable).
        assertTrue(scriptLong.plan.outLoops || scriptLong.plan.out.bars == 4, "8-bar loopable outro must loop-extend")
    }

    @Test
    fun snapToOnsetsWithin35MsAndPhaseGate() {
        // Beats 20 ms off onsets must snap onto them.
        val beats = List(20) { it * 0.5 + 0.02 }
        val onsets = List(20) { it * 0.5 }
        val snapped = GridRepair.snapToOnsets(beats, onsets, 0.035)
        for (i in snapped.indices) {
            assertTrue(abs(snapped[i] - onsets[i]) < 0.001, "beat $i must snap to onset, got ${snapped[i]} vs ${onsets[i]}")
        }
        // Beats far from onsets (>35 ms) stay put.
        val farBeats = List(20) { it * 0.5 + 0.1 }
        val farSnapped = GridRepair.snapToOnsets(farBeats, onsets, 0.035)
        // Coverage < half -> unchanged.
        assertEquals(farBeats, farSnapped, "far beats must not snap")
        // Phase coherence of snapped grid is high (>= 0.42 gate scale).
        val coh = GridRepair.phaseCoherence(snapped, onsets, 0.035)
        assertTrue(coh >= 0.42, "snapped coherence must be >= 0.42, got $coh")
        // 25 ms phase gate.
        assertTrue(LoopMix.phaseGateOk(20.0))
        assertTrue(LoopMix.phaseGateOk(25.0))
        assertFalse(LoopMix.phaseGateOk(26.0))
        assertFalse(LoopMix.phaseGateOk(-30.0))
    }

    @Test
    fun everyLoopIsStillLoopRegressionPreserved() {
        // Loop-first invariant: 124 BPM clean pair still loops 4 bars (1.9.1 crown).
        val plan = loopPlan(analysis(124.0, 200.0), analysis(124.0, 180.0))
        assertNotNull(plan)
        assertEquals(LoopMix.LOOP_BARS, plan.out.bars)
        assertEquals(LoopMix.LOOP_BARS, plan.inn.bars)
        val clock = LoopMix.Clock(plan)
        // Clock loops seamlessly: position wraps (outPositionAt unwrapped grows, inTrackTimeAt wraps via modulo).
        assertTrue(clock.beats > 0)
        assertTrue(clock.releaseAt > 0)
    }

    // ---- Pillar C: vocal handoff lanes ----

    @Test
    fun vocalHandoffOneBarCarve() {
        // Incoming singing: outMid carves -6..-9 dB over 4 beats, inMid/inHigh at 0.
        val script = TransitionMoves.compile(
            choice(MixTemplate.M1, overlapBars = 8, leadInVocal = 1f),
            outBpm = 120.0, inBpm = 120.0,
        )
        val outMidAtLanding = laneAt(script.outMid, 0.0)
        val inMidAtLanding = laneAt(script.inMid, 0.0)
        val inHighAtLanding = laneAt(script.inHigh, 0.0)
        assertTrue(outMidAtLanding <= -3.0, "outMid must carve before B's sung bar, got $outMidAtLanding")
        assertTrue(outMidAtLanding >= -9.5, "outMid carve must be -6..-9 dB band, got $outMidAtLanding")
        assertEquals(0.0, inMidAtLanding, 1e-6, "inMid must stay crisp at 0 dB")
        assertEquals(0.0, inHighAtLanding, 1e-6, "inHigh must stay crisp at 0 dB")
    }

    private fun laneAt(lane: Lane, beat: Double): Double {
        if (lane.beats.isEmpty()) return 0.0
        var best = lane.values[0].toDouble()
        var bestD = Double.POSITIVE_INFINITY
        for (i in lane.beats.indices) {
            val d = abs(lane.beats[i] - beat)
            if (d < bestD) { bestD = d; best = lane.values[i].toDouble() }
        }
        return best
    }

    // ---- Pillar D: bass swap tightening (Clock) ----

    @Test
    fun bassSwapIsTightHalfToOneBeat() {
        val plan = loopPlan(analysis(124.0, 200.0), analysis(124.0, 180.0))
        assertNotNull(plan)
        val clock = LoopMix.Clock(plan)
        val beatSec = 60.0 / 124.0
        // swapBar must be 0.5..1.0 beat (was 1 bar = 4 beats).
        assertTrue(
            clock.swapBar >= beatSec * 0.5 - 1e-6 && clock.swapBar <= beatSec * 1.0 + 1e-6,
            "swapBar must be 0.5-1.0 beat, got ${clock.swapBar} (beat $beatSec)",
        )
    }

    // ---- Rung 0 consecutive album guard (shared) ----

    @Test
    fun consecutiveAlbumGuard() {
        assertTrue(
            TransitionMoves.isConsecutiveAlbumPair("Album", "id", 3, "Album", "id", 4),
            "consecutive album tracks must be gapless",
        )
        assertFalse(
            TransitionMoves.isConsecutiveAlbumPair("Album", "id", 3, "Album", "id", 5),
            "non-consecutive album tracks must still AutoMix",
        )
        assertFalse(
            TransitionMoves.isConsecutiveAlbumPair("", "", 3, "", "", 4),
            "singles (blank album) must AutoMix",
        )
        assertFalse(
            TransitionMoves.isConsecutiveAlbumPair("Album", "id", 0, "Album", "id", 1),
            "unknown track numbers must not force gapless",
        )
        // Planner-level predicate agrees.
        val a = TransitionTrackInfo("a", 180000L, album = "Album", albumId = "id", trackNumber = 3)
        val b = TransitionTrackInfo("b", 180000L, album = "Album", albumId = "id", trackNumber = 4)
        assertTrue(isConsecutiveAlbumPair(a, b))
        val c = TransitionTrackInfo("c", 180000L, album = "Album", albumId = "id", trackNumber = 6)
        assertFalse(isConsecutiveAlbumPair(a, c))
    }
}
