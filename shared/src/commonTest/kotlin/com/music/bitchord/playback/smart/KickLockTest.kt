package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.math.roundToLong
import kotlin.test.assertTrue

/**
 * vc69 P1 kick-lock: transitions land within an eighth of a beat of the
 * nearest kick, and quantise to the eighth grid otherwise.
 */
class KickLockTest {

    private val beats = listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0)

    @Test
    fun snapsToNearestBeatWithinAnEighth() {
        // beatSec 0.5: eighth is 62.5 ms of tolerance.
        val snapped = KickLock.snapToKickLock(2.53, 0.5, beats)
        assertEquals(2.5, snapped, 1e-9)
        assertEquals(30.0, KickLock.kickLockErrorMs(2.53, 0.5, beats), 0.5)
        assertTrue(KickLock.isKickLocked(2.53, 0.5, beats))
    }

    @Test
    fun offGridQuantisesToEighthBeatLattice() {
        // 2.33 sits between beats: nearest eighth on the origin grid.
        val snapped = KickLock.snapToKickLock(2.33, 0.5, beats, gridOrigin = 0.5)
        val eighth = 0.0625
        assertEquals(0.5 + (1.83 / eighth).roundToLong() * eighth, snapped, 1e-9)
        assertTrue(abs(snapped - 2.33) <= eighth / 2.0 + 1e-9)
    }

    @Test
    fun outOfToleranceBeatIsNotClaimedAsKickLocked() {
        assertFalse(KickLock.isKickLocked(2.33, 0.5, beats))
        assertTrue(KickLock.kickLockErrorMs(2.33, 0.5, beats) > 0.0)
    }

    @Test
    fun exactBeatIsUnmoved() {
        assertEquals(2.5, KickLock.snapToKickLock(2.5, 0.5, beats), 1e-9)
        assertEquals(0.0, KickLock.kickLockErrorMs(2.5, 0.5, beats), 1e-9)
    }

    @Test
    fun nonfiniteOrZeroGridIsPassthrough() {
        assertEquals(2.33, KickLock.snapToKickLock(2.33, 0.0, beats), 1e-9)
        assertEquals(2.33, KickLock.snapToKickLock(2.33, -1.0, beats), 1e-9)
        val nan = Double.NaN
        assertTrue(KickLock.snapToKickLock(nan, 0.5, beats).isNaN())
        assertFalse(KickLock.isKickLocked(nan, 0.5, beats))
        assertEquals(0.0, KickLock.kickLockErrorMs(nan, 0.5, beats), 0.0)
    }
}
