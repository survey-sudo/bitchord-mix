# BitChord Handover — vc73, Early-Exit (4 Causes) + Silent-Intro Cue + Beatmatch Wiring + umxhq Stems + Mid-Dip Rides

---

# 0. What this document is

A cold-start brief for an orchestrator + worker fleet picking up BitChord after vc73. It assumes no memory of prior sessions. Read it fully before touching the tree — most of the cost of the last six versions came from agents skipping the constraints in §2.

**One-line state: vc73 is the first build since vc68 to make Song A stop ending 40–60 s too early, Song B stop blending into silence, beatmatch actually engage on genuine 3–8 % pairs, and the muffle/demuffle underwater sound leave — all in one cycle. Its build string is `2.2.3-djmix31`. Nothing on this build has been heard by the user yet — §11 lists every track pair that must be played before a single sentence about audio quality is allowed.**

---

# 1. Status

| Item | Value |
|---|---|
| Version | **vc73 / `2.2.3-djmix31`** |
| APK | `app-prod-arm64-v8a-release.apk`, **67,430,938 B** |
| SHA-256 | `e76cb3ce0d1e1e8704077ec13d069e78534f7dec39abd94158e477a24492cfc0` |
| Cert (oldkey) | `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` |
| `libonnxruntime.so` | 34,208,672 B, count **1** — full Maven AAR 1.31.0 |
| `libbitchord_analysis.so` | 486,288 B |
| `libbitchord_loopmix.so` | 438,616 B |
| QNN libs in APK | **zero** `.so` (only `assets/licenses/Qualcomm-AI-Runtime-QNN-NOTICE.txt`) |
| Build | **`BUILD SUCCESSFUL in 21m04s`, exit `0`** — full log in `vc73-device/build-vc73.log` (57,083 B) |
| Device | Poco F6, SM8635, serial `1439ebea` — **NOT attached at handover time** |
| Package | `com.music.bitchord.loopfix` |
| Launcher | `com.music.bitchord.loopfix/com.music.bitchord.LauncherDefault` ← **not** `.MainActivity` |
| Analysis | **Alive, device-proven at vc71/vc72 lineage**; vc73 on-device acceptance **PENDING USB RECONNECT** |
| User verdict | **"not bad"** on vc71 — **vc72 has no verdict yet; vc73 has no verdict yet** |
| Tests | `:shared:jvmTest` **443/0/0/0** (53 XML files) · `:app:testProdDebugUnitTest` **1355/0/0/1 skip** (`GeniusTest`) — fresh XML, re-measured |
| Git | `HEAD=dc78e0d449d7bdf51e821f8e8c1de223bff8bf5a`, no commits, all work staged/unstaged |
| Evidence | `/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/vc73-device/` — APK copy (byte-identical) + `build-vc73.log` + `vc73-report.md` |

**Audio is entirely unverified on vc73.** The user's verdict on vc71 ("not bad") does not transfer. Every audio & on-device item is marked **UNVERIFIED UNTIL USER EARS HEAR IT** in §11.

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

vc73 ran the same way: `BUILD SUCCESSFUL in 21m04s`, exit `0` (`/tmp/build-vc73.log` + `/tmp/build-vc73.exit` on bk-android; log pulled to `vc73-device/build-vc73.log`).

## 2.5 Git invariants — RE-PINNED

Re-measured Phase 1–2 on the **real tree** (`bk-android:/data/workspace/bitchord-task2/work/BitChord`):

- `HEAD = dc78e0d449d7bdf51e821f8e8c1de223bff8bf5a` ✅
- `git diff --cached | wc -l` = **6085** ✅
- **No commit, no reset, no checkout.** All work lives in the worktree/index.
- New files: **`git add -N` only.**
- Version lives in root `build.gradle.kts`, **lines 44–45**: `73` / `"2.2.3-djmix31"`

The "6085 vs changed-lines" counting distinction from vc72 §2.5 still holds: 6085 counts *unified-diff lines* (context lines included); changed-lines counts additions + deletions only. Both figures are correct; they count different things. Re-pin at the start of the next cycle, do not re-litigate.

## 2.6 Device and ADB

- ADB: `/home/hfzhkn/.local/bin/adb`
- Device: `adb -s 1439ebea`
- The device **dropped off USB once** mid-session for ~6 min (vc71 era). If a command hangs, run `adb devices` before assuming the command was wrong.
- Once, the install path changed between two checks with **no worker responsible** (`~~ug8azz…` → `~~bmTm…`, 13:12 → 13:28). Either the user was testing or another actor touched the device. **Do not assume the running process is yours** — check `dumpsys package` versionCode before trusting a log.
- **At the time of this handover the device was NOT attached** (`adb devices -l` empty, no matching vendor ids in `lsusb`). On-device vc73 acceptance is therefore **PENDING** — the complete ready-to-run script is §12 below and in `vc73-device/vc73-report.md` §8.

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

Run this against vc73 after USB reconnect (script + `SINCE_TS` + telemetry-analysis steps in `vc73-report.md` §8).

## Positive evidence required

Absence of errors is **not** proof. Require lines that can only exist if inference ran:

```
BitChordNPU disabled (QNN_ENABLED=false): CPU execution provider
Discarding unreadable analysis … schema 4        ← vc72 schema 5 forces one pass; BENIGN if seen once
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

Post-vc73, pull it and run `docs/tools/analyze_telemetry.py --since-ts $SINCE_TS` to measure `*A` vs `*B` keys, `steps=`, `drifting beat grid`, plus idle-loop / build-missed-splice / unbeatmatched-classic vitals (§10, Cycle 1).

---

# 4. What is fixed and proven in vc73 — code-complete, device-pending

vc73 is four fronts (A–D) stacked in one cycle. The `file:line` map below **is** the deliverable — read it before re-opening any of these files.

## A. Song A no longer ends way too early (4 stacked causes fixed)

| # | Cause | Fix / location |
|---|---|---|
| A1 | `mixPlan`/`dropPlan` outro floor 36 s, `firstClean` strict raw-`seamDelta` sort let a marginally flatter verse bar ~55 s before EOF outrank clean outro loops near the end | `LoopMix.kt:480` `mixPlan` `outroFloor` `(outDuration-36.0)` → `(outDuration-32.0)`; `LoopMix.kt:721-731` `firstClean` outgoing search now sorts by `(start >= outroFrom ? 0 : 1, floor(seamDelta / 0.15))`; `LoopMix.kt:1277,1287` `dropPlan` gets the same `outroFrom` floor; `effectiveOutroFrom = Double.POSITIVE_INFINITY` when `shift` preserves forward `shiftToNextBar` order |
| A2 | `minSec` `minOf` let a `musicEnd-40` branch *lower* the floor, admitting exits 40–55 s early | `CuePoints.kt:59` now `if (effectiveDuration > 90.0) maxOf(dur*0.78, musicEnd-30.0).coerceAtMost((musicEnd-8.0).coerceAtLeast(0.0))`, else the old short-track form (64 s fixtures unchanged); same fix `TransitionPolicy.kt:636` `rankMixOutCandidates`; `CuePoints.kt:132` early-outro exception restricted to `OUTRO_START`/`OUTRO_PHRASE`/`FADE_START` + `isGenuineQuietOutro` — never `VERSE_END` |
| A3 | `BRIDGE_END`/`VERSE_END` scored `0.95/0.92/0.82` above `CONTENT_END`, cutting off a whole final chorus/outro | `PhrasePlanner.kt:612,712` when `cutsBars > 8 && cutsSungBars > 2 && last.endBar != releaseBar` they fall to `0.55` / `kindBonus 0.0` so `CONTENT_END`/`OUTRO_START` wins |
| A4 | `rampBars` (0.5 %/bar, up to 16 bars) + `exitBars` pulled the fade start up to 45–60 s before `contentEnd` | `TransitionMoves.kt:207` `CONTENT_END` cap `if (exit.kind == CONTENT_END && !looping && (ramp + exit) * bar > 28.0)` → `exit = minOf(exit, 8)`, `ramp = minOf(ramp, maxOf(0, 8 - exit))`, `ramp = minOf(ramp, 6)`; `PhraseConfig.TEMPO_STEP_PER_BAR` stays `0.005` (a global cap broke `Djmix26SharedTest:669`); `TransitionPlanner.kt:1787` `clampToMixWindow` anchors to `transitionEnd` inside the file (`anchor = if (transitionEnd > 1.0 && transitionEnd < length) transitionEnd else length`); `CrossfadeController.kt:1838` finally passes `mixWindow = true` |

## B. Song B no longer blends into a silent intro

| # | Cause | Fix / location |
|---|---|---|
| B1 | No `firstAudibleTime` concept — cue taken from file top including silence | `LoopMix.kt:1854` new `firstAudibleTime(analysis)`: 60th-percentile `typical` over `levelWindows`, threshold `maxOf(SILENT_RMS, typical*0.12)`; else `energyCurve` `peak*0.08`; returns `0.0` when audible from the top; `LoopMix.kt:1884` `isSilentAt`; `LoopMix.kt:1902` `cueOf` `audible = maxOf(audibleStartTime, firstAudibleTime)`, `firstDown`/`firstBeatInList` skip silent instants; `LoopMix.kt:502-506` `inFloor` for `inPick` search |
| B2 | `CuePoints.entries` never consulted `SectionFn.SILENCE` / `audibleStartTime` | `CuePoints.kt:329` `entries(map, analysis)` skips leading `SectionFn.SILENCE` and advances `HEAD` past `firstAudibleTime - 0.15`; `PhrasePlanner.kt:153,212` threads `inAnalysis` through `select`/`choose` |
| B3 | Incoming-start resolution took `firstBeat` unconditionally | `TransitionPlanner.kt:683` `incomingStartPoint` = `maxOf(explicit audible > 0.05, firstAudibleTime)`, then `pickupTime`, then `firstBeat` only if both curves are empty; `TransitionAlternatives.kt:89,151,443` same for `incomingCueOf` / beatless-bridge |
| B4 | `levelWindow` empty on the head/derive path, so B1 had no ceiling | `TrackAnalyzer.kt:1675` `levelWindow` now also populated on the head/derive path, `analyzeHead` returns `levelWindows` |

## C. Beatmatching actually happens

| # | Cause | Fix / location |
|---|---|---|
| C1 | `planMuffle`/`planDropIn`/`planBeatlessBridge` never applied a beatmatch rate | `TransitionAlternatives.kt:243` `muffleMatchedRate` = `incomingRateBetween(out.bpm, inn.bpm)` when both confidences `>= MIN_BEATMATCH_CONFIDENCE` and the rate is inside `AUDIBLE_MIN_RATE..AUDIBLE_MAX_RATE` (`0.94..1.09`), else `1.0`; applied to `planMuffle` / `planDropIn` / `planBeatlessBridge`. (Illustrative old examples: `120/109 = 1.1009` stays unity; `164/90` folds to `0.911` stays unity; genuine 3–8 % pairs now match.) |
| C2 | Sequential `OUT` then `IN` decode — `outgoing decode 0 ms over` / `build-missed-splice` arm deaths dropped loops into unbeatmatched classic | `LoopMixSession.kt:132` `DECODE_EXECUTOR` (2-thread daemon) + `CompletableFuture.supplyAsync` — `OUT` and `IN` regions decode **concurrently** |
| C3 | `startLoopAlignment` settle too loose for short overlaps; `finishLoop` measured a cancelled job (`vc71` `-911.22 ms` failure) | `CrossfadeController.kt:5009` settle `((lead + 0.15)*1000).coerceIn(250L, 2500L)` (was `(lead + abs(error) + 0.4)*1000.coerceIn(300..12000)`); `CrossfadeController.kt:5165` `finishLoop` samples `offsetNow` → `recordAligned` when `loopAlignJob` is still active or `alignedErrorMs` is NaN, so `mix.verify` measures real phase; `CrossfadeController.kt:4165,4209` pass `inAnalysis = inn` into `PhrasePlanner.select` |

## D. Muffle/demuffle gone: real `umxhq` separation + clean ≤200 Hz bass swap

| # | Cause | Fix / location |
|---|---|---|
| D1 | `umxhq` stems bundled but gated off (`automixWindowedStemsEnabled = false`) | `AppSettings.kt:289` default `false → true`; `TrackAnalyzer.kt:255-268` `canUseWindowedStems` = `!isLowRamDevice && (totalMem >= 6 GB || memoryClass >= 256)` |
| D2 | 44.1 k round-trip `floor(in*ratio)` broke `split.size == outRegion.size`, silently discarding every separation | `VocalTracker.kt:326` `separateWindow` pads/trims back to `left.size`; `VocalTracker.kt:340` new `separateBothWindows` returns vocal **and** instrumental stems from one ONNX pass; `TrackAnalyzer.kt:1869` `separateTransitionBothWindows` wraps it under `modelLock` |
| D3 | Without stems the pocket/carve/clash muffle stayed underwater | `LoopMixSession.kt:231,616,671,686,711`: stems → `OUT` = `inst + vocalGain*vocal` with cosine `1.0 → 0.08` vocal ramp, `IN` vocal held `0.10 → 1.0`, **`outStemSeparated` skips the muffle/carve/clash LP entirely**; even without stems: `beatBlendPocket` lost `20000→1800 Hz` LP and `250→20 Hz` HP; `carve` floor `1200 → 5500 Hz`, incoming HP `650 → 220 Hz`; clash `2800 → 20000→5000 Hz` over `[handoffAt..releaseAt]` with HP `20 → 220 Hz` |
| D4 | No narrow vocal-band tool — only broadband LP/HP | `TransitionFilterProcessor.kt:44,143-156,208,270,360,645` zero-allocation RBJ peaking EQ at `1500 Hz`, `Q = 0.707`, glide across `GLIDE_FRAMES = 64`, bypassed above `-0.05 dB`, `setMidGainDb(-9f..0f)`, state only in `configure()`, applied in `process()` (float) and `queueInput()` (16-bit); `open()`/`onReset()` reset it; `isFiltering` includes it; `TransitionFilters.incomingMidDb/outgoingMidDb` added and swapped by `handedOff` (`CrossfadeController.kt:5954-5960`, `PlaybackService.kt:1992,2087`) |
| D5 | Classic rides rode underwater LP / tinny HP for bass swap, vocal separation, filter sweep | `CrossfadeController.kt` `rideBassSwap` `6271`, `rideVocalSeparation` `6322`, `rideFallbackBassHandoff` `6386` now ride **`OPEN_HZ` LP + `bassCutoff(≤200 Hz)` HP only** + vocal-band `outgoingMidDb` dip (`-4.5 * clash`, `-5.5 * amount * depth`, `-5.5 * clash * p`); `rideFilterSweep` `6127`: `FILTER_ENTRY_HZ 7000 → 9500`, `FILTER_FLOOR_HZ 1500 → 2800`, `FILTER_SWEEP_SHAPE 1.8 → 1.1` (midpoint ≈ `5379 Hz`, still inside `Vc70DeclineRideAppTest`'s `3800..5500`), incoming HP bass-only + `-5.0 * clash * p` mid dip; `rideMuffle`/`rideDecline`/`rideBeatlessBridge`/`rideDropIn`/`openFiltersWhenSilent` reset mid to `0f` |
| D6 | Planner fallback variant *could* have been the fix — it was not | `TransitionPlanner.kt:423-454` `fallbackVariantFor` and `:2287` `resolvedFilterVariant` **restored to HEAD** so `AdvancedAutomixPlannerTest` passes unmodified — the audible fix moved entirely into the rides. `Djmix25AppTest.kt:212` `windowedStemsFlagDefaultsOff` → `DefaultsOn` (`assertFalse → assertTrue`): the default genuinely flipped, so the old assertion encoded the old default. |

Verified in the shipped dex / APK: full ORT AAR preserved, zero QNN `.so`, `libbitchord_loopmix.so` 438,616 B retained, `2.2.3-djmix31` badging confirmed.

---

# 5. Open defects, ranked (post-vc73)

## A — RE-TEST: Song A early-exit (4 causes)
All four causes fixed in code. **Unproven on device — and unproven by ear.** Test: play `Beautiful` → `Never Let Go`; check the outgoing track plays to its real end (no 40–60 s early fade/exit). If A1–A4 are all correct, Song A should now end at `contentEnd`/outro, not at an early verse bar or early cue.

## B — RE-TEST: Song B silent-intro blend
`firstAudibleTime` + `SectionFn.SILENCE` consult + incoming-start resolution fixed in code. **Unproven on device.** Test: play `Drip` → `UP`; check the incoming cue lands on an audible intro (no long silent blend-in). If `audibleStartTime` and `firstAudibleTime` both agree at 0.0 on a loud intro, the fix is a no-op by design — no regression risk there.

## C — RE-TEST: beatmatching on genuine 3–8 % pairs
`muffleMatchedRate` + concurrent decode + loop-align settle fixed in code. **Unproven on device.** Test: play `This Is What You Came For` → `One Kiss`; listen for a real beatmatch (not unbeatmatched classic). Telemetry should show matched rates inside `0.94..1.09` on genuine pairs, unity only on out-of-range pairs (`120/109`, `164/90` illustrative).

## D — RE-TEST: muffle gone, bass swap clean
`umxhq` two-stem separation + `≤200 Hz` bass swap + mid dip fixed in code. **Unproven by ear — this is the highest-risk claim in the cycle.** Test: play `Technologic` → `Guess` and any stem-less fallback pair; no underwater LP, no tinny HP, no broadband muffle. The `outStemSeparated` skip-path and the `1500 Hz` peaking-EQ glide budget are the two things most likely to sound wrong if they are wrong.

## D1/D3/D4 carry-forward — RE-TEST from vc72 (still no device telemetry)
vc72's key system (`full-ort-1.31.0-cpu-keyfix1`), confidence parity, and `fittedBpm` edge fix have **still never seen device telemetry**. vc73 does not re-litigate them; it inherits the debt. Test: same telemetry vitals as vc72 §5 (`*A` vs `*B` share, `steps=` presence, `drifting beat grid` on 75.949-BPM rows, `decline:true` counts).

## D2 — Long-run stability still never tested
Nothing has been verified past ~7 minutes on any build. Full queue completion never confirmed. **Test:** queue a long radio station, run 60+ minutes, sample `dumpsys meminfo`, `logcat -b crash`, and telemetry line count every 10 min. No code changes unless something breaks.

## D5 — Octave detection still never fired on a real pair
`164 / 90 = 0.549` is 8.86% off 0.5 (within the 8%-tolerance detector's correct silence). **Test:** queue genuine double/half-time pairs. Unit-wired; no real triggering observed yet.

## D7 — Possible concurrent actor on the device
See §2.6. Verify via `dumpsys package` versionCode before trusting any observation. (At handover there was no actor to watch — there was no device at all.)

---

# 6. NPU workstream — scoped, deliberately parked

Full plan: **`/home/hfzhkn/Bitchord/docs/NPU_QNN_IMPLEMENTATION_PLAN.md`** (453 lines). Read it; do not re-derive it. **Unchanged from vc71/vc72. Read the standing recommendation in HANDOVER_vc71.md §6.7 again: do not start NPU until the audio cycle is clean — and the audio cycle now includes re-proving A–D on device (§5/§11).**

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

**No `HTP ARCH` line has ever been emitted**, because device creation fails before HTP graph finalization. Do not fabricate a choice. Settle it only when a session actually opens. (`libbitchord_loopmix.so` is a loop-mix native lib, not an HTP generation marker — do not conflate the two.)

## 6.6 Effort

- **Phase 0–1** (CPU guard + restore, P0): **1.5 days**
- **Phase 2–5** (add-on to proven): **7–13 days**, widest variance in the QDQ asset phase

## 6.7 Standing recommendation

**Do not start NPU until A–D are resolved on device + by ear.** NPU has never once improved a transition, and the last attempt cost the entire analysis engine. It is an accelerator for a working pipeline, not a fix for a broken one.

---

# 7. Guardrails — the traps that cost this project six versions

1. **Never swap `libonnxruntime.so` globally.** The `pickFirsts` + `jniLibs.srcDir` combination silently replaced the full AAR with a 13 MB reduced build and killed **both QNN and CPU analysis** for vc69, vc70, and vc70-npufix2. Every tempo, key, and transition decision in that span ran on **no beat grid and no vocal mask**. This is the single most expensive mistake of the project.
2. **Reduced ORT is not worth it.** 33 MB → 13 MB saved 20 MB on a 108 MB APK and broke everything. `create_reduced_build_config.py` parses **raw protobuf** and cannot see ops the optimizer introduces at load time (`MatMulIntegerToFloat`, `DynamicQuantizeMatMul`). If a reduced build is ever genuinely needed, the only correct path is `convert_onnx_models_to_ort` → `create_reduced_build_config.py -f ORT`. There is no `--include_all_operators` flag; **full is the default**. Recommendation: do not use a reduced build at all.
3. **Prove the layer underneath before building on top.** vc69 added NPU on top of a build-config change without verifying the swap preserved analysis. That is the root process failure — larger than any individual bug.
4. **Absence of errors is not success.** Require positive evidence: `mel … infer …`, `bars=N`, `vocal mask N frames`. Three rounds of "no error appeared" were all wrong, in both directions.
5. **Never claim audio quality without the user's ears.** Every audio claim this session is unverified until they listen. "Not bad" about vc71 is the only real verdict on record — and vc73 changes how every transition is planned (early-exit floors, silent-intro cues, beatmatch rates, stem separation, and ride filters all changed this cycle).
6. **One Gradle invocation at a time.** §2.3.
7. **A single-item VIEW play never triggers analysis.** §3.
8. **`bpm=` lines do not exist in release builds.** Use telemetry.
9. **`R128Limiter` stays deleted.** Do not resurrect it.
10. **Check `adb devices` before debugging a hang.** At this handover the device was absent entirely.
11. **Check whether another actor touched the device** before trusting runtime state.
12. **Do not re-litigate settled decisions.** Full ORT restored, QNN disabled behind `QNN_ENABLED`, R128 deleted, separate add-on APK for NPU, clash reorders rather than vetoes, no tempo rewrite for octave detection, `PhraseConfig.TEMPO_STEP_PER_BAR = 0.005` (global cap breaks `Djmix26SharedTest:669`), planner fallback variant restored to HEAD (audible fix lives in the rides). Each reopening costs a cycle.
13. **Do not touch `npu-addon/prebuilt/`** when freeing disk.
14. **Version discipline:** this build is `73 / 2.2.3-djmix31` specifically because vc73 re-proves the early-exit floors, silent-intro cue, beatmatch wiring, and stem separation on device. Distinguish builds in telemetry by version, not by filename alone.
15. **Do not "fix" early-exit with a single lever.** vc73 needed four independent fixes (A1–A4) stacked, because each one independently stole global-end time. Any future early-exit regression must be re-diagnosed against all four, not the last one touched.

---

# 8. Version history — why each build exists

| Version | What happened |
|---|---|
| **vc68** | Worked. User's last good baseline. Task 2A `CountdownStabilizer`. Muffle return still snapped. |
| **vc69** | Reduced ORT replaced the full AAR. **Analysis died silently.** Demuffle abrupt, plan-abort filter snap. NPU inert. |
| **vc70** | Crashed on launch — `nativeLibraryDir` NPE pre-`attach()`. Once patched, opened but analysis still dead. Proguard deleting every log. |
| **vc70-npufix2** | NPE fixed, logs restored, **first specific NPU error ever surfaced**: `MatMulIntegerToFloat` missing. Still no analysis. |
| **vc71** | **Full ORT restored, analysis alive. Gate inversion + decline carve. QNN off. QAIRT out. 108 MB → 67 MB. User: "not bad."** Key bug D4 still open. |
| **vc72** | **Keyfix1 cycle: C++ peak-picked chroma + zero-mean Pearson (24 keys) + tonal gate; `SCHEMA_VERSION=5`/`full-ort-1.31.0-cpu-keyfix1`; regional key-confidence parity + M6 gate; `fittedBpm` edge+jitter fix for 75.949-BPM grids; truthful loop `lateStepReason`; telemetry tool. Tests 435/0/0/0 + 1347/0/0/1. On-device acceptance PENDING (no USB at handover).** |
| **vc73** | **Four-front cycle: Song A early-exit (4 causes: outro floor + firstClean sort, minSec minOf, BRIDGE_END/VERSE_END scoring, CONTENT_END cap + clamp anchor + mixWindow); Song B silent-intro cue (firstAudibleTime + SILENCE consult + incomingStartPoint); Beatmatch wiring (muffleMatchedRate 0.94–1.09 + concurrent OUT/IN decode + loop-align settle/finish); Muffle replacement (umxhq 2-stem separation gated on by default + 1500 Hz peaking-EQ mid dip + OPEN_HZ/bassCutoff rides, planner fallback restored to HEAD). Tests 443/0/0/0 + 1355/0/0/1. On-device + listening acceptance PENDING (no USB at handover).** |

The through-line: **vc69's build-config change broke the foundation, and three versions of audio tuning happened on top of an empty analyzer.** vc70's, vc71's tuning happened on a live analyzer with systematically wrong keys; vc72 fixes the key system at its root but had not yet had its telemetry (or its listening); vc73 stacks four fixes on that root but has had neither telemetry nor listening either — keep that epistemic rank in mind with every claim made from now on.

---

# 9. File map

## Planner / policy (shared)
```
shared/.../smart/LoopMix.kt                         mixPlan outroFloor (:480), firstClean sort (:721-731),
                                                  dropPlan floor (:1277,1287), firstAudibleTime (:1854),
                                                  isSilentAt (:1884), cueOf (:1902), inFloor (:502-506)
shared/.../smart/CuePoints.kt                       minSec floor (:59), early-outro exception (:132),
                                                  entries SILENCE consult (:329)
shared/.../smart/PhrasePlanner.kt                   BRIDGE_END/VERSE_END scoring (:612,712),
                                                  inAnalysis threading (:153,212)
shared/.../smart/TransitionMoves.kt                 CONTENT_END ramp+exit cap (:207)
shared/.../smart/TransitionPolicy.kt                rankMixOutCandidates minSec fix (:636)
shared/.../smart/TransitionPlanner.kt               incomingStartPoint (:683), clampToMixWindow anchor
                                                  (:1787), fallbackVariantFor (:423-454), resolvedFilterVariant
                                                  (:2287) — both RESTORED TO HEAD
shared/.../smart/TransitionAlternatives.kt          muffleMatchedRate (:243), incomingCueOf (:89,151,443)
shared/.../smart/HarmonicMix.kt                     fittedBpm / fitBeatSegments (vc72 D1, carried forward)
shared/.../smart/TrackAnalysis.kt                   exit/entryKeyConfidence + regional parity (vc72 D3a, carried fwd)
```

## Renderer (app)
```
app/src/main/java/.../playback/CrossfadeController.kt   mixWindow (:1838), inAnalysis (:4165,4209),
                                                          startLoopAlignment (:5009), finishLoop (:5165),
                                                          rideFilterSweep (:6127), rideBassSwap (:6271),
                                                          rideVocalSeparation (:6322),
                                                          rideFallbackBassHandoff (:6386),
                                                          handedOff mid swap (:5954-5960)
app/src/main/java/.../playback/LoopMixSession.kt        DECODE_EXECUTOR (:132), OUT/IN mix + skips
                                                          (:231,616,671,686,711)
app/src/main/java/.../playback/smart/TrackAnalyzer.kt   canUseWindowedStems (:255-268), levelWindow
                                                          (:1675), separateTransitionBothWindows (:1869),
                                                          analyzeHead levelWindows
app/src/main/java/.../playback/smart/VocalTracker.kt    separateWindow pad/trim (:326),
                                                          separateBothWindows (:340)
app/src/main/java/.../playback/smart/TransitionFilterProcessor.kt  mid peaking EQ (:44,143-156,208,270,360,645)
app/src/main/java/.../playback/PlaybackService.kt       mid swap (:1992,2087)
app/src/main/java/.../playback/smart/AppSettings.kt     automixWindowedStemsEnabled default (:289)
```

## Analysis (carried forward from vc72)
```
native/analyzer/audio_analysis.cpp                        keyfinder (:332-336, :385, :462-546) — vc72 D4, carried fwd
app/src/main/java/.../playback/smart/TrackAnalyzer.kt   ensureNpuPreflight, analyze()
app/src/main/java/.../playback/smart/NpuSelfTest.kt
app/src/main/java/.../playback/smart/NpuNative.kt
shared/src/jvmSharedMain/kotlin/.../smart/NpuSupport.kt          QNN_ENABLED = false  ← the switch
shared/src/jvmSharedMain/kotlin/.../smart/AnalysisStore.kt       SCHEMA_VERSION=5, PIPELINE_PROVENANCE=
                                                                  full-ort-1.31.0-cpu-keyfix1 (vc72, carried fwd)
app/src/main/java/.../playback/PlaybackService.kt                TrackAnalyzer as property initializer
```

## Build
```
app/build.gradle.kts        jniLibs / pickFirsts region — DO NOT restore srcDir
app/proguard-rules.pro      Log.i/w preserved, Log.d/v stripped
build.gradle.kts:44-45      version 73 / "2.2.3-djmix31"
npu-addon/prebuilt/         137 MB QAIRT libs, dormant, gitignored, do not feed into build
```

## Evidence and docs
```
/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/vc73-device/    vc73 APK copy + build log + report
/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/vc72-device/    vc72 APK copy + build log + report
/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/vc71-device/    vc71 proof, telemetry tail, APK copy
/home/hfzhkn/Bitchord/docs/tools/analyze_telemetry.py               D6 telemetry analyzer
/home/hfzhkn/Bitchord/docs/HANDOVER_vc72.md                          prior handover (structure source, do not modify)
/home/hfzhkn/Bitchord/docs/NPU_QNN_IMPLEMENTATION_PLAN.md
/home/hfzhkn/Bitchord/docs/session/BITCHORD_FORENSICS_164_90_MESSY_20261115.md
/home/hfzhkn/Bitchord/docs/session/BITCHORD_PLAN_SWAP_ABORT_TRACE_20261115.md
/home/hfzhkn/Bitchord/docs/ORCHARD_AUTOMIX_ANALYSIS.md
/tmp/orchard-analysis                                                    Orchard clone, read-only reference
```

---

# 10. Recommended next cycle, in order

**Cycle 1 — on-device vc73 acceptance (nothing else first)**
1. USB reconnect `1439ebea`; run the §12 acceptance script (install → `dumpsys` versionCode 73 → launch → multi-item radio queue → 60–90 s → positive proofs → 3 crash captures → telemetry pull → `analyze_telemetry.py --since-ts`).
2. Re-run the D1/D3/D4 + A–D vitals from telemetry (§5): `*A` vs `*B` share, `steps=` presence, `drifting beat grid` on 75.949-BPM rows, `decline:true` + `keyClash` counts, plus vc73-specific vitals — matched `incomingPlaybackRate` inside `0.94..1.09` on genuine pairs, no `build-missed-splice` arm deaths on short overlaps, `outStemSeparated` firing where `canUseWindowedStems` is true.
3. If any vital fails, **stop and diagnose before any tuning.** The D4 defeat proof (H1) says the bug is no longer in chroma — do not re-open chroma. The vc73 defeat proofs (§12.7) say the same for the six vc73 theories — do not re-open them either.

**Cycle 2 — listening verification of A–D families (§11)**
Per-family confirmation with the user, one pair type at a time. Start with the early-exit pair (`Beautiful` → `Never Let Go`) and the silent-intro pair (`Drip` → `UP`), then beatmatch (`This Is What You Came For` → `One Kiss`), then muffle replacement (`Technologic` → `Guess`).

**Cycle 3 — vc72 carry-forward re-proof (§5)**
Key accuracy (`Bubble Tea` `4B`, Crescendo `10A` vs `10B`), declined-pair routing, 75.949-BPM `Keyveatz` steady-pair check — all still pending telemetry + ears from vc72.

**Cycle 4 — long-run stability (§D2)**
60-minute playback. Memory, crash buffer, telemetry growth.

**Cycle 5 — section/loop correctness against ground truth**
Never validated. Coordinate with the user and catalog values.

**Cycle 6+ — NPU, only if 1–5 are clean**
Start with the one-line `<uses-native-library libcdsprpc.so>` manifest fix. One build, one logcat.

---

# 11. What remains unverified — state this honestly to the user

Every item below is **UNVERIFIED UNTIL USER EARS HEAR IT**. The vc71 "not bad" verdict does not extend to vc72 or vc73: the key pipeline, the confidence semantics, the beat-grid acceptance, the early-exit floors, the silent-intro cue, the beatmatch rates, the stem separation, and the ride filters all changed since then.

- **On-device vc73 acceptance** — pending USB reconnect of `1439ebea` (§12). No vc73 telemetry exists yet. Nothing was installed, no adb mutation this cycle.
- **Song A early-exit (A1–A4) — UNVERIFIED UNTIL USER EARS HEAR IT.** Specifically, **`Beautiful` → `Never Let Go`**: Song A should now play out to its real end (not 40–60 s early); no premature fade/exit cut. If it still cuts early, re-diagnose against all four causes (§7.15) — do not fix just the last one.
- **Song B silent-intro blend (B1–B4) — UNVERIFIED UNTIL USER EARS HEAR IT.** Specifically, **`Drip` → `UP`**: the incoming cue should land on an audible intro, not silence; no long silent blend-in. Audible intros must stay at `0.0` (the `0.0`-when-audible-from-the-top guard is the regression check).
- **Beatmatching (C1–C3) — UNVERIFIED UNTIL USER EARS HEAR IT.** Specifically, **`This Is What You Came For` → `One Kiss`**: genuine 3–8 % pairs should now beatmatch (rate inside `0.94..1.09`); `120/109` and `164/90`-class pairs correctly stay at unity. No unbeatmatched-classic blend on genuine pairs.
- **Muffle replacement (D1–D6) — UNVERIFIED UNTIL USER EARS HEAR IT.** Specifically, **`Technologic` → `Guess`**: no underwater LP, no tinny HP, no broadband muffle; full-band outgoing with a vocal-band dip and a clean ≤200 Hz bass swap. This is the highest-risk claim in the cycle — if the stems say "separated" but the ear hears a thin band, the `0.08` outgoing vocal ramp and the `1500 Hz` peaking-EQ depth are the first two knobs to audit.
- **`75.949 BPM` `Keyveatz` self/steady pairs — UNVERIFIED UNTIL USER EARS HEAR IT.** Should now be free of false `drifting beat grid`; listen for a clean beatmatch on 75-BPM-class tracks. This is the vc72 D1 carry-forward check — it has still never passed on device.
- **Key accuracy after D4 (vc72 carry-forward) — UNVERIFIED UNTIL USER EARS HEAR IT.** **CLOSE YOUR EYES — Bubble Tea should read `4B` (A♭ major) instead of `5A`** (C minor); **ATEEZ — Crescendo**: re-check whether it reads **`10A` B minor** (relative minor preserved — CORRECT if the catalog value is truly B minor) or **`10B` D major**-family readings. The relative-key fix predicts `10A` for B minor — the vc71 `10A` reading on Crescendo may incidentally have been a peak-picking artifact landing on the right relative, not a correct detection. Listen and correlate.
- **Declined pairs (vc72 carry-forward) — UNVERIFIED UNTIL USER EARS HEAR IT.** Should now route to `TransitionAlternatives` / `DECLINE` carve rather than `phrase-M6`; listen for the dual-band cut (800 Hz / 438 Hz at mid) instead of a phrase bridge on pairs the planner declines.
- **All vc71 families** — bridge, carve, muffle return, one-kick handoff, SEAM A/B rides, exit beats, DROP_IN arm — **none re-confirmed by ear on vc73**.
- **Long-run stability** past ~7 min.
- **Full queue completion** — the vc71 test queue was mid-flight at handover; same status assumed carried-forward.
- **`decline:true` firing in production** — will DECLINE now actually fire with unstarved, unmasked confidences? Live telemetry decides.
- **Section/loop correctness against ground truth** — never validated.
- **`V73` vs `V75`** — undecided; no `HTP ARCH` line has ever been emitted.
- **Whether dynamic-INT8 assets dispatch on HTP at all** — probably not; needs QDQ.
- **`:app:testProdDebugUnitTest` against the final vc73 artifact** — green in the test cycle (1355/0/0/1, fresh XML); the APK was built later. Re-run on a quiet box before release if the sources moved after the test runs (they did not; re-pin was measured against the same HEAD).
- **`PresenceTest` flakiness (vc72 carry-forward)** — failed twice on mock-server timeouts under a memory-starved box, passed on a quiet rerun. If it fails, check for zombie Gradle daemons before assuming a real regression.

---

# 12. Acceptance criteria for the next handover

A cycle is done when:

1. Both test suites green, from fresh XML, on a quiet box — shared 443/0, app 1355/0/1 skip, nothing suppressed or loosened. (`Djmix25AppTest DefaultsOn` flip is the only intentional assertion change; `AdvancedAutomixPlannerTest` passes unmodified.)
2. On-device: vc73 installed on `1439ebea` and `dumpsys` reads `versionCode=73` / `2.2.3-djmix31`. Analysis proof lines quoted from a real radio-queue run, not inferred from silence.
3. Crash buffer empty on three consecutive captures.
4. APK hash, size, versionCode, cert SHA + full build log recorded.
5. Git invariants re-pinned and unchanged (6085 unified-diff lines; root `build.gradle.kts` `73 / "2.2.3-djmix31"`).
6. Telemetry vitals re-measured: `*A` vs `*B` no longer 100/0; `steps=` present; `drifting beat grid` absent on 75.949-BPM full-quality rows; declined pairs in the DECLINE carve rather than phrase-M6; matched rates inside `0.94..1.09` on genuine pairs, unity on out-of-range pairs; no `build-missed-splice` arm deaths; `outStemSeparated` firing where `canUseWindowedStems` is true.
7. Every audio claim marked **UNVERIFIED UNTIL USER EARS HEAR IT**, with the specific pair/behaviour the user should listen for (`Beautiful` → `Never Let Go`, `Drip` → `UP`, `This Is What You Came For` → `One Kiss`, `Technologic` → `Guess`, plus the `75.949 BPM` `Keyveatz` steady-pair check).
8. Every defeated hypothesis written down, not silently dropped (see §12.7 below). Half the value of this handover is the list of theories that were wrong.

## 12.7 Defeated hypotheses — written down so they are not re-litigated

**vc72 hypotheses (still defeated — do not re-open):**

1. **Defeated Hypothesis 1 (D4, vc72):** *"BitChord's Camelot wheel table in `HarmonicMix.kt` is transposed or swaps relative major/minor (and `ATEEZ — Crescendo` B minor should be `11B`)."* → **FALSE.** `HarmonicMix.kt:28-29` (CAMELOT_MAJOR/MINOR arrays feeding `camelotCode()` at `HarmonicMix.kt:36`) is 100% standard Mixed In Key: `B minor = 10A`, `A major = 11B`, `A♭ major = 4B`, `C minor = 5A`. (The table's numbers are consistent with the wheel as drawn in `MixViewPopup.kt:739-740`.) The 100% `*A` (`54/54`) bug was entirely in `native/analyzer/audio_analysis.cpp`: all-bin `log1p(power)` chroma with **no peak-picking** smeared energy into the chroma of every pitch class, and the **uncentered dot product** (no mean subtraction) gave `k_minor` a constant **+2.72 sum / +0.2267 mean DC bias** over `k_major` — the Krumhansl-Kessler minor profile has the larger total, so every flat chroma scored the same way: minor. The uncentered matcher also compressed `keyConfidence` to **0.04–0.22**, which starved every confidence gate downstream. vc72's fix: peak-picked chroma + **zero-mean Pearson** (mean-subtracted) + 24 keys + tonal gate (`audio_analysis.cpp:385`, `:462-546`). Already settled by vc72 — **do not re-open**.
2. **Defeated Hypothesis 2 (D3, vc72):** *"`decline:true` / `DECLINE` carve is dead/unreachable in production."* → **FALSE.** `DECLINE` was planned 8 times and fired twice in `classic` during the 30.57-min vc71 session — **however**, the carve was starved, not dead: `108/108` `decline-to-mix` dumps logged `steps=?` (all `dualvocal`-only, 0 `keyClash`), and `TransitionAlternatives` clash inversion (`keyClash` with confidence ≥ 0.65) never triggered because **D4 starved `keyConfidence` below 0.25** AND weak regional head/tail keys masked the global keyConfidence the gate relied on. Simultaneously, `CrossfadeController.nativeBridge` ran `phrasePlanFor` (M6) **ahead of** `LoopMix.bridge` on declined pairs. vc72 fixes all three: real confidences (D4), regional/global parity (D3a), and the M6 gate (D3b, `shouldRunPhrasePlannerForBridge`).
3. **Defeated Hypothesis 3 (D1, vc72):** *"`drifting beat grid` is a harmless stale pre-ORT string or pure telemetry-cache race."* → **FALSE.** `fittedBpm` in `HarmonicMix.kt` genuinely returned `null` on `quality="full"` grids: at 75.949 BPM the beat period is **0.790 s > the old 0.75 s fixed edge margin**, AND isolated `snapGrid` onset jitter (±35 ms) broke the **6% single-gap rule** in `fitBeatSegments`, splitting steady grids. `fittedBpm = null` set `PairTraits.unfit = true` (`phaseLockable = false`, `incomingPlaybackRate = 1.0`) — a real downgrade of every 75-BPM-class blend. vc72: edge margin `maxOf(0.75, period * 2.5)` (`HarmonicMix.kt:178`) + isolated-snap tolerance (`HarmonicMix.kt:114`).
4. **Defeated Hypothesis 4 (D5, vc72):** *"`detectOctaveRelationship` is unwired from `mix.dump`."* → **FALSE.** It is wired on all 3 `Evidence` paths. The closest vc71 pair was ratio `0.5443` — **8.86% > 8.0%** tolerance — correctly silent. Zero real 2×/0.5× pairs have been observed triggering it; the detector is unit-wired but field-silent by design.
5. **Defeated Hypothesis 5 (Loop Arm Death, vc72):** *"`outgoing decode 0 ms over` means decoding genuinely missed the splice deadline by 0 ms."* → **FALSE.** `LoopMix.lateStepReason` clamped `(nowMs - deadlineMs).coerceAtLeast(0L)` to `0L`, masking the true overrun; and `CrossfadeController` called `lateDeckReason()` when `LoopMixSession.build` or `shiftToNextBar` returned `null` **seconds before** `deadlineMs` — the arm died long before the deadline. vc72 concentrates epoch/horizon handling in `lateStepReason` (`LoopMix.kt:391`). **vc73 H4 in the correct vc73 framing (below) is a different `0 ms over` claim about sequential decode + pre-deadline abort boundaries — do not conflate the two.**

**vc73 hypotheses (new this cycle — do not re-open):**

6. **Defeated Hypothesis 6 (A — early-exit):** *"`OUT_SEARCH_SEC = 60` itself is the early-exit bug."* → **FALSE.** The 60-second search window is fine — long enough to find clean outro material. The bug was the **`firstClean` sort order** (`LoopMix.kt:721-731`): raw `seamDelta` ordering let a marginally flatter verse bar ~55 s before EOF outrank clean outro loops near the end, regardless of how wide the window was. vc73 fixes the sort (`(start >= outroFrom ? 0 : 1, floor(seamDelta / 0.15))`), the `outroFloor` (36→32 s), the `minSec` `minOf` floor-lowering, the `BRIDGE_END`/`VERSE_END` scoring, and the `CONTENT_END` ramp cap — the window itself was never the problem.
7. **Defeated Hypothesis 7 (B — silent intro):** *"`HEAD` at `0.0` means the analyzer found no intro."* → **FALSE.** The analyzer always had the signal; the consumer never consulted it. `CuePoints.entries` (`CuePoints.kt:329`) **never consulted `SectionFn.SILENCE` / `audibleStartTime`** — it walked raw cue maps from the file top, so a silent or near-silent intro looked identical to an audible one. vc73 threads `inAnalysis` through `PhrasePlanner.select`/`choose` (`PhrasePlanner.kt:153,212`), consults `SectionFn.SILENCE`, and advances `HEAD` past `firstAudibleTime - 0.15`. `HEAD = 0.0` was a plumbing absence, not an analyzer finding.
8. **Defeated Hypothesis 8 (C — arm death / decode):** *"`outgoing decode 0 ms over` is decode latency."* → **FALSE.** It is `LoopMix.lateStepReason` reporting a **pre-deadline `early-abort` boundary** (the arm died *before* the deadline, so the overrun string rounds to 0) plus **sequential `OUT`/`IN` decode** in `LoopMixSession`. vc73 does not speed up the decoder — it runs the two regions **concurrently** (`DECODE_EXECUTOR` + `CompletableFuture.supplyAsync`, `LoopMixSession.kt:132`) and tightens the `startLoopAlignment` settle (`CrossfadeController.kt:5009`) so alignment converges inside short overlaps. Do not chase this with a faster codec.
9. **Defeated Hypothesis 9 (D — muffle):** *"Muffle is the only way to separate two vocals."* → **FALSE.** The `umxhq` vocal/instrumental stems were **already bundled, sample-aligned, and merely gated off** (`automixWindowedStemsEnabled = false`), with a 48 kHz size check (`split.size == outRegion.size`) that **silently discarded every separation** because the 44.1 k round-trip `floor(in*ratio)` truncated the split by a sample or two. vc73 flips the default on eligible devices, pads/trims back to `left.size` (`VocalTracker.kt:326`), returns **both** stems from one ONNX pass (`VocalTracker.kt:340`), and composes `OUT` = `inst + vocalGain*vocal` — the muffle was never the necessary technology, only the available fallback.
10. **Defeated Hypothesis 10 (D — planner variant):** *"The decline/tempo-gap `SWEEP` default must change to `BASS_HANDOFF`."* → **FALSE.** The audible problem was the ride's **LP/HP muffle**, not the planner's `SWEEP` default. Changing the default would have broken `AdvancedAutomixPlannerTest.theFallbackGestureFollowsTheLowEnd` and `twoVocalTracksOverride...`, which encode intentional planner policy. vc73 instead **restores `fallbackVariantFor` / `resolvedFilterVariant` to HEAD** (`TransitionPlanner.kt:423-454`, `:2287`) and moves the audible fix entirely into the rides: `OPEN_HZ` LP + `bassCutoff(≤200 Hz)` HP + vocal-band `outgoingMidDb` dip. If a future defect really is planner policy, re-litigate it with telemetry evidence — not with ear-driven default flipping.

absolute WsThanhDieu to whoever picks this up — Song A no longer dies young, Song B no longer fades into silence, the beatmatch rate is actually wired, and the muffle is demoted to a fallback it never should have been the lead for. Guard the ORT packaging with your life and the rest is tuning — but go hear it first, and hear each family separately.

---

# 12. Device acceptance script (verbatim, also in `vc73-report.md` §8)

Run on a machine with `adb` after USB reconnect of `1439ebea`. The package is `com.music.bitchord.loopfix`; the launcher activity is `com.music.bitchord.loopfix/com.music.bitchord.LauncherDefault` (not `.MainActivity`).

```bash
set -euo pipefail
ADB=/home/hfzhkn/.local/bin/adb
DEV=1439ebea
APK=/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/vc73-device/app-prod-arm64-v8a-release.apk

# 1. Confirm the device is actually attached (CASE B gate)
$ADB devices -l
# expect: 1439ebea device product:... model:... (if the list is empty, stop — do not debug from silence)

# 2. Install vc73
$ADB -s $DEV install -r "$APK"

# 3. Confirm versionCode 73 on the installed package
$ADB -s $DEV shell dumpsys package com.music.bitchord.loopfix | grep -iE 'versionCode|versionName'
# expect: versionCode=73 / versionName=2.2.3-djmix31

# 4. Launch (LauncherDefault activity)
$ADB -s $DEV shell am start -n com.music.bitchord.loopfix/com.music.bitchord.LauncherDefault
sleep 4

# 5. Clear logcat and start a multi-item radio queue (a single-item VIEW play never triggers analysis)
$ADB -s $DEV logcat -c
$ADB -s $DEV shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH --es query "Keyveatz"
sleep 45

# 6. Positive evidence — these lines can only exist if inference ran
$ADB -s $DEV logcat -d -s BitChordTrackAnalyzer:* BitChordNPU:* onnxruntime:* *:S
$ADB -s $DEV logcat -d | grep -iE 'Kernel not found|model unavailable|MatMulIntegerToFloat|DynamicQuantizeMatMul|mel |infer|vocal mask|bars='
# expect lines like:
#   BitChordNPU disabled (QNN_ENABLED=false): CPU execution provider
#   mel 144ms (1113 frames) infer 2788ms
#   BitChordPhrase: structMs=1549 structWallMs=1549 bars=71 partial=false duration=138.0
#   BitChordTrackAnalyzer: vocal mask 958 frames in 1040ms
# (numbers will differ per track — require the *shape*, not the exact numbers)

# 7. Crash buffer — capture three times, must be empty each time
for i in 1 2 3; do
  echo "--- crash capture $i ---"
  $ADB -s $DEV logcat -b crash -d
  sleep 5
done

# 8. Pull telemetry and run the analyzer
SINCE_TS=$(date +%s)
# (set SINCE_TS to the epoch second when step 5 ran; the analyzer needs the session start)
$ADB -s $DEV pull /sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl /tmp/automix-telemetry.jsonl
python3 /home/hfzhkn/Bitchord/docs/tools/analyze_telemetry.py --since-ts "$SINCE_TS" --telemetry /tmp/automix-telemetry.jsonl
# or: python3 /home/hfzhkn/Bitchord/docs/tools/analyze_telemetry.py --since-ts "$SINCE_TS"
# (the tool defaults to the vc71 telemetry tail if no path is given — pass --telemetry for a fresh pull)
```
