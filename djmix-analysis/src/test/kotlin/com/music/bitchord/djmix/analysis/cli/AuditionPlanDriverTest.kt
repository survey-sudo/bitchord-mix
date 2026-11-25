package com.music.bitchord.djmix.analysis.cli

import com.music.bitchord.djmix.analysis.fixtures.FixtureWriter
import com.music.bitchord.djmix.core.contracts.*
import com.music.bitchord.djmix.core.recipes.RecipeInterpreter
import com.music.bitchord.djmix.core.recipes.RecipeRegistry
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.*

/** Explicit contract fixtures test the input seam; they never claim playback/audio parity. */
class AuditionPlanDriverTest {
    @Test fun arbitraryPairPlansButCannotInventPlaybackGeometry() = withPair { fixture ->
        val planned = fixture.run("S1", extra = listOf("--plan-only"))
        assertEquals(0, planned.first, planned.second)
        assertFalse(fixture.script.exists(), "Planning-only success must not write an empty fake script")
        assertTrue(fixture.planOutput.exists())
        val refused = fixture.run("S1")
        assertEquals(1, refused.first); assertTrue(refused.second.contains("Live geometry parity unavailable"))
        assertFalse(fixture.script.exists())
    }

    @Test fun exactFrozenGeometryUsesCoreCompilerAndExportsByteHashes() = withPair { fixture ->
        fixture.freeze("S1")
        val result = fixture.run("S1", frozen = true)
        assertEquals(0, result.first, result.second)
        val script = Json.parseToJsonElement(fixture.script.readText()).jsonObject
        assertEquals(fixture.frozen.renderGeometry, auditionJson.decodeFromString(TransitionScriptV1.serializer(), fixture.script.readText()).renderGeometry)
        for ((path, hash) in script.getValue("assetHashes").jsonObject) assertEquals(PcmIdentity.file(File(path)), hash.jsonPrimitive.content)
        assertEquals("S1", script.getValue("recipeId").jsonPrimitive.content)
        assertTrue(script.getValue("stems").jsonArray.isEmpty())
    }

    @Test fun absentStemsKeepExactFrozenRecipeAndUseItsNoStemSteps() = withPair { fixture ->
        fixture.freeze("S2")
        val result = fixture.run("S2", frozen = true)
        assertEquals(0, result.first, result.second)
        val script = Json.parseToJsonElement(fixture.script.readText()).jsonObject
        assertEquals("S2", script.getValue("recipeId").jsonPrimitive.content)
        assertEquals("no-stem", script.getValue("stemMode").jsonPrimitive.content)
        val kinds = script.getValue("envelopes").jsonArray.map { it.jsonObject.getValue("kind").jsonPrimitive.content }
        assertTrue("LOW_DB" in kinds); assertFalse(kinds.any { it.startsWith("STEM_") })
        assertEquals(fixture.frozenPlan, auditionJson.decodeFromString(TransitionPlanV1.serializer(), fixture.planOutput.readText()))
    }

    @Test fun frozenAnchorsGenerationAndSourceHashesFailClosed() = withPair { fixture ->
        fixture.freeze("S1")
        val original = fixture.frozenPlan
        fixture.savePlan(original.copy(frozenExitMs = original.frozenExitMs + 1_000))
        assertTrue(fixture.run("S1", frozen = true).second.contains("frozen_source_anchor_mismatch"))
        fixture.savePlan(original.copy(generation = 99))
        assertTrue(fixture.run("S1", frozen = true).second.contains("frozen_identity_mismatch"))
        fixture.savePlan(original)
        java.io.RandomAccessFile(fixture.outPcm, "rw").use { it.seek(0); it.writeInt(1) }
        val result = fixture.run("S1", frozen = true)
        assertEquals(1, result.first); assertTrue(result.second.contains("SHA-256")); assertFalse(fixture.script.exists())
    }

    @Test fun vocalMetadataUsesSourceSecondsTimesFiftyAndMixTimeline() = withPair { fixture ->
        fixture.freeze("S1")
        val changed = fixture.out.copy(vocalPresence50fps = List(64 * 50) { if (it >= 32 * 50) 0.8f else 0.05f })
        FixtureWriter.write(changed, fixture.outJson)
        val result = fixture.run("S1", frozen = true)
        assertEquals(0, result.first, result.second)
        val meta = Json.parseToJsonElement(fixture.meta.readText()).jsonObject
        val vocal = meta.getValue("vocalOut").jsonArray
        assertEquals(0.0, vocal.first().jsonArray[0].jsonPrimitive.double)
        assertEquals(0.8f, vocal.first().jsonArray[1].jsonPrimitive.float)
        assertEquals(0.02, vocal[1].jsonArray[0].jsonPrimitive.double)
        assertEquals("mix-seconds", meta.getValue("vocalTimeline").jsonPrimitive.content)
    }

    @Test fun missingOrChangedStemAssetsAreRefusedByHashAndSourceIdentity() = withPair { fixture ->
        fixture.freeze("S2")
        val manifest = File(fixture.root, "out.stems.json")
        val stem = ExportedStem("BASS", File(fixture.root, "missing.f32").path, 48_000, frameCount = 64 * 48_000,
            endMs = 64_000, sha256 = fixture.out.contentHash)
        val value = StemManifest(trackId = "out", available = true, stems = listOf(stem), windowStartMs = 16_000, windowEndMs = 64_000,
            requiredStems = listOf("BASS"), sourcePcmSha256 = fixture.out.contentHash,
            modelSha256 = "98228931494151762a1c4ab1ec7899a894b1f81fd4a509921a9d2f9bacc50845", windowFirstFrame = 16 * 48_000, windowEndFrame = 64 * 48_000)
        manifest.writeText(auditionJson.encodeToString(StemManifest.serializer(), value))
        val missing = fixture.run("S2", frozen = true, extra = listOf("--out-stems", manifest.path))
        assertEquals(1, missing.first); assertTrue(missing.second.contains("length/hash")); assertFalse(fixture.script.exists())
        manifest.writeText(auditionJson.encodeToString(StemManifest.serializer(), value.copy(sourcePcmSha256 = "0".repeat(64))))
        val wrongSource = fixture.run("S2", frozen = true, extra = listOf("--out-stems", manifest.path))
        assertEquals(1, wrongSource.first); assertTrue(wrongSource.second.contains("source PCM identity"))
    }

    @Test fun onlyFrozenRequiredDecksProduceWindowsAndNoStemSelectionStaysEmpty() = withPair { fixture ->
        fixture.freeze("S2")
        val result = fixture.run("S2", frozen = true, extra = listOf("--plan-only", "--assume-stems"))
        assertEquals(0, result.first, result.second)
        val windows = Json.parseToJsonElement(fixture.meta.readText()).jsonObject.getValue("stemWindows").jsonObject
        assertEquals(setOf("out", "in"), windows.keys)
        assertEquals(32_000L, windows.getValue("out").jsonObject.getValue("startMs").jsonPrimitive.long)
        assertEquals(48_000L, windows.getValue("out").jsonObject.getValue("endMs").jsonPrimitive.long)
        assertEquals(2_000L, windows.getValue("out").jsonObject.getValue("paddingMs").jsonPrimitive.long)
        fixture.savePlan(fixture.frozenPlan.copy(requiredStems = emptySet()))
        assertEquals(0, fixture.run("S2", frozen = true, extra = listOf("--plan-only")).first)
        assertTrue(Json.parseToJsonElement(fixture.meta.readText()).jsonObject.getValue("stemWindows").jsonObject.isEmpty())
    }

    @Test fun loopAndHoldQueriesReadSuppliedCoordinatesWithoutBuildingGeometry() {
        val deck = DeckRenderSetupV1(10.0, 20_000, 1_000, 3_000, 7_000.0,
            listOf(0.0, 1_000.0, 7_000.0, 9_000.0), listOf(1_000.0, 1_000.0, 7_000.0, 9_000.0), true, 10.0, 0.4, 1f, null)
        assertEquals(11.0, FrozenCoordinates.sourceSeconds(deck, 0.5, 1_000))
        assertEquals(11.0, FrozenCoordinates.sourceSeconds(deck, 3.0, 1_000))
        assertEquals(13.0, FrozenCoordinates.sourceSeconds(deck, 7.0, 1_000))
        assertEquals(11_000L to 15_000L, FrozenCoordinates.sourceBounds(deck, 9.0, 1_000))
    }

    private class Fixture(val root: File) {
        val outPcm = File(root, "out.f32"); val inPcm = File(root, "in.f32")
        val outJson = File(root, "out.analysis.json"); val inJson = File(root, "in.analysis.json")
        val script = File(root, "export.script.json"); val planOutput = File(root, "export.plan.json"); val meta = File(root, "export.meta.json")
        val sourcePlan = File(root, "playback.plan.json"); val sourceScript = File(root, "playback.script.json")
        val out: TrackAnalysisV3
        lateinit var frozenPlan: TransitionPlanV1
        lateinit var frozen: TransitionScriptV1
        init {
            for (file in listOf(outPcm, inPcm)) java.io.RandomAccessFile(file, "rw").use { it.setLength(64L * 48_000 * 8) }
            out = track("out", PcmIdentity.file(outPcm)); FixtureWriter.write(out, outJson)
            FixtureWriter.write(track("in", PcmIdentity.file(inPcm)), inJson)
        }
        fun freeze(recipeId: String) {
            val recipe = checkNotNull(RecipeRegistry.byId(recipeId))
            frozenPlan = TransitionPlanV1(planId = "fixture-frozen-42", generation = 42, outTrackId = "out", inTrackId = "in",
                djLevel = DjLevel.SUBTLE, geometry = TransitionGeometry.STRAIGHT, frozenExitMs = 32_000, frozenEntryMs = 0,
                swapDownbeatMs = 40_000, overlapDurationMs = 16_000, barDurationMs = 2_000,
                recipeId = recipeId, recipeName = recipe.name, requiredStems = recipe.requirements.requiredStems)
            val outSetup = DeckRenderSetupV1(0.0, 64 * 48_000, 32L * 48_000, 32L * 48_000, null,
                listOf(0.0, 16.0 * 48_000, 16.05 * 48_000), listOf(32.0 * 48_000, 48.0 * 48_000, 48.05 * 48_000), false, 16.05, 0.4, 1f, null)
            val inSetup = DeckRenderSetupV1(0.0, 64 * 48_000, 0, 0, null,
                listOf(0.0, 20.0 * 48_000), listOf(0.0, 20.0 * 48_000), true, 22.0, 0.4, 1f, 20.0)
            val geometry = RenderGeometryV1(outSetup, inSetup, 20.0, 20.0, 19.5, 19.5, true)
            val outRegion = AudioRegionRef(DeckTarget.OUT, "out", 0, 64_000, pcmPath = outPcm.path, stopAtMixMs = 16_050)
            val inRegion = AudioRegionRef(DeckTarget.IN, "in", 0, 64_000, pcmPath = inPcm.path, stopAtMixMs = 22_000, fadeOutTrackMs = 20_000, runOn = true)
            frozen = when (val result = RecipeInterpreter.compileFrozen(recipe, frozenPlan, geometry, outRegion, inRegion, useNoStemVariant = true)) {
                is MixOutcome.Ok -> result.value
                else -> error("Fixture compiler refused $result")
            }
            savePlan(frozenPlan)
            sourceScript.writeText(auditionJson.encodeToString(TransitionScriptV1.serializer(), frozen))
        }
        fun savePlan(plan: TransitionPlanV1) { sourcePlan.writeText(auditionJson.encodeToString(TransitionPlanV1.serializer(), plan)) }
        fun run(recipe: String, frozen: Boolean = false, extra: List<String> = emptyList()): Pair<Int, String> {
            val args = mutableListOf("--out-analysis", outJson.path, "--in-analysis", inJson.path, "--out-pcm", outPcm.path, "--in-pcm", inPcm.path,
                "--out", script.path, "--out-plan", planOutput.path, "--out-meta", meta.path, "--recipe", recipe)
            if (frozen) args += listOf("--frozen-plan", sourcePlan.path, "--frozen-script", sourceScript.path)
            args += extra
            val error = ByteArrayOutputStream()
            return AuditionPlanDriver.run(args.toTypedArray(), PrintStream(ByteArrayOutputStream()), PrintStream(error)) to error.toString()
        }
    }
    companion object {
        private fun track(id: String, hash: String): TrackAnalysisV3 = TrackAnalysisV3(
            trackId = id, contentHash = hash, durationMs = 64_000, analysisLevel = AnalysisLevel.FULL, bpm = 120.0, gridConfidence = 0.95f,
            beatsMs = (0L until 64_000L step 500L).toList(), downbeatsMs = (0L until 64_000L step 2_000L).toList(),
            keyCamelot = "8A", energyCurve = List(64) { 0.5f }, vocalPresence50fps = List(64 * 50) { 0f }, audibleEndMs = 64_000,
            structure = SongStructureV3(sections = listOf(StructureSectionV3(0, 64_000, type = SectionType.INSTRUMENTAL, confidence = 0.9f, energy = 0.5f)),
                phrases = (0L until 64_000L step 16_000L).mapIndexed { i, t -> PhraseBoundaryV3(t, i * 8, 0.9f) }, phraseLenBars = 8, phraseConfidence = 0.9f))
        private fun withPair(block: (Fixture) -> Unit) {
            val root = Files.createTempDirectory("frozen-driver-test-").toFile()
            try { block(Fixture(root)) } finally { root.deleteRecursively() }
        }
    }
}
