/*
 * AUTOMIX-V2(B): StemProvider implementation - 4-stem separation with a legacy 2-stem fallback.
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

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A 4-stem network at its own sample rate. Stems come back in canonical order
 * (StemKind.ordinal: vocals, drums, bass, other), each exactly the input's length.
 */
interface StemEngine {
    /** Cache tag: identifies the weights + DSP that produced a cached region. */
    val tag: String
    val modelRate: Int
    val baseConfidence: Float
    /** Worker thread only. [shouldStop] is polled between chunks; null = unavailable or aborted. */
    fun separate(left: FloatArray, right: FloatArray, shouldStop: () -> Boolean): RawStems?
    fun release()
}

/** Model-rate stems; slots are nulled as they are consumed so peak heap stays one copy. */
class RawStems(
    val left: Array<FloatArray?>,
    val right: Array<FloatArray?>,
    /** Spectral energy of (mixture - sum of raw estimates) / mixture energy. */
    val residualRatio: Double,
)

/**
 * The legacy vocal split: (vocals, instrumental) planar pairs at the input's rate and length,
 * or null. Matches `VocalTracker.separateBothWindows` exactly, and is limited like it to one
 * ~22 s model window, which is why [StemSeparator] chunks longer regions itself.
 */
fun interface LegacyTwoStem {
    fun separate(
        left: FloatArray,
        right: FloatArray,
        rate: Double,
    ): Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>?
}

/**
 * Stream B's [StemProvider]: real 4-stem separation (SCNet, or Open-Unmix x4) for a transition
 * region, cached, with a 2-stem fallback (umxhq vocals, OTHER = dry - vocals, stemCount 2) when no
 * 4-stem model is usable, and null when neither works, so the caller plays dry exactly as vc80.
 *
 * Threading: [separate] blocks for seconds (inference) and must run on the analysis worker; it
 * holds [modelLock] (share TrackAnalyzer's, so stems never run concurrently with beat/vocal
 * inference) and serialises separations internally. Called on a playback/audio thread it never
 * computes: it answers from the cache or returns null.
 *
 * Track identity: [forTrack] gives a provider bound to a track id, so prefetched regions are
 * found again by containment (see [StemCache]); the plain [separate] keys by audio fingerprint.
 */
class StemSeparator(
    /** The current 4-stem engine, or null when no model is installed/verified. Called per separation. */
    private val engine: () -> StemEngine?,
    private val legacy: LegacyTwoStem?,
    private val cache: StemCache,
    /** Planar resampler (the native windowed sinc, which floors its output length). */
    private val resample: (FloatArray, Double, Double) -> FloatArray? = { samples, from, to ->
        MelSpectrogram.resample(samples, from, to)
    },
    private val modelLock: Any = Any(),
    /** Polled between chunks: party mode, thermal, the pass being cancelled. */
    private val shouldStop: () -> Boolean = { false },
    /** Longest window the legacy path is handed at once (umxhq graph is 960 frames ~ 22.3 s). */
    private val legacyWindowSeconds: Double = 21.5,
) : StemProvider {

    private val runLock = Any()
    @Volatile private var fourStemFailures = 0

    override fun separate(left: FloatArray, right: FloatArray, sampleRate: Int, regionStartSec: Double): StemSet? =
        separateFor(null, left, right, sampleRate, regionStartSec)

    /** A provider bound to [trackId]: hand this to the loop mix for that deck. */
    fun forTrack(trackId: String): StemProvider = StemProvider { left, right, rate, start ->
        separateFor(trackId, left, right, rate, start)
    }

    /** Cache-only provider for [trackId]: never computes, never blocks on inference. */
    fun cachedOnly(trackId: String): StemProvider = StemProvider { left, right, rate, start ->
        if (!AutomixV2Stems.enabled) null else lookup(trackId, left, right, rate, start)
    }

    /**
     * Separates ahead of time (e.g. the incoming track's intro while the outgoing one still plays)
     * so the later [forTrack] call is a cache hit. Same blocking contract as [separate].
     */
    fun prefetch(trackId: String, left: FloatArray, right: FloatArray, sampleRate: Int, regionStartSec: Double): Boolean =
        separateFor(trackId, left, right, sampleRate, regionStartSec) != null

    /** Drops memory caches and open sessions (onTrimMemory). */
    fun trim() {
        cache.clearMemory()
        runCatching { engine()?.release() }
    }

    fun separateFor(
        trackId: String?,
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        regionStartSec: Double,
    ): StemSet? {
        if (!AutomixV2Stems.enabled) return null
        if (left.isEmpty() || left.size != right.size || sampleRate <= 0) return null
        if (left.size > AutomixV2Stems.maxRegionSeconds * sampleRate) {
            AnalysisLog.warn("stem region of ${left.size / sampleRate.toDouble()} s exceeds the ${AutomixV2Stems.maxRegionSeconds} s cap")
            return null
        }
        val id = trackId ?: ("fp:" + java.lang.Long.toHexString(StemMath.fingerprint(left, right)))
        lookup(id, left, right, sampleRate, regionStartSec)?.let { return it }
        if (onPlaybackThread()) return null
        return runCatching { compute(id, left, right, sampleRate, regionStartSec) }
            .onFailure { AnalysisLog.warn("stem separation failed", it) }
            .getOrNull()
    }

    private fun fourStemAllowed(): Boolean =
        AutomixV2Stems.fourStemEnabled && fourStemFailures < MAX_FOUR_STEM_FAILURES

    private fun lookup(id: String, left: FloatArray, right: FloatArray, rate: Int, start: Double): StemSet? {
        if (fourStemAllowed()) {
            val tag = runCatching { engine()?.tag }.getOrNull()
            if (tag != null) {
                cache.lookup(id, tag, left, right, rate, start)?.let { return it }
                // A 4-stem model is installed: a cached 2-stem result is not good enough to skip it.
                return null
            }
        }
        if (!AutomixV2Stems.legacyFallbackEnabled) return null
        return cache.lookup(id, LEGACY_TAG, left, right, rate, start)
    }

    private fun compute(id: String, left: FloatArray, right: FloatArray, rate: Int, start: Double): StemSet? {
        synchronized(runLock) {
            // Another worker may have produced it while this one waited.
            lookup(id, left, right, rate, start)?.let { return it }
            val active = if (fourStemAllowed()) engine() else null
            if (active != null) {
                val four = fourStem(active, left, right, rate, start)
                if (four != null) {
                    fourStemFailures = 0
                    cache.put(id, active.tag, four, left, right)
                    return four
                }
                if (shouldStop()) return null
                fourStemFailures++
                AnalysisLog.warn("4-stem separation failed ($fourStemFailures); trying the 2-stem fallback")
            }
            if (!AutomixV2Stems.legacyFallbackEnabled) return null
            val two = twoStem(left, right, rate, start) ?: return null
            cache.put(id, LEGACY_TAG, two, left, right)
            return two
        }
    }

    // --- 4 stems ----------------------------------------------------------------------------

    private fun fourStem(active: StemEngine, left: FloatArray, right: FloatArray, rate: Int, start: Double): StemSet? {
        val modelRate = active.modelRate.toDouble()
        val sameRate = abs(rate - modelRate) < 0.5
        val modelLeft = if (sameRate) left else resample(left, rate.toDouble(), modelRate) ?: return null
        val modelRight = if (sameRate) right else resample(right, rate.toDouble(), modelRate) ?: return null
        if (modelLeft.isEmpty() || modelLeft.size != modelRight.size) return null
        val raw = synchronized(modelLock) {
            if (shouldStop()) null else active.separate(modelLeft, modelRight, shouldStop)
        } ?: return null

        val n = left.size
        val outLeft = HashMap<StemKind, FloatArray>(8)
        val outRight = HashMap<StemKind, FloatArray>(8)
        for (kind in StemKind.values()) {
            val k = kind.ordinal
            val l = raw.left.getOrNull(k) ?: return null
            val r = raw.right.getOrNull(k) ?: return null
            raw.left[k] = null
            raw.right[k] = null
            outLeft[kind] = backToRegion(l, modelRate, rate, n, sameRate) ?: return null
            outRight[kind] = backToRegion(r, modelRate, rate, n, sameRate) ?: return null
        }
        val otherLeft = outLeft.getValue(StemKind.OTHER)
        val otherRight = outRight.getValue(StemKind.OTHER)
        if (!allFinite(outLeft.values) || !allFinite(outRight.values)) {
            AnalysisLog.warn("stem network produced non-finite samples; discarding")
            return null
        }
        val residualLeft = StemMath.foldResidualIntoOther(
            left, outLeft[StemKind.VOCALS], outLeft[StemKind.DRUMS], outLeft[StemKind.BASS], otherLeft,
        )
        val residualRight = StemMath.foldResidualIntoOther(
            right, outRight[StemKind.VOCALS], outRight[StemKind.DRUMS], outRight[StemKind.BASS], otherRight,
        )
        val confidence = confidenceFor(active.baseConfidence, raw.residualRatio, 0.5 * (residualLeft + residualRight))
        return StemSet(rate, n, start, outLeft, outRight, stemCount = 4, confidence = confidence)
    }

    /**
     * Model-rate stem -> region rate and exact region length. The resampler floors its length,
     * so the round trip may lose up to ceil(rate/modelRate) + 1 tail samples; those are zero here
     * and OTHER's residual fold restores them from the dry. Anything further off is malformed.
     */
    private fun backToRegion(stem: FloatArray, modelRate: Double, rate: Int, frames: Int, sameRate: Boolean): FloatArray? {
        val back = if (sameRate) stem else resample(stem, modelRate, rate.toDouble()) ?: return null
        if (back.size == frames) return back
        val slack = ceil(rate / modelRate).toInt() + 1
        if (abs(back.size - frames) > slack) return null
        return back.copyOf(frames)
    }

    // --- 2 stems (legacy) -------------------------------------------------------------------

    private fun twoStem(left: FloatArray, right: FloatArray, rate: Int, start: Double): StemSet? {
        val split = legacy ?: return null
        val n = left.size
        val window = floor(legacyWindowSeconds * rate).toInt().coerceAtLeast(1)
        // ~1.5 s crossfades between windows; a region that fits is one window, no seams.
        val overlap = min(0.5, LEGACY_CROSSFADE_SECONDS * rate / window)
        val starts = StemMath.chunkStarts(n, window, overlap)
        val vocalLeft = FloatArray(n)
        val vocalRight = FloatArray(n)
        for ((index, from) in starts.withIndex()) {
            if (shouldStop()) return null
            val length = min(window, n - from)
            val pieceLeft = left.copyOfRange(from, from + length)
            val pieceRight = right.copyOfRange(from, from + length)
            val pair = synchronized(modelLock) {
                if (shouldStop()) null else split.separate(pieceLeft, pieceRight, rate.toDouble())
            } ?: return null
            val vocals = pair.first
            if (vocals.first.size != length || vocals.second.size != length) return null
            for (q in 0 until length) {
                val weight = if (starts.size == 1) 1f else StemMath.chunkWeight(starts, window, index, q)
                vocalLeft[from + q] += weight * vocals.first[q]
                vocalRight[from + q] += weight * vocals.second[q]
            }
        }
        if (!allFinite(listOf(vocalLeft, vocalRight))) return null
        val otherLeft = FloatArray(n)
        val otherRight = FloatArray(n)
        StemMath.foldResidualIntoOther(left, vocalLeft, null, null, otherLeft)
        StemMath.foldResidualIntoOther(right, vocalRight, null, null, otherRight)
        return StemSet(
            sampleRate = rate,
            frames = n,
            regionStartSec = start,
            left = mapOf(StemKind.VOCALS to vocalLeft, StemKind.OTHER to otherLeft),
            right = mapOf(StemKind.VOCALS to vocalRight, StemKind.OTHER to otherRight),
            stemCount = 2,
            confidence = LEGACY_CONFIDENCE,
        )
    }

    private fun allFinite(arrays: Collection<FloatArray>): Boolean {
        for (array in arrays) {
            for (value in array) if (!value.isFinite()) return false
        }
        return true
    }

    private fun onPlaybackThread(): Boolean {
        val name = Thread.currentThread().name
        return name.startsWith("ExoPlayer:Playback") || name.contains("AudioTrack") || name.startsWith("AAudio")
    }

    companion object {
        const val LEGACY_TAG = "umxhq2"
        const val LEGACY_CONFIDENCE = 0.5f
        private const val LEGACY_CROSSFADE_SECONDS = 1.5
        private const val MAX_FOUR_STEM_FAILURES = 3

        /**
         * Network confidence scaled down when its raw estimates did not add up to the mixture
         * (spectral residual) or the time-domain fold had to move a lot of energy into OTHER.
         */
        internal fun confidenceFor(base: Float, spectralResidual: Double, foldedResidual: Double): Float {
            val worst = maxOf(spectralResidual.coerceAtLeast(0.0), foldedResidual.coerceAtLeast(0.0))
            val factor = (1.0 - 2.0 * sqrt(worst)).coerceIn(0.3, 1.0)
            return (base * factor).toFloat()
        }
    }
}
