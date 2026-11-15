package com.music.bitchord.playback.smart

import kotlin.math.roundToInt

/**
 * In-memory PCM region cache (djmix14). Loop mixes decode two regions over
 * the network (streamed Opus); a cold decode right after a scrub cannot
 * make a 4.5 s splice. This bounded LRU keeps the last few decoded stereo
 * slices so a region covering [reqStart, reqEnd] can be sub-sliced in RAM
 * in <2 ms instead of touching the network again.
 *
 * Scalars + PCM slices only. No cross-deck tensors/chroma ever cross.
 * Thread-safe: [put]/[get] synchronize on one lock; slicing copies.
 */
class LoopRegionCache(
    maxEntries: Int = 4,
    maxBytes: Long = 24L * 1024L * 1024L,
) {
    private val maxEntries = maxEntries.coerceAtLeast(1)
    private val maxBytes = maxBytes.coerceAtLeast(1L)
    private val lock = Any()
    // Access-order map: eldest is the head.
    private val map = LinkedHashMap<String, CachedEntry>(16, 0.75f, true)
    private var totalBytes: Long = 0L

    data class CachedEntry(
        val trackId: String,
        val sampleRate: Int,
        val startSec: Double,
        val endSec: Double,
        val left: FloatArray,
        val right: FloatArray,
    ) {
        fun byteSize(): Long = (left.size.toLong() + right.size.toLong()) * 4L
    }

    /** A RAM sub-slice of a cached entry, exactly [startSec, endSec). */
    data class Slice(
        val trackId: String,
        val sampleRate: Int,
        val startSec: Double,
        val endSec: Double,
        val left: FloatArray,
        val right: FloatArray,
    ) {
        fun byteSize(): Long = (left.size.toLong() + right.size.toLong()) * 4L
    }

    private fun key(trackId: String, sampleRate: Int) = trackId + "|" + sampleRate

    fun put(trackId: String, sampleRate: Int, startSec: Double, endSec: Double, left: FloatArray, right: FloatArray) {
        if (trackId.isBlank() || sampleRate <= 0 || !startSec.isFinite() || !endSec.isFinite() || endSec <= startSec) return
        if (left.isEmpty() || right.isEmpty() || left.size != right.size) return
        val entry = CachedEntry(trackId, sampleRate, startSec, endSec, left.copyOf(), right.copyOf())
        synchronized(lock) {
            val k = key(trackId, sampleRate)
            map.remove(k)?.let { totalBytes -= it.byteSize() }
            map[k] = entry
            totalBytes += entry.byteSize()
            // LRU eviction: drop eldest until both caps hold.
            while (map.isNotEmpty() && (map.size > maxEntries || totalBytes > maxBytes)) {
                val eldest = map.entries.first()
                map.remove(eldest.key)
                totalBytes -= eldest.value.byteSize()
            }
        }
    }

    /**
     * Returns a RAM slice when the cached entry for (trackId, sampleRate)
     * covers [reqStartSec, reqEndSec], else null. Out-of-range or
     * sample-rate mismatch is a miss (caller falls back to network decode).
     */
    fun get(trackId: String, sampleRate: Int, reqStartSec: Double, reqEndSec: Double): Slice? {
        if (reqEndSec <= reqStartSec || !reqStartSec.isFinite() || !reqEndSec.isFinite()) return null
        synchronized(lock) {
            val e = map[key(trackId, sampleRate)] ?: return null
            if (reqStartSec < e.startSec - 1e-9 || reqEndSec > e.endSec + 1e-9) return null
            val off = ((reqStartSec - e.startSec) * sampleRate).roundToInt().coerceIn(0, e.left.size)
            val len = ((reqEndSec - reqStartSec) * sampleRate).roundToInt().coerceIn(0, e.left.size - off)
            if (len <= 0) return null
            val l = e.left.copyOfRange(off, off + len)
            val r = e.right.copyOfRange(off, off + len)
            return Slice(trackId, sampleRate, reqStartSec, reqEndSec, l, r)
        }
    }

    fun contains(trackId: String, sampleRate: Int, reqStartSec: Double, reqEndSec: Double): Boolean {
        synchronized(lock) {
            val e = map[key(trackId, sampleRate)] ?: return false
            return reqStartSec >= e.startSec - 1e-9 && reqEndSec <= e.endSec + 1e-9
        }
    }

    fun size(): Int = synchronized(lock) { map.size }
    fun byteCount(): Long = synchronized(lock) { totalBytes }
    fun clear() {
        synchronized(lock) {
            map.clear()
            totalBytes = 0L
        }
    }
}
