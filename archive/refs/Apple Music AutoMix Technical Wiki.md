# Apple Music AutoMix: Technical Wiki

*Compiled October 30, 2026 from public sources. No Apple binaries were inspected. Every claim carries an evidence grade so your agent can tell what is known from what is guessed.*

**Evidence grades:** **A** = Apple primary source (docs, press release, release notes). **B** = hands-on or reporting by established outlets. **C** = blog, forum or vendor page (weak). **I** = inference by the author, not stated by any source. **?** = unknown.

## 1. Summary

- AutoMix is Apple Music's DJ-style transition engine. Apple announced it on June 9, 2025 and shipped it in iOS 26. Apple describes it as time stretching plus beat matching, with timing driven by analysis of key and tempo. \[A: R1, R2\]
- It is a selector, not one effect. Apple says it picks the transition per song pair, including trimming silence or doing a plain crossfade when that is the better choice. \[A: R2\]
- Scope limits: Apple Music catalog only, no transitions inside albums or in some genres, and unavailable while Hi-Res Lossless is on. It is on by default. \[A: R2\]
- It is not gated by Apple Intelligence hardware. It runs on iPhone 11 and later, and on Macs with Apple silicon. \[A: R1, R3; B: R6, R7\]
- iOS 27 (released mid-September 2026) rebuilt the transition generator: more varied styles, remixed or extended intros and outros, and expansion to Apple TV and HomePod. \[A via B: R10; B: R9, R11\]
- Still unknown: where the analysis runs, which time-stretch algorithm is used, the stretch limits, and the planner's decision rules. Public evidence fits "pre-analysis plus on-device rendering", but no primary source says so. \[?\]

## 2. Infobox

| Field | Value | Grade |
| --- | --- | --- |
| Vendor | Apple | A |
| Type | Playback transition mode in the Music app | A |
| Announced | June 9, 2025 (WWDC25, iOS 26 press release) | A |
| First shipped | iOS 26, autumn 2025 | A |
| Latest major revision | iOS 27, September 2026 | B |
| Default state | On | A |
| Alternatives | Crossfade (user-set seconds), or off | A |
| UI indicator | A small "Mixing" animation on Now Playing | B |
| Where to toggle | iOS: Settings > Apps > Music > Song Transitions, or in the queue. Mac: Music > Settings > Playback > Song Transitions | A |

## 3. Timeline

| Date | Event | Grade |
| --- | --- | --- |
| 2025-06-09 | Announced in the iOS 26 press release | A |
| 2025-06 | Developer beta hands-on goes viral; early complaints about aggressive or badly timed mixes | B |
| 2025-07 | iOS 26 public beta; reports of songs being cut short | B |
| 2025 autumn | iOS 26 ships with AutoMix on by default | A |
| iOS 26.1 | Release notes add AutoMix support over AirPlay | A |
| 2026-06-08 | iOS 27 announced: upgraded algorithms, new transition types, AutoMix coming to Apple TV and HomePod | B |
| 2026-07-16 | iOS 27 public-beta hands-on: the iOS 26 "underwater" sound largely gone; intros and outros remixed | B |
| 2026-09-14 | iOS 27 and HomePod 27 released; HomePod 27 release notes list AutoMix | B |

I did not retrieve a release-day confirmation for tvOS 27.

## 4. User-visible behavior

### 4.1 Modes and controls

Three states exist: AutoMix, Crossfade, and off. \[B: R6\] Crossfade remains available in iOS 27. \[B: R10\] AutoMix can be switched off from the queue or from Settings. \[A: R2\]

### 4.2 What a transition can be

Apple says AutoMix chooses the transition type from the music itself. Examples it gives: removing silence at the start and end of a track, or using a simple crossfade instead of a complex blend. \[A: R2\] MusicTech reports Apple saying AutoMix declines to transition when no seamless option exists. \[B: R8\]

### 4.3 Sound character by version

- **iOS 26:** a similar, predictable style for most transitions, with a recurring "underwater" effect. \[B: R9\] Reviewers also report added reverb and vocals that linger after the other elements fade. \[B: R14\] Beta testers described strong speed changes from the beat matching. \[B: R27\]
- **iOS 27:** more varied and less abrupt. It remixes outros and intros to align tempo and repeats parts of them to bridge the gap. \[B: R9\] Apple's own line says it adds more energy with more immersive transitions. \[A via B: R10\]

### 4.4 When it does not mix

- Albums and some genres play without transitions. \[A: R2\] MusicTech adds that consecutive tracks from the same album are not mixed. \[B: R8\]
- Only Apple Music catalog tracks. \[A: R2\]
- Not while Hi-Res Lossless is enabled. \[A: R2\]
- Blog claims about Dolby Atmos or local files being excluded are unverified. \[C: R29\]

## 5. Availability matrix

| Platform | Status | Grade |
| --- | --- | --- |
| iPhone | iOS 26 and later | A |
| iPad | iPadOS 26 and later | A |
| Mac | Apple silicon only, macOS 26 and later | A |
| Apple Vision Pro | visionOS 26 and later | A |
| AirPlay output | Supported from iOS 26.1 | A |
| HomePod | Crossfade only on version 26; AutoMix on version 27 | A/B |
| Apple TV | Announced for tvOS 27 | B |
| Windows app | Not in Apple's platform list | A (omission) |
| Android | No evidence found | ? |
| Offline downloads | Claimed to work; unverified | C |

## 6. How it works: technical model

### 6.1 Pipeline model

Figure 1 (end of document) shows the model used below. The grade on each stage is how much of it is documented.

1. **Analyze:** tempo and key per track \[A\]; silence at the edges \[A, implied by silence trimming\]; beat grid, downbeats and phrase boundaries \[I\].
2. **Plan:** pick a transition point and type \[A: outcome only; rules are ?\].
3. **Render:** pitch-preserving time stretch, beat alignment, crossfade \[A\]; reverb or filter-like colouring \[B\]; intro and outro extension in iOS 27 \[B\].
4. **Output:** the mixed signal goes to the normal audio path, including AirPlay \[A: support exists; mechanism I\].

### 6.2 Stage 1: analysis

Apple's support text says transitions are based on key and tempo analysis. \[A: R2\] A third-party blog adds amplitude, spectrum and drum structure, but cites nothing. \[C: R29\]

A design constraint from older beat-matching patents applies to any implementation: tempo detectors tend to jump to a harmonic or sub-harmonic of the true tempo, and extreme conversion ratios sound unnatural. \[B: R19\] A planner therefore has to handle half-time and double-time readings and cap stretch ratios. How Apple does this is unknown. \[?\]

### 6.3 Where the analysis runs: competing evidence

| Model | Evidence for | Evidence against or missing |
| --- | --- | --- |
| On-device analysis | MacRumors' summary of Apple's claim says analysis is on device \[B: R7\]; Music prepares upcoming tracks before playback starts \[C: R26\]; an open-source clone shows beat analysis is heavy enough to need background threads \[C: R24\] | No Apple primary source states it |
| Precomputed server metadata shipped with the catalog | AppleMagazine argues it is likely, with no evidence \[C: R28\] | Speculation only |
| Hybrid (server pre-analysis, device-side refinement and rendering) | Compatible with everything above | Unfalsified by current evidence |

Conclusion: unresolved. Treat it as the first test target (Section 11).

### 6.4 Stage 2: planning

No public description exists. Known outputs: silence trim, simple crossfade, beat-matched blend, and (iOS 27) intro or outro extension. \[A, B\] Plausible inputs, all \[I\]: tempo ratio including half and double time, key distance, energy and silence at the boundaries, and how repetitive the outro and intro are. An open-source analogue falls back to a plain crossfade whenever its analysis is unclear, which mirrors Apple's own statement about choosing simpler transitions. \[C: R24; A: R2\]

### 6.5 Stage 3: time stretching

Time stretching changes duration without changing pitch. \[B: R22\] Two families dominate:

- **Time-domain (SOLA, WSOLA):** fast and good on quasi-harmonic material, with phase-jump artifacts on complex signals. \[B: R23\]
- **Phase vocoder:** works in the frequency domain and is known for transient smearing and "phasiness" (an echoey loss of presence). \[B: R23\]

Which family AutoMix uses is unknown. \[?\] The iOS 26 "underwater" colour is consistent with phasiness, a deliberate filter or reverb, or both. This is a hypothesis, not a finding. \[I\]

### 6.6 Key handling

Apple says key analysis informs transitions. \[A: R2\] No source says AutoMix pitch-shifts to fix key clashes; press descriptions say tempo changes without pitch changes. \[B: R5\] In DJ practice, harmonic mixing favours keys that are close on the circle of fifths (often shown in Camelot notation). Whether AutoMix uses such a rule is unknown. \[?\]

### 6.7 Stage 4: output path

iOS 26.1 added AutoMix over AirPlay. \[A: R4\] HomePod on version 26 had crossfade only, and version 27 added AutoMix natively. \[B: R12; A/B: R11\] Together this suggests the mix can be rendered at the source and streamed, and that the engine also lives in the HomePod and Apple TV playback stacks. \[I\] AutoMix is blocked under Hi-Res Lossless for an undisclosed reason; a fixed render format is a plausible cause. \[I\]

### 6.8 Compute and hardware

iPhone 11 and later, and Apple-silicon Macs only. \[A: R1, R3\] It is not an Apple Intelligence feature. \[B: R6, R7\] Apple's "intelligence" wording in the press release is lowercase, while MusicTech claims Apple Intelligence powers it. \[A: R1; B: R8\] The device floor suggests classical DSP plus small learned estimators, not a large language model. \[I\] The Apple-silicon-only Mac limit hints at a compute need or a platform simplification. \[I\]

## 7. Public Apple audio stack (documented, not confirmed as used)

| Layer | API | Role | Grade |
| --- | --- | --- | --- |
| AVFoundation | AVAudioTimePitchAlgorithm: spectral (highest quality, suited to music), timeDomain (modest quality, suited to voice), varispeed (no pitch correction), lowQualityZeroLatency | Pitch-preserving rate change in AVPlayer | A: R17 |
| AVFoundation | Default for playback in apps linked on iOS 15 or later: timeDomain; offline default: spectral | Defaults | A: R17 |
| AVFoundation | timeDomain supports a variable rate from 1/32 to 32 | Rate range | A: R17 |
| AVAudioEngine | AVAudioUnitTimePitch maps to the NewTimePitch audio unit, equivalent to spectral | Effect node | B: R18 |
| Audio Units | Other time effects (for example the iPod time units) can be wrapped via AVAudioUnitTimeEffect | Alternatives | B: R18 |
| MusicKit / MediaPlayer | No documented AutoMix control found (search was not exhaustive) | n/a | ? |

The Music app is not obliged to use any of these. They show what Apple ships publicly, which bounds what a first-party engine would plausibly build on.

## 8. Prior art and patents

None of these is shown to implement AutoMix. They document the problem space.

- **US 7,767,897 / 7,518,053, "Beat matching for portable audio":** defines the beat-matched crossfade, discusses tempo-detector octave jumps, and contrasts time-scale modification with sample-rate conversion. \[B: R19\]
- **US 8,280,539, "Method and apparatus for automatically segueing between audio tracks":** says beat matching alone may not give the smoothest transition, which motivates extra planning. \[B: R20\]
- **US 8,269,093, "Method for creating a beat-synchronized media mix":** argues players lacked automatic beat sync and proposes analyzing tempo across a collection. \[B: R21\]
- **Adjacent, video:** a 2026 Apple application selects transition duration from the next item's characteristics. It concerns video content, not AutoMix. \[C: R31\]

Assignees were not confirmed from the retrieved text. Verify before citing as Apple's.

## 9. Known issues and failure modes

Most reports come from the iOS 26 beta and early release. Treat severity as dated.

| Issue | Report | Grade |
| --- | --- | --- |
| Works better on some genres | Apple says some genres skip transitions; press says results vary | A/B |
| Odd pairings sound jarring (Kate Bush into Limp Bizkit) | heise | B |
| Cuts songs short | TechRadar | B |
| Reverb and lingering vocals | GB News | B |
| Complex or false endings confuse it | Blog and sample clips | C |
| "Mixing" appears but no mix happens; AirPlay glitches | Apple Support Community thread | C |
| Strong speed changes in beta | Hands-on review | B |
| Disabled with Hi-Res Lossless | Apple support | A |

## 10. Comparable systems

| System | Approach | Notes |
| --- | --- | --- |
| Apple Crossfade | Fixed overlap of user-chosen seconds | A: R2 |
| Apple AutoMix | Analysis, planner, stretch, effects | This document |
| Spotify Mix | DJ-style playlist transitions with visible BPM, key, waveform and an adjustable transition point | B: R25 |
| NullMusic AutoMix (open source) | BPM and beat-grid analysis, tempo-matched crossfade, plain-crossfade fallback, analysis moved to a background thread pool | C: R24 |
| Third-party DJ apps | Analyze loaded tracks themselves; some streaming catalogs expose no BPM or key | C: R30 |

## 11. Hypotheses and test plan (for your agent)

### 11.1 Hypotheses

| ID | Hypothesis | Falsifiable test |
| --- | --- | --- |
| H1 | Beat grid and key are precomputed server-side and delivered with catalog data | Look for tempo, key or beat-grid fields in catalog payloads and app caches; compare first-play and repeat-play transition quality; watch CPU during pre-roll |
| H2 | Rendering uses Apple's NewTimePitch (spectral) unit | Inspect imports and audio-component descriptions in Music's binaries; compare spectrogram artifacts to a reference spectral stretch |
| H3 | The planner picks among trim, crossfade, beat-matched and extended mixes using tempo ratio and key distance | Controlled pairs at BPM ratios 1.00, 1.02, 1.05, 1.10, 1.50, 2.00 and key relations; log type and length of each transition |
| H4 | iOS 27 extends intros and outros by repeating bars | Beat-synchronous chroma self-similarity inside the transition window |
| H5 | The "underwater" sound is low-pass plus reverb, not only phasiness | Spectral roll-off and reverb tail measured during overlap on iOS 26 vs 27 |
| H6 | Over AirPlay the mix is rendered at the source | Capture the AirPlay stream; check for a single continuous stream with the mix baked in |
| H7 | Genre exclusions follow catalog genre tags | Genre-labelled playlists mapped against transition occurrence |
| H8 | Hi-Res Lossless blocks AutoMix because of render format limits | Toggle quality levels and log output sample rate and bit depth |

### 11.2 Black-box protocol

1. Build playlists of catalog tracks (AutoMix needs catalog tracks \[A: R2\]) covering BPM ratios, key relations, genres and ending types (cold stop, fade-out, false ending).
2. Record the device's output at a fixed sample rate with timestamps.
3. Per pair, extract: transition start and end, overlap length, tempo trace before and after (stretch ratio = output tempo / input tempo), chroma trace to confirm pitch is preserved, spectral roll-off, and a transient-smearing measure.
4. Repeat across iOS 26.x vs 27, macOS vs iPhone, and direct playback vs AirPlay. Use Crossfade as the control.

### 11.3 Static-analysis targets (for REA)

- Music app on macOS 26.x and 27: strings such as "AutoMix", "Song Transitions" and "Mixing"; audio-unit instantiation; any bundled ML models and their input and output shapes; stretch-ratio constants and clamps.
- Diff the 26.x and 27 builds to isolate the new transition-generation code.
- Settings keys behind the Song Transitions control.

### 11.4 Constraints

Catalog audio is DRM-protected and Apple's software license restricts reverse engineering in some jurisdictions. Check the license terms and keep this to personal interoperability research. Black-box measurement is the lower-risk path.

## 12. Open questions

1. Does analysis run on the server, on the device, or both?
2. Which time-stretch algorithm and what stretch-ratio limits?
3. How does the planner choose among transition types?
4. What exactly counts as "some genres" that skip transitions?
5. Why is Hi-Res Lossless excluded?
6. Is any learned model involved, or is it classical signal processing?
7. Is the mix rendered at the source or the receiver over AirPlay?
8. Does AutoMix work with downloaded tracks offline?

## 13. Glossary

- **BPM:** beats per minute, the tempo.
- **Beat grid:** timestamps of each beat in a track.
- **Downbeat:** the first beat of a bar.
- **Phrase:** a group of bars, typically 4 or 8, that DJs align when mixing.
- **Time-scale modification (TSM):** changing duration without changing pitch.
- **Phase vocoder:** frequency-domain TSM method.
- **WSOLA / SOLA:** time-domain TSM methods based on overlap-add.
- **Crossfade:** one track fades out while the next fades in, with no tempo matching.
- **Harmonic mixing:** choosing transitions between musically compatible keys.

## 14. References

- R1 Apple Newsroom, iOS 26 press release, June 9, 2025: https://www.apple.com/newsroom/2025/06/apple-elevates-the-iphone-experience-with-ios-26/
- R2 Apple Support, Transition songs in Music on iPhone: https://support.apple.com/en-my/guide/iphone/iphadf2fe1f4/ios
- R3 Apple Support, Transition songs in Music on Mac: https://support.apple.com/en-ca/guide/music/muse5e9ec085/mac
- R4 Apple Support, About iOS 26 Updates: https://support.apple.com/en-kg/123075
- R5 MacRumors, June 12, 2025: https://www.macrumors.com/2025/06/12/ios-26-automix-apple-music/
- R6 9to5Mac, June 16, 2025: https://9to5mac.com/2025/06/16/automix-apple-music-ios-26/
- R7 MacRumors, Eight New Features in Apple Music (forum copy): https://forums.macrumors.com/threads/ios-26-eight-new-features-in-apple-music.2463858
- R8 MusicTech, Apple Music iOS 26 updates: https://musictech.com/news/music/apple-music-ios-26-updates/
- R9 9to5Mac, iOS 27 AutoMix hands-on, July 16, 2026: https://9to5mac.com/2026/07/16/ios-27-makes-one-of-my-favorite-apple-music-features-even-better/
- R10 9to5Mac, iOS 27 Apple Music features, June 10, 2026: https://9to5mac.com/2026/06/10/ios-27-heres-all-the-new-apple-music-features/
- R11 9to5Mac, HomePod 27 release, September 14, 2026: https://9to5mac.com/2026/09/14/apple-releases-homepod-27-with-two-new-improvements/
- R12 MacRumors, iOS 27 and HomePod: https://www.macrumors.com/2026/06/10/ios-27-new-functionality-to-homepod/
- R13 heise, AutoMix oddities: https://heise.de/-11139247
- R14 GB News: https://www.gbnews.com/tech/apple-music-automix-ios-26
- R15 TechRadar (via inkl): https://www.inkl.com/news/apple-music-fans-are-obsessed-with-automix-in-ios-26-but-one-big-flaw-could-be-its-downfall
- R16 Apple Support Community thread: https://discussions-kr-prz.apple.com/thread/256143899
- R17 Apple Developer, AVAudioTimePitchAlgorithm: https://developer.apple.com/documentation/avfoundation/avaudiotimepitchalgorithm.md and https://developer.apple.com/documentation/avfoundation/time-pitch-algorithm-settings.md
- R18 Apple Developer Forums, AVAudioUnitTimePitch: https://developer.apple.com/forums/thread/5874
- R19 USPTO 7,767,897: https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/7767897
- R20 USPTO 8,280,539: https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/8280539
- R21 USPTO 8,269,093: https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/8269093
- R22 Wikipedia, Audio time stretching and pitch scaling: https://en.wikipedia.org/wiki/Audio\_time\_stretching\_and\_pitch\_scaling
- R23 Phase vocoder artifacts: https://arxiv.org/pdf/2202.07382 ; TSM thesis: https://www.audiolabs-erlangen.com/content/05\_fau/professor/00\_mueller/01\_group/2011\_DriedgerJonathan\_TSM\_MasterThesis.pdf
- R24 NullMusic release notes: https://github.com/NullCoreDeveloper/NullMusic/releases
- R25 MusicRadar, Spotify Mix: https://www.musicradar.com/music-tech/spotify-responds-to-apple-musics-new-automix-feature-by-letting-you-turn-your-playlists-into-ready-made-dj-sets-with-seamless-transitions
- R26 Audex Trace release notes: https://github.com/audexdev/trace-releases/releases
- R27 Hands-on review (Yahoo): https://currently.att.yahoo.com/att/tried-apple-musics-dj-feature-153000036.html
- R28 AppleMagazine analysis: https://applemagazine.com/everyones-going-crazy-about-apple-automix-heres-why/
- R29 Musconv blog: https://musconv.com/blog/ios-26-apple-music-automix/
- R30 Algoriddim community, streaming analysis: https://community.algoriddim.com/t/how-do-i-analyze-tracks-in-tidal-playlists/9220/27
- R31 Patentlyze, Apple transition patent application: https://patentlyze.com/patent/apple-ai-generated-media-collections-smart-transitions/

## Figures

&#91;embedded content: AutoMix pipeline model · 4 stages, 1 open question\]

**Figure 1.** The four-stage pipeline model from Section 6.1. Grades show how much of each stage Apple or the press documents; the open question is where analysis runs.

&#91;embedded content: transition decision flow · 1 gate, 3 outcomes\]

**Figure 2.** The transition decision flow. The eligibility gate and the three outcomes come from Apple's documentation; the planner's internal rules are not public.

&#91;embedded content: release timeline · 7 milestones\]

**Figure 3.** Release timeline from the Section 3 table, spaced evenly rather than to scale.
