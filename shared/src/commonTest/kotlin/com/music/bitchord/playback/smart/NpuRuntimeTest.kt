package com.music.bitchord.playback.smart

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AUTOMIX-V2(A): runtime switch, auto-disable policy, QDQ routing, QNN options, self-test maths. */
class NpuRuntimeTest {

    @AfterTest
    fun reset() {
        NpuRuntime.install(null)
        NpuRuntime.installSocModel(null)
        AutomixV2Npu.enabled = true
        AutomixV2Npu.requireSelfTestPass = true
    }

    private fun installed(enabled: Boolean = true, version: Long = 92L): InMemoryNpuPolicyStore {
        val store = InMemoryNpuPolicyStore(enabled = enabled)
        NpuRuntime.install(NpuPolicy(store, version))
        return store
    }

    @Test
    fun offWithoutPolicyAndFollowsUserSwitch() {
        assertFalse(NpuSupport.runtimeEnabled())
        val store = installed(enabled = true)
        assertTrue(NpuSupport.runtimeEnabled())
        store.enabled = false
        assertFalse(NpuRuntime.runtimeEnabled())
        store.enabled = true
        AutomixV2Npu.enabled = false
        assertFalse(NpuRuntime.runtimeEnabled())
    }

    @Test
    fun legacyCompileTimeGateStaysOff() {
        // The v2 route never flips the legacy dynamic-int8 path on.
        installed()
        assertFalse(NpuSupport.QNN_ENABLED)
    }

    @Test
    fun threeConsecutiveFailuresAutoDisableUntilVersionChanges() {
        val store = installed()
        val policy = NpuRuntime.policy()!!
        assertFalse(policy.recordFailure("a"))
        policy.recordSuccess()
        assertFalse(policy.recordFailure("b"))
        assertFalse(policy.recordFailure("c"))
        assertTrue(NpuRuntime.runtimeEnabled())
        assertTrue(policy.recordFailure("d"))
        assertFalse(NpuRuntime.runtimeEnabled())
        assertTrue(store.state!!.autoDisabled)
        // Persisted: a new process with the same versionCode stays disabled ...
        NpuRuntime.install(NpuPolicy(store, 92L))
        assertFalse(NpuRuntime.runtimeEnabled())
        // ... and a new versionCode resets it.
        NpuRuntime.install(NpuPolicy(store, 93L))
        assertTrue(NpuRuntime.runtimeEnabled())
        assertEquals(0, store.state!!.consecutiveFailures)
    }

    @Test
    fun selfTestVerdictsGateProduction() {
        installed()
        val policy = NpuRuntime.policy()!!
        assertFalse(NpuRuntime.productionQnnAllowed())
        policy.recordSelfTest(NpuVerdict.SLOW, "qnn slower")
        assertFalse(NpuRuntime.productionQnnAllowed())
        assertTrue(NpuRuntime.runtimeEnabled())
        policy.recordSelfTest(NpuVerdict.PASS, "ok")
        assertTrue(NpuRuntime.productionQnnAllowed())
        assertTrue(NpuRuntime.shouldAttemptQnn(NpuSupport.NpuConfig(), NpuSupport.Vendor.QUALCOMM))
        assertFalse(NpuRuntime.shouldAttemptQnn(NpuSupport.NpuConfig(), NpuSupport.Vendor.MEDIATEK))
        policy.recordSelfTest(NpuVerdict.MISMATCH, "maxAbs=9")
        assertFalse(NpuRuntime.runtimeEnabled())
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
