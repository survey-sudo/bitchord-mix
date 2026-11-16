package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhrasePlannerTest {
    @Test
    fun outroIntroBlendIsM1WithSwapAtMinus8() {
        val a = song("a", 120.0, 16, listOf(section(0, 16, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5)))
        val b = song(
            "b", 120.0, 32,
            listOf(
                section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 5),
                section(16, 32, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 5, vocal = 0.8f),
            ),
            firstVocal = 16,
            sungFrom = 16,
            start = StartKind.INTRO_INSTRUMENTAL,
        )
        val choice = assertNotNull(PhrasePlanner.select(a, b, 0, 120.0, 120.0))
        assertEquals(MixTemplate.M1, choice.template)
        assertEquals(16, choice.overlapBars)
        assertEquals(a.sections[0].endBar, choice.exit.releaseBar)
        assertEquals(choice.exit.releaseBar, choice.entry.landingBar)
        assertEquals(-8.0, choice.swapBar, 0.01)
        assertEquals("Beat blend", choice.rung)
        assertEquals("SWAP_RAMP_BLEND_MS", choice.swapRampName)
        assertEquals(120, choice.swapRampMs)
        assertEquals("TEMPO_POLICY=b", choice.tempoPolicy)
    }

    @Test
    fun vocalInEchoesInstrumentalOutroIntoPickup() {
        // Under the retuned weights the echo-out wins this pair: A is a pure
        // instrumental outro, so a blend (M4) buys nothing — there is no vocal
        // of A's to blend against B's pickup. Echoing A out and landing B's
        // vocal pickup at bar 4 is cleaner, and the old stacked bonuses that
        // used to outvote the template table are now capped at 0.08.
        val a = song("a", 120.0, 8, listOf(section(0, 8, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5)))
        val b = song(
            "b", 120.0, 16,
            listOf(
                section(0, 4, SectionFn.INTRO, SectionRole.GROOVE, energy = 5),
                section(4, 16, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 6, vocal = 0.9f),
            ),
            firstVocal = 4,
            sungFrom = 4,
            start = StartKind.VOCAL_PICKUP,
            pickup = 1f,
        )
        val choice = assertNotNull(PhrasePlanner.select(a, b, 1, 120.0, 120.0))
        assertEquals(MixTemplate.M6, choice.template)
        assertEquals(4, choice.entry.landingBar)
        assertEquals(0.0, choice.tailBeats, 0.01)
        assertEquals("Echo", choice.rung)
    }

    @Test
    fun chorusOutEchoUsesThreeQuarterBeatAndGatesTheSend() {
        val a = song(
            "a", 120.0, 8,
            listOf(section(0, 8, SectionFn.CHORUS, SectionRole.RELEASE, energy = 8, vocal = 0.9f)),
            ending = EndingKind.HARD_END,
            sungFrom = 0,
            sungUntil = 8,
        )
        val b = song(
            "b", 120.0, 8,
            listOf(section(0, 8, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 6, vocal = 0.9f)),
            firstVocal = 0,
            sungFrom = 0,
            start = StartKind.COLD_VOCAL,
        )
        val choice = assertNotNull(PhrasePlanner.select(a, b, 1, 120.0, 120.0))
        assertEquals(MixTemplate.M6, choice.template)
        assertEquals(0.75, choice.echoDelayBeats, 1e-9)
        assertEquals("ECHO_DELAY_BEATS", choice.echoDelayName)
        assertTrue(choice.echoGated)
        assertEquals("Echo", choice.rung)
        val script = TransitionMoves.compile(choice, 120.0, 120.0)
        assertTrue(script.echoDelaySec > 0.0)
        assertTrue(script.echoSend.values.first() == 0f)
    }

    @Test
    fun shortLoopableOutroExtends() {
        val a = song(
            "a", 120.0, 4,
            listOf(section(0, 4, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5, loopable = true)),
            ending = EndingKind.LOOPABLE_OUTRO,
        )
        val b = song(
            "b", 120.0, 32,
            listOf(
                section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 5),
                section(16, 32, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 5, vocal = 0.8f),
            ),
            firstVocal = 16,
            sungFrom = 16,
        )
        val choice = assertNotNull(PhrasePlanner.select(a, b, 0, 120.0, 120.0))
        assertEquals(MixTemplate.M2, choice.template)
        assertTrue(choice.loopK >= 3, "k=${choice.loopK}")
        assertTrue(4 + choice.loopK * 4 >= 16)
        assertEquals("Loop mix", choice.rung)
    }

    @Test
    fun fadeReleasesAtTheFadeBarNotTheEnd() {
        val fadeAt = 56
        val a = song(
            "a", 100.0, 80,
            listOf(
                section(0, 32, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 5, vocal = 0.5f),
                section(32, fadeAt, SectionFn.CHORUS, SectionRole.RELEASE, energy = 7, vocal = 0.8f),
                section(fadeAt, 80, SectionFn.FADE_OUT, SectionRole.AMBIENT, energy = 3),
            ),
            ending = EndingKind.FADE_OUT,
            sungFrom = 0,
            sungUntil = fadeAt,
        )
        val b = song(
            "b", 100.0, 16,
            listOf(section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 4)),
        )
        val choice = assertNotNull(PhrasePlanner.select(a, b, 0, 100.0, 100.0))
        assertEquals(MixTemplate.M9, choice.template)
        assertEquals(ExitKind.FADE_START, choice.exit.kind)
        assertEquals(fadeAt, choice.exit.releaseBar)
        assertTrue(choice.exit.releaseBar < 80)
        assertEquals("Smooth fade", choice.rung)
    }

    @Test
    fun verseProtectionHoldsOverRandomMaps() {
        val random = Random(7)
        repeat(500) {
            val vocal = random.nextInt(2, 8) * 4
            val b = song(
                "b$it", 120.0, vocal + 16,
                listOf(
                    section(0, vocal, SectionFn.INTRO, SectionRole.GROOVE, energy = 4 + random.nextInt(3)),
                    section(vocal, vocal + 16, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 6, vocal = 0.8f),
                ),
                firstVocal = vocal,
                sungFrom = vocal,
                pickup = if (random.nextBoolean()) 1f else 0f,
            )
            val a = song(
                "a$it", 120.0, 16,
                listOf(section(0, 16, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5)),
            )
            val choice = PhrasePlanner.select(a, b, random.nextInt(0, 3), 120.0, 120.0)
            if (choice != null) {
                val pickupBars = kotlin.math.ceil(b.pickupBeats / 4.0).toInt()
                assertTrue(choice.entry.startBar <= vocal - pickupBars, "start ${choice.entry.startBar} vocal $vocal")
            }
        }
    }

    @Test
    fun nonCarveTemplatesDoNotStackVocals() {
        val random = Random(3)
        repeat(40) {
            val a = song(
                "a", 120.0, 16,
                listOf(section(0, 16, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5, vocal = 0.2f)),
                sungFrom = 0,
                sungUntil = 16,
            )
            val b = song(
                "b", 120.0, 16,
                listOf(section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 5)),
                sungFrom = 0,
                sungUntil = if (random.nextBoolean()) 8 else 0,
            )
            val choice = PhrasePlanner.select(a, b, 0, 120.0, 120.0) ?: return@repeat
            val carve = choice.template == MixTemplate.M4 || choice.template == MixTemplate.M5 ||
                choice.template == MixTemplate.ROLLING || choice.template == MixTemplate.VOCAL_OVER_OUTRO
            if (!carve) assertTrue(choice.vocalOverlapBeats <= 1.0, "${choice.template} clash ${choice.vocalOverlapBeats}")
        }
    }

    @Test
    fun chosenPlansSitOnThePhraseGrid() {
        val a = song("a", 120.0, 32, listOf(section(0, 32, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5)))
        val b = song(
            "b", 120.0, 32,
            listOf(
                section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 5),
                section(16, 32, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 5, vocal = 0.7f),
            ),
            firstVocal = 16,
            sungFrom = 16,
        )
        val choice = assertNotNull(PhrasePlanner.select(a, b, 0, 120.0, 120.0))
        assertTrue(a.isPhraseDownbeat(choice.exit.releaseBar) || a.anchors.contains(choice.exit.releaseBar))
        assertTrue(b.isPhraseDownbeat(choice.entry.landingBar) || b.anchors.contains(choice.entry.landingBar))
    }

    @Test
    fun incomingRateIsUnityFromMinusTwoBeats() {
        val a = song("a", 126.0, 16, listOf(section(0, 16, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5)))
        val b = song("b", 128.0, 16, listOf(section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 5)))
        val choice = assertNotNull(PhrasePlanner.select(a, b, 1, 126.0, 128.0))
        var beat = -2.0
        while (beat <= 16.0) {
            assertEquals(1.0, choice.incomingRate(beat), 1e-4, "beat $beat")
            beat += 0.5
        }
        assertEquals("TEMPO_POLICY=b", choice.tempoPolicy)
        assertTrue(choice.tempoPrepBars > 0)
    }

    @Test
    fun loopCrownKeepsAtLeastTheLegacyBars() {
        val out = analysis(124.0, 200.0)
        val inn = analysis(124.0, 180.0)
        val legacy = assertNotNull(LoopMix.plan(out, out.duration, inn, inn.duration, 0.0, emptyList(), emptyList()))
        val mapA = song("a", 124.0, 16, listOf(section(0, 16, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5, loopable = true)))
        val mapB = song("b", 124.0, 16, listOf(section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 5, loopable = true)))
        val crowned = assertNotNull(PhrasePlanner.select(mapA, mapB, 0, 124.0, 124.0, legacyLoop = legacy))
        val plan = assertNotNull(crowned.legacyLoop)
        assertTrue(plan.outLoops && plan.inLoops)
        assertTrue(plan.out.bars >= legacy.out.bars)
        assertTrue(plan.inn.bars >= legacy.inn.bars)
        assertEquals(legacy.out.bars, plan.out.bars)
        assertEquals(legacy.inn.bars, plan.inn.bars)
    }

    @Test
    fun missingIncomingMapLeavesTheDjmix6PlanUntouched() {
        val out = analysis(120.0, 180.0)
        val inn = analysis(120.0, 160.0)
        val legacy = LoopMix.plan(out, out.duration, inn, inn.duration, 0.0, emptyList(), emptyList())
        val mapA = song("a", 120.0, 16, listOf(section(0, 16, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5)))
        assertNull(PhrasePlanner.select(mapA, null, 0, 120.0, 120.0))
        val resolved = PhrasePlanner.select(mapA, null, 0, 120.0, 120.0) ?: legacy
        assertEquals(legacy, resolved)
    }

    @Test
    fun plannerMethodsDoNotTakeTwoFrameSequences() {
        val types = PhrasePlanner::class.java.methods.flatMap { method ->
            listOf(method.parameterTypes.toList())
        }
        for (params in types) {
            val frames = params.count { it == FloatArray::class.java || it == DoubleArray::class.java }
            assertTrue(frames < 2, params.joinToString())
        }
    }

    @Test
    fun tensionIntoAmbientIsNeverChosen() {
        val a = song(
            "a", 120.0, 16,
            listOf(section(0, 16, SectionFn.PRE_CHORUS, SectionRole.TENSION, energy = 7)),
            ending = EndingKind.HARD_END,
        )
        val b = song(
            "b", 120.0, 16,
            listOf(section(0, 16, SectionFn.INTRO, SectionRole.AMBIENT, energy = 2)),
            start = StartKind.AMBIENT,
        )
        assertNull(PhrasePlanner.select(a, b, 0, 120.0, 120.0))
    }

    @Test
    fun liftWithOneChorusIsRejected() {
        val a = song(
            "a", 120.0, 16,
            listOf(
                section(0, 8, SectionFn.PRE_CHORUS, SectionRole.TENSION, energy = 7),
                section(8, 16, SectionFn.CHORUS, SectionRole.RELEASE, energy = 8, vocal = 0.8f),
            ),
            ending = EndingKind.HARD_END,
            sungFrom = 8,
            sungUntil = 16,
        )
        val b = song(
            "b", 128.0, 16,
            listOf(section(0, 16, SectionFn.DROP, SectionRole.RELEASE, energy = 9)),
            start = StartKind.INTRO_BUILD,
        )
        val choice = PhrasePlanner.select(a, b, 1, 120.0, 128.0)
        if (choice != null) assertTrue(choice.template != MixTemplate.ENERGY_LIFT)
    }

    @Test
    fun fourteenPercentTempoGoesToEcho() {
        val a = song("a", 100.0, 16, listOf(section(0, 16, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5)))
        val b = song("b", 114.0, 16, listOf(section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 5)))
        val choice = assertNotNull(PhrasePlanner.select(a, b, 0, 100.0, 114.0))
        assertEquals(MixTemplate.M6, choice.template)
    }

    @Test
    fun theSameInputsProduceTheSamePlan() {
        val a = song("a", 120.0, 16, listOf(section(0, 16, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5)))
        val b = song("b", 120.0, 16, listOf(section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 6)))
        val first = assertNotNull(PhrasePlanner.select(a, b, 1, 120.0, 120.0))
        val second = assertNotNull(PhrasePlanner.select(a, b, 1, 120.0, 120.0))
        assertEquals(first.template, second.template)
        assertEquals(first.score, second.score, 0.0)
        assertEquals(first.overlapBars, second.overlapBars)
        assertEquals(first.swapBar, second.swapBar, 0.0)
        assertEquals(first.exit.releaseBar, second.exit.releaseBar)
        assertEquals(TransitionMoves.phraseLine(first), TransitionMoves.phraseLine(second))
    }

    @Test
    fun planLockKeepsTheTemplate() {
        val lock = PhrasePlanner.Lock()
        val early = blend()
        val other = early.copy(template = MixTemplate.M6, rung = "Echo", score = early.score - 0.1)
        val first = assertNotNull(lock.consider(0.0, 50.0, early))
        assertEquals(MixTemplate.M1, first.template)
        val held = assertNotNull(lock.consider(3.0, 47.0, other))
        assertEquals(MixTemplate.M1, held.template)
        val replanned = assertNotNull(lock.consider(10.0, 40.0, other))
        assertEquals(MixTemplate.M6, replanned.template)
        lock.consider(12.0, 20.0, replanned)
        assertTrue(lock.locked)
        val stuck = assertNotNull(lock.consider(13.0, 19.0, early))
        assertEquals(MixTemplate.M6, stuck.template)
        val slid = assertNotNull(lock.slide(early))
        assertEquals(MixTemplate.M6, slid.template)
        assertTrue(lock.slides >= 1)
    }

    private fun blend(): PhraseChoice {
        val a = song("a", 120.0, 16, listOf(section(0, 16, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5)))
        val b = song("b", 120.0, 16, listOf(section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 5)))
        return assertNotNull(PhrasePlanner.select(a, b, 0, 120.0, 120.0))
    }

    private fun analysis(bpm: Double, duration: Double): TrackAnalysis {
        val frames = (duration * LEVEL_FRAME_RATE).toInt()
        val values = FloatArray(frames) { levelFromRms(0.3).toFloat() }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = duration,
            bpm = bpm,
            beatInterval = 60 / bpm,
            beatConfidence = 0.8,
            downbeats = listOf(0.5),
            firstBeat = 0.5,
            levelWindows = listOf(CurveWindow(0.0, LEVEL_FRAME_RATE, values)),
        )
    }

    private fun section(
        start: Int,
        end: Int,
        fn: SectionFn,
        role: SectionRole,
        energy: Int,
        vocal: Float = 0f,
        loopable: Boolean = false,
    ) = PhraseSection(
        startBar = start,
        endBar = end,
        fn = fn,
        role = role,
        conf = 0.8f,
        group = 0,
        repeatIndex = 0,
        energy = energy / 10f,
        energyLevel = energy,
        energyRel = 0f,
        energySlope = 0f,
        vocal = vocal,
        bass = 0.4f,
        loopable = loopable,
    )

    private fun song(
        key: String,
        bpm: Double,
        bars: Int,
        sections: List<PhraseSection>,
        firstVocal: Int? = null,
        sungFrom: Int = bars,
        sungUntil: Int = bars,
        start: StartKind = StartKind.INTRO_INSTRUMENTAL,
        ending: EndingKind = EndingKind.OUTRO_INSTRUMENTAL,
        pickup: Float = 0f,
    ): SongMap {
        val beat = 60.0 / bpm
        val bar = beat * 4
        val sung = BooleanArray(bars) { it in sungFrom until sungUntil }
        return SongMap(
            trackKey = key,
            durationSec = bars * bar,
            bpm = bpm,
            beatsPerBar = 4,
            downbeats = DoubleArray(bars + 1) { it * bar },
            gridConfidence = 0.9f,
            tempoStable = true,
            phraseAnchor = 0,
            phraseLen = 8,
            sections = sections,
            sungBars = sung,
            firstVocalBar = firstVocal,
            lastVocalBar = if (sungFrom < sungUntil) sungUntil - 1 else null,
            pickupBeats = pickup,
            startKind = start,
            endingKind = ending,
            contentEndSec = bars * bar,
            labelConfidence = 0.8f,
            structMs = 1,
            chorusCount = sections.count { it.fn == SectionFn.CHORUS },
        )
    }
}
