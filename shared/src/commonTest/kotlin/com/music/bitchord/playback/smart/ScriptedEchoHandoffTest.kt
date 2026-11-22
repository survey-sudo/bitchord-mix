package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScriptedEchoHandoffTest {
    @Test
    fun coldVocalIsHeldUntilDryEndAndKeepsItsFirstSourceSample() {
        val out = song("out", 120.0)
        val inn = song("cold", 120.0, vocalFrom = 0, start = StartKind.COLD_VOCAL)
        val choice = assertNotNull(PhrasePlanner.select(out, inn, 0, 120.0, 120.0,
            outAnalysis = analysis(out), inAnalysis = analysis(inn, 0.9f)))
        assertEquals(MixTemplate.M6, choice.template)
        val h = assertNotNull(choice.handoff)
        val script = TransitionMoves.compile(choice, 120.0, 120.0)
        val clock = LoopMix.Clock(script.plan)
        assertSame(h, script.handoff)
        assertEquals(choice.entry.startSec, script.plan.inn.loopStart, 1e-9)
        assertEquals(h.outDryEnd.mixSec, h.inHoldMixSec, 1e-9)
        assertEquals(h.inCueSourceSec, clock.inTrackTimeAt(h.inHoldMixSec - 0.001), 1e-9)
        assertEquals(h.inCueSourceSec + 0.01, clock.inTrackTimeAt(h.inHoldMixSec + 0.01), 1e-9)
        assertEquals(h.inHoldMixSec, h.inFullLevelMixSec, 1e-9)
        assertEquals(TransitionMoves.MUTE_DB, script.inGain.values.first())
        assertEquals(0f, script.inGain.values.last())
        assertTrue(h.inHoldMixSec > 1.9, "120 BPM cold entry must not start a bar early")
    }

    @Test
    fun unequalTemposAndTrackedIncomingGridKeepTheWholeEightBarLeadIn() {
        val out = song("out", 100.0)
        val inn = song("intro", 135.0, vocalFrom = 8, drift = 0.003)
        val choice = assertNotNull(PhrasePlanner.select(out, inn, 0, 100.0, 135.0,
            outAnalysis = analysis(out), inAnalysis = analysis(inn)))
        assertEquals(MixTemplate.M6, choice.template)
        val h = assertNotNull(choice.handoff)
        val script = TransitionMoves.compile(choice, 100.0, 135.0)
        val clock = LoopMix.Clock(script.plan)
        assertEquals(0.0, script.plan.inn.loopStart, 1e-9)
        assertEquals(inn.barTime(8), h.inLanding.sourceSec, 1e-9)
        assertEquals(inn.barTime(8), clock.inTrackTimeAt(h.inLanding.mixSec), 1e-9)
        assertEquals(h.inHoldMixSec + inn.barTime(8), h.inLanding.mixSec, 1e-9)
        assertTrue(h.inLanding.mixSec > h.outDryEnd.mixSec + 14.0)
        assertTrue(clock.releaseAt >= h.inLanding.mixSec)
        assertTrue(clock.elementAt <= PhraseConfig.O_MAX_SEC)
        assertEquals(h.inLanding.mixSec / script.plan.out.beatSec, TransitionMoves.landingBeat(script), 1e-9)
    }

    @Test
    fun pickupMetadataCannotOpenAnEntireIntroOverA() {
        val out = song("out", 100.0)
        val inn = song("intro-labelled-pickup", 135.0, vocalFrom = 8,
            start = StartKind.VOCAL_PICKUP, pickup = 1f)
        val h = assertNotNull(PhrasePlanner.scriptedEchoHandoff(exit(out), entry(inn, 8), out, inn,
            100.0, 135.0, analysis(out), analysis(inn)))
        assertEquals(h.outDryEnd.mixSec, h.inHoldMixSec, 1e-9)
        assertEquals(0.0, h.inCueSourceSec, 1e-9)
    }

    @Test
    fun onlyAnExplicitCoveredPickupCanPrecedeDryEnd() {
        val out = song("out", 120.0)
        val inn = song("pickup", 120.0, vocalFrom = 0, start = StartKind.VOCAL_PICKUP,
            pickup = 1f, offset = 0.5)
        val cue = entry(inn, 0).copy(startSec = 0.0)
        val covered = assertNotNull(PhrasePlanner.scriptedEchoHandoff(exit(out), cue, out, inn,
            120.0, 120.0, analysis(out), analysis(inn, 0.9f)))
        assertEquals(covered.outDryEnd.mixSec - 0.5, covered.inHoldMixSec, 1e-9)
        assertEquals(covered.outDryEnd.mixSec, covered.inLanding.mixSec, 1e-9)
        assertEquals(HandoffStatus.QUALIFIED,
            qualifyMusicalHandoff(covered, analysis(out), analysis(inn, 0.9f), null, null).status)
        assertNull(PhrasePlanner.scriptedEchoHandoff(exit(out), cue, out, inn, 120.0, 120.0),
            "Unknown coverage cannot produce an ornamental M6 that the app will refuse")
        val collisionOut = out.copy(sungBars = BooleanArray(24) { true }, vocalEnergy = FloatArray(24) { 0.9f })
        // SongMap collision also blocks early entry even if a contradictory
        // local model says the outgoing bed is clean.
        val collision = assertNotNull(PhrasePlanner.scriptedEchoHandoff(exit(out), cue, collisionOut, inn,
            120.0, 120.0, analysis(out), analysis(inn, 0.9f)))
        assertEquals(collision.outDryEnd.mixSec, collision.inHoldMixSec, 1e-9)
    }

    @Test
    fun infeasibleLongLeadInIsRejectedWithoutMovingTheCue() {
        val out = song("out", 100.0)
        val inn = song("long", 100.0, vocalFrom = 16)
        assertNull(PhrasePlanner.scriptedEchoHandoff(exit(out), entry(inn, 16), out, inn,
            100.0, 100.0, analysis(out), analysis(inn)))
        val trim = entry(inn, 16).copy(kind = EntryKind.INTRO_TRIM, startBar = 8,
            startSec = inn.barTime(8), leadInBars = 8, trimmedBars = 8)
        assertNull(PhrasePlanner.scriptedEchoHandoff(exit(out), trim, out, inn, 100.0, 100.0),
            "Unknown local admission must not authorize an intro trim")
    }

    @Test
    fun admittedPickupScoresActualSoftOverlapEvenWithZeroNominalBars() {
        val out = song("soft-bed", 100.0).copy(vocalEnergy = FloatArray(24) { 0.1f })
        val inn = song("pickup", 135.0, vocalFrom = 0, start = StartKind.VOCAL_PICKUP,
            pickup = 1f, offset = 60.0 / 135.0)
        val choice = assertNotNull(PhrasePlanner.select(out, inn, 0, 100.0, 135.0,
            outAnalysis = analysis(out, 0.1f), inAnalysis = analysis(inn, 0.9f)))
        assertEquals(MixTemplate.M6, choice.template)
        assertEquals(0, choice.overlapBars)
        val h = assertNotNull(choice.handoff)
        assertTrue(h.inAudibleStart.mixSec < h.outDryEnd.mixSec)
        assertEquals(0.09, assertNotNull(choice.vocalClashEnergy), 1e-6)
        assertEquals(0.91, choice.vocalScore, 1e-6)
        assertTrue(choice.vocalProduct > 0f)
    }

    @Test
    fun rescueCannotOverrideMeasuredCutVocalOrUnknownCoverage() {
        val out = song("out", 100.0)
        val inn = song("cold", 135.0, vocalFrom = 0, start = StartKind.COLD_VOCAL)
        assertNull(PhrasePlanner.musicalRescue(out, inn, 0, 100.0, 135.0,
            listOf(exit(out)), listOf(entry(inn, 0)), analysis(out, 0.9f), analysis(inn, 0.9f)))
        assertNull(PhrasePlanner.musicalRescue(out, inn, 0, 100.0, 135.0,
            listOf(exit(out)), listOf(entry(inn, 0)), null, null))
        val partial = out.copy(sungBars = BooleanArray(4), vocalEnergy = FloatArray(4), partial = true)
        assertNull(PhrasePlanner.musicalRescue(partial, inn, 0, 100.0, 135.0,
            listOf(exit(out).copy(roomBars = 8)), listOf(entry(inn, 0)), analysis(out), analysis(inn)))
        val sungOut = song("loud-out", 120.0, vocalFrom = 0, start = StartKind.COLD_VOCAL)
        val sungIn = song("loud-in", 120.0, vocalFrom = 0, start = StartKind.COLD_VOCAL)
        assertNull(PhrasePlanner.musicalRescue(sungOut, sungIn, 0, 120.0, 120.0,
            listOf(exit(sungOut)), listOf(entry(sungIn, 0)), analysis(sungOut, 0.9f), analysis(sungIn, 0.9f)),
            "True local collision must veto M1 and the measured cut must veto M6")
    }

    @Test
    fun unknownOrPartialLocalMasksCannotAdvertiseM6BeforeLateAppRefusal() {
        val out = song("out", 100.0)
        val inn = song("cold", 135.0, vocalFrom = 0, start = StartKind.COLD_VOCAL)
        val unknown = PhrasePlanner.select(out, inn, 0, 100.0, 135.0)
        assertTrue(unknown == null || unknown.template != MixTemplate.M6)
        val partialOut = analysis(out).copy(vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(20) { 0.02f })))
        val partial = PhrasePlanner.select(out, inn, 0, 100.0, 135.0,
            outAnalysis = partialOut, inAnalysis = analysis(inn, 0.9f))
        assertTrue(partial == null || partial.template != MixTemplate.M6)
        assertNull(PhrasePlanner.scriptedEchoHandoff(exit(out), entry(inn, 0), out, inn,
            100.0, 135.0, null, analysis(inn, 0.9f)))
    }

    @Test
    fun cleanBlendAndTwoSidedCrownKeepTheirPriorityAndNullContract() {
        val out = song("out", 120.0)
        val inn = song("intro", 120.0, vocalFrom = 16)
        val choice = assertNotNull(PhrasePlanner.select(out, inn, 0, 120.0, 120.0))
        assertEquals(MixTemplate.M1, choice.template)
        assertNull(TransitionMoves.compile(choice, 120.0, 120.0).handoff)
        val pick = LoopMix.Pick(8.0, 16.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK)
        val crown = LoopMix.Plan(pick, pick, 1.0, 1, 2, 1, 4, outLoops = true, inLoops = true)
        val crowned = assertNotNull(PhrasePlanner.select(out, inn, 0, 120.0, 120.0, legacyLoop = crown))
        assertEquals(MixTemplate.M2, crowned.template)
        assertSame(crown, TransitionMoves.compile(crowned, 120.0, 120.0).plan)
        assertNull(crowned.handoff)
    }

    private fun song(id: String, bpm: Double, vocalFrom: Int? = null,
        start: StartKind = StartKind.INTRO_INSTRUMENTAL, pickup: Float = 0f,
        offset: Double = 0.0, drift: Double = 0.0): SongMap {
        val bar = 240.0 / bpm
        fun section(from: Int, to: Int, fn: SectionFn, vocal: Float) = PhraseSection(
            from, to, fn, if (fn == SectionFn.VERSE) SectionRole.NARRATIVE else SectionRole.GROOVE,
            0.9f, 0, 0, 0.5f, 5, 0f, 0f, vocal, 0.5f, false)
        val sections = if (vocalFrom == null) listOf(section(0, 24, SectionFn.OUTRO, 0f)) else buildList {
            if (vocalFrom > 0) add(section(0, vocalFrom, SectionFn.INTRO, 0f))
            add(section(vocalFrom, 24, SectionFn.VERSE, 0.9f))
        }
        val end = offset + 24 * (bar + drift)
        return SongMap(trackKey = id, durationSec = end, bpm = bpm, beatsPerBar = 4,
            downbeats = DoubleArray(25) { offset + it * (bar + drift) }, gridConfidence = 0.9f,
            tempoStable = true, phraseAnchor = 0, phraseLen = 8, sections = sections,
            sungBars = BooleanArray(24) { vocalFrom != null && it >= vocalFrom },
            vocalEnergy = FloatArray(24) { if (vocalFrom != null && it >= vocalFrom) 0.9f else 0f },
            firstVocalBar = vocalFrom, lastVocalBar = if (vocalFrom != null) 23 else null,
            pickupBeats = pickup, startKind = start, endingKind = EndingKind.HARD_END,
            contentEndSec = end, labelConfidence = 0.9f, structMs = 1)
    }

    private fun analysis(map: SongMap, vocal: Float? = null) = TrackAnalysis(duration = map.durationSec,
        bpm = map.bpm, beatInterval = 60.0 / map.bpm, vocalWindows = listOf(
            CurveWindow(0.0, 20.0, FloatArray((map.durationSec * 20).toInt() + 2) { frame ->
                val bar = map.downbeats.indexOfLast { it <= frame / 20.0 }
                vocal ?: if (map.sungBars.getOrElse(bar) { false }) 0.9f else 0.02f
            }))).withMeasuredEchoEvidence(map)

    private fun exit(map: SongMap) = ExitCue(ExitKind.HARD_END, 24, map.barTime(24),
        24, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE, 0.5f, 5, false, 0, 0)

    private fun entry(map: SongMap, landing: Int) = EntryCue(EntryKind.HEAD, 0, 0.0, landing,
        SectionFn.VERSE, SectionRole.NARRATIVE, landing, 0f, false, 0.5f, 5, 0, map.pickupBeats)
}
