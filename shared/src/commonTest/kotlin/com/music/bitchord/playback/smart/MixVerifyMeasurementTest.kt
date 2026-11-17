package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MixVerifyMeasurementTest {
    @Test
    fun nonFiniteLegacyInputsNeverPass() {
        assertFalse(MixVerify.verify(Double.NaN, .5f, 0.0, 0).ok)
        assertFalse(MixVerify.verify(0.0, Float.NaN, 0.0, 0).ok)
        assertFalse(MixVerify.verify(0.0, .5f, Double.POSITIVE_INFINITY, 0).ok)
        assertFalse(MixVerify.verify(Double.NEGATIVE_INFINITY, .5f, 0.0, 0).ok)
    }

    @Test
    fun partialAndNullMeasurementsAreReportedUnmeasuredAndKnownViolationsStillFail() {
        val missing = MixVerify.verifyMeasured(null, .5f, null, 0)
        assertFalse(missing.ok)
        assertFalse(missing.lufsMeasured)
        assertTrue(missing.samplePeakMeasured)
        assertFalse(missing.phaseMeasured)
        assertTrue(missing.reasons.contains("lufs-unmeasured"))
        assertTrue(missing.reasons.contains("phase-unmeasured"))

        val partialViolation = MixVerify.verifyMeasured(null, .95f, Double.NaN, 1)
        assertFalse(partialViolation.ok)
        assertTrue(partialViolation.reasons.any { it.startsWith("peak ") })
        assertTrue(partialViolation.reasons.contains("exit-gates 1"))
        assertTrue(partialViolation.reasons.contains("lufs-unmeasured"))
        assertTrue(partialViolation.reasons.contains("phase-unmeasured"))
    }

    @Test
    fun measuredResultDistinguishesTruePeakAndRms() {
        val result = MixVerify.verifyMeasured(-1.0, .5f, 2.0, 0, truePeakLinear = .89f, rmsDeltaDb = 0.5)
        assertTrue(result.ok)
        assertTrue(result.samplePeakMeasured)
        assertTrue(result.truePeakMeasured)
        assertTrue(result.lufsMeasured)
        assertTrue(result.rmsMeasured)

        val truePeakFail = MixVerify.verifyMeasured(-1.0, .5f, 2.0, 0, truePeakLinear = .95f)
        assertFalse(truePeakFail.ok)
        assertTrue(truePeakFail.reasons.any { it.startsWith("true-peak ") })
    }
}
