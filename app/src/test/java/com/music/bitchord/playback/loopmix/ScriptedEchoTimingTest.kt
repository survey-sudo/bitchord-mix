package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.withMeasuredEchoTestEvidence

import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.EndingKind
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhrasePlanner
import com.music.bitchord.playback.smart.PhraseSection
import com.music.bitchord.playback.smart.SectionFn
import com.music.bitchord.playback.smart.SectionRole
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.StartKind
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionMoves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Planning -> compilation -> production session -> required real host JNI. */
class ScriptedEchoTimingTest {
    @Before
    fun requireRealHostJni() {
        assumeTrue("Dedicated host-JNI execution only",
            System.getProperty("bitchord.loopmix.hostNativeRequired") == "true")
        assertTrue("Required libbitchord_loopmix.so did not load", LoopMixRenderer.available)
    }

    @Test(timeout = 120_000)
    fun equalTempoColdAttackStartsAtDryEndWithItsPassbandAlreadyOpen() {
        val fixture = Fixture(120.0, 120.0, 0)
        val h = requireNotNull(fixture.script.handoff)
        assertEquals(h.outDryEnd.mixSec, h.inHoldMixSec, 1e-9)
        assertTrue("Cold B must not begin two seconds early", h.inHoldMixSec >= 1.99)
        val pcm = fixture.renderIncoming()
        assertTrue("IN advanced or leaked before its protected cue",
            rms(pcm, h.inHoldMixSec - 0.2, h.inHoldMixSec - 0.002) < 1e-6)
        val leadingSilence = leadingSilenceFrames(pcm, h.inHoldMixSec, 0.020, 0.005f)
        assertTrue("Cold B remained silent $leadingSilence frames after its exact cue",
            leadingSilence <= (0.005 * RATE).roundToInt())
        val attack = rms(pcm, h.inHoldMixSec + 0.003, h.inHoldMixSec + 0.018)
        val later = rms(pcm, h.inHoldMixSec + 0.025, h.inHoldMixSec + 0.045)
        assertTrue("First high-frequency attack lost while gain/filter opened: attack=$attack later=$later",
            attack > 0.01 && attack > later * 0.70)
        println("ScriptedEcho cold hold=${h.inHoldMixSec} dryEnd=${h.outDryEnd.mixSec} leadingSilenceFrames=$leadingSilence firstAttackRms=$attack laterRms=$later")
    }

    @Test(timeout = 120_000)
    fun unequalTemposRenderTheUntrimmedEightBarLeadAndActualSourceGridLanding() {
        val fixture = Fixture(100.0, 135.0, 8, drift = 0.003)
        val h = requireNotNull(fixture.script.handoff)
        assertEquals(0.0, fixture.script.plan.inn.loopStart, 1e-9)
        assertEquals(fixture.inMap.barTime(8), h.inLanding.sourceSec, 1e-9)
        assertEquals(h.inHoldMixSec + h.inLanding.sourceSec, h.inLanding.mixSec, 1e-9)
        assertTrue(LoopMix.Clock(fixture.script.plan).releaseAt >= h.inLanding.mixSec)
        assertTrue(LoopMix.Clock(fixture.script.plan).elementAt <= PhraseConfig.O_MAX_SEC)
        val pcm = fixture.renderIncoming()
        assertTrue("First cue was consumed while B was held",
            rms(pcm, h.inHoldMixSec + 0.003, h.inHoldMixSec + 0.045) > 0.01)
        assertTrue("Incoming landing marker played early",
            rms(pcm, h.inLanding.mixSec - 0.65, h.inLanding.mixSec - 0.01) < 1e-6)
        assertTrue("Actual JNI missed B's declared landing",
            rms(pcm, h.inLanding.mixSec + 0.003, h.inLanding.mixSec + 0.045) > 0.01)
        fixture.assertGeneratedContractLanes()
        println("ScriptedEcho unequal cue=${h.inCueSourceSec} hold=${h.inHoldMixSec} landingSource=${h.inLanding.sourceSec} landingMix=${h.inLanding.mixSec}")
    }

    private class Fixture(outBpm: Double, inBpm: Double, leadBars: Int, drift: Double = 0.0) {
        private val outMap = song("generated-out", outBpm, null)
        val inMap = song("generated-in", inBpm, leadBars, drift)
        private val outAnalysis = analysis(outMap)
        private val inAnalysis = analysis(inMap)
        private data class InstalledEnvelope(val times: DoubleArray, val values: FloatArray)
        private val installedEnvelopes = mutableMapOf<Pair<Int, Int>, MutableList<InstalledEnvelope>>()
        private val choice = requireNotNull(PhrasePlanner.select(outMap, inMap, 0, outBpm, inBpm,
            outAnalysis = outAnalysis, inAnalysis = inAnalysis))
        val script = TransitionMoves.compile(choice, outBpm, inBpm)

        init {
            assertEquals(MixTemplate.M6, choice.template)
            assertSame(choice.handoff, script.handoff)
            assertEquals(choice.entry.startSec, script.plan.inn.loopStart, 1e-9)
        }

        fun renderIncoming(): FloatArray {
            val h = requireNotNull(script.handoff)
            val refusals = mutableListOf<String>()
            val session = LoopMixSession.build(script.plan, "generated-out", "generated-in", RATE, 1f,
                highQuality = false, script = script, outAnalysis = outAnalysis, inAnalysis = inAnalysis,
                onRefusal = { refusals += it },
                onEnvelopeInstalled = { deck, kind, times, values ->
                    installedEnvelopes.getOrPut(deck to kind) { mutableListOf() } +=
                        InstalledEnvelope(times.copyOf(), values.copyOf())
                }) { side, from, to ->
                val count = floor((to - from) * RATE).toInt()
                assertTrue("Invalid PCM request $from..$to", from >= 0 && count in 1..(RATE * 90))
                val samples = FloatArray(count) { i ->
                    val source = from + i.toDouble() / RATE
                    if (side == LoopMix.Side.OUT) (0.08 * sin(2 * PI * 510 * source)).toFloat()
                    else {
                        val cueAge = source - h.inCueSourceSec
                        val landingAge = source - h.inLanding.sourceSec
                        val age = when {
                            cueAge in 0.0..0.055 -> cueAge
                            landingAge in 0.0..0.055 -> landingAge
                            else -> -1.0
                        }
                        if (age < 0) 0f else (0.08 * sin(2 * PI * 6_000 * age)).toFloat()
                    }
                }
                LoopRegion(samples, samples.copyOf(), from)
            }
            assertNotNull("Required scripted session refused: $refusals", session)
            val built = requireNotNull(session)
            try {
                assertSame(script.plan.handoff, built.plan.handoff)
                val renderer = built.renderer
                assertTrue("Actual native handle required", renderer.isValid)
                // Observe the production incoming knots, gain and filter. Only
                // suppress OUT so cue markers can be timed without subtraction.
                renderer.setEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN, doubleArrayOf(0.0), floatArrayOf(0f))
                renderer.setEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND, doubleArrayOf(0.0), floatArrayOf(0f))
                renderer.setEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_REVERB_SEND, doubleArrayOf(0.0), floatArrayOf(0f))
                val endFrame = ((h.inLanding.mixSec + 0.15) * RATE).roundToInt()
                val output = FloatArray(endFrame * 2)
                val block = FloatArray(512 * 2)
                var frame = renderer.firstFrame()
                while (frame < endFrame) {
                    val count = minOf(512L, if (frame < 0) -frame else endFrame - frame).toInt()
                    renderer.render(frame, block, 0, count)
                    if (frame >= 0) block.copyInto(output, frame.toInt() * 2, endIndex = count * 2)
                    frame += count
                }
                assertEquals(0, renderer.exitGateViolations())
                assertTrue("Finite actual JNI output required", output.all { it.isFinite() })
                return output
            } finally {
                built.close()
            }
        }

        fun assertGeneratedContractLanes() {
            val h = requireNotNull(script.handoff)
            assertEquals(h.inHoldMixSec, h.inFullLevelMixSec, 1e-9)
            fun only(deck: Int, kind: Int): InstalledEnvelope {
                val events = installedEnvelopes[deck to kind]
                assertNotNull("Missing generated renderer lane deck=$deck kind=$kind", events)
                assertEquals("Contract/script lane was installed more than once deck=$deck kind=$kind",
                    1, requireNotNull(events).size)
                return requireNotNull(events).single()
            }
            fun event(deck: Int, kind: Int, time: Double, value: Float) {
                val envelope = only(deck, kind)
                val index = envelope.times.indices.minByOrNull { kotlin.math.abs(envelope.times[it] - time) }
                assertNotNull("No renderer lane knot at declared mix second $time", index)
                val i = requireNotNull(index)
                assertEquals("Renderer lane knot time", time, envelope.times[i], 1e-8)
                assertEquals("Renderer lane value at $time", value, envelope.values[i], 1e-6f)
            }
            fun contractHas(times: DoubleArray, time: Double, description: String) {
                assertTrue("Contract envelope lacks $description coordinate $time",
                    times.any { kotlin.math.abs(it - time) <= 1e-8 })
            }

            // These are the exact arrays installed into the real JNI renderer,
            // captured immediately after setEnvelope, not a reconstructed clock.
            val clock = LoopMix.Clock(script.plan)
            val contract = LoopMixSession.computeEchoOutEnvelopes(script.plan, clock,
                h.wetRetireMixSec, sampleRate = RATE)
            contractHas(contract.outGainTimes, h.outDryFallStart.mixSec, "OUT dry-fall")
            contractHas(contract.outGainTimes, h.outDryEnd.mixSec, "OUT dry-end")
            contractHas(contract.inGainTimes, h.inFullLevelMixSec, "IN full-level cue")
            contractHas(contract.echoSendTimes, h.outCaptureStart.mixSec, "Echo capture")
            contractHas(contract.echoSendTimes, h.outSendClose.mixSec, "Echo send close")
            event(LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN, 0.0, 1f)
            event(LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN, h.outDryFallStart.mixSec, 1f)
            event(LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN, h.outDryEnd.mixSec, 0f)
            event(LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN, h.inHoldMixSec, 1f)
            event(LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN, h.inLanding.mixSec, 1f)
            event(LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND, h.outCaptureStart.mixSec, 0f)
            event(LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND, h.outSendClose.mixSec, 0f)
            event(LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ, h.inLanding.mixSec, 20f)
            event(LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB, h.inLanding.mixSec, 0f)
            event(LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB, h.inLanding.mixSec, 0f)
            val outGain = only(LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN)
            assertEquals(h.wetRetireMixSec, outGain.times.last(), 1e-8)
            assertTrue("Contract retirement must not exceed the reserved clock",
                h.wetRetireMixSec <= LoopMix.Clock(script.plan).releaseAt + 1e-8)
            val knots = LoopMixSession.buildDeckKnots(script.plan, clock, RATE,
                outAnalysis = null, inAnalysis = null, outHasTailKnot = false,
                outStopAtSec = h.wetRetireMixSec, tailRateEarly = 1.0)
            assertEquals("OUT's explicit drain knot must coincide with stopAt",
                h.wetRetireMixSec, knots.outFrames.last() / RATE, 1e-8)
            val geometry = LoopMixSession.incomingGeometry(script.plan, clock, knots)
            assertEquals("Contract release source must use the native hold mapping",
                h.inCueSourceSec + maxOf(0.0, clock.releaseAt - h.inHoldMixSec),
                geometry.releaseTrack, 1e-8)
            val oldNominalOrigin = (script.plan.rampBars + script.plan.exitBars).coerceAtLeast(1) *
                4.0 * script.plan.out.beatSec
            assertTrue("Tracked landing must differ from the old bar-count origin",
                kotlin.math.abs(oldNominalOrigin - h.inLanding.mixSec) > 0.05)
        }
    }

    companion object {
        private const val RATE = 48_000

        private fun rms(pcm: FloatArray, from: Double, to: Double): Double {
            val start = (from * RATE).roundToInt() * 2
            val end = (to * RATE).roundToInt() * 2
            var energy = 0.0
            for (i in start until end) energy += pcm[i].toDouble() * pcm[i]
            return sqrt(energy / (end - start))
        }

        private fun leadingSilenceFrames(pcm: FloatArray, from: Double, windowSec: Double, threshold: Float): Int {
            val start = floor(from * RATE).toInt().coerceAtLeast(0)
            val end = minOf(pcm.size / 2, start + (windowSec * RATE).roundToInt())
            for (frame in start until end) {
                val left = pcm[frame * 2]
                val right = pcm[frame * 2 + 1]
                if (!left.isFinite() || !right.isFinite()) return end - start + 1
                if (abs(left) >= threshold || abs(right) >= threshold) {
                    return frame - start
                }
            }
            return end - start
        }

        private fun song(id: String, bpm: Double, vocalFrom: Int?, drift: Double = 0.0): SongMap {
            fun section(from: Int, to: Int, fn: SectionFn, vocal: Float) = PhraseSection(
                from, to, fn, if (fn == SectionFn.VERSE) SectionRole.NARRATIVE else SectionRole.GROOVE,
                0.9f, 0, 0, 0.5f, 5, 0f, 0f, vocal, 0.5f, false)
            val sections = if (vocalFrom == null) listOf(section(0, 24, SectionFn.OUTRO, 0f)) else buildList {
                if (vocalFrom > 0) add(section(0, vocalFrom, SectionFn.INTRO, 0f))
                add(section(vocalFrom, 24, SectionFn.VERSE, 0.9f))
            }
            val step = 240.0 / bpm + drift
            return SongMap(trackKey = id, durationSec = 24 * step, bpm = bpm, beatsPerBar = 4,
                downbeats = DoubleArray(25) { it * step }, gridConfidence = 0.9f, tempoStable = true,
                phraseAnchor = 0, phraseLen = 8, sections = sections,
                sungBars = BooleanArray(24) { vocalFrom != null && it >= vocalFrom },
                vocalEnergy = FloatArray(24) { if (vocalFrom != null && it >= vocalFrom) 0.9f else 0f },
                firstVocalBar = vocalFrom, lastVocalBar = if (vocalFrom != null) 23 else null,
                pickupBeats = 0f, startKind = if (vocalFrom == 0) StartKind.COLD_VOCAL else StartKind.INTRO_INSTRUMENTAL,
                endingKind = EndingKind.HARD_END, contentEndSec = 24 * step, labelConfidence = 0.9f, structMs = 1)
        }

        private fun analysis(map: SongMap) = TrackAnalysis(duration = map.durationSec, bpm = map.bpm,
            beatInterval = 60.0 / map.bpm, vocalWindows = listOf(
                CurveWindow(0.0, 20.0, FloatArray((map.durationSec * 20).toInt() + 2) { frame ->
                    map.firstVocalBar?.let { bar -> if (frame / 20.0 >= map.barTime(bar)) 0.9f else 0.02f } ?: 0.02f
                }))).withMeasuredEchoTestEvidence(map)
    }
}
