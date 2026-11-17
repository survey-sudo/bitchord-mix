package com.music.bitchord.playback.smart

/**
 * Post-plan dry-run verification (opensonic-style): scalars only.
 * Checks loudness delta, peak ceiling, phase error and exit gates.
 */
object MixVerify {
    const val MAX_LUFS_DELTA_DB = 3.0
    const val MAX_PEAK_LINEAR = 0.896f
    const val MAX_PHASE_ERROR_MS = 25.0
    data class VerifyResult(
        val ok: Boolean,
        val reasons: List<String>,
        val lufsMeasured: Boolean = true,
        val samplePeakMeasured: Boolean = true,
        val truePeakMeasured: Boolean = false,
        val phaseMeasured: Boolean = true,
        val rmsMeasured: Boolean = false,
    )
    fun verify(
        lufsDeltaDb: Double,
        peakLinear: Float,
        phaseErrorMs: Double,
        exitGateViolations: Int,
    ): VerifyResult {
        val reasons = ArrayList<String>()
        if (!lufsDeltaDb.isFinite()) reasons.add("lufs-unmeasured")
        if (!peakLinear.isFinite()) reasons.add("peak-unmeasured")
        if (!phaseErrorMs.isFinite()) reasons.add("phase-unmeasured")
        if (kotlin.math.abs(lufsDeltaDb) > MAX_LUFS_DELTA_DB) reasons.add("lufs-delta " + lufsDeltaDb)
        if (peakLinear > MAX_PEAK_LINEAR) reasons.add("peak " + peakLinear)
        if (kotlin.math.abs(phaseErrorMs) > MAX_PHASE_ERROR_MS) reasons.add("phase " + phaseErrorMs)
        if (exitGateViolations != 0) reasons.add("exit-gates " + exitGateViolations)
        return VerifyResult(
            ok = reasons.isEmpty(), reasons = reasons,
            lufsMeasured = lufsDeltaDb.isFinite(), samplePeakMeasured = peakLinear.isFinite(),
            phaseMeasured = phaseErrorMs.isFinite(),
        )
    }

    fun verifyMeasured(
        lufsDeltaDb: Double?,
        peakLinear: Float?,
        phaseErrorMs: Double?,
        exitGateViolations: Int,
        truePeakLinear: Float? = null,
        rmsDeltaDb: Double? = null,
    ): VerifyResult {
        val reasons = ArrayList<String>()
        val lufsOk = lufsDeltaDb?.isFinite() == true
        val sampleOk = peakLinear?.isFinite() == true
        val trueOk = truePeakLinear?.isFinite() == true
        val phaseOk = phaseErrorMs?.isFinite() == true
        val rmsOk = rmsDeltaDb?.isFinite() == true
        if (!lufsOk) reasons.add("lufs-unmeasured")
        if (!sampleOk) reasons.add("peak-unmeasured")
        if (!phaseOk) reasons.add("phase-unmeasured")
        if (truePeakLinear != null && !trueOk) reasons.add("true-peak-unmeasured")
        if (lufsOk && kotlin.math.abs(lufsDeltaDb!!) > MAX_LUFS_DELTA_DB) reasons.add("lufs-delta $lufsDeltaDb")
        if (sampleOk && peakLinear!! > MAX_PEAK_LINEAR) reasons.add("peak $peakLinear")
        if (trueOk && truePeakLinear!! > MAX_PEAK_LINEAR) reasons.add("true-peak $truePeakLinear")
        if (phaseOk && kotlin.math.abs(phaseErrorMs!!) > MAX_PHASE_ERROR_MS) reasons.add("phase $phaseErrorMs")
        if (rmsDeltaDb != null && !rmsOk) reasons.add("rms-unmeasured")
        if (exitGateViolations != 0) reasons.add("exit-gates $exitGateViolations")
        return VerifyResult(
            ok = reasons.isEmpty(), reasons = reasons,
            lufsMeasured = lufsOk, samplePeakMeasured = sampleOk,
            truePeakMeasured = trueOk, phaseMeasured = phaseOk, rmsMeasured = rmsOk,
        )
    }

}
