package com.music.bitchord.playback.smart

import android.util.Log

/* libbitchord_analysis.so (src/main/cpp/jni/npu_jni.cpp): QNN DSP library-path setup. */
object NpuNative {
    private const val TAG = "BitChordNPU"
    private val initLock = Any()
    @Volatile private var readyLibraryDir: String? = null

    private val analysisLibLoaded: Boolean by lazy {
        runCatching { System.loadLibrary("bitchord_analysis"); true }.getOrDefault(false)
    }

    /**
     * Best-effort pre-flight for the bundled QAIRT stack: points the DSP
     * library search at this APK nativeLibraryDir (mirroring the Orchard
     * qnn_runner ADSP_LIBRARY_PATH setup, bundled libs first, vendor
     * directories after). First gates on linker-visible libcdsprpc, then
     * dlopens the bundled HTP backend. Never throws: NPU is an optimization, and the
     * caller falls through to the CPU execution provider regardless.
     */
    fun initBundledQnn(libraryDir: String): Boolean {
        // Dormant by default: QNN_ENABLED=false means no dlopen at all.
        // Callers (TrackAnalyzer preflight, NpuSelfTest) gate on the same
        // switch; this keeps the JNI entry point safe on its own.
        if (!NpuSupport.QNN_ENABLED || !NpuSupport.BEAT_QDQ_ENABLED) return false
        if (!analysisLibLoaded) {
            Log.w(TAG, "bundled QNN init skipped: bitchord_analysis unavailable")
            return false
        }
        synchronized(initLock) {
            if (readyLibraryDir == libraryDir) return true
            return runCatching {
                val ready = nativeInitAdspPath(libraryDir)
                if (ready) readyLibraryDir = libraryDir
                Log.i(TAG, "bundled QNN init nativeLibraryDir=" + libraryDir + " adspReady=" + ready)
                ready
            }.getOrElse { failure ->
                Log.w(TAG, "bundled QNN init failed; staying on CPU", failure)
                false
            }
        }
    }

    /**
     * Sets ADSP_LIBRARY_PATH and dlopens libQnnSystem plus libQnnHtp by
     * path, only after dlopen("libcdsprpc.so") succeeds in the app namespace.
     * True when all three open; mere /vendor file presence is not sufficient.
     */
    @JvmStatic private external fun nativeInitAdspPath(libraryDir: String): Boolean
}
