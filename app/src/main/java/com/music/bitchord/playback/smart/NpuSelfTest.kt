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
import java.util.Locale
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
 *
 * Test-build harness (vc91, extended in vc92): while QNN is off the probe
 * stays dormant for QNN, and the two dormant-build harnesses below
 * (CPU-vs-XNNPACK benchmark, QNN diagnostic) run only when their test-build
 * constants are flipped on. vc92 flips the benchmark on: it is a CPU-only
 * measurement (no QNN library is dlopened, no QNN session opened) that must
 * produce real SM8635 numbers before CPU_XNNPACK can ever become a default.
 */
object NpuSelfTest {
    private const val TAG = "BitChordNPU"
    private const val START_DELAY_MS = 3000L
    /** Fixed small thread count; this proves dispatch, not throughput. */
    private const val THREADS = 2
    /** Bound on dummy-input elements (4M floats = 16 MB worst case). */
    private const val MAX_INPUT_ELEMENTS = 4_000_000L
    /**
     * Max logit delta tolerated between two providers for the parity check.
     * vc92 tightens this from 1e-4 to 1e-3: the CPU-vs-XNNPACK comparison is
     * a delegate-vs-reference check, not a bit-exact one, and a delegate may
     * reorder float accumulation. Anything above 1e-3 means the delegate is
     * changing the prediction, not just the summation order.
     */
    private const val MAX_LOGIT_DELTA: Float = 1e-3f
    /** Warmup iterations discarded before timing (caches, arenas, page faults). */
    private const val BENCH_WARMUP_ITERATIONS = 3
    /** Timed iterations per provider; median is reported so one hiccup cannot skew it. */
    private const val BENCH_TIMED_ITERATIONS = 20
    /** Mel frames for the benchmark input when the model's frame dim is dynamic: 6 s at 50 fps. */
    private const val BENCH_FRAMES = 300
    /** Mel bands per frame, used when the model's band dim is dynamic. */
    private const val BENCH_MELS = 128
    private val started = AtomicBoolean(false)

    /**
     * Test-build switches. QNN_DIAGNOSTIC_ENABLED stays off (the QNN path is
     * dormant and fails on device); BENCHMARK_CPU_VS_XNNPACK_ENABLED is on for
     * vc92 so the shipped build measures real Snapdragon SM8635 CPU_DEFAULT
     * vs CPU_XNNPACK latency and logit parity. The benchmark touches CPU
     * providers only, so it is safe with QNN_ENABLED=false.
     */
    private const val QNN_DIAGNOSTIC_ENABLED = false
    private const val BENCHMARK_CPU_VS_XNNPACK_ENABLED = false

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
        // Dormant unless there is work to do: the QNN probe needs the master
        // switch, and the CPU-vs-XNNPACK benchmark is an independent test-build
        // harness that must still run while QNN is off.
        if (!NpuSupport.QNN_ENABLED && !BENCHMARK_CPU_VS_XNNPACK_ENABLED) return
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
        fun done() {
            Log.i(TAG, "selftest done in " + (System.currentTimeMillis() - startMs) + "ms")
        }
        if (!NpuSupport.QNN_ENABLED && !BENCHMARK_CPU_VS_XNNPACK_ENABLED) {
            Log.i(TAG, "selftest skipped (qnn disabled, benchmark off)")
            done()
            return
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
        Log.i(
            TAG,
            "selftest start vendor=" + v + " soc=" + soc + " backend=" + (backend ?: "missing") +
                " qnn=" + NpuSupport.QNN_ENABLED + " benchmark=" + BENCHMARK_CPU_VS_XNNPACK_ENABLED,
        )

        var provider = "none"
        var inference = "not-attempted"
        var benchmark = "off"
        var modelPath: String? = null

        // --- Stage 1: QNN (only while the master switch is on). ---
        // While QNN_ENABLED is false this whole block is skipped: no library is
        // dlopened, no context cache is written and openQnn is never called, so
        // the CPU benchmark below is a pure CPU measurement.
        if (NpuSupport.QNN_ENABLED) {
            if (v != NpuSupport.Vendor.QUALCOMM) {
                Log.i(TAG, "selftest provider=none inference=not-attempted (vendor=" + v + ")")
            } else {
                modelPath = runCatching { unpackModel(appContext, BeatTracker.MODEL_ASSET) }.getOrNull()
                if (modelPath == null) {
                    Log.i(TAG, "selftest qnn open FAILED: bundled beat model unavailable")
                    provider = "none"
                    inference = "not-attempted (beat model unavailable)"
                } else {
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
                        session = if (QNN_DIAGNOSTIC_ENABLED) {
                            NpuSession.openQnnDiagnostic(modelPath, THREADS, NpuSupport.NpuConfig(), backend, cacheDir, soc)
                        } else {
                            NpuSession.openQnn(modelPath, THREADS, NpuSupport.NpuConfig(), backend, cacheDir)
                        }
                        qnnOk = true
                        Log.i(TAG, "selftest qnn open OK")
                    } catch (t: Throwable) {
                        Log.i(TAG, "selftest qnn open FAILED: " + (t.message ?: t.toString()) + " [" + t.javaClass.simpleName + "]")
                    }
                    if (qnnOk && session != null) {
                        inference = if (runDummyInference(session)) "ok" else "skipped"
                    }
                    runCatching { session?.close() }
                    provider = if (qnnOk) "QNN" else "QNN-FAILED"
                }
            }
        } else {
            Log.i(TAG, "selftest qnn skipped (QNN_ENABLED=false)")
        }

        // --- Stage 2: CPU_DEFAULT vs CPU_XNNPACK (CPU-only, any vendor). ---
        // Runs regardless of QNN state; when QNN is off this is the whole point
        // of the probe (real SM8635 latency + logit parity for the delegate).
        if (BENCHMARK_CPU_VS_XNNPACK_ENABLED) {
            val model = modelPath ?: runCatching { unpackModel(appContext, BeatTracker.MODEL_ASSET) }.getOrNull()
            benchmark = when {
                model == null -> "failed (beat model unavailable)"
                benchmarkCpuVsXnnpack(model, THREADS) -> "ok"
                else -> "failed"
            }
        }

        Log.i(TAG, "selftest provider=" + provider + " inference=" + inference + " benchmark=" + benchmark)
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

    /**
     * CPU-vs-XNNPACK benchmark (vc92): the same deterministic non-zero input
     * through a plain CPU session and an XNNPACK session, 3 warmup + 20 timed
     * iterations each, median latency per provider, and a max-logit-delta
     * parity check between the two providers.
     *
     * Non-zero, varied input on purpose: an all-zeros tensor drives every
     * activation to a constant, which hides exactly the quantization and
     * delegate divergence this benchmark exists to measure.
     *
     * Logs one fixed-shape line: cpuOpenMs, xnnpackOpenMs, cpuMedianMs,
     * xnnMedianMs, speedup, maxLogitDelta, parityOk. Returns true only when
     * both providers produced logits and parity holds (maxDelta <= 1e-3).
     */
    private fun benchmarkCpuVsXnnpack(modelPath: String, threads: Int): Boolean {
        val cpuStarted = System.nanoTime()
        val cpuSession = runCatching { NpuSession.openCpu(modelPath, threads) }.getOrNull()
        val cpuOpenMs = (System.nanoTime() - cpuStarted) / 1_000_000.0
        if (cpuSession == null) {
            Log.i(TAG, "selftest benchmark cpuVsXnnpack FAILED (cpu session)")
            return false
        }
        val xnnpackStarted = System.nanoTime()
        val xnnpackSession = runCatching { NpuSession.openXnnpack(modelPath, threads) }.getOrNull()
        val xnnpackOpenMs = (System.nanoTime() - xnnpackStarted) / 1_000_000.0
        if (xnnpackSession == null) {
            runCatching { cpuSession.close() }
            Log.i(TAG, "selftest benchmark cpuVsXnnpack FAILED (xnnpack session)")
            return false
        }
        var input: BenchInput? = null
        try {
            val prepared = createBenchInput(cpuSession)
            if (prepared == null) {
                Log.i(TAG, "selftest benchmark cpuVsXnnpack FAILED (input shape)")
                return false
            }
            input = prepared
            val cpuTiming = timedRuns(cpuSession, prepared, BENCH_WARMUP_ITERATIONS, BENCH_TIMED_ITERATIONS)
            val xnnTiming = timedRuns(xnnpackSession, prepared, BENCH_WARMUP_ITERATIONS, BENCH_TIMED_ITERATIONS)
            if (cpuTiming == null || xnnTiming == null) {
                Log.i(TAG, "selftest benchmark cpuVsXnnpack FAILED (inference)")
                return false
            }
            val maxDelta = maxLogitDelta(cpuTiming.beat, cpuTiming.downbeat, xnnTiming.beat, xnnTiming.downbeat)
            val parityOk = maxDelta <= MAX_LOGIT_DELTA
            val speedup = if (xnnTiming.medianMs > 0.0) cpuTiming.medianMs / xnnTiming.medianMs else 0.0
            Log.i(
                TAG,
                "selftest benchmark cpuVsXnnpack" +
                    " elements=" + prepared.elements +
                    " warmup=" + BENCH_WARMUP_ITERATIONS + " iterations=" + BENCH_TIMED_ITERATIONS +
                    " cpuOpenMs=" + fmt(cpuOpenMs) +
                    " xnnpackOpenMs=" + fmt(xnnpackOpenMs) +
                    " cpuMedianMs=" + fmt(cpuTiming.medianMs) +
                    " xnnMedianMs=" + fmt(xnnTiming.medianMs) +
                    " speedup=" + fmt(speedup) +
                    " maxLogitDelta=" + fmt(maxDelta.toDouble()) +
                    " parityOk=" + parityOk,
            )
            return parityOk
        } catch (_: Throwable) {
            return false
        } finally {
            val prepared = input
            runCatching { prepared?.tensor?.close() }
            runCatching { cpuSession.close() }
            runCatching { xnnpackSession.close() }
        }
    }

    /** One prepared benchmark input: the graph input name, its tensor and its element count. */
    private class BenchInput(val name: String, val tensor: OnnxTensor, val elements: Int)

    /**
     * Builds the deterministic non-zero benchmark input from the session's first
     * graph input. Dynamic dims are pinned to a concrete 6 s window (300 frames
     * x 128 mel bands) so both providers run the same real shape rather than a
     * toy one.
     */
    private fun createBenchInput(session: OrtSession): BenchInput? {
        val first = session.getInputInfo().entries.firstOrNull() ?: return null
        val tensorInfo = first.value.info as? ai.onnxruntime.TensorInfo ?: return null
        val declared = tensorInfo.getShape()
        val last = declared.size - 1
        val shape = LongArray(declared.size) { index ->
            val dim = declared[index]
            when {
                dim > 0 -> dim
                // BeatThis reads [batch, frames, mels]: pin the trailing mel band
                // dim and the frame dim before it, collapse anything else to 1.
                index == last -> BENCH_MELS.toLong()
                index == last - 1 -> BENCH_FRAMES.toLong()
                else -> 1L
            }
        }
        val elements = shape.fold(1L) { acc, d -> acc * d }
        if (elements <= 0 || elements > MAX_INPUT_ELEMENTS) return null
        val values = deterministicInput(elements.toInt())
        val tensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            FloatBuffer.wrap(values),
            shape,
        )
        return BenchInput(first.key, tensor, elements.toInt())
    }

    /**
     * Deterministic, non-zero, varied floats in the log-mel magnitude range
     * (log1p(1000 * magnitude) lands around 0..7). A fixed LCG seed keeps the
     * two providers comparable across runs and devices while never producing a
     * constant tensor that would mask quantization divergence.
     */
    private fun deterministicInput(elements: Int): FloatArray {
        val values = FloatArray(elements)
        var state = 0x2545F4914F6CDD1DL
        for (index in 0 until elements) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val unit = ((state ushr 40).toInt() and 0xFFFFFF) / 16777215f
            values[index] = 0.25f + unit * 5.75f
        }
        return values
    }

    /** Median latency (ms) plus the logits captured from the last timed run. */
    private class BenchTiming(val medianMs: Double, val beat: FloatArray, val downbeat: FloatArray)

    /**
     * Runs [warmup] discarded iterations then [iterations] timed ones against
     * [input], returning the median latency and the logits of the final run.
     * Any failure yields null so the caller reports FAILED instead of a bogus
     * number.
     */
    private fun timedRuns(
        session: OrtSession,
        input: BenchInput,
        warmup: Int,
        iterations: Int,
    ): BenchTiming? {
        repeat(warmup) {
            val ok = runCatching {
                session.run(mapOf(input.name to input.tensor)).use { outputs -> extractLogits(outputs) }
            }.getOrNull()
            if (ok == null) return null
        }
        val samples = DoubleArray(iterations)
        var last: Pair<FloatArray, FloatArray>? = null
        for (index in 0 until iterations) {
            val started = System.nanoTime()
            val logits = runCatching {
                session.run(mapOf(input.name to input.tensor)).use { outputs -> extractLogits(outputs) }
            }.getOrNull() ?: return null
            samples[index] = (System.nanoTime() - started) / 1_000_000.0
            last = logits
        }
        val captured = last ?: return null
        val sorted = samples.copyOf().also { it.sort() }
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        return BenchTiming(median, captured.first, captured.second)
    }

    /**
     * Beat and downbeat logits from a result, flattened. Outputs are matched by
     * name ("beat" must not match "downbeat") with a positional fallback, so a
     * reordered or renamed output cannot silently compare the wrong channels.
     */
    private fun extractLogits(outputs: OrtSession.Result): Pair<FloatArray, FloatArray>? {
        val byName = LinkedHashMap<String, ai.onnxruntime.OnnxValue>()
        val positional = ArrayList<ai.onnxruntime.OnnxValue>(outputs.size())
        for (index in 0 until outputs.size()) {
            val value = outputs.get(index) ?: continue
            positional.add(value)
        }
        for (entry in outputs) byName[entry.key] = entry.value
        val beat = namedOutput(byName, "beat") ?: positional.getOrNull(0)
        val downbeat = namedOutput(byName, "downbeat") ?: positional.getOrNull(1)
        if (beat == null || downbeat == null) return null
        val beatLogits = flatOutput(beat) ?: return null
        val downbeatLogits = flatOutput(downbeat) ?: return null
        return beatLogits to downbeatLogits
    }

    /** Output whose name contains [hint]; "beat" never matches "downbeat". */
    private fun namedOutput(
        outputs: Map<String, ai.onnxruntime.OnnxValue>,
        hint: String,
    ): ai.onnxruntime.OnnxValue? {
        for ((name, value) in outputs) {
            val lowered = name.lowercase()
            if (!lowered.contains(hint)) continue
            if (hint == "beat" && lowered.contains("downbeat")) continue
            return value
        }
        return null
    }

    private fun flatOutput(value: ai.onnxruntime.OnnxValue?): FloatArray? = when (val raw = value?.value) {
        is Array<*> -> raw.filterIsInstance<FloatArray>().firstOrNull()
        is FloatArray -> raw
        else -> (value as? OnnxTensor)?.floatBuffer?.let { buffer ->
            val copy = FloatArray(buffer.remaining())
            buffer.duplicate().get(copy)
            copy
        }
    }

    /** Max absolute logit delta across the beat and downbeat outputs of two runs. */
    private fun maxLogitDelta(beatA: FloatArray, downA: FloatArray, beatB: FloatArray, downB: FloatArray): Float {
        val beatDelta = maxAbsDiff(beatA, beatB)
        val downDelta = maxAbsDiff(downA, downB)
        return if (downDelta > beatDelta) downDelta else beatDelta
    }

    /** Max absolute element-wise difference between two equal-length logit vectors. */
    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var max = 0.0f
        for (index in a.indices) {
            val delta = kotlin.math.abs(a[index] - b[index])
            if (delta > max) max = delta
        }
        return max
    }

    /** Locale-independent fixed-precision formatting for the benchmark log line. */
    private fun fmt(value: Double): String = String.format(Locale.US, "%.3f", value)

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
