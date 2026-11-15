package com.music.bitchord.playback.smart

/**
 * NPU execution provider configuration (vc69 NPU): Snapdragon QNN for the
 * Beat This and umxhq models, with CPU fallback. Orchard ships a separate
 * add-on (liborchard_qnn.so over its own ONNX Runtime, QNN execution provider
 * in burst performance mode with context caching, CPU fallback disabled)
 * for its Nirovi and Syvenu models. BitChord keeps one ONNX Runtime
 * (onnxruntime-android 1.31.0 with the bundled source-built QNN runtime
 * from npu-addon/) and resolves the QNN execution provider from inside it,
 * so there is no second runtime to keep in sync. This file is the
 * pure and testable part: vendor detection and option maps. The
 * SessionOptions plumbing lives in the JVM trackers (BeatTracker and
 * VocalTracker) and the Android wiring in TrackAnalyzer.
 *
 * Device scope: Snapdragon Hexagon HTP (V68 and later) via the QNN execution
 * provider. When the device has no Qualcomm NPU, or the provider fails to
 * open, inference stays on the CPU execution provider. Unlike Orchard,
 * CPU fallback is kept (session.disable_cpu_ep_fallback is 0): the NPU is
 * an optimization, never a requirement.
 */
object NpuSupport {

    /**
     * Hard switch for the entire QNN path (vc70 ORT restore).
     *
     * QNN has never executed an inference on the target device: QAIRT
     * fails with QNN_DEVICE_ERROR_INVALID_CONFIG before partitioning,
     * and the reduced-ORT build dropped optimizer-introduced kernels
     * (MatMulIntegerToFloat, DynamicQuantizeMatMul) so both models fail
     * on both backends since vc69. Until both are fixed, QNN stays OFF
     * and inference runs on the full Maven ORT CPU provider.
     *
     * Flip to true only to re-enable the dormant path (which still needs
     * the npu-addon jniLibs srcDir restored in app/build.gradle.kts).
     * Every QNN entry point checks this (shouldAttemptQnn below,
     * NpuSession.open/openQnn, TrackAnalyzer preflight, NpuNative, and
     * NpuSelfTest.schedule); when false no QNN library is dlopened and
     * no QNN session is attempted.
     */
    const val QNN_ENABLED = false

    /**
     * How inference uses the NPU. Off keeps the CPU execution provider
     * exactly as before vc69; on attempts QNN on Snapdragon and falls back
     * to CPU automatically. The master QNN_ENABLED switch above wins over
     * this per-config flag.
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

    /** True when QNN is allowed on and the config wants it on Qualcomm. */
    fun shouldAttemptQnn(config: NpuConfig, vendor: Vendor): Boolean =
        QNN_ENABLED && config.enabled && vendor == Vendor.QUALCOMM

    /**
     * QNN execution provider options map, mirroring Orchard qnn_runner.cpp:
     * the HTP backend library, burst performance, and the deepest graph
     * finalization optimization. backendPath is the bundled HTP backend
     * (see bundledBackendPath); when null the provider resolves its own
     * backend.
     */
    fun qnnProviderOptions(backendPath: String?): Map<String, String> {
        val options = mutableMapOf<String, String>()
        if (backendPath != null && backendPath.isNotBlank()) options["backend_path"] = backendPath
        options["htp_performance_mode"] = "burst"
        options["htp_graph_finalization_optimization_mode"] = "3"
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
     * (qnn_runner open, backend_path). The /vendor probe shipped with vc69
     * is gone: Android linker namespaces keep a process from referencing
     * vendor libraries on every supported API level, so the bundled
     * backend is the only path that can work.
     */
    fun bundledBackendPath(nativeLibraryDir: String): String =
        nativeLibraryDir + "/" + QNN_BACKEND_FILE_NAME
}
