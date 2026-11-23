package com.music.bitchord.playback

import kotlin.math.roundToLong

/**
 * Adaptive native prime lead : 45 s when the incoming stream prefix
 * is ready, else 35-45 s clamped off [blendArmLeadMs] so primeLoopDecks arms
 * early enough to win single-clock capture before classic fallback thresholds.
 */
internal const val ADAPTIVE_PRIME_LEAD_MAX_MS = 45_000L
internal const val ADAPTIVE_PRIME_LEAD_MIN_MS = 35_000L

internal fun adaptivePrimeLeadMs(nextStreamReady: Boolean, blendArmLeadMs: Long): Long =
    if (nextStreamReady) ADAPTIVE_PRIME_LEAD_MAX_MS
    else blendArmLeadMs.coerceIn(ADAPTIVE_PRIME_LEAD_MIN_MS, ADAPTIVE_PRIME_LEAD_MAX_MS)

/**
 * Cold-loop yield headroom (, Bug 4): once both tracks are analysed, a `LoopMix.plan`
 * that returned `null` is deterministically ineligible for that pair. Waiting for it until
 * `10s-14s` before `contentEnd` starves an 8-16 bar (`19-38s`) beatmix. Yield to
 * `PhrasePlanner` -> `LoopMix.bridge` at the earlier of `30s` before the first phrase/outro
 * exit or `max(28s, 10xbarSec)` before `contentEnd`, so the phrase mix has a full 8 bars to
 * arm and fire. Pure scalar; unit-tested in `Djmix18AppHandoffTest`.
 *
 * @param contentEndMs outgoing `contentEndTime` in ms, or `durationMs` when unknown.
 * @param currentPositionMs playhead in ms.
 * @param outBpm outgoing BPM for `10xbarSec` (`barSec = 240/bpm`); `<= 0` falls back to `28s`.
 * @param earliestExitMs earliest phrase/outro exit `releaseSec` in ms, or `null` when unknown.
 * Pure scalar; unit-tested in `Djmix18AppHandoffTest`.
 */
internal fun shouldYieldColdLoopForContentEnd(
    contentEndMs: Long,
    currentPositionMs: Long,
    outBpm: Double,
    earliestExitMs: Long?,
): Boolean {
    val barSec = if (outBpm.isFinite() && outBpm > 1.0) 240.0 / outBpm else 0.0
    val byBarsMs = (10.0 * barSec * 1000.0).roundToLong().coerceAtLeast(28_000L)
    val byContentEndMs = contentEndMs - currentPositionMs
    if (byContentEndMs in 0L..byBarsMs) return true
    if (earliestExitMs != null) {
        val byExitMs = earliestExitMs - currentPositionMs
        if (byExitMs in 0L..30_000L) return true
    }
    return false
}

/**
 * Exact handover target (, Bug 2): the incoming deck's heard position is truth when
 * it is valid (a rendered block, at or past `plan.inn.loopStart - 0.05`), else the clock's
 * `handoverTrackSec`. With `incomingHoldSec > 0` both land at
 * `inn.loopStart + (elementAt - incomingHoldSec)` -- never `inn.loopStart + elementAt`.
 * Pure scalar; unit-tested in `Djmix18AppHandoffTest`.
 */
internal fun handoverTargetSec(
    heardInSec: Double?,
    clockHandoverSec: Double,
    innLoopStartSec: Double,
): Double {
    val heard = heardInSec
    return if (heard != null && heard.isFinite() && heard >= innLoopStartSec - 0.05) heard
    else clockHandoverSec
}

/**
 * Lane1 (pre-splice identity, pure + JVM-testable): returns the abort
 * reason when the live queue no longer matches the planned pair, or null
 * when the splice may proceed. A queue edit inside the arm window changes
 * [liveNextId]; a scrub/replan bumps [planGeneration] past
 * [capturedGeneration]. Both must abort BEFORE any render, so a stale pair
 * never becomes a render-then-abandon.
 *
 * A negative [capturedGeneration] means no capture was made (legacy arm
 * path); the generation check is then skipped rather than failing closed on
 * data the older call site never provided.
 */
internal fun preSpliceIdentityReason(
    expectedInId: String?,
    liveNextId: String?,
    expectedOutId: String?,
    currentOutId: String?,
    capturedGeneration: Long,
    planGeneration: Long,
): String? = when {
    expectedInId != null && expectedInId != liveNextId -> "pre-splice-identity-mismatch"
    expectedOutId != null && currentOutId != null && expectedOutId != currentOutId -> "pre-splice-identity-mismatch"
    capturedGeneration >= 0L && capturedGeneration != planGeneration -> "pre-splice-plan-generation-stale"
    else -> null
}
