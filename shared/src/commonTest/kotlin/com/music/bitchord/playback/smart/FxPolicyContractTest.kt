package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FxPolicyContractTest {
    private fun pairForSlot(targetSlot: Int): Pair<String, String> {
        for (i in 0 until 200) {
            val a = "fx-out-$i"
            val b = "fx-in"
            if (FxPolicy.fxSlot(a, b) == targetSlot) return a to b
        }
        error("no pair for slot $targetSlot")
    }

    @Test
    fun fxSlotDelegatesToPairHash() {
        for ((a, b) in listOf("a" to "b", "out" to "fx-0", "x" to "y")) {
            assertEquals(((pairHash(a, b) % 4) + 4) % 4, FxPolicy.fxSlot(a, b))
        }
    }

    @Test
    fun ineligibleOrUnmeasuredPairsDefaultToClean() {
        val (a0, b0) = pairForSlot(0)
        assertEquals(DjEffect.NONE, FxPolicy.select(a0, b0, rhythmic = true, measured = false, vocalClash = 0.0, speechLike = false, drifting = false, buildup = true, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.select(a0, b0, rhythmic = true, measured = true, vocalClash = 0.0, speechLike = true, drifting = false, buildup = true, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.select(a0, b0, rhythmic = true, measured = true, vocalClash = 0.25, speechLike = false, drifting = false, buildup = true, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.select(a0, b0, rhythmic = true, measured = true, vocalClash = Double.NaN, speechLike = false, drifting = false, buildup = false, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.select(a0, b0, rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = true, buildup = false, cleanSpace = false))
        assertEquals(DjEffect.NONE, blendColour(0.0, a0, b0))
    }

    @Test
    fun eligibleBuildupAndCleanBallotRespectSlotPolicy() {
        val (a0, b0) = pairForSlot(0)
        val (a1, b1) = pairForSlot(1)
        val (a2, b2) = pairForSlot(2)

        assertEquals(DjEffect.FLANGER, FxPolicy.select(a0, b0, rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = true, cleanSpace = false))
        assertEquals(DjEffect.LOOP_ROLL, FxPolicy.select(a1, b1, rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = true, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.select(a2, b2, rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = true, cleanSpace = false))

        // Clean instrumental DJ_BLEND ballot (not wholesale starvation): FLANGER and ECHO both survive.
        assertEquals(DjEffect.FLANGER, FxPolicy.select(a0, b0, rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = false))
        assertEquals(DjEffect.FLANGER, FxPolicy.select(a1, b1, rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = false))
        assertEquals(DjEffect.ECHO, FxPolicy.select(a2, b2, rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = false))

        // Tempo-gap cleanSpace lottery: ECHO 2/3 vs REVERB 1/3 via pairHash % 3.
        val echoId = (0 until 64).first { ((pairHash("a", "id-$it") % 3) + 3) % 3 == 0 }
        val reverbId = (0 until 64).first { ((pairHash("a", "id-$it") % 3) + 3) % 3 == 2 }
        assertEquals(DjEffect.ECHO, FxPolicy.select("a", "id-$echoId", rhythmic = false, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = true))
        assertEquals(DjEffect.REVERB, FxPolicy.select("a", "id-$reverbId", rhythmic = false, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = true))
    }

    @Test
    fun measuredVocalRequiresSeamCoverage() {
        val energy = List(90) { EnergySample(it * 2.0, 1.0) }
        val mask = List(90) { 0.05 }
        val covered = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = 180.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            contentEndTime = 176.0,
            energyCurve = energy,
            vocalActivityMask = mask,
        )
        assertNotNull(FxPolicy.measuredVocal(covered, 160.0, 176.0))
        val uncovered = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = 180.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            contentEndTime = 176.0,
            energyCurve = energy,
            vocalActivityMask = emptyList(),
        )
        assertNull(FxPolicy.measuredVocal(uncovered, 160.0, 176.0))
    }

    @Test
    fun musicalPolicyIsDeterministicAndKeepsNONEguards() {
        // Default lottery retained when gated off.
        assertEquals(false, FxPolicy.MUSICAL_FX_POLICY_ENABLED)
        // Guards: same NONE set as lottery (plus blank).
        assertEquals(DjEffect.NONE, FxPolicy.selectMusical("a", "b", rhythmic = true, measured = false, vocalClash = 0.0, speechLike = false, drifting = false, buildup = true, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.selectMusical("a", "b", rhythmic = true, measured = true, vocalClash = 0.0, speechLike = true, drifting = false, buildup = true, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.selectMusical("a", "b", rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = true, buildup = false, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.selectMusical("a", "b", rhythmic = true, measured = true, vocalClash = Double.NaN, speechLike = false, drifting = false, buildup = false, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.selectMusical("a", "b", rhythmic = true, measured = true, vocalClash = 0.25, speechLike = false, drifting = false, buildup = false, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.selectMusical("", "b", rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = false))
    }

    @Test
    fun musicalBuildupAndCleanSpaceFollowEnergyAndTempoGap() {
        // buildup: LOOP_ROLL when rising else FLANGER (deterministic, hash-free).
        assertEquals(DjEffect.LOOP_ROLL, FxPolicy.selectMusical("a", "b", rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = true, cleanSpace = false, energyRising = true))
        assertEquals(DjEffect.FLANGER, FxPolicy.selectMusical("a", "b", rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = true, cleanSpace = false, energyRising = false))
        // cleanSpace: ECHO when tempoGap > 0.08 else REVERB.
        assertEquals(DjEffect.ECHO, FxPolicy.selectMusical("a", "b", rhythmic = false, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = true, tempoGap = 0.12))
        assertEquals(DjEffect.REVERB, FxPolicy.selectMusical("a", "b", rhythmic = false, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = true, tempoGap = 0.02))
        // vocalClash >= 0.18: ECHO only when rhythmic.
        assertEquals(DjEffect.ECHO, FxPolicy.selectMusical("a", "b", rhythmic = true, measured = true, vocalClash = 0.19, speechLike = false, drifting = false, buildup = false, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.selectMusical("a", "b", rhythmic = false, measured = true, vocalClash = 0.19, speechLike = false, drifting = false, buildup = false, cleanSpace = false))
        // Deterministic: different ids, same musical evidence -> same effect.
        val f1 = FxPolicy.selectMusical("pair-one-out", "pair-one-in", rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = false)
        val f2 = FxPolicy.selectMusical("other-out", "other-in", rhythmic = true, measured = true, vocalClash = 0.0, speechLike = false, drifting = false, buildup = false, cleanSpace = false)
        assertEquals(f1, f2)
    }
}
