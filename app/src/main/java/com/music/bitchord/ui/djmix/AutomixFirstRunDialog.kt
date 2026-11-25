package com.music.bitchord.ui.djmix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.music.bitchord.djmix.core.contracts.DjLevel

/**
 * One-screen Automix first-run explainer (Part B9.5 / M7): the 3 DJ levels and
 * the single player chip. No tour, no second screen.
 *
 * @param onSelectLevel called with the chosen level when the user confirms.
 * @param onLearnToggle called when the Learn switch changes (feeds
 *   `TasteModel.learningEnabled` via `DjmixFlags`).
 * @param onDismiss called on dismiss or after confirm.
 */
@Composable
fun AutomixFirstRunDialog(
    onSelectLevel: (DjLevel) -> Unit,
    onLearnToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    learningEnabled: Boolean = true,
) {
    var selected by remember { mutableStateOf(DjLevel.SUBTLE) }
    var learn by remember(learningEnabled) { mutableStateOf(learningEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automix") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Automix blends each track into the next. Pick how bold it gets:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(Modifier.selectableGroup()) {
                DjLevel.entries.forEach { level ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == level,
                                role = Role.RadioButton,
                                onClick = { selected = level },
                            )
                            .semantics(mergeDescendants = true) {}
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == level, onClick = null)
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                level.title(),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                level.oneLiner(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .toggleable(value = learn, role = Role.Switch) {
                            learn = it
                            onLearnToggle(it)
                        }
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Learn my taste", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Thumbs up/down and listening patterns shape future picks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = learn,
                        onCheckedChange = null,
                    )
                }
                Text(
                    "One small chip in the player shows the mix — tap it for details.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSelectLevel(selected)
                    onDismiss()
                },
            ) {
                Text("Start mixing")
            }
        },
    )
}

/** User-facing level titles for the first-run explainer. */
private fun DjLevel.title(): String = when (this) {
    DjLevel.SUBTLE -> "Subtle"
    DjLevel.CLUB -> "Club"
    DjLevel.FESTIVAL -> "Festival"
}

/** One-line level descriptions (no jargon, no rung/tempo details). */
private fun DjLevel.oneLiner(): String = when (this) {
    DjLevel.SUBTLE -> "Gentle blends, no surprises."
    DjLevel.CLUB -> "Stems, filters and drops."
    DjLevel.FESTIVAL -> "Full FX and big moments."
}
