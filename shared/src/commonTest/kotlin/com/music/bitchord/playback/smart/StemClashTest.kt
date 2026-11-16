package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Energy-weighted vocal clash, and the transfer function it sits on.
 *
 * The tree decided vocal clash by counting frames above
 * [VOCAL_ACTIVE_THRESHOLD] — a boolean vote in which a mask value of 0.30 and a
 * mask value of 0.90 count the same. The native calibration harness
 * (native/analyzer/window_calibration.cpp) shows that vote reporting a clash
 * fraction of 1.000 for both a quiet verse and a loud chorus, while their
 * audible weight differs by an order of magnitude. This pins the weighted
 * replacement and the constants that feed it.
 *
 * The numbers are not arbitrary. The Wiener gain curve the shipped separator
 * realises, measured through the shipped code, is w(r) = r^2/(r^2 + (1-r)^2),
 * so a mask value of r does NOT attenuate by r. Pinning it here means a change
 * to either side — the native separator or these thresholds — has to be made
 * deliberately on both, instead of drifting apart silently.
 */
class StemClashTest {

    // ---- the measured transfer function ------------------------------------

    /**
     * Every decision threshold in the tree sits far below its own midpoint.
     * That is the finding this test exists to stop anyone forgetting: at the
     * boundary the separator keeps 10% of the energy, not 25%.
     */
    @Test
    fun gainCurveMatchesNativeCalibration() {
        assertEquals(0.0122, WienerGain.energyAt(0.10), 0.0001, "r=0.10")
        assertEquals(0.1000, WienerGain.energyAt(0.25), 0.0001, "VOCAL_ACTIVE_THRESHOLD")
        assertEquals(0.2248, WienerGain.energyAt(0.35), 0.0002, "VOICE_FLOOR")
        assertEquals(0.5000, WienerGain.energyAt(0.50), 0.0001, "midpoint")
        assertEquals(0.7269, WienerGain.energyAt(0.62), 0.0002, "vocalProbability gate")
        assertEquals(0.9412, WienerGain.energyAt(0.80), 0.0001, "r=0.80")
    }

    @Test
    fun gainCurveIsMonotonicAndFixedAtEnds() {
        var previous = -1.0
        var r = 0.0
        while (r <= 1.0) {
            val energy = WienerGain.energyAt(r)
            assertTrue(energy >= previous, "gain must not fall as the mask rises (r=$r)")
            previous = energy
            r += 0.05
        }
        assertEquals(0.0, WienerGain.energyAt(0.0), 1e-9)
        // The epsilon the native code carries is reproduced here, so a mask of
        // 1.0 lands a hair under 1.0 rather than exactly on it.
        assertEquals(1.0, WienerGain.energyAt(1.0), 1e-6)
        assertTrue(WienerGain.energyAt(1.0) <= 1.0, "a gain above 1.0 would be amplification")
    }

    /**
     * "A threshold is not a gain", as one assertion. If this ever fails because
     * someone rescaled the mask, that is a real decision and it should show up
     * here loudly rather than as a quiet change in mix quality.
     */
    @Test
    fun noThresholdActsLikeAGain() {
        assertTrue(
            WienerGain.energyAt(VOCAL_ACTIVE_THRESHOLD) < 0.15,
            "VOCAL_ACTIVE_THRESHOLD keeps well under 15% of energy — it is a " +
                "detector, not an attenuator; do not use it as a gain",
        )
        assertTrue(
            WienerGain.energyAt(WienerGain.HALF_ENERGY_MASK) > 0.49,
            "the named half-energy point really is the halfway point",
        )
    }

    @Test
    fun nonFiniteMaskIsNotAMeasurement() {
        // A non-finite mask is not a reading, so it keeps nothing. That matches
        // how weightedClash treats non-finite presence: it is refused rather
        // than guessed at in either direction. Claiming 1.0 for +inf would let
        // a corrupted mask assert that a bin is fully vocal, which is exactly
        // the kind of fabricated evidence this file exists to stop.
        assertEquals(0.0, WienerGain.energyAt(Double.NaN), 1e-9)
        assertEquals(0.0, WienerGain.energyAt(Double.NEGATIVE_INFINITY), 1e-9)
        assertEquals(0.0, WienerGain.energyAt(Double.POSITIVE_INFINITY), 1e-9)
    }

    // ---- the weighted clash ------------------------------------------------

    /**
     * The case that motivated the change. Same frame count, same boolean
     * verdict, wildly different audible weight.
     */
    @Test
    fun quietVerseAndLoudChorusAreSeparated() {
        val quiet = List(12) { ClashFrame(0.30, 0.30, 0.05) }
        val loud = List(12) { ClashFrame(0.90, 0.90, 0.95) }

        assertEquals(1.0, booleanClash(quiet), 1e-9, "boolean vote calls the quiet verse a clash")
        assertEquals(1.0, booleanClash(loud), 1e-9, "and the loud chorus identically")

        val quietWeighted = weightedClash(quiet)!!
        val loudWeighted = weightedClash(loud)!!
        assertEquals(0.09, quietWeighted, 0.001, "quiet verse carries little clash weight")
        assertEquals(0.81, loudWeighted, 0.001, "loud chorus carries a lot")
        assertTrue(
            loudWeighted > quietWeighted * 5.0,
            "the weighted measure must separate these by a wide margin",
        )
    }

    /**
     * A short loud peak inside a quiet programme: the boolean vote calls it a
     * total clash, which is how a single chorus stab used to veto a whole
     * transition on a programme that was otherwise clear.
     */
    @Test
    fun shortPeakInsideQuietProgrammeIsNotATotalClash() {
        val mixed = List(40) { ClashFrame(0.30, 0.30, 0.02) } +
            List(4) { ClashFrame(0.95, 0.95, 1.00) }
        assertTrue(booleanClash(mixed) > 0.99, "boolean vote over-reports")
        val weighted = weightedClash(mixed)!!
        assertTrue(weighted < 0.80, "weighted mean discounts the peak (got $weighted)")
    }

    @Test
    fun instrumentalOverlapCarriesNoClashWeight() {
        // Presence is small but not zero — a real instrumental still reads as a
        // few percent vocal on the mask. The point is that it carries no
        // measurable weight, not that the product is exactly zero.
        val instrumental = List(20) { ClashFrame(0.02, 0.03, 0.8) }
        assertTrue(
            weightedClash(instrumental)!! < 0.005,
            "an instrumental bed is not a clash (got ${weightedClash(instrumental)})",
        )
    }

    @Test
    fun onlyOneSideSingingCarriesNoClashWeight() {
        // Outgoing sings hard, incoming is instrumental — a normal, desirable
        // overlap. The product presenceOut * presenceIn is what makes it
        // negligible: one singer is not a collision.
        val oneSided = List(20) { ClashFrame(0.85, 0.02, 0.9) }
        assertTrue(
            weightedClash(oneSided)!! < 0.05,
            "one singer is not a clash (got ${weightedClash(oneSided)})",
        )
    }

    @Test
    fun symmetryHoldsUnderSwap() {
        val a = listOf(ClashFrame(0.7, 0.3, 0.5), ClashFrame(0.4, 0.9, 0.2))
        val b = listOf(ClashFrame(0.3, 0.7, 0.5), ClashFrame(0.9, 0.4, 0.2))
        assertEquals(weightedClash(a)!!, weightedClash(b)!!, 1e-12, "clash is symmetric in the pair")
    }

    @Test
    fun emptyIsNotAMeasurement() {
        assertNull(weightedClash(emptyList()), "no frames is unknown, not zero clash")
    }

    @Test
    fun zeroEnergyProgrammeIsUnknownNotZero() {
        val silent = List(5) { ClashFrame(0.9, 0.9, 0.0) }
        assertNull(weightedClash(silent), "silence cannot evidence a clash")
    }

    @Test
    fun nonFiniteEnergyIsSkippedNotPoisoned() {
        val frames = listOf(
            ClashFrame(0.9, 0.9, Double.NaN),
            ClashFrame(0.9, 0.9, 1.0),
        )
        assertEquals(0.81, weightedClash(frames)!!, 0.001, "a NaN frame is skipped, not averaged in")
    }

    @Test
    fun weightedClashIsBounded() {
        val wild = listOf(ClashFrame(5.0, 5.0, 1.0), ClashFrame(-2.0, 0.5, 1.0))
        val got = weightedClash(wild)!!
        assertTrue(got in 0.0..1.0, "out-of-range presence inputs must clamp, got $got")
    }

    // ---- ranking -----------------------------------------------------------

    /**
     * The reconciliation the council settled on: keep beat flow at its Orchard
     * weight and give vocal evidence its own separate 0.20 term, rather than
     * raising both and double-counting.
     */
    @Test
    fun clashTermMovesTheRankByTheDocumentedAmount() {
        val noClash = StemClash.rank(confidence = 0.8, flow = 0.6, meanClash = 0.0)
        val fullClash = StemClash.rank(confidence = 0.8, flow = 0.6, meanClash = 1.0)
        assertEquals(0.20, noClash - fullClash, 1e-9, "the clash term spans exactly 0.20")
    }

    @Test
    fun rankMatchesTheDocumentedFormula() {
        val got = StemClash.rank(confidence = 0.9, flow = 0.4, meanClash = 0.25)
        val want = 0.62 * 0.9 + 0.18 * 0.4 + 0.20 * (1.0 - 0.25)
        assertEquals(want, got, 1e-9)
    }

    @Test
    fun perfectCandidateScoresExactlyOne() {
        assertEquals(1.0, StemClash.rank(1.0, 1.0, 0.0), 1e-9, "weights must sum to one")
    }

    @Test
    fun unknownClashFallsBackToNeutralRatherThanFree() {
        // A null meanClash means "not measured". It must not silently read as
        // zero clash, which would promote unmeasured pairs over measured ones.
        val unknown = StemClash.rank(confidence = 0.8, flow = 0.6, meanClash = null)
        val measuredZero = StemClash.rank(confidence = 0.8, flow = 0.6, meanClash = 0.0)
        assertTrue(unknown < measuredZero, "unmeasured must rank below a measured clean pair")
        assertEquals(
            StemClash.rank(0.8, 0.6, 0.5),
            unknown,
            1e-9,
            "unmeasured is treated as the midpoint, which is what NEUTRAL_VOCAL means",
        )
    }

    @Test
    fun rankStaysInRange() {
        var c = 0.0
        while (c <= 1.0) {
            var f = 0.0
            while (f <= 1.0) {
                var k = 0.0
                while (k <= 1.0) {
                    val r = StemClash.rank(c, f, k)
                    assertTrue(r in -0.001..1.001, "rank out of range at c=$c f=$f clash=$k: $r")
                    k += 0.25
                }
                f += 0.25
            }
            c += 0.25
        }
    }

    // ---- duck gain ---------------------------------------------------------

    /**
     * The point of the whole exercise: a measured clash becomes a solvable gain
     * curve instead of a veto. A collision at 0.8, ducked to a 0.05 target,
     * should leave the outgoing vocal audible but out of the way — not silent.
     */
    @Test
    fun duckGainBringsAClashDownToTarget() {
        // Reachable case: a 0.5 clash against a 0.05 target needs a surviving
        // fraction of 0.1, which sits above the karaoke floor.
        val reachable = StemClash.duckGainFor(0.5, target = 0.05, floor = 0.08)
        assertEquals(0.05, 0.5 * reachable, 1e-9, "a reachable target is met exactly")

        // Marginal case: 0.8 clashes down to the floor, which lands near but
        // not on the target. The floor wins, by design.
        val clash = 0.8
        val gain = StemClash.duckGainFor(clash, target = 0.05)
        assertTrue(clash * gain <= clash, "ducking must reduce the clash")
        assertTrue(gain >= 0.08, "never mute the outgoing vocal entirely — karaoke artefact")
        assertTrue(gain < 1.0, "a real clash must actually be ducked")
    }

    @Test
    fun cleanOverlapIsNotDucked() {
        assertEquals(1.0, StemClash.duckGainFor(0.0), 1e-9, "no clash, no duck")
        assertEquals(1.0, StemClash.duckGainFor(0.04, target = 0.05), 1e-9, "under target, no duck")
    }

    @Test
    fun extremeClashStillNeverReachesSilence() {
        val gain = StemClash.duckGainFor(1.0, target = 0.05, floor = 0.08)
        assertTrue(gain >= 0.08, "floor holds even at a total clash, got $gain")
    }
}
