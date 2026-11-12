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
) {
    val barSec: Double get() = if (bpm > 1.0) beatsPerBar * 60.0 / bpm else 0.0

    fun barTime(bar: Int): Double {
        if (bar < 0) return 0.0
        if (bar < downbeats.size) return downbeats[bar]
        val step = barSec
        val origin = downbeats.lastOrNull() ?: 0.0
        return origin + (bar - (downbeats.size - 1).coerceAtLeast(0)) * step
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
            endingKind == other.endingKind
    }

    override fun hashCode(): Int = trackKey.hashCode() * 31 + sections.hashCode()
}
