package com.music.bitchord.playback.loopmix

import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.StuckPlayerDetector
import androidx.media3.common.util.StuckPlayerException
import com.music.bitchord.playback.smart.LoopMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.lang.reflect.Proxy
import kotlin.math.ceil
import kotlin.math.roundToLong

class LoopIncomingStartDetectorTest {
    private fun recordedCrownClock(hold: Double = 0.0): LoopMix.Clock {
        fun pick(start: Double, end: Double, bars: Int): LoopMix.Pick {
            val beatSec = (end - start) / (bars * 4)
            return LoopMix.Pick(start, end, bars, beatSec, 60.0 / beatSec, 0.0, LoopMix.Check.MASK)
        }
        return LoopMix.Clock(LoopMix.Plan(
            out = pick(171.561, 175.546, 2),
            inn = pick(5.365, 15.36, 4),
            fold = 1.0,
            enterBars = 4,
            rampBars = 10,
            exitBars = 2,
            totalBars = 16,
            outLoops = true,
            inLoops = true,
            incomingHoldSec = hold,
        ))
    }

    private data class ScheduledSource(
        val clock: LoopMix.Clock,
        val handover: Double,
        val startAt: Double,
        val startTrack: Double,
    ) {
        val insertionDebtSec = (startTrack - (handover + startAt - clock.elementAt)).coerceAtLeast(0.0)

        fun positionAt(mixSec: Double): Double {
            val elapsed = (mixSec - startAt).coerceAtLeast(0.0)
            // Sink-position model only: progress until alignment, hold while inserted silence
            // pays the negative-source deficit, then resume rate-1 input-source consumption.
            // No detector logic or actual sink implementation is reproduced here.
            return startTrack + minOf(elapsed, ALIGNMENT_DELAY_SEC) +
                (elapsed - ALIGNMENT_DELAY_SEC - insertionDebtSec).coerceAtLeast(0.0)
        }
    }

    private data class Detection(val atMs: Long, val exception: StuckPlayerException)

    /**
     * Exercises Media3 1.11.0's real StuckPlayerDetector and its captured Player.Listener.
     * Explicit one-second onEvents calls drive its real elapsed-time checks. The handler
     * proxy does not dispatch messages, so this does not test Android message scheduling.
     * The existing unitTests.isReturnDefaultValues setting supplies mockable Android stubs.
     */
    private class DetectorHarness(
        private val startAt: Double,
        private val positionAt: (Double) -> Double,
    ) : Closeable {
        private var nowMs = 0L
        private lateinit var listener: Player.Listener
        val detections = mutableListOf<Detection>()
        private val events = Player.Events(FlagSet.Builder().build())
        private val handler = interfaceProxy(HandlerWrapper::class.java) { method, _ ->
            when (method) {
                "sendEmptyMessageDelayed" -> true
                "removeMessages", "removeCallbacksAndMessages" -> null
                else -> error("Unexpected HandlerWrapper method: $method")
            }
        }
        private val clock = interfaceProxy(Clock::class.java) { method, _ ->
            when (method) {
                "elapsedRealtime", "uptimeMillis", "currentTimeMillis" -> nowMs
                "nanoTime" -> nowMs * 1_000_000L
                "createHandler" -> handler
                "onThreadBlocked" -> null
                else -> error("Unexpected Clock method: $method")
            }
        }
        private val player = interfaceProxy(Player::class.java) { method, args ->
            when (method) {
                "addListener" -> {
                    check(!this::listener.isInitialized)
                    listener = args[0] as Player.Listener
                    null
                }
                "removeListener" -> {
                    check(args[0] === listener)
                    null
                }
                "getApplicationLooper" -> null
                "getPlaybackState" -> Player.STATE_READY
                "isPlaying", "getPlayWhenReady" -> nowMs / 1000.0 >= startAt
                "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
                "getCurrentTimeline" -> Timeline.EMPTY
                "getCurrentAdGroupIndex", "getCurrentAdIndexInAdGroup" -> C.INDEX_UNSET
                "getCurrentPosition" -> (positionAt(nowMs / 1000.0) * 1000.0).roundToLong()
                else -> error("Unexpected Player method: $method")
            }
        }
        private val detector = StuckPlayerDetector(
            player,
            StuckPlayerDetector.Callback { exception -> detections += Detection(nowMs, exception) },
            clock,
            10_000,
            PLAYING_TIMEOUT_MS,
            10_000,
            10_000,
        )

        fun tick(atMs: Long) {
            require(atMs >= nowMs)
            nowMs = atMs
            listener.onEvents(player, events)
        }

        fun runThroughElementAndTenSeconds(clock: LoopMix.Clock) {
            val lastTickMs = ceil(clock.elementAt + 10.0).toLong() * 1000L
            for (atMs in 0L..lastTickMs step 1000L) tick(atMs)
        }

        override fun close() = detector.release()
    }

    @Test
    fun legacyUnheldCrownSilenceDebtTriggersTheActualMedia3Detector() {
        val clock = recordedCrownClock()
        val handover = clock.elementTrackTime
        val legacyStartAt = LoopMixSession.STARTUP_LEAD
        val legacyStartTrack = maxOf(0.0, handover + legacyStartAt - clock.elementAt)
        val source = ScheduledSource(clock, handover, legacyStartAt, legacyStartTrack)
        assertTrue("legacy alignment freezes the source for more than ten seconds", source.insertionDebtSec > 10.0)

        DetectorHarness(source.startAt, source::positionAt).use { harness ->
            harness.runThroughElementAndTenSeconds(clock)
            assertTrue("real detector must report the modeled legacy stall", harness.detections.isNotEmpty())
            val first = harness.detections.first()
            val freezeObservedAtMs = ceil(source.startAt + ALIGNMENT_DELAY_SEC).toLong() * 1000L
            assertEquals(freezeObservedAtMs + PLAYING_TIMEOUT_MS, first.atMs)
            assertTrue(harness.detections.all {
                it.exception.stuckType == StuckPlayerException.STUCK_PLAYING_NO_PROGRESS &&
                    it.exception.timeoutMs == PLAYING_TIMEOUT_MS
            })
        }
    }

    @Test
    fun eligibleScheduledSourcesDoNotTripActualDetectorThroughElementPlusTenSeconds() {
        for (hold in listOf(0.0, 1e-10, 1.25, 4.0, 12.0)) {
            val clock = recordedCrownClock(hold)
            for (handover in listOf(2.5, 3.0, clock.elementTrackTime, clock.elementTrackTime + 1.25)) {
                val (startAt, startTrack) = LoopMixSession.incomingStartFor(clock, handover)
                val source = ScheduledSource(clock, handover, startAt, startTrack)
                DetectorHarness(source.startAt, source::positionAt).use { harness ->
                    harness.runThroughElementAndTenSeconds(clock)
                    assertTrue(
                        "hold=$hold H=$handover start=$startAt track=$startTrack " +
                            "debt=${source.insertionDebtSec}: ${harness.detections}",
                        harness.detections.isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun genuinelyFrozenPlayingPositionStillReportsNoProgressAfterTenSeconds() {
        DetectorHarness(startAt = 0.0, positionAt = { 1.662 }).use { harness ->
            for (atMs in 0L..9000L step 1000L) harness.tick(atMs)
            assertTrue("no early timeout", harness.detections.isEmpty())
            harness.tick(10_000L)
            assertEquals(1, harness.detections.size)
            assertEquals(10_000L, harness.detections.single().atMs)
            assertEquals(StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, harness.detections.single().exception.stuckType)
            assertEquals(PLAYING_TIMEOUT_MS, harness.detections.single().exception.timeoutMs)
            harness.tick(11_000L)
            assertTrue(harness.detections.all { it.exception.stuckType == StuckPlayerException.STUCK_PLAYING_NO_PROGRESS })
        }
    }

    private companion object {
        const val ALIGNMENT_DELAY_SEC = 1.65
        const val PLAYING_TIMEOUT_MS = 10_000

        fun <T> interfaceProxy(type: Class<T>, invoke: (String, Array<out Any?>) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "Virtual${type.simpleName}"
                    else -> invoke(method.name, args ?: emptyArray())
                }
            })
    }
}
