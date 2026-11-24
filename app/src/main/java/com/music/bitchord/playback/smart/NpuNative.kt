package com.music.bitchord.playback.smart

/**
 * Dormant native-preflight contract. djmix52 uses the official ORT Java session
 * factory; it does not set DSP environment variables, load vendor libraries or
 * load an addon. A future runner must be implemented against the official ORT
 * C API (OrtGetApiBase / OrtApi), with its own licensed runtime and models.
 */
object NpuNative {
    @Volatile private var qnnDiagnosticArmed = false

    fun armQnnDiagnostic(armed: Boolean) {
        qnnDiagnosticArmed = armed && NpuRuntime.runtimeEnabled()
    }

    fun isQnnDiagnosticArmed(): Boolean = qnnDiagnosticArmed && NpuRuntime.runtimeEnabled()

    @Suppress("UNUSED_PARAMETER")
    fun initBundledQnn(libraryDir: String): Boolean = false

    /** Native ABI compatibility only; the native implementation also returns false without side effects. */
    @JvmStatic external fun nativeInitAdspPath(libraryDir: String): Boolean
}
