package com.music.bitchord.playback.smart

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded one-shot NPU self-test (vc70 NPU dispatch proof).
 *
 * Analysis never runs without playback, so without this the NPU path
 * cannot be verified on a real Snapdragon: session open and the HTP graph
 * initialisation only happen then. This probe opens a minimal QNN session
 * against the bundled beat model shortly after app start, on its own
 * daemon thread with CPU fallback disabled and EPContext caching off. It
 * logs the outcome under BitChordNPU; verbose ORT node assignment and the
 * vendor HTP ARCH log are required too. Session creation alone proves nothing.
 *
 * At most one run per process; off UI and audio threads; never throws; a
 * failure cannot affect playback; aborted when backgrounded.
 */
object NpuSelfTest {
    private const val TAG = "BitChordNPU"
    private const val START_DELAY_MS = 3000L
    /** Fixed small thread count; this proves dispatch, not throughput. */
    private const val THREADS = 2
    private val started = AtomicBoolean(false)

    /**
     * Schedules the probe once. context is only read after attach
     * (post-delay on the probe thread), so an unattached Service
     * reference is safe. libDir resolves (and preflights) the bundled
     * native lib dir.
     */
    fun schedule(
        context: Context,
        vendor: () -> NpuSupport.Vendor,
        libDir: () -> String?,
    ) {
        // Dormant by default: no thread, no model unpack, no dlopen.
        if (!NpuSupport.QNN_ENABLED || !NpuSupport.BEAT_QDQ_ENABLED) return
        if (!started.compareAndSet(false, true)) return
        Thread({
            try {
                Thread.sleep(START_DELAY_MS)
                run(context, vendor, libDir)
            } catch (_: Throwable) {
                // Never propagate out of the probe thread.
            }
        }, "bitchord-npu-selftest").apply { isDaemon = true }.start()
    }
    private fun run(
        context: Context,
        vendor: () -> NpuSupport.Vendor,
        libDir: () -> String?,
    ) {
        val startMs = System.currentTimeMillis()
        if (!NpuSupport.QNN_ENABLED || !NpuSupport.BEAT_QDQ_ENABLED) {
            Log.i(TAG, "selftest skipped (qnn or QDQ beat candidate disabled)")
            Log.i(TAG, "selftest done in 0ms")
            return
        }
        fun done() {
            Log.i(TAG, "selftest done in " + (System.currentTimeMillis() - startMs) + "ms")
        }
        if (shouldSkipBackgrounded(context)) {
            Log.i(TAG, "selftest skip (backgrounded)")
            done()
            return
        }
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: run {
            Log.i(TAG, "selftest skip (no application context)")
            done()
            return
        }
        val v = runCatching { vendor() }.getOrDefault(NpuSupport.Vendor.NONE)
        val soc = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        }.getOrDefault("unknown")
        val dir = runCatching { libDir() }.getOrNull()
        val backend = dir?.let { NpuSupport.bundledBackendPath(it) }?.takeIf { File(it).exists() }
        Log.i(TAG, "selftest start vendor=" + v + " soc=" + soc + " backend=" + (backend ?: "missing"))
        if (v != NpuSupport.Vendor.QUALCOMM) {
            Log.i(TAG, "selftest provider=none inference=not-attempted (vendor=" + v + ")")
            done()
            return
        }
        val model = runCatching { unpackModel(appContext, BeatTracker.NPU_MODEL_ASSET) }.getOrNull()
        if (model == null) {
            Log.i(TAG, "selftest qnn open FAILED: bundled beat model unavailable")
            Log.i(TAG, "selftest provider=none inference=not-attempted (beat model unavailable)")
            done()
            return
        }
        // The linker-visible libcdsprpc gate must pass BEFORE opening QAIRT.
        val preflight = dir != null && NpuNative.initBundledQnn(dir)
        if (!NpuSupport.htpPreflightReady(backend != null, preflight)) {
            Log.i(TAG, "selftest provider=none inference=not-attempted (HTP/libcdsprpc preflight failed)")
            done()
            return
        }
        val cacheDir = runCatching {
            File(appContext.noBackupFilesDir, "npu/selftest-cache").apply { mkdirs() }
        }.getOrNull()
        var session: OrtSession? = null
        var qnnOk = false
        try {
            session = NpuSession.openQnnProbe(
                model, THREADS, NpuSupport.NpuConfig(deviceFingerprint = Build.FINGERPRINT),
                backend, cacheDir, preflightPassed = preflight,
            )
            qnnOk = true
            Log.i(TAG, "selftest qnn open OK fallback=disabled htp_arch=" + NpuSupport.HTP_ARCH + " (not dispatch proof)")
        } catch (t: Throwable) {
            Log.i(TAG, "selftest qnn open FAILED: " + (t.message ?: t.toString()) + " [" + t.javaClass.simpleName + "]")
        }
        var inference = "not-attempted"
        if (qnnOk && session != null) {
            inference = if (runDummyInference(session)) "ok" else "failed"
        }
        runCatching { session?.close() }
        Log.i(TAG, "selftest provider=" + (if (qnnOk) "QNN-PROBE" else "QNN-FAILED") + " inference=" + inference + " fallback=disabled evidence=requires-HTP-node-assignment")
        done()
    }
    /**
     * Runs the exact fixed [1,1500,128] beat contract. Fetch only named
     * beat/downbeat heads and require finite [1,1500] outputs, so the
     * probe proves a forward pass rather than merely creating a session.
     */
    private fun runDummyInference(session: OrtSession): Boolean {
        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        return try {
            val inputName = "input_spectrogram"
            val outputNames = setOf("beat", "downbeat")
            require(inputName in session.inputNames && session.outputNames.containsAll(outputNames)) { "Wrong beat-model I/O" }
            val shape = longArrayOf(1, BeatTracker.CHUNK_FRAMES.toLong(), 128)
            val inputTensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(FloatArray(BeatTracker.CHUNK_FRAMES * 128)),
                shape,
            )
            tensor = inputTensor
            result = session.run(mapOf(inputName to inputTensor), outputNames)
            for (name in outputNames) {
                // Supplier overload is API-24 compatible (the no-arg Java-10 method is not).
                val rows = result.get(name).orElseThrow { IllegalStateException("Missing output $name") }.value as? Array<*>
                    ?: error("Non-array output $name")
                val row = rows.singleOrNull() as? FloatArray ?: error("Wrong batch for $name")
                require(row.size == BeatTracker.CHUNK_FRAMES && row.all { it.isFinite() }) { "Malformed output $name" }
            }
            true
        } catch (failure: Throwable) {
            Log.i(TAG, "selftest inference FAILED: " + (failure.message ?: failure.toString()))
            false
        } finally {
            runCatching { result?.close() }
            runCatching { tensor?.close() }
        }
    }

    /** Copies a bundled asset to the self-test cache via tmp + rename. */
    private fun unpackModel(context: Context, name: String): String {
        val parent = File(context.noBackupFilesDir, "npu/selftest").apply { mkdirs() }
        val target = File(parent, name)
        if (target.exists() && target.length() > 0) return target.absolutePath
        val tmp = File(parent, name + ".tmp")
        context.assets.open(name).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(target)) {
            // renameTo can fail across filesystems; fall back to a copy.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target.absolutePath
    }
    /**
     * True when the app is fully backgrounded: the probe does not spend
     * DSP or filesystem work on a one-shot check while a backgrounded
     * media player is not being interacted with.
     */
    private fun shouldSkipBackgrounded(context: Context): Boolean {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching true
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
        }.getOrDefault(true)
    }
}
