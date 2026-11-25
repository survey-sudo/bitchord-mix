package com.music.bitchord.playback.smart

import com.music.bitchord.data.model.QueueEntryIdentity
import com.music.bitchord.playback.smart.choreo.DjLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One side of the inspected pair, kept across the session's metadata handoff. */
data class MixInspectorTrack(
    val mediaId: String,
    val title: String,
    val artist: String = "",
    val identity: QueueEntryIdentity? = null,
)

/**
 * Identifies one preparation, not just two songs. The engine advances [generation]
 * for a new pair or a remix, so a sheet held on an older transition cannot remix
 * whatever happens to be next now.
 */
data class MixInspectorKey(val generation: Long, val outId: String, val inId: String)

/** An engine-ranked candidate. [score] is the raw recipe score, not a percentage. */
data class MixInspectorRecipeScore(
    val recipeId: String,
    val recipeName: String,
    val score: Double,
    val reason: String = "",
)

/** Shared vocabulary for preparation and execution; it has no Android dependencies. */
enum class MixInspectorPhase {
    IDLE,
    PREPARING_MODELS,
    ANALYZING,
    PLANNED,
    STEMMING,
    PREPARED,
    BUILDING,
    ARMED,
    RUNNING,
    DONE,
    CANCELLED,
}

/**
 * A small, read-only snapshot of the real transition, separate from artwork and
 * waveform progress. The controller publishes at state/timing changes, not on
 * the audio thread or on every audio-clock tick.
 *
 * A null timing means the step has not been measured or completed. A measured
 * zero remains zero, including cache hits and skipped work. [prepStartMs] uses
 * the engine's monotonic clock; it is not a wall-clock date. [preparedLeadSec]
 * and [armLeadMs] retain the engine's measured lead, including a negative lead.
 *
 * [path] is the actual route built or used, never a planning guess. While that
 * is unknown, the intended route may be published separately in [plannedPath].
 * [stemCount] is the engine's 4/2/0 mode, not the sum of both decks' stem counts.
 * [stemsReady] is reported by the engine rather than inferred from the phase.
 */
data class MixInspectorState(
    val generation: Long,
    val current: MixInspectorTrack,
    val next: MixInspectorTrack,
    val level: DjLevel,
    val phase: MixInspectorPhase = MixInspectorPhase.IDLE,
    val recipeId: String? = null,
    val recipeName: String? = null,
    val topCandidates: List<MixInspectorRecipeScore> = emptyList(),
    val reason: String? = null,
    val path: String? = null,
    val plannedPath: String? = null,
    val stemsReady: Boolean? = null,
    val stemCount: Int? = null,
    val prepStartMs: Long? = null,
    val analysisMs: Long? = null,
    val planMs: Long? = null,
    val stemMsOut: Long? = null,
    val stemMsIn: Long? = null,
    val preparedLeadSec: Double? = null,
    val buildMs: Long? = null,
    val armLeadMs: Long? = null,
    val watchdog: Boolean? = null,
    val fxCount: Int? = null,
    val effects: List<String> = emptyList(),
    val fallbackReason: String? = null,
    /** The engine owns the deadline and allowed-recipe checks. The UI never estimates this. */
    val remixAvailable: Boolean = false,
    val remixUnavailableReason: String? = null,
    /** Actual score delta of the selected frozen plan. */
    val tasteAdjustment: Double = 0.0,
    /** Effective playback-owned policy; an unwired publisher fails closed. */
    val learningEnabled: Boolean = false,
) {
    val key: MixInspectorKey get() = MixInspectorKey(generation, current.mediaId, next.mediaId)
    val mixActive: Boolean get() = phase == MixInspectorPhase.RUNNING
}

/**
 * A lightweight request on the controller/UI thread. True means accepted for
 * background preparation, not that the replacement recipe has finished. The
 * engine must recheck the key, time remaining and allowed alternatives before
 * changing a plan, and publish the new preparation with a fresh generation.
 */
fun interface MixInspectorRemixAction {
    fun remixCurrentTransition(key: MixInspectorKey): Boolean
}

/**
 * In-process feed shared by Android and JVM consumers. Playback owns publishing
 * and installs/removes its remix action with its lifecycle. UI and settings
 * facades expose only [state]; no controller, decoder or stem buffers cross it.
 */
object MixInspectorFeed {
    private val mutableState = MutableStateFlow<MixInspectorState?>(null)
    val state: StateFlow<MixInspectorState?> = mutableState.asStateFlow()

    @Volatile
    private var remixAction: MixInspectorRemixAction? = null

    fun publish(value: MixInspectorState?) {
        // Snapshot the small lists: retaining an engine-owned mutable ranking
        // would let a past transition change underneath a reader.
        mutableState.value = value?.copy(
            topCandidates = value.topCandidates.take(3).toList(),
            effects = value.effects.toList(),
        )
    }

    /** Called on the controller thread; pass null when that controller is released. */
    fun setRemixAction(action: MixInspectorRemixAction?) {
        remixAction = action
    }

    fun remixCurrentTransition(key: MixInspectorKey): Boolean {
        val current = state.value ?: return false
        if (!current.remixAvailable || current.key != key) return false
        return remixAction?.remixCurrentTransition(key) ?: false
    }
}
