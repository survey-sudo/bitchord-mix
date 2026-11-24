package com.music.bitchord.ui.djmix

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.music.bitchord.djmix.core.contracts.MixUiPhase
import com.music.bitchord.djmix.core.contracts.MixUiState
import kotlinx.coroutines.delay

/**
 * The one and only Automix player chip (Part A §7.1 / B9.1 / M7).
 *
 * Text is exact per [MixUiPhase] (no rung/tempo/"locked" clutter):
 * - `PREPARING` -> `"Preparing mix · ${progressPct}%"`
 * - `READY` / `ARMED` -> `"${recipeName} in ${mm:ss}"`
 * - `MOVED` -> `"Moved to ${mm:ss} · stems ready soon"` for 3 seconds, then the
 *   READY-style text
 * - `RUNNING` -> `"Mixing · ${recipeName}"`
 * - `LAST_RESORT` -> `"Ending with echo-out"`
 *
 * Driven only by [MixUiState]; never renders in `IDLE`. Tapping opens the Mix
 * Inspector (M7 glue passes `onClick`).
 *
 * @param state single source of truth from `MixMachine` (see `MixUiProjection`).
 * @param onClick invoked when the chip is tapped (open Inspector).
 */
@Composable
fun AutomixStatusChip(
    state: MixUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.phase == MixUiPhase.IDLE) return

    var showMoved by remember(state.phase) {
        mutableStateOf(state.phase == MixUiPhase.MOVED)
    }
    LaunchedEffect(state.phase) {
        if (state.phase == MixUiPhase.MOVED) {
            delay(MOVED_VISIBLE_MS)
            showMoved = false
        }
    }

    val text = remember(state, showMoved) {
        chipTextFor(state, showMoved)
    }
    if (text.isBlank()) return

    Box(
        modifier = modifier.semantics { contentDescription = "Automix: $text" },
    ) {
        SuggestionChip(
            onClick = onClick,
            label = { Text(text, style = MaterialTheme.typography.labelLarge) },
        )
    }
}

/** Computes the exact Part A §7 chip text; falls back to the state's own chip text. */
internal fun chipTextFor(state: MixUiState, showMoved: Boolean): String {
    val recipe = state.recipeName?.trim().orEmpty()
    return when (state.phase) {
        MixUiPhase.PREPARING -> "Preparing mix · ${state.progressPct}%"
        MixUiPhase.READY, MixUiPhase.ARMED -> {
            val countdownSec = state.countdownSec
            if (recipe.isNotEmpty() && countdownSec != null) {
                "$recipe in ${formatMmSs(countdownSec)}"
            } else {
                state.chipText
            }
        }
        MixUiPhase.MOVED -> {
            val targetSec = state.movedTargetSec
            if (showMoved && targetSec != null) {
                "Moved to ${formatMmSs(targetSec)} · stems ready soon"
            } else {
                val countdownSec = state.countdownSec
                if (recipe.isNotEmpty() && countdownSec != null) {
                    "$recipe in ${formatMmSs(countdownSec)}"
                } else {
                    state.chipText
                }
            }
        }
        MixUiPhase.RUNNING, MixUiPhase.HANDING_OVER ->
            if (recipe.isNotEmpty()) "Mixing · $recipe" else state.chipText
        MixUiPhase.LAST_RESORT -> "Ending with echo-out"
        else -> state.chipText
    }
}

/** Formats a total-seconds value as `m:ss` (e.g. 75 -> `"1:15"`). */
internal fun formatMmSs(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private const val MOVED_VISIBLE_MS: Long = 3_000L
