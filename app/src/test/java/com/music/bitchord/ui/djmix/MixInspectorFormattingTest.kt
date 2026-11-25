package com.music.bitchord.ui.djmix

import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.contracts.ReadinessSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class MixInspectorFormattingTest {
    @Test
    fun readinessPairsVisibleSymbolsWithSpokenStatesAndCounts() {
        val state = MixUiState(readiness = ReadinessSummary(true, true, 3, 4, false))
        assertEquals("Analysis ✓ · Plan ✓ · Stems 3/4 · Renderer ✗", readinessText(state))
        assertEquals(
            "Readiness: Analysis ready. Plan ready. Stems 3 of 4 ready. Renderer not ready.",
            readinessContentDescription(state),
        )
    }

    @Test
    fun noStemMixExplainsThatStemsAreNotRequired() {
        val state = MixUiState(readiness = ReadinessSummary(true, true, 0, 0, true))
        assertEquals("Analysis ✓ · Plan ✓ · Stems not required · Renderer ✓", readinessText(state))
        assertEquals(
            "Readiness: Analysis ready. Plan ready. Stems not required. Renderer ready.",
            readinessContentDescription(state),
        )
    }

    @Test
    fun learnedTasteKeepsTheSignAndTwoDecimalPlaces() {
        assertEquals("your taste: +0.12", inspectorTasteBadge(0.12, true))
        assertEquals("your taste: -0.08", inspectorTasteBadge(-0.08, true))
        assertEquals(
            "Phrase ends together · your taste: +0.12",
            inspectorWhyText("  Phrase ends together  ", 0.12, true),
        )
    }

    @Test
    fun learningOffHidesEvenAPreviouslyAppliedTasteOffset() {
        assertNull(inspectorTasteBadge(0.12, false))
        assertEquals("Phrase ends together", inspectorWhyText("Phrase ends together", 0.12, false))
        assertEquals("", inspectorWhyText(" ", -0.08, false))
    }

    @Test
    fun zeroTinyAndInvalidOffsetsLeaveNoEmptyBadgeOrSeparator() {
        for (adjustment in listOf(0.0, -0.0, 0.009, -0.009, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(inspectorTasteBadge(adjustment, true))
            assertEquals("Phrase ends together", inspectorWhyText("Phrase ends together", adjustment, true))
        }
        assertEquals("your taste: -0.08", inspectorWhyText(" ", -0.08, true))
    }

    @Test
    fun thresholdAndRoundingAgreeWithTheSharedPopup() {
        for (adjustment in listOf(0.0, -0.0, 0.009, 0.01, -0.01, 0.12, -0.08, 0.125, -0.125, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (enabled in listOf(false, true)) {
                assertEquals(com.music.bitchord.ui.player.inspectorTasteBadge(adjustment, enabled), inspectorTasteBadge(adjustment, enabled))
                assertEquals(com.music.bitchord.ui.player.inspectorWhyText("Placement", adjustment, enabled), inspectorWhyText("Placement", adjustment, enabled))
            }
        }
        assertEquals("your taste: +0.01", inspectorTasteBadge(0.01, true))
        assertEquals("your taste: -0.01", inspectorTasteBadge(-0.01, true))
        assertEquals("your taste: +0.12", inspectorTasteBadge(0.125, true))
        assertEquals("your taste: -0.12", inspectorTasteBadge(-0.125, true))
    }

    @Test
    fun repeatedFormattingRemovesStaleBadgesAndNeverDuplicatesThem() {
        val reason = inspectorWhyText("Placement", 0.12, true)
        assertEquals(reason, inspectorWhyText(reason, 0.12, true))
        assertEquals("Placement · your taste: -0.08", inspectorWhyText(reason, -0.08, true))
        assertEquals("Placement", inspectorWhyText("$reason · your taste: -0.08", 0.12, false))
        assertEquals("Placement", inspectorWhyText(reason, 0.009, true))
        assertEquals("", inspectorWhyText("your taste: +0.12", 0.12, false))
    }

    @Test
    fun decimalPointAndSignsRemainStableInANonEnglishLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("Placement · your taste: +0.12", inspectorWhyText("Placement", 0.12, true))
            assertEquals("Placement · your taste: -0.08", inspectorWhyText("Placement", -0.08, true))
        } finally {
            Locale.setDefault(original)
        }
    }
}
