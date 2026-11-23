# AutoMix vc84 handover — 2026-11-19

**Status: vc83 shipped & installed; vc84 integrated, not yet built/signed; NPU dormant. Do not install vc84 until noted gates clear.**

## Installed state

- Phone `1439ebea` (Poco F6 SM8635) has **vc83 / `2.2.3-djmix41` / versionCode 83** installed via Wi-Fi ADB `192.168.100.218:38533` (`adb install -r`, `Success`, `lastUpdateTime 2026-11-18 22:04:54`). Launched once (monkey, PID `19603` at check), no play/seek/queue commands, no data clear.
- vc83 source: `170328b` on `automix/readiness-repair-20261118` in `.slim/worktrees/automix-readiness-repair`.
- Signed APK: `release-vc83/artifacts/bitchord-vc83-djmix41.apk`, `67,556,661 B`, SHA-256 `eb2a3f188147e9b91bb3350bcaec6cbd4d4618698d746494280a0bd4094659d4`, v2+v3, cert `20c66ad6...`, audit PASS, 16 KiB aligned.
- Cue evidence: `/workspace/diagnostics/automix-readiness-repair-2026-11-18/` — `phase3-final` full suite `1934 tests, 0 failures, 0 errors, 1 skip (GeniusTest)`, exit `0`.
- Native (cue, `--loop-domain-probe` 63 pairs, `--selftest`, `--reverb-stop-probe`): all exit `0`.

## What vc83 fixed

- `driveArming()` only pre-filters outgoing `A` when incoming `B` is `STATE_READY` and before the fade point; otherwise keeps `A` open. `startFade()`→`driveFade()` immediately, no `800 Hz -> 7 kHz` reopen. `cancelArming()` for disable/pause/seek/timeout, `nowMs` injection, `survivorRecoveryPending`.
- `DECLINE` reverted to `vc81` disjoint exponents (`p^0.5` out / `p^1.9` in).
- Planner/FX/native baseline in `170328b`; loop/missed-splice not yet on shipped build.

## vc84 integration (this tree)

`automix/vc84-integration` at `b140313` (base `253cea2` NPU + `7c8a347` loop + sound-polish patch):

- **NPU swap (`253cea2`)**: `nirovi_qnn.onnx` (8,024,820 B) staged, pad-to-1500 adapter, `htp_arch=73`, strict probe, fingerprint EPContext, `libcdsprpc` gate. **Both flags OFF.** Cue: `1943 tests, 0 failures`. Real-mel gate **FAIL** (beat F1 `0.805`, snapped downbeat `0.657` vs `0.95` required) — do not enable.
- **Loop fix (`baf2b1a`)**: `8.5s` busy-prime budget, `tryLock` model gate + post-acquisition recheck, bounded decode futures, one guarded later-bar rescue, per-deck trace. `519 shared + 1434 app` green twice.
- **Sound polish (`f9f3bf61...`)**: `BASS_HANDOFF` cap, `PHRASE outLp/shape` consumption, 4-beat roll body/header, native roll gate. `r2` `1949/0/0/1`; `r3` evidence retained under tmux but **not yet verified** — cue was offline (`cue.example.ts.net` Tailscale offline).

`b140313` has **not** been built, signed, or installed. Native `echoWetRetireStart` gain-fallback clamped to `max(gainZero, stopAt-1s)` after DeepSeek review.

## Apple baseline (agreed)

Apple Music AutoMix is the "good" reference: no abrupt tonal jump, no solo-A muffle, beat-aligned, both songs intelligible, simple clean fallback. Beat blend handing midrange over (not doubling it) is intentional;_DECLINE mid overlap + dual vocal slam is not.

## Orchard NPU (dissected)

- Depot: `orchard-npu-v73.apk` `56,083,618 B`, `versionCode 1`, `1-qnn2.51`, `hasCode=false`, 7 natives (`libQnnHtpPrepare.so 88 MB`, reduced `libonnxruntime.so 12 MB`, `liborchard_qnn.so 384 KB`, `nirovi_qnn 8.0 MB`, `syvenu_qnn 725 KB`).
- Same-device Step A **passed** (user confirms smooth/fast on this Poco F6).
- **Cannot drop in**: BitChord models are dynamic-quant (`MatMulInteger`/`DynamicQuantizeLinear`), Orchard is static QDQ; signer/package mismatch; ORT collision (`34 MB` Maven vs `12 MB` reduced); no BitChord addon host. Tree-level V73/V75 natives are byte-identical to QAIRT `2.51.0`.
- **Gate before any build**: `disable_cpu_ep_fallback=1` + HTP node assignment in logcat (`Number of partitions supported by QNN EP`, QNN Delegation ≥90%, V73 Skel open, EPContext compile→load). Licence gate: PolyForm Shield / non-compete on Orchard `models/**` weights — confirm rights for exact `nirovi_qnn` hash.

## Blocked

1. **Cue offline** (`error 1033`, Tailscale). Need `cue_status` UP again before `r3` retrieval, vc84 full revalidation, ORT rebuild, or APK build. Heavy lifting only on `bk-android` / `cue` — no local Gradle/Android builds.
2. **Phone Wi-Fi ADB** was `offline` after `vc83` install (`192.168.100.218:42717`); re-enable Wireless Debugging on same Wi-Fi to reinstall.

## What not to do

- Do not ship vc84 without council re-review (GPT-6.1 Sol `#max` flagged PHRASE takeover double-count, M7 stop-gain, straight-roll resume flaws as P1s — still open in tree).
- Do not enable NPU, rebuild ORT, or sign an ORT without the HTP trace.
- Do not resume the stopped `automix-82-integration` WIP (`8807469` + dirty runtime) — it starved FX and omitted non-4/4 handling.
- Do not wipe queue/data/uninstall on phone. `adb install -r` only with version bump.

## Repro / evidence

- Handover source: this file.
- Stop snapshot: `automix-readiness-repair-2026-11-18/stop-handoff-131855/`
- Cue verifier ledger: `/workspace/diagnostics/automix-readiness-repair-2026-11-18/cue-validation/`
- Orchard depot: `https://depot.sfg545.dev/android/npu/npu-addons.json`
