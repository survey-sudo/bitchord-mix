package com.music.bitchord.playback.smart

/**
 * Device classification and dormant provider configuration. The production provider
 * is the official ORT 1.31.0 CPU EP. Both release gates are off; no vendor runtime,
 * runner or NPU model is part of djmix52.
 *
 * Option keys describe the public ORT provider/config interfaces, not an engine:
 * https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html
 * https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
 */
object NpuSupport {
    /** Legacy release gate. Re-enabling NPU requires a new licensed runtime/model delivery. */
    const val QNN_ENABLED = false

    data class NpuConfig(
        val enabled: Boolean = true,
        val disableCpuFallback: Boolean = false,
        val htpPerformanceMode: String = "burst",
        val graphFinalizationOptMode: String = "3",
        val contextCaching: Boolean = true,
        val warnOnce: Boolean = true,
    )

    enum class Vendor { QUALCOMM, MEDIATEK, NONE }

    enum class InferenceProvider { CPU_DEFAULT, CPU_XNNPACK, QNN_GPU_FP16, QNN_HTP_QDQ }
    fun defaultProvider(): InferenceProvider = InferenceProvider.CPU_DEFAULT

    fun detectVendor(socManufacturer: String?, hardware: String?, model: String?, manufacturer: String?): Vendor {
        val soc = socManufacturer.orEmpty().trim()
        val hw = hardware.orEmpty().trim()
        val md = model.orEmpty().trim()
        val maker = manufacturer.orEmpty().trim()
        return when {
            soc.contains("Qualcomm", ignoreCase = true) || hw.startsWith("qcom", ignoreCase = true) ||
                (md.startsWith("SM") && maker.contains("samsung", ignoreCase = true)) -> Vendor.QUALCOMM
            soc.contains("MediaTek", ignoreCase = true) || hw.startsWith("mt", ignoreCase = true) -> Vendor.MEDIATEK
            else -> Vendor.NONE
        }
    }

    fun isQualcommSm8635(socModel: String?): Boolean =
        socModel.orEmpty().trim().startsWith("SM8635", ignoreCase = true)

    fun shouldAttemptQnn(config: NpuConfig, vendor: Vendor): Boolean =
        QNN_ENABLED && NpuRuntime.RUNTIME_AVAILABLE && config.enabled && vendor == Vendor.QUALCOMM

    fun shouldAttempt(provider: InferenceProvider, config: NpuConfig, vendor: Vendor): Boolean = when (provider) {
        InferenceProvider.CPU_DEFAULT -> false
        InferenceProvider.CPU_XNNPACK -> config.enabled
        InferenceProvider.QNN_GPU_FP16, InferenceProvider.QNN_HTP_QDQ -> shouldAttemptQnn(config, vendor)
    }

    fun resolveProvider(config: NpuConfig, vendor: Vendor): InferenceProvider =
        if (shouldAttemptQnn(config, vendor)) InferenceProvider.QNN_HTP_QDQ else InferenceProvider.CPU_DEFAULT

    /** Public QNN EP options, retained as data only. Exactly one backend selector is emitted. */
    fun qnnProviderOptions(backendPath: String?, socModel: String? = null, backendType: String? = null): Map<String, String> {
        val options = linkedMapOf(
            "htp_performance_mode" to "burst",
            "htp_graph_finalization_optimization_mode" to "3",
        )
        val path = backendPath?.takeIf { it.isNotBlank() }
        val type = backendType?.trim()?.lowercase()?.removePrefix("qnn")?.takeIf { it.isNotBlank() }
        when {
            path != null -> options["backend_path"] = path
            type != null -> options["backend_type"] = type
        }
        if (isQualcommSm8635(socModel)) options["htp_arch"] = "73"
        return options
    }

    /** Public session config entries. These are never applied by the CPU-only factory. */
    fun qnnSessionConfig(config: NpuConfig, contextPath: String?): Map<String, String> {
        val entries = linkedMapOf(
            "session.disable_cpu_ep_fallback" to if (config.disableCpuFallback) "1" else "0",
            "session.intra_op.allow_spinning" to "0",
        )
        if (config.contextCaching) {
            entries["ep.context_enable"] = "1"
            entries["ep.context_embed_mode"] = "1"
            contextPath?.takeIf { it.isNotBlank() }?.let { entries["ep.context_file_path"] = it }
        }
        return entries
    }

    const val QNN_BACKEND_FILE_NAME = "libQnnHtp.so"
    /** Name assembly only; does not resolve, load or preflight a backend. */
    fun bundledBackendPath(nativeLibraryDir: String): String = "$nativeLibraryDir/$QNN_BACKEND_FILE_NAME"
}
