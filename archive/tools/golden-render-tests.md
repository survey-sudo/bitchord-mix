# Golden Render Tests — PLAN §8.1 (deterministic, offline, 8 probes)

Source: `work/automix-readiness-repair-2026-11-18/PLAN.md` §8.1 (lines 768–779) + §9 cheat-sheet.
Base synthesis (all probes): A = 120 BPM clicks + 110 Hz bass, B = 126 BPM clicks + 440 Hz pad, 48 kHz.
Harness host: `dvb` /home/boxes/bitchord-mix (x86_64). Gate: every DSP-touching PR must run this suite green before merge.
Status: **spec pending native run** — no native binaries (`loop_mix_renderer_ab_main`, `vocal_spectrogram_test`) are present in the local `/home/hfzhkn/Bitchord` workspace (only `work/vc75-prep/vocal_spectrogram_test.cpp` source was found); all 8 probes below are defined and await a `dvb`/`cue` execution. Do not mark any probe passed without pasted native output.

General determinism rule (PLAN §8.3): fixed input bytes + manifest → byte-identical output. Run before/after any planner patch.

---

## G1 — Zero beat drift (< 2.0 ms, ends 1.0000×)

- **Synthesize:** A 120 BPM impulse clicks + 110 Hz bass, B 126 BPM impulse clicks + 440 Hz pad, both 32+ bars, 48 kHz.
- **Command:**
  ```bash
  # on dvb (repo root /home/boxes/bitchord-mix)
  ./loop_mix_renderer_ab_main --golden-bpm-drift --a 120 --b 126 --rungs LOOP_MIX,OUTRO_LOOP_DROP,BEAT_BLEND
  # fallback if that binary has no golden subcommand yet: --selftest with golden fixtures dir
  ```
- **Assertion:** cross-correlate impulse peaks every bar for each rung (`LOOP_MIX`, `OUTRO_LOOP_DROP`, `BEAT_BLEND`). Compute per-bar peak offset.
- **Threshold:** every pair `≤ 2.0 ms` (96 samples @ 48 kHz); post-handover B rate == `1.0000×` (exactly, no post-mix `beginEase` lurch). Any bar > 2 ms = FAIL.

## G2 — Zero loop-seam clicks (> 35 dB suppression)

- **Synthesize:** 220 Hz sine, looped with an 8 ms raised-cosine seam (C1 envelope) vs hard wrap (no crossfade).
- **Command:**
  ```bash
  ./loop_mix_renderer_ab_main --golden-seam-click --freq 220 --seam-ms 8
  # companion LP check: --lp-bypass-probe must still read ~19.8 kHz, maxStep ≤ 0.01
  ```
- **Assertion:** `max |x[n] − 2·x[n−1] + x[n−2]|` across every wrap boundary; compare smoothed-seam peak vs uncrossfaded-wrap peak.
- **Threshold:** suppression `> 35 dB` vs uncrossfaded wrap. No wrap discontinuity above the −35 dB click floor.

## G3 — Bass-swap & ceiling (LUFS Δ < 1.5, peak ≤ −0.95 dBFS)

- **Synthesize:** A and B both at 0 dBFS master (worst-case hot masters), A 110 Hz bass-heavy, B 440 Hz pad, beat-matched blend 8 bars.
- **Command:**
  ```bash
  ./loop_mix_renderer_ab_main --golden-bass-ceiling --lufs --true-peak
  # MixVerify must receive real LUFS/peak (no zeros)
  ```
- **Assertion:** measure low-band (`<180 Hz`) RMS during the bass-swap window; integrated/regional LUFS across handoff; oversampled true-peak of summed master.
- **Threshold:** low-band RMS never `> max(lowA, lowB) + 0.8 dB`; regional LUFS jump `< 1.5 LU` (p95); master peak `≤ −0.95 dBFS`, `0` clipped samples. (PLAN §9 phrases the same gate as ≤ −1.0 dBTP; use whichever the renderer reports, fail-closed at the stricter of the two.)
- **Note:** kicked/bass doubling > +0.8 dB here also covers the PLAN §2.1 flam (30 ms-apart grids) check.

## G4 — Echo survival past EOF (+0.5 / +1.5 / +2.5 s)

- **Synthesize:** A truncated exactly at `transitionEnd`; `ECHO_HANDOFF` triggered so the dry side ends at EOF while echo tail continues over B.
- **Command:**
  ```bash
  ./loop_mix_renderer_ab_main --golden-echo-survival --echo-handoff
  ```
- **Assertion:** measure echo-band energy (decaying delay tail) at sample-accurate offsets after A EOF while B is audible. Also required as the FxPolicy D6 gate: echo tail survival at +0.5/+1.5/+2.5 s past A EOF.
- **Threshold:** tail energy monotonically decaying and still present at `+0.5 s`, `+1.5 s`, `+2.5 s` (non-silent, decaying); dry A is silent immediately past EOF (see G8).

## G5 — Injected-vocal LRC clash (0 dual-sing frames)

- **Synthesize:** B: bars 0–2 clean (`p_vocal = 0.05`), bar 3+ sung (`p_vocal = 0.88`). Loose LRC metadata claims B is sung `0–15 s` (wrong vs stem). A is clean through the overlap.
- **Command:**
  ```bash
  ./loop_mix_renderer_ab_main --golden-vocal-clash --lrc-loose --expect OUTRO_LOOP_DROP_or_MICRO_LOOP
  # + vocal_spectrogram_test on the same fixture
  ```
- **Assertion:** chosen rung; per-frame vocal co-occurrence `p_vocal_out > 0.30 && p_vocal_in > 0.30` at `> −6 dB`.
- **Threshold:** rung is `OUTRO_LOOP_DROP` (or ≤ 2-bar micro-loop / echo) — **never** a 2-bar `DJ_FILTER` collision. **0 dual-sing frames** (at > −6 dB). Any dual-sing frame = FAIL. Lyric-certain overlap stays an absolute veto even in the gated stem-duck pass.

## G6 — Silence trim (8 s dead tail)

- **Synthesize:** A with a trailing 8 s dead-silence tail after music end; `musicEnd` / `audibleStart` provided as analysis hints.
- **Command:**
  ```bash
  ./loop_mix_renderer_ab_main --golden-silence-trim --tail-sec 8
  ```
- **Assertion:** transition is planned/triggered from `musicEnd`, not from container duration; no 8 s wedge of silence before the incoming downbeat.
- **Threshold:** transition offset == `musicEnd` (hints honored); dead tail produces no delay and no audible silence gap. (Apple-parity behavior: trim silence.)

## G7 — Gapless same-album (0 ms crossfade)

- **Synthesize:** two same-album consecutive tracks (contiguous `sameAlbum=true`, `gapless` metadata), joined at the album seam.
- **Command:**
  ```bash
  ./loop_mix_renderer_ab_main --golden-gapless --same-album
  ```
- **Assertion:** plan rung / crossfade duration at the seam.
- **Threshold:** `0 ms` crossfade — `GAPLESS` rung, sample-contiguous splice, no fade, no gap, no double-beat at the album boundary.

## G8 — Echo dry-cut correctness (regression test)

- **Synthesize:** same fixture as G4; additionally expose the echo decision point vs the actual dry cut position.
- **Command:**
  ```bash
  ./loop_mix_renderer_ab_main --golden-echo-dry-cut
  # regression from the echo council: native Echo must check ACTUAL dry cut, not setup position
  ```
- **Assertion:** the Echo rung's cut check reads the real decoded dry-cut sample position (`decodedOutTo`), not the planned setup position; plan-time clamp (`outDuration vs effectiveEnd`) and the `coversHandoffPcm` end check agree within tolerance.
- **Threshold:** decision matches actual dry cut for all fixtures (including Opus/AAC 6–60 ms container-overshoot cases). A decision keyed to setup position while the dry cut moved = FAIL.

---

## Native run log (fill on dvb — paste real output, never hand-write)

```
date:
host:
binary: loop_mix_renderer_ab_main (commit):
invocation: --selftest / --golden-*
G1 drift: PASS/FAIL (max ms = ___, post-handover rate = ___)
G2 seam:  PASS/FAIL (suppression dB = ___)
G3 bass/ceiling: PASS/FAIL (ΔRMS dB = ___, ΔLUFS = ___, peak = ___)
G4 echo survival: PASS/FAIL (+0.5s = ___, +1.5s = ___, +2.5s = ___)
G5 vocal clash: PASS/FAIL (rung = ___, dual-sing frames = ___)
G6 silence trim: PASS/FAIL (offset = ___)
G7 gapless: PASS/FAIL (xfade ms = ___)
G8 dry-cut: PASS/FAIL (setup vs actual = ___)
lp-bypass-probe: ___ kHz, maxStep ___
loop-domain-probe: ___ pairs, minRMS ___
vocal_spectrogram_test: ___
```

Until the block above is pasted from a real `dvb` run, treat G1–G8 as **specified, not executed**.
