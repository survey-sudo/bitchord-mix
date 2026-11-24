package com.music.bitchord.djmix.core.contracts

import kotlinx.serialization.Serializable

/** Canonical telemetry event types emitted by `MixMachine` and `MixExecutor` (schema v3). */
@Serializable
enum class TelemetryEventType(
    /** Stable dotted event identifier used in JSONL telemetry records and log parsers. */
    val wireName: String,
) {
    MIX_PREP_STARTED("mix.prep.started"),
    MIX_PLAN_FROZEN("mix.plan.frozen"),
    MIX_READY("mix.ready"),
    MIX_ARMED("mix.armed"),
    MIX_FIRE("mix.fire"),
    MIX_TIMING("mix.timing"),
    MIX_MOVED("mix.moved"),
    MIX_LAST_RESORT("mix.last_resort"),
    MIX_HANDOVER("mix.handover"),
    MIX_DONE("mix.done"),
    MIX_COUNTDOWN_BROKEN("mix.countdown.broken"),
    MIX_CLASSIC("mix.classic"),
    MIX_SUSPENDED("mix.suspended"),
    MIX_FAILED("mix.failed"),
    MIX_REFUSED("mix.refused"),
    MIX_REMIXED("mix.remixed"),
    MIX_THERMAL_DEGRADED("mix.thermal.degraded"),
}

/**
 * Versioned automix telemetry event (`schema = 3`) stamped with [ENGINE_VERSION],
 * [planId], [generation], and [state].
 */
@Serializable
data class MixTelemetryV3(
    val schema: Int = 3,
    val engine: String = ENGINE_VERSION,
    val timestampMs: Long,
    val eventType: TelemetryEventType,
    val planId: String? = null,
    val generation: Long = 0L,
    val state: String,
    val outId: String = "",
    val inId: String = "",
    val djLevel: DjLevel? = null,
    val path: ExecutionPath? = null,
    val recipeId: String? = null,
    val countdownBroken: Boolean? = null,
    val analysisMs: Long? = null,
    val planMs: Long? = null,
    val stemMsOut: Long? = null,
    val stemMsIn: Long? = null,
    val buildMs: Long? = null,
    val preparedLeadSec: Double? = null,
    val armLeadMs: Long? = null,
    val reason: RefusalReason? = null,
    val detail: String? = null,
) {
    init {
        require(schema == 3) { "MixTelemetryV3 requires schema == 3, got $schema" }
    }
}
