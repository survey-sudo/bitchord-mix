package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DjColourRollBodyTest {
    private fun primed(rate: Int, bpm: Int, phase: Int = 0): Pair<DjColour, Int> {
        val beat = (60.0 / bpm).toFloat()
        val beatFrames = (beat.toDouble() * rate).roundToInt()
        val colour = DjColour()
        colour.configure(rate, 2)
        colour.set(DjEffect.NONE, 0f, beat, 0f)
        val frames = 8 * beatFrames + phase
        val history = FloatArray(frames * 2) { i -> tag(i / 2, rate, i % 2) }
        colour.process(history, frames, 2)
        assertEquals(DjEffect.LOOP_ROLL, colour.set(DjEffect.LOOP_ROLL, 1f, beat, 0f))
        return colour to beatFrames
    }

    private fun tag(frame: Int, rate: Int, channel: Int): Float {
        val left = (0.10 + frame.toDouble() / rate * 0.01).toFloat()
        return if (channel == 0) left else -0.6f * left
    }

    @Test fun everyTempoFrom70To150HasACompleteBeatAlignedFourBeatBody() {
        for (bpm in 70..150) {
            val rate = 8_000
            val beatFrames = ((60.0 / bpm).toFloat().toDouble() * rate).roundToInt()
            val phase = beatFrames / 3 // control ticks need not arrive on a beat
            val (colour, _) = primed(rate, bpm, phase)
            val frames = 4 * beatFrames - phase
            val dry = 0.8f
            val block = FloatArray(frames * 2) { dry }
            colour.process(block, frames, 2)
            assertEquals(4 * beatFrames, colour.rollBodyFrames, "$bpm BPM body was truncated")
            assertEquals(4L * beatFrames, colour.rollBodyStartFrame, "$bpm BPM body was not beat aligned")
            // The first full body is heard before tightening. Distinct source
            // tags prove the third/fourth beats are real PCM, not silence or a 1 s cap.
            for (i in (rate / 100) until frames step 97) for (c in 0..1) {
                val expected = dry * 0.3f + tag(4 * beatFrames + phase + i, rate, c) * 0.7f
                assertEquals(expected, block[2 * i + c], 2e-5f, "$bpm BPM frame=$i channel=$c")
            }
            assertEquals(2 * beatFrames, colour.rollLoopFrames, "tighten only after a complete four-beat pass")
        }
        println("Classic roll body proof: every BPM 70..150, 8 kHz stereo; complete four-beat source tags and beat-aligned capture at an off-beat control tick")
    }

    @Test fun historyWriterCannotOverwriteTheFrozenBodyEvenAfterManyRingWraps() {
        for ((rate, bpm) in listOf(8_000 to 70, 8_000 to 150, 48_000 to 70, 48_000 to 150)) {
            val (a, beatFrames) = primed(rate, bpm)
            val (b, _) = primed(rate, bpm)
            // Same frozen body; opposite live PCM. Once wet is fully up, only
            // the known dry contribution may differ, even after >8 beats of writes.
            var written = 0
            val total = 32 * beatFrames
            while (written < total) {
                val frames = minOf(513, total - written)
                val aa = FloatArray(frames * 2) { 0.8f }
                val bb = FloatArray(frames * 2) { -0.7f }
                a.process(aa, frames, 2)
                b.process(bb, frames, 2)
                if (written > 16 * beatFrames) {
                    for (i in aa.indices step 17) {
                        assertEquals(aa[i] - 0.8f * 0.3f, bb[i] + 0.7f * 0.3f, 2e-5f,
                            "$rate Hz / $bpm BPM overwrite at ${written + i / 2}")
                    }
                }
                written += frames
            }
            assertEquals(4 * beatFrames, a.rollBodyFrames)
            assertEquals(DjEffect.LOOP_ROLL, a.activeEffect)
        }
        println("Classic roll freeze proof: 8/48 kHz stereo at 70/150 BPM; 32 beats of opposing live PCM cannot overwrite the captured body")
    }

    @Test fun unsupportedOrUnprimedRollIsExplicitNoneNotAnAdvertisedNoOp() {
        val colour = DjColour()
        assertEquals(DjEffect.NONE, colour.set(DjEffect.LOOP_ROLL, 1f, 0.5f, 0f))
        colour.configure(8_000, 1)
        assertEquals(DjEffect.NONE, colour.set(DjEffect.LOOP_ROLL, 1f, 0.5f, 0f))
        val dry = FloatArray(32_000) { 0.25f }
        colour.process(dry, dry.size, 1)
        assertTrue(dry.all { it == 0.25f })
        for (beat in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY, 1f, 0.1f)) {
            assertEquals(DjEffect.NONE, colour.set(DjEffect.LOOP_ROLL, 1f, beat, 0f), "beat=$beat")
            assertEquals(DjEffect.NONE, colour.activeEffect)
        }
        assertEquals(DjEffect.LOOP_ROLL, colour.set(DjEffect.LOOP_ROLL, 1f, 0.5f, 0f))
        colour.process(FloatArray(512) { 0.25f }, 512, 1)
        colour.park()
        assertEquals(0, colour.rollBodyFrames)
        assertEquals(DjEffect.NONE, colour.set(DjEffect.LOOP_ROLL, 1f, 0.5f, 0f))
        colour.configure(48_000, 2)
        assertEquals(DjEffect.NONE, colour.set(DjEffect.LOOP_ROLL, 1f, 0.5f, 0f))
    }

    @Test fun rollWetAttackAndReleaseRampRatherThanStep() {
        val colour = DjColour()
        colour.configure(8_000, 1)
        colour.set(DjEffect.NONE, 0f, 0.5f, 0f)
        colour.process(FloatArray(32_000) { 0.2f }, 32_000, 1)
        assertEquals(DjEffect.LOOP_ROLL, colour.set(DjEffect.LOOP_ROLL, 1f, 0.5f, 0f))
        val attack = FloatArray(240) { 1f }
        colour.process(attack, attack.size, 1)
        var previous = 1f
        for (v in attack) {
            assertTrue(abs(v - previous) < 0.02f, "attack step ${v - previous}")
            previous = v
        }
        assertEquals(0.44f, attack.last(), 1e-4f)
        colour.set(DjEffect.NONE, 0f, 0.5f, 0f)
        val release = FloatArray(240) { 1f }
        colour.process(release, release.size, 1)
        for (v in release) {
            assertTrue(abs(v - previous) < 0.02f, "release step ${v - previous}")
            previous = v
        }
        assertEquals(1f, release.last(), 1e-4f)
    }
}
