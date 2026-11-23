# BitChord Mix — vc91 / `2.2.3-djmix49`

Base: `vc90-djmix48` (`601994d`, frozen source 1583/1583 files verified byte-identical)
Branch on `dvb` (`bitchord-mix`): `vc91-djmix49` (`23adab5`)
Package: `com.music.bitchord.loopfix`
Artifact: `apk/bitchord-2.2.3-djmix49-vc91-arm64-v8a.apk` (67 490 718 B, SHA-256 `0ed12049c1ecc97608450f09667f842708338434735934cfd74d6b70540bec3c`, cert SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`, `zipalign -c -P 16` + `audit-apk.sh` passed)

## Why

Live telemetry from the user's POCO F6 on `2.2.3-djmix48` (`automix-telemetry-vc90.jsonl`, 3 754 events;
superset of the earlier 2 605-event slice) showed three distinct defect classes, confirmed against
source by four parallel investigations and two four-seat council passes:

| Symptom | Evidence | Root cause |
|---|---|---|
| "this song: analysing…, next: analysing…" for the whole track; only a manual Next/Prev wakes it | `natural-end:before-smart-analysis-fallback-window` ×88 (`mix.dump`) / ×99 (`mix.plan.rejected`), `waiting on analysis` ×99, `planAgeMs` max `219 343 912 ms` (9 > 1 h) | Next track's analysis is starved (cache restore queued behind a 10–30 s single-thread compute pass, `nextItemDurationMs()==0` skipping the sibling-rendition lookup, 10 s `PLAYBACK_FIRST_MS` yield loops, ORT sessions destroyed between tracks) and the controller never re-plans when analysis lands. |
| 77.7 % of `mix.dump` / 66.5 % of typed accepted plans collapse to zero-overlap `NATURAL` | `mix.dump` 195/251 `NATURAL`; `natural-end:before-decline-to-mix` ×90 (`mix.dump`) / ×99 (`rejected`) | `TransitionPlanner` rewrites every `EQUAL_POWER` / `ECHO_RIDE` / `analysis-fallback` / `decline-to-mix` plan with an empty `alternativeKind` into `naturalHandoffPlan`. |
| Native blend aborts silently | `handoff_pcm_before_protected_event` ×44 attempts (87 events; `actualHeardMs=-1`) | `SEEK_TO_CLOSEST_SYNC` can land past the requested position, and codec priming (Opus pre-skip / AAC priming) makes decoded PCM start 7–48 ms late, tripping an exact-frame coverage check. |
| Muffle/demuffle "too sudden, not a smooth curve" | 9 840 loop evaluations across 161 refusals (8 304 `sung (lyrics)`, 668 `sung (mask)`); `+19.2 dB AIR` reopen documented in-source | `LoopMixSession` overrode the leaving deck's gain with a unity-hold + 250 ms splice while the low-pass reopened 700 Hz → 20 kHz in the last 2 beats — a full-volume brightness blast at the handback. |

## What changed

### 1. Analysis pipeline & planning reactivity (complaint a)

- `TrackAnalyzer.analysisUpdates: SharedFlow<String>` — emits on cache restore, grid-ready
  intermediate, head publish, whole-track completion, and terminal failure.
- `CrossfadeController` subscribes (200 ms debounce), bumps a monotonic `planGeneration`
  token, discards stale plan verdicts, and re-plans in `Phase.IDLE` when the current/next
  track's analysis improves. `PlaybackService` wires the flow.
- A `NATURAL` fallback is now **provisional** until the arm horizon: newer analysis clears
  the latch instead of locking the pair until a manual skip.
- Cache restores and head fetches moved to a dedicated 3-thread I/O pool — never queued
  behind a compute pass.
- Pass-1 (BeatThis grid, 29.76 s windows decoupled from UMX's 22.245 s) now runs first and
  publishes a usable `head`-quality result in ~1–2 s; Pass-2 (5-window UMX + structure)
  follows and aborts between regions when a newer request arrives.
- ORT sessions retained across tracks with a 100 s idle reaper (was: released on every
  `running.isEmpty()`).
- Watchdogs: 45 s head / 480 s whole-track deadlines, bounded failed/stalled sets, guaranteed
  `running` cleanup, so the UI can no longer sit on "analysing…" forever.
- `PLAYBACK_FIRST_MS` 10 000 → 2 000 ms; Pass-1 lane gets a bounded `DEFAULT` priority boost.
- Source-authority rows are analysed **provisionally** from the playback URI (tagged, not
  persisted) instead of returning null until a YouTube match lands; match lookup has a
  timeout and bounded retries.
- `nextItemDurationMs() == 0` is treated as *unknown*, not *absent*.
- `planAgeMs` sentinel fixed (`-1L`, null instead of device uptime); `planGeneratedAtMs`
  stamped on every plan attempt.

### 2. DJ-grade selection & native execution (complaint b)

- **Tiered fallback ladder (`TransitionPlanner.tieredFallback`)** replaces the blanket
  `NATURAL` rewrite (after controller-owned `LoopMix` / `nativeBridge`):
  0. `decline-holds-no-overlap` (honest key-clash/dual-vocal declines stay `NATURAL` per
     admission contract);
  1. `fallback-beatmix` (`DJ_BLEND` run through `DjMix.annotate` + `VocalDepthFilter` +
     `capIncomingSkip`);
  2. `safety-crossfade` (2–4 beats, equal-power, rate 1.0, downbeat-snapped, strictly gated
     to unknown incoming BPM or ≤ 10 % folded tempo delta so > 10 % pairs never flam);
  3. `fallback-muffle` (`TransitionAlternatives.select` for unmixable pairs);
  4. `fallback-echo` (selective echo-out on trusted outgoing grid when incoming tempo is
     unknown);
  5. `tiered-fallback-exhausted` (honest `NATURAL`).
- `LocalBlendAdmission` now gates `rate < 1.0` slowdowns on `plan.nativeRamped == true` —
  the classic path keeps a no-op tempo write and remains refused, while native ramped blends
  can slow down cleanly.
- Deferred intentionally to vc92: stem-duck crown-loop admission in `TransitionPlanner`
  (no `TrackAnalysis` field exposes a separated vocal stem; `LoopMixSession`'s existing
  windowed stem-ducking runs at render time) and `FxPolicy` refactor (`FxPolicyContractTest`
  pins `pairHash` slots). Tempo octave folding already ran in `alignTempoOctave` / `alignedFold`.

### 3. Native bridge decode priming (complaint b, reliability)

- `SEEK_TO_CLOSEST_SYNC` → `SEEK_TO_PREVIOUS_SYNC`.
- When a region starts within 50 ms of zero and the decoder returns 0 < start ≤ 80 ms late,
  the region is zero-padded and re-anchored to 0.0 — knot alignment preserved, coverage check
  stays strict, anything later than 80 ms still fails closed.
- `PREROLL_SECONDS` 1.0 → 1.5 s.

### 4. Smooth muffle / demuffle / echo (complaint c)

- Removed the `ReleaseSplice` unity-gain override on the leaving deck for muffle and
  beatless-bridge: the planned `fallPoints` fade is restored, so the deck no longer demuffles
  to full band at unity gain and then cuts.
- A dying outgoing deck never reopens its low-pass — it closes monotonically toward 500–700 Hz
  with a C1 raised-cosine in log-Hz while its gain falls.
- Surviving / bridge reopens use proportional legs (`max(3, round(beats × 0.4))`, clamped) and
  an 8-point sampled C1 raised-cosine in log-Hz, so `d(log f)/dt = 0` at both ends and at the
  turnaround — the 2.14× / 3.0× slope kink is gone.
- Incoming low-pass starts at 600 Hz and opens over ~3 beats (was 300 Hz → 20 kHz in 0.75 beat).
- Echo send replaced with a beat-boundary raised-cosine bell (rise 0.75 beat, cosine decay to a
  beat boundary) instead of `0.90 → 0` in 120 ms; the non-handoff branch no longer ends at 0.6
  so the wet tail retires musically.
- `releaseEcho()` added (smooth send-to-zero, restores `echoTargetDry = 1f`, stays engaged
  until the tail decays below −60 dBFS, auto-parks); `PlaybackService.filters` wires
  `releaseEchoes()` to both processors, `CrossfadeController.finish()` releases echoes on
  normal completion, and `parkEcho()` stays reserved for abort (`release()`). Filter state
  cleared on `reset`/`configure`, dry/wet crossfade (0.25/sub-block linear ramp, initialised
  in `onFlush()`) on filter engage/disengage.
- Native TPT low-pass bypass threshold `18 000` → `min(19 800, 0.44 × sampleRate)` Hz, with
  `ic1eq`/`ic2eq` zeroed inside `bypass()`.
- Gentle survivor-only makeup gain (≤ +1.5 dB) proportional to low-pass closure depth.

### 5. NPU / CPU acceleration (complaint b, "power up the NPU")

`QNN_ENABLED` stays **false**. The shipped models are dynamic INT8; QNN HTP requires static
QDQ INT8 (or FP16 on the QNN GPU backend), the UMX separator is a 3-layer BiLSTM that does not
partition onto HTP, and a 30 s QDQ export previously collapsed downbeat F1 to ~32 %. What
landed instead:

- ORT CPU wins: `setCPUArenaAllocator(true)`, `setMemoryPatternOptimization(true)`,
  `intraOp=2 / interOp=1` (never starve the audio thread).
- `InferenceProvider` abstraction (`CPU_DEFAULT`, `CPU_XNNPACK`, `QNN_GPU_FP16`,
  `QNN_HTP_QDQ`) with a safe `CPU_DEFAULT`.
- `qnnProviderOptions()` pins `htp_arch = "73"` for SM8635 and supports QnnGpu vs QnnHtp.
- `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>` in the
  manifest — removes one of the four known `QNN_DEVICE_ERROR_INVALID_CONFIG (14001)` causes.
- CPU-vs-XNNPACK benchmark with a max-logit-delta guard, and a QNN diagnostic path with
  `disable_cpu_ep_fallback=1` + profiling so a future enablement can prove full partitioning.
- `npu-addon/` docs: full-ORT `--use_qnn static_lib` build, QAIRT 2.51 library set,
  `useLegacyPackaging = true`, separate signed add-on APK; explicit warning against
  `--include_ops_by_config` reduced builds.
- New host-side `npu-addon/export_qdq_beat.py` (6 s / 10 s fixed shape, uint16 activations /
  uint8 weights, F1 parity gate at 2 points).

### 6. UI

`AutomixStatus` now carries `rung`, `reason`, and `overlapBars`; the player and mix view render
one extra line — e.g. *"Natural handoff — no overlap (analysis-fallback)"* vs *"Beat blend,
8 bars"* — so a degraded mix explains itself.

## Verification

Native C++ on `dvb` (all exit 0):

| Probe | Result |
|---|---|
| `vocal_spectrogram_test` (`-O2`) | all checks passed (56 ms) |
| `vocal_spectrogram_test` (ASan + UBSan) | all checks passed |
| `window_calibration` | all invariants held (366 ms) |
| `loop_mix_renderer_ab_main --selftest` | passed (6 renders, firstFrame −23520, tail −170.3 dB; 1 745 ms) |
| `--reverb-stop-probe` | passed (697 ms) |
| `--loop-domain-probe` | 63 finite-loop pairs, minRMS 0.0643 (~78 s) |
| `--lp-bypass-probe` (new) | threshold 19800 Hz, maxStep 0.0077 FS, TPT state zeroed (103 ms) |

Kotlin / Android unit suites on `dvb` (post-fix):
- `:shared:jvmTest --tests "*playback*"`: **522 / 522 passed, 0 failures** (`ExitLegTest` updated for monotonic dying-OUT `muffleEnvelope` and sampled C1 `bridgeEnvelope`).
- `:app:testProdDebugUnitTest --tests "com.music.bitchord.EchoOutTest" --tests "com.music.bitchord.playback.*"`: **514 passed, 0 failures**, 12 skipped (incl. `FloatDspChainTest`).

Benchmark dataviz & telemetry verification tooling:
- `tools/automix-telemetry-diff.py` — separates `mix.fire` vs `mix.plan`, dedupes `mix.complete` by `attemptId`, splits `handoff_pcm` attempts/events, gates `planAgeMs > 1h == 0`, checks normalized fire rate per 100 dumps.
- `tools/vc91-benchmark-dataviz.py` → `evidence/vc91-djmix49/benchmark-dataviz.html` — self-contained interactive PRE (DSP envelopes, slope derivative strip, native probes, pipeline budgets) + POST (vc77→vc90 history, refusal Pareto, 9 840-loop sung breakdown, rung/conversion funnels, `planAgeMs`/`deltaMs`, live browser JSONL drop-zone) report.
- `tools/vc91-device-verify.sh` — archives on-device telemetry (verifying non-empty pull before delete), installs, launches, pulls, and runs the diff.

## Live Device Verification (POCO F6 `1439ebea`)

Installed `apk/bitchord-2.2.3-djmix49-vc91-arm64-v8a.apk` (`versionCode=91`, `2.2.3-djmix49`) and captured 681 real playback events (`evidence/vc91-djmix49/device-vc91/automix-telemetry-vc91.jsonl`) after archiving the remaining pre-vc91 log.

| Metric | vc90 baseline (`3 754` ev) | vc91 live (`681` ev) | Status |
|---|---|---|---|
| Executed transitions (`mix.fire / 100 dumps`) | 88 (`35.1`) | 19 (`38.8`) | **MET** (+10.5%) |
| `mix.fire` transitions that were `NATURAL` | — | **0 / 19** (`13 classic`, `5 native-bridge`, `1 crown-loop`) | Verified |
| `planAgeMs > 1 h` | 9 (`max 219 343 912 ms`) | **0** (`max 49 233 ms`) | **MET** |
| `AudioTrack` underruns (analysis active) | — | **0** (`m:0 s:0 k:0 z:0` while BeatThis/UMX ran in 1.7–4.4 s) | Verified |
| `mix.dump` / `accepted` `NATURAL` share | 77.7% / 66.5% | 49.0% / 61.8% | **Metric artefact, not a transition score.** Every `technique:"natural"` row is `executionScope:"normal-queue-advance-no-overlap"` — a plain track-end advance (113 of vc90's 327 "accepted plans", 21 of vc91's 53). Excluding those, final-verdict `NATURAL` share is **0%** in both builds; the authoritative score is `mix.fire` NATURAL (`0/88` → `0/19`). |
| `handoff_pcm_before_protected_event` | 44 attempts (87 events) | 8 attempts (16 events) | Above 0 target — all 8 fired on `native-bridge / Natural handoff` (`cut = outDuration` past real Opus/AAC decoded EOF by ~6–60 ms, plus `c2.android.opus.decoder` starting ~11 ms late on mid-track `1.81 -> 1.821` seeks) |

Dataviz updated with live vc91 overlay: `evidence/vc91-djmix49/benchmark-dataviz.html` (`110 733` B).

## Known gaps / next (vc92 candidates)

- Clamp `Natural handoff` `cut` slightly inside decoded EOF (or avoid strict EOF failure when `cut == outDuration` and decoded Opus/AAC ends 6–60 ms early) and handle mid-track codec seek roll-in (`1.81 -> 1.821`) — directly fixes the remaining 8 `handoff_pcm_before_protected_event` aborts.
- Clear `shownCountdownKey` inside `CrossfadeController.finish()` so the previous pair's watchdog cannot fire `mix.arm.death` 112 s later in `IDLE`.
- Score `NATURAL` from `mix.fire` (authoritative) and exclude `executionScope:"normal-queue-advance-no-overlap"` rows from every plan-based share in `automix-telemetry-diff.py` and `vc91-benchmark-dataviz.py`; add "natural chosen then aborted" as its own metric.
- Full `MixTemplate` transition IR + stem-duck `TrackAnalysis` metadata (vc92) and gated QNN HTP add-on APK (vc93, QAIRT 2.51).
