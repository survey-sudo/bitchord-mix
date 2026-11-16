package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopMixDuckTest {

    @Test
    fun clashBelowTriggerDoesNotDuck() {
        val decision = LoopMixDuck.decide(
            clashE = 0.05,
            outHasVocal = true,
            inHasEarlyVocal = true,
            fallbackTrigger = true,
        )
        assertFalse(decision.wantDuck, "0.05 must not duck, measured overrides fallback")
        assertFalse(decision.duckIncoming)
        assertEquals(LoopMixDuck.MIN_FLOOR, decision.outFloor)
        assertEquals(LoopMixDuck.MIN_FLOOR, decision.inFloor)
    }

    @Test
    fun clashAtPointTenDoesNotDuck() {
        val decision = LoopMixDuck.decide(
            clashE = 0.10,
            outHasVocal = true,
            inHasEarlyVocal = true,
            fallbackTrigger = false,
        )
        assertFalse(decision.wantDuck, "0.10 must not duck")
    }

    @Test
    fun clashJustAboveTriggerDucksGraded() {
        val decision = LoopMixDuck.decide(
            clashE = 0.13,
            outHasVocal = true,
            inHasEarlyVocal = false,
            fallbackTrigger = false,
        )
        assertTrue(decision.wantDuck, "0.13 must duck")
        assertTrue(decision.outFloor > 0.15f, "0.13 must be graded above the floor, got ${decision.outFloor}")
        assertEquals(0.3846f, decision.outFloor, 0.001f, "0.05/0.13 grades via duckGainFor")
    }

    @Test
    fun moderateClashGradesTowardFloor() {
        val decision = LoopMixDuck.decide(
            clashE = 0.30,
            outHasVocal = true,
            inHasEarlyVocal = false,
            fallbackTrigger = false,
        )
        assertTrue(decision.wantDuck, "0.30 must duck")
        assertTrue(
            decision.outFloor >= 0.15f && decision.outFloor < 0.20f,
            "0.05/0.30 must grade near the floor, got ${decision.outFloor}",
        )
    }

    @Test
    fun severeClashClampsToFloor() {
        val moderate = LoopMixDuck.decide(
            clashE = 0.40,
            outHasVocal = true,
            inHasEarlyVocal = false,
            fallbackTrigger = false,
        )
        assertTrue(moderate.wantDuck, "0.40 must duck")
        assertEquals(0.15f, moderate.outFloor, "0.40 must clamp at the floor")

        val severe = LoopMixDuck.decide(
            clashE = 0.65,
            outHasVocal = true,
            inHasEarlyVocal = false,
            fallbackTrigger = false,
        )
        assertTrue(severe.wantDuck, "0.65 must duck")
        assertEquals(0.15f, severe.outFloor, "0.65 must clamp at the floor")
        assertTrue(severe.duckIncoming, "0.65 must duck incoming even without early vocal")
        assertEquals(0.15f, severe.inFloor, "ducked incoming must sit at the floor")
    }

    @Test
    fun unmeasuredBothSingingDucksBoth() {
        val decision = LoopMixDuck.decide(
            clashE = null,
            outHasVocal = true,
            inHasEarlyVocal = true,
            fallbackTrigger = false,
        )
        assertTrue(decision.wantDuck, "unmeasured dual vocal must duck")
        assertTrue(decision.duckIncoming, "unmeasured dual vocal must duck incoming too")
        assertEquals(0.15f, decision.outFloor)
        assertEquals(0.15f, decision.inFloor)
    }

    @Test
    fun unmeasuredWithoutFallbackDoesNotDuck() {
        val decision = LoopMixDuck.decide(
            clashE = null,
            outHasVocal = false,
            inHasEarlyVocal = false,
            fallbackTrigger = false,
        )
        assertFalse(decision.wantDuck, "unmeasured clean pair must not duck")
        assertFalse(decision.duckIncoming)
    }
}
