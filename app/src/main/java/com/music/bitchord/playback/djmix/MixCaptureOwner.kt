package com.music.bitchord.playback.djmix

import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Detached audio provenance; endpoints are inclusive when frameWindowKnown, otherwise native sentinels. */
data class CapturedAudioMetadata(
    val planId: String,
    val generation: Long,
    val playerEpoch: Long,
    val snapshotId: Long,
    val sampleRate: Int,
    val channels: Int,
    val firstMixFrame: Long,
    val lastMixFrame: Long,
    val frameCount: Int,
    val totalWrittenFrames: Long,
    val freezeReason: String,
    val frameWindowKnown: Boolean,
)

internal data class CaptureIdentity(val planId: String, val generation: Long, val playerEpoch: Long) {
    companion object {
        fun from(context: RetainedMixContext): CaptureIdentity? = context.playerEpoch?.let {
            CaptureIdentity(context.planId, context.generation, it)
        }
    }
}

internal data class MixCaptureResult(
    val identity: CaptureIdentity,
    val status: String,
    val file: File? = null,
    val metadata: CapturedAudioMetadata? = null,
    val bytes: Long = 0L,
    val completedNanos: Long = System.nanoTime(),
) {
    companion object {
        fun unavailable(identity: CaptureIdentity, reason: String) = MixCaptureResult(identity, "unavailable:$reason")
    }
}

/**
 * Owned only by DjmixServices. Callback entry points do bounded bookkeeping, never native reads,
 * PCM allocation or file I/O. Each entry owns retirement until its worker has stopped using native
 * memory. Keeping a renderer reference alone would NOT prevent close() from retiring its handle.
 */
internal class MixCaptureOwner(
    private val directory: File,
    parentScope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val onResult: (MixCaptureResult) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerJob + dispatcher)
    private val entries = IdentityHashMap<LoopMixRenderer, Entry>()
    private val results = LinkedHashMap<CaptureIdentity, ResultState>()
    private val selected = HashMap<CaptureIdentity, Int>()
    private var selectedCount = 0
    private var retained = emptySet<CaptureIdentity>()
    private var newestAdmittedGeneration = -1L
    private val copyGate = Semaphore(1)
    private val maintenance = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var closed = false
    @Volatile private var storageReady = false
    val available: Boolean get() = !closed && storageReady && ownerJob.isActive

    /** Shared by the entry, cache, delivery and waiters, even after renderer retirement/eviction. */
    private class ResultState {
        // Acquire before the owner lock; serializes invalidation with callback check + invocation.
        val deliveryLock = Any()
        private val publicationEpoch = 0L
        private var epoch = publicationEpoch // owner lock; only advances once, never resets
        val completed = CompletableDeferred<Unit>()
        var outcome: MixCaptureResult? = null // owner lock
        var callbackActive = false // owner lock
        var pendingCallback: MixCaptureResult? = null // owner lock; one conflated delivery
        val invalidated: Boolean get() = epoch != publicationEpoch // owner lock

        fun resolve(result: MixCaptureResult): MixCaptureResult = // owner lock
            if (invalidated) result.copy(status = "unavailable:foreign_renderer", metadata = null) else result

        fun invalidate() { // delivery lock + owner lock
            epoch = publicationEpoch + 1L
            // Retain files in the bounded deletion ledger, but never expose them as available audio.
            outcome = outcome?.let(::resolve)
        }
    }

    private class Entry(val identity: CaptureIdentity, val renderer: LoopMixRenderer) {
        val requested = CompletableDeferred<String>()
        val retirement = CompletableDeferred<Unit>()
        val state = ResultState()
        val finished = AtomicBoolean(false)
        val admittedNanos = System.nanoTime()
        var retireRequested = false // owner lock
        var retireNotBeforeNanos = 0L // owner lock
    }

    init {
        scope.launch {
            // Old process-local contexts cannot survive a process restart. Only our own files are
            // collected; archives/bundles elsewhere are never touched. No directory I/O on setup.
            storageReady = runCatching {
                check(directory.isDirectory || directory.mkdirs())
                directory.listFiles()?.filter { it.name.startsWith("capture-") &&
                    (it.name.endsWith(".wav") || it.name.endsWith(".part")) }?.forEach {
                    check(it.delete()) { "capture cleanup failed" }
                }
                true
            }.getOrDefault(false)
            while (currentCoroutineContext().isActive) {
                withTimeoutOrNull(60_000L) { maintenance.receive() }
                prune()
            }
        }
    }

    /** Original session fields + executor's captured epoch; never machine/visual/build identity. */
    fun admit(session: LoopMixSession, playerEpoch: Long): Boolean {
        if (!session.captureConfigured || session.capturePlayerEpoch != playerEpoch || session.planId.isBlank() ||
            session.planId.length > 512 || session.preparationGeneration < 0L || playerEpoch < 0L) return false
        val identity = CaptureIdentity(session.planId, session.preparationGeneration, playerEpoch)
        val renderer = session.renderer
        var rejectedState: ResultState? = null
        val entry = synchronized(lock) {
            entries[renderer]?.let { return it.identity == identity && !it.state.invalidated }
            val conflict = entries.values.firstOrNull { it.identity == identity }
            val cached = results[identity]
            if (conflict != null || cached != null) {
                // Entry and cache share this token, including any completion already in flight.
                rejectedState = conflict?.state ?: checkNotNull(cached)
                return@synchronized null
            }
            if (!available || entries.size >= MAX_REGISTRATIONS || identity.generation <= newestAdmittedGeneration) return false
            val admitted = Entry(identity, renderer)
            if (!session.installNativeRetirementHook { retire(it, 0L) }) return false
            entries[renderer] = admitted
            newestAdmittedGeneration = identity.generation
            admitted
        }
        if (entry == null) {
            val state = checkNotNull(rejectedState)
            // Never wait for caller code while holding the owner lock. A callback already in
            // progress finishes before invalidation; every later callback rechecks its epoch.
            synchronized(state.deliveryLock) {
                synchronized(lock) { state.invalidate() }
            }
            state.completed.complete(Unit)
            deliver(state, MixCaptureResult.unavailable(identity, "foreign_renderer"))
            return false
        }
        deliver(entry.state, MixCaptureResult(identity, "pending"))
        // LAZY + completion handler covers cancellation even before the worker ever starts.
        val work = scope.launch(start = CoroutineStart.LAZY) { runEntry(entry) }
        work.invokeOnCompletion { cause -> finishEntry(entry, cause != null) }
        work.start()
        return true
    }

    /** First request wins. JNI request/poll/copy occur on our worker, not this caller. */
    fun request(renderer: LoopMixRenderer, reason: String) {
        synchronized(lock) { entries[renderer]?.requested?.complete(reason) }
    }

    /** Callable after executor.close/epoch replacement. True means we now owe native retirement. */
    fun retire(renderer: LoopMixRenderer, delayMs: Long): Boolean = synchronized(lock) {
        val entry = entries[renderer] ?: return false
        if (!entry.retireRequested) {
            entry.retireRequested = true
            entry.retireNotBeforeNanos = System.nanoTime() + delayMs.coerceIn(0L, 1_500L) * 1_000_000L
        }
        entry.requested.complete("retirement")
        entry.retirement.complete(Unit)
        true
    }

    fun updateRetained(identities: Set<CaptureIdentity>) {
        synchronized(lock) { retained = identities.toSet() }
        maintenance.trySend(Unit)
    }

    fun result(identity: CaptureIdentity): MixCaptureResult? = synchronized(lock) {
        results[identity]?.outcome ?: entries.values.firstOrNull { it.identity == identity }?.let {
            if (it.state.invalidated) MixCaptureResult.unavailable(identity, "foreign_renderer")
            else if (it.state.outcome == null) MixCaptureResult(identity, "pending")
            else MixCaptureResult.unavailable(identity, "result_evicted")
        }
    }

    /** Pin synchronously with the Flag action, before any wait or registry replacement can occur. */
    fun pinSelected(identity: CaptureIdentity): Boolean = synchronized(lock) {
        if (closed || selectedCount >= MAX_SELECTED) return false
        selectedCount++
        selected[identity] = (selected[identity] ?: 0) + 1
        true
    }

    fun unpinSelected(identity: CaptureIdentity) {
        synchronized(lock) {
            val count = selected[identity] ?: return
            if (count == 1) selected.remove(identity) else selected[identity] = count - 1
            selectedCount--
        }
        maintenance.trySend(Unit)
    }

    suspend fun awaitSelected(identity: CaptureIdentity): MixCaptureResult {
        // A PLANNED retained context can precede attach/admission. Rendezvous only on this identity.
        val registrationDeadline = System.nanoTime() + 1_000_000_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            val pending = synchronized(lock) {
                results[identity]?.outcome?.let { return it }
                entries.values.firstOrNull { it.identity == identity }?.also {
                    if (it.state.invalidated) return MixCaptureResult.unavailable(identity, "foreign_renderer")
                    if (it.state.outcome != null) return MixCaptureResult.unavailable(identity, "result_evicted")
                    it.requested.complete("flag")
                }
            }
            if (pending != null) {
                val completed = withTimeoutOrNull(CAPTURE_WORK_MS + FREEZE_WAIT_MS + 1_000L) {
                    pending.state.completed.await()
                    true
                } ?: false
                // A deferred is only a rendezvous: recheck the shared epoch after resumption.
                return synchronized(lock) {
                    when {
                        pending.state.invalidated -> MixCaptureResult.unavailable(identity, "foreign_renderer")
                        !completed -> MixCaptureResult.unavailable(identity, "flag_wait_timeout")
                        else -> pending.state.outcome ?: MixCaptureResult.unavailable(identity, "result_evicted")
                    }
                }
            }
            if (closed) return MixCaptureResult.unavailable(identity, "process_closed")
            if (System.nanoTime() >= registrationDeadline) return MixCaptureResult.unavailable(identity, "not_registered")
            delay(POLL_MS)
        }
    }

    private suspend fun runEntry(entry: Entry) {
        try {
            val reason = withTimeoutOrNull(REGISTRATION_MS) { entry.requested.await() }
            val outcome = if (reason == null) MixCaptureResult.unavailable(entry.identity, "registration_expired") else {
                try {
                    withTimeout(CAPTURE_WORK_MS) { capture(entry, reason) }
                } catch (_: TimeoutCancellationException) {
                    MixCaptureResult.unavailable(entry.identity, "capture_timeout")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: CaptureFailure) {
                    MixCaptureResult.unavailable(entry.identity, failure.reason)
                } catch (_: OutOfMemoryError) {
                    MixCaptureResult.unavailable(entry.identity, "allocation_failed")
                } catch (_: Exception) {
                    MixCaptureResult.unavailable(entry.identity, "disk_or_copy_failure")
                }
            }
            publish(entry, outcome)
            // Bound idle registrations too. After expiry, the session still owns its live renderer;
            // removal makes its hook fall back to immediate close (no copy remains in flight).
            val remainingMs = ((REGISTRATION_MS * 1_000_000L -
                (System.nanoTime() - entry.admittedNanos)) / 1_000_000L).coerceAtLeast(1L)
            withTimeoutOrNull(remainingMs) { entry.retirement.await() }
            while (true) {
                val remaining = synchronized(lock) {
                    if (closed) 0L else (entry.retireNotBeforeNanos - System.nanoTime()) / 1_000_000L
                }
                if (remaining <= 0L) break
                delay(remaining.coerceAtMost(1_500L))
            }
        } finally {
            finishEntry(entry, !currentCoroutineContext().isActive)
        }
    }

    private suspend fun capture(entry: Entry, reason: String): MixCaptureResult {
        val renderer = entry.renderer
        if (synchronized(lock) { entry.state.invalidated }) throw CaptureFailure("foreign_renderer")
        if (!renderer.isValid) throw CaptureFailure("closed_handle")
        renderer.requestCaptureFreeze()
        val frozen = withTimeoutOrNull(FREEZE_WAIT_MS) { pollFrozen(renderer) }
            ?: throw CaptureFailure("freeze_unacknowledged")
        validate(renderer, frozen)
        return copyGate.withPermit {
            currentCoroutineContext().ensureActive()
            if (!renderer.isValid) throw CaptureFailure("closed_handle")
            if (renderer.getCaptureSnapshotInfo() != frozen) throw CaptureFailure("snapshot_changed")
            val samples = FloatArray(frozen.frameCount * frozen.channels)
            // There is NO native offset API. Exactly one FULL read; smaller repeated reads would
            // repeat the prefix. Retirement remains owned by this worker across both JNI calls.
            if (renderer.readFrozenCapture(samples, frozen.frameCount) != frozen.frameCount)
                throw CaptureFailure("short_or_closed_read")
            if (renderer.getCaptureSnapshotInfo() != frozen) throw CaptureFailure("snapshot_changed")
            if (synchronized(lock) { entry.state.invalidated }) throw CaptureFailure("foreign_renderer")
            val id = entry.identity
            val metadata = CapturedAudioMetadata(id.planId, id.generation, id.playerEpoch,
                frozen.snapshotId, frozen.sampleRate, frozen.channels, frozen.firstMixFrame,
                frozen.lastMixFrame, frozen.frameCount, frozen.totalWrittenFrames, reason,
                frameWindowKnown = !(frozen.firstMixFrame == -1L && frozen.lastMixFrame == -1L))
            writeWave(entry, metadata, samples)
        }
    }

    private suspend fun pollFrozen(renderer: LoopMixRenderer): LoopMixRenderer.CaptureSnapshotInfo {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!renderer.isValid) throw CaptureFailure("closed_handle")
            val info = renderer.getCaptureSnapshotInfo()
            when (info.status) {
                LoopMixRenderer.CaptureReadStatus.FROZEN -> return info
                LoopMixRenderer.CaptureReadStatus.NO_DATA -> throw CaptureFailure("empty")
                LoopMixRenderer.CaptureReadStatus.UNAVAILABLE -> throw CaptureFailure("not_armed_or_closed")
                LoopMixRenderer.CaptureReadStatus.PENDING -> delay(POLL_MS)
            }
        }
    }

    private fun validate(renderer: LoopMixRenderer, info: LoopMixRenderer.CaptureSnapshotInfo) {
        val cap = (info.sampleRate.toLong() * 40L).coerceIn(48_000L, 192_000L * 40L)
        // The frozen primitive uses -1/-1 for an unknown mix-frame window, including negative
        // preroll origins. Preserve that fact; never fabricate timestamps from current clocks.
        val unknownWindow = info.firstMixFrame == -1L && info.lastMixFrame == -1L
        val validWindow = info.lastMixFrame >= info.firstMixFrame &&
            info.firstMixFrame <= Long.MAX_VALUE - info.frameCount &&
            info.lastMixFrame == info.firstMixFrame + info.frameCount - 1L
        if (info.snapshotId != 1L || info.channels != 2 || info.sampleRate != renderer.sampleRate ||
            info.sampleRate !in 8_000..192_000 || info.frameCount <= 0 ||
            info.frameCount.toLong() != minOf(info.totalWrittenFrames, cap) ||
            (!unknownWindow && !validWindow))
            throw CaptureFailure("invalid_snapshot_metadata")
    }

    private suspend fun writeWave(entry: Entry, metadata: CapturedAudioMetadata, samples: FloatArray): MixCaptureResult {
        val provenance = buildJsonObject {
            put("planId", metadata.planId); put("generation", metadata.generation); put("playerEpoch", metadata.playerEpoch)
            put("snapshotId", metadata.snapshotId); put("sampleRate", metadata.sampleRate); put("channels", metadata.channels)
            put("firstMixFrame", metadata.firstMixFrame); put("lastMixFrame", metadata.lastMixFrame)
            put("frameCount", metadata.frameCount); put("totalWrittenFrames", metadata.totalWrittenFrames)
            put("frameWindowKnown", metadata.frameWindowKnown)
            put("freezeReason", metadata.freezeReason); put("encoding", "IEEE_FLOAT32_LE")
        }.toString().toByteArray(Charsets.UTF_8)
        val dataBytes = samples.size.toLong() * 4L
        val paddedMetadataBytes = provenance.size + (provenance.size and 1)
        val totalBytes = 12L + 26L + 12L + 8L + paddedMetadataBytes + 8L + dataBytes
        if (!makeRoom(totalBytes)) throw CaptureFailure("storage_budget_or_unavailable")
        val stem = "capture-e${metadata.playerEpoch}-g${metadata.generation}-${UUID.randomUUID()}-s${metadata.snapshotId}"
        val pending = File(directory, "$stem.part")
        val complete = File(directory, "$stem.wav")
        var published = false
        try {
            FileOutputStream(pending).use { raw ->
                val output = BufferedOutputStream(raw, 64 * 1024)
                fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
                fun u32(value: Long) { for (shift in 0..24 step 8) output.write((value ushr shift).toInt() and 255) }
                fun u16(value: Int) { output.write(value and 255); output.write((value ushr 8) and 255) }
                ascii("RIFF"); u32(totalBytes - 8L); ascii("WAVE")
                ascii("fmt "); u32(18); u16(3); u16(metadata.channels) // IEEE float, not integer PCM
                u32(metadata.sampleRate.toLong()); u32(metadata.sampleRate.toLong() * metadata.channels * 4L)
                u16(metadata.channels * 4); u16(32); u16(0)
                ascii("fact"); u32(4); u32(metadata.frameCount.toLong())
                ascii("bcap"); u32(provenance.size.toLong()); output.write(provenance)
                if ((provenance.size and 1) != 0) output.write(0)
                ascii("data"); u32(dataBytes)
                val block = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in samples) {
                    if (!sample.isFinite()) throw CaptureFailure("non_finite_samples")
                    block.putInt(java.lang.Float.floatToRawIntBits(sample))
                    if (!block.hasRemaining()) {
                        currentCoroutineContext().ensureActive()
                        output.write(block.array(), 0, block.position()); block.clear()
                    }
                }
                if (block.position() > 0) output.write(block.array(), 0, block.position())
                output.flush()
                raw.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            if (pending.length() != totalBytes || !pending.renameTo(complete)) throw CaptureFailure("atomic_publication_failed")
            currentCoroutineContext().ensureActive()
            val result = MixCaptureResult(entry.identity, "available", complete.absoluteFile, metadata, totalBytes)
            publish(entry, result)
            published = true
            return result
        } finally {
            if (!published) {
                for (file in listOf(pending, complete)) {
                    if (!runCatching { !file.exists() || file.delete() }.getOrDefault(false)) storageReady = false
                }
            }
        }
    }

    private fun publish(entry: Entry, result: MixCaptureResult) {
        val state = entry.state
        val changed = synchronized(lock) {
            if (state.outcome != null) false else {
                state.outcome = state.resolve(result)
                results[entry.identity] = state
                // Files have their separate budget; keep small failure/status rendezvous bounded.
                while (results.size > MAX_RESULTS) {
                    val victim = results.values.mapNotNull { it.outcome }
                        .firstOrNull { it.file == null && !isProtected(it.identity) } ?: break
                    results.remove(victim.identity)
                }
                true
            }
        }
        if (changed) {
            state.completed.complete(Unit)
            deliver(state, result)
            maintenance.trySend(Unit)
        }
    }

    private fun deliver(state: ResultState, result: MixCaptureResult) {
        val drain = synchronized(lock) {
            state.pendingCallback = state.resolve(result)
            if (state.callbackActive) false else {
                state.callbackActive = true
                true
            }
        }
        if (!drain) return
        // Never hold the owner lock across caller code. The delivery gate makes epoch resolution
        // and invocation indivisible with invalidation, including a callback paused before entry.
        while (true) {
            synchronized(state.deliveryLock) {
                val actual = synchronized(lock) {
                    val pending = state.pendingCallback
                    state.pendingCallback = null
                    if (pending == null) state.callbackActive = false
                    pending?.let(state::resolve)
                } ?: return
                runCatching { onResult(actual) }
            }
        }
    }

    private fun finishEntry(entry: Entry, cancelled: Boolean) {
        if (!entry.finished.compareAndSet(false, true)) return
        publish(entry, MixCaptureResult.unavailable(entry.identity, if (closed) "process_closed" else "cancelled"))
        val retire = synchronized(lock) {
            entries.remove(entry.renderer)
            entry.retireRequested || closed || cancelled
        }
        if (retire) entry.renderer.close()
    }

    private fun isProtected(identity: CaptureIdentity) = identity in retained || identity in selected

    /** Only worker calls perform deletions. Pins/retained keys are checked atomically with eviction. */
    private fun makeRoom(extraBytes: Long = 0L): Boolean {
        if (!available) return false
        val deletes = ArrayList<File>()
        val allowed = synchronized(lock) {
            val now = System.nanoTime()
            val expired = results.values.mapNotNull { it.outcome }
                .filter { !isProtected(it.identity) && now - it.completedNanos >= RESULT_TTL_MS * 1_000_000L }
            for (result in expired) { results.remove(result.identity); result.file?.let(deletes::add) }
            while (true) {
                val files = results.values.mapNotNull { it.outcome }.filter { it.file != null }
                if (files.sumOf { it.bytes } + extraBytes <= MAX_FILE_BYTES && files.size + (if (extraBytes > 0L) 1 else 0) <= MAX_FILES) break
                val victim = files.firstOrNull { !isProtected(it.identity) } ?: return@synchronized false
                results.remove(victim.identity)
                victim.file?.let(deletes::add)
            }
            true
        }
        for (file in deletes) if (!runCatching { !file.exists() || file.delete() }.getOrDefault(false)) storageReady = false
        return allowed && available
    }

    private fun prune() { makeRoom() }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            storageReady = false
            for (entry in entries.values) {
                entry.retireRequested = true
                entry.retireNotBeforeNanos = 0L
            }
        }
        // A copying worker's finally runs after the synchronous JNI invocation has returned.
        // Completion handlers also retire entries cancelled before first execution.
        ownerJob.cancel()
    }

    private class CaptureFailure(val reason: String) : Exception(reason)

    companion object {
        private const val MAX_REGISTRATIONS = 4
        private const val MAX_SELECTED = 4
        private const val MAX_RESULTS = 16
        private const val MAX_FILES = 8
        private const val MAX_FILE_BYTES = 256L * 1024L * 1024L
        private const val RESULT_TTL_MS = 5L * 60_000L
        private const val REGISTRATION_MS = 10L * 60_000L
        private const val FREEZE_WAIT_MS = 2_000L
        private const val CAPTURE_WORK_MS = 10_000L
        private const val POLL_MS = 25L
    }
}
