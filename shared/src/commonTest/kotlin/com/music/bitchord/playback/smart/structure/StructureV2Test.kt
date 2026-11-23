package com.music.bitchord.playback.smart.structure

import com.music.bitchord.playback.smart.SectionFn
import com.music.bitchord.playback.smart.SectionKind
import com.music.bitchord.playback.smart.StructureAnalyzer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AUTOMIX-V2(D): the structure tests that matter. Synthetic songs built from
 * repeated patterns; every boundary, chorus and drop must land within one bar
 * (drops and builds exactly). Plus the vc80 regression when the flag is off.
 */
class StructureV2Test {

    @Test
    fun popFormFindsBoundariesLabelsAndChorusFamilyWithinOneBar() {
        for (withVocals in listOf(true, false)) {
            val song = Synth.pop(POP, seed = 1, keyUpLast = true)
            val r = assertNotNull(
                StructureV2.analyze(song.bars, song.downbeats, 120.0, vocal = if (withVocals) song.vocal else null),
            )
            val n = song.bars.size
            val desc = "vocals=$withVocals ${r.sections.joinToString { "${it.kind}@${it.startBar}" }}"
            for ((index, part) in song.truth.withIndex()) {
                val (kind, start) = part
                val end = song.truth.getOrNull(index + 1)?.second ?: n
                assertTrue(r.sections.any { abs(it.startBar - start) <= 1 }, "no section starts near $kind@$start: $desc")
                val mid = (start + end) / 2
                val at = r.sections.first { mid >= it.startBar && mid < it.endBar }
                assertEquals(POP_EXPECT.getValue(kind), at.kind, "$kind@$start: $desc")
            }
            val chorusStarts = song.truth.filter { it.first == "chorus" }.map { it.second }
            val family = r.families.first()
            assertEquals(chorusStarts.size, family.size, desc)
            for (c in chorusStarts) assertTrue(family.any { abs(it - c) <= 1 }, "chorus $c missing from family: $desc")
            assertTrue(r.hasChorusFamily)
            assertTrue(r.phraseConfidence > 0.6, "phraseConfidence=${r.phraseConfidence}")
            assertTrue(r.labelConfidence >= AutomixV2Structure.minLabelConfidence, "labelConfidence=${r.labelConfidence}")
            assertEquals(StructureMode.POP, r.mode)
            // The bass-light bridge makes the last chorus an impact downbeat; any drop must sit on a chorus start.
            assertTrue(r.drops.all { d -> chorusStarts.any { abs(it - d.bar) <= 1 } }, "drops ${r.drops.map { it.bar }}: $desc")
            assertTrue(r.builds.isEmpty(), "pop form has no riser build: ${r.builds.map { it.startBar }}")
        }
    }

    @Test
    fun edmFormFindsBuildsDropsAndBreakdownExactly() {
        val song = Synth.edm(EDM, seed = 3)
        val r = assertNotNull(StructureV2.analyze(song.bars, song.downbeats, 128.0))
        val desc = r.sections.joinToString { "${it.kind}@${it.startBar}-${it.endBar}" }
        assertEquals(listOf(24, 64), r.drops.map { it.bar }, desc)
        assertEquals(listOf(16 to 24, 56 to 64), r.builds.map { it.startBar to it.endBar }, desc)
        assertEquals(listOf(40 to 56), r.breakdowns.map { it.startBar to it.endBar }, desc)
        val expect = listOf(
            SectionKind.INTRO to (0 to 16), SectionKind.BUILD to (16 to 24), SectionKind.DROP to (24 to 40),
            SectionKind.BREAKDOWN to (40 to 56), SectionKind.BUILD to (56 to 64), SectionKind.DROP to (64 to 80),
            SectionKind.OUTRO to (80 to 96),
        )
        assertEquals(expect, r.sections.map { it.kind to (it.startBar to it.endBar) }, desc)
        assertEquals(StructureMode.DANCE, r.mode)
        assertEquals(StructureMood.HIGH, r.mood)
        assertEquals(listOf(PhraseTag.INTRO, PhraseTag.UP, PhraseTag.CHORUS, PhraseTag.DOWN, PhraseTag.UP, PhraseTag.CHORUS, PhraseTag.OUTRO), r.sections.map { it.tag })
        assertEquals(listOf(24, 64), r.families.first().toList(), "drop family is the chorus family")
        assertTrue(r.intro.bars == 16 && r.intro.mixability > 0.5f, "intro mixability ${r.intro.mixability}")
        assertTrue(r.phraseConfidence > 0.7, "phraseConfidence=${r.phraseConfidence}")
    }

    @Test
    fun aFlatLoopIsNeverConfidentEnoughToRelabel() {
        val song = Synth.flat(96, seed = 2)
        val r = assertNotNull(StructureV2.analyze(song.bars, song.downbeats, 120.0))
        val relabels = r.labelConfidence >= AutomixV2Structure.minLabelConfidence &&
            r.phraseConfidence >= AutomixV2Structure.minPhraseConfidence
        assertTrue(!relabels, "flat loop: label=${r.labelConfidence} phrase=${r.phraseConfidence}")
        assertTrue(r.drops.isEmpty())
    }

    @Test
    fun analyzerAttachesV2AndTheFlagRestoresVc80() {
        val song = Synth.edm(EDM, seed = 3)
        val old = song.bars.map { b ->
            StructureAnalyzer.Bar(
                mfcc = FloatArray(13) { b.timbre[minOf(it, b.timbre.size - 1)] },
                chroma = b.chroma,
                rmsDb = b.rmsDb,
                low = 0.3f,
                flux = b.onsetRate,
                bass = ((b.lowDb + 30f) / 30f).coerceIn(0f, 1f),
            )
        }
        fun analyse(v2: List<V2Bar>?) = StructureAnalyzer.analyseBars(
            trackKey = "edm", durationSec = song.bars.size * 1.875, bpm = 128.0, beatsPerBar = 4,
            downbeats = song.downbeats, bars = old, mask = null, lyricPrior = null, gridConfidence = 0.8f,
            tempoStable = true, activation = 0.8f, speechLike = false, partial = false, v2Bars = v2,
        )
        val vc80 = analyse(null)
        assertEquals(1, vc80.structureVersion)
        val map = analyse(song.bars)
        assertEquals(2, map.structureVersion)
        val v2 = assertNotNull(map.structure)
        assertTrue(v2.applied)
        assertEquals(listOf(24 * 1.875, 64 * 1.875), map.drops)
        assertEquals(16 * 1.875, map.builds.first().start, 1e-9)
        assertEquals(24 * 1.875, map.builds.first().endInclusive, 1e-9)
        assertEquals(SectionFn.DROP, map.sectionAt(24)?.fn)
        assertEquals(SectionFn.BUILD, map.sectionAt(20)?.fn)
        assertEquals(SectionFn.BREAK, map.sectionAt(44)?.fn)
        assertEquals(SectionFn.INTRO, map.sectionAt(0)?.fn)
        assertEquals(SectionFn.OUTRO, map.sectionAt(90)?.fn)
        assertTrue(map.phraseConfidence > 0.7)
        assertEquals(listOf(24 * 1.875, 64 * 1.875), map.chorusRepeats.first())

        val saved = AutomixV2Structure.enabled
        try {
            AutomixV2Structure.enabled = false
            val off = analyse(song.bars)
            assertEquals(1, off.structureVersion)
            assertNull(off.structure)
            assertTrue(off.drops.isEmpty() && off.builds.isEmpty() && off.chorusRepeats.isEmpty())
            assertEquals(vc80.sections, off.sections)
            assertTrue(vc80.anchors.contentEquals(off.anchors))
        } finally {
            AutomixV2Structure.enabled = saved
        }
    }

    @Test
    fun pcmSongFindsChorusesAndRepairsTheBarPhase() {
        val layout = intArrayOf(8, 16, 4, 8, 16, 4, 2, 8, 8, 8)
        val names = arrayOf("intro", "verse", "pre", "chorus", "verse", "pre", "insert", "chorus", "outro", "fade")
        for (lead in doubleArrayOf(0.0, 1.37)) {
            val (pcm, vocal) = renderSong(layout, names, lead)
            val total = layout.sum()
            val duration = pcm.size / 22050.0
            // per-second-ish mask on the track timeline, as the phone passes it
            val mask = FloatArray((duration * 2).toInt()) { i ->
                val bar = ((i / 2.0 - lead) / 2.0).toInt()
                if (i / 2.0 >= lead && vocal.getOrElse(bar) { false }) 0.8f else 0f
            }
            val map = StructureAnalyzer.fromPcm("pcm-$lead", pcm, 22050, 120.0, mask)
            val desc = "lead=$lead ${map.sections.joinToString { "${it.fn}@${it.startBar}" }} d0=${map.downbeats.firstOrNull()}"
            assertEquals(2, map.structureVersion, desc)
            assertTrue(assertNotNull(map.structure).applied, desc)
            assertEquals(lead, map.downbeats.first(), 0.045, desc)
            assertTrue(map.downbeats.size >= total - 1, desc)
            val choruses = map.sections.filter { it.fn == SectionFn.CHORUS }
            for (bar in intArrayOf(28, 58)) {
                assertTrue(choruses.any { abs(it.startBar - bar) <= 1 }, "chorus near $bar: $desc")
            }
            assertTrue(map.sections.any { it.fn == SectionFn.PRE_CHORUS || it.fn == SectionFn.BUILD }, desc)
            assertTrue(map.sections.any { it.fn == SectionFn.OUTRO }, desc)
            assertTrue(map.sections.any { it.fn == SectionFn.FADE_OUT }, desc)
            assertEquals(2, map.chorusRepeats.first().size, desc)
        }
    }

    // ------------------------------------------------------------ synthesis

    private class Song(val bars: List<V2Bar>, val vocal: FloatArray, val downbeats: DoubleArray, val truth: List<Pair<String, Int>>)

    private object Synth {
        private val kinds = listOf("intro", "verse", "pre", "chorus", "bridge", "outro", "build", "drop", "breakdown", "edm_intro", "edm_outro")
        private val prog = mapOf(
            "verse" to arrayOf(intArrayOf(9, 0, 4), intArrayOf(5, 9, 0), intArrayOf(0, 4, 7), intArrayOf(7, 11, 2)),
            "chorus" to arrayOf(intArrayOf(5, 9, 0), intArrayOf(7, 11, 2), intArrayOf(0, 4, 7), intArrayOf(9, 0, 4)),
            "pre" to arrayOf(intArrayOf(2, 5, 9), intArrayOf(7, 11, 2), intArrayOf(2, 5, 9), intArrayOf(7, 11, 2)),
            "bridge" to arrayOf(intArrayOf(4, 8, 11), intArrayOf(9, 0, 4), intArrayOf(2, 6, 9), intArrayOf(4, 8, 11)),
            "intro" to arrayOf(intArrayOf(0, 4, 7), intArrayOf(0, 4, 7), intArrayOf(5, 9, 0), intArrayOf(5, 9, 0)),
            "outro" to arrayOf(intArrayOf(0, 4, 7), intArrayOf(0, 4, 7), intArrayOf(5, 9, 0), intArrayOf(5, 9, 0)),
            "edm_intro" to arrayOf(intArrayOf(9, 0, 4)),
            "edm_outro" to arrayOf(intArrayOf(9, 0, 4)),
            "drop" to arrayOf(intArrayOf(9, 0, 4), intArrayOf(5, 9, 0), intArrayOf(0, 4, 7), intArrayOf(7, 11, 2)),
            "breakdown" to arrayOf(intArrayOf(9, 0, 4), intArrayOf(5, 9, 0), intArrayOf(0, 4, 7), intArrayOf(7, 11, 2)),
            "build" to arrayOf(intArrayOf(9, 0, 4), intArrayOf(5, 9, 0), intArrayOf(0, 4, 7), intArrayOf(7, 11, 2)),
        )
        /** rms, low, high, onsets/beat, vocal */
        private val levels = mapOf(
            "intro" to doubleArrayOf(-14.0, -14.0, -16.0, 1.0, 0.0),
            "verse" to doubleArrayOf(-10.0, -10.0, -12.0, 1.5, 0.75),
            "pre" to doubleArrayOf(-8.0, -9.0, -9.0, 2.0, 0.7),
            "chorus" to doubleArrayOf(-4.0, -6.0, -5.0, 2.5, 0.9),
            "bridge" to doubleArrayOf(-11.0, -16.0, -13.0, 1.2, 0.6),
            "outro" to doubleArrayOf(-13.0, -12.0, -15.0, 1.0, 0.0),
            "edm_intro" to doubleArrayOf(-10.0, -6.0, -14.0, 2.0, 0.0),
            "drop" to doubleArrayOf(-3.0, -3.0, -5.0, 3.0, 0.0),
            "breakdown" to doubleArrayOf(-14.0, -24.0, -14.0, 0.8, 0.0),
            "edm_outro" to doubleArrayOf(-10.0, -6.0, -14.0, 2.0, 0.0),
        )

        private fun timbre(kind: String): DoubleArray {
            val r = Random(1000 + kinds.indexOf(kind))
            return DoubleArray(12) { r.nextDouble() * 2.0 - 1.0 }
        }

        fun gauss(r: Random): Double {
            var s = 0.0
            for (i in 0 until 12) s += r.nextDouble()
            return s - 6.0
        }

        private fun bar(r: Random, kind: String, index: Int, shift: Int, rms: Double, low: Double, high: Double, onset: Double): V2Bar {
            val p = prog.getValue(kind)
            val chord = p[index % p.size]
            val c = FloatArray(12) { 0.05f }
            for (pc in chord) c[(pc + shift) % 12] = 1f
            for (k in 0 until 12) c[k] += (r.nextDouble() * 0.08).toFloat()
            val base = timbre(kind)
            val t = FloatArray(12)
            for (k in 0 until 12) t[k] = (base[k] + 0.15 * gauss(r)).toFloat()
            val e = rms + 0.5 * gauss(r)
            val l = low + 0.6 * gauss(r)
            val h = high + 0.6 * gauss(r)
            val o = onset + 0.1 * gauss(r)
            return V2Bar(c, t, e.toFloat(), l.toFloat(), h.toFloat(), o.toFloat(), 3f)
        }

        fun pop(layout: List<Pair<String, Int>>, seed: Int, keyUpLast: Boolean): Song {
            val r = Random(seed)
            val bars = ArrayList<V2Bar>()
            val vocal = ArrayList<Float>()
            val truth = ArrayList<Pair<String, Int>>()
            val lastChorus = layout.indexOfLast { it.first == "chorus" }
            for ((si, part) in layout.withIndex()) {
                val (kind, len) = part
                truth.add(kind to bars.size)
                val lv = levels.getValue(kind)
                val shift = if (keyUpLast && si == lastChorus) 2 else 0
                for (b in 0 until len) {
                    bars.add(bar(r, kind, b, shift, lv[0], lv[1], lv[2], lv[3]))
                    vocal.add(if (lv[4] > 0) (lv[4] + 0.05 * gauss(r)).coerceIn(0.0, 1.0).toFloat() else 0.03f)
                }
            }
            return Song(bars, vocal.toFloatArray(), DoubleArray(bars.size) { it * 2.0 }, truth)
        }

        fun edm(layout: List<Pair<String, Int>>, seed: Int): Song {
            val r = Random(seed)
            val bars = ArrayList<V2Bar>()
            val truth = ArrayList<Pair<String, Int>>()
            for ((kind, len) in layout) {
                truth.add(kind to bars.size)
                for (b in 0 until len) {
                    if (kind == "build") {
                        val p = (b + 1).toDouble() / len
                        bars.add(bar(r, kind, b, 0, -12 + 6 * p, -22 - 4 * p, -14 + 10 * p, 1.0 + 3.0 * p))
                    } else {
                        val lv = levels.getValue(kind)
                        bars.add(bar(r, kind, b, 0, lv[0], lv[1], lv[2], lv[3]))
                    }
                }
            }
            return Song(bars, FloatArray(bars.size), DoubleArray(bars.size) { it * 1.875 }, truth)
        }

        fun flat(n: Int, seed: Int): Song {
            val r = Random(seed)
            val bars = ArrayList<V2Bar>()
            for (b in 0 until n) bars.add(bar(r, "intro", 0, 0, -10.0, -8.0, -12.0, 1.5))
            return Song(bars, FloatArray(n), DoubleArray(n) { it * 2.0 }, listOf("flat" to 0))
        }
    }

    /** The vc80 StructureAnalyzerTest song, with [lead] seconds of silence before bar 0. */
    private fun renderSong(layout: IntArray, names: Array<String>, lead: Double): Pair<FloatArray, BooleanArray> {
        val sr = 22050.0
        val barSec = 2.0
        val total = layout.sum()
        val offset = (lead * sr).toInt()
        val pcm = FloatArray(offset + (total * barSec * sr).toInt())
        val vocal = BooleanArray(total)
        var bar = 0
        for (s in layout.indices) {
            for (b in 0 until layout[s]) {
                val kind = names[s]
                if (kind == "verse" || kind == "chorus" || kind == "pre") vocal[bar] = true
                renderBar(pcm, offset, bar, barSec, kind, s == 7)
                bar++
            }
        }
        return pcm to vocal
    }

    private fun renderBar(pcm: FloatArray, offset: Int, bar: Int, barSec: Double, kind: String, keyUp: Boolean) {
        val sr = 22050.0
        val from = (bar * barSec * sr).toInt()
        val to = ((bar + 1) * barSec * sr).toInt().coerceAtMost(pcm.size - offset)
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
            pcm[offset + i] = (s * gain * fade).toFloat().coerceIn(-1f, 1f)
        }
    }

    private companion object {
        val POP = listOf(
            "intro" to 8, "verse" to 16, "pre" to 4, "chorus" to 8, "verse" to 16, "pre" to 4,
            "chorus" to 8, "bridge" to 8, "chorus" to 16, "outro" to 8,
        )
        val POP_EXPECT = mapOf(
            "intro" to SectionKind.INTRO, "verse" to SectionKind.VERSE, "pre" to SectionKind.PRE_CHORUS,
            "chorus" to SectionKind.CHORUS, "bridge" to SectionKind.BRIDGE, "outro" to SectionKind.OUTRO,
        )
        val EDM = listOf(
            "edm_intro" to 16, "build" to 8, "drop" to 16, "breakdown" to 16, "build" to 8, "drop" to 16, "edm_outro" to 16,
        )
    }
}
