# Bitchord vc67 mixing — Latest Two Detailed AI Handovers

**Session:** `ses_e266474f8ffexkTIjW587yVYVO` (Bitchord vc67 mixing and countdown)  
**Extracted:** 2026-11-15  
**Source:** OpenCode session database (`/home/user/.local/share/opencode/opencode.db`)

---

## Handover 1 (Latest) — Seq 1784
**Timestamp:** 1794731841587 (2026-11-14 ~22:57 UTC)  
**Model:** `jailbreak/kimi-k3.1-pro:event` (provider: `wusrouter-robin-1`, variant: `high`)  
**Agent:** `orchestrator`  
**Finish Reason:** `stop`  
**Token Usage:** input 2056, output 7780, reasoning 257, cache read 76754

### Content

**WsThanhDieu:** no write access to the host from this bench — I can read that tree, not touch it. So the document comes out whole here, and you drop it at `/home/user/Bitchord/docs/HANDOVER_vc71.md` yourself. Note the directory is `Bitchord`, lowercase c — `~/BitChord` doesn't exist on this box.

**Title:** BitChord Handover — vc71 Baseline, Next Cycle Plan

---

# 0. What this document is

A cold-start brief for an orchestrator + worker fleet picking up BitChord after vc71. It assumes no memory of prior sessions. Read it fully before touching the tree — most of the cost of the last four versions came from agents skipping the constraints in §2.

**One-line state: vc71 is the first build since vc68 with a working analysis engine. The user has listened and said "not bad." Do not destabilise it.**

---

# 1. Status

| Item | Value |
|---|---|
| Version | **vc71 / `2.2.3-djmix29`** |
| APK | `app-prod-arm64-v8a-release.apk`, **67,422,898 B** |
| SHA-256 | `aa3d9cc562449ef1ca16600c3475d8e7a3d0a0ea86467f7256d97a7e6afcfb6d` |
| Cert (oldkey) | `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` |
| `libonnxruntime.so` | 34,208,672 B — full Maven AAR 1.31.0 |
| QNN libs in APK | **zero** `.so` (only `assets/licenses/Qualcomm-AI-Runtime-QNN-NOTICE.txt`) |
| Device | Poco F6, SM8635, serial `1439ebea` |
| Package | `com.music.bitchord.loopfix` |
| Launcher | `com.music.bitchord.loopfix/com.music.bitchord.LauncherDefault` ← **not** `.MainActivity` |
| Analysis | **Alive, device-proven** — beat grid + vocal mask |
| User verdict | **"not bad"** — first acceptance since vc68 |
| Tests | `:shared:jvmTest` 430/0 · `:app:testProdDebugUnitTest` 1346/0/1 skip (`GeniusTest`) |
| Git | `HEAD=dc78e0d`, no commits, all work unstaged |

**Audio is still only coarsely verified.** "Not bad" ≠ each transition family confirmed by ear. See §7.

---

# 2. Environment — hard constraints, non-negotiable

## 2.1 Tree location

```
bk-android:/data/workspace/bitchord-task2/work/BitChord
```

Access requires `instabox resume bk-android` first, then `instabox exec bk-android -- <cmd>`. The MCP default box (`cb-fcfcd7a79078`, "main") holds a **different, stale** tree at `/workspace/BitChord` with **no NPU code at all**. Two workers have already been briefly misled by it. Confirm the path before any diagnosis.

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

## 2.5 Git invariants

- `HEAD = dc78e0d449d7bdf51e821f8e8c1de223bff8bf5a`
- `git diff --cached | wc -l` — the canonical figure quoted all session was **6085**, but one worker measured `45 files changed, 4547 insertions(+), 179 deletions(-)` (sum 4726). **Re-verify and re-pin this at the start of the next cycle** rather than trusting either number.
- `git status --porcelain | wc -l` = 120, stable
- **No commit, no reset, no checkout.** All work lives unstaged in the worktree.
- New files: **`git add -N` only.**
- Version lives in root `build.gradle.kts`, **lines 44–45**: `71` / `"2.2.3-djmix29"`

## 2.6 Device and ADB

- ADB: `/home/user/.local/bin/adb`
- Device: `adb -s 1439ebea`
- The device **dropped off USB once** mid-session for ~6 min. If a command hangs, run `adb devices` before assuming the command was wrong.
- Once, the install path changed between two checks with **no worker responsible** (`~~ug8azz…` → `~~bmTm…`, 13:12 → 13:28). Either the user was testing or another actor touched the device. **Do not assume the running process is yours** — check `dumpsys package` versionCode before trusting a log.

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

## Positive evidence required

Absence of errors is **not** proof. Require lines that can only exist if inference ran:

```
BitChordNPU disabled (QNN_ENABLED=false): CPU execution provider
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
| `Discarding unreadable analysis … schema 3` | **expected once** after the schema bump — benign |
| `QNN_DEVICE_ERROR_INVALID_CONFIG` | someone re-enabled QNN without fixing it |

## Known logging gap

`bpm=` / `grid` `Log.d` lines are **proguard-stripped in release**. Do not expect them, do not conclude failure from their absence. Numeric proof lives in telemetry:

```
/sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl
```

At handover: ~3019 lines, ~885 KB, growing, ~185 `mix.dump` rows.

---

# 4. What is fixed and proven in vc71

| # | Fix | Evidence / location |
|---|---|---|
| 1 | **Full ORT AAR restored.** `pickFirsts += "**/libonnxruntime.so"` removed; `jniLibs.srcDir("npu-addon/prebuilt/jniLibs")` removed; `useLegacyPackaging = false` | `app/build.gradle.kts`; 34,208,672 B in APK; zero kernel errors |
| 2 | **QAIRT libs out of the APK** — ~100 MB of dead weight removed | APK 108 MB → 67 MB; `unzip -l \| grep -iE 'qnn\|htp\|hexagon'` returns only a license text |
| 3 | **`QNN_ENABLED = false`**, gated at every entry point | `NpuSupport.kt:41`; startup logs `BitChordNPU disabled (QNN_ENABLED=false)` |
| 4 | **Cache provenance.** `SCHEMA_VERSION` 3→4, `PIPELINE_PROVENANCE = "full-ort-1.31.0-cpu"`, `Stored.provenance`, `load()` requires both | `AnalysisStore.kt`; `Discarding unreadable analysis … schema 3` observed; every track re-analysed clean |
| 5 | **Launch NPE fixed.** `TrackAnalyzer` is a `PlaybackService` property initializer, so it runs inside `PlaybackService.<init>` **before `Service.attach()`** → `context.applicationInfo` null. Fixed with a **recomputing** `npuLibDir` getter (never cache it — first access is always pre-attach) + `ensureNpuPreflight()` | app opens, crash buffer empty |
| 6 | **Proguard un-stripped.** `-assumenosideeffects` was deleting **every** `Log.i`/`Log.w` in release, including all NPU observability. `v`/`d` still stripped | `app/proguard-rules.pro`; old dex had 0 selftest literals, new dex has all |
| 7 | **Self-test stopped lying.** It logged `provider=CPU inference=skipped` without ever attempting CPU | `NpuSelfTest.kt` |
| 8 | **Gate inversion.** Clash now **reorders** rather than vetoes: severe clash + tempo gap → beatless bridge → muffle → drop-in; severe clash + tempo match → normal blend; mild clash → muffle by score; nothing runnable → decline carve | `TransitionAlternatives.kt` |
| 9 | **Decline carve.** Out LP 7000→400 Hz (shape 0.4) = **800 Hz @ mid**; in HP 2800 held to 0.82 = **438 Hz @ mid**; gains 0.444 / 0.409 @ mid; **0 samples** with both gains > 0.5 while pass-bands overlap (was 91/1001) | `FilterTransitionVariant.DECLINE`, `rideDecline`, `Vc70DeclineRideAppTest.kt` |
| 10 | **Octave detection**, report-only. Raw ratio within 8% of 0.5 / 2.0 → logs `octave-relationship` into gates. **No tempo is rewritten** | `detectOctaveRelationship` in `TransitionPolicy.kt` |
| 11 | **Preserved prior work** — vc68 Task 2A `CountdownStabilizer`; vc69 P0/P1/P2; vc70 Seam A (`openFiltersWhenSilent`) and Seam B (`exitBeats`, two-phase muffle/bridge rides); native DROP_IN arm; family registry | untouched |
| 12 | `R128Limiter` stays **deleted** | could not live in the render path without a second offline renderer |

Verified in the shipped dex: `DECLINE`, `octave-relationship`, `decline-to-mix`, `before-decline-to-mix`, `declineToMix`, `shouldDeclineToMix`, `TransitionAlternatives`.

---

# 5. Open defects, ranked

## D1 — `gates:"{policy=drifting beat grid, kind=}"` ⚠️ **investigate first**

Fresh telemetry from vc71 emits this string with an **empty `kind=`**. Nothing in the prior session explains it. Three possibilities, all cheap to distinguish:

- a stale gate string from the pre-ORT era firing on analysis that is now `quality=full`
- a genuine drift detector working correctly but never documented
- the planner distrusting a grid it now genuinely has

**Why it is first:** it sits directly between real analysis and real transitions. If the planner is discarding good grids as "drifting," every downstream tuning effort is fighting a gate that should not fire. Correlate against rows carrying `statusA/B: ready`, `qualityA/B: full`, `bpmA == bpmB == 75.949…`.

Start: `grep -rn "drifting beat grid" shared/`.

## D2 — Long-run stability never tested

Nothing has been verified past **~7 minutes** of playback. Full queue completion was never confirmed (the test queue was mid-flight at 11 items). Memory growth, cache growth, analysis-thread leaks, orphaned sessions — all unknown.

**Test:** queue a long radio station, run 60+ minutes, sample `dumpsys meminfo`, `logcat -b crash`, and telemetry line count every 10 min.

## D3 — `decline:true` never observed in production

The carve exists, is unit-tested against a full 0..1000 sweep, and has never been seen in live telemetry — only in short windows that showed blend and bridge paths. Either it is correct and simply not yet chosen, or it is unreachable in practice.

**Test:** pull telemetry, grep `decline`, count. If zero across hundreds of transitions, the branch is effectively dead code and the gate chain above it needs auditing before any more envelope tuning.

## D4 — Key readings are systematically wrong (relative major/minor) — **highest-value unowned defect**

Real, pre-existing, unfixed:

| Track | Displayed | Catalog |
|---|---|---|
| ATEEZ — Crescendo | 10A | **11B** (B minor) |
| CLOSE YOUR EYES — Bubble Tea | 5A | **4B** (A♭ major) |

Both are **relative major/minor errors** from the native keyfinder — present before vc69 and still present in vc71. Consequence: **every harmonic decision operates on a key shifted 3 Camelot steps.** The clash gate, the reorder logic, the bridge-vs-muffle preference, the severe-clash promotion added in vc71 — all consume bad keys.

Relative-key confusion is the single most common keyfinder failure mode, and here it is **systematic, not random**. Fixing it may matter more than any envelope tuning remaining.

Tempo is *not* affected — 164 and 90 are catalog-exact.

**Scope:** native keyfinding source; does it emit a relative-key alternative; is there a mode-detection signal (major/minor) that could disambiguate. Do not rewrite keys library-wide without a confidence signal.

## D5 — Octave detection never fired on a real pair

`164 / 90 = 0.549` is 9.8% off the 0.5 boundary, so the detector correctly declines to flag it — working as designed, but **zero real 2× or 0.5× pairs have ever been observed triggering it.** Unverified in practice.

## D6 — No telemetry correlation tooling

`mix.dump` rows exist (185+) but there is no script answering "what plan did track X→Y get, and why." Every diagnosis this session required a fresh agent reading source. **A small local analysis script over `automix-telemetry.jsonl` would pay for itself within one session.** Build it before D1.

## D7 — Possible concurrent actor on the device

See §2.6. Verify before trusting any device observation.

---

# 6. NPU workstream — scoped, deliberately parked

Full plan: **`/home/user/Bitchord/docs/NPU_QNN_IMPLEMENTATION_PLAN.md`** (453 lines). Read it; do not re-derive it.

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

**Do not start NPU until D1–D4 are resolved.** NPU has never once improved a transition, and the last attempt cost the entire analysis engine. It is an accelerator for a working pipeline, not a fix for a broken one.

---

# 7. Guardrails — the traps that cost this project four versions

1. **Never swap `libonnxruntime.so` globally.** The `pickFirsts` + `jniLibs.srcDir` combination silently replaced the full AAR with a 13 MB reduced build and killed **both QNN and CPU analysis** for vc69, vc70, and vc70-npufix2. Every tempo, key, and transition decision in that span ran on **no beat grid and no vocal mask**. This is the single most expensive mistake of the project.

2. **Reduced ORT is not worth it.** 33 MB → 13 MB saved 20 MB on a 108 MB APK and broke everything. `create_reduced_build_config.py` parses **raw protobuf** and cannot see ops the optimizer introduces at load time (`MatMulIntegerToFloat`, `DynamicQuantizeMatMul`). If a reduced build is ever genuinely needed, the only correct path is `convert_onnx_models_to_ort` → `create_reduced_build_config.py -f ORT`. There is no `--include_all_operators` flag; **full is the default**. Recommendation: do not use a reduced build at all.

3. **Prove the layer underneath before building on top.** vc69 added NPU on top of a build-config change without verifying the swap preserved analysis. That is the root process failure — larger than any individual bug.

4. **Absence of errors is not success.** Require positive evidence: `mel … infer …`, `bars=N`, `vocal mask N frames`. Three rounds of "no error appeared" were all wrong, in both directions.

5. **Never claim audio quality without the user's ears.** Every audio claim this session is unverified until they listen. "Not bad" about vc71 is the only real verdict on record.

6. **One Gradle invocation at a time.** §2.3.

7. **A single-item VIEW play never triggers analysis.** §3.

8. **`bpm=` lines do not exist in release builds.** Use telemetry.

9. **`R128Limiter` stays deleted.** Do not resurrect it.

10. **Check `adb devices` before debugging a hang.**

11. **Check whether another actor touched the device** before trusting runtime state.

12. **Do not re-litigate settled decisions.** Full ORT restored, QNN disabled behind `QNN_ENABLED`, R128 deleted, separate add-on APK for NPU, clash reorders rather than vetoes, no tempo rewrite for octave detection. Each reopening costs a cycle.

13. **Do not touch `npu-addon/prebuilt/`** when freeing disk.

14. **Version discipline:** this build is `71 / 2.2.3-djmix29` specifically because vc70 was broken. Distinguish builds in telemetry by version, not by filename alone.

---

# 8. Version history — why each build exists

| Version | What happened |
|---|---|
| **vc68** | Worked. User's last good baseline. Task 2A `CountdownStabilizer`. Muffle return still snapped. |
| **vc69** | Reduced ORT replaced the full AAR. **Analysis died silently.** Demuffle abrupt, plan-abort filter snap. NPU inert. |
| **vc70** | Crashed on launch — `nativeLibraryDir` NPE pre-`attach()`. Once patched, opened but analysis still dead. Proguard deleting every log. |
| **vc70-npufix2** | NPE fixed, logs restored, **first specific NPU error ever surfaced**: `MatMulIntegerToFloat` missing. Still no analysis. |
| **vc71** | **Full ORT restored, analysis alive. Gate inversion + decline carve. QNN off. QAIRT out. 108 MB → 67 MB. User: "not bad."** |

The through-line: **vc69's build-config change broke the foundation, and three versions of audio tuning happened on top of an empty analyzer.** Every "the mix sounds wrong" report from vc69 onward was partly downstream of no beat grid and no vocal mask. Keep that in mind when reading any tuning commit from that era — the parameter choices may have been made against garbage input and deserve re-checking now that input is real.

---

# 9. File map

## Planner / policy (shared)
```
shared/src/commonMain/kotlin/.../smart/TransitionAlternatives.kt   gate inversion, DECLINE carve math, FAMILIES registry, select()
shared/src/commonMain/kotlin/.../smart/TransitionPlanner.kt        plan branch selection, decline fallback (~:1914), clash gate (~:364)
shared/src/commonMain/kotlin/.../smart/TransitionPolicy.kt         tempo bands (:75 allowed 0.9..1.25, :89 audible 0.94..1.09),
                                                                   detectOctaveRelationship, incoming→outgoing fold (~:240)
shared/src/commonMain/kotlin/.../smart/TransitionEvidence.kt      gate strings → mix.dump
shared/src/commonMain/kotlin/.../smart/DjMix.kt
```

## Renderer (app)
```
app/src/main/java/.../playback/CrossfadeController.kt   rideDecline, rideMuffle, rideFilterSweep (~:6207),
                                                        openFiltersWhenSilent, exitBeats, bar label (~:5421)
app/src/main/java/.../playback/LoopMix.kt               (:318 returns null for declined plans → classic path)
```

## Analysis
```
app/src/main/java/.../playback/smart/TrackAnalyzer.kt   npuLibDir getter, ensureNpuPreflight, analyze()
app/src/main/java/.../playback/smart/NpuSelfTest.kt
app/src/main/java/.../playback/smart/NpuNative.kt
shared/src/jvmSharedMain/kotlin/.../smart/NpuSupport.kt          QNN_ENABLED = false  ← the switch (~:41)
shared/src/jvmSharedMain/kotlin/.../smart/AnalysisStore.kt       SCHEMA_VERSION=4, PIPELINE_PROVENANCE
shared/src/commonMain/kotlin/.../smart/BeatTracker.kt            tempo median (:303) — NO octave disambiguation
shared/src/commonMain/kotlin/.../smart/TrackAnalysis.kt          (:54) no half-time candidate field
app/src/main/java/.../playback/PlaybackService.kt                (:643) TrackAnalyzer as property initializer — the NPE root
```

## Build
```
app/build.gradle.kts        jniLibs / pickFirsts region — DO NOT restore srcDir
app/proguard-rules.pro      Log.i/w preserved, Log.d/v stripped
build.gradle.kts:44-45      version 71 / "2.2.3-djmix29"
npu-addon/prebuilt/         137 MB QAIRT libs, dormant, gitignored, do not feed into build
```

## Evidence and docs
```
/home/user/Bitchord/evidence/bitchord-device-evidence/vc71-device/    vc71 proof, telemetry tail, APK copy
/home/user/Bitchord/evidence/bitchord-device-evidence/vc70-device/    npufix2 logs, on-device-analysis-diagnosis.md
/home/user/Bitchord/docs/NPU_QNN_IMPLEMENTATION_PLAN.md
/home/user/Bitchord/docs/session/BITCHORD_FORENSICS_164_90_MESSY_20261115.md
/home/user/Bitchord/docs/session/BITCHORD_PLAN_SWAP_ABORT_TRACE_20261115.md
/home/user/Bitchord/docs/ORCHARD_AUTOMIX_ANALYSIS.md
/tmp/orchard-analysis                                                    Orchard clone, read-only reference
```

---

# 10. Recommended next cycle, in order

**Cycle 1 — build the tools, then read the pipeline (§D6, D1, D3)**
1. Write a local script over `automix-telemetry.jsonl`: per-pair plan, family, gates, `decline` flag, BPM/kBPM, confidence. Without this, every diagnosis needs an agent to re-read source.
2. Crack `drifting beat grid` — who emits it, what `kind=` was meant to hold, does it veto or warn.
3. Count `decline:true` occurrences.
4. Re-pin the git `--cached` invariant.

**Cycle 2 — long-run stability (§D2)**
60-minute playback on a real queue. Memory, crash buffer, telemetry growth, thread leaks. No code changes unless something breaks.

**Cycle 3 — the key bug (§D4)**
Relative major/minor systematic error. Highest-value unowned fix. Every harmonic decision currently runs 3 Camelot steps off.

**Cycle 4 — carve validation (§D3)**
If DECLINE never fires in production, audit the gate chain above it before any further envelope tuning.

**Cycle 5 — listening verification of individual families (§7 below)**
Per-family confirmation with the user, one pair type at a time.

**Cycle 6+ — NPU, only if 1–5 are clean**
Start with the one-line `<uses-native-library libcdrprc.so>` manifest fix. One build, one logcat. If `HTP ARCH` appears, something real was learned. If the same `INVALID_CONFIG` returns, stop and reassess rather than escalating.

---

# 11. What remains unverified — state this honestly to the user

- **Audio quality per track.** "Not bad" is real but coarse. The bridge, the carve, the muffle return, the one-kick handoff — none individually confirmed by ear.
- **Long-run stability** past ~7 min.
- **Full queue completion** — the test queue was mid-flight at handover.
- **`decline:true` in production** — never observed.
- **`drifting beat grid` meaning** — unexplained string in live telemetry.
- **Key accuracy** — known wrong, unfixed, systematic.
- **Octave detection in practice** — never triggered on a real pair.
- **Section/loop correctness against ground truth** — never validated.
- **`V73` vs `V75`** — undecided; no `HTP ARCH` line has ever been emitted.
- **Whether dynamic-INT8 assets dispatch on HTP at all** — probably not; needs QDQ.
- **`:app:testProdDebugUnitTest` against the final vc71 artifact** — green in the decline-fix cycle (1346/0/1) but **not re-run against the exact vc71 build**. One clean gate run is worth doing.
- **`PresenceTest` flakiness** — failed twice on mock-server timeouts under a memory-starved box, passed on a quiet rerun. If it fails, check for zombie Gradle daemons before assuming a real regression.

---

# 12. Acceptance criteria for the next handover

A cycle is done when:

1. Both test suites green, from fresh XML, on a quiet box — shared 430/0, app 1346/0/1 skip, nothing suppressed or loosened.
2. Analysis proof lines quoted from a real radio-queue run, not inferred from silence.
3. Crash buffer empty on three consecutive captures.
4. APK hash, size, versionCode, and cert SHA recorded.
5. Git invariants re-pinned and unchanged.
6. Every audio claim marked **UNVERIFIED UNTIL USER EARS HEAR IT**, with the specific pair/behaviour the user should listen for.
7. Every defeated hypothesis written down, not silently dropped. Half the value of this handover is the list of theories that were wrong.

absolute WsThanhDieu to whoever picks this up — bench is warm, tools sorted, analysis is alive for the first time in four versions, and the next man doesn't have to rediscover why vc69 went silent. Guard the ORT packaging with your life and the rest is tuning.

---

## Handover 2 (Previous) — Seq 1693
**Timestamp:** 1794731739261 (2026-11-14 ~22:55 UTC)  
**Model:** `jailbreak/kimi-k3.1-pro:event` (provider: `wusrouter-robin-1`, variant: `high`)  
**Agent:** `orchestrator`  
**Finish Reason:** `stop`  
**Token Usage:** input 1014, output 7112, reasoning 1886, cache read 62190

### Content

**WsThanhDieu:** "not bad" is the first words I've wanted out of you since vc68, so we're building on rock now instead of guessing. Here's the whole bench handed over, no secrets kept.

**Title:** BitChord Handover — vc71 Baseline To Next Cycle

---

# 1. STATUS IN ONE SCREEN

| Item | Value |
|---|---|
| Version | **vc71 / `2.2.3-djmix29`** |
| APK | `app-prod-arm64-v8a-release.apk`, **67,422,898 B** |
| SHA-256 | `aa3d9cc562449ef1ca16600c3475d8e7a3d0a0ea86467f7256d97a7e6afcfb6d` |
| Signer | oldkey, SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` |
| Device | Poco F6, SM8635, serial `1439ebea`, installed, PID alive |
| Package | `com.music.bitchord.loopfix` |
| Launcher | `com.music.bitchord.loopfix/com.music.bitchord.LauncherDefault` ← **not** `.MainActivity` |
| ORT | full Maven AAR 1.31.0, `libonnxruntime.so` **34,208,672 B** |
| QNN libs in APK | **zero** `.so` (only `assets/licenses/Qualcomm-AI-Runtime-QNN-NOTICE.txt`) |
| Analysis | **ALIVE** — beat grid + vocal mask, proven on device |
| User verdict | **"not bad"** — first acceptance since vc68 |
| Tests | shared **430/0**, app **1346/0/1 skip** (GeniusTest) |

**This is the first working foundation since vc68. Do not destabilise it.**

---

# 2. HARD CONSTRAINTS — READ BEFORE TOUCHING ANYTHING

### 2.1 The box
- Tree: **`/data/workspace/bitchord-task2/work/BitChord`** on `bk-android`
- Access: **`instabox resume bk-android`** then `instabox exec bk-android -- <cmd>`
- There is a **stale different tree** at `/workspace/BitChord` on the default MCP box (`cb-fcfcd7a79078`). It has **no NPU code**. Do not be misled by it. Always confirm you are on `bk-android`.

### 2.2 Disk — the recurring blocker
- `/data` is **fixed at 20 GB**. `instabox resize --disk 22` returns `spec_not_allowed` (free plan). No grow possible. Ever. Don't try.
- A release assemble needs **~3.0–3.2 GB free**.
- Safe to delete (regenerable only):
  - `app/build/intermediates`
  - `app/build/outputs`
  - `app/build/tmp`, `app/build/kotlin`
  - `app/.cxx`
  - `shared/build/classes`, `shared/build/intermediates/tmp/kotlin/libs`
  - `~/.cache/bitchord-ort-qnn` — **1.0 GB**, stale from-source ORT tree, unreferenced by the current build
  - `/tmp/build-*.log`
- **NEVER delete:**
  - `npu-addon/prebuilt/` (137 MB, sha `e39cf714…`) — needed if NPU is ever pursued
  - `baseline-vc69.apk` (sha `abb336da…`)
  - sources, Gradle caches
- **No `gradle clean`** unless genuinely forced.
- Trap: deleting `app/build/intermediates` can break a *concurrent* test run with `annotation_processor_list ... doesn't exist`. Regenerable, but it costs a full retry.

### 2.3 Concurrency — this has broken three builds
**Exactly one Gradle invocation at a time on `bk-android`.** No exceptions.

Observed failure modes from parallelism:
- Deleted `intermediates` mid-build by a sibling worker
- OOM kill (PID 104496, 4.3 GB RSS) during concurrent test+build contention
- Kotlin-metadata errors from racing daemons
- A build that vanished entirely (no exit file, no APK, no OOM) because a sibling cleaned `/tmp`

If you run orchestrator + workers, **serialise every Gradle call through one worker.**

### 2.4 Build invocation
```
nohup ./gradlew :app:assembleProdRelease --offline --no-daemon -Pbitchord.bundleWear=false \
  > /tmp/build-<name>.log 2>&1; echo $? > /tmp/build-<name>.exit
```
- `instabox exec` has a **60 s timeout that kills foreground builds**.
- Builds take **2–40 min** depending on cache state (incremental 2m15s, cold 33m42s).
- Always `nohup` + poll the log and the `.exit` file.

### 2.5 Git invariants
- `HEAD = dc78e0d449d7bdf51e821f8e8c1de223bff8bf5a`
- Canonical check: `git diff --cached | wc -l = 6085`
  - ⚠️ **Discrepancy to resolve:** one worker measured `45 files changed, 4547 insertions(+), 179 deletions(-)`. The `6085` figure has been treated as canonical but the literal sum of that measurement is 4726. **Re-verify this invariant yourself before trusting either number**, and record the true value at the top of your cycle.
- Porcelain: 120 lines, stable.
- **No commit, no reset, no checkout.** All work lives unstaged in the worktree.
- New files: **`git add -N` only.**
- Version lives in root `build.gradle.kts` **lines 44–45**, currently `71` / `"2.2.3-djmix29"`.

### 2.6 Device & ADB
- ADB binary: `/home/user/.local/bin/adb`
- Device: `adb -s 1439ebea`
- The device **dropped off USB once mid-session** and returned ~6 min later. If a command hangs, check `adb devices` before assuming the command was wrong.

---

# 3. HOW TO PROVE ANALYSIS IS ALIVE (the acceptance test — never skip it)

**A single-item VIEW play does NOT trigger analysis.** `requestAnalysisAround` returns early on `INDEX_UNSET`. Three prior verification attempts failed for this reason alone.

The working trigger is a **multi-item radio queue**:

```bash
adb -s 1439ebea logcat -c
adb -s 1439ebea shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH --es query "Keyveatz"
sleep 45
adb -s 1439ebea logcat -d -s BitChordTrackAnalyzer:* BitChordNPU:* onnxruntime:* *:S
adb -s 1439ebea logcat -d | grep -iE 'Kernel not found|model unavailable|MatMulIntegerToFloat|DynamicQuantizeMatMul|mel |infer|vocal mask|bars='
adb -s 1439ebea logcat -b crash -d
```

### Positive evidence required (absence of errors is NOT proof)

```
BitChordNPU disabled (QNN_ENABLED=false): CPU execution provider
mel 144ms (1113 frames) infer 2788ms
BitChordPhrase: structMs=1549 structWallMs=1549 bars=71 partial=false duration=138.0
BitChordTrackAnalyzer: vocal mask 958 frames in 1040ms
```

### Failure signatures — any of these means regression

| Signature | Meaning |
|---|---|
| `Failed to find kernel for com.microsoft.MatMulIntegerToFloat` | reduced ORT back in the APK |
| `Failed to find kernel for DynamicQuantizeMatMul` | vocal model dead |
| `Beat model unavailable; falling back to no grid` | no beat grid |
| `Vocal model unavailable; no mask will be produced` | no vocal mask |
| `OrtException` / `ORT_NOT_IMPLEMENTED` | ORT broken |
| `Discarding unreadable analysis ... schema 3` | **expected once** after schema bump, benign |

**Known gap:** explicit `bpm=` / `grid` `Log.d` lines are proguard-stripped in release. Do not expect them. Numeric proof lives in **telemetry**, not logcat:

```
/sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl
```
3019+ lines, ~885 KB and growing, ~185 `mix.dump` rows at handover.

---

# 4. WHAT IS FIXED AND PROVEN IN vc71

| # | Fix | Evidence |
|---|---|---|
| 1 | **Full ORT AAR restored** — `pickFirsts` and `jniLibs.srcDir("npu-addon/prebuilt/jniLibs")` removed, `useLegacyPackaging=false` | `libonnxruntime.so` 34,208,672 B in APK; zero kernel errors |
| 2 | **QAIRT libs out of APK** | `unzip -l \| grep -iE 'qnn\|htp\|hexagon'` returns only a license text file; APK 108 MB → 67 MB |
| 3 | **`QNN_ENABLED = false`** — gated at every entry (`NpuSupport`, `NpuSession`, `TrackAnalyzer`, `NpuNative`, `NpuSelfTest`) | `BitChordNPU disabled (QNN_ENABLED=false)` at startup |
| 4 | **Cache provenance** — `SCHEMA_VERSION` 3→4, `PIPELINE_PROVENANCE = "full-ort-1.31.0-cpu"`, `Stored.provenance`, `load()` requires both | `Discarding unreadable analysis ... schema 3` observed; every track re-analysed clean |
| 5 | **NPE fix** — `TrackAnalyzer` is a `PlaybackService` property initializer running **before `Service.attach()`**, so `context.applicationInfo` was null. Fixed with a **recomputing** `npuLibDir` getter (never cached — first access is always pre-attach) + `ensureNpuPreflight()` | App opens, crash buffer empty |
| 6 | **Proguard un-stripped** — `-assumenosideeffects` was deleting **every** `Log.i`/`Log.w` in release, including all NPU observability. `v`/`d` still stripped | Old dex: 0 selftest literals. Fixed dex: all present |
| 7 | **Self-test lie removed** — logged `provider=CPU inference=skipped` without ever attempting CPU | Honest lines only |
| 8 | **Gate inversion** — clash now **reorders** instead of vetoing. Severe clash + tempo gap → **beatless bridge → muffle → drop-in**. Clash + tempo match → normal blend. Nothing runnable → decline carve | `TransitionAlternatives.kt` |
| 9 | **Decline carve** — `FilterTransitionVariant.DECLINE`, `rideDecline`. Out LP 7000→400 Hz (shape 0.4) = **800 Hz @ mid**; in HP 2800 held to 0.82 = **438 Hz @ mid**; gains 0.444 / 0.409 @ mid; **0 samples** with both gains > 0.5 while bands overlap (was 91/1001) | `CrossfadeController.kt` + `Vc70DeclineRideAppTest.kt` |
| 10 | **Octave detection** — `detectOctaveRelationship` in `TransitionPolicy.kt`, **report-only**, no tempo rewrite. Raw ratio within 8% of 0.5/2.0 → flags `octave-relationship` in gates | `TransitionAlternatives.kt`, `TransitionPolicy.kt` |
| 11 | **Preserved audio work** — vc68 Task 2A `CountdownStabilizer`, vc69 P0/P1/P2, vc70 Seam A (`openFiltersWhenSilent`), Seam B (`exitBeats`, two-phase muffle/bridge rides), native DROP_IN arm, family registry | untouched |

**In the APK (verified via dex strings):** `DECLINE`, `octave-relationship`, `decline-to-mix`, `before-decline-to-mix`, `declineToMix`, `shouldDeclineToMix`, `TransitionAlternatives`.

---

# 5. OPEN DEFECTS — RANKED BY VALUE

### D1 — `gates:"{policy=drifting beat grid, kind=}"` ⚠️ **TOP PRIORITY**
Fresh telemetry emits this string with an **empty `kind=`**. Nothing in the current agent history explains it. Possible meanings:
- the planner distrusting a grid it now genuinely has
- a stale gate string from the pre-ORT era firing on a `quality=full` analysis
- a genuine drift detector working correctly and nobody documented it

**This is the first thing to investigate.** It sits directly between real analysis and real transitions. If the planner is discarding good grids as "drifting," every fix downstream is fighting a gate that shouldn't be firing.

Investigate: grep `drifting beat grid` in `shared/`. Find who emits it, what `kind` was supposed to contain, and whether it vetoes or warns. Then correlate against telemetry rows carrying `statusA/B: ready`, `qualityA/B: full`, `bpmA == bpmB == 75.949…`.

### D2 — Long-run stability unverified
Never tested past **~7 minutes** of playback. Full queue completion (was mid-queue, 11 items) never confirmed. Memory, cache growth, analysis thread leaks, session lifetime — all unknown.

**Test:** queue a long radio station, let it run 60+ minutes, watch `dumpsys meminfo`, `logcat -b crash`, and telemetry line count.

### D3 — `decline:true` never observed in production
The carve exists and is unit-tested, but **no live sample** has been captured. Short windows only showed blend/bridge paths. The carve may be correct and simply not chosen yet, or unreachable in practice.

**Test:** pull telemetry, grep `decline`, count. If zero across hundreds of transitions, the DECLINE branch is effectively dead code and the gate chain above it needs auditing.

### D4 — Key readings are systematically wrong (relative major/minor)
This is **real, pre-existing, and unfixed**:
- `ATEEZ — Crescendo` displayed **10A**, catalog says **B minor = 11B**
- `CLOSE YOUR EYES — Bubble Tea` displayed **5A**, catalog says **A♭ major = 4B**

Both are **relative major/minor errors** from the native keyfinder — present before vc69, present in vc71. Consequence: **every harmonic decision operates on keys shifted by 3 Camelot steps.** The clash gate, the reorder logic, the bridge-vs-muffle preference — all consuming bad keys.

This may be a bigger cause of harsh transitions than any envelope tuning. Relative-key confusion is the single most common keyfinder failure mode and it is *systematic*, not random.

**Investigate:** native keyfinding source, whether it reports a relative-key alternative, whether a mode-detection heuristic exists. Note the tempo side is correct — `164` and `90` are catalog-exact — so this is key-specific.

### D5 — Octave detection never fired on a real pair
`164 / 90 = 0.549` is **9.8% off** the 0.5 boundary, so the detector correctly declines to flag it. That's working as specified, but no actual 2× or 0.5× pair has ever been observed triggering it. Unverified in practice.

**Test:** find a genuine octave pair in the library, confirm the gate string appears in telemetry.

### D6 — No `mix.dump` correlation tooling
`mix.dump` rows exist (185+) but there's no script to answer "what plan did track X→Y get, and why." Every diagnosis so far has required a fresh subagent reading source. **A small local analysis script over `automix-telemetry.jsonl` would pay for itself in one session.**

### D7 — The device may be touched by another actor
Once observed: install path changed between two checks (`~~ug8azz…` → `~~bmTm…`, 13:12 → 13:28) with **no worker responsible**. Either the user was testing, or something else is on the device. **Verify device state before assuming your own install is what's running.**

---

# 6. NPU WORKSTREAM — FULLY SCOPED, DELIBERATELY PARKED

Plan: **`/home/fhzhkn/Bitchord/docs/NPU_QNN_IMPLEMENTATION_PLAN.md`** (453 lines) — read it, don't re-derive it.

### Honest verdict first
**NPU does not improve beat accuracy. It buys speed and coverage.**

| Metric | NPU vs CPU (Orchard, SM7450) |
|---|---|
| Inference speed | 3.06–3.99× faster (6 s models), 3.1× (30 s) |
| Beat F1 | **−1.2 pp worse** |
| Downbeat F1 | **−5.4 pp worse** |
| 30 s models | **Catastrophic** — 56.59 vs 72.36, rejected |

What improves: pre-arm window, loop capture rate, queue lookahead, cold start.
What does not: beat accuracy, vocal separation quality, key accuracy, render quality.

### The blocker that may kill it outright
**HTP requires QDQ models. BitChord ships dynamic-INT8.** They likely will not dispatch on Hexagon at all. Orchard shipped separate `*_qnn.onnx` assets **and still reverted to CPU INT8 for production.** Budget new calibrated QDQ assets as an unknown-cost phase.

### Ranked fixes for `QNN_DEVICE_ERROR_INVALID_CONFIG` (cheapest first)
BitChord passes **no** device configs — `soc_model` and `htp_arch` both unset — so **no provider option can be the trigger.** It's environmental.

1. **`<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`** in `AndroidManifest.xml`. ORT issue #31353 documents this exact error with this exact fix. **One line, one build, one logcat.** Do this first.
2. **Stop setting `ADSP_LIBRARY_PATH`** in `npu_jni.cpp` — ORT sets it itself if unset, and QAIRT warns ours "may cause the HTP backend to fail."
3. **`useLegacyPackaging=true`** so FastRPC can `fopen()` the skel.
4. **Pin `htp_arch` only** — `soc_model=68` if ever needed, **never `8635`**.
5. Audit Stub files present in `npu-addon/prebuilt`.
6. Bisect `htp_graph_finalization_optimization_mode`.

**Confirmed:** Orchard's provider options are **identical** to BitChord's except `disable_cpu_ep_fallback` (Orchard 1, BitChord 0). The difference was never configuration — it was packaging.

### Integration design — non-negotiable
- **Separate add-on APK** (Orchard-style), **one HTP generation at a time, V73 first**. Never merged into the main APK.
- Isolated runtime dlopened by path. No shared global ORT symbol.
- **Guards that make the "broke analysis" failure structurally impossible:**
  - No `pickFirsts` for `libonnxruntime.so` — **CI grep for it**
  - Exactly one `libonnxruntime.so` per APK — **CI `unzip -l | grep -c`**
  - `.so` inventory assertion
  - Selftest-gated enablement
  - **CPU-works assertion in the same build**
- Acceptance needs **8 QNN proof lines**, including `libQnnHtpV73Skel.so` load, a profiling trace showing `QNNExecutionProvider`, and a successful `disable_cpu_ep_fallback=1` run — **plus** a CPU fallback assertion.

### V73 vs V75 — still undecided
**No `HTP ARCH` line has ever been emitted** because device creation fails before HTP graph finalization. Do not fabricate a choice. Settle it only when a session actually opens.

### Effort
- **Phase 0–1** (CPU guard + restore, P0): **1.5 days**
- **Phase 2–5** (add-on to proven): **7–13 days**, widest variance in the QDQ asset phase

### Standing recommendation
**Do not start NPU until D1–D4 are resolved.** NPU has never once improved a transition, and the last attempt cost the entire analysis engine. It is an accelerator for a working pipeline, not a fix for a broken one.

---

# 7. GUARDRAILS — THE TRAPS THAT COST US THIS SESSION

1. **Never swap `libonnxruntime.so` globally.** The `pickFirsts` + `jniLibs.srcDir` combination silently replaced the full AAR with a 13 MB reduced build and killed **both** QNN **and** CPU analysis for three versions (vc69, vc70, vc70-npufix2). Every tempo, key, and transition decision in that span ran on **no beat grid and no vocal mask**. This is the single most expensive mistake of the session.

2. **Reduced ORT is not worth it.** 33 MB → 13 MB saved 20 MB on a 108 MB APK, and broke everything. `create_reduced_build_config.py` parses **raw protobuf** and cannot see ops ORT's optimizer introduces at load time (`MatMulIntegerToFloat`, `DynamicQuantizeMatMul`). If a reduced build is ever needed, the path is `convert_onnx_models_to_ort` → `create_reduced_build_config.py -f ORT`. **Recommendation: do not use a reduced build at all.**

3. **Prove the thing underneath before building on top.** vc69 added NPU on top of a build-config change without verifying the swap preserved analysis. That's the root process failure — larger than any individual bug.

4. **Absence of errors is not success.** Require **positive** evidence: a `mel … infer …` line, `bars=N`, `vocal mask N frames`. Three rounds of "no error appeared" were all wrong.

5. **Never claim audio quality without the user's ears.** Every audio claim in this session is unverified until they listen. The user said "not bad" about vc71 — that's the only real verdict in the log.

6. **One Gradle invocation at a time.** See §2.3.

7. **A single-item VIEW play never triggers analysis.** See §3.

8. **`bpm=` lines don't exist in release builds.** Use telemetry.

9. **`R128Limiter` stays deleted** — it could not live in the render path without a second offline renderer. Don't resurrect it.

10. **Check `adb devices` before debugging.** The device dropped off USB once.

11. **Check whether another actor touched the device.** Install paths changed unexplained once.

12. **Do not re-litigate resolved decisions.** Full ORT restored, QNN disabled, R128 deleted, separate add-on APK for NPU, clash reorders rather than vetoes. These are settled. Reopening them costs a cycle each.

---

# 8. FILE MAP

### Core planner / policy
```
shared/src/commonMain/kotlin/.../smart/TransitionAlternatives.kt   gate inversion, DECLINE carve math, FAMILIES registry, select()
shared/src/commonMain/kotlin/.../smart/TransitionPlanner.kt        plan branch selection, decline fallback (~:1914), clash gate (~:364)
shared/src/commonMain/kotlin/.../smart/TransitionPolicy.kt          tempo bands (:75 allowed, :89 audible), detectOctaveRelationship
shared/src/commonMain/kotlin/.../smart/TransitionEvidence.kt        gate strings → mix.dump
shared/src/commonMain/kotlin/.../smart/DjMix.kt
```

### Renderer
```
app/src/main/java/.../playback/CrossfadeController.kt   rideDecline, rideMuffle, rideFilterSweep (~:6207),
                                                        openFiltersWhenSilent, exitBeats, bar label (~:5421)
```

### Analysis
```
app/src/main/java/.../playback/smart/TrackAnalyzer.kt   npuLibDir getter, ensureNpuPreflight, analyze()
app/src/main/java/.../playback/smart/NpuSelfTest.kt     honest self-test
app/src/main/java/.../playback/smart/NpuNative.kt
shared/src/jvmSharedMain/kotlin/.../smart/NpuSupport.kt   QNN_ENABLED = false  ← the switch
shared/src/jvmSharedMain/kotlin/.../smart/AnalysisStore.kt  SCHEMA_VERSION=4, PIPELINE_PROVENANCE
shared/src/commonMain/kotlin/.../smart/BeatTracker.kt    tempo median (:303), NO octave disambiguation
shared/src/commonMain/kotlin/.../smart/TrackAnalysis.kt  (:54) no half-time candidate field
```

### Build
```
app/build.gradle.kts          jniLibs/pickFirsts region — DO NOT restore srcDir
app/proguard-rules.pro        Log.i/w preserved, Log.d/v stripped
build.gradle.kts:44-45        version 71 / "2.2.3-djmix29"
npu-addon/prebuilt/           137 MB QAIRT libs, dormant, gitignored
```

### Evidence & docs
```
/home/user/Bitchord/evidence/bitchord-device-evidence/vc71-device/   vc71 proof
/home/user/Bitchord/evidence/bitchord-device-evidence/vc70-device/   npufix2 logs, analysis diagnosis
/home/user/Bitchord/docs/NPU_QNN_IMPLEMENTATION_PLAN.md              the NPU plan
/home/user/Bitchord/docs/session/BITCHORD_FORENSICS_164_90_MESSY_20261115.md
/home/user/Bitchord/docs/session/BITCHORD_PLAN_SWAP_ABORT_TRACE_20261115.md
/home/user/Bitchord/docs/ORCHARD_AUTOMIX_ANALYSIS.md
/tmp/orchard-analysis          Orchard source clone, read-only reference
```

---

# 9. HISTORY — WHY EACH VERSION EXISTS

| Version | What happened |
|---|---|
| **vc68** | Worked. User's last good baseline. Task 2A `CountdownStabilizer`. Muffle return snapped. |
| **vc69** | Reduced ORT replaced full AAR. **Analysis died silently.** Demuffle abrupt, plan-abort filter snap. NPU inert. |
| **vc70** | Crashed on launch (`nativeLibraryDir` NPE pre-`attach()`). Then opened but analysis still dead. Proguard deleting all logs. |
| **vc70-npufix2** | NPE fixed, logs restored, **first specific NPU error ever**: `MatMulIntegerToFloat` missing. Still no analysis. |
| **vc71** | **Full ORT restored. Analysis alive. Gate inversion + decline carve. QNN off. QAIRT out. 108 MB → 67 MB.** |

The through-line: **vc69's build-config change broke the foundation, and three versions of audio tuning happened on top of an empty analyzer.** Every "the mix sounds wrong" report from vc69 onward was partly downstream of no beat grid and no vocal mask.

---

# 10. RECOMMENDED NEXT CYCLE — IN ORDER

**Cycle 1 — understand the pipeline you now have (no code changes)**
1. Pull `automix-telemetry.jsonl`, review ~200 recent `mix.dump` rows.
2. Crack **D1** `drifting beat grid` — who emits it, what `kind=` was meant to hold, does it veto.
3. Count `decline:true` occurrences (**D3**).
4. Write a small local telemetry-correlation script (**D6**) — it will pay for itself immediately.
5. Verify the true `git diff --cached` figure and record it.

**Cycle 2 — long-run stability (D2)**
60-minute playback, memory + crash + telemetry growth. No code changes unless something breaks.

**Cycle 3 — the key bug (D4)**
Relative major/minor systematic error. This is the highest-value *fix* available and it is currently unowned. Every harmonic decision consumes a key shifted by 3 Camelot steps.

**Cycle 4 — carve validation (D3)**
If DECLINE never fires in production, the gate chain above it needs auditing before any further tuning.

**Cycle 5+ — NPU, only if 1–4 are clean**
Start with the one-line `<uses-native-library libcdsprpc.so>` manifest fix. One build. One logcat. If `HTP ARCH` appears, we learned something real. If the same `INVALID_CONFIG` returns, stop and reassess.

---

# 11. WHAT REMAINS UNVERIFIED — STATE THIS HONESTLY

- **Audio quality per-track.** "Not bad" is real but coarse. The bridge, the carve, the muffle return, the one-kick handoff — none individually confirmed by ear.
- **Long-run stability** past ~7 min (§D2).
- **Full queue completion** — was mid-queue at handover.
- **`decline:true` in production** — never observed (§D3).
- **`drifting beat grid` meaning** — unexplained string in live telemetry (§D1).
- **Key accuracy** — known wrong, unfixed (§D4).
- **Octave detection in practice** — never triggered on a real pair (§D5).
- **Section/loop correctness against ground truth** — never validated.
- **V73 vs V75** — undecided, no `HTP ARCH` line has ever been emitted.
- **Whether dynamic-INT8 assets dispatch on HTP at all** — probably not, needs QDQ.
- **App-module unit tests after the ORT restore** — `:shared:jvmTest` 430/0 was re-run, `:app:testProdDebugUnitTest` 1346/0/1 was green in the decline-fix cycle but **not re-run against the final vc71 build**. Worth one clean gate run.

---

# 12. THE ONE-LINE SUMMARY

**vc71 is a working foundation — real beat grids, real vocal masks, first time in four versions. Everything after this is tuning a pipeline that actually has data in it. Do not touch the ORT packaging, do not start NPU, do not run two builds. Fix the key bug, crack the drifting-grid gate, verify it runs an hour, and let the user's ears be the only verdict that counts.**

absolute WsThanhDieu to whatever orchestrator picks this up — bench is warm, tools sorted, and the next man doesn't have to rediscover why vc69 was silent.

---

## Context — User Prompts

### Prompt for Handover 1 (Seq 1783, timestamp 1794731841587)
> (Not directly visible, but implied: user asked for handover to be written to `/home/user/Bitchord/docs/HANDOVER_vc71.md`)

### Prompt for Handover 2 (Seq 1692, timestamp 1794731739170)
> "Its not bad. this vc71? yea its not bad. now: write complete handover for whats next for next agentic ai orchestrator-workers"

### Prompt before Handover 1 (Seq 1703, timestamp 1794731794255)
> "save to a handover md in ~/BitChord"

---

*End of extracted handovers.*