# AE3 Tuning — symptom → knob

Find the symptom, turn the named knob. After any tuning change, re-run the `djmix-core` tests and the
audition (`tools/audition/audition_render.py`) — tuning is verified by [QUALITY.md](QUALITY.md) gates,
not by eye. Engine version this table targets: **`AE 3.0.0`** (`djmix-core` `EngineVersion.kt`).
Wire names in parentheses are the actual code symbols.

## Rules of engagement

- Change the **least global** knob: recipe JSON → DJ-level constant → planner weight → machine config →
  flag → native parameter. Touching `djmix-core` contracts (schema changes) is a **major** version bump —
  don't.
- Every knob below is a named constant or a fields-in-data default; there are no magic numbers on the
  automix paths.

## Symptom → knob table

| Symptom | Knob | Where | Note |
|---|---|---|---|
| Transitions hold the OUT deck too long / swap too late | `swapRule` / `placementPrefs` of the recipe, or `PlacementEvaluator` exit scoring (`exitScore`: outro boundary +0.40/+0.12/+0.28/+0.26, chorus/drop-exit penalty −0.32) | recipe JSON `djmix-core/src/commonMain/resources/recipes/<ID>.json`; `planner/PlacementEvaluator.kt` | tune per recipe first |
| Transitions start too early inside long intros | `preferIntroEntry`, entry scoring (+0.34 intro downbeat / +0.28 / +0.26), `maxEntrySkipBars`, `DjLevel.maxEntrySkipSec` (32 s Club / 64 s Festival / 0 SUBTLE) | recipe JSON; `contracts/EngineVersion.kt` (`DjLevel`) |
| Exit lands mid-vocal | placement hard reject threshold (Syvenu > 0.5 within ±1 beat of exit) | `planner/PlacementEvaluator.kt` | never lower blindly — see [QUALITY.md](QUALITY.md) gate 7 |
| Too many abrupt cuts at Festival | `PairPlanner.ABRUPT_COOLDOWN_TRANSITIONS = 8` (transitions), `jumpAllowed` (ΔBPM > 10 %, or > 8 % with genre change) — ADR 0010 | `planner/PairPlanner.kt` | abrupt is never a fallback |
| Wrong fallback pick on weak grids | low-confidence fallback bonuses `F5 → 0.40`, `C2 → 0.32` (ADR 0010) | `PairPlanner.kt` | never F2/F5-slam |
| Recipe variety too low | variety penalties (`recent3` recipes −0.38/−0.22), Festival unseen-FX bonus (`unseenFxCount × 0.05`, cap 0.12), F1 drop-alignment bonus `dropBoost 0.18` | `PairPlanner.kt` | gate: ≥ 4 distinct recipes / 6 transitions, ≥ 7 over 12 |
| Learned taste over/under-weighted | `LEARNING` bound `TASTE_WEIGHT_CAP = 0.30`; reset in Settings → Automix → "Reset learned preferences" | `planner/PairPlanner.kt`, `learning/TasteModel.kt` | weight is a separate inspectable score term |
| Level skews showy/restrained | `DjLevel.showBias` (SUBTLE −0.10, CLUB 0.0, FESTIVAL +0.10), `fxDepth` (0.6/0.85/1.0), `maxFx` (1/2/3), `maxStretch` (3/6/8 %) | `contracts/EngineVersion.kt` | level-wide, touches all recipes |
| Countdown fires early/late | `MixMachineConfig.commitPointBars = 4`, `minCommitLeadMs = 6_000` | `machine/MixMachineConfig.kt` | one place (D4 test) |
| Countdown shows where it shouldn't | `MixMachineConfig.countdownToleranceMs = 50`, the `MixUiState` READY/ARMED-only `require` | `machine/MixMachineConfig.kt`, `contracts/MixUiState.kt` | honest-countdown invariant |
| "Try another mix" missing/wrong | `MixMachineConfig.remixMinLeadMs = 20_000` | `machine/MixMachineConfig.kt` | |
| MOVED banner timing | `MixMachineConfig.movedBannerDurationMs = 3_000` | `machine/MixMachineConfig.kt` | |
| Pause behaves badly during a mix | sticky-pause handling in `MixMachine` (`UserPause`/`UserResume`, `PauseSync`/`ResumeSync`) | `machine/MixMachine.kt` | never auto-resume |
| Thermal kills prep | `ThermalLevel.requiresStemDegradation` (SEVERE+), `MixMachine.applyStemDegradation` | `contracts/EngineVersion.kt`, `machine/MixMachine.kt` | running mix never stops |
| A whole feature must ship off (hotfix path) | `DjmixFlags` kill switches: `"stems"`, `"analysis"`, `"recipe_lab"`, `"npu_qnn"`, or Safe mode (`SAFE_MODE_RECIPE_IDS = {S1, S5}`) | `app/src/main/java/com/music/bitchord/playback/djmix/diagnostics/DjmixFlags.kt` | see [RELEASE.md](RELEASE.md) |
| Loop feels unsteady | `LoopFxParams.bars`, `.seamCrossfadeMs = 8.0`, `.holdPhrases` | `contracts/TransitionScriptV1.kt` (+ recipe JSON) | |
| Roll too eager to disappear | `RollFxParams.scheduleBeats = [4,2,1,0.5,0.25]`, `.hpfStartHz = 200`, `.hpfEndHz = 2000`, `.boostDb = 2`, `.stopOnDownbeat = true` | recipe JSON / NDK C++ (`dj_fx.h` BeatGate/roll path, `native/loopmix/dj_fx.h`) | replaces 4→2→1→½ schedule |
| Filter sweep too dull/sharp | `SweepFxParams.startHz = 20`, `.endHz = 1500`, `.qStart = 0.707`, `.qEnd = 1.6`, `.bars = 8` (recipe overrides per effect) | recipe JSON; `dj_fx.h` `KIND_FILTER_RES` | keep Q ceiling — no self-oscillation spike > +6 dB |
| Flanger too strong / slow jet | `FlangerFxParams.lfoCyclesPerBars = 2.0`, `.depthSec = 0.003`, `.feedback = 0.64`, `.wetPeak = 0.6`, `.stereoOffsetDeg = 90` | recipe JSON / `dj_fx.h` Flanger | |
| Phaser too thick | `PhaserFxParams.stages = 6`, `.rateBeats = 4.0`, `.depth = 0.7`, `.feedback = 0.6`, `.wetPeak = 0.7` | recipe JSON / `dj_fx.h` Phaser | |
| Riser sounds like hiss | `RiserFxParams` — 3 layers: `noiseSweepStartHz = 300` → `noiseSweepEndHz = 8000`, `noiseLevelPeak = 0.7`, `uplifterOctaves = 1.0`, `includeSnareRoll = true`, `reverseReverbTailBeats = 1.0` | recipe JSON / `dj_fx.h` Riser | |
| Impact too loud | `ImpactFxParams.peakDbfs = −6.0`, `.lowThumpHz = 55` | recipe JSON / `dj_fx.h` | |
| Brake / tape-stop feel | `BrakeFxParams` (`VINYL_BRAKE` rate `exp(−k·t)` to 0.05, LPF 18 kHz → 600–800 Hz, 10 ms end fade), `TapeStopFxParams.wowRateHz = 5.0` | recipe JSON / `dj_fx.h` Brake | |
| Spinback too short | `SpinbackFxParams.lengthSec = 0.25`, `.peakReverseSpeed = 3.0`, `.silenceGapBeats = 1.0` | recipe JSON / `dj_fx.h` | |
| Echo too washy | `EchoFxParams.delayBeats = 0.5`, `.feedback = 0.6`, `.wet = 0.55`, wet `wetHpfHz = 250` / `wetLpfHz = 6000`, `pingPong = true` | recipe JSON / `dj_fx.h` | |
| Gate rhythm wrong | `GateFxParams.divisionStepsPerBeat = 4` (1/16), `.swing = 0`, `.depthPeak = 1.0`, `.rampBars = 2.0` | recipe JSON / `dj_fx.h` BeatGate | |
| Reverb wash too big | `ReverbFxParams.roomSize = 2.2`, `.damping = 0.55`, `.wet = 0.35` | recipe JSON / NDK C++ | |
| Pump depth wrong | `PumpFxParams.beatSec`, depth (beat-locked sidechain) | recipe JSON / `dj_fx.h` `KIND_PUMP_DEPTH = 17` | |
| NPU slower than CPU / parity off | backend selection: `MIN_AGREEMENT = 0.95` within ±1 frame; switch to CPU — see [NPU.md](NPU.md) | `app/src/main/java/com/music/bitchord/playback/djmix/npu/NpuSelfTestRunner.kt` | never overrule CPU fallback |
| Stems too slow | `RecipeRequirementsV2.requiredStems` (separate only what the recipe needs); no-stem variant at commit miss | recipe JSON | do not move the transition for stems |

## Where each knob family lives

- Recipe-level JSON: `djmix-core/src/commonMain/resources/recipes/*.json` — [RECIPES.md](RECIPES.md).
- Planner scoring & placement: `djmix-core/.../planner/PairPlanner.kt`, `PlacementEvaluator.kt`, `CandidateGenerator.kt`.
- State machine timing: `djmix-core/.../machine/MixMachineConfig.kt` — [MIXMACHINE.md](MIXMACHINE.md).
- DJ level & engine version: `djmix-core/.../contracts/EngineVersion.kt`.
- Flags / kill switches: `app/.../djmix/diagnostics/DjmixFlags.kt` — [RELEASE.md](RELEASE.md).
- Native FX DSP: `native/loopmix/dj_fx.h` (envelope ids 0–19 match `contracts/TransitionScriptV1.kt`).
- NPU: `app/.../djmix/npu/*`, `npu-addon/v73` — [NPU.md](NPU.md).
