package com.music.bitchord.playback

import com.music.bitchord.playback.loopmix.LoopMixSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopBuildGenerationTest {
    @Test
    fun lateGenerationCannotOverwriteActiveTimingOrKey() {
        val activeGeneration = AtomicInteger(1)
        val activeKey = AtomicReference("generation-1")
        val staleClosed = AtomicInteger(0)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val first = LoopMixSession.BuildContext(1, 10_000L, LoopMixSession.BuildTiming())
        val second = LoopMixSession.BuildContext(2, 20_000L, LoopMixSession.BuildTiming())

        val firstThread = thread {
            first.timing.begin("out-1", first.deadlineMs, "out")
            firstStarted.countDown()
            releaseFirst.await()
            if (activeGeneration.get() != first.generation) staleClosed.incrementAndGet()
        }
        assertTrue(firstStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
        activeGeneration.set(second.generation)
        activeKey.set("generation-2")
        second.timing.begin("in-2", second.deadlineMs, "in")
        releaseFirst.countDown()
        firstThread.join(2_000)

        assertEquals(1, staleClosed.get())
        assertEquals(2, activeGeneration.get())
        assertEquals("generation-2", activeKey.get())
        assertNotEquals(first.timing, second.timing)
        assertEquals("in-2", second.timing.snapshot(21_000L, second.deadlineMs).single().step)
    }
}
