package com.music.bitchord.playback.smart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * NPU-aware ONNX Runtime session opening (vc70 NPU). Orchard opens its QNN
 * addon with the HTP backend in burst mode plus context caching (qnn_runner
 * open: backend_path, htp performance burst, disable cpu fallback). BitChord
 * mirrors that option set inside the bundled ONNX Runtime 1.31.0 (npu-addon
 * source-built QNN runtime), then falls back to the CPU execution provider
 * when the QNN session fails to open, so the NPU stays an optimization and
 * never a requirement. This runs once per model on the analysis thread, never
 * on the audio thread.
 *
 * vc92 adds the XNNPACK delegate as an explicit, opt-in CPU provider
 * ([openXnnpack]) so the on-device self-test can measure CPU_DEFAULT against
 * CPU_XNNPACK on the target SM8635 before anything becomes a default. The
 * shipped default stays [NpuSupport.InferenceProvider.CPU_DEFAULT] and
 * [NpuSupport.QNN_ENABLED] stays false.
 */
object NpuSession {

    /**
     * Opens the session for [provider]. CPU_XNNPACK is attempted only when the
     * caller asks for it explicitly; every other path resolves exactly as
     * before (QNN when enabled and permitted, plain CPU otherwise).
     */
    fun open(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        vendor: NpuSupport.Vendor,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
        provider: NpuSupport.InferenceProvider = NpuSupport.resolveProvider(config, vendor),
    ): OrtSession {
        // CPU_XNNPACK is an explicit opt-in (measurement builds): the CPU
        // execution provider with the XNNPACK delegate attached. A delegate
        // that cannot open must never cost the model, so the fallback to the
        // plain CPU session is silent here and the shared CPU block below
        // still reports its own failure honestly.
        if (provider == NpuSupport.InferenceProvider.CPU_XNNPACK) {
            try {
                return openXnnpack(modelPath, threads)
            } catch (_: Throwable) {
                // Silent by design: fall through to openCpu below.
            }
        }
        // Startup guard: any QNN failure degrades to CPU; CPU fallback
        // itself throws and callers (BeatTracker/VocalTracker session())
        // convert that to null via runCatching, so analysis stays off.
        if (!NpuSupport.isCpuProvider(provider) && NpuSupport.shouldAttempt(provider, config, vendor)) {
            try {
                return openQnn(modelPath, threads, config, backendPath, cacheDir, socModel, provider)
            } catch (failure: Throwable) {
                if (config.disableCpuFallback) throw failure
                AnalysisLog.warn("NPU QNN open failed; falling back to CPU", failure)
            }
        }
        try {
            return openCpu(modelPath, threads)
        } catch (failure: Throwable) {
            AnalysisLog.warn("NPU CPU open failed; model unavailable", failure)
            throw failure
        }
    }

    /**
     * Opens the plain CPU execution provider session (the shipped default).
     * Immediate CPU ORT wins: the arena allocator and the memory pattern
     * optimisation are both ON, and the thread pool is pinned to two intra-op /
     * one inter-op threads so a backgrounded music player never starves playback
     * on the same silicon (the SM8635 underruns when ORT would otherwise take
     * every core). The arena stays on because it is what makes repeated runs on
     * the same shape cheap: the retained blocks are this session's own working
     * set for the life of the tracker, not a per-run leak, and this session is
     * opened once per model and closed with the tracker.
     */
    fun openCpu(modelPath: String, threads: Int): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            // CPU execution provider. NNAPI is not used.
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setCPUArenaAllocator(true)
            setMemoryPatternOptimization(true)
        }
        return OrtEnvironment.getEnvironment().createSession(modelPath, options)
    }

    /**
     * Opens the CPU execution provider with the XNNPACK delegate attached
     * (vc92 measurement path, see NpuSelfTest.benchmarkCpuVsXnnpack). Tuning
     * mirrors [openCpu] -- arena allocator and memory pattern optimisation on,
     * two intra-op / one inter-op threads -- and the delegate is pinned to two
     * intra-op threads for the same reason: a backgrounded music player must
     * not lose cores to a delegate that would otherwise take them all.
     *
     * Deliberately not a production default: [NpuSupport.defaultProvider] stays
     * CPU_DEFAULT until the on-device benchmark proves both lower latency and
     * logit parity on the target SM8635. [threads] is accepted for symmetry with
     * [openCpu]; the XNNPACK tuning is fixed at two threads on purpose so the
     * measurement compares provider choice, not thread counts.
     */
    @Suppress("UNUSED_PARAMETER")
    fun openXnnpack(modelPath: String, threads: Int): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setCPUArenaAllocator(true)
            setMemoryPatternOptimization(true)
            addXnnpack(mapOf("intra_op_num_threads" to "2"))
        }
        return OrtEnvironment.getEnvironment().createSession(modelPath, options)
    }

    // Widened from private for the NPU self-test (NpuSelfTest, app module):
    // the probe must attempt QNN open directly so its FAILED log is
    // truthful instead of being masked by the CPU fallback in open(). Not
    // planner/DSP logic.
    fun openQnn(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
        provider: NpuSupport.InferenceProvider = NpuSupport.InferenceProvider.QNN_HTP_QDQ,
    ): OrtSession {
        // Dormant by default: QNN_ENABLED=false makes this unreachable in
        // production (open() gates on the resolved provider, which is
        // CPU_DEFAULT while QNN_ENABLED is false, and NpuSelfTest.schedule
        // returns early unless a test-build harness is on). The check keeps
        // any future direct caller from loading QNN libs.
        check(NpuSupport.QNN_ENABLED) { "QNN disabled (NpuSupport.QNN_ENABLED=false)" }
        val contextPath = cacheDir?.let { File(it, "ctx.onnx").absolutePath }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            addQnn(
                NpuSupport.qnnProviderOptions(
                    backendPath = backendPath,
                    socModel = socModel,
                    backendType = if (provider == NpuSupport.InferenceProvider.QNN_GPU_FP16) "gpu" else null,
                ),
            )
            for ((key, value) in NpuSupport.qnnSessionConfig(config, contextPath)) {
                addConfigEntry(key, value)
            }
        }
        return OrtEnvironment.getEnvironment().createSession(modelPath, options)
    }

    /**
     * QNN diagnostic session (test builds only): same open as [openQnn] but with
     * the CPU execution provider fallback disabled (disable_cpu_ep_fallback = "1")
     * and ORT profiling enabled, so a full graph partition can be proven instead of
     * accepting a silent CPU fallback. Reached only while explicitly enabled in a
     * test build; while QNN is off the diagnostic is unreachable and behaviour is
     * unchanged.
     */
    fun openQnnDiagnostic(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
        provider: NpuSupport.InferenceProvider = NpuSupport.InferenceProvider.QNN_HTP_QDQ,
    ): OrtSession {
        check(NpuSupport.QNN_ENABLED) { "QNN disabled (NpuSupport.QNN_ENABLED=false)" }
        val contextPath = cacheDir?.let { File(it, "ctx.onnx").absolutePath }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            addQnn(
                NpuSupport.qnnProviderOptions(
                    backendPath = backendPath,
                    socModel = socModel,
                    backendType = if (provider == NpuSupport.InferenceProvider.QNN_GPU_FP16) "gpu" else null,
                ),
            )
            for ((key, value) in NpuSupport.qnnSessionConfig(config, contextPath)) {
                addConfigEntry(key, value)
            }
            // Diagnostic: prove full graph partitioning, never accept a silent CPU fallback.
            addConfigEntry("session.disable_cpu_ep_fallback", "1")
            enableProfiling("BitChordNPU")
        }
        return OrtEnvironment.getEnvironment().createSession(modelPath, options)
    }
}
