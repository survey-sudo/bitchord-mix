package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalBlendAdmissionTest {
    private fun track(id: String, bpm: Double, voice: Float): TrackAnalysis {
        val beats = List((188.0 * bpm / 60).toInt()) { it * 60.0 / bpm }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, quality = TrackAnalysis.QUALITY_FULL,
            trackId = id, duration = 188.0, contentEndTime = 188.0,
            bpm = bpm, beatInterval = 60.0 / bpm, beatConfidence = 0.95,
            downbeats = beats.filterIndexed { i, _ -> i % 4 == 0 },
            headBeats = BeatSide(beats.take(96), emptyList(), bpm, 0.95),
            tailBeats = BeatSide(beats.takeLast(96), emptyList(), bpm, 0.95),
            key = "B minor", keyConfidence = 0.9,
            keyTail = "B major", keyTailConfidence = 0.05,
            energyCurve = List(189) { EnergySample(it.toDouble(), 1.0) },
            vocalActivityMask = List(189) { voice.toDouble() }, vocalProbability = 0.95,
            vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(3760) { voice })),
        )
    }

    private val out get() = track("local-out", 142.857142857, 0.85f)
    private val inn get() = track("local-in", 130.434782608, 0.02f)
    private fun plan(a: TrackAnalysis = out, b: TrackAnalysis = inn, listenerSpeed: Double = 1.0) = planTransition(
        a, b, TransitionTrackInfo(a.trackId, 188_000), TransitionTrackInfo(b.trackId, 188_000),
        currentTime = 10.0, duration = 188.0, mode = CrossfadeMode.SMART, advanced = true,
        listenerSpeed = listenerSpeed,
    )

    @Test
    fun coarsePredicateRemainsButMeasuredOrdinaryCandidateCanOverrideIt() {
        assertTrue(shouldDeclineToMix(out, inn))
        val candidate = plan()
        assertTrue(LocalBlendAdmission.isQualified(candidate, out, inn), candidate.toString())
        assertEquals(TransitionStyle.DJ_BLEND, candidate.transitionStyle)
        assertEquals(1.0952, candidate.incomingPlaybackRate, 0.0001)
        assertEquals(0.0, candidate.echoSeconds)
        // Regional B major is untrusted; global B minor remains the confidence-matched fallback.
        assertFalse(candidate.mixSummary.contains("clash"), candidate.mixSummary)
        assertTrue(candidate.mixSummary.contains("10A→10A"), candidate.mixSummary)
    }

    @Test
    fun localBlendFitsExactlyAtThreeXButDeclinesJustAboveTheCompositeCap() {
        val normal = plan()
        assertTrue(LocalBlendAdmission.MARKER in normal.policyReasons)
        val boundarySpeed = 3f / normal.incomingPlaybackRate.toFloat()
        assertTrue(localBlendFitsDeckSpeedCap(boundarySpeed.toDouble(), normal.incomingPlaybackRate))

        val atBoundary = plan(listenerSpeed = boundarySpeed.toDouble())
        assertTrue(LocalBlendAdmission.MARKER in atBoundary.policyReasons, atBoundary.toString())
        assertEquals(3f, boundarySpeed * atBoundary.incomingPlaybackRate.toFloat(), 0.0001f)

        val aboveBoundary = boundarySpeed + 0.001f
        assertFalse(localBlendFitsDeckSpeedCap(aboveBoundary.toDouble(), normal.incomingPlaybackRate))
        val declined = plan(listenerSpeed = aboveBoundary.toDouble())
        assertFalse(LocalBlendAdmission.MARKER in declined.policyReasons, declined.toString())
        assertTrue("local-blend-refused:listener-speed-cap" in declined.policyReasons, declined.toString())
        assertTrue(declined.reason.contains("decline-to-mix"), declined.toString())
    }

    @Test
    fun measuredLeadCollisionCannotBePurchasedWithBeatConfidence() {
        val b = inn.copy(vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(3760) { 0.9f })),
            vocalActivityMask = List(189) { 0.9 })
        val verdict = LocalBlendAdmission.evaluate(plan(), out, b)
        assertFalse(verdict.admitted)
        assertEquals("simultaneous-lead-collision", verdict.refusal)
        assertTrue(requireNotNull(verdict.weightedCollision) > 0.6)
        val fallback = plan(out, b)
        assertFalse(LocalBlendAdmission.MARKER in fallback.policyReasons)
        assertTrue(fallback.policyReasons.any { it == "local-blend-refused:simultaneous-lead-collision" })
        assertEquals(TransitionStyle.NATURAL, fallback.transitionStyle)
        assertEquals(0.0, fallback.fadeSeconds)
    }

    @Test
    fun missingOrPartialRawCoverageStaysUnknownEvenWithAQuietCoarseMask() {
        val missing = inn.copy(vocalWindows = emptyList(), vocalActivityMask = emptyList())
        val verdict = LocalBlendAdmission.evaluate(plan(), out, missing)
        assertFalse(verdict.admitted)
        assertEquals("unknown-in-vocal-coverage", verdict.refusal)
        assertNull(verdict.weightedCollision)
        assertTrue(plan(out, missing).policyReasons.contains("local-blend-refused:unknown-in-vocal-coverage"))
        val partial = inn.copy(vocalWindows = listOf(CurveWindow(80.0, 20.0, FloatArray(100) { 0.0f })))
        assertEquals("unknown-in-vocal-coverage", LocalBlendAdmission.evaluate(plan(), out, partial).refusal)
        val invalid = inn.copy(vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(3760) { Float.NaN })))
        assertEquals("unknown-in-vocal-coverage", LocalBlendAdmission.evaluate(plan(), out, invalid).refusal)
    }

    @Test
    fun physicalRateAndGridControlsDoNotBroadenTheGlobalCap() {
        val far = inn.copy(bpm = 100.0, headBeats = null,
            downbeats = List(78) { it * 2.4 })
        assertEquals("outside-upstream-rate-window", LocalBlendAdmission.gridRefusal(out, far))
        assertFalse(LocalBlendAdmission.MARKER in plan(out, far).policyReasons)
        val weak = inn.copy(headBeats = inn.headBeats?.copy(confidence = 0.54))
        assertEquals("weak-join-grid", LocalBlendAdmission.gridRefusal(out, weak))
        val unfit = inn.copy(headBeats = BeatSide(List(24) { it * 3.0 }, emptyList(), inn.bpm, 0.99))
        assertEquals("unfit-join-grid", LocalBlendAdmission.gridRefusal(out, unfit))
        assertEquals("executed-rate-mismatch", LocalBlendAdmission.evaluate(plan().copy(incomingPlaybackRate = 1.08), out, inn).refusal)
        assertEquals("executed-rate-mismatch", LocalBlendAdmission.evaluate(plan().copy(phaseLock = true), out, inn).refusal)
    }

    @Test
    fun upstreamRestorationDoesNotClaimAdvancedIncomingSlowdown() {
        val fasterIncoming = track("faster-in", out.bpm / 0.95, 0.02f)
        assertNull(LocalBlendAdmission.gridRefusal(out, fasterIncoming)) // Original physical reachability still includes 0.95.
        val normal = plan(out, fasterIncoming)
        assertFalse(LocalBlendAdmission.MARKER in normal.policyReasons)
        assertEquals(TransitionStyle.DJ_BLEND, normal.transitionStyle) // Existing close-tempo policy is unchanged.
        assertEquals(0.95, normal.incomingPlaybackRate, 0.0001)
        assertEquals("upstream-incoming-slowdown", LocalBlendAdmission.evaluate(
            normal, out, fasterIncoming).refusal)
        assertEquals(1.0, normal.outgoingPlaybackRate)
    }

    @Test
    fun confidenceMatchedKnownKeyClashStillRefusesAndUntrustedKeyLabelsStayUnknown() {
        val clash = out.copy(keyTail = "B major", keyTailConfidence = 0.95)
        assertEquals("known-key-clash", LocalBlendAdmission.evaluate(plan(), clash, inn).refusal)
        assertFalse(LocalBlendAdmission.MARKER in plan(clash, inn).policyReasons)
        val unknown = out.copy(key = "B major", keyConfidence = 0.05)
        val candidate = plan(unknown, inn.copy(keyConfidence = 0.05))
        assertTrue(LocalBlendAdmission.MARKER in candidate.policyReasons)
        assertTrue(candidate.mixSummary.contains("key unknown"), candidate.mixSummary)
        assertFalse(candidate.mixSummary.contains("clash"))
        val medium = out.copy(keyTail = "B major", keyTailConfidence = 0.30)
        val estimated = plan(medium, inn)
        assertTrue(LocalBlendAdmission.MARKER in estimated.policyReasons)
        assertEquals(TransitionStyle.DJ_BLEND, estimated.transitionStyle)
        assertTrue(estimated.mixSummary.contains("estimated"), estimated.mixSummary)
        assertFalse(estimated.mixSummary.contains("clash"), estimated.mixSummary)
        assertTrue(estimated.policyReasons.none { it.contains("clash") })
    }

    @Test
    fun nativeRampedPlanAdmitsASlowdownTheClassicPathStillRefuses() {
        val fasterIncoming = track("faster-in", out.bpm / 0.95, 0.02f)
        // Measured spans on both sides, so only the rate gate differs between the
        // two candidates: expectedRate here is exactly 0.95.
        val classic = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 10.0, transitionEnd = 14.0, fadeSeconds = 4.0,
            incomingCueTime = 0.0, beatSeconds = 0.42, incomingPlaybackRate = 0.95,
        )
        assertEquals(
            "upstream-incoming-slowdown",
            LocalBlendAdmission.evaluate(classic, out, fasterIncoming).refusal,
        )
        val native = classic.copy(nativeRamped = true)
        val verdict = LocalBlendAdmission.evaluate(native, out, fasterIncoming)
        assertTrue(verdict.admitted, verdict.toString())
        // The flag admits a slowdown; it never widens the executable window nor
        // relaxes the outgoing side.
        assertEquals(
            "executed-rate-mismatch",
            LocalBlendAdmission.evaluate(native.copy(incomingPlaybackRate = 0.92), out, fasterIncoming).refusal,
        )
        assertEquals(
            "executed-rate-mismatch",
            LocalBlendAdmission.evaluate(native.copy(outgoingPlaybackRate = 0.97), out, fasterIncoming).refusal,
        )
    }

    @Test
    fun declineVerdictsHoldTheHandoffWithADistinguishableRung() {
        val b = inn.copy(vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(3760) { 0.9f })),
            vocalActivityMask = List(189) { 0.9 })
        val fallback = plan(out, b)
        // A measured lead collision is an honest decline: the rescue ladder may
        // not re-propose the overlap that evidence vetoed, and the rung that
        // held it is distinguishable from a rung-less collapse.
        assertEquals(TransitionStyle.NATURAL, fallback.transitionStyle)
        assertEquals(0.0, fallback.fadeSeconds)
        assertTrue(fallback.reason.contains("decline-to-mix"), fallback.reason)
        assertTrue("decline-holds-no-overlap" in fallback.policyReasons, fallback.policyReasons.toString())
        assertTrue("natural-handoff-no-overlap" in fallback.policyReasons, fallback.policyReasons.toString())
        assertTrue(
            fallback.policyReasons.any { it.startsWith("local-blend-refused:") },
            fallback.policyReasons.toString(),
        )
    }

    @Test
    fun tempoGapCannotSelectHashEchoFromMissingVocalInputs() {
        val base = TransitionPlan(transitionStyle = TransitionStyle.DJ_FILTER,
            transitionStart = 170.0, transitionEnd = 188.0, fadeSeconds = 18.0,
            incomingCueTime = 0.0, beatSeconds = 0.42, echoSeconds = 0.42,
            filterVariant = FilterTransitionVariant.ECHO_RIDE)
        for (missingOut in listOf(true, false)) {
            val a = if (missingOut) out.copy(vocalWindows = emptyList(), vocalActivityMask = emptyList()) else out
            val b = if (!missingOut) inn.copy(vocalWindows = emptyList(), vocalActivityMask = emptyList()) else inn
            val annotated = DjMix.annotate(base, a, b, outgoingId = "fx-out", incomingId = "fx-in")
            assertEquals(DjEffect.NONE, annotated.djEffect)
            assertEquals(0.0, annotated.echoSeconds)
        }
    }
}
