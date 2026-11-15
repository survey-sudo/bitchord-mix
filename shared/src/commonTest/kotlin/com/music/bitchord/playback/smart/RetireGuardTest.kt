package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * djmix12 retire guard: an audible player may never be retired without a
 * dissolve first.
 */
class RetireGuardTest {

    @Test fun loudPlayerNeedsATail() {
        assertTrue(RetireGuard.audible(1f))
        assertFalse(RetireGuard.canRetire(1f))
        assertTrue(RetireGuard.canRetire(1f, force = true))
    }

    @Test fun silentPlayerRetiresFreely() {
        assertFalse(RetireGuard.audible(0.001f))
        assertFalse(RetireGuard.audible(0f))
        assertTrue(RetireGuard.canRetire(0f))
    }

    @Test fun dissolveLengthsMatchTheSpec() {
        assertEquals(800L, RetireGuard.dissolveMs(planned = true))
        assertEquals(350L, RetireGuard.dissolveMs(planned = false))
    }

    @Test fun gainDbIsMinusSixtyAtTheGate() {
        assertEquals(-60.0, RetireGuard.gainDb(0.001f), 0.001)
    }

    @Test fun relaxedLaneFallsMonotoneToMute() {
        val script = TransitionMoves.compile(
            PhraseChoice(
                template = MixTemplate.RELAXED, rung = "Smooth fade", score = 0.9,
                exit = ExitCue(ExitKind.OUTRO_START, 51, 115.5, 15, 0, 12, SectionFn.OUTRO, SectionRole.GROOVE, 0.2f, 2, true, 0, 0),
                entry = EntryCue(EntryKind.HEAD, 0, 0.0, 4, SectionFn.INTRO, SectionRole.GROOVE, 0, 0f, true, 0.4f, 4, 0, 0f),
                overlapBars = 15, swapBar = 0.0, tailBeats = 0.0, loopK = 0, echoGated = false,
                vocalProduct = 0f, tempoPrepBars = 1, echoDelayBeats = 0.75, echoDelayName = "ECHO_DELAY_BEATS",
                swapRampMs = 120, swapRampName = "SWAP_RAMP_BLEND_MS", tempoPolicy = "b",
                vocalOverlapBeats = 0.0, energyDelta = 0f, struct = 0.9, vocalScore = 0.0,
                phraseScore = 0.9, energyScore = 0.5, lengthScore = 0.5, keyScore = 0.5, tempoScore = 0.9, cost = 0.1,
            ),
            106.0, 109.0,
        )
        val v = script.outGain.values
        val b = script.outGain.beats
        assertEquals(TransitionMoves.MUTE_DB, v.last(), 1e-6f)
        for (i in 1 until v.size) {
            assertTrue(v[i] <= v[i - 1] + 1e-6f, "monotone at $i: ${v[i - 1]} -> ${v[i]}")
            assertTrue(b[i] > b[i - 1], "beats advance at $i")
            // The steep end lives only in the inaudible region: once at or
            // below -50 dB, the remaining fall may be fast. Above that, the
            // per-knot span covers ≥25 ms of the >=800 ms dissolve.
            if (v[i - 1] > -50f && v[i] > -70f) {
                assertTrue(v[i - 1] - v[i] <= 25f, "step at $i: ${v[i - 1]} -> ${v[i]}")
            }
        }
    }
}
