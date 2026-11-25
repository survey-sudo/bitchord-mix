package com.music.bitchord.ui.djmix

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.music.bitchord.playback.djmix.store.TransitionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Automix history (Part B9.3 / M7): last 100 transitions, newest first.
 *
 * - Tap a row → [onRecordClick] (M7 glue shows the plan summary).
 * - Long-press a row → rate 👍/👎 ([onRate] with `1`/`-1`) or Flag ([onFlag]).
 *   The trailing overflow button opens the same menu for discoverability.
 *
 * Driven only by caller-supplied [records] (e.g.
 * `DjmixAnalysisStore.recentTransitions(100)` from a view model) — this screen
 * never touches the database itself.
 *
 * Rating convention matches `DjmixAnalysisStore.updateRating`: `1` = thumbs up,
 * `-1` = thumbs down, `0` = unrated.
 *
 * @param records newest-first transition history (do not pass more than 100).
 */
@Composable
fun MixHistoryScreen(
    records: List<TransitionRecord>,
    onRecordClick: (TransitionRecord) -> Unit,
    onRate: (recordId: Long, rating: Int) -> Unit,
    onFlag: (TransitionRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFormat = remember {
        SimpleDateFormat("MM-dd HH:mm", Locale.US)
    }
    if (records.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No mixes yet", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(records, key = { it.id }) { record ->
            var menuExpanded by remember(record.id) { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClickLabel = "Open mix summary",
                        onLongClickLabel = "Rate or flag this mix",
                        role = Role.Button,
                        onClick = { onRecordClick(record) },
                        onLongClick = { menuExpanded = true },
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Mix ${record.recipe}, ${record.level}, " +
                            timeFormat.format(Date(record.createdAt))
                        stateDescription = when (record.rating) {
                            1 -> "Rated up"
                            -1 -> "Rated down"
                            else -> "Not rated"
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${record.recipe} · ${record.level}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = timeFormat.format(Date(record.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (record.rating) {
                    1 -> Icon(
                        Icons.Rounded.ThumbUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    -1 -> Icon(
                        Icons.Rounded.ThumbDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.semantics {
                            stateDescription = if (menuExpanded) "Expanded" else "Collapsed"
                        },
                    ) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Rate or flag this mix")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("👍 Rate up") },
                            modifier = Modifier.semantics { selected = record.rating == 1 },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.ThumbUp,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = if (record.rating == 1) {
                                { Icon(Icons.Rounded.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                menuExpanded = false
                                onRate(record.id, 1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("👎 Rate down") },
                            modifier = Modifier.semantics { selected = record.rating == -1 },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.ThumbDown,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = if (record.rating == -1) {
                                { Icon(Icons.Rounded.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                menuExpanded = false
                                onRate(record.id, -1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Flag this mix") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Flag, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onFlag(record)
                            },
                        )
                    }
                }
            }
        }
    }
}
