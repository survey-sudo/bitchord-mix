package com.music.bitchord.djmix.render

import com.music.bitchord.djmix.core.contracts.*
import com.music.bitchord.djmix.core.machine.*
import com.music.bitchord.djmix.core.recipes.RecipeInterpreter
import com.music.bitchord.djmix.render.cli.ReplayCli
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToLong
import kotlin.test.*

class BundleReplayTest {
    private val json = NativeScriptJsonCodec.json

    @Test
    fun nativeCodecRoundTripsExactGeometryFxAndStemTypes() {
        val f = FlagFixture()
        val region = f.script.outRegion
        val script = f.script.copy(
            fxParams = ScriptFxParams(
                sweep = SweepFxParams(enabled = true, startMixSec = 0.0, endMixSec = 8.125),
                incomingSweep = SweepFxParams(enabled = true, deck = DeckTarget.IN,
                    isHighPass = false, startHz = 20_000f, endHz = 20f, startMixSec = 1.25, endMixSec = 12.5),
                outSvfQ = 0.9234567f, inSvfQ = 1.1234567f,
            ),
            stems = listOf(StemRegionRef(DeckTarget.OUT, StemKind.VOCALS, "out", region.startMs,
                region.endMs, "/private/device/vocals.f32", f.sr)),
        )
        val encoded = NativeScriptJsonCodec.encode(script)
        assertEquals(script, NativeScriptJsonCodec.decode(encoded))
        assertEquals(encoded, NativeScriptJsonCodec.encode(NativeScriptJsonCodec.decode(encoded)))
        val fields = json.parseToJsonElement(encoded).jsonObject
        assertEquals(script.renderGeometry?.inbound?.knotPositions,
            json.decodeFromJsonElement(RenderGeometryV1.serializer(), fields.getValue("renderGeometry")).inbound.knotPositions)
        assertEquals(0.9234567f, NativeScriptJsonCodec.decode(encoded).fxParams.outSvfQ)
        assertFailure(ReplayErrorCode.CORE_REFUSAL) {
            NativeScriptJsonCodec.encode(script.copy(fxParams = ScriptFxParams(outSvfQ = Float.NaN)))
        }
    }

    @Test
    fun rejectsBadHashEvenWhenByteLengthIsUnchanged() = inTemp { dir ->
        val f = FlagFixture()
        val bytes = f.payloads().toMutableMap()
        bytes["script.json"] = bytes.getValue("script.json").toString(Charsets.UTF_8)
            .replace("flag-S5", "evil-S5").toByteArray()
        assertFailure(ReplayErrorCode.BAD_HASH) { BundleReplayEngine().load(f.write(dir, bytes)) }
    }

    @Test
    fun rejectsAvailableEntryMissingFromZip() = inTemp { dir ->
        val f = FlagFixture()
        assertFailure(ReplayErrorCode.MISSING_ENTRY) {
            BundleReplayEngine().load(f.write(dir, f.payloads() - "recipe.json"))
        }
    }

    @Test
    fun unavailableFrozenRecipeIsNotDefaultedToRegistry() = inTemp { dir ->
        val f = FlagFixture()
        val data = f.payloads().toMutableMap()
        data.remove("recipe.json")
        f.rehash(data)
        assertFailure(ReplayErrorCode.MISSING_ENTRY) { BundleReplayEngine().load(f.write(dir, data)) }
    }

    @Test
    fun capturedFixtureRecompilesAndTracesDeterministically() = inTemp { dir ->
        val f = FlagFixture()
        val path = f.write(dir)
        val engine = BundleReplayEngine()
        val first = engine.replay(path)
        val second = engine.replay(path)
        assertEquals(first, second)
        assertEquals(engine.encodeTrace(first), engine.encodeTrace(second))
        assertEquals(f.script, first.compiledScript)
        assertEquals(listOf("normal", "no_stem"), first.matchingVariants)
        assertEquals(listOf(MixEventKind.PLAN_FROZEN, MixEventKind.REGIONS_READY), first.decisions.map { it.event })
        assertEquals(TelemetryEventType.MIX_PLAN_FROZEN, first.telemetry.single().eventType)
        assertFalse(first.reducerReplayComplete)
        assertTrue(first.limitations.any { "ticks" in it })
        assertEquals(64, first.compiledScriptSha256.length)
    }

    @Test
    fun suppliedFullReducerInputsUseCanonicalCoreReplay() {
        val f = FlagFixture()
        val initial = MixState.Idle(generation = 0, djLevel = DjLevel.SUBTLE)
        val events = listOf(
            MixEvent.NextKnown(PairKey("out", "in"), positionMs = 10_000, djLevel = DjLevel.SUBTLE, isPlaying = true),
            MixEvent.AnalysisReady("out", AnalysisLevel.FULL, generation = 1),
            MixEvent.AnalysisReady("in", AnalysisLevel.FULL, generation = 1),
            MixEvent.PlanFrozen(f.plan, generation = 1),
            MixEvent.RegionsReady(f.plan.planId, 1),
            MixEvent.RendererPrepared(f.plan.planId, 1, usedNoStemVariant = false),
            MixEvent.Tick(positionMs = 54_000, isPlaying = true),
            MixEvent.Tick(positionMs = 60_000, isPlaying = true),
        )
        val config = MixMachineConfig.DEFAULT
        val steps = replayReducer(initial, events, config)
        assertEquals(MixMachine.replay(initial, events, config), steps)
        assertEquals(steps, replayReducer(initial, events, config))
        assertEquals(MixStateKind.ARMED, steps[6].nextState.stateKind)
        assertEquals(MixStateKind.RUNNING, steps[7].nextState.stateKind)
        assertTrue(steps[7].effects.any { it is MixEffect.Start })
    }

    @Test
    fun invalidOrEmptyScriptIsRejectedWithoutDefaults() {
        for (text in listOf("", "{}", "[]", "null", "{broken")) {
            assertFailure(ReplayErrorCode.INVALID_JSON) { NativeScriptJsonCodec.decode(text) }
        }
        val f = FlagFixture()
        val encoded = NativeScriptJsonCodec.json.encodeToJsonElement(TransitionScriptV1.serializer(), f.script).jsonObject
        assertFailure(ReplayErrorCode.INVALID_JSON) { NativeScriptJsonCodec.decode(JsonObject(encoded - "sampleRateHz").toString()) }
    }

    @Test
    fun emptyNonZipAndMissingFilesHaveTypedErrors() = inTemp { dir ->
        assertFailure(ReplayErrorCode.INPUT) { BundleReplayEngine().load(dir.resolve("missing.zip")) }
        val path = dir.resolve("empty.zip")
        Files.write(path, byteArrayOf())
        assertFailure(ReplayErrorCode.INVALID_ZIP) { BundleReplayEngine().load(path) }
        ZipOutputStream(Files.newOutputStream(path)).use { }
        assertFailure(ReplayErrorCode.MISSING_ENTRY) { BundleReplayEngine().load(path) }
    }

    @Test
    fun rejectsMalformedJsonlInsteadOfSkippingRows() = inTemp { dir ->
        val f = FlagFixture()
        val data = f.payloads().toMutableMap()
        data["events_5min.jsonl"] = "{bad}\n".toByteArray()
        f.rehash(data)
        assertFailure(ReplayErrorCode.INVALID_JSON) { BundleReplayEngine().load(f.write(dir, data)) }
    }

    @Test
    fun rejectsUnknownManifestVersion() = inTemp { dir ->
        val f = FlagFixture()
        val data = f.payloads().toMutableMap()
        val original = data.getValue("manifest.json").toString(Charsets.UTF_8)
        data["manifest.json"] = original.replace("\"schema\":1", "\"schema\":9").toByteArray()
        assertFailure(ReplayErrorCode.UNSUPPORTED_VERSION) { BundleReplayEngine().load(f.write(dir, data)) }
    }

    @Test
    fun rejectsUnexpectedArchivePaths() = inTemp { dir ->
        val f = FlagFixture()
        val data = f.payloads() + ("../escape" to byteArrayOf(1))
        assertFailure(ReplayErrorCode.UNSAFE_ENTRY) { BundleReplayEngine().load(f.write(dir, data)) }
    }

    @Test
    fun rejectsRehashedScriptThatDoesNotMatchFrozenCompiler() = inTemp { dir ->
        val f = FlagFixture()
        val data = f.payloads().toMutableMap()
        data["script.json"] = f.encode(TransitionScriptV1.serializer(), f.script.copy(
            loudness = f.script.loudness.copy(targetLufs = -13f),
        ))
        f.rehash(data)
        assertFailure(ReplayErrorCode.SCRIPT_MISMATCH) { BundleReplayEngine().replay(f.write(dir, data)) }
    }

    @Test
    fun validatesLogicalAudioCaptureHashWithoutOpeningExternalPaths() = inTemp { dir ->
        val f = FlagFixture()
        val data = f.payloads().toMutableMap()
        data["mix_capture.pcm"] = byteArrayOf(0, 1, 2, 3)
        f.rehash(data)
        assertEquals(f.script, BundleReplayEngine().replay(f.write(dir, data)).compiledScript)
        data["mix_capture.pcm"] = byteArrayOf(3, 2, 1, 0)
        assertFailure(ReplayErrorCode.BAD_HASH) { BundleReplayEngine().load(f.write(dir, data)) }
    }

    @Test
    fun cliHelpUsageFileOutputAndInputProtection() = inTemp { dir ->
        val f = FlagFixture()
        val bundle = f.write(dir)
        val output = dir.resolve("trace.json")
        val printed = StringBuilder()
        val errors = StringBuilder()
        fun run(vararg args: String) = ReplayCli.run(arrayOf(*args), { printed.append(it) }, { errors.append(it) })
        assertEquals(0, run("--help"))
        assertTrue("replay --bundle" in printed.toString())
        assertEquals(2, run())
        assertEquals(2, run("replay", "--bundle"))
        assertEquals(2, run("replay", "--bundle", bundle.toString(), "--bundle", bundle.toString()))
        assertEquals(2, run("replay", "--bundle", bundle.toString(), "--out", bundle.toString()))
        assertEquals(0, run("replay", "--bundle", bundle.toString(), "--out", output.toString()))
        assertEquals(BundleReplayEngine().encodeTrace(BundleReplayEngine().replay(bundle)), Files.readString(output))
        assertEquals(2, run("replay", "--bundle", dir.resolve("absent.zip").toString()))
    }

    @Test
    fun cliReturnsOneForBundleIntegrityFailure() = inTemp { dir ->
        val f = FlagFixture()
        val data = f.payloads() - "recipe.json"
        val errors = StringBuilder()
        assertEquals(1, ReplayCli.run(arrayOf("replay", "--bundle", f.write(dir, data).toString()), {}, { errors.append(it) }))
        assertTrue("MISSING_ENTRY" in errors.toString())
    }

    @Test
    fun cliInjectedBackendChecksPcmHashAndReportsMissingEventPayload() = inTemp { dir ->
        val fixture = FlagFixture()
        val bundle = fixture.write(dir)
        val pcm = hostPcm(dir, fixture)
        val expectedFile = dir.resolve("expected.wav")
        writeWav(expectedFile, fixture.sr, fixture.renderFrames)
        val expected = WavPcmSummary.summarize(expectedFile).pcmSha256
        val backend = RenderBackend { request ->
            writeWav(request.outputWav, fixture.sr, fixture.renderFrames)
        }
        val output = dir.resolve("replay.wav")
        val printed = StringBuilder()
        val errors = StringBuilder()
        fun run(hash: String) = ReplayCli.run(
            arrayOf("replay", bundle.toString(), "--out", output.toString(), "--deck-a-pcm", pcm.first.toString(),
                "--deck-b-pcm", pcm.second.toString(), "--verify-sha256", hash, "--json"),
            { printed.append(it) }, { errors.append(it) }, backend,
        )
        assertEquals(0, run(expected))
        val receipt = json.parseToJsonElement(printed.toString()).jsonObject
        assertEquals(JsonPrimitive("render"), receipt["operation"])
        assertEquals(JsonPrimitive(false), receipt["reducerReplayComplete"])
        assertEquals(JsonNull, receipt["reducerEquivalent"])
        assertEquals(expected, WavPcmSummary.summarize(output).pcmSha256)
        assertEquals(1, run("b".repeat(64)))
        assertEquals(expected, WavPcmSummary.summarize(output).pcmSha256, "hash mismatch must not replace a previous WAV")
    }

    @Test
    fun archiveDoesNotInventAnUnproducedReducerEventDialect() = inTemp { dir ->
        val f = FlagFixture()
        val data = f.payloads().toMutableMap()
        data["events_5min.jsonl"] = """{"mixEvent":{"type":"user_pause"},"initialState":{"type":"idle"}}""".toByteArray()
        f.rehash(data)
        assertFailure(ReplayErrorCode.INVALID_JSON) { BundleReplayEngine().load(f.write(dir, data)) }
    }

    @Test
    fun coreGuardRefusalIsPreservedForMalformedGeometry() {
        val f = FlagFixture()
        val script = f.script.copy(renderGeometry = f.geometry.copy(out = f.geometry.out.copy(frameCount = 0)))
        val core = assertIs<MixOutcome.Refused>(com.music.bitchord.djmix.core.recipes.ScriptExecutionGuard.validate(script))
        val error = assertFailsWith<BundleReplayException> { NativeScriptJsonCodec.encode(script) }
        assertEquals(ReplayErrorCode.CORE_REFUSAL, error.code)
        assertTrue(error.message.orEmpty().contains(core.detail))
    }

    @Test
    fun invalidCapturedGeometryNeverReachesAPermissiveBackend() = inTemp { dir ->
        val f = FlagFixture()
        val broken = f.geometry.copy(out = f.geometry.out.copy(frameCount = 0))
        val data = f.payloads().toMutableMap()
        data["render_geometry.json"] = f.encode(RenderGeometryV1.serializer(), broken)
        data["script.json"] = f.encode(TransitionScriptV1.serializer(), f.script.copy(renderGeometry = broken))
        f.rehash(data)
        var called = false
        val backend = RenderBackend { called = true; writeWav(it.outputWav, f.sr, f.renderFrames) }
        assertFailure(ReplayErrorCode.CORE_REFUSAL) { BundleReplayEngine().render(f.write(dir, data), dir.resolve("out.wav"), backend, null, null) }
        assertFalse(called)
        assertFalse(Files.exists(dir.resolve("out.wav")))
    }

    @Test
    fun exactCompilerComparisonDistinguishesNormalAndNoStemDecisions() = inTemp { dir ->
        for (noStem in listOf(false, true)) {
            val f = FlagFixture(distinctVariants = true, useNoStem = noStem)
            val trace = BundleReplayEngine().replay(f.write(dir))
            assertEquals(listOf(if (noStem) "no_stem" else "normal"), trace.matchingVariants)
            assertEquals(f.script, trace.compiledScript)
            assertEquals(null, trace.reducerCoverage.equivalent)
            assertEquals(0, trace.reducerCoverage.replayedEventCount)
        }
    }

    @Test
    fun missingPcmAndStemMappingsNeverUseCapturedPaths() = inTemp { dir ->
        val f = FlagFixture()
        val backend = RenderBackend { error("backend must not run") }
        assertFailure(ReplayErrorCode.AUDIO_UNAVAILABLE) {
            BundleReplayEngine().render(f.write(dir), dir.resolve("out.wav"), backend, null, null)
        }
        val pcm = hostPcm(dir, f)
        Files.write(pcm.first, byteArrayOf(0))
        assertFailure(ReplayErrorCode.AUDIO_UNAVAILABLE) {
            BundleReplayEngine().render(f.write(dir), dir.resolve("out.wav"), backend, pcm.first, pcm.second)
        }
        val region = f.script.outRegion
        val stem = StemRegionRef(DeckTarget.OUT, StemKind.VOCALS, "out", region.startMs, region.endMs, "/private/device/stem.f32")
        val script = f.script.copy(stems = listOf(stem))
        val data = f.payloads().toMutableMap()
        data["script.json"] = f.encode(TransitionScriptV1.serializer(), script)
        f.rehash(data)
        assertFailure(ReplayErrorCode.AUDIO_UNAVAILABLE) {
            BundleReplayEngine().render(f.write(dir, data), dir.resolve("out.wav"), backend, pcm.first, pcm.second)
        }
    }

    @Test
    fun backendMustCreateAnActualCompleteWavBeforePublishing() = inTemp { dir ->
        val f = FlagFixture()
        val pcm = hostPcm(dir, f)
        val target = dir.resolve("out.wav")
        val original = byteArrayOf(1, 2, 3)
        Files.write(target, original)
        val backends = listOf(RenderBackend { }, RenderBackend { Files.writeString(it.outputWav, "{\"trace\":true}") },
            RenderBackend { writeWav(it.outputWav, f.sr, 10) }, RenderBackend { writeWav(it.outputWav, 44_100, f.renderFrames) })
        for (backend in backends) {
            assertFailure(ReplayErrorCode.INVALID_AUDIO) { BundleReplayEngine().render(f.write(dir), target, backend, pcm.first, pcm.second) }
            assertContentEquals(original, Files.readAllBytes(target))
        }
    }

    @Test
    fun traceModeCannotMasqueradeAsRequestedWav() = inTemp { dir ->
        val f = FlagFixture()
        val out = dir.resolve("not-a-trace.wav")
        assertEquals(2, ReplayCli.run(arrayOf("replay", "--bundle", f.write(dir).toString(), "--out", out.toString()), {}, {}))
        assertFalse(Files.exists(out))
    }

    @Test
    fun wavInspectionHashesPcmOnlyAndMeasuresSamplePeak() = inTemp { dir ->
        val path = dir.resolve("samples.wav")
        writeWav(path, 48_000, 2)
        val pcm = byteArrayOf(0, 0x80.toByte(), 0xff.toByte(), 0x7f, 0, 0, 0, 0)
        RandomAccessFile(path.toFile(), "rw").use { it.seek(44); it.write(pcm) }
        val summary = WavPcmSummary.summarize(path)
        assertEquals(4L, summary.sampleCount)
        assertEquals(1.0, summary.samplePeak)
        assertEquals(FlagBundleReader.sha256(pcm), summary.pcmSha256)
        assertNotEquals(FlagBundleReader.sha256(Files.readAllBytes(path)), summary.pcmSha256)
    }

    @Test
    fun renderCannotOverwriteBundleOrCallerPcm() = inTemp { dir ->
        val f = FlagFixture()
        val bundle = f.write(dir)
        val original = Files.readAllBytes(bundle)
        val pcm = hostPcm(dir, f)
        val backend = RenderBackend { error("backend must not run") }
        for (target in listOf(bundle, pcm.first, pcm.second)) {
            assertFailure(ReplayErrorCode.INPUT) { BundleReplayEngine().render(bundle, target, backend, pcm.first, pcm.second) }
        }
        assertContentEquals(original, Files.readAllBytes(bundle))
        assertEquals(f.geometry.out.frameCount.toLong() * 8, Files.size(pcm.first))
    }

    @Test
    fun duplicateJsonKeysAndUnboundedNestingAreTypedErrors() {
        assertFailure(ReplayErrorCode.INVALID_JSON) { NativeScriptJsonCodec.objectValue("""{"x":1,"\u0078":2}""", "duplicate") }
        assertFailure(ReplayErrorCode.INVALID_JSON) { NativeScriptJsonCodec.objectValue("[".repeat(129) + "0" + "]".repeat(129), "deep") }
        val script = json.encodeToJsonElement(TransitionScriptV1.serializer(), FlagFixture().script).jsonObject
        assertFailure(ReplayErrorCode.INVALID_JSON) {
            NativeScriptJsonCodec.decode(JsonObject(script + ("sampleRateHz" to JsonPrimitive("48000"))).toString())
        }
    }

    @Test
    fun zipSizeAndManifestIdentityCannotBeBypassed() = inTemp { dir ->
        val f = FlagFixture()
        val data = f.payloads().toMutableMap()
        data["events_5min.jsonl"] = ByteArray(16 * 1024 * 1024 + 1) { 32 }
        assertFailure(ReplayErrorCode.LIMIT_EXCEEDED) { BundleReplayEngine().load(f.write(dir, data)) }
        val identity = f.payloads().toMutableMap()
        val manifest = json.decodeFromString(FlagManifest.serializer(), identity.getValue("manifest.json").toString(Charsets.UTF_8))
        identity["manifest.json"] = f.encode(FlagManifest.serializer(), manifest.copy(inTrackId = "other"))
        assertFailure(ReplayErrorCode.IDENTITY_MISMATCH) { BundleReplayEngine().load(f.write(dir, identity)) }
    }

    @Test
    fun capturedFailureTelemetryRemainsReplayableDiagnosticEvidence() = inTemp { dir ->
        val f = FlagFixture()
        val data = f.payloads().toMutableMap()
        val row = json.decodeFromString(MixTelemetryV3.serializer(), data.getValue("telemetry_5min.jsonl").toString(Charsets.UTF_8))
        data["telemetry_5min.jsonl"] = f.encode(MixTelemetryV3.serializer(), row.copy(countdownBroken = true))
        f.rehash(data)
        val trace = BundleReplayEngine().replay(f.write(dir, data))
        assertEquals(true, trace.telemetry.single().countdownBroken)
        assertEquals(f.script, trace.compiledScript)
        assertFalse(trace.reducerCoverage.complete)
    }

    private fun hostPcm(dir: Path, f: FlagFixture): Pair<Path, Path> {
        val a = dir.resolve("host-a.f32")
        val b = dir.resolve("host-b.f32")
        RandomAccessFile(a.toFile(), "rw").use { it.setLength(f.geometry.out.frameCount.toLong() * 8) }
        RandomAccessFile(b.toFile(), "rw").use { it.setLength(f.geometry.inbound.frameCount.toLong() * 8) }
        return a to b
    }

    private fun writeWav(path: Path, rate: Int, frames: Int) {
        val bytes = frames * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1.toShort()).putShort(2.toShort()).putInt(rate).putInt(rate * 4)
            .putShort(4.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(bytes)
        Files.newOutputStream(path).use { stream ->
            stream.write(header.array())
            val block = ByteArray(8192)
            var remaining = bytes
            while (remaining > 0) { val count = minOf(remaining, block.size); stream.write(block, 0, count); remaining -= count }
        }
    }

    private fun assertFailure(code: ReplayErrorCode, block: () -> Unit) {
        assertEquals(code, assertFailsWith<BundleReplayException>(block = block).code)
    }

    private fun inTemp(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("djmix-render-test-")
        try { block(directory) } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

/** Constructs the actual writer schema in code, including hashes of the exact stored bytes. */
private class FlagFixture(distinctVariants: Boolean = false, useNoStem: Boolean = false) {
    val sr = 48_000
    private val json = NativeScriptJsonCodec.json
    private val steps = listOf(
        RecipeStepV2.LaneCurve(DeckTarget.OUT, EnvelopeKind.GAIN, points = listOf(
            WaypointV2(StepTimeV2(RecipeAnchor.ZERO), 1f), WaypointV2(StepTimeV2(RecipeAnchor.RELEASE), 0f))),
        RecipeStepV2.LaneCurve(DeckTarget.IN, EnvelopeKind.GAIN, points = listOf(
            WaypointV2(StepTimeV2(RecipeAnchor.ZERO), 0f), WaypointV2(StepTimeV2(RecipeAnchor.RELEASE), 1f))),
    )
    private val noStemSteps = if (!distinctVariants) steps else listOf(steps[0].copy(points = listOf(
        WaypointV2(StepTimeV2(RecipeAnchor.ZERO), 1f), WaypointV2(StepTimeV2(RecipeAnchor.RELEASE, param = 0.0, offsetSec = -8.0), 0.75f),
        WaypointV2(StepTimeV2(RecipeAnchor.RELEASE), 0f))), steps[1])
    val recipe = RecipeV2(id = "S5", name = "Fixture straight blend", level = DjLevel.SUBTLE,
        family = RecipeFamily.BLEND, abruptness = AbruptnessClass.SMOOTH,
        lengthOptionsBars = listOf(8), steps = steps, noStemSteps = noStemSteps)
    val plan = TransitionPlanV1(planId = "flag-S5", generation = 1, outTrackId = "out", inTrackId = "in",
        djLevel = DjLevel.SUBTLE, geometry = TransitionGeometry.STRAIGHT, frozenExitMs = 60_000,
        frozenEntryMs = 4_250, swapDownbeatMs = 68_000, overlapDurationMs = 16_000,
        barDurationMs = 2_000, recipeId = "S5", recipeName = recipe.name)
    private val out = DeckRenderSetupV1(59.0, 4_000_000, 0, 0, null,
        listOf(0.0, 864_000.125), listOf(48_000.0, 912_000.125), false, 18.0, 0.025, 1f, null)
    private val inbound = DeckRenderSetupV1(4.0, 4_000_000, 0, 0, null,
        listOf(0.0, 816_000.0), listOf(12_000.0, 828_000.0), true, 17.05, 0.025, 1f, 21.25)
    val geometry = RenderGeometryV1(out, inbound, 17.0, 21.25, 0.5, 4.75, true)
    val renderFrames = 864_000
    private fun region(deck: DeckTarget): AudioRegionRef {
        val d = if (deck == DeckTarget.OUT) out else inbound
        fun ms(sec: Double) = (sec * 1000).roundToLong()
        return AudioRegionRef(deck, if (deck == DeckTarget.OUT) "out" else "in", ms(d.regionStartSec),
            ms(d.regionStartSec + d.frameCount.toDouble() / sr), sr, "/private/device/$deck.f32",
            ms(d.prerollSec), ms(d.stopAtMixSec), d.fadeOutTrackSec?.let(::ms), d.levelGain, d.runOn)
    }
    val script = assertIs<MixOutcome.Ok<TransitionScriptV1>>(RecipeInterpreter.compileFrozen(
        recipe, plan, geometry, region(DeckTarget.OUT), region(DeckTarget.IN), useNoStemVariant = useNoStem,
    )).value

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)

    fun payloads(): Map<String, ByteArray> {
        val data = linkedMapOf(
            "plan.json" to encode(TransitionPlanV1.serializer(), plan),
            "recipe.json" to encode(RecipeV2.serializer(), recipe),
            "render_geometry.json" to encode(RenderGeometryV1.serializer(), geometry),
            "script.json" to encode(TransitionScriptV1.serializer(), script),
            "events_5min.jsonl" to listOf(
                CapturedDecision(1_800_000_000_000L, MixEventKind.PLAN_FROZEN, MixStateKind.PREPARING, 1, plan.planId, "out", "in"),
                CapturedDecision(1_800_000_000_001L, MixEventKind.REGIONS_READY, MixStateKind.PREPARING, 1, plan.planId, "out", "in"),
            ).joinToString("\n", postfix = "\n") { json.encodeToString(CapturedDecision.serializer(), it) }.toByteArray(),
            "telemetry_5min.jsonl" to encode(MixTelemetryV3.serializer(), MixTelemetryV3(
                timestampMs = 1_800_000_000_000L, eventType = TelemetryEventType.MIX_PLAN_FROZEN,
                planId = plan.planId, generation = 1, state = "PREPARING", outId = "out", inId = "in", recipeId = "S5",
            )),
            "analysis_summary.json" to """{"engine":"3.0.0","planId":"flag-S5","recipeId":"S5","out":null,"in":null}""".toByteArray(),
        )
        rehash(data)
        return data
    }

    fun rehash(data: MutableMap<String, ByteArray>) {
        val entries = (FlagBundleReader.textNames + "audioCapture").associateWith { key ->
            val name = if (key == "audioCapture") data.keys.singleOrNull { it.startsWith("mix_capture.") } else key
            val bytes = name?.let(data::get)
            if (bytes == null) FlagEntryStatus(false, null, 0, null, "not_supplied")
            else FlagEntryStatus(true, name, bytes.size.toLong(), FlagBundleReader.sha256(bytes), null)
        }
        data["manifest.json"] = encode(FlagManifest.serializer(), FlagManifest(
            1, "3.0.0", "Flag", 1_800_000_000_000L, plan.planId, 1, "out", "in",
            entries, emptyMap(), emptyMap(), entries.getValue("recipe.json").sha256, emptyList(),
        ))
    }

    fun write(directory: Path, data: Map<String, ByteArray> = payloads()): Path {
        val path = directory.resolve("flag.zip")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, bytes) in data) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return path
    }
}
