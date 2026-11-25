package com.music.bitchord.playback

import com.music.bitchord.playback.djmix.CoordinatorDecisions
import com.music.bitchord.playback.djmix.BailFallbackHandler
import com.music.bitchord.playback.djmix.BailFallbackHandlerHost
import com.music.bitchord.playback.djmix.FadeExecutionDriver
import com.music.bitchord.playback.djmix.FadeExecutionDriverHost
import com.music.bitchord.playback.djmix.TickEngine
import com.music.bitchord.playback.djmix.TickEngineHost
import com.music.bitchord.playback.djmix.VisualFeedPublisher
import com.music.bitchord.playback.djmix.VisualFeedPublisherHost
import com.music.bitchord.playback.djmix.PrepCoordinator
import com.music.bitchord.playback.djmix.PrepCoordinatorHost
import com.music.bitchord.playback.djmix.PrepAnalysisCoordinator
import com.music.bitchord.playback.djmix.PrepAnalysisCoordinatorHost
import com.music.bitchord.playback.djmix.CoordinatorBindings
import com.music.bitchord.playback.djmix.CoordinatorBindingsHost
import com.music.bitchord.playback.djmix.STUCK_BUILD_GUARD_MS
import com.music.bitchord.playback.djmix.HARD_WATCHDOG_MIN_EOF_MS
import com.music.bitchord.playback.djmix.HANDOFF_GUARD_MS
import com.music.bitchord.playback.djmix.ANCHOR_TOLERANCE_NANOS
import com.music.bitchord.playback.djmix.IDLE_STEP_MS
import com.music.bitchord.playback.djmix.ARM_STEP_MS
import com.music.bitchord.playback.djmix.FADE_STEP_MS
import com.music.bitchord.playback.djmix.HALF_TICK_NANOS
import com.music.bitchord.playback.djmix.MAX_EXTRAPOLATION_NANOS
import com.music.bitchord.playback.djmix.BAIL_STEP_MS
import com.music.bitchord.playback.djmix.LOOP_STEP_MS
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.djmix.core.contracts.DjLevel as CoreDjLevel
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixMachineConfig
import com.music.bitchord.djmix.core.machine.PairKey
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.djmix.DeckTiming
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
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.AutomixStatus
import com.music.bitchord.playback.smart.MixInspectorFeed
import com.music.bitchord.playback.smart.MixInspectorKey
import com.music.bitchord.playback.smart.MixInspectorRemixAction
import com.music.bitchord.playback.smart.SeekAction
import com.music.bitchord.playback.smart.MixWaveform
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.deckSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.max
import kotlin.math.roundToLong


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

internal fun nativePlanAfterCrown(
    crown: LoopMix.Plan?,
    classic: TransitionPlan,
    out: TrackAnalysis,
    inn: TrackAnalysis,
    committedNative: Boolean,
    lyricsOut: List<LyricCue> = emptyList(),
    lyricsIn: List<LyricCue> = emptyList(),
    bridge: () -> LoopMix.Plan?,
): LoopMix.Plan? = CoordinatorDecisions.nativePlanAfterCrown(crown, classic, out, inn, committedNative, lyricsOut, lyricsIn, bridge)

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
internal const val ADAPTIVE_PRIME_LEAD_MAX_MS = com.music.bitchord.playback.djmix.ADAPTIVE_PRIME_LEAD_MAX_MS
internal const val ADAPTIVE_PRIME_LEAD_MIN_MS = com.music.bitchord.playback.djmix.ADAPTIVE_PRIME_LEAD_MIN_MS

internal fun adaptivePrimeLeadMs(nextStreamReady: Boolean, blendArmLeadMs: Long): Long = CoordinatorDecisions.adaptivePrimeLeadMs(nextStreamReady, blendArmLeadMs)

internal fun shouldYieldColdLoopForContentEnd(
    contentEndMs: Long,
    currentPositionMs: Long,
    outBpm: Double,
    earliestExitMs: Long?,
): Boolean = CoordinatorDecisions.shouldYieldColdLoopForContentEnd(contentEndMs, currentPositionMs, outBpm, earliestExitMs)

internal fun handoverTargetSec(
    heardInSec: Double?,
    clockHandoverSec: Double,
    innLoopStartSec: Double,
): Double = CoordinatorDecisions.handoverTargetSec(heardInSec, clockHandoverSec, innLoopStartSec)

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
internal const val FADING_STALL_BAIL_MS = com.music.bitchord.playback.djmix.FADING_STALL_BAIL_MS

internal fun fadingStallShouldBail(
    stalledMs: Long,
    progressAtLeastOne: Boolean,
    incomingReady: Boolean,
    outAboutToEof: Boolean,
    incomingBuffering: Boolean,
): Boolean = CoordinatorDecisions.fadingStallShouldBail(stalledMs, progressAtLeastOne, incomingReady, outAboutToEof, incomingBuffering)

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

internal fun isClassicMarkable(
    blocked: Boolean,
    markerVisible: Boolean,
    durationMs: Long,
    currentState: TrackAnalysisState,
    nextState: TrackAnalysisState,
    reason: String,
    alreadyCommittedClassic: Boolean,
    fallbackAdopted: Boolean,
): Boolean = CoordinatorDecisions.isClassicMarkable(blocked, markerVisible, durationMs, currentState, nextState, reason, alreadyCommittedClassic, fallbackAdopted)

internal fun shouldSkipStemNearSplice(
    remainingToSpliceMs: Long,
    deadlineMs: Long,
    nowMs: Long,
): Boolean = CoordinatorDecisions.shouldSkipStemNearSplice(remainingToSpliceMs, deadlineMs, nowMs)

internal fun shouldSkipStemAtLaunch(
    remainingToSpliceMs: Long,
    deadlineMs: Long,
    nowMs: Long,
    analysisBusy: Boolean,
    armWhenReady: Boolean,
): Boolean = CoordinatorDecisions.shouldSkipStemAtLaunch(remainingToSpliceMs, deadlineMs, nowMs, analysisBusy, armWhenReady)

internal fun automixTerminalOutcome(
    isTerminal: Boolean,
    executed: Boolean,
    handedOff: Boolean,
    reason: String,
): String = CoordinatorDecisions.automixTerminalOutcome(isTerminal, executed, handedOff, reason)

internal fun shouldRunPhrasePlannerForBridge(
    currentAnalysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
): Boolean = CoordinatorDecisions.shouldRunPhrasePlannerForBridge(currentAnalysis, nextAnalysis)

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
    /** Production supplies the application owner; omitted only by isolated controller tests. */
    private val djmixServices: com.music.bitchord.playback.djmix.DjmixServices? = null,
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

    private val coordinatorHost by lazy(LazyThreadSafetyMode.NONE) {
        object : BailFallbackHandlerHost, FadeExecutionDriverHost, TickEngineHost, VisualFeedPublisherHost, PrepCoordinatorHost, PrepAnalysisCoordinatorHost, CoordinatorBindingsHost {
            override val active: () -> ExoPlayer
                get() = this@CrossfadeController.active
            override var activeMachineGeneration: Long
                get() = this@CrossfadeController.activeMachineGeneration
                set(value) { this@CrossfadeController.activeMachineGeneration = value }
            override var analysisCollectorJob: Job?
                get() = this@CrossfadeController.analysisCollectorJob
                set(value) { this@CrossfadeController.analysisCollectorJob = value }
            override val analysisFor: (MediaItem) -> TrackAnalysis
                get() = this@CrossfadeController.analysisFor
            override var analysisReplanJob: Job?
                get() = this@CrossfadeController.analysisReplanJob
                set(value) { this@CrossfadeController.analysisReplanJob = value }
            override val analysisRunningFor: (MediaItem) -> Boolean
                get() = this@CrossfadeController.analysisRunningFor
            override val analysisUpdates: SharedFlow<String>
                get() = this@CrossfadeController.analysisUpdates
            override var armDeadline: Long
                get() = this@CrossfadeController.armDeadline
                set(value) { this@CrossfadeController.armDeadline = value }
            override var armGraceUsed: Boolean
                get() = this@CrossfadeController.armGraceUsed
                set(value) { this@CrossfadeController.armGraceUsed = value }
            override var armOutgoingPreFiltered: Boolean
                get() = this@CrossfadeController.armOutgoingPreFiltered
                set(value) { this@CrossfadeController.armOutgoingPreFiltered = value }
            override var bailFromGain: Float
                get() = this@CrossfadeController.bailFromGain
                set(value) { this@CrossfadeController.bailFromGain = value }
            override var bailStartedAt: Long
                get() = this@CrossfadeController.bailStartedAt
                set(value) { this@CrossfadeController.bailStartedAt = value }
            override var blendArmLeadMs: Long
                get() = this@CrossfadeController.blendArmLeadMs
                set(value) { this@CrossfadeController.blendArmLeadMs = value }
            override var buildGeneration: Int
                get() = this@CrossfadeController.buildGeneration
                set(value) { this@CrossfadeController.buildGeneration = value }
            override var capturedLoopTookOver: Boolean?
                get() = this@CrossfadeController.capturedLoopTookOver
                set(value) { this@CrossfadeController.capturedLoopTookOver = value }
            override var capturedTakeoverSpliceBufferStatus: LoopMixPort.TakeoverSpliceBufferStatus?
                get() = this@CrossfadeController.capturedTakeoverSpliceBufferStatus
                set(value) { this@CrossfadeController.capturedTakeoverSpliceBufferStatus = value }
            override var classicArmLeadMs: Long
                get() = this@CrossfadeController.classicArmLeadMs
                set(value) { this@CrossfadeController.classicArmLeadMs = value }
            override var classicFallbackReason: String
                get() = this@CrossfadeController.classicFallbackReason
                set(value) { this@CrossfadeController.classicFallbackReason = value }
            override var classicHandledGeneration: Long
                get() = this@CrossfadeController.classicHandledGeneration
                set(value) { this@CrossfadeController.classicHandledGeneration = value }
            override var classicPrepStartMs: Long
                get() = this@CrossfadeController.classicPrepStartMs
                set(value) { this@CrossfadeController.classicPrepStartMs = value }
            override val countdownStabilizer: CountdownStabilizer
                get() = this@CrossfadeController.countdownStabilizer
            override var deckSide: String
                get() = this@CrossfadeController.deckSide
                set(value) { this@CrossfadeController.deckSide = value }
            override val deckTiming: DeckTiming
                get() = this@CrossfadeController.deckTiming
            override var deferredHandoffLogged: Boolean
                get() = this@CrossfadeController.deferredHandoffLogged
                set(value) { this@CrossfadeController.deferredHandoffLogged = value }
            override var executedRoute: Map<String, Any?>?
                get() = this@CrossfadeController.executedRoute
                set(value) { this@CrossfadeController.executedRoute = value }
            override val fadeCurves: FadeRideCurves
                get() = this@CrossfadeController.fadeCurves
            override var fadeEndMs: Long
                get() = this@CrossfadeController.fadeEndMs
                set(value) { this@CrossfadeController.fadeEndMs = value }
            override var fadeMs: Long
                get() = this@CrossfadeController.fadeMs
                set(value) { this@CrossfadeController.fadeMs = value }
            override var fadeOutBudgetMs: Long
                get() = this@CrossfadeController.fadeOutBudgetMs
                set(value) { this@CrossfadeController.fadeOutBudgetMs = value }
            override var fadeOutOriginMs: Long
                get() = this@CrossfadeController.fadeOutOriginMs
                set(value) { this@CrossfadeController.fadeOutOriginMs = value }
            override var fadeStartedAt: Long
                get() = this@CrossfadeController.fadeStartedAt
                set(value) { this@CrossfadeController.fadeStartedAt = value }
            override var fadingStallSinceMs: Long
                get() = this@CrossfadeController.fadingStallSinceMs
                set(value) { this@CrossfadeController.fadingStallSinceMs = value }
            override var fallbackNaturalGeneration: Long
                get() = this@CrossfadeController.fallbackNaturalGeneration
                set(value) { this@CrossfadeController.fallbackNaturalGeneration = value }
            override val filters: TransitionFilters
                get() = this@CrossfadeController.filters
            override var handedOff: Boolean
                get() = this@CrossfadeController.handedOff
                set(value) { this@CrossfadeController.handedOff = value }
            override var handoverExpectedMs: Long
                get() = this@CrossfadeController.handoverExpectedMs
                set(value) { this@CrossfadeController.handoverExpectedMs = value }
            override var handoverHeardMs: Long
                get() = this@CrossfadeController.handoverHeardMs
                set(value) { this@CrossfadeController.handoverHeardMs = value }
            override var handoverTargetMs: Long
                get() = this@CrossfadeController.handoverTargetMs
                set(value) { this@CrossfadeController.handoverTargetMs = value }
            override var hardWatchdogCooldownUntil: Long
                get() = this@CrossfadeController.hardWatchdogCooldownUntil
                set(value) { this@CrossfadeController.hardWatchdogCooldownUntil = value }
            override var hardWatchdogTriggeredPair: String?
                get() = this@CrossfadeController.hardWatchdogTriggeredPair
                set(value) { this@CrossfadeController.hardWatchdogTriggeredPair = value }
            override var heldAtEnd: ExoPlayer?
                get() = this@CrossfadeController.heldAtEnd
                set(value) { this@CrossfadeController.heldAtEnd = value }
            override var holdWanted: Boolean
                get() = this@CrossfadeController.holdWanted
                set(value) { this@CrossfadeController.holdWanted = value }
            override var idleStallPrepared: Boolean
                get() = this@CrossfadeController.idleStallPrepared
                set(value) { this@CrossfadeController.idleStallPrepared = value }
            override var idleStallSinceMs: Long
                get() = this@CrossfadeController.idleStallSinceMs
                set(value) { this@CrossfadeController.idleStallSinceMs = value }
            override val inClock: PositionClock
                get() = this@CrossfadeController.inClock
            override var inFallbackReplan: Boolean
                get() = this@CrossfadeController.inFallbackReplan
                set(value) { this@CrossfadeController.inFallbackReplan = value }
            override var incoming: ExoPlayer?
                get() = this@CrossfadeController.incoming
                set(value) { this@CrossfadeController.incoming = value }
            override var incomingAnalysis: TrackAnalysis
                get() = this@CrossfadeController.incomingAnalysis
                set(value) { this@CrossfadeController.incomingAnalysis = value }
            override var incomingCueTimeMs: Long
                get() = this@CrossfadeController.incomingCueTimeMs
                set(value) { this@CrossfadeController.incomingCueTimeMs = value }
            override var incomingPlaybackRate: Double
                get() = this@CrossfadeController.incomingPlaybackRate
                set(value) { this@CrossfadeController.incomingPlaybackRate = value }
            override var initiatingExitReason: String?
                get() = this@CrossfadeController.initiatingExitReason
                set(value) { this@CrossfadeController.initiatingExitReason = value }
            override var internalHandoverSeek: Boolean
                get() = this@CrossfadeController.internalHandoverSeek
                set(value) { this@CrossfadeController.internalHandoverSeek = value }
            override var lastArmDeathKey: String?
                get() = this@CrossfadeController.lastArmDeathKey
                set(value) { this@CrossfadeController.lastArmDeathKey = value }
            override var lastCompleteReason: String?
                get() = this@CrossfadeController.lastCompleteReason
                set(value) { this@CrossfadeController.lastCompleteReason = value }
            override var lastDispatchedPairKey: PairKey?
                get() = this@CrossfadeController.lastDispatchedPairKey
                set(value) { this@CrossfadeController.lastDispatchedPairKey = value }
            override var lastEmittedPlanVerdict: String?
                get() = this@CrossfadeController.lastEmittedPlanVerdict
                set(value) { this@CrossfadeController.lastEmittedPlanVerdict = value }
            override var lastFallbackLogKey: String?
                get() = this@CrossfadeController.lastFallbackLogKey
                set(value) { this@CrossfadeController.lastFallbackLogKey = value }
            override var lastFireFallbackReason: String
                get() = this@CrossfadeController.lastFireFallbackReason
                set(value) { this@CrossfadeController.lastFireFallbackReason = value }
            override var lastFireFxCount: Int
                get() = this@CrossfadeController.lastFireFxCount
                set(value) { this@CrossfadeController.lastFireFxCount = value }
            override var lastFireMove: String
                get() = this@CrossfadeController.lastFireMove
                set(value) { this@CrossfadeController.lastFireMove = value }
            override var lastFirePath: String
                get() = this@CrossfadeController.lastFirePath
                set(value) { this@CrossfadeController.lastFirePath = value }
            override var lastFireStems: Int
                get() = this@CrossfadeController.lastFireStems
                set(value) { this@CrossfadeController.lastFireStems = value }
            override var lastFreezeLogKey: String?
                get() = this@CrossfadeController.lastFreezeLogKey
                set(value) { this@CrossfadeController.lastFreezeLogKey = value }
            override var lastHandoffCompleteAtMs: Long
                get() = this@CrossfadeController.lastHandoffCompleteAtMs
                set(value) { this@CrossfadeController.lastHandoffCompleteAtMs = value }
            override var lastLoopAnnounceAt: Long
                get() = this@CrossfadeController.lastLoopAnnounceAt
                set(value) { this@CrossfadeController.lastLoopAnnounceAt = value }
            override var lastLoopBeat: Int
                get() = this@CrossfadeController.lastLoopBeat
                set(value) { this@CrossfadeController.lastLoopBeat = value }
            override var lastLoopPositionMs: Long
                get() = this@CrossfadeController.lastLoopPositionMs
                set(value) { this@CrossfadeController.lastLoopPositionMs = value }
            override var lastLoopVerdict: String
                get() = this@CrossfadeController.lastLoopVerdict
                set(value) { this@CrossfadeController.lastLoopVerdict = value }
            override var lastPairAnalysisSignature: String?
                get() = this@CrossfadeController.lastPairAnalysisSignature
                set(value) { this@CrossfadeController.lastPairAnalysisSignature = value }
            override var lastPlayWhenReadyChangeReason: Int
                get() = this@CrossfadeController.lastPlayWhenReadyChangeReason
                set(value) { this@CrossfadeController.lastPlayWhenReadyChangeReason = value }
            override var lastRejectedLogKey: String?
                get() = this@CrossfadeController.lastRejectedLogKey
                set(value) { this@CrossfadeController.lastRejectedLogKey = value }
            override var lastSmartPlan: TransitionPlan?
                get() = this@CrossfadeController.lastSmartPlan
                set(value) { this@CrossfadeController.lastSmartPlan = value }
            override var lastSmartPlanStartMs: Long
                get() = this@CrossfadeController.lastSmartPlanStartMs
                set(value) { this@CrossfadeController.lastSmartPlanStartMs = value }
            override var lastTimingPair: Pair<String, String>?
                get() = this@CrossfadeController.lastTimingPair
                set(value) { this@CrossfadeController.lastTimingPair = value }
            override var listeningTo: ExoPlayer?
                get() = this@CrossfadeController.listeningTo
                set(value) { this@CrossfadeController.listeningTo = value }
            override var localBlendInFlight: Boolean
                get() = this@CrossfadeController.localBlendInFlight
                set(value) { this@CrossfadeController.localBlendInFlight = value }
            override var loopAlignJob: Job?
                get() = this@CrossfadeController.loopAlignJob
                set(value) { this@CrossfadeController.loopAlignJob = value }
            override var loopBuildJob: Job?
                get() = this@CrossfadeController.loopBuildJob
                set(value) { this@CrossfadeController.loopBuildJob = value }
            override var loopBuildStartedAtMs: Long
                get() = this@CrossfadeController.loopBuildStartedAtMs
                set(value) { this@CrossfadeController.loopBuildStartedAtMs = value }
            override var loopCleanupContendedSession: LoopMixSession?
                get() = this@CrossfadeController.loopCleanupContendedSession
                set(value) { this@CrossfadeController.loopCleanupContendedSession = value }
            override var loopCleanupContendedSinceNanos: Long
                get() = this@CrossfadeController.loopCleanupContendedSinceNanos
                set(value) { this@CrossfadeController.loopCleanupContendedSinceNanos = value }
            override var loopInAnalysis: TrackAnalysis?
                get() = this@CrossfadeController.loopInAnalysis
                set(value) { this@CrossfadeController.loopInAnalysis = value }
            override var loopInDurationMs: Long
                get() = this@CrossfadeController.loopInDurationMs
                set(value) { this@CrossfadeController.loopInDurationMs = value }
            override var loopInItem: MediaItem?
                get() = this@CrossfadeController.loopInItem
                set(value) { this@CrossfadeController.loopInItem = value }
            override var loopIncomingStarted: Boolean
                get() = this@CrossfadeController.loopIncomingStarted
                set(value) { this@CrossfadeController.loopIncomingStarted = value }
            override var loopOutAnalysis: TrackAnalysis?
                get() = this@CrossfadeController.loopOutAnalysis
                set(value) { this@CrossfadeController.loopOutAnalysis = value }
            override var loopOutDurationMs: Long
                get() = this@CrossfadeController.loopOutDurationMs
                set(value) { this@CrossfadeController.loopOutDurationMs = value }
            override var loopOutItem: MediaItem?
                get() = this@CrossfadeController.loopOutItem
                set(value) { this@CrossfadeController.loopOutItem = value }
            override var loopPlanKey: String?
                get() = this@CrossfadeController.loopPlanKey
                set(value) { this@CrossfadeController.loopPlanKey = value }
            override var loopPlanned: LoopMix.Plan?
                get() = this@CrossfadeController.loopPlanned
                set(value) { this@CrossfadeController.loopPlanned = value }
            override val loopPortOf: (ExoPlayer) -> LoopMixPort?
                get() = this@CrossfadeController.loopPortOf
            override var loopRefusal: String?
                get() = this@CrossfadeController.loopRefusal
                set(value) { this@CrossfadeController.loopRefusal = value }
            override val loopRegion: (MediaItem, Long, Double, Double, Int) -> LoopRegion?
                get() = this@CrossfadeController.loopRegion
            override val loopRegionCached: (String, Int, Double, Double) -> Boolean
                get() = this@CrossfadeController.loopRegionCached
            override var loopScript: PhraseScript?
                get() = this@CrossfadeController.loopScript
                set(value) { this@CrossfadeController.loopScript = value }
            override var loopSession: LoopMixSession?
                get() = this@CrossfadeController.loopSession
                set(value) { this@CrossfadeController.loopSession = value }
            override var loopSungIn: List<LoopMix.Span>?
                get() = this@CrossfadeController.loopSungIn
                set(value) { this@CrossfadeController.loopSungIn = value }
            override var loopSungOut: List<LoopMix.Span>?
                get() = this@CrossfadeController.loopSungOut
                set(value) { this@CrossfadeController.loopSungOut = value }
            override var loopSuppressedPair: String?
                get() = this@CrossfadeController.loopSuppressedPair
                set(value) { this@CrossfadeController.loopSuppressedPair = value }
            override var loopSuppressedUntil: Long
                get() = this@CrossfadeController.loopSuppressedUntil
                set(value) { this@CrossfadeController.loopSuppressedUntil = value }
            override var loopTarget: LoopMix.Plan?
                get() = this@CrossfadeController.loopTarget
                set(value) { this@CrossfadeController.loopTarget = value }
            override var loopTargetStartMs: Long
                get() = this@CrossfadeController.loopTargetStartMs
                set(value) { this@CrossfadeController.loopTargetStartMs = value }
            override val loudnessGain: (String) -> Float
                get() = this@CrossfadeController.loudnessGain
            override val lyricSpans: java.util.concurrent.ConcurrentHashMap<String, LyricSpans>
                get() = this@CrossfadeController.lyricSpans
            override val lyricsAsked: MutableSet<String>
                get() = this@CrossfadeController.lyricsAsked
            override val lyricsAskedAt: java.util.concurrent.ConcurrentHashMap<String, Long>
                get() = this@CrossfadeController.lyricsAskedAt
            override val mediaRatio: Double
                get() = this@CrossfadeController.mediaRatio
            override val mixExecutor: MixExecutor
                get() = this@CrossfadeController.mixExecutor
            override val mixWaveform: (MediaItem, Long, Double, Double, Int) -> MixWaveform.Bands?
                get() = this@CrossfadeController.mixWaveform
            override var nativeBinding: NativeBinding?
                get() = this@CrossfadeController.nativeBinding
                set(value) { this@CrossfadeController.nativeBinding = value }
            override var naturalProvisionalUntilPosMs: Long
                get() = this@CrossfadeController.naturalProvisionalUntilPosMs
                set(value) { this@CrossfadeController.naturalProvisionalUntilPosMs = value }
            override val nextStreamReady: (MediaItem) -> Boolean
                get() = this@CrossfadeController.nextStreamReady
            override val nowMs: () -> Long
                get() = this@CrossfadeController.nowMs
            override val onArmAutoMixIncoming: (MediaItem) -> Unit
                get() = this@CrossfadeController.onArmAutoMixIncoming
            override val onArmIncoming: (MediaItem, String?) -> Unit
                get() = this@CrossfadeController.onArmIncoming
            override val onBlendHeadroom: (Float) -> Unit
                get() = this@CrossfadeController.onBlendHeadroom
            override val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit
                get() = this@CrossfadeController.onHandoff
            override val onMixPositionJump: (wrapped: Boolean) -> Unit
                get() = this@CrossfadeController.onMixPositionJump
            override val onSettled: () -> Unit
                get() = this@CrossfadeController.onSettled
            override var opportunity: Opportunity?
                get() = this@CrossfadeController.opportunity
                set(value) { this@CrossfadeController.opportunity = value }
            override val outClock: PositionClock
                get() = this@CrossfadeController.outClock
            override var outgoing: ExoPlayer?
                get() = this@CrossfadeController.outgoing
                set(value) { this@CrossfadeController.outgoing = value }
            override var outgoingAnalysis: TrackAnalysis
                get() = this@CrossfadeController.outgoingAnalysis
                set(value) { this@CrossfadeController.outgoingAnalysis = value }
            override var outgoingItemIndex: Int
                get() = this@CrossfadeController.outgoingItemIndex
                set(value) { this@CrossfadeController.outgoingItemIndex = value }
            override var outgoingPlaybackRate: Double
                get() = this@CrossfadeController.outgoingPlaybackRate
                set(value) { this@CrossfadeController.outgoingPlaybackRate = value }
            override val pairPreparer: com.music.bitchord.playback.djmix.PairPreparer?
                get() = this@CrossfadeController.pairPreparer
            override val phaseName: String
                get() = this@CrossfadeController.phase.name
            override var pinnedCatchUpKey: String?
                get() = this@CrossfadeController.pinnedCatchUpKey
                set(value) { this@CrossfadeController.pinnedCatchUpKey = value }
            override var pinnedCatchUpPlan: TransitionPlan?
                get() = this@CrossfadeController.pinnedCatchUpPlan
                set(value) { this@CrossfadeController.pinnedCatchUpPlan = value }
            override var planGeneratedAtMs: Long
                get() = this@CrossfadeController.planGeneratedAtMs
                set(value) { this@CrossfadeController.planGeneratedAtMs = value }
            override var planGeneration: Long
                get() = this@CrossfadeController.planGeneration
                set(value) { this@CrossfadeController.planGeneration = value }
            override var planInFlight: TransitionPlan?
                get() = this@CrossfadeController.planInFlight
                set(value) { this@CrossfadeController.planInFlight = value }
            override var planResultGeneration: Long
                get() = this@CrossfadeController.planResultGeneration
                set(value) { this@CrossfadeController.planResultGeneration = value }
            override var planRevision: Int
                get() = this@CrossfadeController.planRevision
                set(value) { this@CrossfadeController.planRevision = value }
            override var plannedNextId: String?
                get() = this@CrossfadeController.plannedNextId
                set(value) { this@CrossfadeController.plannedNextId = value }
            override var plannedTransition: Pair<String, Long>?
                get() = this@CrossfadeController.plannedTransition
                set(value) { this@CrossfadeController.plannedTransition = value }
            override var primeJob: Job?
                get() = this@CrossfadeController.primeJob
                set(value) { this@CrossfadeController.primeJob = value }
            override var primeRetryAt: Long
                get() = this@CrossfadeController.primeRetryAt
                set(value) { this@CrossfadeController.primeRetryAt = value }
            override var primedKey: String?
                get() = this@CrossfadeController.primedKey
                set(value) { this@CrossfadeController.primedKey = value }
            override var primedSession: LoopMixSession?
                get() = this@CrossfadeController.primedSession
                set(value) { this@CrossfadeController.primedSession = value }
            override var queuedItemCount: Int
                get() = this@CrossfadeController.queuedItemCount
                set(value) { this@CrossfadeController.queuedItemCount = value }
            override var render: RideSpec
                get() = this@CrossfadeController.render.rideSpec
                set(value) { this@CrossfadeController.render = Render(value) }
            override var replanning: Boolean
                get() = this@CrossfadeController.replanning
                set(value) { this@CrossfadeController.replanning = value }
            override val requestAnalysis: (MediaItem, Long) -> Unit
                get() = this@CrossfadeController.requestAnalysis
            override val routingEvidenceKeys: MutableSet<String>
                get() = this@CrossfadeController.routingEvidenceKeys
            override var routingFallback: Boolean
                get() = this@CrossfadeController.routingFallback
                set(value) { this@CrossfadeController.routingFallback = value }
            override val scope: CoroutineScope
                get() = this@CrossfadeController.scope
            override var scrubEpoch: Long
                get() = this@CrossfadeController.scrubEpoch
                set(value) { this@CrossfadeController.scrubEpoch = value }
            override var scrubSettledAtMs: Long
                get() = this@CrossfadeController.scrubSettledAtMs
                set(value) { this@CrossfadeController.scrubSettledAtMs = value }
            override var settledAt: Long
                get() = this@CrossfadeController.settledAt
                set(value) { this@CrossfadeController.settledAt = value }
            override var shownCountdownKey: String?
                get() = this@CrossfadeController.shownCountdownKey
                set(value) { this@CrossfadeController.shownCountdownKey = value }
            override var shownLoopStartMs: Long
                get() = this@CrossfadeController.shownLoopStartMs
                set(value) { this@CrossfadeController.shownLoopStartMs = value }
            override var smartFadeActive: Boolean
                get() = this@CrossfadeController.smartFadeActive
                set(value) { this@CrossfadeController.smartFadeActive = value }
            override val standby: () -> ExoPlayer
                get() = this@CrossfadeController.standby
            override var straightPreparedPair: com.music.bitchord.playback.djmix.PreparedPair?
                get() = this@CrossfadeController.straightPreparedPair
                set(value) { this@CrossfadeController.straightPreparedPair = value }
            override var suppressScrubUntilMs: Long
                get() = this@CrossfadeController.suppressScrubUntilMs
                set(value) { this@CrossfadeController.suppressScrubUntilMs = value }
            override var survivorRecoveryPending: Boolean
                get() = this@CrossfadeController.survivorRecoveryPending
                set(value) { this@CrossfadeController.survivorRecoveryPending = value }
            override val syncedLines: suspend (MediaItem, Long) -> List<LyricLine>?
                get() = this@CrossfadeController.syncedLines
            override var tailDurationMs: Long
                get() = this@CrossfadeController.tailDurationMs
                set(value) { this@CrossfadeController.tailDurationMs = value }
            override var tailFromGain: Float
                get() = this@CrossfadeController.tailFromGain
                set(value) { this@CrossfadeController.tailFromGain = value }
            override var tailLoopSession: LoopMixSession?
                get() = this@CrossfadeController.tailLoopSession
                set(value) { this@CrossfadeController.tailLoopSession = value }
            override var tailStartMs: Long
                get() = this@CrossfadeController.tailStartMs
                set(value) { this@CrossfadeController.tailStartMs = value }
            override var tailTarget: ExoPlayer?
                get() = this@CrossfadeController.tailTarget
                set(value) { this@CrossfadeController.tailTarget = value }
            override val tempoEngine: EaseTempoEngine
                get() = this@CrossfadeController.tempoEngine
            override var terminalRecorded: Boolean
                get() = this@CrossfadeController.terminalRecorded
                set(value) { this@CrossfadeController.terminalRecorded = value }
            override var tickerJob: Job?
                get() = this@CrossfadeController.tickerJob
                set(value) { this@CrossfadeController.tickerJob = value }
            override val tracks: TransitionFilters
                get() = this@CrossfadeController.tracks
            override var uiStateJob: Job?
                get() = this@CrossfadeController.uiStateJob
                set(value) { this@CrossfadeController.uiStateJob = value }
            override val versionSwapActive: () -> Boolean
                get() = this@CrossfadeController.versionSwapActive
            override var visualCurrentIndex: Int
                get() = this@CrossfadeController.visualCurrentIndex
                set(value) { this@CrossfadeController.visualCurrentIndex = value }
            override var visualGeneration: Long?
                get() = this@CrossfadeController.visualGeneration
                set(value) { this@CrossfadeController.visualGeneration = value }
            override var visualQueue: List<MediaItem>
                get() = this@CrossfadeController.visualQueue
                set(value) { this@CrossfadeController.visualQueue = value }
            override var watchdogFired: Boolean
                get() = this@CrossfadeController.watchdogFired
                set(value) { this@CrossfadeController.watchdogFired = value }
            override val yieldAnalysis: () -> Unit
                get() = this@CrossfadeController.yieldAnalysis
            override fun acceptRoute(plan: Any, fields: Map<String, Any?>): Unit = this@CrossfadeController.acceptRoute(plan, fields)
            override fun appSettingsMarkerShown(): Boolean = this@CrossfadeController.appSettingsMarkerShown()
            override fun armAutoMixVisual(out: ExoPlayer, into: ExoPlayer, nextIndex: Int, items: List<MediaItem>): Unit = this@CrossfadeController.armAutoMixVisual(out, into, nextIndex, items)
            override fun armDeath(reason: String): Boolean = this@CrossfadeController.armDeath(reason)
            override fun armLoop(session: LoopMixSession, out: ExoPlayer, into: ExoPlayer, nextIndex: Int, outPort: LoopMixPort, inPort: LoopMixPort): Unit = this@CrossfadeController.armLoop(session, out, into, nextIndex, outPort, inPort)
            override fun askLyrics(item: MediaItem, durationMs: Long): Unit = this@CrossfadeController.askLyrics(item, durationMs)
            override fun bail(force: Boolean): Unit = this@CrossfadeController.bail(force)
            override fun begin(fade: Long, endMs: Long, smart: Boolean, cueTimeMs: Long, playbackRate: Double, outgoingRate: Double, beatMs: Long, renderStyle: RideSpec, plan: TransitionPlan?): Boolean = this@CrossfadeController.begin(fade, endMs, smart, cueTimeMs, playbackRate, outgoingRate, beatMs, Render(renderStyle), plan)
            override fun beginSleepFadeOut(fade: Long): Unit = this@CrossfadeController.beginSleepFadeOut(fade)
            override fun beginTailOrFinish(out: ExoPlayer, reason: String, retireSite: String): Unit = this@CrossfadeController.beginTailOrFinish(out, reason, retireSite)
            override fun cancelArming(reason: String): Unit = this@CrossfadeController.cancelArming(reason)
            override fun cancelAutoMixVisual(): Unit = this@CrossfadeController.cancelAutoMixVisual()
            override fun captureLoopExitObservation(session: LoopMixSession): Unit = this@CrossfadeController.captureLoopExitObservation(session)
            override fun checkHardWatchdog(): Unit = this@CrossfadeController.checkHardWatchdog()
            override fun classicActiveFxNames(r: RideSpec): List<String> = this@CrossfadeController.classicActiveFxNames(Render(r))
            override fun classicFallbackOnRendererUnavailable(player: ExoPlayer, currentItem: MediaItem, nextItem: MediaItem, duration: Long, nextDuration: Long, reason: String, markerOnly: Boolean, failedPlan: TransitionPlanV1?): Unit = this@CrossfadeController.classicFallbackOnRendererUnavailable(player, currentItem, nextItem, duration, nextDuration, reason, markerOnly, failedPlan)
            override fun classicRouteFields(plan: TransitionPlan?): Map<String, Any?> = this@CrossfadeController.classicRouteFields(plan)
            override fun clearAutoMixVisual(): Unit = this@CrossfadeController.clearAutoMixVisual()
            override fun clearMixView(): Unit = this@CrossfadeController.clearMixView()
            override fun closeLater(renderer: LoopMixRenderer): Unit = this@CrossfadeController.closeLater(renderer)
            override fun configuredFadeMs(): Long = this@CrossfadeController.configuredFadeMs()
            override fun considerSmartTransition(duration: Long, markerOnly: Boolean): Unit = this@CrossfadeController.considerSmartTransition(duration, markerOnly)
            override fun disarmTailLoopSession(): Unit = this@CrossfadeController.disarmTailLoopSession()
            override fun discardPrime(): Unit = this@CrossfadeController.discardPrime()
            override fun dispatchPhaseTick() = when (this@CrossfadeController.phase) {
                Phase.IDLE -> this@CrossfadeController.considerAutoTransition()
                Phase.ARMING -> this@CrossfadeController.driveArming()
                Phase.FADING -> this@CrossfadeController.driveFade()
                Phase.TAIL -> this@CrossfadeController.driveTail()
                Phase.SLEEP_FADE -> this@CrossfadeController.driveSleepFade()
                Phase.BAILING -> this@CrossfadeController.driveBail()
                Phase.LOOP_ARMING -> this@CrossfadeController.driveLoopArming()
                Phase.LOOP_RUNNING -> this@CrossfadeController.driveLoopMix()
            }
            override fun driveArming(): Unit = this@CrossfadeController.driveArming()
            override fun ensureOpportunity(outId: String, inId: String): Opportunity = this@CrossfadeController.ensureOpportunity(outId, inId)
            override fun expireIdleOpportunity(reason: String): Unit = this@CrossfadeController.expireIdleOpportunity(reason)
            override fun expireMixPreview(): Unit = this@CrossfadeController.expireMixPreview()
            override fun fadeFor(duration: Long): Long = this@CrossfadeController.fadeFor(duration)
            override fun finish(): Unit = this@CrossfadeController.finish()
            override fun finishLoop(): Unit = this@CrossfadeController.finishLoop()
            override fun frozenPreparedFor(outItem: MediaItem, inItem: MediaItem, generation: Long, sampleRate: Int, level: com.music.bitchord.playback.smart.choreo.DjLevel): PreparedPair? = this@CrossfadeController.frozenPreparedFor(outItem, inItem, generation, sampleRate, level)
            override fun handOff(out: ExoPlayer, into: ExoPlayer, visualAudioSeconds: Double?, visualEndSeconds: Double?): Unit = this@CrossfadeController.handOff(out, into, visualAudioSeconds, visualEndSeconds)
            override fun holdAhead(player: ExoPlayer, duration: Long): Unit = this@CrossfadeController.holdAhead(player, duration)
            override fun holdAtEnd(out: ExoPlayer): Unit = this@CrossfadeController.holdAtEnd(out)
            override fun isArming(): Boolean = this@CrossfadeController.phase == Phase.ARMING
            override fun isBailing(): Boolean = this@CrossfadeController.phase == Phase.BAILING
            override fun isFading(): Boolean = this@CrossfadeController.phase == Phase.FADING
            override fun isIdle(): Boolean = this@CrossfadeController.phase == Phase.IDLE
            override fun isLoopArming(): Boolean = this@CrossfadeController.phase == Phase.LOOP_ARMING
            override fun isLoopRunning(): Boolean = this@CrossfadeController.phase == Phase.LOOP_RUNNING
            override fun isNativeRendererAvailable(player: ExoPlayer): Boolean = this@CrossfadeController.isNativeRendererAvailable(player)
            override fun isStraightRoute(plan: LoopMix.Plan): Boolean = this@CrossfadeController.isStraightRoute(plan)
            override fun isTail(): Boolean = this@CrossfadeController.phase == Phase.TAIL
            override fun leavingOnBail(): ExoPlayer? = this@CrossfadeController.leavingOnBail()
            override fun listenTo(target: ExoPlayer): Unit = this@CrossfadeController.listenTo(target)
            override fun loopBail(reason: String, settle: Boolean, preserveSeek: Boolean, deferDisarm: Boolean): Unit = this@CrossfadeController.loopBail(reason, settle, preserveSeek, deferDisarm)
            override fun loopBail(reason: String, settle: Boolean, preserveSeek: Boolean, deferDisarm: Boolean, force: Boolean): Unit = this@CrossfadeController.loopBail(reason, settle, preserveSeek, deferDisarm, force)
            override fun mixEvent(name: String, fields: Map<String, Any?>, context: Opportunity?): Unit = this@CrossfadeController.mixEvent(name, fields, context)
            override fun mixGeometry(): MixGeometry = this@CrossfadeController.mixGeometry()
            override fun nativeRouteFields(plan: LoopMix.Plan, outLoops: Boolean?, inLoops: Boolean?, actualConfiguration: Boolean, coarseDecline: Boolean?): Map<String, Any?> = this@CrossfadeController.nativeRouteFields(plan, outLoops, inLoops, actualConfiguration, coarseDecline)
            override fun nativeSessionRouteFields(session: LoopMixSession, coarseDecline: Boolean?): Map<String, Any?> = this@CrossfadeController.nativeSessionRouteFields(session, coarseDecline)
            override fun nativeUnavailabilityReason(player: ExoPlayer): String = this@CrossfadeController.nativeUnavailabilityReason(player)
            override fun nextItemDurationMs(nextIndex: Int, item: MediaItem): Long = this@CrossfadeController.nextItemDurationMs(nextIndex, item)
            override fun notePrep(player: ExoPlayer): Unit = this@CrossfadeController.notePrep(player)
            override fun onArming() = run { this@CrossfadeController.phase = Phase.ARMING }
            override fun onBailing() = run { this@CrossfadeController.phase = Phase.BAILING }
            override fun onFading() = run { this@CrossfadeController.phase = Phase.FADING }
            override fun onIdle() = run { this@CrossfadeController.phase = Phase.IDLE }
            override fun onLoopArming() = run { this@CrossfadeController.phase = Phase.LOOP_ARMING }
            override fun onLoopRunning() = run { this@CrossfadeController.phase = Phase.LOOP_RUNNING }
            override fun onSleepFade() = run { this@CrossfadeController.phase = Phase.SLEEP_FADE }
            override fun onTail() = run { this@CrossfadeController.phase = Phase.TAIL }
            override fun openFiltersWhenSilent(): Unit = this@CrossfadeController.openFiltersWhenSilent()
            override fun openSpareFiltersWhenSilent(): Unit = this@CrossfadeController.openSpareFiltersWhenSilent()
            override fun openSurvivorFiltersWhenRetireeSilent(): Unit = this@CrossfadeController.openSurvivorFiltersWhenRetireeSilent()
            override fun opportunityFields(context: Opportunity?): Map<String, Any?> = this@CrossfadeController.opportunityFields(context)
            override fun planAgeMs(now: Long): Long? = this@CrossfadeController.planAgeMs(now)
            override fun planAutoTransition(player: ExoPlayer): Unit = this@CrossfadeController.planAutoTransition(player)
            override fun positionMs(player: ExoPlayer): Double = this@CrossfadeController.positionMs(player)
            override fun prefetchMixWaves(outItem: MediaItem, inItem: MediaItem, outDurationMs: Long, inDurationMs: Long, outStart: Double, outEnd: Double, inStart: Double, ratio: Double, wantOut: Boolean, wantIn: Boolean): Unit = this@CrossfadeController.prefetchMixWaves(outItem, inItem, outDurationMs, inDurationMs, outStart, outEnd, inStart, ratio, wantOut, wantIn)
            override fun preparedRoute(fields: Map<String, Any?>, context: Opportunity?, revision: Int, generation: Int?): Unit = this@CrossfadeController.preparedRoute(fields, context, revision, generation)
            override fun primeKey(plan: LoopMix.Plan, outItem: MediaItem, inItem: MediaItem, sampleRate: Int): String = this@CrossfadeController.primeKey(plan, outItem, inItem, sampleRate)
            override fun publishAnalysisState(): Unit = this@CrossfadeController.publishAnalysisState()
            override fun publishAutomixDetail(): Unit = this@CrossfadeController.publishAutomixDetail()
            override fun publishBlend(out: ExoPlayer, into: ExoPlayer, force: Boolean): Unit = this@CrossfadeController.publishBlend(out, into, force)
            override fun publishMixPreview(currentItem: MediaItem, nextItem: MediaItem, duration: Long, nextDuration: Long, currentAnalysis: TrackAnalysis, nextAnalysis: TrackAnalysis, state: SmartAnalysis, plan: TransitionPlan, advanced: Boolean): Unit = this@CrossfadeController.publishMixPreview(currentItem, nextItem, duration, nextDuration, currentAnalysis, nextAnalysis, state, plan, advanced)
            override fun publishMixView(out: ExoPlayer, into: ExoPlayer): Unit = this@CrossfadeController.publishMixView(out, into)
            override fun queuedDurationMs(item: MediaItem): Long = this@CrossfadeController.queuedDurationMs(item)
            override fun ranOn(out: ExoPlayer): Boolean = this@CrossfadeController.ranOn(out)
            override fun reconcileQueue(out: ExoPlayer, into: ExoPlayer): Boolean = this@CrossfadeController.reconcileQueue(out, into)
            override fun refusalCode(reason: String): String = this@CrossfadeController.refusalCode(reason)
            override fun releaseHold(): Unit = this@CrossfadeController.releaseHold()
            override fun rememberLoopPair(outItem: MediaItem, inItem: MediaItem, outAnalysis: TrackAnalysis, inAnalysis: TrackAnalysis, durationMs: Long, nextDurationMs: Long): Unit = this@CrossfadeController.rememberLoopPair(outItem, inItem, outAnalysis, inAnalysis, durationMs, nextDurationMs)
            override fun renderFor(plan: TransitionPlan, advanced: Boolean): RideSpec = this@CrossfadeController.renderFor(plan, advanced).rideSpec
            override fun requestAnalysisAround(player: ExoPlayer, duration: Long): Unit = this@CrossfadeController.requestAnalysisAround(player, duration)
            override fun reviseRoute(plan: Any): Int = this@CrossfadeController.reviseRoute(plan)
            override fun routeRefusal(name: String, reason: String, stage: String, technique: String, destination: String?, context: Opportunity?, revision: Int, generation: Int?, coarseDecline: Boolean?): Unit = this@CrossfadeController.routeRefusal(name, reason, stage, technique, destination, context, revision, generation, coarseDecline)
            override fun sameVisualQueueItem(expected: MediaItem, actual: MediaItem?): Boolean = this@CrossfadeController.sameVisualQueueItem(expected, actual)
            override fun seekPhase(tookOver: Boolean): SeekAction.Phase = when (this@CrossfadeController.phase) {
                Phase.IDLE -> SeekAction.Phase.IDLE
                Phase.ARMING -> SeekAction.Phase.ARMING
                Phase.FADING -> SeekAction.Phase.FADING
                Phase.TAIL -> SeekAction.Phase.TAIL
                Phase.LOOP_ARMING -> if (tookOver) SeekAction.Phase.LOOP_RUNNING else SeekAction.Phase.LOOP_ARMING
                Phase.LOOP_RUNNING -> SeekAction.Phase.LOOP_RUNNING
                Phase.BAILING -> SeekAction.Phase.BAILING
                Phase.SLEEP_FADE -> SeekAction.Phase.SLEEP
            }
            override fun startAnalysisCollector(): Unit = this@CrossfadeController.startAnalysisCollector()
            override fun startLoopBuild(key: String, plan: LoopMix.Plan, out: ExoPlayer, into: ExoPlayer, outItem: MediaItem, inItem: MediaItem, durationMs: Long, nextDurationMs: Long, sampleRate: Int, armWhenReady: Boolean, preparedOverride: com.music.bitchord.playback.djmix.PreparedPair?, forceNoStems: Boolean): Unit = this@CrossfadeController.startLoopBuild(key, plan, out, into, outItem, inItem, durationMs, nextDurationMs, sampleRate, armWhenReady, preparedOverride, forceNoStems)
            override fun stateOf(item: MediaItem, analysis: TrackAnalysis): TrackAnalysisState = this@CrossfadeController.stateOf(item, analysis)
            override fun stillWorthFading(): Boolean = this@CrossfadeController.stillWorthFading()
            override fun syncInspectorWithUiState(ui: MixUiState): Unit = this@CrossfadeController.syncInspectorWithUiState(ui)
            override fun tickCadenceMs(): Long = when (this@CrossfadeController.phase) {
                Phase.IDLE -> IDLE_STEP_MS
                Phase.ARMING, Phase.LOOP_ARMING -> ARM_STEP_MS
                Phase.FADING, Phase.TAIL, Phase.SLEEP_FADE -> FADE_STEP_MS
                Phase.BAILING -> BAIL_STEP_MS
                Phase.LOOP_RUNNING -> LOOP_STEP_MS
            }
            override fun toCoreLevel(level: com.music.bitchord.playback.smart.choreo.DjLevel): CoreDjLevel = this@CrossfadeController.toCoreLevel(level)
        }
    }

    private val bailFallbackHandler: BailFallbackHandler by lazy(LazyThreadSafetyMode.NONE) { BailFallbackHandler(coordinatorHost) }
    private val fadeExecutionDriver: FadeExecutionDriver by lazy(LazyThreadSafetyMode.NONE) { FadeExecutionDriver(coordinatorHost) }
    private val tickEngine: TickEngine by lazy(LazyThreadSafetyMode.NONE) { TickEngine(coordinatorHost) }
    private val visualFeedPublisher: VisualFeedPublisher by lazy(LazyThreadSafetyMode.NONE) { VisualFeedPublisher(coordinatorHost) }
    private val prepCoordinator: PrepCoordinator by lazy(LazyThreadSafetyMode.NONE) { PrepCoordinator(coordinatorHost, ::acceptsExecutionCallbacks) }
    private val prepAnalysisCoordinator: PrepAnalysisCoordinator by lazy(LazyThreadSafetyMode.NONE) { PrepAnalysisCoordinator(coordinatorHost) }
    private val coordinatorBindings: CoordinatorBindings by lazy(LazyThreadSafetyMode.NONE) { CoordinatorBindings(coordinatorHost) }

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
    private class Render(val rideSpec: RideSpec = RideSpec()) {
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
        val phaseLock = rideSpec.phaseLock

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
    private fun notePrep(player: ExoPlayer) = prepCoordinator.notePrep(player)

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

    private fun toCoreLevel(level: com.music.bitchord.playback.smart.choreo.DjLevel): CoreDjLevel = prepCoordinator.toCoreLevel(level)

    private fun isNativeRendererAvailable(player: ExoPlayer): Boolean = prepCoordinator.isNativeRendererAvailable(player)

    private fun nativeUnavailabilityReason(player: ExoPlayer): String = prepCoordinator.nativeUnavailabilityReason(player)

    private val mixDeckAdapter: MixDeckAdapter = prepCoordinator.mixDeckAdapter

    private val playerEpoch = djmixServices?.beginPlayerEpoch()
    @Volatile private var executionReleased = false
    private fun acceptsExecutionCallbacks(): Boolean = !executionReleased &&
        (playerEpoch?.let { djmixServices?.isCurrentPlayerEpoch(it) } ?: true)
    internal val mixExecutor: MixExecutor = djmixServices?.createExecutor(
        mixDeckAdapter, MixMachineConfig.DEFAULT, requireNotNull(playerEpoch),
    ) ?: MixExecutor(deckAdapter = mixDeckAdapter, config = MixMachineConfig.DEFAULT)

    private fun onControllerThread(block: () -> Unit) = prepCoordinator.onControllerThread(block)

    private fun syncInspectorWithUiState(ui: MixUiState) = visualFeedPublisher.syncInspectorWithUiState(ui)

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
    private val visualDeckErrors: Player.Listener = visualFeedPublisher.visualDeckErrors

    fun cancelAutoMixVisual() = visualFeedPublisher.cancelAutoMixVisual()

    private fun armAutoMixVisual(out: ExoPlayer, into: ExoPlayer, nextIndex: Int, items: List<MediaItem>) = visualFeedPublisher.armAutoMixVisual(out, into, nextIndex, items)

    private fun clearAutoMixVisual() = visualFeedPublisher.clearAutoMixVisual()

    private fun sameVisualQueueItem(expected: MediaItem, actual: MediaItem?): Boolean = visualFeedPublisher.sameVisualQueueItem(expected, actual)

    fun checkAutoMixVisualTimeline(player: ExoPlayer) = visualFeedPublisher.checkAutoMixVisualTimeline(player)

    private val listener: Player.Listener = prepCoordinator.listener

    private fun isStraightRoute(plan: LoopMix.Plan): Boolean = prepCoordinator.isStraightRoute(plan)

    private fun frozenPreparedFor(
        outItem: MediaItem,
        inItem: MediaItem,
        generation: Long,
        sampleRate: Int,
        level: com.music.bitchord.playback.smart.choreo.DjLevel = AppSettings.automixDjLevel.value,
    ): PreparedPair? = prepCoordinator.frozenPreparedFor(outItem, inItem, generation, sampleRate, level)

    private fun listenTo(target: ExoPlayer) = prepCoordinator.listenTo(target)

    private fun startAnalysisCollector() = prepAnalysisCoordinator.startAnalysisCollector()

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
        djmixServices?.let { pairPreparer?.attachRetainedMixOwner(it, requireNotNull(playerEpoch)) }
        tickEngine.start()
    }

    fun release() {
        if (executionReleased) return
        MixInspectorFeed.setRemixAction(null)
        uiStateJob?.cancel()
        uiStateJob = null
        mixExecutor.dispatch(MixEvent.UserSkip)
        executionReleased = true
        mixExecutor.close()
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

    private fun tick() = tickEngine.tick()

    private fun considerAutoTransition() = tickEngine.considerAutoTransition()

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

    private fun planAutoTransition(player: ExoPlayer) = tickEngine.planAutoTransition(player)

    private fun considerSmartTransition(duration: Long, markerOnly: Boolean = false) = tickEngine.considerSmartTransition(duration, markerOnly)

    private fun classicFallbackOnRendererUnavailable(
        player: ExoPlayer,
        currentItem: MediaItem,
        nextItem: MediaItem,
        duration: Long,
        nextDuration: Long,
        reason: String,
        markerOnly: Boolean,
        failedPlan: TransitionPlanV1?,
    ) = bailFallbackHandler.classicFallbackOnRendererUnavailable(player, currentItem, nextItem, duration, nextDuration, reason, markerOnly, failedPlan)

    private fun renderFor(plan: TransitionPlan, advanced: Boolean) = Render(RideSpec(
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
    ))

    private fun requestAnalysisAround(player: ExoPlayer, duration: Long) = prepAnalysisCoordinator.requestAnalysisAround(player, duration)

    private fun publishAnalysisState() = prepAnalysisCoordinator.publishAnalysisState()

    private fun stateOf(item: MediaItem, analysis: TrackAnalysis): TrackAnalysisState = prepAnalysisCoordinator.stateOf(item, analysis)

    private fun nextItemDurationMs(nextIndex: Int, item: MediaItem): Long = prepAnalysisCoordinator.nextItemDurationMs(nextIndex, item)

    private fun queuedDurationMs(item: MediaItem): Long = prepAnalysisCoordinator.queuedDurationMs(item)

    /** BitChord doesn't carry album metadata on [MediaMetadata] yet, so [TransitionTrackInfo.album] stays blank. */
    private fun MediaItem.toTransitionInfo(durationMs: Long) = TransitionTrackInfo(
        id = mediaId,
        durationMs = durationMs,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
    )

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
    ): Boolean = fadeExecutionDriver.begin(fade, endMs, smart, cueTimeMs, playbackRate, outgoingRate, beatMs, renderStyle.rideSpec, plan)

    private fun holdAtEnd(out: ExoPlayer) = fadeExecutionDriver.holdAtEnd(out)

    private fun holdAhead(player: ExoPlayer, duration: Long) = fadeExecutionDriver.holdAhead(player, duration)

    private fun releaseHold() = fadeExecutionDriver.releaseHold()

    private fun ranOn(out: ExoPlayer): Boolean = fadeExecutionDriver.ranOn(out)

    private fun driveArming() = fadeExecutionDriver.driveArming()

    private fun cancelArming(reason: String) = fadeExecutionDriver.cancelArming(reason)

    private fun startFade() = fadeExecutionDriver.startFade()

    @JvmOverloads
    private fun handOff(
        out: ExoPlayer,
        into: ExoPlayer,
        visualAudioSeconds: Double? = null,
        visualEndSeconds: Double? = null,
    ) = fadeExecutionDriver.handOff(out, into, visualAudioSeconds, visualEndSeconds)

    private fun reconcileQueue(out: ExoPlayer, into: ExoPlayer): Boolean = fadeExecutionDriver.reconcileQueue(out, into)

    private fun driveFade() = fadeExecutionDriver.driveFade()

    private fun beginTailOrFinish(out: ExoPlayer, reason: String = "planned", retireSite: String = "driveFade-done") = fadeExecutionDriver.beginTailOrFinish(out, reason, retireSite)

    private fun disarmTailLoopSession() = fadeExecutionDriver.disarmTailLoopSession()

    private fun driveTail() = fadeExecutionDriver.driveTail()

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

    internal val loopState: LoopDriverState = coordinatorBindings.loopState

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
    private val loopDriverHost: LoopDriverHost = coordinatorBindings.loopDriverHost

    internal val loopArmDriver = LoopArmDriver(loopDriverHost, loopState)
    internal val loopMixDriver = LoopMixDriver(loopDriverHost, loopState, loopArmDriver)

    private val mixStatusHost: MixStatusHost = coordinatorBindings.mixStatusHost

    internal val mixStatusDescriber = MixStatusDescriber(mixStatusHost)

    init {
        loopArmDriver.mixDriver = loopMixDriver
    }

    private val lyricSpans = java.util.concurrent.ConcurrentHashMap<String, LyricSpans>()
    private val lyricsAsked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val lyricsAskedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun askLyrics(item: MediaItem, durationMs: Long) = prepAnalysisCoordinator.askLyrics(item, durationMs)

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

    fun onTrackChanged() = prepCoordinator.onTrackChanged()

    fun triggerRemixPrep() = prepCoordinator.triggerRemixPrep()

    fun remixCurrentTransition(): Boolean = prepCoordinator.remixCurrentTransition()

    private fun twoBarsMsFor(bpm: Double, beatSecondsFallback: Double): Long = bailFallbackHandler.twoBarsMsFor(bpm, beatSecondsFallback)

    private fun classicActiveFxNames(r: Render): List<String> = bailFallbackHandler.classicActiveFxNames(r.rideSpec)

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

    private fun emitWatchdogAndTriggerEmergency(
        player: ExoPlayer,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        reason: String,
        plannedStartMs: Long? = null,
        elapsedMs: Long? = null,
        resetFromLoopArming: Boolean = false,
    ): Boolean = bailFallbackHandler.emitWatchdogAndTriggerEmergency(player, outItem, inItem, durationMs, reason, plannedStartMs, elapsedMs, resetFromLoopArming)

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

    private fun bail(force: Boolean = false) = bailFallbackHandler.bail(force)

    private fun leavingOnBail(): ExoPlayer? = bailFallbackHandler.leavingOnBail()

    private fun finish() = bailFallbackHandler.finish()

    private fun stillWorthFading(): Boolean = bailFallbackHandler.stillWorthFading()

    private fun retire(player: ExoPlayer) = bailFallbackHandler.retire(player)

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

    private val tracks: TransitionFilters = coordinatorBindings.tracks

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

    private val mixViewHost: MixViewHost = coordinatorBindings.mixViewHost

    private val mixViewPublisher = MixViewPublisher(mixViewHost)

    private val mixEventHost: MixEventHost = coordinatorBindings.mixEventHost

    private val mixEventRouter = MixEventRouter(mixEventHost)

    init {
        // Production constructs on the service/player main thread. Finish every cheap host/helper
        // allocation after all controller dependencies exist, before start() exposes callbacks.
        // No later prep/audio callback can become the first initializer of a NONE delegate.
        coordinatorHost
        bailFallbackHandler
        fadeExecutionDriver
        tickEngine
        visualFeedPublisher
        prepCoordinator
        prepAnalysisCoordinator
        coordinatorBindings
    }

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



}
