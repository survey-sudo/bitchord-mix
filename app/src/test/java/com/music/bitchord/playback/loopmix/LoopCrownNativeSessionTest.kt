package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.smart.LoopMix
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Isolated host-JNI proof only. The ordinary app suite must not gain native
 * availability: run this exact class separately with hostNativeRequired=true
 * and the unchanged host-built libbitchord_loopmix.so on java.library.path.
 * No fake renderer or reflection-created session is accepted here. This does
 * not exercise the Android controller/delegate sink or establish listening quality.
 */
class LoopCrownNativeSessionTest {
    @Before fun requireRealHostJni() {
        Assume.assumeTrue("Dedicated host-JNI execution only",
            System.getProperty("bitchord.loopmix.hostNativeRequired") == "true")
        // With required=true, absent/incompatible JNI is a FAILURE, never a skip.
        assertTrue("Required libbitchord_loopmix.so did not load", LoopMixRenderer.available)
    }

    private fun build(p: LoopMix.Plan): LoopMixSession {
        val refusals = mutableListOf<String>()
        val decoded = AtomicInteger()
        val session = LoopMixSession.build(p, "generated-out", "generated-in",
            LoopCrownFixtures.SAMPLE_RATE, 1f, highQuality = false,
            onRefusal = { refusals += it },
        ) { side, from, to ->
            decoded.incrementAndGet()
            assertTrue(from.isFinite() && to.isFinite() && from >= 0.0 && to > from)
            // Generated, permitted stereo PCM: loop and resumed body have
            // different tones, so hearing only the repeated loop cannot pass.
            val count = ceil((to - from) * LoopCrownFixtures.SAMPLE_RATE).toInt() + 2
            assertTrue("Bounded fixture decode", count in 1..(LoopCrownFixtures.SAMPLE_RATE * 120))
            val loopEnd = if (side == LoopMix.Side.IN) p.inn.loopEnd else p.out.loopEnd
            val left = FloatArray(count)
            val right = FloatArray(count)
            for (i in 0 until count) {
                val t = from + i.toDouble() / LoopCrownFixtures.SAMPLE_RATE
                val resumed = side == LoopMix.Side.IN && t >= loopEnd
                val hzL = if (resumed) 880.0 else if (side == LoopMix.Side.IN) 220.0 else 330.0
                val hzR = if (resumed) 660.0 else if (side == LoopMix.Side.IN) 275.0 else 440.0
                left[i] = (.12 * sin(2 * PI * hzL * t)).toFloat()
                right[i] = (.10 * sin(2 * PI * hzR * t + .3)).toFloat()
            }
            LoopRegion(left, right, from)
        }
        assertNotNull("Required real session build refused: $refusals", session)
        assertTrue("Both physical regions decoded", decoded.get() >= 2)
        val built = requireNotNull(session)
        assertTrue("Actual native handle required", built.renderer.isValid)
        assertTrue(refusals.isEmpty())
        assertEquals(p, built.plan)
        return built
    }

    @Test(timeout = 120_000) fun actualCrownProcessesStereoWrapsReleasesAndResumesAtNativeRate() {
        for (p in listOf(
            LoopCrownFixtures.plan(),
            LoopCrownFixtures.plan().copy(incomingHoldSec = 8.0),
            LoopCrownFixtures.plan(120.0, 134.0),
            LoopCrownFixtures.plan(111.0, 169.0, 2.0),
            LoopCrownFixtures.plan(170.0, 80.0, .5),
        )) {
            assertTrue(LoopMix.qualifyCrown(p).accepted)
            val session = build(p)
            try {
                assertEquals(true, session.configuredOutLoops)
                assertEquals(true, session.configuredInLoops)
                val c = session.clock
                val sr = LoopCrownFixtures.SAMPLE_RATE
                val k = LoopCrownFixtures.knots(p, c)
                assertTrue(LoopCrownFixtures.qualify(p, k, c).accepted)
                val g = LoopMixSession.incomingGeometry(p, c, k)
                assertEquals(g.handoverTrack, session.handoverTrack, 1e-9)
                val block = FloatArray(512 * 2)
                var frame = session.renderer.firstFrame()
                assertTrue("Prepared preroll must precede takeover", frame < 0)
                var energy = 0.0
                var resumedEnergy = 0.0
                var stereoDifference = 0.0
                var resumedCrossings = 0
                var previousResumedLeft: Float? = null
                val resumeFrom = ((c.releaseAt + .75) * sr).roundToLong()
                val resumeTo = ((c.releaseAt + 1.75) * sr).roundToLong()
                fun renderTo(target: Long) {
                    assertTrue("Sequential bounded rendering", target >= frame)
                    while (frame < target) {
                        val count = min(512L, target - frame).toInt()
                        block.fill(Float.NaN)
                        session.renderer.render(frame, block, 0, count)
                        for (i in 0 until count) {
                            val l = block[2 * i]
                            val r = block[2 * i + 1]
                            assertTrue("Finite processed stereo PCM", l.isFinite() && r.isFinite())
                            assertTrue("Valid processed peak", abs(l) <= 1.0f && abs(r) <= 1.0f)
                            energy += l.toDouble() * l + r.toDouble() * r
                            stereoDifference += abs(l - r)
                            if (frame + i in resumeFrom until resumeTo) {
                                resumedEnergy += l.toDouble() * l + r.toDouble() * r
                                if (previousResumedLeft?.let { it <= 0f && l > 0f } == true) resumedCrossings++
                                previousResumedLeft = l
                            }
                        }
                        frame += count
                    }
                }
                // The first repeated OUT boundary must map physically back
                // into the body, not park at EOF or continue unwrapped.
                val wrapFrame = (c.beatTimes[p.out.beats] * sr).roundToLong() + 4800
                renderTo(wrapFrame)
                val wrapSec = wrapFrame.toDouble() / sr
                val (beat, frac) = c.beatAt(wrapSec)
                val unwrappedOut = p.out.loopStart + (beat + frac) * p.out.beatSec
                val expectedOut = LoopMix.physicalTrackTime(unwrappedOut, p.out.loopStart, p.out.loopEnd, 1e18)
                assertEquals(expectedOut, session.renderer.heard(LoopMixRenderer.OUT), 4.0 / sr)
                // Exact release and post-release physical coordinates use the
                // emitted IN knots, then advance at native 1x through settle.
                val releaseFrame = (c.releaseAt * sr).roundToLong()
                renderTo(releaseFrame)
                val releasePhysical = LoopMix.physicalTrackTime(g.releaseTrack,
                    p.inn.loopStart, p.inn.loopEnd, g.releaseTrack)
                // A rounded frame can fall a fraction of one sample BEFORE
                // release; use a post-release checkpoint to avoid the wrap jump.
                assertTrue(session.renderer.heard(LoopMixRenderer.IN).isFinite())
                renderTo(resumeFrom)
                val heardFrom = session.renderer.heard(LoopMixRenderer.IN)
                assertEquals(releasePhysical + (resumeFrom.toDouble() / sr - c.releaseAt), heardFrom, 4.0 / sr)
                renderTo(resumeTo)
                val heardTo = session.renderer.heard(LoopMixRenderer.IN)
                assertEquals((resumeTo - resumeFrom).toDouble() / sr, heardTo - heardFrom, 4.0 / sr)
                assertTrue("Nonzero native processed mix", energy > 1.0)
                assertTrue("Genuine stereo output", stereoDifference > 1.0)
                assertTrue("IN-only physical resume is audible after OUT stop", resumedEnergy > 1.0)
                assertTrue("Resumed 880 Hz body, not repeated 220 Hz loop: $resumedCrossings",
                    resumedCrossings in 800..960)
                renderTo((c.elementAt * sr).roundToLong())
                assertEquals(session.handoverTrack, session.renderer.heard(LoopMixRenderer.IN), 4.0 / sr)
                assertEquals(0, session.renderer.exitGateViolations())
                // Past the existing 25 ms fade and retirement block: no loop
                // or stored output may remain. No renderer setting is changed.
                renderTo(((c.elementAt + .15) * sr).roundToLong())
                block.fill(Float.NaN)
                session.renderer.render(frame, block, 0, 512)
                assertTrue("Finite retired output", block.all { it.isFinite() })
                assertTrue("Bounded fade retires both decks", block.all { abs(it) < 1e-6f })
            } finally {
                session.close()
                session.close()
                assertFalse("Idempotent close releases actual native handle", session.renderer.isValid)
            }
        }
    }

    @Test(timeout = 60_000) fun configuredFlagsDescribeActualRendererNotRequestedIncomingLoop() {
        // A native incoming deck addresses the straight source even when the
        // requested plan.inLoops is true. This is not a two-sided crown.
        val p = LoopCrownFixtures.plan().copy(outLoops = false, nativeIncoming = true, rung = "Beat blend")
        val session = build(p)
        try {
            assertTrue(p.inLoops)
            assertEquals(false, session.configuredOutLoops)
            assertEquals(false, session.configuredInLoops)
            val block = FloatArray(512 * 2)
            var frame = session.renderer.firstFrame()
            var energy = 0.0
            val end = (2.0 * LoopCrownFixtures.SAMPLE_RATE).roundToLong()
            while (frame < end) {
                val count = min(512L, end - frame).toInt()
                session.renderer.render(frame, block, 0, count)
                for (i in 0 until 2 * count) {
                    assertTrue(block[i].isFinite())
                    energy += block[i].toDouble() * block[i]
                }
                frame += count
            }
            assertTrue(energy > 1.0)
            assertEquals(p.inn.loopStart + 2.0, session.renderer.heard(LoopMixRenderer.IN), 4.0 / LoopCrownFixtures.SAMPLE_RATE)
        } finally {
            session.close()
            session.close()
            assertFalse(session.renderer.isValid)
        }
    }

    @Test(timeout = 10_000) fun availableJniStillRefusesUnsafeActualKnotsBeforeAnyDecode() {
        assertTrue(LoopMixRenderer.available)
        val p = LoopCrownFixtures.plan(120.0, 144.0)
        val refusals = mutableListOf<String>()
        val decodeCalls = AtomicInteger()
        val session = LoopMixSession.build(p, "out", "in", LoopCrownFixtures.SAMPLE_RATE, 1f,
            outAnalysis = LoopCrownFixtures.irregularOut(p), onRefusal = { refusals += it },
        ) { _, _, _ -> decodeCalls.incrementAndGet(); error("Unsafe knots reached decode") }
        assertNull(session)
        assertEquals(0, decodeCalls.get())
        assertEquals(listOf("crown_actual_out_rate"), refusals)
    }
}
