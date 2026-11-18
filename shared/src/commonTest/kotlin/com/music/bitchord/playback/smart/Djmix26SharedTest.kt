package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * djmix26 shared suite (Task 2A countdown/scrub + Task 2B beat/downbeat/ramp/echo/scoop/decline):
 * CountdownStabilizer, PhrasePlanner.Lock anti-jump, spurious seek filtering,
 * deferred loop-tail disarm, HEAD -> FULL stability, post-scrub catch-up,
 * downbeat snapping, integrated ramp hold, tracked-beat knots, echo/scoop
 * specs, bounded decline, key-clash blend, readiness leads.
 */
class Djmix26SharedTest {

    private fun analysis(
        id: String,
        bpm: Double,
        duration: Double = 200.0,
        quality: String = TrackAnalysis.QUALITY_FULL,
        contentEndTime: Double = duration - 4.0,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val bar = 4.0 * beat
        val downbeats = buildList<Double> {
            var t = 0.5
            while (t <= duration) { add(t); t += bar }
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            firstBeat = 0.5,
            key = "D minor",
            keyConfidence = 0.85,
            contentEndTime = contentEndTime,
            energyCurve = if (quality == TrackAnalysis.QUALITY_FULL) {
                listOf(EnergySample(0.0, 0.7), EnergySample(duration - 5.0, 0.3))
            } else {
                emptyList()
            },
            quality = quality,
        )
    }

    private fun phraseChoice(template: MixTemplate, releaseSec: Double): PhraseChoice {
        val exit = ExitCue(
            kind = ExitKind.OUTRO_PHRASE,
            releaseBar = (releaseSec / 2.0).toInt(),
            releaseSec = releaseSec,
            roomBars = 8,
            vocalRoomBars = 0,
            tailBars = 0,
            sectionFn = SectionFn.OUTRO,
            role = SectionRole.GROOVE,
            energyAtRelease = 0.4f,
            energyLevel = 4,
            loopableRoom = true,
            cutsSungBars = 0,
            cutsBars = 0,
        )
        val entry = EntryCue(
            kind = EntryKind.HEAD,
            startBar = 0,
            startSec = 0.0,
            landingBar = 8,
            landingFn = SectionFn.INTRO,
            landingRole = SectionRole.GROOVE,
            leadInBars = 8,
            leadInVocal = 0f,
            leadInLoopable = true,
            energyAtLanding = 0.4f,
            energyLevel = 4,
            trimmedBars = 0,
            pickupBeats = 0f,
        )
        return PhraseChoice(
            template = template,
            rung = PhrasePlanner.rungOf(template),
            score = 0.9,
            exit = exit,
            entry = entry,
            overlapBars = 8,
            swapBar = -4.0,
            tailBeats = 1.0,
            loopK = 0,
            echoGated = false,
            vocalProduct = 0f,
            tempoPrepBars = 0,
            echoDelayBeats = 0.75,
            echoDelayName = "ECHO_DELAY_BEATS",
            swapRampMs = 120,
            swapRampName = "SWAP_RAMP_BLEND_MS",
            tempoPolicy = "TEMPO_POLICY=b",
            vocalOverlapBeats = 0.0,
            energyDelta = 0f,
            struct = 0.9,
            vocalScore = 0.9,
            phraseScore = 0.9,
            energyScore = 0.9,
            lengthScore = 0.9,
            keyScore = 0.9,
            tempoScore = 0.9,
            cost = 0.0,
        )
    }

    @Test
    fun video2CommittedCountdownNeverDropsToZeroOrOscillates() {
        val stabilizer = CountdownStabilizer()
        val pair = "kgb->technologic"
        val epoch = 1L

        // Committed at 12s (target = 170_000ms, pos = 158_000ms) and shown.
        val committed = stabilizer.consider(
            CountdownStabilizer.Candidate(pair, epoch, CountdownStabilizer.Family.LOOP, "Beat blend", 170_000L, 190_000L, isFullQuality = false),
            currentPosMs = 158_000L,
        )
        assertNotNull(committed)
        assertEquals(170_000L, committed.targetStartMs)
        stabilizer.markShown(pair, epoch)

        // Never flashes 0s while targetStartMs is still in the future.
        assertEquals(12, CountdownStabilizer.displaySeconds(170_000L, 158_000L))
        assertEquals(1, CountdownStabilizer.displaySeconds(170_000L, 169_999L))
        assertEquals(0, CountdownStabilizer.displaySeconds(170_000L, 170_000L))

        // Competing Blend (CLASSIC) at +34s must NOT replace the committed Beat blend.
        val afterClassic = stabilizer.consider(
            CountdownStabilizer.Candidate(pair, epoch, CountdownStabilizer.Family.CLASSIC, "blend", 192_000L, 200_000L, isFullQuality = true),
            currentPosMs = 158_500L,
        )
        assertNotNull(afterClassic)
        assertEquals(CountdownStabilizer.Family.LOOP, afterClassic.family)
        assertEquals(170_000L, afterClassic.targetStartMs)

        // Violent > 1500 ms jump (+37s) on FULL arrival must be rejected.
        val afterJump = stabilizer.consider(
            CountdownStabilizer.Candidate(pair, epoch, CountdownStabilizer.Family.LOOP, "Beat blend", 203_000L, 215_000L, isFullQuality = true),
            currentPosMs = 166_000L,
        )
        assertNotNull(afterJump)
        assertEquals(170_000L, afterJump.targetStartMs)

        // Transient drop to currentPosMs (which caused 0s flash) must be rejected while 170_000L is future.
        val afterZeroDrop = stabilizer.consider(
            CountdownStabilizer.Candidate(pair, epoch, CountdownStabilizer.Family.LOOP, "Beat blend", 166_000L, 190_000L, isFullQuality = true),
            currentPosMs = 166_200L,
        )
        assertNotNull(afterZeroDrop)
        assertEquals(170_000L, afterZeroDrop.targetStartMs)
    }

    @Test
    fun oneWayFallbackSuppressesFailedLoopAndPreventsReOscillation() {
        val stabilizer = CountdownStabilizer()
        val pair = "tech->guess"
        val epoch = 3L

        stabilizer.consider(
            CountdownStabilizer.Candidate(pair, epoch, CountdownStabilizer.Family.LOOP, "Beat blend", 168_000L, 180_000L),
            currentPosMs = 156_000L,
        )
        stabilizer.markShown(pair, epoch)

        // Loop fails -> one-way move to CLASSIC, suppressing LOOP & NATIVE_BRIDGE.
        stabilizer.onRungFailed(pair, epoch, CountdownStabilizer.Family.LOOP, suppressBridgeToo = true)
        assertTrue(stabilizer.isFamilySuppressed(pair, epoch, CountdownStabilizer.Family.LOOP))
        assertTrue(stabilizer.isFamilySuppressed(pair, epoch, CountdownStabilizer.Family.NATIVE_BRIDGE))

        // Classic fallback is adopted.
        val fallback = stabilizer.consider(
            CountdownStabilizer.Candidate(pair, epoch, CountdownStabilizer.Family.CLASSIC, "blend", 169_200L, 177_200L),
            currentPosMs = 168_100L,
        )
        assertNotNull(fallback)
        assertEquals(CountdownStabilizer.Family.CLASSIC, fallback.family)
        assertEquals(169_200L, fallback.targetStartMs)

        // Attempting to re-propose LOOP or NATIVE_BRIDGE on the same (pair, epoch) is blocked.
        val retryLoop = stabilizer.consider(
            CountdownStabilizer.Candidate(pair, epoch, CountdownStabilizer.Family.LOOP, "Beat blend", 172_000L, 180_000L),
            currentPosMs = 168_300L,
        )
        assertNotNull(retryLoop)
        assertEquals(CountdownStabilizer.Family.CLASSIC, retryLoop.family)
        assertEquals(169_200L, retryLoop.targetStartMs)

        // A real user scrub (new scrubEpoch) clears suppression.
        val afterScrub = stabilizer.consider(
            CountdownStabilizer.Candidate(pair, epoch + 1L, CountdownStabilizer.Family.LOOP, "Beat blend", 168_000L, 180_000L),
            currentPosMs = 130_000L,
        )
        assertNotNull(afterScrub)
        assertEquals(CountdownStabilizer.Family.LOOP, afterScrub.family)
    }

    @Test
    fun headToFullArrivalDoesNotJumpCommittedClassicBar() {
        val stabilizer = CountdownStabilizer()
        val pair = "a->b"
        val epoch = 1L

        val headPlan = TransitionPlan(
            shouldStart = false,
            markerVisible = true,
            blocked = false,
            reason = "before-mix-window",
            transitionStart = 166.0,
            transitionEnd = 174.0,
            fadeSeconds = 8.0,
            transitionStyle = TransitionStyle.DJ_BLEND,
            incomingCueTime = 2.0,
        )
        val first = stabilizer.stabilizeClassicPlan(
            pairKey = pair,
            scrubEpoch = epoch,
            fresh = headPlan,
            previous = null,
            isFullQuality = false,
            currentPosMs = 154_000L,
            durationSec = 180.0,
        )
        assertEquals(166.0, first.transitionStart, 0.01)
        stabilizer.markShown(pair, epoch)

        // FULL analysis arrives proposing a bar 22s earlier (144s) or 9s later (175s):
        // committed bar 166.0s must remain pinned, while updated metadata (cue/effect) carries over.
        val fullJumped = headPlan.copy(
            transitionStart = 175.0,
            transitionEnd = 179.0,
            fadeSeconds = 4.0,
            incomingCueTime = 8.5,
            djEffect = DjEffect.ECHO,
        )
        val stabilized = stabilizer.stabilizeClassicPlan(
            pairKey = pair,
            scrubEpoch = epoch,
            fresh = fullJumped,
            previous = first,
            isFullQuality = true,
            currentPosMs = 158_000L,
            durationSec = 180.0,
        )
        assertEquals(166.0, stabilized.transitionStart, 0.01)
        assertEquals(8.5, stabilized.incomingCueTime, 0.01)
        assertEquals(DjEffect.ECHO, stabilized.djEffect)
        assertFalse(stabilized.shouldStart)
    }

    @Test
    fun phrasePlannerLockRejectsJumpOver1500MsWhenShownOrLocked() {
        val lock = PhrasePlanner.Lock()
        val initial = phraseChoice(MixTemplate.M1, releaseSec = 170.0)
        val chosen = lock.consider(nowSec = 10.0, secondsToRelease = 12.0, fresh = initial)
        assertNotNull(chosen)
        assertTrue(lock.locked)
        lock.noteShown()

        // Same template M1 arrives with a +33s jump (HEAD -> FULL) -> MUST be rejected.
        val jumpedLater = phraseChoice(MixTemplate.M1, releaseSec = 203.0)
        val afterLater = lock.consider(nowSec = 12.0, secondsToRelease = 43.0, fresh = jumpedLater)
        assertEquals(170.0, afterLater!!.exit.releaseSec, 0.001)

        // Same template M1 arrives with a -12s jump -> MUST be rejected.
        val jumpedEarlier = phraseChoice(MixTemplate.M1, releaseSec = 158.0)
        val afterEarlier = lock.consider(nowSec = 13.0, secondsToRelease = 0.0, fresh = jumpedEarlier)
        assertEquals(170.0, afterEarlier!!.exit.releaseSec, 0.001)

        // Small <= 1.5s refinement ahead is accepted.
        val minorSnap = phraseChoice(MixTemplate.M1, releaseSec = 171.0)
        val afterMinor = lock.consider(nowSec = 14.0, secondsToRelease = 9.0, fresh = minorSnap)
        assertEquals(171.0, afterMinor!!.exit.releaseSec, 0.001)
    }

    @Test
    fun spuriousSeekIgnoredWhileRealScrubResets() {
        // 0 -> 0 and same-pos seeks always ignored
        assertTrue(SeekAction.shouldIgnoreSeek(SeekAction.Phase.IDLE, 0L, 0L))
        assertTrue(SeekAction.shouldIgnoreSeek(SeekAction.Phase.IDLE, 95_000L, 95_000L))
        assertTrue(SeekAction.shouldIgnoreSeek(SeekAction.Phase.LOOP_ARMING, 95_000L, 95_000L))
        // Tiny <= 1200ms same-item seek while IDLE ignored
        assertTrue(SeekAction.shouldIgnoreSeek(SeekAction.Phase.IDLE, 95_000L, 96_000L, sameMediaItem = true))
        assertTrue(SeekAction.shouldIgnoreSeek(SeekAction.Phase.IDLE, 95_000L, 94_100L, sameMediaItem = true))
        // Real user seek (> 1200ms) or across items NOT ignored
        assertFalse(SeekAction.shouldIgnoreSeek(SeekAction.Phase.IDLE, 95_000L, 120_000L, sameMediaItem = true))
        assertFalse(SeekAction.shouldIgnoreSeek(SeekAction.Phase.IDLE, 95_000L, 95_500L, sameMediaItem = false))
        assertFalse(SeekAction.shouldIgnoreSeek(SeekAction.Phase.LOOP_RUNNING, 160_000L, 100_000L, sameMediaItem = true))
    }

    @Test
    fun loopRunningEmergencyTailDefersDisarmForNativeFade() {
        assertTrue(SeekAction.shouldDeferLoopDisarm(deferRequested = true, isLoopRunning = true))
        assertFalse(SeekAction.shouldDeferLoopDisarm(deferRequested = false, isLoopRunning = true))
        assertFalse(SeekAction.shouldDeferLoopDisarm(deferRequested = true, isLoopRunning = false))
    }

    @Test
    fun midwayStartCatchUpPinsSingleStableCountdown() {
        val out = analysis("kgb", 120.0, duration = 180.0)
        val inn = analysis("tech", 127.0, duration = 200.0)
        // Midway scrub to 168s (12s remaining) when original anchor was 156s:
        val stalePlan = TransitionPlan(
            shouldStart = true,
            markerVisible = true,
            blocked = false,
            reason = "smart-duration",
            transitionStart = 156.0,
            transitionEnd = 174.0,
            fadeSeconds = 18.0,
            transitionStyle = TransitionStyle.DJ_BLEND,
            beatSeconds = 60.0 / 120.0,
        )
        val reanchored = CatchUpPlanner.reanchor(stalePlan, out, inn, 168.0, 180.0)
        assertNotNull(reanchored)
        val pair = "kgb->tech"
        val pinKey = CatchUpPins.key(pair, 2L)
        val stabilizer = CountdownStabilizer()
        val stabilized = stabilizer.stabilizeClassicPlan(
            pair, 2L, reanchored, null, isFullQuality = true, currentPosMs = 168_000L, durationSec = 180.0,
        )
        stabilizer.markShown(pair, 2L)
        val pinnedStartMs = (stabilized.transitionStart * 1000.0).toLong()
        assertTrue(pinnedStartMs > 168_000L)

        // Subsequent ticks advance pos; CatchUpPins + CountdownStabilizer keep countdown strictly monotonic.
        var prevSec = CountdownStabilizer.displaySeconds(pinnedStartMs, 168_000L)
        for (posMs in listOf(168_250L, 168_500L, 168_750L)) {
            val reused = CatchUpPins.reuse(stabilized, pinKey, pinKey, posMs)!!
            val next = stabilizer.stabilizeClassicPlan(
                pair, 2L, reused, stabilized, isFullQuality = true, currentPosMs = posMs, durationSec = 180.0,
            )
            assertTrue(abs(next.transitionStart - stabilized.transitionStart) < 1e-6)
            val sec = CountdownStabilizer.displaySeconds((next.transitionStart * 1000.0).toLong(), posMs)
            assertTrue(sec <= prevSec, "countdown must not jump up: $sec vs $prevSec")
            prevSec = sec
        }
    }

    // ---- Task 2B tests ----

    @Test
    fun blendPlanSnapsOutStartToDownbeatAndCueOfToMusicalDownbeat() {
        val beatOut = 60.0 / 120.0
        val barOut = 4.0 * beatOut
        val outDowns = buildList {
            var t = 0.5
            while (t <= 200.0) { add(t); t += barOut }
        }
        val out = analysis("out-120", 120.0, duration = 200.0).copy(
            downbeats = outDowns,
            contentEndTime = 198.0,
            tailBeats = BeatSide(
                beats = buildList { var t = 0.5; while (t <= 200.0) { add(t); t += beatOut } },
                downbeats = outDowns,
                bpm = 120.0,
                confidence = 0.9,
            ),
        )
        // Incoming has dead silence / beatless pad 0.12..12.5s before first downbeat at 12.5s.
        val beatIn = 60.0 / 122.0
        val barIn = 4.0 * beatIn
        val inDowns = buildList {
            var t = 12.5
            while (t <= 180.0) { add(t); t += barIn }
        }
        val inn = analysis("in-122", 122.0, duration = 180.0).copy(
            audibleStartTime = 0.12,
            firstBeat = 12.5,
            downbeats = inDowns,
            headBeats = BeatSide(
                beats = buildList { var t = 12.5; while (t <= 60.0) { add(t); t += beatIn } },
                downbeats = inDowns.filter { it <= 60.0 },
                bpm = 122.0,
                confidence = 0.9,
            ),
        )
        // Bridge Beat blend must snap outStart to an exact downbeat in out.downbeats,
        // and align inn.loopStart to inn's first musical downbeat (12.5s).
        // Fixture idiom: sung=null (unknown, no vocalWindows) keeps the crown
        // Drop ineligible (firstClean needs mask proof without lyric proof),
        // so the blend path under test is reached. EmptyList would claim
        // verified-instrumental via lyrics and legitimately route to Drop
        // (Drop outranks Blend), hiding the snap behavior.
        val plan = LoopMix.bridge(out, out.duration, inn, inn.duration, earliestOutSec = 100.0, sungOut = null, sungIn = null)
        assertNotNull(plan)
        assertEquals("Beat blend", plan!!.rung)
        assertTrue(
            outDowns.any { abs(it - plan.out.loopStart) < 1e-6 },
            "outStart ${plan.out.loopStart} must snap to an analyzed downbeat",
        )
        assertEquals(12.5, plan.inn.loopStart, 1e-6)

        // When lyrics start BEFORE firstDownbeat, cueOf must NOT skip past lyrics.
        val earlyVocal = listOf(LoopMix.Span(start = 0.30, end = 2.0, certainUntil = 2.0))
        val safeCue = LoopMix.cueOf(inn, sungIn = earlyVocal)
        assertTrue(safeCue <= 0.30 + 0.08, "cueOf $safeCue must not cut early lyrics at 0.30s")
    }

    @Test
    fun transitionMovesHoldMatchesRampedClockBeatTimes() {
        // 100 -> 108 BPM (+8%): 16 prep bars (64 beats). Constant ramp * bar = 38.4s,
        // while the true ramped beat-time integral Clock.beatTimes[rampTo] is ~36.96s.
        val choice = phraseChoice(MixTemplate.M1, releaseSec = 170.0).copy(tempoPrepBars = 16)
        val script = TransitionMoves.compile(choice, outBpm = 100.0, inBpm = 108.0)
        val clock = LoopMix.Clock(script.plan)
        assertEquals(clock.beatTimes[clock.rampTo], script.incomingHoldSec, 1e-9)
        assertEquals(clock.beatTimes[clock.rampTo], script.plan.incomingHoldSec, 1e-9)
        assertTrue(
            abs(script.incomingHoldSec - 16 * (4.0 * 60.0 / 100.0)) > 1.0,
            "ramped hold ${script.incomingHoldSec} must diverge from constant 38.4s",
        )
        // Song B enters right on beat rampTo (exact downbeat).
        assertEquals(script.plan.inn.loopStart, clock.inTrackTimeAt(clock.rampEnd), 1e-9)
        assertTrue(clock.inTrackTimeAt(clock.rampEnd + 1.0) > script.plan.inn.loopStart)
    }

    @Test
    fun echoPlanProducesDownbeatCutWithPreChargedHoldAndNativeIncoming() {
        val out = analysis("out-100", 100.0, duration = 200.0).copy(key = "C major", keyConfidence = 0.9)
        val inn = analysis("in-130", 130.0, duration = 200.0).copy(key = "Eb major", keyConfidence = 0.9, audibleStartTime = 0.1)
        assertTrue(shouldDeclineToMix(out, inn))
        val plan = LoopMix.bridge(out, out.duration, inn, inn.duration, earliestOutSec = 150.0)
        assertNotNull(plan)
        assertEquals("Echo", plan!!.rung)
        assertTrue(plan.echoTail)
        assertTrue(plan.reverbWash)
        assertFalse(plan.buildupRoll)
        assertTrue(plan.nativeIncoming)
        assertEquals(plan.out.beatSec * 4.0, plan.incomingHoldSec, 1e-6)
        assertTrue(out.downbeats.any { abs(it - plan.out.loopStart) < 1e-6 })
        assertEquals(inn.downbeats.first(), plan.inn.loopStart, 1e-6)
    }

    @Test
    fun declinedClassicFallbackUsesShortSculptedDjFilterNever15sEqualPower() {
        val out = analysis("out-100", 100.0, duration = 200.0).copy(
            key = "C major", keyConfidence = 0.9, vocalProbability = 0.85,
        )
        val inn = analysis("in-132", 132.0, duration = 200.0).copy(
            key = "Eb major", keyConfidence = 0.9, vocalProbability = 0.85,
        )
        assertTrue(shouldDeclineToMix(out, inn))
        val plan = planTransition(
            analysis = out,
            nextAnalysis = inn,
            currentTrack = TransitionTrackInfo("out-100", 200_000L),
            nextTrack = TransitionTrackInfo("in-132", 200_000L),
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertEquals(TransitionStyle.DJ_FILTER, plan.transitionStyle)
        assertTrue(plan.fadeSeconds in 3.5..4.5, "sculpted fallback must be 3.5..4.5s, got ${plan.fadeSeconds}")
        assertTrue(plan.filterSweep > 0.0)
        // REWRITTEN (vc71 gate fix): the decline used to render through the
        // SWEEP ride (filterVariant SWEEP, bassSwap false), whose 1500 Hz
        // floor left both decks wide-band at the midpoint. The decline is
        // now the DECLINE carve: a dedicated variant with a performed
        // one-kick handoff (bassSwap true), bounded length still the
        // contract. See TransitionAlternatives.declineOutLpAt.
        assertEquals(FilterTransitionVariant.DECLINE, plan.filterVariant)
        assertTrue(plan.bassSwap, "the carve hands the low end over once, inside the ride")
        assertTrue(plan.vocalOverlap >= 0.8)
    }

    @Test
    fun keyClashBeatmatchedPairRoutesToFiltered4To8BarBassSwapBlend() {
        val out = analysis("out-124", 124.0, duration = 200.0).copy(
            key = "C major", keyConfidence = 0.9, vocalProbability = 0.2,
        )
        val inn = analysis("in-126", 126.0, duration = 200.0).copy(
            key = "F# major", keyConfidence = 0.9, vocalProbability = 0.2,
        )
        assertFalse(shouldDeclineToMix(out, inn))
        val steps = regionalCamelotSteps(out, inn)
        assertNotNull(steps)
        assertTrue(steps!! > 2)

        // 1. LoopMix.bridge Beat blend
        val clear = emptyList<LoopMix.Span>()
        val bridgePlan = LoopMix.bridge(out, out.duration, inn, inn.duration, earliestOutSec = 100.0, sungOut = clear, sungIn = clear)
        assertNotNull(bridgePlan)
        assertEquals("Beat blend", bridgePlan!!.rung)
        assertTrue(bridgePlan.totalBars in 4..8, "key-clash blend must be 4-8 bars, got ${bridgePlan.totalBars}")
        assertTrue(bridgePlan.carve)
        assertTrue(bridgePlan.clash)
        assertFalse(bridgePlan.flanger)
        assertFalse(bridgePlan.buildupRoll)
        assertEquals(1.0, bridgePlan.faderSwapBeats, 1e-6)

        // 2. PhrasePlanner + TransitionMoves
        val outMap = SongMaps.fromAnalysis("out-124", out)
        val inMap = SongMaps.fromAnalysis("in-126", inn)
        val choice = PhrasePlanner.select(outMap, inMap, steps, 124.0, 126.0)
        assertNotNull(choice)
        assertTrue(choice!!.overlapBars in 4..8, "key-clash phrase overlap must be 4-8 bars, got ${choice.overlapBars}")
        val script = TransitionMoves.compile(choice, 124.0, 126.0, outgoingId = "out-124", incomingId = "in-126")
        assertTrue(script.plan.carve)
        assertTrue(script.plan.clash)
        assertEquals(1.0, script.plan.faderSwapBeats, 1e-6)
        assertFalse(script.plan.flanger)
    }

    @Test
    fun nativeBridgeYieldsOnlyUnder8500msWhilePreservingCrownContract() {
        val loopStartMs = 180_000L
        // One strict floor, shared with prime eligibility and LOOP_ARMING.
        assertFalse(LoopMix.shouldYieldColdLoop(loopStartMs, 171_500L, fastReady = false, building = false, blendArmLeadMs = 25_000L, nextStreamReady = false, isNativeBridge = true))
        assertFalse(LoopMix.shouldYieldColdLoop(loopStartMs, 165_000L, fastReady = false, building = true, blendArmLeadMs = 25_000L, nextStreamReady = true, isNativeBridge = true))
        assertTrue(LoopMix.shouldYieldColdLoop(loopStartMs, 171_501L, fastReady = false, building = true, blendArmLeadMs = 25_000L, nextStreamReady = true, isNativeBridge = true))
        assertTrue(LoopMix.shouldYieldColdLoop(loopStartMs, 171_600L, fastReady = false, building = false, blendArmLeadMs = 25_000L, nextStreamReady = false, isNativeBridge = true))
        assertFalse(LoopMix.shouldYieldColdLoop(loopStartMs, 179_999L, fastReady = true, building = true, blendArmLeadMs = 25_000L, nextStreamReady = false, isNativeBridge = true))
    }

    // ---- Task 2B (main) beat-aligned helpers: adapters use bk analysis() ----

    private fun analysis2B(
        bpm: Double,
        duration: Double = 200.0,
        trackId: String = "t",
        key: String = "",
        keyConfidence: Double = 0.0,
        vocalProbability: Double = 0.3,
        audibleStart: Double? = null,
        beatConfidence: Double = 0.85,
    ): TrackAnalysis = analysis(trackId, bpm, duration).copy(
        beatConfidence = beatConfidence,
        key = if (key.isNotEmpty()) key else "D minor",
        keyConfidence = if (key.isNotEmpty()) keyConfidence else 0.85,
        vocalProbability = vocalProbability,
        audibleStartTime = audibleStart,
    )

    private fun exitCue(sec: Double, room: Int = 8) = ExitCue(
        kind = ExitKind.OUTRO_PHRASE,
        releaseBar = (sec / 2.0).toInt(),
        releaseSec = sec,
        roomBars = room,
        vocalRoomBars = 0,
        tailBars = 0,
        sectionFn = SectionFn.OUTRO,
        role = SectionRole.GROOVE,
        energyAtRelease = 0.4f,
        energyLevel = 4,
        loopableRoom = false,
        cutsSungBars = 0,
        cutsBars = 0,
    )

    private fun entryCue(startSec: Double = 0.5, landingBar: Int = 8) = EntryCue(
        kind = EntryKind.HEAD,
        startBar = 0,
        startSec = startSec,
        landingBar = landingBar,
        landingFn = SectionFn.INTRO,
        landingRole = SectionRole.GROOVE,
        leadInBars = landingBar,
        leadInVocal = 0f,
        leadInLoopable = true,
        energyAtLanding = 0.4f,
        energyLevel = 4,
        trimmedBars = 0,
        pickupBeats = 0f,
    )

    private fun choice2B(
        template: MixTemplate = MixTemplate.M1,
        overlapBars: Int = 8,
        prepBars: Int = 0,
        leadInVocal: Float = 0f,
    ) = PhraseChoice(
        template = template,
        rung = PhrasePlanner.rungOf(template),
        score = 0.85,
        exit = exitCue(sec = 170.0, room = overlapBars),
        entry = entryCue().copy(leadInVocal = leadInVocal),
        overlapBars = overlapBars,
        swapBar = -overlapBars / 2.0,
        tailBeats = 1.0,
        loopK = 0,
        echoGated = false,
        vocalProduct = 0f,
        tempoPrepBars = prepBars,
        echoDelayBeats = 0.75,
        echoDelayName = "ECHO_DELAY_BEATS",
        swapRampMs = 120,
        swapRampName = "SWAP_RAMP_BLEND_MS",
        tempoPolicy = "TEMPO_POLICY=b",
        vocalOverlapBeats = 0.0,
        energyDelta = 0f,
        struct = 0.5,
        vocalScore = 0.0,
        phraseScore = 0.5,
        energyScore = 0.5,
        lengthScore = 0.0,
        keyScore = 0.0,
        tempoScore = 0.0,
        cost = 0.0,
    )

    @Test
    fun blendExitAnchorsToOutgoingDownbeat() {
        val out = analysis2B(120.0, trackId = "o", vocalProbability = 0.2)
        val inn = analysis2B(120.0, trackId = "i", vocalProbability = 0.2)
        val plan = LoopMix.bridge(out, 200.0, inn, 200.0, 0.0)
        assertNotNull(plan, "120/120 instrumental pair must beat-blend")
        assertEquals("Beat blend", plan.rung, "rung=${plan.rung}")
        // bk contentEnd-aware blend: effectiveEnd=196 (contentEnd), need=16s
        // => raw 179.7. Snap is BACK-only to last downbeat <= raw (178.5),
        // never forward. (Main duration-based raw was 183.7 -> 182.5.)
        assertEquals(178.5, plan.out.loopStart, 1e-6, "start=${plan.out.loopStart}")
        assertTrue(out.downbeats.any { abs(it - plan.out.loopStart) < 1e-6 }, "must sit on own downbeat")
    }

    @Test
    fun blendSnapRefusesSungSpanAndKeepsRaw() {
        val sungWindows = listOf(CurveWindow(0.0, 10.0, FloatArray(2000) { 0.9f }))
        val out = analysis2B(120.0, trackId = "o", vocalProbability = 0.2).copy(vocalWindows = sungWindows)
        val inn = analysis2B(120.0, trackId = "i", vocalProbability = 0.2).copy(vocalWindows = sungWindows)
        // Cover bk snapped 178.5 (raw 179.7); refusal keeps raw, never scavenges.
        val sung = listOf(LoopMix.Span(start = 177.0, end = 180.0, certainUntil = 179.0))
        val plan = LoopMix.bridge(out, 200.0, inn, 200.0, 0.0, sungOut = sung)
        assertNotNull(plan)
        assertEquals("Beat blend", plan.rung, "rung=${plan.rung}")
        assertNotNull(plan)
        assertEquals(179.7, plan.out.loopStart, 1e-6, "start=${plan.out.loopStart}")
    }

    @Test
    fun crownLoopAloneDownbeatAnchored() {
        val bpm = 120.0
        val beat = 60 / bpm
        val out = analysis2B(bpm, duration = 200.0, trackId = "o").copy(
            tailBeats = BeatSide(
                beats = List(320) { 0.5 + it * beat },
                downbeats = List(80) { 0.5 + it * 4 * beat },
                bpm = bpm, confidence = 0.9,
            ),
            vocalWindows = listOf(CurveWindow(0.0, 10.0, FloatArray(2000) { 0.02f })),
            levelWindows = listOf(CurveWindow(0.0, 10.0, FloatArray(2000) { levelFromRms(0.2).toFloat() })),
        )
        val inn = analysis2B(bpm, duration = 200.0, trackId = "i").copy(
            headBeats = BeatSide(
                beats = List(320) { 0.5 + it * beat },
                downbeats = List(80) { 0.5 + it * 4 * beat },
                bpm = bpm, confidence = 0.9,
            ),
            vocalWindows = listOf(CurveWindow(0.0, 10.0, FloatArray(2000) { 0.02f })),
            levelWindows = listOf(CurveWindow(0.0, 10.0, FloatArray(2000) { levelFromRms(0.2).toFloat() })),
        )
        val plan = LoopMix.plan(out, 200.0, inn, 200.0, earliestOutSec = 20.0)
        assertNotNull(plan, "instrumental constant-grid pair must loop")
        assertTrue(plan.outLoops && plan.inLoops, "crown loops must stay loops")
        assertEquals(plan.out.bars, 4)
        assertEquals(plan.inn.bars, 4)
    }

    @Test
    fun holdEqualsIntegratedRampEnd() {
        val script = TransitionMoves.compile(choice2B(prepBars = 6), outBpm = 120.0, inBpm = 126.0)
        assertEquals(10, script.plan.rampBars)
        val clock = LoopMix.Clock(script.plan)
        assertEquals(clock.rampEnd, script.incomingHoldSec, 1e-6, "hold=${script.incomingHoldSec} clock=${clock.rampEnd}")
        assertTrue(script.incomingHoldSec < 20.0, "hold=${script.incomingHoldSec} must beat 20 s")
        assertEquals(script.plan.incomingHoldSec, script.incomingHoldSec, 1e-6)
        assertEquals(LoopMix.rampHoldSec(120.0, 120.0, 2), 4.0, 1e-6)
    }

    @Test
    fun trackedAnchorsValidateAndFallback() {
        val beat = 0.5
        val side = BeatSide(beats = List(40) { 10.0 + it * beat }, downbeats = emptyList(), bpm = 120.0, confidence = 0.9)
        val anchors = LoopMix.trackedAnchors(side, 10.0, 18.0, 4)
        assertNotNull(anchors)
        assertEquals(17, anchors.size)
        assertEquals(10.0, anchors.first(), 1e-9)
        assertEquals(18.0, anchors.last(), 1e-9)
        assertNull(LoopMix.trackedAnchors(BeatSide(beats = listOf(10.0, 12.0), downbeats = emptyList(), bpm = 120.0, confidence = 0.9), 10.0, 18.0, 4))
        val wandered = BeatSide(beats = List(40) { 10.0 + it * beat + if (it == 5) 0.4 else 0.0 }, downbeats = emptyList(), bpm = 120.0, confidence = 0.9)
        assertNull(LoopMix.trackedAnchors(wandered, 10.0, 18.0, 4), "a 0.4s jump must invalidate the grid")
    }

    @Test
    fun knotPositionsUseAnchorsWhenValid() {
        val beat = 0.5
        val anchors = List(17) { 10.0 + it * beat }
        val pick = LoopMix.Pick(10.0, 18.0, 4, beat, 120.0, 0.0, LoopMix.Check.MASK, anchorBeats = anchors)
        val plan = LoopMix.Plan(
            out = pick, inn = pick, fold = 1.0, enterBars = 4, rampBars = 2, exitBars = 2,
            totalBars = 8, outLoops = true, inLoops = true, rung = "Loop", faderSwapBeats = 1.0,
        )
        val clock = LoopMix.Clock(plan)
        val pos = LoopMix.knotTrackPositions(plan, clock, LoopMix.Side.OUT)
        assertEquals(10.0, pos[0], 1e-9)
        assertEquals(10.5, pos[1], 1e-9, "anchored beats, not synthetic drift")
        assertEquals(18.0, pos[16], 1e-9)
        val synth = LoopMix.knotTrackPositions(plan.copy(out = LoopMix.Pick(10.0, 18.0, 4, beat, 120.0, 0.0, LoopMix.Check.MASK)), clock, LoopMix.Side.OUT)
        for (i in 0 until synth.lastIndex) assertTrue(synth[i] <= synth[i + 1], "synth must be monotonic")
    }

    @Test
    fun scoopGatesOnRealVocalEvidenceAndMapsThroughClock() {
        val pick = LoopMix.Pick(10.0, 18.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK)
        val plan = LoopMix.Plan(
            out = pick, inn = pick, fold = 1.0, enterBars = 2, rampBars = 2, exitBars = 2,
            totalBars = 6, outLoops = true, inLoops = true, rung = "Loop", faderSwapBeats = 1.0,
        )
        val clock = LoopMix.Clock(plan)
        assertNull(LoopMix.scoopSpec(clock, plan, leadInVocal = 0f, vocalOverlapBeats = 0.0))
        val scoop = LoopMix.scoopSpec(clock, plan, leadInVocal = 1f, vocalOverlapBeats = 0.0)
        assertNotNull(scoop)
        val t0 = scoop.outMidTimes.last()
        assertEquals(clock.beatTimes[16], t0, 1e-6, "t0=$t0 beatTimes=${clock.beatTimes[16]}")
        assertTrue(scoop.outMidTimes.all { it >= 0.0 }, "no negative seconds")
        assertTrue(scoop.outMidTimes[0] < scoop.outMidTimes[1] && scoop.outMidTimes[1] < scoop.outMidTimes[2])
        val midBeat = clock.beatTimes[12]
        val mapped = scoop.outMidTimes[1]
        assertEquals(midBeat, mapped, 1e-6)
    }

    @Test
    fun echoSpecIsTrueDjOut() {
        val pick = LoopMix.Pick(150.0, 150.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK)
        val plan = LoopMix.Plan(
            out = pick, inn = pick, fold = 1.0, enterBars = 1, rampBars = 0, exitBars = 1,
            totalBars = 2, outLoops = false, inLoops = false, nativeIncoming = true,
            echoTail = true, rung = "Echo", targetBpmOverride = 120.0, faderSwapBeats = 1.0,
        )
        val clock = LoopMix.Clock(plan)
        assertEquals(clock.rampFrom, clock.rampTo, "degenerate ramp")
        val spec = LoopMix.echoSpec(clock, plan, outStopAtSec = clock.releaseAt + 3.0)
        val win = spec.cutEnd - spec.cutStart
        assertTrue(win in 0.08..0.12, "win=$win")
        assertEquals(clock.rampEnd, spec.cutAt, 1e-9)
        val riseDur = spec.inLpTimes[1] - spec.inLpTimes[0]
        assertTrue(riseDur <= 0.5 + 1e-9, "rise=$riseDur")
        assertEquals(900f, spec.inLpValues[0], 1e-3f)
        assertEquals(0f, spec.echoSendValues.first(), 1e-6f)
        assertEquals(0f, spec.echoSendValues.last(), 1e-6f)
        assertTrue(spec.echoSendValues.max() > 0.4f)
        assertEquals(spec.cutEnd, spec.echoSendTimes.last(), 1e-9)
        assertEquals(0f, spec.outGainValues.last(), 1e-6f)
        assertTrue(spec.echoFeedback in 0.50f..0.58f, "fb=${spec.echoFeedback}")
        assertEquals(0f, spec.inGainValues[1], 1e-6f)
        assertTrue(spec.inGainTimes[1] == spec.cutAt)
    }

    @Test
    fun declineIsBoundedFourSecondFilter() {
        val out = analysis2B(120.0, duration = 200.0, trackId = "a", key = "C major", keyConfidence = 0.9, vocalProbability = 0.9)
        val inn = analysis2B(94.0, duration = 200.0, trackId = "b", key = "F# major", keyConfidence = 0.9, vocalProbability = 0.9)
        assertTrue(shouldDeclineToMix(out, inn), "far tempo + clash + dual vocal must decline")
        val p = planTransition(
            analysis = out, nextAnalysis = inn,
            currentTrack = TransitionTrackInfo(out.trackId, 200_000L),
            nextTrack = TransitionTrackInfo(inn.trackId, 200_000L),
            currentTime = 10.0, duration = 200.0, mode = CrossfadeMode.SMART,
        )
        assertEquals(TransitionStyle.DJ_FILTER, p.transitionStyle, "reason=${p.reason}")
        assertTrue(p.fadeSeconds in 3.5..4.5, "fade=${p.fadeSeconds}")
        assertEquals(4.0, p.fadeSeconds, 1e-6)
        assertTrue(p.reason.contains("decline-to-mix"))
        assertTrue(p.filterSweep >= 1.0 - 1e-9, "filter sweep must engage")
    }

    @Test
    fun keyClashGetsFilteredBlendWithOneBeatSwap() {
        val out = analysis2B(120.0, trackId = "o", key = "C major", keyConfidence = 0.9, vocalProbability = 0.3)
        val inn = analysis2B(120.0, trackId = "i", key = "Eb major", keyConfidence = 0.9, vocalProbability = 0.3)
        assertFalse(shouldDeclineToMix(out, inn), "matching tempo must not decline")
        val plan = LoopMix.bridge(out, 200.0, inn, 200.0, 0.0)
        assertNotNull(plan)
        assertEquals("Beat blend", plan.rung)
        assertTrue(plan.clash, "steps>1 must flag clash")
        assertTrue(plan.totalBars in 4..8, "bars=${plan.totalBars}")
        val clock = LoopMix.Clock(plan)
        assertEquals(0.5, clock.swapBar, 1e-6, "swap=${clock.swapBar}")
        val same = LoopMix.bridge(
            analysis2B(120.0, trackId = "o2", key = "C major", keyConfidence = 0.9),
            200.0,
            analysis2B(120.0, trackId = "i2", key = "C major", keyConfidence = 0.9),
            200.0, 0.0,
        )
        assertNotNull(same)
        assertFalse(same.clash)
    }

    @Test
    fun confidenceGatesNotWeakened() {
        val out = analysis2B(120.0, trackId = "o", beatConfidence = 0.10)
        val inn = analysis2B(120.0, trackId = "i", beatConfidence = 0.10)
        val plan = LoopMix.bridge(out, 200.0, inn, 200.0, 0.0)
        assertNull(plan)
    }

    @Test
    fun readinessLeadsArePlausible() {
        assertEquals(45_000L, PrepLead.MAX_MS)
        assertEquals(45_000L, DeckBudget.LOOP_BUDGET_HORIZON_MS)
    }
}
