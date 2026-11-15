package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * djmix13 scrub decisions: visible states map to exactly one safe action,
 * and a shown plan is invalidated once the playhead crossed its start.
 */
class SeekActionTest {

    @Test fun idleAndArmingCancelUnlessAudible() {
        assertEquals(SeekAction.Action.CANCEL, SeekAction.decide(SeekAction.Phase.IDLE, markerShown = false))
        assertEquals(SeekAction.Action.CANCEL, SeekAction.decide(SeekAction.Phase.ARMING, markerShown = false))
        // Shown but not audible yet: cancel + replan (never ghost-fire).
        assertEquals(SeekAction.Action.CANCEL, SeekAction.decide(SeekAction.Phase.ARMING, markerShown = true))
    }

    @Test fun audibleBlendGoesToEmergencyTail() {
        assertEquals(SeekAction.Action.EMERGENCY_TAIL, SeekAction.decide(SeekAction.Phase.FADING, markerShown = true))
        // djmix25: TAIL honours the in-flight dissolve (stops the 4x exit.tail cascade).
        assertEquals(SeekAction.Action.HONOUR, SeekAction.decide(SeekAction.Phase.TAIL, markerShown = true))
        // Loop phases: torn down, not left running.
        assertEquals(SeekAction.Action.CANCEL, SeekAction.decide(SeekAction.Phase.LOOP, markerShown = true))
    }

    @Test fun crossedStartInvalidatesThePlan() {
        assertTrue(SeekAction.planStillValid(10_000L, 8_000L))
        assertFalse(SeekAction.planStillValid(10_000L, 12_000L))
        assertTrue(SeekAction.planStillValid(10_000L, 10_000L))
    }
}
