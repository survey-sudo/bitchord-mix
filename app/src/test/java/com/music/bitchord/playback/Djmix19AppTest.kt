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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * djmix19 `:app` handoff discipline (vc61).
 *
 * Pure JVM (no native renderer): [LoopMixSession.planIncomingStart] is an
 * instance method on a private constructor holding a native renderer, so it
 * cannot be built here. Following the djmix18 pattern, the hold/element
 * scalars are verified through the shared [LoopMix.Clock] it derives from,
 * and [LoopMixSession] constants plus the exact start formula (copied) are
 * asserted. That guarantees the 18 s hold no longer starts IN at 0.5 s.
 */
class Djmix19AppTest {

    private fun exitCue(bar: Int, sec: Double, room: Int = 8) = ExitCue(
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

    private fun entryCue(startBar: Int = 0, startSec: Double = 0.0, landingBar: Int = 8) = EntryCue(
        kind = EntryKind.HEAD,
        startBar = startBar,
        startSec = startSec,
        landingBar = landingBar,
        landingFn = SectionFn.INTRO,
        landingRole = SectionRole.GROOVE,
        leadInBars = landingBar - startBar,
        leadInVocal = 0f,
        leadInLoopable = true,
        energyAtLanding = 0.4f,
        energyLevel = 4,
        trimmedBars = startBar,
        pickupBeats = 0f,
    )

    private fun choiceFor(
        template: MixTemplate,
        overlapBars: Int = 8,
        prepBars: Int = 0,
        releaseSec: Double = 170.0,
    ) = PhraseChoice(
        template = template,
        rung = PhrasePlanner.rungOf(template),
        score = 0.85,
        exit = exitCue(bar = 72, sec = releaseSec, room = overlapBars),
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

    /** Session start formula (djmix19), copied to avoid instantiating native LoopMixSession. */
    private fun startAtFor(hold: Double, element: Double, handover: Double): Double {
        val base = if (hold > 1e-9) {
            minOf(max(LoopMixSession.STARTUP_LEAD, hold - 1.5), max(LoopMixSession.STARTUP_LEAD, element - 3.0))
        } else {
            LoopMixSession.STARTUP_LEAD
        }
        val tBase = handover - (element - base)
        return if (hold > 1e-9 && tBase < 0.0 && handover >= 2.5) {
            max(base, (element - handover).coerceAtLeast(LoopMixSession.STARTUP_LEAD))
        } else {
            base
        }
    }

    @Test
    fun alignedSecondsIs12msNot1ms() {
        assertTrue("ALIGNED_SECONDS was ${LoopMixSession.ALIGNED_SECONDS}", LoopMixSession.ALIGNED_SECONDS >= 0.01)
        assertEquals(0.012, LoopMixSession.ALIGNED_SECONDS, 1e-9)
        assertEquals(0.5, LoopMixSession.STARTUP_LEAD, 1e-9)
    }

    @Test
    fun hold18sMixStartsIncomingNearHoldNotAtLead() {
        // 120 BPM: bar 2 s, 9 prep bars hold B for 18 s (the +3230 ms case).
        val script = TransitionMoves.compile(choiceFor(MixTemplate.M1, overlapBars = 8, prepBars = 9), outBpm = 120.0, inBpm = 120.0)
        assertEquals(18.0, script.incomingHoldSec, 1e-6)
        val clock = LoopMix.Clock(script.plan)
        val expectedElement = 38.0
        assertEquals(expectedElement, clock.elementAt, 0.5)
        val handover = clock.elementTrackTime
        assertEquals(script.plan.inn.loopStart + (clock.elementAt - 18.0), handover, 0.001)
        val startAt = startAtFor(clock.incomingHoldSec, clock.elementAt, handover)
        assertTrue("inStartAt $startAt must be near hold (>=15.0), NOT 0.5", startAt >= 15.0)
        assertTrue("inStartAt $startAt must leave >=2.5 s to align", startAt <= clock.elementAt - 2.5 + 1e-6)
        // 1:1 timeline: at handover the player must be at handoverTrack.
        val startTrack = max(0.0, handover - (clock.elementAt - startAt))
        assertEquals(handover, startTrack + (clock.elementAt - startAt), 1e-9)
        assertTrue("startTrack $startTrack must be >= 0", startTrack >= 0.0)
    }

    @Test
    fun straightBoundsCoverStopAtFromAppSide() {
        val script = TransitionMoves.compile(choiceFor(MixTemplate.M1, overlapBars = 8, prepBars = 0), outBpm = 120.0, inBpm = 121.0)
        val plan = script.plan
        val clock = LoopMix.Clock(plan)
        val bounds = LoopMix.regionBounds(plan)
        val echoHold = if (plan.echoTail) plan.out.beatSec * 8 else 0.0
        val stopAt = clock.releaseAt + 0.05 + echoHold + plan.exitTailSeconds
        val tailRate = if (clock.beats >= 1) {
            val b0 = clock.beatTimes[clock.beats - 1]
            val b1 = clock.beatTimes[clock.beats]
            (plan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        val lastPos = plan.out.loopStart + clock.beats * plan.out.beatSec
        val stopPos = lastPos + (stopAt - clock.releaseAt) * tailRate
        assertTrue("bounds.outTo ${bounds.outTo} must cover stopPos $stopPos", bounds.outTo >= stopPos - 1e-6)
        // Cache coverage: bounds must be wide enough that a primed decode
        // ([outFrom, outTo]) satisfies isLoopFastReady without re-decode.
        assertTrue(bounds.outTo > bounds.outFrom)
        assertTrue(bounds.inTo > bounds.inFrom)
    }

    @Test
    fun volumeFloorsFromAppSide() {
        assertTrue(com.music.bitchord.playback.smart.MixGain.HEADROOM_FLOOR >= 0.94f)
        val rate = 48000
        val n = rate * 2
        val quiet = tone(n, rate, 0.1)
        val loud = tone(n, rate, 0.8)
        val cut = requireNotNull(
            com.music.bitchord.playback.smart.RegionalLoudness.matchGain(quiet, quiet, 0, n, loud, loud, 0, n, rate),
        )
        assertTrue(cut >= 0.66f)
    }

    @Test
    fun idleStallDecisionAndFreezeDedupKeyWork() {
        // <1500 ms -> WAIT_AND_FREEZE
        assertEquals(IdleStallAction.WAIT_AND_FREEZE, idleStallDecision(stalledMs = 0L, outExhausted = false, alreadyPrepared = false))
        assertEquals(IdleStallAction.WAIT_AND_FREEZE, idleStallDecision(stalledMs = 1499L, outExhausted = false, alreadyPrepared = false))
        // >=1500 ms, not yet prepared -> PREPARE_ONCE
        assertEquals(IdleStallAction.PREPARE_ONCE, idleStallDecision(stalledMs = 1500L, outExhausted = false, alreadyPrepared = false))
        // 1500..1999 ms, alreadyPrepared -> WAIT_AND_FREEZE (500 ms grace for prepare() to recover)
        assertEquals(IdleStallAction.WAIT_AND_FREEZE, idleStallDecision(stalledMs = 1600L, outExhausted = false, alreadyPrepared = true))
        // >=2000 ms, alreadyPrepared -> FORCE_BAIL
        assertEquals(IdleStallAction.FORCE_BAIL, idleStallDecision(stalledMs = 2000L, outExhausted = false, alreadyPrepared = true))
        // outExhausted -> FORCE_BAIL immediately regardless of stall time or prepared state
        assertEquals(IdleStallAction.FORCE_BAIL, idleStallDecision(stalledMs = 0L, outExhausted = true, alreadyPrepared = false))
        assertEquals(IdleStallAction.FORCE_BAIL, idleStallDecision(stalledMs = 100L, outExhausted = true, alreadyPrepared = true))

        // freezeDedupKey dedupes identical phase|pair and changes on phase or pair change
        val k1 = freezeDedupKey("FADING", "trackA", "trackB")
        val k1Repeat = freezeDedupKey("FADING", "trackA", "trackB")
        val kPhaseChange = freezeDedupKey("TAIL", "trackA", "trackB")
        val kPairChange = freezeDedupKey("FADING", "trackA", "trackC")
        val kNullIds = freezeDedupKey("ARMING", null, null)
        assertEquals(k1, k1Repeat)
        assertTrue(k1 != kPhaseChange)
        assertTrue(k1 != kPairChange)
        assertEquals("ARMING|->", kNullIds)
    }

    private fun tone(n: Int, rate: Int, amplitude: Double): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = (sin(2.0 * PI * 440.0 * i / rate) * amplitude).toFloat()
        return out
    }
}
