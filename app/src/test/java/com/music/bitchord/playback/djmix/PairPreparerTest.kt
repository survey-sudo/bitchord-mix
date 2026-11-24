package com.music.bitchord.playback.djmix

import androidx.media3.common.MediaItem
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.AutomixV2Stems
import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.LegacyTwoStem
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.RawStems
import com.music.bitchord.playback.smart.SongMaps
import com.music.bitchord.playback.smart.StemCache
import com.music.bitchord.playback.smart.StemEngine
import com.music.bitchord.playback.smart.StemKind
import com.music.bitchord.playback.smart.StemSeparator
import com.music.bitchord.playback.smart.StemSet
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.AutomixV2Choreo
import com.music.bitchord.playback.smart.choreo.ChoreoCaps
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.choreo.DjLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class PairPreparerTest {

    private fun usableAnalysis(id: String, bpm: Double = 124.0, duration: Double = 200.0): TrackAnalysis {
        val beat = 60.0 / bpm
        val beats = List((duration / beat).toInt() + 1) { it * beat }
        val bars = List((duration / (beat * 4.0)).toInt() + 1) { it * beat * 4.0 }
        val mask = FloatArray((duration * 4.0).toInt()) { 0f }
        return TrackAnalysis(
            trackId = id,
            status = TrackAnalysis.STATUS_READY,
            quality = TrackAnalysis.QUALITY_FULL,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            key = "C major",
            keyConfidence = 0.9,
            downbeats = bars,
            vocalWindows = listOf(CurveWindow(0.0, 4.0, mask)),
            headBeats = BeatSide(beats.take(128), bars.take(32), bpm, 0.9),
            tailBeats = BeatSide(beats.takeLast(128), bars.takeLast(32), bpm, 0.9),
            introEndTime = 16.0,
            outroStartTime = duration - 24.0,
            contentEndTime = duration - 1.0,
        )
    }

    private fun dummyStems(frames: Int, sampleRate: Int, startSec: Double, count: Int = 4): StemSet {
        val left = hashMapOf(
            StemKind.VOCALS to FloatArray(frames),
            StemKind.OTHER to FloatArray(frames),
        )
        val right = hashMapOf(
            StemKind.VOCALS to FloatArray(frames),
            StemKind.OTHER to FloatArray(frames),
        )
        if (count >= 4) {
            left[StemKind.DRUMS] = FloatArray(frames)
            left[StemKind.BASS] = FloatArray(frames)
            right[StemKind.DRUMS] = FloatArray(frames)
            right[StemKind.BASS] = FloatArray(frames)
        }
        return StemSet(sampleRate, frames, startSec, left, right, stemCount = count, confidence = 0.9f)
    }

    private fun testPlan(outStart: Double = 160.0, inStart: Double = 8.0): LoopMix.Plan = LoopMix.Plan(
        out = LoopMix.Pick(outStart, outStart + 8.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
        inn = LoopMix.Pick(inStart, inStart + 8.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
        fold = 1.0, enterBars = 4, rampBars = 4, exitBars = 4, totalBars = 12,
    )

    @Test
    fun fullLifecycleTransitionsDedupSeekGenerationAndTelemetryWork() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val updates = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val analyses = HashMap<String, TrackAnalysis>()
        val stemLock = Any()
        val nowClock = AtomicLong(1_000L)
        val decodeCalls = AtomicInteger(0)
        val stemCalls = AtomicInteger(0)
        val threadNames = CopyOnWriteArrayList<String>()
        val artworkItems = CopyOnWriteArrayList<String>()
        val emittedTimings = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val preparedLatch = AtomicReferenceLatch()

        val preparer = PairPreparer(
            scope = scope,
            analysisFor = { analyses[it] ?: TrackAnalysis(trackId = it) },
            analysisUpdates = updates,
            analysisRunningFor = { id -> id !in analyses },
            songMapFor = { id, a -> SongMaps.fromAnalysis(id, a) },
            canUseStems = { true },
            planSelector = TransitionPlanSelector { input ->
                val plan = testPlan(outStart = if (input.positionSec > 165.0) 176.0 else 160.0)
                val prep = ChoreoSessionGlue.Prep(
                    plan = plan,
                    forced = null,
                    forcedRecipe = null,
                    caps = ChoreoCaps.ALL,
                    level = input.djLevel,
                    outStructure = com.music.bitchord.playback.smart.choreo.StructureView.best(input.outSongMap, input.outAnalysis),
                    inStructure = com.music.bitchord.playback.smart.choreo.StructureView.best(input.inSongMap, input.inAnalysis),
                    pairKey = "${input.outTrack.id}->${input.inTrack.id}",
                    albumOrder = false,
                    note = "",
                )
                SelectedPlan(plan = plan, v2Prep = prep, pathHint = "v2-native")
            },
            decodeRegion = { _, from, to, rate ->
                threadNames += Thread.currentThread().name
                decodeCalls.incrementAndGet()
                val frames = ((to - from).coerceAtLeast(1.0) * rate).toInt()
                LoopRegion(FloatArray(frames), FloatArray(frames), from)
            },
            separateStems = { _, left, _, rate, start, _ ->
                threadNames += Thread.currentThread().name
                assertTrue("stemLock must be held during stem separation", Thread.holdsLock(stemLock))
                stemCalls.incrementAndGet()
                nowClock.addAndGet(50L)
                dummyStems(left.size, rate, start, count = 4)
            },
            stemLock = stemLock,
            onArtworkPrefetch = { item -> artworkItems += item.mediaId },
            capabilities = { ChoreoCaps.ALL },
            positionSecProvider = { 40.0 },
            nowMs = { nowClock.get() },
            mainDispatcher = dispatcher,
            telemetryEvent = { name, fields -> emittedTimings += name to fields },
            analysisWaitTimeoutMs = 5_000L,
            fullPreferredWaitMs = 0L,
        )

        val statesSeen = CopyOnWriteArrayList<PrepState>()
        scheduler.runCurrent()
        val collectJob = kotlinx.coroutines.runBlocking {
            // Observe background states briefly until PREPARED lands.
            val req = PrepRequest(
                outTrack = PrepTrack("A", 200.0, mediaItem = MediaItem.Builder().setMediaId("A").build()),
                inTrack = PrepTrack("B", 200.0, mediaItem = MediaItem.Builder().setMediaId("B").build()),
                djLevel = DjLevel.CLUB,
                positionSec = 10.0,
                sampleRate = 8_000,
            )
            preparer.trigger(req)
            // Deliver analyses via analysisUpdates so reactive wait wakes immediately.
            analyses["A"] = usableAnalysis("A", 120.0)
            analyses["B"] = usableAnalysis("B", 120.0)
            nowClock.addAndGet(25L)
            updates.emit("A")
            updates.emit("B")
            scheduler.advanceUntilIdle()

            val snap = preparedLatch.await { preparer.state.value?.takeIf { it.state == PrepState.PREPARED } }
            assertNotNull("PairPreparer must reach PREPARED", snap)
            val ready = snap!!
            assertEquals("djmix-prep", threadNames.firstOrNull())
            assertTrue("Prep/decode work must stay on djmix-prep, stems on djmix-stems", threadNames.all { it == "djmix-prep" || it == "djmix-stems" })
            assertTrue("SCNet stems must run on dedicated djmix-stems thread (AE3 Part A 3.2)", "djmix-stems" in threadNames)
            assertEquals(2, decodeCalls.get())
            assertTrue("Stems separated on CLUB", stemCalls.get() >= 1)
            assertNotNull(ready.outRegion)
            assertNotNull(ready.inRegion)
            assertNotNull(ready.script)
            assertTrue("Top candidates cached", ready.topCandidates.isNotEmpty())
            assertTrue(ready.timings.preparedLeadSec >= 100.0)

            // Artwork prefetch fires on main dispatcher.
            scheduler.advanceUntilIdle()
            assertEquals(listOf("B"), artworkItems.toList())

            // Duplicate trigger with same pair, level, and valid window must deduplicate!
            val prevDecodes = decodeCalls.get()
            preparer.trigger(req.copy(positionSec = 50.0))
            Thread.sleep(50)
            assertEquals(prevDecodes, decodeCalls.get())

            // Seek past planned exit window MUST re-trigger & produce a new window!
            preparer.onSeek(req.copy(positionSec = 166.0))
            val retimed = preparedLatch.await {
                preparer.state.value?.takeIf { it.state == PrepState.PREPARED && it.generation > ready.generation }
            }
            assertNotNull("Seeking past planned window must re-prepare", retimed)
            assertEquals(176.0, retimed!!.plannedWindowStartSec, 0.01)

            // Lifecycle transitions BUILDING -> ARMED -> RUNNING -> DONE
            preparer.markBuilding("A", "B")
            assertEquals(PrepState.BUILDING, preparer.state.value?.state)
            preparer.markArmed("A", "B", armLeadMs = 3_200L)
            assertEquals(PrepState.ARMED, preparer.state.value?.state)
            preparer.recordTiming("A", "B", buildMs = 410L)
            preparer.markRunning("A", "B")
            assertEquals(PrepState.RUNNING, preparer.state.value?.state)
            preparer.markDone("A", "B")
            assertEquals(PrepState.DONE, preparer.state.value?.state)
            val finalTimings = preparer.state.value!!.timings
            preparer.emitTiming(finalTimings)
            assertEquals(1, emittedTimings.size)
            val fields = emittedTimings.single().second
            assertEquals(17, fields.size)
            assertEquals(410L, fields["buildMs"])
            assertEquals(3_200L, fields["armLeadMs"])
            assertEquals("CLUB", fields["level"])
            assertEquals("v2-native", fields["path"])
        }
        @Suppress("UNUSED_VARIABLE") val unused = statesSeen to collectJob
        preparer.release()
        scope.cancel()
    }

    @Test
    fun generationBumpCancelsObsoleteStemRunBeforeStaleOverwrite() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val updates = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val analyses = mapOf(
            "A" to usableAnalysis("A"),
            "B" to usableAnalysis("B"),
            "C" to usableAnalysis("C"),
        )
        val stemEntered = CountDownLatch(1)
        val abortedFirstRun = AtomicBoolean(false)

        val preparer = PairPreparer(
            scope = scope,
            analysisFor = { analyses[it] ?: TrackAnalysis(trackId = it) },
            analysisUpdates = updates,
            songMapFor = { id, a -> SongMaps.fromAnalysis(id, a) },
            planSelector = TransitionPlanSelector { input ->
                val plan = testPlan()
                val prep = ChoreoSessionGlue.Prep(
                    plan, null, null, ChoreoCaps.ALL,
                    input.djLevel,
                    com.music.bitchord.playback.smart.choreo.StructureView.best(input.outSongMap, input.outAnalysis),
                    com.music.bitchord.playback.smart.choreo.StructureView.best(input.inSongMap, input.inAnalysis),
                    "${input.outTrack.id}->${input.inTrack.id}", false, "",
                )
                SelectedPlan(plan = plan, v2Prep = prep)
            },
            decodeRegion = { _, from, to, rate ->
                val frames = ((to - from).coerceAtLeast(1.0) * rate).toInt()
                LoopRegion(FloatArray(frames), FloatArray(frames), from)
            },
            separateStems = { trackId, left, _, rate, start, shouldAbort ->
                if (trackId == "A") {
                    stemEntered.countDown()
                    val deadline = System.currentTimeMillis() + 3_000L
                    while (!shouldAbort() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(10)
                    }
                    if (shouldAbort()) {
                        abortedFirstRun.set(true)
                        return@PairPreparer null
                    }
                }
                dummyStems(left.size, rate, start, count = 4)
            },
            capabilities = { ChoreoCaps.ALL },
            analysisWaitTimeoutMs = 2_000L,
            fullPreferredWaitMs = 0L,
        )

        preparer.trigger(PrepRequest(PrepTrack("A", 200.0), PrepTrack("B", 200.0), DjLevel.CLUB, 10.0, 8_000))
        assertTrue("First stemming run must start", stemEntered.await(3, TimeUnit.SECONDS))

        // Retarget to A -> C: bumps generation and must cleanly abort A -> B between chunks!
        preparer.trigger(PrepRequest(PrepTrack("A", 200.0), PrepTrack("C", 200.0), DjLevel.CLUB, 10.0, 8_000))
        val ready = AtomicReferenceLatch().await {
            preparer.state.value?.takeIf { it.inId == "C" && it.state == PrepState.PREPARED }
        }
        assertNotNull(ready)
        assertTrue("Stale stem run must observe shouldAbort()=true", abortedFirstRun.get())
        assertEquals("C", preparer.state.value?.inId)

        preparer.release()
        scope.cancel()
    }

    @Test
    fun stemUnavailableFallsBackToNoStemStepsAndChoreoSessionGlueNeverRunsSeparation() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val analyses = mapOf("A" to usableAnalysis("A"), "B" to usableAnalysis("B"))
        val stemCalls = AtomicInteger(0)

        val preparer = PairPreparer(
            scope = scope,
            analysisFor = { analyses.getValue(it) },
            analysisUpdates = MutableSharedFlow(),
            songMapFor = { id, a -> SongMaps.fromAnalysis(id, a) },
            canUseStems = { false },
            planSelector = TransitionPlanSelector { input ->
                val plan = testPlan()
                val prep = ChoreoSessionGlue.Prep(
                    plan, null, null, ChoreoCaps.ALL and ChoreoCaps.STEMS.inv(), input.djLevel,
                    com.music.bitchord.playback.smart.choreo.StructureView.best(input.outSongMap, input.outAnalysis),
                    com.music.bitchord.playback.smart.choreo.StructureView.best(input.inSongMap, input.inAnalysis),
                    "A->B", false, "",
                )
                SelectedPlan(plan = plan, v2Prep = prep)
            },
            decodeRegion = { _, from, to, rate ->
                val frames = ((to - from).coerceAtLeast(1.0) * rate).toInt()
                LoopRegion(FloatArray(frames), FloatArray(frames), from)
            },
            separateStems = { _, _, _, _, _, _ ->
                stemCalls.incrementAndGet()
                null
            },
            capabilities = { ChoreoCaps.ALL and ChoreoCaps.STEMS.inv() },
            fullPreferredWaitMs = 0L,
        )

        preparer.trigger(PrepRequest(PrepTrack("A", 200.0), PrepTrack("B", 200.0), DjLevel.SUBTLE, 20.0, 8_000))
        val snap = AtomicReferenceLatch().await { preparer.state.value?.takeIf { it.state == PrepState.PREPARED } }
        assertNotNull(snap)
        assertEquals(0, stemCalls.get())
        assertNull(snap!!.outStems)
        assertNull(snap.inStems)
        assertNotNull(snap.script)
        assertFalse(snap.script!!.outUsesStems)
        assertFalse(snap.script!!.inUsesStems)
        assertEquals(0, snap.timings.stems)

        // Direct ChoreoSessionGlue.choreograph accepts preparedScript with zero ONNX inference.
        val clock = LoopMix.Clock(snap.plan!!)
        val glueResult = ChoreoSessionGlue.choreograph(
            prep = snap.v2Prep!!,
            plan = snap.plan!!,
            clock = clock,
            sampleRate = 8_000,
            outRegion = snap.outRegion!!,
            inRegion = snap.inRegion!!,
            outAnalysis = analyses["A"],
            inAnalysis = analyses["B"],
            sungOut = null,
            sungIn = null,
            preparedOutStems = snap.outStems,
            preparedInStems = snap.inStems,
            preparedScript = snap.script,
        )
        assertNotNull(glueResult)
        assertFalse(glueResult!!.script.outUsesStems)

        preparer.release()
        scope.cancel()
    }

    @Test
    fun paddedWindowRespectsMaxStemRegionCapAndIndependentStemVsVocalLocks() {
        val (from, to) = PairPreparer.paddedWindow(
            coreFrom = 100.0, coreTo = 120.0, padSec = 6.0, trackDurationSec = 200.0, maxRegionSec = 40.0,
        )
        assertEquals(94.0, from, 1e-6)
        assertEquals(126.0, to, 1e-6)

        val (cappedFrom, cappedTo) = PairPreparer.paddedWindow(
            coreFrom = 100.0, coreTo = 136.0, padSec = 8.0, trackDurationSec = 200.0,
            maxRegionSec = AutomixV2Choreo.MAX_STEM_REGION_SEC,
        )
        assertTrue(cappedFrom <= 100.0 && cappedTo >= 136.0)
        assertEquals(40.0, cappedTo - cappedFrom, 1e-6)

        // Verify StemSeparator honors split locks (stemLock vs legacyLock/vocalLock) and shouldAbort.
        val savedEnabled = AutomixV2Stems.enabled
        val savedFourStem = AutomixV2Stems.fourStemEnabled
        val savedLegacy = AutomixV2Stems.legacyFallbackEnabled
        val tempDir: File = Files.createTempDirectory("stem-locks-").toFile()
        try {
            AutomixV2Stems.enabled = true
            AutomixV2Stems.fourStemEnabled = true
            AutomixV2Stems.legacyFallbackEnabled = true
            val stemLock = Any()
            val vocalLock = Any()
            val engineCalledOnBackground = AtomicBoolean(false)
            val fakeEngine = object : StemEngine {
                override val tag: String = "test4"
                override val modelRate: Int = 8_000
                override val baseConfidence: Float = 0.9f
                override fun separate(left: FloatArray, right: FloatArray, shouldStop: () -> Boolean): RawStems? {
                    if (shouldStop()) return null
                    engineCalledOnBackground.set(true)
                    assertTrue("4-stem must lock stemLock", Thread.holdsLock(stemLock))
                    assertFalse("4-stem must NOT lock vocalLock", Thread.holdsLock(vocalLock))
                    return RawStems(
                        arrayOf(FloatArray(left.size), FloatArray(left.size), FloatArray(left.size), FloatArray(left.size)),
                        arrayOf(FloatArray(left.size), FloatArray(left.size), FloatArray(left.size), FloatArray(left.size)),
                        0.01,
                    )
                }
                override fun release() {}
            }
            val legacyCalled = AtomicBoolean(false)
            val separator = StemSeparator(
                engine = { fakeEngine },
                legacy = LegacyTwoStem { l, r, _ ->
                    legacyCalled.set(true)
                    assertTrue("2-stem legacy must lock vocalLock", Thread.holdsLock(vocalLock))
                    assertFalse("2-stem legacy must NOT lock stemLock", Thread.holdsLock(stemLock))
                    (l.copyOf() to r.copyOf()) to (FloatArray(l.size) to FloatArray(r.size))
                },
                cache = StemCache({ tempDir }),
                resample = { s, _, _ -> s },
                modelLock = stemLock,
                legacyLock = vocalLock,
            )
            // shouldAbort=true aborts before engine runs.
            val aborted = separator.separateFor("t1", FloatArray(800), FloatArray(800), 8_000, 0.0) { true }
            assertNull(aborted)
            assertFalse(engineCalledOnBackground.get())

            // Holding vocalLock on ANOTHER thread while 4-stem runs does NOT deadlock/block 4-stem!
            val vocalLocked = CountDownLatch(1)
            val releaseVocal = CountDownLatch(1)
            val holder = Thread {
                synchronized(vocalLock) {
                    vocalLocked.countDown()
                    releaseVocal.await(4, TimeUnit.SECONDS)
                }
            }
            holder.start()
            assertTrue(vocalLocked.await(2, TimeUnit.SECONDS))
            val result = try {
                separator.separateFor("t1", FloatArray(800), FloatArray(800), 8_000, 0.0)
            } finally {
                releaseVocal.countDown()
                holder.join(2_000)
            }
            assertNotNull(result)
            assertTrue(engineCalledOnBackground.get())
            assertEquals(4, result!!.stemCount)

            // Holding stemLock on another thread while 2-stem legacy runs does NOT block legacy either.
            val legacySeparator = StemSeparator(
                engine = { null },
                legacy = LegacyTwoStem { l, r, _ ->
                    legacyCalled.set(true)
                    assertTrue("2-stem legacy must lock vocalLock", Thread.holdsLock(vocalLock))
                    assertFalse("2-stem legacy must NOT lock stemLock", Thread.holdsLock(stemLock))
                    (l.copyOf() to r.copyOf()) to (FloatArray(l.size) to FloatArray(r.size))
                },
                cache = StemCache({ tempDir }),
                resample = { s, _, _ -> s },
                modelLock = stemLock,
                legacyLock = vocalLock,
            )
            val stemLocked = CountDownLatch(1)
            val releaseStem = CountDownLatch(1)
            val stemHolder = Thread {
                synchronized(stemLock) {
                    stemLocked.countDown()
                    releaseStem.await(4, TimeUnit.SECONDS)
                }
            }
            stemHolder.start()
            assertTrue(stemLocked.await(2, TimeUnit.SECONDS))
            val legacyResult = try {
                legacySeparator.separateFor("t2", FloatArray(800), FloatArray(800), 8_000, 0.0)
            } finally {
                releaseStem.countDown()
                stemHolder.join(2_000)
            }
            assertNotNull(legacyResult)
            assertTrue(legacyCalled.get())
            assertEquals(2, legacyResult!!.stemCount)
        } finally {
            AutomixV2Stems.enabled = savedEnabled
            AutomixV2Stems.fourStemEnabled = savedFourStem
            AutomixV2Stems.legacyFallbackEnabled = savedLegacy
            tempDir.deleteRecursively()
        }
    }

    private class AtomicReferenceLatch {
        fun <T> await(timeoutMs: Long = 4_000L, predicate: () -> T?): T? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                predicate()?.let { return it }
                Thread.sleep(10)
            }
            return predicate()
        }
    }
}
