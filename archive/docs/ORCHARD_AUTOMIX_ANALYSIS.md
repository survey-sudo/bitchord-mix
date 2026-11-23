# Orchard AutoMix — Deep Technical Inspection

**Repo:** `https://github.com/sfg5453/orchard` cloned to `/tmp/orchard-analysis`  
**Commit inspected:** `0662cb565e7cae9df77a46f9a60325ee5bb70960` (“Collapse music video duplicates in mixes and autoplay”)  
**Date:** 2026-11-14  
**Size:** 622 MB, 2,132 tracked files (`du -sh /tmp/orchard-analysis`) — **active repo, not empty/stub/archived.**  
**Method:** source read of all crates in the Rust workspace, `core/js/src/audio/crossfade/` planner, `models/{nirovi,syvenu}`, `mobile/android/npu-addon/`, and `mobile/android/app/src/main/java/.../npu/`. No UIs exercised. Every factual claim cites `file:line`.

> **License blocker — read first.** The AutoMix engine is **not AGPL**. It is **PolyForm Shield 1.0.0** (source-limited, *non-compete*) — see §7. Do **not** copy code from `crates/earmark`, `crates/orchard-adaptive-mix`, `crates/orchard-transition-*`, or `core/js/src/audio/crossfade/` into BitChord (GPL-3.0+). This report describes *functional ideas* for clean-room reimplementation; it is not a license to reuse expression. Obtain counsel before any direct reuse.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Transition Engine](#2-transition-engine)  
   2.1 [Beat / Bar / Downbeat Alignment](#21-beat--bar--downbeat-alignment)  
   2.2 [Tempo Matching / Time-Stretching](#22-tempo-matching--time-stretching)  
   2.3 [Crossfade Curves](#23-crossfade-curves)  
   2.4 [Filter Sweeps](#24-filter-sweeps)  
   2.5 [Echo / Delay / Reverb](#25-echo--delay--reverb)  
   2.6 [Vocal Detection & Carve / Mid-Cut](#26-vocal-detection--carve--mid-cut-envelopes)  
   2.7 [Phrase-Aware Planning](#27-phrase-aware-transition-planning)
3. [Clashing Song Handling — The Critical Question](#3-clashing-song-handling--the-critical-question)
4. [NPU / Hardware Acceleration](#4-npu--hardware-acceleration)
5. [DSP / Audio Processing](#5-dsp--audio-processing)
6. [Comparison Matrix vs BitChord vc68](#6-comparison-matrix-vs-bitchord-vc68)
7. [Portability Assessment](#7-portability-assessment)
8. [Prioritized Recommendations for BitChord vc69](#8-prioritized-recommendations-for-bitchord-vc69)
9. [Appendix A — Inventory](#appendix-a--inventory)
10. [Appendix B — Threshold Quick-Reference](#appendix-b--threshold-quick-reference)

---

## 1. Architecture Overview

| Layer | Detail | Evidence |
|---|---|---|
| **Language stack** | Edition 2024 Rust (workspace) + C++20 + Qt 6.8 QML + C (QuickJS bridge) + JavaScript planner + Kotlin + C++ JNI. Rust for audio core (`crates/earmark`), worker (`crates/orchard-adaptive-mix`), transition glue (`orchard-transition-{core,planner,mobile}`), Android Kotlin, desktop Qt/C++. | `Cargo.toml:1-3` workspace resolver; `Cargo.toml:7-15` 8 members; `README.md:98-101` Rust+Qt; `crates/earmark/Cargo.toml:1-3` edition 2024; `crates/orchard-transition-planner/src/lib.rs:1-27` includes JS via `include_str!`; `mobile/README.md:2` Android app |
| **Framework / Build** | Meson 1.12+ + CMake 3.24+ + Ninja + Cargo; Qt Quick/QML/Network/Multimedia; Node/npm for `providers/youtube`; Android AGP + NDK 28.2.13676358. Desktop APK `.exe`/`.7z`/AppImage/launcher; Android `.apk` Android 12+. | `README.md:95-102` build reqs; `meson.build:1` 38k lines; `mobile/android/npu-addon/README.md:7` NDK; `Cargo.toml:19-23` release profile thin-lto |
| **License (project)** | **AGPL-3.0-or-later** for whole Orchard (`LICENSE`, `LICENSE-EXCEPTION.md`, `Cargo.toml` workspace.package license). Desktop/mobile providers, UI, extras. | `Cargo.toml:10` `license="AGPL-3.0-or-later"`; `README.md` license badge AGPL; `LICENSE:1` head AGPL; `LICENSE-EXCEPTION.md:1-8` |
| **License (engine exception — the trap)** | **Adaptive mix engine under PolyForm Shield 1.0.0** with §7 additional permission. You may convey Orchard combined under AGPL for AGPL parts + Shield for engine; Corresponding Source need not include engine under AGPL. *Only engine files carrying PolyForm header.* | `LICENSE-EXCEPTION.md:3-12` chain; `LICENSE-POLYFORM-SHIELD:1-6` header; `crates/earmark/Cargo.toml:5-6` license-file = PolyForm Shield so engine crate is Shield. |
| **Git layout relevant to mixing** | `crates/earmark/` (pure smart-crossfade engine), `crates/orchard-adaptive-mix/` (local adaptive-mix *preparation* worker, never on audio callback; shared desktop+Android+web), `crates/orchard-transition-planner/` (QuickJS host running unchanged `core/js` planner), `crates/orchard-transition-core/` (render shape `SelectedPlan` + `apply_selected_shape`), `crates/orchard-transition-mobile/` (`liborchard_earmark` cdylib JNI), `core/js/shared/audioAnalysis.js` + `core/js/src/audio/crossfade/` (19 files JS planner policy), `models/{nirovi,syvenu}` (ONNX), `mobile/android/npu-addon/` (QNN add-on packaging), `mobile/android/app/src/main/java/.../playback/smart/` (`AdaptiveMix.kt`, `MixNative.kt`, `npu/`). | `Cargo.toml:7-15` workspaces; `crates/orchard-adaptive-mix/src/lib.rs:1-5` "Local adaptive mix preparation. Never runs on audio callback."; `crates/earmark/src/lib.rs:31-50` three stages Analysis→Planning→Rendering no feedback; `core/js/src/audio/crossfade/wSOLA…` listing |
| **Data flow (desktop)** | Worker decodes *whole songs* at 44.1 kHz for analysis, then trailing/leading **60-s render windows** at 48 kHz via loopback authenticated FFmpeg decode (timeline shared sample-exact). Offline: Earmark analysis + JS planner → `engine.plan_selected()` → `engine.render()` → interleaved f32 → host splices to native incoming at `incoming_resume`. Host plays 48 kHz overlap, starts incoming player early at `incoming_cue = incoming_resume − duration`. Host matches joins on audio because decoder timestamps off by ms (Qt stamps Opus 7 ms early), fades last 5 ms into native. | `crates/orchard-adaptive-mix/README.md:15-22` para 2; `prepare.rs:168-176` decode tail/head windows; `prepare.rs:307-336` cue calc; `prepare.rs:136-160` mono reference clips; "calls in prepare" |
| **Models & inference** | Beat: **Nirovi** (dilated TCN w384, 7×128 rhythm layers) distilled from Beat This `final0`, trained QAT8; Desktop runs Nirovi fp16 on ORT WebGPU (Dawn) or int8 on 2 CPU threads without GPU; vocal: **Syvenu** Conv stem (16/32/64) + 14 dilated residual + 1×1 head, sigmoid 569k params, predicts UMX-HQ vocal share from same 128-bin spectrogram. Desktop Syvenu on ORT CPU 2 non-spinning threads. Mobile ships dynamic INT8 Beat This `final0` (21 MB) + ORT; NPU add-on path §4. | `models/nirovi/README.md:8-22`; `models/syvenu/README.md:8-12`; `crates/orchard-adaptive-mix/src/models.rs:13-18` constants; `crates/orchard-adaptive-mix/src/models.rs:29-86` WebGPU→CPU fallback |
| **Frontend shared by all** | Earmark 20 Hz? No — 22 050 Hz mono, FFT 1024, hop 441 (~50 fps), Slaney mel 30–11 kHz, `log1p(1000·mag)`, 128 bands. Stereo folded `(L+R)/√2`. Nirovi input `[1,1500,128]`. | `models/nirovi/README.md:57-60`; `models/syvenu/README.md:46-48`; `crates/earmark/src/analysis/model_frontends.rs:1-30` constants `BEAT_SPECTROGRAM_*`; `crates/earmark/src/analysis/track.rs:184-202` WholeTrackAnalyzer |

**Where “transition” lives:** `crates/earmark/src/{planner,render,dsp,config,engine}.rs` owns *candidate generation, scoring, strategy → rendering* purely. `core/js/src/audio/crossfade/` owns *product policy* (thresholds, veto/demote gates, `planPairTransition`, `needsFilter`, strategies) executed in QuickJS `crates/orchard-transition-planner/src/{lib.rs,bridge.c}` via `orchard_planner_invoke` / session. `orchard-adaptive-mix/src/{prepare,planner,evidence,analysis,beats}.rs` bridges evidence to planner and enforces exact render. Production path is **JS planner → Rust SelectedPlan → Earmark plan_selected → apply_selected_shape → render**.

---

## 2. Transition Engine

### 2.1 Beat / Bar / Downbeat Alignment

**Three-step architecture.** `SmartCrossfadeEngine` holds reused FFT plan, stretch processor, envelope scratch. Analysis/planning run *ahead of playback*; rendering “written to avoid allocation in its inner loops” and engine is Send-not-Sync (one per worker). `lib.rs:33-48`, `engine.rs:22-31`, `engine.rs:133-147`, `lib.rs:121-128`.

**Beat metadata.** `BeatAnalysis { bpm, beats: Vec<f64> sec, downbeats: Vec<f64> }` supplied externally; Earmark *never* detects beats itself. Validate monotonic. Downbeats drive placement; without them planner scores blind on structure (`lib.rs:51-60`, `types/beat.rs:11-16`, `types/beat.rs:30-39`).

**Anchors from downbeats only (Earmark path).**
- `pick()` in `planner/candidate.rs:254-265`: `downbeats_in(earliest,latest)` if any → else `beats_in` → else `vec![earliest]`.
- Candidate anchors are *downbeat instants*, not beats. Search region: outgoing = last `timing.outgoing_search_window=45s` before end (`config.rs:77`), incoming = `incoming_search_window=45s` from `incoming_head_guard=0s` (`config.rs:78-80`), intersect `feasible` with tail guard `0.25s` outgoing (`config.rs:62`). Outgoing keeps latest `max_anchors` (16), incoming earliest (16) because outros/intros dominate; budget `anchor_budget` bounds `max_anchors²·|beat_lengths| ≤1024` (`candidate.rs:188-194`).
- Hard feasibility `fits()`:`duration∈[1.5,16.0]s`, `outgoing_end+tail_guard≤duration`, `incoming_end≤duration`, constraints `admits` (`candidate.rs:157-186`).

**JS product planner (what actually ships) anchors differ more richly.** `core/js/src/audio/crossfade/pairTransitionEvidence.js:203-244` `generateTransitionCandidates()` builds from `analysis.boundaries` (WholeTrackAnalyzer `structural_boundary_candidates` : e.g. `detected-change`, novelty peaks), plus `beatEntry()` (first beat inside window via `beatPresence≥0.5`, snapped to nearest downbeat within `interval*4`), `bassArrival()` (drop where floor rise `≥0.4` over bar + sharpest half-bar step), and a downbeat-evidence candidate at capped confidence `0.6`. Dedup tolerance `max(0.05, beatInterval/2)`, keep `MAX_ROLE_CANDIDATES=12` per role by `priorityScore = src·0.2+conf·0.55+stab·0.15+clean·0.1-tailCost·0.15`. Outgoing tail audible excess `≤MAX_DISCARDED_AUDIBLE_SECONDS=12s` required (charged audible = audible+unknown; cautious: unknown counts as audible) (`pairTransitionEvidence.js:78-139, 239-244`). Finalist pairs `MAX_DETAILED_CANDIDATES=64` sliced from cheapScore.

**Bar inference fallback.** `BeatAnalysis::beats_per_bar()` infers from median downbeat spacing 1–16 else 4 (`types/beat.rs:96-113`), waltz 3 supported. `PhraseGrid::beats_per_phrase = beats_per_bar × bars_per_phrase(4)` default 16 beats (`config.rs:113`, `analysis/phrase.rs:31-33`). Meter confidence gate `meterConfidence<0.35` demotes in JS gates (`pairTransitionGates.js:221-230`).

**Scoring of closeness.** `beat_alignment()` measures euclidean distance to nearest beat / tolerance `beat_interval×0.5`, 1 at 0, 0 at tolerance (`scoring.rs:94-99, 190-200`). `phaseEvidence()` in `pairTransitionGates.js:103-128` computes `max(distance to nearest downbeat)` to *outgoingEnd* and *incomingEnd* (with half-bar aliasing for `beat-entry`), tolerance `min(outgoing,incoming)·0.5 clamped ≥0.08s`; veto `phase-error` if `beatError > max(0.2, interval·0.45)` (`pairTransitionGates.js:244-246`).

**Kick lock (audio-level correction).** `crates/orchard-adaptive-mix/src/lock.rs:3-6` folds 35–140 Hz onsets every 1 ms (`HOP=0.001`) into 256 phase bins per beat, correlates over `CONTEXT_BEATS=8` each side with shared phase; nudges `incoming_start` ≤⅛ beat (`LIMIT=BINS/8`) iff patterns `score(best)≥0.5` and `score(best)−score(0)≥0.2` and interior `abs(best)<LIMIT`. Prevents flam where trackers are 30 ms off. Reported `kickLock ms` in `Prepare::kick_lock` (`prepare.rs:348`).

### 2.2 Tempo Matching / Time-Stretching

| Knob | Earmark (Rust) default | JS shared planner |
|---|---|---|
| **Target** | Incoming (default). Incoming plays native; consumer resumes instant without ongoing stretch `config.rs:9-20`; `lib.rs:76-82` | Incoming (default via `tempoFit()` : `targetBpm=incoming` if not ramp) `pairTempoHarmony.js:99-138` |
| **Deviation bands** | preferred `0.04`, acceptable `0.08`, max `0.12` `config.rs:26-31`; scoring grades: 1.0 within pref, 0.6 at accept+ 0.2 at max, 0 beyond `scoring.rs:122-139`; `tempoFit()` demotes unmatched (ratios 1.0) rather than dropping candidate `candidate.rs:46-95` | `MAX_STRETCH_DEVIATION=0.04` constant only (`pairTempoHarmony.js:4`); `tempoFit()` if `deviation>0.04` returns `beatmatched:false` ratios 1.0 `pairTempoHarmony.js:115-128`. `MAX_RAMP_DEVIATION=0.08` for glide (`pairTempoHarmony.js:8`) — measured comment: “6% glide clean, 11.8% forced” |
| **Octave folding** | `allow_half_double=true`; `fold_to_octave(bpm, ref)` picks `bpm,{½,2}` minimal log-distance to outgoing (`candidate.rs:267-281`); 87↔174 folds ratio 1.0 (`lib.rs:81-82`) | `alignTempoOctave()` while-loop halving/doubling until in `(0.67·,1.5·)` (`pairTempoHarmony.js:43-48`) |
| **Midpoint/ramp** | `TempoTarget::Midpoint` = `sqrt(out·in)` (`candidate.rs:74`) | `rampFit()`: glide geometric `glide=in/out`, deviation `max(|g−1|,|1/g−1|)`, if >0.08 unmatched with `tempoRamp=true` else `logMean=(in−out)/ln(in/out)`, ratios `logMean/out`, `logMean/in` (`pairTempoHarmony.js:57-85`) |
| **Stretch algorithm** | **Signalsmith Stretch**, never resampling; pitch independent unless `preserve_pitch=false` (`lib.rs:72-74`, `audio/stretch.rs:2-5`, `config.rs:33`). `TimeStretcher::process()` interleaves planar→interleaved, `stretch.set_transpose_factor_semitones()`, `exact()` (`stretch.rs:58-99`); `process_mapped()` feeds blocked `BLOCK=1024` with latency compensation `input_latency+output_latency`, `seek()` for glide (`stretch.rs:107-182`). `semitones_for_ratio` for turntable mode (`stretch.rs:199-206`) | JS only selects ratios; Rust renders. `TempoGlide::pair(avgOut,avgIn)` outgoing starts 1.0 ends span, incoming starts 1/span ends 1.0 (`types/transition.rs:113-122`); geometry ensures N beats consume N source beats at log-mean `types/transition.rs:134-151`, `render/stage.rs:58-69` |
| **Selected-plan guard** | `MAX_SELECTED_TEMPO_RATIO_DEVIATION=0.04`, `MAX_RAMPED=0.12` transcending (`types/transition.rs:99-103`); `plan_selected()` validates: finiteness, >0 BPM/ratio, `duration=beats·60/target_bpm` within 1/sample tolerance, `outgoing/incoming_end ≤ track+1 sample`, glide avg matches `2e-5`, pitch 0 for glide (`engine.rs:280-397`) | `plan()` refuses if rendered would need ongoing incoming stretch without glide (`planner.rs:139-141`), if `duration>30s` or outside decoded windows (`planner.rs:133-136`) |
| **Resampling vs stretching** | Resample only to reconcile *sample-rate mismatch* (incoming rate wins), never for tempo (`lib.rs:70-74`, `engine.rs:253-266`, `audio/resample.rs:2-5`) via **rubato** FFT `Fft` streaming path with delay trim (`resample.rs:15-36, 50-136`) | Not in JS |

**Native-rate handoff.** After stretch, renderer crossfades onto unstretched source at glide ends where rate ~native: `SPLICE_FRAMES=1024`, search `±256` (`render/stage.rs:17-24`); `aligned_end()` finds phase-coherent `end` by maximizing dot product minus noise (`stage.rs:166-204`); `NATIVE_EDGE_SECONDS=0.25s` held untouched each end (`stage.rs:25-28, 129-142`). `outgoing_resume,incoming_resume` account consumed source (`renderer.rs:139-147`).

### 2.3 Crossfade Curves

All overlaps are **equal-power** by default, never linear except ShortFade? Earmark `FadeCurve` enum (`dsp/fade.rs:7-14`):
- `Linear`: `x` / `1−x` (kept for completeness/tests).
- `EqualPower`: `sin(x·π/2)` / `cos(x·π/2)`, power sum =1 (`fade.rs:17-33`).
- `SmoothStep`: `x²·(3−2x)` (S-curve), used for filtered blend + loudness rides (`planner/strategy.rs:92-96, 101-110`).

Automation shapes `CurveShape` (`dsp/automation.rs:11-27`): `Linear`, `SmoothStep`, `EqualPowerIn` `sin`, `EqualPowerOut` `1−cos`, `Logarithmic` (geometric in value), `Logistic` (normalized with `LOGISTIC_STEEPNESS=6`, `automation.rs:29-41`, geometric *and* logistic so filter leaves/arrives without lurch vs raw logistic 0.047 offset).

Fade spans `outgoing_span/incoming_span` as `(start,end)` fractions (default `FULL_SPAN=(0,1)`) allow one-sided delayed fades for technique (`types/transition.rs:56-58`, `dsp/fade.rs:44-53`: hold/release outside span). `orchard-transition-core/src/lib.rs:87-94` maps planner `outgoingFade/incomingFade` to spans; muffle/drop-in use them (`lib.rs:99-117`, `core/js/.../transitionArrangement.js:24-28`).

Loudness gain rides are *linear* AutomationCurve ramps `SmoothStep` (not dB) spanning full overlap (`types/transition.rs:48-49`, `planner/strategy.rs:98-115`).

### 2.4 Filter Sweeps

**Earmark strategy → filter mapping** (`config.rs:149-175`, `planner/strategy.rs:117-170`):
- Config: `bass_swap_hz=180`, `lowpass_min=400`, `lowpass_max=18000`, `highpass_min=20`, `Q=1/√2`, `update_interval=32` samples recalculated (per-sample inaudible but measurably slower). HPF acts as bass swap pivot.
- **BassSwap:** outgoing HPF `20→180 Hz` at `SWAP_START=0.45–0.55`, incoming HPF `180→20` same span (`strategy.rs:18-19`, `120-141`). Via `hold_then_move()` geometric `Logarithmic`.
- **FilteredBlend:** outgoing LPF `18000→400` from `BLEND_FILTER_HOLD=0.3 →1.0` with `Logistic`, incoming HPF `180→20` from `0.0→BLEND_OPEN_BY=0.6` (`strategy.rs:20-23, 142-167`). Outgoing fade softened to `SmoothStep` to avoid double attenuation (`strategy.rs:91-96`).
- **Biquad:** `FilterAutomation { kind, cutoff: AutomationCurve, q, gain_db, depth?: AutomationCurve }` (`dsp/filters.rs:29-48`). Sweep coefficients precomputed per block (`update_interval`), state *per channel* independent, coefficients shared stereo (`filters.rs:82-154`). Cutoff clamped `10 Hz .. 0.45·Nyquist` (`filters.rs:14-17`). Interpolates geometrically so sweep travels in octaves (`filters.rs:156-163`).

**Muffle (JS→core):** `crates/orchard-transition-core/src/lib.rs:20-28,99-117`: `MUFFLE_OPEN_HZ=18000`, `DIM=6000`, `CLOSED=300`, `SWAP_HZ=180`, `Q=[0.5412,1.3066]` (two stacked = 4th-order Butterworth). Outgoing gets HPF cut + two LPFs closing `open→dim→closed` logarithmic across `lead→swap`, incoming HPF opens at swap. Outgoing fades `swap→exit`, incoming `lead→swap`. `apply_selected_shape()` also pushes HPF bass cut on `FilteredBlend`+`bass_swap_fraction` (outgoing 20→200 at swap, incoming 200→20) (`lib.rs:29-76`) and can drop LPF if `filter_sweep==0` (`lib.rs:78-82`).

**Release to native:** `stage.rs:256-295` `release_filters()` crossfades filtered stem to dry copy across span where filters rest open at join (`holds_until()/settles_at()` on AutomationCurve `automation.rs:134-156`). Rationale: open 20 Hz HPF still shifts 50 Hz by ~34°; dry join avoids step (`stage.rs:260-261`). If filter still closed at join, effect kept.

**Bass handoff with own fader:** `render/bass.rs:16-32` `warp()` remaps transition `x` via piecewise linear so `swap→0.5, 0→0,1→1` clamped `0.05–0.95`; `hold_low_band()` double-pass 4th-order zero-phase low-pass at 180 Hz (`bass.rs:63-82`) adds correction `warped_fade×gain − envelope`. `FadePlan.bass_crossover` auto-set only off-middle full-span BassSwap (`lib.rs:38-40`).

### 2.5 Echo / Delay / Reverb

**None.** Exhaustive `grep -rn echo|delay|reverb|plate|flanger` across `crates/**/*.rs` and `core/js/src/audio/crossfade/*.js` returns only Hits: `delay` trimmed in rubato resampler comment (`crates/earmark/src/audio/resample.rs:25-26`), “delay” in `stretch.rs`, and “delayed drop / leaving” phrase in `transitionFlow.js:149,192`. No send nodes, no feedback constants, no plate/comb, no tempo-synced delay, no echo tail, no DJ performance FX (flanger, roll, filter-echo, dub reverb). Orchard is a *clean blender*, not a DJ performance rig.

### 2.6 Vocal Detection & Carve / Mid-Cut Envelopes

**Vocal presence model: Syvenu (UMX-HQ distilled).** `models/syvenu/README.md:8-12`: Conv2d stem, 1×1 to 96 ch, 14 dilated residual Conv1d dilations 1–64×2, sigmoid head, 569 761 params FP32, non-causal ±5 s context. Trained BCE vs UMX-HQ vocal share (clamped vocals/mix averaged 200–4000 Hz) on 89 k tracks (MTG-Jamendo/Jamendo/FMA). Test F1 0.877 vs UMX 0.860 (jam-alt), MAE 0.068–0.079, threshold 0.6 for “active”.

Input `[1,T,128]` *same* beat spectrogram as Nirovi (22 050 Hz, FFT1024, hop441 reflect-centered (`model_frontends.rs`)). Output per frame `[1,T]` presence 0–1. `crates/orchard-adaptive-mix/src/models.rs:176-211` `Models::vocals()`: downmix `(L+R)/√2`, resample to `song::RATE` (44.1 k → 22.05), slide `FRAMES=1500` with `VOCAL_BORDER=250` skip (reverse stitching `models.rs:55-74` NPU or `models.rs:176-211` CPU ORT).

**Evidence curves handed to JS planner.** `evidence.rs:10-130`:
- `bass_curve()`: 4th-order band 35–140 Hz, 0.25 s (`STEP=0.25`) RMS levels normalized to per-song median (75th percentile per-sec), beatless → ≈0.
- `sub_bass_ratio()`: `log10(sub 25–60 / low 60–250)` over middle 60% body; 808 high above zero, boom-bap low.
- `chroma_curve()`: per 0.5 s (`CHROMA_STEP=0.5`) chroma frames normalized sum 1, weight tonal frame/median clipped 4. Local chroma only for beatless bridge clash; beatmatched uses whole-song key.
- `vocal_curve()`: model frames averaged into 0.25 s bins from window start.

WholeTrack spectral vocal *fallback* (no model) in `analysis/track.rs:40-63,687-693`: `TransitionFeatureFrame.vocal` from `vocal_probability_from(low,vocal,high,flatness)= sigmoid(-2.4+5.2·mid_ratio−0.8·low_ratio+0.6·flatness)`.

**Vocal carve/mid-cut — Orchard has *none* like BitChord’s −6 dB carve.** No mid EQ cut, no ducking curve attenuation, no stem duck. Vocal influences only:
1. **Planning penalties** — see §3 vocalScore & gates; colliding voices veto/demote.
2. **Filter depth modulation only on `FilteredBlend`.** `prepare.rs:303-322`: if strategy is `FilteredBlend` AND any outgoing LowPass present, `depth_curve(crop(frames, outgoing_start,outgoing_end),0,1)` in `orchard-transition-core/curve.rs:16-49` crops evenly-spaced control points, interpolates linearly between neighbors, clamps 0–1, finite check. Applied as `FilterAutomation.depth` running *max* (never closes reopen: geometric `scale_travel` `filters.rs:156-163`), so the low-pass closes only where vocals actually sound, stays closed afterwards (a “running max” to avoid wobble). `models.rs:173-175` note: “V2 treats vocal masking as optional. A missing model leaves Earmark’s flat filter ride.”
3. **Muffle** replaces carve with low-pass closure (~700 Hz at muted point `0.75` through close) and dedicated fade spans; voice-aware gating of muffle (`muffle.js:44-56` swap-voice check). No 300–3k cut.

There is **no equalization-carve envelope** across mid band. BitChord `outMid −6 dB` carve has no Orchard equivalent.

### 2.7 Phrase-Aware Planning

**Earmark path (lightweight).**
- Phrase grid: `bars_per_phrase=4` (16 beats in 4/4). `PhraseGrid::detect()` reads beats_per_bar from grid (`phrase.rs:23-29`). `alignment()` = structural × timing: `structural 1.0` at 0 bar of phrase, `0.7` at half bar, `0.4` off, times `1−dist/tolerance` (`phrase.rs:40-52,74-83`); `UNKNOWN=0.5` no downbeats (`phrase.rs:11`).
- `phrases_in(beats)` needs exact multiple (`phrase.rs:64-71`).
- Candidate scoring: `phrase_alignment = position·0.7 + fit·0.3` with `PARTIAL=0.4` if not whole phrases (`scoring.rs:101-119`).

**JS product planner (rich structure).**
- WholeTrackAnalyzer builds `Phrase {start,end,kind,confidence}` `kinds= intro/outro/breakdown/body` (not BitChord’s VERSE/CHORUS/BRIDGE/PRE/…); phrase_seconds = `beat_interval×32` (`analysis/track.rs:726-813`), boundaries `noise + 32 beats + intro_end + outro_start + content_end` dedup `0.05s`. Mix cues `mix_in_time` downbeat ≤ `min(36s, content_end·0.28)` else `phrase_start+32 beats`; `mix_out_time` from `find_mix_out_time()` (50 ms levels, search starts at 55% duration, silence ≥0.3 s + `after_peak≥2·silence` no quick recovery, backtrack over cliff `max(4s)` weighted). Candidates `mix_in_time conf 0.65·beat+0.35·energy`, `drop_cue`, `energy_cliff`, `outro_start`, `content_end` with scores 0.8–0.95 (`track.rs:806-872`).
- Structural novelty boundaries `StructuralBoundaryCandidate` (`novelty= energyΔ·0.55+lowΔ·0.25+vocalΔ·0.20`) ≥0.14 and local max, snapped to nearest downbeat within `max(0.25, interval)` (`track.rs:1000-1060`), confidence `novelty·0.65+beatconf·0.20+max(stability)·0.15`, max 24, min sep 4 s.
- `structureScore(pair)`=`confidence·0.6+sourceStrength·0.3+meter·0.1` with `sourceStrength=detected-change/beat-entry=1, endpoint=0.62, downbeat-evidence=0.42, rhythmic-fallback=0.22` (`pairTransitionScoring.js:35-40,117-127`).
- Long-transition 32 beats only if `permitsLongTransition()` (both `MEASURED_CHANGES`, `conf≥0.65`, `beatConf≥0.7`, `vocal≤0.35` both) else `[4,8,16]` (`pairTransitionEvidence.js:246-273`).

---

## 3. Clashing Song Handling — The Critical Question

> **Short answer:** Orchard is deliberately *very* conservative on clashing pairs. It does **not** attempt a clever filtered blend or bass-swap save for a harmonically-clashing pair. By default it *vetoes* beatmatched (full/conservative) and falls back to `simple_crossfade` → `normal_boundary` (plays out naturally) or to special native techniques (`muffle_swap`, `drop_in`, `beatless_bridge`) if those also earn `native` render. The user report of “too conservative” is structurally true.

### 3.1 The policy tables

`core/js/src/audio/crossfade/pairTransitionGates.js:21-72` `PAIR_TRANSITION_POLICY` frozen:
```
maxStretchDeviation 0.04
maxRampDeviation 0.08
minBeatmatchConfidence 0.55
loopCost 0.01
trustedHarmonicConfidence 0.65
vocal { activeThreshold 0.6, activeFraction 0.3, minCoverage 0.5, sustainedBeats 4 }
confidence { full 0.78, conservative 0.62, simple 0.42, trim 0.25 }
weights { beat .14, structure .10, tempo .10, harmonic .13, vocal .15, energy .06, spectral .05, stability .04, duration .02, dspRisk .03, flow .18 }
legacyWeights { beat .14, structure .14, tempo .10, harmonic .13, vocal .15, energy .12, spectral .08, stability .07, duration .04, dspRisk .03 } // no flow
```

`evaluatePair()` computes `quality = weighted sum` (`pairTransitionScoring.js:210-212`) and `confidence = clamp( quality·0.82 + evidenceCoverage·0.18 − vetoes·0.06 − demotions·0.015 − loopCost)` (`pairTransitionGates.js:258-264`). Class from `classFor(confidence, maximumClass)` caps earned class by gate `maximumClass` (`pairTransitionGates.js:266-276`).

`CLASS_RANK` = normal 0 < silence_trim 1 < simple 2 < beatless 2.5 < conservative 3 < full 4 (`pairTransitionGates.js:12-19`). Render mode: full/conservative/beatless → `native` (Earmark), simple → `live` (host fade), others → `boundary` (`pairTransitionGates.js:314-318`).

### 3.2 The gates — every veto/demote, in priority order

`candidateGates()` `pairTransitionGates.js:166-248`:

| # | Gate code | Trigger | Caps to | Severity |
|---|---|---|---|---|
| 1 | **style-contrast** | `productionContrast ≥ STYLE_CLASH_CONTRAST=0.9` (`transitionFlow.js:23,361-364`: `log10(sub/low)` abs diff) | **normal_boundary** (play out) | veto |
| 2 | **tempo-distance** | `!fit.beatmatched` | simple_crossfade | veto |
| 3 | **beat-confidence** | `min(beatConfidence) < 0.55` | simple_crossfade | veto |
| 4 | **harmonic-clash** | `harmonic.severeClash` where `severeClash = confidence≥0.65 && score<0.2` (`pairTempoHarmony.js:203`) | simple_crossfade | veto |
| 5 | **vocal-collision** | sustained collision (`coverage≥0.5 && activeFraction≥0.3 && longestRunBeats≥4`) (`pairTransitionGates.js:147-152`) | conservative | demotion |
| 6 | **voice-over-voice** | measured voices finite `&&` (sustained `||` `min(in,out)≥0.5`) | **normal_boundary** | veto |
| 7 | **voice-cut** | `measuredVoice && voice.outgoing ≥ cutAt` *unless waived*. `cutAt=0.1` muffle / `0.3` plain; waived if `(singsOut(analysis, outgoingExit) && alignment≠start) || (muffledIntoBeat && heard≥firstBeat)` (`pairTransitionGates.js:200-209`, `transitionFlow.js:239-243,250-268`) | normal_boundary | veto |
| 8 | **needs-filter** | `needsFilter(harmonic,spectral,collision,measuredVoice)`: `(trustedHarmonic(conf≥0.65) && score<0.55) || spectral<0.55 || (measured ? simultaneousMean>0.25 : sustained)` (`pairTransitionGates.js:140-145`) | **simple_crossfade** | veto |
| 9 | **structure-confidence** | `detected<2 || structureConf<0.55 || meterConf<0.35` where `source∈MEASURED_CHANGES` (`pairTransitionEvidence.js:32`) | conservative (or simple if rhythmic-fallback) | demotion |
| 10 | **evidence-coverage** | `harmonic.confidence<0.25 || coverage<0.5` | conservative | demotion |
| 11 | **phase-error** | `beatErrorSeconds > max(0.2, interval*0.45)` | simple_crossfade | veto |

**Key harmonic scoring.** `harmonicEvidence()` `pairTempoHarmony.js:147-205`: 12-root chromatic circle (`keyRelationship()`) score 0–1: same mode distance 0→1.0, 5→0.85, 2→0.65, 1→0.3, 6→0; cross-mode relative major/minor→0.95, same root→0.7, 5→0.55, 6→0.05 else 0.35. Chroma cosine (12-dim) `0–1`. Combined `key·0.7+chroma·0.3`, fallback single or 0.5 neutral. Confidence = `√(confL·confR)`. *Weak key reads are neutral 0.5* — only trusted keys may call clash (`pairTransitionGates.js:134-135`).

**Explicit fallback after veto.** `planPairTransition()` `pairTransitionPlanner.js:89-267`:
- `settle()` pulls winner: if best class is `simple_crossfade` it is lifted to `normal_boundary` because “A short unmatched fade only sounds like a skip.” (`pairTransitionPlanner.js:99`)
- If no `native` evaluations (full/conservative): tries **`dropIn`** (candidates `anchorTime ≤ openingEnd = audibleStart+8 beats`, only 8-beat `alignment=start` overlaps, only if `renderMode=native`) (`pairTransitionPlanner.js:187-195, 244-247`).
- Tries **`muffle`** for pairs that *only failed for needing filter* (`planMuffles()` — see §2.6) with dedicated fade spans `([swap, exit], [lead, swap])` and re-evaluation (`muffle.js:63-89`, `pairTransitionPlanner.js:249-251`). Energy drop must be `≤5 dB` (`MUFFLE.maxEnergyDrop=5`), skip past first beat `≤40 s`, `minOpening=2 s` before fader moves, body `bass≥0.3`, no singer-swap `in≥0.7 && out≥0.5`.
- Else **`beatlessBridge`** (`beatlessBridge.js:53-98`): *only* if both edges beatless `≥4 s` up to 12 s, `audio.crossfade` — outgoing plays to natural end, incoming from `skip≤16 s`, whole overlap beatless (`beatPresence<0.3`); refuses `severeClash`, `voice-over-voice`, `voice-cut`, `style-contrast`. If bridge fails, `fallbackPlan()` → **`normal_boundary`** (`pairTransitionPlanner.js:205-213`).

### 3.3 What Orchard *does* do on clash — filtered blend & bass swap

**Inside a beatmatched candidate** (i.e. it has passed all vetoes including `needs-filter` → simple), `strategyFor()` picks the actual DSP shape (`pairTransitionGates.js:285-312`):
```
if beatmatched && needsFilter -> FilteredBlend (LPF + HPF + depth-from-vocal)
else if bothBassHeavy -> BassSwap (180 Hz handoff)
else -> beatmatched_crossfade
```
`bothBassHeavy` measured by `flow.sharedBeat ≥0.25` if curves measured, else summary `low≥0.62` both (`pairTransitionGates.js:306-311`). So **filtered blend and bass swap exist but *only* as clean-blend DSP**. They do **not** save a clashing pair — a pair that needs filter is capped to `simple_crossfade` *before* strategy selection. The nuance is deliberate: “Blends that only work behind a filter sound forced; they play out. A muffle is that filter, so only voices that still meet inside its short overlap refuse it.” (`pairTransitionGates.js:210-211`).

For **unmatched** simple_crossfade (`pairTransitionGates.js:289-298`): strategy forced to `filtered_blend` (host EqualPower fade + filtered blend) unless its already vetoed to boundary.

**Earmark-only scorer** (when `ORCHARD_MIX_BEATS=earmark`) is similar: `strategy::choose()` `planner/strategy.rs:25-55`: `duration≤2.5 → ShortFade`; `!beatmatched → clashing?Filtered : EqualPower`; `both low≥0.3 → BassSwap; spectral<0.5 → Filtered; else Beatmatched`. `StrategyConfig: bass_swap_low_energy 0.3, filtered_blend_spectral 0.5` (`config.rs:235-244`). Same philosophy: filter is a *style* inside a beatmatch, not a rescue for a rejected clash.

### 3.4 Why the user sees “too conservative”

1. **Hard vetoes on weak evidence.** A spurious `subBassRatio` diff ≥0.9 (`transitionFlow` contrast), a single sustained vocal overlap ≥4 beats at 0.6 (or any measured `voice-over-voice ≥0.5`), a harmonic severe-clash at `score<0.2` with confidence 0.65, or `spectral<0.55` instantly vetoes full/conservative → `normal_boundary`. Confidence margin (`winnerMargin`) then penalizes further.
2. **No musical save attempts.** BitChord vc68 introduced *bounded 4 s filtered decline* and *key-clash blend* as deliberate clashing-pair vehicles (§6). Orchard has no bounded decline or key-clash blend; only muffle (filter-behind), drop-in (early entry), and beatless bridge — each with its own strict gates (`muffle.js:44-76`, `beatlessBridge.js:66-89`). A high-energy hip-hop pair with a long vocal overlap simply plays out.
3. **Beat-confidence floor too high for some genres.** `minBeatmatchConfidence 0.55`, plus `phase-error veto >0.2 s`, plus downbeat/mirror `meterConfidence 0.35` floor — grids that Beat This/Gold-standard would accept get demoted to lazy. Earmark’s own calibrated confidence supposedly clears ~80–90% on labelled sets (`README.md` Best Mix note) — the remaining ~10–20% are exactly the user’s “conservatives.”
4. **Chroma-locality gap.** Orchard beatmatched uses *whole-song* key; only beatless bridge uses local chroma correlation (`beatlessBridge.js:93-96`). A track modulating 8A→5A forces a whole-track clash. BitChord solved this in vc67 with per-region `keyHead/keyTail` (`BITCHORD_HANDOVER_DJMIX25.md §3 P1`).

---

## 4. NPU / Hardware Acceleration

### 4.1 Snapdragon QNN / Hexagon — yes, as an optional add-on (not bundled)

**Stack: Qualcomm Neural Processing SDK (QAIRT 2.51.0) QNN EP + ONNX Runtime 1.31.0 QNN EP, Hexagon HTP backend.**

- **Apple slice:** Full NPU stack ships as **six separate add-on APKs** (one per Hexagon generation) — not in the main APK. Downloaded at runtime only after user consent. Signature-checked, checksum-verified, installed via FileProvider install intent.

| File | Role |
|---|---|
| `mobile/android/npu-addon/runner/qnn_runner.cpp:1-4`  | `liborchard_qnn.so` C shim over its own ORT (dlopens by path so app’s minimal `libonnxruntime.so` doesn’t satisfy it) they want isolated |
| `mobile/android/npu-addon/build.sh:1-32` | builds `liborchard_qnn.so` (-O2 C++17 -fvisibility hidden), strips, packages `libonnxruntime.so + liborchard_qnn.so + libQnnHtp.so + libQnnSystem.so + libQnnHtpPrepare.so + libQnn Htp{gen}Stub.so + libQnnHtp{gen}Skel.so` into APK per `Hexagon V68,V69,V73,V75,V79,V81` (`build.sh:7,32-38`), zips, aligns, signs, emits `npu-addons.json` (`build.sh:50-54`) |
| `mobile/android/app/src/main/java/.../npu/NpuAddon.kt:34-60` | `installed()` checks `pm.checkSignatures(... ) == SIGNATURE_MATCH` + `longVersionCode >= MIN_VERSION_CODE=1L`. `release()` fetches `https://depot.sfg545.dev/android/npu/npu-addons.json` (5 s timeouts), `download()` via DownloadManager + SHA256 verify + install intent |
| `mobile/android/app/src/main/java/.../npu/NpuDevice.kt:40-69` | Detection map: `SM8350/7325/7350→v68`, `SM8450/8475/7450/7475→v69`, `SM8550/7550/8635/7675→v73`, `SM8650→v75`, `SM8750/8735→v79`, `SM8850/8845→v81`; rejects if `/vendor/lib64/libcdsprpc.so` absent (`NpuDevice.kt:55-57`) |
| `mobile/android/npu-addon/runner/qnn_runner.cpp:65-85` | `open()`: `SetIntraOpNumThreads 1`, `session.disable_cpu_ep_fallback=1`, `session.intra_op.allow_spinning=0`, `ep.context_enable=1`, `ep.context_file_path=<ctx>/ctx.onnx`, `ep.context_embed_mode=1` (context binary inside ONNX), appends EP `QNN` keys: `backend_path=<libDir>/libQnnHtp.so`, `htp_performance_mode=burst`, `htp_graph_finalization_optimization_mode=3`, sets `ADSP_LIBRARY_PATH` |
| `mobile/android/app/src/main/cpp/npu/npu.cpp:65-184` | `nativeOpenQnn()` dlopens `<libDir>/liborchard_qnn.so`, dlsyms `orchard_qnn_open/run/close`, opens both models (`/nirovi`, `/syvenu`). Caches context in `<cache>/ctx.onnx` (compile-then-rename atomic, stale fallback removeTree) |
| `mobile/android/app/src/main/java/.../npu/NpuSupport.kt:95-154` | Orchestration: detect `Build.FINGERPRINT` cache; on `setEnabled(true)` compiles (marker `<cache>/ready`), runs `nativeSelfTest()` silence → finite check; `compiling` UI state; MediaTek path instant-enable, Qualcom shows download size dialog |
| `crates/orchard-adaptive-mix/src/npu.rs:13-17,44-74` | Rust FFI `Vtable {run,close}` to match `mobile/.../npu.cpp:76-80`, FRAMES 1500 BINS 128, `VOCAL_BORDER=250`. `beat()` returns split `beat/downbeat` logits `[1500]×2`; `vocal()` overlapping windows stride `FRAMES-2*VOCAL_BORDER=1000`. `unsafe impl Send` noting native serializes per-model invocations (`npu.rs:25`) |
| `mobile/android/app/src/main/cpp/npu/neuron.*` + `npu.cpp:101-140` | MediaTek **Neuron** adapter fallback: `nativeDetectNeuron()` exercises both `.npug` graphs full-support check (`fullSupport()`). If any op unsupported → `supported=false` reason. Opens via `NeuronModelRunner::prepare()` with on-device `npu/cache/.../nirovi.neuron` + `syvenu.neuron` |

**What runs on NPU:** `Models::set_npu(Some(Npu))` overrides CPU sessions for *both* beat (`beat_logits`) and vocal (`vocals`). Fallback is automatic: on first `Err` from NPU, `self.npu=None` and CPU takes over (`models.rs:113-119,190-195`) — silent degradation. Desktop never uses NPU (WebGPU/CPU only).

**Desktop requires no add-on.** ORT 2.0.0-rc.13 `webgpu` feature (`crates/orchard-adaptive-mix/Cargo.toml:19` desktop `dep:ort, ort/webgpu`); worker loads `nirovi_fp16.onnx` with `ort::ep::WebGPU` + `session.disable_cpu_ep_fallback=1` (`models.rs:29-41`). Missing WebGPU → `nirovi_int8.ort` CPU (`models.rs:62-86`). ORT experimental NPU notes on desktop ignored.

### 4.2 ONNX Runtime Configuration (Desktop & Mobile CPU baseline)

| Mode | Value | Effect |
|---|---|---|
| **Desktop (WebGPU)** | `with_execution_providers([WebGPU::default().build().error_on_failure()])`; `with_config_entry("session.disable_cpu_ep_fallback","1")` | Strict GPU, no silent CPU fallback; if unsupported ops → factory error and CPU fallback manually |
| **Desktop (CPU)** | `with_intra_threads(2)`, `with_inter_threads(1)`, `allow_spinning=0`, `arena_allocator(false)`, `memory_pattern(false)` | Two non-spinning threads, arena/memory pattern off. Comment: idle pool sleeps; spinning steals playback cycles (`models.rs:42-58,61-66`) |
| **Android (CPU)** | Same `cpu_builder(threads)`: `intra_threads=threads`, `inter=1`, `arena=false`, `pattern=false`, ORT session from `ort::init_from("libonnxruntime.so")` already loaded by JVM (`crates/orchard-transition-mobile/src/mix.rs:156-164`). Beat uses caller threads (≤4 recommended), vocal ≤2 threads (`models.rs:89-106`). | Same minimal ORT library shipped APK `libonnxruntime.so` |
| **NPU (add-on)** | `SetIntraOpNumThreads(1)`, `disable_cpu_ep_fallback=1`, `allow_spinning=0`, `ep.context_embed_mode=1`, `QNN=backend_path:libQnnHtp.so, htp_performance_mode:burst, htp_graph_finalization_optimization_mode:3` | Single-thread strict HTP burst, mode 3 fastest compile; context embedded (`qnn_runner.cpp:68-81`) |
| **License / features** | `ort/webgpu, download-binaries, tls-rustls, copy-dylibs` desktop; `ort/load-dynamic` Android | Mobile loads dynamic ONNX shipped in package |

### 4.3 Model Quantization

| Model | GPU asset | CPU asset (ship) | NPU add-on asset | Quant method |
|---|---|---|---|---|
| **Nirovi (beat/downbeat)** | `nirovi_fp16.onnx` FP16 for WebGPU (15 252 717 B, sha `f7491…`) | `nirovi_int8.ort` static QDQ **uint8 act/int8 weight**, except `/Div_output_0` & `/stem/stem.1/Relu_output_0` stay uint16 (quantizing input causes nearly all loss) (7 948 784 B sha `7454d…`), converted `convert_onnx_models_to_ort --target_platform arm --enable_type_reduction`, calibration `get_qnn_qdq_config` MinMax ∞ 203 random non-GTZAN 1500-frames, `CalibMaxIntermediateOutputs 8` (`models/nirovi/README.md:8-19`) | `models/npu/qnn/nirovi_qnn.onnx` 7.7 MB QDQ (A8W8) for `orchard_qnn_open` (`ls models/npu/qnn/`). Same for Syvenu | Calibration QAT8 trained dilated TCN (w384 7 rhythm layers) distilled from Beat This `final0`; Beat This official MIT weights (`mobile/BEAT_MODEL.md:21-30`) |
| **Syvenu (vocal)** | FP32 in ONNX (569k params) → ORT CPU FP32 `.ort` same conv stem | `.ort` FP32 (not int8; 120 ms/60 s regardless) (`models/syvenu/README.md:28-30`) | `syvenu_qnn.onnx` 709 KB A8W8 for NPU (`models/npu/qnn/`) async 1×1 head; `npu.rs` fixed 1500-frame windows ( ≈5 s each side) | FP32 weights; exported opset 17 PyTorch→ONNX max diff 4e-7, then ORT conversion max diff 2e-7 |

**Production consensus per `mobile/docs/BEAT_QUANT_BENCHMARK.md:3` + `BEAT_MODEL_ACCURACY.md:3`**: “*Historical experiment: production now uses dynamic INT8 ONNX Runtime on CPU. QNN dependencies now removed; NPU results describe earlier experimental build.*” The add-on build (`mobile/android/npu-addon/README.md:3-10`) is manual-only (only `Publish Android NPU add-ons` workflow, not auto on mobile tags/canary). The Kotlin `NpuSupport` consent prompt/threshold UI remains in main branch (`SettingsCrossfade.kt:41-123`, `NpuDialogs.kt:72-188`) — opt-in experiment.

### 4.4 Benchmarks (all numbers published by Orchard in-repo, measured on **Snapdragon 7 Gen 1 razr 2023 SM7450**, only phone used)

**30 s CPU windows (4 threads, fixed 1500 frames):**

| Model | int8 dyn | INT4 block32 weights | static A8W8 | Comment |
|---|---|---|---|---|
| small0 | 2307.6 ms | 2260.6 ms | 2400.5 ms | INT4 −2% vs int8 |
| final0 | 3380.9 ms | 4009.1 ms | 3721.3 ms | INT4 +19% slower |

**6 s CPU vs NPU (A8W8, same QDQ graph, precompiled contexts):**

| Model | CPU A8W8 | NPU A8W8 | × ratio | Cached load | compile |
|---|---|---|---|---|---|
| small0 | 210.3 ms | 68.6 ms | 3.06× | 248.5 ms | 60.4 s |
| final0 | 316.0 ms | 79.3 ms | 3.99× | 377.4 ms | 62.8 s |

**30 s NPU final0:** compile 529.7 s (mode 1) into 54 571 850 B EPContext (`[1,1500,128]`); cached load 1 082 ms; median 1 191 ms (range 1 187–1 253, ~25× RT) (`BEAT_QUANT_BENCHMARK.md:35-37`). Full-size fast-mode >120 s/crash ~3 GB heap (`-benchmark.md:47-52`). Compatibility matrix A8W8 ok fast-mode only; A16W8 120 s cutoff / crash default; A16W16 BatchNorm rejected (`-benchmark.md:45-49`).

**Accuracy (GTZAN 100-track mini, F1, macro; upstream `mir_eval` 70 ms):**

| Model | Exec/prec | ctx | beat F1 | downbeat F1 |
|---|---|---|---|---|
| final0 host FP32 | — | 30 s | **88.58** | **72.36** |
| final0 host FP32 | — | 6 s | 84.33 | 62.41 |
| final0 CPU dyn INT8 | 6 s | 6 s | 84.28 | 62.24 |
| final0 CPU A8W8 QDQ | 6 s | 6 s | 84.29 | 62.12 |
| final0 **NPU A8W8** | 6 s | 6 s | **83.13** | **56.74** |
| small0 NPU A8W8 | 6 s | 6 s | 78.35 | 52.03 |
| final0 **NPU 30 s A8W8** | 30 s | 1500 | **56.59** | **32.73** (rejected, do not ship) |

**Provider numerics:** CPU-vs-NPU same QDQ reassembled logit MAE small0 1.75/1.69, final0 1.48/1.75 beat/downbeat (`BEAT_MODEL_ACCURACY.md:41-46`). Logit MAE host vs quantized: INT8 0.26, A8W8 2.32 (final0) (`-benchmark.md:59-63`).

**Nirovi QAT8 quality (desktop/CPU):** 1 000 GTZAN logits diff 0.11, same sign 99.7/99.9% beat/downbeat; beat/downbeat F1 0.8787/0.7494 vs weight-only 0.8799/0.7514 (`models/nirovi/README.md:38-41`). Desktop WebGPU 42 ms vs Beat This fp32 123 ms on 1500-frame (Radeon 9060 XT) (`nirovi README:68-72`). Android CPU 1.3 s/window A53 (2.4 s weight-only) 262 ms SD 7 Gen1 (1 121 weight-only) (`beat model README:59-65`), Syvenu 120 ms/60 s 2 threads on desktop (`syvenu README:50`).

**Bottom line for BitChord:** NPU buys ~3–4× throughput (6 s) or ~3.1× (30 s, 1 191 vs 3 721 ms) at accuracy cost 1–5 pp downbeat (6 s) and catastrophic full-length A8W8 (not shippable). Orchard stopped shipping NPU for playback in favor of dynamic INT8 CPU (`BEAT_QUANT_BENCHMARK.md:3`, `BEAT_MODEL_ACCURACY.md:3`). NPU still exists but as opt-in experiment behind download prompt.

---

## 5. DSP / Audio Processing

| Area | Orchard implementation | Files |
|---|---|---|
| **Native code** | **Rust**, no C/Kotlin on render path. Planar `f32`, `Vec<Vec<f32>>` one vector/channel (`audio/buffer.rs:12-16`). FFT via `realfft` (2048/512 hop default), `biquad` DirectForm2T, `rubato` FFT resampler, `signalsmith-stretch` para time stretcher, `ebur128` R128 meter | `crates/earmark/Cargo.toml:14-21` deps; `buffer.rs:3-8` rationale planar keeps loops flat |
| **Real-time safety** | **Not real-time.** `SmartCrossfadeEngine` reused, `Send` not `Sync` — one per worker (`lib.rs:123-128`, `engine.rs:22-27`). Rendering “never on audio callback” — `orchard-adaptive-mix/src/lib.rs:1-5` “Local adaptive mix preparation. Never runs on audio callback.” Worker decodes tail/head at 48 kHz, blends offline to `Vec<f32>` interleaved, host splices. Host joins matched on audio with 5 ms fade (`README.md:20-22`). *Inner loops avoid allocation* (reuse `envelope`/`gain`/`mono` scratch) but outer API (`slice_seconds`, `to_interleaved`, `process_mapped`) allocates — not allocation-free |
| **Buffer management** | `Analyzer { spectrum, mono }` reused; `Renderer { stretcher?, envelope, gain, outgoing_peaks, incoming_peaks }` persisted (`renderer.rs:16-24`); `TimeStretcher { stretch, input, output }` interleave scratch reused, reallocated on channel/rate change only (`stretch.rs:16-38`, `renderer.rs:149-162`). Envelope built once per stem via `FadeCurve::fill_*_span` + `AutomationCurve::fill` (`renderer.rs:104-134`, `fade.rs:44-53`) |
| **SR handling** | **Incoming rate wins** so nothing needs conversion after handoff (`engine.rs:263-266`, `lib.rs:66-68`). Mismatch allowed by resampling (rubato streaming, trim `output_delay`) or error if `allow_resampling=false` (`engine.rs:253-266`); forced `output_sample_rate=Some(48_000)` in worker (`planner.rs:16-19`). `to_channel_count` mono↔stereo fan-out/fold; anything else error (`buffer.rs:163-182`). Resample boundary in `render/stage.rs:78-84` (slice→resample→channel). Duration tolerance `1/sample_rate` in validation (`engine.rs:343-350`) |
| **Limiter** | **Look-ahead offline peak limiter** (whole buffer clairvoyant). `limiter::frame_peaks()` → `held()` max-filter (monotonic deque) `lookahead_ms=5` → `lookahead_gain()` windowed-min + box-average + one-pole release `80 ms` (`dsp/limiter.rs:1-9`, `config.rs:131-133`). `stage::limit_sum()` allowed = `max( ceiling(−0.5 dBFS), outgoingPeak(b), incomingPeak(b) )` per frame; trims only if summed peak > allowed (`stage.rs:314-363`, `limit.rs:92-110`). Side already hot keeps level; returns `ceiling_trim_db`. Prevents adding peaks, not whole-mix trim that would step at joins (`stage.rs:314-319`) |
| **Sample-accurate automation** | Breakpoint lists normalized 0–1 timeline, cursor walk O(n) (`dsp/automation.rs:90-201`). Geometrical/logistic shapes for filters; gain ramps `SmoothStep`. Applied once per transition to produce `Vec<f32>` envelope shared across channels (`render/renderer.rs:164-188`) |
| **Beat tracker** | No built-in DSP beat scorer after model. `WholeTrackAnalyzer` is V2-parity with `BeatTracker` (onset KDE + two-pointer Viterbi + bar HMM 3/4,4/4) tracking at **50 Hz log-mel** — emission means/bar templates/confidence fitted on Beat This CC BY-4.0 spectrograms (Ballroom etc., GTZAN held out) (`THIRD_PARTY_NOTICES.md` Earmark section). Used only `ORCHARD_MIX_BEATS=earmark` A/B fallback; production path is **Nirovi inference** (`prepare.rs:204-231`) |

---

## 6. Comparison Matrix vs BitChord vc68

BitChord baseline from its own handover (vc68 SAT `2.2.3-djmix26`, BITCHORD_HANDOVER_VC68_WRAPUP.md:5-28, BITCHORD_HANDOVER_DJMIX26.md §3, BITCHORD_HANDOVER_DJMIX25.md §3):

* vc61 beat/loop foundations, phrase lanes → vc62–66 loop-mix renderer, `mix.handoff.deferred` (`canAdoptSessionPlayer`), EDM buildup + stereo Jet Flanger + 4→2→1→0.5 loop roll, BUILD/DROP, TPT SVF, plate reverb, 1-beat 200 Hz bass swap, 1.5 ms limiter  
* vc67 UTF-8 keys, Rung-4 Echo fallback, 8-bar/15-s EQUAL_POWER floor, `mixFall` cos-fall + TAIL→HONOUR, `tailDurationMs` reset, prime-before-yield + streaming fallback, VERSE/CHORUS/BRIDGE/PRE/POST/BUILD/DROP, Interpolation beyond 4 bars, `VERSE_END/BRIDGE_END`, Wiener `umxhq` `SeparateVocalWindow` ≤22.8 s, `acapella` 0.88/0.12, per-region Krumhansl+Temperley `keyHead/keyTail`, `ReverbWash` stereo comb 0.0302–0.0444, `echoFeedbackFor` 0.55 >12 s / 0.45 ≤8 s / half M10/CUT_ON_ONE, `REVERB_DAMPING_EDM 0.48/WET 0.42`, 9-point `FaderParams` club/lyrical/radio/ambient, `MixWeightTuner` [0.6,1.5], `MixVerify` (±3 LUFS, 0.896, ±25 ms, exitGates==0), full `mix.plan` schema  
* vc68 CountdownStabilizer + scrub robustness + smartTransitionWindow unification (Task 2A), **downbeat snapping + ramp-hold sync + vocal carve fix (−6 dB outMid) + DJ echo-out + bounded 4 s filtered decline + key-clash blend** (Task 2B)

| Category | Orchard | BitChord vc68 | Verdict |
|---|---|---|---|
| **Architecture / runtime** | Rust Earmark (offline) + QuickJS JS planner + Qt/C++/Kotlin UI + ORT/Dawn. Analysis 44.1 k, render 48 k stereo (`Cargo.toml`, `prepare.rs`, `lib.rs`) | Kotlin (planner/scoring) + C++ native `.so` (`libbitchord_analysis`, `libbitchord_loopmix`, `libbitchord_stretch`) + ONNX (umxhq 9 MB + beat_this 23 MB) + Signalsmith header. Live DJ loop-mix renderer, deck budgets, `mix.handoff.deferred` guard, human-verifiable telemetry 2 226–2 312 tests | BitChord already matches offline-plan + native render; Orchard’s QuickJS-in-Rust split not needed |
| **Beat/bar/downbeat** | Downbeat-only anchors `downbeats→beats→earliest`; hard phrase gate + phase-error veto >0.2 s; kick-lock ≤⅛ beat after plan | Downbeat snapping (vc68) + ramp-hold sync; chord-interpolation beyond 4 bars; section-boundary bonuses | **Orchard kick-lock is superior** (BitChord has no onset-phase fine-align ≤⅛ beat) |
| **Tempo matching** | Constant stretch ≤4% (JS) inclusive or glide ≤8% (JS) / ≤12% (Earmark); log-mean tempo one shared geometric glide; demote to unmatched (never drop), fold 2:1; no going forward unless glide | Ramp-hold sync (vc68) + folded tempo (~2.6% example) beatmix, no chipmunk/stutter; scalar BPM + Camelot distance only | Orchard glide more principled (one tempo trajectory) but BitChord ramp-hold catches up; Orchard has no ramp-hold stutter data |
| **Crossfade curves** | Equal-power `sin/cos` default, `SmoothStep` for filtered blend+loudness, linear available (tests only); fades span-aware `(0,1)`, muffle shortened | 9-point learned `FaderParams` club/lyrical/radio/ambient; `mixFall` cos-fall on echo, phrase-length EQUAL_POWER floor 8 bar/15 s | **BitChord exceeds** (learned trims) — Orchard never saw 9-point templates |
| **Filter sweeps** | Simple station Biquad DF2T Q 0.707, LP 18 k→400 logistic (0.3→1.0) + HP 180→20 (0–0.6); BassSwap HP 20↔180 at 0.45–0.55; muffle 4th-order Butterwater open 18k→6k→300 (Q .54/1.3) + HP; geometric; coeff update 32 | TPT SVF, plate reverb, Jet Flanger + loop roll + BUILD/DROP, vocal carve −6 dB outMid + carve fix, bounded 4 s filtered decline; “filtered blend” = low-pass hide | **BitChord exceeds** in range and DJ performance — Orchard only plain HPF/LPF two-station |
| **Echo/delay/reverb** | **None** (§2.5). No DJ tail, no send, no feedback |  `LoopMix.bridge` Rung-4 Echo (dub echo + reverb release), `PhraseConfig.echoFeedbackFor` 0.55/0.45/halve M10, `ReverbWash` Freeverb-spread combs 0.0302–0.0444 damping 0.48/wet 0.42, DJ echo-out tail on hard exits (vc68) | **BitChord already superior** — Orchard has nothing to port |
| **Vocal detection** | Classifier `Syvenu` 0–1/frame from beat spectrogram (UMX share); broad, synths false-positive; used as gate + depth-mod only, *no separation* | Real separation `SeparateVocalWindow` Wiener STFT/ISTFT with MIT umxhq ONNX ≤22.8 s worker, `acapella` 0.88/0.12, optional instrumental ≥8 GB; carve −6 dB outMid, 0 dB inMid (+fix in vc68) | **BitChord exceeds** (separation + carve). Orchard depth-from-vocal idea (running-max filter depth) could replace full DuckCarve blindness |
| **Phrase planning** | `PhraseGrid` 4-bar 16-beat only (intro/outro/breakdown/body) + novelty boundaries; StructureScore confidence·0.6+source·0.3+meter·0.1; 12 candidates/64 finalists | Full VERSE/CHORUS/BRIDGE/PRE/POST/BUILD/DROP, Interpolation + repeatedBars beyond 4, `VERSE_END/BRIDGE_END`, boundary bonuses, `smartTransitionWindow` unified (vc68) | **BitChord exceeds** structure depth |
| **Clashing handling** | Stratified gates `normal≤silence<simple<bridge<conservative<full`, `needsFilter/spectral<0.55/voice-over-voice/harmonicClash/severe<0.2/styleContrast≥0.9/beatConf<0.55/phase>0.2` veto to simple/normal; only muffs/drop-in/bridge if they independently hit native; *otherwise play out*. Bass swap **inside** pair only if `sharedBeat≥0.25` after surviving gates | **Mitigates instead of vetoing**: *key-clash blend* (keyHead/keyTail per-region overrides whole-track clash), *bounded 4 s filtered decline* caps old unbounded decline, *DJ echo-out* on hard exits, *Rung-4 Echo* for tempo gaps, phrase-length EQUAL_POWER floor, `MixVerify` + `mix.plan` full-schema triage | **BitChord already more permissive/clever; Orchard’s “too conservative” is real.** The lesson is Orchard’s explicit straight-fit “muffle/drop-in/bridge” patterns show *what* alternatives are worth adding to BitChord’s decline path, not its veto thresholds |
| **NPU / HW** | Desktop WebGPU FP16 / CPU INT8 / optional QNN add-on (6 Hexagons V68–V81) + Neuron MediaTek, `burst`, context-embed; **benchmarked 3–4× faster 6 s but 1–5 pp worse, 30 s A8W8 catastrophic → production back to CPU dynamic INT8** (`BEAT_MODEL_ACCURACY.md:36-46`) | No NPU experiment. CPU ORT INT8 `beat_this_final0_q8.onnx` + umxhq INT8 bundled; zero audio-thread allocs | Orchard **experimental/hedged**; BitChord not behind for ship. NPU still to beat (30 s context) |
| **DSP quality** | Flat renderer: biquad (no TPT), rubato resample, signalsmith stretch, ebur128 region R128 trim (±1 dB tolerance, ≤6 dB max), peak sum limiter only, native-edge splice (0.25 s + 1024 f splice, ±256 search) + `release_filters` dry join | TPT SVF, stereo Jet Flanger, Freeverb combs with per-buffer cursors (no 8192 click), 1.5 ms limiter, zero audio-thread allocs (buffers in `Renderer::prepare()` on worker), Signalsmith previously [email protected] — but `MixVerify` gates ±3 LUFS/0.896/25 ms | Orchard **release-to-dry splice + hold-search** more phase-safe for blends; BitChord TPT/verb better sounding |
| **Telemetry / verifiability** | `diag` per-candidate `CandidateScore` (tempo/phrase/spectral/loudness/total), `summary()`, `ORCHARD_MIX_DUMP` JSON dump `<cur>-<next>.json`, `explainRefusal(code,detail)`, stored evidence SQLite 32 MiB, versioned `measured::VERSION` hash | `automix-telemetry.jsonl` 9 463+ lines, `mix.fire/complete/handoff.deferred/pause.detected/verify/plan` full schema (rung/style/exit etc), `MixVerify` dry-run, `CountdownStabilizer` vs flapping | Both strong; Orchard dump **easier to replay**, BitChord more runtime-rich |

**Key gaps worth porting (see §8):** vocal-depth-modulated filter (running-max), kick-lock ≤⅛ beat, native-edge splice + release-to-dry, region R128 sum-limiter, lead/swaps/drop-in/muffle/bridge family (not Orchard thresholds), evidence cache + `ORCHARD_MIX_DUMP` pattern, log-mean numeric rigor.

**Where BitChord already ahead:** DJ FX (echo/reverb/flanger/loop roll/build-drop), real vocal separation, per-region keys, section interpolation, bounded filtered decline + echo-out being a safer declination than playing out, learned fader templates. *Do not copy Orchard’s gates.*

---

## 7. Portability Assessment

### 7.1 License compatibility

| Component | License | Compatible with BitChord GPL-3.0+? |
|---|---|---|
| **Orchard engine** (`crates/earmark/**`, `orchard-adaptive-mix/**`, `orchard-transition-{core,planner,mobile}/**`, `core/js/src/audio/crossfade/**`, `models/{nirovi,syvenu}/**` weights) | **PolyForm Shield 1.0.0** (`LICENSE-POLYFORM-SHIELD`, header `Copyright © 2026 SFG545 Licensed under PolyForm Shield`, `Cargo.toml` license-file) | **No.** Distribution/Changes licenses allow use “for any permitted purpose,” **except** “providing any product that competes with the software” (`LICENSE-POLYFORM-SHIELD: Noncompete/Competition` sections). BitChord is a competing music player. Section 7 that would let you convey the combined work without bringing Shield code under AGPL is explicit: *“This permission applies only to the engine files that carry a PolyForm Shield header. It grants no rights to the engine itself.”* (`LICENSE-EXCEPTION.md:12`). Shipping BitChord with Shield-derived code = breach of Noncompete. Even shipping BitChord with Orchard AGPL code triggers AGPL §13: any GPL work combined with AGPL must be conveyable under AGPL-compatible terms and would be forced into AGPL (permitted to relicense GPL-3.0+ code under AGPL for conveyance, but cannot reconvey under GPL alone). |
| **Orchard AGPL remainder** (Qt app, `app/`, `mobile/` unless Shields header, `providers/`, vendor). | **AGPL-3.0-or-later** | AGPL §13 permits combining GPL+AGPL; the *whole* combined work must be available under AGPL. BitChord currently advertises **GPL-3.0+ clean** (`BITCHORD_HANDOVER_VC67_WRAPUP.md §2`). Adopting AGPL text would force AGPL obstructions and conflict with BitChord’s claimed GPL-only posture (and its Nirovi-like MIT choices: `vocals_umxhq_int8.onnx` MIT, Demucs *not* used NC). Only safe if BitChord willing to relicense to AGPL-3.0-or-later — a major product decision, not an engineering port. |
| **BitChord actual native** | GPL-3.0+ (per handover “GPL-3.0+ clean; no madmom/umxl NC, SamAfshar/AI_DJ, or Orchard” — `BITCHORD_HANDOVER_VC67_WRAPUP.md §2`). Bundled models MIT (`vocals_umxhq_int8.onnx`, `beat_this_final0_q8.onnx` ONNX MIT; htdemucs NC intentionally omitted) | Must stay MIT-model + GPL-code. Orchard’s Nirovi/Syvenu weights themselves are GPL? No, Nirovi distilled from Beat This MIT; spec Shälder? Check THIRD_PARTY_NOTICES for Nirovi/Earmark fitting Cycle — beat annotations MIT CC-BY MIT — but product weights remain Shield-licensed binary alongside code: no legal room to ship them in BitChord |
| **Licensed surfaces to avoid** | `ebur128` (LGPL 2.1+ but works as crate), `biquad 0.6`, `rubato 5`, `signalsmith-stretch 0.1`, `realfft 3.5`, `num-complex 0.4`, `ort 2.0-rc.13`, all MIT/Apache as isolated crates — *not* Shield themselves | These deps individually are fine to reuse in BitChord **fresh** (not copied Orchard code). No license path via Orchard workspace itself |

**Legal summary:** *Copy-paste is blocked.* BitChord can study ideas (e.g. parameter values like 0.45/0.55 swap, 180 Hz pivot, 0.4/0.12 stretch/ramp bands are factual numbers — facts themselves likely not copyrightable, but Orchard’s expression of them in source *is* protectable) and implement **original** calculations from scratch. Contract counsel is required before any direct reuse of Shield or AGPL sections. The handover’s “GPL-3.0+ clean” invariant would be lost on direct import — intentionally quoted (“no Orchard” — `BITCHORD_HANDOVER_VC67_WRAPUP.md` § lineage invariants carried forward).

> **Not legal advice.**

### 7.2 Code quality / portability of identified techniques

| Technique | File | Language | Quality / Effort to port (clean-room) |
|---|---|---|---|
| `depth_curve` crop+sample+running-max + `FilterAutomation.depth` geometric scaling | `orchard-transition-core/curve.rs:16-59`, `earmark/dsp/filters.rs:82-154` | Rust | Small (<1 d): 50 LOC math; drop into C++/Kotlin `FilterSweep` class directly. Testable via constant/linear depths |
| Native-edge splice (`prepare_glide`, `aligned_end`, `splice_start/end`, `release_filters`) | `render/stage.rs:17-28, 129-295` | Rust DXT | Medium (1–3 d): `TempoGlide::offset()`, `crossfade` helpers; requires source/phase-aware verification harness |
| Kick lock ≤⅛ beat correlation 35–140 Hz 256 bins 8-bar context | `orchard-adaptive-mix/lock.rs:80-125` | Rust | Small (1 d): FFT-independent onset extractor + float correlation |
| Log-mean glide `TempoGlide::pair` + `average()`/`offset()` | `types/transition.rs:108-156` | Rust | Small (<1 d) |
| EBU R128 region + sum-only limiter | `analysis/loudness.rs:18-61`, `render/stage.rs:314-363`, `dsp/limiter.rs` | Rust | Small if reusing `ebur128` crate (few LOC); hygiene check for tail |
| Bass warp `warp()` + zero-phase double-pass LP 180 Hz | `render/bass.rs:25-82` | Rust | Medium: needing DSP test for clicks |
| Filter `Subscription` geometric `scale_travel` running max | `filters.rs:156-163` | Rust | Small |
| Automate `CurveShape::Logistic/logarithmic` spectral travel | `dsp/automation.rs:29-68` | Rust | Small (<0.5 d) |
| Candidate/Phrase/Structure scoring + long transition guard | `planner/scoring.rs:50-208` + `candidate.rs:196-265` + `analysis/phrase.rs` | Rust | Medium: weight recalibration on BitChord scalar data |
| JS gate suite + weights (policy) | `core/js/.../pairTransitionGates.js` | JS (QuickJS) | Large: tune 11 weights + 11 vetoes against BitChord scalars, do NOT copy clauses verbatim |
| JS muffle/drop-in/bridge family | `muffle.js`, `beatlessBridge.js`, `beatlessBridge.js`, `transitionFlow.js` | JS | Large (structure re-design) |
| WholeTrack + Screen analysis | `analysis/track.rs:1-164` | Rust | Large |
| BeatThis/Nirovi frontend + chunking/border | `plan/.../beats.rs:1-129`, `analysis/model_frontends.rs` | Rust+ORT | Large (model integration); BitChord already has beat_the_this q8 |
| NPU QNN runner + NPU Kotlin orchestration | `npu-addon/runner/qnn_runner.cpp`, `mobile/.../npu/*.kt` | C++/Kotlin/Rust FFI | Large (≈1–2 w incl. Hexagon APK packaging/signing; EOL not shippable full-length A8W8 anyway per benchmark — see §4 not recommended) |

All references Rust→Kotlin/C++ clean-room must translate types (planar `f32` vs interleaved, `AutomationCurve` vs `CurvePoint`) carefully — no ABI shared.

### 7.3 Estimated effort to integrate each finding (clean-room, not copy)

| Finding | Est. effort* | Risk |
|---|---|---|
| Vocal depth ride on filtered blend | S (<1 d) | Low — testable offline |
| Kick lock | S (1 d) | Low |
| Log-mean glide + native-edge splice | M (2 d) | Medium — phase bugs |
| Release-to-dry filter join | S–M (1–2 d) | Medium — dry buffer lifecycle |
| Region R128 + sum-only limiter | S (1 d) | Low — crate swap |
| Bass warp | S (1 d) | Medium — click regression |
| Biquad geometric logistic | S (0.5 d) | Low |
| Long-transition + scoring recalibration | M (2–3 d) | Medium-high — weight tuning needs telemetry |
| Full JS gate suite | L (1 w incl. QA) | High — subjective tuning rotten |
| Muffle/drop-in/bridge family | L (1–2 w) | High — coverage/completion regressions |
| Storage/DB evidence cache + DUMP | S (1 d) | Low |
| NPU QNN | L (>2 w) | High — packaging + accuracy regression + build complexity |

\* S=small <1 d, M=medium 1–3 d, L=large >3 d. Includes test harness setup in BitChord’s `2 312-run` green gate culture.

---

## 8. Prioritized Recommendations for BitChord vc69

Ordered by *impact ÷ effort ≈ ROI*. Each is a clean-room reimplementation of an **idea** from Orchard — no verbatim code.

### P0 — Fix clashing-pair conservatism & makeifiable decline alternatives

1. **Add a “muffle” alternative to bounded filtered decline, after a musical gate.** Copy functional *structure* of `muffle.js:12-89`/`lib.rs:99-117`: propose Candidate → re-evaluate with muffle spans `incoming [lead→swap] outgoing [swap→exit]`; measure `energyDrop ≤ 5 dB`, `skip ≤ 40 s`, `body ≥ 0.3`, swap-voice `out≥0.5 && in≥0.7`, `lead ≥ 4 beats`, `exit = swap + 2 beats`. If BitChord’s 4 s filtered decline vetoes a pair that muffle would survive, try muffle before `normal_boundary` echo kill. **Impact:** salvages the populist “holds up under a low-pass” crowd vs outright play-out; **Effort:** M (Bodies already have FilteredBlend plumbing; need re-evaluate harness). **Why first:** directly attacks the user report that Orchard/Bitchord declines too many clashes — but with *structure*, not weaker thresholds.

2. **Add a “drop-in” alternative for thriving-inside-genre pairs.** `pairTransitionEvidence.js:360-363` + `transitionArrangement.js:24-28`: 8-beat overlap `alignment=start` with I/O spans `([0,0.5],[0,1/beats])`, only after ordinary blends fail. Incoming anchor `≤ audibleStart + 8 beats`. **Impact:** genre switching (hip-hop→EDM “keeps playing”) without needing a full 16-beat blend. **Effort:** S–M (existing dominant exit already rides faders). **Test:** use `Beautiful → Never Let Go` (sung outro) and `Technologic → Guess` cases from vc68 gate.

3. **Add beatless bridge for double-edge pairs.** `beatlessBridge.js:12-98`: both sides ≥4 s beatless ≤12 s, `skip ≤16 s`, whole overlap beatless, N beatless edges min 4 s / max 12 s, `harmony.score≥0.62+0.2·(harm−0.5)` handle, refuse vocal collisions/severe clash/style. **Impact:** covers R&B/ambient ↔ dance intro pairs that otherwise play out;  enables no-tempo mixing. **Effort:** M (reuse BitChord beatPresence scalar helpers, measure 35–140 Hz band 0.25 s as in `evidence.rs:50-68`).

### P1 — Salient DSP correctness wins (small, low-risk)

4. **Vocal-depth-modulated filter (running-max).** `curve.rs:16-59` + `filters.rs:82-154`. Rather than fixed 18 k→400 LPF, BitChord’s filtered blend should ride how hard vocals sound per 0.25 s bin: `depth = vocal_curve_crop(outgoing_start,end)`, applied as *running maximum* so filter never reopens mid-transition. **Impact:** hiss/stab over bass line without full vocal carve; complements BitChord’s −6 dB outMid carve. **Effort:** S.

5. **Kick-lock ≤⅛ beat after plan (adding correlated onset correction).** `lock.rs:80-125`. Adopt constants `HOP=1 ms, BINS=256, CONTEXT_BEATS=8, LIMIT=BINS/8, MIN_MATCH=0.5, MIN_GAIN=0.2`. **Impact:** fixes flam where BitChord beat tracker grids are “tens of ms off” as Orchard notes (`lock.rs:3-6`). **Effort:** S.

6. **Release filtered stems to dry at native joins.** `stage.rs:256-295`. When outGoing high-pass ~20 Hz or incoming low-pass ~18 kHz rests open at join, blend 1024-frame crossfade to dry copy so phase steps vanish. **Impact:** audible clicks on bass clean-ups. **Effort:** S–M (need dry alias retained).

7. **Glide + native-edge hold (0.25 s + 1024-frame splice + ±256 search).** `stage.rs:17-28, 129-204`, `types/transition.rs:113-151`. If BitChord’s ramp-hold needs clean hand-off at native tempo, use it. **Impact:** quality of tempo glides (Drip→UP folded tempo gate). **Effort:** M (test requires chord)

8. **Sum-only limiter and region-R128 trim.** `loudnessConfig` + `limit_sum` + peer-to-real-time `ebur128`. Use captured LUFS difference `tolerance 1.0 dB, max 6 dB` at scored region boundaries; limiter `ceiling −0.5 dBFS`, `lookahead 5 ms`, `release 80 ms` only over peaks that summed peak exceed a side. **Impact:** prevents whole-mix trim level steps. **Effort:** S (reuse existing `MixVerify` scalars for comparison why Orchard trims only when summing adds).

### P2 — Planner fidelity & telemetry

9. **Add evidence-driven beat-flow & structure scoring as *ranker* (not veto).** `pairTransitionScoring.js:151-208` weights mirror: beat .14, structure .10, tempo .10, harmonic .13, vocal .15, energy .06, spectral .05, stability .04, duration .02, dspRisk .03, flow .18. Add `beatFlow` (deficit/worst/lateDrop/wait/skip/sharedBeat) as new ranker among existing candidates. **Do not inherit Orchard’s `0.78/0.62/0.42` veto caps** — keep BitChord’s class/rung thresholds, calibrated by ears. **Effort:** M.

10. **Add stores evidence + dump replay.** Whole-track store SQLite keyed by any content hash (`measured::VERSION`), 32 MiB LRU cap, `ORCHARD_MIX_DUMP`-style `<cur>-<next>.json` dump (`prepare.rs:40-55`, `planner.rs:25-34`). **Impact:** identical analysis for both decks — no re-diagnosing “did we misread the key?” **Effort:** S. Could copy the *pattern* (not code) for BitChord `AnalysisStore` already exists.

11. **Recalibrate / soften Orchard’s three over-strict vetos into demotions (clean-room), tested with telemetry:** `style-contrast ≥0.9` → demotion to conservative (not `normal_boundary`); `harmonic severeClash <0.2` solo → `simple` (not veto) if `harmonic.conf < 0.65`; `needs-filter` `spectral <0.55` → conservative if `voiceCoverage < 0.5`. **Impact:** fewer automatic play-outs, but only after P0–P1 have taught BitChord how to musically survive those pairs. Gate behind `mix.plan` telemetry first; enable by thumbs after four-pair gate. **Effort:** M–L (needs calibrated dataset).

### P3 — Do not do yet

12. **NPU.** Don’t. Orchard itself backed off: 30 s A8W8 accuracy “not suitable for production” (`BEAT_MODEL_ACCURACY.md:28-30`), 6 s costs 5.5 pp downbeat. BitChord beats that with CPU INT8 and zero audio-thread allocs. Revisit only if BitChord adopts a 30 s *dynamic INT8 + 6-frame border* improvement and wants SD 7 Gen 1 baseline ≈262 ms on Android (`BEAT_MODEL.md:58-65`).

13. **Wholesale JS planner/gate import.** Don’t — license blocks + tuning unknown + BitChord scalars already encode learned preferences. Use the *structure* (gate table, size limits) as check-list for BitChord design reviews, not as numeric policy.

**Why these in this order?** P0 synth rescue pair families gives the biggest immediate win for clashing pairs without lowering gates; P1 fixes sonic defects BitChord can hear now; P2 improves measurement so P0–P1 can be tuned; P3 are proven dead-ends or blocked.

---

## Appendix A — Inventory

### Workspace (`/tmp/orchard-analysis`)

```
Cargo.lock, Cargo.toml (7 members, edition 2024, AGPL)
crates/earmark/                 4 587 lines DSP+analysis+planner+render (Shield)
crates/orchard-adaptive-mix/    ~2 400 lines worker/prepare/evidence/planner/lock/repeat (Shield)
crates/orchard-transition-core/ 269 + 150 lines SelectedPlan/curve (Shield)
crates/orchard-transition-planner/ 100 + 158 lines QuickJS bridge (Shield)
crates/orchard-transition-mobile/ 248 lines JNI MixNative (Shield)
crates/orchard-youtube-extractor/, orchard-core-ffi/, orchard-system-media/
core/js/src/audio/crossfade/ 19 JS files, 2,XXXX lines planner
core/js/shared/trackAnalysis.js
models/nirovi/ (fp16 15 MB + int8 7.9 MB), models/syvenu/ (ort struct), models/npu/qnn/ (7.7 MB + 709 KB add-on), models/beat-this/
mobile/android/npu-addon/ (qnn_runner.cpp, build.sh), mobile/android/app/src/main/cpp/npu/, /smart/npu/
third_party/…/ffmpeg n9.0.2, libwebp 1.6.0, libdatachannel, etc.
```

**Files read for this report** are all listed above via citations — tracked 2132 files, not a partial checkout. No `AGENTS.md` output but ignored. `.gitmodules` indicates submodules not fetched fully (ffmpeg vendor not fully fetched in this clone — no `--recurse-submodules` = 622 M clone; FFmpeg source checksum used in Windows packaging only).

### Reproducibility

- `git -C /tmp/orchard-analysis rev-parse HEAD` → `0662cb5`
- `git clone https://github.com/sfg5453/orchard /tmp/orchard-analysis`
- Orchard tests: `cargo test -p earmark`, `cargo test -p orchard-adaptive-mix`, `meson test -C build/dev`

---

## Appendix B — Threshold Quick-Reference

**Orchard Earmark (Rust)** — `config.rs`
```
tempo target Incoming, preferred_dev 0.04, acceptable 0.08, max 0.12, preserve_pitch true, allow_half_double true
timing min 1.5 max 16.0, allowed_beats [4,8,16,32], outgoing_search 45 incoming_search 45, outgoing_tail 0.25 incoming_head 0, max_anchors 16 max_candidates 1024
analysis fft 2048 hop 512, bass_crossover 200 mid 2000, transient_sigma 1.5, silence_floor -60, bars_per_phrase 4
loudness match true tolerant 1.0 max_gain 6.0 ceiling -0.5 lookahead 5 release 80
filters bass_swap 180 lowpass_min 400 lowpass_max 18000 highpass_min 20 Q 0.7071 step 32
scoring beat 1.0 phrase 1.2 tempo 1.5 spectral 0.8 loudness 0.6 energy 1.0 transient 0.7 low_freq 0.9 duration 0.5
strategy preferred_beats 16, short_fade_max 2.5, bass_swap_low_energy 0.3, filtered_blend_spectral 0.5
MAX_SELECTED_DEVIATION 0.04, MAX_RAMPED_DEVIATION 0.12, tempo_glide tolerance 2e-5
```

**Orchard JS product planner** — `pairTransitionGates.js` & `pairTempoHarmony.js`
```
MAX_STRETCH 0.04, MAX_RAMP 0.08 (6% clean, 11.8% forced)
minBeatmatchConf 0.55, trustedHarmonic 0.65
vocal active 0.6 activeFraction 0.3 minCoverage 0.5 sustainedBeats 4
confidence full 0.78 conservative 0.62 simple 0.42 trim 0.25
weights beat .14 structure .10 tempo .10 harmonic .13 vocal .15 energy .06 spectral .05 stability .04 duration .02 dspRisk .03 flow .18
MAX_ROLE 12 MAX_DETAILED 64 MAX_DISCARDED_AUDIBLE 12
ORDINARY_BEATS [4,8,16] (+32 long), MIN_BEATMATCH 8 beats 4s
STYLE_CLASH_CONTRAST 0.9, VOCAL_ACTIVE 0.6, beatless edge 4-12s skip 16s
```

**Muffle** — `muffle.js`
```
leadBeats 8 minLead 4 exitBeats 2 muted 0.75 maxEnergyDrop 5 levelSeconds 8 maxSkip 40 minOpening 2 minBody 0.3 swapVoice in 0.7 out 0.5
```

**BitChord vc68 for contrast** (from handovers)
```
echoFeedbackFor >12s 0.55 <=8s 0.45 half M10/CUT_ON_ONE
REVERB_DAMPING_EDM 0.48 REVERB_WET_EDM 0.42 comb 0.0302-0.0444
swap 1-beat 200Hz, FaderParams 9 points, outMid -6dB carve
bounded 4s filtered decline, key-clash blend via keyHead/keyTail
MixVerify ±3 LUFS 0.896 peak ±25ms exitGates 0, tuner [0.6,1.5]
```

---

*End of report. All claims source-cited. License section is not legal advice. BitChord v68 has no code from Orchard — this inspection preserved that invariant.*
