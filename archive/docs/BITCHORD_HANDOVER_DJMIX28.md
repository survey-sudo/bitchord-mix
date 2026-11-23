# BitChord DJMIX28 Handover — 2026-11-15 (vc70, BUILT + VERIFIED + MIRRORED, INSTALL PENDING, NPU BUNDLED)

> **Honest state in 5 lines.** `2.2.3-djmix28` (vc70) is **BUILT (`BUILD SUCCESSFUL in 33m 2s`, exit 0), SIGNED (`oldkey`), VERIFIED (1,762 fresh runs, 0 failures, 1 pre-existing `GeniusTest` skip), AND MIRRORED** — no `adb install -r` was attempted in this stage; `djmix28-proof/install-pending.txt` records the safe single-shot for `1439ebea`. `vc69`→`vc70` shipped **Seam A teardown invariant** (defer `filters.open()` until both decks silent, measured +19.2 dB step if reopened audible), **Seam B `exitBeats` + two-phase muffle/bridge rides** (floor parks then returns to full-band 20,000 Hz over explicit beats), **native `DROP_IN` arm** (explicit unfiltered incoming, no fallthrough to high-pass), **family registry** (one `FamilySpec` per alternative kind), and **R128 deletion** (Kotlin sum limiter removed, native −1 dBTP + scalars remain). NPU moved from vc69 code-only to **vc70 bundled two-generation QAIRT** (V73 + V75 hedge for Poco F6 / SM8635, ORT 1.31.0 from source with `--use_qnn`, 21 arm64 `arm64-v8a` libs). **No audio claim** (`UNVERIFIED UNTIL USER EARS HEAR IT`). **NPU dispatch is UNVERIFIED UNTIL FIELD TEST** (no log proves QNN opened or which Skel loaded).

---

## 1. Executive state [PROVEN]

| Item | Value |
|---|---|
| Built on | `bk-android` (`host=instabox-bk-android user=hfzhkn`) — `2026-11-15`, `BUILD SUCCESSFUL in 33m 2s` (`:app:assembleProdRelease --offline --no-daemon -Pbitchord.bundleWear=false`, no clean; run server-side via `nohup` to `/tmp/build-vc70.log`, exit 0; 68 executed, 67 up-to-date, 135 tasks; started `2026-11-15T03:27:06Z`, finished `04:00:22Z`) |
| Version | `2.2.3-djmix28`, **versionCode 70** (`aapt: versionCode='70' versionName='2.2.3-djmix28' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36'`) |
| APK | `/home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix28-automix-arm64-v8a-oldkey.apk` (= `/home/hfzhkn/Downloads/…` via dir symlink, same inode 1624336): **108,019,024 B**, SHA-256 `6a2dd0bbdfdcc2a52e0f06800558d9e8b13dc3d4c6c6852799c0cb56c965ce91` (authoritative, `install-pending.txt` + `apk-verify.txt`). 21 `lib/arm64-v8a/` `.so` libs only (`native-code: 'arm64-v8a'`), v2 cert `20c66ad6…e3fe4c` (oldkey, DN `CN=BitChord loop fix, O=BitChord loop fix, C=US`, `Verified using v2 scheme: true`), expected two-gen reference 108,016,008 B (+3,016 B, +0.0028% metadata variance — jniLibs config unchanged, same V73+V75 contents); `vocals_umxhq_int8.onnx` (9,054,206 B) + `beat_this_final0_q8.onnx` (23,138,816 B) still the only 2 models |
| Installed on | `1439ebea` — **INSTALL PENDING** (no ADB mutation attempted; no uninstall/pm clear/force-stop/play-pause-seek-queue). Safe instruction in §6 |
| Phone proof | `djmix28-proof/` (`apk-verify.txt`, `test-results.txt`, `git-invariants.txt`, `mirror-report.md`, `transfer.md`, `npu-status.md`, `install-pending.txt`) — build/verify/mirror/install-pending evidence only; no fake playback. No prior `djmix27-proof/`, `bitchord-djmix27*.patch`, `BITCHORD_HANDOVER_DJMIX*.md`/`VC69_WRAPUP.md` touched |
| Listening verdict | **UNVERIFIED UNTIL USER EARS HEAR IT** — no audio claim from tests/unit geometry alone |
| NPU verdict | **UNVERIFIED UNTIL FIELD TEST** — Code + bundled libs ship and are test-green, but no hardware log proves QNN dispatch or which Skel (V73 vs V75) loaded; CPU fallback is the proven path |

---

## 2. Version, tests & APK

| Gate | Result |
|---|---|
| `build.gradle.kts` | `bitchordVersionCode = 70`, `bitchordVersionName = "2.2.3-djmix28"` (root `build.gradle.kts` extras; `app/build.gradle.kts` reads them; version-bump edit only in this stage — cached diff preserved) |
| `:shared:jvmTest` | **420** tests, 0 failures/errors/skipped (fresh 03:23Z, 52 XML files) — headline shared gate |
| `:app:testProdDebugUnitTest` | **1,342** tests, 0 failures/errors, **1 skipped (`GeniusTest`, pre-existing)** (fresh 03:23Z, 123 XML files) |
| Fresh grand (jvm + app) | **1,762** runs (420+1342), **0 failures**, 1 skipped — from live fresh XML totals on `bk-android` (`djmix28-proof/test-results.txt`) |
| Stale (NOT counted) | `shared:testDebugUnitTest` **309** / `testReleaseUnitTest` **309** — 2026-11-14T15:35Z pre-vc70 XML, zero vc70 test files (exclude from grand; re-run to consume) |
| New tests in delta | Shared `ExitLegTest` 6, app `Vc70ExitLegAppTest` 5 (rendered behavior only: cutoff/envelope breakpoints + fake-filter/gain asserts, never plan constants alone). Removed: `R128LimiterTest` 9 (file deleted with its limiter) |
| `:app:assembleProdRelease` | `BUILD SUCCESSFUL in 33m 2s` (incremental: 68 executed, 67 up-to-date; build disk 3.6G→2.1G free, no clean, no source/test edits beyond version bump) |
| Git invariants on `bk-android` | `HEAD=dc78e0d`, `git diff --cached \| wc -l = 6085` (SHA `4a4c6821a4a7d463a8cfcb8be45752b5f0fa81f70bd40de141ff27f4efb804e3`), `git diff HEAD \| wc -l = 26072` (SHA `93b658747ef549f45c1224a1e23ee03563e64e4502267d5339037ad33fc66a47` = full patch, 114 files). No `commit`/`reset`/`checkout --` |

Disk note (this stage): `df -h /data` before was 2.0G free (91%); deleted only regenerable `app/build/outputs` + `app/build/intermediates` + `shared/build/classes` (+ `/tmp/build-vc*.log`, none present) → 3.6G free (82%), `npu-addon/prebuilt` untouched (131M, 8 libs); after build 2.1G free (90%). No `gradle clean`.

---

## 3. What shipped in vc70 (seam fixes + NPU, vc69 → vc70)

Delta patch (`bitchord-djmix28.patch`, 30 files, 11,908 lines): 14 new + 14 changed + 2 deleted. New: `THIRD_PARTY_NOTICES.md` (QAIRT), `app/build.gradle.kts` (jniLibs + pickFirsts + ORT 1.31.0), `AndroidManifest.xml` (`extractNativeLibs`), `Qualcomm-AI-Runtime-LICENSE.pdf` + `QNN-NOTICE.txt`, `CMakeLists.txt` + `npu_jni.cpp`, `NpuNative.kt`, `Vc70ExitLegAppTest.kt`, `npu-addon/.gitignore` + `README.md` + `build-ort.sh` + `ops.config`, `ExitLegTest.kt`. Changed: `CrossfadeController.kt`, `LoopMixSession.kt`, `TrackAnalyzer.kt`, `Djmix27AppTest.kt`, `build.gradle.kts` (version), `DjMix.kt`, `LoopMix.kt`, `MixVerify.kt`, `NpuSupport.kt`, `TransitionAlternatives.kt`, `TransitionPlanner.kt`, `NpuSupportTest.kt`, `TransitionAlternativesTest.kt`, `NpuSession.kt`. Deleted: `R128Limiter.kt`, `R128LimiterTest.kt` (synthesized deleted-file hunks in delta; absent from vs-HEAD full patch by construction — see `git-invariants.txt` note).

### Seam A — teardown invariant (`CrossfadeController.kt`)

- Invariant: **a transition exit path may never reopen filters while any deck is audibly above zero gain**. `anyDeckAudible()` (via `RetireGuard.audible` on both players + tail target) gates `openFiltersWhenSilent()` at every teardown site (`finish()`, `bail()` including force, `release()`). Rationale in-code: opening a parked low-pass while audible is a ~30 ms spectral step (measured +19.2 dB AIR, centroid 1242→6001 Hz), not a musical ramp.
- Semantics: parked cutoff is left alone through the `BAILING` dissolve / `TAIL` settle; `finish()` opens only once gains reach zero. Covered by `Vc70ExitLegAppTest.teardownDefersOpenUntilSilent_{finish,bailForce}Path` (fake filters + fake players, gains 0.8/0.2 → 0 calls, then 0/0 → 1 call) + `parkedRenderTeardownKeepsFiltersParkedWhileAudible` (parked 700 Hz cutoff survives audible teardown).

### Seam B — `exitBeats` + two-phase muffle/bridge rides (shared `TransitionPlanner`/`TransitionAlternatives` + `CrossfadeController` + `LoopMixSession` envelopes)

- **Plan owns the return span**: new `TransitionPlan.exitBeats` (default 0; the renderer never invents it). `planMuffle` sets `exitBeats = MUFFLE_EXIT_BEATS = 2` (8-beat lead, `bassSwapFraction = 0.75` = 6/8 boundary); `planBeatlessBridge` sets `exitBeats = min(BRIDGE_EXIT_BEATS = 4, totalBeats/2)`. Zero for styles that never park.
- **Two-phase math** (pure, shared for testability): `TransitionAlternatives.muffleCutoffAt(progress, sweep, entryFraction)` — entry closes open→700 Hz over `0..0.75`, return reopens 700→20,000 Hz over `0.75..1`; `bridgeReopenCutoffAt` — park 1,500 Hz then reopen to 20,000. Geometric (`glideHz`) because pitch is logarithmic; sweep scaling preserved. Envelopes `muffleEnvelope`/`bridgeEnvelope` fix the degenerate `enterEnd == rampEnd` single-sample collapse (20000→2800 in one 64-frame block) — all adjacent breakpoints now span beats, strictly increasing (`ExitLegTest.native{Muffle,Bridge}EnvelopeHasNoCollapse`).
- **Riders**: `rideMuffle` / `rideBeatlessBridge` consume `render.exitBeats` (`returnStart = 1 − exitBeats/totalBeats`) and end full-band at progress 1, so surviving decks never need the teardown `open()` glide. `LoopMixSession` programs matching native envelopes (`muffleEnvelope`/`bridgeEnvelope` via `TransitionAlternatives`) instead of legacy single-glide ramps. Covered by `ExitLegTest.muffle{RideEndsAtFullBand,PlanCarriesNonZeroReturnSpan}` + `bridgeReopenEndsAtFullBand` + `Vc70ExitLegAppTest.{muffle,bridge}RideAtFullProgressIsFullBand`.

### Native `DROP_IN` arm (`LoopMixSession.kt`)

- `if (effectivePlan.dropIn)` explicit arm: incoming deck enters **unfiltered by definition** (no low-pass/high-pass curve on either deck). Bass handoff stays on `KIND_LOW_DB`; the `when` branch `effectivePlan.dropIn -> { /* no curve */ }` is intentional, never falling through to the generic high-pass. This closes the vc69 gap where DROP_IN rode the classic filter path. Classic rider `rideDropIn(progress)` mirrors it (outgoing cuts inside first half, `ridgeEchoOut` rings echo; nothing filters either deck).

### Family registry (`TransitionAlternatives.kt`, 650 lines)

- `FamilySpec(kind, style, priority, rung, usesDropBase, fits, planCandidate, applyLoop)` + `FAMILIES` (muffle/p0, bridge/p1, drop-in/p2 with `usesDropBase=true` + `rung = "Drop"`). `isAlternativeStyle`/`isAlternativeKind`/`specFor{Kind,Style}` derive from it. `DjMix`, the classic rider, and the native envelope writer consult the registry instead of listing families inline — a new family adds one `FamilySpec` + gate/plan functions with no per-family edits downstream. Planning + envelope data live in the registry; audio riders keep their mechanics. `planAlternative` ranks via `rankScore` → `confidence` → `priority`. Covered by `ExitLegTest.familyRegistryCoversAllStyles`.

### R128 deletion (reasoning)

- Deleted `R128Limiter.kt` (189 lines) + `R128LimiterTest.kt` (115 lines). In vc69 the Kotlin sum-only limiter (EBU R128 −0.5 dBFS, 5 ms lookahead, 80 ms release, Orchard `LoudnessConfig`) was **scalar/reentrant but never lifted into the native render loop** — its only consumer was `MixVerify.limiterReductionDb` (a ceiling probe), while real peak protection was already the **native −1 dBTP limiter** (see `MixGain.kt` headroom notes) plus `MixVerify.MAX_PEAK_LINEAR = 0.896` (−0.5 dB) scalar gate. Verified in vc70 tree state: zero `R128` references remain in any `.kt`/`.cpp`/`.h` source; `MixVerify.verify` is back to the simple LUFS/peak/phase/exit-gates gate. Deletion removes a dead-code duplication risk (two limiters with different semantics) without changing rendered behavior — the native limiter is the single guard.

### NPU two-generation hedge (see §5 and `djmix28-proof/npu-status.md`)

- Gradle: `jniLibs.srcDir(rootProject.file("npu-addon/prebuilt/jniLibs"))` + `pickFirsts += "**/libonnxruntime.so"` + `useLegacyPackaging = true` + `onnxruntime-android:1.31.0`. Manifest: `extractNativeLibs=true`. Recipe: `build-ort.sh` (ORT 1.31.0 `--use_qnn`) + `ops.config` (reduced build from the two shipped models). Licences: `assets/licenses/` + `THIRD_PARTY_NOTICES.md`.

---

## 4. Artifacts & proof

| Artifact | Lines / Bytes | SHA-256 |
|---|---|---|
| APK `bitchord-2.2.3-djmix28-automix-arm64-v8a-oldkey.apk` (mirrored, install pending) | 108,019,024 B | `6a2dd0bbdfdcc2a52e0f06800558d9e8b13dc3d4c6c6852799c0cb56c965ce91` |
| `patches/bitchord-djmix28.patch` (vc70 delta, 30 files) | 11,908 lines, 618,287 B | `5f0e2300eb9d4778a7144a2c0e3a10b81308767487c023136ee2a535ee1ae8a9` |
| `patches/bitchord-djmix28-full.patch` (114 files from `dc78e0d`) | 26,072 lines, 1,303,808 B | `93b658747ef549f45c1224a1e23ee03563e64e4502267d5339037ad33fc66a47` |
| Proof `evidence/bitchord-device-evidence/djmix28-proof/` (`apk-verify.txt` 85 lines, `test-results.txt` fresh 1762, `git-invariants.txt` 126 lines, `mirror-report.md`, `transfer.md`, `npu-status.md`, `install-pending.txt`) | — | `apk-verify.txt` `05184089…` |
| This handover | — | — |
| Wrap-up `docs/BITCHORD_HANDOVER_VC70_WRAPUP.md` | — | — |

Delta contents: 14 new + 14 changed + 2 deleted (R128) = 30 files whose patch sections differ from `bitchord-djmix27-full.patch` (102 files); 86 files byte-identical. Delta includes the version bump (`build.gradle.kts` 69→70 / djmix27→djmix28) and the R128 deletions (synthesized deleted-file hunks, since vs-HEAD full patch nets them to zero — see `git-invariants.txt`).

---

## 5. NPU caveats (read before claiming anything)

- **Bundled QAIRT stack**: 21 `arm64-v8a` libs — 7 `libQnn*` (Htp / System / HtpPrepare 88 MB / V73 Skel+Stub / V75 Skel+Stub) + 14 prior, including the **13,011,808 B from-source `libonnxruntime.so`** (ORT 1.31.0 `--use_qnn`). Legacy packaging + `extractNativeLibs` keep the APK at 108 MB. **No V68/V69/V79/V81**; no `liborchard_qnn.so`.
- **V73-vs-V75 deliberate hedge**: both Hexagon generations ship because SM8635 cannot be pinned from QAIRT 2.51 sources; the driver resolves arch from SoC at backend open. The unused generation can be dropped later once field-proven — but only after the log proves it.
- **Runtime**: Qualcomm-only attempt, CPU fallback kept (`disable_cpu_ep_fallback=0`). Non-Qualcomm = CPU by design.
- **On-device dispatch UNVERIFIED**: no log proves QNN opened or which Skel loaded. Settle with the logcat command in `djmix28-proof/npu-status.md`: `adb -s 1439ebea logcat -d BitChordTrackAnalyzer:I BitChordNPU:I *:S | grep -i npu`. Until then, do not claim NPU speedup/quality.

---

## 6. Exact next steps (in order)

1. **Transfer + install** — Phone on the host LAN: download `http://10.212.240.111:8088/bitchord-2.2.3-djmix28-automix-arm64-v8a-oldkey.apk` (or copy from `/home/hfzhkn/Storage/Downloads/`) and verify SHA-256 `6a2dd0bb…ce91` (108,019,024 B), then single-shot `adb -s 1439ebea install -r /home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix28-automix-arm64-v8a-oldkey.apk` (`djmix28-proof/install-pending.txt`). Verify `versionCode=70` / `2.2.3-djmix28` fresh `lastUpdateTime`. No `uninstall`/`pm clear`/`force-stop`/queue edits. LAN server dies when the host/server sleeps — keep the host awake through install.
2. **Four-pair listening gate** — user queues/plays/records; agent stays hands-off. Per pair: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? decline rescued vs hard cut? dead zone >8 bars or mid-vocal cut? Pairs: `This Is What You Came For` → `One Kiss`; `Beautiful` → `Never Let Go`; `Drip` → `UP`; `Technologic` → `Guess`. **Watch specifically for the vc70 seals**: muffle/bridge must return to full-band (no lingering muffle at handoff), teardown must never crack a parked filter audible, DROP_IN must enter unfiltered.
3. **Telemetry + NPU log check** — pull fresh `automix-telemetry.jsonl` into a new file in `djmix28-proof/` (never overwrite prior files); correlate `mix.fire`/`mix.complete`/`mix.handoff.deferred`/`mix.verify`/`mix.plan` + `TransitionEvidence` against what was heard. On the Snapdragon, run the §5 logcat command during one analysis pass and record whether `skels=` + `adspReady=true` + QNN-open appeared (settles V73 vs V75).
4. **Hands-off vs touched playback test** — one Calvin playthrough without touching the device, then read-only telemetry pull; check whether `mix.pause.detected` still appears hands-off.
5. **Re-run stale suites** if a 4-way grand is needed: `:shared:testDebugUnitTest` + `:shared:testReleaseUnitTest` (2026-11-14T15:35Z stale, 309 each) to fold into the fresh total.
6. **Never** claim audio quality or NPU dispatch without ears + field test. `UNVERIFIED UNTIL USER EARS/FIELD TEST`.

*End of handover. vc70 is on the builder and staged — install it, then the verdict belongs to your ears (and, for the NPU, to a Snapdragon field log).*
