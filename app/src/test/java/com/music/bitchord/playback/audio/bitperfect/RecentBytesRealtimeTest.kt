package com.music.bitchord.playback.audio.bitperfect

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentBytesRealtimeTest {
    @Test
    fun recordingPreservesSourcePositionLimitAndMark() {
        val source = ByteBuffer.allocateDirect(16)
        for (i in 0 until source.capacity()) source.put(i, i.toByte())
        source.limit(12).position(3).mark().position(9)
        val recent = RecentBytes(8)

        recent.record(source, 1, 7)

        assertEquals(9, source.position())
        assertEquals(12, source.limit())
        source.reset()
        assertEquals(3, source.position())
        val tail = ByteBuffer.allocate(7)
        recent.copyLast(7, tail)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7), tail.array())
    }

    @Test
    fun oversizedWritesAndWrapRetainExactlyTheNewestBytes() {
        val source = ByteBuffer.allocateDirect(64)
        for (i in 0 until source.capacity()) source.put(i, i.toByte())
        val recent = RecentBytes(8)
        recent.record(source, 4, 20)
        recent.record(source, 40, 5)

        val tail = ByteBuffer.allocate(8)
        recent.copyLast(8, tail)

        assertEquals(8, recent.size)
        assertArrayEquals(byteArrayOf(21, 22, 23, 40, 41, 42, 43, 44), tail.array())
    }

    @Test
    fun recordingReadOnlyInputAndClearingDoNotResurrectOldAudio() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5)).asReadOnlyBuffer()
        val recent = RecentBytes(4)
        recent.record(source, 0, 5)
        recent.clear()
        recent.record(source, 0, 2)

        val tail = ByteBuffer.allocate(2)
        recent.copyLast(2, tail)

        assertEquals(2, recent.size)
        assertArrayEquals(byteArrayOf(1, 2), tail.array())
        assertEquals(0, source.position())
    }

    @Test
    fun zeroCapacityAcceptsWritesWithoutConsumingInput() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
        val recent = RecentBytes(0)
        recent.record(source, 0, 3)
        recent.copyLast(0, ByteBuffer.allocate(0))
        assertEquals(0, recent.size)
        assertEquals(0, source.position())
    }
}
