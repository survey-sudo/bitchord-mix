package com.music.bitchord.ui.djmix

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import com.music.bitchord.djmix.core.contracts.MixUiPhase
import com.music.bitchord.djmix.core.contracts.MixUiState
import kotlinx.coroutines.delay

/**
 * The one and only Automix player chip (Part A §7.1 / B9.1 / M7).
 *
 * Text is exact per [MixUiPhase] (no rung/tempo/"locked" clutter):
 * - `PREPARING` -> `"Preparing mix · ${progressPct}%"`
 * - `READY` / `ARMED` -> `"${recipeName} in ${mm:ss}"`
 * - `MOVED` -> `"Moved to ${mm:ss} · stems ready soon"` for 3 seconds, then
 *   preparation progress until the engine publishes READY
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

    var showMoved by remember(state.phase, state.movedTargetSec) {
        mutableStateOf(state.phase == MixUiPhase.MOVED)
    }
    LaunchedEffect(state.phase, state.movedTargetSec) {
        if (state.phase == MixUiPhase.MOVED) {
            delay(MOVED_VISIBLE_MS)
            showMoved = false
        }
    }

    val text = remember(state, showMoved) {
        chipTextFor(state, showMoved)
    }
    if (text.isBlank()) return

    SuggestionChip(
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "Automix: $text"
            role = Role.Button
            onClick(label = "Open mix inspector") {
                onClick()
                true
            }
        },
    )
}
