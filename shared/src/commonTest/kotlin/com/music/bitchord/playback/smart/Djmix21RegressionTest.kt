package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * djmix21 (vc63) shared regression suite — structure-aware automix:
 *  1. vocal continuity (gap budget, mid-line guard, spotlight);
 *  2. buildup-aware blends (B-side buildup, flanger, quiet-intro guard);
 *  3. vocal separation tier (incoming vocal stays crisp, outgoing mid
 *     carves; vocal handoff seams pay; clash still guarded).
 * Plus the carried regression proofs from vc61/vc62.
 */
class Djmix21RegressionTest {

    // ---- fixture builders ----

    private fun section(
        start: Int, end: Int, fn: SectionFn, role: SectionRole = SectionRole.GROOVE,
        energy: Int = 4, vocal: Float = 0f, slope: Float = 0f, loopable: Boolean = false,
    ) = PhraseSection(
        startBar = start, endBar = end, fn = fn, role = role, conf = 0.85f,
        group = 0, repeatIndex = 0, energy = energy / 10f, energyLevel = energy,
        energyRel = 0f, energySlope = slope, vocal = vocal, bass = 0.4f, loopable = loopable,
    )

    private fun song(
        key: String, bpm: Double, bars: Int, sections: List<PhraseSection>,
        sungFrom: Int = bars, sungUntil: Int = bars,
        start: StartKind = StartKind.INTRO_INSTRUMENTAL,
        ending: EndingKind = EndingKind.OUTRO_INSTRUMENTAL,
        pickup: Float = 0f,
    ): SongMap {
        val beat = 60.0 / bpm
        val bar = beat * 4
        val sung = BooleanArray(bars) { it in sungFrom until sungUntil }
        return SongMap(
            trackKey = key, durationSec = bars * bar, bpm = bpm, beatsPerBar = 4,
            downbeats = DoubleArray(bars + 1) { it * bar },
            gridConfidence = 0.9f, tempoStable = true, phraseAnchor = 0, phraseLen = 8,
            sections = sections, sungBars = sung,
            firstVocalBar = sungFrom.takeIf { it < bars && sungUntil > sungFrom },
            lastVocalBar = (sungUntil - 1).takeIf { sungFrom < sungUntil },
            pickupBeats = pickup, startKind = start, endingKind = ending,
            contentEndSec = bars * bar, labelConfidence = 0.85f, structMs = 1,
            chorusCount = sections.count { it.fn == SectionFn.CHORUS },
        )
    }

    private fun track(
        id: String, bpm: Double, key: String = "C major", vocal: Double = 0.3,
        duration: Double = 180.0, contentEnd: Double? = null, audibleStart: Double = 0.5,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = List(((duration / (4 * beat)) + 1).toInt()) { it * 4 * beat + 0.5 }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id, duration = duration, bpm = bpm, beatInterval = beat,
            beatConfidence = 0.9, downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { i, _ -> i % 8 == 0 },
            firstBeat = 0.5, key = key, keyConfidence = 0.9,
            audibleStartTime = audibleStart, introEndTime = 10.0,
            contentEndTime = contentEnd ?: (duration - 2),
            energyCurve = List(duration.toInt()) { EnergySample(it.toDouble(), 1.0) },
            vocalActivityMask = List(duration.toInt()) { vocal },
            vocalProbability = vocal, quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    private fun plan(out: TrackAnalysis, inn: TrackAnalysis, at: Double = 10.0) = planTransition(
        analysis = out, nextAnalysis = inn,
        currentTrack = TransitionTrackInfo(out.trackId, (out.duration * 1000).toLong()),
        nextTrack = TransitionTrackInfo(inn.trackId, (inn.duration * 1000).toLong()),
        currentTime = at, duration = out.duration, mode = CrossfadeMode.SMART, advanced = true,
    )

    private fun sample(lane: Lane, beat: Double): Double {
        if (lane.beats.isEmpty()) return 0.0
        if (beat <= lane.beats.first()) return lane.values.first().toDouble()
        if (beat >= lane.beats.last()) return lane.values.last().toDouble()
        for (i in 1 until lane.beats.size) {
            if (beat <= lane.beats[i]) {
                val t0 = lane.beats[i - 1]; val t1 = lane.beats[i]
                val v0 = lane.values[i - 1].toDouble(); val v1 = lane.values[i].toDouble()
                val f = if (t1 > t0) (beat - t0) / (t1 - t0) else 0.0
                return v0 + (v1 - v0) * f
            }
        }
        return lane.values.last().toDouble()
    }

    private fun findIds(slot: Int, limit: Int = 500): Pair<String, String> {
        for (i in 0 until limit) {
            val a = "djmix21-out-$i"; val b = "djmix21-in"
            if (pairHash(a, b) % 4 == slot) return a to b
        }
        for (i in 0 until limit) {
            val a = "djmix21-out"; val b = "djmix21-in-$i"
            if (pairHash(a, b) % 4 == slot) return a to b
        }
        throw AssertionError("no pairHash slot $slot")
    }

    // ---- 1a vocal gap budget ----

    @Test
    fun longInstrumentalSeamTrimsOrShortens() {
        // A: verse sung bars 2..11, then a long instrumental outro 12..31.
        // B: quiet intro bars 0..23, first vocal (chorus) at bar 24.
        // Natural seam gap = long trail + long lead >> 16 bars.
        val out = song(
            "a", 120.0, 32,
            listOf(
                section(0, 2, SectionFn.INTRO),
                section(2, 12, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 6, vocal = 0.7f),
                section(12, 32, SectionFn.OUTRO, energy = 6, loopable = true),
            ),
            sungFrom = 2, sungUntil = 12,
        )
        val inn = song(
            "b", 120.0, 48,
            listOf(
                section(0, 24, SectionFn.INTRO, energy = 2),
                section(24, 40, SectionFn.CHORUS, SectionRole.RELEASE, energy = 7, vocal = 0.8f),
                section(40, 48, SectionFn.OUTRO, energy = 3),
            ),
            sungFrom = 24, sungUntil = 40,
        )
        val exits = CuePoints.exits(out, analysis = null)
        val entries = CuePoints.entries(inn)
        val exit = exits.last()
        val entry = entries.first()
        val overlap = 16
        val gap = StructureMix.vocalGap(out, inn, exit, entry, overlap)
        assertTrue(gap.totalBars > PhraseConfig.VOCAL_GAP_HARD_BARS, "fixture gap must exceed hard budget: $gap")

        // Key distance 4 costs long blends their staying power (carve rungs
        // keep a 0.6 floor), so a short rung must win across this seam.
        val choice = PhrasePlanner.select(out, inn, camelotSteps = 4, outBpm = 120.0, inBpm = 120.0)
        assertNotNull(choice, "planner must still pick something: ${PhrasePlanner.lastVetoSummary}")
        val after = StructureMix.vocalGap(out, inn, choice.exit, choice.entry, choice.overlapBars)
        assertTrue(choice.overlapBars <= 4 || after.totalBars <= PhraseConfig.VOCAL_GAP_SOFT_BARS,
            "long blend across huge gap must be trimmed/shortened: template=${choice.template} overlap=${choice.overlapBars} gap=$after")
    }

    // ---- 1b no mid-vocal cut (classic path: planner, not just annotate) ----

    @Test
    fun exitNeverCutsSungLineMidway() {
        val cur = track("a", 120.0, vocal = 0.05)
        val next = track("b", 120.0, vocal = 0.05)
        // Long sung line 140..160; exit at 145 would amputate it. The rule must
        // move the exit BEFORE the line (line end is >6s away), never mid-line.
        val lyrics = listOf(LyricCue(140.0, 160.0, "one long sung line keeps going"))
        val raw = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 138.0, transitionEnd = 145.0, fadeSeconds = 7.0,
            incomingCueTime = 10.0, beatSeconds = 0.5, vocalOverlap = 0.0,
        )
        val far = DjMix.annotate(raw, cur, next, lyrics, emptyList(), "out", "in")
        assertFalse(far.transitionEnd > 140.4 && far.transitionEnd < 159.6,
            "transitionEnd=${far.transitionEnd} left mid-line (140..160)")
        assertTrue(far.transitionEnd <= 140.1 || far.transitionEnd >= 159.5,
            "transitionEnd=${far.transitionEnd} must be at/after line end or before line start")
        // Short line: extend to its end (within the 6s grace).
        val short = listOf(LyricCue(140.0, 148.0, "verse one stays"), LyricCue(148.0, 156.0, "verse one continues"))
        val raw2 = raw.copy(transitionEnd = 145.0)
        val near = DjMix.annotate(raw2, cur, next, short, emptyList(), "out", "in")
        assertTrue(near.transitionEnd >= 147.5, "exit ${near.transitionEnd} still cuts the verse")
    }

    // ---- 2a/2b buildup-aware blends + flanger ----

    @Test
    fun quietBuildupIntroAlignsDropAtHandover() {
        // B: quiet intro, then a rising BUILD into the chorus. The BUILD entry
        // cue must win and land the peak at handover.
        val out = song(
            "a", 120.0, 32,
            listOf(
                section(0, 4, SectionFn.INTRO),
                section(4, 28, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 6, vocal = 0.7f),
                section(28, 32, SectionFn.OUTRO, energy = 7, loopable = true),
            ),
            sungFrom = 4, sungUntil = 28,
        )
        val inn = song(
            "b", 120.0, 48,
            listOf(
                section(0, 4, SectionFn.INTRO, energy = 2),
                section(4, 12, SectionFn.BUILD, SectionRole.TENSION, energy = 4, slope = 0.25f),
                section(12, 24, SectionFn.CHORUS, SectionRole.RELEASE, energy = 8, vocal = 0.8f),
                section(24, 36, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 6, vocal = 0.7f),
                section(36, 48, SectionFn.OUTRO, energy = 3),
            ),
            sungFrom = 12, sungUntil = 36,
        )
        val buildup = StructureMix.headBuildup(inn)
        assertNotNull(buildup)
        assertEquals(4, buildup.startBar, "buildup must be the head BUILD section")
        val entries = CuePoints.entries(inn)
        assertTrue(entries.any { it.kind == EntryKind.BUILD && it.startBar == 4 && it.landingBar == 12 },
            "BUILD entry must start at buildup start and land at its peak; entries=$entries")

        val choice = PhrasePlanner.select(out, inn, camelotSteps = 0, outBpm = 120.0, inBpm = 120.0)
        assertNotNull(choice, "planner must choose: ${PhrasePlanner.lastVetoSummary}")
        // Behaviour, not label: the cue must start at or before the buildup
        // start so the whole buildup plays under the outro (HEAD at bar 0
        // covers it too — the BUILD label is not mandatory).
        assertTrue(choice.entry.startBar <= 4, "cue must start at/before buildup start (bar 4); choice=${choice.template} ${choice.entry}")
        // Peak lands at handover (within 1 bar after the buildup end).
        assertTrue(choice.entry.landingBar in 12..13, "landing=${choice.entry.landingBar} must be the buildup peak")

        // Classic annotate path: B-side rise across the cue window is a buildup.
        val (outId, inId) = findIds(0)
        val curve = List(180) { i ->
            val t = i.toDouble()
            EnergySample(t, if (t < 50.0) 0.3 + (t - 34.0).coerceIn(0.0, 16.0) * 0.04 else 0.95)
        }
        val cur = track("a", 120.0, vocal = 0.05).copy(energyCurve = List(180) { EnergySample(it.toDouble(), 0.9) })
        val next = track("b", 120.0, vocal = 0.05).copy(
            energyCurve = curve,
            vocalActivityMask = List(180) { 0.05 },
        )
        // Chorus-only lyrics: no VERSE section at the head, so the pre-chorus
        // at 34 is the first lifted section and the verse pullback stays out.
        val lyricsIn = listOf(
            LyricCue(40.0, 48.0, "chorus hook"),
            LyricCue(80.0, 88.0, "chorus hook"),
        )
        val raw = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 140.0, transitionEnd = 156.0, fadeSeconds = 16.0,
            incomingCueTime = 34.0, bassSwap = true, beatSeconds = 0.5, vocalOverlap = 0.0,
        )
        val ann = DjMix.annotate(raw, cur, next, emptyList(), lyricsIn, outId, inId)
        assertTrue(ann.buildup, "cue inside B buildup/pre-chorus with rising energy must be buildup; summary=${ann.mixSummary}")
        assertEquals(DjEffect.FLANGER, ann.djEffect, "buildup blend rides the flanger; effect=${ann.djEffect}")
        assertEquals(0.42f, effectSettings(DjEffect.FLANGER).wet, 1e-6f)
        assertEquals(8f, effectSettings(DjEffect.FLANGER).beats, 1e-6f)
    }

    @Test
    fun quietIntroWithNoEventIsNotLongBlended() {
        // B: long quiet intro, first event (chorus vocal) far beyond any overlap.
        val out = song(
            "a", 120.0, 32,
            listOf(
                section(0, 4, SectionFn.INTRO),
                section(4, 28, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 6, vocal = 0.7f),
                section(28, 32, SectionFn.OUTRO, energy = 6, loopable = true),
            ),
            sungFrom = 4, sungUntil = 28,
        )
        val inn = song(
            "b", 120.0, 56,
            listOf(
                section(0, 40, SectionFn.INTRO, energy = 2),
                section(40, 52, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 6, vocal = 0.7f),
                section(52, 56, SectionFn.OUTRO, energy = 3),
            ),
            sungFrom = 40, sungUntil = 52,
        )
        // Key distance 4: quiet-intro long blends decay fastest.
        val choice = PhrasePlanner.select(out, inn, camelotSteps = 4, outBpm = 120.0, inBpm = 120.0)
        assertNotNull(choice, "planner must still pick a short rung or trimmed blend: ${PhrasePlanner.lastVetoSummary}")
        assertTrue(choice.overlapBars <= 4,
            "quiet intro with no event in overlap must not be long-blended: template=${choice.template} overlap=${choice.overlapBars}")
    }

    // ---- Addendum 2: incoming vocal stays crisp ----

    @Test
    fun incomingVocalStaysCrispWhileOutgoingMidCarves() {
        val exit = ExitCue(ExitKind.OUTRO_PHRASE, 72, 170.0, 8, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE, 0.4f, 4, true, 0, 0)
        val vocalEntry = EntryCue(EntryKind.HEAD, 0, 0.0, 8, SectionFn.INTRO, SectionRole.GROOVE, 8, 0.6f, true, 0.4f, 4, 0, 0f)
        val choice = PhraseChoice(
            template = MixTemplate.M1, rung = "Beat blend", score = 0.9, exit = exit, entry = vocalEntry,
            overlapBars = 8, swapBar = -4.0, tailBeats = 1.0, loopK = 0, echoGated = false,
            vocalProduct = 0f, tempoPrepBars = 0, echoDelayBeats = 0.75, echoDelayName = "ECHO_DELAY_BEATS",
            swapRampMs = 120, swapRampName = "SWAP_RAMP_BLEND_MS", tempoPolicy = "TEMPO_POLICY=b",
            vocalOverlapBeats = 0.0, energyDelta = 0f, struct = 0.9, vocalScore = 0.9, phraseScore = 0.9,
            energyScore = 0.9, lengthScore = 0.9, keyScore = 0.9, tempoScore = 0.9, cost = 0.0,
        )
        val script = TransitionMoves.compile(choice, 120.0, 120.0)
        // At the landing (beat 0) and mid-overlap the incoming mid/high must
        // be at unity (>= -1 dB): the vocal arrives crisp, not muffled.
        assertTrue(sample(script.inMid, 0.0) >= -1.0, "inMid@0=${sample(script.inMid, 0.0)}")
        assertTrue(sample(script.inMid, -8.0) >= -1.0, "inMid@-8=${sample(script.inMid, -8.0)}")
        assertTrue(sample(script.inHigh, 0.0) >= -1.0, "inHigh@0=${sample(script.inHigh, 0.0)}")
        assertTrue(sample(script.inHigh, -16.0) >= -1.0, "inHigh@-16=${sample(script.inHigh, -16.0)}")
        // The separation happens on the outgoing mid (vocal band), which is
        // carved below the incoming mid during the overlap.
        val outMid = sample(script.outMid, -8.0)
        val inMid = sample(script.inMid, -8.0)
        assertTrue(outMid < inMid, "outMid@-8=$outMid must sit below inMid@-8=$inMid")
        assertTrue(sample(script.outMid, 0.0) <= -5.0, "outMid@0=${sample(script.outMid, 0.0)} must be carved at landing")
    }

    @Test
    fun dropMixKeepsIncomingMidCrispWhenSinging() {
        val exit = ExitCue(ExitKind.OUTRO_PHRASE, 72, 170.0, 8, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE, 0.4f, 4, true, 0, 0)
        val vocalEntry = EntryCue(EntryKind.HEAD, 0, 0.0, 16, SectionFn.INTRO, SectionRole.GROOVE, 16, 0.6f, true, 0.4f, 4, 0, 0f)
        val choice = PhraseChoice(
            template = MixTemplate.M7, rung = "Drop mix", score = 0.9, exit = exit, entry = vocalEntry,
            overlapBars = 16, swapBar = 0.0, tailBeats = 0.0, loopK = 0, echoGated = false,
            vocalProduct = 0f, tempoPrepBars = 0, echoDelayBeats = 0.75, echoDelayName = "ECHO_DELAY_BEATS",
            swapRampMs = 40, swapRampName = "SWAP_RAMP_MS", tempoPolicy = "TEMPO_POLICY=b",
            vocalOverlapBeats = 0.0, energyDelta = 0f, struct = 0.9, vocalScore = 0.9, phraseScore = 0.9,
            energyScore = 0.9, lengthScore = 0.9, keyScore = 0.9, tempoScore = 0.9, cost = 0.0,
        )
        val script = TransitionMoves.compile(choice, 120.0, 120.0)
        // Was -8 dB two bars before landing; a singing incoming mid must be
        // near unity, so the first buildup line is not buried.
        assertTrue(sample(script.inMid, -8.0) >= -2.0, "inMid@-8=${sample(script.inMid, -8.0)}")
        assertTrue(sample(script.inMid, 0.0) >= -1.0, "inMid@0=${sample(script.inMid, 0.0)}")
    }

    @Test
    fun instrumentalLanesUnaffected() {
        val exit = ExitCue(ExitKind.OUTRO_PHRASE, 72, 170.0, 8, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE, 0.4f, 4, true, 0, 0)
        val silentEntry = EntryCue(EntryKind.HEAD, 0, 0.0, 8, SectionFn.INTRO, SectionRole.GROOVE, 8, 0f, true, 0.4f, 4, 0, 0f)
        val choice = PhraseChoice(
            template = MixTemplate.M1, rung = "Beat blend", score = 0.9, exit = exit, entry = silentEntry,
            overlapBars = 8, swapBar = -4.0, tailBeats = 1.0, loopK = 0, echoGated = false,
            vocalProduct = 0f, tempoPrepBars = 0, echoDelayBeats = 0.75, echoDelayName = "ECHO_DELAY_BEATS",
            swapRampMs = 120, swapRampName = "SWAP_RAMP_BLEND_MS", tempoPolicy = "TEMPO_POLICY=b",
            vocalOverlapBeats = 0.0, energyDelta = 0f, struct = 0.9, vocalScore = 0.9, phraseScore = 0.9,
            energyScore = 0.9, lengthScore = 0.9, keyScore = 0.9, tempoScore = 0.9, cost = 0.0,
        )
        val script = TransitionMoves.compile(choice, 120.0, 120.0)
        assertTrue(sample(script.outMid, -8.0) >= -0.01, "instrumental outMid must stay flat: ${sample(script.outMid, -8.0)}")
        assertTrue(sample(script.inHigh, -32.0) <= -2.4, "instrumental inHigh keeps its -2.5 start: ${sample(script.inHigh, -32.0)}")
    }

    // ---- Addendum 2 item 3: vocal handoff matching ----

    @Test
    fun vocalHandoffSeamBeatsInstrumentalGap() {
        // A sung until bar 21, outro release near bar 24: short trail, and B's
        // first vocal lands just after — the conversation seam.
        val out = song(
            "a", 120.0, 32,
            listOf(
                section(0, 4, SectionFn.INTRO),
                section(4, 22, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 6, vocal = 0.7f),
                section(22, 32, SectionFn.OUTRO, energy = 6, loopable = true),
            ),
            sungFrom = 4, sungUntil = 22,
        )
        // B's first sung bar lands 1-2 bars after A's last sung bar (21):
        // seam = 23 - 22 = 1. Same B but with first vocal far away = no seam.
        val seamInn = song(
            "b", 120.0, 48,
            listOf(
                section(0, 23, SectionFn.INTRO, energy = 3),
                section(23, 40, SectionFn.CHORUS, SectionRole.RELEASE, energy = 8, vocal = 0.8f),
                section(40, 48, SectionFn.OUTRO, energy = 3),
            ),
            sungFrom = 23, sungUntil = 40,
        )
        val farInn = song(
            "b", 120.0, 48,
            listOf(
                section(0, 28, SectionFn.INTRO, energy = 3),
                section(28, 40, SectionFn.CHORUS, SectionRole.RELEASE, energy = 8, vocal = 0.8f),
                section(40, 48, SectionFn.OUTRO, energy = 3),
            ),
            sungFrom = 28, sungUntil = 40,
        )
        val seamChoice = PhrasePlanner.select(out, seamInn, camelotSteps = 0, outBpm = 120.0, inBpm = 120.0)
        val farChoice = PhrasePlanner.select(out, farInn, camelotSteps = 0, outBpm = 120.0, inBpm = 120.0)
        assertNotNull(seamChoice, "seam pair must choose: ${PhrasePlanner.lastVetoSummary}")
        // The far pair must fall to a short rung (or a much worse score).
        if (farChoice != null) {
            val long = farChoice.template in setOf(
                MixTemplate.M1, MixTemplate.M3, MixTemplate.ROLLING, MixTemplate.M2,
                MixTemplate.M7, MixTemplate.RELAXED, MixTemplate.M9,
            )
            assertTrue(!long || farChoice.score < seamChoice.score,
                "far-gap pair must not outscore the vocal seam: far=${farChoice.template} ${farChoice.score} seam=${seamChoice.template} ${seamChoice.score}")
        }
        // Seam math: B's first sung bar follows A's last sung bar with a 1-2
        // bar breath for the aligned candidate.
        val exits = CuePoints.exits(out)
        val entries = CuePoints.entries(seamInn)
        val seams = exits.flatMap { e -> entries.mapNotNull { en -> StructureMix.vocalSeamBars(out, seamInn, e, en) } }
        assertTrue(seams.any { it in 1..2 }, "aligned seam must exist among candidates: $seams")
    }

    @Test
    fun vocalOnVocalOverlapIsStillNotCrispStacked() {
        // A sung right up to its release and B sung from its entry cue: the
        // clash veto must keep non-carve blends from running.
        val out = song(
            "a", 120.0, 32,
            listOf(
                section(0, 4, SectionFn.INTRO),
                section(4, 30, SectionFn.VERSE, SectionRole.NARRATIVE, energy = 6, vocal = 0.7f),
                section(30, 32, SectionFn.OUTRO, energy = 3, loopable = true),
            ),
            sungFrom = 4, sungUntil = 30,
        )
        val inn = song(
            "b", 120.0, 48,
            listOf(
                section(0, 8, SectionFn.CHORUS, SectionRole.RELEASE, energy = 8, vocal = 0.9f),
                section(8, 48, SectionFn.OUTRO, energy = 4),
            ),
            sungFrom = 0, sungUntil = 8,
        )
        val choice = PhrasePlanner.select(out, inn, camelotSteps = 0, outBpm = 120.0, inBpm = 120.0)
        val carves = setOf(MixTemplate.M4, MixTemplate.M5, MixTemplate.ROLLING, MixTemplate.VOCAL_OVER_OUTRO,
            MixTemplate.M6, MixTemplate.M10, MixTemplate.CUT_ON_ONE, MixTemplate.M11, MixTemplate.RELAXED)
        assertTrue(choice == null || choice.template in carves || choice.vocalOverlapBeats <= 1.0,
            "dual-vocal seam must be carved/short, not a crisp stack: choice=${choice?.template} overlap=${choice?.vocalOverlapBeats}")
    }

    // ---- carried regressions vc61/vc62 ----

    private fun beautifulAnalysis(): TrackAnalysis {
        val bpm = 99.93
        val beat = 60.0 / bpm
        val bar = beat * 4.0
        val duration = 178.0
        val contentEnd = 175.5
        val downbeats = List(75) { it * bar }
        val energyCurve = List(178) { i ->
            val t = i.toDouble()
            val e = if (t > contentEnd) 0.02 else if (t >= 156.0) 0.25 else 0.9
            EnergySample(t, e)
        }
        val vocalMask = List(178) { i ->
            val t = i.toDouble()
            when {
                t in 12.0..155.0 -> 0.55
                t in 156.0..175.0 -> 0.35
                else -> 0.02
            }
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, trackId = "beautiful", duration = duration,
            bpm = bpm, beatInterval = beat, beatConfidence = 0.92, downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { i, _ -> i % 4 == 0 },
            key = "8B", keyConfidence = 0.85, audibleStartTime = 0.2, introEndTime = 10.0,
            contentEndTime = contentEnd, outroStartTime = 156.0, mixOutTime = 156.0,
            energyCurve = energyCurve, vocalActivityMask = vocalMask, vocalProbability = 0.8,
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    private fun neverLetGoAnalysis(): TrackAnalysis {
        val bpm = 104.50
        val beat = 60.0 / bpm
        val bar = beat * 4.0
        val duration = 193.6
        val downbeats = List(84) { it * bar }
        val energyCurve = List(194) { i ->
            val t = i.toDouble()
            EnergySample(t, if (t < 18.37) 0.4 else 0.95)
        }
        val vocalMask = List(194) { i -> if (i < 18.37) 0.02 else 0.65 }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, trackId = "never-let-go", duration = duration,
            bpm = bpm, beatInterval = beat, beatConfidence = 0.94, downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { i, _ -> i % 4 == 0 },
            key = "8B", keyConfidence = 0.88, audibleStartTime = 0.0, introEndTime = 18.37,
            contentEndTime = 191.0, mixInTime = 18.37,
            mixInCandidates = listOf(MixCandidate(time = 18.37, score = 0.92, type = "intro_drop")),
            energyCurve = energyCurve, vocalActivityMask = vocalMask, vocalProbability = 0.75,
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    @Test
    fun beautifulToNeverLetGoStillEightBarBlend() {
        val out = beautifulAnalysis()
        val inn = neverLetGoAnalysis()
        val p = plan(out, inn, at = 100.0)
        assertEquals(TransitionStyle.DJ_BLEND, p.transitionStyle, "reason=${p.reason}")
        val bars = round(p.fadeSeconds / (4.0 * p.beatSeconds)).toInt()
        assertEquals(8, bars, "fadeSeconds=${p.fadeSeconds} beatSeconds=${p.beatSeconds}")
        assertTrue(p.transitionEnd >= 172.0 && p.transitionEnd <= 175.6, "transitionEnd=${p.transitionEnd}")
    }

    @Test
    fun calvinPair124StillBeatmixes() {
        val out = track("this-is-what-you-came-for", 124.0, "C major", vocal = 0.3)
        val inn = track("one-kiss", 124.0, "C major", vocal = 0.3)
        assertFalse(shouldDeclineToMix(out, inn))
        assertEquals(TransitionStyle.DJ_BLEND, plan(out, inn).transitionStyle)
    }

    @Test
    fun technologicToGuessStillShort() {
        val out = track("technologic", 124.0, "C major", vocal = 0.8)
        val inn = track("guess", 130.0, "C major", vocal = 0.8)
        assertFalse(shouldDeclineToMix(out, inn))
        val p = plan(out, inn)
        assertFalse(p.reason.contains("decline"), "reason=${p.reason}")
    }

    @Test
    fun declineGateStillFires() {
        val out = track("a", 120.0, "C major", vocal = 0.9)
        val inn = track("b", 94.0, "F\u266F major", vocal = 0.9)
        assertTrue(shouldDeclineToMix(out, inn))
        val declined = plan(out, inn)
        assertEquals(TransitionStyle.DJ_FILTER, declined.transitionStyle)
        assertTrue(declined.fadeSeconds in 3.5..4.5, "fade=${declined.fadeSeconds}")
    }

    @Test
    fun buildupExtensionCoversBuildAndDrop() {
        assertTrue(buildupFits(TransitionStyle.DJ_BLEND, 0.1, false, SectionKind.INST, SectionKind.BUILD, 1.2),
            "BUILD as incoming section must count as a lift")
        assertTrue(buildupFits(TransitionStyle.DJ_BLEND, 0.1, false, SectionKind.INST, SectionKind.DROP, 1.2),
            "DROP as incoming section must count as a lift")
        assertFalse(buildupFits(TransitionStyle.DJ_FILTER, 0.1, false, SectionKind.INST, SectionKind.BUILD, 1.2),
            "non-blend styles never build")
    }
}
