/*
 * Beat-grid seam repair for Automix.
 *
 * The overlap rules — tempo within 1.5% or an octave of that, then a
 * downbeat-phase shift of a whole beat — follow BKmasterplayer's analysis
 * (MIT), app/rust/src/engine/analysis.rs. The spectral-flux onset snap is
 * the same idea: a beat may move at most 35 ms onto a local onset.
 *
 * Copyright (C) 2026 thieggs and BKmasterplayer contributors
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Permission is granted under the MIT license; see THIRD_PARTY_NOTICES.
 */

package com.music.bitchord.playback.smart

import kotlin.math.abs

/**
 * Joins beat windows and pulls beats onto onsets. Scalar times only: no
 * audio, no cross-track correlation.
 */
object GridRepair {
    data class Window(
        val beats: List<Double>,
        val downbeats: List<Double>,
        val bpm: Double,
        val confidence: Double = 1.0,
    )

    data class Joined(val beats: List<Double>, val downbeats: List<Double>)

    /**
     * Concatenates [windows] at each overlap's midpoint. When the next
     * window's tempo agrees (within 1.5%, or an octave of that) but its
     * beats sit a whole beat off the previous window, the next window is
     * shifted by that beat before the join. A window that still doesn't
     * land is left where the midpoint split puts it.
     */
    fun reconcile(windows: List<Window>): Joined {
        val sorted = windows
            .filter { it.beats.size >= 2 && it.bpm > 0 }
            .sortedBy { it.beats.first() }
        if (sorted.isEmpty()) return Joined(emptyList(), emptyList())
        val beats = ArrayList<Double>()
        val downs = ArrayList<Double>()
        var previous: List<Double> = emptyList()
        var previousBpm = 0.0
        for (window in sorted) {
            var useBeats = window.beats
            var useDowns = window.downbeats
            if (previousBpm > 0 && agrees(previousBpm, window.bpm)) {
                val beat = 60.0 / window.bpm
                val shift = phaseShift(previous, window.beats, beat)
                if (shift != 0.0) {
                    useBeats = window.beats.map { it + shift }
                    useDowns = window.downbeats.map { it + shift }
                }
            }
            val from = if (previous.isEmpty()) {
                Double.NEGATIVE_INFINITY
            } else {
                val prevEnd = previous.last()
                val thisStart = useBeats.first()
                (prevEnd + thisStart) / 2.0
            }
            while (beats.isNotEmpty() && beats.last() >= from) beats.removeAt(beats.lastIndex)
            while (downs.isNotEmpty() && downs.last() >= from) downs.removeAt(downs.lastIndex)
            beats += useBeats.filter { it >= from }
            downs += useDowns.filter { it >= from }
            previous = useBeats
            previousBpm = window.bpm
        }
        return Joined(beats, downs)
    }

    /** True when [b] is [a], half of [a], or double [a], within 1.5%. */
    fun agrees(a: Double, b: Double): Boolean {
        if (a <= 0.0 || b <= 0.0) return false
        val ratio = b / a
        val folded = when {
            ratio > 1.5 -> ratio / 2.0
            ratio < 1.0 / 1.5 -> ratio * 2.0
            else -> ratio
        }
        return abs(folded - 1.0) <= 0.015
    }

    /**
     * Whole-beat shift (in seconds, added to the new window) that lands
     * [next] on [previous]. Zero when they already agree, or when no
     * one-beat nudge gets the median error under 15% of a beat.
     */
    fun phaseShift(previous: List<Double>, next: List<Double>, beatSec: Double): Double {
        if (previous.size < 4 || next.size < 4 || beatSec <= 0.0) return 0.0
        val overlapFrom = maxOf(previous.first(), next.first())
        val overlapTo = minOf(previous.last(), next.last())
        if (overlapTo - overlapFrom < beatSec) return 0.0
        var bestShift = 0.0
        var bestError = Double.POSITIVE_INFINITY
        for (steps in intArrayOf(-1, 0, 1)) {
            val shift = steps * beatSec
            val errors = ArrayList<Double>()
            for (time in next) {
                val moved = time + shift
                if (moved < overlapFrom || moved > overlapTo) continue
                val near = nearest(previous, moved) ?: continue
                errors += abs(near - moved)
            }
            if (errors.size < 4) continue
            errors.sort()
            val median = errors[errors.size / 2]
            if (median < bestError) {
                bestError = median
                bestShift = shift
            }
        }
        return if (bestError <= beatSec * 0.15) bestShift else 0.0
    }

    /**
     * Moves every beat by the median onset error, and only when that error
     * is within [windowSec] and at least half the beats have an onset that
     * close. Otherwise the beats come back unchanged.
     */
    fun snapToOnsets(beats: List<Double>, onsets: List<Double>, windowSec: Double = 0.035): List<Double> {
        if (beats.size < 8 || onsets.isEmpty()) return beats
        val shifts = ArrayList<Double>()
        for (beat in beats) {
            val onset = nearest(onsets, beat) ?: continue
            val delta = onset - beat
            if (abs(delta) <= windowSec) shifts += delta
        }
        if (shifts.size < 8 || shifts.size * 2 < beats.size) return beats
        shifts.sort()
        val median = shifts[shifts.size / 2]
        if (abs(median) < 0.0005) return beats
        return beats.map { it + median }
    }

    /**
     * Fraction of [beats] that land within [windowSec] of an onset.
     * Negative when there isn't enough evidence to score the grid.
     */
    fun phaseCoherence(beats: List<Double>, onsets: List<Double>, windowSec: Double = 0.035): Double {
        if (beats.size < 8 || onsets.isEmpty()) return -1.0
        var hits = 0
        for (beat in beats) {
            val onset = nearest(onsets, beat) ?: continue
            if (abs(onset - beat) <= windowSec) hits++
        }
        return hits.toDouble() / beats.size
    }

    private fun nearest(sorted: List<Double>, target: Double): Double? {
        if (sorted.isEmpty()) return null
        var low = 0
        var high = sorted.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] < target) low = mid + 1 else high = mid
        }
        val after = sorted[low]
        val before = sorted.getOrNull(low - 1) ?: return after
        return if (abs(before - target) <= abs(after - target)) before else after
    }
}
