# BitChord Mix — current state, TODO, and what still needs to be done

_Last updated: 2026-11-22, during the vc92 cycle. Single source of truth for a fresh session._

## 0. Where the code lives (do not edit locally)

| Thing | Value |
|---|---|
| Repo | `dvb:/home/boxes/bitchord-mix` (project `8e73e879-1a8d-4fa7-b234-5749911cea6c`, target `c7d205b5-f446-44fc-b465-46e39f8836bb`) |
| Remote | `github.com/survey-sudo/bitchord-mix.git`, `gh` authed as `survey-sudo` (`workflow` scope) |
| Branch | `vc92-djmix50` (branched from `vc91-djmix49 @ 23adab5`) |
| vc91 HEAD | `23adab5` — shipped, installed, verified |
| vc92 state | **uncommitted working tree**: 16 files changed, ~+1381/−326, plus 5 new files |
| Build/sign | Gradle signs from `keystore.properties` + `bitchord-loopfix.jks` in repo root (both gitignored, 0600) |
| Release build env | `GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.daemon=false"` |
| Device | POCO F6 `1439ebea` (SM8635 / Hexagon V73), currently **not connected** (`adb devices` empty) |
| Telemetry path | `/sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl` |
| Local evidence | `/home/user/Bitchord/evidence/vc91-djmix49/` |
| Local tools | `/home/user/Bitchord/tools/` (`automix-telemetry-diff.py`, `vc91-benchmark-dataviz.py`, `vc91-device-verify.sh`, `vc92-device-verify.sh`) |
| vc91 APK copy | `/home/user/Bitchord/apk/bitchord-2.2.3-djmix49-vc91-arm64-v8a.apk` (sha256 `0ed12049…`, cert `20c66ad6…`) |

`dvb` capacity: 4 vCPU, 7.8 GiB RAM, 92 GB free, CMake 3.22.1, NDK 27.2.12479018, SDK at `/home/boxes/android-sdk`. No QAIRT, no Python `onnx`/`onnxruntime` installed.

`dvb` Gradle flakes (`Unexpected type tag 22/72`, `MessageHub ConnectException`, `PresenceTest` MockWebServer pollution) are environmental localhost-socket interference; per-class reruns pass.

---

## 1. What shipped: vc91 / `2.2.3-djmix49` (versionCode 91)

Landed in `ebf18d4` + `23adab5`:

- **Analysis reactivity**: `analysisUpdates` SharedFlow, reactive replan, `planGeneration`; kills the 1-hour-stale-plan class.
- **Tiered fallback ladder** (6 rungs): `decline-holds-no-overlap` → `fallback-beatmix` → `safety-crossfade` → `fallback-muffle` → `fallback-echo` → `tiered-fallback-exhausted`.
- **`LocalBlendAdmission`** gate: `rate < 1.0` only when `nativeRamped`.
- **C1 DSP smoothing**: raised-cosine muffle/bridge envelopes, `releaseEcho()`, TPT bypass integrator clear.
- **NPU prep**: ORT CPU arena/memPattern, `InferenceProvider` enum, `htp_arch=73`, `libcdsprpc.so required=false`, `npu-addon/export_qdq_beat.py` scaffold. `QNN_ENABLED = false`.
- Signed APK: 67,490,718 B, sha256 `0ed12049c1ecc97608450f09667f842708338434735934cfd74d6b70540bec3c`, `zipalign -c -P 16` OK, `audit-apk.sh` PASSED (1× `libonnxruntime.so`, 0 QNN `.so`, 7× `libbitchord_*.so`).

### Live vc91 evidence (POCO F6 `1439ebea`, 681 events)

| Metric | vc90 baseline | vc91 live | Verdict |
|---|---|---|---|
| Executed transitions (`mix.fire / 100 dumps`) | 88 (35.1) | 19 (38.8) | **+10.5 %, met** |
| Fired `NATURAL` | 0/88 | **0/19** | met |
| `planAgeMs > 1 h` | 9 (max 219,343,912 ms) | **0** (max 49,233 ms) | **met** |
| `AudioTrack` underruns during analysis | — | **0** (`m:0 s:0 k:0 z:0`) | met |
| `handoff_pcm_before_protected_event` | 44 att / 87 ev | 8 att / 16 ev | **not met** — Bug 1 |
| `emergency-retire-guard` | 7 | 4 | **not met** — Bug 2 |
| Stale `mix.arm.death` in IDLE | — | 2 | **not met** — Bug 3 |

**Metric correction (important):** the old “NATURAL 77.7 % → 49.0 %” headline was an artefact. Every `technique:"natural"` plan row is `executionScope:"normal-queue-advance-no-overlap"` — a plain track-end advance, not a transition (113 of vc90's 327 “accepted plans”, 21 of vc91's 53). Excluding them, final-verdict `NATURAL` over real transitions is **0 %** in both builds (vc90 0/162, vc91 0/23). The authoritative score is `mix.fire`: `0/88 → 0/19`. Both tools now implement this; `docs/RELEASE_VC91_DJMIX49.md` is corrected.

---

## 2. vc92 / `2.2.3-djmix50` — implemented on `dvb`, **uncommitted and not yet validated**

All edits exist in the working tree of `vc92-djmix50`. Nothing is committed, nothing built, no device run.

### Bug 1 — `handoff_pcm_before_protected_event` (8/8 on `native-bridge / Natural handoff`)

Root cause: two independent shortfalls, both fail-closed.
- Container `outDuration` overshoots real decoded Opus/AAC EOF by 6–60 ms (`159.581 s` vs `159.5745 s`).
- `c2.android.opus.decoder` under `SEEK_TO_PREVIOUS_SYNC` can start late even mid-track (`requestedInFrom=1.81 → decodedInFrom=1.821`).

Implemented:
1. **Plan-time EOF clamp** — `LoopMix.echoPlan` natural branch:
   `naturalCut = minOf(outDuration, effectiveEnd, maxOf(earliestOutSec + bar, outDuration - 0.06))`,
   still honouring `minCut = maxOf(earliestOutSec + bar, effectiveEnd - 4 * bar)`.
2. **Build-time bounded tolerance** — `LoopMixSession.coversHandoffPcm` / `coversPhysical` gain `endToleranceSec` applied **only to the end check**; `build()` passes `0.08` only for a natural handoff whose protected cut is within 0.12 s of container duration; measured Echo stays `0.0`.
3. **Late-start zero pad** — `TrackAnalyzer.padLateStart(...)`: if `0 < actualStart − requestedStart ≤ 80 ms`, prepend zeros and re-anchor; later starts fail closed.

Files: `shared/.../smart/LoopMix.kt`, `app/.../loopmix/LoopMixSession.kt`, `app/.../smart/TrackAnalyzer.kt`, new `app/src/test/.../loopmix/LoopMixSessionCoversHandoffPcmTest.kt`.

### Bug 2 — `loop-cleanup-incoming-unavailable` / `loop-cleanup-adoption-refused` → `emergency-retire-guard`

Implemented in `CrossfadeController.finishLoop` / `loopBail`:
- Cleanliness now decided by the **surviving deck** (`survivor.isPlaying` + `STATE_READY`/`STATE_BUFFERING`, `exitGateViolations()`, `DELEGATE_FULLY_CONSUMED`), never by the retired outgoing player's `isPlaying`.
- Healthy handoff with a missing incoming clock (`healthyHandoffMissingClock`) completes normally instead of bailing.
- `!handedOff` routes cleanly to `loopBail`; `loopBail` mutes/pauses `into` whenever `!wasHandedOff` (fixes `tookOver && !handedOff` leaving the spare audible).
- Retiring outgoing deck muted before `finish()` **only** when `wasHandedOff && tookOver && violations == 0 && DELEGATE_FULLY_CONSUMED`. Every other bail keeps `RetireGuard` intact.

Files: `app/.../CrossfadeController.kt`, new `app/src/test/.../Vc92ControllerFixesTest.kt`.

**Still missing (known gap):** pre-splice incoming identity re-validation (`out.next.mediaId == planned incoming.mediaId` immediately before the splice). Without it, a queue edit inside the arm window can still render a loop that can never be adopted. This is a small `CrossfadeController` edit that must wait until the controller lane releases the file.

### Bug 3 — stale `mix.arm.death`

Terminal IDLE block of `finish()` now clears `shownCountdownKey`, `shownLoopStartMs`, `watchdogFired`. Early returns (`TAIL`, `finishLoop`) keep their state. Covered by `Vc92ControllerFixesTest`.

### Bug 4 — honest NATURAL metric (done, local tools)

`tools/automix-telemetry-diff.py` and `tools/vc91-benchmark-dataviz.py`:
- `is_queue_advance()` excludes `executionScope:"normal-queue-advance-no-overlap"` / `technique:"natural"` from every plan-based share;
- headline is `mix.fire` `NATURAL`;
- new metric “natural handoff chosen then aborted”;
- dataviz regenerated (112,605 B, `node --check` + DOM-stub render pass) and `benchmark-data.json` vc90-live recomputed (214 accepted transitions, 162 final, 113 queue advances).

### Feature 5 — gated stem-duck (`LoopMix.Check.MASK_DUCKED`)

Second pass after the strict clean pass (`MAX_SUNG_SHARE = 0.03`):
- lyric-certain vetoes stay absolute; requires measured `vocalWindows`;
- admits `share 0.03..0.28`, `peak < 0.35`, `longestRunSec ≤ 0.6`, finite mean;
- ranks by `(share, peak, longestRunSec, seamDelta)`;
- `LoopMix.plan` rejects a ducked side if the other side's vocal `> 0.10` or either loop window `> 21.5 s`;
- plan carries `stemDuckRequired` + `duckFloor`;
- `LoopMixSession.build()` refuses `stem_duck_unavailable` when no separator can answer (fail closed; `maskRejects` never widened).

Files: `LoopMix.kt`, `LoopMixSession.kt`, `LoopMixTest.kt` (6 new tests), `CrossfadeController.kt` label branch.

### Feature 6 — telemetry-only blend credit

`LocalBlendAdmission.Verdict.plannedVocalAttenuationDb` (null / `-6.0` / `0.0`) is logged as context and **never** gates admission. `MAX_SOURCE_COLLISION = 0.18` unchanged. Credit is deferred until real gain envelopes are plumbable.

### Feature 7 — musical `FxPolicy` (implemented, default OFF)

`FxPolicy.selectMusical(...)`: `buildup → LOOP_ROLL` (rising energy) else `FLANGER`; `cleanSpace → ECHO` if tempo gap `> 8 %` else `REVERB`; `vocalClash ≥ 0.18 → ECHO` when rhythmic; all `NONE` guards preserved (`!measured`, speech, drift, non-finite or `> 0.20` clash). `MUSICAL_FX_POLICY_ENABLED = false` keeps the shipped lottery byte-identical. Flip only after validated telemetry + a listening A/B.

### Wave 2 skeleton — `MixTemplateIR`

Pure KMP data class (`templateId`, `rung`, out/in windows, overlap, fold, `incomingRate`, `stemDuckRequired`, `djEffect`, `fallbackPolicy`) + `LoopMix.Plan.toMixTemplateIR()` / `TransitionPlan.toMixTemplateIR()` + round-trip tests. **No production consumer reads it yet.**

### NPU / acceleration

- `NpuSession.openXnnpack(...)` wired to `InferenceProvider.CPU_XNNPACK` with silent CPU fallback; `QNN_ENABLED` stays `false`.
- `NpuSelfTest` benchmark enabled **for the measurement build only**: 3 warm-up + 20 timed iterations, non-zero deterministic input, median latency, `maxLogitDelta ≤ 1e-3`, logs under `BitChordNPU`.
- XNNPACK EP **confirmed present** in the bundled `onnxruntime-android:1.31.0` AAR (`XnnpackExecutionProvider` symbols in `libonnxruntime.so`), so the benchmark is real.
- `npu-addon/export_qdq_beat.py` rewritten: full-graph static QDQ (`QUInt16` activations / `QUInt8` per-channel weights) at fixed 6 s / 10 s shapes, deterministic calibration reader, explicit diagnostic + non-zero exit when handed a dynamic-INT8 model (no FP32 baseline), F1 parity gate `≤ 2 points`.
- `.github/workflows/npu-addon.yml` skeleton: `workflow_dispatch` only, QAIRT `2.51.0.260929`, ORT pin `402979a…`, `--use_qnn static_lib`, no `--include_ops_by_config`.

---

## 3. TODO — ordered, with gates

### P0 — finish vc92 (must all pass before ship)

1. **Compile + test the merged tree** on `dvb` (lanes are mid-validation; `LoopMixSession.kt` had a forward-reference to `effectivePlan` that the session lane is moving below its declaration).
   ```
   GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.daemon=false" \
   ./gradlew :shared:jvmTest --tests "*playback*" :app:testProdDebugUnitTest \
     --max-workers=2 --no-daemon --console=plain
   ```
   Expected: ~522 shared + ~514 app, 0 failed; new tests `LoopMixSessionCoversHandoffPcmTest`, `Vc92ControllerFixesTest`, `MixTemplateIRTest`, `FxPolicyContractTest`, `LoopMixTest` green.
2. **Native probes**: `vocal_spectrogram_test`, `loop_mix_renderer_ab_main --selftest`, `--lp-bypass-probe` (expect ~19.8 kHz, `maxStep ≤ 0.01`), `--loop-domain-probe` (63 pairs, minRMS ≥ 0.05).
3. **Pre-splice identity check** in `CrossfadeController` (the known gap in §2/Bug 2).
4. **Measurement build**: build vc92 with `BENCHMARK_CPU_VS_XNNPACK_ENABLED = true`, `QNN_ENABLED = false`; install on `1439ebea`; capture `logcat -s BitChordNPU` for `cpuMedianMs`, `xnnMedianMs`, `speedup`, `maxLogitDelta`, `parityOk`, plus 0 underruns during analysis.
   - Pass bar: `maxLogitDelta < 1e-3`, median ≥ 15 % faster **and** Pass-1 ≥ 1.2×, 0 underruns.
   - If pass, consider `CPU_XNNPACK` as the default provider in a later build; if fail, keep `CPU_DEFAULT` and record the delta.
5. **Flip `BENCHMARK_CPU_VS_XNNPACK_ENABLED = false`** and build the **final ship APK** (two-artifact rule: measurement build ≠ ship build).
6. **Sign + audit**: `tools/vc92-build.sh` (rename from `vc91-build.sh`; content already bumped), `zipalign -c -P 16`, `tools/audit-apk.sh`, cert SHA-256 unchanged (`20c66ad6…`), pull APK to `/home/user/Bitchord/apk/bitchord-2.2.3-djmix50-vc92-arm64-v8a.apk`.
7. **Device verification** (needs the phone online):
   - Re-enable wireless debugging: phone ↔ PC on the same Wi-Fi, Developer options → Wireless debugging → pair (`adb pair <ip:port> <code>`) then `adb connect <ip:port>`; historical address `192.168.100.218:40601`.
   - `bash tools/vc92-device-verify.sh <apk>` (archives old telemetry, installs, pulls, diffs against vc91).
   - Gates: `handoff_pcm` attempts `== 0`; 0 `emergency-retire-guard` on native-bridge; 0 `mix.exit.violated`; 0 stale `mix.arm.death` in IDLE; 0 underruns; `planAgeMs > 1 h == 0`; fired `NATURAL < 15 %`; fire rate not below 38.8/100 dumps.
8. **Commit + tag** `vc92-djmix50`; update `docs/RELEASE_VC92_DJMIX50.md` with artifact hash, cert, live table; regenerate dataviz with the vc92 candidate.

### P1 — Wave 2 (NPU / QNN), gated

1. **QDQ parity gate first** (blocking): produce an FP32 (or reconstructable) beat model, run the rewritten `export_qdq_beat.py`, require downbeat/beat F1 within 2 points of the dynamic-INT8 baseline on CPU ORT. Prior 30 s QDQ collapsed F1 to ~32 %; do not repeat that shape.
2. Only if parity passes: install QAIRT `2.51.0.260929` on `dvb` (92 GB free), extract the subset (`libQnnHtp/System/HtpPrepare.so`, V73/V75 stubs, `hexagon-v73/v75` skels, `include/QNN/`), clone ORT `1.31.0` at `402979a`, build with `--use_qnn static_lib`, `htp_arch=73`, **no** `--include_ops_by_config`.
3. **Packaging**: QNN artifacts go in a **separate signed add-on APK** (`useLegacyPackaging=true`, same cert), never the main 67 MB APK. `NpuNative.initBundledQnn()` tries the add-on `nativeLibraryDir` first, falls back to CPU. `QNN_ENABLED=false` by default; settings toggle off by default.
4. **Proof before enablement**: `QNN_DIAGNOSTIC_ENABLED=true`, `disable_cpu_ep_fallback=1` + ORT profiling must show the whole BeatThis graph on QNN (zero CPU nodes), on-device F1 parity holds, HTP latency beats XNNPACK, 0 underruns.
5. UMX BiLSTM stays on CPU permanently (does not partition to HTP). QNN GPU FP16 is a stretch goal only.

### P2 — deferred with reasons (do not silently enable)

| Item | Status | Reason |
|---|---|---|
| Classic `PitchStretch` tempo | deferred, stays `1f` | `pitch_stretch.cpp` is pitch-only transpose `1/tempo`, clamp 0.94–1.09, `create/process` never called; measured classic rate ~1.0045 (0.45 %, inaudible); enabling risks decoder starvation underruns and re-opens the “stutter across B's intro” complaint. |
| `MixTemplateIR` cutover | skeleton only | Highest regression risk, zero device gain until every planner emits it; land when a feature actually needs it. |
| `LocalBlendAdmission` post-carve credit | telemetry only | Carve depth is template-dependent, not a constant; crediting an assumed fader law is how dual-vocal karaoke gets admitted. Credit only with a measured attenuation envelope, cap −12 dB, floor `effective ≥ raw × 0.5`. |
| Musical `FxPolicy` default | off | Needs validated Bug-4 telemetry + blind listening A/B (D6 currently blocked). Code is ready behind `MUSICAL_FX_POLICY_ENABLED`. |
| Stem-duck crown admission for plans without a real stem path | unsupported | `TrackAnalysis` has no stem field; `LoopMixSession` fails closed instead. |

### P3 — product-quality backlog (the “Apple Music class” gap, honest view)

Already present: beat/downbeat grid, phrase boundaries, lyrics + vocal-mask analysis, crown loops, native tempo-ramped bridges, tiered fallbacks, musical FX vocabulary, C1-smoothed DSP.

Still missing for true DJ-grade mixing:
- **Song-structure segmentation beyond phrases** (intro/build/drop/break/outro labels) feeding transition choice, not just loop admission.
- **Tempo interpolation on the classic path** (safe WSOLA/time-stretch) — currently only native bridges ramp tempo.
- **Per-pair learned preferences** (which rung/FX/loop worked for this pair) — no memory today.
- **Key-aware harmonic mixing** exists as Camelot gating; no measured key-shift/transpose option.
- **Listening A/B harness** (objective loudness/spectral continuity + human blind test) so DSP changes stop shipping on unit tests alone.

---

## 4. Verification gates cheat-sheet

| Gate | Command / evidence | Pass condition |
|---|---|---|
| Shared unit | `:shared:jvmTest --tests "*playback*"` | 0 failed |
| App unit | `:app:testProdDebugUnitTest` | 0 failed |
| Native | `--lp-bypass-probe`, `--loop-domain-probe`, `vocal_spectrogram_test` | 19.8 kHz / `maxStep ≤ 0.01`; 63 pairs minRMS ≥ 0.05; 0 sanitizer errors |
| APK integrity | `zipalign -c -P 16`, `tools/audit-apk.sh` | PASS, 1× `libonnxruntime.so`, 0 QNN `.so`, cert `20c66ad6…` |
| Telemetry | `tools/automix-telemetry-diff.py` | `handoff_pcm == 0`, fired `NATURAL < 15 %`, fire rate ≥ vc91, `planAgeMs > 1 h == 0`, 0 retire-guard |
| XNNPACK | `logcat -s BitChordNPU` | `maxLogitDelta < 1e-3`, median ≥ 15 % faster, Pass-1 ≥ 1.2×, 0 underruns |
| QDQ | `export_qdq_beat.py` | F1 delta ≤ 2 points vs dynamic INT8 |

## 5. Open risks / unknowns

- Decoded-EOF shortfall distribution for the user's actual Opus/AAC files: 60 ms clamp + 80 ms tolerance assumes ≤ 60 ms; log `decodedOutTo vs requiredOutTo` to confirm. Anything worse still fails closed (correct, but the abort class would persist).
- `SEEK_TO_PREVIOUS_SYNC` on a one-header stream can decode from 0 (seconds of CPU); the 80 ms pad does not bound that. Watch `deckTiming` on the first device run.
- `tookOver && !handedOff` semantics: muting `into` prevents an unmuted spare but could click if the in-sink is genuinely audible; the pre-splice identity check is the real fix, and it is not yet in the tree.
- XNNPACK parity measured in isolation may not hold under concurrent audio load (decoder starvation) — needs the paired latency/underrun capture from the device run.
- The measurement build ships a benchmark flag on; forgetting to flip it back would run 46 inferences on every user's launch.
- `adb` is currently offline; no device verification is possible until wireless debugging is re-enabled.
