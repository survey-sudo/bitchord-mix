package com.music.bitchord.playback.smart

import com.music.bitchord.playback.smart.choreo.DjLevel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixInspectorStateTest {
    @BeforeTest
    @AfterTest
    fun clearFeed() {
        MixInspectorFeed.setRemixAction(null)
        MixInspectorFeed.publish(null)
    }

    @Test
    fun existingPositionalConstructorFailsClosedForTaste() {
        val state = MixInspectorState(7, outgoing, incoming, DjLevel.SUBTLE)
        assertEquals(0.0, state.tasteAdjustment)
        assertFalse(state.learningEnabled)
        assertEquals(MixInspectorKey(7, "out", "in"), state.key)
    }

    @Test
    fun phaseCopiesAndPublicationPreserveFrozenTasteAndIdentity() {
        val frozen = fixture().copy(tasteAdjustment = -0.08, learningEnabled = true)
        for (phase in listOf(MixInspectorPhase.PREPARED, MixInspectorPhase.ARMED, MixInspectorPhase.RUNNING, MixInspectorPhase.DONE)) {
            MixInspectorFeed.publish(frozen.copy(phase = phase, reason = "Same frozen plan"))
            val published = assertNotNull(MixInspectorFeed.state.value)
            assertEquals(frozen.key, published.key)
            assertEquals(-0.08, published.tasteAdjustment)
            assertTrue(published.learningEnabled)
            assertEquals(phase == MixInspectorPhase.RUNNING, published.mixActive)
        }
        val disabled = frozen.copy(learningEnabled = false)
        assertEquals(-0.08, disabled.tasteAdjustment)
        assertFalse(disabled.learningEnabled)
    }

    @Test
    fun publicationDetachesRankedCandidatesAndEffects() {
        val candidates = (1..4).map {
            MixInspectorRecipeScore("S$it", "Recipe $it", it / 10.0)
        }.toMutableList()
        val effects = mutableListOf("echo", "filter")
        MixInspectorFeed.publish(fixture().copy(
            topCandidates = candidates, effects = effects, tasteAdjustment = 0.12, learningEnabled = true,
        ))
        val snapshot = assertNotNull(MixInspectorFeed.state.value)
        candidates.clear()
        effects.clear()
        assertEquals(listOf("S1", "S2", "S3"), snapshot.topCandidates.map { it.recipeId })
        assertEquals(listOf("echo", "filter"), snapshot.effects)
        assertEquals(0.12, snapshot.tasteAdjustment)
        assertTrue(snapshot.learningEnabled)
    }

    @Test
    fun remixChecksGenerationBothTracksAvailabilityAndEngineAdmission() {
        val state = fixture().copy(remixAvailable = true, tasteAdjustment = 0.12, learningEnabled = true)
        var calls = 0
        var engineAccepts = true
        MixInspectorFeed.setRemixAction { key ->
            assertEquals(state.key, key)
            calls++
            engineAccepts
        }
        MixInspectorFeed.publish(state)
        assertFalse(MixInspectorFeed.remixCurrentTransition(state.key.copy(generation = 8)))
        assertFalse(MixInspectorFeed.remixCurrentTransition(state.key.copy(outId = "other")))
        assertFalse(MixInspectorFeed.remixCurrentTransition(state.key.copy(inId = "other")))
        assertEquals(0, calls)
        assertTrue(MixInspectorFeed.remixCurrentTransition(state.key))
        engineAccepts = false
        assertFalse(MixInspectorFeed.remixCurrentTransition(state.key))
        assertEquals(2, calls)
        MixInspectorFeed.publish(state.copy(remixAvailable = false))
        assertFalse(MixInspectorFeed.remixCurrentTransition(state.key))
        assertEquals(2, calls)
    }

    @Test
    fun clearedFeedOrDetachedEngineCannotAcceptRemix() {
        val state = fixture().copy(remixAvailable = true)
        MixInspectorFeed.publish(state)
        assertFalse(MixInspectorFeed.remixCurrentTransition(state.key))
        MixInspectorFeed.setRemixAction { true }
        MixInspectorFeed.publish(null)
        assertNull(MixInspectorFeed.state.value)
        assertFalse(MixInspectorFeed.remixCurrentTransition(state.key))
    }

    private fun fixture() = MixInspectorState(7, outgoing, incoming, DjLevel.SUBTLE)

    private val outgoing = MixInspectorTrack("out", "Outgoing")
    private val incoming = MixInspectorTrack("in", "Incoming")
}
