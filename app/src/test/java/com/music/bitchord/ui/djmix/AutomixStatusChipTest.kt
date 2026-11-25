package com.music.bitchord.ui.djmix

import com.music.bitchord.djmix.core.contracts.MixUiPhase
import com.music.bitchord.djmix.core.contracts.MixUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomixStatusChipTest {
    @Test
    fun preparationShowsAnExplicitPercentage() {
        assertEquals("Preparing mix · 42%", chipTextFor(MixUiState(MixUiPhase.PREPARING, progressPct = 42), true))
    }

    @Test
    fun readyAndArmedUseRecipeAndCountdownWithoutDiagnosticTags() {
        for (phase in listOf(MixUiPhase.READY, MixUiPhase.ARMED)) {
            val state = MixUiState(
                phase = phase,
                recipeName = "  Bass swap  ",
                countdownSec = 75,
                countdownVisible = true,
                chipText = "legacy rung · 128 BPM · locked",
            )
            assertEquals("Bass swap in 1:15", chipTextFor(state, true))
        }
    }

    @Test
    fun movedNoticeExpiresWithoutInventingReadinessAndReadyThenShowsCountdown() {
        val moved = MixUiState(
            phase = MixUiPhase.MOVED,
            recipeName = "Bass swap",
            movedTargetSec = 185,
            progressPct = 85,
            chipText = "Moved to 3:05 · stems ready soon",
        )
        assertEquals("Moved to 3:05 · stems ready soon", chipTextFor(moved, true))
        assertEquals("Preparing mix · 85%", chipTextFor(moved, false))
        assertEquals("Preparing mix · 85%", chipTextFor(moved.copy(countdownSec = 25), false))
        val ready = moved.copy(phase = MixUiPhase.READY, countdownSec = 25, countdownVisible = true)
        assertEquals("Bass swap in 0:25", chipTextFor(ready, false))
    }

    @Test
    fun activeAndLastResortCopyIsIndependentOfDiagnosticText() {
        for (phase in listOf(MixUiPhase.RUNNING, MixUiPhase.HANDING_OVER)) {
            assertEquals("Mixing · Bass swap", chipTextFor(MixUiState(phase, recipeName = "Bass swap"), true))
        }
        assertEquals("Ending with echo-out", chipTextFor(MixUiState(MixUiPhase.LAST_RESORT), true))
    }

    @Test
    fun inactiveAndIncompleteCountdownsNeverLeakFallbackHeadlines() {
        for (phase in listOf(MixUiPhase.IDLE, MixUiPhase.DONE, MixUiPhase.SUSPENDED, MixUiPhase.FAILED)) {
            assertEquals("", chipTextFor(MixUiState(phase, chipText = "Next 128 BPM · locked"), true))
        }
        assertEquals("", chipTextFor(MixUiState(MixUiPhase.READY, chipText = "Next 128 BPM · locked"), true))
    }

    @Test
    fun timesClampNegativeValuesAndKeepTwoSecondDigits() {
        assertEquals("0:00", formatMmSs(-1))
        assertEquals("0:09", formatMmSs(9))
        assertEquals("1:00", formatMmSs(60))
        assertEquals("61:01", formatMmSs(3_661))
    }
}
