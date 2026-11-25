package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.DeckRenderSetupV1
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.RenderGeometryV1
import com.music.bitchord.djmix.core.contracts.StemKind as CoreStemKind
import com.music.bitchord.djmix.core.contracts.StemRegionRef
import com.music.bitchord.playback.loopmix.DeckKnots
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.loopmix.PlaybackGeometryQualification
import com.music.bitchord.playback.loopmix.PlaybackIncomingStart
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.StemSet
import kotlin.math.abs
import kotlin.math.roundToLong

/** Exact prepared geometry and region projections for the sole core recipe compiler. */
object CanonicalGeometryBuilder {
    fun canonicalRegionRefs(
        outId: String, inId: String, out: LoopRegion, inn: LoopRegion, sampleRate: Int,
        geometry: com.music.bitchord.djmix.core.contracts.RenderGeometryV1,
    ): Pair<AudioRegionRef, AudioRegionRef> {
        fun endMs(region: LoopRegion): Long =
            ((region.start + minOf(region.left.size, region.right.size).toDouble() / sampleRate) * 1000.0).roundToLong()
        return AudioRegionRef(
            deck = DeckTarget.OUT, trackId = outId, startMs = (out.start * 1000.0).roundToLong(),
            endMs = endMs(out), sampleRateHz = sampleRate,
            prerollMs = (geometry.out.prerollSec * 1000.0).roundToLong(),
            stopAtMixMs = (geometry.out.stopAtMixSec * 1000.0).roundToLong(),
            levelGain = geometry.out.levelGain, runOn = geometry.out.runOn,
        ) to AudioRegionRef(
            deck = DeckTarget.IN, trackId = inId, startMs = (inn.start * 1000.0).roundToLong(),
            endMs = endMs(inn), sampleRateHz = sampleRate,
            prerollMs = (geometry.inbound.prerollSec * 1000.0).roundToLong(),
            stopAtMixMs = (geometry.inbound.stopAtMixSec * 1000.0).roundToLong(),
            fadeOutTrackMs = (geometry.inbound.fadeOutTrackSec!! * 1000.0).roundToLong(),
            levelGain = geometry.inbound.levelGain, runOn = geometry.inbound.runOn,
        )
    }

    fun canonicalStemRefs(
        trackId: String, deck: DeckTarget, region: LoopRegion, stems: StemSet?, sampleRate: Int,
    ): List<StemRegionRef> {
        if (stems == null || sampleRate <= 0 || stems.sampleRate != sampleRate ||
            !stems.regionStartSec.isFinite() || abs(stems.regionStartSec - region.start) > 0.5 / sampleRate ||
            stems.frames != region.left.size || stems.frames != region.right.size ||
            stems.left.keys != stems.right.keys ||
            stems.left.values.any { it.size != stems.frames } || stems.right.values.any { it.size != stems.frames }
        ) return emptyList()
        fun kind(value: com.music.bitchord.playback.smart.StemKind): CoreStemKind = when (value) {
            com.music.bitchord.playback.smart.StemKind.VOCALS -> CoreStemKind.VOCALS
            com.music.bitchord.playback.smart.StemKind.DRUMS -> CoreStemKind.DRUMS
            com.music.bitchord.playback.smart.StemKind.BASS -> CoreStemKind.BASS
            com.music.bitchord.playback.smart.StemKind.OTHER -> CoreStemKind.OTHER
        }
        val endMs = ((stems.regionStartSec + stems.frames.toDouble() / sampleRate) * 1000.0).roundToLong()
        return stems.left.keys.mapNotNull { stem ->
            if (stems.right[stem] == null) null else StemRegionRef(
                deck = deck, stem = kind(stem), trackId = trackId,
                startMs = (stems.regionStartSec * 1000.0).roundToLong(), endMs = endMs, sampleRateHz = sampleRate,
            )
        }
    }

    fun canonicalGeometry(
        plan: LoopMix.Plan, clock: LoopMix.Clock, knots: DeckKnots,
        out: LoopRegion, inn: LoopRegion, sampleRate: Int, inLevel: Float,
        highQuality: Boolean, outStopAtSec: Double,
    ): RenderGeometryV1 {
        require(sampleRate > 0)
        val sr = sampleRate.toDouble()
        fun frames(region: LoopRegion, trackSec: Double) = (trackSec - region.start) * sr
        val incoming = PlaybackGeometryQualification.incomingGeometry(plan, clock, knots)
        val outLoopEnd = if (plan.outLoops) plan.out.loopEnd else plan.out.loopStart
        val inLoopEnd = if (incoming.loops) plan.inn.loopEnd else plan.inn.loopStart
        val (incomingStartMix, incomingStartTrack) = PlaybackIncomingStart.incomingStartFor(clock, incoming.handoverTrack)
        return RenderGeometryV1(
            out = DeckRenderSetupV1(
                regionStartSec = out.start, frameCount = minOf(out.left.size, out.right.size),
                loopStartFrame = frames(out, plan.out.loopStart).roundToLong(),
                loopEndFrame = frames(out, outLoopEnd).roundToLong(), releaseAtFrame = null,
                knotFrames = knots.outFrames.toList(), knotPositions = knots.outPositionsSec.map { frames(out, it) },
                runOn = false, stopAtMixSec = outStopAtSec, prerollSec = 0.4,
                levelGain = 1f, fadeOutTrackSec = null,
            ),
            inbound = DeckRenderSetupV1(
                regionStartSec = inn.start, frameCount = minOf(inn.left.size, inn.right.size),
                loopStartFrame = frames(inn, plan.inn.loopStart).roundToLong(),
                loopEndFrame = frames(inn, inLoopEnd).roundToLong(),
                releaseAtFrame = if (incoming.loops) frames(inn, incoming.releaseTrack) else null,
                knotFrames = knots.inFrames.toList(), knotPositions = knots.inPositionsSec.map { frames(inn, it) },
                runOn = true, stopAtMixSec = clock.elementAt + 2.0, prerollSec = 0.4,
                levelGain = inLevel, fadeOutTrackSec = incoming.handoverTrack,
            ),
            handoverMixSec = clock.elementAt, handoverTrackSec = incoming.handoverTrack,
            incomingStartMixSec = incomingStartMix, incomingStartTrackSec = incomingStartTrack,
            highQuality = highQuality,
        )
    }
}
