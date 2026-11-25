package com.music.bitchord.playback.loopmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Latches pause actual guarded renderer calls; no JNI, sleeps, or timing-based race assertions. */
class NativeRendererLifetimeTest {
    @Test
    fun closeRejectsNewCallsAndDestroysOffAudioOnlyAfterEveryUserReturns() {
        val renderEntered = CountDownLatch(1)
        val queryEntered = CountDownLatch(1)
        val releaseRender = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val renderDone = CountDownLatch(1)
        val queryDone = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val destroyCount = AtomicInteger()
        val calls = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val destroyThread = AtomicReference<Thread?>()
        val lifetime = NativeRendererRetirement.create({ 77L }) {
            assertEquals(77L, it)
            destroyCount.incrementAndGet()
            destroyThread.set(Thread.currentThread())
            destroyed.countDown()
        }
        val renderer = LoopMixRenderer(48_000, object : LoopMixRenderer.Readout {
            override fun firstFrame() = 0L.also { calls.incrementAndGet() }
            override fun exitGateViolations() = 0.also { calls.incrementAndGet() }
            override fun heard(index: Int): Double {
                calls.incrementAndGet()
                queryEntered.countDown()
                await(releaseQuery)
                return 1.0
            }
            override fun render(frame: Long, interleaved: FloatArray, offset: Int, frames: Int) {
                calls.incrementAndGet()
                renderEntered.countDown()
                await(releaseRender)
            }
        }, lifetime)
        val audio = Thread({
            try { renderer.render(0, FloatArray(8), 0, 4) }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { renderDone.countDown() }
        }, "lifetime-test-audio")
        val query = Thread({
            try { renderer.heard(0) }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { queryDone.countDown() }
        }, "lifetime-test-query")
        try {
            audio.start()
            await(renderEntered)
            query.start()
            await(queryEntered)
            renderer.close()
            renderer.close()
            assertFalse(lifetime.isOpen)
            assertEquals(0L, lifetime.acquire())
            assertEquals(0, destroyCount.get())
            assertEquals(1L, destroyed.count)
            assertAllCallsRejected(renderer)
            assertEquals(2, calls.get())
            releaseRender.countDown()
            await(renderDone)
            assertEquals("the query still owns a lease", 0, destroyCount.get())
            releaseQuery.countDown()
            await(queryDone)
            await(destroyed)
            assertEquals(1, destroyCount.get())
            assertNotEquals(audio, destroyThread.get())
            assertNotEquals(query, destroyThread.get())
            assertNotEquals(Thread.currentThread(), destroyThread.get())
            assertEquals("loopmix-retire", requireNotNull(destroyThread.get()).name)
            assertAllCallsRejected(renderer)
            assertEquals(2, calls.get())
            assertNull(failure.get())
        } finally {
            releaseRender.countDown()
            releaseQuery.countDown()
            renderer.close()
            if (audio.isAlive) audio.join(5_000)
            if (query.isAlive) query.join(5_000)
        }
    }

    @Test
    fun renderExceptionReleasesTheLastRetiredLeaseInFinally() {
        val entered = CountDownLatch(1)
        val failNow = CountDownLatch(1)
        val done = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val destroyCount = AtomicInteger()
        val caught = AtomicReference<Throwable?>()
        val expected = IllegalStateException("test render failure")
        val lifetime = NativeRendererRetirement.create({ 81L }) {
            destroyCount.incrementAndGet()
            destroyed.countDown()
        }
        val renderer = LoopMixRenderer(48_000, object : LoopMixRenderer.Readout {
            override fun firstFrame() = 0L
            override fun heard(index: Int) = 0.0
            override fun exitGateViolations() = 0
            override fun render(frame: Long, interleaved: FloatArray, offset: Int, frames: Int) {
                entered.countDown()
                await(failNow)
                throw expected
            }
        }, lifetime)
        val audio = Thread({
            try { renderer.render(0, FloatArray(8), 0, 4) }
            catch (error: Throwable) { caught.set(error) }
            finally { done.countDown() }
        }, "lifetime-test-throw")
        try {
            audio.start()
            await(entered)
            renderer.close()
            assertEquals(0, destroyCount.get())
            failNow.countDown()
            await(done)
            await(destroyed)
            assertEquals(expected, caught.get())
            assertEquals(1, destroyCount.get())
            assertEquals(0L, lifetime.acquire())
            renderer.close()
        } finally {
            failNow.countDown()
            renderer.close()
            if (audio.isAlive) audio.join(5_000)
        }
    }

    @Test
    fun retirementBeforeAcquireNeverEntersNativeAndReclaimsExactlyOnce() {
        val destroyed = CountDownLatch(1)
        val count = AtomicInteger()
        val lifetime = NativeRendererRetirement.create({ 91L }) {
            count.incrementAndGet()
            destroyed.countDown()
        }
        lifetime.retire()
        lifetime.retire()
        assertEquals(0L, lifetime.acquire())
        await(destroyed)
        assertEquals(1, count.get())
        assertFalse(lifetime.destroyIfQuiescent())
    }

    @Test
    fun legacyAtomicExchangeStillHasOneWinnerButIsNotTheLifetimeProtocol() {
        val handle = AtomicLong(77L)
        val destroys = AtomicInteger()
        val destroy: (Long) -> Unit = { destroys.incrementAndGet() }
        LoopMixRenderer.closeNativeHandle(handle, destroy)
        LoopMixRenderer.closeNativeHandle(handle, destroy)
        assertEquals(0L, handle.get())
        assertEquals(1, destroys.get())
    }

    /** Every handle-consuming wrapper must reject a retired lease before JNI/readout dispatch. */
    private fun assertAllCallsRejected(renderer: LoopMixRenderer) {
        val out = FloatArray(8)
        renderer.render(0L, out, 0, 4)
        renderer.setDeck(0, LoopMixRenderer.DeckSetup(out, out, 0.0, 0L, 0L, 0.0,
            doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0), false, 1.0, 0.4, 1f))
        renderer.setEnvelope(0, 0, doubleArrayOf(0.0), floatArrayOf(0f))
        renderer.setHighQuality(true)
        renderer.setEcho(0, 0.5, 0.5f, 0.5f)
        renderer.setFlanger(0, 1.0, 0.001, 0.5f, 0.5f)
        renderer.setIsolate(0, true)
        renderer.prepare()
        renderer.setFadeOut(0, 1.0)
        renderer.setLoopRoll(0, true, 0.0, 0.5, 1.0, 2.0)
        renderer.setReverb(0, 1f, 0.5f, 0.5f)
        renderer.setSvfQ(0, 0.7f)
        assertFalse(renderer.setDeckStems(0, null))
        renderer.setPhaser(0, 1.0, 0.5f, 0.5f)
        renderer.setGate(0, 0.5, 1f)
        renderer.setRiser(0, 0.5, 2.0)
        renderer.setPump(0, 0.5)
        renderer.setBrake(0, 0.0, 1.0)
        renderer.setLoudnessMatch(true, -14f)
        renderer.setLoudnessDropWindow(0.0, 1.0, 1f)
        renderer.setTruePeakLimiter(true)
        renderer.setLoopSeamMs(0, 4.0)
        renderer.setLoopRollEx(0, true, 0.0, 0.5, 1.0, 2.0, 20f, 200f, 0f, false, null)
        renderer.setEchoEx(0, 0.5, 0.5f, 0.5f, 100f, 8_000f, true)
        renderer.setFlangerEx(0, 1.0, 0.001, 0.5f, 0.5f, 0f)
        renderer.setPhaserEx(0, 1.0, 0.5f, 0.5f, 4, 0f)
        renderer.setRiserEx(0, 0.5, 2.0, 100f, 8_000f, 0.5f, 1f, false, 0.0, 0.0)
        renderer.setImpact(0, 0.5, -14f, 60f, 100.0)
        renderer.setGateEx(0, 0.5, 1f, 2f, 0f, 0.0, 1.0)
        renderer.setBrakeEx(0, 0.0, 1.0, 0.1f, 8_000f, 200f, 10.0)
        renderer.setTapeStop(0, 0.0, 1.0, 0f, 1f, 8_000f, 200f, 10.0)
        renderer.setSpinback(0, 0.0, 1.0, 2.0, 0.0)
        renderer.setSweep(0, true, 20f, 200f, 0.7f, 0.7f, 0.0, 1.0, true)
        renderer.setTruePeakCeilingDb(-1f)
        renderer.setCaptureEnabled(false)
        assertEquals(0L, renderer.firstFrame())
        assertEquals(0.0, renderer.heard(0), 0.0)
        assertEquals(0, renderer.exitGateViolations())
        assertEquals(-120.0, renderer.shortTermLufs(0), 0.0)
        assertEquals(0f, renderer.loudnessGuardDb(), 0f)
        assertEquals(0L, renderer.capturedFrames())
        assertEquals(0, renderer.readCapturedAudio(out, 4))
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("latch was not signaled", latch.await(5, TimeUnit.SECONDS))
    }
}
