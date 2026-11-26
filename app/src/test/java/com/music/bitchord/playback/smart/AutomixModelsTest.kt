package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * djmix52 model delivery: the consent matrix, the pin set and the verification
 * primitive. These are the parts a UI or a network hiccup must never be able to
 * widen, and none of them need an emulator.
 */
class AutomixModelsTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `unknown network state is never permission`() {
        // null means offline or "not yet known" (AppSettings.meteredConnection starts null).
        assertFalse(modelNetworkAllowed(null, allowMetered = false))
        assertFalse(modelNetworkAllowed(null, allowMetered = true))
    }

    @Test
    fun `metered needs explicit consent and unmetered never does`() {
        assertTrue(modelNetworkAllowed(metered = false, allowMetered = false))
        assertTrue(modelNetworkAllowed(metered = false, allowMetered = true))
        assertFalse(modelNetworkAllowed(metered = true, allowMetered = false))
        assertTrue(modelNetworkAllowed(metered = true, allowMetered = true))
    }

    @Test
    fun `pins are the three authorised release models`() {
        assertEquals(3, listOf(AutomixModels.BEAT, AutomixModels.VOCALS, AutomixModels.SCNET).size)
        assertEquals("beat_this_final0_q8.onnx", AutomixModels.BEAT.fileName)
        assertEquals(23_138_816L, AutomixModels.BEAT.bytes)
        assertEquals(
            "7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd",
            AutomixModels.BEAT.sha256,
        )
        assertEquals("vocals_umxhq_int8.onnx", AutomixModels.VOCALS.fileName)
        assertEquals(9_054_206L, AutomixModels.VOCALS.bytes)
        assertEquals(
            "a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb",
            AutomixModels.VOCALS.sha256,
        )
        assertEquals("scnet.int8.onnx", AutomixModels.SCNET.fileName)
        assertEquals(12_900_277L, AutomixModels.SCNET.bytes)
        assertEquals(
            "98228931494151762a1c4ab1ec7899a894b1f81fd4a509921a9d2f9bacc50845",
            AutomixModels.SCNET.sha256,
        )
        // Total of the models-v1 manifest; the unpinned dynamic-int8 experiment is not a fourth model.
        assertEquals(45_093_299L, AutomixModels.totalBytes)
        assertEquals(45_093_299L, AutomixModels.BEAT.bytes + AutomixModels.VOCALS.bytes + AutomixModels.SCNET.bytes)
    }

    @Test
    fun `every pin points at the release asset with no url prefix`() {
        for (model in listOf(AutomixModels.BEAT, AutomixModels.VOCALS, AutomixModels.SCNET)) {
            assertEquals(
                "https://github.com/survey-sudo/bitchord-mix/releases/download/models-v1/${model.fileName}",
                model.url,
            )
        }
    }

    @Test
    fun `bundled assets are not claimed`() {
        // SettingsSheet still tests this constant to decide whether to show the row.
        assertFalse(AutomixModels.BUNDLED_ASSETS_ACTIVE)
    }

    @Test
    fun `verification accepts only the pinned content`() {
        val dir = File.createTempFile("automix-models", "").let { it.delete(); it.mkdirs(); it }
        try {
            val payload = ByteArray(4096) { (it * 31).toByte() }
            val file = File(dir, "model.onnx").apply { writeBytes(payload) }
            val sha = sha256(payload)

            val accepted = verifiedModelIdentity(file, payload.size.toLong(), sha)
            assertTrue("correct bytes, size and hash must verify", accepted != null)
            assertEquals(payload.size.toLong(), accepted!!.bytes)

            assertNull("a wrong hash is refused", verifiedModelIdentity(file, payload.size.toLong(), sha256(byteArrayOf(1))))
            assertNull("a wrong size is refused", verifiedModelIdentity(file, payload.size.toLong() + 1, sha))

            // A same-size rewrite invalidates the identity, even with the hash of the old bytes.
            file.writeBytes(ByteArray(payload.size) { 7 })
            assertNull(
                "changed content is refused",
                verifiedModelIdentity(file, payload.size.toLong(), sha),
            )
            assertNull("a directory is refused", verifiedModelIdentity(dir, 0L, sha))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `identity is stable when untouched and differs after a rewrite`() {
        val dir = File.createTempFile("automix-identity", "").let { it.delete(); it.mkdirs(); it }
        try {
            val file = File(dir, "model.onnx")
            file.writeBytes(ByteArray(1024) { 1 })
            val first = modelFileIdentity(file)
            assertTrue("a regular file has an identity", first != null)
            assertEquals("an untouched file keeps its identity", first, modelFileIdentity(file))

            file.writeBytes(ByteArray(1024) { 2 })
            // Force a distinguishable mtime so this does not depend on filesystem timestamp
            // granularity; ctime moves on its own with the write.
            file.setLastModified(first!!.modified.toMillis() + 5000L)
            val second = modelFileIdentity(file)
            assertTrue("a same-size rewrite must not compare equal", first != second)
        } finally {
            dir.deleteRecursively()
        }
    }
}
