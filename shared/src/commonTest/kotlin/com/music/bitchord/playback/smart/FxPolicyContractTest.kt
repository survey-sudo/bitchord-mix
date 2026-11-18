package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun slotsAndMappingsAreConsistentAcrossAllRoutes() {
        for (slot in 0..3) {
            val (a, b) = pairForSlot(slot)
            assertEquals(slot, TransitionMoves.fxSlot(a, b))
            assertEquals(slot, LoopMix.fxSlotForPair(a, b))
            assertEquals(slot, FxPolicy.fxSlot(a, b))
        }
    }

    @Test
    fun ineligibleOrUnmeasuredPairsDefaultToClean() {
        val (a0, b0) = pairForSlot(0)
        assertEquals(DjEffect.NONE, FxPolicy.select(a0, b0, true, measured = false, 0.0, false, false, true, false))
        assertEquals(DjEffect.NONE, FxPolicy.select(a0, b0, true, measured = true, 0.0, speechLike = true, drifting = false, buildup = true, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.select(a0, b0, true, measured = true, vocalClash = 0.25, speechLike = false, drifting = false, buildup = true, cleanSpace = false))
        assertEquals(DjEffect.NONE, blendColour(0.0, a0, b0))
        assertEquals(DjEffect.NONE, FxPolicy.select(a0, b0, true, true, 0.0, false, false, false, false))
        assertEquals(DjEffect.NONE, FxPolicy.select(a0, b0, true, true, 0.0, false, true, true, false))
    }

    @Test
    fun eligibleBuildupAndCleanSpaceRespectSlotPolicy() {
        val (a0, b0) = pairForSlot(0)
        val (a1, b1) = pairForSlot(1)
        val (a2, b2) = pairForSlot(2)
        val (a3, b3) = pairForSlot(3)

        assertEquals(DjEffect.FLANGER, FxPolicy.select(a0, b0, true, true, 0.0, false, false, buildup = true, cleanSpace = false))
        assertEquals(DjEffect.LOOP_ROLL, FxPolicy.select(a1, b1, true, true, 0.0, false, false, buildup = true, cleanSpace = false))
        assertEquals(DjEffect.NONE, FxPolicy.select(a2, b2, true, true, 0.0, false, false, buildup = true, cleanSpace = false))

        assertEquals(DjEffect.ECHO, FxPolicy.select(a0, b0, false, true, 0.0, false, false, buildup = false, cleanSpace = true))
        assertEquals(DjEffect.ECHO, FxPolicy.select(a2, b2, false, true, 0.0, false, false, buildup = false, cleanSpace = true))
        assertEquals(DjEffect.REVERB, FxPolicy.select(a3, b3, false, true, 0.0, false, false, buildup = false, cleanSpace = true))
    }
}
