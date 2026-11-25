package com.music.bitchord.playback.djmix

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.AutomixStatus
import com.music.bitchord.playback.MixDebug
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.RetireGuard
import com.music.bitchord.playback.TransitionFilters
import com.music.bitchord.playback.TransitionFilterProcessor
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.contracts.MixUiPhase
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Narrow host seam supplying playback state, analysis, and filters
 * to [MixStatusDescriber] without exposing controller internals.
 */
internal interface MixStatusHost {
    fun active(): ExoPlayer
    val outgoing: ExoPlayer?
    val incoming: ExoPlayer?
    val tailTarget: ExoPlayer?
    val handedOff: Boolean
    val blendArmLeadMs: Long
    val mixExecutor: MixExecutor
    val loopSession: LoopMixSession?
    val classicFallbackReason: String
    val lastSmartPlan: TransitionPlan?
    val lastSmartPlanStartMs: Long
    fun isNativeRendererAvailable(player: ExoPlayer): Boolean
    fun analysisFor(item: MediaItem): TrackAnalysis
    fun stateOf(item: MediaItem, analysis: TrackAnalysis): TrackAnalysisState
    val filters: TransitionFilters
    val tracks: TransitionFilters
}

/**
 * Owns Automix detail line formatting, countdown progress, tempo display,
 * and filter reopening discipline during exit/silence (M9 slice C-6).
 */
internal class MixStatusDescriber(private val host: MixStatusHost) {

    /**
     * The detailed Automix line under the stream stats: both songs' analysis
     * while it waits, then the planned mix and why a pair isn't beatmatched.
     */
    fun publishAutomixDetail() {
        val player = host.active()
        val item = player.currentMediaItem
        val status = when {
            !AppSettings.smartFadeEnabled.value || item == null || ListenTogether.state.value.inParty -> null
            else -> describeAutomix(player, item)
        }
        if (AppSettings.automixStatus.value != status) AppSettings.automixStatus.value = status
        val detail = status?.detail
        if (AppSettings.automixDetail.value != detail) AppSettings.automixDetail.value = detail
    }

    fun countdownProgress(secondsLeft: Double): Float {
        val lead = (host.blendArmLeadMs / 1000.0).coerceAtLeast(1.0)
        return (1.0 - secondsLeft / lead).toFloat().coerceIn(0f, 1f)
    }

    fun status(
        headline: String,
        detail: String,
        style: String,
        progress: Float,
        state: String = "",
        planType: String = "",
        bars: Int = 0,
        tempoPct: Float = 0f,
        locked: Boolean = false,
        rung: String = "",
        reason: String = "",
        overlapBars: Int = 0,
    ): AutomixStatus {
        val (head, body) = MixDebug.decorate(headline, detail)
        return AutomixStatus(head, body, style, progress, state, planType, bars, tempoPct, locked, rung, reason, overlapBars)
    }

    /** Seconds until [startMs] on the player clock, never negative. */
    fun shownSeconds(player: ExoPlayer, startMs: Long): Double =
        ((startMs - player.currentPosition) / 1000.0).coerceIn(0.0, 3600.0)

    fun describeAutomix(player: ExoPlayer, item: MediaItem): AutomixStatus {
        val ui = host.mixExecutor.uiState.value
        val mState = host.mixExecutor.currentState()
        val nextIndex = player.nextMediaItemIndex
        val next = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
        val mPlan = mState.plan
        val planBars = mPlan?.let { (it.overlapDurationMs / it.barDurationMs.coerceAtLeast(1L)).toInt() } ?: 0
        val outBpm = host.analysisFor(item).bpm
        val inBpm = next?.let { host.analysisFor(it).bpm } ?: 0.0
        val tempoMatchPct = tempoPct(outBpm, inBpm)
        val late = host.classicFallbackReason.takeIf { it.isNotBlank() }?.let { " Classic: $it." }.orEmpty()

        // Drive headline & countdown from MixExecutor.uiState; countdown shown ONLY in READY/ARMED.
        when (ui.phase) {
            MixUiPhase.READY, MixUiPhase.ARMED -> {
                val sec = ui.countdownSec ?: 0
                val lockTag = " · locked"
                return status(
                    headline = ui.chipText + lockTag,
                    detail = "${ui.chipText}. ${ui.inspectorReason}".trim(),
                    style = (mPlan?.geometry?.name ?: "mix").lowercase(),
                    progress = countdownProgress(sec.toDouble()),
                    state = if (ui.phase == MixUiPhase.ARMED) "arming" else "ready",
                    planType = ui.recipeName.orEmpty(),
                    bars = planBars,
                    tempoPct = tempoMatchPct,
                    locked = true,
                    rung = ui.recipeName.orEmpty(),
                    reason = ui.inspectorReason,
                    overlapBars = planBars,
                )
            }
            MixUiPhase.PREPARING, MixUiPhase.MOVED -> {
                return status(
                    headline = ui.chipText,
                    detail = listOfNotNull(
                        ui.recipeName?.takeIf { it.isNotBlank() },
                        ui.movedReason?.takeIf { it.isNotBlank() },
                        ui.inspectorReason.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { ui.chipText },
                    style = if (ui.phase == MixUiPhase.MOVED) "moved" else "preparing",
                    progress = (ui.progressPct / 100f).coerceIn(0f, 1f),
                    state = "preparing",
                    planType = ui.recipeName.orEmpty(),
                    bars = planBars,
                    tempoPct = tempoMatchPct,
                    locked = false,
                    rung = ui.recipeName.orEmpty(),
                    reason = ui.inspectorReason,
                    overlapBars = planBars,
                )
            }
            MixUiPhase.RUNNING, MixUiPhase.HANDING_OVER, MixUiPhase.LAST_RESORT -> {
                val session = host.loopSession
                val aligned = session?.alignedErrorMs?.takeIf { it.isFinite() }
                    ?.let { " Handover ${"%+.1f".format(Locale.ROOT, it)} ms." }.orEmpty()
                return status(
                    headline = ui.chipText,
                    detail = "${ui.chipText}. ${ui.inspectorReason}$aligned".trim(),
                    style = (mPlan?.geometry?.name ?: "mix").lowercase(),
                    progress = (ui.progressPct / 100f).coerceIn(0f, 1f),
                    state = if (ui.phase == MixUiPhase.LAST_RESORT) "echo" else "mixing",
                    planType = ui.recipeName.orEmpty(),
                    bars = planBars,
                    tempoPct = tempoMatchPct,
                    locked = true,
                    rung = ui.recipeName.orEmpty(),
                    reason = ui.inspectorReason,
                    overlapBars = planBars,
                )
            }
            MixUiPhase.SUSPENDED -> {
                return status(
                    headline = "Automix suspended",
                    detail = ui.inspectorReason.ifBlank { "Suspended" },
                    style = "wait",
                    progress = -1f,
                    state = "suspended",
                    locked = false,
                    reason = ui.inspectorReason,
                )
            }
            MixUiPhase.FAILED, MixUiPhase.IDLE, MixUiPhase.DONE -> {
                // Fall through to classic unavailable display (when active) or waiting labels.
            }
        }

        val plan = host.lastSmartPlan
        if (plan != null && !plan.blocked && next != null && AppSettings.smartTransitionWindow.value != null &&
            (!host.isNativeRendererAvailable(player) || mState is MixState.Failed)
        ) {
            val inSeconds = ((host.lastSmartPlanStartMs - player.currentPosition) / 1000.0).coerceAtLeast(0.0)
            val bars = if (plan.beatSeconds > 0) (plan.fadeSeconds / (4 * plan.beatSeconds)).roundToInt() else 0
            val length = if (bars > 0) "$bars bars" else "${"%.1f".format(Locale.ROOT, plan.fadeSeconds)} s"
            val style = mixStyle(plan)
            val bridge = if (outBpm > 0 && inBpm > 0) "${outBpm.roundToInt()}→${inBpm.roundToInt()}" else ""
            val headline = listOf(style.replaceFirstChar { it.uppercase() }, "classic", bridge)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            return status(
                headline = headline,
                detail = "${plan.mixSummary.ifBlank { "$style, $length" }}$late".trim(),
                style = style,
                progress = if (inSeconds > 0) countdownProgress(inSeconds) else 1f,
                state = if (inSeconds > 0) "preparing" else "mixing",
                planType = style.replaceFirstChar { it.uppercase() },
                bars = bars,
                tempoPct = tempoMatchPct,
                locked = false,
                rung = style.replaceFirstChar { it.uppercase() },
                reason = host.classicFallbackReason.ifBlank { plan.reason },
                overlapBars = bars,
            )
        }
        fun label(target: MediaItem?, outgoing: Boolean): String {
            if (target == null) return "none"
            val analysis = host.analysisFor(target)
            val state = host.stateOf(target, analysis)
            if (state == TrackAnalysisState.ANALYSING) return "analysing"
            if (state == TrackAnalysisState.WAITING) return "waiting"
            if (state == TrackAnalysisState.FAILED || !analysis.isUsable) return "failed"
            val side = if (outgoing) analysis.tailBeats else analysis.headBeats
            val bpm = side?.bpm ?: analysis.bpm
            val confidence = side?.confidence ?: analysis.beatConfidence
            val key = analysis.key.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            val grid = if (side != null) "model grid" else "dsp grid"
            val refining = if (state == TrackAnalysisState.REFINING) ", refining" else ""
            return "${bpm.roundToInt()} BPM$key, $grid ${"%.2f".format(Locale.ROOT, confidence)}$refining"
        }
        val detail = "This song: ${label(item, true)}. Next: ${label(next, false)}."
        val nextLabel = label(next, false)
        return status(
            headline = "Next $nextLabel".take(42),
            detail = detail,
            style = "wait",
            progress = -1f,
            rung = if (ui.phase == MixUiPhase.FAILED) "Classic fallback" else "",
            reason = ui.inspectorReason.ifBlank { host.classicFallbackReason },
            overlapBars = 0,
        )
    }

    /** Tempo match of [from]->[to] in percent after octave folding. */
    fun tempoPct(from: Double, to: Double): Float {
        if (!(from > 0) || !(to > 0)) return 0f
        var r = from / to
        while (r > 2.0) r /= 2.0
        while (r < 0.5) r *= 2.0
        return (r * 100.0).toFloat()
    }

    fun loopTempo(plan: LoopMix.Plan): String {
        val from = plan.out.bpm.roundToInt()
        val to = plan.targetBpm.roundToInt()
        return if (from == to) "$from BPM" else "$from→$to"
    }

    fun mixStyle(plan: TransitionPlan): String = when (plan.djEffect) {
        com.music.bitchord.playback.smart.DjEffect.ECHO -> "echo"
        com.music.bitchord.playback.smart.DjEffect.LOOP_ROLL -> "roll"
        com.music.bitchord.playback.smart.DjEffect.REVERB -> "reverb"
        com.music.bitchord.playback.smart.DjEffect.FLANGER -> "blend"
        com.music.bitchord.playback.smart.DjEffect.NONE -> when (plan.transitionStyle) {
            TransitionStyle.DJ_FILTER -> "filter"
            TransitionStyle.DJ_BLEND -> "blend"
            TransitionStyle.MUFFLE -> "muffle"
            TransitionStyle.DROP_IN -> "drop"
            TransitionStyle.BEATLESS_BRIDGE -> "bridge"
            TransitionStyle.EQUAL_POWER -> "crossfade"
            TransitionStyle.GAPLESS -> "gapless"
            TransitionStyle.NATURAL -> "natural handoff"
            else -> "mix"
        }
    }

    fun appSettingsMarkerShown(): Boolean =
        runCatching { AppSettings.smartTransitionWindow.value != null }.getOrDefault(false)

    /**
     * INVARIANT (vc70 Seam A): a transition exit path may never reopen
     * filters while any deck is audibly above zero gain.
     */
    fun anyDeckAudible(): Boolean {
        val outVol = host.outgoing?.volume ?: 0f
        val inVol = host.incoming?.volume ?: 0f
        val tailVol = host.tailTarget?.volume ?: 0f
        return RetireGuard.audible(outVol) || RetireGuard.audible(inVol) || RetireGuard.audible(tailVol)
    }

    /** Opens filters only once both decks are silent (see [anyDeckAudible]). */
    fun openFiltersWhenSilent() {
        if (anyDeckAudible()) return
        host.filters.open()
        host.tracks.incomingMidDb(0f)
        host.tracks.outgoingMidDb(0f)
    }

    /**
     * vc77 retiree gate (Seam A companion): the retiring (spare) filter is
     * [filters.outgoing] regardless of which track [tracks] currently routes.
     */
    fun retireeAudible(): Boolean {
        val retiring = if (host.handedOff) host.outgoing else host.incoming
        val retiringVol = retiring?.volume ?: 0f
        val tailVol = host.tailTarget?.volume ?: 0f
        return RetireGuard.audible(retiringVol) || RetireGuard.audible(tailVol)
    }

    /** Opens only the spare filter, once its retiree is silent. */
    fun openSpareFiltersWhenSilent() {
        if (retireeAudible()) return
        host.filters.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        host.filters.outgoingMidDb(0f)
    }

    /**
     * Abnormal-exit survivor recovery. When a blend ends before progress 1,
     * opens only the survivor's track via [tracks] once its retiree is silent.
     */
    fun openSurvivorFiltersWhenRetireeSilent() {
        if (retireeAudible()) return
        if (host.handedOff) {
            host.tracks.incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
            host.tracks.incomingMidDb(0f)
        } else {
            host.tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
            host.tracks.outgoingMidDb(0f)
        }
    }
}
