package com.music.bitchord.djmix.analysis

import com.music.bitchord.djmix.analysis.cli.WavReader
import com.music.bitchord.djmix.analysis.cli.calculateStemWindow
import com.music.bitchord.playback.smart.StemKind
import com.music.bitchord.playback.smart.StemProvider
import com.music.bitchord.playback.smart.StemSet
import kotlin.test.*

class StemSeparatorJvmTest {
    @Test fun actualProviderReceivesOnlyAlignedBoundedWindow() {
        val pcm = WavReader.Stereo(FloatArray(65_000) { it / 65_000f }, FloatArray(65_000) { -it / 65_000f }, 1_000)
        val window = calculateStemWindow(pcm.frames, pcm.sampleRate, 35_000, 40_000, 2_000)
        var called = 0
        val provider = StemProvider { left, right, rate, start ->
            called++
            assertEquals(9_000, left.size); assertEquals(33.0, start); assertEquals(1_000, rate)
            assertEquals(pcm.left[33_000], left.first()); assertEquals(pcm.right[41_999], right.last())
            StemSet(rate, left.size, start, StemKind.entries.associateWith { left.copyOf() },
                StemKind.entries.associateWith { right.copyOf() }, 4, 0.9f)
        }
        val result = StemSeparatorJvm.separateWindowUsing(pcm, window.firstFrame, window.endFrame, provider)
        assertEquals(1, called); assertEquals(4, result.size); assertTrue(result.values.all { it.frames == 9_000 })
    }

    @Test fun paddingIsBoundedButMissingRequiredCoreAudioIsRefused() {
        val atEnd = calculateStemWindow(70_000, 1_000, 68_000, 70_000, 4_000)
        assertEquals(64_000, atEnd.firstFrame); assertEquals(70_000, atEnd.endFrame)
        val cap = calculateStemWindow(70_000, 1_000, 10_000, 57_000, 5_000)
        assertEquals(48_000, cap.endFrame - cap.firstFrame)
        assertTrue(cap.firstFrame <= 10_000 && cap.endFrame >= 57_000)
        for ((from, to) in listOf(68_000L to 73_000L, 0L to 49_000L, 5L to 5L)) {
            assertFailsWith<IllegalArgumentException> { calculateStemWindow(70_000, 1_000, from, to, 1_000) }
        }
    }

    @Test fun nonIntegerMillisecondFramesCoverCoreAndNeverOverpad() {
        val window = calculateStemWindow(44_100 * 60, 44_100, 1_001, 2_003, 0)
        assertEquals(44_144, window.firstFrame); assertEquals(88_333, window.endFrame)
        val nearStart = calculateStemWindow(100_000, 1_000, 10, 47_510, 100)
        assertTrue(nearStart.firstFrame >= 0 && nearStart.firstFrame >= 10 - 100)
        assertTrue(nearStart.endFrame <= 47_510 + 100)
        val enormousPadding = calculateStemWindow(100_000, 1_000, 20_000, 21_000, Long.MAX_VALUE)
        assertTrue(enormousPadding.endFrame - enormousPadding.firstFrame <= 48_000)
    }

    @Test fun missingOrMisalignedProductionStemsFailInsteadOfReturningFakeAudio() {
        val pcm = WavReader.Stereo(FloatArray(1_000), FloatArray(1_000), 1_000)
        assertFailsWith<IllegalStateException> { StemSeparatorJvm.separateWindowUsing(pcm, 0, 1_000, StemProvider { _, _, _, _ -> null }) }
        assertFailsWith<IllegalArgumentException> {
            StemSeparatorJvm.separateWindowUsing(pcm, 0, 1_000, StemProvider { _, _, _, _ -> StemSet(1_000, 999, 0.0, emptyMap(), emptyMap(), 4, 0.9f) })
        }
    }
}
