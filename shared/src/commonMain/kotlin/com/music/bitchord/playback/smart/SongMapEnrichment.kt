package com.music.bitchord.playback.smart

import kotlin.math.abs

/**
 * Grafts Pass 2 vocal-window evidence onto a Pass 1 PCM [SongMap].
 *
 * The PCM structure pass runs from full-track audio without the vocal
 * model, so its [SongMap.sungBars] come from a null mask and its
 * [SongMap.vocalEnergy] is empty ("not measured"). [SongMaps.fromAnalysis]
 * does the same graft for scalar-only maps, but the planner actually reads
 * the PCM map through `PhraseMaps`, so without this the measured energy
 * never reaches scoring, vetoes, or the stem-duck decision.
 *
 * Coverage: Pass 2 `vocalWindows` only tiles head ~45s + tail ~60s per
 * `TrackAnalyzer.kt`. Uncovered middle bars remain explicitly unmeasured and
 * must not be treated as measured silence by planner consumers.
 */
object SongMapEnrichment {

    /**
     * Returns [base] unchanged unless [analysis] carries usable vocal
     * windows that line up with [base] on time.
     *
     * Sampling is by time against `[base.barTime(b), base.barTime(b + 1))`
     * for each bar, never by analysis bar index: the two grids are on
     * different BPM estimates of the same timeline, and only the base
     * map's downbeats define what a bar is here. A BPM disagreement over
     * 2% refuses the graft rather than smearing energy across the wrong
     * bars.
     */
    fun enrich(base: SongMap, analysis: TrackAnalysis): SongMap {
        if (analysis.quality != TrackAnalysis.QUALITY_FULL || analysis.vocalWindows.isEmpty()) return base
        if (!analysis.bpm.isFinite() || !base.bpm.isFinite() || analysis.bpm <= 0.0 || base.bpm <= 0.0 ||
            abs(base.bpm - analysis.bpm) / analysis.bpm > 0.02
        ) return base
        val n = base.sungBars.size
        if (n <= 0) return base

        // The structure classifier and vocal windows were computed on the base
        // timeline. Do not retime those bars to sparse head/tail model beats
        // without re-aggregating every feature; preserve phase and provenance.
        val barStarts = DoubleArray(n + 1) { base.barTime(it) }
        val seen = BooleanArray(n)
        val sungFromWin = BooleanArray(n)
        val energyAccum = DoubleArray(n)
        val energyCounts = IntArray(n)

        for (window in analysis.vocalWindows) {
            if (window.values.isEmpty() || !window.frameRate.isFinite() || window.frameRate <= 0.0 ||
                !window.startTime.isFinite()
            ) continue
            for (b in 0 until n) {
                val t0 = barStarts[b]
                val t1 = barStarts[b + 1]
                var sum = 0.0
                var count = 0
                var above = 0
                var peak = 0f
                for (i in window.values.indices) {
                    val t = window.startTime + i / window.frameRate
                    if (t < t0 || t >= t1) continue
                    val v = window.values[i]
                    if (!v.isFinite()) continue
                    sum += v.toDouble()
                    count++
                    if (v > SongMaps.VOCAL_BAR_GATE) above++
                    if (v > peak) peak = v
                }
                if (count == 0) continue
                val mean = sum / count
                energyAccum[b] += sum
                energyCounts[b] += count
                seen[b] = true
                if (peak > SongMaps.VOCAL_BAR_GATE && (mean > 0.08 || above >= 2 || peak >= 0.35f)) {
                    sungFromWin[b] = true
                }
            }
        }

        if (seen.none { it }) return base

        val outSung = BooleanArray(n) { b -> if (seen[b]) sungFromWin[b] else base.sungBars[b] }
        val outEnergy = FloatArray(n) { b ->
            if (seen[b]) (energyAccum[b] / energyCounts[b]).toFloat().coerceIn(0f, 1f)
            else if (b < base.vocalEnergy.size) base.vocalEnergy[b] else 0f
        }

        val newSections = base.sections.map { sec ->
            val cStart = maxOf(sec.startBar, 0)
            val cEnd = minOf(sec.endBar, n)
            if (cEnd <= cStart) return@map sec
            var overlapsCovered = false
            var sungCount = 0
            var measuredBars = 0
            for (b in cStart until cEnd) {
                if (seen[b]) { overlapsCovered = true; measuredBars++ }
                if (seen[b] && outSung[b]) sungCount++
            }
            if (!overlapsCovered) return@map sec
            val vocal = sungCount.toFloat() / measuredBars.coerceAtLeast(1)
            val fn = when {
                sec.fn == SectionFn.FADE_OUT || sec.fn == SectionFn.SILENCE -> sec.fn
                vocal >= 0.3f && sec.fn == SectionFn.INSTRUMENTAL -> SectionFn.VERSE
                else -> sec.fn
            }
            sec.copy(
                fn = fn,
                role = when (fn) {
                    SectionFn.VERSE, SectionFn.BRIDGE -> SectionRole.NARRATIVE
                    SectionFn.CHORUS, SectionFn.DROP -> SectionRole.RELEASE
                    else -> sec.role
                },
                vocal = vocal,
                loopable = sec.loopable && vocal < 0.2f && (cStart until cEnd).all {
                    seen[it] || base.isVocalMeasured(it)
                },
                conf = sec.conf,
            )
        }

        // Occupancy invalidates an instrumental claim; it cannot establish a
        // chorus, new phrase anchors or additional boundary confidence.
        val recomputedSections = newSections

        val first = outSung.indexOfFirst { it }.let { if (it < 0) null else it }
        val last = outSung.indexOfLast { it }.let { if (it < 0) null else it }
        val pickup = if (first == 1) 1f else base.pickupBeats
        val startKind = when {
            base.startKind == StartKind.SPOKEN || base.startKind == StartKind.ACAPELLA -> base.startKind
            first == 0 -> StartKind.COLD_VOCAL
            first == 1 -> StartKind.VOCAL_PICKUP
            else -> base.startKind
        }

        val tailVocal = recomputedSections.lastOrNull()?.vocal ?: 0f
        val ending = when {
            base.endingKind == EndingKind.FADE_OUT -> base.endingKind
            tailVocal >= 0.3f -> EndingKind.OUTRO_VOCAL
            else -> base.endingKind
        }
        val anchors = base.anchors
        val confidence = base.labelConfidence
        return base.copy(
            sungBars = outSung,
            vocalEnergy = outEnergy,
            sections = recomputedSections,
            firstVocalBar = first,
            lastVocalBar = last,
            pickupBeats = pickup,
            startKind = startKind,
            endingKind = ending,
            chorusCount = recomputedSections.count { it.fn == SectionFn.CHORUS },
            labelConfidence = confidence,
            anchors = anchors,
            downbeats = base.downbeats,
            gridConfidence = base.gridConfidence,
            tempoStable = base.tempoStable,
            gridMeasured = base.gridMeasured,
            vocalCoverage = BooleanArray(n) { seen[it] || base.isVocalMeasured(it) },
            provenance = when {
                base.provenance.isBlank() -> "enriched:vocal-model"
                base.provenance.endsWith("+vocal-model") -> base.provenance
                else -> base.provenance + "+vocal-model"
            },
        )
    }
}
