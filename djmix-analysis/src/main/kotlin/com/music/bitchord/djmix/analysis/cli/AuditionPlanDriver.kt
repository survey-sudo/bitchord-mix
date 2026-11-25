package com.music.bitchord.djmix.analysis.cli

import com.music.bitchord.djmix.analysis.StemSeparatorJvm
import com.music.bitchord.djmix.analysis.AnalysisPipeline
import com.music.bitchord.djmix.core.contracts.*
import com.music.bitchord.djmix.core.planner.BuiltInRecipeCatalog
import com.music.bitchord.djmix.core.planner.PairPlanner
import com.music.bitchord.djmix.core.planner.PlannerRequest
import com.music.bitchord.djmix.core.recipes.RecipeInterpreter
import com.music.bitchord.djmix.core.recipes.RecipeRegistry
import com.music.bitchord.djmix.core.recipes.ScriptExecutionGuard
import com.music.bitchord.djmix.core.recipes.frozenCopy
import com.music.bitchord.playback.djmix.CanonicalGeometryBuilder
import com.music.bitchord.playback.djmix.PlaybackPlanConcretizer
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.loopmix.PlaybackGeometryQualification
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.choreo.ChoreoCaps
import com.music.bitchord.playback.smart.choreo.PlaybackChoreoPreparation
import com.music.bitchord.playback.smart.choreo.DjLevel as PlaybackDjLevel
import kotlinx.serialization.json.*
import java.io.File
import java.io.PrintStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.system.exitProcess

/** Production shared materialization or exact playback-frozen replay, with the sole core compiler. */
object AuditionPlanDriver {
    @JvmStatic fun main(args: Array<String>) { exitProcess(run(args)) }

    fun run(args: Array<String>, output: PrintStream = System.out, errors: PrintStream = System.err): Int = try {
        val options = cliOptions(args, setOf("help", "plan-only", "assume-stems"))
        if ("help" in options) {
            output.println("AuditionPlanDriver --out-analysis A.json --in-analysis B.json --out-pcm A.f32 --in-pcm B.f32 --out script.json " +
                "[--plan-only --style SUBTLE|CLUB|FESTIVAL --recipe S1..F11|AUTO --seed N --position-ms N] " +
                "[--frozen-plan playback.plan.json --frozen-script playback.script.json --out-stems A.stems.json --in-stems B.stems.json]. " +
                "[--model-dir DIR for fresh execution with complete production analysis/maps]. " +
                "V3-only input supports planning or playback-frozen replay.")
        } else export(options, output)
        0
    } catch (failure: Exception) {
        errors.println("AuditionPlanDriver: ${failure.message ?: failure::class.simpleName}")
        1
    }

    private data class Deck(
        val analysis: TrackAnalysisV3, val pcm: WavReader.Stereo, val path: File,
        val production: AnalysisPipeline.ProductionAnalysis? = null,
    )
    private data class LoadedStems(val manifest: StemManifest, val files: Map<StemKind, File>)
    private data class Materialization(
        val plan: TransitionPlanV1, val geometry: RenderGeometryV1,
        val outRegion: AudioRegionRef, val inRegion: AudioRegionRef,
    )

    private fun <T> requireOk(result: MixOutcome<T>, operation: String): T = when (result) {
        is MixOutcome.Ok -> result.value
        is MixOutcome.Refused -> error("$operation refused ${result.reason}: ${result.detail}")
        is MixOutcome.Failed -> error("$operation failed ${result.kind}: ${result.message}")
    }

    private fun export(options: Map<String, String>, output: PrintStream) {
        val known = setOf("out-analysis", "in-analysis", "out-pcm", "in-pcm", "out", "out-plan", "out-meta", "out-stems", "in-stems",
            "sample-rate", "style", "recipe", "seed", "position-ms", "taste-weights", "frozen-plan", "frozen-script", "plan-only", "assume-stems", "model-dir")
        require(options.keys.all { it in known }) { "Unknown option: ${options.keys.first { it !in known }}" }
        require((options["sample-rate"] ?: "48000").toInt() == 48_000) { "Canonical audition requires 48000 Hz" }
        require("assume-stems" !in options || "plan-only" in options) { "--assume-stems is only valid with --plan-only" }
        val needsProduction = "plan-only" !in options && "frozen-script" !in options
        val modelDirectory = options["model-dir"]?.let(::File)
        require(!needsProduction || modelDirectory != null) {
            "Fresh geometry requires full production analysis/maps: supply --model-dir, or use --plan-only or playback --frozen-plan/--frozen-script; V3 cannot reconstruct that evidence"
        }
        val out = readDeck(options, "out", modelDirectory.takeIf { needsProduction })
        val inn = readDeck(options, "in", modelDirectory.takeIf { needsProduction })
        val frozen = options["frozen-plan"]?.let { auditionJson.decodeFromString(TransitionPlanV1.serializer(), File(it).readText()) }
        val style = options["style"]?.let { DjLevel.valueOf(it.uppercase()) } ?: frozen?.djLevel ?: DjLevel.SUBTLE
        val requested = (options["recipe"] ?: frozen?.recipeId ?: "AUTO").uppercase()
        require(!requested.startsWith("AUTO-") || requested == "AUTO-${style.name}") { "$requested conflicts with --style ${style.name}" }
        val recipe = if (requested == "AUTO" || requested.startsWith("AUTO-")) null else
            checkNotNull(RecipeRegistry.byId(requested)) { "Unknown RecipeV2 id '$requested'" }
        val catalog = BuiltInRecipeCatalog.mergeOverrides(RecipeRegistry.all()).filter { recipe == null || it.id == recipe.id }
        require(catalog.isNotEmpty()) { "Requested recipe is disabled or absent" }
        var plan = frozen ?: requireOk(PairPlanner.plan(PlannerRequest(
            generation = (options["seed"] ?: "1").toLong(), outTrack = out.analysis, inTrack = inn.analysis,
            outTitle = out.analysis.trackId, inTitle = inn.analysis.trackId, djLevel = style,
            positionMs = (options["position-ms"] ?: "0").toLong(), preferNoStemVariant = "assume-stems" !in options,
            tasteWeights = options["taste-weights"]?.let { auditionJson.decodeFromString<Map<String, Double>>(File(it).readText()) } ?: emptyMap(),
        ), catalog), "planner")
        require(plan.outTrackId == out.analysis.trackId && plan.inTrackId == inn.analysis.trackId && plan.djLevel == style &&
            (recipe == null || plan.recipeId == recipe.id)) { "Frozen plan track, level or requested recipe identity differs" }
        options["seed"]?.let { require(it.toLong() == plan.generation) { "Frozen generation differs from --seed" } }
        val frozenRecipe = checkNotNull(RecipeRegistry.byId(plan.recipeId)) { "Frozen recipe is unavailable" }.frozenCopy()
        require(plan.requiredStems.isEmpty() || plan.requiredStems == frozenRecipe.requirements.requiredStems) { "Frozen requiredStems differ from the recipe" }
        val scriptFile = File(options.required("out")).absoluteFile
        val planFile = File(options["out-plan"] ?: "${scriptFile.path}.plan.json")
        val metaFile = File(options["out-meta"] ?: "${scriptFile.path}.meta.json")
        val playback = options["frozen-script"]?.let {
            require(frozen != null) { "--frozen-script requires --frozen-plan" }
            requireOk(ScriptExecutionGuard.validateFrozen(auditionJson.decodeFromString(TransitionScriptV1.serializer(), File(it).readText()), plan), "playback frozen guard")
        }
        if ("plan-only" in options) {
            writeJsonAtomically(planFile, auditionJson.encodeToString(TransitionPlanV1.serializer(), plan))
            writeJsonAtomically(metaFile, buildJsonObject {
                put("planningOnly", true); put("geometryAvailable", playback != null)
                put("stemWindows", stemWindows(plan, playback, out, inn))
            }.toString())
            output.println("planned ${plan.recipeId}; geometry ${if (playback == null) "unavailable" else "playback-frozen"}; wrote ${planFile.path}")
            return
        }
        val materialized = if (playback == null) materialize(plan, frozenRecipe, out, inn, options) else null
        if (materialized != null) plan = materialized.plan
        val geometry = checkNotNull(playback?.renderGeometry ?: materialized?.geometry)
        val outRegion = bindRegion(checkNotNull(playback?.outRegion ?: materialized?.outRegion), geometry.out, out,
            File(scriptFile.parentFile, "${scriptFile.nameWithoutExtension}.out.f32"))
        val inRegion = bindRegion(checkNotNull(playback?.inRegion ?: materialized?.inRegion), geometry.inbound, inn,
            File(scriptFile.parentFile, "${scriptFile.nameWithoutExtension}.in.f32"))
        val loaded = mapOf(DeckTarget.OUT to loadStems(options["out-stems"], out), DeckTarget.IN to loadStems(options["in-stems"], inn))
        val complete = plan.requiredStems.all { requirement ->
            val set = loaded[requirement.deck] ?: return@all false
            val setup = if (requirement.deck == DeckTarget.OUT) geometry.out else geometry.inbound
            val bounds = FrozenCoordinates.sourceBounds(setup, plan.overlapDurationMs / 1000.0, 48_000)
            requirement.stem in set.files && set.manifest.windowFirstFrame!! <= bounds.first && set.manifest.windowEndFrame!! >= bounds.second
        }
        val noStem = plan.requiredStems.isEmpty() || !complete
        require(!noStem || plan.requiredStems.isEmpty() || (plan.hasNoStemVariant && frozenRecipe.noStemSteps.isNotEmpty())) { "Frozen recipe has no no-stem variant" }
        val stems = if (noStem) emptyList() else plan.requiredStems.map { requirement ->
            val deck = if (requirement.deck == DeckTarget.OUT) out else inn
            val region = if (requirement.deck == DeckTarget.OUT) outRegion else inRegion
            val setup = if (requirement.deck == DeckTarget.OUT) geometry.out else geometry.inbound
            val source = WavReader.readStereoPcm(loaded.getValue(requirement.deck)!!.files.getValue(requirement.stem), 48_000)
            val target = File(scriptFile.parentFile, "${scriptFile.nameWithoutExtension}.${requirement.deck}.${requirement.stem}.f32")
            writeSlice(source, setup, target)
            StemRegionRef(requirement.deck, requirement.stem, deck.analysis.trackId, region.startMs, region.endMs, target.absolutePath, 48_000)
        }
        val compiled = requireOk(RecipeInterpreter.compileFrozen(frozenRecipe, plan, geometry, outRegion, inRegion, stems, noStem), "compileFrozen")
        val guarded = requireOk(ScriptExecutionGuard.validateFrozen(compiled, plan), "canonical execution guard")
        val script = Json.parseToJsonElement(auditionJson.encodeToString(TransitionScriptV1.serializer(), guarded)).jsonObject.toMutableMap()
        script["engine"] = JsonPrimitive(ENGINE_VERSION); script["recipeId"] = JsonPrimitive(plan.recipeId)
        script["djLevel"] = JsonPrimitive(style.name); script["stemMode"] = JsonPrimitive(if (noStem) "no-stem" else "scnet")
        script["provenance"] = JsonPrimitive(if (playback != null) "playback-frozen-geometry/core-compileFrozen; host PCM identity verified"
            else "shared-production-geometry/core-compileFrozen; full production analysis/maps; host PCM identity verified")
        script["assetHashes"] = buildJsonObject {
            for (region in listOf(guarded.outRegion, guarded.inRegion)) put(region.pcmPath!!, PcmIdentity.file(File(region.pcmPath)))
            for (stem in guarded.stems) put(stem.pcmPath!!, PcmIdentity.file(File(stem.pcmPath)))
        }
        val meta = metadata(plan, guarded, out, inn)
        writeJsonAtomically(scriptFile, JsonObject(script).toString())
        writeJsonAtomically(planFile, auditionJson.encodeToString(TransitionPlanV1.serializer(), plan))
        writeJsonAtomically(metaFile, meta.toString())
        output.println("exported ${plan.recipeId} using ${if (playback != null) "playback frozen" else "shared production"} geometry; ${if (noStem) "same-recipe no-stem" else "SCNet"}")
    }

    private fun readDeck(options: Map<String, String>, key: String, modelDirectory: File?): Deck {
        val analysis = auditionJson.decodeFromString(TrackAnalysisV3.serializer(), File(options.required("$key-analysis")).readText())
        val path = File(options.required("$key-pcm")).absoluteFile
        val pcm = WavReader.readStereoPcm(path, 48_000)
        require(analysis.schema == 3 && analysis.isUsable && analysis.trackId.isNotBlank()) { "$key analysis is not usable" }
        require(analysis.contentHash.matches(Regex("[0-9a-f]{64}")) && PcmIdentity.file(path) == analysis.contentHash) { "$key PCM SHA-256 differs from analysis contentHash" }
        require(abs(pcm.frames / 48.0 - analysis.durationMs) <= 1.0) { "$key PCM duration differs from analysis" }
        val production = modelDirectory?.let { ProductionAnalysisLoader.analyze(pcm, analysis.trackId, it) }
        if (production != null) {
            require(production.exported.contentHash == analysis.contentHash && production.exported.durationMs == analysis.durationMs) {
                "$key fresh production analysis has a different PCM identity"
            }
            require(production.analysis.isUsable && production.analysis.quality == com.music.bitchord.playback.smart.TrackAnalysis.QUALITY_FULL &&
                production.songMap != null && !production.songMap.partial && !production.songMap.coarse) {
                "$key production evidence is incomplete; fresh geometry requires a complete analysis/map, otherwise use playback-frozen replay"
            }
        }
        return Deck(production?.exported ?: analysis, pcm, path, production)
    }

    private fun materialize(
        frozen: TransitionPlanV1, recipe: RecipeV2, out: Deck, inn: Deck, options: Map<String, String>,
    ): Materialization {
        val outProduction = checkNotNull(out.production) { "Full OUT production analysis/maps are required; V3 is insufficient" }
        val inProduction = checkNotNull(inn.production) { "Full IN production analysis/maps are required; V3 is insufficient" }
        val level = PlaybackDjLevel.valueOf(frozen.djLevel.name)
        val configuration = PlaybackChoreoPreparation.Configuration(
            enabled = true, overridePhraseScripts = true, retimeEntry = true,
            seed = frozen.generation, geometryLevel = level,
        )
        val history = PlaybackChoreoPreparation.History(moves = emptyList(), recipes = emptyList())
        val input = PlaybackPlanConcretizer.Input(
            outId = frozen.outTrackId, inId = frozen.inTrackId,
            outAnalysis = outProduction.analysis, inAnalysis = inProduction.analysis,
            outSongMap = outProduction.songMap, inSongMap = inProduction.songMap,
            sungOut = null, sungIn = null, djLevel = level, sampleRate = 48_000,
            positionSec = (options["position-ms"] ?: "0").toLong() / 1000.0,
            albumOrder = false, stemsExpected = false, preferNoStemVariant = true, caps = ChoreoCaps.ALL,
        )
        val concrete = checkNotNull(PlaybackPlanConcretizer.buildConcreteFromPlanV1(
            frozen, input, outProduction.analysis.duration, inProduction.analysis.duration, configuration, history,
        )) { "Production concretization returned no plan" }
        val plan = checkNotNull(concrete.plan) {
            "Production concretization refused ${concrete.refusalReason}: ${concrete.fallbackReason}"
        }
        val runtimeFrozen = checkNotNull(concrete.transitionPlanV1)
        require(runtimeFrozen.planId == frozen.planId && runtimeFrozen.generation == frozen.generation &&
            runtimeFrozen.recipeId == recipe.id && runtimeFrozen.frozenExitMs == frozen.frozenExitMs &&
            runtimeFrozen.frozenEntryMs == frozen.frozenEntryMs && concrete.frozenRecipe?.id == recipe.id) {
            "Production concretization changed frozen plan/recipe/source identity"
        }
        if (plan.outLoops && plan.inLoops) {
            val nominal = LoopMix.qualifyCrown(plan)
            require(nominal.accepted) { "Production nominal crown refused: ${nominal.refusal}" }
        }
        val clock = LoopMix.Clock(plan)
        val knots = PlaybackChoreoPreparation.knotsFor(
            plan, clock, 48_000, outProduction.analysis, inProduction.analysis, concrete.straightRateMode, level,
        )
        val handoffRefusal = PlaybackGeometryQualification.qualifyHandoffKnots(plan, clock, 48_000, knots)
        require(handoffRefusal == null) { "Production handoff geometry refused: $handoffRefusal" }
        val crown = PlaybackGeometryQualification.qualifyCrownKnots(plan, clock, 48_000, knots)
        require(crown.accepted) { "Production crown geometry refused: ${crown.refusal}" }
        val bounds = PlaybackGeometryQualification.decodeRequirements(
            plan, 48_000, outProduction.analysis, inProduction.analysis, concrete.straightRateMode, level,
        )
        val outPcm = decodeRegion(out.pcm, bounds.outFrom, bounds.outTo)
        val inPcm = decodeRegion(inn.pcm, bounds.inFrom, bounds.inTo)
        val coverage = PlaybackGeometryQualification.preparedCoverageRefusal(
            plan, 48_000, outPcm, inPcm, outProduction.analysis, bounds,
        )
        require(coverage == null && PlaybackGeometryQualification.coversHandoffPcm(plan, 48_000, knots, outPcm, inPcm)) {
            "Production prepared PCM refused: ${coverage ?: "handoff coverage"}"
        }
        val inLevel = PlaybackGeometryQualification.regionalIncomingLevel(plan, outPcm, inPcm, 48_000, 1f)
        val geometry = CanonicalGeometryBuilder.canonicalGeometry(
            plan, clock, knots, outPcm, inPcm, 48_000, inLevel,
            highQuality = true, outStopAtSec = PlaybackChoreoPreparation.outgoingStopAt(plan, clock),
        )
        val (outRef, inRef) = CanonicalGeometryBuilder.canonicalRegionRefs(
            runtimeFrozen.outTrackId, runtimeFrozen.inTrackId, outPcm, inPcm, 48_000, geometry,
        )
        return Materialization(runtimeFrozen, geometry, outRef, inRef)
    }

    /** Host PCM I/O adapter: preserve actual frame origins and never pad absent source audio. */
    private fun decodeRegion(audio: WavReader.Stereo, fromSec: Double, toSec: Double): LoopRegion {
        require(fromSec.isFinite() && toSec.isFinite() && fromSec >= 0.0 && toSec > fromSec)
        val first = (fromSec * audio.sampleRate).toLong().coerceAtMost(audio.frames.toLong()).toInt()
        val end = ceil(toSec * audio.sampleRate).toLong().coerceAtMost(audio.frames.toLong()).toInt()
        require(end > first) { "Requested production region has no decoded PCM" }
        return LoopRegion(audio.left.copyOfRange(first, end), audio.right.copyOfRange(first, end), first.toDouble() / audio.sampleRate)
    }

    private fun bindRegion(region: AudioRegionRef, setup: DeckRenderSetupV1, deck: Deck, target: File): AudioRegionRef {
        require(region.trackId == deck.analysis.trackId && region.sampleRateHz == 48_000) { "Playback region identity differs from analyzed deck" }
        return region.copy(pcmPath = writeSlice(deck.pcm, setup, target).absolutePath)
    }

    private fun writeSlice(audio: WavReader.Stereo, setup: DeckRenderSetupV1, file: File): File {
        val first = (setup.regionStartSec * audio.sampleRate).roundToLong()
        require(abs(first - setup.regionStartSec * audio.sampleRate) <= 0.5 && first >= 0 && first + setup.frameCount <= audio.frames) { "Decoded PCM does not cover frozen region" }
        WavReader.writeStereoPcm(file, WavReader.Stereo(audio.left.copyOfRange(first.toInt(), first.toInt() + setup.frameCount),
            audio.right.copyOfRange(first.toInt(), first.toInt() + setup.frameCount), audio.sampleRate))
        return file
    }

    private fun loadStems(path: String?, deck: Deck): LoadedStems? {
        if (path == null) return null
        val manifest = auditionJson.decodeFromString(StemManifest.serializer(), File(path).readText())
        if (!manifest.available) return null
        require(manifest.trackId == deck.analysis.trackId && manifest.sourcePcmSha256 == deck.analysis.contentHash) { "Stem manifest source PCM identity differs" }
        val productionModelHashes = com.music.bitchord.playback.smart.StemModels.candidates.flatMap { it.files }.map { it.sha256 }.filter { it.isNotBlank() }
        require(manifest.modelSha256 in productionModelHashes && manifest.windowFirstFrame != null && manifest.windowEndFrame != null &&
            manifest.windowFirstFrame >= 0 && manifest.windowEndFrame > manifest.windowFirstFrame && manifest.windowEndFrame <= deck.pcm.frames &&
            manifest.windowEndFrame.toLong() - manifest.windowFirstFrame <= 48_000L * StemSeparatorJvm.MAX_REGION_SECONDS) { "Stem manifest has no trustworthy model/window identity" }
        require(manifest.stems.map { it.stem }.distinct().size == manifest.stems.size &&
            manifest.requiredStems.toSet() == manifest.stems.map { it.stem }.toSet()) { "Stem requirements differ from supplied assets" }
        return LoadedStems(manifest, manifest.stems.associate { stem ->
            require(stem.stem in StemSeparatorJvm.KINDS && stem.sampleRateHz == 48_000 && stem.channels == 2 && stem.frameCount == deck.pcm.frames &&
                stem.startMs == 0L && stem.endMs == deck.analysis.durationMs) { "Stem PCM is not aligned to the analyzed deck" }
            val file = File(stem.pcmPath).absoluteFile
            require(file.isFile && file.length() == deck.pcm.frames.toLong() * 8 && PcmIdentity.file(file) == stem.sha256) { "Stem PCM length/hash differs from manifest" }
            StemKind.valueOf(stem.stem) to file
        })
    }

    private fun stemWindows(plan: TransitionPlanV1, script: TransitionScriptV1?, out: Deck, inn: Deck): JsonObject = buildJsonObject {
        val geometry = script?.renderGeometry
        if (geometry != null) for ((name, target, setup, deck) in listOf(
            WindowDeck("out", DeckTarget.OUT, geometry.out, out),
            WindowDeck("in", DeckTarget.IN, geometry.inbound, inn))) {
            val required = plan.requiredStems.filter { it.deck == target }
            if (required.isEmpty()) continue
            val (first, end) = FrozenCoordinates.sourceBounds(setup, plan.overlapDurationMs / 1000.0, 48_000)
            require(first >= 0 && end <= deck.pcm.frames) { "Frozen overlap exceeds decoded source PCM" }
            put(name, buildJsonObject {
                put("startMs", first * 1000L / 48_000); put("endMs", (end * 1000L + 47_999) / 48_000)
                put("paddingMs", plan.barDurationMs); put("firstFrame", first); put("endFrame", end)
                put("requiredStems", JsonArray(required.map { JsonPrimitive(it.stem.name) }))
            })
        }
    }

    private data class WindowDeck(val name: String, val target: DeckTarget, val setup: DeckRenderSetupV1, val deck: Deck)

    private fun metadata(plan: TransitionPlanV1, script: TransitionScriptV1, out: Deck, inn: Deck): JsonObject {
        val geometry = checkNotNull(script.renderGeometry)
        val release = plan.overlapDurationMs / 1000.0
        fun vocal(deck: Deck, setup: DeckRenderSetupV1): JsonArray = JsonArray((0..ceil(release * 50).toInt()).map { i ->
            val mixSec = minOf(release, i / 50.0)
            val sourceSec = FrozenCoordinates.sourceSeconds(setup, mixSec, 48_000)
            val value = deck.analysis.vocalPresence50fps.getOrNull((sourceSec * 50.0).toInt())
                ?: error("Syvenu evidence does not cover the frozen overlap; metadata export unavailable")
            require(value.isFinite() && value in 0f..1f)
            buildJsonArray { add(mixSec); add(value) }
        })
        fun gains(target: DeckTarget): JsonArray = JsonArray((0..ceil(release * 50).toInt()).map { i ->
            val t = minOf(release, i / 50.0)
            fun lane(kind: EnvelopeKind): Double {
                val l = script.envelopes.firstOrNull { it.deck == target && it.kind == kind } ?: return 1.0
                return sampleLane(l, t)
            }
            buildJsonArray { add(t); add(lane(EnvelopeKind.GAIN) * lane(EnvelopeKind.STEM_VOCALS)) }
        })
        return buildJsonObject {
            put("engine", ENGINE_VERSION); put("planId", plan.planId); put("generation", plan.generation); put("recipeId", plan.recipeId)
            put("level", plan.djLevel.name); put("canonicalPlaybackGeometry", true)
            put("exitSec", plan.frozenExitMs / 1000.0); put("entrySec", plan.frozenEntryMs / 1000.0)
            put("transitionStartSec", 0.0); put("transitionEndSec", release)
            put("swapSec", (plan.swapDownbeatMs - plan.frozenExitMs) / 1000.0)
            put("bpmOut", out.analysis.bpm); put("bpmIn", inn.analysis.bpm)
            put("vocalTimeline", "mix-seconds"); put("vocalFps", 50)
            put("vocalOut", vocal(out, geometry.out)); put("vocalIn", vocal(inn, geometry.inbound))
            put("gainOut", gains(DeckTarget.OUT)); put("gainIn", gains(DeckTarget.IN))
            put("plannerAccepted", true)
            put("placementVocalOut", JsonArray(out.analysis.vocalPresence50fps.map { JsonPrimitive(it) }))
            put("placementVocalIn", JsonArray(inn.analysis.vocalPresence50fps.map { JsonPrimitive(it) }))
            put("placementMethod", "source Syvenu curves; planner acceptance retained separately")
            put("sourcePcmSha256", buildJsonObject { put("out", out.analysis.contentHash); put("in", inn.analysis.contentHash) })
            put("stemWindows", stemWindows(plan, script, out, inn))
            put("placementRulesHit", JsonArray(plan.placementReasons.map { JsonPrimitive(it) }))
        }
    }

    internal fun sampleLane(lane: EnvelopeLane, t: Double): Double {
        if (t <= lane.timesSec.first()) return lane.values.first().toDouble()
        for (i in 1 until lane.timesSec.size) if (t <= lane.timesSec[i]) {
            val dt = lane.timesSec[i] - lane.timesSec[i - 1]
            val u = if (dt <= 0) 1.0 else (t - lane.timesSec[i - 1]) / dt
            val a = lane.values[i - 1].toDouble(); val b = lane.values[i].toDouble()
            return if (lane.exponential && a > 0 && b > 0) kotlin.math.exp(kotlin.math.ln(a) + (kotlin.math.ln(b) - kotlin.math.ln(a)) * u) else a + (b - a) * u
        }
        return lane.values.last().toDouble()
    }
}
