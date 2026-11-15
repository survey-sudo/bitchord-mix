package com.music.bitchord.playback.smart

/**
 * Scalars-only P2 adaptive weight tuner (thumbs up/down feedback -> weights).
 * Weights clamp to 0.6..1.5; defaults 1.0 keep all existing scores identical.
 */
object MixWeightTuner {
    @Volatile var vocalWeight: Double = 1.0
        private set
    @Volatile var keyWeight: Double = 1.0
        private set
    @Volatile var energyWeight: Double = 1.0
        private set
    @Volatile var tempoWeight: Double = 1.0
        private set
    private const val MIN = 0.6
    private const val MAX = 1.5
    private const val STEP = 0.05
    private fun clamp(v: Double) = v.coerceIn(MIN, MAX)
    fun recordFeedback(
        pairKey: String,
        liked: Boolean,
        vocalClash: Boolean,
        keyClash: Boolean,
        energyJump: Boolean,
        tempoStretch: Boolean,
    ) {
        if (pairKey.isBlank()) return
        val d = if (liked) STEP else -STEP
        if (vocalClash) vocalWeight = clamp(vocalWeight + d)
        if (keyClash) keyWeight = clamp(keyWeight + d)
        if (energyJump) energyWeight = clamp(energyWeight + d)
        if (tempoStretch) tempoWeight = clamp(tempoWeight + d)
    }
    fun snapshot() = Snapshot(vocalWeight, keyWeight, energyWeight, tempoWeight)
    fun reset() { vocalWeight = 1.0; keyWeight = 1.0; energyWeight = 1.0; tempoWeight = 1.0 }
    data class Snapshot(
        val vocalWeight: Double,
        val keyWeight: Double,
        val energyWeight: Double,
        val tempoWeight: Double,
    )
}
