package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * vc92 Lane1: pre-splice identity re-validation, pure-function coverage only.
 *
 * [preSpliceIdentityReason] is the controller-side gate that decides whether
 * the native splice (`PrecisionAudioSink.spliceLoop`) may run at all. It is
 * deliberately pure and scalar so every branch is reachable from a JVM test;
 * the splice itself is not. Kept in its own file so the vc92 controller fixes
 * test and this one can evolve independently.
 */
class Vc92PreSpliceIdentityTest {

    @Test
    fun queueEditInArmWindowAbortsBeforeSplice() {
        val reason = preSpliceIdentityReason(
            expectedInId = "planned-in",
            liveNextId = "queue-edited-in", // user reordered the queue mid-arm
            expectedOutId = "out",
            currentOutId = "out",
            capturedGeneration = 5L,
            planGeneration = 5L,
        )
        assertEquals("pre-splice-identity-mismatch", reason)
    }

    @Test
    fun stalePlanGenerationAbortsEvenWhenMediaIdsMatch() {
        val reason = preSpliceIdentityReason(
            expectedInId = "in",
            liveNextId = "in",
            expectedOutId = "out",
            currentOutId = "out",
            capturedGeneration = 5L,
            planGeneration = 6L, // scrub/replan after the splice was scheduled
        )
        assertEquals("pre-splice-plan-generation-stale", reason)
    }

    @Test
    fun matchingIdentityAndGenerationMaySplice() {
        assertNull(
            preSpliceIdentityReason(
                expectedInId = "in",
                liveNextId = "in",
                expectedOutId = "out",
                currentOutId = "out",
                capturedGeneration = 5L,
                planGeneration = 5L,
            ),
        )
    }

    @Test
    fun outgoingIdentityMismatchAborts() {
        assertEquals(
            "pre-splice-identity-mismatch",
            preSpliceIdentityReason("in", "in", "planned-out", "other-out", 5L, 5L),
        )
    }

    @Test
    fun unplannedPairWithoutCaptureMayProceed() {
        // No in-identity captured (legacy arm path): do not fail closed on
        // missing data the older call sites never provided.
        assertNull(preSpliceIdentityReason(null, "anything", null, null, -1L, 5L))
    }
}
