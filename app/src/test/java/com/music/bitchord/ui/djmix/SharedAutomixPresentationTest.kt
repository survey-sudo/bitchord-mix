package com.music.bitchord.ui.djmix

import com.music.bitchord.playback.smart.AutomixStatus
import com.music.bitchord.playback.smart.MixInspectorPhase
import com.music.bitchord.playback.smart.MixInspectorState
import com.music.bitchord.playback.smart.MixInspectorTrack
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.ui.player.automixChipLabel
import com.music.bitchord.ui.player.automixPlayheadAnimationDurationMillis
import com.music.bitchord.ui.player.automixReducedMotionEnabled
import com.music.bitchord.ui.player.inspectorReadinessContentDescription
import com.music.bitchord.ui.player.inspectorReadinessText
import com.music.bitchord.ui.player.inspectorHeldSnapshot
import com.music.bitchord.ui.player.inspectorTasteBadge as sharedInspectorTasteBadge
import com.music.bitchord.ui.player.inspectorWhyText as sharedInspectorWhyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SharedAutomixPresentationTest {
    @Test
    fun playerCountdownDropsLegacyTagsWhilePreservingTheRecipe() {
        val status = status("ready", "Bass swap in 1:15 · 128 BPM · locked")
        assertEquals("Bass swap in 1:15", automixChipLabel(status, inspector(MixInspectorPhase.ARMED)))
        assertEquals("Bass swap in 1:15", automixChipLabel(status.copy(state = "arming"), null))
    }

    @Test
    fun movedBannerExpiresToPreparationUntilTheActualReadyFeedArrives() {
        val moved = status("preparing", "Moved to 3:05 · stems ready soon").copy(style = "moved", progress = 0.85f)
        assertEquals("Moved to 3:05 · stems ready soon", automixChipLabel(moved, null, showMoved = true))
        assertEquals("Preparing mix · 85%", automixChipLabel(moved, null, showMoved = false))
        val ready = moved.copy(state = "ready", headline = "Bass swap in 0:25 · locked")
        assertEquals("Bass swap in 0:25", automixChipLabel(ready, null, showMoved = false))
    }

    @Test
    fun inactiveFeedsAndDiagnosticOnlyStatusesHaveNoPlayerChip() {
        for (phase in listOf(MixInspectorPhase.IDLE, MixInspectorPhase.DONE, MixInspectorPhase.CANCELLED)) {
            assertNull(automixChipLabel(status("mixing"), inspector(phase)))
        }
        for (state in listOf("", "idle", "done", "cancelled", "suspended")) {
            assertNull(automixChipLabel(status(state, "Next 128 BPM · locked"), null))
        }
        assertNull(automixChipLabel(null, null))
    }

    @Test
    fun activeCopyAndPreparationNeverExposeRawDiagnosticsOrNonFiniteProgress() {
        assertEquals("Mixing · Bass swap", automixChipLabel(status("mixing", "legacy rung · locked"), null))
        assertEquals("Ending with echo-out", automixChipLabel(status("echo"), null))
        for (progress in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) {
            assertEquals("Preparing mix · 0%", automixChipLabel(status("preparing").copy(progress = progress), null))
        }
        assertEquals("Preparing mix · 100%", automixChipLabel(status("preparing").copy(progress = 2f), null))
    }

    @Test
    fun cancelledAndBuildingStatesDoNotPretendTheRendererIsReady() {
        assertEquals("Analysis ✗ · Plan ✗ · Stems — · Renderer ✗", inspectorReadinessText(inspector(MixInspectorPhase.CANCELLED)))
        assertEquals(
            "Readiness: Analysis not ready. Plan not ready. Stems readiness unknown. Renderer not ready.",
            inspectorReadinessContentDescription(inspector(MixInspectorPhase.CANCELLED)),
        )
        assertEquals("Analysis ✓ · Plan ✓ · Stems — · Renderer ✗", inspectorReadinessText(inspector(MixInspectorPhase.BUILDING)))
    }

    @Test
    fun noStemTransitionsDoNotLookLikeAZeroOfZeroFailure() {
        val noStems = inspector(MixInspectorPhase.PREPARED).copy(stemCount = 0, stemsReady = true)
        assertEquals("Analysis ✓ · Plan ✓ · Stems not required · Renderer ✗", inspectorReadinessText(noStems))
        assertEquals(
            "Readiness: Analysis ready. Plan ready. Stems not required. Renderer not ready.",
            inspectorReadinessContentDescription(noStems),
        )
    }

    @Test
    fun measuredZeroTimingsRemainReadyEvenAfterCancellation() {
        val measured = inspector(MixInspectorPhase.CANCELLED).copy(
            analysisMs = 0, planMs = 0, buildMs = 0, stemsReady = true, stemCount = 4,
        )
        assertEquals("Analysis ✓ · Plan ✓ · Stems 4/4 · Renderer ✓", inspectorReadinessText(measured))
        assertEquals(
            "Readiness: Analysis ready. Plan ready. Stems 4 of 4 ready. Renderer ready.",
            inspectorReadinessContentDescription(measured),
        )
    }

    @Test
    fun eitherUserPreferenceOrSystemAnimationsOffDisablesPlayheadTween() {
        assertTrue(automixReducedMotionEnabled(true, 1f))
        assertTrue(automixReducedMotionEnabled(false, 0f))
        assertTrue(automixReducedMotionEnabled(true, 0f))
        assertFalse(automixReducedMotionEnabled(false, 1f))
        assertFalse(automixReducedMotionEnabled(false, 0.5f))
        assertFalse(automixReducedMotionEnabled(false, 2f))
        assertEquals(0, automixPlayheadAnimationDurationMillis(automixReducedMotionEnabled(false, 0f)))
        assertEquals(0, automixPlayheadAnimationDurationMillis(automixReducedMotionEnabled(true, 1f)))
        assertEquals(250, automixPlayheadAnimationDurationMillis(automixReducedMotionEnabled(false, 1f)))
    }

    @Test
    fun sharedTasteBadgeUsesSignedHundredthsAndTheExactVisibilityThreshold() {
        assertEquals("your taste: +0.12", sharedInspectorTasteBadge(0.12, true))
        assertEquals("your taste: -0.08", sharedInspectorTasteBadge(-0.08, true))
        assertEquals("your taste: +0.01", sharedInspectorTasteBadge(0.01, true))
        assertEquals("your taste: -0.01", sharedInspectorTasteBadge(-0.01, true))
        assertEquals("your taste: +0.12", sharedInspectorTasteBadge(0.125, true))
        assertEquals("your taste: -0.12", sharedInspectorTasteBadge(-0.125, true))
        for (delta in listOf(0.0, -0.0, 0.009, -0.009, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(sharedInspectorTasteBadge(delta, true))
            assertEquals("Placement", sharedInspectorWhyText("Placement", delta, true))
        }
        assertNull(sharedInspectorTasteBadge(0.12, false))
    }

    @Test
    fun sharedWhyTextReplacesOldBadgesWithoutDuplicatingThem() {
        val first = sharedInspectorWhyText("  Phrase ends together  ", 0.12, true)
        assertEquals("Phrase ends together · your taste: +0.12", first)
        assertEquals(first, sharedInspectorWhyText(first, 0.12, true))
        assertEquals("Phrase ends together · your taste: -0.08", sharedInspectorWhyText(first, -0.08, true))
        assertEquals("Phrase ends together", sharedInspectorWhyText("$first · your taste: -0.08", 0.12, false))
        assertEquals("Phrase ends together", sharedInspectorWhyText(first, Double.NaN, true))
        assertEquals("your taste: +0.12", sharedInspectorWhyText(" ", 0.12, true))
        assertEquals("", sharedInspectorWhyText("your taste: +0.12", 0.12, false))
    }

    @Test
    fun sharedTasteCopyDoesNotDependOnTheDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("Placement · your taste: +0.12", sharedInspectorWhyText("Placement", 0.12, true))
            assertEquals("your taste: -0.08", sharedInspectorTasteBadge(-0.08, true))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun heldPopupKeepsItsPairReasonAndDeltaWhenTheLivePairChanges() {
        val held = inspector(MixInspectorPhase.RUNNING).copy(reason = "Old placement", tasteAdjustment = 0.12, learningEnabled = true)
        val live = held.copy(
            generation = 2, current = MixInspectorTrack("in", "Incoming"), next = MixInspectorTrack("third", "Third"),
            reason = "Next placement", tasteAdjustment = -0.08,
        )
        val displayed = inspectorHeldSnapshot(held, live)
        assertEquals(held, displayed)
        assertEquals("Old placement · your taste: +0.12", sharedInspectorWhyText(displayed.reason.orEmpty(), displayed.tasteAdjustment, displayed.learningEnabled))
        val hidden = inspectorHeldSnapshot(displayed, live.copy(learningEnabled = false))
        assertEquals(held.copy(learningEnabled = false), hidden)
        assertEquals("Old placement", sharedInspectorWhyText(hidden.reason.orEmpty(), hidden.tasteAdjustment, hidden.learningEnabled))
        assertEquals(held, inspectorHeldSnapshot(hidden, live))
    }

    @Test
    fun heldPopupRejectsRemixGenerationBleedAndMissingPolicyFailsClosed() {
        val held = inspector(MixInspectorPhase.RUNNING).copy(reason = "Frozen placement", tasteAdjustment = -0.08, learningEnabled = true)
        val remixed = held.copy(generation = held.generation + 1, reason = "Remix placement", tasteAdjustment = 0.12)
        assertEquals(held, inspectorHeldSnapshot(held, remixed))
        assertEquals(held.copy(learningEnabled = false), inspectorHeldSnapshot(held, null))
        assertEquals(held.copy(learningEnabled = false), inspectorHeldSnapshot(held, inspector(MixInspectorPhase.PREPARED).copy(generation = 9)))
        val sameGeneration = held.copy(phase = MixInspectorPhase.DONE, reason = "Completed placement", learningEnabled = false)
        assertEquals(sameGeneration, inspectorHeldSnapshot(held, sameGeneration))
    }

    private fun status(state: String, headline: String = "") = AutomixStatus(
        headline = headline,
        detail = "Diagnostics only",
        style = "blend",
        progress = 0.42f,
        state = state,
        planType = "Bass swap",
    )

    private fun inspector(phase: MixInspectorPhase) = MixInspectorState(
        generation = 1,
        current = MixInspectorTrack("out", "Outgoing"),
        next = MixInspectorTrack("in", "Incoming"),
        level = DjLevel.SUBTLE,
        phase = phase,
    )
}
