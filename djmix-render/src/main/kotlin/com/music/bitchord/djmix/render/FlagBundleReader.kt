package com.music.bitchord.djmix.render

import com.music.bitchord.djmix.core.contracts.ENGINE_VERSION
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Bounded, root-only ZIP reading. Nothing is extracted and no captured path is opened. */
internal object FlagBundleReader {
    val textNames = setOf(
        "plan.json", "recipe.json", "render_geometry.json", "script.json",
        "analysis_out.json", "analysis_in.json", "flags_snapshot.json", "taste_snapshot.json",
        "events_5min.jsonl", "telemetry_5min.jsonl", "analysis_summary.json",
    )
    private val audioNames = setOf("mix_capture.wav", "mix_capture.flac", "mix_capture.pcm", "mix_capture.bin")
    private const val MAX_TEXT_BYTES = 16L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private val shaPattern = Regex("[a-fA-F0-9]{64}")

    data class Payload(val text: Map<String, String>, val manifest: FlagManifest)
    private data class Digest(val size: Long, val sha: String)

    fun read(path: Path): Payload = try {
        if (Files.size(path) > MAX_TOTAL_BYTES + 1024 * 1024) fail(ReplayErrorCode.LIMIT_EXCEEDED, "ZIP file exceeds input size limit")
        ZipFile(path.toFile()).use { zip ->
            val texts = linkedMapOf<String, String>()
            val digests = linkedMapOf<String, Digest>()
            val names = HashSet<String>()
            var total = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name !in textNames && name !in audioNames && name != "manifest.json") {
                    fail(ReplayErrorCode.UNSAFE_ENTRY, "Unknown or unsafe ZIP entry: $name")
                }
                if (entry.isDirectory || !names.add(name)) fail(ReplayErrorCode.UNSAFE_ENTRY, "Duplicate/directory entry: $name")
                val isText = name !in audioNames
                val limit = if (isText) MAX_TEXT_BYTES else MAX_ENTRY_BYTES
                if (entry.size > limit) fail(ReplayErrorCode.LIMIT_EXCEEDED, "$name exceeds $limit bytes")
                val digest = MessageDigest.getInstance("SHA-256")
                val captured = if (isText) ByteArrayOutputStream() else null
                var size = 0L
                zip.getInputStream(entry).use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        size += count
                        total += count
                        if (size > limit || total > MAX_TOTAL_BYTES) fail(ReplayErrorCode.LIMIT_EXCEEDED, "ZIP expanded size limit: $name")
                        digest.update(buffer, 0, count)
                        captured?.write(buffer, 0, count)
                    }
                }
                digests[name] = Digest(size, hex(digest.digest()))
                if (captured != null) texts[name] = utf8(captured.toByteArray(), name)
            }
            val rawManifest = texts["manifest.json"] ?: fail(ReplayErrorCode.MISSING_ENTRY, "Missing manifest.json")
            val root = NativeScriptJsonCodec.objectValue(rawManifest, "manifest.json")
            if (root["schema"].toString() != "1" || root["engine"].toString() != "\"$ENGINE_VERSION\"") {
                fail(ReplayErrorCode.UNSUPPORTED_VERSION, "Expected manifest schema=1, engine=$ENGINE_VERSION")
            }
            val manifest = NativeScriptJsonCodec.decodeComplete(FlagManifest.serializer(), rawManifest, "manifest.json")
            validate(manifest, digests)
            Payload(texts.toMap(), manifest)
        }
    } catch (error: BundleReplayException) {
        throw error
    } catch (error: ZipException) {
        throw BundleReplayException(ReplayErrorCode.INVALID_ZIP, "Invalid ZIP: ${error.message}", error)
    } catch (error: IOException) {
        throw BundleReplayException(ReplayErrorCode.INPUT, "Cannot read bundle: ${error.message}", error)
    }

    private fun validate(manifest: FlagManifest, files: Map<String, Digest>) {
        if (manifest.trigger != "Flag" || manifest.createdAtMs < 0) fail(ReplayErrorCode.INVALID_MANIFEST, "Invalid Flag trigger/createdAtMs")
        if (manifest.entries.keys != textNames + "audioCapture") fail(ReplayErrorCode.INVALID_MANIFEST, "Manifest status keys do not match Flag schema")
        val declaredFiles = mutableSetOf("manifest.json")
        for ((key, status) in manifest.entries) {
            if (!status.available) {
                if (status.entry != null || status.bytes != 0L || status.sha256 != null || status.detail.isNullOrBlank()) {
                    fail(ReplayErrorCode.INVALID_MANIFEST, "Invalid unavailable status: $key")
                }
                if (key in files) fail(ReplayErrorCode.INVALID_MANIFEST, "Unavailable entry is present: $key")
                continue
            }
            val name = status.entry ?: fail(ReplayErrorCode.INVALID_MANIFEST, "Missing entry name: $key")
            if ((key == "audioCapture" && name !in audioNames) || (key != "audioCapture" && name != key) || status.detail != null) {
                fail(ReplayErrorCode.INVALID_MANIFEST, "Invalid status entry/detail: $key")
            }
            val digest = files[name] ?: fail(ReplayErrorCode.MISSING_ENTRY, "Manifest entry missing: $name")
            if (!declaredFiles.add(name)) fail(ReplayErrorCode.INVALID_MANIFEST, "Entry declared twice: $name")
            if (status.bytes != digest.size || status.bytes < 0) fail(ReplayErrorCode.INVALID_MANIFEST, "Byte count mismatch: $name")
            if (status.sha256?.matches(shaPattern) != true) fail(ReplayErrorCode.INVALID_MANIFEST, "Invalid SHA-256: $name")
            if (!status.sha256.equals(digest.sha, ignoreCase = true)) fail(ReplayErrorCode.BAD_HASH, "SHA-256 mismatch: $name")
        }
        if (declaredFiles != files.keys) fail(ReplayErrorCode.INVALID_MANIFEST, "Unlisted or unavailable ZIP entry")
        for (table in listOf(manifest.assetSha256, manifest.modelSha256)) {
            for ((name, sha) in table) {
                if (name.isBlank() || !sha.matches(shaPattern)) fail(ReplayErrorCode.INVALID_MANIFEST, "Invalid external SHA-256: $name")
            }
        }
        val recipe = manifest.entries.getValue("recipe.json")
        if (manifest.recipeSha256 != recipe.sha256) fail(ReplayErrorCode.INVALID_MANIFEST, "recipeSha256 disagrees with recipe.json status")
        for (name in listOf("events_5min.jsonl", "telemetry_5min.jsonl", "analysis_summary.json")) {
            if (manifest.entries.getValue(name).available != true) fail(ReplayErrorCode.MISSING_ENTRY, "Writer always supplies $name")
        }
    }

    private fun utf8(bytes: ByteArray, name: String): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw BundleReplayException(ReplayErrorCode.INVALID_JSON, "$name is not UTF-8", error)
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fail(code: ReplayErrorCode, message: String): Nothing = throw BundleReplayException(code, message)
}
