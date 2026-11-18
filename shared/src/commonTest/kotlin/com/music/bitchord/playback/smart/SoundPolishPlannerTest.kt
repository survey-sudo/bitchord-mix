package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundPolishPlannerTest {
    private fun track(bpm: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY, trackId = "out", duration = 240.0,
        contentEndTime = 238.0, outroStartTime = 220.0, bpm = bpm, beatInterval = 60.0 / bpm,
        beatConfidence = 0.9, downbeats = List(160) { it * 240.0 / bpm },
        key = "C major", keyConfidence = 0.9,
    )

    private fun bass(beat: Double, fade: Double = 64 * beat) = TransitionPlan(
        transitionStyle = TransitionStyle.DJ_FILTER, filterVariant = FilterTransitionVariant.BASS_HANDOFF,
        transitionStart = 238.0 - fade, transitionEnd = 238.0, fadeSeconds = fade,
        handoffDuration = fade, incomingCueTime = 0.25, beatSeconds = beat,
        transitionBeats = 64, bassSwap = true, reason = "sound-polish-probe",
    )

    @Test fun unsupportedGridOrKeyBassHandoffIsNoMoreThanTwoBarsOrSixSeconds() {
        for (bpm in listOf(70.0, 90.0, 120.0, 150.0)) {
            val out = track(bpm)
            val unsupported = listOf(
                out.copy(trackId = "weak-grid", beatConfidence = 0.1),
                out.copy(trackId = "clash", key = "F# major"),
                out.copy(trackId = "unknown-key", key = ""),
                out.copy(trackId = "weak-key", keyConfidence = 0.1),
                out.copy(trackId = "nan-key", keyConfidence = Double.NaN),
                out.copy(trackId = "unmatched-tempo", bpm = bpm * 1.12, beatInterval = 60.0 / (bpm * 1.12)),
            )
            for (inn in unsupported) {
                val raw = bass(out.beatInterval)
                val plan = DjMix.annotate(raw, out, inn)
                val tag = "$bpm / ${inn.trackId}: ${plan.fadeSeconds}s"
                assertEquals(FilterTransitionVariant.BASS_HANDOFF, plan.filterVariant, tag)
                assertTrue(plan.fadeSeconds <= 8 * out.beatInterval + 1e-9, tag)
                assertTrue(plan.fadeSeconds <= 6.0 + 1e-9, tag)
                assertTrue(plan.transitionBeats <= 8, tag)
                assertEquals(raw.transitionEnd, plan.transitionEnd, 1e-9, tag)
                assertEquals(raw.incomingCueTime, plan.incomingCueTime, 1e-9, tag)
                assertEquals(plan.transitionEnd - plan.transitionStart, plan.handoffDuration, 1e-9, tag)
            }
        }
    }

    @Test fun aShortBassRescueIsNotLengthenedAndUnknownTempoStillHasTheSixSecondCap() {
        val out = track(120.0)
        val inn = out.copy(beatConfidence = 0.1)
        val short = DjMix.annotate(bass(0.5, 0.75), out, inn)
        assertEquals(0.75, short.fadeSeconds, 1e-9)
        val unknown = out.copy(bpm = 0.0, beatInterval = 0.0)
        val bounded = DjMix.annotate(bass(0.0, 24.0), unknown, inn)
        assertEquals(6.0, bounded.fadeSeconds, 1e-9)
    }

    @Test fun catchUpBassRescuesObeyTheSameCapWithoutMovingTheirPinnedStartBehindThePlayhead() {
        for (bpm in listOf(70.0, 150.0)) {
            val out = track(bpm)
            val inn = out.copy(key = "F# major")
            val raw = bass(out.beatInterval, 12.0).copy(reason = "sound-polish:catch-up")
            val bounded = DjMix.annotate(raw, out, inn)
            assertEquals(raw.transitionStart, bounded.transitionStart, 1e-9)
            assertEquals(raw.incomingCueTime, bounded.incomingCueTime, 1e-9)
            assertTrue(bounded.fadeSeconds <= minOf(8 * out.beatInterval, 6.0) + 1e-9)
            assertTrue(bounded.transitionEnd <= raw.transitionEnd)
            assertEquals(bounded.transitionStart + bounded.fadeSeconds, bounded.transitionEnd, 1e-9)
        }
    }

    @Test fun matchedBlendsRetainEightToSixteenBarsAndSupportedFiltersRetainFourToEight() {
        val out = track(120.0)
        val inn = out.copy(trackId = "in")
        for ((requestedBeats, expectedBars) in listOf(1 to 8, 96 to 16)) {
            val raw = bass(0.5, requestedBeats * 0.5).copy(
                transitionStyle = TransitionStyle.DJ_BLEND, filterVariant = FilterTransitionVariant.SWEEP,
            )
            val plan = DjMix.annotate(raw, out, inn)
            assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
            assertEquals(expectedBars.toDouble(), plan.fadeSeconds / (4 * out.beatInterval), 1e-9)
        }
        for ((requestedBeats, expectedBars) in listOf(1 to 4, 96 to 8)) {
            val plan = DjMix.annotate(bass(0.5, requestedBeats * 0.5), out, inn)
            assertEquals(expectedBars.toDouble(), plan.fadeSeconds / (4 * out.beatInterval), 1e-9)
        }
        val carved = DjMix.annotate(bass(0.5, 24.0).copy(filterVariant = FilterTransitionVariant.SWEEP),
            out, inn.copy(key = "F# major"))
        assertTrue(carved.fadeSeconds / 2.0 in 4.0..8.0)
    }

    @Test fun plannerDoesNotAdvertiseRollsWithUnknownOrUnsupportedBeatLengths() {
        val out = track(120.0)
        val inn = out.copy(trackId = "in")
        for (beat in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY, 1.0, 0.1)) {
            val plan = DjMix.annotate(bass(beat, 8.0).copy(djEffect = DjEffect.LOOP_ROLL), out, inn)
            assertEquals(DjEffect.NONE, plan.djEffect, "unsupported roll beat=$beat")
            assertTrue(!plan.mixSummary.contains("loop roll"))
        }
    }
}
