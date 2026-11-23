# Technical Audit: BitChord Mix AutoMix (vc83 installed / vc84 staged) vs. Apple Music AutoMix

**Scope caveat, stated in the project's own vocabulary:** I audited the codebase reference, not the source, binaries, or audio. Everything below about *mechanism* is grade **documented**; everything about *sound* is grade **unknown**, and the document itself says that is the honest status of vc84. Where the doc contradicts itself, I flag it rather than resolve it.

**Headline correction to your framing:** you called this an "early-stage application attempting to replicate AutoMix." It is not early-stage in *scope*. It is a ~17k-line pure-Kotlin decision layer plus a native two-deck Signalsmith renderer, with ML beat/vocal models, Camelot key, phrase templates, isolator lanes, and a telemetry discipline most commercial teams don't have. The real finding is the opposite problem: **breadth has far outrun validation.** Architecturally this is a Stage-4 design; evidentially it is a Stage-2.5 product.

---

## 1. Current Implementation Audit

### 1.1 Pipeline breakdown

| Stage | What actually happens | Assessment |
|---|---|---|
| **Ingest** | Media3/ExoPlayer, YouTube Music Opus streams (bounded-range fetch, on-disk `AudioCache`), local FLAC/M4A/WebM, SMB. `PrecisionAudioSink` intercepts decoder PCM before Media3's own sink. | Sound choice. Intercepting PCM is the only way to get a real DSP chain under Media3. |
| **Analysis** | Fully **offline / ahead-of-playback**. `TrackAnalyzer`: decode → two ONNX models on CPU (beat/downbeat, vocal activity) → native mel/tempo → `TrackAnalysis` persisted in `AnalysisStore` (2,000 entries, schema v5). Second pass (`SongMaps`/`SongSections`) derives bar-level `SongMap`: 13 section functions, 6 roles, start/end kinds, from energy + self-similarity + **lyrics timing**. | Correct architecture (planner never touches PCM). The lyrics-as-structure-prior is clever and cheap. Cost is a full decode + 2 inferences per track on CPU — see §1.3. |
| **Transition point** | Seven-stage elimination ladder (§06): decline gates → tier (`BEATMATCHED`/`DJ_ASSISTED`/`PLAIN_CROSSFADE`) → candidate ranking (`StemClash` 0.82 confidence / 0.18 `BeatFlow`, `KickLock` ⅛-beat) → `PhrasePlanner` templates M1–M11 + 5 named styles, weighted cost over struct/vocal/phrase/energy/length/key/tempo → `PhraseScript` lanes → deterministic `DjMix` colour → `RetireGuard`. | Monotone-weakening ladder is the right invariant. **But the plan is recomputed every tick**, with stability bolted on afterward (`CountdownStabilizer`, `pairHash`). That is a symptom: the planner should commit once at arm time and only *re-plan on invalidation* (seek, stream swap, analysis arrival). |
| **Render — classic (shipped)** | Two ExoPlayer instances. Controller coroutine ticks (20 ms near target, "±25 ms") and pushes `volume`, `playbackRate`, and filter coefficients onto each player's sink. | **This is the architectural bottleneck.** See §1.3. |
| **Render — loop mix (staged, not shipped)** | Sink splices from song PCM to a C++ renderer running two Signalsmith Stretch decks on one master tempo; 25 ms sample-exact crossfades in and out; incoming ExoPlayer is only a clock until `handoverUs`. | This is the correct architecture, and it's the thing that hasn't shipped. The document admits it: "sample-exact, not by manipulating two ExoPlayer instances." |

### 1.2 DSP & MIR capability matrix

| Capability | Status | Evidence / caveat |
|---|---|---|
| Tempo / beat grid | **Implemented** (ML + native tempo, `GridRepair` onset snapping, head/tail `BeatSide`) | No ground-truth accuracy figure anywhere in the doc. The only F1s quoted (0.805 beat, 0.657 downbeat) are **CPU-vs-NPU agreement**, not accuracy. You don't know how good your grid is. |
| Downbeats / phrases | Implemented (`phraseBoundaries`, `PhraseGrid` DP over 8/16-bar lengths) | 0.657 downbeat agreement under quantization implies the float model's downbeat margin is thin — downbeat errors are the #1 cause of "technically aligned, musically wrong" mixes. |
| Key | Implemented, Camelot, head/tail separately | Only a **scoring term** in `PhrasePlanner`. No gating, no pitch shift, no influence on ordering. `HarmonicMix.kt`'s KDoc describes grid steadiness, not harmony — either misnamed or dead code. Debt. |
| Vocal activity | **Implemented** (ML mask, per-bar vocal energy, `StemClash`, `vocalOverlap` as material property, `VocalDepthFilter`) | Strongest MIR component here. This is genuinely ahead of a naïve crossfader. |
| Structure (intro/outro/drop) | Implemented (`SectionFn`/`SectionRole`, `StartKind`/`EndingKind`, `contentEndTime`) | Good contract; quality unknown. |
| Energy / bass presence | 10 Hz curves, low band as bass proxy | Adequate. |
| Time-stretch | **Two different engines**: Signalsmith (native loop path) and ExoPlayer `playbackRate` (classic path, = Sonic/WSOLA) | The *shipped* path uses the worse stretcher. Rate window `0.90..1.25` is not a "transparent stretch window" — +25% WSOLA on a vocal is audible to anyone. |
| Crossfade law | Equal-power (`MixGain`), filter-bent variant | Fine. vc82's "complementary exponents" regression shows gain law is being tuned by RMS, which is the wrong meter (see §2.4). |
| Filters | TPT-SVF, 2×2nd-order Butterworth 24 dB/oct LP+HP per player, lead-time compensated | Properly designed. `incomingLeadMs()`/`outgoingLeadMs()` shows someone understood pipeline latency. |
| Band isolation / bass swap | `HANDOFF_FRACTION = 0.5`, lanes `outLow/inLow/outMid/…`, "isolation" listed in chain | Lanes exist in the plan; whether the classic sink renders a true 3-band crossover or just LP/HP is not stated. Verify. |
| Loudness | `LoudnessProcessor` + peak limiter; `RegionalLoudness` | "Normalisation" — not specified as EBU R128 / LUFS-S. `mix.verify` reports "loudness delta" but it's a dry-run, not measured at the handoff. |
| FX | Echo, flanger, plate reverb, loop roll, mid scoop, `ReleaseSplice` | **More than Apple ships.** This is a liability, not an asset, for a consumer product (see §2). |
| Spoken word / live veto | Implemented at stage 1 | Detection method undocumented. |
| NPU | Dormant, correctly | Agree with keeping it off. |

**Precision inconsistency:** §01 says "custom 64-bit float DSP chain"; §07 says `AudioBlock` is interleaved **Float32**. One of these is marketing. Float32 is fine for this chain; fix the doc so no one "upgrades" it.

### 1.3 Android audio stack assessment

**What's right:**
- Media3 for decode/session/notification; custom sink for PCM; bit-perfect direct path; zero-allocation `PcmBoundary`; `yieldsToPlayback` + single inference thread; `DeckBudget` separating media time from wall time; `RetireGuard` −60 dB stop gate. These are all lessons that were paid for, and the failure-mode table (§16) confirms it.

**What won't scale — the classic two-ExoPlayer blend:**
1. **Two independent `AudioTrack`s = two clocks.** Each has its own startup latency, resampler state, and (on Bluetooth) its own HAL offset. `KickLock` promises ⅛-beat alignment (~58 ms at 128 BPM); a ±25 ms scheduler jitter *plus* unmeasured inter-track skew can eat all of it. The doc never cites a **measured live phase error at handoff** — `mix.verify` is a pre-flight dry run. You cannot claim beat-matching on this path until you measure it from the output.
2. **Control-rate automation from a coroutine.** Volume/rate pushed every 20 ms is a step function unless the sink ramps per block. On a 10 ms AudioTrack buffer that is zipper noise waiting to happen; on a 40 ms Bluetooth buffer it's 2 steps per update. `PhraseScript` already expresses lanes in master beats — they should be rendered sample-accurately in the sink, not replayed by the controller.
3. **Sample-rate heterogeneity.** Opus is 48 kHz; local FLAC is often 44.1 kHz. "One sample rate for all of it" is per-player. Two players at different rates are mixed by AudioFlinger, outside your DSP, outside your loudness match. Whether the loop renderer resamples decks to a common rate is not stated.
4. **`setPlaybackParameters` on ExoPlayer re-buffers** and has rate-change latency measured in hundreds of ms on some devices. A tempo *ramp* through this API is not continuous.

**CPU/battery:** Full-track decode + two ONNX CPU inferences per queued track, run on a one-device SM8635 with `EFFICIENT` defaults. No numbers in the doc for mJ/track or ms/track. On a mid-range SoC with a 50-track queue this is a thermal and battery story you haven't measured. Apple does this server-side across the catalog; you're doing it on the phone, per user, per track, and re-doing it after cache eviction (which is why `AnalysisStore` had to exist).

**Code-level debt:** `PlaybackService` 10,066 lines; `CrossfadeController` 8,214 lines with ~40 injected lambdas — the DI is a workaround for a god object, not a fix. Eight phases × every-function-behaves-per-phase is a state machine spread across 8k lines rather than a table. `TransitionPlanner` 2,323 lines, `LoopMix` 2,047. Version-tag archaeology (djmix6…26, vc69, vc76, vc80–84, "ported from Orchard JS") shows accretion, not refactoring.

**Process debt that is a product risk:** `origin/master` at vc76 with vc79–84 unpushed; 16 worktrees; a build wrapper hard-coded to the wrong checkout; a stale signed APK sitting where a fresh one would go; workstation disk at 91%. For a "device is the user's music player, cannot downgrade" posture, this is one bad afternoon from losing the shipping tree.

---

## 2. Gap Analysis vs. Apple Music AutoMix

What Apple ships (iOS 26 / 2025, as publicly described and observed): catalog-wide pre-analysis, on-device-assisted beat-matching and time-stretching, transitions placed at musical phrase/section boundaries, EQ/filter blending, tempo-gated fallback to a plain crossfade, loudness consistency via Sound Check, and — crucially — **conservatism**: it does little, does it reliably across every genre in the catalog, and almost never does something a listener would call "a DJ trick." Exact internals are not public; I compare against the observable behaviour and the engineering standard it implies.

### 2.1 Structural & phrase awareness
- **Apple:** section/phrase-aware placement with very low false-positive rate across millions of tracks; vocal clash is rare in practice.
- **This build:** richer *vocabulary* than Apple needs (13 section functions, 6 roles, 16 templates, lyrics-informed labels, per-bar vocal energy, `vocalOverlap` as a first-class material property). The `vocalOverlap`-vs-`filterSweep` separation is exactly right and was learned from a shipped clash defect.
- **Gap:** zero reported structure-labelling or vocal-mask accuracy; one user, one genre set. Apple's advantage here is not smarter features, it's **validation at catalog scale**. Your phrase planner is a hypothesis.

### 2.2 Beat-matching & time-stretching
- **Apple:** tight tempo gate (small % stretch, otherwise crossfade), stretch quality good enough that most users don't notice, alignment that is effectively sample-locked because one engine owns both decks.
- **This build:** two-tier reality. Loop path (Signalsmith, one master clock, 25 ms sample-exact splices) is architecturally *at or above* Apple. The **shipped** path is Sonic stretch through ExoPlayer with an asymmetric 0.90–1.25 window and unmeasured inter-player skew.
- **Gap:** the shipping engine is a generation behind the staged one, and the staged one has three open P1s in its audio path. The window must come down to roughly ±6–8% for "transparent" and the rest routed to `DJ_ASSISTED`/fold or crossfade.

### 2.3 Harmonic & key compatibility
- **Apple:** not publicly confirmed as a factor; observably conservative (short, filtered overlaps hide clashes).
- **This build:** key detected with confidence, head/tail, Camelot; used as one weighted term.
- **Gap:** small vs. Apple, but internally inconsistent: you have the data and don't act on it. A clashing key should *shorten the overlap and raise the filter depth*, not merely lower a score. `HarmonicMix.kt` needs renaming or deleting.

### 2.4 Spectral & dynamic blending
- **Apple:** EQ-assisted crossfade (observable low-end handoff), loudness-consistent via per-track catalog loudness.
- **This build:** 24 dB/oct LP/HP with latency compensation, bass-swap midpoint, band lanes, vocal-depth-modulated sweep, reverb/echo tails with dry splice. Spectrally this is **richer than Apple**.
- **Gap:** dynamics. Loudness match is unspecified as LUFS; gain law was tuned by summed RMS (vc82 regression) — RMS doesn't track perceived loudness through a sweeping low-pass, which is exactly why it "measured better and sounded worse." Need momentary/short-term LUFS of *each deck through its own filter* at the handoff, and a true-peak limiter on the **sum**, not per player.

### 2.5 Edge-case handling
| Case | This build | Gap vs. Apple |
|---|---|---|
| Live / spoken word | Vetoed at stage 1 | Method undocumented; Apple has catalog metadata for this. Risk is false negatives (podcast-ish intros, live albums tagged as studio). |
| Abrupt endings | `contentEndTime`, `EndingKind`, `MIN_INCOMING_CLEARANCE = 5 s` | Reasonable. |
| VBR / stream swaps | All analysis in track-timeline seconds; `QualityUpgrade` swaps renditions mid-play; `smart_version_alignment` | Opus pre-skip and encoder-delay differences between renditions shift timelines by 10s of ms — enough to break `KickLock`. Not addressed in doc. |
| Drastic BPM jumps | `fold` 2/0.5, `stretchReachable`, fallback to plain | Good. Better than Apple's observed behaviour, which just crossfades. |
| Seek / pause mid-mix | `SeekAction`, `CatchUpPlanner`, `mix.pause.detected`, `BAILING` | Good; defects here were reproduced and pinned. |
| Bluetooth latency | `AudioOutputStatus` exists | Whether `leadMs` and the 25 ms splices account for route latency isn't stated. |
| Multi-device / multi-SoC | arm64-v8a only, SM8635 only | Apple has a closed hardware set; you have the opposite and have tested on one device. This is the single largest *unknown* for "scales on Android." |

---

## 3. Maturity Rating & Roadmap

### 3.1 Maturity score

**Stage 3, with Stage 4 components present but unvalidated.** Put differently: *designed to Stage 4, shipping at Stage 3, proven at Stage 2.*

Evidence:
- **Beyond Stage 2:** phrase templates, vocal-clash-aware candidate ranking, filter/isolator lanes, structure-labelled sections are all in the shipped vc83 "classic" path → Stage 3 is real.
- **Not yet Stage 4:** the time-stretched, shared-clock renderer is staged, unsigned, uninstalled, with three open P1s in the audio path; key is cosmetic; the shipped stretcher is Sonic.
- **Not Stage 5:** no catalog-scale validation, no ground-truth accuracy for any model, no live-measured alignment, one listener, one device, and the project's own evidence ledger grades most recent work "unconfirmed" and the vc83→vc84 question "unknown."

The honest distance to Apple is **not** feature count — on features you are at parity or beyond. The distance is: (a) one render clock for both decks in the *shipped* path, (b) measurement of what actually comes out of the DAC, (c) robustness across devices/genres, and (d) editorial restraint.

### 3.2 Roadmap

**Immediate (high ROI / low complexity)**
1. **Close the three P1s with characterisation renders**, not reasoning (your own Recipe B). The PHRASE double-count and M7 stop-gain are both "plan says X, renderer does Y" — pin them with `DeclineOverlapRenderTest`-style PCM assertions.
2. **Measure live alignment.** At every classic handoff, log `AudioTrack.getTimestamp()` from both sinks plus the planned beat phase; emit `mix.align.measured`. Until this exists, `BEATMATCHED` on the classic path is a label, not a property.
3. **Shrink the stretch window** to ~0.93–1.08 for `BEATMATCHED`; route 0.90–0.93 and 1.08–1.25 to `DJ_ASSISTED` (quantized anchor, no stretch) or fold.
4. **Move gain/filter automation into the sink.** The sink already receives lanes via `TransitionFilters`; have it own the full `PhraseScript` and ramp per block against its own frame counter. Delete the 20 ms tick as an automation source; keep it for supervision only.
5. **Fix the doc/code contradictions:** Float32 vs 64-bit; `HarmonicMix.kt` KDoc; confirm deck resampling in the loop renderer.
6. **Repo hygiene as a release blocker:** push vc79–84, retire 14 of 16 worktrees, fix the wrapper's `REPO`, delete the stale vc82 APK, free the disk.

**Core DSP upgrades (medium term)**
1. **Unify rendering on the native engine.** Make the two-deck C++ renderer the *only* transition renderer: both decoders feed ring buffers, the renderer owns one clock and one output (`AudioTrack` or AAudio/Oboe for lower, more predictable latency), ExoPlayers become decoders + session state. This single change retires the classic path's skew, Sonic, zipper risk, and AudioFlinger-side mixing in one move, and collapses most of `CrossfadeController`'s phase surface.
2. **Ground-truth your MIR.** Run beat/downbeat on an annotated set (plus 50 hand-annotated tracks from the user's genres). Publish F-measure/CMLt/AMLt and downbeat F1 in the evidence ledger. Gate `BEATMATCHED` on *measured* confidence calibration, not model-reported confidence.
3. **Loudness done properly:** EBU R128 short-term LUFS per deck post-filter, equal-loudness (not equal-power) target at the handoff, sum-bus true-peak limiter, `mix.verify` replaced by post-hoc measurement of the actual rendered handoff.
4. **Real 3-band crossover** (Linkwitz-Riley 4th order) for the `outLow/inLow/…` lanes if the classic sink currently only has LP/HP.
5. **Key as a gate, not a score:** Camelot distance >2 → shorter overlap + deeper filter + no `VOCAL_OVER_OUTRO`; optionally ±1 semitone Signalsmith pitch shift when it buys compatibility and the tier is already `BEATMATCHED`.
6. **Analysis cost control:** analyze head (first 60 s) and tail (last 90 s) at full resolution and the body at coarse resolution; key analysis by rendition hash; schedule on charge/Wi-Fi; log ms and mJ per track in telemetry.
7. **Rendition timeline alignment:** cross-correlate 2–3 s of head PCM between the analyzed rendition and the playing one; apply the offset to all `TrackAnalysis` times. Cheap, and it protects `KickLock`.
8. **Cut the palette.** Default-off everything not graded `accepted`: flanger, loop roll, buildup, reverb wash. Apple's product lesson is that a boring transition that is never wrong beats a clever one that is occasionally wrong.

**Advanced intelligence (long term)**
1. **Joint structure model** (beat + downbeat + section function in one network) replacing the energy/self-similarity/lyrics heuristics — on CPU first; NPU only after the agreement gate *and* an HTP dispatch trace pass.
2. **Lightweight stem awareness** (vocal/drum/bass activity, not full separation) to drive ducking and `StemClash` from spectrogram-level evidence rather than a single vocal mask.
3. **Closed-loop learning from the listener:** a skip inside or within 10 s after a transition is a negative label; a full play-through is weak positive. Feed it into template priors per genre. This is the one place a single-user fork can out-learn Apple on its own user.
4. **Offline evaluation harness:** a corpus of pairs with rendered outputs, objective metrics (measured beat phase error, LUFS delta, vocal-overlap seconds, key distance), and a blind listening protocol. Right now "marked listening session" is the only quality instrument; it does not scale past one person.
5. **Device matrix:** at minimum three SoCs (one Exynos, one MediaTek, one older Snapdragon) and Bluetooth/USB/wired routes before any claim about Android generally.

### Bottom line

This is not a crossfader with ambitions; it is a nearly complete DJ transition engine whose shipped render path is a generation behind its own staged one and whose quality has been measured by exactly one pair of ears. Close the P1s, ship the native renderer as the *only* renderer, instrument the output rather than the plan, and cut the FX palette to what's been accepted — do those four things and the remaining gap to Apple is validation breadth, not engineering.