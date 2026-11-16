package com.music.bitchord.playback.smart

/**
 * Pure stem-duck decision for the loop-mix renderer.
 *
 * The measured energy-weighted collision ([LoopMixDuck.decide]'s `clashE`)
 * decides where it was measured; the legacy boolean gate (`outHasVocal` /
 * `inHasEarlyVocal` / `fallbackTrigger`) is the fallback where it was not.
 * A duck decision taken from the boolean alone would re-litigate the veto
 * the planner already made with better data.
 */
object LoopMixDuck {

    const val TRIGGER = 0.12
    const val MIN_FLOOR = 0.15f
    const val SEVERE_INCOMING = 0.60
    const val DUCK_TARGET = 0.05

    data class Decision(
        val wantDuck: Boolean,
        val duckIncoming: Boolean,
        val outFloor: Float,
        val inFloor: Float,
        val clashE: Double?,
    )

    fun decide(
        clashE: Double?,
        outHasVocal: Boolean,
        inHasEarlyVocal: Boolean,
        fallbackTrigger: Boolean,
        trigger: Double = TRIGGER,
        minFloor: Float = MIN_FLOOR,
        severeIncoming: Double = SEVERE_INCOMING,
    ): Decision {
        val e = clashE?.takeIf { it.isFinite() }
        val wantDuck = if (e != null) e > trigger else (outHasVocal && inHasEarlyVocal) || fallbackTrigger
        val duckIncoming = if (!wantDuck) false else if (e != null) e > severeIncoming || inHasEarlyVocal else inHasEarlyVocal && outHasVocal
        val floor = if (wantDuck && e != null) {
            StemClash.duckGainFor(e, target = DUCK_TARGET, floor = minFloor.toDouble()).toFloat()
        } else {
            minFloor
        }
        val outFloor = floor
        val inFloor = if (duckIncoming) floor else minFloor
        return Decision(
            wantDuck = wantDuck,
            duckIncoming = duckIncoming,
            outFloor = outFloor,
            inFloor = inFloor,
            clashE = e,
        )
    }
}
