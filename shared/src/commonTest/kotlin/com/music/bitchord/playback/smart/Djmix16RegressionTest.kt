package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * djmix16 regression: the three vc57 device residuals.
 *
 * Bug 1: Never Let Go -> S.A.D (seq90..96) — pinned catch-up moving
 * carrot (fadeSec 4.59 -> 3.99 -> 2.26 -> 1.2, 0 mix.fire/arm/exit)
 * and a cold loop holding classic to 4.5 s before the splice.
 * Bug 2: outgoing splice 116245 ms over (seq258, L4242) — wall/media
 * epoch subtraction reported as wall lateness.
 * Bug 3: finish:LOOP_ARMING emergency-retire-guard (8x, tailMs=800)
 * blocking classic re-arming on pre-takeover aborts.
 *
 * Scalars only: BPM, positions, deadlines and volumes. No audio crosses.
 */
class Djmix16RegressionTest {

    // ---- Bug 1a: catch-up pin holds once per (pair, scrubEpoch) ----

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

    @Test fun pinnedCatchUpIsReusedOnceAndNeverRecomputed() {
        // Never Let Go shape: first catch-up at pos 186.6 pins start 187.5.
        // The next tick at pos 187.0 would recompute start 188.0
        // (pos + lead) with the old moving carrot (fadeSec shrinks
        // 4.59 -> 3.99 -> ...). With the pin, the anchored 187.5 holds.
        val pair = "never->sad"
        val epoch = 7L
        val key = CatchUpPins.key(pair, epoch)
        val pinned = catchUp(187.5, 4.59)
        val recomputed = catchUp(188.0, 3.988)

        val reused = CatchUpPins.reuse(pinned, key, key)
        assertNotNull(reused, "same (pair, epoch) must reuse the pinned plan")
        assertEquals(187.5, reused.transitionStart, 1e-9, "start=${reused.transitionStart} must stay pinned at 187.5, not drift to 188.0")
        assertEquals(4.59, reused.fadeSeconds, 1e-9, "fade must stay 4.59, not shrink to 3.988")
        assertTrue(reused !== recomputed, "pinned reuse must return the pinned instance, not the recomputed one")
        // Key stability: same pair + epoch always hits, even as pos advances.
        assertEquals(key, CatchUpPins.key(pair, epoch))
    }

    @Test fun pinnedCatchUpShouldStartFlipsAtStart() {
        // Pinned at pos 186.6 with start 187.5 (shouldStart=false at pin
        // time). The frozen timing must not freeze shouldStart: once the
        // playhead reaches the pinned start the plan must read started.
        val pinned = catchUp(187.5, 4.59)
        val key = CatchUpPins.key("never->sad", 7L)
        assertFalse(pinned.shouldStart, "pin is created before its start")
        // Before the start: stays false, same instance (no copy churn).
        val early = CatchUpPins.reuse(pinned, key, key, currentPositionMs = 186_600L)
        assertNotNull(early, "same (pair, epoch) must reuse the pinned plan")
        assertFalse(early.shouldStart, "before transitionStart shouldStart must stay false")
        assertTrue(early === pinned, "before start must return the pinned instance unchanged")
        // At the start: flips to true, timing still pinned.
        val atStart = CatchUpPins.reuse(pinned, key, key, currentPositionMs = 187_500L)
        assertNotNull(atStart, "same (pair, epoch) must reuse the pinned plan at its start")
        assertTrue(atStart.shouldStart, "at transitionStart shouldStart must become true")
        assertEquals(187.5, atStart.transitionStart, 1e-9, "timing must stay pinned at 187.5")
        assertEquals(4.59, atStart.fadeSeconds, 1e-9, "fade must stay 4.59")
        // Past the start: stays true (remaining <= 0, classic begin fires
        // on the remaining gate, but the plan field must also read started).
        val past = CatchUpPins.reuse(pinned, key, key, currentPositionMs = 188_000L)
        assertNotNull(past)
        assertTrue(past.shouldStart, "past transitionStart shouldStart must stay true")
        assertEquals(187.5, past.transitionStart, 1e-9)
        assertEquals(4.59, past.fadeSeconds, 1e-9)
        // Backwards-compatible 3-arg call (no position) keeps old behavior:
        // returns the pinned instance unchanged.
        val legacy = CatchUpPins.reuse(pinned, key, key)
        assertNotNull(legacy)
        assertTrue(legacy === pinned, "legacy 3-arg reuse must return the pinned instance unchanged")
    }

    @Test fun pinnedCatchUpDropsOnPairOrEpochChange() {
        val pinned = catchUp(187.5)
        val keyA = CatchUpPins.key("a->b", 1L)
        // New scrub epoch (resetPlansForScrub): no reuse, caller stores fresh.
        assertNull(CatchUpPins.reuse(pinned, keyA, CatchUpPins.key("a->b", 2L)), "new scrubEpoch must not reuse the old pin")
        // New pair: no reuse.
        assertNull(CatchUpPins.reuse(pinned, keyA, CatchUpPins.key("c->d", 1L)), "new pair must not reuse")
        // Non-catch-up for a new pair drops the pin.
        assertTrue(CatchUpPins.dropOnNonCatchUp(keyA, "c->d"))
        // djmix17: same-pair non-catch-up ALSO drops (interior -> final
        // anchor flip, Never Let Go seq368 181.6 s). The stale 164.6 s
        // interior pin resurfaced when the new anchor was crossed (seq369).
        assertTrue(CatchUpPins.dropOnNonCatchUp(keyA, "a->b"))
        assertFalse(CatchUpPins.dropOnNonCatchUp(null, "a->b"))
    }

    // ---- Bug 1b: cold loop yields with a full classic arm lead ----

    @Test fun coldLoopYieldsWithFullClassicArmLead() {
        // Never Let Go -> S.A.D shape: loopStart ~193 s, cold, 25 s warm window.
        val loopStartMs = 193_000L
        val blendLead = 20_000L
        // 50 s away: still priming, no yield yet.
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, 143_000L, fastReady = false, building = false, blendArmLeadMs = blendLead, nextStreamReady = false),
            "50 s away must not yield (prime window)",
        )
        // 30 s away (lead 25 s cold): not yet.
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, 163_000L, fastReady = false, building = false, blendArmLeadMs = blendLead, nextStreamReady = false),
        )
        // 25 s away: yield — classic gets its full 25 s arm (old gate gave 4.5 s).
        assertTrue(
            LoopMix.shouldYieldColdLoop(loopStartMs, 168_000L, fastReady = false, building = false, blendArmLeadMs = blendLead, nextStreamReady = false),
            "25 s before loopStart when cold must yield",
        )
        // 3 s away: still yields (late, but classic is the only chance left).
        assertTrue(
            LoopMix.shouldYieldColdLoop(loopStartMs, 190_000L, fastReady = false, building = false, blendArmLeadMs = blendLead, nextStreamReady = false),
        )
        // Fast-ready (Beautiful -> Never: decks in RAM) never yields.
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, 168_000L, fastReady = true, building = false, blendArmLeadMs = blendLead, nextStreamReady = false),
            "fast-ready loops must never yield",
        )
        // Actively building (complete file decoding) never yields — it may land.
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, 168_000L, fastReady = false, building = true, blendArmLeadMs = blendLead, nextStreamReady = false),
            "building loops must not yield",
        )
        // Next ready: lead is max(8.5 s, blendLead) = 20 s.
        assertFalse(
            LoopMix.shouldYieldColdLoop(loopStartMs, 168_000L, fastReady = false, building = false, blendArmLeadMs = blendLead, nextStreamReady = true),
            "25 s away with next ready (lead 20 s) must not yield yet",
        )
        assertTrue(
            LoopMix.shouldYieldColdLoop(loopStartMs, 175_000L, fastReady = false, building = false, blendArmLeadMs = blendLead, nextStreamReady = true),
            "18 s away with next ready must yield",
        )
    }

    // ---- Bug 2: wall is only compared to wall ----

    @Test fun wallNeverSubtractsMediaEpochs() {
        // seq258 shape: wall now 500000, media deadline 170120 (a loop
        // start in ms) mistaken for wall would report ~329880 ms over.
        val nowWall = 500_000L
        val mediaAsDeadline = 170_120L
        assertEquals(0L, DeckBudget.provenOverrunMs(nowWall, mediaAsDeadline, nowWall), "media-as-wall must be stale (>40 s horizon), never lateness")
        val mediaReason = LoopMix.lateStepReason(
            listOf(LoopMix.PrepSpan("outgoing splice", nowWall - 100L, nowWall, mediaAsDeadline)),
            nowWall, mediaAsDeadline,
        )
        assertFalse(mediaReason.contains("329880"), "reason=$mediaReason must not subtract wall-media")
        assertFalse(mediaReason.contains("116245"), "reason=$mediaReason must not report the device bug")
        assertTrue(mediaReason.contains("paused"), "reason=$mediaReason")

        // The device bug itself: a synthetic span with deadline 116245 ms
        // in the past (now - lateBy, wall minus media) must read as
        // no budget (116 s > 40 s horizon), never as 116245 ms over.
        val staleDeadline = nowWall - 116_245L
        val staleSpan = LoopMix.PrepSpan("outgoing splice", staleDeadline, nowWall, staleDeadline)
        assertEquals(0L, DeckBudget.provenOverrunMs(staleSpan.endedMs, staleSpan.deadlineMs, nowWall))
        assertFalse(DeckBudget.isProvablyLate(staleSpan, nowWall))
        val staleReason = LoopMix.lateStepReason(listOf(staleSpan), nowWall, staleDeadline)
        assertFalse(staleReason.contains("116245"), "reason=$staleReason must not report the stale span")
        assertTrue(staleReason.contains("paused"), "reason=$staleReason")

        // PrepSpan getter is sentinel-guarded: 0 deadline never reads as
        // the 374320 boot-uptime bug.
        assertEquals(0L, LoopMix.PrepSpan("outgoing splice", 0L, 374_320L, 0L).overrunMs)
        assertEquals(0L, LoopMix.PrepSpan("x", 0L, nowWall, DeckBudget.NO_DEADLINE_MS).overrunMs)

        // Genuine bounded wall lateness still reports (17 ms over).
        val genuineDeadline = DeckBudget.deadlineMs(0L, true, 1_000L, 0L)
        val genuineSpan = LoopMix.PrepSpan("outgoing splice", 0L, genuineDeadline + 17L, genuineDeadline)
        assertTrue(DeckBudget.isProvablyLate(genuineSpan, genuineDeadline + 17L))
        assertEquals("outgoing splice 17 ms over", LoopMix.lateStepReason(listOf(genuineSpan), genuineDeadline + 17L, genuineDeadline))
        // Horizon boundary: 44 s stale is in force, 46 s is not. (2B readiness
        // expansion: horizon grew 40→45 s with PrepLead.MAX 35→45 s, so the
        // old 41 s stale boundary is superseded.)
        assertEquals(44_000L, DeckBudget.provenOverrunMs(nowWall, nowWall - 44_000L, nowWall))
        assertEquals(0L, DeckBudget.provenOverrunMs(nowWall, nowWall - 46_000L, nowWall))
    }

    // ---- Bug 3: pre-takeover LOOP_ARMING never tails ----

    @Test fun preTakeoverLoopArmingNeverTails() {
        // armLoop state: volume 1.0, playWhenReady false, sink prepared
        // but never spliced. The old finish() read volume 1.0 as
        // audible and took an 800 ms emergency tail on every abort.
        assertFalse(
            RetireGuard.loopArmingSpareAudible(tookOver = false, handedOff = false, volume = 1.0f, playWhenReady = false),
            "pre-takeover prepared-but-silent must not be audible",
        )
        assertTrue(
            RetireGuard.canRetireLoopArmingSpare(tookOver = false, handedOff = false, volume = 1.0f, playWhenReady = false),
            "pre-takeover spare may retire at once (0 ms to IDLE)",
        )
        // Even playWhenReady=true pre-takeover cannot be heard: the
        // InArm sink is silent until the handover.
        assertFalse(
            RetireGuard.loopArmingSpareAudible(tookOver = false, handedOff = false, volume = 1.0f, playWhenReady = true),
        )
        // Genuine audible audio still takes the guard (tookOver + playing).
        assertTrue(
            RetireGuard.loopArmingSpareAudible(tookOver = true, handedOff = false, volume = 1.0f, playWhenReady = true),
            "tookOver and playing must be audible",
        )
        assertFalse(
            RetireGuard.canRetireLoopArmingSpare(tookOver = true, handedOff = false, volume = 1.0f, playWhenReady = true),
            "audible spare must tail",
        )
        // Silent after takeover retires without a tail.
        assertFalse(
            RetireGuard.loopArmingSpareAudible(tookOver = true, handedOff = false, volume = 0.0f, playWhenReady = true),
        )
        assertTrue(
            RetireGuard.canRetireLoopArmingSpare(tookOver = true, handedOff = false, volume = 0.0f, playWhenReady = true),
        )
        // Paused after takeover is not audible either.
        assertFalse(
            RetireGuard.loopArmingSpareAudible(tookOver = true, handedOff = false, volume = 1.0f, playWhenReady = false),
        )
        // Handed off and playing is audible.
        assertTrue(
            RetireGuard.loopArmingSpareAudible(tookOver = false, handedOff = true, volume = 1.0f, playWhenReady = true),
        )
        // Force still retires.
        assertTrue(
            RetireGuard.canRetireLoopArmingSpare(tookOver = true, handedOff = false, volume = 1.0f, playWhenReady = true, force = true),
        )
    }
}
