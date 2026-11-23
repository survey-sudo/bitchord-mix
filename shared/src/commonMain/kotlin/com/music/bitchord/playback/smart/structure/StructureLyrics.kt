package com.music.bitchord.playback.smart.structure

import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.normalizeLyric

/**
 * AUTOMIX-V2(D): per-bar lyric repetition prior for [StructureV2.analyze] /
 * `StructureAnalyzer.fromPcm(lyricPrior = ...)`.
 *
 * A bar scores 1 when a synced line that repeats somewhere in the song (same
 * normalised text, at least twice) is sung in it, 0 otherwise. Repetition of
 * lines is chorus evidence, not proof; v2 weighs it at 15% of the chorus score.
 */
object StructureLyrics {
    fun prior(lyrics: List<LyricCue>, downbeats: DoubleArray, barSec: Double): FloatArray? {
        if (lyrics.isEmpty() || downbeats.isEmpty()) return null
        val texts = lyrics.map { normalizeLyric(it.text) }
        val counts = HashMap<String, Int>()
        for (t in texts) if (t.isNotEmpty()) counts[t] = (counts[t] ?: 0) + 1
        val out = FloatArray(downbeats.size)
        var any = false
        for ((i, cue) in lyrics.withIndex()) {
            val t = texts[i]
            if (t.isEmpty() || (counts[t] ?: 0) < 2) continue
            for (b in downbeats.indices) {
                val b0 = downbeats[b]
                val b1 = if (b + 1 < downbeats.size) downbeats[b + 1] else b0 + barSec
                if (cue.startSec < b1 && cue.endSec > b0) {
                    out[b] = 1f
                    any = true
                }
            }
        }
        return if (any) out else null
    }
}
