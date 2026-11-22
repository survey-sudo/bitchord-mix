package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * djmix11 graceful-exit guarantee: the outgoing deck may never go
 * audible→silence faster than the planned dissolve (800 ms floor), and
 * the outgoing lane must end muted with a real equal-power fall — never
 * parked at unity (the old RELAXED/VOCAL lanes' chop-at-unity bug).
 */
class ExitTailTest {

    private fun relaxedChoice() = PhraseChoice(
        template = MixTemplate.RELAXED,
        rung = "Smooth fade",
        score = 0.9,
        exit = ExitCue(
            kind = ExitKind.OUTRO_START,
            releaseBar = 51,
            releaseSec = 115.5,
            roomBars = 15,
            vocalRoomBars = 0,
            tailBars = 12,
            sectionFn = SectionFn.OUTRO,
            role = SectionRole.GROOVE,
            energyAtRelease = 0.2f,
            energyLevel = 2,
            loopableRoom = true,
            cutsSungBars = 0,
            cutsBars = 0,
        ),
        entry = EntryCue(
            kind = EntryKind.HEAD,
            startBar = 0,
            startSec = 0.0,
            landingBar = 4,
            landingFn = SectionFn.INTRO,
            landingRole = SectionRole.GROOVE,
            leadInBars = 0,
            leadInVocal = 0f,
            leadInLoopable = true,
            energyAtLanding = 0.4f,
            energyLevel = 4,
            trimmedBars = 0,
            pickupBeats = 0f,
        ),
        overlapBars = 15,
        swapBar = 0.0,
        tailBeats = 0.0,
        loopK = 0,
        echoGated = false,
        vocalProduct = 0f,
        tempoPrepBars = 1,
        echoDelayBeats = 0.75,
        echoDelayName = "ECHO_DELAY_BEATS",
        swapRampMs = 120,
        swapRampName = "SWAP_RAMP_BLEND_MS",
        tempoPolicy = "b",
        vocalOverlapBeats = 0.0,
        energyDelta = 0f,
        struct = 0.9,
        vocalScore = 0.0,
        phraseScore = 0.9,
        energyScore = 0.5,
        lengthScore = 0.5,
        keyScore = 0.5,
        tempoScore = 0.9,
        cost = 0.1,
    )

    @Test
    fun exitTailSecondsIsTwoBeatsFlooredAndCapped() {
        // 120 BPM: beat 0.5 s → exactly 2 beats = 1.0 s.
        assertEquals(1.0, PhraseConfig.exitTailSeconds(0.5), 1e-9)
        // 200 BPM: beat 0.3 s → 0.6 s would be under 800 ms; floor wins.
        assertEquals(0.8, PhraseConfig.exitTailSeconds(0.3), 1e-9)
        // 60 BPM: beat 1.0 s → 2.0 s, at the cap.
        assertEquals(2.0, PhraseConfig.exitTailSeconds(1.0), 1e-9)
        // 40 BPM: beat 1.5 s → 3.0 s would exceed the cap; cap wins.
        assertEquals(2.0, PhraseConfig.exitTailSeconds(1.5), 1e-9)
        assertEquals(350.0, PhraseConfig.EXIT_TAIL_EMERGENCY_MS, 1e-9)
        assertEquals(800.0, PhraseConfig.EXIT_TAIL_MIN_MS, 1e-9)
    }

    @Test
    fun relaxedOutgoingLaneEndsMutedAfterThePlannedDissolve() {
        val script = TransitionMoves.compile(relaxedChoice(), 106.0, 109.0)
        // The plan carries the tail.
        assertTrue(script.plan.exitTailSeconds >= 0.8, "tail ${script.plan.exitTailSeconds}")
        // The outgoing lane sinks to mute (equal power), never parked at 0 dB.
        val lane = script.outGain
        assertEquals(TransitionMoves.MUTE_DB, lane.values.last(), 1e-6f)
        // ...and the mute lands at least 2 beats past the landing (the dissolve).
        assertTrue(
            lane.beats.last() >= 2.0,
            "mute at ${lane.beats.last()} beats; planned tail is >= 2 beats",
        )
        // No step to a nonzero value after the mute.
        for (i in lane.values.indices) {
            if (lane.values[i] > -79f) {
                assertTrue(
                    i == lane.values.indices.last || lane.values[i + 1] <= lane.values[i] + 5f,
                    "outGain steps $i: ${lane.values[i]} -> ${lane.values[i + 1]}",
                )
            }
        }
    }

    @Test
    fun everyCompiledTemplateEndsMuted() {
        val templates = MixTemplate.entries.filter { it != MixTemplate.M2 && it != MixTemplate.M6 }
        for (t in templates) {
            val choice = relaxedChoice().copy(template = t, rung = "x")
            val script = TransitionMoves.compile(choice, 120.0, 120.0)
            assertEquals(
                TransitionMoves.MUTE_DB,
                script.outGain.values.last(),
                1e-6f,
            )
            assertTrue(
                script.outGain.beats.last() >= 1.0,
                "template $t ends at ${script.outGain.beats.last()}",
            )
            assertTrue(script.plan.exitTailSeconds >= 0.8)
        }
    }

    @Test
    fun laneFallNeverStepsMoreThan6DbInOneKnot() {
        // The exit curve is continuous: no audible→silence jump between knots.
        val script = TransitionMoves.compile(relaxedChoice(), 106.0, 109.0)
        val v = script.outGain.values
        for (i in 1 until v.size) {
            assertTrue(v[i] <= v[i - 1] + 6f, "step at $i: ${v[i - 1]} -> ${v[i]}")
        }
    }
}
