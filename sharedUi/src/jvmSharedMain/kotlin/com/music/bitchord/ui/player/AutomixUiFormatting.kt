package com.music.bitchord.ui.player

import com.music.bitchord.playback.smart.AutomixStatus
import com.music.bitchord.playback.smart.MixInspectorPhase
import com.music.bitchord.playback.smart.MixInspectorState
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/** Show the frozen plan's delta, with locale-independent signed hundredths. */
fun inspectorTasteBadge(tasteAdjustment: Double, learningEnabled: Boolean): String? {
    if (!learningEnabled || !tasteAdjustment.isFinite() || abs(tasteAdjustment) < 0.01) return null
    val cents = round(abs(tasteAdjustment) * 100.0).toLong()
    val sign = if (tasteAdjustment < 0.0) "-" else "+"
    return "your taste: $sign${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

/** Reformat safely after republishing, including removal of a now-disabled badge. */
fun inspectorWhyText(reason: String, tasteAdjustment: Double, learningEnabled: Boolean): String {
    var placement = reason.trim()
    while (TASTE_SUFFIX.containsMatchIn(placement)) {
        placement = TASTE_SUFFIX.replace(placement, "").trimEnd()
    }
    return listOfNotNull(
        placement.takeIf { it.isNotEmpty() },
        inspectorTasteBadge(tasteAdjustment, learningEnabled),
    ).joinToString(" · ")
}

/** A handoff changes only held badge visibility; a matching key may refresh all fields. */
fun inspectorHeldSnapshot(held: MixInspectorState, live: MixInspectorState?): MixInspectorState =
    if (live != null && live.key == held.key) live else held.copy(learningEnabled = live?.learningEnabled == true)

private val TASTE_SUFFIX = Regex("(?:\\s*·\\s*)?your taste: [+-]\\d+\\.\\d{2}\\s*$")

/** The single player label. Diagnostic headlines never escape into the chip. */
fun automixChipLabel(
    status: AutomixStatus?,
    inspector: MixInspectorState?,
    showMoved: Boolean = true,
): String? {
    if (status == null) return null
    if (inspector != null && inspector.phase in INACTIVE_INSPECTOR_PHASES) return null
    val recipe = status.planType.trim().takeIf { it.isNotEmpty() }
        ?: inspector?.recipeName?.trim()?.takeIf { it.isNotEmpty() }
    val headline = status.headline.trim().trimEnd('.').removeSuffix(" · locked").trim()
    return when (status.state) {
        "echo" -> "Ending with echo-out"
        "mixing" -> recipe?.let { "Mixing · $it" } ?: "Mixing"
        "ready", "arming" -> {
            val countdown = COUNTDOWN.find(headline)
            if (recipe != null && countdown != null) "$recipe in ${countdown.groupValues[1]}" else null
        }
        "preparing" -> if (status.style == "moved" && showMoved) {
            MOVED_TARGET.find(headline)?.let { "Moved to ${it.groupValues[1]} · stems ready soon" }
                ?: preparationLabel(status.progress)
        } else {
            preparationLabel(status.progress)
        }
        else -> null
    }
}

/** Visible readiness retains text and symbols, independent of the palette. */
fun inspectorReadinessText(state: MixInspectorState): String {
    fun mark(done: Boolean) = if (done) "✓" else "✗"
    val required = state.stemCount?.coerceAtLeast(0)
    val stems = when {
        required == null -> "—"
        required == 0 -> "not required"
        state.stemsReady == true -> "$required/$required"
        state.stemsReady == false -> "0/$required"
        else -> "—/$required"
    }
    return "Analysis ${mark(analysisReady(state))} · Plan ${mark(planReady(state))} · " +
        "Stems $stems · Renderer ${mark(rendererReady(state))}"
}

/** Worded readiness for TalkBack; a cancelled phase cannot imply completed work. */
fun inspectorReadinessContentDescription(state: MixInspectorState): String {
    fun status(done: Boolean) = if (done) "ready" else "not ready"
    val required = state.stemCount?.coerceAtLeast(0)
    val stems = when {
        required == null -> "Stems readiness unknown"
        required == 0 -> "Stems not required"
        state.stemsReady == true -> "Stems $required of $required ready"
        state.stemsReady == false -> "Stems 0 of $required ready"
        else -> "Stems readiness unknown, $required required"
    }
    return "Readiness: Analysis ${status(analysisReady(state))}. " +
        "Plan ${status(planReady(state))}. $stems. Renderer ${status(rendererReady(state))}."
}

/** Compose supplies the live system duration scale on Android and other hosts. */
fun automixReducedMotionEnabled(reduceAnimation: Boolean, systemDurationScale: Float): Boolean =
    reduceAnimation || systemDurationScale == 0f

/** The inspector snaps to measured progress when either motion preference disables animation. */
fun automixPlayheadAnimationDurationMillis(reducedMotion: Boolean): Int = if (reducedMotion) 0 else 250

private fun analysisReady(state: MixInspectorState): Boolean =
    state.analysisMs != null || state.phase in PLANNED_OR_LATER_PHASES

private fun planReady(state: MixInspectorState): Boolean =
    state.planMs != null || state.phase in PLANNED_OR_LATER_PHASES

private fun rendererReady(state: MixInspectorState): Boolean =
    state.buildMs != null || !state.path.isNullOrBlank() || state.phase in RENDERER_READY_PHASES

private fun preparationLabel(rawProgress: Float): String {
    val progress = rawProgress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    return "Preparing mix · ${(progress * 100f).roundToInt()}%"
}

private val INACTIVE_INSPECTOR_PHASES = setOf(
    MixInspectorPhase.IDLE, MixInspectorPhase.DONE, MixInspectorPhase.CANCELLED,
)
private val PLANNED_OR_LATER_PHASES = setOf(
    MixInspectorPhase.PLANNED, MixInspectorPhase.STEMMING, MixInspectorPhase.PREPARED,
    MixInspectorPhase.BUILDING, MixInspectorPhase.ARMED, MixInspectorPhase.RUNNING, MixInspectorPhase.DONE,
)
private val RENDERER_READY_PHASES = setOf(
    MixInspectorPhase.ARMED, MixInspectorPhase.RUNNING, MixInspectorPhase.DONE,
)
private val COUNTDOWN = Regex("\\bin\\s+(\\d+:\\d{2})(?=\\s|[·.]|$)")
private val MOVED_TARGET = Regex("^Moved to (\\d+:\\d{2})(?=\\s|[·.]|$)")
