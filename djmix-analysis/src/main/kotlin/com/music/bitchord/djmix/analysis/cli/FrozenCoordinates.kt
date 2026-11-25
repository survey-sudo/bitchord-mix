package com.music.bitchord.djmix.analysis.cli

import com.music.bitchord.djmix.core.contracts.DeckRenderSetupV1
import kotlin.math.floor

/** Read-only coordinate queries over supplied playback knots. This never creates render geometry. */
internal object FrozenCoordinates {
    fun unwrappedAt(deck: DeckRenderSetupV1, seconds: Double, sampleRate: Int): Double {
        val frame = seconds * sampleRate
        val fs = deck.knotFrames
        val ps = deck.knotPositions
        require(fs.size >= 2 && fs.size == ps.size && frame >= 0 && frame <= fs.last() + 1e-6) { "Frozen knots do not cover requested metadata time" }
        if (frame <= fs.first()) return ps.first()
        val i = fs.indexOfFirst { it >= frame }.let { if (it < 1) fs.lastIndex else it }
        return ps[i - 1] + (ps[i] - ps[i - 1]) * (frame - fs[i - 1]) / (fs[i] - fs[i - 1])
    }

    fun physical(deck: DeckRenderSetupV1, position: Double): Double {
        val length = deck.loopEndFrame - deck.loopStartFrame
        if (length <= 0L || position < deck.loopEndFrame) return position
        val release = deck.releaseAtFrame
        return if (release == null || position < release) {
            deck.loopStartFrame + (position - deck.loopStartFrame) % length
        } else deck.loopEndFrame + position - release
    }

    fun sourceSeconds(deck: DeckRenderSetupV1, seconds: Double, sampleRate: Int): Double =
        deck.regionStartSec + physical(deck, unwrappedAt(deck, seconds, sampleRate)) / sampleRate

    /** Conservative exact source interval of a monotone frozen read, including any crossed loop seam. */
    fun sourceBounds(deck: DeckRenderSetupV1, releaseSec: Double, sampleRate: Int): Pair<Long, Long> {
        val a = unwrappedAt(deck, 0.0, sampleRate)
        val b = unwrappedAt(deck, releaseSec, sampleRate)
        val release = deck.releaseAtFrame
        var low = minOf(physical(deck, a), physical(deck, b))
        var high = maxOf(physical(deck, a), physical(deck, b))
        if (deck.loopEndFrame > deck.loopStartFrame && b >= deck.loopEndFrame &&
            (release == null || a < release)) {
            low = minOf(low, deck.loopStartFrame.toDouble())
            high = maxOf(high, deck.loopEndFrame.toDouble() + 0.008 * sampleRate)
        }
        val origin = deck.regionStartSec * sampleRate
        return floor(origin + low).toLong() to kotlin.math.ceil(origin + high).toLong().coerceAtLeast(floor(origin + low).toLong() + 1)
    }
}
