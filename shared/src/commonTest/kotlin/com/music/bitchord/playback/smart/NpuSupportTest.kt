package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * vc69 NPU: Snapdragon detection, QNN option maps, and CPU fallback flags.
 * Mirrors the OrchID qnn_runner.cpp option set (burst HTP, context
 * caching) with BitChord CPU fallback kept (0), not Orchard disabled (1).
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
        // vc70 ORT restore: master QNN_ENABLED=false forces all false;
        // flipping it restores the enabled+Qualcomm expectation below.
        assertEquals(NpuSupport.QNN_ENABLED, NpuSupport.shouldAttemptQnn(NpuSupport.NpuConfig(enabled = true), NpuSupport.Vendor.QUALCOMM))
        assertFalse(NpuSupport.shouldAttemptQnn(NpuSupport.NpuConfig(enabled = false), NpuSupport.Vendor.QUALCOMM))
        assertFalse(NpuSupport.shouldAttemptQnn(NpuSupport.NpuConfig(enabled = true), NpuSupport.Vendor.MEDIATEK))
        assertFalse(NpuSupport.shouldAttemptQnn(NpuSupport.NpuConfig(enabled = true), NpuSupport.Vendor.NONE))
    }

    @Test
    fun providerOptionsMirrorQnnRunner() {
        val options = NpuSupport.qnnProviderOptions("/data/app/base.apk/lib/arm64/libQnnHtp.so")
        assertEquals("burst", options["htp_performance_mode"])
        assertEquals("3", options["htp_graph_finalization_optimization_mode"])
        assertEquals("73", options["htp_arch"])
        assertEquals("/data/app/base.apk/lib/arm64/libQnnHtp.so", options["backend_path"])
    }

    @Test
    fun providerOptionsWithoutBackendPathStillConfiguresBurst() {
        val options = NpuSupport.qnnProviderOptions(null)
        assertEquals("burst", options["htp_performance_mode"])
        assertEquals("73", options["htp_arch"])
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

    @Test
    fun candidateAndQnnStayOffPendingBothEvidenceGates() {
        assertFalse(NpuSupport.BEAT_QDQ_ENABLED)
        assertFalse(NpuSupport.QNN_ENABLED)
    }

    @Test
    fun htpRequiresBackendAndLinkerVisibleCdspRpc() {
        assertFalse(NpuSupport.htpPreflightReady(false, false))
        assertFalse(NpuSupport.htpPreflightReady(true, false))
        assertFalse(NpuSupport.htpPreflightReady(false, true))
        assertTrue(NpuSupport.htpPreflightReady(true, true))
    }

    @Test
    fun onlyProbeDisablesCpuFallbackAndBypassesContextCache() {
        val playback = NpuSupport.NpuConfig()
        val probe = NpuSupport.probeConfig(playback)
        val entries = NpuSupport.qnnSessionConfig(probe, "/cache/old-context.onnx")
        assertEquals("1", entries["session.disable_cpu_ep_fallback"])
        assertTrue("ep.context_enable" !in entries)
        assertTrue("ep.context_file_path" !in entries)
        assertFalse(playback.disableCpuFallback)
        assertTrue(playback.contextCaching)
    }

    @Test
    fun providerOptionsHonorExplicitConfig() {
        val options = NpuSupport.qnnProviderOptions(null, NpuSupport.NpuConfig(
            htpPerformanceMode = "balanced", graphFinalizationOptMode = "2", htpArch = "75",
        ))
        assertEquals("balanced", options["htp_performance_mode"])
        assertEquals("2", options["htp_graph_finalization_optimization_mode"])
        assertEquals("75", options["htp_arch"])
    }
}
