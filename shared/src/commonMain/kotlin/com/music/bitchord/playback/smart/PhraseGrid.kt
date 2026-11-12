package com.music.bitchord.playback.smart

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Bar grid, phrase anchor and the phrase dynamic program.
 * Lengths of 8 and 16 are free. A 2-bar or 12-bar phrase is allowed when the
 * novelty at its edge pays for the cost, which is how a pop extension survives.
 */
object PhraseGrid {
    fun lenCost(bars: Int): Double = PhraseConfig.lenCost(bars)

    /**
     * Boundaries that maximise Σ nov[b] − [PhraseConfig.LEN_COST_WEIGHT] · lenCost.
     * Always includes 0 and [novelty.size]. Lengths stay inside 2..32.
     */
    fun boundaries(novelty: DoubleArray): IntArray {
        val n = novelty.size
        if (n <= 0) return intArrayOf(0)
        if (n <= PhraseConfig.LEN_MIN_BARS) return intArrayOf(0, n)
        val dp = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val prev = IntArray(n + 1) { -1 }
        dp[0] = 0.0
        for (end in 1..n) {
            val lo = max(0, end - PhraseConfig.LEN_MAX_BARS)
            val hi = end - PhraseConfig.LEN_MIN_BARS
            var start = lo
            while (start <= hi) {
                if (dp[start] > Double.NEGATIVE_INFINITY / 2) {
                    val len = end - start
                    val score = dp[start] + noveltyAt(novelty, end) - PhraseConfig.LEN_COST_WEIGHT * lenCost(len)
                    if (score > dp[end]) {
                        dp[end] = score
                        prev[end] = start
                    }
                }
                start++
            }
        }
        if (prev[n] < 0) return intArrayOf(0, n)
        val out = ArrayList<Int>()
        var at = n
        while (at > 0 && out.size <= n) {
            out.add(at)
            val next = prev[at]
            if (next < 0 || next >= at) break
            at = next
        }
        out.add(0)
        out.reverse()
        return out.toIntArray()
    }

    private fun noveltyAt(novelty: DoubleArray, bar: Int): Double {
        if (bar <= 0) return 0.0
        if (bar >= novelty.size) return novelty.last()
        return novelty[bar]
    }

    /** argmax over phase 0..7 of the 8-bar novelty, with a half-weight 4-bar term. */
    fun anchor(novelty: DoubleArray, firstStrongBar: Int = 0): Int {
        if (novelty.isEmpty()) return 0
        var bestA = 0
        var best = Double.NEGATIVE_INFINITY
        for (a in 0..7) {
            var sum = 0.0
            for (b in novelty.indices) {
                if (b % 8 == a) sum += novelty[b]
                if (b % 4 == a) sum += 0.5 * novelty[b]
            }
            if (sum > best + 1e-9) {
                best = sum
                bestA = a
            }
        }
        // A tie goes to the first downbeat after the first onset. Equal scores keep the lower phase.
        return if (bestA == 0) 0 else bestA.coerceIn(0, 7).let { phase ->
            if (kotlin.math.abs(best - scoreOf(novelty, firstStrongBar % 8)) < 1e-6) firstStrongBar % 8 else phase
        }
    }

    private fun scoreOf(novelty: DoubleArray, a: Int): Double {
        var sum = 0.0
        for (b in novelty.indices) {
            if (b % 8 == a) sum += novelty[b]
            if (b % 4 == a) sum += 0.5 * novelty[b]
        }
        return sum
    }

    /**
     * Off-grid boundaries stronger than mean + 1.5σ start a local phrase grid.
     * [isPhraseStart] then uses the latest anchor at or before the bar.
     */
    fun reanchors(novelty: DoubleArray, bounds: IntArray, globalAnchor: Int): IntArray {
        if (novelty.isEmpty()) return intArrayOf(globalAnchor)
        val mean = novelty.average()
        var variance = 0.0
        for (v in novelty) variance += (v - mean) * (v - mean)
        val sigma = sqrt(variance / novelty.size.coerceAtLeast(1))
        val gate = mean + 1.5 * sigma
        val found = ArrayList<Int>()
        found.add(globalAnchor)
        for (b in bounds) {
            if (b <= 0 || b >= novelty.size) continue
            if (novelty[b] <= gate) continue
            val onGrid = (b - globalAnchor) % 8 == 0
            if (!onGrid) found.add(b)
        }
        return found.toIntArray()
    }

    fun isPhraseStart(bar: Int, anchor: Int, anchors: IntArray, phraseLen: Int = 8): Boolean {
        var local = anchor
        for (a in anchors) if (a <= bar) local = a
        val len = if (phraseLen < 1) 8 else phraseLen
        return bar >= local && (bar - local) % len == 0
    }

    /** Move each beat onto the strongest flux sample within ±35 ms. Returns the fraction snapped inside 20 ms. */
    fun snapToFlux(
        beats: DoubleArray,
        fluxTimes: DoubleArray,
        fluxValues: DoubleArray,
        windowSec: Double = 0.035,
    ): Snap {
        if (beats.isEmpty() || fluxTimes.isEmpty()) return Snap(beats, 0f)
        val out = DoubleArray(beats.size)
        var inside = 0
        for (i in beats.indices) {
            val t = beats[i]
            var bestT = t
            var bestV = Double.NEGATIVE_INFINITY
            for (k in fluxTimes.indices) {
                val d = fluxTimes[k] - t
                if (d < -windowSec) continue
                if (d > windowSec) break
                if (fluxValues[k] > bestV) {
                    bestV = fluxValues[k]
                    bestT = fluxTimes[k]
                }
            }
            out[i] = if (bestV > Double.NEGATIVE_INFINITY / 2) bestT else t
            if (kotlin.math.abs(out[i] - t) <= 0.020) inside++
        }
        return Snap(out, if (beats.isEmpty()) 0f else inside.toFloat() / beats.size)
    }

    data class Snap(val beats: DoubleArray, val within20ms: Float)

    /**
     * Mean normalised flux at the snapped beats, times the fraction that
     * landed within 20 ms. A grid sitting on the off-beat, where the flux
     * window is empty, comes back at 0.4 or below.
     */
    fun gridConfidence(
        beats: DoubleArray,
        fluxTimes: DoubleArray,
        fluxValues: DoubleArray,
    ): Float {
        if (beats.isEmpty() || fluxValues.isEmpty()) return 0f
        val snap = snapToFlux(beats, fluxTimes, fluxValues)
        var peak = 1e-9
        for (v in fluxValues) if (v > peak) peak = v
        var sum = 0.0
        for (t in snap.beats) {
            var best = 0.0
            var bestD = Double.MAX_VALUE
            for (k in fluxTimes.indices) {
                val d = kotlin.math.abs(fluxTimes[k] - t)
                if (d < bestD) {
                    bestD = d
                    best = fluxValues[k]
                }
            }
            if (bestD <= 0.035) sum += best / peak
        }
        val mean = sum / beats.size
        return (mean * snap.within20ms).toFloat().coerceIn(0f, 1f)
    }
}
