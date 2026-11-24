package com.music.bitchord.djmix.core.learning

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.round

/**
 * Implicit/explicit listening signals that feed the taste model (Part B8.1).
 *
 * Each signal maps to a fixed bounded delta via [TasteModel.deltaFor]:
 * - [ExplicitThumbsUp] / [ExplicitThumbsDown]: explicit Inspector rating.
 * - [EarlySkipWithin15s]: user skipped within 15 s after the mix started (one negative signal).
 * - [SeekBackDuringMix]: user sought backwards into the outgoing deck during the mix (one negative signal).
 * - [ThreeConsecutivePlaythroughs]: three consecutive full playthroughs completed (+0.20,
 *   emitted once per completed 3-playthrough streak — see [TasteModel.noteFullPlaythrough]).
 */
@Serializable
enum class TasteSignal {
    ExplicitThumbsUp,
    ExplicitThumbsDown,
    EarlySkipWithin15s,
    SeekBackDuringMix,
    ThreeConsecutivePlaythroughs,
}

/** Serializable snapshot of [TasteModel] state for inspection and persistence. */
@Serializable
data class TasteSnapshot(
    val recipeWeights: Map<String, Double> = emptyMap(),
    val genreWeights: Map<String, Double> = emptyMap(),
    val playthroughStreaks: Map<String, Int> = emptyMap(),
    val learningEnabled: Boolean = true,
)

/**
 * Bounded per-recipe and per-(recipe, genre) taste weights (Part B8.2, pure Kotlin).
 *
 * Model:
 * - Every weight lives in [[MIN_WEIGHT], [MAX_WEIGHT]] (`[-0.3, +0.3]`).
 * - A signal updates the global per-recipe weight by its delta and, when a
 *   [genreClass] is supplied, additionally updates the per-`(recipeId, genreClass)`
 *   weight by the same delta. Each weight is clamped independently, so no
 *   combination can exceed the cap once the final sum is re-clamped.
 * - [adjustmentFor] returns `clamp(recipeWeight + genreWeight)` (missing keys read
 *   as `0.0`), so genre-specific evidence refines the global preference while
 *   global evidence still generalises across genres.
 * - [learningEnabled] = false or [reset] yields an exact `0.0` adjustment for every
 *   query, so `PairPlanner` picks are deterministic (Part B8.4). Signals received
 *   while disabled are ignored (weights stay frozen) — re-enabling resumes from
 *   the stored weights rather than from evidence gathered in the dark.
 *
 * Persistence wire-up (Android side, M7/M8 glue — not this file):
 * - Write deltas via `DjmixAnalysisStore.updatePreferenceWeight(key, delta)` using
 *   [recipeKey] / [genreKey] as `pref_key`s.
 * - Hydrate via [restoreFromPreferenceMap] from `DjmixAnalysisStore.getPreferenceWeights()`
 *   (keys starting with `"__"` such as the legacy migration marker are ignored).
 * - Reset via `DjmixAnalysisStore.resetPreferences()` plus [reset].
 * - Feed `PairPlanner` via [plannerWeights] (keys are plain recipe ids, matching
 *   `PlannerRequest.tasteWeights[recipe.id]`).
 *
 * No Android, I/O, or clock types are used here: all timestamps and storage live
 * in the Android glue.
 */
class TasteModel(var learningEnabled: Boolean = true) {

    private val recipeWeights = mutableMapOf<String, Double>()
    private val genreWeights = mutableMapOf<String, Double>()
    private val playthroughStreaks = mutableMapOf<String, Int>()

    /**
     * Applies [signal] for [recipeId], optionally scoped to [genreClass].
     * No-op when [learningEnabled] is false or [recipeId] is blank. Negative signals
     * reset that recipe's consecutive-playthrough streak; positive signals preserve it.
     */
    fun applySignal(signal: TasteSignal, recipeId: String, genreClass: String? = null) {
        if (!learningEnabled) return
        val id = recipeId.trim()
        if (id.isEmpty()) return
        val delta = deltaFor(signal)
        recipeWeights[id] = clampWeight((recipeWeights[id] ?: 0.0) + delta)
        val genre = normalizeGenreClass(genreClass)
        if (genre != null) {
            val key = genreKey(id, genre)
            genreWeights[key] = clampWeight((genreWeights[key] ?: 0.0) + delta)
        }
        if (delta < 0.0) {
            playthroughStreaks.remove(id)
        }
    }

    /**
     * Records one full playthrough of [recipeId] and returns true when a
     * [TasteSignal.ThreeConsecutivePlaythroughs] bonus (+0.20) was applied
     * (every 3rd consecutive playthrough; the streak then restarts).
     * Returns false while the streak is incomplete, and always false while
     * [learningEnabled] is false.
     */
    fun noteFullPlaythrough(recipeId: String, genreClass: String? = null): Boolean {
        if (!learningEnabled) return false
        val id = recipeId.trim()
        if (id.isEmpty()) return false
        val next = (playthroughStreaks[id] ?: 0) + 1
        if (next % PLAYTHROUGHS_PER_STREAK_BONUS == 0) {
            playthroughStreaks.remove(id)
            applySignal(TasteSignal.ThreeConsecutivePlaythroughs, id, genreClass)
            return true
        }
        playthroughStreaks[id] = next
        return false
    }

    /** Current consecutive full-playthrough count for [recipeId] (0 when none). */
    fun consecutivePlaythroughs(recipeId: String): Int =
        playthroughStreaks[recipeId.trim()] ?: 0

    /** Clears every learned weight and streak; afterwards every adjustment is exactly `0.0`. */
    fun reset() {
        recipeWeights.clear()
        genreWeights.clear()
        playthroughStreaks.clear()
    }

    /**
     * Adjustment for ([recipeId], [genreClass]) in `[-0.3, +0.3]`, or exact `0.0`
     * when [learningEnabled] is false or the recipe is unknown.
     */
    fun adjustmentFor(recipeId: String, genreClass: String? = null): Double {
        if (!learningEnabled) return 0.0
        val id = recipeId.trim()
        if (id.isEmpty()) return 0.0
        val recipe = recipeWeights[id] ?: 0.0
        val genre = normalizeGenreClass(genreClass)?.let { genreWeights[genreKey(id, it)] } ?: 0.0
        return clampWeight(recipe + genre)
    }

    /**
     * `PlannerRequest.tasteWeights`-compatible view: recipe id -> adjustment for
     * [genreClass], for every recipe with learned evidence. Empty when disabled or
     * after [reset] (planner then sees `0.0` for every recipe — deterministic).
     */
    fun plannerWeights(genreClass: String? = null): Map<String, Double> {
        if (!learningEnabled) return emptyMap()
        val ids = sortedSetOf<String>()
        ids += recipeWeights.keys
        for (key in genreWeights.keys) {
            val id = key.substringBefore(':')
            if (id.isNotEmpty()) ids += id
        }
        val out = LinkedHashMap<String, Double>(ids.size)
        for (id in ids) {
            out[id] = adjustmentFor(id, genreClass)
        }
        return out
    }

    /** Raw per-recipe weights (defensive copy). */
    fun recipeWeights(): Map<String, Double> = recipeWeights.toMap()

    /** Raw per-(recipe, genre) weights keyed by [genreKey] (defensive copy). */
    fun genreWeights(): Map<String, Double> = genreWeights.toMap()

    /**
     * All weights as a `pref_key -> weight` map for `DjmixAnalysisStore` persistence
     * (recipe keys via [recipeKey], genre keys via [genreKey]), sorted by key.
     */
    fun toPreferenceMap(): Map<String, Double> {
        val merged = LinkedHashMap<String, Double>(recipeWeights.size + genreWeights.size)
        merged.putAll(recipeWeights)
        merged.putAll(genreWeights)
        return merged.toSortedMap()
    }

    /**
     * Restores weights from a `pref_key -> weight` map (for example
     * `DjmixAnalysisStore.getPreferenceWeights()`). Keys containing `':'` restore
     * genre weights; keys starting with `"__"` (database markers) are skipped;
     * non-finite values are treated as `0.0`; everything else is clamped.
     */
    fun restoreFromPreferenceMap(weights: Map<String, Double>) {
        reset()
        for ((key, value) in weights) {
            if (key.startsWith("__")) continue
            val weight = clampWeight(value)
            if (':' in key) {
                genreWeights[key] = weight
            } else if (key.isNotBlank()) {
                recipeWeights[key] = weight
            }
        }
    }

    /** Serializable snapshot of the full model state. */
    fun snapshot(): TasteSnapshot = TasteSnapshot(
        recipeWeights = recipeWeights.toMap(),
        genreWeights = genreWeights.toMap(),
        playthroughStreaks = playthroughStreaks.toMap(),
        learningEnabled = learningEnabled,
    )

    /** Replaces all model state from [snapshot] (weights re-clamped defensively). */
    fun restore(snapshot: TasteSnapshot) {
        reset()
        for ((key, value) in snapshot.recipeWeights) {
            if (key.isNotBlank()) recipeWeights[key] = clampWeight(value)
        }
        for ((key, value) in snapshot.genreWeights) {
            if (key.isNotBlank()) genreWeights[key] = clampWeight(value)
        }
        for ((key, count) in snapshot.playthroughStreaks) {
            if (key.isNotBlank() && count > 0) playthroughStreaks[key] = count
        }
        learningEnabled = snapshot.learningEnabled
    }

    companion object {
        /** Minimum learned weight (never below). */
        const val MIN_WEIGHT: Double = -0.3

        /** Maximum learned weight (never above). */
        const val MAX_WEIGHT: Double = 0.3

        /** Delta for an explicit thumbs-up. */
        const val THUMBS_UP_DELTA: Double = 0.05

        /** Delta for an explicit thumbs-down. */
        const val THUMBS_DOWN_DELTA: Double = -0.05

        /** Delta for an early skip (one negative signal). */
        const val EARLY_SKIP_DELTA: Double = -0.05

        /** Delta for a seek-back during the mix (one negative signal). */
        const val SEEK_BACK_DELTA: Double = -0.05

        /** Bonus applied once per completed 3-consecutive-playthrough streak. */
        const val PLAYTHROUGH_STREAK_BONUS: Double = 0.20

        /** Consecutive full playthroughs required for [PLAYTHROUGH_STREAK_BONUS]. */
        const val PLAYTHROUGHS_PER_STREAK_BONUS: Int = 3

        /** |adjustment| below this never shows a taste badge. */
        const val BADGE_THRESHOLD: Double = 0.01

        /** Fixed delta applied by [signal]; pure mapping shared with Android glue persistence. */
        fun deltaFor(signal: TasteSignal): Double = when (signal) {
            TasteSignal.ExplicitThumbsUp -> THUMBS_UP_DELTA
            TasteSignal.ExplicitThumbsDown -> THUMBS_DOWN_DELTA
            TasteSignal.EarlySkipWithin15s -> EARLY_SKIP_DELTA
            TasteSignal.SeekBackDuringMix -> SEEK_BACK_DELTA
            TasteSignal.ThreeConsecutivePlaythroughs -> PLAYTHROUGH_STREAK_BONUS
        }

        /** Preference-map key for a global per-recipe weight (plain recipe id). */
        fun recipeKey(recipeId: String): String = recipeId.trim()

        /** Preference-map key for a per-(recipe, genre) weight (`"S1:DANCE"`). */
        fun genreKey(recipeId: String, genreClass: String): String =
            "${recipeId.trim()}:${normalizeGenreClass(genreClass).orEmpty()}"

        /** Normalises a raw genre label to its canonical uppercase class, or null when blank. */
        fun normalizeGenreClass(raw: String?): String? =
            raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

        /** Clamps a raw value into [[MIN_WEIGHT], [MAX_WEIGHT]]; non-finite becomes `0.0`. */
        fun clampWeight(value: Double): Double =
            if (!value.isFinite()) 0.0 else value.coerceIn(MIN_WEIGHT, MAX_WEIGHT)

        /**
         * Inspector badge for [adjustment]: `"your taste: +0.12"` / `"your taste: -0.08"`,
         * or null when the adjustment is non-finite or below [BADGE_THRESHOLD] in
         * magnitude (nothing worth showing).
         */
        fun formatTasteBadge(adjustment: Double): String? {
            if (!adjustment.isFinite() || abs(adjustment) < BADGE_THRESHOLD) return null
            val cents = round(adjustment * 100.0).toInt()
            if (cents == 0) return null
            val sign = if (cents >= 0) "+" else "-"
            val absCents = abs(cents)
            val whole = absCents / 100
            val frac = (absCents % 100).toString().padStart(2, '0')
            return "your taste: $sign$whole.$frac"
        }
    }
}
