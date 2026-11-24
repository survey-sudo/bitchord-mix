package com.music.bitchord.playback.smart.choreo

/*
 * AUTOMIX-V2(E): stem-aware transition choreography.
 *
 * Everything in this package is pure Kotlin (commonMain): it reads the plan
 * geometry ([com.music.bitchord.playback.smart.LoopMix.Plan] + Clock) that the
 * vc80 planners already produce and writes per-deck envelope scripts on that
 * clock. It never changes the outgoing geometry (splice point, loop, release),
 * so the vc80 finite-loop / handover invariants are untouched. Every failure
 * returns null, and the caller then keeps the vc80 envelopes.
 */

/**
 * How much DJ the user wants. Plain enum so settings/UI can store it by name.
 *
 * SUBTLE is the default: S1..S5, max 1 FX, 3% stretch cap, restrained blends.
 * CLUB: C1..C7, 1-2 FX, 6% stretch cap, up to 32 s drop re-time.
 * FESTIVAL: F1..F8, 2-3 layered FX, 8% stretch cap, up to 64 s drop re-time.
 */
enum class DjLevel(
    /** Multiplier on wet/send/resonance/riser/gate depths. */
    val fxDepth: Float,
    /** Effects (echo, reverb, flanger, phaser, gate, riser, pump, brake) allowed in one transition. */
    val maxFx: Int,
    /** Longest stretch of the incoming intro the choreographer may skip to land on a drop (s). 0 = never re-time. */
    val maxEntrySkipSec: Double,
    /** Small score bias toward "showier" moves; negative at SUBTLE keeps it quiet. */
    val showBias: Double,
    /** Tempo-stretch cap for beat-matched recipes at this level (0.03 = 3%, 0.06 = 6%, 0.08 = 8%). */
    val maxStretch: Double,
) {
    SUBTLE(0.6f, 1, 0.0, -0.10, 0.03),
    CLUB(0.85f, 2, 32.0, 0.0, 0.06),
    FESTIVAL(1.0f, 3, 64.0, 0.10, 0.08),
    ;

    fun atLeast(other: DjLevel): Boolean = ordinal >= other.ordinal

    companion object {
        /** Settings round-trip: unknown names fall back to SUBTLE. */
        fun fromName(name: String?): DjLevel = values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SUBTLE
    }
}

/**
 * Feature flags for stream E. Defaults are chosen so a failure anywhere falls
 * back to the vc80 envelopes, never to silence.
 */
object AutomixV2Choreo {
    /** Master switch: off = LoopMixSession behaves exactly as vc80. */
    @Volatile var enabled: Boolean = true

    /** User-facing intensity (settings hook in work/E/NOTES.md). */
    @Volatile var level: DjLevel = DjLevel.SUBTLE

    /** Consume a [com.music.bitchord.playback.smart.StemProvider] when one is passed. */
    @Volatile var useStems: Boolean = true

    /** Move the incoming cue so its DROP lands on the swap downbeat (CLUB+ only, bounded by [DjLevel.maxEntrySkipSec]). */
    @Volatile var retimeEntry: Boolean = true

    /** Let the choreographer replace a compiled PhraseScript's lanes (false = only plans without a script). */
    @Volatile var overridePhraseScripts: Boolean = true

    /** Seed for the variety jitter; deterministic for a given seed + pair. */
    @Volatile var seed: Long = 0L

    /** A best candidate scoring below this is not used at SUBTLE (vc80 envelopes stay). */
    @Volatile var minScore: Double = 0.15

    /** Regions longer than this are not sent to the stem separator (worker-time budget). */
    const val MAX_STEM_REGION_SEC = 40.0

    /** Minimum separator confidence before a StemSet is trusted. */
    const val MIN_STEM_CONFIDENCE = 0.35f

    /** Recent moves and recipes, for the variety rule. Process-wide, survives across transitions. */
    val history: MoveHistory = MoveHistory()
}

/**
 * The last few chosen moves and recipes, one entry per pair (a rebuild of the
 * same pair replaces its entry instead of counting twice). Lock-free: the list
 * is immutable and swapped whole.
 */
class MoveHistory(private val capacity: Int = 8) {
    private data class Entry(val pairKey: String, val move: Move, val recipeId: RecipeId?)

    @Volatile private var entries: List<Entry> = emptyList()

    fun record(pairKey: String, move: Move, recipeId: RecipeId? = null) {
        val current = entries
        val entry = Entry(pairKey, move, recipeId)
        val next = if (current.isNotEmpty() && current.last().pairKey == pairKey) {
            current.dropLast(1) + entry
        } else {
            current + entry
        }
        entries = if (next.size > capacity) next.takeLast(capacity) else next
    }

    /** Most recent last, excluding an entry for [pairKey] itself (a rebuild must not penalise its own move). */
    fun recent(pairKey: String? = null): List<Move> {
        val list = entries
        val trimmed = if (pairKey != null && list.isNotEmpty() && list.last().pairKey == pairKey) list.dropLast(1) else list
        return trimmed.map { it.move }
    }

    /** Most recent recipes last, excluding an entry for [pairKey] itself. */
    fun recentRecipes(pairKey: String? = null): List<RecipeId> {
        val list = entries
        val trimmed = if (pairKey != null && list.isNotEmpty() && list.last().pairKey == pairKey) list.dropLast(1) else list
        return trimmed.mapNotNull { it.recipeId }
    }

    fun clear() {
        entries = emptyList()
    }
}
