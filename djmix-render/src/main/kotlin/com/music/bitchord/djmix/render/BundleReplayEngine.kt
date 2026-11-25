package com.music.bitchord.djmix.render

import com.music.bitchord.djmix.core.contracts.*
import com.music.bitchord.djmix.core.learning.TasteSnapshot
import com.music.bitchord.djmix.core.machine.MixEventKind
import com.music.bitchord.djmix.core.machine.MixStateKind
import com.music.bitchord.djmix.core.recipes.RecipeInterpreter
import com.music.bitchord.djmix.core.recipes.ScriptExecutionGuard
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.roundToLong

class BundleReplayEngine {
    fun load(bundle: Path): RenderContract {
        val payload = FlagBundleReader.read(bundle)
        val text = payload.text
        fun required(name: String): String = text[name] ?: fail(ReplayErrorCode.MISSING_ENTRY, "Required replay entry unavailable: $name")
        fun <T> decode(name: String, serializer: KSerializer<T>): T = NativeScriptJsonCodec.decodeComplete(serializer, required(name), name)
        fun <T> optional(name: String, serializer: KSerializer<T>): T? = text[name]?.let { NativeScriptJsonCodec.decodeComplete(serializer, it, name) }
        val contract = RenderContract(
            payload.manifest, decode("plan.json", TransitionPlanV1.serializer()), decode("recipe.json", RecipeV2.serializer()),
            decode("render_geometry.json", RenderGeometryV1.serializer()), NativeScriptJsonCodec.decode(required("script.json")),
            lines(required("events_5min.jsonl"), "events_5min.jsonl") { line, name ->
                NativeScriptJsonCodec.decodeValue(CapturedDecision.serializer(), line, name)
            },
            lines(required("telemetry_5min.jsonl"), "telemetry_5min.jsonl") { line, name ->
                NativeScriptJsonCodec.decodeComplete(MixTelemetryV3.serializer(), line, name)
            },
            optional("analysis_out.json", TrackAnalysisV3.serializer()), optional("analysis_in.json", TrackAnalysisV3.serializer()),
            optional("flags_snapshot.json", CapturedFlags.serializer()), optional("taste_snapshot.json", TasteSnapshot.serializer()),
        )
        validateIdentities(contract)
        validateSummary(required("analysis_summary.json"), contract)
        return contract
    }

    fun replay(bundle: Path): BundleDecisionTrace = replay(load(bundle))

    fun replay(contract: RenderContract): BundleDecisionTrace {
        validateIdentities(contract)
        val script = contract.script
        requireCore(ScriptExecutionGuard.validateFrozen(script, contract.plan), "captured script")
        val capabilities = FxCapability.entries.toSet()
        val matches = mutableListOf<Pair<String, TransitionScriptV1>>()
        val outcomes = mutableListOf<String>()
        var compiledCount = 0
        for (noStem in listOf(false, true)) {
            if (noStem && (!contract.plan.hasNoStemVariant || contract.recipe.noStemSteps.isEmpty())) continue
            val variant = if (noStem) "no_stem" else "normal"
            val result = try {
                RecipeInterpreter.compileFrozen(recipe = contract.recipe, plan = contract.plan, geometry = contract.geometry,
                    outRegion = script.outRegion, inRegion = script.inRegion, stems = script.stems,
                    useNoStemVariant = noStem, availableCapabilities = capabilities, dropWindowMs = script.dropWindowMs)
            } catch (error: IllegalArgumentException) {
                throw BundleReplayException(ReplayErrorCode.CORE_REFUSAL, "Frozen compiler rejected $variant: ${error.message}", error)
            }
            when (result) {
                is MixOutcome.Ok -> {
                    compiledCount++
                    outcomes += "$variant:compiled"
                    if (result.value == script) matches += variant to result.value
                }
                is MixOutcome.Refused -> outcomes += "$variant:${result.reason}:${result.detail}"
                is MixOutcome.Failed -> outcomes += "$variant:${result.kind}:${result.message}"
            }
        }
        if (matches.isEmpty()) fail(if (compiledCount == 0) ReplayErrorCode.CORE_REFUSAL else ReplayErrorCode.SCRIPT_MISMATCH,
            "Captured script has no exact frozen compiler match (${outcomes.joinToString("; ")})")
        val compiled = matches.first().second // Every member equals the entire captured script.
        val coverage = ReducerReplayCoverage(false, null, 0,
            "Flag debug summaries omit ticks, full MixEvent inputs, initial MixState and MixMachineConfig; exact reducer replay is unavailable.")
        return BundleDecisionTrace(engine = contract.manifest.engine, planId = contract.plan.planId, generation = contract.plan.generation,
            manifest = contract.manifest,
            capturedScriptSha256 = contract.manifest.entries.getValue("script.json").sha256
                ?: fail(ReplayErrorCode.INVALID_MANIFEST, "Missing captured script hash"),
            compiledScriptSha256 = FlagBundleReader.sha256(NativeScriptJsonCodec.encode(compiled).toByteArray(Charsets.UTF_8)),
            matchingVariants = matches.map { it.first }, compilerCapabilities = FxCapability.entries.toList(),
            decisions = contract.decisions, telemetry = contract.telemetry, compiledScript = compiled,
            reducerReplayComplete = false, reducerCoverage = coverage,
            limitations = listOf(coverage.reason,
                "The native capability mask and selected no-stem bit are not captured. Full core capabilities are reported and every exact variant match is retained.",
                "External asset/model bytes, historical planner/taste inputs, decoded PCM identity and device/native bit-exact parity are not established."),
            warnings = contract.manifest.warnings.toList())
    }

    /** Explicit caller PCM only. Captured device paths are never opened or forwarded.
     * Output is staged and independently inspected before atomically publishing a WAV.
     */
    fun render(bundle: Path, output: Path, backend: RenderBackend, deckAPcm: Path?, deckBPcm: Path?, expectedSha256: String? = null): RenderReplayResult =
        render(bundle, output, backend, deckAPcm, deckBPcm, expectedSha256, emptyList())

    fun render(bundle: Path, output: Path, backend: RenderBackend, deckAPcm: Path?, deckBPcm: Path?,
        expectedSha256: String? = null, stemPcm: List<StemPcmInput>): RenderReplayResult {
        if (expectedSha256 != null && !expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) fail(ReplayErrorCode.INPUT, "Expected PCM SHA-256 must contain 64 hex digits")
        val contract = load(bundle)
        // This exact comparison includes the ORIGINAL device path declarations, before any override.
        val trace = replay(contract)
        val mapped = linkedMapOf<Pair<DeckTarget, StemKind>, Path>()
        for (input in stemPcm) {
            if (!input.path.isAbsolute) fail(ReplayErrorCode.INPUT, "--stem-pcm paths must be absolute owner paths")
            if (mapped.put(input.deck to input.stem, input.path) != null) fail(ReplayErrorCode.INPUT,
                "Duplicate --stem-pcm mapping: ${input.deck}:${input.stem}")
        }
        val needed = contract.script.stems.map { it.deck to it.stem }.toSet()
        if ((mapped.keys - needed).isNotEmpty()) fail(ReplayErrorCode.INPUT, "Unknown --stem-pcm mappings: ${mapped.keys - needed}")
        if ((needed - mapped.keys).isNotEmpty()) fail(ReplayErrorCode.AUDIO_UNAVAILABLE,
            "Missing explicit --stem-pcm mappings: ${needed - mapped.keys}; captured paths are not host inputs")
        for ((deck, refs) in contract.script.stems.groupBy { it.deck }) {
            if (refs.all { it.stem == StemKind.OTHER }) fail(ReplayErrorCode.AUDIO_UNAVAILABLE,
                "$deck OTHER-only stems cannot activate native mixing; the captured script needs a real separated base stem")
        }
        val script = contract.script
        fun spec(region: AudioRegionRef, stem: StemKind?, path: Path?, option: String): PcmSpec {
            val frames = if (region.deck == DeckTarget.OUT) contract.geometry.out.frameCount else contract.geometry.inbound.frameCount
            return PcmSpec(region, stem, requirePcm(path, frames, option), frames)
        }
        val inputs = listOf(spec(script.outRegion, null, deckAPcm, "--deck-a-pcm"),
            spec(script.inRegion, null, deckBPcm, "--deck-b-pcm")) + script.stems.map {
            spec(if (it.deck == DeckTarget.OUT) script.outRegion else script.inRegion, it.stem,
                mapped.getValue(it.deck to it.stem), "--stem-pcm ${it.deck}:${it.stem}")
        }
        if (inputs.sumOf { it.frames.toLong() * 8 } > OwnerPcmStaging.MAX_TOTAL_BYTES) fail(ReplayErrorCode.LIMIT_EXCEEDED,
            "Owner PCM exceeds the 512 MiB aggregate protocol limit")
        val protectedInputs = listOf(bundle) + inputs.map { it.path }
        fun checkOutput(stagedPcm: List<Path> = emptyList()) {
            if ((protectedInputs + stagedPcm).any { sameFile(it, output) }) fail(ReplayErrorCode.INPUT, "WAV output must not overwrite bundle or dry/stem PCM (including aliases)")
        }
        checkOutput()
        val g = contract.geometry
        val horizon = maxOf(g.out.knotFrames.last() / script.sampleRateHz,
            g.inbound.knotFrames.last() / script.sampleRateHz, g.out.stopAtMixSec, g.inbound.stopAtMixSec,
            script.envelopes.maxOfOrNull { it.timesSec.last() } ?: 0.0)
        val expectedFrames = (horizon * script.sampleRateHz).roundToLong()
        if (expectedFrames <= 0 || expectedFrames > (OwnerPcmStaging.MAX_BYTES - 44) / 4) fail(ReplayErrorCode.LIMIT_EXCEEDED,
            "Canonical render exceeds the bounded WAV protocol")
        val target = output.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent)
        Files.createDirectories(parent)
        val work = Files.createTempDirectory(parent, ".djmix-render-")
        try {
            val staged = work.resolve("render.wav")
            val stagedInputs = inputs.mapIndexed { index, input ->
                val path = work.resolve("input-$index.f32")
                val hashes = OwnerPcmStaging.stage(input.path, path, input.frames.toLong() * 8)
                path to inputReceipt(input, hashes, contract.manifest.assetSha256)
            }
            // Only local PCM refs change. Track/source timeline, geometry, stems and automation stay frozen.
            val patched = script.copy(outRegion = script.outRegion.copy(pcmPath = stagedInputs[0].first.toString()),
                inRegion = script.inRegion.copy(pcmPath = stagedInputs[1].first.toString()),
                stems = script.stems.mapIndexed { index, stem -> stem.copy(pcmPath = stagedInputs[index + 2].first.toString()) })
            val executionJson = NativeScriptJsonCodec.encode(patched)
            if (executionJson.toByteArray(Charsets.UTF_8).size > 16 * 1024 * 1024) fail(ReplayErrorCode.LIMIT_EXCEEDED, "Execution script exceeds text protocol limit")
            backend.render(RenderRequest(executionJson, staged, work))
            val stagedPcm = stagedInputs.map { it.first }
            if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS) || (protectedInputs + stagedPcm).any { sameFile(it, staged) }) {
                fail(ReplayErrorCode.INVALID_AUDIO, "Backend must produce a regular staged WAV independent of bundle and input PCM")
            }
            val inspected = WavPcmSummary.summarize(staged)
            if (inspected.sampleRateHz != contract.script.sampleRateHz) fail(ReplayErrorCode.INVALID_AUDIO, "Produced WAV rate differs from script.sampleRateHz")
            if (inspected.sampleCount / 2 != expectedFrames) fail(ReplayErrorCode.INVALID_AUDIO,
                "Produced WAV has ${inspected.sampleCount / 2} frames; native canonical protocol requires $expectedFrames")
            val matched = expectedSha256?.equals(inspected.pcmSha256, ignoreCase = true)
            if (matched != false) {
                checkOutput(stagedPcm)
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            val receipts = stagedInputs.map { it.second }
            val verifiedKeys = receipts.flatMap { it.verifiedCapturedAssetSha256.keys }.toSet()
            return RenderReplayResult(trace, inspected.copy(outputWav = target), expectedSha256, matched,
                localPcmOverrides = true, inputPcm = receipts,
                unverifiedCapturedAssetHashKeys = (contract.manifest.assetSha256.keys - verifiedKeys).sorted())
        } finally {
            Files.walk(work).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    fun encodeTrace(trace: BundleDecisionTrace): String = NativeScriptJsonCodec.json.encodeToString(BundleDecisionTrace.serializer(), trace) + "\n"

    private fun requirePcm(path: Path?, frames: Int, option: String): Path {
        val value = path?.toAbsolutePath()?.normalize() ?: fail(ReplayErrorCode.AUDIO_UNAVAILABLE, "$option is required; captured PCM paths are not host inputs")
        val bytes = frames.toLong() * 8
        if (bytes <= 0 || bytes > OwnerPcmStaging.MAX_BYTES) fail(ReplayErrorCode.LIMIT_EXCEEDED, "$option exceeds the 256 MiB PCM protocol limit")
        if (!Files.isRegularFile(value) || !Files.isReadable(value) || Files.size(value) != bytes) fail(ReplayErrorCode.AUDIO_UNAVAILABLE,
            "$option must be readable regular interleaved stereo f32le PCM with exactly $frames aligned frames")
        return value
    }

    private data class PcmSpec(val region: AudioRegionRef, val stem: StemKind?, val path: Path, val frames: Int)

    private fun inputReceipt(input: PcmSpec, hashes: OwnerPcmStaging.Digests, captured: Map<String, String>): ReplayPcmInput {
        val deck = input.region.deck.name.lowercase()
        val base = "prepared_${deck}_${input.stem?.name?.lowercase() ?: "region"}"
        val verified = linkedMapOf<String, String>()
        for ((key, actual) in listOf("${base}_left_f32le" to hashes.left, "${base}_right_f32le" to hashes.right)) {
            val expected = captured[key] ?: continue
            if (!expected.equals(actual, ignoreCase = true)) fail(ReplayErrorCode.BAD_HASH, "Owner PCM differs from captured prepared channel: $key")
            verified[key] = actual
        }
        val identity = when (verified.size) {
            2 -> "captured_prepared_channels_verified"
            1 -> "owner_provided_partially_verified"
            else -> "owner_provided_unverified"
        }
        return ReplayPcmInput(input.region.deck, input.stem, input.region.trackId, input.region.startMs, input.region.endMs,
            input.region.sampleRateHz, input.frames, hashes.interleaved, hashes.left, hashes.right,
            capturedAssetIdentity = identity, verifiedCapturedAssetSha256 = verified)
    }

    private fun sameFile(a: Path, b: Path): Boolean = a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize() ||
        (Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b))

    private fun validateIdentities(c: RenderContract) {
        val p = c.plan
        val m = c.manifest
        if (m.schema != 1 || m.engine != ENGINE_VERSION || m.trigger != "Flag") fail(ReplayErrorCode.UNSUPPORTED_VERSION, "Unsupported Flag manifest")
        if (p.planId.isBlank() || m.planId != p.planId || m.generation != p.generation || m.outTrackId != p.outTrackId || m.inTrackId != p.inTrackId ||
            c.script.planId != p.planId || c.script.generation != p.generation || c.script.outRegion.trackId != p.outTrackId ||
            c.script.inRegion.trackId != p.inTrackId || c.recipe.id != p.recipeId || c.script.renderGeometry != c.geometry ||
            c.outAnalysis?.trackId?.let { it != p.outTrackId } == true || c.inAnalysis?.trackId?.let { it != p.inTrackId } == true
        ) fail(ReplayErrorCode.IDENTITY_MISMATCH, "Manifest/plan/recipe/script/geometry/analysis identity mismatch")
        for (row in c.decisions) {
            if (row.ts < 0 || row.generation < 0 || row.event == MixEventKind.TICK) fail(ReplayErrorCode.INVALID_JSON, "Invalid debug summary; actual Flag rings exclude ticks")
            if (row.planId == p.planId && (row.generation != p.generation || row.outId != p.outTrackId || row.inId != p.inTrackId)) {
                fail(ReplayErrorCode.IDENTITY_MISMATCH, "Captured decision identity mismatch")
            }
        }
        for (row in c.telemetry) {
            if (row.engine != ENGINE_VERSION || row.schema != 3) fail(ReplayErrorCode.UNSUPPORTED_VERSION, "Unsupported telemetry version")
            if (row.timestampMs < 1_000_000_000_000L || row.generation < 0 || row.state !in MixStateKind.entries.map { it.name }) {
                fail(ReplayErrorCode.INVALID_JSON, "Invalid persisted telemetry epoch/state/generation")
            }
            if (row.planId == p.planId && (row.generation != p.generation || row.outId != p.outTrackId || row.inId != p.inTrackId ||
                (row.recipeId != null && row.recipeId != p.recipeId))) fail(ReplayErrorCode.IDENTITY_MISMATCH, "Captured telemetry identity mismatch")
        }
    }

    private fun validateSummary(text: String, c: RenderContract) {
        val summary = NativeScriptJsonCodec.objectValue(text, "analysis_summary.json")
        if (summary.keys != setOf("engine", "planId", "recipeId", "out", "in") || summary["engine"] != JsonPrimitive(ENGINE_VERSION) ||
            summary["planId"] != JsonPrimitive(c.plan.planId) || summary["recipeId"] != JsonPrimitive(c.plan.recipeId)
        ) fail(ReplayErrorCode.IDENTITY_MISMATCH, "Invalid analysis summary engine/identity/fields")
        fun check(key: String, analysis: TrackAnalysisV3?) {
            val value = summary.getValue(key)
            if (analysis == null) {
                if (value != JsonNull) fail(ReplayErrorCode.IDENTITY_MISMATCH, "Summary has $key without full analysis")
                return
            }
            val expected = buildJsonObject {
                put("trackId", analysis.trackId); put("durationMs", analysis.durationMs); put("audibleEndMs", analysis.audibleEndMs)
                put("analysisLevel", analysis.analysisLevel.name); put("bpm", analysis.bpm); put("gridConfidence", analysis.gridConfidence)
                put("keyCamelot", analysis.keyCamelot?.let { JsonPrimitive(it) } ?: JsonNull)
                put("beatsCount", analysis.beatsMs.size); put("downbeatsCount", analysis.downbeatsMs.size)
                put("structureSections", analysis.structure.sections.size); put("chorusFamilies", analysis.structure.chorusFamilies.size)
                put("integratedLufs", analysis.integratedLufs); put("modelVersions", JsonObject(analysis.modelVersions.mapValues { JsonPrimitive(it.value) }))
                put("usable", analysis.isUsable)
            }
            if (value != expected) fail(ReplayErrorCode.IDENTITY_MISMATCH, "Summary $key disagrees with full analysis")
        }
        check("out", c.outAnalysis); check("in", c.inAnalysis)
    }

    private fun <T> lines(text: String, name: String, decode: (String, String) -> T): List<T> {
        val rows = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (rows.size > 20_000) fail(ReplayErrorCode.LIMIT_EXCEEDED, "$name exceeds retained/live ring limit")
        return rows.mapIndexed { index, line -> decode(line, "$name:${index + 1}") }
    }

    private fun <T> requireCore(outcome: MixOutcome<T>, name: String): T = when (outcome) {
        is MixOutcome.Ok -> outcome.value
        is MixOutcome.Refused -> fail(ReplayErrorCode.CORE_REFUSAL, "$name: ${outcome.reason}: ${outcome.detail}")
        is MixOutcome.Failed -> fail(ReplayErrorCode.CORE_REFUSAL, "$name: ${outcome.kind}: ${outcome.message}")
    }
    private fun fail(code: ReplayErrorCode, message: String): Nothing = throw BundleReplayException(code, message)
}
