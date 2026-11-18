package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhraseGeometryContractTest {
    private fun map(
        key: String,
        bars: Int = 32,
        bpm: Double = 120.0,
        vocalBars: IntRange = IntRange.EMPTY,
        vocalEnergyVal: Float = 0.9f,
        role: SectionRole = SectionRole.GROOVE,
        fn: SectionFn = SectionFn.OUTRO,
    ): SongMap {
        val barSec = 240.0 / bpm
        val sung = BooleanArray(bars) { it in vocalBars }
        val vEnergy = FloatArray(bars) { if (it in vocalBars) vocalEnergyVal else 0.0f }
        return SongMap(
            trackKey = key,
            durationSec = bars * barSec,
            bpm = bpm,
            beatsPerBar = 4,
            downbeats = DoubleArray(bars + 1) { it * barSec },
            gridConfidence = 0.92f,
            tempoStable = true,
            phraseAnchor = 0,
            phraseLen = 8,
            anchors = intArrayOf(0, 8, 16, 24).filter { it < bars }.toIntArray(),
            sections = listOf(PhraseSection(0, bars, fn, role, 0.9f, 0, 0, 0.6f, 6, 0f, 0f, if (vocalBars.isEmpty()) 0f else 0.8f, 0.4f, true)),
            sungBars = sung,
            vocalEnergy = vEnergy,
            firstVocalBar = vocalBars.firstOrNull(),
            lastVocalBar = vocalBars.lastOrNull(),
            pickupBeats = 0f,
            startKind = if (0 in vocalBars) StartKind.COLD_VOCAL else StartKind.INTRO_INSTRUMENTAL,
            endingKind = EndingKind.OUTRO_INSTRUMENTAL,
            contentEndSec = bars * barSec,
            labelConfidence = 0.9f,
            structMs = 1,
        )
    }

    @Test
    fun loopPickUsesBarsNotBeatsForTwoAndFourBarLoopsAndClampsToRoom() {
        val exit4 = ExitCue(ExitKind.OUTRO_PHRASE, 16, 32.0, 8, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE, 0.5f, 5, true, 0, 0)
        val entry = EntryCue(EntryKind.HEAD, 0, 0.0, 0, SectionFn.INTRO, SectionRole.GROOVE, 0, 0f, false, 0.5f, 5, 0, 0f)
        val choice4 = PhraseChoice(
            template = MixTemplate.M2, rung = "Loop mix", score = 1.0, exit = exit4, entry = entry,
            overlapBars = 4, swapBar = 0.0, tailBeats = 0.0, loopK = 0, echoGated = false,
            vocalProduct = 0f, tempoPrepBars = 0, echoDelayBeats = 0.0, echoDelayName = "none",
            swapRampMs = 0, swapRampName = "none", tempoPolicy = "b", vocalOverlapBeats = 0.0,
            energyDelta = 0f, struct = 1.0, vocalScore = 1.0, phraseScore = 1.0,
            energyScore = 1.0, lengthScore = 1.0, keyScore = 1.0, tempoScore = 1.0, cost = 0.0,
        )
        val script4 = TransitionMoves.compile(choice4, 120.0, 120.0)
        assertEquals(8.0, script4.plan.out.loopEnd - script4.plan.out.loopStart, 0.05)

        val exit2 = exit4.copy(roomBars = 2)
        val script2 = TransitionMoves.compile(choice4.copy(exit = exit2), 120.0, 120.0)
        assertEquals(4.0, script2.plan.out.loopEnd - script2.plan.out.loopStart, 0.05)

        // Insufficient room before release: releaseSec=5.0 at 120 BPM has only 2 bars available -> clamped cleanly, non-negative.
        val crampedExit = exit4.copy(releaseBar = 2, releaseSec = 5.0, roomBars = 4)
        val scriptCramped = TransitionMoves.compile(choice4.copy(exit = crampedExit), 120.0, 120.0)
        assertTrue(scriptCramped.plan.out.loopStart >= 0.0)
        assertTrue(scriptCramped.plan.out.loopEnd - scriptCramped.plan.out.loopStart <= 5.0 + 1e-6)
    }

    @Test
    fun zeroDryOverlapTemplatesDoNotChargeIncomingLeadInAsDryCollision() {
        val out = map("out-m6", bars = 24, vocalBars = 20..23, vocalEnergyVal = 0.9f, fn = SectionFn.VERSE, role = SectionRole.NARRATIVE)
        val inn = map("in-m6", bars = 24, vocalBars = 0..7, vocalEnergyVal = 0.9f, fn = SectionFn.INTRO, role = SectionRole.GROOVE)
        val choice = assertNotNull(PhrasePlanner.select(out, inn, camelotSteps = 4, outBpm = 120.0, inBpm = 142.0))
        assertTrue(choice.template in setOf(MixTemplate.M6, MixTemplate.M10, MixTemplate.CUT_ON_ONE), "got ${choice.template}")
        assertEquals(0.0, choice.vocalOverlapBeats, 1e-6)
        assertEquals(0.0, choice.vocalClashEnergy ?: -1.0, 1e-6)
        assertEquals(0f, choice.vocalProduct, 1e-6f)
    }

    @Test
    fun vetoAndScoreShareHarmonicClashOverlapGeometry() {
        // Long runway (16 clean room bars) with a key clash (steps=3): overlapOf(..., steps=3) caps to 8 bars.
        // Incoming bars 8..15 are sung, bars 0..7 are clean. If score omitted steps it would measure 16 bars and clash!
        val out = map("out-long", bars = 32, vocalBars = 16..23, vocalEnergyVal = 0.9f, fn = SectionFn.OUTRO, role = SectionRole.GROOVE)
        val inn = map("in-long", bars = 32, vocalBars = 8..15, vocalEnergyVal = 0.9f, fn = SectionFn.INTRO, role = SectionRole.GROOVE)
        val choice = assertNotNull(PhrasePlanner.select(out, inn, camelotSteps = 3, outBpm = 120.0, inBpm = 120.0))
        assertTrue(choice.overlapBars <= 8, "clash overlap must stay <=8 bars, got ${choice.overlapBars}")
        assertTrue((choice.vocalClashEnergy ?: 0.0) <= 0.20, "clash=${choice.vocalClashEnergy}")
    }

    @Test
    fun singleLoudBarInEightIsVetoedByCountFloor() {
        // 1 loud bar (0.9 x 0.9) in an 8-bar overlap dilutes to mean
        // 0.81/8 = 0.101, below CLASH_VETO 0.20. The count floor (>0.05)
        // must still veto: exactly one sung bar overlapped (4 beats).
        // Out: all-sung chorus forces the synthesized room-8 exit so the
        // window holds sung bars. In: one sung bar in the 0..7 window,
        // landing moved to 8 via firstVocalBar so leadIn is 8 and M1 is
        // a candidate (leadInVocal 1/8 < 0.2). Earlier vetoes (tension,
        // chorus, mid-verse) all pass, so the clash veto is reached.
        val out = map("out-dilute", bars = 18, vocalBars = 0..17, vocalEnergyVal = 0.9f, fn = SectionFn.CHORUS, role = SectionRole.RELEASE)
            .copy(endingKind = EndingKind.HARD_END)
        val inn = map("in-dilute", bars = 32, vocalBars = 4..4, vocalEnergyVal = 0.9f, fn = SectionFn.INTRO, role = SectionRole.GROOVE)
            .copy(firstVocalBar = 8, lastVocalBar = 4)
        PhrasePlanner.select(out, inn, camelotSteps = 0, outBpm = 120.0, inBpm = 120.0)
        assertTrue(
            PhrasePlanner.lastVetoSummary.contains("vocal clash"),
            "expected vocal clash veto for 1-in-8, got ${PhrasePlanner.lastVetoSummary}",
        )
    }
}
