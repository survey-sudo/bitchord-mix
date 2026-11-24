package com.music.bitchord.djmix.core.planner

import com.music.bitchord.djmix.core.contracts.AbruptnessClass
import com.music.bitchord.djmix.core.contracts.DeckStemRequirement
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.EnvelopeKind
import com.music.bitchord.djmix.core.contracts.RecipeFamily
import com.music.bitchord.djmix.core.contracts.RecipeFxType
import com.music.bitchord.djmix.core.contracts.RecipeStepV2
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.StemKind
import kotlin.math.max

/**
 * Built-in catalog of AE3 transition recipes (`S1..S6`, `C1..C9`, `F1..F11`) aligned with
 * Part A §5 and Part B5.5. External [RecipeV2] JSON documents in M4 override or extend
 * these descriptors via [mergeOverrides].
 */
object BuiltInRecipeCatalog {

    private fun outStem(kind: StemKind) = DeckStemRequirement(DeckTarget.OUT, kind)
    private fun inStem(kind: StemKind) = DeckStemRequirement(DeckTarget.IN, kind)

    // ---- Subtle (S1..S6) -----------------------------------------------------------------------

    val S1 = RecipeCatalogEntry(
        id = "S1",
        displayName = "Smooth blend",
        description = "Beat-matched equal-power blend with gentle low-end handoff",
        level = DjLevel.SUBTLE,
        family = RecipeFamily.BLEND,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = emptySet(),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.45f,
        maxTempoDeltaPct = 3.0,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 16,
        genreAffinity = mapOf("POP" to 0.22, "DANCE" to 0.10, "ROCK" to 0.12, "UNKNOWN" to 0.15),
    )

    val S2 = RecipeCatalogEntry(
        id = "S2",
        displayName = "Bass swap",
        description = "Equal-power blend trading bass stems cleanly on the downbeat",
        level = DjLevel.SUBTLE,
        family = RecipeFamily.SWAP,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = emptySet(),
        requiredStems = setOf(outStem(StemKind.BASS), inStem(StemKind.BASS)),
        hasNoStemVariant = true,
        minGridConfidence = 0.45f,
        maxTempoDeltaPct = 3.0,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 16,
        genreAffinity = mapOf("DANCE" to 0.22, "POP" to 0.12, "HIPHOP" to 0.10, "UNKNOWN" to 0.12),
    )

    val S3 = RecipeCatalogEntry(
        id = "S3",
        displayName = "Muffle-in",
        description = "Incoming track opens from a warm resonant low-pass muffle",
        level = DjLevel.SUBTLE,
        family = RecipeFamily.FX,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = setOf(RecipeFxType.SWEEP, RecipeFxType.SVF_Q),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.45f,
        maxTempoDeltaPct = 3.0,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 8,
        minOverlapBars = 3,
        genreAffinity = mapOf("POP" to 0.16, "DANCE" to 0.10, "UNKNOWN" to 0.10),
    )

    val S4 = RecipeCatalogEntry(
        id = "S4",
        displayName = "Echo out",
        description = "Tempo-synced ping-pong echo throw releasing cleanly into incoming bar 1",
        level = DjLevel.SUBTLE,
        family = RecipeFamily.CUT,
        abruptness = AbruptnessClass.PUNCHY,
        fxTags = setOf(RecipeFxType.ECHO, RecipeFxType.SWEEP),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.25f,
        maxTempoDeltaPct = Double.POSITIVE_INFINITY,
        requiresBeatMatch = false,
        defaultOverlapBars = 4,
        minPreRollBars = 2,
        maxPreRollBars = 8,
        swapsBass = false,
        isCut = true,
        minOverlapBars = 1,
        genreAffinity = mapOf("HIPHOP" to 0.24, "ROCK" to 0.18, "POP" to 0.08),
    )

    val S5 = RecipeCatalogEntry(
        id = "S5",
        displayName = "Simple fade",
        description = "Restrained equal-power fade for ballads and drifting tempi",
        level = DjLevel.SUBTLE,
        family = RecipeFamily.BLEND,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = emptySet(),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.0f,
        maxTempoDeltaPct = Double.POSITIVE_INFINITY,
        requiresBeatMatch = false,
        defaultOverlapBars = 4,
        minPreRollBars = 2,
        maxPreRollBars = 8,
        swapsBass = false,
        minOverlapBars = 1,
        genreAffinity = mapOf("BALLAD" to 0.48, "ROCK" to 0.15),
    )

    val S6 = RecipeCatalogEntry(
        id = "S6",
        displayName = "Breakdown blend",
        description = "Clean dry blend landing into the incoming breakdown",
        level = DjLevel.SUBTLE,
        family = RecipeFamily.BLEND,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = emptySet(),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.45f,
        maxTempoDeltaPct = 3.0,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 16,
        preferBreakdownExit = true,
        genreAffinity = mapOf("DANCE" to 0.18, "POP" to 0.14, "UNKNOWN" to 0.12),
    )

    // ---- Club (C1..C9) -------------------------------------------------------------------------

    val C1 = RecipeCatalogEntry(
        id = "C1",
        displayName = "Drum swap",
        description = "Drums and bass trade cleanly across bars with an echo throw",
        level = DjLevel.CLUB,
        family = RecipeFamily.SWAP,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = setOf(RecipeFxType.ECHO),
        requiredStems = setOf(outStem(StemKind.DRUMS), outStem(StemKind.BASS), inStem(StemKind.DRUMS), inStem(StemKind.BASS)),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 6.0,
        defaultOverlapBars = 16,
        minPreRollBars = 8,
        maxPreRollBars = 16,
        minOverlapBars = 4,
        genreAffinity = mapOf("DANCE" to 0.22, "HIPHOP" to 0.16, "POP" to 0.10),
    )

    val C2 = RecipeCatalogEntry(
        id = "C2",
        displayName = "Filter wash + echo",
        description = "Resonant high-pass filter wash and tempo echo bridging any tempo gap",
        level = DjLevel.CLUB,
        family = RecipeFamily.FX,
        abruptness = AbruptnessClass.PUNCHY,
        fxTags = setOf(RecipeFxType.SWEEP, RecipeFxType.REVERB, RecipeFxType.ECHO, RecipeFxType.SVF_Q),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.0f,
        maxTempoDeltaPct = Double.POSITIVE_INFINITY,
        requiresBeatMatch = false,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 16,
        isCut = true,
        minOverlapBars = 2,
        genreAffinity = mapOf("HIPHOP" to 0.20, "ROCK" to 0.25, "BALLAD" to 0.22, "DANCE" to 0.14, "POP" to 0.14),
    )

    val C3 = RecipeCatalogEntry(
        id = "C3",
        displayName = "Acapella ride",
        description = "Outgoing vocals ride cleanly over the incoming groove then echo out",
        level = DjLevel.CLUB,
        family = RecipeFamily.SWAP,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = setOf(RecipeFxType.ECHO),
        requiredStems = setOf(outStem(StemKind.VOCALS), outStem(StemKind.BASS)),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 6.0,
        requiresHarmony = true,
        defaultOverlapBars = 16,
        minPreRollBars = 8,
        maxPreRollBars = 16,
        minOverlapBars = 4,
        avoidVocalExit = false,
        genreAffinity = mapOf("POP" to 0.26, "DANCE" to 0.16, "HIPHOP" to 0.16),
    )

    val C4 = RecipeCatalogEntry(
        id = "C4",
        displayName = "Instrumental bed",
        description = "Outgoing instrumental ducks vocals cleanly for the incoming vocal entry",
        level = DjLevel.CLUB,
        family = RecipeFamily.SWAP,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = setOf(RecipeFxType.SWEEP),
        requiredStems = setOf(outStem(StemKind.VOCALS), outStem(StemKind.BASS)),
        hasNoStemVariant = true,
        minGridConfidence = 0.45f,
        maxTempoDeltaPct = 6.0,
        defaultOverlapBars = 16,
        minPreRollBars = 4,
        maxPreRollBars = 16,
        genreAffinity = mapOf("POP" to 0.24, "HIPHOP" to 0.20, "DANCE" to 0.14),
    )

    val C5 = RecipeCatalogEntry(
        id = "C5",
        displayName = "Loop extend",
        description = "Beat-locked outgoing outro loop holds under the incoming intro",
        level = DjLevel.CLUB,
        family = RecipeFamily.BLEND,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = setOf(RecipeFxType.LOOP, RecipeFxType.SWEEP),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        requiresOutLoop = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 6.0,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 16,
        minOverlapBars = 4,
        genreAffinity = mapOf("DANCE" to 0.20, "POP" to 0.14, "UNKNOWN" to 0.12),
    )

    val C6 = RecipeCatalogEntry(
        id = "C6",
        displayName = "Phaser dissolve",
        description = "Tempo-synced phaser and stereo flanger dissolve into the mix",
        level = DjLevel.CLUB,
        family = RecipeFamily.FX,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = setOf(RecipeFxType.PHASER, RecipeFxType.FLANGER, RecipeFxType.SWEEP),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 6.0,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 16,
        minOverlapBars = 4,
        genreAffinity = mapOf("DANCE" to 0.18, "POP" to 0.14, "ROCK" to 0.14),
    )

    val C7 = RecipeCatalogEntry(
        id = "C7",
        displayName = "Drop on the beat",
        description = "Tightening snare roll and sweep landing right on the incoming drop",
        level = DjLevel.CLUB,
        family = RecipeFamily.DROP,
        abruptness = AbruptnessClass.PUNCHY,
        fxTags = setOf(RecipeFxType.ROLL, RecipeFxType.LOOP_ROLL, RecipeFxType.RISER, RecipeFxType.SWEEP),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 6.0,
        isDropRecipe = true,
        requiresInDrop = true,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 32,
        swapsBass = false,
        isCut = true,
        genreAffinity = mapOf("DANCE" to 0.34, "POP" to 0.14),
    )

    val C8 = RecipeCatalogEntry(
        id = "C8",
        displayName = "Loop roll out",
        description = "Beat-locked loop tightens into a roll and cuts cleanly on bar 1",
        level = DjLevel.CLUB,
        family = RecipeFamily.FX,
        abruptness = AbruptnessClass.PUNCHY,
        fxTags = setOf(RecipeFxType.LOOP, RecipeFxType.ROLL, RecipeFxType.LOOP_ROLL, RecipeFxType.SWEEP),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        requiresOutLoop = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 6.0,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 16,
        isCut = true,
        minOverlapBars = 4,
        genreAffinity = mapOf("DANCE" to 0.22, "HIPHOP" to 0.14),
    )

    val C9 = RecipeCatalogEntry(
        id = "C9",
        displayName = "Filter ping-pong",
        description = "Alternating bar-locked HPF/LPF sweeps with jet flanger accent",
        level = DjLevel.CLUB,
        family = RecipeFamily.FX,
        abruptness = AbruptnessClass.PUNCHY,
        fxTags = setOf(RecipeFxType.SWEEP, RecipeFxType.FLANGER, RecipeFxType.ECHO),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 6.0,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 16,
        minOverlapBars = 4,
        genreAffinity = mapOf("DANCE" to 0.20, "POP" to 0.16),
    )

    // ---- Festival (F1..F11) --------------------------------------------------------------------

    val F1 = RecipeCatalogEntry(
        id = "F1",
        displayName = "Riser drop",
        description = "Multi-layer riser, beat gate, and loop roll launching into the drop",
        level = DjLevel.FESTIVAL,
        family = RecipeFamily.DROP,
        abruptness = AbruptnessClass.PUNCHY,
        fxTags = setOf(
            RecipeFxType.RISER,
            RecipeFxType.GATE,
            RecipeFxType.ECHO,
            RecipeFxType.ROLL,
            RecipeFxType.LOOP_ROLL,
            RecipeFxType.SWEEP,
            RecipeFxType.IMPACT,
        ),
        requiredStems = setOf(inStem(StemKind.DRUMS), inStem(StemKind.BASS), inStem(StemKind.VOCALS)),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 8.0,
        isDropRecipe = true,
        defaultOverlapBars = 16,
        minPreRollBars = 4,
        maxPreRollBars = 64,
        isCut = true,
        minOverlapBars = 4,
        genreAffinity = mapOf("DANCE" to 0.34, "POP" to 0.12, "UNKNOWN" to 0.16),
    )

    val F2 = RecipeCatalogEntry(
        id = "F2",
        displayName = "Brake slam",
        description = "Exponential turntable brake with LPF roll-off slamming on the 1",
        level = DjLevel.FESTIVAL,
        family = RecipeFamily.CUT,
        abruptness = AbruptnessClass.ABRUPT,
        fxTags = setOf(RecipeFxType.BRAKE, RecipeFxType.REVERB, RecipeFxType.RISER),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.0f,
        maxTempoDeltaPct = Double.POSITIVE_INFINITY,
        requiresBeatMatch = false,
        defaultOverlapBars = 4,
        minPreRollBars = 2,
        maxPreRollBars = 8,
        swapsBass = false,
        isCut = true,
        minOverlapBars = 1,
        genreAffinity = mapOf("HIPHOP" to 0.24, "ROCK" to 0.26, "BALLAD" to 0.20, "UNKNOWN" to 0.12),
    )

    val F3 = RecipeCatalogEntry(
        id = "F3",
        displayName = "Stem shuffle",
        description = "Staggered drum, bass, and vocal stem swap with echo and flanger",
        level = DjLevel.FESTIVAL,
        family = RecipeFamily.SWAP,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = setOf(RecipeFxType.ECHO, RecipeFxType.FLANGER, RecipeFxType.SWEEP),
        requiredStems = setOf(outStem(StemKind.DRUMS), outStem(StemKind.BASS), inStem(StemKind.DRUMS), inStem(StemKind.BASS)),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 8.0,
        defaultOverlapBars = 24,
        minPreRollBars = 8,
        maxPreRollBars = 24,
        minOverlapBars = 4,
        genreAffinity = mapOf("DANCE" to 0.30, "POP" to 0.28, "UNKNOWN" to 0.16),
    )

    val F4 = RecipeCatalogEntry(
        id = "F4",
        displayName = "Acapella mashup",
        description = "Outgoing vocal mashed over incoming beat with pump, reverb, and echo",
        level = DjLevel.FESTIVAL,
        family = RecipeFamily.SWAP,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = setOf(RecipeFxType.PUMP, RecipeFxType.REVERB, RecipeFxType.ECHO),
        requiredStems = setOf(outStem(StemKind.VOCALS), outStem(StemKind.BASS)),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 8.0,
        requiresHarmony = true,
        defaultOverlapBars = 16,
        minPreRollBars = 8,
        maxPreRollBars = 32,
        minOverlapBars = 4,
        avoidVocalExit = false,
        genreAffinity = mapOf("POP" to 0.28, "HIPHOP" to 0.26, "DANCE" to 0.14),
    )

    val F5 = RecipeCatalogEntry(
        id = "F5",
        displayName = "Tunnel",
        description = "Phaser, reverb, and opposing filter tunnel bridging tempo or grid gaps",
        level = DjLevel.FESTIVAL,
        family = RecipeFamily.FX,
        abruptness = AbruptnessClass.PUNCHY,
        fxTags = setOf(RecipeFxType.PHASER, RecipeFxType.FLANGER, RecipeFxType.REVERB, RecipeFxType.GATE, RecipeFxType.SWEEP),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.0f,
        maxTempoDeltaPct = Double.POSITIVE_INFINITY,
        requiresBeatMatch = false,
        defaultOverlapBars = 16,
        minPreRollBars = 4,
        maxPreRollBars = 16,
        isCut = true,
        minOverlapBars = 2,
        genreAffinity = mapOf("DANCE" to 0.26, "POP" to 0.26, "ROCK" to 0.30, "BALLAD" to 0.28, "UNKNOWN" to 0.18),
    )

    val F6 = RecipeCatalogEntry(
        id = "F6",
        displayName = "Pump rider",
        description = "Beat-locked sidechain pump and jet flanger across a long phrase blend",
        level = DjLevel.FESTIVAL,
        family = RecipeFamily.FX,
        abruptness = AbruptnessClass.SMOOTH,
        fxTags = setOf(RecipeFxType.PUMP, RecipeFxType.FLANGER, RecipeFxType.SWEEP),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 8.0,
        defaultOverlapBars = 16,
        minPreRollBars = 8,
        maxPreRollBars = 32,
        minOverlapBars = 4,
        genreAffinity = mapOf("DANCE" to 0.30, "POP" to 0.14),
    )

    val F7 = RecipeCatalogEntry(
        id = "F7",
        displayName = "Spinback cut",
        description = "Reverse platter spinback and echo throw into the incoming 1",
        level = DjLevel.FESTIVAL,
        family = RecipeFamily.CUT,
        abruptness = AbruptnessClass.ABRUPT,
        fxTags = setOf(RecipeFxType.SPINBACK, RecipeFxType.ECHO),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.0f,
        maxTempoDeltaPct = Double.POSITIVE_INFINITY,
        requiresBeatMatch = false,
        defaultOverlapBars = 4,
        minPreRollBars = 2,
        maxPreRollBars = 8,
        swapsBass = false,
        isCut = true,
        minOverlapBars = 1,
        genreAffinity = mapOf("HIPHOP" to 0.32, "POP" to 0.12),
    )

    val F8 = RecipeCatalogEntry(
        id = "F8",
        displayName = "Double drop",
        description = "Aligned drops collide on bar 1 with riser, gate, and echo",
        level = DjLevel.FESTIVAL,
        family = RecipeFamily.DROP,
        abruptness = AbruptnessClass.PUNCHY,
        fxTags = setOf(RecipeFxType.RISER, RecipeFxType.GATE, RecipeFxType.ECHO, RecipeFxType.IMPACT),
        requiredStems = setOf(outStem(StemKind.BASS), inStem(StemKind.DRUMS), inStem(StemKind.BASS)),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 4.0,
        isDropRecipe = true,
        requiresInDrop = true,
        requiresOutDrop = true,
        defaultOverlapBars = 16,
        minPreRollBars = 4,
        maxPreRollBars = 64,
        minOverlapBars = 4,
        genreAffinity = mapOf("DANCE" to 0.36),
    )

    val F9 = RecipeCatalogEntry(
        id = "F9",
        displayName = "Backspin into drop",
        description = "Accelerating backspin releasing with impact onto the incoming groove",
        level = DjLevel.FESTIVAL,
        family = RecipeFamily.CUT,
        abruptness = AbruptnessClass.ABRUPT,
        fxTags = setOf(RecipeFxType.SPINBACK, RecipeFxType.ECHO, RecipeFxType.IMPACT),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.0f,
        maxTempoDeltaPct = Double.POSITIVE_INFINITY,
        requiresBeatMatch = false,
        defaultOverlapBars = 4,
        minPreRollBars = 2,
        maxPreRollBars = 8,
        swapsBass = false,
        isCut = true,
        minOverlapBars = 1,
        genreAffinity = mapOf("HIPHOP" to 0.28, "DANCE" to 0.16),
    )

    val F10 = RecipeCatalogEntry(
        id = "F10",
        displayName = "Echo freeze",
        description = "Stutter-freeze echo on the final beat launching cleanly into the drop",
        level = DjLevel.FESTIVAL,
        family = RecipeFamily.FX,
        abruptness = AbruptnessClass.PUNCHY,
        fxTags = setOf(RecipeFxType.ECHO, RecipeFxType.GATE, RecipeFxType.LOOP, RecipeFxType.ROLL, RecipeFxType.SWEEP),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 8.0,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 16,
        isCut = true,
        minOverlapBars = 2,
        genreAffinity = mapOf("DANCE" to 0.24, "POP" to 0.20, "HIPHOP" to 0.18),
    )

    val F11 = RecipeCatalogEntry(
        id = "F11",
        displayName = "Riser + impact cut",
        description = "Tempo-synced riser build cutting cleanly into an impact hit on the drop",
        level = DjLevel.FESTIVAL,
        family = RecipeFamily.DROP,
        abruptness = AbruptnessClass.PUNCHY,
        fxTags = setOf(RecipeFxType.RISER, RecipeFxType.IMPACT, RecipeFxType.ROLL, RecipeFxType.SWEEP),
        requiredStems = emptySet(),
        hasNoStemVariant = true,
        minGridConfidence = 0.50f,
        maxTempoDeltaPct = 8.0,
        isDropRecipe = true,
        defaultOverlapBars = 8,
        minPreRollBars = 4,
        maxPreRollBars = 64,
        isCut = true,
        minOverlapBars = 2,
        genreAffinity = mapOf("DANCE" to 0.28, "POP" to 0.18),
    )

    /** Complete 26-recipe catalog (`S1..S6`, `C1..C9`, `F1..F11`). */
    val entries: List<RecipeCatalogEntry> = listOf(
        S1, S2, S3, S4, S5, S6,
        C1, C2, C3, C4, C5, C6, C7, C8, C9,
        F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11,
    )

    private val byIdMap: Map<String, RecipeCatalogEntry> = entries.associateBy { it.id }

    fun byId(id: String): RecipeCatalogEntry? = byIdMap[id]

    /** Converts a JSON-backed [RecipeV2] into a planner [RecipeCatalogEntry] for M4 overrides. */
    fun fromRecipeV2(recipe: RecipeV2): RecipeCatalogEntry {
        val fx = buildSet {
            for (step in recipe.steps) {
                when (step) {
                    is RecipeStepV2.ConfigureFx -> add(step.fx)
                    is RecipeStepV2.LaneCurve -> if (
                        step.kind == EnvelopeKind.HIGH_PASS_HZ ||
                        step.kind == EnvelopeKind.LOW_PASS_HZ ||
                        step.kind == EnvelopeKind.FILTER_KNOB
                    ) {
                        add(RecipeFxType.SWEEP)
                    }
                    else -> Unit
                }
            }
        }
        val req = recipe.requirements
        val prefs = recipe.placementPrefs
        val swapsBass = recipe.steps.any { it is RecipeStepV2.SwapLows } || recipe.family == RecipeFamily.SWAP
        val isCut = recipe.family == RecipeFamily.CUT || recipe.abruptness == AbruptnessClass.ABRUPT
        val minBars = recipe.lengthOptionsBars.minOrNull()?.coerceAtLeast(1) ?: 4
        val maxBars = prefs.maxEntrySkipBars.takeIf { it > 0 } ?: (recipe.lengthOptionsBars.maxOrNull() ?: 16)
        return RecipeCatalogEntry(
            id = recipe.id,
            displayName = recipe.name,
            description = recipe.description,
            level = recipe.level,
            family = recipe.family,
            abruptness = recipe.abruptness,
            fxTags = fx,
            requiredStems = req.requiredStems,
            hasNoStemVariant = recipe.noStemSteps.isNotEmpty() || req.requiredStems.isEmpty(),
            minGridConfidence = req.minGridConfidence,
            maxTempoDeltaPct = if (!req.requiresBeatMatch) Double.POSITIVE_INFINITY else req.maxTempoGap * 100.0,
            isDropRecipe = req.requiresDrops || recipe.family == RecipeFamily.DROP || prefs.preferDropEntry,
            defaultOverlapBars = recipe.lengthOptionsBars.firstOrNull() ?: 8,
            minPreRollBars = minBars,
            maxPreRollBars = max(minBars, maxBars),
            requiresBeatMatch = req.requiresBeatMatch,
            requiresHarmony = req.requiresHarmony,
            requiresInDrop = req.requiresDrops,
            requiresOutDrop = req.requiresOutDrop,
            requiresOutLoop = req.requiresOutLoop,
            swapsBass = swapsBass,
            isCut = isCut,
            minOverlapBars = req.minOverlapBars.toInt().coerceAtLeast(1),
            preferOutroExit = prefs.preferOutroExit,
            preferIntroEntry = prefs.preferIntroEntry,
            avoidVocalExit = prefs.avoidVocalExit,
            avoidVocalEntry = prefs.avoidVocalEntry,
            avoidBuildExit = prefs.avoidBuildExit,
            preferPostSwapPhraseBars = prefs.preferPostSwapPhraseBars,
            genreAffinity = req.genreAffinity,
            enabled = recipe.enabled,
        )
    }

    /** Merges external M4 [RecipeV2] definitions over [entries] by matching recipe `id`. */
    fun mergeOverrides(overrides: List<RecipeV2>): List<RecipeCatalogEntry> {
        if (overrides.isEmpty()) return entries
        val overrideMap = LinkedHashMap<String, RecipeCatalogEntry>(overrides.size)
        for (override in overrides) {
            overrideMap[override.id] = fromRecipeV2(override)
        }
        val merged = ArrayList<RecipeCatalogEntry>(entries.size + overrideMap.size)
        val seen = HashSet<String>(entries.size + overrideMap.size)
        for (entry in entries) {
            val replacement = overrideMap[entry.id]
            seen += entry.id
            merged += (replacement ?: entry)
        }
        for ((id, extra) in overrideMap) {
            if (id !in seen) merged += extra
        }
        return merged
    }
}
