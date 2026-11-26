/*
 * AUTOMIX-V2(B): stem model catalogue, delivery and verification.
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 */

package com.music.bitchord.playback.smart

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Matches the native `bitchord::stems::Layout` values. */
enum class StemLayout(val native: Int) { SCNET_CAC(0), UMX_MAGNITUDE(1) }

/**
 * One weight file. [sha256] empty = not pinned yet (only usable with
 * [AutomixV2Stems.allowUnpinnedModels]). [bundledAsset] is an optional embedded-file
 * adapter for other platforms; Android djmix52 ships no ONNX assets. [url] is the
 * pinned release location, accessed through the platform's delivery coordinator.
 */
class StemModelFile(
    val fileName: String,
    val sha256: String,
    val bytes: Long,
    val url: String?,
    val bundledAsset: String? = null,
    val licence: String,
)

/**
 * A separation network and the exact signal contract it was trained on. Files are listed in
 * the order the engine opens them: one graph for SCNet, vocals/drums/bass/other for UMX.
 */
class StemModelSpec(
    val id: String,
    /** Cache key component: bump whenever weights or DSP change what the stems sound like. */
    val cacheTag: String,
    val layout: StemLayout,
    /** The graph's static frame count T. */
    val frames: Int,
    /** Real audio per chunk inside the (frames-1)*1024 STFT segment; 0 = all of it. */
    val segmentSamples: Int,
    /** Music-Source-Separation-Training inference.normalize (mono mean/std). */
    val normalize: Boolean,
    val baseConfidence: Float,
    val files: List<StemModelFile>,
)

/** The catalogue, best first. Download / licence details: tools/models/MODELS.md. */
object StemModels {
    /** SCNet (10.1 M params, MUSDB18-HQ), weight-only int8 export by audiojs; computes in float32. */
    val SCNET_INT8 = StemModelSpec(
        id = "scnet-musdb18-int8w",
        cacheTag = "scnet1",
        layout = StemLayout.SCNET_CAC,
        frames = 476,
        segmentSamples = 485_100,
        normalize = true,
        baseConfidence = 0.9f,
        files = listOf(
            StemModelFile(
                fileName = "scnet.int8.onnx",
                sha256 = "98228931494151762a1c4ab1ec7899a894b1f81fd4a509921a9d2f9bacc50845",
                bytes = 12_900_277L,
                url = "https://github.com/survey-sudo/bitchord-mix/releases/download/models-v1/scnet.int8.onnx",
                licence = "MIT (starrytong/SCNet; weights MIT per starrytong/SCNet#35); trained on MUSDB18-HQ",
            ),
        ),
    )

    /**
     * SCNet exported by tools/models/export_scnet.py with ORT dynamic int8 (real int8 MatMul/Conv
     * kernels, faster on CPU). This unpinned, no-URL experiment is NOT a fourth models-v1
     * release/download. Pin it only after a later export passes its SNR gate.
     */
    val SCNET_DYN_INT8 = StemModelSpec(
        id = "scnet-musdb18-dynint8",
        cacheTag = "scnet1d",
        layout = StemLayout.SCNET_CAC,
        frames = 476,
        segmentSamples = 485_100,
        normalize = true,
        baseConfidence = 0.88f,
        files = listOf(
            StemModelFile(
                fileName = "scnet_dyn_int8.onnx",
                sha256 = "",
                bytes = 0L,
                url = null,
                licence = "MIT (starrytong/SCNet); trained on MUSDB18-HQ",
            ),
        ),
    )

    /**
     * Open-Unmix UMX-HQ, four independent targets (MIT weights, Zenodo 3370489). Vocals is the
     * graph delivered by models-v1; drums/bass/other are unpublished local experiments.
     */
    val UMXHQ4_INT8 = StemModelSpec(
        id = "umxhq4-int8",
        cacheTag = "umxhq4",
        layout = StemLayout.UMX_MAGNITUDE,
        frames = 960,
        segmentSamples = 0,
        normalize = false,
        baseConfidence = 0.7f,
        files = listOf(
            StemModelFile(
                fileName = "vocals_umxhq_int8.onnx",
                sha256 = "a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb",
                bytes = 9_054_206L,
                url = "https://github.com/survey-sudo/bitchord-mix/releases/download/models-v1/vocals_umxhq_int8.onnx",
                licence = "MIT (sigsep/open-unmix-pytorch umxhq)",
            ),
            StemModelFile("drums_umxhq_int8.onnx", "", 0L, null, licence = "MIT (sigsep umxhq)"),
            StemModelFile("bass_umxhq_int8.onnx", "", 0L, null, licence = "MIT (sigsep umxhq)"),
            StemModelFile("other_umxhq_int8.onnx", "", 0L, null, licence = "MIT (sigsep umxhq)"),
        ),
    )

    val candidates: List<StemModelSpec> = listOf(SCNET_INT8, SCNET_DYN_INT8, UMXHQ4_INT8)
}

/** Fetches [url] into [destination]; the store verifies the hash. Orchestrator-provided (HTTP stack, Wi-Fi policy). */
fun interface StemModelDownloader {
    fun download(url: String, destination: File): Boolean
}

/**
 * Platform delivery authority. Android supplies AutomixModels: resolve returns only
 * identity-checked, previously SHA-verified paths; install queues its one gated batch
 * (and returns false while preparing); uninstall also goes through that authority.
 * A configured coordinator is exclusive: the store NEVER falls back to its own HTTP.
 */
interface StemModelCoordinator {
    fun resolve(model: StemModelFile): File?
    fun install(spec: StemModelSpec): Boolean
    fun uninstall(spec: StemModelSpec)
}

/**
 * Where stem weights live on the device, and whether they can be trusted.
 *
 * Delivery is on-demand (see NOTES.md): files go to [directory] (app filesDir/stems) and are
 * only ever used after their SHA-256 matches the pin in [StemModels]. [bundled] is a
 * compatibility local-file resolver (also called by filename without asset metadata).
 * Root filesDir copies are retained alongside the old stems directory for upgrades.
 *
 * Without [coordinator], resolve/verification and install belong on worker threads.
 * With Android's coordinator, lookups never hash or do HTTP: missing paths may queue
 * its IO preparation, and every install obeys its unmetered/explicit-consent gate.
 */
class StemModelStore(
    private val directory: () -> File?,
    private val bundled: (assetName: String) -> File? = { null },
    private val downloader: StemModelDownloader? = null,
    private val coordinator: StemModelCoordinator? = null,
) {
    /** path -> "length:lastModified:sha" of files already hashed this process. */
    private val verified = ConcurrentHashMap<String, String>()

    /** The first spec whose files are all present and verified, with its files in open order. */
    fun firstReady(candidates: List<StemModelSpec> = StemModels.candidates): Pair<StemModelSpec, List<File>>? {
        for (spec in candidates) {
            val files = resolve(spec) ?: continue
            return spec to files
        }
        return null
    }

    /** All of [spec]'s files, verified, or null when any is missing or fails its hash. No network. */
    fun resolve(spec: StemModelSpec): List<File>? {
        val out = ArrayList<File>(spec.files.size)
        for (model in spec.files) {
            out += locate(model) ?: return null
        }
        return out
    }

    /** Bytes still to download for [spec] (0 when ready or nothing is downloadable). */
    fun missingBytes(spec: StemModelSpec): Long =
        spec.files.filter { locate(it) == null && it.url != null }.sumOf { it.bytes }

    /**
     * Downloads every missing file of [spec] that has a URL, verifying each before it becomes
     * visible (written to `.part`, hashed, then renamed). Blocking; worker thread only.
     * Returns true when [spec] is ready afterwards.
     */
    fun install(spec: StemModelSpec): Boolean {
        coordinator?.let { return it.install(spec) }
        val dir = directory() ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false
        for (model in spec.files) {
            if (locate(model) != null) continue
            val url = model.url ?: return false
            val fetch = downloader ?: return false
            val part = File(dir, model.fileName + ".part")
            runCatching { part.delete() }
            val fetched = runCatching { fetch.download(url, part) }
                .onFailure { AnalysisLog.warn("stem model download failed: ${model.fileName}", it) }
                .getOrDefault(false)
            if (!fetched || !matches(part, model)) {
                AnalysisLog.warn("stem model ${model.fileName} rejected (download or SHA-256 mismatch)")
                runCatching { part.delete() }
                return false
            }
            val target = File(dir, model.fileName)
            runCatching { target.delete() }
            if (!part.renameTo(target)) {
                runCatching { part.delete() }
                return false
            }
        }
        return resolve(spec) != null
    }

    /** Removes downloaded (not bundled) files of [spec], e.g. when the user turns stems off. */
    fun uninstall(spec: StemModelSpec) {
        coordinator?.let { it.uninstall(spec); return }
        val dir = directory() ?: return
        for (model in spec.files) {
            if (model.bundledAsset != null) continue
            val file = File(dir, model.fileName)
            verified.remove(file.absolutePath)
            runCatching { file.delete() }
            runCatching { File(dir, model.fileName + ".part").delete() }
        }
    }

    private fun locate(model: StemModelFile): File? {
        coordinator?.let { return it.resolve(model) }
        val local = runCatching { bundled(model.bundledAsset ?: model.fileName) }.getOrNull()
        if (local != null && matches(local, model)) return local
        val dir = directory() ?: return null
        // Removing bundledAsset metadata must not strand the vc93 root copy.
        val rootCopy = dir.parentFile?.let { File(it, model.fileName) }
        if (rootCopy != null && matches(rootCopy, model)) return rootCopy
        val file = File(dir, model.fileName)
        if (!file.isFile) return null
        if (matches(file, model)) return file
        // A corrupt or stale download must not be retried as-is forever.
        AnalysisLog.warn("stem model ${model.fileName} failed verification; deleting")
        verified.remove(file.absolutePath)
        runCatching { file.delete() }
        return null
    }

    private fun matches(file: File, model: StemModelFile): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (model.sha256.isEmpty()) return AutomixV2Stems.allowUnpinnedModels
        if (model.bytes > 0L && file.length() != model.bytes) return false
        val stamp = stamp(file, model.sha256) ?: return false
        if (verified[file.absolutePath] == stamp) return true
        val ok = sha256Hex(file)?.equals(model.sha256, ignoreCase = true) == true && stamp(file, model.sha256) == stamp
        if (ok) verified[file.absolutePath] = stamp
        return ok
    }

    private fun stamp(file: File, hash: String): String? = runCatching {
        val path = file.toPath()
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isRegularFile) return@runCatching null
        val ctime = runCatching { Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        "${attrs.fileKey()}:${attrs.size()}:${attrs.lastModifiedTime()}:${attrs.creationTime()}:$ctime:$hash"
    }.getOrNull()

    companion object {
        /** Streaming SHA-256 of [file] as lowercase hex, or null when unreadable. */
        fun sha256Hex(file: File): String? = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }.getOrNull()
    }
}
