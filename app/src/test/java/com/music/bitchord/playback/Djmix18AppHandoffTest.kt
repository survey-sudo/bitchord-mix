package com.music.bitchord.playback

import com.music.bitchord.playback.smart.CuePoints
import com.music.bitchord.playback.smart.EntryCue
import com.music.bitchord.playback.smart.EntryKind
import com.music.bitchord.playback.smart.ExitCue
import com.music.bitchord.playback.smart.ExitKind
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.PhraseChoice
import com.music.bitchord.playback.smart.PhrasePlanner
import com.music.bitchord.playback.smart.SectionFn
import com.music.bitchord.playback.smart.SectionRole
import com.music.bitchord.playback.smart.StructureAnalyzer
import com.music.bitchord.playback.smart.TransitionMoves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * djmix18 `:app` handoff/analyzer/speed discipline.
 *
 * Pure JVM (no native renderer, no Android): the helpers under test are the
 * scalars the controller and LoopMixSession build on. LoopMixSession itself
 * needs the native renderer, so its knot/outTo logic is verified through the
 * shared [LoopMix.Clock] it derives from (same hold, same clock), which is
 * what guarantees `handoverTrackSec` / `inKnotPositions` / `setFadeOut` share
 * one timestamp.
 */
class Djmix18AppHandoffTest {

    // ---- 1. safeDeckSpeed hard [0.92, 1.08] / 1.0 clamp ----

    @Test
    fun safeDeckSpeedNeverAllows194or999() {
        assertEquals(1.08f, safeDeckSpeed(1.94f, beatmatched = true), 1e-6f)
        assertEquals(1.08f, safeDeckSpeed(999f, beatmatched = true), 1e-6f)
        assertEquals(1.0f, safeDeckSpeed(Float.POSITIVE_INFINITY, beatmatched = true), 1e-6f)
        assertEquals(1.0f, safeDeckSpeed(Float.NEGATIVE_INFINITY, beatmatched = true), 1e-6f)
        assertEquals(0.92f, safeDeckSpeed(0.5f, beatmatched = true), 1e-6f)
        assertEquals(0.92f, safeDeckSpeed(0.0f, beatmatched = true), 1e-6f)
        assertEquals(1.0f, safeDeckSpeed(1.0f, beatmatched = true), 1e-6f)
        assertEquals(1.05f, safeDeckSpeed(1.05f, beatmatched = true), 1e-6f)
        // Non-beatmatched and non-finite are always 1.0.
        assertEquals(1.0f, safeDeckSpeed(1.94f, beatmatched = false), 1e-6f)
        assertEquals(1.0f, safeDeckSpeed(Float.NaN, beatmatched = true), 1e-6f)
        assertEquals(1.0f, safeDeckSpeed(Float.NaN, beatmatched = false), 1e-6f)
    }

    @Test
    fun deckSpeedSafeStacksListenerSpeedOverClampedRate() {
        // Listener speed still works; only the mix component is clamped.
        assertEquals(1.08f, deckSpeedSafe(1.0f, 999.0, beatmatched = true), 1e-5f)
        assertEquals(1.0f, deckSpeedSafe(1.0f, 1.0, beatmatched = false), 1e-5f)
        assertEquals(1.0f, deckSpeedSafe(1.0f, Double.NaN, beatmatched = true), 1e-5f)
        // 1.25x user speed over a 1.94 mix rate: rate clamps to 1.08, stacked = 1.35.
        assertEquals(1.25f * 1.08f, deckSpeedSafe(1.25f, 1.94, beatmatched = true), 1e-4f)
    }

    // ---- 2. LoopMixSession knot / handover exactness with hold + exitTail ----

    private fun exitCue(bar: Int, sec: Double, room: Int = 8) = ExitCue(
        kind = ExitKind.OUTRO_PHRASE,
        releaseBar = bar,
        releaseSec = sec,
        roomBars = room,
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

    private fun entryCue(startBar: Int = 0, startSec: Double = 0.0, landingBar: Int = 8) = EntryCue(
        kind = EntryKind.HEAD,
        startBar = startBar,
        startSec = startSec,
        landingBar = landingBar,
        landingFn = SectionFn.INTRO,
        landingRole = SectionRole.GROOVE,
        leadInBars = landingBar - startBar,
        leadInVocal = 0f,
        leadInLoopable = true,
        energyAtLanding = 0.4f,
        energyLevel = 4,
        trimmedBars = startBar,
        pickupBeats = 0f,
    )

    private fun choiceFor(
        template: MixTemplate,
        overlapBars: Int = 8,
        prepBars: Int = 0,
        releaseSec: Double = 170.0,
    ) = PhraseChoice(
        template = template,
        rung = PhrasePlanner.rungOf(template),
        score = 0.85,
        exit = exitCue(bar = 72, sec = releaseSec, room = overlapBars),
        entry = entryCue(landingBar = overlapBars),
        overlapBars = overlapBars,
        swapBar = -overlapBars / 2.0,
        tailBeats = 1.0,
        loopK = 0,
        echoGated = template == MixTemplate.M6,
        vocalProduct = 0f,
        tempoPrepBars = prepBars,
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

    @Test
    fun clockWithIncomingHoldSecSharesOneHandoverTimestamp() {
        // 100 -> 102 BPM: Task 2B syncs incomingHoldSec to LoopMix.Clock.beatTimes[rampTo].
        val choice = choiceFor(MixTemplate.M1, overlapBars = 8, prepBars = 4)
        val script = TransitionMoves.compile(choice, outBpm = 100.0, inBpm = 102.0)
        val clock = LoopMix.Clock(script.plan, incomingHoldSec = script.incomingHoldSec)
        assertEquals(clock.beatTimes[clock.rampTo], script.incomingHoldSec, 1e-6)
        assertEquals(clock.beatTimes[clock.rampTo], script.plan.incomingHoldSec, 1e-6)

        val expected = script.plan.inn.loopStart + (clock.elementAt - script.incomingHoldSec)
        assertEquals(expected, clock.handoverTrackSec, 0.001)
        assertEquals(expected, clock.elementTrackTime, 0.001)
        assertEquals(clock.handoverTrackSec, clock.elementTrackTime, 1e-9)
        // During the hold IN stays at the cue; after it, 1:1 from the cue.
        assertEquals(script.plan.inn.loopStart, clock.inTrackTimeAt(0.0), 1e-9)
        assertEquals(script.plan.inn.loopStart, clock.inTrackTimeAt(clock.rampEnd), 1e-9)
        assertEquals(script.plan.inn.loopStart + 4.0, clock.inTrackTimeAt(clock.rampEnd + 4.0), 1e-9)
        // Handover must NOT be loopStart + elementAt (the multi-second intro jump).
        val naive = script.plan.inn.loopStart + clock.elementAt
        assertTrue("handover ${clock.handoverTrackSec} must differ from naive $naive by hold", (naive - clock.handoverTrackSec) > 9.0)
    }

    @Test
    fun straightOutDeckOutToCoversStopAtWithoutTruncation() {
        // Straight OUT deck (M1: outLoops == false) with exitTail dissolve.
        val choice = choiceFor(MixTemplate.M1, overlapBars = 8, prepBars = 0)
        val script = TransitionMoves.compile(choice, outBpm = 120.0, inBpm = 121.0)
        val plan = script.plan
        assertEquals(false, plan.outLoops)
        assertTrue("phrase plans carry exitTail", plan.exitTailSeconds > 0.0)
        val clock = LoopMix.Clock(plan)
        val outBeat = plan.out.beatSec
        val echoHold = if (plan.echoTail) outBeat * 8 else 0.0
        val stopAt = clock.releaseAt + 0.05 + echoHold + plan.exitTailSeconds
        val tailRate = if (clock.beats >= 1) {
            val b0 = clock.beatTimes[clock.beats - 1]
            val b1 = clock.beatTimes[clock.beats]
            (outBeat / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        // Replicates LoopMixSession.build's requiredOutTo (djmix18).
        val bounds = LoopMix.regionBounds(plan)
        val required = if (!plan.outLoops) {
            val lastPos = plan.out.loopStart + clock.beats * outBeat
            val tailTrack = (stopAt - clock.releaseAt).coerceAtLeast(0.0) * tailRate
            val pastElement = plan.out.loopStart + clock.elementAt + maxOf(echoHold, plan.exitTailSeconds) + 0.5
            maxOf(bounds.outTo, lastPos + tailTrack + 0.5, pastElement)
        } else bounds.outTo
        // The tail knot sits at stopAt on the steady rate; required outTo must cover it +0.5.
        val lastPos = plan.out.loopStart + clock.beats * outBeat
        val stopPos = lastPos + (stopAt - clock.releaseAt) * tailRate
        assertTrue("required outTo $required must cover stopPos $stopPos", required >= stopPos - 1e-6)
        assertTrue("required outTo $required must be past elementAt+tail", required >= plan.out.loopStart + clock.elementAt + maxOf(echoHold, plan.exitTailSeconds) - 1e-6)
    }

    // ---- 3. Cold-loop yield headroom ----

    @Test
    fun coldLoopYieldLeaves28sOr10BarsBeforeContentEnd() {
        // No exit known, 120 BPM: 10 bars = 20s < 28s floor, so 28s rules.
        assertTrue(shouldYieldColdLoopForContentEnd(210_000L, 182_000L, 120.0, null))
        assertTrue(!shouldYieldColdLoopForContentEnd(210_000L, 181_000L, 120.0, null)) // 29s left
        // 60 BPM: bar = 4s, 10 bars = 40s > 28s floor.
        assertTrue(shouldYieldColdLoopForContentEnd(210_000L, 175_000L, 60.0, null)) // 35s < 40s
        assertTrue(!shouldYieldColdLoopForContentEnd(210_000L, 165_000L, 60.0, null)) // 45s > 40s
        // Earliest exit 30s: 25s before exit yields even when contentEnd is far.
        assertTrue(shouldYieldColdLoopForContentEnd(300_000L, 200_000L, 120.0, 225_000L))
        assertTrue(!shouldYieldColdLoopForContentEnd(300_000L, 200_000L, 120.0, 235_000L)) // 35s to exit
    }

    @Test
    fun coldLoopYieldNeverYieldsInThePastOrBeforeLead() {
        assertTrue(!shouldYieldColdLoopForContentEnd(210_000L, 100_000L, 120.0, null))
        // Unknown BPM falls back to 28s floor.
        assertTrue(shouldYieldColdLoopForContentEnd(210_000L, 185_000L, 0.0, null))
        assertTrue(!shouldYieldColdLoopForContentEnd(210_000L, 180_000L, 0.0, null))
    }

    // ---- 4. Exact handover target (heard vs clock) ----

    @Test
    fun handoverTargetPrefersValidHeardElseClock() {
        val loopStart = 8.0
        assertEquals(12.5, handoverTargetSec(12.5, 12.0, loopStart), 1e-9)
        assertEquals(12.0, handoverTargetSec(null, 12.0, loopStart), 1e-9)
        assertEquals(12.0, handoverTargetSec(Double.NaN, 12.0, loopStart), 1e-9)
        // Heard far before the cue is not a handover.
        assertEquals(12.0, handoverTargetSec(1.0, 12.0, loopStart), 1e-9)
        assertEquals(12.0, handoverTargetSec(0.0, 12.0, loopStart), 1e-9)
    }

    // ---- 5. fromPcm full duration (Bug 3, SongMap) ----

    @Test
    fun head120MapOn210TrackKeepsFullDurationAndPartial() {
        val sr = 22050
        val pcm = FloatArray((120.0 * sr).toInt()) { i ->
            (0.4 * sin(2 * PI * 110.0 * i / sr)).toFloat()
        }
        val map = StructureAnalyzer.fromPcm(
            trackKey = "app-handoff-210",
            pcm = pcm,
            sampleRate = sr,
            bpm = 120.0,
            fullTrackDurationSec = 210.0,
        )
        assertTrue(map.partial)
        assertEquals(210.0, map.durationSec, 0.5)
        val exits = CuePoints.exits(map, playableOutDurationSec = 210.0)
        assertTrue(exits.isNotEmpty())
        for (e in exits) {
            assertTrue("exit at ${e.releaseSec} eats >22% of 210s", e.releaseSec >= 210.0 * 0.78 - 1e-6)
        }
        // Without playable duration the map still must not emit early exits.
        val exitsDefault = CuePoints.exits(map)
        assertTrue(exitsDefault.isNotEmpty())
        for (e in exitsDefault) {
            assertTrue(e.releaseSec >= 210.0 * 0.78 - 1e-6)
        }
    }
}
