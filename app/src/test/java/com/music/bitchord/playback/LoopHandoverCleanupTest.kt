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

/** Exercises production finishLoop (and finish routing), not a copied decision helper. */
class LoopHandoverCleanupTest {
    private class Deck(val name: String, val events: MutableList<String>) {
        var position = 0L
        var ready = true
        var playing = true
        var state = Player.STATE_READY // Media3 READY is 3, not ENDED (4).
        var volume = 1f
        val seeks = mutableListOf<Long>()
        val destructive = mutableListOf<String>()
        var onMute: (() -> Unit)? = null
        var onStop: (() -> Unit)? = null
        var onIsPlaying: (() -> Unit)? = null
        var onSeek: (() -> Unit)? = null
        val listeners = mutableListOf<Player.Listener>()
        var discontinuities = 0
        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "getPlaybackState" -> state
                "getPlayWhenReady" -> ready
                "setPlayWhenReady" -> { ready = args!![0] as Boolean; events.add("$name:play=$ready"); null }
                "isPlaying" -> { onIsPlaying?.invoke(); playing && ready && state == Player.STATE_READY }
                "getCurrentPosition" -> position
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "seekTo" -> {
                    val before = Player.PositionInfo(null, 0, null, null, 0, position, position, -1, -1)
                    position = args!!.last() as Long
                    seeks.add(position)
                    events.add("$name:seek")
                    onSeek?.invoke()
                    val after = Player.PositionInfo(null, 0, null, null, 0, position, position, -1, -1)
                    listeners.toList().forEach {
                        discontinuities++
                        it.onPositionDiscontinuity(before, after, Player.DISCONTINUITY_REASON_SEEK)
                    }
                    null
                }
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

    private class Rig(
        delaySec: Double = 0.3,
        anchor: Double = 13.0,
        started: Boolean = true,
        adopted: Boolean = true,
    ) : AutoCloseable {
        val events = mutableListOf<String>()
        val out = Deck("out", events)
        val inn = Deck("in", events)
        val outPort = LoopMixPort()
        val inPort = LoopMixPort()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private var clockPublishedAt = 0L
        private val targetAtPublish = anchor + delaySec
        private var invocationStart = 0L
        private var invocationEnd = 0L
        var completionReason: Any? = null
        // 12 beats including settle at 1/6 s gives elementAt=2 s, matching
        // the native freeze reproduction's cleanup at mix 2.3 / track 13.3.
        private val pick = LoopMix.Pick(100.0, 100.0 + 4.0 / 6, 1, 1.0 / 6, 360.0, 0.0, LoopMix.Check.MASK)
        private val plan = LoopMix.Plan(pick, pick.copy(loopStart = 11.0, loopEnd = 11.0 + 4.0 / 6), 1.0, 0, 1, 0, 1)
        val clock = LoopMix.Clock(plan)
        private val renderer = LoopMixRenderer(48_000, object : LoopMixRenderer.Readout {
            override fun firstFrame() = -48_000L
            override fun heard(index: Int) = 13.032 // frozen last-generated endpoint
            override fun exitGateViolations() = 0
        })
        val session = LoopMixSession::class.java.getDeclaredConstructor(
            LoopMix.Plan::class.java, LoopMix.Clock::class.java, String::class.java, String::class.java,
            LoopMixRenderer::class.java, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(plan, clock, "out", "in", renderer, 48_000, anchor)
        val controller = CrossfadeController(
            scope, { if (adopted) inn.player else out.player }, { out.player }, { _, _ -> events.add("adopt") },
            onBlendHeadroom = { completionReason = get("lastCompleteReason") },
        )

        init {
            session.attach(outPort, inPort)
            inPort.armIncoming(LoopMixPort.InArm((anchor * 1e6).roundToLong()))
            outPort.tookOver = true
            out.position = ((100.0 + clock.elementAt + delaySec) * 1000).roundToLong()
            inn.position = ((anchor + delaySec) * 1000).roundToLong()
            set("loopSession", session)
            set("outgoing", out.player)
            set("incoming", inn.player)
            set("loopIncomingStarted", started)
            set("handedOff", adopted)
            val phase = field("phase").type.enumConstants.first { it.toString() == "LOOP_RUNNING" }
            set("phase", phase)
            val now = System.nanoTime()
            clockPublishedAt = now
            outPort.publishPosition(out.position * 1000, now, outPort.generation, outPort.clockEpoch)
            inPort.publishPosition(inn.position * 1000, now, inPort.generation, inPort.clockEpoch)
        }

        private fun field(name: String) = CrossfadeController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun set(name: String, value: Any) { field(name).set(controller, value) }
        fun get(name: String): Any? = field(name).get(controller)
        fun invoke(name: String = "finishLoop") {
            invocationStart = System.nanoTime()
            CrossfadeController::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(controller)
            invocationEnd = System.nanoTime()
        }
        fun assertRecoveryAtCurrentTarget() {
            val target = inn.seeks.single()
            val earliest = ((targetAtPublish + (invocationStart - clockPublishedAt) / 1e9) * 1000).roundToLong()
            val latest = ((targetAtPublish + (invocationEnd - clockPublishedAt) / 1e9) * 1000).roundToLong()
            assertTrue("target=$target expected=$earliest..$latest", target in earliest - 1..latest + 1)
        }
        fun incomingPosition(seconds: Double) {
            inn.position = (seconds * 1000).roundToLong()
            inPort.publishPosition((seconds * 1e6).roundToLong(), System.nanoTime(), inPort.generation, inPort.clockEpoch)
        }
        fun setIncomingPublicationInProgress(inProgress: Boolean) {
            LoopMixPort::class.java.getDeclaredField("positionSequence").apply { isAccessible = true }
                .setLong(inPort, if (inProgress) 3L else 4L)
        }
        fun registerSessionListener() {
            CrossfadeController::class.java.getDeclaredMethod("listenTo", ExoPlayer::class.java)
                .apply { isAccessible = true }.invoke(controller, inn.player)
        }
        override fun close() { scope.cancel() }
    }

    @Test fun alignedCleanupIgnoresFrozenGeneratedEndpointAndRetiresOnlySpare() {
        Rig().use { r ->
            var mutedArmed = false
            var retiredDisarmed = false
            r.out.onMute = { mutedArmed = mutedArmed || (r.outPort.outArm != null && r.inPort.inArm != null) }
            r.out.onStop = { retiredDisarmed = r.outPort.outArm == null && r.inPort.inArm == null }
            r.invoke()
            assertTrue(r.inn.seeks.isEmpty())
            assertTrue(r.out.seeks.isEmpty())
            assertTrue(r.inn.destructive.isEmpty())
            assertEquals(listOf("stop", "clearMediaItems"), r.out.destructive)
            assertTrue(mutedArmed)
            assertTrue(retiredDisarmed)
            assertEquals("IDLE", r.get("phase").toString())
            assertEquals("loop-complete", r.completionReason)
        }
    }

    @Test fun genuineDriftRecoversToCurrentTimelineNotFrozenEndpoint() {
        Rig().use { r ->
            r.registerSessionListener()
            r.incomingPosition(13.55)
            r.inn.onSeek = { r.inPort.invalidatePosition(); r.inPort.interrupted = true }
            r.invoke()
            assertEquals(1, r.inn.seeks.size)
            assertEquals(1, r.inn.discontinuities)
            r.assertRecoveryAtCurrentTarget()
            assertEquals("loop-handover-drift-recovery", r.completionReason)
            assertTrue(r.out.seeks.isEmpty())
            assertTrue(r.inn.destructive.isEmpty())
            assertEquals("IDLE", r.get("phase").toString())
            assertNull(r.get("tailLoopSession"))
            assertEquals(listOf("stop", "clearMediaItems"), r.out.destructive)
        }
    }

    @Test fun publicationContentionDefersThenCompletesOnNextLoopTickWithoutSeek() {
        Rig().use { r ->
            r.setIncomingPublicationInProgress(true)
            r.invoke()
            assertEquals("LOOP_RUNNING", r.get("phase").toString())
            assertSame(r.session, r.get("loopSession"))
            assertNotNull(r.outPort.outArm)
            assertNotNull(r.inPort.inArm)
            assertTrue(r.out.destructive.isEmpty())
            assertTrue(r.inn.seeks.isEmpty())
            r.setIncomingPublicationInProgress(false)
            r.invoke("driveLoopMix")
            assertEquals("IDLE", r.get("phase").toString())
            assertEquals("loop-complete", r.completionReason)
            assertTrue(r.inn.seeks.isEmpty())
            assertTrue(r.inn.destructive.isEmpty())
        }
    }

    @Test fun persistentPublicationContentionHasBoundedNoSeekExit() {
        Rig().use { r ->
            r.setIncomingPublicationInProgress(true)
            r.invoke()
            r.set("loopCleanupContendedSinceNanos", System.nanoTime() - 101_000_000L)
            r.invoke("driveLoopMix")
            assertEquals("TAIL", r.get("phase").toString())
            assertNull(r.get("loopSession"))
            assertEquals("loop-cleanup-clock-contention-timeout", r.get("loopRefusal"))
            assertTrue(r.inn.seeks.isEmpty())
            assertTrue(r.out.seeks.isEmpty())
            assertTrue(r.inn.destructive.isEmpty())
        }
    }

    @Test fun ordinaryMedia3PollingAgeDoesNotTurnAlignedCleanupIntoTail() {
        Rig().use { r ->
            val publishedAt = System.nanoTime() - 300_000_000L
            r.outPort.publishPosition(r.out.position * 1000 - 300_000L, publishedAt, r.outPort.generation, r.outPort.clockEpoch)
            r.inPort.publishPosition(r.inn.position * 1000 - 300_000L, publishedAt, r.inPort.generation, r.inPort.clockEpoch)
            r.invoke()
            assertEquals("IDLE", r.get("phase").toString())
            assertEquals("loop-complete", r.completionReason)
            assertTrue(r.inn.seeks.isEmpty())
            assertTrue(r.inn.destructive.isEmpty())
        }
    }

    @Test fun publicationAfterFinishReferenceKeepsAlignedIncomingOnNormalCleanup() {
        Rig().use { r ->
            var overlappingPublishedAt = 0L
            r.out.onIsPlaying = {
                // finishLoop captures its reference before querying isPlaying.
                // Publish both clocks here, after that reference but before
                // either presentedSeconds read, using the real monotonic clock.
                r.out.onIsPlaying = null
                overlappingPublishedAt = System.nanoTime()
                r.outPort.publishPosition(r.out.position * 1000, overlappingPublishedAt, r.outPort.generation, r.outPort.clockEpoch)
                r.inPort.publishPosition(r.inn.position * 1000, overlappingPublishedAt, r.inPort.generation, r.inPort.clockEpoch)
            }
            r.invoke()
            assertTrue(overlappingPublishedAt != 0L)
            assertTrue(r.inn.seeks.isEmpty())
            assertTrue(r.out.seeks.isEmpty())
            assertTrue(r.inn.destructive.isEmpty())
            assertEquals(listOf("stop", "clearMediaItems"), r.out.destructive)
            assertEquals("IDLE", r.get("phase").toString())
            assertEquals("loop-complete", r.completionReason)
            assertNull(r.get("loopRefusal"))
            assertNull(r.get("tailLoopSession"))
        }
    }

    @Test fun freshClockWithinToleranceDoesNotSeekEvenIfPlayerPositionIsStale() {
        for (position in listOf(13.16, 13.3, 13.44)) {
            Rig().use { r ->
                r.incomingPosition(position)
                r.inn.position = 0L // coarse/control-thread position is not the decision clock
                r.invoke()
                assertTrue("presented=$position", r.inn.seeks.isEmpty())
                assertTrue(r.inn.destructive.isEmpty())
                assertEquals("loop-complete", r.completionReason)
            }
        }
    }

    @Test fun delayedCleanupAndActualKnotAnchorDoNotCreateDrift() {
        for (delay in listOf(0.3, 0.6, 1.0)) {
            Rig(delaySec = delay, anchor = 14.125).use { r ->
                assertNotEquals(14.125, r.clock.handoverTrackSec, 0.001)
                r.invoke()
                assertTrue("delay=$delay", r.inn.seeks.isEmpty())
                assertEquals("loop-complete", r.completionReason)
            }
        }
    }

    @Test fun unstartedBufferingIncomingIsPositionedBeforeAuthorizedPlayAndAdopt() {
        Rig(started = false, adopted = false).use { r ->
            r.inn.ready = false
            r.inn.state = Player.STATE_BUFFERING
            r.inn.position = 0L
            r.inPort.invalidatePosition()
            r.invoke()
            assertEquals(1, r.inn.seeks.size)
            r.assertRecoveryAtCurrentTarget()
            assertEquals(listOf("in:seek", "in:play=true", "adopt"), r.events)
            assertTrue(r.out.seeks.isEmpty())
            assertTrue(r.inn.destructive.isEmpty())
            assertEquals("loop-handover-unstarted-recovery", r.completionReason)
        }
    }

    @Test fun userPauseCannotStartOrAdoptUnstartedIncoming() {
        Rig(started = false, adopted = false).use { r ->
            r.out.ready = false
            r.inn.ready = false
            r.set("lastPlayWhenReadyChangeReason", Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            r.invoke()
            assertFalse(r.out.ready)
            assertFalse(r.inn.ready)
            assertFalse(r.events.contains("adopt"))
            assertTrue(r.out.seeks.isEmpty())
            assertTrue(r.inn.seeks.isEmpty())
            assertTrue(r.out.destructive.isEmpty())
        }
    }

    @Test fun startedBufferingSuppressedOrMissingClockPreservesSurvivorWithoutSeek() {
        for (failure in listOf("buffering", "suppressed", "missing", "stale", "old-arm", "flush")) {
            Rig().use { r ->
                when (failure) {
                    "buffering" -> r.inn.state = Player.STATE_BUFFERING
                    "suppressed" -> r.inn.playing = false
                    "missing", "flush" -> r.inPort.invalidatePosition()
                    "stale" -> r.inPort.publishPosition(1_000_000, System.nanoTime() - 1_000_000_000, r.inPort.generation, r.inPort.clockEpoch)
                    "old-arm" -> {
                        val generation = r.inPort.generation
                        val epoch = r.inPort.clockEpoch
                        r.inPort.armIncoming(LoopMixPort.InArm(13_000_000))
                        r.inPort.publishPosition(1_000_000, System.nanoTime(), generation, epoch)
                    }
                }
                r.invoke()
                assertTrue(failure, r.inn.seeks.isEmpty())
                assertTrue(failure, r.out.seeks.isEmpty())
                assertTrue(failure, r.inn.destructive.isEmpty())
                assertNull(r.get("loopSession"))
                assertEquals("TAIL", r.get("phase").toString())
            }
        }
    }

    @Test fun earlyFinishRoutingUsesBoundedAbnormalTailWithoutRecursionOrSeek() {
        Rig(delaySec = -0.5).use { r ->
            r.invoke("finish") // real finish -> finishLoop routing
            assertNull(r.get("loopSession"))
            assertEquals("TAIL", r.get("phase").toString())
            assertEquals("loop-cleanup-before-splice", r.get("loopRefusal"))
            assertTrue(r.inn.seeks.isEmpty())
            assertTrue(r.out.seeks.isEmpty())
            assertTrue(r.inn.destructive.isEmpty())
        }
    }
}
