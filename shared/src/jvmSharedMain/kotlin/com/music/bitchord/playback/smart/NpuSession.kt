package com.music.bitchord.playback.smart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * CPU session factory using the official ONNX Runtime Java interfaces:
 * https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html
 *
 * djmix52 links the standard ORT 1.31.0 AAR. No backend loading, vendor path setup,
 * provider registration or EP-context engine is included here. The dormant QNN
 * signatures remain for compatibility, not as an alternative way around the release gate.
 * Session creation belongs on the analysis/preparation worker, never on the audio thread.
 */
object NpuSession {
    private val sessionEp: MutableMap<OrtSession, String> =
        Collections.synchronizedMap(WeakHashMap())
    private val lastByModel = ConcurrentHashMap<String, String>()
    private class SessionOptionsRef(session: OrtSession, queue: ReferenceQueue<OrtSession>) :
        WeakReference<OrtSession>(session, queue) {
        @Volatile var options: OrtSession.SessionOptions? = null
    }

    private val sessionQueue = ReferenceQueue<OrtSession>()
    private val retainedOptions = ConcurrentHashMap<SessionOptionsRef, Boolean>()
    private val reaperStarted = AtomicBoolean(false)

    @Suppress("UNUSED_PARAMETER")
    fun open(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        vendor: NpuSupport.Vendor,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
        acceptsFixedShape: Boolean = false,
    ): OrtSession {
        val session = try {
            openCpu(modelPath, threads)
        } catch (failure: Throwable) {
            warnSafely("ORT CPU open failed; model unavailable", failure)
            throw failure
        }
        val description = "CPU (djmix52; NPU runtime unavailable)"
        sessionEp[session] = description
        lastByModel[File(modelPath).name] = description
        // Best-effort success diagnostic only. The session above is already created and is
        // about to be handed to the caller as its owned session, so a throwing AnalysisLog
        // sink must not turn a successful open into a failure: direct callers such as
        // VocalTracker/OrtStemEngine would catch it, discard the session and never close it.
        // Deliberately contained at this one call site; the global sink policy is not changed
        // here and remains D4's accounting item.
        warnSafely("BitChordNPU session ${File(modelPath).name} ep=$description")
        return session
    }

    /** One CPU EP, up to four intra-op workers, one inter-op worker, no NNAPI/delegate. */
    fun openCpu(modelPath: String, threads: Int): OrtSession {
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(threads.coerceIn(1, 4))
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setCPUArenaAllocator(true)
            options.setMemoryPatternOptimization(true)
            val session = OrtEnvironment.getEnvironment().createSession(modelPath, options)
            retainOptions(session, options)
            return session
        } catch (failure: Throwable) {
            runCatching { options.close() }
            throw failure
        }
    }

    /** Closes a session opened here, then releases the options retained for its lifetime. */
    fun close(session: OrtSession) {
        try {
            session.close()
        } finally {
            val ref = retainedOptions.keys.firstOrNull { it.get() === session }
            if (ref != null && retainedOptions.remove(ref) != null) closeOptions(ref.options)
            sessionEp.remove(session)
        }
    }

    private fun retainOptions(session: OrtSession, options: OrtSession.SessionOptions) {
        val ref = SessionOptionsRef(session, sessionQueue)
        ref.options = options
        retainedOptions[ref] = true
        if (reaperStarted.compareAndSet(false, true)) {
            Thread({
                while (true) {
                    try {
                        val collected = sessionQueue.remove() as SessionOptionsRef
                        if (retainedOptions.remove(collected) != null) closeOptions(collected.options)
                    } catch (interrupted: InterruptedException) {
                        // The reaper is process-lived; an interrupt only restarts the wait.
                    }
                }
            }, "BitChord-ORT-options").apply { isDaemon = true }.start()
        }
    }

    private fun closeOptions(options: OrtSession.SessionOptions?) {
        if (options == null) return
        try {
            options.close()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            warnSafely("ORT SessionOptions close failed", failure)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun openQnn(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
        provider: NpuSupport.InferenceProvider = NpuSupport.InferenceProvider.QNN_HTP_QDQ,
    ): OrtSession {
        check(NpuRuntime.RUNTIME_AVAILABLE && NpuSupport.QNN_ENABLED) { "QNN is not supplied in djmix52" }
        throw UnsupportedOperationException("A separately licensed runtime and verified model must implement this entry point")
    }

    fun openQnnDiagnostic(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
        provider: NpuSupport.InferenceProvider = NpuSupport.InferenceProvider.QNN_HTP_QDQ,
    ): OrtSession = openQnn(modelPath, threads, config, backendPath, cacheDir, socModel, provider)

    fun describe(): String = if (lastByModel.isEmpty()) "no sessions opened" else {
        lastByModel.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun describe(session: OrtSession): String =
        sessionEp[session] ?: "unknown (not opened through NpuSession)"

    /** Metadata query only, using the official Java TensorInfo interface. */
    fun fixedInputFrames(session: OrtSession): Int? = runCatching {
        val info = session.inputInfo.values.firstOrNull()?.info as? TensorInfo ?: return@runCatching null
        val shape = info.shape
        if (shape.size < 2) null else shape[1].takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }.getOrNull()

    private fun warnSafely(message: String, failure: Throwable? = null) {
        try {
            if (failure == null) AnalysisLog.warn(message) else AnalysisLog.warn(message, failure)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // Optional diagnostics must not discard a session or block CPU fallback.
        }
    }
}
