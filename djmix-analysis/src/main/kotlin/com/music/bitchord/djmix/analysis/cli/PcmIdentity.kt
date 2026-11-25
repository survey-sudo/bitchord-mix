package com.music.bitchord.djmix.analysis.cli

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** SHA-256 of little-endian raw f32 bytes, exactly as read by the native renderer. */
object PcmIdentity {
    fun file(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return hex(digest.digest())
    }

    fun mono(samples: FloatArray, sampleRate: Int): String {
        require(sampleRate > 0)
        return hashSamples(samples.size, 1) { frame, _ -> samples[frame] }
    }

    fun stereo(audio: WavReader.Stereo): String = hashSamples(audio.frames, 2) { frame, channel ->
        if (channel == 0) audio.left[frame] else audio.right[frame]
    }

    private fun hashSamples(frames: Int, channels: Int, value: (Int, Int) -> Float): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) for (channel in 0 until channels) {
            val sample = value(frame, channel)
            require(sample.isFinite()) { "Cannot hash non-finite PCM" }
            if (bytes.remaining() < 4) { digest.update(bytes.array(), 0, bytes.position()); bytes.clear() }
            bytes.putFloat(sample)
        }
        digest.update(bytes.array(), 0, bytes.position())
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
