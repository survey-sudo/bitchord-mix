package com.music.bitchord.djmix.core.recipes

import com.music.bitchord.djmix.core.contracts.AbruptnessClass
import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.EnvelopeKind
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RecipeFxType
import com.music.bitchord.djmix.core.contracts.RecipeStepV2
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.StemKind
import com.music.bitchord.djmix.core.contracts.StemRegionRef
import com.music.bitchord.djmix.core.contracts.StepTimeV2
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1

/** Severity level of a [RecipeLintIssue]. */
enum class LintSeverity {
    ERROR,
    WARNING,
}

/** Diagnostic emitted by [RecipeLinter] for a recipe or recipe set. */
data class RecipeLintIssue(
    val recipeId: String,
    val severity: LintSeverity = LintSeverity.ERROR,
    val code: String,
    val message: String,
)

/** Aggregate output of [RecipeLinter]. */
data class RecipeLintReport(
    val issues: List<RecipeLintIssue>,
) {
    /** Blocking lint failures. */
    val errors: List<RecipeLintIssue> get() = issues.filter { it.severity == LintSeverity.ERROR }

    /** Non-blocking lint advisories. */
    val warnings: List<RecipeLintIssue> get() = issues.filter { it.severity == LintSeverity.WARNING }

    /** True when no [LintSeverity.ERROR] issues were found. */
    val passed: Boolean get() = errors.isEmpty()
}

/**
 * Static and simulation linter enforcing AE3 B5.3 & Part A §5 recipe invariants across
 * [RecipeV2] JSON documents before registration or runtime execution.
 */
object RecipeLinter {

    private val FESTIVAL_ONLY_ABRUPT_IDS: Set<String> = setOf("F2", "F7", "F9")

    /** Required Club tier FX types per Part A §5. */
    val REQUIRED_CLUB_FX: Set<RecipeFxType> = setOf(
        RecipeFxType.LOOP,
        RecipeFxType.ROLL,
        RecipeFxType.SWEEP,
        RecipeFxType.FLANGER,
        RecipeFxType.PHASER,
        RecipeFxType.ECHO,
    )

    /** Required Festival tier FX additions per Part A §5. */
    val REQUIRED_FESTIVAL_EXTRA_FX: Set<RecipeFxType> = setOf(
        RecipeFxType.RISER,
        RecipeFxType.IMPACT,
        RecipeFxType.BRAKE,
        RecipeFxType.SPINBACK,
        RecipeFxType.GATE,
    )

    /** Decodes and lints a single raw JSON string. */
    fun lintJson(jsonText: String): RecipeLintReport = when (val parsed = RecipeRegistry.parseJson(jsonText)) {
        is MixOutcome.Ok -> lintRecipe(parsed.value)
        is MixOutcome.Refused -> RecipeLintReport(
            listOf(RecipeLintIssue("unknown", LintSeverity.ERROR, "PARSE_REFUSED", parsed.detail)),
        )
        is MixOutcome.Failed -> RecipeLintReport(
            listOf(RecipeLintIssue("unknown", LintSeverity.ERROR, "PARSE_ERROR", parsed.message)),
        )
    }

    /** Lints a single [RecipeV2] statically and via [RecipeInterpreter] + [ChoreoGuard]. */
    fun lintRecipe(recipe: RecipeV2): RecipeLintReport {
        val issues = ArrayList<RecipeLintIssue>()
        checkStaticFields(recipe, issues)
        checkStepListStatic(recipe.id, "steps", recipe.steps, forbidStems = false, issues)
        checkStepListStatic(recipe.id, "noStemSteps", recipe.noStemSteps, forbidStems = true, issues)
        if (issues.none { it.severity == LintSeverity.ERROR }) {
            checkSimulations(recipe, issues)
        }
        return RecipeLintReport(issues)
    }

    /** Lints an entire recipe catalog for individual invariants, duplicates, and Part A §5 FX coverage. */
    fun lintAll(recipes: List<RecipeV2>): RecipeLintReport {
        val issues = ArrayList<RecipeLintIssue>()
        val seenIds = HashSet<String>(recipes.size)
        for (recipe in recipes) {
            if (!seenIds.add(recipe.id)) {
                issues += RecipeLintIssue(recipe.id, LintSeverity.ERROR, "DUPLICATE_ID", "Duplicate recipe id '${recipe.id}'")
            }
            issues += lintRecipe(recipe).issues
        }
        issues += lintCoverage(recipes)
        return RecipeLintReport(issues)
    }

    /** Verifies Part A §5 FX coverage across Club and Festival recipe tiers. */
    fun lintCoverage(recipes: List<RecipeV2>): List<RecipeLintIssue> {
        val issues = ArrayList<RecipeLintIssue>()
        val clubFx = HashSet<RecipeFxType>()
        val festFx = HashSet<RecipeFxType>()
        for (recipe in recipes) {
            if (!recipe.enabled) continue
            val tags = directFxTags(recipe)
            if (recipe.level == DjLevel.CLUB) clubFx += tags
            if (recipe.level == DjLevel.FESTIVAL) festFx += tags
        }
        val missingClub = REQUIRED_CLUB_FX - clubFx
        if (missingClub.isNotEmpty()) {
            issues += RecipeLintIssue(
                "CATALOG",
                LintSeverity.ERROR,
                "MISSING_CLUB_FX",
                "Club tier misses Part A §5 FX coverage: $missingClub",
            )
        }
        val allFestNeeded = REQUIRED_CLUB_FX + REQUIRED_FESTIVAL_EXTRA_FX
        val missingFest = allFestNeeded - festFx
        if (missingFest.isNotEmpty()) {
            issues += RecipeLintIssue(
                "CATALOG",
                LintSeverity.ERROR,
                "MISSING_FESTIVAL_FX",
                "Festival tier misses Part A §5 FX coverage: $missingFest",
            )
        }
        return issues
    }

    /** Collects primary FX tags present directly on [recipe.steps] (excluding `fallbackFx`). */
    fun directFxTags(recipe: RecipeV2): Set<RecipeFxType> = buildSet {
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

    private fun checkStaticFields(recipe: RecipeV2, issues: MutableList<RecipeLintIssue>) {
        if (recipe.schema != 2) {
            issues += RecipeLintIssue(recipe.id, LintSeverity.ERROR, "BAD_SCHEMA", "Expected schema=2, got ${recipe.schema}")
        }
        if (recipe.description.isBlank()) {
            issues += RecipeLintIssue(recipe.id, LintSeverity.ERROR, "BLANK_DESCRIPTION", "Recipe description must not be blank")
        }
        if (recipe.steps.isEmpty()) {
            issues += RecipeLintIssue(recipe.id, LintSeverity.ERROR, "EMPTY_STEPS", "Recipe must define non-empty steps")
        }
        if (recipe.noStemSteps.isEmpty()) {
            issues += RecipeLintIssue(recipe.id, LintSeverity.ERROR, "EMPTY_NO_STEM_STEPS", "Recipe must define non-empty noStemSteps")
        }
        if (recipe.abruptness == AbruptnessClass.ABRUPT || recipe.id in FESTIVAL_ONLY_ABRUPT_IDS) {
            if (recipe.abruptness != AbruptnessClass.ABRUPT) {
                issues += RecipeLintIssue(recipe.id, LintSeverity.ERROR, "ABRUPT_FLAG_MISSING", "Recipe '${recipe.id}' must be AbruptnessClass.ABRUPT")
            }
            if (recipe.level != DjLevel.FESTIVAL) {
                issues += RecipeLintIssue(recipe.id, LintSeverity.ERROR, "ABRUPT_NOT_FESTIVAL", "Abrupt recipe '${recipe.id}' must require DjLevel.FESTIVAL")
            }
            if (!recipe.requirements.requiresTempoOrGenreJump) {
                issues += RecipeLintIssue(recipe.id, LintSeverity.ERROR, "ABRUPT_NO_JUMP_FLAG", "Abrupt recipe '${recipe.id}' must set requiresTempoOrGenreJump=true")
            }
            if (recipe.requirements.requiresBeatMatch) {
                issues += RecipeLintIssue(recipe.id, LintSeverity.ERROR, "ABRUPT_BEATMATCH", "Abrupt recipe '${recipe.id}' must set requiresBeatMatch=false")
            }
        }
        if (!recipe.requirements.maxTempoGap.isFinite() ||
            !recipe.requirements.minOverlapBars.isFinite() ||
            !recipe.requirements.minGridConfidence.isFinite() ||
            !recipe.energyDelta.isFinite()
        ) {
            issues += RecipeLintIssue(recipe.id, LintSeverity.ERROR, "NON_FINITE_REQ", "Non-finite requirement metric")
        }
    }

    private fun checkStepListStatic(
        recipeId: String,
        label: String,
        steps: List<RecipeStepV2>,
        forbidStems: Boolean,
        issues: MutableList<RecipeLintIssue>,
    ) {
        for ((idx, step) in steps.withIndex()) {
            when (step) {
                is RecipeStepV2.EnableIsolator -> Unit
                is RecipeStepV2.LaneCurve -> {
                    if (forbidStems && step.kind.isStem) {
                        issues += RecipeLintIssue(
                            recipeId,
                            LintSeverity.ERROR,
                            "NO_STEM_USES_STEM",
                            "$label[$idx] targets stem lane ${step.kind}",
                        )
                    }
                    if (step.points.isEmpty()) {
                        issues += RecipeLintIssue(recipeId, LintSeverity.ERROR, "EMPTY_POINTS", "$label[$idx] has empty points")
                    }
                    for (wp in step.points) {
                        checkStepTimeFinite(recipeId, label, idx, wp.time, issues)
                        if (!wp.value.isFinite() || !wp.depthBase.isFinite()) {
                            issues += RecipeLintIssue(recipeId, LintSeverity.ERROR, "NON_FINITE_WAYPOINT", "$label[$idx] has non-finite waypoint value")
                        }
                    }
                }
                is RecipeStepV2.SwapLows -> {
                    if (forbidStems && step.useBassStems) {
                        issues += RecipeLintIssue(
                            recipeId,
                            LintSeverity.ERROR,
                            "NO_STEM_USES_BASS_STEM",
                            "$label[$idx] SwapLows sets useBassStems=true inside noStemSteps",
                        )
                    }
                    checkStepTimeFinite(recipeId, label, idx, step.at, issues)
                    if (!step.widthBeats.isFinite() || step.widthBeats <= 0.0 ||
                        !step.inLowBeforeDb.isFinite() || !step.outLowAfterDb.isFinite()
                    ) {
                        issues += RecipeLintIssue(recipeId, LintSeverity.ERROR, "BAD_SWAP_LOWS", "$label[$idx] invalid SwapLows parameters")
                    }
                }
                is RecipeStepV2.ConfigureFx -> {
                    step.startTime?.let { checkStepTimeFinite(recipeId, label, idx, it, issues) }
                    step.endTime?.let { checkStepTimeFinite(recipeId, label, idx, it, issues) }
                    step.wetCurve?.forEach { wp ->
                        checkStepTimeFinite(recipeId, label, idx, wp.time, issues)
                        if (!wp.value.isFinite() || !wp.depthBase.isFinite()) {
                            issues += RecipeLintIssue(recipeId, LintSeverity.ERROR, "NON_FINITE_FX_WET", "$label[$idx] non-finite wetCurve value")
                        }
                    }
                    step.fallbackWetCurve?.forEach { wp ->
                        checkStepTimeFinite(recipeId, label, idx, wp.time, issues)
                        if (!wp.value.isFinite() || !wp.depthBase.isFinite()) {
                            issues += RecipeLintIssue(recipeId, LintSeverity.ERROR, "NON_FINITE_FX_FB", "$label[$idx] non-finite fallbackWetCurve value")
                        }
                    }
                    for ((k, v) in step.params) {
                        if (!v.isFinite()) {
                            issues += RecipeLintIssue(recipeId, LintSeverity.ERROR, "NON_FINITE_PARAM", "$label[$idx] param '$k' is non-finite")
                        }
                    }
                }
            }
        }
    }

    private fun checkStepTimeFinite(
        recipeId: String,
        label: String,
        idx: Int,
        time: StepTimeV2,
        issues: MutableList<RecipeLintIssue>,
    ) {
        if (!time.param.isFinite() || !time.offsetBars.isFinite() ||
            !time.offsetBeats.isFinite() || !time.offsetSec.isFinite()
        ) {
            issues += RecipeLintIssue(recipeId, LintSeverity.ERROR, "NON_FINITE_TIME", "$label[$idx] has non-finite StepTimeV2")
        }
    }

    private fun checkSimulations(recipe: RecipeV2, issues: MutableList<RecipeLintIssue>) {
        if (!recipe.enabled) return
        val barMs = 2_000L
        val defaultBars = recipe.lengthOptionsBars.first()
        val shortestBars = recipe.lengthOptionsBars.min()
        val configs = LinkedHashSet<Pair<Int, Double>>()
        val midRatio = if (recipe.placementPrefs.preferDropEntry || recipe.placementPrefs.swapRule.startsWith("DROP")) 1.0 else 0.5
        configs += defaultBars to midRatio
        configs += shortestBars to midRatio
        if (recipe.placementPrefs.preferDropEntry || recipe.placementPrefs.swapRule.startsWith("DROP") ||
            recipe.placementPrefs.swapRule.startsWith("CUT")
        ) {
            configs += shortestBars to 1.0
        }

        val fullStems = allDeckStems("out-track", "in-track")
        for ((bars, ratio) in configs) {
            val overlapMs = bars.toLong() * barMs
            val swapOffsetMs = (overlapMs * ratio).toLong().coerceIn(0L, overlapMs)
            val plan = syntheticPlan(recipe, bars, barMs, swapOffsetMs, recipe.level)
            val outReg = AudioRegionRef(DeckTarget.OUT, "out-track", 50_000L, 120_000L)
            val inReg = AudioRegionRef(DeckTarget.IN, "in-track", 0L, 60_000L)

            for (noStem in listOf(false, true)) {
                val res = RecipeInterpreter.interpret(
                    recipe = recipe,
                    plan = plan,
                    outRegion = outReg,
                    inRegion = inReg,
                    stems = if (noStem) emptyList() else fullStems,
                    useNoStemVariant = noStem,
                )
                if (res !is MixOutcome.Ok) {
                    val detail = when (res) {
                        is MixOutcome.Refused -> "${res.reason}: ${res.detail}"
                        is MixOutcome.Failed -> "${res.kind}: ${res.message}"
                        is MixOutcome.Ok -> ""
                    }
                    issues += RecipeLintIssue(
                        recipe.id,
                        LintSeverity.ERROR,
                        "SIMULATION_FAILED",
                        "Interpret/Guard failed (bars=$bars, ratio=$ratio, noStem=$noStem): $detail",
                    )
                }
            }
        }

        if (recipe.abruptness == AbruptnessClass.ABRUPT) {
            val clubPlan = syntheticPlan(recipe, defaultBars, barMs, defaultBars * barMs, DjLevel.CLUB)
            val clubAttempt = RecipeInterpreter.interpret(
                recipe = recipe,
                plan = clubPlan,
                outRegion = AudioRegionRef(DeckTarget.OUT, "out-track", 50_000L, 120_000L),
                inRegion = AudioRegionRef(DeckTarget.IN, "in-track", 0L, 60_000L),
                useNoStemVariant = true,
            )
            if (clubAttempt !is MixOutcome.Refused || clubAttempt.reason != RefusalReason.ABRUPT_DISALLOWED) {
                issues += RecipeLintIssue(
                    recipe.id,
                    LintSeverity.ERROR,
                    "ABRUPT_NOT_GATED",
                    "Abrupt recipe '${recipe.id}' must refuse DjLevel.CLUB with ABRUPT_DISALLOWED",
                )
            }
        }
    }

    private fun syntheticPlan(
        recipe: RecipeV2,
        bars: Int,
        barMs: Long,
        swapOffsetMs: Long,
        level: DjLevel,
    ): TransitionPlanV1 {
        val exitMs = 60_000L
        val overlapMs = bars.toLong() * barMs
        val geometry = if (recipe.requirements.requiresOutLoop) TransitionGeometry.LOOP else TransitionGeometry.BRIDGE
        return TransitionPlanV1(
            planId = "lint-${recipe.id}-$bars-$level",
            generation = 1L,
            outTrackId = "out-track",
            inTrackId = "in-track",
            djLevel = level,
            geometry = geometry,
            frozenExitMs = exitMs,
            frozenEntryMs = 0L,
            swapDownbeatMs = exitMs + swapOffsetMs,
            overlapDurationMs = overlapMs,
            barDurationMs = barMs,
            recipeId = recipe.id,
            recipeName = recipe.name,
            recipeDescription = recipe.description,
        )
    }

    private fun allDeckStems(outId: String, inId: String): List<StemRegionRef> = buildList {
        for (stem in StemKind.entries) {
            add(StemRegionRef(DeckTarget.OUT, stem, outId, 50_000L, 120_000L))
            add(StemRegionRef(DeckTarget.IN, stem, inId, 0L, 60_000L))
        }
    }
}
