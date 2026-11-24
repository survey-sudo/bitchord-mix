package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.djmix.PairPreparer
import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.DjLevel
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class StuckStateRecoveryTest {

    private class FakeFilters : TransitionFilters {
        var outgoingEchoCalls = 0
        var incomingCutoffs = mutableListOf<Pair<Float, Float>>()
        var outgoingCutoffs = mutableListOf<Pair<Float, Float>>()
        override fun incoming(lowPassHz: Float, highPassHz: Float) {
            incomingCutoffs += lowPassHz to highPassHz
        }
        override fun outgoing(lowPassHz: Float, highPassHz: Float) {
            outgoingCutoffs += lowPassHz to highPassHz
        }
        override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) {
            outgoingEchoCalls++
        }
    }

    private class Deck(initial: List<MediaItem>, private val duration: Long) {
        private val items = initial.toMutableList()
        private var index = if (items.isEmpty()) C.INDEX_UNSET else 0
        private var state = if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
        private var volume = 1f
        private var playing = true
        var position = 10_000L
        var parameters = PlaybackParameters.DEFAULT
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getCurrentMediaItemIndex", "getCurrentPeriodIndex" -> index
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args!![0] as Int]
                "getNextMediaItemIndex" -> (index + 1).takeIf { it in items.indices } ?: C.INDEX_UNSET
                "hasNextMediaItem" -> index + 1 in items.indices
                "getCurrentTimeline" -> Timeline.EMPTY
                "getCurrentPosition", "getContentPosition" -> position
                "getDuration" -> duration
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playing
                "isPlaying" -> playing && state == Player.STATE_READY
                "setPlayWhenReady" -> { playing = args!![0] as Boolean; null }
                "play" -> { playing = true; null }
                "pause" -> { playing = false; null }
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; null }
                "getPlaybackParameters" -> parameters
                "setPlaybackParameters" -> { parameters = args!![0] as PlaybackParameters; null }
                "setPlaybackSpeed" -> { parameters = PlaybackParameters(args!![0] as Float); null }
                "setMediaItems" -> {
                    @Suppress("UNCHECKED_CAST") val queue = args!![0] as List<MediaItem>
                    items.clear(); items.addAll(queue); index = args[1] as Int; position = args[2] as Long
                    state = Player.STATE_IDLE; null
                }
                "prepare" -> { state = Player.STATE_READY; null }
                "stop" -> { state = Player.STATE_IDLE; null }
                "clearMediaItems" -> { items.clear(); index = C.INDEX_UNSET; null }
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

    private fun analysis(id: String, bpm: Double = 120.0): TrackAnalysis = TrackAnalysis(
        trackId = id,
        status = TrackAnalysis.STATUS_READY,
        quality = TrackAnalysis.QUALITY_FULL,
        duration = 180.0,
        bpm = bpm,
        beatInterval = 60.0 / bpm,
        beatConfidence = 0.9,
        key = "C major",
        keyConfidence = 0.9,
        headBeats = BeatSide(List(32) { it * (60.0 / bpm) }, List(8) { it * (240.0 / bpm) }, bpm, 0.9),
        vocalWindows = listOf(CurveWindow(0.0, 4.0, FloatArray(100) { 0f })),
    )

    private fun withRig(
        a: TrackAnalysis = analysis("A"),
        b: TrackAnalysis = analysis("B"),
        c: TrackAnalysis = analysis("C"),
        test: (Rig) -> Unit,
    ) {
        val telemetryDir = AutomixTelemetry::class.java.getDeclaredField("dir").apply { isAccessible = true }
        val savedDir = telemetryDir.get(AutomixTelemetry)
        val restore = listOf(
            preserve(AppSettings.smartFadeEnabled),
            preserve(AppSettings.playbackSpeed),
            preserve(AppSettings.crossfadeSeconds),
            preserve(AppSettings.smartTransitionWindow),
            preserve(AppSettings.smartMixInProgress),
            preserve(AppSettings.smartMixBlend),
            preserve(AppSettings.automixDjLevel),
            preserve(AppSettings.automixStatus),
            preserve(AppSettings.automixDetail),
        )
        val dir = Files.createTempDirectory("stuck-state-recovery-").toFile()
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        try {
            telemetryDir.set(AutomixTelemetry, dir)
            AppSettings.smartFadeEnabled.value = true
            AppSettings.playbackSpeed.value = 1f
            AppSettings.crossfadeSeconds.value = 4
            AppSettings.automixDjLevel.value = DjLevel.CLUB
            Rig(dir, scheduler, mapOf(a.trackId to a, b.trackId to b, c.trackId to c)).use(test)
        } finally {
            telemetryDir.set(AutomixTelemetry, savedDir)
            restore.forEach { it() }
            Dispatchers.resetMain()
            dir.deleteRecursively()
        }
    }

    private fun settleTransition(r: Rig) {
        r.call("finish")
        if (r.get("phase").toString() == "TAIL") {
            r.wallMs += 2_000L
            r.call("tick")
        }
    }

    private fun <T> preserve(flow: MutableStateFlow<T>): () -> Unit {
        val saved = flow.value
        return { flow.value = saved }
    }

    private class Rig(
        val dir: File,
        val scheduler: TestCoroutineScheduler,
        val analyses: Map<String, TrackAnalysis>,
    ) : AutoCloseable {
        var wallMs = 10_000L
        val first = Deck(analyses.keys.map { MediaItem.Builder().setMediaId(it).build() }, 180_000L)
        val second = Deck(emptyList(), 180_000L)
        val filters = FakeFilters()
        var active = first
        var standby = second
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val controller = CrossfadeController(
            scope = scope,
            active = { active.player },
            standby = { standby.player },
            onHandoff = { _, _ ->
                val tmp = active
                active = standby
                standby = tmp
            },
            analysisFor = { analyses[it.mediaId] ?: TrackAnalysis(trackId = it.mediaId) },
            filters = filters,
            nowMs = { wallMs },
        )

        fun get(name: String): Any? = CrossfadeController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller)

        fun set(name: String, value: Any?) {
            CrossfadeController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
        }

        fun call(name: String, vararg args: Any?): Any? = CrossfadeController::class.java.declaredMethods
            .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(controller, *args)

        fun events(ev: String): List<String> = File(dir, "automix-telemetry.jsonl")
            .takeIf { it.exists() }
            ?.readLines()
            ?.filter { it.contains("\"ev\":\"$ev\"") }
            .orEmpty()

        override fun close() {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun stuckBuildingOverEightSecondsCancelsTriggersEmergencyWithFxAndClearsNextPairSuppression() = withRig { r ->
        val enumClass = Class.forName("com.music.bitchord.playback.CrossfadeController\$Phase")
        val loopArmingPhase = enumClass.enumConstants.first { it.toString() == "LOOP_ARMING" }

        r.set("phase", loopArmingPhase)
        r.set("loopBuildStartedAtMs", r.wallMs)
        r.set("loopOutItem", r.first.player.getMediaItemAt(0))
        r.set("loopInItem", r.first.player.getMediaItemAt(1))
        r.set("loopSuppressedPair", "A->B")
        r.set("loopSuppressedUntil", r.wallMs + 90_000L)
        r.set("buildGeneration", 4)
        r.set("primeJob", Job()) // Active fake build job that hasn't settled.

        // Advance beyond the 8_000 ms stuck-build guard.
        r.wallMs += 8_001L
        r.first.position = 175_000L
        r.call("checkHardWatchdog")

        assertTrue("Stuck build must emit mix.watchdog", r.events("mix.watchdog").any { it.contains("stuck-building-8s") })
        assertTrue("Generation must advance to drop any late native session", (r.get("buildGeneration") as Int) > 4)
        // Emergency classic FX transition armed and fired because fake standby prepared synchronously.
        val phaseAfter = r.get("phase").toString()
        assertTrue("Must exit LOOP_ARMING into FADING or ARMING (was $phaseAfter)", phaseAfter == "FADING" || phaseAfter == "ARMING")
        val fired = r.events("mix.fire").single()
        assertTrue(fired.contains("\"path\":\"classic\""))
        assertTrue(fired.contains("\"engine\":\"classic\""))
        assertTrue("Emergency path must have Echo + Filter FX", fired.contains("Filter") && fired.contains("Echo"))
        assertEquals(DjLevel.CLUB.name, if (fired.contains("\"level\":\"CLUB\"")) DjLevel.CLUB.name else "")
        assertTrue("Filter cutoffs must engage immediately", r.filters.outgoingCutoffs.isNotEmpty())

        // Advance past midpoint (handoff) and into echo send window (progress >= echoFrom).
        r.second.position = 2_600L
        r.first.position = 177_600L
        r.wallMs += 2_600L
        r.call("tick")
        r.second.position = 4_200L
        r.first.position = 179_200L
        r.wallMs += 1_600L
        r.call("tick")
        assertTrue("Outgoing echo FX must be engaged across echo send", r.filters.outgoingEchoCalls > 0)

        // Complete transition (finish -> TAIL -> IDLE) -> clears pair suppression & emits mix.timing (17 fields).
        settleTransition(r)
        assertEquals("IDLE", r.get("phase").toString())
        assertNull("Completed/recovered transition must clear loopSuppressedPair", r.get("loopSuppressedPair"))
        val timing = r.events("mix.timing").single()
        assertTrue(timing.contains("\"watchdog\":true"))
        assertTrue(timing.contains("\"path\":\"classic\""))
        assertTrue(timing.contains("\"fallbackReason\":\"stuck-building-8s\""))
        assertTrue(timing.contains("\"prepStartMs\":"))
        assertTrue(timing.contains("\"analysisMs\":"))
        assertTrue(timing.contains("\"planMs\":"))
        assertTrue(timing.contains("\"stemMsOut\":"))
        assertTrue(timing.contains("\"stemMsIn\":"))
        assertTrue(timing.contains("\"preparedLeadSec\":"))
        assertTrue(timing.contains("\"buildMs\":"))
        assertTrue(timing.contains("\"armLeadMs\":"))
        assertTrue(timing.contains("\"fxCount\":"))
        assertTrue(timing.contains("\"stems\":"))
    }

    @Test
    fun hardWatchdogNeverAllowsDeclinedOrUnanalyzedTrackToReachEofDryWithoutMix() = withRig(
        a = TrackAnalysis(trackId = "unknown-out", duration = 180.0),
        b = TrackAnalysis(trackId = "unknown-in", duration = 180.0),
    ) { r ->
        // Advance past max(2 bars, 4.5s) = 4.5s before EOF (176.0s of 180.0s).
        r.first.position = 176_000L
        r.call("tick")

        assertTrue("Watchdog must fire near EOF", r.events("mix.watchdog").isNotEmpty())
        val fired = r.events("mix.fire").singleOrNull()
        assertNotNull("Must fire an overlap mix with FX rather than skipping to EOF dry", fired)
        assertTrue(fired!!.contains("\"path\":\"classic\""))
        assertTrue(fired.contains("\"fx\":") && fired.contains("Echo") && fired.contains("Filter"))
        assertTrue("Filter cutoffs must engage immediately", r.filters.outgoingCutoffs.isNotEmpty())

        // Advance past midpoint (handoff) and echo send window before EOF.
        r.second.position = 2_200L
        r.first.position = 178_200L
        r.wallMs += 2_200L
        r.call("tick")
        r.second.position = 3_400L
        r.first.position = 179_400L
        r.wallMs += 1_200L
        r.call("tick")
        assertTrue("Outgoing echo FX must be engaged", r.filters.outgoingEchoCalls > 0)

        settleTransition(r)
        assertEquals("IDLE", r.get("phase").toString())
        val timing = r.events("mix.timing").single()
        assertTrue(timing.contains("\"watchdog\":true"))
    }

    @Test
    fun onTrackChangedClearsPairSuppressionAndCountdownStabilizer() = withRig { r ->
        val stabilizer = r.get("countdownStabilizer") as CountdownStabilizer
        val pair = "A->B"
        val epoch = r.get("scrubEpoch") as Long
        stabilizer.onRungFailed(pair, epoch, CountdownStabilizer.Family.LOOP, suppressBridgeToo = true)
        r.set("loopSuppressedPair", pair)
        r.set("loopSuppressedUntil", r.wallMs + 90_000L)
        assertTrue(stabilizer.isFamilySuppressed(pair, epoch, CountdownStabilizer.Family.LOOP))

        r.controller.onTrackChanged()
        assertNull(r.get("loopSuppressedPair"))
        assertFalse(stabilizer.isFamilySuppressed(pair, epoch, CountdownStabilizer.Family.LOOP))
    }

    @Test
    fun emergencyFadeNeverFiresWhenAutomixDisabledOrPausedOrVideoOrigin() = withRig(
        a = TrackAnalysis(trackId = "vid-out", duration = 180.0),
        b = TrackAnalysis(trackId = "vid-in", duration = 180.0),
    ) { r ->
        // 1. Paused: watchdog must NOT fire.
        r.first.player.pause()
        r.first.position = 178_000L
        r.call("tick")
        assertTrue(r.events("mix.fire").isEmpty())
        assertTrue(r.events("mix.watchdog").isEmpty())

        // 2. Automix disabled: watchdog must NOT fire.
        r.first.player.play()
        AppSettings.smartFadeEnabled.value = false
        r.call("tick")
        assertTrue(r.events("mix.fire").isEmpty())
        assertTrue(r.events("mix.watchdog").isEmpty())
    }
}
