package com.music.bitchord.playback.djmix

import com.music.bitchord.data.settings.TrackAnalysisState

/** How much of each track either side of the overlap the mix popup shows, as a fraction of the overlap. */
internal const val MIX_VIEW_CONTEXT = 0.24

/** Waveform columns per second of audio in the mix popup, and a ceiling for very long windows. */
internal const val MIX_WAVE_BINS_PER_SECOND = 40.0
internal const val MIX_WAVE_MAX_BINS = 3_000

/** How far ahead of a transition its waveforms start decoding. */
internal const val MIX_PREFETCH_MS = 30_000L

/** How often a paused track's seek-bar marker is re-planned. */
internal const val PAUSED_MARKER_STEP_MS = 1_000L

/** Extra decoded either side of the popup's own window, for a plan that shifts before it arms. */
internal const val MIX_PREFETCH_PAD_SECONDS = 3.0

/** Points the mix popup's fader lines are sampled at. */
internal const val MIX_VIEW_FADER_POINTS = 64

/** How long a waveform that failed to decode is left before it is tried again. */
internal const val MIX_WAVE_RETRY_MS = 5_000L

/** How long the popup's preview outlives the last plan that laid it out. */
internal const val MIX_PREVIEW_STALE_MS = 1_500L

internal const val TAG = "BitChordCrossfade"

/**
 * Used only before a pair has been analysed, or when the evidence is
 * too weak for more than a plain fade — see [considerSmartTransition].
 * Once real analysis lands, the overlap is sized from tempo and
 * structure instead and this is never read.
 */
internal const val DEFAULT_SMART_FALLBACK_SECONDS = 6.0

/** Ramp used when a fade is interrupted. */
internal const val BAIL_MS = 120L

/** djmix13: how long a shown loop stays suppressed after a watchdog trip. */
internal const val LOOP_SUPPRESSION_MS = 90_000L

/** Stuck-build guard: any BUILDING state > 8s cancels & invokes emergency path. */
internal const val STUCK_BUILD_GUARD_MS = 8_000L

/** Minimum EOF floor for Hard Watchdog (D2): max(2 bars, 4.5s). */
internal const val HARD_WATCHDOG_MIN_EOF_MS = 4_500L

/** djmix26: brief post-scrub settle window so midway seeks pick one stable plan. */
internal const val SCRUB_SETTLE_MS = 350L

internal val HARD_LOOP_REFUSALS = setOf(
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
internal const val ARM_LEAD_MS = 4_000L

/**
 * The earliest, in wall-clock ms before the next item starts, that
 * Media3 1.11 lets a player's renderers move on to reading it:
 * `ExoPlayerImplInternal.DURATION_TO_ADVANCE_READING_THRESHOLD_US`,
 * checked in `maybeUpdateReadingPeriod` against the playing position
 * divided by speed (javap, 1.11.0). Before this point [holdAtEnd]
 * provably cannot flush; after it, it might. Re-check on a Media3 bump.
 */
internal const val MEDIA3_READ_AHEAD_LIMIT_MS = 10_000L

/**
 * Margin on [MEDIA3_READ_AHEAD_LIMIT_MS] for a position report up to a
 * tick stale and the message's hop to the playback thread.
 */
internal const val HOLD_SAFETY_MS = 1_000L

/**
 * How far ahead of the last safe moment [holdAhead] starts holding: a
 * dozen idle ticks, so one late tick never misses it.
 */
internal const val HOLD_AHEAD_MS = 3_000L

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
internal val MEASURED_ENOUGH_TO_ENTER_ON = setOf(
    TrackAnalysisState.ANALYSED,
    TrackAnalysisState.REFINING,
)

/**
 * Longest a transition will wait on an incoming track that will not
 * become ready. Past this the queue is left to move on plainly, which is
 * a missed crossfade rather than a broken one.
 */
internal const val ARM_TIMEOUT_MS = 12_000L

/**
 * Hands off early when the outgoing track is this close to its end,
 * before its still-intact queue can advance it. Several fade ticks, so
 * one late tick cannot miss it.
 */
internal const val HANDOFF_GUARD_MS = 400L

/**
 * How far a freshly derived beat anchor may sit from the published one
 * before it is worth sending. Above a tick's worth of position jitter,
 * well under what the eye reads as a pulse off the beat.
 */
internal const val ANCHOR_TOLERANCE_NANOS = 25_000_000L

/** Relative tempo change worth re-publishing; beatmatch rates are fixed per blend. */
internal const val TEMPO_TOLERANCE = 0.005f

internal const val IDLE_STEP_MS = 250L

/** Coalesces a burst of analysis updates for the same pair into one re-plan. */
internal const val ANALYSIS_REPLAN_DEBOUNCE_MS = 200L
/** A fallback NATURAL is final only once arming is at most this far away. */
internal const val FALLBACK_NATURAL_HORIZON_MS = 12_000L

/**
 * Arming only waits on a buffer now — nothing is being converged — so
 * this is about how promptly the fade can start once the incoming track
 * is ready, not about a control loop's step size.
 */
internal const val ARM_STEP_MS = 40L
internal const val FADE_STEP_MS = 30L

/** A new position report landed somewhere in the last fade tick; half of one is the best guess. */
internal const val HALF_TICK_NANOS = FADE_STEP_MS * 500_000L

/** Never run a stalled player's position on further than its loop could plausibly sleep. */
internal const val MAX_EXTRAPOLATION_NANOS = 600_000_000L
internal const val BAIL_STEP_MS = 15L

/** The loop mix's tick: fine enough for the handoff and the beat pulse, which land on beats. */
internal const val LOOP_STEP_MS = 20L

/** The loop mix's setup: how far ahead its loop must start, and when its sinks are armed. */
internal const val LOOP_SETUP_SECONDS = 15.0
internal const val POST_MIX_ANALYSIS_DELAY_MS = 12_000L
internal const val LOOP_ARM_LEAD_MS = 12_000L
/** djmix14: prime lead — start decoding as soon as the pair is planned within 40 s. */
internal const val LOOP_PRIME_LEAD_MS = 45_000L
internal const val LOOP_MIN_LEAD_MS = 9_000L

/**
 * How close to the planned bar the decks may still be unfinished before
 * that bar is given up. The decode itself starts as soon as the plan
 * exists; this is only the splice, which the sink has to be holding
 * before the playhead reaches it. Missing it shifts to the next bar
 * instead of abandoning the loop.
 */
internal const val LOOP_COMMIT_MS = 400L

/** A deck build that came back empty is tried again, not on every tick. */
internal const val LOOP_PRIME_RETRY_MS = 2_000L

/** After the incoming player starts, how long its clock settles before it is measured against the deck. */
internal const val LOOP_ALIGN_SETTLE_SECONDS = 1.2
internal const val LOOP_ALIGN_ROUNDS = 5
internal const val LOOP_ALIGN_SAMPLES = 15
internal const val LOOP_ALIGN_SAMPLE_MS = 30L

/** Past the incoming player's takeover, before the outgoing one is retired. */
internal const val LOOP_END_MARGIN_SECONDS = 0.3

/** A position this far back is a loop wrap; and how often the ramp re-anchors controllers. */
internal const val LOOP_WRAP_MS = 400L
internal const val LOOP_ANNOUNCE_MS = 2_000L

/** A retired sink has long stopped rendering by then. */
internal const val LOOP_RENDERER_CLOSE_DELAY_MS = 1_500L
internal const val ADAPTIVE_PRIME_LEAD_MAX_MS = 45_000L
internal const val ADAPTIVE_PRIME_LEAD_MIN_MS = 35_000L
internal const val FADING_STALL_BAIL_MS = 2_500L
