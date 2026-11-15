# Remaining work

Status snapshot, 2026-11-16. Everything below is either **blocked**, **measured**, or
**not started** — no item is claimed done without evidence.

Current build: `vc74` / `2.2.3-djmix32`, APK `67,431,730 B`,
SHA-256 `c3215537daa1ea7a01322db27b3fac735dfc75f356a2b2094ec1f16ec9deae14`,
installed on `1439ebea`.

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

**Decision pending:** whether the keystore goes into GitHub secrets at all.

The CI release job is now **proven green** on run `43767187882` (commit `5fbe776`,
2026-11-15): `Unit tests` finished in `8m 48s` (`528 s`), `Release APK (arm64)`
finished in `17m 41s` (`1,061 s`), and the parallel run concluded in `17m 45s`
(`1,065 s`). `assembleProdRelease` alone took `14m 21s` (`861 s`).

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
