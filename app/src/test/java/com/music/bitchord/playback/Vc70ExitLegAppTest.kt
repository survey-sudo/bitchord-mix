package com.music.bitchord.playback

import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy

/**
 * vc70: Seam A (teardown never reopens while audible) and Seam B
 * (two-phase rides end at full-band). Rendered behavior only: fake
 * filters record cutoffs, fake players hold gains; no plan-shape asserts.
 */
class Vc70ExitLegAppTest {

    private class FakeFilters : TransitionFilters {
        var openCalls = 0
        var incomingLow = arrayListOf<Float>()
        var outgoingLow = arrayListOf<Float>()
        var lastIncoming: Pair<Float, Float>? = null
        var lastOutgoing: Pair<Float, Float>? = null
        override fun incoming(lowPassHz: Float, highPassHz: Float) {
            incomingLow.add(lowPassHz)
            lastIncoming = lowPassHz to highPassHz
        }
        override fun outgoing(lowPassHz: Float, highPassHz: Float) {
            outgoingLow.add(lowPassHz)
            lastOutgoing = lowPassHz to highPassHz
        }
        override fun open() {
            openCalls++
            // Mirror the default open() so parked state visibly clears.
            incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
            outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        }
        fun resetOpen() { openCalls = 0 }
    }

    private class PlayerState(var volume: Float = 1f, var playWhenReady: Boolean = false)

    private fun fakePlayer(state: PlayerState): ExoPlayer {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "getVolume" -> state.volume
                "setVolume" -> { state.volume = (args?.get(0) as? Float) ?: 0f; null }
                "getPlayWhenReady" -> state.playWhenReady
                "setPlayWhenReady" -> { state.playWhenReady = (args?.get(0) as? Boolean) ?: false; null }
                "getPlaybackState" -> 4 // STATE_READY
                "isPlaying" -> false
                "getCurrentPosition" -> 0L
                "getDuration" -> 200_000L
                "getCurrentMediaItem" -> null
                "getPlaybackParameters" -> null
                else -> {
                    val rt = method.returnType
                    when {
                        rt == java.lang.Boolean.TYPE -> false
                        rt == java.lang.Integer.TYPE -> 0
                        rt == java.lang.Long.TYPE -> 0L
                        rt == java.lang.Float.TYPE -> 0f
                        rt == java.lang.Double.TYPE -> 0.0
                        rt == java.lang.Short.TYPE -> 0.toShort()
                        rt == java.lang.Byte.TYPE -> 0.toByte()
                        rt == java.lang.Character.TYPE -> 0.toChar()
                        rt == Void.TYPE -> null
                        else -> null
                    }
                }
            }
        }
        return Proxy.newProxyInstance(
            javaClass.classLoader!!,
            arrayOf(ExoPlayer::class.java),
            handler,
        ) as ExoPlayer
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

    private fun renderFor(c: CrossfadeController, plan: TransitionPlan): Any {
        val m = CrossfadeController::class.java.getDeclaredMethod("renderFor", TransitionPlan::class.java, Boolean::class.javaPrimitiveType)
        m.isAccessible = true
        return m.invoke(c, plan, false)!!
    }

    private fun setPhase(c: CrossfadeController, phaseName: String) {
        val phaseClass = Class.forName("com.music.bitchord.playback.CrossfadeController\$Phase")
        val value = java.lang.Enum.valueOf(phaseClass as Class<out Enum<*>>, phaseName)
        field(c, "phase").set(c, value)
    }

    private fun invokePrivate(c: CrossfadeController, name: String, vararg args: Any?) {
        val m = CrossfadeController::class.java.getDeclaredMethod(name, *args.map { it!!::class.java }.toTypedArray())
        m.isAccessible = true
        m.invoke(c, *args)
    }

    private fun invokeRide(c: CrossfadeController, name: String, progress: Float) {
        val m = CrossfadeController::class.java.getDeclaredMethod(name, Float::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(c, progress)
    }

    @Test
    fun muffleRideAtFullProgressIsFullBand() {
        val fake = FakeFilters()
        val c = newController(fake)
        val plan = TransitionPlan(
            transitionStyle = TransitionStyle.MUFFLE,
            muffleLeadBeats = 8,
            exitBeats = 2,
            bassSwapFraction = 0.75,
            bassSwap = true,
            filterSweep = 1.0,
            beatSeconds = 0.5,
            fadeSeconds = 4.0,
        )
        field(c, "render").set(c, renderFor(c, plan))
        field(c, "handedOff").set(c, false)
        invokeRide(c, "rideMuffle", 0.75f)
        // At the swap the outgoing is at its floor (parked, not yet returned).
        val atSwapLow = fake.lastIncoming?.first ?: -1f
        assertTrue("muffle at 0.75 should be parked near 700, got $atSwapLow", atSwapLow in 600f..900f)
        // At the end the return leg has left to full-band.
        invokeRide(c, "rideMuffle", 1.0f)
        val atEndLow = fake.lastIncoming?.first ?: -1f
        assertEquals("muffle ride at 1.0 must be full-band", TransitionFilterProcessor.OPEN_HZ, atEndLow, 1e-3f)
    }

    @Test
    fun bridgeRideAtFullProgressIsFullBand() {
        val fake = FakeFilters()
        val c = newController(fake)
        val plan = TransitionPlan(
            transitionStyle = TransitionStyle.BEATLESS_BRIDGE,
            exitBeats = 4,
            vocalOverlap = 0.85,
            filterSweep = 0.25,
            beatSeconds = 0.5,
            fadeSeconds = 4.0,
        )
        field(c, "render").set(c, renderFor(c, plan))
        field(c, "handedOff").set(c, false)
        invokeRide(c, "rideBeatlessBridge", 0.75f)
        // Mid-return: strictly between park and full-band.
        val midOut = fake.lastIncoming?.first ?: -1f
        assertTrue("bridge mid-return should be leaving park, got $midOut", midOut > 1500f && midOut < TransitionFilterProcessor.OPEN_HZ)
        invokeRide(c, "rideBeatlessBridge", 1.0f)
        val endOut = fake.lastIncoming?.first ?: -1f
        val endIn = fake.lastOutgoing?.first ?: -1f
        assertEquals("bridge outgoing at 1.0 must be full-band", TransitionFilterProcessor.OPEN_HZ, endOut, 1e-3f)
        assertEquals("bridge incoming at 1.0 must be full-band", TransitionFilterProcessor.OPEN_HZ, endIn, 1e-3f)
    }

    @Test
    fun teardownDefersOpenUntilSilent_finishPath() {
        val fake = FakeFilters()
        val c = newController(fake)
        val outState = PlayerState(volume = 0.8f)
        val inState = PlayerState(volume = 0.2f)
        val out = fakePlayer(outState)
        val inn = fakePlayer(inState)
        field(c, "outgoing").set(c, out)
        field(c, "incoming").set(c, inn)
        field(c, "handedOff").set(c, false)
        setPhase(c, "IDLE")
        fake.resetOpen()
        val finish = CrossfadeController::class.java.getDeclaredMethod("finish")
        finish.isAccessible = true
        finish.invoke(c)
        assertEquals("finish must not reopen while audible", 0, fake.openCalls)
        // Silence both decks and re-enter teardown: now it may open.
        outState.volume = 0f
        inState.volume = 0f
        field(c, "outgoing").set(c, out)
        field(c, "incoming").set(c, inn)
        setPhase(c, "IDLE")
        fake.resetOpen()
        finish.invoke(c)
        assertEquals("finish must open once silent", 1, fake.openCalls)
    }

    @Test
    fun teardownDefersOpenUntilSilent_bailForcePath() {
        val fake = FakeFilters()
        val c = newController(fake)
        val outState = PlayerState(volume = 0.9f)
        val inState = PlayerState(volume = 0.4f)
        field(c, "outgoing").set(c, fakePlayer(outState))
        field(c, "incoming").set(c, fakePlayer(inState))
        field(c, "handedOff").set(c, false)
        setPhase(c, "FADING")
        fake.resetOpen()
        val bail = CrossfadeController::class.java.getDeclaredMethod("bail", Boolean::class.javaPrimitiveType)
        bail.isAccessible = true
        bail.invoke(c, true)
        assertEquals("force bail must not reopen while audible", 0, fake.openCalls)
        // BAILING dissolve drives the leaving gain to zero; once silent the
        // final finish() may open.
        outState.volume = 0f
        inState.volume = 0f
        field(c, "outgoing").set(c, fakePlayer(outState))
        field(c, "incoming").set(c, fakePlayer(inState))
        // Drive bail completion through finish() (BAILING bypasses the guard).
        setPhase(c, "BAILING")
        fake.resetOpen()
        val finish = CrossfadeController::class.java.getDeclaredMethod("finish")
        finish.isAccessible = true
        finish.invoke(c)
        assertEquals("finish after bail must open once silent", 1, fake.openCalls)
    }

    @Test
    fun parkedRenderTeardownKeepsFiltersParkedWhileAudible() {
        val fake = FakeFilters()
        val c = newController(fake)
        val plan = TransitionPlan(
            transitionStyle = TransitionStyle.MUFFLE,
            muffleLeadBeats = 8,
            exitBeats = 2,
            bassSwapFraction = 0.75,
            bassSwap = true,
            filterSweep = 1.0,
            beatSeconds = 0.5,
            fadeSeconds = 4.0,
        )
        field(c, "render").set(c, renderFor(c, plan))
        field(c, "handedOff").set(c, false)
        // Park the outgoing low-pass mid-entry (audible parked state).
        invokeRide(c, "rideMuffle", 0.5f)
        val parked = fake.lastIncoming?.first ?: -1f
        assertTrue("expected parked cutoff mid-entry, got $parked", parked < 5000f)
        // Teardown while audible must leave the parked cutoff alone.
        val outState = PlayerState(volume = 0.8f)
        val inState = PlayerState(volume = 0.2f)
        field(c, "outgoing").set(c, fakePlayer(outState))
        field(c, "incoming").set(c, fakePlayer(inState))
        setPhase(c, "IDLE")
        fake.resetOpen()
        val finish = CrossfadeController::class.java.getDeclaredMethod("finish")
        finish.isAccessible = true
        finish.invoke(c)
        assertEquals(0, fake.openCalls)
        assertEquals("parked cutoff must survive audible teardown", parked, fake.lastIncoming?.first ?: -1f, 1e-3f)
    }
}
