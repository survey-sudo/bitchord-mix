package com.music.bitchord.playback.loopmix

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production Java-future wait/worker cancellation; no native decoder or renderer. */
class LoopDecodeBudgetTest {
    private fun nowMs() = System.nanoTime() / 1_000_000L
    private val pcm = LoopRegion(floatArrayOf(1f), floatArrayOf(1f), 0.0)

    @Test fun bothDecksShareOneBoundedBudgetAndNeverSeriallyRedecode() {
        val executor = Executors.newFixedThreadPool(2)
        val calls = AtomicInteger()
        val finished = CountDownLatch(2)
        val control = LoopBuildControl(::nowMs, maxBuildMs = 200L)
        fun decode(): LoopRegion? {
            calls.incrementAndGet()
            try { CountDownLatch(1).await(10, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally { finished.countDown() }
            return null
        }
        try {
            val start = nowMs()
            val result = decodeLoopRegions(executor, control, { _, _ -> }, ::decode, ::decode)
            assertTrue(result == null || result.first == null || result.second == null)
            assertTrue("one total budget, not an unbounded get", nowMs() - start < 1_000L)
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals("never enter the old serial retry", 2, calls.get())
            assertEquals("deadline", control.failure)
            awaitRetired(control)
        } finally { control.cancel(); executor.shutdownNow() }
    }

    @Test fun cancellationInterruptsBothFuturesWithoutMoreDecode() = cancellationFixture(interruptParent = false)
    @Test fun parentInterruptionCancelsBothFuturesWithoutMoreDecode() = cancellationFixture(interruptParent = true)

    @Test fun oneDecoderExceptionCancelsItsPeerAndDoesNotSeriallyRedecode() {
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(2)
        val peerFinished = CountDownLatch(1)
        val calls = AtomicInteger()
        val control = LoopBuildControl(::nowMs, maxBuildMs = 2_000L)
        try {
            val result = decodeLoopRegions(executor, control, { _, _ -> },
                {
                    calls.incrementAndGet(); entered.countDown()
                    assertTrue(entered.await(1, TimeUnit.SECONDS))
                    throw IllegalStateException("decoder refuses container")
                },
                {
                    calls.incrementAndGet(); entered.countDown()
                    try { CountDownLatch(1).await(10, TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    finally { peerFinished.countDown() }
                    null
                },
            )
            assertNull(result)
            assertTrue(peerFinished.await(1, TimeUnit.SECONDS))
            assertEquals("one decode on each side, no exception retry", 2, calls.get())
            assertEquals("decode-error:IllegalStateException", control.failure)
            awaitRetired(control)
        } finally { control.cancel(); executor.shutdownNow() }
    }

    private fun cancellationFixture(interruptParent: Boolean) {
        val executor = Executors.newFixedThreadPool(2)
        val calls = AtomicInteger()
        val entered = CountDownLatch(2)
        val finished = CountDownLatch(2)
        val parentDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val control = LoopBuildControl(::nowMs, maxBuildMs = 5_000L)
        fun decode(): LoopRegion? {
            calls.incrementAndGet()
            entered.countDown()
            try { CountDownLatch(1).await(10, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally { finished.countDown() }
            return null
        }
        val parent = Thread {
            try { decodeLoopRegions(executor, control, { _, _ -> }, ::decode, ::decode) }
            catch (error: Throwable) { failure.set(error) }
            finally { parentDone.countDown() }
        }
        try {
            parent.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            if (interruptParent) parent.interrupt() else control.cancel()
            assertTrue(parentDone.await(1, TimeUnit.SECONDS))
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue("cancellation must not be caught as retryable decode", failure.get() is CancellationException || control.isCancelled)
            assertEquals(2, calls.get())
            awaitRetired(control)
        } finally { control.cancel(); parent.interrupt(); parent.join(1_000); executor.shutdownNow() }
    }

    @Test fun cancelledFutureIsNotProofThatItsWorkerRetired() {
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val parentDone = CountDownLatch(1)
        val control = LoopBuildControl(::nowMs, maxBuildMs = 5_000L)
        fun decode(): LoopRegion? {
            entered.countDown()
            // Simulate a blocking codec/model that doesn't honor interruption.
            while (release.count > 0) {
                try { release.await(2, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { /* Still owns real work until explicitly released. */ }
            }
            return pcm
        }
        val parent = Thread {
            try { decodeLoopRegions(executor, control, { _, _ -> }, ::decode, ::decode) }
            catch (_: CancellationException) { }
            finally { parentDone.countDown() }
        }
        try {
            parent.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            control.cancel()
            assertTrue(parentDone.await(1, TimeUnit.SECONDS))
            assertFalse("cancel(true) completed the futures, not the decoder calls", control.isRetired)
            release.countDown()
            awaitRetired(control)
        } finally { release.countDown(); control.cancel(); parent.join(1_000); executor.shutdownNow() }
    }

    private fun awaitRetired(control: LoopBuildControl) {
        val until = System.nanoTime() + 1_000_000_000L
        while (!control.isRetired && System.nanoTime() < until) Thread.yield()
        assertTrue(control.isRetired)
    }
}
