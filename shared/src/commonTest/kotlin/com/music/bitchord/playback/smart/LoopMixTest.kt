package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web player's engine/loopMix.test.ts, ported: seams, wraps, folds, vocal
 * rejection and the 1-bar fallback. The web's AudioBuffer loudness becomes the
 * analysis's level windows here.
 */
class LoopMixTest {

    /** Loud everywhere except where [quiet] says, at the analyzer's level rate. */
    private fun levels(duration: Double, quiet: List<Pair<Double, Double>> = emptyList()): List<CurveWindow> {
        val frames = (duration * LEVEL_FRAME_RATE).roundToInt()
        val values = FloatArray(frames) { index ->
            val t = index / LEVEL_FRAME_RATE
            if (quiet.any { (a, b) -> t >= a && t < b }) 0f else levelFromRms(0.3).toFloat()
        }
        return listOf(CurveWindow(0.0, LEVEL_FRAME_RATE, values))
    }

    private fun analysis(
        bpm: Double,
        duration: Double,
        vocals: List<CurveWindow> = emptyList(),
        quiet: List<Pair<Double, Double>> = emptyList(),
    ) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        duration = duration,
        bpm = bpm,
        beatInterval = 60 / bpm,
        beatConfidence = 0.8,
        downbeats = listOf(0.5),
        firstBeat = 0.5,
        vocalWindows = vocals,
        levelWindows = levels(duration, quiet),
    )

    /** A vocal window that is [level] everywhere except [clean] spans. */
    private fun vocals(from: Double, to: Double, level: Float, clean: List<Pair<Double, Double>> = emptyList()): CurveWindow {
        val fps = 10.0
        val values = FloatArray(((to - from) * fps).roundToInt()) { index ->
            val t = from + index / fps
            if (clean.any { (a, b) -> t >= a && t < b }) 0.05f else level
        }
        return CurveWindow(from, fps, values)
    }

    /** Synced lyrics with nothing sung in the searched stretches: every loop is clear. */
    private val clear = emptyList<LoopMix.Span>()

    private fun plan(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        sungOut: List<LoopMix.Span>? = clear,
        sungIn: List<LoopMix.Span>? = clear,
    ) = LoopMix.plan(out, out.duration, inn, inn.duration, 0.0, sungOut, sungIn)

    @Test
    fun loopsWholeBarsOutgoingNearItsEndIncomingAtItsStart() {
        val plan = assertNotNull(plan(analysis(124.0, 200.0), analysis(124.0, 180.0)))
        val bar = 60.0 / 124 * 4
        for (pick in listOf(plan.out, plan.inn)) {
            val bars = (pick.loopStart - 0.5) / bar
            assertTrue(abs(bars - bars.roundToInt()) < 1e-9)
            assertEquals(LoopMix.LOOP_BARS * bar, pick.loopEnd - pick.loopStart, 1e-9)
        }
        assertTrue(plan.out.loopEnd <= 199.5)
        assertTrue(plan.out.loopStart > 200 - 60)
        assertEquals(0.5, plan.inn.loopStart, 1e-9)
    }

    @Test
    fun prefersBarsTheVocalMaskCallsInstrumental() {
        // sung throughout except 150-170 s on the way out and 20-35 s on the way in
        val out = analysis(120.0, 200.0, listOf(vocals(130.0, 200.0, 0.8f, listOf(150.0 to 170.0))))
        val inn = analysis(120.0, 180.0, listOf(vocals(0.0, 45.0, 0.8f, listOf(20.0 to 35.0))))
        val plan = assertNotNull(plan(out, inn, null, null))
        assertTrue(plan.out.loopStart >= 150)
        assertTrue(plan.out.loopEnd <= 170 + 0.8)
        assertTrue(plan.out.vocal <= LoopMix.MAX_SUNG_SHARE)
        assertTrue(plan.inn.loopStart >= 20)
        assertTrue(plan.inn.loopEnd <= 35 + 0.8)
        assertEquals(LoopMix.Check.MASK, plan.out.checkedBy)
    }

    @Test
    fun skipsAQuietFadeOutForALoopThatGrooves() {
        val out = analysis(120.0, 200.0, quiet = listOf(185.0 to 200.0))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0)))
        assertTrue(plan.out.loopEnd <= 185)
    }

    @Test
    fun rampsAnyTempoGapFoldingToDoubleOrHalfTimeWhenCloser() {
        val near = assertNotNull(plan(analysis(120.0, 200.0), analysis(134.0, 180.0)))
        assertEquals(1.0, near.fold)
        assertEquals(ceil((134.0 - 120) / 2.5).toInt(), near.rampBars)
        // 111 -> 169 is 58 BPM up, but only 26.5 down to 169's half time: that's the ramp taken
        val wide = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        assertEquals(2.0, wide.fold)
        assertEquals(ceil((111 - 169.0 / 2) / 2.5).toInt(), wide.rampBars)
        val slow = assertNotNull(plan(analysis(170.0, 200.0), analysis(80.0, 180.0)))
        assertEquals(0.5, slow.fold)
        // the incoming loop can only let go at its own boundary
        for (p in listOf(near, wide, slow)) {
            val inLoopOutBars = LoopMix.LOOP_BARS / p.fold
            assertEquals(0.0, p.totalBars % inLoopOutBars, 1e-9)
        }
    }

    @Test
    fun cutsEachLoopOnDetectedDownbeatsTempoDriftAndAll() {
        // a live-played song: the beat stretches 0.5% over the minute, so a constant grid would miss
        val beats = ArrayList<Double>()
        var t = 140.0
        var beat = 60.0 / 120
        while (t < 199.5) {
            beats += t
            t += beat
            beat *= 1.0002
        }
        val downbeats = beats.filterIndexed { index, _ -> index % 4 == 0 }
        val out = analysis(120.0, 200.0).copy(tailBeats = BeatSide(beats, downbeats, 120.0, 0.9))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0)))
        assertTrue(plan.out.loopStart in downbeats)
        assertTrue(plan.out.loopEnd in downbeats)
        assertEquals(LoopMix.LOOP_BARS, downbeats.indexOf(plan.out.loopEnd) - downbeats.indexOf(plan.out.loopStart))
        // the loop's tempo is its own length over 16 beats
        assertEquals((plan.out.loopEnd - plan.out.loopStart) / 16, plan.out.beatSec, 1e-12)
    }

    @Test
    fun neverCutsOnADownbeatTheModelPutOnTheWrongBeat() {
        val beat = 0.5
        // every bar line at 140 + 2k s, except the one at 190 s, which the model put two beats late
        val downbeats = List(30) { 140 + it * 4 * beat }.map { if (abs(it - 190) < 0.01) 191.0 else it }
        val out = analysis(120.0, 200.0).copy(tailBeats = BeatSide(downbeats, downbeats, 120.0, 0.9))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0)))
        assertTrue(plan.out.loopStart != 191.0 && plan.out.loopEnd != 191.0)
        assertEquals(LoopMix.LOOP_BARS * 4 * beat, plan.out.loopEnd - plan.out.loopStart, 1e-9)
    }

    @Test
    fun clockWrapsOnlyAtLoopEndsAndTheIncomingSongArrivesAtItsOwnSpeed() {
        val plan = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        val clock = LoopMix.Clock(plan)
        // each beat's position follows from the last at the beat's own rate; a wrap only at a loop end
        for (side in LoopMix.Side.entries) {
            val pick = if (side == LoopMix.Side.OUT) plan.out else plan.inn
            var previous = LoopMix.positionAt(clock, side, 0.0)!!
            for (k in 1 until clock.beats) {
                val position = LoopMix.positionAt(clock, side, clock.beatTimes[k])!!
                val step = if (side == LoopMix.Side.OUT) pick.beatSec else plan.fold * pick.beatSec
                var expected = previous + step
                if (expected >= pick.loopEnd - 1e-9) expected -= pick.loopEnd - pick.loopStart
                assertEquals(expected, position, 1e-6)
                previous = position
            }
        }
        // the ramp ends at the incoming song's own tempo, so its last looped beat plays at rate 1
        val last = clock.beats - 1
        val rate = clock.tempo(last) * plan.fold * plan.inn.beatSec / 60
        assertEquals(1.0, rate, 1e-9)
        // and the unwrapped incoming position at release is a whole number of its loops
        val loops = (clock.inReleasePosition - plan.inn.loopStart) / (plan.inn.loopEnd - plan.inn.loopStart)
        assertEquals(loops.roundToInt().toDouble(), loops, 1e-9)
        assertEquals(plan.inn.loopEnd + (clock.elementAt - clock.releaseAt), clock.elementTrackTime, 1e-9)
        assertTrue(clock.handoffAt > 0 && clock.handoffAt < clock.releaseAt)
    }

    @Test
    fun maskStatsCountsFramesOverTheThresholdAndIsNullWhereTheMaskDoesNotReach() {
        val windows = listOf(vocals(0.0, 10.0, 0.8f, listOf(0.0 to 5.0)))
        assertEquals(0.5, LoopMix.maskStats(windows, 0.0, 10.0)!!.share, 1e-9)
        assertEquals(0.0, LoopMix.maskStats(windows, 0.0, 5.0)!!.share, 1e-9)
        assertNull(LoopMix.maskStats(windows, 8.0, 14.0))
    }

    @Test
    fun positionIsNothingBeforeTheMixAndTheLoopStartOnItsFirstBeat() {
        val plan = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        val clock = LoopMix.Clock(plan)
        assertNull(LoopMix.positionAt(clock, LoopMix.Side.OUT, -0.1))
        assertEquals(plan.out.loopStart, LoopMix.positionAt(clock, LoopMix.Side.OUT, 0.0)!!, 1e-9)
        assertEquals(plan.inn.loopStart, LoopMix.positionAt(clock, LoopMix.Side.IN, 0.0)!!, 1e-9)
    }

    @Test
    fun positionStaysInsideEachLoopWrappingBackToItsStart() {
        val plan = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        val clock = LoopMix.Clock(plan)
        var sawWrap = false
        var last = -1.0
        var t = 0.0
        while (t < clock.releaseAt) {
            val out = LoopMix.positionAt(clock, LoopMix.Side.OUT, t)!!
            assertTrue(out >= plan.out.loopStart - 1e-9 && out <= plan.out.loopEnd + 1e-9)
            if (out < last - 1) sawWrap = true
            last = out
            val inn = LoopMix.positionAt(clock, LoopMix.Side.IN, t)!!
            assertTrue(inn >= plan.inn.loopStart - 1e-9 && inn <= plan.inn.loopEnd + 1e-9)
            t += 0.05
        }
        assertTrue(sawWrap)
    }

    @Test
    fun theIncomingSongGoesOnFromItsLoopEndInRealTimeAndTheOutgoingOneStops() {
        val plan = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        val clock = LoopMix.Clock(plan)
        assertNull(LoopMix.positionAt(clock, LoopMix.Side.OUT, clock.releaseAt + 0.1))
        assertEquals(plan.inn.loopEnd, LoopMix.positionAt(clock, LoopMix.Side.IN, clock.releaseAt)!!, 1e-9)
        assertEquals(clock.elementTrackTime, LoopMix.positionAt(clock, LoopMix.Side.IN, clock.elementAt)!!, 1e-9)
    }

    @Test
    fun beatPhaseIsZeroOnEveryScheduledBeat() {
        val plan = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        val clock = LoopMix.Clock(plan)
        assertEquals(0.0, LoopMix.beatPhaseAt(clock, clock.beatTimes[5])!!, 1e-9)
        val mid = (clock.beatTimes[5] + clock.beatTimes[6]) / 2
        assertEquals(0.5, LoopMix.beatPhaseAt(clock, mid)!!, 1e-9)
        assertNull(LoopMix.beatPhaseAt(clock, -1.0))
    }

    @Test
    fun skipsALoopASyncedLineOverlapsAndOneALineIsStillEndingInto() {
        // out song: sung until 172.2 s, then a line from 189 s; the latest clean 4 bars end before 189
        val sung = listOf(LoopMix.Span(150.0, 172.2), LoopMix.Span(189.0, 199.0))
        val plan = assertNotNull(plan(analysis(120.0, 200.0), analysis(120.0, 180.0), sung, clear))
        for (span in sung) assertTrue(plan.out.loopStart - 0.3 >= span.end || plan.out.loopEnd <= span.start)
        assertEquals(LoopMix.Check.LYRICS, plan.out.checkedBy)
    }

    @Test
    fun callsASoftVerseSung() {
        // mask 0.3 everywhere but 176-186 s, as Afsos measured
        val out = analysis(120.0, 200.0, listOf(vocals(130.0, 200.0, 0.3f, listOf(176.0 to 186.0))))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0), null, clear))
        assertTrue(plan.out.loopStart >= 176.5)
        assertTrue(plan.out.loopEnd <= 186)
        assertEquals(LoopMix.Check.MASK, plan.out.checkedBy)
    }

    @Test
    fun fallsBackToA1BarLoopWhenOnlyOneCleanBarExists() {
        // sung everywhere in the search but 180.5-182.5 (one bar)
        val sung = listOf(LoopMix.Span(100.0, 180.2), LoopMix.Span(182.6, 199.0))
        val plan = assertNotNull(plan(analysis(120.0, 200.0), analysis(120.0, 180.0), sung, clear))
        assertEquals(1, plan.out.bars)
        assertEquals(4 * 0.5, plan.out.loopEnd - plan.out.loopStart, 1e-9)
    }

    @Test
    fun anOpenLinesGuessedTailIsLeftToTheMask() {
        // Line-synced lyrics: a line every 8 s from 140 s, none with an end, so each reads as
        // sung until the next. The mask says the singing stops 3 s into each line.
        val lines = (0 until 8).map { LoopMix.Line((140_000 + it * 8_000).toLong(), null, blank = false) }
        val spans = LoopMix.sungSpans(lines)
        val quiet = (0 until 8).map { (140.0 + it * 8 + 3) to (140.0 + it * 8 + 8) }
        val out = analysis(120.0, 200.0, listOf(vocals(130.0, 200.0, 0.8f, quiet)))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0), spans, clear))
        assertEquals(LoopMix.Check.LYRICS_AND_MASK, plan.out.checkedBy)
        // never over a line's first words
        for (span in spans) assertTrue(plan.out.loopStart - 0.3 >= span.certainUntil || plan.out.loopEnd <= span.start)
        // A gap the line does not claim can still loop. The certain opening cannot.
        val bare = assertNotNull(
            plan(analysis(120.0, 200.0, quiet = listOf(176.0 to 200.0)), analysis(120.0, 180.0), spans, clear),
        )
        for (span in spans) assertTrue(bare.out.loopStart - 0.3 >= span.certainUntil || bare.out.loopEnd <= span.start)
    }

    @Test
    fun makesNoLoopMixWhenNoBarIsInstrumental() {
        assertNull(plan(analysis(120.0, 200.0), analysis(120.0, 180.0), listOf(LoopMix.Span(0.0, 200.0)), clear))
    }

    @Test
    fun turnsSyncedLinesIntoPaddedSpansALineWithoutAnEndSungUntilTheNext() {
        val spans = LoopMix.sungSpans(
            listOf(
                LoopMix.Line(10_000, 12_000, blank = false),
                LoopMix.Line(13_000, null, blank = false),
                LoopMix.Line(16_000, null, blank = true),
                LoopMix.Line(20_000, 21_000, blank = false),
            ),
        )
        assertEquals(9.85, spans[0].start, 1e-9)
        assertEquals(12.35, spans[0].end, 1e-9)
        assertEquals(14.85, spans[1].end, 1e-9)
        assertEquals(3, spans.size)
    }

    @Test
    fun aLineEndOnTheNextLineDoesNotMarkTheGapAsSung() {
        val spans = LoopMix.sungSpans(
            listOf(
                LoopMix.Line(10_000, 18_000, blank = false),
                LoopMix.Line(18_000, 26_000, blank = false),
            ),
        )
        assertTrue(spans[0].certainUntil < 13.5, "the gap was marked sung until ${spans[0].certainUntil}")
    }

    @Test
    fun aWordEndIsWhatCountsAsSung() {
        val spans = LoopMix.sungSpans(
            listOf(LoopMix.Line(10_000, 18_000, blank = false, wordEndMs = 12_000)),
        )
        assertEquals(12.35, spans[0].certainUntil, 0.02)
    }

    @Test
    fun anArmedOrCountingLoopIsNotCancelledByARefreshThatFindsNothing() {
        assertEquals(
            LoopMix.Hold.KEEP,
            LoopMix.holdLoop(armed = true, havePlan = true, gotReplacement = false, pairChanged = false, seekPast = true),
        )
        assertEquals(
            LoopMix.Hold.KEEP,
            LoopMix.holdLoop(armed = false, havePlan = true, gotReplacement = false, pairChanged = false, seekPast = false),
        )
        assertEquals(
            LoopMix.Hold.TAKE,
            LoopMix.holdLoop(armed = false, havePlan = false, gotReplacement = true, pairChanged = false, seekPast = false),
        )
        assertEquals(
            LoopMix.Hold.CLEAR,
            LoopMix.holdLoop(armed = false, havePlan = true, gotReplacement = false, pairChanged = false, seekPast = true),
        )
    }

    @Test
    fun musicVideosGetNoLoopMixOnEitherSide() {
        val out = analysis(120.0, 200.0)
        val inn = analysis(120.0, 180.0)
        assertNull(LoopMix.plan(out, 200.0, inn, 180.0, 0.0, clear, clear, outVideo = true))
        assertNull(LoopMix.plan(out, 200.0, inn, 180.0, 0.0, clear, clear, inVideo = true))
    }

    @Test
    fun aLyricsClearPairStaysALoopMixWhenKeysClashAndTheGridDrifts() {
        // v1.9.1 eligibility: constant grid, synced lyrics with nothing sung in
        // the search, both sides long enough for a 4-bar loop. Camelot and a
        // messy tail must not take that loop away — the loop mix is decided
        // before either of those, and they are not inputs to it.
        val out = analysis(126.0, 220.0).copy(
            key = "C major",
            keyConfidence = 0.95,
            tailBeats = BeatSide(
                beats = listOf(10.0, 10.2, 10.7, 11.4),
                downbeats = listOf(10.0),
                bpm = 140.0,
                confidence = 0.2,
            ),
        )
        val inn = analysis(130.0, 200.0).copy(key = "F# major", keyConfidence = 0.95)
        val plan = assertNotNull(LoopMix.plan(out, out.duration, inn, inn.duration, 0.0, clear, clear))
        assertEquals(LoopMix.LOOP_BARS, plan.out.bars)
        assertEquals(LoopMix.LOOP_BARS, plan.inn.bars)
        assertEquals(LoopMix.Check.LYRICS, plan.out.checkedBy)
        assertEquals(LoopMix.Check.LYRICS, plan.inn.checkedBy)
    }

    @Test
    fun noMaskAndNoLyricsMeansNoLoop() {
        assertNull(plan(analysis(120.0, 200.0), analysis(120.0, 180.0), null, null))
    }

    @Test
    fun shiftsAMissedLoopToTheNextCleanBarInsteadOfDroppingIt() {
        val out = analysis(126.0, 220.0)
        val inn = analysis(130.0, 200.0)
        val planned = assertNotNull(plan(out, inn))
        val shifted = assertNotNull(
            LoopMix.shiftToNextBar(planned, out, out.duration, inn, inn.duration, clear, clear),
        )
        val bar = 60.0 / 126.0 * 4
        assertTrue(shifted.out.loopStart > planned.out.loopStart + 0.5)
        // The missed loop was the latest 4-bar, so the next clean one is the bar after it.
        assertEquals(bar, shifted.out.loopStart - planned.out.loopStart, 1e-6)
        assertEquals(LoopMix.Check.LYRICS, shifted.out.checkedBy)
        assertEquals(LoopMix.Check.LYRICS, shifted.inn.checkedBy)
        // 126 -> 130 stays unfolded; the ramp length is the same check plan() applies.
        assertEquals(1.0, shifted.fold)
        assertEquals(ceil((130.0 - 126.0) / LoopMix.MAX_BPM_PER_BAR).toInt(), shifted.rampBars)
        assertEquals(planned.inn.loopStart, shifted.inn.loopStart, 1e-9)
    }

    @Test
    fun shiftSkipsASungBarAndStillRefusesALoopTheLyricsDoNotClear() {
        // One clean bar late in the song, then singing again: plan() takes that bar,
        // and nothing after it is allowed through.
        val sung = listOf(LoopMix.Span(100.0, 180.2), LoopMix.Span(182.6, 219.0))
        val out = analysis(120.0, 220.0)
        val inn = analysis(120.0, 180.0)
        val planned = assertNotNull(plan(out, inn, sung, clear))
        assertEquals(1, planned.out.bars)
        assertNull(LoopMix.shiftToNextBar(planned, out, out.duration, inn, inn.duration, sung, clear))
        // A sung span that only covers the bar after a longer clean loop must not be chosen,
        // and a clear bar past it still may.
        val hole = listOf(LoopMix.Span(150.0, 170.2), LoopMix.Span(174.6, 176.4), LoopMix.Span(186.0, 219.0))
        val earlier = assertNotNull(plan(out, inn, hole, clear))
        val moved = assertNotNull(LoopMix.shiftToNextBar(earlier, out, out.duration, inn, inn.duration, hole, clear))
        assertTrue(moved.out.loopStart > earlier.out.loopStart)
        for (span in hole) {
            val from = moved.out.loopStart - LoopMix.LYRIC_GUARD_SEC
            assertTrue(span.start >= moved.out.loopEnd || span.end <= from)
        }
    }

    @Test
    fun shiftPrefersAPhraseBoundaryWithinABarOfTheNextLine() {
        val out = analysis(120.0, 220.0)
        val inn = analysis(120.0, 200.0)
        val natural = assertNotNull(plan(out, inn))
        // Pretend the mix was committed a long way before the latest loop, so several
        // clean bars remain and a phrase boundary sits one bar after the first of them.
        val missed = natural.copy(
            out = natural.out.copy(loopStart = 160.5, loopEnd = 168.5, bars = LoopMix.LOOP_BARS),
        )
        val plain = assertNotNull(LoopMix.shiftToNextBar(missed, out, out.duration, inn, inn.duration, clear, clear))
        assertEquals(162.5, plain.out.loopStart, 1e-6)
        val phrased = out.copy(phraseBoundaries = listOf(0.5, 164.5, 180.5))
        val snapped = assertNotNull(
            LoopMix.shiftToNextBar(missed, phrased, phrased.duration, inn, inn.duration, clear, clear),
        )
        assertEquals(164.5, snapped.out.loopStart, 1e-6)
        assertEquals(LoopMix.Check.LYRICS, snapped.out.checkedBy)
    }

    @Test
    fun repeatedShiftsStopWhenTheWindowRunsOut() {
        val out = analysis(120.0, 200.0)
        val inn = analysis(120.0, 180.0)
        var current = assertNotNull(plan(out, inn))
        var steps = 0
        while (true) {
            val next = LoopMix.shiftToNextBar(current, out, out.duration, inn, inn.duration, clear, clear)
                ?: break
            assertTrue(next.out.loopStart > current.out.loopStart)
            current = next
            steps++
            assertTrue(steps < 80)
        }
        assertTrue(steps > 0)
        assertNull(LoopMix.shiftToNextBar(current, out, out.duration, inn, inn.duration, clear, clear))
    }

    @Test
    fun lateStepReasonNamesTheStepAndTheMillisecondOverrun() {
        val reason = LoopMix.lateStepReason(
            listOf(
                LoopMix.PrepSpan("outgoing stream", 0, 1_000, 3_200),
                LoopMix.PrepSpan("incoming decode", 1_000, 5_000, 3_200),
            ),
            nowMs = 5_000,
            deadlineMs = 3_200,
        )
        assertEquals("incoming decode 1800 ms over", reason)
        val pending = LoopMix.lateStepReason(emptyList(), nowMs = 9_400, deadlineMs = 8_000)
        assertEquals("deck build 1400 ms over", pending)
    }

    @Test
    fun anArmedLoopIsDroppedWhenLyricsOrTheMaskVetoIt() {
        assertEquals(
            LoopMix.Hold.CLEAR,
            LoopMix.holdLoop(
                armed = true,
                havePlan = true,
                gotReplacement = false,
                pairChanged = false,
                seekPast = false,
                sungVeto = true,
            ),
        )
        assertEquals(
            LoopMix.Hold.TAKE,
            LoopMix.holdLoop(
                armed = true,
                havePlan = true,
                gotReplacement = true,
                pairChanged = false,
                seekPast = false,
                sungVeto = true,
            ),
        )
        assertEquals(
            LoopMix.Hold.KEEP,
            LoopMix.holdLoop(armed = true, havePlan = true, gotReplacement = false, pairChanged = false, seekPast = false),
        )
    }

    @Test
    fun lyricsDoNotPassABarTheMaskCallsSung() {
        val out = analysis(120.0, 200.0, listOf(vocals(0.0, 200.0, 0.9f)))
        val inn = analysis(120.0, 180.0, listOf(vocals(0.0, 40.0, 0.9f, listOf(8.0 to 24.0))))
        assertNull(plan(out, inn, clear, clear))
    }

    @Test
    fun aLyricsClearBarStillNeedsAQuietMaskWhenOneExists() {
        val out = analysis(120.0, 200.0, listOf(vocals(130.0, 200.0, 0.8f, listOf(160.0 to 190.0))))
        val inn = analysis(120.0, 180.0, listOf(vocals(0.0, 40.0, 0.8f, listOf(8.0 to 28.0))))
        val mixed = assertNotNull(plan(out, inn, clear, clear))
        assertEquals(LoopMix.Check.LYRICS_AND_MASK, mixed.out.checkedBy)
        assertEquals(LoopMix.Check.LYRICS_AND_MASK, mixed.inn.checkedBy)
    }

    @Test
    fun aKeptLoopIsVetoedWhenTheMaskArrivesSung() {
        val quiet = analysis(120.0, 200.0)
        val inn = analysis(120.0, 180.0)
        val kept = assertNotNull(plan(quiet, inn))
        val sung = quiet.copy(vocalWindows = listOf(vocals(0.0, 200.0, 0.9f)))
        assertTrue(LoopMix.vetoes(kept, sung, inn, clear, clear))
        val lyric = listOf(LoopMix.Span(kept.out.loopStart - 1.0, kept.out.loopEnd + 1.0, kept.out.loopEnd + 1.0))
        assertTrue(LoopMix.vetoes(kept, quiet, inn, lyric, clear))
        assertFalse(LoopMix.vetoes(kept, quiet, inn, clear, clear))
    }

    @Test
    fun beatBlendStaysInsideTheCapAndArrivesAtTheIncomingTempo() {
        val plan = assertNotNull(LoopMix.bridge(analysis(120.0, 200.0), 200.0, analysis(128.0, 180.0), 180.0, 0.0))
        assertEquals("Beat blend", plan.rung)
        assertFalse(plan.inLoops)
        assertFalse(plan.nativeIncoming)
        assertEquals(128.0, plan.targetBpm, 0.5)
    }

    @Test
    fun aVocalPairPastSixPercentDoesNotStretch() {
        // The pair is vocal overall, with a measured clean final phrase exit.
        val out = analysis(120.0, 200.0, listOf(vocals(0.0, 200.0, 0.8f,
            listOf(196.0 to 200.0)))).copy(vocalProbability = 0.8)
        val inn = analysis(128.0, 180.0, listOf(vocals(0.0, 180.0, 0.8f))).copy(vocalProbability = 0.8)
        val plan = assertNotNull(LoopMix.bridge(out, 200.0, inn, 180.0, 0.0))
        assertEquals("Natural handoff", plan.rung)
        assertTrue(plan.nativeIncoming)
        assertFalse(plan.echoTail)
        assertEquals(HandoffStatus.QUALIFIED,
            qualifyMusicalHandoff(assertNotNull(plan.handoff), out, inn, null, null).status)
    }

    @Test
    fun aCleanOutroDropsInsteadOfStretchingTheIncomingSong() {
        val out = analysis(120.0, 200.0, listOf(vocals(130.0, 200.0, 0.8f, listOf(170.0 to 198.0))))
        val inn = analysis(124.0, 180.0, listOf(vocals(0.0, 40.0, 0.9f)))
        val plan = assertNotNull(LoopMix.bridge(out, 200.0, inn, 180.0, 0.0, clear, clear))
        assertEquals("Drop", plan.rung)
        assertTrue(plan.outLoops)
        assertFalse(plan.inLoops)
        assertTrue(plan.nativeIncoming)
    }

    @Test
    fun lateStepReasonBeforeDeadlineReportsEarlyAbortNotZeroMsOver() {
        val span = LoopMix.PrepSpan("outgoing decode", 1_000L, 1_400L, 5_000L)
        val reason = LoopMix.lateStepReason(listOf(span), nowMs = 1_500L, deadlineMs = 5_000L)
        assertFalse(reason.contains("0 ms over"), "reason=$reason")
        assertTrue(reason.contains("early-abort"), "reason=$reason")
        assertTrue(reason.contains("3500 ms left"), "reason=$reason")

        val emptyReason = LoopMix.lateStepReason(emptyList(), nowMs = 2_000L, deadlineMs = 5_000L)
        assertFalse(emptyReason.contains("0 ms over"), "emptyReason=$emptyReason")
        assertTrue(emptyReason.contains("early-abort"), "emptyReason=$emptyReason")
    }

    private fun sparseVocal(from: Double, to: Double, sungEvery: Int, sungValue: Float = 0.30f): CurveWindow {
        val fps = 10.0
        val count = ((to - from) * fps).roundToInt()
        val values = FloatArray(count) { if (it % sungEvery == 0) sungValue else 0.05f }
        return CurveWindow(from, fps, values)
    }

    @Test
    fun gatedMaskDuckedAdmitsModerateVocalWithStemDuck() {
        // Strict clean fails (share ~0.14 > 0.03); gated ducked admits
        // (0.03..0.28, peak 0.30 < 0.35, run 0.1 <= 0.6) with stem duck.
        val out = analysis(120.0, 200.0, listOf(sparseVocal(0.0, 200.0, 7)))
        val inn = analysis(120.0, 180.0)
        val plan = assertNotNull(LoopMix.plan(out, 200.0, inn, 180.0, 0.0, null, clear))
        assertEquals(LoopMix.Check.MASK_DUCKED, plan.out.checkedBy)
        assertTrue(plan.out.vocal in 0.03..0.28, "vocal=${plan.out.vocal}")
        assertEquals(LoopMix.Check.LYRICS, plan.inn.checkedBy)
        assertTrue(plan.stemDuckRequired)
        assertEquals(LoopMixDuck.MIN_FLOOR, plan.duckFloor)
    }

    @Test
    fun cleanPassPrecedesDuckedWhenCleanExists() {
        // Share ~0.02 (every 50th) is clean: ducked must not be used.
        val out = analysis(120.0, 200.0, listOf(sparseVocal(0.0, 200.0, 50)))
        val inn = analysis(120.0, 180.0)
        val plan = assertNotNull(LoopMix.plan(out, 200.0, inn, 180.0, 0.0, null, clear))
        assertTrue(plan.out.checkedBy != LoopMix.Check.MASK_DUCKED, "checkedBy=${plan.out.checkedBy}")
        assertFalse(plan.stemDuckRequired)
    }

    @Test
    fun dualDuckedCrownsAreRejected() {
        // Both sides ducked at ~0.14: other side vocal > 0.10 -> no plan.
        val out = analysis(120.0, 200.0, listOf(sparseVocal(0.0, 200.0, 7)))
        val inn = analysis(120.0, 180.0, listOf(sparseVocal(0.0, 180.0, 7)))
        assertNull(LoopMix.plan(out, 200.0, inn, 180.0, 0.0, null, null))
        // One ducked + clean other (0.0) is the only admitted shape.
        val cleanIn = analysis(120.0, 180.0)
        assertNotNull(LoopMix.plan(out, 200.0, cleanIn, 180.0, 0.0, null, clear))
    }

    @Test
    fun longDuckedLoopExceeding21p5IsRejected() {
        // 40 BPM: 4-bar loop = 24s > 21.5 -> ducked rejected.
        val out = analysis(40.0, 200.0, listOf(sparseVocal(0.0, 200.0, 7)))
        val inn = analysis(40.0, 180.0)
        assertNull(LoopMix.plan(out, 200.0, inn, 180.0, 0.0, null, clear))
    }

    @Test
    fun lyricCertainVetoStaysAbsoluteForDucked() {
        val out = analysis(120.0, 200.0, listOf(sparseVocal(0.0, 200.0, 7)))
        val inn = analysis(120.0, 180.0)
        val certain = listOf(LoopMix.Span(0.0, 200.0, certainUntil = 200.0))
        assertNull(LoopMix.plan(out, 200.0, inn, 180.0, 0.0, certain, clear))
    }

    @Test
    fun duckedRankingPrefersLowestShare() {
        // Earlier region share ~0.10 (every 10th), later ~0.20 (every 5th).
        // Latest-first would pick the later region; ranking picks the earlier.
        val fps = 10.0
        val count = 2000
        val values = FloatArray(count) { i ->
            val t = i / fps
            val every = if (t < 170.0) 10 else 5
            if (i % every == 0) 0.30f else 0.05f
        }
        val out = analysis(120.0, 200.0, listOf(CurveWindow(0.0, fps, values)))
        val inn = analysis(120.0, 180.0)
        val plan = assertNotNull(LoopMix.plan(out, 200.0, inn, 180.0, 0.0, null, clear))
        assertEquals(LoopMix.Check.MASK_DUCKED, plan.out.checkedBy)
        assertTrue(plan.out.loopStart < 170.0, "expected earlier lower-share loop, got ${plan.out.loopStart}")
    }

    @Test
    fun naturalHandoffClamps60msBeforeContainerEOF() {
        // Bug 1a: natural cut must be outDuration - 0.06 (not raw outDuration)
        // when effectiveEnd == outDuration, inside real decoded material.
        val out = analysis(120.0, 200.0, listOf(vocals(0.0, 200.0, 0.8f, listOf(196.0 to 200.0)))).copy(vocalProbability = 0.8)
        val inn = analysis(128.0, 180.0, listOf(vocals(0.0, 180.0, 0.8f))).copy(vocalProbability = 0.8)
        val plan = assertNotNull(LoopMix.bridge(out, 200.0, inn, 180.0, 0.0))
        assertEquals("Natural handoff", plan.rung)
        assertEquals(199.94, assertNotNull(plan.handoff).outDryEnd.sourceSec, 1e-8)
        assertEquals(199.94, assertNotNull(plan.handoff).outDryFallStart.sourceSec, 1e-8)
    }
}
