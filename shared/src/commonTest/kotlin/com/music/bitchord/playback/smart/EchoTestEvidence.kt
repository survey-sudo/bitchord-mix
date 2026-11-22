package com.music.bitchord.playback.smart

import kotlin.math.ceil

/** Synthetic beat-model output for fixtures explicitly testing admitted Echo.
 * Missing-evidence fixtures deliberately do not call this test-only helper.
 */
internal fun TrackAnalysis.withMeasuredEchoEvidence(map: SongMap? = null): TrackAnalysis {
    val bars = map?.downbeats?.toList()
    val origin = downbeats.firstOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val pulse = if (bars != null) {
        bars.zipWithNext().flatMap { (a, b) -> List(4) { a + it * (b - a) / 4.0 } } + bars.last()
    } else {
        List(ceil((duration - origin).coerceAtLeast(0.0) * bpm / 60.0).toInt() + 1) { origin + it * 60.0 / bpm }
    }
    val anchors = bars ?: downbeats
    val side = BeatSide(pulse, anchors, bpm, .9)
    return copy(headBeats = side, tailBeats = side,
        phraseBoundaries = if (map != null) anchors else phraseBoundaries)
}
