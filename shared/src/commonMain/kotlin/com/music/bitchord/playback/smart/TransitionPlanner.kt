/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), merging its
 * TransitionPlanner.kt and WsolaPlanner.kt into one file.
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

@file:JvmName("TransitionPlannerKt")
@file:JvmMultifileClass

package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns stored analysis into a concrete transition plan for one pair of
 * tracks.
 *
 * Nothing here touches PCM; the planner decides *where* a transition happens
 * and *how* ambitious it is. [CrossfadeController] is what executes a plan: it
 * reads the timing fields ([TransitionPlan.transitionStart], [TransitionPlan.fadeSeconds]),
 * cues the incoming track to [TransitionPlan.incomingCueTime] instead of 0,
 * stretches it by [TransitionPlan.incomingPlaybackRate] to align tempo, and
 * renders [TransitionPlan.transitionStyle] as filtering across the blend —
 * a closing low-pass over the outgoing track for [TransitionStyle.DJ_FILTER],
 * a low-end handover at [TransitionPlan.bassSwapFraction] for
 * [TransitionStyle.DJ_BLEND]. The gain curve underneath is equal-power in every
 * case; see [com.music.bitchord.playback.TransitionFilterProcessor].
 */

/** Which crossfade behaviour the listener asked for. */
enum class CrossfadeMode { STANDARD, SMART }

/**
 * The minimal facts about a queue item the planner needs, independent of
 * Media3's `MediaItem` — kept separate so this file stays pure and testable
 * without constructing one.
 */
data class TransitionTrackInfo(
    val id: String,
    val durationMs: Long,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
    /** djmix24 Rung 0 guard: 1-based album track number, 0 when unknown. */
    val trackNumber: Int = 0,
)

/** How the renderer should execute a planned transition. */
enum class TransitionStyle {
    /** A constant-power fade, unfiltered. The only style the bottom tier permits. */
    EQUAL_POWER,

    /** Album siblings played through: a near-instant handoff, not a mix. */
    GAPLESS,

    /** Ordinary queue advance at physical EOF, with no overlap, cue skip, or effect. */
    NATURAL,

    /** Beat-aligned blend with a bass swap, for matching or near-matching tempi. */
    DJ_BLEND,

    /** Filtered handoff for tempi too far apart to blend flat. */
    DJ_FILTER,

    /**
     * Muffle (vc69 P0): the outgoing deck keeps its level while a steep
     * low-pass closes over it over the lead beats; the incoming track fades
     * in over the same span and takes the bass on the swap downbeat. The
     * muffled outgoing track is gone two beats later.
     */
    MUFFLE,

    /**
     * Drop-in (vc69 P0): the incoming track lands at full level on its
     * kick-locked anchor while the outgoing one cuts within four beats,
     * leaving a brief echo tail. For high-energy transitions.
     */
    DROP_IN,

    /**
     * Beatless bridge (vc69 P0): both tracks fade through a brief
     * ambient moment on their beatless edges, where no beat ever plays
     * against another, before the incoming beat lands as the outgoing
     * song ends. For tonal clashes where beat sync would sound wrong.
     */
    BEATLESS_BRIDGE,
}

/**
 * The gesture used inside an unmatched-tempo [TransitionStyle.DJ_FILTER].
 *
 * This is deterministic per pair rather than random per planner tick: the
 * planner runs repeatedly while a song plays, and a genuinely random answer
 * would make the marker and the renderer change their minds before arming.
 */
enum class FilterTransitionVariant {
    /** Strong complementary high/low-pass sweep. */
    SWEEP,

    /** Lighter colour change with a conspicuous low-end and fader handoff. */
    BASS_HANDOFF,

    /** Filter ride whose outgoing side trails away through the beat-synced echo. */
    ECHO_RIDE,

    /**
     * Bounded-decline carve: the 4 s DJ_FILTER for declined pairs, rendered
     * as a separation ride (outgoing muffled to its kick bed, incoming held
     * to presence until it is gone) rather than a sweep. Never a beatmatch;
     * the plan stays at rate 1.0 and the envelopes, not stretch, hide the flam.
     */
    DECLINE,
}

/**
 * The planned transition for one pair of tracks, in outgoing-track timeline
 * seconds.
 *
 * A plan is produced on every tick; [shouldStart] is what says the playhead
 * has actually reached it. [markerVisible] is separate because a future UI
 * may want to draw the upcoming transition before it begins. When [blocked]
 * is true nothing should happen at all and [reason] says why.
 */
data class TransitionPlan(
    val shouldStart: Boolean = false,
    val markerVisible: Boolean = false,
    val blocked: Boolean = false,
    val reason: String = "",
    val transitionStart: Double = 0.0,
    val transitionEnd: Double = 0.0,
    val fadeSeconds: Double = 0.0,
    val transitionStyle: TransitionStyle = TransitionStyle.EQUAL_POWER,
    /** Where in the incoming track playback should be cued to when the transition opens. */
    val incomingCueTime: Double = 0.0,
    /** Where the incoming track's arrangement lands, on its own timeline. */
    val incomingHandoffTime: Double = 0.0,
    val incomingPlaybackRate: Double = 1.0,
    /**
     * Advanced Automix: the speed-up the *outgoing* track is brought up to
     * over its last beats before the blend, when it is the slower of the two.
     * Tempo only ever moves up, so at most one of this and
     * [incomingPlaybackRate] is above 1, and neither is ever below it.
     */
    val outgoingPlaybackRate: Double = 1.0,
    val handoffStartSeconds: Double = 0.0,
    val handoffDuration: Double = 0.0,
    val pickupSeconds: Double = 0.0,
    val transitionBeats: Int = 0,
    val bassSwap: Boolean = false,
    val handoffFraction: Double = HANDOFF_FRACTION,
    val bedPosition: Double = BED_POSITION,
    val bassSwapFraction: Double = 0.7,
    val filterSweep: Double = 0.0,
    /** The stable per-pair gesture for [TransitionStyle.DJ_FILTER]. */
    val filterVariant: FilterTransitionVariant = FilterTransitionVariant.SWEEP,
    /**
     * How strongly the two tracks are expected to be singing over each other
     * through this overlap, 0..1; see [vocalOverlapAmount].
     *
     * Separate from [filterSweep] because they answer to different things.
     * [filterSweep] is a property of the *style* — a filter ride is what an
     * unmatched pair gets instead of a beat-matched blend — and a blend
     * deliberately asks for none of it. This is a property of the *material*, and
     * it applies whatever the style: two tempo-matched vocals sitting on the same
     * grid is the case a blend handles worst, precisely because nothing about the
     * arrangement is going to separate them.
     *
     * Zero whenever either track lacks a vocal mask, which leaves every style
     * rendering exactly as it did before this existed.
     */
    val vocalOverlap: Double = 0.0,
    /**
     * The tempi the overlap is built on, which are **not** the analyses' raw
     * BPMs: the incoming one has been folded into the outgoing one's octave.
     * Zero when the plan is not beat-matched.
     */
    val outgoingBpm: Double = 0.0,
    val incomingBpm: Double = 0.0,
    /**
     * One beat of the outgoing grid in seconds, or 0 when that grid is not
     * trusted. Advanced Automix lands its gain and filter moves on these beats.
     */
    val beatSeconds: Double = 0.0,
    /**
     * Both grids are trusted and the tempi are matched, so the renderer may
     * nudge the incoming track's speed to hold the two beats in phase.
     */
    val phaseLock: Boolean = false,
    /**
     * Whether this plan actually runs through the native ramped renderer, so
     * the incoming deck playback rate is real and measurable. The classic
     * beatmatch path keeps a no-op tempo write, so its plans carry false and a
     * local slowdown there stays refused.
     */
    val nativeRamped: Boolean = false,
    /**
     * Advanced Automix echo out: the delay time, in seconds, the outgoing
     * track's last beat repeats at as it leaves, or 0 for no echo. Planned only
     * where the outgoing file has at least [ECHO_REPEATS] of these left after
     * the transition ends, because the tail is generated from the outgoing
     * player's own (silenced) audio and dies with it.
     */
    val echoSeconds: Double = 0.0,
    /** Why the policy landed where it did, when it declined to be more ambitious. */
    val policyReasons: List<String> = emptyList(),
    /**
     * Colour chosen for this pair. The loop mix does not use it: a qualifying
     * loop is taken before [DjMix.annotate] runs.
     */
    val djEffect: DjEffect = DjEffect.NONE,
    /** Mid cut on the outgoing vocal while the incoming vocal is in, from the umxhq mask. */
    val vocalHandoff: Boolean = false,
    /** Bars of a lyric-free outro phrase to repeat. 0 when the outro is already long enough. */
    val phraseExtendBars: Int = 0,
    val outSection: String = "",
    val inSection: String = "",
    /** One line for the Automix readout: style, sections, key, effects. */
    val mixSummary: String = "",
    /**
     * Ease the incoming deck up by [BUILDUP_RISE] across the overlap, with
     * the flanger, then let it go. A pre-chorus or a rising instrumental
     * into a chorus. Not a beatmatch, and not applied over two singers.
     */
    val buildup: Boolean = false,
    /**
     * Muffle (vc69 P0): outgoing lead beats the low-pass closes over before
     * the bass swap. Zero for every other style.
     */
    val muffleLeadBeats: Int = 0,
    /**
     * Return-leg length for styles whose filter parks at a floor mid-overlap
     * and must leave over beats, not over the teardown open() glide. Symmetric with [muffleLeadBeats]: [exitBeats] is the trailing span
     * the cutoff spends returning to full-band (20000 Hz). Zero for styles
     * that never park. The plan model owns this span — the renderer never
     * invents it — so a teardown that only parks can be audited against the
     * plan's own data.
     */
    val exitBeats: Int = 0,
    /**
     * Vocal-depth-modulated filter ride (vc69 P1): how much of the filter
     * cutoff travel each slice of the overlap spends, as a running maximum
     * over the fraction of the overlap. Empty means full spend everywhere
     * (the historical behavior) and is what unmeasured pairs carry.
     */
    val vocalDepthCurve: List<Double> = emptyList(),
    /**
     * Which vc69 alternative family produced this plan, or empty for every
     * other style. Carried for telemetry and the transition evidence dump.
     */
    val alternativeKind: String = "",
) {
    /** Convenience for the engine, which schedules in milliseconds. */
    val fadeMs: Long get() = (fadeSeconds * 1000).roundToLong()
}

/**
 * Plans the transition out of [currentTrack] and into [nextTrack].
 *
 * Called on every playback tick; the returned plan describes the transition
 * whether or not it has started yet.
 *
 * @param albumSequential true only when this is an album genuinely being
 *   played through in order, which is the sole case that earns a gapless
 *   handoff instead of a mix.
 * @param currentTime the outgoing track's playhead, in seconds.
 * @param advanced Advanced Automix: length from how well the pair agrees and
 *   how energetic the join is, edges on whole bars and downbeats, speed-ups
 *   of up to 10% to beat-match, an echo out where the outgoing track leaves
 *   singing, and another exit tried when the planned one sings over the
 *   incoming track. Off leaves every plan exactly as before.
 * @param phraseBars Apple-style Automix phrase length: `8` or `16` asks for a
 *   mix whose overlap is exactly that many bars of the outgoing track's own
 *   4/4 grid, both edges snapped onto phrase boundaries or downbeats. `0` (the
 *   default) leaves the adaptive overlap above in charge. A pair that cannot
 *   support a bar-aligned mix of that length — no trusted tempo, or too short
 *   to fit it — falls back to the ordinary smart plan rather than forcing a
 *   ragged one.
 * @param mixWindow Confine the mix to the last and first [MIX_WINDOW_SECONDS] of the pair — see
 *   [clampToMixWindow]. The desktop asks for it; the phone leaves it off, so its plans are
 *   unchanged.
 * @param listenerSpeed Listener playback speed used to keep a local blend's composed deck speed
 *   at or below 3x. The default preserves the historical 1x planning behavior for other callers.
 */
fun planTransition(
    analysis: TrackAnalysis = TrackAnalysis(),
    nextAnalysis: TrackAnalysis = TrackAnalysis(),
    currentTrack: TransitionTrackInfo? = null,
    nextTrack: TransitionTrackInfo? = null,
    currentTime: Double = 0.0,
    duration: Double = 0.0,
    fadeSeconds: Double = 6.0,
    minFadeSeconds: Double = 1.0,
    mode: CrossfadeMode = CrossfadeMode.STANDARD,
    albumSequential: Boolean = false,
    advanced: Boolean = false,
    phraseBars: Int = 0,
    mixWindow: Boolean = false,
    lyricsOut: List<LyricCue> = emptyList(),
    lyricsIn: List<LyricCue> = emptyList(),
    listenerSpeed: Double = 1.0,
    allowRecoveryCrossfade: Boolean = false,
): TransitionPlan {
    val plan = planTransitionInner(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        currentTrack = currentTrack,
        nextTrack = nextTrack,
        currentTime = currentTime,
        duration = duration,
        fadeSeconds = fadeSeconds,
        minFadeSeconds = minFadeSeconds,
        mode = mode,
        albumSequential = albumSequential,
        advanced = advanced,
        phraseBars = phraseBars,
        mixWindow = mixWindow,
        lyricsOut = lyricsOut,
        lyricsIn = lyricsIn,
        listenerSpeed = listenerSpeed,
    )
    val windowed = if (!mixWindow) plan else clampToMixWindow(
        plan,
        length = max(duration.orZero(), plannerTrackDurationSeconds(currentTrack)),
        playbackTime = max(0.0, currentTime.orZero()),
    )
    if (mode != CrossfadeMode.SMART) return windowed
    // djmix15-pilot: DjMix.annotate ALWAYS runs (also on :catch-up) so
    // catch-up keeps its DJ metadata (djEffect, vocalHandoff, buildup,
    // sections, mixSummary for renderFor/describeAutomix). Timing mutation
    // is guarded inside DjMix helpers (placeOnStructure / clashFilter /
    // ensureOverlap) — never here.
    val decorated = VocalDepthFilter.withVocalDepth(
        DjMix.annotate(
            windowed,
            analysis,
            nextAnalysis,
            lyricsOut,
            lyricsIn,
            currentTrack?.id.orEmpty(),
            nextTrack?.id.orEmpty(),
        ),
        analysis,
        nextAnalysis,
    )
    // vc69 P2: keep per-transition evidence for the steady paths too. The
    // decline branch already recorded rich gate maps for its plans (the
    // decline reason or an alternative kind carries that); anything else is
    // recorded here so every armed transition stays replayable.
    // Missing evidence is not permission to edit the songs. Dedicated native
    // techniques still get their normal chance in the controller before this
    // dry queue-advance plan is adopted. Crossfade remains available for an
    // explicit recovery after a native transition has failed, or STANDARD mode.
    val nextLength = max(nextAnalysis.duration.orZero(), plannerTrackDurationSeconds(nextTrack))
    val uncertainClassic = decorated.transitionStyle == TransitionStyle.EQUAL_POWER ||
        decorated.filterVariant == FilterTransitionVariant.ECHO_RIDE ||
        decorated.reason.contains("analysis-fallback") || decorated.reason.contains("plain-crossfade") ||
        (decorated.reason.contains("decline-to-mix") && decorated.alternativeKind.isEmpty() &&
            LocalBlendAdmission.MARKER !in decorated.policyReasons)
    val chosen = if (!allowRecoveryCrossfade && !decorated.blocked && uncertainClassic) {
        val fallbackLength = max(duration.orZero(), plannerTrackDurationSeconds(currentTrack))
        tieredFallback(decorated, analysis, nextAnalysis, currentTrack, nextTrack, lyricsOut, lyricsIn, fallbackLength, nextLength, currentTime, advanced)
    } else decorated
    if (chosen.transitionStyle == TransitionStyle.NATURAL ||
        (chosen.alternativeKind.isEmpty() && !chosen.reason.contains("decline-to-mix"))) {
        TransitionEvidenceCache.record(standardEvidence(chosen, analysis, nextAnalysis))
    }
    return chosen
}

/**
 * vc91: the ordered rescue ladder behind the classic uncertain verdict.
 *
 * The previous behaviour converted any uncertain classic plan straight into the
 * zero overlap natural handoff, which threw away real mix opportunities the
 * stored analysis still supported. The ladder keeps each rung honest and reaches
 * the honest handoff last, reserved for the cases no mix can rescue.
 *
 * Rung order, most ambitious first. Every rung is a plan this function can
 * actually execute as a classic plan, and each carries its own marker:
 *
 *  0. `decline-holds-no-overlap` — an honest decline keeps its zero-overlap
 *     [TransitionStyle.NATURAL] handoff; no rung may overrule that evidence.
 *  1. `fallback-beatmix` — a real beat-matched blend ([TransitionStyle.DJ_BLEND]).
 *  2. `safety-crossfade` — 2-4 beats, equal-power, no rate change, downbeat
 *     snapped, ending at the content end.
 *  3. `fallback-muffle` — a vc69 alternative family for an unmixable pair.
 *  4. `fallback-echo` — a selective echo out on a trusted outgoing grid.
 *  5. `tiered-fallback-exhausted` — the honest [TransitionStyle.NATURAL] handoff.
 *
 * The native bridge and the crown loop are deliberately **not** built here: the
 * controller owns both (`CrossfadeController.loopPlanFor` / `nativeBridge`) and
 * tries them before it adopts this classic plan. Synthesising a crown plan here
 * produced a zero-fade `DJ_BLEND` that the controller could never arm, which
 * silently suppressed the rungs below it.
 *
 * Stem-ducked crown loops are not admitted here either: no [TrackAnalysis] field
 * exposes a separated vocal stem, so the planner has no evidence that
 * `dryMinusVocal` could run. The clean-loop veto therefore stays honest rather
 * than being relaxed on a guess.
 */
private fun tieredFallback(
    decorated: TransitionPlan,
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    currentTrack: TransitionTrackInfo?,
    nextTrack: TransitionTrackInfo?,
    lyricsOut: List<LyricCue>,
    lyricsIn: List<LyricCue>,
    length: Double,
    nextLength: Double,
    currentTime: Double,
    advanced: Boolean,
): TransitionPlan {
    val playbackTime = max(0.0, currentTime.orZero())
    val beatSeconds = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: (analysis.bpm.orZero().takeIf { it > 1.0 }?.let { 60.0 / it } ?: 0.5)
    val outgoingGrid = analysis.beatConfidence.orZero() >= MIN_DJ_CONFIDENCE
    val incomingGrid = nextAnalysis.beatConfidence.orZero() >= MIN_DJ_CONFIDENCE
    // 0. An honest decline has already weighed every overlap gesture and said
    // no: hard folded tempo delta plus a key clash or two live leads. The rungs
    // below would re-propose exactly what that evidence vetoed, and for these
    // pairs the zero-overlap natural handoff is the pinned outcome the listener
    // hears as their own crossfade into the incoming intro. The decline reason
    // survives the rewrite ("natural-end:decline-to-mix"), and this is a
    // per-verdict hold mirroring the decline clause of `uncertainClassic` — not
    // the old blanket empty-alternativeKind collapse: uncertain crossfade and
    // echo-ride plans below still get every rung.
    if (decorated.reason.contains("decline-to-mix") && decorated.alternativeKind.isEmpty() &&
        LocalBlendAdmission.MARKER !in decorated.policyReasons
    ) {
        return naturalHandoffPlan(max(length, decorated.transitionEnd), decorated.reason).copy(
            policyReasons = (decorated.policyReasons + "natural-handoff-no-overlap" + "decline-holds-no-overlap")
                .distinct(),
        )
    }
    // 1. A genuine beat-matched blend, not the filter ride mixPlan falls back
    // to on its own: an unmatched pair has no shared grid, and its rate change
    // would buy nothing the safety crossfade below cannot do at 1.0x.
    if (advanced && length > 0.0 && nextLength > 0.0 && outgoingGrid && incomingGrid) {
        val blend = mixPlan(
            analysis, nextAnalysis, length, "content_end", nextLength,
            PlannerPairTraits(analysis, nextAnalysis),
        )
        if (blend.transitionStyle == TransitionStyle.DJ_BLEND) {
            val annotatedBlend = VocalDepthFilter.withVocalDepth(
                DjMix.annotate(
                    blend,
                    analysis,
                    nextAnalysis,
                    lyricsOut,
                    lyricsIn,
                    currentTrack?.id.orEmpty(),
                    nextTrack?.id.orEmpty(),
                ),
                analysis,
                nextAnalysis,
            )
            val started = playbackTime >= annotatedBlend.transitionStart
            return plannerCapIncomingSkip(
                annotatedBlend.copy(
                    shouldStart = started,
                    policyReasons = (decorated.policyReasons + annotatedBlend.policyReasons + "fallback-beatmix").distinct(),
                    reason = if (started) "smart-beatmix-fallback" else "before-smart-beatmix-fallback-window",
                    mixSummary = annotatedBlend.mixSummary.ifBlank { "beat blend" },
                ),
                nextAnalysis,
                nextLength,
            )
        }
    }
    // Folded tempo compatibility: classic has a no-op stretcher, so >10%
    // tempo gaps are never forced into a 1.0x equal-power double-kick flam;
    // CrossfadeController routes those through LoopMix / nativeBridge instead.
    val outBpm = analysis.bpm.orZero()
    val inBpm = nextAnalysis.bpm.orZero()
    val foldedDelta = if (outBpm > 0.0 && inBpm > 0.0) {
        val folded = alignTempoOctave(outBpm, inBpm)
        if (folded > 0.0) abs(folded / outBpm - 1.0) else Double.POSITIVE_INFINITY
    } else {
        0.0
    }
    val tempoCompatible = foldedDelta <= 0.10 + 1e-6

    // 2. The bounded safety crossfade. Only the outgoing grid is required when
    // incoming tempo is unknown or within the 10% folded stretch window.
    if (outgoingGrid && length > 0.0 && tempoCompatible) {
        // 2-4 beats, floored at 2 s only where four beats are longer than that.
        // A bare coerceIn(2.0, 4.0 * beatSeconds) throws above 120 BPM, which is
        // most dance music, and the fade must end at the content end instead of
        // running past it, or the rung never arms.
        val fourBeats = 4.0 * beatSeconds
        val targetOverlap = clamp(2.0 * beatSeconds, minOf(2.0, fourBeats), maxOf(2.0, fourBeats))
        val start = plannerAlignedTransitionStart(
            analysis,
            max(0.0, length - targetOverlap),
            length - 0.05,
            preferEarlier = false,
            minimum = 0.0,
        )
        val fade = length - start
        if (fade > 0.0) {
            val started = playbackTime >= start
            return plannerCapIncomingSkip(
                TransitionPlan(
                    shouldStart = started,
                    markerVisible = true,
                    transitionStart = start,
                    transitionEnd = length,
                    fadeSeconds = fade,
                    transitionStyle = TransitionStyle.EQUAL_POWER,
                    incomingCueTime = plannerIncomingStartPoint(nextAnalysis),
                    beatSeconds = beatSeconds,
                    vocalOverlap = 0.0,
                    incomingPlaybackRate = 1.0,
                    outgoingPlaybackRate = 1.0,
                    policyReasons = (decorated.policyReasons + "safety-crossfade").distinct(),
                    reason = if (started) "smart-safety-crossfade" else "before-smart-safety-crossfade-window",
                    mixSummary = "safety crossfade",
                ),
                nextAnalysis,
                nextLength,
            )
        }
    }
    // 3. A carve/muffle/drop/bridge gesture, only for a pair nothing can mix.
    if (PLANNER_UNMIXABLE_VETO_ENABLED && plannerIsUnmixablePair(analysis, nextAnalysis)) {
        TransitionAlternatives.select(
            out = analysis,
            inn = nextAnalysis,
            outAnchor = length,
            nextLength = nextLength,
            playbackTime = playbackTime,
            beatSec = beatSeconds,
        )?.let { candidate ->
            return plannerCapIncomingSkip(
                candidate.plan.copy(
                    policyReasons = (decorated.policyReasons + candidate.plan.policyReasons + "fallback-muffle")
                        .distinct(),
                    reason = if (playbackTime >= candidate.plan.transitionStart) {
                        "smart-muffle-fallback"
                    } else {
                        "before-smart-muffle-fallback"
                    },
                ),
                nextAnalysis, nextLength,
            )
        }
    }
    // 4. A selective echo out. Below the DJ confidence floor the honest answer is
    // the handoff below: an echo on a grid we do not trust is a guess, not a
    // rescue. Requiring the grid also makes the NATURAL rung reachable at all,
    // since beatSeconds carries a 0.5 s default even for a blank analysis.
    if (beatSeconds > 0.0 && outgoingGrid && length > 0.0 && inBpm <= 0.0) {
        val echoSeconds = beatSeconds.coerceIn(0.25, 1.0)
        val start = max(0.0, length - echoSeconds)
        val started = playbackTime >= start
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = start,
            transitionEnd = length,
            fadeSeconds = echoSeconds,
            transitionStyle = TransitionStyle.DJ_FILTER,
            incomingCueTime = plannerIncomingStartPoint(nextAnalysis),
            beatSeconds = beatSeconds,
            echoSeconds = echoSeconds,
            policyReasons = (decorated.policyReasons + "fallback-echo").distinct(),
            reason = if (started) "smart-echo-fallback" else "before-smart-echo-fallback-window",
            mixSummary = "selective echo out",
        )
    }
    // 5. Honest handoff. naturalHandoffPlan's own marker is kept: the
    // controller's provisional NATURAL re-plan keys on it, and dropping it would
    // latch the handoff for the rest of the song.
    return naturalHandoffPlan(max(length, decorated.transitionEnd), decorated.reason).copy(
        policyReasons = (decorated.policyReasons + "natural-handoff-no-overlap" + "tiered-fallback-exhausted")
            .distinct(),
    )
}

/** The player, not a second-deck fade, owns an uneventful end-of-track handoff. */
fun naturalHandoffPlan(duration: Double, reason: String): TransitionPlan {
    if (!duration.isFinite() || duration <= 0.0) return plannerBlocked("no-duration")
    return TransitionPlan(
        transitionStyle = TransitionStyle.NATURAL,
        transitionStart = duration,
        transitionEnd = duration,
        reason = "natural-end:$reason",
        mixSummary = "natural handoff",
        policyReasons = listOf("natural-handoff-no-overlap"),
    )
}

/**
 * Apple-style Automix window: mixing is confined to the last [MIX_WINDOW_SECONDS] of the outgoing
 * track and the first [MIX_WINDOW_SECONDS] of the incoming one. The planner's own overlaps are far
 * shorter than this in practice, so the clamp is a guard that keeps the invariant true no matter how
 * the anchors are ranked — it never lengthens a mix, only refuses to let one start too early or cue
 * too deep into the next track.
 */
private fun clampToMixWindow(plan: TransitionPlan, length: Double, playbackTime: Double): TransitionPlan {
    if (plan.blocked || length <= 0.0) return plan
    var result = plan
    val anchor = if (plan.transitionEnd > 1.0 && plan.transitionEnd < length) plan.transitionEnd else length
    val earliestStart = (anchor - PLANNER_MIX_WINDOW_SECONDS).coerceAtLeast(0.0)
    if (result.transitionStart < earliestStart) {
        val end = result.transitionEnd.coerceAtLeast(earliestStart)
        result = result.copy(
            shouldStart = playbackTime >= earliestStart,
            transitionStart = earliestStart,
            transitionEnd = end,
            fadeSeconds = (end - earliestStart).coerceAtLeast(0.0),
        )
    }
    if (result.incomingCueTime > PLANNER_MIX_WINDOW_SECONDS) {
        result = result.copy(incomingCueTime = PLANNER_MIX_WINDOW_SECONDS)
    }
    return result
}

private fun planTransitionInner(
    analysis: TrackAnalysis = TrackAnalysis(),
    nextAnalysis: TrackAnalysis = TrackAnalysis(),
    currentTrack: TransitionTrackInfo? = null,
    nextTrack: TransitionTrackInfo? = null,
    currentTime: Double = 0.0,
    duration: Double = 0.0,
    fadeSeconds: Double = 6.0,
    minFadeSeconds: Double = 1.0,
    mode: CrossfadeMode = CrossfadeMode.STANDARD,
    albumSequential: Boolean = false,
    advanced: Boolean = false,
    phraseBars: Int = 0,
    mixWindow: Boolean = false,
    lyricsOut: List<LyricCue> = emptyList(),
    lyricsIn: List<LyricCue> = emptyList(),
    listenerSpeed: Double = 1.0,
): TransitionPlan {
    val length = max(duration.orZero(), plannerTrackDurationSeconds(currentTrack))
    val playbackTime = max(0.0, currentTime.orZero())
    if (length <= 0) return plannerBlocked("no-duration")

    val standardFade = clamp(fadeSeconds, minFadeSeconds, 12.0)
    if (mode != CrossfadeMode.SMART) {
        val nextLengthEarly = max(nextAnalysis.duration.orZero(), plannerTrackDurationSeconds(nextTrack))
        return plannerStandardWithCatchUpOrBlock(
            plannerStandardTransition(length, playbackTime, standardFade, minFadeSeconds),
            analysis, nextAnalysis, nextLengthEarly, playbackTime, length,
        )
    }

    if (length < PLANNER_MIN_SMART_DURATION_SECONDS) {
        return plannerBlocked("short-duration-guard", transitionStart = length, transitionEnd = length)
    }

    val analyzedContentEnd = analysis.contentEndTime.orZero().takeIf { it != 0.0 } ?: length
    val finalMixAnchor = if (analyzedContentEnd > 0 && analyzedContentEnd <= length) {
        analyzedContentEnd
    } else {
        length
    }
    // Ranked once and kept: the best is the anchor (what [resolveMixOutAnchor]
    // answers), and the rest are where an advanced plan looks for a vocal-free exit.
    val rankedMixOuts = rankMixOutCandidates(analysis, finalMixAnchor, length)
    val mixOutAnchor = rankedMixOuts.firstOrNull()
        ?.let { MixOutAnchor(it.time, it.type, it.discardedMusicSeconds) }
        ?: MixOutAnchor(finalMixAnchor, "content_end", 0.0)
    val hasInteriorMixOut = mixOutAnchor.time < finalMixAnchor - 1

    if (albumSequential && plannerSameAlbum(currentTrack, nextTrack) && !hasInteriorMixOut) {
        val transitionStart = max(0.0, length - 0.45)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = length,
            fadeSeconds = 0.12,
            transitionStyle = TransitionStyle.GAPLESS,
            reason = if (started) "same-album-gapless" else "before-gapless-window",
        )
    }

    if (plannerIsSpokenOrLive(currentTrack) || plannerIsSpokenOrLive(nextTrack)) {
        return plannerBlocked("blocked-speech-or-live")
    }

    if (!plannerAnalysisReadyForTrack(analysis, currentTrack, requireFull = true, durationSec = length, currentSec = playbackTime) ||
        !plannerAnalysisReadyForTrack(nextAnalysis, nextTrack)
    ) {
        // djmix15: standard fallback gets the same universal catch-up and
        // near-EOF block as the primary plan — S.A.D's 22 s stale window
        // came through this early return.
        val base = plannerStandardTransition(
            length,
            playbackTime,
            max(standardFade, 8.0),
            minFadeSeconds,
            "smart-analysis-fallback",
        )
        val nextLengthFallback = max(nextAnalysis.duration.orZero(), plannerTrackDurationSeconds(nextTrack))
        return plannerStandardWithCatchUpOrBlock(base, analysis, nextAnalysis, nextLengthFallback, playbackTime, length)
    }

    val preferredMixAnchor = min(length, mixOutAnchor.time)
    val mixAnchor =
        if (playbackTime >= preferredMixAnchor - 0.05 && preferredMixAnchor < finalMixAnchor - 1) {
            finalMixAnchor
        } else {
            preferredMixAnchor
        }

    val policy = assessTransitionTier(analysis, nextAnalysis, advanced)
    if (policy.tier == TransitionTier.PLAIN_CROSSFADE) {
        val transitionStart = max(0.0, mixAnchor - standardFade)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = mixAnchor,
            fadeSeconds = mixAnchor - transitionStart,
            transitionStyle = TransitionStyle.EQUAL_POWER,
            incomingCueTime = plannerIncomingStartPoint(nextAnalysis),
            policyReasons = policy.reasons,
            reason = if (started) "smart-plain-crossfade" else "before-plain-crossfade-window",
        )
    }

    val nextLength = max(nextAnalysis.duration.orZero(), plannerTrackDurationSeconds(nextTrack))

    // Offer the ordinary upstream phase-lockable blend before the whole-track
    // preference. It earns this exception only on its final, decorated source
    // spans: neither a strong grid nor whole-track vocals decide local safety.
    var localBlendRefusal: String? = null
    if (advanced && (plannerIsUnmixablePair(analysis, nextAnalysis) ||
            mixPlan(analysis, nextAnalysis, mixAnchor, mixOutAnchor.type, nextLength,
                PlannerPairTraits(analysis, nextAnalysis)).transitionStyle != TransitionStyle.DJ_BLEND)) {
        localBlendRefusal = LocalBlendAdmission.gridRefusal(analysis, nextAnalysis)
        if (localBlendRefusal == null) {
            val traits = PlannerPairTraits(analysis, nextAnalysis)
            var candidate = plannerCapIncomingSkip(
                mixPlan(analysis, nextAnalysis, mixAnchor, mixOutAnchor.type, nextLength, traits, upstreamBlend = true),
                nextAnalysis, nextLength,
            )
            if (mixWindow) candidate = clampToMixWindow(candidate, length, playbackTime)
            candidate = DjMix.annotate(candidate, analysis, nextAnalysis, lyricsOut, lyricsIn)
            // Structure decoration can move a cue; apply the source skip cap before admission too.
            candidate = plannerCapIncomingSkip(candidate, nextAnalysis, nextLength)
            val admission = LocalBlendAdmission.evaluate(candidate, analysis, nextAnalysis, lyricsOut, lyricsIn)
            localBlendRefusal = admission.refusal
            val speedFits = localBlendFitsDeckSpeedCap(listenerSpeed, candidate.incomingPlaybackRate)
            if (admission.admitted && !speedFits) localBlendRefusal = "listener-speed-cap"
            if (admission.admitted && speedFits && playbackTime <= candidate.transitionStart + 0.5) {
                return candidate.copy(
                    shouldStart = playbackTime >= candidate.transitionStart,
                    vocalOverlap = requireNotNull(admission.simultaneousFraction),
                    djEffect = DjEffect.NONE,
                    echoSeconds = 0.0,
                    phaseLock = false,
                    buildup = false,
                    policyReasons = policy.reasons + plannerAutomixNotes(analysis, nextAnalysis) + LocalBlendAdmission.MARKER,
                    reason = if (playbackTime >= candidate.transitionStart) LocalBlendAdmission.MARKER
                        else "before-${LocalBlendAdmission.MARKER}",
                )
            }
            if (admission.admitted && speedFits) localBlendRefusal = "blend-window-passed"
        }
    }
    val localBlendNotes = listOfNotNull(localBlendRefusal?.let { "local-blend-refused:$it" })

    // A pair no speed-up can match that also clashes in key or sings on both
    // sides has nothing a DJ gesture can fix: every one of them put two loud,
    // unsynced songs on top of each other mid-arrangement. The listener's own
    // crossfade at the real end of the song, into the incoming intro, is what
    // sounds right for these.
    if (PLANNER_UNMIXABLE_VETO_ENABLED && plannerIsUnmixablePair(analysis, nextAnalysis)) {
        // 2B bounded decline (supersedes the vc67 raw >=15s EQUAL_POWER
        // phrase-length floor): a 4 s filtered/carved DJ_FILTER gesture
        // (outgoing presence, incoming HP, one-kick bass handoff when both
        // kicks are present — never double bass) ending at content_end. The
        // unsettled 15 s full-band overlap put two unsynced arrangements on
        // top of each other; 4 s rides the equal-power fall to zero ending
        // at the anchor, so there is no violent cutoff. Lengthening or
        // shortening it stays in 3.5–4.5 s; [DjMix] must not resize it
        // (see the decline exemption in ensureOverlap). Other fallbacks
        // (unclear-analysis standard fade, album gapless) are untouched.
        val beatSec = analysis.beatInterval.orZero().takeIf { it > 0.05 }
            ?: analysis.bpm.orZero().takeIf { it > 1.0 }?.let { 60.0 / it }
            ?: 0.5
        // vc69 P0: offer muffle, drop-in, or beatless-bridge before the
        // bounded decline. Each family requires bass-curve evidence, so
        // pairs with no measured curves decline exactly as before.
        TransitionAlternatives.select(
            out = analysis,
            inn = nextAnalysis,
            outAnchor = finalMixAnchor,
            nextLength = nextLength,
            playbackTime = playbackTime,
            beatSec = beatSec,
        )?.let { candidate ->
            TransitionEvidenceCache.record(
                TransitionAlternatives.evidenceFor(analysis, nextAnalysis, candidate, finalMixAnchor),
            )
            return plannerCapIncomingSkip(candidate.plan.copy(policyReasons = candidate.plan.policyReasons + localBlendNotes),
                nextAnalysis, nextLength)
        }
        val declineFade = 4.0.coerceAtMost(finalMixAnchor.coerceAtLeast(0.5))
        val transitionStart = max(0.0, finalMixAnchor - declineFade)
        // Bounded-decline carve: one dedicated gesture, never the sweep or
        // the handoff. Both keep midrange in play too long for raw tempi.
        // Octave ambiguity is detection-only and recorded in the evidence
        // gates below (never a warn here: planning runs on every tick).
        val declineVariant = FilterTransitionVariant.DECLINE
        val incomingCueTime = plannerIncomingStartPoint(nextAnalysis)
        val started = playbackTime >= transitionStart
        val measuredOverlap = plannerPlannedVocalOverlap(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            transitionStart = transitionStart,
            transitionEnd = finalMixAnchor,
            incomingCueTime = incomingCueTime,
            incomingPlaybackRate = 1.0,
        )
        TransitionEvidenceCache.record(
            TransitionEvidenceCache.Evidence(
                outId = analysis.trackId,
                inId = nextAnalysis.trackId,
                style = "decline",
                reason = if (playbackTime >= transitionStart) "decline-to-mix" else "before-decline-to-mix",
                gates = buildMap {
                    put("decline-to-mix", declineToMixDetail(analysis, nextAnalysis) ?: "declined")
                    localBlendRefusal?.let { put("local-blend", it) }
                    TransitionAlternatives.octaveGateDescription(analysis, nextAnalysis)?.let {
                        put("octave-relationship", it)
                    }
                },
                scores = mapOf("vocalOverlap" to measuredOverlap),
                flowScore = BeatFlow.beatFlow(
                    analysis,
                    nextAnalysis,
                    transitionStart,
                    incomingCueTime,
                    finalMixAnchor - transitionStart,
                    anchorTime = incomingCueTime,
                ).score,
            ),
        )
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = finalMixAnchor,
            fadeSeconds = finalMixAnchor - transitionStart,
            transitionStyle = TransitionStyle.DJ_FILTER,
            // The carve always hands the low end over once, inside the
            // ride: bassSwap labels the performed handoff honestly. The old
            // flag pinned it to the BASS_HANDOFF variant only, while
            // rideFilterSweep handed over unconditionally.
            bassSwap = true,
            bassSwapFraction = 0.5,
            filterSweep = FILTER_SWEEP,
            filterVariant = declineVariant,
            beatSeconds = beatSec,
            incomingCueTime = incomingCueTime,
            incomingHandoffTime = incomingCueTime,
            vocalOverlap = maxOf(measuredOverlap, 0.85),
            policyReasons = policy.reasons + "decline-to-mix" + listOfNotNull(declineToMixDetail(analysis, nextAnalysis)) + localBlendNotes,
            reason = if (started) "decline-to-mix" else "before-decline-to-mix",
        )
    }

    // Apple-style Automix: when the listener fixed the phrase length, a
    // bar-aligned mix of exactly that many bars takes priority. A null answer
    // means the evidence will not support it, so the adaptive plan below runs
    // unchanged.
    if (phraseBars == PHRASE_BARS_SHORT || phraseBars == PHRASE_BARS_LONG) {
        plannerPhraseBarPlan(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            length = length,
            nextLength = nextLength,
            phraseBars = phraseBars,
            mixAnchor = mixAnchor,
            advanced = advanced,
        )?.let { plan ->
            if (playbackTime < plan.transitionEnd) {
                val started = playbackTime >= plan.transitionStart
                return plannerCapIncomingSkip(
                    plan.copy(
                        shouldStart = started,
                        policyReasons = policy.reasons,
                        reason = if (started) "smart-phrase-$phraseBars-bar" else "before-phrase-$phraseBars-bar",
                    ),
                    nextAnalysis,
                    nextLength,
                )
            }
        }
    }

    plannerPhraseSwitch(analysis, nextAnalysis, length, nextLength, advanced)
        ?.takeIf { playbackTime < it.transitionEnd }
        ?.let { plan ->
            val started = playbackTime >= plan.transitionStart
            return plannerCapIncomingSkip(
                plan.copy(
                    shouldStart = started,
                    policyReasons = policy.reasons,
                    reason = if (started) "smart-phrase-switch" else "before-phrase-switch",
                ),
                nextAnalysis,
                nextLength,
            )
        }

    val traits = if (advanced) PlannerPairTraits(analysis, nextAnalysis) else null
    val primary = mixPlan(analysis, nextAnalysis, mixAnchor, mixOutAnchor.type, nextLength, traits)
    // An exit where the outgoing track is still singing over the incoming one
    // is worth trading for another the analysis ranked, if one halves the
    // collision. Only planned while it could still be taken: once the playhead
    // is past a start, that plan has already been armed or lost.
    val plan = if (traits != null &&
        primary.vocalOverlap > PLANNER_VOCAL_REROUTE_OVERLAP &&
        playbackTime < primary.transitionStart
    ) {
        rankedMixOuts.asSequence()
            .filter { abs(it.time - mixAnchor) > 1.0 && it.time <= length }
            .take(PLANNER_MAX_REROUTE_CANDIDATES)
            .map { mixPlan(analysis, nextAnalysis, it.time, it.type, nextLength, traits) }
            .filter { playbackTime < it.transitionStart }
            .minByOrNull { it.vocalOverlap }
            ?.takeIf { it.vocalOverlap <= primary.vocalOverlap * PLANNER_VOCAL_REROUTE_GAIN }
            ?: primary
    } else {
        primary
    }
    // djmix14 late-scrub catch-up: a plan whose start is already behind the
    // playhead must re-anchor ahead instead of showing a dead 0s countdown.
    // djmix14-review: pass through capIncomingSkip (was bare return) so the
    // incoming cue invariant holds for catch-up plans too.
    CatchUpPlanner.reanchor(plan, analysis, nextAnalysis, playbackTime, length)?.let { caught ->
        return plannerCapIncomingSkip(caught, nextAnalysis, nextLength)
    }
    // djmix15: too-late near EOF. reanchor returned null (no room for a
    // lead + dissolve) but the playhead is 20 s past a stale start — the
    // `started` fallthrough below would fire a truncated micro-fade
    // (LAST GOODBYE: 0.18 s before EOF, 0.322 s fade, delta +20090).
    // With <2.0 s of effective room and >0.5 s past the start, block so
    // ExoPlayer advances cleanly at EOF instead.
    if (playbackTime > plan.transitionStart + 0.5) {
        val contentEndEq = analysis.contentEndTime
        val effectiveEndEq = if (contentEndEq.isFinite() && contentEndEq > playbackTime + 2.0 && contentEndEq > 0.0) {
            contentEndEq.coerceAtMost(length)
        } else {
            length
        }
        if (effectiveEndEq - playbackTime < 2.0) {
            return plannerBlocked("too-late-near-eof", transitionStart = plan.transitionStart, transitionEnd = plan.transitionEnd)
        }
    }
    val started = playbackTime >= plan.transitionStart
    return plannerCapIncomingSkip(
        plan.copy(
            shouldStart = started,
            policyReasons = policy.reasons + plannerAutomixNotes(analysis, nextAnalysis) + localBlendNotes,
            reason = if (started) plan.reason else "before-${plan.reason}",
        ),
        nextAnalysis,
        nextLength,
    )
}

/** Whether the actual Float playback-speed composition can preserve a local blend's admitted rate. */
fun localBlendFitsDeckSpeedCap(listenerSpeed: Double, admittedIncomingRate: Double): Boolean {
    if (!listenerSpeed.isFinite() || listenerSpeed <= 0.0 ||
        !admittedIncomingRate.isFinite() || admittedIncomingRate <= 0.0
    ) return false
    val listener = listenerSpeed.toFloat()
    val incoming = admittedIncomingRate.toFloat()
    val composite = listener * incoming
    return listener.isFinite() && incoming.isFinite() && composite.isFinite() && composite <= 3f
}

/**
 * The adaptive overlap ending at [mixAnchor]: a beat-matched blend when the
 * tempi agree, a filter ride when they don't. Timing only; the caller
 * decides whether it has started.
 */
private fun mixPlan(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    mixAnchor: Double,
    mixOutType: String,
    nextLength: Double,
    traits: PlannerPairTraits?,
    upstreamBlend: Boolean = false,
): TransitionPlan {
    val currentBpm = traits?.outgoingBpm ?: analysis.bpm.orZero()
    val nextBpm = traits?.incomingBpm ?: nextAnalysis.bpm.orZero()
    val gridBeatSeconds = if (traits?.outgoingGrid == true) 60 / currentBpm else 0.0
    val filterVariant = when {
        // Tempo gap past the stretch limit: echo on the outgoing bar before a
        // plain filter ride. A loop mix never reaches this function.
        traits?.tempoFar == true -> FilterTransitionVariant.ECHO_RIDE
        traits != null -> plannerFallbackVariantFor(
            analysis, nextAnalysis, mixAnchor, if (currentBpm > 0) 60 / currentBpm else 0.0,
        )
        else -> FilterTransitionVariant.SWEEP
    }
    val energy = if (traits != null) {
        plannerEnergyScale(analysis, nextAnalysis, traits, mixAnchor, gridBeatSeconds)
    } else {
        1.0
    }
    val (overlap, transitionBeats, incomingPlaybackRate) = plannerAdaptiveOverlap(
        analysis, nextAnalysis, traits, energy, filterVariant, currentBpm, nextBpm,
    )
    val handoffBpm = if (currentBpm > 0) currentBpm else nextBpm
    // Symmetric ±8% folded tempo tolerance (matching bkmasterplayer; ±6% when dual-vocal).
    val tempoCap = if (traits?.vocalConflict == true) 0.06 else PhraseConfig.TEMPO_HARD_DELTA
    val foldedRateDelta = if (currentBpm > 0 && nextBpm > 0) {
        abs(1.0 - incomingRateBetween(currentBpm, nextBpm))
    } else {
        Double.POSITIVE_INFINITY
    }
    val withinCap = foldedRateDelta <= tempoCap
    val sameBeatBlend = (upstreamBlend && incomingPlaybackRate >= 1.0 && traits?.phaseLockable == true &&
        LocalBlendAdmission.gridRefusal(analysis, nextAnalysis) == null) || (
        currentBpm > 0 && nextBpm > 0 &&
            withinCap &&
            (analysis.beatConfidence.orZero() >= MIN_DJ_CONFIDENCE && nextAnalysis.beatConfidence.orZero() >= MIN_DJ_CONFIDENCE)
        ) || (
            traits?.phaseLockable == true &&
                analysis.beatConfidence.orZero() >= MIN_DJ_CONFIDENCE &&
                nextAnalysis.beatConfidence.orZero() >= MIN_DJ_CONFIDENCE &&
                foldedRateDelta <= PhraseConfig.TEMPO_HARD_DELTA
        )
    val outgoingArrangementOverlap = 0.0
    val arrangementEnd = max(0.0, mixAnchor - outgoingArrangementOverlap)
    // Advanced: the exit pulled back onto a downbeat, so whatever the incoming
    // track lands on at the end of the overlap lands on the one.
    val mixEnd = if (gridBeatSeconds > 0) {
        plannerOnDownbeatBefore(analysis, arrangementEnd, 4 * gridBeatSeconds)
    } else {
        arrangementEnd
    }
    val maxBeats = if (traits != null) PLANNER_MAX_ADVANCED_BEATS.toDouble() else PLANNER_AUTO_TRANSITION_MAX_BEATS
    val bar = if (handoffBpm > 0) 4 * 60 / handoffBpm else 2.0
    val fallbackMaxSeconds = when (filterVariant) {
        FilterTransitionVariant.SWEEP,
        FilterTransitionVariant.BASS_HANDOFF,
        FilterTransitionVariant.ECHO_RIDE,
        FilterTransitionVariant.DECLINE,
        -> max(8.0 * bar, 8.0).coerceAtMost(24.0)
    }
    val maxSeconds = when {
        traits != null && !sameBeatBlend -> fallbackMaxSeconds
        traits != null -> max(16.0 * bar, 16.0).coerceAtMost(40.0)
        else -> PLANNER_AUTO_TRANSITION_MAX_SECONDS
    }
    val maximumOverlap = minOf(
        if (handoffBpm > 0) (maxBeats * 60) / handoffBpm else maxSeconds,
        maxSeconds,
        mixEnd * 0.4,
        if (nextLength > 0) nextLength * 0.4 else maxSeconds,
    )
    val handoffBeats = if (sameBeatBlend) 32 else 16
    val beatSeconds = if (handoffBpm > 0) 60 / handoffBpm else 0.5
    val handoffSeconds = if (handoffBpm > 0) {
        clamp((handoffBeats * 60) / handoffBpm, 2.0, if (sameBeatBlend) 6.0 else 5.0)
    } else {
        4.0
    }
    val analyzedPickup = nextAnalysis.audibleStartTime ?: nextAnalysis.pickupTime
    val pickupSeconds = if (analyzedPickup != null && analyzedPickup.isFinite() && analyzedPickup >= 0) {
        analyzedPickup
    } else {
        0.0
    }
    val incomingDropTime = incomingCuePoint(nextAnalysis, latestIncomingCue(nextLength))
    val requestedIncomingHandoff = incomingDropTime
    val maxIncomingHandoff = nextLength - MIN_INCOMING_CLEARANCE_SECONDS
    val incomingHandoffTime =
        if (maxIncomingHandoff >= incomingDropTime) {
            min(requestedIncomingHandoff, maxIncomingHandoff)
        } else {
            incomingDropTime
        }
    val rawIncomingCueTime = plannerIncomingStartPoint(nextAnalysis)

    val finalIncomingCueTime: Double
    val transitionStart: Double

    if (sameBeatBlend && beatSeconds > 0) {
        val introDropTime = incomingHandoffTime / max(0.8, incomingPlaybackRate)
        val introRoom = (incomingHandoffTime - rawIncomingCueTime).coerceAtLeast(0.0) / max(0.8, incomingPlaybackRate)
        val targetBarsFloor = if (traits?.vocalConflict == true || traits?.keyClash == true) 4.0 * bar else 8.0 * bar
        val shortIntro = incomingHandoffTime > rawIncomingCueTime + 1.0 &&
            introRoom >= 4.0 * bar - 0.5 &&
            introRoom < targetBarsFloor
        val effectiveFloor = if (shortIntro) max(4.0 * bar, introRoom) else targetBarsFloor
        val lowerClamp = if (shortIntro) min(4.0 * bar, maximumOverlap) else min(12.0, maximumOverlap)
        val desiredOverlap = max(introDropTime, min(effectiveFloor, maximumOverlap))
        val clamped = clamp(desiredOverlap, lowerClamp, maximumOverlap)
        val totalOverlap = if (traits != null) plannerWholeBars(clamped, beatSeconds, maximumOverlap) else clamped
        val targetStart = max(0.0, mixEnd - totalOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maximumOverlap)
        transitionStart = plannerAlignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = false,
            minimum = earliestTransitionStart,
        )
        finalIncomingCueTime =
            max(0.0, incomingHandoffTime - (mixEnd - transitionStart) * incomingPlaybackRate)
    } else {
        val clamped = clamp(overlap, min(handoffSeconds, maximumOverlap), maximumOverlap)
        val actualOverlap = if (traits != null) plannerWholeBars(clamped, beatSeconds, maximumOverlap) else clamped
        val targetStart = max(0.0, mixEnd - actualOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maximumOverlap)
        transitionStart = plannerAlignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = false,
            minimum = earliestTransitionStart,
        )
        // No shared grid to land the incoming arrangement on, so the overlap
        // sits over the incoming intro instead: cueing an unmatched song at its
        // drop put two full arrangements on top of each other.
        finalIncomingCueTime = rawIncomingCueTime
    }

    val alignedOverlap = mixEnd - transitionStart
    val hasBassContent = analysis.lowEnergyCurve.isNotEmpty() || nextAnalysis.lowEnergyCurve.isNotEmpty()
    val measuredVocalOverlap = plannerPlannedVocalOverlap(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        transitionStart = transitionStart,
        transitionEnd = mixEnd,
        incomingCueTime = finalIncomingCueTime,
        incomingPlaybackRate = incomingPlaybackRate,
    )
    // A missing time mask must not mean "instrumental". Whole-track vocal
    // likelihood is less precise, but it is a safe fallback when either side
    // has not produced its mask yet.
    val vocalOverlap = max(
        measuredVocalOverlap,
        if (
            traits?.vocalConflict == true &&
            (analysis.vocalActivityMask.isEmpty() || nextAnalysis.vocalActivityMask.isEmpty())
        ) PLANNER_VOCAL_MASK_MISSING_PROTECTION else 0.0,
    )
    val vocalSafeFallback = !sameBeatBlend && vocalOverlap >= PLANNER_VOCAL_SAFE_FALLBACK_THRESHOLD
    // Echo on the bar when the tempos cannot be stretched together. Each song
    // keeps its own tempo; the outgoing beat repeats for one bar as it leaves.
    // A matched blend stays dry — an echo there would flam the shared grid.
    val echoSeconds = if (traits?.tempoFar == true && beatSeconds > 0) {
        beatSeconds.coerceIn(0.25, 1.0)
    } else {
        0.0
    }
    val resolvedFilterVariant = if (traits?.tempoFar == true) {
        FilterTransitionVariant.ECHO_RIDE
    } else if (vocalSafeFallback) {
        FilterTransitionVariant.SWEEP
    } else {
        filterVariant
    }
    return TransitionPlan(
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = mixEnd,
        fadeSeconds = alignedOverlap,
        handoffStartSeconds = 0.0,
        handoffDuration = alignedOverlap,
        incomingCueTime = finalIncomingCueTime,
        incomingHandoffTime = incomingHandoffTime,
        incomingPlaybackRate = if (upstreamBlend && sameBeatBlend) plannerRoundRate(incomingPlaybackRate)
            else plannerIncomingRateFor(incomingPlaybackRate, traits != null),
        outgoingPlaybackRate = plannerOutgoingRateFor(incomingPlaybackRate, traits != null),
        pickupSeconds = pickupSeconds,
        transitionBeats = transitionBeats,
        bassSwap = sameBeatBlend || hasBassContent,
        transitionStyle = if (sameBeatBlend) TransitionStyle.DJ_BLEND else TransitionStyle.DJ_FILTER,
        // The two styles are alternatives, not a scale: a matched pair hands the
        // low end over on a beat and otherwise stays open, while an unmatched
        // pair has no shared grid to hand anything over on and instead pulls the
        // outgoing track behind a closing low-pass. Left at zero on the blend
        // branch so the renderer doesn't do both at once.
        filterSweep = if (sameBeatBlend) 0.0 else FILTER_SWEEP,
        filterVariant = if (sameBeatBlend) FilterTransitionVariant.SWEEP else resolvedFilterVariant,
        vocalOverlap = vocalOverlap,
        beatSeconds = gridBeatSeconds,
        // Tempo is fixed while audible. Live phase-lock pulses changed
        // AudioTrack playback parameters mid-buffer and cracked on some phones.
        phaseLock = false,
        echoSeconds = echoSeconds,
        // Classic dual-player blend: its incoming tempo write is the no-op one
        // (TransitionFilterProcessor pins beatmatchTempo to 1f), so it never
        // claims a native ramped rate. A sub-1.0 local slowdown therefore stays
        // refused in LocalBlendAdmission, exactly as upstream did.
        nativeRamped = false,
        reason = "smart-duration",
    )
}
