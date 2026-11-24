package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService

/**
 * Narrow host seam supplying state, executors, and playback preferences to [PreparedSessionManager]
 * without exposing [PairPreparer] internals or controller references.
 */
internal interface PreparedSessionHost {
    val stateFlow: MutableStateFlow<PreparedPair?>
    val scope: CoroutineScope
    val executor: ExecutorService
    fun isReleased(): Boolean
    fun currentGeneration(): Long
    fun nowMs(): Long
    fun mixExecutor(): MixExecutor?
    fun inLevel(outId: String, inId: String): Float
    fun highQuality(): Boolean
    fun publishIfCurrent(gen: Long, transform: (PreparedPair) -> PreparedPair)
}

/**
 * Owns off-thread `LoopMixSession` construction, single-winner consumption, and asynchronous
 * cleanup of superseded native sessions for [PairPreparer] (M9 slice P-2).
 */
internal class PreparedSessionManager(private val host: PreparedSessionHost) {

    /** Atomically claims and removes the prepared [LoopMixSession] matching the active pair and generation. */
    fun consumePreparedSession(
        outId: String,
        inId: String,
        expectedGeneration: Long,
        useNoStemVariant: Boolean = false,
    ): LoopMixSession? {
        val state = host.stateFlow
        while (true) {
            val cur = state.value ?: return null
            if (cur.outId != outId || cur.inId != inId || cur.generation != expectedGeneration) return null
            val session = cur.preparedSession ?: return null
            if (cur.preparedSessionNoStem != useNoStemVariant) return null
            if (state.compareAndSet(cur, cur.copy(preparedSession = null))) {
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
        val inLevel = runCatching { host.inLevel(frozen.outId, frozen.inId) }
            .getOrDefault(1f).takeIf { it.isFinite() && it > 0f } ?: 1f
        val highQuality = runCatching { host.highQuality() }.getOrDefault(true)
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
        host.publishIfCurrent(frozen.generation) { cur ->
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
        if (host.isReleased()) return
        runCatching {
            host.executor.execute {
                if (host.isReleased() || host.currentGeneration() != targetGeneration) return@execute
                val cur = host.stateFlow.value ?: return@execute
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
                        host.publishIfCurrent(targetGeneration) { next }
                        next
                    } else cur
                } else cur

                if (pair.script == null) {
                    host.mixExecutor()?.dispatch(MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, "no_script_for_renderer", recoverable = true))
                    return@execute
                }
                if (!LoopMixRenderer.available) {
                    // Without native JNI, live MixExecutor enters Failed -> classic fallback;
                    // standalone PairPreparer keeps PREPARED cleanly for JVM unit tests.
                    host.mixExecutor()?.dispatch(MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, "renderer_unavailable", recoverable = false))
                    return@execute
                }
                val buildStarted = host.nowMs()
                var refusalReason: String? = null
                var usedNoStem = useNoStemVariant
                var session = prepareRenderer(pair, useNoStemVariant = usedNoStem) { refusalReason = it }
                if (session == null && !usedNoStem && pair.transitionPlanV1?.hasNoStemVariant == true) {
                    usedNoStem = true
                    refusalReason = null
                    session = prepareRenderer(pair.copy(useNoStemVariant = true), useNoStemVariant = true) { refusalReason = it }
                }
                if (host.isReleased() || host.currentGeneration() != targetGeneration) {
                    closeSessionAsync(session)
                    return@execute
                }
                if (session == null) {
                    host.mixExecutor()?.dispatch(
                        MixEvent.RendererError(
                            FailureKind.RENDERER_PREPARE_ERROR,
                            refusalReason ?: "renderer_prepare_failed",
                            recoverable = pair.transitionPlanV1?.hasNoStemVariant == true,
                        ),
                    )
                    return@execute
                }
                val buildMs = (host.nowMs() - buildStarted).coerceAtLeast(0L)
                host.publishIfCurrent(targetGeneration) { s ->
                    s.copy(
                        state = if (s.state.ordinal < PrepState.PREPARED.ordinal) PrepState.PREPARED else s.state,
                        useNoStemVariant = usedNoStem,
                        preparedSession = session,
                        preparedSessionNoStem = usedNoStem,
                        timings = s.timings.copy(buildMs = buildMs),
                    )
                }
                host.mixExecutor()?.dispatch(MixEvent.RendererPrepared(planId, targetGeneration, usedNoStem))
            }
        }
    }

    /** Closes any stale prepared session owned by a prior generation. */
    fun closeSupersededSession(pair: PreparedPair?, newGen: Long) {
        val session = pair?.preparedSession ?: return
        if (pair.generation != newGen) closeSessionAsync(session)
    }

    /** Closes [session] asynchronously on [Dispatchers.IO]. */
    fun closeSessionAsync(session: LoopMixSession?) {
        if (session == null) return
        host.scope.launch(Dispatchers.IO) {
            runCatching { session.close() }
        }
    }
}
