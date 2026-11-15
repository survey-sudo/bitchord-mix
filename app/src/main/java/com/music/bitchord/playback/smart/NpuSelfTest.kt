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
 * daemon thread, and logs the outcome under BitChordNPU with fixed line
 * shapes. The HTP ARCH line comes from the vendor QAIRT library.
 *
 * At most one run per process; off UI and audio threads; never throws; a
 * failure cannot affect playback; aborted when backgrounded.
 */
object NpuSelfTest {
    private const val TAG = "BitChordNPU"
    private const val START_DELAY_MS = 3000L
    /** Fixed small thread count; this proves dispatch, not throughput. */
    private const val THREADS = 2
    /** Bound on dummy-input elements (4M floats = 16 MB worst case). */
    private const val MAX_INPUT_ELEMENTS = 4_000_000L
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
        if (!NpuSupport.QNN_ENABLED) return
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
        if (!NpuSupport.QNN_ENABLED) {
            Log.i(TAG, "selftest skipped (qnn disabled)")
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
        val model = runCatching { unpackModel(appContext, BeatTracker.MODEL_ASSET) }.getOrNull()
        if (model == null) {
            Log.i(TAG, "selftest qnn open FAILED: bundled beat model unavailable")
            Log.i(TAG, "selftest provider=none inference=not-attempted (beat model unavailable)")
            done()
            return
        }
        // Mirror production preflight (dlopen and ADSP path) before graph
        // init. Never throws; a failure here means graph open fails
        // honestly below.
        if (dir != null) NpuNative.initBundledQnn(dir)
        val cacheDir = runCatching {
            File(appContext.noBackupFilesDir, "npu/selftest-cache").apply { mkdirs() }
        }.getOrNull()
        var session: OrtSession? = null
        var qnnOk = false
        try {
            session = NpuSession.openQnn(model, THREADS, NpuSupport.NpuConfig(), backend, cacheDir)
            qnnOk = true
            Log.i(TAG, "selftest qnn open OK")
        } catch (t: Throwable) {
            Log.i(TAG, "selftest qnn open FAILED: " + (t.message ?: t.toString()) + " [" + t.javaClass.simpleName + "]")
        }
        var inference = "not-attempted"
        if (qnnOk && session != null) {
            inference = if (runDummyInference(session)) "ok" else "skipped"
        }
        runCatching { session?.close() }
        Log.i(TAG, "selftest provider=" + (if (qnnOk) "QNN" else "QNN-FAILED") + " inference=" + inference)
        done()
    }
    /**
     * Runs one tiny forward pass on a zeros input shaped from the
     * session input metadata (forces the HTP graph to execute, not
     * merely open). Dynamic dims are substituted with a small concrete
     * size; anything unexpected yields false, reported as skipped.
     */
    private fun runDummyInference(session: OrtSession): Boolean {
        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        return try {
            val infos = session.getInputInfo()
            val first = infos.entries.firstOrNull() ?: return false
            val tensorInfo = first.value.info as? ai.onnxruntime.TensorInfo ?: return false
            val shape = tensorInfo.getShape().map { d -> if (d > 0) d else 8L }.toLongArray()
            val elements = shape.fold(1L) { acc, d -> acc * d }
            if (elements <= 0 || elements > MAX_INPUT_ELEMENTS) return false
            val inputTensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(FloatArray(elements.toInt())),
                shape,
            )
            tensor = inputTensor
            result = session.run(mapOf(first.key to inputTensor))
            true
        } catch (_: Throwable) {
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
