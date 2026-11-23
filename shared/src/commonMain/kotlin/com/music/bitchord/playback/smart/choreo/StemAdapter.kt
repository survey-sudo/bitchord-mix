package com.music.bitchord.playback.smart.choreo

import com.music.bitchord.playback.smart.StemKind
import com.music.bitchord.playback.smart.StemSet
import kotlin.math.sqrt

/*
 * AUTOMIX-V2(E) <- B adapter. The ONLY file in the choreographer that reads
 * stream B's StemSet / StemKind directly: a late rename in Stems.kt is a fix
 * here and nowhere else.
 */

/** What the choreographer needs from one deck's stems, independent of B's types. */
class StemView(
    /** 2 = vocals + instrumental (OTHER), 4 = vocals/drums/bass/other. */
    val stemCount: Int,
    val confidence: Float,
    private val regionStartSec: Double,
    private val sampleRate: Int,
    private val frames: Int,
    private val rms: (kind: StemPart, fromFrame: Int, toFrame: Int) -> Double,
) {
    val full: Boolean get() = stemCount >= 4
    val vocalSplit: Boolean get() = stemCount >= 2

    /** RMS of [part] over track seconds [fromSec]..[toSec]; NaN outside the region. */
    fun rmsOver(part: StemPart, fromSec: Double, toSec: Double): Double {
        if (sampleRate <= 0 || frames <= 0 || toSec <= fromSec) return Double.NaN
        val a = ((fromSec - regionStartSec) * sampleRate).toInt().coerceIn(0, frames)
        val b = ((toSec - regionStartSec) * sampleRate).toInt().coerceIn(0, frames)
        if (b - a < 16) return Double.NaN
        return rms(part, a, b)
    }

    /**
     * Share of the deck's energy that is vocal over [fromSec]..[toSec], 0..1,
     * or null outside the region. This is the stems-based vocal activity the
     * vocal-clash term prefers over the analysis mask.
     */
    fun vocalShare(fromSec: Double, toSec: Double): Double? {
        val v = rmsOver(StemPart.VOCALS, fromSec, toSec)
        val all = rmsOver(StemPart.ALL, fromSec, toSec)
        if (v.isNaN() || all.isNaN()) return null
        if (all < 1e-4) return 0.0
        return ((v * v) / (all * all)).coerceIn(0.0, 1.0)
    }

    /** Share of the deck's energy in the bass stem (4-stem only), or null. */
    fun bassShare(fromSec: Double, toSec: Double): Double? {
        if (!full) return null
        val b = rmsOver(StemPart.BASS, fromSec, toSec)
        val all = rmsOver(StemPart.ALL, fromSec, toSec)
        if (b.isNaN() || all.isNaN()) return null
        if (all < 1e-4) return 0.0
        return ((b * b) / (all * all)).coerceIn(0.0, 1.0)
    }
}

/** Stem parts, B-independent. ALL is the mixture (sum of stems). */
enum class StemPart { VOCALS, DRUMS, BASS, OTHER, ALL }

object StemAdapter {
    /** Null when [stems] is unusable (wrong shape, too low confidence, missing channels). */
    fun view(stems: StemSet?): StemView? {
        if (stems == null) return null
        if (stems.frames <= 0 || stems.sampleRate <= 0) return null
        if (stems.confidence < AutomixV2Choreo.MIN_STEM_CONFIDENCE) return null
        val count = stems.stemCount
        if (count < 2) return null
        val needed = if (count >= 4) listOf(StemKind.VOCALS, StemKind.DRUMS, StemKind.BASS, StemKind.OTHER)
        else listOf(StemKind.VOCALS, StemKind.OTHER)
        for (k in needed) {
            val l = stems.left[k] ?: return null
            val r = stems.right[k] ?: return null
            if (l.size < stems.frames || r.size < stems.frames) return null
        }
        val kinds: Map<StemPart, List<StemKind>> = mapOf(
            StemPart.VOCALS to listOf(StemKind.VOCALS),
            StemPart.DRUMS to if (count >= 4) listOf(StemKind.DRUMS) else emptyList(),
            StemPart.BASS to if (count >= 4) listOf(StemKind.BASS) else emptyList(),
            StemPart.OTHER to listOf(StemKind.OTHER),
            StemPart.ALL to needed,
        )
        return StemView(
            stemCount = if (count >= 4) 4 else 2,
            confidence = stems.confidence,
            regionStartSec = stems.regionStartSec,
            sampleRate = stems.sampleRate,
            frames = stems.frames,
        ) { part, from, to ->
            val list = kinds[part].orEmpty()
            if (list.isEmpty()) {
                Double.NaN
            } else {
                // Decimate: 1 in 8 frames is plenty for a level and keeps this cheap.
                var sum = 0.0
                var n = 0
                var i = from
                while (i < to) {
                    var l = 0f
                    var r = 0f
                    for (k in list) {
                        l += stems.left[k]!![i]
                        r += stems.right[k]!![i]
                    }
                    sum += (l * l + r * r) * 0.5
                    n++
                    i += 8
                }
                if (n == 0) Double.NaN else sqrt(sum / n)
            }
        }
    }

    /** True when [stems] has frames identical to a region of [frames] samples (contract: same frames). */
    fun matchesRegion(stems: StemSet?, frames: Int, sampleRate: Int): Boolean =
        stems != null && stems.frames == frames && stems.sampleRate == sampleRate
}
