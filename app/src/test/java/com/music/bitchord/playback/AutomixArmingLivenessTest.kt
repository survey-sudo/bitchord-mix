package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.TrackAnalysis
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class AutomixControllerRig : AutoCloseable {
    companion object { const val DURATION_MS = 200_000L }

    class Deck(initial: List<MediaItem>, var position: Long, var playWhenReady: Boolean) {
        val items = initial.toMutableList()
        var state = if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
        var prepareState = Player.STATE_READY
        var volume = 1f
        var parameters = PlaybackParameters.DEFAULT
        var repeat = Player.REPEAT_MODE_OFF
        var shuffle = false
        var skipSilence = false
        var pauseAtEnd = false
        private var index = if (items.isEmpty()) C.INDEX_UNSET else 0
        private val listeners = Collections.synchronizedSet(mutableSetOf<Player.Listener>())
        private val timeline = object : Timeline() {
            override fun getWindowCount() = items.size
            override fun getPeriodCount() = items.size
            override fun getWindow(i: Int, w: Window, projection: Long): Window {
                w.uid = items[i].mediaId; w.mediaItem = items[i]; w.durationUs = DURATION_MS * 1000
                w.firstPeriodIndex = i; w.lastPeriodIndex = i; w.isSeekable = true; return w
            }
            override fun getPeriod(i: Int, p: Period, setIds: Boolean): Period =
                p.set(items[i].mediaId, items[i].mediaId, i, DURATION_MS * 1000, 0)
            override fun getIndexOfPeriod(uid: Any) = items.indexOfFirst { it.mediaId == uid }
            override fun getUidOfPeriod(i: Int): Any = items[i].mediaId
        }
        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getCurrentMediaItemIndex", "getCurrentPeriodIndex" -> index
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args!![0] as Int]
                "getNextMediaItemIndex" -> (index + 1).takeIf { it < items.size } ?: C.INDEX_UNSET
                "hasNextMediaItem" -> index >= 0 && index + 1 < items.size
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
                "setMediaItems" -> { items.clear(); items.addAll(args!![0] as List<MediaItem>); index = args[1] as Int; position = args[2] as Long; state = Player.STATE_IDLE; null }
                "prepare" -> { state = prepareState; null }
                "stop" -> { state = Player.STATE_IDLE; null }
                "clearMediaItems" -> { items.clear(); index = C.INDEX_UNSET; null }
                "removeMediaItems" -> { items.subList(args!![0] as Int, args[1] as Int).clear(); null }
                "addMediaItems" -> { items.addAll(args!![0] as List<MediaItem>); null }
                "release" -> null
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
        fun ids() = items.map { it.mediaId }
        fun emitError() = listeners.toList().forEach {
            it.onPlayerError(androidx.media3.common.PlaybackException("test", null, 0))
        }
    }

    class Filters : TransitionFilters {
        var opens = 0
        override fun open() { opens++ }
        override fun incoming(lowPassHz: Float, highPassHz: Float) = Unit
        override fun outgoing(lowPassHz: Float, highPassHz: Float) = Unit
    }

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val items = listOf("A", "B", "C").map { MediaItem.Builder().setMediaId(it).build() }
    private val analyses = items.associate { it.mediaId to analysis(it.mediaId) }
    val out = Deck(items, 10_000L, true)
    val standby = Deck(emptyList(), 0L, false)
    val filters = Filters()
    val controller = CrossfadeController(
        scope = scope,
        active = { activeDeck.player },
        standby = { spareDeck.player },
        onHandoff = { outgoing, incoming ->
            check(outgoing === activeDeck.player)
            check(incoming === spareDeck.player)
            val old = activeDeck; activeDeck = spareDeck; spareDeck = old
            handoffs += "${outgoing.currentMediaItem?.mediaId}->${incoming.currentMediaItem?.mediaId}"
        },
        analysisFor = { analyses.getValue(it.mediaId) },
        filters = filters,
    )
    private var activeDeck = out
    private var spareDeck = standby
    val handoffs = mutableListOf<String>()
    var startMs = 0L
    private val telemetryDir = Files.createTempDirectory("automix-arm").toFile()
    private val telemetryField = AutomixTelemetry::class.java.getDeclaredField("dir").apply { isAccessible = true }
    private val previousTelemetryDir = telemetryField.get(AutomixTelemetry)
    val phase: String get() = field("phase").toString()

    init { telemetryField.set(AutomixTelemetry, telemetryDir) }
    private fun field(name: String): Any? = CrossfadeController::class.java.getDeclaredField(name).apply { isAccessible = true }.get(controller)
    fun setPrivate(name: String, value: Any) = CrossfadeController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
    fun start() { controller.start(); pulse() }
    fun pulse() { scheduler.advanceTimeBy(1_000L); scheduler.runCurrent() }
    fun arm() {
        start(); startMs = requireNotNull(controller.plannedTransitionFor("A")); out.position = startMs - 1L; pulse()
        assertEquals("ARMING", phase)
    }
    fun telemetry(event: String): Boolean = File(telemetryDir, "automix-telemetry.jsonl").takeIf { it.exists() }?.readLines()?.any { it.contains("\"ev\":\"$event\"") } == true
    override fun close() { controller.release(); scope.cancel(); telemetryField.set(AutomixTelemetry, previousTelemetryDir); telemetryDir.deleteRecursively() }

    private fun analysis(id: String): TrackAnalysis {
        val duration = DURATION_MS / 1000.0
        val energy = (0..duration.toInt()).map { EnergySample(it.toDouble(), 0.8) }
        return TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = id, duration = duration, bpm = 120.0, beatInterval = 0.5,
            beatConfidence = 0.9, downbeats = generateSequence(0.5) { it + 2.0 }.takeWhile { it < duration }.toList(), firstBeat = 0.5,
            contentEndTime = duration - 4.0, energyCurve = energy, vocalActivityMask = energy.map { 0.0 }, quality = TrackAnalysis.QUALITY_FULL)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AutomixArmingLivenessTest {
    @Test fun disablingSmartFadeCancelsArmingAndPreservesQueue() = withRig { rig ->
        rig.arm(); val position = rig.out.position
        val old = AppSettings.smartFadeEnabled.value
        AppSettings.smartFadeEnabled.value = false
        rig.pulse()
        AppSettings.smartFadeEnabled.value = old
        assertEquals("IDLE", rig.phase); assertEquals(position, rig.out.position); assertEquals(listOf("A", "B", "C"), rig.out.ids()); assertTrue(rig.filters.opens > 0)
        rig.pulse(); assertTrue(rig.controller.plannedTransitionFor("A") != null)
    }

    @Test fun pausingOutgoingCancelsArmingWithoutAutoResume() = withRig { rig ->
        rig.arm(); rig.out.playWhenReady = false; rig.pulse()
        assertEquals("IDLE", rig.phase); assertFalse(rig.out.playWhenReady); assertEquals(listOf("A", "B", "C"), rig.out.ids())
        rig.out.playWhenReady = true; rig.pulse(); assertTrue(rig.controller.plannedTransitionFor("A") != null)
    }

    @Test fun expiredBufferingArmEmitsDeathAndReturnsIdle() = withRig { rig ->
        rig.standby.prepareState = Player.STATE_BUFFERING; rig.arm(); rig.setPrivate("armGraceUsed", true); rig.setPrivate("armDeadline", -1L); rig.pulse()
        assertEquals("IDLE", rig.phase); assertEquals(listOf("A", "B", "C"), rig.out.ids()); assertTrue(rig.telemetry("mix.arm.death"))
    }

    @Test fun transientErrorDuringFadingKeepsFreezeGuard() = withRig { rig ->
        rig.arm(); rig.out.position = rig.startMs + 1L; rig.pulse(); assertEquals("FADING", rig.phase); rig.out.emitError()
        assertEquals("FADING", rig.phase); assertTrue(rig.telemetry("mix.freeze.onInterruption"))
    }

    private fun withRig(block: (AutomixControllerRig) -> Unit) {
        val old = AppSettings.smartFadeEnabled.value; AppSettings.smartFadeEnabled.value = true
        try { AutomixControllerRig().use { block(it) } } finally { AppSettings.smartFadeEnabled.value = old }
    }
}
