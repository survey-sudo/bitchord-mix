package com.music.bitchord.playback.smart.choreo

import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AUTOMIX-V2(E): the choreographer's contract, on synthetic plans only.
 * Requirement gating + fallback, the no-doubled-bass invariant, simple fade
 * on incompatible pairs, the drop landing, and the variety rule.
 */
class ChoreographerTest {

    @BeforeTest
    fun reset() {
        AutomixV2Choreo.enabled = true
        AutomixV2Choreo.useStems = true
        AutomixV2Choreo.seed = 0L
        AutomixV2Choreo.history.clear()
    }

    private fun track(
        id: String,
        bpm: Double,
        key: String = "C major",
        keyConfidence: Double = 0.8,
        beatConfidence: Double = 0.9,
        vocal: Double = 0.3,
    ) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = id,
        duration = 240.0,
        bpm = bpm,
        beatInterval = 60.0 / bpm,
        beatConfidence = beatConfidence,
        key = key,
        keyConfidence = keyConfidence,
        vocalProbability = vocal,
        quality = TrackAnalysis.QUALITY_FULL,
    )

    private fun pick(start: Double, bpm: Double) =
        LoopMix.Pick(start, start, 4, 60.0 / bpm, bpm, 0.0, LoopMix.Check.MASK)

    /** A beat-matched straight blend: both decks from t0, constant tempo, [bars] bars to the release. */
    private fun blendPlan(bpm: Double, bars: Int = 8, inStart: Double = 10.0) = LoopMix.Plan(
        out = pick(200.0, bpm),
        inn = pick(inStart, bpm),
        fold = 1.0,
        enterBars = 0,
        rampBars = 0,
        exitBars = bars,
        totalBars = bars,
        outLoops = false,
        inLoops = false,
        nativeIncoming = true,
        rung = "Beat blend",
    )

    /** An echo-shaped plan: incoming held for one bar, native tempo, not beat-matched. */
    private fun echoPlan(outBpm: Double, inBpm: Double): LoopMix.Plan {
        val bar = 4 * 60.0 / outBpm
        return LoopMix.Plan(
            out = pick(200.0, outBpm),
            inn = pick(0.5, inBpm),
            fold = 1.0,
            enterBars = 1,
            rampBars = 0,
            exitBars = 1,
            totalBars = 2,
            outLoops = false,
            inLoops = false,
            nativeIncoming = true,
            echoTail = true,
            rung = "Echo",
            targetBpmOverride = outBpm,
            incomingHoldSec = bar,
        )
    }

    private fun stems(vocal: Double = 0.1, bass: Double = 0.3) = StemView(
        stemCount = 4,
        confidence = 0.9f,
        regionStartSec = 0.0,
        sampleRate = 1000,
        frames = 1000 * 600,
    ) { part, _, _ ->
        when (part) {
            StemPart.ALL -> 1.0
            StemPart.VOCALS -> vocal
            StemPart.BASS -> bass
            else -> 0.5
        }
    }

    private fun inputs(
        plan: LoopMix.Plan,
        out: TrackAnalysis,
        inn: TrackAnalysis,
        level: DjLevel = DjLevel.SUBTLE,
        caps: Int = 0,
        withStems: Boolean = false,
        inStructure: StructureView = StructureView.EMPTY,
        history: List<Move> = emptyList(),
    ) = ChoreoInputs(
        plan = plan,
        clock = LoopMix.Clock(plan),
        out = out,
        inn = inn,
        outStructure = StructureView.EMPTY,
        inStructure = inStructure,
        outStems = if (withStems) stems() else null,
        inStems = if (withStems) stems() else null,
        caps = caps,
        level = level,
        pairKey = "${out.trackId}->${inn.trackId}",
        history = history,
    )

    @Test
    fun movesNeedingStemsOrCapsAreGatedAndFallBack() {
        val out = track("a", 124.0)
        val inn = track("b", 124.0)
        val skipped = ArrayList<String>()
        val ranked = Choreographer.rank(inputs(blendPlan(124.0), out, inn, level = DjLevel.FESTIVAL), skipped)
        val moves = ranked.map { it.move }.toSet()
        // No stems, no v2 caps: every stem/cap move is skipped, with its reason logged.
        for (m in listOf(Move.DRUMS_FIRST, Move.DRUM_SWAP, Move.ACAPELLA_OVER_INSTRUMENTAL, Move.BRAKE_CUT, Move.PHASER_DISSOLVE, Move.PUMP_BLEND)) {
            assertFalse(m in moves, "$m must be gated without stems/caps")
            assertTrue(skipped.any { it.startsWith(m.name) }, "$m skip reason missing")
        }
        // The bass swap falls back to the EQ kill instead of disappearing.
        assertTrue(Move.STEM_BASS_SWAP in moves)
        val script = assertNotNull(Choreographer.choose(inputs(blendPlan(124.0), out, inn), forced = Move.STEM_BASS_SWAP))
        assertFalse(script.outUsesStems || script.inUsesStems)
        val swap = script.swapAt
        assertEquals(MoveCatalog.KILL_DB, script.value(true, EnvKind.LOW_DB, swap + 0.2), 0.01f)
        assertEquals(0f, script.value(false, EnvKind.LOW_DB, swap + 0.2), 0.01f)
        assertEquals(MoveCatalog.KILL_DB, script.value(false, EnvKind.LOW_DB, swap - 0.2), 0.01f)
        // CLUB-only moves are not offered at SUBTLE even with stems and every cap.
        val subtle = Choreographer.rank(inputs(blendPlan(124.0), out, inn, caps = ChoreoCaps.ALL, withStems = true)).map { it.move }
        assertFalse(Move.DRUM_SWAP in subtle)
        assertFalse(Move.BRAKE_CUT in subtle)
        // ...and become available at CLUB.
        val club = Choreographer.rank(inputs(blendPlan(124.0), out, inn, level = DjLevel.CLUB, caps = ChoreoCaps.ALL, withStems = true)).map { it.move }
        assertTrue(Move.DRUM_SWAP in club)
    }

    @Test
    fun stemBassSwapNeverDoublesTheLowEnd() {
        val bpm = 124.0
        val plan = blendPlan(bpm)
        val inp = inputs(plan, track("a", bpm), track("b", bpm), caps = ChoreoCaps.STEMS, withStems = true)
        val script = assertNotNull(Choreographer.choose(inp, forced = Move.STEM_BASS_SWAP))
        assertTrue(script.outUsesStems && script.inUsesStems)
        val clock = LoopMix.Clock(plan)
        val beat = 60.0 / bpm
        // The swap is on a bar line of the mix clock.
        assertEquals(0, script.swapBeat % 4)
        assertEquals(clock.beatTimes[script.swapBeat], script.swapAt, 1e-9)
        var t = 0.0
        var both = 0.0
        while (t <= clock.releaseAt) {
            val lo = ChoreoGuard.lowLevel(script.outLanes, true, t)
            val li = ChoreoGuard.lowLevel(script.inLanes, true, t)
            assertFalse(lo >= 0.9f && li >= 0.9f, "bass doubled at $t: out=$lo in=$li")
            both = if (lo > 0.5f && li > 0.5f) both + 0.001 else 0.0
            assertTrue(both <= beat, "both lows above -6 dB for over a beat at $t")
            t += 0.001
        }
        // Incoming bass stem is shut until the downbeat and fully open right after it; outgoing the reverse.
        assertEquals(0f, script.value(false, EnvKind.STEM_BASS, script.swapAt - 0.1), 1e-4f)
        assertEquals(1f, script.value(false, EnvKind.STEM_BASS, script.swapAt + 0.1), 1e-4f)
        assertEquals(1f, script.value(true, EnvKind.STEM_BASS, script.swapAt - 0.1), 1e-4f)
        assertEquals(0f, script.value(true, EnvKind.STEM_BASS, script.swapAt + 0.1), 1e-4f)
    }

    @Test
    fun incompatiblePairsGetTheSimpleFade() {
        // Low-confidence grids, 92 -> 128 BPM, clashing keys, two singers.
        val shaky = Choreographer.choose(
            inputs(
                echoPlan(92.0, 128.0),
                track("ballad", 92.0, key = "C major", beatConfidence = 0.3, vocal = 0.8),
                track("club", 128.0, key = "F# major", beatConfidence = 0.3, vocal = 0.8),
            ),
        )
        assertEquals(Move.SIMPLE_FADE, assertNotNull(shaky).move)
        // Solid grids but a 30 % tempo jump and a key clash: still a fade at the default level.
        val jump = Choreographer.choose(
            inputs(
                echoPlan(92.0, 128.0),
                track("pop", 92.0, key = "C major", vocal = 0.8),
                track("techno", 128.0, key = "F# major", vocal = 0.8),
            ),
        )
        val fade = assertNotNull(jump)
        assertEquals(Move.SIMPLE_FADE, fade.move)
        // The fade is a real equal-power crossfade ending silent on the outgoing side.
        val r = LoopMix.Clock(echoPlan(92.0, 128.0)).releaseAt
        assertEquals(0f, fade.value(true, EnvKind.GAIN, r), 1e-3f)
        assertEquals(1f, fade.value(false, EnvKind.GAIN, r), 1e-3f)
    }

    @Test
    fun edmBuildDropLandsOnTheDropDownbeat() {
        val bpm = 126.0
        val beat = 60.0 / bpm
        val plan = blendPlan(bpm, bars = 8, inStart = 10.0)
        val clock = LoopMix.Clock(plan)
        // The incoming drop sits on mix beat 24 (bar 6): native incoming from t0 at track 10 s.
        val dropTrack = 10.0 + clock.beatTimes[24]
        val structure = StructureView(emptyList(), listOf(dropTrack), emptyList(), emptyList(), emptyList(), 0.8, "test")
        val script = assertNotNull(
            Choreographer.choose(inputs(plan, track("x", bpm), track("y", bpm), level = DjLevel.CLUB, inStructure = structure)),
        )
        assertEquals(Move.EDM_BUILD_DROP, script.move)
        assertEquals(24, script.swapBeat)
        assertEquals(clock.beatTimes[24], script.swapAt, 1e-9)
        val s = script.swapAt
        // Outgoing is gone exactly on the 1; incoming lands dry on it.
        assertEquals(1f, script.value(true, EnvKind.GAIN, s - 0.05), 1e-3f)
        assertEquals(0f, script.value(true, EnvKind.GAIN, s), 1e-3f)
        assertEquals(0f, script.value(false, EnvKind.GAIN, s - 0.02), 1e-3f)
        assertEquals(1f, script.value(false, EnvKind.GAIN, s + 0.005), 1e-3f)
        assertTrue(script.value(false, EnvKind.LOW_PASS_HZ, s + 0.005) >= 18000f)
        // The build: a loop roll released on the drop and a rising outgoing high-pass.
        assertTrue(script.outFx.loopRoll)
        assertEquals(s, script.outFx.rollReleaseSec, 1e-9)
        assertTrue(script.value(true, EnvKind.HIGH_PASS_HZ, s - 0.02) > script.value(true, EnvKind.HIGH_PASS_HZ, s - 8 * beat))
    }

    @Test
    fun varietyRuleNeverRepeatsAMoveThreeTimesRunning() {
        val out = track("p", 100.0, vocal = 0.2)
        val inn = track("q", 100.0, vocal = 0.2)
        val plan = blendPlan(100.0)
        val first = assertNotNull(Choreographer.choose(inputs(plan, out, inn)))
        // Deterministic for the same inputs and seed.
        assertEquals(first.move, assertNotNull(Choreographer.choose(inputs(plan, out, inn))).move)
        val third = assertNotNull(Choreographer.choose(inputs(plan, out, inn, history = listOf(first.move, first.move))))
        assertNotEquals(first.move, third.move)
        // The process-wide history counts one entry per pair, so rebuilding a pair is not a repeat.
        val h = MoveHistory()
        h.record("p->q", first.move)
        h.record("p->q", first.move)
        assertEquals(listOf(first.move), h.recent())
        assertTrue(h.recent("p->q").isEmpty())
    }
}
