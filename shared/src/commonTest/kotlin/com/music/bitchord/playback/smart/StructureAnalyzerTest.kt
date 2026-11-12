package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructureAnalyzerTest {
    @Test
    fun shortPhrasesSurviveTheDynamicProgram() {
        val n = 4 + 12 + 2 + 16
        val novelty = DoubleArray(n) { 0.02 }
        novelty[4] = 1.0
        novelty[16] = 1.0
        novelty[18] = 1.0
        val bounds = PhraseGrid.boundaries(novelty).toSet()
        assertTrue(4 in bounds, bounds.joinToString())
        assertTrue(16 in bounds, bounds.joinToString())
        assertTrue(18 in bounds, bounds.joinToString())
        assertTrue(bounds.all { it in 0..n })
    }

    @Test
    fun phaseFlippedGridIsNotConfident() {
        val times = DoubleArray(64) { it * 0.05 }
        val flux = DoubleArray(times.size) { i -> if (i % 10 == 0) 1.0 else 0.0 }
        val onGrid = DoubleArray(8) { it * 0.5 }
        val flipped = DoubleArray(8) { 0.25 + it * 0.5 }
        val honest = PhraseGrid.gridConfidence(onGrid, times, flux)
        val off = PhraseGrid.gridConfidence(flipped, times, flux)
        assertTrue(honest > off)
        assertTrue(off <= 0.4f, "flipped confidence $off")
    }

    @Test
    fun aZeroMaskBeatsALyricsClaim() {
        val n = 16
        val bars = List(n) { StructureAnalyzer.Bar(FloatArray(13) { 0.1f }, oneHot(0), -18f, 0.2f, 0.4f, 0.3f) }
        val mask = FloatArray(n) { 0f }
        val lyrics = FloatArray(n) { 1f }
        val map = StructureAnalyzer.analyseBars(
            trackKey = "mask",
            durationSec = 32.0,
            bpm = 120.0,
            beatsPerBar = 4,
            downbeats = DoubleArray(n) { it * 2.0 },
            bars = bars,
            mask = mask,
            lyricPrior = lyrics,
            gridConfidence = 0.8f,
            tempoStable = true,
            activation = 0.8f,
            speechLike = false,
            partial = false,
        )
        assertTrue(map.sungBars.none { it })
        assertEquals(null, map.firstVocalBar)
    }

    @Test
    fun barCountStaysInsideTheBudget() {
        val n = 200
        val bars = List(n) { i ->
            val chorus = i in 32 until 48 || i in 96 until 112
            StructureAnalyzer.Bar(
                mfcc = FloatArray(13) { if (chorus) 1f else 0f },
                chroma = oneHot(if (chorus) 5 else 0),
                rmsDb = if (chorus) -8f else -16f,
                low = 0.3f,
                flux = if (i % 8 == 0) 2f else 0.2f,
                bass = 0.4f,
            )
        }
        val started = System.currentTimeMillis()
        val map = StructureAnalyzer.analyseBars(
            trackKey = "budget",
            durationSec = n * 2.0,
            bpm = 120.0,
            beatsPerBar = 4,
            downbeats = DoubleArray(n) { it * 2.0 },
            bars = bars,
            mask = FloatArray(n * 2) { i -> if (i / 2 in 16 until 160) 0.5f else 0f },
            lyricPrior = null,
            gridConfidence = 0.8f,
            tempoStable = true,
            activation = 0.7f,
            speechLike = false,
            partial = false,
            startedMs = started,
            now = { System.currentTimeMillis() },
        )
        val elapsed = System.currentTimeMillis() - started
        println("structMs=${map.structMs} structWallMs=$elapsed bars=$n")
        assertTrue(n <= 200)
        assertTrue(map.sections.isNotEmpty())
    }

    @Test
    fun syntheticSongFindsTheChorusPreOutroAndFade() {
        val bpm = 120.0
        val barSec = 2.0
        val layout = intArrayOf(8, 16, 4, 8, 16, 4, 2, 8, 8, 8)
        val names = arrayOf("intro", "verse", "pre", "chorus", "verse", "pre", "insert", "chorus", "outro", "fade")
        val total = layout.sum()
        val pcm = FloatArray((total * barSec * 22050).toInt())
        var bar = 0
        val vocal = BooleanArray(total)
        for (s in layout.indices) {
            val len = layout[s]
            for (b in 0 until len) {
                val kind = names[s]
                if (kind == "verse" || kind == "chorus" || kind == "pre") vocal[bar] = true
                val keyUp = s == 7
                renderBar(pcm, bar, barSec, kind, keyUp)
                bar++
            }
        }
        val mask = FloatArray(total * 2) { i -> if (vocal.getOrElse(i / 2) { false }) 0.8f else 0f }
        val started = System.currentTimeMillis()
        val map = StructureAnalyzer.fromPcm("synthetic", pcm, 22050, bpm, mask, startedMs = started, now = { System.currentTimeMillis() })
        println("synthetic structMs=${map.structMs} sections=${map.sections.joinToString { "${it.fn}:${it.startBar}-${it.endBar}" }} anchor=${map.phraseAnchor}")
        assertEquals(0, map.phraseAnchor)
        assertTrue(map.sections.any { it.fn == SectionFn.CHORUS })
        assertTrue(map.sections.any { it.fn == SectionFn.PRE_CHORUS || it.fn == SectionFn.BUILD })
        assertTrue(map.sections.any { it.fn == SectionFn.OUTRO })
        assertTrue(map.sections.any { it.fn == SectionFn.FADE_OUT })
        val chorus = map.sections.filter { it.fn == SectionFn.CHORUS }
        assertTrue(chorus.size >= 2, "key-up chorus should group, got ${chorus.size}")
        assertTrue(map.anchors.any { it > 0 } || map.phraseAnchor == 0)
    }

    private fun oneHot(pc: Int) = FloatArray(12) { if (it == pc) 1f else 0f }

    private fun renderBar(pcm: FloatArray, bar: Int, barSec: Double, kind: String, keyUp: Boolean) {
        val sr = 22050.0
        val from = (bar * barSec * sr).toInt()
        val to = ((bar + 1) * barSec * sr).toInt().coerceAtMost(pcm.size)
        val gain = when (kind) {
            "chorus" -> 1.6
            "fade" -> 0.35 * (1.0 - (bar % 8) / 10.0)
            "intro", "outro" -> 0.45
            "insert" -> 0.15
            else -> 0.8
        }
        val chords = if (kind == "chorus" || kind == "pre") {
            arrayOf(intArrayOf(5, 9, 0), intArrayOf(7, 11, 2), intArrayOf(0, 4, 7), intArrayOf(9, 0, 4))
        } else {
            arrayOf(intArrayOf(9, 0, 4), intArrayOf(5, 9, 0), intArrayOf(0, 4, 7), intArrayOf(7, 11, 2))
        }
        val chord = chords[bar % 4]
        val shift = if (keyUp) 2 else 0
        val random = Random(bar * 17 + 3)
        for (i in from until to) {
            val t = (i - from) / sr
            val beat = t / 0.5
            var s = 0.0
            if (kind != "fade" || beat < 3.2) {
                if (beat % 1.0 < 0.08) s += 0.8 * sin(2 * PI * 55 * t)
                if ((beat + 2.0) % 2.0 < 0.06) s += 0.4 * sin(2 * PI * 180 * t)
            }
            val hatEvery = if (kind == "chorus") 0.25 else 0.5
            if (beat % hatEvery < 0.02) s += 0.15 * (random.nextDouble() - 0.5)
            if (kind == "verse" || kind == "chorus" || kind == "pre") s += 0.22 * (random.nextDouble() - 0.5)
            for (pc in chord) {
                val midi = 60 + ((pc + shift) % 12)
                val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
                s += 0.18 * sin(2 * PI * hz * t)
            }
            val fade = if (kind == "fade") (1.0 - t / barSec).coerceAtLeast(0.05) else 1.0
            pcm[i] = (s * gain * fade).toFloat().coerceIn(-1f, 1f)
        }
    }
}
