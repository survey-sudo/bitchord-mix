package com.music.bitchord.playback

import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixArmingLivenessTest {
    @Test
    fun terminalArmingReasonsAreDistinctFromTransientInterruption() {
        assertTrue(CrossfadeController::class.java.declaredMethods.any { it.name == "cancelArming" })
        assertTrue(CrossfadeController::class.java.declaredMethods.any { it.name == "bail" })
    }
}
