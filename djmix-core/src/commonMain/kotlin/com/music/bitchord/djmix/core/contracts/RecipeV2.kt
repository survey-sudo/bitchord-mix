package com.music.bitchord.djmix.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Timeline anchor against which recipe step times are resolved. */
@Serializable
enum class RecipeAnchor {
    ZERO,
    IN_AUDIBLE,
    SWAP,
    RELEASE,
    OUT_STOP,
    PRE_FRAC,
    POST_FRAC,
    OVERLAP_FRAC,
    A_PLUS_CAPPED_S,
    S_PLUS_CAPPED_R,
    SWAP_BACK_A,
    SWAP_BACK_ZERO,
    RELEASE_BACK_S,
    VOCAL_ENTRY,
    FADE_START,
}

/** Interpolation curve law for an envelope step. */
@Serializable
enum class CurveShapeV2 {
    LINEAR,
    EXPONENTIAL,
    EQUAL_POWER_FALL,
    EQUAL_POWER_RISE,
}

/** Effect identifier configurable inside a [RecipeV2] step. */
@Serializable
enum class RecipeFxType {
    LOOP,
    ROLL,
    SWEEP,
    FLANGER,
    PHASER,
    RISER,
    IMPACT,
    BRAKE,
    TAPE_STOP,
    SPINBACK,
    ECHO,
    GATE,
    REVERB,
    PUMP,
    LOOP_ROLL,
    SVF_Q,
}

/** Anchor-relative timestamp inside a recipe step. */
@Serializable
data class StepTimeV2(
    val anchor: RecipeAnchor,
    val param: Double = 0.0,
    val offsetBars: Double = 0.0,
    val offsetBeats: Double = 0.0,
    val offsetSec: Double = 0.0,
    val minAt: RecipeAnchor? = null,
    val maxAt: RecipeAnchor? = null,
)

/** Automation waypoint in a [RecipeStepV2]. */
@Serializable
data class WaypointV2(
    val time: StepTimeV2,
    val value: Float,
    val scaleByFxDepth: Boolean = false,
    val depthBase: Float = 0f,
)

/** Polymorphic step inside a JSON-backed [RecipeV2] choreography. */
@Serializable
sealed interface RecipeStepV2 {
    /** Envelope automation curve on one deck. */
    @Serializable
    @SerialName("lane_curve")
    data class LaneCurve(
        val deck: DeckTarget,
        val kind: EnvelopeKind,
        val shape: CurveShapeV2 = CurveShapeV2.LINEAR,
        val points: List<WaypointV2>,
        val steps: Int = 12,
        val holdNeutralAtStart: Boolean = true,
        val ensureNeutralAtReleaseForIn: Boolean = true,
    ) : RecipeStepV2

    /** Low-band EQ or bass-stem swap centred on [at]. */
    @Serializable
    @SerialName("swap_lows")
    data class SwapLows(
        val at: StepTimeV2 = StepTimeV2(RecipeAnchor.SWAP),
        val widthBeats: Double = 0.25,
        val inLowBeforeDb: Float = -24f,
        val outLowAfterDb: Float = -24f,
        val useBassStems: Boolean = true,
    ) : RecipeStepV2

    /** Enables the 3-band LR4 isolator on both decks. */
    @Serializable
    @SerialName("enable_isolator")
    data object EnableIsolator : RecipeStepV2

    /** Configures an effect processor and optional wet/depth automation curve. */
    @Serializable
    @SerialName("configure_fx")
    data class ConfigureFx(
        val deck: DeckTarget,
        val fx: RecipeFxType,
        val requiredCap: FxCapability? = null,
        val fallbackFx: RecipeFxType? = null,
        val params: Map<String, Double> = emptyMap(),
        val flag: Boolean = false,
        val startTime: StepTimeV2? = null,
        val endTime: StepTimeV2? = null,
        val wetCurve: List<WaypointV2>? = null,
        val fallbackWetCurve: List<WaypointV2>? = null,
    ) : RecipeStepV2
}

/** Eligibility requirements for selecting a [RecipeV2]. */
@Serializable
data class RecipeRequirementsV2(
    val maxTempoGap: Double = 0.08,
    val requiresBeatMatch: Boolean = true,
    val requiresHarmony: Boolean = false,
    val genreAffinity: Map<String, Double> = emptyMap(),
    val requiresDrops: Boolean = false,
    val requiresOutDrop: Boolean = false,
    val requiresOutLoop: Boolean = false,
    val requiredStems: Set<DeckStemRequirement> = emptySet(),
    val minStemCount: Int = 0,
    val minGridConfidence: Float = 0.45f,
    val minOverlapBars: Double = 2.0,
    val requiredCapabilities: Set<FxCapability> = emptySet(),
    val requiresTempoOrGenreJump: Boolean = false,
)

/** Musical placement preferences for candidate exit/entry scoring. */
@Serializable
data class PlacementPrefsV2(
    val swapRule: String = "MID_PHRASE",
    val preferOutroExit: Boolean = true,
    val preferIntroEntry: Boolean = true,
    val preferDropEntry: Boolean = false,
    val maxEntrySkipBars: Int = 0,
    val avoidVocalExit: Boolean = true,
    val avoidVocalEntry: Boolean = true,
    val avoidBuildExit: Boolean = true,
    val preferPostSwapPhraseBars: Int = 4,
)

/**
 * Data-driven recipe contract (`schema = 2`, B5). Recipes are loaded from `recipes/*.json`
 * and interpreted by `djmix-core/recipes` into [TransitionScriptV1] instances.
 */
@Serializable
data class RecipeV2(
    val schema: Int = 2,
    val id: String,
    val name: String,
    val description: String = "",
    val level: DjLevel,
    val family: RecipeFamily,
    val abruptness: AbruptnessClass,
    val requirements: RecipeRequirementsV2 = RecipeRequirementsV2(),
    val lengthOptionsBars: List<Int> = listOf(8, 16),
    val placementPrefs: PlacementPrefsV2 = PlacementPrefsV2(),
    val steps: List<RecipeStepV2> = emptyList(),
    val noStemSteps: List<RecipeStepV2> = emptyList(),
    val energyDelta: Float = 0f,
    val enabled: Boolean = true,
) {
    init {
        require(schema == 2) { "RecipeV2 requires schema == 2, got $schema" }
        require(id.isNotBlank()) { "RecipeV2 id must not be blank" }
        require(name.isNotBlank()) { "RecipeV2 name must not be blank" }
        require(lengthOptionsBars.isNotEmpty() && lengthOptionsBars.all { it > 0 }) {
            "lengthOptionsBars must be non-empty positive integers"
        }
    }
}
