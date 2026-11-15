package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * djmix25 shared suite (vc67) — P1-P2 on-device AutoMix upgrades.
 * Scalars only (patent-safe); no PCM, no cross-track audio comparison.
 */
class Djmix25SharedTest {

    private fun analysis(
        bpm: Double,
        duration: Double = 200.0,
        trackId: String = "t",
        key: String = "",
        keyConfidence: Double = 0.0,
        keyHead: String = "",
        keyTail: String = "",
        keyHeadConfidence: Double = 0.0,
        keyTailConfidence: Double = 0.0,
        vocalProbability: Double = 0.3,
        vocalWindows: List<CurveWindow> = emptyList(),
        instrumentalWindows: List<CurveWindow> = emptyList(),
    ) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = duration,
        bpm = bpm,
        beatInterval = 60 / bpm,
        beatConfidence = 0.85,
        downbeats = List(50) { it * 4 * 60 / bpm },
        firstBeat = 0.5,
        key = key,
        keyConfidence = keyConfidence,
        keyHead = keyHead,
        keyTail = keyTail,
        keyHeadConfidence = keyHeadConfidence,
        keyTailConfidence = keyTailConfidence,
        vocalProbability = vocalProbability,
        vocalWindows = vocalWindows,
        instrumentalWindows = instrumentalWindows,
        quality = TrackAnalysis.QUALITY_FULL,
    )

    private fun map(
        bars: Int = 64,
        energy: (Int) -> Int = { 5 },
        sungRanges: List<IntRange> = emptyList(),
    ): SongMap {
        val sections = listOf(
            PhraseSection(0, bars, SectionFn.CHORUS, SectionRole.RELEASE, 0.8f, 0, 0, 0.8f, energy(0), 0f, 0f, 0.7f, 0.4f, false),
        )
        // Split into per-bar-ish sections so sectionAt works: single chorus
        // section is enough for fingerprint tests; sungBars drive the rest.
        val sung = BooleanArray(bars) { bar -> sungRanges.any { bar in it } }
        val downbeats = DoubleArray(bars) { it * 2.0 }
        return SongMap(
            trackKey = "m",
            durationSec = bars * 2.0,
            bpm = 120.0,
            beatsPerBar = 4,
            downbeats = downbeats,
            gridConfidence = 0.85f,
            tempoStable = true,
            phraseAnchor = 0,
            phraseLen = 8,
            sections = sections,
            sungBars = sung,
            firstVocalBar = sung.indexOfFirst { it }.let { if (it < 0) null else it },
            lastVocalBar = sung.indexOfLast { it }.let { if (it < 0) null else it },
            pickupBeats = 0f,
            startKind = StartKind.INTRO_INSTRUMENTAL,
            endingKind = EndingKind.UNKNOWN,
            contentEndSec = bars * 2.0,
            labelConfidence = 0.8f,
            structMs = 1,
        )
    }

    // ---- KEY region resolve (head vs tail compatibility) ----

    @Test
    fun exitKeyUsesTailAndEntryUsesHead() {
        val out = analysis(120.0, key = "C major", keyConfidence = 0.9, keyTail = "G major", keyTailConfidence = 0.9)
        val inn = analysis(120.0, key = "C major", keyConfidence = 0.9, keyHead = "G major", keyHeadConfidence = 0.9)
        assertEquals("G major", exitKeyForMix(out))
        assertEquals("G major", entryKeyForMix(inn))
        assertEquals(0, regionalCamelotSteps(out, inn))
    }

    @Test
    fun regionalFallsBackToGlobalWhenAbsent() {
        val out = analysis(120.0, key = "C major", keyConfidence = 0.9)
        val inn = analysis(120.0, key = "A minor", keyConfidence = 0.9)
        // C major 8B vs A minor 8A: same number -> 0 steps.
        assertEquals(0, regionalCamelotSteps(out, inn))
    }

    @Test
    fun nativeAsciiAndUnicodeKeysResolveToSameCamelot() {
        // JNI now emits ASCII; Kotlin must map both forms to one root.
        // C# major == Db major (3B); Eb major is 5B; C major is 8B.
        assertEquals("3B", camelotCode("C# major"))
        assertEquals("3B", camelotCode("C\u266f major"))
        assertEquals("3B", camelotCode("Db major"))
        assertEquals("5B", camelotCode("Eb major"))
        assertEquals("5B", camelotCode("E\u266d major"))
        assertEquals("8B", camelotCode("C major"))
        // B natural is 1B, distinct from Db/C# (3B).
        assertEquals("1B", camelotCode("B major"))
    }

    // ---- decline-to-mix must NOT fire on same-camelot relative pairs ----

    @Test
    fun declineDoesNotFireOnRelativePair8Bto8A() {
        // 8B (C major) -> 8A (A minor): same number, 0 steps, even at a
        // tempo delta beyond 8% the key branch must not decline.
        val out = analysis(100.0, key = "C major", keyConfidence = 0.9, vocalProbability = 0.3)
        val inn = analysis(120.0, key = "A minor", keyConfidence = 0.9, vocalProbability = 0.3)
        // Delta 20% > 8% but steps 0 -> no key clash; vocal low -> no decline.
        assertFalse(shouldDeclineToMix(out, inn), "8B->8A relative pair must not decline")
    }

    @Test
    fun declineDoesNotFireOnLowConfidenceKeys() {
        val out = analysis(100.0, key = "C major", keyConfidence = 0.3, vocalProbability = 0.3)
        val inn = analysis(120.0, key = "F major", keyConfidence = 0.3, vocalProbability = 0.3)
        // Steps >2 plausible but confidences < 0.5 -> gated, no decline.
        assertFalse(shouldDeclineToMix(out, inn))
    }

    @Test
    fun declineFiresOnConfidentClashWithFarTempo() {
        val out = analysis(100.0, key = "C major", keyConfidence = 0.9, vocalProbability = 0.3)
        val inn = analysis(130.0, key = "Eb major", keyConfidence = 0.9, vocalProbability = 0.3)
        // C (8B) vs Eb (5B): dist 3 -> clash, delta 30% -> decline.
        assertTrue(shouldDeclineToMix(out, inn))
    }

    // ---- INTERPOLATION: chorus-reuse edges ----

    @Test
    fun interpolationFindsRepeatedBars() {
        // Two identical-energy sung blocks => fingerprints repeat.
        val m = map(
            bars = 32,
            energy = { 7 },
            sungRanges = listOf(0 until 8, 16 until 24),
        )
        val opps = InterpolationAnalyzer.interpolationOpportunities(m)
        assertTrue(opps.isNotEmpty(), "repeats must be opportunities, got $opps")
        assertTrue(InterpolationAnalyzer.repeatedBars(m, 16) > 0)
        assertEquals(0, InterpolationAnalyzer.firstOccurrence(m, 16))
    }

    @Test
    fun interpolationUniqueBarsAreNotExtensible() {
        val m = map(
            bars = 32,
            energy = { it -> (it % 10) + 1 },
            sungRanges = listOf(0 until 4),
        )
        // Varying energy makes most bars unique; first bar has no earlier twin.
        assertEquals(0, InterpolationAnalyzer.firstOccurrence(m, 0))
    }

    @Test
    fun phrasePlannerPrefersSegmentAlignedExits() {
        // cue generation includes VERSE_END / BRIDGE_END when the map paints them.
        val sections = listOf(
            PhraseSection(0, 8, SectionFn.INTRO, SectionRole.GROOVE, 0.5f, 0, 0, 0.4f, 4, 0f, 0f, 0f, 0.4f, false),
            PhraseSection(8, 16, SectionFn.CHORUS, SectionRole.RELEASE, 0.8f, 0, 0, 0.8f, 8, 0f, 0f, 0.7f, 0.4f, false),
            PhraseSection(16, 24, SectionFn.VERSE, SectionRole.NARRATIVE, 0.6f, 0, 0, 0.6f, 6, 0f, 0f, 0.7f, 0.4f, false),
            PhraseSection(24, 32, SectionFn.BRIDGE, SectionRole.NARRATIVE, 0.5f, 0, 0, 0.5f, 5, 0f, 0f, 0.7f, 0.4f, false),
        )
        val sung = BooleanArray(32) { it in 8 until 32 }
        val m = SongMap(
            trackKey = "v", durationSec = 64.0, bpm = 120.0, beatsPerBar = 4,
            downbeats = DoubleArray(33) { it * 2.0 }, gridConfidence = 0.85f, tempoStable = true,
            phraseAnchor = 0, phraseLen = 8, sections = sections, sungBars = sung,
            firstVocalBar = 8, lastVocalBar = 31, pickupBeats = 0f,
            startKind = StartKind.INTRO_INSTRUMENTAL, endingKind = EndingKind.UNKNOWN,
            contentEndSec = 64.0, labelConfidence = 0.8f, structMs = 1,
        )
        val exits = CuePoints.exits(m)
        val kinds = exits.map { it.kind }
        assertTrue(ExitKind.VERSE_END in kinds, "VERSE_END must be offered, got $kinds")
        assertTrue(ExitKind.BRIDGE_END in kinds, "BRIDGE_END must be offered, got $kinds")
    }

    @Test
    fun verseEndOfferedDespiteSungContinuationAndLaterChorus() {
        // Defensive: VERSE_END must survive when the next section is sung
        // AND a later chorus exists (both hard vetoes exempt it; the
        // scorer prices it).
        val sections = listOf(
            PhraseSection(0, 8, SectionFn.INTRO, SectionRole.GROOVE, 0.5f, 0, 0, 0.4f, 4, 0f, 0f, 0f, 0.4f, false),
            PhraseSection(8, 16, SectionFn.CHORUS, SectionRole.RELEASE, 0.8f, 0, 0, 0.8f, 8, 0f, 0f, 0.7f, 0.4f, false),
            PhraseSection(16, 24, SectionFn.VERSE, SectionRole.NARRATIVE, 0.6f, 0, 0, 0.6f, 6, 0f, 0f, 0.7f, 0.4f, false),
            PhraseSection(24, 32, SectionFn.CHORUS, SectionRole.RELEASE, 0.8f, 0, 0, 0.8f, 8, 0f, 0f, 0.7f, 0.4f, false),
        )
        val sung = BooleanArray(32) { it in 8 until 32 }
        val m = SongMap(
            trackKey = "w", durationSec = 64.0, bpm = 120.0, beatsPerBar = 4,
            downbeats = DoubleArray(33) { it * 2.0 }, gridConfidence = 0.85f, tempoStable = true,
            phraseAnchor = 0, phraseLen = 8, sections = sections, sungBars = sung,
            firstVocalBar = 8, lastVocalBar = 31, pickupBeats = 0f,
            startKind = StartKind.INTRO_INSTRUMENTAL, endingKind = EndingKind.UNKNOWN,
            contentEndSec = 64.0, labelConfidence = 0.8f, structMs = 1,
        )
        val kinds = CuePoints.exits(m).map { it.kind }
        assertTrue(ExitKind.VERSE_END in kinds, "VERSE_END must be offered, got $kinds")
    }

    // ---- P1.11 echo density + P1.12 fader table ----

    @Test
    fun echoFeedbackIsTunedByFadeAndTemplate() {
        assertEquals(0.55f, PhraseConfig.echoFeedbackFor("M6", 14.0))
        assertEquals(0.45f, PhraseConfig.echoFeedbackFor("M6", 6.0))
        assertEquals(0.52f, PhraseConfig.echoFeedbackFor("M6", 10.0))
        // M10/CUT get half.
        assertEquals(0.275f, PhraseConfig.echoFeedbackFor("M10", 14.0))
    }

    @Test
    fun faderTemplatesDifferBySwapWidth() {
        val club = MixGain.faderTemplateFor("club")
        val lyrical = MixGain.faderTemplateFor("lyrical")
        assertEquals(1.0, club.swapBeats)
        assertEquals(0.5, lyrical.swapBeats)
        assertTrue(MixGain.faderTemplate(0f, club) == 1f)
        assertTrue(MixGain.faderTemplate(1f, club) == 0f)
    }

    // ---- instrumental share ----

    @Test
    fun instrumentalShareMirrorsVocalWindows() {
        val fps = 10.0
        val vocal = CurveWindow(0.0, fps, FloatArray(100) { 0.8f })
        val inst = CurveWindow(0.0, fps, FloatArray(100) { 0.2f })
        val a = analysis(120.0, vocalWindows = listOf(vocal), instrumentalWindows = listOf(inst))
        val share = LoopMix.instrumentalShare(a, 0.0, 5.0)
        assertNotNull(share)
        assertTrue(abs(share!! - 0.2) < 0.02, "got $share")
    }

    // ---- acapella gate ----

    @Test
    fun acapellaRequiresSameCamelotAndConfidence() {
        val out = analysis(120.0, key = "C major", keyConfidence = 0.9, keyTail = "C major", keyTailConfidence = 0.9, vocalProbability = 0.9)
        val inn = analysis(120.0, key = "C major", keyConfidence = 0.9, keyHead = "C major", keyHeadConfidence = 0.9, vocalProbability = 0.2)
        assertTrue(TransitionMoves.shouldAcapella(out, inn))
        val weak = analysis(120.0, key = "C major", keyConfidence = 0.4, keyTail = "C major", keyTailConfidence = 0.4, vocalProbability = 0.9)
        assertFalse(TransitionMoves.shouldAcapella(weak, inn))
    }

    // ---- decline now routes to echo (bridge) ----

    @Test
    fun bridgeGivesDeclinedPairsAnEchoRung() {
        val out = analysis(100.0, duration = 200.0, key = "C major", keyConfidence = 0.9, vocalProbability = 0.3)
        // Fill grids: constantGrid needs beatConfidence + bpm; downbeats set.
        val inn = analysis(130.0, duration = 200.0, key = "Eb major", keyConfidence = 0.9, vocalProbability = 0.3)
        // Far tempo + key clash -> declined, but bridge must still echo.
        assertTrue(shouldDeclineToMix(out, inn))
        val plan = LoopMix.bridge(out, out.duration, inn, inn.duration, 0.0)
        assertNotNull(plan, "declined pair must still get bridge echo")
        assertEquals("Echo", plan!!.rung)
        assertTrue(plan.echoTail)
    }
    // ---- vc67 P2 MixWeightTuner ----
    @Test
    fun tunerClampsToBounds() {
        MixWeightTuner.reset()
        assertEquals(1.0, MixWeightTuner.vocalWeight, 1e-9)
        repeat(30) { MixWeightTuner.recordFeedback("a->b", true, true, false, false, false) }
        assertEquals(1.5, MixWeightTuner.vocalWeight, 1e-9)
        repeat(40) { MixWeightTuner.recordFeedback("a->b", false, true, false, false, false) }
        assertEquals(0.6, MixWeightTuner.vocalWeight, 1e-9)
        MixWeightTuner.recordFeedback("", true, true, true, true, true)
        MixWeightTuner.reset()
        assertEquals(1.0, MixWeightTuner.vocalWeight, 1e-9)
        assertEquals(1.0, MixWeightTuner.keyWeight, 1e-9)
    }
    @Test
    fun tunerDefaultsPreservePhraseScores() {
        MixWeightTuner.reset()
        val snap = MixWeightTuner.snapshot()
        assertEquals(1.0, snap.keyWeight, 1e-9)
        assertEquals(1.0, snap.energyWeight, 1e-9)
        assertEquals(1.0, snap.tempoWeight, 1e-9)
        MixWeightTuner.recordFeedback("a->b", true, false, true, true, true)
        assertTrue(MixWeightTuner.keyWeight > 1.0)
        assertTrue(MixWeightTuner.energyWeight > 1.0)
        assertTrue(MixWeightTuner.tempoWeight > 1.0)
        MixWeightTuner.reset()
    }
    @Test
    fun tunerUnflaggedDeltasAreNoops() {
        MixWeightTuner.reset()
        MixWeightTuner.recordFeedback("a->b", true, false, false, false, false)
        assertEquals(1.0, MixWeightTuner.vocalWeight, 1e-9)
        assertEquals(1.0, MixWeightTuner.keyWeight, 1e-9)
    }
    // ---- vc67 P2 MixVerify ----
    @Test
    fun verifyPassesWhenAllGatesClear() {
        val r = MixVerify.verify(1.0, 0.5f, 5.0, 0)
        assertTrue(r.ok)
        assertTrue(r.reasons.isEmpty())
    }
    @Test
    fun verifyFailsEachGate() {
        assertFalse(MixVerify.verify(3.5, 0.5f, 0.0, 0).ok)
        assertFalse(MixVerify.verify(0.0, 0.95f, 0.0, 0).ok)
        assertFalse(MixVerify.verify(0.0, 0.5f, 30.0, 0).ok)
        assertFalse(MixVerify.verify(0.0, 0.5f, 0.0, 1).ok)
        assertFalse(MixVerify.verify(0.0, 0.5f, 0.0, -1).ok)
    }
    @Test
    fun verifyBoundaryValuesPass() {
        assertTrue(MixVerify.verify(3.0, 0.896f, 25.0, 0).ok)
        assertTrue(MixVerify.verify(-3.0, 0.0f, -25.0, 0).ok)
    }


}
