package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.DeckRenderSetupV1
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.RenderGeometryV1
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.learning.TasteSnapshot
import com.music.bitchord.djmix.core.recipes.RecipeRegistry
import com.music.bitchord.playback.djmix.diagnostics.DiagnosticsBundler
import com.music.bitchord.playback.djmix.diagnostics.DiagnosticsManifest
import com.music.bitchord.playback.djmix.diagnostics.FlagsSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class DiagnosticsBundlerCompletenessTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun explicitFlagBundleContainsEveryFrozenArtifactSnapshotsHashesTelemetryAndAudio() {
        val root = kotlin.io.path.createTempDirectory("djmix-flag-test").toFile()
        val audio = File(root, "capture.flac").apply { writeBytes(byteArrayOf(0x66, 0x4c, 0x61, 0x43, 1, 2, 3)) }
        val plan = plan()
        val script = script(plan)
        val analysisA = analysis("out")
        val analysisB = analysis("in")
        val recipe = requireNotNull(RecipeRegistry.byId("S1"))
        val context = RetainedMixContext(
            planId = plan.planId,
            generation = plan.generation,
            outTrackId = plan.outTrackId,
            inTrackId = plan.inTrackId,
            transitionPlanV1 = plan,
            frozenRecipe = recipe,
            renderGeometry = geometry(),
            transitionScript = script,
            analysisA = analysisA,
            analysisB = analysisB,
            audioCaptureRef = audio,
            flagsSnapshot = FlagsSnapshot(safeMode = true, learningEnabled = false),
            tasteSnapshot = TasteSnapshot(recipeWeights = mapOf("S1" to 0.15), learningEnabled = false),
            assetSha256 = mapOf("recipe_asset" to SHA_A),
            modelSha256 = mapOf("beat_model" to SHA_B),
            recentEvents = listOf("{\"event\":\"plan_frozen\"}"),
            telemetryLines = listOf("{\"engine\":\"3.0.0\",\"eventType\":\"mix.done\"}"),
        )
        val zipFile = requireNotNull(DiagnosticsBundler(File(root, "reports"), clock = { 1_701_234_567_890L }).bundle(retainedContext = context))

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(entries.containsAll(setOf(
                "manifest.json", "plan.json", "recipe.json", "render_geometry.json", "script.json",
                "analysis_out.json", "analysis_in.json", "analysis_summary.json", "flags_snapshot.json",
                "taste_snapshot.json", "events_5min.jsonl", "telemetry_5min.jsonl", "mix_capture.flac",
            )))
            val manifest = json.decodeFromString<DiagnosticsManifest>(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
            assertEquals("3.0.0", manifest.engine)
            assertEquals("Flag", manifest.trigger)
            assertEquals(plan.planId, manifest.planId)
            assertEquals(plan.generation, manifest.generation)
            assertEquals(SHA_A, manifest.assetSha256.getValue("recipe_asset"))
            assertEquals(SHA_B, manifest.modelSha256.getValue("beat_model"))
            assertTrue(manifest.entries.getValue("audioCapture").available)
            assertTrue(manifest.recipeSha256?.matches(Regex("[a-f0-9]{64}")) == true)
            assertEquals(plan.planId, zip.getInputStream(zip.getEntry("plan.json")).reader().readText().let { Regex("\"planId\":\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) })
            assertTrue(zip.getInputStream(zip.getEntry("script.json")).reader().readText().contains("renderGeometry"))
            assertTrue(zip.getInputStream(zip.getEntry("analysis_out.json")).reader().readText().contains("\"trackId\": \"out\""))
            assertTrue(zip.getInputStream(zip.getEntry("analysis_in.json")).reader().readText().contains("\"trackId\": \"in\""))
            assertTrue(zip.getInputStream(zip.getEntry("telemetry_5min.jsonl")).reader().readText().contains("\"engine\":\"3.0.0\""))
            assertTrue(zip.getInputStream(zip.getEntry("events_5min.jsonl")).reader().readText().contains("plan_frozen"))
            assertEquals(audio.readBytes().toList(), zip.getInputStream(zip.getEntry("mix_capture.flac")).readBytes().toList())
            val summary = zip.getInputStream(zip.getEntry("analysis_summary.json")).reader().readText()
            assertTrue(summary.contains("\"engine\": \"3.0.0\""))
            assertTrue(summary.contains(plan.planId))
            val flags = zip.getInputStream(zip.getEntry("flags_snapshot.json")).reader().readText()
            assertTrue(flags.contains("\"safeMode\": true"))
            assertFalse(flags.contains("\"learningEnabled\": true"))
            assertNotNull(zip.getEntry("taste_snapshot.json"))
        }
        assertEquals(listOf(zipFile), DiagnosticsBundler(File(root, "reports")).listBundles())
    }

    @Test
    fun absentPlanScriptAnalysesAndAudioAreNamedInManifestInsteadOfPretendingComplete() {
        val root = kotlin.io.path.createTempDirectory("djmix-flag-missing").toFile()
        val zipFile = requireNotNull(DiagnosticsBundler(File(root, "reports"), clock = { 1000L }).bundle())
        ZipFile(zipFile).use { zip ->
            val manifest = json.decodeFromString<DiagnosticsManifest>(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
            assertFalse(manifest.entries.getValue("plan.json").available)
            assertFalse(manifest.entries.getValue("script.json").available)
            assertFalse(manifest.entries.getValue("analysis_out.json").available)
            assertFalse(manifest.entries.getValue("audioCapture").available)
            assertTrue(manifest.warnings.contains("events_ring_empty"))
            assertTrue(manifest.warnings.contains("telemetry_ring_empty"))
            assertFalse(zip.getInputStream(zip.getEntry("events_5min.jsonl")).reader().readText().isNotEmpty())
        }
        assertTrue(zipFile.parentFile.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    private fun plan() = TransitionPlanV1(
        planId = "flag-plan-9", generation = 9L, outTrackId = "out", inTrackId = "in", djLevel = DjLevel.SUBTLE,
        geometry = TransitionGeometry.STRAIGHT, frozenExitMs = 10_000L, frozenEntryMs = 2_000L,
        swapDownbeatMs = 10_500L, overlapDurationMs = 1_000L, barDurationMs = 500L,
        recipeId = "S1", recipeName = "Straight",
    )

    private fun script(plan: TransitionPlanV1) = TransitionScriptV1(
        planId = plan.planId,
        generation = plan.generation,
        outRegion = AudioRegionRef(DeckTarget.OUT, "out", 0L, 10_000L),
        inRegion = AudioRegionRef(DeckTarget.IN, "in", 0L, 10_000L),
        renderGeometry = geometry(),
    )

    private fun geometry(): RenderGeometryV1 {
        fun deck(start: Double) = DeckRenderSetupV1(
            regionStartSec = start, frameCount = 48_000, loopStartFrame = 0L, loopEndFrame = 0L,
            releaseAtFrame = null, knotFrames = listOf(0.0, 48_000.0), knotPositions = listOf(0.0, 48_000.0),
            runOn = false, stopAtMixSec = 1.0, prerollSec = 0.4, levelGain = 1f, fadeOutTrackSec = null,
        )
        return RenderGeometryV1(deck(0.0), deck(2.0), 1.0, 1.0, 0.5, 2.5, true)
    }

    private fun analysis(id: String) = TrackAnalysisV3(trackId = id, durationMs = 120_000L, bpm = 120.0)

    private companion object {
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
