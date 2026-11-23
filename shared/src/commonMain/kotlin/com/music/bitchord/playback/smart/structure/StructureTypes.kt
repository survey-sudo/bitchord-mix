package com.music.bitchord.playback.smart.structure

import com.music.bitchord.playback.smart.SectionKind

/**
 * AUTOMIX-V2(D): one bar of beat-synchronous features.
 *
 * Levels are in dB on the extractor's own scale; [StructureV2] only ever
 * compares them inside one track (relative to the track's 90th percentile),
 * so the absolute offset does not matter.
 */
class V2Bar(
    /** 12 pitch classes, L2-normalised; all zero for a silent bar. */
    val chroma: FloatArray,
    /** MFCC-like timbre, coefficients 1..12 (c0 dropped, loudness lives in [rmsDb]). */
    val timbre: FloatArray,
    val rmsDb: Float,
    /** 30-150 Hz: kick + bass. */
    val lowDb: Float,
    /** 2.5-8 kHz (capped below Nyquist): hats, snares, risers. */
    val highDb: Float,
    /** Detected onsets per beat in this bar. */
    val onsetRate: Float,
    /** Chroma peak over chroma mean: high = tonal, low = drums/noise. */
    val clarity: Float,
)

/**
 * Per-bar stem levels in dB, [Float.NaN] where the stems were not separated
 * (Stream B separates head/tail regions only). See [StemBars.from].
 */
class StemBars(
    val vocalsDb: FloatArray,
    val drumsDb: FloatArray,
    val bassDb: FloatArray,
    val otherDb: FloatArray,
) {
    fun covered(bar: Int): Boolean =
        bar in vocalsDb.indices && !vocalsDb[bar].isNaN() && !drumsDb[bar].isNaN()
}

/** rekordbox-style track structure mode: verse/chorus pop, or intro/up/down/chorus dance form. */
enum class StructureMode { POP, DANCE }

/** rekordbox-style mood: density/tempo class, used only as a planner hint. */
enum class StructureMood { LOW, MID, HIGH }

/**
 * rekordbox Phrase taxonomy. HIGH-mood (dance) tracks use INTRO/UP/DOWN/CHORUS/OUTRO,
 * MID/LOW (pop) tracks use INTRO/VERSE/CHORUS/BRIDGE/OUTRO; UP = build/pre-chorus,
 * DOWN = breakdown.
 */
enum class PhraseTag { INTRO, VERSE, UP, DOWN, CHORUS, BRIDGE, OUTRO }

class V2Section(
    val startBar: Int,
    val endBar: Int,
    val kind: SectionKind,
    /** 0..1 label confidence. */
    val conf: Float,
    /** Repetition family (cluster) id; equal ids sound alike. */
    val family: Int,
    /** 0 for the first occurrence of [family], 1 for the second, ... */
    val repeatIndex: Int,
    val tag: PhraseTag,
    /** 0..1 confidence that a section really starts at [startBar]. */
    val startConfidence: Float,
)

class BarSpan(val startBar: Int, val endBar: Int, val confidence: Float)

class BarEvent(val bar: Int, val strength: Float)

/**
 * How DJ-friendly an intro (counted from bar 0) or outro (counted back from
 * the end) is. [vocalFreeBars] is -1 when no vocal evidence exists.
 */
class EdgeInfo(
    val bars: Int,
    val vocalFreeBars: Int,
    /** Bars with a steady beat (onsets and kick/bass present). */
    val beatBars: Int,
    /** Percussive and atonal (or drums stem dominant): safe to layer under anything. */
    val drumsOnly: Boolean,
    /** 0..1: how good this edge is to blend over for 8-16 bars. */
    val mixability: Float,
) {
    companion object {
        val NONE = EdgeInfo(0, -1, 0, false, 0f)
    }
}

/** Everything structure v2 found for one track. Bar indices are on [downbeats]. */
class StructureResult(
    val version: Int,
    val downbeats: DoubleArray,
    val barSec: Double,
    val sections: List<V2Section>,
    /** Section starts plus the end bar; includes weaker phrase boundaries inside merged sections. */
    val boundaries: IntArray,
    /** Same length as [boundaries]. */
    val boundaryConfidence: FloatArray,
    /** Per bar 0..n, 0..1 combined novelty (index b = change between bar b-1 and b). */
    val novelty: FloatArray,
    val drops: List<BarEvent>,
    val builds: List<BarSpan>,
    val breakdowns: List<BarSpan>,
    /** Start bars of each repetition family; [0] is the chorus (or drop) family when one exists. */
    val families: List<IntArray>,
    /** Off-4-bar-grid strong boundaries that restart the local phrase count. */
    val reanchors: IntArray,
    val phraseConfidence: Double,
    val labelConfidence: Double,
    val mode: StructureMode,
    val mood: StructureMood,
    val intro: EdgeInfo,
    val outro: EdgeInfo,
    val vocalMeasured: Boolean,
    val hasChorusFamily: Boolean,
    /** True when [com.music.bitchord.playback.smart.SongMap.sections] were built from [sections]. */
    val applied: Boolean = false,
) {
    val bars: Int get() = novelty.size - 1

    fun barTime(bar: Int): Double {
        if (bar <= 0) return downbeats.firstOrNull() ?: 0.0
        if (bar < downbeats.size) return downbeats[bar]
        val last = downbeats.lastOrNull() ?: 0.0
        return last + (bar - (downbeats.size - 1).coerceAtLeast(0)) * barSec
    }

    fun withApplied(value: Boolean): StructureResult = StructureResult(
        version, downbeats, barSec, sections, boundaries, boundaryConfidence, novelty, drops, builds,
        breakdowns, families, reanchors, phraseConfidence, labelConfidence, mode, mood, intro, outro,
        vocalMeasured, hasChorusFamily, value,
    )

    fun withEdges(intro: EdgeInfo, outro: EdgeInfo): StructureResult = StructureResult(
        version, downbeats, barSec, sections, boundaries, boundaryConfidence, novelty, drops, builds,
        breakdowns, families, reanchors, phraseConfidence, labelConfidence, mode, mood, intro, outro,
        vocalMeasured, hasChorusFamily, applied,
    )
}

/**
 * Model slot for a learned structure head (e.g. an All-In-One export fed with
 * Stream B's stems). Runs on the analysis worker only. Return null to fall
 * back to DSP.
 */
fun interface StructureModel {
    fun predict(bars: List<V2Bar>, stems: StemBars?, bpm: Double): StructureModelOutput?
}

/**
 * [boundary] has n + 1 entries (probability that a section starts at bar b);
 * [labels] holds per-bar probabilities (n entries) for any kinds the model knows.
 */
class StructureModelOutput(
    val boundary: FloatArray,
    val labels: Map<SectionKind, FloatArray>,
)
