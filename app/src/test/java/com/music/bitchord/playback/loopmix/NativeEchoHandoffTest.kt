package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.withMeasuredEchoTestEvidence

import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.HandoffStatus
import com.music.bitchord.playback.smart.nativeTiming
import com.music.bitchord.playback.smart.qualifyMusicalHandoff
import com.music.bitchord.playback.smart.CurveWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

class NativeEchoHandoffTest {
    @Before
    fun requireRealHostJni() {
        assumeTrue("Dedicated host-JNI execution only",
            System.getProperty("bitchord.loopmix.hostNativeRequired") == "true")
        assertTrue("Required libbitchord_loopmix.so did not load", LoopMixRenderer.available)
    }

    @Test(timeout = 120_000)
    fun healthySourceHonorsProtectedDryFallAndRetiresWetAtStop() {
        val fixture = Fixture()
        val pcm = fixture.renderAll()
        val swap = fixture.fall
        val pre = rms(pcm.outDry, swap - 0.20, swap - 0.10)
        val earlyDry = rms(pcm.outDry, swap + 0.110, swap + 0.170)
        val earlyMix = rms(pcm.full, swap + 0.110, swap + 0.170)
        assertTrue("Fixture needs audible OUT before swap: $pre", pre > 0.03)
        assertTrue("OUT dry dropped too soon after swap: early=$earlyDry pre=$pre", earlyDry > 0.65 * pre)
        assertTrue("Combined mix dipped after swap: early=$earlyMix pre=$pre", earlyMix > 0.65 * pre)
        assertEntryAndTail(fixture, pcm)
    }

    @Test(timeout = 120_000)
    fun sourceEndingAfterProtectedCutMayDrainWithoutMovingTheIncoming() {
        val fixture = Fixture(short = true)
        val pcm = fixture.renderAll()
        assertTrue("Dry source must be silent after the contracted cut",
            rms(pcm.outDry, fixture.dryEnd + 0.01, fixture.dryEnd + 0.15) < 1e-6)
        assertEntryAndTail(fixture, pcm)
    }

    @Test(timeout = 120_000)
    fun sourceEndingBeforeProtectedCutIsRefusedInsteadOfResizingFade() {
        val fixture = Fixture()
        val refusals = mutableListOf<String>()
        val session = LoopMixSession.build(fixture.plan, "short-out", "in", RATE, 1f,
            highQuality = false, outAnalysis = fixture.outAnalysis, inAnalysis = fixture.inAnalysis,
            onRefusal = { refusals += it }) { side, from, to ->
            val end = if (side == LoopMix.Side.OUT) fixture.plan.handoff!!.outDryEnd.sourceSec - 0.10 else to
            val size = floor((end - from) * RATE).toInt()
            LoopRegion(FloatArray(size) { 0.01f }, FloatArray(size) { 0.01f }, from)
        }
        session?.close()
        assertNull(session)
        assertFalse("Budget must not mask the intended physical-PCM refusal: $refusals",
            "handoff_render_budget" in refusals)
        assertTrue("Expected explicit protected-cut refusal: $refusals",
            "handoff_pcm_before_protected_event" in refusals)
    }

    @Test(timeout = 120_000)
    fun unknownNaturalEndPlaysEveryDrySampleWithoutUnqualifiedEffects() {
        val base = Fixture().plan
        val h = requireNotNull(nativeTiming(4.0, 4.9, 6.4, 6.4,
            1.5, 1.5, 1.5, 2.4, 0.0, 0.0))
        val plan = base.copy(handoff = h, incomingHoldSec = h.inHoldMixSec,
            rung = "Natural handoff", alternativeKind = "natural-end", echoTail = false, reverbWash = false)
        val refusals = mutableListOf<String>()
        val session = LoopMixSession.build(plan, "natural-out", "natural-in", RATE, 1f,
            highQuality = false, outAnalysis = TrackAnalysis(duration = 6.4),
            inAnalysis = TrackAnalysis(duration = 30.0), onRefusal = { refusals += it }) { side, from, to ->
            val end = if (side == LoopMix.Side.OUT) 6.4 else to
            val size = ((end - from) * RATE).roundToInt()
            val tone = FloatArray(size) { i -> (0.08 * sin(2 * PI * 510 * (from + i.toDouble() / RATE))).toFloat() }
            LoopRegion(tone, tone.copyOf(), from)
        }
        assertNotNull("Deliberate unknown natural route refused: $refusals", session)
        val built = requireNotNull(session)
        try {
            val renderer = built.renderer
            // Isolate OUT: no wet output may appear after physical EOF.
            renderer.setEnvelope(LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN, doubleArrayOf(0.0), floatArrayOf(0f))
            val output = FloatArray(((h.outDryEnd.mixSec + 0.10) * RATE).roundToInt() * 2)
            val block = FloatArray(512 * 2)
            var frame = renderer.firstFrame()
            val endFrame = output.size / 2
            while (frame < endFrame) {
                val count = minOf(512L, if (frame < 0) -frame else endFrame - frame).toInt()
                renderer.render(frame, block, 0, count)
                if (frame >= 0) block.copyInto(output, frame.toInt() * 2, endIndex = count * 2)
                frame += count
            }
            assertTrue("OUT must retain its final phrase", rms(output, 2.36, 2.39) > 0.04)
            assertTrue("No unmeasured Echo/reverb tail", rms(output, 2.41, 2.48) < 1e-6)
            assertEquals(0, renderer.exitGateViolations())
        } finally {
            built.close()
        }
    }

    @Test(timeout = 120_000)
    fun plannerNaturalEndReservesReleaseClockAndBuildsAtPhysicalEof() {
        val out = TrackAnalysis(status = TrackAnalysis.STATUS_READY, duration = 12.0,
            bpm = 120.0, beatInterval = 0.5, beatConfidence = 0.9,
            downbeats = List(7) { it * 2.0 }, contentEndTime = 12.0,
            key = "C major", keyConfidence = 0.9, vocalProbability = 0.9)
        val inn = TrackAnalysis(status = TrackAnalysis.STATUS_READY, duration = 30.0,
            bpm = 135.0, beatInterval = 60.0 / 135.0, beatConfidence = 0.9,
            downbeats = List(20) { it * 240.0 / 135.0 },
            key = "F# minor", keyConfidence = 0.9, vocalProbability = 0.9)
        val plan = LoopMix.bridge(out, out.duration, inn, inn.duration, earliestOutSec = 0.5)
        assertNotNull("Missing local masks should produce explicit natural fallback", plan)
        val selected = requireNotNull(plan)
        assertEquals("Natural handoff", selected.rung)
        assertFalse(selected.echoTail)
        assertFalse(selected.reverbWash)
        val handoff = requireNotNull(selected.handoff)
        val clock = LoopMix.Clock(selected)
        assertTrue("releaseAt=${clock.releaseAt} dryEnd=${handoff.outDryEnd.mixSec}",
            clock.releaseAt + 1e-9 >= handoff.outDryEnd.mixSec)
        assertTrue("releaseAt=${clock.releaseAt} landing=${handoff.inLanding.mixSec}",
            clock.releaseAt + 1e-9 >= handoff.inLanding.mixSec)
        assertTrue(clock.elementAt <= PhraseConfig.O_MAX_SEC + 1e-6)

        val refusals = mutableListOf<String>()
        val session = LoopMixSession.build(selected, "planner-natural-out", "planner-natural-in", RATE, 1f,
            highQuality = false, outAnalysis = out, inAnalysis = inn, onRefusal = { refusals += it }) { side, from, to ->
            val physicalEnd = if (side == LoopMix.Side.OUT) out.duration else inn.duration
            val end = minOf(to, physicalEnd)
            val count = floor((end - from) * RATE).toInt()
            assertTrue("Source-zero-clamped bounded request $from..$end", from >= 0.0 && count in 1..(RATE * 20))
            val left = FloatArray(count) { i ->
                (0.08 * sin(2 * PI * 440 * (from + i.toDouble() / RATE))).toFloat()
            }
            LoopRegion(left, left.copyOf(), from)
        }
        assertNotNull("Planner-selected natural handoff must build: $refusals", session)
        requireNotNull(session).close()
    }

    @Test(timeout = 120_000)
    fun plannerQualifiedEchoBuildsWithWetRetirementInsideReservedClock() {
        val out = TrackAnalysis(status = TrackAnalysis.STATUS_READY, duration = 100.0,
            bpm = 100.0, beatInterval = 0.6, beatConfidence = 0.9,
            downbeats = List(42) { it * 2.4 }, contentEndTime = 98.0,
            key = "C major", keyConfidence = 0.9, vocalProbability = 0.9,
            vocalWindows = listOf(CurveWindow(0.0, 20.0,
                FloatArray(2000) { if (it / 20.0 < 95.0) 0.8f else 0f }))).withMeasuredEchoTestEvidence()
        val inn = TrackAnalysis(status = TrackAnalysis.STATUS_READY, duration = 30.0,
            bpm = 135.0, beatInterval = 60.0 / 135.0, beatConfidence = 0.9,
            downbeats = List(56) { it * 240.0 / 135.0 }, audibleStartTime = 0.0,
            key = "F# minor", keyConfidence = 0.9, vocalProbability = 0.9,
            vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(600)))).withMeasuredEchoTestEvidence()
        val plan = LoopMix.bridge(out, out.duration, inn, inn.duration, earliestOutSec = 80.0,
            sungOut = emptyList(), sungIn = emptyList())
        assertNotNull("Qualified native Echo should be selected", plan)
        val selected = requireNotNull(plan)
        assertEquals("Echo", selected.rung)
        assertTrue(selected.echoTail)
        val handoff = requireNotNull(selected.handoff)
        assertEquals(HandoffStatus.QUALIFIED,
            qualifyMusicalHandoff(handoff, out, inn, emptyList(), emptyList()).status)
        val clock = LoopMix.Clock(selected)
        assertTrue(clock.releaseAt + 1e-9 >= handoff.outDryEnd.mixSec)
        assertTrue(clock.releaseAt + 1e-9 >= handoff.inLanding.mixSec)
        assertTrue("Wet retirement must be reserved: wet=${handoff.wetRetireMixSec} release=${clock.releaseAt}",
            clock.releaseAt + 1e-9 >= handoff.wetRetireMixSec)
        assertTrue(clock.elementAt <= PhraseConfig.O_MAX_SEC + 1e-6)

        val refusals = mutableListOf<String>()
        val session = LoopMixSession.build(selected, "qualified-echo-out", "qualified-echo-in", RATE, 1f,
            highQuality = false, outAnalysis = out, inAnalysis = inn, sungOut = emptyList(), sungIn = emptyList(),
            onRefusal = { refusals += it }) { side, from, to ->
            val duration = if (side == LoopMix.Side.OUT) out.duration else inn.duration
            val end = minOf(to, duration)
            val count = floor((end - from) * RATE).toInt()
            assertTrue("Physical bounded PCM request $from..$end", from >= 0.0 && count in 1..(RATE * 30))
            val left = FloatArray(count) { i ->
                (0.08 * sin(2 * PI * 440 * (from + i.toDouble() / RATE))).toFloat()
            }
            LoopRegion(left, left.copyOf(), from)
        }
        assertNotNull("Planner Echo refused during host renderer build: $refusals", session)
        requireNotNull(session).close()
    }

    private fun assertEntryAndTail(fixture: Fixture, pcm: Rendered) {
        val swap = fixture.incomingStart
        val openEnd = swap
        assertTrue("IN must be silent before swap", rms(pcm.inOnly, swap - 0.20, swap) < 1e-6)
        assertTrue("IN must open after swap", rms(pcm.inOnly, swap + 0.55, swap + 0.70) > 0.02)
        assertTrue("Cold attack must already be audible in its first 15 ms",
            rms(pcm.inOnly, swap + 0.001, swap + 0.016) > 0.02)
        assertTrue("OUT dry must stay at zero after contracted dry end",
            rms(pcm.outDry, fixture.dryEnd + 0.01, fixture.dryEnd + 0.30) < 1e-6)

        val stop = fixture.stop
        val tail = rms(pcm.wet, openEnd + 0.05, openEnd + 0.70)
        val lateTail = rms(pcm.wet, stop - 0.20, stop)
        assertTrue("Wet tail must ring past dry handoff: $tail", tail > 0.001)
        assertTrue("Wet tail failed to decay: late=$lateTail tail=$tail", lateTail < 0.02 * tail)
        assertTrue("Step at OUT stop", peakStep(pcm.wet, stop - 0.005, stop + 0.005) < 0.01 * tail)
        assertTrue("Wet tail remains past OUT stop", rms(pcm.wet, stop + 0.01, stop + 0.10) < 1e-6)
    }

    private enum class Mix { FULL, DRY, IN_ONLY }

    private class Rendered(val full: FloatArray, dry: FloatArray, val inOnly: FloatArray) {
        val outDry = FloatArray(dry.size) { dry[it] - inOnly[it] }
        val wet = FloatArray(full.size) { full[it] - dry[it] }
    }

    private class Fixture(short: Boolean = false) {
        private val outBeat = 0.6
        private val exitTail = PhraseConfig.exitTailSeconds(outBeat)
        val handoff = requireNotNull(nativeTiming(
            4.0, 4.9, 5.95, 6.4, 1.5, 1.5, 1.5, 2.4, 0.0,
            2.4 + 0.05 + outBeat * 8 + exitTail))
        private val reservedBars = kotlin.math.ceil(maxOf(handoff.outDryEnd.mixSec,
            handoff.inLanding.mixSec, handoff.wetRetireMixSec) / (outBeat * 4.0) - 1e-9).toInt()
        val plan = LoopMix.Plan(
            out = LoopMix.Pick(4.0, 4.0, 4, 0.6, 100.0, 0.0, LoopMix.Check.MASK),
            inn = LoopMix.Pick(1.5, 1.5, 4, 60.0 / 135.0, 135.0, 0.0, LoopMix.Check.MASK),
            fold = 1.0, enterBars = 1, rampBars = 0,
            exitBars = reservedBars - 1, totalBars = reservedBars,
            outLoops = false, inLoops = false, nativeIncoming = true, echoTail = true,
            rung = "Echo", targetBpmOverride = 100.0, incomingHoldSec = handoff.inHoldMixSec,
            exitTailSeconds = exitTail, reverbWash = true, handoff = handoff,
        )
        private val clock = LoopMix.Clock(plan)
        init {
            assertTrue(clock.releaseAt + 1e-9 >= handoff.outDryEnd.mixSec)
            assertTrue(clock.releaseAt + 1e-9 >= handoff.inLanding.mixSec)
            assertTrue(clock.releaseAt + 1e-9 >= handoff.wetRetireMixSec)
        }
        val incomingStart = handoff.inAudibleStart.mixSec
        val dryEnd = handoff.outDryEnd.mixSec
        val fall = handoff.outDryFallStart.mixSec
        val stop = handoff.wetRetireMixSec
        private val trackedBeat = 1.04 * plan.out.beatSec
        val inAnalysis = TrackAnalysis(duration = 90.0, vocalProbability = 0.9,
            vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(600) { 0.8f })))
        val outAnalysis = inAnalysis.copy(vocalWindows = listOf(CurveWindow(0.0, 20.0,
            FloatArray(600) { if (it / 20.0 < 5.8) 0.8f else 0f })), tailBeats = if (short) BeatSide(
            List(clock.beats + 1) { plan.out.loopStart + it * trackedBeat },
            emptyList(), 60.0 / trackedBeat, 0.9,
        ) else null)
        // Real EOF is after the protected cut, while wet audio is still draining.
        private val outTrackEof = if (short) plan.handoff!!.outDryEnd.sourceSec + SHORT_AFTER_SWAP else null

        fun renderAll(): Rendered = Rendered(render(Mix.FULL), render(Mix.DRY), render(Mix.IN_ONLY))

        private fun render(mix: Mix): FloatArray {
            val refusals = mutableListOf<String>()
            var stemCalls = 0
            val session = LoopMixSession.build(plan, "generated-out", "generated-in", RATE, 1f,
                highQuality = false, outAnalysis = outAnalysis, inAnalysis = inAnalysis, onRefusal = { refusals += it },
                separateVocalStem = { _, left, right, _ -> stemCalls++; left to right },
            ) { side, from, to ->
                val out = side == LoopMix.Side.OUT
                val end = if (out && outTrackEof != null) minOf(to, outTrackEof) else to
                val count = floor((end - from) * RATE).toInt()
                assertTrue("Bounded and valid PCM request $from..$end", from >= 0.0 && count in 1..(RATE * 45))
                fun tone(hz: Double, amplitude: Double, phase: Double) = FloatArray(count) { i ->
                    (amplitude * sin(2 * PI * hz * (from + i.toDouble() / RATE) + phase)).toFloat()
                }
                if (out) LoopRegion(tone(510.0, 0.09, 0.5), tone(630.0, 0.08, 1.2), from)
                else LoopRegion(tone(253.0, 0.08, 0.4), tone(337.0, 0.07, 0.2), from)
            }
            assertNotNull("Required real Echo session refused: $refusals", session)
            val built = requireNotNull(session)
            assertEquals("No simultaneous contracted vocals: protected phrase must not be stem-ducked", 0, stemCalls)
            try {
                val renderer = built.renderer
                assertTrue("Actual native handle required", renderer.isValid)
                assertEquals("Echo", built.plan.rung)
                assertEquals(false, built.configuredOutLoops)
                assertEquals(false, built.configuredInLoops)
                assertEquals(GridSource.NATIVE_CLOCK, built.outGridSource)

                // Keep geometry, decoder samples and level matching identical across the renders.
                // Full is the production configuration. The references suppress selected OUT lanes.
                if (mix != Mix.FULL) {
                    renderer.setEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND, doubleArrayOf(0.0), floatArrayOf(0f))
                    renderer.setEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_REVERB_SEND, doubleArrayOf(0.0), floatArrayOf(0f))
                }
                if (mix == Mix.IN_ONLY) {
                    renderer.setEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN, doubleArrayOf(0.0), floatArrayOf(0f))
                }

                val endFrame = ((stop + 0.15) * RATE).roundToInt()
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
                assertTrue("Finite PCM below limiting levels for linear subtraction", output.all { it.isFinite() && abs(it) < 0.5f })
                return output
            } finally {
                built.close()
            }
        }
    }

    companion object {
        private const val RATE = 48_000
        private const val SHORT_AFTER_SWAP = 0.20

        private fun rms(pcm: FloatArray, from: Double, to: Double): Double {
            val start = (from * RATE).roundToInt() * 2
            val end = (to * RATE).roundToInt() * 2
            var energy = 0.0
            for (i in start until end) energy += pcm[i].toDouble() * pcm[i]
            return sqrt(energy / (end - start))
        }

        private fun peakStep(pcm: FloatArray, from: Double, to: Double): Double {
            val start = (from * RATE).roundToInt() * 2
            val end = (to * RATE).roundToInt() * 2
            var peak = 0.0
            for (i in start until end) peak = maxOf(peak, abs(pcm[i] - pcm[i - 2]).toDouble())
            return peak
        }
    }
}
