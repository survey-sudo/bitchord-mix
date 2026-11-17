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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

@androidx.annotation.OptIn(UnstableApi::class)
class LoopGeneratedTimestampTest {
    private data class Write(val buffer: ByteBuffer, val pts: Long, val position: Int, val bytes: Int)

    /** Consumes exactly the recorded prefix, returning false while bytes remain. */
    private class FakeAudioSink {
        val limits = ArrayDeque<Int>()
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
                    !buffer.hasRemaining()
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
        assertEquals(TAKEOVER_US + expectedOffsetUs, fake.writes[0].pts)
        assertEquals(TAKEOVER_US + duration, field(sink, "loopNextUs"))
        assertEquals(TAKEOVER_US + duration, field(sink, "processedEndUs"))

        fake.limits.add(Int.MAX_VALUE)
        fake.limits.add(8)
        assertFalse(sink.handleBuffer(decoder, TAKEOVER_US, 1))
        assertSame(fake.writes[0].buffer, fake.writes[1].buffer)
        assertEquals(8, fake.writes[1].position)
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
        renderer.close()
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
