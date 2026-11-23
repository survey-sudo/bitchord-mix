package com.music.bitchord.playback.smart

import java.io.File
import java.nio.file.Files
import kotlin.math.floor
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * AUTOMIX-V2(B): StemSeparator's contract without native code or ONNX Runtime: the 4-stem path,
 * the legacy 2-stem fallback (including regions longer than one umxhq window), null on total
 * failure, and the cache keying (exact, containment, probe mismatch, rate, disk round trip).
 */
class StemSeparatorTest {

    private lateinit var dir: File
    private val saved = Snapshot()

    private class Snapshot {
        val enabled = AutomixV2Stems.enabled
        val four = AutomixV2Stems.fourStemEnabled
        val legacy = AutomixV2Stems.legacyFallbackEnabled
        val memory = AutomixV2Stems.memoryCacheBytes
        val disk = AutomixV2Stems.diskCacheBytes
        val maxSeconds = AutomixV2Stems.maxRegionSeconds
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("stems-test").toFile()
        AutomixV2Stems.enabled = true
        AutomixV2Stems.fourStemEnabled = true
        AutomixV2Stems.legacyFallbackEnabled = true
        AutomixV2Stems.memoryCacheBytes = 64L * 1024 * 1024
        AutomixV2Stems.diskCacheBytes = 64L * 1024 * 1024
        AutomixV2Stems.maxRegionSeconds = 48.0
    }

    @AfterTest
    fun tearDown() {
        AutomixV2Stems.enabled = saved.enabled
        AutomixV2Stems.fourStemEnabled = saved.four
        AutomixV2Stems.legacyFallbackEnabled = saved.legacy
        AutomixV2Stems.memoryCacheBytes = saved.memory
        AutomixV2Stems.diskCacheBytes = saved.disk
        AutomixV2Stems.maxRegionSeconds = saved.maxSeconds
        dir.deleteRecursively()
    }

    /** Fixed fractions of the mixture: vocals .4, drums .3, bass .2, other .1. */
    private class FakeEngine(
        override val modelRate: Int,
        private val fail: Boolean = false,
        override val tag: String = "fake4",
    ) : StemEngine {
        var calls = 0
        override val baseConfidence: Float = 0.9f
        override fun separate(left: FloatArray, right: FloatArray, shouldStop: () -> Boolean): RawStems? {
            calls++
            if (fail) return null
            val gains = floatArrayOf(0.4f, 0.3f, 0.2f, 0.1f)
            return RawStems(
                Array(4) { k -> FloatArray(left.size) { left[it] * gains[k] } },
                Array(4) { k -> FloatArray(right.size) { right[it] * gains[k] } },
                residualRatio = 0.0,
            )
        }
        override fun release() {}
    }

    /** VocalTracker.separateBothWindows stand-in: vocals = half the mix, refuses > 22.3 s. */
    private class FakeLegacy(private val maxSeconds: Double = 22.3) : LegacyTwoStem {
        var calls = 0
        var fail = false
        override fun separate(
            left: FloatArray,
            right: FloatArray,
            rate: Double,
        ): Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>? {
            calls++
            if (fail || left.size > maxSeconds * rate) return null
            val vl = FloatArray(left.size) { left[it] * 0.5f }
            val vr = FloatArray(right.size) { right[it] * 0.5f }
            return (vl to vr) to (FloatArray(left.size) { left[it] - vl[it] } to FloatArray(right.size) { right[it] - vr[it] })
        }
    }

    /** Floors its length like the native resampler. */
    private fun linearResample(samples: FloatArray, from: Double, to: Double): FloatArray? {
        if (samples.isEmpty()) return null
        if (from == to) return samples.copyOf()
        val size = floor(samples.size * to / from).toInt()
        return FloatArray(size) { i ->
            val pos = i * from / to
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            samples[idx] * (1f - frac) + samples[(idx + 1).coerceAtMost(samples.size - 1)] * frac
        }
    }

    private fun region(seconds: Double, rate: Int, phase: Double = 0.0): Pair<FloatArray, FloatArray> {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { (0.5 * sin(it * 0.031 + phase) + 0.2 * sin(it * 0.17)).toFloat() } to
            FloatArray(n) { (0.4 * sin(it * 0.023 + phase) + 0.1 * sin(it * 0.29)).toFloat() }
    }

    private fun assertMixtureConsistent(set: StemSet, left: FloatArray, right: FloatArray) {
        assertEquals(left.size, set.frames)
        for ((dry, channel) in listOf(left to set.left, right to set.right)) {
            for (i in dry.indices) {
                var sum = 0f
                for (kind in StemKind.values()) sum += channel[kind]?.get(i) ?: 0f
                assertEquals(dry[i], sum, 1e-4f, "sample $i")
            }
        }
    }

    private fun newSeparator(engine: StemEngine?, legacy: LegacyTwoStem?, cache: StemCache = StemCache({ dir })) =
        StemSeparator(engine = { engine }, legacy = legacy, cache = cache, resample = ::linearResample)

    @Test
    fun fourStemPathReturnsConsistentFullSet() {
        val engine = FakeEngine(modelRate = 8_000)
        val (l, r) = region(3.0, 8_000)
        val set = assertNotNull(newSeparator(engine, FakeLegacy()).forTrack("t1").separate(l, r, 8_000, 12.0))
        assertEquals(4, set.stemCount)
        assertEquals(12.0, set.regionStartSec)
        assertEquals(l[100] * 0.3f, set.left.getValue(StemKind.DRUMS)[100], 1e-6f)
        assertMixtureConsistent(set, l, r)
    }

    @Test
    fun fourStemPathResamplesToTheModelRateAndBackExactlySized() {
        val engine = FakeEngine(modelRate = 44_100)
        val (l, r) = region(1.0, 48_000)
        val set = assertNotNull(newSeparator(engine, FakeLegacy()).forTrack("t1").separate(l, r, 48_000, 0.0))
        assertEquals(4, set.stemCount)
        for (kind in StemKind.values()) assertEquals(l.size, set.left.getValue(kind).size)
        assertMixtureConsistent(set, l, r)
    }

    @Test
    fun missingFourStemModelFallsBackToLegacyTwoStemAcrossWindows() {
        val legacy = FakeLegacy()
        // 30 s is wider than one umxhq window, so it must be split and crossfaded.
        val (l, r) = region(30.0, 4_000)
        val set = assertNotNull(newSeparator(null, legacy).forTrack("t2").separate(l, r, 4_000, 60.0))
        assertEquals(2, set.stemCount)
        assertTrue(legacy.calls >= 2, "legacy ran ${legacy.calls} windows")
        assertEquals(setOf(StemKind.VOCALS, StemKind.OTHER), set.left.keys)
        for (i in l.indices step 97) assertEquals(l[i] * 0.5f, set.left.getValue(StemKind.VOCALS)[i], 1e-5f)
        assertMixtureConsistent(set, l, r)
    }

    @Test
    fun failingFourStemEngineFallsBackAndTotalFailureIsNull() {
        val engine = FakeEngine(modelRate = 8_000, fail = true)
        val legacy = FakeLegacy()
        val (l, r) = region(2.0, 8_000)
        val set = assertNotNull(newSeparator(engine, legacy).forTrack("t3").separate(l, r, 8_000, 0.0))
        assertEquals(2, set.stemCount)
        assertEquals(1, engine.calls)

        legacy.fail = true
        assertNull(newSeparator(engine, legacy).forTrack("t4").separate(l, r, 8_000, 0.0))
        AutomixV2Stems.enabled = false
        assertNull(newSeparator(FakeEngine(8_000), legacy).forTrack("t5").separate(l, r, 8_000, 0.0))
    }

    @Test
    fun cacheKeysOnTrackRegionRateAndAudio() {
        val engine = FakeEngine(modelRate = 8_000)
        val separator = newSeparator(engine, FakeLegacy())
        val (l, r) = region(6.0, 8_000)
        val first = assertNotNull(separator.forTrack("song").separate(l, r, 8_000, 10.0))
        assertEquals(1, engine.calls)

        // Exact repeat: served from memory, the stored stems themselves.
        val again = assertNotNull(separator.forTrack("song").separate(l, r, 8_000, 10.0))
        assertEquals(1, engine.calls)
        assertSame(first.left[StemKind.VOCALS], again.left[StemKind.VOCALS])

        // A later, shorter window of the same decode (prefetch -> loop mix): sliced, no inference.
        val offset = 8_000 // 1 s in
        val subLeft = l.copyOfRange(offset, offset + 3 * 8_000)
        val subRight = r.copyOfRange(offset, offset + 3 * 8_000)
        val sub = assertNotNull(separator.forTrack("song").separate(subLeft, subRight, 8_000, 11.0))
        assertEquals(1, engine.calls)
        assertEquals(first.left.getValue(StemKind.BASS)[offset + 5], sub.left.getValue(StemKind.BASS)[5])
        assertMixtureConsistent(sub, subLeft, subRight)

        // Same window timing but different audio (a re-decode that landed elsewhere): recomputed.
        val (otherLeft, otherRight) = region(3.0, 8_000, phase = 1.0)
        separator.forTrack("song").separate(otherLeft, otherRight, 8_000, 11.0)
        assertEquals(2, engine.calls)

        // Another track, and another sample rate, never share entries.
        separator.forTrack("another").separate(l, r, 8_000, 10.0)
        assertEquals(3, engine.calls)
        separator.forTrack("song").separate(l, r, 16_000, 5.0)
        assertEquals(4, engine.calls)
    }

    @Test
    fun diskCacheSurvivesANewProcessAndStaysExactlyConsistent() {
        val (l, r) = region(4.0, 8_000)
        val firstEngine = FakeEngine(modelRate = 8_000)
        val original = assertNotNull(newSeparator(firstEngine, null).forTrack("disk").separate(l, r, 8_000, 3.5))

        // Fresh cache object = cold memory; the region must come back from disk.
        val secondEngine = FakeEngine(modelRate = 8_000)
        val restored = assertNotNull(newSeparator(secondEngine, null, StemCache({ dir })).forTrack("disk").separate(l, r, 8_000, 3.5))
        assertEquals(0, secondEngine.calls)
        assertEquals(4, restored.stemCount)
        for (i in l.indices step 13) {
            assertEquals(original.left.getValue(StemKind.VOCALS)[i], restored.left.getValue(StemKind.VOCALS)[i], 1e-4f)
        }
        assertMixtureConsistent(restored, l, r)

        // A cached 2-stem result does not stand in once a 4-stem model exists.
        val legacyOnly = newSeparator(null, FakeLegacy(), StemCache({ dir }))
        val (l2, r2) = region(2.0, 8_000, phase = 2.0)
        assertEquals(2, assertNotNull(legacyOnly.forTrack("upgrade").separate(l2, r2, 8_000, 0.0)).stemCount)
        val upgraded = FakeEngine(modelRate = 8_000)
        val four = assertNotNull(newSeparator(upgraded, FakeLegacy(), StemCache({ dir })).forTrack("upgrade").separate(l2, r2, 8_000, 0.0))
        assertEquals(4, four.stemCount)
        assertEquals(1, upgraded.calls)
    }

    @Test
    fun diskCacheStaysWithinItsBudget() {
        AutomixV2Stems.diskCacheBytes = 400_000L // ~2 regions of 1 s at 8 kHz (3 stems x 2 ch x 16 bit)
        val engine = FakeEngine(modelRate = 8_000)
        val separator = newSeparator(engine, null)
        repeat(6) { i ->
            val (l, r) = region(1.0, 8_000, phase = i.toDouble())
            separator.forTrack("budget$i").separate(l, r, 8_000, 0.0)
        }
        val total = dir.listFiles()!!.filter { it.name.endsWith(".stm") }.sumOf { it.length() }
        assertTrue(total <= 400_000L, "disk cache holds $total bytes")
    }
}
