package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.machine.MixState

/** Executor-lock confined; never reconstructs a completion from the current/last plan. */
internal class ImplicitTasteTracker {
    data class CompletedMix(
        val planId: String,
        val generation: Long,
        val recipeId: String,
        val outTrackId: String,
        val inTrackId: String,
        val completedAtMs: Long,
        val tasteEpoch: Long,
    )

    private var pending: CompletedMix? = null
    private var latestCompletedGeneration: Long? = null

    val hasPendingCompletion: Boolean get() = pending != null

    /** The generation watermark survives consumption, expiry, and invalidation. */
    fun recordCompletion(generation: Long, completed: CompletedMix?) {
        val latest = latestCompletedGeneration
        if (latest != null && generation <= latest) return
        latestCompletedGeneration = generation
        pending = completed?.takeIf {
            it.generation == generation && generation >= 0L && it.tasteEpoch >= 0L &&
                it.planId.isNotBlank() && it.recipeId.isNotBlank() &&
                it.outTrackId.isNotBlank() && it.inTrackId.isNotBlank()
        }
    }

    /** Observe every admitted event; a rejected/consumed window cannot later revive. */
    fun observe(
        state: MixState,
        nowMs: Long?,
        tasteEpoch: Long?,
        executorCurrent: Boolean,
        learningAllowed: (String) -> Boolean,
        userSkip: Boolean,
    ): CompletedMix? {
        val completed = pending ?: return null
        if (!executorCurrent || nowMs == null || tasteEpoch != completed.tasteEpoch ||
            nowMs < completed.completedAtMs || nowMs - completed.completedAtMs !in 0L..EARLY_SKIP_WINDOW_MS ||
            !matchesAudibleTrack(state, completed) || !learningAllowed(completed.recipeId)
        ) {
            invalidate()
            return null
        }
        if (!userSkip) return null
        // Claim before mutation: callback failures and duplicate skips cannot replay it.
        pending = null
        return completed
    }

    fun invalidate() {
        pending = null
    }

    private fun matchesAudibleTrack(state: MixState, completed: CompletedMix): Boolean = when (state) {
        is MixState.Done -> state.generation == completed.generation &&
            state.plan.generation == completed.generation && state.plan.planId == completed.planId &&
            state.plan.recipeId == completed.recipeId && state.pair.outTrackId == completed.outTrackId &&
            state.pair.inTrackId == completed.inTrackId && state.plan.outTrackId == completed.outTrackId &&
            state.plan.inTrackId == completed.inTrackId
        is MixState.Preparing, is MixState.Ready, is MixState.Armed,
        is MixState.Moved, is MixState.LastResort ->
            state.generation > completed.generation && state.pair?.outTrackId == completed.inTrackId
        // Running/HandingOver lack a new clean completion; inactive/failure states lack an audible identity.
        else -> false
    }

    private companion object {
        const val EARLY_SKIP_WINDOW_MS = 15_000L
    }
}
