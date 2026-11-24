package com.music.bitchord.djmix.core.recipes

import com.music.bitchord.djmix.core.contracts.AbruptnessClass
import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.BrakeFxParams
import com.music.bitchord.djmix.core.contracts.CurveShapeV2
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.EchoFxParams
import com.music.bitchord.djmix.core.contracts.EnvelopeKind
import com.music.bitchord.djmix.core.contracts.EnvelopeLane
import com.music.bitchord.djmix.core.contracts.FlangerFxParams
import com.music.bitchord.djmix.core.contracts.FxCapability
import com.music.bitchord.djmix.core.contracts.GateFxParams
import com.music.bitchord.djmix.core.contracts.ImpactFxParams
import com.music.bitchord.djmix.core.contracts.LoopBounds
import com.music.bitchord.djmix.core.contracts.LoopFxParams
import com.music.bitchord.djmix.core.contracts.LoudnessSettings
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.PhaserFxParams
import com.music.bitchord.djmix.core.contracts.PumpFxParams
import com.music.bitchord.djmix.core.contracts.RecipeAnchor
import com.music.bitchord.djmix.core.contracts.RecipeFxType
import com.music.bitchord.djmix.core.contracts.RecipeStepV2
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.ReverbFxParams
import com.music.bitchord.djmix.core.contracts.RiserFxParams
import com.music.bitchord.djmix.core.contracts.RollFxParams
import com.music.bitchord.djmix.core.contracts.ScriptFxParams
import com.music.bitchord.djmix.core.contracts.SpinbackFxParams
import com.music.bitchord.djmix.core.contracts.StemKind
import com.music.bitchord.djmix.core.contracts.StemRegionRef
import com.music.bitchord.djmix.core.contracts.StepTimeV2
import com.music.bitchord.djmix.core.contracts.SweepFxParams
import com.music.bitchord.djmix.core.contracts.TapeStopFxParams
import com.music.bitchord.djmix.core.contracts.TimeRangeMs
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.contracts.WaypointV2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Pure-KMP interpreter converting a declarative [RecipeV2] and frozen [TransitionPlanV1]
 * into a renderer-ready [TransitionScriptV1], validated by [ChoreoGuard].
 */
object RecipeInterpreter {

    private const val PREROLL_SEC: Double = 0.025

    /**
     * Compiles [recipe] against [plan] and deck audio regions into a validated [TransitionScriptV1].
     *
     * Selects [RecipeV2.noStemSteps] when [useNoStemVariant] is true; otherwise verifies that every
     * required stem in [RecipeV2.requirements] is attached in [stems].
     */
    fun interpret(
        recipe: RecipeV2,
        plan: TransitionPlanV1,
        outRegion: AudioRegionRef,
        inRegion: AudioRegionRef,
        stems: List<StemRegionRef> = emptyList(),
        useNoStemVariant: Boolean = false,
        dropWindowMs: TimeRangeMs? = null,
        availableCapabilities: Set<FxCapability> = FxCapability.entries.toSet(),
    ): MixOutcome<TransitionScriptV1> {
        if (!recipe.enabled) {
            return MixOutcome.Refused(
                RefusalReason.CHOREOGRAPHY_UNAVAILABLE,
                "Recipe '${recipe.id}' is disabled",
            )
        }
        if (plan.overlapDurationMs <= 0L || plan.barDurationMs <= 0L) {
            return MixOutcome.Refused(
                RefusalReason.HANDOFF_INVALID_GEOMETRY,
                "Plan overlap (${plan.overlapDurationMs}ms) and bar (${plan.barDurationMs}ms) must be positive",
            )
        }
        if (outRegion.deck != DeckTarget.OUT || inRegion.deck != DeckTarget.IN ||
            outRegion.trackId != plan.outTrackId || inRegion.trackId != plan.inTrackId
        ) {
            return MixOutcome.Refused(
                RefusalReason.HANDOFF_INVALID_GEOMETRY,
                "AudioRegionRef tracks/decks do not match TransitionPlanV1",
            )
        }
        if (outRegion.sampleRateHz != inRegion.sampleRateHz) {
            return MixOutcome.Refused(
                RefusalReason.HANDOFF_INVALID_GEOMETRY,
                "Deck sample rates differ (${outRegion.sampleRateHz} vs ${inRegion.sampleRateHz})",
            )
        }
        if (recipe.abruptness == AbruptnessClass.ABRUPT && plan.djLevel != DjLevel.FESTIVAL) {
            return MixOutcome.Refused(
                RefusalReason.ABRUPT_DISALLOWED,
                "Abrupt recipe '${recipe.id}' requires DjLevel.FESTIVAL, got ${plan.djLevel}",
            )
        }

        val stepList = if (useNoStemVariant) recipe.noStemSteps else recipe.steps
        if (stepList.isEmpty()) {
            val reason = if (useNoStemVariant) {
                RefusalReason.NO_STEM_RECIPE_UNAVAILABLE
            } else {
                RefusalReason.CHOREOGRAPHY_UNAVAILABLE
            }
            return MixOutcome.Refused(reason, "Recipe '${recipe.id}' has empty step list (noStem=$useNoStemVariant)")
        }

        val effectiveStems = if (useNoStemVariant) emptyList() else stems
        if (!useNoStemVariant) {
            for (req in recipe.requirements.requiredStems) {
                if (effectiveStems.none { it.deck == req.deck && it.stem == req.stem }) {
                    return MixOutcome.Refused(
                        RefusalReason.STEM_DUCK_UNAVAILABLE,
                        "Missing required stem ${req.deck}/${req.stem} for recipe '${recipe.id}'",
                    )
                }
            }
        }

        val compiler = Compiler(
            plan = plan,
            outRegion = outRegion,
            stems = effectiveStems,
            useNoStemVariant = useNoStemVariant,
            availableCapabilities = availableCapabilities,
            stepList = stepList,
        )
        for (step in stepList) {
            compiler.apply(step)
        }

        val outStopMs = (compiler.ctx.outStopSec * 1000.0).roundToLong().coerceAtLeast(plan.overlapDurationMs)
        val finalOutRegion = if (outRegion.stopAtMixMs != null && outRegion.stopAtMixMs >= outStopMs) {
            outRegion
        } else {
            outRegion.copy(stopAtMixMs = outStopMs)
        }
        val dropBump = if (recipe.energyDelta > 0.1f) (recipe.energyDelta * 2f).coerceIn(0f, 1.5f) else 0f
        val script = TransitionScriptV1(
            planId = plan.planId,
            generation = plan.generation,
            sampleRateHz = outRegion.sampleRateHz,
            outRegion = finalOutRegion,
            inRegion = inRegion,
            knots = plan.tempoKnots,
            loopBounds = compiler.loopBounds,
            envelopes = compiler.orderedLanes(),
            fxParams = compiler.fxParams,
            stems = effectiveStems,
            loudness = LoudnessSettings(dropBumpLu = dropBump),
            dropWindowMs = dropWindowMs,
        )

        return ChoreoGuard.validate(
            script = script,
            plan = plan,
            swapsBass = !recipe.id.equals("S5", ignoreCase = true),
            requireStemsOnStemLanes = !useNoStemVariant,
        )
    }

    private data class AnchorContext(
        val rSec: Double,
        val sSec: Double,
        val aSec: Double,
        val barSec: Double,
        val beatSec: Double,
        val outStopSec: Double,
        val vocalEntrySec: Double,
        val fadeStartSec: Double,
    ) {
        fun rawAnchor(anchor: RecipeAnchor, param: Double = 0.0): Double = when (anchor) {
            RecipeAnchor.ZERO -> 0.0
            RecipeAnchor.IN_AUDIBLE -> aSec
            RecipeAnchor.SWAP -> sSec
            RecipeAnchor.RELEASE -> rSec
            RecipeAnchor.OUT_STOP -> outStopSec
            RecipeAnchor.PRE_FRAC -> aSec + param.coerceIn(0.0, 1.0) * max(0.0, sSec - aSec)
            RecipeAnchor.POST_FRAC -> sSec + param.coerceIn(0.0, 1.0) * max(0.0, rSec - sSec)
            RecipeAnchor.OVERLAP_FRAC -> param.coerceIn(0.0, 1.0) * rSec
            RecipeAnchor.A_PLUS_CAPPED_S -> min(sSec, aSec + param * barSec)
            RecipeAnchor.S_PLUS_CAPPED_R -> min(rSec, sSec + param * barSec)
            RecipeAnchor.SWAP_BACK_A -> max(aSec, sSec - param * barSec)
            RecipeAnchor.SWAP_BACK_ZERO -> max(0.0, sSec - param * barSec)
            RecipeAnchor.RELEASE_BACK_S -> max(sSec, rSec - param * barSec)
            RecipeAnchor.VOCAL_ENTRY -> vocalEntrySec
            RecipeAnchor.FADE_START -> fadeStartSec
        }

        fun resolve(time: StepTimeV2, deck: DeckTarget, maxOverride: Double? = null): Double {
            val ceiling = maxOverride ?: if (deck == DeckTarget.IN) rSec else outStopSec
            var t = rawAnchor(time.anchor, time.param) +
                time.offsetBars * barSec +
                time.offsetBeats * beatSec +
                time.offsetSec
            time.minAt?.let { t = max(t, rawAnchor(it)) }
            time.maxAt?.let { t = min(t, rawAnchor(it)) }
            return t.coerceIn(0.0, ceiling)
        }
    }

    private class Compiler(
        private val plan: TransitionPlanV1,
        private val outRegion: AudioRegionRef,
        private val stems: List<StemRegionRef>,
        private val useNoStemVariant: Boolean,
        private val availableCapabilities: Set<FxCapability>,
        stepList: List<RecipeStepV2>,
    ) {
        val ctx: AnchorContext
        var fxParams: ScriptFxParams = ScriptFxParams()
        var loopBounds: LoopBounds? = null
        private val lanes = LinkedHashMap<Pair<DeckTarget, EnvelopeKind>, EnvelopeLane>()

        init {
            val rSec = plan.overlapDurationMs / 1000.0
            val barSec = (plan.barDurationMs / 1000.0).coerceAtLeast(0.25)
            val beatSec = barSec / 4.0
            val sSec = ((plan.swapDownbeatMs - plan.frozenExitMs).coerceIn(0L, plan.overlapDurationMs)) / 1000.0
            val aSec = (plan.recipeParams["incomingHoldSec"] ?: 0.0)
                .coerceIn(0.0, max(0.0, sSec - 0.05))
            val vocalEntrySec = (plan.recipeParams["vocalEntrySec"] ?: sSec).coerceIn(0.0, rSec)
            val fadeSec = (plan.recipeParams["fadeSec"] ?: (2.0 * barSec)).coerceIn(0.5, rSec)
            val fadeStartSec = (rSec - fadeSec).coerceIn(0.0, max(0.0, rSec - 0.2))
            val hasPostReleaseTail = stepList.any { step ->
                step is RecipeStepV2.ConfigureFx && step.deck == DeckTarget.OUT &&
                    (step.fx == RecipeFxType.ECHO || step.fx == RecipeFxType.REVERB || step.fallbackFx == RecipeFxType.ECHO)
            } && sSec + 0.15 > rSec
            val baseOutStop = max(rSec, (outRegion.stopAtMixMs ?: plan.overlapDurationMs) / 1000.0)
            val outStopSec = if (hasPostReleaseTail) max(baseOutStop, sSec + 0.20) else baseOutStop
            ctx = AnchorContext(
                rSec = rSec,
                sSec = sSec,
                aSec = aSec,
                barSec = barSec,
                beatSec = beatSec,
                outStopSec = outStopSec,
                vocalEntrySec = vocalEntrySec,
                fadeStartSec = fadeStartSec,
            )
        }

        fun orderedLanes(): List<EnvelopeLane> = lanes.values.toList()

        fun apply(step: RecipeStepV2) {
            when (step) {
                is RecipeStepV2.EnableIsolator -> {
                    fxParams = fxParams.copy(isolate = true)
                }
                is RecipeStepV2.LaneCurve -> applyLaneCurve(step)
                is RecipeStepV2.SwapLows -> applySwapLows(step)
                is RecipeStepV2.ConfigureFx -> applyConfigureFx(step)
            }
        }

        private fun applyLaneCurve(step: RecipeStepV2.LaneCurve) {
            if (step.kind.isStem) {
                val stemKind = ChoreoGuard.stemKindFor(step.kind) ?: return
                if (useNoStemVariant || stems.none { it.deck == step.deck && it.stem == stemKind }) {
                    return
                }
            }
            val lane = buildLane(
                deck = step.deck,
                kind = step.kind,
                shape = step.shape,
                waypoints = step.points,
                steps = step.steps,
                holdNeutralAtStart = step.holdNeutralAtStart,
                ensureNeutralAtReleaseForIn = step.ensureNeutralAtReleaseForIn,
                scaleDepth = plan.djLevel.fxDepth,
            ) ?: return
            lanes[step.deck to step.kind] = lane
            maybeAttachSweepMetadata(step.deck, step.kind, lane)
        }

        private fun maybeAttachSweepMetadata(deck: DeckTarget, kind: EnvelopeKind, lane: EnvelopeLane) {
            val isHp = when (kind) {
                EnvelopeKind.HIGH_PASS_HZ, EnvelopeKind.FILTER_KNOB -> true
                EnvelopeKind.LOW_PASS_HZ -> false
                else -> return
            }
            val existing = if (deck == DeckTarget.OUT) fxParams.sweep else fxParams.incomingSweep
            val updated = (existing ?: SweepFxParams(deck = deck)).copy(
                enabled = true,
                deck = deck,
                isHighPass = isHp,
                startHz = lane.values.first(),
                endHz = lane.values.last(),
                exponential = lane.exponential,
            )
            fxParams = if (deck == DeckTarget.OUT) {
                fxParams.copy(sweep = updated)
            } else {
                fxParams.copy(incomingSweep = updated)
            }
        }

        private fun applySwapLows(step: RecipeStepV2.SwapLows) {
            val center = ctx.resolve(step.at, DeckTarget.OUT, maxOverride = ctx.rSec)
            val halfW = (step.widthBeats * ctx.beatSec * 0.5).coerceAtLeast(0.005)
            var tEnd = min(ctx.rSec, center + halfW)
            var tStart = max(0.0, tEnd - 2.0 * halfW)
            if (ctx.rSec > 0.02 && tStart < 0.001) {
                tStart = 0.001
                tEnd = min(ctx.rSec, max(tEnd, tStart + 0.005))
            }
            val hasOutBass = stems.any { it.deck == DeckTarget.OUT && it.stem == StemKind.BASS }
            val hasInBass = stems.any { it.deck == DeckTarget.IN && it.stem == StemKind.BASS }
            if (step.useBassStems && !useNoStemVariant && hasOutBass && hasInBass) {
                val inBefore = dbToLinear(step.inLowBeforeDb).coerceIn(0f, 1f)
                val outAfter = if (step.outLowAfterDb <= -23f) 0f else dbToLinear(step.outLowAfterDb).coerceIn(0f, 1f)
                lanes[DeckTarget.OUT to EnvelopeKind.STEM_BASS] = EnvelopeLane(
                    deck = DeckTarget.OUT,
                    kind = EnvelopeKind.STEM_BASS,
                    timesSec = listOf(0.0, tStart, tEnd),
                    values = listOf(1f, 1f, outAfter),
                )
                lanes[DeckTarget.IN to EnvelopeKind.STEM_BASS] = EnvelopeLane(
                    deck = DeckTarget.IN,
                    kind = EnvelopeKind.STEM_BASS,
                    timesSec = listOf(0.0, tStart, tEnd),
                    values = listOf(inBefore, inBefore, 1f),
                )
            } else {
                lanes[DeckTarget.OUT to EnvelopeKind.LOW_DB] = EnvelopeLane(
                    deck = DeckTarget.OUT,
                    kind = EnvelopeKind.LOW_DB,
                    timesSec = listOf(0.0, tStart, tEnd),
                    values = listOf(0f, 0f, step.outLowAfterDb),
                )
                lanes[DeckTarget.IN to EnvelopeKind.LOW_DB] = EnvelopeLane(
                    deck = DeckTarget.IN,
                    kind = EnvelopeKind.LOW_DB,
                    timesSec = listOf(0.0, tStart, tEnd),
                    values = listOf(step.inLowBeforeDb, step.inLowBeforeDb, 0f),
                )
            }
        }

        private fun applyConfigureFx(step: RecipeStepV2.ConfigureFx) {
            val capMissing = step.requiredCap != null && step.requiredCap !in availableCapabilities
            if (capMissing && step.fallbackFx == null) return
            val activeFx = if (capMissing) step.fallbackFx!! else step.fx
            val rawCurve = if (capMissing) step.fallbackWetCurve ?: step.wetCurve else step.wetCurve
            val p = step.params
            val depth = plan.djLevel.fxDepth
            when (activeFx) {
                RecipeFxType.LOOP -> configureLoop(step, p)
                RecipeFxType.ROLL, RecipeFxType.LOOP_ROLL -> configureRoll(step, p)
                RecipeFxType.SWEEP -> configureSweep(step, p)
                RecipeFxType.SVF_Q -> configureSvfQ(step, p)
                RecipeFxType.ECHO -> configureEcho(step, p, depth, rawCurve)
                RecipeFxType.REVERB -> configureReverb(step, p, depth, rawCurve)
                RecipeFxType.FLANGER -> configureFlanger(step, p, depth, rawCurve)
                RecipeFxType.PHASER -> configurePhaser(step, p, depth, rawCurve)
                RecipeFxType.RISER -> configureRiser(step, p, depth, rawCurve)
                RecipeFxType.IMPACT -> configureImpact(step, p)
                RecipeFxType.BRAKE -> configureBrake(step, p, rawCurve)
                RecipeFxType.TAPE_STOP -> configureTapeStop(step, p, rawCurve)
                RecipeFxType.SPINBACK -> configureSpinback(step, p, rawCurve)
                RecipeFxType.GATE -> configureGate(step, p, depth, rawCurve)
                RecipeFxType.PUMP -> configurePump(step, p, depth, rawCurve)
            }
        }

        private fun configureLoop(step: RecipeStepV2.ConfigureFx, p: Map<String, Double>) {
            val bars = (p["bars"] ?: 4.0).toInt().coerceAtLeast(1)
            fxParams = fxParams.copy(
                loop = LoopFxParams(
                    enabled = true,
                    bars = bars,
                    seamCrossfadeMs = p["seamCrossfadeMs"] ?: 8.0,
                    holdPhrases = (p["holdPhrases"] ?: 0.0).toInt().coerceAtLeast(0),
                ),
            )
            if (step.deck == DeckTarget.OUT &&
                (plan.geometry == TransitionGeometry.LOOP || outRegion.endMs > outRegion.startMs)
            ) {
                val loopBarsMs = bars.toLong() * plan.barDurationMs
                val startMs = (plan.frozenExitMs - loopBarsMs).coerceAtLeast(outRegion.startMs)
                val endMs = if (startMs < plan.frozenExitMs) {
                    plan.frozenExitMs
                } else {
                    (plan.frozenExitMs + loopBarsMs).coerceAtMost(outRegion.endMs)
                }
                if (endMs > startMs) {
                    loopBounds = LoopBounds(
                        outLoopStartMs = startMs,
                        outLoopEndMs = endMs,
                        outReleaseAtMixMs = plan.overlapDurationMs,
                    )
                }
            }
        }

        private fun configureRoll(step: RecipeStepV2.ConfigureFx, p: Map<String, Double>) {
            val startSec = step.startTime?.let { ctx.resolve(it, step.deck) }
                ?: max(0.0, ctx.sSec - 2.0 * ctx.barSec)
            val endSec = step.endTime?.let { ctx.resolve(it, step.deck) } ?: ctx.sSec
            fxParams = fxParams.copy(
                roll = RollFxParams(
                    enabled = true,
                    deck = step.deck,
                    startMixSec = startSec,
                    releaseMixSec = max(startSec, endSec),
                    beatSec = ctx.beatSec,
                    totalBeats = p["totalBeats"] ?: max(1.0, (endSec - startSec) / ctx.beatSec),
                    hpfStartHz = (p["hpfStartHz"] ?: 200.0).toFloat(),
                    hpfEndHz = (p["hpfEndHz"] ?: 2000.0).toFloat(),
                    boostDb = (p["boostDb"] ?: 2.0).toFloat(),
                    stopOnDownbeat = (p["stopOnDownbeat"] ?: 1.0) >= 0.5,
                ),
            )
        }

        private fun configureSweep(step: RecipeStepV2.ConfigureFx, p: Map<String, Double>) {
            val base = if (step.deck == DeckTarget.OUT) fxParams.sweep else fxParams.incomingSweep
            val isHp = !step.flag
            val updated = (base ?: SweepFxParams(deck = step.deck)).copy(
                enabled = true,
                deck = step.deck,
                isHighPass = isHp,
                startHz = (p["startHz"] ?: if (isHp) 20.0 else 20000.0).toFloat(),
                endHz = (p["endHz"] ?: if (isHp) 1500.0 else 300.0).toFloat(),
                qStart = (p["qStart"] ?: 0.707).toFloat(),
                qEnd = (p["qEnd"] ?: 1.2).toFloat(),
                bars = (p["bars"] ?: 8.0).toInt().coerceAtLeast(1),
            )
            fxParams = if (step.deck == DeckTarget.OUT) {
                fxParams.copy(sweep = updated)
            } else {
                fxParams.copy(incomingSweep = updated)
            }
        }

        private fun configureSvfQ(step: RecipeStepV2.ConfigureFx, p: Map<String, Double>) {
            val qVal = (p["q"] ?: 0.9).toFloat().coerceIn(0.25f, 6.0f)
            val base = if (step.deck == DeckTarget.OUT) fxParams.sweep else fxParams.incomingSweep
            val updated = (base ?: SweepFxParams(enabled = true, deck = step.deck)).copy(
                enabled = true,
                deck = step.deck,
                qEnd = qVal,
            )
            fxParams = if (step.deck == DeckTarget.OUT) {
                fxParams.copy(sweep = updated)
            } else {
                fxParams.copy(incomingSweep = updated)
            }
        }

        private fun configureEcho(
            step: RecipeStepV2.ConfigureFx,
            p: Map<String, Double>,
            depth: Float,
            rawCurve: List<WaypointV2>?,
        ) {
            fxParams = fxParams.copy(
                echo = EchoFxParams(
                    enabled = true,
                    deck = step.deck,
                    delayBeats = p["delayBeats"] ?: 0.5,
                    delaySec = p["delaySec"] ?: 0.0,
                    feedback = (p["feedback"] ?: 0.60).toFloat().coerceIn(0f, 0.95f),
                    wet = ((p["wet"] ?: 0.55) * depth).toFloat().coerceIn(0f, 1.2f),
                    wetHpfHz = (p["wetHpfHz"] ?: 250.0).toFloat(),
                    wetLpfHz = (p["wetLpfHz"] ?: 6000.0).toFloat(),
                    pingPong = (p["pingPong"] ?: if (step.flag) 1.0 else 0.0) >= 0.5,
                ),
            )
            addWetLane(step.deck, EnvelopeKind.ECHO_SEND, rawCurve, depth)
        }

        private fun configureReverb(
            step: RecipeStepV2.ConfigureFx,
            p: Map<String, Double>,
            depth: Float,
            rawCurve: List<WaypointV2>?,
        ) {
            fxParams = fxParams.copy(
                reverb = ReverbFxParams(
                    enabled = true,
                    deck = step.deck,
                    roomSize = (p["roomSize"] ?: 2.2).toFloat(),
                    damping = (p["damping"] ?: 0.55).toFloat(),
                    wet = ((p["wet"] ?: 0.35) * depth).toFloat().coerceIn(0f, 1.2f),
                ),
            )
            addWetLane(step.deck, EnvelopeKind.REVERB_SEND, rawCurve, depth)
        }

        private fun configureFlanger(
            step: RecipeStepV2.ConfigureFx,
            p: Map<String, Double>,
            depth: Float,
            rawCurve: List<WaypointV2>?,
        ) {
            fxParams = fxParams.copy(
                flanger = FlangerFxParams(
                    enabled = true,
                    deck = step.deck,
                    lfoCyclesPerBars = p["lfoCyclesPerBars"] ?: 2.0,
                    lfoHz = p["lfoHz"] ?: 0.0,
                    depthSec = p["depthSec"] ?: 0.003,
                    feedback = (p["feedback"] ?: 0.64).toFloat().coerceIn(0f, 0.95f),
                    wetPeak = ((p["wetPeak"] ?: 0.60) * depth).toFloat().coerceIn(0f, 1.2f),
                    stereoOffsetDeg = (p["stereoOffsetDeg"] ?: 90.0).toFloat(),
                ),
            )
            addWetLane(step.deck, EnvelopeKind.FLANGER_WET, rawCurve, depth)
        }

        private fun configurePhaser(
            step: RecipeStepV2.ConfigureFx,
            p: Map<String, Double>,
            depth: Float,
            rawCurve: List<WaypointV2>?,
        ) {
            fxParams = fxParams.copy(
                phaser = PhaserFxParams(
                    enabled = true,
                    deck = step.deck,
                    stages = (p["stages"] ?: 6.0).toInt().coerceIn(2, 12),
                    rateBeats = p["rateBeats"] ?: 4.0,
                    depth = ((p["depth"] ?: 0.70) * depth).toFloat().coerceIn(0f, 1.0f),
                    feedback = (p["feedback"] ?: 0.60).toFloat().coerceIn(0f, 0.95f),
                    wetPeak = ((p["wetPeak"] ?: 0.70) * depth).toFloat().coerceIn(0f, 1.2f),
                    stereoOffsetDeg = (p["stereoOffsetDeg"] ?: 90.0).toFloat(),
                ),
            )
            addWetLane(step.deck, EnvelopeKind.PHASER_WET, rawCurve, depth)
        }

        private fun configureRiser(
            step: RecipeStepV2.ConfigureFx,
            p: Map<String, Double>,
            depth: Float,
            rawCurve: List<WaypointV2>?,
        ) {
            fxParams = fxParams.copy(
                riser = RiserFxParams(
                    enabled = true,
                    deck = step.deck,
                    beatSec = ctx.beatSec,
                    lengthBeats = p["lengthBeats"] ?: 16.0,
                    noiseSweepStartHz = (p["noiseSweepStartHz"] ?: 300.0).toFloat(),
                    noiseSweepEndHz = (p["noiseSweepEndHz"] ?: 8000.0).toFloat(),
                    noiseLevelPeak = ((p["noiseLevelPeak"] ?: 0.70) * depth).toFloat().coerceIn(0f, 1.4f),
                    uplifterOctaves = (p["uplifterOctaves"] ?: 1.0).toFloat(),
                    includeSnareRoll = (p["includeSnareRoll"] ?: 1.0) >= 0.5,
                    reverseReverbTailBeats = p["reverseReverbTailBeats"] ?: 1.0,
                ),
            )
            addWetLane(step.deck, EnvelopeKind.RISER_LEVEL, rawCurve, depth)
        }

        private fun configureImpact(step: RecipeStepV2.ConfigureFx, p: Map<String, Double>) {
            val trigSec = step.startTime?.let { ctx.resolve(it, step.deck) } ?: ctx.sSec
            fxParams = fxParams.copy(
                impact = ImpactFxParams(
                    enabled = true,
                    deck = step.deck,
                    triggerMixSec = trigSec,
                    peakDbfs = (p["peakDbfs"] ?: -6.0).toFloat(),
                    lowThumpHz = (p["lowThumpHz"] ?: 55.0).toFloat(),
                    noiseBurstMs = p["noiseBurstMs"] ?: 180.0,
                ),
            )
        }

        private fun configureBrake(
            step: RecipeStepV2.ConfigureFx,
            p: Map<String, Double>,
            rawCurve: List<WaypointV2>?,
        ) {
            val startSec = step.startTime?.let { ctx.resolve(it, step.deck) }
                ?: max(0.0, ctx.sSec - 1.5 * ctx.beatSec)
            val endSec = step.endTime?.let { ctx.resolve(it, step.deck) } ?: ctx.sSec
            fxParams = fxParams.copy(
                brake = BrakeFxParams(
                    enabled = true,
                    deck = step.deck,
                    startMixSec = startSec,
                    lengthSec = max(0.05, endSec - startSec),
                    endRate = (p["endRate"] ?: 0.05).toFloat(),
                    lpfStartHz = (p["lpfStartHz"] ?: 18000.0).toFloat(),
                    lpfEndHz = (p["lpfEndHz"] ?: 800.0).toFloat(),
                    endFadeMs = p["endFadeMs"] ?: 10.0,
                ),
            )
            addWetLane(step.deck, EnvelopeKind.BRAKE, rawCurve, scaleDepth = 1f)
        }

        private fun configureTapeStop(
            step: RecipeStepV2.ConfigureFx,
            p: Map<String, Double>,
            rawCurve: List<WaypointV2>?,
        ) {
            val startSec = step.startTime?.let { ctx.resolve(it, step.deck) }
                ?: max(0.0, ctx.sSec - 2.0 * ctx.beatSec)
            val endSec = step.endTime?.let { ctx.resolve(it, step.deck) } ?: ctx.sSec
            fxParams = fxParams.copy(
                tapeStop = TapeStopFxParams(
                    enabled = true,
                    deck = step.deck,
                    startMixSec = startSec,
                    lengthSec = max(0.05, endSec - startSec),
                    wowDepth = (p["wowDepth"] ?: 0.03).toFloat(),
                    wowRateHz = (p["wowRateHz"] ?: 5.0).toFloat(),
                    lpfStartHz = (p["lpfStartHz"] ?: 18000.0).toFloat(),
                    lpfEndHz = (p["lpfEndHz"] ?: 600.0).toFloat(),
                    endFadeMs = p["endFadeMs"] ?: 10.0,
                ),
            )
            addWetLane(step.deck, EnvelopeKind.BRAKE, rawCurve, scaleDepth = 1f)
        }

        private fun configureSpinback(
            step: RecipeStepV2.ConfigureFx,
            p: Map<String, Double>,
            rawCurve: List<WaypointV2>?,
        ) {
            val startSec = step.startTime?.let { ctx.resolve(it, step.deck) }
                ?: max(0.0, ctx.sSec - 1.5 * ctx.beatSec)
            val endSec = step.endTime?.let { ctx.resolve(it, step.deck) }
                ?: max(startSec + 0.05, ctx.sSec - ctx.beatSec)
            fxParams = fxParams.copy(
                spinback = SpinbackFxParams(
                    enabled = true,
                    deck = step.deck,
                    startMixSec = startSec,
                    lengthSec = max(0.05, endSec - startSec),
                    peakReverseSpeed = p["peakReverseSpeed"] ?: 3.0,
                    silenceGapBeats = p["silenceGapBeats"] ?: 1.0,
                ),
            )
            addWetLane(step.deck, EnvelopeKind.BRAKE, rawCurve, scaleDepth = 1f)
        }

        private fun configureGate(
            step: RecipeStepV2.ConfigureFx,
            p: Map<String, Double>,
            depth: Float,
            rawCurve: List<WaypointV2>?,
        ) {
            fxParams = fxParams.copy(
                gate = GateFxParams(
                    enabled = true,
                    deck = step.deck,
                    beatSec = ctx.beatSec,
                    divisionStepsPerBeat = (p["divisionStepsPerBeat"] ?: 4.0).toFloat(),
                    swing = (p["swing"] ?: 0.0).toFloat(),
                    isStutter = (p["isStutter"] ?: if (step.flag) 1.0 else 0.0) >= 0.5,
                    depthPeak = ((p["depthPeak"] ?: 1.0) * depth).toFloat().coerceIn(0f, 1.0f),
                    rampBars = p["rampBars"] ?: 2.0,
                ),
            )
            addWetLane(step.deck, EnvelopeKind.GATE_DEPTH, rawCurve, depth)
        }

        private fun configurePump(
            step: RecipeStepV2.ConfigureFx,
            p: Map<String, Double>,
            depth: Float,
            rawCurve: List<WaypointV2>?,
        ) {
            fxParams = fxParams.copy(
                pump = PumpFxParams(
                    enabled = true,
                    deck = step.deck,
                    beatSec = ctx.beatSec,
                    fullBand = (p["fullBand"] ?: if (step.flag) 1.0 else 0.0) >= 0.5,
                ),
            )
            addWetLane(step.deck, EnvelopeKind.PUMP_DEPTH, rawCurve, depth)
        }

        private fun addWetLane(
            deck: DeckTarget,
            kind: EnvelopeKind,
            rawCurve: List<WaypointV2>?,
            scaleDepth: Float,
        ) {
            if (rawCurve.isNullOrEmpty()) return
            val lane = buildLane(
                deck = deck,
                kind = kind,
                shape = CurveShapeV2.LINEAR,
                waypoints = rawCurve,
                steps = 12,
                holdNeutralAtStart = (deck == DeckTarget.OUT),
                ensureNeutralAtReleaseForIn = (deck == DeckTarget.IN),
                scaleDepth = scaleDepth,
            ) ?: return
            lanes[deck to kind] = lane
        }

        private fun buildLane(
            deck: DeckTarget,
            kind: EnvelopeKind,
            shape: CurveShapeV2,
            waypoints: List<WaypointV2>,
            steps: Int,
            holdNeutralAtStart: Boolean,
            ensureNeutralAtReleaseForIn: Boolean,
            scaleDepth: Float,
        ): EnvelopeLane? {
            if (waypoints.isEmpty()) return null
            val maxTime = if (deck == DeckTarget.IN || kind == EnvelopeKind.GAIN) ctx.rSec else ctx.outStopSec
            val resolved = ArrayList<Pair<Double, Float>>(waypoints.size + 3)
            for (wp in waypoints) {
                val valScaled = if (wp.scaleByFxDepth) {
                    wp.depthBase + scaleDepth * (wp.value - wp.depthBase)
                } else {
                    wp.value
                }
                val t = ctx.resolve(wp.time, deck, maxOverride = maxTime)
                resolved += (t to valScaled)
            }

            if (holdNeutralAtStart && resolved.first().first > 0.002) {
                val holdVal = if (deck == DeckTarget.IN && kind == EnvelopeKind.GAIN) 0f else kind.neutralValue
                resolved.add(0, 0.0 to holdVal)
            } else if (deck == DeckTarget.IN && kind == EnvelopeKind.GAIN && resolved.first().first > 0.002) {
                resolved.add(0, 0.0 to 0f)
            } else if (deck == DeckTarget.OUT && kind == EnvelopeKind.GAIN &&
                resolved.first().first <= 0.002 && !ChoreoGuard.isNeutral(EnvelopeKind.GAIN, resolved.first().second)
            ) {
                resolved[0] = 0.0 to 1f
            }

            if (deck == DeckTarget.IN && ensureNeutralAtReleaseForIn &&
                !ChoreoGuard.isNeutral(kind, resolved.last().second)
            ) {
                resolved += (ctx.rSec to kind.neutralValue)
            }

            val normalized = normalizeStrictTimes(resolved, minTime = 0.0, maxTime = maxTime)
            if (normalized.isEmpty()) return null
            val expanded = expandShape(normalized, shape, steps)
            val withPreroll = if (deck == DeckTarget.OUT && kind == EnvelopeKind.GAIN) {
                val head = expanded.first()
                if (head.first > 0.0) {
                    listOf(-PREROLL_SEC to 0f, 0.0 to 1f) + expanded
                } else {
                    listOf(-PREROLL_SEC to 0f, 0.0 to 1f) + expanded.drop(1)
                }
            } else {
                expanded
            }
            val strictFinal = normalizeStrictTimes(
                withPreroll,
                minTime = if (deck == DeckTarget.OUT && kind == EnvelopeKind.GAIN) -PREROLL_SEC else 0.0,
                maxTime = maxTime,
            )
            return EnvelopeLane(
                deck = deck,
                kind = kind,
                timesSec = strictFinal.map { it.first },
                values = strictFinal.map { it.second },
                exponential = shape == CurveShapeV2.EXPONENTIAL || kind.defaultExponential,
            )
        }

        private fun normalizeStrictTimes(
            raw: List<Pair<Double, Float>>,
            minTime: Double,
            maxTime: Double,
        ): List<Pair<Double, Float>> {
            if (raw.isEmpty()) return emptyList()
            val n = raw.size
            val ts = DoubleArray(n) { raw[it].first.coerceIn(minTime, maxTime) }
            val vs = FloatArray(n) { raw[it].second }
            for (i in 1 until n) {
                if (ts[i] < ts[i - 1]) ts[i] = ts[i - 1]
            }
            val coalesced = ArrayList<Pair<Double, Float>>(n)
            for (i in 0 until n) {
                val prev = coalesced.lastOrNull()
                if (prev != null && abs(ts[i] - prev.first) <= 1e-6 && abs(vs[i] - prev.second) <= 1e-6f) {
                    continue
                }
                coalesced += (ts[i] to vs[i])
            }
            if (coalesced.size <= 1) return coalesced
            val outT = DoubleArray(coalesced.size) { coalesced[it].first }
            val outV = FloatArray(coalesced.size) { coalesced[it].second }
            val eps = 0.001
            for (i in outT.lastIndex - 1 downTo 0) {
                if (outT[i] >= outT[i + 1] - 1e-6) {
                    outT[i] = max(minTime, outT[i + 1] - eps)
                }
            }
            for (i in 1 until outT.size) {
                if (outT[i] <= outT[i - 1] + 1e-6) {
                    outT[i] = min(maxTime, outT[i - 1] + eps)
                }
            }
            val res = ArrayList<Pair<Double, Float>>(outT.size)
            for (i in outT.indices) {
                val prev = res.lastOrNull()
                if (prev == null || outT[i] > prev.first + 1e-9) {
                    res += (outT[i] to outV[i])
                } else if (i == outT.lastIndex && res.size >= 2) {
                    res[res.lastIndex] = prev.first to outV[i]
                }
            }
            return res
        }

        private fun expandShape(
            pts: List<Pair<Double, Float>>,
            shape: CurveShapeV2,
            steps: Int,
        ): List<Pair<Double, Float>> {
            if (shape != CurveShapeV2.EQUAL_POWER_RISE && shape != CurveShapeV2.EQUAL_POWER_FALL) {
                return pts
            }
            if (pts.size <= 1) return pts
            val nSteps = steps.coerceIn(2, 32)
            val out = ArrayList<Pair<Double, Float>>(pts.size * nSteps)
            out += pts.first()
            for (i in 1 until pts.size) {
                val (t0, v0) = pts[i - 1]
                val (t1, v1) = pts[i]
                val dt = t1 - t0
                if (dt <= 0.005 || abs(v1 - v0) <= 1e-4f) {
                    out += (t1 to v1)
                    continue
                }
                for (k in 1..nSteps) {
                    val u = k.toDouble() / nSteps.toDouble()
                    val t = t0 + dt * u
                    val w = if (shape == CurveShapeV2.EQUAL_POWER_RISE) {
                        sin(u * PI * 0.5)
                    } else {
                        1.0 - cos(u * PI * 0.5)
                    }
                    val v = v0 + (v1 - v0) * w.toFloat()
                    out += (t to v)
                }
            }
            return out
        }

        private fun dbToLinear(db: Float): Float =
            if (!db.isFinite() || db <= -48f) 0f else 10.0.pow(db / 20.0).toFloat()
    }
}
