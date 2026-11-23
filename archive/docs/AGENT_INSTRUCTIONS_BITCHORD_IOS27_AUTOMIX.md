# AGENT EXECUTION PLAYBOOK: BitChord (`djmix4`) Beat-Matching Fix & iOS 27 Apple Music AutoMix Parity

> **Target Agent Role:** Autonomous Android / Native C++ / Audio DSP Coding & Reverse-Engineering Agent  
> **Execution Environment:** Remote **InstaBox SSH Sandbox** (`instabox.sh` Linux VM + `instabox-android` Android 14 `x86_64` container) — **NEVER build or install toolchains on the user's local PC.**  
> **Primary Baseline Artifact:** `BitChord-djmix4.apk` (`https://drive.google.com/file/d/1opqdJulQo-BrKEsxBaB3mvPsjGtxoJE_/view?usp=sharing`, package `com.music.bitchord.loopfix`)  
> **Upstream Open-Source Reference:** `https://github.com/kushagrasinghx/BitChord` (GPL-3.0-or-later, v1.9.1+ architecture)  
> **Mandatory RE Tooling:** `https://github.com/morluto/rea` (`rea` CLI for structured APK + JNI `.so` Evidence JSON & diffing)

---

## 0. Mission & Hard Guardrails

You are tasked with reverse-engineering `BitChord-djmix4.apk` (`com.music.bitchord.loopfix`), diffing every Kotlin and C++ modification it made against upstream `kushagrasinghx/BitChord`, fixing all causes of **improper / unnatural beat-matching**, and upgrading the entire Automix pipeline to **iOS 27 Apple Music AutoMix parity**.

### Non-Negotiable Constraints
1. **Remote Sandbox Execution Only:** Every download, decompilation (`rea`, `jadx`, `ghidra`/`rizin`), Rust/C++ NDK build, Gradle build, unit test, offline render test, and Android 14 emulator run MUST execute inside the **InstaBox SSH host** provided by the user's InstaBox SSH command/code.
2. **Dual-ABI Mandatory (`arm64-v8a` + `x86_64`):**
   - InstaBox's Android 14 container (`instabox-android` at `172.20.0.10:5555`) is **`x86_64`-only** with **no ARM translation**.
   - The user's physical Android phone requires **`arm64-v8a`**.
   - Configure Gradle/CMake (`abiFilters += listOf("arm64-v8a", "x86_64")`) so all native libraries (`libloopmix.so` / `libautomix_dsp.so`, `libaudio_analysis.so`, `onnxruntime`) build for **both** ABIs (or produce a universal APK + split `arm64-v8a` release APK).
3. **Strict License & Patent Hygiene (GPL-3.0-or-later Compatible Only):**
   - **ALLOWED code & algorithm sources:**
     - `kushagrasinghx/BitChord` (GPL-3.0-or-later)
     - `thieggs/bkmasterplayer` (MIT — `engine/analysis.rs`, `engine/automix.rs`, `engine/mixer.rs`)
     - `VertexSelection/automix-engine` (LGPL-2.1 — `cue_analysis.py`, `stem_transition.py` regional LUFS & vocal-safe cue concepts)
     - `Clash-Projects/LastWave-Native`, `cosmictaserdev-creator/Convx`, `congnghetinhtu/AutoMixSuite`, `JohanDevl/opendj` (all GPL-3.0)
     - `Signalsmith/signalsmith-stretch` (MIT, already vendored in BitChord `native/loopmix`)
     - Beat This! ONNX (`CPJKU/beat_this`, MIT) & Open-Unmix (`sigsep/open-unmix-pytorch`, MIT — **NOT** `umxl` CC-BY-NC-SA-4.0)
   - **FORBIDDEN sources:**
     - **DO NOT** copy code verbatim from current `SFG5453/Orchard` `crates/orchard-adaptive-mix` (`PolyForm Shield License 1.0.0` — non-free/non-GPL). Older AGPL-3.0 Orchard-derived code already inside `kushagrasinghx/BitChord` may be kept/modified under GPL-3.0+.
     - **DO NOT** bundle `madmom` (CC-BY-NC-SA), `umxl` weights (CC-BY-NC-SA), or `SamAfshar2011/AI_DJ` (all-rights-reserved).
   - **Spotify Patent US 8,280,539 Avoidance (active until 2031-03-11):**
     - Select exit candidates on Track A solely from Track A's internal structure, and entry candidates on Track B solely from Track B's internal structure.
     - Score `(exit_A, entry_B)` pairs using **scalar summary metrics only** (BPM ratio, phase offset at anchor, Camelot key distance, scalar vocal-activity share, scalar regional LUFS). **NEVER** compute sliding-window cross-track frame-by-frame descriptor sequence correlation across the overlap.
4. **Preserve & Upgrade the "Loop Mix First" Invariant:**
   - Clean instrumental loop transitions (`LoopMix.plan`) remain Tier 1 whenever genuinely instrumental and rhythmically coherent, **but** fix the two `djmix4` failure modes observed in production telemetry:
     - **False-positive vocal loops (`clear by lyrics` alone):** Never trust line-synced lyrics *without* checking the `VocalTracker` acoustic mask (`Check.LYRICS_AND_MASK` required when vocal mask is available; lyrics are a veto, never a sole voucher!).
     - **False-negative loop starvation (`53 sung (lyrics), 0 sung (mask)` → 2-bar filter chop):** Stop rejecting clean 1–2 bar instrumental pickups or inter-line instrumental turnarounds merely because coarse LRC line padding covered them, and **never** collapse a rejected loop pair into a rushed 2-bar `ExoPlayer` filter chop when beat grids are valid.

---

## Phase 1: InstaBox SSH Provisioning & Toolchain Bootstrap

1. **Connect & Verify InstaBox Resources:**
   - Connect via the user-provided `instabox` SSH command/host. Do **not** run heavy jobs on the local caller machine.
   - Inspect CPU, RAM, disk, and Android container reachability:
     ```bash
     uname -a && nproc && free -h && df -h /workspace /tmp
     adb connect 172.20.0.10:5555 || true
     adb devices -l
     ```
2. **Install Required System & RE Dependencies on InstaBox:**
   - Ensure `procps` (`ps` binary — **mandatory** on Linux or `jadx` headless hangs/fails inside `rea`), JDK 21, Android SDK (`platforms;android-35`, `build-tools;35.0.0`, `ndk;27.2.12479018`, `cmake;3.22.1`), Python 3 (`gdown`, `numpy`, `scipy`, `soundfile`, `librosa` for objective offline transition verification), `apktool`, `jadx`, `rizin` (or headless Ghidra), and `morluto/rea` are installed under `/workspace/tools`:
     ```bash
     sudo apt-get update && sudo apt-get install -y \
       procps git curl wget unzip zip jq python3 python3-pip python3-venv \
       openjdk-21-jdk ninja-build cmake build-essential pkg-config ffmpeg
     which ps && ps -ef | head -n 5
     ```
   - Install **morluto/rea**:
     ```bash
     git clone --depth 1 https://github.com/morluto/rea.git /workspace/tools/rea
     # Build/install rea CLI per repo instructions (Node/Rust/Python entrypoint) and verify:
     rea --help
     ```
   - Install `jadx` (latest release >= 1.5.1) and `rizin` / Ghidra headless so `rea` has full Android + native `.so` disassembly backends on `PATH`.
3. **Transfer Reference Assets (Optional / If Needed from Caller):**
   - If the caller environment provides `/home/hfzhkn/Downloads/automix-research.md`, `/home/hfzhkn/Downloads/Apple Music AutoMix Technical Wiki.md`, or `/home/hfzhkn/bkmasterplayer`, sync only text/code references or clone `https://github.com/thieggs/bkmasterplayer.git` and `https://github.com/VertexSelection/automix-engine.git` directly inside `/workspace/refs/` on InstaBox.

---

## Phase 2: Acquire `BitChord-djmix4.apk` & Run Structured `rea` Differential Audit

1. **Download `BitChord-djmix4.apk` & Clone Upstream `BitChord`:**
   ```bash
   mkdir -p /workspace/audit /workspace/src
   python3 -m pip install --break-system-packages gdown
   gdown "https://drive.google.com/file/d/1opqdJulQo-BrKEsxBaB3mvPsjGtxoJE_/view?usp=sharing" \
     -O /workspace/audit/BitChord-djmix4.apk --fuzzy
   sha256sum /workspace/audit/BitChord-djmix4.apk
   git clone https://github.com/kushagrasinghx/BitChord.git /workspace/src/BitChord
   ```
2. **Unpack & Inspect `BitChord-djmix4.apk` Structure:**
   - Extract APK contents to `/workspace/audit/djmix4_unpacked` (`unzip -q` + `apktool d`).
   - Enumerate `lib/*/*.so` (`libloopmix.so`, `libaudio_analysis.so`, `libonnxruntime.so`, etc.), `assets/*.onnx`, `AndroidManifest.xml` (`package="com.music.bitchord.loopfix"`), and DEX count.
3. **Run `morluto/rea` Evidence Generation & Scoped Inspection (CRITICAL WORKFLOW):**
   - **Rule:** Large `rea` Evidence JSON files exceed LLM context limits if dumped raw to stdout. Always write Evidence JSON to disk and query via `rea inspect-analysis-view` with `"kind": "evidence-file"`.
   - Generate Android APK evidence and native `.so` evidence:
     ```bash
     rea analyze-apk /workspace/audit/BitChord-djmix4.apk \
       --output /workspace/audit/djmix4.apk.evidence.json

     rea analyze-binary /workspace/audit/djmix4_unpacked/lib/arm64-v8a/libloopmix.so \
       --output /workspace/audit/libloopmix.so.evidence.json

     rea analyze-binary /workspace/audit/djmix4_unpacked/lib/arm64-v8a/libaudio_analysis.so \
       --output /workspace/audit/libaudio_analysis.so.evidence.json
     ```
   - Query `rea inspect-analysis-view` scoped specifically to the Automix / LoopMix packages and JNI symbols:
     - Kotlin packages: `com.music.bitchord.playback`, `com.music.bitchord.playback.smart`, `com.music.bitchord.playback.loopmix`
     - Classes to decompile & diff line-by-line against `/workspace/src/BitChord`:
       1. `CrossfadeController.kt`
       2. `TransitionPlanner.kt` & `TransitionPolicy.kt`
       3. `LoopMix.kt`, `LoopMixSession.kt`, `LoopMixPort.kt`, `LoopMixRenderer.kt`
       4. `TrackAnalyzer.kt`, `BeatTracker.kt`, `VocalTracker.kt`, `TrackFeatures.kt`
       5. `TransitionFilterProcessor.kt` & custom Media3 `AudioSink` / `AudioProcessor` hooks
     - Native C++ symbols in `libloopmix.so` & `libaudio_analysis.so`:
       - `bitchord::loopmix::Renderer::*`, `Deck::*`, `Biquad::*`, `Envelope::*`
       - `tempo_analysis.cpp` / `audio_analysis.cpp`
4. **Produce `/workspace/audit/DJMIX4_DIFF_REPORT.md` Before Writing Fixes:**
   - Reconstruct and port every positive change from `BitChord-djmix4.apk` (such as package rename `com.music.bitchord.loopfix` if desired for side-by-side install, UI mix debug strings, custom `LoopMixPort` sink hooks, or `djmix4` experiments) onto `/workspace/src/BitChord`, while documenting every regression `djmix4` introduced or left unfixed from upstream.

---

## Phase 3: Exact Root-Cause Matrix To Eliminate (Verified in Upstream & `djmix4` Telemetry)

Your engineering changes MUST directly resolve all 7 verified defects below:

| # | Observed Symptom / Telemetry | Exact Code Root Cause | Mandatory Architectural Fix |
|---|---|---|---|
| **RC-1** | Non-loop transitions flam, gallop off-beat for ~0.85 s, or drift apart across the overlap | `CrossfadeController.kt` runs `LoopMix` on a single sample-exact `LoopMixRenderer` clock, **but** runs `DJ_BLEND` / `DJ_FILTER` on **two separate `ExoPlayer` instances** (`ARM_STEP_MS = 40 ms` polling + Opus sync-sample seek jitter + `AudioTrack` startup latency + `holdPhase` ±3% speed nudges delayed by `LOCK_START_MS = 300 ms` + `5` samples + `400 ms` settle) | **Unify ALL Automix transitions (`LOOP_MIX`, `PHRASE_EXTENSION`, `BEAT_BLEND`, `VOCAL_HANDOFF`, `ECHO_OUT`) onto the native single-clock `SignalsmithStretch` renderer (`AutomixRenderer`, evolved from `LoopMixRenderer`)!** Splice out of outgoing `ExoPlayer` via `OutArm` and hand over to incoming `ExoPlayer` at `rate = 1.0` with `≤ 1.5 ms` frame-shift alignment (`InArm`). Keep dual-`ExoPlayer` ONLY as an emergency fallback for DRM/video/unsupported sinks. |
| **RC-2** | Chipmunk / slurred vocals and unnatural post-transition tempo lurch | `TransitionPolicy.kt` allows `MAX_INCOMING_RATE = 1.25` (+25%!) and `MIN_INCOMING_RATE = 0.85` (-15%!) one-sided on `ExoPlayer`'s time-domain WSOLA (`SonicAudioProcessor`), then `beginEase` walks speed back after handoff | Cap static stretch at **±6% vocal / ±8% instrumental**; use **continuous knot-based tempo bridge** inside `SignalsmithStretch` (`bpmOut` at `t=0` → `bpmIn` at `t=rampEnd`) so outgoing & incoming share the exact instantaneous beat phase AND the incoming track reaches **`1.000x` native speed before handover** (zero post-mix `beginEase` lurch!). Support `2:1` / `1:2` half/double-time folding (`[0.5, 1.0, 2.0]`). |
| **RC-3** | Beat grid sits off the actual kick transient; loop wraps click/stumble; 22s window seams flip downbeat phase | `TrackAnalyzer.seamBeats` (`TrackAnalyzer.kt:8510`) concatenates 22s Beat This! windows at midpoints without phase reconciliation or onset snapping | Port `bkmasterplayer` (`engine/analysis.rs`) post-processing into Kotlin/C++: (1) **Spectral-flux onset snapping** (`snap_to_onsets`, ±35 ms search to lock grid to real kick/snare attacks), (2) **Phase coherence** (`phase_coherence >= 0.42`) to reject hallucinated grids on ambient intros/outros, (3) **Multi-region seam reconciliation** (`join_regions` — reconcile 4/4 bar phase across overlapping windows; keep distinct `TempoRegion`s if song genuinely changes tempo >1.5%), and (4) **6–12 ms equal-power micro-crossfade at loop wrap seams** inside `Deck::sampleAt`. |
| **RC-4** | `loop mix ... clear by lyrics` loops a sung vocal hook! (Screenshot 3) | `CrossfadeController.askLyrics` + `LoopMix.kt` assumes "a loop lyrics vouch for needs no vocal mask" (`Check.LYRICS`). LRC timestamps miss intro ad-libs, vocal chops, and outro melismas or drift by ±500 ms | **Lyrics are a VETO, never a sole pass!** Require `VocalTracker` mask clearance (`p_vocal < 0.22` max, `< 0.14` mean across every bar of the loop) whenever `VocalSpectrogram` is available (`Check.LYRICS_AND_MASK` or `Check.MASK`), using synced lyrics only as an *additional* conservative veto (with tight word-end bounds, not 2-second blanket line padding). |
| **RC-5** | `filter mix in 5 s · 2 bars · no loop: incoming: 65 loops: 12 quiet, 53 sung (lyrics), 0 sung (mask)` (Screenshot 2) | Coarse LRC line spans mark all 53 incoming bars as `sung (lyrics)` even though `0 sung (mask)`! And when `LoopMix` returns `null`, planner collapses to a rushed 2-bar `DJ_FILTER` | (1) Fix `LoopMix.sungSpans`: do not bridge lyric gaps `>= 1.5 s`, and allow **1-bar / 2-bar micro-loops** on instrumental pickups verified by `VocalTracker` (`0 sung (mask)`) when lyrics have not acoustically started. (2) Add **Asymmetric Outro Loop + Vocal Pickup Drop** (loop outgoing instrumental 2–4 bars while ramping tempo to `bpmIn`, then drop incoming unlooped right on its pickup/downbeat!). (3) Size beatmatched `BEAT_BLEND` / `VOCAL_HANDOFF` at **4–8 bars (8–16 s)**, never 2 bars unless content remaining `< 6 s`. |
| **RC-6** | `this song: analysed · next: analysing...` right up to the transition (Screenshot 1) | `TrackAnalyzer.kt:7508` uses a **single-thread executor** (`bitchord-smart-analysis`), so a 35 MB whole-track decode of Song A blocks `analyzeHead` of Song B! | Split `TrackAnalyzer` into **two executors**: a high-priority `nextTrackHeadExecutor` (analyzes first 35 s of `nextMediaItemIndex` immediately upon track change + prefetches `requestAnalysisHead` for `next` and `next+1`) and a background `wholeTrackExecutor` for Pass-1 full-track + tail analysis. Furthermore, allow `AutomixRenderer` blends to build as soon as outgoing `tailBeats` + incoming `headBeats` (`REFINING` or `ANALYSED`) are ready, without blocking on slow network lyric timeouts (`> 1.5 s` lyric timeout → proceed with `VocalTracker` mask!). |
| **RC-7** | Abrupt cutoff at outgoing EOF; flat EQ mud or loudness dip/clipping during blends | `echoSeconds = 0.0` disabled in planner; outgoing `ExoPlayer` stops at EOF; `LoopMixRenderer` lacks low-pass filter, echo tail, regional LUFS match, and true-peak limiter | Upgrade native `AutomixRenderer` DSP (`loop_mix_renderer.cpp`) with: (1) **TPT State-Variable Filters (Cytomic SVF)** per deck (LPF + HPF + 200 Hz Bass Swap), (2) **Tempo-synced stereo Ping-Pong Echo-Out tail** (`3/4` beat or `1/2` beat, band-passed `220 Hz–4.5 kHz`, ringing past outgoing region EOF!), (3) **Regional EBU R128 LUFS gain matching** over the exact 8-bar exit/entry windows, and (4) **Stereo-linked lookahead True-Peak Limiter (`-1.0 dBTP`, 1.5 ms lookahead)**. |

---
## Phase 4: iOS 27 Apple Music AutoMix Parity — Target Architecture & Decision Ladder

Apple Music AutoMix on iOS 26 / iOS 27 feels "human DJ natural" because it **never** forces a bad stretch, **never** lets two lead singers collide, **never** lets kicks flam by 30 ms on two drifting players, and **dynamically adapts transition duration & technique** to song structure (intro/outro phrasing, vocal pickup, silence trimming, harmonic compatibility, and album continuity).

Implement the following **6-Rung Deterministic Automix Ladder** (in `TransitionPlanner.kt` + `LoopMix.kt` + `AutomixSession.kt`), where **Rungs 1–5 all render through the unified native single-clock `AutomixRenderer` (`loop_mix_renderer.cpp`)**:

### Rung 0 — Album / Gapless / Live Continuity Guard (`GAPLESS`)
- **Trigger:**
  1. Consecutive tracks share the same `albumId` (or normalized album title + artist) AND `in.trackNumber == out.trackNumber + 1` (when user setting `respectAlbumGapless = true`, default **ON** for iOS 27 parity), **OR**
  2. Acoustic seamless-segue detection: outgoing final `150 ms` RMS `> -34 dBFS` AND incoming first `150 ms` RMS `> -34 dBFS` AND spectral centroid delta `< 450 Hz` (continuous live crowd / concept-album synth pad / DJ mix split).
- **Action:** Pure sample-accurate Media3 `GAPLESS` playback (0 ms crossfade, 1.0x speed, all filters open).

### Rung 1 — Dual-Loop Instrumental Bridge (`LOOP_MIX` — Existing Flagship, Hardened)
- **Trigger:** Both outgoing tail (last 60 s) and incoming head (first 45 s) contain a clean `2-bar` or `4-bar` (or `1-bar` high-confidence `>= 0.88` percussive) loop verified acoustically by **`VocalTracker` (`p_vocal_max < 0.22`, `p_vocal_mean < 0.14`)** AND not vetoed by tight word/line lyrics; effective BPM delta after `[0.5, 1.0, 2.0]` folding `<= 28%` (spread smoothly over 4–12 bars via `SignalsmithStretch` knots).
- **Hardening over `djmix4`:**
  - Snap loop `loopStart` and `loopEnd` to onset-snapped downbeats; verify loop-seam spectral cosine similarity `>= 0.80` and RMS step `< 3.5 dB`.
  - Apply an **8 ms equal-power seam crossfade** (`kLoopSeamSeconds = 0.008`) on every wrap inside `Deck::sampleAt` using pre-loop / post-loop guard samples from `LoopRegion` so zero-crossing discontinuities never click.
  - Check Camelot key distance (`camelotDistance(out.key, inn.key)`): if dissonant (`distance >= 3` and both sides have tonal harmonic content `tonality > 0.45`), engage a gentle outgoing low-pass (`2.8 kHz`) + incoming high-pass (`380 Hz`) during the shared loop bars so keys never clash!

### Rung 2 — Asymmetric Phrase-Extension / Vocal-Pickup Drop (`OUTRO_LOOP_DROP` — iOS 27 Signature Move!)
- **Why this fixes Screenshot 2 (`53 sung (lyrics), 0 sung (mask)`):** Pop, hip-hop, and R&B songs frequently start singing on Beat 1 or Bar 2 (`introEndTime < 4 s`), making a dual-loop impossible—yet Track A has a clean 2-bar or 4-bar instrumental outro (or beat tail)!
- **Trigger:** Outgoing has a clean 2-bar or 4-bar instrumental loop (`p_vocal_max < 0.22`), beat grids on both sides pass coherence (`>= 0.48`), folded BPM delta `<= 15%`, and incoming has a clear `pickupTime` / first downbeat (`headBeats != null`).
- **Action (Single-Clock `AutomixRenderer`):**
  1. Outgoing splices into its clean 2/4-bar loop and **ramps tempo smoothly** from `bpmOut` to `bpmIn` over `2` or `4` bars (`SignalsmithStretch` knots).
  2. Incoming does **NOT** loop (`loopStart = 0, loopEnd = 0, runOn = true`): it enters **at `1.000x` native speed** (`bpmIn`), pre-aligned so its `pickupTime` (if vocal pickup precedes Bar 1, e.g. 1–2 beats before downbeat) lands in the final beats of the outgoing loop, and **Incoming Bar 1 Downbeat hits the exact sample where the outgoing bass cuts (`-18 dB` shelf / `200 Hz` HPF) and outgoing loop dissolves via a 1-bar echo/HPF wash!**
  3. Result: Zero vocal clash, zero time-stretch artifacts on the incoming lead vocal, rock-solid downbeat drop!

### Rung 3 — Sample-Exact Beatmatched Phrase Blend (`BEAT_BLEND` — Replaces Dual-`ExoPlayer` `DJ_BLEND`)
- **Trigger:** No clean loop needed (or outgoing is melodic fade-out), both tracks have coherent onset-snapped beat grids (`confidence >= 0.45`, `phaseCoherence >= 0.42`), local grid alignment residual at anchor `< ±25 ms` (`bkmasterplayer` `offset_near` gate), and folded BPM delta `<= 10%` (or `<= 12%` when vocal overlap `< 0.20`).
- **Duration Sizing (Phrase-Aware):**
  - Low vocal overlap (`< 0.20`) & compatible Camelot key (`distance <= 1` or relative major/minor): **8 bars** (`32 beats`, clamped `10 s .. 20 s`).
  - Moderate vocal/harmonic density: **4 bars** (`16 beats`, clamped `6.5 s .. 14 s`).
  - Tight outro / early incoming vocal: **2–4 bars** positioned so outgoing vocal line ends **before** incoming `vocalEntryTime` (`Vocal Handoff` anchor solver!).
- **Continuous Tempo Bridge (No Static +25% Stretch!):**
  - Instead of playing the incoming song at a static stretched speed and walking it back later via `beginEase`, construct **beat-by-beat `knotFrames` / `knotPositions`** across the `N`-bar overlap:
    - At `m = 0` (blend start): mix tempo = `bpmOut` (outgoing at `1.000x`, incoming stretched by `bpmOut / targetInBpm` within `±8%`—or start at geometric midpoint `sqrt(bpmOut * targetInBpm)` if outgoing pre-ramped 2 bars prior).
    - Across `m = 0 .. rampEnd` (first 75% of blend): mix tempo glides linearly in beat-period space to `bpmIn`.
    - From `m = rampEnd .. handover`: mix tempo = `bpmIn` (`incoming` deck is already at **`1.000x` exact native rate** when `inPort` takes over!).
  - **EQ & Vocal-Handoff Envelopes inside `AutomixRenderer`:**
    - **Bass Swap:** Sharp 0.5-beat complementary `200 Hz` crossover ending precisely on the phrase midpoint downbeat (or Bar 1 drop downbeat) so **two kicks NEVER play simultaneously at full low-end**.
    - **Dynamic Vocal Carving:** Driven by `vocalOverlap(t)` from `VocalTracker`: when both decks have `p_vocal > 0.25`, outgoing low-pass sweeps smoothly down to `1.2 kHz` while incoming enters above `650 Hz` until outgoing vocal clears.

### Rung 4 — Beat-Synced Echo-Out / Filter Wash Handoff (`ECHO_HANDOFF` — Tempo Jump & Vocal-Heavy Rescue)
- **Trigger:** Folded BPM delta `> 10%` (e.g. `92 BPM → 128 BPM` where stretching sounds unnatural) OR harmonic clash (`camelotDistance >= 4` with high tonality) OR only one side has a locked grid.
- **Inspired by:** `bkmasterplayer` (`automix.rs::tempo_jump_plan` + `mixer.rs::prime_echo`) and iOS 27 AutoMix cross-tempo transitions.
- **Action (Single-Clock `AutomixRenderer`, NO tempo stretch on incoming!):**
  1. Outgoing plays at **`1.000x`** up to a strong phrase exit downbeat (`mixOutTime`, trimming any trailing silence via `musicEnd` `-38 dBFS` gate).
  2. Over the final `4 beats` before exit, outgoing low-end HPFs up to `220 Hz` and feeds a **BPM-locked `3/4`-beat (dotted-eighth) stereo ping-pong delay** (`feedback = 0.44`, wet = `0.55`, band-limited `240 Hz – 4.2 kHz`).
  3. On the exit beat, outgoing dry gain drops cleanly over `1 beat` while its **echo tail continues ringing for `6–8 beats` (`3–4 s`)** inside `AutomixRenderer`—even if the outgoing file's audio ended!
  4. Simultaneously on that exact exit moment, incoming enters at **`1.000x` native tempo** on its `audibleStartTime` / `pickupTime` / Bar-1 downbeat, emerging cleanly underneath the decaying echo wash!
  5. **Never** produce a rushed 2-bar muffled `DJ_FILTER` collision when `ECHO_HANDOFF` can cleanly hand the baton on a phrase boundary!

### Rung 5 — Silence-Trimmed Equal-Power Vocal-Safe Crossfade (`SMART_CROSSFADE` — Safe Floor)
- **Trigger:** Unanalyzed stream, live radio, DRM/unsupported sink where `LoopMixPort` cannot attach, or completely beatless ambient/spoken-word audio (`beatConfidence < 0.25` on both sides).
- **Action:** Trim trailing/leading dead air (`contentEndTime` at `-40 dBFS`, `audibleStartTime` at `-42 dBFS`), apply `4–6 s` equal-power S-curve crossfade with `rideVocalSeparation` if vocal masks exist.

---

## Phase 5: File-by-File Implementation Blueprint in BitChord

### 1. Native C++ Engine (`native/loopmix/loop_mix_renderer.h` & `loop_mix_renderer.cpp` + JNI)
Extend `bitchord::loopmix::Renderer` (keep existing JNI methods working or cleanly extend signatures in `LoopMixRenderer.kt` + `loop_mix_jni.cpp`):
1. **Non-Loop Linear/Ramped Deck Mode:**
   - Already partially supported when `loopEnd <= loopStart` (`length <= 0` in `Deck::sampleAt`!), **verify and harden**: when `loopEnd <= loopStart`, `Deck::sampleAt(unwrapped)` reads straight through `0 .. left.size() - 1` with zero wrapping, following `knotTimes` / `knotPositions` seamlessly into `runOn = true` (`rate = 1.0`).
2. **Clickless Loop-Wrap Crossfade (`kLoopSeamSeconds = 0.008`):**
   - When `length > 0 && unwrapped >= loopEnd && unwrapped < int64_t(releaseAt)`: near each wrap boundary (`posInLoop < seamSamples`), blend `deck.left[index]` with `deck.left[loopEnd + posInLoop]` (decoded from the `+0.5 s` post-loop margin already fetched in `LoopMixSession.kt:6873`!) using `sin/cos` equal-power weights. This eliminates 100% of loop-seam clicks.
3. **Upgrade Per-Deck Filter Bank to Zero-Delay-Feedback TPT SVF (Ported from `bkmasterplayer/app/rust/src/engine/mixer.rs::Svf`):**
   - Replace/augment RBJ `Biquad` with Cytomic TPT `Svf` (or smoothed per-64-frame coefficient interpolation without zipper noise) supporting simultaneous:
     - `KIND_GAIN` (existing)
     - `KIND_LOW_DB` (`200 Hz` low-shelf / bass-kill crossover)
     - `KIND_HIGH_PASS_HZ` (`20 Hz .. 4000 Hz` exponential)
     - **NEW `KIND_LOW_PASS_HZ`** (`250 Hz .. 20000 Hz` exponential — needed for `BEAT_BLEND` vocal carving & `ECHO_HANDOFF` darkening!)
4. **Add Tempo-Synced Stereo Ping-Pong Echo Stage (`EchoStage` on Outgoing Deck):**
   - Add `setEcho(int deck, double delaySeconds, float feedback, float wetLevel, const Envelope& sendEnv, const Envelope& dryEnv)`:
     - Circular stereo delay buffer (`max 2.0 s * sampleRate`), feedback path filtered with 1-pole HPF at `220 Hz` + 1-pole LPF at `4200 Hz` (`tanh` soft-saturation inside feedback loop at `0.95` so repeats warm up and never explode).
     - Crucially, allow `deck.stopAt` to extend to `dryEnd + echoTailSeconds` even after `deck.sampleAt` passes `left.size()` (feeding `0.0f` dry input while the delay buffer drains naturally over the incoming song!).
5. **Add Master Stereo-Linked Lookahead True-Peak Limiter (`-1.0 dBTP`):**
   - In `Renderer::render(...)`, after summing `Deck OUT` and `Deck IN` into `mixLeft_` / `mixRight_`:
     - Run a `1.5 ms` (`~72 frames @ 48 kHz`) lookahead stereo-linked peak limiter with ceiling `0.891f` (`-1.0 dBFS`), instant attack over the lookahead window, `60 ms` exponential release.
     - Account for the `72-frame` limiter latency in `Renderer::firstFrame()` and `offsetNow()` so splice and handover remain **sub-millisecond phase-exact**!
6. **`SignalsmithStretch` Quality Preset Selection:**
   - Currently `loop_mix_renderer.cpp:7096` hardcodes `deck.stretch.presetCheaper(2, float(sampleRate_), true)`.
   - Because `AutomixSession.build` / `render` runs well ahead of the speaker (`LOOP_ARM_LEAD_MS = 12_000L`) and in `AUTOMIX_BALANCED` / `HIGH_QUALITY` mode modern arm64 CPUs easily render `presetDefault(2, float(sampleRate_), true)` at >15x real-time: use `presetDefault` when `AutomixPerformanceMode != EFFICIENT` (and `presetCheaper` only in `EFFICIENT` or thermal throttling), dramatically improving vocal formant & transient crispness during tempo ramps!

### 2. Beat Grid, Onset Snapping & Multi-Region Analysis (`TrackAnalyzer.kt`, `BeatTracker.kt`, `native/analyzer/tempo_analysis.cpp`)
Port the proven algorithms from `thieggs/bkmasterplayer/app/rust/src/engine/analysis.rs` (MIT):
1. **Spectral-Flux Onset Snapping (`snapToOnsets`):**
   - Compute 100 Hz broadband + low-band (`40–180 Hz` kick) half-wave rectified spectral flux over each decoded head/tail region.
   - Measure median onset timing bias within `±35 ms` of Beat This! predicted beat times; shift grid phase so beats sit **directly on the physical kick/transient attack** (fixes the "slightly late/flamming kick" feel of raw neural beat activations!).
2. **Phase Coherence & Downbeat Reconciliation (`seamBeats` Replacement):**
   - Compute `phaseCoherence` (`0.0 .. 1.0`) per window by comparing median onset energy inside `±20 ms` of beat ticks vs off-beat midpoints.
   - Replace naive midpoint concatenation in `TrackAnalyzer.seamBeats` (`TrackAnalyzer.kt:8510`):
     - Across overlapping 22 s windows, verify whether window `i` and window `i+1` agree on BPM (`<= 1.5%`), octave (`1x` vs `2x`), and **4/4 bar downbeat phase (`beat_index mod 4`)** within the `1.0 s` overlap!
     - If window `i+1` has lower downbeat confidence and shifted `mod 4` bar phase on the same steady groove, align its downbeat numbering to window `i`; if the song genuinely changed tempo or meter (`> 1.5%` BPM shift with high coherence), emit separate `TempoRegion` entries so the planner uses the **local region around the actual anchor** (`tailRegion` for exit, `headRegion` for entry).
3. **Regional LUFS & True Silence / Pickup Bounds:**
   - Compute EBU R128 K-weighted momentary/short-term loudness (`lufsAtWindow(fromSec, toSec)`) over the candidate exit window (outgoing) and candidate entry window (incoming), clamping loudness trim gain to `[-7.0 dB, +5.0 dB]` (`VertexSelection/automix-engine` regional loudness matching, far more accurate than whole-track average replaygain when an intro is sparse!).
   - Detect `audibleStartTime` (`-42 dBFS` sustained `80 ms`), `pickupTime` (first percussive or vocal onset before Bar 1 downbeat, preserved up to `2 beats` so vocal pickups like *"It's Britney..."* or *"One, two..."* are NEVER chopped off!), and `musicEndTime` (last frame `> -38 dBFS` sustained `150 ms` so 8 seconds of dead trailing silence never delays a transition).
4. **Dual-Executor Fast Lookahead Queue (`TrackAnalyzer.kt`):**
   - Replace single `executor` with:
     - `headExecutor` (priority thread): runs `analyzeHead` + `VocalTracker` on the first `35 s` of `nextMediaItemIndex` immediately when a song starts or queue changes, completing in `< 1.8 s` on device.
     - `fullExecutor`: runs Pass-1 structural + tail Beat This! / VocalTracker windows for the currently playing song and `nextMediaItemIndex`.
   - Trigger `cache.requestAnalysisHead` for both `next` and `next + 1` on track transition.

### 3. Lyrics + Vocal Mask Fusion & Non-Loop `AutomixSession` (`LoopMix.kt`, `TransitionPlanner.kt`, `CrossfadeController.kt`)
1. **Fix `LoopMix.kt` & `CrossfadeController.loopPlanFor`:**
   - Remove the indefinite block on `if (outLyrics == null || inLyrics == null) return null` (`CrossfadeController.kt:4902`)! Start fetching lyrics immediately (`askLyrics`), wait up to `1500 ms` once within `25 s` of transition, and if lyrics are absent or slow, plan immediately using `VocalTracker` (`Check.MASK`).
   - In `LoopMix.kt`, **require acoustic `VocalTracker` clearance even when synced lyrics say a span is blank** (`Check.LYRICS_AND_MASK` whenever `vocalWindows` is non-empty). Never allow `Check.LYRICS` to pass a bar whose `VocalTracker` mask exceeds `0.22`!
   - Tighten `LoopMix.sungSpans`: do not pad `> 0.35 s` before a lyric line's start timestamp, and treat gaps `>= 1.6 s` between lyric lines as candidate instrumental windows (still verified by `VocalTracker`!).
   - Allow `1-bar` instrumental loops on incoming intros when `beatConfidence >= 0.85` and `0 sung (mask)` if `2-bar` and `4-bar` candidates are sung.
2. **Create `AutomixSession.kt` (or generalize `LoopMixSession.kt`):**
   - Generalize `LoopMixSession.build(...)` so it accepts **either** a `LoopMix.Plan` (Rung 1) **or** a `TransitionPlan` for Rungs 2 (`OUTRO_LOOP_DROP`), 3 (`BEAT_BLEND`), and 4 (`ECHO_HANDOFF`)!
   - Decode `outRegion` (`transitionStart - 1.0 s .. transitionEnd + 0.5 s`) and `inRegion` (`incomingCueTime - 1.0 s .. handoverTrack + 3.0 s`), configure `LoopMixRenderer` knots, EQ/LPF/HPF envelopes, optional `EchoStage`, regional LUFS `inLevel`, and attach to `outPort` + `inPort` using the **exact same battle-tested `driveLoopArming` / `driveLoopMix` / `startLoopAlignment` (`≤ 1.5 ms` handover accuracy) state machine** in `CrossfadeController.kt`!
   - Update `publishMixPreview`, `publishMixView`, and `describeAutomix` ("Stats for nerds") so the waveform popup and status line display the exact active rung (`loop mix`, `drop mix`, `beat blend`, `echo handoff`, `crossfade`, `gapless`), bar count, BPM bridge (`124 → 128 BPM`), Camelot keys, and splice/handover alignment in `ms`!

---

## Phase 6: Automated Verification & Quality Gates (Run Inside InstaBox!)

Do **NOT** ship an APK based solely on "it compiles." Execute all 4 verification gates inside InstaBox:

### Gate 1: Native C++ DSP & Planner Unit + Golden-Audio Render Suite
- Build a small host C++ / JVM test runner (or Gradle unit test + standalone Linux binary compiled from `loop_mix_renderer.cpp` + `tempo_analysis.cpp` on InstaBox `x86_64`) that renders synthetic & real test pairs to `.wav` and asserts:
  1. **Zero Beat Drift (`< 2.0 ms`):** Synthesize Track A (`120.0 BPM` impulse clicks on beats + `110 Hz` bass) and Track B (`126.0 BPM` impulse clicks + `440 Hz` pad). Render Rung 1 (`LOOP_MIX`), Rung 2 (`OUTRO_LOOP_DROP`), and Rung 3 (`BEAT_BLEND`). Cross-correlate impulse peaks across every bar of the transition: **every beat pair must align within `≤ 2.0 ms` (`96 samples @ 48 kHz`)**, and post-handover Track B rate must equal `1.0000x`.
  2. **Zero Loop-Seam Clicks:** Measure max sample-to-sample 2nd derivative (`|x[n] - 2x[n-1] + x[n-2]|`) across wrap boundaries on a continuous `220 Hz` sine wave; assert `> 35 dB` click suppression with the `8 ms` seam crossfade vs uncrossfaded wrap.
  3. **Bass-Swap & True-Peak Ceiling:** Verify low-band (`< 180 Hz`) RMS during overlap never exceeds `max(lowA, lowB) + 0.8 dB` (no kick doubling), regional LUFS jump across transition `< 1.5 LU`, and master output peak `<= -0.95 dBFS` (`0` clipped samples even with two `0 dBFS` masters!).
  4. **Echo Tail Survival Past EOF:** Truncate Track A right at `transitionEnd` (`EOF`), trigger Rung 4 (`ECHO_HANDOFF`), and verify non-zero decaying echo energy at `+0.5 s`, `+1.5 s`, and `+2.5 s` past Track A's EOF layered cleanly over Track B!
  5. **Vocal Clash Prevention:** Construct Track B with clean instrumental bars `0..2` (`p_vocal = 0.05`) and sung vocals starting at Bar 3 (`p_vocal = 0.88`), plus loose LRC lyrics claiming `0.0s .. 15.0s` sung: verify Rung 2 (`OUTRO_LOOP_DROP`) or 2-bar micro-loop fires cleanly with **zero concurrent frames where `p_vocal_out > 0.30 && p_vocal_in > 0.30` at > -6 dB gain**, and **never** collapses to a 2-bar `DJ_FILTER`!

### Gate 2: Dual-ABI Gradle Build (`arm64-v8a` + `x86_64`)
- Build both debug and signed release APKs inside InstaBox:
  ```bash
  ./gradlew testDebugUnitTest assembleDebug assembleRelease
  ```
- Inspect the built APK with `unzip -l` and `rea analyze-apk` to confirm:
  - Both `lib/arm64-v8a/libloopmix.so` (and `libaudio_analysis.so`, `libonnxruntime.so`) AND `lib/x86_64/*.so` are packaged and stripped cleanly (or produce a dedicated `arm64-v8a`-only phone APK alongside the universal/`x86_64` test APK so the user's phone APK stays compact!).
  - Sign the phone deliverable APK with a consistent v2/v3 signing key (`apksigner verify --verbose`).

### Gate 3: Live Runtime Verification in `instabox-android` (Android 14 `x86_64`)
- Connect via ADB (`adb connect 172.20.0.10:5555`), install the APK, push 6 diverse Creative-Commons / synthetic test tracks covering:
  - Pair 1: `128 BPM → 130 BPM` vocal pop (tests Rung 2/3 `OUTRO_LOOP_DROP` / `BEAT_BLEND` without loop starvation)
  - Pair 2: `122 BPM → 126 BPM` house/electronic with clean intros/outros (tests Rung 1 `LOOP_MIX`)
  - Pair 3: `94 BPM → 128 BPM` hip-hop to dance tempo jump (tests Rung 4 `ECHO_HANDOFF`)
  - Pair 4: Same-album Track 1 → Track 2 continuous segue (tests Rung 0 `GAPLESS`)
  - Pair 5: Track with falso-positive LRC ("clear by lyrics") over an audible vocal hook (verifies `VocalTracker` blocks vocal looping!)
- Capture `adb logcat -s BitChordCrossfade BitChordLoopMix BitChordTrackAnalyzer` and UI screenshots (`adb exec-out screencap -p`):
  - Verify `loop splice: deck ±X.XXms` (`|lag| < 2.0 ms`) and `handover ±X.XXms` (`|error| < 1.5 ms`).
  - Verify `next:` reaches `analysed (...)` within `< 4 s` of playback start (no `next: analysing...` starvation).
  - Verify zero ANRs, zero native crashes (` tombstones` / `SIGSEGV`), and smooth UI frame pacing.

---

## Phase 7: Required Final Deliverables Back to User

When all gates pass on InstaBox, deliver:
1. **Signed Phone APK (`BitChord-automix-ios27-arm64.apk`)** (plus universal APK if desired), uploaded/exported with `sha256sum` and exact byte size.
2. **`DJMIX4_DIFF_REPORT.md`:** Concise summary of what `BitChord-djmix4.apk` changed vs upstream `1.9.1` and which specific `djmix4` bugs were eradicated.
3. **`VERIFICATION_REPORT.md`:** Objective numbers from Gate 1 (measured beat alignment error in `ms`, loop-seam click floor in `dB`, LUFS delta, true-peak max `dBFS`) + Gate 3 `logcat` excerpts & Automix popup screenshots from `instabox-android`.
4. **Clean Git Patch / Repo Branch** inside `/workspace/src/BitChord` with modular, GPL-3.0-or-later compliant commits and zero proprietary/NC taint.

