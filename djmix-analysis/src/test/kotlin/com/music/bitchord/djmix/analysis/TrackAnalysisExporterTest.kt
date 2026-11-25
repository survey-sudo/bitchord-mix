package com.music.bitchord.djmix.analysis

import com.music.bitchord.djmix.analysis.cli.*
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.*

class TrackAnalysisExporterTest {
    @Test fun canonicalStereoHashEqualsActualRawPcmBytesAndChangesOnSampleEdit() = temporary { tmp ->
        val file = File(tmp, "input.f32")
        val pcm = WavReader.Stereo(floatArrayOf(-0.0f, 0.25f, -0.5f), floatArrayOf(0.1f, 0f, 1f), 48_000)
        WavReader.writeStereoPcm(file, pcm)
        assertEquals(PcmIdentity.file(file), PcmIdentity.stereo(pcm))
        val before = PcmIdentity.stereo(pcm); pcm.right[1] = 0.0001f
        assertNotEquals(before, PcmIdentity.stereo(pcm))
    }

    @Test fun missingProductionModelsRefuseAnalysisWithoutFabricatedJson() = temporary { tmp ->
        val pcm = File(tmp, "input.f32"); WavReader.writeStereoPcm(pcm, WavReader.Stereo(FloatArray(4_800), FloatArray(4_800), 48_000))
        val output = File(tmp, "analysis.json")
        val errors = ByteArrayOutputStream()
        val rc = TrackAnalysisExporter.run(arrayOf("--input", pcm.path, "--input-format", "pcm_f32", "--sample-rate", "48000", "--channels", "2",
            "--out", output.path, "--model-dir", tmp.path), errors = PrintStream(errors))
        assertEquals(1, rc); assertFalse(output.exists()); assertTrue(errors.toString().contains("production model"))
    }

    @Test fun missingScnetWritesUnavailableManifestWithSourceIdentityAndNoStemFiles() = temporary { tmp ->
        val input = File(tmp, "input.f32")
        val audio = WavReader.Stereo(FloatArray(48_000) { 0.1f }, FloatArray(48_000) { -0.1f }, 48_000)
        WavReader.writeStereoPcm(input, audio)
        val manifest = File(tmp, "stems.json")
        val rc = TrackAnalysisExporter.run(arrayOf("--input", input.path, "--input-format", "pcm_f32", "--sample-rate", "48000", "--channels", "2",
            "--out", File(tmp, "unused-analysis.json").path, "--track-id", "track", "--stems", "--stems-only",
            "--scnet-model", File(tmp, "absent.onnx").path, "--out-stems", manifest.path,
            "--window-start-ms", "200", "--window-end-ms", "700", "--window-padding-ms", "100", "--required-stems", "BASS"))
        assertEquals(0, rc)
        val value = Json.decodeFromString(StemManifest.serializer(), manifest.readText())
        assertFalse(value.available); assertTrue(value.stems.isEmpty()); assertTrue(value.reason.contains("absent"))
        assertEquals(4_800, value.windowFirstFrame); assertEquals(38_400, value.windowEndFrame)
        assertEquals(PcmIdentity.file(input), value.sourcePcmSha256); assertEquals(listOf("BASS"), value.requiredStems)
        assertFalse(File(tmp, "stems.bass.f32").exists()); assertFalse(File(tmp, "unused-analysis.json").exists())
    }

    @Test fun alignedStemWriterPreservesFrameIdentityOffsetsAndHash() = temporary { tmp ->
        val file = File(tmp, "stem.f32")
        val part = WavReader.Stereo(floatArrayOf(0.25f, -0.5f), floatArrayOf(0.75f, 0.1f), 48_000)
        TrackAnalysisExporter.writeAlignedStem(file, 8, part, 3)
        val decoded = WavReader.readStereoPcm(file, 48_000)
        assertEquals(8 * 8L, file.length()); assertEquals(0.25f, decoded.left[3]); assertEquals(0.1f, decoded.right[4])
        assertTrue(decoded.left.take(3).all { it == 0f }); assertTrue(decoded.right.drop(5).all { it == 0f })
        assertEquals(PcmIdentity.stereo(decoded), PcmIdentity.file(file))
    }

    private fun temporary(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("ae3-export-test-").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }
}
