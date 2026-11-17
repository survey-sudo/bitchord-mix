package com.music.bitchord.playback

import com.music.bitchord.playback.smart.TransitionModelGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackAnalyzerDeadlineTest {
    @Test
    fun expiredBudgetAfterLockWaitSkipsInference() {
        val now = AtomicInteger(1_000)
        val gate = TransitionModelGate { now.get().toLong() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Thread {
            gate.hold {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        first.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val calls = AtomicInteger(0)
        val result = Thread {
            assertNull(gate.withBudget(4_000L, { false }) { calls.incrementAndGet() })
        }
        result.start()
        now.set(3_000)
        release.countDown()
        result.join(2_000)
        first.join(2_000)
        assertEquals(0, calls.get())
    }
}
