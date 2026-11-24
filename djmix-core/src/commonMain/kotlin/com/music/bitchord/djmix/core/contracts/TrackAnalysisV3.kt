package com.music.bitchord.djmix.core.contracts

import kotlinx.serialization.Serializable

/** Musical role of a song structure section. */
@Serializable
enum class SectionType {
    INTRO,
    VERSE,
    PRE_CHORUS,
    CHORUS,
    POST_CHORUS,
    HOOK,
    BRIDGE,
    BREAKDOWN,
    BUILD,
    DROP,
    INSTRUMENTAL,
    OUTRO,
    FADE_OUT,
    SILENCE,
}

/** Classified musical section within [SongStructureV3]. */
@Serializable
data class StructureSectionV3(
    val startMs: Long,
    val endMs: Long,
    val startBar: Int = 0,
    val endBar: Int = 0,
    val type: SectionType,
    val confidence: Float = 0f,
    val familyId: Int = -1,
    val repeatIndex: Int = 0,
    val energy: Float = 0f,
    val vocal: Float = 0f,
    val bass: Float = 0f,
    val loopable: Boolean = false,
) {
    init {
        require(endMs >= startMs) { "Section endMs ($endMs) must be >= startMs ($startMs)" }
    }
}

/** Detected phrase boundary on the track timeline. */
@Serializable
data class PhraseBoundaryV3(
    val timeMs: Long,
    val barIndex: Int = 0,
    val confidence: Float = 1f,
    val isReanchor: Boolean = false,
)

/** Detected musical drop arrival downbeat. */
@Serializable
data class DropEventV3(
    val timeMs: Long,
    val barIndex: Int = 0,
    val strength: Float = 1f,
)

/** Repeated chorus or drop family as a sequence of occurrence start timestamps (ms). */
@Serializable
data class ChorusFamilyV3(
    val familyId: Int,
    val occurrenceStartMs: List<Long>,
)

/**
 * Persisted song structure, phrase grid, builds, drops, breakdowns, and chorus repetition
 * families for a track. Folds the ephemeral `PhraseMaps` and `SongMaps` caches directly
 * into [TrackAnalysisV3] per ADR 0009.
 */
@Serializable
data class SongStructureV3(
    val sections: List<StructureSectionV3> = emptyList(),
    val phrases: List<PhraseBoundaryV3> = emptyList(),
    val builds: List<TimeRangeMs> = emptyList(),
    val drops: List<DropEventV3> = emptyList(),
    val breakdowns: List<TimeRangeMs> = emptyList(),
    val chorusFamilies: List<ChorusFamilyV3> = emptyList(),
    val phraseLenBars: Int = 8,
    val phraseAnchorBar: Int = 0,
    val phraseConfidence: Float = 0f,
    val labelConfidence: Float = 0f,
    val mode: String = "",
    val mood: String = "",
)

/**
 * Versioned track analysis contract (`schema = 3`) stored in Room (`djmix.db`) and consumed
 * by the `:djmix-core` planner and Mix Inspector.
 */
@Serializable
data class TrackAnalysisV3(
    val schema: Int = 3,
    val trackId: String,
    val contentHash: String = "",
    val durationMs: Long,
    val analysisLevel: AnalysisLevel = AnalysisLevel.NONE,
    val bpm: Double = 0.0,
    val gridConfidence: Float = 0f,
    val beatsMs: List<Long> = emptyList(),
    val downbeatsMs: List<Long> = emptyList(),
    val keyCamelot: String? = null,
    val keyConfidence: Float = 0f,
    val integratedLufs: Float = -14f,
    val shortTermLufsCurve: List<Float> = emptyList(),
    val energyCurve: List<Float> = emptyList(),
    val vocalPresence50fps: List<Float> = emptyList(),
    val structure: SongStructureV3 = SongStructureV3(),
    val silentIntroEndMs: Long = 0L,
    val audibleEndMs: Long = durationMs,
    val modelVersions: Map<String, String> = emptyMap(),
) {
    init {
        require(schema == 3) { "TrackAnalysisV3 requires schema == 3, got $schema" }
        require(durationMs >= 0L) { "durationMs must be non-negative, got $durationMs" }
        require(bpm >= 0.0) { "bpm must be non-negative, got $bpm" }
    }

    /** True when analysis produced a usable tempo grid. */
    val isUsable: Boolean
        get() = analysisLevel != AnalysisLevel.NONE && bpm > 0.0 && beatsMs.isNotEmpty()
}
