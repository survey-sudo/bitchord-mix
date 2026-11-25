package com.music.bitchord.djmix.analysis.cli

import com.music.bitchord.djmix.analysis.AnalysisConfig
import com.music.bitchord.djmix.analysis.AnalysisPipeline
import com.music.bitchord.djmix.analysis.StemSeparatorJvm
import com.music.bitchord.djmix.analysis.fixtures.FixtureWriter
import kotlinx.serialization.Serializable
import java.io.File
import java.io.PrintStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.system.exitProcess

@Serializable
data class ExportedStem(
    val stem: String,
    val pcmPath: String,
    val sampleRateHz: Int,
    val channels: Int = 2,
    val frameCount: Int,
    val startMs: Long = 0,
    val endMs: Long,
    val sha256: String = "",
)

@Serializable
data class StemManifest(
    val schema: Int = 1,
    val trackId: String,
    val available: Boolean,
    val reason: String = "",
    val modelPath: String? = null,
    val stems: List<ExportedStem> = emptyList(),
    val windowStartMs: Long? = null,
    val windowEndMs: Long? = null,
    val requiredStems: List<String> = emptyList(),
    val sourcePcmSha256: String = "",
    val modelSha256: String? = null,
    val windowFirstFrame: Int? = null,
    val windowEndFrame: Int? = null,
)

internal data class StemWindow(val firstFrame: Int, val endFrame: Int, val startMs: Long, val endMs: Long)

@Serializable
private data class AnalysisExecutionReceipt(
    val schema: Int = 1,
    val trackId: String,
    val inputSha256: String,
    val analysisSha256: String,
    val sampleRateHz: Int,
    val channels: Int,
    val frameCount: Int,
    val beatModelTag: String?,
    val vocalModelTag: String?,
    val beatModelSha256: String?,
    val vocalModelSha256: String?,
    val beatBackend: String?,
    val vocalBackend: String?,
    val inference: String,
    val coverage: String,
    val beatTimesSec: List<Double>,
    val reason: String = "",
)

/** Resolve the required overlap plus optional bar padding into bounded source frames. */
internal fun calculateStemWindow(
    trackFrames: Int,
    sampleRate: Int,
    requestedStartMs: Long,
    requestedEndMs: Long,
    paddingMs: Long = 0L,
): StemWindow {
    require(trackFrames > 0 && sampleRate > 0 && requestedStartMs >= 0L && requestedEndMs > requestedStartMs && paddingMs >= 0L) {
        "Invalid stem window"
    }
    val trackMs = (trackFrames.toLong() * 1000L + sampleRate - 1) / sampleRate
    val startMs = requestedStartMs.coerceAtMost(trackMs)
    require(requestedEndMs <= trackMs) { "Required stem overlap exceeds decoded PCM; missing core audio cannot be clamped" }
    val endMs = requestedEndMs
    require(endMs > startMs) { "Stem window is outside the decoded PCM" }
    val first = (startMs * sampleRate / 1000L).toInt().coerceIn(0, trackFrames)
    val last = ((endMs * sampleRate + 999L) / 1000L).toInt().coerceIn(0, trackFrames)
    val maximumFrames = sampleRate.toLong() * StemSeparatorJvm.MAX_REGION_SECONDS
    val coreFrames = last - first
    require(coreFrames > 0 && coreFrames.toLong() <= maximumFrames) {
        "Required overlap exceeds the ${StemSeparatorJvm.MAX_REGION_SECONDS} second SCNet window cap"
    }
    val requestedPaddingFrames = if (paddingMs >= StemSeparatorJvm.MAX_REGION_SECONDS * 1000L) maximumFrames
        else (paddingMs * sampleRate + 999L) / 1000L
    var leftPadding = minOf(requestedPaddingFrames, first.toLong())
    var rightPadding = minOf(requestedPaddingFrames, (trackFrames - last).toLong())
    val remaining = maximumFrames - coreFrames
    if (leftPadding + rightPadding > remaining) {
        leftPadding = minOf(leftPadding, (remaining + 1L) / 2L)
        rightPadding = minOf(rightPadding, remaining - leftPadding)
        if (leftPadding + rightPadding < remaining) {
            leftPadding = minOf(requestedPaddingFrames, first.toLong(), remaining - rightPadding)
        }
    }
    val boundedFirst = (first - leftPadding).toInt()
    val boundedEnd = (last + rightPadding).toInt()
    return StemWindow(
        firstFrame = boundedFirst,
        endFrame = boundedEnd,
        startMs = boundedFirst.toLong() * 1000L / sampleRate,
        endMs = (boundedEnd.toDouble() * 1000.0 / sampleRate).roundToLong(),
    )
}

/** Testable CLI entry; absent optional models never manufacture separated audio. */
object TrackAnalysisExporter {
    fun run(args: Array<String>, output: PrintStream = System.out, errors: PrintStream = System.err): Int = try {
        val options = cliOptions(args, setOf("stems", "stems-only", "help"))
        if ("help" in options) {
            output.println("TrackAnalysisExporter --input WAV|PCM --out ANALYSIS.json [--input-format wav|pcm_f32 --sample-rate HZ --channels 1|2] " +
                "[--track-id ID --duration SEC --out-pcm MONO.f32 --model-dir DIR] " +
                "[--stems --scnet-model MODEL.onnx --out-stems MANIFEST.json --window-start-ms MS --window-end-ms MS " +
                "--window-padding-ms MS --required-stems VOCALS,DRUMS]; " +
                "invoke the canonical plan driver with --audition-plan-driver followed by its options")
            0
        } else {
            export(options, output, errors)
            0
        }
    } catch (failure: Exception) {
        errors.println("TrackAnalysisExporter: ${failure.message ?: failure::class.simpleName}")
        1
    }

    private fun export(options: Map<String, String>, output: PrintStream, errors: PrintStream) {
        val out = File(options.required("out"))
        val receipt = File("${out.path}.execution.json")
        // Invalidate the previous attempt before validation, decode, model setup, or inference.
        Files.deleteIfExists(receipt.toPath())
        try {
            exportAttempt(options, out, receipt, output, errors)
        } catch (failure: Throwable) {
            try {
                Files.deleteIfExists(receipt.toPath())
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun exportAttempt(options: Map<String, String>, out: File, receipt: File, output: PrintStream, errors: PrintStream) {
        val known = setOf("input", "out", "input-format", "sample-rate", "channels", "track-id", "duration", "out-pcm", "model-dir",
            "stems", "stems-only", "scnet-model", "out-stems", "stem-start-sec", "stem-end-sec",
            "window-start-ms", "window-end-ms", "window-padding-ms", "required-stems")
        require(options.keys.all { it in known }) { "Unknown option: ${options.keys.first { it !in known }}" }
        val input = File(options.required("input"))
        val trackId = options["track-id"] ?: input.name
        require(trackId.isNotBlank()) { "--track-id must not be blank" }
        val format = options["input-format"] ?: "wav"
        val audio = when (format) {
            "wav" -> WavReader.readStereoWav(input)
            "pcm_f32" -> WavReader.readStereoPcm(input, options.required("sample-rate").toInt(), options["channels"]?.toInt() ?: 1)
            else -> error("Unknown input format $format")
        }
        val channels = if (format == "wav") wavChannelCount(input, audio) else options["channels"]?.toInt() ?: 1
        val duration = audio.frames.toDouble() / audio.sampleRate
        options["duration"]?.toDouble()?.let { supplied ->
            require(supplied.isFinite() && supplied > 0 && abs(supplied - duration) <= 1.0 / audio.sampleRate) {
                "--duration must match the decoded PCM; missing samples are never padded"
            }
        }
        if ("stems-only" in options) {
            require("stems" in options) { "--stems-only requires --stems" }
            exportStems(options, audio, trackId, out, errors)
            output.println("wrote stems for ${input.absolutePath}")
            return
        }
        val production = ProductionAnalysisLoader.analyze(audio, trackId, File(options.required("model-dir")))
        val analysisJson = FixtureWriter.toJson(production.exported)
        val analysisSha256 = MessageDigest.getInstance("SHA-256").digest(analysisJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        writeJsonAtomically(out, analysisJson)
        options["out-pcm"]?.let { path ->
            val mono = audio.mono()
            val file = File(path)
            file.parentFile?.mkdirs()
            file.outputStream().buffered().use { stream ->
                val buffer = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (sample in mono) { buffer.clear(); buffer.putFloat(sample); stream.write(buffer.array()) }
            }
        }
        if ("stems" in options) exportStems(options, audio, trackId, out, errors)
        check(PcmIdentity.file(out) == analysisSha256) { "Analysis JSON changed before execution receipt publication" }
        val execution = executionReceipt(production, audio, channels, trackId, analysisSha256)
        writeJsonAtomically(receipt, auditionJson.encodeToString(AnalysisExecutionReceipt.serializer(), execution))
        if (execution.inference != "completed") errors.println("execution evidence unavailable: ${execution.reason}")
        output.println("wrote ${out.absolutePath}")
    }

    private fun executionReceipt(
        production: AnalysisPipeline.ProductionAnalysis,
        audio: WavReader.Stereo,
        channels: Int,
        trackId: String,
        analysisSha256: String,
    ): AnalysisExecutionReceipt {
        val evidence = production.execution
        val beat = evidence?.beat
        val vocal = evidence?.vocal
        val beats = evidence?.beatTimesSec.orEmpty()
        val duration = audio.frames.toDouble() / audio.sampleRate
        val inputSha256 = PcmIdentity.stereo(audio)
        fun executed(backend: String?): Boolean {
            val value = backend?.trim()?.lowercase() ?: return false
            return value.isNotEmpty() && listOf("unknown", "unavailable", "not-run", "not_run", "none", "no sessions opened")
                .none { value.startsWith(it) }
        }
        val reason = when {
            production.exported.trackId != trackId || production.exported.contentHash != inputSha256 -> "Analysis does not bind the decoded input"
            duration > AnalysisConfig.MAX_WHOLE_MODEL_SECONDS -> "Head/tail policy does not cover the whole file"
            evidence == null -> "No request-local inference evidence"
            evidence.coverage != "whole-file" -> "Inference coverage is ${evidence.coverage}"
            beat == null || vocal == null -> "Missing successful verified production-model call"
            beat.modelTag != "nirovi" || vocal.modelTag != "syvenu" -> "Fallback or non-production model executed"
            beat.modelSha256?.matches(Regex("[0-9a-f]{64}")) != true ||
                vocal.modelSha256?.matches(Regex("[0-9a-f]{64}")) != true -> "Executed model file was not verified by the production loader"
            beat.inputFrames <= 0 || beat.coveredFrames != beat.inputFrames ||
                vocal.inputFrames != beat.inputFrames || vocal.coveredFrames != vocal.inputFrames -> "Incomplete model-frame coverage"
            !executed(beat.backend) || !executed(vocal.backend) -> "Executed session backend is unavailable"
            beats.size < 8 || beats.any { !it.isFinite() || it < 0.0 || it > duration } ||
                beats.zipWithNext().any { (earlier, later) -> later <= earlier } -> "Raw full-file Nirovi beat return is not usable"
            else -> null
        }
        return AnalysisExecutionReceipt(
            trackId = trackId, inputSha256 = inputSha256, analysisSha256 = analysisSha256,
            sampleRateHz = audio.sampleRate, channels = channels, frameCount = audio.frames,
            beatModelTag = beat?.modelTag, vocalModelTag = vocal?.modelTag,
            beatModelSha256 = beat?.modelSha256, vocalModelSha256 = vocal?.modelSha256,
            beatBackend = beat?.backend, vocalBackend = vocal?.backend,
            inference = if (reason == null) "completed" else "unavailable",
            coverage = if (reason == null) "whole-file" else evidence?.coverage?.takeUnless { it == "whole-file" } ?: "unavailable",
            beatTimesSec = if (reason == null) beats else emptyList(), reason = reason.orEmpty(),
        )
    }

    /** Stereo decoding duplicates mono channels, so retain the input's real channel dimension. */
    private fun wavChannelCount(input: File, audio: WavReader.Stereo): Int = RandomAccessFile(input, "r").use { stream ->
        var channels = 0
        var rate = 0
        var bits = 0
        var dataBytes = 0
        require(stream.length() >= 12L && stream.readInt() == 0x52494646) { "WAV changed after decoding" }
        stream.skipBytes(4)
        require(stream.readInt() == 0x57415645) { "WAV changed after decoding" }
        while (stream.filePointer + 8L <= stream.length()) {
            val tag = stream.readInt()
            val size = Integer.reverseBytes(stream.readInt())
            val start = stream.filePointer
            require(size >= 0 && start + size <= stream.length()) { "WAV changed after decoding" }
            if (tag == 0x666d7420) { // fmt , including the extensible format handled by WavReader.
                require(size >= 16) { "Short WAV fmt chunk" }
                stream.seek(start + 2L)
                channels = java.lang.Short.reverseBytes(stream.readShort()).toInt() and 0xffff
                rate = Integer.reverseBytes(stream.readInt())
                stream.seek(start + 14L)
                bits = java.lang.Short.reverseBytes(stream.readShort()).toInt() and 0xffff
            }
            if (tag == 0x64617461) dataBytes = size // data
            stream.seek(start + size + (size and 1))
        }
        require(channels in 1..2 && bits in listOf(16, 24, 32) && rate == audio.sampleRate &&
            dataBytes.toLong() == audio.frames.toLong() * channels * (bits / 8)) { "WAV dimensions changed after decoding" }
        channels
    }

    private fun exportStems(options: Map<String, String>, audio: WavReader.Stereo, trackId: String, out: File, errors: PrintStream) {
        val manifestFile = options["out-stems"]?.let(::File) ?: File(out.parentFile, "${out.nameWithoutExtension}.stems.json")
        val model = options["scnet-model"]?.let(::File)
            ?: options["model-dir"]?.let { File(it, "scnet.int8.onnx") }
        // Write unavailable first: a failed retry cannot leave a usable stale manifest.
        val requestedKinds = options["required-stems"]?.split(',')?.map { it.trim().uppercase() }?.distinct()
            ?: StemSeparatorJvm.KINDS
        require(requestedKinds.isNotEmpty() && requestedKinds.all { it in StemSeparatorJvm.KINDS }) {
            "--required-stems must contain one or more of ${StemSeparatorJvm.KINDS.joinToString()}"
        }
        val fullDurationMs = (audio.frames.toDouble() * 1000.0 / audio.sampleRate).roundToLong()
        val startMs = options["window-start-ms"]?.toLong() ?: options["stem-start-sec"]?.toDouble()?.let { (it * 1000.0).toLong() } ?: 0L
        val endMs = options["window-end-ms"]?.toLong()
            ?: options["stem-end-sec"]?.toDouble()?.let { (it * 1000.0).toLong() }
            ?: ((audio.frames.toLong() * 1000L + audio.sampleRate - 1) / audio.sampleRate)
        require(("window-start-ms" in options) == ("window-end-ms" in options)) { "Supply both --window-start-ms and --window-end-ms" }
        fun unavailable(reason: String, window: StemWindow? = null) {
            val manifest = StemManifest(
                trackId = trackId, available = false, reason = reason, modelPath = model?.absolutePath,
                windowStartMs = window?.startMs, windowEndMs = window?.endMs, requiredStems = requestedKinds,
                sourcePcmSha256 = PcmIdentity.stereo(audio), windowFirstFrame = window?.firstFrame, windowEndFrame = window?.endFrame,
            )
            writeJsonAtomically(manifestFile, auditionJson.encodeToString(StemManifest.serializer(), manifest))
            errors.println("stems unavailable: $reason; planner must use the recipe's no-stem variant")
        }
        val window = try {
            calculateStemWindow(audio.frames, audio.sampleRate, startMs, endMs, options["window-padding-ms"]?.toLong() ?: 0L)
        } catch (failure: IllegalArgumentException) {
            if (failure.message?.contains("overlap exceeds") == true) {
                unavailable("Required overlap exceeds the ${StemSeparatorJvm.MAX_REGION_SECONDS} second SCNet cap")
                return
            }
            throw failure
        }
        unavailable("SCNet separation not completed", window)
        if (model == null || !model.isFile) { unavailable("SCNet model absent; supply --scnet-model or --model-dir", window); return }
        try {
            StemSeparatorJvm(model).use { separator ->
                val result = separator.separateWindow(audio, window.firstFrame, window.endFrame)
                require(result.keys.containsAll(requestedKinds)) { "SCNet did not return the requested stems" }
                val files = result.filterKeys { it in requestedKinds }.map { (kind, samples) ->
                    val file = File(manifestFile.absoluteFile.parentFile, "${manifestFile.nameWithoutExtension}.${kind.lowercase()}.f32")
                    writeAlignedStem(file, audio.frames, samples, window.firstFrame)
                    ExportedStem(kind, file.absolutePath, audio.sampleRate, frameCount = audio.frames,
                        startMs = 0L, endMs = fullDurationMs, sha256 = PcmIdentity.file(file))
                }
                val manifest = StemManifest(
                    trackId = trackId, available = true, modelPath = model.absolutePath, stems = files,
                    windowStartMs = window.startMs, windowEndMs = window.endMs, requiredStems = requestedKinds,
                    sourcePcmSha256 = PcmIdentity.stereo(audio), modelSha256 = separator.modelSha256,
                    windowFirstFrame = window.firstFrame, windowEndFrame = window.endFrame,
                )
                writeJsonAtomically(manifestFile, auditionJson.encodeToString(StemManifest.serializer(), manifest))
            }
        } catch (failure: Exception) {
            unavailable("SCNet inference failed: ${failure.message ?: failure::class.simpleName}", window)
        }
    }

    internal fun writeAlignedStem(file: File, fullFrames: Int, samples: WavReader.Stereo, offsetFrames: Int) {
        require(offsetFrames >= 0 && offsetFrames.toLong() + samples.frames <= fullFrames) { "Stem window exceeds decoded track" }
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { stream ->
            stream.setLength(0L)
            stream.setLength(fullFrames.toLong() * 8L)
            stream.seek(offsetFrames.toLong() * 8L)
            val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            fun flush() {
                if (buffer.position() > 0) stream.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            for (index in 0 until samples.frames) {
                if (buffer.remaining() < 8) flush()
                buffer.putFloat(samples.left[index]).putFloat(samples.right[index])
            }
            flush()
        }
    }

}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--audition-plan-driver") {
        exitProcess(AuditionPlanDriver.run(args.drop(1).toTypedArray()))
    }
    exitProcess(TrackAnalysisExporter.run(args))
}
