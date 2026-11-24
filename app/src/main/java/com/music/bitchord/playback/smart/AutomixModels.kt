package com.music.bitchord.playback.smart

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Dns
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** An inode and its full-resolution timestamps, not just a model-sized file. No symlinks. */
internal data class ModelFileIdentity(
    val device: Long,
    val inode: Long,
    val bytes: Long,
    val modified: FileTime,
    val changed: FileTime,
)

internal fun modelFileIdentity(file: File): ModelFileIdentity? = runCatching {
    // Android's Linux NIO provider exposes ctime as well as mtime. ctime catches an
    // in-place, same-size edit even if somebody restores the original mtime.
    val attrs = Files.readAttributes(
        file.toPath(), "unix:dev,ino,size,lastModifiedTime,ctime,isRegularFile", LinkOption.NOFOLLOW_LINKS,
    )
    if (attrs["isRegularFile"] != true) return@runCatching null
    ModelFileIdentity(
        (attrs["dev"] as Number).toLong(), (attrs["ino"] as Number).toLong(),
        (attrs["size"] as Number).toLong(), attrs["lastModifiedTime"] as FileTime, attrs["ctime"] as FileTime,
    )
}.getOrNull()

/** Worker-only SHA verification; a file changed during hashing is never accepted. */
internal fun verifiedModelIdentity(file: File, bytes: Long, sha256: String): ModelFileIdentity? = runCatching {
    val before = modelFileIdentity(file) ?: return@runCatching null
    if (before.bytes != bytes) return@runCatching null
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    before.takeIf { hash == sha256 && modelFileIdentity(file) == before }
}.getOrNull()

/** Unknown/offline is not permission, even for a batch with explicit metered consent. */
internal fun modelNetworkAllowed(metered: Boolean?, allowMetered: Boolean): Boolean =
    metered == false || (metered == true && allowMetered)

/**
 * The single Android delivery coordinator for the three pinned models-v1 weights.
 * All SHA work and HTTP run on IO workers. Trackers and the Android StemModelStore
 * only receive paths whose verified inode/timestamps still match. vc93's filesDir
 * copies and the old filesDir/stems copies are checked before any network request.
 *
 * Enabling Automix requests preparation, not metered consent. Consent is ephemeral,
 * applies only to the current batch, and is cleared on completion, failure or delete.
 */
object AutomixModels : StemModelCoordinator {
    class Model(val fileName: String, val bytes: Long, val sha256: String) {
        val url: String get() = RELEASE_URL + fileName
    }

    /** Compatibility for the existing settings wrapper; no asset-unpacking mode remains. */
    const val BUNDLED_ASSETS_ACTIVE = false
    private const val RELEASE_URL =
        "https://github.com/survey-sudo/bitchord-unified/releases/download/models-v1/"

    val BEAT = Model(
        "beat_this_final0_q8.onnx", 23_138_816L,
        "7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd",
    )
    val VOCALS = Model(
        "vocals_umxhq_int8.onnx", 9_054_206L,
        "a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb",
    )
    val SCNET = Model(
        "scnet.int8.onnx", 12_900_277L,
        "98228931494151762a1c4ab1ec7899a894b1f81fd4a509921a9d2f9bacc50845",
    )
    private val ALL = listOf(BEAT, VOCALS, SCNET)
    val totalBytes: Long = ALL.sumOf { it.bytes } // 45,093,299 B; no unpinned fourth model.

    sealed interface State {
        data object Ready : State
        data object Missing : State
        /** UI: Preparing models. Also used while upgrade copies are hashed offline. */
        data object Verifying : State
        /** UI: Preparing models, with transfer progress. */
        data class Downloading(val fraction: Float) : State
        data object WaitingForNetwork : State
        data object AwaitingMeteredConsent : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _availabilityGeneration = MutableStateFlow(0L)
    /** A new verified all-model availability lets failed analysis attempts retry. */
    val availabilityGeneration: StateFlow<Long> = _availabilityGeneration.asStateFlow()

    private const val TAG = "AutomixModels"
    private const val RETRY_AFTER_MS = 2 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var filesDir: File? = null
    @Volatile private var application: Context? = null
    private data class VerifiedFile(val file: File, val identity: ModelFileIdentity)
    private val verified = mutableMapOf<String, VerifiedFile>()
    private val rejected = mutableMapOf<String, ModelFileIdentity>()
    private val observed = mutableMapOf<String, ModelFileIdentity?>()
    private var verifiedRevision = 0L
    private var readyRevision = -1L
    private var working = false
    private var failedAt = 0L
    private var activeCall: Call? = null

    private class Batch(downloadRequested: Boolean, allowMetered: Boolean) {
        @Volatile var downloadRequested = downloadRequested
        @Volatile var allowMetered = allowMetered
        @Volatile var interrupted = false
    }
    private var pending: Batch? = null
    private class NetworkWait(val waitingState: State, val policyBlocked: Boolean = false) :
        IOException("Waiting for an authorized network")

    /** Called after settings initialization, including restored-enabled installs. */
    fun install(context: Context) {
        synchronized(lock) {
            if (filesDir != null) return
            application = context.applicationContext
            filesDir = context.applicationContext.filesDir
        }
        // Always verify/reuse local upgrade copies, including when offline or disabled.
        request(force = true, downloadMissing = false)
        scope.launch {
            AppSettings.smartFadeEnabled.collect { on ->
                if (on) request(force = true) // force retries; it NEVER grants consent.
            }
        }
        scope.launch {
            AppSettings.meteredConnection.collect { networkChanged() }
        }
    }

    /** No hashing here. A changed/missing file is refused and queued for IO verification. */
    fun path(model: Model): String {
        if (model !in ALL) throw FileNotFoundException("Not a models-v1 release model: ${model.fileName}")
        synchronized(lock) { trustedLocked(model)?.let { return it.absolutePath } }
        request(force = false)
        throw FileNotFoundException("${model.fileName} is not verified yet (Preparing models)")
    }

    /** Cheap identity checks only; safe at the analysis request gate, before claiming renditions. */
    fun isReady(): Boolean {
        synchronized(lock) {
            if (allTrustedLocked()) {
                readyLocked()
                return true
            }
        }
        request(force = false)
        return false
    }

    /** Retains zero-argument callable references used by existing Download / Retry rows. */
    fun download() = download(allowMetered = false)

    /** allowMetered=true must come ONLY from an explicit UI confirmation for this batch. */
    fun download(allowMetered: Boolean = false) {
        request(force = true, allowMetered = allowMetered)
    }

    /** Deletes only our three exact filenames and their partials, never a directory/cache. */
    fun delete(): Boolean = deleteModels(ALL)

    // Every independent Android stem store entry delegates here; there is no second HTTP installer.
    override fun resolve(model: StemModelFile): File? {
        val pin = managed(model) ?: return null
        return runCatching { File(path(pin)) }.getOrNull()
    }

    override fun install(spec: StemModelSpec): Boolean {
        if (spec.files.any { managed(it) == null }) return false
        request(force = false)
        return spec.files.all { resolve(it) != null }
    }

    override fun uninstall(spec: StemModelSpec) {
        deleteModels(spec.files.mapNotNull(::managed))
    }

    private fun managed(model: StemModelFile): Model? = ALL.firstOrNull {
        it.fileName == model.fileName && it.bytes == model.bytes && it.sha256 == model.sha256
    }

    private fun copies(model: Model): List<File> = filesDir?.let {
        listOf(File(it, model.fileName), File(File(it, "stems"), model.fileName))
    }.orEmpty()

    private fun trustedLocked(model: Model): File? {
        val copy = verified[model.fileName] ?: return null
        if (modelFileIdentity(copy.file) == copy.identity) return copy.file
        verified.remove(model.fileName)
        verifiedRevision++
        if (_state.value == State.Ready) _state.value = State.Verifying
        return null
    }

    private fun allTrustedLocked(): Boolean = ALL.map { trustedLocked(it) != null }.all { it }

    private fun readyLocked() {
        if (readyRevision != verifiedRevision) {
            readyRevision = verifiedRevision
            _availabilityGeneration.value += 1L
        }
        failedAt = 0L
        pending = null // Consent is never carried into another request/retry.
        _state.value = State.Ready
    }

    private fun observeLocked() {
        for (model in ALL) for (file in copies(model)) observed[file.absolutePath] = modelFileIdentity(file)
    }

    private fun localFilesChangedLocked(): Boolean = ALL.any { model ->
        copies(model).any { file ->
            file.absolutePath !in observed || observed[file.absolutePath] != modelFileIdentity(file)
        }
    }

    private fun deleteModels(models: List<Model>): Boolean {
        synchronized(lock) {
            // A hashing/transfer worker must not publish after a delete. Waiting batches
            // have no active worker, so Delete can cancel them and revoke their consent.
            if (working || filesDir == null) return false
            pending = null
            var removed = true
            for (model in models.distinct()) {
                if (verified.remove(model.fileName) != null) verifiedRevision++
                for (file in copies(model)) {
                    for (target in listOf(file, File(file.parentFile, file.name + ".part"), File(file.parentFile, file.name + ".tmp"))) {
                        if (target.exists() && !target.delete()) removed = false
                    }
                }
            }
            observeLocked()
            failedAt = 0L
            _state.value = State.Missing
            Log.i(TAG, "Automix model delete completed=$removed")
            return removed
        }
    }

    private fun request(force: Boolean, allowMetered: Boolean = false, downloadMissing: Boolean = true) {
        synchronized(lock) {
            if (filesDir == null) return
            if (!working && allTrustedLocked()) {
                readyLocked()
                return
            }
            if (!force && pending == null && failedAt != 0L &&
                SystemClock.elapsedRealtime() - failedAt < RETRY_AFTER_MS && !localFilesChangedLocked()
            ) return
            val batch = pending ?: Batch(downloadMissing, allowMetered).also { pending = it }
            if (downloadMissing) batch.downloadRequested = true
            if (allowMetered) batch.allowMetered = true // Only download(true) reaches this assignment.
            if (working) return
            if (!force && !networkAllowed(batch) && !localFilesChangedLocked()) {
                _state.value = waitingState()
                return
            }
            startLocked(batch)
        }
    }

    private fun startLocked(batch: Batch) {
        working = true
        _state.value = State.Verifying
        scope.launch { prepare(batch) }
    }

    private fun networkAllowed(batch: Batch): Boolean =
        modelNetworkAllowed(AppSettings.meteredConnection.value, batch.allowMetered)

    private fun waitingState(): State = if (AppSettings.meteredConnection.value == true) {
        State.AwaitingMeteredConsent
    } else {
        State.WaitingForNetwork
    }

    private fun networkChanged() {
        val cancel: Call?
        synchronized(lock) {
            val batch = pending ?: return
            cancel = activeCall?.takeIf { !networkAllowed(batch) }
            if (cancel != null) batch.interrupted = true
            if (!working && batch.downloadRequested) {
                if (networkAllowed(batch)) startLocked(batch) else _state.value = waitingState()
            }
        }
        // Interrupt a blocked socket read immediately, not just at the next progress update.
        cancel?.cancel()
    }

    private fun prepare(batch: Batch) {
        var resumeWhenAuthorized = false
        try {
            for (model in ALL) {
                if (synchronized(lock) { trustedLocked(model) } != null) continue
                for (file in copies(model)) {
                    val before = modelFileIdentity(file) ?: continue
                    if (before.bytes != model.bytes || synchronized(lock) { rejected[file.absolutePath] == before }) continue
                    val identity = verifiedModelIdentity(file, model.bytes, model.sha256)
                    if (identity == null) {
                        if (modelFileIdentity(file) == before) synchronized(lock) { rejected[file.absolutePath] = before }
                        continue
                    }
                    synchronized(lock) {
                        rejected.remove(file.absolutePath)
                        verified[model.fileName] = VerifiedFile(file, identity)
                        verifiedRevision++
                    }
                    break
                }
            }
            synchronized(lock) {
                observeLocked()
                if (allTrustedLocked()) {
                    readyLocked()
                    return
                }
                if (!batch.downloadRequested) {
                    pending = null
                    _state.value = State.Missing
                    return
                }
            }
            ensureNetwork(batch)
            val missing = synchronized(lock) { ALL.filter { trustedLocked(it) == null } }
            val total = missing.sumOf { it.bytes }.toFloat()
            var done = 0L
            for (model in missing) {
                ensureNetwork(batch)
                _state.value = State.Downloading((done / total).coerceIn(0f, 1f))
                fetch(model, batch) { read ->
                    _state.value = State.Downloading(((done + read) / total).coerceIn(0f, 1f))
                }
                done += model.bytes
            }
            synchronized(lock) {
                observeLocked()
                if (!allTrustedLocked()) throw IOException("A model changed before preparation completed")
                readyLocked()
            }
            Log.i(TAG, "All three Automix models verified")
        } catch (failure: Exception) {
            synchronized(lock) {
                observeLocked()
                if (failure is NetworkWait || batch.interrupted || !networkAllowed(batch)) {
                    _state.value = (failure as? NetworkWait)?.waitingState ?: waitingState()
                    // A quick offline/metered -> unmetered change may arrive while the
                    // canceled call is unwinding. Resume that SAME batch once, off IO.
                    resumeWhenAuthorized = batch.interrupted ||
                        (failure as? NetworkWait)?.policyBlocked == true || !networkAllowed(batch)
                    batch.interrupted = false
                } else {
                    failedAt = SystemClock.elapsedRealtime()
                    pending = null
                    _state.value = State.Failed
                    Log.w(TAG, "Automix model preparation failed", failure)
                }
            }
        } finally {
            synchronized(lock) {
                activeCall = null
                working = false
                if (resumeWhenAuthorized && pending === batch && networkAllowed(batch)) startLocked(batch)
            }
        }
    }

    private fun ensureNetwork(batch: Batch) {
        if (!networkAllowed(batch)) throw NetworkWait(waitingState(), policyBlocked = true)
    }

    private fun fetch(model: Model, batch: Batch, progress: (Long) -> Unit) {
        ensureNetwork(batch)
        val manager = application?.getSystemService(ConnectivityManager::class.java)
            ?: throw NetworkWait(State.WaitingForNetwork)
        val network = manager.activeNetwork ?: throw NetworkWait(State.WaitingForNetwork)
        val capabilities = manager.getNetworkCapabilities(network)
            ?: throw NetworkWait(State.WaitingForNetwork)
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            throw NetworkWait(State.WaitingForNetwork)
        }
        if (!batch.allowMetered && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            throw NetworkWait(State.AwaitingMeteredConsent)
        }
        // Bind DNS and sockets to the checked network; OkHttp must not reconnect a
        // Wi-Fi transfer over mobile data before the metering callback can cancel it.
        val client = Http.client.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(Dns { host -> network.getAllByName(host).toList() })
            .retryOnConnectionFailure(false)
            .build()
        val target = copies(model).firstOrNull() ?: throw IOException("Model directory unavailable")
        val part = File(target.parentFile, target.name + ".part")
        val call = client.newCall(Request.Builder().url(model.url).build())
        synchronized(lock) { activeCall = call }
        try {
            ensureNetwork(batch)
            if (part.exists() && !part.delete()) throw IOException("Cannot replace ${part.name}")
            Log.i(TAG, "Fetching ${model.url}")
            var read = 0L
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${model.fileName}")
                val body = response.body ?: throw IOException("Empty body for ${model.fileName}")
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastReport = 0L
                        while (true) {
                            ensureNetwork(batch)
                            val n = input.read(buffer)
                            if (n < 0) break
                            ensureNetwork(batch)
                            read += n
                            if (read > model.bytes) throw IOException("${model.fileName} is larger than expected")
                            output.write(buffer, 0, n)
                            if (read - lastReport >= 256 * 1024) {
                                lastReport = read
                                progress(read)
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
            if (read != model.bytes) throw IOException("${model.fileName} is $read bytes, expected ${model.bytes}")
            _state.value = State.Verifying
            val sourceIdentity = verifiedModelIdentity(part, model.bytes, model.sha256)
                ?: throw IOException("${model.fileName} failed SHA-256 verification")
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            val installedIdentity = modelFileIdentity(target) ?: throw IOException("Cannot stat ${model.fileName}")
            if (installedIdentity.device != sourceIdentity.device || installedIdentity.inode != sourceIdentity.inode ||
                installedIdentity.bytes != sourceIdentity.bytes || installedIdentity.modified != sourceIdentity.modified
            ) throw IOException("${model.fileName} changed during installation")
            synchronized(lock) {
                verified[model.fileName] = VerifiedFile(target, installedIdentity)
                verifiedRevision++
            }
            progress(model.bytes)
        } finally {
            synchronized(lock) { if (activeCall === call) activeCall = null }
            part.delete()
        }
    }
}
