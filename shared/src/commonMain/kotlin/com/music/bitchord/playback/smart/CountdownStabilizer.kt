package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Single authoritative countdown commit & anti-oscillation lock (djmix26, Task 2A).
 *
 * Governs what the UI (`describeAutomix`, `smartTransitionWindow`) and scheduler
 * commit to once a countdown is visible or inside the commit window (`<= 45 s`).
 *
 * Invariants:
 * 1. Once `(pairKey, scrubEpoch)` has a shown/locked countdown inside the commit
 *    window, its `targetStartMs` cannot jump by `> 1500 ms` (e.g. on `HEAD -> FULL`).
 * 2. It never flashes `0s` while `targetStartMs > currentPosMs`.
 * 3. It never flips back and forth between `Beat blend` (`LOOP`/`NATIVE_BRIDGE`)
 *    and `Blend` (`CLASSIC`).
 * 4. Explicit failure/yield of a rung (`onRungFailed`) moves one-way to the
 *    fallback rung and keeps the failed rung suppressed for `(pairKey, scrubEpoch)`.
 */
class CountdownStabilizer {
    enum class Family { LOOP, NATIVE_BRIDGE, CLASSIC }

    data class Candidate(
        val pairKey: String,
        val scrubEpoch: Long,
        val family: Family,
        val label: String,
        val targetStartMs: Long,
        val targetEndMs: Long,
        val isFullQuality: Boolean = true,
    )

    data class Committed(
        val pairKey: String,
        val scrubEpoch: Long,
        val family: Family,
        val label: String,
        val targetStartMs: Long,
        val targetEndMs: Long,
        val isFullQuality: Boolean,
        val shown: Boolean = false,
        val locked: Boolean = false,
        val suppressedFamilies: Set<Family> = emptySet(),
    )

    private var current: Committed? = null
    private var suppressedPairKey: String? = null
    private var suppressedEpoch: Long = -1L
    private val suppressedFamilies: MutableSet<Family> = mutableSetOf()
    private var failedCommittedNative: Boolean = false

    fun reset() {
        current = null
        suppressedPairKey = null
        suppressedEpoch = -1L
        suppressedFamilies.clear()
        failedCommittedNative = false
    }

    fun committedFor(pairKey: String, scrubEpoch: Long): Committed? =
        current?.takeIf { it.pairKey == pairKey && it.scrubEpoch == scrubEpoch }

    fun isFamilySuppressed(pairKey: String, scrubEpoch: Long, family: Family): Boolean =
        suppressedPairKey == pairKey && suppressedEpoch == scrubEpoch && family in suppressedFamilies

    /** A rejected candidate alone must not authorize an automatic crossfade. */
    fun hasFailedCommittedNative(pairKey: String, scrubEpoch: Long): Boolean =
        suppressedPairKey == pairKey && suppressedEpoch == scrubEpoch && failedCommittedNative

    /** New queue occurrence, possibly with identical IDs; leave countdown/audio state alone. */
    fun clearFailedNativeRecovery() { failedCommittedNative = false }

    fun markShown(pairKey: String, scrubEpoch: Long) {
        val c = committedFor(pairKey, scrubEpoch) ?: return
        if (!c.shown || !c.locked) {
            current = c.copy(shown = true, locked = true)
        }
    }

    /**
     * One-way failure/yield for [family] on `(pairKey, scrubEpoch)`.
     * When [suppressBridgeToo] is true (e.g. loop fallback to classic), both
     * `LOOP` and `NATIVE_BRIDGE` stay suppressed for this `(pairKey, scrubEpoch)`
     * so the UI can never oscillate back to `Beat blend`.
     */
    fun onRungFailed(
        pairKey: String,
        scrubEpoch: Long,
        family: Family,
        suppressBridgeToo: Boolean = false,
    ) {
        if (suppressedPairKey != pairKey || suppressedEpoch != scrubEpoch) {
            suppressedPairKey = pairKey
            suppressedEpoch = scrubEpoch
            suppressedFamilies.clear()
            failedCommittedNative = false
        }
        suppressedFamilies.add(family)
        if (suppressBridgeToo) {
            suppressedFamilies.add(Family.LOOP)
            suppressedFamilies.add(Family.NATIVE_BRIDGE)
        }
        val c = committedFor(pairKey, scrubEpoch)
        if (c != null && c.family != Family.CLASSIC && c.family in suppressedFamilies &&
            (c.shown || c.locked)) failedCommittedNative = true
        if (c != null && c.family in suppressedFamilies) {
            current = null
        }
    }

    fun consider(candidate: Candidate?, currentPosMs: Long): Committed? {
        if (candidate == null) return current
        if (suppressedPairKey != candidate.pairKey || suppressedEpoch != candidate.scrubEpoch) {
            suppressedPairKey = candidate.pairKey
            suppressedEpoch = candidate.scrubEpoch
            suppressedFamilies.clear()
            failedCommittedNative = false
        }
        if (candidate.family in suppressedFamilies) {
            return committedFor(candidate.pairKey, candidate.scrubEpoch)
        }

        val existing = committedFor(candidate.pairKey, candidate.scrubEpoch)
        val remainingMs = candidate.targetStartMs - currentPosMs
        val inCommitWindow = remainingMs <= COMMIT_WINDOW_MS

        if (existing == null) {
            val committed = Committed(
                pairKey = candidate.pairKey,
                scrubEpoch = candidate.scrubEpoch,
                family = candidate.family,
                label = candidate.label,
                targetStartMs = candidate.targetStartMs,
                targetEndMs = candidate.targetEndMs,
                isFullQuality = candidate.isFullQuality,
                shown = false,
                locked = inCommitWindow,
                suppressedFamilies = suppressedFamilies.toSet(),
            )
            current = committed
            return committed
        }

        val existingValid = existing.targetStartMs >= currentPosMs - PAST_GRACE_MS
        val existingLocked = existing.locked || existing.shown ||
            (existing.targetStartMs - currentPosMs <= COMMIT_WINDOW_MS)

        if (!existingLocked) {
            val updated = existing.copy(
                family = candidate.family,
                label = candidate.label,
                targetStartMs = candidate.targetStartMs,
                targetEndMs = candidate.targetEndMs,
                isFullQuality = candidate.isFullQuality,
                locked = inCommitWindow,
                suppressedFamilies = suppressedFamilies.toSet(),
            )
            current = updated
            return updated
        }

        if (existingValid) {
            if (candidate.family != existing.family) {
                return existing
            }
            val deltaMs = abs(candidate.targetStartMs - existing.targetStartMs)
            if (deltaMs > MAX_DRIFT_MS) {
                return existing
            }
            if (existing.targetStartMs > currentPosMs && candidate.targetStartMs <= currentPosMs) {
                return existing
            }
            val updated = if (existing.shown) {
                existing.copy(
                    isFullQuality = existing.isFullQuality || candidate.isFullQuality,
                    suppressedFamilies = suppressedFamilies.toSet(),
                )
            } else {
                existing.copy(
                    label = candidate.label,
                    targetStartMs = candidate.targetStartMs,
                    targetEndMs = candidate.targetEndMs,
                    isFullQuality = candidate.isFullQuality,
                    suppressedFamilies = suppressedFamilies.toSet(),
                )
            }
            current = updated
            return updated
        }

        // Existing anchor is already past (currentPosMs > existing.targetStartMs + PAST_GRACE_MS).
        if (candidate.targetStartMs > currentPosMs) {
            val updated = Committed(
                pairKey = candidate.pairKey,
                scrubEpoch = candidate.scrubEpoch,
                family = candidate.family,
                label = candidate.label,
                targetStartMs = candidate.targetStartMs,
                targetEndMs = candidate.targetEndMs,
                isFullQuality = candidate.isFullQuality,
                shown = existing.shown,
                locked = true,
                suppressedFamilies = suppressedFamilies.toSet(),
            )
            current = updated
            return updated
        }
        return existing
    }

    /**
     * Stabilizes a classic [TransitionPlan] through `HEAD -> FULL` upgrades
     * so `transitionStart` stays pinned to the committed bar once shown/locked.
     */
    fun stabilizeClassicPlan(
        pairKey: String,
        scrubEpoch: Long,
        fresh: TransitionPlan,
        previous: TransitionPlan?,
        isFullQuality: Boolean,
        currentPosMs: Long,
        durationSec: Double,
        allowCommit: Boolean = true,
    ): TransitionPlan {
        // A dry queue advance must never be resurrected or stretched into a
        // previously cached classic fade merely because its marker is hidden.
        if (fresh.transitionStyle == TransitionStyle.NATURAL) return fresh
        if (fresh.blocked || !fresh.markerVisible) {
            val existing = committedFor(pairKey, scrubEpoch)
            if (existing != null && existing.family == Family.CLASSIC &&
                existing.targetStartMs >= currentPosMs - PAST_GRACE_MS &&
                previous != null && !previous.blocked &&
                (durationSec <= 0.0 || durationSec - currentPosMs / 1000.0 >= 2.0)
            ) {
                return previous.copy(shouldStart = currentPosMs >= existing.targetStartMs)
            }
            return fresh
        }
        val candStartMs = (fresh.transitionStart * 1000.0).roundToLong()
        val candEndMs = (fresh.transitionEnd * 1000.0).roundToLong()
        val existing = committedFor(pairKey, scrubEpoch)
        if (!allowCommit && (existing == null || existing.family != Family.CLASSIC)) {
            return fresh
        }
        val committed = consider(
            Candidate(
                pairKey = pairKey,
                scrubEpoch = scrubEpoch,
                family = Family.CLASSIC,
                label = fresh.transitionStyle.name,
                targetStartMs = candStartMs,
                targetEndMs = candEndMs,
                isFullQuality = isFullQuality,
            ),
            currentPosMs,
        ) ?: return fresh

        if (committed.family == Family.CLASSIC &&
            abs(candStartMs - committed.targetStartMs) > MAX_DRIFT_MS &&
            committed.targetStartMs >= currentPosMs - PAST_GRACE_MS
        ) {
            val pinnedStartSec = committed.targetStartMs / 1000.0
            val baseFade = if (previous != null && !previous.blocked && previous.fadeSeconds >= 1.2) {
                previous.fadeSeconds
            } else {
                fresh.fadeSeconds.coerceAtLeast(1.2)
            }
            val maxEnd = if (durationSec > 0.0) durationSec else (pinnedStartSec + baseFade)
            val pinnedEndSec = (pinnedStartSec + baseFade).coerceAtMost(maxEnd)
            val pinnedFade = (pinnedEndSec - pinnedStartSec).coerceAtLeast(0.0)
            return fresh.copy(
                shouldStart = currentPosMs >= committed.targetStartMs,
                markerVisible = true,
                blocked = false,
                transitionStart = pinnedStartSec,
                transitionEnd = pinnedEndSec,
                fadeSeconds = pinnedFade,
            )
        }
        return fresh
    }

    companion object {
        const val COMMIT_WINDOW_MS = 45_000L
        const val MAX_DRIFT_MS = 1_500L
        const val PAST_GRACE_MS = 600L

        /**
         * Display seconds for a countdown targeting [targetStartMs] from [currentPosMs].
         * Never flashes `0s` while [targetStartMs] is still in the future (`> currentPosMs`).
         */
        fun displaySeconds(targetStartMs: Long, currentPosMs: Long): Int {
            val remainingMs = targetStartMs - currentPosMs
            if (remainingMs <= 0L) return 0
            return ceil(remainingMs / 1000.0).toInt().coerceAtLeast(1)
        }
    }
}
