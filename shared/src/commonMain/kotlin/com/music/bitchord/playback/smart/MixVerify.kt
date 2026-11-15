package com.music.bitchord.playback.smart

/**
 * Post-plan dry-run verification (opensonic-style): scalars only.
 * Checks loudness delta, peak ceiling, phase error and exit gates.
 */
object MixVerify {
    const val MAX_LUFS_DELTA_DB = 3.0
    const val MAX_PEAK_LINEAR = 0.896f
    const val MAX_PHASE_ERROR_MS = 25.0
    data class VerifyResult(val ok: Boolean, val reasons: List<String>)
    fun verify(
        lufsDeltaDb: Double,
        peakLinear: Float,
        phaseErrorMs: Double,
        exitGateViolations: Int,
    ): VerifyResult {
        val reasons = ArrayList<String>()
        if (kotlin.math.abs(lufsDeltaDb) > MAX_LUFS_DELTA_DB) reasons.add("lufs-delta " + lufsDeltaDb)
        if (peakLinear > MAX_PEAK_LINEAR) reasons.add("peak " + peakLinear)
        if (kotlin.math.abs(phaseErrorMs) > MAX_PHASE_ERROR_MS) reasons.add("phase " + phaseErrorMs)
        if (exitGateViolations != 0) reasons.add("exit-gates " + exitGateViolations)
        return VerifyResult(reasons.isEmpty(), reasons)
    }

}
