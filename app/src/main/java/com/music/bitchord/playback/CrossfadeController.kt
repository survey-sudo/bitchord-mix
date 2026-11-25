package com.music.bitchord.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel as CoreDjLevel
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.contracts.MixUiPhase
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixMachineConfig
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.machine.MixStateKind
import com.music.bitchord.djmix.core.machine.PairKey
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.QueueEntryIdentity
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.djmix.DeckTiming
import com.music.bitchord.playback.djmix.DeckWave
import com.music.bitchord.playback.djmix.EaseTempoEngine
import com.music.bitchord.playback.djmix.EaseTempoHost
import com.music.bitchord.playback.djmix.FadeRideCurves
import com.music.bitchord.playback.djmix.IdleStallAction
import com.music.bitchord.playback.djmix.LoopArmDriver
import com.music.bitchord.playback.djmix.LoopDriverHost
import com.music.bitchord.playback.djmix.LoopDriverState
import com.music.bitchord.playback.djmix.LoopFinishAction
import com.music.bitchord.playback.djmix.LoopMixDriver
import com.music.bitchord.playback.djmix.LyricSpans
import com.music.bitchord.playback.djmix.MixDeckAdapter
import com.music.bitchord.playback.djmix.MixEventHost
import com.music.bitchord.playback.djmix.MixEventRouter
import com.music.bitchord.playback.djmix.MixExecutor
import com.music.bitchord.playback.djmix.MixGeometry
import com.music.bitchord.playback.djmix.MixStatusDescriber
import com.music.bitchord.playback.djmix.MixStatusHost
import com.music.bitchord.playback.djmix.MixViewHost
import com.music.bitchord.playback.djmix.MixViewPublisher
import com.music.bitchord.playback.djmix.NativeBinding
import com.music.bitchord.playback.djmix.Opportunity
import com.music.bitchord.playback.djmix.PhraseCandidate
import com.music.bitchord.playback.djmix.PositionClock
import com.music.bitchord.playback.djmix.PreparedPair
import com.music.bitchord.playback.djmix.RideSpec
import com.music.bitchord.playback.djmix.canAdoptSessionPlayer
import com.music.bitchord.playback.djmix.freezeDedupKey
import com.music.bitchord.playback.djmix.idleStallDecision
import com.music.bitchord.playback.djmix.loopFinishAction
import com.music.bitchord.playback.djmix.shouldRestorePlayAfterFocusSwap
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LocalBlendAdmission
import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.shouldDeclineToMix
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhraseMaps
import com.music.bitchord.playback.smart.SongMaps
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.AutomixStatus
import com.music.bitchord.playback.smart.DeckBudget
import com.music.bitchord.playback.smart.MixGain
import com.music.bitchord.playback.smart.MixInspectorFeed
import com.music.bitchord.playback.smart.MixInspectorKey
import com.music.bitchord.playback.smart.MixInspectorPhase
import com.music.bitchord.playback.smart.MixInspectorRemixAction
import com.music.bitchord.playback.smart.MixInspectorState
import com.music.bitchord.playback.smart.RetireGuard
import com.music.bitchord.playback.smart.SeekAction
import com.music.bitchord.playback.smart.MixWaveform
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.localBlendFitsDeckSpeedCap
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.BUILDUP_RISE
import com.music.bitchord.playback.smart.deckPlayedRate
import com.music.bitchord.playback.smart.deckSpeed
import com.music.bitchord.playback.smart.camelotCode
import com.music.bitchord.playback.smart.camelotSteps
import com.music.bitchord.playback.smart.regionalCamelotSteps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import java.util.Locale

/**
 * A real crossfade: two tracks audible at once, the outgoing one falling as the
 * incoming one rises, the way Spotify and Apple Music do it.
 *
 * ## Why there are two players
 *
 * One ExoPlayer renders one queue item at a time, so at a track boundary there
 * is exactly one source and the gain it can be given is either 1 (no fade) or 0
 * (silence). The previous version of this class was a single-player volume
 * ramp, and that is precisely why it never sounded like a crossfade: it dipped
 * to silence at the join and climbed back out, leaving a hole where the blend
 * should be. Overlap needs a second decoder. There is no way around it.
 *
 * ## Which player plays what
 *
 * Two peers, not a player and a helper. Both are full ExoPlayers built the same
 * way and both can own the queue; at any instant one of them *is* the session
 * (it backs the MediaSession, holds audio focus and carries the notification)
 * and the other is idle. They swap roles at every transition.
 *
 *  - **[active]** — whichever player the session currently points at. The rest
 *    of the app only ever sees this one.
 *  - **[standby]** — the idle player. Between transitions it holds nothing. To
 *    arm a transition it is loaded with *the queue, positioned on the incoming
 *    track* at the plan's cue point, and started silently.
 *
 * The crucial word is **incoming**. An earlier version of this class put the
 * *outgoing* track on the second player: the session player jumped ahead to the
 * next song and the second player carried the old song's tail. That works, but
 * it forces a moment where both players render *the same audio*, and two
 * ExoPlayers cannot be started sample-accurately against each other. Whatever
 * they were misaligned by — measured on real transitions at 9 to 41ms — was
 * heard as the last instant of the outgoing track playing twice, at the head of
 * every single crossfade. No amount of tuning removes that; the duplication is
 * structural.
 *
 * Loading the *incoming* track on the standby removes it outright. The two
 * players never hold the same audio, so there is nothing to align, nothing to
 * hand over, and no seam to hide. The incoming track is simply already playing,
 * from exactly the right position, when its fader starts to move.
 *
 * ## The handoff
 *
 * Because both players own the queue, finishing a transition is a **role swap**
 * rather than a seek: nothing is re-buffered, nothing is re-sought, and no audio
 * is rendered twice. [onHandoff] is what performs it — the service moves the
 * MediaSession, audio focus, its listeners and its bookkeeping onto the incoming
 * player.
 *
 * It fires halfway through the blend — [HANDOFF_AT] — where the equal-power
 * curve makes the two tracks equally loud and the incoming one takes over. The
 * queue index, the metadata, the notification and the UI flip to the incoming
 * song there: not at its first, barely audible note, and not trailing the song
 * on its way out. Before it the incoming player is a silent-then-rising
 * shadow of the session; after it [outgoing] is the idle player, still audible,
 * being faded out.
 *
 * ## Curve
 *
 * `sin`/`cos` rather than the old `sqrt`: `sin²+cos²=1` exactly, so two tracks
 * fading past each other hold constant *power* the whole way through and the
 * transition has no dip in the middle. That is the standard crossfade law, and
 * it is what makes a long crossfade sound like a blend instead of a dip.
 */
@UnstableApi
/**
 * Hard deck-speed discipline (djmix18, HANDOVER_MASTER \u00a76 Bug 1 &amp; Bug 2): the beatmatch
 * rate a deck may play at. Returns `1.0f` when [raw] is not a beatmatch (the caller stacked
 * a listener speed on a non-mix rate) or not finite, and strictly clamps to
 * `[0.92f, 1.08f]` (\u00b18\u2009max) — never `1.94\u00d7` or `999\u00d7`, under any circumstance.
 * The listener's own speed is stacked on top of this in [deckSpeed]: clamping the rate here
 * keeps user speed (0.25\u20133\u00d7) working while the mix component can never run away.
 * Pure scalar; unit-tested in `Djmix18AppHandoffTest`.
 */
internal fun safeDeckSpeed(raw: Float, beatmatched: Boolean = true): Float =
    EaseTempoEngine.safeDeckSpeed(raw, beatmatched)

/**
 * [safeDeckSpeed] applied to a mix [rate] (1.0 = no stretch) before [deckSpeed] stacks it with
 * the listener speed. [rate] may arrive as `NaN` (unknown BPM ratio): that is `1.0f`, never
 * a stretch. Every `setPlaybackSpeed` that carries a beatmatch rate goes through here so the
 * `[0.92f, 1.08f]` ceiling applies to ordinary unqualified requests. A locally
 * admitted classic blend uses its separately checked physical rate in incomingBaseSpeed.
 */
internal fun safeRateForDeck(rate: Double, beatmatched: Boolean = true): Double =
    EaseTempoEngine.safeRateForDeck(rate, beatmatched)

/**
 * Final `ExoPlayer` speed for a mix [rate]: listener speed stacked on [safeRateForDeck].
 * Use this for every beatmatch `setPlaybackSpeed`. Non-beatmatched decks play at the
 * listener speed (`rate` is forced to `1.0`).
 */
internal fun deckSpeedSafe(user: Float, rate: Double, beatmatched: Boolean = true): Float =
    EaseTempoEngine.deckSpeedSafe(user, rate, beatmatched)

/**
 * Applies a locally admitted physical stretch before the existing composite
 * player-speed cap. Invalid rates cannot bypass the ordinary deck-speed guard.
 */
internal fun deckSpeedForAdmittedBlend(user: Float, admittedRate: Double): Float =
    EaseTempoEngine.deckSpeedForAdmittedBlend(user, admittedRate)

/** Called only after the genuine crown attempt. A displayed native countdown keeps its family. */
internal fun nativePlanAfterCrown(
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

/**
 * Cold-loop yield headroom (djmix18, Bug&nbsp;4): once both tracks are analysed, a `LoopMix.plan`
 * that returned `null` is deterministically ineligible for that pair. Waiting for it until
 * `10s\u201314s` before `contentEnd` starves an 8\u201316 bar (`19\u201338s`) beatmix. Yield to
 * `PhrasePlanner` → `LoopMix.bridge` at the earlier of `30s` before the first phrase/outro
 * exit or `max(28s, 10\u00d7barSec)` before `contentEnd`, so the phrase mix has a full 8 bars to
 * arm and fire. Pure scalar; unit-tested in `Djmix18AppHandoffTest`.
 *
 * @param contentEndMs outgoing `contentEndTime` in ms, or `durationMs` when unknown.
 * @param currentPositionMs playhead in ms.
 * @param outBpm outgoing BPM for `10\u00d7barSec` (`barSec = 240/bpm`); `<= 0` falls back to `28s`.
 * @param earliestExitMs earliest phrase/outro exit `releaseSec` in ms, or `null` when unknown.
 * Pure scalar; unit-tested in `Djmix18AppHandoffTest`.
 */
/**
 * Adaptive native prime lead (Task 2B): 45 s when the incoming stream prefix
 * is ready, else 35-45 s clamped off [blendArmLeadMs] so primeLoopDecks arms
 * early enough to win single-clock capture before classic fallback thresholds.
 */
internal const val ADAPTIVE_PRIME_LEAD_MAX_MS = 45_000L
internal const val ADAPTIVE_PRIME_LEAD_MIN_MS = 35_000L

internal fun adaptivePrimeLeadMs(nextStreamReady: Boolean, blendArmLeadMs: Long): Long =
    if (nextStreamReady) ADAPTIVE_PRIME_LEAD_MAX_MS
    else blendArmLeadMs.coerceIn(ADAPTIVE_PRIME_LEAD_MIN_MS, ADAPTIVE_PRIME_LEAD_MAX_MS)

internal fun shouldYieldColdLoopForContentEnd(
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
internal fun handoverTargetSec(
    heardInSec: Double?,
    clockHandoverSec: Double,
    innLoopStartSec: Double,
): Double {
    val heard = heardInSec
    return if (heard != null && heard.isFinite() && heard >= innLoopStartSec - 0.05) heard
    else clockHandoverSec
}

internal typealias IdleStallAction = com.music.bitchord.playback.djmix.IdleStallAction

internal fun idleStallDecision(stalledMs: Long, outExhausted: Boolean, alreadyPrepared: Boolean): IdleStallAction =
    com.music.bitchord.playback.djmix.idleStallDecision(stalledMs, outExhausted, alreadyPrepared)

/**
 * vc77 FADING-stall bound: [FADE_STEP_MS] ticks in [CrossfadeController.driveFade]
 * can hold `progress == 1` forever when the incoming player sits in
 * `STATE_BUFFERING` (elapsed frozen, so `incomingAt < 0.999` never reaches
 * `incomingReady`) and the outgoing file still has more than
 * [HANDOFF_GUARD_MS] left (so `outAboutToEof` never fires either). The outgoing
 * fader is already 0 at progress 1, so waiting changes nothing audible but
 * also never unblocks. At [FADING_STALL_BAIL_MS] of continuous buffering the
 * caller force-bails through the existing abnormal-exit path (survivor kept,
 * survivor filters reopened only once the retiree is silent — Seam A).
 * A user pause parks in `STATE_READY`, never `STATE_BUFFERING`, so an
 * intentional hold can never trip this. Pure scalar; unit-tested in
 * `FadingStallWatchdogTest`.
 */
internal const val FADING_STALL_BAIL_MS = 2_500L

internal fun fadingStallShouldBail(
    stalledMs: Long,
    progressAtLeastOne: Boolean,
    incomingReady: Boolean,
    outAboutToEof: Boolean,
    incomingBuffering: Boolean,
): Boolean =
    progressAtLeastOne && !incomingReady && !outAboutToEof && incomingBuffering &&
        stalledMs >= FADING_STALL_BAIL_MS

internal fun freezeDedupKey(phaseName: String, outgoingId: String?, incomingId: String?): String =
    com.music.bitchord.playback.djmix.freezeDedupKey(phaseName, outgoingId, incomingId)

/**
 * Whether a session player paused by an audio-focus swap may be auto-resumed
 * (djmix22, vc64). True only when the player was audibly playing before the
 * handoff, the pause was not the listener's (a user pause — reason USER_REQUEST —
 * is sticky and never auto-resumed), and the adopting player is holding (or has
 * atomically acquired) focus. A user-paused player is NEVER auto-resumed, and a
 * player that was not playing before the swap is never started by it.
 */
internal fun shouldRestorePlayAfterFocusSwap(wasPlaying: Boolean, userPaused: Boolean, focusHeld: Boolean): Boolean =
    com.music.bitchord.playback.djmix.shouldRestorePlayAfterFocusSwap(wasPlaying, userPaused, focusHeld)

/** What [CrossfadeController.finish] must do with an audible spare (djmix22). */
internal typealias LoopFinishAction = com.music.bitchord.playback.djmix.LoopFinishAction

/**
 * LoopMix completion routing (djmix22, vc64): a `LOOP_RUNNING`/`LOOP_ARMING`
 * mix that has taken over AND handed off must complete via [CrossfadeController]'s
 * `finishLoop()` (the renderer exit-gate path), NOT via a bare `finish()` that
 * treats the audible spare at volume 1.0 as an emergency-retire-guard.
 */
internal fun loopFinishAction(
    phaseName: String,
    tookOver: Boolean,
    handedOff: Boolean,
    exitGateViolations: Int,
    audibleSpare: Boolean,
): LoopFinishAction = com.music.bitchord.playback.djmix.loopFinishAction(
    phaseName, tookOver, handedOff, exitGateViolations, audibleSpare,
)

/**
 * Session-adoption guard (djmix23, vc65).
 */
internal fun canAdoptSessionPlayer(loopIncomingStarted: Boolean): Boolean =
    com.music.bitchord.playback.djmix.canAdoptSessionPlayer(loopIncomingStarted)

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
internal fun isClassicMarkable(
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
internal fun shouldSkipStemNearSplice(
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
internal fun shouldSkipStemAtLaunch(
    remainingToSpliceMs: Long,
    deadlineMs: Long,
    nowMs: Long,
    analysisBusy: Boolean,
    armWhenReady: Boolean,
): Boolean = shouldSkipStemNearSplice(remainingToSpliceMs, deadlineMs, nowMs) || (analysisBusy && armWhenReady)

/** One terminal classification shared by normal classic and native completion paths. */
internal fun automixTerminalOutcome(
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
internal fun shouldRunPhrasePlannerForBridge(
    currentAnalysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
): Boolean = !shouldDeclineToMix(currentAnalysis, nextAnalysis)

/**
 * A compiled phrase candidate that has NOT been committed yet.
 *
 * [CrossfadeController.compilePhrase] builds this without touching the
 * committed phrase detail, the log dedup key, telemetry or logcat: the
 * caller runs `commitPhrase` only after the candidate's plan has been
 * accepted, alongside the accepted `loopScript`. A too-near / geometry
 * rejection (nativeBridge "bridge too near", the shift retry) therefore
 * leaves the previous committed detail, dedup key and log output
 * untouched, so the UI never shows a rejected candidate's line and a
 * later acceptance of the same candidate still logs it.
 */
internal typealias PhraseCandidate = com.music.bitchord.playback.djmix.PhraseCandidate

internal fun isConsecutiveAlbumTrack(
    albumA: String, albumIdA: String, trackNumA: Int,
    albumB: String, albumIdB: String, trackNumB: Int,
): Boolean = TransitionMoves.isConsecutiveAlbumPair(albumA, albumIdA, trackNumA, albumB, albumIdB, trackNumB)

class CrossfadeController(
    private val scope: CoroutineScope,
    /** The player backing the session right now. Moves at every [onHandoff]. */
    private val active: () -> ExoPlayer,
    /** The idle player, which the next transition will load the incoming track onto. */
    private val standby: () -> ExoPlayer,
    /**
     * Moves the session onto the player that has just started the incoming
     * track: the MediaSession's player, audio focus, the service's listeners and
     * everything it books against a track change.
     *
     * Called once per transition, halfway through the blend. After it returns, [active] must answer `incoming` and [standby]
     * must answer `outgoing` — this class re-reads neither during a transition,
     * but everything else in the service does.
     */
    private val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
    /**
     * Stored Automix analysis for a media item, or an empty [TrackAnalysis]
     * when there is none yet. This is the seam Phase 1's DSP analyzer plugs
     * into: until analysis finishes, a track reads as "no evidence", which
     * [planTransition] answers with the same fixed-length crossfade this
     * class always ran before Automix existed.
     */
    private val analysisFor: (MediaItem) -> TrackAnalysis = { TrackAnalysis() },
    /**
     * Queues background analysis for a media item that will soon need it.
     * Cheap to call on every tick: a track already analysed, already in
     * flight, or not yet fully cached is a no-op.
     *
     * Takes the item's duration in milliseconds, or 0 when Media3 hasn't loaded
     * that far ahead yet. The analyzer needs it to tell one rendition of a
     * recording from a differently-cut one before reusing an analysis across
     * them, and this class is the only place that already knows it.
     */
    private val requestAnalysis: (MediaItem, Long) -> Unit = { _, _ -> },
    /**
     * The low-pass and high-pass riding each side of a transition. This is what
     * makes a plan's
     * [com.music.bitchord.playback.smart.TransitionPlan.transitionStyle] audible
     * rather than advisory: see [rideFilters]. Defaults to
     * [TransitionFilters.None], which renders every style as the plain
     * equal-power blend this class ran before.
     */
    private val filters: TransitionFilters = TransitionFilters.None,
    /**
     * Whether a decode and inference for a media item is running right now.
     * Only feeds the stats line — nothing about a transition waits on it.
     */
    private val analysisRunningFor: (MediaItem) -> Boolean = { false },
    /**
     * Asks analysis to stay off the cores for the next ~10 s. A handover is
     * when the incoming decoder and the mix renderer need them.
     */
    private val yieldAnalysis: () -> Unit = {},
    /**
     * The track about to be loaded onto the standby player, announced at the
     * moment [begin] arms rather than at the handoff.
     *
     * The timing is the entire point. Anything keyed to the *incoming* track
     * that lives on the standby player's audio path has to be set before that
     * player renders a frame, and the handoff happens after the incoming track
     * is already audible. Loudness normalization is the case that motivated
     * it: the standby's processor has to be told which song it is about to
     * level, or it spends the first half of the blend applying the outgoing
     * track's gain to the incoming one.
     *
     * Also given the media id of whatever follows the incoming track on the
     * standby's queue, so that player's per-track state can follow it across
     * a later gapless boundary without waiting on the service.
     */
    private val onArmIncoming: (MediaItem, String?) -> Unit = { _, _ -> },
    /** The armed incoming item, only when AutoMix owns the overlap. */
    private val onArmAutoMixIncoming: (MediaItem) -> Unit = {},
    /**
     * A smooth headroom trim for both players while two tracks overlap:
     * `1 / sqrt(incomingGain + outgoingGain)`, 1 otherwise.
     *
     * Equal-power gains sum to more than 1 — up to 1.41 at the midpoint — so
     * two loud masters peaking together pass full scale in the platform mixer
     * and are hard-clipped into a crackle. The trim holds that to 1.19 at a
     * cost of at most 1.5 dB mid-blend. A gain rather than a limiter on
     * purpose — see [LoudnessProcessor].
     */
    private val onBlendHeadroom: (Float) -> Unit = {},
    /**
     * True while the service is mid-swap between two versions/cuts of the
     * current track. That swap fades across the same active/standby pair
     * this controller does, so the two must never run at once — arming a
     * transition here would tear the standby player away from the version
     * swap that already owns it, and the other direction is guarded
     * symmetrically where the swap starts. Checked once at the top of
     * [considerAutoTransition] rather than the pieces inside it, since
     * [Phase.IDLE] is the only phase that can still be preempted for free.
     */
    private val versionSwapActive: () -> Boolean = { false },
    /** Invoked after [finish] has completed cleanup and returned to IDLE. */
    private val onSettled: () -> Unit = {},
    /**
     * Whether the incoming track's audio is ready to blend into, as one more
     * condition on showing the Automix window on the seek bar. Only marks the
     * window; arming the transition never waits on it.
     */
    private val nextStreamReady: (MediaItem) -> Boolean = { true },
    /**
     * A three-band waveform of a stretch of a media item — its duration in ms,
     * then the span in seconds and how many bins — for the mix popup, or null
     * when its audio cannot be read. Blocking; called off the main thread.
     */
    private val mixWaveform: (MediaItem, Long, Double, Double, Int) -> MixWaveform.Bands? = { _, _, _, _, _ -> null },
    /** A player's [LoopMixPort], read through its sink's role; null where there is none. */
    private val loopPortOf: (ExoPlayer) -> LoopMixPort? = { null },
    /** Stereo audio of a track for a loop mix deck: (item, duration ms, from s, to s, rate). Blocking. */
    private val loopRegion: (MediaItem, Long, Double, Double, Int) -> LoopRegion? = { _, _, _, _, _ -> null },
    /** True when the region is already decoded in the RAM PCM cache (trackId, sampleRate, from, to). */
    private val loopRegionCached: (String, Int, Double, Double) -> Boolean = { _, _, _, _ -> false },
    /** A track's synced lyrics, for vouching a loop instrumental; null when it has none. */
    private val syncedLines: suspend (MediaItem, Long) -> List<LyricLine>? = { _, _ -> null },
    /** The loudness-normalisation gain a track plays at, so the incoming deck is levelled like its player. */
    private val loudnessGain: (String) -> Float = { 1f },
    /** True on-device vocal/instrumental stem separation for the acapella rung. */
    private val separateTransitionWindow: ((left: FloatArray, right: FloatArray, sampleRate: Int, keepVocals: Boolean) -> Pair<FloatArray, FloatArray>?)? = null,
    /** True on-device dual-stem (vocals+instrumental) separation for loop mixes (vc73). */
    private val separateTransitionBothWindows: ((left: FloatArray, right: FloatArray, sampleRate: Int) -> Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>?)? = null,
    /** Exact dual-stem (voc, inst) pair for loop vocal-duck (vc77); same service as above. */
    private val separateTransitionPair: ((left: FloatArray, right: FloatArray, sampleRate: Int) -> Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>?)? = null,
    // FORK-HOOK(prepare): background pair preparer (analysis + planning + overlap stems + artwork prefetch).
    private val pairPreparer: com.music.bitchord.playback.djmix.PairPreparer? = null,
    /** True while a whole-track analysis pass is in flight (vc74 gate). */
    private val wholeTrackBusy: () -> Boolean = { false },
    /** The session's playhead moved on purpose during a loop mix: a loop wrap (true) or a re-anchor (false). */
    private val onMixPositionJump: (wrapped: Boolean) -> Unit = {},
    /** Monotonic wall clock (ms) for arming/fade deadlines; production uses [SystemClock.elapsedRealtime]. */
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    /**
     * Emits a trackId whenever that track's analysis changes: cache restore,
     * grid-ready intermediate, head publish, whole-track completion, or terminal
     * failure. Collected in [scope] so a background pass landing mid-song
     * re-plans the pending pair instead of leaving an analysis-fallback NATURAL
     * handoff latched until the queue changes. An inert flow is the default for
     * hosts that do not wire the analyser.
     */
    private val analysisUpdates: SharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 1),
) {

    private enum class Phase {
        /** Nothing in flight; watching for the next transition. */
        IDLE,

        /**
         * The standby player is loading the incoming track and buffering to its
         * cue point. Silent, and nothing has been committed: abandoning here
         * costs only the standby's decoder.
         */
        ARMING,

        /** Incoming track rising on one player, outgoing falling on the other. */
        FADING,

        /**
         * The blend is done; the outgoing player is dissolving to silence
         * over the graceful-exit tail before it is retired. Exists so that
         * [finish] can never retire an audible outgoing player (djmix11).
         */
        TAIL,

        /**
         * The sleep timer is due at this track's end: the outgoing track is
         * being faded to silence on its own, over the same span an ordinary
         * transition would have used, but nothing is being loaded to follow
         * it. See [beginSleepFadeOut].
         */
        SLEEP_FADE,

        /** Something interrupted the fade; the outgoing track is being ramped away. */
        BAILING,

        /** A loop mix's decks are being built, or are built and waiting for the outgoing song's loop start. */
        LOOP_ARMING,

        /** The loop mix is playing out of the outgoing sink; the incoming player takes over at its end. */
        LOOP_RUNNING,
    }

    private var phase = Phase.IDLE

    /**
     * The player the session was on when this transition began — the one whose
     * track is being left. Held explicitly rather than re-read through
     * [standby], because [onHandoff] moves it out from under that name halfway
     * through the fade and the ramp has to keep driving the same two players it
     * started with.
     */
    private var outgoing: ExoPlayer? = null

    /** The player carrying the track arriving. Becomes the session at [onHandoff]. */
    private var incoming: ExoPlayer? = null

    /**
     * Whether [onHandoff] has run for the transition in flight, which is what
     * decides who owns what if it has to be unwound: before it, [outgoing] is
     * the session and [incoming] is a silent scratch player; after it, they have
     * traded places.
     */
    private var handedOff = false

    /**
     * How many items the queue held when the standby was loaded with a copy of
     * it. AutoPlay appending mid-transition is explicitly allowed, so the
     * difference is reconciled onto the standby before the swap rather than
     * being allowed to lose the appended tracks — see [reconcileQueue].
     */
    private var queuedItemCount = 0

    /** Media id of the track this blend or prime is bringing in. Null when nothing is planned. */
    private var plannedNextId: String? = null

    /**
     * The outgoing player, while it is held at the end of its own track — see
     * [holdAtEnd]. Null when nothing is held.
     */
    private var heldAtEnd: ExoPlayer? = null

    /**
     * Set by an idle tick that found a transition coming for the playing
     * track — see [holdAhead]. A tick that leaves it false lets go of any hold.
     */
    private var holdWanted = false

    /**
     * The outgoing track's queue index when the transition armed. A player
     * that could not be held moves off it at its end, onto the very song
     * fading in — see [ranOn].
     */
    private var outgoingItemIndex = C.INDEX_UNSET

    /** Which player this class's own listener is currently attached to. */
    private var listeningTo: ExoPlayer? = null
    private var tickerJob: Job? = null
    private var lastTickState: Map<String, Any?>? = null
    private var lastTickStateAtMs = 0L

    /** Length of the transition in flight, in ms. Fixed when it begins. */
    private var fadeMs = 0L

    /**
     * Where the fade window ends, in the session player's position ms.
     * Standard mode sets this to the track's own duration, which is what
     * [driveArming] always compared against before Automix existed; a
     * Automix plan can set it earlier, at an analyzed mix-out anchor, so
     * [driveArming] watches this field rather than re-deriving the fade point
     * from [ExoPlayer.getDuration] on every tick.
     */
    private var fadeEndMs = 0L

    /**
     * Which setting armed the fade in flight, so [driveFade] knows which one
     * being switched off mid-blend means "stop now" rather than misreading the
     * other mode's control as the fade having been turned off. Automix
     * doesn't need [AppSettings.crossfadeSeconds] to be above zero at all —
     * see [considerSmartTransition] — so treating that as still-zero as a
     * reason to cut a Automix short would end every one of them on its
     * first tick.
     */
    private var smartFadeActive = false

    /**
     * Where the incoming track is cued when the lap hands the queue over, in
     * its own timeline ms. Standard fades always leave this at 0 — a plain
     * track change starts from the top — and only a Automix plan sets it
     * to an analyzed mix-in point instead.
     */
    private var incomingCueTimeMs: Long = 0L

    /**
     * The tempo-stretch ratio applied to the incoming track for the
     * transition, stacked on top of whatever [AppSettings.playbackSpeed] the
     * listener already has set — 1.0 is a no-op. This is what actually
     * beatmatches a BEATMATCHED-tier plan: without it, the two tracks blend
     * at their own unrelated tempi and the result is a crossfade with
     * smarter timing, not a beatmatch.
     */
    private var incomingPlaybackRate: Double = 1.0
    /** This exact classic plan passed local admission; its physical rate is not the global taste cap. */
    private var localBlendInFlight = false

    /**
     * Advanced Automix: the speed-up the outgoing track is brought up to before
     * the blend, when it is the slower of the pair — see [rampOutgoing]. 1.0
     * otherwise, and always 1.0 classically.
     */
    private var outgoingPlaybackRate: Double = 1.0

    /** How much faster the incoming timeline runs than the outgoing one through the blend. */
    private val mediaRatio: Double get() = incomingPlaybackRate / outgoingPlaybackRate

    /**
     * The style-specific half of the plan in flight — everything [rideFilters]
     * needs and nothing else. Fixed when the transition begins, because a plan
     * is recomputed every tick and a bass swap that moved to a different beat
     * halfway through the blend would be heard as the low end flapping.
     */
    private var render = Render()

    /** The Automix plan the transition in flight was armed from, for the mix popup; null for a standard fade. */
    private var planInFlight: TransitionPlan? = null

    /** The last reason logged for the seek bar's Automix marker showing or not, so it logs on change. */
    private var lastMarkerVerdict = ""


    /**
     * The style fields of a [com.music.bitchord.playback.smart.TransitionPlan],
     * separated out so the standard (non-Smart) path can pass defaults without
     * constructing a plan it never made.
     *
     * Every fraction the ride reads is resolved here, once per transition,
     * rather than on each 30ms fade tick. With [advanced] off they come out as
     * the classic constants; with it on and a trusted grid, each is moved onto
     * the nearest beat or bar of the overlap, so the bass swap, the filters
     * opening and the echo all land where a DJ would put them.
     */
    private class Render(
        style: TransitionStyle = TransitionStyle.EQUAL_POWER,
        bassSwap: Boolean = false,
        bassSwapFraction: Double = 0.7,
        filterSweep: Double = 0.0,
        filterVariant: FilterTransitionVariant = FilterTransitionVariant.SWEEP,
        vocalOverlap: Double = 0.0,
        /** Advanced Automix: DJ fader curves, moves on the beat, phase lock, tempo ease-back. */
        advanced: Boolean = false,
        /** The overlap's length in outgoing beats; 0 when the grid is not trusted. */
        beats: Double = 0.0,
        lockPhase: Boolean = false,
        /** The plan's echo time; 0 for no echo out. */
        echoSeconds: Double = 0.0,
        djEffect: com.music.bitchord.playback.smart.DjEffect = com.music.bitchord.playback.smart.DjEffect.NONE,
        vocalHandoff: Boolean = false,
        beatSecondsF: Float = 0f,
        buildup: Boolean = false,
        /** Muffle (vc69 P0) lead beats; 0 for other styles. */
        muffleLeadBeats: Int = 0,
        /** Return-leg beats: trailing span the filter leaves to full-band. 0 for styles that never park. */
        exitBeats: Int = 0,
        /** Vocal-depth (vc69 P1) depth curve over the overlap; empty means full spend. */
        vocalDepthCurve: List<Double> = emptyList(),
    ) {
        val rideSpec = com.music.bitchord.playback.djmix.RideSpec(
            style = style,
            bassSwap = bassSwap,
            bassSwapFraction = bassSwapFraction,
            filterSweep = filterSweep,
            filterVariant = filterVariant,
            vocalOverlap = vocalOverlap,
            advanced = advanced,
            beats = beats,
            lockPhase = lockPhase,
            echoSeconds = echoSeconds,
            djEffect = djEffect,
            vocalHandoff = vocalHandoff,
            beatSecondsF = beatSecondsF,
            buildup = buildup,
            muffleLeadBeats = muffleLeadBeats,
            exitBeats = exitBeats,
            vocalDepthCurve = vocalDepthCurve,
        )
        val style: TransitionStyle get() = rideSpec.style
        val bassSwap: Boolean get() = rideSpec.bassSwap
        val filterSweep: Double get() = rideSpec.filterSweep
        val filterVariant: FilterTransitionVariant get() = rideSpec.filterVariant
        val vocalOverlap: Double get() = rideSpec.vocalOverlap
        val advanced: Boolean get() = rideSpec.advanced
        val beats: Double get() = rideSpec.beats
        val djEffect: com.music.bitchord.playback.smart.DjEffect get() = rideSpec.djEffect
        val vocalHandoff: Boolean get() = rideSpec.vocalHandoff
        val beatSecondsF: Float get() = rideSpec.beatSecondsF
        val buildup: Boolean get() = rideSpec.buildup
        val muffleLeadBeats: Int get() = rideSpec.muffleLeadBeats
        val exitBeats: Int get() = rideSpec.exitBeats

        /** Hold the two beats in phase by nudging the incoming track's speed. */
        val phaseLock = advanced && lockPhase

        val echo: Boolean get() = rideSpec.echo
        val echoSecondsF: Float get() = rideSpec.echoSecondsF
        val echoTailMs: Float get() = rideSpec.echoTailMs
        val swapAt: Double get() = rideSpec.swapAt
        val filterSwapAtF: Float get() = rideSpec.filterSwapAtF
        val echoAt: Double get() = rideSpec.echoAt
        val swapAtF: Float get() = rideSpec.swapAtF
        val handoffAt: Float get() = rideSpec.handoffAt
    }

    private var fadeStartedAt = 0L
    private var fadeOutOriginMs = 0L
    private var fadeOutBudgetMs = 1L

    /**
     * How early a smart mix arms the next deck. Starts at [PrepLead.BASE_MS]
     * and grows when the previous preparation ran long.
     */
    private var blendArmLeadMs = com.music.bitchord.playback.smart.PrepLead.BASE_MS
    private var incomingPreparedAt = 0L
    private var prepListener: Player.Listener? = null

    private fun notePrep(player: ExoPlayer) {
        prepListener?.let { previous ->
            runCatching { player.removeListener(previous) }
        }
        incomingPreparedAt = nowMs()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_READY || incomingPreparedAt == 0L) return
                val prep = nowMs() - incomingPreparedAt
                incomingPreparedAt = 0L
                blendArmLeadMs = com.music.bitchord.playback.smart.PrepLead.next(blendArmLeadMs, prep)
                player.removeListener(this)
                if (prepListener === this) prepListener = null
            }
        }
        prepListener = listener
        player.addListener(listener)
    }

    /**
     * Both tracks' analyses as the blend began, for the beat the scrubber
     * glows on — see [publishBlend]. Fixed at the start, like [render], so a
     * refining pass landing mid-blend cannot move the grid under the glow.
     */
    private var outgoingAnalysis = TrackAnalysis()
    private var incomingAnalysis = TrackAnalysis()
    private var bailStartedAt = 0L
    /** Graceful-exit dissolve (djmix11): the outgoing player's tail once a blend is done. */
    private var tailFromGain = 0f
    private var tailStartMs = 0L
    private var tailDurationMs = 0L
    /** djmix12: player the TAIL phase dissolves (outgoing normally, incoming when a
     * pre-handoff audible strand is retired). */
    private var tailTarget: ExoPlayer? = null
    private var lastRejectedLogKey: String? = null
    private var lastEmittedPlanVerdict: String? = null
    private var lastFallbackLogKey: String? = null
    /** djmix17: dedup key for begin() arm-death telemetry. */
    private var lastArmDeathKey: String? = null
    /** djmix19: dedup cache for mix.freeze.onInterruption (pair + phase). */
    private var lastFreezeLogKey: String? = null
    /** djmix19: for driveFade idle-stall detection (0 when not stalled). */
    private var idleStallSinceMs = 0L
    /** True once a stalled incoming player was asked to re-prepare(). */
    private var idleStallPrepared = false
    /** vc77: wall of the progress==1 BUFFERING stall in driveFade (0 when not stalled). */
    private var fadingStallSinceMs = 0L
    /** djmix13: wall of the most recent accepted (re)plan, for arm-death accounting; -1 = never. */
    private var lastPlanStartedAtMs = -1L
    /** Wall of the most recent successful planTransition attempt, accepted or not; -1 = never. */
    private var planGeneratedAtMs = -1L
    /** Truthful plan age for telemetry; null until the first planTransition attempt. */
    private fun planAgeMs(now: Long = nowMs()): Long? =
        planGeneratedAtMs.takeIf { it >= 0L }?.let { (now - it).coerceAtLeast(0L) }

    // ---- Reactive re-planning on analyser updates ----------------------------
    /** Monotonic token bumped whenever fresh analysis for the pending pair lands. */
    private var planGeneration = 0L
    /** Generation a plan result was computed against; an older result is discarded. */
    private var planResultGeneration = -1L
    /** Debounced analysis re-plan job; at most one is active. */
    private var analysisReplanJob: Job? = null
    /** The [analysisUpdates] collector, cancelled with the controller. */
    private var analysisCollectorJob: Job? = null
    /** True while an analysis-triggered plan pass is running (never two at once). */
    private var replanning = false
    /** Generation on which an analysis-fallback NATURAL was taken; -1 = none pending. */
    private var fallbackNaturalGeneration = -1L
    /** Media position before which a fallback NATURAL is provisional, or -1 = none. */
    private var naturalProvisionalUntilPosMs = -1L
    /** Last observed analysis signature for the pair, for hosts that do not pass [analysisUpdates]. */
    private var lastPairAnalysisSignature: String? = null
    /** djmix13: shown loop pair that just failed; the loop is skipped for it. */
    private var loopSuppressedPair: String? = null
    private var loopSuppressedUntil = 0L
    /** djmix14: reason for the next mix.complete (planned-fade-done, loop-complete, ...). */
    private var lastCompleteReason: String? = null
    /** First observed loop bail cause; independent of the terminal/tail completion reason. */
    private var initiatingExitReason: String? = null
    /** Port observations captured before cleanup disarms or releases the loop session. */
    private var capturedTakeoverSpliceBufferStatus: LoopMixPort.TakeoverSpliceBufferStatus? = null
    private var capturedLoopTookOver: Boolean? = null
    /** djmix22 (vc64): last playWhenReady-change reason seen on the session player,
     * logged on mix.pause.detected so a focus-loss pause is provable from telemetry. */
    @Volatile private var lastPlayWhenReadyChangeReason: Int = 0
    /** djmix22 (vc64): wall (SystemClock.elapsedRealtime) of the last mix.complete
     * with handoff=true. tick() watches for a paused session player within 3s of it. */
    private var lastHandoffCompleteAtMs: Long = 0L
    /** djmix14-review: scrub epoch + pinned catch-up (prevents countdown drift). */
    private var scrubEpoch: Long = 0L
    private var pinnedCatchUpKey: String? = null
    private var pinnedCatchUpPlan: TransitionPlan? = null
    private val countdownStabilizer = CountdownStabilizer()
    private var scrubSettledAtMs: Long = 0L
    private var tailLoopSession: LoopMixSession? = null
    private var loopCleanupContendedSession: LoopMixSession? = null
    private var loopCleanupContendedSinceNanos = 0L
    private var inFallbackReplan = false
    private var loopBuildStartedAtMs: Long = 0L
    private var hardWatchdogTriggeredPair: String? = null
    private var hardWatchdogCooldownUntil: Long = 0L
    private var lastTimingPair: Pair<String, String>? = null
    private var classicPrepStartMs: Long = 0L
    private var classicArmLeadMs: Long = 0L
    private var lastFirePath: String = ""
    private var lastFireMove: String = ""
    private var lastFireFxCount: Int = 0
    private var lastFireStems: Int = 0
    private var lastFireFallbackReason: String = ""
    private var armGraceUsed = false
    private var armDeadline = 0L
    private var armOutgoingPreFiltered = false

    // ---- One-brain MixExecutor wiring (Milestone M3-B) ----------------------
    private var uiStateJob: Job? = null
    @Volatile private var activeMachineGeneration: Long = 0L
    private var lastDispatchedPairKey: PairKey? = null
    private var classicFallbackReason: String = ""
    private var classicHandledGeneration: Long = -1L

    private fun toCoreLevel(level: com.music.bitchord.playback.smart.choreo.DjLevel): CoreDjLevel = when (level) {
        com.music.bitchord.playback.smart.choreo.DjLevel.SUBTLE -> CoreDjLevel.SUBTLE
        com.music.bitchord.playback.smart.choreo.DjLevel.CLUB -> CoreDjLevel.CLUB
        com.music.bitchord.playback.smart.choreo.DjLevel.FESTIVAL -> CoreDjLevel.FESTIVAL
    }

    private fun toChoreoLevel(level: CoreDjLevel): com.music.bitchord.playback.smart.choreo.DjLevel = when (level) {
        CoreDjLevel.SUBTLE -> com.music.bitchord.playback.smart.choreo.DjLevel.SUBTLE
        CoreDjLevel.CLUB -> com.music.bitchord.playback.smart.choreo.DjLevel.CLUB
        CoreDjLevel.FESTIVAL -> com.music.bitchord.playback.smart.choreo.DjLevel.FESTIVAL
    }

    private fun isNativeRendererAvailable(player: ExoPlayer): Boolean {
        val outPort = loopPortOf(player) ?: return false
        if (!LoopMixRenderer.available || !outPort.supportsLoopMix) return false
        return loopPortOf(standby()) != null
    }

    private fun nativeUnavailabilityReason(player: ExoPlayer): String {
        val outPort = loopPortOf(player)
        return when {
            !LoopMixRenderer.available -> "no renderer"
            outPort == null -> "no output port"
            !outPort.supportsLoopMix -> "this output can't take it"
            loopPortOf(standby()) == null -> "no standby sink"
            else -> "renderer_unavailable"
        }
    }

    private val mixDeckAdapter = object : MixDeckAdapter {
        override fun startPrepare(
            pair: PairKey,
            generation: Long,
            djLevel: CoreDjLevel,
            positionMs: Long,
            minExitMs: Long?,
            preferNoStemVariant: Boolean,
            excludedRecipeIds: Set<String>,
        ) {
            activeMachineGeneration = generation
            val preparer = pairPreparer ?: return
            val player = runCatching { active() }.getOrNull() ?: return
            val baseReq = prepRequestFor(player, djLevelOverride = toChoreoLevel(djLevel)) ?: return
            if (baseReq.outTrack.id != pair.outTrackId || baseReq.inTrack.id != pair.inTrackId) return
            val excluded = excludedRecipeIds.mapNotNull {
                com.music.bitchord.playback.smart.choreo.RecipeId.fromCode(it)
            }.toSet()
            preparer.trigger(
                baseReq.copy(
                    positionSec = positionMs.coerceAtLeast(0L) / 1000.0,
                    minExitMs = minExitMs,
                    preferNoStemVariant = preferNoStemVariant,
                    excludedRecipes = excluded,
                    machineGeneration = generation,
                ),
                force = true,
            )
        }

        override fun cancelPrepare(generation: Long) {
            val cur = pairPreparer?.state?.value
            if (cur != null && cur.generation == generation) {
                pairPreparer?.invalidate("cancelled:$generation")
            }
            if (phase == Phase.IDLE) {
                discardPrime()
            }
        }

        override fun prepareRendererOffThread(planId: String, generation: Long, useNoStemVariant: Boolean) {
            val preparer = pairPreparer ?: return
            val player = runCatching { active() }.getOrNull()
            if (player != null && !isNativeRendererAvailable(player)) {
                val reason = nativeUnavailabilityReason(player)
                classicFallbackReason = reason
                AutomixTelemetry.event(
                    "mix.classic.reason",
                    mapOf("reason" to reason, "planId" to planId, "generation" to generation, "stage" to "prepare"),
                )
                mixExecutor.dispatch(MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, reason, recoverable = false))
                return
            }
            preparer.prepareRendererOffThread(planId, generation, useNoStemVariant)
        }

        override fun armTransition(planId: String, generation: Long, atMs: Long, btLatencyOffsetMs: Long) {
            armFromExecutor(planId, generation, atMs)
        }

        override fun startTransition(planId: String, generation: Long) {
            startFromExecutor(planId, generation)
        }

        override fun pauseBothInSync(generation: Long, byUser: Boolean) = onControllerThread {
            val out = outgoing ?: runCatching { active() }.getOrNull()
            val into = incoming
            out?.playWhenReady = false
            into?.playWhenReady = false
        }

        override fun resumeBothInSync(generation: Long) = onControllerThread {
            val out = outgoing
            val into = incoming
            if (handedOff) {
                into?.playWhenReady = true
                out?.playWhenReady = true
            } else if (out != null && into != null) {
                out.playWhenReady = true
                if (loopIncomingStarted || phase == Phase.FADING) {
                    into.playWhenReady = true
                }
            } else {
                runCatching { active().playWhenReady = true }
            }
        }

        override fun retireDeck(deck: DeckTarget, generation: Long, preserveSurvivor: Boolean) {
            if (!preserveSurvivor) return
            onControllerThread {
                when {
                    phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING -> {
                        if (deck == DeckTarget.OUT && handedOff) {
                            finishLoop()
                        } else {
                            loopBail(
                                "executor-retire-${deck.name.lowercase()}",
                                settle = false,
                                preserveSeek = true,
                                deferDisarm = handedOff,
                            )
                        }
                    }
                    phase == Phase.ARMING -> cancelArming("executor-retire-${deck.name.lowercase()}")
                    phase == Phase.FADING -> bail(force = true)
                }
            }
        }

        override fun completeHandover(planId: String, generation: Long) = onControllerThread {
            val out = outgoing
            val into = incoming
            if (out != null && into != null && !handedOff) {
                if (phase == Phase.LOOP_RUNNING) {
                    val session = loopSession
                    val m = session?.mixSeconds(System.nanoTime(), out.isPlaying)
                    if (session != null && canAdoptSessionPlayer(loopIncomingStarted)) {
                        handOff(out, into, m, session.clock.releaseAt)
                    }
                } else if (phase == Phase.FADING) {
                    handOff(out, into)
                }
            }
        }
    }

    internal val mixExecutor: MixExecutor = MixExecutor(
        deckAdapter = mixDeckAdapter,
        config = MixMachineConfig.DEFAULT,
        store = null,
    )

    private inline fun onControllerThread(crossinline block: () -> Unit) {
        val looperThread = runCatching { active().applicationLooper.thread }.getOrNull()
        if (looperThread == null || Thread.currentThread() === looperThread) {
            block()
        } else {
            scope.launch { block() }
        }
    }

    private fun armFromExecutor(planId: String, generation: Long, atMs: Long) = onControllerThread {
        if (!AppSettings.smartFadeEnabled.value || ListenTogether.state.value.inParty ||
            BitPerfectOutput.status.value.active || versionSwapActive()
        ) return@onControllerThread
        if (phase == Phase.LOOP_ARMING && loopSession?.preparationGeneration == generation) {
            loopTargetStartMs = atMs.coerceAtLeast(0L)
            return@onControllerThread
        }
        if (phase != Phase.IDLE && phase != Phase.LOOP_ARMING) return@onControllerThread
        val player = runCatching { active() }.getOrNull() ?: return@onControllerThread
        if (!isNativeRendererAvailable(player)) {
            val reason = nativeUnavailabilityReason(player)
            classicFallbackReason = reason
            AutomixTelemetry.event(
                "mix.classic.reason",
                mapOf("reason" to reason, "planId" to planId, "generation" to generation, "stage" to "arm"),
            )
            mixExecutor.dispatch(MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, reason, recoverable = false))
            return@onControllerThread
        }
        val currentItem = player.currentMediaItem ?: return@onControllerThread
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex >= player.mediaItemCount) return@onControllerThread
        val nextItem = player.getMediaItemAt(nextIndex)
        val outPort = loopPortOf(player) ?: return@onControllerThread
        val into = standby()
        val inPort = loopPortOf(into) ?: return@onControllerThread
        if (player === into) return@onControllerThread
        val sampleRate = outPort.sampleRate
        if (sampleRate <= 0) return@onControllerThread

        val preparer = pairPreparer ?: return@onControllerThread
        val pair = preparer.frozen(currentItem.mediaId, nextItem.mediaId, generation)
            ?: preparer.snapshotFor(currentItem.mediaId, nextItem.mediaId)?.takeIf {
                it.generation == generation && it.plan != null
            }
            ?: run {
                mixExecutor.dispatch(
                    MixEvent.RendererError(
                        FailureKind.RENDERER_PREPARE_ERROR,
                        "frozen_pair_missing",
                        recoverable = false,
                    ),
                )
                return@onControllerThread
            }
        val plan = pair.plan ?: return@onControllerThread
        val mState = mixExecutor.currentState()
        val wantNoStem = when (mState) {
            is MixState.Armed -> mState.usingNoStemVariant
            is MixState.Ready -> mState.usingNoStemVariant
            is MixState.LastResort -> true
            else -> pair.useNoStemVariant
        }
        val session = preparer.consumePreparedSession(currentItem.mediaId, nextItem.mediaId, generation, wantNoStem)
            ?: preparer.consumePreparedSession(currentItem.mediaId, nextItem.mediaId, generation, !wantNoStem)
            ?: loopSession?.takeIf { it.preparationGeneration == generation }
            ?: preparer.prepareRenderer(pair, useNoStemVariant = wantNoStem)
        if (session == null) {
            mixExecutor.dispatch(
                MixEvent.RendererError(
                    FailureKind.RENDERER_PREPARE_ERROR,
                    "arm_session_null",
                    recoverable = false,
                ),
            )
            return@onControllerThread
        }

        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: (pair.outAnalysis?.duration?.times(1000.0))?.roundToLong()
            ?: 0L
        val nextDurationMs = nextItemDurationMs(nextIndex, nextItem)
        rememberLoopPair(
            currentItem, nextItem,
            pair.outAnalysis ?: analysisFor(currentItem),
            pair.inAnalysis ?: analysisFor(nextItem),
            durationMs, nextDurationMs,
        )
        discardPrime()
        tempoEngine.endEase(clickless = false)
        MixDebug.reset()
        phase = Phase.LOOP_ARMING
        smartFadeActive = true
        handedOff = false
        outgoing = player
        incoming = into
        outgoingItemIndex = player.currentMediaItemIndex
        loopPlanned = plan
        loopPlanKey = "${currentItem.mediaId}->${nextItem.mediaId}|${plan.out.loopStart}"
        loopTarget = plan
        loopTargetStartMs = atMs.coerceAtLeast(0L)
        loopScript = pair.phraseScript
        holdAtEnd(player)
        deckTiming.clear()
        deckTiming.deadlineMs = DeckBudget.deadlineMs(nowMs(), player.isPlaying, loopTargetStartMs, player.currentPosition)
        loopBuildStartedAtMs = 0L
        val context = ensureOpportunity(currentItem.mediaId, nextItem.mediaId)
        val revision = reviseRoute(plan)
        val coarseDecline = shouldDeclineToMix(
            pair.outAnalysis ?: analysisFor(currentItem),
            pair.inAnalysis ?: analysisFor(nextItem),
        )
        nativeBinding = NativeBinding(context, revision, generation.toInt(), coarseDecline)
        armLoop(session, player, into, nextIndex, outPort, inPort)
    }

    private fun startFromExecutor(planId: String, generation: Long) = onControllerThread {
        if (phase == Phase.LOOP_RUNNING && loopSession?.preparationGeneration == generation) return@onControllerThread
        if (loopSession == null || loopSession?.preparationGeneration != generation) {
            val targetAtMs = mixExecutor.currentState().plan?.let {
                (it.lastPhraseDownbeatMs ?: it.frozenExitMs)
            } ?: runCatching { active().currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
            armFromExecutor(planId, generation, targetAtMs)
        }
        val session = loopSession ?: return@onControllerThread
        val out = outgoing ?: return@onControllerThread
        val outPort = session.outPort
        if (outPort != null && !outPort.tookOver && phase == Phase.LOOP_ARMING) {
            Log.d(TAG, "executor Start planId=$planId gen=$generation pos=${out.currentPosition}ms tookOver=${outPort.tookOver}")
        }
    }

    private fun syncInspectorWithUiState(ui: MixUiState) {
        val mState = mixExecutor.currentState()
        val pair = mState.pair
        if (ui.phase == MixUiPhase.IDLE || pair == null) {
            if (mState is MixState.Idle && (pairPreparer == null || MixInspectorFeed.state.value != null)) {
                MixInspectorFeed.publish(null)
            }
            return
        }
        val cur = MixInspectorFeed.state.value ?: return
        if (cur.current.mediaId != pair.outTrackId || cur.next.mediaId != pair.inTrackId) return
        val mappedPhase = when (ui.phase) {
            MixUiPhase.IDLE -> MixInspectorPhase.IDLE
            MixUiPhase.PREPARING -> if (cur.phase.ordinal < MixInspectorPhase.PREPARED.ordinal) cur.phase else MixInspectorPhase.PREPARED
            MixUiPhase.MOVED -> MixInspectorPhase.PLANNED
            MixUiPhase.READY -> MixInspectorPhase.PREPARED
            MixUiPhase.ARMED -> MixInspectorPhase.ARMED
            MixUiPhase.RUNNING, MixUiPhase.HANDING_OVER -> MixInspectorPhase.RUNNING
            MixUiPhase.DONE -> MixInspectorPhase.DONE
            MixUiPhase.LAST_RESORT -> MixInspectorPhase.BUILDING
            MixUiPhase.SUSPENDED, MixUiPhase.FAILED -> MixInspectorPhase.CANCELLED
        }
        val updated = cur.copy(
            phase = mappedPhase,
            recipeName = ui.recipeName ?: cur.recipeName,
            reason = ui.inspectorReason.takeIf { it.isNotBlank() } ?: cur.reason,
            stemsReady = if (ui.readiness.stemsRequired == 0) true else (ui.readiness.stemsReady >= ui.readiness.stemsRequired),
            remixAvailable = ui.canRemix && cur.phase < MixInspectorPhase.ARMED,
            remixUnavailableReason = if (ui.canRemix) null else (cur.remixUnavailableReason ?: "Remix unavailable in ${ui.phase.name.lowercase()}."),
        )
        if (updated != cur) {
            MixInspectorFeed.publish(updated)
        }
    }


    /**
     * When the last transition finished, from [SystemClock.elapsedRealtime], or
     * zero while none has this session.
     *
     * Read through [msSinceTransition] by callers that have to stay off the
     * session player for a moment *after* a blend as well as during one.
     */
    private var settledAt = 0L

    /**
     * djmix18 (Bug 2): a seek issued by the loop-mix handover itself ([finishLoop]'s
     * corrective seek) must never be read as a user scrub. While [internalHandoverSeek]
     * is true (or within [suppressScrubUntilMs] after it), [listener]'s
     * `onPositionDiscontinuity` is ignored so `resetPlansForScrub` never tears down a
     * just-completed mix or fires a second seek after unmuting.
     */
    @Volatile private var internalHandoverSeek = false
    @Volatile private var suppressScrubUntilMs = 0L

    /** Exact handover telemetry captured in [finishLoop], emitted on `mix.complete`. */
    private var handoverExpectedMs = 0L
    private var handoverHeardMs = -1L
    private var handoverTargetMs = 0L

    /**
     * Gain the outgoing track was at when the fade was interrupted, so the ramp
     * out starts from where it actually is rather than from full volume.
     */
    private var bailFromGain = 0f

    /**
     * Abnormal-exit survivor recovery: a [driveFade] blend that ended via
     * settingSwitchedOff (or a forced [bail]) before progress 1 leaves the
     * survivor parked mid-ride while audible. Set at the [driveFade]/[bail]
     * exit so [finish] returns the survivor to full-band once the retiree
     * is silent, without touching the retiring spare filter.
     */
    private var survivorRecoveryPending = false

    /** Dedupes the per-tick plan log down to one line per distinct verdict. */
    private var lastPlanVerdict = ""

    /** Where each side of the blend really is between the player's own reports — see [PositionClock]. */
    private val outClock = PositionClock(HALF_TICK_NANOS, MAX_EXTRAPOLATION_NANOS)
    private val inClock = PositionClock(HALF_TICK_NANOS, MAX_EXTRAPOLATION_NANOS)

    /** [player]'s position through whichever clock follows its side of the blend. */
    private fun positionMs(player: ExoPlayer): Double =
        if (player === incoming) inClock.read(player) else outClock.read(player)

    /**
     * True while a transition is armed or running.
     *
     * For callers about to do something that would otherwise fight this class
     * for the session player mid-blend — [PlaybackService]'s quality upgrade is
     * the one that does, since `replaceMediaItem` tears the current source down
     * and rebuilds it. Doing that to either player mid-transition breaks the
     * blend rather than merely delaying it, so such a caller should wait for
     * this to clear rather than proceed anyway.
     */
    fun isTransitioning(): Boolean = phase != Phase.IDLE

    /** Applies the listener's speed without exposing AudioTrack's parameter-change click. */
    fun applyPlaybackSpeed(speed: Float) {
        if (isTransitioning()) return
        // User-controlled speed must track the slider immediately. A previous
        // gain-notch wrapper restarted for every slider emission and sounded
        // like repeated stalls while dragging.
        val safe = deckSpeed(speed, 1.0)
        for (player in listOf(active(), standby()).distinct()) {
            player.setPlaybackSpeed(safe)
        }
    }

    /**
     * How long since the last transition finished, or null while none has.
     *
     * For the same caller as [isTransitioning], which needs a little more than
     * that flag can give it. The flag clears on the tick the blend completes,
     * so a source torn down and rebuilt the moment it clears puts its break in
     * the audio a few hundred milliseconds after the incoming track finally
     * stood alone — not a broken blend, but heard as one. A caller that wants
     * the transition to have been *over* for a while, rather than merely to
     * have ended, waits this out too.
     *
     * Says nothing about a transition still in flight — it reports whatever the
     * one before it left behind — so [isTransitioning] stays the first question
     * to ask.
     */
    fun msSinceTransition(): Long? =
        settledAt.takeIf { it != 0L }?.let { nowMs() - it }

    private var visualGeneration: Long? = null
    private var visualQueue: List<MediaItem> = emptyList()
    private var visualCurrentIndex = C.INDEX_UNSET
    private val visualDeckErrors = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = cancelAutoMixVisual()
    }

    fun cancelAutoMixVisual() = AutoMixVisualFeed.cancel(visualGeneration)

    private fun armAutoMixVisual(out: ExoPlayer, into: ExoPlayer, nextIndex: Int, items: List<MediaItem>) {
        fun trackAt(index: Int): AutoMixVisualTrack? {
            val item = items.getOrNull(index) ?: return null
            val entryId = item.queueEntryId
            val ordinal = (0 until index).count {
                val prior = items[it]
                prior.mediaId == item.mediaId && prior.queueEntryId == entryId
            }
            return AutoMixVisualTrack(
                QueueEntryIdentity(item.mediaId, entryId, ordinal),
                index,
                item.mediaMetadata.artworkUri?.toString(),
            )
        }
        val from = trackAt(out.currentMediaItemIndex) ?: return
        val to = trackAt(nextIndex) ?: return
        visualQueue = items
        visualCurrentIndex = from.armedIndex
        visualGeneration = AutoMixVisualFeed.arm(from, to)
        out.addListener(visualDeckErrors)
        into.addListener(visualDeckErrors)
    }

    private fun clearAutoMixVisual() {
        if (visualGeneration == null) return
        cancelAutoMixVisual()
        outgoing?.removeListener(visualDeckErrors)
        incoming?.removeListener(visualDeckErrors)
        visualGeneration = null
        visualQueue = emptyList()
        visualCurrentIndex = C.INDEX_UNSET
    }

    // MediaMetadata.equals does not compare the contents of extras.
    private fun sameVisualQueueItem(expected: MediaItem, actual: MediaItem?): Boolean =
        actual != null && expected == actual && expected.queueEntryId == actual.queueEntryId &&
            expected.queueTier == actual.queueTier

    /**
     * Raw playlist mutations can bypass the session wrapper. Allow the controller's pure
     * append reconciliation and the service's post-handoff history trim/rotation, but
     * not a replaced or reordered snapshot. Accepted user edits use the intent hook.
     */
    fun checkAutoMixVisualTimeline(player: ExoPlayer) {
        val visual = AutoMixVisualFeed.pending(visualGeneration) ?: return
        val index = player.currentMediaItemIndex
        val trim = visualCurrentIndex - index
        val retained = visualQueue.size - trim
        if (index !in 0 until player.mediaItemCount || trim < 0 || (!handedOff && trim != 0) ||
            retained > player.mediaItemCount || (0 until retained).any {
                !sameVisualQueueItem(visualQueue[it + trim], player.getMediaItemAt(it))
            } || (!handedOff && player.nextMediaItemIndex != visual.incoming.armedIndex)
        ) {
            cancelAutoMixVisual()
        } else {
            visualQueue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            visualCurrentIndex = index
        }
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // The listener moving the playhead. djmix13: never silently
            // fire a stale plan or double-fire the incoming deck.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                // djmix18 (Bug 2): the loop-mix handover's own corrective seek is not a
                // scrub. Ignore it entirely — no resetPlansForScrub, no second seek.
                val nowMs0 = nowMs()
                if (internalHandoverSeek || nowMs0 < suppressScrubUntilMs) {
                    internalHandoverSeek = false
                    AutomixTelemetry.event(
                        "mix.seek",
                        mapOf(
                            "phase" to phase.name,
                            "positionBeforeMs" to oldPosition.positionMs,
                            "positionAfterMs" to newPosition.positionMs,
                            "action" to "handover-suppressed",
                            "planAgeMs" to planAgeMs(nowMs0),
                        ),
                    )
                    return
                }
                val now = nowMs()
                val tookOver = loopSession?.outPort?.tookOver == true
                val seekPhase = when (phase) {
                    Phase.IDLE -> SeekAction.Phase.IDLE
                    Phase.ARMING -> SeekAction.Phase.ARMING
                    Phase.FADING -> SeekAction.Phase.FADING
                    Phase.TAIL -> SeekAction.Phase.TAIL
                    Phase.LOOP_ARMING -> if (tookOver) SeekAction.Phase.LOOP_RUNNING else SeekAction.Phase.LOOP_ARMING
                    Phase.LOOP_RUNNING -> SeekAction.Phase.LOOP_RUNNING
                    Phase.BAILING -> SeekAction.Phase.BAILING
                    Phase.SLEEP_FADE -> SeekAction.Phase.SLEEP
                }
                val sameMediaItem = oldPosition.mediaItemIndex == newPosition.mediaItemIndex
                if (SeekAction.shouldIgnoreSeek(seekPhase, oldPosition.positionMs, newPosition.positionMs, sameMediaItem)) {
                    AutomixTelemetry.event(
                        "mix.seek",
                        mapOf(
                            "phase" to phase.name,
                            "positionBeforeMs" to oldPosition.positionMs,
                            "positionAfterMs" to newPosition.positionMs,
                            "action" to "spurious-ignored",
                            "planAgeMs" to planAgeMs(now),
                        ),
                    )
                    return
                }
                val action = SeekAction.decide(
                    seekPhase,
                    appSettingsMarkerShown() || shownCountdownKey != null,
                )
                tempoEngine.cancelPendingSpeedChange()
                AutomixTelemetry.event(
                    "mix.seek",
                    mapOf(
                        "phase" to phase.name,
                        "positionBeforeMs" to oldPosition.positionMs,
                        "positionAfterMs" to newPosition.positionMs,
                        "action" to action.name.lowercase(),
                        "planAgeMs" to planAgeMs(now),
                    ),
                )
                when (action) {
                    SeekAction.Action.HONOUR -> Unit
                    SeekAction.Action.CANCEL -> {
                        cancelAutoMixVisual()
                        resetPlansForScrub()
                        AutomixTelemetry.event("mix.plan.rejected", mapOf("rung" to "scrub-cancelled", "reason" to "scrub", "phase" to phase.name))
                        if (phase == Phase.ARMING) cancelArming("scrub-cancelled") else bail(force = true)
                    }
                    SeekAction.Action.EMERGENCY_TAIL -> {
                        cancelAutoMixVisual()
                        resetPlansForScrub()
                        val isLoopRunning = phase == Phase.LOOP_RUNNING || loopSession?.outPort?.tookOver == true
                        if (isLoopRunning) {
                            val wasHandedOff = handedOff
                            val activePlayer = active()
                            val audibleSpare = if (wasHandedOff) outgoing else incoming
                            val session = loopSession
                            session?.let { s ->
                                val heardOut = runCatching { s.renderer.heard(LoopMixRenderer.OUT) }.getOrNull() ?: 0.0
                                val heardIn = runCatching { s.renderer.heard(LoopMixRenderer.IN) }.getOrNull() ?: 0.0
                                runCatching { s.renderer.setFadeOut(LoopMixRenderer.OUT, (heardOut + 0.35).coerceAtLeast(0.0)) }
                                runCatching { s.renderer.setFadeOut(LoopMixRenderer.IN, (heardIn + 0.35).coerceAtLeast(0.0)) }
                            }
                            loopBail("seek-emergency-tail", settle = false, preserveSeek = true, deferDisarm = true)
                            @Suppress("UNUSED_VARIABLE") val captured = activePlayer to audibleSpare
                        } else {
                            val spare = if (handedOff) outgoing else incoming
                            if (spare != null && RetireGuard.audible(spare.volume)) {
                                beginTailOrFinish(spare, "seek-emergency-tail", "discontinuity:" + phase.name)
                            } else {
                                bail()
                            }
                        }
                    }
                }
                if (sameMediaItem) {
                    mixExecutor.dispatch(MixEvent.UserSeek(newPosition.positionMs))
                    notifyPairPreparer(fromSeek = true)
                }
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            onPlaylistChanged(timeline)
            notifyPairPreparer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            tempoEngine.cancelPendingSpeedChange()
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                    if (!handedOff) cancelAutoMixVisual()
                    expireIdleOpportunity("natural-auto-advance")
                    ranOutBeforeHandoff()
                }
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                    cancelAutoMixVisual()
                    expireIdleOpportunity("natural-repeat")
                }
            }
            lastDispatchedPairKey = null
            onTrackChanged()
        }

        override fun onPlayerError(error: PlaybackException) {
            AutomixTelemetry.event("mix.error", mapOf("error" to error.message.orEmpty(), "phase" to phase.name))
            mixExecutor.dispatch(
                MixEvent.RendererError(
                    FailureKind.RENDERER_RUNTIME_ERROR,
                    error.message.orEmpty(),
                    recoverable = false,
                ),
            )
            bail()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) tempoEngine.cancelPendingSpeedChange()
            lastPlayWhenReadyChangeReason = reason
            when {
                !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ->
                    mixExecutor.dispatch(MixEvent.UserPause)
                !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
                    mixExecutor.dispatch(MixEvent.AudioFocusLost(transient = true))
                playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ->
                    mixExecutor.dispatch(MixEvent.UserResume)
                playWhenReady && !mixExecutor.currentState().userPaused &&
                    reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM ->
                    mixExecutor.dispatch(MixEvent.UserResume)
            }
            if (playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
            val held = heldAtEnd ?: return
            if (held !== listeningTo) return
            val into = incoming
            if (phase == Phase.FADING && !handedOff && into != null) {
                // The blend is running and the incoming track is already
                // playing under it: this is just the handoff, a little late.
                handOff(held, into)
                return
            }
            // No blend to hand to. Let the queue move on as it would have.
            releaseHold()
            if (phase == Phase.ARMING) cancelArming("outgoing-ended-while-arming")
            held.play()
        }
    }

    /**
     * The session player ran off the end of its track, unheld, before the
     * handoff took it off that track — so it is now playing the song this
     * transition was bringing in, from its first note, on the outgoing side.
     *
     * Still arming, that copy is simply the next track arriving the plain
     * way, but on a sink the arming ride had left low-passed for the blend,
     * and [driveArming] would go on to wait for the *new* song to reach the
     * old one's mix-out point and blend it into itself. So the transition is
     * dropped and the queue moves on as it would have. Mid-blend, the incoming
     * player already has the song at its cue: that is the handoff, late, and
     * the copy on the outgoing side is silenced as [ranOn] does.
     */
    /**
     * The queue changed under a plan. If the next track is no longer the one
     * the plan was built for, the prime is discarded and an in-flight blend
     * bails. A change that only touches items after next rebuilds the standby
     * snapshot instead. Every index is checked against [Timeline.windowCount].
     */
    private fun onPlaylistChanged(timeline: Timeline) {
        val player = listeningTo ?: return
        val count = timeline.windowCount
        val current = player.currentMediaItemIndex
        if (current < 0 || current >= count) {
            dropForQueueEdit("current index $current outside $count windows")
            return
        }
        val next = current + 1
        val nextId = if (next < count) {
            val window = Timeline.Window()
            timeline.getWindow(next, window).mediaItem.mediaId
        } else {
            null
        }
        val expected = plannedNextId ?: loopInItem?.mediaId
        // djmix15: after the handoff the session player IS the incoming
        // track (i+1), so its "next" is i+2 by definition. Comparing that
        // to the armed expected (i+1) is a certain false queueEdit 1.7-6.9s
        // after every classic startFade (phone PIN-locked). Only the
        // pre-handoff window can judge next against the plan; the active
        // current-index check above still catches a user removing or
        // replacing the audible incoming track. See [SeekAction.isNextEdit].
        if (SeekAction.isNextEdit(handedOff, nextId, expected)) {
            dropForQueueEdit("next is $nextId, plan wanted $expected")
            return
        }
        val out = outgoing
        val into = incoming
        // Same reason: after the handoff the standby snapshot is obsolete
        // (the retiring player may have been truncated at handOff), so a
        // diverged snapshot is not a user edit.
        if (!handedOff && out != null && into != null && phase != Phase.IDLE && !reconcileQueue(out, into)) {
            dropForQueueEdit("standby snapshot diverged")
        }
    }

    /**
     * djmix14 scrub reset: every plan, lock and countdown is dropped so a
     * stale [loopPlanned] (past loopStart) can never short-circuit classic
     * begin() or re-lock a dead 0s countdown after a seek. Called on both
     * CANCEL and EMERGENCY_TAIL scrub paths.
     */
    private fun resetPlansForScrub() {
        if (phase == Phase.IDLE && opportunity != null && !terminalRecorded) {
            mixEvent("mix.plan.rejected", mapOf("stage" to "terminal", "outcome" to "cancelled",
                "terminal" to true, "refusalCode" to "scrub-cancelled", "reason" to "scrub"))
            terminalRecorded = true
            opportunity = null
        }
        scrubEpoch++
        planGeneration++
        analysisReplanJob?.cancel()
        analysisReplanJob = null
        countdownStabilizer.reset()
        scrubSettledAtMs = nowMs() + SCRUB_SETTLE_MS
        pinnedCatchUpKey = null
        pinnedCatchUpPlan = null
        shownCountdownKey = null
        shownLoopStartMs = 0L
        watchdogFired = false
        loopPlanned = null
        loopPlanKey = null
        loopScript = null
        loopTargetStartMs = 0L
        loopTarget = null
        classicFallbackReason = ""
        classicHandledGeneration = -1L
        lastSmartPlan = null
        lastSmartPlanStartMs = 0L
        lastRejectedLogKey = null
        lastEmittedPlanVerdict = null
        lastFallbackLogKey = null
        lastArmDeathKey = null
        loopSuppressedPair = null
        loopSuppressedUntil = 0L
        AppSettings.smartTransitionWindow.value = null
        discardPrime()
    }

    /** Minimum lead (s) a new loop start needs ahead of the playhead. */
    private fun minLoopLeadSec(): Double {
        val primed = primedSession != null && primedKey != null
        return if (primed) 0.8 else 4.5
    }

    /**
     * djmix14-review: single fast-ready check for all 4 sites. True when the
     * decks are already primed for this exact key, or both exact PCM regions
     * are in the RAM cache (bounds match LoopMixSession.build exactly).
     */
    private fun isLoopFastReady(
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        sampleRate: Int,
    ): Boolean {
        if (primedSession != null && primedKey == primeKey(plan, outItem, inItem, sampleRate)) return true
        val straight = straightPreparedPair
        if (straight != null && ChoreoSessionGlue.isSamePlan(straight.plan ?: return false, plan) &&
            straight.sampleRate == sampleRate &&
            straight.outId == outItem.mediaId && straight.inId == inItem.mediaId
        ) return true
        val b = LoopMix.regionBounds(plan)
        return loopRegionCached(outItem.mediaId, sampleRate, b.outFrom, b.outTo) &&
            loopRegionCached(inItem.mediaId, sampleRate, b.inFrom, b.inTo)
    }
    /** Lead for [plan] via the shared fast-ready check: 0.8 cached/primed, 4.5 cold. */
    private fun fastLeadSec(
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        sampleRate: Int,
    ): Double = if (isLoopFastReady(plan, outItem, inItem, sampleRate)) 0.8 else 4.5

    private fun isStraightRoute(plan: LoopMix.Plan): Boolean =
        plan.alternativeKind == "v2-straight"

    /**
     * Returns the exact frozen preparation matching `(outItem, inItem, generation, level)`
     * from [PairPreparer], never triggering decode or transition-time re-planning.
     */
    private fun frozenPreparedFor(
        outItem: MediaItem,
        inItem: MediaItem,
        generation: Long,
        sampleRate: Int,
        level: com.music.bitchord.playback.smart.choreo.DjLevel = AppSettings.automixDjLevel.value,
    ): PreparedPair? {
        if (sampleRate <= 0 || generation <= 0L) return null
        return pairPreparer?.frozen(outItem.mediaId, inItem.mediaId, generation, level)
            ?.takeIf { it.sampleRate == sampleRate }
    }

    private fun dropForQueueEdit(why: String) {
        Log.d(TAG, "playlist changed: $why")
        // djmix10: an audible mix keeps its plan (plan vars are only
        // cleared pre-audible); the blend finishes and hands off.
        val audible = phase == Phase.FADING || phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING ||
            (phase == Phase.ARMING && appSettingsMarkerShown())
        if (audible) {
            AutomixTelemetry.event("mix.freeze.queueEdit", mapOf("why" to why, "phase" to phase.name))
            return
        }
        if (phase == Phase.IDLE && opportunity != null && !terminalRecorded) {
            mixEvent("mix.plan.rejected", mapOf("stage" to "terminal", "outcome" to "cancelled",
                "terminal" to true, "refusalCode" to "queue-edit", "reason" to why))
            terminalRecorded = true
            opportunity = null
        }
        planGeneration++
        analysisReplanJob?.cancel()
        analysisReplanJob = null
        lastSmartPlan = null
        discardPrime()
        loopPlanned = null
        loopScript = null
        loopPlanKey = null
        plannedNextId = null
        if (phase != Phase.IDLE) bail()
    }

    private fun ranOutBeforeHandoff() {
        if (handedOff) return
        when (phase) {
            Phase.ARMING -> cancelArming("outgoing-ran-on-while-arming")
            Phase.LOOP_ARMING, Phase.LOOP_RUNNING -> bail()
            Phase.FADING -> {
                val out = outgoing ?: return bail()
                val into = incoming ?: return bail()
                Log.d(TAG, "outgoing ran on into the next track before the handoff")
                out.volume = 0f
                handOff(out, into)
            }
            else -> Unit
        }
    }

    /**
     * Keeps [listener] on whichever player is the session.
     *
     * It has to move rather than sit on both: arming loads a whole queue onto
     * the standby, which Media3 reports as the playlist changing, and a listener
     * attached there would read that as the queue being replaced out from under
     * the very transition it is setting up.
     */
    private fun listenTo(target: ExoPlayer) {
        if (listeningTo === target) return
        listeningTo?.removeListener(listener)
        target.addListener(listener)
        listeningTo = target
    }

    /**
     * Collects [analysisUpdates] for the current pair in the controller's own
     * main-thread scope. Re-reads [analysisFor] through the ordinary planning
     * path on emission rather than waiting for the next IDLE tick.
     */
    private fun startAnalysisCollector() {
        analysisCollectorJob?.cancel()
        analysisCollectorJob = scope.launch {
            analysisUpdates.collect { trackId ->
                if (trackId.isBlank()) return@collect
                val pair = runCatching {
                    val p = active()
                    val cur = p.currentMediaItem?.mediaId
                    val idx = p.nextMediaItemIndex
                    val next = if (idx != C.INDEX_UNSET) p.getMediaItemAt(idx).mediaId else null
                    cur to next
                }.getOrNull() ?: return@collect
                if (trackId != pair.first && trackId != pair.second) return@collect
                onPairAnalysisChanged(trackId)
            }
        }
    }

    /**
     * A background analysis pass landed for one side of the pending pair.
     * Coalesces bursts, bumps the generation token so a plan computed against
     * older evidence is discarded, and re-plans only inside the pre-arm window:
     * a plan already committed or firing is never cancelled or blocked.
     */
    private fun onPairAnalysisChanged(trackId: String) {
        if (phase != Phase.IDLE) return
        if (versionSwapActive() || ListenTogether.state.value.inParty) return
        // When MixExecutor + PairPreparer own planning, PairPreparer handles analysisUpdates directly.
        if (pairPreparer != null && runCatching { isNativeRendererAvailable(active()) }.getOrDefault(false)) return
        if (fallbackNaturalGeneration >= 0L) {
            val untilPos = naturalProvisionalUntilPosMs
            val pos = runCatching { active().currentPosition }.getOrNull()
            if (pos == null || pos < untilPos || untilPos < 0L) {
                fallbackNaturalGeneration = -1L
                naturalProvisionalUntilPosMs = -1L
                lastSmartPlan = null
                plannedTransition = null
                AppSettings.smartTransitionWindow.value = null
            }
        }
        analysisReplanJob?.cancel()
        analysisReplanJob = scope.launch {
            delay(ANALYSIS_REPLAN_DEBOUNCE_MS)
            if (!isActive || phase != Phase.IDLE) return@launch
            val token = ++planGeneration
            runCatching { replanForAnalysis(token) }
        }
    }

    /** One plan pass for the current pair; the result is tagged with the token it used. */
    private fun replanForAnalysis(token: Long) {
        if (replanning) return
        replanning = true
        try {
            planAutoTransition(active())
            planResultGeneration = token
        } finally {
            replanning = false
        }
    }

    // FORK-HOOK(mix-inspector): the controller accepts only the exact preparation currently shown.
    private fun installMixInspectorFeed() {
        MixInspectorFeed.setRemixAction(
            MixInspectorRemixAction { key: MixInspectorKey ->
                key == MixInspectorFeed.state.value?.key && remixCurrentTransition()
            },
        )
    }

    fun start() {
        installMixInspectorFeed()
        pairPreparer?.attachExecutionBridge(
            executor = mixExecutor,
            inLevel = { outId, inId ->
                (loudnessGain(inId) / loudnessGain(outId)).takeIf { it.isFinite() && it > 0f } ?: 1f
            },
            highQuality = { AppSettings.automixPerformanceMode.value != AutomixPerformanceMode.EFFICIENT },
        )
        listenTo(active())
        startAnalysisCollector()
        uiStateJob?.cancel()
        uiStateJob = scope.launch {
            mixExecutor.uiState.collect { ui ->
                syncInspectorWithUiState(ui)
                publishAutomixDetail()
            }
        }
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var failedTicks = 0L
            while (isActive) {
                val nextDelayMs = try {
                    tick()
                    val base = when (phase) {
                        Phase.IDLE -> IDLE_STEP_MS
                        Phase.ARMING -> ARM_STEP_MS
                        Phase.FADING -> FADE_STEP_MS
                        Phase.TAIL -> FADE_STEP_MS
                        Phase.SLEEP_FADE -> FADE_STEP_MS
                        Phase.BAILING -> BAIL_STEP_MS
                        Phase.LOOP_ARMING -> ARM_STEP_MS
                        Phase.LOOP_RUNNING -> LOOP_STEP_MS
                    }
                    // djmix14: firing jitter — within 2 s of the target, tick
                    // every 20 ms so startFade fires within +-25 ms.
                    val nearTarget = when (phase) {
                        Phase.ARMING -> lastSmartPlanStartMs > 0L && kotlin.math.abs((active().currentPosition - lastSmartPlanStartMs)) <= 2000L
                        Phase.LOOP_ARMING -> loopTargetStartMs > 0L && kotlin.math.abs((active().currentPosition - loopTargetStartMs)) <= 2000L
                        else -> false
                    }
                    if (failedTicks > 0L) {
                        AutomixTelemetry.event("mix.tick.recovered", mapOf("phase" to phase.name, "failedTicks" to failedTicks))
                        failedTicks = 0L
                    }
                    if (nearTarget) 20L else base
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // A failed iteration must not kill Automix's SupervisorJob child.
                    // Report once per uninterrupted failure burst; success re-arms it.
                    if (failedTicks == 0L) reportTickError(error)
                    failedTicks++
                    IDLE_STEP_MS
                }
                // Also yield on failure, including deterministic player getter failures.
                delay(nextDelayMs)
            }
        }
    }

    private fun reportTickError(error: Exception) {
        // Diagnostic getters can fail for the same reason as tick(). Never let
        // them hide the original failure, and never swallow cancellation/errors.
        fun <T> readSafely(read: () -> T): T? = try {
            read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val player = readSafely { active() }
        AutomixTelemetry.event(
            "mix.tick.error",
            mapOf(
                "phase" to phase.name,
                "currentId" to readSafely { player?.currentMediaItem?.mediaId },
                "nextId" to readSafely {
                    player?.let { deck ->
                        deck.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
                            ?.let { deck.getMediaItemAt(it).mediaId }
                    }
                },
                "positionMs" to readSafely { player?.currentPosition },
                "exception" to error.javaClass.name,
                // Frames identify the throw site without messages containing URLs/tokens.
                "origin" to error.stackTrace.take(4).joinToString(" <- "),
            ),
        )
    }

    fun release() {
        MixInspectorFeed.setRemixAction(null)
        uiStateJob?.cancel()
        uiStateJob = null
        mixExecutor.dispatch(MixEvent.UserSkip)
        pairPreparer?.release()
        clearAutoMixVisual()
        tickerJob?.cancel()
        tickerJob = null
        analysisCollectorJob?.cancel()
        analysisCollectorJob = null
        analysisReplanJob?.cancel()
        analysisReplanJob = null
        loopBuildJob?.cancel()
        loopAlignJob?.cancel()
        loopSession?.let {
            it.outPort?.disarm()
            it.inPort?.disarm()
            closeLater(it.renderer)
        }
        loopSession = null
        loopBuildStartedAtMs = 0L
        disarmTailLoopSession()
        tempoEngine.endEase(clickless = false)
        releaseHold()
        listeningTo?.removeListener(listener)
        listeningTo = null
        active().volume = 1f
        AppSettings.smartMixInProgress.value = false
        AppSettings.smartMixBlend.value = null
        clearMixView()
        AppSettings.smartMixPreview.value = null
        openFiltersWhenSilent()
        filters.parkEchoes()
        onBlendHeadroom(1f)
    }

    // ---- Entry points -------------------------------------------------------

    /**
     * A skip the listener asked for: drop any blend in flight and get out of
     * the way.
     *
     * Crossfade is deliberately a property of tracks *running out*, not of
     * being changed. Blending a manual skip means the song just left behind
     * stays audible over the one that was asked for, which reads as the app
     * ignoring the button rather than as a transition — the point of pressing
     * next is usually to stop hearing the current track.
     *
     * Called before the skip is carried out, so the outgoing track is already on
     * its way down as the new one starts, and the listener's own seek lands on a
     * player this class has finished with.
     */
    fun onSkipRequested() {
        tempoEngine.cancelPendingSpeedChange()
        cancelAutoMixVisual()
        mixExecutor.dispatch(MixEvent.UserSkip)
        if (phase != Phase.IDLE) bail(force = true)
    }

    // ---- Ticker -------------------------------------------------------------

    private fun publishTickState() {
        val player = active()
        val current = player.currentMediaItem
        val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { player.getMediaItemAt(it) }
        val enabled = AppSettings.smartFadeEnabled.value
        val state = mapOf<String, Any?>(
            "phase" to phase.name,
            "currentId" to current?.mediaId,
            "nextId" to next?.mediaId,
            "smartFadeEnabled" to enabled,
            "isPlaying" to player.isPlaying,
            "playWhenReady" to player.playWhenReady,
            "playbackState" to player.playbackState,
            "durationMs" to player.duration,
            "repeatMode" to player.repeatMode,
            "currentVideoOrigin" to current?.isVideoOrigin,
            "nextVideoOrigin" to next?.isVideoOrigin,
            "versionSwapActive" to versionSwapActive(),
            "inParty" to ListenTogether.state.value.inParty,
            "bitPerfect" to BitPerfectOutput.status.value.active,
        )
        val now = nowMs()
        if (state != lastTickState || (enabled && now - lastTickStateAtMs >= 30_000L)) {
            AutomixTelemetry.event("mix.state", state + ("positionMs" to player.currentPosition))
            lastTickState = state
            lastTickStateAtMs = now
        }
    }

    private fun tick() {
        if (visualGeneration != null && !AppSettings.smartFadeEnabled.value) cancelAutoMixVisual()
        // A pause has to take the other player with it, or one half of the blend
        // carries on alone over a stopped one. Mirrored every tick rather than
        // handled as an event, so audio focus loss, the sleep timer and the
        // pause button all get the same treatment for free. Which player follows
        // which flips at the handoff, halfway through the blend: before it the
        // incoming track shadows the session, after it the outgoing tail does.
        if (phase == Phase.FADING || phase == Phase.BAILING) {
            if (handedOff) {
                outgoing?.playWhenReady = incoming?.playWhenReady ?: true
            } else {
                outgoing?.playWhenReady?.let { incoming?.playWhenReady = it }
            }
        }

        // Every tick, not only when a transition can be planned. This used to
        // live inside [considerSmartTransition], which needs an idle phase, a
        // playing player and a known duration — none of which hold during a
        // transition or during the re-buffer after a quality upgrade. The line
        // simply froze on the previous pair, so a track that had not been
        // analysed kept showing the *departing* track's "analysed" until
        // ticking resumed.
        publishAnalysisState()
        publishAutomixDetail()
        expireMixPreview()
        checkHandoffPauseWatch()
        if (phase != Phase.IDLE && AppSettings.smartFadeEnabled.value &&
            !ListenTogether.state.value.inParty && !BitPerfectOutput.status.value.active &&
            !versionSwapActive()
        ) {
            val mState = mixExecutor.currentState()
            if (mState.pair != null && mState !is MixState.Done && mState !is MixState.Failed && mState !is MixState.Suspended) {
                val tickPlayer = active()
                mixExecutor.dispatch(
                    MixEvent.Tick(
                        positionMs = tickPlayer.currentPosition.coerceAtLeast(0L),
                        isPlaying = tickPlayer.isPlaying,
                    ),
                )
            }
        }

        if (phase == Phase.IDLE) tempoEngine.driveEase()

        val phaseAtTickStart = phase
        when (phase) {
            Phase.IDLE -> considerAutoTransition()
            Phase.ARMING -> driveArming()
            Phase.FADING -> driveFade()
            Phase.TAIL -> driveTail()
            Phase.SLEEP_FADE -> driveSleepFade()
            Phase.BAILING -> driveBail()
            Phase.LOOP_ARMING -> driveLoopArming()
            Phase.LOOP_RUNNING -> driveLoopMix()
        }
        // Only run watchdog when tick started in IDLE or LOOP_ARMING so an
        // in-tick arm-expired cancelArming() -> finish() transition to IDLE
        // is not immediately overridden back into ARMING on that same tick.
        if (phaseAtTickStart == Phase.IDLE || phaseAtTickStart == Phase.LOOP_ARMING) {
            checkHardWatchdog()
        }
        try {
            publishTickState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best-effort observation must not interrupt core work or its cadence.
        }
    }

    /**
     * Watches for an unexpected paused session player within 3 s after a completed handoff
     * and records `mix.pause.detected` telemetry once per handover.
     */
    private fun checkHandoffPauseWatch() {
        if (phase != Phase.IDLE || lastHandoffCompleteAtMs <= 0L) return
        val now = nowMs()
        if (now - lastHandoffCompleteAtMs > 3000L) {
            lastHandoffCompleteAtMs = 0L
            return
        }
        val sessionPlayer = active()
        if (sessionPlayer.playWhenReady) return
        val sinceHandoff = now - lastHandoffCompleteAtMs
        lastHandoffCompleteAtMs = 0L
        AutomixTelemetry.event(
            "mix.pause.detected",
            mapOf(
                "site" to "post-handoff",
                "playWhenReady" to sessionPlayer.playWhenReady,
                "playbackState" to sessionPlayer.playbackState,
                "isPlaying" to sessionPlayer.isPlaying,
                "volume" to sessionPlayer.volume,
                "changeReason" to lastPlayWhenReadyChangeReason,
                "msSinceHandoff" to sinceHandoff,
                "positionMs" to sessionPlayer.currentPosition,
                "loopIncomingStarted" to loopIncomingStarted,
            ),
        )
    }

    /**
     * Arms a crossfade as the playing track runs out, and holds the track's
     * end for it ahead of that — see [holdAhead]. Whatever path through
     * [planAutoTransition] decides no transition is coming lets go of the
     * hold, which is free.
     */
    private fun considerAutoTransition() {
        val player = active()
        // Paused or buffering: the transition is no nearer, and a hold already
        // in place is still exactly as right as it was. The seek bar's marker
        // is the exception — see [refreshMarkerWhilePaused].
        if (!player.isPlaying) {
            refreshMarkerWhilePaused(player)
            return
        }
        holdWanted = false
        planAutoTransition(player)
        if (!holdWanted && phase == Phase.IDLE) releaseHold()
    }

    /**
     * Puts the Automix window on the seek bar for a track that is not playing.
     *
     * The marker comes out of the same plan that arms a transition, and that
     * plan only ran while playing — so a paused track never got one: a song
     * restored paused when the app opens, or paused before its first tick
     * planned, showed a bare bar with both tracks analysed. This plans for the
     * marker alone, at most once a [PAUSED_MARKER_STEP_MS]; nothing is armed
     * or held, and anything that would clear the marker while
     * playing is left for the playing path to decide.
     */
    private fun refreshMarkerWhilePaused(player: ExoPlayer) {
        if (!AppSettings.smartFadeEnabled.value) return
        val now = nowMs()
        // The mix popup reads the same plan, and someone looking at it is
        // waiting on the next answer rather than glancing at a marker.
        val step = if (AppSettings.mixViewOpen.value) IDLE_STEP_MS else PAUSED_MARKER_STEP_MS
        if (now - pausedMarkerAt < step) return
        pausedMarkerAt = now
        if (versionSwapActive() || ListenTogether.state.value.inParty) return
        if (!player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ONE) return
        // A queue restored paused is not prepared yet, so the player has no
        // duration until play: the runtime the item was queued with stands in,
        // then the analysed one.
        val item = player.currentMediaItem ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: queuedDurationMs(item).takeIf { it > 0L }
            ?: (analysisFor(item).duration * 1000).roundToLong().takeIf { it > 0L }
            ?: return
        considerSmartTransition(duration, markerOnly = true)
    }

    private var pausedMarkerAt = 0L

    /**
     * Where the next transition out of a track starts, in media ms, as the
     * last idle tick planned it — or null when that track has no crossfade or
     * Automix transition coming and simply runs to its end.
     *
     * Read by [DirectSourceGate]'s window, which opens the next track's
     * resolve a fixed time before the blend rather than before the file end.
     */
    fun plannedTransitionFor(mediaId: String): Long? =
        plannedTransition?.takeIf { it.first == mediaId }?.second

    private var plannedTransition: Pair<String, Long>? = null

    private fun planAutoTransition(player: ExoPlayer) {
        plannedTransition = null
        // Not while a version swap owns the standby player — see
        // [versionSwapActive]. Nothing to clean up on the way out unlike the
        // party case below: a version swap is a between-tracks affair on the
        // same item, so the transition window and mix flag it would have
        // armed still describe the next track correctly once the swap lets go.
        if (versionSwapActive()) return
        // Not while listening together. A blend starts the next track early, by
        // a length this device decides for itself from its own copy of the
        // audio — so in a party every member would begin the next song at a
        // different moment, and each would then be dragged back by a correcting
        // seek. The transition a party shares is the plain one: whoever reaches
        // the end first publishes the change and everybody moves together. See
        // [PartySync].
        //
        // The analysis behind it stops too, including a pass already running —
        // see [com.music.bitchord.playback.smart.TrackAnalyzer], which reads
        // the party for itself. Nothing here asks for one while this returns,
        // but a request made a tick before the party started would otherwise
        // run to completion: a whole-track decode and two model passes, spent
        // on a transition that cannot happen.
        if (ListenTogether.state.value.inParty) {
            // Left behind by the last pair planned before the party started.
            // The marker describes a transition that is no longer going to
            // happen, and the flag a mix that is no longer running; both would
            // otherwise sit on screen for as long as the party lasts.
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixInProgress.value = false
            return
        }
        // Bit-perfect output carries one stream to the DAC, unchanged. A blend
        // needs two, with gain on both, so the queue plays gapless instead.
        if (BitPerfectOutput.status.value.active) {
            AppSettings.smartTransitionWindow.value = null
            return
        }
        // Nothing to transition *into*, so any analysis state left over from the
        // previous pair is stale — the last track of a queue should not still be
        // claiming both songs are measured.
        if (!player.hasNextMediaItem()) {
            AppSettings.smartTransitionWindow.value = null
            return
        }

        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return

        // Repeating one track would crossfade it into itself, so nothing is
        // armed and no window is marked — but the queue behind the loop has not
        // moved, and what sits after it is still the track that plays next the
        // moment repeat-one comes off.
        //
        // Returning here outright is what made turning repeat off look like it
        // lost an analysis. Analysis is only ever asked for on the way to
        // planning a transition, so for as long as the loop ran nothing asked
        // for the following track at all, and the request that finally arrived
        // when repeat came off was the *first* one — a whole-track decode
        // starting from nothing on a song that was by then seconds away, where
        // an unlooped queue would have had it measured minutes earlier. The
        // measurement is the same either way, so it may as well be made during
        // the loop rather than after it.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) {
            if (AppSettings.smartFadeEnabled.value) requestAnalysisAround(player, duration)
            // Stale otherwise: the marker would keep describing the transition
            // planned for this pair before the loop went on, at a point the
            // playhead now runs past on every lap without anything happening.
            AppSettings.smartTransitionWindow.value = null
            return
        }

        // Automix is its own on/off, independent of the manual crossfade
        // length: it decides its own duration from each pair of tracks (beats,
        // tempo, structure), so requiring a nonzero [AppSettings.crossfadeSeconds]
        // first would tie an automatic feature to a manual one it doesn't use.
        if (AppSettings.smartFadeEnabled.value) {
            considerSmartTransition(duration)
            return
        }
        discardPrime()

        if (configuredFadeMs() <= 0L) return
        val fade = fadeFor(duration)
        if (fade <= 0L) return

        player.currentMediaItem?.mediaId?.let { plannedTransition = it to duration - fade }
        val remaining = duration - player.currentPosition
        if (SleepTimer.afterTrack.value) {
            // The sleep timer is due when this track ends. There is no next
            // track to arm a crossfade into, so run the same fade-out this
            // pair would otherwise have used against silence instead — see
            // [beginSleepFadeOut]. No arm-lead margin: nothing is buffering.
            if (remaining > fade) return
            beginSleepFadeOut(fade)
            return
        }
        holdAhead(player, duration)
        // Arm early: the standby has to open the incoming track and buffer to
        // its cue point, and that work has to be finished by the time the fade
        // is due rather than started then.
        if (remaining > fade + ARM_LEAD_MS) return

        begin(fade, endMs = duration, smart = false)
    }

    /**
     * Arms a Automix transition once its plan says the playhead is close
     * enough to start arming for it.
     *
     * Reads the plan's timing (where the fade starts and how long it runs),
     * where the incoming track should be cued
     * ([com.music.bitchord.playback.smart.TransitionPlan.incomingCueTime]),
     * and the tempo-stretch to align it with the outgoing track
     * ([com.music.bitchord.playback.smart.TransitionPlan.incomingPlaybackRate])
     * — see [driveLap], which applies both at the handoff — and the style the
     * blend is rendered in
     * ([com.music.bitchord.playback.smart.TransitionPlan.transitionStyle]),
     * which [rideFilters] turns into a filter ride or a bass swap over the same
     * equal-power gain curve.
     */
    private fun considerSmartTransition(duration: Long, markerOnly: Boolean = false) {
        val player = active()
        val currentItem = player.currentMediaItem ?: return
        val nextIndex = player.nextMediaItemIndex
        val activePrepStates = setOf(
            MixStateKind.PREPARING, MixStateKind.MOVED, MixStateKind.READY,
            MixStateKind.ARMED, MixStateKind.LAST_RESORT,
        )
        if (nextIndex == C.INDEX_UNSET || nextIndex >= player.mediaItemCount) {
            val mState = mixExecutor.currentState()
            if (mState.pair != null && mState.stateKind in activePrepStates) {
                mixExecutor.dispatch(MixEvent.QueueEdited(null, player.currentPosition.coerceAtLeast(0L)))
            }
            AppSettings.smartTransitionWindow.value = null
            return
        }
        val nextItem = player.getMediaItemAt(nextIndex)
        ensureOpportunity(currentItem.mediaId, nextItem.mediaId)
        if (currentItem.isVideoOrigin || nextItem.isVideoOrigin) {
            val mState = mixExecutor.currentState()
            if (mState.pair != null && mState.stateKind in activePrepStates) {
                mixExecutor.dispatch(MixEvent.QueueEdited(null, player.currentPosition.coerceAtLeast(0L)))
            }
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixInProgress.value = false
            return
        }
        val nextDuration = nextItemDurationMs(nextIndex, nextItem)
        val posMs = player.currentPosition.coerceAtLeast(0L)

        requestAnalysisAround(player, duration)
        askLyrics(currentItem, duration)
        askLyrics(nextItem, nextDuration)

        if (SleepTimer.afterTrack.value) {
            val sleepFade = configuredFadeMs().takeIf { it > 0L }
                ?: (DEFAULT_SMART_FALLBACK_SECONDS * 1000.0).roundToLong()
            if (!markerOnly && duration - posMs <= sleepFade) {
                beginSleepFadeOut(sleepFade)
            }
            return
        }

        val pair = PairKey(currentItem.mediaId, nextItem.mediaId)
        val coreLevel = toCoreLevel(AppSettings.automixDjLevel.value)
        var mState = mixExecutor.currentState()
        if (mState.djLevel != coreLevel) {
            mState = mixExecutor.dispatch(MixEvent.LevelChanged(coreLevel)).first
        }

        val nativeAvailable = isNativeRendererAvailable(player)
        if (pairPreparer != null && nativeAvailable) {
            val inActivePrep = mState.stateKind in activePrepStates
            when {
                mState is MixState.Idle ||
                    (mState is MixState.Done && mState.pair != pair) ||
                    (mState is MixState.Failed && mState.pair != pair) ||
                    (mState is MixState.Suspended && mState.pair != pair) -> {
                    classicFallbackReason = ""
                    classicHandledGeneration = -1L
                    mState = mixExecutor.dispatch(
                        MixEvent.NextKnown(pair, posMs, coreLevel, player.isPlaying),
                    ).first
                }
                inActivePrep && mState.pair?.outTrackId != pair.outTrackId -> {
                    classicFallbackReason = ""
                    classicHandledGeneration = -1L
                    mState = mixExecutor.dispatch(
                        MixEvent.NextKnown(pair, posMs, coreLevel, player.isPlaying),
                    ).first
                }
                inActivePrep && mState.pair?.inTrackId != pair.inTrackId -> {
                    classicFallbackReason = ""
                    classicHandledGeneration = -1L
                    mState = mixExecutor.dispatch(
                        MixEvent.QueueEdited(pair.inTrackId, posMs),
                    ).first
                }
            }
            if (mState.pair == pair && mState !is MixState.Failed &&
                mState !is MixState.Suspended && mState !is MixState.Done
            ) {
                mState = mixExecutor.dispatch(MixEvent.Tick(posMs, player.isPlaying)).first
            }

            if (phase != Phase.IDLE) return

            if (mState !is MixState.Failed) {
                if (mState is MixState.Suspended) {
                    AppSettings.smartTransitionWindow.value = null
                    plannedTransition = null
                    return
                }
                val v1Plan = mState.plan
                val targetStartMs = when (mState) {
                    is MixState.Armed -> mState.armAtMs
                    is MixState.LastResort -> mState.targetDownbeatMs
                    is MixState.Moved -> mState.plan?.frozenExitMs ?: mState.newExitMs
                    is MixState.Ready -> mState.plan.frozenExitMs
                    is MixState.Preparing -> mState.plan?.frozenExitMs
                    else -> null
                }
                val targetSpanMs = v1Plan?.overlapDurationMs?.coerceAtLeast(1_000L) ?: 0L
                val windowAllowed = nowMs() >= scrubSettledAtMs || duration - posMs <= 5_000L
                if (v1Plan != null && targetStartMs != null && targetStartMs > 0L && duration > 0L) {
                    lastSmartPlanStartMs = targetStartMs
                    plannedTransition = currentItem.mediaId to targetStartMs
                    val prepared = pairPreparer.snapshotFor(pair.outTrackId, pair.inTrackId)
                    prepared?.plan?.let { p ->
                        loopPlanned = p
                        loopPlanKey = "${pair.outTrackId}->${pair.inTrackId}|${p.out.loopStart}"
                        if (AppSettings.mixViewOpen.value && AppSettings.smartMixView.value == null) {
                            prefetchMixWaves(
                                currentItem, nextItem, duration, nextDuration,
                                p.out.loopStart,
                                (p.out.loopStart + LoopMix.Clock(p).releaseAt).coerceAtLeast(p.out.loopStart + 0.5),
                                p.inn.loopStart,
                                p.inn.bpm / p.out.bpm.coerceAtLeast(1.0),
                            )
                        }
                    }
                    AppSettings.smartTransitionWindow.value = if (windowAllowed && targetStartMs < duration) {
                        val s = (targetStartMs.toDouble() / duration).toFloat().coerceIn(0f, 1f)
                        val e = ((targetStartMs + targetSpanMs).toDouble() / duration).toFloat().coerceIn(s, 1f)
                        TransitionWindow(start = s, end = e)
                    } else null
                    holdAhead(player, duration)
                } else {
                    AppSettings.smartTransitionWindow.value = null
                    plannedTransition = null
                }
                return
            }
        } else if (!nativeAvailable && mState.pair != null && mState.stateKind in activePrepStates) {
            val reason = nativeUnavailabilityReason(player)
            classicFallbackReason = reason
            mixExecutor.dispatch(MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, reason, recoverable = false))
            mState = mixExecutor.currentState()
        }

        // Classic two-player crossfade runs strictly when the native renderer is unavailable or failed.
        val reason = when {
            !nativeAvailable -> nativeUnavailabilityReason(player)
            mState is MixState.Failed -> mState.message.ifBlank { mState.reason?.name?.lowercase() ?: mState.kind.name.lowercase() }
            else -> "preparer-unavailable"
        }
        classicFallbackReason = reason
        classicFallbackOnRendererUnavailable(
            player = player,
            currentItem = currentItem,
            nextItem = nextItem,
            duration = duration,
            nextDuration = nextDuration,
            reason = reason,
            markerOnly = markerOnly,
            failedPlan = (mState as? MixState.Failed)?.plan,
        )
    }

    private fun classicFallbackOnRendererUnavailable(
        player: ExoPlayer,
        currentItem: MediaItem,
        nextItem: MediaItem,
        duration: Long,
        nextDuration: Long,
        reason: String,
        markerOnly: Boolean,
        failedPlan: TransitionPlanV1?,
    ) {
        val posMs = player.currentPosition.coerceAtLeast(0L)
        val reasonKey = "${currentItem.mediaId}->${nextItem.mediaId}|$scrubEpoch|$reason"
        if (reasonKey != lastFallbackLogKey) {
            lastFallbackLogKey = reasonKey
            AutomixTelemetry.event(
                "mix.classic.reason",
                mapOf(
                    "reason" to reason,
                    "outId" to currentItem.mediaId,
                    "inId" to nextItem.mediaId,
                    "stage" to "consider-smart",
                    "rendererAvailable" to LoopMixRenderer.available,
                ),
            )
        }
        val pairKey = "${currentItem.mediaId}->${nextItem.mediaId}"
        val currentAnalysis = analysisFor(currentItem)
        val nextAnalysis = analysisFor(nextItem)
        val analysisState = AppSettings.smartAnalysis.value
        val recoveryRequired = countdownStabilizer.hasFailedCommittedNative(pairKey, scrubEpoch)
        val bothAnalyzed = currentAnalysis.isUsable && nextAnalysis.isUsable
        val coarseDecline = bothAnalyzed && shouldDeclineToMix(currentAnalysis, nextAnalysis)

        if (failedPlan == null && !recoveryRequired && (!bothAnalyzed || coarseDecline)) {
            val naturalPlan = TransitionPlan(
                shouldStart = false,
                markerVisible = false,
                blocked = true,
                reason = if (!bothAnalyzed) "classic:analysis-pending" else "classic:declined",
                transitionStart = duration / 1000.0,
                transitionEnd = duration / 1000.0,
                fadeSeconds = 0.0,
                transitionStyle = TransitionStyle.NATURAL,
                beatSeconds = 0.0,
                mixSummary = "Natural handoff ($reason)",
            )
            lastSmartPlan = naturalPlan
            lastSmartPlanStartMs = 0L
            planGeneratedAtMs = nowMs()
            plannedTransition = null
            AppSettings.smartTransitionWindow.value = null
            acceptRoute(naturalPlan, classicRouteFields(naturalPlan) + ("technique" to "natural"))
            publishMixPreview(currentItem, nextItem, duration, nextDuration, currentAnalysis, nextAnalysis, analysisState, naturalPlan, true)
            return
        }

        val fallbackSec = configuredFadeMs().takeIf { it > 0L }?.div(1000.0) ?: DEFAULT_SMART_FALLBACK_SECONDS
        val outEndMs = failedPlan?.let { (it.frozenExitMs + it.overlapDurationMs).coerceIn(1_500L, duration) }
            ?: (currentAnalysis.contentEndTime.takeIf { it.isFinite() && it > 1.0 }?.times(1000.0))
                ?.roundToLong()?.coerceIn(1_500L, duration)
            ?: duration
        val rawFadeMs = failedPlan?.overlapDurationMs?.takeIf { it >= 1_200L }
            ?: (fallbackSec * 1000.0).roundToLong()
        val maxFadeForMode = if (failedPlan == null && recoveryRequired && coarseDecline) 4_000L else rawFadeMs
        val fadeMs = minOf(rawFadeMs, maxFadeForMode, (outEndMs - 500L).coerceAtLeast(1_200L)).coerceAtLeast(1_200L)
        val startMs = (outEndMs - fadeMs).coerceAtLeast(0L)
        val beatSec = currentAnalysis.beatInterval.takeIf { it.isFinite() && it in 0.25..1.5 }
            ?: currentAnalysis.bpm.takeIf { it.isFinite() && it > 40.0 }?.let { 60.0 / it }
            ?: 0.5
        val inCueMs = failedPlan?.frozenEntryMs
            ?: (nextAnalysis.mixInTime.takeIf { it.isFinite() && it >= 0.0 }?.times(1000.0))?.roundToLong()
            ?: 0L
        val classicPlan = if (failedPlan == null && recoveryRequired) {
            TransitionPlan(
                shouldStart = true,
                markerVisible = true,
                blocked = false,
                reason = if (coarseDecline) "classic:decline-recovery:$reason" else "classic:native-recovery:$reason",
                transitionStart = startMs / 1000.0,
                transitionEnd = outEndMs / 1000.0,
                fadeSeconds = fadeMs / 1000.0,
                transitionStyle = TransitionStyle.DJ_FILTER,
                incomingCueTime = inCueMs / 1000.0,
                incomingPlaybackRate = 1.0,
                outgoingPlaybackRate = 1.0,
                bassSwap = !coarseDecline,
                filterSweep = 1.0,
                filterVariant = if (coarseDecline) FilterTransitionVariant.DECLINE else FilterTransitionVariant.SWEEP,
                beatSeconds = beatSec,
                phaseLock = false,
                echoSeconds = 0.0,
                djEffect = com.music.bitchord.playback.smart.DjEffect.NONE,
                mixSummary = if (coarseDecline) "Filter decline ($reason)" else "Recovery crossfade ($reason)",
            )
        } else {
            val classicStyle = if (failedPlan == null && bothAnalyzed && !coarseDecline) {
                TransitionStyle.DJ_BLEND
            } else {
                TransitionStyle.EQUAL_POWER
            }
            TransitionPlan(
                shouldStart = true,
                markerVisible = true,
                blocked = false,
                reason = "classic:$reason",
                transitionStart = startMs / 1000.0,
                transitionEnd = outEndMs / 1000.0,
                fadeSeconds = fadeMs / 1000.0,
                transitionStyle = classicStyle,
                incomingCueTime = inCueMs / 1000.0,
                incomingPlaybackRate = 1.0,
                outgoingPlaybackRate = 1.0,
                bassSwap = classicStyle == TransitionStyle.DJ_BLEND,
                beatSeconds = beatSec,
                phaseLock = false,
                mixSummary = "Classic crossfade ($reason)",
            )
        }
        lastSmartPlan = classicPlan
        lastSmartPlanStartMs = startMs
        planGeneratedAtMs = nowMs()
        acceptRoute(classicPlan, classicRouteFields(classicPlan))
        publishMixPreview(currentItem, nextItem, duration, nextDuration, currentAnalysis, nextAnalysis, analysisState, classicPlan, true)

        val readyToMark = (failedPlan != null ||
            (analysisState.current == TrackAnalysisState.ANALYSED && analysisState.next in MEASURED_ENOUGH_TO_ENTER_ON)) &&
            (nowMs() >= scrubSettledAtMs || duration - posMs <= 5_000L)
        AppSettings.smartTransitionWindow.value = if (readyToMark && startMs < duration) {
            TransitionWindow(
                start = (startMs.toDouble() / duration).toFloat().coerceIn(0f, 1f),
                end = (outEndMs.toDouble() / duration).toFloat().coerceIn(0f, 1f),
            )
        } else null
        if (markerOnly) return
        plannedTransition = currentItem.mediaId to startMs
        holdAhead(player, duration)
        val effectiveLeadMs = maxOf(blendArmLeadMs, if (!nextStreamReady(nextItem)) 25_000L else 0L)
        if (startMs - posMs > effectiveLeadMs) return
        begin(
            fade = fadeMs,
            endMs = outEndMs,
            smart = true,
            cueTimeMs = inCueMs,
            playbackRate = 1.0,
            outgoingRate = 1.0,
            beatMs = (beatSec * 1000.0).roundToLong(),
            renderStyle = renderFor(classicPlan, true),
            plan = classicPlan,
        )
    }

    private fun renderFor(plan: TransitionPlan, advanced: Boolean) = Render(
        style = plan.transitionStyle,
        bassSwap = plan.bassSwap,
        bassSwapFraction = plan.bassSwapFraction,
        filterSweep = plan.filterSweep,
        filterVariant = plan.filterVariant,
        vocalOverlap = plan.vocalOverlap,
        advanced = advanced,
        beats = if (plan.beatSeconds > 0) plan.fadeSeconds / plan.beatSeconds else 0.0,
        lockPhase = plan.phaseLock,
        echoSeconds = plan.echoSeconds,
        djEffect = plan.djEffect,
        vocalHandoff = plan.vocalHandoff,
        beatSecondsF = plan.beatSeconds.toFloat(),
        buildup = plan.buildup,
        muffleLeadBeats = plan.muffleLeadBeats,
        exitBeats = plan.exitBeats,
        vocalDepthCurve = plan.vocalDepthCurve,
    )

    /**
     * Queues the playing track and the one queued after it for analysis.
     *
     * Cheap no-ops once a track is analysed or already in flight; called every
     * tick so a track that finishes caching mid-song is picked up without a
     * separate trigger.
     *
     * Not folded into [considerSmartTransition], because the pair still needs
     * measuring in the one case that never plans a transition at all: a track
     * on repeat-one, which will hand over to this same next track as soon as
     * the loop is switched off.
     */
    private fun requestAnalysisAround(player: ExoPlayer, duration: Long) {
        val currentItem = player.currentMediaItem ?: return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val nextItem = player.getMediaItemAt(nextIndex)
        if (currentItem.isVideoOrigin || nextItem.isVideoOrigin) return
        requestAnalysis(currentItem, duration)
        // djmix20 analyse-ahead (P0-1): next AND next+1 are queued
        // immediately (the architecture already supports next+1 lookahead).
        // The old 12 s post-mix settle gate starved the incoming entry half
        // (phrase-null tried=0 / before-smart fallback) whenever a skip or
        // quick mix followed a handover. TrackAnalyzer.request is a cheap
        // no-op when already analysed/in-flight, and head work runs on its
        // own single-thread executor, so eager queueing costs nothing when
        // warm and wins the whole lead when cold.
        requestAnalysis(nextItem, nextItemDurationMs(nextIndex, nextItem))
        askLyrics(currentItem, duration)
        askLyrics(nextItem, nextItemDurationMs(nextIndex, nextItem))
        val after = nextIndex + 1
        if (after < player.mediaItemCount) {
            val later = player.getMediaItemAt(after)
            if (!later.isVideoOrigin) {
                val laterDuration = nextItemDurationMs(after, later)
                askLyrics(later, laterDuration)
                requestAnalysis(later, laterDuration)
            }
        }
    }

    /**
     * Keeps the stats line describing the pair that is actually playing.
     *
     * Cheap enough to run unconditionally — two concurrent-map lookups and a
     * set membership test — and running it unconditionally is the point: any
     * gating reintroduces the staleness this exists to remove.
     */
    private fun publishAnalysisState() {
        val player = active()
        val currentItem = player.currentMediaItem
        val nextIndex = player.nextMediaItemIndex
        val nextItem = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
        val outAnalysis = currentItem?.let { analysisFor(it) }
        val inAnalysis = nextItem?.let { analysisFor(it) }
        AppSettings.smartAnalysis.value = SmartAnalysis(
            current = currentItem?.let { stateOf(it, outAnalysis ?: TrackAnalysis()) } ?: TrackAnalysisState.WAITING,
            next = nextItem?.let { stateOf(it, inAnalysis ?: TrackAnalysis()) } ?: TrackAnalysisState.WAITING,
        )
        // Safety net for hosts that do not pass [analysisUpdates]: the tick's
        // own observation of a fresher analysis feeds the same debounced
        // re-plan path, so an analysis landing mid-song still replaces a
        // provisional NATURAL instead of leaving it latched.
        val signature = listOf(
            currentItem?.mediaId.orEmpty(), nextItem?.mediaId.orEmpty(),
            outAnalysis?.status.orEmpty(), outAnalysis?.quality.orEmpty(),
            inAnalysis?.status.orEmpty(), inAnalysis?.quality.orEmpty(),
            outAnalysis?.bpm ?: 0.0, inAnalysis?.bpm ?: 0.0,
        ).joinToString("|")
        val previous = lastPairAnalysisSignature
        lastPairAnalysisSignature = signature
        if (previous != null && previous != signature) {
            // Either side of the pair changed; passing the outgoing id is enough
            // for onPairAnalysisChanged to recognise the pending pair.
            val pairId = currentItem?.mediaId ?: nextItem?.mediaId
            if (!pairId.isNullOrBlank()) onPairAnalysisChanged(pairId)
        }
    }

    /**
     * Where one track stands, for the stats line. "Analysing" is asked for
     * first because a track can be in flight while a superseded provisional
     * result is already on record, and the work in progress is the more useful
     * thing to say about it.
     */
    private fun stateOf(item: MediaItem, analysis: TrackAnalysis): TrackAnalysisState = when {
        // Usable first, and a pass in flight *second*. The other order was
        // right up to the point a head-only result started arriving before the
        // whole-track one: a track measured off its opening reads as analysed,
        // then finishes caching, then has the full pass run over it to replace
        // the provisional numbers — and reported "analysing" again throughout.
        // Going backwards from analysed reads as something having broken, when
        // what is happening is a better answer being computed. Confidence on one
        // such track went 0.39 to 0.94 and its cue moved from 0.1s to 9.5s.
        analysis.isUsable ->
            if (analysisRunningFor(item)) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSED
        analysisRunningFor(item) -> TrackAnalysisState.ANALYSING
        // A recorded-but-unusable result is the analyzer's way of saying it
        // tried and got nothing, and that it will not try again — it writes a
        // ready-but-empty entry precisely so the track stops being retried. A
        // track nothing has looked at yet has no status at all, which is the
        // only case that is still merely waiting.
        analysis.status == TrackAnalysis.STATUS_READY -> TrackAnalysisState.FAILED
        else -> TrackAnalysisState.WAITING
    }

    /**
     * The next queue item's own duration.
     *
     * Media3 fills a timeline window's duration in when the item is *prepared*,
     * which for the track after this one happens a few seconds before it starts
     * playing. So for almost the whole of the current track this answered zero —
     * and zero is not a harmless "don't know" downstream. It reaches
     * [com.music.bitchord.playback.smart.TrackAnalyzer.request] as the next
     * track's duration, and with no duration to check a sibling copy against the
     * analyzer will only read the rendition the cache key resolves to *right
     * now*, which with source substitution on is the `#alt` entry — while the
     * copy actually on disk is the plain one its own head fetch just pulled
     * down. Nothing matches, the pass returns silently, and it does that on every
     * tick for the rest of the track. Measured: a fully cached next track sat
     * unread for three minutes and was analysed eight seconds before the fade it
     * was meant to inform, having been analysable the whole time.
     *
     * A zero here means *unknown*, not *no such track*: the analyser is still
     * asked about [item] by mediaId/trackId, so its cache's sibling-rendition
     * lookup runs regardless of the number returned. The runtime is on the item
     * already — queued from a row that knew it and carried on the playback URI
     * as `d=` (see `Song.matchQuery`) — and a stored analysis is another hint.
     * Both are hints, never preconditions: a missing one leaves 0 (unknown), and
     * analysis still proceeds with the only copy on disk.
     */
    private fun nextItemDurationMs(nextIndex: Int, item: MediaItem): Long {
        val timeline = active().currentTimeline
        if (!timeline.isEmpty) {
            timeline.getWindow(nextIndex, Timeline.Window()).durationMs
                .takeIf { it != C.TIME_UNSET && it > 0 }
                ?.let { return it }
        }
        return queuedDurationMs(item).takeIf { it > 0L }
            ?: (analysisFor(item).duration * 1000).roundToLong().takeIf { it > 0L }
            ?: 0L // unknown: requestAnalysis still runs, keyed by mediaId
    }

    /**
     * The runtime the queue row carried, in milliseconds, or 0 when the item
     * doesn't state one — a local file, or a track queued without a duration.
     *
     * Deliberately forgiving: [Uri.getQueryParameter] throws on an opaque URI,
     * and a missing or unparsable value is simply an absent duration rather than
     * anything worth failing a tick over.
     */
    private fun queuedDurationMs(item: MediaItem): Long {
        val uri = item.localConfiguration?.uri ?: return 0L
        val seconds = runCatching { uri.getQueryParameter("d") }.getOrNull()?.toLongOrNull() ?: return 0L
        return if (seconds > 0) seconds * 1000L else 0L
    }

    /** BitChord doesn't carry album metadata on [MediaMetadata] yet, so [TransitionTrackInfo.album] stays blank. */
    private fun MediaItem.toTransitionInfo(durationMs: Long) = TransitionTrackInfo(
        id = mediaId,
        durationMs = durationMs,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
    )

    /**
     * Loads the standby player with the queue, positioned on the incoming track
     * at the plan's cue point, and leaves it buffering there silently.
     *
     * Nothing is committed here. The standby is a scratch player until
     * [startFade] runs, so a queue edit, a skip or a pause arriving during
     * arming costs nothing but the decoder it was holding.
     *
     * The cue point is reached by *starting there* rather than by seeking:
     * `setMediaItems` takes the position the item is to begin at, so the
     * incoming track opens at its analyzed mix-in point with no seek, no
     * discontinuity and no frame-rounding. Same for the beatmatch stretch, which
     * is applied before a note has been rendered rather than being switched on
     * underneath one already playing.
     */
    private fun begin(
        fade: Long,
        endMs: Long,
        smart: Boolean,
        cueTimeMs: Long = 0L,
        playbackRate: Double = 1.0,
        outgoingRate: Double = 1.0,
        beatMs: Long = 0L,
        renderStyle: Render = Render(),
        plan: TransitionPlan? = null,
    ): Boolean {
        val out = active()
        val into = standby()
        // djmix17: no silent arm failures — count every guard exit.
        if (out === into) return armDeath("same-player")
        val nextIndex = out.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex >= out.mediaItemCount) return armDeath("no-next-index")
        val localBlend = plan?.takeIf { LocalBlendAdmission.MARKER in it.policyReasons }
        if (localBlend != null && (!LocalBlendAdmission.isQualified(localBlend,
                out.currentMediaItem?.let(analysisFor) ?: TrackAnalysis(), analysisFor(out.getMediaItemAt(nextIndex)),
                out.currentMediaItem?.let { lyricSpans[it.mediaId]?.cues }.orEmpty().toList(),
                lyricSpans[out.getMediaItemAt(nextIndex).mediaId]?.cues.orEmpty().toList()) ||
                !localBlendFitsDeckSpeedCap(AppSettings.playbackSpeed.value.toDouble(), playbackRate) ||
                abs(playbackRate - localBlend.incomingPlaybackRate) > 0.0001 || outgoingRate != 1.0 ||
                abs(cueTimeMs / 1000.0 - localBlend.incomingCueTime) > 0.002 ||
                abs(endMs / 1000.0 - localBlend.transitionEnd) > 0.002 ||
                abs(fade / 1000.0 - localBlend.fadeSeconds) > 0.002)) return armDeath("local-blend-admission-lost")
        val classicBlockedByNative = smart && isNativeRendererAvailable(out) &&
            classicFallbackReason.isBlank() && mixExecutor.currentState() !is MixState.Failed &&
            !SleepTimer.afterTrack.value
        if (classicBlockedByNative) return armDeath("native-renderer-available")
        plannedNextId = out.getMediaItemAt(nextIndex).mediaId
        out.currentMediaItem?.let { ensureOpportunity(it.mediaId, requireNotNull(plannedNextId)) }
        // The last blend's stretch, if it is somehow still easing off: this
        // player is about to be the outgoing side of a new one.
        tempoEngine.endEase(clickless = false)

        // vc67: reset tail telemetry so a stale 800 from the previous
        // transition can never leak into mix.complete after a zero-tail.
        tailDurationMs = 0L
        fadeMs = fade
        fadeEndMs = endMs
        smartFadeActive = smart
        incomingCueTimeMs = cueTimeMs.coerceAtLeast(0L)
        localBlendInFlight = localBlend != null
        incomingPlaybackRate = if (localBlendInFlight) playbackRate
            else com.music.bitchord.playback.smart.audibleTempoRate(playbackRate)
        outgoingPlaybackRate = com.music.bitchord.playback.smart.audibleTempoRate(outgoingRate)
        val rampLeadMs = tempoEngine.configureOutgoingRamp(outgoingRate, beatMs, endMs, fade)
        render = renderStyle
        planInFlight = plan
        armDeadline = nowMs() + ARM_TIMEOUT_MS + rampLeadMs
        armGraceUsed = false
        armOutgoingPreFiltered = false
        handedOff = false
        outgoing = out
        incoming = into
        outgoingItemIndex = out.currentMediaItemIndex

        val t0 = SystemClock.elapsedRealtimeNanos()
        val items = (0 until out.mediaItemCount).map { out.getMediaItemAt(it) }
        val copyDurationUs = (SystemClock.elapsedRealtimeNanos() - t0) / 1000L
        queuedItemCount = items.size
        val perTrackUs = if (items.isNotEmpty()) copyDurationUs.toDouble() / items.size else 0.0
        Log.d(
            TAG,
            "begin() queue copy: ${items.size} tracks in ${copyDurationUs}µs (${String.format(java.util.Locale.US, "%.2f", perTrackUs)}µs/track)",
        )

        Log.d(
            TAG,
            "arm ${if (smart) "smart" else "standard"} fade=${fade}ms end=${endMs}ms " +
                "cue=${incomingCueTimeMs}ms rate=$incomingPlaybackRate at=${out.currentPosition}ms " +
                "style=${render.style} bassSwap=${render.bassSwap}@${render.swapAt} " +
                "advanced=${render.advanced} lock=${render.phaseLock} handoff=${render.handoffAt} " +
                "echo=${if (render.echo) "${render.echoSecondsF}s@${render.echoAt}" else "off"} " +
                "sweep=${render.filterSweep}",
        )

        holdAtEnd(out)

        // Always disable skip-silence on incoming deck consistently across classic and native paths.
        into.skipSilenceEnabled = false
        into.repeatMode = out.repeatMode
        into.shuffleModeEnabled = false
        // Stacks on top of the listener's speed control rather than replacing
        // it, so a beatmatched transition and "play everything at 1.25x" don't
        // fight each other. Undone in [finish].
        // Ordinary requests retain safeDeckSpeed; a locally admitted blend preserves
        // its checked physical rate, set once while this incoming deck is silent.
        // Tempo only. The old pitch corrector turned a small transpose into a
        // time factor of about a thousand, which is the 999x runaway.
        tempoEngine.prepareIncomingSpeed(into, incomingPlaybackRate, localBlendInFlight)
        into.volume = 0f
        // vc77: the incoming track rides on the spare sink; start its filter
        // neutral so no parked cutoff from the last blend muffles its first
        // decoded frames. Gated on the retiree (here the incoming deck
        // itself, just silenced) so Seam A is never violated.
        openSpareFiltersWhenSilent()
        val setNanos0 = SystemClock.elapsedRealtimeNanos()
        into.setMediaItems(items, nextIndex, incomingCueTimeMs)
        val setDurationUs = (SystemClock.elapsedRealtimeNanos() - setNanos0) / 1000L
        Log.d(TAG, "standby setMediaItems: ${items.size} tracks in ${setDurationUs}µs")
        // Before `prepare`, so the standby's per-player audio state is right
        // for the incoming track from its very first decoded frame rather than
        // from the handoff, which is half a blend too late.
        items.getOrNull(nextIndex)?.let { item ->
            val after = into.nextMediaItemIndex
            val afterId = if (after == C.INDEX_UNSET) null else items.getOrNull(after)?.mediaId
            onArmIncoming(item, afterId)
        }
        // Buffers without sounding. Started for real in [startFade].
        into.playWhenReady = false
        into.prepare()
        if (smart) notePrep(into)
        if (smart && plan != null) {
            val outItem = items.getOrNull(outgoingItemIndex)
            val inItem = items.getOrNull(nextIndex)
            if (outItem != null && inItem != null) {
                val outDuration = out.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                val geometry = mixGeometry()
                prefetchMixWaves(
                    outItem, inItem, outDuration, queuedDurationMs(inItem),
                    geometry.outStart, geometry.outEnd, geometry.inStart, geometry.ratio,
                )
            }
        }

        val outId = out.currentMediaItem?.mediaId.orEmpty()
        val inId = into.currentMediaItem?.mediaId ?: plannedNextId.orEmpty()
        if (classicPrepStartMs <= 0L) classicPrepStartMs = nowMs()
        if (classicArmLeadMs <= 0L) classicArmLeadMs = (endMs - fade - out.currentPosition).coerceAtLeast(0L)
        lastTimingPair = outId to inId
        pairPreparer?.markArmed(outId, inId, armLeadMs = classicArmLeadMs)
        phase = Phase.ARMING
        if (smart) {
            armAutoMixVisual(out, into, nextIndex, items)
            items.getOrNull(nextIndex)?.let(onArmAutoMixIncoming)
        }
        acceptRoute(plan ?: "standard-classic", classicRouteFields(plan))
        if (into.playbackState == Player.STATE_READY && into.currentMediaItem?.mediaId == opportunity?.inId) {
            preparedRoute(classicRouteFields(plan))
        }
        return true
    }

    /**
     * Stops [out] reading on into the next track, so the handoff can cut its
     * queue without a break in its sound.
     *
     * A player reads well ahead of what it plays: everything its sink holds,
     * seconds of it on a float route, plus what the decoder has in flight. So
     * the outgoing player starts reading the *next* item — the song fading
     * in — that far before its own track ends, and its audio is already
     * queued behind the last of this one. [handOff] then removes that item,
     * and Media3, finding the period its renderers are reading gone, seeks to
     * where it already is: the sink is flushed and refilled, and the track
     * still sounding through the second half of the blend drops out for a
     * moment. Back when the handoff came at the start of the blend that
     * never happened — the track had a whole fade left to run — which is why
     * the break arrived with the midpoint handoff.
     *
     * Pausing at the end of the item keeps the renderers on this track: they
     * are told it is final, play it out to its last sample and stop. Removing
     * the items behind it then touches nothing being read. It has to be set
     * before the read-ahead reaches the next track, though — setting it once
     * that has happened makes Media3 flush to undo it, the same break, at
     * full volume, just before the blend.
     *
     * Which is what the standard crossfade kept doing. This used to be set
     * at arm time and trusted an estimate of the read-ahead — the sink's lead
     * plus a second and a half for the decoder — and the standard path arms
     * only its fade plus [ARM_LEAD_MS] before the file ends, inside what a
     * deep decoder pipeline (FLAC frames, a software decoder's queue) really
     * reads ahead. Automix mostly escaped it by arming off a mix-out point
     * well clear of the file end. So the estimate is gone: this only holds
     * where Media3 *cannot* yet be reading on — see [MEDIA3_READ_AHEAD_LIMIT_MS]
     * — and [holdAhead] puts it there from the idle tick, for either kind of
     * transition, long before either arms. Where that is too late — a seek
     * into the last seconds — the end is left unheld, and [handOff] leaves
     * the queue alone rather than flush.
     *
     * Released in [finish], by an idle tick that no longer expects a
     * transition, and by [listener] if the track runs out while still the
     * session's.
     */
    private fun holdAtEnd(out: ExoPlayer) {
        if (heldAtEnd === out) return
        val duration = out.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val left = duration - out.currentPosition
        if (left <= maxReadAheadMs(out)) {
            Log.d(TAG, "end not held: ${left}ms left, may already be reading on (${maxReadAheadMs(out)}ms)")
            return
        }
        releaseHold()
        out.pauseAtEndOfMediaItems = true
        heldAtEnd = out
        Log.d(TAG, "end held at ${left}ms left")
    }

    /**
     * Holds the playing track's end for the transition an idle tick has
     * found coming, as soon as it is within reach of [holdAtEnd] — whether or
     * not that transition has armed yet. Arming waits for its own lead;
     * holding cannot, because by then the player may already be reading on.
     */
    private fun holdAhead(player: ExoPlayer, duration: Long) {
        val left = duration - player.currentPosition
        val limit = maxReadAheadMs(player)
        if (left > limit + HOLD_AHEAD_MS) return
        holdWanted = true
        // Silently, past the point it can be done: [begin] says so once, not
        // every idle tick.
        if (left > limit) holdAtEnd(player)
    }

    /** Undoes [holdAtEnd]. Idempotent; never flushes. */
    private fun releaseHold() {
        heldAtEnd?.pauseAtEndOfMediaItems = false
        heldAtEnd = null
    }

    /**
     * The furthest before its track's end, in media ms, that [player] can
     * have started reading the next one: Media3's wall-clock limit at its
     * current speed, and a margin for a position report that is a tick old
     * and the hop to the playback thread.
     */
    private fun maxReadAheadMs(player: ExoPlayer): Long {
        val speed = player.playbackParameters.speed.coerceAtLeast(1f)
        return (MEDIA3_READ_AHEAD_LIMIT_MS * speed).roundToLong() + HOLD_SAFETY_MS
    }

    /**
     * Whether [out], left unheld, has run on from its own track into the one
     * after it — the song fading in, started a second time on the player
     * that is leaving. Never true for a held player: it stops on its track.
     */
    private fun ranOn(out: ExoPlayer): Boolean =
        handedOff && outgoingItemIndex != C.INDEX_UNSET && out.currentMediaItemIndex != outgoingItemIndex

    /**
     * Waits for the standby to have the incoming track ready at its cue point,
     * and for the outgoing track to reach the fade.
     *
     * There is nothing to align here — the two players hold different songs — so
     * this is only ever waiting on a buffer.
     */
    private fun driveArming() {
        val out = outgoing ?: return cancelArming("missing-outgoing-player")
        val into = incoming ?: return cancelArming("missing-incoming-player")
        if (!stillWorthFading()) return cancelArming("mix-disabled-or-no-next")
        val armedPlan = planInFlight
        if (armedPlan != null && LocalBlendAdmission.MARKER in armedPlan.policyReasons &&
            !localBlendFitsDeckSpeedCap(AppSettings.playbackSpeed.value.toDouble(), incomingPlaybackRate)
        ) return cancelArming("local-blend-listener-speed-cap")
        // Paused while armed: the transition is no longer imminent, and holding
        // a prepared decoder open against a stopped player is worse than arming
        // again when playback resumes.
        if (!out.playWhenReady) return cancelArming("paused-before-fade")

        val now = nowMs()
        val expired = now > armDeadline
        val ready = into.playbackState == Player.STATE_READY
        if (ready && into.currentMediaItem?.mediaId == opportunity?.inId) preparedRoute(classicRouteFields(planInFlight))

        // A standby that never got the incoming track ready has nothing to fade
        // up. Give up and let the queue move on plainly rather than fading into
        // silence. djmix10: streamed Opus routinely needs one grace window
        // past the deadline (quality-upgrade rebuffer, BT jitter) — extend
        // once instead of bailing straight to a plain advance.
        if (expired && !ready) {
            if (!armGraceUsed) {
                armGraceUsed = true
                armDeadline = now + ARM_TIMEOUT_MS
                AutomixTelemetry.event("mix.arm.grace", mapOf("phase" to phase.name))
                if (armOutgoingPreFiltered) {
                    armOutgoingPreFiltered = false
                    resetPreFilteredOutgoing()
                    fadeCurves.rideFilters(render.rideSpec, 0f, null)
                }
                return
            }
            AutomixTelemetry.event("mix.arm.expired", mapOf("phase" to phase.name))
            return cancelArming("arm-expired")
        }

        // Wait for the track to actually reach the fade point. [fadeEndMs] is
        // the track's own duration in standard mode, or a Automix plan's
        // analyzed mix-out anchor when it ends before the file does.
        val atFadePoint = fadeEndMs <= 0L || fadeEndMs - out.currentPosition <= fadeMs
        if (atFadePoint && ready) {
            startFade()
            return
        }

        // Both sinks write seconds ahead of the speaker. The standby fills its
        // whole buffer while it waits here, so its filter has to be at the
        // blend's opening position before that audio is written, not once the
        // fade starts — otherwise the incoming track's first seconds are heard
        // unfiltered. The outgoing sink crosses into the blend its lead before
        // the listener does, so its filter only moves ahead once incoming is
        // ready and the fade start has not already passed; otherwise solo A
        // darkens/colours alone and jumps when [startFade] re-anchors
        // [fadeOutOriginMs].
        val outgoingAt = if (ready && !atFadePoint && fadeMs > 0L && fadeEndMs > 0L) {
            ((out.currentPosition + tracks.outgoingLeadMs() - (fadeEndMs - fadeMs)).toFloat() / fadeMs)
                .takeIf { it > 0f }
                ?.coerceAtMost(1f)
        } else {
            null
        }
        if (outgoingAt != null) {
            armOutgoingPreFiltered = true
            fadeCurves.rideFilters(render.rideSpec, 0f, outgoingAt)
        } else {
            if (armOutgoingPreFiltered) {
                armOutgoingPreFiltered = false
                resetPreFilteredOutgoing()
            }
            fadeCurves.rideFilters(render.rideSpec, 0f, null)
        }
        tempoEngine.maybeRampOutgoing(out, outgoingPlaybackRate)
    }

    /** Restores solo outgoing filter/colour/echo state if [driveArming] pre-filtered before rebuffering. */
    private fun resetPreFilteredOutgoing() {
        tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        tracks.outgoingMidDb(0f)
        tracks.outgoingColour(com.music.bitchord.playback.smart.DjEffect.NONE, 0f, render.beatSecondsF, 0f)
        if (render.echo) tracks.outgoingEcho(render.echoSecondsF, 0f, 1f)
    }

    /** Releases a pre-fade arm cleanly without calling global [TransitionFilters.open] while solo A is audible. */
    private fun cancelArming(reason: String) {
        if (phase != Phase.ARMING) return
        val expiredPairKey = if (reason == "arm-expired") {
            val curId = outgoing?.currentMediaItem?.mediaId ?: runCatching { active().currentMediaItem?.mediaId }.getOrNull()
            val nxtId = incoming?.currentMediaItem?.mediaId ?: plannedNextId
            if (!curId.isNullOrBlank() && !nxtId.isNullOrBlank()) "$curId->$nxtId" else null
        } else null
        lastCompleteReason = reason
        mixEvent(
            "mix.arm.death",
            mapOf(
                "stage" to "prepared", "technique" to "classic", "refusalCode" to refusalCode(reason),
                "reason" to reason,
                "phase" to phase.name,
                "msSincePlan" to planAgeMs(),
            ),
        )
        if (armOutgoingPreFiltered) {
            armOutgoingPreFiltered = false
            survivorRecoveryPending = true
        }
        bail(force = true)
        if (expiredPairKey != null && phase == Phase.IDLE) {
            // Brief cooldown so a standby player that timed out preparing does not immediately thrash-arm on next tick.
            hardWatchdogTriggeredPair = expiredPairKey
            hardWatchdogCooldownUntil = nowMs() + 5_000L
        }
    }

    /**
     * Starts the incoming track under the outgoing one.
     * The session stays where it is: the player, the notification and the
     * queue keep showing the outgoing song through the first half of the
     * blend, while its bar fills to the end, and move to the incoming song at
     * the midpoint — see [handOff]. Switching the moment the incoming track
     * first sounded put a song on screen that could barely be heard yet, under
     * one that was still plainly playing.
     */
    private fun startFade() {
        val out = outgoing ?: return cancelArming("missing-outgoing-at-fade")
        val into = incoming ?: return cancelArming("missing-incoming-at-fade")
        if (localBlendInFlight &&
            !localBlendFitsDeckSpeedCap(AppSettings.playbackSpeed.value.toDouble(), incomingPlaybackRate)
        ) return cancelArming("local-blend-listener-speed-cap")

        armOutgoingPreFiltered = false
        into.volume = 0f
        into.playWhenReady = true
        fadeStartedAt = nowMs()
        // djmix13: record firing accuracy at the moment the blend starts.
        // djmix14: cueErrorMs was seconds-minus-ms (5000-5=4995). Convert first.
        val targetCueMs = ((planInFlight?.incomingCueTime ?: 0.0) * 1000.0).roundToLong()
        val outId = out.currentMediaItem?.mediaId.orEmpty()
        val inId = into.currentMediaItem?.mediaId ?: plannedNextId.orEmpty()
        val levelName = AppSettings.automixDjLevel.value.name
        val fxNames = classicActiveFxNames(render)
        val fallback = lastFireFallbackReason.ifBlank { loopRefusal.orEmpty() }
        lastTimingPair = outId to inId
        lastFirePath = "classic"
        lastFireMove = ""
        lastFireFxCount = fxNames.size
        lastFireStems = 0
        lastFireFallbackReason = fallback
        pairPreparer?.markRunning(outId, inId)
        pairPreparer?.recordTiming(
            outId, inId,
            armLeadMs = classicArmLeadMs,
            path = "classic",
            level = levelName,
            move = "",
            fxCount = fxNames.size,
            stems = 0,
            fallbackReason = fallback,
        )
        val classicReason = classicFallbackReason.ifBlank {
            fallback.ifBlank { if (smartFadeActive) "native-renderer-unavailable" else "manual-crossfade" }
        }
        AutomixTelemetry.event(
            "mix.classic.reason",
            mapOf(
                "reason" to classicReason,
                "outId" to outId,
                "inId" to inId,
                "smart" to smartFadeActive,
                "stage" to "start-fade",
            ),
        )
        executedRoute = classicRouteFields(planInFlight)
        mixEvent(
            "mix.fire",
            requireNotNull(executedRoute) + mapOf(
                "stage" to "executed", "executionMarker" to "classic-fade-fired",
                "mode" to "classic",
                "path" to "classic",
                "engine" to "classic",
                "choreo" to "",
                "fx" to fxNames.joinToString(","),
                "fxCount" to fxNames.size,
                "level" to levelName,
                "stems" to 0,
                "fallbackReason" to fallback,
                "plannedStartMs" to lastSmartPlanStartMs,
                "actualStartMs" to out.currentPosition,
                "deltaMs" to (out.currentPosition - lastSmartPlanStartMs),
                "cueErrorMs" to (into.currentPosition - targetCueMs),
                "trackIndex" to outgoingItemIndex,
            ),
        )
        fadeOutOriginMs = out.currentPosition
        val fileLeft = out.duration.takeIf { it != C.TIME_UNSET && it > 0L }?.minus(fadeOutOriginMs)
        fadeOutBudgetMs = minOf(fadeMs, fileLeft ?: fadeMs).coerceAtLeast(1L)
        outClock.reset()
        inClock.reset()
        tempoEngine.onFadeStart(out, fadeStartedAt, outgoingPlaybackRate)
        outgoingAnalysis = out.currentMediaItem?.let(analysisFor) ?: TrackAnalysis()
        incomingAnalysis = into.currentMediaItem?.let(analysisFor) ?: TrackAnalysis()

        Log.d(TAG, "blend start at cue=${into.currentPosition}ms out=${out.currentPosition}ms")

        // Published before the mix flag, in the same tick: the scrubber shows
        // the old sheen for a mix flag with no blend beside it, and would flash
        // it for a frame otherwise.
        publishBlend(out, into, force = true)
        val realMix = FadeRideCurves.isRealMix(
            smartFadeActive, render.style, incomingCueTimeMs, incomingPlaybackRate, outgoingPlaybackRate,
        )
        AppSettings.smartMixInProgress.value = realMix
        if (realMix) publishMixView(out, into)
        // The bar fills to the end through the first half of the blend, which
        // would run straight over the marker for the transition now under way.
        AppSettings.smartTransitionWindow.value = null
        phase = Phase.FADING
        AutoMixVisualFeed.start(visualGeneration, audioSeconds = 0.0)
        driveFade()
    }

    /**
     * Moves the session onto the incoming player, halfway through the blend.
     *
     * Everything hanging off the session player — queue index, metadata, the
     * notification, the UI, audio focus — flips to the incoming song at the
     * point it becomes the louder of the two. From here [outgoing] is the idle
     * player, still audible, being faded away.
     */
    @JvmOverloads
    private fun handOff(
        out: ExoPlayer,
        into: ExoPlayer,
        visualAudioSeconds: Double? = null,
        visualEndSeconds: Double? = null,
    ) {
        if (handedOff) return
        if (!reconcileQueue(out, into)) {
            bail()
            return
        }
        // djmix18 (Bug 1): never call setPlaybackSpeed after handover with WSOLA steps across
        // B's solo intro. Reset the incoming player cleanly to the listener speed here, while
        // the outgoing tail still masks the single parameter change, and cancel any in-flight
        // ease so finish() cannot restart stepped WSOLA speed changes on the solo. Classic
        // mixes finish the blend at the incoming rate; the stretch came off at this line.
        // Native LoopMix decks are already at 1x (the renderer did the tempo), so this is a
        // no-op there — and still a guard that no ease is pending when the player takes over.
        runCatching {
            tempoEngine.onHandover(into, localBlendInFlight)
        }
        // AutoPlay may have appended to the queue since the standby was loaded
        // with a copy of it — during arming or the first half of the blend,
        // both of which the outgoing player owned — and those tracks would
        // otherwise be lost at the swap. [reconcileQueue] already carried
        // that append across, or refused a change that was not an append.

        Log.d(TAG, "handoff at in=${into.currentPosition}ms out=${out.currentPosition}ms")
        // The incoming song is about to be the one on screen, and a few seconds
        // later the one the speaker is playing from the player itself. Analysis
        // that starts now lands on the same cores as that decoder.
        yieldAnalysis()

        // Before the swap, so the listener follows the session rather than
        // firing on a player this class is about to demote.
        listenTo(into)
        handedOff = true
        AutoMixVisualFeed.pending(visualGeneration)?.let { visual ->
            val expected = visualQueue.getOrNull(visual.incoming.armedIndex)
            if (expected == null || into.currentMediaItemIndex != visual.incoming.armedIndex ||
                !sameVisualQueueItem(expected, into.currentMediaItem)
            ) {
                cancelAutoMixVisual()
            } else {
                visualQueue = (0 until into.mediaItemCount).map(into::getMediaItemAt)
                visualCurrentIndex = into.currentMediaItemIndex
            }
        }
        val endOfItemSample = if (visualAudioSeconds == null && AutoMixVisualFeed.pending(visualGeneration) != null) {
            val span = effectiveFadeSpan(into)
            val axisMs = if (render.advanced) span * mediaRatio.toFloat() else span.toFloat()
            val elapsed = (into.currentPosition - incomingCueTimeMs).coerceAtLeast(0L).toFloat()
            val remaining = out.duration.takeIf { it != C.TIME_UNSET && it > 0L }?.minus(out.currentPosition)
            val endProgress = remaining?.let { (span - it).toFloat() / span } ?: 0f
            val progress = maxOf(elapsed / axisMs, endProgress).coerceIn(0f, 1f)
            (progress * axisMs / 1000.0) to (axisMs / 1000.0)
        } else null
        onHandoff(out, into)
        val swap = visualAudioSeconds ?: endOfItemSample?.first
        val end = visualEndSeconds ?: endOfItemSample?.second
        if (swap != null && end != null) AutoMixVisualFeed.swap(visualGeneration, swap, end)

        // The outgoing player holds the whole queue too, and a standard
        // crossfade runs right up to its track's natural end — at which point
        // ExoPlayer would do what it always does and advance to the next item,
        // starting the incoming song a second time, on top of itself, out of the
        // player that is supposed to be going quiet. Truncating the queue at the
        // playing item turns that into STATE_ENDED, which [driveFade] already
        // reads as the tail being spent. Safe to discard: [into] is the
        // authoritative queue from here, and this player is retired seconds
        // later anyway.
        //
        // Only seamless because [holdAtEnd] kept this player from reading into
        // the items being removed. Without the hold, a handoff this close to
        // the track's end removes the period its renderers are reading, and
        // Media3 flushes the sink to recover — a break in the track still
        // playing. So an unheld player keeps its queue: it runs on into the
        // incoming song at the very end of its fade, where [ranOn] has it
        // silenced, and [finish] retires it a tick or two later.
        if (heldAtEnd === out && out.mediaItemCount > out.currentMediaItemIndex + 1) {
            out.removeMediaItems(out.currentMediaItemIndex + 1, out.mediaItemCount)
        }
    }

    /**
     * Copies onto the standby anything appended to the queue while it was
     * arming.
     *
     * AutoPlay extending the queue mid-transition is explicitly allowed — it
     * doesn't change the playing item, so it has never been a reason to drop a
     * blend. Under the old design that was free, because only one player ever
     * held the queue. Now the standby is carrying a copy taken at arm time, and
     * that copy is what survives the swap, so the difference has to be carried
     * across or the appended tracks simply vanish when the roles change.
     *
     * Only a pure append is reconciled. Anything else — a queue replaced, an
     * item removed or moved — changes what the incoming track *is*, and
     * [listener] has already bailed the transition for it.
     */
    /**
     * Copies a pure append onto the standby. A removal, a move, or a replaced
     * item is not an append: the incoming track may no longer be the one this
     * blend armed, and the caller drops the blend. Returns false in that case.
     * Does not assume a listener already noticed.
     */
    private fun reconcileQueue(out: ExoPlayer, into: ExoPlayer): Boolean {
        val outCount = out.mediaItemCount
        val intoCount = into.mediaItemCount
        if (queuedItemCount < 0 || queuedItemCount > outCount || queuedItemCount > intoCount) {
            Log.d(TAG, "queue length diverged (out=$outCount into=$intoCount snap=$queuedItemCount)")
            return false
        }
        val shared = queuedItemCount
        for (i in 0 until shared) {
            if (i >= outCount || i >= intoCount) return false
            if (out.getMediaItemAt(i).mediaId != into.getMediaItemAt(i).mediaId) {
                Log.d(TAG, "queue diverged at $i")
                return false
            }
        }
        if (outCount < queuedItemCount) return false
        val nextIndex = out.currentMediaItemIndex + 1
        if (nextIndex in 0 until shared) {
            val outTail = (nextIndex + 1 until outCount).map { out.getMediaItemAt(it).mediaId }
            val intoTail = (nextIndex + 1 until intoCount).map { into.getMediaItemAt(it).mediaId }
            if (outTail != intoTail) {
                if (intoCount > nextIndex + 1) into.removeMediaItems(nextIndex + 1, intoCount)
                val rebuilt = (nextIndex + 1 until outCount).map { out.getMediaItemAt(it) }
                if (rebuilt.isNotEmpty()) into.addMediaItems(rebuilt)
                queuedItemCount = outCount
                Log.d(TAG, "rebuilt ${rebuilt.size} item(s) after next")
                return true
            }
        }
        if (outCount == queuedItemCount) return true
        val appended = (queuedItemCount until outCount).map { out.getMediaItemAt(it) }
        into.addMediaItems(appended)
        queuedItemCount = outCount
        Log.d(TAG, "reconciled ${appended.size} appended item(s) onto the incoming player")
        return true
    }

    private fun effectiveFadeSpan(player: ExoPlayer): Long {
        val remainingIncoming = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?.minus(incomingCueTimeMs)
            ?.coerceAtLeast(0L)
        val fourBarsMs = if (render.beatSecondsF > 0f) (render.beatSecondsF * 16f * 1000f).toLong() else 0L
        val incomingCap = remainingIncoming?.let { left ->
            // A third of a short song used to shrink an 8-bar blend to a
            // fraction of a bar. Keep at least four bars when the song has them.
            maxOf(left / 3, minOf(left, fourBarsMs)).coerceAtLeast(1L)
        } ?: Long.MAX_VALUE
        return minOf(fadeMs, incomingCap).coerceAtLeast(1L)
    }

    /**
     * The crossfade proper.
     *
     * Driven off the *incoming* track's position rather than off a clock, so a
     * pause parks the transition where it stands and resuming picks it back up
     * — no timer to reconcile, and neither player left hanging at half volume
     * while the other waits.
     */
    private fun driveFade() {
        val out = outgoing ?: return bail()
        val player = incoming ?: return bail()
        val span = effectiveFadeSpan(player)
        val elapsed = (inClock.read(player) - incomingCueTimeMs).coerceAtLeast(0.0).toFloat()
        // Advanced: [fadeMs] is outgoing time, and a stretched incoming track
        // covers `rate` times as much of its own timeline in it — unscaled, a
        // 4% stretch finished the blend 4% early and left the incoming drop
        // landing after it rather than on its end.
        val wallSpan = if (render.advanced) span * mediaRatio.toFloat() else span.toFloat()
        val incomingHeard = elapsed / wallSpan
        val incomingProgress = incomingHeard.coerceIn(0f, 1f)
        // Duration/decoder timing can put the real file end ahead of the
        // analysed anchor. In that case the outgoing deck must still reach
        // zero before its final sample instead of remaining loud and appearing
        // to stop. Normally this is zero and incoming timing remains authority.
        // Read through [outClock] rather than raw `out.currentPosition` so
        // outgoing-driven progress and AutoMix artwork reveal stay smooth.
        val outDuration = out.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val outPosition = outClock.read(out).toLong()
        val outRemaining = outDuration?.minus(outPosition)
        // Measured from where the fade actually started, over the overlap we
        // still have. Using the planned span against a file that was already
        // about to end put progress near 1 on the first tick and cut A off.
        val traveled = (outPosition - fadeOutOriginMs).coerceAtLeast(0L)
        val budget = fadeOutBudgetMs.coerceAtLeast(1L)
        val outgoingProgress = (traveled.toFloat() / budget.toFloat()).coerceIn(0f, 1f)
        val progress = maxOf(incomingProgress, outgoingProgress)
        val echoTailEnd = render.echoAt + render.echoTailMs / wallSpan
        val echoFileLeft = if (render.echo) outDuration?.minus(fadeOutOriginMs) else null
        val echoBudget = minOf(budget, echoFileLeft ?: budget).coerceAtLeast(1L)
        val echoHeard = if (render.echo) maxOf(incomingHeard, traveled.toFloat() / echoBudget) else progress

        // Before the handoff the session is still the outgoing player, so a
        // failure on the incoming one would otherwise go unheard: its position
        // stops, the blend parks at whatever mix it had reached, and the
        // outgoing track runs out under it.
        // djmix19: an incoming that never leaves STATE_IDLE used to bail() on
        // every 30 ms tick, freezing the blend and logging mix.freeze 762x.
        // Track the stall: try prepare() once after 1.5 s, then force-exit so
        // the blend never hangs; an outgoing about to run out forces at once.
        if (!handedOff && player.playbackState == Player.STATE_IDLE) {
            val now = nowMs()
            if (idleStallSinceMs == 0L) idleStallSinceMs = now
            val stalledMs = now - idleStallSinceMs
            val outExhausted = outRemaining != null && outRemaining <= HANDOFF_GUARD_MS
            return when (idleStallDecision(stalledMs, outExhausted, idleStallPrepared)) {
                IdleStallAction.WAIT_AND_FREEZE -> bail()
                IdleStallAction.PREPARE_ONCE -> {
                    idleStallPrepared = true
                    runCatching { player.prepare() }
                    bail()
                }
                IdleStallAction.FORCE_BAIL -> {
                    idleStallSinceMs = 0L
                    idleStallPrepared = false
                    bail(force = true)
                }
            }
        }
        idleStallSinceMs = 0L
        idleStallPrepared = false

        // Faders act at the speaker; everything in the DSP chain — the filters
        // and the headroom trim — acts where each sink is writing, its lead
        // ahead of that. So those are aimed at the blend as it will be when the
        // audio they touch is played, each side by its own lead.
        val incomingAt = ((elapsed + tracks.incomingLeadMs()) / wallSpan).coerceIn(0f, 1f)
        val outgoingAt = (echoHeard + tracks.outgoingLeadMs() / span.toFloat()).coerceIn(0f, 1f)

        val rise = fadeCurves.mixRise(render.rideSpec, progress)
        val fall = if (render.echo) {
            val fileEnd = echoFileLeft?.let { it.toDouble() / echoBudget } ?: Double.POSITIVE_INFINITY
            val fadeEnd = minOf(maxOf(1.0, echoTailEnd), fileEnd.coerceAtLeast(1.0))
            // The player fader carries both dry and wet. Keep it moving through
            // the existing tail, ending at the decoder EOF if that arrives first.
            if (echoHeard >= fadeEnd) 0f
            else MixGain.fall(((echoHeard - render.echoAt) / (fadeEnd - render.echoAt)).toFloat()).coerceAtLeast(0f)
        } else {
            fadeCurves.mixFall(render.rideSpec, progress)
        }
        player.volume = rise
        out.volume = if (ranOn(out)) 0f else fall
        onBlendHeadroom(
            FadeRideCurves.headroomFor(
                fadeCurves.mixRise(render.rideSpec, outgoingAt),
                fadeCurves.outgoingLevel(render.rideSpec, elapsed / wallSpan + tracks.outgoingLeadMs() / span.toFloat(), wallSpan),
            ),
        )
        if (render.phaseLock) tempoEngine.holdPhase(
            out, player, progress, incomingPlaybackRate, mediaRatio,
            localBlendInFlight, outgoingAnalysis, incomingAnalysis,
        )

        // The midpoint, or sooner if the outgoing track is about to run out
        // from under it: until the handoff its queue still runs on past the
        // track, and reaching the end would start the next item — the very
        // song fading in — a second time on the player that is leaving.
        val visualEnd = wallSpan / 1000.0
        val visualTime = progress * wallSpan / 1000.0
        if (!handedOff && (progress >= render.handoffAt || (outRemaining != null && outRemaining <= HANDOFF_GUARD_MS))) {
            handOff(out, player, visualTime, visualEnd)
        }
        AutoMixVisualFeed.advance(visualGeneration, visualTime, visualEnd)
        if (progress >= 1f) AutoMixVisualFeed.complete(visualGeneration)
        publishBlend(out, player)
        if (AppSettings.smartMixView.value != null) AppSettings.smartMixProgress.value = progress
        fadeCurves.rideFilters(render.rideSpec, incomingAt, outgoingAt)
        tempoEngine.rideBuildup(
            incoming, render.buildup, handedOff, localBlendInFlight, incomingPlaybackRate, progress,
        )

        // Finish when the fade runs its course or its setting is switched off.
        // The outgoing decoder ending is not completion: its final sample may
        // arrive early, and the incoming fader still has to finish rising.
        // Checked against the
        // setting that actually started it — a Automix normally runs with
        // [configuredFadeMs] at zero, and reading that as "turned off" would
        // end every Automix on its first tick.
        val settingSwitchedOff = if (smartFadeActive) {
            !AppSettings.smartFadeEnabled.value
        } else {
            configuredFadeMs() <= 0L
        }
        // An echo out rings past the end of the blend, carried by the outgoing
        // player's own silenced audio, so that player is kept until its tail
        // has been heard out.
        val tailDone = !render.echo || incomingHeard >= echoTailEnd
        // vc77: progress is the max of the two sides, so it can reach 1 while
        // the incoming sink is still mid-blend and would strand its filter.
        // Unless the outgoing file is about to EOF (where waiting would cut
        // it off), completion needs the incoming side caught up too; when it
        // is about to EOF the clamp below puts both sides at 1.0 full-band.
        val outAboutToEof = outRemaining != null && outRemaining <= HANDOFF_GUARD_MS
        val incomingReady = incomingAt >= 0.999f
        val done = settingSwitchedOff ||
            (progress >= 1f && tailDone && (incomingReady || outAboutToEof))
        // vc77 hang bound: progress==1 blocked by incomingReady while the
        // incoming player sits in STATE_BUFFERING and the outgoing file is
        // not near EOF. After FADING_STALL_BAIL_MS of
        // continuous buffering, force-bail through the existing
        // abnormal-exit path (survivor kept, its filters reopened only by
        // finish() once the retiree is silent — Seam A). A pause parks in
        // STATE_READY, never BUFFERING, so an intentional hold never trips
        // this, and a stall that recovers first completes normally.
        if (!done) {
            val now = nowMs()
            val buffering = player.playbackState == Player.STATE_BUFFERING
            val blockedAtOne = progress >= 1f && !incomingReady && !outAboutToEof && buffering
            if (blockedAtOne) {
                if (fadingStallSinceMs == 0L) fadingStallSinceMs = now
            } else {
                fadingStallSinceMs = 0L
            }
            if (fadingStallSinceMs != 0L) {
                val stalledMs = now - fadingStallSinceMs
                if (fadingStallShouldBail(stalledMs, progress >= 1f, incomingReady, outAboutToEof, buffering)) {
                    fadingStallSinceMs = 0L
                    AutomixTelemetry.event(
                        "mix.stall.forceBail",
                        mapOf(
                            "stalledMs" to stalledMs,
                            "progress" to progress,
                            "incomingAt" to incomingAt,
                            "playbackState" to player.playbackState,
                            "bufferedPercentage" to player.bufferedPercentage,
                            "outRemainingMs" to (outRemaining ?: -1L),
                            "handoff" to handedOff,
                        ),
                    )
                    bail(force = true)
                    return
                }
            }
        }
        if (done) {
            // Clamp both sides to progress 1 before teardown: a stranded
            // incoming filter otherwise survives into the next blend at a
            // parked cutoff.
            if (progress >= 1f) {
                fadeCurves.rideFilters(render.rideSpec, 1f, 1f)
            } else {
                // Abnormal exit (setting switched off) before progress 1:
                // defer survivor-only recovery to finish() once the retiree
                // is silent; the spare filter is left untouched.
                survivorRecoveryPending = true
            }
            // A blend cut short before its midpoint still ends on the incoming
            // track — it is the one playing on, and what [finish] keeps.
            handOff(out, player)
            beginTailOrFinish(out, "planned-fade-done", "driveFade-done")
        }
    }

    /**
     * Graceful-exit guarantee (djmix11): the outgoing player may never be
     * retired while audible. The fade reached its end quietly, or jumped
     * there in one tick (a zero-length span, a cued standby clock ahead) —
     * either way, dissolve over the planned tail (800 ms) or the emergency
     * one (350 ms) before [finish] retires it. The retire is the only
     * hard-cut site left on this path; it now always sees silence.
     */
    private fun beginTailOrFinish(out: ExoPlayer, reason: String = "planned", retireSite: String = "driveFade-done") {
        lastCompleteReason = reason
        val vol = out.volume
        if (vol <= 0.001f) {
            // djmix14-review: a smoothly finished planned fade already
            // dissolved to silence (<= -60 dB). It must still truthfully
            // record mix.exit.tail (0 occurrences in djmix11-13 because this
            // early return skipped it), then retire.
            val planned = fadeMs > 0L
            // vc67: zero-tail must not retain the previous transition tail.
            tailDurationMs = 0L
            AutomixTelemetry.event(
                "mix.exit.tail",
                mapOf(
                    "tailMs" to 0L,
                    "mode" to if (planned) "planned" else "emergency",
                    "gain" to vol,
                    "gainDb" to RetireGuard.gainDb(vol),
                    "reason" to reason,
                    "retireSite" to retireSite,
                ),
            )
            finish()
            return
        }
        tailFromGain = vol
        tailStartMs = nowMs()
        tailTarget = out
        val planned = fadeMs > 0L
        tailDurationMs = if (planned) PhraseConfig.EXIT_TAIL_MIN_MS.toLong() else PhraseConfig.EXIT_TAIL_EMERGENCY_MS.toLong()
        AutomixTelemetry.event(
            "mix.exit.tail",
            mapOf(
                "tailMs" to tailDurationMs,
                "mode" to if (planned) "planned" else "emergency",
                "gain" to vol,
                "gainDb" to RetireGuard.gainDb(vol),
                "reason" to reason,
                "retireSite" to retireSite,
            ),
        )
        phase = Phase.TAIL
    }

    private fun disarmTailLoopSession() {
        val s = tailLoopSession ?: return
        captureLoopExitObservation(s)
        tailLoopSession = null
        s.outPort?.disarm()
        s.inPort?.disarm()
        closeLater(s.renderer)
    }

    private fun driveTail() {
        val out = tailTarget ?: outgoing
        if (out == null) {
            disarmTailLoopSession()
            finish()
            return
        }
        val p = (nowMs() - tailStartMs).toFloat() / tailDurationMs.coerceAtLeast(1L)
        if (p >= 1f) {
            out.volume = 0f
            disarmTailLoopSession()
            finish()
            return
        }
        // Same equal-power law as the blend itself (MixGain.fall): the sum
        // with the incoming at 1 stays flat through the dissolve.
        out.volume = tailFromGain * MixGain.fall(p.coerceIn(0f, 1f))
    }

    /**
     * Tells the scrubber where the blend's beats fall, and whether it is moving.
     *
     * The grid comes from whichever side the listener is hearing more of —
     * the outgoing track until the handoff, the incoming one after — at the
     * rate it is actually playing: the listener's speed, and for the incoming
     * side the beatmatch stretch on top. On a beatmatched pair both grids
     * agree anyway; on one that isn't, this follows the dominant track.
     *
     * Computed every fade tick but *published* only when it has moved: the
     * scrubber runs its own beat clock and only leans on this anchor, so
     * re-sending the same grid thirty times a second just wakes every
     * collector for nothing. A new value goes out when the tempo changes, the
     * anchor drifts past [ANCHOR_TOLERANCE_NANOS] — a pause, a stall, the
     * grid moving to the other song — or playback starts or stops.
     */
    private fun publishBlend(out: ExoPlayer, into: ExoPlayer, force: Boolean = false) {
        mixViewPublisher.publishBlend(out, into, force)
    }

    private fun mixGeometry(): MixGeometry = mixViewPublisher.mixGeometry()

    private fun prefetchMixWaves(
        outItem: MediaItem,
        inItem: MediaItem,
        outDurationMs: Long,
        inDurationMs: Long,
        outStart: Double,
        outEnd: Double,
        inStart: Double,
        ratio: Double,
        wantOut: Boolean = true,
        wantIn: Boolean = true,
    ) {
        mixViewPublisher.prefetchMixWaves(
            outItem, inItem, outDurationMs, inDurationMs,
            outStart, outEnd, inStart, ratio, wantOut, wantIn,
        )
    }

    private fun publishMixPreview(
        currentItem: MediaItem,
        nextItem: MediaItem,
        duration: Long,
        nextDuration: Long,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        state: SmartAnalysis,
        plan: TransitionPlan,
        advanced: Boolean,
    ) {
        mixViewPublisher.publishMixPreview(
            currentItem, nextItem, duration, nextDuration,
            currentAnalysis, nextAnalysis, state, plan, advanced,
        )
    }

    private fun expireMixPreview() {
        mixViewPublisher.expireMixPreview()
    }

    private fun publishMixView(out: ExoPlayer, into: ExoPlayer) {
        mixViewPublisher.publishMixView(out, into)
    }

    private fun clearMixView() {
        mixViewPublisher.clearMixView()
    }

    private fun sameGrid(last: MixBlend, beatMs: Float, anchor: Long): Boolean =
        mixViewPublisher.sameGrid(last, beatMs, anchor)

    private fun beginSleepFadeOut(fade: Long) {
        mixEventRouter.beginSleepFadeOut(fade)
    }

    private fun driveSleepFade() {
        mixEventRouter.driveSleepFade()
    }

    private fun finishSleepFade() {
        mixEventRouter.finishSleepFade()
    }

    private fun driveBail() {
        mixEventRouter.driveBail()
    }


    // ---- The loop mix -------------------------------------------------------
    //
    // Automix's top rung (see [LoopMix]): both songs loop instrumental bars
    // while one tempo ramps from the outgoing song's to the incoming one's,
    // rendered inside the outgoing player's sink by [LoopMixRenderer] so the
    // takeover is sample-exact and the two decks can't drift apart. Tried
    // before any blend; a pair it can't do (no grid, no instrumental bars, a
    // video, a source-backed copy, a speed other than 1x) gets the planner's
    // blend, filter mix or crossfade exactly as before.

    internal val deckTiming = DeckTiming(nowMs)
    private var loopSession: LoopMixSession? = null
    private var loopBuildJob: Job? = null
    private var loopAlignJob: Job? = null
    private var primedSession: LoopMixSession? = null
    private var primedKey: String? = null
    private var primeJob: Job? = null
    private var primeRetryAt: Long = 0L
    private var buildGeneration: Int = 0
    private var deckSide: String = "outgoing"
    private var loopTarget: LoopMix.Plan? = null
    private var loopTargetStartMs: Long = 0L
    private var loopOutItem: MediaItem? = null
    private var loopInItem: MediaItem? = null
    private var loopOutAnalysis: TrackAnalysis? = null
    private var loopInAnalysis: TrackAnalysis? = null
    private var loopSungOut: List<LoopMix.Span>? = null
    private var loopSungIn: List<LoopMix.Span>? = null
    private var loopOutDurationMs: Long = 0L
    private var loopInDurationMs: Long = 0L
    private var straightPreparedPair: com.music.bitchord.playback.djmix.PreparedPair? = null
    private var loopPlanKey: String? = null
    private var loopPlanned: LoopMix.Plan? = null
    private var loopScript: PhraseScript? = null
    private var shownCountdownKey: String? = null
    private var shownLoopStartMs: Long = 0L
    private var watchdogFired: Boolean = false
    private var loopRefusal: String? = null
    private var lastLoopVerdict: String = ""
    private var loopIncomingStarted: Boolean = false
    private var deferredHandoffLogged: Boolean = false
    private var lastLoopPositionMs: Long = -1L
    private var lastLoopAnnounceAt: Long = 0L
    private var lastLoopBeat: Int = -1

    internal val loopState: LoopDriverState = object : LoopDriverState {
        override val deckTiming: DeckTiming get() = this@CrossfadeController.deckTiming
        override var loopSession: LoopMixSession?
            get() = this@CrossfadeController.loopSession
            set(v) { this@CrossfadeController.loopSession = v }
        override var loopBuildJob: Job?
            get() = this@CrossfadeController.loopBuildJob
            set(v) { this@CrossfadeController.loopBuildJob = v }
        override var loopAlignJob: Job?
            get() = this@CrossfadeController.loopAlignJob
            set(v) { this@CrossfadeController.loopAlignJob = v }
        override var primedSession: LoopMixSession?
            get() = this@CrossfadeController.primedSession
            set(v) { this@CrossfadeController.primedSession = v }
        override var primedKey: String?
            get() = this@CrossfadeController.primedKey
            set(v) { this@CrossfadeController.primedKey = v }
        override var primeJob: Job?
            get() = this@CrossfadeController.primeJob
            set(v) { this@CrossfadeController.primeJob = v }
        override var primeRetryAt: Long
            get() = this@CrossfadeController.primeRetryAt
            set(v) { this@CrossfadeController.primeRetryAt = v }
        override var buildGeneration: Int
            get() = this@CrossfadeController.buildGeneration
            set(v) { this@CrossfadeController.buildGeneration = v }
        override var deckSide: String
            get() = this@CrossfadeController.deckSide
            set(v) { this@CrossfadeController.deckSide = v }
        override var loopTarget: LoopMix.Plan?
            get() = this@CrossfadeController.loopTarget
            set(v) { this@CrossfadeController.loopTarget = v }
        override var loopTargetStartMs: Long
            get() = this@CrossfadeController.loopTargetStartMs
            set(v) { this@CrossfadeController.loopTargetStartMs = v }
        override var loopOutItem: MediaItem?
            get() = this@CrossfadeController.loopOutItem
            set(v) { this@CrossfadeController.loopOutItem = v }
        override var loopInItem: MediaItem?
            get() = this@CrossfadeController.loopInItem
            set(v) { this@CrossfadeController.loopInItem = v }
        override var loopOutAnalysis: TrackAnalysis?
            get() = this@CrossfadeController.loopOutAnalysis
            set(v) { this@CrossfadeController.loopOutAnalysis = v }
        override var loopInAnalysis: TrackAnalysis?
            get() = this@CrossfadeController.loopInAnalysis
            set(v) { this@CrossfadeController.loopInAnalysis = v }
        override var loopSungOut: List<LoopMix.Span>?
            get() = this@CrossfadeController.loopSungOut
            set(v) { this@CrossfadeController.loopSungOut = v }
        override var loopSungIn: List<LoopMix.Span>?
            get() = this@CrossfadeController.loopSungIn
            set(v) { this@CrossfadeController.loopSungIn = v }
        override var loopOutDurationMs: Long
            get() = this@CrossfadeController.loopOutDurationMs
            set(v) { this@CrossfadeController.loopOutDurationMs = v }
        override var loopInDurationMs: Long
            get() = this@CrossfadeController.loopInDurationMs
            set(v) { this@CrossfadeController.loopInDurationMs = v }
        override var straightPreparedPair: com.music.bitchord.playback.djmix.PreparedPair?
            get() = this@CrossfadeController.straightPreparedPair
            set(v) { this@CrossfadeController.straightPreparedPair = v }
        override var loopPlanKey: String?
            get() = this@CrossfadeController.loopPlanKey
            set(v) { this@CrossfadeController.loopPlanKey = v }
        override var loopPlanned: LoopMix.Plan?
            get() = this@CrossfadeController.loopPlanned
            set(v) { this@CrossfadeController.loopPlanned = v }
        override var loopScript: PhraseScript?
            get() = this@CrossfadeController.loopScript
            set(v) { this@CrossfadeController.loopScript = v }
        override var shownCountdownKey: String?
            get() = this@CrossfadeController.shownCountdownKey
            set(v) { this@CrossfadeController.shownCountdownKey = v }
        override var shownLoopStartMs: Long
            get() = this@CrossfadeController.shownLoopStartMs
            set(v) { this@CrossfadeController.shownLoopStartMs = v }
        override var watchdogFired: Boolean
            get() = this@CrossfadeController.watchdogFired
            set(v) { this@CrossfadeController.watchdogFired = v }
        override var loopRefusal: String?
            get() = this@CrossfadeController.loopRefusal
            set(v) { this@CrossfadeController.loopRefusal = v }
        override var lastLoopVerdict: String
            get() = this@CrossfadeController.lastLoopVerdict
            set(v) { this@CrossfadeController.lastLoopVerdict = v }
        override var loopIncomingStarted: Boolean
            get() = this@CrossfadeController.loopIncomingStarted
            set(v) { this@CrossfadeController.loopIncomingStarted = v }
        override var deferredHandoffLogged: Boolean
            get() = this@CrossfadeController.deferredHandoffLogged
            set(v) { this@CrossfadeController.deferredHandoffLogged = v }
        override var lastLoopPositionMs: Long
            get() = this@CrossfadeController.lastLoopPositionMs
            set(v) { this@CrossfadeController.lastLoopPositionMs = v }
        override var lastLoopAnnounceAt: Long
            get() = this@CrossfadeController.lastLoopAnnounceAt
            set(v) { this@CrossfadeController.lastLoopAnnounceAt = v }
        override var lastLoopBeat: Int
            get() = this@CrossfadeController.lastLoopBeat
            set(v) { this@CrossfadeController.lastLoopBeat = v }
        override var loopCleanupContendedSession: LoopMixSession?
            get() = this@CrossfadeController.loopCleanupContendedSession
            set(v) { this@CrossfadeController.loopCleanupContendedSession = v }
        override var loopCleanupContendedSinceNanos: Long
            get() = this@CrossfadeController.loopCleanupContendedSinceNanos
            set(v) { this@CrossfadeController.loopCleanupContendedSinceNanos = v }
    }
    private var opportunity: Opportunity?
        get() = mixEventRouter.opportunity
        set(value) { mixEventRouter.opportunity = value }

    private var planRevision: Int
        get() = mixEventRouter.planRevision
        set(value) { mixEventRouter.planRevision = value }

    private var executedRoute: Map<String, Any?>?
        get() = mixEventRouter.executedRoute
        set(value) { mixEventRouter.executedRoute = value }

    private var terminalRecorded: Boolean
        get() = mixEventRouter.terminalRecorded
        set(value) { mixEventRouter.terminalRecorded = value }

    private var routingFallback: Boolean
        get() = mixEventRouter.routingFallback
        set(value) { mixEventRouter.routingFallback = value }

    private var nativeBinding: NativeBinding?
        get() = mixEventRouter.nativeBinding
        set(value) { mixEventRouter.nativeBinding = value }

    private val routingEvidenceKeys: MutableSet<String>
        get() = mixEventRouter.routingEvidenceKeys

    private fun ensureOpportunity(outId: String, inId: String): Opportunity =
        mixEventRouter.ensureOpportunity(outId, inId)

    private fun expireIdleOpportunity(reason: String) {
        mixEventRouter.expireIdleOpportunity(reason)
    }

    private fun resetOpportunityEventKeys() {
        mixEventRouter.resetOpportunityEventKeys()
    }

    private fun opportunityFields(context: Opportunity?): Map<String, Any?> =
        mixEventRouter.opportunityFields(context)

    private fun mixEvent(name: String, fields: Map<String, Any?>, context: Opportunity? = opportunity) {
        mixEventRouter.mixEvent(name, fields, context)
    }

    private fun nativeRouteFields(
        plan: LoopMix.Plan,
        outLoops: Boolean? = null,
        inLoops: Boolean? = null,
        actualConfiguration: Boolean = false,
        coarseDecline: Boolean? = null,
    ): Map<String, Any?> =
        mixEventRouter.nativeRouteFields(plan, outLoops, inLoops, actualConfiguration, coarseDecline)

    private fun nativeSessionRouteFields(session: LoopMixSession, coarseDecline: Boolean? = null): Map<String, Any?> =
        mixEventRouter.nativeSessionRouteFields(session, coarseDecline)

    private fun classicRouteFields(plan: TransitionPlan?): Map<String, Any?> =
        mixEventRouter.classicRouteFields(plan)

    private fun acceptRoute(plan: Any, fields: Map<String, Any?>) {
        mixEventRouter.acceptRoute(plan, fields)
    }

    private fun reviseRoute(plan: Any): Int =
        mixEventRouter.reviseRoute(plan)

    private fun preparedRoute(
        fields: Map<String, Any?>,
        context: Opportunity? = opportunity,
        revision: Int = planRevision,
        generation: Int? = null,
    ) {
        mixEventRouter.preparedRoute(fields, context, revision, generation)
    }

    private fun refusalCode(reason: String): String =
        mixEventRouter.refusalCode(reason)

    private fun routeRefusal(
        name: String,
        reason: String,
        stage: String,
        technique: String,
        destination: String? = null,
        context: Opportunity? = opportunity,
        revision: Int = planRevision,
        generation: Int? = null,
        coarseDecline: Boolean? = null,
    ) {
        mixEventRouter.routeRefusal(name, reason, stage, technique, destination, context, revision, generation, coarseDecline)
    }
    private val loopDriverHost = object : LoopDriverHost {
        override val scope: CoroutineScope get() = this@CrossfadeController.scope
        override fun nowMs(): Long = this@CrossfadeController.nowMs()
        override fun active(): ExoPlayer = this@CrossfadeController.active()
        override fun standby(): ExoPlayer = this@CrossfadeController.standby()
        override val outgoing: ExoPlayer? get() = this@CrossfadeController.outgoing
        override val incoming: ExoPlayer? get() = this@CrossfadeController.incoming

        override val phaseName: String get() = this@CrossfadeController.phase.name
        override fun isLoopArming(): Boolean = this@CrossfadeController.phase == Phase.LOOP_ARMING
        override fun isLoopRunning(): Boolean = this@CrossfadeController.phase == Phase.LOOP_RUNNING
        override fun isIdle(): Boolean = this@CrossfadeController.phase == Phase.IDLE
        override fun isTail(): Boolean = this@CrossfadeController.phase == Phase.TAIL
        override fun setPhaseLoopArming() { this@CrossfadeController.phase = Phase.LOOP_ARMING }
        override fun setPhaseLoopRunning() { this@CrossfadeController.phase = Phase.LOOP_RUNNING }

        override var smartFadeActive: Boolean
            get() = this@CrossfadeController.smartFadeActive
            set(v) { this@CrossfadeController.smartFadeActive = v }
        override var handedOff: Boolean
            get() = this@CrossfadeController.handedOff
            set(v) { this@CrossfadeController.handedOff = v }
        override var loopBuildStartedAtMs: Long
            get() = this@CrossfadeController.loopBuildStartedAtMs
            set(v) { this@CrossfadeController.loopBuildStartedAtMs = v }
        override var blendArmLeadMs: Long
            get() = this@CrossfadeController.blendArmLeadMs
            set(v) { this@CrossfadeController.blendArmLeadMs = v }
        override var loopSuppressedPair: String?
            get() = this@CrossfadeController.loopSuppressedPair
            set(v) { this@CrossfadeController.loopSuppressedPair = v }
        override var loopSuppressedUntil: Long
            get() = this@CrossfadeController.loopSuppressedUntil
            set(v) { this@CrossfadeController.loopSuppressedUntil = v }
        override var outgoingItemIndex: Int
            get() = this@CrossfadeController.outgoingItemIndex
            set(v) { this@CrossfadeController.outgoingItemIndex = v }
        override var queuedItemCount: Int
            get() = this@CrossfadeController.queuedItemCount
            set(v) { this@CrossfadeController.queuedItemCount = v }
        override var plannedNextId: String?
            get() = this@CrossfadeController.plannedNextId
            set(v) { this@CrossfadeController.plannedNextId = v }
        override var initiatingExitReason: String?
            get() = this@CrossfadeController.initiatingExitReason
            set(v) { this@CrossfadeController.initiatingExitReason = v }
        override var capturedTakeoverSpliceBufferStatus: LoopMixPort.TakeoverSpliceBufferStatus?
            get() = this@CrossfadeController.capturedTakeoverSpliceBufferStatus
            set(v) { this@CrossfadeController.capturedTakeoverSpliceBufferStatus = v }
        override var capturedLoopTookOver: Boolean?
            get() = this@CrossfadeController.capturedLoopTookOver
            set(v) { this@CrossfadeController.capturedLoopTookOver = v }
        override var lastCompleteReason: String?
            get() = this@CrossfadeController.lastCompleteReason
            set(v) { this@CrossfadeController.lastCompleteReason = v }
        override var lastTimingPair: Pair<String, String>?
            get() = this@CrossfadeController.lastTimingPair
            set(v) { this@CrossfadeController.lastTimingPair = v }
        override var lastFirePath: String
            get() = this@CrossfadeController.lastFirePath
            set(v) { this@CrossfadeController.lastFirePath = v }
        override var lastFireMove: String
            get() = this@CrossfadeController.lastFireMove
            set(v) { this@CrossfadeController.lastFireMove = v }
        override var lastFireFxCount: Int
            get() = this@CrossfadeController.lastFireFxCount
            set(v) { this@CrossfadeController.lastFireFxCount = v }
        override var lastFireStems: Int
            get() = this@CrossfadeController.lastFireStems
            set(v) { this@CrossfadeController.lastFireStems = v }
        override var lastFireFallbackReason: String
            get() = this@CrossfadeController.lastFireFallbackReason
            set(v) { this@CrossfadeController.lastFireFallbackReason = v }
        override val lastPlayWhenReadyChangeReason: Int
            get() = this@CrossfadeController.lastPlayWhenReadyChangeReason
        override var lastFreezeLogKey: String?
            get() = this@CrossfadeController.lastFreezeLogKey
            set(v) { this@CrossfadeController.lastFreezeLogKey = v }
        override var lastArmDeathKey: String?
            get() = this@CrossfadeController.lastArmDeathKey
            set(v) { this@CrossfadeController.lastArmDeathKey = v }
        override var lastFallbackLogKey: String?
            get() = this@CrossfadeController.lastFallbackLogKey
            set(v) { this@CrossfadeController.lastFallbackLogKey = v }
        override var outgoingAnalysis: TrackAnalysis
            get() = this@CrossfadeController.outgoingAnalysis
            set(v) { this@CrossfadeController.outgoingAnalysis = v }
        override var incomingAnalysis: TrackAnalysis
            get() = this@CrossfadeController.incomingAnalysis
            set(v) { this@CrossfadeController.incomingAnalysis = v }
        override var lastSmartPlan: TransitionPlan?
            get() = this@CrossfadeController.lastSmartPlan
            set(v) { this@CrossfadeController.lastSmartPlan = v }
        override var lastSmartPlanStartMs: Long
            get() = this@CrossfadeController.lastSmartPlanStartMs
            set(v) { this@CrossfadeController.lastSmartPlanStartMs = v }
        override var pinnedCatchUpKey: String?
            get() = this@CrossfadeController.pinnedCatchUpKey
            set(v) { this@CrossfadeController.pinnedCatchUpKey = v }
        override var pinnedCatchUpPlan: TransitionPlan?
            get() = this@CrossfadeController.pinnedCatchUpPlan
            set(v) { this@CrossfadeController.pinnedCatchUpPlan = v }
        override var handoverExpectedMs: Long
            get() = this@CrossfadeController.handoverExpectedMs
            set(v) { this@CrossfadeController.handoverExpectedMs = v }
        override var handoverHeardMs: Long
            get() = this@CrossfadeController.handoverHeardMs
            set(v) { this@CrossfadeController.handoverHeardMs = v }
        override var handoverTargetMs: Long
            get() = this@CrossfadeController.handoverTargetMs
            set(v) { this@CrossfadeController.handoverTargetMs = v }
        override var internalHandoverSeek: Boolean
            get() = this@CrossfadeController.internalHandoverSeek
            set(v) { this@CrossfadeController.internalHandoverSeek = v }
        override var suppressScrubUntilMs: Long
            get() = this@CrossfadeController.suppressScrubUntilMs
            set(v) { this@CrossfadeController.suppressScrubUntilMs = v }
        override var inFallbackReplan: Boolean
            get() = this@CrossfadeController.inFallbackReplan
            set(v) { this@CrossfadeController.inFallbackReplan = v }
        override var routingFallback: Boolean
            get() = this@CrossfadeController.routingFallback
            set(v) { this@CrossfadeController.routingFallback = v }
        override var tailLoopSession: LoopMixSession?
            get() = this@CrossfadeController.tailLoopSession
            set(v) { this@CrossfadeController.tailLoopSession = v }
        override var tailDurationMs: Long
            get() = this@CrossfadeController.tailDurationMs
            set(v) { this@CrossfadeController.tailDurationMs = v }

        override val visualGeneration: Long? get() = this@CrossfadeController.visualGeneration
        override val scrubEpoch: Long get() = this@CrossfadeController.scrubEpoch
        override val activeMachineGeneration: Long get() = this@CrossfadeController.activeMachineGeneration
        override val pairPreparer: com.music.bitchord.playback.djmix.PairPreparer? get() = this@CrossfadeController.pairPreparer
        override val mixExecutor: MixExecutor get() = this@CrossfadeController.mixExecutor
        override val tempoEngine: EaseTempoEngine get() = this@CrossfadeController.tempoEngine
        override val countdownStabilizer: CountdownStabilizer get() = this@CrossfadeController.countdownStabilizer
        override val filters: TransitionFilters get() = this@CrossfadeController.filters
        override val tracks: TransitionFilters get() = this@CrossfadeController.tracks
        override val lyricSpans: Map<String, LyricSpans> get() = this@CrossfadeController.lyricSpans
        override val lyricsAskedAt: Map<String, Long> get() = this@CrossfadeController.lyricsAskedAt

        override fun loopPortOf(player: ExoPlayer): LoopMixPort? = this@CrossfadeController.loopPortOf(player)
        override fun holdAtEnd(player: ExoPlayer) = this@CrossfadeController.holdAtEnd(player)
        override fun armAutoMixVisual(out: ExoPlayer, into: ExoPlayer, nextIndex: Int, items: List<MediaItem>) =
            this@CrossfadeController.armAutoMixVisual(out, into, nextIndex, items)
        override fun cancelAutoMixVisual() = this@CrossfadeController.cancelAutoMixVisual()
        override fun onArmIncoming(item: MediaItem, afterId: String?) = this@CrossfadeController.onArmIncoming(item, afterId)
        override fun onArmAutoMixIncoming(item: MediaItem) = this@CrossfadeController.onArmAutoMixIncoming(item)
        override fun onBlendHeadroom(headroom: Float) = this@CrossfadeController.onBlendHeadroom(headroom)
        override fun yieldAnalysis() = this@CrossfadeController.yieldAnalysis()
        override fun handOff(out: ExoPlayer, into: ExoPlayer, m: Double, visualEnd: Double) =
            this@CrossfadeController.handOff(out, into, m, visualEnd)
        override fun onMixPositionJump(wrapped: Boolean) = this@CrossfadeController.onMixPositionJump(wrapped)
        override fun finish() = this@CrossfadeController.finish()
        override fun disarmTailLoopSession() = this@CrossfadeController.disarmTailLoopSession()
        override fun openFiltersWhenSilent() = this@CrossfadeController.openFiltersWhenSilent()
        override fun publishAutomixDetail() = this@CrossfadeController.publishAutomixDetail()
        override fun considerSmartTransition(duration: Long) = this@CrossfadeController.considerSmartTransition(duration)
        override fun isNativeRendererAvailable(player: ExoPlayer): Boolean =
            this@CrossfadeController.isNativeRendererAvailable(player)
        override fun analysisFor(item: MediaItem): TrackAnalysis = this@CrossfadeController.analysisFor(item)
        override fun loudnessGain(id: String): Float = this@CrossfadeController.loudnessGain(id)
        override fun loopRegion(item: MediaItem, length: Long, from: Double, to: Double, sampleRate: Int): LoopRegion? =
            this@CrossfadeController.loopRegion(item, length, from, to, sampleRate)
        override fun frozenPreparedFor(
            outItem: MediaItem,
            inItem: MediaItem,
            targetGen: Long,
            sampleRate: Int,
            djLevel: com.music.bitchord.playback.smart.choreo.DjLevel?,
        ): PreparedPair? = this@CrossfadeController.frozenPreparedFor(
            outItem,
            inItem,
            targetGen,
            sampleRate,
            djLevel ?: AppSettings.automixDjLevel.value,
        )
        override fun isStraightRoute(plan: LoopMix.Plan): Boolean = this@CrossfadeController.isStraightRoute(plan)

        override fun ensureOpportunity(outId: String, inId: String): Opportunity =
            this@CrossfadeController.ensureOpportunity(outId, inId)
        override val opportunity: Opportunity? get() = this@CrossfadeController.opportunity
        override val planRevision: Int get() = this@CrossfadeController.planRevision
        override fun reviseRoute(plan: Any): Int = this@CrossfadeController.reviseRoute(plan)
        override var executedRoute: Map<String, Any?>?
            get() = this@CrossfadeController.executedRoute
            set(v) { this@CrossfadeController.executedRoute = v }
        override val terminalRecorded: Boolean get() = this@CrossfadeController.terminalRecorded
        override var nativeBinding: NativeBinding?
            get() = this@CrossfadeController.nativeBinding
            set(v) { this@CrossfadeController.nativeBinding = v }
        override val routingEvidenceKeys: MutableSet<String> get() = this@CrossfadeController.routingEvidenceKeys
        override fun nativeRouteFields(plan: LoopMix.Plan, coarseDecline: Boolean?): Map<String, Any?> =
            this@CrossfadeController.nativeRouteFields(plan, actualConfiguration = true, coarseDecline = coarseDecline)
        override fun nativeSessionRouteFields(session: LoopMixSession, coarseDecline: Boolean?): Map<String, Any?> =
            this@CrossfadeController.nativeSessionRouteFields(session, coarseDecline)
        override fun routeRefusal(
            name: String,
            reason: String,
            stage: String,
            technique: String,
            destination: String?,
            context: Opportunity?,
            revision: Int,
            generation: Int?,
            coarseDecline: Boolean?,
        ) = this@CrossfadeController.routeRefusal(name, reason, stage, technique, destination, context, revision, generation, coarseDecline)
        override fun preparedRoute(route: Map<String, Any?>, context: Opportunity?, revision: Int, generation: Int?) =
            this@CrossfadeController.preparedRoute(route, context, revision, generation)
        override fun mixEvent(name: String, fields: Map<String, Any?>, context: Opportunity?) =
            this@CrossfadeController.mixEvent(name, fields, context)
    }

    internal val loopArmDriver = LoopArmDriver(loopDriverHost, loopState)
    internal val loopMixDriver = LoopMixDriver(loopDriverHost, loopState, loopArmDriver)

    private val mixStatusHost = object : MixStatusHost {
        override fun active(): ExoPlayer = this@CrossfadeController.active()
        override val outgoing: ExoPlayer? get() = this@CrossfadeController.outgoing
        override val incoming: ExoPlayer? get() = this@CrossfadeController.incoming
        override val tailTarget: ExoPlayer? get() = this@CrossfadeController.tailTarget
        override val handedOff: Boolean get() = this@CrossfadeController.handedOff
        override val blendArmLeadMs: Long get() = this@CrossfadeController.blendArmLeadMs
        override val mixExecutor: MixExecutor get() = this@CrossfadeController.mixExecutor
        override val loopSession: LoopMixSession? get() = this@CrossfadeController.loopSession
        override val classicFallbackReason: String get() = this@CrossfadeController.classicFallbackReason
        override val lastSmartPlan: TransitionPlan? get() = this@CrossfadeController.lastSmartPlan
        override val lastSmartPlanStartMs: Long get() = this@CrossfadeController.lastSmartPlanStartMs
        override fun isNativeRendererAvailable(player: ExoPlayer): Boolean = this@CrossfadeController.isNativeRendererAvailable(player)
        override fun analysisFor(item: MediaItem): TrackAnalysis = this@CrossfadeController.analysisFor(item)
        override fun stateOf(item: MediaItem, analysis: TrackAnalysis): TrackAnalysisState = this@CrossfadeController.stateOf(item, analysis)
        override val filters: TransitionFilters get() = this@CrossfadeController.filters
        override val tracks: TransitionFilters get() = this@CrossfadeController.tracks
    }

    internal val mixStatusDescriber = MixStatusDescriber(mixStatusHost)

    init {
        loopArmDriver.mixDriver = loopMixDriver
    }

    private val lyricSpans = java.util.concurrent.ConcurrentHashMap<String, LyricSpans>()
    private val lyricsAsked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val lyricsAskedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Asks for [item]'s synced lyrics once; a loop they vouch for needs no vocal mask. */
    private fun askLyrics(item: MediaItem, durationMs: Long) {
        val id = item.mediaId
        if (!lyricsAsked.add(id)) return
        lyricsAskedAt[id] = nowMs()
        scope.launch(Dispatchers.IO) {
            val lines = runCatching { syncedLines(item, durationMs) }.getOrNull()
            // Only line-synced lyrics say where a voice is; plain text says nothing about time.
            val spans = lines?.takeIf { found -> found.count { it.timeMs > 0 } >= 4 }?.let { found ->
                LoopMix.sungSpans(
                    found.map { line ->
                        val end = line.sungUntilMs ?: line.words.lastOrNull()?.endMs
                        LoopMix.Line(
                            line.timeMs,
                            end,
                            blank = line.isGap || line.text.isBlank(),
                            wordEndMs = line.words.lastOrNull()?.endMs,
                        )
                    },
                )
            }
            val cues = lines.orEmpty().map { line ->
                val end = line.sungUntilMs ?: line.words.lastOrNull()?.endMs ?: (line.timeMs + 2_000L)
                com.music.bitchord.playback.smart.LyricCue(
                    line.timeMs / 1000.0,
                    end / 1000.0,
                    line.text,
                )
            }
            lyricSpans[id] = LyricSpans(spans, cues)
        }
    }

    /** Counts a classic begin() guard exit (deduped per reason) and returns false. */
    private fun armDeath(reason: String): Boolean {
        val key = "$reason|${phase.name}"
        if (key != lastArmDeathKey) {
            lastArmDeathKey = key
            routeRefusal("mix.arm.death", reason, "accepted", "classic")
        }
        return false
    }

    private fun isSourceBacked(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        return uri.getQueryParameter("v") == null && uri.authority == "source"
    }

    private fun primeKey(plan: LoopMix.Plan, outItem: MediaItem, inItem: MediaItem, sampleRate: Int) =
        "${plan.rung}|${outItem.mediaId}->${inItem.mediaId}|${plan.out.loopStart}|${plan.out.loopEnd}|${plan.out.bars}|" +
            "${plan.inn.loopStart}|${plan.inn.loopEnd}|${plan.inn.bars}|$sampleRate"

    private fun loopRefusalFor(
        player: ExoPlayer,
        current: MediaItem,
        next: MediaItem,
        state: SmartAnalysis,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
    ): String? = loopArmDriver.loopRefusalFor(player, current, next, state, currentAnalysis, nextAnalysis)

    private fun dropVetoedLoop(pair: String, disarm: Boolean, coarseDecline: Boolean? = null) {
        loopArmDriver.dropVetoedLoop(pair, disarm, coarseDecline)
    }

    private fun dropRefusedLoop(pair: String, disarm: Boolean, reason: String, coarseDecline: Boolean? = null) {
        loopArmDriver.dropRefusedLoop(pair, disarm, reason, coarseDecline)
    }

    private fun primeLoopDecks(
        player: ExoPlayer,
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        nextDurationMs: Long,
    ) {
        loopArmDriver.primeLoopDecks(player, plan, outItem, inItem, durationMs, nextDurationMs)
    }

    private fun beginLoop(
        out: ExoPlayer,
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        nextDurationMs: Long,
    ): Boolean = loopArmDriver.beginLoop(out, plan, outItem, inItem, durationMs, nextDurationMs)

    private fun startLoopBuild(
        key: String,
        plan: LoopMix.Plan,
        out: ExoPlayer,
        into: ExoPlayer,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        nextDurationMs: Long,
        sampleRate: Int,
        armWhenReady: Boolean,
        preparedOverride: com.music.bitchord.playback.djmix.PreparedPair? = null,
        forceNoStems: Boolean = false,
    ) {
        loopArmDriver.startLoopBuild(key, plan, out, into, outItem, inItem, durationMs, nextDurationMs, sampleRate, armWhenReady, preparedOverride, forceNoStems)
    }

    private fun rememberLoopPair(
        outItem: MediaItem,
        inItem: MediaItem,
        outAnalysis: TrackAnalysis,
        inAnalysis: TrackAnalysis,
        durationMs: Long,
        nextDurationMs: Long,
    ) {
        loopArmDriver.rememberLoopPair(outItem, inItem, outAnalysis, inAnalysis, durationMs, nextDurationMs)
    }

    private fun snapshotLoopAnalysis(analysis: TrackAnalysis): TrackAnalysis =
        loopArmDriver.snapshotLoopAnalysis(analysis)

    private fun discardPrime() {
        loopArmDriver.discardPrime()
    }

    private fun shiftLoopOrFallback(out: ExoPlayer) {
        loopArmDriver.shiftLoopOrFallback(out)
    }

    private fun armLoop(
        session: LoopMixSession,
        out: ExoPlayer,
        into: ExoPlayer,
        nextIndex: Int,
        outPort: LoopMixPort,
        inPort: LoopMixPort,
    ) {
        loopArmDriver.armLoop(session, out, into, nextIndex, outPort, inPort)
    }

    private fun driveLoopArming() {
        loopArmDriver.driveLoopArming()
    }

    /** Called from the decode thread at the start of each stream/decode/resample slice. */
    fun markDeckStep(step: String) {
        loopArmDriver.markDeckStep(step)
    }

    private fun driveLoopMix() {
        loopMixDriver.driveLoopMix()
    }

    private fun rejectLiveCrown(): Boolean = loopMixDriver.rejectLiveCrown()

    private fun startLoopAlignment(session: LoopMixSession) {
        loopMixDriver.startLoopAlignment(session)
    }

    private fun publishLoopBeat(session: LoopMixSession, m: Double, now: Long) {
        loopMixDriver.publishLoopBeat(session, m, now)
    }

    private fun announceLoopPosition() {
        loopMixDriver.announceLoopPosition()
    }

    fun loopPositionMs(): Long? = loopMixDriver.loopPositionMs()

    private fun finishLoop() {
        loopMixDriver.finishLoop()
    }

    private fun loopBail(
        reason: String,
        settle: Boolean = true,
        preserveSeek: Boolean = false,
        deferDisarm: Boolean = false,
    ) = loopMixDriver.loopBail(reason, settle, preserveSeek, deferDisarm)

    private fun loopBail(
        reason: String,
        settle: Boolean = true,
        preserveSeek: Boolean = false,
        deferDisarm: Boolean = false,
        force: Boolean = false,
    ) = loopMixDriver.loopBail(reason, settle, preserveSeek, deferDisarm, force)

    private fun captureLoopExitObservation(session: LoopMixSession) {
        loopMixDriver.captureLoopExitObservation(session)
    }

    private fun loopFallBack(reason: String) {
        loopMixDriver.loopFallBack(reason)
    }

    private fun closeLater(renderer: LoopMixRenderer) {
        loopMixDriver.closeLater(renderer)
    }

    private fun closeSessionOffThread(session: LoopMixSession?) {
        loopMixDriver.closeSessionOffThread(session)
    }

    internal fun lyricSpansFor(trackId: String): List<LoopMix.Span>? = lyricSpans[trackId]?.spans

    fun onTrackChanged() {
        loopSuppressedPair = null
        loopSuppressedUntil = 0L
        countdownStabilizer.reset()
        hardWatchdogTriggeredPair = null
        hardWatchdogCooldownUntil = 0L
        notifyPairPreparer()
    }

    fun triggerRemixPrep() {
        notifyPairPreparer(force = true)
    }

    /**
     * Remixes the coming transition: same pair, same level, a different allowed recipe.
     *
     * Only before anything is armed or sounding (a running renderer is never mutated), and only
     * with enough lead to re-decode and rebuild. Returns false without touching the existing
     * preparation when no other recipe fits, so the Inspector never shows a remix that did not
     * happen.
     */
    fun remixCurrentTransition(): Boolean {
        val preparer = pairPreparer ?: return false
        if (phase == Phase.ARMING || phase == Phase.FADING || phase == Phase.LOOP_ARMING ||
            phase == Phase.LOOP_RUNNING || phase == Phase.TAIL || phase == Phase.BAILING
        ) return false
        val player = runCatching { active() }.getOrNull() ?: return false
        val mState = mixExecutor.currentState()
        val mPlan = mState.plan
        if (mPlan != null && (mState is MixState.Preparing || mState is MixState.Ready)) {
            val remainingMs = mPlan.frozenExitMs - player.currentPosition
            if (remainingMs < MixMachineConfig.DEFAULT.remixMinLeadMs) return false
            val (nextState, _) = mixExecutor.dispatch(MixEvent.RemixRequested)
            if (nextState.generation <= mState.generation) return false
            AutomixTelemetry.event(
                "mix.remix",
                mapOf(
                    "outId" to mPlan.outTrackId,
                    "inId" to mPlan.inTrackId,
                    "level" to mState.djLevel.name,
                    "excludedRecipe" to mPlan.recipeId,
                    "remainingMs" to remainingMs,
                    "phase" to phase.name,
                ),
            )
            discardPrime()
            return true
        }
        val request = prepRequestFor(player) ?: return false
        val currentSnap = preparer.snapshotFor(
            request.outTrack.id, request.inTrack.id, request.djLevel,
        ) ?: return false
        val startMs = currentSnap.plan?.out?.loopStart?.let { (it * 1000.0).roundToLong() }
            ?: (currentSnap.plannedWindowStartSec * 1000.0).roundToLong().takeIf { it > 0L }
            ?: return false
        val remainingMs = startMs - player.currentPosition
        if (remainingMs < maxOf(blendArmLeadMs, LOOP_PRIME_RETRY_MS + 2_500L)) return false
        val excluded = com.music.bitchord.playback.smart.choreo.RecipeId.fromCode(currentSnap.script?.recipeId)
            ?: currentSnap.v2Prep?.forcedRecipe
        if (!preparer.onRemixRequested(request, excluded)) return false
        AutomixTelemetry.event(
            "mix.remix",
            mapOf(
                "outId" to request.outTrack.id,
                "inId" to request.inTrack.id,
                "level" to request.djLevel.name,
                "excludedRecipe" to excluded?.name,
                "remainingMs" to remainingMs,
                "phase" to phase.name,
            ),
        )
        discardPrime()
        return true
    }

    /** The one request shape both ordinary preparation and a remix use for the shown pair. */
    private fun prepRequestFor(
        player: ExoPlayer,
        djLevelOverride: com.music.bitchord.playback.smart.choreo.DjLevel = AppSettings.automixDjLevel.value,
    ): com.music.bitchord.playback.djmix.PrepRequest? {
        if (!AppSettings.smartFadeEnabled.value || ListenTogether.state.value.inParty ||
            BitPerfectOutput.status.value.active || versionSwapActive()
        ) return null
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return null
        val currentItem = player.currentMediaItem ?: return null
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex >= player.mediaItemCount) return null
        val nextItem = player.getMediaItemAt(nextIndex)
        if (currentItem.isVideoOrigin || nextItem.isVideoOrigin) return null
        val outDurMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: queuedDurationMs(currentItem).takeIf { it > 0L }
            ?: (analysisFor(currentItem).duration * 1000.0).roundToLong().coerceAtLeast(0L)
        val inDurMs = nextItemDurationMs(nextIndex, nextItem).coerceAtLeast(0L)
        val sampleRate = loopPortOf(player)?.sampleRate?.takeIf { it > 0 } ?: 48_000
        val albumOrder = runCatching {
            isConsecutiveAlbumTrack(
                currentItem.mediaMetadata.albumTitle?.toString().orEmpty(), "",
                currentItem.mediaMetadata.trackNumber ?: 0,
                nextItem.mediaMetadata.albumTitle?.toString().orEmpty(), "",
                nextItem.mediaMetadata.trackNumber ?: 0,
            )
        }.getOrDefault(false)
        val fallbackSeconds = configuredFadeMs().takeIf { it > 0L }?.div(1000.0)
            ?: DEFAULT_SMART_FALLBACK_SECONDS
        return com.music.bitchord.playback.djmix.PrepRequest(
            outTrack = com.music.bitchord.playback.djmix.PrepTrack(
                id = currentItem.mediaId,
                durationSec = outDurMs / 1000.0,
                title = currentItem.mediaMetadata.title?.toString().orEmpty(),
                artist = currentItem.mediaMetadata.artist?.toString().orEmpty(),
                albumTitle = currentItem.mediaMetadata.albumTitle?.toString().orEmpty(),
                trackNumber = currentItem.mediaMetadata.trackNumber ?: 0,
                mediaItem = currentItem,
                uri = currentItem.localConfiguration?.uri,
            ),
            inTrack = com.music.bitchord.playback.djmix.PrepTrack(
                id = nextItem.mediaId,
                durationSec = inDurMs / 1000.0,
                title = nextItem.mediaMetadata.title?.toString().orEmpty(),
                artist = nextItem.mediaMetadata.artist?.toString().orEmpty(),
                albumTitle = nextItem.mediaMetadata.albumTitle?.toString().orEmpty(),
                trackNumber = nextItem.mediaMetadata.trackNumber ?: 0,
                mediaItem = nextItem,
                uri = nextItem.localConfiguration?.uri,
            ),
            djLevel = djLevelOverride,
            positionSec = player.currentPosition.coerceAtLeast(0L) / 1000.0,
            sampleRate = sampleRate,
            albumOrder = albumOrder,
            preferStraight = false,
            fallbackSeconds = fallbackSeconds,
            lyricsOut = lyricSpans[currentItem.mediaId]?.cues.orEmpty().toList(),
            lyricsIn = lyricSpans[nextItem.mediaId]?.cues.orEmpty().toList(),
        )
    }

    private fun notifyPairPreparer(force: Boolean = false, fromSeek: Boolean = false) {
        val preparer = pairPreparer ?: return
        val player = runCatching { active() }.getOrNull() ?: return
        val baseReq = prepRequestFor(player) ?: return
        val mState = mixExecutor.currentState()
        val matchingPair = mState.pair?.outTrackId == baseReq.outTrack.id &&
            mState.pair?.inTrackId == baseReq.inTrack.id
        val req = if (matchingPair && activeMachineGeneration > 0L) {
            baseReq.copy(machineGeneration = activeMachineGeneration)
        } else baseReq
        when {
            force -> {
                if (mState is MixState.Preparing || mState is MixState.Ready) {
                    mixExecutor.dispatch(MixEvent.RemixRequested)
                } else {
                    preparer.onRemixRequested(req)
                }
            }
            fromSeek -> preparer.onSeek(req)
            else -> if (!matchingPair || mState is MixState.Idle || mState is MixState.Failed) {
                preparer.onTrackStart(req)
            }
        }
    }

    private fun twoBarsMsFor(bpm: Double, beatSecondsFallback: Double): Long {
        val barSec = when {
            bpm.isFinite() && bpm > 1.0 -> 240.0 / bpm
            beatSecondsFallback.isFinite() && beatSecondsFallback > 0.05 -> beatSecondsFallback * 4.0
            else -> 0.0
        }
        return if (barSec > 0.0) (2.0 * barSec * 1000.0).roundToLong() else 0L
    }

    private fun classicActiveFxNames(r: Render): List<String> = buildList {
        if (r.style == TransitionStyle.DJ_FILTER || r.filterSweep > 0.0) add("Filter")
        if (r.bassSwap) add("BassSwap")
        if (r.echo) add("Echo")
        if (r.djEffect != com.music.bitchord.playback.smart.DjEffect.NONE) {
            val name = when (r.djEffect) {
                com.music.bitchord.playback.smart.DjEffect.ECHO -> "Echo"
                com.music.bitchord.playback.smart.DjEffect.REVERB -> "Reverb"
                com.music.bitchord.playback.smart.DjEffect.FLANGER -> "Flanger"
                com.music.bitchord.playback.smart.DjEffect.LOOP_ROLL -> "LoopRoll"
                com.music.bitchord.playback.smart.DjEffect.NONE -> ""
            }
            if (name.isNotEmpty() && name !in this) add(name)
        }
        if (r.vocalHandoff) add("VocalScoop")
    }

    /**
     * D2 Hard Watchdog & stuck-state guard:
     * 1. Cancels any `BUILDING` (`Phase.LOOP_ARMING && loopSession == null`) stuck for > 8_000 ms,
     *    closing late native renderers off-thread and firing the emergency path.
     * 2. At `T - 2 bars` before planned start (or `max(2 bars, 4.5s)` before EOF when no plan
     *    is armed yet), if not ARMED / LOOP_RUNNING / FADING, fires the emergency path and emits
     *    `mix.watchdog`.
     */
    private fun checkHardWatchdog() {
        val player = runCatching { active() }.getOrNull() ?: return
        if (!AppSettings.smartFadeEnabled.value) return
        if (versionSwapActive() || ListenTogether.state.value.inParty ||
            BitPerfectOutput.status.value.active || SleepTimer.afterTrack.value
        ) return
        if (!player.isPlaying || !player.playWhenReady) return
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex >= player.mediaItemCount) return
        val currentItem = player.currentMediaItem ?: return
        val nextItem = player.getMediaItemAt(nextIndex)
        if (currentItem.isVideoOrigin || nextItem.isVideoOrigin) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return

        val pairKey = "${currentItem.mediaId}->${nextItem.mediaId}"

        // Guard 1: > 8_000 ms stuck in BUILDING (LOOP_ARMING while loopSession == null).
        if (phase == Phase.LOOP_ARMING && loopSession == null &&
            loopBuildStartedAtMs > 0L && nowMs() - loopBuildStartedAtMs > STUCK_BUILD_GUARD_MS
        ) {
            val elapsed = nowMs() - loopBuildStartedAtMs
            val outItem = loopOutItem ?: currentItem
            val inItem = loopInItem ?: nextItem
            emitWatchdogAndTriggerEmergency(
                player = player,
                outItem = outItem,
                inItem = inItem,
                durationMs = duration,
                reason = "stuck-building-8s",
                plannedStartMs = loopTargetStartMs.takeIf { it > 0L },
                elapsedMs = elapsed,
                resetFromLoopArming = true,
            )
            return
        }

        val alreadyArmedOrActive = phase == Phase.ARMING ||
            phase == Phase.FADING ||
            phase == Phase.LOOP_RUNNING ||
            phase == Phase.TAIL ||
            phase == Phase.BAILING ||
            phase == Phase.SLEEP_FADE ||
            (phase == Phase.LOOP_ARMING && loopSession != null)
        if (alreadyArmedOrActive) return

        if (hardWatchdogTriggeredPair == pairKey && nowMs() < hardWatchdogCooldownUntil) return

        val outAnalysis = analysisFor(currentItem)
        val snap = pairPreparer?.snapshotFor(currentItem.mediaId, nextItem.mediaId, AppSettings.automixDjLevel.value)
        val twoBarsMs = twoBarsMsFor(
            outAnalysis.bpm.takeIf { it > 1.0 } ?: loopPlanned?.out?.bpm ?: snap?.plan?.out?.bpm ?: 0.0,
            lastSmartPlan?.beatSeconds ?: 0.0,
        )
        val plannedStartMs = loopTargetStartMs.takeIf { it > 0L }
            ?: loopPlanned?.out?.loopStart?.let { (it * 1000.0).roundToLong() }
            ?: lastSmartPlanStartMs.takeIf { it > 0L }
            ?: plannedTransitionFor(currentItem.mediaId)
            ?: snap?.plannedWindowStartSec?.takeIf { it > 0.0 }?.let { (it * 1000.0).roundToLong() }

        val eofFloorMs = maxOf(twoBarsMs, HARD_WATCHDOG_MIN_EOF_MS)
        val eofDeadlineMs = duration - eofFloorMs
        val planDeadlineMs = plannedStartMs?.takeIf { it in 1L until duration }?.let { it - twoBarsMs }
        val triggerPosMs = if (planDeadlineMs != null) minOf(planDeadlineMs, eofDeadlineMs) else eofDeadlineMs
        val pos = player.currentPosition
        if (pos < triggerPosMs) return

        val reason = if (phase == Phase.LOOP_ARMING && loopSession == null) {
            "watchdog-building-late"
        } else if (planDeadlineMs != null && pos >= planDeadlineMs) {
            "watchdog-t-minus-2bars"
        } else {
            "watchdog-near-eof"
        }
        emitWatchdogAndTriggerEmergency(
            player = player,
            outItem = currentItem,
            inItem = nextItem,
            durationMs = duration,
            reason = reason,
            plannedStartMs = plannedStartMs,
            resetFromLoopArming = phase == Phase.LOOP_ARMING && loopSession == null,
        )
    }

    /**
     * Resets any stalled build if requested, emits `mix.watchdog` + updates [PairPreparer],
     * and fires the emergency transition so Automix never falls off EOF dry.
     */
    private fun emitWatchdogAndTriggerEmergency(
        player: ExoPlayer,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        reason: String,
        plannedStartMs: Long? = null,
        elapsedMs: Long? = null,
        resetFromLoopArming: Boolean = false,
    ): Boolean {
        val fromPhase = phase.name
        if (resetFromLoopArming) {
            discardPrime()
            loopPlanned = null
            loopScript = null
            loopPlanKey = null
            loopTarget = null
            loopTargetStartMs = 0L
            outgoing = null
            incoming = null
            releaseHold()
            phase = Phase.IDLE
        }
        val pairKey = "${outItem.mediaId}->${inItem.mediaId}"
        val pos = player.currentPosition
        val remainingMs = (durationMs - pos).coerceAtLeast(0L)
        hardWatchdogTriggeredPair = pairKey
        hardWatchdogCooldownUntil = nowMs() + 5_000L
        lastFireFallbackReason = reason
        val outPort = loopPortOf(player)
        val inPort = loopPortOf(standby())
        val level = AppSettings.automixDjLevel.value
        val snap = pairPreparer?.snapshotFor(outItem.mediaId, inItem.mediaId, level)
        val snapPlan = snap?.plan
        val canTryNativeEmergency = reason != "stuck-building-8s" &&
            isNativeRendererAvailable(player) &&
            outPort != null &&
            inPort != null &&
            remainingMs >= 2_500L &&
            snap != null &&
            snapPlan != null &&
            snap.outRegion != null &&
            snap.inRegion != null &&
            snapPlan.out.loopStart * 1000.0 >= pos + 400L
        val attemptedPath = if (canTryNativeEmergency) "v2-native" else "classic"
        pairPreparer?.recordWatchdog(outItem.mediaId, inItem.mediaId, reason, path = attemptedPath)
        val fields = linkedMapOf<String, Any?>(
            "reason" to reason,
            "key" to pairKey,
            "outId" to outItem.mediaId,
            "inId" to inItem.mediaId,
            "pos" to pos,
            "remainingMs" to remainingMs,
            "plannedStartMs" to plannedStartMs,
            "path" to attemptedPath,
            "level" to AppSettings.automixDjLevel.value.name,
            "phase" to fromPhase,
        )
        if (elapsedMs != null) fields["elapsedMs"] = elapsedMs
        AutomixTelemetry.event("mix.watchdog", fields)
        return triggerEmergencyMix(player, outItem, inItem, durationMs, reason)
    }

    private fun triggerEmergencyMix(
        player: ExoPlayer,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        reason: String,
    ): Boolean {
        val remMs = (durationMs - player.currentPosition).coerceAtLeast(1_200L)
        val outPort = loopPortOf(player)
        val into = standby()
        val inPort = loopPortOf(into)
        val level = AppSettings.automixDjLevel.value
        val snap = pairPreparer?.snapshotFor(outItem.mediaId, inItem.mediaId, level)
        val snapPlan = snap?.plan
        val canTryNativeEmergency = reason != "stuck-building-8s" &&
            isNativeRendererAvailable(player) &&
            outPort != null &&
            inPort != null &&
            remMs >= 2_500L &&
            snap != null &&
            snapPlan != null &&
            snap.outRegion != null &&
            snap.inRegion != null &&
            snapPlan.out.loopStart * 1000.0 >= player.currentPosition + 400L

        if (canTryNativeEmergency) {
            val nextIdx = player.nextMediaItemIndex
            val inDurationMs = if (nextIdx != C.INDEX_UNSET) nextItemDurationMs(nextIdx, inItem) else 0L
            val key = primeKey(snapPlan!!, outItem, inItem, outPort!!.sampleRate)
            tempoEngine.endEase(clickless = false)
            MixDebug.reset()
            phase = Phase.LOOP_ARMING
            smartFadeActive = true
            handedOff = false
            outgoing = player
            incoming = into
            outgoingItemIndex = player.currentMediaItemIndex
            loopTarget = snapPlan
            loopTargetStartMs = (snapPlan.out.loopStart * 1000.0).roundToLong()
            holdAtEnd(player)
            deckTiming.clear()
            deckTiming.deadlineMs = DeckBudget.deadlineMs(nowMs(), player.isPlaying, loopTargetStartMs, player.currentPosition)
            loopBuildStartedAtMs = nowMs()
            lastFireFallbackReason = reason
            pairPreparer?.recordWatchdog(outItem.mediaId, inItem.mediaId, reason, path = "v2-native")
            startLoopBuild(
                key, snapPlan, player, into, outItem, inItem, durationMs, inDurationMs, outPort.sampleRate,
                armWhenReady = true, preparedOverride = snap, forceNoStems = true,
            )
            return true
        }
        val mState = mixExecutor.currentState()
        if (mState.pair != null && mState.stateKind !in setOf(
                MixStateKind.IDLE, MixStateKind.DONE, MixStateKind.FAILED, MixStateKind.SUSPENDED,
            )
        ) {
            mixExecutor.dispatch(
                MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, reason, recoverable = false),
            )
        }
        return emergencyClassicFadeWithFx(player, outItem, inItem, durationMs, reason)
    }

    private fun emergencyClassicFadeWithFx(
        player: ExoPlayer,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        reason: String,
    ): Boolean {
        classicFallbackReason = reason
        AutomixTelemetry.event(
            "mix.classic.reason",
            mapOf(
                "reason" to reason,
                "outId" to outItem.mediaId,
                "inId" to inItem.mediaId,
                "stage" to "watchdog-emergency",
            ),
        )
        val level = AppSettings.automixDjLevel.value
        val outAnalysis = analysisFor(outItem)
        val inAnalysis = analysisFor(inItem)
        val remMs = (durationMs - player.currentPosition).coerceAtLeast(1_200L)
        val targetFadeMs = when (level) {
            com.music.bitchord.playback.smart.choreo.DjLevel.SUBTLE -> 5_000L
            com.music.bitchord.playback.smart.choreo.DjLevel.CLUB -> 6_500L
            com.music.bitchord.playback.smart.choreo.DjLevel.FESTIVAL -> 8_000L
        }
        val fadeMsEmergency = minOf(remMs, targetFadeMs).coerceAtLeast(1_200L)
        val fadeSec = fadeMsEmergency / 1000.0
        val rawBeatSec = outAnalysis.beatInterval.takeIf { it.isFinite() && it in 0.25..1.25 }
            ?: outAnalysis.bpm.takeIf { it.isFinite() && it > 40.0 }?.let { 60.0 / it }
            ?: 0.5
        val effectiveBeatSec = minOf(rawBeatSec, fadeSec / 4.0).coerceAtLeast(0.25)
        val echoSec = (effectiveBeatSec * 2.0).coerceAtLeast(1.0)
        val inCueSec = inAnalysis.mixInTime.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val startSec = player.currentPosition.coerceAtLeast(0L) / 1000.0
        val endSec = durationMs / 1000.0
        val emergencyPlan = TransitionPlan(
            shouldStart = true,
            markerVisible = false,
            blocked = false,
            reason = reason,
            transitionStart = startSec,
            transitionEnd = endSec,
            fadeSeconds = fadeSec,
            transitionStyle = TransitionStyle.DJ_FILTER,
            incomingCueTime = inCueSec,
            incomingPlaybackRate = 1.0,
            outgoingPlaybackRate = 1.0,
            bassSwap = true,
            bassSwapFraction = 0.5,
            filterSweep = 1.0,
            filterVariant = FilterTransitionVariant.ECHO_RIDE,
            vocalOverlap = 0.5,
            beatSeconds = effectiveBeatSec,
            phaseLock = false,
            echoSeconds = echoSec,
            djEffect = com.music.bitchord.playback.smart.DjEffect.ECHO,
            mixSummary = "Emergency Echo Sweep",
        )
        lastFireFallbackReason = reason
        lastSmartPlan = emergencyPlan
        lastSmartPlanStartMs = player.currentPosition
        pairPreparer?.recordWatchdog(outItem.mediaId, inItem.mediaId, reason, path = "classic")
        val renderStyle = Render(
            style = TransitionStyle.DJ_FILTER,
            bassSwap = true,
            bassSwapFraction = 0.5,
            filterSweep = 1.0,
            filterVariant = FilterTransitionVariant.ECHO_RIDE,
            vocalOverlap = 0.5,
            advanced = true,
            beats = (fadeSec / effectiveBeatSec).coerceAtLeast(4.0),
            lockPhase = false,
            echoSeconds = echoSec,
            djEffect = com.music.bitchord.playback.smart.DjEffect.ECHO,
            vocalHandoff = false,
            beatSecondsF = effectiveBeatSec.toFloat(),
        )
        val began = begin(
            fade = fadeMsEmergency,
            endMs = durationMs,
            smart = true,
            cueTimeMs = (inCueSec * 1000.0).roundToLong(),
            playbackRate = 1.0,
            outgoingRate = 1.0,
            beatMs = (effectiveBeatSec * 1000.0).roundToLong(),
            renderStyle = renderStyle,
            plan = emergencyPlan,
        )
        if (began && standby().playbackState == Player.STATE_READY) {
            driveArming()
        }
        return began
    }

    // ---- Stats for nerds ----------------------------------------------------

    private var lastSmartPlan: TransitionPlan? = null
    private var lastSmartPlanStartMs = 0L

    private fun publishAutomixDetail() = mixStatusDescriber.publishAutomixDetail()

    private fun countdownProgress(secondsLeft: Double): Float = mixStatusDescriber.countdownProgress(secondsLeft)

    private fun status(
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
    ): AutomixStatus = mixStatusDescriber.status(
        headline, detail, style, progress, state, planType, bars, tempoPct, locked, rung, reason, overlapBars
    )

    private fun shownSeconds(player: ExoPlayer, startMs: Long): Double =
        mixStatusDescriber.shownSeconds(player, startMs)

    private fun describeAutomix(player: ExoPlayer, item: MediaItem): AutomixStatus =
        mixStatusDescriber.describeAutomix(player, item)

    private fun tempoPct(from: Double, to: Double): Float = mixStatusDescriber.tempoPct(from, to)

    private fun loopTempo(plan: LoopMix.Plan): String = mixStatusDescriber.loopTempo(plan)

    private fun mixStyle(plan: TransitionPlan): String = mixStatusDescriber.mixStyle(plan)

    private fun appSettingsMarkerShown(): Boolean = mixStatusDescriber.appSettingsMarkerShown()

    private fun anyDeckAudible(): Boolean = mixStatusDescriber.anyDeckAudible()

    private fun openFiltersWhenSilent() = mixStatusDescriber.openFiltersWhenSilent()

    private fun retireeAudible(): Boolean = mixStatusDescriber.retireeAudible()

    private fun openSpareFiltersWhenSilent() = mixStatusDescriber.openSpareFiltersWhenSilent()

    private fun openSurvivorFiltersWhenRetireeSilent() = mixStatusDescriber.openSurvivorFiltersWhenRetireeSilent()

    private fun bail(force: Boolean = false) {
        if (phase == Phase.IDLE || phase == Phase.BAILING) return
        // djmix10 freeze-once-armed: once the blend is audible (FADING,
        // or a live loop session), a load/queue/seek/error must not
        // destroy it. The mix is already paid for (decks decoded,
        // gains ramped); finishing it is always more graceful than
        // aborting it. Pre-arm phases may still bail (costs only the
        // standby decoder).
        // ARMING (marker shown or not) is now frozen once armed (djmix12 P1-4).
        if (!force && (phase == Phase.FADING || phase == Phase.TAIL || phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING ||
            phase == Phase.ARMING)) {
            val freezeKey = freezeDedupKey(
                phase.name,
                outgoing?.currentMediaItem?.mediaId,
                incoming?.currentMediaItem?.mediaId,
            )
            if (freezeKey != lastFreezeLogKey) {
                lastFreezeLogKey = freezeKey
                AutomixTelemetry.event("mix.freeze.onInterruption", mapOf("phase" to phase.name, "reason" to "interrupted-while-armed"))
            }
            return
        }
        cancelAutoMixVisual()
        if (phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING) {
            loopBail("interrupted", settle = false, preserveSeek = false, deferDisarm = false, force = force)
            return
        }
        Log.d(TAG, "bail from $phase")
        AppSettings.smartMixInProgress.value = false
        AppSettings.smartMixBlend.value = null
        clearMixView()
        if (!handedOff && phase != Phase.FADING) {
            if (armOutgoingPreFiltered) {
                armOutgoingPreFiltered = false
                survivorRecoveryPending = true
            }
            // Nothing was ever audible; no ramp to run.
            finish()
            return
        }
        // Deferred open (Seam A invariant): both tracks are audible here.
        // Reopening a parked filter now would glide open in ~30 ms under
        // full gain (the measured step). Leave the cutoff parked through
        // the BAILING dissolve; finish() opens it once gains reach zero.
        openFiltersWhenSilent()
        // Whichever track is *not* the session's is the one ramped away: the
        // outgoing tail after the handoff, the incoming track before it.
        if (handedOff) incoming?.volume = 1f else outgoing?.volume = 1f
        // Forced exit from an audible blend before its ride completed:
        // the survivor would strand parked if finish() only opened the spare.
        survivorRecoveryPending = true
        bailFromGain = leavingOnBail()?.volume ?: 0f
        bailStartedAt = nowMs()
        phase = Phase.BAILING
    }

    private fun leavingOnBail(): ExoPlayer? = if (handedOff) outgoing else incoming

    private fun finish() {
        (loopSession ?: tailLoopSession)?.let(::captureLoopExitObservation)
        // djmix14: capture the real completion values BEFORE clearing.
        // djmix15: loop mixes store BPMs in loopSession.plan, not
        // outgoingAnalysis/incomingAnalysis (which stay blank 0.0). Fall
        // back to the plan so loop completions never report 0.0. loopSession
        // is deliberately kept non-null through finish() by finishLoop() and
        // loopBail() for exactly this capture.
        val endPhase = phase.name
        val completionContext = opportunity
        val timingPair = lastTimingPair ?: completionContext?.let { it.outId to it.inId }
        val completionRoute = executedRoute ?: mapOf("technique" to "unknown")
        val completionBinding = nativeBinding.takeUnless { completionRoute["technique"] == "classic" }
        val completionRevision = completionBinding?.revision ?: planRevision
        val completionGeneration = completionBinding?.generation
        val didHandoff = handedOff
        val loopOutPort = loopSession?.outPort
        val didTakeOver = loopOutPort?.tookOver ?: capturedLoopTookOver
        val takeoverSpliceBufferStatus = if (loopOutPort != null) {
            loopOutPort.takeoverSpliceBufferStatus
        } else {
            capturedTakeoverSpliceBufferStatus
        }
        val retiringPlayer = if (didHandoff) outgoing else incoming
        val retiringPlayerPlayWhenReady = retiringPlayer?.playWhenReady
        val retiringPlayerPlaybackState = retiringPlayer?.playbackState
        val loopPlan = loopSession?.plan
        val outBpm = RetireGuard.loopOutBpm(outgoingAnalysis.bpm, loopPlan?.out?.bpm ?: 0.0)
        val inBpm = RetireGuard.loopInBpm(incomingAnalysis.bpm, loopPlan?.inn?.bpm ?: 0.0)
        val tailMs = tailDurationMs.takeIf { it > 0 } ?: (loopPlan?.let { RetireGuard.loopTailMs(it.exitTailSeconds) } ?: tailDurationMs)
        // vc67: clear the tail account so the next mix never inherits it.
        tailDurationMs = 0L
        val completeReason = lastCompleteReason ?: "finish"
        // djmix12 retire guard: an audible spare may not be retired
        // here. Convert to an emergency TAIL (350 ms) first; driveTail
        // quiets it to silence and re-enters finish() with phase==TAIL.
        // djmix16: LOOP_ARMING pre-takeover (!tookOver, !handedOff) is
        // never audible (prepare without play, sink never spliced) even
        // at volume 1.0 — silence it and finish at once (0 ms to IDLE)
        // instead of an 800 ms emergency tail that blocked classic
        // re-arming. Genuine audible (tookOver/handedOff) still tails.
        // djmix22 (vc64): a LOOP mix that has taken over AND handed off
        // completes via finishLoop() — the renderer exit-gate path, which
        // mutes the retiring player while the port still holds its silence
        // and hands the session to the incoming song at the handover
        // position — never via this bare emergency settle, which treated the
        // audible spare at volume 1.0 as a guard violation mid-mix (vc63:
        // mix.fire loop -> finish-guard 1.668 s later -> 350 ms emergency
        // tail -> seek-emergency-tail double teardown). finishLoop() nulls
        // loopSession before calling finish(), so the re-entry below is
        // never re-routed (violations reads -1, tookOver false).
        if (phase != Phase.IDLE && phase != Phase.TAIL && phase != Phase.BAILING) {
            val spare = if (handedOff) outgoing else incoming
            val vol = spare?.volume ?: 0f
            val preTakeoverLoopArming = phase == Phase.LOOP_ARMING && !handedOff &&
                loopSession?.outPort?.tookOver != true &&
                !RetireGuard.loopArmingSpareAudible(
                    tookOver = loopSession?.outPort?.tookOver == true,
                    handedOff = handedOff,
                    volume = vol,
                    playWhenReady = spare?.playWhenReady ?: false,
                )
            if (preTakeoverLoopArming) {
                spare?.volume = 0f
                spare?.playWhenReady = false
            } else if (RetireGuard.audible(vol)) {
                val routing = loopFinishAction(
                    phaseName = phase.name,
                    tookOver = loopSession?.outPort?.tookOver == true,
                    handedOff = handedOff,
                    exitGateViolations = loopSession?.renderer?.exitGateViolations() ?: -1,
                    audibleSpare = true,
                )
                if (routing == LoopFinishAction.FINISH_LOOP) {
                    finishLoop()
                    return
                }
                AutomixTelemetry.event(
                    "mix.exit.guard",
                    opportunityFields(completionContext) + mapOf("planRevision" to completionRevision) +
                        (executedRoute ?: mapOf("technique" to "unknown")) + mapOf(
                            "site" to "finish", "phase" to phase.name, "gainDb" to RetireGuard.gainDb(vol),
                            "initiatingExitReason" to initiatingExitReason,
                            "takeoverSpliceBufferDelegateStatus" to takeoverSpliceBufferStatus?.name,
                            "handedOff" to didHandoff, "tookOver" to didTakeOver,
                            "retiringPlayerPlayWhenReady" to retiringPlayerPlayWhenReady,
                            "retiringPlayerPlaybackState" to retiringPlayerPlaybackState,
                        ),
                )
                beginTailOrFinish(spare!!, "emergency-retire-guard", "finish:" + phase.name)
                return
            }
        }
        if (phase != Phase.TAIL) disarmTailLoopSession()
        clearAutoMixVisual()
        discardPrime()
        val wasTransitioning = phase != Phase.IDLE
        val isTerminal = wasTransitioning && !routingFallback && !terminalRecorded
        val completionOutcome = automixTerminalOutcome(
            isTerminal = isTerminal,
            executed = executedRoute != null,
            handedOff = didHandoff,
            reason = completeReason,
        )
        if (isTerminal) terminalRecorded = true
        val completionFields = completionRoute + mapOf(
            "stage" to if (isTerminal) "terminal" else "fallback",
            "terminal" to isTerminal, "outcome" to completionOutcome,
            "planRevision" to completionRevision, "buildGeneration" to completionGeneration,
            "phase" to endPhase, "handoff" to didHandoff, "bpmA" to outBpm, "bpmB" to inBpm,
            "tailMs" to tailMs, "reason" to completeReason,
            "expectedHandoverMs" to handoverExpectedMs, "actualHeardMs" to handoverHeardMs, "targetMs" to handoverTargetMs,
            "initiatingExitReason" to initiatingExitReason,
            "takeoverSpliceBufferDelegateStatus" to takeoverSpliceBufferStatus?.name,
            "handedOff" to didHandoff, "tookOver" to didTakeOver,
            "retiringPlayerPlayWhenReady" to retiringPlayerPlayWhenReady,
            "retiringPlayerPlaybackState" to retiringPlayerPlaybackState,
        )
        if (phase != Phase.IDLE) {
            Log.d(TAG, "finish from $phase")
            // Stamped under this guard rather than beside the assignment at the
            // bottom, because this function is idempotent and gets called with
            // nothing in flight: marking every one of those as a transition
            // just ended would keep pushing the mark forward and hold a waiting
            // caller off for as long as the calls kept coming.
            settledAt = nowMs()
            // djmix22: arm the 3 s post-handoff pause watch (tick() clears it).
            if (didHandoff) lastHandoffCompleteAtMs = settledAt
        }
        armOutgoingPreFiltered = false
        AppSettings.smartMixInProgress.value = false
        AppSettings.smartMixBlend.value = null
        clearMixView()
        // Before anything else: a transition that never handed off leaves the
        // outgoing player as the session, and it must not pause at the end of
        // its track for a blend that is not coming.
        releaseHold()
        // Deferred open (Seam A invariant): only reopen once both decks are
        // silent. The retiring deck was dissolved to zero by TAIL/BAILING
        // before this point; the session deck is either already full-band
        // at progress 1 (two-phase exit legs) or will be driven from the
        // parked state by the next arm while silent. Opening while audible
        // is the ~30 ms step.
        // vc77: past the guard above the retiring spare has dissolved to
        // <= 0.001f (or was silenced pre-takeover), so silence it
        // unconditionally when handed off; then also open the spare filter
        // (see openSpareFiltersWhenSilent) so a handed-off survivor at 1
        // never strands it at DECLINE/SWEEP park. Colour is released
        // smoothly before echoes are parked.
        if (handedOff) outgoing?.volume = 0f
        openFiltersWhenSilent()
        if (survivorRecoveryPending) {
            survivorRecoveryPending = false
            // Abnormal exit: return the survivor to full-band via the role
            // mapping and leave the retiring spare filter untouched (Seam A:
            // never open both while one is audible). The spare stays parked
            // until its next arm, when begin() reopens it while silent.
            openSurvivorFiltersWhenRetireeSilent()
        } else {
            openSpareFiltersWhenSilent()
        }
        filters.releaseColour()
        filters.releaseEchoes()
        onBlendHeadroom(1f)
        // A loop mix already plays the incoming player at 1× (the renderer did
        // the tempo). [incomingPlaybackRate] belongs to the dual-player path
        // and can still hold the previous blend's rate. Walking the player
        // off that rate, or re-applying the speed it already has, changes
        // AudioTrack playback params under the first notes after the splice.
        val nativeHandover = phase == Phase.LOOP_RUNNING || phase == Phase.LOOP_ARMING
        val releaseFrom = if (render.buildup) {
            deckPlayedRate(incomingPlaybackRate, BUILDUP_RISE)
        } else {
            incomingPlaybackRate
        }
        render = Render()
        tempoEngine.clearPhaseNudge()

        if (handedOff) {
            // The roles have already traded: the incoming player is the session
            // and owns the queue from here, and the outgoing one is spare.
            incoming?.let {
                it.volume = 1f
                // djmix18 (Bug 1): NEVER run beginEase()/driveEase() WSOLA speed steps
                // across B's solo intro after handover. handOff() already reset this player
                // to the listener speed while the outgoing tail masked the single change;
                // finish() now only re-asserts that speed (no steps). Stepping 1.62s apart
                // over the solo intro is what stuttered on vocals/beats. A native mix skips
                // this for the same reason; classic handoffs get the same guarantee.
                @Suppress("UNUSED_VARIABLE") val releaseFromUnused = releaseFrom
                tempoEngine.onHandedOffFinish(it)
            }
            outgoing?.let(::retire)
        } else {
            // The transition never became audible, so the session player never
            // moved and the standby is the one to throw away.
            outgoing?.let { out ->
                out.volume = 1f
                // Sped up for a blend that never came: walk it back like any stretch.
                if (outgoingPlaybackRate != 1.0) {
                    tempoEngine.beginEase(out, outgoingPlaybackRate, out.currentMediaItem?.let(analysisFor) ?: TrackAnalysis())
                }
            }
            incoming?.let(::retire)
        }

        outgoing = null
        incoming = null
        // Clear the dissolved-tail reference only after retire and after the
        // Seam A gates above have run. An active tail is preserved until
        // driveTail has silenced it, and finish's early returns (emergency
        // tail / finishLoop) never reach here, so a new tailTarget is never
        // cleared early. Without this, retire's volume=1 reset leaves a stale
        // audible reference that blocks openFiltersWhenSilent/
        // openSpareFiltersWhenSilent and the next begin.
        tailTarget = null
        handedOff = false
        outgoingItemIndex = C.INDEX_UNSET
        queuedItemCount = 0
        plannedNextId = null
        incomingCueTimeMs = 0L
        incomingPlaybackRate = 1.0
        outgoingPlaybackRate = 1.0
        tempoEngine.resetRamp()
        outgoingAnalysis = TrackAnalysis()
        incomingAnalysis = TrackAnalysis()
        localBlendInFlight = false
        lastPairAnalysisSignature = null
        loopBuildStartedAtMs = 0L
        phase = Phase.IDLE
        if (wasTransitioning) {
            mixEvent("mix.complete", completionFields, completionContext)
            if (isTerminal) {
                opportunity = null
                initiatingExitReason = null
            }
            capturedTakeoverSpliceBufferStatus = null
            capturedLoopTookOver = null
            classicFallbackReason = ""
            if (!routingFallback) {
                loopSuppressedPair = null
                loopSuppressedUntil = 0L
                countdownStabilizer.reset()
                hardWatchdogTriggeredPair = null
                hardWatchdogCooldownUntil = 0L
                val timingOutId = timingPair?.first.orEmpty()
                val timingInId = timingPair?.second.orEmpty()
                if (timingOutId.isNotBlank() && timingInId.isNotBlank()) {
                    pairPreparer?.markDone(timingOutId, timingInId)
                }
                val levelName = AppSettings.automixDjLevel.value.name
                val finalTimings = pairPreparer?.snapshotFor(timingOutId, timingInId)?.timings
                    ?: com.music.bitchord.playback.djmix.PairTimings(
                        outId = timingOutId,
                        inId = timingInId,
                        prepStartMs = classicPrepStartMs,
                        armLeadMs = classicArmLeadMs,
                        watchdog = lastFireFallbackReason.startsWith("watchdog") || lastFireFallbackReason == "stuck-building-8s" || lastFireFallbackReason.startsWith("natural-override"),
                        path = lastFirePath.ifBlank { if (didTakeOver == true) "v2-native" else "classic" },
                        level = levelName,
                        move = lastFireMove,
                        fxCount = lastFireFxCount,
                        stems = lastFireStems,
                        fallbackReason = lastFireFallbackReason,
                    )
                AutomixTelemetry.event("mix.timing", finalTimings.toTelemetryMap())
                if (isTerminal && didHandoff) {
                    val mState = mixExecutor.currentState()
                    if (mState is MixState.Armed) {
                        mixExecutor.dispatch(MixEvent.Tick(mState.armAtMs, isPlaying = true))
                    }
                    val afterArm = mixExecutor.currentState()
                    if (afterArm is MixState.Running) {
                        mixExecutor.dispatch(
                            MixEvent.Tick(
                                positionMs = afterArm.plan.frozenExitMs + afterArm.plan.overlapDurationMs,
                                isPlaying = true,
                            ),
                        )
                    }
                    if (mixExecutor.currentState() is MixState.HandingOver) {
                        mixExecutor.dispatch(MixEvent.HandoverComplete)
                    }
                } else if (isTerminal) {
                    val mState = mixExecutor.currentState()
                    if (mState.pair != null && mState.stateKind !in setOf(
                            MixStateKind.IDLE, MixStateKind.DONE, MixStateKind.FAILED, MixStateKind.SUSPENDED,
                        )
                    ) {
                        mixExecutor.dispatch(MixEvent.UserSkip)
                    }
                }
                lastTimingPair = null
                classicPrepStartMs = 0L
                classicArmLeadMs = 0L
                lastFirePath = ""
                lastFireMove = ""
                lastFireFxCount = 0
                lastFireStems = 0
                lastFireFallbackReason = ""
            }
            if (routingFallback) {
                nativeBinding = null
                routingFallback = false
            }
            handoverExpectedMs = 0L
            handoverHeardMs = -1L
            handoverTargetMs = 0L
            idleStallSinceMs = 0L
            idleStallPrepared = false
            fadingStallSinceMs = 0L
            lastFreezeLogKey = null
            onSettled()
        }
        lastCompleteReason = null
    }

    /** Still a next track, still playing, still switched on — by whichever setting armed this one. */
    private fun stillWorthFading(): Boolean {
        val stillOn = if (smartFadeActive) AppSettings.smartFadeEnabled.value else configuredFadeMs() > 0L
        return stillOn && (outgoing ?: active()).hasNextMediaItem()
    }

    /**
     * Puts a player back in the drawer: emptied, silent no longer, and on the
     * listener's own playback rate again.
     *
     * The volume matters as much as the emptying. A player left at the gain it
     * faded out on is the next transition's *incoming* player, and it would
     * arrive already turned down — so the reset is part of retiring it, not part
     * of preparing it.
     */
    private fun retire(player: ExoPlayer) {
        // djmix12 invariant: retire() must only ever see silence.
        if (player.volume > 0.001f) {
            AutomixTelemetry.event("mix.exit.violated", mapOf("site" to "retire", "gainDb" to RetireGuard.gainDb(player.volume)))
        }
        player.stop()
        player.clearMediaItems()
        // This is the next transition's incoming player, and from its handoff
        // the session: a hold left on it would pause every track at its end.
        player.pauseAtEndOfMediaItems = false
        player.volume = 1f
        player.setPlaybackSpeed(deckSpeed(AppSettings.playbackSpeed.value, 1.0))
        tracks.incomingTempo(1f)
        tracks.outgoingTempo(1f)
    }

    // ---- Numbers ------------------------------------------------------------

    private fun configuredFadeMs(): Long = AppSettings.crossfadeSeconds.value * 1000L

    /**
     * The configured length, kept off tracks too short to spend it on. A fade
     * that swallows a third of a song stops being a transition and starts being
     * the arrangement.
     */
    private fun fadeFor(duration: Long): Long {
        val configured = configuredFadeMs()
        if (duration == C.TIME_UNSET || duration <= 0L) return configured
        return minOf(configured, duration / 3).coerceAtLeast(0L)
    }

    /**
     * [filters] addressed by track rather than by session role.
     *
     * The service wires [TransitionFilters.incoming] to the session player's
     * sink and [TransitionFilters.outgoing] to the spare's, which describes the
     * two tracks only once the handoff has moved the session. The handoff is
     * halfway through the blend now, so for the first half the roles are the
     * other way round, and each ride would otherwise be filtering the wrong
     * song.
     */
    private val tracks = object : TransitionFilters {
        override fun incoming(lowPassHz: Float, highPassHz: Float) {
            if (handedOff) filters.incoming(lowPassHz, highPassHz) else filters.outgoing(lowPassHz, highPassHz)
        }

        override fun outgoing(lowPassHz: Float, highPassHz: Float) {
            if (handedOff) filters.outgoing(lowPassHz, highPassHz) else filters.incoming(lowPassHz, highPassHz)
        }

        override fun incomingLeadMs(): Long =
            if (handedOff) filters.incomingLeadMs() else filters.outgoingLeadMs()

        override fun outgoingLeadMs(): Long =
            if (handedOff) filters.outgoingLeadMs() else filters.incomingLeadMs()

        override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) {
            if (handedOff) {
                filters.outgoingEcho(delaySeconds, send, dry)
            } else {
                filters.incomingEcho(delaySeconds, send, dry)
            }
        }

        override fun incomingTempo(rate: Float) {
            if (handedOff) filters.incomingTempo(rate) else filters.outgoingTempo(rate)
        }

        override fun outgoingTempo(rate: Float) {
            if (handedOff) filters.outgoingTempo(rate) else filters.incomingTempo(rate)
        }

        override fun outgoingColour(
            effect: com.music.bitchord.playback.smart.DjEffect,
            amount: Float,
            beatSeconds: Float,
            scoop: Float,
        ) {
            if (handedOff) filters.outgoingColour(effect, amount, beatSeconds, scoop)
            else filters.incomingColour(effect, amount, beatSeconds, scoop)
        }

        override fun incomingColour(
            effect: com.music.bitchord.playback.smart.DjEffect,
            amount: Float,
            beatSeconds: Float,
            scoop: Float,
        ) {
            if (handedOff) filters.incomingColour(effect, amount, beatSeconds, scoop)
            else filters.outgoingColour(effect, amount, beatSeconds, scoop)
        }

        override fun incomingMidDb(gainDb: Float) {
            if (handedOff) filters.incomingMidDb(gainDb) else filters.outgoingMidDb(gainDb)
        }

        override fun outgoingMidDb(gainDb: Float) {
            if (handedOff) filters.outgoingMidDb(gainDb) else filters.incomingMidDb(gainDb)
        }
    }

    private val fadeCurves: FadeRideCurves = FadeRideCurves(filters, tracks)

    private val tempoEngine = EaseTempoEngine(object : EaseTempoHost {
        override val scope: CoroutineScope get() = this@CrossfadeController.scope
        override val filters: TransitionFilters get() = this@CrossfadeController.filters
        override val tracks: TransitionFilters get() = this@CrossfadeController.tracks
        override fun active(): ExoPlayer = this@CrossfadeController.active()
        override fun incoming(): ExoPlayer? = this@CrossfadeController.incoming
        override fun isHandedOff(): Boolean = handedOff
        override fun isIdle(): Boolean = phase == Phase.IDLE
        override fun nowMs(): Long = this@CrossfadeController.nowMs()
        override fun positionMs(player: ExoPlayer): Double = this@CrossfadeController.positionMs(player)
        override fun userPlaybackSpeed(): Float = AppSettings.playbackSpeed.value
        override fun inParty(): Boolean = ListenTogether.state.value.inParty
    })

    private val mixViewHost: MixViewHost = object : MixViewHost {
        override val scope: CoroutineScope get() = this@CrossfadeController.scope
        override val fadeCurves: FadeRideCurves get() = this@CrossfadeController.fadeCurves
        override val tempoEngine: EaseTempoEngine get() = this@CrossfadeController.tempoEngine
        override val mixWaveform: (MediaItem, Long, Double, Double, Int) -> MixWaveform.Bands?
            get() = this@CrossfadeController.mixWaveform
        override fun nowMs(): Long = this@CrossfadeController.nowMs()
        override fun positionMs(player: ExoPlayer): Double = this@CrossfadeController.positionMs(player)
        override fun isSmartFadeActive(): Boolean = smartFadeActive
        override fun isHandedOff(): Boolean = handedOff
        override fun isIdle(): Boolean = phase == Phase.IDLE
        override val outgoingAnalysis: TrackAnalysis get() = this@CrossfadeController.outgoingAnalysis
        override val incomingAnalysis: TrackAnalysis get() = this@CrossfadeController.incomingAnalysis
        override val planInFlight: TransitionPlan? get() = this@CrossfadeController.planInFlight
        override val fadeMs: Long get() = this@CrossfadeController.fadeMs
        override val fadeEndMs: Long get() = this@CrossfadeController.fadeEndMs
        override val incomingCueTimeMs: Long get() = this@CrossfadeController.incomingCueTimeMs
        override val mediaRatio: Double get() = this@CrossfadeController.mediaRatio
        override fun currentRideSpec(): RideSpec = render.rideSpec
        override fun rideSpecFor(plan: TransitionPlan, advanced: Boolean): RideSpec =
            renderFor(plan, advanced).rideSpec
    }

    private val mixViewPublisher = MixViewPublisher(mixViewHost)

    private val mixEventHost: MixEventHost = object : MixEventHost {
        override val phaseName: String get() = phase.name
        override val scrubEpoch: Long get() = this@CrossfadeController.scrubEpoch
        override fun isIdle(): Boolean = phase == Phase.IDLE
        override fun active(): ExoPlayer = this@CrossfadeController.active()
        override val outgoingPlayer: ExoPlayer? get() = outgoing
        override fun setOutgoingPlayer(player: ExoPlayer?) {
            outgoing = player
        }
        override fun setIncomingPlayer(player: ExoPlayer?) {
            incoming = player
        }
        override fun setHandedOff(handedOff: Boolean) {
            this@CrossfadeController.handedOff = handedOff
        }
        override fun onSleepFadeStarted() {
            phase = Phase.SLEEP_FADE
        }
        override fun onSleepFadeFinished() {
            outgoing = null
            phase = Phase.IDLE
        }
        override fun onSleepFadeAborted() {
            outgoing = null
            phase = Phase.IDLE
        }
        override fun isSleepTimerAfterTrack(): Boolean = SleepTimer.afterTrack.value
        override fun cancelSleepTimer() {
            SleepTimer.cancel()
        }
        override fun leavingOnBail(): ExoPlayer? = this@CrossfadeController.leavingOnBail()
        override fun finish() {
            this@CrossfadeController.finish()
        }
        override fun nowMs(): Long = this@CrossfadeController.nowMs()
        override val bailStartedAt: Long get() = this@CrossfadeController.bailStartedAt
        override val bailFromGain: Float get() = this@CrossfadeController.bailFromGain
        override fun isRanOn(player: ExoPlayer): Boolean = ranOn(player)
        override fun onBlendHeadroom(headroom: Float) {
            this@CrossfadeController.onBlendHeadroom(headroom)
        }
        override fun onNewOpportunity() {
            countdownStabilizer.clearFailedNativeRecovery()
            planGeneration++
            planResultGeneration = -1L
            fallbackNaturalGeneration = -1L
            naturalProvisionalUntilPosMs = -1L
            initiatingExitReason = null
            capturedTakeoverSpliceBufferStatus = null
            capturedLoopTookOver = null
        }
        override fun onResetOpportunityEventKeys() {
            lastRejectedLogKey = null
            lastEmittedPlanVerdict = null
            lastArmDeathKey = null
            lastFallbackLogKey = null
        }
    }

    private val mixEventRouter = MixEventRouter(mixEventHost)

    // Reflection shims retained solely for existing unit tests (Djmix31AppTest,
    // Vc70DeclineRideAppTest, Vc70ExitLegAppTest) until M9 D1 test cleanup.
    private fun rideStyle(progress: Float) = fadeCurves.rideStyle(render.rideSpec, progress)
    private fun rideColour(progress: Float) = fadeCurves.rideColour(render.rideSpec, progress)
    private fun rideVocalSeparation(progress: Float) = fadeCurves.rideVocalSeparation(render.rideSpec, progress)
    private fun rideMuffle(progress: Float) = fadeCurves.rideMuffle(render.rideSpec, progress)
    private fun rideBeatlessBridge(progress: Float) = fadeCurves.rideBeatlessBridge(render.rideSpec, progress)
    private fun rideFilterSweep(progress: Float) = fadeCurves.rideFilterSweep(render.rideSpec, progress)
    private fun rideDecline(progress: Float) = fadeCurves.rideDecline(render.rideSpec, progress)
    private fun rideFallbackBassHandoff(progress: Float) = fadeCurves.rideFallbackBassHandoff(render.rideSpec, progress)
    private fun rideBassSwap(progress: Float) = fadeCurves.rideBassSwap(render.rideSpec, progress)
    private fun mixRise(progress: Float): Float = fadeCurves.mixRise(render.rideSpec, progress)
    private fun mixFall(progress: Float): Float = fadeCurves.mixFall(render.rideSpec, progress)

    // There is deliberately no second, equal-gain pair here any more. It existed
    // for the handoff of a track from one player to the other, where the two
    // signals were the same signal and so summed in amplitude rather than in
    // power. Nothing in this class renders the same audio twice now, so every
    // gain it applies is a gain against a genuinely different track, and
    // equal-power is right everywhere.

    private companion object {
        /** How much of each track either side of the overlap the mix popup shows, as a fraction of the overlap. */
        const val MIX_VIEW_CONTEXT = 0.24

        /** Waveform columns per second of audio in the mix popup, and a ceiling for very long windows. */
        const val MIX_WAVE_BINS_PER_SECOND = 40.0
        const val MIX_WAVE_MAX_BINS = 3_000

        /** How far ahead of a transition its waveforms start decoding. */
        const val MIX_PREFETCH_MS = 30_000L

        /** How often a paused track's seek-bar marker is re-planned. */
        const val PAUSED_MARKER_STEP_MS = 1_000L

        /** Extra decoded either side of the popup's own window, for a plan that shifts before it arms. */
        const val MIX_PREFETCH_PAD_SECONDS = 3.0

        /** Points the mix popup's fader lines are sampled at. */
        const val MIX_VIEW_FADER_POINTS = 64

        /** How long a waveform that failed to decode is left before it is tried again. */
        const val MIX_WAVE_RETRY_MS = 5_000L

        /** How long the popup's preview outlives the last plan that laid it out. */
        const val MIX_PREVIEW_STALE_MS = 1_500L

        const val TAG = "BitChordCrossfade"

        /**
         * Used only before a pair has been analysed, or when the evidence is
         * too weak for more than a plain fade — see [considerSmartTransition].
         * Once real analysis lands, the overlap is sized from tempo and
         * structure instead and this is never read.
         */
        const val DEFAULT_SMART_FALLBACK_SECONDS = 6.0

        /** Ramp used when a fade is interrupted. */
        const val BAIL_MS = 120L

        /** djmix13: how long a shown loop stays suppressed after a watchdog trip. */
        const val LOOP_SUPPRESSION_MS = 90_000L

        /** Stuck-build guard: any BUILDING state > 8s cancels & invokes emergency path. */
        const val STUCK_BUILD_GUARD_MS = 8_000L

        /** Minimum EOF floor for Hard Watchdog (D2): max(2 bars, 4.5s). */
        const val HARD_WATCHDOG_MIN_EOF_MS = 4_500L

        /** djmix26: brief post-scrub settle window so midway seeks pick one stable plan. */
        const val SCRUB_SETTLE_MS = 350L

        private val HARD_LOOP_REFUSALS = setOf(
            "no renderer",
            "playback speed is not 1x",
            "skip silence is on",
            "sleep timer",
            "analysed off another recording",
            "this output can't take it",
            "no standby sink",
            "sung veto",
            "stale loop start passed",
        )

        /**
         * Head start the standby gets to open the incoming track and buffer to
         * its cue point.
         *
         * Sized for a *stream being opened*, which is the only thing arming
         * waits on now — there is no alignment to converge. Usually instant, as
         * the next track has normally been read ahead onto disk by the time it
         * matters, but a cold one has to be resolved and fetched, and a
         * transition that arrives before its incoming track is ready is one that
         * gets dropped.
         */
        const val ARM_LEAD_MS = 4_000L

        /**
         * The earliest, in wall-clock ms before the next item starts, that
         * Media3 1.11 lets a player's renderers move on to reading it:
         * `ExoPlayerImplInternal.DURATION_TO_ADVANCE_READING_THRESHOLD_US`,
         * checked in `maybeUpdateReadingPeriod` against the playing position
         * divided by speed (javap, 1.11.0). Before this point [holdAtEnd]
         * provably cannot flush; after it, it might. Re-check on a Media3 bump.
         */
        const val MEDIA3_READ_AHEAD_LIMIT_MS = 10_000L

        /**
         * Margin on [MEDIA3_READ_AHEAD_LIMIT_MS] for a position report up to a
         * tick stale and the message's hop to the playback thread.
         */
        const val HOLD_SAFETY_MS = 1_000L

        /**
         * How far ahead of the last safe moment [holdAhead] starts holding: a
         * dozen idle ticks, so one late tick never misses it.
         */
        const val HOLD_AHEAD_MS = 3_000L

        /**
         * States in which a track is measured well enough to be *entered* on.
         *
         * [TrackAnalysisState.REFINING] belongs here because the entry fields —
         * tempo, beat confidence, the cue point — are all measured over the
         * track's opening, which is precisely what a head-only pass reads. The
         * whole-track pass it is waiting on adds the *exit* half: content end,
         * outro, mix-out anchors, the energy curve. Those matter when this track
         * is later the one being left, and not at all for the transition into it.
         */
        val MEASURED_ENOUGH_TO_ENTER_ON = setOf(
            TrackAnalysisState.ANALYSED,
            TrackAnalysisState.REFINING,
        )

        /**
         * Longest a transition will wait on an incoming track that will not
         * become ready. Past this the queue is left to move on plainly, which is
         * a missed crossfade rather than a broken one.
         */
        const val ARM_TIMEOUT_MS = 12_000L

        /**
         * Hands off early when the outgoing track is this close to its end,
         * before its still-intact queue can advance it. Several fade ticks, so
         * one late tick cannot miss it.
         */
        const val HANDOFF_GUARD_MS = 400L

        /**
         * How far a freshly derived beat anchor may sit from the published one
         * before it is worth sending. Above a tick's worth of position jitter,
         * well under what the eye reads as a pulse off the beat.
         */
        const val ANCHOR_TOLERANCE_NANOS = 25_000_000L

        /** Relative tempo change worth re-publishing; beatmatch rates are fixed per blend. */
        const val TEMPO_TOLERANCE = 0.005f

        const val IDLE_STEP_MS = 250L

        /** Coalesces a burst of analysis updates for the same pair into one re-plan. */
        const val ANALYSIS_REPLAN_DEBOUNCE_MS = 200L
        /** A fallback NATURAL is final only once arming is at most this far away. */
        const val FALLBACK_NATURAL_HORIZON_MS = 12_000L

        /**
         * Arming only waits on a buffer now — nothing is being converged — so
         * this is about how promptly the fade can start once the incoming track
         * is ready, not about a control loop's step size.
         */
        const val ARM_STEP_MS = 40L
        const val FADE_STEP_MS = 30L

        /** A new position report landed somewhere in the last fade tick; half of one is the best guess. */
        const val HALF_TICK_NANOS = FADE_STEP_MS * 500_000L

        /** Never run a stalled player's position on further than its loop could plausibly sleep. */
        const val MAX_EXTRAPOLATION_NANOS = 600_000_000L
        const val BAIL_STEP_MS = 15L

        /** The loop mix's tick: fine enough for the handoff and the beat pulse, which land on beats. */
        const val LOOP_STEP_MS = 20L

        /** The loop mix's setup: how far ahead its loop must start, and when its sinks are armed. */
        const val LOOP_SETUP_SECONDS = 15.0
        const val POST_MIX_ANALYSIS_DELAY_MS = 12_000L
        const val LOOP_ARM_LEAD_MS = 12_000L
        /** djmix14: prime lead — start decoding as soon as the pair is planned within 40 s. */
        const val LOOP_PRIME_LEAD_MS = 45_000L
        const val LOOP_MIN_LEAD_MS = 9_000L

        /**
         * How close to the planned bar the decks may still be unfinished before
         * that bar is given up. The decode itself starts as soon as the plan
         * exists; this is only the splice, which the sink has to be holding
         * before the playhead reaches it. Missing it shifts to the next bar
         * instead of abandoning the loop.
         */
        const val LOOP_COMMIT_MS = 400L

        /** A deck build that came back empty is tried again, not on every tick. */
        const val LOOP_PRIME_RETRY_MS = 2_000L

        /** After the incoming player starts, how long its clock settles before it is measured against the deck. */
        const val LOOP_ALIGN_SETTLE_SECONDS = 1.2
        const val LOOP_ALIGN_ROUNDS = 5
        const val LOOP_ALIGN_SAMPLES = 15
        const val LOOP_ALIGN_SAMPLE_MS = 30L

        /** Past the incoming player's takeover, before the outgoing one is retired. */
        const val LOOP_END_MARGIN_SECONDS = 0.3

        /** A position this far back is a loop wrap; and how often the ramp re-anchors controllers. */
        const val LOOP_WRAP_MS = 400L
        const val LOOP_ANNOUNCE_MS = 2_000L

        /** A retired sink has long stopped rendering by then. */
        const val LOOP_RENDERER_CLOSE_DELAY_MS = 1_500L
    }

}
