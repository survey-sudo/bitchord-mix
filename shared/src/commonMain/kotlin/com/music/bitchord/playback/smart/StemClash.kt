package com.music.bitchord.playback.smart

/**
 * Vocal-clash measurement that can tell a quiet verse from a loud chorus.
 *
 * The tree has always decided clash by counting frames above
 * [VOCAL_ACTIVE_THRESHOLD] — a boolean vote in which a mask value of 0.30 and a
 * mask value of 0.90 count the same. Measured against the native calibration
 * harness (native/analyzer/window_calibration.cpp), that vote reports a clash
 * fraction of 1.000 for both a quiet verse under a loud bed and a loud chorus,
 * while their audible weight differs by an order of magnitude: 0.090 versus
 * 0.810 weighted.
 *
 * Two things live here:
 *
 *  1. [WienerGain] — the transfer function the separator actually realises,
 *     read back out of the shipped native code rather than out of a comment.
 *     A mask value of r does NOT attenuate by r; it keeps r^2/(r^2+(1-r)^2) of
 *     the energy. Every threshold in the tree sits far below the 50% point,
 *     which is the single most important thing to know before retuning any of
 *     them: at the decision boundary the separator removes almost nothing.
 *
 *  2. [weightedClash] and [StemClash.rank] — the replacement, so ranking can be
 *     driven by how much vocal actually collides rather than by whether a
 *     threshold was crossed.
 *
 * Nothing here runs on an audio thread and nothing here allocates per sample:
 * these are called once per candidate overlap during planning.
 */
object WienerGain {

    /**
     * Fraction of energy a bin keeps when the vocal mask value there is [mask].
     *
     * The separator applies `w = r^2 / (r^2 + (1-r)^2 + 1e-8)`. The epsilon is
     * carried here too so this function and the native code agree at the ends
     * rather than only in the middle.
     */
    fun energyAt(mask: Double): Double {
        if (!mask.isFinite()) return 0.0
        val r = mask.coerceIn(0.0, 1.0)
        val rr = r * r
        val cc = (1.0 - r) * (1.0 - r)
        return rr / (rr + cc + 1e-8)
    }

    /**
     * The mask value at which half the energy is kept.
     *
     * Worth naming because it is the natural place to put a threshold that
     * means "this is singing", and because every current threshold is well
     * below it.
     */
    const val HALF_ENERGY_MASK: Double = 0.5
}

/**
 * One instant of a proposed overlap, in the planner's own terms.
 *
 * [energyOut] is the outgoing track's energy at this instant, and it is the
 * weight: a clash that lands on a loud outgoing bar is audible, and the same
 * presence values over a quiet bar are not.
 */
data class ClashFrame(
    val presenceOut: Double,
    val presenceIn: Double,
    val energyOut: Double,
)

/**
 * Energy-weighted mean simultaneous vocal over an overlap, in 0..1.
 *
 * Null when there is nothing to weigh — no frames, or a programme whose energy
 * is zero throughout. Null means "not measured", which callers must treat as
 * unknown rather than as a clean pair; see [StemClash.rank].
 *
 * The product `presenceOut * presenceIn` is what makes a solo vocal carry no
 * clash weight: one side singing while the other is instrumental is a normal,
 * desirable overlap, not a collision.
 */
fun weightedClash(frames: List<ClashFrame>): Double? {
    if (frames.isEmpty()) return null
    var numerator = 0.0
    var denominator = 0.0
    for (frame in frames) {
        val energy = frame.energyOut
        if (!energy.isFinite() || energy <= 0.0) continue
        numerator += energy * clamp01(frame.presenceOut) * clamp01(frame.presenceIn)
        denominator += energy
    }
    if (denominator <= 0.0) return null
    return (numerator / denominator).coerceIn(0.0, 1.0)
}

/**
 * The boolean vote this replaces, kept only so tests can demonstrate what it
 * costs and so a caller that genuinely wants a count can ask for one.
 * Planning should prefer [weightedClash].
 */
fun booleanClash(frames: List<ClashFrame>, threshold: Double = VOCAL_ACTIVE_THRESHOLD): Double {
    if (frames.isEmpty()) return 0.0
    val both = frames.count {
        it.presenceOut >= threshold && it.presenceIn >= threshold
    }
    return both.toDouble() / frames.size
}

private fun clamp01(value: Double): Double =
    if (!value.isFinite()) 0.0 else value.coerceIn(0.0, 1.0)

/**
 * Candidate ranking with vocal clash folded in.
 *
 * [BeatFlow.rankScore] splits confidence 0.82 against beat flow 0.18, which is
 * Orchard's weighting for coarse frame measures. Vocal evidence is a third kind
 * of measurement and it gets its own term, funded out of confidence rather than
 * out of flow — beat flow keeps the weight its author chose, and a confident
 * beat grid does not get to outvote a vocal collision:
 *
 *     rank = 0.62 * confidence + 0.18 * flow + 0.20 * (1 - meanClash)
 *
 * The three weights sum to one, so a candidate that is perfect on every axis
 * scores exactly 1.0.
 */
object StemClash {

    const val CONFIDENCE_WEIGHT: Double = 0.62
    const val FLOW_WEIGHT: Double = 0.18
    const val CLASH_WEIGHT: Double = 0.20

    /** Value used when [meanClash] was never measured. */
    private const val UNMEASURED_CLASH: Double = 0.5

    /**
     * Ranking score in 0..1.
     *
     * A null [meanClash] is treated as the midpoint, never as zero. Reading
     * "not measured" as "no clash" would promote unmeasured pairs over pairs
     * that were measured and found clean, which is the wrong direction: the
     * unmeasured pair is a risk, not a bargain.
     */
    fun rank(confidence: Double, flow: Double, meanClash: Double?): Double {
        val safeConfidence = if (confidence.isFinite()) confidence.coerceIn(0.0, 1.0) else 0.0
        val safeFlow = if (flow.isFinite()) flow.coerceIn(0.0, 1.0) else 0.5
        val safeClash = if (meanClash == null || !meanClash.isFinite()) {
            UNMEASURED_CLASH
        } else {
            meanClash.coerceIn(0.0, 1.0)
        }
        return CONFIDENCE_WEIGHT * safeConfidence +
            FLOW_WEIGHT * safeFlow +
            CLASH_WEIGHT * (1.0 - safeClash)
    }

    /**
     * Duck gain that reduces a measured clash to [target] while keeping the
     * outgoing vocal as loud as it can be.
     *
     * Because the separator's energy response is quadratic in the mask, getting
     * a real duck needs a mask well above the old threshold: a mask of 0.25
     * keeps 10% of the energy, a mask of 0.5 keeps 50%. This returns the gain
     * to apply, floored so the outgoing vocal never reaches silence — a fully
     * removed vocal under an instrument bed reads as karaoke, which is a
     * different and worse artefact than a quiet collision.
     *
     * The gain is `target / meanClash`, not a blend toward it: to bring a
     * measured clash down to a target the surviving fraction has to be exactly
     * that ratio. The floor overrides it when the ratio falls below it, in
     * which case the target is simply unreachable without muting — and muting
     * is the one outcome worth refusing.
     */
    fun duckGainFor(meanClash: Double, target: Double = 0.05, floor: Double = 0.08): Double {
        if (!meanClash.isFinite() || meanClash <= target) return 1.0
        val safeFloor = floor.coerceIn(0.0, 1.0)
        val safeTarget = target.coerceAtLeast(0.0)
        val ratio = safeTarget / meanClash
        return ratio.coerceIn(safeFloor, 1.0)
    }
}
