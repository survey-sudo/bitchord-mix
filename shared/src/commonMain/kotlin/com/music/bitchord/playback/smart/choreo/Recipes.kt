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

// ---- concise step DSL helpers -------------------------------------------------------------------

private fun t(
    anchor: Anchor,
    param: Double = 0.0,
    bars: Double = 0.0,
    beats: Double = 0.0,
    sec: Double = 0.0,
    min: Anchor? = null,
    max: Anchor? = null,
) = StepTime(anchor, param, bars, beats, sec, min, max)

private val T_ZERO = t(Anchor.ZERO)
private val T_A = t(Anchor.IN_AUDIBLE)
private val T_S = t(Anchor.SWAP)
private val T_R = t(Anchor.RELEASE)
private val T_STOP = t(Anchor.OUT_STOP)

private fun wp(time: StepTime, v: Float, scale: Boolean = false, base: Float = 0f) =
    Waypoint(time, v, scale, base)

private fun outFallFrom(from: StepTime, to: StepTime = T_R, steps: Int = 12) = RecipeStep.LaneCurve(
    DeckTarget.OUT, EnvKind.GAIN, CurveShape.EQUAL_POWER_FALL,
    listOf(wp(from, 1f), wp(to, 0f)), steps = steps,
)

private fun outCutAt(cutTime: StepTime = T_S, leadSec: Double = -0.05) = RecipeStep.LaneCurve(
    DeckTarget.OUT, EnvKind.GAIN, CurveShape.LINEAR,
    listOf(
        wp(StepTime(cutTime.anchor, cutTime.param, cutTime.offsetBars, cutTime.offsetBeats, cutTime.offsetSec + leadSec, cutTime.minAt, cutTime.maxAt), 1f),
        wp(cutTime, 0f),
    ),
)

private fun inRise(
    from: StepTime = T_A,
    to: StepTime,
    peak: Float = 1f,
    holdUntil: StepTime? = null,
    steps: Int = 12,
): RecipeStep.LaneCurve {
    val pts = ArrayList<Waypoint>()
    pts += wp(from, 0f)
    pts += wp(to, peak)
    if (holdUntil != null && peak < 1f) {
        pts += wp(holdUntil, peak)
        pts += wp(StepTime(holdUntil.anchor, holdUntil.param, holdUntil.offsetBars, holdUntil.offsetBeats + 1.0, holdUntil.offsetSec, holdUntil.minAt, holdUntil.maxAt), 1f)
    } else if (peak < 1f) {
        pts += wp(T_R, 1f)
    }
    return RecipeStep.LaneCurve(DeckTarget.IN, EnvKind.GAIN, CurveShape.EQUAL_POWER_RISE, pts, steps = steps)
}

private fun inSlamAtSwap(preOffsetSec: Double = -0.015, postOffsetSec: Double = 0.005) = RecipeStep.LaneCurve(
    DeckTarget.IN, EnvKind.GAIN, CurveShape.LINEAR,
    listOf(
        wp(T_A, 0f),
        wp(t(Anchor.SWAP, sec = preOffsetSec, min = Anchor.IN_AUDIBLE), 0f),
        wp(t(Anchor.SWAP, sec = postOffsetSec), 1f),
    ),
)

private fun hpSweep(
    deck: DeckTarget,
    from: StepTime,
    to: StepTime,
    fromHz: Float,
    toHz: Float,
    scaleTo: Boolean = false,
    baseHz: Float = 0f,
    resetToNeutralAt: StepTime? = null,
    holdAtStart: Boolean = true,
): RecipeStep.LaneCurve {
    val pts = mutableListOf(wp(from, fromHz), wp(to, toHz, scale = scaleTo, base = baseHz))
    if (resetToNeutralAt != null) pts += wp(resetToNeutralAt, 20f)
    return RecipeStep.LaneCurve(deck, EnvKind.HIGH_PASS_HZ, CurveShape.EXPONENTIAL, pts, holdNeutralAtStart = holdAtStart)
}

private fun lpSweep(
    deck: DeckTarget,
    from: StepTime,
    to: StepTime,
    fromHz: Float,
    toHz: Float,
    holdAtStart: Boolean = true,
) = RecipeStep.LaneCurve(
    deck, EnvKind.LOW_PASS_HZ, CurveShape.EXPONENTIAL,
    listOf(wp(from, fromHz), wp(to, toHz)),
    holdNeutralAtStart = holdAtStart,
)

// ---- All 20 recipes (S1-S5, C1-C7, F1-F8) -------------------------------------------------------

object Recipes {

    // ---- Subtle (S1-S5) ------------------------------------------------------------------------

    val S1 = Recipe(
        id = RecipeId.S1,
        displayName = "Smooth blend",
        minLevel = DjLevel.SUBTLE,
        barRange = 8..16,
        stretchCap = 0.03,
        genreWeights = mapOf(
            GenreClass.POP to 0.22,
            GenreClass.DANCE to 0.10,
            GenreClass.ROCK to 0.12,
            GenreClass.UNKNOWN to 0.15,
        ),
        minBeatConf = 0.45,
        minOverlapBars = 2.0,
        swapsBass = true,
        cut = false,
        swapRule = SwapWindowRule.LAST_PHRASE,
        move = Move.APPLE_STYLE_SMOOTH,
        fxSummary = "None",
        steps = listOf(
            outFallFrom(T_A, T_R, steps = 16),
            inRise(T_A, T_R, steps = 16),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
            hpSweep(DeckTarget.OUT, T_S, T_R, 20f, 150f),
        ),
        noStemSteps = listOf(
            outFallFrom(T_A, T_R, steps = 16),
            inRise(T_A, T_R, steps = 16),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
            hpSweep(DeckTarget.OUT, T_S, T_R, 20f, 150f),
        ),
    )

    val S2 = Recipe(
        id = RecipeId.S2,
        displayName = "Bass swap",
        minLevel = DjLevel.SUBTLE,
        barRange = 8..16,
        stretchCap = 0.03,
        genreWeights = mapOf(
            GenreClass.DANCE to 0.22,
            GenreClass.POP to 0.12,
            GenreClass.HIPHOP to 0.10,
            GenreClass.UNKNOWN to 0.12,
        ),
        minBeatConf = 0.45,
        minOverlapBars = 2.0,
        swapsBass = true,
        cut = false,
        swapRule = SwapWindowRule.BAR_9_OR_MID,
        move = Move.STEM_BASS_SWAP,
        fxSummary = "None",
        steps = listOf(
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 2.0), peak = 0.85f, holdUntil = T_S),
            outFallFrom(T_S, t(Anchor.S_PLUS_CAPPED_R, param = 4.0)),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
            hpSweep(DeckTarget.OUT, T_S, t(Anchor.S_PLUS_CAPPED_R, param = 4.0), 20f, 320f),
        ),
        noStemSteps = listOf(
            RecipeStep.EnableIsolator,
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 2.0), peak = 0.85f, holdUntil = T_S),
            outFallFrom(T_S, t(Anchor.S_PLUS_CAPPED_R, param = 4.0)),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.MID_DB, CurveShape.LINEAR,
                listOf(wp(T_S, 0f), wp(t(Anchor.S_PLUS_CAPPED_R, param = 4.0), -10f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.HIGH_DB, CurveShape.LINEAR,
                listOf(wp(T_S, 0f), wp(t(Anchor.S_PLUS_CAPPED_R, param = 4.0), -8f)),
            ),
        ),
    )

    private val s3SharedSteps = listOf(
        inRise(T_A, t(Anchor.SWAP_BACK_A, param = 7.0, max = Anchor.SWAP), peak = 0.9f, holdUntil = T_S),
        outFallFrom(T_S, t(Anchor.S_PLUS_CAPPED_R, param = 2.0)),
        lpSweep(DeckTarget.IN, t(Anchor.SWAP_BACK_A, param = 8.0), T_S, 300f, 20000f, holdAtStart = false),
        hpSweep(DeckTarget.OUT, t(Anchor.SWAP_BACK_A, param = 8.0), T_S, 20f, 400f),
        RecipeStep.ConfigureFx(DeckTarget.IN, FxType.SVF_Q, param3 = 0.9f),
        RecipeStep.ConfigureFx(DeckTarget.OUT, FxType.SVF_Q, param3 = 0.9f),
    )

    val S3 = Recipe(
        id = RecipeId.S3,
        displayName = "Muffle-in",
        minLevel = DjLevel.SUBTLE,
        barRange = 8..8,
        stretchCap = 0.03,
        genreWeights = mapOf(
            GenreClass.POP to 0.16,
            GenreClass.DANCE to 0.10,
            GenreClass.UNKNOWN to 0.10,
        ),
        minBeatConf = 0.45,
        minOverlapBars = 3.0,
        swapsBass = true,
        cut = false,
        swapRule = SwapWindowRule.BAR_9_OR_LATE,
        move = Move.MUFFLE_IN,
        fxSummary = "Filter sweep (Q 0.9)",
        steps = s3SharedSteps + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, inLowBeforeDb = -12f),
        noStemSteps = s3SharedSteps + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, inLowBeforeDb = -12f, useBassStems = false),
    )

    private val s4Steps = listOf(
        outCutAt(T_S, leadSec = -0.08),
        RecipeStep.LaneCurve(
            DeckTarget.OUT, EnvKind.LOW_DB, CurveShape.LINEAR,
            listOf(wp(t(Anchor.SWAP, sec = -0.08, min = Anchor.ZERO), 0f), wp(T_S, -24f)),
        ),
        hpSweep(DeckTarget.OUT, t(Anchor.SWAP_BACK_ZERO, param = 2.0), T_S, 20f, 450f),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.ECHO,
            param1 = 0.5, // 1/2 beat
            param2 = 0.55f,
            param3 = 0.5f,
            wetCurve = listOf(
                wp(t(Anchor.SWAP_BACK_ZERO, param = 2.0), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.85f, scale = true),
                wp(t(Anchor.SWAP, sec = 0.12, max = Anchor.OUT_STOP), 0f),
            ),
        ),
        inSlamAtSwap(preOffsetSec = -0.02, postOffsetSec = 0.12),
    )

    val S4 = Recipe(
        id = RecipeId.S4,
        displayName = "Echo out",
        minLevel = DjLevel.SUBTLE,
        barRange = 4..4,
        stretchCap = 0.08,
        genreWeights = mapOf(
            GenreClass.HIPHOP to 0.28,
            GenreClass.ROCK to 0.20,
            GenreClass.POP to 0.08,
        ),
        needsGridConfidence = false,
        requiresBeatMatch = false,
        minBeatConf = 0.35,
        minOverlapBars = 0.0,
        swapsBass = false,
        cut = true,
        swapRule = SwapWindowRule.CUT_BAR_5_OR_LATE,
        move = Move.FILTER_WASH_ECHO_OUT,
        fxSummary = "Echo (1/2 beat)",
        steps = s4Steps,
        noStemSteps = s4Steps,
    )

    private val s5Steps = listOf(
        outFallFrom(t(Anchor.FADE_START), T_R, steps = 16),
        inRise(t(Anchor.FADE_START), T_R, steps = 16),
    )

    val S5 = Recipe(
        id = RecipeId.S5,
        displayName = "Simple fade",
        minLevel = DjLevel.SUBTLE,
        barRange = 2..8,
        stretchCap = Double.POSITIVE_INFINITY,
        genreWeights = mapOf(
            GenreClass.BALLAD to 0.50,
            GenreClass.ROCK to 0.15,
        ),
        needsGridConfidence = false,
        requiresBeatMatch = false,
        minBeatConf = 0.0,
        minOverlapBars = 0.0,
        swapsBass = false,
        cut = false,
        swapRule = SwapWindowRule.FULL_FADE,
        move = Move.SIMPLE_FADE,
        fxSummary = "None",
        steps = s5Steps,
        noStemSteps = s5Steps,
    )

    // ---- Club (C1-C7) --------------------------------------------------------------------------

    private val c1Echo = RecipeStep.ConfigureFx(
        deck = DeckTarget.OUT,
        fx = FxType.ECHO,
        param1 = 0.5,
        param2 = 0.52f,
        param3 = 0.48f,
        wetCurve = listOf(
            wp(t(Anchor.POST_FRAC, param = 0.5), 0f),
            wp(t(Anchor.RELEASE, beats = -0.25, min = Anchor.SWAP), 0.75f, scale = true),
            wp(T_R, 0f),
        ),
    )

    val C1 = Recipe(
        id = RecipeId.C1,
        displayName = "Drum swap",
        minLevel = DjLevel.CLUB,
        barRange = 16..16,
        stretchCap = 0.06,
        genreWeights = mapOf(
            GenreClass.DANCE to 0.20,
            GenreClass.HIPHOP to 0.16,
            GenreClass.POP to 0.10,
        ),
        requiresStems = 4,
        stemModeMinStems = 4,
        minBeatConf = 0.50,
        minOverlapBars = 4.0,
        swapsBass = true,
        cut = false,
        swapRule = SwapWindowRule.LATE_PHRASE,
        move = Move.DRUM_SWAP,
        fxSummary = "Echo throw",
        steps = listOf(
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 1.0), peak = 0.85f, holdUntil = T_S),
            outFallFrom(t(Anchor.POST_FRAC, param = 0.5), T_R),
            // Bar 1-8: IN drums only under OUT; Bar 9: drums swap; Bar 13: bass swap; Bar 15: OUT fade + echo throw.
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_DRUMS, CurveShape.LINEAR,
                listOf(
                    wp(t(Anchor.PRE_FRAC, param = 0.66, beats = -0.06), 1f),
                    wp(t(Anchor.PRE_FRAC, param = 0.66, beats = 0.06), 0f),
                ),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.STEM_DRUMS, CurveShape.LINEAR,
                listOf(wp(T_ZERO, 1f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.STEM_OTHER, CurveShape.LINEAR,
                listOf(
                    wp(t(Anchor.PRE_FRAC, param = 0.66), 0f),
                    wp(T_S, 1f),
                ),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.STEM_VOCALS, CurveShape.LINEAR,
                listOf(wp(T_S, 0f), wp(t(Anchor.POST_FRAC, param = 0.5), 1f)),
            ),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
            c1Echo,
        ),
        noStemSteps = listOf(
            RecipeStep.EnableIsolator,
            inRise(T_A, t(Anchor.PRE_FRAC, param = 0.66), peak = 0.82f, holdUntil = T_S),
            outFallFrom(t(Anchor.POST_FRAC, param = 0.5), T_R),
            hpSweep(DeckTarget.IN, t(Anchor.PRE_FRAC, param = 0.66), T_S, 320f, 20f, holdAtStart = false),
            hpSweep(DeckTarget.OUT, t(Anchor.PRE_FRAC, param = 0.66), T_R, 20f, 800f),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.MID_DB, CurveShape.LINEAR,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.5), 0f), wp(T_R, -10f)),
            ),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
            c1Echo,
        ),
    )

    private val c2Steps = listOf(
        outCutAt(T_S, leadSec = -0.05),
        inRise(T_A, t(Anchor.SWAP, sec = -0.005, min = Anchor.IN_AUDIBLE), peak = 0.9f, holdUntil = t(Anchor.SWAP, sec = -0.005, min = Anchor.IN_AUDIBLE)),
        hpSweep(DeckTarget.OUT, t(Anchor.SWAP_BACK_A, param = 8.0), T_S, 20f, 1500f),
        RecipeStep.LaneCurve(
            DeckTarget.OUT, EnvKind.FILTER_RES, CurveShape.LINEAR,
            listOf(
                wp(t(Anchor.SWAP_BACK_A, param = 8.0), 1f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 1.7f), // 0.707 * 1.7 ~= 1.2
                wp(T_S, 1f),
            ),
        ),
        lpSweep(DeckTarget.IN, t(Anchor.SWAP_BACK_A, param = 8.0), T_S, 350f, 20000f, holdAtStart = false),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.REVERB,
            param1 = 2.4,
            param2 = 0.55f,
            param3 = 0.45f,
            heavyReverbOnRockBallad = true,
            wetCurve = listOf(
                wp(t(Anchor.SWAP_BACK_A, param = 8.0), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.4f),
                wp(T_STOP, 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.ECHO,
            param1 = 0.5,
            param2 = 0.55f,
            param3 = 0.5f,
            wetCurve = listOf(
                wp(t(Anchor.SWAP, beats = -1.0, min = Anchor.ZERO), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.85f, scale = true),
                wp(t(Anchor.SWAP, sec = 0.12, max = Anchor.OUT_STOP), 0f),
            ),
        ),
    )

    val C2 = Recipe(
        id = RecipeId.C2,
        displayName = "Filter wash + echo",
        minLevel = DjLevel.CLUB,
        barRange = 8..16,
        stretchCap = Double.POSITIVE_INFINITY,
        genreWeights = mapOf(
            GenreClass.HIPHOP to 0.22,
            GenreClass.ROCK to 0.28,
            GenreClass.BALLAD to 0.28,
            GenreClass.DANCE to 0.14,
            GenreClass.POP to 0.14,
        ),
        needsGridConfidence = false,
        requiresBeatMatch = false,
        minBeatConf = 0.0,
        minOverlapBars = 0.0,
        swapsBass = true,
        cut = true,
        swapRule = SwapWindowRule.CUT_LATE,
        move = Move.FILTER_WASH_ECHO_OUT,
        fxSummary = "Reverb + Echo",
        steps = c2Steps + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, inLowBeforeDb = -12f),
        noStemSteps = c2Steps + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, inLowBeforeDb = -12f, useBassStems = false),
    )

    private val c3Echo = RecipeStep.ConfigureFx(
        deck = DeckTarget.OUT,
        fx = FxType.ECHO,
        param1 = 0.5,
        param2 = 0.55f,
        param3 = 0.5f,
        flag = true, // adapt 1/2 vs 1 beat to BPM
        wetCurve = listOf(
            wp(t(Anchor.POST_FRAC, param = 0.5, beats = -1.5, min = Anchor.ZERO), 0f),
            wp(t(Anchor.RELEASE, beats = -0.3, min = Anchor.SWAP), 0.8f, scale = true),
            wp(T_R, 0f),
        ),
    )

    val C3 = Recipe(
        id = RecipeId.C3,
        displayName = "Acapella ride",
        minLevel = DjLevel.CLUB,
        barRange = 16..16,
        stretchCap = 0.06,
        genreWeights = mapOf(
            GenreClass.POP to 0.26,
            GenreClass.DANCE to 0.16,
            GenreClass.HIPHOP to 0.16,
        ),
        requiresHarmony = true,
        requiresStems = 2,
        stemModeMinStems = 2,
        minBeatConf = 0.50,
        minOverlapBars = 4.0,
        swapsBass = true,
        cut = false,
        swapRule = SwapWindowRule.MID_PHRASE,
        move = Move.ACAPELLA_OVER_INSTRUMENTAL,
        fxSummary = "Echo out",
        steps = listOf(
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 1.0)),
            outFallFrom(t(Anchor.POST_FRAC, param = 0.5), T_R),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_OTHER, CurveShape.EQUAL_POWER_FALL,
                listOf(wp(T_A, 1f), wp(T_S, 0f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_DRUMS, CurveShape.LINEAR,
                listOf(wp(T_A, 1f), wp(t(Anchor.A_PLUS_CAPPED_S, param = 0.25), 0f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.STEM_VOCALS, CurveShape.LINEAR,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.5), 0f), wp(t(Anchor.RELEASE, sec = -0.01), 1f)),
            ),
            hpSweep(DeckTarget.OUT, T_S, T_R, 20f, 160f),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
            c3Echo,
        ),
        noStemSteps = listOf(
            RecipeStep.EnableIsolator,
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 1.0)),
            outFallFrom(t(Anchor.POST_FRAC, param = 0.5), T_R),
            hpSweep(DeckTarget.OUT, T_A, T_S, 20f, 380f),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.MID_DB, CurveShape.LINEAR,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.5), 0f), wp(T_R, -12f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.HIGH_DB, CurveShape.LINEAR,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.5), 0f), wp(T_R, -9f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.MID_DB, CurveShape.LINEAR,
                listOf(wp(T_ZERO, -6f), wp(t(Anchor.POST_FRAC, param = 0.5), -6f), wp(t(Anchor.RELEASE, sec = -0.01), 0f)),
                holdNeutralAtStart = false,
            ),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
            c3Echo,
        ),
    )

    val C4 = Recipe(
        id = RecipeId.C4,
        displayName = "Instrumental bed",
        minLevel = DjLevel.CLUB,
        barRange = 16..16,
        stretchCap = 0.06,
        genreWeights = mapOf(
            GenreClass.POP to 0.24,
            GenreClass.HIPHOP to 0.20,
            GenreClass.DANCE to 0.14,
        ),
        stemModeMinStems = 2,
        minBeatConf = 0.45,
        minOverlapBars = 2.0,
        swapsBass = true,
        cut = false,
        swapRule = SwapWindowRule.BAR_9_OR_MID,
        move = Move.INSTRUMENTAL_BED,
        fxSummary = "Soft HPF / vocal pocket",
        steps = listOf(
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 0.5)),
            outFallFrom(t(Anchor.POST_FRAC, param = 0.5), T_R),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_VOCALS, CurveShape.EQUAL_POWER_FALL,
                listOf(
                    wp(t(Anchor.VOCAL_ENTRY, bars = -1.0, min = Anchor.IN_AUDIBLE), 1f),
                    wp(t(Anchor.VOCAL_ENTRY, sec = -0.04, min = Anchor.IN_AUDIBLE), 0f),
                ),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_OTHER, CurveShape.LINEAR,
                listOf(wp(T_S, 1f), wp(T_R, 0f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_DRUMS, CurveShape.LINEAR,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.5), 1f), wp(T_R, 0f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.STEM_DRUMS, CurveShape.LINEAR,
                listOf(wp(T_S, 0f), wp(t(Anchor.POST_FRAC, param = 0.5), 1f)),
            ),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
        ),
        noStemSteps = listOf(
            RecipeStep.EnableIsolator,
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 0.5)),
            outFallFrom(T_S, T_R),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.MID_DB, CurveShape.LINEAR,
                listOf(
                    wp(t(Anchor.VOCAL_ENTRY, bars = -1.0, min = Anchor.IN_AUDIBLE), 0f),
                    wp(t(Anchor.VOCAL_ENTRY, sec = -0.04, min = Anchor.IN_AUDIBLE), -9f),
                ),
            ),
            hpSweep(DeckTarget.IN, T_S, t(Anchor.S_PLUS_CAPPED_R, param = 1.0), 280f, 20f, holdAtStart = false),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
        ),
    )

    private val c5Shared = listOf(
        inRise(T_A, T_S, peak = 0.85f, holdUntil = T_S, steps = 16),
        outFallFrom(T_S, T_R, steps = 12),
        hpSweep(DeckTarget.IN, t(Anchor.SWAP_BACK_A, param = 1.0), T_S, 160f, 20f, holdAtStart = false),
        RecipeStep.LaneCurve(
            DeckTarget.OUT, EnvKind.HIGH_PASS_HZ, CurveShape.EXPONENTIAL,
            listOf(wp(T_A, 20f), wp(T_S, 90f), wp(T_R, 400f)),
        ),
    )

    val C5 = Recipe(
        id = RecipeId.C5,
        displayName = "Loop extend",
        minLevel = DjLevel.CLUB,
        barRange = 8..16,
        stretchCap = 0.06,
        genreWeights = mapOf(
            GenreClass.DANCE to 0.18,
            GenreClass.POP to 0.14,
            GenreClass.UNKNOWN to 0.12,
        ),
        requiresOutLoop = true,
        minBeatConf = 0.50,
        minOverlapBars = 4.0,
        swapsBass = true,
        cut = false,
        swapRule = SwapWindowRule.LATE_PHRASE,
        move = Move.LOOP_EXTEND,
        fxSummary = "Filter sweep",
        steps = c5Shared + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
        noStemSteps = c5Shared + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
    )

    private val c6Base = listOf(
        inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 2.0), peak = 0.88f, holdUntil = T_S),
        outFallFrom(T_S, T_R),
        hpSweep(DeckTarget.OUT, T_S, T_R, 20f, 380f),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.PHASER,
            requiredCap = ChoreoCaps.PHASER,
            fallbackFx = FxType.FLANGER,
            param1 = 8.0, // 1 cycle per 8 beats
            param2 = 0.7f,
            param3 = 0.45f,
            wetCurve = listOf(
                wp(t(Anchor.SWAP_BACK_A, param = 2.0), 0f),
                wp(T_S, 0.5f, scale = true),
                wp(T_R, 0.5f, scale = true),
            ),
            fallbackWetCurve = listOf(
                wp(t(Anchor.SWAP_BACK_A, param = 2.0), 0f),
                wp(T_S, 1f),
                wp(T_R, 1f),
            ),
        ),
    )

    val C6 = Recipe(
        id = RecipeId.C6,
        displayName = "Phaser dissolve",
        minLevel = DjLevel.CLUB,
        barRange = 8..8,
        stretchCap = 0.06,
        genreWeights = mapOf(
            GenreClass.DANCE to 0.16,
            GenreClass.POP to 0.14,
            GenreClass.ROCK to 0.14,
        ),
        minBeatConf = 0.50,
        minOverlapBars = 4.0,
        swapsBass = true,
        cut = false,
        swapRule = SwapWindowRule.MID_PHRASE,
        move = Move.PHASER_DISSOLVE,
        fxSummary = "Phaser / Flanger",
        steps = c6Base + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
        noStemSteps = c6Base + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
    )

    private val c7Steps = listOf(
        outCutAt(T_S, leadSec = -0.03),
        RecipeStep.LaneCurve(
            DeckTarget.OUT, EnvKind.LOW_DB, CurveShape.LINEAR,
            listOf(wp(t(Anchor.SWAP, sec = -0.03, min = Anchor.ZERO), 0f), wp(T_S, -24f)),
        ),
        hpSweep(DeckTarget.OUT, t(Anchor.SWAP_BACK_ZERO, param = 4.0), t(Anchor.SWAP, sec = -0.01, min = Anchor.ZERO), 20f, 800f),
        RecipeStep.LaneCurve(
            DeckTarget.OUT, EnvKind.FILTER_RES, CurveShape.LINEAR,
            listOf(
                wp(t(Anchor.SWAP_BACK_ZERO, param = 4.0), 1f),
                wp(t(Anchor.SWAP, sec = -0.01, min = Anchor.ZERO), 2.0f, scale = true, base = 1f),
                wp(T_S, 1f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.LOOP_ROLL,
            param1 = 8.0,
            startTime = t(Anchor.SWAP_BACK_ZERO, param = 2.0),
            endTime = T_S,
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.RISER,
            requiredCap = ChoreoCaps.RISER,
            param1 = 16.0,
            wetCurve = listOf(
                wp(t(Anchor.SWAP_BACK_ZERO, param = 4.0), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.5f),
                wp(T_S, 0f),
            ),
        ),
        inSlamAtSwap(preOffsetSec = -0.012, postOffsetSec = 0.004),
    )

    val C7 = Recipe(
        id = RecipeId.C7,
        displayName = "Drop on the beat",
        minLevel = DjLevel.CLUB,
        barRange = 8..16,
        stretchCap = 0.06,
        genreWeights = mapOf(
            GenreClass.DANCE to 0.34,
            GenreClass.POP to 0.14,
        ),
        requiresDrops = true,
        minBeatConf = 0.50,
        minOverlapBars = 2.0,
        swapsBass = false,
        cut = true,
        swapRule = SwapWindowRule.DROP_BEAT,
        move = Move.EDM_BUILD_DROP,
        fxSummary = "Riser + Loop roll",
        steps = c7Steps,
        noStemSteps = c7Steps,
    )

    // ---- Festival (F1-F8) ----------------------------------------------------------------------

    private val f1FxSteps = listOf(
        outCutAt(T_S, leadSec = -0.03),
        hpSweep(DeckTarget.OUT, t(Anchor.PRE_FRAC, param = 0.5), t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 20f, 1200f),
        RecipeStep.ConfigureFx(DeckTarget.OUT, FxType.SVF_Q, param3 = 0.9f),
        RecipeStep.LaneCurve(
            DeckTarget.OUT, EnvKind.FILTER_RES, CurveShape.LINEAR,
            listOf(
                wp(t(Anchor.PRE_FRAC, param = 0.5), 1f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 1.78f), // 0.9 * 1.78 = 1.6
                wp(T_S, 1f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.RISER,
            requiredCap = ChoreoCaps.RISER,
            fallbackFx = FxType.FLANGER,
            param1 = 32.0,
            wetCurve = listOf(
                wp(t(Anchor.PRE_FRAC, param = 0.5), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.8f),
                wp(T_S, 0f),
            ),
            fallbackWetCurve = listOf(
                wp(t(Anchor.PRE_FRAC, param = 0.5), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 1f),
                wp(T_S, 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.GATE,
            requiredCap = ChoreoCaps.GATE,
            param1 = 4.0, // 1/16 grid
            param2 = 0f,
            wetCurve = listOf(
                wp(t(Anchor.PRE_FRAC, param = 0.75), 0f),
                wp(t(Anchor.PRE_FRAC, param = 0.875), 0.55f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.95f),
                wp(T_S, 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.LOOP_ROLL,
            param1 = 8.0,
            startTime = t(Anchor.SWAP_BACK_ZERO, param = 2.0),
            endTime = T_S,
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.ECHO,
            param1 = 0.5, // 1/2 beat
            param2 = 0.65f,
            param3 = 0.55f,
            wetCurve = listOf(
                wp(t(Anchor.SWAP, beats = -1.0, min = Anchor.ZERO), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.85f),
                wp(t(Anchor.SWAP, sec = 0.12, max = Anchor.OUT_STOP), 0f),
            ),
        ),
    )

    val F1 = Recipe(
        id = RecipeId.F1,
        displayName = "Riser drop",
        minLevel = DjLevel.FESTIVAL,
        barRange = 16..16,
        stretchCap = 0.08,
        genreWeights = mapOf(
            GenreClass.DANCE to 0.34,
            GenreClass.POP to 0.12,
            GenreClass.UNKNOWN to 0.16,
        ),
        minBeatConf = 0.50,
        minOverlapBars = 4.0,
        swapsBass = true,
        cut = true,
        hasDropExemption = true,
        swapRule = SwapWindowRule.DROP_OR_LATE,
        move = Move.EDM_BUILD_DROP,
        fxSummary = "Riser + Gate + Echo + Loop roll",
        steps = f1FxSteps + listOf(
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 1.0), peak = 0.85f, holdUntil = t(Anchor.SWAP, sec = -0.02, min = Anchor.IN_AUDIBLE)),
            RecipeStep.LaneCurve(DeckTarget.IN, EnvKind.STEM_DRUMS, CurveShape.LINEAR, listOf(wp(T_ZERO, 1f))),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.STEM_VOCALS, CurveShape.LINEAR,
                listOf(wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0f), wp(t(Anchor.SWAP, sec = 0.02), 1f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.STEM_OTHER, CurveShape.LINEAR,
                listOf(wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0f), wp(t(Anchor.SWAP, sec = 0.02), 1f)),
            ),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
        ),
        noStemSteps = f1FxSteps + listOf(
            inRise(T_A, t(Anchor.PRE_FRAC, param = 0.5), peak = 0.65f, holdUntil = t(Anchor.SWAP, sec = -0.02, min = Anchor.IN_AUDIBLE)),
            hpSweep(DeckTarget.IN, T_A, T_S, 350f, 20f, holdAtStart = false),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
        ),
    )

    private val f2Steps = listOf(
        outCutAt(T_S, leadSec = -0.02),
        RecipeStep.LaneCurve(
            DeckTarget.OUT, EnvKind.LOW_DB, CurveShape.LINEAR,
            listOf(wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0f), wp(T_S, -24f)),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.REVERB,
            param1 = 2.6,
            param2 = 0.45f,
            param3 = 0.5f,
            heavyReverbOnRockBallad = true,
            wetCurve = listOf(
                wp(t(Anchor.SWAP_BACK_ZERO, param = 2.0), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.5f),
                wp(T_STOP, 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.BRAKE,
            requiredCap = ChoreoCaps.BRAKE,
            fallbackFx = FxType.ECHO,
            param1 = 1.0, // 1 beat brake
            param2 = 0.55f,
            param3 = 0.5f,
            flag = false, // vinyl brake
            startTime = t(Anchor.SWAP, beats = -1.0, min = Anchor.ZERO),
            endTime = T_S,
            wetCurve = listOf(
                wp(t(Anchor.SWAP, beats = -1.0, min = Anchor.ZERO), 0f),
                wp(T_S, 1f),
            ),
            fallbackWetCurve = listOf(
                wp(t(Anchor.SWAP, beats = -1.0, min = Anchor.ZERO), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.8f),
                wp(t(Anchor.SWAP, sec = 0.12, max = Anchor.OUT_STOP), 0f),
            ),
        ),
        inSlamAtSwap(preOffsetSec = -0.015, postOffsetSec = 0.005),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.IN,
            fx = FxType.RISER,
            requiredCap = ChoreoCaps.RISER,
            param1 = 1.0, // 1-beat burst
            wetCurve = listOf(
                wp(t(Anchor.SWAP, sec = -0.005, min = Anchor.IN_AUDIBLE), 0f),
                wp(t(Anchor.SWAP, sec = 0.02, max = Anchor.RELEASE), 0.85f),
                wp(t(Anchor.SWAP, beats = 1.0, max = Anchor.RELEASE), 0f),
            ),
        ),
    )

    val F2 = Recipe(
        id = RecipeId.F2,
        displayName = "Brake slam",
        minLevel = DjLevel.FESTIVAL,
        barRange = 4..8,
        stretchCap = Double.POSITIVE_INFINITY,
        genreWeights = mapOf(
            GenreClass.HIPHOP to 0.30,
            GenreClass.ROCK to 0.35,
            GenreClass.BALLAD to 0.35,
            GenreClass.UNKNOWN to 0.12,
        ),
        needsGridConfidence = false,
        requiresBeatMatch = false,
        minBeatConf = 0.0,
        minOverlapBars = 0.0,
        swapsBass = false,
        cut = true,
        hasDropExemption = true,
        swapRule = SwapWindowRule.CUT_LATE,
        move = Move.BRAKE_CUT,
        fxSummary = "Reverb + Brake + Riser burst",
        steps = f2Steps,
        noStemSteps = f2Steps,
    )

    private val f3FxSteps = listOf(
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.ECHO,
            param1 = 0.5,
            param2 = 0.58f,
            param3 = 0.5f,
            wetCurve = listOf(
                wp(t(Anchor.POST_FRAC, param = 0.5), 0f),
                wp(t(Anchor.RELEASE, beats = -0.5, min = Anchor.SWAP), 0.85f),
                wp(T_R, 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.FLANGER,
            param2 = 0.6f,
            param3 = 0.5f,
            wetCurve = listOf(
                wp(t(Anchor.POST_FRAC, param = 0.5), 0f),
                wp(t(Anchor.RELEASE, beats = -0.25, min = Anchor.SWAP), 1f),
                wp(T_R, 0f),
            ),
        ),
    )

    val F3 = Recipe(
        id = RecipeId.F3,
        displayName = "Stem shuffle",
        minLevel = DjLevel.FESTIVAL,
        barRange = 24..24,
        stretchCap = 0.08,
        genreWeights = mapOf(
            GenreClass.DANCE to 0.30,
            GenreClass.POP to 0.30,
            GenreClass.UNKNOWN to 0.16,
        ),
        requiresStems = 4,
        stemModeMinStems = 4,
        minBeatConf = 0.50,
        minOverlapBars = 4.0,
        swapsBass = true,
        cut = false,
        swapRule = SwapWindowRule.BAR_9_OR_FIRST_THIRD,
        move = Move.DRUM_SWAP,
        fxSummary = "Echo + Flanger",
        steps = listOf(
            // 1-8 (A..S): IN drums replace OUT drums; 9-16 (S..p2): IN bass replaces OUT bass; 17-24 (p2..R): IN vocals/other in, OUT echoes & flangers out.
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 1.0), peak = 0.9f, holdUntil = t(Anchor.POST_FRAC, param = 0.5)),
            outFallFrom(t(Anchor.POST_FRAC, param = 0.5), T_R),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_DRUMS, CurveShape.LINEAR,
                listOf(wp(T_A, 1f), wp(t(Anchor.A_PLUS_CAPPED_S, param = 1.0), 0f)),
            ),
            RecipeStep.LaneCurve(DeckTarget.IN, EnvKind.STEM_DRUMS, CurveShape.LINEAR, listOf(wp(T_ZERO, 1f))),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.STEM_VOCALS, CurveShape.LINEAR,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.5), 0f), wp(t(Anchor.RELEASE, sec = -0.02), 1f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.STEM_OTHER, CurveShape.LINEAR,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.5), 0f), wp(t(Anchor.RELEASE, sec = -0.02), 1f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_VOCALS, CurveShape.EQUAL_POWER_FALL,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.5), 1f), wp(T_R, 0f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_OTHER, CurveShape.EQUAL_POWER_FALL,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.5), 1f), wp(T_R, 0f)),
            ),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
        ) + f3FxSteps,
        noStemSteps = listOf(
            RecipeStep.EnableIsolator,
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 1.0), peak = 0.72f, holdUntil = t(Anchor.POST_FRAC, param = 0.5)),
            outFallFrom(t(Anchor.POST_FRAC, param = 0.5), T_R),
            hpSweep(DeckTarget.IN, T_A, T_S, 280f, 20f, holdAtStart = false),
            hpSweep(DeckTarget.OUT, T_S, T_R, 20f, 500f),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.MID_DB, CurveShape.LINEAR,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.5), 0f), wp(T_R, -12f)),
            ),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
        ) + f3FxSteps,
    )

    private val f4FxSteps = listOf(
        RecipeStep.ConfigureFx(
            deck = DeckTarget.IN,
            fx = FxType.PUMP,
            requiredCap = ChoreoCaps.PUMP,
            flag = true, // fullBand: IN instrumental pumps gently under OUT vocal
            wetCurve = listOf(
                wp(T_S, 0f),
                wp(t(Anchor.POST_FRAC, param = 0.25), 0.45f),
                wp(t(Anchor.POST_FRAC, param = 0.65), 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.REVERB,
            param1 = 2.5,
            param2 = 0.5f,
            param3 = 0.45f,
            wetCurve = listOf(
                wp(t(Anchor.POST_FRAC, param = 0.55), 0f),
                wp(t(Anchor.RELEASE, beats = -0.4, min = Anchor.SWAP), 0.55f),
                wp(T_STOP, 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.ECHO,
            param1 = 0.5,
            param2 = 0.6f,
            param3 = 0.5f,
            wetCurve = listOf(
                wp(t(Anchor.POST_FRAC, param = 0.65), 0f),
                wp(t(Anchor.RELEASE, beats = -0.3, min = Anchor.SWAP), 0.85f),
                wp(T_R, 0f),
            ),
        ),
    )

    val F4 = Recipe(
        id = RecipeId.F4,
        displayName = "Acapella mashup",
        minLevel = DjLevel.FESTIVAL,
        barRange = 16..32,
        stretchCap = 0.08,
        genreWeights = mapOf(
            GenreClass.POP to 0.30,
            GenreClass.HIPHOP to 0.28,
            GenreClass.DANCE to 0.14,
        ),
        requiresHarmony = true,
        requiresStems = 2,
        stemModeMinStems = 2,
        minBeatConf = 0.50,
        minOverlapBars = 4.0,
        swapsBass = true,
        cut = false,
        swapRule = SwapWindowRule.EARLY_PHRASE,
        move = Move.ACAPELLA_OVER_INSTRUMENTAL,
        fxSummary = "Pump + Reverb + Echo",
        steps = listOf(
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 1.0)),
            outFallFrom(t(Anchor.POST_FRAC, param = 0.65), T_R),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_OTHER, CurveShape.EQUAL_POWER_FALL,
                listOf(wp(T_A, 1f), wp(T_S, 0f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_DRUMS, CurveShape.LINEAR,
                listOf(wp(T_A, 1f), wp(t(Anchor.A_PLUS_CAPPED_S, param = 0.5), 0f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.STEM_VOCALS, CurveShape.LINEAR,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.65), 0f), wp(t(Anchor.RELEASE, sec = -0.02), 1f)),
            ),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
        ) + f4FxSteps,
        noStemSteps = listOf(
            RecipeStep.EnableIsolator,
            inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 1.0)),
            outFallFrom(t(Anchor.POST_FRAC, param = 0.65), T_R),
            hpSweep(DeckTarget.OUT, T_A, T_S, 20f, 420f),
            RecipeStep.LaneCurve(
                DeckTarget.IN, EnvKind.MID_DB, CurveShape.LINEAR,
                listOf(wp(T_ZERO, -6f), wp(t(Anchor.POST_FRAC, param = 0.65), -6f), wp(t(Anchor.RELEASE, sec = -0.02), 0f)),
                holdNeutralAtStart = false,
            ),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.MID_DB, CurveShape.LINEAR,
                listOf(wp(t(Anchor.POST_FRAC, param = 0.65), 0f), wp(T_R, -12f)),
            ),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
        ) + f4FxSteps,
    )

    private val f5Steps = listOf(
        outCutAt(T_S, leadSec = -0.04),
        inRise(T_A, t(Anchor.SWAP, sec = -0.01, min = Anchor.IN_AUDIBLE), peak = 0.9f, holdUntil = t(Anchor.SWAP, sec = -0.01, min = Anchor.IN_AUDIBLE)),
        hpSweep(DeckTarget.OUT, T_A, T_S, 20f, 2000f),
        lpSweep(DeckTarget.IN, T_A, T_S, 250f, 20000f, holdAtStart = false),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.PHASER,
            requiredCap = ChoreoCaps.PHASER,
            fallbackFx = FxType.FLANGER,
            param1 = 8.0,
            param2 = 0.8f,
            param3 = 0.55f,
            wetCurve = listOf(
                wp(T_A, 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.7f),
                wp(T_S, 0f),
            ),
            fallbackWetCurve = listOf(
                wp(T_A, 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 1f),
                wp(T_S, 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.REVERB,
            param1 = 2.8,
            param2 = 0.45f,
            param3 = 0.5f,
            heavyReverbOnRockBallad = true,
            wetCurve = listOf(
                wp(T_A, 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.5f),
                wp(T_STOP, 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.GATE,
            requiredCap = ChoreoCaps.STUTTER,
            param1 = -4.0, // gated stutter in the last bar
            param2 = 0f,
            wetCurve = listOf(
                wp(t(Anchor.SWAP_BACK_A, param = 1.0), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.92f),
                wp(T_S, 0f),
            ),
        ),
    )

    val F5 = Recipe(
        id = RecipeId.F5,
        displayName = "Tunnel",
        minLevel = DjLevel.FESTIVAL,
        barRange = 16..16,
        stretchCap = Double.POSITIVE_INFINITY,
        genreWeights = mapOf(
            GenreClass.DANCE to 0.26,
            GenreClass.POP to 0.26,
            GenreClass.ROCK to 0.34,
            GenreClass.BALLAD to 0.34,
            GenreClass.UNKNOWN to 0.14,
        ),
        needsGridConfidence = false,
        requiresBeatMatch = false,
        minBeatConf = 0.0,
        minOverlapBars = 0.0,
        swapsBass = true,
        cut = true,
        hasDropExemption = true,
        swapRule = SwapWindowRule.CUT_LATE,
        move = Move.PHASER_DISSOLVE,
        fxSummary = "Phaser + Reverb + Stutter gate",
        steps = f5Steps + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, inLowBeforeDb = -12f),
        noStemSteps = f5Steps + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, inLowBeforeDb = -12f, useBassStems = false),
    )

    private val f6Shared = listOf(
        inRise(T_A, t(Anchor.A_PLUS_CAPPED_S, param = 2.0), peak = 0.9f, holdUntil = T_S),
        outFallFrom(T_S, T_R, steps = 16),
        hpSweep(DeckTarget.OUT, T_S, T_R, 20f, 360f),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.PUMP,
            requiredCap = ChoreoCaps.PUMP,
            flag = false, // low-band duck on IN kick
            wetCurve = listOf(
                wp(T_A, 0f),
                wp(t(Anchor.A_PLUS_CAPPED_S, param = 1.0), 0.8f),
                wp(T_S, 0.8f),
                wp(T_R, 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.FLANGER,
            param2 = 0.58f,
            param3 = 0.48f,
            wetCurve = listOf(
                wp(t(Anchor.RELEASE_BACK_S, param = 4.0), 0f),
                wp(t(Anchor.RELEASE, beats = -0.5, min = Anchor.SWAP), 1f),
                wp(T_R, 0f),
            ),
        ),
    )

    val F6 = Recipe(
        id = RecipeId.F6,
        displayName = "Pump rider",
        minLevel = DjLevel.FESTIVAL,
        barRange = 16..32,
        stretchCap = 0.08,
        genreWeights = mapOf(
            GenreClass.DANCE to 0.32,
            GenreClass.POP to 0.10,
        ),
        requiredCaps = ChoreoCaps.PUMP,
        minBeatConf = 0.50,
        minOverlapBars = 4.0,
        swapsBass = true,
        cut = false,
        swapRule = SwapWindowRule.MID_PHRASE,
        move = Move.PUMP_BLEND,
        fxSummary = "Pump (0.8) + Flanger",
        steps = f6Shared + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
        noStemSteps = f6Shared + RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
    )

    private val f7Steps = listOf(
        outCutAt(T_S, leadSec = -0.02),
        RecipeStep.LaneCurve(
            DeckTarget.OUT, EnvKind.LOW_DB, CurveShape.LINEAR,
            listOf(wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0f), wp(T_S, -24f)),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.BRAKE,
            requiredCap = ChoreoCaps.SPINBACK,
            param1 = 1.0, // 1 beat spinback
            flag = true,  // brakeSpinback = true
            startTime = t(Anchor.SWAP, beats = -1.0, min = Anchor.ZERO),
            endTime = T_S,
            wetCurve = listOf(
                wp(t(Anchor.SWAP, beats = -1.0, min = Anchor.ZERO), 0f),
                wp(T_S, 1f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.ECHO,
            param1 = 0.5,
            param2 = 0.55f,
            param3 = 0.5f,
            wetCurve = listOf(
                wp(t(Anchor.SWAP, beats = -1.5, min = Anchor.ZERO), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.85f),
                wp(t(Anchor.SWAP, sec = 0.12, max = Anchor.OUT_STOP), 0f),
            ),
        ),
        inSlamAtSwap(preOffsetSec = -0.015, postOffsetSec = 0.005),
    )

    val F7 = Recipe(
        id = RecipeId.F7,
        displayName = "Spinback cut",
        minLevel = DjLevel.FESTIVAL,
        barRange = 2..8,
        stretchCap = 0.08,
        genreWeights = mapOf(
            GenreClass.HIPHOP to 0.36,
            GenreClass.POP to 0.12,
        ),
        requiredCaps = ChoreoCaps.SPINBACK,
        needsGridConfidence = false,
        requiresBeatMatch = false,
        minBeatConf = 0.35,
        minOverlapBars = 0.0,
        swapsBass = false,
        cut = true,
        hasDropExemption = true,
        swapRule = SwapWindowRule.CUT_LATE,
        move = Move.BRAKE_CUT,
        fxSummary = "Spinback + Echo",
        steps = f7Steps,
        noStemSteps = f7Steps,
    )

    private val f8FxSteps = listOf(
        outFallFrom(t(Anchor.POST_FRAC, param = 0.5), T_R),
        inRise(T_A, t(Anchor.SWAP, sec = -0.02, min = Anchor.IN_AUDIBLE), peak = 0.85f, holdUntil = t(Anchor.SWAP, sec = -0.02, min = Anchor.IN_AUDIBLE)),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.RISER,
            requiredCap = ChoreoCaps.RISER,
            param1 = 16.0,
            wetCurve = listOf(
                wp(t(Anchor.SWAP_BACK_A, param = 4.0), 0f),
                wp(t(Anchor.SWAP, sec = -0.02, min = Anchor.ZERO), 0.75f),
                wp(T_S, 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.GATE,
            requiredCap = ChoreoCaps.GATE,
            param1 = 4.0,
            wetCurve = listOf(
                wp(t(Anchor.POST_FRAC, param = 0.4), 0f),
                wp(t(Anchor.RELEASE, beats = -0.5, min = Anchor.SWAP), 0.85f),
                wp(T_R, 0f),
            ),
        ),
        RecipeStep.ConfigureFx(
            deck = DeckTarget.OUT,
            fx = FxType.ECHO,
            param1 = 0.5,
            param2 = 0.6f,
            param3 = 0.5f,
            wetCurve = listOf(
                wp(t(Anchor.POST_FRAC, param = 0.6), 0f),
                wp(t(Anchor.RELEASE, beats = -0.25, min = Anchor.SWAP), 0.8f),
                wp(T_R, 0f),
            ),
        ),
    )

    val F8 = Recipe(
        id = RecipeId.F8,
        displayName = "Double drop",
        minLevel = DjLevel.FESTIVAL,
        barRange = 16..16,
        stretchCap = 0.04,
        genreWeights = mapOf(
            GenreClass.DANCE to 0.36,
        ),
        requiresDrops = true,
        requiresOutDrop = true,
        stemModeMinStems = 4,
        minBeatConf = 0.50,
        minOverlapBars = 4.0,
        swapsBass = true,
        cut = false,
        hasDropExemption = true,
        swapRule = SwapWindowRule.DROP_OR_MID,
        move = Move.EDM_BUILD_DROP,
        fxSummary = "Gate + Riser + Echo",
        steps = f8FxSteps + listOf(
            // Bass from IN only at aligned drop S; both drums stay up into S..postFrac, then OUT exits with gate + echo.
            RecipeStep.LaneCurve(DeckTarget.IN, EnvKind.STEM_DRUMS, CurveShape.LINEAR, listOf(wp(T_ZERO, 1f))),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_VOCALS, CurveShape.EQUAL_POWER_FALL,
                listOf(wp(T_S, 1f), wp(t(Anchor.POST_FRAC, param = 0.5), 0f)),
            ),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.STEM_OTHER, CurveShape.EQUAL_POWER_FALL,
                listOf(wp(T_S, 1f), wp(t(Anchor.POST_FRAC, param = 0.5), 0f)),
            ),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25),
        ),
        noStemSteps = f8FxSteps + listOf(
            RecipeStep.EnableIsolator,
            hpSweep(DeckTarget.OUT, T_S, T_R, 20f, 360f),
            RecipeStep.LaneCurve(
                DeckTarget.OUT, EnvKind.MID_DB, CurveShape.LINEAR,
                listOf(wp(T_S, 0f), wp(t(Anchor.POST_FRAC, param = 0.5), -9f)),
            ),
            RecipeStep.SwapLows(at = T_S, widthBeats = 0.25, useBassStems = false),
        ),
    )

    val ALL: List<Recipe> = listOf(
        S1, S2, S3, S4, S5,
        C1, C2, C3, C4, C5, C6, C7,
        F1, F2, F3, F4, F5, F6, F7, F8,
    )

    private val BY_ID: Map<RecipeId, Recipe> = ALL.associateBy { it.id }
    private val BY_LEVEL: Map<DjLevel, List<Recipe>> = DjLevel.values().associateWith { lvl ->
        ALL.filter { it.minLevel == lvl }
    }

    fun byId(id: RecipeId): Recipe = BY_ID.getValue(id)

    /** Strict level scoping: returns ONLY recipes belonging to [level]. */
    fun forLevel(level: DjLevel): List<Recipe> = BY_LEVEL.getValue(level)

    fun forMove(level: DjLevel, move: Move): List<Recipe> =
        forLevel(level).filter {
            it.move == move || (it.id == RecipeId.C6 && move == Move.FLANGER_DISSOLVE)
        }
}
