package com.music.bitchord.playback.loopmix

import android.util.Log
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.RenderGeometryV1
import com.music.bitchord.djmix.core.contracts.FxCapability
import com.music.bitchord.djmix.core.contracts.DeckTarget as CoreDeckTarget
import com.music.bitchord.djmix.core.recipes.ScriptExecutionGuard
import com.music.bitchord.playback.djmix.PreparedPair
import com.music.bitchord.playback.djmix.ChoreoScriptBuilder
import com.music.bitchord.playback.djmix.StraightRateMode
import com.music.bitchord.playback.djmix.StraightMixGeometry
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LoopMixDuck
import com.music.bitchord.playback.smart.MixGain
import com.music.bitchord.playback.smart.TransitionAlternatives
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.HandoffStatus
import com.music.bitchord.playback.smart.qualifyMusicalHandoff
// AUTOMIX-V2(E): choreographer + stems/structure inputs.
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.StemSet
import com.music.bitchord.playback.smart.choreo.AutomixV2Choreo
import com.music.bitchord.playback.smart.choreo.ChoreoCaps
import com.music.bitchord.playback.smart.choreo.ChoreoRendererBridge
import com.music.bitchord.playback.smart.choreo.ChoreoScript
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.choreo.RecipeId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Legacy test/consumer compatibility helper. Production session construction no
 * longer invokes synchronous separation; canonical stem variants are compiled
 * by [ChoreoSessionGlue] from the frozen recipe instead.
 */
@Deprecated("Synchronous separation is no longer part of the production session path")
internal fun dryMinusVocal(dry: FloatArray, vocal: FloatArray): FloatArray? =
    if (dry.size != vocal.size) null else FloatArray(dry.size) { dry[it] - vocal[it] }

/**
 * One loop mix, from planning to the incoming player's takeover: the plan and
 * its clock, the renderer with both decks loaded, and the two sinks' ports.
 *
 * Every time here is mix seconds — 0 being the moment the outgoing song reaches
 * its loop start — unless it says track seconds.
 */
class LoopMixSession private constructor(
    val plan: LoopMix.Plan,
    val clock: LoopMix.Clock,
    val outId: String,
    val inId: String,
    val renderer: LoopMixRenderer,
    val sampleRate: Int,
    /** Track second the incoming player becomes audible at (knot-exact). */
    val handoverTrack: Double = clock.elementTrackTime,
    /** Actual native deck configuration; unknown only for manually constructed sessions. */
    val configuredOutLoops: Boolean? = null,
    val configuredInLoops: Boolean? = null,
    /** Source of the emitted knots, independent of planner anchor evidence. */
    val outGridSource: GridSource = GridSource.UNKNOWN,
    val inGridSource: GridSource = GridSource.UNKNOWN,
    /** Actual installed script path; a non-looping bridge is not automatically a straight recipe. */
    val executionPath: String = "classic",
    val preparationGeneration: Long = 0L,
    val planId: String = "",
    /** Exact canonical script installed for this prepared generation, when R2 is active. */
    val canonicalScript: TransitionScriptV1? = null,
    val canonicalGeometry: RenderGeometryV1? = null,
    /** True when renderer attachment forced compilation of this same recipe's no-stem variant. */
    val canonicalNoStemVariant: Boolean = false,
) {
    /** Preserve the prior manual-session constructor; actual geometry is unknown for this path. */
    private constructor(
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        outId: String,
        inId: String,
        renderer: LoopMixRenderer,
        sampleRate: Int,
        handoverTrack: Double,
    ) : this(plan, clock, outId, inId, renderer, sampleRate, handoverTrack,
        configuredOutLoops = null, configuredInLoops = null)

    /** Track microseconds of the outgoing loop start: where the sink splices. */
    val takeoverUs: Long = (plan.out.loopStart * 1_000_000).roundToLong()

    /** Where the incoming player starts (silent), and the mix second it starts at. */
    var inStartTrack: Double = 0.0
        private set
    var inStartAt: Double = 0.0
        private set

    /**
     * Null until [attach]. A primed session is closed when the queue changes,
     * which is before either sink exists. Reading a port that was never
     * attached must not throw.
     */
    var outPort: LoopMixPort? = null
        private set
    var inPort: LoopMixPort? = null
        private set

    /** AUTOMIX-V2(E): the choreographed move (enum name), display label, recipe ID, FX count, and top-3 candidates. */
    var choreoMove: String = ""
        internal set
    var choreoLabel: String = ""
        internal set
    var choreoRecipeId: String = ""
        internal set
    var choreoRecipeName: String = ""
        internal set
    var choreoFxCount: Int = 0
        internal set
    var choreoFxNames: List<String> = emptyList()
        internal set
    var choreoStemsUsed: Int = 0
        internal set
    var choreoLevel: String = ""
        internal set
    var choreoTopCandidates: List<String> = emptyList()
        internal set

    private val closed = AtomicBoolean(false)
    private val preparedOwnershipClaimed = AtomicBoolean(false)
    private val retirementLock = Any()
    private var nativeRetirementHook: ((LoopMixRenderer) -> Boolean)? = null

    /** A consumed session may still have a stale alias in the preparation publisher. */
    internal fun claimPreparedOwnership(): Boolean = synchronized(retirementLock) {
        !closed.get() && preparedOwnershipClaimed.compareAndSet(false, true)
    }

    internal fun claimForAttachment(): Boolean = synchronized(retirementLock) {
        if (closed.get()) false else { preparedOwnershipClaimed.set(true); true }
    }

    internal fun closeIfStillPrepared() = closeOwned(onlyPrepared = true)

    /** Install before attach; the hook defers native retirement, never port teardown. */
    internal fun installNativeRetirementHook(hook: (LoopMixRenderer) -> Boolean): Boolean =
        synchronized(retirementLock) {
            if (closed.get() || nativeRetirementHook != null) false
            else { nativeRetirementHook = hook; true }
        }

    /** Set only by the setup funnel before native prepare; admission never arms a live renderer. */
    internal var captureConfigured: Boolean = false
        private set
    internal var capturePlayerEpoch: Long? = null
        private set

    /** The residual offset after alignment, ms (player minus deck); NaN until measured. */
    var alignedErrorMs: Double = Double.NaN
        private set

    fun attach(out: LoopMixPort, inn: LoopMixPort) {
        outPort = out
        inPort = inn
        val firstFrame = renderer.firstFrame()
        out.armOutgoing(LoopMixPort.OutArm(renderer, takeoverUs, sampleRate, firstFrame))
    }

    /**
     * Mix seconds now, read off the outgoing sink's own clock (what the
     * listener is hearing); null before it has a reading.
     */
    fun mixSeconds(nowNanos: Long, playing: Boolean): Double? =
        outPort?.presentedSeconds(nowNanos, playing)?.let { it - plan.out.loopStart }

    /**
     * Where the incoming player has to start: [STARTUP_LEAD] into the mix,
     * silent, at the track position that would put it on the deck's own
     * timeline. Before the song's start it starts at 0, and the silence the
     * alignment inserts makes up the rest.
     */
    fun planIncomingStart() {
        canonicalGeometry?.let { geometry ->
            inStartAt = geometry.incomingStartMixSec
            inStartTrack = geometry.incomingStartTrackSec
            return
        }
        val (startAt, startTrack) = incomingStartFor(clock, handoverTrack)
        inStartAt = startAt
        inStartTrack = startTrack
    }

    /**
     * How far the incoming player's audio is ahead of where the deck will be,
     * in seconds, measured now on both sinks' clocks; null without readings.
     * After the release the deck plays its song at rate 1 from its loop end, so
     * at mix second m it is at handoverTrack - (elementAt - m) — before the
     * release too, as far as the player's timeline is concerned.
     */
    fun offsetNow(nowNanos: Long): Double? {
        val deck = incomingTrackNow(nowNanos, outgoingPlaying = true) ?: return null
        val player = inPort?.presentedSeconds(nowNanos, playing = true) ?: return null
        return player - deck
    }

    /** The decoder's rate-1 timeline, anchored to the actual renderer knot. */
    fun incomingTrackNow(nowNanos: Long, outgoingPlaying: Boolean): Double? =
        mixSeconds(nowNanos, outgoingPlaying)?.let { handoverTrack + (it - clock.elementAt) }

    /** Asks the incoming sink to cancel [offsetSeconds] (player ahead: silence inserted; behind: frames dropped). */
    fun correct(offsetSeconds: Double) {
        val port = inPort ?: return
        val frames = (offsetSeconds * port.sampleRate.coerceAtLeast(1)).roundToLong()
        if (frames != 0L) port.requestShift(-frames)
    }

    fun recordAligned(errorSeconds: Double) {
        alignedErrorMs = errorSeconds * 1000
    }

    /** Idempotent port teardown; an admitted capture owner controls subsequent native retirement. */
    fun close() = closeOwned(onlyPrepared = false)

    private fun closeOwned(onlyPrepared: Boolean) {
        val retire = synchronized(retirementLock) {
            if (onlyPrepared && preparedOwnershipClaimed.get()) return
            if (!closed.compareAndSet(false, true)) return
            nativeRetirementHook
        }
        outPort?.disarm()
        inPort?.disarm()
        outPort = null
        inPort = null
        if (retire?.invoke(renderer) != true) renderer.close()
    }

    companion object {
        private const val TAG = "BitChordLoopMix"

        /**
         * Measured energy-weighted clash above which the outgoing vocal is
         * stem-ducked.
         *
         * 0.12, not 0.05: E is a weighted product, so a soft verse over a soft
         * verse lands at 0.04-0.16 (0.09 canonical) — exactly the band the
         * planner deliberately lets through the veto. Ducking that to a 0.15
         * floor carves a -16.5 dB hole into a clean handover. Above ~0.12 the
         * pair is two real voices, and there a solvable gain curve beats two
         * intelligible leads. Deliberately distinct from
         * VOCAL_CLASH_TOLERANCE (0.05), which is the planner's rendering
         * target for "reads as clean", a stricter question than "worth
         * ducking".
         */
        const val STEM_DUCK_TRIGGER = LoopMixDuck.TRIGGER

        /**
         * Residual outgoing vocal gain after the duck. 0.15, not silence: a
         * fully removed vocal under an instrument bed reads as karaoke, which
         * is a worse artefact than a quiet collision. At the Wiener curve this
         * floor keeps about 2% energy, but as a gain on the stem it keeps the
         * phrase present and audible.
         */
        const val STEM_DUCK_FLOOR = LoopMixDuck.MIN_FLOOR

        /** Two-thread daemon pool for parallel OUT+IN region decode (vc73). */
        private val DECODE_EXECUTOR: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newFixedThreadPool(
                2,
                java.util.concurrent.ThreadFactory { r -> Thread(r, "loopmix-decode").apply { isDaemon = true } },
            )

        /** Mix seconds into the mix at which the incoming player starts, silent. */
        const val STARTUP_LEAD = PlaybackIncomingStart.STARTUP_LEAD

        /** Incoming player startup on the exact handover timeline. */
        fun incomingStartFor(
            clock: LoopMix.Clock,
            handoverTrack: Double = clock.elementTrackTime,
        ): Pair<Double, Double> = PlaybackIncomingStart.incomingStartFor(clock, handoverTrack)

        /** Latest cue: at least [startupLead] before elementAt when feasible. */
        fun clampIncomingStartAt(inStartAt: Double, elementAt: Double, startupLead: Double = STARTUP_LEAD): Double =
            PlaybackIncomingStart.clampIncomingStartAt(inStartAt, elementAt, startupLead)

        /** Seconds of each song decoded either side of what the decks play. */
        private const val PREROLL_SECONDS = 1.5
        private const val TAIL_SECONDS = 3.0

        /**
         * Builds the renderer for [plan] at [sampleRate], decoding the two
         * regions through [decode] (track seconds from..to, resampled to
         * [sampleRate]); null when either won't decode. Blocking: call off the
         * main thread. [inLevel] scales the incoming deck (loudness matching).
         */
        fun build(
            plan: LoopMix.Plan,
            outId: String,
            inId: String,
            sampleRate: Int,
            inLevel: Float,
            highQuality: Boolean = true,
            script: PhraseScript? = null,
            separateStem: ((side: LoopMix.Side, left: FloatArray, right: FloatArray, sampleRate: Int, keepVocals: Boolean) -> Pair<FloatArray, FloatArray>?)? = null,
            separateVocalStem: ((side: LoopMix.Side, left: FloatArray, right: FloatArray, sampleRate: Int) -> Pair<FloatArray, FloatArray>?)? = null,
            separateVocalPair: ((side: LoopMix.Side, left: FloatArray, right: FloatArray, sampleRate: Int) -> Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>?)? = null,
            outAnalysis: TrackAnalysis? = null,
            inAnalysis: TrackAnalysis? = null,
            sungOut: List<LoopMix.Span>? = null,
            sungIn: List<LoopMix.Span>? = null,
            onRefusal: ((String) -> Unit)? = null,
            onEnvelopeInstalled: ((deck: Int, kind: Int, times: DoubleArray, values: FloatArray) -> Unit)? = null,
            // AUTOMIX-V2(E): all optional; defaults keep the vc91 build cleanly.
            outSongMap: SongMap? = null,
            inSongMap: SongMap? = null,
            djLevel: DjLevel = AutomixV2Choreo.level,
            albumOrder: Boolean = false,
            preparedPrep: ChoreoSessionGlue.Prep? = null,
            preparedOutStems: StemSet? = null,
            preparedInStems: StemSet? = null,
            preparedScript: ChoreoScript? = null,
            preparedOutRegion: LoopRegion? = null,
            preparedInRegion: LoopRegion? = null,
            preparedCanonicalScript: TransitionScriptV1? = null,
            preparedCanonicalGeometry: RenderGeometryV1? = null,
            /** All production arms opt in. Defaults preserve legacy non-production probes. */
            preparedOnly: Boolean = false,
            preparedSampleRate: Int? = null,
            straightRateMode: StraightRateMode? = null,
            preparationGeneration: Long = 0L,
            useNoStemVariant: Boolean = false,
            planId: String = "",
            preparedGeneration: Long = 0L,
            preparedPlanId: String = "",
            canonicalPlan: TransitionPlanV1? = null,
            canonicalRecipe: RecipeV2? = null,
            captureEnabled: Boolean = false,
            capturePlayerEpoch: Long? = null,
            decode: (side: LoopMix.Side, from: Double, to: Double) -> LoopRegion?,
        ): LoopMixSession? {
            fun refuse(reason: String): LoopMixSession? {
                onRefusal?.invoke(reason)
                return null
            }
            if (sampleRate <= 0) return refuse("invalid_sample_rate")
            val genMismatch = preparationGeneration > 0L && preparedGeneration > 0L &&
                preparedGeneration != preparationGeneration
            val idMismatch = planId.isNotEmpty() && preparedPlanId.isNotEmpty() &&
                preparedPlanId != planId
            if (preparedOnly && (preparedSampleRate != sampleRate || preparedPrep == null ||
                    preparedPrep.sampleRate != sampleRate ||
                    !ChoreoSessionGlue.isSamePlan(preparedPrep.plan, plan) ||
                    genMismatch || idMismatch ||
                    preparedPrep.level != djLevel || preparedPrep.pairKey != "$outId->$inId" ||
                    preparedPrep.albumOrder != albumOrder || preparedPrep.straightRateMode != straightRateMode)) {
                return refuse("prepared_geometry_or_rate_mismatch")
            }
            val crown = plan.outLoops && plan.inLoops
            // Preserve the early capability gate for non-crown routes. Crowns
            // qualify first, even without JNI, so a geometry refusal is not
            // misattributed to renderer capability (and never reaches decode).
            if (!crown && !LoopMixRenderer.available) return refuse("renderer_unavailable")
            val naturalHandoff = plan.handoff != null && plan.alternativeKind == "natural-end" &&
                !plan.echoTail && script == null
            val contractEcho = naturalHandoff || (plan.handoff != null && plan.echoTail &&
                ((plan.rung == "Echo" && script == null) || script?.choice?.template == MixTemplate.M6))
            val handoff = plan.handoff.takeIf { contractEcho }
            // Pass 1: caps, structure, and optional verified drop re-time without synchronous stem separation.
            val v2Prep = if (preparedOnly || canonicalPlan != null) preparedPrep else if (AutomixV2Choreo.enabled && LoopMixRenderer.available && handoff == null) {
                preparedPrep?.takeIf {
                    it.pairKey == "$outId->$inId" &&
                        it.level == djLevel &&
                        it.albumOrder == albumOrder &&
                        ChoreoSessionGlue.isSamePlan(it.plan, plan) &&
                        (it.sampleRate == 0 || it.sampleRate == sampleRate) &&
                        it.straightRateMode == straightRateMode
                }
                    ?: ChoreoSessionGlue.prepare(
                        plan, script, outAnalysis, inAnalysis, outSongMap, inSongMap, djLevel, outId, inId,
                        sampleRate, albumOrder,
                        stemsExpected = !useNoStemVariant && (preparedOutStems != null || preparedInStems != null) && AutomixV2Choreo.useStems,
                    )
            } else null
            return buildPlanned(
                v2Prep?.plan ?: plan, outId, inId, sampleRate, inLevel, highQuality, script,
                outAnalysis, inAnalysis, sungOut, sungIn,
                onRefusal, onEnvelopeInstalled,
                v2Prep,
                if (useNoStemVariant) null else preparedOutStems,
                if (useNoStemVariant) null else preparedInStems,
                preparedScript, preparedOutRegion, preparedInRegion,
                preparedOnly, straightRateMode, djLevel, preparationGeneration,
                useNoStemVariant, planId, canonicalPlan, canonicalRecipe, preparedCanonicalScript, preparedCanonicalGeometry, captureEnabled, capturePlayerEpoch, decode,
            )
        }

        /** Prepared-input-only entry: two ordinary decks, the existing renderer and handover lifecycle. */
        fun buildStraight(
            prepared: PreparedPair,
            outId: String,
            inId: String,
            sampleRate: Int,
            inLevel: Float,
            highQuality: Boolean = true,
            onRefusal: ((String) -> Unit)? = null,
            useNoStemVariant: Boolean = false,
            planId: String = "",
            captureEnabled: Boolean = false,
            capturePlayerEpoch: Long? = null,
        ): LoopMixSession? {
            val plan = prepared.plan
            if (plan == null || plan.outLoops || plan.inLoops || plan.handoff != null ||
                prepared.straightRateMode == null || prepared.outId != outId || prepared.inId != inId ||
                prepared.sampleRate != sampleRate || (prepared.script == null && prepared.transitionPlanV1 == null)) {
                onRefusal?.invoke("straight_prepared_contract_missing")
                return null
            }
            val effectiveNoStem = useNoStemVariant || prepared.useNoStemVariant
            val effectivePlanId = planId.ifEmpty { prepared.transitionPlanV1?.planId.orEmpty() }
            val preparedPlanId = prepared.transitionPlanV1?.planId.orEmpty()
            return build(
                plan, outId, inId, sampleRate, inLevel, highQuality,
                outAnalysis = prepared.outAnalysis, inAnalysis = prepared.inAnalysis,
                sungOut = prepared.sungOut, sungIn = prepared.sungIn,
                onRefusal = onRefusal, djLevel = prepared.djLevel, albumOrder = prepared.albumOrder,
                preparedPrep = prepared.v2Prep,
                preparedOutStems = if (effectiveNoStem) null else prepared.outStems,
                preparedInStems = if (effectiveNoStem) null else prepared.inStems,
                preparedScript = prepared.script,
                preparedCanonicalScript = if (effectiveNoStem) prepared.canonicalNoStemScript else prepared.canonicalScript,
                preparedCanonicalGeometry = prepared.renderGeometry,
                preparedOutRegion = prepared.outRegion, preparedInRegion = prepared.inRegion,
                preparedOnly = true, preparedSampleRate = prepared.sampleRate,
                straightRateMode = prepared.straightRateMode, preparationGeneration = prepared.generation,
                useNoStemVariant = effectiveNoStem,
                planId = effectivePlanId,
                preparedGeneration = prepared.generation,
                preparedPlanId = preparedPlanId,
                canonicalPlan = prepared.transitionPlanV1,
                canonicalRecipe = prepared.frozenRecipe,
                captureEnabled = captureEnabled,
                capturePlayerEpoch = capturePlayerEpoch,
                decode = { _, _, _ -> error("prepared-only straight build must never decode") },
            )
        }

        private fun buildPlanned(
            plan: LoopMix.Plan,
            outId: String,
            inId: String,
            sampleRate: Int,
            inLevel: Float,
            highQuality: Boolean,
            script: PhraseScript?,
            outAnalysis: TrackAnalysis?,
            inAnalysis: TrackAnalysis?,
            sungOut: List<LoopMix.Span>?,
            sungIn: List<LoopMix.Span>?,
            onRefusal: ((String) -> Unit)?,
            onEnvelopeInstalled: ((deck: Int, kind: Int, times: DoubleArray, values: FloatArray) -> Unit)?,
            v2Prep: ChoreoSessionGlue.Prep?,
            preparedOutStems: StemSet?,
            preparedInStems: StemSet?,
            preparedScript: ChoreoScript?,
            preparedOutRegion: LoopRegion?,
            preparedInRegion: LoopRegion?,
            preparedOnly: Boolean,
            straightRateMode: StraightRateMode?,
            djLevel: DjLevel,
            preparationGeneration: Long,
            useNoStemVariant: Boolean = false,
            planId: String = "",
            canonicalPlan: TransitionPlanV1? = null,
            canonicalRecipe: RecipeV2? = null,
            preparedCanonicalScript: TransitionScriptV1? = null,
            preparedCanonicalGeometry: RenderGeometryV1? = null,
            captureEnabled: Boolean = false,
            capturePlayerEpoch: Long? = null,
            decode: (side: LoopMix.Side, from: Double, to: Double) -> LoopRegion?,
        ): LoopMixSession? {

            fun refuse(reason: String): LoopMixSession? {
                onRefusal?.invoke(reason)
                return null
            }
            if (sampleRate <= 0) return refuse("invalid_sample_rate")
            val crown = plan.outLoops && plan.inLoops
            // Preserve the early capability gate for non-crown routes. Crowns
            // qualify first, even without JNI, so a geometry refusal is not
            // misattributed to renderer capability (and never reaches decode).
            if (!crown && !LoopMixRenderer.available) return refuse("renderer_unavailable")
            // The script hold is the truth for the handover: pass it into the clock so
            // clock.handoverTrackSec, the IN knots and setFadeOut share ONE timestamp.
            val naturalHandoff = plan.handoff != null && plan.alternativeKind == "natural-end" &&
                !plan.echoTail && script == null
            val contractEcho = naturalHandoff || (plan.handoff != null && plan.echoTail &&
                ((plan.rung == "Echo" && script == null) || script?.choice?.template == MixTemplate.M6))
            val handoff = plan.handoff.takeIf { contractEcho }
            if (handoff != null && (!plan.incomingHoldSec.isFinite() || abs(plan.incomingHoldSec - handoff.inHoldMixSec) > 1e-8 ||
                    (script != null && (!script.incomingHoldSec.isFinite() ||
                        abs(script.incomingHoldSec - handoff.inHoldMixSec) > 1e-8)))) {
                return refuse("handoff_hold_mismatch")
            }
            val effectiveHoldSec = handoff?.inHoldMixSec ?: if (canonicalPlan != null) {
                canonicalPlan.recipeParams["incomingHoldSec"] ?: plan.incomingHoldSec
            } else script?.incomingHoldSec ?: plan.incomingHoldSec
            val effectivePlan = if (effectiveHoldSec != plan.incomingHoldSec || (plan.handoff != null && !contractEcho)) {
                plan.copy(incomingHoldSec = effectiveHoldSec, handoff = handoff)
            } else plan
            if (crown) {
                val nominal = LoopMix.qualifyCrown(effectivePlan)
                if (!nominal.accepted) return refuse("crown_nominal_${nominal.refusal ?: "invalid"}")
            }
            val clock = LoopMix.Clock(effectivePlan)
            if (handoff != null) {
                val qualification = qualifyMusicalHandoff(handoff, outAnalysis, inAnalysis, sungOut, sungIn)
                Log.i(TAG, "handoff status=${qualification.status} reason=${qualification.reason} natural=$naturalHandoff outCoverage=${qualification.outCutMaskCoverage} inCoverage=${qualification.inEntryMaskCoverage} overlapCoverage=${qualification.overlapMaskCoverage}")
                if (qualification.status == HandoffStatus.UNSAFE) {
                    return refuse("handoff_${qualification.reason}")
                }
                if (qualification.status == HandoffStatus.UNKNOWN && !naturalHandoff) {
                    return refuse("handoff_unmeasured_capture")
                }
                if (naturalHandoff && (handoff.outDryFallStart != handoff.outDryEnd ||
                        abs(handoff.wetRetireMixSec - handoff.outDryEnd.mixSec) > 1e-8 || plan.reverbWash ||
                        handoff.inCueSourceSec != handoff.inAudibleStart.sourceSec ||
                        handoff.inAudibleStart.mixSec < handoff.outDryEnd.mixSec - 1e-8 ||
                        abs(handoff.inFullLevelMixSec - handoff.inAudibleStart.mixSec) > 1e-8)) {
                    return refuse("handoff_natural_geometry")
                }
                if (effectivePlan.outLoops || effectivePlan.inLoops || !effectivePlan.nativeIncoming ||
                    abs(effectivePlan.inn.loopStart - handoff.inCueSourceSec) > 1e-8 ||
                    abs(effectivePlan.out.loopStart - (handoff.outDryEnd.sourceSec - handoff.outDryEnd.mixSec)) > 1e-8 ||
                    handoff.inLanding.mixSec > clock.releaseAt + 1e-8 ||
                    handoff.outDryEnd.mixSec > clock.releaseAt + 1e-8) {
                    return refuse("handoff_plan_geometry")
                }
            }
            // Contract request starts are clamped to physical source zero by
            // regionBounds, once for both parallel and fallback decode and
            // identically for the controller's cache-ready query.
            // Nominal bounds shared with fast-ready checks. Actual tracked
            // knots below may extend the physical decode request before build.
            val bounds = LoopMix.regionBounds(effectivePlan)
            val echoHoldEarly = if (effectivePlan.echoTail) effectivePlan.out.beatSec * 8 else 0.0
            val outStopMixSec = handoff?.wetRetireMixSec
                ?: (clock.releaseAt + 0.05 + echoHoldEarly + effectivePlan.exitTailSeconds)
            if (handoff != null && handoff.wetRetireMixSec > clock.releaseAt + 1e-8) {
                return refuse("handoff_render_budget")
            }
            val tailRateEarly = if (clock.beats >= 1) {
                val b0 = clock.beatTimes[clock.beats - 1]
                val b1 = clock.beatTimes[clock.beats]
                (effectivePlan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
            } else 1.0
            val outTo = bounds.outTo
            val inTail = bounds.inTo - clock.elementTrackTime
            // Resolve actual tracked knots before decoding. A local grid can
            // extend beyond nominal Clock bounds; decode that physical passage
            // once, then reject an incomplete decoder result below.
            val outHasTailKnot = !effectivePlan.outLoops && outStopMixSec > clock.releaseAt + 1e-6
            val knots = buildDeckKnots(effectivePlan, clock, sampleRate, outAnalysis, inAnalysis,
                outHasTailKnot, outStopMixSec, tailRateEarly, straightRateMode, djLevel)
            if (handoff != null) {
                qualifyHandoffKnots(effectivePlan, clock, sampleRate, knots)?.let { return refuse(it) }
            }
            if (crown) {
                val actual = qualifyCrownKnots(effectivePlan, clock, sampleRate, knots)
                if (!actual.accepted) return refuse(requireNotNull(actual.refusal))
                if (!LoopMixRenderer.available) return refuse("renderer_unavailable")
            }
            val incomingGeometry = incomingGeometry(effectivePlan, clock, knots)
            val actualInTo = maxOf(bounds.inTo, incomingGeometry.handoverTrack + inTail,
                incomingGeometry.requiredEnd)
            // Preserve straight OUT's existing real-EOF/effect-drain contract.
            // Only repeated OUT audio needs a complete loop body and seam.
            val requiredOutEnd = if (effectivePlan.outLoops) effectivePlan.out.loopEnd + 0.008 else null
            val actualOutTo = if (straightRateMode != null) knots.outPositionsSec.maxOrNull()!! + 0.5 else outTo
            val reusableOut = preparedOutRegion?.takeIf { region ->
                val regionEnd = region.start + region.left.size.toDouble() / sampleRate
                val outDur = outAnalysis?.duration?.takeIf { it.isFinite() && it > 1.0 }
                region.start <= maxOf(0.0, bounds.outFrom) + 0.02 &&
                    coversOutgoingStartAndLoop(region, sampleRate, effectivePlan) &&
                    (regionEnd >= actualOutTo - 0.05 || (outDur != null && regionEnd >= outDur - 0.15))
            }
            val reusableIn = preparedInRegion?.takeIf { region ->
                region.start <= maxOf(0.0, bounds.inFrom) + 0.02 &&
                    region.start <= effectivePlan.inn.loopStart &&
                    coversPhysical(region, sampleRate, effectivePlan.inn.loopStart, maxOf(incomingGeometry.requiredEnd, actualInTo - 0.05))
            }
            if (preparedOnly) {
                preparedCoverageRefusal(effectivePlan, sampleRate, preparedOutRegion, preparedInRegion,
                    outAnalysis, inAnalysis, straightRateMode, djLevel)?.let { return refuse(it) }
                if (reusableOut == null || reusableIn == null) return refuse("prepared_pcm_coverage_mismatch")
            }
            val (outDecoded, inDecoded) = when {
                reusableOut != null && reusableIn != null -> reusableOut to reusableIn
                reusableOut != null -> reusableOut to decode(LoopMix.Side.IN, bounds.inFrom, actualInTo)
                reusableIn != null -> decode(LoopMix.Side.OUT, bounds.outFrom, actualOutTo) to reusableIn
                else -> try {
                    val outFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                        { decode(LoopMix.Side.OUT, bounds.outFrom, actualOutTo) },
                        DECODE_EXECUTOR,
                    )
                    val inFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                        { decode(LoopMix.Side.IN, bounds.inFrom, actualInTo) },
                        DECODE_EXECUTOR,
                    )
                    outFuture.get() to inFuture.get()
                } catch (e: Exception) {
                    decode(LoopMix.Side.OUT, bounds.outFrom, actualOutTo) to decode(LoopMix.Side.IN, bounds.inFrom, actualInTo)
                }
            }
            var outRegion = outDecoded ?: return refuse("decode_out_unavailable")
            var inRegion = inDecoded ?: return refuse("decode_in_unavailable")
            if (outRegion.start > plan.out.loopStart || inRegion.start > plan.inn.loopStart) {
                Log.w(TAG, "a region starts after its loop: out ${outRegion.start}, in ${inRegion.start}")
                return refuse("pcm_starts_after_loop")
            }
            if (!coversOutgoingStartAndLoop(outRegion, sampleRate, effectivePlan) ||
                !coversPhysical(inRegion, sampleRate, effectivePlan.inn.loopStart, incomingGeometry.requiredEnd)
            ) {
                Log.i(TAG, "loop-domain rejected: incomplete physical PCM out=${outRegion.left.size}/${outRegion.right.size} in=${inRegion.left.size}/${inRegion.right.size} requiredOut=$requiredOutEnd requiredIn=${incomingGeometry.requiredEnd}")
                return refuse("pcm_incomplete_physical_coverage")
            }
            if (handoff != null && !coversHandoffPcm(effectivePlan, sampleRate, knots, outRegion, inRegion)) {
                return refuse("handoff_pcm_before_protected_event")
            }
            Log.i(TAG, "loop-domain outId=$outId inId=$inId rung=${effectivePlan.rung} script=${script != null} outLoops=${effectivePlan.outLoops} inLoops=${incomingGeometry.loops} loopStart=${effectivePlan.inn.loopStart} loopEnd=${effectivePlan.inn.loopEnd} releaseUnwrapped=${incomingGeometry.releaseTrack} lastUnwrapped=${knots.inPositionsSec.last()} handoverPhysical=${incomingGeometry.handoverTrack} requestedInFrom=${bounds.inFrom} requestedInTo=$actualInTo decodedInFrom=${inRegion.start} decodedInTo=${inRegion.start + inRegion.left.size.toDouble() / sampleRate} requiredInTo=${incomingGeometry.requiredEnd} requestedOutFrom=${bounds.outFrom} requestedOutTo=$actualOutTo decodedOutFrom=${outRegion.start} decodedOutTo=${outRegion.start + outRegion.left.size.toDouble() / sampleRate} requiredOutTo=$requiredOutEnd")
            // Pass 2: pure CPU choreography on pre-separated stems and real knots, or isolator EQ without stems.
            // Null keeps legacy envelopes (no synchronous ONNX inference at build time).
            val allowStems = !useNoStemVariant && canonicalPlan == null
            val v2 = if (v2Prep != null && allowStems) {
                ChoreoSessionGlue.choreograph(
                    v2Prep, effectivePlan, clock, sampleRate, outRegion, inRegion,
                    outAnalysis, inAnalysis, sungOut, sungIn,
                    preparedOutStems = preparedOutStems,
                    preparedInStems = preparedInStems,
                    preparedScript = preparedScript,
                )
            } else null
            var v2Script: ChoreoScript? = when {
                canonicalPlan != null -> null
                v2Prep == null -> null
                !allowStems -> {
                    val reusableNoStem = preparedScript?.takeIf {
                        !it.outUsesStems && !it.inUsesStems &&
                            it.level == v2Prep.level &&
                            RecipeId.fromCode(it.recipeId) !in v2Prep.excludedRecipes
                    }
                    reusableNoStem ?: ChoreoSessionGlue.withoutStems(
                        v2Prep, effectivePlan, clock, sampleRate, outAnalysis, inAnalysis,
                        sungOut, sungIn,
                        plannedRecipe = RecipeId.fromCode(preparedScript?.recipeId) ?: v2Prep.forcedRecipe,
                    )
                }
                else -> v2?.script
            }
            if (preparedOnly && canonicalPlan == null && v2Script == null) return refuse("choreography_unavailable")
            val level = LoopQualification.regionalIncomingLevel(plan, outRegion, inRegion, sampleRate, inLevel)
            // R2 canonical path: compile the detached registry recipe against the
            // exact regions/knots that will be installed. Legacy choreography is
            // not allowed to repair or replace a frozen canonical script.
            var canonical = if (canonicalPlan != null) {
                val frozenRecipe = canonicalRecipe ?: return refuse("canonical_frozen_recipe_missing")
                if (frozenRecipe.id != canonicalPlan.recipeId) return refuse("canonical_frozen_recipe_id_mismatch")
                val compiled = when (val outcome = ChoreoScriptBuilder.compileCanonical(
                    planV1 = canonicalPlan,
                    frozenRecipe = frozenRecipe,
                    effectivePlan = effectivePlan,
                    outRegion = outRegion,
                    inRegion = inRegion,
                    outStems = preparedOutStems,
                    inStems = preparedInStems,
                    sampleRate = sampleRate,
                    caps = ChoreoRendererBridge.capabilities(),
                    outAnalysis = outAnalysis,
                    inAnalysis = inAnalysis,
                    straightRateMode = straightRateMode,
                    level = djLevel,
                    highQuality = highQuality,
                    inLevel = level,
                    useNoStemVariant = useNoStemVariant,
                    preparedScript = preparedCanonicalScript,
                    preparedGeometry = preparedCanonicalGeometry,
                )) {
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Ok -> outcome.value
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Refused ->
                        return refuse("canonical_${outcome.detail}")
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Failed ->
                        return refuse("canonical_${outcome.message}")
                }
                when (val guarded = ScriptExecutionGuard.validateFrozen(
                    compiled.script, canonicalPlan, FxCapability.fromMask(ChoreoRendererBridge.capabilities()),
                )) {
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Ok -> compiled.copy(script = guarded.value)
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Refused ->
                        return refuse("canonical_guard_${guarded.detail}")
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Failed ->
                        return refuse("canonical_guard_${guarded.message}")
                }
            } else null
            var canonicalNoStemBackup: TransitionScriptV1? = null
            var canonicalNoStemBackupRefusal: String? = null
            if (canonical != null && canonical.script.stems.isNotEmpty() && canonicalPlan?.hasNoStemVariant == true) {
                when (val noStem = ChoreoSessionGlue.compileSameRecipeNoStem(
                    recipe = requireNotNull(canonicalRecipe),
                    plan = canonicalPlan,
                    geometry = canonical.geometry,
                    canonical = canonical.script,
                    availableCapabilities = FxCapability.fromMask(ChoreoRendererBridge.capabilities()),
                )) {
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Ok -> canonicalNoStemBackup = noStem.value
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Refused -> canonicalNoStemBackupRefusal = noStem.detail
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Failed -> canonicalNoStemBackupRefusal = noStem.message
                }
            }
            val renderer = LoopMixRenderer(sampleRate)
            var rendererOwnershipTransferred = false
            var canonicalNoStemFallback = false
            try {
            if (!renderer.isValid) return refuse("renderer_build_failed")
            val sr = sampleRate.toDouble()
            fun samplesOf(region: LoopRegion, trackSeconds: Double) = (trackSeconds - region.start) * sr
            fun installEnvelope(deck: Int, kind: Int, times: DoubleArray, values: FloatArray,
                exponential: Boolean = false) {
                renderer.setEnvelope(deck, kind, times, values, exponential)
                onEnvelopeInstalled?.invoke(deck, kind, times.copyOf(), values.copyOf())
            }

            val echoHold = echoHoldEarly
            val outStopAtSec = outStopMixSec
            val outKnotFrames = knots.outFrames
            val outKnotPositions = DoubleArray(knots.outPositionsSec.size) {
                samplesOf(outRegion, knots.outPositionsSec[it])
            }
            val outLoopEnd = if (effectivePlan.outLoops) effectivePlan.out.loopEnd else effectivePlan.out.loopStart
            renderer.setDeck(
                LoopMixRenderer.OUT,
                LoopMixRenderer.DeckSetup(
                    left = outRegion.left,
                    right = outRegion.right,
                    regionStart = outRegion.start,
                    loopStart = canonical?.geometry?.out?.loopStartFrame ?: samplesOf(outRegion, effectivePlan.out.loopStart).roundToLong(),
                    loopEnd = canonical?.geometry?.out?.loopEndFrame ?: samplesOf(outRegion, outLoopEnd).roundToLong(),
                    releaseAt = canonical?.geometry?.out?.releaseAtFrame ?: 1e18,
                    knotFrames = canonical?.geometry?.out?.knotFrames?.toDoubleArray() ?: outKnotFrames,
                    knotPositions = canonical?.geometry?.out?.knotPositions?.toDoubleArray() ?: outKnotPositions,
                    runOn = canonical?.geometry?.out?.runOn ?: false,
                    stopAt = canonical?.geometry?.out?.stopAtMixSec ?: (handoff?.wetRetireMixSec ?: (clock.releaseAt + 0.05 + echoHold + effectivePlan.exitTailSeconds)),
                    preroll = canonical?.geometry?.out?.prerollSec ?: 0.4,
                    level = canonical?.geometry?.out?.levelGain ?: 1f,
                ),
            )
            val inKnotFrames = knots.inFrames
            val inKnotPositions = DoubleArray(knots.inPositionsSec.size) {
                samplesOf(inRegion, knots.inPositionsSec[it])
            }
            val inLoopEnd = if (incomingGeometry.loops) effectivePlan.inn.loopEnd else effectivePlan.inn.loopStart
            val inReleaseTrackSec = incomingGeometry.releaseTrack
            val handoverTrackSec = incomingGeometry.handoverTrack
            renderer.setDeck(
                LoopMixRenderer.IN,
                LoopMixRenderer.DeckSetup(
                    left = inRegion.left,
                    right = inRegion.right,
                    regionStart = inRegion.start,
                    loopStart = canonical?.geometry?.inbound?.loopStartFrame ?: samplesOf(inRegion, effectivePlan.inn.loopStart).roundToLong(),
                    loopEnd = canonical?.geometry?.inbound?.loopEndFrame ?: samplesOf(inRegion, inLoopEnd).roundToLong(),
                    releaseAt = canonical?.let { it.geometry.inbound.releaseAtFrame ?: 1e18 }
                        ?: samplesOf(inRegion, inReleaseTrackSec),
                    knotFrames = canonical?.geometry?.inbound?.knotFrames?.toDoubleArray() ?: inKnotFrames,
                    knotPositions = canonical?.geometry?.inbound?.knotPositions?.toDoubleArray() ?: inKnotPositions,
                    runOn = canonical?.geometry?.inbound?.runOn ?: true,
                    stopAt = canonical?.geometry?.inbound?.stopAtMixSec ?: (clock.elementAt + 2.0),
                    preroll = canonical?.geometry?.inbound?.prerollSec ?: 0.4,
                    level = canonical?.geometry?.inbound?.levelGain ?: level,
                ),
            )

            // Attach stems on the decks the script relies on (after setDeck, before prepare).
            // If useNoStemVariant or a deck refuses a StemSet, re-choose isolator EQ without stems
            // while preserving DJ level and frozen transition geometry.
            var v2OutStems = false
            var v2InStems = false
            val v2Chosen = v2Script
            if (canonical != null) {
                val needsOut = canonical.script.stems.any { it.deck == CoreDeckTarget.OUT }
                val needsIn = canonical.script.stems.any { it.deck == CoreDeckTarget.IN }
                if (needsOut) v2OutStems = ChoreoRendererBridge.attachStems(renderer, LoopMixRenderer.OUT, preparedOutStems)
                if (needsIn) v2InStems = ChoreoRendererBridge.attachStems(renderer, LoopMixRenderer.IN, preparedInStems)
                if ((needsOut && !v2OutStems) || (needsIn && !v2InStems)) {
                    if (v2OutStems) ChoreoRendererBridge.detachStems(renderer, LoopMixRenderer.OUT)
                    if (v2InStems) ChoreoRendererBridge.detachStems(renderer, LoopMixRenderer.IN)
                    v2OutStems = false
                    v2InStems = false
                    val noStemScript = canonicalNoStemBackup
                        ?: return refuse("canonical_no_stem_${canonicalNoStemBackupRefusal ?: "variant_unavailable"}")
                    canonical = canonical.copy(script = noStemScript)
                    canonicalNoStemFallback = true
                }
            } else if (allowStems && v2Chosen != null && v2 != null && v2Prep != null) {
                if (v2Chosen.outUsesStems) v2OutStems = ChoreoRendererBridge.attachStems(renderer, LoopMixRenderer.OUT, v2.outStems)
                if (v2Chosen.inUsesStems) v2InStems = ChoreoRendererBridge.attachStems(renderer, LoopMixRenderer.IN, v2.inStems)
                if ((v2Chosen.outUsesStems && !v2OutStems) || (v2Chosen.inUsesStems && !v2InStems)) {
                    if (v2OutStems) ChoreoRendererBridge.detachStems(renderer, LoopMixRenderer.OUT)
                    if (v2InStems) ChoreoRendererBridge.detachStems(renderer, LoopMixRenderer.IN)
                    v2OutStems = false
                    v2InStems = false
                    v2Script = ChoreoSessionGlue.withoutStems(
                        v2Prep, effectivePlan, clock, sampleRate, outAnalysis, inAnalysis, sungOut, sungIn,
                        plannedRecipe = RecipeId.fromCode(v2Chosen.recipeId),
                    )
                }
            }
            if (preparedOnly && canonical == null && v2Script == null) {
                return refuse("no_stem_recipe_unavailable")
            }
            val v2Final = v2Script
            if (canonical != null) {
                val canonicalCaps = ChoreoRendererBridge.capabilities()
                when (val guarded = ScriptExecutionGuard.validateFrozen(
                    canonical.script,
                    canonicalPlan!!,
                    FxCapability.fromMask(canonicalCaps),
                )) {
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Ok -> Unit
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Refused ->
                        return refuse("canonical_guard_${guarded.detail}")
                    is com.music.bitchord.djmix.core.contracts.MixOutcome.Failed ->
                        return refuse("canonical_guard_${guarded.message}")
                }
                ChoreoRendererBridge.apply(renderer, canonical.script, canonicalCaps, v2OutStems, v2InStems)
            } else {
            val nativeEchoOut = contractEcho || (effectivePlan.echoTail && effectivePlan.rung == "Echo" && script == null)
            val contractM6 = handoff != null && script?.choice?.template == MixTemplate.M6
            if (nativeEchoOut) {
                val outPcmEndSec = if (effectivePlan.outLoops) Double.POSITIVE_INFINITY else {
                    straightOutPcmEndSec(outKnotFrames, outKnotPositions, outRegion.left.size, sr)
                }
                val echoEnv = computeEchoOutEnvelopes(effectivePlan, clock, outStopAtSec, outPcmEndSec, sampleRate)
                if (!naturalHandoff) renderer.setEcho(LoopMixRenderer.OUT, echoEnv.delaySec, echoEnv.feedback, 0.55f)
                if (!contractM6) installEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND,
                    echoEnv.echoSendTimes, echoEnv.echoSendValues,
                )
                if (!naturalHandoff) renderer.setReverb(
                    LoopMixRenderer.OUT,
                    roomSize = 2.2f, damping = PhraseConfig.REVERB_DAMPING, wet = PhraseConfig.REVERB_WET,
                )
                installEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_REVERB_SEND,
                    echoEnv.reverbSendTimes, echoEnv.reverbSendValues,
                )
                if (!contractM6) {
                    installEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                        echoEnv.outGainTimes, echoEnv.outGainValues)
                    installEnvelope(LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                        echoEnv.inGainTimes, echoEnv.inGainValues)
                    installEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB,
                        echoEnv.outLowTimes, echoEnv.outLowValues)
                    installEnvelope(LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB,
                        echoEnv.inLowTimes, echoEnv.inLowValues)
                    installEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                        echoEnv.outHpTimes, echoEnv.outHpValues, exponential = true)
                }
                installEnvelope(LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_PASS_HZ,
                    echoEnv.inLpTimes, echoEnv.inLpValues, exponential = true)
            } else {
                if (effectivePlan.echoTail) {
                    val outBpmForEcho = if (effectivePlan.out.bpm > 0) effectivePlan.out.bpm else 120.0
                    val delayBeats = if (outBpmForEcho > PhraseConfig.ECHO_BPM_SPLIT) {
                        PhraseConfig.ECHO_DELAY_BEATS_FAST
                    } else {
                        PhraseConfig.ECHO_DELAY_BEATS
                    }
                    val delay = script?.echoDelaySec?.takeIf { it > 0.01 } ?: (effectivePlan.out.beatSec * delayBeats)
                    val feedback = script?.echoFeedback?.takeIf { it > 0f } ?: PhraseConfig.ECHO_FEEDBACK
                    renderer.setEcho(LoopMixRenderer.OUT, delay, feedback, 0.55f)
                    val open = (clock.releaseAt - effectivePlan.out.beatSec).coerceAtLeast(0.0)
                    // Taper to explicit 0.0 by release+2 beats so the native
                    // echoWetRetireStart fires musically (positive-to-zero
                    // release) instead of falling back to stopAt-1s.
                    val echoBeatSec = effectivePlan.out.beatSec.coerceAtLeast(0.1)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND,
                        doubleArrayOf(0.0, open, clock.releaseAt, clock.releaseAt + 2.0 * echoBeatSec),
                        floatArrayOf(0f, 0f, 0.6f, 0f),
                    )
                }
                val wantFlanger = (script?.flanger == true) || effectivePlan.flanger
                if (wantFlanger) {
                    val beatSec = effectivePlan.out.beatSec.coerceAtLeast(0.1)
                    val lfoHz = LoopMix.lfoHzForBeatSec(beatSec)
                    val isBuildup = effectivePlan.buildupRoll
                    val depth = if (isBuildup) LoopMix.FLANGER_DEPTH_BUILDUP_SEC else LoopMix.FLANGER_DEPTH_COLOUR_SEC
                    renderer.setFlanger(
                        LoopMixRenderer.OUT,
                        lfoHz = lfoHz,
                        depthSeconds = depth,
                        feedback = LoopMix.FLANGER_FEEDBACK,
                        wet = LoopMix.FLANGER_WET,
                    )
                    val rampEnd = clock.releaseAt
                    val rampStart = (rampEnd - 8.0 * beatSec).coerceAtLeast(clock.enterEnd)
                    val tail = rampEnd + minOf(beatSec * 2.0, 0.8)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_FLANGER_WET,
                        doubleArrayOf(rampStart, rampEnd, tail),
                        floatArrayOf(0.15f, 1.0f, 0.0f),
                    )
                }
                if (effectivePlan.buildupRoll) {
                    val beatSec = effectivePlan.out.beatSec.coerceAtLeast(0.1)
                    val totalBeats = clock.beats.toDouble()
                    val rollBeats = if (totalBeats >= 16.0) 16.0 else 8.0
                    val rollStartSec = (clock.releaseAt - rollBeats * beatSec).coerceAtLeast(0.0)
                    renderer.setLoopRoll(
                        LoopMixRenderer.OUT,
                        enabled = true,
                        rollStartSec = rollStartSec,
                        beatSec = beatSec,
                        releaseSec = clock.releaseAt,
                        totalRollBeats = rollBeats,
                    )
                    val hpEnd = effectivePlan.hpSweepEndHz.takeIf { it > 0f } ?: LoopMix.BUILDUP_HP_END_HZ
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                        doubleArrayOf(rollStartSec, clock.releaseAt),
                        floatArrayOf(30f, hpEnd),
                        exponential = true,
                    )
                    renderer.setSvfQ(LoopMixRenderer.OUT, LoopMix.BUILDUP_HP_Q)
                }
                if (effectivePlan.reverbWash) {
                    val reverbDamping = if (effectivePlan.buildupRoll) PhraseConfig.REVERB_DAMPING_EDM else PhraseConfig.REVERB_DAMPING
                    val reverbWet = if (effectivePlan.buildupRoll) PhraseConfig.REVERB_WET_EDM else PhraseConfig.REVERB_WET
                    renderer.setReverb(
                        LoopMixRenderer.OUT,
                        roomSize = 2.2f, damping = reverbDamping, wet = reverbWet,
                    )
                    val open = (clock.releaseAt - effectivePlan.out.beatSec).coerceAtLeast(0.0)
                    // vc77: taper the send back to zero by outStopMixSec so
                    // the wash never hard-cuts at stopAt.
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_REVERB_SEND,
                        doubleArrayOf(open, clock.releaseAt, outStopMixSec),
                        floatArrayOf(0f, 0.65f, 0f),
                    )
                }

                val h = LoopMix.HANDOVER_SEC
                val mixDe = clock.rampEnd - clock.enterEnd
                if (mixDe > 1e-6) {
                    val outFall = MixGain.fallPoints(clock.rampEnd, clock.releaseAt)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                        doubleArrayOf(-h, 0.0, clock.enterEnd, *outFall.first.sliceArray(1 until outFall.first.size)),
                        floatArrayOf(0f, 1f, 1f, *outFall.second.sliceArray(1 until outFall.second.size)),
                    )
                    val inRise = MixGain.risePoints(0.0, clock.rampEnd)
                    renderer.setEnvelope(
                        LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                        doubleArrayOf(*inRise.first, clock.releaseAt),
                        floatArrayOf(*inRise.second, 1f),
                    )
                } else {
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                        doubleArrayOf(-h, 0.0, clock.enterEnd, clock.rampEnd, clock.releaseAt),
                        floatArrayOf(0f, 1f, 1f, 0.6f, 0f),
                    )
                    renderer.setEnvelope(
                        LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                        doubleArrayOf(0.0, clock.enterEnd, clock.rampEnd),
                        floatArrayOf(0f, 0.55f, 1f),
                    )
                }
                val swapFrom = clock.handoffAt - clock.swapBar / 2
                val swapTo = clock.handoffAt + clock.swapBar / 2
                val cut = LoopMix.BASS_CUT_DB.toFloat()
                renderer.setEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB,
                    doubleArrayOf(swapFrom, swapTo), floatArrayOf(0f, cut),
                )
                renderer.setEnvelope(
                    LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB,
                    doubleArrayOf(swapFrom, swapTo), floatArrayOf(cut, 0f),
                )
                if (effectivePlan.muffle) {
                    // Muffle DYING OUT: monotonic C1 raised-cosine close
                    // open->floor (700 Hz) over the whole span, ending at the
                    // floor at releaseAt while the OUT fader falls to zero.
                    // No 20 kHz reopen right before death (no brightness
                    // blast). Breakpoints come from the family registry
                    // builder sampled on the beat grid, strictly increasing.
                    val (times, vals) = TransitionAlternatives.muffleEnvelope(clock)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                        times, vals,
                        exponential = true,
                    )
                }
                if (effectivePlan.beatlessBridge) {
                    // Beatless bridge: dying OUT closes monotonically to park
                    // (no 20 kHz reopen); survivor IN parks then reopens
                    // park->open over the proportional C1 return leg so it
                    // is full-band at releaseAt.
                    val (outTimes, outVals) = TransitionAlternatives.bridgeDeadOutEnvelope(clock)
                    val (inTimes, inVals) = TransitionAlternatives.bridgeEnvelope(clock)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                        outTimes, outVals,
                        exponential = true,
                    )
                    renderer.setEnvelope(
                        LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_PASS_HZ,
                        inTimes, inVals,
                        exponential = true,
                    )
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_REVERB_SEND,
                        doubleArrayOf(0.0, clock.releaseAt),
                        floatArrayOf(0.3f, 0.6f),
                    )
                }
                if (effectivePlan.dropIn) {
                    // Drop-in explicit arm: the incoming deck enters
                    // unfiltered by definition (no low-pass/high-pass curve
                    // on either deck). Bass handoff stays on KIND_LOW_DB
                    // already programmed above; never fall into the generic
                    // high-pass branch below.
                }
                // Muffle/bridge OUT keeps the planned MixGain fallPoints
                // (rampEnd -> releaseAt) fall to zero: no unity-gain splice
                // override here (the old ReleaseSplice 1.0-until-releaseAt
                // holds the dying deck loud right before the handoff). The
                // ReleaseSplice class is kept for genuine survivor-stem
                // splices elsewhere; it is simply not used to hold a dying
                // OUT at unity.
                val beatBlendPocket = effectivePlan.rung == "Beat blend"
                when {
                    effectivePlan.dropIn -> {
                        // Drop-in explicit arm (see also the dropIn envelope
                        // block above): no low-pass/high-pass curve on either
                        // deck. The incoming enters unfiltered; bass changes
                        // hands on KIND_LOW_DB. This branch is intentional,
                        // not a fallthrough into the generic high-pass.
                    }
                    effectivePlan.carve -> {
                        renderer.setEnvelope(
                            LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                            doubleArrayOf(clock.enterEnd, clock.rampEnd),
                            floatArrayOf(20000f, 5500f),
                            exponential = true,
                        )
                        renderer.setEnvelope(
                            LoopMixRenderer.IN, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                            doubleArrayOf(0.0, clock.rampEnd, clock.releaseAt),
                            floatArrayOf(220f, 220f, 20f),
                            exponential = true,
                        )
                        if (effectivePlan.clash && !effectivePlan.buildupRoll) {
                            renderer.setEnvelope(
                                LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                                doubleArrayOf(clock.rampEnd, clock.releaseAt),
                                floatArrayOf(LoopMix.HIGH_PASS_FROM_HZ.toFloat(), LoopMix.HIGH_PASS_TO_HZ.toFloat()),
                                exponential = true,
                            )
                        }
                    }
                    beatBlendPocket -> {
                        // vc73: no LP/HP muffle. KIND_LOW_DB bass swap + computeVocalMidCarve (KIND_MID_DB) keep it clear.
                    }
                    effectivePlan.clash && effectivePlan.outLoops -> {
                        renderer.setEnvelope(
                            LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                            doubleArrayOf(clock.handoffAt, clock.releaseAt),
                            floatArrayOf(20000f, 5000f),
                            exponential = true,
                        )
                        renderer.setEnvelope(
                            LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                            doubleArrayOf(clock.handoffAt, clock.releaseAt),
                            floatArrayOf(20f, 220f),
                            exponential = true,
                        )
                    }
                    else -> {
                        renderer.setEnvelope(
                            LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                            doubleArrayOf(clock.rampEnd, clock.releaseAt),
                            floatArrayOf(LoopMix.HIGH_PASS_FROM_HZ.toFloat(), LoopMix.HIGH_PASS_TO_HZ.toFloat()),
                            exponential = true,
                        )
                    }
                }
                if (script != null) {
                    applyPhraseLanes(renderer, clock, effectivePlan, script, contractM6, ::installEnvelope)
                    renderer.setIsolate(LoopMixRenderer.OUT, true)
                    renderer.setIsolate(LoopMixRenderer.IN, true)
                } else if (effectivePlan.carve || effectivePlan.clash || effectivePlan.rung == "Beat blend") {
                    renderer.setIsolate(LoopMixRenderer.OUT, true)
                    renderer.setIsolate(LoopMixRenderer.IN, true)
                }
                val midCarve = computeVocalMidCarve(effectivePlan, clock, script, inAnalysis, sungIn)
                if (midCarve.apply) {
                    renderer.setIsolate(LoopMixRenderer.OUT, true)
                    renderer.setIsolate(LoopMixRenderer.IN, true)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_MID_DB,
                        midCarve.outTimes, midCarve.outDb,
                    )
                    renderer.setEnvelope(
                        LoopMixRenderer.IN, LoopMixRenderer.KIND_MID_DB,
                        midCarve.inTimes, midCarve.inDb,
                    )
                }
            }
            // M6's explicit contract has a generic Echo effects setup above,
            // but the score-sheet lanes are the one authoritative set of
            // overlapping gain/send/filter envelopes. Install them after the
            // non-overlapping contract effect sends, before native prepare.
            if (nativeEchoOut && contractM6 && script != null) {
                applyPhraseLanes(renderer, clock, effectivePlan, script, contractM6, ::installEnvelope)
                renderer.setIsolate(LoopMixRenderer.OUT, true)
                renderer.setIsolate(LoopMixRenderer.IN, true)
            }
            // The choreographed script owns every envelope kind it writes (with or without stems).
            if (v2Final != null && v2Prep != null) {
                val applyCaps = if (useNoStemVariant) v2Prep.caps and ChoreoCaps.STEMS.inv() else v2Prep.caps
                ChoreoRendererBridge.apply(renderer, v2Final, applyCaps, v2OutStems, v2InStems)
            }
            }
            renderer.setHighQuality(canonical?.geometry?.highQuality ?: highQuality)
            if (captureEnabled) renderer.setCaptureEnabled(true)
            renderer.prepare()
            renderer.setFadeOut(
                LoopMixRenderer.IN,
                canonical?.geometry?.inbound?.fadeOutTrackSec ?: handoverTrackSec,
            )
            val session = LoopMixSession(effectivePlan, clock, outId, inId, renderer, sampleRate, handoverTrackSec,
                configuredOutLoops = effectivePlan.outLoops, configuredInLoops = incomingGeometry.loops,
                outGridSource = knots.outGridSource, inGridSource = knots.inGridSource,
                executionPath = when {
                    canonical != null -> "canonical"
                    v2Final == null -> "classic"
                    straightRateMode != null -> "v2-straight"
                    else -> "v2-native"
                },
                preparationGeneration = preparationGeneration,
                planId = planId,
                canonicalScript = canonical?.script,
                canonicalGeometry = canonical?.geometry,
                canonicalNoStemVariant = useNoStemVariant || canonicalNoStemFallback,
            ).also {
                it.captureConfigured = captureEnabled
                it.capturePlayerEpoch = capturePlayerEpoch
                it.planIncomingStart()
                // AUTOMIX-V2(E): what the choreographer did, for the status line / Mix Inspector / telemetry.
                if (canonical != null) {
                    it.choreoMove = canonical.recipe.family.name
                    it.choreoLabel = canonical.recipe.name
                    it.choreoRecipeId = canonical.recipe.id
                    it.choreoRecipeName = canonical.recipe.name
                    it.choreoFxNames = ChoreoScriptBuilder.canonicalFxNames(canonical.script)
                    it.choreoFxCount = it.choreoFxNames.size
                    it.choreoStemsUsed = canonical.script.stems.groupBy { stem -> stem.deck }
                        .values.maxOfOrNull { stems -> stems.size } ?: 0
                    it.choreoLevel = requireNotNull(canonicalPlan).djLevel.name
                    it.choreoTopCandidates = emptyList()
                } else if (v2Final != null) {
                    it.choreoMove = v2Final.move.name
                    it.choreoLabel = v2Final.label
                    it.choreoRecipeId = v2Final.recipeId
                    it.choreoRecipeName = v2Final.recipeName
                    it.choreoFxCount = v2Final.fxCount
                    it.choreoFxNames = (v2Final.outFx.activeFxNames + v2Final.inFx.activeFxNames).distinct()
                    val attachedOut = if (v2OutStems) v2?.outStems?.stemCount ?: 0 else 0
                    val attachedIn = if (v2InStems) v2?.inStems?.stemCount ?: 0 else 0
                    it.choreoStemsUsed = maxOf(attachedOut, attachedIn)
                    it.choreoLevel = v2Final.level.name
                    it.choreoTopCandidates = v2Final.topCandidates.map { cand ->
                        "${cand.recipeId.code} (${cand.recipeName}): ${((cand.total * 100.0).toLong() / 100.0)}"
                    }
                }
            }
            rendererOwnershipTransferred = true
            return session
            } finally {
                if (!rendererOwnershipTransferred) renderer.close()
            }
        }

        /**
         * Score-sheet lanes, in beats relative to the landing, written over the
         * generic blend. Gain is converted from dB. A value at or under −80 dB
         * is silence, which the renderer treats as a mute.
         */
        private fun applyPhraseLanes(
            renderer: LoopMixRenderer,
            clock: LoopMix.Clock,
            plan: LoopMix.Plan,
            script: com.music.bitchord.playback.smart.PhraseScript,
            contractM6: Boolean = false,
            install: (Int, Int, DoubleArray, FloatArray, Boolean) -> Unit =
                { deck, kind, times, values, exponential -> renderer.setEnvelope(deck, kind, times, values, exponential) },
        ) {
            val handoff = plan.handoff.takeIf { contractM6 }
            val landingBeat = handoff?.let {
                val (beat, fraction) = clock.beatAt(it.inLanding.mixSec)
                beat + fraction
            }
            fun clockBeatTime(beat: Double): Double {
                val times = clock.beatTimes
                if (beat <= 0.0) return beat * (times[1] - times[0])
                val last = times.lastIndex
                if (beat >= last) return times[last] + (beat - last) * (times[last] - times[last - 1])
                val k = beat.toInt()
                val fraction = beat - k
                return times[k] + (times[k + 1] - times[k]) * fraction
            }
            fun seconds(beatFromLanding: Double): Double {
                if (handoff != null) return clockBeatTime(requireNotNull(landingBeat) + beatFromLanding)
                val landing = (plan.rampBars + plan.exitBars).coerceAtLeast(1) * 4.0
                val beat = landing + beatFromLanding
                if (beat <= 0.0) return beat * plan.out.beatSec
                val last = clock.beatTimes.lastIndex
                val k = beat.toInt().coerceIn(0, (last - 1).coerceAtLeast(0))
                val frac = (beat - k).coerceIn(0.0, 1.0)
                val t0 = clock.beatTimes[k]
                val t1 = clock.beatTimes[(k + 1).coerceAtMost(last)]
                return t0 + (t1 - t0) * frac
            }
            fun db(value: Float): Float = if (value <= -80f) 0f else 10.0.pow(value / 20.0).toFloat()
            fun put(deck: Int, kind: Int, lane: com.music.bitchord.playback.smart.Lane, asDb: Boolean, exponential: Boolean = lane.exponential) {
                if (lane.beats.isEmpty()) return
                val values = FloatArray(lane.values.size) { if (asDb) db(lane.values[it]) else lane.values[it] }
                if (handoff == null) {
                    val times = DoubleArray(lane.beats.size) { seconds(lane.beats[it]) }
                    install(deck, kind, times, values, exponential)
                    return
                }
                val mappedBeats = lane.beats.copyOf()
                val points = mappedBeats.indices.map { seconds(mappedBeats[it]) to values[it] }.toMutableList()
                if (handoff != null && deck == LoopMixRenderer.OUT && kind == LoopMixRenderer.KIND_GAIN) {
                    // Keep the silent native preroll that the generic contract
                    // used to provide, then let the M6 lane own the actual cut.
                    points += (-LoopMix.HANDOVER_SEC to 0f)
                    points += (handoff.wetRetireMixSec to 0f)
                } else if (handoff != null && deck == LoopMixRenderer.IN && kind == LoopMixRenderer.KIND_GAIN) {
                    // M6 encodes its landing point first, then the earlier cue
                    // activation. Put events into renderer time order and make
                    // the landing full-level, as the contract requires.
                    points += (-LoopMix.HANDOVER_SEC to 0f)
                    points += (0.0 to 0f)
                    points += (handoff.inLanding.mixSec to 1f)
                }
                val ordered = points.sortedBy { it.first }.fold(mutableListOf<Pair<Double, Float>>()) { acc, point ->
                    if (acc.isNotEmpty() && abs(acc.last().first - point.first) <= 1e-12) {
                        acc[acc.lastIndex] = point
                    } else acc += point
                    acc
                }
                val times = DoubleArray(ordered.size) { ordered[it].first }
                val orderedValues = FloatArray(ordered.size) { ordered[it].second }
                install(deck, kind, times, orderedValues, exponential)
            }
            put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN, script.outGain, asDb = true)
            put(LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN, script.inGain, asDb = true)
            put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB, script.outLow, asDb = false)
            put(LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB, script.inLow, asDb = false)
            put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_MID_DB, script.outMid, asDb = false)
            put(LoopMixRenderer.IN, LoopMixRenderer.KIND_MID_DB, script.inMid, asDb = false)
            put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_DB, script.outHigh, asDb = false)
            put(LoopMixRenderer.IN, LoopMixRenderer.KIND_HIGH_DB, script.inHigh, asDb = false)
            put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ, script.outHp, asDb = false, exponential = true)
            put(LoopMixRenderer.IN, LoopMixRenderer.KIND_HIGH_PASS_HZ, script.inHp, asDb = false, exponential = true)
            if (plan.echoTail) put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND, script.echoSend, asDb = false)
        }

        /** Interpolate the exact arrays supplied to native, in source seconds. */
        internal fun knotSourceAt(frames: DoubleArray, positions: DoubleArray, mixSec: Double, sampleRate: Int): Double? =
            LoopQualification.knotSourceAt(frames, positions, mixSec, sampleRate)

        /** A contract is checked against emitted coordinates before any decoder or JNI work. */
        internal fun qualifyHandoffKnots(plan: LoopMix.Plan, clock: LoopMix.Clock, sampleRate: Int, knots: DeckKnots): String? =
            LoopQualification.qualifyHandoffKnots(plan, clock, sampleRate, knots)

        internal fun coversHandoffPcm(plan: LoopMix.Plan, sampleRate: Int, knots: DeckKnots,
            out: LoopRegion, inn: LoopRegion): Boolean =
            LoopQualification.coversHandoffPcm(plan, sampleRate, knots, out, inn)

        /** Qualify buildDeckKnots output before PCM/JNI; delegates to [LoopQualification]. */
        internal fun qualifyCrownKnots(
            plan: LoopMix.Plan,
            clock: LoopMix.Clock,
            sampleRate: Int,
            knots: DeckKnots,
        ): CrownKnotQualification = LoopQualification.qualifyCrownKnots(plan, clock, sampleRate, knots)

        internal fun trackedBeatsInWindow(
            sideBeats: List<Double>?,
            anchorStart: Double,
            count: Int,
            beatSec: Double,
        ): DoubleArray? = LoopQualification.trackedBeatsInWindow(sideBeats, anchorStart, count, beatSec)

        /** Coordinates here remain track seconds; native subtracts region.start. */
        internal fun incomingGeometry(plan: LoopMix.Plan, clock: LoopMix.Clock, knots: DeckKnots): IncomingGeometry =
            LoopQualification.incomingGeometry(plan, clock, knots)

        internal fun coversPhysical(region: LoopRegion, sampleRate: Int, requiredStart: Double, requiredEnd: Double): Boolean =
            LoopQualification.coversPhysical(region, sampleRate, requiredStart, requiredEnd)

        /** OUT loop completeness only; straight OUT may reach real EOF during FX drain. */
        internal fun coversOutgoingStartAndLoop(region: LoopRegion, sampleRate: Int, plan: LoopMix.Plan): Boolean =
            LoopQualification.coversOutgoingStartAndLoop(region, sampleRate, plan)

        /** Exact build request, including actual tracked/straight knots and handover tail. */
        internal fun decodeRequirements(
            plan: LoopMix.Plan, sampleRate: Int, outAnalysis: TrackAnalysis?, inAnalysis: TrackAnalysis?,
            straightRateMode: StraightRateMode? = null, level: DjLevel = AutomixV2Choreo.level,
        ): LoopMix.RegionBounds =
            LoopQualification.decodeRequirements(plan, sampleRate, outAnalysis, inAnalysis, straightRateMode, level)

        /** PREPARED means complete, usable PCM on the same geometry/rate contract as build. */
        internal fun preparedCoverageRefusal(
            plan: LoopMix.Plan, sampleRate: Int, out: LoopRegion?, inn: LoopRegion?,
            outAnalysis: TrackAnalysis?, inAnalysis: TrackAnalysis?, straightRateMode: StraightRateMode? = null,
            level: DjLevel = AutomixV2Choreo.level,
        ): String? =
            LoopQualification.preparedCoverageRefusal(plan, sampleRate, out, inn, outAnalysis, inAnalysis, straightRateMode, level)

        internal fun buildDeckKnots(
            plan: LoopMix.Plan,
            clock: LoopMix.Clock,
            sampleRate: Int,
            outAnalysis: TrackAnalysis?,
            inAnalysis: TrackAnalysis?,
            outHasTailKnot: Boolean,
            outStopAtSec: Double,
            tailRateEarly: Double,
            straightRateMode: StraightRateMode? = null,
            level: DjLevel = AutomixV2Choreo.level,
        ): DeckKnots = DeckKnotBuilder.buildDeckKnots(
            plan, clock, sampleRate, outAnalysis, inAnalysis,
            outHasTailKnot, outStopAtSec, tailRateEarly, straightRateMode, level,
        )

        internal fun computeVocalMidCarve(
            plan: LoopMix.Plan,
            clock: LoopMix.Clock,
            script: PhraseScript?,
            inAnalysis: TrackAnalysis?,
            sungIn: List<LoopMix.Span>?,
        ): VocalMidCarve = DeckKnotBuilder.computeVocalMidCarve(plan, clock, script, inAnalysis, sungIn)

        /** Invert the emitted straight-deck knots at the last decoded OUT sample. */
        private fun straightOutPcmEndSec(
            frames: DoubleArray,
            positions: DoubleArray,
            pcmSize: Int,
            sampleRate: Double,
        ): Double = DeckKnotBuilder.straightOutPcmEndSec(frames, positions, pcmSize, sampleRate)

        internal fun computeEchoOutEnvelopes(
            plan: LoopMix.Plan,
            clock: LoopMix.Clock,
            outStopAtSec: Double,
            outPcmEndSec: Double = Double.POSITIVE_INFINITY,
            sampleRate: Int = 48000,
        ): EchoOutEnvelopes =
            DeckKnotBuilder.computeEchoOutEnvelopes(plan, clock, outStopAtSec, outPcmEndSec, sampleRate)

        /** Within this, a handover is heard as one sound; past it, the correction runs again. */
        const val ALIGNED_SECONDS = 0.012

        fun aligned(errorSeconds: Double) = abs(errorSeconds) <= ALIGNED_SECONDS

    }
}
