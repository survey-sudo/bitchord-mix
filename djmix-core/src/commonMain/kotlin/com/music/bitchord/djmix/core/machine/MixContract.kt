package com.music.bitchord.djmix.core.machine

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.contracts.MixTelemetryV3
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.ThermalLevel
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Canonical identity of an outgoing -> incoming track pair. */
@Serializable
data class PairKey(
    val outTrackId: String,
    val inTrackId: String,
)

/** Discriminator over the 11 [MixState] subclasses for table tests and diagnostics. */
@Serializable
enum class MixStateKind {
    IDLE,
    PREPARING,
    READY,
    ARMED,
    RUNNING,
    HANDING_OVER,
    DONE,
    MOVED,
    LAST_RESORT,
    SUSPENDED,
    FAILED,
}

/** Discriminator over the 20 [MixEvent] subclasses for table tests and diagnostics. */
@Serializable
enum class MixEventKind {
    NEXT_KNOWN,
    QUEUE_EDITED,
    ANALYSIS_READY,
    PLAN_FROZEN,
    REGIONS_READY,
    STEMS_READY,
    RENDERER_PREPARED,
    TICK,
    USER_SEEK,
    USER_SKIP,
    USER_PAUSE,
    USER_RESUME,
    AUDIO_FOCUS_LOST,
    ROUTE_CHANGED,
    BACKGROUNDED,
    THERMAL_CHANGED,
    RENDERER_ERROR,
    HANDOVER_COMPLETE,
    LEVEL_CHANGED,
    REMIX_REQUESTED,
}

/** Immutable state of [MixMachine] (exactly 11 subclasses per B2). */
@Serializable
sealed interface MixState {
    /** Exact state category discriminator. */
    val stateKind: MixStateKind

    /** Monotonic preparation/execution generation identifier. */
    val generation: Long

    /** Active DJ level preference. */
    val djLevel: DjLevel

    /** Active pair being prepared or executed, or null when inactive. */
    val pair: PairKey?

    /** Active frozen plan, or null before planning completes. */
    val plan: TransitionPlanV1?

    /** Current Bluetooth audio route latency offset in milliseconds. */
    val btLatencyMs: Long

    /** Most recent platform thermal status. */
    val thermalLevel: ThermalLevel

    /** True while the app is backgrounded. */
    val backgrounded: Boolean

    /** Sticky user pause flag (cleared ONLY by [MixEvent.UserResume]). */
    val userPaused: Boolean

    /** No upcoming transition is active. */
    @Serializable
    @SerialName("idle")
    data class Idle(
        override val generation: Long = 0L,
        override val djLevel: DjLevel = DjLevel.SUBTLE,
        override val btLatencyMs: Long = 0L,
        override val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        override val backgrounded: Boolean = false,
        override val userPaused: Boolean = false,
    ) : MixState {
        override val stateKind: MixStateKind get() = MixStateKind.IDLE
        override val pair: PairKey? get() = null
        override val plan: TransitionPlanV1? get() = null
    }

    /** Background analysis, planning, decode, stemming, and renderer preparation are in flight. */
    @Serializable
    @SerialName("preparing")
    data class Preparing(
        override val pair: PairKey,
        override val generation: Long,
        override val djLevel: DjLevel = DjLevel.SUBTLE,
        val outAnalysisLevel: AnalysisLevel = AnalysisLevel.NONE,
        val inAnalysisLevel: AnalysisLevel = AnalysisLevel.NONE,
        override val plan: TransitionPlanV1? = null,
        val regionsReady: Boolean = false,
        val stemsReady: Boolean = false,
        val stemsRequired: Int = 0,
        val stemsCompleted: Int = 0,
        val rendererPrepared: Boolean = false,
        val usingNoStemVariant: Boolean = false,
        val preferNoStemVariant: Boolean = false,
        val excludedRecipeIds: Set<String> = emptySet(),
        val lastPositionMs: Long = 0L,
        val isPlaying: Boolean = true,
        override val btLatencyMs: Long = 0L,
        override val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        override val backgrounded: Boolean = false,
        override val userPaused: Boolean = false,
        val focusPaused: Boolean = false,
    ) : MixState {
        override val stateKind: MixStateKind get() = MixStateKind.PREPARING
    }

    /** Regions, required stems (or no-stem variant), script, and renderer are all prepared. */
    @Serializable
    @SerialName("ready")
    data class Ready(
        override val pair: PairKey,
        override val generation: Long,
        override val djLevel: DjLevel = DjLevel.SUBTLE,
        override val plan: TransitionPlanV1,
        val rendererPrepared: Boolean = true,
        val usingNoStemVariant: Boolean = false,
        val stemsRequired: Int = 0,
        val stemsCompleted: Int = 0,
        val excludedRecipeIds: Set<String> = emptySet(),
        val lastPositionMs: Long = 0L,
        val isPlaying: Boolean = true,
        override val btLatencyMs: Long = 0L,
        override val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        override val backgrounded: Boolean = false,
        override val userPaused: Boolean = false,
        val focusPaused: Boolean = false,
    ) : MixState {
        init {
            require(rendererPrepared) { "Ready state requires rendererPrepared == true" }
        }

        override val stateKind: MixStateKind get() = MixStateKind.READY
    }

    /** Commit point crossed with a prepared renderer; waiting for [armAtMs] to fire playback. */
    @Serializable
    @SerialName("armed")
    data class Armed(
        override val pair: PairKey,
        override val generation: Long,
        override val djLevel: DjLevel = DjLevel.SUBTLE,
        override val plan: TransitionPlanV1,
        val armAtMs: Long = plan.frozenExitMs,
        val rendererPrepared: Boolean = true,
        val usingNoStemVariant: Boolean = false,
        val lastPositionMs: Long = 0L,
        val isPlaying: Boolean = true,
        override val btLatencyMs: Long = 0L,
        override val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        override val backgrounded: Boolean = false,
        override val userPaused: Boolean = false,
        val focusPaused: Boolean = false,
    ) : MixState {
        init {
            require(rendererPrepared) { "Armed state requires rendererPrepared == true" }
        }

        override val stateKind: MixStateKind get() = MixStateKind.ARMED
    }

    /** Dual-deck transition audio is actively rendering and advancing. */
    @Serializable
    @SerialName("running")
    data class Running(
        override val pair: PairKey,
        override val generation: Long,
        override val djLevel: DjLevel = DjLevel.SUBTLE,
        override val plan: TransitionPlanV1,
        val startedAtMs: Long,
        val lastPositionMs: Long = startedAtMs,
        val progressPct: Int = 0,
        val usingNoStemVariant: Boolean = false,
        val isPlaying: Boolean = true,
        val survivingDeck: DeckTarget = DeckTarget.IN,
        val pendingNextLevel: DjLevel? = null,
        override val btLatencyMs: Long = 0L,
        override val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        override val backgrounded: Boolean = false,
        override val userPaused: Boolean = false,
        val focusPaused: Boolean = false,
    ) : MixState {
        init {
            require(survivingDeck == DeckTarget.IN) { "Running survivingDeck must remain DeckTarget.IN" }
        }

        override val stateKind: MixStateKind get() = MixStateKind.RUNNING
    }

    /** Transition overlap completed; handing active playback over to the incoming survivor deck. */
    @Serializable
    @SerialName("handing_over")
    data class HandingOver(
        override val pair: PairKey,
        override val generation: Long,
        override val djLevel: DjLevel = DjLevel.SUBTLE,
        override val plan: TransitionPlanV1,
        val lastPositionMs: Long = plan.frozenExitMs + plan.overlapDurationMs,
        val survivingDeck: DeckTarget = DeckTarget.IN,
        val pendingNextLevel: DjLevel? = null,
        override val btLatencyMs: Long = 0L,
        override val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        override val backgrounded: Boolean = false,
        override val userPaused: Boolean = false,
        val focusPaused: Boolean = false,
    ) : MixState {
        init {
            require(survivingDeck == DeckTarget.IN) { "HandingOver survivingDeck must remain DeckTarget.IN" }
        }

        override val stateKind: MixStateKind get() = MixStateKind.HANDING_OVER
    }

    /** Transition and deck handover completed cleanly. */
    @Serializable
    @SerialName("done")
    data class Done(
        override val pair: PairKey,
        override val generation: Long,
        override val djLevel: DjLevel = DjLevel.SUBTLE,
        override val plan: TransitionPlanV1,
        override val btLatencyMs: Long = 0L,
        override val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        override val backgrounded: Boolean = false,
        override val userPaused: Boolean = false,
    ) : MixState {
        override val stateKind: MixStateKind get() = MixStateKind.DONE
    }

    /** Transition was not ready at the original commit point and shifted to [newExitMs]. */
    @Serializable
    @SerialName("moved")
    data class Moved(
        override val pair: PairKey,
        override val generation: Long,
        override val djLevel: DjLevel = DjLevel.SUBTLE,
        val fromExitMs: Long,
        val newExitMs: Long,
        val movedAtPositionMs: Long = 0L,
        val reason: RefusalReason = RefusalReason.COMMIT_DEADLINE_MISSED,
        val outAnalysisLevel: AnalysisLevel = AnalysisLevel.NONE,
        val inAnalysisLevel: AnalysisLevel = AnalysisLevel.NONE,
        override val plan: TransitionPlanV1? = null,
        val regionsReady: Boolean = false,
        val stemsReady: Boolean = false,
        val stemsRequired: Int = 0,
        val stemsCompleted: Int = 0,
        val rendererPrepared: Boolean = false,
        val usingNoStemVariant: Boolean = false,
        val preferNoStemVariant: Boolean = false,
        val excludedRecipeIds: Set<String> = emptySet(),
        val lastPositionMs: Long = movedAtPositionMs,
        val isPlaying: Boolean = true,
        override val btLatencyMs: Long = 0L,
        override val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        override val backgrounded: Boolean = false,
        override val userPaused: Boolean = false,
        val focusPaused: Boolean = false,
    ) : MixState {
        override val stateKind: MixStateKind get() = MixStateKind.MOVED
    }

    /** No later phrase boundary existed; executing a musical exit on the last phrase downbeat. */
    @Serializable
    @SerialName("last_resort")
    data class LastResort(
        override val pair: PairKey,
        override val generation: Long,
        override val djLevel: DjLevel = DjLevel.SUBTLE,
        override val plan: TransitionPlanV1,
        val targetDownbeatMs: Long = plan.lastPhraseDownbeatMs ?: plan.frozenExitMs,
        val rendererPrepared: Boolean = false,
        val lastPositionMs: Long = 0L,
        val isPlaying: Boolean = true,
        override val btLatencyMs: Long = 0L,
        override val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        override val backgrounded: Boolean = false,
        override val userPaused: Boolean = false,
        val focusPaused: Boolean = false,
    ) : MixState {
        override val stateKind: MixStateKind get() = MixStateKind.LAST_RESORT
    }

    /** Automix suspended due to an external interruption such as permanent audio focus loss. */
    @Serializable
    @SerialName("suspended")
    data class Suspended(
        val reason: RefusalReason,
        val detail: String = "",
        override val pair: PairKey? = null,
        override val generation: Long = 0L,
        override val djLevel: DjLevel = DjLevel.SUBTLE,
        override val plan: TransitionPlanV1? = null,
        override val btLatencyMs: Long = 0L,
        override val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        override val backgrounded: Boolean = false,
        override val userPaused: Boolean = false,
    ) : MixState {
        override val stateKind: MixStateKind get() = MixStateKind.SUSPENDED
    }

    /** Active transition failed and deferred to classic or stopped safely. */
    @Serializable
    @SerialName("failed")
    data class Failed(
        val kind: FailureKind,
        val reason: RefusalReason? = null,
        val message: String = "",
        override val pair: PairKey? = null,
        override val generation: Long = 0L,
        override val djLevel: DjLevel = DjLevel.SUBTLE,
        override val plan: TransitionPlanV1? = null,
        override val btLatencyMs: Long = 0L,
        override val thermalLevel: ThermalLevel = ThermalLevel.NONE,
        override val backgrounded: Boolean = false,
        override val userPaused: Boolean = false,
    ) : MixState {
        override val stateKind: MixStateKind get() = MixStateKind.FAILED
    }
}

/** Pure input event fed to [MixMachine.reduce] (exactly 20 subclasses per B2). */
@Serializable
sealed interface MixEvent {
    /** Exact event category discriminator. */
    val eventKind: MixEventKind

    /** Upcoming outgoing -> incoming pair became known. */
    @Serializable
    @SerialName("next_known")
    data class NextKnown(
        val pair: PairKey,
        val positionMs: Long = 0L,
        val djLevel: DjLevel? = null,
        val isPlaying: Boolean = true,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.NEXT_KNOWN
    }

    /** User or autoplay modified the upcoming queue entry (`null` clears next track). */
    @Serializable
    @SerialName("queue_edited")
    data class QueueEdited(
        val nextTrackId: String?,
        val positionMs: Long = 0L,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.QUEUE_EDITED
    }

    /** Head/tail or full analysis completed for [trackId]. */
    @Serializable
    @SerialName("analysis_ready")
    data class AnalysisReady(
        val trackId: String,
        val level: AnalysisLevel,
        val generation: Long? = null,
        val usable: Boolean = true,
        val refusalReason: RefusalReason? = null,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.ANALYSIS_READY
    }

    /** Planner froze [plan] for [generation]. */
    @Serializable
    @SerialName("plan_frozen")
    data class PlanFrozen(
        val plan: TransitionPlanV1,
        val generation: Long = plan.generation,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.PLAN_FROZEN
    }

    /** Both deck PCM regions finished decoding for `(planId, generation)`. */
    @Serializable
    @SerialName("regions_ready")
    data class RegionsReady(
        val planId: String,
        val generation: Long,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.REGIONS_READY
    }

    /** Stem separation finished or updated progress for `(planId, generation)`. */
    @Serializable
    @SerialName("stems_ready")
    data class StemsReady(
        val planId: String,
        val generation: Long,
        val stemsCompleted: Int,
        val stemsRequired: Int,
        val stemLatencyOutMs: Long? = null,
        val stemLatencyInMs: Long? = null,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.STEMS_READY
    }

    /** Native renderer finished `nativePrepare()` off the audio thread for `(planId, generation)`. */
    @Serializable
    @SerialName("renderer_prepared")
    data class RendererPrepared(
        val planId: String,
        val generation: Long,
        val usedNoStemVariant: Boolean = false,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.RENDERER_PREPARED
    }

    /** Outgoing playback position / playing-state tick. */
    @Serializable
    @SerialName("tick")
    data class Tick(
        val positionMs: Long,
        val isPlaying: Boolean = true,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.TICK
    }

    /** User scrubbed the outgoing or transitioning playback position to [toMs]. */
    @Serializable
    @SerialName("user_seek")
    data class UserSeek(
        val toMs: Long,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.USER_SEEK
    }

    /** User skipped the current or queued track. */
    @Serializable
    @SerialName("user_skip")
    data object UserSkip : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.USER_SKIP
    }

    /** User explicitly paused playback (sets sticky pause). */
    @Serializable
    @SerialName("user_pause")
    data object UserPause : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.USER_PAUSE
    }

    /** User explicitly resumed playback (clears sticky pause). */
    @Serializable
    @SerialName("user_resume")
    data object UserResume : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.USER_RESUME
    }

    /** Platform audio focus lost (`transient == false` indicates permanent loss). */
    @Serializable
    @SerialName("audio_focus_lost")
    data class AudioFocusLost(
        val transient: Boolean,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.AUDIO_FOCUS_LOST
    }

    /** Output audio route latency changed. */
    @Serializable
    @SerialName("route_changed")
    data class RouteChanged(
        val btLatencyMs: Long,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.ROUTE_CHANGED
    }

    /** Application moved to background or foreground. */
    @Serializable
    @SerialName("backgrounded")
    data class Backgrounded(
        val isBackgrounded: Boolean = true,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.BACKGROUNDED
    }

    /** Platform thermal status changed. */
    @Serializable
    @SerialName("thermal_changed")
    data class ThermalChanged(
        val status: ThermalLevel,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.THERMAL_CHANGED
    }

    /** Renderer encountered a preparation or playback error. */
    @Serializable
    @SerialName("renderer_error")
    data class RendererError(
        val code: FailureKind,
        val message: String = "",
        val recoverable: Boolean = true,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.RENDERER_ERROR
    }

    /** Player handover from the transition sink to the surviving incoming player completed. */
    @Serializable
    @SerialName("handover_complete")
    data object HandoverComplete : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.HANDOVER_COMPLETE
    }

    /** User changed the DJ level in settings. */
    @Serializable
    @SerialName("level_changed")
    data class LevelChanged(
        val level: DjLevel,
    ) : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.LEVEL_CHANGED
    }

    /** User tapped "Try another mix" in the Mix Inspector. */
    @Serializable
    @SerialName("remix_requested")
    data object RemixRequested : MixEvent {
        override val eventKind: MixEventKind get() = MixEventKind.REMIX_REQUESTED
    }
}

/** Declarative side-effect emitted by [MixMachine.reduce] for `MixExecutor` to run. */
@Serializable
sealed interface MixEffect {
    /** Starts background analysis, planning, decode, and optional stemming for [pair]. */
    @Serializable
    @SerialName("start_prepare")
    data class StartPrepare(
        val pair: PairKey,
        val generation: Long,
        val djLevel: DjLevel,
        val positionMs: Long = 0L,
        val minExitMs: Long? = null,
        val preferNoStemVariant: Boolean = false,
        val excludedRecipeIds: Set<String> = emptySet(),
    ) : MixEffect

    /** Cancels in-flight preparation tasks belonging to [generation]. */
    @Serializable
    @SerialName("cancel_prepare")
    data class CancelPrepare(
        val generation: Long,
    ) : MixEffect

    /** Builds the script and runs `nativePrepare` off the audio thread for `(planId, generation)`. */
    @Serializable
    @SerialName("prepare_renderer")
    data class PrepareRenderer(
        val planId: String,
        val generation: Long,
        val useNoStemVariant: Boolean = false,
    ) : MixEffect

    /** Arms the already-prepared renderer to start at [atMs] compensated by [btLatencyOffsetMs]. */
    @Serializable
    @SerialName("arm")
    data class Arm(
        val planId: String,
        val generation: Long,
        val atMs: Long,
        val btLatencyOffsetMs: Long = 0L,
    ) : MixEffect

    /** Starts audio rendering of the prepared `(planId, generation)`. */
    @Serializable
    @SerialName("start")
    data class Start(
        val planId: String,
        val generation: Long,
    ) : MixEffect

    /** Pauses both synchronized decks without tearing down the active session. */
    @Serializable
    @SerialName("pause_sync")
    data class PauseSync(
        val generation: Long,
        val byUser: Boolean,
    ) : MixEffect

    /** Resumes both synchronized decks following an explicit user resume. */
    @Serializable
    @SerialName("resume_sync")
    data class ResumeSync(
        val generation: Long,
    ) : MixEffect

    /** Retires the non-surviving [deck] while preserving the active/surviving deck. */
    @Serializable
    @SerialName("retire")
    data class Retire(
        val deck: DeckTarget,
        val generation: Long,
        val preserveSurvivor: Boolean = true,
    ) : MixEffect {
        init {
            require(preserveSurvivor) { "Retire must always preserve the surviving deck" }
        }
    }

    /** Hands active playback over to the incoming player for `(planId, generation)`. */
    @Serializable
    @SerialName("handover")
    data class Handover(
        val planId: String,
        val generation: Long,
    ) : MixEffect

    /** Publishes an updated [MixUiState] snapshot to the player chip and Mix Inspector. */
    @Serializable
    @SerialName("show_ui")
    data class ShowUi(
        val uiState: MixUiState,
    ) : MixEffect

    /** Emits a structured [MixTelemetryV3] diagnostic record. */
    @Serializable
    @SerialName("emit")
    data class Emit(
        val telemetry: MixTelemetryV3,
    ) : MixEffect
}

/** One deterministic step recorded by [MixMachine.replay]. */
@Serializable
data class ReplayStep(
    val index: Int,
    val previousState: MixState,
    val event: MixEvent,
    val nextState: MixState,
    val effects: List<MixEffect>,
)
