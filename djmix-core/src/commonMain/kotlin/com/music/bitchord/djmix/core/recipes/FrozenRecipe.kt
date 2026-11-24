package com.music.bitchord.djmix.core.recipes

import com.music.bitchord.djmix.core.contracts.RecipeStepV2
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.WaypointV2

/**
 * Detaches all nested collections from registry/source ownership for one frozen generation.
 * Retain this value with the frozen plan; reloads affect only subsequent snapshots. Callers
 * must treat the returned Kotlin read-only collections as values, not cast and mutate them.
 */
fun RecipeV2.frozenCopy(): RecipeV2 = copy(
    requirements = requirements.copy(
        genreAffinity = requirements.genreAffinity.toMap(),
        requiredStems = requirements.requiredStems.map { it.copy() }.toSet(),
        requiredCapabilities = requirements.requiredCapabilities.toSet(),
    ),
    lengthOptionsBars = lengthOptionsBars.toList(),
    placementPrefs = placementPrefs.copy(),
    steps = steps.map(::copyStep),
    noStemSteps = noStemSteps.map(::copyStep),
)

private fun copyPoints(points: List<WaypointV2>): List<WaypointV2> =
    points.map { it.copy(time = it.time.copy()) }

private fun copyStep(step: RecipeStepV2): RecipeStepV2 = when (step) {
    RecipeStepV2.EnableIsolator -> step
    is RecipeStepV2.SwapLows -> step.copy(at = step.at.copy())
    is RecipeStepV2.LaneCurve -> step.copy(points = copyPoints(step.points))
    is RecipeStepV2.ConfigureFx -> step.copy(
        params = step.params.toMap(),
        startTime = step.startTime?.copy(),
        endTime = step.endTime?.copy(),
        wetCurve = step.wetCurve?.let(::copyPoints),
        fallbackWetCurve = step.fallbackWetCurve?.let(::copyPoints),
    )
}
