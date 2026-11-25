/*
 * AUTOMIX-V2(B): bounded memory + disk cache of separated regions.
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

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Separated regions keyed by track id + model tag + sample rate + region (start sample, frames).
 *
 * Only VOCALS, DRUMS and BASS are stored (VOCALS alone for 2-stem sets). OTHER is rebuilt on
 * every hit as `dry - VOCALS - DRUMS - BASS` from the caller's own dry audio, so a hit is always
 * exactly mixture-consistent with what the caller is about to play, even after 16-bit disk
 * quantisation.
 *
 * A request is served by any cached region of the same track/tag/rate that *contains* it, so a
 * region separated ahead of time (prefetch) also serves the slightly shorter or later window the
 * loop mix finally decodes. Containment is checked against probe samples of the cached dry, so a
 * re-decode that landed even one sample elsewhere is a miss, never misaligned stems.
 *
 * Memory: an LRU bounded by [memoryBudget] bytes. Disk: one file per region in [directory],
 * oldest evicted past [diskBudget] bytes. All methods are thread-safe; disk I/O happens on the
 * caller's (worker) thread.
 */
class StemCache(
    private val directory: () -> File?,
    private val memoryBudget: () -> Long = { AutomixV2Stems.memoryCacheBytes },
    private val diskBudget: () -> Long = { AutomixV2Stems.diskCacheBytes },
) {
    data class Key(
        val trackId: String,
        val tag: String,
        val sampleRate: Int,
        val startSample: Long,
        val frames: Int,
    )

    private class Entry(
        val key: Key,
        val stemCount: Int,
        val confidence: Float,
        /** VOCALS, DRUMS, BASS (or VOCALS only when stemCount == 2), planar. */
        val left: List<FloatArray>,
        val right: List<FloatArray>,
        val probeStride: Int,
        val probe: FloatArray,
    ) {
        val bytes: Long get() = (left.sumOf { it.size.toLong() } + right.sumOf { it.size.toLong() } + probe.size) * 4L
    }

    private val memory = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var memoryBytes = 0L
    private val diskLock = Any()

    /** Memory entries currently held (tests / telemetry). */
    val memoryEntries: Int get() = synchronized(memory) { memory.size }

    fun clearMemory() {
        synchronized(memory) {
            memory.clear()
            memoryBytes = 0L
        }
    }

    /**
     * Stems for [left]/[right] (the caller's dry region at [sampleRate], starting at track second
     * [regionStartSec]) from any containing cached region of [trackId]/[tag]; null on a miss.
     */
    fun lookup(
        trackId: String,
        tag: String,
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        regionStartSec: Double,
    ): StemSet? {
        if (left.isEmpty() || left.size != right.size || sampleRate <= 0) return null
        val start = StemMath.samplesAt(regionStartSec, sampleRate)
        val frames = left.size
        val candidates = synchronized(memory) {
            memory.values.filter { it.key.trackId == trackId && it.key.tag == tag && it.key.sampleRate == sampleRate }
        }
        for (entry in candidates) {
            if (!contains(entry.key, start, frames)) continue
            val offset = (start - entry.key.startSample).toInt()
            if (!probeMatches(entry, offset, left)) continue
            synchronized(memory) { memory[entry.key] } // touch (access order)
            return build(entry, offset, left, right, sampleRate, regionStartSec)
        }
        val fromDisk = loadFromDisk(trackId, tag, sampleRate, start, frames, left) ?: return null
        remember(fromDisk)
        val offset = (start - fromDisk.key.startSample).toInt()
        return build(fromDisk, offset, left, right, sampleRate, regionStartSec)
    }

    /** Caches [stems] separated from [left]/[right] (its own dry); memory now, disk synchronously. */
    fun put(trackId: String, tag: String, stems: StemSet, left: FloatArray, right: FloatArray) {
        if (left.size != stems.frames || right.size != stems.frames || stems.frames <= 0) return
        val kinds = if (stems.stemCount >= 4) STORED_FOUR else STORED_TWO
        val l = kinds.map { stems.left[it] ?: return }
        val r = kinds.map { stems.right[it] ?: return }
        if (l.any { it.size != stems.frames } || r.any { it.size != stems.frames }) return
        val key = Key(trackId, tag, stems.sampleRate, StemMath.samplesAt(stems.regionStartSec, stems.sampleRate), stems.frames)
        val stride = probeStride(stems.frames)
        val probe = FloatArray((stems.frames - 1) / stride + 1) { left[it * stride] }
        val entry = Entry(key, if (stems.stemCount >= 4) 4 else 2, stems.confidence, l, r, stride, probe)
        remember(entry)
        runCatching { writeToDisk(entry) }.onFailure { AnalysisLog.warn("stem disk cache write failed", it) }
    }

    // --- memory -----------------------------------------------------------------------------

    private fun remember(entry: Entry) {
        val budget = memoryBudget()
        if (entry.bytes > budget) return
        synchronized(memory) {
            memory.remove(entry.key)?.let { memoryBytes -= it.bytes }
            memory[entry.key] = entry
            memoryBytes += entry.bytes
            val iterator = memory.entries.iterator()
            while (memoryBytes > budget && iterator.hasNext()) {
                val eldest = iterator.next()
                if (eldest.key == entry.key) continue
                memoryBytes -= eldest.value.bytes
                iterator.remove()
            }
        }
    }

    private fun contains(key: Key, start: Long, frames: Int): Boolean {
        if (frames <= 0 || key.frames < frames || start < key.startSample) return false
        val offset = start - key.startSample
        return offset >= 0L && offset <= (key.frames - frames).toLong()
    }

    private fun probeMatches(entry: Entry, offset: Int, left: FloatArray): Boolean {
        val stride = entry.probeStride
        var index = (offset + stride - 1) / stride
        var checked = 0
        while (index < entry.probe.size) {
            val position = index * stride - offset
            if (position >= left.size) break
            if (abs(left[position] - entry.probe[index]) > PROBE_TOLERANCE) return false
            checked++
            index++
        }
        return checked > 0
    }

    private fun build(
        entry: Entry,
        offset: Int,
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        regionStartSec: Double,
    ): StemSet {
        val frames = left.size
        val exact = offset == 0 && frames == entry.key.frames
        fun slice(array: FloatArray): FloatArray = if (exact) array else array.copyOfRange(offset, offset + frames)
        val kinds = if (entry.stemCount >= 4) STORED_FOUR else STORED_TWO
        val outLeft = HashMap<StemKind, FloatArray>(8)
        val outRight = HashMap<StemKind, FloatArray>(8)
        kinds.forEachIndexed { i, kind ->
            outLeft[kind] = slice(entry.left[i])
            outRight[kind] = slice(entry.right[i])
        }
        val otherLeft = FloatArray(frames)
        val otherRight = FloatArray(frames)
        StemMath.foldResidualIntoOther(left, outLeft[StemKind.VOCALS], outLeft[StemKind.DRUMS], outLeft[StemKind.BASS], otherLeft)
        StemMath.foldResidualIntoOther(right, outRight[StemKind.VOCALS], outRight[StemKind.DRUMS], outRight[StemKind.BASS], otherRight)
        outLeft[StemKind.OTHER] = otherLeft
        outRight[StemKind.OTHER] = otherRight
        return StemSet(sampleRate, frames, regionStartSec, outLeft, outRight, entry.stemCount, entry.confidence)
    }

    // --- disk -------------------------------------------------------------------------------

    private fun prefix(trackId: String, tag: String, sampleRate: Int): String =
        "${trackHash(trackId)}_${tag}_${sampleRate}_"

    private fun fileFor(dir: File, key: Key): File =
        File(dir, prefix(key.trackId, key.tag, key.sampleRate) + "${key.startSample}_${key.frames}$SUFFIX")

    private fun diskBytes(frames: Int, stored: Int, probes: Int): Long =
        HEADER_BYTES + probes * 4L + stored * 2L * (4L + frames * 2L)

    private fun writeToDisk(entry: Entry) {
        val dir = directory() ?: return
        val expectedBytes = diskBytes(entry.key.frames, entry.left.size, entry.probe.size)
        if (expectedBytes > diskBudget()) return
        synchronized(diskLock) {
            if (!dir.exists() && !dir.mkdirs()) return
            val target = fileFor(dir, entry.key)
            // Each instance has its own diskLock; never share another writer's staging file.
            val temp = File.createTempFile(target.name + ".", ".tmp", dir)
            try {
                FileOutputStream(temp).use { fileOutput ->
                    DataOutputStream(BufferedOutputStream(fileOutput, 1 shl 16)).use { out ->
                        out.writeInt(MAGIC)
                        out.writeInt(VERSION)
                        out.writeInt(entry.key.sampleRate)
                        out.writeLong(entry.key.startSample)
                        out.writeInt(entry.key.frames)
                        out.writeInt(entry.stemCount)
                        out.writeFloat(entry.confidence)
                        out.writeInt(entry.left.size)
                        out.writeInt(entry.probeStride)
                        out.writeInt(entry.probe.size)
                        for (value in entry.probe) out.writeFloat(value)
                        val bytes = ByteArray(CHUNK * 2)
                        for (i in entry.left.indices) {
                            for (channel in listOf(entry.left[i], entry.right[i])) {
                                var peak = 0f
                                for (value in channel) peak = max(peak, abs(value))
                                val scale = if (peak > 0f) peak / 32767f else 1f
                                out.writeFloat(scale)
                                var position = 0
                                while (position < channel.size) {
                                    val count = min(CHUNK, channel.size - position)
                                    for (j in 0 until count) {
                                        val v = channel[position + j] / scale
                                        val q = if (v.isNaN()) 0 else v.roundToInt().coerceIn(-32767, 32767)
                                        bytes[2 * j] = (q shr 8).toByte()
                                        bytes[2 * j + 1] = q.toByte()
                                    }
                                    out.write(bytes, 0, count * 2)
                                    position += count
                                }
                            }
                        }
                        out.flush()
                        fileOutput.fd.sync()
                    }
                }
                check(temp.length() == expectedBytes) { "Incomplete stem cache entry" }
                if (expectedBytes > diskBudget()) return
                // Fail closed if atomic replacement is unsupported; never delete the old entry.
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                evictDisk(dir)
            } finally {
                runCatching { temp.delete() }
            }
        }
    }

    private fun evictDisk(dir: File) {
        val budget = diskBudget()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= budget) return
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= budget) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun loadFromDisk(
        trackId: String,
        tag: String,
        sampleRate: Int,
        start: Long,
        frames: Int,
        left: FloatArray,
    ): Entry? {
        val dir = directory() ?: return null
        val head = prefix(trackId, tag, sampleRate)
        val names = dir.list { _, name -> name.startsWith(head) && name.endsWith(SUFFIX) } ?: return null
        for (name in names) {
            val parts = name.removePrefix(head).removeSuffix(SUFFIX).split('_')
            if (parts.size != 2) continue
            val cachedStart = parts[0].toLongOrNull() ?: continue
            val cachedFrames = parts[1].toIntOrNull() ?: continue
            val key = Key(trackId, tag, sampleRate, cachedStart, cachedFrames)
            if (!contains(key, start, frames)) continue
            val file = File(dir, name)
            val entry = runCatching { read(file, key) }.getOrNull()
            // A failed read must not unlink a valid replacement from another cache instance.
            if (entry == null) continue
            if (!probeMatches(entry, (start - cachedStart).toInt(), left)) continue
            runCatching { file.setLastModified(System.currentTimeMillis()) }
            return entry
        }
        return null
    }

    private fun read(file: File, key: Key): Entry? {
        FileInputStream(file).use { fileInput ->
            // Inspect the opened file, not a path another instance can atomically replace.
            val size = fileInput.channel.size()
            if (size < HEADER_BYTES || size > diskBudget()) return null
            DataInputStream(BufferedInputStream(fileInput, 1 shl 16)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                if (input.readInt() != key.sampleRate) return null
                if (input.readLong() != key.startSample) return null
                val frames = input.readInt()
                if (frames != key.frames || frames <= 0) return null
                val stemCount = input.readInt()
                if (stemCount != 2 && stemCount != 4) return null
                val confidence = input.readFloat()
                val stored = input.readInt()
                if (stored != (if (stemCount >= 4) STORED_FOUR.size else STORED_TWO.size)) return null
                val stride = input.readInt()
                val probeCount = input.readInt()
                if (stride != probeStride(frames) || probeCount != (frames - 1) / stride + 1) return null
                if (size != diskBytes(frames, stored, probeCount)) return null
                val probe = FloatArray(probeCount) { input.readFloat() }
                val bytes = ByteArray(CHUNK * 2)
                val l = ArrayList<FloatArray>(stored)
                val r = ArrayList<FloatArray>(stored)
                repeat(stored) {
                    for (channel in 0 until 2) {
                        val scale = input.readFloat()
                        val samples = FloatArray(frames)
                        var position = 0
                        while (position < frames) {
                            val count = min(CHUNK, frames - position)
                            input.readFully(bytes, 0, count * 2)
                            for (j in 0 until count) {
                                val q = ((bytes[2 * j].toInt() shl 8) or (bytes[2 * j + 1].toInt() and 0xff)).toShort()
                                samples[position + j] = q * scale
                            }
                            position += count
                        }
                        if (channel == 0) l += samples else r += samples
                    }
                }
                return Entry(key, if (stemCount >= 4) 4 else 2, confidence, l, r, stride, probe)
            }
        }
    }

    companion object {
        private const val MAGIC = 0x42435354 // "BCST"
        private const val VERSION = 1
        private const val SUFFIX = ".stm"
        private const val HEADER_BYTES = 44L
        private const val CHUNK = 1 shl 15
        private const val PROBE_TOLERANCE = 1e-4f
        private val STORED_FOUR = listOf(StemKind.VOCALS, StemKind.DRUMS, StemKind.BASS)
        private val STORED_TWO = listOf(StemKind.VOCALS)

        private fun probeStride(frames: Int): Int = max(1, frames / 256)

        internal fun trackHash(trackId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(trackId.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
