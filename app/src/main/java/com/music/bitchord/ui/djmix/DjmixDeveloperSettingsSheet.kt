package com.music.bitchord.ui.djmix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.planner.BuiltInRecipeCatalog
import com.music.bitchord.playback.djmix.diagnostics.FlagsSnapshot

/**
 * Dev Settings sheet for Automix (Part B7 / B11.5 / M8): 7-tap developer
 * overrides fed by [FlagsSnapshot] and forwarded through callbacks into
 * `DjmixFlags` (M8 glue persists there — this sheet never writes storage).
 *
 * Sections:
 * - Force level (Auto / Subtle / Club / Festival)
 * - Force recipe (Auto + 26 built-in recipe ids)
 * - Switches: show grid on seekbar, show Inspector Details, Safe mode
 *   (S1/S5 straight blends only)
 * - Recipe Lab 30 s preview trigger
 * - NPU self-test + backend status ([npuStatusText])
 * - Clear stem cache, Export diagnostics (share the last Flag bundle)
 *
 * Opened by the 7-tap Developer entry (M7/M8 glue), not part of normal Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjmixDeveloperSettingsSheet(
    flags: FlagsSnapshot,
    npuStatusText: String?,
    onForceLevel: (DjLevel?) -> Unit,
    onForceRecipe: (String?) -> Unit,
    onShowGridOnSeekbar: (Boolean) -> Unit,
    onShowInspectorDetails: (Boolean) -> Unit,
    onRecipeLabPreview: () -> Unit,
    onNpuSelfTest: () -> Unit,
    onClearStemCache: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onSafeMode: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        var recipeMenuExpanded by remember { mutableStateOf(false) }
        val forcedRecipe = flags.forceRecipeId

        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Developer · Automix", style = MaterialTheme.typography.titleMedium)

            // Force level.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Force level", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = flags.forceLevel == null,
                        onClick = { onForceLevel(null) },
                        label = { Text("Auto") },
                        modifier = Modifier.semantics { contentDescription = "Force level: Auto" },
                    )
                    DjLevel.entries.forEach { level ->
                        FilterChip(
                            selected = flags.forceLevel == level,
                            onClick = { onForceLevel(level) },
                            label = { Text(level.displayName()) },
                            modifier = Modifier.semantics {
                                contentDescription = "Force level: ${level.displayName()}"
                            },
                        )
                    }
                }
            }

            // Force recipe.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Force recipe", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { recipeMenuExpanded = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Force recipe: ${forcedRecipe ?: "Auto"}"
                            stateDescription = if (recipeMenuExpanded) "Expanded" else "Collapsed"
                        },
                    ) {
                        Text(forcedRecipe ?: "Auto")
                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = recipeMenuExpanded,
                        onDismissRequest = { recipeMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Auto (no force)") },
                            modifier = Modifier.semantics { selected = forcedRecipe == null },
                            onClick = {
                                recipeMenuExpanded = false
                                onForceRecipe(null)
                            },
                        )
                        BuiltInRecipeCatalog.entries.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text("${entry.id} · ${entry.displayName}") },
                                modifier = Modifier.semantics { selected = forcedRecipe == entry.id },
                                onClick = {
                                    recipeMenuExpanded = false
                                    onForceRecipe(entry.id)
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Switches.
            DevSwitchRow(
                label = "Show grid on seekbar",
                checked = flags.showGridOnSeekbar,
                onCheckedChange = onShowGridOnSeekbar,
            )
            DevSwitchRow(
                label = "Show Inspector Details",
                checked = flags.showInspectorDetails,
                onCheckedChange = onShowInspectorDetails,
            )
            DevSwitchRow(
                label = "Safe mode (S1/S5 straight blends only)",
                checked = flags.safeMode,
                onCheckedChange = onSafeMode,
            )

            HorizontalDivider()

            // Actions.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onRecipeLabPreview) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Text("Recipe Lab · 30 s")
                }
                OutlinedButton(onClick = onNpuSelfTest) {
                    Icon(Icons.Rounded.Science, contentDescription = null)
                    Text("NPU self-test")
                }
            }
            Text(
                text = npuStatusText ?: "NPU backend: unknown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onClearStemCache) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                    Text("Clear stem cache")
                }
                OutlinedButton(onClick = onExportDiagnostics) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Text("Export diagnostics")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Shield,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    "Safe mode rollback: S1/S5 straight blends only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Label + switch row for developer toggles. */
@Composable
private fun DevSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** User-facing DJ level names for dev chips. */
private fun DjLevel.displayName(): String = when (this) {
    DjLevel.SUBTLE -> "Subtle"
    DjLevel.CLUB -> "Club"
    DjLevel.FESTIVAL -> "Festival"
}
