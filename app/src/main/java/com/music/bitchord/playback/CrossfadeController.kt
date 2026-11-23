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
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LocalBlendAdmission
import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.shouldDeclineToMix
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhraseMaps
import com.music.bitchord.playback.smart.PhraseChoice
import com.music.bitchord.playback.smart.PhrasePlanner
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.SongMaps
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.FILTER_ECHO_AT
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.AutomixStatus
import com.music.bitchord.playback.smart.DeckBudget
import com.music.bitchord.playback.smart.MixGain
import com.music.bitchord.playback.smart.RetireGuard
import com.music.bitchord.playback.smart.SeekAction
import com.music.bitchord.playback.smart.MixWaveform
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionAlternatives
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionEvidenceCache
import com.music.bitchord.playback.smart.localBlendFitsDeckSpeedCap
import com.music.bitchord.playback.smart.VocalDepthFilter
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.BUILDUP_RISE
import com.music.bitchord.playback.smart.deckPlayedRate
import com.music.bitchord.playback.smart.deckSpeed
import com.music.bitchord.playback.smart.echoTailSeconds
import com.music.bitchord.playback.smart.releasableRate
import com.music.bitchord.playback.smart.planTransition
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
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
internal fun safeDeckSpeed(raw: Float, beatmatched: Boolean = true): Float {
    if (!beatmatched || !raw.isFinite()) return 1.0f
    return raw.coerceIn(0.92f, 1.08f)
}

/**
 * [safeDeckSpeed] applied to a mix [rate] (1.0 = no stretch) before [deckSpeed] stacks it with
 * the listener speed. [rate] may arrive as `NaN` (unknown BPM ratio): that is `1.0f`, never
 * a stretch. Every `setPlaybackSpeed` that carries a beatmatch rate goes through here so the
 * `[0.92f, 1.08f]` ceiling applies to ordinary unqualified requests. A locally
 * admitted classic blend uses its separately checked physical rate in incomingBaseSpeed.
 */
internal fun safeRateForDeck(rate: Double, beatmatched: Boolean = true): Double =
    safeDeckSpeed(rate.toFloat(), beatmatched).toDouble()

/**
 * Final `ExoPlayer` speed for a mix [rate]: listener speed stacked on [safeRateForDeck].
 * Use this for every beatmatch `setPlaybackSpeed`. Non-beatmatched decks play at the
 * listener speed (`rate` is forced to `1.0`).
 */
internal fun deckSpeedSafe(user: Float, rate: Double, beatmatched: Boolean = true): Float =
    deckSpeed(user, safeRateForDeck(rate, beatmatched))

/**
 * Applies a locally admitted physical stretch before the existing composite
 * player-speed cap. Invalid rates cannot bypass the ordinary deck-speed guard.
 */
internal fun deckSpeedForAdmittedBlend(user: Float, admittedRate: Double): Float {
    val base = if (user.isFinite() && user in 0.25f..3f) user else 1f
    val rate = if (admittedRate.isFinite() &&
        admittedRate in com.music.bitchord.playback.smart.MIN_INCOMING_RATE..
            com.music.bitchord.playback.smart.MAX_INCOMING_RATE
    ) admittedRate.toFloat() else 1f
    return (base * rate).coerceIn(0.25f, 3f)
}

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

internal enum class IdleStallAction { WAIT_AND_FREEZE, PREPARE_ONCE, FORCE_BAIL }

internal fun idleStallDecision(stalledMs: Long, outExhausted: Boolean, alreadyPrepared: Boolean): IdleStallAction = when {
    outExhausted -> IdleStallAction.FORCE_BAIL
    stalledMs >= 1500L && !alreadyPrepared -> IdleStallAction.PREPARE_ONCE
    stalledMs >= 2000L && alreadyPrepared -> IdleStallAction.FORCE_BAIL
    else -> IdleStallAction.WAIT_AND_FREEZE
}

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
    "$phaseName|${outgoingId ?: ""}->${incomingId ?: ""}"

/**
 * Whether a session player paused by an audio-focus swap may be auto-resumed
 * (djmix22, vc64). True only when the player was audibly playing before the
 * handoff, the pause was not the listener's (a user pause — reason USER_REQUEST —
 * is sticky and never auto-resumed), and the adopting player is holding (or has
 * atomically acquired) focus. A user-paused player is NEVER auto-resumed, and a
 * player that was not playing before the swap is never started by it.
 */
internal fun shouldRestorePlayAfterFocusSwap(wasPlaying: Boolean, userPaused: Boolean, focusHeld: Boolean): Boolean =
    wasPlaying && !userPaused && focusHeld

/** What [CrossfadeController.finish] must do with an audible spare (djmix22). */
internal enum class LoopFinishAction { FINISH_LOOP, EMERGENCY_TAIL, FINISH }

/**
 * LoopMix completion routing (djmix22, vc64): a `LOOP_RUNNING`/`LOOP_ARMING`
 * mix that has taken over AND handed off must complete via [CrossfadeController]'s
 * `finishLoop()` (the renderer exit-gate path), NOT via a bare `finish()` that
 * treats the audible spare at volume 1.0 as an emergency-retire-guard. The gate
 * (renderer `exitGateViolations`) is trivially clean mid-mix (it only counts
 * decks that hit `stopAt`), so a took-over, handed-off session with a clean gate
 * completes through `finishLoop`. Pre-handoff, un-took-over, and non-clean-gate
 * cases keep the djmix12 emergency tail (the retiring plain sink can bleed),
 * and a non-audible spare finishes at once (the classic clean path).
 */
internal fun loopFinishAction(
    phaseName: String,
    tookOver: Boolean,
    handedOff: Boolean,
    exitGateViolations: Int,
    audibleSpare: Boolean,
): LoopFinishAction = when {
    !audibleSpare -> LoopFinishAction.FINISH
    (phaseName == "LOOP_RUNNING" || phaseName == "LOOP_ARMING") && tookOver && handedOff &&
        exitGateViolations == 0 -> LoopFinishAction.FINISH_LOOP
    else -> LoopFinishAction.EMERGENCY_TAIL
}

/**
 * Session-adoption guard (djmix23, vc65). The session may only move onto the
 * incoming player once that player has actually started. The loop clock hold
 * geometry can schedule inStartAt after handoffAt (vc64 06:23 freeze: the
 * Calvin pair handed the session to the paused position-0 incoming player and
 * left song 2 paused until a manual play). The native renderer keeps the mix
 * audible through the wait, so holding the adoption until the start is
 * inaudible; adopting early is the jam.
 */
internal fun canAdoptSessionPlayer(loopIncomingStarted: Boolean): Boolean = loopIncomingStarted

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
internal class PhraseCandidate(
    val plan: LoopMix.Plan,
    val script: PhraseScript,
    val detail: String,
    val logKey: String,
    val locked: Boolean,
    val slides: Int,
    val lockedAtSec: Double?,
    val out: TrackAnalysis,
    val inn: TrackAnalysis,
)

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
    // AUTOMIX-V2(orch): 4-stem provider per track (Stream B); cachedOnly = never compute. Null = vc80 2-stem paths only.
    private val stemProviderFor: ((trackId: String, cachedOnly: Boolean) -> com.music.bitchord.playback.smart.StemProvider?)? = null,
    // AUTOMIX-V2(orch): background stem prefetch of a planned transition region (Stream B).
    private val prefetchStems: ((item: MediaItem, durationMs: Long, from: Double, to: Double, rate: Int) -> Unit)? = null,
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

    /** The outgoing speed-up ramp: steps in all, one beat of outgoing media each, from [rampStartMs]. */
    private var rampSteps = 0
    private var rampStepMs = 0L
    private var rampStartMs = 0L
    private var rampStep = 0

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

    /** Decoding the mix popup's waveforms for the blend in flight. */
    private var mixViewJob: Job? = null

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
        val style: TransitionStyle = TransitionStyle.EQUAL_POWER,
        val bassSwap: Boolean = false,
        bassSwapFraction: Double = 0.7,
        val filterSweep: Double = 0.0,
        val filterVariant: FilterTransitionVariant = FilterTransitionVariant.SWEEP,
        val vocalOverlap: Double = 0.0,
        /** Advanced Automix: DJ fader curves, moves on the beat, phase lock, tempo ease-back. */
        val advanced: Boolean = false,
        /** The overlap's length in outgoing beats; 0 when the grid is not trusted. */
        val beats: Double = 0.0,
        lockPhase: Boolean = false,
        /** The plan's echo time; 0 for no echo out. */
        echoSeconds: Double = 0.0,
        val djEffect: com.music.bitchord.playback.smart.DjEffect = com.music.bitchord.playback.smart.DjEffect.NONE,
        val vocalHandoff: Boolean = false,
        val beatSecondsF: Float = 0f,
        val buildup: Boolean = false,
        /** Muffle (vc69 P0) lead beats; 0 for other styles. */
        val muffleLeadBeats: Int = 0,
        /** Return-leg beats: trailing span the filter leaves to full-band. 0 for styles that never park. */
        val exitBeats: Int = 0,
        /** Vocal-depth (vc69 P1) depth curve over the overlap; empty means full spend. */
        val vocalDepthCurve: List<Double> = emptyList(),
    ) {
        /** Running-maximum depth: a filter that reopens mid-transition sounds like a mistake. */
        val depthCurve: List<Double> = VocalDepthFilter.runningMax(vocalDepthCurve)
        private val grid = if (advanced && beats >= 2.0) beats else 0.0
        val clash = vocalOverlap.coerceIn(0.0, 1.0)

        /** Echo the outgoing track out rather than fading or filtering it away. Needs the grid it repeats on. */
        val echo = grid > 0 && echoSeconds > 0.0
        val echoSecondsF = echoSeconds.toFloat()

        /** How long the repeats ring after the dry signal goes, in ms. */
        val echoTailMs = (echoTailSeconds(echoSeconds) * 1000).toFloat()

        /** Hold the two beats in phase by nudging the incoming track's speed. */
        val phaseLock = advanced && lockPhase

        val swapAt = onBeat(bassSwapFraction.coerceIn(0.05, 0.95))

        /** The low end changes hands over half a beat that ends on [swapAt]; classically, centred on it. */
        val swapWidth = if (grid > 0) SWAP_BEATS / grid else 2 * BASS_SWAP_WIDTH
        val swapFrom = if (grid > 0) swapAt - swapWidth else swapAt - BASS_SWAP_WIDTH
        val entryOpenBy = onBar(
            when (filterVariant) {
                FilterTransitionVariant.SWEEP -> ENTRY_OPEN_BY
                FilterTransitionVariant.BASS_HANDOFF -> 0.38
                FilterTransitionVariant.ECHO_RIDE -> 0.52
                // The decline carve rides its own shared envelope envelope
                // (TransitionAlternatives.declineInHpAt); this field is
                // unused on that path. Value kept consistent for the
                // derived vocal EntryOpenBy contract.
                FilterTransitionVariant.DECLINE -> TransitionAlternatives.DECLINE_IN_OPEN_BY
            },
        )
        val vocalEntryOpenBy = onBar(entryOpenBy + (VOCAL_SAFE_ENTRY_OPEN_BY - entryOpenBy) * clash)
        val blendEntryOpenBy = onBar(BLEND_ENTRY_OPEN_BY + (BLEND_ENTRY_CLASH_OPEN_BY - BLEND_ENTRY_OPEN_BY) * clash)
        val blendExitFrom = onBar(BLEND_EXIT_FROM + (BLEND_EXIT_CLASH_FROM - BLEND_EXIT_FROM) * clash)
        val filterSwapAt = onBar(
            when (filterVariant) {
                FilterTransitionVariant.SWEEP -> FILTER_BASS_SWAP_AT
                FilterTransitionVariant.BASS_HANDOFF -> 0.43
                FilterTransitionVariant.ECHO_RIDE -> 0.58
                // The carve hands its low end over at the same bar as a
                // sweep: one kick, never two.
                FilterTransitionVariant.DECLINE -> FILTER_BASS_SWAP_AT
            },
        )
        val filterSwapAtF = filterSwapAt.toFloat()


        /**
         * Where the dry outgoing signal is killed and only its echo goes on:
         * the filter ride's bass handover. The send opens
         * across the beat before it, so what repeats is that one beat — the
         * DJ move of hitting echo on the last beat and pulling the fader.
         */
        val echoAt = onBar(FILTER_ECHO_AT)
        val echoFrom = if (grid > 0) echoAt - 1.0 / grid else echoAt
        // Short overlaps can have fewer than four beats after the echo cue.
        // Close the dry path by progress 1 even when the wet fader runs longer.
        val echoKill = if (grid > 0) minOf(ECHO_KILL_BEATS / grid, 1.0 - echoAt) else 0.0

        /**
         * How much of the DJ fader curve a blend keeps: all of it for two tracks
         * that don't sing over each other, none — plain equal power — for a
         * head-on vocal collision, where both faders up would only be louder.
         */
        val holdFaders = (1.0 - clash).toFloat()
        val swapAtF = swapAt.toFloat()

        /**
         * Where a held outgoing fader starts to fall: no later than the
         * midpoint, and at least a bar before the end, so no fall is ever
         * steeper than one bar. A fader held at full to the swap and then
         * dropped in the beats left was heard as the song cutting out.
         */
        val fallFrom = minOf(swapAtF, 0.5f, if (grid > 0) (1.0 - 4.0 / grid).toFloat() else 0.5f)
            .coerceAtLeast(0f)

        /**
         * Where the session moves to the incoming track. Advanced: when it
         * actually takes over — the bass swap in a blend.
         */
        val handoffAt = when {
            !advanced -> HANDOFF_AT
            style == TransitionStyle.DJ_BLEND && bassSwap -> swapAtF
            style == TransitionStyle.DJ_FILTER && (filterVariant == FilterTransitionVariant.BASS_HANDOFF || filterVariant == FilterTransitionVariant.DECLINE) -> filterSwapAtF
            else -> HANDOFF_AT
        }

        private fun onBeat(fraction: Double): Double =
            if (grid > 0) (round(fraction * grid) / grid).coerceIn(1.0 / grid, 1.0 - 1.0 / grid) else fraction

        private fun onBar(fraction: Double): Double =
            if (grid >= 8.0) {
                (round(fraction * grid / 4.0) * 4.0 / grid).coerceIn(4.0 / grid, 1.0 - 4.0 / grid)
            } else {
                onBeat(fraction)
            }
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
    private var armGraceUsed = false
    private var armDeadline = 0L
    private var armOutgoingPreFiltered = false

    /** Length of the fade-out driven by [Phase.SLEEP_FADE], in ms. Fixed when it begins. */
    private var sleepFadeMs = 0L

    /**
     * Where the outgoing track was when [beginSleepFadeOut] started, in its
     * own position ms. Progress is measured from here rather than off a
     * clock, for the same reason [driveFade] measures off the incoming
     * track's position: a pause should park the fade-out where it stands,
     * not keep counting down underneath a silent, stopped player.
     */
    private var sleepFadeStartPositionMs = 0L

    /**
     * The outgoing track's own volume when [beginSleepFadeOut] started, so a
     * fade-out that begins mid-ramp (rare, but a plan can be replanned right up
     * to the moment it arms) scales from where the volume actually is rather
     * than assuming it started at full.
     */
    private var sleepFadeStartGain = 1f

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

    // ---- Phase lock (Advanced Automix) --------------------------------------

    /** When the speed nudge in flight ends, from [SystemClock.elapsedRealtime]; 0 when none is. */
    private var nudgeUntil = 0L

    /** No phase is measured before this: the players are settling after a start or a nudge. */
    private var lockQuietUntil = 0L

    /** Phase errors, in incoming seconds, gathered until there are enough to take a median of. */
    private val lockErrors = DoubleArray(LOCK_SAMPLES)
    private var lockCount = 0

    /** [beatAt]'s answer — seconds past the last beat, and that beat's length — without allocating. */
    private val beatScratch = DoubleArray(2)

    /** Where each side of the blend really is between the player's own reports — see [PositionClock]. */
    private val outClock = PositionClock()
    private val inClock = PositionClock()

    /**
     * A player's position between the moments it actually reports one.
     *
     * Media3 1.11 schedules its playback loop dynamically: while the output
     * buffer is full it sleeps, and [ExoPlayer.getCurrentPosition] only moves
     * when it wakes — measured here at about every 280ms, a step at a time.
     * Everything a blend times off a position was reading those steps: the
     * faders and filters moved in lurches, the scrubber's beat anchor jumped by
     * up to a beat, and the phase lock compared two players whose last reports
     * were a quarter of a second apart in age, on a 320ms beat — noise it then
     * dutifully "corrected".
     *
     * So each new report is taken as having landed halfway through the tick it
     * was seen in, and moved on from there at the player's own speed until the
     * next one. One subtraction and one multiply per read, and no need to turn
     * the scheduling — and the battery it saves — off.
     */
    private class PositionClock {
        private var player: ExoPlayer? = null
        private var reported = 0L
        private var seenAt = 0L

        fun reset() {
            player = null
        }

        /** [p]'s position now, in ms. */
        fun read(p: ExoPlayer): Double {
            val now = System.nanoTime()
            val position = p.currentPosition
            if (p !== player || position != reported) {
                seenAt = if (p === player) now - HALF_TICK_NANOS else now
                player = p
                reported = position
            }
            if (!p.isPlaying) return position.toDouble()
            val since = (now - seenAt).coerceIn(0L, MAX_EXTRAPOLATION_NANOS)
            return position + since / 1e6 * p.playbackParameters.speed
        }
    }

    /** [player]'s position through whichever clock follows its side of the blend. */
    private fun positionMs(player: ExoPlayer): Double =
        if (player === incoming) inClock.read(player) else outClock.read(player)

    // ---- Tempo ease-back (Advanced Automix) --------------------------------

    /** The player still coming off a beatmatch stretch after its blend finished, or null. */
    private var easing: ExoPlayer? = null
    private var easeOnActive = false
    private var easeRate = 1.0
    private var easeStep = 0.0
    private var easeStepMs = 0L
    private var easeNextAtMs = 0L
    private var easeItemIndex = 0
    private var softSpeedJob: Job? = null

    /** Last speed written for a buildup, so the rise steps instead of clicking every tick. */
    private var buildupSpeed = 1f

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
                        // djmix14: full scrub reset (all plans) so the next tick
                        // replans from the new playhead. No 350 ms dip on an
                        // inaudible player — silent teardown, instant replan.
                        resetPlansForScrub()
                        AutomixTelemetry.event("mix.plan.rejected", mapOf("rung" to "scrub-cancelled", "reason" to "scrub", "phase" to phase.name))
                        if (phase == Phase.ARMING) cancelArming("scrub-cancelled") else bail(force = true)
                    }
                    SeekAction.Action.EMERGENCY_TAIL -> {
                        resetPlansForScrub()
                        val isLoopRunning = phase == Phase.LOOP_RUNNING || loopSession?.outPort?.tookOver == true
                        if (isLoopRunning) {
                            // Audible loop mix: dissolve over 350 ms via the
                            // renderer fade (both decks) then tear down, leaving
                            // playback at the scrub target in IDLE. The 350 ms
                            // tail guard inside finish() covers the player side;
                            // the renderer fade covers the deck side. Never a
                            // click, never an abrupt cut.
                            // Capture the active session player BEFORE loopBail:
                            // finish() nulls outgoing/incoming, so reading them
                            // after would be a no-op. wasHandedOff says which
                            // player owns the session (incoming after handoff);
                            // the discontinuity fired on that same active() player,
                            // so it is already at newPosition.positionMs.
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
                            // preserveSeek: loopBail already skips the old-clock
                            // seek. The active player never moved off the user
                            // seek; the audible spare dissolves via RetireGuard
                            // inside finish() -> beginTailOrFinish (no click).
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
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            onPlaylistChanged(timeline)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                // Something replaced the queue out from under the fade — a new
                // album, a new search result — so the tail still playing is a
                // leftover of a session that no longer exists. Note that this
                // does *not* fire when AutoPlay appends to the end, since the
                // playing item doesn't change: extending the queue mid-fade is
                // harmless and shouldn't cost the listener the blend.
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                    expireIdleOpportunity("natural-auto-advance")
                    ranOutBeforeHandoff()
                }
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> expireIdleOpportunity("natural-repeat")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            AutomixTelemetry.event("mix.error", mapOf("error" to error.message.orEmpty(), "phase" to phase.name))
            bail()
        }

        // The session reached the end of a track [holdAtEnd] is holding, before
        // the handoff took it off that track. Only ever a late handoff, or a
        // transition that was held for ahead of arming and never started;
        // either way the hold must not leave the listener paused at the end of
        // a song.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // djmix22: keep the last change reason for mix.pause.detected (do not
            // let the END_OF_MEDIA_ITEM rescue below skip the capture).
            lastPlayWhenReadyChangeReason = reason
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
        phrasePair = null
        phraseLock = PhrasePlanner.Lock()
        phraseDetail = null
        phraseLogKey = null
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
        // A NATURAL taken on an analysis-fallback is provisional until the arm
        // deadline. Newer evidence before that deadline invalidates it so the
        // plan is re-attempted instead of staying latched for the whole song.
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

    fun start() {
        listenTo(active())
        startAnalysisCollector()
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
        disarmTailLoopSession()
        endEase(clickless = false)
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
        if (phase != Phase.IDLE) bail()
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
        checkShownWatchdog()
        expireMixPreview()
        checkHandoffPauseWatch()

        if (easing != null && phase == Phase.IDLE) driveEase()

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
        try {
            publishTickState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best-effort observation must not interrupt core work or its cadence.
        }
    }

    /**
     * djmix22 (vc64) pause watch: if the session player is found paused within 3 s
     * after a mix.complete with handoff=true, record mix.pause.detected (once per
     * handoff) so the next occurrence is provable from telemetry alone — the
     * 70 s silent-song jam after a loop-mix focus swap left no other trace.
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
     * djmix9 watchdog: a shown countdown froze its rung. If the mix still has
     * not armed 1.2 s after its bar, bail to the graceful blend path - a
     * transition must never spin silently into silence.
     */
    private fun checkShownWatchdog() {
        val player = active()
        if (!player.isPlaying) return
        // (i) IDLE shown-but-unarmed: countdown visible, no arm 1.2 s after its bar.
        if (phase == Phase.IDLE && loopSession == null) {
            val key = shownCountdownKey ?: return
            if (watchdogFired || key != loopPlanKey || loopPlanned == null) return
            if (player.currentPosition <= shownLoopStartMs + 1200L) return
            watchdogFired = true
            val reason = "watchdog: shown countdown unarmed 1.2s after its bar"
            AutomixTelemetry.event("mix.watchdog", mapOf("key" to key, "pos" to player.currentPosition, "start" to shownLoopStartMs))
            Log.i("AutomixCrash", reason + " key=$key pos=${player.currentPosition} start=$shownLoopStartMs")
            loopSuppressedPair = key.substringBefore("|")
            loopSuppressedUntil = nowMs() + LOOP_SUPPRESSION_MS
            shownCountdownKey = null
            AutomixTelemetry.event(
                "mix.arm.death",
                mapOf("reason" to reason, "phase" to phase.name, "msSincePlan" to planAgeMs()),
            )
            loopFallBack(reason)
            return
        }
        // (ii) LOOP_ARMING still building past the splice: cancel the late
        // build, suppress the loop, and let catch-up classic fire this tick.
        if (phase == Phase.LOOP_ARMING) {
            val took = loopSession?.outPort?.tookOver == true
            if (took) return
            if (loopTargetStartMs <= 0L) return
            if (player.currentPosition <= loopTargetStartMs + 1200L) return
            val key = loopPlanKey ?: shownCountdownKey ?: "loop-arming"
            primeJob?.cancel()
            primeJob = null
            loopBuildJob?.cancel()
            loopBuildJob = null
            val reason = "watchdog: loop arming unarmed 1.2s after its bar"
            AutomixTelemetry.event("mix.watchdog", mapOf("key" to key, "pos" to player.currentPosition, "start" to loopTargetStartMs))
            Log.i("AutomixCrash", reason + " key=$key pos=${player.currentPosition} start=$loopTargetStartMs")
            loopSuppressedPair = key.substringBefore("|")
            loopSuppressedUntil = nowMs() + LOOP_SUPPRESSION_MS
            shownCountdownKey = null
            watchdogFired = true
            AutomixTelemetry.event(
                "mix.arm.death",
                mapOf("reason" to reason, "phase" to phase.name, "msSincePlan" to planAgeMs()),
            )
            loopFallBack(reason)
        }
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
        if (nextIndex == C.INDEX_UNSET) return
        val nextItem = player.getMediaItemAt(nextIndex)
        ensureOpportunity(currentItem.mediaId, nextItem.mediaId)
        // Capture the token AFTER the opportunity is ensured (a new pair bumps
        // it). A newer analysis signal during this pass makes its verdict stale,
        // so it is discarded before it can latch a fallback NATURAL.
        val generationAtEntry = planGeneration
        // Even a manual catalogue match is still video-origin. AutoMix's
        // analysis and cueing are deliberately never applied to either side
        // of a transition involving a video row.
        if (currentItem.isVideoOrigin || nextItem.isVideoOrigin) {
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixInProgress.value = false
            return
        }
        val nextDuration = nextItemDurationMs(nextIndex, nextItem)
        val pairKey = "${currentItem.mediaId}->${nextItem.mediaId}"

        // Even for the marker alone: a stored analysis is only loaded off disk
        // by asking for it, and without that a paused track plans blind.
        requestAnalysisAround(player, duration)

        // djmix26: brief post-scrub settle window so rapid/midway seeks pick
        // one stable plan instead of flickering before the playhead settles.
        val nowWall0 = nowMs()
        if (phase == Phase.IDLE && nowWall0 < scrubSettledAtMs) {
            if (duration - player.currentPosition <= 5_000L) {
                scrubSettledAtMs = 0L
            } else {
                askLyrics(currentItem, duration)
                askLyrics(nextItem, nextDuration)
                AppSettings.smartTransitionWindow.value = null
                return
            }
        }

        // Only used before analysis lands, or when the evidence is too weak
        // for more than a plain fade (see [TransitionTier.PLAIN_CROSSFADE]):
        // once real analysis is available, [planTransition] sizes the overlap
        // itself from tempo and structure and ignores this entirely. Honours
        // the manual slider if the listener also set one, so the two settings
        // don't fight; falls back to a fixed length when it's at "Off".
        val fallbackSeconds = configuredFadeMs().takeIf { it > 0L }
            ?.div(1000.0)
            ?: DEFAULT_SMART_FALLBACK_SECONDS

        // Resolved once and reused: [analysisFor] was being called five separate
        // times per tick below, and the answer cannot change mid-tick.
        val currentAnalysis = analysisFor(currentItem)
        val nextAnalysis = analysisFor(nextItem)
        val analysisState = AppSettings.smartAnalysis.value
        // The DJ planner is Automix now; there is no separate mode switch.
        val advanced = true

        askLyrics(currentItem, duration)
        askLyrics(nextItem, nextDuration)
        val outLyricCues = lyricSpans[currentItem.mediaId]?.cues.orEmpty().toList()
        val inLyricCues = lyricSpans[nextItem.mediaId]?.cues.orEmpty().toList()
        fun recoveryRequired(): Boolean = countdownStabilizer.hasFailedCommittedNative(pairKey, scrubEpoch)
        fun classicPlanFor(recovery: Boolean): TransitionPlan = planTransition(
            analysis = currentAnalysis,
            nextAnalysis = nextAnalysis,
            currentTrack = currentItem.toTransitionInfo(duration),
            nextTrack = nextItem.toTransitionInfo(nextDuration),
            currentTime = player.currentPosition / 1000.0,
            duration = duration / 1000.0,
            fadeSeconds = fallbackSeconds,
            mode = CrossfadeMode.SMART,
            advanced = advanced,
            // Classic phrase length stays adaptive (0): the native PhrasePlanner
            // owns bridge phrase length and there is no listener setting here.
            phraseBars = 0,
            lyricsOut = outLyricCues,
            lyricsIn = inLyricCues,
            mixWindow = true,
            listenerSpeed = AppSettings.playbackSpeed.value.toDouble(),
            allowRecoveryCrossfade = recovery,
        )
        // Set on every attempt, accepted or not: telemetry age must describe
        // when the planner last ran, not when a route was finally accepted.
        planGeneratedAtMs = nowMs()
        var plan = classicPlanFor(recoveryRequired())
        // djmix16 pin: ANY :catch-up plan for (pair, scrubEpoch) is pinned
        // ONCE and reused on every subsequent tick — never recomputed as
        // playbackTime + lead (moving carrot: transitionStart 187.5 ->
        // 188.9 -> 190.7, fadeSec 4.59 -> 3.99 -> 2.26 -> 1.2 on Never Let
        // Go -> S.A.D, remaining stuck at +900 ms). The old
        // `pos <= pinned.start+400` gate expired the pin the moment the
        // loop held classic begin past the start, then re-pinned ahead,
        // forever chasing. The pin now holds until pair/epoch changes or
        // the mix arms; a past pinned start gives remaining <= 0, so
        // classic begin() fires next tick instead of chasing.
        run {
            val pKey = "${currentItem.mediaId}->${nextItem.mediaId}"
            if (plan.reason.endsWith(":catch-up") && !plan.blocked) {
                val key = com.music.bitchord.playback.smart.CatchUpPins.key(pKey, scrubEpoch)
                val reuse = com.music.bitchord.playback.smart.CatchUpPins.reuse(pinnedCatchUpPlan, pinnedCatchUpKey, key, player.currentPosition)
                if (reuse != null) {
                    plan = reuse
                } else {
                    pinnedCatchUpKey = key
                    pinnedCatchUpPlan = plan
                }
            } else if (pinnedCatchUpKey != null && !plan.reason.endsWith(":catch-up") && !plan.blocked) {
                // djmix17: ANY valid non-catch-up plan drops the pin —
                // new pair, same pair after an interior -> final anchor
                // flip (Never Let Go seq368 181.6 s) or a backward seek.
                // A blocked plan never arms anyway, so it keeps the pin
                // until a valid plan arrives.
                if (com.music.bitchord.playback.smart.CatchUpPins.dropOnNonCatchUp(pinnedCatchUpKey, pKey)) {
                    pinnedCatchUpKey = null
                    pinnedCatchUpPlan = null
                }
            }
        }
        // A newer analysis generation arrived while this pass was in flight:
        // discard its verdict rather than latching a stale fallback NATURAL.
        // planResultGeneration also guards a result tagged by a later replan.
        if (generationAtEntry != planGeneration || planResultGeneration > generationAtEntry) return
        // One line per distinct verdict rather than one per 250ms tick, so the
        // log says what the planner decided for this pair without burying it.
        val verdict = "${plan.reason}|${plan.transitionStyle}|fade=${plan.fadeMs}" +
            "|cue=${plan.incomingCueTime}|rate=${plan.incomingPlaybackRate}" +
            "|vocalOverlap=${"%.2f".format(Locale.ROOT, plan.vocalOverlap)}" +
            "|advanced=$advanced|lock=${plan.phaseLock}|variant=${plan.filterVariant}|echo=${plan.echoSeconds}" +
            "|blocked=${plan.blocked}|policy=${plan.policyReasons.joinToString(",")}"
        if (verdict != lastPlanVerdict) {
            lastPlanVerdict = verdict
            Log.d(
                TAG,
                "plan ${currentItem.mediaId}->${nextItem.mediaId}: $verdict " +
                    "bpm=${currentAnalysis.bpm}/${nextAnalysis.bpm} " +
                    "conf=${currentAnalysis.beatConfidence}/${nextAnalysis.beatConfidence}",
            )
        }

        val classicTailSec = if (plan.fadeSeconds > 0.0) PhraseConfig.EXIT_TAIL_MIN_MS / 1000.0 else PhraseConfig.EXIT_TAIL_EMERGENCY_MS / 1000.0
        val rejKey = plan.reason + "|" + plan.transitionStyle.name
        if (rejKey != lastRejectedLogKey) {
            lastRejectedLogKey = rejKey
        if (plan.reason.contains("fallback") || plan.reason.contains("plain") || plan.reason.contains("unmixable") || plan.reason.contains("decline")) {
            mixEvent("mix.plan.rejected", mapOf("stage" to "candidate", "technique" to "classic", "rung" to "classic-fallback", "reason" to plan.reason,
                "qualityA" to currentAnalysis.quality, "qualityB" to nextAnalysis.quality,
                "statusA" to currentAnalysis.status, "statusB" to nextAnalysis.status,
                "bpmA" to currentAnalysis.bpm, "bpmB" to nextAnalysis.bpm,
                "confA" to currentAnalysis.beatConfidence, "confB" to nextAnalysis.beatConfidence))
        }
        } else if (!plan.blocked && verdict != lastEmittedPlanVerdict) {
            lastEmittedPlanVerdict = verdict
            mixEvent(
                "mix.plan",
                mapOf(
                    "stage" to "candidate", "technique" to "classic",
                    "template" to "classic", "locked" to false, "slides" to 0,
                    "bpmA" to currentAnalysis.bpm, "bpmB" to nextAnalysis.bpm,
                    "statusA" to currentAnalysis.status, "statusB" to nextAnalysis.status,
                    "qualityA" to currentAnalysis.quality, "qualityB" to nextAnalysis.quality,
                    "exitTailSec" to classicTailSec, "fadeSec" to plan.fadeSeconds,
                    "style" to plan.transitionStyle.name,
                    // djmix25 P2.13 telemetry completeness (was 0 hits on every
                    // mix.plan line in vc66 telemetry).
                    "reason" to plan.reason,
                    "rung" to plan.transitionStyle.name,
                    "transitionStyle" to plan.transitionStyle.name,
                    "decline" to plan.reason.contains("decline-to-mix"),
                    "declineToMix" to plan.reason.contains("decline-to-mix"),
                    "coarseDecline" to shouldDeclineToMix(currentAnalysis, nextAnalysis),
                    "coarseOverride" to (shouldDeclineToMix(currentAnalysis, nextAnalysis) &&
                        LocalBlendAdmission.MARKER in plan.policyReasons),
                    "camelotLeft" to (camelotCode(com.music.bitchord.playback.smart.exitKeyForMix(currentAnalysis)) ?: ""),
                    "camelotRight" to (camelotCode(com.music.bitchord.playback.smart.entryKeyForMix(nextAnalysis)) ?: ""),
                    "fxSlot" to com.music.bitchord.playback.smart.TransitionMoves.fxSlot(currentItem.mediaId, nextItem.mediaId),
                    "vocalOverlapBars" to plan.vocalOverlap,
                    "styleFamily" to "CLASSIC",
                    "exitKind" to "",
                    "entryKind" to "",
                    "sameAlbum" to false,
                    "gapless" to (plan.transitionStyle.name == "GAPLESS"),
                    "filterVariant" to plan.filterVariant.name,
                    "djEffect" to plan.djEffect.name,
                ),
            )
            // vc69 P2 evidence dump: one structured record per armed
            // transition (Orchard ORCHARD_MIX_DUMP shape: what was chosen,
            // why, and the gate scores), pulled without root from the same
            // telemetry stream.
            val dumpedEvidence = TransitionEvidenceCache.recent(1).firstOrNull()
            if (dumpedEvidence != null) {
                AutomixTelemetry.event(
                    "mix.dump",
                    mapOf(
                        "outId" to dumpedEvidence.outId,
                        "inId" to dumpedEvidence.inId,
                        "style" to dumpedEvidence.style,
                        "reason" to dumpedEvidence.reason,
                        "confidence" to dumpedEvidence.confidence,
                        "flowScore" to dumpedEvidence.flowScore,
                        "gates" to dumpedEvidence.gates.toString(),
                    ),
                )
            }
        }
        // The loop mix first: a pair it can do never reaches the blend below.
        // djmix13: a loop that just died for this shown pair stays suppressed
        // briefly, so the classic plan gets time to actually complete.
        val suppressingLoop = (loopSuppressedPair == pairKey && nowMs() < loopSuppressedUntil) ||
            countdownStabilizer.isFamilySuppressed(pairKey, scrubEpoch, CountdownStabilizer.Family.LOOP)
        val suppressingBridge = suppressingLoop ||
            countdownStabilizer.isFamilySuppressed(pairKey, scrubEpoch, CountdownStabilizer.Family.NATIVE_BRIDGE)
        if (!suppressingLoop && pairKey != loopSuppressedPair) {
            loopSuppressedPair = null
        }
        val committedBeforeLoop = countdownStabilizer.committedFor(pairKey, scrubEpoch)
        val lockedClassic = plan.transitionStyle != TransitionStyle.NATURAL && committedBeforeLoop != null &&
            (committedBeforeLoop.locked || committedBeforeLoop.shown) &&
            committedBeforeLoop.family == CountdownStabilizer.Family.CLASSIC &&
            committedBeforeLoop.targetStartMs >= player.currentPosition - CountdownStabilizer.PAST_GRACE_MS

        val prevLoopPlanned = loopPlanned
        val prevLoopPlanKey = loopPlanKey
        val prevLoopScript = loopScript
        var loop: LoopMix.Plan? = null
        var planLoop: LoopMix.Plan? = null
        val lockedBridge = committedBeforeLoop != null &&
            (committedBeforeLoop.locked || committedBeforeLoop.shown) &&
            committedBeforeLoop.family == CountdownStabilizer.Family.NATIVE_BRIDGE &&
            committedBeforeLoop.targetStartMs >= player.currentPosition - CountdownStabilizer.PAST_GRACE_MS
        if (!suppressingLoop && !lockedClassic) {
            planLoop = if (lockedBridge) {
                loopRefusal = loopRefusalFor(player, currentItem, nextItem, analysisState, currentAnalysis, nextAnalysis)
                null
            } else loopPlanFor(
                player, currentItem, nextItem, currentAnalysis, nextAnalysis, duration, nextDuration, analysisState,
            )
            val staleCommittedLoop = planLoop == null &&
                loopRefusal == "stale loop start passed" &&
                committedBeforeLoop != null
            val committedNative = committedBeforeLoop != null &&
                (committedBeforeLoop.locked || committedBeforeLoop.shown) &&
                committedBeforeLoop.family != CountdownStabilizer.Family.CLASSIC &&
                committedBeforeLoop.targetStartMs >= player.currentPosition - CountdownStabilizer.PAST_GRACE_MS
            loop = nativePlanAfterCrown(planLoop, plan, currentAnalysis, nextAnalysis, committedNative,
                outLyricCues, inLyricCues) {
                if (!suppressingBridge && !staleCommittedLoop) nativeBridge(
                    player, currentItem, nextItem, currentAnalysis, nextAnalysis, duration, nextDuration,
                ) else null
            }
            if (loop == null && !committedNative && LocalBlendAdmission.isQualified(plan, currentAnalysis, nextAnalysis,
                    outLyricCues, inLyricCues)) {
                // The shared cache can still contain an uncommitted Echo. Do not publish it over classic.
                loopPlanned = null
                loopPlanKey = null
                loopScript = null
            }
        }
        // djmix26: anti-oscillation & HEAD -> FULL stability for Loop/Bridge.
        if (committedBeforeLoop != null &&
            (committedBeforeLoop.locked || committedBeforeLoop.shown) &&
            committedBeforeLoop.family != CountdownStabilizer.Family.CLASSIC &&
            committedBeforeLoop.targetStartMs >= player.currentPosition - CountdownStabilizer.PAST_GRACE_MS
        ) {
            val previousRefusal = prevLoopPlanned?.let {
                retainedPlanRefusal(it, currentAnalysis, nextAnalysis,
                    lyricSpans[currentItem.mediaId]?.spans, lyricSpans[nextItem.mediaId]?.spans)
                    ?: retainedBoundsRefusal(it, player.duration, nextDuration)
            }
            val hardRefusal = loopRefusal in HARD_LOOP_REFUSALS || previousRefusal != null
            if (previousRefusal != null) {
                countdownStabilizer.onRungFailed(pairKey, scrubEpoch, committedBeforeLoop.family)
            }
            val prevMatchesCommitted = prevLoopPlanned != null &&
                prevLoopPlanKey?.startsWith("$pairKey|") == true &&
                nativeFamily(prevLoopPlanned) == committedBeforeLoop.family &&
                previousRefusal == null &&
                (prevLoopPlanned.out.loopStart * 1000).roundToLong() == committedBeforeLoop.targetStartMs
            if (loop == null && !hardRefusal && !suppressingBridge && prevMatchesCommitted &&
                committedBeforeLoop.targetStartMs > player.currentPosition
            ) {
                loopPlanned = prevLoopPlanned
                loopPlanKey = prevLoopPlanKey
                loopScript = prevLoopScript
                loop = prevLoopPlanned
                loopRefusal = null
            } else if (loop == null && hardRefusal) {
                if (previousRefusal != null) noteLoopVerdict(pairKey, null, previousRefusal)
                countdownStabilizer.onRungFailed(
                    pairKey, scrubEpoch, committedBeforeLoop.family,
                    suppressBridgeToo = (loopRefusal != "sung veto"),
                )
            } else if (loop != null) {
                val candStartMs = (loop.out.loopStart * 1000).roundToLong()
                if ((nativeFamily(loop) != committedBeforeLoop.family ||
                    kotlin.math.abs(candStartMs - committedBeforeLoop.targetStartMs) > CountdownStabilizer.MAX_DRIFT_MS) &&
                    prevMatchesCommitted
                ) {
                    loopPlanned = prevLoopPlanned
                    loopPlanKey = prevLoopPlanKey
                    loopScript = prevLoopScript
                    loop = prevLoopPlanned
                }
            }
        }
        // djmix16 cold-loop yield: a cold loop (decks not in RAM) needs
        // a full classic arm lead, not 4.5 s. Never Let Go -> S.A.D
        // (104.5 -> 66.5 BPM) primed for 47 s and never built:
        // TrackAnalyzer.loopRegion needs a COMPLETE rendition
        // (chooseRendition filters isComplete), but the DB-cached
        // analysis meant S.A.D was never downloaded, and nothing else
        // fetched it inside the 40 s prime window — every prime
        // returned null in ~2 ms and retried every 2 s. The old
        // <4.5 s gate kept `loop != null` shadowing classic until
        // 189.3 s (4.5 s before the loop), leaving <4 s for a cold
        // HTTP ExoPlayer standby to buffer a 9.1 s DJ_FILTER blend —
        // classic never armed (0 mix.fire/arm/exit). Yield in time:
        // max(8.5 s, blendArmLead, 25 s while the next stream warms)
        // when cold and not actively building, and suppress the pair
        // so it does not reappear next tick.
        if (loop != null) {
            val loopStartMsTmp = (loop.out.loopStart * 1000).roundToLong()
            val sampleRateTmp = loopPortOf(player)?.sampleRate ?: 48000
            var fastReadyTmp = isLoopFastReady(loop, currentItem, nextItem, sampleRateTmp)
            var buildingTmp = primeJob?.isActive == true
            // vc67 fix: cold-loop self-murder order. yieldForHeadroom and
            // shouldYieldColdLoop run before primeLoopDecks below, so on tick 1
            // within T-30s (!fastReadyTmp && !buildingTmp), loop is killed and
            // loopSuppressedPair blacklisted for 90s before primeLoopDecks ever
            // starts primeJob. When the classic-fallback floor allows building
            // (remaining > hardColdAbortMs, within prime lead), prime FIRST so
            // primeJob?.isActive == true (buildingTmp = true) before the yield
            // checks run. LoopMix.shouldYieldColdLoop pure contract unchanged.
            val remainingMsEarly = loopStartMsTmp - player.currentPosition
            val nextReadyTmp = nextStreamReady(nextItem)
            val primeLeadMsTmp = adaptivePrimeLeadMs(nextReadyTmp, blendArmLeadMs)
            val isBridge = !isCrown(loop)
            val hardColdAbortMs = if (isBridge) 8_000L else if (!nextReadyTmp) 14_000L else 10_000L
            if (!fastReadyTmp && !buildingTmp) {
                if (remainingMsEarly >= hardColdAbortMs && remainingMsEarly <= primeLeadMsTmp) {
                    rememberLoopPair(currentItem, nextItem, currentAnalysis, nextAnalysis, duration, nextDuration)
                    primeLoopDecks(player, loop, currentItem, nextItem, duration, nextDuration)
                    fastReadyTmp = isLoopFastReady(loop, currentItem, nextItem, sampleRateTmp)
                    buildingTmp = primeJob?.isActive == true
                }
            }
            val retryPendingTmp = !fastReadyTmp && !buildingTmp &&
                remainingMsEarly >= hardColdAbortMs &&
                remainingMsEarly <= primeLeadMsTmp &&
                nowMs() < primeRetryAt
            // djmix18 (Bug 4): a LoopMix.plan that returned null once both tracks are analysed
            // is deterministically ineligible — do not keep waiting on a cold loop until
            // 10s-14s before contentEnd. Task 2B: never yield a valid nativeBridge while >= 8s
            // remains to decode a native transition window.
            val playedOutSec = (player.duration.takeIf { it != C.TIME_UNSET && it > 1_000L } ?: 0L) / 1000.0
            val contentEndMs = (currentAnalysis.contentEndTime.takeIf { it.isFinite() && it > 1.0 }
                ?: playedOutSec.takeIf { it > 1.0 }
                ?: (duration / 1000.0)).let { (it * 1000).roundToLong() }
            val earliestExitMs = runCatching {
                val outMapForYield = PhraseMaps.get(currentItem.mediaId)
                    ?: SongMaps.fromAnalysis(currentItem.mediaId, currentAnalysis)
                if (outMapForYield == null) null
                else com.music.bitchord.playback.smart.CuePoints.exits(
                    outMapForYield, playedOutSec, currentAnalysis,
                ).firstOrNull()?.let { (it.releaseSec * 1000).roundToLong() }
            }.getOrNull()
            val bothAnalysed = currentAnalysis.quality != TrackAnalysis.QUALITY_HEAD &&
                nextAnalysis.quality != TrackAnalysis.QUALITY_HEAD
            val yieldForHeadroom = isBridge && bothAnalysed &&
                remainingMsEarly < 8_000L &&
                shouldYieldColdLoopForContentEnd(
                    contentEndMs, player.currentPosition, currentAnalysis.bpm, earliestExitMs,
                )
            if (yieldForHeadroom) {
                if (!fastReadyTmp && !buildingTmp) {
                    val fbKey = "cold-loop-headroom-using-classic|${loop.out.loopStart}"
                    if (fbKey != lastFallbackLogKey) {
                        lastFallbackLogKey = fbKey
                        routeRefusal("mix.loop.fallback", "cold-loop-headroom-using-classic", "accepted", "native-bridge", "classic",
                            coarseDecline = shouldDeclineToMix(currentAnalysis, nextAnalysis))
                    }
                    countdownStabilizer.onRungFailed(
                        pairKey, scrubEpoch,
                        CountdownStabilizer.Family.NATIVE_BRIDGE,
                        suppressBridgeToo = true,
                    )
                    loop = null
                    loopPlanned = null
                    loopScript = null
                    loopPlanKey = null
                    loopSuppressedPair = pairKey
                    loopSuppressedUntil = nowMs() + LOOP_SUPPRESSION_MS
                    discardPrime()
                }
            }
            if (loop != null && com.music.bitchord.playback.smart.LoopMix.shouldYieldColdLoop(
                    loopStartMsTmp, player.currentPosition, fastReadyTmp, buildingTmp || retryPendingTmp,
                    blendArmLeadMs, nextReadyTmp, isNativeBridge = isBridge,
                )
            ) {
                val fbKey = "insufficient-cold-lead-using-classic|${loop.out.loopStart}"
                if (fbKey != lastFallbackLogKey) {
                    lastFallbackLogKey = fbKey
                    // Destination is recorded after the bridge rescue resolves below.
                }
                val yieldedWasCrown = isCrown(loop)
                loop = if (yieldedWasCrown && !LocalBlendAdmission.isQualified(plan, currentAnalysis, nextAnalysis,
                        outLyricCues, inLyricCues)) {
                    nativeBridge(player, currentItem, nextItem, currentAnalysis, nextAnalysis, duration, nextDuration)
                } else null
                if (loop != null) {
                    val bridgeStartMs = (loop.out.loopStart * 1000).roundToLong()
                    val bridgeRate = loopPortOf(player)?.sampleRate ?: 48000
                    val bridgeRemMs = bridgeStartMs - player.currentPosition
                    if (!isLoopFastReady(loop, currentItem, nextItem, bridgeRate) && bridgeRemMs < 8_000L) {
                        // Bridge cold and < 8s left to decode: classic must arm this tick.
                        loop = null
                    } else if (bridgeRemMs <= primeLeadMsTmp) {
                        rememberLoopPair(currentItem, nextItem, currentAnalysis, nextAnalysis, duration, nextDuration)
                        primeLoopDecks(player, loop, currentItem, nextItem, duration, nextDuration)
                    }
                }
                if (loop == null) {
                    routeRefusal("mix.loop.fallback", "insufficient-cold-lead-using-classic", "accepted",
                        if (yieldedWasCrown) "crown-loop" else "native-bridge", "classic",
                        coarseDecline = shouldDeclineToMix(currentAnalysis, nextAnalysis))
                    countdownStabilizer.onRungFailed(
                        pairKey, scrubEpoch,
                        if (yieldedWasCrown) CountdownStabilizer.Family.LOOP else CountdownStabilizer.Family.NATIVE_BRIDGE,
                        suppressBridgeToo = true,
                    )
                    loopPlanned = null
                    loopScript = null
                    loopPlanKey = null
                    loopSuppressedPair = pairKey
                    loopSuppressedUntil = nowMs() + LOOP_SUPPRESSION_MS
                    discardPrime()
                } else {
                    routeRefusal("mix.loop.fallback", "insufficient-cold-lead-using-classic", "accepted", "crown-loop", "native-bridge",
                        coarseDecline = shouldDeclineToMix(currentAnalysis, nextAnalysis))
                    countdownStabilizer.onRungFailed(
                        pairKey, scrubEpoch, CountdownStabilizer.Family.LOOP, suppressBridgeToo = false,
                    )
                }
            }
        }
        if (loop != null) {
            val loopStartMs = (loop.out.loopStart * 1000).roundToLong()
            val loopFamily = nativeFamily(loop)
            countdownStabilizer.consider(
                CountdownStabilizer.Candidate(
                    pairKey = pairKey,
                    scrubEpoch = scrubEpoch,
                    family = loopFamily,
                    label = loop.rung,
                    targetStartMs = loopStartMs,
                    targetEndMs = duration,
                    isFullQuality = currentAnalysis.quality != TrackAnalysis.QUALITY_HEAD,
                ),
                player.currentPosition,
            )
            lastSmartPlan = plan
            acceptRoute(loop, nativeRouteFields(loop, coarseDecline = shouldDeclineToMix(currentAnalysis, nextAnalysis)))
            lastPlanStartedAtMs = nowMs()
            lastSmartPlanStartMs = (plan.transitionStart * 1000).roundToLong()
            publishMixPreview(currentItem, nextItem, duration, nextDuration, currentAnalysis, nextAnalysis, analysisState, plan, advanced)
            AppSettings.smartTransitionWindow.value = TransitionWindow(
                start = (loopStartMs.toFloat() / duration).coerceIn(0f, 1f),
                end = 1f,
            )
            // The plan can be known for the whole song (it always picks the
            // latest clean loop in the last minute). Decoding both decks
            // starts now, not twelve seconds out — that wait is what made a
            // slow incoming stream miss the splice and throw the loop away.
            rememberLoopPair(currentItem, nextItem, currentAnalysis, nextAnalysis, duration, nextDuration)
            // djmix14: prime as soon as the pair is within 40 s (was 12-20 s
            // effective via LOOP_ARM_LEAD). Farther out, wait to save decode.
            // djmix15: NEVER gate warming on nextStreamReady. The DirectSource
            // gate is true only after a prefetch/decode, but a DB-cached
            // TrackAnalysis (quality=full, status=ready) means TrackAnalyzer
            // never downloaded the next file — a chicken-and-egg deadlock
            // (Never Let Go -> S.A.D: 6 mix.plan, 0 mix.fire/arm/exit).
            // primeLoopDecks itself streams via copyToRead, so priming IS
            // the warm. Classic begin() likewise prepares the standby over
            // HTTP. Gating either on the gate stalls the very I/O that could
            // satisfy it.
            if (loopStartMs - player.currentPosition <= adaptivePrimeLeadMs(nextStreamReady(nextItem), blendArmLeadMs)) {
                primeLoopDecks(player, loop, currentItem, nextItem, duration, nextDuration)
            }
            // djmix15: no early return on !nextStreamReady. In particular if
            // loopStartMs - pos < 4500L (or the cold-lead gate above just
            // nulled loop) and loop cannot arm because the stream isn't
            // cached, fall through to classic begin() below so the standby
            // arms and blends instead of playing dry to EOF.
            if (markerOnly) return
            plannedTransition = currentItem.mediaId to loopStartMs
            holdAhead(player, duration)
            if (loopStartMs - player.currentPosition > blendArmLeadMs) return
            // djmix17: beginLoop reports failure instead of being a
            // dead end — on false, fall straight through to classic
            // begin() below on this same tick (Never Let Go: beginLoop
            // silently returned on inPort==null and the unconditional
            // return below starved classic to dry EOF).
            if (beginLoop(player, loop, currentItem, nextItem, duration, nextDuration)) return
            // djmix17-harden: beginLoop refused this tick (guard emitted
            // mix.loop.fallback + suppressed the pair). The loop's atom is
            // now classic's: drop the loop cache so a stale loopPlanned /
            // loopScript / key cannot shadow the classic plan next tick,
            // and clear the placeholder plannedTransition that pointed at
            // loopStartMs — classic overwrites it with its own start below
            // (or leaves null if blocked, never a stale loop target).
            loop = null
            loopPlanned = null
            loopScript = null
            loopPlanKey = null
            plannedTransition = null
        }
        if (phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING) return
        discardPrime()
        // A native arm can fail during this very tick. Admit its bounded
        // recovery immediately instead of waiting behind a stale natural plan.
        if (plan.transitionStyle == TransitionStyle.NATURAL && recoveryRequired()) {
            planGeneratedAtMs = nowMs()
            plan = classicPlanFor(true)
        }

        if (plan.transitionStyle == TransitionStyle.NATURAL) {
            // The planner answers NATURAL for an "uncertain classic" — no usable
            // analysis yet — with a "natural-end:...analysis-fallback" reason (or
            // a decline). That verdict is provisional until arming is due: record
            // its generation so a newer analysis pass can replace it, rather than
            // latching a handoff for the rest of the song.
            val fromAnalysisFallback = plan.reason.startsWith("natural-end:") &&
                (plan.reason.contains("analysis-fallback") || plan.reason.contains("decline-to-mix") ||
                    plan.policyReasons.contains("natural-handoff-no-overlap"))
            if (fromAnalysisFallback) {
                naturalProvisionalUntilPosMs = (plan.transitionStart * 1000).roundToLong() -
                    maxOf(ARM_LEAD_MS, FALLBACK_NATURAL_HORIZON_MS)
                fallbackNaturalGeneration = if (player.currentPosition < naturalProvisionalUntilPosMs) planGeneration else -1L
            } else {
                naturalProvisionalUntilPosMs = -1L
                fallbackNaturalGeneration = -1L
            }
            // Leave both songs intact. No standby fade, countdown commitment,
            // seek, rate change, or pause-at-EOF: the existing queue owns advance.
            releaseHold()
            holdWanted = false
            plannedTransition = null
            pinnedCatchUpPlan = null
            pinnedCatchUpKey = null
            lastSmartPlan = plan
            lastSmartPlanStartMs = duration
            acceptRoute(plan, classicRouteFields(plan) + mapOf(
                "technique" to "natural", "rung" to "Natural handoff",
                "executionScope" to "normal-queue-advance-no-overlap",
            ))
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixInProgress.value = false
            AppSettings.smartMixPreview.value = null
            AppSettings.smartMixBlend.value = null
            clearMixView()
            return
        }

        // Gated on *both* tracks being measured, not on the plan alone. Until
        // then the planner is still sizing the overlap from a fallback that
        // moves as evidence lands, and a marker that slides along the bar while
        // you watch it is worse than none. Cleared during the transition itself
        // by [driveLap], because from that moment these fractions describe a
        // track the session player has already left.
        //
        // Asymmetric on purpose, because the two sides are read for different
        // things and a head-only result covers one of them completely.
        //
        // Where the window *sits* comes almost entirely from the outgoing track:
        // its content end, its outro, its mix-out anchors. A provisional result
        // has none of those — [analyzeHead] drops them deliberately rather than
        // answering confidently about a track it has only seen the opening of —
        // so the plan falls back to a plain end-of-track window, and the marker
        // would sit there and then jump backwards when the whole-track pass
        // lands. That is the sliding marker this guard exists for, so the
        // outgoing side still has to be finished.
        //
        // The incoming side is the opposite case. All the planner asks of it is
        // tempo, confidence and where it is safe to cue in — which are exactly
        // the fields a head pass measures, and it measures them over the same
        // opening window the whole-track pass would. Refining will sharpen those
        // numbers but not move them, so holding the marker back for it hid a
        // window that was already correct. Since the incoming track is now
        // routinely analysed from its opening long before it plays, that was
        // most of the time the marker was missing.
        val alreadyCommittedClassic = countdownStabilizer.committedFor(pairKey, scrubEpoch)?.family ==
            CountdownStabilizer.Family.CLASSIC
        val fallbackAdopted = suppressingLoop && !plan.blocked && plan.markerVisible
        val waitingOnLoopEvidence = !suppressingBridge &&
            (loopRefusal == "waiting on analysis" || loopRefusal == "waiting on lyrics") &&
            ((plan.transitionStart * 1000).roundToLong() - player.currentPosition) > 12_000L
        val allowClassicCommit = !waitingOnLoopEvidence &&
            (alreadyCommittedClassic || fallbackAdopted || !plan.reason.contains("fallback") ||
                ((plan.transitionStart * 1000).roundToLong() - player.currentPosition) <= 12_000L)
        plan = countdownStabilizer.stabilizeClassicPlan(
            pairKey = pairKey,
            scrubEpoch = scrubEpoch,
            fresh = plan,
            previous = lastSmartPlan,
            isFullQuality = currentAnalysis.quality != TrackAnalysis.QUALITY_HEAD,
            currentPosMs = player.currentPosition,
            durationSec = duration / 1000.0,
            allowCommit = allowClassicCommit,
        )
        lastSmartPlan = plan
        if (!plan.blocked && allowClassicCommit) acceptRoute(plan, classicRouteFields(plan))
        lastPlanStartedAtMs = nowMs()
        lastSmartPlanStartMs = (plan.transitionStart * 1000).roundToLong()
        publishMixPreview(currentItem, nextItem, duration, nextDuration, currentAnalysis, nextAnalysis, analysisState, plan, advanced)

        val markable = isClassicMarkable(
            blocked = plan.blocked,
            markerVisible = plan.markerVisible,
            durationMs = duration,
            currentState = analysisState.current,
            nextState = analysisState.next,
            reason = plan.reason,
            alreadyCommittedClassic = alreadyCommittedClassic,
            fallbackAdopted = fallbackAdopted,
        )
        val markerVerdict = when {
            markable -> "shown"
            plan.blocked -> "hidden: plan blocked (${plan.reason})"
            !plan.markerVisible -> "hidden: plan has no marker (${plan.reason})"
            duration <= 0L -> "hidden: no duration"
            analysisState.current != TrackAnalysisState.ANALYSED -> "hidden: current is ${analysisState.current}"
            analysisState.next !in MEASURED_ENOUGH_TO_ENTER_ON -> "hidden: next is ${analysisState.next}"
            else -> "hidden: next stream not ready"
        }
        if (markerVerdict != lastMarkerVerdict) {
            lastMarkerVerdict = markerVerdict
            Log.d(
                TAG,
                "marker ${currentItem.mediaId} \"${currentItem.mediaMetadata.title}\" -> " +
                    "${nextItem.mediaId} \"${nextItem.mediaMetadata.title}\": $markerVerdict",
            )
        }
        AppSettings.smartTransitionWindow.value = if (markable) {
            TransitionWindow(
                start = (plan.transitionStart * 1000.0 / duration).toFloat().coerceIn(0f, 1f),
                end = (plan.transitionEnd * 1000.0 / duration).toFloat().coerceIn(0f, 1f),
            )
        } else {
            null
        }
        if (markable && !plan.blocked && (plan.transitionStart * 1000).roundToLong() > player.currentPosition) {
            loopRefusal = null
        }

        if (plan.blocked || markerOnly) return

        val fade = plan.fadeMs
        if (fade <= 0L) return

        val transitionStartMs = (plan.transitionStart * 1000).roundToLong()
        plannedTransition = currentItem.mediaId to transitionStartMs
        val remaining = transitionStartMs - player.currentPosition
        if (remaining <= MIX_PREFETCH_MS && currentAnalysis.isUsable && nextAnalysis.isUsable) {
            prefetchMixWaves(
                currentItem, nextItem, duration, nextDuration,
                plan.transitionStart, plan.transitionEnd, plan.incomingCueTime,
                plan.incomingPlaybackRate / plan.outgoingPlaybackRate.coerceAtLeast(0.01),
            )
        }
        if (SleepTimer.afterTrack.value) {
            // Automix would start blending into the next track here; the sleep
            // timer means there is no next track, so run the same fade-out
            // against silence instead — see [beginSleepFadeOut].
            if (remaining > 0L) return
            beginSleepFadeOut(fade)
            return
        }
        holdAhead(player, duration)
        // Same arm-ahead margin as the standard path, just measured against
        // the plan's own start rather than a fixed offset from track end —
        // an analyzed mix-out anchor can place that start well before the
        // file actually ends.
        //
        // Plus, when the outgoing track is the one sped up, the beats that
        // ramp takes: it has to be at the incoming tempo by the fade, so it
        // starts that much earlier, from arming.
        //
        // djmix15-pilot: classic never stalls to EOF waiting on nextStreamReady.
        // That lambda (PlaybackService:2025: !MusicOnlyPlayback.wants &&
        // (!DirectSourceGate.isActive || isStreamReady 512KB prefix)) is
        // window-only at markable (1663) by documented intent; begin() below
        // prepares the standby over HTTP via AudioCache.playbackFactory /
        // DirectSourceGate.awaitOpen safely regardless (the window opens at
        // DirectSourceGate.WINDOW_MS=25s off plannedTransition published at
        // 1696). When not ready, arm on that 25 s window instead of the
        // shorter learned lead (20-35 s) so the standby gets the full window
        // to cache before ARM_TIMEOUT (12 s + grace). Earlier arm only
        // buffers longer — driveArming still waits for the fade point.
        val beatMs = (plan.beatSeconds * 1000).roundToLong()
        val rampMs = if (beatMs > 0L) rampStepsFor(plan.outgoingPlaybackRate) * beatMs else 0L
        val effectiveLeadMs = maxOf(
            blendArmLeadMs,
            if (!nextStreamReady(nextItem)) 25_000L else 0L,
        )
        if (remaining > effectiveLeadMs + rampMs) return

        if (begin(
                fade,
                endMs = (plan.transitionEnd * 1000).roundToLong(),
                smart = true,
                cueTimeMs = (plan.incomingCueTime * 1000).roundToLong(),
                playbackRate = plan.incomingPlaybackRate,
                outgoingRate = plan.outgoingPlaybackRate,
                beatMs = beatMs,
                renderStyle = renderFor(plan, advanced),
                plan = plan,
            )
        ) {
            loopRefusal = null
        }
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
        plannedNextId = out.getMediaItemAt(nextIndex).mediaId
        out.currentMediaItem?.let { ensureOpportunity(it.mediaId, requireNotNull(plannedNextId)) }
        // The last blend's stretch, if it is somehow still easing off: this
        // player is about to be the outgoing side of a new one.
        endEase(clickless = false)

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
        rampSteps = if (beatMs > 0L) rampStepsFor(outgoingRate) else 0
        rampStepMs = beatMs
        rampStartMs = endMs - fade - rampSteps * beatMs
        rampStep = 0
        render = renderStyle
        planInFlight = plan
        armDeadline = nowMs() + ARM_TIMEOUT_MS + rampSteps * beatMs
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

        // Carried across so the incoming track inherits the listener's own
        // settings rather than whatever the standby was left on last time.
        into.skipSilenceEnabled = out.skipSilenceEnabled
        into.repeatMode = out.repeatMode
        into.shuffleModeEnabled = out.shuffleModeEnabled
        // Stacks on top of the listener's speed control rather than replacing
        // it, so a beatmatched transition and "play everything at 1.25x" don't
        // fight each other. Undone in [finish].
        // Ordinary requests retain safeDeckSpeed; a locally admitted blend preserves
        // its checked physical rate, set once while this incoming deck is silent.
        into.setPlaybackSpeed(incomingBaseSpeed())
        buildupSpeed = into.playbackParameters.speed
        // Tempo only. The old pitch corrector turned a small transpose into a
        // time factor of about a thousand, which is the 999x runaway.
        tracks.incomingTempo(1f)
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

        phase = Phase.ARMING
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
                    rideFilters(0f, null)
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
            rideFilters(0f, outgoingAt)
        } else {
            if (armOutgoingPreFiltered) {
                armOutgoingPreFiltered = false
                resetPreFilteredOutgoing()
            }
            rideFilters(0f, null)
        }
        if (rampSteps > 0) rampOutgoing(out)
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
        executedRoute = classicRouteFields(planInFlight)
        mixEvent(
            "mix.fire",
            requireNotNull(executedRoute) + mapOf(
                "stage" to "executed", "executionMarker" to "classic-fade-fired",
                "mode" to "classic",
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
        nudgeUntil = 0L
        lockCount = 0
        outClock.reset()
        inClock.reset()
        // The incoming player's clock is still settling off its start.
        lockQuietUntil = fadeStartedAt + LOCK_START_MS
        // However the ramp went — a late arm, a slow buffer — the blend starts
        // on the incoming tempo.
        if (rampSteps > 0 && rampStep < rampSteps) {
            rampStep = rampSteps
            out.setPlaybackSpeed(deckSpeedSafe(AppSettings.playbackSpeed.value, outgoingPlaybackRate))
            tracks.outgoingTempo(1f)
        }
        outgoingAnalysis = out.currentMediaItem?.let(analysisFor) ?: TrackAnalysis()
        incomingAnalysis = into.currentMediaItem?.let(analysisFor) ?: TrackAnalysis()

        Log.d(TAG, "blend start at cue=${into.currentPosition}ms out=${out.currentPosition}ms")

        // Published before the mix flag, in the same tick: the scrubber shows
        // the old sheen for a mix flag with no blend beside it, and would flash
        // it for a frame otherwise.
        publishBlend(out, into, force = true)
        AppSettings.smartMixInProgress.value = isRealMix()
        if (isRealMix()) publishMixView(out, into)
        // The bar fills to the end through the first half of the blend, which
        // would run straight over the marker for the transition now under way.
        AppSettings.smartTransitionWindow.value = null
        phase = Phase.FADING
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
    private fun handOff(out: ExoPlayer, into: ExoPlayer) {
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
            easing = null
            easeOnActive = false
            softSpeedJob?.cancel()
            softSpeedJob = null
            nudgeUntil = 0L
            lockCount = 0
            // A locally admitted blend keeps its measured source mapping through
            // the remaining overlap. finish() releases it once; a midpoint reset
            // would invalidate that admission and de-sync the grids halfway through.
            if (!localBlendInFlight) {
                into.setPlaybackSpeed(deckSpeedSafe(AppSettings.playbackSpeed.value, 1.0, beatmatched = false))
            }
            tracks.incomingTempo(1f)
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
        onHandoff(out, into)

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
        // The incoming track gets the same say over the length as the outgoing
        // one did, so a long crossfade into a short track tightens rather than
        // swallowing it. Its duration is often still unknown when the fade
        // starts — the stream is only being opened — so this is read every tick
        // and simply narrows the span once the answer arrives. Capped only by
        // the incoming track's own length, not by [configuredFadeMs] — a Smart
        // Fade plan already sized itself independently of that setting, and
        // may be running with it at zero.
        // Measured from where the incoming track was *cued*, not from zero. A
        // Automix plan can drop it in mid-arrangement, and reading its raw
        // position as elapsed-fade would put a cue at 0:45 instantly past the
        // end of an 8-second fade — finishing the blend on its first tick and
        // landing as an abrupt cut, which is precisely the failure a cued
        // transition is supposed to avoid.
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
        val span = minOf(fadeMs, incomingCap).coerceAtLeast(1L)
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
        val outDuration = out.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val outPosition = out.currentPosition
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

        val rise = mixRise(progress)
        val fall = if (render.echo) {
            val fileEnd = echoFileLeft?.let { it.toDouble() / echoBudget } ?: Double.POSITIVE_INFINITY
            val fadeEnd = minOf(maxOf(1.0, echoTailEnd), fileEnd.coerceAtLeast(1.0))
            // The player fader carries both dry and wet. Keep it moving through
            // the existing tail, ending at the decoder EOF if that arrives first.
            if (echoHeard >= fadeEnd) 0f
            else MixGain.fall(((echoHeard - render.echoAt) / (fadeEnd - render.echoAt)).toFloat()).coerceAtLeast(0f)
        } else {
            mixFall(progress)
        }
        player.volume = rise
        out.volume = if (ranOn(out)) 0f else fall
        onBlendHeadroom(
            headroomFor(mixRise(outgoingAt), outgoingLevel(elapsed / wallSpan + tracks.outgoingLeadMs() / span.toFloat(), wallSpan)),
        )
        if (render.phaseLock) holdPhase(out, player, progress)

        // The midpoint, or sooner if the outgoing track is about to run out
        // from under it: until the handoff its queue still runs on past the
        // track, and reaching the end would start the next item — the very
        // song fading in — a second time on the player that is leaving.
        if (!handedOff && (progress >= render.handoffAt || (outRemaining != null && outRemaining <= HANDOFF_GUARD_MS))) {
            handOff(out, player)
        }
        publishBlend(out, player)
        if (AppSettings.smartMixView.value != null) AppSettings.smartMixProgress.value = progress
        rideFilters(incomingAt, outgoingAt)
        rideBuildup(progress)

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
                rideFilters(1f, 1f)
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
        if (!smartFadeActive) return
        val session = if (handedOff) into else out
        val playing = session.isPlaying
        val last = AppSettings.smartMixBlend.value
        // A paused blend's grid is frozen along with its players; re-deriving
        // it against a clock that keeps running would only slide the anchor.
        if (!force && last != null && !playing && !last.playing) return

        // Placed with [beatAt], the grid the phase lock itself steers by: beats
        // counted within the analyzer's downbeat bars, not off one interval from
        // the first beat. That constant grid drifts — a 0.1% tempo error is
        // 180ms three minutes in, a third of a beat, right where a blend runs —
        // so the pulse sat visibly off audio the lock had put exactly on it.
        // The rate is each player's real speed, which includes any phase-lock
        // nudge in flight rather than the stretch it was armed with.
        val sides = if (handedOff) {
            listOf(incomingAnalysis to into, outgoingAnalysis to out)
        } else {
            listOf(outgoingAnalysis to out, incomingAnalysis to into)
        }
        var beatMs = 0f
        var anchor = 0L
        for ((analysis, player) in sides) {
            if (!beatAt(analysis, positionMs(player) / 1000.0)) continue
            val rate = player.playbackParameters.speed.toDouble().takeIf { it > 0.0 } ?: 1.0
            val sinceBeat = beatScratch[0]
            beatMs = (beatScratch[1] * 1000.0 / rate).toFloat()
            anchor = System.nanoTime() - (sinceBeat / rate * 1e9).toLong()
            break
        }
        if (!force && last != null && last.playing == playing && sameGrid(last, beatMs, anchor)) return
        AppSettings.smartMixBlend.value = MixBlend(
            beatMs = beatMs,
            beatAnchorNanos = anchor,
            playing = playing,
        )
    }

    /**
     * The overlap on each track's own timeline, as the mix popup draws it,
     * and the context shown either side of it. Fixed by the plan, so arming
     * and the blend agree on it.
     */
    private class MixGeometry(val outStart: Double, val outEnd: Double, val inStart: Double, val inEnd: Double, val ratio: Double) {
        val outSpan get() = outEnd - outStart
        val context get() = outSpan * MIX_VIEW_CONTEXT
        val outFrom get() = outStart - context
        val outTo get() = outEnd + context
        val inFrom get() = inStart - context * ratio
        val inTo get() = inEnd + context * ratio
    }

    private fun mixGeometry(): MixGeometry {
        val outEnd = fadeEndMs / 1000.0
        val outStart = outEnd - (fadeMs / 1000.0).coerceAtLeast(0.5)
        val ratio = if (render.advanced) mediaRatio else 1.0
        val inStart = incomingCueTimeMs / 1000.0
        return MixGeometry(outStart, outEnd, inStart, inStart + (outEnd - outStart) * ratio, ratio)
    }

    /** A decoded waveform and the stretch of its track, in seconds, it covers. */
    private class DeckWave(val mediaId: String, val from: Double, val to: Double, val bands: MixWaveform.Bands) {
        fun covers(id: String, start: Double, end: Double) = id == mediaId && from <= start + 0.05 && to >= end - 0.05
    }

    /**
     * The waveforms for the next transition. Decoded ahead of it — see
     * [prefetchMixWaves] — so the popup has them the moment the blend starts.
     */
    private var outgoingWave: DeckWave? = null
    private var incomingWave: DeckWave? = null

    /** Which pair [mixViewJob] is decoding, so a tick does not start it twice. */
    private var mixWavePair: String? = null

    /** The media ids [mixViewJob] has still to decode, which the popup shows as skeletons. */
    private val pendingWaveIds = mutableSetOf<String>()

    /**
     * When a media id's waveform last failed to decode. Held off for
     * [MIX_WAVE_RETRY_MS] rather than retried on the next tick, which with the
     * popup open would be four failing decodes a second.
     */
    private val failedWaveAt = mutableMapOf<String, Long>()

    private fun waveFailedRecently(id: String) =
        failedWaveAt[id]?.let { nowMs() - it < MIX_WAVE_RETRY_MS } == true

    /**
     * Decodes both waveforms the mix popup will draw, once the next
     * transition is [MIX_PREFETCH_MS] away. Padded beyond the popup's own
     * context, since the plan can still shift a little before it arms; the
     * popup places each waveform by its time, so the extra is simply offscreen.
     *
     * Only the windows themselves are decoded, never the whole file, and on a
     * background thread long before they are needed: a blend can be over in
     * a few seconds, which is less than a decode deep into a file can take.
     */
    private fun prefetchMixWaves(
        outItem: MediaItem,
        inItem: MediaItem,
        outDurationMs: Long,
        inDurationMs: Long,
        outStart: Double,
        outEnd: Double,
        inStart: Double,
        ratio: Double,
        /** Each side on its own: the popup's preview decodes a track as soon as that one is measured. */
        wantOut: Boolean = true,
        wantIn: Boolean = true,
    ) {
        val span = (outEnd - outStart).coerceAtLeast(0.5)
        val pad = span * MIX_VIEW_CONTEXT + MIX_PREFETCH_PAD_SECONDS
        val outFrom = (outStart - pad).coerceAtLeast(0.0)
        val outTo = outEnd + pad
        val inFrom = (inStart - pad * ratio).coerceAtLeast(0.0)
        val inTo = inStart + (span + pad) * ratio
        val pair = "${outItem.mediaId}->${inItem.mediaId}"
        if (mixWavePair != null && mixWavePair != pair) failedWaveAt.clear()
        val outReady = !wantOut || waveFailedRecently(outItem.mediaId) ||
            outgoingWave?.covers(outItem.mediaId, outFrom + MIX_PREFETCH_PAD_SECONDS, outTo - MIX_PREFETCH_PAD_SECONDS) == true
        val inReady = !wantIn || waveFailedRecently(inItem.mediaId) ||
            incomingWave?.covers(inItem.mediaId, inFrom + MIX_PREFETCH_PAD_SECONDS, inTo - MIX_PREFETCH_PAD_SECONDS) == true
        if (outReady && inReady) return
        if (mixViewJob?.isActive == true && mixWavePair == pair) return
        mixViewJob?.cancel()
        mixWavePair = pair
        pendingWaveIds.clear()
        if (!outReady) pendingWaveIds += outItem.mediaId
        if (!inReady) pendingWaveIds += inItem.mediaId
        republishWaves()
        mixViewJob = scope.launch {
            suspend fun wave(item: MediaItem, durationMs: Long, from: Double, to: Double): DeckWave? =
                withContext(Dispatchers.IO) {
                    val began = nowMs()
                    val bins = ((to - from) * MIX_WAVE_BINS_PER_SECOND).toInt().coerceIn(64, MIX_WAVE_MAX_BINS)
                    val bands = runCatching { mixWaveform(item, durationMs, from, to, bins) }
                        .onFailure { Log.w(TAG, "mix waveform failed for ${item.mediaId}", it) }
                        .getOrNull()
                    Log.d(
                        TAG,
                        "mix waveform ${item.mediaId} " +
                            String.format(Locale.ROOT, "%.1f-%.1fs", from, to) +
                            ": ${if (bands == null) "none" else "ok"} in ${nowMs() - began}ms",
                    )
                    bands?.let { DeckWave(item.mediaId, from, to, it) }
                }
            if (!outReady) {
                outgoingWave = wave(outItem, outDurationMs, outFrom, outTo)
                if (outgoingWave == null) failedWaveAt[outItem.mediaId] = nowMs()
                pendingWaveIds -= outItem.mediaId
                republishWaves()
            }
            if (!inReady) {
                incomingWave = wave(inItem, inDurationMs, inFrom, inTo)
                if (incomingWave == null) failedWaveAt[inItem.mediaId] = nowMs()
                pendingWaveIds -= inItem.mediaId
                republishWaves()
            }
        }
    }

    /** Puts whatever waveforms have arrived into the views the popup can be showing. */
    private fun republishWaves() {
        AppSettings.smartMixView.value?.let { AppSettings.smartMixView.value = withWaves(it, mixViewIds) }
        AppSettings.smartMixPreview.value?.let { AppSettings.smartMixPreview.value = withWaves(it, mixPreviewIds) }
    }

    /** [view] with the decoded waveforms of [ids] in, and each deck marked while its own is still coming. */
    private fun withWaves(view: MixTransitionView, ids: Pair<String, String>?): MixTransitionView {
        fun MixDeck.with(wave: DeckWave?, id: String?): MixDeck {
            val decoded = if (wave != null && wave.mediaId == id) {
                withWave(wave.bands.low, wave.bands.mid, wave.bands.high, wave.from, wave.to)
            } else {
                this
            }
            return decoded.withDecoding(id != null && id in pendingWaveIds)
        }
        return view.withDecks(
            view.outgoing.with(outgoingWave, ids?.first),
            view.incoming.with(incomingWave, ids?.second),
        )
    }

    /** The media ids of the blend [AppSettings.smartMixView] is showing. */
    private var mixViewIds: Pair<String, String>? = null

    /** The media ids of the pair [AppSettings.smartMixPreview] lays out. */
    private var mixPreviewIds: Pair<String, String>? = null

    /** When [AppSettings.smartMixPreview] was last planned, so one nothing is planning any more is let go. */
    private var mixPreviewAt = 0L

    /** What the last preview showed, so a tick that changes nothing does not republish it. */
    private var mixPreviewKey = ""

    /**
     * Lays the next transition out for a mix popup opened by hand between
     * blends: the same decks, frame and faders the blend will show, from the
     * plan as it stands. Only while the popup is up — nobody else reads it —
     * and its waveforms are decoded now rather than [MIX_PREFETCH_MS] out,
     * because someone is looking.
     *
     * A side still being measured goes out flagged and without numbers, and
     * the popup draws a skeleton for it; the frame it sits in may still move
     * as that measurement lands.
     */
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
        if (!AppSettings.mixViewOpen.value || AppSettings.smartMixView.value != null) return
        mixPreviewAt = nowMs()
        val outStart = plan.transitionStart
        val outEnd = maxOf(plan.transitionEnd, outStart + 0.5)
        val inStart = plan.incomingCueTime
        val ratio = plan.incomingPlaybackRate / plan.outgoingPlaybackRate.coerceAtLeast(0.01)
        val geometry = MixGeometry(outStart, outEnd, inStart, inStart + (outEnd - outStart) * ratio, ratio)
        val currentAnalysing = state.current == TrackAnalysisState.WAITING || state.current == TrackAnalysisState.ANALYSING
        val nextAnalysing = state.next == TrackAnalysisState.WAITING || state.next == TrackAnalysisState.ANALYSING
        val beatSeconds = if (currentAnalysis.isUsable) plan.beatSeconds else 0.0
        val beats = if (beatSeconds > 0) (geometry.outSpan / beatSeconds).roundToInt() else 0

        // Before the waveforms, so a decode that starts now is marked on the decks below.
        prefetchMixWaves(
            currentItem, nextItem, duration, nextDuration, outStart, outEnd, inStart, ratio,
            wantOut = currentAnalysis.isUsable,
            wantIn = nextAnalysis.isUsable,
        )

        val ids = currentItem.mediaId to nextItem.mediaId
        val key = "${ids.first}|${ids.second}|${round(outStart * 20)}|${round(outEnd * 20)}|${round(inStart * 20)}|" +
            "$beats|$currentAnalysing|$nextAnalysing|${currentAnalysis.bpm}|${nextAnalysis.bpm}|" +
            "${currentAnalysis.key}|${nextAnalysis.key}|${plan.transitionStyle}|${plan.bassSwap}"
        if (key == mixPreviewKey && mixPreviewIds == ids && AppSettings.smartMixPreview.value != null) return
        mixPreviewKey = key

        // The plan's own fader curves, through the same code the blend will
        // ride them with. Swapped in and straight back out on this thread:
        // [render] is only read by the blend, and no blend is running while
        // there is a plan to preview.
        val planned = renderFor(plan, advanced)
        val running = render
        render = planned
        val samples = MIX_VIEW_FADER_POINTS
        val wallSpan = (plan.fadeMs * if (planned.advanced) ratio else 1.0).toFloat()
        val fadeOut: FloatArray
        val fadeIn: FloatArray
        try {
            fadeOut = FloatArray(samples) { outgoingLevel(it / (samples - 1f), wallSpan).coerceIn(0f, 1f) }
            fadeIn = FloatArray(samples) { mixRise(it / (samples - 1f)).coerceIn(0f, 1f) }
        } finally {
            render = running
        }

        fun deck(item: MediaItem, analysis: TrackAnalysis, durationMs: Long, analysing: Boolean, from: Double, to: Double) = MixDeck(
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            artworkUrl = item.mediaMetadata.artworkUri?.toString(),
            bpm = if (analysis.isUsable) analysis.bpm else 0.0,
            key = if (analysis.isUsable) analysis.key else "",
            durationMs = durationMs.takeIf { it > 0L } ?: (analysis.duration * 1000).roundToLong(),
            waveStart = from,
            waveEnd = to,
            analysing = analysing,
        )

        mixPreviewIds = ids
        AppSettings.smartMixPreview.value = withWaves(
            MixTransitionView(
                outgoing = deck(currentItem, currentAnalysis, duration, currentAnalysing, geometry.outFrom, geometry.outTo),
                incoming = deck(nextItem, nextAnalysis, nextDuration, nextAnalysing, geometry.inFrom, geometry.inTo),
                outStart = outStart,
                outEnd = outEnd,
                inStart = geometry.inStart,
                inEnd = geometry.inEnd,
                beats = beats,
                beatMarks = beatMarks(currentAnalysis, outStart, outEnd, beatSeconds),
                fadeOut = fadeOut,
                fadeIn = fadeIn,
                bassSwapAt = when {
                    plan.transitionStyle == TransitionStyle.DJ_FILTER &&
                        (plan.filterVariant == FilterTransitionVariant.BASS_HANDOFF || plan.filterVariant == FilterTransitionVariant.DECLINE) -> planned.filterSwapAtF
                    planned.bassSwap -> planned.swapAtF
                    else -> -1f
                },
            ),
            ids,
        )
    }

    /**
     * Lets the preview go once the popup is closed, a blend has taken over, or
     * nothing has planned the pair for a while — the queue ran out, a party
     * started, Automix was switched off. Only between blends: arming plans
     * nothing, and the preview is still the right picture until the blend
     * replaces it.
     */
    private fun expireMixPreview() {
        if (AppSettings.smartMixPreview.value == null) return
        val stale = phase == Phase.IDLE && nowMs() - mixPreviewAt > MIX_PREVIEW_STALE_MS
        if (AppSettings.mixViewOpen.value && AppSettings.smartMixView.value == null && !stale) return
        AppSettings.smartMixPreview.value = null
        mixPreviewIds = null
        mixPreviewKey = ""
    }

    /** Beats inside [outStart]..[outEnd] on [analysis]'s grid, as fractions of it, for the popup's grid lines. */
    private fun beatMarks(analysis: TrackAnalysis, outStart: Double, outEnd: Double, beatSeconds: Double): FloatArray {
        if (beatSeconds <= 0) return FloatArray(0)
        val span = outEnd - outStart
        val anchor = analysis.downbeats.lastOrNull { it <= outStart } ?: outStart
        val first = anchor + ceil((outStart - anchor) / beatSeconds) * beatSeconds
        return generateSequence(first) { it + beatSeconds }
            .takeWhile { it <= outEnd + 1e-6 }
            .map { ((it - outStart) / span).toFloat() }
            .toList().toFloatArray()
    }

    /**
     * Lays the blend just started out for the mix popup: both tracks' cards,
     * where the overlap sits on each timeline, its beats and the faders it
     * will ride, with whichever waveforms have already been decoded.
     */
    private fun publishMixView(out: ExoPlayer, into: ExoPlayer) {
        val plan = planInFlight ?: return
        val outItem = out.currentMediaItem ?: return
        val inItem = into.currentMediaItem ?: return
        val geometry = mixGeometry()
        val outStart = geometry.outStart
        val outEnd = geometry.outEnd
        val outSpan = geometry.outSpan
        val beatSeconds = plan.beatSeconds
        val beats = if (beatSeconds > 0) (outSpan / beatSeconds).roundToInt() else 0
        val marks = beatMarks(outgoingAnalysis, outStart, outEnd, beatSeconds)
        val wallSpan = (fadeMs * if (render.advanced) mediaRatio else 1.0).toFloat()
        val samples = MIX_VIEW_FADER_POINTS
        val fadeOut = FloatArray(samples) { outgoingLevel(it / (samples - 1f), wallSpan).coerceIn(0f, 1f) }
        val fadeIn = FloatArray(samples) { mixRise(it / (samples - 1f)).coerceIn(0f, 1f) }

        fun deck(item: MediaItem, player: ExoPlayer, analysis: TrackAnalysis, from: Double, to: Double) = MixDeck(
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            artworkUrl = item.mediaMetadata.artworkUri?.toString(),
            bpm = analysis.bpm,
            key = analysis.key,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                ?: (analysis.duration * 1000).roundToLong(),
            waveStart = from,
            waveEnd = to,
        )

        AppSettings.smartMixProgress.value = 0f
        mixViewIds = outItem.mediaId to inItem.mediaId
        // The blend is the picture from here; a popup opened on the plan moves onto it.
        AppSettings.smartMixPreview.value = null
        mixPreviewIds = null
        mixPreviewKey = ""
        AppSettings.smartMixView.value = MixTransitionView(
            outgoing = deck(outItem, out, outgoingAnalysis, geometry.outFrom, geometry.outTo),
            incoming = deck(inItem, into, incomingAnalysis, geometry.inFrom, geometry.inTo),
            outStart = outStart,
            outEnd = outEnd,
            inStart = geometry.inStart,
            inEnd = geometry.inEnd,
            beats = beats,
            beatMarks = marks,
            fadeOut = fadeOut,
            fadeIn = fadeIn,
            bassSwapAt = when {
                render.style == TransitionStyle.DJ_FILTER && (render.filterVariant == FilterTransitionVariant.BASS_HANDOFF || render.filterVariant == FilterTransitionVariant.DECLINE) ->
                    render.filterSwapAtF
                render.bassSwap -> render.swapAtF
                else -> -1f
            },
        )
        republishWaves()
    }

    private fun clearMixView() {
        mixViewJob?.cancel()
        mixViewJob = null
        mixWavePair = null
        mixViewIds = null
        outgoingWave = null
        incomingWave = null
        pendingWaveIds.clear()
        failedWaveAt.clear()
        AppSettings.smartMixView.value = null
    }

    /** Whether [beatMs] and [anchor] describe the grid [last] already published, to within a tick's jitter. */
    private fun sameGrid(last: MixBlend, beatMs: Float, anchor: Long): Boolean {
        if (beatMs <= 0f || last.beatMs <= 0f) return beatMs <= 0f && last.beatMs <= 0f
        if (abs(beatMs - last.beatMs) > beatMs * TEMPO_TOLERANCE) return false
        val beatNanos = (beatMs * 1_000_000.0).toLong().coerceAtLeast(1L)
        // Distance between the two anchors, the short way round one beat.
        val offset = Math.floorMod(anchor - last.beatAnchorNanos, beatNanos)
        return minOf(offset, beatNanos - offset) <= ANCHOR_TOLERANCE_NANOS
    }

    /**
     * Starts [Phase.SLEEP_FADE]: the track playing right now is faded to
     * silence on its own, over `fade` ms — the same span a real transition
     * into the next track would have spent on this side of the blend — and
     * then paused.
     *
     * Deliberately not [begin] with a no-op standby. [begin] loads the queue
     * onto the idle player and starts it silently so it is ready to take over
     * at the handoff; none of that should happen here; there is no handoff
     * coming, and preparing a second decoder just to throw it away the moment
     * the fade ends wastes exactly the work this exists to skip.
     */
    private fun beginSleepFadeOut(fade: Long) {
        val out = active()
        sleepFadeMs = fade.coerceAtLeast(1L)
        sleepFadeStartPositionMs = out.currentPosition
        sleepFadeStartGain = out.volume
        outgoing = out
        incoming = null
        handedOff = false
        Log.d(TAG, "sleep timer due: fading out over ${fade}ms instead of transitioning")
        phase = Phase.SLEEP_FADE
    }

    /**
     * The fade-out proper. Mirrors [driveFade]'s curve on the outgoing side —
     * same [fallGain], same position-driven progress — but there is no
     * incoming track to weigh it against, so the span is simply [sleepFadeMs].
     */
    private fun driveSleepFade() {
        val out = outgoing ?: return finishSleepFade()
        // The listener turned the timer off mid-fade — nothing left to do but
        // hand the track back at full volume and let ordinary transition
        // logic resume next tick.
        if (!SleepTimer.afterTrack.value) {
            out.volume = 1f
            outgoing = null
            phase = Phase.IDLE
            return
        }
        val elapsed = (out.currentPosition - sleepFadeStartPositionMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / sleepFadeMs).coerceIn(0f, 1f)
        out.volume = sleepFadeStartGain * fallGain(progress)
        val done = progress >= 1f ||
            out.playbackState == Player.STATE_ENDED ||
            out.playbackState == Player.STATE_IDLE
        if (done) finishSleepFade()
    }

    /** Pauses the track the fade-out was run on and retires the timer that asked for it. */
    private fun finishSleepFade() {
        outgoing?.pause()
        outgoing?.volume = 1f
        SleepTimer.cancel()
        outgoing = null
        phase = Phase.IDLE
    }

    /** Ramps the outgoing track away rather than cutting it, so an interruption has no click in it. */
    private fun driveBail() {
        val leaving = leavingOnBail()
        if (leaving == null) {
            finish()
            return
        }
        // djmix11: even bail (emergency) takes the shortened dissolve.
        val progress = (nowMs() - bailStartedAt).toFloat() /
            maxOf(BAIL_MS, PhraseConfig.EXIT_TAIL_EMERGENCY_MS.toLong())
        if (progress < 1f) {
            val fall = if (leaving === outgoing && ranOn(leaving)) 0f else bailFromGain * fallGain(progress)
            leaving.volume = fall
            onBlendHeadroom(headroomFor(1f, fall))
            return
        }
        finish()
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

    private var loopSession: LoopMixSession? = null
    private var loopBuildJob: Job? = null
    private var loopAlignJob: Job? = null

    /** Decks decoded ahead of arming, for [primedKey]. Held until the mix arms or the pair changes. */
    private var primedSession: LoopMixSession? = null
    private var primedKey: String? = null
    /** AUTOMIX-V2(orch): last plan key whose stem regions were prefetched. */
    private var stemPrefetchedKey: String? = null
    private var primeJob: Job? = null
    private var primeRetryAt = 0L
    private var buildGeneration = 0
    /** One opportunity survives revisions, retries and native -> classic cleanup. */
    private data class Opportunity(val attemptId: String, val outId: String, val inId: String, val scrubEpoch: Long)
    private val opportunityRunId = java.util.UUID.randomUUID().toString()
    private var opportunitySequence = 0L
    private var opportunity: Opportunity? = null
    private var planRevision = 0
    private var revisionRoute: Any? = null
    private var executedRoute: Map<String, Any?>? = null
    private var terminalRecorded = false
    private var routingFallback = false
    private data class NativeBinding(
        val context: Opportunity,
        val revision: Int,
        val generation: Int,
        val coarseDecline: Boolean?,
    )
    private var nativeBinding: NativeBinding? = null
    private val routingEvidenceKeys = mutableSetOf<String>()

    private fun ensureOpportunity(outId: String, inId: String): Opportunity {
        opportunity?.let { current ->
            if (current.outId == outId && current.inId == inId && current.scrubEpoch == scrubEpoch && !terminalRecorded) return current
        }
        // A pair changed without a measured terminal event: do not invent success.
        countdownStabilizer.clearFailedNativeRecovery()
        opportunity = Opportunity("$opportunityRunId:${++opportunitySequence}", outId, inId, scrubEpoch)
        planGeneration++
        planResultGeneration = -1L
        fallbackNaturalGeneration = -1L
        naturalProvisionalUntilPosMs = -1L
        planRevision = 0
        revisionRoute = null
        executedRoute = null
        initiatingExitReason = null
        capturedTakeoverSpliceBufferStatus = null
        capturedLoopTookOver = null
        nativeBinding = null
        terminalRecorded = false
        routingEvidenceKeys.clear()
        resetOpportunityEventKeys()
        return opportunity!!
    }

    /** Natural queue occurrences can share IDs; expire telemetry only, never audio/planning state. */
    private fun expireIdleOpportunity(reason: String) {
        if (phase != Phase.IDLE) return
        val context = opportunity ?: return
        if (!terminalRecorded) {
            mixEvent("mix.plan.rejected", mapOf("stage" to "terminal", "outcome" to "unknown",
                "terminal" to true, "refusalCode" to "media-occurrence-ended", "reason" to reason), context)
        }
        terminalRecorded = true
        opportunity = null
        planRevision = 0
        revisionRoute = null
        executedRoute = null
        nativeBinding = null
        routingEvidenceKeys.clear()
        resetOpportunityEventKeys()
    }

    /** Outer event guards must not hide the same verdict on a later opportunity. */
    private fun resetOpportunityEventKeys() {
        lastRejectedLogKey = null
        lastEmittedPlanVerdict = null
        lastArmDeathKey = null
        lastFallbackLogKey = null
        phraseLogKey = null
        lastPhraseNullKey = null
    }

    private fun opportunityFields(context: Opportunity?): Map<String, Any?> = mapOf(
        "attemptId" to context?.attemptId,
        "outId" to context?.outId, "inId" to context?.inId,
        "scrubEpoch" to context?.scrubEpoch,
        "outRenditionRevision" to "unknown", "inRenditionRevision" to "unknown",
        "outAnalysisRevision" to "unknown", "inAnalysisRevision" to "unknown",
    )

    private fun mixEvent(name: String, fields: Map<String, Any?>, context: Opportunity? = opportunity) {
        AutomixTelemetry.event(name, opportunityFields(context) + mapOf("planRevision" to planRevision) + fields)
    }

    private fun nativeRouteFields(
        plan: LoopMix.Plan,
        outLoops: Boolean? = null,
        inLoops: Boolean? = null,
        actualConfiguration: Boolean = false,
        coarseDecline: Boolean? = null,
    ): Map<String, Any?> {
        val configurationKnown = actualConfiguration || outLoops != null || inLoops != null
        // Telemetry may be assembled on the playback ticker. Keep its planned
        // endpoint summaries scalar-only; the full shared qualifier allocates
        // a clock and is run at admission, not again for every route event.
        val targetRate = plan.targetBpm / plan.out.bpm
        val reciprocalRate = 1.0 / targetRate
        val ratesFinite = plan.outLoops && plan.inLoops && targetRate.isFinite() && targetRate > 0.0 &&
            reciprocalRate.isFinite() && reciprocalRate > 0.0
        val isConfiguredCrown = if (configurationKnown) outLoops == true && inLoops == true
            else plan.outLoops && plan.inLoops
        val technique = when {
            configurationKnown && (outLoops == null || inLoops == null) -> "unknown-native"
            configurationKnown && outLoops == true && inLoops == true -> "crown-loop"
            configurationKnown -> "native-bridge"
            plan.outLoops && plan.inLoops -> "crown-loop" // requested candidate only
            else -> "native-bridge"
        }
        return mapOf(
            "technique" to technique,
            "rung" to plan.rung,
            "requestedOutLoops" to plan.outLoops, "requestedInLoops" to plan.inLoops,
            "configuredOutLoops" to outLoops, "configuredInLoops" to inLoops,
            "outLoopStart" to plan.out.loopStart, "outLoopEnd" to plan.out.loopEnd,
            "inLoopStart" to plan.inn.loopStart, "inLoopEnd" to plan.inn.loopEnd,
            "fold" to plan.fold, "rampBars" to plan.rampBars,
            "echoTail" to plan.echoTail, "carve" to plan.carve, "clash" to plan.clash,
            "coarseDecline" to coarseDecline,
            "coarseOverride" to (coarseDecline == true && isConfiguredCrown),
            "plannedOutGridSource" to if (plan.out.anchorBeats != null) "tracked" else "constant-grid",
            "plannedInGridSource" to if (plan.inn.anchorBeats != null) "tracked" else "constant-grid",
            "outGridSource" to "unknown", "inGridSource" to "unknown",
            "plannedOutMinRate" to if (ratesFinite) minOf(1.0, targetRate) else null,
            "plannedOutMaxRate" to if (ratesFinite) maxOf(1.0, targetRate) else null,
            "plannedInMinRate" to if (ratesFinite) minOf(1.0, reciprocalRate) else null,
            "plannedInMaxRate" to if (ratesFinite) maxOf(1.0, reciprocalRate) else null,
        )
    }

    private fun nativeSessionRouteFields(session: LoopMixSession, coarseDecline: Boolean? = null): Map<String, Any?> =
        nativeRouteFields(session.plan, session.configuredOutLoops, session.configuredInLoops,
            actualConfiguration = true, coarseDecline = coarseDecline) + mapOf(
            "outGridSource" to session.outGridSource.telemetryValue,
            "inGridSource" to session.inGridSource.telemetryValue,
            "choreo" to session.choreoMove, // AUTOMIX-V2(orch): executed-move attribution
        )

    private fun classicRouteFields(plan: TransitionPlan?): Map<String, Any?> = mapOf(
        "technique" to "classic", "transitionStyle" to plan?.transitionStyle?.name,
        "filterVariant" to plan?.filterVariant?.name, "djEffect" to plan?.djEffect?.name,
        "localBlendAdmission" to (plan?.policyReasons?.contains(LocalBlendAdmission.MARKER) == true),
        "plannedIncomingRate" to plan?.incomingPlaybackRate,
    )

    private fun acceptRoute(plan: Any, fields: Map<String, Any?>) {
        reviseRoute(plan)
        val key = "accepted|${fields["technique"]}|$planRevision"
        if (routingEvidenceKeys.add(key)) mixEvent("mix.plan", fields + mapOf("stage" to "accepted"))
    }

    private fun reviseRoute(plan: Any): Int {
        if (revisionRoute != plan) {
            revisionRoute = plan
            planRevision++
        }
        return planRevision
    }

    private fun preparedRoute(fields: Map<String, Any?>, context: Opportunity? = opportunity, revision: Int = planRevision, generation: Int? = null) {
        if (context != opportunity || terminalRecorded) return
        val key = "prepared|${fields["technique"]}|$revision|$generation"
        if (routingEvidenceKeys.add(key)) mixEvent("mix.plan", fields + mapOf(
            "stage" to "prepared", "planRevision" to revision, "buildGeneration" to generation,
        ), context)
    }

    private fun refusalCode(reason: String): String = reason
        .replace(Regex("\\s+\\d+ ms over$"), " deadline-over")
        .replace(Regex(" early-abort \\(\\d+ ms left\\)$"), " early-abort")

    private fun routeRefusal(name: String, reason: String, stage: String, technique: String, destination: String? = null,
        context: Opportunity? = opportunity, revision: Int = planRevision, generation: Int? = null,
        coarseDecline: Boolean? = null) {
        if (context != opportunity || terminalRecorded) return
        val code = refusalCode(reason)
        if (!routingEvidenceKeys.add("refusal|$stage|$technique|$code")) return
        mixEvent(name, mapOf("stage" to stage, "technique" to technique, "reason" to reason,
            "refusalCode" to code, "fallbackTo" to destination, "phase" to phase.name,
            "planRevision" to revision, "buildGeneration" to generation,
            "coarseDecline" to coarseDecline), context)
    }
    private val deckTiming = DeckTiming(nowMs)
    @Volatile private var deckSide: String = "outgoing"

    /** The loop currently being armed, and the analyses the shift re-checks against. */
    private var loopTarget: LoopMix.Plan? = null
    private var loopTargetStartMs = 0L
    private var loopOutItem: MediaItem? = null
    private var loopInItem: MediaItem? = null
    private var loopOutAnalysis: TrackAnalysis? = null
    private var loopInAnalysis: TrackAnalysis? = null
    private var loopSungOut: List<LoopMix.Span>? = null
    private var loopSungIn: List<LoopMix.Span>? = null
    private var loopOutDurationMs = 0L
    private var loopInDurationMs = 0L

    /** The pair (and analyses) the cached loop plan belongs to, and that plan; null when none is possible. */
    private var loopPlanKey: String? = null
    private var loopPlanned: LoopMix.Plan? = null
    /** Score sheet for [loopPlanned] when it came from the phrase planner. Null keeps the djmix6 envelopes. */
    private var loopScript: PhraseScript? = null
    private var phraseLock = PhrasePlanner.Lock()
    /** djmix9: first-shown countdown locks the rung (watchdog state). */
    private var shownCountdownKey: String? = null
    private var shownLoopStartMs: Long = 0L
    private var watchdogFired = false
    private var phrasePair: String? = null
    private var phraseDetail: String? = null
    private var phraseLogKey: String? = null
    // djmix14: memo of the last PhrasePlanner.select + dedup of phrase-null spam.
    private var phraseMemoKey: String? = null
    private var phraseMemoFresh: PhraseChoice? = null
    private var lastPhraseNullKey: String? = null

    /** Why the pair gets no loop mix, for the log and the stats line; null when it does. */
    private var loopRefusal: String? = null
    private var lastLoopVerdict = ""
    private var loopIncomingStarted = false
    /** djmix23: whether mix.handoff.deferred was logged for this loop session. */
    private var deferredHandoffLogged = false
    private var lastLoopPositionMs = -1L
    private var lastLoopAnnounceAt = 0L
    private var lastLoopBeat = -1

    /**
     * Synced lyrics per media id, once asked. [spans] is null when the song has
     * none. [cues] keep the line text so section labels can see a repeated chorus.
     */
    private class LyricSpans(
        val spans: List<LoopMix.Span>?,
        val cues: List<com.music.bitchord.playback.smart.LyricCue> = emptyList(),
    )

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

    /** Why [current] -> [next] can't be a loop mix right now, or null when it can. */
    private fun loopRefusalFor(
        player: ExoPlayer,
        current: MediaItem,
        next: MediaItem,
        state: SmartAnalysis,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
    ): String? = when {
        !LoopMixRenderer.available -> "no renderer"
        AppSettings.playbackSpeed.value != 1f -> "playback speed is not 1x"
        player.skipSilenceEnabled -> "skip silence is on"
        SleepTimer.afterTrack.value -> "sleep timer"
        !gridsReady(state, currentAnalysis, nextAnalysis) -> "waiting on analysis"
        isSourceBacked(current) || isSourceBacked(next) -> "analysed off another recording"
        loopPortOf(player)?.supportsLoopMix != true -> "this output can't take it"
        loopPortOf(standby()) == null -> "no standby sink"
        else -> null
    }

    /** A track whose analysis reads a matched YouTube recording rather than what plays. */
    private fun isSourceBacked(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        return uri.getQueryParameter("v") == null && uri.authority == "source"
    }

    /**
     * The loop mix for [current] -> [next], planned once per pair and analysis,
     * or null when the pair gets none (see [loopRefusal]).
     */
    private fun loopPlanFor(
        player: ExoPlayer,
        current: MediaItem,
        next: MediaItem,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        durationMs: Long,
        nextDurationMs: Long,
        state: SmartAnalysis,
    ): LoopMix.Plan? {
        val pair = "${current.mediaId}->${next.mediaId}"
        val coarseDecline = shouldDeclineToMix(currentAnalysis, nextAnalysis)
        loopRefusalFor(player, current, next, state, currentAnalysis, nextAnalysis)?.let { why ->
            noteLoopVerdict(pair, null, why, coarseDecline)
            return null
        }
        askLyrics(current, durationMs)
        askLyrics(next, nextDurationMs)
        // Null until the fetch returns. Planning does not wait: a missing
        // fetch is "no lyrics yet", and the vocal mask decides. An empty
        // span list is a fetch that found no synced lines.
        val outLyrics = lyricSpans[current.mediaId]
        val inLyrics = lyricSpans[next.mediaId]
        val cached = loopPlanned?.takeIf { loopPlanKey?.startsWith("$pair|") == true }
        // The shared cache also holds native bridges. Only a two-sided loop is
        // crown evidence; do not KEEP a bridge here or erase its phrase script.
        val sticky = cached?.takeIf(::isCrown)
        val armed = phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING
        sticky?.let { retained ->
            retainedPlanRefusal(retained, currentAnalysis, nextAnalysis, outLyrics?.spans, inLyrics?.spans)?.let { reason ->
                dropRefusedLoop(pair, armed && !handedOff, reason, coarseDecline)
                return null
            }
            retainedBoundsRefusal(retained, player.duration, nextDurationMs)?.let { reason ->
                dropRefusedLoop(pair, armed && !handedOff, reason, coarseDecline)
                return null
            }
        }
        if (sticky == null && (lyricsPending(current.mediaId, currentAnalysis) || lyricsPending(next.mediaId, nextAnalysis))) {
            noteLoopVerdict(pair, null, "waiting on lyrics", coarseDecline)
            return null
        }
        // djmix14: a sticky plan whose loopStart the playhead already crossed
        // (+50 ms tolerance) is dead. It MUST be discarded (CLEAR), never
        // kept by Hold.KEEP — the KEEP path resurrected the pre-scrub plan
        // with a past loopStart and short-circuited classic begin().
        if (sticky != null && !SeekAction.planStillValid(
                (sticky.out.loopStart * 1000).roundToLong() + PhraseConfig.PREP_TOLERANCE_MS,
                player.currentPosition,
            )
        ) {
            noteLoopVerdict(pair, null, "stale loop start passed", coarseDecline)
            loopPlanned = null
            loopScript = null
            loopPlanKey = null
            return null
        }
        val seekPast = sticky != null && player.currentPosition > sticky.out.loopStart * 1000 + 1_500
        val why = StringBuilder()
        // djmix18 (Bug 3): clamp to the live playable stream so rendition mismatches
        // (analysis.duration=220s vs stream=180s) never place the loop start past EOF.
        val playableOutSec = (player.duration.takeIf { it != C.TIME_UNSET && it > 1_000L } ?: 0L) / 1000.0
        val candidate = LoopMix.plan(
            out = currentAnalysis,
            outDuration = durationMs / 1000.0,
            inn = nextAnalysis,
            inDuration = nextDurationMs / 1000.0,
            earliestOutSec = when {
                sticky == null -> player.currentPosition / 1000.0 + 1.0
                seekPast -> player.currentPosition / 1000.0
                else -> sticky.out.loopStart
            },
            sungOut = outLyrics?.spans,
            sungIn = inLyrics?.spans,
            why = why,
            playableOutDurationSec = playableOutSec,
        )
        val stickyVeto = sticky != null && LoopMix.vetoes(sticky, currentAnalysis, nextAnalysis, outLyrics?.spans, inLyrics?.spans)
        val candidateRefusal = candidate?.let {
            retainedPlanRefusal(it, currentAnalysis, nextAnalysis, outLyrics?.spans, inLyrics?.spans)
        }
        val replacement = candidate?.takeIf { isCrown(it) && candidateRefusal == null }
        when (LoopMix.holdLoop(armed, sticky != null, replacement != null, pairChanged = false, seekPast, sungVeto = stickyVeto)) {
            LoopMix.Hold.KEEP -> {
                // Lyrics or a mask can arrive after the plan was stored. KEEP is
                // not a free pass: the same check runs again here, and a veto
                // drops the loop so the next rung can take the pair.
                if (sticky != null && LoopMix.vetoes(sticky, currentAnalysis, nextAnalysis, outLyrics?.spans, inLyrics?.spans)) {
                dropVetoedLoop(pair, armed, coarseDecline)
                    return null
                }
                loopRefusal = null
                return sticky
            }
            LoopMix.Hold.TAKE -> if (armed && stickyVeto && !handedOff) {
                loopBail("sung veto", settle = false)
                loopRefusal = null
            }
            LoopMix.Hold.CLEAR -> {
                if (stickyVeto) {
                    dropVetoedLoop(pair, armed && !handedOff, coarseDecline)
                    return null
                }
                val reason = candidateRefusal ?: if (why.isEmpty()) "no later bar" else why.toString()
                noteLoopVerdict(pair, null, reason, coarseDecline)
                if (cached == null || isCrown(cached)) {
                    loopPlanned = null
                    loopScript = null
                    loopPlanKey = null
                }
                return null
            }
        }
        val base = replacement ?: return null
        // djmix14-review: new loop start must be ahead by the decode lead,
        // via the shared isLoopFastReady exact-bounds check.
        run {
            val posSec = player.currentPosition / 1000.0
            val sampleRate = loopPortOf(player)?.sampleRate ?: 48000
            val lead = fastLeadSec(base, current, next, sampleRate)
            if (base.out.loopStart < posSec + lead) {
                noteLoopVerdict(pair, null, "loop too near playhead", coarseDecline)
                return null
            }
        }
        val crowned = crownLoop(base, current, next, currentAnalysis, nextAnalysis)
        val plan = stamp(crowned, currentAnalysis, nextAnalysis)
        retainedPlanRefusal(plan, currentAnalysis, nextAnalysis, outLyrics?.spans, inLyrics?.spans)?.let { reason ->
            noteLoopVerdict(pair, null, reason, coarseDecline)
            return null
        }
        loopPlanKey = "$pair|${plan.out.loopStart}"
        loopPlanned = plan
        loopScript = null
        phraseDetail = null
        loopRefusal = null
        noteLoopVerdict(pair, plan, null, coarseDecline)
        return plan
    }

    /**
     * Forgets a loop the lyrics or the vocal mask no longer allow. When it was
     * already arming, the decks come down so this tick can start the next rung.
     */
    private fun dropVetoedLoop(pair: String, disarm: Boolean, coarseDecline: Boolean? = null) {
        dropRefusedLoop(pair, disarm, "sung veto", coarseDecline)
    }

    private fun dropRefusedLoop(pair: String, disarm: Boolean, reason: String, coarseDecline: Boolean? = null) {
        loopPlanned = null
        loopScript = null
        loopPlanKey = null
        if (disarm) {
            loopFallBack(reason)
            loopRefusal = reason
        } else {
            noteLoopVerdict(pair, null, reason, coarseDecline)
        }
    }

    private fun isCrown(plan: LoopMix.Plan): Boolean = plan.outLoops && plan.inLoops

    private fun nativeFamily(plan: LoopMix.Plan): CountdownStabilizer.Family =
        if (isCrown(plan)) CountdownStabilizer.Family.LOOP else CountdownStabilizer.Family.NATIVE_BRIDGE

    private fun retainedBoundsRefusal(plan: LoopMix.Plan, outDurationMs: Long, inDurationMs: Long): String? = when {
        outDurationMs > 1_000L && plan.out.loopEnd * 1000 > outDurationMs -> "outgoing loop beyond playable duration"
        inDurationMs > 1_000L && plan.inn.loopEnd * 1000 > inDurationMs -> "incoming loop beyond playable duration"
        else -> null
    }

    /** The same immutable geometry admission is used for NEW, KEEP and restoration. */
    private fun retainedPlanRefusal(
        plan: LoopMix.Plan,
        out: TrackAnalysis,
        inn: TrackAnalysis,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
    ): String? {
        // Local contradictory evidence is authoritative even on an armed crown.
        if (LoopMix.vetoes(plan, out, inn, sungOut, sungIn)) return "sung veto"
        if (plan.echoTail) {
            plan.handoff?.let { handoff ->
                com.music.bitchord.playback.smart.EchoAdmission.musicalRefusal(handoff, out, inn)
                    ?.let { return it }
            }
        }
        if (isCrown(plan)) {
            val qualification = LoopMix.qualifyCrown(plan)
            if (!qualification.accepted) return qualification.refusal ?: "crown qualification refused"
        }
        return null
    }

    /** True for 1.5 s after lyrics were asked, and only when no vocal mask can decide instead. */
    private fun lyricsPending(id: String, analysis: TrackAnalysis): Boolean {
        if (lyricSpans.containsKey(id)) return false
        if (analysis.vocalWindows.isNotEmpty()) return false
        val asked = lyricsAskedAt[id] ?: return false
        return nowMs() - asked < 1_500L
    }

    /** A head and a tail are enough. The whole-track pass can still be running. */
    private fun gridsReady(state: SmartAnalysis, out: TrackAnalysis, inn: TrackAnalysis): Boolean {
        fun ready(st: TrackAnalysisState, analysis: TrackAnalysis, tail: Boolean): Boolean {
            if (st == TrackAnalysisState.ANALYSED) return true
            if (st != TrackAnalysisState.REFINING) return false
            val side = if (tail) analysis.tailBeats else analysis.headBeats
            return side != null && side.bpm > 0 && side.confidence >= 0.45
        }
        return ready(state.current, out, tail = true) && ready(state.next, inn, tail = false)
    }

    /**
     * The rung after a loop the pair cannot do, on the same renderer. Null
     * leaves the dual player to crossfade.
     */
    private fun nativeBridge(
        player: ExoPlayer,
        current: MediaItem,
        next: MediaItem,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        durationMs: Long,
        nextDurationMs: Long,
    ): LoopMix.Plan? {
        val coarseDecline = shouldDeclineToMix(currentAnalysis, nextAnalysis)
        when (loopRefusal) {
            "no renderer",
            "playback speed is not 1x",
            "skip silence is on",
            "sleep timer",
            "waiting on analysis",
            "waiting on lyrics",
            "analysed off another recording",
            "this output can't take it",
            "no standby sink",
            -> return null
        }
        if (handedOff) return null
        if (lastSmartPlan?.transitionStyle == TransitionStyle.GAPLESS) return null
        val pair = "${current.mediaId}->${next.mediaId}"
        // djmix14: stale sticky is dead — clear so UI never reads a past plan.
        loopPlanned?.takeIf { loopPlanKey?.startsWith("$pair|") == true }?.let { stale ->
            if (!SeekAction.planStillValid(
                    (stale.out.loopStart * 1000).roundToLong() + PhraseConfig.PREP_TOLERANCE_MS,
                    player.currentPosition,
                )
            ) {
                loopPlanned = null
                loopScript = null
                loopPlanKey = null
            }
        }
        val committed = countdownStabilizer.committedFor(pair, scrubEpoch)
        loopPlanned?.takeIf { cached ->
            !isCrown(cached) && loopPlanKey?.startsWith("$pair|") == true &&
                committed?.family == CountdownStabilizer.Family.NATIVE_BRIDGE &&
                (committed.locked || committed.shown) &&
                retainedPlanRefusal(cached, currentAnalysis, nextAnalysis,
                    lyricSpans[current.mediaId]?.spans, lyricSpans[next.mediaId]?.spans) == null &&
                retainedBoundsRefusal(cached, player.duration, nextDurationMs) == null
        }?.let { cached ->
            loopRefusal = null
            return cached
        }
        if (shouldRunPhrasePlannerForBridge(currentAnalysis, nextAnalysis)) {
            phrasePlanFor(player, current, next, currentAnalysis, nextAnalysis, durationMs)?.let { candidate ->
                val phrase = candidate.plan
                val phraseScript = candidate.script
                val stamped = stamp(phrase, currentAnalysis, nextAnalysis)
                if (isCrown(stamped) && committed?.family == CountdownStabilizer.Family.NATIVE_BRIDGE &&
                    (committed.locked || committed.shown)) return@let
                val phraseRefusal = retainedPlanRefusal(stamped, currentAnalysis, nextAnalysis,
                    lyricSpans[current.mediaId]?.spans, lyricSpans[next.mediaId]?.spans)
                if (phraseRefusal != null) {
                    noteLoopVerdict(pair, null, phraseRefusal, coarseDecline)
                    return@let
                }
                val alreadyPlanned = loopPlanned?.let {
                    loopPlanKey?.startsWith("$pair|") == true && abs(it.out.loopStart - stamped.out.loopStart) < 0.05
                } == true
                if (!alreadyPlanned) {
                    val posSec = player.currentPosition / 1000.0
                    val sampleRate = loopPortOf(player)?.sampleRate ?: 48000
                    val lead = fastLeadSec(stamped, current, next, sampleRate)
                    if (stamped.out.loopStart < posSec + lead) {
                        // Rejected: neither loopScript nor phraseDetail is
                        // committed, so the UI never shows this candidate.
                        noteLoopVerdict(pair, null, "bridge too near", coarseDecline)
                        return@let
                    }
                }
                loopPlanKey = "$pair|${stamped.rung}|${stamped.out.loopStart}"
                loopPlanned = stamped
                loopScript = phraseScript
                commitPhrase(candidate)
                loopRefusal = null
                noteLoopVerdict(pair, stamped, null, coarseDecline)
                return stamped
            }
        }
        val playableOutSecBridge = (player.duration.takeIf { it != C.TIME_UNSET && it > 1_000L } ?: 0L) / 1000.0
        val plan = LoopMix.bridge(
            out = currentAnalysis,
            outDuration = durationMs / 1000.0,
            inn = nextAnalysis,
            inDuration = nextDurationMs / 1000.0,
            earliestOutSec = player.currentPosition / 1000.0 + 1.0,
            sungOut = lyricSpans[current.mediaId]?.spans,
            sungIn = lyricSpans[next.mediaId]?.spans,
            outVideo = current.isVideoOrigin,
            inVideo = next.isVideoOrigin,
            playableOutDurationSec = playableOutSecBridge,
        ) ?: return null
        val stamped = stamp(plan, currentAnalysis, nextAnalysis)
        run {
            val posSec = player.currentPosition / 1000.0
            val sampleRate = loopPortOf(player)?.sampleRate ?: 48000
            val lead = fastLeadSec(stamped, current, next, sampleRate)
            if (stamped.out.loopStart < posSec + lead) {
                noteLoopVerdict(pair, null, "bridge too near", coarseDecline)
                return null
            }
        }
        loopPlanKey = "$pair|${stamped.rung}|${stamped.out.loopStart}"
        loopPlanned = stamped
        loopScript = null
        phraseDetail = null
        loopRefusal = null
        noteLoopVerdict(pair, stamped, null, coarseDecline)
        return stamped
    }

    /**
     * Keeps a djmix6 loop. A phrase-aligned alternative is used only when it
     * is still a loop and its bar counts are at least the legacy ones.
     */
    private fun crownLoop(
        legacy: LoopMix.Plan,
        current: MediaItem,
        next: MediaItem,
        out: TrackAnalysis,
        inn: TrackAnalysis,
    ): LoopMix.Plan {
        if (!legacy.outLoops || !legacy.inLoops || !PhraseConfig.PHRASE_PLANNER_ENABLED) return legacy
        return runCatching {
            val playableCrown = if (out.duration.isFinite() && out.duration > 1.0) out.duration else 0.0
            val choice = PhrasePlanner.select(
                SongMaps.fromAnalysis(current.mediaId, out),
                SongMaps.fromAnalysis(next.mediaId, inn),
                regionalCamelotSteps(out, inn),
                out.bpm, inn.bpm,
                legacyLoop = legacy,
                playableOutDurationSec = playableCrown,
                outAnalysis = out,
                inAnalysis = inn,
            )
            val kept = choice?.legacyLoop ?: return legacy
            if (kept.out.bars >= legacy.out.bars && kept.inn.bars >= legacy.inn.bars && kept.outLoops && kept.inLoops) kept else legacy
        }.getOrDefault(legacy)
    }

    /**
     * The phrase planner, or null so [LoopMix.bridge] runs. Exceptions stay
     * inside this function: a structure bug must not take down playback.
     * Returns the plan paired atomically with its [PhraseScript] and its
     * not-yet-committed [phraseDetail] so a rejected candidate cannot leave
     * a stale [loopScript] or phrase detail behind for a later plan; the
     * caller assigns [loopScript] and commits the detail only on
     * acceptance. A null return (no fresh choice, lyrics pending, or
     * [PhrasePlanner.Lock.consider] holding nothing) produces no candidate
     * and leaves any committed script and detail untouched.
     */
    private fun phrasePlanFor(
        player: ExoPlayer,
        current: MediaItem,
        next: MediaItem,
        out: TrackAnalysis,
        inn: TrackAnalysis,
        durationMs: Long,
    ): PhraseCandidate? {
        if (!PhraseConfig.PHRASE_PLANNER_ENABLED) return null
        val pair = "${current.mediaId}->${next.mediaId}"
        if (phrasePair != pair) {
            phrasePair = pair
            phraseLock = PhrasePlanner.Lock()
            phraseDetail = null
            phraseLogKey = null
        }
        val outMap = PhraseMaps.get(current.mediaId) ?: runCatching { SongMaps.fromAnalysis(current.mediaId, out) }.getOrNull()
        val inMap = PhraseMaps.get(next.mediaId) ?: runCatching { SongMaps.fromAnalysis(next.mediaId, inn) }.getOrNull()
        // djmix14 memo: PhrasePlanner.select is combinatorial; tick must
        // never recompute it every 250 ms for the same pair/qualities/BPMs.
        val earliestBar = if (out.bpm > 0) ((player.currentPosition / 1000.0) / (240.0 / out.bpm)).toInt() else 0
        val memoKey = "$pair|${out.quality}|${inn.quality}|${out.bpm}|${inn.bpm}|$earliestBar|${outMap?.hashCode()}|${inMap?.hashCode()}|${outMap?.vocalEnergy?.contentHashCode()}|${outMap?.sungBars?.contentHashCode()}|${inMap?.vocalEnergy?.contentHashCode()}|${inMap?.sungBars?.contentHashCode()}"
        val fresh = if (memoKey == phraseMemoKey) {
            phraseMemoFresh
        } else {
            val computed = runCatching {
                // djmix18: live playable duration so cue exits never land past EOF.
                val playableOutSecPhrase = (player.duration.takeIf { it != C.TIME_UNSET && it > 1_000L } ?: 0L) / 1000.0
                PhrasePlanner.select(
                    outMap, inMap, regionalCamelotSteps(out, inn), out.bpm, inn.bpm,
                    earliestExitSec = player.currentPosition / 1000.0,
                    playableOutDurationSec = playableOutSecPhrase,
                    outAnalysis = out,
                    inAnalysis = inn,
                )
            }.getOrNull()
            phraseMemoKey = memoKey
            phraseMemoFresh = computed
            computed
        }
        if (fresh == null) {
            // djmix14 dedup: one line per (pairKey, detail), not 1800.
            val nullKey = "$pair|${PhrasePlanner.lastVetoSummary}"
            if (nullKey != lastPhraseNullKey) {
                lastPhraseNullKey = nullKey
            AutomixTelemetry.event("mix.plan.rejected", mapOf("rung" to "phrase-null", "detail" to PhrasePlanner.lastVetoSummary))
            }
        }
        if (!phraseLock.locked && (lyricsPending(current.mediaId, out) || lyricsPending(next.mediaId, inn))) {
            return null
        }
        val now = nowMs() / 1000.0
        val release = fresh?.exit?.releaseSec ?: (durationMs / 1000.0)
        val left = release - player.currentPosition / 1000.0
        val chosen = phraseLock.consider(now, left, fresh) ?: return null
        return compilePhrase(chosen, out, inn, current.mediaId, next.mediaId)
    }

    /**
     * Compiles [choice] into an uncommitted [PhraseCandidate]. This is
     * side-effect free with respect to the committed detail/log state:
     * [phraseDetail], [phraseLogKey], telemetry and logcat are only
     * written by [commitPhrase], once the caller has accepted the
     * candidate's plan. A candidate that is compiled but never committed
     * (too-near / geometry rejection) leaves the committed UI line, the
     * dedup key and the log output exactly as they were.
     */
    private fun compilePhrase(
        choice: com.music.bitchord.playback.smart.PhraseChoice,
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outId: String = loopOutItem?.mediaId.orEmpty(),
        inId: String = loopInItem?.mediaId.orEmpty(),
    ): PhraseCandidate {
        val script = TransitionMoves.compile(choice, out.bpm, inn.bpm, outgoingId = outId, incomingId = inId)
        val line = TransitionMoves.phraseLine(choice) + " · " + TransitionMoves.sectionLine(choice) +
            " · exit tail %.1fs".format(Locale.US, script.plan.exitTailSeconds)
        val logKey = "${choice.template}|${phraseLock.locked}|${phraseLock.slides}|${choice.exit.releaseBar}"
        return PhraseCandidate(
            plan = script.plan,
            script = script,
            detail = line,
            logKey = logKey,
            locked = phraseLock.locked,
            slides = phraseLock.slides,
            lockedAtSec = phraseLock.lockedAtSec,
            out = out,
            inn = inn,
        )
    }

    /**
     * Commits an accepted [PhraseCandidate]: [phraseDetail] moves to the
     * candidate's line together with the accepted plan/script, and the
     * `mix.plan` telemetry plus the BitChordPhrase/AutomixScript logs
     * fire once per distinct [PhraseCandidate.logKey] — exactly as they
     * did when [compilePhrase] emitted them. A log key that was never
     * committed (rejected candidate) has not advanced [phraseLogKey],
     * so a later acceptance of that same candidate still logs.
     */
    private fun commitPhrase(candidate: PhraseCandidate) {
        phraseDetail = candidate.detail
        if (candidate.logKey == phraseLogKey) return
        phraseLogKey = candidate.logKey
        val choice = candidate.script.choice
        val out = candidate.out
        val inn = candidate.inn
        mixEvent("mix.plan", nativeRouteFields(candidate.plan, coarseDecline = shouldDeclineToMix(out, inn)) + mapOf("stage" to "candidate", "template" to choice.template, "locked" to candidate.locked, "slides" to candidate.slides, "bpmA" to out.bpm, "bpmB" to inn.bpm, "statusA" to out.status, "statusB" to inn.status, "qualityA" to out.quality, "qualityB" to inn.quality, "exitTailSec" to candidate.script.plan.exitTailSeconds,
            // djmix25 P2.13 telemetry completeness.
            "reason" to "phrase-${choice.template.name}",
            "rung" to choice.rung,
            "style" to choice.template.name,
            "transitionStyle" to "PHRASE",
            "decline" to shouldDeclineToMix(out, inn),
            "declineToMix" to shouldDeclineToMix(out, inn),
            "camelotLeft" to (camelotCode(com.music.bitchord.playback.smart.exitKeyForMix(out)) ?: ""),
            "camelotRight" to (camelotCode(com.music.bitchord.playback.smart.entryKeyForMix(inn)) ?: ""),
            "fxSlot" to com.music.bitchord.playback.smart.TransitionMoves.fxSlot(out.trackId, inn.trackId),
            "vocalOverlapBars" to choice.vocalOverlapBeats,
            "styleFamily" to "PHRASE",
            "exitKind" to choice.exit.kind.name,
            "entryKind" to choice.entry.kind.name,
            "sameAlbum" to false,
            "gapless" to false,
            "filterVariant" to "",
            "djEffect" to "",
        ))
        val counts = MixDebug.counts()
        val json = TransitionMoves.scriptJson(
            candidate.script, counts[0], counts[1], counts[2],
            bpmA = out.bpm, bpmB = inn.bpm,
            camelot = regionalCamelotSteps(out, inn),
            lockedAt = candidate.lockedAtSec,
            slides = candidate.slides,
        )
        if (candidate.locked) {
            Log.i("BitChordPhrase", candidate.detail)
            Log.i("AutomixScript", json)
        } else {
            Log.i(
                "BitChordPhrase",
                "plan ${choice.tempoPolicy} ${choice.swapRampName}=${choice.swapRampMs} ${choice.echoDelayName}",
            )
        }
    }

    private fun stamp(plan: LoopMix.Plan, out: TrackAnalysis, inn: TrackAnalysis): LoopMix.Plan {
        val steps = regionalCamelotSteps(out, inn)
        val left = camelotCode(com.music.bitchord.playback.smart.exitKeyForMix(out))
        val right = camelotCode(com.music.bitchord.playback.smart.entryKeyForMix(inn))
        val keys = if (left != null && right != null) "$left→$right" else plan.keys
        val outConf = com.music.bitchord.playback.smart.exitKeyConfidence(out)
        val inConf = com.music.bitchord.playback.smart.entryKeyConfidence(inn)
        val keyClash = steps != null && steps > 2 && outConf >= 0.5 && inConf >= 0.5
        val clash = plan.clash || (plan.rung != "Echo" && keyClash)
        val carve = plan.carve || (plan.rung == "Beat blend" && keyClash)
        return plan.copy(clash = clash, carve = carve, keys = keys)
    }

    private fun noteLoopVerdict(pair: String, plan: LoopMix.Plan?, refusal: String?, coarseDecline: Boolean? = null) {
        loopRefusal = refusal
        if (refusal != null) routeRefusal("mix.plan.rejected", refusal, "candidate",
            if (refusal.startsWith("bridge")) "native-bridge" else "crown-loop",
            context = opportunity, coarseDecline = coarseDecline)
        if (plan != null) {
            val revision = reviseRoute(plan)
            val fields = nativeRouteFields(plan, coarseDecline = coarseDecline)
            if (routingEvidenceKeys.add("candidate|${fields["technique"]}|$revision")) {
                mixEvent("mix.plan", fields + mapOf("stage" to "candidate"))
            }
        }
        val verdict = "$pair: " + (plan?.let { "loop mix at ${"%.2f".format(Locale.ROOT, it.out.loopStart)}s · ${describeLoop(it)}" } ?: "no loop mix ($refusal)")
        if (verdict == lastLoopVerdict) return
        lastLoopVerdict = verdict
        Log.d(TAG, "loop $verdict")
    }

    /** "out 4-bar, clear by lyrics · in 2-bar, clear by mask · 111 → 85 BPM over 11 bars". */
    private fun describeLoop(plan: LoopMix.Plan): String {
        val from = plan.out.bpm.roundToInt()
        val to = plan.targetBpm.roundToInt()
        val tempo = if (from == to) "$from BPM" else "$from → $to BPM over ${plan.rampBars} bars"
        val keys = if (plan.keys.isBlank()) "" else " · ${plan.keys}"
        if (plan.rung != "Loop") return "${plan.rung} · ${plan.totalBars} bars · $tempo$keys"
        val fold = when {
            plan.fold > 1 -> " (next in double time)"
            plan.fold < 1 -> " (next in half time)"
            else -> ""
        }
        fun side(pick: LoopMix.Pick): String {
            val by = when (pick.checkedBy) {
                LoopMix.Check.LYRICS -> "lyrics"
                LoopMix.Check.MASK -> "mask"
                LoopMix.Check.LYRICS_AND_MASK -> "lyrics + mask"
            }
            return "${pick.bars}-bar, clear by $by"
        }
        return "out ${side(plan.out)} · in ${side(plan.inn)} · $tempo$fold$keys"
    }

    /**
     * Starts building the decks as soon as a plan exists, off the playback
     * thread. Arming the sinks still waits for [LOOP_ARM_LEAD_MS]; the decode
     * does not. A build that is still running when the loop's own start
     * arrives is shifted to the next bar in [driveLoopArming], not discarded.
     */
    private fun primeLoopDecks(
        player: ExoPlayer,
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        nextDurationMs: Long,
    ) {
        val into = standby()
        if (player === into) return
        val outPort = loopPortOf(player) ?: return
        val sampleRate = outPort.sampleRate
        if (sampleRate <= 0) return
        val key = primeKey(plan, outItem, inItem, sampleRate)
        if (primedKey == key && primedSession != null) return
        if (primeJob?.isActive == true && primedKey == key) return
        if (primedKey == key && nowMs() < primeRetryAt) return
        // AUTOMIX-V2(orch): stems are precomputed on the analysis worker, never separated at the splice.
        val prefetch = prefetchStems
        if (prefetch != null && stemPrefetchedKey != key) {
            stemPrefetchedKey = key
            runCatching {
                val bounds = LoopMix.regionBounds(plan)
                prefetch.invoke(outItem, durationMs, bounds.outFrom, bounds.outTo, sampleRate)
                prefetch.invoke(inItem, nextDurationMs, bounds.inFrom, bounds.inTo, sampleRate)
            }.onFailure { Log.w(TAG, "stem prefetch failed", it) }
        }
        discardPrime()
        val lead = (plan.out.loopStart * 1000).roundToLong() - player.currentPosition
        deckTiming.deadlineMs = DeckBudget.deadlineMs(nowMs(), player.isPlaying, (plan.out.loopStart * 1000).roundToLong(), player.currentPosition)
        Log.d(
            TAG,
            "loop prime ${outItem.mediaId}->${inItem.mediaId} at ${sampleRate}Hz, " +
                "${lead}ms before ${"%.2f".format(Locale.ROOT, plan.out.loopStart)}s",
        )
        startLoopBuild(key, plan, player, into, outItem, inItem, durationMs, nextDurationMs, sampleRate, armWhenReady = false)
    }

    /**
     * djmix17: counts a classic begin() guard exit (deduped per reason so
     * a 4 Hz retry does not spam the JSONL) and returns false.
     */
    private fun armDeath(reason: String): Boolean {
        val key = "$reason|${phase.name}"
        if (key != lastArmDeathKey) {
            lastArmDeathKey = key
            routeRefusal("mix.arm.death", reason, "accepted", "classic")
        }
        return false
    }

    /**
     * Attaches decks that were primed ahead of time, or keeps waiting on
     * the build already running. djmix17: returns true when the loop
     * armed or is still building (LOOP_ARMING holds), false when a guard
     * refuses — the caller then falls straight through to classic
     * begin() on the same tick instead of returning and starving it.
     */
    private fun beginLoop(
        out: ExoPlayer,
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        nextDurationMs: Long,
    ): Boolean {
        val into = standby()
        fun refuse(reason: String): Boolean {
            val pairKey = "${outItem.mediaId}->${inItem.mediaId}"
            val key = "beginLoop:$reason|$pairKey"
            if (key != lastFallbackLogKey) {
                lastFallbackLogKey = key
                routeRefusal("mix.loop.fallback", reason, "accepted", nativeRouteFields(plan)["technique"] as String, "classic",
                    coarseDecline = loopOutAnalysis?.let { out -> loopInAnalysis?.let { inn -> shouldDeclineToMix(out, inn) } })
            }
            loopSuppressedPair = pairKey
            loopSuppressedUntil = nowMs() + LOOP_SUPPRESSION_MS
            return false
        }
        if (out === into) return refuse("same-player")
        val nextIndex = out.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return refuse("no-next-index")
        val outPort = loopPortOf(out) ?: return refuse("no-out-port")
        val inPort = loopPortOf(into) ?: return refuse("no-in-port")
        endEase(clickless = false)
        MixDebug.reset()
        phase = Phase.LOOP_ARMING
        smartFadeActive = true
        handedOff = false
        outgoing = out
        incoming = into
        outgoingItemIndex = out.currentMediaItemIndex
        loopTarget = plan
        loopTargetStartMs = (plan.out.loopStart * 1000).roundToLong()
        holdAtEnd(out)
        val sampleRate = outPort.sampleRate
        val key = primeKey(plan, outItem, inItem, sampleRate)
        val ready = primedSession?.takeIf { primedKey == key }
        if (ready != null) {
            primedSession = null
            primedKey = null
            Log.d(TAG, "loop decks were ready ${loopTargetStartMs - out.currentPosition}ms ahead")
            armLoop(ready, out, into, nextIndex, outPort, inPort)
            return true
        }
        if (primeJob?.isActive == true && primedKey == key) {
            // djmix17-harden: a prime started while paused (or before any
            // deadline existed) leaves NO_DEADLINE (or 0L); without an
            // in-force wall deadline driveLoopArming's lateness math is
            // blind until the watchdog. Recompute if missing so a slow
            // hung primeJob is still judged against this loop's wall.
            if (deckTiming.deadlineMs == DeckBudget.NO_DEADLINE_MS || deckTiming.deadlineMs <= 0L) {
                deckTiming.deadlineMs = DeckBudget.deadlineMs(nowMs(), out.isPlaying, loopTargetStartMs, out.currentPosition)
            }
            Log.d(TAG, "loop decks still building ${loopTargetStartMs - out.currentPosition}ms ahead")
            return true
        }
        // djmix15: fresh build — drop any stale spans/deadline from a prior
        // pair before installing this build's wall deadline.
        deckTiming.clear()
        deckTiming.deadlineMs = DeckBudget.deadlineMs(nowMs(), out.isPlaying, loopTargetStartMs, out.currentPosition)
        startLoopBuild(key, plan, out, into, outItem, inItem, durationMs, nextDurationMs, sampleRate, armWhenReady = true)
        return true
    }

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
    ) {
        primedKey = key
        val generation = ++buildGeneration
        val inLevel = (loudnessGain(inItem.mediaId) / loudnessGain(outItem.mediaId))
            .takeIf { it.isFinite() && it > 0f } ?: 1f
        val highQuality = AppSettings.automixPerformanceMode.value != AutomixPerformanceMode.EFFICIENT
        val buildPair = "${outItem.mediaId}->${inItem.mediaId}"
        val script = loopScript?.takeIf {
            (loopPlanKey?.startsWith("$buildPair|") == true && loopPlanned == plan) || loopTarget == plan
        }
        val context = ensureOpportunity(outItem.mediaId, inItem.mediaId)
        val revision = reviseRoute(plan)
        val capturedPlan = plan.copy(out = plan.out.copy(anchorBeats = plan.out.anchorBeats?.toList()),
            inn = plan.inn.copy(anchorBeats = plan.inn.anchorBeats?.toList()))
        // Freeze the correct pair BEFORE suspension. CurveWindow owns mutable
        // arrays; a data-class copy alone would still share another pass's mask.
        val rememberedPair = loopOutItem?.mediaId == outItem.mediaId && loopInItem?.mediaId == inItem.mediaId
        val capturedOutAnalysis = snapshotLoopAnalysis(if (rememberedPair) loopOutAnalysis ?: analysisFor(outItem) else analysisFor(outItem))
        val capturedInAnalysis = snapshotLoopAnalysis(if (rememberedPair) loopInAnalysis ?: analysisFor(inItem) else analysisFor(inItem))
        val capturedSungOut = lyricSpans[outItem.mediaId]?.spans?.toList()
        val capturedSungIn = lyricSpans[inItem.mediaId]?.spans?.toList()
        val capturedCoarseDecline = shouldDeclineToMix(capturedOutAnalysis, capturedInAnalysis)
        val spliceRemainingAtLaunch = (plan.out.loopStart * 1000).roundToLong() - out.currentPosition
        val skipStemAtLaunch = shouldSkipStemAtLaunch(
            spliceRemainingAtLaunch,
            deckTiming.deadlineMs,
            nowMs(),
            wholeTrackBusy(),
            armWhenReady,
        )
        val job = scope.launch {
            val began = nowMs()
            var buildFailure: String? = null
            val session = withContext(Dispatchers.Default) {
                runCatching {
                    val stemFn = if (skipStemAtLaunch) null else separateTransitionWindow
                    val sepStem: ((LoopMix.Side, FloatArray, FloatArray, Int, Boolean) -> Pair<FloatArray, FloatArray>?)? = stemFn?.let { sep ->
                        { _: LoopMix.Side, left: FloatArray, right: FloatArray, sr: Int, keep: Boolean ->
                            if (shouldSkipStemNearSplice(Long.MAX_VALUE, deckTiming.deadlineMs, nowMs())) {
                                null
                            } else {
                                sep(left, right, sr, keep)
                            }
                        }
                    }
                    // vc74 (djmix32): single-pass vocal stem; instrumental derived
                    // locally as dry minus vocal, so no second Wiener pass per deck.
                    val vocalFn = if (skipStemAtLaunch) null else separateTransitionWindow
                    val sepVocal: ((LoopMix.Side, FloatArray, FloatArray, Int) -> Pair<FloatArray, FloatArray>?)? = vocalFn?.let { sep ->
                        { _: LoopMix.Side, left: FloatArray, right: FloatArray, sr: Int ->
                            if (shouldSkipStemNearSplice(Long.MAX_VALUE, deckTiming.deadlineMs, nowMs())) {
                                null
                            } else {
                                sep(left, right, sr, true)
                            }
                        }
                    }
                    // vc77: exact (voc, inst) pair from VocalTracker (no 48k
                    // round-trip) for the duck; falls back to sepVocal above
                    // inside LoopMixSession when this is null.
                    val pairFn = if (skipStemAtLaunch) null else separateTransitionPair
                    val sepVocalPair: ((LoopMix.Side, FloatArray, FloatArray, Int) -> Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>?)? = pairFn?.let { sep ->
                        { _: LoopMix.Side, left: FloatArray, right: FloatArray, sr: Int ->
                            if (shouldSkipStemNearSplice(Long.MAX_VALUE, deckTiming.deadlineMs, nowMs())) {
                                null
                            } else {
                                sep(left, right, sr)
                            }
                        }
                    }
                    // AUTOMIX-V2(orch): per-deck 4-stem providers (B), structure v2 maps (D), DJ level + album order (E).
                    fun v2Stems(id: String): com.music.bitchord.playback.smart.StemProvider? {
                        val provider = stemProviderFor?.invoke(id, skipStemAtLaunch) ?: return null
                        return com.music.bitchord.playback.smart.StemProvider { l, r, sr, start ->
                            if (shouldSkipStemNearSplice(Long.MAX_VALUE, deckTiming.deadlineMs, nowMs())) {
                                stemProviderFor.invoke(id, true)?.separate(l, r, sr, start)
                            } else {
                                provider.separate(l, r, sr, start)
                            }
                        }
                    }
                    val v2OutMap = PhraseMaps.get(outItem.mediaId)
                        ?: runCatching { SongMaps.fromAnalysis(outItem.mediaId, capturedOutAnalysis) }.getOrNull()
                    val v2InMap = PhraseMaps.get(inItem.mediaId)
                        ?: runCatching { SongMaps.fromAnalysis(inItem.mediaId, capturedInAnalysis) }.getOrNull()
                    val v2AlbumOrder = runCatching {
                        isConsecutiveAlbumTrack(
                            outItem.mediaMetadata.albumTitle?.toString().orEmpty(), "", outItem.mediaMetadata.trackNumber ?: 0,
                            inItem.mediaMetadata.albumTitle?.toString().orEmpty(), "", inItem.mediaMetadata.trackNumber ?: 0,
                        )
                    }.getOrDefault(false)
                    LoopMixSession.build(
                        capturedPlan, outItem.mediaId, inItem.mediaId, sampleRate, inLevel, highQuality, script,
                        separateStem = sepStem, separateVocalStem = sepVocal, separateVocalPair = sepVocalPair,
                        outAnalysis = capturedOutAnalysis, inAnalysis = capturedInAnalysis,
                        sungOut = capturedSungOut, sungIn = capturedSungIn,
                        onRefusal = { reason -> buildFailure = reason },
                        stemProvider = v2Stems(outItem.mediaId),
                        inStemProvider = v2Stems(inItem.mediaId),
                        outSongMap = v2OutMap,
                        inSongMap = v2InMap,
                        djLevel = AppSettings.automixDjLevel.value,
                        albumOrder = v2AlbumOrder,
                        decode = { side, from, to ->
                        // vc73: parallel OUT+IN decode — record side per-call, not via shared deckSide race.
                        val sideName = if (side == LoopMix.Side.OUT) "outgoing" else "incoming"
                        deckSide = sideName
                        val item = if (side == LoopMix.Side.OUT) outItem else inItem
                        val length = if (side == LoopMix.Side.OUT) durationMs else nextDurationMs
                        loopRegion(item, length, from, to, sampleRate).also { region ->
                            if (region == null) buildFailure = "$sideName decode-null"
                        }
                    })
                }.onFailure {
                    Log.w(TAG, "loop build failed", it)
                    buildFailure = "session error:${it.javaClass.simpleName}"
                }.getOrNull()
            }
            if (generation != buildGeneration) {
                session?.close()
                return@launch
            }
            deckTiming.endOpen()
            if (primedKey != key) {
                session?.close()
                return@launch
            }
            if (context != opportunity || terminalRecorded) {
                session?.close()
                return@launch
            }
            val steps = deckTiming.snapshot(nowMs())
                .joinToString { "${it.step} ${it.endedMs - it.startedMs}ms" }
            if (session == null) {
                routeRefusal("mix.plan.rejected", buildFailure ?: "session build-null", "prepared",
                    nativeRouteFields(plan, coarseDecline = capturedCoarseDecline)["technique"] as String,
                    context = context, revision = revision, generation = generation,
                    coarseDecline = capturedCoarseDecline)
                Log.w(TAG, "loop build produced no decks ($steps)")
                primeRetryAt = nowMs() + LOOP_PRIME_RETRY_MS
                // djmix16: a null build while already LOOP_ARMING yields
                // to classic immediately (loopFallBack), not only 400 ms
                // before the splice. The old gate kept phase LOOP_ARMING
                // retrying a build that can never succeed (cold next
                // track: no complete rendition) for the whole window,
                // starving classic begin(). Retrying needs a file that
                // only the fallback standby prepare will ever fetch.
                // IDLE primes keep the 2 s retry (a retry may find the
                // file once the DirectSourceGate window opens).
                if (phase == Phase.LOOP_ARMING && outgoing === out) {
                    val now = nowMs()
                    val deadline = deckTiming.deadlineMs
                    val reason = if (deadline != DeckBudget.NO_DEADLINE_MS && deadline > 0L && now <= deadline) {
                        buildFailure ?: "${deckSide.ifEmpty { "deck" }} build-null"
                    } else {
                        lateDeckReason()
                    }
                    loopFallBack(reason)
                }
                return@launch
            }
            val builtMs = nowMs() - began
            blendArmLeadMs = com.music.bitchord.playback.smart.PrepLead.next(blendArmLeadMs, builtMs)
            Log.d(TAG, "loop decks built in ${builtMs}ms ($steps), next arm ${blendArmLeadMs}ms")
            when {
                phase == Phase.LOOP_ARMING && outgoing === out && armWhenReady -> {
                    val nextIndex = out.nextMediaItemIndex
                    val outPort = loopPortOf(out)
                    val inPort = loopPortOf(into)
                    if (nextIndex == C.INDEX_UNSET || outPort == null || inPort == null) {
                        session.close()
                        shiftLoopOrFallback(out)
                    } else {
                        primedKey = null
                        nativeBinding = NativeBinding(context, revision, generation, capturedCoarseDecline)
                        armLoop(session, out, into, nextIndex, outPort, inPort)
                    }
                }
                phase == Phase.LOOP_ARMING && outgoing === out -> {
                    // djmix17-harden: a prime launched with armWhenReady=
                    // false that lands while beginLoop already holds
                    // LOOP_ARMING must arm (or shift) NOW, not stash
                    // primedSession for a considerSmartTransition that
                    // never runs again (tick goes to driveLoopArming).
                    // Mirrors the armWhenReady branch: port-less builds
                    // shift/fall back instead of sitting stashed.
                    val nextIndex = out.nextMediaItemIndex
                    val outPort = loopPortOf(out)
                    val inPort = loopPortOf(into)
                    if (nextIndex == C.INDEX_UNSET || outPort == null || inPort == null) {
                        session.close()
                        shiftLoopOrFallback(out)
                    } else {
                        primedKey = null
                        nativeBinding = NativeBinding(context, revision, generation, capturedCoarseDecline)
                        armLoop(session, out, into, nextIndex, outPort, inPort)
                    }
                }
                phase == Phase.IDLE -> {
                    primedSession = session
                    nativeBinding = NativeBinding(context, revision, generation, capturedCoarseDecline)
                    preparedRoute(nativeSessionRouteFields(session, capturedCoarseDecline), context, revision, generation)
                }
                else -> session.close()
            }
        }
        primeJob = job
        if (armWhenReady) loopBuildJob = job
    }

    private fun rememberLoopPair(
        outItem: MediaItem,
        inItem: MediaItem,
        outAnalysis: TrackAnalysis,
        inAnalysis: TrackAnalysis,
        durationMs: Long,
        nextDurationMs: Long,
    ) {
        loopOutItem = outItem
        loopInItem = inItem
        plannedNextId = inItem.mediaId
        loopOutAnalysis = outAnalysis
        loopInAnalysis = inAnalysis
        loopSungOut = lyricSpans[outItem.mediaId]?.spans
        loopSungIn = lyricSpans[inItem.mediaId]?.spans
        loopOutDurationMs = durationMs
        loopInDurationMs = nextDurationMs
    }

    private fun snapshotLoopAnalysis(analysis: TrackAnalysis): TrackAnalysis {
        fun windows(values: List<com.music.bitchord.playback.smart.CurveWindow>) = values.map {
            com.music.bitchord.playback.smart.CurveWindow(it.startTime, it.frameRate, it.values.copyOf())
        }
        fun beats(value: com.music.bitchord.playback.smart.BeatSide?) = value?.copy(
            beats = value.beats.toList(), downbeats = value.downbeats.toList(),
        )
        return analysis.copy(
            downbeats = analysis.downbeats.toList(), phraseBoundaries = analysis.phraseBoundaries.toList(),
            mixInCandidates = analysis.mixInCandidates.toList(), mixOutCandidates = analysis.mixOutCandidates.toList(),
            energyCurve = analysis.energyCurve.toList(), lowEnergyCurve = analysis.lowEnergyCurve.toList(),
            vocalActivityMask = analysis.vocalActivityMask.toList(),
            headBeats = beats(analysis.headBeats), tailBeats = beats(analysis.tailBeats),
            vocalWindows = windows(analysis.vocalWindows), levelWindows = windows(analysis.levelWindows),
            instrumentalWindows = windows(analysis.instrumentalWindows),
        )
    }

    private fun primeKey(plan: LoopMix.Plan, outItem: MediaItem, inItem: MediaItem, sampleRate: Int) =
        "${plan.rung}|${outItem.mediaId}->${inItem.mediaId}|${plan.out.loopStart}|${plan.out.loopEnd}|${plan.out.bars}|" +
            "${plan.inn.loopStart}|${plan.inn.loopEnd}|${plan.inn.bars}|$sampleRate"

    /** Called from the decode thread at the start of each stream/decode/resample slice. */
    fun markDeckStep(step: String) {
        deckTiming.begin("$deckSide $step")
    }

    private fun discardPrime() {
        buildGeneration++
        primeJob?.cancel()
        primeJob = null
        loopBuildJob = null
        primedKey = null
        primeRetryAt = 0L
        primedSession?.close()
        primedSession = null
        // djmix15: a stale deckTiming from a prior track/build can never be
        // this build's deadlines — reset on every prime/scrub/discard so
        // lateStepReason cannot subtract a dead wall epoch.
        deckTiming.clear()
    }

    /**
     * The planned bar was reached and the incoming side still was not ready.
     * Moves the loop to the next bar [LoopMix.shiftToNextBar] accepts, and
     * only then falls back to the blend.
     */
    private fun shiftLoopOrFallback(out: ExoPlayer) {
        if (phase != Phase.LOOP_ARMING) return
        val plan = loopTarget ?: return loopFallBack(lateDeckReason())
        val outAnalysis = loopOutAnalysis ?: return loopFallBack(lateDeckReason())
        val inAnalysis = loopInAnalysis ?: return loopFallBack(lateDeckReason())
        val outItem = loopOutItem ?: return loopFallBack(lateDeckReason())
        val inItem = loopInItem ?: return loopFallBack(lateDeckReason())
        val why = StringBuilder()
        val guardSec = LOOP_COMMIT_MS / 1000.0
        val positionSec = out.currentPosition / 1000.0
        var candidate = LoopMix.shiftToNextBar(
            plan, outAnalysis, loopOutDurationMs / 1000.0, inAnalysis, loopInDurationMs / 1000.0,
            loopSungOut, loopSungIn, why = why,
        )
        while (candidate != null && candidate.out.loopStart < positionSec - PhraseConfig.PREP_TOLERANCE_MS / 1000.0) {
            val further = LoopMix.shiftToNextBar(
                candidate, outAnalysis, loopOutDurationMs / 1000.0, inAnalysis, loopInDurationMs / 1000.0,
                loopSungOut, loopSungIn, why = why,
            )
            if (further == null) {
                candidate = null
                break
            }
            candidate = further
        }
        // vc77 single retry: shiftToNextBar already walked forward from
        // missed.out.loopStart + epsilon; for straight phrase blends (M1/M6,
        // !outLoops && !inLoops, not an instrumental crown-loop) one relaxed
        // PhrasePlanner.select at the live playhead can still rescue a later
        // bar. Exactly one attempt, assigning loopScript from the
        // compilePhrase candidate only on acceptance;
        // still null falls through to loopFallBack immediately (no loop).
        if (candidate == null && !plan.outLoops && !plan.inLoops) {
            val retryOutDurMs = out.duration.takeIf { it != C.TIME_UNSET && it > 1_000L }
                ?: loopOutDurationMs.takeIf { it > 0L } ?: 0L
            val outMapRetry = PhraseMaps.get(outItem.mediaId)
                ?: runCatching { SongMaps.fromAnalysis(outItem.mediaId, outAnalysis) }.getOrNull()
            val inMapRetry = PhraseMaps.get(inItem.mediaId)
                ?: runCatching { SongMaps.fromAnalysis(inItem.mediaId, inAnalysis) }.getOrNull()
            val choiceRetry = runCatching {
                PhrasePlanner.select(
                    outMapRetry, inMapRetry, regionalCamelotSteps(outAnalysis, inAnalysis),
                    outAnalysis.bpm, inAnalysis.bpm,
                    earliestExitSec = positionSec + 0.30,
                    playableOutDurationSec = retryOutDurMs / 1000.0,
                    outAnalysis = outAnalysis,
                    inAnalysis = inAnalysis,
                )
            }.getOrNull()
            val retryCandidate = if (choiceRetry != null) {
                runCatching { compilePhrase(choiceRetry, outAnalysis, inAnalysis, outItem.mediaId, inItem.mediaId) }.getOrNull()
            } else null
            if (retryCandidate != null) {
                val retryPlan = retryCandidate.plan
                val retryScript = retryCandidate.script
                val outDurSec = loopOutDurationMs / 1000.0
                if (retryPlan.out.loopStart >= positionSec + 0.15 &&
                    (outDurSec <= 0.0 || retryPlan.out.loopStart <= outDurSec)
                ) {
                    candidate = retryPlan
                    loopScript = retryScript
                    commitPhrase(retryCandidate)
                } else {
                    // Rejected: neither loopScript nor phraseDetail is
                    // committed for this retry.
                    loopScript = null
                }
            }
        }
        val now = nowMs()
        val deadline = deckTiming.deadlineMs
        val notLate = deadline != DeckBudget.NO_DEADLINE_MS && deadline > 0L && now <= deadline
        val reason = if (notLate) "shift-bar-null" else lateDeckReason()
        candidate?.let {
            retainedPlanRefusal(it, outAnalysis, inAnalysis, loopSungOut, loopSungIn)?.let { refusal ->
                loopFallBack(refusal)
                return
            }
        }
        if (candidate == null) {
            Log.d(TAG, "loop has no later bar ($why)")
            loopFallBack(reason)
            return
        }
        Log.d(
            TAG,
            "loop start ${"%.2f".format(Locale.ROOT, plan.out.loopStart)}s missed ($reason); " +
                "shifting to ${"%.2f".format(Locale.ROOT, candidate.out.loopStart)}s",
        )
        loopSession?.let { session ->
            session.outPort?.disarm()
            session.inPort?.disarm()
            session.renderer.let(::closeLater)
        }
        loopSession = null
        primeJob?.cancel()
        primeJob = null
        loopBuildJob = null
        primedSession?.close()
        primedSession = null
        primedKey = null
        loopPlanned = candidate
        loopTarget = candidate
        loopTargetStartMs = (candidate.out.loopStart * 1000).roundToLong()
        if (loopOutDurationMs > 0L) {
            AppSettings.smartTransitionWindow.value = TransitionWindow(
                start = (loopTargetStartMs.toFloat() / loopOutDurationMs).coerceIn(0f, 1f),
                end = 1f,
            )
        }
        val into = incoming ?: standby()
        val sampleRate = loopPortOf(out)?.sampleRate ?: 0
        if (sampleRate <= 0) {
            loopFallBack(reason)
            return
        }
        deckTiming.clear()
          deckTiming.deadlineMs = DeckBudget.deadlineMs(nowMs(), out.isPlaying, loopTargetStartMs, out.currentPosition)
        // The reason stays on the refusal until the shifted mix actually arms,
        // so a later fallback still says what was late the first time if this
        // attempt records nothing of its own.
        loopRefusal = reason
        startLoopBuild(
            primeKey(candidate, outItem, inItem, sampleRate),
            candidate, out, into, outItem, inItem, loopOutDurationMs, loopInDurationMs, sampleRate,
            armWhenReady = true,
        )
    }

    private fun lateDeckReason(): String {
        val now = nowMs()
        return LoopMix.lateStepReason(deckTiming.snapshot(now), now, deckTiming.deadlineMs)
    }

    /** The decks are built: the incoming player loads its song, and both sinks are armed. */
    private fun armLoop(
        session: LoopMixSession,
        out: ExoPlayer,
        into: ExoPlayer,
        nextIndex: Int,
        outPort: LoopMixPort,
        inPort: LoopMixPort,
    ) {
        // This is a new native execution attempt. Clear attempt evidence here,
        // never in begin(), which may be the classic fallback for the same
        // opportunity after this attempt's cleanup.
        initiatingExitReason = null
        capturedTakeoverSpliceBufferStatus = null
        capturedLoopTookOver = null
        val outItem = loopOutItem
        val inItem = loopInItem
        if (outItem != null && inItem != null) {
            retainedPlanRefusal(session.plan, analysisFor(outItem), analysisFor(inItem),
                lyricSpans[outItem.mediaId]?.spans, lyricSpans[inItem.mediaId]?.spans)?.let { refusal ->
                session.close()
                loopFallBack(refusal)
                return
            }
        }
        val items = (0 until out.mediaItemCount).map { out.getMediaItemAt(it) }
        queuedItemCount = items.size
        into.skipSilenceEnabled = false
        into.repeatMode = out.repeatMode
        into.shuffleModeEnabled = out.shuffleModeEnabled
        into.setPlaybackSpeed(deckSpeed(AppSettings.playbackSpeed.value, 1.0))
        // vc77: the spare filter must already be neutral before either gain
        // is raised. The incoming player is still the unspliced standby
        // before prepare(), and loop audio bypasses the classic filters, so
        // opening the spare here is inaudible on the loop path; the gated
        // open keeps Seam A while both decks are still at their old gains.
        filters.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        filters.outgoingMidDb(0f)
        openFiltersWhenSilent()
        // Silent through its own sink until the handover, so the player's
        // volume stays where the listener's ear expects it.
        into.volume = 1f
        out.volume = 1f
        onBlendHeadroom(1f)
        into.setMediaItems(items, nextIndex, (session.inStartTrack * 1000).roundToLong())
        items.getOrNull(nextIndex)?.let { item ->
            val after = into.nextMediaItemIndex
            val afterId = if (after == C.INDEX_UNSET) null else items.getOrNull(after)?.mediaId
            onArmIncoming(item, afterId)
        }
        into.playWhenReady = false
        inPort.armIncoming(LoopMixPort.InArm((session.handoverTrack * 1_000_000).roundToLong()))
        deckTiming.begin("incoming prepare")
        into.prepare()
        session.attach(outPort, inPort)
        loopSession = session
        nativeBinding?.let { binding ->
            preparedRoute(nativeSessionRouteFields(session, binding.coarseDecline),
                binding.context, binding.revision, binding.generation)
        }
        loopIncomingStarted = false
        deferredHandoffLogged = false
        lastLoopPositionMs = -1L
        lastLoopBeat = -1
        Log.d(
            TAG,
            "loop armed: takeover at ${session.takeoverUs}us (stream offset ${outPort.streamOffsetUs}us), " +
                "incoming from ${"%.3f".format(Locale.ROOT, session.inStartTrack)}s, handover at " +
                "${"%.3f".format(Locale.ROOT, session.handoverTrack)}s, mix ${"%.1f".format(Locale.ROOT, session.clock.elementAt)}s",
        )
    }

    /** Waits for the outgoing sink to splice over to the renderer. */
    private fun driveLoopArming() {
        if (rejectLiveCrown()) return
        val out = outgoing ?: return loopBail("no outgoing player")
        if (!out.playWhenReady) {
            // djmix22: same proof as driveLoopMix (pre-takeover pause watch).
            AutomixTelemetry.event(
                "mix.pause.detected",
                mapOf(
                    "site" to "driveLoopArming",
                    "playWhenReady" to out.playWhenReady,
                    "playbackState" to out.playbackState,
                    "isPlaying" to out.isPlaying,
                    "volume" to out.volume,
                    "changeReason" to lastPlayWhenReadyChangeReason,
                    "handedOff" to handedOff,
                    "tookOver" to (loopSession?.outPort?.tookOver == true),
                    "positionMs" to out.currentPosition,
                    "loopIncomingStarted" to loopIncomingStarted,
                    "inStartAtMs" to ((loopSession?.inStartAt?.times(1000))?.roundToLong() ?: -1L),
                    "handoffAtMs" to ((loopSession?.clock?.handoffAt?.times(1000))?.roundToLong() ?: -1L),
                ),
            )
            return loopBail("paused before the loop")
        }
        val session = loopSession
        if (session == null) {
            // djmix17-harden: a build that finished while LOOP_ARMING but
            // stashed primedSession (port race) would otherwise strand:
            // considerSmartTransition never runs again. Pick it up here
            // before any shift/fallback verdict.
            if (primedSession != null && loopTarget != null && loopOutItem != null && loopInItem != null && incoming != null) {
                val plan0 = loopTarget!!
                val outItem0 = loopOutItem!!
                val inItem0 = loopInItem!!
                val into0 = incoming!!
                val sr0 = loopPortOf(out)?.sampleRate ?: 0
                if (sr0 > 0 && primedKey == primeKey(plan0, outItem0, inItem0, sr0)) {
                    val ready0 = primedSession!!
                    val outPort0 = loopPortOf(out)
                    val inPort0 = loopPortOf(into0)
                    val nextIdx0 = out.nextMediaItemIndex
                    if (outPort0 != null && inPort0 != null && nextIdx0 != C.INDEX_UNSET) {
                        primedSession = null
                        primedKey = null
                        armLoop(ready0, out, into0, nextIdx0, outPort0, inPort0)
                        return
                    }
                }
            }
            // A build that is still running gets to finish: cancelling it on
            // the bar would throw away the decode that is about to land. Once
            // it has stopped and this bar's splice can no longer be armed, the
            // loop moves to the next bar instead of being dropped.
            // djmix14: if still building 150 ms before the splice, do not
            // stall at 0s — abort to catch-up classic this tick.
            val building = primeJob?.isActive == true
            if (building && out.currentPosition >= loopTargetStartMs - LOOP_COMMIT_MS) {
                val key = loopPlanKey ?: "loop-arming"
                loopSuppressedPair = key.substringBefore("|")
                loopSuppressedUntil = nowMs() + LOOP_SUPPRESSION_MS
                loopFallBack("build-missed-splice")
                return
            }
            if (!building && out.currentPosition >= loopTargetStartMs - LOOP_COMMIT_MS) {
                shiftLoopOrFallback(out)
                return
            }
            if (!building && nowMs() >= primeRetryAt) {
                val plan = loopTarget
                val outItem = loopOutItem
                val inItem = loopInItem
                val into = incoming
                val sampleRate = loopPortOf(out)?.sampleRate ?: 0
                if (plan != null && outItem != null && inItem != null && into != null && sampleRate > 0) {
                    primeRetryAt = nowMs() + LOOP_PRIME_RETRY_MS
                    startLoopBuild(
                        primeKey(plan, outItem, inItem, sampleRate),
                        plan, out, into, outItem, inItem, loopOutDurationMs, loopInDurationMs, sampleRate,
                        armWhenReady = true,
                    )
                }
            }
            return
        }
        if (incoming?.playbackState == Player.STATE_READY) deckTiming.endOpenIf("incoming prepare")
        if (session.outPort?.interrupted == true) return loopBail("the outgoing sink was flushed", settle = false)
        if (session.outPort?.tookOver == true) {
            phase = Phase.LOOP_RUNNING
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixInProgress.value = true
            val binding = nativeBinding
            executedRoute = nativeSessionRouteFields(session, binding?.coarseDecline)
            mixEvent(
                "mix.fire",
                requireNotNull(executedRoute) + mapOf(
                    "stage" to "executed", "executionMarker" to "native-takeover-observed",
                    "executionScope" to "takeover-only-not-delegate-buffer-or-listening-proof",
                    "planRevision" to binding?.revision, "buildGeneration" to binding?.generation,
                    "mode" to "loop",
                    "plannedStartMs" to loopTargetStartMs,
                    "actualStartMs" to out.currentPosition,
                    "deltaMs" to (out.currentPosition - loopTargetStartMs),
                    "prerollSec" to 1.0,
                    "spliceTargetMs" to loopTargetStartMs,
                    "trackIndex" to outgoingItemIndex,
                ), binding?.context ?: opportunity,
            )
            scope.launch(Dispatchers.Default) {
                session.outPort?.spliceLag()?.let { (lag, score) ->
                    Log.d(
                        TAG,
                        "loop splice: deck ${"%+.2f".format(Locale.ROOT, lag * 1000.0 / session.sampleRate)}ms " +
                            "against its song (r=${"%.3f".format(Locale.ROOT, score)})",
                    )
                }
            }
            return
        }
        val m = session.mixSeconds(System.nanoTime(), out.isPlaying) ?: return
        if (m > 0.45) {
            // djmix16: m is MEDIA lateness (presented - loopStart, in
            // seconds) — it must never be subtracted from a wall clock
            // (now - lateBy mixed domains and reported 116245 ms over
            // 41 ms after arming, growing 1:1 with wall). Record the
            // splice step in wall time only (now -> now against the
            // wall deadline); the shift decision stays a media decision.
            val now = nowMs()
            deckTiming.addClosed("outgoing splice", now, now, deckTiming.deadlineMs)
            shiftLoopOrFallback(out)
        }
    }

    /** The mix is running: starts the incoming player, aligns it, hands the session over, and ends. */
    private fun driveLoopMix() {
        if (rejectLiveCrown()) return
        val session = loopSession ?: return finish()
        val out = outgoing ?: return loopBail("no outgoing player")
        val into = incoming ?: return loopBail("no incoming player")
        val sessionPlayer = if (handedOff) into else out
        if (session.outPort?.interrupted == true || (loopIncomingStarted && session.inPort?.interrupted == true)) {
            return loopBail("a sink was flushed", settle = false)
        }
        if (!sessionPlayer.playWhenReady) {
            // djmix22: prove a paused session player from telemetry (focus-loss
            // swap pause would otherwise only show as a bare loopBail downstream).
            AutomixTelemetry.event(
                "mix.pause.detected",
                mapOf(
                    "site" to "driveLoopMix",
                    "playWhenReady" to sessionPlayer.playWhenReady,
                    "playbackState" to sessionPlayer.playbackState,
                    "isPlaying" to sessionPlayer.isPlaying,
                    "volume" to sessionPlayer.volume,
                    "changeReason" to lastPlayWhenReadyChangeReason,
                    "handedOff" to handedOff,
                    "tookOver" to (session.outPort?.tookOver == true),
                    "positionMs" to sessionPlayer.currentPosition,
                    "loopIncomingStarted" to loopIncomingStarted,
                    "inStartAtMs" to (session.inStartAt * 1000).roundToLong(),
                    "handoffAtMs" to (session.clock.handoffAt * 1000).roundToLong(),
                ),
            )
            return loopBail("paused", settle = true)
        }
        if (loopCleanupContendedSession === session) return finishLoop()
        val now = System.nanoTime()
        val m = session.mixSeconds(now, out.isPlaying) ?: return
        if (!loopIncomingStarted && m >= session.inStartAt) {
            into.playWhenReady = true
            loopIncomingStarted = true
            // The incoming decoder starts here, while the renderer is already
            // the thing being heard. Hold analysis for this ramp-up.
            yieldAnalysis()
        }
        if (loopIncomingStarted && loopAlignJob == null && into.isPlaying &&
            session.inPort?.positionUs != C.TIME_UNSET && m >= session.inStartAt + LOOP_ALIGN_SETTLE_SECONDS
        ) {
            startLoopAlignment(session)
        }
        if (!handedOff && m >= session.clock.handoffAt) {
            if (canAdoptSessionPlayer(loopIncomingStarted)) {
                handOff(out, into)
            } else if (!deferredHandoffLogged) {
                // djmix23: hold geometry scheduled the decoder start after
                // this handoff (inStartAt > handoffAt). Adopting now would make
                // the session a never-started, paused player. The renderer
                // covers the wait, so hold the adoption until the start.
                deferredHandoffLogged = true
                AutomixTelemetry.event(
                    "mix.handoff.deferred",
                    mapOf(
                        "mMs" to (m * 1000).roundToLong(),
                        "inStartAtMs" to (session.inStartAt * 1000).roundToLong(),
                        "handoffAtMs" to (session.clock.handoffAt * 1000).roundToLong(),
                    ),
                )
            }
        }
        publishLoopBeat(session, m, now)
        announceLoopPosition()
        if (m >= session.clock.elementAt + LOOP_END_MARGIN_SECONDS) finishLoop()
    }

    /** No callback work: contradictory local evidence is checked on the ticker. */
    private fun rejectLiveCrown(): Boolean {
        val plan = loopSession?.plan ?: loopTarget ?: return false
        if (!isCrown(plan)) return false
        val outItem = loopOutItem ?: return false
        val inItem = loopInItem ?: return false
        val reason = retainedPlanRefusal(plan, analysisFor(outItem), analysisFor(inItem),
            lyricSpans[outItem.mediaId]?.spans, lyricSpans[inItem.mediaId]?.spans) ?: return false
        if (loopSession?.outPort?.tookOver == true) loopBail(reason, settle = false) else loopFallBack(reason)
        return true
    }

    /**
     * Puts the incoming player exactly in step with the deck it takes over
     * from, while it is still silent: measures the offset on both sinks' own
     * clocks, and has its sink drop frames or insert silence to cancel it.
     */
    private fun startLoopAlignment(session: LoopMixSession) {
        val job = scope.launch {
            try {
                repeat(LOOP_ALIGN_ROUNDS) { round ->
                    val samples = ArrayList<Double>(LOOP_ALIGN_SAMPLES)
                    repeat(LOOP_ALIGN_SAMPLES) {
                        session.offsetNow(System.nanoTime())?.let(samples::add)
                        delay(LOOP_ALIGN_SAMPLE_MS)
                    }
                    if (samples.size < LOOP_ALIGN_SAMPLES / 2) return@launch
                    samples.sort()
                    val error = samples[samples.size / 2]
                    val spread = samples.last() - samples.first()
                    Log.d(
                        TAG,
                        "loop align $round: incoming ${"%+.2f".format(Locale.ROOT, error * 1000)}ms against the deck " +
                            "(spread ${"%.2f".format(Locale.ROOT, spread * 1000)}ms)",
                    )
                    session.recordAligned(error)
                    if (LoopMixSession.aligned(error)) return@launch
                    // A shift lands where the sink is processing, its lead ahead of
                    // what is heard, and runs for as long as it is: both have to
                    // have played out before the next reading means anything.
                    val port = session.inPort
                    val lead = if (port != null && port.processedUs != C.TIME_UNSET && port.positionUs != C.TIME_UNSET) {
                        ((port.processedUs - port.positionUs).coerceAtLeast(0L)) / 1e6
                    } else {
                        1.0
                    }
                    session.correct(error)
                    delay(((lead + 0.15) * 1000).roundToLong().coerceIn(250L, 2500L))
                }
                session.offsetNow(System.nanoTime())?.let {
                    session.recordAligned(it)
                    Log.d(TAG, "loop align: left at ${"%+.2f".format(Locale.ROOT, it * 1000)}ms")
                }
            } finally {
                if (loopAlignJob != null && loopAlignJob === coroutineContext[Job]) loopAlignJob = null
            }
        }
        loopAlignJob = job
    }

    /** The scrubber's beat pulse, on the mix's own beats (they move through the ramp). */
    private fun publishLoopBeat(session: LoopMixSession, m: Double, now: Long) {
        if (m < 0) return
        val (beat, _) = session.clock.beatAt(m)
        val times = session.clock.beatTimes
        if (beat == lastLoopBeat || beat !in 0 until times.size - 1) return
        lastLoopBeat = beat
        val beatMs = ((times[beat + 1] - times[beat]) * 1000).toFloat()
        val anchor = now - ((m - times[beat]) * 1e9).roundToLong()
        AppSettings.smartMixBlend.value = MixBlend(beatMs = beatMs, beatAnchorNanos = anchor, playing = true)
    }

    /**
     * Re-anchors the session's controllers on the mix's playhead: at every loop
     * wrap (a deliberate jump back) and every couple of seconds through the
     * ramp, where the song runs at the ramp's tempo rather than at 1x.
     */
    private fun announceLoopPosition() {
        val position = loopPositionMs() ?: return
        val now = nowMs()
        val wrapped = lastLoopPositionMs >= 0 && position < lastLoopPositionMs - LOOP_WRAP_MS
        lastLoopPositionMs = position
        if (wrapped || now - lastLoopAnnounceAt >= LOOP_ANNOUNCE_MS) {
            lastLoopAnnounceAt = now
            onMixPositionJump(wrapped)
        }
    }

    /**
     * Where the session's song is during a loop mix, from the mix's own clock:
     * looping, following the ramp, and on its own after the release. Null
     * outside one, where the player's own position is the truth.
     */
    fun loopPositionMs(): Long? {
        if (phase != Phase.LOOP_RUNNING) return null
        val session = loopSession ?: return null
        val playing = outgoing?.isPlaying ?: return null
        val m = session.mixSeconds(System.nanoTime(), playing) ?: return null
        if (m < 0) return null
        val side = if (handedOff) LoopMix.Side.IN else LoopMix.Side.OUT
        val position = LoopMix.positionAt(session.clock, side, m) ?: return null
        return (position * 1000).roundToLong()
    }

    /** The mix played out: the incoming player is the song, the outgoing one is retired. */
    private fun finishLoop() {
        val session = loopSession ?: return finish()
        captureLoopExitObservation(session)
        val out = outgoing
        val into = incoming
        val wasIncomingStarted = loopIncomingStarted
        val nowNanos = System.nanoTime()
        val outReading = session.outPort?.readPresentedPosition(nowNanos, out?.isPlaying == true)
        val inReading = if (wasIncomingStarted) session.inPort?.readPresentedPosition(nowNanos, into?.isPlaying == true) else null
        val contended = outReading?.contended == true || inReading?.contended == true
        if (contended && (if (handedOff) into?.playWhenReady else out?.playWhenReady) == true &&
            session.outPort?.interrupted != true && session.inPort?.interrupted != true
        ) {
            if (loopCleanupContendedSession !== session) {
                loopCleanupContendedSession = session
                loopCleanupContendedSinceNanos = nowNanos
            }
            // Five ordinary 20 ms LOOP ticks at most; keep ports and decoder
            // untouched while a publication overlaps the read. Never seek on it.
            if (nowNanos - loopCleanupContendedSinceNanos < 100_000_000L) return
            loopBail("loop-cleanup-clock-contention-timeout", settle = false, preserveSeek = true, deferDisarm = true)
            return
        }
        loopCleanupContendedSession = null
        loopCleanupContendedSinceNanos = 0L
        val m = outReading?.seconds?.let { it - session.plan.out.loopStart }
        val incomingNow = inReading?.seconds
        val violations = session.renderer.exitGateViolations()
        // finish() can route here before the scheduled decoder splice. Such
        // termination is abnormal, never evidence of a healthy handover.
        val abnormal = when {
            out == null || into == null -> "loop-cleanup-missing-player"
            session.outPort?.interrupted == true || session.inPort?.interrupted == true -> "loop-cleanup-interrupted"
            !(if (handedOff) into.playWhenReady else out.playWhenReady) -> "loop-cleanup-paused"
            m == null -> "loop-cleanup-clock-unavailable"
            m < session.clock.elementAt -> "loop-cleanup-before-splice"
            violations != 0 -> "loop-cleanup-exit-gate"
            wasIncomingStarted && (!out.isPlaying || !into.isPlaying ||
                into.playbackState != Player.STATE_READY ||
                incomingNow == null) -> "loop-cleanup-incoming-unavailable"
            !wasIncomingStarted && (!out.playWhenReady || !out.isPlaying) -> "loop-cleanup-outgoing-unavailable"
            else -> null
        }
        if (abnormal != null) {
            // Nulls loopSession before finish() and uses its existing bounded
            // retire tail, so there is no finish -> finishLoop recursion or
            // speculative seek over a user target.
            loopBail(abnormal, settle = false, preserveSeek = true, deferDisarm = true)
            return
        }
        lastCompleteReason = "loop-complete"
        // djmix15: loop BPMs live in session.plan, not in
        // outgoingAnalysis/incomingAnalysis (blank 0.0). Record them before
        // finish() so mix.complete reports real tempos even if the retire
        // guard diverts to a TAIL and finish() is re-entered after
        // loopSession is gone. Scalars only.
        val plan = session.plan
        if (outgoingAnalysis.bpm <= 0) outgoingAnalysis = outgoingAnalysis.copy(bpm = plan.out.bpm)
        if (incomingAnalysis.bpm <= 0) incomingAnalysis = incomingAnalysis.copy(bpm = plan.inn.bpm)
        tailDurationMs = RetireGuard.loopTailMs(plan.exitTailSeconds)
        Log.d(
            TAG,
            "loop mix cleanup: incoming ${"%+.2f".format(Locale.ROOT, session.alignedErrorMs)}ms " +
                "against the deck at the handover",
        )
        loopScript?.let { script ->
            val counts = MixDebug.counts()
            Log.i("AutomixScript", TransitionMoves.scriptJson(script, counts[0], counts[1], counts[2], slides = phraseLock.slides, lockedAt = phraseLock.lockedAtSec))
            Log.i("BitChordPhrase", TransitionMoves.phraseLine(script.choice))
        }
        // heard(IN) is a generated block endpoint, frozen after its fade.
        // Diagnostic only: position decisions use both presented clocks at now.
        val expectedHandoverMs = (session.handoverTrack * 1000).roundToLong()
        val heardInSecRaw = runCatching { session.renderer.heard(LoopMixRenderer.IN) }.getOrNull()
        val heardValidSec = heardInSecRaw?.takeIf { it.isFinite() && it != 0.0 &&
            it >= session.plan.inn.loopStart - 0.05 }
        val targetSec = session.handoverTrack + (m!! - session.clock.elementAt)
        val targetMs = (targetSec * 1000).roundToLong()
        handoverExpectedMs = expectedHandoverMs
        handoverHeardMs = heardValidSec?.let { (it * 1000).roundToLong() } ?: -1L
        handoverTargetMs = targetMs
        val driftMs = incomingNow?.let { ((targetSec - it) * 1000).roundToLong() }
        if (!wasIncomingStarted || (driftMs != null && abs(driftMs) > 150L)) {
            val reason = if (wasIncomingStarted) "loop-handover-drift-recovery" else "loop-handover-unstarted-recovery"
            lastCompleteReason = reason
            AutomixTelemetry.event(
                "mix.seek",
                mapOf("phase" to phase.name, "reason" to reason,
                    "expectedHandoverMs" to expectedHandoverMs,
                    "actualHeardMs" to handoverHeardMs, "targetMs" to targetMs,
                    "driftMs" to driftMs),
            )
            internalHandoverSeek = true
            suppressScrubUntilMs = nowMs() + 1_200L
            try {
                // Explicitly INCOMING, including a never-started buffering
                // decoder. Seek before play/adopt; READY is not required.
                into!!.seekTo(targetMs.coerceAtLeast(0L))
            } finally {
                internalHandoverSeek = false
            }
        }
        if (!wasIncomingStarted) {
            loopIncomingStarted = true
            into!!.playWhenReady = true
        }
        if (!handedOff) handOff(out!!, into!!)
        if (!handedOff) {
            loopBail("loop-cleanup-adoption-refused", settle = false, preserveSeek = true, deferDisarm = true)
            return
        }
        // Record phase while ports are still armed. Missing phase is unknown,
        // never a zero-error success.
        if (wasIncomingStarted && incomingNow != null) session.recordAligned(incomingNow - targetSec)
        loopAlignJob?.cancel()
        loopAlignJob = null
        // djmix15: the native renderer already brought the outgoing deck
        // <= -60 dB (mix.exit.gate violations=0), but LoopMixPort splices PCM
        // without touching ExoPlayer.volume, so RetireGuard.audible(spare.volume)
        // falsely diverts to a 350ms Phase.TAIL and overwrites reason to
        // "emergency-retire-guard". Mute the retiring player NOW, while the
        // port still holds silence, so zero dry sample can ever leak, then
        // disarm. Only when the renderer provably finished — genuine audible
        // audio still takes the guard below.
        outgoing?.volume = 0f
        captureLoopExitObservation(session)
        session.outPort?.disarm()
        session.inPort?.disarm()
        loopSession = null
        // The player is now the only source. The first seconds of that song
        // are where a starved decoder underruns, so analysis waits again
        // from this instant, not from the earlier mid-mix handoff.
        AutomixTelemetry.event("mix.exit.gate", mapOf("violations" to violations))
        // vc67: post-plan dry-run verification (scalars only).
        runCatching {
            val aligned = session.alignedErrorMs
            val phaseErr = if (wasIncomingStarted && aligned.isFinite()) aligned else Double.NaN
            val vr = com.music.bitchord.playback.smart.MixVerify.verify(0.0, 0.0f, phaseErr, violations)
            AutomixTelemetry.event(
                "mix.verify",
                mapOf(
                    "ok" to (vr.ok && phaseErr.isFinite()),
                    "reasons" to if (phaseErr.isFinite()) vr.reasons.joinToString(",") else "phase-unmeasured",
                    "phaseErrorMs" to phaseErr.takeIf { it.isFinite() },
                    "exitGateViolations" to violations,
                    "scope" to "phase-and-exit-gate-only",
                ),
            )
        }

        yieldAnalysis()
        finish()
        closeLater(session.renderer)
    }

    /**
     * The mix can't go on (a pause, a seek, a skip, a flush): settles onto
     * whichever song is ahead in it, at the mix's own position for it — or,
     * when [settle] is false, leaves the session player where something else
     * already put it.
     */
    private fun loopBail(
        reason: String,
        settle: Boolean = true,
        preserveSeek: Boolean = false,
        deferDisarm: Boolean = false,
    ) {
        lastCompleteReason = reason
        if (initiatingExitReason == null) initiatingExitReason = reason
        loopCleanupContendedSession = null
        loopCleanupContendedSinceNanos = 0L
        val session = loopSession
        if (session != null) captureLoopExitObservation(session)
        // djmix15: loop BPMs live in the plan, not in the (blank) analyses.
        // Record them so finish() reports real tempos even after a
        // RetireGuard tail re-enters finish() post-null. No mute here:
        // RetireGuard stays 100% active for genuine audible audio.
        val bailPlan = session?.plan
        if (bailPlan != null) {
            if (outgoingAnalysis.bpm <= 0) outgoingAnalysis = outgoingAnalysis.copy(bpm = bailPlan.out.bpm)
            if (incomingAnalysis.bpm <= 0) incomingAnalysis = incomingAnalysis.copy(bpm = bailPlan.inn.bpm)
        }
        if (loopRefusal.isNullOrBlank()) loopRefusal = reason
        Log.d(TAG, "loop mix stops ($phase): $reason")
        discardPrime()
        loopBuildJob?.cancel()
        loopBuildJob = null
        loopAlignJob?.cancel()
        loopAlignJob = null
        val out = outgoing
        val into = incoming
        val wasHandedOff = handedOff
        val m = session?.mixSeconds(System.nanoTime(), playing = false)
        val tookOver = session?.outPort?.tookOver == true
        val defer = SeekAction.shouldDeferLoopDisarm(deferDisarm, tookOver)
        if (defer && session != null) {
            // djmix26: keep the retiring port armed while the 350 ms native
            // setFadeOut tail renders in Phase.TAIL; disarm the active player's
            // inPort after handoff so it never gates the user's seek target.
            if (wasHandedOff) session.inPort?.disarm()
            tailLoopSession = session
        } else {
            session?.outPort?.disarm()
            session?.inPort?.disarm()
        }
        if (!tookOver && !wasHandedOff) {
            into?.volume = 0f
            into?.playWhenReady = false
        }
        // The settle point, worked out before [finish] forgets the plan.
        val target: Long? = when {
            session == null -> null
            wasHandedOff -> m?.let { LoopMix.positionAt(session.clock, LoopMix.Side.IN, it) }?.let { (it * 1000).roundToLong() }
            tookOver -> (m?.takeIf { it >= 0 }?.let { LoopMix.positionAt(session.clock, LoopMix.Side.OUT, it) }
                ?: session.plan.out.loopStart).let { (it * 1000).roundToLong() }
            else -> null
        }
        // Null loopSession before finish() so finish() routes audible emergency
        // bails into Phase.TAIL (never back into finishLoop()), then disarm
        // tailLoopSession at the end of Phase.TAIL (or immediately if IDLE).
        loopSession = null
        finish()
        if (!defer) {
            session?.renderer?.let(::closeLater)
        } else if (phase != Phase.TAIL) {
            disarmTailLoopSession()
        }
        // The session player, after [finish]: idle by then, so the seek's own
        // discontinuity can't come back here as another bail.
        // djmix14-review: manual user scrub is authoritative — never overwrite
        // newPosition with the old loop clock. preserveSeek skips the seek.
        val sessionPlayer = if (wasHandedOff) into else out
        if (sessionPlayer != null && target != null && !preserveSeek && (settle || (!wasHandedOff && tookOver))) {
            sessionPlayer.seekTo(target)
        }
    }

    /** Snapshot ticker-visible port evidence before its generation is disarmed. */
    private fun captureLoopExitObservation(session: LoopMixSession) {
        val port = session.outPort ?: return
        capturedLoopTookOver = port.tookOver
        capturedTakeoverSpliceBufferStatus = port.takeoverSpliceBufferStatus
    }

    /** The loop mix isn't happening after all: the pair takes the planner's blend instead. */
    private fun loopFallBack(reason: String) {
        // djmix13: count every arm death so regressions are visible.
        val failedPlan = loopSession?.plan ?: loopTarget ?: loopPlanned
        val failedTechnique = failedPlan?.let { nativeRouteFields(it)["technique"] as String } ?: "unknown-native"
        routeRefusal("mix.arm.death", reason, "prepared", failedTechnique, "classic",
            coarseDecline = nativeBinding?.coarseDecline
                ?: loopOutAnalysis?.let { out -> loopInAnalysis?.let { inn -> shouldDeclineToMix(out, inn) } })
        val fbKey = "$reason|${phase.name}"
        if (fbKey != lastFallbackLogKey) {
            lastFallbackLogKey = fbKey
            routeRefusal("mix.loop.fallback", reason, "prepared", failedTechnique, "classic",
                coarseDecline = nativeBinding?.coarseDecline
                    ?: loopOutAnalysis?.let { out -> loopInAnalysis?.let { inn -> shouldDeclineToMix(out, inn) } })
        }
        Log.d(TAG, "loop mix falls back to a blend: $reason")
        val pairKey = loopOutItem?.let { o -> loopInItem?.let { i -> "${o.mediaId}->${i.mediaId}" } }
            ?: loopPlanKey?.substringBefore("|")
        if (pairKey != null) {
            countdownStabilizer.onRungFailed(
                pairKey, scrubEpoch, CountdownStabilizer.Family.LOOP, suppressBridgeToo = true,
            )
            loopSuppressedPair = pairKey
            loopSuppressedUntil = nowMs() + LOOP_SUPPRESSION_MS
        }
        loopPlanned = null
        loopScript = null
        loopPlanKey = null
        lastSmartPlan = null
        lastSmartPlanStartMs = 0L
        pinnedCatchUpKey = null
        pinnedCatchUpPlan = null
        loopRefusal = reason
        // Internal pre-takeover teardown is not the opportunity's terminal:
        // classic fallback and decode retries keep the original pair identity.
        val previousFallback = routingFallback
        routingFallback = true
        try {
            loopBail(reason, settle = false)
        } finally {
            // A retire guard can suspend this cleanup in TAIL. Keep its
            // nonterminal classification through that tail's eventual
            // mix.complete rather than reverting when loopBail returns.
            if (phase != Phase.TAIL) routingFallback = previousFallback
        }
        // djmix26: immediately re-anchor & adopt/arm classic on the same tick
        // so the countdown & smartTransitionWindow never flash late or vanish.
        if (phase == Phase.IDLE && !inFallbackReplan) {
            inFallbackReplan = true
            try {
                val p = active()
                val dur = p.duration
                if (AppSettings.smartFadeEnabled.value && p.hasNextMediaItem() && dur != C.TIME_UNSET && dur > 0L) {
                    considerSmartTransition(dur)
                    publishAutomixDetail()
                }
            } finally {
                inFallbackReplan = false
            }
        }
    }

    /** Frees the renderer once the retired sink is certain to have stopped calling it. */
    private fun closeLater(renderer: LoopMixRenderer) {
        scope.launch {
            delay(LOOP_RENDERER_CLOSE_DELAY_MS)
            renderer.close()
        }
    }

    // ---- Stats for nerds ----------------------------------------------------

    private var lastSmartPlan: TransitionPlan? = null
    private var lastSmartPlanStartMs = 0L

    /**
     * The detailed Automix line under the stream stats: both songs' analysis
     * while it waits, then the planned mix and why a pair isn't beatmatched.
     */
    private fun publishAutomixDetail() {
        val player = active()
        val item = player.currentMediaItem
        val status = when {
            !AppSettings.smartFadeEnabled.value || item == null || ListenTogether.state.value.inParty -> null
            else -> describeAutomix(player, item)
        }
        if (AppSettings.automixStatus.value != status) AppSettings.automixStatus.value = status
        val detail = status?.detail
        if (AppSettings.automixDetail.value != detail) AppSettings.automixDetail.value = detail
    }

    private fun countdownProgress(secondsLeft: Double): Float {
        val lead = (blendArmLeadMs / 1000.0).coerceAtLeast(1.0)
        return (1.0 - secondsLeft / lead).toFloat().coerceIn(0f, 1f)
    }

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
    ): AutomixStatus {
        val (head, body) = MixDebug.decorate(headline, detail)
        return AutomixStatus(head, body, style, progress, state, planType, bars, tempoPct, locked, rung, reason, overlapBars)
    }

    /** Seconds until [startMs] on the player clock, never negative. */
    private fun shownSeconds(player: ExoPlayer, startMs: Long): Double =
        ((startMs - player.currentPosition) / 1000.0).coerceIn(0.0, 3600.0)

    private fun describeAutomix(player: ExoPlayer, item: MediaItem): AutomixStatus {
        if (phase == Phase.IDLE && nowMs() < scrubSettledAtMs) {
            return status(
                headline = "Replanning…",
                detail = "Replanning transition…",
                style = "wait",
                progress = 0f,
                state = "preparing",
            )
        }
        val late = loopRefusal?.takeIf { it.isNotBlank() }?.let { " Late: $it." }.orEmpty()
        val session = loopSession
        if (session != null) {
            val m = session.mixSeconds(System.nanoTime(), outgoing?.isPlaying ?: false)
            val secondsLeft = if (m == null || m < 0) -(m ?: -blendArmLeadMs / 1000.0) else 0.0
            val stage = when {
                m == null || m < 0 -> "${ceil(secondsLeft).toInt().coerceAtLeast(1)}s"
                phase == Phase.LOOP_ARMING -> "0s"
                m < session.clock.releaseAt -> "looping"
                else -> "letting go"
            }
            val tempo = loopTempo(session.plan)
            val aligned = session.alignedErrorMs.takeIf { it.isFinite() }
                ?.let { " Handover ${"%+.1f".format(Locale.ROOT, it)} ms." }.orEmpty()
            val phrase = phraseDetail?.let { " $it." }.orEmpty()
            val lockedNow = phraseLock.locked || phraseLock.shown || shownCountdownKey != null
            val lockTag = if (lockedNow) " · locked" else ""
            return status(
                headline = listOf(session.choreoLabel.ifEmpty { session.plan.rung }, stage, tempo).joinToString(" · ") + lockTag, // AUTOMIX-V2(orch)
                detail = "${session.choreoLabel.ifEmpty { session.plan.rung }}. ${describeLoop(session.plan)}.$phrase$aligned$late",
                style = session.plan.rung.lowercase(),
                progress = if (m != null && m >= 0) 1f else countdownProgress(secondsLeft),
                state = when (m) {
                    null -> "arming"
                    else -> if (m < 0) "arming" else if (m < session.clock.releaseAt) "mixing" else "echo"
                },
                planType = session.plan.rung,
                bars = session.plan.totalBars,
                tempoPct = tempoPct(session.plan.out.bpm, session.plan.targetBpm),
                locked = lockedNow,
                rung = session.plan.rung,
                overlapBars = session.plan.totalBars,
            )
        }
        val nextIndex = player.nextMediaItemIndex
        val next = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
        val pairKey = next?.let { "${item.mediaId}->${it.mediaId}" }
        val suppressingLoop = pairKey != null && loopSuppressedPair == pairKey && nowMs() < loopSuppressedUntil
        val planned = if (suppressingLoop) null else loopPlanned
        // Honest countdown: a start already behind the playhead (-600 ms)
        // must never read as 0s/locked. Show replanning until the catch-up
        // planner re-anchors ahead.
        if (planned != null && next != null && loopPlanKey?.startsWith("${item.mediaId}->${next.mediaId}|") == true) {
            val startMs = (planned.out.loopStart * 1000).roundToLong()
            if ((phase == Phase.IDLE || (phase == Phase.LOOP_ARMING && loopSession?.outPort?.tookOver != true)) && startMs < player.currentPosition - 600L) {
                return status(
                    headline = "Replanning…",
                    detail = "Replanning transition…",
                    style = planned.rung.lowercase(),
                    progress = 0f,
                    state = "preparing",
                    planType = planned.rung,
                    bars = planned.totalBars,
                    tempoPct = tempoPct(planned.out.bpm, planned.targetBpm),
                    locked = false,
                )
            }
            val inSeconds = (planned.out.loopStart - player.currentPosition / 1000.0).coerceAtLeast(0.0)
            val seconds = CountdownStabilizer.displaySeconds(startMs, player.currentPosition)
            // djmix14: lock whenever the countdown is shown (seconds>=0).
            // The old seconds>0 gate was blind after a scrub (seconds==0).
            val shownKey = loopPlanKey
            if (shownKey != null && shownCountdownKey != shownKey) {
                shownCountdownKey = shownKey
                shownLoopStartMs = startMs
                watchdogFired = false
                phraseLock.noteShown()
            }
            if (pairKey != null) countdownStabilizer.markShown(pairKey, scrubEpoch)
            val phrase = phraseDetail?.let { " $it." }.orEmpty()
            val lockedNow = phraseLock.locked || phraseLock.shown || shownCountdownKey != null
            val lockTag = if (lockedNow) " · locked" else ""
            return status(
                headline = "${planned.rung} · ${seconds}s · ${loopTempo(planned)}" + lockTag,
                detail = "${planned.rung} in ${seconds}s. ${describeLoop(planned)}.$phrase$late",
                style = planned.rung.lowercase(),
                progress = countdownProgress(inSeconds),
                state = when {
                    phase == Phase.LOOP_RUNNING || phase == Phase.FADING -> "mixing"
                    lockedNow -> "ready"
                    else -> "preparing"
                },
                planType = planned.rung,
                bars = planned.totalBars,
                tempoPct = tempoPct(planned.out.bpm, planned.targetBpm),
                locked = lockedNow,
                rung = planned.rung,
                overlapBars = planned.totalBars,
            )
        }
        val plan = lastSmartPlan
        if (plan != null && !plan.blocked && next != null && AppSettings.smartTransitionWindow.value != null) {
            // Honest countdown: a classic start already behind the playhead
            // (-600 ms) shows replanning, never now/0s.
            if ((phase == Phase.IDLE || (phase == Phase.LOOP_ARMING && loopSession?.outPort?.tookOver != true)) && lastSmartPlanStartMs < player.currentPosition - 600L) {
                return status(
                    headline = "Replanning…",
                    detail = "Replanning transition…",
                    style = "wait",
                    progress = 0f,
                    state = "preparing",
                    planType = "",
                    bars = 0,
                    tempoPct = 0f,
                    locked = false,
                )
            }
            val inSeconds = ((lastSmartPlanStartMs - player.currentPosition) / 1000.0).coerceAtLeast(0.0)
            val seconds = CountdownStabilizer.displaySeconds(lastSmartPlanStartMs, player.currentPosition)
            val whenText = if (lastSmartPlanStartMs > player.currentPosition) "${seconds}s" else "now"
            if (pairKey != null) countdownStabilizer.markShown(pairKey, scrubEpoch)
            val bars = if (plan.beatSeconds > 0) (plan.fadeSeconds / (4 * plan.beatSeconds)).roundToInt() else 0
            val length = if (bars > 0) "$bars bars" else "${"%.1f".format(Locale.ROOT, plan.fadeSeconds)} s"
            val style = mixStyle(plan)
            val key = plan.mixSummary.split(" · ").firstOrNull { "→" in it }.orEmpty()
            val outBpm = analysisFor(item).bpm
            val inBpm = next.let { analysisFor(it).bpm }
            val bridge = if (outBpm > 0 && inBpm > 0) "${outBpm.roundToInt()}→${inBpm.roundToInt()}" else ""
            val rate = if (LocalBlendAdmission.MARKER in plan.policyReasons) plan.incomingPlaybackRate
                else com.music.bitchord.playback.smart.audibleTempoRate(plan.incomingPlaybackRate)
            val tempo = when {
                plan.buildup -> "build"
                bridge.isNotBlank() -> bridge
                abs(rate - 1) < 0.0005 -> ""
                else -> "next ${"%.2f".format(Locale.ROOT, rate)}×"
            }
            val lateBit = if (late.isBlank()) "" else "late"
            val headline = listOf(style.replaceFirstChar { it.uppercase() }, whenText, tempo, key, lateBit)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            val why = plan.policyReasons.firstOrNull()?.let { " $it." }.orEmpty()
            val summary = plan.mixSummary.ifBlank { "$style, $length" }
            val lockedNow = phraseLock.locked || phraseLock.shown ||
                (pairKey != null && countdownStabilizer.committedFor(pairKey, scrubEpoch)?.locked == true)
            return status(
                headline = headline + if (lockedNow) " · locked" else "",
                detail = "$summary. $length.$why$late".replace("  ", " "),
                style = style,
                progress = if (inSeconds > 0) countdownProgress(inSeconds) else 1f,
                state = when {
                    inSeconds > 0 && lockedNow -> "ready"
                    inSeconds > 0 -> "preparing"
                    else -> "mixing"
                },
                planType = style.replaceFirstChar { it.uppercase() },
                bars = bars,
                tempoPct = tempoPct(outBpm, inBpm),
                locked = lockedNow,
                rung = style.replaceFirstChar { it.uppercase() },
                reason = plan.reason,
                overlapBars = bars,
            )
        }
        fun label(target: MediaItem?, outgoing: Boolean): String {
            if (target == null) return "none"
            val analysis = analysisFor(target)
            val state = stateOf(target, analysis)
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
        // A NATURAL plan clears the transition window, so it never reaches the
        // classic status above. Carry its rung/reason out anyway: a handoff with
        // no overlap is a degraded mix and the listener should see why.
        val natural = plan?.takeIf { it.transitionStyle == TransitionStyle.NATURAL }
        return status(
            headline = "Next $nextLabel".take(42),
            detail = detail,
            style = "wait",
            progress = -1f,
            rung = if (natural != null) "Natural handoff" else "",
            reason = natural?.reason?.removePrefix("natural-end:")?.ifBlank { "analysis-fallback" } ?: "",
            overlapBars = 0,
        )
    }

    /** Tempo match of [from]->[to] in percent after octave folding. */
    private fun tempoPct(from: Double, to: Double): Float {
        if (!(from > 0) || !(to > 0)) return 0f
        var r = from / to
        while (r > 2.0) r /= 2.0
        while (r < 0.5) r *= 2.0
        return (r * 100.0).toFloat()
    }

    private fun loopTempo(plan: LoopMix.Plan): String {
        val from = plan.out.bpm.roundToInt()
        val to = plan.targetBpm.roundToInt()
        return if (from == to) "$from BPM" else "$from→$to"
    }

    private fun mixStyle(plan: TransitionPlan): String = when (plan.djEffect) {
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

    // ---- Lifecycle of a transition -----------------------------------------

    /**
     * Abandons whatever is in flight.
     *
     * What has to be put back depends entirely on whether [startFade] got as far
     * as swapping the roles. Before the handoff the session player is untouched
     * and the standby is a silent scratch player, so there is nothing to unwind
     * at all — [finish] just retires it. After the handoff the session has
     * already moved and cannot be moved back (the incoming track is playing and
     * has been announced), so the only thing left is to take the outgoing track
     * away gracefully.
     */
    private fun appSettingsMarkerShown(): Boolean =
        runCatching { com.music.bitchord.data.settings.AppSettings.smartTransitionWindow.value != null }.getOrDefault(false)

    /**
     * INVARIANT (vc70 Seam A): a transition exit path may never reopen
     * filters while any deck is audibly above zero gain. Opening a parked
     * low-pass while audible is a ~30 ms spectral step (measured +19.2 dB
     * AIR, centroid 1242->6001 Hz) rather than a musical ramp. Every
     * exit (finish, bail including force, release) must defer
     * filters.open() until [RetireGuard] reports silence on both decks;
     * the parked cutoff is only reopened once gains reach zero via the
     * existing TAIL/BAILING dissolve. Do not add an unconditional open()
     * at a teardown site without this check.
     */
    private fun anyDeckAudible(): Boolean {
        val outVol = outgoing?.volume ?: 0f
        val inVol = incoming?.volume ?: 0f
        val tailVol = tailTarget?.volume ?: 0f
        return RetireGuard.audible(outVol) || RetireGuard.audible(inVol) || RetireGuard.audible(tailVol)
    }

    /** Opens filters only once both decks are silent (see [anyDeckAudible]). */
    private fun openFiltersWhenSilent() {
        if (anyDeckAudible()) return
        filters.open()
        tracks.incomingMidDb(0f)
        tracks.outgoingMidDb(0f)
    }

    /**
     * vc77 retiree gate (Seam A companion): the retiring (spare) filter is
     * [filters.outgoing] — the physical spare sink — regardless of which
     * track [tracks] currently routes. Post-handoff the survivor plays at 1,
     * so [openFiltersWhenSilent] can never open the spare; gating on the
     * retiree alone lets the spare return to neutral while the survivor
     * stays untouched. Tested volume is the retiree plus [tailTarget].
     */
    private fun retireeAudible(): Boolean {
        val retiring = if (handedOff) outgoing else incoming
        val retiringVol = retiring?.volume ?: 0f
        val tailVol = tailTarget?.volume ?: 0f
        return RetireGuard.audible(retiringVol) || RetireGuard.audible(tailVol)
    }

    /** Opens only the spare filter, once its retiree is silent. */
    private fun openSpareFiltersWhenSilent() {
        if (retireeAudible()) return
        filters.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        filters.outgoingMidDb(0f)
    }

    /**
     * Abnormal-exit survivor recovery. When a blend ends before progress 1
     * (setting switched off or forced bail), the survivor session deck can be
     * left parked mid-ride while audible at 1 — [finish] would otherwise only
     * open the spare. Opens only the survivor's track via [tracks] (the
     * session physical in both [handedOff] states) once its retiree is
     * silent, leaving the retiring spare filter untouched: moving the spare
     * while its deck is audible would be a second Seam-A step and it is
     * retired silently anyway. Survivor open while the survivor is audible
     * is the deliberate exception — a permanent muffle is worse than the
     * ~30 ms glide step, and the step is not compounded by a spare move.
     */
    private fun openSurvivorFiltersWhenRetireeSilent() {
        if (retireeAudible()) return
        if (handedOff) {
            tracks.incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
            tracks.incomingMidDb(0f)
        } else {
            tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
            tracks.outgoingMidDb(0f)
        }
    }

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
        if (phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING) {
            loopBail("interrupted", settle = false)
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
        nudgeUntil = 0L

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
                easing = null
                softSpeedJob?.cancel()
                softSpeedJob = null
                nudgeUntil = 0L
                it.setPlaybackSpeed(deckSpeedSafe(AppSettings.playbackSpeed.value, 1.0, beatmatched = false))
                filters.incomingTempo(1f)
            }
            outgoing?.let(::retire)
        } else {
            // The transition never became audible, so the session player never
            // moved and the standby is the one to throw away.
            outgoing?.let { out ->
                out.volume = 1f
                // Sped up for a blend that never came: walk it back like any stretch.
                if (outgoingPlaybackRate != 1.0) {
                    beginEase(out, outgoingPlaybackRate, out.currentMediaItem?.let(analysisFor) ?: TrackAnalysis())
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
        rampSteps = 0
        outgoingAnalysis = TrackAnalysis()
        incomingAnalysis = TrackAnalysis()
        localBlendInFlight = false
        lastPairAnalysisSignature = null
        phase = Phase.IDLE
        if (wasTransitioning) {
            mixEvent("mix.complete", completionFields, completionContext)
            if (isTerminal) {
                opportunity = null
                initiatingExitReason = null
            }
            capturedTakeoverSpliceBufferStatus = null
            capturedLoopTookOver = null
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
            if (!aimIncoming) return
            if (handedOff) filters.incoming(lowPassHz, highPassHz) else filters.outgoing(lowPassHz, highPassHz)
        }

        override fun outgoing(lowPassHz: Float, highPassHz: Float) {
            if (!aimOutgoing) return
            if (handedOff) filters.outgoing(lowPassHz, highPassHz) else filters.incoming(lowPassHz, highPassHz)
        }

        override fun incomingLeadMs(): Long =
            if (handedOff) filters.incomingLeadMs() else filters.outgoingLeadMs()

        override fun outgoingLeadMs(): Long =
            if (handedOff) filters.outgoingLeadMs() else filters.incomingLeadMs()

        override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) {
            if (!aimOutgoing) return
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
            if (!aimOutgoing) return
            if (handedOff) filters.outgoingColour(effect, amount, beatSeconds, scoop)
            else filters.incomingColour(effect, amount, beatSeconds, scoop)
        }

        override fun incomingColour(
            effect: com.music.bitchord.playback.smart.DjEffect,
            amount: Float,
            beatSeconds: Float,
            scoop: Float,
        ) {
            if (!aimIncoming) return
            if (handedOff) filters.incomingColour(effect, amount, beatSeconds, scoop)
            else filters.outgoingColour(effect, amount, beatSeconds, scoop)
        }

        override fun incomingMidDb(gainDb: Float) {
            if (!aimIncoming) return
            if (handedOff) filters.incomingMidDb(gainDb) else filters.outgoingMidDb(gainDb)
        }

        override fun outgoingMidDb(gainDb: Float) {
            if (!aimOutgoing) return
            if (handedOff) filters.outgoingMidDb(gainDb) else filters.incomingMidDb(gainDb)
        }
    }

    /**
     * Which side a ride pass may aim. Both, unless [rideFilters] is evaluating
     * the style at two different points in the blend — one per side, because
     * each side's filter runs its own distance ahead of the speaker.
     */
    private var aimIncoming = true
    private var aimOutgoing = true

    /**
     * Aims the incoming filter at [incomingAt] and the outgoing one at
     * [outgoingAt]: each the point in the blend its sink is processing *now*,
     * which is seconds past the point being heard — see
     * [TransitionFilterProcessor.leadUs]. Driving both off the heard progress,
     * as this used to, left every filter move seconds late: the incoming track
     * played its first seconds unfiltered, and the entry filter was still
     * lifting after the faders had finished.
     *
     * One style pass when the two agree, two masked passes when they don't —
     * a handful of `pow`s either way. [outgoingAt] is null for the arming
     * case, where the outgoing sink has not reached the blend yet and must be
     * left open.
     */
    private fun rideFilters(incomingAt: Float, outgoingAt: Float?) {
        if (outgoingAt == incomingAt) {
            rideStyle(incomingAt)
            return
        }
        // Incoming first: the few progress-independent branches that open both
        // filters agree with themselves, and the outgoing pass after this one
        // then has the last word on its own side.
        aimOutgoing = false
        rideStyle(incomingAt)
        aimOutgoing = true
        if (outgoingAt == null) return
        aimIncoming = false
        rideStyle(outgoingAt)
        aimIncoming = true
    }

    /**
     * Renders the plan's [TransitionStyle] as filtering across the blend.
     *
     * The gain curve is the same equal-power pair for every style — this is
     * what makes them sound different from each other, and it is the whole of
     * Phase 4. Driven off the same `progress` as the gains so the two stay
     * locked: a pause parks the filter exactly where it parks the fade.
     */
    private fun rideStyle(progress: Float) {
        when (render.style) {
            TransitionStyle.DJ_FILTER -> when (render.filterVariant) {
                FilterTransitionVariant.BASS_HANDOFF -> rideFallbackBassHandoff(progress)
                FilterTransitionVariant.DECLINE -> rideDecline(progress)
                FilterTransitionVariant.SWEEP,
                FilterTransitionVariant.ECHO_RIDE,
                -> rideFilterSweep(progress)
            }
            TransitionStyle.DJ_BLEND ->
                if (render.bassSwap) rideBassSwap(progress) else rideVocalSeparation(progress)
            // GAPLESS is an album being played through, where any filtering would
            // be an edit the record didn't ask for — so it stays open whatever
            // the material does.
            TransitionStyle.GAPLESS, TransitionStyle.NATURAL -> filters.open()
            // EQUAL_POWER used to be defined the same way: the bottom tier,
            // reached because the evidence was too weak to justify anything more
            // opinionated, therefore don't touch the spectrum.
            //
            // That conflated two different kinds of evidence. The tier is decided
            // by tempo and beat confidence; whether both tracks are singing is
            // measured by a separate model that doesn't depend on either. A pair
            // can have useless tempo evidence — dropping it to this tier — and a
            // perfectly good vocal mask on both sides saying they collide. Every
            // one of those transitions was rendered as a plain crossfade with two
            // full vocals over each other, because the weak half of the evidence
            // was silencing the strong half.
            TransitionStyle.MUFFLE -> rideMuffle(progress)
            TransitionStyle.DROP_IN -> rideDropIn(progress)
            TransitionStyle.BEATLESS_BRIDGE -> rideBeatlessBridge(progress)
            TransitionStyle.EQUAL_POWER -> rideVocalSeparation(progress)
        }
        if (render.echo) rideEchoOut(progress)
        rideColour(progress)
    }

    /** Flanger, plate, loop roll and the vocal-mid scoop, in amounts fixed by the plan. */
    private fun rideColour(progress: Float) {
        val effect = render.djEffect
        val settings = com.music.bitchord.playback.smart.effectSettings(effect)
        val amount = when (effect) {
            // Last several bars of an instrumental blend, loud enough to hear.
            com.music.bitchord.playback.smart.DjEffect.FLANGER -> when {
                render.buildup -> settings.wet * (0.35f + 0.65f * progress)
                progress > 0.30f -> settings.wet
                else -> 0f
            }
            // Only the tail. A wash from the first sample is what was muffling mixes.
            com.music.bitchord.playback.smart.DjEffect.REVERB -> settings.wet * ((progress - 0.75f) / 0.25f).coerceIn(0f, 1f)
            com.music.bitchord.playback.smart.DjEffect.LOOP_ROLL -> if (progress > 0.35f) 1f else 0f
            // A throw on the last bars. The tempo-gap echo is [rideEchoOut], which
            // owns the dry kill; this one leaves the song intact.
            com.music.bitchord.playback.smart.DjEffect.ECHO -> if (!render.echo && progress > 0.72f) settings.wet else 0f
            else -> 0f
        }
        val scoop = if (render.vocalHandoff && progress > 0.35f) 0.85f else 0f
        val beat = render.beatSecondsF
        tracks.outgoingColour(effect, amount, beat, scoop)
        tracks.incomingColour(com.music.bitchord.playback.smart.DjEffect.NONE, 0f, beat, 0f)
    }

    /**
     * Echo out, over whatever the style did to the outgoing side: that track
     * stays open — an echo of a muffled signal is only mud — with its low end
     * handed off on the echo beat, the send opening across the beat before it,
     * and the dry signal killed on it. After that only the repeats are left,
     * ringing over the incoming track.
     *
     * Left alone before the send opens, so the echo stage is not even engaged
     * — not a sample touched — until the beat that feeds it.
     */
    private fun rideEchoOut(progress: Float) {
        val p = progress.toDouble()
        val handover = ((p - (render.echoAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, bassCutoff(handover))
        if (p < render.echoFrom) return
        val send = if (p >= render.echoAt) 0.0 else (p - render.echoFrom) / (render.echoAt - render.echoFrom)
        tracks.outgoingEcho(render.echoSecondsF, send.toFloat(), echoDry(progress))
    }

    /** The outgoing dry level in an echo out: whole until the echo beat, gone a fraction of a beat after. */
    private fun echoDry(progress: Float): Float =
        (1.0 - ((progress - render.echoAt) / render.echoKill).coerceIn(0.0, 1.0)).toFloat()

    /**
     * How loud the outgoing track actually is at [progress] — uncapped, since
     * an echo tail runs past the end of the blend — for the headroom trim: its
     * fader, or with an echo out, where the fader stays up for the repeats and
     * the chain does the fading, the louder of its dry signal and its repeats.
     * The repeats sum with a full-level incoming track in the platform mixer
     * just as a dry signal would.
     */
    private fun outgoingLevel(progress: Float, wallSpan: Float): Float {
        if (!render.echo) return mixFall(progress)
        val sinceEcho = (progress - render.echoAt.toFloat()) * wallSpan
        val repeats = if (sinceEcho > 0f) sinceEcho / (render.echoSecondsF * 1000f) else 0f
        val wet = if (sinceEcho > 0f) ECHO_LEVEL * ECHO_DECAY.pow(repeats) else 0f
        return maxOf(echoDry(progress), wet)
    }

    /**
     * The minimum intervention: pull two colliding vocals apart, and otherwise
     * leave the spectrum alone.
     *
     * Not a filter ride. [rideFilterSweep] is a *style* — a gesture chosen for a
     * pair that cannot be blended flat, driving to [FILTER_FLOOR_HZ] and taking
     * the outgoing track somewhere distant. This is damage control on a pair that
     * was going to be crossfaded plainly, and it has to stay subtle enough that a
     * listener notices the absence of the clash rather than the presence of a
     * filter. So it works the same way — complementary bands, outgoing losing its
     * top while the incoming enters with its body lifted — over a much shorter
     * distance, and only as far as the measured collision justifies.
     *
     * Zero overlap leaves both sides open, which is exactly what these styles did
     * before, so nothing changes for a pair that doesn't collide or for either
     * track lacking a vocal mask.
     */
    private fun rideVocalSeparation(progress: Float) {
        val amount = render.vocalOverlap.coerceIn(0.0, 1.0)
        if (amount <= 0.0) {
            filters.open()
            return
        }
        // vc73: clean <=200 Hz bass swap + vocal-mid dip. No underwater LP muffle / tinny HP demuffle.
        val depth = VocalDepthFilter.valueAtCurve(render.depthCurve, progress.toDouble())
        val p = progress.toDouble()
        val handoverAmount = ((p - (render.swapAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, bassCutoff(handoverAmount))
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, bassCutoff(1.0 - handoverAmount))
        tracks.outgoingMidDb((-5.5 * amount * depth).toFloat())
        tracks.incomingMidDb(0f)
    }

    /**
     * Pulls the outgoing track behind a closing low-pass while the incoming one
     * arrives with its body lifted out, for a pair too far apart in tempo to
     * blend flat.
     *
     * ## Why both sides are filtered
     *
     * The first version filtered only the outgoing track, and squared the
     * progress so that the sweep was spent almost entirely in the second half.
     * Both halves of that were wrong for the same reason: at the midpoint the
     * outgoing cutoff was still at 6.9kHz — wide open across the whole vocal
     * range — and the incoming track was explicitly set to no filtering at all.
     * So for the entire first half of every transition, two complete vocals
     * played over each other at comparable level, and the only thing
     * distinguishing them was gain. That is what a plain crossfade sounds like,
     * which is the one thing this is meant not to be.
     *
     * What a DJ does instead is hand the midrange over rather than double it:
     * the outgoing track starts losing its top the moment the blend begins, and
     * the incoming one enters high-passed — hats and presence only, no vocal
     * body — opening out as the outgoing track darkens. The two occupy
     * complementary bands through the middle of the blend and never compete for
     * the range a voice lives in.
     *
     * [FILTER_SWEEP_SHAPE] is what replaces the squaring: front-loaded now, so
     * the outgoing track's top is gone within the first tenth of the blend
     * rather than somewhere past the midpoint. What keeps that from gutting the
     * track being left is [FILTER_FLOOR_HZ] — the ride settles onto a 300Hz bed
     * and stays there — not restraint in the early travel, which is the part the
     * listener reads as the transition happening at all.
     */
    /**
     * Muffle: the outgoing deck keeps its level while a steep low-pass
     * closes over it to the muffle floor across the entry portion, then
     * reopens floor->open across the plan's return leg ([Render.exitBeats]).
     * The incoming enters clean and the low end still changes hands once,
     * on the swap downbeat. At progress 1 the outgoing low-pass is
     * full-band, so teardown never needs the open() glide on this deck.
     */
    private fun rideMuffle(progress: Float) {
        val sweep = render.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            filters.open()
            return
        }
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val lead = render.muffleLeadBeats.takeIf { it > 0 } ?: 0
        val exit = render.exitBeats.takeIf { it > 0 }
            ?: TransitionAlternatives.MUFFLE_EXIT_BEATS
        val entryFraction = if (lead > 0) {
            ((lead - exit).toDouble() / lead.toDouble()).coerceIn(0.05, 0.95)
        } else {
            render.swapAt.coerceIn(0.05, 0.95)
        }
        val cutoff = TransitionAlternatives.muffleCutoffAt(
            progress = progress.toDouble(),
            sweep = sweep,
            entryFraction = entryFraction,
            openHz = open,
            floorHz = MUFFLE_FLOOR_HZ,
        )
        val p = progress.toDouble()
        val handover = ((p - (render.swapAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        tracks.outgoing(cutoff.toFloat(), bassCutoff(handover))
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, bassCutoff(1.0 - handover))
        tracks.incomingMidDb(0f)
        tracks.outgoingMidDb(0f)
    }

    /**
     * Drop-in (vc69 P0): the incoming track is already at full level on its
     * kick-locked anchor; the outgoing one cuts inside the first half of
     * the overlap and leaves its echo tail (ridgeEchoOut keeps ringing it).
     * Nothing filters either deck here.
     */
    private fun rideDropIn(progress: Float) {
        val half = (progress.toDouble() / 0.5).coerceIn(0.0, 1.0)
        tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, bassCutoff(half))
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        tracks.incomingMidDb(0f)
        tracks.outgoingMidDb(0f)
    }

    /**
     * Beatless bridge: both decks through a soft low-pass for the span, then
     * both leave park->open over the plan's return leg ([Render.exitBeats])
     * so the surviving incoming deck is full-band at progress 1 and never
     * needs the teardown open() glide while audible.
     */
    private fun rideBeatlessBridge(progress: Float) {
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val amount = render.vocalOverlap.coerceIn(0.0, 1.0)
        val depth = VocalDepthFilter.valueAtCurve(render.depthCurve, progress.toDouble())
        val baseFloor = glide(open, VOCAL_SEPARATION_FLOOR_HZ, amount)
        val floor = VocalDepthFilter.scaleTravel(open, baseFloor, depth)
        val separated = glide(open, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE))
        val totalBeats = render.beats.coerceAtLeast(1.0)
        val exitBeats: Double = (render.exitBeats.takeIf { it > 0 }
            ?: TransitionAlternatives.BRIDGE_EXIT_BEATS).toDouble()
        val returnStart = (1.0 - (exitBeats / totalBeats)).coerceIn(0.05, 0.95)
        val p = progress.toDouble()
        if (p < returnStart) {
            // Entry close, rescaled so it reaches park by the return start.
            val entryProg = (p / returnStart).coerceIn(0.0, 1.0)
            val entrySeparated = glide(open, floor, entryProg.pow(FILTER_SWEEP_SHAPE))
            tracks.outgoing(minOf(entrySeparated, BRIDGE_LOW_PASS_HZ).toFloat(), TransitionFilterProcessor.OFF_HZ)
            tracks.incoming(BRIDGE_LOW_PASS_HZ.toFloat(), TransitionFilterProcessor.OFF_HZ)
        } else {
            // Return leg: park at the floor the entry reached, reopen to open.
            val park = minOf(separatedAtReturnStart(returnStart), BRIDGE_LOW_PASS_HZ)
            val reopened = TransitionAlternatives.bridgeReopenCutoffAt(p, returnStart, park, open)
            tracks.outgoing(reopened.toFloat(), TransitionFilterProcessor.OFF_HZ)
            tracks.incoming(reopened.toFloat(), TransitionFilterProcessor.OFF_HZ)
        }
        tracks.incomingMidDb(0f)
        tracks.outgoingMidDb(0f)
    }

    /** Parked cutoff the bridge entry reaches at [returnStart] for the reopen leg. */
    private fun separatedAtReturnStart(returnStart: Double): Double {
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val amount = render.vocalOverlap.coerceIn(0.0, 1.0)
        val depthAtReturn = VocalDepthFilter.valueAtCurve(render.depthCurve, returnStart)
        val baseFloor = glide(open, VOCAL_SEPARATION_FLOOR_HZ, amount)
        val floor = VocalDepthFilter.scaleTravel(open, baseFloor, depthAtReturn)
        return glide(open, floor, 1.0.pow(FILTER_SWEEP_SHAPE))
    }

    private fun rideFilterSweep(progress: Float) {
        val sweep = render.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            filters.open()
            return
        }
        // vc73: clean sweep — outgoing LP never below 2800 Hz, incoming HP is bass-only (<=200 Hz), vocal-mid dip separates.
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val entry = glide(open, FILTER_ENTRY_HZ, sweep)
        val floor = glide(open, FILTER_FLOOR_HZ, sweep)
        val cutoff = glide(entry, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE))
        val handover = ((progress - (render.filterSwapAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        val p = progress.toDouble()
        tracks.outgoing(cutoff.toFloat(), bassCutoff(handover))
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, bassCutoff(1.0 - handover))
        tracks.outgoingMidDb((-5.0 * render.clash * p).toFloat())
        tracks.incomingMidDb(0f)
    }

    /**
     * Bounded-decline carve (FilterTransitionVariant.DECLINE): the 4 s
     * separation ride for declined pairs. Delegates to the shared envelope
     * (TransitionAlternatives.declineOutLpAt / declineInHpAt) so the numbers
     * the shared tests assert are the numbers the renderer rides: the
     * outgoing low-pass muffles to its floor (kick bed only) while the
     * incoming is held to presence, and the low end still changes hands once
     * so two kicks never flam. At progress 1 the outgoing is parked at its
     * floor and silent while the incoming is fully open and alone, so
     * teardown needs no open() glide on either deck.
     */
    private fun rideDecline(progress: Float) {
        val sweep = render.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            filters.open()
            return
        }
        val outLp = TransitionAlternatives.declineOutLpAt(progress.toDouble(), sweep)
        val inHp = TransitionAlternatives.declineInHpAt(progress.toDouble(), sweep)
        val handover = ((progress - (render.filterSwapAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        tracks.outgoing(outLp.toFloat(), bassCutoff(handover))
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, maxOf(inHp.toFloat(), bassCutoff(1.0 - handover)))
        tracks.incomingMidDb(0f)
        tracks.outgoingMidDb(0f)
    }

    /**
     * A compact fallback move for unmatched grids: keep the mids recognisable,
     * remove the two kicks from each other's way, then darken the outgoing deck
     * as the incoming low end takes over. This is deliberately a different
     * gesture from the long filter sweep and uses the same two filter stages.
     */
    private fun rideFallbackBassHandoff(progress: Float) {
        val p = progress.toDouble()
        val handover = ((p - (render.filterSwapAt - render.swapWidth)) / render.swapWidth).coerceIn(0.0, 1.0)
        // vc73: clean bass swap + mid dip. No 1600 Hz LP / 900-2400 Hz HP muffle.
        tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, bassCutoff(handover))
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, bassCutoff(1.0 - handover))
        tracks.outgoingMidDb((-5.5 * render.clash * p).toFloat())
        tracks.incomingMidDb(0f)
    }

    /**
     * Where the incoming track's high-pass sits at [progress].
     *
     * Rides from [topHz] down to nothing by [openBy] of the fade, so the track
     * is whole well before it is alone — the filter is there to keep it out of
     * the outgoing vocal's way during the overlap, not to colour the track the
     * listener is left with. [amount] scales the whole gesture, so a partial
     * sweep lifts proportionally less out.
     *
     * [ENTRY_SHAPE] is why the descent isn't linear. A geometric glide runs from
     * [TransitionFilterProcessor.OFF_HZ] to [topHz], and the bottom half of that
     * range is sub-bass nobody hears a filter in: measured, a plain ride was
     * down to 123Hz by a third of the way through, which is to say doing nothing
     * at all for two thirds of the overlap. The exponent spends the travel where
     * a voice actually is — 772Hz at a sixth of the way in, 436Hz at a third —
     * and still arrives at fully open on time.
     */
    private fun entryHighPass(progress: Float, amount: Double, topHz: Double, openBy: Double): Float {
        val remaining = (1.0 - progress / openBy).coerceIn(0.0, 1.0)
        return glide(TransitionFilterProcessor.OFF_HZ.toDouble(), topHz, amount * remaining.pow(ENTRY_SHAPE))
            .toFloat()
    }

    /**
     * Geometric interpolation between two cutoffs: [amount] 0 gives [from], 1
     * gives [to].
     *
     * Geometric rather than linear because pitch is logarithmic — a cutoff
     * moving in equal Hz steps sounds like it lurches through the bottom of its
     * range and crawls through the top.
     */
    private fun glide(from: Double, to: Double, amount: Double): Double =
        from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    /**
     * Hands the low end from one track to the other, once, at the beat the
     * planner chose.
     *
     * Below [BASS_SWAP_HZ] exactly one track is present at any instant: the
     * incoming track arrives with its low end lifted out, and takes it over as
     * the outgoing track's is lifted in turn. Ramped over [BASS_SWAP_WIDTH] of
     * the fade rather than switched, because a 24 dB/octave filter appearing in
     * one buffer is a transient of its own.
     *
     * The midrange is handled far more lightly than in [rideFilterSweep] but is
     * no longer left alone, which it was. This style is chosen for pairs that
     * are beat-matched and close in tempo, so the two tracks are *meant* to
     * sound simultaneous — but "simultaneous" and "two lead vocals at once" are
     * not the same thing, and only the bass was ever being separated. So the
     * incoming track still enters with its body lifted, over a shorter window
     * and from a lower corner, and the outgoing track loses its top in the last
     * half, where it is already quiet enough that the change reads as it
     * receding rather than as an effect.
     */
    private fun rideBassSwap(progress: Float) {
        // vc73: pure <=200 Hz bass swap + vocal-mid dip. No 520/950 Hz HP, no 2200/1100 Hz LP.
        val handover = ((progress - render.swapFrom) / render.swapWidth).coerceIn(0.0, 1.0)
        val clash = render.vocalOverlap.coerceIn(0.0, 1.0)
        tracks.incoming(TransitionFilterProcessor.OPEN_HZ, bassCutoff(1.0 - handover))
        tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, bassCutoff(handover))
        if (clash > 0.15) {
            tracks.outgoingMidDb((-4.5 * clash).toFloat())
        } else {
            tracks.outgoingMidDb(0f)
        }
        tracks.incomingMidDb(0f)
    }

    /**
     * The outgoing track's low-pass through a beat-matched blend: open until
     * [BLEND_EXIT_FROM], then closing to [BLEND_EXIT_LOW_PASS_HZ] by the end.
     *
     * Deliberately shallow. Enough to take the air and the sibilance off a voice
     * that is on its way out, so it stops competing with the one arriving;
     * nowhere near the [FILTER_FLOOR_HZ] that [rideFilterSweep] drives to, which
     * would contradict the reason this style was chosen.
     *
     * [clash] both starts it earlier and takes it further, because "shallow" is
     * the right default and the wrong answer for two choruses landing together.
     */
    private fun blendExitLowPass(progress: Float, clash: Double): Float {
        val from = render.blendExitFrom
        val amount = ((progress - from) / (1.0 - from)).coerceIn(0.0, 1.0)
        val floor = glide(BLEND_EXIT_LOW_PASS_HZ, BLEND_EXIT_CLASH_LOW_PASS_HZ, clash)
        return glide(TransitionFilterProcessor.OPEN_HZ.toDouble(), floor, amount).toFloat()
    }

    /** [amount] 0 leaves the low end alone; 1 lifts it out entirely. */
    private fun bassCutoff(amount: Double): Float =
        glide(TransitionFilterProcessor.OFF_HZ.toDouble(), BASS_SWAP_HZ, amount).toFloat()

    /**
     * Whether the transition in flight is doing something a plain crossfade
     * could not — which is what [AppSettings.smartMixInProgress] promises the
     * listener when it lights the scrubber up.
     *
     * Any one of three things qualifies, because they are the three things
     * analysis buys: a style that filters or swaps bass, an incoming track cued
     * into its arrangement instead of its first frame, or a tempo stretch. The
     * case this exists to exclude is the fallback — an unanalysed pair, cued at
     * 0:00, fading equal-power — which is indistinguishable from what the app
     * did before Automix existed and would be a lie to advertise.
     */
    private fun isRealMix(): Boolean = smartFadeActive && render.style != TransitionStyle.NATURAL && (
        render.style == TransitionStyle.DJ_BLEND ||
            render.style == TransitionStyle.DJ_FILTER ||
            incomingCueTimeMs > 0L ||
            incomingPlaybackRate != 1.0 ||
            outgoingPlaybackRate != 1.0
        )

    /** Equal-power pair: [riseGain]² + [fallGain]² = 1, so the blend never dips. */
    private fun riseGain(progress: Float): Float = MixGain.rise(progress)

    private fun fallGain(progress: Float): Float = MixGain.fall(progress)

    /**
     * The incoming fader for the blend in flight. Classic Automix — and every
     * non-DJ style — is the equal-power [riseGain]. Advanced, each style gets
     * the curve a DJ would ride it on, with the filters doing the separating:
     *  - a bass-swap blend has the incoming fader fully up by the swap
     *    ("both faders up"), backed off toward equal power as far as the two
     *    vocals collide;
     *  - a filter ride brings it up a little early, behind its high-pass.
     *
     * [headroomFor] keeps two faders at full from summing past full scale.
     */
    private fun mixRise(progress: Float): Float {
        // The decline carve owns its faders: its loud regions never coincide,
        // which is what hides the raw-tempo flam. Same numbers as the tests.
        if (render.filterVariant == FilterTransitionVariant.DECLINE) {
            return TransitionAlternatives.declineInGainAt(progress.toDouble()).toFloat()
        }
        if (!render.advanced) return riseGain(progress)
        val styled = when (render.style) {
            TransitionStyle.DJ_BLEND ->
                if (render.bassSwap) held(riseGain(progress), riseGain(progress / render.swapAtF)) else riseGain(progress)
            // One smooth pair for every filter gesture: the filters are what
            // tell the variants apart, and the low end changes hands on a beat
            // in all of them, so no fader needs to sit at full to make room.
            TransitionStyle.DJ_FILTER -> riseGain(progress.pow(FILTER_RISE_SHAPE))
            else -> riseGain(progress)
        }
        val vocalSafe = riseGain(progress.pow(VOCAL_SAFE_RISE_SHAPE))
        return styled + (vocalSafe - styled) * render.clash.toFloat()
    }

    /**
     * The outgoing fader, [mixRise]'s partner: held at full in a blend until
     * [Render.fallFrom] — the swap, but never past the midpoint or within a
     * bar of the end — and a smooth fall in a filter ride, where the closing
     * low-pass is already taking it away.
     */
    private fun mixFall(progress: Float): Float {
        // See mixRise: the carve's early fall keeps the loud regions disjoint.
        if (render.filterVariant == FilterTransitionVariant.DECLINE) {
            return TransitionAlternatives.declineOutGainAt(progress.toDouble()).toFloat()
        }
        if (!render.advanced) return fallGain(progress)
        val styled = when (render.style) {
            TransitionStyle.DJ_BLEND -> {
                if (!render.bassSwap) {
                    fallGain(progress)
                } else {
                    val from = render.fallFrom
                    held(fallGain(progress), if (progress <= from) 1f else fallGain((progress - from) / (1f - from)))
                }
            }
            TransitionStyle.DJ_FILTER -> fallGain(progress.pow(FILTER_FALL_SHAPE))
            else -> fallGain(progress)
        }
        val vocalSafe = fallGain(progress.pow(VOCAL_SAFE_FALL_SHAPE))
        return styled + (vocalSafe - styled) * render.clash.toFloat()
    }

    /** [dj] as far as [Render.holdFaders] allows, the rest of the way back toward [plain]. */
    private fun held(plain: Float, dj: Float): Float = plain + (dj - plain) * render.holdFaders

    // ---- Phase lock ---------------------------------------------------------

    /**
     * Keeps the incoming track's beats on the outgoing track's, the way a DJ
     * rides the jog wheel: measure how far apart the two beats are, then run the
     * incoming track a few percent fast or slow for just long enough to close
     * the gap.
     *
     * The incoming track starts on a 40ms arming tick plus however long its
     * player takes to start, so it lands anything up to tens of milliseconds
     * off the beat — the "galloping" of a bad mix. Nothing measured or
     * corrected that before.
     *
     * Pulsed rather than continuous on purpose. A speed change is only heard
     * once the audio already buffered ahead has played out, so a controller
     * that reacted to every reading would keep correcting an error it had
     * already fixed and oscillate. A pulse of known size and length moves the
     * incoming track by exactly the measured error however late it lands, and
     * the lock then waits [LOCK_SETTLE_MS] before measuring again. Readings are
     * taken [LOCK_SAMPLES] at a time and the median used, because a player's
     * reported position jitters by about as much as the error being fixed.
     *
     * No new processing: this is the same speed control the beatmatch stretch
     * already runs through, moved briefly.
     */
    private fun holdPhase(out: ExoPlayer, into: ExoPlayer, progress: Float) {
        val now = nowMs()
        if (nudgeUntil != 0L) {
            // A pause mid-pulse ends it early; the next reading picks up the rest.
            if (now < nudgeUntil && into.isPlaying) return
            nudgeUntil = 0L
            into.setPlaybackSpeed(incomingBaseSpeed())
            tracks.incomingTempo(1f)
            lockQuietUntil = now + LOCK_SETTLE_MS
            lockCount = 0
            return
        }
        // djmix18 (Bug 1): phase-lock micro-speed pulsing disabled. Repeated +/-3%
        // setPlaybackSpeed writes stutter the Sonic WSOLA stretcher on live vocals/beats.
        // Hold the incoming deck at its beatmatch rate for the whole blend; the mix is
        // already beat-aligned by the ramp and the grid, and a pulsing correction is worse
        // than a small drift. No new nudge is ever started here.
        return
        // Once the outgoing track is on its way out its beat stops mattering.
        if (progress >= LOCK_UNTIL || now < lockQuietUntil) return
        if (!out.isPlaying || !into.isPlaying) {
            lockCount = 0
            return
        }
        val error = phaseError(out, into)
        if (error.isNaN()) return
        lockErrors[lockCount++] = error
        if (lockCount < LOCK_SAMPLES) return
        lockCount = 0
        lockErrors.sort()
        val median = lockErrors[LOCK_SAMPLES / 2]
        if (abs(median) < LOCK_DEADBAND_SECONDS) {
            lockQuietUntil = now + LOCK_IN_PHASE_MS
            return
        }
        val base = incomingBaseSpeed()
        // Ahead slows down, behind speeds up; the pulse lasts as long as it
        // takes that offset in speed to cover the error.
        // Speeding up is capped so a nudge stacked on a large stretch never
        // takes the incoming track past [MAX_TOTAL_SPEED_UP].
        val factor = if (median > 0) {
            1f - NUDGE
        } else {
            minOf(1f + NUDGE, (MAX_TOTAL_SPEED_UP / incomingPlaybackRate).toFloat())
        }
        if (factor <= 1f && median < 0) return
        into.setPlaybackSpeed(deckSpeedSafe(AppSettings.playbackSpeed.value, incomingPlaybackRate * factor))
        tracks.incomingTempo(1f)
        nudgeUntil = now + (abs(median) / (base * abs(factor - 1f)) * 1000).roundToLong().coerceIn(1L, MAX_NUDGE_MS)
    }

    private fun incomingBaseSpeed(): Float {
        if (!localBlendInFlight) return deckSpeedSafe(AppSettings.playbackSpeed.value, incomingPlaybackRate)
        return deckSpeedForAdmittedBlend(AppSettings.playbackSpeed.value, incomingPlaybackRate)
    }

    /**
     * The buildup. Speed and pitch rise together by [BUILDUP_RISE] across the
     * overlap, late rather than from the first sample, and the flanger opens
     * with them. Steps of about one percent, not a write on every tick.
     * The pitch corrector stays off: this rise is the AudioTrack rate itself.
     */
    private fun rideBuildup(progress: Float) {
        val into = incoming ?: return
        if (!render.buildup || handedOff || localBlendInFlight) return
        val lift = BUILDUP_RISE * progress.toDouble() * progress.toDouble()
        // djmix18: clamp the beatmatch rate to [0.92f, 1.08f] before the buildup lift so the
        // tempo lift can never push a deck past the hard ceiling. deckPlayedRate re-clamps
        // rate + lift to [0.94, 1.15]; safeRateForDeck first pins rate to [0.92, 1.08].
        val target = deckSpeed(AppSettings.playbackSpeed.value, safeRateForDeck(incomingPlaybackRate) + lift)
        if (abs(target - buildupSpeed) < 0.008f) return
        buildupSpeed = target
        into.setPlaybackSpeed(target)
    }

    /**
     * How far the incoming beat sits ahead (positive) or behind the outgoing
     * one, in incoming-timeline seconds, or NaN when either has no grid or
     * the two are too far apart for this to be a phase error rather than a
     * grid disagreement.
     *
     * Read in outgoing seconds so the two grids compare directly: the incoming
     * one is divided by the stretch. Wrapped to the shorter of the two beats,
     * so a track counted at half or double time against the other still
     * locks on the shared pulse.
     */
    private fun phaseError(out: ExoPlayer, into: ExoPlayer): Double {
        if (!beatAt(outgoingAnalysis, positionMs(out) / 1000.0)) return Double.NaN
        val outSince = beatScratch[0]
        val outBeat = beatScratch[1]
        if (!beatAt(incomingAnalysis, positionMs(into) / 1000.0)) return Double.NaN
        val rate = mediaRatio
        val period = minOf(outBeat, beatScratch[1] / rate)
        var diff = (beatScratch[0] / rate - outSince) % period
        if (diff > period / 2) diff -= period else if (diff < -period / 2) diff += period
        if (abs(diff) > period * LOCK_MAX_BEATS) return Double.NaN
        return diff * rate
    }

    /**
     * Where [positionSec] falls in [analysis]'s beat: seconds past the last
     * beat into [beatScratch]`[0]`, that beat's length into `[1]`. False when
     * the track has no grid at all.
     *
     * Beats are counted within the bar the analyzer's downbeats bound, not off
     * one constant interval from the first beat, so a track that drifts still
     * reads its own local beat rather than one that slid minutes ago.
     */
    private fun beatAt(analysis: TrackAnalysis, positionSec: Double): Boolean {
        // The tempo is the authority. A stored beat interval that disagrees with
        // it by more than a quarter is not a beat — a head-only analysis was seen
        // carrying one of 24.7s on a 127 BPM track, which stopped the scrubber's
        // pulse dead for the rest of the blend.
        val fromTempo = if (analysis.bpm > 0.0) 60.0 / analysis.bpm else 0.0
        val interval = analysis.beatInterval
        val nominal = when {
            interval > 0.0 && (fromTempo <= 0.0 || abs(interval / fromTempo - 1.0) < 0.25) -> interval
            fromTempo > 0.0 -> fromTempo
            else -> return false
        }
        val downbeats = analysis.downbeats
        val next = downbeats.binarySearch(positionSec).let { if (it >= 0) it + 1 else -it - 1 }
        val origin: Double
        var beat = nominal
        if (next in 1 until downbeats.size) {
            origin = downbeats[next - 1]
            val bar = downbeats[next] - origin
            // A bar of one to eight beats is a bar, counted from the analyzer's own
            // downbeats. Anything longer is a stretch the analyzer found no
            // downbeat in, and dividing it up would invent a tempo.
            val beatsInBar = (bar / nominal).roundToInt()
            if (beatsInBar in 1..8) beat = bar / beatsInBar
        } else {
            origin = if (next >= 1) downbeats[next - 1] else analysis.firstBeat
        }
        if (beat <= 0.0 || !beat.isFinite()) return false
        beatScratch[0] = ((positionSec - origin) % beat + beat) % beat
        beatScratch[1] = beat
        return true
    }

    // ---- Tempo ease-back ----------------------------------------------------

    /**
     * Starts walking [player] off a beatmatch stretch of [rate], one small
     * step per beat of its own track, instead of snapping it back to the
     * listener's speed the moment the blend ends.
     *
     * Each step is at most [TEMPO_STEP_PER_BEAT] — under what anyone hears as
     * the tempo moving — so the length follows the stretch: a 10% speed-up
     * comes off over 14 beats, a 3% one over 4. One speed change per beat, not
     * per tick: each is a real change to the audio path.
     *
     * Plain speed changes, like [rampOutgoing]'s, not [setPlaybackSpeedClickless]:
     * its gain notch on every beat was the audible part, heard as a pump.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun beginEase(player: ExoPlayer, rate: Double, analysis: TrackAnalysis) {
        val audible = releasableRate(rate)
        val stretch = audible - 1.0
        if (abs(stretch) < 0.01) {
            player.setPlaybackSpeed(deckSpeed(AppSettings.playbackSpeed.value, 1.0))
            easeTempo(player, 1f)
            return
        }
        // Four steps across about 6.5s, then the song is itself again.
        // One change per beat was a click through the first seconds of every
        // mixed song, and holding the stretch for the rest of the track is
        // what bent arrangements out of shape.
        val steps = 4
        easeStep = stretch / steps
        easeRate = audible
        easeStepMs = 1_620L
        easeItemIndex = player.currentMediaItemIndex
        easeNextAtMs = player.currentPosition + easeStepMs
        easing = player
        easeOnActive = if (player === incoming) handedOff else !handedOff
        player.setPlaybackSpeed(deckSpeedSafe(AppSettings.playbackSpeed.value, audible))
        easeTempo(player, 1f)
    }

    /**
     * One step of the ease, when the playhead has crossed the next bar.
     * Measured on the track's own position so a pause holds it. Anything that
     * moves the listener off the stretched track — a skip, the next song, a
     * seek backwards, a party starting, the player no longer being the
     * session's — ends it on the listener's speed at once.
     */
    private fun driveEase() {
        val player = easing ?: return
        val position = player.currentPosition
        if (player !== active() ||
            player.currentMediaItemIndex != easeItemIndex ||
            position < easeNextAtMs - 2 * easeStepMs ||
            ListenTogether.state.value.inParty
        ) {
            endEase()
            return
        }
        if (position < easeNextAtMs) return
        easeRate -= easeStep
        if (abs(easeRate - 1.0) < EASE_DONE) {
            // The last step, the same size as the others.
            endEase(clickless = false)
            return
        }
        player.setPlaybackSpeed(deckSpeedSafe(AppSettings.playbackSpeed.value, easeRate))
        easeTempo(player, 1f)
        easeNextAtMs += easeStepMs
    }

    /**
     * Brings the outgoing track up to [outgoingPlaybackRate] over its last
     * [rampSteps] beats before the fade, one [TEMPO_STEP_PER_BEAT] step per
     * beat, so it arrives at the incoming track's tempo instead of the incoming
     * track being slowed to its own. Stepped off the track's position, so a
     * tick landing late only delays a step, never skips the tempo it reaches.
     */
    private fun rampOutgoing(out: ExoPlayer) {
        val due = ((out.currentPosition - rampStartMs) / rampStepMs + 1).toInt().coerceIn(0, rampSteps)
        if (due <= rampStep) return
        rampStep = due
        val rate = 1.0 + (outgoingPlaybackRate - 1.0) * due / rampSteps
        out.setPlaybackSpeed(deckSpeedSafe(AppSettings.playbackSpeed.value, rate))
        tracks.outgoingTempo(1f)
    }

    /** Beats a tempo change of [rate] takes at [TEMPO_STEP_PER_BEAT]; 0 for none. */
    private fun rampStepsFor(rate: Double): Int {
        val stretch = abs(rate - 1.0)
        return if (stretch < EASE_DONE) 0 else ceil(stretch / TEMPO_STEP_PER_BEAT).toInt()
    }

    /** Puts the easing player on the listener's own speed and forgets it. Idempotent. */
    private fun endEase(clickless: Boolean = true) {
        val player = easing ?: return
        val onActive = easeOnActive
        easing = null
        if (clickless) {
            setPlaybackSpeedClickless(player, deckSpeed(AppSettings.playbackSpeed.value, 1.0))
        } else {
            softSpeedJob?.cancel()
            softSpeedJob = null
            player.volume = 1f
            player.setPlaybackSpeed(deckSpeed(AppSettings.playbackSpeed.value, 1.0))
        }
        if (onActive) filters.incomingTempo(1f) else filters.outgoingTempo(1f)
    }

    /**
     * Pitch correction for the deck that is easing. Remembered as active or
     * spare at the moment the ease starts, because [finish] clears the
     * handoff flag while the walk back to the listener's speed is still going.
     */
    private fun easeTempo(player: ExoPlayer, rate: Float) {
        if (easing == null || easing !== player) {
            easeOnActive = if (player === incoming) handedOff else !handedOff
        }
        if (easeOnActive) filters.incomingTempo(rate) else filters.outgoingTempo(rate)
    }

    /**
     * Changes speed behind a very short gain notch. Android's AudioTrack speed
     * path can expose a discontinuity when PlaybackParams changes with a
     * non-zero sample under the cursor; the notch puts that boundary at silence.
     * Used only between transitions, so the fade's own gain automation never
     * competes with it. The incoming deck is configured while silent instead.
     */
    private fun setPlaybackSpeedClickless(player: ExoPlayer, target: Float) {
        if (abs(player.playbackParameters.speed - target) < SPEED_CHANGE_EPSILON) return
        if (!player.isPlaying || player !== active() || player.volume <= 0f) {
            player.setPlaybackSpeed(target)
            return
        }
        softSpeedJob?.cancel()
        softSpeedJob = scope.launch {
            val level = player.volume
            try {
                for (step in 1..SPEED_NOTCH_STEPS) {
                    player.volume = level * (1f - step.toFloat() / SPEED_NOTCH_STEPS)
                    delay(SPEED_NOTCH_STEP_MS)
                }
                player.setPlaybackSpeed(target)
                delay(SPEED_NOTCH_HOLD_MS)
                for (step in 1..SPEED_NOTCH_STEPS) {
                    player.volume = level * step.toFloat() / SPEED_NOTCH_STEPS
                    delay(SPEED_NOTCH_STEP_MS)
                }
            } finally {
                if (player === active() && phase == Phase.IDLE) player.volume = level
            }
        }
    }

    /** See [onBlendHeadroom]. */
    private fun headroomFor(incomingGain: Float, outgoingGain: Float): Float {
        // djmix9: floored trim - a true-peak limiter handles overs, not a -3 dB dip.
        return com.music.bitchord.playback.smart.MixGain.headroomTrim(incomingGain, outgoingGain)
    }

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
         * Where the outgoing low-pass sits the instant a filter ride begins.
         *
         * The ride used to start from [TransitionFilterProcessor.OPEN_HZ] and
         * travel down, which meant the first stretch of every transition was
         * spent crossing a range nobody can hear a filter in: a tenth of the way
         * through the fade the cutoff was still at 17.5kHz, indistinguishable
         * from no filter at all, and the ride only became audible around the
         * midpoint. Engaging here instead — above the fundamentals of everything
         * but cymbals, so what goes first is air and shimmer — is what makes the
         * gesture read as a hand landing on the filter the moment the blend
         * starts, rather than something remembered late.
         *
         * 9kHz was the first attempt at that and still read as late by ear: it
         * is above everything but cymbals, so engaging there takes the air off
         * and nothing else, and the outgoing vocal — the thing actually clashing
         * — was untouched until the sweep had travelled most of the way down.
         * 7kHz is inside the presence range, so the gesture is audible on the
         * voice itself from the first instant.
         */
        const val FILTER_ENTRY_HZ = 9_500.0

        /**
         * The bottom of a filter ride. Below a few hundred hertz a track stops
         * reading as "further away" and starts reading as "broken", which is not
         * the impression a transition should leave of the song being left.
         */
        const val FILTER_FLOOR_HZ = 2_800.0

        /**
         * Muffle (vc69 P0) low-pass floor: near 700 Hz, past where a voice is
         * understood (muffle.js muted point), so the closing track reads as
         * muffled rather than faded.
         */
        const val MUFFLE_FLOOR_HZ = 700.0

        /** Muffle cutoff shape across the lead beats. */
        const val MUFFLE_SHAPE = 1.4

        /**
         * Beatless bridge (vc69 P0) low-pass for both decks: soft and
         * ambient, never a full close, since neither side ever carried a
         * beat to kill.
         */
        const val BRIDGE_LOW_PASS_HZ = 1500.0

        /**
         * Where the low end is considered to end. Around the fundamental of a
         * bass guitar's upper register, and the usual corner on a mixer's bass
         * kill — high enough to clear the kick and the sub, low enough to leave
         * the body of the vocal alone.
         */
        const val BASS_SWAP_HZ = 200.0

        /** How much of the fade the low end takes to change hands. */
        const val BASS_SWAP_WIDTH = 0.10

        // ---- Advanced Automix ----------------------------------------------

        /**
         * Advanced: the low end changes hands over this many beats, ending on
         * the swap beat. Half a beat is a hand on the EQ, not a kill switch —
         * long enough that the 24 dB/octave corner moving is no click, short
         * enough that the two kicks never play together.
         */
        const val SWAP_BEATS = 0.5

        /** Where a filter ride hands its low end over, before bar-snapping. */
        const val FILTER_BASS_SWAP_AT = 0.5

        /**
         * Filter-ride fader shapes, as exponents on progress. Above 1 holds the
         * outgoing fader up longer, since the closing low-pass is already
         * taking it away; below 1 brings the incoming one up a little early
         * behind its high-pass.
         */
        const val FILTER_FALL_SHAPE = 1.15f
        const val FILTER_RISE_SHAPE = 0.65f

        /** Alternate fallback: a quick bass exchange followed by a shallow exit filter. */
        const val FALLBACK_HANDOFF_ENTRY_HZ = 900.0
        const val FALLBACK_HANDOFF_FLOOR_HZ = 1_600.0
        const val FALLBACK_HANDOFF_SHAPE = 0.8

        /** Vocal-aware fallback: keep the arriving lead out until the outgoing lead has cleared. */
        const val VOCAL_SAFE_ENTRY_HIGH_PASS_HZ = 2_400.0
        const val VOCAL_SAFE_ENTRY_OPEN_BY = 0.78
        const val VOCAL_SAFE_EXIT_SHAPE = 0.55
        const val VOCAL_SAFE_RISE_SHAPE = 1.65f
        const val VOCAL_SAFE_FALL_SHAPE = 0.65f

        /**
         * An echo out's dry signal fades over this many beats. It was a quarter
         * beat, which over a few repeats at half level was heard as the song
         * cutting out rather than trailing off.
         */
        const val ECHO_KILL_BEATS = 4.0

        /**
         * The echo's first repeat against the dry level, and what each repeat
         * keeps of the one before — [TransitionFilterProcessor]'s wet and
         * feedback, mirrored here for the headroom trim.
         */
        const val ECHO_LEVEL = 0.5f
        const val ECHO_DECAY = 0.4f

        /** Grace after the incoming track starts before its position is trusted for phase. */
        const val LOCK_START_MS = 300L

        /** Phase readings per decision; the median of these is what gets corrected. */
        const val LOCK_SAMPLES = 5

        /** Under this the two beats are together as far as anyone can hear. */
        const val LOCK_DEADBAND_SECONDS = 0.008

        /** After a nudge: long enough for the audio already buffered to play out at the new speed. */
        const val LOCK_SETTLE_MS = 400L

        /** After a reading in phase, how long before looking again. */
        const val LOCK_IN_PHASE_MS = 500L

        /**
         * How far a nudge moves the incoming speed. Inside the stretch the policy
         * already calls transparent, and over a pulse this short nobody hears
         * the tempo move — just the beats pulling together.
         */
        const val NUDGE = 0.03f

        /** Longest single nudge; anything left over is corrected on the next reading. */
        const val MAX_NUDGE_MS = 1_500L

        /** Past this much of the blend the outgoing track is too far gone for its beat to matter. */
        const val LOCK_UNTIL = 0.9f

        /**
         * More than this far apart — in beats of the shared pulse — is two grids
         * disagreeing about where the beat is, not a start that landed late, and
         * nudging toward it would only make it worse.
         */
        const val LOCK_MAX_BEATS = 0.3

        /**
         * The most the tempo moves in one beat, ramping up or easing back.
         * Gradual drift below about 1% a beat reads as the groove, not as the
         * track changing speed.
         */
        const val TEMPO_STEP_PER_BEAT = 0.0075

        /** The ceiling on a phase-lock nudge stacked on a speed-up. */
        const val MAX_TOTAL_SPEED_UP = 1.12

        /** Close enough to the listener's own speed to call the ease done. */
        const val EASE_DONE = 1e-4

        /** A step every this long when the track has no beat to step on. */
        const val EASE_FALLBACK_STEP_MS = 500L

        /** Short silence around an audible AudioTrack PlaybackParams update. */
        const val SPEED_NOTCH_STEPS = 2
        const val SPEED_NOTCH_STEP_MS = 2L
        const val SPEED_NOTCH_HOLD_MS = 2L
        const val SPEED_CHANGE_EPSILON = 0.0001f

        /**
         * Shape of the outgoing low-pass against fade progress, between
         * [FILTER_ENTRY_HZ] and [FILTER_FLOOR_HZ].
         *
         * Was 2.0 — squared — which left the cutoff at 6.9kHz at the midpoint,
         * so the outgoing vocal went untouched through the whole first half of
         * every transition. Then 1.3, which was still back-loaded: the exponent
         * held the cutoff near its entry point through the opening of the fade,
         * which is precisely where the two vocals overlap at comparable level.
         *
         * Above 1 now, so the song stays open through the first part of the
         * fade and only darkens toward the end. A front-loaded ride to a few
         * hundred hertz was muffling mixes that still had a chorus left in
         * them. [FILTER_FLOOR_HZ] stops the ride while the vocal is still
         * recognisable.
         */
        const val FILTER_SWEEP_SHAPE = 1.1

        /**
         * Where the incoming track's high-pass starts on a filter ride.
         *
         * Above the fundamental range of most voices and the body of a snare, so
         * what arrives first is presence and percussion — enough to hear a track
         * coming and lock onto its groove, not enough for a second lead vocal.
         *
         * 700Hz was that corner while the outgoing sweep was gentler. It no longer
         * is: the sweep engages at [FILTER_ENTRY_HZ] and is down to 4kHz a tenth
         * of the way in, so a 700Hz entry left the two tracks sharing very nearly
         * three octaves — and sharing them from 529Hz up, which is exactly where a
         * lead vocal's fundamentals sit. 1.2kHz takes about an octave off the
         * bottom of that shared band, and it is the octave the collision actually
         * happens in. What is left of the outgoing track then sits *under* the
         * arriving one rather than inside it, which is what makes the incoming
         * track read as a layer landing on top of a darkening one instead of a
         * second voice in the same space.
         */
        const val ENTRY_HIGH_PASS_HZ = 1_200.0

        /**
         * How far into the fade the incoming track is fully open again.
         *
         * Comfortably before the end: past this point the outgoing track is deep
         * into its own sweep and quiet with it, so there is nothing left to keep
         * out of the way of, and anything still filtered here would just be the
         * new track arriving wrong.
         */
        const val ENTRY_OPEN_BY = 0.6

        /**
         * Shape of the incoming high-pass's descent; see [entryHighPass].
         *
         * Below 1 so the corner lingers in the range a voice occupies instead of
         * dropping straight through it into sub-bass, where a high-pass is
         * inaudible and the clash this exists to prevent is already back.
         *
         * 0.35 rather than 0.45 for more of the same, and the effect compounds
         * across the overlap rather than being a flat offset: on a filter ride the
         * corner sits a fourteenth higher a tenth of the way in, a quarter higher
         * at three tenths, a third higher at four. So the hold is back-loaded into
         * the middle of the blend — where both tracks are near equal gain and the
         * collision is at its worst — and what gets given up in exchange is the
         * bottom of the descent, which is a few hundred hertz of sub-bass nobody
         * hears a high-pass leave. The release into the last of [ENTRY_OPEN_BY] is
         * correspondingly more of an event, which is the point: the arriving track
         * opening out is the moment the listener is meant to notice.
         */
        const val ENTRY_SHAPE = 0.35

        /**
         * How far [rideVocalSeparation] closes the outgoing track's top at a
         * full collision.
         *
         * Well above [FILTER_FLOOR_HZ]'s 300Hz, because this fires on pairs that
         * were going to be crossfaded plainly and the intent is to stop two
         * voices competing, not to send one of them into another room. 1.6kHz is
         * below the presence and sibilance a lead vocal is picked out by, and
         * above enough of its body that the track still reads as itself.
         */
        const val VOCAL_SEPARATION_FLOOR_HZ = 1_600.0

        /**
         * Where the incoming track's high-pass starts in [rideVocalSeparation].
         *
         * Lower than [ENTRY_HIGH_PASS_HZ]'s 1.2kHz, for the same reason the floor
         * is higher: on a plain crossfade the arriving track has no filter
         * gesture to explain itself with, so it has to sound like it fades in
         * normally. 700Hz clears the body of a voice while leaving its lower
         * harmonics, which is enough to stop it fighting the outgoing lead.
         *
         * Was 450Hz, which fit that description on paper and was mostly inaudible
         * in practice: a fifth of the way in it was already down to 268Hz, doing
         * nothing about a collision the vocal model had reported at full strength.
         * 700Hz is the corner a filter ride itself used to open at, so it is a
         * known-restrained one rather than a new guess — and keeping this style a
         * clear step below that one leaves the two ranked the way their tiers are.
         */
        const val VOCAL_SEPARATION_HIGH_PASS_HZ = 700.0

        /**
         * [ENTRY_HIGH_PASS_HZ]'s counterpart for a beat-matched blend: lower, and
         * briefer.
         *
         * Was 320Hz, which the bass swap almost entirely swallowed. The incoming
         * track is already high-passed at [BASS_SWAP_HZ] until the low end changes
         * hands and [rideBassSwap] takes whichever corner is higher, so a 320Hz
         * entry was only above that floor for the first sixth of the blend, and
         * only ever by a little. 520Hz gives the arriving track an entry gesture
         * that outlives the bass kill — clear of it until nearly three tenths in —
         * rather than one hiding inside it.
         */
        const val BLEND_ENTRY_HIGH_PASS_HZ = 520.0

        /** [ENTRY_OPEN_BY]'s counterpart for a beat-matched blend. */
        const val BLEND_ENTRY_OPEN_BY = 0.45

        /**
         * Where [BLEND_ENTRY_HIGH_PASS_HZ] and [BLEND_ENTRY_OPEN_BY] go at a full
         * vocal collision: a corner high enough to hold the arriving voice's body
         * out, held for most of the blend rather than a third of it.
         *
         * Still short of [ENTRY_HIGH_PASS_HZ]'s filter-ride treatment. The two
         * tracks are on a shared grid and meant to sound simultaneous; the aim is
         * to stop the two leads occupying one band, not to hide either of them.
         *
         * Tracks [BLEND_ENTRY_HIGH_PASS_HZ] upward — 620Hz to 950Hz — so how hard
         * the two are singing over each other stays the thing that separates a
         * marginal collision from a head-on one, rather than both converging on
         * whatever the bass kill was already doing.
         */
        const val BLEND_ENTRY_CLASH_HIGH_PASS_HZ = 950.0
        const val BLEND_ENTRY_CLASH_OPEN_BY = 0.7

        /** Where [BLEND_EXIT_FROM] and [BLEND_EXIT_LOW_PASS_HZ] go at a full collision. */
        const val BLEND_EXIT_CLASH_FROM = 0.12
        const val BLEND_EXIT_CLASH_LOW_PASS_HZ = 1_100.0

        /**
         * Where the outgoing track starts losing its top on a beat-matched
         * blend.
         *
         * Was 0.5, which left the outgoing track completely unfiltered for the
         * whole first half — the same "remembered late" complaint that
         * [FILTER_ENTRY_HZ] answers on a filter ride, in the one style where
         * both tracks are at their most similar and so most likely to clash.
         * Brought forward rather than to zero: a beat-matched blend is chosen
         * because the two tracks are meant to sound simultaneous, and opening
         * with the outgoing one already darkened would defeat that.
         */
        const val BLEND_EXIT_FROM = 0.3

        /**
         * Where that low-pass lands by the end of the blend. High enough that the
         * track is still plainly itself — this style is chosen for pairs meant to
         * sound simultaneous — and low enough to take the sibilance off a voice
         * that is leaving.
         */
        const val BLEND_EXIT_LOW_PASS_HZ = 2_200.0

        /**
         * Where in the blend the session moves to the incoming track: halfway,
         * which with an equal-power curve is the instant the two are equally
         * loud and the incoming one takes over.
         */
        const val HANDOFF_AT = 0.5f

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

    /**
     * Wall-clock slices of one deck build. Written on the decode thread and
     * read on the playback thread; the lock is the whole protocol.
     */
    private class DeckTiming(private val nowMs: () -> Long) {
        private val lock = Any()
        private val spans = ArrayList<LoopMix.PrepSpan>()
        private var openStep: String? = null
        private var openStarted = 0L
        // djmix15: NO_DEADLINE (paused/none), never 0L. 0L would subtract as
        // the boot uptime (~374000 ms on device) in lateStepReason.
        var deadlineMs: Long = DeckBudget.NO_DEADLINE_MS

        fun begin(step: String) = synchronized(lock) {
            val now = nowMs()
            closeOpen(now)
            openStep = step
            openStarted = now
        }

        fun endOpen() = synchronized(lock) { closeOpen(nowMs()) }

        fun endOpenIf(step: String) = synchronized(lock) {
            if (openStep == step) closeOpen(nowMs())
        }

        fun addClosed(step: String, startedMs: Long, endedMs: Long, deadline: Long) = synchronized(lock) {
            spans += LoopMix.PrepSpan(step, startedMs, endedMs, deadline)
        }

        fun clear() = synchronized(lock) {
            spans.clear()
            openStep = null
            // djmix15: drop the prior build's wall deadline too, so a stale
            // nowMs - deadlineMs can never read as this build's lateness
            // (374320 ms over, growing 1:1 with wall time).
            deadlineMs = DeckBudget.NO_DEADLINE_MS
        }

        fun snapshot(now: Long): List<LoopMix.PrepSpan> = synchronized(lock) {
            val closed = spans.toList()
            val step = openStep ?: return closed
            closed + LoopMix.PrepSpan(step, openStarted, now, deadlineMs)
        }

        private fun closeOpen(now: Long) {
            val step = openStep ?: return
            spans += LoopMix.PrepSpan(step, openStarted, now, deadlineMs)
            openStep = null
        }
    }
}
