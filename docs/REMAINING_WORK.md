# Remaining work

## vc78 candidate — reproduced wet-stop defect

Pending candidate: **78 / 2.2.3-djmix36**, based on installed `vc77`.
The `vc77` listening failure remains authoritative; this candidate has not
yet been built or heard on device.

- Added native `--reverb-stop-probe` using the production send/release/stop
  geometry and deterministic broadband transients, with no artificial decay gap.
  Unmodified `vc77` fails: standard wash late-50 ms RMS **−49.174 dBFS**, only
  **26.239 dB** below the active window, boundary step **0.003066**. The dry-gain
  exit gate still reports zero violations. This demonstrates a wet cutoff, but
  does not establish that every reported abrupt transition has this cause.
- Candidate retires actual reverb output with a smooth envelope ending at
  `stopAt`; zero send stops new input rather than instantly zeroing stored wet
  output. Original probe passes: standard late RMS **−99.897 dBFS**, drop
  **76.962 dB**, step below threshold. Removing retirement makes the probe fail.
- Wet-only preservation passes: early post-release RMS **−28.985 / −29.844 dBFS**,
  within 0.5 dB of the old renderer. Deliberately muting reverb fails preservation;
  removing retirement restores the boundary failure. Scheduled echo behavior
  remains unchanged. Focused review found no remaining blocker for the reproduced
  reverb geometry; full native gate passed after reconciliation (exit 0).
  Production probe is required by both Actions jobs and `tools/vc75-gate.sh`.
  Android CI/build, signing, install, and listening checks are still pending.
- Pause-reason hypothesis withdrawn: shipped Media3 1.11.0 bytecode confirms
  `USER_REQUEST=1`, `END_OF_MEDIA_ITEM=5`. The replay pause is not evidence of
  an internal end-of-item pause. No auto-resume or user-pause behavior change
  is planned for this candidate.

---

Status snapshot, 2026-11-17 (local date). **Candidate: vc77 / 2.2.3-djmix35;
signed and installed; transition/listening verification pending.** Earlier records below
are historical where they conflict with this snapshot.

- Source: branch `vc77-ci`, commit `39290def8024a66840d1caa993a83f4d1c440f97`.
- [Actions run 43912914370](https://github.com/survey-sudo/bitchord-mix/actions/runs/43912914370)
  passed both jobs, including the actual release assemble/audit/upload steps
  (the job's `continue-on-error` did not mask a failure).
- Downloaded HTML reports independently checked: shared **495 tests, 0 failures,
  0 ignored**; app **1,373 tests, 0 failures, 1 ignored** (live Genius test).
  No XML was uploaded by this workflow.
- Latest unsigned APK SHA-256:
  `2cc665fac3fc6b4161eec897c02e703fcb7fd03d1becd9078382ac871d57e66f`.
  Artifact and reports are outside the repo under
  `~/Bitchord/work/vc77-followup-ci/43912914370/`.
- Follow-ups include prime stem retention, accepted-only phrase script/detail
  commits, abnormal-exit survivor filter recovery, stale-tail cleanup, a
  buffering watchdog, and native-floor stem sizing with bounded endpoint
  completion. These passed CI; audible behavior remains unverified.
- Remaining review items: active-sweep flush continuity, shift retry lead,
  per-build timing isolation, inference deadline after a model-lock wait, and
  a production-envelope reverb-tail test. The timing fix is a proposal only.
- Signed on `bk-android` after its cooldown, without uploading the key to GitHub.
  Certificate SHA-256 matches
  `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`;
  v2/v3 signature verification, 16 KiB alignment check, and APK audit passed.
  Signed APK: **67,548,469 B**, SHA-256
  `485dd2aa978902d6a6887b958f34c6a5524cd78903c6458f9221d239d6752909`,
  retained under the artifact directory's `signed/` folder.
- Wi-Fi ADB update on `1439ebea`: `adb install -r` succeeded; package manager
  confirms **77 / 2.2.3-djmix35**; launched successfully (PID 17782 at check).
  Existing app data/23-item queue retained and pre-update telemetry saved outside
  the repo. First 15-minute capture completed: **2 loop transitions**, both
  `mix.verify.ok=true` and `exitGateViolations=0`; handover heard minus expected
  offsets **+58 ms / +60 ms**. One `stem-duck` decision logged measured
  `clashE=0.14529196781328904` (0 null-clash decisions in this small sample).
  This proves a measured duck decision, not successful ONNX separation or
  perceptual stem quality. No `mix.arm.death`, `shift-bar-null`, or fatal exception
  was observed. Neither targeted reverb-tail coverage nor a rare-failure rate
  is established by two transitions. Playback was paused at the final check;
  it was left paused. Logs/fresh telemetry are under the artifact's `device-after/`.
  `vc76` reproduced the demuffle/reverb zap and null stem-clash metric;
  **Listening result: user reports the zap is still present, abrupt and not
  graceful. The vc77 audible-fix claim is refuted; release remains blocked.**
  A subsequent manual replay logged a user-request pause during a live loop,
  then `mix.exit.guard` / 350 ms emergency tail at full retiree gain. Its
  causal relationship to the heard zap is not established. A focused lifecycle
  trace and production-envelope native probe are in progress. Blind A/B and
  targeted seek/reverb/abnormal-exit checks remain open; Apple Music parity is unproven.

Historical baseline: `vc76` / `2.2.3-djmix34`, signed APK `67,433,006 B`,
SHA-256 `02920d8278003c5fe9b492a3f0a0dc052ddb4b48a93d8e8ff2e2b84cb1e0ca79`,
signed with cert SHA-256
`20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` on
`bk-android` (`app-prod-arm64-v8a-release.apk`), pulled to
`~/Bitchord/work/vc76-release/`. Prior `vc75` (`78d006d7...`, `b7375e8`)
remains superseded before device verification.

The earlier pre-install baseline below is retained for reference; the latest
device findings and pending candidate are recorded above.

Test & gate record on `bk-android` at `vc76`: `:shared:jvmTest` **479/479**
pass; `:app:testProdDebugUnitTest` **1358/1359** pass (1 skipped), with only
unrelated env-dependent `QueueShuffleTierTest` `< 50ms` timing benchmark
failing under contention and passing **27/27** (`shuffle: 2.42ms`,
`restore: 3.55ms`) isolated; `./tools/vc75-gate.sh` fixed (`fail` check moved
after calibration) and passing (16 native + 11 calibration invariants).

---

## Automix overhaul (council-built, 2026-11-16)

Two council rounds (3 of 4 seats, then 4 of 4). Verdict: stems are the unfair
advantage and were unused by default; the clash measurement now decides
ranking, veto, and the render duck. (`StemClash.rank` remains pinned-only /
unused in production.)

| # | Deliverable | Status |
|---|---|---|
| D1 | Energy-weighted clash core (`f59044a` + Pass-2 `SongMapEnrichment.enrich` + `SongMap` equals/hashCode + `CrossfadeController.memoKey` + `PhrasePlanner.vocalClashEnergy` `\|\|` unmeasured guard): fixed Pass-1 `PhraseMaps` bypass where `StructureAnalyzer.fromPcm(..., mask=null)` left `vocalEnergy` empty and `sungBars` all-false in production; `SongMap.vocalEnergy`, `PhrasePlanner.vocalClashEnergy`, veto on measured `E > 0.20`, weights `0.18/0.30/0.12/0.14/0.08/0.08/0.10`, cut as `0.20×discardedVocalEnergy + 0.30×clashE`, bonuses capped `0.08` | **Landed, unit-tested (479/479 `:shared:jvmTest`), awaits on-device logs.** |
| D2 | Stem-duck default-on (`d87faae` + `LoopMixDuck.decide` + `StemClash.duckGainFor` floor wiring): trigger `E > 0.12`, asymmetric, floor `0.15f`, boolean fallback when unmeasured, `PhraseChoice.vocalClashEnergy` plumbed; extracted pure `LoopMixDuck.decide()` and wired `duckGainFor` (floor `0.15f`) into outgoing and incoming stem duck; pinned with `LoopMixDuckTest` and off-device `native/loopmix/loop_mix_renderer_ab_main.cpp --selftest` (`duck-off -10.434 dB`, `duck-gain(0.18) -12.555 dB`, `fixed-0.15 / duck-gain(0.60) -12.749 dB`, `exitGateViolations=0`) | **Landed & verified off-device, awaits on-device listen.** |
| D4 | `BeatFlow.rankScore` coverage-lerped `w = 0.18 + 0.27×coverage` (`b6d1d95`), penalty `0.7/0.3/0.2` | **Landed, unit-tested (479/479), awaits on-device listen.** |
| D5 | `MixWeightTuner` deleted (`d87faae`) + its 3 `Djmix25` tests | **Landed** |
| D3 | Seam-local tempo/key/energy; `1.09→1.06` cap | **Deferred**: needs per-bar key/BPM fields `SongMap` doesn't carry; cap ships only bundled with it (council, 3/3) |
| D5b | `MixTemplate` 16→4 | **Deferred** until D6 shows which rungs win (council, 3/3) |
| D6 | Blind A/B gate: 20 pairs, stem-on vs stem-off vs Apple, need ≥60% on clash pairs | **Blocked** on phone (`adb` disconnected) + Apple Automix WAV captures; off-device WAV A/B harness (`native/loopmix/loop_mix_renderer_ab_main.cpp`) is ready for stem-on vs stem-off renders |

Not raised, on purpose: the detector threshold stays at `0.25`. It is a
detector on the analyzer's calibrated scale (chorus `0.57–0.71`, soft verse
`0.2–0.4`), not the separator's gain; `0.50` would sit inside the chorus band
and blind the planner to the soft verse the whole overhaul exists to catch.
Pinned in `StemClashTest.detectorThresholdMustStayOnTheDetectorScale`.

Build rule: Gradle runs on GitHub Actions or `bk-android`, never on this PC.
The current path builds unsigned on Actions and signs with the existing key
on `bk-android`; APKs and keystores stay outside the repository.

---

## Measured: the stem gain curve (calibration harness, 2026-11-16)

`native/analyzer/window_calibration.cpp` reads the Wiener transfer function out
of the shipped code rather than out of the comment, and pins it:

| mask value `r` | 0.10 | 0.25 | 0.35 | 0.50 | 0.62 | 0.80 | 1.00 |
|---|---|---|---|---|---|---|---|
| energy kept | 1.2% | 10.0% | 22.5% | 50.0% | 72.7% | 94.1% | 100% |

The curve is `r²/(r²+(1−r)²)`, so **every decision threshold in the tree sits
below 50%**: `VOCAL_ACTIVE_THRESHOLD = 0.25` keeps 10% of energy,
`VocalDepthFilter.VOICE_FLOOR = 0.35` keeps 22.5%, `vocalProbability ≥ 0.62`
keeps 72.7%. A threshold is not a gain, and none of the three has been checked
against a listening test.

Also pinned, and previously only asserted in a comment:

- `vocals + instrumental` reconstructs dry to `2.5e-09` RMS at dry RMS `0.255`
  — the complement identity the single-pass `dry − vocals` design rests on.
- `(dry − vocals)` matches the complement-mask stem to `2.9e-09`.
- Best alignment lag is **0 samples** across a ±64 search, so the local
  subtraction cannot comb-filter.
- Boolean frame-count clash reports 1.000 for both a quiet verse and a loud
  chorus; the energy-weighted mean reports 0.090 and 0.810.

---

## P0 — blocked on the user's ears

**Nothing about audio quality is proven.** The `vc74` analysis fixes are measured
(`mel 85 ms`, `infer 1,887 ms`, `vocal mask` present, `structure exceeded` 0), but the
mix behaviour is not.

| Item | State |
|---|---|
| Phrase exits not cutting Song A early | **UNVERIFIED** — no phrase plan ran |
| Song B entering past silence | **UNVERIFIED** |
| Two-deck beatmatch | **UNVERIFIED** |
| No muffle / demuffle on vocals | **UNVERIFIED** |
| Clean ≤200 Hz bass handover | **UNVERIFIED** |

Why no phrase plan ran on the last session: the only analysed pair was
`134.83 → 148.61 BPM` = ratio `1.1022`, above `AUDIBLE_MAX_RATE = 1.09`, so it was
correctly declined to `DJ_FILTER`. The next pair had `qualityB: none`.

Listening gate:

- `Beautiful` → `Never Let Go`
- `Drip` → `UP`
- `This Is What You Came For` → `One Kiss`
- `Technologic` → `Guess`

Per pair: (a) Song A reaches near its real end, (b) Song B enters on music,
(c) both decks beatmatch, (d) no underwater muffle on vocals, clean low-end handover.

---

## P1 — infrastructure

### Sync `bk-android` with origin

`bk-android` is the build source of truth and currently sits at `4e19a2a`, while
origin is at `8ba485d` (CI + grok-box kit). After the cooldown lifts:

```bash
cd /data/workspace/bitchord-task2/work/BitChord
git pull --ff-only origin master
git log --oneline -3
```

Only `chop.py` is untracked, so this fast-forwards cleanly.

### grok-box migration

Blocked: `grok-box` is `offline, last seen 40m ago` on the tailnet
(`tx 780 rx 0`). Kit is ready in `docs/tools/grokbox/`. When it answers:

1. `bootstrap.sh --check` and compare against the measured table.
2. `migrate-repo.md` — ~165 M transfer, excludes rebuildable output.
3. `verify-build.md` — rebuild the same commit and compare the APK.

Migration counts as done only when the same source produces the same bytes.

### CI

`.github/workflows/android-ci.yml` replaces upstream's broken `android.yml`
(wrong branch, no `bundleWear` flag, no pinned NDK). Two parallel jobs: unit tests
and the arm64 release APK.

Signing is optional — with no secrets it emits `app-release-unsigned.apk`. To get a
signed, installable APK, add four repository secrets:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

**Current decision:** use unsigned Actions output and sign on `bk-android`;
no signing secrets need to be uploaded to GitHub. The latest run is recorded
in the status snapshot above; older timing records below are historical.

Latest CI record: run `43847603785` at `0157ee5` (`Unit tests` passed in `8m 24s`; `Release APK (arm64)` failed at `51m 1s` when the hosted runner lost communication). `.github/workflows/android-ci.yml` has since been hardened (`-Xmx3g`, `--max-workers=2 --no-daemon`, pre-build `df -h`, failure diagnostics upload, and `continue-on-error: true` on `release` until two consecutive green runs).

Historical `vc74` CI baseline (`5fbe776`, run `43767187882`, 2026-11-15): `Unit tests` finished in `8m 48s` (`528 s`), `Release APK (arm64)` finished in `17m 41s` (`1,061 s`), and the parallel run concluded in `17m 45s` (`1,065 s`). `assembleProdRelease` alone took `14m 21s` (`861 s`).

- **Payload parity (`docs/ci-parity-vc74.md`):** **1,584 of 1,592 entries** are
  SHA-256 identical to the verified build-box `vc74` APK, including all three
  `classes*.dex` files (`classes.dex`, `classes2.dex`, `classes3.dex`). The 8
  differences are `META-INF/version-control-info.textproto` (records the git
  commit: `dc78e0d` vs `5fbe776`) and 20 bytes inside `.note.gnu.build-id` in each
  of the seven `libbitchord_*.so` — a host-path hash from `lld` that survives into
  the build-id but does not touch `.text`, `.rodata`, `.data`, or symbols.
- **Native audit of the CI artifact:** exactly one `libonnxruntime.so` at
  `34,208,672 B` (SHA-256 identical to the box build), zero QNN/HTP libraries, all
  seven `libbitchord_*` present, version `74` / `2.2.3-djmix32`.
- **SDK caching overhead:** `Install SDK packages` took only `40 s` (release) /
  `44 s` (unit tests), while Gradle cache restore is already `26 s` / `34 s` and
  `Free disk space` costs `63 s` / `73 s`. Caching the Android SDK/NDK is not
  worth it; the compile (`14m 21s`) is the cost.
- **Signing status (verified fact):** the CI artifact (`67,411,246 B`, SHA-256
  `00c877c55162fa2202a8b00aab2ae38afc58611ccbd5f8ff6e9cd9f34aa022db`) is
  **unsigned** (zero `META-INF/*.RSA`/`*.SF` entries and no `APK Sig Block 42`), so
  it cannot be installed on the phone, and a differently-signed APK cannot replace
  the installed app (`adb install -r` fails on signature mismatch; uninstalling is
  off the table). Adding the four repository signing secrets makes CI emit an
  identical APK signed with the same keystore so it installs over the current one
  with `-r`.

### Known debt: `compileSdk` 36 vs InnerTubeX's declared 37

`innertubex-android` declares `minCompileSdk = 37`, so `app`, `shared` and
`sharedUi` all disable their `*AarMetadata` tasks. This project compiles against
36: AGP 8.13.1's maximum recommended value, and the target the release was built
and verified against. InnerTubeX's bytecode uses no API-37 symbols, so the app
runs, but the check is a real safety net that is off.

**The platform is no longer the blocker.** `bk-android` now has `android-37` and
`android-37.0` installed, and CI can install `platforms;android-37`. The proper
fix is therefore available: bump `compileSdk` to 37 in all three modules, delete
the disables, and re-verify on device. It is deferred only because it changes the
build target, and the current APK is the one awaiting a listening test.

Because the checks are disabled, each library build script registers
`prepareAarMetadataCheckDirs`, which creates the directory AGP's bundle tasks
declare as an input. Without it, a clean checkout fails Gradle input validation —
that broke the first two CI release runs (2026-11-16).

---

## P2 — NPU (why it is off, and what it would take)

`NpuSupport.QNN_ENABLED = false`. This is not a preference — the path has never
executed an inference on the device.

Two independent defects:

1. **QAIRT device config** — `QNN_DEVICE_ERROR_INVALID_CONFIG` before partitioning.
   The bundled stack carries Hexagon **V73 and V75** because `SM8635` could not be
   pinned from the QAIRT 2.51 sources, and no `htp_arch` provider option is set.
   Likely fix: set `htp_arch` explicitly in `NpuSupport.qnnProviderOptions`.
2. **Reduced ORT build** — `npu-addon/ops.config` lists only the ops present in the
   two models, so the build drops the ops ORT's optimizer introduces at load time
   (`MatMulIntegerToFloat`, `DynamicQuantizeMatMul`). Both models then fail on
   **both** backends, which is why `vc69` had no working analysis at all.

To re-enable, in order:

1. Add the optimizer-introduced ops to `npu-addon/ops.config`.
2. Rebuild ORT 1.31.0 with QNN EP (`npu-addon/build-ort.sh`) — needs the 2.8 GB
   QAIRT SDK subset and an ORT source checkout.
3. Re-add `npu-addon/prebuilt/jniLibs` as a jniLibs source set with pickFirsts.
4. Prove one inference on the Hexagon via `NpuSelfTest` before shipping.
5. Re-bundle ~100 MB of QAIRT libs, and re-check APK size.

Cost/benefit: the models are small (`beat_this` 22 MB q8, `umxhq` 8.6 MB int8) and
analysis is one-time per track, currently ~2 s. The NPU buys seconds nobody sees, in
exchange for 100 MB and a vendor stack that has never worked. **Do this after the
audio is right, not before.**

---

## P3 — features not built

The vision playbook's iOS 27 parity ladder, checked against code:

| Rung | Name | Status |
|---|---|---|
| 0 | `GAPLESS` | implemented |
| 1 | `LOOP_MIX` | implemented |
| 2 | `OUTRO_LOOP_DROP` | **not implemented** (0 files) |
| 3 | `BEAT_BLEND` | covered by `DJ_BLEND` + beatmatch |
| 4 | `ECHO_HANDOFF` | **partial** — `ECHO_RIDE` exists, tempo-jump rescue does not |
| 5 | `SMART_CROSSFADE` | covered by `EQUAL_POWER` / `GAPLESS` |

What exists today: `TransitionStyle` = `EQUAL_POWER, GAPLESS, DJ_BLEND, DJ_FILTER,
MUFFLE, DROP_IN, BEATLESS_BRIDGE`; filter variants = `SWEEP, BASS_HANDOFF,
ECHO_RIDE, DECLINE`; `MixTemplate` = `M1–M11, ROLLING, CUT_ON_ONE, ENERGY_LIFT,
RELAXED, VOCAL_OVER_OUTRO`.

### Native DSP debt

Deferred since djmix11: per-sample filter coefficient smoothing (the 64-frame zipper),
native flanger, policy-a curve, isolator lanes live, stems dry-run harness.

### Dual-ABI and emulator gates

`abiFilters` is `arm64-v8a` only. No `x86_64` build, no emulator runtime verification.
The vision playbook's Gate 2 and Gate 3 are unmet.

### Key accuracy

`Bubble Tea` `4B`, `Crescendo` `10A` vs `10B` — still unconfirmed by ears or telemetry.

### Last.fm credentials absent from the installed build

`buildConfigField("String", "LASTFM_API_KEY", ...)` in app/build.gradle.kts reads
`local.properties`, falling back to the `LASTFM_API_KEY` environment variable. The
`vc74` APK installed on the phone has both fields empty (proven by dex parity: the
CI build, which has no local.properties, produced byte-identical dex). So any
Last.fm-backed feature is inert on the device right now, and nothing about it can be
tested until those values are supplied to a build.

---

## Policy tunable, not a bug

`AUDIBLE_MAX_RATE = 1.09` (`TransitionPolicy.kt:90`). The `134.83 → 148.61` pair sits
at `1.1022`. Raising the ceiling to `1.11`–`1.12` would let it beatmatch. Real DJ gear
allows ±8 % (vinyl) to ±16 % (CDJ). Change only after the in-range pairs have been
heard, so a real regression is not masked by a wider window.

---

## Explicit non-goals

- **Demucs / `htdemucs` stems** — rejected on licence and memory (needs ~2.2 GB RAM).
  Only the bundled `umxhq` mask model is used.
- **QNN as a hard requirement** — CPU fallback stays on. The NPU is an optimisation,
  never a dependency.
