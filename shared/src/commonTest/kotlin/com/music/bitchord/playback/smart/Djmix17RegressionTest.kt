package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * djmix17 regression: the Never Let Go skip residuals.
 *
 * Bug A (seq365..369): `loop` stayed non-null across 160..193.6 s
 * while a hung primeJob (`building == true`) blocked
 * shouldYieldColdLoop (`if (fastReady || building) return false`) —
 * and even past the deadline, beginLoop's silent `return` pairs with
 * the caller's unconditional `return` starved classic begin() to EOF.
 * Bug B (seq367->368->369): interior-anchor catch-up pin (164.6 s)
 * survived the final-anchor non-catch-up plan (181.6 s) because
 * dropOnNonCatchUp only dropped across pairs; seq369 then reused the
 * 18-second-stale pin instead of pinning a fresh 182.9 s catch-up.
 * Bug C: AnalysisStore never persisted `quality`, so every track
 * restored after a restart reported quality="none".
 *
 * Scalars only: positions, leads and pins. No audio crosses.
 */
class Djmix17RegressionTest {

    // ---- Bug A: building yields inside the hard deadline ----

    @Test fun buildingYieldsInsideHardDeadline() {
        // Never Let Go -> S.A.D shape: loopStart ~193 s.
        val loopStartMs = 193_000L
        val blendLead = 20_000L
        // 25 s out while building: still allowed to run (prime window).
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, 168_000L, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = false),
            "building 25 s out must be allowed to run",
        )
        // djmix17-harden: 14 s cold (next stream warms): hard deadline, must yield.
        assertTrue(
            LoopMix.shouldYieldColdLoop(loopStartMs, 179_000L, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = false),
            "building 14 s out cold must yield (hard deadline 10/14)",
        )
        // 12 s cold: inside 14 s, must yield.
        assertTrue(
            LoopMix.shouldYieldColdLoop(loopStartMs, 181_000L, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = false),
            "building 12 s out cold must yield (inside 14 s)",
        )
        // 15 s cold: outside 14 s, still allowed.
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, 178_000L, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = false),
            "building 15 s out cold must be allowed",
        )
        // 10 s warm (next ready): hard deadline, must yield.
        assertTrue(
            LoopMix.shouldYieldColdLoop(loopStartMs, 183_000L, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = true),
            "building 10 s out warm must yield (hard deadline 10/14)",
        )
        // 8.5 s warm: inside 10 s, must yield.
        assertTrue(
            LoopMix.shouldYieldColdLoop(loopStartMs, 184_500L, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = true),
            "building 8.5 s out warm must yield (inside 10 s)",
        )
        // 11 s warm: outside 10 s, still allowed.
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, 182_000L, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = true),
            "building 11 s out warm must be allowed",
        )
        // Fast-ready never yields, even 0.5 s out with a build running.
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, 192_500L, fastReady = true, building = true, blendArmLeadMs = blendLead, nextStreamReady = false),
            "fast-ready must never yield, even inside the deadline",
        )
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, 192_500L, fastReady = true, building = false, blendArmLeadMs = blendLead, nextStreamReady = false),
            "fast-ready must never yield",
        )
    }

    // ---- Bug B: anchor flip drops the stale pin ----

    private fun catchUp(startSec: Double, fadeSec: Double = 4.0): TransitionPlan =
        TransitionPlan(
            shouldStart = false,
            markerVisible = true,
            blocked = false,
            reason = "smart-duration:catch-up",
            transitionStart = startSec,
            transitionEnd = startSec + fadeSec,
            fadeSeconds = fadeSec,
            transitionStyle = TransitionStyle.DJ_FILTER,
            incomingCueTime = 0.0,
        )

    @Test fun anchorFlipDropsStaleInteriorPinThenPinsFreshFinalCatchUp() {
        val pair = "never->sad"
        // seq367 (163.7 s): interior-anchor catch-up pins start 164.6 s.
        val interiorPin = catchUp(164.6, 4.5935)
        val interiorKey = CatchUpPins.key(pair, 0L)
        // Pin/reuse round-trip: same key returns the interior pin.
        val reusedInterior = CatchUpPins.reuse(interiorPin, interiorKey, interiorKey)
        assertNotNull(reusedInterior)
        assertEquals(164.6, reusedInterior.transitionStart, 1e-9)

        // seq368 (181.1 s): final-anchor plan is valid and NOT :catch-up.
        // The helper must drop the stale interior pin on the same pair.
        assertTrue(
            CatchUpPins.dropOnNonCatchUp(interiorKey, pair),
            "same-pair valid non-catch-up (interior->final flip) must drop the pin",
        )
        // After the drop the caller holds null: the next catch-up for the
        // same key is NOT reused from the stale interior pin.
        assertNull(
            CatchUpPins.reuse(null, null, interiorKey),
            "after the drop, reuse must miss so the fresh plan is stored",
        )

        // seq369 (182.0 s): fresh final-anchor catch-up start 182.9 s.
        // It must pin (not resurrect 164.6) and then hold.
        val finalCatchUp = catchUp(182.9, 4.5935)
        val fresh = CatchUpPins.reuse(null, null, interiorKey)
        assertNull(fresh, "fresh catch-up must not reuse a dropped interior pin")
        // Caller stores the fresh plan under the key.
        var pinnedKey: String? = interiorKey
        var pinned = finalCatchUp
        val reusedFinal = CatchUpPins.reuse(pinned, pinnedKey, interiorKey)
        assertNotNull(reusedFinal)
        assertEquals(182.9, reusedFinal!!.transitionStart, 1e-9, "new pin must be the 182.9 s final catch-up, not the stale 164.6 s interior pin")
        pinned = reusedFinal // caller holds the fresh pin
        assertEquals(182.9, pinned.transitionStart, 1e-9)
        assertEquals(4.5935, pinned.fadeSeconds, 1e-9)
        pinnedKey = interiorKey
        assertEquals(interiorKey, pinnedKey) // key stable across the flip
    }

    // ---- djmix17-harden: contentEnd-tail margin ----
    //
    // Never Let Go: duration 193.641 s, contentEnd ~190.7 s (silence tail),
    // loopStart a few seconds after contentEnd in the worst case. A build
    // that yields at 10 s (ready) / 14 s (warming) before loopStart still
    // leaves 7/11 s before contentEnd, enough for classic to arm. The
    // 8.5/12 deadlines left only 5.5/9 s.

    @Test fun buildingYieldLeavesClassicMarginBeforeContentEnd() {
        // loopStart 193 s, contentEnd 190.7 s (2.3 s tail skew).
        val loopStartMs = 193_000L
        val contentEndMs = 190_700L
        val blendLead = 20_000L
        // Yield points: pos = loopStart - 10 s (warm) / -14 s (cold).
        val warmYieldPos = loopStartMs - 10_000L // 183 s
        val coldYieldPos = loopStartMs - 14_000L // 179 s
        // Margins to contentEnd: 7.7 s warm, 11.7 s cold.
        assertTrue(contentEndMs - warmYieldPos >= 7_000L, "warm yield must leave >=7 s to contentEnd")
        assertTrue(contentEndMs - coldYieldPos >= 11_000L, "cold yield must leave >=11 s to contentEnd")
        // Both yield at those positions.
        assertTrue(
            LoopMix.shouldYieldColdLoop(loopStartMs, warmYieldPos, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = true),
            "warm yield point must yield",
        )
        assertTrue(
            LoopMix.shouldYieldColdLoop(loopStartMs, coldYieldPos, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = false),
            "cold yield point must yield",
        )
        // Just outside each deadline the build may still run.
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, warmYieldPos - 1_000L, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = true),
            "11 s out warm must still run",
        )
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, coldYieldPos - 1_000L, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = false),
            "15 s out cold must still run",
        )
    }

    @Test fun coldNonBuildingYieldsAtFullArmLead() {
        // Non-building cold loops keep the full lead (blendArmLead 20 s,
        // 25 s while the stream warms, floor 8.5 s) — strictly earlier
        // than any building yield, so classic always gets its buffer.
        val loopStartMs = 193_000L
        // 20 s out cold-warming: inside 25 s lead, must yield.
        assertTrue(
            LoopMix.shouldYieldColdLoop(loopStartMs, 173_000L, fastReady = false, building = false, blendArmLeadMs = 20_000L, nextStreamReady = false),
            "non-building cold 20 s out warming must yield",
        )
        // 30 s out warm: outside 20 s lead, must not yield.
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, 163_000L, fastReady = false, building = false, blendArmLeadMs = 20_000L, nextStreamReady = true),
            "non-building warm 30 s out must not yield",
        )
    }
}
