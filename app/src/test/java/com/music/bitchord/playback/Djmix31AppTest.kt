package com.music.bitchord.playback

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * vc73 (djmix31): clean classic rides. Bass swaps are pure <=200 Hz, vocal
 * separation lives in the mid dip (1500 Hz peaking EQ), never in an underwater
 * low-pass / tinny high-pass. The filter sweep keeps its outgoing LP wide
 * (>=2800 Hz) and its incoming HP at the bass edge (<=220 Hz).
 */
class Djmix31AppTest {

    private class FakeFilters : TransitionFilters {
        var incomingLow = arrayListOf<Float>()
        var incomingHigh = arrayListOf<Float>()
        var outgoingLow = arrayListOf<Float>()
        var outgoingHigh = arrayListOf<Float>()
        var incomingMid = arrayListOf<Float>()
        var outgoingMid = arrayListOf<Float>()
        var lastIncoming: Pair<Float, Float>? = null
        var lastOutgoing: Pair<Float, Float>? = null
        var lastIncomingMid: Float? = null
        var lastOutgoingMid: Float? = null
        override fun incoming(lowPassHz: Float, highPassHz: Float) {
            incomingLow.add(lowPassHz)
            incomingHigh.add(highPassHz)
            lastIncoming = lowPassHz to highPassHz
        }
        override fun outgoing(lowPassHz: Float, highPassHz: Float) {
            outgoingLow.add(lowPassHz)
            outgoingHigh.add(highPassHz)
            lastOutgoing = lowPassHz to highPassHz
        }
        override fun incomingMidDb(gainDb: Float) {
            incomingMid.add(gainDb)
            lastIncomingMid = gainDb
        }
        override fun outgoingMidDb(gainDb: Float) {
            outgoingMid.add(gainDb)
            lastOutgoingMid = gainDb
        }
        override fun open() {
            incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
            outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
            incomingMidDb(0f)
            outgoingMidDb(0f)
        }
    }

    private fun newController(fakeFilters: FakeFilters): CrossfadeController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return CrossfadeController(
            scope = scope,
            active = { throw IllegalStateException("no active in test") },
            standby = { throw IllegalStateException("no standby in test") },
            onHandoff = { _, _ -> },
            filters = fakeFilters,
        )
    }

    private fun field(c: CrossfadeController, name: String): java.lang.reflect.Field {
        val f = CrossfadeController::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f
    }

    private fun renderFor(c: CrossfadeController, plan: TransitionPlan, advanced: Boolean): Any {
        val m = CrossfadeController::class.java.getDeclaredMethod("renderFor", TransitionPlan::class.java, Boolean::class.javaPrimitiveType)
        m.isAccessible = true
        return m.invoke(c, plan, advanced)!!
    }

    private fun invokeRide(c: CrossfadeController, name: String, progress: Float) {
        val m = CrossfadeController::class.java.getDeclaredMethod(name, Float::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(c, progress)
    }

    private fun planFor(style: TransitionStyle, variant: FilterTransitionVariant, bassSwap: Boolean): TransitionPlan =
        TransitionPlan(
            transitionStyle = style,
            filterVariant = variant,
            bassSwap = bassSwap,
            bassSwapFraction = 0.5,
            filterSweep = 1.0,
            vocalOverlap = 0.8,
            beatSeconds = 0.5,
            fadeSeconds = 4.0,
        )

    /** Rides that must never LP below 3500 / HP above 220, with a mid dip when vocals collide. */
    private fun assertCleanBassSwap(fake: FakeFilters, rideName: String, c: CrossfadeController, dipScalesWithProgress: Boolean = false) {
        for (p in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
            invokeRide(c, rideName, p)
            val inc = fake.lastIncoming ?: throw AssertionError("missing incoming at $p")
            val out = fake.lastOutgoing ?: throw AssertionError("missing outgoing at $p")
            // handedOff=true: incoming->filters.incoming, outgoing->filters.outgoing.
            assertTrue("$rideName p=$p outgoing LP must stay >=3500, got ${out.first}", out.first >= 3500f)
            assertTrue("$rideName p=$p outgoing HP must stay <=220, got ${out.second}", out.second <= 220f)
            assertTrue("$rideName p=$p incoming LP must stay >=3500, got ${inc.first}", inc.first >= 3500f)
            assertTrue("$rideName p=$p incoming HP must stay <=220, got ${inc.second}", inc.second <= 220f)
            val dip = fake.lastOutgoingMid ?: throw AssertionError("missing mid at $p")
            if (dipScalesWithProgress && p == 0.0f) {
                assertTrue("$rideName p=0 dip must be 0 by design (scales with p), got $dip", abs(dip) < 1e-6f)
            } else {
                assertTrue("$rideName p=$p must dip outgoing mid when vocalOverlap>0, got $dip", dip < -1.0f)
            }
        }
    }

    @Test
    fun bassSwapNeverMufflesOrDemuffles() {
        val fake = FakeFilters()
        val c = newController(fake)
        field(c, "render").set(c, renderFor(c, planFor(TransitionStyle.DJ_BLEND, FilterTransitionVariant.SWEEP, true), true))
        field(c, "handedOff").set(c, true)
        assertCleanBassSwap(fake, "rideBassSwap", c)
    }

    @Test
    fun vocalSeparationNeverMufflesOrDemuffles() {
        val fake = FakeFilters()
        val c = newController(fake)
        field(c, "render").set(c, renderFor(c, planFor(TransitionStyle.DJ_BLEND, FilterTransitionVariant.SWEEP, false), true))
        field(c, "handedOff").set(c, true)
        assertCleanBassSwap(fake, "rideVocalSeparation", c)
    }

    @Test
    fun fallbackBassHandoffNeverMufflesOrDemuffles() {
        val fake = FakeFilters()
        val c = newController(fake)
        field(c, "render").set(c, renderFor(c, planFor(TransitionStyle.DJ_FILTER, FilterTransitionVariant.BASS_HANDOFF, false), true))
        field(c, "handedOff").set(c, true)
        assertCleanBassSwap(fake, "rideFallbackBassHandoff", c, dipScalesWithProgress = true)
    }

    @Test
    fun filterSweepKeepsIncomingCleanAndOutgoingWide() {
        val fake = FakeFilters()
        val c = newController(fake)
        field(c, "render").set(c, renderFor(c, planFor(TransitionStyle.DJ_FILTER, FilterTransitionVariant.SWEEP, false), true))
        field(c, "handedOff").set(c, true)
        for (p in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
            invokeRide(c, "rideFilterSweep", p)
            val inc = fake.lastIncoming ?: throw AssertionError("missing incoming at $p")
            val out = fake.lastOutgoing ?: throw AssertionError("missing outgoing at $p")
            assertTrue("sweep p=$p incoming HP must be <=220 (no 1200/2400 tinny), got ${inc.second}", inc.second <= 220f)
            assertTrue("sweep p=$p outgoing LP must be >=2800 (no 1500 mud), got ${out.first}", out.first >= 2800f)
        }
        // Midpoint matches the Vc70 window (9500->2800 at 0.5^1.1 ~= 5379).
        invokeRide(c, "rideFilterSweep", 0.5f)
        val outLp = fake.lastOutgoing?.first ?: -1f
        assertTrue("sweep midpoint LP stays in 3800..5500, got $outLp", outLp in 3800f..5500f)
    }

    @Test
    fun midEqProcessesStereoFloatCleanly() {
        val proc = TransitionFilterProcessor()
        proc.configure(44100, 2)
        proc.setCutoffs(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        proc.setMidGainDb(-6f)
        val frames = 1024
        val block = AudioBlock(channelCount = 2, capacityFrames = frames)
        for (f in 0 until frames) {
            val s = (0.5 * sin(2.0 * PI * 1500.0 * f / 44100)).toFloat()
            block.samples[f * 2] = s
            block.samples[f * 2 + 1] = s
        }
        block.reset(frames)
        proc.process(block)
        for (i in block.samples.indices) {
            val v = block.samples[i]
            assertTrue("float sample $i must be finite, got $v", v.isFinite())
            assertTrue("float sample $i must not clip, got $v", abs(v) <= 1.5f)
        }
    }

    @Test
    fun midEqProcessesPcmCleanly() {
        val proc = TransitionFilterProcessor()
        proc.configure(44100, 2)
        proc.setCutoffs(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        proc.setMidGainDb(-6f)
        val frames = 1024
        val input = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val s = (0.5 * sin(2.0 * PI * 1500.0 * f / 44100) * 32767.0).toInt().toShort()
            input[f * 2] = s
            input[f * 2 + 1] = s
        }
        val buf = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
        for (v in input) buf.putShort(v)
        buf.flip()
        proc.queueInput(buf)
        val out = proc.output
        assertTrue("pcm output must have data", out.remaining() > 0)
        val result = ShortArray(out.remaining() / 2)
        out.order(ByteOrder.nativeOrder()).asShortBuffer().get(result)
        assertEquals("pcm sample count", input.size, result.size)
        var inPeak = 0
        var outPeak = 0
        for (v in input) { val a = abs(v.toInt()); if (a > inPeak) inPeak = a }
        for (v in result) { val a = abs(v.toInt()); if (a > outPeak) outPeak = a }
        assertTrue("pcm outPeak must be positive, got $outPeak", outPeak > 0)
        assertTrue("pcm dip must reduce peak $outPeak < $inPeak", outPeak < inPeak)
        assertTrue("pcm outPeak must not clip at full scale", outPeak <= 32767)
    }

    @Test
    fun openResetsMidDip() {
        val proc = TransitionFilterProcessor()
        proc.configure(44100, 2)
        proc.setMidGainDb(-6f)
        val f = TransitionFilterProcessor::class.java.getDeclaredField("targetMidGainDb")
        f.isAccessible = true
        assertEquals(-6f, f.getFloat(proc), 1e-6f)
        assertTrue("isFiltering must be true with a dip", proc.isFiltering)
        proc.open()
        assertEquals(0f, f.getFloat(proc), 1e-6f)
        // setMidGainDb coerces to -9..0.
        proc.setMidGainDb(-20f)
        assertEquals(-9f, f.getFloat(proc), 1e-6f)
        proc.setMidGainDb(5f)
        assertEquals(0f, f.getFloat(proc), 1e-6f)
    }

    @Test
    fun windowedStemsOnByDefault() {
        assertTrue(AppSettings.automixWindowedStemsEnabled.value)
    }
}
