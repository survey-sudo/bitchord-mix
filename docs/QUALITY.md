# AE3 Quality System

How we know AE3 is good: corpus + objective gates (`tools/mixq`), listening gate (`tools/audition`),
performance macrobenchmarks, the 2-hour soak, and CI synthetic gates. Spec: Part B6.

## 1. Golden-pairs corpus (devbox only, never in git)

- Location on the devbox: `golden-pairs/` as `pairs.json` with file paths. The copyrighted audio is
  **never** committed.
- **40 pairs** across EDM, house, techno, pop, R&B, hip-hop, rock, ballad, K-pop/J-pop, Latin from the
  owner's library, including:
  - 6 genre jumps;
  - 4 tempo jumps above 8 %;
  - 4 consecutive album pairs;
  - 4 pairs with long silent intros.
- The owner can add pairs by flagging a mix (Part B7); confirmed flag-bundles are promoted into the
  corpus after review.
- CI cannot use this corpus: CI renders **10 synthetic pairs** generated in-repo (see §6), so no
  copyright leaves the machine.

## 2. Objective gates — `tools/mixq/mixq.py` (the 8 Part B6.2 gates)

`tools/mixq/mixq.py` computes the gates directly from 48 kHz WAVs (numpy + stdlib only). With
`--meta meta.json` (Nirovi/Syvenu analysis + planner metadata) the beat/vocal/placement metrics use the
real models; without it, clean DSP fallback estimators run standalone on the WAV (each estimator is
labelled in the `methods` output so a metadata-backed number is never confused with a bare-WAV estimate).

| # | Metric | Gate |
|---|---|---|
| 1 | True peak (4× oversampled, no NaN/Inf) | ≤ −1.0 dBTP |
| 2 | Beat-phase error over the overlap (Nirovi beats on both decks vs the mix) | median ≤ 10 ms, p95 ≤ 25 ms |
| 3 | Vocal-overlap seconds (Syvenu on both sources, gain-weighted) | ≤ 2.0 s (0 target at Subtle/Club) |
| 4 | Bass clash (both decks' 20–200 Hz above −6 dB rel.) | ≤ 1 beat |
| 5 | Short-term LUFS deviation from the A→B line | ±2.0 LU (Festival drop windows exempt) |
| 6 | Spectral-flux z-score at the swap | ≤ 3.0 (abrupt recipes exempt) |
| 7 | Exit/entry placement rule violations (Part A §4) | 0 |
| 8 | Silence gaps > 150 ms inside the transition | 0 |

Commands (exit 0 = all pass, 1 = a gate failed, 2 = usage/IO error):

```bash
python3 tools/mixq/mixq.py check <wav_or_dir> [--meta meta.json] [-o results.json]
python3 tools/mixq/mixq.py report results.json --out docs/quality/AE3-<build>.html
```

`mixq report` writes an HTML table per pair × recipe plus an aggregate. Each release stores it as
`docs/quality/AE3-<build>.html`.

## 3. Listening gate — `tools/audition/` (the owner's ears are final)

- `tools/audition/audition_render.py` renders a static HTML rater per release candidate: audio players
  for every pair × recipe render, 1–5 stars, keep / fix / drop buttons, notes box. Ratings save to
  `ratings.json` (one-click download from the page). See [RECIPES.md](RECIPES.md) for the render
  pipeline (`--binary` host renderer, `--upload` to `gdrive:djmix53-audition/` via rclone).
- The page plus audio goes to the owner's Drive as `AE3-audition-<build>/`.
- **Release gate:** average ≥ 3.5 stars on Festival and Club, and no "drop" votes on recipes that ship
  enabled. Recipes voted "drop" ship disabled.

## 4. Performance macrobenchmarks (Part B6.4)

androidTest macrobenchmarks run on the owner's phone over ADB; results to `docs/perf/AE3-<build>.md`:

- analysis ms per track (CPU / NPU);
- plan ms;
- stems ms per window;
- renderer callback CPU % (p95 below 20 % of the buffer period);
- prep-to-READY seconds (p50 ≤ 6 s / p95 ≤ 15 s cold; ≤ 3 s warm — Part A §3, Part C2);
- memory peak during prep.

A regression above **20 %** against the previous build fails the gate. Battery/thermal budgets
(Part B15): background analysis ≤ 3 % battery per hour of playback (`dumpsys batterystats`), automix
threads idle-wake ≤ once per 5 s when no transition is pending, prep pauses at thermal ≥ SEVERE.

**Measured numbers: `[PENDING DEVICE RUN]`** — the phone runs happen in M11 (release measures), per
milestone M10 the pipeline exists but no device numbers are recorded yet.

## 5. Soak test (Part B6.5)

A 2-hour scripted session over ADB — `tools/soak/soak.sh` (landed with M10; `--dry-run` prints every ADB
command without touching the device, and `--battery-check` adds the §4 battery/idle gates). The scripted
run on the phone itself is `[PENDING DEVICE RUN]`; the criteria below are the gates it enforces:

- autoplay a 40-track queue at Festival;
- every 3–7 minutes a random action: seek inside the outro, skip, pause/resume during RUNNING,
  queue edit, level change, toggle Bluetooth, background/foreground.

Pass criteria:

- 0 crashes or ANRs;
- 0 `mix.countdown.broken`;
- 100 % of natural transitions produce an overlap;
- memory growth below 50 MB from start to end;
- 0 stuck states (any state > 10 s beyond its expected exit — watched via `MixTelemetryV3` state fields).

Run before every release.

## 6. CI synthetic gates (Part B6.6, GitHub Actions)

`android-ci.yml`:

- `:djmix-core` tests: MixMachine table (11 × 20) + property (10k) + replay tests, planner tests,
  recipe linter tests;
- native gates: analyzer and stem-DSP tests; `--v2-probe` and **bit-identity** probes
  (`native/loopmix/loop_mix_renderer_startup_probe.cpp` path, ADR 0008);
- host render of **10 synthetic pairs** generated in-repo (`tools/audition/generate_synthetic_pairs.py`,
  no copyright), checked against golden SHA-256 and the `mixq` gates;
- APK build and audit (`tools/audit-apk.sh`); `tools/check_audiothread_alloc.py` flags allocation APIs
  inside `@AudioThread` functions.

Release builds additionally run the native probes under ASan/UBSan (`.github/workflows/release.yml`).
Real-corpus `mixq` and audition runs stay on the devbox (`make ae3-quality` — **[PENDING M10 — the
Makefile target still needs to land]**; until then run the tools directly per §2/§3).

## 7. Diagnostics that feed quality

Flag-this-mix bundles land in
`Android/data/com.music.bitchord.loopfix/files/djmix-reports/<timestamp>.zip` — `TransitionPlan` +
`TransitionScript` JSON, last 5 minutes of `MixEvent` + `MixTelemetryV3`, analysis summaries of both
tracks, and a 40 s stereo 48 kHz FLAC of the renderer's output around the swap. On the devbox the
`MixEvent` log replays through `MixMachine` and the transition re-renders bit-exactly from the bundle
(Part B7.2; the host renderer binary is
`native/loopmix/loop_mix_renderer_ab_main --render`; the zip-level replay entry point is
`tools/replay_bundle.py` — it validates the bundle's `MixEvent` window, then re-renders the flagged
transition from the owner's library audio and prints the WAV's SHA-256 for comparison with the device
render, with `--check-only` for validation alone). A flagged real pair becomes a corpus candidate (§1).
