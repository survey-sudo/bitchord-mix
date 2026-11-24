package com.music.bitchord.playback.djmix

import android.os.Process
import android.os.SystemClock
import androidx.media3.common.MediaItem
import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.CandidateScoreSummary
import com.music.bitchord.djmix.core.contracts.DeckStemRequirement
import com.music.bitchord.djmix.core.contracts.DeckTarget as CoreDeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel as CoreDjLevel
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.StemKind as CoreStemKind
import com.music.bitchord.djmix.core.contracts.TempoKnot
import com.music.bitchord.djmix.core.contracts.TimeRangeMs
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.planner.BuiltInRecipeCatalog
import com.music.bitchord.djmix.core.planner.PairPlanner
import com.music.bitchord.djmix.core.planner.PlannerRequest
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.djmix.store.DjmixAnalysisStore
import com.music.bitchord.playback.djmix.store.TrackAnalysisV3Mapper
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.MixInspectorFeed
import com.music.bitchord.playback.smart.MixInspectorPhase
import com.music.bitchord.playback.smart.MixInspectorRecipeScore
import com.music.bitchord.playback.smart.MixInspectorState
import com.music.bitchord.playback.smart.MixInspectorTrack
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhraseMaps
import com.music.bitchord.playback.smart.PhrasePlanner
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.SongMaps
import com.music.bitchord.playback.smart.StemKind
import com.music.bitchord.playback.smart.StemSet
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.alignTempoOctave
import com.music.bitchord.playback.smart.camelotCode
import com.music.bitchord.playback.smart.choreo.AutomixV2Choreo
import com.music.bitchord.playback.smart.choreo.Candidate
import com.music.bitchord.playback.smart.choreo.ChoreoCaps
import com.music.bitchord.playback.smart.choreo.ChoreoInputs
import com.music.bitchord.playback.smart.choreo.ChoreoRendererBridge
import com.music.bitchord.playback.smart.choreo.ChoreoScript
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.choreo.Choreographer
import com.music.bitchord.playback.smart.choreo.DeckTarget
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.choreo.RecipeId
import com.music.bitchord.playback.smart.choreo.Recipes
import com.music.bitchord.playback.smart.choreo.StemAdapter
import com.music.bitchord.playback.smart.choreo.StemView
import com.music.bitchord.playback.smart.choreo.StructureView
import com.music.bitchord.playback.smart.choreo.TrackClock
import com.music.bitchord.playback.smart.entryKeyConfidence
import com.music.bitchord.playback.smart.entryKeyForMix
import com.music.bitchord.playback.smart.exitKeyConfidence
import com.music.bitchord.playback.smart.exitKeyForMix
import com.music.bitchord.playback.smart.regionalCamelotSteps
import com.music.bitchord.playback.smart.shouldDeclineToMix
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Lifecycle state machine for background preparation of an upcoming `(outId, inId)` pair.
 */
enum class PrepState {
    IDLE,
    ANALYZING,
    PLANNED,
    STEMMING,
    PREPARED,
    BUILDING,
    ARMED,
    RUNNING,
    DONE,
}

/**
 * Per-transition preparation & execution timing record emitted as `mix.timing` telemetry
 * and exposed for the Mix Inspector.
 */
data class PairTimings(
    val outId: String = "",
    val inId: String = "",
    val prepStartMs: Long = 0L,
    val analysisMs: Long = 0L,
    val planMs: Long = 0L,
    val stemMsOut: Long = 0L,
    val stemMsIn: Long = 0L,
    val preparedLeadSec: Double = 0.0,
    val buildMs: Long = 0L,
    val armLeadMs: Long = 0L,
    val watchdog: Boolean = false,
    val path: String = "",
    val level: String = "",
    val move: String = "",
    val fxCount: Int = 0,
    val stems: Int = 0,
    val fallbackReason: String = "",
) {
    fun toTelemetryMap(): Map<String, Any?> = mapOf(
        "outId" to outId,
        "inId" to inId,
        "prepStartMs" to prepStartMs,
        "analysisMs" to analysisMs,
        "planMs" to planMs,
        "stemMsOut" to stemMsOut,
        "stemMsIn" to stemMsIn,
        "preparedLeadSec" to preparedLeadSec,
        "buildMs" to buildMs,
        "armLeadMs" to armLeadMs,
        "watchdog" to watchdog,
        "path" to path,
        "level" to level,
        "move" to move,
        "fxCount" to fxCount,
        "stems" to stems,
        "fallbackReason" to fallbackReason,
    )
}

data class PrepTrack(
    val id: String,
    val durationSec: Double,
    val title: String = "",
    val artist: String = "",
    val albumTitle: String = "",
    val trackNumber: Int = 0,
    val albumArtist: String = "",
    val mediaItem: MediaItem? = null,
    val uri: Any? = null,
)

data class PrepRequest(
    val outTrack: PrepTrack,
    val inTrack: PrepTrack,
    val djLevel: DjLevel,
    val positionSec: Double,
    val sampleRate: Int = 48_000,
    val albumOrder: Boolean = false,
    val preferStraight: Boolean = false,
    val excludedRecipes: Set<RecipeId> = emptySet(),
    val fallbackSeconds: Double = 8.0,
    val lyricsOut: List<LyricCue> = emptyList(),
    val lyricsIn: List<LyricCue> = emptyList(),
    val minExitMs: Long? = null,
    val preferNoStemVariant: Boolean = false,
    val machineGeneration: Long? = null,
)

data class PlanInput(
    val outTrack: PrepTrack,
    val inTrack: PrepTrack,
    val outAnalysis: TrackAnalysis,
    val inAnalysis: TrackAnalysis,
    val outSongMap: SongMap?,
    val inSongMap: SongMap?,
    val sungOut: List<LoopMix.Span>?,
    val sungIn: List<LoopMix.Span>?,
    val djLevel: DjLevel,
    val sampleRate: Int,
    val positionSec: Double,
    val albumOrder: Boolean,
    val stemsExpected: Boolean,
    val preferStraight: Boolean = false,
    val excludedRecipes: Set<RecipeId> = emptySet(),
    val fallbackSeconds: Double = 8.0,
    val lyricsOut: List<LyricCue> = emptyList(),
    val lyricsIn: List<LyricCue> = emptyList(),
    val caps: Int = 0,
    val recentRecipeIds: List<String> = emptyList(),
    val transitionsSinceLastAbrupt: Int = 999,
    val minExitMs: Long? = null,
    val preferNoStemVariant: Boolean = false,
    val tasteWeights: Map<String, Double> = emptyMap(),
    val machineGeneration: Long = 0L,
    val outDecodeRangeMs: TimeRangeMs? = null,
    val inDecodeRangeMs: TimeRangeMs? = null,
)

data class SelectedPlan(
    val plan: LoopMix.Plan?,
    val phraseScript: PhraseScript? = null,
    val v2Prep: ChoreoSessionGlue.Prep? = null,
    val exitWindowSec: ClosedFloatingPointRange<Double>? = null,
    val entryCueSec: Double? = null,
    val pathHint: String = "v2-native",
    val fallbackReason: String = "",
    val straightRateMode: StraightRateMode? = null,
    val previewScript: ChoreoScript? = null,
    val transitionPlanV1: TransitionPlanV1? = null,
    val refusalReason: RefusalReason? = null,
)

fun interface TransitionPlanSelector {
    fun select(input: PlanInput): SelectedPlan?
}

/**
 * Sole transition planner: consults `:djmix-core` [PairPlanner] first for `{exit window, entry cue,
 * geometry, recipe, requiredStems}`, then constructs the concrete `LoopMix.Plan` and
 * [ChoreoSessionGlue.Prep] aligned strictly to that frozen decision.
 */
class DefaultTransitionPlanSelector(
    private val straightFallback: (PlanInput) -> SelectedPlan? = { StraightMixGeometry.select(it, it.caps) },
) : TransitionPlanSelector {
    override fun select(input: PlanInput): SelectedPlan? {
        val outA = input.outAnalysis
        val inA = input.inAnalysis
        val classic = StraightMixGeometry.classicPlan(input)
        if (StraightMixGeometry.policyExcluded(classic, input.albumOrder)) {
            return SelectedPlan(
                plan = null,
                fallbackReason = if (input.albumOrder) "album-order-excluded" else classic.reason,
                refusalReason = if (input.albumOrder) RefusalReason.ALBUM_ORDER_EXCLUDED else null,
            )
        }
        val outDur = input.outTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: outA.duration
        val inDur = input.inTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: inA.duration
        if (input.preferStraight && (!outA.isUsable || !inA.isUsable)) {
            return straightFallback(input)
        }

        val outV3 = PairPreparer.toV3ForPlanner(input.outTrack.id, outA, PhraseMaps.get(input.outTrack.id), input.outSongMap, outDur)
        val inV3 = PairPreparer.toV3ForPlanner(input.inTrack.id, inA, PhraseMaps.get(input.inTrack.id), input.inSongMap, inDur)
        val positionMs = (input.positionSec * 1000.0).roundToLong().coerceAtLeast(0L)
        val plannerReq = PlannerRequest(
            generation = input.machineGeneration.coerceAtLeast(0L),
            outTrack = outV3,
            inTrack = inV3,
            outTitle = input.outTrack.title,
            inTitle = input.inTrack.title,
            djLevel = PairPreparer.toCoreDjLevel(input.djLevel),
            positionMs = positionMs,
            minExitMs = input.minExitMs,
            recentRecipeIds = input.recentRecipeIds,
            transitionsSinceLastAbrupt = input.transitionsSinceLastAbrupt,
            excludedRecipeIds = input.excludedRecipes.map { it.code }.toSet(),
            preferNoStemVariant = input.preferNoStemVariant || !input.stemsExpected,
            tasteWeights = input.tasteWeights,
            outDecodeRangeMs = input.outDecodeRangeMs,
            inDecodeRangeMs = input.inDecodeRangeMs,
        )

        var outcome = PairPlanner.plan(plannerReq)
        if (outcome is MixOutcome.Refused && outcome.reason == RefusalReason.NO_ELIGIBLE_BOUNDARY) {
            val lastResort = PairPlanner.planLastResortMusicalExit(plannerReq)
            if (lastResort is MixOutcome.Ok) {
                outcome = lastResort
            }
        }
        if (outcome !is MixOutcome.Ok) {
            val refused = outcome as? MixOutcome.Refused
            return SelectedPlan(
                plan = null,
                fallbackReason = refused?.detail ?: "no-v2-plan",
                refusalReason = refused?.reason ?: RefusalReason.NO_ELIGIBLE_BOUNDARY,
            )
        }

        return buildConcreteFromPlanV1(outcome.value, input, outDur, inDur)
    }

    private fun buildConcreteFromPlanV1(
        v1: TransitionPlanV1,
        input: PlanInput,
        outDur: Double,
        inDur: Double,
    ): SelectedPlan? {
        val outA = input.outAnalysis
        val inA = input.inAnalysis
        val exitSec = v1.frozenExitMs / 1000.0
        val entrySec = v1.frozenEntryMs / 1000.0
        val barSec = (v1.barDurationMs / 1000.0).coerceAtLeast(0.5)
        val overlapBars = (v1.overlapDurationMs.toDouble() / v1.barDurationMs.coerceAtLeast(500L))
            .roundToInt().coerceIn(1, 16)
        val requestedRecipe = RecipeId.fromCode(v1.recipeId) ?: fallbackChoreoRecipeId(v1)
        val effectiveNoStem = input.preferNoStemVariant || !input.stemsExpected || v1.requiredStems.isEmpty()

        // Try matching LOOP geometry via candidate builder only when aligned to PairPlanner's frozen window.
        if (!input.preferStraight && v1.geometry == TransitionGeometry.LOOP) {
            val crown = LoopMix.plan(
                out = outA,
                outDuration = outDur,
                inn = inA,
                inDuration = inDur,
                earliestOutSec = (exitSec - barSec).coerceAtLeast(input.positionSec + 0.5),
                sungOut = input.sungOut,
                sungIn = input.sungIn,
                playableOutDurationSec = outDur,
            )
            if (crown != null && abs(crown.out.loopStart - exitSec) <= 1.5 * barSec &&
                abs(crown.inn.loopStart - entrySec) <= 2.0 * barSec
            ) {
                val stamped = stamp(crown, outA, inA)
                val prep = ChoreoSessionGlue.prepare(
                    plan = stamped,
                    script = null,
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = input.outSongMap,
                    inSongMap = input.inSongMap,
                    level = input.djLevel,
                    outId = input.outTrack.id,
                    inId = input.inTrack.id,
                    sampleRate = input.sampleRate,
                    albumOrder = input.albumOrder,
                    stemsExpected = !effectiveNoStem,
                    excludedRecipes = input.excludedRecipes,
                    capsOverride = input.caps,
                )?.let { base ->
                    ChoreoSessionGlue.Prep(
                        plan = base.plan,
                        forced = base.forced,
                        forcedRecipe = requestedRecipe,
                        caps = base.caps,
                        level = base.level,
                        outStructure = base.outStructure,
                        inStructure = base.inStructure,
                        pairKey = base.pairKey,
                        albumOrder = base.albumOrder,
                        note = base.note,
                        sampleRate = base.sampleRate,
                        straightRateMode = base.straightRateMode,
                        excludedRecipes = base.excludedRecipes,
                    )
                }
                if (prep != null && prep.plan.handoff == null) {
                    return SelectedPlan(
                        plan = prep.plan,
                        phraseScript = null,
                        v2Prep = prep,
                        exitWindowSec = prep.plan.out.loopStart..(prep.plan.out.loopStart + LoopMix.Clock(prep.plan).releaseAt),
                        entryCueSec = prep.plan.inn.loopStart,
                        pathHint = "v2-native",
                        transitionPlanV1 = alignV1Timing(v1, prep.plan),
                    )
                }
            }
        }

        // Try matching BRIDGE geometry via PhrasePlanner / LoopMix.bridge only when aligned to PairPlanner's window.
        if (!input.preferStraight && v1.geometry == TransitionGeometry.BRIDGE) {
            val steps = regionalCamelotSteps(outA, inA)
            val phraseChoice = if (PhraseConfig.PHRASE_PLANNER_ENABLED && !shouldDeclineToMix(outA, inA)) {
                runCatching {
                    PhrasePlanner.select(
                        input.outSongMap,
                        input.inSongMap,
                        steps,
                        outA.bpm,
                        inA.bpm,
                        earliestExitSec = (exitSec - barSec).coerceAtLeast(input.positionSec + 0.5),
                        playableOutDurationSec = outDur,
                        outAnalysis = outA,
                        inAnalysis = inA,
                    )
                }.getOrNull()
            } else null
            if (phraseChoice != null) {
                val script = TransitionMoves.compile(
                    phraseChoice, outA.bpm, inA.bpm,
                    outgoingId = input.outTrack.id,
                    incomingId = input.inTrack.id,
                )
                if (abs(script.plan.out.loopStart - exitSec) <= 1.5 * barSec &&
                    abs(script.plan.inn.loopStart - entrySec) <= 2.0 * barSec
                ) {
                    val stamped = stamp(script.plan, outA, inA)
                    val base = ChoreoSessionGlue.prepare(
                        plan = stamped,
                        script = script,
                        outAnalysis = outA,
                        inAnalysis = inA,
                        outSongMap = input.outSongMap,
                        inSongMap = input.inSongMap,
                        level = input.djLevel,
                        outId = input.outTrack.id,
                        inId = input.inTrack.id,
                        sampleRate = input.sampleRate,
                        albumOrder = input.albumOrder,
                        stemsExpected = !effectiveNoStem,
                        excludedRecipes = input.excludedRecipes,
                        capsOverride = input.caps,
                    )
                    if (base != null && base.plan.handoff == null) {
                        val prep = ChoreoSessionGlue.Prep(
                            plan = base.plan,
                            forced = base.forced,
                            forcedRecipe = requestedRecipe,
                            caps = base.caps,
                            level = base.level,
                            outStructure = base.outStructure,
                            inStructure = base.inStructure,
                            pairKey = base.pairKey,
                            albumOrder = base.albumOrder,
                            note = base.note,
                            sampleRate = base.sampleRate,
                            straightRateMode = base.straightRateMode,
                            excludedRecipes = base.excludedRecipes,
                        )
                        return SelectedPlan(
                            plan = prep.plan,
                            phraseScript = script,
                            v2Prep = prep,
                            exitWindowSec = prep.plan.out.loopStart..(prep.plan.out.loopStart + LoopMix.Clock(prep.plan).releaseAt),
                            entryCueSec = prep.plan.inn.loopStart,
                            pathHint = "v2-native",
                            transitionPlanV1 = alignV1Timing(v1, prep.plan),
                        )
                    }
                }
            }
            val bridge = LoopMix.bridge(
                out = outA,
                outDuration = outDur,
                inn = inA,
                inDuration = inDur,
                earliestOutSec = (exitSec - barSec).coerceAtLeast(input.positionSec + 0.5),
                sungOut = input.sungOut,
                sungIn = input.sungIn,
                playableOutDurationSec = outDur,
            )
            if (bridge != null && bridge.handoff == null &&
                abs(bridge.out.loopStart - exitSec) <= 1.5 * barSec &&
                abs(bridge.inn.loopStart - entrySec) <= 2.0 * barSec
            ) {
                val stamped = stamp(bridge, outA, inA)
                val base = ChoreoSessionGlue.prepare(
                    plan = stamped,
                    script = null,
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = input.outSongMap,
                    inSongMap = input.inSongMap,
                    level = input.djLevel,
                    outId = input.outTrack.id,
                    inId = input.inTrack.id,
                    sampleRate = input.sampleRate,
                    albumOrder = input.albumOrder,
                    stemsExpected = !effectiveNoStem,
                    excludedRecipes = input.excludedRecipes,
                    capsOverride = input.caps,
                )
                if (base != null) {
                    val prep = ChoreoSessionGlue.Prep(
                        plan = base.plan,
                        forced = base.forced,
                        forcedRecipe = requestedRecipe,
                        caps = base.caps,
                        level = base.level,
                        outStructure = base.outStructure,
                        inStructure = base.inStructure,
                        pairKey = base.pairKey,
                        albumOrder = base.albumOrder,
                        note = base.note,
                        sampleRate = base.sampleRate,
                        straightRateMode = base.straightRateMode,
                        excludedRecipes = base.excludedRecipes,
                    )
                    return SelectedPlan(
                        plan = prep.plan,
                        phraseScript = null,
                        v2Prep = prep,
                        exitWindowSec = prep.plan.out.loopStart..(prep.plan.out.loopStart + LoopMix.Clock(prep.plan).releaseAt),
                        entryCueSec = prep.plan.inn.loopStart,
                        pathHint = "v2-native",
                        transitionPlanV1 = alignV1Timing(v1, prep.plan),
                    )
                }
            }
        }

        // Anchored straight geometry preserving PairPlanner's {frozenExitMs, frozenEntryMs, recipe}.
        return buildStraightFromV1(v1, input, outDur, inDur, overlapBars, requestedRecipe)
            ?: straightFallback(input)?.copy(transitionPlanV1 = v1)
    }

    private fun buildStraightFromV1(
        v1: TransitionPlanV1,
        input: PlanInput,
        outDur: Double,
        inDur: Double,
        requestedBars: Int,
        requestedRecipe: RecipeId,
    ): SelectedPlan? {
        val outA = input.outAnalysis
        val inA = input.inAnalysis
        val outBpm = outA.tailBeats?.bpm?.takeIf { it.isFinite() && it > 1.0 }
            ?: outA.bpm.takeIf { it.isFinite() && it > 1.0 } ?: 120.0
        val inBpm = inA.headBeats?.bpm?.takeIf { it.isFinite() && it > 1.0 }
            ?: inA.bpm.takeIf { it.isFinite() && it > 1.0 } ?: outBpm
        val rateMode = StraightMixGeometry.rateMode(outA, inA, input.djLevel)
        val folded = alignTempoOctave(outBpm, inBpm)
        val fold = if (rateMode == StraightRateMode.BEAT_MATCH) inBpm / folded else 1.0
        val exitSec = (v1.frozenExitMs / 1000.0).coerceAtLeast(0.0)
        val entrySec = (v1.frozenEntryMs / 1000.0).coerceAtLeast(0.0)
        val outStruct = StructureView.best(input.outSongMap, outA)
        val inStruct = StructureView.best(input.inSongMap, inA)
        val recipeForStraight = if (requestedRecipe == RecipeId.C5) {
            RecipeId.C2
        } else requestedRecipe

        var bars = requestedBars.coerceIn(1, 16)
        var fitted: LoopMix.Plan? = null
        while (bars >= 1) {
            val enter = if (bars >= 4) 1 else 0
            val exit = if (bars >= 2) 1 else 0
            val cand = LoopMix.Plan(
                out = LoopMix.Pick(exitSec, exitSec, 0, 60.0 / outBpm, outBpm, 0.0, LoopMix.Check.MASK),
                inn = LoopMix.Pick(entrySec, entrySec, 0, 60.0 / inBpm, inBpm, 0.0, LoopMix.Check.MASK),
                fold = fold,
                enterBars = enter,
                rampBars = bars - enter - exit,
                exitBars = exit,
                totalBars = bars,
                outLoops = false,
                inLoops = false,
                nativeIncoming = rateMode == StraightRateMode.RATE_ONE,
                targetBpmOverride = if (rateMode == StraightRateMode.RATE_ONE) 0.0 else null,
                rung = v1.recipeName.ifBlank { "Straight" },
                alternativeKind = "v2-straight",
            )
            val span = LoopMix.Clock(cand).releaseAt
            val req = runCatching {
                LoopMixSession.decodeRequirements(cand, input.sampleRate, outA, inA, rateMode, input.djLevel)
            }.getOrNull()
            if (req != null && exitSec + span <= outDur + 0.5 && entrySec < inDur) {
                fitted = cand
                break
            }
            bars--
        }
        val placed = fitted ?: return null
        val stamped = stamp(placed, outA, inA)
        val prep = ChoreoSessionGlue.Prep(
            plan = stamped,
            forced = null,
            forcedRecipe = recipeForStraight,
            caps = input.caps,
            level = input.djLevel,
            outStructure = outStruct,
            inStructure = inStruct,
            pairKey = "${input.outTrack.id}->${input.inTrack.id}",
            albumOrder = input.albumOrder,
            note = "core-planner:${v1.recipeId}:${rateMode.name.lowercase()}",
            sampleRate = input.sampleRate,
            straightRateMode = rateMode,
            excludedRecipes = input.excludedRecipes,
        )
        val previewScript = ChoreoSessionGlue.withoutStems(
            prep, stamped, LoopMix.Clock(stamped), input.sampleRate, outA, inA,
            input.sungOut, input.sungIn, recipeForStraight,
        ) ?: ChoreoSessionGlue.withoutStems(
            prep, stamped, LoopMix.Clock(stamped), input.sampleRate, outA, inA,
            input.sungOut, input.sungIn, null,
        ) ?: return null
        val span = LoopMix.Clock(stamped).releaseAt
        val alignedV1 = v1.copy(
            frozenExitMs = (stamped.out.loopStart * 1000.0).roundToLong(),
            frozenEntryMs = (stamped.inn.loopStart * 1000.0).roundToLong(),
            overlapDurationMs = (span * 1000.0).roundToLong().coerceAtLeast(500L),
        )
        return SelectedPlan(
            plan = stamped,
            v2Prep = prep,
            entryCueSec = stamped.inn.loopStart,
            exitWindowSec = stamped.out.loopStart..(stamped.out.loopStart + span),
            pathHint = "v2-straight",
            straightRateMode = rateMode,
            fallbackReason = if (v1.isLastResort) "last-resort-musical-exit" else "",
            previewScript = previewScript,
            transitionPlanV1 = alignedV1,
        )
    }

    private fun alignV1Timing(v1: TransitionPlanV1, plan: LoopMix.Plan): TransitionPlanV1 {
        val clock = LoopMix.Clock(plan)
        val exitMs = (plan.out.loopStart * 1000.0).roundToLong().coerceAtLeast(0L)
        val entryMs = (plan.inn.loopStart * 1000.0).roundToLong().coerceAtLeast(0L)
        val overlapMs = (clock.releaseAt * 1000.0).roundToLong().coerceAtLeast(1L)
        return v1.copy(
            frozenExitMs = exitMs,
            frozenEntryMs = entryMs,
            swapDownbeatMs = v1.swapDownbeatMs.coerceIn(exitMs, exitMs + overlapMs),
            overlapDurationMs = overlapMs,
        )
    }

    private fun fallbackChoreoRecipeId(v1: TransitionPlanV1): RecipeId = when (v1.recipeId) {
        "S6" -> RecipeId.S2
        "C8" -> RecipeId.C2
        "C9" -> RecipeId.C1
        "F9" -> RecipeId.F7
        "F10" -> RecipeId.F1
        "F11" -> RecipeId.F5
        else -> when (v1.djLevel) {
            CoreDjLevel.SUBTLE -> RecipeId.S4
            CoreDjLevel.CLUB -> RecipeId.C2
            CoreDjLevel.FESTIVAL -> RecipeId.F5
        }
    }

    private fun stamp(plan: LoopMix.Plan, out: TrackAnalysis, inn: TrackAnalysis): LoopMix.Plan {
        val steps = regionalCamelotSteps(out, inn)
        val left = camelotCode(exitKeyForMix(out))
        val right = camelotCode(entryKeyForMix(inn))
        val keys = if (left != null && right != null) "$left→$right" else plan.keys
        val outConf = exitKeyConfidence(out)
        val inConf = entryKeyConfidence(inn)
        val keyClash = steps != null && steps > 2 && outConf >= 0.5 && inConf >= 0.5
        val clash = plan.clash || (plan.rung != "Echo" && keyClash)
        val carve = plan.carve || (plan.rung == "Beat blend" && keyClash)
        return plan.copy(clash = clash, carve = carve, keys = keys)
    }
}

data class PreparedPair(
    val outId: String,
    val inId: String,
    val outTitle: String = "",
    val outArtist: String = "",
    val inTitle: String = "",
    val inArtist: String = "",
    val djLevel: DjLevel,
    val state: PrepState,
    val plan: LoopMix.Plan? = null,
    val phraseScript: PhraseScript? = null,
    val v2Prep: ChoreoSessionGlue.Prep? = null,
    val script: ChoreoScript? = null,
    val topCandidates: List<Candidate> = emptyList(),
    val outStems: StemSet? = null,
    val inStems: StemSet? = null,
    val outRegion: LoopRegion? = null,
    val inRegion: LoopRegion? = null,
    val sampleRate: Int = 48_000,
    val outAnalysis: TrackAnalysis? = null,
    val inAnalysis: TrackAnalysis? = null,
    val outSongMap: SongMap? = null,
    val inSongMap: SongMap? = null,
    val sungOut: List<LoopMix.Span>? = null,
    val sungIn: List<LoopMix.Span>? = null,
    val albumOrder: Boolean = false,
    val timings: PairTimings = PairTimings(outId = outId, inId = inId, level = djLevel.name),
    val preparedAtMs: Long = 0L,
    val plannedWindowStartSec: Double = 0.0,
    val plannedWindowEndSec: Double = 0.0,
    val entryCueSec: Double = 0.0,
    val generation: Long = 0L,
    val analysisSignature: String = "",
    val straightRateMode: StraightRateMode? = null,
    val transitionPlanV1: TransitionPlanV1? = null,
    val useNoStemVariant: Boolean = false,
    val preparedSession: LoopMixSession? = null,
    val preparedSessionNoStem: Boolean = false,
    val lastRefusalReason: RefusalReason? = null,
    internal val outStemWindowStartSec: Double? = null,
    internal val outStemWindowEndSec: Double? = null,
    internal val inStemWindowStartSec: Double? = null,
    internal val inStemWindowEndSec: Double? = null,
)

/**
 * Background pipeline that prepares `(outId, inId)` well before the splice:
 * HEAD_TAIL/FULL analysis -> [PairPlanner] freeze -> containment-aware decode on `djmix-prep` ->
 * SCNet stem separation on dedicated `djmix-stems` -> off-thread `LoopMixSession` + `nativePrepare`.
 */
class PairPreparer(
    private val scope: CoroutineScope,
    private val analysisFor: (String) -> TrackAnalysis,
    private val analysisUpdates: SharedFlow<String>,
    private val analysisRunningFor: (String) -> Boolean = { false },
    private val songMapFor: (String, TrackAnalysis) -> SongMap? = { id, analysis ->
        PhraseMaps.get(id) ?: runCatching { SongMaps.fromAnalysis(id, analysis) }.getOrNull()
    },
    private val sungFor: (String) -> List<LoopMix.Span>? = { null },
    private val canUseStems: () -> Boolean = { AutomixV2Choreo.useStems },
    private val planSelector: TransitionPlanSelector,
    private val decodeRegion: (track: PrepTrack, fromSec: Double, toSec: Double, sampleRate: Int) -> LoopRegion?,
    private val separateStems: (
        trackId: String,
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        regionStartSec: Double,
        shouldAbort: () -> Boolean,
    ) -> StemSet?,
    private val stemLock: Any = Any(),
    private val requestAnalysis: (PrepTrack) -> Unit = {},
    private val onArtworkPrefetch: (MediaItem) -> Unit = {},
    private val capabilities: () -> Int = { runCatching { ChoreoRendererBridge.capabilities() }.getOrDefault(0) },
    private val positionSecProvider: () -> Double = { 0.0 },
    private val nowMs: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.nanoTime() / 1_000_000L }
    },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val telemetryEvent: (String, Map<String, Any?>) -> Unit = { name, fields ->
        AutomixTelemetry.event(name, fields)
    },
    private val analysisWaitTimeoutMs: Long = DEFAULT_ANALYSIS_WAIT_MS,
    private val fullPreferredWaitMs: Long = DEFAULT_FULL_PREFERRED_WAIT_MS,
    private val executor: ExecutorService = newPrepExecutor(),
    private val ownsExecutor: Boolean = true,
    private val store: DjmixAnalysisStore? = null,
    private val stemExecutor: ExecutorService = newStemExecutor(),
) {
    private val _state = MutableStateFlow<PreparedPair?>(null)
    val state: StateFlow<PreparedPair?> = _state.asStateFlow()

    private val generation = AtomicLong(0L)
    private val historyLock = Any()
    private val recentRecipeIds = ArrayDeque<String>()
    private var transitionsSinceLastAbrupt: Int = 999
    private var lastRecordedDoneGen: Long = -1L

    @Volatile private var mixExecutor: MixExecutor? = null
    @Volatile private var inLevelProvider: (String, String) -> Float = { _, _ -> 1f }
    @Volatile private var highQualityProvider: () -> Boolean = { true }

    @Volatile private var collectorJob: Job? = null
    @Volatile private var inspectorCollectorJob: Job? = null
    @Volatile private var activeRequest: PrepRequest? = null
    @Volatile private var activeGeneration: Long = 0L
    @Volatile private var released = false

    fun attachExecutionBridge(
        executor: MixExecutor?,
        inLevel: (String, String) -> Float = { _, _ -> 1f },
        highQuality: () -> Boolean = { true },
    ) {
        mixExecutor = executor
        inLevelProvider = inLevel
        highQualityProvider = highQuality
    }

    fun recordCompletedRecipe(recipeId: String?) {
        val id = recipeId?.trim()?.uppercase().takeUnless { it.isNullOrEmpty() } ?: return
        synchronized(historyLock) {
            if (recentRecipeIds.size >= RECIPE_HISTORY_CAP) recentRecipeIds.removeFirst()
            recentRecipeIds.addLast(id)
            transitionsSinceLastAbrupt = if (id in ABRUPT_RECIPE_IDS) {
                0
            } else {
                (transitionsSinceLastAbrupt + 1).coerceAtMost(999)
            }
        }
    }

    private fun recentRecipeIdsSnapshot(): List<String> = synchronized(historyLock) { recentRecipeIds.toList() }

    private fun currentTransitionsSinceLastAbrupt(): Int = synchronized(historyLock) { transitionsSinceLastAbrupt }

    // FORK-HOOK(mix-inspector): PairPreparer is the only process-global producer of this feed.
    init {
        collectorJob = scope.launch {
            analysisUpdates.collect { trackId ->
                if (trackId.isBlank() || released) return@collect
                onAnalysisUpdated(trackId)
            }
        }
        inspectorCollectorJob = scope.launch {
            _state.collect { publishInspectorSnapshot(it) }
        }
    }

    /** Trigger preparation when next track is known (track start, queue edit, skip, seek past window, remix). */
    fun trigger(request: PrepRequest, force: Boolean = false) {
        if (released) return
        val outId = request.outTrack.id
        val inId = request.inTrack.id
        if (outId.isBlank() || inId.isBlank() || outId == inId) return

        val current = _state.value
        if (!force && shouldDeduplicate(current, activeRequest, request)) {
            return
        }

        val gen = request.machineGeneration?.also { generation.set(it) } ?: generation.incrementAndGet()
        activeRequest = request
        activeGeneration = gen

        val initialTimings = PairTimings(
            outId = outId,
            inId = inId,
            prepStartMs = nowMs(),
            level = request.djLevel.name,
        )
        val previous = _state.value
        closeSupersededSession(previous, gen)
        _state.value = PreparedPair(
            outId = outId,
            inId = inId,
            outTitle = request.outTrack.title,
            outArtist = request.outTrack.artist,
            inTitle = request.inTrack.title,
            inArtist = request.inTrack.artist,
            djLevel = request.djLevel,
            state = PrepState.ANALYZING,
            sampleRate = request.sampleRate,
            albumOrder = request.albumOrder,
            timings = initialTimings,
            generation = gen,
            useNoStemVariant = request.preferNoStemVariant,
        )

        runCatching {
            executor.execute {
                executePipeline(gen, request, initialTimings, reuseFrom = previous)
            }
        }
    }

    fun onTrackStart(request: PrepRequest) = trigger(request, force = false)

    fun onQueueChange(request: PrepRequest) = trigger(request, force = false)

    fun onSeek(request: PrepRequest) {
        val current = _state.value
        val soughtPastWindow = current != null &&
            current.outId == request.outTrack.id &&
            current.inId == request.inTrack.id &&
            current.plannedWindowStartSec > 0.0 &&
            request.positionSec > current.plannedWindowStartSec + 0.5 &&
            current.state !in ACTIVE_EXECUTION_STATES
        trigger(request, force = soughtPastWindow)
    }

    /** Returns false without invalidating the existing preparation if no other allowed recipe fits. */
    fun onRemixRequested(request: PrepRequest, exclude: RecipeId? = null): Boolean {
        val current = _state.value ?: return false
        if (released || current.state in ACTIVE_EXECUTION_STATES || current.outId != request.outTrack.id ||
            current.inId != request.inTrack.id || current.djLevel != request.djLevel) return false
        val currentCode = current.transitionPlanV1?.recipeId ?: current.script?.recipeId
        val excluded = exclude ?: RecipeId.fromCode(currentCode)
        val excludedCodes = (request.excludedRecipes.map { it.code } + listOfNotNull(currentCode, excluded?.code)).toSet()
        val outA = current.outAnalysis ?: analysisFor(request.outTrack.id)
        val inA = current.inAnalysis ?: analysisFor(request.inTrack.id)
        if (outA.isUsable && inA.isUsable) {
            val outV3 = toV3ForPlanner(request.outTrack.id, outA, PhraseMaps.get(request.outTrack.id), current.outSongMap, request.outTrack.durationSec)
            val inV3 = toV3ForPlanner(request.inTrack.id, inA, PhraseMaps.get(request.inTrack.id), current.inSongMap, request.inTrack.durationSec)
            val probe = PairPlanner.plan(
                PlannerRequest(
                    generation = current.generation,
                    outTrack = outV3,
                    inTrack = inV3,
                    outTitle = request.outTrack.title,
                    inTitle = request.inTrack.title,
                    djLevel = toCoreDjLevel(request.djLevel),
                    positionMs = (request.positionSec * 1000.0).roundToLong().coerceAtLeast(0L),
                    minExitMs = request.minExitMs,
                    recentRecipeIds = recentRecipeIdsSnapshot(),
                    transitionsSinceLastAbrupt = currentTransitionsSinceLastAbrupt(),
                    excludedRecipeIds = excludedCodes,
                    preferNoStemVariant = request.preferNoStemVariant,
                    tasteWeights = store?.getPreferenceWeights().orEmpty(),
                ),
            )
            if (probe !is MixOutcome.Ok) return false
        } else if (excluded == null) {
            return false
        }
        val nextExcluded = if (excluded != null) request.excludedRecipes + excluded else request.excludedRecipes
        trigger(request.copy(excludedRecipes = nextExcluded), force = true)
        return true
    }

    /**
     * Handles analysis updates reactively on `djmix-prep` without calling `trigger(force = true)`
     * or cancelling running decode/SCNet jobs. Refines quietly via [PairPlanner] and re-freezes
     * only if refined exit/entry shifts by `>= 1 bar` or breaks recipe requirements.
     */
    fun onAnalysisUpdated(trackId: String) {
        if (released) return
        val req = activeRequest ?: return
        if (trackId != req.outTrack.id && trackId != req.inTrack.id) return
        val current = _state.value ?: return
        if (current.state in ACTIVE_EXECUTION_STATES) return
        val targetGen = current.generation
        runCatching {
            executor.execute {
                refineOnAnalysisUpdate(targetGen, req)
            }
        }
    }

    fun snapshotFor(outId: String, inId: String, djLevel: DjLevel? = null): PreparedPair? =
        _state.value?.takeIf {
            it.generation == generation.get() && it.outId == outId && it.inId == inId &&
                (djLevel == null || it.djLevel == djLevel)
        }

    /**
     * Exact execution lookup replacing `snapshotForBuild` and structural `plan ==` comparisons.
     * Validates pair identity, exact frozen generation, active DJ level, script/prep readiness,
     * and PCM coverage.
     */
    fun frozen(
        outId: String,
        inId: String,
        generation: Long,
        djLevel: DjLevel? = null,
    ): PreparedPair? {
        val expectedLevel = djLevel ?: activeRequest?.djLevel
        return snapshotFor(outId, inId, expectedLevel)?.takeIf { pair ->
            pair.generation == generation &&
                pair.state in setOf(PrepState.PREPARED, PrepState.BUILDING, PrepState.ARMED, PrepState.RUNNING) &&
                pair.plan != null &&
                pair.v2Prep != null &&
                ChoreoSessionGlue.isSamePlan(pair.v2Prep.plan, pair.plan) &&
                pair.script != null &&
                LoopMixSession.preparedCoverageRefusal(
                    pair.plan, pair.sampleRate, pair.outRegion, pair.inRegion,
                    pair.outAnalysis, pair.inAnalysis, pair.straightRateMode, pair.djLevel,
                ) == null
        }
    }

    fun isCurrentGeneration(outId: String, inId: String, expected: Long): Boolean =
        snapshotFor(outId, inId)?.generation == expected

    fun markBuilding(outId: String, inId: String) {
        updateIfPair(outId, inId) { cur ->
            val nextState = if (cur.state.ordinal < PrepState.BUILDING.ordinal || cur.state == PrepState.BUILDING) {
                PrepState.BUILDING
            } else cur.state
            cur.copy(state = nextState)
        }
    }

    fun markArmed(outId: String, inId: String, armLeadMs: Long = 0L) {
        updateIfPair(outId, inId) { cur ->
            val lead = if (armLeadMs > 0L) armLeadMs else cur.timings.armLeadMs
            cur.copy(
                state = PrepState.ARMED,
                timings = cur.timings.copy(armLeadMs = lead),
            )
        }
    }

    fun markRunning(outId: String, inId: String) {
        updateIfPair(outId, inId) { cur ->
            cur.copy(state = PrepState.RUNNING)
        }
    }

    fun markDone(outId: String, inId: String) {
        val updated = updateIfPair(outId, inId) { cur ->
            cur.copy(state = PrepState.DONE)
        } ?: return
        if (updated.generation != lastRecordedDoneGen) {
            lastRecordedDoneGen = updated.generation
            recordCompletedRecipe(updated.transitionPlanV1?.recipeId ?: updated.script?.recipeId)
        }
    }

    fun recordTiming(
        outId: String,
        inId: String,
        buildMs: Long? = null,
        armLeadMs: Long? = null,
        path: String? = null,
        level: String? = null,
        move: String? = null,
        fxCount: Int? = null,
        stems: Int? = null,
        fallbackReason: String? = null,
        watchdog: Boolean? = null,
    ): PairTimings {
        val updated = updateIfPair(outId, inId) { cur ->
            cur.copy(
                timings = cur.timings.copy(
                    buildMs = buildMs ?: cur.timings.buildMs,
                    armLeadMs = armLeadMs ?: cur.timings.armLeadMs,
                    path = path ?: cur.timings.path,
                    level = level ?: cur.timings.level,
                    move = move ?: cur.timings.move,
                    fxCount = fxCount ?: cur.timings.fxCount,
                    stems = stems ?: cur.timings.stems,
                    fallbackReason = fallbackReason ?: cur.timings.fallbackReason,
                    watchdog = watchdog ?: cur.timings.watchdog,
                ),
            )
        }
        return updated?.timings ?: PairTimings(
            outId = outId,
            inId = inId,
            buildMs = buildMs ?: 0L,
            armLeadMs = armLeadMs ?: 0L,
            path = path.orEmpty(),
            level = level.orEmpty(),
            move = move.orEmpty(),
            fxCount = fxCount ?: 0,
            stems = stems ?: 0,
            fallbackReason = fallbackReason.orEmpty(),
            watchdog = watchdog ?: false,
        )
    }

    fun recordWatchdog(
        outId: String,
        inId: String,
        reason: String,
        path: String? = null,
        fallbackReason: String = reason,
    ) {
        updateIfPair(outId, inId) { cur ->
            cur.copy(
                timings = cur.timings.copy(
                    watchdog = true,
                    path = path ?: cur.timings.path.ifBlank { "classic" },
                    fallbackReason = fallbackReason,
                ),
            )
        }
    }

    fun emitTiming(timings: PairTimings) {
        telemetryEvent("mix.timing", timings.toTelemetryMap())
    }

    fun invalidate(reason: String = "") {
        generation.incrementAndGet()
        activeRequest = null
        val cur = _state.value
        closeSupersededSession(cur, generation.get())
        if (cur != null && cur.state != PrepState.IDLE) {
            _state.value = cur.copy(
                state = PrepState.IDLE,
                outStems = null,
                inStems = null,
                outRegion = null,
                inRegion = null,
                preparedSession = null,
                timings = if (reason.isNotEmpty()) cur.timings.copy(fallbackReason = reason) else cur.timings,
            )
        }
    }

    fun release() {
        released = true
        generation.incrementAndGet()
        activeRequest = null
        collectorJob?.cancel()
        collectorJob = null
        closeSupersededSession(_state.value, generation.get())
        _state.value = null
        publishInspectorSnapshot(null)
        inspectorCollectorJob?.cancel()
        inspectorCollectorJob = null
        if (ownsExecutor) {
            runCatching { executor.shutdownNow() }
            runCatching { stemExecutor.shutdownNow() }
        }
    }

    fun consumePreparedSession(
        outId: String,
        inId: String,
        expectedGeneration: Long,
        useNoStemVariant: Boolean = false,
    ): LoopMixSession? {
        while (true) {
            val cur = _state.value ?: return null
            if (cur.outId != outId || cur.inId != inId || cur.generation != expectedGeneration) return null
            val session = cur.preparedSession ?: return null
            if (cur.preparedSessionNoStem != useNoStemVariant) return null
            if (_state.compareAndSet(cur, cur.copy(preparedSession = null))) {
                return session
            }
        }
    }

    /** Builds `LoopMixSession` + `nativePrepare()` off the main/audio thread during `PREPARING -> READY`. */
    fun prepareRenderer(
        frozen: PreparedPair,
        useNoStemVariant: Boolean = frozen.useNoStemVariant,
        onRefusal: ((String) -> Unit)? = null,
    ): LoopMixSession? {
        val plan = frozen.plan ?: return null.also { onRefusal?.invoke("prepared_plan_missing") }
        val sampleRate = frozen.sampleRate
        if (sampleRate <= 0) return null.also { onRefusal?.invoke("invalid_sample_rate") }
        frozen.preparedSession?.let { existing ->
            if (frozen.preparedSessionNoStem == useNoStemVariant) return existing
            closeSessionAsync(existing)
        }
        val inLevel = runCatching { inLevelProvider(frozen.outId, frozen.inId) }
            .getOrDefault(1f).takeIf { it.isFinite() && it > 0f } ?: 1f
        val highQuality = runCatching { highQualityProvider() }.getOrDefault(true)
        val planId = frozen.transitionPlanV1?.planId.orEmpty()
        var refusal: String? = null
        val session = if (frozen.straightRateMode != null) {
            LoopMixSession.buildStraight(
                prepared = frozen,
                outId = frozen.outId,
                inId = frozen.inId,
                sampleRate = sampleRate,
                inLevel = inLevel,
                highQuality = highQuality,
                onRefusal = { refusal = it },
                useNoStemVariant = useNoStemVariant,
                planId = planId,
            )
        } else {
            LoopMixSession.build(
                plan = plan,
                outId = frozen.outId,
                inId = frozen.inId,
                sampleRate = sampleRate,
                inLevel = inLevel,
                highQuality = highQuality,
                script = frozen.phraseScript,
                outAnalysis = frozen.outAnalysis,
                inAnalysis = frozen.inAnalysis,
                sungOut = frozen.sungOut,
                sungIn = frozen.sungIn,
                onRefusal = { refusal = it },
                outSongMap = frozen.outSongMap,
                inSongMap = frozen.inSongMap,
                djLevel = frozen.djLevel,
                albumOrder = frozen.albumOrder,
                preparedPrep = frozen.v2Prep,
                preparedOutStems = if (useNoStemVariant) null else frozen.outStems,
                preparedInStems = if (useNoStemVariant) null else frozen.inStems,
                preparedScript = frozen.script,
                preparedOutRegion = frozen.outRegion,
                preparedInRegion = frozen.inRegion,
                preparedOnly = true,
                preparedSampleRate = sampleRate,
                straightRateMode = null,
                preparationGeneration = frozen.generation,
                useNoStemVariant = useNoStemVariant,
                planId = planId,
                preparedGeneration = frozen.generation,
                preparedPlanId = planId,
                decode = { _, _, _ -> null },
            )
        }
        if (session == null) {
            onRefusal?.invoke(refusal ?: "session_build_failed")
            return null
        }
        publishIfCurrent(frozen.generation) { cur ->
            if (cur.outId != frozen.outId || cur.inId != frozen.inId) cur
            else {
                if (cur.preparedSession != null && cur.preparedSession !== session) {
                    closeSessionAsync(cur.preparedSession)
                }
                cur.copy(preparedSession = session, preparedSessionNoStem = useNoStemVariant)
            }
        }
        return session
    }

    /** Executes `MixEffect.PrepareRenderer` off-thread on `djmix-prep`. */
    fun prepareRendererOffThread(planId: String, targetGeneration: Long, useNoStemVariant: Boolean) {
        if (released) return
        runCatching {
            executor.execute {
                if (released || generation.get() != targetGeneration) return@execute
                val cur = _state.value ?: return@execute
                if (cur.generation != targetGeneration || cur.plan == null || cur.v2Prep == null ||
                    cur.outRegion == null || cur.inRegion == null
                ) return@execute
                val pair = if (cur.script == null && (useNoStemVariant || cur.v2Prep != null)) {
                    val v2 = cur.v2Prep
                    val noStemScript = ChoreoSessionGlue.withoutStems(
                        v2, cur.plan, LoopMix.Clock(cur.plan), cur.sampleRate,
                        cur.outAnalysis, cur.inAnalysis, cur.sungOut, cur.sungIn,
                        plannedRecipe = v2.forcedRecipe,
                    )
                    if (noStemScript != null) {
                        val next = cur.copy(script = noStemScript, useNoStemVariant = useNoStemVariant, state = PrepState.PREPARED)
                        publishIfCurrent(targetGeneration) { next }
                        next
                    } else cur
                } else cur

                if (pair.script == null) {
                    mixExecutor?.dispatch(MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, "no_script_for_renderer", recoverable = true))
                    return@execute
                }
                if (!LoopMixRenderer.available) {
                    // Without native JNI, live MixExecutor enters Failed -> classic fallback;
                    // standalone PairPreparer keeps PREPARED cleanly for JVM unit tests.
                    mixExecutor?.dispatch(MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, "renderer_unavailable", recoverable = false))
                    return@execute
                }
                val buildStarted = nowMs()
                var refusalReason: String? = null
                var usedNoStem = useNoStemVariant
                var session = prepareRenderer(pair, useNoStemVariant = usedNoStem) { refusalReason = it }
                if (session == null && !usedNoStem && pair.transitionPlanV1?.hasNoStemVariant == true) {
                    usedNoStem = true
                    refusalReason = null
                    session = prepareRenderer(pair.copy(useNoStemVariant = true), useNoStemVariant = true) { refusalReason = it }
                }
                if (released || generation.get() != targetGeneration) {
                    closeSessionAsync(session)
                    return@execute
                }
                if (session == null) {
                    mixExecutor?.dispatch(
                        MixEvent.RendererError(
                            FailureKind.RENDERER_PREPARE_ERROR,
                            refusalReason ?: "renderer_prepare_failed",
                            recoverable = pair.transitionPlanV1?.hasNoStemVariant == true,
                        ),
                    )
                    return@execute
                }
                val buildMs = (nowMs() - buildStarted).coerceAtLeast(0L)
                publishIfCurrent(targetGeneration) { s ->
                    s.copy(
                        state = if (s.state.ordinal < PrepState.PREPARED.ordinal) PrepState.PREPARED else s.state,
                        useNoStemVariant = usedNoStem,
                        preparedSession = session,
                        preparedSessionNoStem = usedNoStem,
                        timings = s.timings.copy(buildMs = buildMs),
                    )
                }
                mixExecutor?.dispatch(MixEvent.RendererPrepared(planId, targetGeneration, usedNoStem))
            }
        }
    }

    private fun closeSupersededSession(pair: PreparedPair?, newGen: Long) {
        val session = pair?.preparedSession ?: return
        if (pair.generation != newGen) closeSessionAsync(session)
    }

    private fun closeSessionAsync(session: LoopMixSession?) {
        if (session == null) return
        scope.launch(Dispatchers.IO) {
            runCatching { session.close() }
        }
    }

    private fun shouldDeduplicate(
        current: PreparedPair?,
        prevRequest: PrepRequest?,
        nextRequest: PrepRequest,
    ): Boolean {
        if (current == null || prevRequest == null) return false
        if (current.outId != nextRequest.outTrack.id || current.inId != nextRequest.inTrack.id) return false
        if (current.djLevel != nextRequest.djLevel) return false
        if (current.state in ACTIVE_EXECUTION_STATES) return true
        if (prevRequest.sampleRate != nextRequest.sampleRate || prevRequest.albumOrder != nextRequest.albumOrder ||
            prevRequest.preferStraight != nextRequest.preferStraight ||
            prevRequest.outTrack.durationSec != nextRequest.outTrack.durationSec ||
            prevRequest.inTrack.durationSec != nextRequest.inTrack.durationSec ||
            prevRequest.outTrack.uri != nextRequest.outTrack.uri || prevRequest.inTrack.uri != nextRequest.inTrack.uri) return false
        // Seek past the planned exit window must re-plan.
        if (current.plannedWindowStartSec > 0.0 && nextRequest.positionSec > current.plannedWindowStartSec + 0.5) {
            return false
        }
        return when (current.state) {
            PrepState.ANALYZING, PrepState.PLANNED, PrepState.STEMMING -> true
            PrepState.PREPARED -> {
                val sig = analysisSignature(
                    analysisFor(nextRequest.outTrack.id),
                    analysisFor(nextRequest.inTrack.id),
                )
                sig == current.analysisSignature
            }
            PrepState.IDLE, PrepState.DONE -> false
            else -> true
        }
    }

    /** The selector's view of an already-prepared pair, for a remix feasibility probe. */
    private fun planInput(request: PrepRequest, current: PreparedPair): PlanInput {
        val outA = current.outAnalysis ?: analysisFor(request.outTrack.id)
        val inA = current.inAnalysis ?: analysisFor(request.inTrack.id)
        return PlanInput(
            outTrack = request.outTrack,
            inTrack = request.inTrack,
            outAnalysis = outA,
            inAnalysis = inA,
            outSongMap = current.outSongMap,
            inSongMap = current.inSongMap,
            sungOut = current.sungOut ?: sungFor(request.outTrack.id),
            sungIn = current.sungIn ?: sungFor(request.inTrack.id),
            djLevel = request.djLevel,
            sampleRate = request.sampleRate,
            positionSec = request.positionSec,
            albumOrder = current.albumOrder,
            stemsExpected = canUseStems() && AutomixV2Choreo.useStems &&
                ChoreoCaps.has(capabilities(), ChoreoCaps.STEMS),
            preferStraight = request.preferStraight,
            excludedRecipes = request.excludedRecipes,
            fallbackSeconds = request.fallbackSeconds,
            lyricsOut = request.lyricsOut,
            lyricsIn = request.lyricsIn,
            caps = capabilities(),
            recentRecipeIds = recentRecipeIdsSnapshot(),
            transitionsSinceLastAbrupt = currentTransitionsSinceLastAbrupt(),
            minExitMs = request.minExitMs,
            preferNoStemVariant = request.preferNoStemVariant,
            tasteWeights = store?.getPreferenceWeights().orEmpty(),
            machineGeneration = current.generation,
        )
    }

    private fun updateIfPair(
        outId: String,
        inId: String,
        transform: (PreparedPair) -> PreparedPair,
    ): PreparedPair? {
        while (true) {
            val cur = _state.value ?: return null
            if (cur.outId != outId || cur.inId != inId) return null
            val next = transform(cur)
            if (_state.compareAndSet(cur, next)) return next
        }
    }

    private fun executePipeline(
        gen: Long,
        request: PrepRequest,
        initialTimings: PairTimings,
        reuseFrom: PreparedPair? = null,
    ) {
        fun isAborted(): Boolean = released || generation.get() != gen

        runCatching {
            if (isAborted()) return

            // --- Stage 1: Non-blocking HEAD_TAIL/FULL check (never stall 15 s in awaitAnalysis) ---
            requestAnalysis(request.outTrack)
            requestAnalysis(request.inTrack)

            val rawOut = analysisFor(request.outTrack.id)
            val rawIn = analysisFor(request.inTrack.id)
            mixExecutor?.dispatch(MixEvent.AnalysisReady(request.outTrack.id, analysisLevelOf(rawOut), gen, rawOut.isUsable))
            mixExecutor?.dispatch(MixEvent.AnalysisReady(request.inTrack.id, analysisLevelOf(rawIn), gen, rawIn.isUsable))

            val outTermFail = rawOut.status == TrackAnalysis.STATUS_READY && !rawOut.isUsable && !analysisRunningFor(request.outTrack.id)
            val inTermFail = rawIn.status == TrackAnalysis.STATUS_READY && !rawIn.isUsable && !analysisRunningFor(request.inTrack.id)

            if ((!rawOut.isUsable || !rawIn.isUsable) && !request.preferStraight) {
                val sig = analysisSignature(rawOut, rawIn)
                val analysisMs = (nowMs() - initialTimings.prepStartMs).coerceAtLeast(0L)
                if (outTermFail || inTermFail) {
                    publishIfCurrent(gen) { cur ->
                        cur.copy(
                            state = PrepState.IDLE,
                            outAnalysis = rawOut,
                            inAnalysis = rawIn,
                            analysisSignature = sig,
                            timings = cur.timings.copy(
                                analysisMs = analysisMs,
                                fallbackReason = "analysis-unavailable",
                            ),
                        )
                    }
                    mixExecutor?.dispatch(
                        MixEvent.AnalysisReady(
                            trackId = if (outTermFail) request.outTrack.id else request.inTrack.id,
                            level = AnalysisLevel.NONE,
                            generation = gen,
                            usable = false,
                            refusalReason = RefusalReason.WAITING_ON_ANALYSIS,
                        ),
                    )
                } else {
                    publishIfCurrent(gen) { cur ->
                        cur.copy(
                            outAnalysis = rawOut,
                            inAnalysis = rawIn,
                            analysisSignature = sig,
                            timings = cur.timings.copy(analysisMs = analysisMs),
                        )
                    }
                }
                return
            }

            planDecodeAndLaunchStems(
                gen = gen,
                request = request,
                initialTimings = initialTimings,
                rawOut = rawOut,
                rawIn = rawIn,
                reuseFrom = reuseFrom,
            )
        }
    }

    private fun refineOnAnalysisUpdate(targetGen: Long, request: PrepRequest) {
        if (released || generation.get() != targetGen) return
        val cur = _state.value ?: return
        if (cur.generation != targetGen || cur.state in ACTIVE_EXECUTION_STATES) return
        if (cur.outId != request.outTrack.id || cur.inId != request.inTrack.id) return

        val rawOut = analysisFor(request.outTrack.id)
        val rawIn = analysisFor(request.inTrack.id)
        mixExecutor?.dispatch(MixEvent.AnalysisReady(request.outTrack.id, analysisLevelOf(rawOut), targetGen, rawOut.isUsable))
        mixExecutor?.dispatch(MixEvent.AnalysisReady(request.inTrack.id, analysisLevelOf(rawIn), targetGen, rawIn.isUsable))

        if (cur.state == PrepState.ANALYZING && cur.plan == null) {
            if ((rawOut.isUsable && rawIn.isUsable) || request.preferStraight) {
                planDecodeAndLaunchStems(
                    gen = targetGen,
                    request = request.copy(positionSec = positionSecProvider()),
                    initialTimings = cur.timings,
                    rawOut = rawOut,
                    rawIn = rawIn,
                    reuseFrom = cur,
                )
            } else {
                val outTermFail = rawOut.status == TrackAnalysis.STATUS_READY && !rawOut.isUsable && !analysisRunningFor(request.outTrack.id)
                val inTermFail = rawIn.status == TrackAnalysis.STATUS_READY && !rawIn.isUsable && !analysisRunningFor(request.inTrack.id)
                if (outTermFail || inTermFail) {
                    publishIfCurrent(targetGen) { s ->
                        s.copy(
                            state = PrepState.IDLE,
                            outAnalysis = rawOut,
                            inAnalysis = rawIn,
                            analysisSignature = analysisSignature(rawOut, rawIn),
                            timings = s.timings.copy(fallbackReason = "analysis-unavailable"),
                        )
                    }
                }
            }
            return
        }

        val sig = analysisSignature(rawOut, rawIn)
        val outMap = if (rawOut.isUsable) songMapFor(request.outTrack.id, rawOut) else cur.outSongMap
        val inMap = if (rawIn.isUsable) songMapFor(request.inTrack.id, rawIn) else cur.inSongMap
        if (sig == cur.analysisSignature &&
            songMapsEquivalent(outMap, cur.outSongMap) &&
            songMapsEquivalent(inMap, cur.inSongMap)
        ) return
        if (!rawOut.isUsable || !rawIn.isUsable) return

        val outA = rawOut.copy(duration = request.outTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: rawOut.duration)
        val inA = rawIn.copy(duration = request.inTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: rawIn.duration)
        val outDur = request.outTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: outA.duration
        val inDur = request.inTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: inA.duration
        val liveReq = request.copy(positionSec = positionSecProvider().coerceAtLeast(request.positionSec))

        val outV3 = toV3ForPlanner(liveReq.outTrack.id, outA, PhraseMaps.get(liveReq.outTrack.id), outMap, outDur)
        val inV3 = toV3ForPlanner(liveReq.inTrack.id, inA, PhraseMaps.get(liveReq.inTrack.id), inMap, inDur)
        val refinedOutcome = PairPlanner.plan(
            PlannerRequest(
                generation = targetGen,
                outTrack = outV3,
                inTrack = inV3,
                outTitle = liveReq.outTrack.title,
                inTitle = liveReq.inTrack.title,
                djLevel = toCoreDjLevel(liveReq.djLevel),
                positionMs = (liveReq.positionSec * 1000.0).roundToLong().coerceAtLeast(0L),
                minExitMs = liveReq.minExitMs,
                recentRecipeIds = recentRecipeIdsSnapshot(),
                transitionsSinceLastAbrupt = currentTransitionsSinceLastAbrupt(),
                excludedRecipeIds = liveReq.excludedRecipes.map { it.code }.toSet(),
                preferNoStemVariant = liveReq.preferNoStemVariant,
                tasteWeights = store?.getPreferenceWeights().orEmpty(),
            ),
        )
        val oldV1 = cur.transitionPlanV1
        val refinedV1 = (refinedOutcome as? MixOutcome.Ok)?.value
        val barMs = (oldV1?.barDurationMs ?: refinedV1?.barDurationMs ?: (barSeconds(outA.bpm, 0.5) * 1000.0).roundToLong())
            .coerceAtLeast(500L)
        val oldExitMs = oldV1?.frozenExitMs ?: (cur.plannedWindowStartSec * 1000.0).roundToLong()
        val oldEntryMs = oldV1?.frozenEntryMs ?: (cur.entryCueSec * 1000.0).roundToLong()
        val shiftedGeBar = refinedV1 != null &&
            (abs(refinedV1.frozenExitMs - oldExitMs) >= barMs || abs(refinedV1.frozenEntryMs - oldEntryMs) >= barMs)
        val reqInvalidated = refinedV1 != null && oldV1 != null &&
            (oldV1.requiredStems != refinedV1.requiredStems && cur.state == PrepState.PREPARED &&
                ((cur.script?.outUsesStems == true && cur.outStems == null) ||
                    (cur.script?.inUsesStems == true && cur.inStems == null)))

        if (!shiftedGeBar && !reqInvalidated) {
            // < 1 bar shift and requirements intact: keep the frozen plan and any decoded/stem work.
            publishIfCurrent(targetGen) { s ->
                s.copy(
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = outMap,
                    inSongMap = inMap,
                    analysisSignature = sig,
                )
            }
            return
        }

        // Only re-freeze if MixMachine has not yet promoted past Preparing/Moved (or when standalone).
        val machineState = mixExecutor?.currentState()
        if (machineState != null && machineState !is MixState.Preparing && machineState !is MixState.Moved) {
            publishIfCurrent(targetGen) { s ->
                s.copy(
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = outMap,
                    inSongMap = inMap,
                    analysisSignature = sig,
                )
            }
            return
        }

        planDecodeAndLaunchStems(
            gen = targetGen,
            request = liveReq,
            initialTimings = cur.timings,
            rawOut = rawOut,
            rawIn = rawIn,
            reuseFrom = cur,
        )
    }

    private fun planDecodeAndLaunchStems(
        gen: Long,
        request: PrepRequest,
        initialTimings: PairTimings,
        rawOut: TrackAnalysis,
        rawIn: TrackAnalysis,
        reuseFrom: PreparedPair?,
    ) {
        fun isAborted(): Boolean = released || generation.get() != gen

        val outMap = if (rawOut.isUsable) songMapFor(request.outTrack.id, rawOut) else null
        val inMap = if (rawIn.isUsable) songMapFor(request.inTrack.id, rawIn) else null
        val analysisMs = (nowMs() - initialTimings.prepStartMs).coerceAtLeast(0L)
        val sig = analysisSignature(rawOut, rawIn)
        // Physical durations come from what plays; musical evidence remains intact.
        val outA = rawOut.copy(duration = request.outTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: rawOut.duration)
        val inA = rawIn.copy(duration = request.inTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: rawIn.duration)
        val sungOut = sungFor(request.outTrack.id)
        val sungIn = sungFor(request.inTrack.id)

        // --- Stage 2: PLANNED ---
        val planStartMs = nowMs()
        val stemsAllowed = canUseStems() && AutomixV2Choreo.useStems &&
            ChoreoCaps.has(capabilities(), ChoreoCaps.STEMS) && !request.preferNoStemVariant
        val input = PlanInput(
            outTrack = request.outTrack,
            inTrack = request.inTrack,
            outAnalysis = outA,
            inAnalysis = inA,
            outSongMap = outMap,
            inSongMap = inMap,
            sungOut = sungOut,
            sungIn = sungIn,
            djLevel = request.djLevel,
            sampleRate = request.sampleRate,
            positionSec = request.positionSec,
            albumOrder = request.albumOrder,
            stemsExpected = stemsAllowed,
            preferStraight = request.preferStraight,
            excludedRecipes = request.excludedRecipes,
            fallbackSeconds = request.fallbackSeconds,
            lyricsOut = request.lyricsOut,
            lyricsIn = request.lyricsIn,
            caps = capabilities(),
            recentRecipeIds = recentRecipeIdsSnapshot(),
            transitionsSinceLastAbrupt = currentTransitionsSinceLastAbrupt(),
            minExitMs = request.minExitMs,
            preferNoStemVariant = request.preferNoStemVariant,
            tasteWeights = store?.getPreferenceWeights().orEmpty(),
            machineGeneration = gen,
        )
        val selected = planSelector.select(input)
        val planMs = (nowMs() - planStartMs).coerceAtLeast(0L)
        if (isAborted()) return

        if (selected == null || (selected.plan == null && selected.exitWindowSec == null)) {
            val reason = selected?.fallbackReason?.ifBlank { "no-v2-plan" } ?: "no-v2-plan"
            publishIfCurrent(gen) { cur ->
                cur.copy(
                    state = PrepState.IDLE,
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = outMap,
                    inSongMap = inMap,
                    sungOut = sungOut,
                    sungIn = sungIn,
                    analysisSignature = sig,
                    lastRefusalReason = selected?.refusalReason,
                    timings = cur.timings.copy(
                        analysisMs = analysisMs,
                        planMs = planMs,
                        path = selected?.pathHint.orEmpty(),
                        fallbackReason = reason,
                    ),
                )
            }
            return
        }

        val v2Prep = selected.v2Prep ?: selected.plan?.let { plan ->
            ChoreoSessionGlue.prepare(
                plan = plan,
                script = selected.phraseScript,
                outAnalysis = outA,
                inAnalysis = inA,
                outSongMap = outMap,
                inSongMap = inMap,
                level = request.djLevel,
                outId = request.outTrack.id,
                inId = request.inTrack.id,
                sampleRate = request.sampleRate,
                albumOrder = request.albumOrder,
                stemsExpected = stemsAllowed,
                straightRateMode = selected.straightRateMode,
                excludedRecipes = request.excludedRecipes,
                capsOverride = input.caps,
            )
        }
        val effectivePlan = v2Prep?.plan ?: selected.plan
        val windowStartSec = effectivePlan?.out?.loopStart
            ?: selected.exitWindowSec?.start
            ?: 0.0
        val windowEndSec = effectivePlan?.let {
            val c = LoopMix.Clock(it)
            it.out.loopStart + c.releaseAt + it.exitTailSeconds
        } ?: selected.exitWindowSec?.endInclusive ?: (windowStartSec + 8.0)
        val entryCueSec = effectivePlan?.inn?.loopStart ?: selected.entryCueSec ?: 0.0

        val planV1 = resolveTransitionPlanV1(selected, request, gen, outA, inA, selected.previewScript)
        if (planV1 != null) {
            mixExecutor?.dispatch(MixEvent.PlanFrozen(planV1, gen))
            if (isAborted()) return
        }

        val plannedPair = PreparedPair(
            outId = request.outTrack.id,
            inId = request.inTrack.id,
            outTitle = request.outTrack.title,
            outArtist = request.outTrack.artist,
            inTitle = request.inTrack.title,
            inArtist = request.inTrack.artist,
            djLevel = request.djLevel,
            state = PrepState.PLANNED,
            plan = effectivePlan,
            phraseScript = selected.phraseScript,
            v2Prep = v2Prep,
            sampleRate = request.sampleRate,
            outAnalysis = outA,
            inAnalysis = inA,
            outSongMap = outMap,
            inSongMap = inMap,
            sungOut = sungOut,
            sungIn = sungIn,
            albumOrder = request.albumOrder,
            timings = initialTimings.copy(
                analysisMs = analysisMs,
                planMs = planMs,
                path = selected.pathHint,
            ),
            plannedWindowStartSec = windowStartSec,
            plannedWindowEndSec = windowEndSec,
            entryCueSec = entryCueSec,
            generation = gen,
            analysisSignature = sig,
            straightRateMode = selected.straightRateMode,
            transitionPlanV1 = planV1,
            useNoStemVariant = request.preferNoStemVariant,
        )
        publishIfCurrent(gen) { plannedPair }

        val (outBounds, inBounds) = computeDecodeWindows(
            effectivePlan = effectivePlan,
            exitWindowSec = selected.exitWindowSec,
            entryCueSec = entryCueSec,
            outDurationSec = request.outTrack.durationSec.takeIf { it > 0.0 } ?: outA.duration,
            inDurationSec = request.inTrack.durationSec.takeIf { it > 0.0 } ?: inA.duration,
            outAnalysis = outA,
            inAnalysis = inA,
            sampleRate = request.sampleRate,
            straightRateMode = selected.straightRateMode,
            level = request.djLevel,
        )

        // Containment reuse of previously decoded PCM before decoding.
        val reusableSamePair = reuseFrom?.takeIf {
            it.outId == request.outTrack.id && it.inId == request.inTrack.id && it.sampleRate == request.sampleRate
        }
        val outRegion = sliceLoopRegion(reusableSamePair?.outRegion, request.sampleRate, outBounds.first, outBounds.second)
            ?: decodeRegion(request.outTrack, outBounds.first, outBounds.second, request.sampleRate)
        if (isAborted()) return
        publishIfCurrent(gen) { it.copy(outRegion = outRegion) }

        val inRegion = sliceLoopRegion(reusableSamePair?.inRegion, request.sampleRate, inBounds.first, inBounds.second)
            ?: decodeRegion(request.inTrack, inBounds.first, inBounds.second, request.sampleRate)
        if (isAborted()) return

        val coverageReason = if (effectivePlan == null) {
            "prepared_script_geometry_missing"
        } else {
            LoopMixSession.preparedCoverageRefusal(
                effectivePlan, request.sampleRate, outRegion, inRegion,
                outA, inA, selected.straightRateMode, request.djLevel,
            )
        }
        if (coverageReason != null) {
            publishIfCurrent(gen) {
                it.copy(
                    state = PrepState.IDLE,
                    outRegion = outRegion,
                    inRegion = inRegion,
                    lastRefusalReason = RefusalReason.PCM_COVERAGE_INCOMPLETE,
                    timings = it.timings.copy(fallbackReason = coverageReason),
                )
            }
            return
        }

        // --- Stage 3: STEMMING ---
        publishIfCurrent(gen) { cur ->
            cur.copy(
                state = PrepState.STEMMING,
                outRegion = outRegion,
                inRegion = inRegion,
            )
        }
        if (planV1 != null) {
            mixExecutor?.dispatch(MixEvent.RegionsReady(planV1.planId, gen))
            if (isAborted()) return
        }

        val authoritativeV1 = selected.transitionPlanV1 != null && planV1 != null
        val (needOutStems, needInStems) = if (stemsAllowed && (outRegion != null || inRegion != null)) {
            if (authoritativeV1) {
                val outReq = planV1!!.requiredStems.any { it.deck == CoreDeckTarget.OUT }
                val inReq = planV1.requiredStems.any { it.deck == CoreDeckTarget.IN }
                outReq to inReq
            } else {
                requiredStemSides(
                    effectivePlan = effectivePlan,
                    v2Prep = v2Prep,
                    outA = outA,
                    inA = inA,
                    sungOut = sungOut,
                    sungIn = sungIn,
                    sampleRate = request.sampleRate,
                    caps = v2Prep?.caps ?: capabilities(),
                    level = request.djLevel,
                    outId = request.outTrack.id,
                    inId = request.inTrack.id,
                    albumOrder = request.albumOrder,
                )
            }
        } else {
            false to false
        }

        // M5 §3.3.2: separate only the overlap window (± 1 bar) per required deck, not the full padded decode region.
        val (outStemBounds, inStemBounds) = computeStemOverlapWindows(
            effectivePlan = effectivePlan,
            outRegion = outRegion,
            inRegion = inRegion,
            outA = outA,
            inA = inA,
            sampleRate = request.sampleRate,
            straightRateMode = selected.straightRateMode,
            level = request.djLevel,
            windowStartSec = windowStartSec,
            windowEndSec = windowEndSec,
            entryCueSec = entryCueSec,
        )
        val outStemInput = if (needOutStems && outRegion != null && outStemBounds != null) {
            sliceLoopRegion(outRegion, request.sampleRate, outStemBounds.first, outStemBounds.second) ?: outRegion
        } else null
        val inStemInput = if (needInStems && inRegion != null && inStemBounds != null) {
            sliceLoopRegion(inRegion, request.sampleRate, inStemBounds.first, inStemBounds.second) ?: inRegion
        } else null
        val outStemStartSec = outStemInput?.start ?: 0.0
        val outStemEndSec = outStemInput?.let { it.start + it.left.size.toDouble() / request.sampleRate } ?: 0.0
        val inStemStartSec = inStemInput?.start ?: 0.0
        val inStemEndSec = inStemInput?.let { it.start + it.left.size.toDouble() / request.sampleRate } ?: 0.0

        // Containment reuse of already-separated stems when the overlap stem window fits inside prior StemSet.
        val halfSampleSec = 0.5 / request.sampleRate.coerceAtLeast(1)
        val reusableOutCovers = reusableSamePair?.outStems != null && outStemInput != null && (
            (reusableSamePair.outStemWindowStartSec == null || reusableSamePair.outStemWindowEndSec == null) ||
                (outStemStartSec + halfSampleSec >= reusableSamePair.outStemWindowStartSec &&
                    outStemEndSec - halfSampleSec <= reusableSamePair.outStemWindowEndSec)
            )
        val slicedOutStems = if (needOutStems && outRegion != null && outStemInput != null && reusableOutCovers) {
            sliceStemSet(reusableSamePair?.outStems, outStemStartSec, outStemEndSec)
                ?.takeIf { StemAdapter.matchesRegion(it, outStemInput.left.size, request.sampleRate) }
                ?.let { compositeStemSetForRegion(outRegion, it, request.sampleRate) }
                ?.takeIf { StemAdapter.matchesRegion(it, outRegion.left.size, request.sampleRate) }
        } else null
        val reusableInCovers = reusableSamePair?.inStems != null && inStemInput != null && (
            (reusableSamePair.inStemWindowStartSec == null || reusableSamePair.inStemWindowEndSec == null) ||
                (inStemStartSec + halfSampleSec >= reusableSamePair.inStemWindowStartSec &&
                    inStemEndSec - halfSampleSec <= reusableSamePair.inStemWindowEndSec)
            )
        val slicedInStems = if (needInStems && inRegion != null && inStemInput != null && reusableInCovers) {
            sliceStemSet(reusableSamePair?.inStems, inStemStartSec, inStemEndSec)
                ?.takeIf { StemAdapter.matchesRegion(it, inStemInput.left.size, request.sampleRate) }
                ?.let { compositeStemSetForRegion(inRegion, it, request.sampleRate) }
                ?.takeIf { StemAdapter.matchesRegion(it, inRegion.left.size, request.sampleRate) }
        } else null

        val needSepOut = needOutStems && slicedOutStems == null && outRegion != null && outStemInput != null &&
            outStemInput.left.size / request.sampleRate.toDouble() <= AutomixV2Choreo.MAX_STEM_REGION_SEC
        val needSepIn = needInStems && slicedInStems == null && inRegion != null && inStemInput != null &&
            inStemInput.left.size / request.sampleRate.toDouble() <= AutomixV2Choreo.MAX_STEM_REGION_SEC

        if (!needSepOut && !needSepIn) {
            val (initialCompleted, totalRequired) = countCompletedAndRequiredStems(
                authoritativeV1 = authoritativeV1,
                planV1 = planV1,
                needOutStems = needOutStems,
                needInStems = needInStems,
                outStems = slicedOutStems,
                inStems = slicedInStems,
            )
            finalizePreparedPair(
                gen = gen,
                request = request,
                selected = selected,
                effectivePlan = effectivePlan,
                planV1 = planV1,
                v2Prep = v2Prep,
                outA = outA,
                inA = inA,
                outMap = outMap,
                inMap = inMap,
                sungOut = sungOut,
                sungIn = sungIn,
                outRegion = outRegion,
                inRegion = inRegion,
                outStems = slicedOutStems,
                inStems = slicedInStems,
                outStemWindowStartSec = outStemStartSec.takeIf { slicedOutStems != null },
                outStemWindowEndSec = outStemEndSec.takeIf { slicedOutStems != null },
                inStemWindowStartSec = inStemStartSec.takeIf { slicedInStems != null },
                inStemWindowEndSec = inStemEndSec.takeIf { slicedInStems != null },
                stemMsOut = 0L,
                stemMsIn = 0L,
                stemsCompleted = initialCompleted,
                stemsRequired = totalRequired,
                analysisMs = analysisMs,
                planMs = planMs,
                windowStartSec = windowStartSec,
            )
            return
        }

        // Run slow SCNet inference on dedicated single-thread `djmix-stems` so `djmix-prep` stays free.
        runCatching {
            stemExecutor.execute {
                if (isAborted()) return@execute
                var outStems = slicedOutStems
                var inStems = slicedInStems
                var stemMsOut = 0L
                var stemMsIn = 0L

                if (needSepOut && outRegion != null && outStemInput != null) {
                    val t0 = nowMs()
                    val rawOutStems = synchronized(stemLock) {
                        if (isAborted()) null
                        else separateStems(
                            request.outTrack.id,
                            outStemInput.left,
                            outStemInput.right,
                            request.sampleRate,
                            outStemInput.start,
                            ::isAborted,
                        )
                    }?.takeIf { StemAdapter.matchesRegion(it, outStemInput.left.size, request.sampleRate) }
                    outStems = rawOutStems
                        ?.let { compositeStemSetForRegion(outRegion, it, request.sampleRate) }
                        ?.takeIf { StemAdapter.matchesRegion(it, outRegion.left.size, request.sampleRate) }
                    stemMsOut = (nowMs() - t0).coerceAtLeast(0L)
                }
                if (isAborted()) return@execute

                if (needSepIn && inRegion != null && inStemInput != null) {
                    val t0 = nowMs()
                    val rawInStems = synchronized(stemLock) {
                        if (isAborted()) null
                        else separateStems(
                            request.inTrack.id,
                            inStemInput.left,
                            inStemInput.right,
                            request.sampleRate,
                            inStemInput.start,
                            ::isAborted,
                        )
                    }?.takeIf { StemAdapter.matchesRegion(it, inStemInput.left.size, request.sampleRate) }
                    inStems = rawInStems
                        ?.let { compositeStemSetForRegion(inRegion, it, request.sampleRate) }
                        ?.takeIf { StemAdapter.matchesRegion(it, inRegion.left.size, request.sampleRate) }
                    stemMsIn = (nowMs() - t0).coerceAtLeast(0L)
                }
                if (isAborted()) return@execute

                val (completed, required) = countCompletedAndRequiredStems(
                    authoritativeV1 = authoritativeV1,
                    planV1 = planV1,
                    needOutStems = needOutStems,
                    needInStems = needInStems,
                    outStems = outStems,
                    inStems = inStems,
                )
                finalizePreparedPair(
                    gen = gen,
                    request = request,
                    selected = selected,
                    effectivePlan = effectivePlan,
                    planV1 = planV1,
                    v2Prep = v2Prep,
                    outA = outA,
                    inA = inA,
                    outMap = outMap,
                    inMap = inMap,
                    sungOut = sungOut,
                    sungIn = sungIn,
                    outRegion = outRegion,
                    inRegion = inRegion,
                    outStems = outStems,
                    inStems = inStems,
                    outStemWindowStartSec = outStemStartSec.takeIf { outStems != null },
                    outStemWindowEndSec = outStemEndSec.takeIf { outStems != null },
                    inStemWindowStartSec = inStemStartSec.takeIf { inStems != null },
                    inStemWindowEndSec = inStemEndSec.takeIf { inStems != null },
                    stemMsOut = stemMsOut,
                    stemMsIn = stemMsIn,
                    stemsCompleted = completed,
                    stemsRequired = required,
                    analysisMs = analysisMs,
                    planMs = planMs,
                    windowStartSec = windowStartSec,
                )
            }
        }
    }

    private fun finalizePreparedPair(
        gen: Long,
        request: PrepRequest,
        selected: SelectedPlan,
        effectivePlan: LoopMix.Plan?,
        planV1: TransitionPlanV1?,
        v2Prep: ChoreoSessionGlue.Prep?,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        outMap: SongMap?,
        inMap: SongMap?,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
        outRegion: LoopRegion?,
        inRegion: LoopRegion?,
        outStems: StemSet?,
        inStems: StemSet?,
        outStemWindowStartSec: Double? = null,
        outStemWindowEndSec: Double? = null,
        inStemWindowStartSec: Double? = null,
        inStemWindowEndSec: Double? = null,
        stemMsOut: Long,
        stemMsIn: Long,
        stemsCompleted: Int,
        stemsRequired: Int,
        analysisMs: Long,
        planMs: Long,
        windowStartSec: Double,
    ) {
        if (released || generation.get() != gen) return
        val effectiveCaps = if (request.preferNoStemVariant) {
            (v2Prep?.caps ?: capabilities()) and ChoreoCaps.STEMS.inv()
        } else {
            v2Prep?.caps ?: capabilities()
        }
        val (script, top3) = chooseFinalScript(
            effectivePlan = effectivePlan,
            v2Prep = v2Prep,
            outA = outA,
            inA = inA,
            sungOut = sungOut,
            sungIn = sungIn,
            outStems = if (request.preferNoStemVariant) null else outStems,
            inStems = if (request.preferNoStemVariant) null else inStems,
            sampleRate = request.sampleRate,
            caps = effectiveCaps,
            level = request.djLevel,
            outId = request.outTrack.id,
            inId = request.inTrack.id,
            albumOrder = request.albumOrder,
            preferredScript = selected.previewScript.takeIf {
                request.preferNoStemVariant || (outStems == null && inStems == null)
            },
        )
        if (released || generation.get() != gen) return

        if (script == null) {
            publishIfCurrent(gen) {
                it.copy(
                    state = PrepState.IDLE,
                    outRegion = outRegion,
                    inRegion = inRegion,
                    lastRefusalReason = RefusalReason.CHOREOGRAPHY_UNAVAILABLE,
                    timings = it.timings.copy(fallbackReason = "same-level-choreography-unavailable"),
                )
            }
            return
        }

        val usedOutStems = if (script.outUsesStems) outStems else null
        val usedInStems = if (script.inUsesStems) inStems else null
        val usedNoStemVariant = request.preferNoStemVariant || (!script.outUsesStems && !script.inUsesStems && stemsRequired > 0)
        val stemsCount = maxOf(usedOutStems?.stemCount ?: 0, usedInStems?.stemCount ?: 0)
        val leadSec = (windowStartSec - positionSecProvider()).coerceAtLeast(0.0)
        val readyAtMs = nowMs()

        publishIfCurrent(gen) { cur ->
            cur.copy(
                state = PrepState.PREPARED,
                script = script,
                topCandidates = top3,
                outStems = usedOutStems,
                inStems = usedInStems,
                outStemWindowStartSec = outStemWindowStartSec.takeIf { usedOutStems != null },
                outStemWindowEndSec = outStemWindowEndSec.takeIf { usedOutStems != null },
                inStemWindowStartSec = inStemWindowStartSec.takeIf { usedInStems != null },
                inStemWindowEndSec = inStemWindowEndSec.takeIf { usedInStems != null },
                outRegion = outRegion,
                inRegion = inRegion,
                preparedAtMs = readyAtMs,
                useNoStemVariant = usedNoStemVariant,
                timings = cur.timings.copy(
                    analysisMs = analysisMs,
                    planMs = planMs,
                    stemMsOut = stemMsOut,
                    stemMsIn = stemMsIn,
                    preparedLeadSec = leadSec,
                    path = selected.pathHint,
                    level = request.djLevel.name,
                    move = script.move.name,
                    fxCount = script.fxCount,
                    stems = stemsCount,
                    fallbackReason = selected.fallbackReason,
                ),
            )
        }

        request.inTrack.mediaItem?.let { item ->
            scope.launch(mainDispatcher) {
                if (!released && generation.get() == gen) onArtworkPrefetch(item)
            }
        }

        if (planV1 != null) {
            val activeExecutor = mixExecutor
            if (activeExecutor != null) {
                activeExecutor.dispatch(
                    MixEvent.StemsReady(
                        planId = planV1.planId,
                        generation = gen,
                        stemsCompleted = stemsCompleted,
                        stemsRequired = if (usedNoStemVariant) 0 else stemsRequired,
                        stemLatencyOutMs = stemMsOut.takeIf { it > 0L },
                        stemLatencyInMs = stemMsIn.takeIf { it > 0L },
                    ),
                )
            } else if (LoopMixRenderer.available) {
                _state.value?.takeIf { it.generation == gen }?.let { frozenPair ->
                    prepareRenderer(frozenPair, useNoStemVariant = usedNoStemVariant)
                }
            }
        }
    }

    private fun resolveTransitionPlanV1(
        selected: SelectedPlan,
        request: PrepRequest,
        gen: Long,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        script: ChoreoScript?,
    ): TransitionPlanV1? {
        selected.transitionPlanV1?.let { return it }
        val plan = selected.plan
        val outBpm = outA.bpm.takeIf { it.isFinite() && it > 1.0 } ?: plan?.out?.bpm?.takeIf { it > 1.0 } ?: 120.0
        val barMs = (barSeconds(outBpm, plan?.out?.beatSec ?: 0.5) * 1000.0).roundToLong().coerceAtLeast(500L)
        val exitMs = ((plan?.out?.loopStart ?: selected.exitWindowSec?.start ?: 0.0) * 1000.0).roundToLong().coerceAtLeast(0L)
        val entryMs = ((plan?.inn?.loopStart ?: selected.entryCueSec ?: 0.0) * 1000.0).roundToLong().coerceAtLeast(0L)
        val overlapMs = if (plan != null) {
            (LoopMix.Clock(plan).releaseAt * 1000.0).roundToLong().coerceAtLeast(1L)
        } else {
            val w = selected.exitWindowSec ?: return null
            ((w.endInclusive - w.start) * 1000.0).roundToLong().coerceAtLeast(1L)
        }
        val geom = when {
            plan == null -> TransitionGeometry.STRAIGHT
            plan.outLoops || plan.inLoops -> TransitionGeometry.LOOP
            selected.straightRateMode == null -> TransitionGeometry.BRIDGE
            else -> TransitionGeometry.STRAIGHT
        }
        val recipeCode = script?.recipeId?.takeIf { it.isNotBlank() }
            ?: selected.previewScript?.recipeId?.takeIf { it.isNotBlank() }
            ?: selected.v2Prep?.forcedRecipe?.code
            ?: "S1"
        val recipeName = script?.recipeName?.takeIf { it.isNotBlank() }
            ?: selected.previewScript?.recipeName?.takeIf { it.isNotBlank() }
            ?: selected.v2Prep?.forcedRecipe?.displayName
            ?: "Smooth blend"
        val catalogEntry = BuiltInRecipeCatalog.byId(recipeCode)
        return TransitionPlanV1(
            planId = "plan-$gen-$recipeCode-$exitMs",
            generation = gen,
            outTrackId = request.outTrack.id,
            inTrackId = request.inTrack.id,
            outTitle = request.outTrack.title,
            inTitle = request.inTrack.title,
            djLevel = toCoreDjLevel(request.djLevel),
            geometry = geom,
            frozenExitMs = exitMs,
            frozenEntryMs = entryMs,
            swapDownbeatMs = exitMs + overlapMs / 2L,
            overlapDurationMs = overlapMs,
            barDurationMs = barMs,
            recipeId = recipeCode,
            recipeName = recipeName,
            requiredStems = catalogEntry?.requiredStems ?: emptySet(),
            hasNoStemVariant = catalogEntry?.hasNoStemVariant ?: true,
        )
    }

    private fun publishIfCurrent(gen: Long, transform: (PreparedPair) -> PreparedPair) {
        while (true) {
            if (released || generation.get() != gen) return
            val cur = _state.value ?: return
            if (cur.generation != gen) return
            val next = transform(cur)
            if (_state.compareAndSet(cur, next)) return
        }
    }

    private fun publishInspectorSnapshot(pair: PreparedPair?) {
        if (released) {
            MixInspectorFeed.publish(null)
            return
        }
        if (pair == null) {
            MixInspectorFeed.publish(null)
            return
        }
        val script = pair.script
        val planV1 = pair.transitionPlanV1
        val timings = pair.timings
        val plannedPath = timings.path.takeIf { it.isNotBlank() }
        val actualPath = if (pair.state.ordinal >= PrepState.PREPARED.ordinal) plannedPath else null
        val candidates = pair.topCandidates.ifEmpty { script?.topCandidates.orEmpty() }
            .take(3)
            .map { candidate ->
                MixInspectorRecipeScore(
                    recipeId = candidate.recipeId.code,
                    recipeName = candidate.recipeName,
                    score = candidate.total,
                    reason = candidate.reason,
                )
            }
            .ifEmpty {
                planV1?.topScores.orEmpty().take(3).map { s ->
                    MixInspectorRecipeScore(
                        recipeId = s.recipeId,
                        recipeName = s.recipeName,
                        score = s.score,
                        reason = s.reason,
                    )
                }
            }
        val effects = if (script == null) {
            emptyList()
        } else {
            (script.outFx.activeFxNames + script.inFx.activeFxNames).distinct()
        }
        val stemsReady = when {
            pair.state.ordinal >= PrepState.PREPARED.ordinal -> true
            pair.state == PrepState.STEMMING -> false
            else -> null
        }
        val remixReady = pair.state == PrepState.PREPARED && script != null
        val remixUnavailableReason = when {
            remixReady -> null
            pair.state.ordinal >= PrepState.BUILDING.ordinal -> "This transition is already building or playing."
            pair.script == null && pair.state == PrepState.PREPARED -> "No prepared recipe is available to remix."
            pair.state == PrepState.IDLE -> "There is no prepared transition to remix."
            else -> "Preparation is not ready yet."
        }
        MixInspectorFeed.publish(
            MixInspectorState(
                generation = pair.generation,
                current = MixInspectorTrack(
                    mediaId = pair.outId,
                    title = pair.outTitle,
                    artist = pair.outArtist,
                ),
                next = MixInspectorTrack(
                    mediaId = pair.inId,
                    title = pair.inTitle,
                    artist = pair.inArtist,
                ),
                level = pair.djLevel,
                phase = when (pair.state) {
                    PrepState.IDLE -> MixInspectorPhase.IDLE
                    PrepState.ANALYZING -> MixInspectorPhase.ANALYZING
                    PrepState.PLANNED -> MixInspectorPhase.PLANNED
                    PrepState.STEMMING -> MixInspectorPhase.STEMMING
                    PrepState.PREPARED -> MixInspectorPhase.PREPARED
                    PrepState.BUILDING -> MixInspectorPhase.BUILDING
                    PrepState.ARMED -> MixInspectorPhase.ARMED
                    PrepState.RUNNING -> MixInspectorPhase.RUNNING
                    PrepState.DONE -> MixInspectorPhase.DONE
                },
                plannedPath = plannedPath,
                path = actualPath,
                recipeId = planV1?.recipeId?.takeIf { it.isNotBlank() }
                    ?: script?.recipeId?.takeIf { it.isNotBlank() }
                    ?: pair.v2Prep?.forcedRecipe?.code
                    ?: candidates.firstOrNull()?.recipeId,
                recipeName = planV1?.recipeName?.takeIf { it.isNotBlank() }
                    ?: script?.recipeName?.takeIf { it.isNotBlank() }
                    ?: pair.v2Prep?.forcedRecipe?.displayName
                    ?: candidates.firstOrNull()?.recipeName,
                reason = script?.reason?.takeIf { it.isNotBlank() }
                    ?: planV1?.placementReasons?.firstOrNull(),
                stemsReady = stemsReady,
                stemCount = if (pair.state.ordinal >= PrepState.PREPARED.ordinal) timings.stems else null,
                prepStartMs = timings.prepStartMs.takeIf { it > 0L },
                analysisMs = if (pair.state != PrepState.ANALYZING) timings.analysisMs else null,
                planMs = if (pair.state.ordinal >= PrepState.PLANNED.ordinal && pair.state != PrepState.ANALYZING) timings.planMs else null,
                stemMsOut = if (pair.state.ordinal >= PrepState.STEMMING.ordinal) timings.stemMsOut else null,
                stemMsIn = if (pair.state.ordinal >= PrepState.STEMMING.ordinal) timings.stemMsIn else null,
                preparedLeadSec = if (pair.state.ordinal >= PrepState.PREPARED.ordinal) timings.preparedLeadSec else null,
                buildMs = timings.buildMs.takeIf { it > 0L },
                armLeadMs = timings.armLeadMs.takeIf { it > 0L },
                watchdog = timings.watchdog,
                fxCount = script?.fxCount ?: timings.fxCount.takeIf { it > 0 },
                effects = effects,
                topCandidates = candidates,
                fallbackReason = timings.fallbackReason.takeIf { it.isNotBlank() },
                remixAvailable = remixReady,
                remixUnavailableReason = remixUnavailableReason,
            ),
        )
    }

    private fun computeDecodeWindows(
        effectivePlan: LoopMix.Plan?,
        exitWindowSec: ClosedFloatingPointRange<Double>?,
        entryCueSec: Double,
        outDurationSec: Double,
        inDurationSec: Double,
        outAnalysis: TrackAnalysis,
        inAnalysis: TrackAnalysis,
        sampleRate: Int,
        straightRateMode: StraightRateMode? = null,
        level: DjLevel = AutomixV2Choreo.level,
    ): Pair<Pair<Double, Double>, Pair<Double, Double>> {
        val outBarSec = barSeconds(outAnalysis.bpm, effectivePlan?.out?.beatSec ?: 0.5)
        val inBarSec = barSeconds(inAnalysis.bpm, effectivePlan?.inn?.beatSec ?: 0.5)
        if (effectivePlan != null) {
            val bounds = LoopMixSession.decodeRequirements(effectivePlan, sampleRate, outAnalysis, inAnalysis, straightRateMode, level)
            val clock = LoopMix.Clock(effectivePlan)
            val echoHold = if (effectivePlan.echoTail) effectivePlan.out.beatSec * 8 else 0.0
            val outStop = clock.releaseAt + 0.05 + echoHold + effectivePlan.exitTailSeconds
            val tailRate = if (clock.beats >= 1) {
                val b0 = clock.beatTimes[clock.beats - 1]
                val b1 = clock.beatTimes[clock.beats]
                (effectivePlan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
            } else 1.0
            val outHasTailKnot = !effectivePlan.outLoops && outStop > clock.releaseAt + 1e-6
            val actualInTo = runCatching {
                val knots = LoopMixSession.buildDeckKnots(
                    effectivePlan, clock, sampleRate, outAnalysis, inAnalysis,
                    outHasTailKnot, outStop, tailRate, straightRateMode, level,
                )
                val geom = LoopMixSession.incomingGeometry(effectivePlan, clock, knots)
                val inTail = bounds.inTo - clock.elementTrackTime
                // decodeRequirements already includes the actual handover; do not add the offset twice.
                maxOf(bounds.inTo, geom.requiredEnd)
            }.getOrDefault(bounds.inTo)

            val outWin = paddedWindow(bounds.outFrom, bounds.outTo, 2.0 * outBarSec, outDurationSec)
            val inWin = paddedWindow(bounds.inFrom, actualInTo, 2.0 * inBarSec, inDurationSec)
            return outWin to inWin
        }
        val window = requireNotNull(exitWindowSec)
        val span = (window.endInclusive - window.start).coerceAtLeast(4.0)
        val outWin = paddedWindow(
            coreFrom = (window.start - 1.0).coerceAtLeast(0.0),
            coreTo = window.endInclusive + 1.0,
            padSec = 2.0 * outBarSec,
            trackDurationSec = outDurationSec,
        )
        val inWin = paddedWindow(
            coreFrom = (entryCueSec - 1.0).coerceAtLeast(0.0),
            coreTo = entryCueSec + span + 3.0,
            padSec = 2.0 * inBarSec,
            trackDurationSec = inDurationSec,
        )
        return outWin to inWin
    }

    private fun requiredStemSides(
        effectivePlan: LoopMix.Plan?,
        v2Prep: ChoreoSessionGlue.Prep?,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
        sampleRate: Int,
        caps: Int,
        level: DjLevel,
        outId: String,
        inId: String,
        albumOrder: Boolean,
    ): Pair<Boolean, Boolean> {
        if (effectivePlan == null) return true to true
        val inputs = buildChoreoInputs(
            effectivePlan = effectivePlan,
            v2Prep = v2Prep,
            outA = outA,
            inA = inA,
            sungOut = sungOut,
            sungIn = sungIn,
            outStems = dummyStemView(sampleRate),
            inStems = dummyStemView(sampleRate),
            sampleRate = sampleRate,
            caps = caps,
            level = level,
            outId = outId,
            inId = inId,
            albumOrder = albumOrder,
            stemsExpected = true,
        )
        val top3 = Choreographer.rank(inputs).take(3).map { it.recipeId }.toMutableList()
        v2Prep?.forcedRecipe?.let { if (it !in top3) top3.add(0, it) }
        var needOut = false
        var needIn = false
        for (id in top3) {
            val recipe = Recipes.byId(id)
            val needsAny = recipe.requiresStems > 0 || recipe.stemModeMinStems > 0 ||
                recipe.steps.any {
                    (it is com.music.bitchord.playback.smart.choreo.RecipeStep.LaneCurve && it.kind.isStem) ||
                        (it is com.music.bitchord.playback.smart.choreo.RecipeStep.SwapLows && it.useBassStems)
                }
            if (needsAny) {
                if (recipe.requiresStems > 0 || recipe.steps.any {
                        (it is com.music.bitchord.playback.smart.choreo.RecipeStep.LaneCurve &&
                            it.deck == DeckTarget.OUT && it.kind.isStem) ||
                            (it is com.music.bitchord.playback.smart.choreo.RecipeStep.SwapLows && it.useBassStems)
                    }
                ) {
                    needOut = true
                }
                if ((recipe.requiresStems > 0 && id != RecipeId.C3 && id != RecipeId.F4) || recipe.steps.any {
                        (it is com.music.bitchord.playback.smart.choreo.RecipeStep.LaneCurve &&
                            it.deck == DeckTarget.IN && it.kind.isStem) ||
                            (it is com.music.bitchord.playback.smart.choreo.RecipeStep.SwapLows && it.useBassStems)
                    }
                ) {
                    needIn = true
                }
            }
        }
        return needOut to needIn
    }

    private fun chooseFinalScript(
        effectivePlan: LoopMix.Plan?,
        v2Prep: ChoreoSessionGlue.Prep?,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
        outStems: StemSet?,
        inStems: StemSet?,
        sampleRate: Int,
        caps: Int,
        level: DjLevel,
        outId: String,
        inId: String,
        albumOrder: Boolean,
        preferredScript: ChoreoScript? = null,
    ): Pair<ChoreoScript?, List<Candidate>> {
        if (effectivePlan == null || v2Prep == null) return null to emptyList()
        val inputs = buildChoreoInputs(
            effectivePlan = effectivePlan,
            v2Prep = v2Prep,
            outA = outA,
            inA = inA,
            sungOut = sungOut,
            sungIn = sungIn,
            outStems = StemAdapter.view(outStems),
            inStems = StemAdapter.view(inStems),
            sampleRate = sampleRate,
            caps = caps,
            level = level,
            outId = outId,
            inId = inId,
            albumOrder = albumOrder,
            stemsExpected = outStems != null || inStems != null,
        )
        val ranked = Choreographer.rank(inputs).take(3)
        // Keep PREPARED identical to what will execute: the selector already validated this exact
        // script against this geometry and rate mode. Re-choosing here could hand the build a
        // different recipe than the one the preview promised.
        val planned = preferredScript?.takeIf {
            it.level == level && RecipeId.fromCode(it.recipeId) !in v2Prep.excludedRecipes &&
                (!it.outUsesStems || outStems != null) && (!it.inUsesStems || inStems != null)
        }
        val script = planned
            ?: (if (v2Prep.forcedRecipe != null) Choreographer.chooseRecipe(inputs, v2Prep.forcedRecipe) else null)
            ?: Choreographer.choose(inputs, v2Prep.forced)
            ?: Choreographer.chooseRecipe(inputs, null)
            ?: ChoreoSessionGlue.withoutStems(
                v2Prep, effectivePlan, LoopMix.Clock(effectivePlan), sampleRate,
                outA, inA, sungOut, sungIn, plannedRecipe = v2Prep.forcedRecipe,
            )
        return script to (script?.topCandidates ?: ranked)
    }

    private fun buildChoreoInputs(
        effectivePlan: LoopMix.Plan,
        v2Prep: ChoreoSessionGlue.Prep?,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
        outStems: StemView?,
        inStems: StemView?,
        sampleRate: Int,
        caps: Int,
        level: DjLevel,
        outId: String,
        inId: String,
        albumOrder: Boolean,
        stemsExpected: Boolean,
        excludedRecipes: Set<RecipeId> = v2Prep?.excludedRecipes ?: emptySet(),
    ): ChoreoInputs {
        val clock = LoopMix.Clock(effectivePlan)
        val echoHold = if (effectivePlan.echoTail) effectivePlan.out.beatSec * 8 else 0.0
        val outStop = clock.releaseAt + 0.05 + echoHold + effectivePlan.exitTailSeconds
        val tailRate = if (clock.beats >= 1) {
            val b0 = clock.beatTimes[clock.beats - 1]
            val b1 = clock.beatTimes[clock.beats]
            (effectivePlan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        val outHasTailKnot = !effectivePlan.outLoops && outStop > clock.releaseAt + 1e-6
        val pairKey = "$outId->$inId"
        val outStruct = v2Prep?.outStructure ?: com.music.bitchord.playback.smart.choreo.StructureView.best(null, outA)
        val inStruct = v2Prep?.inStructure ?: com.music.bitchord.playback.smart.choreo.StructureView.best(null, inA)
        val (outClock, inClock) = runCatching {
            val knots = LoopMixSession.buildDeckKnots(
                effectivePlan, clock, sampleRate, outA, inA, outHasTailKnot, outStop, tailRate,
                v2Prep?.straightRateMode, level,
            )
            val geometry = LoopMixSession.incomingGeometry(effectivePlan, clock, knots)
            val sr = sampleRate.toDouble()
            val outTrk = TrackClock { mix ->
                if (mix < 0.0) null
                else interpolateKnots(knots.outFrames, knots.outPositionsSec, mix * sr)?.let { u ->
                    if (effectivePlan.outLoops) LoopMix.physicalTrackTime(u, effectivePlan.out.loopStart, effectivePlan.out.loopEnd, 1e18) else u
                }
            }
            val inTrk = TrackClock { mix ->
                if (mix < 0.0) null
                else interpolateKnots(knots.inFrames, knots.inPositionsSec, mix * sr)?.let { u ->
                    if (geometry.loops) LoopMix.physicalTrackTime(u, effectivePlan.inn.loopStart, effectivePlan.inn.loopEnd, geometry.releaseTrack) else u
                }
            }
            outTrk to inTrk
        }.getOrDefault(null to null)

        return ChoreoInputs(
            plan = effectivePlan,
            clock = clock,
            out = outA,
            inn = inA,
            outStructure = outStruct,
            inStructure = inStruct,
            outStems = outStems,
            inStems = inStems,
            caps = caps,
            level = level,
            pairKey = pairKey,
            history = AutomixV2Choreo.history.recent(pairKey),
            recipeHistory = AutomixV2Choreo.history.recentRecipes(pairKey),
            seed = AutomixV2Choreo.seed,
            sungOut = sungOut,
            sungIn = sungIn,
            albumOrder = albumOrder,
            stemsExpected = stemsExpected,
            outClock = outClock,
            inClock = inClock,
            excludedRecipes = excludedRecipes,
        )
    }

    companion object {
        private const val DEFAULT_ANALYSIS_WAIT_MS = 15_000L
        private const val DEFAULT_FULL_PREFERRED_WAIT_MS = 3_000L
        private const val RECIPE_HISTORY_CAP = 12
        private val ABRUPT_RECIPE_IDS = setOf("F2", "F7", "F9")
        private val ACTIVE_EXECUTION_STATES = setOf(
            PrepState.BUILDING,
            PrepState.ARMED,
            PrepState.RUNNING,
        )

        internal fun newPrepExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
                }
                runnable.run()
            }, "djmix-prep").apply { isDaemon = true }
        }

        internal fun newStemExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
                }
                runnable.run()
            }, "djmix-stems").apply { isDaemon = true }
        }

        internal fun toCoreDjLevel(level: DjLevel): CoreDjLevel = when (level) {
            DjLevel.SUBTLE -> CoreDjLevel.SUBTLE
            DjLevel.CLUB -> CoreDjLevel.CLUB
            DjLevel.FESTIVAL -> CoreDjLevel.FESTIVAL
        }

        internal fun analysisLevelOf(analysis: TrackAnalysis): AnalysisLevel = when {
            !analysis.isUsable -> AnalysisLevel.NONE
            analysis.quality == TrackAnalysis.QUALITY_FULL -> AnalysisLevel.FULL
            else -> AnalysisLevel.HEAD_TAIL
        }

        internal fun toV3ForPlanner(
            trackId: String,
            analysis: TrackAnalysis,
            phraseMap: SongMap?,
            songMap: SongMap?,
            fallbackDurationSec: Double,
        ): TrackAnalysisV3 {
            val durSec = fallbackDurationSec.takeIf { it.isFinite() && it > 1.0 } ?: analysis.duration
            val normalizedAnalysis = if (durSec > 1.0 && durSec != analysis.duration) {
                analysis.copy(duration = durSec)
            } else analysis
            val v3 = TrackAnalysisV3Mapper.toV3(
                trackId = trackId,
                contentHash = "",
                analysis = normalizedAnalysis,
                phraseMap = phraseMap,
                songMap = songMap,
                modelVersions = emptyMap(),
            )
            if (normalizedAnalysis.isUsable && v3.beatsMs.isEmpty() && v3.bpm > 1.0 && v3.durationMs > 0L) {
                val beatMs = (60_000.0 / v3.bpm).roundToLong().coerceAtLeast(250L)
                val firstMs = (normalizedAnalysis.firstBeat.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
                    .let { (it * 1000.0).roundToLong().coerceIn(0L, v3.durationMs) }
                val beats = ArrayList<Long>()
                val downs = ArrayList<Long>()
                var t = firstMs
                var idx = 0
                while (t <= v3.durationMs && beats.size < 2048) {
                    beats += t
                    if (idx % 4 == 0) downs += t
                    t += beatMs
                    idx++
                }
                return v3.copy(
                    analysisLevel = if (v3.analysisLevel == AnalysisLevel.NONE) AnalysisLevel.HEAD_TAIL else v3.analysisLevel,
                    beatsMs = beats,
                    downbeatsMs = if (v3.downbeatsMs.isEmpty()) downs else v3.downbeatsMs,
                )
            }
            return v3
        }

        internal fun sliceLoopRegion(
            region: LoopRegion?,
            sampleRate: Int,
            fromSec: Double,
            toSec: Double,
        ): LoopRegion? {
            if (region == null || sampleRate <= 0 || region.left.isEmpty() ||
                region.left.size != region.right.size || toSec <= fromSec
            ) return null
            val frames = region.left.size
            val endSec = region.start + frames.toDouble() / sampleRate
            if (fromSec + 0.5 / sampleRate < region.start || toSec - 0.5 / sampleRate > endSec) return null
            val startOffset = ((fromSec - region.start) * sampleRate).roundToLong().toInt().coerceIn(0, frames)
            val endOffset = ((toSec - region.start) * sampleRate).roundToLong().toInt().coerceIn(startOffset, frames)
            if (endOffset <= startOffset) return null
            return LoopRegion(
                left = region.left.copyOfRange(startOffset, endOffset),
                right = region.right.copyOfRange(startOffset, endOffset),
                start = region.start + startOffset.toDouble() / sampleRate,
            )
        }

        internal fun sliceStemSet(
            stems: StemSet?,
            fromSec: Double,
            toSec: Double,
        ): StemSet? {
            if (stems == null || stems.sampleRate <= 0 || stems.frames <= 0 || toSec <= fromSec) return null
            val sr = stems.sampleRate
            val endSec = stems.regionStartSec + stems.frames.toDouble() / sr
            if (fromSec + 0.5 / sr < stems.regionStartSec || toSec - 0.5 / sr > endSec) return null
            val startOffset = ((fromSec - stems.regionStartSec) * sr).roundToLong().toInt().coerceIn(0, stems.frames)
            val endOffset = ((toSec - stems.regionStartSec) * sr).roundToLong().toInt().coerceIn(startOffset, stems.frames)
            val newFrames = endOffset - startOffset
            if (newFrames <= 0) return null
            return StemSet(
                sampleRate = sr,
                frames = newFrames,
                regionStartSec = stems.regionStartSec + startOffset.toDouble() / sr,
                left = stems.left.mapValues { (_, arr) -> arr.copyOfRange(startOffset, endOffset) },
                right = stems.right.mapValues { (_, arr) -> arr.copyOfRange(startOffset, endOffset) },
                stemCount = stems.stemCount,
                confidence = stems.confidence,
            )
        }

        internal fun barSeconds(bpm: Double, fallbackBeatSec: Double): Double =
            if (bpm.isFinite() && bpm > 1.0) (240.0 / bpm) else (fallbackBeatSec.takeIf { it > 0.05 } ?: 0.5) * 4.0

        internal fun analysisSignature(out: TrackAnalysis, inn: TrackAnalysis): String =
            "${out.status}:${out.quality}:${(out.bpm * 10).toInt()}|${inn.status}:${inn.quality}:${(inn.bpm * 10).toInt()}"

        private fun songMapsEquivalent(a: SongMap?, b: SongMap?): Boolean {
            if (a === b) return true
            if (a == null || b == null) return false
            return a.schema == b.schema &&
                a.trackKey == b.trackKey &&
                a.bpm == b.bpm &&
                a.beatsPerBar == b.beatsPerBar &&
                a.downbeats.contentEquals(b.downbeats) &&
                a.gridConfidence == b.gridConfidence &&
                a.tempoStable == b.tempoStable &&
                a.phraseAnchor == b.phraseAnchor &&
                a.phraseLen == b.phraseLen &&
                a.anchors.contentEquals(b.anchors) &&
                a.sections == b.sections &&
                a.sungBars.contentEquals(b.sungBars) &&
                a.vocalEnergy.contentEquals(b.vocalEnergy) &&
                a.firstVocalBar == b.firstVocalBar &&
                a.lastVocalBar == b.lastVocalBar &&
                a.startKind == b.startKind &&
                a.endingKind == b.endingKind &&
                a.contentEndSec == b.contentEndSec &&
                a.drops == b.drops &&
                a.builds == b.builds &&
                a.breakdowns == b.breakdowns &&
                a.chorusRepeats == b.chorusRepeats &&
                a.structureVersion == b.structureVersion
        }

        private fun computeStemOverlapWindows(
            effectivePlan: LoopMix.Plan?,
            outRegion: LoopRegion?,
            inRegion: LoopRegion?,
            outA: TrackAnalysis,
            inA: TrackAnalysis,
            sampleRate: Int,
            straightRateMode: StraightRateMode?,
            level: DjLevel,
            windowStartSec: Double,
            windowEndSec: Double,
            entryCueSec: Double,
        ): Pair<Pair<Double, Double>?, Pair<Double, Double>?> {
            val sr = sampleRate.coerceAtLeast(1).toDouble()
            val outBarSec = barSeconds(outA.bpm, effectivePlan?.out?.beatSec ?: 0.5)
            val inBarSec = barSeconds(inA.bpm, effectivePlan?.inn?.beatSec ?: 0.5)
            val outRegionEnd = outRegion?.let { it.start + it.left.size.toDouble() / sr } ?: 0.0
            val inRegionEnd = inRegion?.let { it.start + it.left.size.toDouble() / sr } ?: 0.0

            if (effectivePlan == null) {
                val span = (windowEndSec - windowStartSec).coerceAtLeast(4.0)
                val outBounds = outRegion?.let {
                    val from = (windowStartSec - outBarSec).coerceIn(it.start, outRegionEnd)
                    val to = (windowEndSec + outBarSec).coerceIn(from, outRegionEnd)
                    if (to > from + 0.05) from to to else it.start to outRegionEnd
                }
                val inBounds = inRegion?.let {
                    val from = (entryCueSec - inBarSec).coerceIn(it.start, inRegionEnd)
                    val to = (entryCueSec + span + inBarSec).coerceIn(from, inRegionEnd)
                    if (to > from + 0.05) from to to else it.start to inRegionEnd
                }
                return outBounds to inBounds
            }

            val clock = LoopMix.Clock(effectivePlan)
            val knots = runCatching {
                val echoHold = if (effectivePlan.echoTail) effectivePlan.out.beatSec * 8 else 0.0
                val outStop = clock.releaseAt + 0.05 + echoHold + effectivePlan.exitTailSeconds
                val tailRate = if (clock.beats >= 1) {
                    val b0 = clock.beatTimes[clock.beats - 1]
                    val b1 = clock.beatTimes[clock.beats]
                    (effectivePlan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
                } else 1.0
                val outHasTailKnot = !effectivePlan.outLoops && outStop > clock.releaseAt + 1e-6
                LoopMixSession.buildDeckKnots(
                    effectivePlan, clock, sampleRate, outA, inA,
                    outHasTailKnot, outStop, tailRate, straightRateMode, level,
                )
            }.getOrNull()

            val outOverlapStart = effectivePlan.out.loopStart
            val outOverlapEnd = if (effectivePlan.outLoops) {
                effectivePlan.out.loopEnd
            } else {
                val releasePos = knots?.let {
                    interpolateKnots(it.outFrames, it.outPositionsSec, clock.releaseAt * sr)
                } ?: (outOverlapStart + clock.releaseAt)
                maxOf(effectivePlan.out.loopEnd, releasePos)
            }

            val inOverlapStart = effectivePlan.inn.loopStart
            val inOverlapEnd = if (effectivePlan.inLoops) {
                effectivePlan.inn.loopEnd
            } else {
                val releasePos = knots?.let {
                    interpolateKnots(it.inFrames, it.inPositionsSec, clock.releaseAt * sr)
                } ?: (inOverlapStart + clock.releaseAt)
                maxOf(effectivePlan.inn.loopEnd, releasePos)
            }

            val outBounds = outRegion?.let {
                val from = (outOverlapStart - outBarSec).coerceIn(it.start, outRegionEnd)
                val to = (outOverlapEnd + outBarSec).coerceIn(from, outRegionEnd)
                if (to > from + 0.05) from to to else it.start to outRegionEnd
            }
            val inBounds = inRegion?.let {
                val from = (inOverlapStart - inBarSec).coerceIn(it.start, inRegionEnd)
                val to = (inOverlapEnd + inBarSec).coerceIn(from, inRegionEnd)
                if (to > from + 0.05) from to to else it.start to inRegionEnd
            }
            return outBounds to inBounds
        }

        private fun compositeStemSetForRegion(
            region: LoopRegion,
            overlapStems: StemSet,
            sampleRate: Int,
        ): StemSet {
            val totalFrames = region.left.size
            if (overlapStems.frames == totalFrames &&
                abs(overlapStems.regionStartSec - region.start) <= 0.5 / sampleRate.coerceAtLeast(1)
            ) {
                return overlapStems
            }
            val offset = ((overlapStems.regionStartSec - region.start) * sampleRate)
                .roundToLong().toInt().coerceIn(0, totalFrames)
            val copyLen = minOf(overlapStems.frames, totalFrames - offset).coerceAtLeast(0)
            val leftMap = HashMap<StemKind, FloatArray>(overlapStems.left.size)
            val rightMap = HashMap<StemKind, FloatArray>(overlapStems.right.size)
            for ((kind, srcL) in overlapStems.left) {
                val srcR = overlapStems.right[kind] ?: continue
                val dstL = if (kind == StemKind.OTHER) region.left.copyOf() else FloatArray(totalFrames)
                val dstR = if (kind == StemKind.OTHER) region.right.copyOf() else FloatArray(totalFrames)
                if (copyLen > 0) {
                    System.arraycopy(srcL, 0, dstL, offset, minOf(copyLen, srcL.size))
                    System.arraycopy(srcR, 0, dstR, offset, minOf(copyLen, srcR.size))
                }
                leftMap[kind] = dstL
                rightMap[kind] = dstR
            }
            return StemSet(
                sampleRate = overlapStems.sampleRate,
                frames = totalFrames,
                regionStartSec = region.start,
                left = leftMap,
                right = rightMap,
                stemCount = overlapStems.stemCount,
                confidence = overlapStems.confidence,
            )
        }

        private fun countCompletedAndRequiredStems(
            authoritativeV1: Boolean,
            planV1: TransitionPlanV1?,
            needOutStems: Boolean,
            needInStems: Boolean,
            outStems: StemSet?,
            inStems: StemSet?,
        ): Pair<Int, Int> {
            if (authoritativeV1 && planV1 != null) {
                val required = planV1.requiredStems.size
                val completed = planV1.requiredStems.count { req ->
                    val deckStems = if (req.deck == CoreDeckTarget.OUT) outStems else inStems
                    val sharedKind = when (req.kind) {
                        CoreStemKind.VOCALS -> StemKind.VOCALS
                        CoreStemKind.DRUMS -> StemKind.DRUMS
                        CoreStemKind.BASS -> StemKind.BASS
                        CoreStemKind.OTHER -> StemKind.OTHER
                    }
                    deckStems?.left?.containsKey(sharedKind) == true
                }
                return completed to required
            }
            val required = (if (needOutStems) 1 else 0) + (if (needInStems) 1 else 0)
            val completed = (if (outStems != null) 1 else 0) + (if (inStems != null) 1 else 0)
            return completed to required
        }

        internal fun paddedWindow(
            coreFrom: Double,
            coreTo: Double,
            padSec: Double,
            trackDurationSec: Double,
            maxRegionSec: Double = AutomixV2Choreo.MAX_STEM_REGION_SEC,
        ): Pair<Double, Double> {
            val maxEnd = if (trackDurationSec.isFinite() && trackDurationSec > 0.0) trackDurationSec else Double.POSITIVE_INFINITY
            val baseFrom = coreFrom.coerceIn(0.0, maxEnd)
            val baseTo = coreTo.coerceIn(baseFrom, maxEnd)
            val coreSpan = (baseTo - baseFrom).coerceAtLeast(0.0)
            if (coreSpan >= maxRegionSec) {
                return baseFrom to baseTo
            }
            val extraBudget = (maxRegionSec - coreSpan).coerceAtLeast(0.0)
            val eachSidePad = minOf(padSec.coerceAtLeast(0.0), extraBudget / 2.0)
            var from = (baseFrom - eachSidePad).coerceAtLeast(0.0)
            var to = minOf(maxEnd, baseTo + eachSidePad)
            val usedPad = (baseFrom - from) + (to - baseTo)
            val remainingPad = minOf(2.0 * padSec.coerceAtLeast(0.0), extraBudget) - usedPad
            if (remainingPad > 1e-6) {
                if (from <= 1e-6 && to < maxEnd) {
                    to = minOf(maxEnd, to + remainingPad)
                } else if (to >= maxEnd - 1e-6 && from > 0.0) {
                    from = (from - remainingPad).coerceAtLeast(0.0)
                }
            }
            return from to to
        }

        private fun dummyStemView(sampleRate: Int): StemView =
            StemView(
                stemCount = 4,
                confidence = 1f,
                regionStartSec = 0.0,
                sampleRate = sampleRate,
                frames = sampleRate * 60,
                rms = { _, _, _ -> 0.5 },
            )

        private fun interpolateKnots(frames: DoubleArray, positions: DoubleArray, frame: Double): Double? {
            if (frames.isEmpty() || frames.size != positions.size) return null
            if (frame <= frames[0]) return positions[0]
            val last = frames.size - 1
            if (frame >= frames[last]) {
                val denom = maxOf(1.0, frames[last] - frames[maxOf(0, last - 1)])
                return positions[last] + (frame - frames[last]) / denom *
                    (positions[last] - positions[maxOf(0, last - 1)])
            }
            var lo = 0
            var hi = last
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (frames[mid] <= frame) lo = mid else hi = mid
            }
            val span = frames[hi] - frames[lo]
            val f = if (span <= 0.0) 0.0 else (frame - frames[lo]) / span
            return positions[lo] + (positions[hi] - positions[lo]) * f
        }
    }
}
