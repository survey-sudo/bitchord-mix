package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongSectionsTest {
    @Test
    fun finalPartialCueIsIgnoredWithoutChangingValidSections() {
        val analysis = TrackAnalysis(duration = 10.0, contentEndTime = 10.0)
        val valid = listOf(LyricCue(2.0, 4.0, "a normal verse line"))
        val partial = LyricCue(9.8, 10.0, "clipped final line")
        val sections = labelSections(analysis, valid + partial)

        assertEquals(labelSections(analysis, valid), sections)
        assertEquals(SectionKind.VERSE, sectionAt(sections, 3.0)?.kind)
        assertTrue(sections.all { it.start >= 0.0 && it.start < it.end && it.end <= 10.0 })
        assertEquals(labelSections(analysis), labelSections(analysis, listOf(partial)))
    }

    @Test
    fun ordinaryShortCuesStillReceiveTheMinimumSpan() {
        val analysis = TrackAnalysis(duration = 10.0, contentEndTime = 10.0)
        assertEquals(
            labelSections(analysis, listOf(LyricCue(2.0, 2.3, "short line"))),
            labelSections(analysis, listOf(LyricCue(2.0, 2.01, "short line"))),
        )
    }

    @Test
    fun smartPlanningHandlesFinalPartialCueOnEitherDeck() {
        // Same steady, ready analysis shape used by DjMixTest's matched-pair plan.
        val outgoing = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "a",
            duration = 200.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            downbeats = List(100) { it * 2.0 },
            key = "C major",
            keyConfidence = 0.9,
            contentEndTime = 196.0,
            audibleStartTime = 0.5,
        )
        val incoming = outgoing.copy(trackId = "b")
        val valid = listOf(LyricCue(20.0, 24.0, "a normal verse line"))
        val clipped = valid + LyricCue(195.8, 196.0, "clipped final line")

        for ((lyricsOut, lyricsIn) in listOf(clipped to valid, valid to clipped, clipped to clipped)) {
            val plan = planTransition(
                analysis = outgoing,
                nextAnalysis = incoming,
                currentTrack = TransitionTrackInfo("a", 200_000),
                nextTrack = TransitionTrackInfo("b", 200_000),
                currentTime = 10.0,
                duration = 200.0,
                mode = CrossfadeMode.SMART,
                advanced = true,
                lyricsOut = lyricsOut,
                lyricsIn = lyricsIn,
            )
            assertFalse(plan.blocked, "tail cue must not prevent a valid SMART plan: ${plan.reason}")
            assertTrue(plan.transitionStart.isFinite() && plan.transitionEnd.isFinite())
            if (plan.transitionStyle == TransitionStyle.NATURAL) {
                assertEquals(200.0, plan.transitionStart)
                assertEquals(200.0, plan.transitionEnd)
                assertEquals(0.0, plan.fadeSeconds)
            } else {
                assertTrue(plan.transitionStart >= 0.0 && plan.transitionEnd > plan.transitionStart)
                assertTrue(plan.fadeSeconds > 0.0 && plan.fadeSeconds.isFinite())
            }
            assertTrue(plan.mixSummary.isNotBlank(), "SMART plan must pass through DJ annotation")
        }
    }
}
