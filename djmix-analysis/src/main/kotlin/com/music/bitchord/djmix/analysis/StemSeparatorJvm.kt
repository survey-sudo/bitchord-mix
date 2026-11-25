package com.music.bitchord.djmix.analysis

import com.music.bitchord.djmix.analysis.cli.PcmIdentity
import com.music.bitchord.djmix.analysis.cli.WavReader
import com.music.bitchord.playback.smart.OrtStemEngine
import com.music.bitchord.playback.smart.StemCache
import com.music.bitchord.playback.smart.StemModels
import com.music.bitchord.playback.smart.StemNative
import com.music.bitchord.playback.smart.StemProvider
import com.music.bitchord.playback.smart.StemSeparator as PlaybackStemSeparator
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/** I/O adapter to the playback separator, ORT engine and native DSP. No host STFT or resampler. */
class StemSeparatorJvm(private val model: File, threads: Int = 4) : StemSeparator, Closeable {
    val modelSha256 = PcmIdentity.file(model)
    private val spec = StemModels.candidates.firstOrNull { candidate ->
        candidate.files.size == 1 && candidate.files.single().let {
            it.sha256.isNotBlank() && model.length() == it.bytes && it.sha256 == modelSha256
        }
    } ?: error("SCNet model is unpinned or differs from the production StemModels identity")
    private val engine = OrtStemEngine(spec, listOf(model), StemNative, { threads })
    private val separator = PlaybackStemSeparator(engine = { engine }, legacy = null, cache = StemCache({ null }))

    override fun separate(pcm: FloatArray, sampleRate: Double): Map<String, FloatArray> {
        require(sampleRate.isFinite() && sampleRate > 0)
        return separateStereo(WavReader.Stereo(pcm, pcm, sampleRate.roundToInt())).mapValues { it.value.mono() }
    }

    fun separateStereo(pcm: WavReader.Stereo): Map<String, WavReader.Stereo> = separateWindow(pcm, 0, pcm.frames)

    fun separateWindow(pcm: WavReader.Stereo, startFrame: Int, endFrame: Int): Map<String, WavReader.Stereo> {
        check(StemNative.available) { "Production native stem DSP is unavailable" }
        return separateWindowUsing(pcm, startFrame, endFrame, separator)
    }

    override fun close() { separator.trim(); engine.release() }

    companion object {
        const val MODEL_RATE = 44_100
        const val MODEL_FRAMES = 476
        const val MAX_REGION_SECONDS = 48
        val KINDS = listOf("VOCALS", "DRUMS", "BASS", "OTHER")

        /** Tests exercise the actual window adapter and production StemProvider contract. */
        internal fun separateWindowUsing(
            pcm: WavReader.Stereo, startFrame: Int, endFrame: Int, provider: StemProvider,
        ): Map<String, WavReader.Stereo> {
            require(pcm.sampleRate > 0 && pcm.frames == pcm.right.size && startFrame >= 0 && endFrame > startFrame && endFrame <= pcm.frames)
            require(endFrame.toLong() - startFrame <= pcm.sampleRate.toLong() * MAX_REGION_SECONDS) { "SCNet overlap exceeds the 48 second cap" }
            val left = pcm.left.copyOfRange(startFrame, endFrame)
            val right = pcm.right.copyOfRange(startFrame, endFrame)
            require(left.all { it.isFinite() } && right.all { it.isFinite() })
            val result = checkNotNull(provider.separate(left, right, pcm.sampleRate, startFrame.toDouble() / pcm.sampleRate)) {
                "Production stem separator returned unavailable"
            }
            require(result.frames == endFrame - startFrame && result.sampleRate == pcm.sampleRate &&
                result.regionStartSec == startFrame.toDouble() / pcm.sampleRate && result.stemCount == 4) { "Misaligned production stems" }
            return com.music.bitchord.playback.smart.StemKind.entries.associate { kind ->
                val l = checkNotNull(result.left[kind]); val r = checkNotNull(result.right[kind])
                require(l.size == result.frames && r.size == result.frames && l.all { it.isFinite() } && r.all { it.isFinite() })
                kind.name to WavReader.Stereo(l, r, result.sampleRate)
            }
        }
    }
}
