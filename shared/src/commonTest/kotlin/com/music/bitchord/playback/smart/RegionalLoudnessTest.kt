package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RegionalLoudnessTest {
    @Test
    fun aLouderEntryIsTurnedDownAndTheGainStaysInsideTheClamp() {
        val rate = 48_000
        val n = rate * 2
        val quiet = tone(n, rate, 0.1)
        val loud = tone(n, rate, 0.2)
        val gain = assertNotNull(
            RegionalLoudness.matchGain(quiet, quiet, 0, n, loud, loud, 0, n, rate),
        )
        // djmix9: symmetric +-3 dB clamp (0.1->0.2 is -6 dB, so -3 dB applies).
        assertEquals(0.708, gain.toDouble(), 0.03)
        val huge = tone(n, rate, 0.8)
        val clamped = assertNotNull(
            RegionalLoudness.matchGain(quiet, quiet, 0, n, huge, huge, 0, n, rate),
        )
        assertTrue(clamped >= 0.70 && clamped <= 0.72, "was $clamped")
    }

    private fun tone(n: Int, rate: Int, amplitude: Double): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = (sin(2.0 * PI * 440.0 * i / rate) * amplitude).toFloat()
        return out
    }
}
