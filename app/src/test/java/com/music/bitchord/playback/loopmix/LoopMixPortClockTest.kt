package com.music.bitchord.playback.loopmix

import org.junit.Assert.*
import org.junit.Test

class LoopMixPortClockTest {
    @Test fun readingsRequireCurrentArmAndBoundedCoherentFreshness() {
        val port = LoopMixPort()
        port.armIncoming(LoopMixPort.InArm(13_000_000))
        val now = 2_000_000_000L
        port.publishPosition(13_000_000, now, port.generation, port.clockEpoch)
        assertEquals(13.1, port.presentedSeconds(now + 100_000_000, true)!!, 1e-9)
        assertEquals(13.3, port.presentedSeconds(now + 300_000_000, true)!!, 1e-9)
        assertEquals(13.0, port.presentedSeconds(now + 100_000_000, false)!!, 1e-9)
        assertEquals(13.0 - 1e-9, port.presentedSeconds(now - 1, true)!!, 1e-12)
        assertNull(port.presentedSeconds(now + LoopMixPort.POSITION_FRESHNESS_NANOS + 1, true))

        val generation = port.generation
        val epoch = port.clockEpoch
        port.armIncoming(LoopMixPort.InArm(14_000_000))
        assertNull(port.presentedSeconds(now, true))
        port.publishPosition(99_000_000, now, generation, epoch)
        assertNull(port.presentedSeconds(now, true))
        port.publishPosition(14_000_000, now, port.generation, port.clockEpoch)
        assertEquals(14.0, port.presentedSeconds(now, true)!!, 1e-9)
        port.disarm()
        assertNull(port.presentedSeconds(now, true))
    }

    @Test fun publicationInProgressIsContendedRatherThanUnavailable() {
        val port = LoopMixPort()
        port.armIncoming(LoopMixPort.InArm(13_000_000))
        val now = 2_000_000_000L
        port.publishPosition(13_000_000, now, port.generation, port.clockEpoch)
        val sequence = LoopMixPort::class.java.getDeclaredField("positionSequence").apply { isAccessible = true }
        sequence.setLong(port, 3L) // suspended single writer, between sequence increments
        val reading = port.readPresentedPosition(now, true)
        assertTrue(reading.contended)
        assertNull(reading.seconds)
        sequence.setLong(port, 4L)
        assertEquals(13.0, port.readPresentedPosition(now, true).seconds!!, 1e-9)
        port.invalidatePosition()
        assertFalse(port.readPresentedPosition(now, true).contended)
        assertNull(port.readPresentedPosition(now, true).seconds)
    }

    @Test fun overlappingPublicationProjectsBackToReferenceButRejectsDistantFuture() {
        val port = LoopMixPort()
        port.armIncoming(LoopMixPort.InArm(13_000_000))
        val reference = 2_000_000_000L
        port.publishPosition(13_100_000, reference + 100_000_000, port.generation, port.clockEpoch)
        assertEquals(13.0, port.presentedSeconds(reference, true)!!, 1e-9)
        assertEquals(13.1, port.presentedSeconds(reference, false)!!, 1e-9)
        assertNotNull(port.presentedSeconds(reference + 100_000_000 - LoopMixPort.POSITION_FRESHNESS_NANOS, true))
        assertNull(port.presentedSeconds(reference + 100_000_000 - LoopMixPort.POSITION_FRESHNESS_NANOS - 1, true))
    }

    @Test fun startupFlushInvalidatesObservationButAllowsFreshReadingOnSameArm() {
        val port = LoopMixPort()
        port.armIncoming(LoopMixPort.InArm(13_000_000))
        val generation = port.generation
        val epoch = port.clockEpoch
        val now = 2_000_000_000L
        port.publishPosition(13_000_000, now, generation, epoch)
        port.invalidatePosition()
        assertNotNull(port.inArm)
        assertNull(port.presentedSeconds(now, true))
        // A delegate read that began before flush cannot resurrect its clock.
        port.publishPosition(99_000_000, now, generation, epoch)
        assertNull(port.presentedSeconds(now, true))
        port.publishPosition(13_100_000, now, generation, port.clockEpoch)
        assertEquals(13.1, port.presentedSeconds(now, true)!!, 1e-9)
    }
}
