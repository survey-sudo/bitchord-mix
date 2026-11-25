package com.music.bitchord.ui.djmix

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.ui.player.automixPlayheadAnimationDurationMillis
import com.music.bitchord.ui.player.rememberAutomixReducedMotion

/**
 * One labelled FX segment on the Inspector timeline. [label] is always drawn as
 * text next to its colour band — colour is never the sole information (B9.6).
 *
 * @param startFraction / endFraction within the timeline window (`0..1`, start < end).
 */
data class FxLane(
    val label: String,
    val startFraction: Float,
    val endFraction: Float,
)

/**
 * Mix Inspector sheet (Part B9.2 / A§7.3 / M7) — order:
 * header (titles, DJ level, recipe + one-liner) → dual-waveform timeline →
 * readiness row + progress → why sentence + taste badge → actions →
 * collapsible Details (collapsed by default).
 *
 * Driven only by [MixUiState], [TransitionPlanV1], caller-supplied waveform
 * slices + callbacks. No controller/preparer imports: M7 glue feeds view-model
 * state and forwards callbacks (`Try another mix` -> remix event, thumbs ->
 * `TasteModel` + `DjmixAnalysisStore`, Flag -> `DiagnosticsBundler`).
 *
 * @param outWaveform / inWaveform normalised `0..1` slices already cropped to the
 *   transition window (e.g. `TrackAnalysisV3.energyCurve` across
 *   `[windowStartMs, windowEndMs]`); empty renders an empty lane, never crashes.
 * @param playheadFraction live playhead position in the window (`0..1`); animated
 *   unless reduced motion is on (animator scale `0` or reduced-motion setting).
 * @param fxLanes labelled FX segments (echo, sweep, …) drawn under the waveforms.
 * @param inferenceBackend short backend tag for Details (`"QNN"` or `"CPU"`); null hides the row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixInspectorSheetV3(
    state: MixUiState,
    plan: TransitionPlanV1?,
    outWaveform: List<Float>,
    inWaveform: List<Float>,
    playheadFraction: Float,
    fxLanes: List<FxLane>,
    onRemix: () -> Unit,
    onThumbsUp: () -> Unit,
    onThumbsDown: () -> Unit,
    onFlag: () -> Unit,
    onDismiss: () -> Unit,
    inferenceBackend: String? = null,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        MixInspectorContent(
            state = state,
            plan = plan,
            outWaveform = outWaveform,
            inWaveform = inWaveform,
            playheadFraction = playheadFraction,
            fxLanes = fxLanes,
            inferenceBackend = inferenceBackend,
            onRemix = onRemix,
            onThumbsUp = onThumbsUp,
            onThumbsDown = onThumbsDown,
            onFlag = onFlag,
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        )
    }
}

/** Embeddable Inspector body (same contract as [MixInspectorSheetV3]). */
@Composable
fun MixInspectorContent(
    state: MixUiState,
    plan: TransitionPlanV1?,
    outWaveform: List<Float>,
    inWaveform: List<Float>,
    playheadFraction: Float,
    fxLanes: List<FxLane>,
    inferenceBackend: String?,
    onRemix: () -> Unit,
    onThumbsUp: () -> Unit,
    onThumbsDown: () -> Unit,
    onFlag: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberAutomixReducedMotion()
    val learningEnabled by AppSettings.automixLearnFromReactions.collectAsStateWithLifecycle()
    val targetFraction = playheadFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val timelineFraction = if (reducedMotion) {
        targetFraction
    } else {
        val animatedFraction by animateFloatAsState(
            targetValue = targetFraction,
            animationSpec = tween(durationMillis = automixPlayheadAnimationDurationMillis(reducedMotion)),
            label = "inspectorPlayhead",
        )
        animatedFraction
    }
    var detailsExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header: current → next, DJ level, recipe + one-liner.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "${plan?.outTitle?.ifBlank { "Current track" } ?: "Current track"} → " +
                    "${plan?.inTitle?.ifBlank { "Next track" } ?: "Next track"}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
            Text(
                text = "${plan?.djLevel?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Subtle"} · " +
                    "${plan?.recipeName ?: state.recipeName ?: "Mix"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan?.recipeDescription?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Dual-waveform transition timeline.
        TransitionTimeline(
            plan = plan,
            outWaveform = outWaveform,
            inWaveform = inWaveform,
            playheadFraction = timelineFraction,
            fxLanes = fxLanes,
            modifier = Modifier.fillMaxWidth(),
        )

        // Readiness row + progress bar.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = readinessText(state),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = readinessContentDescription(state)
                },
            )
            LinearProgressIndicator(
                progress = { (state.progressPct / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Mix preparation progress"
                },
            )
        }

        // Why sentence + optional taste badge.
        val why = state.inspectorReason.ifBlank {
            plan?.placementReasons?.firstOrNull().orEmpty()
        }
        val whyText = inspectorWhyText(why, plan?.tasteAdjustment ?: 0.0, learningEnabled)
        if (whyText.isNotBlank()) {
            Text(text = whyText, style = MaterialTheme.typography.bodySmall)
        }

        // Actions: Try another mix (only when canRemix), thumbs, Flag.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.canRemix) {
                TextButton(
                    onClick = onRemix,
                    modifier = Modifier.semantics { contentDescription = "Try another mix" },
                ) { Text("Try another mix") }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onThumbsUp) {
                Icon(Icons.Rounded.ThumbUp, contentDescription = "This mix worked (thumbs up)")
            }
            IconButton(onClick = onThumbsDown) {
                Icon(Icons.Rounded.ThumbDown, contentDescription = "This mix didn't work (thumbs down)")
            }
            IconButton(onClick = onFlag) {
                Icon(Icons.Rounded.Flag, contentDescription = "Flag this mix for diagnostics")
            }
        }

        HorizontalDivider()

        // Collapsible Details (collapsed by default).
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Details", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { detailsExpanded = !detailsExpanded },
                modifier = Modifier.semantics {
                    stateDescription = if (detailsExpanded) "Expanded" else "Collapsed"
                },
            ) {
                Icon(
                    if (detailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (detailsExpanded) "Hide details" else "Show details",
                )
            }
        }
        if (detailsExpanded) {
            DetailsBody(state = state, plan = plan, inferenceBackend = inferenceBackend)
        }
    }
}

/** Detail rows: top-3 scores, timings, backend, fallback/move reason. */
@Composable
private fun DetailsBody(
    state: MixUiState,
    plan: TransitionPlanV1?,
    inferenceBackend: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val topScores = plan?.topScores ?: emptyList()
        if (topScores.isNotEmpty()) {
            Text("Top candidates", style = MaterialTheme.typography.labelLarge)
            topScores.forEach { score ->
                Text(
                    "· ${score.recipeName} (${score.recipeId}) — ${"%.2f".format(score.score)}" +
                        if (score.reason.isNotBlank()) " — ${score.reason}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        plan?.let {
            Text("Exit ${formatMmSs((it.frozenExitMs / 1000).toInt())} · " +
                "Entry ${formatMmSs((it.frozenEntryMs / 1000).toInt())} · " +
                "Overlap ${"%.1f".format(it.overlapDurationMs / 1000.0)} s · " +
                "Bar ${"%.2f".format(it.barDurationMs / 1000.0)} s",
                style = MaterialTheme.typography.bodySmall)
        }
        if (inferenceBackend != null) {
            Text("Backend ep=${inferenceBackend.uppercase()}", style = MaterialTheme.typography.bodySmall)
        }
        val fallbackReason = state.movedReason
            ?: if (plan?.isLastResort == true) "Last-resort musical exit (echo-out)" else null
        if (!fallbackReason.isNullOrBlank()) {
            Text("Fallback: $fallbackReason", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Dual-waveform timeline: OUT (top) & IN (bottom) bars, shaded overlap zone,
 * swap-downbeat marker, labelled FX lanes, live playhead. Pure [Canvas] —
 * waveforms are caller-supplied slices, never audio I/O here.
 */
@Composable
private fun TransitionTimeline(
    plan: TransitionPlanV1?,
    outWaveform: List<Float>,
    inWaveform: List<Float>,
    playheadFraction: Float,
    fxLanes: List<FxLane>,
    modifier: Modifier = Modifier,
) {
    val overlapColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val outColor = MaterialTheme.colorScheme.primary
    val inColor = MaterialTheme.colorScheme.secondary
    val markerColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurface
    val lanePalette = listOf(
        Color(0xFF7C4DFF), Color(0xFF00B0FF), Color(0xFF00C853),
        Color(0xFFFF9100), Color(0xFFE040FB), Color(0xFF00B8D4),
    )
    val windowStartMs = remember(plan) {
        ((plan?.frozenExitMs ?: 0L) - WINDOW_PADDING_MS).coerceAtLeast(0L)
    }
    val windowEndMs = remember(plan) {
        windowStartMs + (plan?.overlapDurationMs ?: 0L) + WINDOW_PADDING_MS * 2L
    }
    val windowMs = (windowEndMs - windowStartMs).coerceAtLeast(1L)

    Canvas(
        modifier = modifier
            .height(TIMELINE_HEIGHT)
            .semantics {
                contentDescription = buildString {
                    append("Transition timeline. OUT: outgoing track, top waveform. IN: incoming track, bottom waveform.")
                    if (plan != null) append(" Shaded overlap and SWAP downbeat marker.")
                    if (fxLanes.isNotEmpty()) append(" Effects: ${fxLanes.joinToString { it.label }}.")
                }
                progressBarRangeInfo = ProgressBarRangeInfo(playheadFraction, 0f..1f)
            },
    ) {
        val w = size.width
        val h = size.height
        val outH = h * 0.42f
        val inTop = h * 0.48f
        val inH = h * 0.42f
        val lanesTop = h - FX_LANE_HEIGHT.toPx()

        fun fractionOfMs(ms: Long): Float = ((ms - windowStartMs).toFloat() / windowMs.toFloat()).coerceIn(0f, 1f)

        // Overlap shade.
        plan?.let {
            val x0 = fractionOfMs(it.frozenExitMs) * w
            val x1 = fractionOfMs(it.frozenExitMs + it.overlapDurationMs) * w
            drawRect(
                color = overlapColor,
                topLeft = Offset(x0, 0f),
                size = Size((x1 - x0).coerceAtLeast(2f), h),
            )
            if (x1 - x0 >= 48f * density) {
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = labelColor.toArgb()
                        textSize = 10f * density
                        isAntiAlias = true
                    }
                    drawText("OVERLAP", x0 + 3f * density, h - FX_LANE_HEIGHT.toPx() - 3f * density, paint)
                }
            }
        }

        fun drawBars(values: List<Float>, top: Float, height: Float, color: Color) {
            if (values.isEmpty()) return
            val barW = (w / values.size).coerceAtLeast(1f)
            val midY = top + height / 2f
            values.forEachIndexed { index, value ->
                val v = value.coerceIn(0f, 1f)
                val barH = (v * height).coerceAtLeast(1f)
                val x = index * barW
                drawRect(
                    color = color.copy(alpha = 0.85f),
                    topLeft = Offset(x, midY - barH / 2f),
                    size = Size((barW * 0.8f).coerceAtLeast(1f), barH),
                )
            }
        }
        drawBars(outWaveform, 0f, outH, outColor)
        drawBars(inWaveform, inTop, inH, inColor)
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = labelColor.toArgb()
                textSize = 11f * density
                isAntiAlias = true
            }
            drawText("OUT", 4f * density, 14f * density, paint)
            drawText("IN", 4f * density, inTop + 14f * density, paint)
        }

        // Swap downbeat marker (line + text label, never colour alone).
        plan?.let {
            val x = fractionOfMs(it.swapDownbeatMs) * w
            drawLine(markerColor, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = markerColor.toArgb()
                    textSize = 10f * density
                    isAntiAlias = true
                }
                drawText("SWAP", x + 2f, 12f * density, paint)
            }
        }

        // FX lanes with text labels.
        fxLanes.forEachIndexed { index, lane ->
            val color = lanePalette[index % lanePalette.size]
            val start = lane.startFraction.coerceIn(0f, 1f)
            val end = lane.endFraction.coerceIn(start, 1f)
            val x0 = start * w
            val x1 = end * w
            drawRect(
                color = color.copy(alpha = 0.35f),
                topLeft = Offset(x0, lanesTop),
                size = Size((x1 - x0).coerceAtLeast(2f), FX_LANE_HEIGHT.toPx()),
            )
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    this.color = color.toArgb()
                    textSize = 9f * density
                    isAntiAlias = true
                }
                drawText(lane.label.uppercase(), x0 + 4f, lanesTop + FX_LANE_HEIGHT.toPx() - 3f, paint)
            }
        }

        // Live playhead (animated by caller unless reduced motion).
        val px = playheadFraction.coerceIn(0f, 1f) * w
        drawLine(Color.White, Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
    }
}

private val TIMELINE_HEIGHT = 140.dp
private val FX_LANE_HEIGHT = 16.dp
private const val WINDOW_PADDING_MS: Long = 4_000L
