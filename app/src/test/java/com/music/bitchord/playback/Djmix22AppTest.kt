package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * djmix22 `:app` handoff discipline (vc64).
 *
 * Pure JVM (no ExoPlayer, no Media3 focus machinery needed): the two decisions
 * are the controller-side half of the fix for vc63's Calvin-pair jam
 * (mix.fire loop -> finish-guard 1.668 s later -> 350 ms emergency tail ->
 * seek-emergency-tail double teardown, then the second song paused with no
 * focus holder for ~70 s).
 *
 * [shouldRestorePlayAfterFocusSwap] is the guard the service consults before
 * auto-resuming a session player paused by the focus swap: a user-paused
 * player must NEVER be auto-resumed, and only a player that was audibly
 * playing before the adoption may be restored. [loopFinishAction] is the
 * completion routing inside finish(): a took-over, handed-off loop session
 * with a clean exit gate completes via finishLoop(), never the emergency tail.
 */
class Djmix22AppTest {

    // ---- Focus-swap restore -------------------------------------------------

    @Test
    fun `playing through a handoff is restored`() {
        assertTrue(
            shouldRestorePlayAfterFocusSwap(
                wasPlaying = true,
                userPaused = false,
                focusHeld = true,
            ),
        )
    }

    @Test
    fun `user-paused player is never auto-resumed`() {
        assertFalse(
            shouldRestorePlayAfterFocusSwap(
                wasPlaying = true,
                userPaused = true,
                focusHeld = true,
            ),
        )
        assertFalse(
            shouldRestorePlayAfterFocusSwap(
                wasPlaying = true,
                userPaused = true,
                focusHeld = false,
            ),
        )
    }

    @Test
    fun `not-playing player is never started by the swap`() {
        assertFalse(
            shouldRestorePlayAfterFocusSwap(
                wasPlaying = false,
                userPaused = false,
                focusHeld = true,
            ),
        )
    }

    @Test
    fun `no focus held means no restore`() {
        assertFalse(
            shouldRestorePlayAfterFocusSwap(
                wasPlaying = true,
                userPaused = false,
                focusHeld = false,
            ),
        )
    }

    // ---- Loop finish routing ------------------------------------------------

    @Test
    fun `took-over handed-off session completes via finishLoop not emergency tail`() {
        assertEquals(
            LoopFinishAction.FINISH_LOOP,
            loopFinishAction(
                phaseName = "LOOP_RUNNING",
                tookOver = true,
                handedOff = true,
                exitGateViolations = 0,
                audibleSpare = true,
            ),
        )
    }

    @Test
    fun `arming past takeover and handoff also completes via finishLoop`() {
        assertEquals(
            LoopFinishAction.FINISH_LOOP,
            loopFinishAction(
                phaseName = "LOOP_ARMING",
                tookOver = true,
                handedOff = true,
                exitGateViolations = 0,
                audibleSpare = true,
            ),
        )
    }

    @Test
    fun `pre-handoff loop mix keeps the emergency tail for its settle seek`() {
        assertEquals(
            LoopFinishAction.EMERGENCY_TAIL,
            loopFinishAction(
                phaseName = "LOOP_RUNNING",
                tookOver = true,
                handedOff = false,
                exitGateViolations = 0,
                audibleSpare = true,
            ),
        )
    }

    @Test
    fun `not took over means emergency tail`() {
        assertEquals(
            LoopFinishAction.EMERGENCY_TAIL,
            loopFinishAction(
                phaseName = "LOOP_RUNNING",
                tookOver = false,
                handedOff = false,
                exitGateViolations = 0,
                audibleSpare = true,
            ),
        )
    }

    @Test
    fun `dirty exit gate keeps the emergency tail`() {
        assertEquals(
            LoopFinishAction.EMERGENCY_TAIL,
            loopFinishAction(
                phaseName = "LOOP_RUNNING",
                tookOver = true,
                handedOff = true,
                exitGateViolations = 2,
                audibleSpare = true,
            ),
        )
    }

    @Test
    fun `unprovable gate keeps the emergency tail`() {
        // -1: no session (the finishLoop re-entry, or a nulled renderer).
        assertEquals(
            LoopFinishAction.EMERGENCY_TAIL,
            loopFinishAction(
                phaseName = "LOOP_RUNNING",
                tookOver = false,
                handedOff = true,
                exitGateViolations = -1,
                audibleSpare = true,
            ),
        )
    }

    @Test
    fun `classic audible spare keeps the emergency tail`() {
        assertEquals(
            LoopFinishAction.EMERGENCY_TAIL,
            loopFinishAction(
                phaseName = "FADING",
                tookOver = false,
                handedOff = true,
                exitGateViolations = 0,
                audibleSpare = true,
            ),
        )
    }

    @Test
    fun `inaudible spare finishes at once even outside a mix`() {
        assertEquals(
            LoopFinishAction.FINISH,
            loopFinishAction(
                phaseName = "IDLE",
                tookOver = false,
                handedOff = false,
                exitGateViolations = 0,
                audibleSpare = false,
            ),
        )
        assertEquals(
            LoopFinishAction.FINISH,
            loopFinishAction(
                phaseName = "LOOP_RUNNING",
                tookOver = true,
                handedOff = true,
                exitGateViolations = 0,
                audibleSpare = false,
            ),
        )
    }
}
