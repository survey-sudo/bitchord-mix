package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionEvidenceCache
import com.music.bitchord.playback.smart.TransitionPlan
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The same ticker must plan another pair after a completed handoff and an IDLE scrub.
 * Real tick -> planAutoTransition -> considerSmartTransition -> begin/finish; no lifecycle
 * fields are seeded or reset by the harness. Default null loop ports/regions exercise the
 * classic fallback, so this does not establish native finishLoop/renderer correctness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomixReplanLifecycleTest {
    @Test
    fun completedHandoffPlansAndRunsTheNextPairOnTheOriginalTicker() = withRig { r ->
        r.start()
        r.assertPlannedPair("A", "B")
        r.completePlannedTransition("A", "B")

        r.activeDeck.position = 36_000L
        r.pulse()
        r.assertPlannedPair("B", "C")
        assertEquals(listOf("A->B"), r.handoffs)
        assertEquals(1, r.settled)
        assertEquals(false, r.get("handedOff"))
        assertEquals(0, r.get("queuedItemCount"))

        r.completePlannedTransition("B", "C")
        assertEquals(listOf("A->B", "B->C"), r.handoffs)
        assertEquals(listOf("B", "C"), r.armedItems)
        assertEquals(2, r.settled)
        assertEquals("C", r.activeDeck.player.currentMediaItem?.mediaId)
        assertEquals(2, r.activeDeck.player.currentMediaItemIndex)
        r.assertOriginalTicker()
    }

    @Test
    fun idleSeekAfterHandoffCancelsThenReplansTheCurrentPair() = withRig { r ->
        r.start()
        r.completePlannedTransition("A", "B")
        r.activeDeck.position = 36_000L
        r.pulse()
        r.assertPlannedPair("B", "C")
        val epoch = r.get("scrubEpoch") as Long

        // Matches the observed IDLE cancellation at B ~36 s -> 0. The fake emits
        // the real listener callback on the player selected by production handOff.
        r.activeDeck.player.seekTo(0L)
        assertEquals(1, r.activeDeck.seekCallbacks)
        assertEquals(epoch + 1L, r.get("scrubEpoch"))
        assertEquals("IDLE", r.get("phase").toString())
        assertNull(r.get("lastSmartPlan"))
        assertNull(AppSettings.smartTransitionWindow.value)
        assertEquals("B", r.activeDeck.player.currentMediaItem?.mediaId)
        assertEquals("C", r.activeDeck.player.getMediaItemAt(r.activeDeck.player.nextMediaItemIndex).mediaId)
        assertEquals(listOf("A->B"), r.handoffs)
        assertEquals(1, r.settled)
        r.pulse()
        r.assertOriginalTicker()

        // Android's local-test SystemClock stub stays at zero: advancing coroutine
        // time cannot expire the 350 ms scrub settle. Resume playback to its real
        // <=5 s-to-EOF bypass instead of clearing scrubSettledAtMs in the test.
        // This checks eventual replanning, not real-time expiry at B's opening.
        r.activeDeck.position = DURATION_MS - 5_000L
        r.pulse()
        r.assertPlannedPair("B", "C", allowArmed = true)
        assertEquals("ARMING", r.get("phase").toString())
        assertEquals(listOf("B", "C"), r.armedItems)
        assertEquals(3, r.get("queuedItemCount"))
        r.completePlannedTransition("B", "C")
        assertEquals(listOf("A->B", "B->C"), r.handoffs)
        assertEquals(2, r.settled)
        r.assertOriginalTicker()
    }

    private class Rig(val scheduler: TestCoroutineScheduler) : AutoCloseable {
        private val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        private val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, failure -> failures.add(failure) },
        )
        private val items = listOf("A", "B", "C").map { MediaItem.Builder().setMediaId(it).build() }
        // Keep one cached object per identity; no analysis is fabricated between ticks.
        private val analyses = items.associate { it.mediaId to analysedTrack(it.mediaId) }
        private val first = Deck("first", items, position = 10_000L, playWhenReady = true)
        private val second = Deck("second", emptyList())
        var activeDeck = first
            private set
        private var spareDeck = second
        val handoffs = mutableListOf<String>()
        val armedItems = mutableListOf<String>()
        var settled = 0
            private set
        private var originalTicker: Job? = null
        val controller = CrossfadeController(
            scope = scope,
            active = { activeDeck.player },
            standby = { spareDeck.player },
            onHandoff = { outgoing, incoming ->
                assertSame(activeDeck.player, outgoing)
                assertSame(spareDeck.player, incoming)
                handoffs.add("${outgoing.currentMediaItem?.mediaId}->${incoming.currentMediaItem?.mediaId}")
                val previous = activeDeck
                activeDeck = spareDeck
                spareDeck = previous
            },
            analysisFor = { analyses.getValue(it.mediaId) },
            onArmIncoming = { item, _ -> armedItems.add(item.mediaId) },
            onSettled = { settled++ },
        )

        fun get(name: String): Any? = CrossfadeController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller)

        fun start() {
            controller.start()
            originalTicker = get("tickerJob") as Job
            pulse()
        }

        fun pulse() {
            scheduler.advanceTimeBy(1_000L)
            scheduler.runCurrent()
            failures.firstOrNull()?.let { failure ->
                val site = failure.stackTrace.firstOrNull {
                    it.className.startsWith(CrossfadeController::class.java.name)
                }
                throw AssertionError(
                    "Automix ticker failed at $site: ${failure.javaClass.name}: ${failure.message}",
                    failure,
                )
            }
            assertOriginalTicker()
        }

        fun assertOriginalTicker() {
            assertSame("completion/scrub must not replace the ticker", originalTicker, get("tickerJob"))
            assertTrue("the original ticker must still be active", originalTicker?.isActive == true)
        }

        fun assertPlannedPair(outId: String, inId: String, allowArmed: Boolean = false) {
            assertEquals(outId, activeDeck.player.currentMediaItem?.mediaId)
            assertEquals(inId, activeDeck.player.getMediaItemAt(activeDeck.player.nextMediaItemIndex).mediaId)
            assertEquals(3, activeDeck.player.mediaItemCount)
            assertEquals(TrackAnalysisState.ANALYSED, AppSettings.smartAnalysis.value.current)
            assertEquals(TrackAnalysisState.ANALYSED, AppSettings.smartAnalysis.value.next)
            val start = controller.plannedTransitionFor(outId)
            assertNotNull("tick must publish a transition for $outId->$inId", start)
            if (allowArmed) {
                assertEquals("a due plan must have armed the incoming deck", "ARMING", get("phase").toString())
                assertEquals(inId, get("plannedNextId"))
            } else {
                assertTrue("$outId->$inId must have a future start", start!! > activeDeck.position)
            }
            val window = AppSettings.smartTransitionWindow.value
            assertNotNull("$outId->$inId must produce a second actionable window", window)
            assertTrue("the transition window must have positive width", window!!.end > window.start)
            val plan = get("lastSmartPlan") as TransitionPlan
            assertFalse(plan.blocked)
            assertTrue(plan.fadeMs > 0L)
            assertTrue("the planned overlap must still have time left", plan.transitionEnd * 1_000 > activeDeck.position)
            assertEquals(start, (plan.transitionStart * 1_000).roundToLong())
            // A non-null old window alone could still describe A->B. Require the
            // actual planner's newest evidence to name the current pair as well.
            val evidence = TransitionEvidenceCache.recent(1).single()
            assertEquals(outId, evidence.outId)
            assertEquals(inId, evidence.inId)
        }

        fun completePlannedTransition(outId: String, inId: String) {
            val out = activeDeck
            val into = spareDeck
            assertEquals(outId, out.player.currentMediaItem?.mediaId)
            val start = requireNotNull(controller.plannedTransitionFor(outId))
            out.position = maxOf(out.position, start + 1L)
            pulse()
            assertEquals("FADING", get("phase").toString())
            assertEquals(3, get("queuedItemCount"))
            assertEquals(inId, into.player.currentMediaItem?.mediaId)
            val plan = get("planInFlight") as TransitionPlan
            // Advance the two media clocks through the actual planned overlap;
            // gains, handoff, retirement, counters and IDLE reset are production work.
            val ratio = plan.incomingPlaybackRate / plan.outgoingPlaybackRate
            out.position = (plan.transitionEnd * 1_000).roundToLong().coerceAtMost(DURATION_MS)
            into.position += ceil(plan.fadeMs * ratio).toLong() + 100L
            pulse()
            assertEquals("IDLE", get("phase").toString())
            assertSame(into, activeDeck)
            assertEquals(inId, activeDeck.player.currentMediaItem?.mediaId)
            assertTrue("finish must retire the outgoing queue", out.items.isEmpty())
        }

        override fun close() {
            controller.release()
            scope.cancel()
        }
    }

    /** Identity and queue mutations matter: the same physical deck returns as C's loader. */
    private class Deck(
        private val name: String,
        initialItems: List<MediaItem>,
        var position: Long = 0L,
        var playWhenReady: Boolean = false,
    ) {
        val items = initialItems.toMutableList()
        private var index = if (items.isEmpty()) C.INDEX_UNSET else 0
        private var state = if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
        private var volume = 1f
        private var parameters = PlaybackParameters.DEFAULT
        private var repeat = Player.REPEAT_MODE_OFF
        private var shuffle = false
        private var skipSilence = false
        private var pauseAtEnd = false
        private val listeners = mutableSetOf<Player.Listener>()
        private val timeline = QueueTimeline { items }
        var seekCallbacks = 0
            private set
        private fun nextIndex() = (index + 1).takeIf { index >= 0 && it in items.indices } ?: C.INDEX_UNSET
        private fun positionInfo() = Player.PositionInfo(
            items.getOrNull(index)?.mediaId, index, items.getOrNull(index),
            items.getOrNull(index)?.mediaId, index, position, position, C.INDEX_UNSET, C.INDEX_UNSET,
        )

        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "$name:${items.getOrNull(index)?.mediaId}"
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getCurrentMediaItemIndex", "getCurrentPeriodIndex" -> index
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args!![0] as Int]
                "getNextMediaItemIndex" -> nextIndex()
                "hasNextMediaItem" -> nextIndex() != C.INDEX_UNSET
                "getCurrentTimeline" -> timeline
                "getCurrentPosition", "getContentPosition" -> position
                "getDuration" -> if (index in items.indices) DURATION_MS else C.TIME_UNSET
                "getPlaybackState" -> state
                "isPlaying" -> playWhenReady && state == Player.STATE_READY
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> { playWhenReady = args!![0] as Boolean; null }
                "play" -> { playWhenReady = true; null }
                "pause" -> { playWhenReady = false; null }
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; null }
                "getPlaybackParameters" -> parameters
                "setPlaybackSpeed" -> { parameters = PlaybackParameters(args!![0] as Float); null }
                "getRepeatMode" -> repeat
                "setRepeatMode" -> { repeat = args!![0] as Int; null }
                "getShuffleModeEnabled" -> shuffle
                "setShuffleModeEnabled" -> { shuffle = args!![0] as Boolean; null }
                "getSkipSilenceEnabled" -> skipSilence
                "setSkipSilenceEnabled" -> { skipSilence = args!![0] as Boolean; null }
                "getPauseAtEndOfMediaItems" -> pauseAtEnd
                "setPauseAtEndOfMediaItems" -> { pauseAtEnd = args!![0] as Boolean; null }
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "setMediaItems" -> {
                    check(args!!.size == 3) { "Harness expects setMediaItems(items, index, position)" }
                    @Suppress("UNCHECKED_CAST") val queue = args[0] as List<MediaItem>
                    items.clear()
                    items.addAll(queue)
                    index = args[1] as Int
                    position = args[2] as Long
                    state = Player.STATE_IDLE
                    null
                }
                "prepare" -> { state = Player.STATE_READY; null }
                "removeMediaItems" -> { items.subList(args!![0] as Int, args[1] as Int).clear(); null }
                "addMediaItems" -> {
                    @Suppress("UNCHECKED_CAST") val appended = args!![0] as List<MediaItem>
                    items.addAll(appended)
                    null
                }
                "stop" -> { state = Player.STATE_IDLE; null }
                "clearMediaItems" -> { items.clear(); index = C.INDEX_UNSET; position = 0L; null }
                "seekTo" -> {
                    val before = positionInfo()
                    if (args!!.size == 2) index = args[0] as Int
                    position = args.last() as Long
                    val after = positionInfo()
                    listeners.toList().forEach {
                        it.onPositionDiscontinuity(before, after, Player.DISCONTINUITY_REASON_SEEK)
                    }
                    seekCallbacks++
                    null
                }
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

    private class QueueTimeline(private val items: () -> List<MediaItem>) : Timeline() {
        override fun getWindowCount() = items().size
        override fun getPeriodCount() = items().size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            val item = items()[windowIndex]
            window.uid = item.mediaId
            window.mediaItem = item
            window.durationUs = DURATION_MS * 1_000L
            window.defaultPositionUs = 0L
            window.firstPeriodIndex = windowIndex
            window.lastPeriodIndex = windowIndex
            window.isSeekable = true
            return window
        }
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            val uid = items()[periodIndex].mediaId.takeIf { setIds }
            return period.set(uid, uid, periodIndex, DURATION_MS * 1_000L, 0L)
        }
        override fun getIndexOfPeriod(uid: Any) = items().indexOfFirst { it.mediaId == uid }
        override fun getUidOfPeriod(periodIndex: Int): Any = items()[periodIndex].mediaId
    }

    private fun withRig(test: (Rig) -> Unit) {
        val restore = listOf(
            preserve(AppSettings.smartFadeEnabled), preserve(AppSettings.crossfadeSeconds),
            preserve(AppSettings.playbackSpeed), preserve(AppSettings.mixViewOpen),
            preserve(SleepTimer.afterTrack), preserve(AppSettings.smartAnalysis),
            preserve(AppSettings.smartTransitionWindow), preserve(AppSettings.smartMixInProgress),
            preserve(AppSettings.smartMixBlend), preserve(AppSettings.smartMixView),
            preserve(AppSettings.smartMixPreview), preserve(AppSettings.smartMixProgress),
            preserve(AppSettings.automixStatus), preserve(AppSettings.automixDetail),
        )
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        try {
            AppSettings.smartFadeEnabled.value = true
            AppSettings.crossfadeSeconds.value = 0
            AppSettings.playbackSpeed.value = 1f
            AppSettings.mixViewOpen.value = false
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixView.value = null
            AppSettings.smartMixPreview.value = null
            SleepTimer.afterTrack.value = false
            Rig(scheduler).use(test)
        } finally {
            restore.forEach { it() }
            Dispatchers.resetMain()
        }
    }

    private fun <T> preserve(flow: MutableStateFlow<T>): () -> Unit {
        val saved = flow.value
        return { flow.value = saved }
    }

    companion object {
        private const val DURATION_MS = 200_000L

        /** Stable full-analysis recipe from Djmix27AppTest.build, with compatible grids. */
        private fun analysedTrack(id: String): TrackAnalysis {
            val duration = DURATION_MS / 1_000.0
            val beat = 0.5
            val energy = (0..duration.toInt()).map { EnergySample(it.toDouble(), 0.8) }
            return TrackAnalysis(
                status = TrackAnalysis.STATUS_READY,
                trackId = id,
                duration = duration,
                bpm = 120.0,
                beatInterval = beat,
                beatConfidence = 0.9,
                downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it <= duration }.toList(),
                firstBeat = 2.5,
                key = "C major",
                keyConfidence = 0.9,
                contentEndTime = duration - 4.0,
                energyCurve = energy,
                vocalActivityMask = energy.map { 0.0 },
                vocalProbability = 0.1,
                lowEnergyCurve = energy,
                quality = TrackAnalysis.QUALITY_FULL,
            )
        }
    }
}
