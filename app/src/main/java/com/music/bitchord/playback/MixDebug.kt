package com.music.bitchord.playback

import java.util.concurrent.atomic.AtomicInteger

/**
 * Underrun and discontinuity counts for the Automix diagnostic line.
 *
 * The audio thread only increments. Nothing here allocates.
 */
object MixDebug {
    private val underruns = AtomicInteger()
    private val gaps = AtomicInteger()
    private val late = AtomicInteger()

    fun underrun() {
        underruns.incrementAndGet()
    }

    fun gap() {
        gaps.incrementAndGet()
    }

    /** A mix block whose render took longer than the audio it produced. */
    fun late() {
        late.incrementAndGet()
    }

    fun reset() {
        underruns.set(0)
        gaps.set(0)
        late.set(0)
    }

    /** u, g, l as logged on AutomixScript. The audio thread only increments these. */
    fun counts(): IntArray = intArrayOf(underruns.get(), gaps.get(), late.get())

    /**
     * [detail] always carries the counts, including zeros, so a clean handover
     * is visible when the Automix line is expanded. [headline] gains them only
     * once one of them has moved, so a quiet line stays as it was.
     */
    fun decorate(headline: String, detail: String): Pair<String, String> {
        val suffix = " · u${underruns.get()} g${gaps.get()} l${late.get()}"
        val head = if (underruns.get() == 0 && gaps.get() == 0 && late.get() == 0) {
            headline
        } else {
            headline + suffix
        }
        return head to (detail + suffix)
    }
}
