package com.music.bitchord.djmix.core.recipes

import com.music.bitchord.djmix.core.contracts.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Mandatory pure-core gate before canonical preparation, export, rendering or replay.
 * Never repairs a script or resolves PCM. The adapter must additionally verify actual bytes,
 * channels, frame counts and stem alignment against these declarations before native setup.
 *
 * Physical mapping/tolerances follow LoopQualification (one sample coverage, half sample
 * coordinate agreement), DeckKnotBuilder and native Deck::trackSecondsAt/inputEnd. The
 * advancing-rate limits 0.75..1.35 are the existing crown execution/renderer limits.
 */
object ScriptExecutionGuard {
    private const val MIN_RATE = 0.75
    private const val MAX_RATE = 1.35
    private const val MAX_Q = 1.92f // native v2::kMaxResonanceQ
    private const val FADE_SEC = 0.025
    private const val SEAM_SEC = 0.008

    /** Validates a serialized executable script without manufacturing a planning context. */
    fun validate(
        script: TransitionScriptV1,
        availableCapabilities: Set<FxCapability> = FxCapability.entries.toSet(),
        beatSec: Double? = null,
    ): MixOutcome<TransitionScriptV1> {
        geometryRefusal(script)?.let { return it }
        val normalized = normalizeSpinback(script, beatSec) ?: return choreography("spinback_missing_compiled_silence_gap_sec")
        contentRefusal(normalized, availableCapabilities, beatSec)?.let { return it }
        val g = normalized.renderGeometry ?: return invalid("render_geometry_missing")
        ChoreoGuard.checkExecutionLanes(normalized, g.handoverMixSec, g.out.stopAtMixSec)?.let {
            return choreography(it)
        }
        physicalRefusal(normalized)?.let { return it }
        return MixOutcome.Ok(normalized)
    }

    /** Additionally checks frozen identity, declared hold, plan anchors and level FX budget. */
    fun validateFrozen(
        script: TransitionScriptV1,
        plan: TransitionPlanV1,
        availableCapabilities: Set<FxCapability> = FxCapability.entries.toSet(),
    ): MixOutcome<TransitionScriptV1> {
        geometryRefusal(script, plan)?.let { return it }
        val beatSec = (plan.barDurationMs / 4.0) / 1000.0
        val normalized = when (val result = validate(script, availableCapabilities, beatSec)) {
            is MixOutcome.Ok -> result.value
            is MixOutcome.Refused -> return result
            is MixOutcome.Failed -> return result
        }
        return ChoreoGuard.validate(normalized, plan)
    }

    internal fun geometryRefusal(script: TransitionScriptV1, plan: TransitionPlanV1? = null): MixOutcome.Refused? {
        val g = script.renderGeometry ?: return invalid("render_geometry_missing")
        if (script.knots.isNotEmpty() || script.loopBounds != null) return invalid("competing_legacy_geometry")
        if (script.planId.isBlank() || script.generation < 0 || script.sampleRateHz <= 0) return invalid("script_identity_or_rate")
        if (script.outRegion.deck != DeckTarget.OUT || script.inRegion.deck != DeckTarget.IN ||
            script.outRegion.trackId.isBlank() || script.inRegion.trackId.isBlank()
        ) return invalid("region_identity")
        val sr = script.sampleRateHz
        deckRefusal(g.out, script.outRegion, sr)?.let { return it }
        deckRefusal(g.inbound, script.inRegion, sr)?.let { return it }
        if (!positive(g.handoverMixSec) || !nonnegative(g.handoverTrackSec) ||
            !nonnegative(g.incomingStartMixSec) || !nonnegative(g.incomingStartTrackSec) ||
            g.incomingStartMixSec >= g.handoverMixSec
        ) return invalid("handover_timing")
        if (g.out.fadeOutTrackSec != null || g.inbound.fadeOutTrackSec != g.handoverTrackSec ||
            !g.inbound.runOn || g.inbound.stopAtMixSec + 1e-9 < g.handoverMixSec + FADE_SEC
        ) return invalid("handover_fade_setup")
        if (abs(g.inbound.knotFrames.last() - g.handoverMixSec * sr) > 1e-6) {
            return invalid("handover_not_final_in_knot")
        }
        val handoverPosition = positionAt(g.inbound, g.handoverMixSec * sr)
        if (g.inbound.loopEndFrame > g.inbound.loopStartFrame &&
            (g.inbound.releaseAtFrame == null || handoverPosition < g.inbound.releaseAtFrame)
        ) return invalid("handover_before_loop_release")
        val actualTrack = g.inbound.regionStartSec + physical(g.inbound, handoverPosition) / sr
        if (abs(actualTrack - g.handoverTrackSec) > 0.5 / sr) return invalid("handover_track_mapping")
        val startTrack = max(0.0, g.handoverTrackSec - (g.handoverMixSec - g.incomingStartMixSec))
        if (abs(startTrack - g.incomingStartTrackSec) > 0.5 / sr) return invalid("incoming_start_track_mapping")

        val seenStems = HashSet<Pair<DeckTarget, StemKind>>()
        for (stem in script.stems) {
            val region = if (stem.deck == DeckTarget.OUT) script.outRegion else script.inRegion
            if (!seenStems.add(stem.deck to stem.stem) || stem.trackId != region.trackId ||
                stem.sampleRateHz != sr || stem.startMs != region.startMs || stem.endMs != region.endMs ||
                stem.pcmPath?.isBlank() == true
            ) return MixOutcome.Refused(RefusalReason.STEM_DUCK_UNAVAILABLE, "stem_region_alignment:${stem.deck}/${stem.stem}")
        }
        if (plan != null) {
            if (script.planId != plan.planId || script.generation != plan.generation ||
                script.outRegion.trackId != plan.outTrackId || script.inRegion.trackId != plan.inTrackId
            ) return invalid("frozen_identity_mismatch")
            if (plan.overlapDurationMs <= 0 || plan.barDurationMs <= 0 ||
                plan.swapDownbeatMs < plan.frozenExitMs ||
                plan.swapDownbeatMs - plan.frozenExitMs > plan.overlapDurationMs ||
                plan.recipeParams.values.any { !it.isFinite() }
            ) return invalid("frozen_plan_timing")
            val release = plan.overlapDurationMs / 1000.0
            if (g.handoverMixSec + 1e-9 < release || g.out.stopAtMixSec + 1e-9 < release) return invalid("frozen_release_timing")
            if (millis(g.out.regionStartSec + g.out.knotPositions.first() / sr) != plan.frozenExitMs ||
                millis(g.inbound.regionStartSec + g.inbound.knotPositions.first() / sr) != plan.frozenEntryMs
            ) return invalid("frozen_source_anchor_mismatch")
            val declaredHold = plan.recipeParams["incomingHoldSec"] ?: 0.0
            if (!nonnegative(declaredHold) || abs(initialHold(g.inbound) / sr - declaredHold) > 0.5 / sr) {
                return MixOutcome.Refused(RefusalReason.HANDOFF_HOLD_MISMATCH, "frozen_initial_hold_mismatch")
            }
            if (plan.geometry == TransitionGeometry.STRAIGHT &&
                (g.out.loopEndFrame > g.out.loopStartFrame || g.inbound.loopEndFrame > g.inbound.loopStartFrame)
            ) return invalid("straight_plan_has_loop")
            if (plan.geometry == TransitionGeometry.LOOP && g.out.loopEndFrame <= g.out.loopStartFrame) {
                return invalid("loop_plan_missing_out_loop")
            }
        }
        return null
    }

    private fun deckRefusal(d: DeckRenderSetupV1, region: AudioRegionRef, sr: Int): MixOutcome.Refused? {
        val side = region.deck
        if (d.frameCount <= 0 || !nonnegative(d.regionStartSec) || !nonnegative(d.prerollSec) ||
            !positive(d.stopAtMixSec) || !d.levelGain.isFinite() || d.levelGain < 0f ||
            d.fadeOutTrackSec?.let { !nonnegative(it) } == true ||
            !nativeFrame(d.stopAtMixSec * sr) || !nativeFrame(d.prerollSec * sr) ||
            !nativeFrame(d.regionStartSec * sr) || !millisecondRange(d.regionStartSec + d.frameCount.toDouble() / sr) ||
            !millisecondRange(d.stopAtMixSec) || !millisecondRange(d.prerollSec) ||
            d.fadeOutTrackSec?.let { !millisecondRange(it) } == true
        ) return invalid("deck_numeric:$side")
        if (d.loopStartFrame < 0 || d.loopEndFrame < d.loopStartFrame || d.loopEndFrame > d.frameCount ||
            d.releaseAtFrame?.let { !nativeFrame(it) || it < d.loopEndFrame ||
                (d.loopEndFrame > d.loopStartFrame && it > 1e15) } == true
        ) return invalid("deck_loop:$side")
        val fs = d.knotFrames
        val ps = d.knotPositions
        if (fs.size < 2 || fs.size != ps.size || fs.first() != 0.0 ||
            fs.any { !nativeFrame(it) } || ps.any { !nativeFrame(it) } || ps.first() >= d.frameCount
        ) return invalid("deck_knots:$side")
        var advancing = false
        for (i in 1 until fs.size) {
            val dt = fs[i] - fs[i - 1]
            val dp = ps[i] - ps[i - 1]
            if (!dt.isFinite() || dt <= 0.0 || !dp.isFinite()) return invalid("deck_knot_order:$side")
            if (dp == 0.0 && !advancing && side == DeckTarget.IN) continue
            if (dp <= 0.0) return invalid("deck_nonadvancing:$side")
            // Native's >0.5-sample activation search must recognize the first advancing segment.
            if (!advancing && ps[i] - ps.first() <= 0.5) return invalid("deck_subsample_activation:$side")
            advancing = true
            val rate = dp / dt
            if (!rate.isFinite() || rate < MIN_RATE - 1e-9 || rate > MAX_RATE + 1e-9) {
                return MixOutcome.Refused(RefusalReason.CROWN_EXECUTION_RATE, "deck_rate:$side:$i:$rate")
            }
        }
        if (!advancing) return invalid("deck_nonadvancing:$side")
        if (region.sampleRateHz != sr || region.startMs != millis(d.regionStartSec) ||
            region.endMs != millis(d.regionStartSec + d.frameCount.toDouble() / sr) ||
            region.prerollMs != millis(d.prerollSec) || region.stopAtMixMs != millis(d.stopAtMixSec) ||
            region.fadeOutTrackMs != d.fadeOutTrackSec?.let(::millis) || region.levelGain != d.levelGain ||
            region.runOn != d.runOn || region.pcmPath?.isBlank() == true
        ) return invalid("region_geometry_projection:$side")
        return null
    }

    /** Exact interpolation over retained knots, with the native renderer's post-knot continuation. */
    private fun positionAt(d: DeckRenderSetupV1, frame: Double): Double {
        val fs = d.knotFrames
        val ps = d.knotPositions
        if (frame >= fs.last()) {
            val rate = if (d.runOn) 1.0 else {
                val probe = fs.last() - 1.0
                val i = (fs.indexOfFirst { it > probe }).coerceIn(1, fs.lastIndex)
                ((ps[i] - ps[i - 1]) / (fs[i] - fs[i - 1])).coerceIn(0.85, 1.15)
            }
            return ps.last() + (frame - fs.last()) * rate
        }
        val i = fs.indexOfFirst { it >= frame }.coerceAtLeast(1)
        return ps[i - 1] + (ps[i] - ps[i - 1]) * (frame - fs[i - 1]) / (fs[i] - fs[i - 1])
    }

    private fun initialHold(d: DeckRenderSetupV1): Double {
        val firstAdvance = d.knotPositions.indexOfFirst { it > d.knotPositions.first() }
        return if (firstAdvance > 1) d.knotFrames[firstAdvance - 1] else 0.0
    }

    private fun physical(d: DeckRenderSetupV1, position: Double): Double {
        val length = d.loopEndFrame - d.loopStartFrame
        if (length <= 0 || position < d.loopEndFrame) return position
        val release = d.releaseAtFrame
        return if (release == null || position < release) {
            d.loopStartFrame + (position - d.loopStartFrame) % length
        } else d.loopEndFrame + (position - release)
    }

    private fun physicalRefusal(script: TransitionScriptV1): MixOutcome.Refused? {
        val g = script.renderGeometry ?: return invalid("render_geometry_missing")
        val sr = script.sampleRateHz
        fun covers(d: DeckRenderSetupV1, endPosition: Double, incoming: Boolean): Boolean {
            // A repeated body and its seam continuation must exist even when both endpoint
            // samples wrap near its beginning. Never compare unwrapped positions with PCM size.
            var required = max(physical(d, d.knotPositions.first()), physical(d, endPosition))
            if (d.loopEndFrame > d.loopStartFrame) {
                required = max(required, d.loopEndFrame + SEAM_SEC * sr)
            }
            if (incoming) {
                required = max(required, (g.handoverTrackSec + FADE_SEC - d.regionStartSec) * sr)
            }
            return required.isFinite() && d.frameCount + 1.0 >= required
        }
        val outGain = script.envelopes.first { it.deck == DeckTarget.OUT && it.kind == EnvelopeKind.GAIN }
        // Include the complete final fading segment, not just the last positive waypoint.
        var dryEnd = 0.0
        for (i in outGain.values.indices) {
            if (outGain.values[i] > 1e-3f || (i > 0 && outGain.values[i - 1] > 1e-3f)) {
                dryEnd = max(dryEnd, outGain.timesSec[i])
            }
        }
        if (!covers(g.out, positionAt(g.out, dryEnd * sr), incoming = false)) {
            return MixOutcome.Refused(RefusalReason.PCM_COVERAGE_INCOMPLETE, "out_dry_or_loop_pcm_incomplete")
        }
        if (!covers(g.inbound, g.inbound.knotPositions.last(), incoming = true)) {
            return MixOutcome.Refused(RefusalReason.PCM_COVERAGE_INCOMPLETE, "in_loop_or_handover_pcm_incomplete")
        }
        return null
    }

    private fun contentRefusal(
        script: TransitionScriptV1,
        caps: Set<FxCapability>,
        beatSec: Double? = null,
    ): MixOutcome.Refused? {
        val g = script.renderGeometry ?: return invalid("render_geometry_missing")
        val fx = script.fxParams
        val required = mutableSetOf<FxCapability>()
        if (script.stems.isNotEmpty()) required += FxCapability.STEMS
        if (script.loudness.enabled || script.dropWindowMs != null || script.loudness.dropBumpLu != 0f) required += FxCapability.LOUDNESS
        if (script.loudness.truePeakLimiter) required += FxCapability.TRUE_PEAK
        if (!script.loudness.targetLufs.isFinite() || script.loudness.targetLufs <= -60f || script.loudness.targetLufs >= -3f ||
            !script.loudness.truePeakCeilingDbtp.isFinite() || script.loudness.truePeakCeilingDbtp !in -12f..-0.1f ||
            !script.loudness.dropBumpLu.isFinite() || script.loudness.dropBumpLu !in 0f..6f
        ) return choreography("loudness_parameters")
        script.dropWindowMs?.let { if (it.startMs < 0 || it.endMs <= it.startMs) return choreography("drop_window") }
        for (lane in script.envelopes) {
            laneCapability(lane.kind)?.let { required += it }
            val deck = if (lane.deck == DeckTarget.OUT) g.out else g.inbound
            if (lane.timesSec.isEmpty() || lane.timesSec.size != lane.values.size ||
                lane.timesSec.any { !it.isFinite() || it < -deck.prerollSec || it > deck.stopAtMixSec } ||
                lane.values.any { !it.isFinite() || !laneValueValid(lane.kind, it) }
            ) return choreography("envelope_parameters:${lane.deck}/${lane.kind}")
            if (lane.kind == EnvelopeKind.GATE_DEPTH && fx.gate?.isStutter == true) required += FxCapability.STUTTER
        }
        if (listOfNotNull(fx.outSvfQ, fx.inSvfQ).any { !it.isFinite() || it !in 0.5f..MAX_Q }) {
            return choreography("base_svf_q")
        }
        if (fx.sweep?.enabled == true || fx.incomingSweep?.enabled == true) required += FxCapability.FILTER_RES
        if (fx.phaser?.enabled == true) required += FxCapability.PHASER
        if (fx.riser?.enabled == true || fx.impact?.enabled == true) required += FxCapability.RISER
        if (fx.gate?.enabled == true) required += if (fx.gate.isStutter) FxCapability.STUTTER else FxCapability.GATE
        if (fx.pump?.enabled == true) required += FxCapability.PUMP
        if (fx.brake?.enabled == true || fx.tapeStop?.enabled == true) required += FxCapability.BRAKE
        if (fx.spinback?.enabled == true) required += FxCapability.SPINBACK
        (required - caps).firstOrNull()?.let { return choreography("capability_missing:$it") }
        fxRefusal(fx, script.sampleRateHz, g, beatSec)?.let { return it }
        fun lane(deck: DeckTarget, kind: EnvelopeKind) = script.envelopes.any { it.deck == deck && it.kind == kind }
        if (fx.phaser?.let { it.enabled && !lane(it.deck, EnvelopeKind.PHASER_WET) } == true ||
            fx.gate?.let { it.enabled && !lane(it.deck, EnvelopeKind.GATE_DEPTH) } == true ||
            fx.riser?.let { it.enabled && !lane(it.deck, EnvelopeKind.RISER_LEVEL) } == true ||
            fx.pump?.let { it.enabled && !lane(it.deck, EnvelopeKind.PUMP_DEPTH) } == true
        ) return choreography("enabled_fx_missing_automation")
        for (envelope in script.envelopes) {
            val present = when (envelope.kind) {
                EnvelopeKind.ECHO_SEND -> fx.echo?.let { it.enabled && it.deck == envelope.deck } == true
                EnvelopeKind.REVERB_SEND -> fx.reverb?.let { it.enabled && it.deck == envelope.deck } == true
                EnvelopeKind.FLANGER_WET -> fx.flanger?.let { it.enabled && it.deck == envelope.deck } == true
                EnvelopeKind.PHASER_WET -> fx.phaser?.let { it.enabled && it.deck == envelope.deck } == true
                EnvelopeKind.GATE_DEPTH -> fx.gate?.let { it.enabled && it.deck == envelope.deck } == true
                EnvelopeKind.RISER_LEVEL -> fx.riser?.let { it.enabled && it.deck == envelope.deck } == true
                EnvelopeKind.PUMP_DEPTH -> fx.pump?.let { it.enabled && it.deck == envelope.deck } == true
                EnvelopeKind.BRAKE -> fx.brake?.let { it.enabled && it.deck == envelope.deck } == true ||
                    fx.tapeStop?.let { it.enabled && it.deck == envelope.deck } == true ||
                    fx.spinback?.let { it.enabled && it.deck == envelope.deck } == true
                else -> true
            }
            if (!present) return choreography("envelope_processor_missing:${envelope.deck}/${envelope.kind}")
        }
        return null
    }

    private fun laneCapability(kind: EnvelopeKind): FxCapability? = when (kind) {
        EnvelopeKind.STEM_VOCALS, EnvelopeKind.STEM_DRUMS, EnvelopeKind.STEM_BASS, EnvelopeKind.STEM_OTHER -> FxCapability.STEMS
        EnvelopeKind.FILTER_RES -> FxCapability.FILTER_RES
        EnvelopeKind.FILTER_KNOB -> FxCapability.FILTER_KNOB
        EnvelopeKind.PHASER_WET -> FxCapability.PHASER
        EnvelopeKind.GATE_DEPTH -> FxCapability.GATE
        EnvelopeKind.RISER_LEVEL -> FxCapability.RISER
        EnvelopeKind.PUMP_DEPTH -> FxCapability.PUMP
        EnvelopeKind.BRAKE -> FxCapability.BRAKE
        else -> null
    }

    private fun fxCapability(fx: RecipeFxType): FxCapability? = when (fx) {
        RecipeFxType.SWEEP -> FxCapability.FILTER_RES
        RecipeFxType.PHASER -> FxCapability.PHASER
        RecipeFxType.GATE -> FxCapability.GATE
        RecipeFxType.RISER, RecipeFxType.IMPACT -> FxCapability.RISER
        RecipeFxType.BRAKE, RecipeFxType.TAPE_STOP -> FxCapability.BRAKE
        RecipeFxType.SPINBACK -> FxCapability.SPINBACK
        RecipeFxType.PUMP -> FxCapability.PUMP
        else -> null // Legacy setters (including base SVF Q) have no capability bit.
    }

    private fun laneValueValid(kind: EnvelopeKind, value: Float): Boolean = when (kind) {
        EnvelopeKind.GAIN, EnvelopeKind.STEM_VOCALS, EnvelopeKind.STEM_DRUMS,
        EnvelopeKind.STEM_BASS, EnvelopeKind.STEM_OTHER -> value >= 0f
        EnvelopeKind.HIGH_PASS_HZ, EnvelopeKind.LOW_PASS_HZ -> value > 0f
        EnvelopeKind.ECHO_SEND, EnvelopeKind.REVERB_SEND, EnvelopeKind.FLANGER_WET,
        EnvelopeKind.PHASER_WET, EnvelopeKind.GATE_DEPTH, EnvelopeKind.PUMP_DEPTH, EnvelopeKind.BRAKE -> value in 0f..1f
        EnvelopeKind.RISER_LEVEL -> value in 0f..2f
        EnvelopeKind.FILTER_KNOB -> value in -1f..1f
        EnvelopeKind.FILTER_RES -> value in 0.25f..8f
        else -> true
    }

    private fun normalizeSpinback(script: TransitionScriptV1, beatSec: Double?): TransitionScriptV1? {
        val spin = script.fxParams.spinback ?: return script
        val missingCompiledSec = spin.enabled && spin.silenceGapSec == 0.0 && spin.silenceGapBeats > 0.0
        if (!missingCompiledSec) return script
        val bs = beatSec
        if (bs == null || !bs.isFinite() || bs <= 0.0) return null
        val compiledSec = spin.silenceGapBeats * bs
        return script.copy(
            fxParams = script.fxParams.copy(
                spinback = spin.copy(silenceGapSec = compiledSec),
            ),
        )
    }

    private fun fxRefusal(
        fx: ScriptFxParams,
        sr: Int,
        g: RenderGeometryV1,
        beatSec: Double? = null,
    ): MixOutcome.Refused? {
        fun horizon(deck: DeckTarget) = if (deck == DeckTarget.OUT) g.out.stopAtMixSec else g.handoverMixSec
        fun interval(start: Double, end: Double, deck: DeckTarget) = nonnegative(start) && positive(end) && end > start && end <= horizon(deck)
        fun finite(vararg xs: Double) = xs.all { it.isFinite() }
        fx.loop?.let {
            if (it.bars <= 0 || it.holdPhrases < 0 || !it.seamCrossfadeMs.isFinite() || it.seamCrossfadeMs !in 0.5..8.0 ||
                (it.enabled && g.out.loopEndFrame <= g.out.loopStartFrame)
            ) return choreography("loop_parameters")
        }
        for ((sweep, deck) in listOf(fx.sweep to DeckTarget.OUT, fx.incomingSweep to DeckTarget.IN)) {
            sweep?.let { s ->
                if (!finite(s.startHz.toDouble(), s.endHz.toDouble(), s.qStart.toDouble(), s.qEnd.toDouble()) ||
                    s.deck != deck || s.startHz !in 20f..(sr * 0.45f) || s.endHz !in 20f..(sr * 0.45f) ||
                    s.qStart !in 0.5f..MAX_Q || s.qEnd !in 0.5f..MAX_Q || s.bars <= 0 ||
                    s.startMixSec?.isFinite() == false || s.endMixSec?.isFinite() == false ||
                    (s.enabled && (s.startMixSec == null || s.endMixSec == null || !interval(s.startMixSec, s.endMixSec, deck)))
                ) return choreography("sweep_unresolved_or_invalid:$deck")
            }
        }
        fx.roll?.let { r ->
            val deck = if (r.deck == DeckTarget.OUT) g.out else g.inbound
            if (!interval(r.startMixSec, r.releaseMixSec, r.deck) || !positive(r.beatSec) || !positive(r.totalBeats) ||
                deck.loopEndFrame <= deck.loopStartFrame ||
                r.scheduleBeats.isEmpty() || r.scheduleBeats.any { !it.isFinite() || it !in 0.0625..32.0 } ||
                !finite(r.hpfStartHz.toDouble(), r.hpfEndHz.toDouble(), r.boostDb.toDouble()) ||
                r.hpfStartHz !in 20f..8000f || r.hpfEndHz !in r.hpfStartHz..16000f || r.boostDb !in -6f..6f
            ) return choreography("roll_parameters")
        }
        fx.echo?.let { e ->
            if (!finite(e.delayBeats, e.delaySec, e.feedback.toDouble(), e.wet.toDouble(), e.wetHpfHz.toDouble(), e.wetLpfHz.toDouble()) ||
                e.delayBeats <= 0.0 || e.delaySec <= 0.01 || e.delaySec > 2.0 || e.feedback !in 0f..0.85f || e.wet <= 0f || e.wet > 1.2f ||
                e.wetHpfHz !in 40f..4000f || e.wetLpfHz !in 500f..(sr * 0.45f)
            ) return choreography("echo_parameters")
        }
        fx.reverb?.let { r ->
            if (!finite(r.roomSize.toDouble(), r.damping.toDouble(), r.wet.toDouble()) ||
                r.roomSize !in 0.5f..6f || r.damping !in 0f..1f || r.wet <= 0f || r.wet > 1.2f
            ) return choreography("reverb_parameters")
        }
        fx.flanger?.let { f ->
            if (!finite(f.lfoCyclesPerBars, f.lfoHz, f.depthSec, f.feedback.toDouble(), f.wetPeak.toDouble(), f.stereoOffsetDeg.toDouble()) ||
                f.lfoCyclesPerBars <= 0.0 || f.lfoHz <= 0.0 || f.depthSec !in 0.0002..0.0045 || f.feedback !in -0.78f..0.78f || f.wetPeak <= 0f || f.wetPeak > 1f
            ) return choreography("flanger_parameters")
        }
        fx.phaser?.let { p ->
            if (!finite(p.rateBeats, p.depth.toDouble(), p.feedback.toDouble(), p.wetPeak.toDouble(), p.stereoOffsetDeg.toDouble()) ||
                p.rateBeats !in 0.0625..64.0 || p.stages !in 4..8 || p.stages % 2 != 0 || p.depth !in 0f..1f ||
                p.feedback !in -0.85f..0.85f || p.wetPeak !in 0f..1.2f
            ) return choreography("phaser_parameters")
        }
        fx.riser?.let { r ->
            if (!finite(r.beatSec, r.lengthBeats, r.noiseSweepStartHz.toDouble(), r.noiseSweepEndHz.toDouble(),
                    r.noiseLevelPeak.toDouble(), r.uplifterOctaves.toDouble(), r.reverseReverbTailBeats) ||
                r.beatSec <= 0.1 || r.beatSec >= 4.0 || r.lengthBeats !in 0.25..256.0 || r.noiseSweepStartHz !in 60f..12000f ||
                r.noiseSweepEndHz !in (r.noiseSweepStartHz + 50f)..(sr * 0.42f) || r.noiseLevelPeak !in 0f..1.5f ||
                r.uplifterOctaves !in 0.25f..3f || r.reverseReverbTailBeats !in 0.0..2.0
            ) return choreography("riser_parameters")
        }
        fx.impact?.let { i ->
            if (!finite(i.triggerMixSec, i.peakDbfs.toDouble(), i.lowThumpHz.toDouble(), i.noiseBurstMs) ||
                i.triggerMixSec < 0 || i.triggerMixSec > horizon(i.deck) || i.peakDbfs !in -40f..-6f ||
                i.lowThumpHz !in 28f..140f || i.noiseBurstMs !in 30.0..1500.0
            ) return choreography("impact_parameters")
        }
        fx.gate?.let { gate ->
            if (!finite(gate.beatSec, gate.divisionStepsPerBeat.toDouble(), gate.swing.toDouble(), gate.depthPeak.toDouble(), gate.rampBars) ||
                gate.beatSec <= 0.1 || gate.beatSec >= 4.0 || abs(gate.divisionStepsPerBeat) !in 0.0625f..16f ||
                (gate.divisionStepsPerBeat < 0f && !gate.isStutter) ||
                gate.swing !in 0f..0.33f || gate.depthPeak !in 0f..1f || gate.rampBars <= 0
            ) return choreography("gate_parameters")
        }
        fx.pump?.let { if (!it.beatSec.isFinite() || it.beatSec <= 0.1 || it.beatSec >= 4.0) return choreography("pump_parameters") }
        fun brake(start: Double, len: Double, deck: DeckTarget, lp0: Float, lp1: Float, fade: Double): Boolean =
            interval(start, start + len, deck) && len in 0.02..8.0 && finite(lp0.toDouble(), lp1.toDouble(), fade) &&
                lp0 in 2000f..(sr * 0.45f) && lp1 in 100f..lp0 && fade in 1.0..(len * 500.0)
        fx.brake?.let { b ->
            if (!brake(b.startMixSec, b.lengthSec, b.deck, b.lpfStartHz, b.lpfEndHz, b.endFadeMs) ||
                !b.endRate.isFinite() || b.endRate !in 0.01f..0.45f
            ) return choreography("brake_parameters")
        }
        fx.tapeStop?.let { b ->
            if (!brake(b.startMixSec, b.lengthSec, b.deck, b.lpfStartHz, b.lpfEndHz, b.endFadeMs) ||
                !b.wowDepth.isFinite() || b.wowDepth !in 0f..0.12f || !b.wowRateHz.isFinite() || b.wowRateHz !in 0.5f..15f
            ) return choreography("tape_stop_parameters")
        }
        fx.spinback?.let { s ->
            // Backward compat: older enabled scripts may carry silenceGapBeats but omit compiled silenceGapSec (default 0.0).
            // Derive/refuse only for enabled spinback; disabled objects preserve old parameter-bounds behavior.
            val hasSourceGap = s.silenceGapBeats > 0.0
            val missingCompiledSec = s.enabled && s.silenceGapSec == 0.0 && hasSourceGap
            val resolvedSilenceGapSec = if (missingCompiledSec) {
                val bs = beatSec
                if (bs == null || !bs.isFinite() || bs <= 0.0) {
                    return choreography("spinback_missing_compiled_silence_gap_sec")
                }
                s.silenceGapBeats * bs
            } else {
                s.silenceGapSec
            }
            if (s.enabled && hasSourceGap && !missingCompiledSec) {
                val bs = beatSec
                if (bs != null && bs.isFinite() && bs > 0.0) {
                    val expected = s.silenceGapBeats * bs
                    // Consistency check against authoritative frozen clock only; no alternate BPM.
                    if (abs(s.silenceGapSec - expected) > 1e-6 * max(1.0, abs(expected))) {
                        return choreography("spinback_compiled_silence_gap_sec_mismatch")
                    }
                }
            }
            if (!interval(s.startMixSec, s.startMixSec + s.lengthSec, s.deck) || s.lengthSec !in 0.02..3.0 ||
                !s.peakReverseSpeed.isFinite() || s.peakReverseSpeed !in 1.0..6.0 ||
                !nonnegative(s.silenceGapBeats) || !resolvedSilenceGapSec.isFinite() || resolvedSilenceGapSec < 0.0 ||
                resolvedSilenceGapSec > 4.0
            ) return choreography("spinback_parameters")
        }
        if (listOf(fx.brake?.enabled, fx.tapeStop?.enabled, fx.spinback?.enabled).count { it == true } > 1) return choreography("conflicting_brake_processors")
        return null
    }

    internal fun recipeRefusal(
        recipe: RecipeV2,
        plan: TransitionPlanV1,
        steps: List<RecipeStepV2>,
        noStem: Boolean,
        caps: Set<FxCapability>,
    ): MixOutcome.Refused? {
        if (recipe.id != plan.recipeId) return invalid("frozen_recipe_id_mismatch")
        if (!plan.djLevel.atLeast(recipe.level)) return choreography("recipe_level_ineligible")
        val release = plan.overlapDurationMs / 1000.0
        if (plan.recipeParams["incomingHoldSec"]?.let { !nonnegative(it) || it > release } == true ||
            plan.recipeParams["vocalEntrySec"]?.let { !nonnegative(it) || it > release } == true ||
            plan.recipeParams["fadeSec"]?.let { !it.isFinite() || it < minOf(0.5, release) || it > release } == true
        ) return invalid("recipe_plan_parameter_would_be_coerced")
        if (!recipe.energyDelta.isFinite() || recipe.requirements.genreAffinity.values.any { !it.isFinite() } ||
            !recipe.requirements.maxTempoGap.isFinite() || !recipe.requirements.minOverlapBars.isFinite() ||
            !recipe.requirements.minGridConfidence.isFinite() || recipe.requirements.maxTempoGap < 0.0 ||
            recipe.requirements.minOverlapBars <= 0.0 || recipe.requirements.minGridConfidence !in 0f..1f ||
            recipe.requirements.minStemCount < 0
        ) return choreography("recipe_nonfinite")
        val variantCaps = if (noStem) recipe.requirements.requiredCapabilities - FxCapability.STEMS else recipe.requirements.requiredCapabilities
        (variantCaps - caps).firstOrNull()?.let { return choreography("capability_missing:$it") }
        fun timeValid(t: StepTimeV2): Boolean = listOf(t.param, t.offsetBars, t.offsetBeats, t.offsetSec).all { it.isFinite() }
        fun pointsValid(ps: List<WaypointV2>): Boolean = ps.isNotEmpty() && ps.all {
            timeValid(it.time) && it.value.isFinite() && it.depthBase.isFinite()
        }
        val seenFx = HashMap<String, RecipeStepV2.ConfigureFx>()
        val seenLanes = HashSet<Pair<DeckTarget, EnvelopeKind>>()
        for (step in steps) when (step) {
            RecipeStepV2.EnableIsolator -> Unit
            is RecipeStepV2.LaneCurve -> {
                if (!seenLanes.add(step.deck to step.kind)) return choreography("duplicate_recipe_lane:${step.deck}/${step.kind}")
                if (!pointsValid(step.points) || step.steps !in 2..32 ||
                    step.points.any { !laneValueValid(step.kind, it.value) } || (noStem && step.kind.isStem)
                ) return choreography("recipe_lane_invalid")
                laneCapability(step.kind)?.let { if (it !in caps) return choreography("capability_missing:$it") }
            }
            is RecipeStepV2.SwapLows -> {
                if (!timeValid(step.at) || !positive(step.widthBeats) || !step.inLowBeforeDb.isFinite() || !step.outLowAfterDb.isFinite() ||
                    (noStem && step.useBassStems)
                ) return choreography("recipe_low_swap_invalid")
            }
            is RecipeStepV2.ConfigureFx -> {
                if (step.fx == RecipeFxType.LOOP && step.deck != DeckTarget.OUT) return choreography("inbound_loop_fx_not_representable")
                if (step.params.values.any { !it.isFinite() || !it.toFloat().isFinite() } ||
                    step.startTime?.let { !timeValid(it) } == true || step.endTime?.let { !timeValid(it) } == true ||
                    step.wetCurve?.let { !pointsValid(it) } == true || step.fallbackWetCurve?.let { !pointsValid(it) } == true
                ) return choreography("recipe_fx_nonfinite")
                val needed = listOfNotNull(step.requiredCap, fxCapability(step.fx))
                needed.firstOrNull { it !in caps }?.let { return choreography("capability_missing:$it") }
                // A frozen recipe is never silently converted to a different capability fallback.
                val slot = when (step.fx) {
                    RecipeFxType.SVF_Q, RecipeFxType.SWEEP -> "${step.fx}:${step.deck}"
                    RecipeFxType.ROLL, RecipeFxType.LOOP_ROLL -> "ROLL"
                    else -> step.fx.name
                }
                val previous = seenFx.put(slot, step)
                // Bundled ROLL/LOOP_ROLL are exact aliases for the same single native processor.
                if (previous != null && (slot != "ROLL" ||
                        previous.copy(fx = RecipeFxType.ROLL) != step.copy(fx = RecipeFxType.ROLL))) {
                    return choreography("duplicate_fx_slot:$slot")
                }
                rawParameterRefusal(step)?.let { return it }
            }
        }
        return null
    }

    /** Reject inputs that the compatibility compiler would otherwise coerce before the final guard. */
    private fun rawParameterRefusal(step: RecipeStepV2.ConfigureFx): MixOutcome.Refused? {
        val p = step.params
        fun qValid(key: String): Boolean = p[key]?.let { it.toFloat() in 0.5f..MAX_Q } ?: true
        fun integer(key: String, lo: Int, hi: Int = Int.MAX_VALUE): Boolean = p[key]?.let { it in lo.toDouble()..hi.toDouble() && it == it.toInt().toDouble() } ?: true
        if (!integer("bars", 1) || !integer("holdPhrases", 0) || !integer("stages", 4, 8) ||
            !qValid("q") || !qValid("qStart") || !qValid("qEnd") ||
            listOf("pingPong", "includeSnareRoll", "stopOnDownbeat", "isStutter", "fullBand").any { key ->
                p[key]?.let { it != 0.0 && it != 1.0 } == true
            }
        ) return choreography("recipe_fx_parameter_would_be_coerced:${step.fx}")
        return null
    }

    private fun nativeFrame(value: Double): Boolean = nonnegative(value) && value < Long.MAX_VALUE.toDouble() / 2.0
    private fun millisecondRange(sec: Double): Boolean = nonnegative(sec) && sec * 1000.0 < Long.MAX_VALUE.toDouble()
    private fun millis(sec: Double): Long = (sec * 1000.0).roundToLong()
    private fun nonnegative(value: Double): Boolean = value.isFinite() && value >= 0.0
    private fun positive(value: Double): Boolean = value.isFinite() && value > 0.0
    private fun invalid(detail: String) = MixOutcome.Refused(RefusalReason.HANDOFF_INVALID_GEOMETRY, detail)
    private fun choreography(detail: String) = MixOutcome.Refused(RefusalReason.CHOREOGRAPHY_UNAVAILABLE, detail)
}
