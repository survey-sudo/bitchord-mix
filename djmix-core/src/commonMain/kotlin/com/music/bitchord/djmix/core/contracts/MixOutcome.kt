package com.music.bitchord.djmix.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed result replacing swallowed exceptions (`runCatching`) and free-form refusal strings
 * across planning, preparation, and state-machine boundaries.
 */
@Serializable
sealed interface MixOutcome<out T> {
    /** True when the operation succeeded with a value. */
    val isOk: Boolean get() = this is Ok

    /** Returns the success value or null for [Refused] / [Failed]. */
    fun getOrNull(): T? = (this as? Ok)?.value

    /** Operation succeeded and produced [value]. */
    @Serializable
    @SerialName("ok")
    data class Ok<out T>(val value: T) : MixOutcome<T>

    /** Operation was intentionally declined for a known musical, policy, or timing [reason]. */
    @Serializable
    @SerialName("refused")
    data class Refused(
        val reason: RefusalReason,
        val detail: String = "",
    ) : MixOutcome<Nothing>

    /** Operation encountered an error of category [kind]. */
    @Serializable
    @SerialName("failed")
    data class Failed(
        val kind: FailureKind,
        val message: String = "",
        val recoverable: Boolean = true,
    ) : MixOutcome<Nothing>
}

/**
 * Canonical refusal taxonomy unifying legacy `loopRefusalFor`, `HARD_LOOP_REFUSALS`,
 * `handoff_pcm_*`, `LoopMix.qualifyCrown`, `MoveCatalog.reject`, and Part A §4 hard rejects.
 */
@Serializable
enum class RefusalReason {
    /** Analysis has not yet produced usable grids for one or both tracks. */
    WAITING_ON_ANALYSIS,

    /** Synced lyric fetch needed before finalising is still pending. */
    WAITING_ON_LYRICS,

    /** Beat-grid confidence is below the threshold required by the candidate or recipe. */
    LOW_GRID_CONFIDENCE,

    /** Exit boundary sits inside an active sung vocal line (Syvenu > 0.5 within ±1 beat). */
    EXIT_MID_VOCAL,

    /** Entry boundary sits inside an active sung vocal line at SUBTLE or CLUB. */
    ENTRY_MID_VOCAL,

    /** Exit boundary falls inside a musical build section. */
    EXIT_INSIDE_BUILD,

    /** Both decks' basslines exceed -6 dB simultaneously for more than one beat. */
    BASS_CLASH,

    /** Abrupt recipe (cut, brake, spinback) disallowed because pair lacks tempo/genre jump. */
    ABRUPT_DISALLOWED,

    /** Decoded PCM for a handoff plan stopped or started past a protected musical event. */
    HANDOFF_PCM_BEFORE_PROTECTED_EVENT,

    /** Handoff timing or geometry failed consistency verification. */
    HANDOFF_INVALID_GEOMETRY,

    /** Handoff endpoint exceeds the measured track duration. */
    HANDOFF_OUTSIDE_TRACK,

    /** Synced lyrics mark a certain vocal across the outgoing cut window. */
    HANDOFF_OUT_CUT_CERTAIN_LYRIC,

    /** Vocal mask detected singing across the outgoing cut window. */
    HANDOFF_OUT_CUT_MEASURED_VOCAL,

    /** Incoming entry would clip a protected audible attack or opening vocal. */
    HANDOFF_IN_ENTRY_PROTECTED_ATTACK,

    /** Handoff overlap would place simultaneous vocals on both decks. */
    HANDOFF_DUAL_VOCAL,

    /** Vocal mask coverage across handoff cut, entry, or overlap is incomplete. */
    HANDOFF_MASK_COVERAGE,

    /** Plan and script disagree on the incoming hold duration. */
    HANDOFF_HOLD_MISMATCH,

    /** Wet reverb/echo tail exceeds the handoff render budget. */
    HANDOFF_RENDER_BUDGET,

    /** Required stem ducking or stem buffers are unavailable for the transition. */
    STEM_DUCK_UNAVAILABLE,

    /** A frozen loop start already passed behind the current playhead. */
    STALE_LOOP_START_PASSED,

    /** Loop start lies closer to the playhead than the required decode lead. */
    LOOP_TOO_NEAR_PLAYHEAD,

    /** No eligible phrase boundary remains before the outgoing track's audible end. */
    NO_ELIGIBLE_BOUNDARY,

    /** Outgoing or incoming track is too short for a valid overlap window. */
    TRACK_TOO_SHORT,

    /** Prepared incoming track does not match the currently queued next track identity. */
    INCOMING_IDENTITY_MISMATCH,

    /** Native loop-mix renderer library or sink is unavailable. */
    RENDERER_UNAVAILABLE,

    /** Remaining lead time before the frozen exit is below the remix cutoff. */
    INSUFFICIENT_LEAD_FOR_REMIX,

    /** Global playback speed is not 1.0x. */
    PLAYBACK_SPEED_NOT_ONE,

    /** Skip-silence mode is active on the outgoing player. */
    SKIP_SILENCE_ENABLED,

    /** Sleep timer is set to stop after the current track. */
    SLEEP_TIMER_ACTIVE,

    /** Track analysis belongs to a matched external recording rather than the active stream. */
    SOURCE_BACKED_ANALYSIS,

    /** Current audio output route cannot accept the loop-mix sink. */
    OUTPUT_UNSUPPORTED,

    /** No standby player sink is available for the incoming track. */
    NO_STANDBY_SINK,

    /** Lyrics or vocal mask vetoed the retained loop during verification. */
    SUNG_VETO,

    /** Decoded PCM does not cover the required physical loop or handover span. */
    PCM_COVERAGE_INCOMPLETE,

    /** Crown tempo stretch rate falls outside the renderer's advancing-rate limits. */
    CROWN_EXECUTION_RATE,

    /** Required tempo ramp exceeds the maximum ramp bar budget. */
    CROWN_RAMP_BUDGET,

    /** Crown clock, pick lengths, fold, or release alignment failed validation. */
    CROWN_INVALID_CLOCK,

    /** Pair tempo gap exceeds the recipe or DJ-level stretch cap. */
    TEMPO_GAP_EXCEEDED,

    /** Key relation is incompatible for a recipe requiring harmonic compatibility. */
    HARMONY_INCOMPATIBLE,

    /** No incoming drop downbeat sits inside the eligible window. */
    NO_INCOMING_DROP,

    /** Available overlap window is shorter than the recipe minimum. */
    INSUFFICIENT_OVERLAP,

    /** Consecutive tracks from the same album are restricted to simple/gentle transitions. */
    ALBUM_ORDER_EXCLUDED,

    /** Music videos are excluded from loop transitions because audio may diverge from lyric sync. */
    MUSIC_VIDEO_EXCLUDED,

    /** Thermal throttling paused or blocked heavy preparation. */
    THERMAL_THROTTLED,

    /** Permanent audio focus loss suspended automix. */
    AUDIO_FOCUS_LOST,

    /** Queue modification invalidated or cleared the upcoming pair. */
    QUEUE_CHANGED,

    /** User seek or skip cancelled the pending transition. */
    USER_CANCELLED,

    /** Preparation failed to become ready before the commit deadline. */
    COMMIT_DEADLINE_MISSED,

    /** No no-stem fallback recipe/variant could be compiled for the pair. */
    NO_STEM_RECIPE_UNAVAILABLE,

    /** Choreography script compilation failed or produced no valid script. */
    CHOREOGRAPHY_UNAVAILABLE,
}

/** Error category for [MixOutcome.Failed] and [MixEvent.RendererError]. */
@Serializable
enum class FailureKind {
    DECODE_ERROR,
    ANALYSIS_ERROR,
    STEM_ERROR,
    RENDERER_PREPARE_ERROR,
    RENDERER_RUNTIME_ERROR,
    AUDIO_FOCUS_LOST_PERMANENT,
    INTERNAL_INVARIANT,
}
