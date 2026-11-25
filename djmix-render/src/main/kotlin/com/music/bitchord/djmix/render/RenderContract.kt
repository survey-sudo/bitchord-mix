package com.music.bitchord.djmix.render

import com.music.bitchord.djmix.core.contracts.*
import com.music.bitchord.djmix.core.learning.TasteSnapshot
import com.music.bitchord.djmix.core.machine.*
import kotlinx.serialization.Serializable
import java.nio.file.Path

enum class ReplayErrorCode {
    INPUT, INVALID_ZIP, UNSAFE_ENTRY, LIMIT_EXCEEDED, MISSING_ENTRY,
    INVALID_JSON, UNSUPPORTED_VERSION, INVALID_MANIFEST, BAD_HASH,
    IDENTITY_MISMATCH, CORE_REFUSAL, SCRIPT_MISMATCH, AUDIO_UNAVAILABLE, INVALID_AUDIO, RENDER_FAILURE,
}

class BundleReplayException(val code: ReplayErrorCode, message: String, cause: Throwable? = null) :
    Exception(message, cause)

@Serializable
data class FlagEntryStatus(val available: Boolean, val entry: String?, val bytes: Long, val sha256: String?, val detail: String?)

/** Exact DiagnosticsBundler schema; the manifest is not in its own entries table. */
@Serializable
data class FlagManifest(
    val schema: Int,
    val engine: String,
    val trigger: String,
    val createdAtMs: Long,
    val planId: String?,
    val generation: Long?,
    val outTrackId: String?,
    val inTrackId: String?,
    val entries: Map<String, FlagEntryStatus>,
    val assetSha256: Map<String, String>,
    val modelSha256: Map<String, String>,
    val recipeSha256: String?,
    val warnings: List<String>,
)

/** App-owned flags wire shape only. This module does not reimplement app policy. */
@Serializable
data class CapturedFlags(
    val safeMode: Boolean,
    val forceLevel: DjLevel?,
    val forceRecipeId: String?,
    val showGridOnSeekbar: Boolean,
    val showInspectorDetails: Boolean,
    val learningEnabled: Boolean,
    val killSwitches: Map<String, Boolean>,
    val disabledRecipeIds: Set<String>,
)

/** Actual recordDebugEvent wire shape; JSONObject.put(null) omits nullable identities. */
@Serializable
data class CapturedDecision(
    val ts: Long,
    val event: MixEventKind,
    val state: MixStateKind,
    val generation: Long,
    val planId: String? = null,
    val outId: String? = null,
    val inId: String? = null,
)

data class RenderContract(
    val manifest: FlagManifest,
    val plan: TransitionPlanV1,
    val recipe: RecipeV2,
    val geometry: RenderGeometryV1,
    val script: TransitionScriptV1,
    val decisions: List<CapturedDecision>,
    val telemetry: List<MixTelemetryV3>,
    val outAnalysis: TrackAnalysisV3?,
    val inAnalysis: TrackAnalysisV3?,
    val flags: CapturedFlags?,
    val taste: TasteSnapshot?,
)

/** Unavailable is not a mismatch. Current Flag rings cannot establish reducer equivalence. */
@Serializable
data class ReducerReplayCoverage(
    val complete: Boolean,
    val equivalent: Boolean?,
    val replayedEventCount: Int,
    val reason: String,
)

@Serializable
data class BundleDecisionTrace(
    val schema: Int = 1,
    val operation: String = "decision_trace",
    val engine: String,
    val planId: String,
    val generation: Long,
    val manifest: FlagManifest,
    val capturedScriptSha256: String,
    val compiledScriptSha256: String,
    val matchingVariants: List<String>,
    val compilerCapabilities: List<FxCapability>,
    val decisions: List<CapturedDecision>,
    val telemetry: List<MixTelemetryV3>,
    val compiledScript: TransitionScriptV1,
    val reducerReplayComplete: Boolean,
    val reducerCoverage: ReducerReplayCoverage,
    val limitations: List<String>,
    val warnings: List<String>,
)

/** Full reducer inputs are an explicit caller API, not an invented Flag ZIP dialect. */
fun replayReducer(initial: MixState, events: List<MixEvent>, config: MixMachineConfig): List<ReplayStep> =
    MixMachine.replay(initial, events, config)

data class RenderRequest(val scriptJson: String, val outputWav: Path, val workingDirectory: Path)

/** Owner-selected host files only; no path is obtained from an archived StemRegionRef. */
data class StemPcmInput(val deck: DeckTarget, val stem: StemKind, val path: Path)

/** Hashes describe the staged bytes, not a decoder/device or captured-asset parity claim. */
@Serializable
data class ReplayPcmInput(
    val deck: DeckTarget,
    val stem: StemKind?,
    val trackId: String,
    val startMs: Long,
    val endMs: Long,
    val sampleRateHz: Int,
    val frameCount: Int,
    val interleavedF32leSha256: String,
    val leftF32leSha256: String,
    val rightF32leSha256: String,
    val encoding: String = "f32le_interleaved_stereo",
    val source: String = "explicit_owner_override",
    val capturedAssetIdentity: String = "owner_provided_unverified",
    val verifiedCapturedAssetSha256: Map<String, String> = emptyMap(),
)

/** An adapter must create outputWav. The engine independently inspects the actual WAV. */
fun interface RenderBackend {
    fun render(request: RenderRequest)
}

/** PCM16 sample peak only; no true-peak, decoder, device or bit-exact parity claim. */
data class RenderResult(val outputWav: Path, val pcmSha256: String, val sampleRateHz: Int, val sampleCount: Long, val samplePeak: Double)

data class RenderReplayResult(
    val decisionTrace: BundleDecisionTrace,
    val render: RenderResult,
    val expectedSha256: String?,
    val hashMatched: Boolean?,
    val localPcmOverrides: Boolean = false,
    val inputPcm: List<ReplayPcmInput> = emptyList(),
    val unverifiedCapturedAssetHashKeys: List<String> = emptyList(),
)
