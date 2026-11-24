package com.music.bitchord.djmix.core.planner

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.min

/** Ring on the Mixed In Key Camelot wheel (`A` = minor, `B` = major). */
@Serializable
enum class CamelotMode {
    A,
    B,
}

/**
 * Harmonic key on the 12-step Mixed In Key Camelot wheel (`"1A".."12B"`).
 * Provides circular wheel distance and normalized compatibility scoring (`0.0..1.0`).
 */
@Serializable
data class CamelotKey(
    val number: Int,
    val mode: CamelotMode,
) {
    init {
        require(number in 1..12) { "Camelot number must be in 1..12, got $number" }
    }

    /** Canonical Camelot notation such as `"8A"` or `"11B"`. */
    val code: String
        get() = "$number${mode.name}"

    /** Shortest circular distance along the 1..12 wheel (`0..6`). */
    fun circularDistance(other: CamelotKey): Int {
        val raw = abs(number - other.number)
        return min(raw, 12 - raw)
    }

    /** Clockwise steps from `this` to [other] on the 1..12 wheel (`0..11`). */
    fun clockwiseSteps(other: CamelotKey): Int = ((other.number - number) % 12 + 12) % 12

    /** True for exact, relative A/B, or ±1 step on the same ring. */
    fun isCompatible(other: CamelotKey): Boolean =
        number == other.number || (mode == other.mode && circularDistance(other) <= 1)

    /**
     * Harmonic compatibility score in `0.0..1.0`:
     * - `1.0`: exact match
     * - `0.88`: relative A/B or ±1 step on the same ring
     * - `0.65`: ±2 steps, diagonal ±1 across rings, or energy boost (+7 clockwise on same ring)
     * - `0.25`: harmonic clash
     */
    fun compatibilityScore(other: CamelotKey): Double {
        if (this == other) return EXACT_SCORE
        val wheelDist = circularDistance(other)
        if (number == other.number) return COMPATIBLE_SCORE
        if (mode == other.mode) {
            if (wheelDist == 1) return COMPATIBLE_SCORE
            if (wheelDist == 2 || clockwiseSteps(other) == ENERGY_BOOST_STEPS) return BOOST_OR_NEAR_SCORE
            return CLASH_SCORE
        }
        return if (wheelDist == 1) BOOST_OR_NEAR_SCORE else CLASH_SCORE
    }

    override fun toString(): String = code

    companion object {
        const val EXACT_SCORE: Double = 1.0
        const val COMPATIBLE_SCORE: Double = 0.88
        const val BOOST_OR_NEAR_SCORE: Double = 0.65
        const val CLASH_SCORE: Double = 0.25
        const val UNKNOWN_SCORE: Double = 0.60
        private const val ENERGY_BOOST_STEPS: Int = 7
        private const val MIN_KEY_CONFIDENCE: Float = 0.35f

        /** Parses Camelot notation `"1A".."12B"` (case-insensitive, trimmed), or returns null. */
        fun parse(notation: String?): CamelotKey? {
            if (notation.isNullOrBlank()) return null
            val trimmed = notation.trim().uppercase()
            if (trimmed.length !in 2..3) return null
            val mode = when (trimmed.last()) {
                'A' -> CamelotMode.A
                'B' -> CamelotMode.B
                else -> return null
            }
            val digits = trimmed.dropLast(1)
            if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
            val num = digits.toIntOrNull() ?: return null
            if (num !in 1..12) return null
            return CamelotKey(num, mode)
        }

        /**
         * Scores two Camelot key strings weighted by confidence.
         * Returns [UNKNOWN_SCORE] when either key is missing/unparseable or below [MIN_KEY_CONFIDENCE].
         */
        fun compatibilityScore(
            outKey: String?,
            inKey: String?,
            outConfidence: Float = 1f,
            inConfidence: Float = 1f,
        ): Double {
            if (outConfidence < MIN_KEY_CONFIDENCE || inConfidence < MIN_KEY_CONFIDENCE) return UNKNOWN_SCORE
            val outParsed = parse(outKey) ?: return UNKNOWN_SCORE
            val inParsed = parse(inKey) ?: return UNKNOWN_SCORE
            return outParsed.compatibilityScore(inParsed)
        }

        /**
         * True only when both keys parse, meet confidence floor, and satisfy [CamelotKey.isCompatible].
         */
        fun areCompatible(
            outKey: String?,
            inKey: String?,
            outConfidence: Float = 1f,
            inConfidence: Float = 1f,
        ): Boolean {
            if (outConfidence < MIN_KEY_CONFIDENCE || inConfidence < MIN_KEY_CONFIDENCE) return false
            val outParsed = parse(outKey) ?: return false
            val inParsed = parse(inKey) ?: return false
            return outParsed.isCompatible(inParsed)
        }
    }
}
