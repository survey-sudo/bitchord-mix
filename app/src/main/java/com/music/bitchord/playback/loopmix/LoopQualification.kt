package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.djmix.StraightRateMode
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.AutomixV2Choreo
import com.music.bitchord.playback.smart.choreo.DjLevel
import java.util.concurrent.atomic.AtomicReference

/** Platform facade for shared qualification and prepared-coverage diagnostics. */
internal object LoopQualification {

    /** One regional, peak-safe gain shared by canonical geometry and native IN setup. */
    internal fun regionalIncomingLevel(
        plan: LoopMix.Plan, out: LoopRegion, inn: LoopRegion, sampleRate: Int, fallback: Float,
    ): Float = PlaybackGeometryQualification.regionalIncomingLevel(plan, out, inn, sampleRate, fallback)

    /** Last non-user-visible geometry failure class, readable by diagnostics/tests only. */
    private val geometryFailureClass = AtomicReference<String?>(null)
    internal fun lastPreparedGeometryFailureClass(): String? = geometryFailureClass.get()
    internal fun recordPreparedGeometryFailure(error: Throwable) {
        geometryFailureClass.set(error.javaClass.name)
    }

    /** Interpolate the exact arrays supplied to native, in source seconds. */
    internal fun knotSourceAt(frames: DoubleArray, positions: DoubleArray, mixSec: Double, sampleRate: Int): Double? =
        PlaybackGeometryQualification.knotSourceAt(frames, positions, mixSec, sampleRate)

    internal fun qualifyHandoffKnots(
        plan: LoopMix.Plan, clock: LoopMix.Clock, sampleRate: Int, knots: DeckKnots,
    ): String? = PlaybackGeometryQualification.qualifyHandoffKnots(plan, clock, sampleRate, knots)

    internal fun coversHandoffPcm(
        plan: LoopMix.Plan, sampleRate: Int, knots: DeckKnots, out: LoopRegion, inn: LoopRegion,
    ): Boolean = PlaybackGeometryQualification.coversHandoffPcm(plan, sampleRate, knots, out, inn)

    internal fun qualifyCrownKnots(
        plan: LoopMix.Plan, clock: LoopMix.Clock, sampleRate: Int, knots: DeckKnots,
    ): CrownKnotQualification = PlaybackGeometryQualification.qualifyCrownKnots(plan, clock, sampleRate, knots)

    internal fun trackedBeatsInWindow(
        sideBeats: List<Double>?, anchorStart: Double, count: Int, beatSec: Double,
    ): DoubleArray? = PlaybackGeometryQualification.trackedBeatsInWindow(sideBeats, anchorStart, count, beatSec)

    /** Coordinates here remain track seconds; native subtracts region.start. */
    internal fun incomingGeometry(plan: LoopMix.Plan, clock: LoopMix.Clock, knots: DeckKnots): IncomingGeometry =
        PlaybackGeometryQualification.incomingGeometry(plan, clock, knots)

    internal fun coversPhysical(region: LoopRegion, sampleRate: Int, requiredStart: Double, requiredEnd: Double): Boolean =
        PlaybackGeometryQualification.coversPhysical(region, sampleRate, requiredStart, requiredEnd)

    internal fun coversOutgoingStartAndLoop(region: LoopRegion, sampleRate: Int, plan: LoopMix.Plan): Boolean =
        PlaybackGeometryQualification.coversOutgoingStartAndLoop(region, sampleRate, plan)

    /** Exact build request, including actual tracked/straight knots and handover tail. */
    internal fun decodeRequirements(
        plan: LoopMix.Plan, sampleRate: Int, outAnalysis: TrackAnalysis?, inAnalysis: TrackAnalysis?,
        straightRateMode: StraightRateMode? = null, level: DjLevel = AutomixV2Choreo.level,
    ): LoopMix.RegionBounds =
        PlaybackGeometryQualification.decodeRequirements(plan, sampleRate, outAnalysis, inAnalysis, straightRateMode, level)

    /** PREPARED means complete, usable PCM on the same geometry/rate contract as build. */
    internal fun preparedCoverageRefusal(
        plan: LoopMix.Plan, sampleRate: Int, out: LoopRegion?, inn: LoopRegion?,
        outAnalysis: TrackAnalysis?, inAnalysis: TrackAnalysis?, straightRateMode: StraightRateMode? = null,
        level: DjLevel = AutomixV2Choreo.level,
    ): String? {
        if (sampleRate <= 0) return "prepared_invalid_sample_rate"
        if (out == null || inn == null) return "prepared_pcm_missing"
        val bounds = try {
            decodeRequirements(plan, sampleRate, outAnalysis, inAnalysis, straightRateMode, level)
                .also { geometryFailureClass.set(null) }
        } catch (error: Throwable) {
            recordPreparedGeometryFailure(error)
            return "prepared_invalid_geometry"
        }
        return PlaybackGeometryQualification.preparedCoverageRefusal(plan, sampleRate, out, inn, outAnalysis, bounds)
    }
}
