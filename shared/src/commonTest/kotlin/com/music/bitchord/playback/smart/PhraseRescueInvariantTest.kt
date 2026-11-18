package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

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
        assertNull(PhrasePlanner.select(b.copy(beatsPerBar = 3), b, 0, 120.0, 120.0))
    }

    @Test
    fun measuredClashRescuesToEchoButSoftVerseStillBlends() {
        val exit = ExitCue(ExitKind.OUTRO_PHRASE, 24, 48.0, 8, 0, 0,
            SectionFn.OUTRO, SectionRole.GROOVE, .5f, 5, false, 0, 0)
        val entry = EntryCue(EntryKind.HEAD, 0, 0.0, 4, SectionFn.INTRO, SectionRole.GROOVE,
            4, 0f, false, .5f, 5, 0, 0f)
        // Boolean masks deliberately disagree with measured energy: the measured
        // signal must prevent a loud collision without vetoing a soft verse.
        val loudOut = map("a").copy(vocalEnergy = FloatArray(24) { .9f })
        val loudIn = map("b").copy(vocalEnergy = FloatArray(24) { .9f })
        val echo = assertNotNull(PhrasePlanner.musicalRescue(loudOut, loudIn, 0, 120.0, 120.0,
            listOf(exit), listOf(entry)))
        assertEquals(MixTemplate.M6, echo.template)
        assertEquals(0, echo.overlapBars)
        val soft = assertNotNull(PhrasePlanner.musicalRescue(
            loudOut.copy(vocalEnergy = FloatArray(24) { .3f }),
            loudIn.copy(vocalEnergy = FloatArray(24) { .3f }), 0, 120.0, 120.0,
            listOf(exit), listOf(entry)))
        assertEquals(MixTemplate.M1, soft.template)
        assertEquals(.09f, soft.vocalProduct, 1e-5f)
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
        val choice = assertNotNull(PhrasePlanner.select(out, inn, camelotSteps = 4, outBpm = 120.0, inBpm = 120.0))
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
        val latest = ExitCue(ExitKind.CONTENT_END, 24, 48.0, 8, 0, 0,
            SectionFn.OUTRO, SectionRole.GROOVE, .5f, 5, false, 0, 0)
        val valid = EntryCue(EntryKind.HEAD, 0, 0.0, 4, SectionFn.INTRO, SectionRole.GROOVE,
            4, 0f, false, .5f, 5, 0, 0f)
        val choice = assertNotNull(PhrasePlanner.musicalRescue(out, inn, 4, 120.0, 120.0,
            listOf(latest, latest.copy(releaseBar = 16, releaseSec = 32.0)),
            listOf(valid.copy(startSec = 20.0), valid)))
        assertEquals(48.0, choice.exit.releaseSec)
        assertEquals(valid, choice.entry)
        assertEquals(MixTemplate.M6, choice.template)
        assertEquals(0, choice.overlapBars)
    }
}
