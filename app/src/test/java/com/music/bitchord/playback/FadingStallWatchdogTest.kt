package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc77 FADING-stall bound (pure JVM proof). The controller holds
 * `progress == 1` while `incomingAt < 0.999` blocks `incomingReady` and the
 * outgoing file is not near EOF; with the incoming player frozen in
 * `STATE_BUFFERING` that never unblocks. [fadingStallShouldBail] is the pure
 * gate [CrossfadeController.driveFade] applies every tick, and
 * [FADING_STALL_BAIL_MS] is its timeout.
 */
class FadingStallWatchdogTest {

    @Test
    fun timeoutIsBoundedBeyondOrdinaryRebuffers() {
        assertEquals(2_500L, FADING_STALL_BAIL_MS)
    }

    @Test
    fun briefDecodeStallDoesNotBail() {
        // Under the bound the blend stays in FADING: a stall that recovers
        // first completes through the planned path (inaudible either way —
        // the outgoing fader is already 0 at progress 1).
        assertFalse(
            fadingStallShouldBail(
                stalledMs = 0L,
                progressAtLeastOne = true,
                incomingReady = false,
                outAboutToEof = false,
                incomingBuffering = true,
            ),
        )
        assertFalse(
            fadingStallShouldBail(
                stalledMs = FADING_STALL_BAIL_MS - 1,
                progressAtLeastOne = true,
                incomingReady = false,
                outAboutToEof = false,
                incomingBuffering = true,
            ),
        )
    }

    @Test
    fun sustainedBufferingAtProgressOneBails() {
        assertTrue(
            fadingStallShouldBail(
                stalledMs = FADING_STALL_BAIL_MS,
                progressAtLeastOne = true,
                incomingReady = false,
                outAboutToEof = false,
                incomingBuffering = true,
            ),
        )
        assertTrue(
            fadingStallShouldBail(
                stalledMs = 30_000L,
                progressAtLeastOne = true,
                incomingReady = false,
                outAboutToEof = false,
                incomingBuffering = true,
            ),
        )
    }

    @Test
    fun userPauseNeverTripsTheWatchdog() {
        // A paused blend parks in STATE_READY, never BUFFERING, so even a
        // long intentional hold must not force-bail.
        assertFalse(
            fadingStallShouldBail(
                stalledMs = 10_000L,
                progressAtLeastOne = true,
                incomingReady = false,
                outAboutToEof = false,
                incomingBuffering = false,
            ),
        )
    }

    @Test
    fun readyIncomingAndEofPathsStillCompletePlanned() {
        // incomingReady => done fires through the planned path.
        assertFalse(
            fadingStallShouldBail(
                stalledMs = 10_000L,
                progressAtLeastOne = true,
                incomingReady = true,
                outAboutToEof = false,
                incomingBuffering = true,
            ),
        )
        // Outgoing near EOF => vc77 allows completion without incomingReady.
        assertFalse(
            fadingStallShouldBail(
                stalledMs = 10_000L,
                progressAtLeastOne = true,
                incomingReady = false,
                outAboutToEof = true,
                incomingBuffering = true,
            ),
        )
    }

    @Test
    fun progressBelowOneIsNotTheHang() {
        // Below progress 1 the outgoing side still drives the faders; the
        // blend is not stuck.
        assertFalse(
            fadingStallShouldBail(
                stalledMs = 10_000L,
                progressAtLeastOne = false,
                incomingReady = false,
                outAboutToEof = false,
                incomingBuffering = true,
            ),
        )
    }
}
