package com.music.bitchord.playback.djmix

import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LocalBlendAdmission
import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.shouldDeclineToMix
import com.music.bitchord.playback.smart.DeckBudget
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import kotlinx.coroutines.launch
import kotlin.math.roundToLong


/** Pure admission, watchdog, and terminal decisions shared by coordinator execution. */
internal object CoordinatorDecisions {
    /** Called only after the genuine crown attempt. A displayed native countdown keeps its family. */
    fun nativePlanAfterCrown(
        crown: LoopMix.Plan?,
        classic: TransitionPlan,
        out: TrackAnalysis,
        inn: TrackAnalysis,
        committedNative: Boolean,
        lyricsOut: List<LyricCue> = emptyList(),
        lyricsIn: List<LyricCue> = emptyList(),
        bridge: () -> LoopMix.Plan?,
    ): LoopMix.Plan? = crown ?: if (!committedNative &&
        LocalBlendAdmission.isQualified(classic, out, inn, lyricsOut, lyricsIn)) null else bridge()

    fun adaptivePrimeLeadMs(nextStreamReady: Boolean, blendArmLeadMs: Long): Long =
        if (nextStreamReady) ADAPTIVE_PRIME_LEAD_MAX_MS
        else blendArmLeadMs.coerceIn(ADAPTIVE_PRIME_LEAD_MIN_MS, ADAPTIVE_PRIME_LEAD_MAX_MS)

    fun shouldYieldColdLoopForContentEnd(
        contentEndMs: Long,
        currentPositionMs: Long,
        outBpm: Double,
        earliestExitMs: Long?,
    ): Boolean {
        val barSec = if (outBpm.isFinite() && outBpm > 1.0) 240.0 / outBpm else 0.0
        val byBarsMs = (10.0 * barSec * 1000.0).roundToLong().coerceAtLeast(28_000L)
        val byContentEndMs = contentEndMs - currentPositionMs
        if (byContentEndMs in 0L..byBarsMs) return true
        if (earliestExitMs != null) {
            val byExitMs = earliestExitMs - currentPositionMs
            if (byExitMs in 0L..30_000L) return true
        }
        return false
    }

    /**
     * Exact handover target (djmix18, Bug&nbsp;2): the incoming deck's heard position is truth when
     * it is valid (a rendered block, at or past `plan.inn.loopStart - 0.05`), else the clock's
     * `handoverTrackSec`. With `incomingHoldSec &gt; 0` both land at
     * `inn.loopStart + (elementAt - incomingHoldSec)` — never `inn.loopStart + elementAt`.
     * Pure scalar; unit-tested in `Djmix18AppHandoffTest`.
     */
    fun handoverTargetSec(
        heardInSec: Double?,
        clockHandoverSec: Double,
        innLoopStartSec: Double,
    ): Double {
        val heard = heardInSec
        return if (heard != null && heard.isFinite() && heard >= innLoopStartSec - 0.05) heard
        else clockHandoverSec
    }

    fun fadingStallShouldBail(
        stalledMs: Long,
        progressAtLeastOne: Boolean,
        incomingReady: Boolean,
        outAboutToEof: Boolean,
        incomingBuffering: Boolean,
    ): Boolean =
        progressAtLeastOne && !incomingReady && !outAboutToEof && incomingBuffering &&
            stalledMs >= FADING_STALL_BAIL_MS

    /**
     * djmix24 Rung 0 consecutive-album guard (pure, scalars only). Same non-blank
     * album title/id AND trackNumber > 0 AND next == current + 1 -> GAPLESS.
     * Singles / blank album -> AutoMix. Exposed for Djmix24AppTest.
     */
    /**
     * djmix26 (Task 2A): classic scrubber marker & status-pill gate.
     * Does NOT wipe smartTransitionWindow on `!nextStreamReady` or on `QUALITY_HEAD`
     * (`REFINING`) when we already have an actionable non-fallback plan, a
     * committed countdown, or a loop fallback in progress.
     */
    fun isClassicMarkable(
        blocked: Boolean,
        markerVisible: Boolean,
        durationMs: Long,
        currentState: TrackAnalysisState,
        nextState: TrackAnalysisState,
        reason: String,
        alreadyCommittedClassic: Boolean,
        fallbackAdopted: Boolean,
    ): Boolean {
        if (blocked || !markerVisible || durationMs <= 0L) return false
        if (alreadyCommittedClassic || fallbackAdopted) return true
        val nextReady = nextState == TrackAnalysisState.ANALYSED || nextState == TrackAnalysisState.REFINING
        if (!nextReady) return false
        if (currentState == TrackAnalysisState.ANALYSED) return true
        return currentState == TrackAnalysisState.REFINING && !reason.contains("fallback")
    }

    /**
     * djmix26 (Task 2A): skip heavy stem separation (`separateTransitionWindow`)
     * when within ~2.0–2.5 s of the loop splice so optional separation never
     * stalls `startLoopBuild` past takeover.
     */
    fun shouldSkipStemNearSplice(
        remainingToSpliceMs: Long,
        deadlineMs: Long,
        nowMs: Long,
    ): Boolean {
        if (remainingToSpliceMs <= 2_500L) return true
        return deadlineMs != DeckBudget.NO_DEADLINE_MS && deadlineMs > 0L && (deadlineMs - nowMs) <= 2_000L
    }

    /**
     * vc74 (djmix32): true when loop-deck stem separation must be skipped at build
     * launch. The analysis that the planner needs is worth more than an optional
     * stem split on a contended phone; the deck falls back to the dry region
     * exactly as it already does when separation returns null.
     *
     * vc77: a primed build (`armWhenReady` false) has 35-45 s of lead and no
     * immediate takeover, so contention alone must not permanently null the stem
     * functions for that build — the per-window [shouldSkipStemNearSplice] check
     * live at separation time still enforces the splice/deadline protection. The
     * busy gate applies only to an arm-time build (`armWhenReady` true), which
     * must finish before takeover.
     */
    fun shouldSkipStemAtLaunch(
        remainingToSpliceMs: Long,
        deadlineMs: Long,
        nowMs: Long,
        analysisBusy: Boolean,
        armWhenReady: Boolean,
    ): Boolean = shouldSkipStemNearSplice(remainingToSpliceMs, deadlineMs, nowMs) || (analysisBusy && armWhenReady)

    /** One terminal classification shared by normal classic and native completion paths. */
    fun automixTerminalOutcome(
        isTerminal: Boolean,
        executed: Boolean,
        handedOff: Boolean,
        reason: String,
    ): String = when {
        !isTerminal -> "unknown"
        executed && handedOff && reason in setOf(
            "finish",
            "loop-complete",
            "planned",
            "planned-fade-done",
            "loop-handover-drift-recovery",
            "loop-handover-unstarted-recovery",
        ) -> "completed"
        reason.contains("scrub") || reason.contains("seek") || reason == "interrupted" -> "cancelled"
        else -> "unknown"
    }

    /**
     * D3b: phrase planner (M6) must never hijack a declined pair in [nativeBridge];
     * declined pairs flow into [LoopMix.bridge] (TransitionAlternatives / Echo).
     */
    fun shouldRunPhrasePlannerForBridge(
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
    ): Boolean = !shouldDeclineToMix(currentAnalysis, nextAnalysis)
}
