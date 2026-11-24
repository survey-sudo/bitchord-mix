package com.music.bitchord.djmix.core.machine

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.MixUiPhase
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.contracts.ReadinessSummary
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1

/** Pure UI projection from [MixState] to [MixUiState] for the player chip and Mix Inspector. */
internal object MixUiProjection {

    fun uiStateFor(state: MixState, config: MixMachineConfig): MixUiState = when (state) {
        is MixState.Idle -> MixUiState(phase = MixUiPhase.IDLE)
        is MixState.Preparing -> prepUiState(
            phase = MixUiPhase.PREPARING,
            plan = state.plan,
            outAnalysis = state.outAnalysisLevel,
            inAnalysis = state.inAnalysisLevel,
            regionsReady = state.regionsReady,
            stemsReady = state.stemsReady || state.usingNoStemVariant || state.stemsRequired == 0,
            stemsCompleted = state.stemsCompleted,
            stemsRequired = state.stemsRequired,
            rendererPrepared = state.rendererPrepared,
            movedTargetSec = null,
            canRemix = isRemixAllowed(state.plan, state.lastPositionMs, config),
        )
        is MixState.Moved -> prepUiState(
            phase = MixUiPhase.MOVED,
            plan = state.plan,
            outAnalysis = state.outAnalysisLevel,
            inAnalysis = state.inAnalysisLevel,
            regionsReady = state.regionsReady,
            stemsReady = state.stemsReady || state.usingNoStemVariant || state.stemsRequired == 0,
            stemsCompleted = state.stemsCompleted,
            stemsRequired = state.stemsRequired,
            rendererPrepared = state.rendererPrepared,
            movedTargetSec = (state.newExitMs / 1000L).toInt().coerceAtLeast(0),
            canRemix = false,
        )
        is MixState.Ready -> countdownUiState(
            phase = MixUiPhase.READY,
            plan = state.plan,
            targetMs = state.plan.frozenExitMs,
            positionMs = state.lastPositionMs,
            stemsReady = if (state.usingNoStemVariant) 0 else state.stemsCompleted,
            stemsRequired = if (state.usingNoStemVariant) 0 else state.stemsRequired,
            canRemix = isRemixAllowed(state.plan, state.lastPositionMs, config),
            config = config,
        )
        is MixState.Armed -> countdownUiState(
            phase = MixUiPhase.ARMED,
            plan = state.plan,
            targetMs = state.armAtMs,
            positionMs = state.lastPositionMs,
            stemsReady = if (state.usingNoStemVariant) 0 else state.plan.requiredStems.size,
            stemsRequired = if (state.usingNoStemVariant) 0 else state.plan.requiredStems.size,
            canRemix = false,
            config = config,
        )
        is MixState.Running -> activeUiState(
            phase = MixUiPhase.RUNNING,
            chipText = "Mixing · ${state.plan.recipeName}",
            progressPct = state.progressPct.coerceIn(0, 100),
            plan = state.plan,
            rendererReady = true,
        )
        is MixState.HandingOver -> activeUiState(
            phase = MixUiPhase.HANDING_OVER,
            chipText = "Mixing · ${state.plan.recipeName}",
            progressPct = 100,
            plan = state.plan,
            rendererReady = true,
        )
        is MixState.Done -> MixUiState(
            phase = MixUiPhase.DONE,
            progressPct = 100,
            recipeName = state.plan.recipeName,
            readiness = ReadinessSummary(true, true, 0, 0, true),
        )
        is MixState.LastResort -> activeUiState(
            phase = MixUiPhase.LAST_RESORT,
            chipText = "Ending with echo-out",
            progressPct = if (state.rendererPrepared) 100 else 80,
            plan = state.plan,
            rendererReady = state.rendererPrepared,
        )
        is MixState.Suspended -> MixUiState(
            phase = MixUiPhase.SUSPENDED,
            recipeName = state.plan?.recipeName,
            inspectorReason = state.detail,
        )
        is MixState.Failed -> MixUiState(
            phase = MixUiPhase.FAILED,
            recipeName = state.plan?.recipeName,
            inspectorReason = state.message,
        )
    }

    fun isRemixAllowed(plan: TransitionPlanV1?, positionMs: Long, config: MixMachineConfig): Boolean =
        plan != null && (plan.frozenExitMs - positionMs) >= config.remixMinLeadMs

    private fun prepUiState(
        phase: MixUiPhase,
        plan: TransitionPlanV1?,
        outAnalysis: AnalysisLevel,
        inAnalysis: AnalysisLevel,
        regionsReady: Boolean,
        stemsReady: Boolean,
        stemsCompleted: Int,
        stemsRequired: Int,
        rendererPrepared: Boolean,
        movedTargetSec: Int?,
        canRemix: Boolean,
    ): MixUiState {
        val pct = prepProgressPct(
            outAnalysis, inAnalysis, plan != null, regionsReady,
            stemsReady, stemsCompleted, stemsRequired, rendererPrepared,
        )
        val movedReason = if (movedTargetSec != null) "stems ready soon" else null
        val chipText = if (movedTargetSec != null) {
            "Moved to ${formatMmSs(movedTargetSec)} · $movedReason"
        } else {
            "Preparing mix · $pct%"
        }
        return MixUiState(
            phase = phase,
            chipText = chipText,
            progressPct = pct,
            recipeName = plan?.recipeName,
            movedTargetSec = movedTargetSec,
            movedReason = movedReason,
            inspectorReason = plan?.placementReasons?.firstOrNull().orEmpty(),
            readiness = ReadinessSummary(
                analysisDone = outAnalysis != AnalysisLevel.NONE && inAnalysis != AnalysisLevel.NONE,
                planDone = plan != null,
                stemsReady = stemsCompleted.coerceAtLeast(0),
                stemsRequired = stemsRequired.coerceAtLeast(0),
                rendererReady = rendererPrepared,
            ),
            canRemix = canRemix,
        )
    }

    private fun countdownUiState(
        phase: MixUiPhase,
        plan: TransitionPlanV1,
        targetMs: Long,
        positionMs: Long,
        stemsReady: Int,
        stemsRequired: Int,
        canRemix: Boolean,
        config: MixMachineConfig,
    ): MixUiState {
        val remainingMs = (targetMs - positionMs).coerceAtLeast(config.countdownToleranceMs + 1L)
        val countdownSec = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(1)
        return MixUiState(
            phase = phase,
            chipText = "${plan.recipeName} in ${formatMmSs(countdownSec)}",
            progressPct = 100,
            recipeName = plan.recipeName,
            countdownSec = countdownSec,
            countdownVisible = true,
            inspectorReason = plan.placementReasons.firstOrNull().orEmpty(),
            readiness = ReadinessSummary(
                analysisDone = true,
                planDone = true,
                stemsReady = stemsReady.coerceAtLeast(0),
                stemsRequired = stemsRequired.coerceAtLeast(0),
                rendererReady = true,
            ),
            canRemix = canRemix,
        )
    }

    private fun activeUiState(
        phase: MixUiPhase,
        chipText: String,
        progressPct: Int,
        plan: TransitionPlanV1,
        rendererReady: Boolean,
    ): MixUiState = MixUiState(
        phase = phase,
        chipText = chipText,
        progressPct = progressPct,
        recipeName = plan.recipeName,
        inspectorReason = plan.placementReasons.firstOrNull().orEmpty(),
        readiness = ReadinessSummary(
            analysisDone = true,
            planDone = true,
            stemsReady = 0,
            stemsRequired = 0,
            rendererReady = rendererReady,
        ),
    )

    private fun prepProgressPct(
        outLevel: AnalysisLevel,
        inLevel: AnalysisLevel,
        hasPlan: Boolean,
        regionsReady: Boolean,
        stemsReady: Boolean,
        stemsCompleted: Int,
        stemsRequired: Int,
        rendererPrepared: Boolean,
    ): Int {
        var pct = 0
        if (outLevel != AnalysisLevel.NONE) pct += 10
        if (inLevel != AnalysisLevel.NONE) pct += 10
        if (hasPlan) pct += 20
        if (regionsReady) pct += 20
        pct += when {
            stemsReady -> 25
            stemsRequired > 0 -> (stemsCompleted.coerceIn(0, stemsRequired) * 25) / stemsRequired
            else -> 0
        }
        if (rendererPrepared) pct += 15
        return pct.coerceIn(0, 100)
    }

    private fun formatMmSs(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
