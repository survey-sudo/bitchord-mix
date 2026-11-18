/* The frozen legacy chunk loop derives from BeatTracker.kt's Orchard port.
 * Copyright (C) 2026 SFG545 and Kushagra Singh
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.music.bitchord.playback.smart

import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BeatModelAdapterTest {
    @Test
    fun assetsAndCandidateOutputNamesAreExplicit() {
        assertEquals("beat_this_final0_q8.onnx", BeatModelAdapter.modelAsset(false))
        assertEquals("nirovi_qnn.onnx", BeatModelAdapter.modelAsset(true))
        assertEquals("input_spectrogram", BeatModelAdapter.INPUT_NAME)
        assertEquals(setOf("beat", "downbeat"), BeatModelAdapter.OUTPUT_NAMES)
    }

    @Test
    fun qdqAlwaysPadsTo1500AndCropsShortAndMultiChunkOutputs() {
        for (frames in listOf(1, 12, 17, 1488, 1500, 1501, 2999, 11991)) {
            val mel = spectrogram(frames)
            val beat = FloatArray(frames) { Float.NaN }
            val downbeat = FloatArray(frames) { Float.NaN }
            var calls = 0
            BeatModelAdapter.inferChunks(mel, beat, downbeat, true) { input, inputFrames ->
                calls++
                assertEquals(1500, inputFrames)
                assertEquals(1500 * mel.mels, input.size)
                val realFrames = input.count { it != 0f } / mel.mels
                assertTrue(input.drop(realFrames * mel.mels).all { it == 0f })
                FloatArray(inputFrames) { input[it * mel.mels] } to
                    FloatArray(inputFrames) { -input[it * mel.mels] }
            }
            assertTrue(calls > 0)
            // Every real frame is written once by the preserved border schedule;
            // no padded zeros, gaps, duplicated offsets or extra frames escape.
            assertContentEquals(FloatArray(frames) { mel.values[it * mel.mels] }, beat)
            assertContentEquals(FloatArray(frames) { -mel.values[it * mel.mels] }, downbeat)
        }
    }

    @Test
    fun flagOffMatchesLegacyCpuInputsShapesAndLogitsBitForBit() {
        for (frames in listOf(1, 12, 1488, 1500, 1501, 2999, 11991)) {
            val mel = spectrogram(frames)
            val expectedBeat = FloatArray(frames)
            val expectedDownbeat = FloatArray(frames)
            val actualBeat = FloatArray(frames)
            val actualDownbeat = FloatArray(frames)
            val expectedInputs = mutableListOf<Pair<FloatArray, Int>>()
            val actualInputs = mutableListOf<Pair<FloatArray, Int>>()
            fun output(input: FloatArray, length: Int) =
                FloatArray(length) { input[it * mel.mels] + length / 100f } to
                    FloatArray(length) { input[it * mel.mels] - length / 100f }
            legacyCpu(mel, expectedBeat, expectedDownbeat) { input, length ->
                expectedInputs += input to length
                output(input, length)
            }
            BeatModelAdapter.inferChunks(mel, actualBeat, actualDownbeat, false) { input, length ->
                actualInputs += input to length
                output(input, length)
            }
            assertContentEquals(expectedBeat, actualBeat)
            assertContentEquals(expectedDownbeat, actualDownbeat)
            assertEquals(expectedInputs.size, actualInputs.size)
            expectedInputs.zip(actualInputs).forEach { (expected, actual) ->
                assertEquals(expected.second, actual.second)
                assertContentEquals(expected.first, actual.first)
            }
        }
    }

    @Test
    fun malformedShortOutputsDoNotProduceAPartialGrid() {
        assertFailsWith<IllegalArgumentException> {
            BeatModelAdapter.inferChunks(spectrogram(17), FloatArray(17), FloatArray(17), true) { _, _ ->
                FloatArray(16) to FloatArray(1500)
            }
        }
    }

    private fun spectrogram(frames: Int) = MelSpectrogram.Spectrogram(
        values = FloatArray(frames * 128) { it + 1f }, frames = frames, mels = 128,
    )

    /** Frozen vc83 chunk/crop implementation, without the ORT call. */
    private fun legacyCpu(
        mel: MelSpectrogram.Spectrogram, beats: FloatArray, downbeats: FloatArray,
        run: (FloatArray, Int) -> Pair<FloatArray, FloatArray>,
    ) {
        var start = 0
        while (start < mel.frames) {
            val length = min(1500, mel.frames - start)
            if (length <= 12 && start > 0) break
            val chunk = mel.values.copyOfRange(start * mel.mels, (start + length) * mel.mels)
            val (beat, downbeat) = run(chunk, length)
            val keepFrom = if (start == 0) 0 else 6
            val keepTo = if (start + length >= mel.frames) length else length - 6
            for (index in keepFrom until keepTo) {
                val target = start + index
                if (target >= beats.size) break
                beats[target] = beat[index]
                downbeats[target] = downbeat[index]
            }
            if (start + length >= mel.frames) break
            start += 1488
        }
    }
}
