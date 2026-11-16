package com.music.bitchord.playback

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.DjEffect
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.effectSettings
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
        var lastIncomingMid: Float? = null
        var lastOutgoingMid: Float? = null
        var lastOutgoingColourEffect: DjEffect? = null
        var lastOutgoingColourAmount: Float? = null
        override fun incoming(lowPassHz: Float, highPassHz: Float) {
            incomingLow.add(lowPassHz)
            lastIncoming = lowPassHz to highPassHz
        }
        override fun outgoing(lowPassHz: Float, highPassHz: Float) {
            outgoingLow.add(lowPassHz)
            lastOutgoing = lowPassHz to highPassHz
        }
        override fun incomingMidDb(gainDb: Float) { lastIncomingMid = gainDb }
        override fun outgoingMidDb(gainDb: Float) { lastOutgoingMid = gainDb }
        override fun outgoingColour(effect: DjEffect, amount: Float, beatSeconds: Float, scoop: Float) {
            lastOutgoingColourEffect = effect
            lastOutgoingColourAmount = amount
        }
        override fun open() {
            openCalls++
            // Mirror the default open() so parked state visibly clears.
            incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
            outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        }
        fun resetOpen() { openCalls = 0 }
    }

    private class PlayerState(
        var volume: Float = 1f,
        var playWhenReady: Boolean = false,
        var position: Long = 0L,
        var durationMs: Long = 200_000L,
    )

    private fun fakePlayer(state: PlayerState): ExoPlayer {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "getVolume" -> state.volume
                "setVolume" -> { state.volume = (args?.get(0) as? Float) ?: 0f; null }
                "getPlayWhenReady" -> state.playWhenReady
                "setPlayWhenReady" -> { state.playWhenReady = (args?.get(0) as? Boolean) ?: false; null }
                "getPlaybackState" -> 4 // STATE_READY
                "isPlaying" -> false
                "getCurrentPosition" -> state.position
                "getDuration" -> state.durationMs
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

    @Test
    fun postHandoffFinishOpensSpareFilterWhileSurvivorStaysUntouched() {
        val fake = FakeFilters()
        val c = newController(fake)
        val outState = PlayerState(volume = 0f)
        val inState = PlayerState(volume = 1f)
        val out = fakePlayer(outState)
        val inn = fakePlayer(inState)
        // Survivor sentinel (must survive finish) and parked spare at the
        // SWEEP/DECLINE floor (must be opened even though the survivor at 1
        // blocks the gated open).
        fake.incoming(700f, 20f)
        fake.outgoing(2800f, 20f)
        fake.outgoingMidDb(-6f)
        field(c, "outgoing").set(c, out)
        field(c, "incoming").set(c, inn)
        field(c, "handedOff").set(c, true)
        field(c, "tailTarget").set(c, null)
        setPhase(c, "IDLE")
        fake.resetOpen()
        val finish = CrossfadeController::class.java.getDeclaredMethod("finish")
        finish.isAccessible = true
        finish.invoke(c)
        assertEquals(
            "spare must open to OPEN_HZ",
            TransitionFilterProcessor.OPEN_HZ, fake.lastOutgoing?.first ?: -1f, 1e-3f,
        )
        assertEquals(
            "spare HP must open to OFF_HZ",
            TransitionFilterProcessor.OFF_HZ, fake.lastOutgoing?.second ?: -1f, 1e-3f,
        )
        assertEquals("spare mid must open to 0 dB", 0f, fake.lastOutgoingMid ?: -1f, 1e-6f)
        assertEquals("survivor must stay untouched", 700f to 20f, fake.lastIncoming)
        assertEquals("no gated open while survivor audible", 0, fake.openCalls)
    }

    @Test
    fun reverbRideRampsSmoothlyFromPoint75ToOne() {
        val fake = FakeFilters()
        val c = newController(fake)
        val plan = TransitionPlan(
            transitionStyle = TransitionStyle.EQUAL_POWER,
            djEffect = DjEffect.REVERB,
            beatSeconds = 0.5,
            fadeSeconds = 4.0,
        )
        field(c, "render").set(c, renderFor(c, plan))
        field(c, "handedOff").set(c, true)
        val wet = effectSettings(DjEffect.REVERB).wet
        val amounts = mutableListOf<Float>()
        for (p in listOf(0.75f, 0.80f, 0.90f, 1.0f)) {
            invokeRide(c, "rideColour", p)
            assertEquals(DjEffect.REVERB, fake.lastOutgoingColourEffect)
            amounts.add(fake.lastOutgoingColourAmount ?: -1f)
        }
        assertEquals("reverb must start silent at 0.75", 0f, amounts[0], 1e-6f)
        assertTrue(
            "reverb must ramp monotonically, got $amounts",
            amounts[0] < amounts[1] && amounts[1] < amounts[2] && amounts[2] < amounts[3],
        )
        assertTrue(
            "0.80 must still be well below wet (no single-tick jump), 0.80=${amounts[1]} wet=$wet",
            amounts[1] < wet * 0.5f,
        )
        assertTrue("0.90 must still be below wet, 0.90=${amounts[2]} wet=$wet", amounts[2] < wet)
        assertEquals("reverb must reach wet at 1.0", wet, amounts[3], 1e-4f)
        for (i in 1 until amounts.size) {
            val step = amounts[i] - amounts[i - 1]
            assertTrue("step $i ($step) must not be a jump towards $wet", step < wet * 0.6f)
        }
    }

    @Test
    fun driveFadeClampsStrandedIncomingFilterWhenProgressReachesOne() {
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
        field(c, "handedOff").set(c, true)
        // Outgoing budget exhausts (progress 1) while the incoming sink is
        // still at ~0.375: incoming would strand parked if completion fired
        // without the clamp.
        val outState = PlayerState(volume = 0f, position = 10_000L, durationMs = 60_000L)
        val inState = PlayerState(volume = 0f, position = 1_500L, durationMs = 200_000L)
        val out = fakePlayer(outState)
        val inn = fakePlayer(inState)
        field(c, "outgoing").set(c, out)
        field(c, "incoming").set(c, inn)
        field(c, "fadeMs").setLong(c, 4000L)
        field(c, "fadeEndMs").setLong(c, 60_000L)
        field(c, "incomingCueTimeMs").setLong(c, 0L)
        field(c, "fadeOutOriginMs").setLong(c, 8000L)
        field(c, "fadeOutBudgetMs").setLong(c, 2000L)
        field(c, "smartFadeActive").set(c, false)
        setPhase(c, "FADING")
        val driveFade = CrossfadeController::class.java.getDeclaredMethod("driveFade")
        driveFade.isAccessible = true
        val savedCrossfade = AppSettings.crossfadeSeconds.value
        AppSettings.crossfadeSeconds.value = 8
        try {
            // First tick: outgoing at progress 1, incoming at ~0.375, file
            // not about to EOF => must NOT complete; incoming stays parked.
            driveFade.invoke(c)
            val phaseAfterFirst = field(c, "phase").get(c).toString()
            assertTrue("must wait for incoming when file is far from EOF, phase=$phaseAfterFirst", phaseAfterFirst == "FADING")
            assertTrue(
                "incoming must still be parked before EOF, got ${fake.lastIncoming?.first}",
                (fake.lastIncoming?.first ?: 99999f) < 5000f,
            )
            // Second tick: outgoing now within the guard window => complete,
            // and the clamped ride must leave the incoming full-band.
            outState.durationMs = 10_200L
            driveFade.invoke(c)
            assertEquals(
                "incoming must clamp to full-band at completion",
                TransitionFilterProcessor.OPEN_HZ, fake.lastIncoming?.first ?: -1f, 1e-3f,
            )
            assertEquals(
                "outgoing must clamp to full-band at completion",
                TransitionFilterProcessor.OPEN_HZ, fake.lastOutgoing?.first ?: -1f, 1e-3f,
            )
        } finally {
            AppSettings.crossfadeSeconds.value = savedCrossfade
        }
    }

    @Test
    fun abnormalSettingOffOpensSurvivorOnly() {
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
        field(c, "handedOff").set(c, true)
        // Progress ~0.05 (both parked) with the classic setting switched
        // off: the blend must end before progress 1 and the survivor must
        // not strand parked.
        val outState = PlayerState(volume = 0f, position = 8_100L, durationMs = 60_000L)
        val inState = PlayerState(volume = 0f, position = 200L, durationMs = 200_000L)
        val out = fakePlayer(outState)
        val inn = fakePlayer(inState)
        field(c, "outgoing").set(c, out)
        field(c, "incoming").set(c, inn)
        field(c, "fadeMs").setLong(c, 4000L)
        field(c, "fadeEndMs").setLong(c, 60_000L)
        field(c, "incomingCueTimeMs").setLong(c, 0L)
        field(c, "fadeOutOriginMs").setLong(c, 8_000L)
        field(c, "fadeOutBudgetMs").setLong(c, 2_000L)
        field(c, "smartFadeActive").set(c, false)
        field(c, "tailTarget").set(c, null)
        setPhase(c, "FADING")
        val driveFade = CrossfadeController::class.java.getDeclaredMethod("driveFade")
        driveFade.isAccessible = true
        val savedCrossfade = AppSettings.crossfadeSeconds.value
        AppSettings.crossfadeSeconds.value = 0
        try {
            driveFade.invoke(c)
            assertEquals("TAIL", field(c, "phase").get(c).toString())
            assertTrue(
                "survivor parked before tail completes, got ${fake.lastIncoming?.first}",
                (fake.lastIncoming?.first ?: 99999f) < 5000f,
            )
            val parkedOutgoing = fake.lastOutgoing?.first ?: -1f
            val parkedOutgoingHigh = fake.lastOutgoing?.second ?: -1f
            assertTrue("spare parked before tail completes, got $parkedOutgoing", parkedOutgoing < 5000f)
            // Tail dissolve completes: retiree silent, survivor alone at 1.
            outState.volume = 0f
            inState.volume = 1f
            field(c, "outgoing").set(c, out)
            field(c, "incoming").set(c, inn)
            setPhase(c, "TAIL")
            fake.resetOpen()
            val finish = CrossfadeController::class.java.getDeclaredMethod("finish")
            finish.isAccessible = true
            finish.invoke(c)
            assertEquals(
                "survivor must return to full-band",
                TransitionFilterProcessor.OPEN_HZ, fake.lastIncoming?.first ?: -1f, 1e-3f,
            )
            assertEquals(
                "survivor HP must return to OFF_HZ",
                TransitionFilterProcessor.OFF_HZ, fake.lastIncoming?.second ?: -1f, 1e-3f,
            )
            assertEquals(
                "spare must stay untouched",
                parkedOutgoing, fake.lastOutgoing?.first ?: -1f, 1e-3f,
            )
            assertEquals(
                "spare HP must stay untouched",
                parkedOutgoingHigh, fake.lastOutgoing?.second ?: -1f, 1e-3f,
            )
            assertEquals("no both-open while survivor audible", 0, fake.openCalls)
        } finally {
            AppSettings.crossfadeSeconds.value = savedCrossfade
        }
    }

    @Test
    fun forcedBailOpensSurvivorOnlyAfterRetireeSilent() {
        val fake = FakeFilters()
        val c = newController(fake)
        val outState = PlayerState(volume = 0.9f)
        val inState = PlayerState(volume = 0.4f)
        val out = fakePlayer(outState)
        val inn = fakePlayer(inState)
        // Survivor (filters.incoming) parked mid-ride, spare (filters.outgoing)
        // parked at the SWEEP/DECLINE floor.
        fake.incoming(700f, 20f)
        fake.outgoing(2800f, 20f)
        field(c, "outgoing").set(c, out)
        field(c, "incoming").set(c, inn)
        field(c, "handedOff").set(c, true)
        field(c, "tailTarget").set(c, null)
        setPhase(c, "FADING")
        fake.resetOpen()
        val bail = CrossfadeController::class.java.getDeclaredMethod("bail", Boolean::class.javaPrimitiveType)
        bail.isAccessible = true
        bail.invoke(c, true)
        assertEquals("BAILING", field(c, "phase").get(c).toString())
        assertEquals(700f to 20f, fake.lastIncoming)
        assertEquals(2800f to 20f, fake.lastOutgoing)
        // BAILING dissolve completes: retiree silent, survivor alone at 1.
        outState.volume = 0f
        inState.volume = 1f
        field(c, "outgoing").set(c, out)
        field(c, "incoming").set(c, inn)
        setPhase(c, "BAILING")
        val finish = CrossfadeController::class.java.getDeclaredMethod("finish")
        finish.isAccessible = true
        finish.invoke(c)
        assertEquals(
            "survivor must return to full-band",
            TransitionFilterProcessor.OPEN_HZ, fake.lastIncoming?.first ?: -1f, 1e-3f,
        )
        assertEquals(
            "spare must stay untouched",
            2800f to 20f, fake.lastOutgoing,
        )
        assertEquals("no both-open while survivor audible", 0, fake.openCalls)
    }

    @Test
    fun staleTailDoesNotBlockNextNeutralization() {
        val fake = FakeFilters()
        val c = newController(fake)
        // First transition: post-handoff tail completion. The spare
        // (outgoing) has dissolved to silence, the survivor (incoming) is
        // at 1, and tailTarget is the dissolving spare.
        val outState = PlayerState(volume = 0f)
        val inState = PlayerState(volume = 1f)
        val out = fakePlayer(outState)
        val inn = fakePlayer(inState)
        fake.outgoing(2800f, 20f)
        field(c, "outgoing").set(c, out)
        field(c, "incoming").set(c, inn)
        field(c, "handedOff").set(c, true)
        field(c, "tailTarget").set(c, out)
        setPhase(c, "TAIL")
        val finish = CrossfadeController::class.java.getDeclaredMethod("finish")
        finish.isAccessible = true
        finish.invoke(c)
        // finish must clear the tail reference after retirement. Without the
        // clear, retire()'s volume=1 reset leaves a stale audible reference.
        assertTrue("finish must clear tailTarget", field(c, "tailTarget").get(c) == null)
        assertEquals(1f, outState.volume, 1e-6f)
        // Next transition: the ex-tail player is now the audible survivor
        // at 1 and the new spare is silent. Retiree gating must see only
        // the silent spare, not the stale tail at 1, so the spare
        // neutralisation begin() runs still opens.
        val spareState = PlayerState(volume = 0f)
        val spare = fakePlayer(spareState)
        fake.outgoing(2800f, 20f)
        field(c, "outgoing").set(c, spare)
        field(c, "incoming").set(c, out)
        field(c, "handedOff").set(c, true)
        setPhase(c, "IDLE")
        finish.invoke(c)
        assertEquals(
            "stale tail must not block next spare neutralisation",
            TransitionFilterProcessor.OPEN_HZ, fake.lastOutgoing?.first ?: -1f, 1e-3f,
        )
        assertTrue("tailTarget must stay cleared", field(c, "tailTarget").get(c) == null)
    }
}
