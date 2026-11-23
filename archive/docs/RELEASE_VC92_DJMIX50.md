# vc92 / 2.2.3-djmix50 — handoff EOF, loop cleanup authority, watchdog, honest NATURAL metric

Base: `vc91-djmix49 @ 23adab5` on `dvb:/home/boxes/bitchord-mix`.
Branch: `vc92-djmix50`.

## What vc92 changes

### Bug 1 — `handoff_pcm_before_protected_event` (8/8 on `native-bridge / Natural handoff`)

Two independent shortfalls, both fixed:

1. **Plan-time EOF clamp** (`LoopMix.echoPlan`, natural branch). The container
   `outDuration` overshoots real decoded Opus/AAC EOF by 6–60 ms, so a natural
   handoff whose dry end equals `outDuration` fails the strict coverage gate.
   The natural cut is now
   `minOf(outDuration, effectiveEnd, maxOf(earliestOutSec + bar, outDuration - 0.06))`,
   still honouring `minCut = maxOf(earliestOutSec + bar, effectiveEnd - 4 * bar)`.
2. **Build-time bounded tolerance** (`LoopMixSession.coversHandoffPcm` /
   `coversPhysical`). New `endToleranceSec` applies **only** to the end check,
   never the start check. `build()` passes `0.08` only for a natural handoff
   whose protected cut sits within 0.12 s of container duration; measured Echo
   handoffs stay exact (`0.0`).
3. **Late-start zero pad** (`TrackAnalyzer.padLateStart`). `MediaCodec` under
   `SEEK_TO_PREVIOUS_SYNC` can start a few ms after the requested position
   (device: `1.81 -> 1.821`). A late start ≤ 80 ms is zero-padded and re-anchored
   to the requested time; anything later fails closed.

### Bug 2 — `loop-cleanup-incoming-unavailable` / `adoption-refused`

`CrossfadeController.finishLoop` now decides cleanliness from the **surviving
deck's authority** (`survivor.isPlaying` + `STATE_READY`/`STATE_BUFFERING`,
exit-gate violations, `DELEGATE_FULLY_CONSUMED`) instead of the retired outgoing
player's `isPlaying`. A healthy handoff with a missing incoming clock completes
normally instead of bailing to `emergency-retire-guard`.

`loopBail` mutes the incoming deck whenever it never became the session
(`!wasHandedOff`), and mutes the retiring outgoing deck before `finish()` only
when handoff + takeover both happened, the delegate was fully consumed, and
`exitGateViolations() == 0`. Every other bail keeps `RetireGuard` intact.

### Bug 3 — stale `mix.arm.death`

The terminal IDLE block of `finish()` now clears `shownCountdownKey`,
`shownLoopStartMs`, and `watchdogFired`, so a finished pair cannot fire the
shown-countdown watchdog minutes later. Early returns (`TAIL`, `finishLoop`)
never reach that block and keep their state.

### Bug 4 — NATURAL metric

`technique:"natural"` rows are plain track-end advances
(`executionScope:"normal-queue-advance-no-overlap"`), not transitions. Both
`tools/automix-telemetry-diff.py` and `tools/vc91-benchmark-dataviz.py` now:

- score `NATURAL` from `mix.fire` (authoritative), and
- exclude queue advances from every plan-based share, reporting them separately,
- and count "natural handoff chosen then aborted" as its own metric.

Effect on the vc91 evidence: final-verdict `NATURAL` over real transitions is
**0 %** in both builds (vc90 0/162, vc91 0/23); the authoritative fired number is
`0/88 -> 0/19`. The old `49 % / 61.8 %` headline was 113 and 21 queue advances.

## Council verdict (2026-11-23, `deepai/muse-ember#max`, single-agent synthesis)

The four roster seats failed with "service temporarily unavailable" (provider-side),
so this is the orchestrator escalating the model rather than a four-seat consensus.
Recorded as such; do not cite it as multi-model agreement.

**Verdict: principles right, phasing order wrong.** Honest metrics, two-artifact
builds, flags-off-by-default, and Bug1–4 before new features are all correct. The
15-gap inventory is probably right as an inventory but mis-weighted: correctness
gaps that make `NATURAL` fire at all are treated as one workstream among many,
while Apple parity is deferred to Phase 9 listening validation. That inverts the
risk — if transitions are musically wrong at Phase 9, `MASK_DUCKED`, FxPolicy, and
`MixTemplateIR` built in Phases 2–7 are built on a broken base.

Demanded reordering (adopted into Phase 0 below):

1. **Device validation moves into Phase 0, immediately after Bug1–4.** `adb` offline
   on `1439ebea` = zero evidence vc92 works on the POCO F6. `handoff_pcm` (8),
   retire-guard (4), stale death (2) are exercised and dying; that needs on-device
   soak *before* ship.
2. **Listening validation becomes a continuous gate**, not a Phase 9 discovery.
   After Bug1–4: a small blind spot-check (20+ real-queue transitions; gap/overlap,
   double-beat, vocal clash, loudness jump, premature EOF cut). Phase 9 becomes
   acceptance, not discovery.
3. **Signed-APK provenance becomes CI-enforced** (two-artifact rule, benchmark flag
   OFF, `QNN_ENABLED=false`, `MUSICAL_FX_POLICY_ENABLED=false`, versionCode bump,
   install-over-vc91 upgrade test, checksum of what was signed) — not a manual flip
   remembered at release.
4. **Features gate individually, after musical correctness**: (a) `NATURAL` fires on
   real queues, (b) beat/key/vocal/energy correctness, (c) *then* `MASK_DUCKED`,
   blend credit, FxPolicy. Stem-duck and FX on top of 0 % `NATURAL` mask alignment
   errors and add artifacts.

Most dangerous assumptions:

1. "Bug1 clamp + tolerance + Bug2 survivor cleanup will make `NATURAL` fire." Two
   builds of the `technique:natural` mislabel hide the truth — real `NATURAL` is
   0 % in both. No evidence yet the planner *selects* natural for most real tracks;
   the selection policy may be the actual blocker.
2. "Controller lane green + JVM suite = audio correctness." JVM/fake-`AudioTrack`
   tests cannot reproduce PCM handoff timing, retire-guard races, Bluetooth, Doze,
   thermal throttling, or POCO F6 codec behaviour. The device being offline leaves
   this assumption untested where it matters.
3. "Apple parity = add features (duck, FxPolicy, IR, NPU)." XNNPACK EP presence in
   the bundled ORT 1.31.0 ≠ real-time headroom; QNN is off and the harness is
   measurement-only. Flags-off features give architecture confidence without
   musical evidence.

Missing gates before a ship decision:

- **On-device soak**: wireless debugging re-enabled, signed vc92 on `1439ebea`,
  prescribed real-library queue (50–100 tracks), `mix.fire` `NATURAL` %, `planAge>1h`,
  underruns, `handoff_pcm`/retire-guard/stale-death, crash/ANR, battery. Ship
  requires `NATURAL` > 0 sustained on *eligible* transitions at a threshold agreed
  in advance (e.g. ≥ 30–40 % of eligible) — not "improved".
- **Metric-honesty regression test**: `technique:natural` deprecated in reporting;
  everything on `mix.fire`; a test that would have caught the 113/21 mislabel.
- **Two-artifact CI gate**: benchmark harness OFF verified in the shipped binary,
  QNN off, FxPolicy off, `MASK_DUCKED` off by default; flag audit in the shipped APK.
- **Signing gate**: non-debug signed APK, correct key, versionCode bump, fresh-install
  + upgrade-from-vc91 test, rollback plan, published checksum/changelog.
- **Full-suite gate**: full JVM green on a clean run, flakes root-caused.
- **Listening gate**: minimum 20-transition human pass with no gap/double-beat/
  vocal-clash/loudness jump, signed off *before* any Feature5–7 enablement.

Stop-list:

- Do not ship the benchmark/NPU harness build; flip the flag OFF and verify.
- Do not enable in vc92 ship: `MUSICAL_FX_POLICY`, `MASK_DUCKED` by default,
  production blend credit, `MixTemplateIR` production paths, any QNN/NPU claim.
- Do not report or gate on `technique:natural`; only `mix.fire` `NATURAL` counts.
- Do not ship while the device is offline or without on-device soak data.
- Do not expand scope to new stem/FX/IR/NPU features until `NATURAL` fires on real
  queues and the spot-listening passes.
- Do not normalize `handoff_pcm`/retire-guard/stale-death deaths as expected noise;
  investigate or explicitly accept in writing before ship.
- Do not treat MessageHub flakes as infra noise until root-caused or shown
  unrelated to `LoopMixSession`.


### Gated stem-duck (`LoopMix.Check.MASK_DUCKED`)

A second pass after the strict clean pass (`MAX_SUNG_SHARE = 0.03`) admits a
loop whose measured vocal share is `0.03..0.28`, peak `< 0.35`, longest run
`<= 0.6 s`, with **no lyric-certain overlap**. Candidates rank by
`(share, peak, longestRunSec, seamDelta)`. A ducked side is only crowned when
the other side's vocal is `<= 0.10` and the loop is `<= 21.5 s`; the plan then
carries `stemDuckRequired`/`duckFloor`. `maskRejects` is never widened and the
renderer fails closed if the stem path is unavailable.

### Telemetry-only blend credit

`LocalBlendAdmission.Verdict` gains `plannedVocalAttenuationDb`. It is logged as
context and never gates admission; `MAX_SOURCE_COLLISION = 0.18` is unchanged.

### Musical `FxPolicy` (implemented, default off)

`FxPolicy.selectMusical(...)` is a pure musical selector
(`buildup -> LOOP_ROLL/FLANGER`, `cleanSpace -> ECHO/REVERB`,
`vocalClash >= 0.18 -> ECHO` when rhythmic) with every `NONE` guard preserved.
`MUSICAL_FX_POLICY_ENABLED = false` keeps the shipped lottery byte-identical;
the flip waits for validated telemetry plus a listening A/B.

### `MixTemplateIR` skeleton

Pure KMP data model plus `LoopMix.Plan.toMixTemplateIR()` /
`TransitionPlan.toMixTemplateIR()` converters and round-trip tests. No
production consumer reads the IR yet.

### NPU

`CPU_XNNPACK` is wired as an opt-in `InferenceProvider` and the dormant
`NpuSelfTest` benchmark is enabled for the vc92 device build only:
3 warm-up + 20 timed iterations, non-zero deterministic input, median latency
and max-logit-delta per provider. `QNN_ENABLED` stays `false`; the shipped APK
is unchanged by the benchmark (release builds keep the probe dormant).

## Explicitly out of scope

- Classic `PitchStretch` stays bypassed at `1f`.
- QNN HTP: no QAIRT download until the QDQ export passes F1 parity; any QNN
  artifact ships separately, never in the main APK.
- `MixTemplateIR` cutover: no planner emits/consumes it yet.

## Verification gates

- Unit: `:shared:jvmTest --tests "*playback*"`, `:app:testProdDebugUnitTest`,
  `LoopMixSessionCoversHandoffPcmTest`, `Vc92ControllerFixesTest`,
  `MixTemplateIRTest`, `FxPolicyContractTest`, `LoopMixTest`.
- Native: `vocal_spectrogram_test`, `loop_mix_renderer_ab_main --selftest`,
  `--lp-bypass-probe`, `--loop-domain-probe`.
- Device (`1439ebea`, vc92): `handoff_pcm` attempts `== 0`, `0`
  `emergency-retire-guard` on native-bridge, `0` `mix.exit.violated`, `0` stale
  `mix.arm.death` in IDLE, `0` underruns, `planAgeMs > 1 h == 0`, fired
  `NATURAL` `< 15 %`, fire rate not below vc91.
- APK: `zipalign -c -P 16`, `tools/audit-apk.sh`, certificate unchanged.

## Artifacts

- APK: `apk/bitchord-2.2.3-djmix50-vc92-arm64-v8a.apk` (fill hash after build).
- Telemetry: `evidence/vc92-djmix50/device-vc92/`.
- Dataviz: `evidence/vc91-djmix49/benchmark-dataviz.html` (fire-based NATURAL).
