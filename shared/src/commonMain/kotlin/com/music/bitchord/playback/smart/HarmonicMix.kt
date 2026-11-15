/*
 * Camelot wheel and local beat-grid steadiness for Automix.
 *
 * The wheel and the "three regular bars, or it is not a bar" test are ported
 * from BKmasterplayer (MIT), app/rust/src/engine/analysis.rs:
 *
 * Copyright (C) 2026 thieggs and BKmasterplayer contributors
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Permission is granted under the MIT license; see THIRD_PARTY_NOTICES.
 */

package com.music.bitchord.playback.smart

import kotlin.math.abs

private val PITCH_CLASS = mapOf(
    "C" to 0, "C#" to 1, "DB" to 1,
    "D" to 2, "D#" to 3, "EB" to 3,
    "E" to 4,
    "F" to 5, "F#" to 6, "GB" to 6,
    "G" to 7, "G#" to 8, "AB" to 8,
    "A" to 9, "A#" to 10, "BB" to 10,
    "B" to 11,
)

/** Circle-of-fifths slot for each pitch class, major then minor. From BKmasterplayer. */
private val CAMELOT_MAJOR = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
private val CAMELOT_MINOR = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

/**
 * Camelot code for a key such as "C major" or "A minor", or null when the
 * name is not a key. Major is the B ring, minor the A ring, matching the
 * Mixed In Key wheel.
 */
fun camelotCode(key: String): String? {
    val parts = key.trim().split(Regex("\\s+"))
    if (parts.isEmpty() || parts[0].isEmpty()) return null
    val token = parts[0].replace("♯", "#").replace("♭", "b").uppercase()
    val root = PITCH_CLASS[token] ?: return null
    val mode = parts.getOrNull(1)?.lowercase()
    val minor = mode == "minor" || mode == "min" || mode == "m"
    val major = mode == null || mode == "major" || mode == "maj"
    if (!minor && !major) return null
    val number = if (minor) CAMELOT_MINOR[root] else CAMELOT_MAJOR[root]
    return "$number${if (minor) 'A' else 'B'}"
}

/**
 * Two Camelot codes mix: same number (relative major/minor), or the same
 * ring one step around the wheel, including the 12↔1 wrap.
 */
fun camelotCompatible(left: String, right: String): Boolean {
    fun parse(code: String): Pair<Int, Char>? {
        if (code.length < 2) return null
        val letter = code.last()
        val number = code.dropLast(1).toIntOrNull() ?: return null
        if (letter != 'A' && letter != 'B') return null
        if (number !in 1..12) return null
        return number to letter
    }
    val a = parse(left) ?: return false
    val b = parse(right) ?: return false
    val raw = abs(a.first - b.first)
    val dist = minOf(raw, 12 - raw)
    return (a.second == b.second && dist <= 1) || a.first == b.first
}

/**
 * Steps around the wheel between two key names. Same number (relative
 * major/minor, or the same key) is 0. A different ring adds one step.
 * Null when either name is not a key.
 */
fun camelotSteps(left: String, right: String): Int? {
    fun parse(code: String): Pair<Int, Char>? {
        if (code.length < 2) return null
        val letter = code.last()
        val number = code.dropLast(1).toIntOrNull() ?: return null
        if (letter != 'A' && letter != 'B') return null
        if (number !in 1..12) return null
        return number to letter
    }
    val a = parse(camelotCode(left) ?: return null) ?: return null
    val b = parse(camelotCode(right) ?: return null) ?: return null
    if (a.first == b.first) return 0
    val raw = abs(a.first - b.first)
    val dist = minOf(raw, 12 - raw)
    return dist + if (a.second == b.second) 0 else 1
}

/**
 * Whether [beats] is a steady grid. A short list is treated as steady,
 * because refusing a mix for lack of evidence is worse than mixing.
 *
 * Hand-played drumming shows up as a wide spread of beat intervals: the
 * interquartile range is more than [spread] of the median. A click track
 * stays well under that.
 */
/**
 * One steady piece of a beat list: a run whose intervals stay within 6% of
 * their own median. A hand-played song is several of these, not one average.
 *
 * Ported in spirit from BKmasterplayer's `fit_grid` / region join: the period
 * is the dominant interval of the beats that agree, and a piece shorter than
 * eight beats is not a grid.
 */
data class FittedGrid(
    val start: Double,
    val end: Double,
    val period: Double,
    val beats: Int,
)

fun fitBeatSegments(beats: List<Double>): List<FittedGrid> {
    if (beats.size < 8) return emptyList()
    val out = ArrayList<FittedGrid>()
    var i = 0
    while (i < beats.size - 1) {
        val gaps = ArrayList<Double>()
        var j = i
        while (j + 1 < beats.size) {
            val gap = beats[j + 1] - beats[j]
            if (gap !in 0.28..1.4) break
            if (gaps.isNotEmpty()) {
                val med = gaps.sorted()[gaps.size / 2]
                if (gaps.size >= 4 && abs(gap / med - 1.0) > 0.06) {
                    val dev = gap - med
                    val fwdOk = j + 2 < beats.size &&
                        abs((beats[j + 2] - beats[j]) / (2.0 * med) - 1.0) <= 0.06 &&
                        dev * ((beats[j + 2] - beats[j + 1]) - med) < 0.0
                    val bwdOk = j - 1 >= i &&
                        abs((beats[j + 1] - beats[j - 1]) / (2.0 * med) - 1.0) <= 0.06 &&
                        dev * ((beats[j] - beats[j - 1]) - med) < 0.0
                    val isolatedSnap = abs(dev) <= 0.075 && (fwdOk || bwdOk)
                    if (!isolatedSnap) break
                }
            }
            gaps += gap
            j++
            if (gaps.size >= 48) break
        }
        if (gaps.size >= 7) {
            val sorted = gaps.sorted()
            val med = sorted[sorted.size / 2]
            out += FittedGrid(beats[i], beats[j], med, gaps.size + 1)
            i = j
        } else if (gaps.isNotEmpty() && abs(gaps[0] / gaps.sorted()[gaps.size / 2] - 1.0) > 0.15) {
            i += 1
        } else {
            i = if (j > i) j else i + 1
        }
    }
    return out
}

/**
 * The tempo of the steady piece at the start or the end of [beats]. Null when
 * that end has no piece long enough to trust — the only case a drifting grid
 * should refuse a beatmatch.
 */
/**
 * Tempo to lock a join to.
 *
 * A steady piece that still agrees with the song is the piece to match: a
 * track can drift and settle. A piece an octave away, or more than 15% off
 * the song's own tempo, is a different count of the same music. Matching
 * that retimes the song away from itself, which is the forced beatmatch
 * that pulls the arrangement apart. The song's own tempo wins.
 */
fun joinBpm(local: Double?, global: Double): Double {
    if (local == null || !local.isFinite() || local <= 0.0) return global
    if (!global.isFinite() || global <= 0.0) return local
    val ratio = local / global
    if (ratio < 0.85 || ratio > 1.15) return global
    return local
}

fun fittedBpm(beats: List<Double>, atEnd: Boolean): Double? {
    if (beats.size < 8) return null
    val at = if (atEnd) beats.last() else beats.first()
    val segments = fitBeatSegments(beats)
    val pred: (FittedGrid) -> Boolean = {
        val margin = maxOf(0.75, it.period * 2.5)
        at >= it.start - margin && at <= it.end + margin
    }
    val seg = (if (atEnd) segments.lastOrNull(pred) else segments.firstOrNull(pred))
        ?: return null
    return 60.0 / seg.period
}

fun beatGridSteady(beats: List<Double>, spread: Double = 0.08): Boolean {
    if (beats.size < 8) return true
    val intervals = ArrayList<Double>(beats.size)
    for (i in 1 until beats.size) {
        val gap = beats[i] - beats[i - 1]
        if (gap in 0.28..1.4) intervals += gap
    }
    if (intervals.size < 6) return true
    intervals.sort()
    val median = intervals[intervals.size / 2]
    if (median <= 0.0) return true
    val q1 = intervals[intervals.size / 4]
    val q3 = intervals[(intervals.size * 3) / 4]
    return (q3 - q1) / median <= spread
}
