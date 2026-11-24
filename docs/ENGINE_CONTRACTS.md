# AE3 Engine Contracts

All versioned data contracts live in `djmix-core/src/commonMain/kotlin/com/music/bitchord/djmix/core/contracts/`
(kotlinx.serialization, each with a `schema` int). Source of truth for sizes and field lists: the Kotlin files;
this document is the human reference. Engine version policy is at the bottom (Part B16).

## Versioning policy (Part B16) — `AE major.minor.patch`

`ENGINE_VERSION = "3.0.0"` (`contracts/EngineVersion.kt`; `ENGINE_MAJOR=3`, `ENGINE_MINOR=0`, `ENGINE_PATCH=0`).

- **major**: a contract schema breaks, or the state machine changes.
- **minor**: new recipes, effects or models.
- **patch**: tuning and fixes.

The engine version is shown in Settings → About and stamped into every telemetry record and diagnostics
bundle. djmix builds increment independently (`1.10-djmix53` / versionCode 95; ADR 0004).

Within a major.minor, contracts may **add** optional fields; they must never remove or rename a field, or
re-order an enum in a way that changes serialized values. Every schema class validates its `schema` int in
an `init { require(...) }` block.

## 1. `TrackAnalysisV3` — `schema = 3`

File: `contracts/TrackAnalysisV3.kt`. Purpose: beats, downbeats, grid confidence, BPM, key + confidence,
loudness (integrated + short-term curve), energy curve, vocal-presence curve (Syvenu, 50 fps), structure
(sections, phrases, builds, drops, breakdowns, chorus families), model versions. Consumers: planner,
Room store (`store/TrackAnalysisV3Mapper`), Inspector.

- `SongStructureV3` carries `sections: List<StructureSectionV3>`, `phrases: List<PhraseBoundaryV3>`,
  `builds` / `breakdowns: List<TimeRangeMs>`, `drops: List<DropEventV3>`, `chorusFamilies`,
  `phraseLenBars = 8` (StructureV2 may say 4 or 16), `mode`, `mood`.
- `analysisLevel: NONE | HEAD_TAIL | FULL`; `isUsable` = level ≥ HEAD_TAIL ∧ `bpm > 0` ∧ beats non-empty.

```json
{
  "schema": 3, "trackId": "t_0af3", "contentHash": "9c1d…", "durationMs": 214000,
  "analysisLevel": "FULL", "bpm": 124.02, "gridConfidence": 0.87,
  "beatsMs": [0, 484, 968, "…"], "downbeatsMs": [0, 1936, "…"],
  "keyCamelot": "8A", "keyConfidence": 0.91,
  "integratedLufs": -9.8, "shortTermLufsCurve": [-10.1, -9.9, "…"],
  "energyCurve": [0.4, 0.6, "…"], "vocalPresence50fps": [0.0, 0.0, "…"],
  "structure": { "sections": [ "…SectionType: INTRO|VERSE|CHORUS|DROP|BREAKDOWN|OUTRO…" ],
                 "phrases": [ "…PhraseBoundaryV3…" ], "phraseLenBars": 8 },
  "silentIntroEndMs": 1200, "audibleEndMs": 213800,
  "modelVersions": { "beat": "nirovi:int8-1", "vocal": "syvenu:int8-1" }
}
```

## 2. `TransitionPlanV1` — `schema = 1`

File: `contracts/TransitionPlanV1.kt`. Purpose: frozen exit/entry, geometry (`LOOP | BRIDGE | STRAIGHT`),
tempo map / knots (`TempoKnot` with optional exact-double fields for host/device bit-identity), swap
downbeat, recipe id + parameters, required stems (`DeckStemRequirement: {deck, stem}`), placement reasons,
top-3 candidate scores, `generation`. Execution identity is strictly
**`(outTrackId, inTrackId, generation)`** — never structural plan equality. Invariants in `init`: top-3
scores cap, non-negative times, positive `barDurationMs`.

```json
{
  "schema": 1, "planId": "pl_7", "generation": 42,
  "outTrackId": "t_0af3", "inTrackId": "t_b1e2",
  "djLevel": "FESTIVAL", "geometry": "LOOP",
  "frozenExitMs": 193600, "frozenEntryMs": 2400, "swapDownbeatMs": 196512,
  "overlapDurationMs": 29120, "barDurationMs": 1935,
  "tempoKnots": [{ "mixTimeMs": 0, "trackPosMs": 193600, "bpm": 124.0,
                   "exactMixSec": 0.0, "exactTrackPosSec": 193.6 }],
  "recipeId": "F1", "recipeName": "Riser drop",
  "recipeParams": { "riserBars": 8.0 },
  "requiredStems": [{ "deck": "IN", "stem": "DRUMS" }, { "deck": "IN", "stem": "BASS" }],
  "hasNoStemVariant": true,
  "placementReasons": ["Exit after final chorus · Entry at intro bar 1 · no vocal overlap"],
  "topScores": [{ "recipeId": "F1", "recipeName": "Riser drop", "score": 1.42, "reason": "drop-aligned" }],
  "tasteAdjustment": 0.12, "isLastResort": false
}
```

## 3. `TransitionScriptV1` — `schema = 1`

File: `contracts/TransitionScriptV1.kt`. Purpose: everything the renderer needs — both decks' region
references (`AudioRegionRef`: track id + window + `prerollMs`, `fadeOutTrackMs`, `levelGain`), knots,
`LoopBounds`, every envelope lane (`EnvelopeLane` with `EnvelopeKind` native ids 0–19), FX setter params
(`ScriptFxParams`), optional `StemRegionRef` stems, `LoudnessSettings`, `dropWindowMs`.

**Golden rule:** a `TransitionScriptV1` renders **bit-identically** on the device renderer and the host CLI
(`native/loopmix/loop_mix_renderer_ab_main --render script.json --out out.wav`) given the same PCM (ADR 0008).

Envelope kinds (excerpt): `GAIN(0)`, `LOW_DB(1)`, `HIGH_PASS_HZ(2, exponential)`, `LOW_PASS_HZ(3)`,
`ECHO_SEND(4)`, `MID_DB(5)`, `HIGH_DB(6)`, `FLANGER_WET(7)`, `REVERB_SEND(8)`, `STEM_VOCALS(9)`,
`STEM_DRUMS(10)`, `STEM_BASS(11)`, `STEM_OTHER(12)`, `FILTER_RES(13)`, `PHASER_WET(14)`, `GATE_DEPTH(15)`,
`RISER_LEVEL(16)`, `PUMP_DEPTH(17)`, `BRAKE(18)`, `FILTER_KNOB(19)`. Kinds 9–19 are v2; 9–12 are stem gains.

```json
{
  "schema": 1, "planId": "pl_7", "generation": 42, "sampleRateHz": 48000,
  "outRegion": { "deck": "OUT", "trackId": "t_0af3", "startMs": 189072, "endMs": 214000, "prerollMs": 400 },
  "inRegion":  { "deck": "IN",  "trackId": "t_b1e2", "startMs": 0, "endMs": 32000, "prerollMs": 400 },
  "knots": [{ "mixTimeMs": 0, "trackPosMs": 193600, "bpm": 124 }],
  "loopBounds": { "outLoopStartMs": 193600, "outLoopEndMs": 194568, "outReleaseAtMixMs": 27456 },
  "envelopes": [
    { "deck": "OUT", "kind": "HIGH_PASS_HZ", "timesSec": [0, 8, 16], "values": [20, 900, 1800], "exponential": true },
    { "deck": "IN",  "kind": "GAIN", "timesSec": [0, 8, 16], "values": [0.0, 0.6, 1.0] }
  ],
  "fxParams": { "gate": { "enabled": true, "divisionStepsPerBeat": 4.0, "depthPeak": 1.0 },
                "riser": { "enabled": true, "noiseSweepEndHz": 8000.0 } },
  "stems": [{ "deck": "IN", "stem": "DRUMS", "trackId": "t_b1e2", "startMs": 0, "endMs": 32000, "pcmPath": "stems/…drums.f32" }],
  "loudness": { "targetLufs": -9.5 }
}
```

## 4. `RecipeV2` — `schema = 2`

File: `contracts/RecipeV2.kt`; documents live in
`djmix-core/src/commonMain/resources/recipes/*.json` (26 files, `S1..S6`, `C1..C9`, `F1..F11` — see
[RECIPES.md](RECIPES.md)). Fields: `id`, `name`, `description`, `level` (`DjLevel: SUBTLE | CLUB | FESTIVAL`
with per-level `fxDepth`, `maxFx`, `maxEntrySkipSec`, `showBias`, `maxStretch`),
`family` (`BLEND | SWAP | CUT | FX | DROP`), `abruptness` (`SMOOTH | PUNCHY | ABRUPT`),
`requirements` (`RecipeRequirementsV2`: `maxTempoGap`, `requiredStems`, `minGridConfidence = 0.45`,
`minOverlapBars`, `requiredCapabilities`, `requiresTempoOrGenreJump`, …),
`lengthOptionsBars`, `placementPrefs` (`PlacementPrefsV2` with `RecipeAnchor`-referencing `swapRule`),
`steps` / `noStemSteps` (`lane_curve`, `swap_lows`, `configure_fx` with `StepTimeV2` anchors and
`WaypointV2` curves), `energyDelta`, `enabled`.

Timeline anchors (`RecipeAnchor`): `ZERO, IN_AUDIBLE, SWAP, RELEASE, OUT_STOP, PRE_FRAC, POST_FRAC,
OVERLAP_FRAC, A_PLUS_CAPPED_S, S_PLUS_CAPPED_R, SWAP_BACK_A, SWAP_BACK_ZERO, RELEASE_BACK_S, VOCAL_ENTRY,
FADE_START`. Curve shapes: `LINEAR, EXPONENTIAL, EQUAL_POWER_FALL, EQUAL_POWER_RISE`. Recipe FX types:
`LOOP, ROLL, SWEEP, FLANGER, PHASER, RISER, IMPACT, BRAKE, TAPE_STOP, SPINBACK, ECHO, GATE, REVERB, PUMP,
LOOP_ROLL, SVF_Q`.

See [RECIPES.md](RECIPES.md) for the full JSON example and authoring rules.

## 5. `MixEvent` / `MixState` / `MixEffect` (machine contract, not JSON on the wire)

File: `machine/MixContract.kt` (discriminators `MixStateKind` over **11 states**, `MixEventKind` over
**20 events**). `MixMachine.reduce(state, event): Pair<MixState, List<MixEffect>>` is pure — time arrives as
events (see [MIXMACHINE.md](MIXMACHINE.md)).

`MixEvent` (kotlinx-serialization wire names via `@SerialName`): `next_known`, `queue_edited`,
`analysis_ready`, `plan_frozen`, `regions_ready`, `stems_ready`, `renderer_prepared`, `tick`, `user_seek`,
`user_skip`, `user_pause`, `user_resume`, `audio_focus_lost`, `route_changed`, `backgrounded`,
`thermal_changed`, `renderer_error`, `handover_complete`, `level_changed`, `remix_requested`.

`MixEffect`: `StartPrepare(pair, generation)`, `CancelPrepare(generation)`, `PrepareRenderer(planId)`,
`Arm(planId, atMs)`, `Start(planId)`, `PauseSync`, `ResumeSync`, `Retire(deck)`, `Handover(planId)`,
`ShowUi(MixUiState)`, `Emit(telemetry)`.

`MixOutcome<T>` (`contracts/MixOutcome.kt`): `Ok(value) | Refused(reason: RefusalReason, detail) |
Failed(kind: FailureKind, message, recoverable)`. `RefusalReason` is the closed vocabulary used by the
preparer, reducer, telemetry and Inspector — e.g. `WAITING_ON_ANALYSIS`, `LOW_GRID_CONFIDENCE`,
`EXIT_MID_VOCAL`, `ENTRY_MID_VOCAL`, `EXIT_INSIDE_BUILD`, `BASS_CLASH`, `ABRUPT_DISALLOWED`,
`HANDOFF_*` (pcm/geometry/lyric/voice gates), `STEM_DUCK_UNAVAILABLE`, `COMMIT_DEADLINE_MISSED`,
`THERMAL_THROTTLED`, `USER_CANCELLED`, `QUEUE_CHANGED`, `CHOREOGRAPHY_UNAVAILABLE`, and others — see the
enum for the full list. `FailureKind`: `DECODE_ERROR, ANALYSIS_ERROR, STEM_ERROR,
RENDERER_PREPARE_ERROR, RENDERER_RUNTIME_ERROR, AUDIO_FOCUS_LOST_PERMANENT, INTERNAL_INVARIANT`.

## 6. `MixTelemetryV3` — `schema = 3`

File: `contracts/MixTelemetryV3.kt`. One JSONL schema for all automix telemetry, written by
`diagnostics/TelemetryV3Writer` (rotation at 10 MB × 3 files). Every record carries `engine = "3.0.0"`,
`planId`, `generation`, `state`; `TelemetryEventType.wireName` values: `mix.prep.started`,
`mix.plan.frozen`, `mix.ready`, `mix.armed`, `mix.fire`, `mix.timing`, `mix.moved`, `mix.last_resort`,
`mix.handover`, `mix.done`, `mix.countdown.broken`, `mix.classic`, `mix.suspended`, `mix.failed`,
`mix.refused`, `mix.remixed`, `mix.thermal.degraded`. Timing fields (`analysisMs`, `planMs`, `stemMsOut`,
`stemMsIn`, `buildMs`, `preparedLeadSec`, `armLeadMs`) feed the Part A §10 results table
(`tools/analyze_telemetry.py`).

```json
{ "schema": 3, "engine": "3.0.0", "timestampMs": 1731312000123,
  "eventType": "mix.fire", "planId": "pl_7", "generation": 42,
  "state": "RUNNING", "outId": "t_0af3", "inId": "t_b1e2",
  "djLevel": "FESTIVAL", "path": "PREPARER_FROZEN", "recipeId": "F1",
  "preparedLeadSec": 14.8, "armLeadMs": 14800 }
```

## 7. `MixUiState`

File: `contracts/MixUiState.kt`. The single read-only UI projection emitted by `MixMachine` (via
`MixUiProjection`) for the player chip and Mix Inspector. `MixUiPhase` maps 1:1 from the 11 machine states.

**Invariant enforced in `init`:** `countdownVisible == true` only when `phase` is `READY` or `ARMED`.
`canRemix` is true only in PREPARING/READY with ≥ `MixMachineConfig.remixMinLeadMs = 20_000 ms` left.

```json
{ "phase": "READY", "chipText": "Riser drop in 0:42", "progressPct": 100,
  "recipeName": "Riser drop", "countdownSec": 42, "countdownVisible": true,
  "inspectorReason": "Exit after final chorus · Entry at intro bar 1 · no vocal overlap",
  "readiness": { "analysisDone": true, "planDone": true, "stemsReady": 2, "stemsRequired": 2, "rendererReady": false },
  "canRemix": true }
```
