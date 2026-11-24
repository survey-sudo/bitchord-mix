package com.music.bitchord.playback.djmix

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.playback.TransitionFilters
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

/** Exercises engine lifecycle effects with virtual time; no controller or native player is constructed. */
@OptIn(ExperimentalCoroutinesApi::class)
class EaseTempoEngineCancellationTest {
    // null: queued but not entered; 0: descending; 2: zero before speed
    // write; 6: ascending. Each stage is reached with runCurrent at its time.
    private val stages = listOf<Long?>(null, 0L, 2L, 6L)

    @Test
    fun pauseCancellationSettlesAtEveryNotchStageWithoutResumingPlayback() {
        for (stage in stages) Rig().use { r ->
            r.startReset(stage)
            r.first.playWhenReady = false
            r.engine.cancelPendingSpeedChange()

            assertSettled(r.first, 1f, 0.8f)
            assertFalse(r.first.playWhenReady)
            val effects = r.first.effectCount
            r.scheduler.advanceUntilIdle()
            assertEquals(effects, r.first.effectCount)
            assertEquals(0, r.first.transportWrites)
        }
    }

    @Test
    fun endEaseFalseSettlesAtEveryStageBeforeReleaseAndLeavesNoLateWrites() {
        for (stage in stages) Rig().use { r ->
            r.startReset(stage)
            // release() and arm paths use this after the clickless reset has
            // already cleared easing. It must still finish the pending reset.
            r.engine.endEase(clickless = false)
            assertSettled(r.first, 1f, 0.8f)
            val effects = r.first.effectCount
            r.first.released = true

            r.scheduler.advanceUntilIdle()
            assertEquals(effects, r.first.effectCount)
            assertEquals(0, r.first.transportWrites)
        }
    }

    @Test
    fun newArmSettlesOldResetBeforeTakingGainOwnership() {
        for (stage in stages) Rig().use { r ->
            r.startReset(stage)
            r.engine.endEase(clickless = false)
            assertSettled(r.first, 1f, 0.8f)

            r.idle = false
            r.engine.configureOutgoingRamp(1.03, 400L, 20_000L, 4_000L)
            r.engine.prepareIncomingSpeed(r.second.player, 1.04, localBlendInFlight = false)
            r.first.volume = 0.33f // The next fade now owns this gain.
            val effects = r.first.effectCount
            r.scheduler.advanceUntilIdle()

            assertSettled(r.first, 1f, 0.33f)
            assertEquals(1.04f, r.second.parameters.speed, 0.00001f)
            assertEquals(effects, r.first.effectCount)
        }
    }

    @Test
    fun cancelledOldGenerationCannotClearOrRestoreOverANewerReset() = Rig().use { r ->
        r.startReset(2L)
        r.engine.endEase(clickless = false)
        r.first.volume = 0.6f
        r.queueReset()
        // The cancelled continuation and the new job are both queued here.
        r.scheduler.runCurrent()
        assertEquals(0.3f, r.first.volume, 0.00001f)

        r.engine.cancelPendingSpeedChange()
        assertSettled(r.first, 1f, 0.6f)
        val effects = r.first.effectCount
        r.scheduler.advanceUntilIdle()
        assertEquals(effects, r.first.effectCount)
    }

    @Test
    fun roleSwapAndHandoverCannotWriteAnObsoleteResetIntoEitherDeck() {
        for (stage in stages) Rig().use { r ->
            r.startReset(stage)
            r.session = r.second
            r.idle = false
            r.handedOff = true
            val firstEffects = r.first.effectCount
            val secondEffects = r.second.effectCount
            // A locally admitted handover deliberately keeps its new rate.
            r.engine.onHandover(r.second.player, localBlendInFlight = true)
            r.scheduler.advanceUntilIdle()

            assertEquals(firstEffects, r.first.effectCount)
            assertEquals(secondEffects, r.second.effectCount)
        }
    }

    @Test
    fun asyncOwnershipChecksRejectRoleItemAndPhaseChangesWithoutCancellationHook() {
        for (stage in stages) for (change in listOf("role", "item", "index", "phase")) {
            Rig().use { r ->
                r.startReset(stage)
                when (change) {
                    "role" -> r.session = r.second
                    "item" -> r.first.item = MediaItem.Builder().setMediaId("replacement").build()
                    "index" -> r.first.itemIndex++
                    "phase" -> r.idle = false
                }
                val firstEffects = r.first.effectCount
                val secondEffects = r.second.effectCount
                r.scheduler.advanceUntilIdle()
                r.engine.cancelPendingSpeedChange()

                assertEquals("$change at $stage", firstEffects, r.first.effectCount)
                assertEquals(secondEffects, r.second.effectCount)
            }
        }
    }

    @Test
    fun cancellationAfterItemReplacementDoesNotResetOrRestoreTheNewItem() {
        for (stage in stages) Rig().use { r ->
            r.startReset(stage)
            r.first.item = MediaItem.Builder().setMediaId("next-item").build()
            r.first.parameters = PlaybackParameters(1.3f)
            r.first.volume = 0.42f
            val effects = r.first.effectCount

            r.engine.endEase(clickless = false)
            r.scheduler.advanceUntilIdle()
            assertSettled(r.first, 1.3f, 0.42f)
            assertEquals(effects, r.first.effectCount)
        }
    }

    @Test
    fun cancellationUsesLatestListenerSpeedIncludingAnAlreadyAppliedChange() {
        for (stage in stages) for (alreadyApplied in listOf(false, true)) Rig().use { r ->
            r.startReset(stage)
            r.userSpeed = 1.25f
            if (alreadyApplied) r.first.parameters = PlaybackParameters(r.userSpeed)
            val speedWrites = r.first.speedWrites.size

            r.engine.cancelPendingSpeedChange()
            r.scheduler.advanceUntilIdle()
            assertSettled(r.first, 1.25f, 0.8f)
            assertTrue(r.first.speedWrites.drop(speedWrites).all { it == 1.25f })
        }
    }

    @Test
    fun normalCompletionUsesLatestListenerSpeedAndForgetsThePendingReset() = Rig().use { r ->
        r.startReset(6L)
        r.userSpeed = 1.2f
        r.scheduler.advanceUntilIdle()
        assertSettled(r.first, 1.2f, 0.8f)

        r.first.parameters = PlaybackParameters(1.37f)
        r.first.volume = 0.4f
        val effects = r.first.effectCount
        r.engine.cancelPendingSpeedChange()
        r.engine.endEase(clickless = false)
        r.engine.driveEase()
        assertSettled(r.first, 1.37f, 0.4f)
        assertEquals(effects, r.first.effectCount)
    }

    @Test
    fun settlingFailurePropagatesAndIsNotReportedAsSuccessfulRestoration() = Rig().use { r ->
        r.startReset(2L)
        val failure = IllegalStateException("speed write failed")
        r.first.speedFailure = failure
        try {
            r.engine.cancelPendingSpeedChange()
            fail("Cancellation must propagate a failed speed reset")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1.05f, r.first.parameters.speed, 0.00001f)
        assertEquals(0f, r.first.volume, 0f)
        r.first.speedFailure = null
        val effects = r.first.effectCount
        r.scheduler.advanceUntilIdle()
        r.engine.cancelPendingSpeedChange()
        assertEquals(effects, r.first.effectCount)
    }

    private fun assertSettled(deck: Deck, speed: Float, volume: Float) {
        assertEquals(speed, deck.parameters.speed, 0.00001f)
        assertEquals(volume, deck.volume, 0.00001f)
        assertEquals(0, deck.transportWrites)
    }

    private class Rig : AutoCloseable {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val first = Deck("outgoing-survivor")
        val second = Deck("incoming")
        var session = first
        var userSpeed = 1f
        var idle = true
        var handedOff = false
        val engine = EaseTempoEngine(object : EaseTempoHost {
            override val scope: CoroutineScope get() = this@Rig.scope
            override val filters: TransitionFilters = TransitionFilters.None
            override val tracks: TransitionFilters = TransitionFilters.None
            override fun active(): ExoPlayer = session.player
            override fun incoming(): ExoPlayer = second.player
            override fun isHandedOff(): Boolean = handedOff
            override fun isIdle(): Boolean = idle
            override fun nowMs(): Long = scheduler.currentTime
            override fun positionMs(player: ExoPlayer): Double = player.currentPosition.toDouble()
            override fun userPlaybackSpeed(): Float = userSpeed
            override fun inParty(): Boolean = false
        })

        fun queueReset() {
            first.position = 10_000L
            engine.beginEase(first.player, 1.05, TrackAnalysis())
            first.position = 0L // Backward seek invalidates the ease's threshold.
            engine.driveEase()
        }

        fun startReset(stage: Long?) {
            queueReset()
            if (stage != null) {
                scheduler.advanceTimeBy(stage)
                scheduler.runCurrent()
            }
        }

        override fun close() {
            scope.cancel()
            scheduler.runCurrent()
        }
    }

    private class Deck(id: String) {
        var item = MediaItem.Builder().setMediaId(id).build()
        var itemIndex = 0
        var position = 10_000L
        var parameters = PlaybackParameters.DEFAULT
        var volume = 0.8f
        var playWhenReady = true
        var released = false
        var speedFailure: IllegalStateException? = null
        val speedWrites = mutableListOf<Float>()
        val volumeWrites = mutableListOf<Float>()
        var transportWrites = 0
        val effectCount: Int get() = speedWrites.size + volumeWrites.size + transportWrites
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Deck(${item.mediaId})"
                "getCurrentMediaItem" -> item
                "getCurrentMediaItemIndex" -> itemIndex
                "getCurrentPosition" -> position
                "getPlaybackParameters" -> parameters
                "getVolume" -> volume
                "getPlayWhenReady", "isPlaying" -> playWhenReady
                "setPlaybackSpeed" -> {
                    check(!released) { "speed write after release" }
                    speedFailure?.let { throw it }
                    val speed = args!![0] as Float
                    parameters = PlaybackParameters(speed)
                    speedWrites += speed
                    null
                }
                "setVolume" -> {
                    check(!released) { "volume write after release" }
                    volume = args!![0] as Float
                    volumeWrites += volume
                    null
                }
                "setPlayWhenReady", "play", "pause" -> {
                    transportWrites++
                    playWhenReady = when (method.name) {
                        "play" -> true
                        "pause" -> false
                        else -> args!![0] as Boolean
                    }
                    null
                }
                else -> throw AssertionError("Unexpected player call: ${method.name}")
            }
        } as ExoPlayer
    }
}
