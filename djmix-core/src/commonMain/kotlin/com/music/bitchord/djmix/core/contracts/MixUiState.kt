package com.music.bitchord.djmix.core.contracts

import kotlinx.serialization.Serializable

/** Honest player-facing automix phase mapped 1:1 from `MixMachine` states. */
@Serializable
enum class MixUiPhase {
    IDLE,
    PREPARING,
    READY,
    ARMED,
    MOVED,
    RUNNING,
    HANDING_OVER,
    DONE,
    LAST_RESORT,
    SUSPENDED,
    FAILED,
}

/** Stage completion summary shown inside the Mix Inspector readiness bar. */
@Serializable
data class ReadinessSummary(
    val analysisDone: Boolean,
    val planDone: Boolean,
    val stemsReady: Int,
    val stemsRequired: Int,
    val rendererReady: Boolean,
) {
    init {
        require(stemsReady >= 0 && stemsRequired >= 0) {
            "Stem counts must be non-negative: ready=$stemsReady, required=$stemsRequired"
        }
    }
}

/**
 * Single read-only UI projection emitted by `MixMachine` for the player chip and Mix Inspector.
 * Enforces the Part A §2.3 rule that countdowns are visible ONLY in [MixUiPhase.READY] or [MixUiPhase.ARMED].
 */
@Serializable
data class MixUiState(
    val phase: MixUiPhase = MixUiPhase.IDLE,
    val chipText: String = "",
    val progressPct: Int = 0,
    val recipeName: String? = null,
    val countdownSec: Int? = null,
    val countdownVisible: Boolean = false,
    val movedTargetSec: Int? = null,
    val movedReason: String? = null,
    val inspectorReason: String = "",
    val readiness: ReadinessSummary = ReadinessSummary(
        analysisDone = false,
        planDone = false,
        stemsReady = 0,
        stemsRequired = 0,
        rendererReady = false,
    ),
    val canRemix: Boolean = false,
) {
    init {
        require(!countdownVisible || (phase == MixUiPhase.READY || phase == MixUiPhase.ARMED)) {
            "Countdown may only be visible in READY or ARMED, was phase=$phase"
        }
        require(progressPct in 0..100) { "progressPct must be in 0..100, got $progressPct" }
    }
}
