package com.music.bitchord.playback.djmix

import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.alignTempoOctave
import com.music.bitchord.playback.smart.choreo.ChoreoCaps
import com.music.bitchord.playback.smart.choreo.ChoreoInputs
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.choreo.Choreographer
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.choreo.RecipeId
import com.music.bitchord.playback.smart.choreo.Recipes
import com.music.bitchord.playback.smart.choreo.StructureView
import com.music.bitchord.playback.smart.planTransition
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Shared prepare/build geometry. A synthetic timing clock never manufactures beat confidence. */
internal object StraightMixGeometry {
    fun rateMode(out: TrackAnalysis?, inn: TrackAnalysis?, level: DjLevel): StraightRateMode =
        StraightRatePolicy.rateMode(out, inn, level)

    fun classicPlan(input: PlanInput): TransitionPlan = planTransition(
        analysis = input.outAnalysis,
        nextAnalysis = input.inAnalysis,
        currentTrack = input.outTrack.transitionInfo(),
        nextTrack = input.inTrack.transitionInfo(),
        currentTime = input.positionSec,
        duration = input.outTrack.durationSec,
        fadeSeconds = input.fallbackSeconds,
        mode = CrossfadeMode.SMART,
        advanced = true,
        mixWindow = true,
        lyricsOut = input.lyricsOut,
        lyricsIn = input.lyricsIn,
        // vc91's exit/cue, before its uncertain-classic -> NATURAL rewrite.
        allowRecoveryCrossfade = true,
    )

    fun policyExcluded(classic: TransitionPlan, albumOrder: Boolean): Boolean =
        albumOrder || classic.transitionStyle == TransitionStyle.GAPLESS ||
            (classic.blocked && classic.reason in setOf("blocked-speech-or-live", "short-duration-guard", "no-duration"))

    fun select(input: PlanInput, caps: Int, classic: TransitionPlan = classicPlan(input)): SelectedPlan? {
        if (policyExcluded(classic, input.albumOrder)) return null
        val outDuration = physicalDuration(input.outTrack.durationSec, input.outAnalysis.duration) ?: return null
        val inDuration = physicalDuration(input.inTrack.durationSec, input.inAnalysis.duration) ?: return null
        val exit = classic.transitionEnd.takeIf { it.isFinite() && it > input.positionSec + 1.0 }
            ?.coerceAtMost(outDuration) ?: outDuration
        val cue = classic.incomingCueTime.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val mode = rateMode(input.outAnalysis, input.inAnalysis, input.djLevel)
        val outBpm = input.outAnalysis.tailBeats?.bpm?.takeIf { it.isFinite() && it > 1.0 }
            ?: input.outAnalysis.bpm.takeIf { it.isFinite() && it > 1.0 } ?: 120.0
        val inBpm = input.inAnalysis.headBeats?.bpm?.takeIf { it.isFinite() && it > 1.0 }
            ?: input.inAnalysis.bpm.takeIf { it.isFinite() && it > 1.0 } ?: outBpm
        val folded = alignTempoOctave(outBpm, inBpm)
        val fold = if (mode == StraightRateMode.BEAT_MATCH) inBpm / folded else 1.0
        val outStructure = StructureView.best(input.outSongMap, input.outAnalysis)
        val inStructure = StructureView.best(input.inSongMap, input.inAnalysis)
        var best: SelectedPlan? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (recipe in Recipes.forLevel(input.djLevel)) {
            if (recipe.id in input.excludedRecipes || recipe.id.code in input.excludedRecipeIds || recipe.requiresOutLoop) continue
            val minimum = maxOf(1, ceil(recipe.minOverlapBars).toInt())
            var bars = recipe.barRange.first.coerceAtLeast(minimum)
            var plan: LoopMix.Plan? = null
            while (bars >= minimum) {
                val candidate = plan(outBpm, inBpm, fold, bars, 0.0, cue, mode)
                val span = LoopMix.Clock(candidate).releaseAt
                val start = exit - span
                if (start >= input.positionSec + 1.0 && start >= 0.0) {
                    val placed = candidate.copy(out = candidate.out.copy(loopStart = start, loopEnd = start))
                    val required = LoopMixSession.decodeRequirements(
                        placed, input.sampleRate, input.outAnalysis, input.inAnalysis, mode, input.djLevel,
                    )
                    if (required.inTo <= inDuration && start + span <= outDuration + 1e-6) {
                        plan = placed
                        break
                    }
                }
                bars--
            }
            val fitted = plan ?: continue
            val prep = ChoreoSessionGlue.Prep(
                fitted, null, recipe.id, caps, input.djLevel, outStructure, inStructure,
                "${input.outTrack.id}->${input.inTrack.id}", input.albumOrder,
                "vc91 exit/cue · ${mode.name.lowercase()}", input.sampleRate, mode, input.excludedRecipes,
            )
            // Validate the same recipe's no-stem variant before spending any decode/inference work.
            val script = ChoreoSessionGlue.withoutStems(
                prep, fitted, LoopMix.Clock(fitted), input.sampleRate, input.outAnalysis, input.inAnalysis,
                input.sungOut, input.sungIn, recipe.id,
            ) ?: continue
            val inputs = ChoreoInputs(
                fitted, LoopMix.Clock(fitted), input.outAnalysis, input.inAnalysis,
                outStructure, inStructure, caps = caps, level = input.djLevel,
                pairKey = prep.pairKey, recipeHistory = com.music.bitchord.playback.smart.choreo.AutomixV2Choreo.history.recentRecipes(prep.pairKey),
                stemsExpected = input.stemsExpected, excludedRecipes = input.excludedRecipes,
            )
            val score = Choreographer.rankRecipes(inputs, forced = recipe.id).firstOrNull()?.total ?: continue
            if (score > bestScore) {
                bestScore = score
                best = SelectedPlan(
                    plan = fitted, v2Prep = prep, entryCueSec = cue,
                    exitWindowSec = fitted.out.loopStart..exit, pathHint = "v2-straight",
                    straightRateMode = mode, fallbackReason = if (!input.outAnalysis.isUsable || !input.inAnalysis.isUsable) {
                        "musical-evidence-unavailable:no-stem"
                    } else "", previewScript = script,
                )
            }
        }
        return best
    }

    /** A bounded, non-looping PCM rescue gets fresh geometry, never a relabelled crown. */
    fun rescuePlan(start: Double, cue: Double, bars: Int, outBpm: Double, hold: Double = 0.0): LoopMix.Plan =
        plan(outBpm, outBpm, 1.0, bars, start, cue, StraightRateMode.RATE_ONE, hold)

    private fun plan(
        outBpm: Double, inBpm: Double, fold: Double, bars: Int, start: Double, cue: Double,
        mode: StraightRateMode, hold: Double = 0.0,
    ): LoopMix.Plan {
        val enter = if (bars >= 4) 1 else 0
        val exit = if (bars >= 2) 1 else 0
        return LoopMix.Plan(
            out = LoopMix.Pick(start, start, 0, 60.0 / outBpm, outBpm, 0.0, LoopMix.Check.MASK),
            inn = LoopMix.Pick(cue, cue, 0, 60.0 / inBpm, inBpm, 0.0, LoopMix.Check.MASK),
            fold = fold, enterBars = enter, rampBars = bars - enter - exit, exitBars = exit, totalBars = bars,
            outLoops = false, inLoops = false, nativeIncoming = mode == StraightRateMode.RATE_ONE,
            targetBpmOverride = if (mode == StraightRateMode.RATE_ONE) 0.0 else null,
            incomingHoldSec = hold, rung = "Straight", alternativeKind = "v2-straight",
        )
    }

    private fun physicalDuration(metadata: Double, analysis: Double): Double? =
        metadata.takeIf { it.isFinite() && it > 1.0 } ?: analysis.takeIf { it.isFinite() && it > 1.0 }

    private fun PrepTrack.transitionInfo() = TransitionTrackInfo(
        id, (durationSec * 1000).roundToLong(),
        title = mediaItem?.mediaMetadata?.title?.toString().orEmpty(),
        artist = mediaItem?.mediaMetadata?.artist?.toString().orEmpty(),
        album = albumTitle, trackNumber = trackNumber,
    )
}
