package com.music.bitchord.djmix.analysis

import com.music.bitchord.playback.smart.AudioMath
import com.music.bitchord.playback.smart.AnalysisRegion
import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.MelSpectrogram
import kotlin.test.*

class AnalysisPipelineDeterminismTest {
    private val rate = 11_025
    private fun audio(seconds: Int = 8) = FloatArray(rate * seconds) { (0.2 * kotlin.math.sin(2 * Math.PI * 220 * it / rate)).toFloat() }
    private fun grid() = BeatTracker.Grid(List(16) { it * 0.5 }, List(4) { it * 2.0 }, 120.0, 0.5, 0.0, 0.9)

    @Test fun productionNativeFrontendAndFeaturesAreActuallyExecuted() {
        assertTrue(TrackFeatures.available, "Host tests must load the real native analyzer")
        val samples = audio()
        val feature = assertNotNull(TrackFeatures.analyze(samples, 8.0))
        assertTrue(feature.energyCurve.isNotEmpty())
        val spec = assertNotNull(MelSpectrogram.compute(assertNotNull(MelSpectrogram.resample(samples, rate.toDouble()))))
        assertEquals(128, spec.mels)
        assertTrue(spec.values.all { it.isFinite() })
    }

    @Test fun bothInjectedInferenceBoundariesReceiveTheSameProductionMelObject() {
        var seen: MelSpectrogram.Spectrogram? = null
        val beat = object : BeatBackend {
            override fun track(pcm: FloatArray, sampleRate: Double, offsetSeconds: Double): BeatTracker.Grid? = error("unexpected PCM inference")
            override fun track(spectrogram: MelSpectrogram.Spectrogram, offsetSeconds: Double): BeatTracker.Grid {
                seen = spectrogram; return grid()
            }
        }
        val vocal = object : VocalBackend {
            override fun track(pcm: FloatArray, sampleRate: Double): FloatArray? = error("unexpected PCM inference")
            override fun track(spectrogram: MelSpectrogram.Spectrogram): FloatArray {
                assertSame(seen, spectrogram)
                return FloatArray(spectrogram.frames) { if (it >= 200) 0.4f else 0.1f }
            }
        }
        val result = AnalysisPipeline.analyze("seam", audio(), rate, beatBackend = beat, vocalBackend = vocal, nowMs = { 1000L })
        assertEquals(3, result.schema)
        assertEquals(64, result.contentHash.length)
        assertTrue(result.vocalPresence50fps[250] > result.vocalPresence50fps[0])
        assertTrue(result.energyCurve.isNotEmpty())
        // Injected grid/presence verify orchestration only; this is not a model/audio parity claim.
    }

    @Test fun missingBackendsThermalAndNonFinitePcmNeverProduceReadySuccess() {
        assertFailsWith<IllegalStateException> { AnalysisPipeline.analyze("missing", audio(), rate) }
        assertFailsWith<IllegalArgumentException> { AnalysisPipeline.analyze("nan", floatArrayOf(Float.NaN), rate) }
        assertFailsWith<IllegalStateException> {
            AnalysisPipeline.analyze("hot", audio(), rate, thermalPolicy = object : ThermalPolicy { override fun isCool() = false })
        }
    }

    @Test fun productionGridSeamAndStructureAreDeterministic() {
        val snapped = AudioMath.snapGrid(grid(), List(16) { it * 0.5 + 0.01 })
        val seamed = AudioMath.seamBeats(listOf(AnalysisRegion(snapped, 8.0)))
        assertEquals(16, seamed.beats.size)
        val a = StructureAnalyzer.fromPcm("t", audio(), rate, 120.0, startedMs = 1L, now = { 1000L })
        val b = StructureAnalyzer.fromPcm("t", audio(), rate, 120.0, startedMs = 1L, now = { 1000L })
        assertEquals(a.sections, b.sections)
    }

    @Test fun structureTimeoutIsObservable() {
        assertFailsWith<com.music.bitchord.playback.smart.StructureAnalyzer.Timeout> {
            StructureAnalyzer.fromPcm("t", audio(), rate, 120.0, startedMs = 1L, now = { 100_000L })
        }
    }
}
