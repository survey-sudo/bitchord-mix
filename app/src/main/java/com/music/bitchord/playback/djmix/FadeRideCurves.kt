package com.music.bitchord.playback.djmix

import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.playback.TransitionFilterProcessor
import com.music.bitchord.playback.TransitionFilters
import com.music.bitchord.playback.smart.DjEffect
import com.music.bitchord.playback.smart.FILTER_ECHO_AT
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.MixGain
import com.music.bitchord.playback.smart.TransitionAlternatives
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.VocalDepthFilter
import com.music.bitchord.playback.smart.echoTailSeconds
import com.music.bitchord.playback.smart.effectSettings
import kotlin.math.pow
import kotlin.math.round

/**
 * Frozen per-transition curve specification derived once when a blend arms or
 * is previewed so 30 ms fade ticks allocate nothing.
 *
 * Responsibility: pre-resolves beat/bar-snapped transition cue fractions, fader
 * shapes, echo windows, and depth curves for [FadeRideCurves].
 * Inputs: scalar transition parameters from `TransitionPlan` / `Render`.
 * Thread owner: constructed on the main/controller thread and read-only thereafter.
 */
internal class RideSpec(
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
    val djEffect: DjEffect = DjEffect.NONE,
    val vocalHandoff: Boolean = false,
    val beatSecondsF: Float = 0f,
    val buildup: Boolean = false,
    /** Muffle lead beats; 0 for other styles. */
    val muffleLeadBeats: Int = 0,
    /** Return-leg beats: trailing span the filter leaves to full-band. 0 for styles that never park. */
    val exitBeats: Int = 0,
    /** Vocal-depth curve over the overlap; empty means full spend. */
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
    val swapWidth = if (grid > 0) FadeRideCurves.SWAP_BEATS / grid else 2 * FadeRideCurves.BASS_SWAP_WIDTH
    val swapFrom = if (grid > 0) swapAt - swapWidth else swapAt - FadeRideCurves.BASS_SWAP_WIDTH
    val entryOpenBy = onBar(
        when (filterVariant) {
            FilterTransitionVariant.SWEEP -> FadeRideCurves.ENTRY_OPEN_BY
            FilterTransitionVariant.BASS_HANDOFF -> 0.38
            FilterTransitionVariant.ECHO_RIDE -> 0.52
            // The decline carve rides its own shared envelope
            // (TransitionAlternatives.declineInHpAt); this field is
            // unused on that path. Value kept consistent for the
            // derived vocal EntryOpenBy contract.
            FilterTransitionVariant.DECLINE -> TransitionAlternatives.DECLINE_IN_OPEN_BY
        },
    )
    val vocalEntryOpenBy = onBar(entryOpenBy + (FadeRideCurves.VOCAL_SAFE_ENTRY_OPEN_BY - entryOpenBy) * clash)
    val blendEntryOpenBy = onBar(
        FadeRideCurves.BLEND_ENTRY_OPEN_BY +
            (FadeRideCurves.BLEND_ENTRY_CLASH_OPEN_BY - FadeRideCurves.BLEND_ENTRY_OPEN_BY) * clash,
    )
    val blendExitFrom = onBar(
        FadeRideCurves.BLEND_EXIT_FROM +
            (FadeRideCurves.BLEND_EXIT_CLASH_FROM - FadeRideCurves.BLEND_EXIT_FROM) * clash,
    )
    val filterSwapAt = onBar(
        when (filterVariant) {
            FilterTransitionVariant.SWEEP -> FadeRideCurves.FILTER_BASS_SWAP_AT
            FilterTransitionVariant.BASS_HANDOFF -> 0.43
            FilterTransitionVariant.ECHO_RIDE -> 0.58
            // The carve hands its low end over at the same bar as a
            // sweep: one kick, never two.
            FilterTransitionVariant.DECLINE -> FadeRideCurves.FILTER_BASS_SWAP_AT
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
    val echoKill = if (grid > 0) minOf(FadeRideCurves.ECHO_KILL_BEATS / grid, 1.0 - echoAt) else 0.0

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
        !advanced -> FadeRideCurves.HANDOFF_AT
        style == TransitionStyle.DJ_BLEND && bassSwap -> swapAtF
        style == TransitionStyle.DJ_FILTER &&
            (filterVariant == FilterTransitionVariant.BASS_HANDOFF || filterVariant == FilterTransitionVariant.DECLINE) ->
            filterSwapAtF
        else -> FadeRideCurves.HANDOFF_AT
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

/**
 * Evaluates gain, filter cutoff, echo-out, colour, and headroom curves across a
 * classic/fallback transition's `[0, 1]` progress, and drives side-masked
 * [TransitionFilters] writes when incoming and outgoing sinks sit at different
 * buffer leads ahead of the speaker.
 *
 * Responsibility: pure curve evaluation + side-aimed [TransitionFilters] dispatch
 * (M9 slice C-1). Owns no player lifecycle or phase state.
 * Inputs: immutable [RideSpec], normalized progress floats, raw session [filters],
 * and role-mapped track [tracks].
 * Thread owner: main/controller thread ([MainThread] for filter-applying rides;
 * pure gain/level helpers have no thread state and allocate nothing per tick).
 */
@OptIn(UnstableApi::class)
internal class FadeRideCurves(
    private val filters: TransitionFilters,
    private val tracks: TransitionFilters,
) {
    /**
     * Which side a ride pass may aim. Both, unless [rideFilters] is evaluating
     * the style at two different points in the blend — one per side, because
     * each side's filter runs its own distance ahead of the speaker.
     */
    private var aimIncoming = true
    private var aimOutgoing = true

    /** Pre-allocated proxy gating [tracks] writes by [aimIncoming]/[aimOutgoing]. */
    private val aimedTracks: TransitionFilters = object : TransitionFilters {
        override fun incoming(lowPassHz: Float, highPassHz: Float) {
            if (!aimIncoming) return
            tracks.incoming(lowPassHz, highPassHz)
        }

        override fun outgoing(lowPassHz: Float, highPassHz: Float) {
            if (!aimOutgoing) return
            tracks.outgoing(lowPassHz, highPassHz)
        }

        override fun incomingLeadMs(): Long = tracks.incomingLeadMs()

        override fun outgoingLeadMs(): Long = tracks.outgoingLeadMs()

        override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) {
            if (!aimOutgoing) return
            tracks.outgoingEcho(delaySeconds, send, dry)
        }

        override fun incomingTempo(rate: Float) = tracks.incomingTempo(rate)

        override fun outgoingTempo(rate: Float) = tracks.outgoingTempo(rate)

        override fun outgoingColour(effect: DjEffect, amount: Float, beatSeconds: Float, scoop: Float) {
            if (!aimOutgoing) return
            tracks.outgoingColour(effect, amount, beatSeconds, scoop)
        }

        override fun incomingColour(effect: DjEffect, amount: Float, beatSeconds: Float, scoop: Float) {
            if (!aimIncoming) return
            tracks.incomingColour(effect, amount, beatSeconds, scoop)
        }

        override fun incomingMidDb(gainDb: Float) {
            if (!aimIncoming) return
            tracks.incomingMidDb(gainDb)
        }

        override fun outgoingMidDb(gainDb: Float) {
            if (!aimOutgoing) return
            tracks.outgoingMidDb(gainDb)
        }
    }

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
    @MainThread
    fun rideFilters(spec: RideSpec, incomingAt: Float, outgoingAt: Float?) {
        if (outgoingAt == incomingAt) {
            rideStyle(spec, incomingAt)
            return
        }
        // Incoming first: the few progress-independent branches that open both
        // filters agree with themselves, and the outgoing pass after this one
        // then has the last word on its own side.
        aimOutgoing = false
        try {
            rideStyle(spec, incomingAt)
        } finally {
            aimOutgoing = true
        }
        if (outgoingAt == null) return
        aimIncoming = false
        try {
            rideStyle(spec, outgoingAt)
        } finally {
            aimIncoming = true
        }
    }

    /**
     * Renders [spec]'s [TransitionStyle] as filtering across the blend.
     *
     * Driven off the same `progress` as the gains so the two stay locked:
     * a pause parks the filter exactly where it parks the fade.
     */
    @MainThread
    fun rideStyle(spec: RideSpec, progress: Float) {
        when (spec.style) {
            TransitionStyle.DJ_FILTER -> when (spec.filterVariant) {
                FilterTransitionVariant.BASS_HANDOFF -> rideFallbackBassHandoff(spec, progress)
                FilterTransitionVariant.DECLINE -> rideDecline(spec, progress)
                FilterTransitionVariant.SWEEP,
                FilterTransitionVariant.ECHO_RIDE,
                -> rideFilterSweep(spec, progress)
            }
            TransitionStyle.DJ_BLEND ->
                if (spec.bassSwap) rideBassSwap(spec, progress) else rideVocalSeparation(spec, progress)
            // GAPLESS is an album being played through, where any filtering would
            // be an edit the record didn't ask for — so it stays open whatever
            // the material does.
            TransitionStyle.GAPLESS, TransitionStyle.NATURAL -> filters.open()
            TransitionStyle.MUFFLE -> rideMuffle(spec, progress)
            TransitionStyle.DROP_IN -> rideDropIn(progress)
            TransitionStyle.BEATLESS_BRIDGE -> rideBeatlessBridge(spec, progress)
            TransitionStyle.EQUAL_POWER -> rideVocalSeparation(spec, progress)
        }
        if (spec.echo) rideEchoOut(spec, progress)
        rideColour(spec, progress)
    }

    /** Flanger, plate, loop roll and the vocal-mid scoop, in amounts fixed by the plan. */
    @MainThread
    fun rideColour(spec: RideSpec, progress: Float) {
        val effect = spec.djEffect
        val settings = effectSettings(effect)
        val amount = when (effect) {
            // Last several bars of an instrumental blend, loud enough to hear.
            DjEffect.FLANGER -> when {
                spec.buildup -> settings.wet * (0.35f + 0.65f * progress)
                progress > 0.30f -> settings.wet
                else -> 0f
            }
            // Only the tail. A wash from the first sample is what was muffling mixes.
            DjEffect.REVERB -> settings.wet * ((progress - 0.75f) / 0.25f).coerceIn(0f, 1f)
            DjEffect.LOOP_ROLL -> if (progress > 0.35f) 1f else 0f
            // A throw on the last bars. The tempo-gap echo is [rideEchoOut], which
            // owns the dry kill; this one leaves the song intact.
            DjEffect.ECHO -> if (!spec.echo && progress > 0.72f) settings.wet else 0f
            else -> 0f
        }
        val scoop = if (spec.vocalHandoff && progress > 0.35f) 0.85f else 0f
        val beat = spec.beatSecondsF
        aimedTracks.outgoingColour(effect, amount, beat, scoop)
        aimedTracks.incomingColour(DjEffect.NONE, 0f, beat, 0f)
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
    @MainThread
    fun rideEchoOut(spec: RideSpec, progress: Float) {
        val p = progress.toDouble()
        val handover = ((p - (spec.echoAt - spec.swapWidth)) / spec.swapWidth).coerceIn(0.0, 1.0)
        aimedTracks.outgoing(TransitionFilterProcessor.OPEN_HZ, bassCutoff(handover))
        if (p < spec.echoFrom) return
        val send = if (p >= spec.echoAt) 0.0 else (p - spec.echoFrom) / (spec.echoAt - spec.echoFrom)
        aimedTracks.outgoingEcho(spec.echoSecondsF, send.toFloat(), echoDry(spec, progress))
    }

    /** The outgoing dry level in an echo out: whole until the echo beat, gone a fraction of a beat after. */
    fun echoDry(spec: RideSpec, progress: Float): Float =
        (1.0 - ((progress - spec.echoAt) / spec.echoKill).coerceIn(0.0, 1.0)).toFloat()

    /**
     * How loud the outgoing track actually is at [progress] — uncapped, since
     * an echo tail runs past the end of the blend — for the headroom trim: its
     * fader, or with an echo out, where the fader stays up for the repeats and
     * the chain does the fading, the louder of its dry signal and its repeats.
     * The repeats sum with a full-level incoming track in the platform mixer
     * just as a dry signal would.
     */
    fun outgoingLevel(spec: RideSpec, progress: Float, wallSpan: Float): Float {
        if (!spec.echo) return mixFall(spec, progress)
        val sinceEcho = (progress - spec.echoAt.toFloat()) * wallSpan
        val repeats = if (sinceEcho > 0f) sinceEcho / (spec.echoSecondsF * 1000f) else 0f
        val wet = if (sinceEcho > 0f) ECHO_LEVEL * ECHO_DECAY.pow(repeats) else 0f
        return maxOf(echoDry(spec, progress), wet)
    }

    /**
     * The minimum intervention: pull two colliding vocals apart, and otherwise
     * leave the spectrum alone.
     *
     * Zero overlap leaves both sides open, which is exactly what these styles did
     * before, so nothing changes for a pair that doesn't collide or for either
     * track lacking a vocal mask.
     */
    @MainThread
    fun rideVocalSeparation(spec: RideSpec, progress: Float) {
        val amount = spec.vocalOverlap.coerceIn(0.0, 1.0)
        if (amount <= 0.0) {
            filters.open()
            return
        }
        // Clean <=200 Hz bass swap + vocal-mid dip without LP muffle / HP demuffle.
        val depth = VocalDepthFilter.valueAtCurve(spec.depthCurve, progress.toDouble())
        val p = progress.toDouble()
        val handoverAmount = ((p - (spec.swapAt - spec.swapWidth)) / spec.swapWidth).coerceIn(0.0, 1.0)
        aimedTracks.outgoing(TransitionFilterProcessor.OPEN_HZ, bassCutoff(handoverAmount))
        aimedTracks.incoming(TransitionFilterProcessor.OPEN_HZ, bassCutoff(1.0 - handoverAmount))
        aimedTracks.outgoingMidDb((-5.5 * amount * depth).toFloat())
        aimedTracks.incomingMidDb(0f)
    }

    /**
     * Muffle: the outgoing deck keeps its level while a steep low-pass
     * closes over it to the muffle floor across the entry portion, then
     * reopens floor->open across the plan's return leg ([RideSpec.exitBeats]).
     * The incoming enters clean and the low end still changes hands once,
     * on the swap downbeat. At progress 1 the outgoing low-pass is
     * full-band, so teardown never needs the open() glide on this deck.
     */
    @MainThread
    fun rideMuffle(spec: RideSpec, progress: Float) {
        val sweep = spec.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            filters.open()
            return
        }
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val lead = spec.muffleLeadBeats.takeIf { it > 0 } ?: 0
        val exit = spec.exitBeats.takeIf { it > 0 }
            ?: TransitionAlternatives.MUFFLE_EXIT_BEATS
        val entryFraction = if (lead > 0) {
            ((lead - exit).toDouble() / lead.toDouble()).coerceIn(0.05, 0.95)
        } else {
            spec.swapAt.coerceIn(0.05, 0.95)
        }
        val cutoff = TransitionAlternatives.muffleCutoffAt(
            progress = progress.toDouble(),
            sweep = sweep,
            entryFraction = entryFraction,
            openHz = open,
            floorHz = MUFFLE_FLOOR_HZ,
        )
        val p = progress.toDouble()
        val handover = ((p - (spec.swapAt - spec.swapWidth)) / spec.swapWidth).coerceIn(0.0, 1.0)
        aimedTracks.outgoing(cutoff.toFloat(), bassCutoff(handover))
        aimedTracks.incoming(TransitionFilterProcessor.OPEN_HZ, bassCutoff(1.0 - handover))
        aimedTracks.incomingMidDb(0f)
        aimedTracks.outgoingMidDb(0f)
    }

    /**
     * Drop-in: the incoming track is already at full level on its
     * kick-locked anchor; the outgoing one cuts inside the first half of
     * the overlap and leaves its echo tail (rideEchoOut keeps ringing it).
     * Nothing filters either deck here.
     */
    @MainThread
    fun rideDropIn(progress: Float) {
        val half = (progress.toDouble() / 0.5).coerceIn(0.0, 1.0)
        aimedTracks.outgoing(TransitionFilterProcessor.OPEN_HZ, bassCutoff(half))
        aimedTracks.incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        aimedTracks.incomingMidDb(0f)
        aimedTracks.outgoingMidDb(0f)
    }

    /**
     * Beatless bridge: both decks through a soft low-pass for the span, then
     * both leave park->open over the plan's return leg ([RideSpec.exitBeats])
     * so the surviving incoming deck is full-band at progress 1 and never
     * needs the teardown open() glide while audible.
     */
    @MainThread
    fun rideBeatlessBridge(spec: RideSpec, progress: Float) {
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val amount = spec.vocalOverlap.coerceIn(0.0, 1.0)
        val depth = VocalDepthFilter.valueAtCurve(spec.depthCurve, progress.toDouble())
        val baseFloor = glide(open, VOCAL_SEPARATION_FLOOR_HZ, amount)
        val floor = VocalDepthFilter.scaleTravel(open, baseFloor, depth)
        @Suppress("UNUSED_VARIABLE")
        val separated = glide(open, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE))
        val totalBeats = spec.beats.coerceAtLeast(1.0)
        val exitBeats: Double = (spec.exitBeats.takeIf { it > 0 }
            ?: TransitionAlternatives.BRIDGE_EXIT_BEATS).toDouble()
        val returnStart = (1.0 - (exitBeats / totalBeats)).coerceIn(0.05, 0.95)
        val p = progress.toDouble()
        if (p < returnStart) {
            // Entry close, rescaled so it reaches park by the return start.
            val entryProg = (p / returnStart).coerceIn(0.0, 1.0)
            val entrySeparated = glide(open, floor, entryProg.pow(FILTER_SWEEP_SHAPE))
            aimedTracks.outgoing(minOf(entrySeparated, BRIDGE_LOW_PASS_HZ).toFloat(), TransitionFilterProcessor.OFF_HZ)
            aimedTracks.incoming(BRIDGE_LOW_PASS_HZ.toFloat(), TransitionFilterProcessor.OFF_HZ)
        } else {
            // Return leg: park at the floor the entry reached, reopen to open.
            val park = minOf(separatedAtReturnStart(spec, returnStart), BRIDGE_LOW_PASS_HZ)
            val reopened = TransitionAlternatives.bridgeReopenCutoffAt(p, returnStart, park, open)
            aimedTracks.outgoing(reopened.toFloat(), TransitionFilterProcessor.OFF_HZ)
            aimedTracks.incoming(reopened.toFloat(), TransitionFilterProcessor.OFF_HZ)
        }
        aimedTracks.incomingMidDb(0f)
        aimedTracks.outgoingMidDb(0f)
    }

    /** Parked cutoff the bridge entry reaches at [returnStart] for the reopen leg. */
    private fun separatedAtReturnStart(spec: RideSpec, returnStart: Double): Double {
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val amount = spec.vocalOverlap.coerceIn(0.0, 1.0)
        val depthAtReturn = VocalDepthFilter.valueAtCurve(spec.depthCurve, returnStart)
        val baseFloor = glide(open, VOCAL_SEPARATION_FLOOR_HZ, amount)
        val floor = VocalDepthFilter.scaleTravel(open, baseFloor, depthAtReturn)
        return glide(open, floor, 1.0.pow(FILTER_SWEEP_SHAPE))
    }

    @MainThread
    fun rideFilterSweep(spec: RideSpec, progress: Float) {
        val sweep = spec.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            filters.open()
            return
        }
        // Clean sweep: outgoing LP never below 2800 Hz, incoming HP is bass-only (<=200 Hz), vocal-mid dip separates.
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val entry = glide(open, FILTER_ENTRY_HZ, sweep)
        val floor = glide(open, FILTER_FLOOR_HZ, sweep)
        val cutoff = glide(entry, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE))
        val handover = ((progress - (spec.filterSwapAt - spec.swapWidth)) / spec.swapWidth).coerceIn(0.0, 1.0)
        val p = progress.toDouble()
        aimedTracks.outgoing(cutoff.toFloat(), bassCutoff(handover))
        aimedTracks.incoming(TransitionFilterProcessor.OPEN_HZ, bassCutoff(1.0 - handover))
        aimedTracks.outgoingMidDb((-5.0 * spec.clash * p).toFloat())
        aimedTracks.incomingMidDb(0f)
    }

    /**
     * Bounded-decline carve ([FilterTransitionVariant.DECLINE]): the 4 s
     * separation ride for declined pairs. Delegates to the shared envelope
     * ([TransitionAlternatives.declineOutLpAt] / [TransitionAlternatives.declineInHpAt])
     * so the numbers the shared tests assert are the numbers the renderer rides:
     * the outgoing low-pass muffles to its floor (kick bed only) while the
     * incoming is held to presence, and the low end still changes hands once
     * so two kicks never flam. At progress 1 the outgoing is parked at its
     * floor and silent while the incoming is fully open and alone, so
     * teardown needs no open() glide on either deck.
     */
    @MainThread
    fun rideDecline(spec: RideSpec, progress: Float) {
        val sweep = spec.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            filters.open()
            return
        }
        val outLp = TransitionAlternatives.declineOutLpAt(progress.toDouble(), sweep)
        val inHp = TransitionAlternatives.declineInHpAt(progress.toDouble(), sweep)
        val handover = ((progress - (spec.filterSwapAt - spec.swapWidth)) / spec.swapWidth).coerceIn(0.0, 1.0)
        aimedTracks.outgoing(outLp.toFloat(), bassCutoff(handover))
        aimedTracks.incoming(TransitionFilterProcessor.OPEN_HZ, maxOf(inHp.toFloat(), bassCutoff(1.0 - handover)))
        aimedTracks.incomingMidDb(0f)
        aimedTracks.outgoingMidDb(0f)
    }

    /**
     * A compact fallback move for unmatched grids: keep the mids recognisable,
     * remove the two kicks from each other's way, then dip the outgoing mids
     * as the incoming low end takes over.
     */
    @MainThread
    fun rideFallbackBassHandoff(spec: RideSpec, progress: Float) {
        val p = progress.toDouble()
        val handover = ((p - (spec.filterSwapAt - spec.swapWidth)) / spec.swapWidth).coerceIn(0.0, 1.0)
        // Clean bass swap + mid dip without deep LP/HP muffle.
        aimedTracks.outgoing(TransitionFilterProcessor.OPEN_HZ, bassCutoff(handover))
        aimedTracks.incoming(TransitionFilterProcessor.OPEN_HZ, bassCutoff(1.0 - handover))
        aimedTracks.outgoingMidDb((-5.5 * spec.clash * p).toFloat())
        aimedTracks.incomingMidDb(0f)
    }

    /**
     * Where the incoming track's high-pass sits at [progress].
     *
     * Rides from [topHz] down to nothing by [openBy] of the fade, scaled by [amount],
     * using [ENTRY_SHAPE] so the corner lingers in the vocal presence range.
     */
    fun entryHighPass(progress: Float, amount: Double, topHz: Double, openBy: Double): Float {
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
    fun glide(from: Double, to: Double, amount: Double): Double =
        from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    /**
     * Hands the low end from one track to the other, once, at the beat the
     * planner chose, with a light vocal-mid dip when vocals clash.
     */
    @MainThread
    fun rideBassSwap(spec: RideSpec, progress: Float) {
        // Pure <=200 Hz bass swap + vocal-mid dip without deep HP/LP muffle.
        val handover = ((progress - spec.swapFrom) / spec.swapWidth).coerceIn(0.0, 1.0)
        val clash = spec.vocalOverlap.coerceIn(0.0, 1.0)
        aimedTracks.incoming(TransitionFilterProcessor.OPEN_HZ, bassCutoff(1.0 - handover))
        aimedTracks.outgoing(TransitionFilterProcessor.OPEN_HZ, bassCutoff(handover))
        if (clash > 0.15) {
            aimedTracks.outgoingMidDb((-4.5 * clash).toFloat())
        } else {
            aimedTracks.outgoingMidDb(0f)
        }
        aimedTracks.incomingMidDb(0f)
    }

    /**
     * The outgoing track's low-pass through a beat-matched blend: open until
     * [RideSpec.blendExitFrom], then closing to [BLEND_EXIT_LOW_PASS_HZ]
     * (or [BLEND_EXIT_CLASH_LOW_PASS_HZ] at full vocal clash) by the end.
     */
    fun blendExitLowPass(spec: RideSpec, progress: Float, clash: Double): Float {
        val from = spec.blendExitFrom
        val amount = ((progress - from) / (1.0 - from)).coerceIn(0.0, 1.0)
        val floor = glide(BLEND_EXIT_LOW_PASS_HZ, BLEND_EXIT_CLASH_LOW_PASS_HZ, clash)
        return glide(TransitionFilterProcessor.OPEN_HZ.toDouble(), floor, amount).toFloat()
    }

    /** [amount] 0 leaves the low end alone; 1 lifts it out entirely. */
    fun bassCutoff(amount: Double): Float =
        glide(TransitionFilterProcessor.OFF_HZ.toDouble(), BASS_SWAP_HZ, amount).toFloat()

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
    fun mixRise(spec: RideSpec, progress: Float): Float {
        // The decline carve owns its faders: its loud regions never coincide,
        // which is what hides the raw-tempo flam. Same numbers as the tests.
        if (spec.filterVariant == FilterTransitionVariant.DECLINE) {
            return TransitionAlternatives.declineInGainAt(progress.toDouble()).toFloat()
        }
        if (!spec.advanced) return riseGain(progress)
        val styled = when (spec.style) {
            TransitionStyle.DJ_BLEND ->
                if (spec.bassSwap) held(spec, riseGain(progress), riseGain(progress / spec.swapAtF)) else riseGain(progress)
            // One smooth pair for every filter gesture: the filters are what
            // tell the variants apart, and the low end changes hands on a beat
            // in all of them, so no fader needs to sit at full to make room.
            TransitionStyle.DJ_FILTER -> riseGain(progress.pow(FILTER_RISE_SHAPE))
            else -> riseGain(progress)
        }
        val vocalSafe = riseGain(progress.pow(VOCAL_SAFE_RISE_SHAPE))
        return styled + (vocalSafe - styled) * spec.clash.toFloat()
    }

    /**
     * The outgoing fader, [mixRise]'s partner: held at full in a blend until
     * [RideSpec.fallFrom] — the swap, but never past the midpoint or within a
     * bar of the end — and a smooth fall in a filter ride, where the closing
     * low-pass is already taking it away.
     */
    fun mixFall(spec: RideSpec, progress: Float): Float {
        // See mixRise: the carve's early fall keeps the loud regions disjoint.
        if (spec.filterVariant == FilterTransitionVariant.DECLINE) {
            return TransitionAlternatives.declineOutGainAt(progress.toDouble()).toFloat()
        }
        if (!spec.advanced) return fallGain(progress)
        val styled = when (spec.style) {
            TransitionStyle.DJ_BLEND -> {
                if (!spec.bassSwap) {
                    fallGain(progress)
                } else {
                    val from = spec.fallFrom
                    held(spec, fallGain(progress), if (progress <= from) 1f else fallGain((progress - from) / (1f - from)))
                }
            }
            TransitionStyle.DJ_FILTER -> fallGain(progress.pow(FILTER_FALL_SHAPE))
            else -> fallGain(progress)
        }
        val vocalSafe = fallGain(progress.pow(VOCAL_SAFE_FALL_SHAPE))
        return styled + (vocalSafe - styled) * spec.clash.toFloat()
    }

    /** [dj] as far as [RideSpec.holdFaders] allows, the rest of the way back toward [plain]. */
    private fun held(spec: RideSpec, plain: Float, dj: Float): Float = plain + (dj - plain) * spec.holdFaders

    companion object {
        /**
         * Whether the transition in flight is doing something a plain crossfade
         * could not — which is what `AppSettings.smartMixInProgress` promises the
         * listener when it lights the scrubber up.
         */
        fun isRealMix(
            smartFadeActive: Boolean,
            style: TransitionStyle,
            incomingCueTimeMs: Long,
            incomingPlaybackRate: Double,
            outgoingPlaybackRate: Double,
        ): Boolean = smartFadeActive && style != TransitionStyle.NATURAL && (
            style == TransitionStyle.DJ_BLEND ||
                style == TransitionStyle.DJ_FILTER ||
                incomingCueTimeMs > 0L ||
                incomingPlaybackRate != 1.0 ||
                outgoingPlaybackRate != 1.0
            )

        /** Equal-power pair: [riseGain]² + [fallGain]² = 1, so the blend never dips. */
        fun riseGain(progress: Float): Float = MixGain.rise(progress)

        fun fallGain(progress: Float): Float = MixGain.fall(progress)

        /** Floored blend headroom trim — a true-peak limiter handles overs, not a -3 dB dip. */
        fun headroomFor(incomingGain: Float, outgoingGain: Float): Float =
            MixGain.headroomTrim(incomingGain, outgoingGain)

        /** Where the outgoing low-pass sits the instant a filter ride begins. */
        const val FILTER_ENTRY_HZ = 9_500.0

        /** The bottom of a filter ride so the departing vocal remains recognisable. */
        const val FILTER_FLOOR_HZ = 2_800.0

        /** Muffle low-pass floor: near 700 Hz, past where a voice is understood. */
        const val MUFFLE_FLOOR_HZ = 700.0

        /** Muffle cutoff shape across the lead beats. */
        const val MUFFLE_SHAPE = 1.4

        /** Beatless bridge low-pass for both decks: soft and ambient, never a full close. */
        const val BRIDGE_LOW_PASS_HZ = 1500.0

        /** Where the low end is considered to end (kick + sub fundamental upper register). */
        const val BASS_SWAP_HZ = 200.0

        /** How much of the fade the low end takes to change hands. */
        const val BASS_SWAP_WIDTH = 0.10

        /** Advanced: the low end changes hands over this many beats, ending on the swap beat. */
        const val SWAP_BEATS = 0.5

        /** Where a filter ride hands its low end over, before bar-snapping. */
        const val FILTER_BASS_SWAP_AT = 0.5

        /** Filter-ride fader shapes, as exponents on progress. */
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

        /** An echo out's dry signal fades over this many beats. */
        const val ECHO_KILL_BEATS = 4.0

        /** First repeat level and feedback decay mirrored here for the headroom trim. */
        const val ECHO_LEVEL = 0.5f
        const val ECHO_DECAY = 0.4f

        /** Shape of the outgoing low-pass against fade progress between entry and floor. */
        const val FILTER_SWEEP_SHAPE = 1.1

        /** Where the incoming track's high-pass starts on a filter ride. */
        const val ENTRY_HIGH_PASS_HZ = 1_200.0

        /** How far into the fade the incoming track is fully open again. */
        const val ENTRY_OPEN_BY = 0.6

        /** Shape of the incoming high-pass's descent; see [entryHighPass]. */
        const val ENTRY_SHAPE = 0.35

        /** How far [rideVocalSeparation] closes the outgoing track's top at a full collision. */
        const val VOCAL_SEPARATION_FLOOR_HZ = 1_600.0

        /** Where the incoming track's high-pass starts in [rideVocalSeparation]. */
        const val VOCAL_SEPARATION_HIGH_PASS_HZ = 700.0

        /** [ENTRY_HIGH_PASS_HZ] and [ENTRY_OPEN_BY] counterparts for a beat-matched blend. */
        const val BLEND_ENTRY_HIGH_PASS_HZ = 520.0
        const val BLEND_ENTRY_OPEN_BY = 0.45

        /** Where blend entry high-pass and open-by move at a full vocal collision. */
        const val BLEND_ENTRY_CLASH_HIGH_PASS_HZ = 950.0
        const val BLEND_ENTRY_CLASH_OPEN_BY = 0.7

        /** Where blend exit from and low-pass floor move at a full vocal collision. */
        const val BLEND_EXIT_CLASH_FROM = 0.12
        const val BLEND_EXIT_CLASH_LOW_PASS_HZ = 1_100.0

        /** Where the outgoing track starts losing its top on a beat-matched blend. */
        const val BLEND_EXIT_FROM = 0.3

        /** Where that low-pass lands by the end of the blend. */
        const val BLEND_EXIT_LOW_PASS_HZ = 2_200.0

        /** Default midpoint handoff fraction where equal-power gains cross. */
        const val HANDOFF_AT = 0.5f
    }
}
