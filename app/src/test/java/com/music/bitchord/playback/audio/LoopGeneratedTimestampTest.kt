package com.music.bitchord.playback.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

@androidx.annotation.OptIn(UnstableApi::class)
class LoopGeneratedTimestampTest {
    private data class Write(val buffer: ByteBuffer, val pts: Long, val position: Int, val bytes: Int)

    /** Consumes the recorded prefix; return/throw overrides exercise independent delegate outcomes. */
    private class FakeAudioSink {
        val limits = ArrayDeque<Int>()
        val results = ArrayDeque<Boolean>()
        var failureAfterWrite: RuntimeException? = null
        val writes = mutableListOf<Write>()
        val samples = mutableListOf<Float>()
        var positionUs = 0L
        val sink: AudioSink = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "supportsFormat" -> true
                "getCurrentPositionUs" -> positionUs
                "handleBuffer" -> {
                    val buffer = args!![0] as ByteBuffer
                    val count = minOf(buffer.remaining(), if (limits.isEmpty()) Int.MAX_VALUE else limits.removeFirst())
                    writes += Write(buffer, args[1] as Long, buffer.position(), count)
                    val end = buffer.position() + count
                    while (buffer.position() < end) samples += buffer.order(ByteOrder.nativeOrder()).float
                    failureAfterWrite?.let { failure ->
                        failureAfterWrite = null
                        throw failure
                    }
                    if (results.isEmpty()) !buffer.hasRemaining() else results.removeFirst()
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as AudioSink
    }

    private class RenderReadout : LoopMixRenderer.Readout {
        val calls = mutableListOf<Pair<Long, Int>>()
        override fun firstFrame() = 0L
        override fun heard(index: Int) = 0.0
        override fun exitGateViolations() = 0
        override fun render(frame: Long, interleaved: FloatArray, offset: Int, frames: Int) {
            calls += frame to frames
            repeat(frames) { i ->
                val value = sample(frame + i)
                interleaved[(offset + i) * 2] = value
                interleaved[(offset + i) * 2 + 1] = -value
            }
        }
    }

    @Test fun `inserted alignment offset survives next outgoing generation`() = exercise(-480, 10_000L)
    @Test fun `dropped alignment offset survives next outgoing generation`() = exercise(480, -10_000L)
    @Test fun `zero alignment preserves generated timestamp sequence`() = exercise(0, 0L)

    private fun exercise(shiftFrames: Long, expectedOffsetUs: Long) {
        val fake = FakeAudioSink()
        val chain = DspChain()
        val sink = PrecisionAudioSink(fake.sink, chain, enableFloatOutput = true, isAudible = { false })
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT).setChannelCount(2).setSampleRate(RATE).build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
        assertEquals(PcmEncoding.PCM_FLOAT, sink.targetOutputEncoding)

        // Establish the offset through production incoming alignment, then retain
        // this same sink through disarm and the next outgoing arm (no flush/reset).
        val port = chain.transition.loop
        port.armIncoming(LoopMixPort.InArm(10_000_000L))
        port.requestShift(shiftFrames)
        assertTrue(sink.handleBuffer(input(1024), 1_000_000L, 1))
        assertEquals(0L, port.pendingShift.get())
        assertEquals(expectedOffsetUs, field(sink, "delegateOffsetUs"))
        port.disarm()
        assertTrue(sink.handleBuffer(input(32), 1_500_000L, 1))
        assertEquals(expectedOffsetUs, field(sink, "delegateOffsetUs"))

        val readout = RenderReadout()
        val renderer = LoopMixRenderer(RATE, readout)
        port.armOutgoing(LoopMixPort.OutArm(renderer, TAKEOVER_US, RATE, 0L))
        fake.writes.clear()
        fake.samples.clear()
        val decoder = input(1024)
        val duration = Util.sampleCountToDurationUs(1024L, RATE)

        // Partial takeover block: generated path must first drain the SAME buffer
        // with its saved shifted PTS, then render one new block and stall again.
        fake.limits.add(8)
        assertFalse(sink.handleBuffer(decoder, TAKEOVER_US, 1))
        assertEquals(1024 * 8, decoder.position())
        assertEquals(listOf(0L to 1024), readout.calls)
        assertTrue("tookOver remains the logical splice marker", port.tookOver)
        assertEquals(LoopMixPort.TakeoverSpliceBufferStatus.UNRESOLVED, port.takeoverSpliceBufferStatus)
        assertEquals(TAKEOVER_US + expectedOffsetUs, fake.writes[0].pts)
        assertEquals(TAKEOVER_US + duration, field(sink, "loopNextUs"))
        assertEquals(TAKEOVER_US + duration, field(sink, "processedEndUs"))

        fake.limits.add(Int.MAX_VALUE)
        fake.limits.add(8)
        assertFalse(sink.handleBuffer(decoder, TAKEOVER_US, 1))
        assertSame(fake.writes[0].buffer, fake.writes[1].buffer)
        assertEquals(8, fake.writes[1].position)
        assertEquals(
            LoopMixPort.TakeoverSpliceBufferStatus.DELEGATE_FULLY_CONSUMED,
            port.takeoverSpliceBufferStatus,
        )
        assertEquals(fake.writes[0].pts, fake.writes[1].pts)
        assertEquals(TAKEOVER_US + duration + expectedOffsetUs, fake.writes[2].pts)
        assertEquals(listOf(0L to 1024, 1024L to 1024), readout.calls)
        assertEquals(TAKEOVER_US + duration * 2, field(sink, "loopNextUs"))
        assertEquals(TAKEOVER_US + duration * 2, field(sink, "processedEndUs"))

        // Generated retry also keeps its PTS exactly once, without another offset.
        fake.limits.add(Int.MAX_VALUE)
        fake.limits.add(8)
        assertFalse(sink.handleBuffer(decoder, TAKEOVER_US, 1))
        assertSame(fake.writes[2].buffer, fake.writes[3].buffer)
        assertEquals(8, fake.writes[3].position)
        assertEquals(fake.writes[2].pts, fake.writes[3].pts)
        assertEquals(TAKEOVER_US + duration * 2 + expectedOffsetUs, fake.writes[4].pts)
        assertEquals(listOf(0L to 1024, 1024L to 1024, 2048L to 1024), readout.calls)
        assertEquals(1024 * 8, decoder.position()) // generated output consumes no decoder input
        assertEquals(TAKEOVER_US + duration * 3, field(sink, "loopNextUs"))
        assertEquals(TAKEOVER_US + duration * 3, field(sink, "processedEndUs"))
        assertEquals(expectedOffsetUs, field(sink, "delegateOffsetUs"))

        // Accepted PCM covers each rendered frame exactly once across retries.
        assertEquals((2048 + 1) * 2, fake.samples.size)
        repeat(2048 + 1) { frame ->
            assertEquals(sample(frame.toLong()), fake.samples[2 * frame], 0f)
            assertEquals(-sample(frame.toLong()), fake.samples[2 * frame + 1], 0f)
        }
        fake.positionUs = TAKEOVER_US + expectedOffsetUs
        assertEquals(TAKEOVER_US, sink.getCurrentPositionUs(false))
        assertEquals(duration * 3, chain.transition.leadUs)
        val previousGeneration = port.generation
        port.armOutgoing(LoopMixPort.OutArm(renderer, TAKEOVER_US, RATE, 0L))
        assertNull("new arm clears the previous generation's status", port.takeoverSpliceBufferStatus)
        port.publishTakeoverSpliceBufferStatus(
            previousGeneration,
            LoopMixPort.TakeoverSpliceBufferStatus.DELEGATE_FULLY_CONSUMED,
        )
        assertNull("old-generation publications are ignored", port.takeoverSpliceBufferStatus)
        val armedGeneration = port.generation
        port.disarm()
        assertNull(port.takeoverSpliceBufferStatus)
        port.publishTakeoverSpliceBufferStatus(
            armedGeneration,
            LoopMixPort.TakeoverSpliceBufferStatus.DELEGATE_FULLY_CONSUMED,
        )
        assertNull("stale generation must not revive its acceptance status", port.takeoverSpliceBufferStatus)
        renderer.close()
    }

    @Test fun `takeover splice remains unresolved when delegate never accepts bytes`() = withTakeover { fake, sink, port, decoder ->
        fake.limits.add(0)
        assertFalse(sink.handleBuffer(decoder, TAKEOVER_US, 1))
        assertTrue("tookOver is not delegate acceptance", port.tookOver)
        assertEquals(LoopMixPort.TakeoverSpliceBufferStatus.UNRESOLVED, port.takeoverSpliceBufferStatus)

        fake.limits.add(0)
        assertFalse(sink.handleBuffer(decoder, TAKEOVER_US, 1))
        assertSame(fake.writes[0].buffer, fake.writes[1].buffer)
        assertEquals(LoopMixPort.TakeoverSpliceBufferStatus.UNRESOLVED, port.takeoverSpliceBufferStatus)
        port.disarm()
        assertNull(port.takeoverSpliceBufferStatus)
    }

    @Test fun `delegate true with bytes remaining stays unresolved until exact splice drains`() = withTakeover { fake, sink, port, decoder ->
        fake.limits.add(8)
        fake.results.add(true)
        assertFalse(sink.handleBuffer(decoder, TAKEOVER_US, 1))
        assertTrue(port.tookOver)
        assertEquals(LoopMixPort.TakeoverSpliceBufferStatus.UNRESOLVED, port.takeoverSpliceBufferStatus)

        fake.limits.add(Int.MAX_VALUE)
        fake.limits.add(0)
        assertFalse(sink.handleBuffer(decoder, TAKEOVER_US, 1))
        assertSame(fake.writes[0].buffer, fake.writes[1].buffer)
        assertEquals(8, fake.writes[1].position)
        assertEquals(LoopMixPort.TakeoverSpliceBufferStatus.DELEGATE_FULLY_CONSUMED, port.takeoverSpliceBufferStatus)
    }

    @Test fun `delegate false after draining splice cannot be upgraded by reused buffer`() = withTakeover { fake, sink, port, decoder ->
        fake.results.add(false)
        assertFalse(sink.handleBuffer(decoder, TAKEOVER_US, 1))
        assertTrue(port.tookOver)
        assertEquals(1024 * 8, fake.writes.single().bytes)
        assertFalse(fake.writes.single().buffer.hasRemaining())
        assertEquals(LoopMixPort.TakeoverSpliceBufferStatus.UNRESOLVED, port.takeoverSpliceBufferStatus)

        fake.limits.add(Int.MAX_VALUE)
        fake.limits.add(0)
        assertFalse(sink.handleBuffer(decoder, TAKEOVER_US, 1))
        assertSame(fake.writes[0].buffer, fake.writes[1].buffer)
        assertEquals(1024 * 8, fake.writes[1].bytes)
        assertEquals(LoopMixPort.TakeoverSpliceBufferStatus.UNRESOLVED, port.takeoverSpliceBufferStatus)
    }

    @Test fun `delegate draining splice then throwing cannot be upgraded on retry`() = withTakeover { fake, sink, port, decoder ->
        val failure = IllegalStateException("delegate failed after consuming splice")
        fake.failureAfterWrite = failure
        try {
            sink.handleBuffer(decoder, TAKEOVER_US, 1)
            fail("delegate failure must propagate")
        } catch (expected: IllegalStateException) {
            assertSame(failure, expected)
        }
        assertTrue(port.tookOver)
        assertEquals(1024 * 8, fake.writes.single().bytes)
        assertFalse(fake.writes.single().buffer.hasRemaining())
        assertEquals(LoopMixPort.TakeoverSpliceBufferStatus.UNRESOLVED, port.takeoverSpliceBufferStatus)

        // A successful generated write reuses the same ByteBuffer object but
        // is no longer evidence about the exhausted, throwing splice offer.
        fake.limits.add(Int.MAX_VALUE)
        fake.limits.add(0)
        assertFalse(sink.handleBuffer(decoder, TAKEOVER_US, 1))
        assertSame(fake.writes[0].buffer, fake.writes[1].buffer)
        assertEquals(1024 * 8, fake.writes[1].bytes)
        assertEquals(LoopMixPort.TakeoverSpliceBufferStatus.UNRESOLVED, port.takeoverSpliceBufferStatus)
    }

    private fun withTakeover(test: (FakeAudioSink, PrecisionAudioSink, LoopMixPort, ByteBuffer) -> Unit) {
        val fake = FakeAudioSink()
        val chain = DspChain()
        val sink = PrecisionAudioSink(fake.sink, chain, enableFloatOutput = true, isAudible = { false })
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT).setChannelCount(2).setSampleRate(RATE).build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
        val port = chain.transition.loop
        val renderer = LoopMixRenderer(RATE, RenderReadout())
        port.armOutgoing(LoopMixPort.OutArm(renderer, TAKEOVER_US, RATE, 0L))
        try {
            test(fake, sink, port, input(1024))
        } finally {
            renderer.close()
        }
    }

    private fun field(sink: PrecisionAudioSink, name: String): Long =
        PrecisionAudioSink::class.java.getDeclaredField(name).apply { isAccessible = true }.getLong(sink)

    private fun input(frames: Int): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder()).apply {
            repeat(frames * 2) { putFloat(0.125f) }
            flip()
        }

    companion object {
        private const val RATE = 48000
        private const val TAKEOVER_US = 2_000_000L
        private fun sample(frame: Long): Float = ((frame % 32) + 1).toFloat() / 128f
    }
}
