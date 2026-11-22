package com.music.bitchord.playback

import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlin.math.ceil

/** Synthetic model output only for tests explicitly exercising an admitted Echo. */
internal fun TrackAnalysis.withMeasuredEchoTestEvidence(map: SongMap? = null): TrackAnalysis {
    val bars = map?.downbeats?.toList()
    val origin = downbeats.firstOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val pulse = if (bars != null) {
        bars.zipWithNext().flatMap { (a, b) -> List(4) { a + it * (b - a) / 4.0 } } + bars.last()
    } else List(ceil((duration - origin).coerceAtLeast(0.0) * bpm / 60.0).toInt() + 1) {
        origin + it * 60.0 / bpm
    }
    val anchors = bars ?: downbeats
    val side = BeatSide(pulse, anchors, bpm, .9)
    return copy(headBeats = side, tailBeats = side,
        phraseBoundaries = if (map != null) anchors else phraseBoundaries)
}
