package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Device classification, dormant provider option maps and the CPU fallback flags.
 * The option keys are the public ORT QNN provider interface; djmix52 never opens
 * such a session (see [NpuRuntime.RUNTIME_AVAILABLE]), so this pins the shape of a
 * future route rather than the shipped one.
 */
class NpuSupportTest {

    @Test
    fun qualcommChipsetsAreDetected() {
        assertEquals(NpuSupport.Vendor.QUALCOMM,
            NpuSupport.detectVendor("Qualcomm", "qcom", "SM-S928B", "samsung"))
        assertEquals(NpuSupport.Vendor.QUALCOMM,
            NpuSupport.detectVendor("", "qcom", "Pixel 8", "Google"))
        assertEquals(NpuSupport.Vendor.QUALCOMM,
            NpuSupport.detectVendor("", "", "SM-G991B", "samsung"))
    }

    @Test
    fun nonQualcommIsNotDetectedAsQualcomm() {
        assertEquals(NpuSupport.Vendor.MEDIATEK,
            NpuSupport.detectVendor("MediaTek", "mt6893", "Xiaomi 12", "Xiaomi"))
        assertEquals(NpuSupport.Vendor.NONE,
            NpuSupport.detectVendor("Google", "Tensor", "Pixel 8", "Google"))
        assertEquals(NpuSupport.Vendor.NONE,
            NpuSupport.detectVendor(null, null, null, null))
        // A Samsung model that is not SM never qualifies on model alone.
        assertEquals(NpuSupport.Vendor.NONE,
            NpuSupport.detectVendor("", "", "Galaxy S25", "samsung"))
    }

    @Test
    fun qnnIsAttemptedOnlyWhenEnabledAndQualcomm() {
        // djmix52 ships no QNN runtime, so every combination is false. The vendor and
        // user-switch shape is still asserted so a future release cannot silently
        // widen it.
        assertFalse(NpuSupport.shouldAttemptQnn(NpuSupport.NpuConfig(enabled = true), NpuSupport.Vendor.QUALCOMM))
        assertFalse(NpuSupport.shouldAttemptQnn(NpuSupport.NpuConfig(enabled = false), NpuSupport.Vendor.QUALCOMM))
        assertFalse(NpuSupport.shouldAttemptQnn(NpuSupport.NpuConfig(enabled = true), NpuSupport.Vendor.MEDIATEK))
        assertFalse(NpuSupport.shouldAttemptQnn(NpuSupport.NpuConfig(enabled = true), NpuSupport.Vendor.NONE))
        // The production provider stays CPU, and the compile-time gate stays closed.
        assertFalse(NpuSupport.QNN_ENABLED)
        assertEquals(NpuSupport.InferenceProvider.CPU_DEFAULT, NpuSupport.defaultProvider())
    }

    @Test
    fun providerOptionsMirrorQnnRunner() {
        val options = NpuSupport.qnnProviderOptions("/data/app/base.apk/lib/arm64/libQnnHtp.so")
        assertEquals("burst", options["htp_performance_mode"])
        assertEquals("3", options["htp_graph_finalization_optimization_mode"])
        assertEquals("/data/app/base.apk/lib/arm64/libQnnHtp.so", options["backend_path"])
    }

    @Test
    fun providerOptionsWithoutBackendPathStillConfiguresBurst() {
        val options = NpuSupport.qnnProviderOptions(null)
        assertEquals("burst", options["htp_performance_mode"])
        assertTrue("backend_path" !in options)
    }

    @Test
    fun sessionConfigKeepsCpuFallback() {
        // Orchard passes 1 (no fallback); BitChord passes 0 so the NPU is
        // an optimization, never a requirement.
        val entries = NpuSupport.qnnSessionConfig(NpuSupport.NpuConfig(disableCpuFallback = false), "/cache/ctx.onnx")
        assertEquals("0", entries["session.disable_cpu_ep_fallback"])
        assertEquals("0", entries["session.intra_op.allow_spinning"])
        assertEquals("1", entries["ep.context_enable"])
        assertEquals("1", entries["ep.context_embed_mode"])
        assertEquals("/cache/ctx.onnx", entries["ep.context_file_path"])
    }

    @Test
    fun sessionConfigHidesContextWhenCachingOff() {
        val entries = NpuSupport.qnnSessionConfig(NpuSupport.NpuConfig(contextCaching = false), "/cache/ctx.onnx")
        assertTrue("ep.context_enable" !in entries)
        assertTrue("ep.context_file_path" !in entries)
    }

    @Test
    fun bundledBackendPathLivesInAppLibDirNotVendor() {
        val path = NpuSupport.bundledBackendPath("/data/app/base.apk/lib/arm64")
        assertEquals("/data/app/base.apk/lib/arm64/libQnnHtp.so", path)
        assertFalse(path.startsWith("/vendor/"))
    }
}
