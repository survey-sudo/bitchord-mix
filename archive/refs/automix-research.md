# DJ-level on-device Automix for BitChord: research dossier

*Compiled 10 Nov 2026 (UTC+7) for the BitChord loop-fix fork. Feeds the bkmasterplayer port and the automix overhaul.*

**Confidence marks.** **[A]** primary source, i.e. Apple text, a repo's own code or licence file, or a paper. **[B]** established outlet or a detailed hands-on. **[C]** forum, Reddit or blog, anecdotal. **[I]** my inference, not stated by any source. **[?]** unknown or unconfirmed. Repo facts marked "(read)" were checked in a shallow clone on 10 Nov 2026.

---

## 0. TL;DR

1. **Apple AutoMix in iOS 27 is confirmed to loop, repeat intro/outro phrases, and use reverb and delay.** It is **not** confirmed to use a flanger, to separate stems, or to label song sections. Apple's own iOS 27 "What's New" text reads: *"AutoMix now draws on a richer palette of effects – including looping, reverb, delay, and more"*. iOS 27.1, 27.2 and 27.2.1 made no AutoMix changes.
2. **Apple's new building block is public.** At WWDC26 Apple shipped a **Music Understanding** framework (on-device key, beats/bars, sections/segments/phrases, pace, instrument activity including vocals, and LUFS). It is the best public proxy for what AutoMix "knows". Apple does not say AutoMix uses it.
3. **BitChord's 1.9.1 tree already has the expensive parts:** Beat This! final0 q8 ONNX, the open-unmix **umxhq** vocal model (int8, MIT weights), Signalsmith Stretch, and AGPL code from heydavidf/automix and Orchard. So the gap to "DJ-level" is mostly **planning, section awareness and effects**, not new models.
4. **Full-song stems on a mid-range phone are not viable in real time.** HTDemucs peaks at about 2.2 GB of RAM. Separate **only the transition windows**, or use the umxhq vocal mask for vocal-out.
5. **Licence traps:** madmom models (CC BY-NC-SA), open-unmix **umxl** (NC), and SamAfshar2011/AI_DJ (proprietary). Avoid all three. Rubber Band (GPL-2+), SoundTouch (LGPL), Bungee (MPL-2.0) and Essentia/AGPL code are all usable in BitChord.
6. **Patent caution (US only, not legal advice):** US 8,280,539 (Spotify/Echo Nest, active to 2031-03-11) claims picking the transition window by **sliding-window comparison of both tracks' descriptors**. bkmasterplayer deliberately avoids this, and BitChord's planner should avoid it too.

---

## 1. Apple Music AutoMix: verified evolution

### 1.1 Timeline

| When | Version | What changed | Grade and source |
|---|---|---|---|
| 2022-12-06 | iOS 16.2 (Apple Music Sing) | Precedent: **on-device, real-time vocal isolation** with adjustable vocal level, iPhone 11 and later. Not AutoMix, but it shows Apple has shipped stem-like tech for years. | A: [Apple Newsroom](https://www.apple.com/newsroom/2022/12/apple-introduces-apple-music-sing/) |
| 2025-06-09 | iOS 26 announced | "AutoMix transitions one song into the next like a DJ does, **time stretching and beatmatching**." | A: [iOS 26 updates](https://support.apple.com/en-us/123075), [iOS 26 PR](https://www.apple.com/newsroom/2025/06/apple-elevates-the-iphone-experience-with-ios-26/) |
| 2025 autumn | iOS 26.0 | Ships on by default and catalog-only. It "automatically selects the best transition", including silence trim or a simple crossfade. Albums and some genres are skipped, and it is off under Hi-Res Lossless. | A: [Support: Transition songs](https://support.apple.com/guide/iphone/transition-songs-iphadf2fe1f4/ios) |
| 2025-09 | iOS 26 (user reports) | "AutoMix seems to prefer transitioning to the **chorus** of the next song" and skips the first parts. This is the first hint of section awareness. | C: [r/AppleMusic, Sep 17 2025](https://www.reddit.com/r/AppleMusic/comments/1niytd1/which_transition_do_you_prefer_and_why/) |
| iOS 26 era | iOS 26 (reviews) | Adds reverb at times, and "the vocals from one song stick around long after the bassline has faded". Can skip a long instrumental intro. | B: [GB News](https://www.gbnews.com/tech/apple-music-automix-ios-26) |
| iOS 26.1 | 26.1 | "Apple Music **AutoMix support over AirPlay**." | A: [About iOS 26 Updates](https://support.apple.com/en-us/123075) (read) |
| iOS 26.2 / 26.4 | 26.2, 26.4 | No AutoMix line in the release notes (26.2 added offline lyrics, 26.4 added Playlist Playground). A Reddit user thought mixes improved after 26.2, which suggests server or model updates outside the notes. | A (absence): same page; C: [Reddit](https://www.reddit.com/r/AppleMusic/comments/1ptcyav/anybody_feel_like_the_automix_improved_a_lot/) |
| 2026-06-08/09 | iOS 27 announced (WWDC26) | Apple: "AutoMix has added a whole new layer of energy and excitement… even better transitions that feel more immersive". Coming to **tvOS and HomePod**. Press paraphrase: "upgraded the algorithms to generate **new transition types**". | A: [Apple services PR, Jun 9 2026](https://www.apple.com/newsroom/2026/06/apple-unveils-innovative-features-and-intelligence-experiences-across-services/); B: [MacRumors](https://www.macrumors.com/2026/06/09/apple-music-gains-automix-upgrades-and-more-in-ios-27/), [9to5Mac](https://9to5mac.com/2026/06/10/ios-27-heres-all-the-new-apple-music-features/) |
| 2026-06 | WWDC26 | **Music Understanding framework**, on-device: key (per time range), rhythm (beats, bars, BPM), structure (**sections, segments, phrases**, as time ranges only, with no labels in the API), pace, **instrument activity** (drums, bass, vocals and so on, 0–1 over time), and LUFS loudness. Built by Apple's "Computational Music Team", which says it was built "to solve real challenges here at Apple". | A: [WWDC26 session 253](https://developer.apple.com/videos/play/wwdc2026/253/) |
| 2026-06-10 | iOS 27 beta 1 | Hands-on: a new transition "similar to **vocal chops/stutter edits**". | C: [r/AppleMusic](https://www.reddit.com/r/AppleMusic/comments/1u1zcn0/ios_27_new_automix_transition/) |
| 2026-07-16 | iOS 27 public beta | The iOS 26 "underwater" sound is mostly gone. It "will even **remix the outro and intro**… to align the tempo" and "**repeat parts of the outros and intros** to bridge the transition". | B: [9to5Mac hands-on](https://9to5mac.com/2026/07/16/ios-27-makes-one-of-my-favorite-apple-music-features-even-better/) |
| 2026-08-10 | iOS 27 beta 5 | Apple's in-app "What's New" splash: "**Enhanced AutoMix: AutoMix now draws on a richer palette of effects – including looping, reverb, delay, and more** – to craft smooth, high-energy transitions." | A via B (Apple UI text quoted by a beta tracker): [BetaProfiles beta 5](https://forum.betaprofiles.com/t/whats-new-in-ios-27-beta-5/23226) |
| 2026-09-14 | iOS 27.0 release | Release day for iOS 27. AutoMix on tvOS and HomePod. | B: [Mac Observer](https://www.macobserver.com/tips/round-ups/apple-music-automix-tvos-homepod-ios-27-lyrics-pairings/), [9to5Mac Sep 18](https://9to5mac.com/2026/09/18/apple-music-in-ios-27-five-new-features-iphone/) |
| 2026-09 | iOS 27.x (users) | Mixed reviews. Some report "repeating parts of the song like a loop… really makes you think it's a DJ". Others report a fragment looping then an abrupt end, verses still cut, long silences, a stutter glitch near the end of a mix, and best results in dance/pop/electronic. | C: [r/AppleMusic, Sep 20 2026](https://www.reddit.com/r/AppleMusic/comments/1wkm7d1/automix_ios_27/) |
| 2026-10-23 | iOS 27.1 | iPhone Duo only. Nothing on AutoMix. | B: [MacRumors](https://www.macrumors.com/2026/10/23/apple-releases-ios-27-1/) |
| 2026-10-26 | iOS 27.2 | Apple Music change is a new icon and the rebrand only. No AutoMix change. | A: [About iOS 27 Updates](https://support.apple.com/en-us/149076) (read); B: [MacRumors 27.2](https://www.macrumors.com/2026/10/26/ios-27-2-features/) |
| 2026-11-09 | iOS 27.2.1 (latest) | Bug and security fixes only. | A: same Apple page |

### 1.2 The user's claims, checked one by one

| Claim | Verdict | Evidence |
|---|---|---|
| Latest AutoMix uses **DJ-level loops** | **CONFIRMED (iOS 27.0, beta from June 2026)** | Apple's own splash text says "looping" [A via B]. 9to5Mac describes repeated intro and outro parts [B]. Reddit users report the same [C]. |
| Uses a **flanger** | **NOT CONFIRMED** | Apple names "looping, reverb, delay, and more". "Flanger" appears in no Apple or press text I could find. The "underwater" sound from iOS 26 was probably filtering or reverb, not a flanger [I]. It could be inside "and more" [?]. |
| **Separates instrumental from vocals** (stems) | **NOT CONFIRMED for AutoMix; plausible** | Apple has had on-device real-time vocal isolation since Apple Music Sing (iOS 16.2) [A], and Music Understanding reports per-instrument activity including vocals [A]. Reviewers hear vocals that linger while the bass fades [B], and iOS 27 adds "vocal chop" transitions [C]. Both are consistent with stems, and also with plain EQ or loops. No source says AutoMix separates stems [?]. |
| Knows **sections** (intro, riff, pre-chorus, chorus, bridge, outro) | **PARTIALLY.** Section and phrase awareness is likely; labelled sections are not confirmed, and "riff" is not confirmed at all | Music Understanding exposes **unlabelled** sections, segments and phrases, and Apple's talk explains sections as "chorus, verse, intro or bridge" [A]. Since iOS 26, users say it jumps to the chorus [C]. iOS 27 repeats intro and outro phrases [B]. No source mentions riff or pre-chorus detection [?]. |
| "So good" | **MIXED** | Many hands-on reviewers say iOS 27 is much better [B][C]. Failure reports continue: cut verses, abrupt loops, silences, glitches [C]. |

### 1.3 Corrections and additions to the user's wiki

- The wiki says iOS 27 "rebuilt the transition generator". Apple's words are softer: "upgraded algorithms… new transition types" (press paraphrase) [B]. Keep the wiki's grade at B.
- New since the wiki was written:
  - Apple's beta-5 splash text, which confirms looping, reverb and delay.
  - The Music Understanding framework. It is the best answer to the wiki's open question "where does analysis run?": Apple ships an all-on-device analyzer with exactly the features AutoMix needs [A]. That AutoMix uses it is [I].
  - The device floor matches Apple Music Sing (iPhone 11) [I].
- The wiki's prior-art section should note that US 8,280,539 belongs to **Spotify (ex-Echo Nest)** and is active to 2031 (from bkmasterplayer's legal notes, [docs/JURIDICO.md](https://github.com/thieggs/bkmasterplayer/blob/main/docs/JURIDICO.md), (read)). Check it yourself at [Google Patents](https://patents.google.com/patent/US8280539B2/en).
- Spotify's analogue (Mix) launched in beta on 2025-08-19. It shows BPM, Camelot key and waveforms, and lets users edit the transition point, volume, EQ and effects [A: [Spotify newsroom](https://newsroom.spotify.com/2025-08-19/mix-your-favorite-playlists-seamlessly-by-adding-your-own-transitions/)].
- What to copy from Apple's behaviour [I]:
  - Skip genres where mixing hurts (classical, opera, spoken word).
  - Never mix inside an album.
  - Repeat phrases to extend a short intro or outro.
  - Effect variety on rotation.
  - A visible "Mixing" indicator.
- What to avoid, from Apple's failure reports:
  - Cutting first verses.
  - Looping a fragment and then ending abruptly.
  - Silences.

---

## 2. Open-source automix and auto-DJ survey

"GPL-ok" means it can be included in BitChord, which is GPL-3.0-or-later and already contains AGPL-3.0 files combined under AGPL §13 (see `LoopMix.kt` and `TrackAnalysis.kt` headers, (read)).

### 2.1 Players and auto-DJ engines

| Project | Licence (GPL-ok?) | Language | Pipeline | Genuinely good | Mobile cost |
|---|---|---|---|---|---|
| [thieggs/bkmasterplayer](https://github.com/thieggs/bkmasterplayer) | MIT ✅ (read) | Flutter + Rust engine | **Analysis:** Beat This! via [beat-this-rs](https://github.com/danigb/beat-this-rs) (small model about 10 MB on the phone, full 83 MB on server or desktop). Multi-region grid fit with onset snapping; Camelot key by Pearson correlation; intro/outro from per-bar energy at the 75th percentile. **Planner** (`automix.rs`): requires a steady grid at both ends. Speed is chosen among 1×, 2:1 and 1:2, capped at ±8%; beyond that it echoes on the bar. Prefers 16 bars, minimum 4, maximum 40 s. A key clash caps the mix at 8 bars with a filter. Phase is re-measured at the mix point and it falls back if off by more than 25 ms. Tempo ramps back over 16 bars. The echo tail plays past the end of the file. **Styles:** bass swap, blend, filter, echo, cut. | Very defensive planner with clear fallbacks; drift-tolerant "each at its own tempo, cut or echo on the bar"; music-end detection that keeps final hits; good tests. | Small Beat This model on device; Signalsmith stretch. Heavy analysis can be offloaded to a server, which BitChord can't do. |
| [kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord) 1.9.1 (baseline) | GPL-3+ with AGPL parts (read) | Kotlin + C++ | Beat This! **final0 q8** ONNX and open-unmix **umxhq int8** vocal mask, over head and tail regions. `TransitionPolicy` tier ladder; `LoopMix` (4/2/1-bar instrumental loops, lyric- and mask-checked, shared beat clock and tempo ramp); styles EQUAL_POWER, GAPLESS, DJ_BLEND, DJ_FILTER (sweep, bass handoff, echo ride). | The loop mix is the crown and stays first. It already has vocal masks and lyrics. | ORT Android 1.28, Signalsmith in `native/third_party`. |
| [heydavidf/automix](https://github.com/heydavidf/automix) | AGPL-3 ✅ (BitChord's loop mix descends from it) (read) | TS / Web Audio | essentia.js BPM, key and energy; picks a compatible next track; Signalsmith stretch; phrase-boundary crossfade; weak grids get a 12 s fade. | Simple, honest fallbacks. | Browser only. |
| [opendj (interdome)](https://git.interdo.me/interdome/opendj) | GPL-3 ✅ (read) | C++ / JUCE | **Phrases** = 16 beats from the first musical bar. Intro and outro found by missing mids and highs, not loudness (thin = mid and high below 0.7 of the 75th percentile). Out at outro start, at most 16 bars before the music ends; 32 beats if two phrases spare, else 16. Bass swap at half. **Vocal-aware:** stems separated while the previous track plays; a bar counts as sung when the vocal stem is over ¼ of the bar level (about −12 dB) and above −40 dBFS. The planner minimises bars where both tracks sing, then hands off by fading the outgoing vocal stem over the bar before the incoming vocal. Key lock whenever tempo changes; LUFS match within 6 dB. Beat-synced flanger, phaser, gate, stutter, echo-out. ([ROADMAP](https://git.interdo.me/interdome/opendj/src/branch/main/ROADMAP.md), [PR #44](https://git.interdo.me/interdome/opendj/pulls/44)) | **The best open reference for vocal-clash avoidance and DJ effects.** Thresholds are documented and tested. | Desktop. HTDemucs takes 118 s per 5-minute track on CPU and 27 s on an RTX 4070, so it is not phone-real-time. |
| [NullCoreDeveloper/NullMusic](https://github.com/NullCoreDeveloper/NullMusic) | GPL-3 ✅ (read) | Kotlin / Media3 | Classical DSP: STFT spectral flux, autocorrelation BPM, comb-filter phase. 18 s window from mid-track; folded to 70–140 BPM; 5 MB fetch cap; energy scan of head (16 s) and tail (24 s) for mix points; chroma key. **`AdaptivePreArmManager`**: 20 s base lead, up to 35 s, grows when preparation exceeds 8 s. | Cheap. The **adaptive pre-arm** idea fits BitChord's original "decks too slow" bug. | Very low. |
| [Mixxx AutoDJ](https://manual.mixxx.org/2.5/en/chapters/djing_with_mixxx#auto-dj) | GPL-2+ ✅ | C++ | Intro/outro cue sections (first and last sound at −60 dBFS). Modes: Full Intro+Outro (default), Fade At Outro Start, Full Track, Skip Silence. The crossfade length is the shorter of intro and outro. The manual says AutoDJ "does not take into account the volume… frequency content, nor the rhythms". | Robust cue model; Sync Lock handles 2:1 and dynamic-tempo leaders. | Desktop. |
| [SamAfshar2011/AI_DJ](https://github.com/SamAfshar2011/AI_DJ) | **Proprietary, "All Rights Reserved"** ❌ (read) | Python | Offline render. Analysis: BPM, downbeats, phrases, Camelot, LUFS, energy, vocal proxy, sections, 64-d embedding. Plans the set by energy arc; constant-rate Rubber Band stretch clamped to ±6% (±12% aggressive); bass swap; filter/echo when too far apart; −14 LUFS master with limiter. | Good design ideas only. **Do not copy code.** | Desktop offline. |
| [charbelmalo/opensonic](https://github.com/charbelmalo/opensonic) | MIT ✅ (read) | Python | Demucs stems, per-stem gain automation instead of full-mix crossfades; onset cross-correlation lag measurement with micro-nudge; verify gates (beat-match, flutter, loudness, stem clash). | **Post-render verification gates** are worth copying. | Desktop (about 15 min of CPU for 8 tracks). |
| [MZehren/Automix](https://github.com/MZehren/Automix) | MIT ✅ | Python | Cue-in/cue-out detection from structure, using madmom drum transcription. | Research cue-point model. | Desktop; madmom NC models ❌. |
| [aida-ugent/dnb-autodj](https://github.com/aida-ugent/dnb-autodj/) and [paper](https://link.springer.com/article/10.1186/s13636-018-0134-8) | check repo ? | Python | Drum-and-bass auto-DJ: downbeats, structural segmentation (drop detection), key, phrase-aligned mixes with EQ. | Classic academic reference for structure-aware DJ planning. | Desktop. |

### 2.2 Research worth borrowing

- **Real DJ mix statistics:** [Kim et al., ISMIR 2020, djmix-analysis](https://github.com/mir-aidj/djmix-analysis/) aligned 1,557 real mixes to their tracks. It studies cue points, tempo changes and EQ use. Use it to tune cue and tempo priors. [A]
- **Learned transitions:** DJtransGAN ([arXiv 2110.06525](https://arxiv.org/html/2110.06525v2)) learns fader and EQ curves with differentiable effects. It suggests parametric EQ/fader curve templates, which are cheap at runtime. [A]
- **Track ordering:** DJ-MC (Liebman, Saar-Tsechansky & Stone, AAMAS 2015, RL playlist sequencing) covers which track comes next, not how to mix it. **[? not re-fetched]**

### 2.3 Analysis models and DSP libraries

| Component | Repo | Licence (GPL-ok?) | Notes and mobile cost |
|---|---|---|---|
| Beats/downbeats | [CPJKU/beat_this](https://github.com/CPJKU/beat_this) | MIT code and weights ✅ (read) | final0 about 78 MB and small0 about 8.1 MB (README). BitChord ships final0 int8. For low-end or thermal fallback, use the small ONNX from [beat-this-rs](https://github.com/danigb/beat-this-rs) (about 10 MB, plus a 270 KB mel model). |
| Beats (online) | [mjhydri/BeatNet](https://github.com/mjhydri/BeatNet) | CC BY 4.0 ✅ (FSF lists it as GPLv3-compatible) | Causal particle-filter beat, downbeat and meter tracking. Useful for live correction, but Beat This is stronger offline. Optional. |
| Beats (legacy) | [CPJKU/madmom](https://github.com/CPJKU/madmom) | Code BSD ✅, **models CC BY-NC-SA ❌** (read) | Avoid shipping its models. |
| Structure + labels | [mir-aidj/all-in-one](https://github.com/mir-aidj/all-in-one) | MIT ✅ (read) | Beats, downbeats, segments and **labels** (intro, verse, chorus, bridge, outro). Needs Demucs 4-stem input plus NATTEN. A 4090 does 33 min of audio in 73 s, so it is **too heavy for a phone**. Weights are trained on Harmonix [? weight licence not separately stated]. |
| Structure + labels | [ASLP-lab/SongFormer](https://github.com/ASLP-lab/SongFormer) | Code CC BY 4.0 ✅ (read) | 8 labels **including pre-chorus** and inst. Depends on MuQ and MusicFM self-supervised backbones (large; check their weight licences [?]). Server or desktop only. |
| Structure (classical) | [urinieto/msaf](https://github.com/urinieto/msaf) | MIT ✅ (read) | Foote novelty, spectral clustering and others. **The algorithms are cheap enough to port** to beat-synchronous features on device. |
| Key | [mixxxdj/libkeyfinder](https://github.com/mixxxdj/libkeyfinder) | GPL-3 ✅ (read) | C++, proven in Mixxx; FFTW dependency (swap for Signalsmith FFT). |
| Key/MIR suite | [MTG/essentia](https://github.com/MTG/essentia) | AGPL-3 ✅ for BitChord (read) | Key profiles (edma, bgate), loudness, onsets; has an `build_android.sh`. Heavy; take the algorithms, not the whole library. |
| Stems | [facebookresearch/demucs](https://github.com/facebookresearch/demucs) (htdemucs) | MIT ✅ (read) | On a phone, peak RAM is **about 2.2 GB** with the ORT arena and graph optimisation off (5 GB with defaults). A GPU path (ExecuTorch Vulkan) exists. **Offline only, opt-in, for transition windows.** [B: [NewAgeDevs](https://newagedevs.com/blog/music-separation-on-a-phone)] |
| Stems | [deezer/spleeter](https://github.com/deezer/spleeter) | MIT ✅ | TF models, 2/4/5 stems, older quality. Workable 2-stem fallback, but a TF dependency. |
| Stems/vocals | [sigsep/open-unmix-pytorch](https://github.com/sigsep/open-unmix-pytorch) | MIT code. **umxhq weights MIT ✅**, **umxl weights CC BY-NC-SA ❌** (read) | BitChord already ships `vocals_umxhq_int8.onnx`. Extend it from a vocal *mask* to vocal-out or instrumental rendering over the mix window. |
| Real-time lightweight stems | Band-SCNet (Interspeech 2025), Online SCNet, HS-TasNet, RT-STT; the 0.86M-param distilled student from [matzelle](https://www.matzelle.co/blog/2026-09-04-four-stem-separation-on-a-raspberry-pi) | Licences unknown [?] | Band-SCNet: 2.59M params, 7.79 dB SDR, RTF 0.48 on one laptop thread. The student: 3.4 MB, about 67% of one Cortex-A72 core, 3.8 dB SDR [B]. This shows **real-time 4-stem on an ARM CPU is feasible at lower quality**. |
| Time-stretch | [Signalsmith-Audio/signalsmith-stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) | MIT ✅ (read) | Already in BitChord. Keep it as the default. Supports split computation for even CPU load. |
| Time-stretch | [breakfastquay/rubberband](https://github.com/breakfastquay/rubberband) | GPL-2.0-**or-later** ✅ (read) | R3 is top quality but heavier. Its App Store restriction is irrelevant on Android. Optional "HQ" engine. |
| Time-stretch | [SoundTouch](https://www.surina.net/soundtouch/) | LGPL-2.1 ✅ | WSOLA, very cheap, more artefacts. Ultra-low-end fallback only. |
| Time-stretch | [bungee](https://github.com/bungee-audio-stretch/bungee) | MPL-2.0 ✅ (read) | Phase vocoder, NEON, ships Android builds; granular mode makes loops and reverse easy. Worth A/B testing against Signalsmith. |
| Loudness | libebur128 (MIT) ✅ | C | For R128 LUFS and true-peak. **[? not re-fetched]** |

---

## 3. Design: DJ-level on-device automix for BitChord

### 3.1 Principles

1. **Loop mix first.** If `LoopMix.plan` qualifies, it wins, with no scoring against other styles. Test: every 1.9.1 pair that got a loop still gets one.
2. **Never late.** Preparation is deadline-driven: both decks are prepared at plan time, the transition moves to the next valid bar, and a log records which step was late and by how many ms (the fix already landed). Add NullMusic-style **adaptive lead time**.
3. **Musical first, then clever.** Phrase alignment, no overlapping lead vocals, and no cut lyric lines matter more than effects.
4. **Degrade on evidence.** Every rung below has explicit confidence gates (BitChord's tier ladder already does this).
5. **Effects rotate deterministically per pair.** Apple's iOS 26 complaint was sameness [B].

### 3.2 Analysis pipeline

Run per track when it becomes *next* in the queue (or two ahead on Wi-Fi and charging). Cache it.

| Feature | Method (recommended) | Already in BitChord? | Cost |
|---|---|---|---|
| Decode | Head 90 s and tail 120 s at full rate. Whole track at 11–22 kHz mono for coarse features. | Partial (head/tail) | Network-bound. Reuse the playback cache. |
| Beats, downbeats, drift | Beat This! final0 q8 on head and tail. **Multi-region grid fit** (bkmasterplayer `fit_grid` and `join_regions`) with onset snapping. Steady-grid flag; 2:1 and 1:2 folding. | Yes / port | Main ML cost. Small model when the thermal state is high or on low RAM. |
| Phrases | Candidate boundaries every 4/8/16 bars from the first musical bar (opendj). Scored by beat-synchronous novelty: chroma, MFCC-lite and 3-band energy over a Foote checkerboard kernel (MSAF algorithm, ported). | No | DSP, under 100 ms. |
| Sections and labels | (a) **Lyric repetition**: synced LRC lines that repeat (normalised text) mark the chorus, the first block is the verse, and gaps with no lyrics but high energy are inst/riff/drop. (b) Beat-synchronous chroma self-similarity for repeated blocks. (c) Per-bar energy and vocal share to tell intro, build, chorus, breakdown and outro apart (opendj's thin-mids rule). Labels: intro, verse, pre-chorus (the bars right before a chorus with rising energy), chorus, bridge (a one-off block after the second chorus), inst/riff, outro. | Lyrics and vocal mask exist | DSP plus lyrics, cheap. **This is BitChord's unfair advantage** over Apple's unlabelled API and all-in-one-class models [I]. |
| Key | Per region (head and tail can differ from the global key): HPCP/chroma plus key profiles (port libkeyfinder, GPL-3, or the Essentia edma profile). Store Camelot and confidence. | Field exists | Cheap. |
| Energy and loudness | Integrated LUFS and true peak (libebur128); per-bar 3-band energy (low under 200 Hz, mid 200–2.5k, high over 2.5k). | Energy curves exist | Cheap. |
| Vocal activity | umxhq vocal mask, then **per-bar vocal share** (opendj rule: share over 0.25 and above −40 dBFS means sung), merged with lyric line timings. | Mask exists | Already paid. |
| Stems (optional) | **Windowed only**: A's last up to 32 bars and B's first up to 32 bars. Tier 1: umxhq vocals, with instrumental = mix − vocals (soft mask). Tier 2 (opt-in, 8 GB+ devices): htdemucs over the same windows, roughly 2×45 s instead of a whole song. Cache the windows as Opus. | Tier 1 model present | Tier 1 is cheap. Tier 2 is heavy (RAM 2.2 GB): only while charging, or skip. |

**Cache.** Key by `(videoId, formatId/bitrate, analyzerVersion)` in Room. Store arrays as delta-coded float32, about 10–30 KB per track. Keep separate invalidation for the planner version and the analysis version, so planner changes don't force re-analysis.

### 3.3 Transition planner

The ladder runs top-down. Each rung has gates and a cost function, and the first rung that passes wins (rung 1 is absolute).

**Rung 0: eligibility.**

| Condition | Result |
|---|---|
| Album siblings, or seamless (tail and head under 0.4 s of silence, as in bkmasterplayer) | GAPLESS |
| Spoken word, live, classical, opera, or user-excluded | EQUAL_POWER or a short crossfade |

**Rung 1: LOOP MIX** (BitChord, unchanged). It needs clean instrumental 4/2/1-bar loops on both sides by lyrics and mask, and a tempo within the loop mix's own limits. Deadline-safe: if not ready, slide to the next valid bar. Only if no later bar passes, fall to rung 2.

**Rung 2: phrase-matched beatmix.**

| Gate | Threshold |
|---|---|
| Grids | Steady at A's exit and B's entry |
| Speed | Within ±6% (vocal-heavy) or ±8% (instrumental), including 2:1 |
| Phase error at the mix point | 25 ms or less |

Enumerate candidates:
- A exits: outro start, end of the last chorus, the start of each 8/16-bar phrase in the last 60 s.
- B entries: the first musical downbeat, intro phrase starts, and optionally the first chorus if the intro is over 32 bars and beatless (Apple-style skip, but **never skip a verse that has lyrics** unless the user enabled "radio edit").
- Length: 32, 16, 8 or 4 bars.

Cost, lower is better. Use per-track features plus pair scalars only (see the patent note):

```
cost = w1·vocalOverlapBars + w2·keyDistance(Camelot) + w3·|speed-1| + w4·secondsOfMusicDiscarded
     + w5·|ΔLUFS| + w6·energyJump(exit bar vs entry bar, scalar) + w7·cutsLyricLine + w8·notOnPhrase
```

Sub-styles inside rung 2:
- **Bass swap** (default). Kill B's lows; swap on the downbeat at the halfway phrase, within one beat.
- **Vocal handoff.** If vocal overlap is 1 bar or more, fade A's vocal stem or mask over the bar before B's vocal (opendj).
- **Acapella-over-instrumental** (stems tier 2 only). A's vocal rides over B's instrumental intro for up to 8 bars. Only allowed with the same or a relative Camelot key and high key confidence.
- **Intro/outro extension.** If the clean outro or intro is shorter than the planned length, repeat a clean instrumental 4/8-bar phrase. This is Apple's iOS 27 move [B], and it is effectively a generalised loop mix, so reuse `LoopMix.Clock`.

**Rung 3: key clash, tempo OK.** Shorten to 4–8 bars with a filter blend: high-pass rising on A, low-pass opening on B (bkmasterplayer rule). Or a short bass swap on a breakdown.

**Rung 4: tempo too far, or a played/drifting grid.** **Echo-out on the bar**: A cuts on its music-end downbeat, then a beat-synced delay (½ or ¾ beat, feedback about 0.55, high-pass in the loop) rings out past the end of the file while B enters on its first downbeat (bkmasterplayer `tempo_jump_plan`). Alternatives rotate per pair:
- **Loop roll** (1 → ½ → ¼ beat, 4 bars) into a cut on B's downbeat.
- Reverb wash-out.
- Rarely, a brake or spinback.

**Rung 5: unclear beats.** Equal-power crossfade of 6–8 s with silence trim.

**Rung 6: last resort.** Today's filter mix, with the late-step log.

**Patent note (not legal advice).** bkmasterplayer avoids US 8,280,539 by choosing A's exit from A's own structure and B's entry from B's own structure, and by comparing the two tracks only through scalars (BPM, key). The cost function above enumerates per-track candidates and uses pair scalars (key distance, ΔLUFS, vocal-overlap count, energy jump). It never runs a sliding-window similarity search across both tracks' descriptor sequences. Keep it that way, or get legal review if this fork is ever distributed in the US [I].

### 3.4 Rendering and effects toolkit

All effects are beat-clocked from the shared mix clock, allocation-free on the audio thread, with SIMD biquads.

| Tool | Spec | Use |
|---|---|---|
| 3-band isolator EQ | LR4 crossovers at about 200 Hz and 2.5 kHz; kill to −∞ dB; 1-beat ramps | Bass swap, mid scoop under vocals |
| Filter sweep | SVF high-pass on A from 30 Hz to 1–2 kHz over N bars, Q 0.7–1.2; low-pass on B opening from 300 Hz to 20 kHz | Key clash, energy down |
| Echo / delay | ½, ¾ or 1 beat; feedback 0.45–0.6; HP/LP in the loop; tail outlives A | Rung 4, outro throws, vocal throws |
| Flanger | LFO synced to 4–16 beats, 1–8 ms delay, feedback 0.3–0.6, wet 50% or less. Last 4–8 bars of A or a build only, at most 1 in 5 transitions | Colour (Apple: unconfirmed) |
| Loop roll / stutter | Quantised 1, ½, ¼, ⅛ beat repeats; 2–5 ms crossfades at loop seams; slip-mode return | Builds, rung 4 |
| Reverb | Small plate (Freeverb-class, public domain), high-pass 300 Hz on the send | Wash-outs, endings |
| Vocal-out / acapella | Soft mask (umxhq) or stems (htdemucs); gain automation per stem; 5 ms crossover between stem and mix (opendj) | Rung 2 vocal handoff |
| Tempo | Signalsmith with key lock always on; constant ratio during the overlap, then ramp back over 16 bars (bkmasterplayer) | All beatmixes |
| Gain | LUFS match within ±6 dB (opendj); master true-peak limiter (−1 dBTP, 1.5 ms lookahead) only during overlaps | Every transition |

### 3.5 Quality guardrails and verification

**Hard gates** (fall a rung if any fails):
- Grid confidence
- Phase error of 25 ms or less at the mix point
- Speed within the cap
- Lead-vocal overlap of 1 bar or less
- No cut lyric line
- At most one chorus of A discarded
- No verse of B skipped (unless the user opts in)
- B's entry not later than 45 s into the track

**Post-plan dry run** (opensonic-style verify), on the rendered overlap buffer when it is cheap:
- Onset cross-correlation drift of 10 ms or less over the overlap
- No clipping
- Loudness jump of 3 LU or less

**Rotation:** deterministic per pair hash, avoiding the same effect twice in a row.

**Telemetry log line per transition:** rung, style, reasons, any late step with ms overrun. Add an optional 👍/👎 on the "Mixing" chip to tune weights.

### 3.6 Performance budget (mid-range Android, e.g. Snapdragon 7-class, 6–8 GB)

| Item | Budget |
|---|---|
| Analysis start | When B becomes next, and at least 60 s before A's earliest exit (adaptive lead from 20 to 35 s, growing when preparation exceeds 8 s; NullMusic) |
| CPU | 2 ORT intra-op threads on big cores; XNNPACK or CPU EP. **NNAPI is deprecated from Android 15** ([migration guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)), so don't adopt it. QNN EP is optional on Snapdragon. |
| Memory | Beat This q8 plus umxhq int8 under 150 MB resident. For any htdemucs path, turn the ORT arena and graph optimisation off and expect about 2.2 GB, so only on 8 GB+ devices ([NewAgeDevs](https://newagedevs.com/blog/music-separation-on-a-phone)) |
| Downgrades | `PowerManager` thermal status of moderate or higher: small beat model, no stems. Battery saver: no two-ahead prefetch. |
| Audio thread | Under 25% of one core during an overlap (two Signalsmith instances plus effects); no allocations or locks |
| Storage | Analysis about 30 KB per track; stem windows LRU 200 MB, Opus 96 kbps |
| UI | Planning off the main thread; no recomposition on audio ticks (use the Compose perf skill) |

### 3.7 Prioritised roadmap

**P0 (now):**
- Keep loop-first with the "every 1.9.1 loop is still a loop" test.
- Port bkmasterplayer's multi-region grid fit, echo-on-bar with tail, the music-end rule, the 25 ms phase gate and 2:1 handling.
- Add LUFS match and the limiter.
- Add adaptive pre-arm lead time.
- Use per-bar vocal share from the existing umxhq mask, and add vocal-overlap to the planner.

**P1:**
- Lyric-repetition and self-similarity **section labels**, with section-aware exits and entries (don't skip verses; exit after the last chorus).
- Intro/outro phrase extension via `LoopMix.Clock`.
- The vocal handoff.
- Loop roll, flanger and reverb in the rotation.
- Key per region with libkeyfinder-style profiles.

**P2:**
- Windowed stems (htdemucs opt-in, or a licensed lightweight Band-SCNet-class model if one with a compatible licence appears) for acapella-over-instrumental.
- Learned EQ/fader templates from djmix-analysis statistics.
- 👍/👎 weight tuning.
- An optional "HQ stretch" engine (Rubber Band R3 or Bungee A/B).

---

## 4. Open questions and unverifiable items

- Whether AutoMix uses Music Understanding, uses stems, or uses a flanger [?].
- Weight licences for all-in-one, MuQ/MusicFM (SongFormer), Band-SCNet and the matzelle student [?].
- Phone RTF for Beat This final0 q8 over about 3.5 minutes of audio: measure it on device, because no public number was found [?].
- The UVR-MDX LiteRT claim (a 28 MB model running at about RTF 1.0 on a Snapdragon 732G) came from a search snippet only. Its Hugging Face page returned 401 [?].
