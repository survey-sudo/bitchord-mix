package com.music.bitchord.playback.djmix

import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.djmix.core.machine.PairKey
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.LocalBlendAdmission
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.TransitionPlan
import java.util.UUID

/**
 * Narrow host seam supplying player controls, clock, telemetry identifiers,
 * and phase callbacks to [MixEventRouter] without exposing controller internals.
 *
 * Thread owner: invoked from the main/controller thread.
 */
internal interface MixEventHost {
    val phaseName: String
    val scrubEpoch: Long
    fun isIdle(): Boolean
    fun active(): ExoPlayer
    val outgoingPlayer: ExoPlayer?
    fun setOutgoingPlayer(player: ExoPlayer?)
    fun setIncomingPlayer(player: ExoPlayer?)
    fun setHandedOff(handedOff: Boolean)
    fun onSleepFadeStarted()
    fun onSleepFadeFinished()
    fun onSleepFadeAborted()
    fun isSleepTimerAfterTrack(): Boolean
    fun cancelSleepTimer()

    fun leavingOnBail(): ExoPlayer?
    fun finish()
    fun nowMs(): Long
    val bailStartedAt: Long
    val bailFromGain: Float
    fun isRanOn(player: ExoPlayer): Boolean
    fun onBlendHeadroom(headroom: Float)

    fun onNewOpportunity()
    fun onResetOpportunityEventKeys()
}

/** One opportunity survives revisions, retries and native -> classic cleanup. */
internal data class Opportunity(
    val attemptId: String,
    val outId: String,
    val inId: String,
    val scrubEpoch: Long,
)

/** Native candidate binding tying a specific attempt generation and coarse decline flag to [Opportunity]. */
internal data class NativeBinding(
    val context: Opportunity,
    val revision: Int,
    val generation: Int,
    val coarseDecline: Boolean?,
)

/**
 * Owns sleep-fade attenuation, emergency bail volume curves, and transition telemetry
 * route lifecycle/verdicts (M9 slice C-4).
 *
 * Responsibility: sleep timer fade-out, bail ramp execution, opportunity tracking,
 * route planning telemetry event emission, and refusal recording.
 * Thread owner: main/controller thread ([MainThread]).
 */
@OptIn(UnstableApi::class)
internal class MixEventRouter(private val host: MixEventHost) {

    // ---- Sleep fade state ---------------------------------------------------
    private var sleepFadeMs = 0L
    private var sleepFadeStartPositionMs = 0L
    private var sleepFadeStartGain = 1f

    // ---- Opportunity & routing telemetry state ------------------------------
    private val opportunityRunId = UUID.randomUUID().toString()
    private var opportunitySequence = 0L
    var opportunity: Opportunity? = null
    var planRevision = 0
    private var revisionRoute: Any? = null
    var executedRoute: Map<String, Any?>? = null
    var terminalRecorded = false
    var routingFallback = false
    var nativeBinding: NativeBinding? = null
    val routingEvidenceKeys = mutableSetOf<String>()
    private val loggedFireKeys = LinkedHashSet<String>()

    // ---- Sleep fade execution -----------------------------------------------

    /**
     * Starts sleep-fade: the track playing right now is faded to silence on its own,
     * over [fade] ms — the same span a real transition into the next track would
     * have spent on this side of the blend — and then paused.
     */
    @MainThread
    fun beginSleepFadeOut(fade: Long) {
        val out = host.active()
        sleepFadeMs = fade.coerceAtLeast(1L)
        sleepFadeStartPositionMs = out.currentPosition
        sleepFadeStartGain = out.volume
        host.setOutgoingPlayer(out)
        host.setIncomingPlayer(null)
        host.setHandedOff(false)
        Log.d(TAG, "sleep timer due: fading out over ${fade}ms instead of transitioning")
        host.onSleepFadeStarted()
    }

    /**
     * The fade-out proper. Mirrors driveFade's curve on the outgoing side —
     * same fallGain, same position-driven progress — but there is no
     * incoming track to weigh it against, so the span is simply [sleepFadeMs].
     */
    @MainThread
    fun driveSleepFade() {
        val out = host.outgoingPlayer ?: return finishSleepFade()
        // The listener turned the timer off mid-fade — nothing left to do but
        // hand the track back at full volume and let ordinary transition
        // logic resume next tick.
        if (!host.isSleepTimerAfterTrack()) {
            out.volume = 1f
            host.onSleepFadeAborted()
            return
        }
        val elapsed = (out.currentPosition - sleepFadeStartPositionMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / sleepFadeMs).coerceIn(0f, 1f)
        out.volume = sleepFadeStartGain * FadeRideCurves.fallGain(progress)
        val done = progress >= 1f ||
            out.playbackState == Player.STATE_ENDED ||
            out.playbackState == Player.STATE_IDLE
        if (done) finishSleepFade()
    }

    /** Pauses the track the fade-out was run on and retires the timer that asked for it. */
    @MainThread
    fun finishSleepFade() {
        host.outgoingPlayer?.pause()
        host.outgoingPlayer?.volume = 1f
        host.cancelSleepTimer()
        host.onSleepFadeFinished()
    }

    // ---- Bail drive ---------------------------------------------------------

    /** Ramps the outgoing track away rather than cutting it, so an interruption has no click in it. */
    @MainThread
    fun driveBail() {
        val leaving = host.leavingOnBail()
        if (leaving == null) {
            host.finish()
            return
        }
        // djmix11: even bail (emergency) takes the shortened dissolve.
        val progress = (host.nowMs() - host.bailStartedAt).toFloat() /
            maxOf(BAIL_MS, PhraseConfig.EXIT_TAIL_EMERGENCY_MS.toLong())
        if (progress < 1f) {
            val fall = if (leaving === host.outgoingPlayer && host.isRanOn(leaving)) {
                0f
            } else {
                host.bailFromGain * FadeRideCurves.fallGain(progress)
            }
            leaving.volume = fall
            host.onBlendHeadroom(FadeRideCurves.headroomFor(1f, fall))
            return
        }
        host.finish()
    }

    // ---- Opportunity & routing events ---------------------------------------

    fun ensureOpportunity(outId: String, inId: String): Opportunity {
        opportunity?.let { current ->
            if (current.outId == outId && current.inId == inId && current.scrubEpoch == host.scrubEpoch && !terminalRecorded) {
                return current
            }
        }
        // A pair changed without a measured terminal event: do not invent success.
        host.onNewOpportunity()
        opportunity = Opportunity("$opportunityRunId:${++opportunitySequence}", outId, inId, host.scrubEpoch)
        planRevision = 0
        revisionRoute = null
        executedRoute = null
        nativeBinding = null
        terminalRecorded = false
        routingEvidenceKeys.clear()
        resetOpportunityEventKeys()
        return opportunity!!
    }

    /** Natural queue occurrences can share IDs; expire telemetry only, never audio/planning state. */
    fun expireIdleOpportunity(reason: String) {
        if (!host.isIdle()) return
        val context = opportunity ?: return
        if (!terminalRecorded) {
            mixEvent(
                "mix.plan.rejected",
                mapOf(
                    "stage" to "terminal",
                    "outcome" to "unknown",
                    "terminal" to true,
                    "refusalCode" to "media-occurrence-ended",
                    "reason" to reason,
                ),
                context,
            )
        }
        terminalRecorded = true
        opportunity = null
        planRevision = 0
        revisionRoute = null
        executedRoute = null
        nativeBinding = null
        routingEvidenceKeys.clear()
        resetOpportunityEventKeys()
    }

    /** Outer event guards must not hide the same verdict on a later opportunity. */
    fun resetOpportunityEventKeys() {
        host.onResetOpportunityEventKeys()
    }

    fun opportunityFields(context: Opportunity?): Map<String, Any?> = mapOf(
        "attemptId" to context?.attemptId,
        "outId" to context?.outId,
        "inId" to context?.inId,
        "scrubEpoch" to context?.scrubEpoch,
        "outRenditionRevision" to "unknown",
        "inRenditionRevision" to "unknown",
        "outAnalysisRevision" to "unknown",
        "inAnalysisRevision" to "unknown",
    )

    fun mixEvent(name: String, fields: Map<String, Any?>, context: Opportunity? = opportunity) {
        AutomixTelemetry.event(name, opportunityFields(context) + mapOf("planRevision" to planRevision) + fields)
        // These markers follow native takeover / the classic incoming-player start.
        // Never log from a plan, readiness event, failure, or executor Start request.
        if (name == "mix.fire" && fields["stage"] == "executed") runCatching {
            val path = when (fields["executionMarker"]) {
                "native-takeover-observed" -> "prepared"
                "classic-fade-fired" -> "classic"
                else -> return@runCatching
            }
            val key = context?.attemptId ?: "${fields["trackIndex"]}:${fields["actualStartMs"]}"
            if (!loggedFireKeys.add(key)) return@runCatching
            while (loggedFireKeys.size > 64) loggedFireKeys.remove(loggedFireKeys.first())
            if (context != null) MixFireObservation.record(
                PairKey(context.outId, context.inId),
                (fields["actualStartMs"] as? Number)?.toLong(),
                fields["planId"] as? String,
                (fields["preparationGeneration"] as? Number)?.toLong()?.takeIf { it > 0L },
            )
            val recipe = (fields["recipeId"] as? String)?.takeIf { it.isNotBlank() } ?: "none"
            val level = (fields["level"] as? String)?.takeIf { it.isNotBlank() } ?: "none"
            val stems = (fields["stems"] as? Number)?.toInt() ?: 0
            val prepLeadSec = fields["preparedLeadSec"] as? Number ?: 0
            val buildMs = fields["buildMs"] as? Number ?: 0
            val rawReason = (fields["fallbackReason"] as? String)?.takeIf { it.isNotBlank() } ?: "none"
            val reason = when (rawReason) {
                "no renderer", "no output port", "this output can't take it", "no standby sink",
                "renderer_unavailable", "native-renderer-unavailable" -> "renderer-unavailable"
                "safe_mode" -> "safe-mode"
                else -> rawReason // Preserve other real classic paths for the owner's gate audit.
            }
            Log.i("BitChordMix", "BitChordMix fire path=$path recipe=$recipe level=$level stems=$stems prepLeadSec=$prepLeadSec buildMs=$buildMs reason=$reason")
        }
    }

    fun nativeRouteFields(
        plan: LoopMix.Plan,
        outLoops: Boolean? = null,
        inLoops: Boolean? = null,
        actualConfiguration: Boolean = false,
        coarseDecline: Boolean? = null,
    ): Map<String, Any?> {
        val configurationKnown = actualConfiguration || outLoops != null || inLoops != null
        // Telemetry may be assembled on the playback ticker. Keep its planned
        // endpoint summaries scalar-only; the full shared qualifier allocates
        // a clock and is run at admission, not again for every route event.
        val targetRate = plan.targetBpm / plan.out.bpm
        val reciprocalRate = 1.0 / targetRate
        val ratesFinite = plan.outLoops && plan.inLoops && targetRate.isFinite() && targetRate > 0.0 &&
            reciprocalRate.isFinite() && reciprocalRate > 0.0
        val isConfiguredCrown = if (configurationKnown) outLoops == true && inLoops == true
            else plan.outLoops && plan.inLoops
        val technique = when {
            configurationKnown && (outLoops == null || inLoops == null) -> "unknown-native"
            configurationKnown && outLoops == true && inLoops == true -> "crown-loop"
            configurationKnown -> "native-bridge"
            plan.outLoops && plan.inLoops -> "crown-loop" // requested candidate only
            else -> "native-bridge"
        }
        return mapOf(
            "technique" to technique,
            "rung" to plan.rung,
            "requestedOutLoops" to plan.outLoops,
            "requestedInLoops" to plan.inLoops,
            "configuredOutLoops" to outLoops,
            "configuredInLoops" to inLoops,
            "outLoopStart" to plan.out.loopStart,
            "outLoopEnd" to plan.out.loopEnd,
            "inLoopStart" to plan.inn.loopStart,
            "inLoopEnd" to plan.inn.loopEnd,
            "fold" to plan.fold,
            "rampBars" to plan.rampBars,
            "echoTail" to plan.echoTail,
            "carve" to plan.carve,
            "clash" to plan.clash,
            "coarseDecline" to coarseDecline,
            "coarseOverride" to (coarseDecline == true && isConfiguredCrown),
            "plannedOutGridSource" to if (plan.out.anchorBeats != null) "tracked" else "constant-grid",
            "plannedInGridSource" to if (plan.inn.anchorBeats != null) "tracked" else "constant-grid",
            "outGridSource" to "unknown",
            "inGridSource" to "unknown",
            "plannedOutMinRate" to if (ratesFinite) minOf(1.0, targetRate) else null,
            "plannedOutMaxRate" to if (ratesFinite) maxOf(1.0, targetRate) else null,
            "plannedInMinRate" to if (ratesFinite) minOf(1.0, reciprocalRate) else null,
            "plannedInMaxRate" to if (ratesFinite) maxOf(1.0, reciprocalRate) else null,
        )
    }

    fun nativeSessionRouteFields(session: LoopMixSession, coarseDecline: Boolean? = null): Map<String, Any?> =
        nativeRouteFields(
            session.plan,
            session.configuredOutLoops,
            session.configuredInLoops,
            actualConfiguration = true,
            coarseDecline = coarseDecline,
        ) + mapOf(
            "outGridSource" to session.outGridSource.telemetryValue,
            "inGridSource" to session.inGridSource.telemetryValue,
            "choreo" to session.choreoMove, // AUTOMIX-V2(orch): executed-move attribution
            // The real execution path, not the planned one: straight routes must be distinguishable.
            "path" to session.executionPath,
            "preparationGeneration" to session.preparationGeneration,
            "planId" to session.planId,
            "recipeId" to session.choreoRecipeId,
        )

    fun classicRouteFields(plan: TransitionPlan?): Map<String, Any?> = mapOf(
        "technique" to "classic",
        "transitionStyle" to plan?.transitionStyle?.name,
        "filterVariant" to plan?.filterVariant?.name,
        "djEffect" to plan?.djEffect?.name,
        "localBlendAdmission" to (plan?.policyReasons?.contains(LocalBlendAdmission.MARKER) == true),
        "plannedIncomingRate" to plan?.incomingPlaybackRate,
    )

    fun acceptRoute(plan: Any, fields: Map<String, Any?>) {
        reviseRoute(plan)
        val key = "accepted|${fields["technique"]}|$planRevision"
        if (routingEvidenceKeys.add(key)) mixEvent("mix.plan", fields + mapOf("stage" to "accepted"))
    }

    fun reviseRoute(plan: Any): Int {
        if (revisionRoute != plan) {
            revisionRoute = plan
            planRevision++
        }
        return planRevision
    }

    fun preparedRoute(
        fields: Map<String, Any?>,
        context: Opportunity? = opportunity,
        revision: Int = planRevision,
        generation: Int? = null,
    ) {
        if (context != opportunity || terminalRecorded) return
        val key = "prepared|${fields["technique"]}|$revision|$generation"
        if (routingEvidenceKeys.add(key)) {
            mixEvent(
                "mix.plan",
                fields + mapOf(
                    "stage" to "prepared",
                    "planRevision" to revision,
                    "buildGeneration" to generation,
                ),
                context,
            )
        }
    }

    fun refusalCode(reason: String): String = reason
        .replace(REGEX_DEADLINE_OVER, " deadline-over")
        .replace(REGEX_EARLY_ABORT, " early-abort")

    fun routeRefusal(
        name: String,
        reason: String,
        stage: String,
        technique: String,
        destination: String? = null,
        context: Opportunity? = opportunity,
        revision: Int = planRevision,
        generation: Int? = null,
        coarseDecline: Boolean? = null,
    ) {
        if (context != opportunity || terminalRecorded) return
        val code = refusalCode(reason)
        if (!routingEvidenceKeys.add("refusal|$stage|$technique|$code")) return
        mixEvent(
            name,
            mapOf(
                "stage" to stage,
                "technique" to technique,
                "reason" to reason,
                "refusalCode" to code,
                "fallbackTo" to destination,
                "phase" to host.phaseName,
                "planRevision" to revision,
                "buildGeneration" to generation,
                "coarseDecline" to coarseDecline,
            ),
            context,
        )
    }

    companion object {
        private const val TAG = "MixEventRouter"
        const val BAIL_MS = 120L
        private val REGEX_DEADLINE_OVER = Regex("\\s+\\d+ ms over$")
        private val REGEX_EARLY_ABORT = Regex(" early-abort \\(\\d+ ms left\\)$")
    }
}
