package com.music.bitchord.playback.smart.structure

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * AUTOMIX-V2(D): per-bar stem levels from Stream B's separated regions.
 *
 * Takes plain mono arrays so this file does not depend on `Stems.kt`
 * landing first. With a `StemSet` `s` (stereo planar) call:
 * ```
 * StemBarsBuilder.add(acc, s.sampleRate, s.regionStartSec,
 *     s.left[StemKind.VOCALS], s.left[StemKind.DRUMS], s.left[StemKind.BASS], s.left[StemKind.OTHER],
 *     downbeats, barSec)
 * ```
 * (left channel is enough for levels). Bars the region does not cover at
 * least 75% stay [Float.NaN] (= "not measured").
 */
object StemBarsBuilder {
    fun empty(bars: Int): StemBars = StemBars(
        FloatArray(bars) { Float.NaN }, FloatArray(bars) { Float.NaN },
        FloatArray(bars) { Float.NaN }, FloatArray(bars) { Float.NaN },
    )

    fun add(
        into: StemBars,
        sampleRate: Int,
        regionStartSec: Double,
        vocals: FloatArray?,
        drums: FloatArray?,
        bass: FloatArray?,
        other: FloatArray?,
        downbeats: DoubleArray,
        barSec: Double,
    ): StemBars {
        if (sampleRate <= 0) return into
        val frames = listOfNotNull(vocals, drums, bass, other).minOfOrNull { it.size } ?: return into
        val regionEnd = regionStartSec + frames.toDouble() / sampleRate
        val n = min(downbeats.size, into.vocalsDb.size)
        for (b in 0 until n) {
            val t0 = downbeats[b]
            val t1 = if (b + 1 < downbeats.size) downbeats[b + 1] else t0 + barSec
            val c0 = max(t0, regionStartSec)
            val c1 = min(t1, regionEnd)
            if (t1 <= t0 || (c1 - c0) < 0.75 * (t1 - t0)) continue
            val a = ((c0 - regionStartSec) * sampleRate).toInt().coerceIn(0, frames)
            val z = ((c1 - regionStartSec) * sampleRate).toInt().coerceIn(a, frames)
            if (z <= a) continue
            into.vocalsDb[b] = levelDb(vocals, a, z)
            into.drumsDb[b] = levelDb(drums, a, z)
            into.bassDb[b] = levelDb(bass, a, z)
            into.otherDb[b] = levelDb(other, a, z)
        }
        return into
    }

    private fun levelDb(x: FloatArray?, a: Int, z: Int): Float {
        if (x == null) return Float.NaN
        var acc = 0.0
        for (i in a until z) acc += x[i].toDouble() * x[i]
        val p = acc / (z - a)
        return (10.0 * ln(p + 1e-12) / ln(10.0)).toFloat()
    }
}
