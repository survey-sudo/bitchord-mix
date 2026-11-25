package com.music.bitchord.djmix.core.recipes.cli

import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.FxCapability
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RecipeStepV2
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.StemKind
import com.music.bitchord.djmix.core.contracts.StemRegionRef
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.recipes.RecipeInterpreter
import com.music.bitchord.djmix.core.recipes.RecipeLintIssue
import com.music.bitchord.djmix.core.recipes.RecipeLinter
import com.music.bitchord.djmix.core.recipes.RecipeRegistry
import java.io.File
import kotlin.system.exitProcess

private data class Options(
    val all: Boolean,
    val paths: List<String>,
    val checkFallbacks: Boolean,
    val noCoverage: Boolean,
    val json: Boolean,
)

/** Command-line host for the platform-free commonMain recipe linter. */
fun main(args: Array<String>) {
    exitProcess(run(args, System.out, System.err))
}

/** Pure process result: tests can exercise CLI behavior without terminating the test JVM. */
fun run(args: Array<String>, out: Appendable, err: Appendable): Int {
    val options = parseOptions(args, err) ?: return 2
    val recipes: List<RecipeV2>
    val issues = ArrayList<RecipeLintIssue>()
    if (options.all || options.paths.isEmpty()) {
        if (options.noCoverage) {
            err.appendLine("--no-coverage requires positional recipe paths")
            return 2
        }
        recipes = try {
            RecipeRegistry.all()
        } catch (failure: Throwable) {
            err.appendLine("Unable to load bundled recipes: ${failure.message ?: failure::class.simpleName}")
            return 2
        }
    } else {
        val loaded = loadRecipes(options.paths, issues, err) ?: return 2
        recipes = loaded
    }

    if (options.noCoverage) {
        for (recipe in recipes) issues += RecipeLinter.lintRecipe(recipe).issues
        val seen = HashSet<String>()
        for (recipe in recipes) {
            if (!seen.add(recipe.id)) {
                issues += RecipeLintIssue(recipe.id, code = "DUPLICATE_ID", message = "Duplicate recipe id '${recipe.id}'")
            }
        }
    } else {
        issues += RecipeLinter.lintAll(recipes).issues
    }
    if (options.checkFallbacks) issues += lintFallbackCombinations(recipes)

    if (options.json) {
        out.appendLine(reportJson(recipes.size, issues))
    } else if (issues.isEmpty()) {
        out.appendLine("PASS: ${recipes.size} RecipeV2 definitions; schema, anchors, boundaries, lows, FX budgets, abrupt gates" +
            if (!options.noCoverage) ", tier FX coverage" else "")
        if (options.checkFallbacks) out.appendLine("PASS: optional capability fallback combinations")
    } else {
        for (issue in issues) err.appendLine("${issue.recipeId}: ${issue.code}: ${issue.message}")
        err.appendLine("FAIL: ${issues.size} violation(s), ${recipes.size} recipe(s)")
    }
    return if (issues.isEmpty()) 0 else 1
}

private fun parseOptions(args: Array<String>, err: Appendable): Options? {
    var all = false
    var checkFallbacks = false
    var noCoverage = false
    var json = false
    val paths = ArrayList<String>()
    for (arg in args) {
        when (arg) {
            "--all" -> all = true
            "--check-fallbacks" -> checkFallbacks = true
            "--no-coverage" -> noCoverage = true
            "--json" -> json = true
            else -> if (arg.startsWith("-")) {
                err.appendLine("Unknown option: $arg")
                err.appendLine("Usage: lintRecipes [--all | <recipe.json|directory>...] [--check-fallbacks] [--no-coverage] [--json]")
                return null
            } else paths += arg
        }
    }
    if (all && paths.isNotEmpty()) {
        err.appendLine("--all cannot be combined with positional recipe paths")
        return null
    }
    if (noCoverage && paths.isEmpty()) {
        err.appendLine("--no-coverage requires positional recipe paths")
        return null
    }
    if (paths.isEmpty() && !all) all = true
    return Options(all, paths, checkFallbacks, noCoverage, json)
}

private fun loadRecipes(
    rawPaths: List<String>,
    issues: MutableList<RecipeLintIssue>,
    err: Appendable,
): List<RecipeV2>? {
    val files = ArrayList<File>()
    for (raw in rawPaths) {
        val path = File(raw)
        if (!path.exists()) {
            err.appendLine("Cannot read recipe path: $raw (does not exist)")
            return null
        }
        if (path.isDirectory) {
            val children = path.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
                ?.sortedBy { it.name }
            if (children.isNullOrEmpty()) {
                err.appendLine("Recipe directory contains no JSON files: $raw")
                return null
            }
            files.addAll(children)
        } else {
            files += path
        }
    }

    val recipes = ArrayList<RecipeV2>(files.size)
    for (file in files) {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (failure: Exception) {
            err.appendLine("Cannot read ${file.path}: ${failure.message ?: failure::class.simpleName}")
            return null
        }
        when (val parsed = RecipeRegistry.parseJson(text)) {
            is MixOutcome.Ok -> {
                if (file.extension.equals("json", ignoreCase = true) && file.nameWithoutExtension != parsed.value.id) {
                    issues += RecipeLintIssue(
                        parsed.value.id,
                        code = "FILENAME_ID",
                        message = "Filename '${file.name}' does not match recipe id '${parsed.value.id}'",
                    )
                }
                recipes += parsed.value
            }
            is MixOutcome.Refused -> issues += RecipeLintIssue("unknown", code = "PARSE_REFUSED", message = parsed.detail)
            is MixOutcome.Failed -> issues += RecipeLintIssue("unknown", code = "PARSE_ERROR", message = parsed.message)
        }
    }
    return recipes
}

private fun lintFallbackCombinations(recipes: List<RecipeV2>): List<RecipeLintIssue> {
    val issues = ArrayList<RecipeLintIssue>()
    for (recipe in recipes) {
        val variants = listOf(false, true)
        for (noStem in variants) {
            val steps = if (noStem) recipe.noStemSteps else recipe.steps
            val optionalCaps = steps.filterIsInstance<RecipeStepV2.ConfigureFx>()
                .mapNotNull { it.requiredCap }
                .distinct()
            val missingSets = capabilitySubsets(optionalCaps)
            val ratios = if (recipe.placementPrefs.preferDropEntry || recipe.placementPrefs.swapRule.startsWith("DROP")) {
                listOf(1.0)
            } else if (recipe.placementPrefs.swapRule.startsWith("CUT")) {
                listOf(0.5, 1.0)
            } else {
                listOf(0.5)
            }
            for (bars in recipe.lengthOptionsBars) for (ratio in ratios) {
                val overlapMs = bars.toLong() * 2_000L
                val swapMs = (overlapMs * ratio).toLong().coerceIn(0L, overlapMs)
                val plan = syntheticPlan(recipe, bars, overlapMs, swapMs)
                for (missing in missingSets) {
                    val available = FxCapability.entries.toSet() - missing
                    val result = RecipeInterpreter.interpret(
                        recipe = recipe,
                        plan = plan,
                        outRegion = AudioRegionRef(DeckTarget.OUT, "lint-out", 50_000L, 120_000L),
                        inRegion = AudioRegionRef(DeckTarget.IN, "lint-in", 0L, 60_000L),
                        stems = if (noStem) emptyList() else allStems(),
                        useNoStemVariant = noStem,
                        availableCapabilities = available,
                    )
                    if (result !is MixOutcome.Ok) {
                        val detail = when (result) {
                            is MixOutcome.Refused -> "${result.reason}: ${result.detail}"
                            is MixOutcome.Failed -> "${result.kind}: ${result.message}"
                            is MixOutcome.Ok -> ""
                        }
                        issues += RecipeLintIssue(
                            recipe.id,
                            code = "FALLBACK_SIMULATION_FAILED",
                            message = "${if (noStem) "noStemSteps" else "steps"}, bars=$bars, swap=$ratio, missing=$missing: $detail",
                        )
                        break
                    }
                }
            }
        }
    }
    return issues
}

private fun capabilitySubsets(capabilities: List<FxCapability>): List<Set<FxCapability>> =
    (0 until (1 shl capabilities.size)).map { mask ->
        capabilities.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
    }

private fun syntheticPlan(recipe: RecipeV2, bars: Int, overlapMs: Long, swapOffsetMs: Long) = TransitionPlanV1(
    planId = "lint-${recipe.id}-$bars",
    generation = 1L,
    outTrackId = "lint-out",
    inTrackId = "lint-in",
    djLevel = recipe.level,
    geometry = if (recipe.requirements.requiresOutLoop) TransitionGeometry.LOOP else TransitionGeometry.BRIDGE,
    frozenExitMs = 60_000L,
    frozenEntryMs = 0L,
    swapDownbeatMs = 60_000L + swapOffsetMs,
    overlapDurationMs = overlapMs,
    barDurationMs = 2_000L,
    recipeId = recipe.id,
    recipeName = recipe.name,
    recipeDescription = recipe.description,
)

private fun allStems(): List<StemRegionRef> = buildList {
    for (stem in StemKind.entries) {
        add(StemRegionRef(DeckTarget.OUT, stem, "lint-out", 50_000L, 120_000L))
        add(StemRegionRef(DeckTarget.IN, stem, "lint-in", 0L, 60_000L))
    }
}

private fun reportJson(recipeCount: Int, issues: List<RecipeLintIssue>): String {
    val diagnostics = issues.joinToString(",") { issue ->
        "{\"recipeId\":${jsonString(issue.recipeId)},\"severity\":${jsonString(issue.severity.name)}," +
            "\"code\":${jsonString(issue.code)},\"message\":${jsonString(issue.message)}}"
    }
    return "{\"passed\":${issues.isEmpty()},\"recipeCount\":$recipeCount,\"issueCount\":${issues.size},\"issues\":[$diagnostics]}"
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}
