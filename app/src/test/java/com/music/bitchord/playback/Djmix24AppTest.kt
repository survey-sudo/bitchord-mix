package com.music.bitchord.playback

import com.music.bitchord.playback.smart.EntryCue
import com.music.bitchord.playback.smart.EntryKind
import com.music.bitchord.playback.smart.ExitCue
import com.music.bitchord.playback.smart.ExitKind
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.PhraseChoice
import com.music.bitchord.playback.smart.SectionFn
import com.music.bitchord.playback.smart.SectionRole
import com.music.bitchord.playback.smart.TransitionMoves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * djmix24 `:app` suite (vc66).
 *
 * Bug A1 regression: loopPlanFor (LoopMix.plan) and nativeBridge
 * (LoopMix.bridge) both clear loopScript = null, so LoopMixSession.build
 * previously saw script = null and never armed the flanger/buildup. The
 * Plan scalar carrier (flanger / buildupRoll / reverbWash / hpSweepEndHz)
 * now carries the deterministic FX on BOTH paths, and LoopMixSession.build
 * honours effectivePlan even when script is null. These tests pin the
 * scalars on both paths plus the vc65 session invariants.
 */
class Djmix24AppTest {

    private fun exitCue(room: Int = 8) = ExitCue(
        kind = ExitKind.OUTRO_PHRASE,
        releaseBar = 72,
        releaseSec = 170.0,
        roomBars = room,
        vocalRoomBars = 0,
        tailBars = 0,
        sectionFn = SectionFn.OUTRO,
        role = SectionRole.GROOVE,
        energyAtRelease = 0.4f,
        energyLevel = 4,
        loopableRoom = room >= 4,
        cutsSungBars = 0,
        cutsBars = 0,
    )

    private fun entryCue() = EntryCue(
        kind = EntryKind.HEAD,
        startBar = 0,
        startSec = 0.0,
        landingBar = 8,
        landingFn = SectionFn.INTRO,
        landingRole = SectionRole.GROOVE,
        leadInBars = 8,
        leadInVocal = 0f,
        leadInLoopable = true,
        energyAtLanding = 0.4f,
        energyLevel = 4,
        trimmedBars = 0,
        pickupBeats = 0f,
    )

    private fun choice(vocalOverlap: Double = 0.0): PhraseChoice = PhraseChoice(
        template = MixTemplate.M1,
        rung = "Phrase",
        score = 0.85,
        exit = exitCue(),
        entry = entryCue(),
        overlapBars = 8,
        swapBar = 0.0,
        tailBeats = 0.0,
        loopK = 0,
        echoGated = false,
        vocalProduct = 0f,
        tempoPrepBars = 0,
        echoDelayBeats = 0.75,
        echoDelayName = "ECHO_DELAY_BEATS",
        swapRampMs = 120,
        swapRampName = "SWAP_RAMP_BLEND_MS",
        tempoPolicy = "TEMPO_POLICY=b",
        vocalOverlapBeats = vocalOverlap,
        energyDelta = 0f,
        struct = 0.9,
        vocalScore = 0.9,
        phraseScore = 0.9,
        energyScore = 0.9,
        lengthScore = 0.9,
        keyScore = 0.9,
        tempoScore = 0.9,
        cost = 0.0,
    )

    // ---- Bug A1: FX carrier on loopPlanFor path (LoopMix.plan) ----

    @Test
    fun loopPlanPathCarriesNonNullFxOnEdmPair() {
        val out = track("out", 124.34)
        val inn = track("in", 124.25)
        val clear = emptyList<LoopMix.Span>()
        val plan = LoopMix.plan(out, out.duration, inn, inn.duration, 0.0, clear, clear)
        assertNotNull(plan)
        // Non-null FX/buildup configuration (Bug A1 scalar carrier).
        assertTrue("loopPlanFor EDM must carry flanger=true (Bug A1)", plan!!.flanger)
        assertTrue("loopPlanFor EDM must carry buildupRoll=true", plan.buildupRoll)
        assertTrue("hpSweep 420..600Hz, got ${plan.hpSweepEndHz}", plan.hpSweepEndHz in 420f..600f)
        assertTrue("EDM drop carries reverb release", plan.reverbWash)
    }

    @Test
    fun bridgePathCarriesNonNullFxOnEdmPair() {
        val out = track("out", 124.34)
        val inn = track("in", 124.25)
        val clear = emptyList<LoopMix.Span>()
        val plan = LoopMix.bridge(out, out.duration, inn, inn.duration, 0.0, clear, clear)
        assertNotNull(plan)
        assertTrue("bridge EDM must carry flanger=true (Bug A1)", plan!!.flanger)
        // Bridge blend on EDM also carries the roll + sweep.
        assertTrue("bridge EDM must carry buildupRoll", plan.buildupRoll)
    }

    @Test
    fun dualVocalBridgeNeverFlanges() {
        // Phrase path: vocalOverlapBeats >= 1.0 must never flange/roll.
        val script = TransitionMoves.compile(choice(vocalOverlap = 2.0), outBpm = 124.34, inBpm = 124.25)
        assertFalse(script.plan.flanger)
        assertFalse(script.plan.buildupRoll)
        assertFalse(script.flanger)
        // Bridge path: clean instrumental loops (even on vocal tracks)
        // DO flange (loops are instrumental); sung loops must not.
        // Clean loops (lyrics-vouched, no mask) -> EDM flanges.
        val cleanOut = track("out", 124.34)
        val cleanIn = track("in", 124.25)
        val clear = emptyList<LoopMix.Span>()
        val cleanPlan = LoopMix.bridge(cleanOut, cleanOut.duration, cleanIn, cleanIn.duration, 0.0, clear, clear)
        assertNotNull(cleanPlan)
        assertTrue("clean-loop EDM bridge must flange", cleanPlan!!.flanger)
        // Sung loops (mask calls the bar sung) -> bridge echoes or is null,
        // never flanges over sung bars.
        val sungOut = trackSung("out", 124.34)
        val sungIn = trackSung("in", 124.25)
        val sungPlan = LoopMix.bridge(sungOut, sungOut.duration, sungIn, sungIn.duration, 0.0, null, null)
        if (sungPlan != null) {
            assertFalse("sung-loop bridge must never flange", sungPlan.flanger)
            assertFalse("sung-loop bridge must never roll", sungPlan.buildupRoll)
        }
    }

    // ---- vc65 preserved invariants ----

    @Test
    fun canAdoptSessionPlayerPreserved() {
        assertFalse(canAdoptSessionPlayer(false))
        assertTrue(canAdoptSessionPlayer(true))
    }

    // ---- Rung 0 consecutive-album guard ----

    @Test
    fun consecutiveAlbumPrefersGapless() {
        assertTrue(isConsecutiveAlbumTrack("Album", "id", 3, "Album", "id", 4))
        assertFalse(isConsecutiveAlbumTrack("Album", "id", 3, "Album", "id", 5))
        assertFalse(isConsecutiveAlbumTrack("", "", 3, "", "", 4))
        assertFalse(isConsecutiveAlbumTrack("Album", "id", 0, "Album", "id", 1))
        assertFalse(isConsecutiveAlbumTrack("A", "id1", 3, "A", "id2", 4))
    }

    // ---- FX rotation varied, deterministic, anti-repeat by pair hash ----

    @Test
    fun fxRotationVariedAndDeterministic() {
        val slots = (0 until 100).map { TransitionMoves.fxSlot("track$it", "track${it + 1}") }.toSet()
        assertTrue("rotation must vary", slots.size >= 3)
        assertEquals(TransitionMoves.fxSlot("a", "b"), TransitionMoves.fxSlot("a", "b"))
        // Anti-repeat: consecutive queue pairs (a->b, b->c) rarely repeat the
        // same slot twice in a row when the pairs differ.
        var repeats = 0
        var total = 0
        for (i in 0 until 100) {
            total++
            if (TransitionMoves.fxSlot("t$i", "t${i + 1}") == TransitionMoves.fxSlot("t${i + 1}", "t${i + 2}")) repeats++
        }
        // Random hash repeats ~25%; the cap proves it is not a constant effect.
        assertTrue("rotation must not be constant (repeats=$repeats/$total)", repeats < total * 0.6)
    }

    private fun trackSung(id: String, bpm: Double): com.music.bitchord.playback.smart.TrackAnalysis {
        val base = track(id, bpm, vocal = 0.9)
        val fps = 10.0
        val sung = com.music.bitchord.playback.smart.CurveWindow(0.0, fps, FloatArray((180.0 * fps).toInt()) { 0.9f })
        return base.copy(vocalWindows = listOf(sung))
    }

    private fun track(id: String, bpm: Double, vocal: Double = 0.3): com.music.bitchord.playback.smart.TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = List(((180.0 / (4 * beat)) + 1).toInt()) { it * 4 * beat + 0.5 }
        return com.music.bitchord.playback.smart.TrackAnalysis(
            status = com.music.bitchord.playback.smart.TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = 180.0,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            firstBeat = 0.5,
            key = "C major",
            keyConfidence = 0.9,
            energyCurve = List(180) { com.music.bitchord.playback.smart.EnergySample(it.toDouble(), 1.0) },
            vocalProbability = vocal,
            quality = com.music.bitchord.playback.smart.TrackAnalysis.QUALITY_FULL,
            levelWindows = listOf(
                com.music.bitchord.playback.smart.CurveWindow(
                    0.0,
                    com.music.bitchord.playback.smart.LEVEL_FRAME_RATE,
                    FloatArray((180.0 * com.music.bitchord.playback.smart.LEVEL_FRAME_RATE).toInt()) {
                        com.music.bitchord.playback.smart.levelFromRms(0.3).toFloat()
                    },
                ),
            ),
        )
    }
}
