package com.music.bitchord.playback

import com.music.bitchord.playback.smart.MixGain
import com.music.bitchord.playback.smart.RegionalLoudness
import com.music.bitchord.playback.smart.TransitionMoves
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
import com.music.bitchord.playback.smart.nativeTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * djmix20 app-side proofs (vc62). Pure JVM: native LoopMixSession cannot be
 * built here, so writer-side scalars are checked via LoopMix.Clock and the
 * shared freeze/stall helpers the controller calls.
 */
class Djmix20AppTest {

    private fun choiceFor(template: MixTemplate, overlapBars: Int = 8): PhraseChoice {
        val exit = ExitCue(ExitKind.OUTRO_PHRASE, 72, 170.0, 8, 0, 0, SectionFn.OUTRO, SectionRole.GROOVE, 0.4f, 4, true, 0, 0)
        val entry = EntryCue(EntryKind.HEAD, 0, 0.0, 8, SectionFn.INTRO, SectionRole.GROOVE, 8, 0f, true, 0.4f, 4, 0, 0f)
        return PhraseChoice(
            template = template, rung = PhrasePlanner.rungOf(template), score = 0.9,
            handoff = if (template == MixTemplate.M6) requireNotNull(nativeTiming(
                168.0, 168.0, 169.625, 170.0, 0.0, 0.0, 16.0, 18.0, 0.0, 5.05)) else null,
            exit = exit, entry = entry, overlapBars = overlapBars,
            swapBar = -overlapBars / 2.0, tailBeats = 1.0, loopK = 0,
            echoGated = template == MixTemplate.M6, vocalProduct = 0f, tempoPrepBars = 0,
            echoDelayBeats = 0.75, echoDelayName = "ECHO_DELAY_BEATS",
            swapRampMs = 120, swapRampName = "SWAP_RAMP_BLEND_MS", tempoPolicy = "TEMPO_POLICY=b",
            vocalOverlapBeats = 0.0, energyDelta = 0f, struct = 0.9, vocalScore = 0.9,
            phraseScore = 0.9, energyScore = 0.9, lengthScore = 0.9, keyScore = 0.9, tempoScore = 0.9, cost = 0.0,
        )
    }

    @Test
    fun idleStallForceExitStillDeterministic() {
        // vc61 Fix 5 must not regress: 1.5 s prepare-once, 2.0 s force bail.
        assertEquals(IdleStallAction.WAIT_AND_FREEZE, idleStallDecision(0L, false, false))
        assertEquals(IdleStallAction.PREPARE_ONCE, idleStallDecision(1500L, false, false))
        assertEquals(IdleStallAction.FORCE_BAIL, idleStallDecision(2000L, false, true))
        assertEquals(IdleStallAction.FORCE_BAIL, idleStallDecision(0L, true, false))
        val k = freezeDedupKey("FADING", "a", "b")
        assertEquals(k, freezeDedupKey("FADING", "a", "b"))
        assertTrue(k != freezeDedupKey("TAIL", "a", "b"))
    }

    @Test
    fun echoTailIsInDeckBoundsForLoopPath() {
        // P1-6 planner/lane level: LoopMix bounds must cover the echo tail so
        // the tail rings past EOF instead of being truncated.
        val script = TransitionMoves.compile(choiceFor(MixTemplate.M6, overlapBars = 0), 120.0, 120.0)
        val clock = LoopMix.Clock(script.plan)
        val bounds = LoopMix.regionBounds(script.plan)
        val echoHold = if (script.plan.echoTail) script.plan.out.beatSec * 8 else 0.0
        val stopAt = clock.releaseAt + 0.05 + echoHold + script.plan.exitTailSeconds
        // outTo must be at least the track position at stopAt (straight deck).
        val tailRate = if (clock.beats >= 1) {
            val b0 = clock.beatTimes[clock.beats - 1]; val b1 = clock.beatTimes[clock.beats]
            (script.plan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        val lastPos = script.plan.out.loopStart + clock.beats * script.plan.out.beatSec
        val stopPos = lastPos + (stopAt - clock.releaseAt) * tailRate
        assertTrue("bounds.outTo=${bounds.outTo} stopPos=$stopPos", bounds.outTo >= stopPos - 1e-6)
        assertTrue(bounds.outTo > bounds.outFrom)
    }

    @Test
    fun loopBuildNullFallbackDecisionIsSameTickWhenArming() {
        // P0-3 audit as a pure rule: build-null while LOOP_ARMING must yield
        // to classic immediately (loopFallBack same tick), not ride to EOF.
        // The controller's startLoopBuild implements this; here we assert the
        // scalar that drives it (yield decisions) stay conservative.
        assertTrue(LoopMix.shouldYieldColdLoop(100_000L, 95_000L, fastReady = false, building = true, blendArmLeadMs = 0L, nextStreamReady = true))
        assertTrue(!LoopMix.shouldYieldColdLoop(100_000L, 95_000L, fastReady = true, building = true, blendArmLeadMs = 0L, nextStreamReady = true))
    }

    @Test
    fun volumeFloorsStay() {
        assertTrue(MixGain.HEADROOM_FLOOR >= 0.94f)
        val n = 96000
        val quiet = tone(n, 48000, 0.1); val loud = tone(n, 48000, 0.8)
        val cut = requireNotNull(RegionalLoudness.matchGain(quiet, quiet, 0, n, loud, loud, 0, n, 48000))
        assertTrue(cut >= 0.66f)
    }

    private fun tone(n: Int, rate: Int, amplitude: Double): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = (sin(2.0 * PI * 440.0 * i / rate) * amplitude).toFloat()
        return out
    }
}
