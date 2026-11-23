package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MixTemplateIRTest {
    private fun loopPlan(stemDuck: Boolean = false) = LoopMix.Plan(
        out = LoopMix.Pick(loopStart = 170.0, loopEnd = 178.0, bars = 4, beatSec = 0.5, bpm = 120.0, vocal = 0.14, checkedBy = LoopMix.Check.MASK_DUCKED),
        inn = LoopMix.Pick(loopStart = 0.5, loopEnd = 8.5, bars = 4, beatSec = 0.5, bpm = 120.0, vocal = 0.0, checkedBy = LoopMix.Check.LYRICS),
        fold = 1.0,
        enterBars = 4,
        rampBars = 2,
        exitBars = 2,
        totalBars = 8,
        rung = "Loop",
        stemDuckRequired = stemDuck,
        duckFloor = LoopMixDuck.MIN_FLOOR,
    )

    @Test
    fun loopPlanConvertsToUnifiedIR() {
        val plan = loopPlan(stemDuck = true)
        val ir = plan.toMixTemplateIR()
        assertEquals("loopmix", ir.templateId)
        assertEquals("Loop", ir.rung)
        assertEquals(170.0, ir.outStartSec, 1e-9)
        assertEquals(178.0, ir.outEndSec, 1e-9)
        assertEquals(0.5, ir.inCueSec, 1e-9)
        assertTrue(ir.overlapSec > 0.0)
        assertEquals(1.0, ir.fold, 1e-9)
        assertEquals(1.0, ir.incomingRate, 1e-9)
        assertTrue(ir.stemDuckRequired)
        assertEquals(DjEffect.NONE, ir.djEffect)
        assertEquals("none", ir.fallbackPolicy)
    }

    @Test
    fun loopPlanEffectMappingIsStable() {
        val echo = loopPlan().copy(echoTail = true)
        assertEquals(DjEffect.ECHO, echo.toMixTemplateIR().djEffect)
        val roll = loopPlan().copy(buildupRoll = true)
        assertEquals(DjEffect.LOOP_ROLL, roll.toMixTemplateIR().djEffect)
        val reverb = loopPlan().copy(reverbWash = true)
        assertEquals(DjEffect.REVERB, reverb.toMixTemplateIR().djEffect)
        val flanger = loopPlan().copy(flanger = true)
        assertEquals(DjEffect.FLANGER, flanger.toMixTemplateIR().djEffect)
        val natural = loopPlan().copy(alternativeKind = "natural-end")
        assertEquals("natural-end", natural.toMixTemplateIR().fallbackPolicy)
    }

    @Test
    fun transitionPlanConvertsToUnifiedIR() {
        val plan = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 170.0,
            transitionEnd = 178.0,
            fadeSeconds = 8.0,
            incomingCueTime = 0.5,
            incomingPlaybackRate = 1.0952,
            djEffect = DjEffect.FLANGER,
        )
        val ir = plan.toMixTemplateIR()
        assertEquals("transition", ir.templateId)
        assertEquals(TransitionStyle.DJ_BLEND.name, ir.rung)
        assertEquals(170.0, ir.outStartSec, 1e-9)
        assertEquals(178.0, ir.outEndSec, 1e-9)
        assertEquals(0.5, ir.inCueSec, 1e-9)
        assertEquals(8.0, ir.overlapSec, 1e-9)
        assertEquals(1.0, ir.fold, 1e-9)
        assertEquals(1.0952, ir.incomingRate, 1e-9)
        assertFalse(ir.stemDuckRequired)
        assertEquals(DjEffect.FLANGER, ir.djEffect)
    }

    @Test
    fun roundTripPreservesGeometryAndPolicy() {
        val loop = loopPlan(stemDuck = false)
        val loopIr = loop.toMixTemplateIR()
        // Rebuild the same geometry from IR scalars and re-convert: stable.
        val rebuilt = LoopMix.Plan(
            out = LoopMix.Pick(loopIr.outStartSec, loopIr.outEndSec, 4, 0.5, 120.0, 0.0, LoopMix.Check.LYRICS),
            inn = LoopMix.Pick(loopIr.inCueSec, loopIr.inCueSec + 8.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.LYRICS),
            fold = loopIr.fold,
            enterBars = 4,
            rampBars = 2,
            exitBars = 2,
            totalBars = 8,
            rung = loopIr.rung,
            stemDuckRequired = loopIr.stemDuckRequired,
            duckFloor = LoopMixDuck.MIN_FLOOR,
        )
        val roundTripped = rebuilt.toMixTemplateIR()
        assertEquals(loopIr.outStartSec, roundTripped.outStartSec, 1e-9)
        assertEquals(loopIr.outEndSec, roundTripped.outEndSec, 1e-9)
        assertEquals(loopIr.inCueSec, roundTripped.inCueSec, 1e-9)
        assertEquals(loopIr.stemDuckRequired, roundTripped.stemDuckRequired)
        assertEquals(loopIr.djEffect, roundTripped.djEffect)

        val trans = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_FILTER,
            transitionStart = 10.0,
            transitionEnd = 14.0,
            fadeSeconds = 4.0,
            incomingCueTime = 2.0,
            incomingPlaybackRate = 1.0,
            djEffect = DjEffect.ECHO,
            alternativeKind = "decline-carve",
        )
        val transIr = trans.toMixTemplateIR()
        assertEquals("decline-carve", transIr.fallbackPolicy)
        val rebuiltTrans = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_FILTER,
            transitionStart = transIr.outStartSec,
            transitionEnd = transIr.outEndSec,
            fadeSeconds = transIr.overlapSec,
            incomingCueTime = transIr.inCueSec,
            incomingPlaybackRate = transIr.incomingRate,
            djEffect = transIr.djEffect,
            alternativeKind = transIr.fallbackPolicy,
        )
        val transRoundTripped = rebuiltTrans.toMixTemplateIR()
        assertEquals(transIr.outStartSec, transRoundTripped.outStartSec, 1e-9)
        assertEquals(transIr.overlapSec, transRoundTripped.overlapSec, 1e-9)
        assertEquals(transIr.incomingRate, transRoundTripped.incomingRate, 1e-9)
        assertEquals(transIr.djEffect, transRoundTripped.djEffect)
        assertNotNull(transIr.rung)
    }
}
