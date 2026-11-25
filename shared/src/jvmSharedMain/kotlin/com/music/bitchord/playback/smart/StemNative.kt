/*
 * AUTOMIX-V2(B): JNI binding of the 4-stem separation DSP (native/analyzer/stem_dsp.h,
 * app/src/main/cpp/jni/stem_jni.cpp), linked into libbitchord_analysis.
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 */

package com.music.bitchord.playback.smart

import java.nio.ByteBuffer

/**
 * [StemDsp] on Android. Worker threads only: every call allocates or runs DSP over a whole
 * model chunk. [available] is false (and every engine then refuses) when the analysis library
 * did not load or the FFT failed its native self-check.
 */
object StemNative : StemDsp {

    override val available: Boolean by lazy {
        NativeAnalysisLibrary.available && runCatching { nativeSelfCheck() }
            .onFailure { AnalysisLog.warn("stem DSP unavailable", it) }
            .getOrDefault(false)
    }

    override fun createJob(
        left: FloatArray,
        right: FloatArray,
        layout: Int,
        frames: Int,
        segmentSamples: Int,
        minOverlap: Double,
        postFilter: Int,
        normalize: Boolean,
    ): Long = if (!available) 0L else nativeCreateJob(left, right, layout, frames, segmentSamples, minOverlap, postFilter, normalize)

    override fun destroyJob(job: Long) {
        if (job != 0L) nativeDestroyJob(job)
    }

    override fun chunkCount(job: Long): Int = if (job == 0L) -1 else nativeChunkCount(job)
    override fun inputFloats(job: Long): Int = if (job == 0L) -1 else nativeInputFloats(job)
    override fun outputFloats(job: Long): Int = if (job == 0L) -1 else nativeOutputFloats(job)
    override fun outputBuffers(job: Long): Int = if (job == 0L) -1 else nativeOutputBuffers(job)

    override fun prepareChunk(job: Long, index: Int, input: ByteBuffer): Boolean =
        job != 0L && nativePrepareChunk(job, index, input)

    override fun consumeChunk(
        job: Long,
        index: Int,
        input: ByteBuffer,
        out0: ByteBuffer?,
        out1: ByteBuffer?,
        out2: ByteBuffer?,
        out3: ByteBuffer?,
    ): Boolean = job != 0L && nativeConsumeChunk(job, index, input, out0, out1, out2, out3)

    override fun residualRatio(job: Long): Double = if (job == 0L) -1.0 else nativeResidualRatio(job)

    override fun copyStem(job: Long, stem: Int, channel: Int, out: FloatArray): Boolean =
        job != 0L && nativeCopyStem(job, stem, channel, out)

    override fun allocate(floats: Int): ByteBuffer? = if (!available || floats <= 0) null else nativeAllocate(floats)

    override fun free(buffer: ByteBuffer) {
        nativeFree(buffer)
    }

    @JvmStatic private external fun nativeSelfCheck(): Boolean
    @JvmStatic private external fun nativeCreateJob(
        left: FloatArray,
        right: FloatArray,
        layout: Int,
        frames: Int,
        segmentSamples: Int,
        minOverlap: Double,
        postFilter: Int,
        normalize: Boolean,
    ): Long
    @JvmStatic private external fun nativeDestroyJob(job: Long)
    @JvmStatic private external fun nativeChunkCount(job: Long): Int
    @JvmStatic private external fun nativeInputFloats(job: Long): Int
    @JvmStatic private external fun nativeOutputFloats(job: Long): Int
    @JvmStatic private external fun nativeOutputBuffers(job: Long): Int
    @JvmStatic private external fun nativePrepareChunk(job: Long, index: Int, input: ByteBuffer): Boolean
    @JvmStatic private external fun nativeConsumeChunk(
        job: Long,
        index: Int,
        input: ByteBuffer,
        out0: ByteBuffer?,
        out1: ByteBuffer?,
        out2: ByteBuffer?,
        out3: ByteBuffer?,
    ): Boolean
    @JvmStatic private external fun nativeResidualRatio(job: Long): Double
    @JvmStatic private external fun nativeCopyStem(job: Long, stem: Int, channel: Int, out: FloatArray): Boolean
    @JvmStatic private external fun nativeAllocate(floats: Int): ByteBuffer?
    @JvmStatic private external fun nativeFree(buffer: ByteBuffer)
}
