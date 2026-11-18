package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.smart.DeckBudget
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.TrackAnalysis
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One build's cancellation and wall budget, shared by its parent and both decoder workers. */
class LoopBuildControl(
    private val nowMs: () -> Long,
    @Volatile var deadlineMs: Long = DeckBudget.NO_DEADLINE_MS,
    maxBuildMs: Long = DeckBudget.LOOP_BUDGET_HORIZON_MS,
) {
    private val lock = Any()
    private val workers = mutableSetOf<Thread>()
    private val futures = ArrayList<Future<*>>(2)
    private val cappedAtNanos = System.nanoTime() + maxBuildMs.coerceAtLeast(1L) * 1_000_000L
    @Volatile var isCancelled = false
        private set
    @Volatile var failure: String? = null
        private set

    /** Actual worker retirement, not Future.isDone (cancel(true) may precede worker exit). */
    val isRetired: Boolean get() = synchronized(lock) { workers.isEmpty() }

    internal fun remainingMs(): Long {
        val realLeft = (cappedAtNanos - System.nanoTime()) / 1_000_000L
        val deadline = deadlineMs
        val wallLeft = if (deadline != DeckBudget.NO_DEADLINE_MS && deadline > 0L) {
            deadline - nowMs() - SPLICE_RESERVE_MS
        } else Long.MAX_VALUE
        return minOf(realLeft, wallLeft)
    }

    fun shouldStop(): Boolean = isCancelled || Thread.currentThread().isInterrupted || remainingMs() <= 0L

    internal fun fail(reason: String) = synchronized(lock) {
        if (failure == null) failure = reason
    }

    internal fun <T> worker(block: () -> T): T? {
        val thread = Thread.currentThread()
        synchronized(lock) {
            if (shouldStop()) return null
            workers.add(thread)
        }
        try {
            return block()
        } finally {
            synchronized(lock) { workers.remove(thread) }
        }
    }

    internal fun register(future: Future<*>) {
        val cancelled = synchronized(lock) { futures.add(future); isCancelled }
        if (cancelled) future.cancel(true)
    }

    fun cancel(reason: String = "cancelled") {
        val (tasks, threads) = synchronized(lock) {
            isCancelled = true
            if (failure == null) failure = reason
            futures.toList() to workers.toList()
        }
        tasks.forEach { it.cancel(true) }
        // Blocking model/decoder calls may ignore interruption. The controller must
        // inspect isRetired before submitting a replacement, not assume they exited.
        threads.filter { it !== Thread.currentThread() }.forEach { it.interrupt() }
    }

    companion object {
        const val SPLICE_RESERVE_MS = 400L
    }
}

/** Immutable build seam: tests substitute a builder, not the controller's scheduling policy. */
data class LoopBuildRequest(
    val plan: LoopMix.Plan,
    val outId: String,
    val inId: String,
    val sampleRate: Int,
    val inLevel: Float,
    val highQuality: Boolean,
    val script: PhraseScript?,
    val control: LoopBuildControl,
    val separateStem: ((LoopMix.Side, FloatArray, FloatArray, Int, Boolean) -> Pair<FloatArray, FloatArray>?)?,
    val separateVocalStem: ((LoopMix.Side, FloatArray, FloatArray, Int) -> Pair<FloatArray, FloatArray>?)?,
    val separateVocalPair: ((LoopMix.Side, FloatArray, FloatArray, Int) -> Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>?)?,
    val outAnalysis: TrackAnalysis?,
    val inAnalysis: TrackAnalysis?,
    val sungOut: List<LoopMix.Span>?,
    val sungIn: List<LoopMix.Span>?,
    val onDecodeStep: (LoopMix.Side, String) -> Unit,
    val decode: (LoopMix.Side, Double, Double) -> LoopRegion?,
) {
    fun build(): LoopMixSession? = LoopMixSession.build(
        plan, outId, inId, sampleRate, inLevel, highQuality, script,
        separateStem = separateStem, separateVocalStem = separateVocalStem,
        separateVocalPair = separateVocalPair, outAnalysis = outAnalysis, inAnalysis = inAnalysis,
        sungOut = sungOut, sungIn = sungIn, control = control, onDecodeStep = onDecodeStep, decode = decode,
    )
}

/** Physical requests use the tracked knots, not merely the nominal clock/cache bounds. */
internal fun loopPhysicalBounds(
    plan: LoopMix.Plan,
    sampleRate: Int,
    script: PhraseScript?,
    outAnalysis: TrackAnalysis?,
    inAnalysis: TrackAnalysis?,
): LoopMix.RegionBounds {
    val effective = plan.copy(incomingHoldSec = script?.incomingHoldSec ?: plan.incomingHoldSec)
    val clock = LoopMix.Clock(effective)
    val bounds = LoopMix.regionBounds(effective)
    val stop = clock.releaseAt + 0.05 + (if (effective.echoTail) effective.out.beatSec * 8 else 0.0) + effective.exitTailSeconds
    val tailRate = if (clock.beats >= 1) {
        val delta = clock.beatTimes[clock.beats] - clock.beatTimes[clock.beats - 1]
        (effective.out.beatSec / delta.coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
    } else 1.0
    val knots = LoopMixSession.buildDeckKnots(effective, clock, sampleRate, outAnalysis, inAnalysis,
        !effective.outLoops && stop > clock.releaseAt + 1e-6, stop, tailRate)
    val incoming = LoopMixSession.incomingGeometry(effective, clock, knots)
    return bounds.copy(inTo = maxOf(bounds.inTo,
        incoming.handoverTrack + bounds.inTo - clock.elementTrackTime, incoming.requiredEnd))
}

/** Bounded OUT/IN waits; interruption/cancellation/error never starts a second serial decode. */
internal fun decodeLoopRegions(
    executor: ExecutorService,
    control: LoopBuildControl,
    onStep: (LoopMix.Side, String) -> Unit,
    decodeOut: () -> LoopRegion?,
    decodeIn: () -> LoopRegion?,
): Pair<LoopRegion?, LoopRegion?>? {
    fun submit(side: LoopMix.Side, decode: () -> LoopRegion?): Future<LoopRegion?> {
        onStep(side, "queued")
        return executor.submit(Callable {
            control.worker {
                onStep(side, "worker-start")
                try {
                    if (control.shouldStop()) null else decode()
                } finally {
                    onStep(side, if (control.shouldStop()) "decode-cancelled" else "decode-finished")
                }
            }
        }).also(control::register)
    }
    fun await(future: Future<LoopRegion?>): LoopRegion? {
        while (!control.shouldStop()) {
            try {
                return future.get(control.remainingMs().coerceIn(1L, 100L), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Recheck the same total budget/cancellation, not a fresh timeout per deck.
            }
        }
        if (!control.isCancelled) control.fail(if (Thread.currentThread().isInterrupted) "interrupted" else "deadline")
        return null
    }
    try {
        if (control.shouldStop()) return null
        val out = submit(LoopMix.Side.OUT, decodeOut)
        val inn = submit(LoopMix.Side.IN, decodeIn)
        val result = await(out) to await(inn)
        if (result.first == null || result.second == null) control.cancel(control.failure ?: "decode-null")
        return result
    } catch (interrupted: InterruptedException) {
        control.cancel("interrupted")
        Thread.currentThread().interrupt()
        throw CancellationException("Loop build interrupted").apply { initCause(interrupted) }
    } catch (cancelled: CancellationException) {
        control.cancel()
        throw cancelled
    } catch (failed: ExecutionException) {
        control.cancel("decode-error:${failed.cause?.javaClass?.simpleName ?: failed.javaClass.simpleName}")
        return null
    } catch (failed: Exception) {
        control.cancel("decode-error:${failed.javaClass.simpleName}")
        return null
    }
}
