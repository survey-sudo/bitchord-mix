package com.music.bitchord.playback.smart.choreo

/**
 * AUTOMIX-V2 Step 5: DJ levels rebuilt as bar-timed choreography recipes.
 *
 * Each DJ level owns a strictly disjoint recipe pool:
 * - SUBTLE:   S1..S5 (max 1 FX, 3% stretch cap, restrained blends)
 * - CLUB:     C1..C7 (1-2 FX, 6% stretch cap, signature moves)
 * - FESTIVAL: F1..F8 (2-3 layered FX, 8% stretch cap, performance moves)
 *
 * Every recipe provides both [steps] (stem-aware) and [noStemSteps] (using the
 * LR4 isolator EQ / filters instead of stems). A recipe degrades only to its
 * own no-stem variant or to another recipe of the SAME level — never downward.
 */
enum class RecipeId(
    val code: String,
    val displayName: String,
    val minLevel: DjLevel,
) {
    // Subtle (S1-S5)
    S1("S1", "Smooth blend", DjLevel.SUBTLE),
    S2("S2", "Bass swap", DjLevel.SUBTLE),
    S3("S3", "Muffle-in", DjLevel.SUBTLE),
    S4("S4", "Echo out", DjLevel.SUBTLE),
    S5("S5", "Simple fade", DjLevel.SUBTLE),

    // Club (C1-C7)
    C1("C1", "Drum swap", DjLevel.CLUB),
    C2("C2", "Filter wash + echo", DjLevel.CLUB),
    C3("C3", "Acapella ride", DjLevel.CLUB),
    C4("C4", "Instrumental bed", DjLevel.CLUB),
    C5("C5", "Loop extend", DjLevel.CLUB),
    C6("C6", "Phaser dissolve", DjLevel.CLUB),
    C7("C7", "Drop on the beat", DjLevel.CLUB),

    // Festival (F1-F8)
    F1("F1", "Riser drop", DjLevel.FESTIVAL),
    F2("F2", "Brake slam", DjLevel.FESTIVAL),
    F3("F3", "Stem shuffle", DjLevel.FESTIVAL),
    F4("F4", "Acapella mashup", DjLevel.FESTIVAL),
    F5("F5", "Tunnel", DjLevel.FESTIVAL),
    F6("F6", "Pump rider", DjLevel.FESTIVAL),
    F7("F7", "Spinback cut", DjLevel.FESTIVAL),
    F8("F8", "Double drop", DjLevel.FESTIVAL),
    ;

    companion object {
        fun fromCode(code: String?): RecipeId? =
            if (code.isNullOrBlank()) null else values().firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

enum class DeckTarget { OUT, IN }

enum class Anchor {
    /** t = 0.0 (start of mix clock). */
    ZERO,
    /** Bar 1 of overlap (c.inAudibleAt). */
    IN_AUDIBLE,
    /** Chosen swap / landing / cut downbeat (S). */
    SWAP,
    /** Overlap end (c.release). */
    RELEASE,
    /** Outgoing stop boundary including echo/reverb tail (c.outStop). */
    OUT_STOP,
    /** A + (S - A) * param. */
    PRE_FRAC,
    /** S + (R - S) * param. */
    POST_FRAC,
    /** A + (R - A) * param. */
    OVERLAP_FRAC,
    /** min(S, A + param * bar). */
    A_PLUS_CAPPED_S,
    /** min(R, S + param * bar). */
    S_PLUS_CAPPED_R,
    /** max(A, S - param * bar). */
    SWAP_BACK_A,
    /** max(0.0, S - param * bar). */
    SWAP_BACK_ZERO,
    /** max(S, R - param * bar). */
    RELEASE_BACK_S,
    /** Incoming vocal entry (or A + min(bar, preSpan * 0.6)). */
    VOCAL_ENTRY,
    /** Ballad-length equal-power fade start: max(A, R - clamp((R - A) * 0.8, 4.0, 10.0)). */
    FADE_START,
}

enum class CurveShape { LINEAR, EXPONENTIAL, EQUAL_POWER_FALL, EQUAL_POWER_RISE }

enum class SwapWindowRule {
    LAST_PHRASE,
    BAR_9_OR_MID,
    BAR_9_OR_LATE,
    CUT_BAR_5_OR_LATE,
    FULL_FADE,
    LATE_PHRASE,
    CUT_LATE,
    MID_PHRASE,
    EARLY_PHRASE,
    DROP_BEAT,
    DROP_OR_LATE,
    DROP_OR_MID,
    BAR_9_OR_FIRST_THIRD,
}

enum class FxType {
    ECHO,
    REVERB,
    FLANGER,
    PHASER,
    GATE,
    RISER,
    PUMP,
    BRAKE,
    LOOP_ROLL,
    SVF_Q,
}

data class StepTime(
    val anchor: Anchor,
    val param: Double = 0.0,
    val offsetBars: Double = 0.0,
    val offsetBeats: Double = 0.0,
    val offsetSec: Double = 0.0,
    val minAt: Anchor? = null,
    val maxAt: Anchor? = null,
)

data class Waypoint(
    val time: StepTime,
    val value: Float,
    val scaleByFxDepth: Boolean = false,
    val depthBase: Float = 0f,
)

sealed class RecipeStep {
    data class LaneCurve(
        val deck: DeckTarget,
        val kind: EnvKind,
        val shape: CurveShape = CurveShape.LINEAR,
        val points: List<Waypoint>,
        val steps: Int = 12,
        val holdNeutralAtStart: Boolean = true,
        val ensureNeutralAtReleaseForIn: Boolean = true,
    ) : RecipeStep()

    data class SwapLows(
        val at: StepTime = StepTime(Anchor.SWAP),
        val widthBeats: Double = 0.25,
        val inLowBeforeDb: Float = -24f,
        val outLowAfterDb: Float = -24f,
        val useBassStems: Boolean = true,
    ) : RecipeStep()

    data object EnableIsolator : RecipeStep()

    data class ConfigureFx(
        val deck: DeckTarget,
        val fx: FxType,
        val requiredCap: Int = 0,
        val fallbackFx: FxType? = null,
        val param1: Double = 0.0,
        val param2: Float = 0f,
        val param3: Float = 0f,
        val flag: Boolean = false,
        val startTime: StepTime? = null,
        val endTime: StepTime? = null,
        val heavyReverbOnRockBallad: Boolean = false,
        val wetCurve: List<Waypoint>? = null,
        val fallbackWetCurve: List<Waypoint>? = null,
    ) : RecipeStep()
}

data class Recipe(
    val id: RecipeId,
    val displayName: String,
    val minLevel: DjLevel,
    val barRange: IntRange,
    val stretchCap: Double,
    val genreWeights: Map<GenreClass, Double>,
    val requiresHarmony: Boolean = false,
    val requiresDrops: Boolean = false,
    val requiresOutDrop: Boolean = false,
    val requiresOutLoop: Boolean = false,
    val needsGridConfidence: Boolean = true,
    val requiresBeatMatch: Boolean = true,
    /** Minimum stems needed when unforced (0 = none/filter-capable, 2 = vocal split, 4 = full). */
    val requiresStems: Int = 0,
    /** Minimum stems needed to select [steps] instead of [noStemSteps] (0 = SwapLows opportunistic). */
    val stemModeMinStems: Int = 0,
    val requiredCaps: Int = 0,
    val minBeatConf: Double = 0.45,
    val minOverlapBars: Double = 2.0,
    val swapsBass: Boolean = true,
    val cut: Boolean = false,
    val hasDropExemption: Boolean = false,
    val swapRule: SwapWindowRule = SwapWindowRule.MID_PHRASE,
    val move: Move,
    val steps: List<RecipeStep>,
    val noStemSteps: List<RecipeStep>,
    val fxSummary: String,
) {
    val isFallbackCapableWithoutStems: Boolean get() = true
}
