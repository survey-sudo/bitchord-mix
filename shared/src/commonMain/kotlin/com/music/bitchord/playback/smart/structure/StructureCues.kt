package com.music.bitchord.playback.smart.structure

import com.music.bitchord.playback.smart.SectionKind
import com.music.bitchord.playback.smart.SongMap
import kotlin.math.abs

/**
 * AUTOMIX-V2(D): read-only helpers for the planner (Stream E) over a v2 [SongMap].
 * All return track seconds and are empty / null when the map has no v2 structure.
 */
object StructureCues {

    /** Section starts whose boundary confidence is at least [minConfidence], seconds, ascending. */
    fun phraseStarts(map: SongMap, minConfidence: Float = 0.5f): List<Double> {
        val v2 = map.structure ?: return emptyList()
        val out = ArrayList<Double>()
        for (i in v2.boundaries.indices) {
            val b = v2.boundaries[i]
            if (b <= 0 || b >= v2.bars) continue
            if (v2.boundaryConfidence[i] >= minConfidence) out.add(v2.barTime(b))
        }
        return out
    }

    /**
     * The v2 boundary nearest [sec] within [toleranceBars] bars (default one bar),
     * or null. Use to pull a cue that is "almost" on a phrase onto it.
     */
    fun snapToPhrase(map: SongMap, sec: Double, toleranceBars: Double = 1.0, minConfidence: Float = 0.3f): Double? {
        val v2 = map.structure ?: return null
        val tol = toleranceBars * v2.barSec
        var best: Double? = null
        var bestD = Double.MAX_VALUE
        for (i in v2.boundaries.indices) {
            if (v2.boundaryConfidence[i] < minConfidence) continue
            val t = v2.barTime(v2.boundaries[i])
            val d = abs(t - sec)
            if (d <= tol && d < bestD) {
                bestD = d
                best = t
            }
        }
        return best
    }

    /**
     * Where an incoming track is most rewarding to land, best first: drops, then
     * chorus-family starts, then hooks. Each is a downbeat in track seconds.
     */
    fun landings(map: SongMap): List<Double> {
        val v2 = map.structure ?: return emptyList()
        val out = ArrayList<Double>()
        for (d in v2.drops.sortedByDescending { it.strength }) out.add(v2.barTime(d.bar))
        for (s in v2.sections) if (s.kind == SectionKind.CHORUS) out.add(v2.barTime(s.startBar))
        for (s in v2.sections) if (s.kind == SectionKind.HOOK) out.add(v2.barTime(s.startBar))
        return out.distinct()
    }

    /**
     * Natural exit points of an outgoing track, latest-first: outro start, the
     * breakdown after the last drop/chorus, and the end of the last chorus.
     */
    fun exits(map: SongMap): List<Double> {
        val v2 = map.structure ?: return emptyList()
        val out = ArrayList<Double>()
        val outro = v2.sections.lastOrNull { it.kind == SectionKind.OUTRO }
        if (outro != null) out.add(v2.barTime(outro.startBar))
        val lastPeak = v2.sections.lastOrNull { it.kind == SectionKind.CHORUS || it.kind == SectionKind.DROP }
        if (lastPeak != null) {
            val down = v2.sections.firstOrNull { it.kind == SectionKind.BREAKDOWN && it.startBar >= lastPeak.endBar }
            if (down != null) out.add(v2.barTime(down.startBar))
            out.add(v2.barTime(lastPeak.endBar))
        }
        return out.distinct()
    }

    /** rekordbox-style phrase tag at [sec], or null. */
    fun tagAt(map: SongMap, sec: Double): PhraseTag? {
        val v2 = map.structure ?: return null
        return v2.sections.lastOrNull { v2.barTime(it.startBar) <= sec + 1e-6 && sec < v2.barTime(it.endBar) }?.tag
    }
}
