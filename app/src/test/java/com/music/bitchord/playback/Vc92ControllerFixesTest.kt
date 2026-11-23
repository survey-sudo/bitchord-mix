package com.music.bitchord.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.LoopMix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.math.roundToLong

/**
 * vc92 controller fixes: terminal finish() clears the shown-countdown watchdog
 * state (no late mix.arm.death on a finished pair), and loopBail() mutes and
 * pauses the incoming player whenever it never became the session - even
 * when the port took over (tookOver && !handedOff).
 *
 * Reflection / proxy ExoPlayer pattern mirrors LoopHandoverCleanupTest.
 */
class Vc92ControllerFixesTest {
    private class Deck(val name: String, val events: MutableList<String>) {
        var position = 0L
        var ready = true
        var playing = true
        var state = Player.STATE_READY
        var volume = 1f
        val seeks = mutableListOf<Long>()
        val destructive = mutableListOf<String>()
        var onMute: (() -> Unit)? = null
        var onStop: (() -> Unit)? = null
        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "getPlaybackState" -> state
                "getPlayWhenReady" -> ready
                "setPlayWhenReady" -> { ready = args!![0] as Boolean; events.add("$name:play=$ready"); null }
                "isPlaying" -> playing && ready && state == Player.STATE_READY
                "getCurrentPosition" -> position
                "seekTo" -> { position = args!!.last() as Long; seeks.add(position); events.add("$name:seek"); null }
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; if (volume == 0f) onMute?.invoke(); null }
                "stop", "clearMediaItems", "release" -> {
                    destructive.add(method.name)
                    if (method.name == "stop") onStop?.invoke()
                    null
                }
                "getDuration" -> 200_000L
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as ExoPlayer
    }

    private class Rig(adopted: Boolean = true) : AutoCloseable {
        val events = mutableListOf<String>()
        val out = Deck("out", events)
        val inn = Deck("in", events)
        val outPort = LoopMixPort()
        val inPort = LoopMixPort()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var nowMs = 10_000L
        private val pick = LoopMix.Pick(100.0, 100.0 + 4.0 / 6, 1, 1.0 / 6, 360.0, 0.0, LoopMix.Check.MASK)
        val plan = LoopMix.Plan(pick, pick.copy(loopStart = 11.0, loopEnd = 11.0 + 4.0 / 6), 1.0, 0, 1, 0, 1)
        val clock = LoopMix.Clock(plan)
        private val renderer = LoopMixRenderer(48_000, object : LoopMixRenderer.Readout {
            override fun firstFrame() = -48_000L
            override fun heard(index: Int) = 13.032
            override fun exitGateViolations() = 0
        })
        val session = LoopMixSession::class.java.getDeclaredConstructor(
            LoopMix.Plan::class.java, LoopMix.Clock::class.java, String::class.java, String::class.java,
            LoopMixRenderer::class.java, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(plan, clock, "out", "in", renderer, 48_000, 13.0)
        val controller = CrossfadeController(
            scope, { if (adopted) inn.player else out.player }, { out.player }, { _, _ -> events.add("adopt") },
            nowMs = { nowMs },
        )

        init {
            session.attach(outPort, inPort)
            outPort.tookOver = true
            set("outgoing", out.player)
            set("incoming", inn.player)
            set("handedOff", adopted)
            val phase = field("phase").type.enumConstants.first { it.toString() == "LOOP_RUNNING" }
            set("phase", phase)
        }

        private fun field(name: String) = CrossfadeController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun set(name: String, value: Any?) { field(name).set(controller, value) }
        fun get(name: String): Any? = field(name).get(controller)
        fun invoke(name: String) {
            CrossfadeController::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(controller)
        }
        fun invokeLoopBail(reason: String) {
            CrossfadeController::class.java.declaredMethods.single { it.name == "loopBail" && it.parameterCount == 4 }
                .apply { isAccessible = true }.invoke(controller, reason, false, true, false)
        }
        override fun close() { scope.cancel() }
    }

    @Test fun terminalFinishClearsShownWatchdogAndLaterTickIsNoOp() {
        Rig(adopted = true).use { r ->
            // A finished pair: session player already on the incoming deck,
            // retiring spare already silent.
            r.out.volume = 0f
            r.inn.position = 100_000L
            val key = "outId->inId|12.5"
            r.set("shownCountdownKey", key)
            r.set("shownLoopStartMs", 0L)
            r.set("watchdogFired", false)
            r.set("loopPlanKey", key)
            r.set("loopPlanned", r.plan)

            r.invoke("finish")

            assertEquals("IDLE", r.get("phase").toString())
            assertNull(r.get("shownCountdownKey"))
            assertEquals(0L, r.get("shownLoopStartMs"))
            assertEquals(false, r.get("watchdogFired"))

            // The conditions for the watchdog are all still present except
            // the cleared key (playing, matching plan key, live plan, position
            // long past the shown bar): without the terminal clear it would
            // fire mix.arm.death and fall back.
            assertTrue(r.inn.player.isPlaying)
            assertEquals(key, r.get("loopPlanKey"))
            assertNotNull(r.get("loopPlanned"))

            r.invoke("checkShownWatchdog")

            // No-op: no fallback, no suppression, no watchdog flag.
            assertEquals("IDLE", r.get("phase").toString())
            assertNotNull(r.get("loopPlanned"))
            assertEquals(false, r.get("watchdogFired"))
            assertNull(r.get("loopSuppressedPair"))
        }
    }

    @Test fun loopBailMutesAndPausesIncomingWhenTookOverButNotHandedOff() {
        Rig(adopted = false).use { r ->
            r.set("loopSession", r.session)
            r.set("loopIncomingStarted", true)
            var innMuted = false
            r.inn.onMute = { innMuted = true }

            r.invokeLoopBail("vc92-test")

            // vc92: !wasHandedOff mutes even though tookOver=true. Under the
            // old (!tookOver && !wasHandedOff) gate `into` stayed audible and
            // playing, and finish() diverted to an emergency TAIL.
            assertTrue("into must be muted before finish()", innMuted)
            assertFalse(r.inn.player.playWhenReady)
            assertEquals("IDLE", r.get("phase").toString())
            assertNull(r.get("loopSession"))
        }
    }

    @Test fun healthyHandoffMissingIncomingClockCompletesWithoutAlignmentOrBail() {
        Rig(adopted = true).use { r ->
            r.set("loopSession", r.session)
            r.set("loopIncomingStarted", true)
            // Prove the splice delegate was fully consumed, as in live telemetry.
            val now = System.nanoTime()
            r.out.position = ((100.0 + r.clock.elementAt + 0.3) * 1000).roundToLong()
            r.inn.position = ((13.0 + 0.3) * 1000).roundToLong()
            r.outPort.publishPosition(r.out.position * 1000, now, r.outPort.generation, r.outPort.clockEpoch)
            r.outPort.publishTakeoverSpliceBufferStatus(
                r.outPort.generation, LoopMixPort.TakeoverSpliceBufferStatus.DELEGATE_FULLY_CONSUMED,
            )
            r.inPort.invalidatePosition() // incoming clock missing
            // Retired outgoing deck: not playing until finish() retires it.
            r.out.playing = false

            r.invoke("finishLoop")

            // Normal completion, not loopBail -> emergency tail: the retiring
            // spare is retired straight to IDLE (finish() nulls
            // lastCompleteReason on the way out, so observe the retire).
            assertEquals("IDLE", r.get("phase").toString())
            assertNull(r.get("loopRefusal"))
            assertTrue(r.inn.seeks.isEmpty())
            assertEquals(listOf("stop", "clearMediaItems"), r.out.destructive)
        }
    }
}
