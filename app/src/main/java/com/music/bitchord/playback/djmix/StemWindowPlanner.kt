package com.music.bitchord.playback.djmix

import androidx.media3.common.MediaItem
import com.music.bitchord.djmix.core.contracts.DeckTarget as CoreDeckTarget
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.StemKind as CoreStemKind
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.StemKind
import com.music.bitchord.playback.smart.StemSet
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.AutomixV2Choreo
import com.music.bitchord.playback.smart.choreo.ChoreoCaps
import com.music.bitchord.playback.smart.choreo.ChoreoScript
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.choreo.Choreographer
import com.music.bitchord.playback.smart.choreo.DeckTarget
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.choreo.RecipeId
import com.music.bitchord.playback.smart.choreo.RecipeStep
import com.music.bitchord.playback.smart.choreo.Recipes
import com.music.bitchord.playback.smart.choreo.StemAdapter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Narrow host seam providing decode, stem separation, planning, and state publishing services
 * to [StemWindowPlanner] without coupling back to [PairPreparer].
 */
internal interface StemWindowHost {
    val scope: CoroutineScope
    val mainDispatcher: CoroutineDispatcher
    val stemExecutor: ExecutorService
    val stemLock: Any
    fun isAborted(gen: Long): Boolean
    fun nowMs(): Long
    fun positionSec(): Double
    fun songMapFor(trackId: String, analysis: TrackAnalysis): SongMap?
    fun sungFor(trackId: String): List<LoopMix.Span>?
    fun canUseStems(): Boolean
    fun capabilities(): Int
    fun recentRecipeIdsSnapshot(): List<String>
    fun currentTransitionsSinceLastAbrupt(): Int
    fun tasteWeights(): Map<String, Double>
    fun selectPlan(input: PlanInput): SelectedPlan?
    fun resolveTransitionPlanV1(
        selected: SelectedPlan,
        request: PrepRequest,
        gen: Long,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        script: ChoreoScript?,
    ): TransitionPlanV1?
    fun decodeRegion(track: PrepTrack, fromSec: Double, toSec: Double, sampleRate: Int): LoopRegion?
    fun separateStems(
        trackId: String,
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        regionStartSec: Double,
        shouldAbort: () -> Boolean,
    ): StemSet?
    fun publishIfCurrent(gen: Long, transform: (PreparedPair) -> PreparedPair)
    fun mixExecutor(): MixExecutor?
    fun onArtworkPrefetch(item: MediaItem)
    fun prepareRendererIfCurrent(gen: Long, useNoStemVariant: Boolean)
}

/**
 * Containment-aware PCM decode planning, overlap-window stem separation, and final pair
 * publication extracted from [PairPreparer] (M9 slice P-3).
 */
internal class StemWindowPlanner(private val host: StemWindowHost) {

    /** Runs stages PLANNED -> decode -> STEMMING -> PREPARED for [request] at generation [gen]. */
    fun planDecodeAndLaunchStems(
        gen: Long,
        request: PrepRequest,
        initialTimings: PairTimings,
        rawOut: TrackAnalysis,
        rawIn: TrackAnalysis,
        reuseFrom: PreparedPair?,
    ) {
        fun isAborted(): Boolean = host.isAborted(gen)

        val outMap = if (rawOut.isUsable) host.songMapFor(request.outTrack.id, rawOut) else null
        val inMap = if (rawIn.isUsable) host.songMapFor(request.inTrack.id, rawIn) else null
        val analysisMs = (host.nowMs() - initialTimings.prepStartMs).coerceAtLeast(0L)
        val sig = PairPreparer.analysisSignature(rawOut, rawIn)
        // Physical durations come from what plays; musical evidence remains intact.
        val outA = rawOut.copy(duration = request.outTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: rawOut.duration)
        val inA = rawIn.copy(duration = request.inTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: rawIn.duration)
        val sungOut = host.sungFor(request.outTrack.id)
        val sungIn = host.sungFor(request.inTrack.id)

        // --- Stage 2: PLANNED ---
        val planStartMs = host.nowMs()
        val caps = host.capabilities()
        val stemsAllowed = host.canUseStems() && AutomixV2Choreo.useStems &&
            ChoreoCaps.has(caps, ChoreoCaps.STEMS) && !request.preferNoStemVariant
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
            caps = caps,
            recentRecipeIds = host.recentRecipeIdsSnapshot(),
            transitionsSinceLastAbrupt = host.currentTransitionsSinceLastAbrupt(),
            minExitMs = request.minExitMs,
            preferNoStemVariant = request.preferNoStemVariant,
            tasteWeights = host.tasteWeights(),
            machineGeneration = gen,
        )
        val selected = host.selectPlan(input)
        val planMs = (host.nowMs() - planStartMs).coerceAtLeast(0L)
        if (isAborted()) return

        if (selected == null || (selected.plan == null && selected.exitWindowSec == null)) {
            val reason = selected?.fallbackReason?.ifBlank { "no-v2-plan" } ?: "no-v2-plan"
            host.publishIfCurrent(gen) { cur ->
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

        val planV1 = host.resolveTransitionPlanV1(selected, request, gen, outA, inA, selected.previewScript)
        if (planV1 != null) {
            host.mixExecutor()?.dispatch(MixEvent.PlanFrozen(planV1, gen))
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
        host.publishIfCurrent(gen) { plannedPair }

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
            ?: host.decodeRegion(request.outTrack, outBounds.first, outBounds.second, request.sampleRate)
        if (isAborted()) return
        host.publishIfCurrent(gen) { it.copy(outRegion = outRegion) }

        val inRegion = sliceLoopRegion(reusableSamePair?.inRegion, request.sampleRate, inBounds.first, inBounds.second)
            ?: host.decodeRegion(request.inTrack, inBounds.first, inBounds.second, request.sampleRate)
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
            host.publishIfCurrent(gen) {
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
        host.publishIfCurrent(gen) { cur ->
            cur.copy(
                state = PrepState.STEMMING,
                outRegion = outRegion,
                inRegion = inRegion,
            )
        }
        if (planV1 != null) {
            host.mixExecutor()?.dispatch(MixEvent.RegionsReady(planV1.planId, gen))
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
                    caps = v2Prep?.caps ?: host.capabilities(),
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
            host.stemExecutor.execute {
                if (isAborted()) return@execute
                var outStems = slicedOutStems
                var inStems = slicedInStems
                var stemMsOut = 0L
                var stemMsIn = 0L

                if (needSepOut && outRegion != null && outStemInput != null) {
                    val t0 = host.nowMs()
                    val rawOutStems = synchronized(host.stemLock) {
                        if (isAborted()) null
                        else host.separateStems(
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
                    stemMsOut = (host.nowMs() - t0).coerceAtLeast(0L)
                }
                if (isAborted()) return@execute

                if (needSepIn && inRegion != null && inStemInput != null) {
                    val t0 = host.nowMs()
                    val rawInStems = synchronized(host.stemLock) {
                        if (isAborted()) null
                        else host.separateStems(
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
                    stemMsIn = (host.nowMs() - t0).coerceAtLeast(0L)
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
        if (host.isAborted(gen)) return
        val effectiveCaps = if (request.preferNoStemVariant) {
            (v2Prep?.caps ?: host.capabilities()) and ChoreoCaps.STEMS.inv()
        } else {
            v2Prep?.caps ?: host.capabilities()
        }
        val (script, top3) = ChoreoScriptBuilder.chooseFinalScript(
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
        if (host.isAborted(gen)) return

        if (script == null) {
            host.publishIfCurrent(gen) {
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
        val leadSec = (windowStartSec - host.positionSec()).coerceAtLeast(0.0)
        val readyAtMs = host.nowMs()

        host.publishIfCurrent(gen) { cur ->
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
            host.scope.launch(host.mainDispatcher) {
                if (!host.isAborted(gen)) host.onArtworkPrefetch(item)
            }
        }

        if (planV1 != null) {
            val activeExecutor = host.mixExecutor()
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
                host.prepareRendererIfCurrent(gen, usedNoStemVariant)
            }
        }
    }

    companion object {
        /** Computes the OUT/IN `[fromSec, toSec]` PCM decode windows padded around the planned transition. */
        internal fun computeDecodeWindows(
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

        /** Determines which deck sides require stem separation based on top-ranked candidate recipes. */
        internal fun requiredStemSides(
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
            val inputs = ChoreoScriptBuilder.buildChoreoInputs(
                effectivePlan = effectivePlan,
                v2Prep = v2Prep,
                outA = outA,
                inA = inA,
                sungOut = sungOut,
                sungIn = sungIn,
                outStems = ChoreoScriptBuilder.dummyStemView(sampleRate),
                inStems = ChoreoScriptBuilder.dummyStemView(sampleRate),
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
                        (it is RecipeStep.LaneCurve && it.kind.isStem) ||
                            (it is RecipeStep.SwapLows && it.useBassStems)
                    }
                if (needsAny) {
                    if (recipe.requiresStems > 0 || recipe.steps.any {
                            (it is RecipeStep.LaneCurve &&
                                it.deck == DeckTarget.OUT && it.kind.isStem) ||
                                (it is RecipeStep.SwapLows && it.useBassStems)
                        }
                    ) {
                        needOut = true
                    }
                    if ((recipe.requiresStems > 0 && id != RecipeId.C3 && id != RecipeId.F4) || recipe.steps.any {
                            (it is RecipeStep.LaneCurve &&
                                it.deck == DeckTarget.IN && it.kind.isStem) ||
                                (it is RecipeStep.SwapLows && it.useBassStems)
                        }
                    ) {
                        needIn = true
                    }
                }
            }
            return needOut to needIn
        }

        /** Slices an already-decoded [LoopRegion] if it strictly covers `[fromSec, toSec]`. */
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

        /** Slices an existing [StemSet] to `[fromSec, toSec]` when contained inside its bounds. */
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

        /** Computes 4-beat bar duration in seconds from [bpm] or a positive [fallbackBeatSec]. */
        internal fun barSeconds(bpm: Double, fallbackBeatSec: Double): Double =
            if (bpm.isFinite() && bpm > 1.0) (240.0 / bpm) else (fallbackBeatSec.takeIf { it > 0.05 } ?: 0.5) * 4.0

        /** Narrows SCNet stem separation bounds to the overlap window +/- 1 bar on each deck. */
        internal fun computeStemOverlapWindows(
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
                    ChoreoScriptBuilder.interpolateKnots(it.outFrames, it.outPositionsSec, clock.releaseAt * sr)
                } ?: (outOverlapStart + clock.releaseAt)
                maxOf(effectivePlan.out.loopEnd, releasePos)
            }

            val inOverlapStart = effectivePlan.inn.loopStart
            val inOverlapEnd = if (effectivePlan.inLoops) {
                effectivePlan.inn.loopEnd
            } else {
                val releasePos = knots?.let {
                    ChoreoScriptBuilder.interpolateKnots(it.inFrames, it.inPositionsSec, clock.releaseAt * sr)
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

        /** Composites overlap-window [overlapStems] back onto full-region [region] geometry. */
        internal fun compositeStemSetForRegion(
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

        /** Counts completed vs. required stems under either V1 contract or heuristic fallback. */
        internal fun countCompletedAndRequiredStems(
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
                    val sharedKind = when (req.stem) {
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

        /** Pads `[coreFrom, coreTo]` symmetrically within `[0, trackDurationSec]` and [maxRegionSec]. */
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
    }
}
