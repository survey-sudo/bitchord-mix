package com.music.bitchord.playback.smart

/**
 * NPU execution provider configuration: Snapdragon QNN for the Beat This and
 * umxhq models, with CPU fallback. Orchard ships a separate add-on
 * (liborchard_qnn.so over its own ONNX Runtime, QNN execution provider in
 * burst performance mode with context caching, CPU fallback disabled) for its
 * Nirovi and Syvenu models. BitChord keeps one ONNX Runtime
 * (onnxruntime-android 1.31.0 with the bundled source-built QNN runtime from
 * npu-addon/) and resolves the QNN execution provider from inside it, so there
 * is no second runtime to keep in sync. This file is the pure and testable
 * part: vendor detection and option maps. The SessionOptions plumbing lives in
 * the JVM trackers (BeatTracker and VocalTracker) and the Android wiring in
 * TrackAnalyzer.
 *
 * Device scope: Snapdragon Hexagon HTP (V68 and later) via the QNN execution
 * provider. When the device has no Qualcomm NPU, or the provider fails to
 * open, inference stays on the CPU execution provider. Unlike Orchard, CPU
 * fallback is kept (session.disable_cpu_ep_fallback is 0): the NPU is an
 * optimization, never a requirement.
 *
 * Provider abstraction: the QNN switch is modelled as the [InferenceProvider]
 * enum (CPU_DEFAULT, CPU_XNNPACK, QNN_GPU_FP16, QNN_HTP_QDQ) and resolved per
 * device + config through [resolveProvider]; [defaultProvider] answers
 * CPU_DEFAULT and the master [QNN_ENABLED] guard stays false, so the shipped
 * build keeps running the plain CPU execution provider.
 *
 * [InferenceProvider.CPU_XNNPACK] is openable (see NpuSession.openXnnpack) but
 * stays opt-in only. It is never returned by [resolveProvider] and never
 * selected as a default: the on-device self-test benchmark
 * (NpuSelfTest.benchmarkCpuVsXnnpack) must first prove lower latency and logit
 * parity on the target Snapdragon SM8635 before anything flips.
 */
object NpuSupport {

    /**
     * Hard switch for the entire QNN path.
     *
     * Owner: NpuSupport. Purpose: single guard over every QNN entry point.
     * Guard: when false no QNN library is dlopened and no QNN session is
     * attempted (shouldAttemptQnn, NpuSession.open/openQnn, TrackAnalyzer
     * preflight, NpuNative, NpuSelfTest.schedule); inference runs on the
     * plain Maven ORT CPU provider.
     *
     * Dormant because QNN has never executed an inference on the target
     * device: QAIRT fails with QNN_DEVICE_ERROR_INVALID_CONFIG before
     * partitioning, and the reduced-ORT build dropped optimizer-introduced
     * kernels (MatMulIntegerToFloat, DynamicQuantizeMatMul), so both models
     * fail on both backends. Flip to true only once both are fixed; the
     * dormant path also needs the npu-addon jniLibs srcDir restored in
     * app/build.gradle.kts. The CPU-vs-XNNPACK benchmark does NOT need this
     * flag: it runs on CPU providers only.
     */
    const val QNN_ENABLED = false

    /**
     * How inference uses the NPU. Off keeps the plain CPU execution
     * provider; on attempts QNN on Snapdragon and falls back to CPU
     * automatically. The master QNN_ENABLED switch above wins over this
     * per-config flag.
     */
    data class NpuConfig(
        val enabled: Boolean = true,
        /** Orchard hard-fails without the NPU (1); BitChord stays playable anywhere (0). */
        val disableCpuFallback: Boolean = false,
        /** Hexagon performance mode (Orchard uses burst). */
        val htpPerformanceMode: String = "burst",
        /** HTP graph finalization optimization mode (Orchard uses 3). */
        val graphFinalizationOptMode: String = "3",
        /** Cache the compiled context binary so repeat runs skip the compile. */
        val contextCaching: Boolean = true,
        /** Clip the NPU attempt log to one warning per process. */
        val warnOnce: Boolean = true,
    )

    /** Which vendor silicon the device runs, for execution provider choice. */
    enum class Vendor { QUALCOMM, MEDIATEK, NONE }

    /**
     * The inference provider a model may run on. Replaces the old single
     * global QNN switch as the per-attempt choice; the master [QNN_ENABLED]
     * guard underneath keeps the shipped build on [InferenceProvider.CPU_DEFAULT].
     */
    enum class InferenceProvider {
        /** Plain CPU execution provider; the production default. */
        CPU_DEFAULT,
        /**
         * CPU execution provider with the XNNPACK delegate. Opt-in only and
         * never resolved automatically: it must first be measured against
         * CPU_DEFAULT on the target device before it can become a default.
         */
        CPU_XNNPACK,
        /** Qualcomm QNN on the Adreno GPU backend (FP16). */
        QNN_GPU_FP16,
        /** Qualcomm QNN on the Hexagon HTP backend (QDQ). */
        QNN_HTP_QDQ,
    }

    /** Production default inference provider: plain CPU, no NPU. */
    fun defaultProvider(): InferenceProvider = InferenceProvider.CPU_DEFAULT

    /**
     * Classifies the device from its build fields. Qualcomm when the SoC is
     * Qualcomm, the hardware reads qcom, or a Samsung model reads SM (the
     * Galaxy Snapdragon line); MediaTek for its mt hardware prefix. A Pixel
     * (Tensor) classifies as NONE: Tensor is not a Qualcomm hexagon and no
     * QNN backend ships for it on this path.
     */
    fun detectVendor(socManufacturer: String?, hardware: String?, model: String?, manufacturer: String?): Vendor {
        val soc = (socManufacturer ?: "").trim()
        val hw = (hardware ?: "").trim()
        val md = (model ?: "").trim()
        val mfr = (manufacturer ?: "").trim()
        if (soc.contains("Qualcomm", ignoreCase = true)) return Vendor.QUALCOMM
        if (hw.startsWith("qcom", ignoreCase = true)) return Vendor.QUALCOMM
        if (md.startsWith("SM") && mfr.contains("samsung", ignoreCase = true)) return Vendor.QUALCOMM
        if (soc.contains("MediaTek", ignoreCase = true)) return Vendor.MEDIATEK
        if (hw.startsWith("mt", ignoreCase = true)) return Vendor.MEDIATEK
        return Vendor.NONE
    }

    /**
     * True when the SoC model string identifies a Qualcomm SM8635, BitChord's
     * sole NPU target (Poco F6). While set, the QNN provider options pin
     * `htp_arch = "73"` so the QAIRT driver selects the V73 Hexagon kernel
     * generation instead of resolving across the bundled V73/V75 pair.
     */
    fun isQualcommSm8635(socModel: String?): Boolean {
        val model = (socModel ?: "").trim().uppercase()
        if (model.isEmpty()) return false
        return model.startsWith("SM8635", ignoreCase = true)
    }

    /** True when QNN is allowed on and the config wants it on Qualcomm. */
    fun shouldAttemptQnn(config: NpuConfig, vendor: Vendor): Boolean =
        QNN_ENABLED && config.enabled && vendor == Vendor.QUALCOMM

    /**
     * True for the two CPU execution-provider choices. Used by
     * [NpuSession.open] so a CPU provider is never routed into the QNN open
     * (the XNNPACK delegate is a CPU provider, not an NPU one).
     */
    fun isCpuProvider(provider: InferenceProvider): Boolean = when (provider) {
        InferenceProvider.CPU_DEFAULT,
        InferenceProvider.CPU_XNNPACK -> true
        InferenceProvider.QNN_GPU_FP16,
        InferenceProvider.QNN_HTP_QDQ -> false
    }

    /**
     * Whether [provider] may run inference right now. CPU_DEFAULT never attempts
     * (the plain CPU path runs it); CPU_XNNPACK is the opt-in delegate; the QNN
     * providers need the master switch, the per-config flag and a QNN vendor.
     */
    fun shouldAttempt(provider: InferenceProvider, config: NpuConfig, vendor: Vendor): Boolean = when (provider) {
        InferenceProvider.CPU_DEFAULT -> false
        InferenceProvider.CPU_XNNPACK -> config.enabled
        InferenceProvider.QNN_GPU_FP16,
        InferenceProvider.QNN_HTP_QDQ -> QNN_ENABLED && config.enabled && vendor == Vendor.QUALCOMM
    }

    /**
     * Resolves which [InferenceProvider] runs inference for a device + config.
     * Returns CPU_DEFAULT while the master switch or the config flag are off, so
     * the shipped build keeps running the plain CPU execution provider.
     * CPU_XNNPACK is deliberately unreachable here: it is an explicit
     * measurement opt-in (NpuSession.open's provider argument), never a
     * resolution result.
     */
    fun resolveProvider(config: NpuConfig, vendor: Vendor): InferenceProvider = when {
        !QNN_ENABLED -> InferenceProvider.CPU_DEFAULT
        !config.enabled -> InferenceProvider.CPU_DEFAULT
        vendor == Vendor.QUALCOMM -> InferenceProvider.QNN_HTP_QDQ
        else -> InferenceProvider.CPU_DEFAULT
    }

    /**
     * QNN execution provider options map, mirroring Orchard qnn_runner.cpp:
     * the HTP backend library, burst performance, and the deepest graph
     * finalization optimization. backendPath is the bundled HTP backend
     * (see bundledBackendPath); when null the provider resolves its own
     * backend. backendType selects the QNN backend library family -- "gpu"
     * for QnnGpu, "htp" for QnnHtp -- and is mutually exclusive with
     * backendPath, so exactly one of them is set by the caller.
     *
     * A detected Qualcomm SM8635 (the sole BitChord NPU target, Poco F6)
     * pins `htp_arch = "73"` so the QAIRT driver selects the V73 Hexagon
     * kernel generation instead of resolving across the V73/V75 pair; with it
     * unset the QNN open still failed with
     * QNN_DEVICE_ERROR_INVALID_CONFIG (14001) before partitioning.
     *
     * ADSP_LIBRARY_PATH is deliberately not overridden here: while ORT
     * manages the QNN backend (backend_path / backend_type above) the provider
     * resolves the HTP libraries itself, and overriding the path from the app
     * side would fight it. NpuNative still sets the bundled library search
     * path for the dlopen preflight, which is the supported hook.
     */
    fun qnnProviderOptions(
        backendPath: String?,
        socModel: String? = null,
        backendType: String? = null,
    ): Map<String, String> {
        val options = mutableMapOf<String, String>()
        if (!backendType.isNullOrBlank()) {
            val selected = backendType.trim().lowercase()
            // Accept both the QNN naming ("QnnGpu" / "QnnHtp") and the ORT
            // backend_type values ("gpu" / "htp").
            val backend = if (selected.startsWith("qnn")) selected.removePrefix("qnn").trim() else selected
            if (backend.isNotEmpty()) options["backend_type"] = backend
        }
        if (backendPath != null && backendPath.isNotBlank()) options["backend_path"] = backendPath
        options["htp_performance_mode"] = "burst"
        options["htp_graph_finalization_optimization_mode"] = "3"
        if (isQualcommSm8635(socModel)) options["htp_arch"] = "73"
        return options
    }

    /**
     * Session config entries for the QNN session, mirroring Orchard:
     * spinning off, context caching and embedding on, and CPU fallback
     * controlled by the config (0 here, unlike Orchard which passes 1).
     * contextPath is the compiled-context file inside the cache directory.
     */
    fun qnnSessionConfig(config: NpuConfig, contextPath: String?): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        entries["session.disable_cpu_ep_fallback"] = if (config.disableCpuFallback) "1" else "0"
        entries["session.intra_op.allow_spinning"] = "0"
        if (config.contextCaching) {
            entries["ep.context_enable"] = "1"
            entries["ep.context_embed_mode"] = "1"
            if (contextPath != null && contextPath.isNotBlank()) {
                entries["ep.context_file_path"] = contextPath
            }
        }
        return entries
    }

    /** QAIRT HTP backend library file name inside the bundled native lib dir. */
    const val QNN_BACKEND_FILE_NAME = "libQnnHtp.so"

    /**
     * Path of the bundled QNN backend for the provider options map. Orchard
     * loads its add-on libQnnHtp.so by this same libDir plus name scheme
     * (qnn_runner open, backend_path). A /vendor probe cannot work: Android
     * linker namespaces keep a process from referencing vendor libraries on
     * every supported API level, so the bundled backend is the only path.
     */
    fun bundledBackendPath(nativeLibraryDir: String): String =
        nativeLibraryDir + "/" + QNN_BACKEND_FILE_NAME
}
