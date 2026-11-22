package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.DjEffect
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.LocalBlendAdmission
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.localBlendFitsDeckSpeedCap
import com.music.bitchord.playback.smart.planTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Synthetic policy/dual-player lifecycle coverage. No JNI, saved private analysis or PCM replay is claimed. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalBlendControllerRegressionTest {
    private fun track(id: String, bpm: Double, confidence: Double, duration: Double, voice: Float, key: String): TrackAnalysis {
        val beats = List((duration * bpm / 60).toInt()) { it * 60.0 / bpm }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, quality = TrackAnalysis.QUALITY_FULL,
            trackId = id, duration = duration, contentEndTime = duration,
            bpm = bpm, beatInterval = 60.0 / bpm, beatConfidence = confidence,
            downbeats = beats.filterIndexed { i, _ -> i % 4 == 0 },
            headBeats = BeatSide(beats.take(96), emptyList(), bpm, confidence),
            tailBeats = BeatSide(beats.takeLast(96), emptyList(), bpm, confidence),
            key = key, keyConfidence = 0.05, vocalProbability = 0.95,
            energyCurve = List(ceil(duration).toInt() + 1) { EnergySample(it.toDouble(), 1.0) },
            vocalActivityMask = List(ceil(duration).toInt() + 1) { voice.toDouble() },
            vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(ceil(duration * 20).toInt()) { voice })),
        )
    }

    private val out get() = track("route-out", 142.857142857, 0.93622, 185.021, 0.85f, "B major")
    private val inn get() = track("route-in", 130.434782608, 0.95, 188.021, 0.02f, "B minor")
    private fun plan(a: TrackAnalysis, b: TrackAnalysis, listenerSpeed: Float = 1f): TransitionPlan = planTransition(
        a, b, TransitionTrackInfo(a.trackId, 185_021L), TransitionTrackInfo(b.trackId, 188_021L),
        currentTime = 10.0, duration = a.duration, mode = CrossfadeMode.SMART, advanced = true, mixWindow = true,
        listenerSpeed = listenerSpeed.toDouble(),
    )

    @Test
    fun fullPlannerDjMixAndControllerPathFiresQualifiedClassicAtItsActualRate() = withRig { r ->
        val candidate = plan(r.a, r.b) // Includes the real DjMix annotation and depth filter.
        assertTrue(LocalBlendAdmission.MARKER in candidate.policyReasons)
        assertEquals(TransitionStyle.DJ_BLEND, candidate.transitionStyle)
        var nativeEchoRequests = 0
        val echo = echo()
        // This is the same lazy route selector used by considerSmartTransition after loopPlanFor.
        // An otherwise available scriptless Echo must never be requested for this qualified classic.
        assertNull(nativePlanAfterCrown(null, candidate, r.a, r.b, false) {
            nativeEchoRequests++; echo
        })
        assertEquals(0, nativeEchoRequests)

        r.out.position = (candidate.transitionStart * 1000).roundToLong() - 1_500L
        r.call("considerSmartTransition", 185_021L, false)
        assertEquals("ARMING", r.get("phase").toString())
        val armed = r.get("planInFlight") as TransitionPlan
        assertTrue(LocalBlendAdmission.MARKER in armed.policyReasons)
        assertEquals(TransitionStyle.DJ_BLEND, armed.transitionStyle)
        assertEquals(DjEffect.NONE, armed.djEffect)
        assertNull(r.get("loopPlanned"))
        assertNull(r.get("loopSession"))
        assertEquals(1.0952, r.into.parameters.speed.toDouble(), 0.0002)
        assertEquals(1.0952, r.get("incomingPlaybackRate") as Double, 0.0002)
        assertEquals(false, r.get("render")!!.javaClass.getDeclaredField("phaseLock").apply { isAccessible = true }.get(r.get("render")))

        r.out.position = (armed.transitionStart * 1000).roundToLong()
        r.call("driveArming")
        assertEquals("FADING", r.get("phase").toString())
        assertEquals(1.0952, r.into.parameters.speed.toDouble(), 0.0002)
        val fired = r.events().single { it.contains("\"ev\":\"mix.fire\"") }
        assertTrue(fired, fired.contains("\"technique\":\"classic\""))
        assertTrue(fired, fired.contains("\"transitionStyle\":\"DJ_BLEND\""))
        assertFalse(r.events().any { it.contains("\"stage\":\"executed\"") && it.contains("\"rung\":\"Echo\"") })
        r.call("handOff", r.out.player, r.into.player)
        assertEquals(1.0952, r.into.parameters.speed.toDouble(), 0.0002)
        // Model the completed retiring fade, rather than bypassing its audible-spare guard.
        r.out.player.volume = 0f
        r.into.player.volume = 1f
        r.call("finish")
        assertEquals("IDLE", r.get("phase").toString())
        assertEquals(false, r.get("localBlendInFlight"))
        assertEquals(1.0, r.into.parameters.speed.toDouble(), 0.00001)
    }

    @Test
    fun qualifiedArmStillObeysExistingCancellation() = withRig { r ->
        val candidate = plan(r.a, r.b)
        r.out.position = (candidate.transitionStart * 1000).roundToLong() - 1_500L
        r.call("considerSmartTransition", 185_021L, false)
        assertEquals("ARMING", r.get("phase").toString())
        r.call("cancelArming", "scrub-cancelled")
        assertEquals("IDLE", r.get("phase").toString())
        assertFalse(r.events().any { it.contains("\"ev\":\"mix.fire\"") })
    }

    @Test
    fun controllerReevaluatesLocalAdmissionAtCurrentListenerSpeed() = withRig { r ->
        AppSettings.playbackSpeed.value = 3f
        r.call("considerSmartTransition", 185_021L, false)
        val considered = r.get("lastSmartPlan") as TransitionPlan
        assertFalse(considered.toString(), LocalBlendAdmission.MARKER in considered.policyReasons)
        assertTrue(considered.toString(), "local-blend-refused:listener-speed-cap" in considered.policyReasons)
        assertTrue(considered.toString(), considered.reason.contains("decline-to-mix"))
        assertEquals("IDLE", r.get("phase").toString())
    }

    @Test
    fun beginLastDefenseRejectsAnAlreadyPlannedRateThatNoLongerFits() = withRig { r ->
        val candidate = plan(r.a, r.b)
        assertTrue(LocalBlendAdmission.MARKER in candidate.policyReasons)
        AppSettings.playbackSpeed.value = 3f
        val render = r.call("renderFor", candidate, true)
        val began = r.call(
            "begin",
            candidate.fadeMs,
            (candidate.transitionEnd * 1000).roundToLong(),
            true,
            (candidate.incomingCueTime * 1000).roundToLong(),
            candidate.incomingPlaybackRate,
            candidate.outgoingPlaybackRate,
            (candidate.beatSeconds * 1000).roundToLong(),
            render,
            candidate,
        ) as Boolean
        assertFalse(began)
        assertEquals("IDLE", r.get("phase").toString())
        assertFalse(r.events().any { it.contains("\"ev\":\"mix.fire\"") })
    }

    @Test
    fun armedLocalBlendCancelsRatherThanForcingWhenListenerSpeedChanges() = withRig { r ->
        val candidate = plan(r.a, r.b)
        r.out.position = (candidate.transitionStart * 1000).roundToLong() - 1_500L
        r.call("considerSmartTransition", 185_021L, false)
        assertEquals("ARMING", r.get("phase").toString())
        assertTrue(r.get("localBlendInFlight") as Boolean)

        AppSettings.playbackSpeed.value = 3f
        r.out.position = (candidate.transitionStart * 1000).roundToLong()
        r.call("driveArming")

        assertEquals("IDLE", r.get("phase").toString())
        assertFalse(r.get("localBlendInFlight") as Boolean)
        assertFalse(r.events().any { it.contains("\"ev\":\"mix.fire\"") })
    }

    @Test
    fun genuineCrownAndShownNativeFamilyKeepPriorityWhileUnqualifiedClassicStillUsesBridge() {
        val a = out
        val b = inn
        val candidate = plan(a, b)
        val crown = crown()
        assertTrue(LoopMix.qualifyCrown(crown).accepted)
        var calls = 0
        assertSame(crown, nativePlanAfterCrown(crown, candidate, a, b, false) { calls++; echo() })
        assertEquals(0, calls)
        val retainedEcho = echo()
        assertSame(retainedEcho, nativePlanAfterCrown(null, candidate, a, b, true) { calls++; retainedEcho })
        assertEquals(1, calls)
        val unqualified = candidate.copy(policyReasons = emptyList())
        assertSame(retainedEcho, nativePlanAfterCrown(null, unqualified, a, b, false) { calls++; retainedEcho })
        assertEquals(2, calls)

        // Current captured lyric cues participate in controller re-admission.
        val sungOut = listOf(LyricCue(candidate.transitionStart, candidate.transitionEnd, "outgoing lead"))
        val sungIn = listOf(LyricCue(candidate.incomingCueTime,
            candidate.incomingCueTime + candidate.fadeSeconds * candidate.incomingPlaybackRate, "incoming lead"))
        assertSame(retainedEcho, nativePlanAfterCrown(null, candidate, a, b, false, sungOut, sungIn) { calls++; retainedEcho })
        assertEquals(3, calls)
    }

    @Test
    fun admittedRateUsesExistingCompositeDeckSpeedCap() {
        assertEquals(1.0952f, deckSpeedForAdmittedBlend(1f, 1.0952), 0.0001f)
        val boundarySpeed = 3f / 1.25f
        assertTrue(localBlendFitsDeckSpeedCap(boundarySpeed.toDouble(), 1.25))
        assertEquals(3f, deckSpeedForAdmittedBlend(boundarySpeed, 1.25), 0.0001f)
        assertFalse(localBlendFitsDeckSpeedCap(3.0, 1.25))
        assertFalse(localBlendFitsDeckSpeedCap(3.0, 99.0))
        assertEquals(1.25f, deckSpeedForAdmittedBlend(1.25f, Double.NaN), 0.0001f)
    }

    @Test
    fun missingEvidenceLeavesQueueAndSongsUntouchedWithoutArmingACrossfade() = withRig(
        TrackAnalysis(trackId = "unknown-out", duration = 185.021),
        TrackAnalysis(trackId = "unknown-in", duration = 188.021),
    ) { r ->
        r.out.position = 180_000L
        r.out.player.volume = 1f
        r.call("considerSmartTransition", 185_021L, false)
        val considered = r.get("lastSmartPlan") as TransitionPlan
        assertEquals(TransitionStyle.NATURAL, considered.transitionStyle)
        assertEquals("IDLE", r.get("phase").toString())
        assertNull(r.get("plannedTransition"))
        assertNull(r.get("heldAtEnd"))
        assertFalse(r.get("holdWanted") as Boolean)
        assertEquals(0, r.into.player.mediaItemCount)
        assertEquals(2, r.out.player.mediaItemCount)
        assertEquals(180_000L, r.out.player.currentPosition)
        assertEquals(1f, r.out.player.volume)
        assertTrue(r.out.player.playWhenReady)
        assertEquals(PlaybackParameters.DEFAULT, r.out.parameters)
        assertFalse(r.events().any { it.contains("\"ev\":\"mix.fire\"") })
        assertTrue(r.events().any { it.contains("\"technique\":\"natural\"") })
    }

    @Test
    fun explicitNativeFailureStillPermitsLastResortRecoveryCrossfade() = withRig(
        TrackAnalysis(trackId = "recovery-out", duration = 185.021),
        TrackAnalysis(trackId = "recovery-in", duration = 188.021),
    ) { r ->
        val countdown = r.get("countdownStabilizer") as com.music.bitchord.playback.smart.CountdownStabilizer
        val pair = "${r.a.trackId}->${r.b.trackId}"
        r.call("ensureOpportunity", r.a.trackId, r.b.trackId)
        countdown.consider(com.music.bitchord.playback.smart.CountdownStabilizer.Candidate(
            pair, 0L, com.music.bitchord.playback.smart.CountdownStabilizer.Family.NATIVE_BRIDGE,
            "Echo", 180_000L, 185_021L), 178_000L)
        countdown.onRungFailed(pair, 0L, com.music.bitchord.playback.smart.CountdownStabilizer.Family.NATIVE_BRIDGE)
        r.set("loopSuppressedPair", "${r.a.trackId}->${r.b.trackId}")
        r.set("loopSuppressedUntil", 100_000L)
        r.out.position = 178_000L
        r.call("considerSmartTransition", 185_021L, false)
        val considered = r.get("lastSmartPlan") as TransitionPlan
        assertEquals(TransitionStyle.DJ_FILTER, considered.transitionStyle) // bounded near-deadline recovery
        assertTrue(considered.fadeSeconds > 0.0)
        assertEquals(DjEffect.NONE, considered.djEffect)
        assertEquals(0.0, considered.echoSeconds, 0.0)
        assertEquals("ARMING", r.get("phase").toString())
    }

    @Test
    fun aColdRejectedNativeCandidateDoesNotAuthorizeAFallbackCrossfade() = withRig(
        TrackAnalysis(trackId = "cold-out", duration = 185.021),
        TrackAnalysis(trackId = "cold-in", duration = 188.021),
    ) { r ->
        r.set("loopSuppressedPair", "${r.a.trackId}->${r.b.trackId}")
        r.set("loopSuppressedUntil", 100_000L)
        r.out.position = 178_000L
        r.call("considerSmartTransition", 185_021L, false)
        assertEquals(TransitionStyle.NATURAL, (r.get("lastSmartPlan") as TransitionPlan).transitionStyle)
        assertEquals("IDLE", r.get("phase").toString())
        assertEquals(0, r.into.player.mediaItemCount)
    }

    private class Rig(val dir: File, val a: TrackAnalysis, val b: TrackAnalysis) : AutoCloseable {
        val out = Deck(listOf(a.trackId, b.trackId).map { MediaItem.Builder().setMediaId(it).build() }, 185_021L)
        val into = Deck(emptyList(), 188_021L)
        private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        private var swapped = false
        val controller = CrossfadeController(scope = scope,
            active = { if (swapped) into.player else out.player }, standby = { if (swapped) out.player else into.player },
            onHandoff = { _, _ -> swapped = true }, analysisFor = { if (it.mediaId == a.trackId) a else b }, nowMs = { 10_000L })
        fun get(name: String): Any? = CrossfadeController::class.java.getDeclaredField(name).apply { isAccessible = true }.get(controller)
        fun set(name: String, value: Any?) { CrossfadeController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value) }
        fun call(name: String, vararg args: Any?): Any? = CrossfadeController::class.java.declaredMethods
            .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(controller, *args)
        fun events(): List<String> = File(dir, "automix-telemetry.jsonl").takeIf { it.exists() }?.readLines().orEmpty()
        override fun close() { controller.release(); scope.cancel() }
    }

    private class Deck(initial: List<MediaItem>, private val duration: Long) {
        private val items = initial.toMutableList()
        private var index = if (items.isEmpty()) C.INDEX_UNSET else 0
        private var state = if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
        private var volume = 0f
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

    private fun withRig(a: TrackAnalysis = out, b: TrackAnalysis = inn, test: (Rig) -> Unit) {
        val telemetryDir = AutomixTelemetry::class.java.getDeclaredField("dir").apply { isAccessible = true }
        val savedDir = telemetryDir.get(AutomixTelemetry)
        val restore = listOf(preserve(AppSettings.smartFadeEnabled), preserve(AppSettings.playbackSpeed),
            preserve(AppSettings.crossfadeSeconds), preserve(AppSettings.smartTransitionWindow),
            preserve(AppSettings.smartMixInProgress), preserve(AppSettings.smartMixBlend), preserve(AppSettings.smartMixView),
            preserve(AppSettings.smartMixPreview), preserve(AppSettings.smartMixProgress),
            preserve(AppSettings.automixStatus), preserve(AppSettings.automixDetail))
        val dir = Files.createTempDirectory("local-blend-controller-").toFile()
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            telemetryDir.set(AutomixTelemetry, dir)
            AppSettings.smartFadeEnabled.value = true
            AppSettings.playbackSpeed.value = 1f
            AppSettings.crossfadeSeconds.value = 4
            Rig(dir, a, b).use(test)
        } finally {
            telemetryDir.set(AutomixTelemetry, savedDir)
            restore.forEach { it() }
            Dispatchers.resetMain()
            dir.deleteRecursively()
        }
    }

    private fun <T> preserve(flow: MutableStateFlow<T>): () -> Unit {
        val saved = flow.value
        return { flow.value = saved }
    }

    private fun crown() = LoopMix.Plan(
        out = LoopMix.Pick(160.0, 168.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
        inn = LoopMix.Pick(8.0, 16.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
        fold = 1.0, enterBars = 4, rampBars = 4, exitBars = 4, totalBars = 12,
    )
    private fun echo() = crown().copy(outLoops = false, inLoops = false, rung = "Echo", echoTail = true)
}
