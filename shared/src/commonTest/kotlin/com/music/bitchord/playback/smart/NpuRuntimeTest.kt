package com.music.bitchord.playback.smart

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * djmix52: the NPU route is dark in every configuration. These tests pin that
 * down (no policy, persisted opt-in, self-test verdict or versionCode bump can
 * open it) and keep the pure policy/routing/self-test maths under test.
 */
class NpuRuntimeTest {

    @AfterTest
    fun reset() {
        NpuRuntime.install(null)
        NpuRuntime.installSocModel(null)
        NpuRuntime.installBackendProvider(null)
        AutomixV2Npu.enabled = true
        AutomixV2Npu.requireSelfTestPass = true
    }

    private fun installed(enabled: Boolean = true, version: Long = 92L): InMemoryNpuPolicyStore {
        val store = InMemoryNpuPolicyStore(enabled = enabled)
        NpuRuntime.install(NpuPolicy(store, version))
        return store
    }

    @Test
    fun releaseGateKeepsRuntimeOff() {
        // Both compile-time gates are off, and RUNTIME_AVAILABLE wins over everything else.
        assertFalse(NpuRuntime.RUNTIME_AVAILABLE)
        assertFalse(NpuSupport.QNN_ENABLED)
        assertFalse(NpuRuntime.runtimeEnabled())
        assertFalse(NpuRuntime.productionQnnAllowed())
    }

    @Test
    fun persistedOptInCannotEnableTheNpu() {
        installed(enabled = true)
        AutomixV2Npu.enabled = true
        assertFalse(NpuRuntime.runtimeEnabled())
        // A self-test PASS recorded by an older build still does not open the gate.
        NpuRuntime.policy()!!.recordSelfTest(NpuVerdict.PASS, "stale")
        assertFalse(NpuRuntime.runtimeEnabled())
        assertFalse(NpuRuntime.productionQnnAllowed())
        assertFalse(NpuRuntime.shouldAttemptQnn(NpuSupport.NpuConfig(), NpuSupport.Vendor.QUALCOMM))
    }

    @Test
    fun legacyCompileTimeGateStaysOff() {
        installed()
        assertFalse(NpuSupport.QNN_ENABLED)
        assertFalse(NpuSupport.shouldAttemptQnn(NpuSupport.NpuConfig(enabled = true), NpuSupport.Vendor.QUALCOMM))
    }

    @Test
    fun backendProviderIsNeverInvokedWhileDark() {
        var calls = 0
        installed()
        NpuRuntime.installBackendProvider { calls++; "/data/app/lib/libQnnHtp.so" }
        assertNull(NpuRuntime.backendPath())
        assertEquals(0, calls)
    }

    @Test
    fun policyStateMachineStillTracksFailuresAndVerdicts() {
        // The persisted policy keeps working even though nothing can consume it.
        val store = installed()
        val policy = NpuRuntime.policy()!!
        assertTrue(policy.enabled())
        assertFalse(policy.recordFailure("a"))
        policy.recordSuccess()
        assertFalse(policy.recordFailure("b"))
        assertFalse(policy.recordFailure("c"))
        assertTrue(policy.recordFailure("d"))
        assertFalse(policy.enabled())
        assertTrue(store.state!!.autoDisabled)
        // Persisted across a process restart with the same versionCode ...
        NpuRuntime.install(NpuPolicy(store, 92L))
        assertFalse(NpuRuntime.policy()!!.enabled())
        // ... and reset by a new versionCode.
        NpuRuntime.install(NpuPolicy(store, 93L))
        assertTrue(NpuRuntime.policy()!!.enabled())
        assertEquals(0, store.state!!.consecutiveFailures)
        // None of that reaches the runtime gate.
        assertFalse(NpuRuntime.runtimeEnabled())
    }

    @Test
    fun selfTestVerdictsAreRecordedButNeverUnlockProduction() {
        installed()
        val policy = NpuRuntime.policy()!!
        policy.recordSelfTest(NpuVerdict.SLOW, "qnn slower")
        assertTrue(policy.selfTestDone())
        assertFalse(policy.selfTestPassed())
        policy.recordSelfTest(NpuVerdict.PASS, "ok")
        assertTrue(policy.selfTestPassed())
        assertFalse(NpuRuntime.productionQnnAllowed())
        assertFalse(NpuRuntime.shouldAttemptQnn(NpuSupport.NpuConfig(), NpuSupport.Vendor.QUALCOMM))
        policy.recordSelfTest(NpuVerdict.MISMATCH, "maxAbs=9")
        assertTrue(policy.state().autoDisabled)
        assertFalse(NpuRuntime.productionQnnAllowed())
    }

    @Test
    fun onlyStaticQdqSiblingsOfNonRecurrentModelsMayUseQnn() {
        assertEquals("beat_this_final0_qdq.onnx", NpuRuntime.qnnCandidateName("beat_this_final0_q8.onnx"))
        assertEquals("beat_this_final0_qdq.onnx", NpuRuntime.qnnCandidateName("beat_this_final0_qdq.onnx"))
        assertEquals("beat_this_final0_qdq_ctx.onnx", NpuRuntime.precompiledContextName("beat_this_final0_qdq.onnx"))
        assertNull(NpuRuntime.qnnCandidateName("scnet.int8.onnx"))
        assertNull(NpuRuntime.qnnCandidateName("vocals_umxhq_int8.onnx"))
        assertNull(NpuRuntime.qnnCandidateName("x_ctx.onnx"))
    }

    @Test
    fun htpTargetPinnedOnlyForVerifiedSocs() {
        assertEquals(NpuRuntime.HtpTarget(73, 68), NpuRuntime.htpTargetFor("SM8635"))
        assertEquals(NpuRuntime.HtpTarget(73, null), NpuRuntime.htpTargetFor("SM8550"))
        assertNull(NpuRuntime.htpTargetFor("SM8650"))
        assertNull(NpuRuntime.htpTargetFor(null))
    }

    @Test
    fun providerOptionsAndSessionConfig() {
        val options = NpuRuntime.qnnProviderOptions("/lib/libQnnHtp.so", NpuRuntime.PERF_BURST,
            NpuRuntime.HtpTarget(73, 68), NpuRuntime.QnnConfig())
        assertEquals("/lib/libQnnHtp.so", options["backend_path"])
        assertEquals("burst", options["htp_performance_mode"])
        assertEquals("3", options["htp_graph_finalization_optimization_mode"])
        assertEquals("1", options["enable_htp_fp16_precision"])
        assertEquals("1", options["offload_graph_io_quantization"])
        assertEquals("73", options["htp_arch"])
        assertEquals("68", options["soc_model"])
        assertTrue("htp_arch" !in NpuRuntime.qnnProviderOptions(null, NpuRuntime.PERF_BURST, null, NpuRuntime.QnnConfig()))
        assertEquals(NpuRuntime.PERF_SUSTAINED, NpuRuntime.performanceModeFor("scnet.int8.onnx"))
        assertEquals(NpuRuntime.PERF_BURST, NpuRuntime.performanceModeFor("beat_this_final0_qdq.onnx"))

        val strict = NpuRuntime.qnnSessionConfig(NpuRuntime.QnnConfig(strictPartition = true), null)
        assertEquals("1", strict["session.disable_cpu_ep_fallback"])
        assertTrue("ep.context_enable" !in strict)
        val generating = NpuRuntime.qnnSessionConfig(NpuRuntime.QnnConfig(), "/cache/x_ctx.onnx")
        assertEquals("0", generating["session.disable_cpu_ep_fallback"])
        assertEquals("1", generating["ep.context_enable"])
        assertEquals("/cache/x_ctx.onnx", generating["ep.context_file_path"])
    }

    @Test
    fun contextCacheKeyChangesWithModelRuntimeAndSoc() {
        val base = NpuRuntime.contextCacheFileName("m_qdq.onnx", "ABCDEF0123456789FFFF", "1.29.0", "SM8635", "k")
        assertTrue(base.startsWith(NpuRuntime.contextCachePrefix("m_qdq.onnx")))
        assertTrue(base.endsWith("_ctx.onnx"))
        assertNotEquals(base, NpuRuntime.contextCacheFileName("m_qdq.onnx", "abcdef0123456780", "1.29.0", "SM8635", "k"))
        assertNotEquals(base, NpuRuntime.contextCacheFileName("m_qdq.onnx", "abcdef0123456789", "1.31.0", "SM8635", "k"))
        assertNotEquals(base, NpuRuntime.contextCacheFileName("m_qdq.onnx", "abcdef0123456789", "1.29.0", "SM8550", "k"))
        val a = NpuRuntime.qnnProviderOptions("/a", NpuRuntime.PERF_BURST, null, NpuRuntime.QnnConfig())
        val b = NpuRuntime.qnnProviderOptions("/b", NpuRuntime.PERF_SUSTAINED, null, NpuRuntime.QnnConfig())
        assertEquals(NpuRuntime.optionsFingerprint(a), NpuRuntime.optionsFingerprint(b))
        val c = NpuRuntime.qnnProviderOptions("/a", NpuRuntime.PERF_BURST, null, NpuRuntime.QnnConfig(fp16Precision = false))
        assertNotEquals(NpuRuntime.optionsFingerprint(a), NpuRuntime.optionsFingerprint(c))
    }

    @Test
    fun selfTestMaths() {
        val input = NpuRuntime.selfTestInput(50, 8)
        assertEquals(400, input.size)
        assertTrue(input.contentEquals(NpuRuntime.selfTestInput(50, 8)))
        val same = NpuRuntime.compareLogits(floatArrayOf(1f, 2f), floatArrayOf(1f, 2.5f))
        assertEquals(0.5, same.maxAbs, 1e-6)
        assertEquals(1.0, NpuRuntime.peakFMeasure(listOf(10.0, 35.0), listOf(11.0, 34.0), 2.0), 1e-9)
        assertEquals(NpuVerdict.PASS, NpuRuntime.judgeSelfTest(NpuRuntime.LogitDiff(0.2, 0.02, 10), 1.0, 10.0, 40.0))
        assertEquals(NpuVerdict.SLOW, NpuRuntime.judgeSelfTest(NpuRuntime.LogitDiff(0.2, 0.02, 10), null, 50.0, 40.0))
        assertEquals(NpuVerdict.MISMATCH, NpuRuntime.judgeSelfTest(NpuRuntime.LogitDiff(4.0, 0.02, 10), null, 10.0, 40.0))
        assertEquals(NpuVerdict.MISMATCH, NpuRuntime.judgeSelfTest(NpuRuntime.LogitDiff(0.2, 0.02, 10), 0.5, 10.0, 40.0))
    }
}
