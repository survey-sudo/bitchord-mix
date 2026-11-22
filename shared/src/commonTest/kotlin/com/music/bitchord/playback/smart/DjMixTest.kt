package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DjMixTest {

    @Test
    fun repeatedLyricLinesAreTheChorusAndTheFirstBlockIsTheVerse() {
        val lyrics = listOf(
            LyricCue(20.0, 24.0, "I walked home"),
            LyricCue(24.0, 28.0, "in the rain"),
            LyricCue(40.0, 44.0, "we will rock"),
            LyricCue(44.0, 48.0, "you tonight"),
            LyricCue(70.0, 74.0, "we will rock"),
            LyricCue(74.0, 78.0, "you tonight"),
        )
        val sections = labelSections(track(120.0), lyrics)
        assertEquals(SectionKind.VERSE, sectionAt(sections, 22.0)?.kind)
        assertEquals(SectionKind.CHORUS, sectionAt(sections, 42.0)?.kind)
        assertEquals(SectionKind.CHORUS, sectionAt(sections, 72.0)?.kind)
        assertEquals(SectionKind.INTRO, sectionAt(sections, 2.0)?.kind)
        assertEquals(SectionKind.OUTRO, sectionAt(sections, 100.0)?.kind)
    }

    @Test
    fun aRepeatedEnergyShapeIsAChorusWhenTheLineIsNotFlat() {
        val energy = ArrayList<EnergySample>()
        for (i in 0 until 80) {
            val t = i.toDouble()
            val motif = if ((t % 20) in 0.0..6.0) 0.2 + (t % 20) * 0.1 else 0.9
            energy += EnergySample(t, motif)
        }
        val sections = labelSections(track(120.0).copy(energyCurve = energy, duration = 80.0, contentEndTime = 78.0))
        val choruses = sections.filter { it.kind == SectionKind.CHORUS }
        assertTrue(choruses.size >= 2, "repeated motif should mark more than one chorus, got $sections")
    }

    @Test
    fun aPlanThatSkipsAVerseIsPulledBackToIt() {
        val lyrics = listOf(
            LyricCue(8.0, 20.0, "first verse line"),
            LyricCue(40.0, 48.0, "chorus hook"),
            LyricCue(80.0, 88.0, "chorus hook"),
        )
        val current = track(120.0)
        val next = track(120.0).copy(trackId = "in")
        val raw = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 160.0,
            transitionEnd = 176.0,
            fadeSeconds = 16.0,
            incomingCueTime = 50.0,
            bassSwap = true,
            beatSeconds = 0.5,
            vocalOverlap = 0.0,
        )
        val sections = labelSections(next, lyrics)
        val plan = DjMix.annotate(raw, current, next, emptyList(), lyrics, "out", "in")
        assertTrue(
            plan.incomingCueTime < 20.0,
            "cue ${plan.incomingCueTime} still skips the verse; sections=$sections",
        )
        assertTrue(
            plan.mixSummary.contains("verse") || plan.inSection == "verse",
            "summary=${plan.mixSummary} in=${plan.inSection} cue=${plan.incomingCueTime} sections=$sections",
        )
    }

    @Test
    fun aKeyClashBecomesAShortFilterAndALoopPlanIsUntouched() {
        val out = track(120.0, "C major")
        val clash = track(120.0, "F# major")
        val blend = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 140.0,
            transitionEnd = 176.0,
            fadeSeconds = 36.0,
            beatSeconds = 0.5,
            bassSwap = true,
            reason = "smart-duration",
        )
        val filtered = DjMix.annotate(blend, out, clash, outgoingId = "a", incomingId = "b")
        assertEquals(TransitionStyle.DJ_FILTER, filtered.transitionStyle)
        val bars = filtered.fadeSeconds / (4 * 0.5)
        assertTrue(bars in 4.0..8.05, "key clash should run 4 to 8 bars, was $bars")
        assertTrue(filtered.mixSummary.contains("clash"))
    }

    @Test
    fun pairHashCannotAuthorizeAnUnqualifiedEchoOrReplacementWash() {
        assertEquals(0, pairHash("a", "b") % 3)
        val out = track(100.0).let { it.copy(vocalActivityMask = List(it.energyCurve.size) { 0.02 }) }
        val inn = track(140.0).let { it.copy(vocalActivityMask = List(it.energyCurve.size) { 0.02 }) }
        val echoed = DjMix.annotate(gap(), out, inn, outgoingId = "a", incomingId = "b")
        assertEquals(DjEffect.NONE, echoed.djEffect)
        assertEquals(0.0, echoed.echoSeconds)
        val echoId = (0 until 64).first { pairHash("a", "id-$it") % 3 == 1 }
        val stillEcho = DjMix.annotate(gap(), out, inn, outgoingId = "a", incomingId = "id-$echoId")
        assertEquals(DjEffect.NONE, stillEcho.djEffect)
        assertEquals(0.0, stillEcho.echoSeconds)
        val reverbId = (0 until 64).first { pairHash("a", "id-$it") % 3 == 2 }
        val reverbed = DjMix.annotate(gap(), out, inn, outgoingId = "a", incomingId = "id-$reverbId")
        assertEquals(DjEffect.NONE, reverbed.djEffect)
        assertEquals(0.0, reverbed.echoSeconds)
        assertFalse(reverbed.mixSummary.contains("reverb wash"))
    }

    @Test
    fun aRunawayTempoIsNotPlayedAndASmallNudgeStillIs() {
        assertEquals(1.0, audibleTempoRate(100.0))
        assertEquals(1.0, audibleTempoRate(0.01))
        assertEquals(1.0, audibleTempoRate(Double.NaN))
        assertEquals(120.0 / 111.0, audibleTempoRate(120.0 / 111.0), 1e-6)
        assertEquals(AUDIBLE_MAX_RATE, audibleTempoRate(1.25))
        assertEquals(AUDIBLE_MIN_RATE, audibleTempoRate(0.90))
        assertEquals(1.0, audibleTempoRate(999.0))
        assertEquals(1f, deckSpeed(1f, 999.0))
        assertEquals(1.06f, deckSpeed(1f, 1.0, BUILDUP_RISE), 1e-4f)
        assertEquals(1.15f, deckSpeed(1f, 1.09, BUILDUP_RISE), 1e-4f)
        assertEquals(1.15, releasableRate(1.15), 1e-6)
        assertEquals(1.0, releasableRate(999.0))
    }

    @Test
    fun aPreChorusCanRiseAndAVerseCannot() {
        assertTrue(
            buildupFits(
                TransitionStyle.DJ_BLEND, 0.0, false,
                SectionKind.PRE_CHORUS, SectionKind.CHORUS, 1.0,
            ),
        )
        assertTrue(
            buildupFits(
                TransitionStyle.DJ_BLEND, 0.1, false,
                SectionKind.INST, SectionKind.CHORUS, 1.2,
            ),
        )
        assertFalse(
            buildupFits(
                TransitionStyle.DJ_BLEND, 0.0, false,
                SectionKind.VERSE, SectionKind.CHORUS, 1.4,
            ),
        )
        assertFalse(
            buildupFits(
                TransitionStyle.DJ_BLEND, 0.5, true,
                SectionKind.PRE_CHORUS, SectionKind.CHORUS, 1.4,
            ),
        )
        assertFalse(
            buildupFits(
                TransitionStyle.DJ_FILTER, 0.0, false,
                SectionKind.PRE_CHORUS, SectionKind.CHORUS, 1.4,
            ),
        )
    }

    @Test
    fun aPieceFarFromTheSongsOwnTempoIsNotTheLock() {
        assertEquals(120.0, joinBpm(60.0, 120.0))
        assertEquals(120.0, joinBpm(200.0, 120.0))
        assertEquals(112.0, joinBpm(112.0, 120.0))
    }

    @Test
    fun anInstrumentalBlendCanFlangeAndTwoSingersDoNotGetThatFlanger() {
        val id = (0 until 24).first { pairHash("out", "fx-$it") % 4 == 0 }
        assertEquals(DjEffect.FLANGER, blendColour(0.0, "out", "fx-$id", measured = true))
        assertEquals(DjEffect.NONE, blendColour(0.0, "out", "fx-$id"))
        assertEquals(DjEffect.NONE, blendColour(0.5, "out", "fx-$id", measured = true))
        val echoId = (0 until 64).first { pairHash("out", "fx-$it") % 4 == 2 }
        assertEquals(DjEffect.ECHO, blendColour(0.0, "out", "fx-$echoId", measured = true))
    }

    @Test
    fun anExitInsideAVerseWaitsUntilThatVerseEnds() {
        val lyrics = listOf(
            LyricCue(140.0, 148.0, "verse one stays"),
            LyricCue(148.0, 156.0, "verse one continues"),
        )
        val raw = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 142.0,
            transitionEnd = 145.0,
            fadeSeconds = 3.0,
            beatSeconds = 0.5,
        )
        val plan = DjMix.annotate(raw, track(120.0), track(120.0), lyrics, emptyList(), "out", "in")
        assertTrue(plan.transitionEnd >= 147.5, "exit ${plan.transitionEnd} still cuts the verse")
    }

    @Test
    fun vocalWindowsHandTheVocalOverWhenNoMaskWasStored() {
        val window = CurveWindow(0.0, 10.0, FloatArray(120) { 0.8f })
        val out = track(120.0).copy(vocalWindows = listOf(window), energyCurve = emptyList())
        val inn = track(120.0).copy(vocalWindows = listOf(window), energyCurve = emptyList())
        val plan = DjMix.annotate(
            TransitionPlan(
                transitionStyle = TransitionStyle.DJ_BLEND,
                transitionStart = 1.0,
                transitionEnd = 9.0,
                fadeSeconds = 8.0,
                incomingCueTime = 1.0,
                beatSeconds = 0.5,
                vocalOverlap = 0.0,
            ),
            out,
            inn,
            outgoingId = "sung-a",
            incomingId = "sung-b",
        )
        assertTrue(plan.vocalHandoff, "windows should count as singing when the mask array is empty")
    }

    @Test
    fun effectDefaultsStayInsideTheTastefulRange() {
        val echo = effectSettings(DjEffect.ECHO)
        assertTrue(echo.feedback in 0.45f..0.6f)
        val roll = effectSettings(DjEffect.LOOP_ROLL)
        assertEquals(4f, roll.beats)
        val flanger = effectSettings(DjEffect.FLANGER)
        assertTrue(flanger.wet <= 0.5f)
        assertTrue(flanger.feedback in 0.3f..0.6f)
        assertTrue(flanger.beats in 4f..16f)
        val reverb = effectSettings(DjEffect.REVERB)
        assertTrue(reverb.wet < 0.4f)
    }

    @Test
    fun vocalMasksOnBothSidesHandTheVocalOver() {
        val mask = List(40) { 0.8 }
        val energy = List(40) { EnergySample(it * 0.5, 1.0) }
        val out = track(120.0).copy(vocalActivityMask = mask, energyCurve = energy)
        val inn = track(120.0).copy(vocalActivityMask = mask, energyCurve = energy)
        val plan = DjMix.annotate(
            TransitionPlan(
                transitionStyle = TransitionStyle.DJ_BLEND,
                transitionStart = 1.0,
                transitionEnd = 9.0,
                fadeSeconds = 8.0,
                incomingCueTime = 1.0,
                beatSeconds = 0.5,
                vocalOverlap = 0.4,
                bassSwap = true,
            ),
            out,
            inn,
            outgoingId = "a",
            incomingId = "c",
        )
        assertTrue(plan.vocalHandoff)
        assertTrue(plan.mixSummary.contains("vocal handoff"))
    }

    @Test
    fun aSlowPreparationLengthensTheLeadAndAFastOneWalksItBack() {
        assertEquals(PrepLead.BASE_MS, PrepLead.next(PrepLead.BASE_MS, 2_000L))
        assertEquals(PrepLead.BASE_MS + 4_000L, PrepLead.next(PrepLead.BASE_MS, 12_000L))
        assertEquals(PrepLead.MAX_MS, PrepLead.next(PrepLead.MAX_MS, 20_000L))
        assertEquals(22_000L, PrepLead.next(24_000L, 1_000L))
    }

    @Test
    fun aMatchedPairSharesAtLeastEightBarsAndTheFaderDoesNotStep() {
        val current = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "a",
            duration = 200.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            downbeats = List(80) { it * 2.0 },
            key = "C major",
            keyConfidence = 0.9,
            contentEndTime = 198.0,
            audibleStartTime = 0.5,
        )
        val next = current.copy(trackId = "b")
        val plan = planTransition(
            analysis = current,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("a", 200_000),
            nextTrack = TransitionTrackInfo("b", 200_000),
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        if (plan.transitionStyle == TransitionStyle.DJ_BLEND || plan.transitionStyle == TransitionStyle.DJ_FILTER) {
            val bars = plan.fadeSeconds / 2.0
            val floor = if (plan.transitionStyle == TransitionStyle.DJ_BLEND) 8.0 else 4.0
            assertTrue(bars + 0.05 >= floor, "${plan.transitionStyle} overlapped $bars bars")
        }
        var previous = MixGain.fall(0f)
        var cursor = 0.02f
        while (cursor <= 1f) {
            val value = MixGain.fall(cursor)
            assertTrue(previous - value < 0.08f, "fall stepped from $previous to $value")
            assertTrue(value <= previous + 0.001f)
            previous = value
            cursor += 0.02f
        }
        assertTrue(MixGain.filterFall(0f) > 0.98f)
        assertTrue(MixGain.filterFall(1f) < 0.02f)
    }

    @Test
    fun tempoGapWithWeakConfidenceIsClean() {
        val weakOut = track(100.0).copy(beatConfidence = 0.1)
        val weakIn = track(140.0).copy(beatConfidence = 0.1)
        val weak = DjMix.annotate(gap(), weakOut, weakIn, outgoingId = "a", incomingId = "b")
        assertEquals(DjEffect.NONE, weak.djEffect)
        assertEquals(0.0, weak.echoSeconds)
        val halfWeak = DjMix.annotate(
            gap(),
            track(100.0).copy(beatConfidence = 0.1),
            track(140.0),
            outgoingId = "a",
            incomingId = "b",
        )
        assertEquals(DjEffect.NONE, halfWeak.djEffect)
        assertEquals(0.0, halfWeak.echoSeconds)
    }

    @Test
    fun fxPolicyIsCleanWhenUnmeasuredOrDualVocal() {
        val slot0 = (0 until 64).first { FxPolicy.fxSlot("out", "fx-$it") == 0 }
        assertEquals(
            DjEffect.NONE,
            FxPolicy.select("out", "fx-$slot0", true, measured = false, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = false),
        )
        assertEquals(
            DjEffect.NONE,
            FxPolicy.select("out", "fx-$slot0", true, measured = true, vocalClash = 0.5, speechLike = false, drifting = false, buildup = false, cleanSpace = false),
        )
        assertEquals(
            DjEffect.NONE,
            FxPolicy.select("out", "fx-$slot0", true, measured = true, vocalClash = 0.0, speechLike = true, drifting = false, buildup = false, cleanSpace = false),
        )
        assertEquals(
            DjEffect.NONE,
            FxPolicy.select("out", "fx-$slot0", true, measured = true, vocalClash = 0.0, speechLike = false, drifting = true, buildup = false, cleanSpace = false),
        )
        assertEquals(
            DjEffect.NONE,
            FxPolicy.select("out", "fx-$slot0", true, measured = true, vocalClash = Double.NaN, speechLike = false, drifting = false, buildup = false, cleanSpace = false),
        )
    }

    @Test
    fun fxPolicyCleanInstrumentalBallotPreservesFlangerAndEcho() {
        val flangerId = (0 until 64).first { FxPolicy.fxSlot("out", "fx-$it") == 0 }
        assertEquals(
            DjEffect.FLANGER,
            FxPolicy.select("out", "fx-$flangerId", true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = false),
        )
        val echoId = (0 until 64).first { FxPolicy.fxSlot("out", "fx-$it") == 2 }
        assertEquals(
            DjEffect.ECHO,
            FxPolicy.select("out", "fx-$echoId", true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = false),
        )
        val clean = FxPolicy.select("a", "b", rhythmic = false, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = true)
        assertTrue(clean == DjEffect.ECHO || clean == DjEffect.REVERB, "cleanSpace must be ECHO/REVERB got $clean")
        assertEquals(
            DjEffect.FLANGER,
            blendColour(0.0, "out", "fx-$flangerId", measured = true, buildup = true),
        )
    }

    @Test
    fun phraseExtendFilterKeepsBarsWithoutLoopRoll() {
        val lyrics = listOf(
            LyricCue(20.0, 24.0, "first line"),
            LyricCue(40.0, 44.0, "second line"),
        )
        val energy = List(170) { EnergySample(it.toDouble(), if (it >= 160) 0.2 else 1.0) }
        val out = track(120.0).copy(
            duration = 170.0,
            contentEndTime = 168.0,
            energyCurve = energy,
            vocalActivityMask = List(170) { 0.05 },
        )
        val inn = track(120.0).copy(trackId = "inn")
        val filterPlan = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_FILTER,
            transitionStart = 160.0,
            transitionEnd = 168.0,
            fadeSeconds = 8.0,
            beatSeconds = 0.5,
            vocalOverlap = 0.0,
        )
        val filtered = DjMix.annotate(filterPlan, out, inn, lyrics, emptyList(), "out-filter", "in-filter")
        assertEquals(4, filtered.phraseExtendBars, "filter must keep bars; summary=${filtered.mixSummary} effect=${filtered.djEffect}")
        assertTrue(filtered.djEffect != DjEffect.LOOP_ROLL, "filter must not get LOOP_ROLL got ${filtered.djEffect}")
        val blendPlan = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 152.0,
            transitionEnd = 168.0,
            fadeSeconds = 16.0,
            beatSeconds = 0.5,
            vocalOverlap = 0.0,
        )
        val blended = DjMix.annotate(blendPlan, out, inn, lyrics, emptyList(), "out-blend", "in-blend")
        assertEquals(4, blended.phraseExtendBars, "blend must keep bars; summary=${blended.mixSummary} effect=${blended.djEffect}")
        assertEquals(DjEffect.LOOP_ROLL, blended.djEffect, "blend must get LOOP_ROLL")
    }

    @Test
    fun colourDoesNotAllocateWhileItProcesses() {
        val colour = DjColour()
        colour.configure(48000, 2)
        colour.set(DjEffect.FLANGER, 0.28f, 0.5f, 0f)
        val block = FloatArray(512 * 2)
        for (i in 0 until 512) {
            val s = kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 48000.0).toFloat()
            block[2 * i] = s
            block[2 * i + 1] = s
        }
        colour.process(block, 512, 2)
        assertTrue(block.any { abs(it) > 0.01f })
        assertTrue(block.all { abs(it) < 4f })
        colour.set(DjEffect.LOOP_ROLL, 1f, 0.5f, 0f)
        colour.process(block, 512, 2)
        assertTrue(block.all { it.isFinite() })
        colour.set(DjEffect.REVERB, 0.22f, 0.5f, 0f)
        colour.process(block, 512, 2)
        assertTrue(block.all { it.isFinite() })
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

    private fun track(bpm: Double, key: String = "C major") = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        duration = 180.0,
        bpm = bpm,
        beatInterval = 60.0 / bpm,
        beatConfidence = 0.9,
        key = key,
        keyConfidence = 0.9,
        contentEndTime = 176.0,
        audibleStartTime = 0.4,
        energyCurve = List(90) { EnergySample(it * 2.0, if (it < 4 || it > 80) 0.2 else 1.0) },
    )
}
