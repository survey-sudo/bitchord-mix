/*
 * Chunk scheduling extracted from BeatTracker.kt (ported from Orchard).
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.music.bitchord.playback.smart

import kotlin.math.min

/** Pure chunk adapter, shared by inference and CUE JVM tests (no ORT/native library needed). */
internal object BeatModelAdapter {
    const val INPUT_NAME = "input_spectrogram"
    const val BEAT_OUTPUT = "beat"
    const val DOWNBEAT_OUTPUT = "downbeat"
    val OUTPUT_NAMES: Set<String> = setOf(BEAT_OUTPUT, DOWNBEAT_OUTPUT)

    fun modelAsset(useQdqModel: Boolean): String =
        if (useQdqModel) BeatTracker.NPU_MODEL_ASSET else BeatTracker.CPU_MODEL_ASSET

    /**
     * Preserve the original dynamic CPU chunk/crop schedule. Only the QDQ
     * candidate pads short inputs to 1500 frames; padding is never copied
     * into the track logits. [run] receives row-major values and input frames.
     */
    fun inferChunks(
        spectrogram: MelSpectrogram.Spectrogram,
        beatLogits: FloatArray,
        downbeatLogits: FloatArray,
        useQdqModel: Boolean,
        run: (FloatArray, Int) -> Pair<FloatArray, FloatArray>,
    ) {
        val mels = spectrogram.mels
        val stride = BeatTracker.CHUNK_FRAMES - 2 * BeatTracker.BORDER_FRAMES
        var start = 0
        while (start < spectrogram.frames) {
            val length = min(BeatTracker.CHUNK_FRAMES, spectrogram.frames - start)
            if (length <= 2 * BeatTracker.BORDER_FRAMES && start > 0) break

            val source = spectrogram.values.copyOfRange(start * mels, (start + length) * mels)
            val inputFrames = if (useQdqModel) BeatTracker.CHUNK_FRAMES else length
            val chunk = if (inputFrames == length) source else source.copyOf(inputFrames * mels)
            val (beat, downbeat) = run(chunk, inputFrames)
            require(beat.size >= length && downbeat.size >= length) { "Short beat-model output" }

            val keepFrom = if (start == 0) 0 else BeatTracker.BORDER_FRAMES
            val keepTo = if (start + length >= spectrogram.frames) length else length - BeatTracker.BORDER_FRAMES
            for (index in keepFrom until keepTo) {
                val target = start + index
                if (target >= beatLogits.size) break
                beatLogits[target] = beat[index]
                downbeatLogits[target] = downbeat[index]
            }
            if (start + length >= spectrogram.frames) break
            start += stride
        }
    }
}
