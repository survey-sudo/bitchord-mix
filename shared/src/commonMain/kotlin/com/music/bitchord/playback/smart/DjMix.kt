package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.min

/**
 * What the blend adds on top of its style. Chosen once per pair from the
 * pair's ids, so the planner cannot change its mind on the next tick.
 *
 * Echo, loop roll and reverb are the tempo-gap alternatives. Flanger is a
 * colour on a beatmix, at most one pair in five. Vocal handoff is separate:
 * it rides along whenever both masks say the singers would stack.
 */
enum class DjEffect {
    NONE,
    ECHO,
    LOOP_ROLL,
    REVERB,
    FLANGER,
}

/**
 * Tasteful defaults for the beat-synced toolkit. The audio thread reads
 * these numbers; it does not decide them.
 */
data class EffectSettings(
    val wet: Float,
    val feedback: Float,
    /** LFO or roll length, in beats. */
    val beats: Float,
)

fun effectSettings(effect: DjEffect): EffectSettings = when (effect) {
    DjEffect.ECHO -> EffectSettings(wet = 0.40f, feedback = 0.55f, beats = 1f)
    DjEffect.LOOP_ROLL -> EffectSettings(wet = 1f, feedback = 0f, beats = 4f)
    // A wash, not a blanket. It only opens in the last quarter of a mix.
    DjEffect.REVERB -> EffectSettings(wet = 0.12f, feedback = 0.45f, beats = 0f)
    DjEffect.FLANGER -> EffectSettings(wet = 0.42f, feedback = 0.4f, beats = 8f)
    DjEffect.NONE -> EffectSettings(wet = 0f, feedback = 0f, beats = 0f)
}

/**
 * Colour for one blend. Tempo-gap echo, roll and reverb are chosen earlier
 * and are left alone. Two singers do not get a flanger. An instrumental
 * blend does, on half the pairs, so it is actually heard. Reverb is not a
 * choice here: a wash on an ordinary beatmix is what was muffling songs
 * that had a better mix in them.
 */
/**
 * Whether this blend should rise into the next section.
 *
 * A pre-chorus, or an instrumental or bridge whose own energy is climbing,
 * into a chorus, riff, or intro. The rise is a few percent of speed and
 * pitch with a flanger. A verse, an outro, and two singers stacked are left
 * alone: those are not a buildup.
 */
fun buildupFits(
    style: TransitionStyle,
    vocalOverlap: Double,
    handoff: Boolean,
    outSection: SectionKind?,
    inSection: SectionKind?,
    energyRise: Double,
): Boolean {
    if (style != TransitionStyle.DJ_BLEND) return false
    if (handoff || vocalOverlap >= 0.35) return false
    if (outSection == SectionKind.OUTRO || outSection == SectionKind.VERSE) return false
    val intoLift = inSection == null ||
        inSection == SectionKind.CHORUS ||
        inSection == SectionKind.INST ||
        inSection == SectionKind.INTRO ||
        inSection == SectionKind.PRE_CHORUS ||
        inSection == SectionKind.BUILD ||
        inSection == SectionKind.DROP
    if (!intoLift) return false
    if (outSection == SectionKind.PRE_CHORUS) return true
    if (outSection == SectionKind.INST && energyRise >= 1.12) return true
    if (outSection == SectionKind.BRIDGE && energyRise >= 1.18) return true
    if (outSection == SectionKind.CHORUS && energyRise >= 1.25) return true
    if (outSection == null && energyRise >= 1.3) return true
    return false
}

fun blendColour(vocalOverlap: Double, outgoingId: String, incomingId: String, measured: Boolean = false, buildup: Boolean = false): DjEffect {
    return FxPolicy.select(
        outgoingId, incomingId, rhythmic = true, measured = measured,
        vocalClash = vocalOverlap, speechLike = false, drifting = false,
        buildup = buildup, cleanSpace = false,
    )
}

/** Pair hash selects only among effects already justified by measured musical evidence. */
object FxPolicy {
    fun fxSlot(outgoingId: String, incomingId: String): Int =
        ((pairHash(outgoingId, incomingId) % 4) + 4) % 4

    fun select(
        outgoingId: String,
        incomingId: String,
        rhythmic: Boolean,
        measured: Boolean,
        vocalClash: Double,
        speechLike: Boolean,
        drifting: Boolean,
        buildup: Boolean,
        cleanSpace: Boolean,
    ): DjEffect {
        if (outgoingId.isBlank() || incomingId.isBlank() || !measured || speechLike || drifting ||
            !vocalClash.isFinite() || vocalClash > 0.20
        ) return DjEffect.NONE
        val slot = fxSlot(outgoingId, incomingId)
        if (cleanSpace) return when (slot) {
            0, 1, 2 -> DjEffect.ECHO
            3 -> DjEffect.REVERB
            else -> DjEffect.ECHO
        }
        if (!rhythmic || vocalClash >= 0.18) return DjEffect.NONE
        if (buildup) return if (slot == 0) DjEffect.FLANGER else if (slot == 1) DjEffect.LOOP_ROLL else DjEffect.NONE
        return DjEffect.NONE
    }

    internal fun measuredVocal(analysis: TrackAnalysis, from: Double, to: Double): Double? {
        if (!from.isFinite() || !to.isFinite() || to <= from) return null
        // Require window coverage; whole-track vocalProbability is not measurement of this seam.
        if (analysis.vocalWindows.any { it.values.isNotEmpty() && it.frameRate > 0.0 &&
                it.startTime <= from && it.startTime + it.values.size / it.frameRate >= to &&
                it.values.all { v -> v.isFinite() } }) return vocalShare(analysis, from, to)
        val curve = analysis.energyCurve
        if (curve.isEmpty() || curve.first().time > from || curve.last().time < to ||
            analysis.vocalActivityMask.size != curve.size || analysis.vocalActivityMask.any { !it.isFinite() }
        ) return null
        return vocalActivityBetween(analysis, from, to)
    }

    internal fun drifting(out: TrackAnalysis, inn: TrackAnalysis): Boolean =
        (out.tailBeats?.beats?.let { it.size >= 8 && fittedBpm(it, atEnd = true) == null } == true) ||
            (inn.headBeats?.beats?.let { it.size >= 8 && fittedBpm(it, atEnd = false) == null } == true)
}

/**
 * Stable pair hash. Only the two ids go in, never a descriptor sequence.
 */
fun pairHash(outgoingId: String, incomingId: String): Int {
    var hash = 0L
    val key = outgoingId + "\u0000" + incomingId
    for (ch in key) hash = hash * 31 + ch.code
    return (hash and 0x7fffffff).toInt()
}

/**
 * The rungs under the loop mix.
 *
 * A loop mix never reaches this function: the controller takes it first and
 * returns. Everything here reads each track's own sections, then compares
 * the two sides only by scalars (Camelot distance, tempo ratio, one energy
 * number, one vocal-share number). It does not slide one track's features
 * across the other's.
 */
object DjMix {
    fun annotate(
        plan: TransitionPlan,
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        lyricsOut: List<LyricCue> = emptyList(),
        lyricsIn: List<LyricCue> = emptyList(),
        outgoingId: String = "",
        incomingId: String = "",
    ): TransitionPlan {
        if (plan.blocked || plan.transitionStyle == TransitionStyle.GAPLESS) {
            return if (plan.transitionStyle == TransitionStyle.GAPLESS) {
                plan.copy(mixSummary = "gapless")
            } else {
                plan
            }
        }
        // Alternative families carry their own spans (muffle lead + return,
        // drop-in anchor, beatless bridge duration). Structural remapping
        // must never resize them, so they pass through untouched. Consults
        // the family registry: a new family needs no edit here.
        if (TransitionAlternatives.isAlternativeStyle(plan.transitionStyle) ||
            TransitionAlternatives.isAlternativeKind(plan.alternativeKind)
        ) {
            return plan
        }
        var next = plan
        val outSections = labelSections(analysis, lyricsOut)
        val inSections = labelSections(nextAnalysis, lyricsIn)
        next = placeOnStructure(next, analysis, nextAnalysis, lyricsOut, lyricsIn, outSections, inSections)
        next = clashFilter(next, analysis, nextAnalysis)
        next = ensureOverlap(next, analysis)
        next = tempoGap(next, analysis, nextAnalysis, outgoingId, incomingId)
        val handoff = vocalHandoff(next, analysis, nextAnalysis)
        val extend = 0
        val outAt = sectionAt(outSections, next.transitionEnd)?.kind
        val inAt = sectionAt(inSections, next.incomingCueTime)?.kind
        var build = buildupFits(
            next.transitionStyle,
            next.vocalOverlap,
            handoff,
            outAt,
            inAt,
            energyRise(analysis, next.transitionStart, next.transitionEnd),
        )
        // djmix21 buildup-aware: the incoming track's own rise counts too. A
        // cue inside B's buildup/pre-chorus — or inside a quiet INTRO that
        // then rises (mirrors the A-side INST branch in buildupFits) — is a
        // buildup blend even when A's side alone would not read as one.
        if (!build && next.transitionStyle == TransitionStyle.DJ_BLEND && !handoff &&
            next.vocalOverlap < 0.35 &&
            (inAt == SectionKind.PRE_CHORUS || inAt == SectionKind.BUILD || inAt == SectionKind.INTRO)
        ) {
            val inRise = energyRise(nextAnalysis, next.incomingCueTime, next.incomingCueTime + next.fadeSeconds)
            if (inRise >= PhraseConfig.BUILDUP_RISE_MIN) build = true
        }
        next = colour(next.copy(buildup = build), analysis, nextAnalysis, outgoingId, incomingId)
        build = build && next.djEffect in setOf(DjEffect.FLANGER, DjEffect.LOOP_ROLL)
        return next.copy(
            vocalHandoff = handoff,
            buildup = build,
            outSection = outAt?.name?.lowercase() ?: "",
            inSection = inAt?.name?.lowercase() ?: "",
            mixSummary = summary(next, analysis, nextAnalysis, outAt, inAt, handoff, extend, build),
        )
    }

    /**
     * Move the exit and the entry onto each track's own sections when the
     * current plan would cut a sung line or skip a verse. Candidates come
     * from that track alone.
     */
    private fun placeOnStructure(
        plan: TransitionPlan,
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        lyricsOut: List<LyricCue>,
        lyricsIn: List<LyricCue>,
        outSections: List<Section>,
        inSections: List<Section>,
    ): TransitionPlan {
        // djmix15-pilot: catch-up timing is playhead-anchored (0.6-1.5 s lead
        // sized to the room left). Never snap it onto lyrics/sections — that
        // would move transitionEnd/fade/cue off the anchor. Metadata helpers
        // (vocalHandoff, phraseExtend, colour, tempoGap, summary) still run.
        if (plan.reason.endsWith(":catch-up")) return plan
        var end = plan.transitionEnd
        var start = plan.transitionStart
        var cue = plan.incomingCueTime
        if (lyricsOut.isNotEmpty()) {
            // djmix21 mid-vocal guard: never end A mid-line on a blend. Extend
            // to the line end when it is within LYRIC_EXTENSION_MAX_SEC, else
            // move the exit to before the line instead of amputating it.
            val cut = lyricsOut.firstOrNull { end > it.startSec + 0.4 && end < it.endSec - 0.4 }
            if (cut != null) {
                if (cut.endSec - end <= PhraseConfig.LYRIC_EXTENSION_MAX_SEC && cut.endSec > start + 1.0) {
                    end = cut.endSec
                } else if (cut.startSec > start + 1.0 && cut.startSec >= start) {
                    end = cut.startSec
                }
            }
            val chorus = outSections.lastOrNull { it.kind == SectionKind.CHORUS && end > it.start + 1.0 && end < it.end - 1.0 }
            if (chorus != null && chorus.end > start + 1.0 && chorus.end <= analysis.contentEndTime + 1.0 &&
                chorus.end - end <= PhraseConfig.LYRIC_EXTENSION_MAX_SEC
            ) {
                end = chorus.end
            }
            // Leave at the end of the phrase we are in: a verse, a pre-chorus,
            // a bridge or a riff, not halfway through it.
            val here = sectionAt(outSections, end)
            if (here != null && here.kind != SectionKind.INTRO && here.kind != SectionKind.OUTRO &&
                end > here.start + 0.8 && end < here.end - 0.4 &&
                here.end > start + 1.0 && here.end <= analysis.contentEndTime + 1.0 &&
                here.end - end <= PhraseConfig.LYRIC_EXTENSION_MAX_SEC
            ) {
                end = here.end
            }
        }
        if (lyricsIn.isNotEmpty()) {
            val verse = inSections.firstOrNull { it.kind == SectionKind.VERSE && it.start < 45.0 }
            if (verse != null && cue > verse.end + 0.25) cue = verse.start
            val cut = lyricsIn.firstOrNull { cue > it.startSec + 0.3 && cue < it.endSec - 0.3 }
            if (cut != null) cue = cut.startSec
            val landed = sectionAt(inSections, cue)
            if (landed != null && landed.kind != SectionKind.INTRO &&
                cue - landed.start > 0.6 && cue - landed.start < 8.0 && landed.start < 45.0
            ) {
                cue = landed.start
            }
        }
        if (lyricsOut.isEmpty() && !plan.reason.contains("phrase") && analysis.contentEndTime - end > 0.5) {
            val here = sectionAt(outSections, end)
            if (here != null && here.kind != SectionKind.INTRO && here.kind != SectionKind.OUTRO &&
                end > here.start + 0.8 && end < here.end - 0.4 &&
                here.end - end <= 6.0 && here.end > start + 1.0 &&
                here.end <= analysis.contentEndTime + 0.05
            ) {
                end = here.end
            }
        }
        if (cue > 45.0 && lyricsIn.any { it.startSec < 45.0 }) {
            cue = lyricsIn.first { it.startSec < 45.0 }.startSec
        }
        if (abs(end - plan.transitionEnd) < 0.05 && abs(cue - plan.incomingCueTime) < 0.05) return plan
        val fade = (end - start).coerceAtLeast(plan.beatSeconds.takeIf { it > 0 } ?: 0.5)
        if (end < start) return plan
        return plan.copy(
            transitionEnd = end,
            fadeSeconds = fade,
            handoffDuration = fade,
            incomingCueTime = cue.coerceAtLeast(0.0),
        )
    }

    /** Key clash with a tempo the stretch can meet: a short filter, not a long blend. */
    private fun clashFilter(
        plan: TransitionPlan,
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
    ): TransitionPlan {
        // djmix15-pilot: catch-up timing is playhead-anchored; clashFilter
        // would re-anchor a DJ_BLEND catch-up to a key-clash filter window
        // (transitionStart/fade), destroying the re-anchor. No re-timing.
        if (plan.reason.endsWith(":catch-up")) return plan
        if (plan.transitionStyle != TransitionStyle.DJ_BLEND) return plan
        if (plan.reason.contains("phrase")) return plan
        val left = camelotCode(exitKeyForMix(analysis))
        val right = camelotCode(entryKeyForMix(nextAnalysis))
        if (left == null || right == null || camelotCompatible(left, right)) return plan
        if (!stretchReachable(analysis.bpm, nextAnalysis.bpm)) return plan
        val beat = plan.beatSeconds.takeIf { it > 0 } ?: (if (analysis.bpm > 0) 60.0 / analysis.bpm else 0.5)
        val cap = 8 * 4 * beat
        val floor = min(4 * 4 * beat, cap)
        val fade = plan.fadeSeconds.coerceIn(floor, cap)
        val end = plan.transitionEnd
        val start = (end - fade).coerceAtLeast(0.0)
        return plan.copy(
            transitionStyle = TransitionStyle.DJ_FILTER,
            transitionStart = start,
            fadeSeconds = end - start,
            handoffDuration = end - start,
            filterSweep = if (plan.filterSweep > 0) plan.filterSweep else FILTER_SWEEP,
            filterVariant = if (plan.bassSwap) FilterTransitionVariant.BASS_HANDOFF else FilterTransitionVariant.SWEEP,
        )
    }

    /**
     * A beatmix shares at least 8 bars and at most 16. A filter, echo or
     * roll shares at least 4 and at most 8, when the outgoing track has
     * that much music left before [TransitionPlan.transitionEnd].
     */
    private fun ensureOverlap(plan: TransitionPlan, analysis: TrackAnalysis): TransitionPlan {
        // djmix15: catch-up quick dissolves (1.2-3.5 s in <2.5 s of room)
        // are intentionally shorter than the 4-bar club minimum. Lengthening
        // them moves the start behind the playhead and recreates the dead
        // stale window. Never re-size a catch-up.
        if (plan.reason.endsWith(":catch-up")) return plan
        // 2B bounded decline (TransitionPlanner): the 4 s DJ_FILTER length is
        // intentional — the 4-bar club minimum would stretch it to ~8 bars
        // and recreate the exposed overlap. Never resize a decline plan.
        // DECLINE is the variant that carries the carve; the reason check
        // stays as a belt-and-braces for plans built without it.
        if (plan.reason.contains("decline-to-mix") || plan.filterVariant == FilterTransitionVariant.DECLINE) return plan
        if (plan.transitionStyle != TransitionStyle.DJ_BLEND && plan.transitionStyle != TransitionStyle.DJ_FILTER) {
            return plan
        }
        val beat = plan.beatSeconds.takeIf { it > 0 }
            ?: analysis.beatInterval.takeIf { it > 0 }
            ?: if (analysis.bpm > 0) 60.0 / analysis.bpm else return plan
        val blend = plan.transitionStyle == TransitionStyle.DJ_BLEND
        val defaultMinBars = if (blend) 8 else 4
        val defaultMinFade = defaultMinBars * 4 * beat
        val minBars = if (blend &&
            plan.incomingHandoffTime > 0.0 &&
            plan.incomingCueTime <= 0.5 &&
            plan.incomingHandoffTime < defaultMinFade * plan.incomingPlaybackRate - 0.5
        ) {
            4
        } else {
            defaultMinBars
        }
        val maxBars = if (blend) 16 else 8
        val minFade = minBars * 4 * beat
        val maxFade = maxBars * 4 * beat
        val end = plan.transitionEnd
        if (end <= 0.0) return plan
        val desired = plan.fadeSeconds.coerceIn(minFade, maxFade)
        if (abs(desired - plan.fadeSeconds) < 0.05) return plan
        val start = (end - desired).coerceAtLeast(0.0)
        val fade = end - start
        if (fade + 0.05 < minFade && start <= 0.0) return plan
        return plan.copy(transitionStart = start, fadeSeconds = fade, handoffDuration = fade)
    }

    /** Tempo-gap colour requires measured arrangement space; slots vary only eligible colours. */
    private fun tempoGap(
        plan: TransitionPlan,
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        outgoingId: String,
        incomingId: String,
    ): TransitionPlan {
        val gap = plan.filterVariant == FilterTransitionVariant.ECHO_RIDE || plan.echoSeconds > 0.0
        if (!gap || plan.transitionStyle == TransitionStyle.EQUAL_POWER) return plan
        val a = FxPolicy.measuredVocal(analysis, plan.transitionStart, plan.transitionEnd)
        val b = FxPolicy.measuredVocal(nextAnalysis, plan.incomingCueTime, plan.incomingCueTime + plan.fadeSeconds * plan.incomingPlaybackRate)
        val eligible = FxPolicy.select(
            outgoingId, incomingId,
            rhythmic = false,
            measured = a != null && b != null,
            vocalClash = maxOf(plan.vocalOverlap, (a ?: 1.0) * (b ?: 1.0)),
            speechLike = false,
            drifting = FxPolicy.drifting(analysis, nextAnalysis),
            buildup = false,
            cleanSpace = a != null && b != null && a < VOCAL_ACTIVE_THRESHOLD && b < VOCAL_ACTIVE_THRESHOLD,
        )
        return when (eligible) {
            DjEffect.REVERB -> plan.copy(djEffect = DjEffect.REVERB, echoSeconds = 0.0)
            DjEffect.ECHO -> plan.copy(djEffect = DjEffect.ECHO)
            else -> plan.copy(djEffect = DjEffect.NONE, echoSeconds = 0.0)
        }
    }

    /** Flanger or a late throw. Never replaces an echo, roll or reverb already chosen. */
    private fun colour(
        plan: TransitionPlan,
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        outgoingId: String,
        incomingId: String,
    ): TransitionPlan {
        if (plan.djEffect != DjEffect.NONE) return plan
        if (plan.transitionStyle != TransitionStyle.DJ_BLEND) return plan
        val a = FxPolicy.measuredVocal(analysis, plan.transitionStart, plan.transitionEnd)
        val b = FxPolicy.measuredVocal(nextAnalysis, plan.incomingCueTime, plan.incomingCueTime + plan.fadeSeconds * plan.incomingPlaybackRate)
        val measured = a != null && b != null &&
            analysis.beatConfidence.orZero() >= MIN_DJ_CONFIDENCE && nextAnalysis.beatConfidence.orZero() >= MIN_DJ_CONFIDENCE
        val effect = FxPolicy.select(
            outgoingId, incomingId,
            rhythmic = true,
            measured = measured,
            vocalClash = maxOf(plan.vocalOverlap, (a ?: 1.0) * (b ?: 1.0)),
            speechLike = analysis.vocalProbability > 0.85 && analysis.beatConfidence < 0.35 ||
                nextAnalysis.vocalProbability > 0.85 && nextAnalysis.beatConfidence < 0.35,
            drifting = FxPolicy.drifting(analysis, nextAnalysis) || !stretchReachable(analysis.bpm, nextAnalysis.bpm),
            buildup = plan.buildup,
            cleanSpace = plan.echoSeconds > 0.0,
        )
        return if (effect == DjEffect.NONE) plan else plan.copy(djEffect = effect)
    }

    private fun vocalHandoff(plan: TransitionPlan, analysis: TrackAnalysis, nextAnalysis: TrackAnalysis): Boolean {
        val beat = plan.beatSeconds.takeIf { it > 0 } ?: 0.5
        if (plan.fadeSeconds < beat) return false
        val outShare = vocalShare(analysis, plan.transitionStart, plan.transitionEnd)
        val inShare = vocalShare(nextAnalysis, plan.incomingCueTime, plan.incomingCueTime + plan.fadeSeconds)
        return outShare >= 0.25 && inShare >= 0.25
    }

    /**
     * A short outro with a lyric-free 4-bar phrase can be repeated. This is
     * the outro extension; it is not a loop mix, which would already have won
     * if both sides had a clean loop.
     */
    private fun phraseExtend(
        plan: TransitionPlan,
        analysis: TrackAnalysis,
        lyricsOut: List<LyricCue>,
        outSections: List<Section>,
    ): Int {
        if (lyricsOut.isEmpty()) return 0
        val beat = plan.beatSeconds.takeIf { it > 0 }
            ?: analysis.beatInterval.takeIf { it > 0 }
            ?: return 0
        val bars = 4
        val phrase = bars * 4 * beat
        val end = plan.transitionEnd
        val from = end - phrase
        if (from < 0) return 0
        if (lyricsOut.any { it.endSec > from + 0.2 && it.startSec < end - 0.2 }) return 0
        val outro = outSections.firstOrNull { it.kind == SectionKind.OUTRO } ?: return 0
        if (outro.end - outro.start >= 8 * 4 * beat) return 0
        return bars
    }

    private fun summary(
        plan: TransitionPlan,
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        outAt: SectionKind?,
        inAt: SectionKind?,
        handoff: Boolean,
        extend: Int,
        build: Boolean,
    ): String {
        val style = when {
            plan.djEffect == DjEffect.ECHO -> "echo on the bar"
            plan.djEffect == DjEffect.LOOP_ROLL && extend > 0 -> "outro phrase"
            plan.djEffect == DjEffect.LOOP_ROLL -> "loop roll"
            plan.djEffect == DjEffect.REVERB -> "reverb wash"
            plan.transitionStyle == TransitionStyle.DJ_BLEND -> "phrase beatmix"
            plan.transitionStyle == TransitionStyle.DJ_FILTER -> "filter mix"
            plan.transitionStyle == TransitionStyle.EQUAL_POWER -> "crossfade"
            else -> "mix"
        }
        val parts = ArrayList<String>()
        parts += style
        if (outAt != null || inAt != null) {
            parts += "out ${outAt?.name?.lowercase() ?: "—"} → in ${inAt?.name?.lowercase() ?: "—"}"
        }
        val left = camelotCode(exitKeyForMix(analysis))
        val right = camelotCode(entryKeyForMix(nextAnalysis))
        if (left != null && right != null) {
            parts += if (camelotCompatible(left, right)) "$left→$right" else "$left→$right clash"
        }
        val fx = ArrayList<String>()
        if (plan.transitionStyle == TransitionStyle.DJ_BLEND && plan.bassSwap) fx += "bass swap"
        if (handoff) fx += "vocal handoff"
        if (build) fx += "build"
        if (plan.djEffect == DjEffect.FLANGER) fx += "flanger"
        if (plan.filterVariant == FilterTransitionVariant.SWEEP && plan.transitionStyle == TransitionStyle.DJ_FILTER &&
            plan.djEffect == DjEffect.NONE
        ) {
            fx += "sweep"
        }
        if (fx.isNotEmpty()) parts += fx.joinToString(", ")
        return parts.joinToString(" · ")
    }
}
