// AUTOMIX-V2(A): parity and latency self-test for the QNN Nirovi graph against
// the CPU reference. The verdict is persisted per (fingerprint, appVersion, addon)
// so Settings and the log can report ep=QNN or ep=CPU (<reason>).
package com.music.bitchord.playback.djmix.npu

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Runs the QNN-vs-CPU parity and timing check for Nirovi. */
object NpuSelfTestRunner {
    private const val TAG = "BitChordNPU"
    private const val STORE_NAME = "npu_selftest.json"
    private const val WARMUPS = 2
    private const val TIMED_RUNS = 7
    private const val PEAK_TOLERANCE_FRAMES = 1
    private const val MIN_AGREEMENT = 0.95
    private const val FRAMES = 1500
    private const val MEL_BINS = 128

    /** Deterministic window: a fixed-seed click train over a noise floor. Same every run. */
    internal fun deterministicWindow(): FloatArray {
        val out = FloatArray(FRAMES * MEL_BINS)
        var seed = 0x2545F491L
        for (frame in 0 until FRAMES) {
            val beatFrame = frame % 50 == 0
            for (bin in 0 until MEL_BINS) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                val noise = ((seed ushr 40).toFloat() / (1 shl 24).toFloat()) * 0.05f
                out[frame * MEL_BINS + bin] = noise + if (beatFrame) 0.8f else 0f
            }
        }
        return out
    }

    /** Frame indices of local maxima above a threshold, the beat-peak set. */
    internal fun peaks(values: FloatArray, threshold: Float): List<Int> {
        val result = mutableListOf<Int>()
        for (i in 1 until values.size - 1) {
            if (values[i] >= threshold && values[i] >= values[i - 1] && values[i] > values[i + 1]) {
                result += i
            }
        }
        return result
    }

    /**
     * Fraction of CPU beat peaks that have a QNN peak within ±1 frame. Also requires
     * the QNN peak set not to be wildly larger than the CPU set, so an all-positive
     * output cannot pass.
     */
    internal fun beatPeakAgreement(cpu: FloatArray, qnn: FloatArray): Double {
        val cpuPeaks = peaks(cpu, 0.5f)
        if (cpuPeaks.isEmpty()) return 0.0
        val qnnPeaks = peaks(qnn, 0.5f)
        if (qnnPeaks.isEmpty()) return 0.0
        var matched = 0
        for (p in cpuPeaks) {
            if (qnnPeaks.any { abs(it - p) <= PEAK_TOLERANCE_FRAMES }) matched++
        }
        if (qnnPeaks.size > cpuPeaks.size * 2) return 0.0
        return matched.toDouble() / cpuPeaks.size
    }

    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * Runs the full check. Never throws. Returns a record that may be persisted.
     * [cpu] and [qnn] are the two open sessions; the caller owns their lifetime.
     */
    fun run(
        context: Context,
        cpu: Session,
        qnn: Session,
        spec: ModelSpec,
        addonVersionCode: Long,
    ): SelfTestRecord {
        val fingerprint = Build.FINGERPRINT
        val appCode = appVersionCode(context)
        val window = deterministicWindow()
        val shape = intArrayOf(1, FRAMES, MEL_BINS)
        val outSize = FRAMES

        fun fail(reason: String) = SelfTestRecord(
            fingerprint, appCode, addonVersionCode, false, 0.0, 0.0, 0.0, reason,
        )

        val cpuOut = cpu.run(spec.inputName, window, shape, spec.outputName, outSize)
            ?: return fail("cpu run returned null")
        val qnnOut = qnn.run(spec.inputName, window, shape, spec.outputName, outSize)
            ?: return fail("qnn run returned null")
        if (qnn.ep != "QNN") return fail("session not on QNN: ep=${qnn.ep}")

        val agreement = beatPeakAgreement(cpuOut, qnnOut)
        if (agreement < MIN_AGREEMENT) {
            return SelfTestRecord(
                fingerprint, appCode, addonVersionCode, false, agreement, 0.0, 0.0,
                "parity ${"%.3f".format(agreement)} < $MIN_AGREEMENT",
            )
        }

        val cpuTimes = timed(WARMUPS, TIMED_RUNS) { cpu.run(spec.inputName, window, shape, spec.outputName, outSize) }
        val npuTimes = timed(WARMUPS, TIMED_RUNS) { qnn.run(spec.inputName, window, shape, spec.outputName, outSize) }
        if (cpuTimes == null || npuTimes == null) return fail("timed run failed")

        val cpuMed = median(cpuTimes)
        val npuMed = median(npuTimes)
        val passed = npuMed < cpuMed
        val reason = if (passed) "parity ${"%.3f".format(agreement)}, npu ${"%.1f".format(npuMed)} ms < cpu ${"%.1f".format(cpuMed)} ms"
        else "npu ${"%.1f".format(npuMed)} ms not faster than cpu ${"%.1f".format(cpuMed)} ms"
        Log.i(TAG, "selftest ep=QNN agreement=$agreement cpuMs=$cpuMed npuMs=$npuMed verdict=${if (passed) "PASS" else "FAIL"}")
        return SelfTestRecord(fingerprint, appCode, addonVersionCode, passed, agreement, cpuMed, npuMed, reason)
    }

    /** Warmups are discarded; each timed run returns milliseconds, or null on failure. */
    private fun timed(warmups: Int, runs: Int, step: () -> FloatArray?): List<Double>? {
        repeat(warmups) { if (step() == null) return null }
        val out = ArrayList<Double>(runs)
        repeat(runs) {
            val start = System.nanoTime()
            if (step() == null) return null
            out += (System.nanoTime() - start) / 1_000_000.0
        }
        return out
    }

    private fun appVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    // ---- persistence (per device / app / add-on) ----

    fun load(context: Context): SelfTestRecord? = runCatching {
        val json = JSONObject(File(context.filesDir, STORE_NAME).readText())
        SelfTestRecord(
            fingerprint = json.getString("fingerprint"),
            appVersionCode = json.getLong("appVersionCode"),
            addonVersionCode = json.getLong("addonVersionCode"),
            passed = json.getBoolean("passed"),
            beatPeakAgreement = json.getDouble("beatPeakAgreement"),
            cpuMedianMs = json.getDouble("cpuMedianMs"),
            npuMedianMs = json.getDouble("npuMedianMs"),
            reason = json.getString("reason"),
        )
    }.getOrNull()

    fun save(context: Context, record: SelfTestRecord) {
        val json = JSONObject()
            .put("fingerprint", record.fingerprint)
            .put("appVersionCode", record.appVersionCode)
            .put("addonVersionCode", record.addonVersionCode)
            .put("passed", record.passed)
            .put("beatPeakAgreement", record.beatPeakAgreement)
            .put("cpuMedianMs", record.cpuMedianMs)
            .put("npuMedianMs", record.npuMedianMs)
            .put("reason", record.reason)
        val target = File(context.filesDir, STORE_NAME)
        val tmp = File(context.filesDir, "$STORE_NAME.part")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) tmp.delete()
    }

    /** Settings label: "NPU: Ready (Hexagon V73)" only when the stored verdict is current. */
    fun statusLabel(context: Context, addonVersionCode: Long?): String {
        if (addonVersionCode == null) return "NPU: Not installed"
        val record = load(context) ?: return "NPU: Not tested"
        val current = record.fingerprint == Build.FINGERPRINT &&
            record.appVersionCode == appVersionCode(context) &&
            record.addonVersionCode == addonVersionCode
        return when {
            !current -> "NPU: Not tested (changed since last test)"
            record.passed -> "NPU: Ready (Hexagon V73)"
            else -> "NPU: Off (${record.reason})"
        }
    }
}
