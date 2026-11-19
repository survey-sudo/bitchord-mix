package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Fixed planner-level route witnesses. Not controller acceptance or renderer execution. */
class LoopRoutingReplayTest {
    private data class Witness(
        val id: String,
        val out: TrackAnalysis,
        val inn: TrackAnalysis,
        val outLyrics: List<LoopMix.Span>? = emptyList(),
        val inLyrics: List<LoopMix.Span>? = emptyList(),
        val fold: Double? = null,
        val expectedCrown: Boolean? = null,
    )

    private fun analysis(
        duration: Double,
        localBpm: Double,
        globalBpm: Double = localBpm,
        wholeVocal: Double = 0.0,
        mask: (Double) -> Float = { 0.05f },
        rms: (Double) -> Double = { 0.3 },
        withMask: Boolean = true,
    ): TrackAnalysis {
        val beat = 60.0 / localBpm
        val start = 0.5
        val frames = (duration * 10.0).toInt()
        fun side(from: Double, to: Double): BeatSide {
            val beats = generateSequence(from) { it + beat }.takeWhile { it <= to + 1e-8 }.toList()
            return BeatSide(beats, beats.filterIndexed { index, _ -> index % 4 == 0 }, localBpm, 0.9)
        }
        val maskWindows = if (withMask) listOf(CurveWindow(0.0, 10.0, FloatArray(frames) { i -> mask(i / 10.0) })) else emptyList()
        val levelWindows = listOf(CurveWindow(0.0, 10.0, FloatArray(frames) { i -> levelFromRms(rms(i / 10.0)).toFloat() }))
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = duration,
            bpm = globalBpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = generateSequence(start) { it + 4.0 * beat }.takeWhile { it < duration }.toList(),
            firstBeat = start,
            vocalProbability = wholeVocal,
            headBeats = side(start, minOf(duration - beat, 60.5)),
            tailBeats = side(maxOf(start, duration - 60.0 + beat), duration - beat),
            vocalWindows = maskWindows,
            levelWindows = levelWindows,
        )
    }

    private fun witnesses(): List<Witness> {
        val cleanOut = analysis(200.0, 120.0, mask = { if (it >= 190.0 && it < 198.5) 0.05f else 0.9f })
        val cleanIn = analysis(180.0, 120.0, mask = { if (it < 8.5) 0.05f else 0.9f })
        val conflictOut = cleanOut.copy(vocalProbability = 0.9)
        val conflictIn = cleanIn.copy(bpm = 132.0, vocalProbability = 0.9)
        return listOf(
            Witness("clean-control", cleanOut, cleanIn, expectedCrown = true),
            // Result deliberately not asserted against one binary policy; the
            // paired replay reports the before/after candidate observation.
            Witness("whole-track-conflict", conflictOut, conflictIn),
            Witness(
                "local-sung-veto",
                analysis(200.0, 120.0, mask = { 0.9f }), cleanIn,
                outLyrics = listOf(LoopMix.Span(188.0, 200.0)), expectedCrown = false,
            ),
            Witness("unmeasured-mask", analysis(200.0, 120.0, withMask = false), cleanIn,
                outLyrics = null, inLyrics = null, expectedCrown = false),
            Witness("quiet-corridor", analysis(200.0, 120.0, rms = { 0.0 }), cleanIn,
                expectedCrown = false),
            Witness("wide-120-to-134", analysis(200.0, 120.0), analysis(180.0, 134.0), expectedCrown = true),
            Witness("wide-111-to-169-fold2", analysis(200.0, 111.0), analysis(180.0, 169.0), fold = 2.0, expectedCrown = true),
            Witness("wide-170-to-80-fold-half", analysis(200.0, 170.0), analysis(180.0, 80.0), fold = 0.5, expectedCrown = true),
            Witness("declined-native-bridge", analysis(200.0, 120.0, wholeVocal = 0.9, mask = { 0.9f }),
                conflictIn, expectedCrown = false),
        )
    }

    @Test
    fun fixedPlannerRouteReplayReportsCandidateAndSeparatesUnobservedStages() {
        var crownCandidates = 0
        var nativeBridgeCandidates = 0
        var globalDeclines = 0
        for (w in witnesses()) {
            val why = StringBuilder()
            val fold = w.fold ?: alignedFold(w.out.bpm, w.inn.bpm)
            val crown = LoopMix.plan(w.out, 200.0, w.inn, 180.0, 0.0,
                w.outLyrics, w.inLyrics, why = why)
            val decline = shouldDeclineToMix(w.out, w.inn)
            if (decline) globalDeclines++
            if (w.id == "whole-track-conflict") assertTrue(decline)
            if (w.id == "declined-native-bridge") assertTrue(decline)
            w.expectedCrown?.let { expected -> assertEquals(expected, crown != null, "${w.id}: $why") }
            if (w.id == "clean-control") {
                val plan = assertNotNull(crown)
                assertEquals(190.5, plan.out.loopStart, 1e-9)
                assertEquals(198.5, plan.out.loopEnd, 1e-9)
                assertEquals(0.5, plan.inn.loopStart, 1e-9)
                assertEquals(8.5, plan.inn.loopEnd, 1e-9)
            }
            if (w.id.startsWith("wide-")) {
                val plan = assertNotNull(crown, w.id)
                assertEquals(w.fold ?: 1.0, plan.fold, 1e-9)
            }
            val selected = crown ?: LoopMix.bridge(w.out, 200.0, w.inn, 180.0, 0.0,
                w.outLyrics, w.inLyrics)
            val route = when {
                selected == null -> "classic"
                selected.outLoops && selected.inLoops -> "native-crown"
                else -> "native-bridge:${selected.rung}"
            }
            if (route == "native-crown") crownCandidates++
            if (route.startsWith("native-bridge")) nativeBridgeCandidates++
            if (w.id == "declined-native-bridge") {
                val bridge = assertNotNull(selected)
                assertFalse(bridge.outLoops && bridge.inLoops)
                assertTrue(route.startsWith("native-bridge"))
            }
            // A deterministic cold-policy observation only; it does not assert
            // controller acceptance or that a real fallback was executed.
            val coldYield = LoopMix.shouldYieldColdLoop(
                loopStartMs = 190_500L, currentPositionMs = 182_000L,
                fastReady = false, building = false, blendArmLeadMs = 8_500L,
                nextStreamReady = true,
            )
            val warmYield = LoopMix.shouldYieldColdLoop(
                loopStartMs = 190_500L, currentPositionMs = 182_000L,
                fastReady = true, building = false, blendArmLeadMs = 8_500L,
                nextStreamReady = true,
            )
            println("ROUTE_REPLAY|${w.id}|candidate=$route|globalDecline=$decline|" +
                "fold=$fold|rampBars=${selected?.rampBars ?: -1}|outLoops=${selected?.outLoops}|inLoops=${selected?.inLoops}|" +
                "effects=${selected?.echoTail}:${selected?.flanger}:${selected?.reverbWash}|localReason=$why|" +
                "coldYield=$coldYield|warmYield=$warmYield|accepted=unobserved|prepared=unobserved|executed=unobserved|terminal=unobserved")
        }
        assertTrue(crownCandidates >= 4)
        assertTrue(nativeBridgeCandidates > 0)
        assertTrue(globalDeclines >= 2)
        println("ROUTE_REPLAY_SUMMARY|opportunities=9|crownCandidates=$crownCandidates|" +
            "nativeBridgeCandidates=$nativeBridgeCandidates|globalDeclines=$globalDeclines|" +
            "controllerAccepted=unobserved|prepared=unobserved|rendererOutput=separate-host-JNI-suite|" +
            "controllerTakeover=unobserved|delegateAcceptance=unobserved|listening=unobserved")
    }
}
