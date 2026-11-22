package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhraseRescueInvariantTest {
    private fun map(
        key: String,
        start: StartKind = StartKind.INTRO_INSTRUMENTAL,
        speechLike: Boolean = false,
        activation: Float = 1f,
        bars: Int = 24,
        vocalBars: IntRange = IntRange.EMPTY,
        vocalEnergyVal: Float = 0.9f,
        sections: List<PhraseSection>? = null,
    ): SongMap {
        val barSec = 2.0
        val defaultSections = listOf(
            PhraseSection(0, bars, SectionFn.OUTRO, SectionRole.GROOVE, 0.8f, 0, 0, 0.5f, 5, 0f, 0f, if (vocalBars.isEmpty()) 0f else 0.9f, 0.4f, false),
        )
        return SongMap(
            trackKey = key,
            durationSec = bars * barSec,
            bpm = 120.0,
            beatsPerBar = 4,
            downbeats = DoubleArray(bars + 1) { it * barSec },
            gridConfidence = 0.9f,
            tempoStable = true,
            phraseAnchor = 0,
            phraseLen = 8,
            sections = sections ?: defaultSections,
            sungBars = BooleanArray(bars) { it in vocalBars },
            vocalEnergy = FloatArray(bars) { if (it in vocalBars) vocalEnergyVal else 0f },
            firstVocalBar = vocalBars.firstOrNull(),
            lastVocalBar = vocalBars.lastOrNull(),
            pickupBeats = 0f,
            startKind = start,
            endingKind = EndingKind.HARD_END,
            contentEndSec = bars * barSec,
            labelConfidence = 0.8f,
            structMs = 1,
            activation = activation,
            speechLike = speechLike,
        )
    }

    @Test
    fun spokenOrLowActivationRescueNeverForcesBeatmatchedPlan() {
        val spoken = map("a", start = StartKind.SPOKEN, speechLike = true)
        val lowAct = map("c", activation = 0.05f)
        val b = map("b")
        val c1 = PhrasePlanner.select(spoken, b, 0, 120.0, 120.0)
        val c2 = PhrasePlanner.select(lowAct, b, 0, 120.0, 120.0)
        assertTrue(c1 == null || c1.template in setOf(MixTemplate.RELAXED, MixTemplate.M11))
        assertTrue(c2 == null || c2.template in setOf(MixTemplate.RELAXED, MixTemplate.M11))
    }

    @Test
    fun matchedTempoKeyClashRescueUsesEchoInsteadOfLongBeatBlend() {
        val out = map(
            "out-clash",
            sections = listOf(PhraseSection(0, 24, SectionFn.CHORUS, SectionRole.RELEASE, 0.9f, 0, 0, 0.8f, 8, 0f, 0f, 0f, 0.5f, false)),
        )
        val inn = map(
            "in-clash",
            sections = listOf(PhraseSection(0, 24, SectionFn.CHORUS, SectionRole.RELEASE, 0.9f, 0, 0, 0.8f, 8, 0f, 0f, 0f, 0.5f, false)),
        )
        val choice = assertNotNull(PhrasePlanner.select(out, inn, camelotSteps = 4, outBpm = 120.0, inBpm = 120.0,
            outAnalysis = measuredQuiet(out), inAnalysis = measuredQuiet(inn)))
        assertEquals(MixTemplate.M6, choice.template)
        assertEquals(0, choice.overlapBars)
    }

    @Test
    fun rescueNeverSkipsPastIncomingSkipCapOrPicksEarlierExitWhenLaterExists() {
        val out = map(
            "out-multi",
            sections = listOf(
                PhraseSection(0, 12, SectionFn.VERSE, SectionRole.NARRATIVE, 0.9f, 0, 0, 0.6f, 6, 0f, 0f, 0f, 0.4f, false),
                PhraseSection(12, 24, SectionFn.CHORUS, SectionRole.RELEASE, 0.9f, 1, 0, 0.8f, 8, 0f, 0f, 0f, 0.5f, false),
            ),
        )
        val inn = map(
            "in-multi",
            sections = listOf(PhraseSection(0, 24, SectionFn.CHORUS, SectionRole.RELEASE, 0.9f, 0, 0, 0.8f, 8, 0f, 0f, 0f, 0.5f, false)),
        )
        val choice = assertNotNull(PhrasePlanner.select(out, inn, camelotSteps = 0, outBpm = 120.0, inBpm = 120.0,
            outAnalysis = measuredQuiet(out), inAnalysis = measuredQuiet(inn)))
        assertTrue(choice.entry.startSec <= inn.durationSec * 0.25 + 1e-6)
        assertTrue(choice.exit.releaseSec >= out.durationSec * 0.68 - 1e-6)
    }

    /** These fixtures explicitly contain instrumental audio on both sides. */
    private fun measuredQuiet(map: SongMap) = TrackAnalysis(duration = map.durationSec, bpm = map.bpm,
        beatInterval = 60.0 / map.bpm, vocalWindows = listOf(
            CurveWindow(0.0, 20.0, FloatArray((map.durationSec * 20).toInt() + 2) { 0.02f }))).withMeasuredEchoEvidence(map)
}
