package com.music.bitchord.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.playback.TransitionFilterProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class TransitionFilterBufferReuseTest {
    @Test
    fun largeParkedInputIsBitExactAcrossReusedBoundedOutput() {
        val processor = TransitionFilterProcessor()
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val frames = AudioBlock.DEFAULT_CAPACITY_FRAMES * 3 + 17
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (sample in 0 until frames * 2) input.putShort(sample.toShort())
        input.flip()
        var storage: ByteBuffer? = null
        var samplesRead = 0
        while (input.hasRemaining()) {
            val before = input.position()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.nativeOrder())
            assertTrue(input.position() > before)
            if (storage == null) storage = output else assertSame(storage, output)
            assertTrue(output.remaining() <= AudioBlock.DEFAULT_CAPACITY_FRAMES * 4)
            while (output.hasRemaining()) assertEquals((samplesRead++).toShort(), output.short)
        }
        assertEquals(frames * 2, samplesRead)
    }

    @Test
    fun directDspConfigureAlsoPreparesTheLegacyPcmEntryPoint() {
        val processor = TransitionFilterProcessor()
        processor.configure(44100, 2)
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putShort(1).putShort(-2).putShort(3).putShort(-4).flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(8, output.remaining())
        assertEquals(1.toShort(), output.short)
        assertEquals((-2).toShort(), output.short)
        assertEquals(3.toShort(), output.short)
        assertEquals((-4).toShort(), output.short)
    }

    @Test
    fun pendingFormatDoesNotOverwritePreviousOutputBeforeFlush() {
        val processor = TransitionFilterProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(123).putShort(-456).flip()
        processor.queueInput(input)
        processor.configure(AudioProcessor.AudioFormat(96000, 8, C.ENCODING_PCM_16BIT))
        val previous = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(4, previous.remaining())
        assertEquals(123.toShort(), previous.short)
        assertEquals((-456).toShort(), previous.short)
    }

    @Test
    fun anEmptyStreamEndsWithoutPollingOutputAfterPreparation() {
        val processor = TransitionFilterProcessor()
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.queueEndOfStream()
        assertTrue(processor.isEnded)
    }
}
