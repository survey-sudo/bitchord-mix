package com.music.bitchord.djmix.core.machine

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.BrakeFxParams
import com.music.bitchord.djmix.core.contracts.CandidateScoreSummary
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.EchoFxParams
import com.music.bitchord.djmix.core.contracts.EnvelopeKind
import com.music.bitchord.djmix.core.contracts.EnvelopeLane
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.contracts.FxCapability
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.MixTelemetryV3
import com.music.bitchord.djmix.core.contracts.MixUiPhase
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.contracts.PhraseBoundaryV3
import com.music.bitchord.djmix.core.contracts.RecipeAnchor
import com.music.bitchord.djmix.core.contracts.RecipeFamily
import com.music.bitchord.djmix.core.contracts.RecipeFxType
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.RecipeStepV2
import com.music.bitchord.djmix.core.contracts.ScriptFxParams
import com.music.bitchord.djmix.core.contracts.SectionType
import com.music.bitchord.djmix.core.contracts.SongStructureV3
import com.music.bitchord.djmix.core.contracts.StepTimeV2
import com.music.bitchord.djmix.core.contracts.StructureSectionV3
import com.music.bitchord.djmix.core.contracts.TelemetryEventType
import com.music.bitchord.djmix.core.contracts.ThermalLevel
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.WaypointV2
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lean M1 gate: 11x20 matrix (220 cells) + contract JSON round-trips + B1.2#5.
 * Matrix fixtures keep playback far from the frozen exit so no cell fires
 * Start by accident; firing itself is covered by dedicated invariant steps.
 */
class MixCoreMatrixAndContractsTest {

    private val pair = PairKey(outTrackId = "out-A", inTrackId = "in-A")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }

    private fun plan(generation: Long = 1L): TransitionPlanV1 = TransitionPlanV1(
        planId = "plan-m", generation = generation, outTrackId = "out-A", inTrackId = "in-A",
        djLevel = DjLevel.CLUB, geometry = TransitionGeometry.BRIDGE,
        frozenExitMs = 180_000L, frozenEntryMs = 164_000L, swapDownbeatMs = 180_000L,
        overlapDurationMs = 16_000L, barDurationMs = 2_000L,
        recipeId = "recipe-m", recipeName = "Matrix Blend",
        placementReasons = listOf("phrase-aligned"),
        topScores = listOf(CandidateScoreSummary("recipe-m", "Matrix Blend", 0.9, "best")),
        nextEligiblePhraseMs = 260_000L, lastPhraseDownbeatMs = 240_000L,
    )

    private fun states(): Map<MixStateKind, MixState> {
        val p = plan(1L)
        return mapOf(
            MixStateKind.IDLE to MixState.Idle(1L, DjLevel.CLUB),
            MixStateKind.PREPARING to MixState.Preparing(pair, 1L, DjLevel.CLUB, lastPositionMs = 10_000L),
            MixStateKind.READY to MixState.Ready(pair, 1L, DjLevel.CLUB, p, lastPositionMs = 10_000L),
            MixStateKind.ARMED to MixState.Armed(pair, 1L, DjLevel.CLUB, p, lastPositionMs = 10_000L),
            MixStateKind.RUNNING to MixState.Running(pair, 1L, DjLevel.CLUB, p, startedAtMs = 160_000L, lastPositionMs = 160_000L),
            MixStateKind.HANDING_OVER to MixState.HandingOver(pair, 1L, DjLevel.CLUB, p, lastPositionMs = 176_000L),
            MixStateKind.DONE to MixState.Done(pair, 1L, DjLevel.CLUB, p),
            MixStateKind.MOVED to MixState.Moved(pair, 1L, DjLevel.CLUB, fromExitMs = 180_000L, newExitMs = 260_000L, plan = p),
            MixStateKind.LAST_RESORT to MixState.LastResort(pair, 1L, DjLevel.CLUB, p.copy(isLastResort = true), rendererPrepared = true, lastPositionMs = 10_000L),
            MixStateKind.SUSPENDED to MixState.Suspended(com.music.bitchord.djmix.core.contracts.RefusalReason.AUDIO_FOCUS_LOST, pair = pair, generation = 1L, djLevel = DjLevel.CLUB, plan = p),
            MixStateKind.FAILED to MixState.Failed(FailureKind.RENDERER_PREPARE_ERROR, pair = pair, generation = 1L, djLevel = DjLevel.CLUB, plan = p),
        )
    }

    private fun events(): Map<MixEventKind, MixEvent> = mapOf(
        MixEventKind.NEXT_KNOWN to MixEvent.NextKnown(pair, 10_000L, DjLevel.CLUB, true),
        MixEventKind.QUEUE_EDITED to MixEvent.QueueEdited("in-B", 10_000L),
        MixEventKind.ANALYSIS_READY to MixEvent.AnalysisReady("out-A", AnalysisLevel.FULL, 1L),
        MixEventKind.PLAN_FROZEN to MixEvent.PlanFrozen(plan(1L), 1L),
        MixEventKind.REGIONS_READY to MixEvent.RegionsReady("plan-m", 1L),
        MixEventKind.STEMS_READY to MixEvent.StemsReady("plan-m", 1L, 0, 0),
        MixEventKind.RENDERER_PREPARED to MixEvent.RendererPrepared("plan-m", 1L, false),
        MixEventKind.TICK to MixEvent.Tick(10_000L, true),
        MixEventKind.USER_SEEK to MixEvent.UserSeek(10_000L),
        MixEventKind.USER_SKIP to MixEvent.UserSkip,
        MixEventKind.USER_PAUSE to MixEvent.UserPause,
        MixEventKind.USER_RESUME to MixEvent.UserResume,
        MixEventKind.AUDIO_FOCUS_LOST to MixEvent.AudioFocusLost(transient = true),
        MixEventKind.ROUTE_CHANGED to MixEvent.RouteChanged(120L),
        MixEventKind.BACKGROUNDED to MixEvent.Backgrounded(true),
        MixEventKind.THERMAL_CHANGED to MixEvent.ThermalChanged(ThermalLevel.NONE),
        MixEventKind.RENDERER_ERROR to MixEvent.RendererError(FailureKind.DECODE_ERROR, "x", true),
        MixEventKind.HANDOVER_COMPLETE to MixEvent.HandoverComplete,
        MixEventKind.LEVEL_CHANGED to MixEvent.LevelChanged(DjLevel.FESTIVAL),
        MixEventKind.REMIX_REQUESTED to MixEvent.RemixRequested,
    )

    @Test
    fun matrixAll220CellsReduceCleanly() {
        val cfg = MixMachineConfig.DEFAULT
        val visited = mutableSetOf<Pair<MixStateKind, MixEventKind>>()
        for ((sk, st) in states()) for ((ek, ev) in events()) {
            val (next, fx) = MixMachine.reduce(st, ev, cfg)
            visited += sk to ek
            for (show in fx.filterIsInstance<MixEffect.ShowUi>()) {
                assertTrue(
                    !show.uiState.countdownVisible || next.stateKind in setOf(MixStateKind.READY, MixStateKind.ARMED),
                    "$sk x $ek countdown visible in ${next.stateKind}",
                )
            }
            if (fx.any { it is MixEffect.Start }) {
                assertTrue(sk == MixStateKind.ARMED || sk == MixStateKind.LAST_RESORT, "$sk x $ek Start from $sk")
            }
            if (fx.any { it is MixEffect.ResumeSync }) {
                assertTrue(ek == MixEventKind.USER_RESUME, "$sk x $ek ResumeSync without USER_RESUME")
            }
            if (st is MixState.HandingOver) {
                for (ret in fx.filterIsInstance<MixEffect.Retire>()) {
                    assertTrue(ret.deck != st.survivingDeck, "$sk x $ek retired survivingDeck")
                }
            }
            assertTrue(next.generation >= 0L, "$sk x $ek negative generation")
            assertTrue(MixMachine.uiStateFor(next, cfg).let { !it.countdownVisible || next.stateKind == MixStateKind.READY || next.stateKind == MixStateKind.ARMED }, "$sk x $ek projection countdown")
        }
        assertEquals(220, visited.size)
    }

    @Test
    fun contractsJsonRoundTripAndIds() {
        val analysis = TrackAnalysisV3(
            trackId = "t1", durationMs = 200_000L, analysisLevel = AnalysisLevel.FULL, bpm = 124.0,
            beatsMs = listOf(0L, 500L), downbeatsMs = listOf(0L),
            structure = SongStructureV3(sections = listOf(StructureSectionV3(0L, 200_000L, type = SectionType.OUTRO)), phrases = listOf(PhraseBoundaryV3(0L))),
        )
        assertEquals(analysis, json.decodeFromString(TrackAnalysisV3.serializer(), json.encodeToString(TrackAnalysisV3.serializer(), analysis)))
        val p = plan(1L)
        assertEquals(p, json.decodeFromString(TransitionPlanV1.serializer(), json.encodeToString(TransitionPlanV1.serializer(), p)))
        val script = TransitionScriptV1(
            planId = "plan-m", generation = 1L,
            outRegion = AudioRegionRef(DeckTarget.OUT, "out-A", 0L, 180_000L),
            inRegion = AudioRegionRef(DeckTarget.IN, "in-A", 0L, 180_000L),
            envelopes = listOf(EnvelopeLane(DeckTarget.OUT, EnvelopeKind.GAIN, listOf(0.0, 8.0), listOf(1f, 0.5f))),
            fxParams = ScriptFxParams(brake = BrakeFxParams(enabled = true), echo = EchoFxParams(enabled = true)),
        )
        assertEquals(script, json.decodeFromString(TransitionScriptV1.serializer(), json.encodeToString(TransitionScriptV1.serializer(), script)))
        val recipe = RecipeV2(
            id = "recipe-m", name = "Matrix Blend", level = DjLevel.CLUB, family = RecipeFamily.BLEND,
            abruptness = com.music.bitchord.djmix.core.contracts.AbruptnessClass.SMOOTH,
            steps = listOf(
                RecipeStepV2.LaneCurve(DeckTarget.OUT, EnvelopeKind.GAIN, points = listOf(WaypointV2(StepTimeV2(RecipeAnchor.SWAP), 1f))),
                RecipeStepV2.SwapLows(), RecipeStepV2.EnableIsolator,
                RecipeStepV2.ConfigureFx(DeckTarget.OUT, RecipeFxType.BRAKE),
            ),
            noStemSteps = listOf(RecipeStepV2.EnableIsolator),
        )
        assertEquals(recipe, json.decodeFromString(RecipeV2.serializer(), json.encodeToString(RecipeV2.serializer(), recipe)))
        val tele = MixTelemetryV3(timestampMs = 100L, eventType = TelemetryEventType.MIX_FIRE, state = "RUNNING", djLevel = DjLevel.CLUB)
        assertEquals("3.0.0", tele.engine)
        assertEquals(tele, json.decodeFromString(MixTelemetryV3.serializer(), json.encodeToString(MixTelemetryV3.serializer(), tele)))
        val ui = MixUiState(phase = MixUiPhase.READY, chipText = "Blend in 0:12", countdownSec = 12, countdownVisible = true)
        assertEquals(ui, json.decodeFromString(MixUiState.serializer(), json.encodeToString(MixUiState.serializer(), ui)))
        assertEquals(MixOutcome.Ok("hello"), json.decodeFromString<MixOutcome.Ok<String>>(json.encodeToString(MixOutcome.Ok("hello"))))
        val refused = MixOutcome.Refused(com.music.bitchord.djmix.core.contracts.RefusalReason.WAITING_ON_ANALYSIS, "wait")
        assertEquals(refused, json.decodeFromString<MixOutcome.Refused>(json.encodeToString(refused)))
        val failed = MixOutcome.Failed(FailureKind.DECODE_ERROR, "bad", true)
        assertEquals(failed, json.decodeFromString<MixOutcome.Failed>(json.encodeToString(failed)))
        assertEquals(20, EnvelopeKind.entries.size)
        assertEquals((0..19).toSet(), EnvelopeKind.entries.map { it.nativeId }.toSet())
        assertEquals(EnvelopeKind.BRAKE, EnvelopeKind.fromNativeId(18))
        assertEquals(EnvelopeKind.FILTER_KNOB, EnvelopeKind.fromNativeId(19))
        assertEquals(12, FxCapability.entries.size)
        assertEquals(12, FxCapability.entries.map { it.bit }.distinct().size)
        assertEquals(FxCapability.ALL_MASK, FxCapability.toMask(FxCapability.entries))
        assertEquals(FxCapability.entries.toSet(), FxCapability.fromMask(FxCapability.ALL_MASK))
    }

    @Test
    fun b12Invariant5ConfigStickyPauseSurvivorNoStem() {
        assertEquals(4, MixMachineConfig.DEFAULT.commitPointBars)
        assertEquals(6_000L, MixMachineConfig.DEFAULT.minCommitLeadMs)
        assertEquals(8_000L, MixMachineConfig.DEFAULT.commitLeadMs(2_000L))
        val cfg = MixMachineConfig.DEFAULT
        var st: MixState = MixState.Ready(pair, 1L, DjLevel.CLUB, plan(1L), lastPositionMs = 10_000L)
        val paused = MixMachine.reduce(st, MixEvent.UserPause, cfg)
        st = paused.first
        assertTrue(st.userPaused)
        assertTrue(paused.second.any { it is MixEffect.PauseSync })
        for (ev in listOf(MixEvent.Tick(11_000L, true), MixEvent.UserSeek(12_000L), MixEvent.RouteChanged(100L))) {
            val r = MixMachine.reduce(st, ev, cfg)
            assertTrue(r.first.userPaused, "sticky pause lost on $ev")
            assertTrue(r.second.none { it is MixEffect.ResumeSync }, "ResumeSync before USER_RESUME on $ev")
            st = r.first
        }
        assertTrue(!MixMachine.reduce(st, MixEvent.UserResume, cfg).first.userPaused)
        val ho = MixState.HandingOver(pair, 1L, DjLevel.CLUB, plan(1L))
        val skip = MixMachine.reduce(ho, MixEvent.UserSkip, cfg)
        assertTrue(skip.second.filterIsInstance<MixEffect.Retire>().all { it.deck != ho.survivingDeck })
        val handover = MixMachine.reduce(ho, MixEvent.HandoverComplete, cfg)
        assertTrue(handover.second.filterIsInstance<MixEffect.Retire>().all { it.deck != ho.survivingDeck })
        val prep = MixState.Preparing(
            pair, 1L, DjLevel.FESTIVAL, plan = plan(1L), regionsReady = true, stemsReady = false,
            stemsRequired = 2, stemsCompleted = 0, rendererPrepared = false, lastPositionMs = 172_000L,
        )
        val ns = MixMachine.reduce(prep, MixEvent.Tick(172_000L, true), cfg)
        assertEquals(DjLevel.FESTIVAL, ns.first.djLevel)
        assertTrue(ns.second.any { it is MixEffect.PrepareRenderer && it.useNoStemVariant })
    }
}
