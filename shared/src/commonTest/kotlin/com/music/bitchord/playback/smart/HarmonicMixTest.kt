package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarmonicMixTest {

    @Test
    fun camelotPlacesCMajorAt8BAndItsRelativeAt8A() {
        assertEquals("8B", camelotCode("C major"))
        assertEquals("8A", camelotCode("A minor"))
        assertEquals("9B", camelotCode("G major"))
        assertEquals("2B", camelotCode("F# major"))
        assertNull(camelotCode(""))
        assertNull(camelotCode("spoken"))
    }

    @Test
    fun camelotAllowsNeighboursRelativesAndTheWrapAndRefusesAClash() {
        assertTrue(camelotCompatible("8A", "9A"))
        assertTrue(camelotCompatible("8A", "8B"))
        assertTrue(camelotCompatible("12A", "1A"))
        assertTrue(camelotCompatible("1B", "12B"))
        assertFalse(camelotCompatible("8A", "10A"))
        assertFalse(camelotCompatible("8A", "3B"))
        assertFalse(camelotCompatible("8B", "2B"))
    }

    @Test
    fun aClickTrackIsSteadyAndADriftingGridIsNot() {
        val clicks = List(32) { it * 0.5 }
        assertTrue(beatGridSteady(clicks))
        // Hand-played: the beat wanders from 118 BPM out to about 96 and back.
        val drifting = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until 40) {
            drifting += t
            val bend = 0.5 + 0.12 * kotlin.math.sin(i / 6.0)
            t += bend
        }
        assertFalse(beatGridSteady(drifting))
        assertTrue(beatGridSteady(listOf(0.0, 0.5, 1.0)))
    }

    @Test
    fun anAdvancedPlanNamesACamelotClashAndDoesNotStretchADriftingGrid() {
        val steady = track(120.0, "C major", List(64) { 20.0 + it * 0.5 })
        val clash = track(120.0, "F# major", List(64) { it * 0.5 })
        val planned = planTransition(
            analysis = steady,
            nextAnalysis = clash,
            currentTrack = TransitionTrackInfo("a", 180_000, "A", "X"),
            nextTrack = TransitionTrackInfo("b", 180_000, "B", "Y"),
            currentTime = 10.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertTrue(planned.policyReasons.any { it.startsWith("camelot") && "clash" in it })

        val driftBeats = ArrayList<Double>()
        var t = 140.0
        for (i in 0 until 48) {
            driftBeats += t
            t += 0.48 + 0.1 * kotlin.math.sin(i / 5.0)
        }
        val drifting = steady.copy(
            tailBeats = BeatSide(driftBeats, driftBeats.filterIndexed { i, _ -> i % 4 == 0 }, 120.0, 0.9),
        )
        val next = track(128.0, "C major", List(32) { it * 60.0 / 128.0 })
        val held = planTransition(
            analysis = drifting,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("a", 180_000, "A", "X"),
            nextTrack = TransitionTrackInfo("b", 180_000, "B", "Y"),
            currentTime = 10.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertTrue(held.policyReasons.any { "drift" in it })
        assertEquals(1.0, held.incomingPlaybackRate, 1e-9)
        assertEquals(1.0, held.outgoingPlaybackRate, 1e-9)
        assertTrue(held.policyReasons.any { "drift" in it })
    }

    @Test
    fun aSteadyEndingOfADriftingSongCanStillBeBeatMatched() {
        val beats = ArrayList<Double>()
        var t = 80.0
        for (i in 0 until 20) {
            beats += t
            t += 0.32 + i * 0.015
        }
        for (i in 0 until 24) {
            beats += t
            t += 0.5
        }
        assertEquals(120.0, fittedBpm(beats, atEnd = true)!!, 1.5)
        val out = track(120.0, "C major", List(16) { it * 0.5 }).copy(
            tailBeats = BeatSide(beats, beats.filterIndexed { i, _ -> i % 4 == 0 }, 100.0, 0.9),
        )
        val next = track(128.0, "C major", List(32) { it * 60.0 / 128.0 })
        val plan = planTransition(
            analysis = out,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("a", 180_000, "A", "X"),
            nextTrack = TransitionTrackInfo("b", 180_000, "B", "Y"),
            currentTime = 10.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertTrue(abs(plan.incomingPlaybackRate - 1.0) > 0.01)
        assertTrue(plan.policyReasons.none { "drift" in it })
    }

    @Test
    fun aTempoGapPastTheStretchLimitNeedsMoreThanAPlainTempoEstimate() {
        val out = track(100.0, "C major", List(40) { 140.0 + it * 0.6 }).copy(
            energyCurve = List(180) { EnergySample(it.toDouble(), 1.0) },
            vocalActivityMask = List(180) { 0.02 },
        )
        val next = track(140.0, "C major", List(32) { it * 60.0 / 140.0 }).copy(
            energyCurve = List(180) { EnergySample(it.toDouble(), 1.0) },
            vocalActivityMask = List(180) { 0.02 },
        )
        val plan = planTransition(
            analysis = out,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("a", 180_000, "A", "X"),
            nextTrack = TransitionTrackInfo("b", 180_000, "B", "Y"),
            currentTime = 10.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertEquals(TransitionStyle.NATURAL, plan.transitionStyle)
        assertEquals(0.0, plan.fadeSeconds)
        assertEquals(0.0, plan.echoSeconds)
        assertEquals(1.0, plan.incomingPlaybackRate, 1e-6)
    }


    @Test
    fun onsetSnapped75BpmGridWithIrregularEdgeBeatsIsNotDrifting() {
        val period = 60.0 / 75.949
        val jitter = doubleArrayOf(0.0, 0.012, -0.030, 0.028, 0.0, -0.010, 0.026, -0.025)
        val beats = ArrayList<Double>()
        // Irregular intro edge beat (1.20 s > old 0.75 s margin)
        beats += 10.0
        var base = 11.20
        for (i in 0 until 24) {
            beats += base + jitter[i % jitter.size]
            base += period
        }
        // Irregular outro edge beat (1.20 s after last steady beat)
        beats += beats.last() + 1.20

        val tailBpm = fittedBpm(beats, atEnd = true)
        val headBpm = fittedBpm(beats, atEnd = false)
        assertTrue(tailBpm != null && abs(tailBpm - 75.949) < 2.0, "tailBpm=$tailBpm")
        assertTrue(headBpm != null && abs(headBpm - 75.949) < 2.0, "headBpm=$headBpm")

        val out = track(75.949, "Ab major", beats).copy(
            tailBeats = BeatSide(beats, beats.filterIndexed { i, _ -> i % 4 == 0 }, 75.949, 0.9),
        )
        val next = track(75.949, "Ab major", List(32) { it * period })
        val plan = planTransition(
            analysis = out,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("a", 180_000, "A", "X"),
            nextTrack = TransitionTrackInfo("b", 180_000, "B", "Y"),
            currentTime = 10.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertTrue(plan.policyReasons.none { "drift" in it }, "reasons=${plan.policyReasons}")
    }

    @Test
    fun camelotPinsCoverReportedMajorAndMinorKeys() {
        assertEquals("4B", camelotCode("Ab major"))
        assertEquals("5A", camelotCode("C minor"))
        assertEquals("10A", camelotCode("B minor"))
        assertEquals("11B", camelotCode("A major"))
        assertEquals("10B", camelotCode("D major"))
        assertEquals("4A", camelotCode("F minor"))
    }

    private fun track(bpm: Double, key: String, beats: List<Double>) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        duration = 180.0,
        bpm = bpm,
        beatInterval = 60.0 / bpm,
        beatConfidence = 0.9,
        downbeats = beats.filterIndexed { index, _ -> index % 4 == 0 },
        firstBeat = beats.firstOrNull() ?: 0.0,
        key = key,
        keyConfidence = 0.8,
        contentEndTime = 176.0,
        audibleStartTime = 0.2,
        headBeats = BeatSide(beats.take(32), emptyList(), bpm, 0.9),
        tailBeats = BeatSide(beats.takeLast(32), emptyList(), bpm, 0.9),
    )
}
