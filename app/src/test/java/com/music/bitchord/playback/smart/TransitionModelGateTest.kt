package com.music.bitchord.playback.smart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.*
import org.junit.Test

class TransitionModelGateTest {
    @Test fun aTwoSecondModelHolderDeclinesOptionalWorkImmediately() {
        val lock = ReentrantLock()
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            lock.lock()
            try { acquired.countDown(); release.await(2, TimeUnit.SECONDS) }
            finally { lock.unlock() }
        }
        val steps = mutableListOf<String>()
        try {
            holder.start()
            assertTrue(acquired.await(1, TimeUnit.SECONDS))
            val began = System.nanoTime()
            val result = tryTransitionModel<String>(lock, { null }, steps::add) { fail("model must stay exclusive"); "bad" }
            assertNull(result)
            assertTrue((System.nanoTime() - began) / 1_000_000L < 500L)
            assertTrue("holder still owns its 2s window", holder.isAlive)
            assertEquals(listOf("model-lock", "stem-skip:model-lock-unavailable"), steps)
        } finally { release.countDown(); holder.join(2_500) }
    }

    @Test fun yieldOrDeadlineStartingAtAcquisitionIsRecheckedWithoutSleepOrInference() {
        for (reason in listOf("playback-yield", "deadline", "cancelled")) {
            val lock = ReentrantLock()
            val checks = AtomicInteger()
            val steps = mutableListOf<String>()
            val result = tryTransitionModel<String>(lock,
                { if (checks.getAndIncrement() == 0) null else reason }, steps::add,
            ) { fail("fresh acquired-lock verdict must win"); "bad" }
            assertNull(result)
            assertEquals(2, checks.get())
            assertFalse(lock.isLocked)
            assertTrue(steps.contains("stem-skip:$reason"))
            assertFalse(steps.contains("model"))
        }
    }

    @Test fun anExistingPlaybackYieldDoesNotAcquireTheModel() {
        val lock = ReentrantLock()
        val steps = mutableListOf<String>()
        assertNull(tryTransitionModel<String>(lock, { "playback-yield" }, steps::add) { fail("no model"); "bad" })
        assertEquals(listOf("stem-skip:playback-yield"), steps)
        assertFalse(lock.isLocked)
    }
}
