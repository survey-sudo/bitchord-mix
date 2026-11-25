package com.music.bitchord.djmix.analysis.cli

import com.music.bitchord.playback.smart.Pcm
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded audio only. Container decoding belongs to the caller (normally ffmpeg). */
object WavReader {
    data class Result(val pcm: Pcm, val channels: Int, val sampleRate: Int, val bitsPerSample: Int)
    data class Stereo(val left: FloatArray, val right: FloatArray, val sampleRate: Int) {
        val frames: Int get() = left.size
        fun mono(): FloatArray = FloatArray(frames) { (left[it] + right[it]) * 0.5f }
    }

    private data class Header(val offset: Int, val size: Int, val rate: Int, val channels: Int, val bits: Int, val format: Int)

    fun readWav(file: File): Result? = runCatching {
        val bytes = file.readBytes()
        val header = header(bytes)
        val stereo = decode(bytes, header)
        Result(Pcm(stereo.mono(), header.rate.toDouble()), header.channels, header.rate, header.bits)
    }.getOrNull()

    fun readStereoWav(file: File): Stereo {
        val bytes = file.readBytes()
        return decode(bytes, header(bytes))
    }

    fun readPcmF32(file: File, sampleRate: Int, channels: Int): Pcm {
        val stereo = readStereoPcm(file, sampleRate, channels)
        return Pcm(stereo.mono(), sampleRate.toDouble())
    }

    fun readStereoPcm(file: File, sampleRate: Int, channels: Int = 2): Stereo {
        require(sampleRate > 0 && channels in 1..2) { "PCM requires a positive rate and one or two channels" }
        val bytes = file.readBytes()
        return decode(bytes, Header(0, bytes.size, sampleRate, channels, 32, 3))
    }

    fun writeStereoPcm(file: File, audio: Stereo) {
        require(audio.left.size == audio.right.size && audio.sampleRate > 0)
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { stream ->
            val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for (i in audio.left.indices) {
                require(audio.left[i].isFinite() && audio.right[i].isFinite()) { "Non-finite PCM at frame $i" }
                if (buffer.remaining() < 8) { stream.write(buffer.array(), 0, buffer.position()); buffer.clear() }
                buffer.putFloat(audio.left[i]); buffer.putFloat(audio.right[i])
            }
            if (buffer.position() > 0) stream.write(buffer.array(), 0, buffer.position())
        }
    }

    private fun header(bytes: ByteArray): Header {
        require(bytes.size >= 12 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "Expected RIFF/WAVE" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var offset = -1
        var size = 0
        var position = 12
        while (position + 8 <= bytes.size) {
            val length = buffer.getInt(position + 4)
            require(length >= 0 && position.toLong() + 8 + length <= bytes.size) { "Truncated WAV chunk" }
            val start = position + 8
            when (tag(bytes, position)) {
                "fmt " -> {
                    require(length >= 16) { "Short WAV fmt chunk" }
                    format = buffer.getShort(start).toInt() and 0xffff
                    channels = buffer.getShort(start + 2).toInt() and 0xffff
                    rate = buffer.getInt(start + 4)
                    bits = buffer.getShort(start + 14).toInt() and 0xffff
                    if (format == 0xfffe) {
                        require(length >= 40) { "Short extensible WAV format" }
                        format = buffer.getShort(start + 24).toInt() and 0xffff
                    }
                    require(buffer.getShort(start + 12).toInt() == channels * (bits / 8)) { "Invalid WAV block alignment" }
                }
                "data" -> { require(offset == -1) { "Multiple WAV data chunks" }; offset = start; size = length }
            }
            val next = start.toLong() + length + (length and 1)
            require(next <= Int.MAX_VALUE) { "WAV exceeds supported size" }
            position = next.toInt()
        }
        require(offset >= 0 && size > 0 && rate > 0 && channels in 1..2) { "Missing or invalid WAV fmt/data" }
        require((format == 1 && bits in listOf(16, 24, 32)) || (format == 3 && bits == 32)) { "Unsupported decoded WAV format $format/$bits" }
        return Header(offset, size, rate, channels, bits, format)
    }

    private fun decode(bytes: ByteArray, h: Header): Stereo {
        val width = h.bits / 8
        val align = width * h.channels
        require(h.size > 0 && h.size % align == 0) { "PCM ends inside a frame" }
        val frames = h.size / align
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun sample(position: Int): Float = when {
            h.format == 3 -> buffer.getFloat(position)
            h.bits == 16 -> buffer.getShort(position) / 32768f
            h.bits == 24 -> {
                val raw = (bytes[position].toInt() and 255) or ((bytes[position + 1].toInt() and 255) shl 8) or
                    (bytes[position + 2].toInt() shl 16)
                (if (raw and 0x800000 != 0) raw - 0x1000000 else raw) / 8388608f
            }
            else -> buffer.getInt(position) / 2147483648f
        }
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (i in 0 until frames) {
            val start = h.offset + i * align
            left[i] = sample(start)
            right[i] = if (h.channels == 2) sample(start + width) else left[i]
            require(left[i].isFinite() && right[i].isFinite()) { "Non-finite PCM at frame $i" }
        }
        return Stereo(left, right, h.rate)
    }

    private fun tag(bytes: ByteArray, offset: Int): String = String(bytes, offset, 4, Charsets.US_ASCII)
}
