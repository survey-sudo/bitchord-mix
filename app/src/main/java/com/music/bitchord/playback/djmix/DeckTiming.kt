package com.music.bitchord.playback.djmix

import com.music.bitchord.playback.smart.DeckBudget
import com.music.bitchord.playback.smart.LoopMix

/**
 * Wall-clock timings for the steps of a deck build (M9 C-7). Thread-safe: written
 * on the decode thread as slices of audio and the filter arrive, and
 * read on the playback thread; the lock is the whole protocol.
 */
internal class DeckTiming(private val nowMs: () -> Long) {
    private val lock = Any()
    private val spans = ArrayList<LoopMix.PrepSpan>()
    private var openStep: String? = null
    private var openStarted = 0L
    // djmix15: NO_DEADLINE (paused/none), never 0L. 0L would subtract as
    // the boot uptime (~374000 ms on device) in lateStepReason.
    var deadlineMs: Long = DeckBudget.NO_DEADLINE_MS

    fun begin(step: String) = synchronized(lock) {
        val now = nowMs()
        closeOpen(now)
        openStep = step
        openStarted = now
    }

    fun endOpen() = synchronized(lock) { closeOpen(nowMs()) }

    fun endOpenIf(step: String) = synchronized(lock) {
        if (openStep == step) closeOpen(nowMs())
    }

    fun addClosed(step: String, startedMs: Long, endedMs: Long, deadline: Long) = synchronized(lock) {
        spans += LoopMix.PrepSpan(step, startedMs, endedMs, deadline)
    }

    fun clear() = synchronized(lock) {
        spans.clear()
        openStep = null
        // djmix15: drop the prior build's wall deadline too, so a stale
        // nowMs - deadlineMs can never read as this build's lateness
        // (374320 ms over, growing 1:1 with wall time).
        deadlineMs = DeckBudget.NO_DEADLINE_MS
    }

    fun snapshot(now: Long): List<LoopMix.PrepSpan> = synchronized(lock) {
        val closed = spans.toList()
        val step = openStep ?: return closed
        closed + LoopMix.PrepSpan(step, openStarted, now, deadlineMs)
    }

    private fun closeOpen(now: Long) {
        val step = openStep ?: return
        spans += LoopMix.PrepSpan(step, openStarted, now, deadlineMs)
        openStep = null
    }
}
