package com.music.bitchord.playback.smart

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NpuContextFingerprintTest {
    @Test
    fun cacheKeyTracksModelRuntimeQairtArchOptionsAndFirmware() {
        val dir = Files.createTempDirectory("bitchord-epcontext-test").toFile()
        try {
            val model = dir.resolve("nirovi_qnn.onnx").apply { writeText("model-a") }
            val backend = dir.resolve("libQnnHtp.so").apply { writeText("qairt-a") }
            val runtime = dir.resolve("libonnxruntime.so").apply { writeText("ort-a") }
            val config = NpuSupport.NpuConfig(deviceFingerprint = "firmware-a")
            fun key(c: NpuSupport.NpuConfig = config, version: String = "1.31.0") =
                qnnContextFingerprint(model, backend, c, version)
            val original = key()
            assertEquals(64, original.length)
            assertEquals(original, key())
            assertNotEquals(original, key(version = "1.32.0"))
            assertNotEquals(original, key(config.copy(htpArch = "75")))
            assertNotEquals(original, key(config.copy(htpPerformanceMode = "balanced")))
            assertNotEquals(original, key(config.copy(graphFinalizationOptMode = "2")))
            assertNotEquals(original, key(config.copy(deviceFingerprint = "firmware-b")))
            model.writeText("model-b")
            assertNotEquals(original, key())
            model.writeText("model-a")
            backend.writeText("qairt-b")
            assertNotEquals(original, key())
            backend.writeText("qairt-a")
            runtime.writeText("ort-b")
            assertNotEquals(original, key())
        } finally {
            dir.deleteRecursively()
        }
    }
}
