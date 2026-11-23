# BitChord Forensics — vc69 (`2.2.3-djmix27`) Screen Recording `1000078928.mp4`
Date: 2026-11-15. Method: same forensic approach as vc67 recordings. Read-only; no app code changed, no build, no install. `ffmpeg`/`ffprobe` were already present (`/usr/bin/ffprobe`, `/usr/bin/ffmpeg`, ffmpeg n9.0.2); no install was needed.

Input: `/tmp/opencode/uploads/03e17806-a8ca-4cb6-9b85-b56a4196fc56/1000078928.mp4` (copied to `1000078928_original.mp4` in evidence, SHA-256 `89825da25e7e732038cef039794ac230d56e8782c6715bead7c15133113825d5`).
Evidence dir: `/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/vc69-forensics/`.

## 1. File / stream facts

Command (as run):
```
ffprobe -v error -show_format -show_streams -of json /tmp/opencode/uploads/03e17806-a8ca-4cb6-9b85-b56a4196fc56/1000078928.mp4
ffprobe -hide_banner /tmp/opencode/uploads/03e17806-a8ca-4cb6-9b85-b56a4196fc56/1000078928.mp4
```

Measured (verbatim):
- Container: MOV/MP4 (`mp42`), duration **6.780227 s**, size **271678 B**, bitrate **320553 b/s**. Tags: `creation_time 2026-11-15T01:08:40.000000Z`, `com.android.version 16`.
- Video stream 0: `hevc` (H.265 Main, `hvc1`), **720×1280**, `yuv420p`, **30 fps (30 tbr)**, `nb_frames 203`, bitrate `188546 b/s`. `duration 6.766667 s`.
- Audio stream 1: `aac` (AAC LC, `mp4a.40.2`), **44100 Hz stereo** (`channel_layout stereo`), `fltp`, bitrate `128224 b/s`, `nb_frames 292`, `duration 6.780227 s`.

Audio extraction command (as run):
```
ffmpeg -y -v error -i "/tmp/opencode/uploads/03e17806-a8ca-4cb6-9b85-b56a4196fc56/1000078928.mp4" -vn -acodec pcm_s16le -ar 44100 "vc69_audio.wav"
```
Extracted: `vc69_audio.wav`, `pcm_s16le`, 44100 Hz, 2 ch, `299008` frames, duration `6.7802 s`, SHA-256 `da6889c978a5aa7c93c24cf41c9ddddaf586a57340fa78ec33ad7a5c23d46859`.

Audio is **present, not silent, not corrupt**. File is **usable but very short (6.78 s)** — it captures only the first ~6 s of a 14 s transition (see §2). It does not contain handback, bridge, or a full blend. Any claim requiring the end of the transition is NOT determinable from this file.

Global level (command `ffmpeg -i vc69_audio.wav -af volumedetect -f null /dev/null`):
`- mean_volume -19.8 dB, max_volume -5.0 dB, n_samples 598016`. Python check: global RMS `-19.8 dB`, max sample `0.563 (-5.0 dBFS)`.

Critical capture-chain facts (all measured):
- **Dual-mono**: L/R correlation `1.0000`, max |L−R| `0.0000`, RMS L `0.1022` = RMS R `0.1022` over the whole clip and per-second. AAC declares "stereo" and the UI claims "Stereo", but decoded PCM is bit-identical L/R. Stereo defects (width, double-bass imbalance) are NOT diagnosable; the mono collapse is itself unexplained.
- **15 kHz ceiling**: full spectrogram (`showspectrumpic=s=1600x600`) is black above ~15 kHz for the entire clip; energy is sustained only to ~15 kHz. Whether this is the Opus 153 kbps source cutoff, the 128 kbps screen-record AAC cutoff, or both cannot be separated from this file. Any ">15 kHz restoration" (e.g. exact 20 kHz) is NOT measurable here.
- Silence test (command `ffmpeg -i vc69_audio.wav -af silencedetect=noise=-50dB:d=0.05 -f null -`): **no silence intervals detected**. Quietest 20 ms window is `-32.3 dB` at `t=3.38 s` (musical tail, see §5). No dropout to silence.

## 2. What the UI shows (frame reads)

Frame commands:
```
ffmpeg -y -v error -i "<src>" -vf fps=1 "frames_1fps/f_%02d.png"
ffmpeg -y -v error -i "<src>" -vf fps=5 "frames_5fps/f_%03d.png"
ffmpeg -y -v error -i "<src>" -vf fps=10 "frames_10fps/f_%03d.png"
```
`fps=10` mapping: `f_001 → t=0.00 s`, `f_010 → t=0.90 s`, `f_015 → t=1.40 s`, `f_068 → t=6.70 s`. All frames show the same now-playing card: **`Stay 4 Good / CLOSE YOUR EYES`**, codec line **`Opus · 48.0 kHz · 153 kbps · Stereo`**, lyric line evolving (`Can you, baby, stay for good?` → `...with me?`), progress advancing monotonically.

Timeline of the AutoMix label (read directly from frames):
- `t=0.00–1.30 s`: `Echo · 8s → 7s · 80 BPM · locked` (progress `2:46 / -0:22` → `2:48 / -0:21`).
- `t=1.30–1.40 s`: label switches to `Filter · 14s · 80→130 · out — → in intro · locked` (first seen `f_015` at `t=1.40 s`; last `Echo` frame `f_014` at `t=1.30 s`, so switch window is `(1.30, 1.40]`).
- `t=1.40–6.70 s`: `Filter · 14s → 9s · 80→130 · out — → in intro · locked`, counting down 1 s per second without jump (`14s` at ~1.4 s, `13s` at ~2 s, `12s` at ~3 s, `11s` at ~4 s, `10s` at ~5 s, `9s` at ~6 s; progress `2:48 / -0:21` → `2:53 / -0:15`).

Interpretation: the clip starts **mid-Echo** (7–8 s remaining) and the Echo is **preempted/aborted at 7 s remaining** by a new 14 s Filter transition that runs to the end of the clip. Handback, bridge exit, and Filter completion are **never reached** (clip ends with 9 s still remaining). The "out — → in intro" wording and `80→130` indicate a tempo-morphing filter blend (outgoing track faded/filtered, incoming intro). The now-playing title does not change, so the incoming track identity is not visible.

## 3. Timestamped timeline table

| t (s) | UI state (frame) | Audio event (measured) | Verdict |
|---|---|---|---|
| 0.00 | `Echo 8s, 80 BPM`, `2:46/-0:22` (`f_001`) | Continuous music, AIR (4–15 kHz) bright (42.1 dB on 100 ms grid), centroid 3652 Hz | Baseline; no step |
| 0.00–0.65 | `Echo 8s` | Gradual darkening: AIR 42.1→29.1 dB, centroid 3652→2407 Hz over 0.65 s; RMS fluctuates −25.5…−19.1 dB (100 ms) | Musical breakdown or filter sweep ramp; **no** one-sample entry cliff (see §5.2) |
| 0.90 | `Echo 7s`, `2:47/-0:21` (`f_010`) | AIR 29.5 dB, centroid 2165 Hz; SUB peak 34.6 dB (musical bass) | No defect |
| 1.30–1.40 | **Echo→Filter switch window** (`f_014` Echo 7s → `f_015` Filter 14s) | RMS continuous, no >6 dB step, no click, no gap in this 100 ms window | Label switch itself is clean; planner preemption, not an acoustic defect |
| **1.485–1.55** | `Filter 14s (just started)`, `2:48/-0:21` | **AIR +19.2 dB in ~65 ms, 10–90% rise ~43 ms, RMS +2.2 dB only, centroid 1242→6001 Hz, vertical wall in spectrogram** (detail §5.1) | **DEFECT D1 — instantaneous filter-open (demuffle signature). UI claims slow blend start; audio snaps open in <100 ms.** |
| 1.56–1.60 | `Filter 14s/13s` | RMS rises to −14.0 dB (musical downbeat follows the opening by ~70–90 ms) | Music revealed by opening; ordering (AIR leads RMS) is the filter fingerprint |
| 2.10–2.44 | `Filter 13s/12s` | Tilt event: AIR +9.4 dB in 50 ms while RMS falls −20.7→−23.9 dB, VOK falls 20.6→11.8 dB (2.21–2.32 s) | Likely musical break (vocal stops, highs persist); not the D1 signature (see §5.4). Secondary watch item only |
| 3.20–3.60 | `Filter 12s/11s` | Broadband gap: RMS to −32.3 dB at 3.38 s then to −16.5 dB at 3.48 s; **all** bands fall/rise together (100 ms dRMS −11.1/+10.8 dB) | Musical dynamics, **not** a filter step (§5.4) |
| 4.40–4.75 | `Filter 11s/10s` | Hit: AIR 8.3→24.2 dB **and** VOK +~8 dB **and** RMS −18.7→−14.9 dB together at 4.56–4.57 s | Musical hit (all bands together), **not** a filter step (§5.4) |
| 4.90–5.40 | `Filter 10s` | SUB 38–42 dB for ~0.5 s (highest in clip) with normal RMS | Musical bass entry; **no** sustained double-bass (>1 s) (§5.4) |
| 5.90–6.10 | `Filter 10s/9s` | VOK +7.3 dB at 5.90 s, AIR +5.0 dB; RMS −19.4 dB | Musical phrase entry |
| 6.70 | `Filter 9s`, `2:53/-0:15` (`f_068`) | Music continues; clip ends mid-transition | Handback / bridge / Filter end **not captured** |

## 4. Supporting numbers (all from commands run)

Waveform/spectrogram commands:
```
ffmpeg -y -v error -i vc69_audio.wav -filter_complex "aformat=channel_layouts=mono,showwavespic=s=1400x300:colors=0x2a9df4" -frames:v 1 waveform_full.png
ffmpeg -y -v error -i vc69_audio.wav -filter_complex "showspectrumpic=s=1600x600" -frames:v 1 spectrogram_full.png
ffmpeg -y -v error -ss 1.3 -t 0.5 -i vc69_audio.wav -filter_complex "showspectrumpic=s=1600x600:legend=1" -frames:v 1 spec_zoom_1p3_1p8.png
ffmpeg -y -v error -ss 1.40 -t 0.25 -i vc69_audio.wav -filter_complex "showspectrumpic=s=1600x600:legend=1" -frames:v 1 spec_zoom_1p40_1p65.png
```
Artifacts: `waveform_full.png`, `waveform_large.png`, `wave_zoom_1p4_2p0.png`, `wave_zoom_2p5_3p5.png`, `spectrogram_full.png`, `spec_0_2.png` (0–2 s), `spec_2_3.png`, `spec_3_4.png`, `spec_zoom_1p3_1p8.png`, `spec_zoom_1p40_1p65.png`. Frames: `frames_1fps/f_01..f_07.png`, `frames_5fps/f_001..f_034.png`, `frames_10fps/f_001..f_068.png`.

100 ms RMS/peak grid (Python FFT/RMS, 67 windows; code in session log): largest adjacent steps are `3.40 s dRMS +10.8 dB`, `3.30 s −11.1 dB`, `2.70 s +9.4 dB`, `2.60 s −6.5 dB` — all broadband (see §5.4). The D1 event is **invisible** on the 100 ms RMS grid (`t=1.40 dRMS +0.7 dB`) precisely because it is a spectral opening with no level change; it requires band analysis to see.

100 ms band-energy grid (bands: SUB 20–120 Hz, LOW 120–250 Hz, LOMID 250–1 kHz, VOK 1–4 kHz, AIR 4–15 kHz; 200 ms Hann window, 100 ms hop). Key rows:
- `t=1.30: TOTAL 48.6, SUB 16.9, LOMID 44.4, VOK 31.0, AIR 21.9, CENT 2044 Hz`
- `t=1.40: TOTAL 49.4 (+0.7), AIR 37.9 (+16.0), VOK 38.7 (+7.7), CENT 5057 Hz (+3014 Hz)`
- `t=1.50: TOTAL 55.8 (+6.4), AIR 44.2 (+6.3), VOK 52.0 (+13.4)`
No other 100 ms AIR step exceeds ±16 dB in the clip except `4.50 s (+13.6 dB, with VOK +6.0 dB and RMS rise — musical hit)`.

## 5. Defects

### D1 — Instantaneous filter-open step at ~1.49 s (PRIMARY; matches known demuffle root cause for the LPF)

Timestamp: onset **`t≈1.485 s`**, peak **`t≈1.55 s`**, musical follow-up at **`t=1.56–1.58 s`**. UI at onset: `Filter 14s, 80→130, out — → in intro` (just started; `f_015` at 1.40 s). UI switch preceded the audio step by ~85–185 ms (switch `(1.30,1.40]`, onset `1.485`).

Measured signature (5 ms hop / 15 ms window, Python FFT):
- Pre-step floor (`1.40–1.48 s`): AIR `−1.9…+3.3 dB`, centroid `1078–1985 Hz`, RMS `−23.3…−22.4 dB`, SUB `15.4–17.0 dB` (flat).
- Rise (`1.485→1.55 s`): AIR `3.3→10.0→13.6→16.4→22.5 dB` = **+19.2 dB**; centroid `1954→2540→3903→4138→5598 Hz` (**+~3.6 kHz in-window; 10 ms grid: 1242→6001 Hz**); VOK `10.5→22.2 dB (+11.7 dB over 70 ms)`; RMS `−22.8→−20.6 dB (**only +2.2 dB during the opening**)`, then `−14.0 dB` at `1.58 s` when the downbeat lands.
- Steepest segment: `1.485 (3.3 dB) → 1.490 (10.0 dB)` = **+6.7 dB in one 5 ms hop**.
- 10–90% rise time: 10% level `5.2 dB` crossed ~`1.492 s`, 90% level `20.6 dB` crossed ~`1.535 s` → **~43 ms**. (10 ms grid corroborates: `1.48 s AIR 5.1 → 1.54 s 24.8 dB`.)
- Spectrum deltas (50 ms average spectra, pre `1.40–1.45 s` vs post `1.60–1.65 s`): **700 Hz +17.0 dB, 1 kHz +13.3 dB, 2 kHz +32.6 dB, 2.8 kHz +32.4 dB, 4 kHz +26.0 dB, 6 kHz +19.5 dB, 8 kHz +20.2 dB, 12 kHz +24.2 dB, 15 kHz +31.6 dB; 100 Hz −0.6 dB, 200 Hz −3.7 dB** (highs restored, lows unchanged).
- Spectrogram: near-vertical wall at `1.48–1.50 s` in `spec_zoom_1p3_1p8.png` and `spec_zoom_1p40_1p65.png` — above ~5 kHz, purple/dark (−75…−90 dBFS) left, red (−40…−60 dBFS) right, wall width ~20–30 ms (STFT-smear). Waveform zoom `wave_zoom_1p4_2p0.png` shows added high-frequency hash with no click spike and no low-frequency discontinuity.
- UI–audio mismatch: UI claims a 14 s Filter blend at its first second; audio completes a ~43 ms opening in that second. A 14 s low-pass sweep cannot produce a 43 ms +19 dB AIR step with flat RMS.

Root-cause mapping: **Consistent with the already-known demuffle step (teardown `filters.open()` gliding ~700 Hz→20 kHz in ~30 ms).** The observed ~43 ms is the measured value through a 128 kbps AAC leg (AAC frame ~23 ms) + 15–20 ms analysis windows; subtracting encoding/analysis smear leaves a source glide in the same order as the ~30 ms code diagnosis. **This clip therefore validates the LPF half of that diagnosis in order of magnitude; it does not quote corner frequencies as measured.** Exact "700 Hz" and "20 kHz" cannot be quoted from this recording: the pre-step spectrum has musical notches (500–1 kHz) that defeat a naive −20 dB-edge estimator (both pre/post returned 520 Hz), and the 15 kHz codec ceiling hides any 20 kHz endpoint. The correct statement is: muffle in the ~700 Hz–1 kHz region opening to wideband (≥12 kHz), step duration ~40 ms.

Bass/HPF half of the same diagnosis: **NOT validated / NOT observed.** SUB is flat (`17.0→17.1 dB` across the event); LOW 120–250 Hz shows no step; point spectra at 120 Hz `−1.4 dB`, 180 Hz `−3.6 dB` (post minus pre). If the teardown simultaneously glides the bass HPF 200→20 Hz, its low-end restoration is either absent, masked by musical content, or below this recording's sensitivity. Do not cite this clip as proof of the HPF component.

Alternative that must be ruled out and is ruled out: musical drop. A drop raises TOTAL, RMS, and all bands together (cf. `4.56 s`: AIR+VOK+RMS together). D1 raises AIR **first**, with RMS flat for ~60 ms and VOK peaking ~20 ms after AIR; AIR leads level. That ordering is a filter opening, not a drum/vocal entry.

### D2 — Echo preemption (context, not a separate acoustic defect)

UI switches `Echo 7s → Filter 14s` in `(1.30,1.40]`. No click (max sample-to-sample |diff| `0.321`; counts `>0.4: 0`), no gap, no level step at the switch itself. The acoustic consequence (D1) arrives ~85–185 ms later, consistent with teardown latency, not with the label change alone. Whether the planner should abort a 7 s Echo for a new Filter is a product/geometry decision, not an acoustic defect measurable here. Report it as the context that fires D1 (Echo teardown on preempt is the most likely trigger for the `filters.open()` glide), but do not file it as an independent listening defect. Code owner should confirm trigger: Echo-teardown vs Filter-entry filter vs Filter-teardown.

### Negative results (all measured, not assumed)

- **No entry-side cliff captured.** Clip-start fine analysis (`0.00–0.50 s`, 10 ms hop): AIR `5.0 dB` at `0.00 s` → `13–25 dB` musical hits → gradual fall to `~10 dB` by `0.35 s`. The 0–1.40 s darkening is gradual over >1 s (centroid `3652→2044 Hz`), not a one-sample `20 kHz→2800 Hz` step. The native-muffle entry therefore predates the clip or used a different envelope. **This recording neither validates nor contradicts the entry-side diagnosis.**
- **No beatless-bridge exit snap.** No bridge phase is present (UI is Echo/Filter throughout). **Not determinable.**
- **No clicks.** P50 |diff| `0.0120`, p99 `0.0898`, p99.9 `0.1506`, max `0.3210`; `>0.4: 0`, `>0.5: 0`, `>0.6: 0` (Python `numpy.diff` on mono mix). No single-sample spikes at any boundary. Waveform zooms confirm continuity.
- **No dropouts / no silence.** Quietest 20 ms: `3.38 s −32.3 dB`; next `3.34 s −31.7 dB` — still −32 dB musical tail (LOMID `12.9 dB`, LOW `14.3 dB` at 3.38 s), not digital silence. All other windows ≥ −29 dB. Pre/post-muffle `−23 dB` floor is muffled music, not a gap.
- **No sustained double-bass.** SUB highs (`4.90–5.40 s`: `38.7, 42.6, 38.5, 38.1, 40.6, 40.6 dB`) last ~0.5 s with normal RMS, consistent with a musical bass entry. No >1 s dual-deck low-end sustain beyond music.
- **No tempo morph measured.** Envelope autocorrelation (20 ms RMS envelope): whole-clip best lag `0.74 s (81 BPM)`, first half `0.74 s (81 BPM)`, second half `0.74 s (81 BPM)`. The `130 BPM` lag (`0.46 s`) is absent (whole-clip `0.50 s` correlation is negative). Either the morph has not started affecting the audible mix in the first 6 s, or the 130 BPM deck is masked. **UI `80→130` is not acoustically proven in this clip; no tempo defect can be alleged from it.**
- **No pitch bend evidenced.** Harmonic stacks in `spec_0_2.png` / `spectrogram_full.png` (1–3 kHz wavy lines) show no coherent ramp. Measurement is limited (see §6); state only "no bend observed".
- **No echo-tail truncation signature.** Echo is aborted, but truncation would be a high-energy drop; the only sharp high spectral event is a rise (D1). No tail-cut artifact is measurable; also note the clip is dual-mono mix, so reverb/echo tail stereo cannot be isolated.
- **No countdown jump / no skipped transition.** Label decrements 1 s per second; progress `2:46→2:53` advances 7 s over 6.78 s clip (≈1×), remaining `−0:22→−0:15`. No freeze, no jump. Transition is incomplete, not skipped.

Candidates examined and classified as musical (for completeness):
- `2.21–2.32 s` tilt (AIR `17.7→27.1 dB` in 50 ms while VOK `20.6→11.8 dB`, RMS `−20.7→−23.9 dB`): spectral tilt with falling level — musical break, not D1 (D1 has rising/flat level with AIR leading).
- `3.30/3.40 s` broadband swing (`dRMS −11.1/+10.8 dB`, SUB `+9.4/+6.2 dB`, LOMID `−9.4/+14.2 dB` together): all bands together = phrase gap and re-entry.
- `4.56 s` hit (AIR `8.3→18.5→24.2 dB` + VOK + RMS together in 20 ms): transient hit, all bands together.

## 6. What is NOT determinable from this recording

1. **Exact filter corners and exact source glide time.** 128 kbps AAC (~23 ms frames) + 15–20 ms analysis windows smear a ~30 ms source glide to the observed ~43 ms. Cannot distinguish 30 ms vs 60 ms source, cannot quote "700 Hz" / "20 kHz" / "2800 Hz" / "1500 Hz" as measured values. Only the step nature, band pattern, and ~40 ms scale are proven.
2. **Bass HPF 200→20 Hz glide.** SUB/LOW are flat across D1. If the code glides HPF with LPF, this recording's music + codec hide it. Do not cite this clip for the HPF half.
3. **Entry cliff, bridge exit, Filter completion, handback.** Clip is 6.78 s, ends at 9 s remaining. No muffle entry, no bridge, no handoff is inside the file.
4. **Trigger identity for D1.** Echo-teardown-on-preempt, Filter-entry filter, or Filter-teardown produce the same acoustic signature in a dual-mono mix. Telemetry (`mix.fire`, envelope) is required; this file cannot name the trigger.
5. **Stereo / width / stereo-echo defects.** Dual-mono (`corr 1.0000`, `maxdiff 0.0000`) vs UI "Stereo". No stereo image is present to diagnose. The mono collapse is unexplained — component under test (BitChord renders mono? source is mono? screen-record downmix?) is NOT determinable from the file.
6. **Capture method (internal vs phone-mic).** High SNR, no room-noise floor (quietest −32 dB is music tail), and clean hash suggest internal capture or a quiet-room close mic, but both can look this clean. Mic vs internal is NOT provable from the file; room-noise diagnosis is therefore moot. State it as an assumption, not a fact.
7. **Tempo/pitch quality beyond "stable ~81 BPM, no bend observed".** Short, mono, mixed-deck clip with no per-deck stems cannot prove or disprove beatmix quality, chipmunk, or stretch artifacts.
8. **Build attribution.** The user attributes this to `2.2.3-djmix27` (vc69). The file itself contains no version string. Treat attribution as user-supplied, not forensically proven.

## 7. Usability statement

**NOT silent, NOT corrupt, NOT unusable. Usable with partial coverage.** Duration 6.78 s is the limitation: it proves D1 (a ~43 ms, +19 dB AIR opening ~0.1 s after the Echo→Filter switch) and nothing about the remaining 9 s of the transition. Any vc70 decision needing handback, entry, bridge, HPF, stereo, or exact-corner data needs a longer internal-audio capture covering the full 14 s plus telemetry.

## 8. Commands log (verbatim, for reproduction)

```
ffprobe -v error -show_format -show_streams -of json /tmp/opencode/uploads/03e17806-a8ca-4cb6-9b85-b56a4196fc56/1000078928.mp4
ffmpeg -y -v error -i "/tmp/opencode/uploads/03e17806-a8ca-4cb6-9b85-b56a4196fc56/1000078928.mp4" -vn -acodec pcm_s16le -ar 44100 "vc69_audio.wav"
ffmpeg -i vc69_audio.wav -af volumedetect -f null /dev/null
ffmpeg -i vc69_audio.wav -af silencedetect=noise=-50dB:d=0.05 -f null -
ffmpeg -y -v error -i "<src>" -vf fps=1 "frames_1fps/f_%02d.png"
ffmpeg -y -v error -i "<src>" -vf fps=5 "frames_5fps/f_%03d.png"
ffmpeg -y -v error -i "<src>" -vf fps=10 "frames_10fps/f_%03d.png"
ffmpeg -y -v error -i vc69_audio.wav -filter_complex "aformat=channel_layouts=mono,showwavespic=s=1400x300:colors=0x2a9df4" -frames:v 1 waveform_full.png
ffmpeg -y -v error -i vc69_audio.wav -filter_complex "showspectrumpic=s=1600x600" -frames:v 1 spectrogram_full.png
ffmpeg -y -v error -ss 1.3 -t 0.5 -i vc69_audio.wav -filter_complex "showspectrumpic=s=1600x600:legend=1" -frames:v 1 spec_zoom_1p3_1p8.png
ffmpeg -y -v error -ss 1.40 -t 0.25 -i vc69_audio.wav -filter_complex "showspectrumpic=s=1600x600:legend=1" -frames:v 1 spec_zoom_1p40_1p65.png
# Python (wave + numpy) for: 100 ms RMS table, 100 ms band-energy table, 10 ms/5 ms fine hops around 1.0–2.2 s / 2.1–2.45 s / 3.2–3.65 s / 4.4–4.75 s / 1.40–1.65 s, 50 ms pre/post spectra, L/R correlation, click |diff| stats, 20 ms minima, envelope-autocorrelation tempo.
```
All numbers above are outputs of these commands; no timestamps or dB values were estimated by eye.

*End of report.*
