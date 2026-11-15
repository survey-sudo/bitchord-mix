package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * djmix13: deck budgets must never mix wall-clock with media time.
 * The device overrun strings ("17 ms over", "49012 ms over", "136249 ms over")
 * were wall/media epoch mixing while paused.
 */
class DeckBudgetTest {

    @Test fun playingPairGetsWallDeadline() {
        val d = DeckBudget.deadlineMs(nowMs = 1_000_000L, playing = true, targetStartMs = 124_148L, positionMs = 120_000L)
        assertEquals(1_000_000L + (124_148L - 120_000L), d)
    }

    @Test fun playbackSpeedScalesBudget() {
        val d = DeckBudget.deadlineMs(0L, true, 10_000L, 0L, 2f)
        assertEquals(5_000L, d)
    }

    @Test fun pausedPlayerHasNoBudget() {
        val d = DeckBudget.deadlineMs(nowMs = 9_999_999L, playing = false, targetStartMs = 4_000L, positionMs = 120_000L)
        assertEquals(DeckBudget.NO_DEADLINE_MS, d)
        // The paused span is never "late", however far the wall clock ran.
        val span = LoopMix.PrepSpan("outgoing stream", 0L, 9_999_999L, d)
        assertFalse(DeckBudget.isProvablyLate(span))
        assertEquals(0L, DeckBudget.provenOverrunMs(span.endedMs, span.deadlineMs))
        // Regression: none of the device's values may read as an overrun paused.
        val reason = LoopMix.lateStepReason(listOf(span), 9_999_999L, d)
        assertTrue(reason.contains("paused"), "reason=$reason")
    }

    @Test fun pastTargetClampsToNow() {
        val d = DeckBudget.deadlineMs(7000L, true, 100_000L, 120_000L)
        assertEquals(7000L, d)
    }

    @Test fun genuineLatenessIsReported() {
        val d = DeckBudget.deadlineMs(0L, true, 1_000L, 0L)
        val span = LoopMix.PrepSpan("outgoing splice", 0L, d + 17L, d)
        assertTrue(DeckBudget.isProvablyLate(span))
        val reason = LoopMix.lateStepReason(listOf(span), d + 17L, d)
        assertEquals("outgoing splice 17 ms over", reason)
    }
}
