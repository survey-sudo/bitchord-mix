/*
 * AUTOMIX-V2(B): stem separation contract (producer: Stream B; consumers: C renderer, E planner).
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

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Contract (docs: workspace/work/CONTRACTS.md). Do not change these names.

enum class StemKind { VOCALS, DRUMS, BASS, OTHER }

/**
 * Planar stereo stems for one decoded region; frames identical to the region; sum of stems ==
 * dry mixture (residual folded into OTHER).
 *
 * Treat every array as read-only: the same instance may be served from the cache to several
 * callers. With [stemCount] == 2 only [StemKind.VOCALS] and [StemKind.OTHER] are present (OTHER is
 * then the whole instrumental); use [leftOf]/[rightOf] rather than `!!`.
 */
class StemSet(
    val sampleRate: Int,
    val frames: Int,
    val regionStartSec: Double,
    val left: Map<StemKind, FloatArray>,
    val right: Map<StemKind, FloatArray>,
    /** 2 = vocal/instrumental only (VOCALS + OTHER, legacy umxhq), 4 = full. */
    val stemCount: Int,
    val confidence: Float,
)

fun interface StemProvider {
    /** Worker thread only. Null = unavailable; caller must fall back to dry. */
    fun separate(left: FloatArray, right: FloatArray, sampleRate: Int, regionStartSec: Double): StemSet?
}

// ---------------------------------------------------------------------------
// Helpers (additive; pure, so commonTest covers them).

/** The stem's left channel, or null when this set does not carry it (2-stem sets). */
fun StemSet.leftOf(kind: StemKind): FloatArray? = left[kind]

/** The stem's right channel, or null when this set does not carry it (2-stem sets). */
fun StemSet.rightOf(kind: StemKind): FloatArray? = right[kind]

/** Approximate heap held by the stem arrays (bytes); what the memory cache budgets. */
val StemSet.byteSize: Long
    get() = (left.values.sumOf { it.size.toLong() } + right.values.sumOf { it.size.toLong() }) * 4L

/** Region end in track seconds. */
val StemSet.regionEndSec: Double get() = regionStartSec + frames.toDouble() / sampleRate

/**
 * Stream B's feature flags. Every default keeps vc80 behaviour on failure: a missing model,
 * a failed run or a disabled flag returns null from the provider, and the caller plays dry.
 * Mutable so the orchestrator can bind them to settings / remote config at runtime.
 */
object AutomixV2Stems {
    /** Master switch for StemSeparator (both the 4-stem and the legacy 2-stem path). */
    @Volatile var enabled: Boolean = true

    /** Use the 4-stem model when it is installed and verified. */
    @Volatile var fourStemEnabled: Boolean = true

    /** When no 4-stem model is usable, fall back to umxhq vocals + OTHER = dry - vocals. */
    @Volatile var legacyFallbackEnabled: Boolean = true

    /** Minimum crossfade overlap between model chunks (fraction of a chunk). 0.12 ~ 1.3 s for SCNet. */
    @Volatile var minChunkOverlap: Double = 0.12

    /** SCNet post-filter: false = the network's complex estimates (SCNet's own path), true = Wiener EM1. */
    @Volatile var scnetWiener: Boolean = false

    /** UMX-4 fallback post-filter: true = Wiener EM1 (open-unmix default niter=1). */
    @Volatile var umxWiener: Boolean = true

    /** Regions longer than this are refused (null), bounding native memory (~4 MB per second). */
    @Volatile var maxRegionSeconds: Double = 48.0

    /** Memory LRU budget for separated regions. */
    @Volatile var memoryCacheBytes: Long = 64L * 1024 * 1024

    /** Disk cache budget (16-bit VOCALS/DRUMS/BASS; OTHER rebuilt from the dry on load). */
    @Volatile var diskCacheBytes: Long = 160L * 1024 * 1024

    /**
     * Try the QNN/NPU execution provider for stem models. Off: SCNet's dual-path LSTMs and
     * open-unmix's LSTM have no HTP kernel, so the graph would partition back to CPU. Stream A's
     * `*_qdq.onnx` conventions (tools/npu) decide when this can flip.
     */
    @Volatile var allowNpu: Boolean = false

    /** Close ORT sessions after each separation (frees ~50-100 MB between transitions). */
    @Volatile var releaseSessionsAfterUse: Boolean = true

    /** Accept model files whose SHA-256 is not pinned in StemModels (debug builds only). */
    @Volatile var allowUnpinnedModels: Boolean = false
}

/** Pure mixture-consistency and chunking math, shared by every separator path. */
object StemMath {

    /**
     * Folds the residual into OTHER so the four stems sum to [dry] exactly:
     * OTHER = dry - VOCALS - DRUMS - BASS (missing stems count as zero). Returns the residual
     * energy ratio sum((dry - sum of the incoming stems)^2) / sum(dry^2) as a quality signal
     * (0 for silent input). All arrays must be [dry]-sized; [other] is overwritten.
     */
    fun foldResidualIntoOther(
        dry: FloatArray,
        vocals: FloatArray?,
        drums: FloatArray?,
        bass: FloatArray?,
        other: FloatArray,
    ): Double {
        require(other.size == dry.size) { "other ${other.size} != dry ${dry.size}" }
        require(vocals == null || vocals.size == dry.size) { "vocals size" }
        require(drums == null || drums.size == dry.size) { "drums size" }
        require(bass == null || bass.size == dry.size) { "bass size" }
        var residualEnergy = 0.0
        var dryEnergy = 0.0
        for (i in dry.indices) {
            val known = (vocals?.get(i) ?: 0f) + (drums?.get(i) ?: 0f) + (bass?.get(i) ?: 0f)
            val folded = dry[i] - known
            val residual = (folded - other[i]).toDouble()
            residualEnergy += residual * residual
            dryEnergy += dry[i].toDouble() * dry[i]
            other[i] = folded
        }
        return if (dryEnergy > 0.0) residualEnergy / dryEnergy else 0.0
    }

    /**
     * Chunk starts covering [0, total) with chunks of [useful] samples overlapping by at least
     * [minOverlap] (fraction), spread evenly: first at 0, last ending exactly at [total].
     * Mirrors native/analyzer/stem_dsp.cpp PlanChunks bit for bit.
     */
    fun chunkStarts(total: Int, useful: Int, minOverlap: Double): IntArray {
        if (total <= 0 || useful <= 0) return IntArray(0)
        if (total <= useful) return intArrayOf(0)
        val overlap = minOverlap.coerceIn(0.0, 0.9)
        val step = max(1L, floor(useful * (1.0 - overlap)).toLong())
        val span = (total - useful).toLong()
        val count = ((span + step - 1) / step + 1).toInt()
        val denominator = (count - 1).toLong()
        return IntArray(count) { i -> ((i * span + denominator / 2) / denominator).toInt() }
    }

    /**
     * Normalized raised-cosine crossfade weight of chunk [index] at local sample [q]: the weights
     * of every chunk covering a sample sum to 1. Mirrors native ChunkWeight.
     */
    fun chunkWeight(starts: IntArray, useful: Int, index: Int, q: Int): Float {
        if (index !in starts.indices || q < 0 || q >= useful) return 0f
        val global = starts[index].toLong() + q
        var total = 0.0
        for (j in starts.indices) {
            if (global < starts[j] || global >= starts[j].toLong() + useful) continue
            total += rawWeight(starts, useful, j, (global - starts[j]).toInt())
        }
        val mine = rawWeight(starts, useful, index, q)
        return if (total > 0.0) (mine / total).toFloat() else 1f
    }

    private fun rawWeight(starts: IntArray, useful: Int, index: Int, q: Int): Double {
        var weight = 1.0
        if (index > 0) {
            val overlap = starts[index - 1].toLong() + useful - starts[index]
            if (overlap > 0 && q < overlap) {
                val s = sin(0.5 * PI * (q + 0.5) / overlap)
                weight *= s * s
            }
        }
        if (index + 1 < starts.size) {
            val overlap = starts[index].toLong() + useful - starts[index + 1]
            if (overlap > 0) {
                val fadeFrom = useful - overlap
                if (q >= fadeFrom) {
                    val c = cos(0.5 * PI * (q - fadeFrom + 0.5) / overlap)
                    weight *= c * c
                }
            }
        }
        return max(weight, 1e-12)
    }

    /**
     * A cheap content fingerprint of a planar region: length plus FNV-1a over ~4096 evenly
     * spaced samples of each channel. Identifies "the same decoded audio" for the cache when no
     * track id is known, and guards a cached region against a re-decode that landed elsewhere.
     */
    fun fingerprint(left: FloatArray, right: FloatArray): Long {
        var hash = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
        val prime = 0x100000001b3L
        fun mix(value: Int) {
            var v = value
            repeat(4) {
                hash = (hash xor (v and 0xff).toLong()) * prime
                v = v ushr 8
            }
        }
        mix(left.size)
        mix(right.size)
        val n = min(left.size, right.size)
        if (n > 0) {
            val stride = max(1, n / 4096)
            var i = 0
            while (i < n) {
                mix(left[i].toRawBits())
                mix(right[i].toRawBits())
                i += stride
            }
            mix(left[n - 1].toRawBits())
            mix(right[n - 1].toRawBits())
        }
        return hash
    }

    /** Seconds -> whole samples at [rate], rounded (region keys and slice offsets). */
    fun samplesAt(seconds: Double, rate: Int): Long = kotlin.math.round(seconds * rate).toLong()

    /** Frames a [rate] resample of [frames] at [fromRate] produces (the native resampler floors). */
    fun resampledFrames(frames: Int, fromRate: Double, rate: Double): Int =
        floor(frames * rate / fromRate).toInt()

    /** Seconds of audio a model chunk count implies (budget logging). */
    fun chunkSeconds(chunks: Int, useful: Int, modelRate: Double): Double = ceil(chunks * useful / modelRate)
}
