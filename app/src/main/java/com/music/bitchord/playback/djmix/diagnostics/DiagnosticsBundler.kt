package com.music.bitchord.playback.djmix.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.music.bitchord.djmix.core.contracts.ENGINE_VERSION
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.RenderGeometryV1
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.learning.TasteSnapshot
import com.music.bitchord.playback.djmix.RetainedMixContext
import com.music.bitchord.playback.smart.AnalysisLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Per-track summary embedded in a diagnostics bundle as `analysis_summary.json`.
 * Derived purely from [TrackAnalysisV3] so the bundle is self-describing without
 * shipping full analysis payloads.
 */
@Serializable
data class AnalysisSummaryTrack(
    val trackId: String = "",
    val durationMs: Long = 0L,
    val audibleEndMs: Long = 0L,
    val analysisLevel: String = "NONE",
    val bpm: Double = 0.0,
    val gridConfidence: Float = 0f,
    val keyCamelot: String? = null,
    val beatsCount: Int = 0,
    val downbeatsCount: Int = 0,
    val structureSections: Int = 0,
    val chorusFamilies: Int = 0,
    val integratedLufs: Float = -14f,
    val modelVersions: Map<String, String> = emptyMap(),
    val usable: Boolean = false,
)

/** Top-level `analysis_summary.json` payload for a diagnostics bundle. */
@Serializable
data class AnalysisSummary(
    val engine: String = ENGINE_VERSION,
    val planId: String? = null,
    val recipeId: String? = null,
    val out: AnalysisSummaryTrack? = null,
    @SerialName("in") val inbound: AnalysisSummaryTrack? = null,
)

@Serializable
data class BundleEntryStatus(
    val available: Boolean,
    val entry: String? = null,
    val bytes: Long = 0,
    val sha256: String? = null,
    val detail: String? = null,
)

/** Missing values are explicit; an empty ring and an absent frozen payload are different states. */
@Serializable
data class DiagnosticsManifest(
    val schema: Int = 1,
    val engine: String = ENGINE_VERSION,
    val trigger: String = "Flag",
    val createdAtMs: Long,
    val planId: String? = null,
    val generation: Long? = null,
    val outTrackId: String? = null,
    val inTrackId: String? = null,
    val entries: Map<String, BundleEntryStatus>,
    val assetSha256: Map<String, String> = emptyMap(),
    val modelSha256: Map<String, String> = emptyMap(),
    val recipeSha256: String? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * Writes a diagnostics zip bundle (Part B7 / B14 / M8), **only when the owner
 * explicitly taps Flag**. There is no automatic or background call path — M7/M8
 * glue must invoke [bundle] from the Flag action and nothing else.
 *
 * Contents of `djmix-reports/<timestamp>.zip`:
 * - `plan.json` — [TransitionPlanV1] (pretty JSON)
 * - `script.json` — [TransitionScriptV1] (pretty JSON)
 * - `events_5min.jsonl` — caller-supplied app events from the last 5 minutes
 *   (e.g. `MixMachine` state transitions), one JSON object per line
 * - `telemetry_5min.jsonl` — [TelemetryV3Writer.recentLines] snapshot
 * - `analysis_summary.json` — [AnalysisSummary] derived from the pair's analyses
 * - `mix_capture.wav` (or `.flac`, matching the source file) — the 40 s
 *   ring-buffer audio capture supplied by the caller, when available
 *
 * Output dir: callers pass
 * `File(context.getExternalFilesDir(null), "djmix-reports")`, i.e.
 * `Android/data/com.music.bitchord.loopfix/files/djmix-reports/`. [bundle]
 * returns the written [File] (or null when it could not be written) and never
 * throws.
 */
class DiagnosticsBundler(
    val reportsDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Writes one bundle. [events5min] / [telemetry5min] are raw JSONL lines (no
     * trailing newline needed). This blocking compatibility API is for a control/worker lane,
     * never the audio callback. [bundleAsync] provides an explicit I/O-dispatching entry point.
     * Missing payloads are recorded in manifest.json; a non-null payload is never silently skipped.
     */
    fun bundle(
        plan: TransitionPlanV1? = null,
        script: TransitionScriptV1? = null,
        events5min: List<String> = emptyList(),
        telemetry5min: List<String> = emptyList(),
        outAnalysis: TrackAnalysisV3? = null,
        inAnalysis: TrackAnalysisV3? = null,
        audioCapture: File? = null,
        frozenRecipe: RecipeV2? = null,
        renderGeometry: RenderGeometryV1? = null,
        flagsSnapshot: FlagsSnapshot? = null,
        tasteSnapshot: TasteSnapshot? = null,
        assetSha256: Map<String, String> = emptyMap(),
        modelSha256: Map<String, String> = emptyMap(),
        retainedContext: RetainedMixContext? = null,
    ): File? {
        var temporary: File? = null
        return runCatching {
            check(reportsDir.isDirectory || reportsDir.mkdirs()) { "Cannot create reports directory" }
            // Unique even for simultaneous Flag taps with the same injected clock.
            val pending = File.createTempFile("${timestamp()}-", ".zip.part", reportsDir)
            temporary = pending
            val zipFile = File(reportsDir, pending.name.removeSuffix(".part"))
            val actualPlan = plan ?: retainedContext?.transitionPlanV1
            val actualScript = script ?: retainedContext?.transitionScript
            val actualRecipe = frozenRecipe ?: retainedContext?.frozenRecipe
            val actualGeometry = renderGeometry ?: retainedContext?.renderGeometry ?: actualScript?.renderGeometry
            val actualOut = outAnalysis ?: retainedContext?.analysisA
            val actualIn = inAnalysis ?: retainedContext?.analysisB
            val actualAudio = audioCapture ?: retainedContext?.audioCaptureRef
            val actualFlags = flagsSnapshot ?: retainedContext?.flagsSnapshot
            val actualTaste = tasteSnapshot ?: retainedContext?.tasteSnapshot
            val assets = retainedContext?.assetSha256.orEmpty() + assetSha256
            val models = retainedContext?.modelSha256.orEmpty() + modelSha256
            val events = if (events5min.isEmpty()) retainedContext?.recentEvents.orEmpty() else events5min
            val telemetry = if (telemetry5min.isEmpty()) retainedContext?.telemetryLines.orEmpty() else telemetry5min
            val statuses = LinkedHashMap<String, BundleEntryStatus>()
            val warnings = ArrayList<String>()
            if (actualPlan != null && actualScript != null && actualScript.planId != actualPlan.planId) warnings += "script_plan_identity_mismatch"
            if (actualPlan != null && actualRecipe != null && actualRecipe.id != actualPlan.recipeId) warnings += "recipe_plan_identity_mismatch"
            if (actualPlan != null && actualGeometry == null) warnings += "render_geometry_missing"
            if (actualPlan != null && actualScript == null) warnings += "transition_script_missing"
            if (actualPlan != null && (actualOut == null || actualIn == null)) warnings += "track_analysis_missing"
            if (assets.isEmpty()) warnings += "asset_sha_metadata_missing"
            if (models.isEmpty()) warnings += "model_sha_metadata_missing"
            for ((name, sha) in assets + models) if (!sha.matches(Regex("[a-fA-F0-9]{64}"))) warnings += "invalid_sha256:$name"
            FileOutputStream(pending).use { output ->
                ZipOutputStream(output).use { zip ->
                    fun write(name: String, text: String) {
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                        statuses[name] = BundleEntryStatus(true, name, bytes.size.toLong(), sha256(bytes))
                    }
                    fun missing(name: String) { statuses[name] = BundleEntryStatus(false, detail = "not_supplied") }
                    if (actualPlan == null) missing("plan.json") else write("plan.json", json.encodeToString(TransitionPlanV1.serializer(), actualPlan))
                    if (actualRecipe == null) missing("recipe.json") else write("recipe.json", json.encodeToString(RecipeV2.serializer(), actualRecipe))
                    if (actualGeometry == null) missing("render_geometry.json") else write("render_geometry.json", json.encodeToString(RenderGeometryV1.serializer(), actualGeometry))
                    if (actualScript == null) missing("script.json") else write("script.json", json.encodeToString(TransitionScriptV1.serializer(), actualScript))
                    if (actualOut == null) missing("analysis_out.json") else write("analysis_out.json", json.encodeToString(TrackAnalysisV3.serializer(), actualOut))
                    if (actualIn == null) missing("analysis_in.json") else write("analysis_in.json", json.encodeToString(TrackAnalysisV3.serializer(), actualIn))
                    if (actualFlags == null) missing("flags_snapshot.json") else write("flags_snapshot.json", json.encodeToString(FlagsSnapshot.serializer(), actualFlags))
                    if (actualTaste == null) missing("taste_snapshot.json") else write("taste_snapshot.json", json.encodeToString(TasteSnapshot.serializer(), actualTaste))
                    write("events_5min.jsonl", joinLines(events))
                    write("telemetry_5min.jsonl", joinLines(telemetry))
                    if (events.isEmpty()) warnings += "events_ring_empty"
                    if (telemetry.isEmpty()) warnings += "telemetry_ring_empty"
            val summary = AnalysisSummary(
                        planId = actualPlan?.planId,
                        recipeId = actualPlan?.recipeId,
                        out = actualOut?.let { summarize(it) },
                        inbound = actualIn?.let { summarize(it) },
            )
                    write("analysis_summary.json", json.encodeToString(AnalysisSummary.serializer(), summary))
                    if (actualAudio?.isFile == true) {
                        val extension = actualAudio.extension.lowercase(Locale.US).takeIf { it in setOf("wav", "flac", "pcm") } ?: "bin"
                        if (extension == "bin") warnings += "audio_format_unknown:${actualAudio.extension}"
                        val name = "mix_capture.$extension"
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.putNextEntry(ZipEntry(name))
                        val count = DigestInputStream(actualAudio.inputStream(), digest).use { it.copyTo(zip) }
                        zip.closeEntry()
                        statuses["audioCapture"] = BundleEntryStatus(true, name, count, hex(digest.digest()))
                    } else statuses["audioCapture"] = BundleEntryStatus(false, detail = when {
                        actualAudio != null -> "capture_file_missing:${actualAudio.name}"
                        else -> retainedContext?.audioCaptureStatus?.takeIf { it.isNotBlank() && it != "available" } ?: "not_supplied"
                    })
                    val manifest = DiagnosticsManifest(
                        createdAtMs = clock(), planId = actualPlan?.planId, generation = actualPlan?.generation,
                        outTrackId = actualPlan?.outTrackId, inTrackId = actualPlan?.inTrackId,
                        entries = statuses.toMap(), assetSha256 = assets, modelSha256 = models,
                        recipeSha256 = statuses["recipe.json"]?.sha256, warnings = warnings,
                    )
                    write("manifest.json", json.encodeToString(DiagnosticsManifest.serializer(), manifest))
                    zip.finish()
                    zip.flush()
                    output.fd.sync()
                }
            }
            check(pending.renameTo(zipFile)) { "Atomic bundle publication failed" }
            temporary = null
            zipFile
        }.onFailure { error -> runCatching { AnalysisLog.warn("djmix bundle: write failed", error) } }
            .also { temporary?.delete() }.getOrNull()
    }

    /** Explicit Flag action with I/O confined to a worker; retaining a mix never calls this. */
    suspend fun bundleAsync(context: RetainedMixContext, dispatcher: CoroutineDispatcher = Dispatchers.IO): File? =
        withContext(dispatcher) { bundle(retainedContext = context) }

    /** Existing bundles in [reportsDir], newest first. */
    fun listBundles(): List<File> = runCatching {
        reportsDir.listFiles { file -> file.isFile && file.extension == "zip" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }.onFailure { error -> runCatching { AnalysisLog.warn("djmix bundle: list failed", error) } }
        .getOrDefault(emptyList())

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))

    private fun joinLines(lines: List<String>): String =
        buildString {
            for (line in lines) {
                append(line)
                if (!line.endsWith('\n')) append('\n')
            }
        }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun summarize(analysis: TrackAnalysisV3): AnalysisSummaryTrack = AnalysisSummaryTrack(
        trackId = analysis.trackId,
        durationMs = analysis.durationMs,
        audibleEndMs = analysis.audibleEndMs,
        analysisLevel = analysis.analysisLevel.name,
        bpm = analysis.bpm,
        gridConfidence = analysis.gridConfidence,
        keyCamelot = analysis.keyCamelot,
        beatsCount = analysis.beatsMs.size,
        downbeatsCount = analysis.downbeatsMs.size,
        structureSections = analysis.structure.sections.size,
        chorusFamilies = analysis.structure.chorusFamilies.size,
        integratedLufs = analysis.integratedLufs,
        modelVersions = analysis.modelVersions,
        usable = analysis.isUsable,
    )

    companion object {
        /**
         * `ACTION_SEND` intent for sharing [zipFile] (e.g. to Drive), via the app's
         * shared `FileProvider` (`${packageName}.fileprovider`, the same authority
         * used by LyricsShareVideo). Returns null when the provider is unavailable
         * (caller should fall back to saving the zip manually).
         */
        fun buildShareIntent(context: Context, zipFile: File): Intent? = runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "BitChord Automix diagnostics")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.onFailure { error -> runCatching { AnalysisLog.warn("djmix bundle: share intent failed", error) } }
            .getOrNull()

        /** [buildShareIntent] wrapped in a chooser; null when no share intent could be built. */
        fun buildShareChooser(context: Context, zipFile: File): Intent? =
            buildShareIntent(context, zipFile)?.let { Intent.createChooser(it, "Share diagnostics") }
    }
}
