package com.music.bitchord.playback.smart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * CPU session factory using the official ONNX Runtime Java interfaces:
 * https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html
 *
 * djmix52 links the standard ORT 1.31.0 AAR. No backend loading, vendor path setup,
 * provider registration or EP-context engine is included here. The dormant QNN
 * signatures remain for compatibility, not as an alternative way around the release gate.
 * Session creation belongs on the analysis/preparation worker, never on the audio thread.
 */
object NpuSession {
    @Suppress("UNUSED_PARAMETER")
    fun open(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        vendor: NpuSupport.Vendor,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
    ): OrtSession = try {
        openCpu(modelPath, threads)
    } catch (failure: Throwable) {
        AnalysisLog.warn("ORT CPU open failed; model unavailable", failure)
        throw failure
    }

    /** One CPU EP, up to four intra-op workers, one inter-op worker, no NNAPI/delegate. */
    fun openCpu(modelPath: String, threads: Int): OrtSession {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(threads.coerceIn(1, 4))
        options.setInterOpNumThreads(1)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setCPUArenaAllocator(true)
        options.setMemoryPatternOptimization(true)
        // SessionOptions must outlive sessions created from them (official Java API contract).
        return OrtEnvironment.getEnvironment().createSession(modelPath, options)
    }

    @Suppress("UNUSED_PARAMETER")
    fun openQnn(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
        provider: NpuSupport.InferenceProvider = NpuSupport.InferenceProvider.QNN_HTP_QDQ,
    ): OrtSession {
        check(NpuRuntime.RUNTIME_AVAILABLE && NpuSupport.QNN_ENABLED) { "QNN is not supplied in djmix52" }
        throw UnsupportedOperationException("A separately licensed runtime and verified model must implement this entry point")
    }

    fun openQnnDiagnostic(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
        provider: NpuSupport.InferenceProvider = NpuSupport.InferenceProvider.QNN_HTP_QDQ,
    ): OrtSession = openQnn(modelPath, threads, config, backendPath, cacheDir, socModel, provider)
}
