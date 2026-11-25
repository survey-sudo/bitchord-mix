package com.music.bitchord.playback.loopmix

/** A stretch of a song decoded for a deck: planar, at the renderer's rate, from track second [start]. */
class LoopRegion(val left: FloatArray, val right: FloatArray, val start: Double)

/** Bounded attribution; holds/tail/settle retain their existing clock geometry. */
enum class GridSource(val telemetryValue: String) {
    UNKNOWN("unknown"), TRACKED("tracked"), CLOCK("clock"), NATIVE_CLOCK("native-clock"), RATE_ONE("rate-one"),
}

class DeckKnots(
    val outFrames: DoubleArray,
    val outPositionsSec: DoubleArray,
    val inFrames: DoubleArray,
    val inPositionsSec: DoubleArray,
    val outGridSource: GridSource = GridSource.UNKNOWN,
    val inGridSource: GridSource = GridSource.UNKNOWN,
    val sampleRate: Int = 0,
)

data class CrownKnotQualification(
    val accepted: Boolean,
    val refusal: String? = null,
    val outMinRate: Double? = null,
    val outMaxRate: Double? = null,
    val inMinRate: Double? = null,
    val inMaxRate: Double? = null,
)

/** Incoming deck coordinate contract computed before JNI/session preparation. */
data class IncomingGeometry(
    val loops: Boolean,
    val releaseTrack: Double,
    val handoverTrack: Double,
    val requiredEnd: Double,
)
