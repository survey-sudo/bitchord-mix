package com.music.bitchord.djmix.render

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Same per-audio/aggregate bounds as the bundle protocol; streams never load whole PCM. */
internal object OwnerPcmStaging {
    const val MAX_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    data class Digests(val interleaved: String, val left: String, val right: String)

    fun stage(source: Path, target: Path, expectedBytes: Long): Digests {
        if (expectedBytes <= 0 || expectedBytes > MAX_BYTES || expectedBytes % 8 != 0L) {
            throw BundleReplayException(ReplayErrorCode.LIMIT_EXCEEDED, "Owner PCM exceeds bounded stereo f32 protocol")
        }
        try {
            val interleaved = MessageDigest.getInstance("SHA-256")
            val left = MessageDigest.getInstance("SHA-256")
            val right = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    val bytes = ByteArray(8192)
                    val leftBytes = ByteArray(bytes.size / 2)
                    val rightBytes = ByteArray(bytes.size / 2)
                    var remaining = expectedBytes
                    while (remaining > 0) {
                        val count = minOf(bytes.size.toLong(), remaining).toInt()
                        if (input.readNBytes(bytes, 0, count) != count) invalid("Owner PCM became shorter than its aligned region")
                        for (offset in 0 until count step 4) {
                            // IEEE-754 little-endian exponent bits; retain exact raw bytes (including signed zero).
                            if ((bytes[offset + 3].toInt() and 0x7f) == 0x7f && (bytes[offset + 2].toInt() and 0x80) != 0) {
                                invalid("Owner PCM contains a non-finite f32le sample")
                            }
                        }
                        for (offset in 0 until count step 8) {
                            System.arraycopy(bytes, offset, leftBytes, offset / 2, 4)
                            System.arraycopy(bytes, offset + 4, rightBytes, offset / 2, 4)
                        }
                        interleaved.update(bytes, 0, count)
                        left.update(leftBytes, 0, count / 2)
                        right.update(rightBytes, 0, count / 2)
                        output.write(bytes, 0, count)
                        remaining -= count
                    }
                    if (input.read() != -1) invalid("Owner PCM became longer than its aligned region")
                }
            }
            return Digests(hex(interleaved.digest()), hex(left.digest()), hex(right.digest()))
        } catch (error: IOException) {
            throw BundleReplayException(ReplayErrorCode.AUDIO_UNAVAILABLE, "Cannot stage owner PCM: ${error.message}", error)
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun invalid(message: String): Nothing = throw BundleReplayException(ReplayErrorCode.INVALID_AUDIO, message)
}

/** Only the explicitly supplied executable is launched; no shell or JNI is involved. */
class NativeProcessRenderBackend(private val rendererBinary: Path) : RenderBackend {
    override fun render(request: RenderRequest) {
        if (!Files.isRegularFile(rendererBinary) || !Files.isExecutable(rendererBinary)) {
            throw BundleReplayException(ReplayErrorCode.AUDIO_UNAVAILABLE, "Renderer is not executable: $rendererBinary")
        }
        if (request.scriptJson.toByteArray(Charsets.UTF_8).size > 16 * 1024 * 1024) {
            throw BundleReplayException(ReplayErrorCode.LIMIT_EXCEEDED, "Execution script exceeds text protocol limit")
        }
        val script = Files.createTempFile(request.workingDirectory, "native-script-", ".json")
        val log = Files.createTempFile(request.workingDirectory, "native-render-", ".log")
        var child: Process? = null
        try {
            Files.writeString(script, request.scriptJson, Charsets.UTF_8)
            val process = ProcessBuilder(rendererBinary.toAbsolutePath().toString(), "--render", script.toString(),
                "--out", request.outputWav.toAbsolutePath().toString())
                .directory(request.workingDirectory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            child = process
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                throw BundleReplayException(ReplayErrorCode.RENDER_FAILURE, "Native renderer timed out after five minutes")
            }
            if (process.exitValue() != 0) {
                val message = Files.newInputStream(log).use { it.readNBytes(8192).toString(Charsets.UTF_8) }
                throw BundleReplayException(ReplayErrorCode.RENDER_FAILURE, "Native renderer exited ${process.exitValue()}: $message")
            }
        } finally {
            val process = child
            if (process != null && process.isAlive) {
                process.destroyForcibly()
                try { process.waitFor() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            Files.deleteIfExists(script)
            Files.deleteIfExists(log)
        }
    }
}

/** Bounded inspection of the actual native PCM16 stereo WAV; no estimated true peak. */
object WavPcmSummary {
    private const val MAX_BYTES = 256L * 1024 * 1024

    fun summarize(path: Path): RenderResult = try {
        RandomAccessFile(path.toFile(), "r").use { file ->
            val length = file.length()
            if (length < 44 || length > MAX_BYTES) invalid("WAV size must be 44..$MAX_BYTES bytes")
            if (ascii(file, 4) != "RIFF" || u32(file) != length - 8 || ascii(file, 4) != "WAVE") invalid("Invalid RIFF/WAVE header")
            var rate = 0
            var dataOffset = -1L
            var dataSize = -1L
            while (file.filePointer + 8 <= length) {
                val kind = ascii(file, 4)
                val size = u32(file)
                val start = file.filePointer
                val end = start + size + (size and 1L)
                if (end > length) invalid("Truncated WAV chunk")
                when (kind) {
                    "fmt " -> {
                        if (rate != 0 || size < 16) invalid("Missing/duplicate/short WAV fmt chunk")
                        val format = u16(file)
                        val channels = u16(file)
                        val sr = u32(file)
                        val byteRate = u32(file)
                        val blockAlign = u16(file)
                        val bits = u16(file)
                        if (format != 1 || channels != 2 || bits != 16 || blockAlign != 4 ||
                            sr <= 0 || sr > Int.MAX_VALUE || byteRate != sr * 4
                        ) invalid("Expected PCM16 stereo WAV")
                        rate = sr.toInt()
                    }
                    "data" -> {
                        if (dataOffset >= 0 || size <= 0 || size % 4 != 0L) invalid("Invalid/duplicate/empty WAV data chunk")
                        dataOffset = start
                        dataSize = size
                    }
                }
                file.seek(end)
            }
            if (file.filePointer != length || rate == 0 || dataOffset < 0) invalid("Incomplete WAV structure")
            file.seek(dataOffset)
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(8192)
            var remaining = dataSize
            var peak = 0.0
            while (remaining > 0) {
                val count = minOf(bytes.size.toLong(), remaining).toInt()
                file.readFully(bytes, 0, count)
                digest.update(bytes, 0, count)
                for (index in 0 until count step 2) {
                    val sample = ((bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8)).toShort().toInt()
                    peak = maxOf(peak, abs(sample / 32768.0))
                }
                remaining -= count
            }
            RenderResult(path, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, rate, dataSize / 2, peak)
        }
    } catch (error: IOException) {
        throw BundleReplayException(ReplayErrorCode.INVALID_AUDIO, "Cannot read produced WAV: ${error.message}", error)
    }

    private fun ascii(file: RandomAccessFile, size: Int): String = ByteArray(size).also { file.readFully(it) }.toString(Charsets.US_ASCII)
    private fun u16(file: RandomAccessFile): Int = file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
    private fun u32(file: RandomAccessFile): Long = u16(file).toLong() or (u16(file).toLong() shl 16)
    private fun invalid(message: String): Nothing = throw BundleReplayException(ReplayErrorCode.INVALID_AUDIO, message)
}
