package com.music.bitchord.playback

import android.net.LoopFixtureUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.loopmix.LoopBuildControl
import com.music.bitchord.playback.loopmix.LoopBuildRequest
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.loopmix.loopPhysicalBounds
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.tryTransitionModel
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil
import kotlin.math.roundToLong
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
import org.junit.Assert.*
import org.junit.Test

/**
 * Production controller, fake Media3 players/PCM/model/builder, real Default worker.
 * No JNI, Android decoder, model inference, phone, or audible-success claim.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomixLoopSpliceTest {
    @Test fun busy8400msPrimeLatchesAllThreeOptionalCallbacksOff() = withRig(compatible = true) { r ->
        val probes = AtomicInteger()
        r.busy = { probes.getAndIncrement() == 0 }
        r.warm = true
        val plan = loopPlan(100.0)
        r.out.position = 91_600L
        r.builder = { request ->
            assertNull(request.separateStem)
            assertNull(request.separateVocalStem)
            assertNull(request.separateVocalPair)
            val b = loopPhysicalBounds(request.plan, request.sampleRate, request.script, request.outAnalysis, request.inAnalysis)
            assertEquals(99.0, b.outFrom, 0.0)
            assertEquals(plan.out.loopStart, request.plan.out.loopStart, 0.0)
            assertNotNull(request.decode(LoopMix.Side.OUT, b.outFrom, b.outTo))
            assertNotNull(request.decode(LoopMix.Side.IN, b.inFrom, b.inTo))
            fakeSession(request)
        }
        r.prime(plan)
        r.awaitBuild()
        assertEquals("one launch probe, latched even when it becomes idle", 1, probes.get())
        assertEquals(0, r.modelCalls.get())
        assertNotNull(r.get("primedSession"))
        assertFalse(r.lines("mix.loop.fallback").any { it.contains("build-missed-splice") })
        assertTrue(r.lines("mix.loop.build.launch").single().contains("\"skipStemAtLaunch\":true"))
    }

    @Test fun busyThirtySecondPrimeRetainsStemsButCallbackTime8400msBudgetSkips() = withRig(compatible = true) { r ->
        r.busy = { true }
        val plan = loopPlan(100.0)
        r.out.position = 70_000L
        r.builder = { request ->
            assertNotNull(request.separateStem)
            assertNotNull(request.separateVocalStem)
            assertNotNull(request.separateVocalPair)
            val dry = floatArrayOf(0.25f, -0.25f)
            // Far prime invokes the existing optional path normally.
            assertNotNull(request.separateVocalStem!!(LoopMix.Side.OUT, dry, dry, 1_000))
            assertEquals(1, r.modelCalls.get())
            r.now.addAndGet(21_600L) // Same generation's wall deadline now has only 8.4s.
            assertNull(request.separateStem!!(LoopMix.Side.OUT, dry, dry, 1_000, true))
            assertNull(request.separateVocalStem!!(LoopMix.Side.OUT, dry, dry, 1_000))
            assertNull(request.separateVocalPair!!(LoopMix.Side.IN, dry, dry, 1_000))
            assertArrayEquals(floatArrayOf(0.25f, -0.25f), dry, 0f)
            fakeSession(request)
        }
        r.prime(plan)
        r.awaitBuild()
        assertEquals(1, r.modelCalls.get())
        val result = r.lines("mix.loop.build.result").single()
        assertTrue(result.contains("\"outStemSkip\":\"busy-under-budget\""))
        assertTrue(result.contains("\"inStemSkip\":\"busy-under-budget\""))
    }

    @Test fun coldNative8400msAdoptsOneClassicThisTickWithoutNinetySecondSuppression() = withRig { r ->
        val native = r.nativePlan()
        r.out.position = (native.out.loopStart * 1_000).roundToLong() - 8_400L
        r.nextReady = false
        r.start()
        assertEquals("ARMING", r.phase())
        assertEquals(1, r.into.prepareCalls)
        assertEquals(listOf("B"), r.armed)
        assertTrue("8.4s must never first launch and then abort a new prime", r.requests.isEmpty())
        r.assertSoftAttemptGuard()
        r.nextReady = true
        repeat(4) { r.pulse(250L) }
        assertEquals(1, r.into.prepareCalls)
        assertEquals(1, r.armed.size)
        assertTrue(r.requests.isEmpty())
        assertFalse(r.phase().startsWith("LOOP"))
        val classic = r.get("planInFlight") as TransitionPlan
        r.out.position = maxOf(r.out.position, (classic.transitionStart * 1_000).roundToLong() + 1L)
        r.pulse(250L)
        assertEquals("FADING", r.phase())
        assertTrue(r.out.position < r.analysis("A").contentEndTime * 1_000)
        assertEquals(1, r.armed.size)
        val fb = r.lines("mix.loop.fallback").single()
        assertTrue(fb.contains("\"kind\":\"SOFT_READINESS\""))
        assertTrue(fb.contains("\"planAgeValid\":true"))
        assertTrue(fb.contains("\"msSincePlan\":"))
    }

    @Test fun identicalMediaIdsInANaturalLaterQueueOccurrenceAreEligibleBefore90s() = withRig { r ->
        val native = r.nativePlan()
        r.out.position = (native.out.loopStart * 1_000).roundToLong() - 8_400L
        r.nextReady = false
        r.start()
        r.assertSoftAttemptGuard()
        val firstAttempt = r.get("loopAttemptId") as Long
        r.nextReady = true
        r.pulse(1_000L)
        r.completeClassic()
        assertEquals("IDLE", r.phase())
        assertEquals("B", r.activeDeck.player.currentMediaItem?.mediaId)
        // Queue is A,B,A,B: a real AUTO item callback retires the prior occurrence,
        // rather than treating the pair string A->B as a permanent identity.
        r.activeDeck.autoAdvance(2, 70_000L)
        r.warm = true
        r.pulse(250L)
        r.awaitBuild()
        assertTrue((r.get("loopAttemptId") as Long) > firstAttempt)
        assertTrue("same IDs can prime in a fresh occurrence", r.requests.isNotEmpty())
        assertTrue(r.now.get() - 1_000_000L < 90_000L)
        assertNull(r.get("loopSuppressedPair"))
        assertEquals(0L, r.get("loopSuppressedUntil"))
    }

    @Test fun scrubRetainsTheExistingSoftGuardResetAndAllowsWarmSamePair() = withRig { r ->
        val native = r.nativePlan()
        r.out.position = (native.out.loopStart * 1_000).roundToLong() - 8_400L
        r.nextReady = false
        r.start()
        val epoch = r.get("scrubEpoch") as Long
        r.out.player.seekTo(70_000L)
        assertEquals(epoch + 1, r.get("scrubEpoch"))
        r.warm = true
        r.nextReady = true
        r.pulse(1_000L)
        r.awaitBuild()
        assertTrue(r.requests.isNotEmpty())
        assertNull(r.get("loopSuppressedPair"))
        assertEquals(0L, r.get("loopSuppressedUntil"))
    }

    @Test fun emptyPrimeHasOnlyOneRetryThenClassicNotAnUnboundedPrimeLoop() = withRig(compatible = true) { r ->
        r.out.position = 90_000L
        r.warm = true
        r.builder = { null }
        val plan = loopPlan(100.0)
        r.prime(plan)
        r.awaitBuild()
        r.now.addAndGet(2_000L)
        r.prime(plan)
        r.awaitBuild()
        assertEquals(2, r.requests.size)
        assertEquals("ARMING", r.phase())
        r.assertSoftAttemptGuard()
        repeat(4) { r.invoke("tick") }
        assertEquals(2, r.requests.size)
        assertEquals(1, r.armed.size)
    }

    @Test fun buildingAtT400msHasOneGuardedShiftAndNeverBlindlyShiftsAgain() = withRig(compatible = true) { r ->
        val plan = loopPlan(100.0)
        val releaseSecond = CountDownLatch(1)
        val calls = AtomicInteger()
        r.warm = true
        r.out.position = 91_600L
        r.builder = { request ->
            if (calls.incrementAndGet() == 1) fakeSession(request) else {
                try { releaseSecond.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
                null
            }
        }
        try {
            r.prime(plan)
            val first = r.takeBuild()
            awaitRetired(first.control) // Publication is still pending on Main; no decoder worker owns work.
            assertTrue((r.get("primeJob") as Job).isActive)
            assertTrue(r.begin(plan))
            r.out.position = 99_600L
            r.invoke("driveLoopArming")
            val shifted = r.get("loopTargetStartMs") as Long
            assertTrue("future target has full warm build + classic prepare + splice reserve", shifted - r.out.position >= 1_900L)
            assertEquals(true, r.get("loopShiftUsed"))
            assertEquals("LOOP_ARMING", r.phase())
            r.scheduler.runCurrent()
            val second = r.takeBuild()
            assertTrue(second.plan.out.loopStart > first.plan.out.loopStart)
            assertNotEquals(first.control, second.control)
            r.out.position = shifted - 400L
            r.invoke("driveLoopArming")
            assertEquals("ARMING", r.phase())
            assertEquals(2, r.requests.size)
            assertEquals(1, r.armed.size)
            r.assertSoftAttemptGuard()
            repeat(4) { r.invoke("tick") }
            assertEquals("one shift per attempt, never per changing key", 2, r.requests.size)
        } finally { releaseSecond.countDown() }
    }

    @Test fun nonRetiredBlockingBuildCannotQueueAReplacementAndClassicPreparesEarly() = withRig(compatible = true, durationMs = 200_000L) { r ->
        val plan = loopPlan(178.0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        r.warm = false
        r.nextReady = true
        r.out.position = 158_000L
        r.builder = {
            entered.countDown()
            while (release.count > 0) {
                try { release.await(3, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { /* Simulate uninterruptible model/codec work. */ }
            }
            null
        }
        try {
            r.prime(plan)
            val request = r.takeBuild()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(r.begin(plan))
            r.out.position = 170_000L // Cold native floor is applied in LOOP_ARMING, not just IDLE.
            r.set("loopIsNativeBridge", true)
            val started = System.nanoTime()
            r.invoke("driveLoopArming")
            assertTrue((System.nanoTime() - started) / 1_000_000L < 1_000L)
            assertEquals("ARMING", r.phase())
            assertEquals(1, r.into.prepareCalls)
            assertEquals(1, r.requests.size)
            assertFalse(request.control.isRetired)
            r.assertSoftAttemptGuard()
            repeat(4) { r.invoke("tick") }
            assertEquals("no replacement behind occupied workers", 1, r.requests.size)
        } finally { release.countDown() }
    }

    @Test fun modelLockHeldFor2sStillLetsDryDeckPrepareBeforeTheHolderReturns() = withRig(compatible = true) { r ->
        val lock = ReentrantLock()
        val entered = CountDownLatch(1)
        val holder = Thread {
            lock.lock()
            try { entered.countDown(); Thread.sleep(2_000L) }
            finally { lock.unlock() }
        }
        r.modelLock = lock
        r.busy = { false } // Busy proxy need not reveal the actual model owner: tryLock is the bound.
        r.out.position = 70_000L
        r.warm = true
        val plan = loopPlan(100.0)
        r.builder = { request ->
            val dry = floatArrayOf(0.25f, -0.25f)
            assertNotNull(request.separateVocalStem)
            assertNotNull(request.separateVocalPair)
            assertNull(request.separateVocalStem!!(LoopMix.Side.OUT, dry, dry, 1_000))
            assertNull(request.separateVocalPair!!(LoopMix.Side.IN, dry, dry, 1_000))
            fakeSession(request)
        }
        try {
            holder.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val began = System.nanoTime()
            r.prime(plan)
            r.awaitBuild()
            assertTrue(r.begin(plan))
            assertEquals(1, r.into.prepareCalls)
            assertTrue("prepare must not sit behind the 2s model lock", (System.nanoTime() - began) / 1_000_000L < 1_000L)
            assertTrue(holder.isAlive)
            assertEquals(0, r.modelCalls.get())
            val result = r.lines("mix.loop.build.result").single()
            assertTrue(result.contains("model-lock-unavailable"))
            assertTrue(result.contains("\"outStage\""))
            assertTrue(result.contains("\"inStage\""))
        } finally { holder.join(2_500L) }
    }

    @Test fun perDeckSpansDoNotCloseEachOtherAndOldGenerationWritesAreRejected() = withRig(compatible = true) { r ->
        r.warm = true
        r.out.position = 70_000L
        var old: LoopBuildRequest? = null
        val calls = AtomicInteger()
        r.builder = { request ->
            if (calls.incrementAndGet() == 1) {
                old = request
                val base = r.now.get()
                request.onDecodeStep(LoopMix.Side.OUT, "queued")
                request.onDecodeStep(LoopMix.Side.IN, "queued")
                r.now.set(base + 10); request.onDecodeStep(LoopMix.Side.OUT, "stream")
                r.now.set(base + 20); request.onDecodeStep(LoopMix.Side.IN, "stream")
                r.now.set(base + 30); request.onDecodeStep(LoopMix.Side.OUT, "decode")
                r.now.set(base + 50); request.onDecodeStep(LoopMix.Side.IN, "decode")
                r.now.set(base + 80); request.onDecodeStep(LoopMix.Side.OUT, "resample")
                r.now.set(base + 100); request.onDecodeStep(LoopMix.Side.IN, "resample")
            } else {
                old!!.onDecodeStep(LoopMix.Side.OUT, "stale-poison")
                request.onDecodeStep(LoopMix.Side.OUT, "fresh-out")
                request.onDecodeStep(LoopMix.Side.IN, "fresh-in")
            }
            fakeSession(request)
        }
        r.prime(loopPlan(100.0))
        r.awaitBuild()
        val first = r.lines("mix.loop.build.result").single()
        assertTrue(first.contains("OUT stream:20ms"))
        assertTrue(first.contains("IN stream:30ms"))
        assertTrue(first.contains("OUT decode:50ms"))
        assertTrue(first.contains("IN decode:50ms"))
        r.invoke("resetPlansForScrub")
        r.prime(loopPlan(100.0))
        r.awaitBuild()
        val second = r.lines("mix.loop.build.result").last()
        assertTrue(second.contains("fresh-out"))
        assertTrue(second.contains("fresh-in"))
        assertFalse(second.contains("stale-poison"))
        assertTrue(second.contains("\"pairKey\":\"A->B\""))
        assertTrue(second.contains("\"generation\":"))
    }

    private class Rig(val scheduler: TestCoroutineScheduler, val compatible: Boolean, val durationMs: Long) : AutoCloseable {
        val now = AtomicLong(1_000_000L)
        private val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, error -> errors.add(error) })
        val items = listOf("A", "B", "A", "B").map { MediaItem.Builder().setMediaId(it).setUri(LoopFixtureUri(it)).build() }
        val out = Deck(items, durationMs, now, index = 0, position = 10_000L, playWhenReady = true)
        val into = Deck(emptyList(), durationMs, now)
        var activeDeck = out
        private var spareDeck = into
        val armed = mutableListOf<String>()
        val requests = CopyOnWriteArrayList<LoopBuildRequest>()
        private val started = LinkedBlockingQueue<LoopBuildRequest>()
        val modelCalls = AtomicInteger()
        var modelLock: ReentrantLock? = null
        var busy: () -> Boolean = { false }
        var warm = false
        var nextReady = true
        var builder: (LoopBuildRequest) -> LoopMixSession? = ::fakeSession
        private val outPort = LoopMixPort().apply { sampleRate = 1_000; channelCount = 2; precision = true }
        private val inPort = LoopMixPort().apply { sampleRate = 1_000; channelCount = 2; precision = true }
        private val telemetryField = AutomixTelemetry::class.java.getDeclaredField("dir").apply { isAccessible = true }
        private val savedDir = telemetryField.get(AutomixTelemetry)
        private val dir = Files.createTempDirectory("automix-loop-splice").toFile()
        val controller = CrossfadeController(
            scope, { activeDeck.player }, { spareDeck.player }, { _, _ ->
                val previous = activeDeck; activeDeck = spareDeck; spareDeck = previous
            },
            analysisFor = { analysis(it.mediaId) },
            onArmIncoming = { item, _ -> armed.add(item.mediaId) },
            nextStreamReady = { nextReady },
            loopPortOf = { if (it === out.player) outPort else inPort },
            loopRegionCached = { _, _, _, _ -> warm },
            loopRegion = { _, _, from, to, rate, step, stop ->
                if (stop()) null else {
                    step("stream"); step("decode")
                    val start = from.coerceAtLeast(0.0)
                    val samples = ceil((to - start) * rate).toInt().coerceAtLeast(1)
                    LoopRegion(FloatArray(samples) { 0.25f }, FloatArray(samples) { 0.25f }, start)
                }
            },
            wholeTrackBusy = { busy() },
            separateTransitionWindow = { left, right, _, _, _, stop, step ->
                if (stop()) null else model(left, right, step)
            },
            separateTransitionPair = { left, right, _, _, stop, step ->
                if (stop()) null else model(left, right, step)?.let { it to it }
            },
            buildLoopSession = { request -> requests.add(request); started.put(request); builder(request) },
            loopRendererAvailable = { true },
            nowMs = now::get,
        )
        init { telemetryField.set(AutomixTelemetry, dir) }

        fun analysis(id: String): TrackAnalysis {
            val duration = durationMs / 1_000.0
            val bpm = if (compatible || id == "A") 120.0 else 94.0
            val beat = 60.0 / bpm
            val energy = (0..duration.toInt()).map { EnergySample(it.toDouble(), 0.8) }
            val vocals = if (compatible) 0.0 else 0.8
            return TrackAnalysis(
                status = TrackAnalysis.STATUS_READY, trackId = id, duration = duration,
                bpm = bpm, beatInterval = beat, beatConfidence = 0.9, firstBeat = 0.5,
                downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it <= duration }.toList(),
                key = "C major", keyConfidence = 0.9, contentEndTime = duration - 2.0,
                energyCurve = energy, vocalProbability = vocals, vocalActivityMask = energy.map { vocals },
                vocalWindows = listOf(CurveWindow(0.0, 1.0, FloatArray(duration.toInt() + 1) { vocals.toFloat() })),
                lowEnergyCurve = if (compatible) energy else emptyList(), quality = TrackAnalysis.QUALITY_FULL,
            )
        }

        private fun model(left: FloatArray, right: FloatArray, step: (String) -> Unit): Pair<FloatArray, FloatArray>? {
            val lock = modelLock
            return if (lock == null) { modelCalls.incrementAndGet(); left to right }
            else tryTransitionModel(lock, { null }, step) { modelCalls.incrementAndGet(); left to right }
        }

        fun get(name: String): Any? = field(name).get(controller)
        fun set(name: String, value: Any?) { field(name).set(controller, value) }
        private fun field(name: String) = CrossfadeController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String, vararg args: Any?): Any? = CrossfadeController::class.java.declaredMethods
            .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(controller, *args)
        fun phase() = get("phase").toString()
        fun prime(plan: LoopMix.Plan) {
            val a = activeDeck.player.currentMediaItem!!
            val b = activeDeck.player.getMediaItemAt(activeDeck.player.nextMediaItemIndex)
            invoke("ensureLoopAttempt", activeDeck.player, a, b)
            invoke("rememberLoopPair", a, b, analysis(a.mediaId), analysis(b.mediaId), durationMs, durationMs)
            invoke("primeLoopDecks", activeDeck.player, plan, a, b, durationMs, durationMs)
            scheduler.runCurrent()
        }
        fun begin(plan: LoopMix.Plan): Boolean = invoke("beginLoop", out.player, plan, items[0], items[1], durationMs, durationMs) as Boolean
        fun nativePlan(): LoopMix.Plan = invoke("nativeBridge", out.player, items[0], items[1], analysis("A"), analysis("B"), durationMs, durationMs) as LoopMix.Plan
        fun takeBuild(): LoopBuildRequest = started.poll(3, TimeUnit.SECONDS) ?: error("builder never started")
        fun awaitBuild() {
            val job = get("primeJob") as? Job
            val until = System.nanoTime() + 3_000_000_000L
            while (job?.isActive == true && System.nanoTime() < until) { scheduler.runCurrent(); Thread.yield() }
            scheduler.runCurrent()
            assertFalse("build must finish/decline in this bounded fixture", job?.isActive == true)
            checkErrors()
        }
        fun start() { controller.start(); scheduler.runCurrent(); checkErrors() }
        fun pulse(deltaMs: Long) {
            now.addAndGet(deltaMs)
            for (deck in listOf(out, into)) if (deck.playWhenReady) deck.position += deltaMs
            scheduler.advanceTimeBy(deltaMs)
            scheduler.runCurrent()
            checkErrors()
        }
        fun completeClassic() {
            val plan = get("planInFlight") as TransitionPlan
            val leaving = activeDeck
            val arriving = spareDeck
            leaving.position = maxOf(leaving.position, (plan.transitionStart * 1_000).roundToLong() + 1L)
            pulse(250L)
            assertEquals("FADING", phase())
            leaving.position = (plan.transitionEnd * 1_000).roundToLong()
            arriving.position += ceil(plan.fadeMs * plan.incomingPlaybackRate / plan.outgoingPlaybackRate).toLong() + 100L
            pulse(1_000L)
            if (phase() == "TAIL") pulse(1_000L)
            assertEquals("IDLE", phase())
        }
        fun assertSoftAttemptGuard() {
            assertNull(get("loopSuppressedPair"))
            assertEquals(0L, get("loopSuppressedUntil"))
            val stabilizer = get("countdownStabilizer") as CountdownStabilizer
            val epoch = get("scrubEpoch") as Long
            assertTrue(stabilizer.isFamilySuppressed("A->B", epoch, CountdownStabilizer.Family.LOOP))
            assertTrue(stabilizer.isFamilySuppressed("A->B", epoch, CountdownStabilizer.Family.NATIVE_BRIDGE))
        }
        fun lines(event: String): List<String> = File(dir, "automix-telemetry.jsonl").takeIf { it.exists() }
            ?.readLines()?.filter { it.contains("\"ev\":\"$event\"") }.orEmpty()
        private fun checkErrors() { errors.firstOrNull()?.let { throw AssertionError("controller failed", it) } }
        override fun close() {
            controller.release()
            scope.cancel()
            scheduler.runCurrent()
            telemetryField.set(AutomixTelemetry, savedDir)
            dir.deleteRecursively()
        }
    }

    private class Deck(
        initial: List<MediaItem>, private val durationMs: Long, private val now: AtomicLong,
        var index: Int = C.INDEX_UNSET, var position: Long = 0L, var playWhenReady: Boolean = false,
    ) {
        val items = initial.toMutableList()
        private var state = if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
        private var readyAt = 0L
        private var volume = 1f
        private var parameters = PlaybackParameters.DEFAULT
        private var repeat = Player.REPEAT_MODE_OFF
        private var shuffle = false
        private var skipSilence = false
        private var pauseAtEnd = false
        val listeners = mutableSetOf<Player.Listener>()
        var prepareCalls = 0
        private val timeline = QueueTimeline({ items }, durationMs)
        private fun next() = (index + 1).takeIf { index >= 0 && it in items.indices } ?: C.INDEX_UNSET
        private fun playbackState(): Int {
            if (state == Player.STATE_BUFFERING && now.get() >= readyAt) state = Player.STATE_READY
            return state
        }
        private fun info() = Player.PositionInfo(items.getOrNull(index)?.mediaId, index, items.getOrNull(index),
            items.getOrNull(index)?.mediaId, index, position, position, C.INDEX_UNSET, C.INDEX_UNSET)
        val player: ExoPlayer = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getCurrentMediaItemIndex", "getCurrentPeriodIndex" -> index
                "getMediaItemAt" -> items[args!![0] as Int]
                "getMediaItemCount" -> items.size
                "getNextMediaItemIndex" -> next()
                "hasNextMediaItem" -> next() != C.INDEX_UNSET
                "getCurrentTimeline" -> timeline
                "getCurrentPosition", "getContentPosition" -> {
                    check(!Thread.currentThread().name.startsWith("DefaultDispatcher")) { "worker read Media3 position" }
                    position
                }
                "getDuration" -> if (index in items.indices) durationMs else C.TIME_UNSET
                "getPlaybackState" -> playbackState()
                "isPlaying" -> playWhenReady && playbackState() == Player.STATE_READY
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
                    @Suppress("UNCHECKED_CAST") val queue = args!![0] as List<MediaItem>
                    items.clear(); items.addAll(queue); index = args[1] as Int; position = args[2] as Long
                    state = Player.STATE_IDLE; null
                }
                "prepare" -> { prepareCalls++; readyAt = now.get() + 1_000L; state = Player.STATE_BUFFERING; null }
                "stop" -> { state = Player.STATE_IDLE; null }
                "clearMediaItems" -> { items.clear(); index = C.INDEX_UNSET; position = 0; null }
                "removeMediaItems" -> { items.subList(args!![0] as Int, args[1] as Int).clear(); null }
                "addMediaItems" -> { @Suppress("UNCHECKED_CAST") val more = args!![0] as List<MediaItem>; items.addAll(more); null }
                "seekTo" -> {
                    val before = info()
                    if (args!!.size == 2) index = args[0] as Int
                    position = args.last() as Long
                    listeners.toList().forEach { it.onPositionDiscontinuity(before, info(), Player.DISCONTINUITY_REASON_SEEK) }
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
        fun autoAdvance(newIndex: Int, newPosition: Long) {
            index = newIndex; position = newPosition; state = Player.STATE_READY; playWhenReady = true
            listeners.toList().forEach { it.onMediaItemTransition(items[index], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
        }
    }

    private class QueueTimeline(private val items: () -> List<MediaItem>, private val durationMs: Long) : Timeline() {
        override fun getWindowCount() = items().size
        override fun getPeriodCount() = items().size
        override fun getWindow(index: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            window.uid = "occurrence-$index"
            window.mediaItem = items()[index]
            window.durationUs = durationMs * 1_000L
            window.defaultPositionUs = 0L
            window.firstPeriodIndex = index; window.lastPeriodIndex = index; window.isSeekable = true
            return window
        }
        override fun getPeriod(index: Int, period: Period, setIds: Boolean) =
            period.set(if (setIds) "occurrence-$index" else null, "occurrence-$index", index, durationMs * 1_000L, 0L)
        override fun getIndexOfPeriod(uid: Any) = uid.toString().substringAfter("occurrence-", "-1").toInt()
        override fun getUidOfPeriod(index: Int): Any = "occurrence-$index"
    }

    private fun withRig(compatible: Boolean = false, durationMs: Long = 110_000L, test: (Rig) -> Unit) {
        val restore = listOf(
            preserve(AppSettings.smartFadeEnabled), preserve(AppSettings.crossfadeSeconds), preserve(AppSettings.playbackSpeed),
            preserve(AppSettings.mixViewOpen), preserve(SleepTimer.afterTrack), preserve(AppSettings.smartAnalysis),
            preserve(AppSettings.smartTransitionWindow), preserve(AppSettings.smartMixInProgress), preserve(AppSettings.smartMixBlend),
            preserve(AppSettings.smartMixView), preserve(AppSettings.smartMixPreview), preserve(AppSettings.smartMixProgress),
            preserve(AppSettings.automixStatus), preserve(AppSettings.automixDetail),
        )
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        try {
            AppSettings.smartFadeEnabled.value = true; AppSettings.crossfadeSeconds.value = 0
            AppSettings.playbackSpeed.value = 1f; AppSettings.mixViewOpen.value = false
            AppSettings.smartTransitionWindow.value = null; AppSettings.smartMixView.value = null
            AppSettings.smartMixPreview.value = null; SleepTimer.afterTrack.value = false
            Rig(scheduler, compatible, durationMs).use(test)
        } finally { restore.forEach { it() }; Dispatchers.resetMain() }
    }
    private fun <T> preserve(flow: MutableStateFlow<T>): () -> Unit { val saved = flow.value; return { flow.value = saved } }

    companion object {
        private fun loopPlan(start: Double): LoopMix.Plan {
            val out = LoopMix.Pick(start, start + 2.0, 1, 0.5, 120.0, 0.0, LoopMix.Check.MASK)
            val inn = out.copy(loopStart = 2.5, loopEnd = 4.5)
            return LoopMix.Plan(out, inn, 1.0, 1, 1, 1, 3)
        }
        private fun fakeSession(request: LoopBuildRequest): LoopMixSession {
            val clock = LoopMix.Clock(request.plan)
            val renderer = LoopMixRenderer(request.sampleRate, object : LoopMixRenderer.Readout {
                override fun firstFrame() = -request.sampleRate.toLong()
                override fun heard(index: Int) = 0.0
                override fun exitGateViolations() = 0
            })
            return LoopMixSession::class.java.getDeclaredConstructor(LoopMix.Plan::class.java, LoopMix.Clock::class.java,
                String::class.java, String::class.java, LoopMixRenderer::class.java, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            ).apply { isAccessible = true }.newInstance(request.plan, clock, request.outId, request.inId, renderer,
                request.sampleRate, clock.elementTrackTime).also { it.planIncomingStart() }
        }
        private fun awaitRetired(control: LoopBuildControl) {
            val until = System.nanoTime() + 1_000_000_000L
            while (!control.isRetired && System.nanoTime() < until) Thread.yield()
            assertTrue("workers must be retired before rebuild", control.isRetired)
        }
    }
}
