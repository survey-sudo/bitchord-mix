package com.music.bitchord.playback.smart

/**
 * One track's form. Cues and the planner read only these scalars. Nothing
 * here is a frame sequence that gets compared with another track.
 */
enum class SectionFn {
    INTRO, VERSE, PRE_CHORUS, CHORUS, POST_CHORUS, BRIDGE,
    BREAK, BUILD, DROP, INSTRUMENTAL, OUTRO, FADE_OUT, SILENCE,
}

/** What the section is doing, coarser than [SectionFn]. Continuity is judged on this. */
enum class SectionRole { AMBIENT, GROOVE, NARRATIVE, TENSION, RELEASE, NONE }

enum class StartKind {
    INTRO_INSTRUMENTAL, INTRO_BUILD, VOCAL_PICKUP, COLD_VOCAL,
    CHORUS_FIRST, AMBIENT, ACAPELLA, SPOKEN, UNKNOWN,
}

enum class EndingKind {
    OUTRO_INSTRUMENTAL, OUTRO_VOCAL, LOOPABLE_OUTRO, FADE_OUT,
    HARD_END, SILENCE_TAIL, UNKNOWN,
}

data class PhraseSection(
    val startBar: Int,
    val endBar: Int,
    val fn: SectionFn,
    val role: SectionRole,
    val conf: Float,
    val group: Int,
    val repeatIndex: Int,
    /** 0..1, cross-track comparable. */
    val energy: Float,
    /** 1..10, the integer level the vetoes use. */
    val energyLevel: Int,
    val energyRel: Float,
    val energySlope: Float,
    val vocal: Float,
    val bass: Float,
    val loopable: Boolean,
    val lufs: Float = -14f,
    val onsetRate: Float = 0f,
)

data class SongMap(
    val schema: Int = PhraseConfig.SONGMAP_SCHEMA,
    val trackKey: String,
    val durationSec: Double,
    val bpm: Double,
    val beatsPerBar: Int,
    val downbeats: DoubleArray,
    val gridConfidence: Float,
    val tempoStable: Boolean,
    val phraseAnchor: Int,
    val phraseLen: Int,
    val anchors: IntArray = IntArray(0),
    val sections: List<PhraseSection>,
    val sungBars: BooleanArray,
    /**
     * Per-bar vocal energy, 0..1: how much vocal a listener actually hears in
     * that bar, not whether the mask crossed a line.
     *
     * [sungBars] answers "is anyone singing here", which is the right question
     * for finding a gap to cut in and the wrong one for deciding whether an
     * overlap will collide. A bar mean of 0.30 and a bar mean of 0.90 both read
     * `sungBars[b] = true`; weighted by bar energy, the same pair of tracks
     * scores 0.090 of clash against 0.810 — a factor of nine that the boolean
     * throws away.
     *
     * Empty means "not measured", and [vocalEnergyAt] then falls back to the
     * boolean, so a map built by hand behaves exactly as it did before.
     */
    val vocalEnergy: FloatArray = FloatArray(0),
    val firstVocalBar: Int?,
    val lastVocalBar: Int?,
    val pickupBeats: Float,
    val startKind: StartKind,
    val endingKind: EndingKind,
    val contentEndSec: Double,
    val labelConfidence: Float,
    val structMs: Int,
    val structWallMs: Int = structMs,
    val partial: Boolean = false,
    val coarse: Boolean = false,
    val activation: Float = 1f,
    val speechLike: Boolean = false,
    val chorusCount: Int = sections.count { it.fn == SectionFn.CHORUS },
    /** Empty retains legacy hand-built maps; producers explicitly mark unknown bars false. */
    val vocalCoverage: BooleanArray = BooleanArray(0),
    val gridMeasured: Boolean = false,
    val provenance: String = "",
    /** True only for observed downbeats, never interpolated/extrapolated ones. */
    val gridCoverage: BooleanArray = BooleanArray(0),
) {
    val barSec: Double get() = if (bpm > 1.0) beatsPerBar.coerceAtLeast(1) * 60.0 / bpm else 0.0

    fun isVocalMeasured(bar: Int): Boolean = if (vocalCoverage.isEmpty()) {
        bar in sungBars.indices && (vocalEnergy.isEmpty() || bar < vocalEnergy.size)
    } else vocalCoverage.getOrElse(bar) { false }

    fun isGridMeasured(bar: Int): Boolean = gridMeasured &&
        if (gridCoverage.isEmpty()) bar in downbeats.indices else gridCoverage.getOrElse(bar) { false }

    fun barTime(bar: Int): Double {
        if (bar < 0) return 0.0
        if (bar < downbeats.size) return downbeats[bar]
        val step = if (downbeats.size >= 2) (downbeats.last() - downbeats[downbeats.lastIndex - 1])
            .takeIf { it.isFinite() && it > 0.0 } ?: barSec else barSec
        val origin = downbeats.lastOrNull() ?: 0.0
        return origin + (bar - (downbeats.size - 1).coerceAtLeast(0)) * step
    }

    /**
     * Vocal energy heard in [bar], 0..1.
     *
     * Falls back to the boolean when the map was built without a measurement,
     * so a hand-constructed map is unchanged. An out-of-range bar is silence.
     */
    fun vocalEnergyAt(bar: Int): Double {
        if (bar < 0) return 0.0
        if (vocalEnergy.isNotEmpty()) {
            return if (bar < vocalEnergy.size) vocalEnergy[bar].toDouble().coerceIn(0.0, 1.0) else 0.0
        }
        return if (sungBars.getOrElse(bar) { false }) 1.0 else 0.0
    }

    /** Mean vocal energy across [from] until [until], 0..1. Empty range is 0. */
    fun vocalEnergyMean(from: Int, until: Int): Double {
        val start = from.coerceAtLeast(0)
        val stop = minOf(until, sungBars.size, if (vocalEnergy.isNotEmpty()) vocalEnergy.size else sungBars.size)
        if (stop <= start) return 0.0
        var sum = 0.0
        for (b in start until stop) sum += vocalEnergyAt(b)
        return sum / (stop - start)
    }

    fun sectionAt(bar: Int): PhraseSection? = sections.firstOrNull { bar >= it.startBar && bar < it.endBar }

    fun isPhraseStart(bar: Int): Boolean {
        val local = anchors.lastOrNull { it <= bar } ?: phraseAnchor
        val len = phraseLen.coerceAtLeast(1)
        return bar >= local && (bar - local) % len == 0
    }

    fun isPhraseDownbeat(bar: Int, prefer8: Boolean = false): Boolean {
        val local = anchors.lastOrNull { it <= bar } ?: phraseAnchor
        if (bar < local) return false
        val delta = bar - local
        if (prefer8 && phraseLen >= 8 && delta % 8 == 0) return true
        return delta % 4 == 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SongMap) return false
        return trackKey == other.trackKey && schema == other.schema &&
            durationSec == other.durationSec && partial == other.partial &&
            sections == other.sections && startKind == other.startKind &&
            endingKind == other.endingKind &&
            sungBars.contentEquals(other.sungBars) && vocalEnergy.contentEquals(other.vocalEnergy)
    }

    override fun hashCode(): Int =
        ((trackKey.hashCode() * 31 + sections.hashCode()) * 31 + sungBars.contentHashCode()) * 31 +
            vocalEnergy.contentHashCode()
}
