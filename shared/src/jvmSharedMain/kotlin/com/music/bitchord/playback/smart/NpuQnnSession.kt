package com.music.bitchord.playback.smart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** CPU-only compatibility adapter; no QNN engine/cache implementation ships in djmix52. */
fun NpuSession.describe(): String = NpuQnnSession.describe()
fun NpuSession.describe(session: OrtSession): String = NpuQnnSession.describe(session)

object NpuQnnSession {
    fun interface QdqResolver {
        fun resolve(modelFile: File, candidateName: String): File?
    }

    @Volatile private var installedResolver: QdqResolver = QdqResolver { _, _ -> null }
    private val gatedResolver = QdqResolver { model, name ->
        if (NpuRuntime.runtimeEnabled()) installedResolver.resolve(model, name) else null
    }
    /** Even an independently installed Android resolver cannot unpack QDQ files while dark. */
    var qdqResolver: QdqResolver
        get() = gatedResolver
        set(value) { installedResolver = value }

    private val sessionEp: MutableMap<OrtSession, String> = Collections.synchronizedMap(WeakHashMap())
    private val lastByModel = ConcurrentHashMap<String, String>()

    @Suppress("UNUSED_PARAMETER")
    fun open(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        vendor: NpuSupport.Vendor,
        backendPath: String?,
        cacheDir: File?,
        acceptsFixedShape: Boolean = false,
    ): OrtSession {
        val session = NpuSession.open(modelPath, threads, config, vendor, backendPath, cacheDir)
        val description = "CPU (djmix52; NPU runtime unavailable)"
        sessionEp[session] = description
        lastByModel[File(modelPath).name] = description
        AnalysisLog.warn("BitChordNPU session ${File(modelPath).name} ep=$description")
        return session
    }

    /** Shares the production CPU configuration, including arena/memory-pattern optimizations. */
    fun openCpu(modelPath: String, threads: Int): OrtSession = NpuSession.openCpu(modelPath, threads)

    @Suppress("UNUSED_PARAMETER")
    fun openQnn(
        modelPath: String,
        threads: Int,
        config: NpuRuntime.QnnConfig,
        backendPath: String?,
        cacheDir: File?,
    ): OrtSession {
        check(NpuRuntime.runtimeEnabled()) { "QNN is not supplied in djmix52" }
        throw UnsupportedOperationException("A separately licensed runtime and verified QDQ model are required")
    }

    fun describe(): String = if (lastByModel.isEmpty()) "no sessions opened" else {
        lastByModel.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}=${it.value}" }
    }
    fun describe(session: OrtSession): String = sessionEp[session] ?: "unknown (not opened through NpuSession)"

    @Suppress("UNUSED_PARAMETER")
    fun isQnn(session: OrtSession): Boolean = false
    fun stillAllowed(session: OrtSession): Boolean = !isQnn(session) || NpuRuntime.runtimeEnabled()
    @Suppress("UNUSED_PARAMETER")
    fun onRunFailure(session: OrtSession, failure: Throwable): Boolean = false
    @Suppress("UNUSED_PARAMETER")
    fun onRunSuccess(session: OrtSession) = Unit

    /** Metadata query only, using the official Java TensorInfo interface. */
    fun fixedInputFrames(session: OrtSession): Int? = runCatching {
        val info = session.inputInfo.values.firstOrNull()?.info as? TensorInfo ?: return@runCatching null
        val shape = info.shape
        if (shape.size < 2) null else shape[1].takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }.getOrNull()

    fun ortVersion(): String = runCatching {
        OrtEnvironment::class.java.getMethod("getVersion").invoke(OrtEnvironment.getEnvironment()) as? String
    }.getOrNull() ?: "unknown"
}
