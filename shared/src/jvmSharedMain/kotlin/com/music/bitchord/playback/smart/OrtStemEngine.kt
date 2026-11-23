/*
 * AUTOMIX-V2(B): ONNX Runtime engine for 4-stem networks (SCNet / Open-Unmix x4).
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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The native DSP around a stem network (native/analyzer/stem_dsp.h), implemented on Android by
 * `StemNative` (app module, JNI into libbitchord_analysis). An interface so this shared engine
 * does not depend on the app module, and so the desktop build can simply have none.
 *
 * Buffers from [allocate] are native memory exposed as direct ByteBuffers; they must be released
 * with [free] exactly once. Stems are in canonical order (StemKind.ordinal).
 */
interface StemDsp {
    val available: Boolean
    fun createJob(
        left: FloatArray,
        right: FloatArray,
        layout: Int,
        frames: Int,
        segmentSamples: Int,
        minOverlap: Double,
        postFilter: Int,
        normalize: Boolean,
    ): Long
    fun destroyJob(job: Long)
    fun chunkCount(job: Long): Int
    fun inputFloats(job: Long): Int
    fun outputFloats(job: Long): Int
    fun outputBuffers(job: Long): Int
    fun prepareChunk(job: Long, index: Int, input: ByteBuffer): Boolean
    fun consumeChunk(
        job: Long,
        index: Int,
        input: ByteBuffer,
        out0: ByteBuffer?,
        out1: ByteBuffer?,
        out2: ByteBuffer?,
        out3: ByteBuffer?,
    ): Boolean
    fun residualRatio(job: Long): Double
    fun copyStem(job: Long, stem: Int, channel: Int, out: FloatArray): Boolean
    fun allocate(floats: Int): ByteBuffer?
    fun free(buffer: ByteBuffer)

    companion object {
        const val POST_DIRECT = 0
        const val POST_WIENER_EM1 = 1
        const val BINS = 2049
    }
}

/**
 * Runs one [StemModelSpec] through ONNX Runtime, chunk by chunk, on the calling (worker) thread.
 *
 * Sessions open through [NpuSession.open] (Stream A owns that path): CPU unless
 * [AutomixV2Stems.allowNpu] and the vendor allow QNN, with each model file getting its own QNN
 * context-cache directory. Tensors stay in native memory and outputs are written in place
 * (ORT pinned outputs), so a 30 s region never puts the 62 MB SCNet output on the Java heap.
 */
class OrtStemEngine(
    private val spec: StemModelSpec,
    private val files: List<File>,
    private val dsp: StemDsp,
    private val inferenceThreads: () -> Int,
    private val npuConfig: () -> NpuSupport.NpuConfig = { NpuSupport.NpuConfig() },
    private val npuVendor: () -> NpuSupport.Vendor = { NpuSupport.Vendor.NONE },
    private val npuBackendPath: () -> String? = { null },
    private val npuCacheDir: () -> File? = { null },
) : StemEngine {

    override val tag: String get() = spec.cacheTag
    override val modelRate: Int get() = MODEL_RATE
    override val baseConfidence: Float get() = spec.baseConfidence

    private val lock = Any()
    @Volatile private var sessions: List<OrtSession>? = null

    override fun separate(left: FloatArray, right: FloatArray, shouldStop: () -> Boolean): RawStems? {
        if (!dsp.available || left.isEmpty() || left.size != right.size) return null
        val active = sessions() ?: return null
        val post = when (spec.layout) {
            StemLayout.SCNET_CAC -> if (AutomixV2Stems.scnetWiener) StemDsp.POST_WIENER_EM1 else StemDsp.POST_DIRECT
            StemLayout.UMX_MAGNITUDE -> if (AutomixV2Stems.umxWiener) StemDsp.POST_WIENER_EM1 else StemDsp.POST_DIRECT
        }
        val started = System.currentTimeMillis()
        val job = dsp.createJob(
            left, right, spec.layout.native, spec.frames, spec.segmentSamples,
            AutomixV2Stems.minChunkOverlap, post, spec.normalize,
        )
        if (job == 0L) {
            AnalysisLog.warn("stem job refused (${spec.id}, ${left.size} frames)")
            return null
        }
        var input: ByteBuffer? = null
        val outputs = arrayOfNulls<ByteBuffer>(4)
        try {
            val chunks = dsp.chunkCount(job)
            if (chunks < 0) return null
            if (chunks > 0) {
                val inFloats = dsp.inputFloats(job)
                val outFloats = dsp.outputFloats(job)
                val buffers = dsp.outputBuffers(job)
                if (inFloats <= 0 || outFloats <= 0 || buffers != active.size || buffers > 4) return null
                val cell = StemDsp.BINS.toLong() * spec.frames
                val inShape = longArrayOf(1, inFloats / cell, StemDsp.BINS.toLong(), spec.frames.toLong())
                val outShape = longArrayOf(1, outFloats / cell, StemDsp.BINS.toLong(), spec.frames.toLong())
                val inBuffer = dsp.allocate(inFloats)?.order(ByteOrder.nativeOrder()) ?: return null
                input = inBuffer
                for (b in 0 until buffers) {
                    outputs[b] = dsp.allocate(outFloats)?.order(ByteOrder.nativeOrder()) ?: return null
                }
                val environment = OrtEnvironment.getEnvironment()
                for (index in 0 until chunks) {
                    if (shouldStop()) return null
                    val chunkStarted = System.currentTimeMillis()
                    if (!dsp.prepareChunk(job, index, inBuffer)) return null
                    // A fresh FloatBuffer view per tensor (see VocalTracker on Android's
                    // missing covariant Buffer overrides): never rewind/position a view.
                    OnnxTensor.createTensor(environment, inBuffer.asFloatBuffer(), inShape).use { tensor ->
                        for (b in 0 until buffers) {
                            if (!runInto(environment, active[b], tensor, outputs[b]!!, outShape, outFloats)) return null
                        }
                    }
                    if (!dsp.consumeChunk(job, index, inBuffer, outputs[0], outputs[1], outputs[2], outputs[3])) return null
                    AnalysisLog.warn("stem chunk ${index + 1}/$chunks (${spec.id}) in ${System.currentTimeMillis() - chunkStarted}ms")
                }
            }
            val n = left.size
            val outLeft = arrayOfNulls<FloatArray>(4)
            val outRight = arrayOfNulls<FloatArray>(4)
            for (stem in 0 until 4) {
                val l = FloatArray(n)
                val r = FloatArray(n)
                if (!dsp.copyStem(job, stem, 0, l) || !dsp.copyStem(job, stem, 1, r)) return null
                outLeft[stem] = l
                outRight[stem] = r
            }
            AnalysisLog.warn("stems ${spec.id}: $chunks chunk(s), ${n} frames in ${System.currentTimeMillis() - started}ms")
            return RawStems(outLeft, outRight, dsp.residualRatio(job))
        } catch (failure: Throwable) {
            AnalysisLog.warn("stem inference failed (${spec.id})", failure)
            return null
        } finally {
            dsp.destroyJob(job)
            input?.let { dsp.free(it) }
            for (buffer in outputs) buffer?.let { dsp.free(it) }
            if (AutomixV2Stems.releaseSessionsAfterUse) release()
        }
    }

    /**
     * Runs [session] writing straight into [output] (pinned output). Falls back to ORT-allocated
     * output plus one copy only when the pinned run throws and the heap can take the copy.
     */
    private fun runInto(
        environment: OrtEnvironment,
        session: OrtSession,
        input: OnnxTensor,
        output: ByteBuffer,
        shape: LongArray,
        floats: Int,
    ): Boolean {
        val inName = session.inputNames.first()
        val outName = session.outputNames.first()
        val pinnedFailure = OnnxTensor.createTensor(environment, output.asFloatBuffer(), shape).use { target ->
            runCatching { session.run(mapOf(inName to input), mapOf(outName to target)).close() }.exceptionOrNull()
        } ?: return true
        val runtime = Runtime.getRuntime()
        val headroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        if (headroom < floats.toLong() * 4L * 2L) {
            AnalysisLog.warn("pinned stem output failed and heap too small to copy", pinnedFailure)
            return false
        }
        AnalysisLog.warn("pinned stem output failed; copying output", pinnedFailure)
        session.run(mapOf(inName to input)).use { result ->
            val tensor = result.get(0) as? OnnxTensor ?: return false
            val source = tensor.floatBuffer
            val destination = output.asFloatBuffer()
            if (source.remaining() != destination.remaining()) return false
            destination.put(source)
        }
        return true
    }

    private fun sessions(): List<OrtSession>? {
        sessions?.let { return it }
        synchronized(lock) {
            sessions?.let { return it }
            val opened = ArrayList<OrtSession>(files.size)
            try {
                for ((index, file) in files.withIndex()) {
                    val vendor = if (AutomixV2Stems.allowNpu) npuVendor() else NpuSupport.Vendor.NONE
                    // NpuSession caches its QNN context as <cacheDir>/ctx.onnx: one dir per graph.
                    val cache = npuCacheDir()?.let { File(it, "stems_${spec.cacheTag}_$index").apply { mkdirs() } }
                    val session = NpuSession.open(
                        file.absolutePath, inferenceThreads(), npuConfig(), vendor, npuBackendPath(), cache,
                    )
                    opened += session
                    if (!shapeMatches(session)) {
                        throw IllegalStateException("unexpected tensor shape in ${file.name}")
                    }
                }
            } catch (failure: Throwable) {
                AnalysisLog.warn("stem model unavailable (${spec.id})", failure)
                for (session in opened) runCatching { session.close() }
                return null
            }
            sessions = opened
            return opened
        }
    }

    /** The graph must have this spec's static [1, *, 2049, frames] input. */
    private fun shapeMatches(session: OrtSession): Boolean {
        val info = session.inputInfo.values.firstOrNull()?.info as? TensorInfo ?: return false
        val shape = info.shape
        return shape.size == 4 && shape[2] == StemDsp.BINS.toLong() && shape[3] == spec.frames.toLong()
    }

    override fun release() {
        synchronized(lock) {
            sessions?.forEach { runCatching { it.close() } }
            sessions = null
        }
    }

    companion object {
        const val MODEL_RATE = 44_100
    }
}

/**
 * Resolves the best installed 4-stem model into one long-lived [OrtStemEngine] (sessions open
 * lazily inside it). Pass [current] as StemSeparator's `engine`. A model installed, removed or
 * replaced at runtime is picked up on the next separation.
 */
class StemEngines(
    private val store: StemModelStore,
    private val dsp: () -> StemDsp?,
    private val inferenceThreads: () -> Int,
    private val npuConfig: () -> NpuSupport.NpuConfig = { NpuSupport.NpuConfig() },
    private val npuVendor: () -> NpuSupport.Vendor = { NpuSupport.Vendor.NONE },
    private val npuBackendPath: () -> String? = { null },
    private val npuCacheDir: () -> File? = { null },
) {
    private val lock = Any()
    private var engine: OrtStemEngine? = null
    private var engineKey: String? = null

    fun current(): StemEngine? {
        if (!AutomixV2Stems.fourStemEnabled) return null
        val native = runCatching { dsp() }.getOrNull() ?: return null
        if (!native.available) return null
        val (spec, files) = store.firstReady() ?: return null
        val key = spec.id + "|" + files.joinToString("|") { it.absolutePath }
        synchronized(lock) {
            if (key == engineKey) engine?.let { return it }
            engine?.release()
            val created = OrtStemEngine(
                spec, files, native, inferenceThreads, npuConfig, npuVendor, npuBackendPath, npuCacheDir,
            )
            engine = created
            engineKey = key
            return created
        }
    }
}
