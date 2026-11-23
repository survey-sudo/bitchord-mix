package com.music.bitchord.playback.smart

/**
 * Wave 2 unified transition intent (pure KMP). One IR naming the geometry both
 * the loop mix ([LoopMix.Plan]) and the planner ([TransitionPlan]) produce,
 * so future templates can be compared and round-tripped without re-deciding
 * musical policy. No platform, audio, or player dependency: scalars only.
 */
data class MixTemplateIR(
    /** Which template family produced this intent ("loopmix" or "transition"). */
    val templateId: String,
    /** Status-line rung (LoopMix rung or TransitionStyle name). */
    val rung: String,
    /** Outgoing source window start (track seconds). */
    val outStartSec: Double,
    /** Outgoing source window end (track seconds). */
    val outEndSec: Double,
    /** Incoming source cue (track seconds). */
    val inCueSec: Double,
    /** Overlap where both decks sound (seconds). */
    val overlapSec: Double,
    /** Incoming beats per outgoing beat (1, or 2 / 0.5 when folded; 1.0 for non-loop). */
    val fold: Double,
    /** Incoming playback rate at landing (1.0 for native; plan rate otherwise). */
    val incomingRate: Double,
    /** Whether the renderer must duck the stem (gated MASK_DUCKED admission). */
    val stemDuckRequired: Boolean,
    /** Colour chosen for this transition. */
    val djEffect: DjEffect,
    /** Fallback policy string ("none", alternativeKind, or planner reason). */
    val fallbackPolicy: String,
)

/**
 * Convert a loop-mix plan to the unified IR. Pure: reads only [LoopMix.Plan]
 * scalars, no analysis, no audio. The overlap is the scheduled mix length
 * from [LoopMix.Clock.releaseAt]; the incoming deck arrives at unity (1.0).
 */
fun LoopMix.Plan.toMixTemplateIR(): MixTemplateIR = MixTemplateIR(
    templateId = "loopmix",
    rung = rung,
    outStartSec = out.loopStart,
    outEndSec = out.loopEnd,
    inCueSec = inn.loopStart,
    overlapSec = LoopMix.Clock(this).releaseAt,
    fold = fold,
    incomingRate = 1.0,
    stemDuckRequired = stemDuckRequired,
    djEffect = when {
        echoTail -> DjEffect.ECHO
        buildupRoll -> DjEffect.LOOP_ROLL
        reverbWash -> DjEffect.REVERB
        flanger -> DjEffect.FLANGER
        else -> DjEffect.NONE
    },
    fallbackPolicy = alternativeKind.ifBlank { "none" },
)

/**
 * Convert a planner transition to the unified IR. Pure: reads only
 * [TransitionPlan] scalars. Overlap is the fade length; fold is neutral
 * (1.0) because the planner does not fold octaves; the stem-duck is never
 * required on this path.
 */
fun TransitionPlan.toMixTemplateIR(): MixTemplateIR = MixTemplateIR(
    templateId = "transition",
    rung = transitionStyle.name,
    outStartSec = transitionStart,
    outEndSec = transitionEnd,
    inCueSec = incomingCueTime,
    overlapSec = fadeSeconds,
    fold = 1.0,
    incomingRate = incomingPlaybackRate,
    stemDuckRequired = false,
    djEffect = djEffect,
    fallbackPolicy = alternativeKind.ifBlank { reason }.ifBlank { "none" },
)
