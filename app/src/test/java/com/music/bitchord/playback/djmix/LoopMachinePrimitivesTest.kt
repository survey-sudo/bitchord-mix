package com.music.bitchord.playback.djmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests verifying extracted M9 C-7 and C-6 primitives in isolation.
 */
class LoopMachinePrimitivesTest {

    @Test
    fun `idle stall decision transitions correctly`() {
        assertEquals(IdleStallAction.WAIT_AND_FREEZE, idleStallDecision(0L, false, false))
        assertEquals(IdleStallAction.WAIT_AND_FREEZE, idleStallDecision(1499L, false, false))
        assertEquals(IdleStallAction.PREPARE_ONCE, idleStallDecision(1500L, false, false))
        assertEquals(IdleStallAction.WAIT_AND_FREEZE, idleStallDecision(1600L, false, true))
        assertEquals(IdleStallAction.FORCE_BAIL, idleStallDecision(2000L, false, true))
        assertEquals(IdleStallAction.FORCE_BAIL, idleStallDecision(0L, true, false))
    }

    @Test
    fun `loop finish action routes clean takeover to finish loop`() {
        assertEquals(
            LoopFinishAction.FINISH,
            loopFinishAction("LOOP_RUNNING", tookOver = true, handedOff = true, exitGateViolations = 0, audibleSpare = false),
        )
        assertEquals(
            LoopFinishAction.FINISH_LOOP,
            loopFinishAction("LOOP_RUNNING", tookOver = true, handedOff = true, exitGateViolations = 0, audibleSpare = true),
        )
        assertEquals(
            LoopFinishAction.FINISH_LOOP,
            loopFinishAction("LOOP_ARMING", tookOver = true, handedOff = true, exitGateViolations = 0, audibleSpare = true),
        )
        assertEquals(
            LoopFinishAction.EMERGENCY_TAIL,
            loopFinishAction("FADING", tookOver = true, handedOff = true, exitGateViolations = 0, audibleSpare = true),
        )
        assertEquals(
            LoopFinishAction.EMERGENCY_TAIL,
            loopFinishAction("LOOP_RUNNING", tookOver = false, handedOff = true, exitGateViolations = 0, audibleSpare = true),
        )
        assertEquals(
            LoopFinishAction.EMERGENCY_TAIL,
            loopFinishAction("LOOP_RUNNING", tookOver = true, handedOff = false, exitGateViolations = 0, audibleSpare = true),
        )
        assertEquals(
            LoopFinishAction.EMERGENCY_TAIL,
            loopFinishAction("LOOP_RUNNING", tookOver = true, handedOff = true, exitGateViolations = 1, audibleSpare = true),
        )
    }

    @Test
    fun `freeze dedup key formats phase and pair`() {
        assertEquals("FADING|a->b", freezeDedupKey("FADING", "a", "b"))
        assertEquals("ARMING|->", freezeDedupKey("ARMING", null, null))
    }

    @Test
    fun `focus swap restore respects user pause`() {
        assertTrue(shouldRestorePlayAfterFocusSwap(wasPlaying = true, userPaused = false, focusHeld = true))
        assertFalse(shouldRestorePlayAfterFocusSwap(wasPlaying = true, userPaused = true, focusHeld = true))
        assertFalse(shouldRestorePlayAfterFocusSwap(wasPlaying = false, userPaused = false, focusHeld = true))
        assertFalse(shouldRestorePlayAfterFocusSwap(wasPlaying = true, userPaused = false, focusHeld = false))
    }

    @Test
    fun `can adopt session player requires loop incoming started`() {
        assertFalse(canAdoptSessionPlayer(false))
        assertTrue(canAdoptSessionPlayer(true))
    }

    @Test
    fun `deck timing captures and clears spans`() {
        var clock = 1000L
        val timing = DeckTiming { clock }
        timing.begin("decode")
        clock = 1050L
        timing.endOpen()
        val snap = timing.snapshot(clock)
        assertEquals(1, snap.size)
        assertEquals("decode", snap[0].step)
        assertEquals(1000L, snap[0].startedMs)
        assertEquals(1050L, snap[0].endedMs)

        timing.clear()
        assertTrue(timing.snapshot(clock).isEmpty())
    }
}
