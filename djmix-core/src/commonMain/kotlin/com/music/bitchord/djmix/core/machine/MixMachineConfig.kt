package com.music.bitchord.djmix.core.machine

import kotlinx.serialization.Serializable

/**
 * Pure configuration constants for [MixMachine], serving as the single source of truth
 * for commit lead, countdown tolerance, moved-banner duration, and remix cutoff.
 */
@Serializable
data class MixMachineConfig(
    /** Number of bars before frozen exit at which the pair must be READY to arm. */
    val commitPointBars: Int = 4,
    /** Minimum commit lead in milliseconds before the frozen exit. */
    val minCommitLeadMs: Long = 6_000L,
    /** Tolerance window (ms) around countdown 0 within which firing is triggered cleanly. */
    val countdownToleranceMs: Long = 50L,
    /** Duration (ms) for which the MOVED chip message is displayed before switching to READY text. */
    val movedBannerDurationMs: Long = 3_000L,
    /** Minimum remaining lead (ms) required before frozen exit to permit "Try another mix". */
    val remixMinLeadMs: Long = 20_000L,
) {
    init {
        require(commitPointBars >= 1) { "commitPointBars must be >= 1, got $commitPointBars" }
        require(minCommitLeadMs >= 0L) { "minCommitLeadMs must be >= 0, got $minCommitLeadMs" }
        require(countdownToleranceMs >= 0L) { "countdownToleranceMs must be >= 0, got $countdownToleranceMs" }
        require(movedBannerDurationMs >= 0L) { "movedBannerDurationMs must be >= 0, got $movedBannerDurationMs" }
        require(remixMinLeadMs >= 0L) { "remixMinLeadMs must be >= 0, got $remixMinLeadMs" }
    }

    /** Commit lead before the frozen exit in milliseconds for [barDurationMs]. */
    fun commitLeadMs(barDurationMs: Long): Long =
        maxOf(commitPointBars * barDurationMs, minCommitLeadMs)

    companion object {
        /** Default AE3 state-machine configuration. */
        val DEFAULT: MixMachineConfig = MixMachineConfig()
    }
}
