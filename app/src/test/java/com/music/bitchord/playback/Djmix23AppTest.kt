package com.music.bitchord.playback

import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.EntryCue
import com.music.bitchord.playback.smart.EntryKind
import com.music.bitchord.playback.smart.ExitCue
import com.music.bitchord.playback.smart.ExitKind
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.PhraseChoice
import com.music.bitchord.playback.smart.PhrasePlanner
import com.music.bitchord.playback.smart.SectionFn
import com.music.bitchord.playback.smart.SectionRole
import com.music.bitchord.playback.smart.TransitionMoves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * djmix23 `:app` session-adoption discipline (vc65).
 *
 * Pure JVM (no ExoPlayer): the vc64 06:23 freeze (Calvin pair ->
 * "This Is What You Came For" -> "One Kiss") left song 2 paused at 0:00 on
 * two consecutive loop mixes. Video + `mix.pause.detected` prove the pause is
 * reason 1 = USER_REQUEST (Media3 1.11.0), not focus loss (2): the session
 * was adopted onto a never-started incoming player. Two guards now keep that
 * shape out of driveLoopMix and finishLoop:
 *
 * 1. [canAdoptSessionPlayer]: `handOff` only once `loopIncomingStarted`.
 *   The loop clock's hold geometry can schedule the start (inStartAt) after
 *   the session handoff (handoffAt) — driveLoopMix holds the adoption until
 *   the start, which the renderer makes inaudible.
 * 2. [LoopMixSession.clampIncomingStartAt]: inStartAt is clamped strictly
 *   before elementAt with a decode lead, so the start cannot fall off the
 *   end of the mix entirely; finishLoop additionally starts the incoming
 *   player first if it was somehow still unstarted. The geometry tests pin
 *   both orderings against the real planner output.
 */
class Djmix23AppTest {

    // ---- Adoption guard ---------------------------------------------------

    @Test
    fun `unstarted incoming player is never adopted as the session`() {
        assertFalse(
            "canAdoptSessionPlayer(false) must be false: adopting a never-started " +
                "player makes the session a paused position-0 player (vc64 06:23 freeze)",
            canAdoptSessionPlayer(loopIncomingStarted = false),
        )
    }

    @Test
    fun `started incoming player is adopted as the session`() {
        assertTrue(canAdoptSessionPlayer(loopIncomingStarted = true))
    }

    // ---- Geometry helpers (copied cues; the clock math itself is the real one) ----

    private fun exitCue(bar: Int, sec: Double, room: Int) = ExitCue(
        kind = ExitKind.OUTRO_PHRASE,
        releaseBar = bar,
        releaseSec = sec,
        roomBars = room,
        vocalRoomBars = 0,
        tailBars = 0,
        sectionFn = SectionFn.OUTRO,
        role = SectionRole.GROOVE,
        energyAtRelease = 0.4f,
        energyLevel = 4,
        loopableRoom = true,
        cutsSungBars = 0,
        cutsBars = 0,
    )

    private fun entryCue(landingBar: Int) = EntryCue(
        kind = EntryKind.HEAD,
        startBar = 0,
        startSec = 0.0,
        landingBar = landingBar,
        landingFn = SectionFn.INTRO,
        landingRole = SectionRole.GROOVE,
        leadInBars = landingBar,
        leadInVocal = 0f,
        leadInLoopable = true,
        energyAtLanding = 0.4f,
        energyLevel = 4,
        trimmedBars = 0,
        pickupBeats = 0f,
    )

    private fun choiceFor(template: MixTemplate, overlapBars: Int, prepBars: Int) = PhraseChoice(
        template = template,
        rung = PhrasePlanner.rungOf(template),
        score = 0.85,
        exit = exitCue(bar = 72, sec = 170.0, room = overlapBars),
        entry = entryCue(landingBar = overlapBars),
        overlapBars = overlapBars,
        swapBar = -overlapBars / 2.0,
        tailBeats = 1.0,
        loopK = 0,
        echoGated = template == MixTemplate.M6,
        vocalProduct = 0f,
        tempoPrepBars = prepBars,
        echoDelayBeats = 0.75,
        echoDelayName = "ECHO_DELAY_BEATS",
        swapRampMs = 120,
        swapRampName = "SWAP_RAMP_BLEND_MS",
        tempoPolicy = "TEMPO_POLICY=b",
        vocalOverlapBeats = 0.0,
        energyDelta = 0f,
        struct = 0.9,
        vocalScore = 0.9,
        phraseScore = 0.9,
        energyScore = 0.9,
        lengthScore = 0.9,
        keyScore = 0.9,
        tempoScore = 0.9,
        cost = 0.0,
    )

    private fun clockFor(overlapBars: Int, prepBars: Int, outBpm: Double = 124.341, inBpm: Double = 124.250) =
        LoopMix.Clock(
            TransitionMoves.compile(
                choiceFor(MixTemplate.M1, overlapBars, prepBars),
                outBpm = outBpm,
                inBpm = inBpm,
            ).plan,
        )

    @Test
    fun `calvin hold geometry reorders start after handoff but before element`() {
        // Order (i): handoffAt < inStartAt < elementAt — the live vc64 shape.
        // handoffAt ~0.97, hold ~1.93, elementAt ~7.73. The adoption guard
        // holds the handoff until the start; the clamp preserves the decode
        // lead before the element.
        val clock = clockFor(overlapBars = 1, prepBars = 0)
        assertTrue("hold must be > 0 (hold branch live), was ${clock.incomingHoldSec}", clock.incomingHoldSec > 0.0)
        assertTrue(
            "handoverTrack must be >= 2.5 (branch precondition), was ${clock.elementTrackTime}",
            clock.elementTrackTime >= 2.5,
        )
        val (inStartAt, inStartTrack) = LoopMixSession.incomingStartFor(clock)
        assertTrue(
            "inStartAt $inStartAt must be AFTER handoffAt ${clock.handoffAt}: " +
                "that reorder is why vc64 adopted the session paused",
            inStartAt > clock.handoffAt + 0.5,
        )
        assertTrue(
            "inStartAt $inStartAt must stay a decode lead before elementAt " +
                "${clock.elementAt} (clamped), not fall off the end of the mix",
            inStartAt < clock.elementAt - LoopMixSession.STARTUP_LEAD + 1e-9,
        )
        assertTrue("inStartTrack must sit at track start (video frozen 0:00), was $inStartTrack", inStartTrack < 0.5)
        assertEquals(0.5, LoopMixSession.STARTUP_LEAD, 1e-9)
    }

    @Test
    fun `start past elementAt is clamped strictly before the end`() {
        // Order (ii): elementAt <= pre-clamp inStartAt — the clamp keeps the
        // start inside the mix with exactly the decode lead, so the start
        // still fires and the mix completes onto a started player instead of
        // an unstarted one.
        val clamped = LoopMixSession.clampIncomingStartAt(inStartAt = 8.0, elementAt = 8.0)
        assertEquals("a start at elementAt must clamp to a lead before it", 7.5, clamped, 1e-9)
        val alsoClamped = LoopMixSession.clampIncomingStartAt(inStartAt = 9.0, elementAt = 8.0)
        assertEquals("a start past elementAt must clamp to a lead before it", 7.5, alsoClamped, 1e-9)
        val untouched = LoopMixSession.clampIncomingStartAt(inStartAt = 1.93, elementAt = 7.73)
        assertEquals("an in-mix start must be untouched", 1.93, untouched, 1e-9)
    }

    @Test
    fun `element past start never lands on a never-started player`() {
        // Sweep the real planner grid used by the probe: every plan's
        // incoming start must satisfy handoffAt-agnostic invariants the
        // handoff guard and finishLoop pre-start rely on: start strictly
        // inside the mix with decode head-room, track position at the start.
        for (ov in 1..16) {
            for (prep in 0..12) {
                val clock = clockFor(overlapBars = ov, prepBars = prep)
                val (inStartAt, inStartTrack) = LoopMixSession.incomingStartFor(clock)
                assertTrue(
                    "ov=$ov prep=$prep: inStartAt $inStartAt must be < elementAt ${clock.elementAt}",
                    inStartAt < clock.elementAt,
                )
                assertTrue(
                    "ov=$ov prep=$prep: inStartAt $inStartAt must keep a decode lead before " +
                        "elementAt ${clock.elementAt}",
                    inStartAt <= clock.elementAt - LoopMixSession.STARTUP_LEAD + 1e-9,
                )
                assertTrue("ov=$ov prep=$prep: inStartTrack $inStartTrack must be >= 0", inStartTrack >= 0.0)
                assertTrue(
                    "ov=$ov prep=$prep: inStartTrack $inStartTrack must be <= handoverTrack " +
                        "${clock.elementTrackTime}",
                    inStartTrack <= clock.elementTrackTime + 1e-9,
                )
            }
        }
    }

    // ---- User-pause-never-resumed invariant (vc65) ------------------------

    @Test
    fun `user pause is never auto-resumed by the handoff guard`() {
        assertFalse(
            shouldRestorePlayAfterFocusSwap(wasPlaying = true, userPaused = true, focusHeld = true),
        )
        assertFalse(
            shouldRestorePlayAfterFocusSwap(wasPlaying = true, userPaused = true, focusHeld = false),
        )
    }

    @Test
    fun `playing through a handoff still restores only when focus is held`() {
        assertTrue(
            shouldRestorePlayAfterFocusSwap(wasPlaying = true, userPaused = false, focusHeld = true),
        )
        assertFalse(
            shouldRestorePlayAfterFocusSwap(wasPlaying = true, userPaused = false, focusHeld = false),
        )
    }
}
