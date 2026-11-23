# BitChord Handover — vc72, D1/D3/D4/Loop Fixes + First Key-Accuracy Cycle

---

# 0. What this document is

A cold-start brief for an orchestrator + worker fleet picking up BitChord after vc72. It assumes no memory of prior sessions. Read it fully before touching the tree — most of the cost of the last five versions came from agents skipping the constraints in §2.

**One-line state: vc72 is the first build since vc68 to turn key accuracy into a measured quantity instead of a guess. Its provenance string is `full-ort-1.31.0-cpu-keyfix1` for a reason: this is the keyfix1 cycle. Nothing on this build has been heard by the user yet — §11 lists every track pair that must be played before a single sentence about audio quality is allowed.**

---

# 1. Status

| Item | Value |
|---|---|
| Version | **vc72 / `2.2.3-djmix30`** |
| APK | `app-prod-arm64-v8a-release.apk`, **67,425,238 B** |
| SHA-256 | `414485c20fc3ff9a309f8c5184378772d8bc5b2535c9a0a2609fe1d39c2d93fa` |
| Cert (oldkey) | `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` |
| `libonnxruntime.so` | 34,208,672 B, count **1** — full Maven AAR 1.31.0 |
| `libbitchord_analysis.so` | 486,288 B |
| QNN libs in APK | **zero** `.so` (only `assets/licenses/Qualcomm-AI-Runtime-QNN-NOTICE.txt`) |
| Build | **`BUILD SUCCESSFUL in 43m 59s`, exit `0`** — full log in `vc72-device/build-vc72.log` (56,593 B) |
| Device | Poco F6, SM8635, serial `1439ebea` — **NOT attached at handover time** |
| Package | `com.music.bitchord.loopfix` |
| Launcher | `com.music.bitchord.loopfix/com.music.bitchord.LauncherDefault` ← **not** `.MainActivity` |
| Analysis | **Alive, device-proven at vc71**; vc72 on-device acceptance **PENDING USB RECONNECT** |
| User verdict | **"not bad"** on vc71 — **vc72 has no verdict yet** |
| Tests | `:shared:jvmTest` **435/0/0/0** · `:app:testProdDebugUnitTest` **1347/0/0/1 skip** (fresh XML, re-measured) |
| Git | `HEAD=dc78e0d449d7bdf51e821f8e8c1de223bff8bf5a`, no commits, all work staged/unstaged |

**Audio is entirely unverified on vc72.** The user's verdict on vc71 ("not bad") does not transfer. Every audio & on-device item is marked **UNVERIFIED UNTIL USER EARS HEAR IT** in §11.

---

# 2. Environment — hard constraints, non-negotiable

## 2.1 Tree location

```
bk-android:/data/workspace/bitchord-task2/work/BitChord
```

Access requires `instabox resume bk-android` first, then `instabox exec bk-android -- <cmd>`. The MCP default box (`cb-fcfcd7a79078`, "main") holds a **different, stale** tree at `/workspace/BitChord` with **no NPU code at all**. Two workers have already been briefly misled by it, and the Phase 3 operator at least found that `bk-android` itself is not resolvable by raw `ssh` — *the only correct entrypoint is `instabox exec bk-android --`*. Confirm the path before any diagnosis.

## 2.2 Disk — the recurring hard stop

`/data` is **fixed at 20 GB**. `instabox resize bk-android --disk 22` returns `spec_not_allowed: this box's disk size is fixed at 20 GB` (free plan). There is no grow path. A release assemble needs **~3.0–3.2 GB free**; the box typically sits at 2.4–4.0 GB.

Free space safely by deleting **only** regenerable artifacts:

```bash
rm -rf app/build/intermediates app/build/outputs app/build/tmp app/build/kotlin app/.cxx
rm -rf shared/build/classes shared/build/intermediates/tmp/kotlin/libs
rm -rf ~/.cache/bitchord-ort-qnn        # 1.0 GB, stale from-source ORT tree, unreferenced
rm -f  /tmp/build-*.log
```

**Never delete:** `npu-addon/prebuilt/` (137 MB, sha `e39cf714…`), `baseline-vc69.apk` (sha `abb336da…`), sources, Gradle caches.

**No `gradle clean`** unless genuinely forced.

## 2.3 Concurrency — this has broken three builds

**Exactly one Gradle invocation at a time on `bk-android`.** No exceptions, no "it's only a test task."

Observed failures from parallelism:
- Sibling worker deleted `app/build/intermediates` mid-build → `annotation_processor_list … doesn't exist`
- OOM kill (PID 104496, 4.3 GB RSS) during concurrent test+build contention
- Kotlin-metadata errors from racing daemons
- A build that vanished entirely — no exit file, no APK, no OOM, because a sibling cleaned `/tmp`

If you run orchestrator + workers, **serialise every Gradle call through a single worker** and have that worker hold a lock file.

## 2.4 Build invocation

```bash
nohup ./gradlew :app:assembleProdRelease --offline --no-daemon -Pbitchord.bundleWear=false \
  > /tmp/build-<name>.log 2>&1; echo $? > /tmp/build-<name>.exit
```

`instabox exec` has a **60 s timeout that kills foreground builds**. Builds take 2 min (warm) to 40 min (cold). Always `nohup` + poll both the log and the `.exit` file.

vc72 ran the same way: `BUILD SUCCESSFUL in 43m 59s`, exit `0` (`/tmp/build-vc72.log` + `/tmp/build-vc72.exit` on bk-android; log pulled to `vc72-device/build-vc72.log`).

## 2.5 Git invariants — RE-PINNED

Re-measured Phase 3 on the **real tree** (`bk-android:/data/workspace/bitchord-task2/work/BitChord`):

- `HEAD = dc78e0d449d7bdf51e821f8e8c1de223bff8bf5a` ✅
- `git diff --cached | wc -l` = **6085** ✅
- `git diff --cached --shortstat` = **45 files changed, 4547 insertions(+), 179 deletions(-)** = **4726** ✅
- **The "6085 vs 4726" pseudo-discrepancy is explained once and for all:** 6085 counts *unified-diff lines* (context lines included); 4726 counts *changed lines only* (4547 additions + 179 deletions). Both figures are correct; they count different things. Re-pin at the start of the next cycle, do not re-litigate.
- `git status --porcelain | wc -l` = **123** ✅
- **No commit, no reset, no checkout.** All work lives in the worktree/index.
- New files: **`git add -N` only.**
- Version lives in root `build.gradle.kts`, **lines 44–45**: `72` / `"2.2.3-djmix30"`

## 2.6 Device and ADB

- ADB: `/home/hfzhkn/.local/bin/adb`
- Device: `adb -s 1439ebea`
- The device **dropped off USB once** mid-session for ~6 min. If a command hangs, run `adb devices` before assuming the command was wrong.
- Once, the install path changed between two checks with **no worker responsible** (`~~ug8azz…` → `~~bmTm…`, 13:12 → 13:28). Either the user was testing or another actor touched the device. **Do not assume the running process is yours** — check `dumpsys package` versionCode before trusting a log.
- **At the time of this handover the device was NOT attached** (`adb devices -l` empty, no matching vendor ids in `lsusb`). On-device vc72 acceptance is therefore **PENDING** — the complete ready-to-run script is §12 below and in `vc72-device/vc72-report.md` §5.

## 2.7 Why this project has no license constraints

Private personal build. The owner has declared licenses not a constraint; Orchard (PolyForm Shield 1.0.0, non-compete) may be referenced and adapted.

---

# 3. The one acceptance test that matters

**A single-item VIEW play never triggers analysis.** `requestAnalysisAround` returns early on `INDEX_UNSET`. Three separate verification attempts failed for this reason alone and were misread as "analysis is broken" or, worse, as success.

The working trigger is a **multi-item radio queue**:

```bash
adb -s 1439ebea logcat -c
adb -s 1439ebea shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH --es query "Keyveatz"
sleep 45
adb -s 1439ebea logcat -d -s BitChordTrackAnalyzer:* BitChordNPU:* onnxruntime:* *:S
adb -s 1439ebea logcat -d | grep -iE 'Kernel not found|model unavailable|MatMulIntegerToFloat|DynamicQuantizeMatMul|mel |infer|vocal mask|bars='
adb -s 1439ebea logcat -b crash -d
```

Run this against vc72 after USB reconnect (script + `SINCE_TS` + telemetry-analysis steps in `vc72-report.md` §5).

## Positive evidence required

Absence of errors is **not** proof. Require lines that can only exist if inference ran:

```
BitChordNPU disabled (QNN_ENABLED=false): CPU execution provider
Discarding unreadable analysis … schema 4        ← vc72 schema 5 forces one pass; BENIGN
mel 144ms (1113 frames) infer 2788ms
BitChordPhrase: structMs=1549 structWallMs=1549 bars=71 partial=false duration=138.0
BitChordTrackAnalyzer: vocal mask 958 frames in 1040ms
```

## Regression signatures — any of these means you broke it

| Signature | Meaning |
|---|---|
| `Failed to find kernel for com.microsoft.MatMulIntegerToFloat` | reduced ORT back in the APK |
| `Failed to find kernel for DynamicQuantizeMatMul` | vocal model dead |
| `Beat model unavailable; falling back to no grid` | no beat grid |
| `Vocal model unavailable; no mask will be produced` | no vocal mask |
| `OrtException` / `ORT_NOT_IMPLEMENTED` | ORT broken |
| `Discarding unreadable analysis … schema 4` | **expected once** after the schema bump — benign |
| `QNN_DEVICE_ERROR_INVALID_CONFIG` | someone re-enabled QNN without fixing it |

## Known logging gap

`bpm=` / `grid` `Log.d` lines are **proguard-stripped in release**. Do not expect them, do not conclude failure from their absence. Numeric proof lives in telemetry:

```
/sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl
```

Post-vc72, pull it and run `docs/tools/analyze_telemetry.py --since-ts $SINCE_TS` to measure `*A` vs `*B` keys, `steps=`, and `drifting beat grid` (see §10, Cycle 1).

---

# 4. What is fixed and proven in vc72 — code-complete, device-pending

| # | Fix | Evidence / location |
|---|---|---|
| 1 | **Full ORT preserved.** 34,208,672 B, count 1; zero QNN `.so`; same posture as vc71 | APK verified Phase 3 (hash + zip inventory) |
| 2 | **D4 — C++ keyfinder rewritten for major/minor disambiguation.** Peak-picked chroma (only local peaks ≥2× the band, Orchard-style); **zero-mean Pearson correlation** against Krumhansl-Kessler profiles across **all 24 keys**; tonal gate (contrast <1.5 cannot produce high confidence); per-region head/tail chromas via the same `detect_key()`. `keyConfidence`, `keyHeadConfidence`, `keyTailConfidence` now meaningful | `native/analyzer/audio_analysis.cpp:332-336` (regions), `:385` (peak-picking), `:462-507` (Pearson + 24 keys), `:513-546` (gate + head/tail) |
| 3 | **Cache provenance bump.** `SCHEMA_VERSION` 4→**5**, `PIPELINE_PROVENANCE = "full-ort-1.31.0-cpu-keyfix1"`; `load()` enforces both | `shared/.../AnalysisStore.kt:377`, `:391` (enforced `:97-98`) |
| 4 | **D3a/D3b — key-confidence parity consumer-side.** `exitKeyConfidence`/`entryKeyConfidence` return regional (tail/head) confidence when regional key is trusted (≥0.25), else global — so regional decisions inherit the same confidence the gates compare against | `shared/.../TrackAnalysis.kt:260`, `:270`; consumed `TransitionMoves.kt:95`, `TransitionAlternatives.kt:109-110`, `TransitionPlanner.kt:381-382`, `LoopMix.kt:1209-1210`, `:1306-1307`, `:1374` |
| 5 | **D3b — `shouldRunPhrasePlannerForBridge`.** Phrase planner (M6, `nativeBridge`/`phrasePlanFor`) must never hijack a declined pair; declined pairs flow into `LoopMix.bridge` (`TransitionAlternatives`/Echo) | `app/.../CrossfadeController.kt:345` (definition), `:4086` (gate call); tests `Djmix27AppTest.kt:207`, `:211` |
| 6 | **D1 — `fittedBpm`/`fitBeatSegments` edge & jitter fix.** Edge margin `maxOf(0.75, period * 2.5)` so a 75.949 BPM grid (0.790 s/beat > 0.75 s old margin) is accepted; isolated onset-snap jitter (±35 ms `snapGrid` evidence tolerance up to 0.075 s) no longer breaks a segment at the 6% single-gap rule | `shared/.../HarmonicMix.kt:114` (`fitBeatSegments`), `:178` (`fittedBpm`) |
| 7 | **Loop arm-death truthful diagnostics.** `lateStepReason` reports the real millisecond overrun instead of a clamped 0 ms; horizon/epoch handling concentrated there | `shared/.../LoopMix.kt:370`, `:391`; regression tests `Djmix15RegressionTest.kt:41-76`, `DeckBudgetTest.kt:33-46`, `LoopMixTest.kt:460` |
| 8 | **D6 — telemetry correlation tool.** `analyze_telemetry.py --since-ts` reproduces D1/D3/D4/D5 breakdowns and the `steps=` audit from any telemetry pull | `/home/hfzhkn/Bitchord/docs/tools/analyze_telemetry.py` (sha256 `68bc5a82…7af8`) |
| 9 | **No regressions in gates/limits preserved from vc71.** Full ORT, `QNN_ENABLED=false`, v2 signing cert, `R128Limiter` still deleted, clash reorders rather than vetoes, no tempo rewrite for octave detection | tests 435/1347 fresh-green; dex audit §2 (report §3) |

Verified in the shipped dex: `full-ort-1.31.0-cpu-keyfix1`, `exitKeyConfidence`, `entryKeyConfidence`, `fittedBpm`, `BitChordNPU`, `DECLINE`, `keyClash`, `TransitionAlternatives`. (`fitBeatSegments` / `shouldRunPhrasePlannerForBridge` are internal symbols, absent as dex strings by design; both confirmed in source at the locations above.)

---

# 5. Open defects, ranked (post-vc72)

## D1 — RE-TEST: `drifting beat grid` should now stop at 75.949 BPM
`fittedBpm` previously returned `null` on `quality="full"` grids (see §12.7 H3 for the two compounded bugs). vc72 widens the edge margin and tolerates isolated snap jitter. **Unproven on device.** Test: pull fresh telemetry, grep `drifting beat grid` with `bpmA/B: 75.949`, `qualityA/B: full`; any residual firing on ≤80 BPM-class grids breaks the fix.

## D4 — RE-TEST: key system — the whole point of this cycle
The C++ keyfinder is rewritten (H1). **Unproven on device.** Test: pull fresh telemetry; count `keyA/B` suffixes post-vc72; every `*A` share should collapse from 100% (54/54 in vc71). Re-check the §11 listening pairs. If `*A` persists, the bug is no longer in chroma — audit `PITCH_CLASS` ordering / JNI string mapping next, not the matcher.

## D3 — RE-TEST: declined-pair routing with restored confidences
`keyConfidence` is no longer starved by the D4 bug, regional keys are no longer silently masked, and M6 can no longer hijack declined pairs. **Unproven on device.** Test: pull telemetry; count `decline:true` + `keyClash` + the `decline-to-mix` steps audit with `docs/tools/analyze_telemetry.py`; expect non-`dualvocal`-only carves and `steps=` values present (vc71 had 108/108 `steps=?`, all `dualvocal`-only, zero `keyClash`).

## D2 — Long-run stability still never tested
Nothing has been verified past ~7 minutes on any build. Full queue completion never confirmed. **Test:** queue a long radio station, run 60+ minutes, sample `dumpsys meminfo`, `logcat -b crash`, and telemetry line count every 10 min. No code changes unless something breaks.

## D5 — Octave detection still never fired on a real pair
`164 / 90 = 0.549` is 8.86% off 0.5 (within the 8%-tolerance detector's correct silence). **Test:** queue genuine double/half-time pairs. Unit-wired; no real triggering observed yet.

## D6 — DONE (code); correlation harness operational
`docs/tools/analyze_telemetry.py` exists and is used in §12 acceptance. Extend it only when the next cycle answers a question it can't.

## D7 — Possible concurrent actor on the device
See §2.6. Verify via `dumpsys package` versionCode before trusting any observation. (At handover there was no actor to watch — there was no device at all.)

---

# 6. NPU workstream — scoped, deliberately parked

Full plan: **`/home/hfzhkn/Bitchord/docs/NPU_QNN_IMPLEMENTATION_PLAN.md`** (453 lines). Read it; do not re-derive it. **Unchanged from vc71. Read the standing recommendation in HANDOVER_vc71.md §6.7 again: do not start NPU until the audio cycle is clean — and the audio cycle now includes re-proving D1/D3/D4 on device (§5).**

## 6.1 Honest benefit assessment

**NPU does not improve beat accuracy. It buys speed and coverage.**

Orchard's measured numbers (SM7450, real hardware):

| Metric | NPU vs CPU |
|---|---|
| Inference speed | 3.06–3.99× faster (6 s models), 3.1× (30 s) |
| Beat F1 | **−1.2 pp worse** |
| Downbeat F1 | **−5.4 pp worse** |
| 30 s models | **Catastrophic** — 56.59 vs 72.36, rejected outright |

What could improve: pre-arm window, native loop capture rate, queue lookahead, cold start.
What will not: beat accuracy (worse), vocal separation quality, key accuracy, render quality.

## 6.2 The blocker that may kill it outright

**HTP requires QDQ models. BitChord ships dynamic-INT8.** They likely will not dispatch on Hexagon at all. Orchard shipped separate `*_qnn.onnx` assets and **still reverted to CPU INT8 for production.** Budget new calibrated QDQ assets as an open-ended phase — this is the widest variance in the whole estimate.

## 6.3 Ranked fixes for `QNN_DEVICE_ERROR_INVALID_CONFIG`

BitChord passes **no** device configs (`soc_model` and `htp_arch` both unset), so **no provider option can be the trigger**. It is environmental. Cheapest first:

1. **`<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`** in `AndroidManifest.xml`. ORT issue #31353 documents this exact error with this exact fix. **One line, one build, one logcat.**
2. **Stop setting `ADSP_LIBRARY_PATH`** in `npu_jni.cpp` — ORT sets it itself only if unset, and QAIRT warns that an existing value "may cause the HTP backend to fail."
3. **`useLegacyPackaging = true`** so FastRPC can `fopen()` the skel.
4. **Pin `htp_arch` only** — `soc_model=68` if ever needed, **never `8635`**.
5. Audit Stub files present in `npu-addon/prebuilt`.
6. Bisect `htp_graph_finalization_optimization_mode`.

Confirmed: Orchard's provider options are **identical** to BitChord's except `disable_cpu_ep_fallback` (Orchard 1, BitChord 0). The difference was never configuration — it was **packaging**.

## 6.4 Integration design — non-negotiable

- **Separate add-on APK** (Orchard-style), **one HTP generation at a time, V73 first.** Never merged into the main APK. This is what makes the "broke analysis" failure structurally impossible rather than a thing to remember.
- Isolated runtime, dlopened by path. No shared global ORT symbol.
- Guards:
  - no `pickFirsts` for `libonnxruntime.so` — **CI grep**
  - exactly one `libonnxruntime.so` per APK — **CI `unzip -l | grep -c`**
  - `.so` inventory assertion
  - self-test-gated enablement
  - **CPU-works assertion in the same build**
- Acceptance needs **8 QNN proof lines**, including `libQnnHtpV73Skel.so` load, a profiling trace showing `QNNExecutionProvider`, and a successful `disable_cpu_ep_fallback=1` run — **plus** a CPU fallback assertion.

## 6.5 V73 vs V75 — still undecided

**No `HTP ARCH` line has ever been emitted**, because device creation fails before HTP graph finalization. Do not fabricate a choice. Settle it only when a session actually opens.

## 6.6 Effort

- **Phase 0–1** (CPU guard + restore, P0): **1.5 days**
- **Phase 2–5** (add-on to proven): **7–13 days**, widest variance in the QDQ asset phase

## 6.7 Standing recommendation

**Do not start NPU until D1–D4 are resolved on device.** NPU has never once improved a transition, and the last attempt cost the entire analysis engine. It is an accelerator for a working pipeline, not a fix for a broken one.

---

# 7. Guardrails — the traps that cost this project five versions

1. **Never swap `libonnxruntime.so` globally.** The `pickFirsts` + `jniLibs.srcDir` combination silently replaced the full AAR with a 13 MB reduced build and killed **both QNN and CPU analysis** for vc69, vc70, and vc70-npufix2. Every tempo, key, and transition decision in that span ran on **no beat grid and no vocal mask**. This is the single most expensive mistake of the project.
2. **Reduced ORT is not worth it.** 33 MB → 13 MB saved 20 MB on a 108 MB APK and broke everything. `create_reduced_build_config.py` parses **raw protobuf** and cannot see ops the optimizer introduces at load time (`MatMulIntegerToFloat`, `DynamicQuantizeMatMul`). If a reduced build is ever genuinely needed, the only correct path is `convert_onnx_models_to_ort` → `create_reduced_build_config.py -f ORT`. There is no `--include_all_operators` flag; **full is the default**. Recommendation: do not use a reduced build at all.
3. **Prove the layer underneath before building on top.** vc69 added NPU on top of a build-config change without verifying the swap preserved analysis. That is the root process failure — larger than any individual bug.
4. **Absence of errors is not success.** Require positive evidence: `mel … infer …`, `bars=N`, `vocal mask N frames`. Three rounds of "no error appeared" were all wrong, in both directions.
5. **Never claim audio quality without the user's ears.** Every audio claim this session is unverified until they listen. "Not bad" about vc71 is the only real verdict on record — and vc72 changes alter how every transition is planned.
6. **One Gradle invocation at a time.** §2.3.
7. **A single-item VIEW play never triggers analysis.** §3.
8. **`bpm=` lines do not exist in release builds.** Use telemetry.
9. **`R128Limiter` stays deleted.** Do not resurrect it.
10. **Check `adb devices` before debugging a hang.** At this handover the device was absent entirely.
11. **Check whether another actor touched the device** before trusting runtime state.
12. **Do not re-litigate settled decisions.** Full ORT restored, QNN disabled behind `QNN_ENABLED`, R128 deleted, separate add-on APK for NPU, clash reorders rather than vetoes, no tempo rewrite for octave detection. Each reopening costs a cycle.
13. **Do not touch `npu-addon/prebuilt/`** when freeing disk.
14. **Version discipline:** this build is `72 / 2.2.3-djmix30` specifically because vc72 re-proves the analysis pipeline's key, confidence, and grid decisions. Distinguish builds in telemetry by version, not by filename alone.

---

# 8. Version history — why each build exists

| Version | What happened |
|---|---|
| **vc68** | Worked. User's last good baseline. Task 2A `CountdownStabilizer`. Muffle return still snapped. |
| **vc69** | Reduced ORT replaced the full AAR. **Analysis died silently.** Demuffle abrupt, plan-abort filter snap. NPU inert. |
| **vc70** | Crashed on launch — `nativeLibraryDir` NPE pre-`attach()`. Once patched, opened but analysis still dead. Proguard deleting every log. |
| **vc70-npufix2** | NPE fixed, logs restored, **first specific NPU error ever surfaced**: `MatMulIntegerToFloat` missing. Still no analysis. |
| **vc71** | **Full ORT restored, analysis alive. Gate inversion + decline carve. QNN off. QAIRT out. 108 MB → 67 MB. User: "not bad."** Key bug D4 still open. |
| **vc72** | **Keyfix1 cycle: C++ peak-picked chroma + zero-mean Pearson (24 keys) + tonal gate; `SCHEMA_VERSION=5`/`full-ort-1.31.0-cpu-keyfix1`; regional key-confidence parity (`exit/entryKeyConfidence` + `shouldRunPhrasePlannerForBridge`); `fittedBpm`/`fitBeatSegments` edge+jitter fix for 75.949-BPM grids; truthful loop `lateStepReason`; telemetry tool. Tests 435/0/0/0 + 1347/0/0/1. On-device acceptance PENDING (no USB at handover).** |

The through-line: **vc69's build-config change broke the foundation, and three versions of audio tuning happened on top of an empty analyzer.** vc70's, vc71's tuning happened on a live analyzer with systematically wrong keys; vc72 fixes the key system at its root but has not yet had its telemetry (or its listening) — keep that epistemic rank in mind with every claim made from now on.

---

# 9. File map

## Planner / policy (shared)
```
shared/src/commonMain/kotlin/.../smart/TransitionAlternatives.kt   gate inversion, DECLINE carve math, FAMILIES registry, select()
shared/src/commonMain/kotlin/.../smart/TransitionPlanner.kt        plan branch selection + decline fallback, clash gate (~:381)
shared/src/commonMain/kotlin/.../smart/TransitionPolicy.kt         tempo bands, detectOctaveRelationship, incoming→outgoing fold
shared/src/commonMain/kotlin/.../smart/TransitionEvidence.kt      gate strings → mix.dump
shared/src/commonMain/kotlin/.../smart/DjMix.kt
shared/src/commonMain/kotlin/.../smart/TrackAnalysis.kt            exitKeyConfidence (:260), entryKeyConfidence (:270), exitKeyForMix/entryKeyForMix (≥0.25 regional-else-global)
shared/src/commonMain/kotlin/.../smart/TransitionMoves.kt          confidence at :95
shared/src/commonMain/kotlin/.../smart/HarmonicMix.kt              camelotCode (:36), fitBeatSegments (:114), fittedBpm (:178), CAMELOT_MAJOR/MINOR (:28-29)
shared/src/commonMain/kotlin/.../smart/LoopMix.kt                  lateStepReason (:391), regional keyConfidence (:1209-1210, :1306-1307, :1374)
```

## Renderer (app)
```
app/src/main/java/.../playback/CrossfadeController.kt   shouldRunPhrasePlannerForBridge (:345); gate call (:4086); rideDecline etc.
```

## Analysis
```
native/analyzer/audio_analysis.cpp                        D4 keyfinder (:332-336, :385, :462-546) — chroma, Pearson, 24 keys, tonal gate
app/src/main/java/.../playback/smart/TrackAnalyzer.kt   npuLibDir getter, ensureNpuPreflight, analyze()
app/src/main/java/.../playback/smart/NpuSelfTest.kt
app/src/main/java/.../playback/smart/NpuNative.kt
shared/src/jvmSharedMain/kotlin/.../smart/NpuSupport.kt          QNN_ENABLED = false  ← the switch (~:41)
shared/src/jvmSharedMain/kotlin/.../smart/AnalysisStore.kt       SCHEMA_VERSION=5 (:377), PIPELINE_PROVENANCE=full-ort-1.31.0-cpu-keyfix1 (:391), enforced (:97-98)
app/src/main/java/.../playback/PlaybackService.kt                TrackAnalyzer as property initializer — the NPE root
```

## Build
```
app/build.gradle.kts        jniLibs / pickFirsts region — DO NOT restore srcDir
app/proguard-rules.pro      Log.i/w preserved, Log.d/v stripped
build.gradle.kts:44-45      version 72 / "2.2.3-djmix30"
npu-addon/prebuilt/         137 MB QAIRT libs, dormant, gitignored, do not feed into build
```

## Evidence and docs
```
/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/vc72-device/    vc72 APK copy + build log + report + pending device folder
/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/vc71-device/    vc71 proof, telemetry tail, APK copy
/home/hfzhkn/Bitchord/docs/tools/analyze_telemetry.py               D6 telemetry analyzer (default input: vc71 telemetry tail)
/home/hfzhkn/Bitchord/docs/NPU_QNN_IMPLEMENTATION_PLAN.md
/home/hfzhkn/Bitchord/docs/session/BITCHORD_FORENSICS_164_90_MESSY_20261115.md
/home/hfzhkn/Bitchord/docs/session/BITCHORD_PLAN_SWAP_ABORT_TRACE_20261115.md
/home/hfzhkn/Bitchord/docs/ORCHARD_AUTOMIX_ANALYSIS.md
/tmp/orchard-analysis                                                    Orchard clone, read-only reference
```

---

# 10. Recommended next cycle, in order

**Cycle 1 — on-device vc72 acceptance (nothing else first)**
1. USB reconnect `1439ebea`; run the §12 acceptance script (install → launch → multi-item radio queue → 60–90 s → positive proofs → 3 crash captures → telemetry pull → `analyze_telemetry.py --since-ts`).
2. Re-run the D1/D3/D4 vitals from telemetry (§5): `*A` vs `*B` share, `steps=` presence, `drifting beat grid` on 75.949 BPM rows, `decline:true` + `keyClash` counts.
3. If any vital fails, **stop and diagnose before any tuning.** The D4 defeat proof (H1) says the bug is no longer in chroma — do not re-open chroma.

**Cycle 2 — listening verification of individual families (§11)**
Per-family confirmation with the user, one pair type at a time. Start with the key pair (Bubble Tea, Crescendo — §11).

**Cycle 3 — long-run stability (§D2)**
60-minute playback. Memory, crash buffer, telemetry growth.

**Cycle 4 — section/loop correctness against ground truth**
Never validated. Coordinate with the user and catalog values.

**Cycle 5+ — NPU, only if 1–4 are clean**
Start with the one-line `<uses-native-library libcdsprpc.so>` manifest fix. One build, one logcat.

---

# 11. What remains unverified — state this honestly to the user

Every item below is **UNVERIFIED UNTIL USER EARS HEAR IT**. The vc71 "not bad" verdict does not extend to vc72: the key pipeline, the confidence semantics, the beat-grid acceptance, and the planning order all changed this cycle.

- **On-device vc72 acceptance** — pending USB reconnect of `1439ebea` (§12). No vc72 telemetry exists yet.
- **Key accuracy after D4** — pending telemetry + ears. Specifically, **CLOSE YOUR EYES — Bubble Tea should read `4B` (A♭ major) instead of `5A`** (C minor) now; **ATEEZ — Crescendo**: re-check whether it reads **`10A` B minor** (relative minor preserved — CORRECT if the catalog value is truly B minor) or **`10B` D major**-family readings. The relative-key fix predicts `10A` for B minor — the vc71 `10A` reading on Crescendo may incidentally have been a peak-picking artifact landing on the right relative, not a correct detection. Listen and correlate.
- **`75.949 BPM` `Keyveatz` self/steady pairs** — should now be free of false `drifting beat grid`; listen for a clean beatmatch on 75-BPM-class tracks.
- **Declined pairs** should now route to `TransitionAlternatives` / `DECLINE` carve rather than `phrase-M6`; listen for the dual-band cut (800 Hz / 438 Hz at mid) instead of a phrase bridge on pairs the planner declines.
- **All vc71 families** — bridge, carve, muffle return, one-kick handoff, SEAM A/B rides, exit beats, DROP_IN arm — **none re-confirmed by ear on vc72**.
- **Long-run stability** past ~7 min.
- **Full queue completion** — the vc71 test queue was mid-flight at handover; same status assumed carried-forward.
- **`decline:true` firing in production** — will DECLINE now actually fire with unstarved, unmasked confidences? Live telemetry decides.
- **Section/loop correctness against ground truth** — never validated.
- **`V73` vs `V75`** — undecided; no `HTP ARCH` line has ever been emitted.
- **Whether dynamic-INT8 assets dispatch on HTP at all** — probably not; needs QDQ.
- **`:app:testProdDebugUnitTest` against the final vc72 artifact** — green in the test cycle (1347/0/0/1, fresh XML Nov 15 11:22/11:29); the APK was built later that evening. Re-run on a quiet box before release if the sources moved after the test runs (they did not; re-pin was measured against the same HEAD).
- **`PresenceTest` flakiness** — failed twice on mock-server timeouts under a memory-starved box, passed on a quiet rerun. If it fails, check for zombie Gradle daemons before assuming a real regression.

---

# 12. Acceptance criteria for the next handover

A cycle is done when:

1. Both test suites green, from fresh XML, on a quiet box — shared 435/0, app 1347/0/1 skip, nothing suppressed or loosened.
2. On-device: vc72 installed on `1439ebea` and `dumpsys` reads `versionCode=72` / `2.2.3-djmix30`. Analysis proof lines quoted from a real radio-queue run, not inferred from silence.
3. Crash buffer empty on three consecutive captures.
4. APK hash, size, versionCode, cert SHA + full build log recorded.
5. Git invariants re-pinned and unchanged (6085 unified-diff lines / 4726 changed lines; 123 porcelain).
6. Telemetry vitals re-measured: `*A` vs `*B` no longer 100/0; `steps=` present; `drifting beat grid` absent on 75.949-BPM full-quality rows; declined pairs in the DECLINE carve rather than phrase-M6.
7. Every audio claim marked **UNVERIFIED UNTIL USER EARS HEAR IT**, with the specific pair/behaviour the user should listen for.
8. Every defeated hypothesis written down, not silently dropped (see §12.7 below). Half the value of this handover is the list of theories that were wrong.

## 12.7 Defeated hypotheses — written down so they are not re-litigated

1. **Defeated Hypothesis 1 (D4):** *"BitChord's Camelot wheel table in `HarmonicMix.kt` is transposed or swaps relative major/minor (and `ATEEZ — Crescendo` B minor should be `11B`)."* → **FALSE.** `HarmonicMix.kt:28-29` (CAMELOT_MAJOR/MINOR arrays feeding `camelotCode()` at `HarmonicMix.kt:36`) is 100% standard Mixed In Key: `B minor = 10A`, `A major = 11B`, `A♭ major = 4B`, `C minor = 5A`. (The table's numbers are consistent with the wheel as drawn in `MixViewPopup.kt:739-740`.) The 100% `*A` (`54/54`) bug was entirely in `native/analyzer/audio_analysis.cpp`: all-bin `log1p(power)` chroma with **no peak-picking** smeared energy into the chroma of every pitch class, and the **uncentered dot product** (no mean subtraction) gave `k_minor` a constant **+2.72 sum / +0.2267 mean DC bias** over `k_major` — the Krumhansl-Kessler minor profile has the larger total, so every flat chroma scored the same way: minor. The uncentered matcher also compressed `keyConfidence` to **0.04–0.22**, which starved every confidence gate downstream (see H2). vc72's fix: peak-picked chroma + **zero-mean Pearson** (mean-subtracted) + 24 keys + tonal gate (`audio_analysis.cpp:385`, `:462-546`).
2. **Defeated Hypothesis 2 (D3):** *"`decline:true` / `DECLINE` carve is dead/unreachable in production."* → **FALSE.** `docs/tools/analyze_telemetry.py` (D6) proved `DECLINE` was planned 8 times and fired twice in `classic` during the 30.57-min vc71 session on device — **however**, the carve was starved, not dead: `108/108` `decline-to-mix` dumps logged `steps=?` (all `dualvocal`-only, 0 `keyClash`), and `TransitionAlternatives` clash inversion (`keyClash` with confidence ≥ 0.65) never triggered because **D4 starved `keyConfidence` below 0.25** AND weak regional head/tail keys masked the global keyConfidence the gate relied on. Simultaneously, `CrossfadeController.nativeBridge` ran `phrasePlanFor` (M6) **ahead of** `LoopMix.bridge` on declined pairs — so a declined pair still got a phrase bridge before the DECLINE carve could choose otherwise. vc72 fixes all three: real confidences (D4), regional/global parity (D3a), and the M6 gate (D3b, `shouldRunPhrasePlannerForBridge`).
3. **Defeated Hypothesis 3 (D1):** *"`drifting beat grid` is a harmless stale pre-ORT string or pure telemetry-cache race."* → **FALSE.** While `classic` `mix.dump` does fire before M1 loop planning (ordering invariant, unrelated), `fittedBpm` in `HarmonicMix.kt` genuinely returned `null` on `quality="full"` grids: at 75.949 BPM the beat period is **0.790 s > the old 0.75 s fixed edge margin**, AND isolated `snapGrid` onset jitter (±35 ms) broke the **6% single-gap rule** in `fitBeatSegments`, splitting steady grids. `fittedBpm = null` set `PairTraits.unfit = true` (`phaseLockable = false`, `incomingPlaybackRate = 1.0`) — a real downgrade of every 75-BPM-class blend, not a display artifact. vc72: edge margin `maxOf(0.75, period * 2.5)` (`HarmonicMix.kt:178`) + isolated-snap tolerance (`HarmonicMix.kt:114`).
4. **Defeated Hypothesis 4 (D5):** *"`detectOctaveRelationship` is unwired from `mix.dump`."* → **FALSE.** It is wired on all 3 `Evidence` paths. The closest vc71 pair was ratio `0.5443` — **8.86% > 8.0%** tolerance — correctly silent. Zero real 2×/0.5× pairs have been observed triggering it; the detector is unit-wired but field-silent by design, pending a real double/half-time queue.
5. **Defeated Hypothesis 5 (Loop Arm Death):** *"`outgoing decode 0 ms over` means decoding genuinely missed the splice deadline by 0 ms."* → **FALSE.** `LoopMix.lateStepReason` clamped `(nowMs - deadlineMs).coerceAtLeast(0L)` to `0L`, masking the true overrun; and `CrossfadeController` called `lateDeckReason()` when `LoopMixSession.build` or `shiftToNextBar` returned `null` **seconds before** `deadlineMs` — i.e. the arm died long before the deadline, and the logged reason was computed after the fact. vc72 concentrates epoch/horizon handling in `lateStepReason` (`LoopMix.kt:391`) and reports the real overrun; regression tests updated (`Djmix15RegressionTest.kt:41-76`).

absolute WsThanhDieu to whoever picks this up — keys are now real quantities instead of a wheel guess, the change is bounded to one native file plus parity plumbing, and the next man doesn't have to rediscover why vc71's map keyed everything in A. Guard the ORT packaging with your life and the rest is tuning — but go hear it first.
