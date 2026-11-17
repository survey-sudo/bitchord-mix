package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopSourceDomainTest {
    @Test fun mappingMatchesNativeTableAndStraightIdentity() {
        // Native probe uses regionStart=2; these inputs/outputs are track seconds.
        for ((input, expected) in listOf(3.0 to 3.0, 5.0 to 5.0, 6.0 to 4.0,
            10.0 to 4.0, 12.0 to 6.0, 15.0 to 9.0)) {
            assertEquals(expected, LoopMix.physicalTrackTime(input, 4.0, 6.0, 12.0), 1e-9)
            assertEquals(input, LoopMix.physicalTrackTime(input, 4.0, 4.0, 12.0), 1e-9)
        }
    }

    @Test fun holdsKeepUnwrappedClockMonotonicAcrossFoldsAndLoopLengths() {
        for (bars in listOf(1, 2, 4)) for (fold in listOf(0.5, 1.0, 2.0))
        for (loop in listOf(false, true)) for (native in listOf(false, true)) {
            val pick = LoopMix.Pick(3.875, 3.875 + bars * 4 * 0.4775, bars, 0.4775,
                60 / 0.4775, 0.0, LoopMix.Check.MASK)
            val plan = LoopMix.Plan(pick, pick, fold, 2, 4, 2, 8,
                inLoops = loop, nativeIncoming = native, incomingHoldSec = 1.25)
            val clock = LoopMix.Clock(plan)
            val unwrapped = clock.beatTimes.map(clock::inUnwrappedTimeAt)
            assertTrue(unwrapped.zipWithNext().all { (a, b) -> b >= a })
            assertEquals(pick.loopStart, clock.inUnwrappedTimeAt(1.0), 1e-9)
            val release = clock.inReleasePosition
            val expected = if (loop && !native && release >= pick.loopEnd)
                LoopMix.physicalTrackTime(unwrapped.last(), pick.loopStart, pick.loopEnd, release)
                else unwrapped.last()
            assertEquals(expected, clock.elementTrackTime, 1e-9)
        }
    }
}
