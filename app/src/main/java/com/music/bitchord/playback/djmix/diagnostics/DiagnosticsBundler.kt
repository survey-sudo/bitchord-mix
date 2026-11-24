package com.music.bitchord.playback.djmix.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.music.bitchord.djmix.core.contracts.ENGINE_VERSION
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.playback.smart.AnalysisLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
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
     * trailing newline needed — one line is written per entry). Nullable [plan],
     * [script], [audioCapture] entries are skipped when null.
     */
    fun bundle(
        plan: TransitionPlanV1? = null,
        script: TransitionScriptV1? = null,
        events5min: List<String> = emptyList(),
        telemetry5min: List<String> = emptyList(),
        outAnalysis: TrackAnalysisV3? = null,
        inAnalysis: TrackAnalysisV3? = null,
        audioCapture: File? = null,
    ): File? = runCatching {
        if (!reportsDir.isDirectory) reportsDir.mkdirs()
        val zipFile = File(reportsDir, "${timestamp()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            if (plan != null) {
                zip.putNextEntry(ZipEntry("plan.json"))
                zip.write(json.encodeToString(TransitionPlanV1.serializer(), plan).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (script != null) {
                zip.putNextEntry(ZipEntry("script.json"))
                zip.write(json.encodeToString(TransitionScriptV1.serializer(), script).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("events_5min.jsonl"))
            zip.write(joinLines(events5min).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("telemetry_5min.jsonl"))
            zip.write(joinLines(telemetry5min).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val summary = AnalysisSummary(
                planId = plan?.planId,
                recipeId = plan?.recipeId,
                out = outAnalysis?.let { summarize(it) },
                inbound = inAnalysis?.let { summarize(it) },
            )
            zip.putNextEntry(ZipEntry("analysis_summary.json"))
            zip.write(json.encodeToString(AnalysisSummary.serializer(), summary).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (audioCapture?.isFile == true) {
                val extension = audioCapture.extension.lowercase(Locale.US)
                    .takeIf { it == "wav" || it == "flac" } ?: "wav"
                zip.putNextEntry(ZipEntry("mix_capture.$extension"))
                audioCapture.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        zipFile
    }.onFailure { AnalysisLog.warn("djmix bundle: write failed", it) }
        .getOrNull()

    /** Existing bundles in [reportsDir], newest first. */
    fun listBundles(): List<File> = runCatching {
        reportsDir.listFiles { file -> file.isFile && file.extension == "zip" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }.onFailure { AnalysisLog.warn("djmix bundle: list failed", it) }
        .getOrDefault(emptyList())

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))

    private fun joinLines(lines: List<String>): String =
        buildString {
            for (line in lines) {
                append(line.replace('\n', ' ').trim())
                append('\n')
            }
        }

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
        }.onFailure { AnalysisLog.warn("djmix bundle: share intent failed", it) }
            .getOrNull()

        /** [buildShareIntent] wrapped in a chooser; null when no share intent could be built. */
        fun buildShareChooser(context: Context, zipFile: File): Intent? =
            buildShareIntent(context, zipFile)?.let { Intent.createChooser(it, "Share diagnostics") }
    }
}
