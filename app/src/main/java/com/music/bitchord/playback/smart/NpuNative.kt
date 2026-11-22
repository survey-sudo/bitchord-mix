package com.music.bitchord.playback.smart

import android.util.Log

/* libbitchord_analysis.so (src/main/cpp/jni/npu_jni.cpp): QNN DSP library-path setup. */
object NpuNative {
    private const val TAG = "BitChordNPU"

    private val analysisLibLoaded: Boolean by lazy {
        runCatching { System.loadLibrary("bitchord_analysis"); true }.getOrDefault(false)
    }

    /**
     * Test-build QNN diagnostic arming. Defaults to false, which is the shipped
     * behaviour; the NPU self-test arms it only when explicitly enabled in a test
     * build, which switches the QNN session it opens into the diagnostic profile
     * (CPU execution provider fallback disabled + ORT profiling enabled, so a full
     * graph partition can be proven instead of accepting a silent CPU fallback).
     * Pure Kotlin state: no native call is made and no behaviour changes while
     * disarmed.
     */
    @Volatile private var qnnDiagnosticArmed = false

    /** Arms / queries the test-build QNN diagnostic profile. */
    fun armQnnDiagnostic(armed: Boolean) {
        qnnDiagnosticArmed = armed
    }

    fun isQnnDiagnosticArmed(): Boolean = qnnDiagnosticArmed

    /**
     * Best-effort pre-flight for the bundled QAIRT stack: points the DSP
     * library search at this APK nativeLibraryDir (mirroring the Orchard
     * qnn_runner ADSP_LIBRARY_PATH setup, bundled libs first, vendor
     * directories after) and dlopens the bundled HTP backend once to prove
     * the stack is present. Never throws: NPU is an optimization, and the
     * caller falls through to the CPU execution provider regardless.
     */
    fun initBundledQnn(libraryDir: String): Boolean {
        // Dormant by default: QNN_ENABLED=false means no dlopen at all.
        // Callers (TrackAnalyzer preflight, NpuSelfTest) gate on the same
        // switch; this keeps the JNI entry point safe on its own.
        if (!NpuSupport.QNN_ENABLED) return false
        if (!analysisLibLoaded) {
            Log.w(TAG, "bundled QNN init skipped: bitchord_analysis unavailable")
            return false
        }
        return runCatching {
            val ready = nativeInitAdspPath(libraryDir)
            Log.i(TAG, "bundled QNN init nativeLibraryDir=" + libraryDir + " adspReady=" + ready)
            ready
        }.getOrElse { failure ->
            Log.w(TAG, "bundled QNN init failed; staying on CPU", failure)
            false
        }
    }

    /**
     * Sets ADSP_LIBRARY_PATH and dlopens libQnnSystem plus libQnnHtp by
     * path. True when both open.
     */
    @JvmStatic external fun nativeInitAdspPath(libraryDir: String): Boolean
}
