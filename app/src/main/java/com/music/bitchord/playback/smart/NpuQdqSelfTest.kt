package com.music.bitchord.playback.smart

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * AUTOMIX-V2(A): bounded one-shot NPU self-test with a numerical verdict. New file beside
 * the legacy [NpuSelfTest] (untouched; it stays dormant behind NpuSupport.QNN_ENABLED=false).
 *
 * Runs the statically quantized Beat This! model (`beat_this_final0_qdq.onnx`, or its
 * precompiled `_qdq_ctx.onnx`) on QNN with `session.disable_cpu_ep_fallback=1` and graph-I/O
 * quantization inside QNN, so a silent CPU fallback makes the open FAIL instead of passing
 * as "NPU". The same deterministic input then runs through the QDQ model on the CPU EP; the
 * logits are compared (max/mean abs diff, beat-peak F-measure) and timed. One summary line:
 *
 *   BitChordNPU selftest ep=QNN ms=… cpuMs=… openMs=… maxAbsDiff=… meanAbsDiff=… peakF=… verdict=…
 *
 * The verdict feeds [NpuPolicy]: PASS unlocks QNN for production sessions, MISMATCH
 * auto-disables the NPU until the next versionCode, QNN_FAILED counts toward the
 * three-strikes auto-disable, SLOW keeps production on CPU without disabling anything.
 *
 * Once per versionCode (unless [AutomixV2Npu.selfTestEveryLaunch]); once per process at
 * most; on its own background daemon thread, never on the UI or audio thread; never throws.
 */
object NpuQdqSelfTest {
    private const val TAG = "BitChordNPU"
    private const val START_DELAY_MS = 3000L
    /** Fixed small thread count for the CPU reference (the comparison, not throughput, matters). */
    private const val THREADS = 2
    /** Bound on input elements (4M floats = 16 MB worst case). */
    private const val MAX_INPUT_ELEMENTS = 4_000_000L
    /** Beat-peak match window in frames (2 frames = 40 ms at 50 fps). */
    private const val PEAK_TOLERANCE_FRAMES = 2.0
    private const val MIN_PEAKS_FOR_F = 4

    private val started = AtomicBoolean(false)

    /** Last summary line (also logged), for a diagnostics screen. */
    @Volatile var lastSummary: String = "not run"
        private set

    /**
     * Schedules the probe once. context is only read after attach
     * (post-delay on the probe thread), so an unattached Service
     * reference is safe. The bundled backend (and its ADSP preflight) comes from
     * NpuRuntime.backendPath(), installed by NpuAndroid.install.
     */
    fun schedule(
        context: Context,
        vendor: () -> NpuSupport.Vendor,
    ) {
        if (!NpuRuntime.runtimeEnabled()) {
            summary("none", 0, 0, 0, null, null, NpuVerdict.SKIPPED, "npu-off")
            return
        }
        if (!started.compareAndSet(false, true)) return
        Thread({
            try {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                Thread.sleep(START_DELAY_MS)
                run(context, vendor)
            } catch (_: Throwable) {
                // Never propagate out of the probe thread.
            }
        }, "bitchord-npu-qdq-selftest").apply { isDaemon = true }.start()
    }

    private fun run(
        context: Context,
        vendor: () -> NpuSupport.Vendor,
    ) {
        val policy = NpuRuntime.policy()
        if (!NpuRuntime.runtimeEnabled() || policy == null) {
            summary("none", 0, 0, 0, null, null, NpuVerdict.SKIPPED, "npu-off")
            return
        }
        if (!AutomixV2Npu.selfTestEveryLaunch && policy.selfTestDone()) {
            Log.i(TAG, "BitChordNPU selftest cached " + policy.summary())
            return
        }
        if (shouldSkipBackgrounded(context)) {
            summary("none", 0, 0, 0, null, null, NpuVerdict.SKIPPED, "backgrounded")
            return
        }
        val appContext = runCatching { context.applicationContext }.getOrNull()
        if (appContext == null) {
            summary("none", 0, 0, 0, null, null, NpuVerdict.SKIPPED, "no-application-context")
            return
        }
        val v = runCatching { vendor() }.getOrDefault(NpuSupport.Vendor.NONE)
        if (v != NpuSupport.Vendor.QUALCOMM) {
            summary("none", 0, 0, 0, null, null, NpuVerdict.SKIPPED, "vendor=" + v)
            return
        }
        val candidate = NpuRuntime.qnnCandidateName(BeatTracker.MODEL_ASSET)
        val anchor = File(appContext.filesDir, BeatTracker.MODEL_ASSET)
        val qdq = candidate?.let { name -> runCatching { NpuQnnSession.qdqResolver.resolve(anchor, name) }.getOrNull() }
        if (candidate == null || qdq == null) {
            summary("none", 0, 0, 0, null, null, NpuVerdict.SKIPPED, "no-qdq-model")
            return
        }
        val contextName = NpuRuntime.precompiledContextName(candidate)
        val qnnFile = contextName?.let { name -> runCatching { NpuQnnSession.qdqResolver.resolve(anchor, name) }.getOrNull() } ?: qdq

        // Mirrors production: ADSP path + dlopen preflight before any QNN session.
        val backend = NpuRuntime.backendPath()
        if (backend == null) {
            record(policy, "QNN-FAILED", 0, 0, 0, null, null, NpuVerdict.QNN_FAILED, "backend-missing")
            return
        }

        val strict = NpuRuntime.QnnConfig(
            strictPartition = true,
            offloadGraphIoQuantization = false,
            contextCaching = false,
        )
        val openStart = SystemClock.elapsedRealtime()
        val opened: OrtSession = try {
            NpuQnnSession.openQnn(qnnFile.absolutePath, THREADS, strict, backend, null)
        } catch (t: Throwable) {
            val ms = SystemClock.elapsedRealtime() - openStart
            record(policy, "QNN-FAILED", 0, 0, ms, null, null, NpuVerdict.QNN_FAILED,
                "open:" + (t.message ?: t.javaClass.simpleName).replace('\n', ' ').take(160))
            return
        }
        val openMs = SystemClock.elapsedRealtime() - openStart
        val description = NpuQnnSession.describe(opened)

        val shape = inputShape(opened)
        if (shape == null) {
            runCatching { opened.close() }
            summary("QNN", 0, 0, openMs, null, null, NpuVerdict.SKIPPED, "unexpected-input-shape")
            return
        }
        val input = NpuRuntime.selfTestInput(shape[1].toInt(), shape[2].toInt())

        var qnn: OrtSession? = opened
        var qnnOut = timedRun(opened, input, shape)
        if (qnnOut == null) {
            // ENGINE_ERROR after a DSP restart: recreate the session once.
            runCatching { opened.close() }
            qnn = runCatching { NpuQnnSession.openQnn(qnnFile.absolutePath, THREADS, strict, backend, null) }.getOrNull()
            qnnOut = qnn?.let { timedRun(it, input, shape) }
        }
        runCatching { qnn?.close() }
        if (qnnOut == null) {
            record(policy, "QNN-FAILED", 0, 0, openMs, null, null, NpuVerdict.QNN_FAILED, "run-failed")
            return
        }

        val cpu = runCatching { NpuQnnSession.openCpu(qdq.absolutePath, THREADS) }.getOrNull()
        val cpuOut = cpu?.let { timedRun(it, input, shape) }
        runCatching { cpu?.close() }
        if (cpuOut == null) {
            summary("QNN", qnnOut.ms, 0, openMs, null, null, NpuVerdict.SKIPPED, "cpu-reference-failed")
            return
        }

        val beatDiff = NpuRuntime.compareLogits(cpuOut.beat, qnnOut.beat)
        val downDiff = NpuRuntime.compareLogits(cpuOut.downbeat, qnnOut.downbeat)
        val diff = NpuRuntime.LogitDiff(
            maxAbs = max(beatDiff.maxAbs, downDiff.maxAbs),
            meanAbs = (beatDiff.meanAbs + downDiff.meanAbs) / 2,
            count = beatDiff.count + downDiff.count,
        )
        val referencePeaks = BeatTracker.pickPeaks(cpuOut.beat)
        val peakF = if (referencePeaks.size >= MIN_PEAKS_FOR_F) {
            NpuRuntime.peakFMeasure(referencePeaks, BeatTracker.pickPeaks(qnnOut.beat), PEAK_TOLERANCE_FRAMES)
        } else {
            null
        }
        val verdict = NpuRuntime.judgeSelfTest(diff, peakF, qnnOut.ms.toDouble(), cpuOut.ms.toDouble())
        record(policy, "QNN", qnnOut.ms, cpuOut.ms, openMs, diff, peakF, verdict, description)
    }

    private class Outputs(val beat: FloatArray, val downbeat: FloatArray, val ms: Long)

    /** Warm-up run plus one timed run; null on any failure. */
    private fun timedRun(session: OrtSession, input: FloatArray, shape: LongArray): Outputs? {
        var tensor: OnnxTensor? = null
        return try {
            val name = session.inputNames.first()
            val created = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input), shape)
            tensor = created
            session.run(mapOf(name to created)).use { }
            val start = SystemClock.elapsedRealtime()
            session.run(mapOf(name to created)).use { result ->
                val ms = SystemClock.elapsedRealtime() - start
                if (result.size() < 2) return null
                val beat = flatten(result.get(0).value) ?: return null
                val downbeat = flatten(result.get(1).value) ?: return null
                Outputs(beat, downbeat, ms)
            }
        } catch (failure: Throwable) {
            // Not reported to NpuQnnSession.onRunFailure: the self-test records its own verdict.
            Log.w(TAG, "BitChordNPU selftest run failed on " + NpuQnnSession.describe(session), failure)
            null
        } finally {
            runCatching { tensor?.close() }
        }
    }

    /** [1, T, mels] with dynamic dims replaced by the beat tracker's chunk geometry. */
    private fun inputShape(session: OrtSession): LongArray? = runCatching {
        val info = session.inputInfo.values.firstOrNull()?.info as? TensorInfo ?: return@runCatching null
        val raw = info.shape
        if (raw.size != 3) return@runCatching null
        val shape = longArrayOf(
            1L,
            if (raw[1] > 0) raw[1] else BeatTracker.CHUNK_FRAMES.toLong(),
            if (raw[2] > 0) raw[2] else 128L,
        )
        val elements = shape[0] * shape[1] * shape[2]
        if (elements <= 0 || elements > MAX_INPUT_ELEMENTS) null else shape
    }.getOrNull()

    /** Flattens a [1, T] (or [T]) float output into one array. */
    private fun flatten(value: Any?): FloatArray? = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            val parts = value.map { flatten(it) ?: return null }
            val out = FloatArray(parts.sumOf { it.size })
            var offset = 0
            for (part in parts) {
                part.copyInto(out, offset)
                offset += part.size
            }
            out
        }
        else -> null
    }

    private fun record(
        policy: NpuPolicy,
        ep: String,
        qnnMs: Long,
        cpuMs: Long,
        openMs: Long,
        diff: NpuRuntime.LogitDiff?,
        peakF: Double?,
        verdict: NpuVerdict,
        detail: String,
    ) {
        runCatching { policy.recordSelfTest(verdict, detail) }
        summary(ep, qnnMs, cpuMs, openMs, diff, peakF, verdict, detail)
        Log.i(TAG, "BitChordNPU policy " + runCatching { policy.summary() }.getOrDefault("?"))
    }

    private fun summary(
        ep: String,
        qnnMs: Long,
        cpuMs: Long,
        openMs: Long,
        diff: NpuRuntime.LogitDiff?,
        peakF: Double?,
        verdict: NpuVerdict,
        detail: String,
    ) {
        val soc = NpuRuntime.socModel() ?: runCatching { Build.HARDWARE }.getOrDefault("unknown")
        val target = NpuRuntime.htpTargetFor(NpuRuntime.socModel())
        val line = String.format(
            Locale.US,
            "BitChordNPU selftest ep=%s ms=%d cpuMs=%d openMs=%d maxAbsDiff=%s meanAbsDiff=%s peakF=%s verdict=%s soc=%s htp=%s ort=%s detail=%s",
            ep,
            qnnMs,
            cpuMs,
            openMs,
            diff?.let { String.format(Locale.US, "%.4f", it.maxAbs) } ?: "NaN",
            diff?.let { String.format(Locale.US, "%.4f", it.meanAbs) } ?: "NaN",
            peakF?.let { String.format(Locale.US, "%.3f", it) } ?: "n/a",
            verdict.name,
            soc,
            target?.let { "v" + it.arch + "/" + (it.socModel?.toString() ?: "auto") } ?: "auto",
            NpuQnnSession.ortVersion(),
            detail,
        )
        lastSummary = line
        Log.i(TAG, line)
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
