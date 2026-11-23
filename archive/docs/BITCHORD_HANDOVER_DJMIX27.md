# BitChord DJMIX27 Handover — 2026-11-15 (vc69, BUILT + VERIFIED + MIRRORED, INSTALL PENDING)

> **Honest state in 5 lines.** `2.2.3-djmix27` (vc69) is **BUILT, SIGNED (`oldkey`), VERIFIED, MIRRORED, INSTALL PENDING** — phone `1439ebea` was **offline for ADB** (ping 100% loss at `192.168.100.218`, `adb devices -l` empty) in the read-only probe, so `install -r` was not run; `install-pending.txt` logged cleanly. APK `/home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix27-automix-arm64-v8a-oldkey.apk` (= `/home/hfzhkn/Downloads/…` via dir symlink, same inode): `87,551,587` B, SHA-256 `abb336daf8017235df12dce4e40c2952c3235674feaa9305b97290e79909a73a`, 14 `arm64-v8a` `.so` libs, v2 cert `20c66ad6…e3fe4c`, `versionCode=69`, `versionName=2.2.3-djmix27`. Fresh gate **1,760** test runs (**423** shared jvm + **1,337** app), **0 failures**, **1 skipped (`GeniusTest`, pre-existing)**. vc68→vc69 shipped **P0 decline rescues** (muffle / drop-in / beatless bridge), **P1 vocal-depth filter + kick-lock + release-splice + R128 sum limiter**, **P2 beat-flow ranking + transition evidence dump**, and **NPU QNN execution-provider wiring for Beat This + umxhq** (CPU fallback kept). **No audio claim** (`UNVERIFIED UNTIL USER EARS HEAR IT`). **NPU dispatch is UNVERIFIED UNTIL FIELD TEST** (no Snapdragon log proves QNN opened; no QNN libs are bundled — the HTP backend is expected from device firmware).

---

## 1. Executive state [PROVEN]

| Item | Value |
|---|---|
| Built on | `bk-android` (`host=instabox-bk-android user=hfzhkn`) — `2026-11-14`, `BUILD SUCCESSFUL in 8m 23s` (`:app:assembleProdRelease --offline --no-daemon`, no clean; run server-side via `nohup` to `/tmp/build-vc69.log`, exit 0) |
| Version | `2.2.3-djmix27`, **versionCode 69** (`aapt: versionCode='69' versionName='2.2.3-djmix27' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36'`) |
| APK | `/home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix27-automix-arm64-v8a-oldkey.apk` (= `/home/hfzhkn/Downloads/…` via dir symlink) — **87,551,587 B**, SHA-256 `abb336daf8017235df12dce4e40c2952c3235674feaa9305b97290e79909a73a` (authoritative, `install-pending.txt` + `apk-verify.txt`). 14 `lib/arm64-v8a/` `.so` libs only (`native-code: 'arm64-v8a'`), v2 cert `20c66ad6…e3fe4c` (oldkey, DN `CN=BitChord loop fix, O=BitChord loop fix, C=US`, `Verified using v2 scheme: true`), `vocals_umxhq_int8.onnx` (9,054,206 B) + `beat_this_final0_q8.onnx` (23,138,816 B) already bundled — **0 extra APK models added in vc69**; **0 QNN libs bundled** (see §5 / `npu-status.md`) |
| Installed on | `1439ebea` — **INSTALL PENDING** (ping 100% loss, `adb devices -l` empty in read-only probe; no `install -r` attempted, no uninstall/pm clear/force-stop/play-pause-seek-queue). Safe instruction in §4. |
| Phone proof | `djmix27-proof/` (`apk-verify.txt`, `test-results.txt`, `git-invariants.txt`, `mirror-report.md`, `npu-status.md`, `install-pending.txt`) — build/verify/mirror/install-pending evidence only; no fake playback. No prior `djmix26-proof/`, `bitchord-djmix26*.patch`, `BITCHORD_HANDOVER_DJMIX*.md`/`VC68_WRAPUP.md` touched. |
| Listening verdict | **UNVERIFIED UNTIL USER EARS HEAR IT** — no audio claim from tests/unit geometry alone |
| NPU verdict | **UNVERIFIED UNTIL FIELD TEST** — Code ships, option maps test-green, but no hardware log proves QNN dispatch; CPU fallback is the proven path |

---

## 2. Version, tests & APK

| Gate | Result |
|---|---|
| `build.gradle.kts` | `bitchordVersionCode = 69`, `bitchordVersionName = "2.2.3-djmix27"` (root `build.gradle.kts` extras; `app/build.gradle.kts` reads them; unstaged edit only — cached diff preserved) |
| `:shared:jvmTest` | **423** tests, 0 failures/errors/skipped (fresh 16:42Z, 52 XML files) — headline shared gate |
| `:app:testProdDebugUnitTest` | **1,337** tests, 0 failures/errors, **1 skipped (`GeniusTest`, pre-existing)** (fresh 16:42Z, 122 XML files) |
| Fresh grand (jvm + app) | **1,760** runs, **0 failures**, 1 skipped — from live fresh XML totals on `bk-android` (`djmix27-proof/test-results.txt`) |
| Stale (NOT counted) | `shared:testDebugUnitTest` **309** / `testReleaseUnitTest` **309** — 15:35Z pre-vc69 XML, zero vc69 test files (exclude from grand; re-run to consume) |
| New tests in delta | Shared **+56** (367→423): `KickLockTest` 5, `NpuSupportTest` 8, `BeatFlowTest` 6, `TransitionAlternativesTest` 10, `TransitionEvidenceTest` 4, `VocalDepthFilterTest` 9, `R128LimiterTest` 9, `ReleaseSpliceTest` 5. App **+10** (1327→1337): `Djmix27AppTest` 10 |
| `:app:assembleProdRelease` | `BUILD SUCCESSFUL in 8m 23s` (incremental: 43 executed, 92 up-to-date); native proved via APK listing (`libbitchord_analysis.so` 481,768 B, `libbitchord_loopmix.so` 438,616 B, `libonnxruntime.so` 28,637,280 B — all unchanged vs vc68) |
| Git invariants on `bk-android` | `HEAD=dc78e0d`, `git diff --cached \| wc -l = 6085` (SHA `4a4c6821a4a7d463a8cfcb8be45752b5f0fa81f70bd40de141ff27f4efb804e3`), `git diff HEAD \| wc -l = 22862` (SHA `3db58c05ffd858648776f836d31b1fffcc29b22245cc68b650df4fbdc186ddef` = full patch, 102 files). No `commit`/`reset`/`checkout --`. One `git add -N` (KickLock.kt) to include an otherwise-untracked new file; cached count/SHA unchanged |

---

## 3. What shipped in vc69 (P0 + P1 + P2 + NPU, vc68 → vc69)

Delta patch (`bitchord-djmix27.patch`, 28 files, 9,749 lines): 19 new + 9 modified. New: `TransitionAlternatives.kt`, `KickLock.kt`, `VocalDepthFilter.kt`, `R128Limiter.kt`, `ReleaseSplice.kt`, `BeatFlow.kt`, `TransitionEvidence.kt`, `NpuSupport.kt`, `NpuSession.kt`, `BeatTracker.kt` wiring, `Djmix27AppTest.kt` + 8 shared test files. Modified: `CrossfadeController.kt`, `LoopMixSession.kt`, `TrackAnalyzer.kt`, `DjMix.kt`, `LoopMix.kt`, `MixVerify.kt`, `TransitionPlanner.kt`, `VocalTracker.kt`, `build.gradle.kts` (version only).

### P0 — Decline rescues (muffle / drop-in / beatless bridge)

- **TransitionAlternatives** (491 lines, ported from Orchard `pairTransitionGates.js`/`muffle.js`/`beatlessBridge.js`/`transitionArrangement.js`): when a pair would otherwise be declined or produce a weak blend, offer muffle (8-beat lead low-pass cover, both decks at native tempo, BitChord covers the 8–10% tempo band Orchard excludes on stretch), drop-in (8-beat overlap + 0.75 s echo tail + energy gate), or beatless bridge (4–12 s span over measured beatless presence) instead of raw overlap/silence. Every family requires bass-curve evidence; pairs with no measured curves keep pre-vc69 decline behaviour exactly (proven by `declineWithoutCurvesKeepsBoundedFourSecondFilter`).
- **Wiring**: `LoopMix` flagged rungs use `muffleFits`/`dropInFits`/`beatlessBridgeFits`; `TransitionPlanner`/`planTransition` emits `MUFFLE`/`DROP_IN`/`BEATLESS_BRIDGE` styles end-to-end (asserted in `Djmix27AppTest`); `DjMix` structural remapping passes alternative spans through untouched so rescues are never resized.
- **Kick-lock anchors** (`KickLock`, P1 engine for P0): incoming anchors are snapped to 1/8-beat kick-lock (≈0.125 beat tolerance) — Orchard downbeat-only anchoring tightened so swaps never land half a beat off the kick.

### P1 — Vocal depth / kick-lock / release-splice / R128

- **VocalDepthFilter** (204 lines): measures depth from the vocal-activity mask the Syvenu/umxhq pipeline already produced; presence interpolates the mask against energy-curve sample times (fallback: open-unmix window means, then whole-track probability). Scales outgoing low-pass / incoming high-pass travel at each mix instant (running-max, so the filter never reopens mid-transition); unmeasured pairs stay at neutral full spend (pre-vc69 render). Decorated via `TransitionPlanner.vocalDepthCurve`; rendered in `CrossfadeController` (`depthCurve` + `scaleTravel`).
- **ReleaseSplice** (scalar, allocation-free): 0.25 s cosine equal-power handback from wet echo/reverb tail to dry at transition boundaries (Orchard 1024-frame / 0.25 s native edge mirrored on the mix clock). Programmed in `LoopMixSession` via `envelopeArrays`.
- **R128Limiter** (189 lines): sum-only look-ahead limiter, EBU R128 ceilings (−0.5 dBFS, 5 ms lookahead, 80 ms release, Orchard `LoudnessConfig` defaults). Limits only summed peaks past the ceiling; scalar/reentrant so it can lift into the native loop later. `MixVerify` deepens from a scalar peak check into measured repair against the same ceiling constants.

### P2 — Beat flow / evidence dump

- **BeatFlow** (220 lines, from Orchard `transitionFlow.js` beatFlow: 16 overlap samples, equal-power weighting, deficit + late-drop + wait/skip accounting): scores how well the beat the listener already hears carries through the overlap and past handoff (low-band energy as the bass proxy, normalized per-track; neutral 0.5 without curves). Scores feed the alternative-transition ranker (Orchard flow weight 0.18 lineage) so the rescue whose beat survives wins.
- **TransitionEvidence** (169 lines, from Orchard `ORCHARD_MIX_DUMP` planner dump): per-transition evidence record (out/in ids, style, reason, gates, scores, confidence, flowScore) as JSONL into the existing automix-telemetry stream (Orchard `input`/`live`/`native` shape, flattened). In-memory cache (cap 200, single-writer snapshot reads) never blocks playback or touches PCM; dumped in `CrossfadeController` alongside the vocal-depth render path.

### NPU QNN EP (see §5 and `djmix27-proof/npu-status.md` for the precise statement)

- `NpuSupport` + `NpuSession` wire Snapdragon QNN (HTP burst, graph-opt 3, context caching) for the two existing ONNX models with **CPU fallback kept** (Orchard hard-fails; BitChord stays playable anywhere). `TrackAnalyzer` detects vendor at runtime and resolves `/vendor/lib64/libQnnHtp.so`-family backend paths; `BeatTracker`/`VocalTracker` delegate session open to `NpuSession` with CPU-safe defaults. No new Gradle dependency, no new models, no QNN libs bundled.

### Merge / compatibility notes

- All cross-track scoring remains scalars-only; zero audio-thread allocations in render paths (`ReleaseSplice`/`R128Limiter` pure-scalar, `TransitionEvidence` bounded cache). GPL-3.0+ clean. `arm64-v8a`/`oldkey` only. Full patch 22,862 lines from `dc78e0d`; cached diff of 6,085 lines untouched.

---

## 4. Artifacts & proof

| Artifact | Lines / Bytes | SHA-256 |
|---|---|---|
| APK `bitchord-2.2.3-djmix27-automix-arm64-v8a-oldkey.apk` (mirrored, install pending) | 87,551,587 B | `abb336daf8017235df12dce4e40c2952c3235674feaa9305b97290e79909a73a` |
| `patches/bitchord-djmix27.patch` (vc69 delta, 28 files) | 9,749 lines | `5bf91488fef249abc5c2a6f833e4dc3974f9cdde952a5e2921dd94c218c85792` |
| `patches/bitchord-djmix27-full.patch` (102 files from `dc78e0d`) | 22,862 lines | `3db58c05ffd858648776f836d31b1fffcc29b22245cc68b650df4fbdc186ddef` |
| Proof `evidence/bitchord-device-evidence/djmix27-proof/` (`apk-verify.txt` 56 lines, `test-results.txt` 8 lines fresh 1760, `git-invariants.txt` 112 lines, `mirror-report.md`, `npu-status.md`, `install-pending.txt`) | — | `apk-verify.txt` `1dbaafda…` |
| This handover | — | — |
| Wrap-up `docs/BITCHORD_HANDOVER_VC69_WRAPUP.md` | — | — |

---

## 5. NPU caveats (read before claiming anything)

- **No QNN/native libs are bundled.** The APK carries 14 `arm64-v8a` `.so` only; `libonnxruntime.so` (28,637,280 B) + `libonnxruntime4j_jni.so` (83,224 B) are byte-identical to vc68. There is no `libQnnHtp.so` / `libQnnHtpV68Stub.so` / `libQnnHtpV81Skel.so` / `liborchard_qnn.so` in the APK. The QNN execution provider is resolved inside the bundled ONNX Runtime at runtime; the HTP backend library is expected on device firmware (`/vendor/lib64/libQnnHtp.so` et al.). **The NPU feature is therefore in the APK as code + option maps, and explicitly requires runtime vendor libs from the device.**
- **Runtime detection is Qualcomm-only** (SoC `Qualcomm` / `qcom` / Samsung `SM…`); MediaTek and Pixel Tensor run CPU by design (no QNN attempt).
- **CPU fallback is kept** (`disable_cpu_ep_fallback=0`); QNN open failure warns once and falls back to the pre-vc69 CPU session. Only dual failure surfaces as model-unavailable.
- **On-device dispatch is UNVERIFIED** — no Snapdragon log/telemetry has proven a QNN session opened, numerical parity with CPU, or any latency win. Do not claim NPU speedup/quality until a field test with log capture proves it (§6.3).

---

## 6. Exact next steps (in order)

1. **Re-pair `1439ebea`** (Wireless Debugging ON, phone on LAN) → read-only pre snapshots → single-shot `adb install -r` (full sequence in `djmix27-proof/install-pending.txt`). Verify `versionCode=69` / `2.2.3-djmix27` fresh `lastUpdateTime`. No `uninstall`/`pm clear`/`force-stop`/queue edits.
2. **Four-pair listening gate** — user queues/plays/records; agent stays hands-off. Per pair report: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? decline rescued (muffle/drop-in/bridge) vs hard cut? dead zone >8 bars or mid-vocal cut? Pairs: `This Is What You Came For` → `One Kiss`; `Beautiful` → `Never Let Go`; `Drip` → `UP`; `Technologic` → `Guess`. Note which pairs took the P0 rescues via the new evidence records.
3. **Telemetry + NPU log check** — pull fresh `automix-telemetry.jsonl` into a new file in `djmix27-proof/` (never overwrite prior files); correlate `mix.fire`/`mix.complete`/`mix.handoff.deferred`/`mix.verify`/`mix.plan` + `TransitionEvidence` records (style/reason/gates/scores/flowScore) against what was heard. On a Snapdragon device, capture logcat during one analysis pass: confirm QNN session open (absence of "NPU QNN open failed; falling back to CPU") and compare analysis time/quality vs CPU before claiming anything about the NPU.
4. **Hands-off vs touched playback test** — one Calvin playthrough without touching the device, then read-only telemetry pull; check whether `mix.pause.detected` still appears hands-off.
5. **Re-run stale suites** if a 4-way grand is needed: `:shared:testDebugUnitTest` + `:shared:testReleaseUnitTest` (15:35Z stale, 309 each) to fold into the fresh total.
6. **Never** claim audio quality or NPU dispatch without ears + field test. `UNVERIFIED UNTIL USER EARS/FIELD TEST`.

*End of handover. vc69 is on the builder and staged — install it, then the verdict belongs to your ears (and, for the NPU, to a Snapdragon field log).*
