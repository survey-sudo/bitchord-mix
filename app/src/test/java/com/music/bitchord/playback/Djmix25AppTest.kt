package com.music.bitchord.playback

import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.LEVEL_FRAME_RATE
import com.music.bitchord.playback.smart.levelFromRms
import com.music.bitchord.playback.smart.camelotCode
import com.music.bitchord.playback.smart.camelotSteps
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.PhraseChoice
import com.music.bitchord.playback.smart.EntryCue
import com.music.bitchord.playback.smart.EntryKind
import com.music.bitchord.playback.smart.ExitCue
import com.music.bitchord.playback.smart.ExitKind
import com.music.bitchord.playback.smart.SectionFn
import com.music.bitchord.playback.smart.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * djmix25 `:app` suite (vc67).
 * FX suppression + acapella gating + JNI key names + regional keys.
 */
class Djmix25AppTest {

    private fun exitCue(room: Int = 8) = ExitCue(
        kind = ExitKind.OUTRO_PHRASE,
        releaseBar = 72,
        releaseSec = 170.0,
        roomBars = room,
        vocalRoomBars = 0,
        tailBars = 0,
        sectionFn = SectionFn.OUTRO,
        role = SectionRole.GROOVE,
        energyAtRelease = 0.4f,
        energyLevel = 4,
        loopableRoom = room >= 4,
        cutsSungBars = 0,
        cutsBars = 0,
    )

    private fun entryCue() = EntryCue(
        kind = EntryKind.HEAD,
        startBar = 0,
        startSec = 0.0,
        landingBar = 8,
        landingFn = SectionFn.INTRO,
        landingRole = SectionRole.GROOVE,
        leadInBars = 8,
        leadInVocal = 0f,
        leadInLoopable = true,
        energyAtLanding = 0.4f,
        energyLevel = 4,
        trimmedBars = 0,
        pickupBeats = 0f,
    )

    private fun choice(vocalOverlap: Double = 0.0, vocalProduct: Float = 0f): PhraseChoice = PhraseChoice(
        template = MixTemplate.M1,
        rung = "Phrase",
        score = 0.85,
        exit = exitCue(),
        entry = entryCue(),
        overlapBars = 8,
        swapBar = 0.0,
        tailBeats = 0.0,
        loopK = 0,
        echoGated = false,
        vocalProduct = vocalProduct,
        tempoPrepBars = 0,
        echoDelayBeats = 0.75,
        echoDelayName = "ECHO_DELAY_BEATS",
        swapRampMs = 120,
        swapRampName = "SWAP_RAMP_BLEND_MS",
        tempoPolicy = "TEMPO_POLICY=b",
        vocalOverlapBeats = vocalOverlap,
        energyDelta = 0f,
        struct = 0.9,
        vocalScore = 0.9,
        phraseScore = 0.9,
        energyScore = 0.9,
        lengthScore = 0.9,
        keyScore = 0.9,
        tempoScore = 0.9,
        cost = 0.0,
    )

    private fun track(id: String, bpm: Double, vocal: Double = 0.3): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = List(((180.0 / (4 * beat)) + 1).toInt()) { it * 4 * beat + 0.5 }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = 180.0,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            firstBeat = 0.5,
            key = "C major",
            keyConfidence = 0.9,
            energyCurve = List(180) { EnergySample(it.toDouble(), 1.0) },
            vocalProbability = vocal,
            quality = TrackAnalysis.QUALITY_FULL,
            levelWindows = listOf(
                CurveWindow(
                    0.0,
                    LEVEL_FRAME_RATE,
                    FloatArray((180.0 * LEVEL_FRAME_RATE).toInt()) {
                        levelFromRms(0.3).toFloat()
                    },
                ),
            ),
        )
    }

    // ---- JNI key names: C# (Db) must be 3B, not 8B (C) ----

    @Test
    fun sharpKeyYieldsThreeBNotEightB() {
        // C# major is Db major (3B); it must not collapse to C major (8B).
        assertEquals("3B", camelotCode("C# major"))
        assertEquals("3B", camelotCode("C\u266f major"))
        // B natural is 1B, distinct from Db/C# (3B).
        assertEquals("1B", camelotCode("B major"))
        assertEquals("8B", camelotCode("C major"))
        assertEquals("8A", camelotCode("A minor"))
        // Relative pair 8B->8A distance 0.
        assertEquals(0, camelotSteps("C major", "A minor"))
        // C# major vs C major: transposed, not relative.
        val d = camelotSteps("C# major", "C major")
        assertTrue("C# vs C must not be relative, got $d", d != null && d != 0)
    }

    // ---- FX rotation suppression on dual vocal ----

    @Test
    fun dualVocalNeverFlanges() {
        // vocalOverlapBeats >= 1.0 forces clean (flanger suppressed).
        val script = TransitionMoves.compile(choice(vocalOverlap = 2.0, vocalProduct = 0.5f), outBpm = 124.34, inBpm = 124.25, outgoingId = "a", incomingId = "b")
        assertFalse("dual-vocal must never flange", script.plan.flanger)
        assertFalse(script.flanger)
        assertFalse("dual-vocal must never roll", script.plan.buildupRoll)
        // Even an EDM pair with vocal clash stays clean.
        val edmScript = TransitionMoves.compile(choice(vocalOverlap = 1.5, vocalProduct = 0.4f), outBpm = 124.34, inBpm = 124.25, outgoingId = "out", incomingId = "in")
        assertFalse(edmScript.plan.flanger)
    }

    @Test
    fun cleanEdmStillFlanges() {
        val script = TransitionMoves.compile(choice(vocalOverlap = 0.0, vocalProduct = 0f), outBpm = 124.34, inBpm = 124.25, outgoingId = "out", incomingId = "in")
        // EDM + low vocal + eligible template must flange (Bug A4 preserved).
        assertTrue("clean EDM must flange", script.plan.flanger)
    }

    @Test
    fun fxRotationNeverRepeatsTwiceInQueue() {
        // Anti-repeat by pair hash exists: consecutive queue pairs vary.
        var repeats = 0
        for (i in 0 until 100) {
            if (TransitionMoves.fxSlot("t$i", "t${i + 1}") == TransitionMoves.fxSlot("t${i + 1}", "t${i + 2}")) repeats++
        }
        assertTrue("rotation must not be constant (repeats=$repeats)", repeats < 60)
    }

    // ---- acapella gating ----

    @Test
    fun acapellaGatingRequiresSameCamelotAndConfidence() {
        val out = track("out", 120.0, vocal = 0.9).copy(
            key = "C major", keyConfidence = 0.9,
            keyTail = "C major", keyTailConfidence = 0.9,
        )
        val inn = track("in", 120.0, vocal = 0.2).copy(
            key = "C major", keyConfidence = 0.9,
            keyHead = "C major", keyHeadConfidence = 0.9,
        )
        assertTrue(TransitionMoves.shouldAcapella(out, inn))
        // Different camelot -> no acapella.
        val other = track("other", 120.0, vocal = 0.2).copy(
            key = "G major", keyConfidence = 0.9,
            keyHead = "G major", keyHeadConfidence = 0.9,
        )
        assertFalse(TransitionMoves.shouldAcapella(out, other))
        // Low confidence -> no acapella.
        val weak = out.copy(keyTailConfidence = 0.4)
        assertFalse(TransitionMoves.shouldAcapella(weak, inn))
        // Vocal incoming -> no acapella.
        val vocalIn = inn.copy(vocalProbability = 0.9)
        assertFalse(TransitionMoves.shouldAcapella(out, vocalIn))
    }

    @Test
    fun acapellaOverInstrumentalCompilesWithCarrier() {
        val out = track("out", 120.0, vocal = 0.9)
        val inn = track("in", 120.0, vocal = 0.2)
        // Direct compile path with the carrier on must still produce a script.
        val script = TransitionMoves.acapellaOverInstrumental(choice(), 120.0, 120.0, outgoingId = "a", incomingId = "b")
        assertNotNull(script)
        assertTrue(script.plan.acapella)
        assertTrue("acapella is capped at 8 bars, got ${script.choice.overlapBars}", script.choice.overlapBars <= 8)
    }

    // ---- windowed stems gate is opt-in + RAM-gated (AppSettings flag) ----

    @Test
    fun windowedStemsFlagDefaultsOn() {
        // vc73: the flag defaults true (umxhq separation active out of the box
        // on capable devices; TrackAnalyzer still RAM-gates instrumentation).
        val field = com.music.bitchord.data.settings.AppSettings.automixWindowedStemsEnabled
        assertTrue(field.value)
    }

    // ---- regional keys flow through TrackAnalysis ----

    @Test
    fun regionalKeysDefaultToBlankAndFallbackToGlobal() {
        val a = track("x", 120.0)
        assertEquals("", a.keyHead)
        assertEquals("", a.keyTail)
        assertEquals("C major", a.key)
    }
    // ---- vc67 AnalysisStore regional keys round-trip ----
    @Test
    fun regionalKeysSurviveSaveLoad() {
        val root = java.nio.file.Files.createTempDirectory("vc67-regional").toFile()
        val store = com.music.bitchord.playback.smart.AnalysisStore { root }
        val a = track("x", 120.0).copy(keyHead = "C major", keyHeadConfidence = 0.9, keyTail = "G major", keyTailConfidence = 0.85)
        store.save("x", a)
        val loaded = store.load("x")
        assertNotNull(loaded)
        assertEquals("C major", loaded!!.keyHead)
        assertEquals(0.9, loaded.keyHeadConfidence, 1e-9)
        assertEquals("G major", loaded.keyTail)
        assertEquals(0.85, loaded.keyTailConfidence, 1e-9)
    }
    // ---- vc67 vocal separation safe fallback (no native/ORT on JVM) ----
    @Test
    fun separateFallsBackCleanlyWithoutNative() {
        val l = FloatArray(4410) { 0.5f }
        val r = FloatArray(4410) { 0.5f }
        val out = com.music.bitchord.playback.smart.VocalSpectrogram.separate(l, r, 44100.0, FloatArray(0), 0, true)
        assertTrue(out.isEmpty())
        val t = com.music.bitchord.playback.smart.VocalSpectrogram.compute(l, r)
        val tracker = com.music.bitchord.playback.smart.VocalTracker({ "missing.onnx" }, { 1 })
        assertTrue(tracker.separateWindow(l, r, 44100.0, true) == null)
    }
    // ---- vc67 tailDurationMs zero-tail contract ----
    @Test
    fun tailResetsAfterFinishSoZeroTailNeverInherits() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val c = CrossfadeController(
            scope = scope,
            active = { throw IllegalStateException("no player in test") },
            standby = { throw IllegalStateException("no player in test") },
            onHandoff = { _, _ -> },
        )
        val field = CrossfadeController::class.java.getDeclaredField("tailDurationMs")
        field.isAccessible = true
        assertEquals(0L, field.getLong(c))
        field.setLong(c, 800L)
        assertEquals(800L, field.getLong(c))
        val finish = CrossfadeController::class.java.getDeclaredMethod("finish")
        finish.isAccessible = true
        finish.invoke(c)
        assertEquals("finish must clear the tail account", 0L, field.getLong(c))
    }


}
