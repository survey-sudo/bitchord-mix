package com.music.bitchord.playback.djmix

import com.music.bitchord.playback.TransitionFilters
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.TransitionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** The exception-safe mask restoration is a behavior fix, separate from extraction. */
class FadeRideCurvesMaskTest {
    @Test
    fun incomingPassFailureRestoresBothMasksForTheNextCall() = verifyRecovery("incoming")

    @Test
    fun outgoingPassFailureRestoresBothMasksForTheNextCall() = verifyRecovery("outgoing")

    private fun verifyRecovery(failingSide: String) {
        val sink = RecordingFilters()
        val curves = FadeRideCurves(sink, sink)
        val spec = RideSpec(
            style = TransitionStyle.DJ_FILTER,
            filterVariant = FilterTransitionVariant.DECLINE,
            filterSweep = 1.0,
        )
        sink.failSide = failingSide
        try {
            // Incoming is evaluated first with outgoing masked; the second
            // pass evaluates outgoing with incoming masked.
            curves.rideFilters(spec, incomingAt = 0.2f, outgoingAt = 0.7f)
            fail("Expected the $failingSide filter pass to throw")
        } catch (actual: IllegalStateException) {
            assertEquals("$failingSide filter failed", actual.message)
        }
        sink.failSide = null
        sink.calls.clear()
        curves.rideFilters(spec, incomingAt = 0.45f, outgoingAt = 0.45f)

        val expected = RecordingFilters()
        FadeRideCurves(expected, expected).rideFilters(spec, incomingAt = 0.45f, outgoingAt = 0.45f)
        assertEquals(listOf("outgoing", "incoming", "incomingMid", "outgoingMid"), sink.calls.map { it.side })
        assertEquals(expected.calls, sink.calls)
    }

    private data class Call(val side: String, val first: Float, val second: Float = 0f)

    private class RecordingFilters : TransitionFilters {
        var failSide: String? = null
        val calls = mutableListOf<Call>()
        override fun incoming(lowPassHz: Float, highPassHz: Float) {
            record("incoming", lowPassHz, highPassHz)
        }
        override fun outgoing(lowPassHz: Float, highPassHz: Float) {
            record("outgoing", lowPassHz, highPassHz)
        }
        override fun incomingMidDb(gainDb: Float) {
            record("incomingMid", gainDb)
        }
        override fun outgoingMidDb(gainDb: Float) {
            record("outgoingMid", gainDb)
        }
        private fun record(side: String, first: Float, second: Float = 0f) {
            if (side == failSide) throw IllegalStateException("$side filter failed")
            calls += Call(side, first, second)
        }
    }
}
