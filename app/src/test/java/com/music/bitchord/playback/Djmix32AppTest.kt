package com.music.bitchord.playback

import com.music.bitchord.playback.loopmix.dryMinusVocal
import com.music.bitchord.playback.smart.DeckBudget
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.wholeTrackExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * vc74 (djmix32): on-device analysis contention fixes.
 *
 * - whole-track passes share one pipeline thread (vc73: 3 concurrent passes at
 *   6x mel/infer slowdown on a hot backgrounded phone).
 * - loop-deck stem separation is skipped while analysis is busy.
 * - stem separation is single-pass: instrumental == dry - vocal.
 * - the structure timeout has realistic headroom (20 s, not 5 s).
 */
class Djmix32AppTest {

    @Test
    fun `whole-track analysis executor is single-threaded`() {
        // Behavioral: on a JVM unit runner newSingleThreadExecutor returns a
        // delegating wrapper, not a ThreadPoolExecutor, so assert the observable
        // contract instead of internals: two tasks run serially on one thread.
        val executor = wholeTrackExecutor()
        try {
            val first = executor.submit { Thread.currentThread().name }
            val second = executor.submit { Thread.currentThread().name }
            val firstThread = first.get(10, TimeUnit.SECONDS)
            val secondThread = second.get(10, TimeUnit.SECONDS)
            assertEquals(firstThread, secondThread)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `stem skip gate true when analysis busy`() {
        val now = 1_000_000L
        // Far from the splice with no deadline: only the busy gate can skip,
        // and only at arm time (vc77: a prime retains stem separation).
        assertTrue(
            shouldSkipStemAtLaunch(
                remainingToSpliceMs = 30_000L,
                deadlineMs = DeckBudget.NO_DEADLINE_MS,
                nowMs = now,
                analysisBusy = true,
                armWhenReady = true,
            ),
        )
        assertFalse(
            shouldSkipStemAtLaunch(
                remainingToSpliceMs = 30_000L,
                deadlineMs = DeckBudget.NO_DEADLINE_MS,
                nowMs = now,
                analysisBusy = false,
                armWhenReady = true,
            ),
        )
        // Near the splice, skip even when idle.
        assertTrue(
            shouldSkipStemAtLaunch(
                remainingToSpliceMs = 2_000L,
                deadlineMs = DeckBudget.NO_DEADLINE_MS,
                nowMs = now,
                analysisBusy = false,
                armWhenReady = true,
            ),
        )
    }

    @Test
    fun `prime build retains stems while analysis busy`() {
        val now = 1_000_000L
        // vc77 regression: a prime (armWhenReady=false) with 20-45 s of lead
        // must not permanently null stemFn/vocalFn/pairFn just because a
        // whole-track pass is in flight.
        assertFalse(
            shouldSkipStemAtLaunch(
                remainingToSpliceMs = 30_000L,
                deadlineMs = DeckBudget.NO_DEADLINE_MS,
                nowMs = now,
                analysisBusy = true,
                armWhenReady = false,
            ),
        )
        // The same busy state at arm time still skips.
        assertTrue(
            shouldSkipStemAtLaunch(
                remainingToSpliceMs = 30_000L,
                deadlineMs = DeckBudget.NO_DEADLINE_MS,
                nowMs = now,
                analysisBusy = true,
                armWhenReady = true,
            ),
        )
        // Prime deadline protection remains: near the splice, skip even
        // when the prime was allowed to retain stems at launch.
        assertTrue(
            shouldSkipStemAtLaunch(
                remainingToSpliceMs = 2_000L,
                deadlineMs = DeckBudget.NO_DEADLINE_MS,
                nowMs = now,
                analysisBusy = false,
                armWhenReady = false,
            ),
        )
        assertTrue(
            shouldSkipStemAtLaunch(
                remainingToSpliceMs = 30_000L,
                deadlineMs = now + 1_000L,
                nowMs = now,
                analysisBusy = false,
                armWhenReady = false,
            ),
        )
    }

    @Test
    fun `single-pass stem arithmetic is dry minus vocal`() {
        val dry = floatArrayOf(0.5f, -0.25f, 1.0f, 0.0f, -0.75f)
        val vocal = floatArrayOf(0.2f, -0.1f, 0.9f, 0.0f, -0.3f)
        val inst = dryMinusVocal(dry, vocal)
        assertNotNull(inst)
        assertEquals(dry.size, inst!!.size)
        for (i in dry.indices) {
            assertEquals(dry[i] - vocal[i], inst[i], 0.0f)
            // Sample-exact reconstruction: vocal + instrumental == dry.
            assertTrue(abs(vocal[i] + inst[i] - dry[i]) == 0.0f)
        }

        // Short vocal stem: not sample-aligned, null (caller keeps the dry region).
        assertNull(dryMinusVocal(dry, vocal.copyOf(3)))
        // Long vocal stem: not sample-aligned, null.
        assertNull(dryMinusVocal(dry, vocal + floatArrayOf(0.1f, 0.2f)))
        // Empty dry with empty vocal is aligned and yields empty.
        val empty = dryMinusVocal(floatArrayOf(), floatArrayOf())
        assertNotNull(empty)
        assertEquals(0, empty!!.size)
    }

    @Test
    fun `structure timeout has realistic headroom`() {
        assertEquals(20_000L, PhraseConfig.STRUCT_TIMEOUT_MS)
    }
}
