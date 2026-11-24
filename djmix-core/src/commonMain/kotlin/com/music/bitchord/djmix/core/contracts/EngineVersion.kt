package com.music.bitchord.djmix.core.contracts

import kotlinx.serialization.Serializable

/** Semantic version of the BitChord Automix Engine 3 contracts and state machine. */
const val ENGINE_VERSION: String = "3.0.0"

/** Major version incremented on contract schema breaks or state-machine changes. */
const val ENGINE_MAJOR: Int = 3

/** Minor version incremented when adding recipes, effects, or models. */
const val ENGINE_MINOR: Int = 0

/** Patch version incremented for tuning and bug fixes. */
const val ENGINE_PATCH: Int = 0

/** User-facing automix intensity level controlling FX budget, stretch cap, and entry re-timing. */
@Serializable
enum class DjLevel(
    /** Multiplier on wet, send, resonance, riser, and gate depths. */
    val fxDepth: Float,
    /** Maximum simultaneous audible effects allowed in one transition. */
    val maxFx: Int,
    /** Longest incoming intro skip (seconds) allowed to land on a drop; 0 disables re-timing. */
    val maxEntrySkipSec: Double,
    /** Small score bias toward showier recipes; negative at SUBTLE keeps transitions restrained. */
    val showBias: Double,
    /** Maximum tempo-stretch ratio allowed for beat-matched recipes (e.g. 0.08 = 8%). */
    val maxStretch: Double,
) {
    SUBTLE(0.6f, 1, 0.0, -0.10, 0.03),
    CLUB(0.85f, 2, 32.0, 0.0, 0.06),
    FESTIVAL(1.0f, 3, 64.0, 0.10, 0.08),
    ;

    /** True when this level is at least as permissive as [other]. */
    fun atLeast(other: DjLevel): Boolean = ordinal >= other.ordinal

    companion object {
        /** Parses a persisted level name, falling back to [SUBTLE] when blank or unknown. */
        fun fromName(name: String?): DjLevel =
            if (name.isNullOrBlank()) SUBTLE
            else entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SUBTLE
    }
}

/** Identifies one of the two transition decks in the dual-deck renderer. */
@Serializable
enum class DeckTarget {
    /** Outgoing (currently playing) deck. */
    OUT,

    /** Incoming (next queued) deck. */
    IN,
}

/** Separated audio stem kind matching native stem storage slot order for VOCALS..BASS. */
@Serializable
enum class StemKind(
    /** Native slot index passed to `Renderer::setDeckStem`; OTHER is derived as dry - (V + D + B). */
    val nativeSlot: Int,
) {
    VOCALS(0),
    DRUMS(1),
    BASS(2),
    OTHER(3),
}

/** Identifies a specific stem required on a specific deck. */
@Serializable
data class DeckStemRequirement(
    val deck: DeckTarget,
    val stem: StemKind,
)

/** Transition geometric arrangement selected by the planner. */
@Serializable
enum class TransitionGeometry {
    /** Two-sided or outgoing loop-locked tempo ramp transition. */
    LOOP,

    /** Beat-aligned or phrase-aligned bridge across both decks without full crown looping. */
    BRIDGE,

    /** Straight playback overlap without looping. */
    STRAIGHT,
}

/** High-level stylistic family for a transition recipe. */
@Serializable
enum class RecipeFamily {
    BLEND,
    SWAP,
    CUT,
    FX,
    DROP,
}

/** How sudden the transition's perceptual swap is; gates abrupt moves to tempo/genre jumps. */
@Serializable
enum class AbruptnessClass {
    SMOOTH,
    PUNCHY,
    ABRUPT,
}

/** Completeness level of a track's stored analysis. */
@Serializable
enum class AnalysisLevel {
    NONE,
    HEAD_TAIL,
    FULL,
}

/** Device thermal severity reported by the platform thermal service. */
@Serializable
enum class ThermalLevel {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    ;

    /** True when heavy background prep/stemming must degrade to no-stem variants or pause. */
    val requiresStemDegradation: Boolean
        get() = ordinal >= SEVERE.ordinal
}

/** Execution path taken by a transition, reported in telemetry and Mix Inspector. */
@Serializable
enum class ExecutionPath {
    PREPARER_FROZEN,
    PREPARER_NO_STEM_FALLBACK,
    LAST_RESORT_MUSICAL_EXIT,
    CLASSIC_RENDERER_UNAVAILABLE,
}

/** Inclusive/half-open time span in milliseconds on a track or mix timeline. */
@Serializable
data class TimeRangeMs(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(endMs >= startMs) { "TimeRangeMs endMs ($endMs) must be >= startMs ($startMs)" }
    }

    /** Span length in milliseconds. */
    val durationMs: Long get() = endMs - startMs
}
