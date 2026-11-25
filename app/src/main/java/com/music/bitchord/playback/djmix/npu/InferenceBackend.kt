// AUTOMIX-V2(A): inference backends behind one interface. CPU ORT is always
// available; the QNN add-on backend is only offered when the registry says so.
package com.music.bitchord.playback.djmix.npu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.music.bitchord.playback.smart.NpuRuntime
import java.io.File
import java.nio.FloatBuffer

/** A loaded model on one backend. Not thread-safe; callers serialise per session. */
interface Session : AutoCloseable {
    /** Runs one inference. Returns the named output, or null on failure. */
    fun run(inputName: String, data: FloatArray, shape: IntArray, outputName: String, outSize: Int): FloatArray?

    /** Which execution provider actually ran: "QNN" or "CPU". */
    val ep: String

    override fun close()
}

/** Outcome of a self-test, persisted by [NpuSelfTestRunner]. */
data class SelfTestResult(
    val passed: Boolean,
    val beatPeakAgreement: Double,
    val cpuMedianMs: Double,
    val npuMedianMs: Double,
    val reason: String,
)

interface InferenceBackend {
    /** Stable id matching `backends` entries in models.json, e.g. "cpu-ort", "qnn-v73". */
    val id: String

    /** Cheap check: true if this backend could open a model right now. */
    fun isAvailable(): Boolean

    /** Opens a session. Throws on failure; callers catch and fall back. */
    fun open(model: ModelSpec): Session

    /** Runs the backend's own health check for [model]. Never throws. */
    fun selfTest(model: ModelSpec): SelfTestResult
}

/** Minimal model description the backends need, derived from models.json. */
data class ModelSpec(
    val id: String,
    val fileName: String,
    val file: File,
    val inputName: String,
    val inputShape: IntArray,
    val outputName: String,
)

/** Official ORT CPU provider. The always-available fallback. */
class CpuOrtBackend : InferenceBackend {
    override val id: String = "cpu-ort"

    override fun isAvailable(): Boolean = true

    override fun open(model: ModelSpec): Session {
        require(model.file.isFile && model.file.length() > 0L) { "model missing: ${model.fileName}" }
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        try {
            val session = env.createSession(model.file.absolutePath, options)
            return CpuSession(env, session, options)
        } catch (failure: Throwable) {
            runCatching { options.close() }
            throw failure
        }
    }

    override fun selfTest(model: ModelSpec): SelfTestResult {
        // The CPU reference is the ground truth; it is trivially its own agreement.
        return runCatching {
            open(model).use { }
            SelfTestResult(true, 1.0, 0.0, 0.0, "cpu reference")
        }.getOrElse {
            SelfTestResult(false, 0.0, 0.0, 0.0, "cpu open failed: ${it.message}")
        }
    }

    private class CpuSession(
        private val env: OrtEnvironment,
        private val session: OrtSession,
        private val options: OrtSession.SessionOptions,
    ) : Session {
        override val ep: String = "CPU"

        override fun run(
            inputName: String,
            data: FloatArray,
            shape: IntArray,
            outputName: String,
            outSize: Int,
        ): FloatArray? = synchronized(lock) {
            if (closed) return@synchronized null
            runCatching {
            val longShape = LongArray(shape.size) { shape[it].toLong() }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longShape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { outputs ->
                    val value = outputs.get(outputName).orElse(null) as? OnnxTensor ?: return@runCatching null
                    val buf = value.floatBuffer
                    val n = minOf(buf.remaining(), outSize)
                    FloatArray(n).also { buf.get(it) }
                }
            }
            }.getOrNull()
        }

        private val lock = Any()
        @Volatile private var closed = false
        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                runCatching { session.close() }
                runCatching { options.close() }
            }
        }
    }
}

/**
 * Orchard add-on backend. Only usable when the add-on is installed, the bridge
 * loaded, and the self-test passed for this (device, app, addon) tuple.
 */
class QnnAddonBackend(
    private val context: Context,
    private val addon: () -> NpuAddon?,
) : InferenceBackend {
    override val id: String = "qnn-v73"

    override fun isAvailable(): Boolean =
        NpuRuntime.RUNTIME_AVAILABLE && NpuRuntime.productionQnnAllowed() &&
            OrchardNpuBridge.bridgeLoaded() && addon() != null

    override fun open(model: ModelSpec): Session {
        check(NpuRuntime.RUNTIME_AVAILABLE && NpuRuntime.productionQnnAllowed()) {
            "NPU runtime gate is closed"
        }
        val current = addon() ?: throw IllegalStateException("NPU add-on not installed")
        check(OrchardNpuBridge.bridgeLoaded()) { "NPU bridge not loaded" }
        val staged = OrchardNpuBridge.stageQnnModels(context, current)
            ?: throw IllegalStateException("NPU models not staged")
        val graph = when (model.id) {
            "nirovi-qnn" -> staged.first
            "syvenu-qnn" -> staged.second
            else -> throw IllegalArgumentException("no QNN graph for ${model.id}")
        }
        val handle = OrchardNpuBridge.open(current, graph, OrchardNpuBridge.cacheDir(context))
        if (handle == 0L) throw IllegalStateException("orchard_qnn_open failed")
        return QnnSession(handle)
    }

    override fun selfTest(model: ModelSpec): SelfTestResult =
        // Parity against CPU lives in NpuSelfTestRunner, which owns the window and timing.
        runCatching {
            open(model).use { }
            SelfTestResult(true, 0.0, 0.0, 0.0, "open ok; parity runs in NpuSelfTestRunner")
        }.getOrElse {
            SelfTestResult(false, 0.0, 0.0, 0.0, "qnn open failed: ${it.message}")
        }

    private class QnnSession(private val handle: Long) : Session {
        private val lock = Any()
        @Volatile private var closed = false
        override val ep: String = "QNN"

        override fun run(
            inputName: String,
            data: FloatArray,
            shape: IntArray,
            outputName: String,
            outSize: Int,
        ): FloatArray? {
            synchronized(lock) {
                if (closed) return null
                return OrchardNpuBridge.run(handle, inputName, data, shape, outSize)
            }
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                OrchardNpuBridge.close(handle)
            }
        }
    }

    private companion object {
        private const val TAG = "BitChordNPU"
        init { Log.d(TAG, "QnnAddonBackend loaded") }
    }
}
