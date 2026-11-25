package com.music.bitchord.ui.djmix

import com.music.bitchord.djmix.core.contracts.MixUiPhase
import com.music.bitchord.djmix.core.contracts.MixUiState

/** Player copy stays limited to the active mix, without diagnostic tags. */
internal fun chipTextFor(state: MixUiState, showMoved: Boolean): String {
    val recipe = state.recipeName?.trim().orEmpty()
    return when (state.phase) {
        MixUiPhase.PREPARING -> "Preparing mix · ${state.progressPct}%"
        MixUiPhase.READY, MixUiPhase.ARMED -> {
            val countdownSec = state.countdownSec
            if (recipe.isNotEmpty() && countdownSec != null) {
                "$recipe in ${formatMmSs(countdownSec)}"
            } else {
                ""
            }
        }
        MixUiPhase.MOVED -> {
            val targetSec = state.movedTargetSec
            if (showMoved && targetSec != null) {
                "Moved to ${formatMmSs(targetSec)} · stems ready soon"
            } else {
                // Countdown is only valid after the state becomes READY/ARMED.
                "Preparing mix · ${state.progressPct}%"
            }
        }
        MixUiPhase.RUNNING, MixUiPhase.HANDING_OVER ->
            if (recipe.isNotEmpty()) "Mixing · $recipe" else "Mixing"
        MixUiPhase.LAST_RESORT -> "Ending with echo-out"
        MixUiPhase.IDLE, MixUiPhase.DONE, MixUiPhase.SUSPENDED, MixUiPhase.FAILED -> ""
    }
}

/** Formats total seconds as `m:ss`, including zero and durations over an hour. */
internal fun formatMmSs(totalSec: Int): String {
    val seconds = totalSec.coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal fun readinessText(state: MixUiState): String {
    val readiness = state.readiness
    fun mark(done: Boolean) = if (done) "✓" else "✗"
    val stems = if (readiness.stemsRequired == 0) {
        "Stems not required"
    } else {
        "Stems ${readiness.stemsReady}/${readiness.stemsRequired}"
    }
    return "Analysis ${mark(readiness.analysisDone)} · Plan ${mark(readiness.planDone)} · " +
        "$stems · Renderer ${mark(readiness.rendererReady)}"
}

/** Spoken states do not depend on a screen reader's pronunciation of checkmarks. */
internal fun readinessContentDescription(state: MixUiState): String {
    val readiness = state.readiness
    fun status(done: Boolean) = if (done) "ready" else "not ready"
    val stems = if (readiness.stemsRequired == 0) {
        "Stems not required"
    } else {
        "Stems ${readiness.stemsReady} of ${readiness.stemsRequired} ready"
    }
    return "Readiness: Analysis ${status(readiness.analysisDone)}. " +
        "Plan ${status(readiness.planDone)}. $stems. Renderer ${status(readiness.rendererReady)}."
}

internal fun inspectorTasteBadge(tasteAdjustment: Double, learningEnabled: Boolean): String? =
    com.music.bitchord.ui.player.inspectorTasteBadge(tasteAdjustment, learningEnabled)

/** Hide a previously learned offset immediately when learning is switched off. */
internal fun inspectorWhyText(reason: String, tasteAdjustment: Double, learningEnabled: Boolean): String =
    com.music.bitchord.ui.player.inspectorWhyText(reason, tasteAdjustment, learningEnabled)

internal const val MOVED_VISIBLE_MS: Long = 3_000L
