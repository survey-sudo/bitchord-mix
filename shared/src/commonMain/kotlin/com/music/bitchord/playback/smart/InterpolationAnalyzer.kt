package com.music.bitchord.playback.smart

/**
 * Interpolation (phrase reuse / mashup awareness), djmix25 P1.2.
 *
 * Scalars only, patent-safe: per-bar fingerprints from [SongMap] scalars
 * (energy level + sung bit) with no cross-track audio comparison. Two bars
 * with matching fingerprints are "interchangeable": an exit at a repeated
 * bar can be looped/extended seamlessly, and a clean outro phrase can be
 * replayed under the next track's intro.
 *
 * Fingerprint: quantize [PhraseSection.energyLevel] (1..10) to 0..10 and
 * combine with the sung bit: `energyLevel * 2 + (sung ? 1 : 0)`. Bars with
 * identical fingerprints match; adjacent levels within tolerance 1 also
 * count as a weak match.
 */
object InterpolationAnalyzer {

    /** Fingerprint of [bar] in [map]; -1 when there is no map evidence. */
    fun fingerprint(map: SongMap, bar: Int): Int {
        if (bar < 0 || bar >= map.sungBars.size) return -1
        val section = map.sectionAt(bar) ?: return -1
        val sung = if (map.sungBars[bar]) 1 else 0
        return section.energyLevel.coerceIn(1, 10) * 2 + sung
    }

    /** Full fingerprint array for [map] (empty when the map has no bars). */
    fun fingerprints(map: SongMap): IntArray {
        val n = map.sungBars.size
        if (n <= 0) return intArrayOf()
        return IntArray(n) { fingerprint(map, it) }
    }

    /**
     * How many other bars share [bar]'s fingerprint (exact), beyond
     * [minDistance] bars away. 0 means the bar is unique.
     */
    fun repeatedBars(map: SongMap, bar: Int, minDistance: Int = 8): Int {
        val fp = fingerprint(map, bar)
        if (fp < 0) return 0
        var count = 0
        for (other in 0 until map.sungBars.size) {
            if (other == bar) continue
            if (kotlin.math.abs(other - bar) < minDistance) continue
            if (fingerprint(map, other) == fp) count++
        }
        return count
    }

    /**
     * First bar with the same fingerprint as [bar], or [bar] itself when
     * the bar is unique. `firstOccurrence(bar) = bar - matchedDistance`.
     */
    fun firstOccurrence(map: SongMap, bar: Int): Int {
        val fp = fingerprint(map, bar)
        if (fp < 0) return bar
        for (other in 0 until bar) {
            if (fingerprint(map, other) == fp) return other
        }
        return bar
    }

    /**
     * Bars that repeat an earlier bar (reuse opportunities): "run this
     * chorus again under B's intro" or "extend a clean outro phrase by
     * replaying an earlier clean bar". Returned in bar order.
     */
    fun interpolationOpportunities(map: SongMap): List<Int> {
        val n = map.sungBars.size
        if (n < 8) return emptyList()
        val seen = HashMap<Int, Int>()
        val out = ArrayList<Int>()
        for (bar in 0 until n) {
            val fp = fingerprint(map, bar)
            if (fp < 0) continue
            if (seen.containsKey(fp)) out.add(bar) else seen[fp] = bar
        }
        return out
    }

    /** Whether [bar] can be loop-extended (it has a clean repeat elsewhere). */
    fun isExtensible(map: SongMap, bar: Int): Boolean = repeatedBars(map, bar) > 0
}
