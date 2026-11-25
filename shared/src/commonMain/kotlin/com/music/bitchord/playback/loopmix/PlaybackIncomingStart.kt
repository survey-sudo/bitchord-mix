package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.smart.LoopMix
import kotlin.math.max
import kotlin.math.min

/** Incoming player startup coordinates on the prepared deck timeline. */
object PlaybackIncomingStart {
    /** Mix seconds into the mix at which the incoming player starts, silent. */
    const val STARTUP_LEAD = 0.5

    /**
     * The incoming player's start, computed from a plan clock alone:
     * startAt into the mix, at track position startTrack. The hold
     * branch can land the start after handoffAt (the vc64 freeze); the
     * clamp keeps at least STARTUP_LEAD of decode head-room before
     * elementAt so driveLoopMix cannot run out of mix before the start
     * (djmix23, vc65). Pure JVM, no renderer needed.
     */
    fun incomingStartFor(
        clock: LoopMix.Clock,
        handoverTrack: Double = clock.elementTrackTime,
    ): Pair<Double, Double> {
        val hold = clock.incomingHoldSec
        val element = clock.elementAt
        val base = if (hold > 1e-9) {
            minOf(max(STARTUP_LEAD, hold - 1.5), max(STARTUP_LEAD, element - 3.0))
        } else {
            STARTUP_LEAD
        }
        val tBase = handoverTrack - (element - base)
        var inStartAt = if (tBase < 0.0 && handoverTrack >= 2.5) {
            max(base, (element - handoverTrack).coerceAtLeast(STARTUP_LEAD))
        } else {
            base
        }
        inStartAt = clampIncomingStartAt(inStartAt, element, STARTUP_LEAD)
        val inStartTrack = max(0.0, handoverTrack - (element - inStartAt))
        return inStartAt to inStartTrack
    }

    /** Latest cue: at least [startupLead] before elementAt when feasible. */
    fun clampIncomingStartAt(inStartAt: Double, elementAt: Double, startupLead: Double = STARTUP_LEAD): Double {
        val latest = (elementAt - startupLead).coerceAtLeast(startupLead)
        return min(inStartAt, latest)
    }
}
