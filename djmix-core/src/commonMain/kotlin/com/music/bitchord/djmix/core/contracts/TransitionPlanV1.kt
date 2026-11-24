package com.music.bitchord.djmix.core.contracts

import kotlinx.serialization.Serializable

/**
 * Beat-synchronous timing knot mapping a mix-clock instant to a deck source position.
 * Optional exact double-precision seconds preserve bit-identity between host CLI and device.
 */
@Serializable
data class TempoKnot(
    val mixTimeMs: Long,
    val trackPosMs: Long,
    val bpm: Double = 0.0,
    val exactMixSec: Double? = null,
    val exactTrackPosSec: Double? = null,
)

/** Summary of a scored candidate plan shown in the Mix Inspector (top 3). */
@Serializable
data class CandidateScoreSummary(
    val recipeId: String,
    val recipeName: String,
    val score: Double,
    val reason: String = "",
)

/**
 * Frozen transition plan produced by `PairPreparer` (`schema = 1`).
 * Execution identity is strictly `(outTrackId, inTrackId, generation)` rather than structural equality.
 */
@Serializable
data class TransitionPlanV1(
    val schema: Int = 1,
    val planId: String,
    val generation: Long,
    val outTrackId: String,
    val inTrackId: String,
    val outTitle: String = "",
    val inTitle: String = "",
    val djLevel: DjLevel,
    val geometry: TransitionGeometry,
    val frozenExitMs: Long,
    val frozenEntryMs: Long,
    val swapDownbeatMs: Long,
    val overlapDurationMs: Long,
    val barDurationMs: Long,
    val tempoKnots: List<TempoKnot> = emptyList(),
    val recipeId: String,
    val recipeName: String,
    val recipeDescription: String = "",
    val recipeParams: Map<String, Double> = emptyMap(),
    val requiredStems: Set<DeckStemRequirement> = emptySet(),
    val hasNoStemVariant: Boolean = true,
    val placementReasons: List<String> = emptyList(),
    val topScores: List<CandidateScoreSummary> = emptyList(),
    val tasteAdjustment: Double = 0.0,
    val isLastResort: Boolean = false,
    val nextEligiblePhraseMs: Long? = null,
    val lastPhraseDownbeatMs: Long? = null,
) {
    init {
        require(schema == 1) { "TransitionPlanV1 requires schema == 1, got $schema" }
        require(generation >= 0L) { "generation must be non-negative, got $generation" }
        require(frozenExitMs >= 0L) { "frozenExitMs must be non-negative, got $frozenExitMs" }
        require(frozenEntryMs >= 0L) { "frozenEntryMs must be non-negative, got $frozenEntryMs" }
        require(overlapDurationMs >= 0L) { "overlapDurationMs must be non-negative, got $overlapDurationMs" }
        require(barDurationMs > 0L) { "barDurationMs must be positive, got $barDurationMs" }
        require(topScores.size <= 3) { "topScores holds at most top 3 candidates, got ${topScores.size}" }
    }
}
