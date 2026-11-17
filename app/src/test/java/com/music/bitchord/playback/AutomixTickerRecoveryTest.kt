package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.TrackAnalysis
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Runs the production start() loop; no copied ticker/retry decision helper. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomixTickerRecoveryTest {
    private class Deck {
        var current = MediaItem.Builder().setMediaId("A").build()
        var next = MediaItem.Builder().setMediaId("B").build()
        var position = 12_345L
        var itemFailure: Exception? = null
        var positionFailure: Exception? = null
        val mutations = mutableListOf<String>()
        val listeners = mutableListOf<Player.Listener>()
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "getCurrentMediaItem" -> { itemFailure?.let { throw it }; current }
                "getCurrentPosition" -> { positionFailure?.let { throw it }; position }
                "getNextMediaItemIndex" -> 1
                "getCurrentMediaItemIndex" -> 0
                "getMediaItemAt" -> if (args!![0] == 0) current else next
                "getMediaItemCount" -> 2
                "getDuration" -> 200_000L
                "getPlaybackState" -> Player.STATE_READY
                "getRepeatMode" -> Player.REPEAT_MODE_OFF
                "getPlayWhenReady", "isPlaying", "hasNextMediaItem" -> true
                "getVolume" -> 0.7f
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "stop", "seekTo", "play", "pause", "setPlayWhenReady", "setVolume", "clearMediaItems", "release" -> {
                    mutations.add(method.name)
                    null
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> C.INDEX_UNSET
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as ExoPlayer
    }

    private class Rig(scheduler: TestCoroutineScheduler) : AutoCloseable {
        val deck = Deck()
        val parent = SupervisorJob()
        val unhandled = mutableListOf<Throwable>()
        private val scope = CoroutineScope(
            parent + StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, error -> unhandled.add(error) },
        )
        private val savedSmartFade = AppSettings.smartFadeEnabled.value
        private val savedAnalysis = AppSettings.smartAnalysis.value
        private val savedStatus = AppSettings.automixStatus.value
        private val savedDetail = AppSettings.automixDetail.value
        private val savedInProgress = AppSettings.smartMixInProgress.value
        private val savedBlend = AppSettings.smartMixBlend.value
        private val savedView = AppSettings.smartMixView.value
        private val savedPreview = AppSettings.smartMixPreview.value
        private val telemetryDir = AutomixTelemetry::class.java.getDeclaredField("dir").apply { isAccessible = true }
        private val savedDir = telemetryDir.get(AutomixTelemetry)
        private val dir = Files.createTempDirectory("automix-ticker-test").toFile()
        var activeFailure: Exception? = null
        var activeCalls = 0
        var tickerCompletion: Throwable? = null
        val analysedIds = mutableListOf<String>()
        val controller = CrossfadeController(
            scope = scope,
            active = {
                activeCalls++
                activeFailure?.let { error -> activeFailure = null; throw error }
                deck.player
            },
            standby = { deck.player },
            onHandoff = { _, _ -> fail("recovery must not hand off players") },
            analysisFor = { item ->
                analysedIds.add(item.mediaId)
                TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = item.mediaId)
            },
            analysisRunningFor = { it.mediaId == "C" },
            // Keep this fixture focused on ticker publication, independent of planner gates.
            versionSwapActive = { true },
        )
        val ticker: Job
            get() = CrossfadeController::class.java.getDeclaredField("tickerJob")
                .apply { isAccessible = true }.get(controller) as Job

        init {
            AppSettings.smartFadeEnabled.value = false
            telemetryDir.set(AutomixTelemetry, dir)
        }

        fun start() {
            controller.start()
            ticker.invokeOnCompletion { tickerCompletion = it }
        }

        fun lines(event: String): List<String> = File(dir, "automix-telemetry.jsonl")
            .takeIf { it.exists() }?.readLines()?.filter { it.contains("\"ev\":\"$event\"") }.orEmpty()

        override fun close() {
            parent.cancel()
            telemetryDir.set(AutomixTelemetry, savedDir)
            AppSettings.smartFadeEnabled.value = savedSmartFade
            AppSettings.smartAnalysis.value = savedAnalysis
            AppSettings.automixStatus.value = savedStatus
            AppSettings.automixDetail.value = savedDetail
            AppSettings.smartMixInProgress.value = savedInProgress
            AppSettings.smartMixBlend.value = savedBlend
            AppSettings.smartMixView.value = savedView
            AppSettings.smartMixPreview.value = savedPreview
            dir.deleteRecursively()
        }
    }

    @Test fun actualTickerRecoversOnceThenPublishesFreshPairWithoutRestart() = runTest {
        Rig(testScheduler).use { r ->
            r.start() // synchronous listener setup succeeds; first actual tick fails.
            r.activeFailure = IllegalStateException("temporary getter failure https://secret.invalid/token")
            runCurrent()
            assertTrue(r.ticker.isActive)
            assertTrue(r.analysedIds.isEmpty())
            val error = r.lines("mix.tick.error").single()
            assertTrue(error.contains("\"phase\":\"IDLE\""))
            assertTrue(error.contains("\"currentId\":\"A\""))
            assertTrue(error.contains("\"nextId\":\"B\""))
            assertTrue(error.contains("\"positionMs\":12345"))
            assertTrue(error.contains("java.lang.IllegalStateException"))
            assertTrue(error.contains("AutomixTickerRecoveryTest"))
            assertFalse(error.contains("secret.invalid"))
            val callsAfterFailure = r.activeCalls
            advanceTimeBy(249)
            runCurrent()
            assertEquals(callsAfterFailure, r.activeCalls)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(r.analysedIds.containsAll(listOf("A", "B")))
            assertEquals(TrackAnalysisState.FAILED, AppSettings.smartAnalysis.value.current)
            assertEquals(1, r.lines("mix.tick.recovered").size)
            val firstState = r.lines("mix.state").single()
            assertTrue(firstState.contains("\"currentId\":\"A\""))
            assertTrue(firstState.contains("\"nextId\":\"B\""))
            assertTrue(firstState.contains("\"versionSwapActive\":true"))
            r.deck.position++
            advanceTimeBy(250)
            runCurrent()
            assertEquals("moving position must not flood state telemetry", 1, r.lines("mix.state").size)

            r.deck.current = r.deck.next
            r.deck.next = MediaItem.Builder().setMediaId("C").build()
            r.analysedIds.clear()
            advanceTimeBy(250)
            runCurrent()
            assertEquals(listOf("B", "C"), r.analysedIds)
            assertEquals(TrackAnalysisState.ANALYSING, AppSettings.smartAnalysis.value.next)
            assertEquals(2, r.lines("mix.state").size)
            assertTrue(r.lines("mix.state").last().contains("\"currentId\":\"B\""))
            assertTrue(r.lines("mix.state").last().contains("\"nextId\":\"C\""))
            assertTrue(r.ticker.isActive)
            assertTrue(r.deck.mutations.isEmpty())
            assertTrue(r.unhandled.isEmpty())
        }
    }

    @Test fun persistentGetterFailuresYieldAndLogOnceUntilSuccessRearmsReporting() = runTest {
        Rig(testScheduler).use { r ->
            r.deck.itemFailure = IllegalArgumentException("bad item")
            r.deck.positionFailure = IllegalStateException("bad position")
            r.start()
            runCurrent()
            val callsAfterFailure = r.activeCalls
            advanceTimeBy(249)
            runCurrent()
            assertEquals(callsAfterFailure, r.activeCalls)
            advanceTimeBy(5_001)
            runCurrent()
            assertTrue(r.activeCalls > callsAfterFailure)
            assertTrue(r.ticker.isActive)
            assertEquals(1, r.lines("mix.tick.error").size)
            assertTrue(r.lines("mix.tick.recovered").isEmpty())
            assertTrue(r.lines("mix.tick.error").single().contains("\"currentId\":null"))

            r.deck.itemFailure = null
            r.deck.positionFailure = null
            advanceTimeBy(250)
            runCurrent()
            assertEquals(1, r.lines("mix.tick.recovered").size)
            r.deck.itemFailure = IllegalArgumentException("bad item again")
            advanceTimeBy(250)
            runCurrent()
            assertEquals(2, r.lines("mix.tick.error").size)
            assertTrue(r.deck.mutations.isEmpty())
            assertTrue(r.unhandled.isEmpty())
        }
    }

    @Test fun cancellationExceptionEndsTickerWithoutRetryOrErrorTelemetry() = runTest {
        Rig(testScheduler).use { r ->
            r.start()
            val cancelled = CancellationException("cancel tick")
            r.activeFailure = cancelled
            runCurrent()
            assertTrue(r.ticker.isCancelled)
            assertSame(cancelled, r.tickerCompletion)
            val calls = r.activeCalls
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(calls, r.activeCalls)
            assertTrue(r.lines("mix.tick.error").isEmpty())
            assertTrue(r.parent.isActive)
            assertTrue(r.unhandled.isEmpty())
        }
    }

    @Test fun normalReleaseCancelsPendingRecoveryDelayAndRemovesListener() = runTest {
        Rig(testScheduler).use { r ->
            r.start()
            r.activeFailure = IllegalStateException("one failed tick")
            runCurrent()
            val pending = r.ticker
            r.controller.release()
            runCurrent()
            assertTrue(pending.isCancelled)
            assertTrue(r.deck.listeners.isEmpty())
            val callsAfterRelease = r.activeCalls
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(callsAfterRelease, r.activeCalls)
            assertTrue(r.lines("mix.tick.recovered").isEmpty())
            assertTrue(r.unhandled.isEmpty())
        }
    }

    @Test fun parentCancellationStopsPendingRecoveryWithoutRetry() = runTest {
        Rig(testScheduler).use { r ->
            r.start()
            r.activeFailure = IllegalStateException("one failed tick")
            runCurrent()
            r.parent.cancel()
            runCurrent()
            val calls = r.activeCalls
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(r.ticker.isCancelled)
            assertEquals(calls, r.activeCalls)
            assertTrue(r.lines("mix.tick.recovered").isEmpty())
            assertTrue(r.unhandled.isEmpty())
        }
    }
}
