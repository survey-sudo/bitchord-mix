package com.music.bitchord.playback.smart

import com.music.bitchord.playback.smart.structure.AutomixV2Structure
import com.music.bitchord.playback.smart.structure.StructureV2
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
 * `TrackAnalyzer.kt:1432-1433`, so uncovered middle bars read `0f`. That is
 * safe because D1/D2 read exit-tail and entry-head bars, which are exactly
 * the covered regions; the middle is never the overlap.
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
        if (analysis.bpm <= 0.0 || base.bpm <= 0.0 || abs(base.bpm - analysis.bpm) / analysis.bpm > 0.02) return base
        val n = base.sungBars.size
        if (n <= 0) return base

        val barStarts = DoubleArray(n + 1) { base.barTime(it) }
        val seen = BooleanArray(n)
        val sungFromWin = BooleanArray(n)
        val energyAccum = DoubleArray(n)

        for (window in analysis.vocalWindows) {
            if (window.values.isEmpty() || window.frameRate <= 0.0) continue
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
                    sum += v.toDouble()
                    count++
                    if (v > SongMaps.VOCAL_BAR_GATE) above++
                    if (v > peak) peak = v
                }
                if (count == 0) continue
                val mean = sum / count
                energyAccum[b] = if (seen[b]) (energyAccum[b] + mean) / 2.0 else mean
                seen[b] = true
                if (peak > SongMaps.VOCAL_BAR_GATE && (mean > 0.08 || above >= 2 || peak >= 0.35f)) {
                    sungFromWin[b] = true
                }
            }
        }

        if (seen.none { it }) return base

        val outSung = BooleanArray(n) { b -> if (seen[b]) sungFromWin[b] else base.sungBars[b] }
        val outEnergy = FloatArray(n) { b ->
            if (seen[b]) energyAccum[b].toFloat().coerceIn(0f, 1f)
            else if (b < base.vocalEnergy.size) base.vocalEnergy[b] else 0f
        }

        val newSections = base.sections.map { sec ->
            val cStart = maxOf(sec.startBar, 0)
            val cEnd = minOf(sec.endBar, n)
            if (cEnd <= cStart) return@map sec
            var overlapsCovered = false
            var sungCount = 0
            for (b in cStart until cEnd) {
                if (seen[b]) overlapsCovered = true
                if (outSung[b]) sungCount++
            }
            if (!overlapsCovered) return@map sec
            val len = cEnd - cStart
            sec.copy(vocal = sungCount.toFloat() / len)
        }

        val first = outSung.indexOfFirst { it }.let { if (it < 0) null else it }
        val last = outSung.indexOfLast { it }.let { if (it < 0) null else it }
        val pickup = if (first == 1) 1f else base.pickupBeats
        val startKind = when {
            base.startKind == StartKind.SPOKEN || base.startKind == StartKind.ACAPELLA -> base.startKind
            first == 0 -> StartKind.COLD_VOCAL
            first == 1 -> StartKind.VOCAL_PICKUP
            else -> base.startKind
        }

        return base.copy(
            sungBars = outSung,
            vocalEnergy = outEnergy,
            sections = newSections,
            firstVocalBar = first,
            lastVocalBar = last,
            pickupBeats = pickup,
            startKind = startKind,
            // AUTOMIX-V2(D): intro/outro vocal-free counts follow the measured vocals.
            structure = base.structure?.let { v2 ->
                if (AutomixV2Structure.enabled) runCatching { StructureV2.refreshEdges(v2, outSung) }.getOrElse { v2 } else v2
            },
        )
    }
}
