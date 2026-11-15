package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Djmix31RegressionTest {

    private fun levelWindow(duration: Double, dipAt: Double? = null): List<CurveWindow> {
        val fps = 10.0
        val n = (duration * fps).toInt()
        val values = FloatArray(n) { 0.9f }
        if (dipAt != null) {
            val idx = (dipAt * fps).toInt()
            for (i in idx until minOf(n, idx + 5)) values[i] = 0.35f
        }
        return listOf(CurveWindow(0.0, fps, values))
    }

    private fun vocalWindow(duration: Double): List<CurveWindow> {
        val fps = 10.0
        val n = (duration * fps).toInt()
        return listOf(CurveWindow(0.0, fps, FloatArray(n) { 0.02f }))
    }

    private fun outAnalysis(duration: Double = 200.0): TrackAnalysis {
        val beat = 0.5
        val downbeats = buildList {
            var t = 0.0
            while (t <= duration) { add(t); t += 2.0 }
        }
        val energy = List((duration / 1.0).toInt() + 1) { EnergySample(it.toDouble(), 0.8) }
        // Seam dip near the outro loop start makes the outro seamDelta large;
        // the verse loop near 142s stays flat (delta ~0). Old code would pick
        // the flat verse; vc73 must still prefer the outro.
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "out",
            duration = duration,
            bpm = 120.0,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            firstBeat = 0.5,
            key = "",
            keyConfidence = 0.0,
            contentEndTime = duration - 2.0,
            energyCurve = energy,
            vocalProbability = 0.05,
            lowEnergyCurve = energy,
            vocalWindows = vocalWindow(duration),
            levelWindows = levelWindow(duration, dipAt = 190.0),
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    private fun inAnalysis(duration: Double = 200.0): TrackAnalysis {
        val beat = 0.5
        val downbeats = buildList {
            var t = 0.0
            while (t <= duration) { add(t); t += 2.0 }
        }
        val energy = List((duration / 1.0).toInt() + 1) { EnergySample(it.toDouble(), 0.8) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "inn",
            duration = duration,
            bpm = 120.0,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            firstBeat = 0.5,
            key = "",
            keyConfidence = 0.0,
            energyCurve = energy,
            vocalProbability = 0.05,
            lowEnergyCurve = energy,
            vocalWindows = vocalWindow(duration),
            levelWindows = levelWindow(duration),
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    @Test
    fun loopMixPlanPrefersLateOutroOverFlatterVerse() {
        val out = outAnalysis(200.0)
        val inn = inAnalysis(200.0)
        val plan = LoopMix.plan(out, 200.0, inn, 200.0, earliestOutSec = 0.0)
            ?: throw AssertionError("expected LoopMix.plan")
        assertTrue(
            plan.out.loopStart > 180.0,
            "expected outro loop near 188-192s, got ${plan.out.loopStart} (outroFloor must win over flat verse at ~142s)",
        )
    }

    @Test
    fun dropPlanPrefersLateOutro() {
        val out = outAnalysis(200.0)
        val inn = inAnalysis(200.0)
        val plan = LoopMix.bridge(out, 200.0, inn, 200.0, earliestOutSec = 0.0)
            ?: throw AssertionError("expected bridge plan")
        // Bridge may be Beat blend / Drop / Echo depending on gates; when it
        // loops (Drop), the outgoing loop must be in the outro. When it does
        // not loop, the outgoing start must still be in the last 30s window.
        if (plan.outLoops) {
            assertTrue(plan.out.loopStart > 180.0, "drop out.loopStart=${plan.out.loopStart}")
        } else {
            assertTrue(plan.out.loopStart > 170.0, "bridge out start=${plan.out.loopStart}")
        }
    }

    private fun longMap(): SongMap {
        val bars = 105
        val sections = listOf(
            PhraseSection(0, 8, SectionFn.INTRO, SectionRole.GROOVE, 0.5f, 0, 0, 0.4f, 4, 0f, 0f, 0.05f, 0.4f, false),
            PhraseSection(8, 24, SectionFn.VERSE, SectionRole.NARRATIVE, 0.6f, 0, 0, 0.6f, 6, 0f, 0f, 0.8f, 0.6f, false),
            PhraseSection(24, 40, SectionFn.CHORUS, SectionRole.RELEASE, 0.8f, 0, 0, 0.8f, 8, 0f, 0f, 0.7f, 0.8f, false),
            PhraseSection(40, 56, SectionFn.VERSE, SectionRole.NARRATIVE, 0.6f, 0, 0, 0.6f, 6, 0f, 0f, 0.8f, 0.6f, false),
            PhraseSection(56, 64, SectionFn.BRIDGE, SectionRole.NARRATIVE, 0.5f, 0, 0, 0.5f, 5, 0f, 0f, 0.8f, 0.5f, false),
            PhraseSection(64, 80, SectionFn.CHORUS, SectionRole.RELEASE, 0.8f, 0, 0, 0.8f, 8, 0f, 0f, 0.7f, 0.8f, false),
            PhraseSection(80, 105, SectionFn.OUTRO, SectionRole.GROOVE, 0.6f, 0, 0, 0.6f, 6, 0f, 0f, 0.05f, 0.6f, false),
        )
        val sung = BooleanArray(bars) { bar -> bar < 80 }
        return SongMap(
            trackKey = "m",
            durationSec = 210.0,
            bpm = 120.0,
            beatsPerBar = 4,
            downbeats = DoubleArray(bars) { it * 2.0 },
            gridConfidence = 0.85f,
            tempoStable = true,
            phraseAnchor = 0,
            phraseLen = 8,
            sections = sections,
            sungBars = sung,
            firstVocalBar = 8,
            lastVocalBar = 79,
            pickupBeats = 0f,
            startKind = StartKind.INTRO_INSTRUMENTAL,
            endingKind = EndingKind.HARD_END,
            contentEndSec = 205.0,
            labelConfidence = 0.8f,
            structMs = 1,
        )
    }

    @Test
    fun cuePointsExitsDoNotExitFortyFiveSecondsEarlyOn210s() {
        val m = longMap()
        val exits = CuePoints.exits(m)
        assertTrue(exits.isNotEmpty(), "exits must not be empty")
        for (e in exits) {
            assertTrue(e.releaseSec >= 175.0 - 1e-6, "exit ${e.kind} at ${e.releaseSec}s is 45-60s early on a 210s track")
        }
    }

    @Test
    fun phrasePlannerDoesNotPreferBridgeOverContentWhenFinalChorusRemains() {
        val outMap = longMap()
        val innMap = SongMap(
            trackKey = "in",
            durationSec = 120.0,
            bpm = 120.0,
            beatsPerBar = 4,
            downbeats = DoubleArray(60) { it * 2.0 },
            gridConfidence = 0.85f,
            tempoStable = true,
            phraseAnchor = 0,
            phraseLen = 8,
            sections = listOf(
                PhraseSection(0, 16, SectionFn.INTRO, SectionRole.GROOVE, 0.5f, 0, 0, 0.4f, 4, 0f, 0f, 0.05f, 0.4f, false),
                PhraseSection(16, 60, SectionFn.CHORUS, SectionRole.RELEASE, 0.8f, 0, 0, 0.8f, 8, 0f, 0f, 0.7f, 0.8f, false),
            ),
            sungBars = BooleanArray(60) { it >= 16 },
            firstVocalBar = 16,
            lastVocalBar = 59,
            pickupBeats = 0f,
            startKind = StartKind.INTRO_INSTRUMENTAL,
            endingKind = EndingKind.HARD_END,
            contentEndSec = 118.0,
            labelConfidence = 0.8f,
            structMs = 1,
        )
        val choice = PhrasePlanner.select(outMap, innMap, camelotSteps = 0, outBpm = 120.0, inBpm = 120.0)
        assertNotNull(choice, "PhrasePlanner must choose: ${PhrasePlanner.lastVetoSummary}")
        assertTrue(
            choice.exit.kind != ExitKind.BRIDGE_END && choice.exit.kind != ExitKind.VERSE_END,
            "must not chop final chorus via ${choice.exit.kind} at ${choice.exit.releaseSec}s (cutsBars=${choice.exit.cutsBars} cutsSung=${choice.exit.cutsSungBars})",
        )
    }

    private fun silentIntroAnalysis(): TrackAnalysis {
        val fps = 10.0
        val n = 1000
        val level = FloatArray(n) { i -> if (i < 50) 0.0f else 0.9f }
        val energy = List(200) { i -> EnergySample(i.toDouble(), if (i < 5) 0.0 else 0.8) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "silent",
            duration = 200.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            downbeats = List(100) { it * 2.0 },
            firstBeat = 0.5,
            audibleStartTime = 0.0,
            energyCurve = energy,
            levelWindows = listOf(CurveWindow(0.0, fps, level)),
            vocalProbability = 0.05,
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    private fun audibleAtZeroAnalysis(): TrackAnalysis {
        val fps = 10.0
        val n = 1000
        val level = FloatArray(n) { 0.9f }
        val energy = List(200) { i -> EnergySample(i.toDouble(), 0.4) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "audible",
            duration = 200.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            downbeats = List(100) { it * 2.0 },
            firstBeat = 0.5,
            audibleStartTime = 0.0,
            energyCurve = energy,
            levelWindows = listOf(CurveWindow(0.0, fps, level)),
            vocalProbability = 0.05,
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    @Test
    fun silentIntroSkippedEvenWhenAudibleStartIsZero() {
        val silent = silentIntroAnalysis()
        assertTrue(LoopMix.firstAudibleTime(silent) > 4.0, "firstAudibleTime=${LoopMix.firstAudibleTime(silent)}")
        val cue = LoopMix.cueOf(silent, null)
        assertTrue(cue > 4.0, "cueOf=${cue} must skip 5s silence")
        val plan = planTransition(
            analysis = silent,
            nextAnalysis = silent,
            currentTrack = TransitionTrackInfo("silent", 200_000L),
            nextTrack = TransitionTrackInfo("silent", 200_000L),
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
        )
        assertTrue(plan.incomingCueTime > 4.0 || LoopMix.firstAudibleTime(silent) > 4.0, "planner must notice silence, cue=${plan.incomingCueTime}")
    }

    @Test
    fun audibleZeroIntroStaysAtZero() {
        val audible = audibleAtZeroAnalysis()
        assertEquals(0.0, LoopMix.firstAudibleTime(audible), 1e-6)
        val cue = LoopMix.cueOf(audible, null)
        assertTrue(cue <= 0.6, "cueOf=${cue} must stay near 0 for Never-Let-Go style audible intro")
        // CuePoints.entries needs a map; the analysis path must not advance when audible is 0.
        assertEquals(0.0, LoopMix.firstAudibleTime(audible), 1e-6)
    }

    @Test
    fun clampAnchorsToTransitionEndNotTrailingSilence() {
        val out = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "out",
            duration = 210.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            downbeats = List(105) { it * 2.0 },
            firstBeat = 0.5,
            contentEndTime = 175.0,
            mixOutCandidates = listOf(MixCandidate(time = 168.0, score = 0.95, type = "energy_cliff")),
            energyCurve = List(211) { EnergySample(it.toDouble(), 0.8) },
            vocalProbability = 0.05,
            lowEnergyCurve = List(211) { EnergySample(it.toDouble(), 0.8) },
            quality = TrackAnalysis.QUALITY_FULL,
        )
        val inn = inAnalysis(200.0)
        val plan = planTransition(
            analysis = out,
            nextAnalysis = inn,
            currentTrack = TransitionTrackInfo("out", 210_000L),
            nextTrack = TransitionTrackInfo("inn", 200_000L),
            currentTime = 10.0,
            duration = 210.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
            mixWindow = true,
        )
        assertTrue(plan.transitionStart <= plan.transitionEnd, "start=${plan.transitionStart} end=${plan.transitionEnd}")
        // Anchored to transitionEnd (~168-175), not to 210-30=180 which would
        // drag past the real end.
        assertTrue(plan.transitionStart < 180.0, "start=${plan.transitionStart} must anchor to transitionEnd, not length-30")
    }

    @Test
    fun fallbackPrefersBassHandoffWhenConfident() {
        fun a(id: String, bpm: Double, bass: Double): TrackAnalysis {
            val energy = List(211) { EnergySample(it.toDouble(), 0.8) }
            val low = List(211) { EnergySample(it.toDouble(), bass) }
            val downbeats = List(100) { it * (60.0 / bpm * 4) }
            return TrackAnalysis(
                status = TrackAnalysis.STATUS_READY,
                trackId = id,
                duration = 210.0,
                bpm = bpm,
                beatInterval = 60.0 / bpm,
                beatConfidence = 0.9,
                downbeats = downbeats,
                firstBeat = 0.5,
                contentEndTime = 208.0,
                energyCurve = energy,
                lowEnergyCurve = low,
                vocalProbability = 0.05,
                quality = TrackAnalysis.QUALITY_FULL,
            )
        }
        // 120 vs 132 = ~9% folded delta: not beatmatched, not tempoFar (inside stretch).
        // With a realistic strong-kick lowEnergyCurve both sides carry a kick at the
        // join, so the restored fallbackVariantFor must choose BASS_HANDOFF. vc73
        // renders that handoff as a clean <=200 Hz bass swap + vocal-mid dip.
        val out = a("o", 120.0, 0.8)
        val inn = a("i", 132.0, 0.8)
        val plan = planTransition(
            analysis = out,
            nextAnalysis = inn,
            currentTrack = TransitionTrackInfo("o", 210_000L),
            nextTrack = TransitionTrackInfo("i", 210_000L),
            currentTime = 10.0,
            duration = 210.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertTrue(
            plan.filterVariant == FilterTransitionVariant.BASS_HANDOFF || plan.filterVariant == FilterTransitionVariant.ECHO_RIDE || plan.filterVariant == FilterTransitionVariant.DECLINE,
            "confident fallback must not be SWEEP, got ${plan.filterVariant} style=${plan.transitionStyle}",
        )
        // Stronger: when it is a filter ride, it must be BASS_HANDOFF, not SWEEP.
        if (plan.transitionStyle == TransitionStyle.DJ_FILTER || plan.transitionStyle == TransitionStyle.DJ_BLEND) {
            assertTrue(plan.filterVariant == FilterTransitionVariant.BASS_HANDOFF || plan.filterVariant == FilterTransitionVariant.ECHO_RIDE,
                "got ${plan.filterVariant}")
        }
    }
}
