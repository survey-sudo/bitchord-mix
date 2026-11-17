package com.music.bitchord.playback

import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc71: the bounded-decline carve (FilterTransitionVariant.DECLINE).
 * Rendered behaviour only: fake filters record the corners the ride asks
 * for, fake faders hold the gains; no plan-shape asserts.
 *
 * The offline gain candidate requires complementary faders with positive
 * overlap through the handoff. Existing filter curves and the low-end
 * handoff stay in place; these assertions make no listening-quality claim.
 * handedOff=false swaps the sinks (tracks.outgoing -> filters.incoming),
 * following the Vc70ExitLegAppTest harness.
 */
class Vc70DeclineRideAppTest {

    private class FakeFilters : TransitionFilters {
        var incomingLow = arrayListOf<Float>()
        var incomingHigh = arrayListOf<Float>()
        var outgoingLow = arrayListOf<Float>()
        var outgoingHigh = arrayListOf<Float>()
        var lastIncoming: Pair<Float, Float>? = null
        var lastOutgoing: Pair<Float, Float>? = null
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
        fun reset() {
            incomingLow.clear()
            incomingHigh.clear()
            outgoingLow.clear()
            outgoingHigh.clear()
            lastIncoming = null
            lastOutgoing = null
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

    private fun invokeGain(c: CrossfadeController, name: String, progress: Float): Float {
        val m = CrossfadeController::class.java.getDeclaredMethod(name, Float::class.javaPrimitiveType)
        m.isAccessible = true
        return m.invoke(c, progress) as Float
    }

    private fun declinePlan(): TransitionPlan = TransitionPlan(
        transitionStyle = TransitionStyle.DJ_FILTER,
        filterVariant = FilterTransitionVariant.DECLINE,
        filterSweep = 1.0,
        bassSwap = true,
        bassSwapFraction = 0.5,
        vocalOverlap = 0.85,
        beatSeconds = 60.0 / 164.0,
        fadeSeconds = 4.0,
    )

    @Test
    fun declineRideMufflesOutgoingAndHoldsIncomingAtMidpoint() {
        val fake = FakeFilters()
        val c = newController(fake)
        field(c, "render").set(c, renderFor(c, declinePlan(), true))
        field(c, "handedOff").set(c, false)
        invokeRide(c, "rideDecline", 0.5f)
        // handedOff=false: tracks.outgoing -> filters.incoming (first).
        val outLp = fake.lastIncoming?.first ?: -1f
        val inHp = fake.lastOutgoing?.second ?: -1f
        assertTrue("midpoint outgoing LP must be muffled below 1000, got $outLp", outLp < 1000f)
        assertTrue("midpoint incoming HP must hold the body out above 300, got $inHp", inHp > 300f)
    }

    @Test
    fun declineRideHasComplementaryGainOverlapAndOneBassHandoff() {
        val fake = FakeFilters()
        val c = newController(fake)
        field(c, "render").set(c, renderFor(c, declinePlan(), true))
        field(c, "handedOff").set(c, false)
        var overlapSamples = 0
        var bassHandoffs = 0
        var outgoingBassCut = false
        for (i in 0..100) {
            val p = i / 100f
            invokeRide(c, "rideStyle", p)
            val outHp = fake.lastIncoming?.second ?: -1f
            val inHp = fake.lastOutgoing?.second ?: -1f
            val outGain = invokeGain(c, "mixFall", p)
            val inGain = invokeGain(c, "mixRise", p)
            assertEquals("equal-power faders at p=$p", 1f, outGain * outGain + inGain * inGain, 2e-6f)
            if (outGain >= 0.5f && inGain >= 0.5f) overlapSamples++
            // At least one deck retains the existing 200 Hz bass high-pass.
            assertTrue("low-end handoff at p=$p", outHp >= 199.99f || inHp >= 199.99f)
            val bassCut = outHp >= 199.99f
            if (bassCut != outgoingBassCut) {
                bassHandoffs++
                outgoingBassCut = bassCut
            }
            if (i == 50) {
                assertEquals("midpoint outgoing gain", 0.7071068f, outGain, 1e-6f)
                assertEquals("midpoint incoming gain", 0.7071068f, inGain, 1e-6f)
            }
        }
        assertTrue("both gains >= 0.5 over at least 30% of the sampled ride", overlapSamples >= 30)
        assertEquals("outgoing bass is cut once", 1, bassHandoffs)
    }

    @Test
    fun declineVariantDispatchesToCarveNotSweep() {
        val fake = FakeFilters()
        val c = newController(fake)
        val sweepPlan = declinePlan().copy(filterVariant = FilterTransitionVariant.SWEEP, bassSwap = false)
        field(c, "render").set(c, renderFor(c, sweepPlan, true))
        field(c, "handedOff").set(c, false)
        invokeRide(c, "rideStyle", 0.5f)
        val sweepLp = fake.lastIncoming?.first ?: -1f
        assertTrue("sweep midpoint LP stays wide near 4500, got $sweepLp", sweepLp in 3800f..5500f)
        field(c, "render").set(c, renderFor(c, declinePlan(), true))
        field(c, "handedOff").set(c, false)
        fake.reset()
        invokeRide(c, "rideStyle", 0.5f)
        val carveLp = fake.lastIncoming?.first ?: -1f
        assertTrue("carve midpoint LP is muffled below 1000, got $carveLp", carveLp < 1000f)
        assertTrue("carve must be materially darker than the sweep", carveLp < sweepLp * 0.35f)
    }

    @Test
    fun declineRideEndsOpenOnIncomingAndParkedSilentOnOutgoing() {
        val fake = FakeFilters()
        val c = newController(fake)
        field(c, "render").set(c, renderFor(c, declinePlan(), true))
        field(c, "handedOff").set(c, false)
        invokeRide(c, "rideDecline", 1.0f)
        val outLp = fake.lastIncoming?.first ?: -1f
        val inHp = fake.lastOutgoing?.second ?: -1f
        val outGain = invokeGain(c, "mixFall", 1.0f)
        val inGain = invokeGain(c, "mixRise", 1.0f)
        assertTrue("outgoing parked at its floor, got $outLp", outLp in 350f..450f)
        assertEquals("incoming fully open at the end", TransitionFilterProcessor.OFF_HZ, inHp, 1e-3f)
        assertEquals("outgoing silent at the end", 0f, outGain, 1e-3f)
        assertEquals("incoming alone at the end", 1f, inGain, 1e-3f)
    }
}
