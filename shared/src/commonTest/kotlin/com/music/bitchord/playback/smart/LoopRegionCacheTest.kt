package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * djmix14 LoopRegionCache: hit, sub-slice, miss, LRU eviction.
 */
class LoopRegionCacheTest {

    @Test fun exactRegionHit() {
        val c = LoopRegionCache()
        val sr = 48000
        val left = FloatArray(10) { it.toFloat() }
        val right = FloatArray(10) { (it * 2).toFloat() }
        c.put("t1", sr, 10.0, 10.0 + 10.0 / sr, left, right)
        val s = c.get("t1", sr, 10.0, 10.0 + 10.0 / sr)
        assertNotNull(s)
        assertEquals(10, s.left.size)
    }

    @Test fun coveringSubSlice() {
        val c = LoopRegionCache()
        val sr = 1000 // 1 kHz for integer math: 40 s = 40_000 samples
        val left = FloatArray(40_000) { it.toFloat() }
        val right = FloatArray(40_000) { -it.toFloat() }
        c.put("t1", sr, 140.0, 180.0, left, right)
        // Request [148,172] is covered by [140,180].
        val s = c.get("t1", sr, 148.0, 172.0)
        assertNotNull(s, "covering sub-slice must hit")
        assertEquals(24_000, s.left.size, "148..172 at 1kHz = 24k samples")
        assertEquals(148.0, s.startSec)
        assertEquals(172.0, s.endSec)
        // First sliced sample equals original offset 8000.
        assertEquals(8000f, s.left[0])
        assertTrue(c.contains("t1", sr, 148.0, 172.0))
    }

    @Test fun missOutOfRangeAndSampleRate() {
        val c = LoopRegionCache()
        val sr = 48000
        val left = FloatArray(4800) { 0f }
        val right = FloatArray(4800) { 0f }
        c.put("t1", sr, 10.0, 10.1, left, right)
        assertNull(c.get("t1", sr, 10.2, 10.3), "out of range must miss")
        assertNull(c.get("t1", 44100, 10.0, 10.1), "sample-rate mismatch must miss")
        assertNull(c.get("other", sr, 10.0, 10.1), "track miss")
    }

    @Test fun lruByteCapEviction() {
        // 4-entry cap: fifth put evicts eldest.
        val c = LoopRegionCache(maxEntries = 4, maxBytes = 24L * 1024 * 1024)
        val sr = 48000
        fun entry(id: String) {
            val l = FloatArray(100) { 0f }
            val r = FloatArray(100) { 0f }
            c.put(id, sr, 0.0, 100.0 / sr, l, r)
        }
        entry("a"); entry("b"); entry("c"); entry("d")
        assertEquals(4, c.size())
        // Touch a (access order): get makes a most-recent.
        c.get("a", sr, 0.0, 100.0 / sr)
        entry("e")
        assertEquals(4, c.size(), "cap held at 4")
        // b was eldest after touch, so b evicted.
        assertNull(c.get("b", sr, 0.0, 100.0 / sr))
        assertNotNull(c.get("a", sr, 0.0, 100.0 / sr))
        assertNotNull(c.get("e", sr, 0.0, 100.0 / sr))
    }
}
