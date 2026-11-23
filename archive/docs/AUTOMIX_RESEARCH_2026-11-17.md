# Apple AutoMix and BitChord: evidence, audio engineering, and Snapdragon feasibility

Research date: **2026-11-17**. BitChord source examined: **`c2c7f32180b3e9c28922b384fc6c20a34135171f`**, branch **`vc77-ci`**.

This is a research deliverable following [the stopped vc78 handover](AUTOMIX_HANDOVER_2026-11-17.md). The new request authorizes learning, council analysis, and read-only examination of saved evidence. No production changes, Android/native builds, device commands, installation, playback, commit, or push were performed for this report. The earlier handover and `REMAINING_WORK.md` remain intact.

**Main finding:** Apple publicly confirms music-dependent transition selection, key/tempo analysis, beat matching, and time stretching. Current reporting also supports phrase repetition, looping, reverb, and delay. Its exact stem separation, flanger, semantic labeling, analysis placement, and Neural Engine implementation remain undisclosed in the reviewed sources. A credible BitChord implementation must combine musical judgment, preparation, DSP quality, and continuous playback; adding effects or enabling an NPU alone does not establish any of those outcomes.

**Your Snapdragon 8s Gen 3 does have a Hexagon NPU.** Qualcomm identifies the platform as SM8635 and lists its neural accelerator in the product brief [S07]. Current BitChord deliberately uses CPU inference. That is a software/deployment state, not a limitation of the phone's hardware.

## 1. Evidence rules and research scope

The report distinguishes four kinds of statement:

- **Primary documentation:** what Apple, Qualcomm, Android, or a project's maintainers actually disclose.
- **Reported behavior:** third-party listening observations or reproduced Apple UI text, retaining that provenance.
- **Source or saved runtime evidence:** what the current BitChord code and stored logs establish.
- **Engineering recommendation:** a proposed way to build or evaluate comparable behavior; it is not a claim about Apple's private implementation.

The orchestrator fetched primary and secondary sources with TinyFish Search/Fetch and independently checked consequential source locations. Council seats inspected local materials; their tool access did not provide independent web retrieval. Shared source evidence is one evidence base, not four separate confirmations. No reverse engineering of Apple's binary, new Apple listening experiment, or device benchmark was performed.

The older `refs/automix-research.md` was useful for source discovery, but it contains proposals, historical package versions, and stronger claims than its evidence warrants. This report does not inherit its thresholds, model memory estimates, effect-rotation policy, or parity claims as established facts.

Selected complete fetch responses retained outside git:

- `../automix-research-2026-11-17/apple-and-structure-sources.json`
- `../automix-research-2026-11-17/npu-and-dsp-sources.json`
- `../automix-research-2026-11-17/pinned-qnn-and-evaluation-sources.json`

These paths are relative to the repository root, not this document's directory. Source links and their limits are listed in section 10.

## 2. What we can establish about Apple AutoMix

| Feature or question | Evidence and confidence | What it does not establish |
|---|---|---|
| Beat matching and time stretching | **Direct Apple confirmation.** The iOS 26 announcement names both operations [S01]. | The stretch algorithm, transient treatment, tempo bounds, or rate trajectory. |
| Key/tempo-aware transition choice | **Direct Apple confirmation.** Mac playback settings say transitions occur at the right moment based on key and tempo analysis [S03]. | Pitch shifting, exact harmonic rules, local versus global analysis, or a learned planner. |
| Adaptive restraint | **Direct Apple confirmation.** AutoMix may trim silence or choose a simple crossfade when appropriate [S02]. | A mandate to process every pair or always use the most elaborate transition. |
| Phrase repetition and intro/outro extension | **Credible hands-on report.** 9to5Mac describes repeated parts of intros/outros in iOS 27 [S06]. | Exact segmentation, repeat count, seam algorithm, or generated new musical content. |
| Looping, reverb, delay | **Apple UI wording reproduced by a third party.** BetaProfiles quotes these effects in the iOS 27 beta-5 splash screen [S05]. | Independent binary verification, full effect list, parameter curves, or routing. |
| Improved 2026 transitions | **Direct Apple confirmation**, but qualitative: its services announcement describes improved transitions and tvOS/HomePod availability [S04]. | Measured superiority across recordings or a public architecture disclosure. |
| Flanger, phaser, bass-stem swaps | **Not established by the reviewed evidence.** | Hearing a swirling or bass-changing sound does not identify its algorithm. |
| Vocal/instrumental separation in AutoMix | **Not established.** | Vocal activity metadata and the separate Apple Music Sing feature do not prove AutoMix receives separated PCM. |
| Named intro/verse/prechorus/chorus/bridge/riff/build/drop labels | **Not established for AutoMix.** | Good cue selection can use boundaries and arrangement features without explicit semantic labels. |
| On-device versus service analysis | **Not disclosed for the whole AutoMix pipeline in reviewed sources.** | Catalog eligibility does not prove server analysis; an on-device framework does not prove AutoMix uses it. |
| Neural Engine/NPU execution | **Not established for AutoMix.** | Hardware availability, Apple Intelligence branding, and actual accelerator dispatch are separate facts. |

Apple's current support article also constrains fair comparisons: AutoMix is for Apple Music subscription content, may avoid dynamic transitions for sequential albums or incompatible genres/tempos, and may not dynamically mix uploaded or purchased/downloaded iTunes content. AutoMix and Crossfade are unavailable with Hi-Res Lossless. The article explicitly excludes Android, Windows, Apple Watch, and Intel Macs from AutoMix availability [S02]. This says nothing about whether a separate Android app can implement similar audio techniques.

Apple's smooth results are consistent with selecting compatible passages, aligning their musical timing, managing their overlap, and declining unsuitable transformations. Public evidence does not let us assign a causal percentage to any component or promise that Apple succeeds on every pair.

## 3. Apple's public music-analysis capabilities: useful, but not an AutoMix blueprint

Apple's **Music Understanding** framework is unusually relevant. Its WWDC26 session and documentation explicitly describe analysis that runs entirely on device and works offline [S08, S09]:

| Analysis | Public output | Relevance to mixing |
|---|---|---|
| Rhythm | Beat timestamps, bar timestamps, global BPM | Align pulses and bar starts; retain the actual timestamps rather than rebuilding a grid from one BPM. |
| Key | Tonic/mode over time ranges | Avoid assuming a song's ending has the same harmony as its beginning. |
| Structure | Hierarchical sections, segments, phrases as time ranges | Align musical sentences and larger changes. |
| Pace | Perceived energy/momentum, distinct from fixed BPM | A breakdown and a drop can share BPM while feeling very different. |
| Instrument activity | Presence intervals and time-varying strength | Find space for vocals, bass, drums, or another foreground part. |
| Loudness | Integrated, momentary, short-term, and peak information | Match perceived level and identify sudden changes. |

The session explains sections using examples such as chorus, verse, intro, and bridge, but the demonstrated structure API returns **unlabelled ranges**, not a semantic label per section. Instrument activity is a measurement of presence/intensity, not isolated audio. The session explicitly names **Final Cut Pro** as a consumer; it does not name AutoMix.

This is evidence that Apple has public technology for the right analytical dimensions. It is not evidence of AutoMix's dependency graph, model architecture, execution provider, or server/device allocation.

## 4. How to engineer the natural transitions the user describes

The following is general audio and DJ engineering, informed by primary DSP and MIR references [S12–S16]. It is a proposed model for BitChord, not a recovered Apple algorithm.

### 4.1 Musical timing has several levels

Matching BPM is only the first level. Two songs can have the same BPM while their kicks are offset, their bar starts disagree, or one chorus arrives halfway through the other's musical sentence.

A useful planner needs beat phase, downbeat phase, phrase boundaries, and local tempo over the actual overlap. It must distinguish a vocal pickup before the downbeat from unwanted leading silence. Half/double-tempo alternatives need examination of accent patterns and bar positions, not just a numerical ratio. Live drums and rubato require a locally varying time map or a transition that does not demand prolonged synchronization.

Phrase length is a musical hypothesis. Regular dance music often supports multi-bar patterns; that does not justify assuming every recording is 4/4 or every section begins on an eight-bar grid. The DnB automatic-DJ research explicitly benefits from genre-specific assumptions and does not establish general performance across pop, classical, live, and spoken material [S14].

### 4.2 Section awareness should support musical intent

Boundary detection, recurrence detection, and semantic labeling are different tasks. Novelty detects a change; self-similarity detects repeated texture/harmony; a semantic model or heuristic interprets that repetition as verse, chorus, or another function. MSAF separates boundary detection from label clustering [S16].

For BitChord, the useful representation is a time interval with evidence: boundary confidence, repetition group, local tempo/key, vocal prominence, bass/percussion occupancy, loudness, density, and optional functional label. Missing measurements must remain missing.

The requested concepts are not all mutually exclusive section types:

- Intro/outro describe position and function.
- Verse, prechorus, chorus, and bridge describe organization and lyrical/harmonic roles.
- Build, breakdown, and drop describe tension and energy events.
- Riff and hook describe motifs that may recur inside several section types.

A rising energy curve alone does not prove a buildup. Repeated lyrics support a chorus hypothesis but do not guarantee it. A riff can survive an instrumental swap only if its timing and harmonic purpose are respected.

### 4.3 Instrumental swaps and vocal handoffs

With aligned vocal estimate `v(t)` and residual `i(t) = x(t) - v(t)`, an implementation can retain one song's vocal while introducing another song's accompaniment. It can also duck only a conflicting vocal instead of muffling the whole mix. The residual includes all remaining sources and separation error; it is not a set of clean bass, drum, and instrument tracks.

Good use requires acceptable leakage, no truncated consonants or sustained notes, matched sample rate and delay, stereo coherence, and sufficient context around model windows. Overlap/context blending is needed where independently processed windows meet. Separation's intended musical purpose should be judged in the mix as well as in solo stems.

The identity `v + (x - v) = x` holds for the chosen aligned signal domain. If the dry path is at 48 kHz while both estimates travel through 44.1 kHz and back, endpoint completion alone does not prove reconstruction against the untouched dry signal. Independent lossy stem encoding also weakens complement reconstruction. These are reasons to measure alignment and reconstruction through the real path.

A vocal handoff should protect the foreground sentence. Sometimes the best choice is to wait for a vocal gap. Sometimes restrained stem attenuation is preferable. For a cold vocal entrance, a shorter transition may preserve meaning better than a long overlapping blend.

### 4.4 Bass swap: frequency control versus source control

An EQ bass swap reduces one track's low-frequency contribution while bringing in the other's around a suitable downbeat. This is relatively inexpensive DSP and can avoid two competing kicks/basslines dominating simultaneously.

A bass **stem** swap independently controls an estimated instrument. It is more selective but requires an additional separation output. Low frequencies also contain kick drums and parts of other instruments; bass harmonics extend above the low band. EQ and source separation are therefore different tools.

Crossovers, phase response, gain ramps, and arrangement all affect the result. A fixed cutoff or swap duration is not universally musical. The handoff should be chosen around actual occupancy and arrival, with headroom for overlap and resonance.

### 4.5 Time stretch, resampling, and the meanings of interpolation

These operations must not be conflated:

1. **Sample interpolation/resampling** estimates values between samples. Proper sample-rate conversion preserves duration and pitch when moving between sample rates. Reading source samples faster into a fixed output rate changes tempo and pitch together.
2. **Pitch-preserving time stretching** changes duration without intentionally moving the musical key. Time-domain methods such as WSOLA align overlapping waveform regions; spectral methods manage phase evolution and transients. Their artifacts differ with material.
3. **Automation interpolation** smoothly changes gain, filter frequency, tempo ratio, or effect amount.
4. **Fractional-delay interpolation** supports delays between sample positions, useful in a flanger and alignment.
5. **Phrase reuse** repeats recorded material. It does not imply generated or interpolated new music.

BitChord already contains Signalsmith Stretch. Its public documentation explicitly distinguishes input and output latency, explains seek/preroll/flush behavior, and requires automation to be aligned with processing time [S12]. Correct use of an existing engine matters before replacing it.

With playback-rate ratio `r(t)`, source position follows `p(t) = p(0) + integral(r(t) dt)`. A changing tempo must be reflected in cue prediction, decoded position, stretch latency, and output alignment. Assuming a single final ratio across the whole interval creates drift.

Transients, sustained tones, and vocals stress stretching differently. Stereo channels need coherent treatment to preserve the image. A return to natural tempo needs a musically tolerable trajectory and continuous processing state. Rubber Band's R2 technical notes illustrate transient phase resets and phase-coherence handling; they do not describe Apple's engine, and the notes explicitly distinguish R2 from R3 [S13].

### 4.6 Looping and EDM effects

An effective loop extends a compatible musical phrase. Its start/end need agreement in rhythm, harmonic progression, vocal content, and signal continuity. A short waveform crossfade can remove a click but cannot repair a repeated half-word or an unresolved chord progression.

A loop roll repeats increasingly short beat subdivisions to build tension. A slip-style implementation may keep the underlying timeline progressing so playback can return to the intended musical position. Quantization, release timing, and click-free windowing matter at every subdivision.

A flanger mixes the signal with a short modulated delay, creating moving comb-filter notches. Its delay interpolation, feedback, wet amount, and stereo behavior need control. It is intentionally audible coloration; use around a suitable arrangement event, not simply because both tracks fall in an EDM-like BPM range. Reverb or low-pass filtering can also sound “underwater,” so that description alone does not identify a flanger.

Echo/delay can carry a final vocal or percussion event across a tempo change. Reverb can dissolve an ending into a sparse entrance. In both cases, **stopping the send is different from stopping the already-ringing output**. Tail ownership must survive track retirement, and the release must account for scheduled endings and interruption.

The higher-value decision is whether the effect improves this particular musical handoff. Pair-hash rotation creates variety but supplies no evidence that an effect fits the passage.

### 4.7 Loudness, headroom, and the final playback seam

For `y = gA*A + gB*B`, signal power includes the correlation term:

`P(y) = gA² P(A) + gB² P(B) + 2 gA gB Cov(A,B)`.

Equal-power fades approximate constant power for similarly powered, uncorrelated sources. They are not universally constant-loudness fades. At the midpoint, identical aligned signals under equal-power gains can sum about 3 dB above one source. An aligned linear fade between identical signals preserves amplitude; delayed or differently processed versions can still comb-filter.

Use local loudness and spectral measurements as well as whole-track normalization. Preserve musical dynamics while managing overlap headroom and effect resonance. A limiter can catch peaks but cannot fix a wrong phrase or a sudden tonal opening.

The renderer-to-decoder handover needs the same scrutiny as the mix itself: source sample position, rate, algorithmic latency, gain, filter response/state, stereo image, and pending wet tails. A logical timestamp agreement is not an output-waveform measurement. Longer fades cannot reliably conceal a backward seek or an abrupt change from processed to dry playback. Candidate approaches include continuous rendering through the join or a measured, aligned, warmed handover; selection requires captured evidence.

## 5. What BitChord already has, and what the current evidence exposes

The application already contains beat/downbeat analysis, a vocal estimator and two-part separation path, structure heuristics, phrase planning, Signalsmith stretching, bass/filter automation, looping, flanger, echo, and reverb. The absence of an effect library is not the established cause of failure.

The findings below were checked against actual source spans. They are **code behaviors or measurement gaps**, not proven explanations of the user's heard defect.

| Finding | Source pointer at `c2c7f32` | Consequence for investigation |
|---|---|---|
| The completion verifier receives `0.0` loudness delta and `0.0f` peak; unavailable phase defaults to zero. | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:5427`; `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixVerify.kt:12` | `mix.verify.ok=true` does not measure output loudness/peak and can hide absent phase data. |
| `finishLoop()` corrects a READY player's position when drift exceeds 150 ms, then disarms the ports. | `CrossfadeController.kt:5384` | Inspect the actual rendered-to-decoded join around this operation. A seek may be necessary for position, but its audible consequence is not established by routing tests. |
| Filter flush clears states and snaps current cutoff/mid values to targets. | `app/src/main/java/com/music/bitchord/playback/TransitionFilterProcessor.kt:335` | Startup initialization and an audible seek/flush need distinct continuity evidence. |
| Abnormal recovery explicitly allows an audible survivor to open its filters; the comment describes approximately 30 ms. | `CrossfadeController.kt:5895` | That duration is code commentary, not a measured universal musical recovery time. |
| The PCM structure pass builds equally spaced four-beat bars from time zero, assigns confidence from spectral activity, and sets tempo stable. | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt:123` | A numeric BPM does not establish actual downbeat phase. A high synthetic confidence can make an incorrect cue look trustworthy. |
| The production caller supplies scalar DSP BPM, with no mask/lyric prior in that call; the controller prefers the cached map. | `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt:1335`; `CrossfadeController.kt:4354` | Measured model downbeats are not automatically the grid used by this structure path. |
| Vocal enrichment changes sung bars/section vocal fractions but preserves labels, roles and loopability. Uncovered values can become zero. | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMapEnrichment.kt:74` | Recheck dependent structure conclusions and coverage at every selected cue. Unknown is not measured silence. |
| Rescue can return a beat/echo choice at the minimum score after strict candidates fail. | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt:259` | Review which rejected safeguards still apply. Natural playback needs a viable place among candidate outcomes. |
| EDM classification uses tempo range and tempo difference; effect selection adds vocal/template gates and pair hashing. | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt:71`, `:137` | A detected buildup/drop or phrase-specific effect opportunity is not established by those gates. |
| “Interpolation” fingerprints energy level plus a sung bit. | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/InterpolationAnalyzer.kt:19` | Matching fingerprints do not prove identical riffs, harmonic interchangeability, or safe loop boundaries. |
| The upstream vocal curve averages target/mix magnitude ratios over a frequency band. | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt:350` | Its later `vocalEnergy` name does not make it calibrated vocal loudness or intelligibility. |
| The native limiter scans existing samples within the current render block; its peak scan does not reconstruct intersample peaks or read the written ring buffer. | `native/loopmix/loop_mix_renderer.cpp:1008` | Its “true-peak” comment is not evidence of validated true-peak limiting. Characterize peaks and callback-partition behavior [S20]. |

Additional council leads—sparse head/tail bar indexing, cache identity, generation-owned timing, and inference deadlines—remain relevant, but are not grounds for a broad patch in this research pass. The handover already records several of these risks and their evidential limits.

## 6. The saved vc78 seam: observations and missing evidence

The saved `../vc78-ci/43955519403/device-after/fresh-telemetry-at-stop.json` contains 51 events. One normal loop sequence is particularly useful:

| Saved timestamp, epoch ms | Observed event |
|---:|---|
| 1794864590328 | `mix.fire`, mode loop, queue index 8; planned 172081 ms, actual 171501 ms; 1 s preroll. |
| 1794864596723 | `loop-handover-correct`: expected 4440 ms, heard/target 4488 ms, drift −186 ms. |
| 1794864596726 | Decoder position 4674 → 4488 ms, tagged `handover-suppressed`. |
| 1794864596742 | Exit gate violations 0. |
| 1794864596745 | Verification `ok=true`, phase scalar approximately −0.009 ms. |
| 1794864596759 | Normal `LOOP_RUNNING` completion. |

The seek is observed. A repeated fragment, timbral step, or audible gap caused by it is **not** observed in a synchronized waveform, because none was captured. The tiny phase scalar and quiet exit gate do not establish continuity through this handover.

**A stronger, falsifiable timing hypothesis emerged from the DSP review.** `LoopMixSession.kt:871` schedules the incoming renderer's fade at `handoverTrackSec`. Native code stores the source position at the end of the rendered block (`loop_mix_renderer.cpp:885`), marks the deck finished after its fade (`:1005`), and stops advancing that deck on later calls (`:711`). Controller cleanup waits until `elementAt + 0.3 s` (`CrossfadeController.kt:5230`, constant at `:7689`) and then compares renderer `heard()` with the player's current position.

That can compare a frozen rendered position with an advancing decoder at a later instant. Render-ahead buffering introduces another distinction between generated samples and presented samples. The saved 4488-versus-4674 ms comparison is consistent with this class of problem, but does not establish it. In a controlled replay, hold splice alignment fixed while varying cleanup delay and timestamp both generated and presented positions. If the apparent drift follows elapsed time after renderer completion, the reference is stale; if same-instant presentation measurements differ, genuine alignment remains implicated. Do not simply disable the correction without resolving its purpose and measuring the outcome.

There is also a processing-parity question: renderer output passes through the outgoing sink's DSP chain (`PrecisionAudioSink.kt:605`), while incoming decoded audio is gated on its own path (`:637`). Regional matching selects an incoming renderer gain (`LoopMixSession.kt:477`). Compare effective gain, filters, stereo response and limiter state across both paths; matching source positions alone would not prove equivalent output.

A later loop includes `changeReason=1` with `playWhenReady=false`, followed by an exit guard and 800 ms tail. Reason 1 is USER_REQUEST in the shipped Media3 version. It does not establish an internal pause defect or authorize automatically resuming the user's pause.

Earlier classic/DECLINE plan entries do not identify the final effect script of a later `mode=loop` fire. Nor does a stem-duck decision prove successful inference. The user reported failures on three tracks; the saved events do not identify three fully recorded normal transitions or align each complaint with one exact effect path.

The vc78 native probe established a narrower reverb wet-stop repair. The user's subsequent listening rejected overall transition quality. Both statements remain true.

## 7. Snapdragon NPU: a practical opportunity with specific proof requirements

### 7.1 Current app versus hardware

Qualcomm's SM8635 product brief lists the Hexagon NPU and INT4/INT8/INT16/FP16 capabilities [S07]. The device is not disqualified by lack of neural hardware.

Current source establishes:

- `NpuSupport.QNN_ENABLED = false` at `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt:41`.
- The CPU session is selected in `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuSession.kt:37`.
- `app/build.gradle.kts:462` declares standard **ORT Android 1.31.0**. Older references to 1.28 are stale for this source snapshot.
- The saved vc78 APK audit reports one ORT library and zero QNN/HTP libraries at `../vc78-ci/43955519403/signed/audit.log:10`.

Re-enabling a flag alone would not create a working accelerator path. Existing dormant comments and addon instructions must not override the current package evidence.

### 7.2 The documentation changed: avoid stale generalizations

The old ONNX Runtime QNN page describes an earlier provider generation and includes Windows-only prebuilt and quantized-only HTP statements [S10]. The current **QNN EP v2.8.0** release and version-pinned documentation describe an Android ARM64 Maven distribution, including `com.qualcomm.qti:onnxruntime-android-qnn`, and a validated dependency combination of ORT 1.29.0 with `qnn-runtime` 2.52.0 [S11]. The release states ORT compatibility >=1.24.1; BitChord's 1.31.0 combination has not been tested here.

The newer documentation describes floating-point and quantized HTP models on supported hardware. It still requires fixed shapes and supports operators/types subject to conditions. It lists LSTM, which does not automatically establish support for BitChord's dynamically quantized recurrent graph. Hardware FP16 capability does not prove every operator exports or executes correctly.

Do not prescribe an `htp_arch`, numeric `soc_model`, or firmware compatibility from the product name alone. Exact SM8635 support must be checked against the selected runtime, device firmware and real execution. NNAPI was deprecated in Android 15; it was not removed [S17].

### 7.3 Useful division of labor

| Work | Suitable initial placement | Reason |
|---|---|---|
| Beat, activity, structure and separation neural networks | Background CPU baseline; evaluate NPU for supported graphs | These can benefit from accelerated tensor compute and finish before the transition. |
| Spectrograms, resampling, reconstruction | Profile CPU/SIMD first; include in complete pipeline cost | Accelerating only inference leaves frontend, copies and inverse transforms. |
| Time stretching, filters, faders, delay, reverb, loop joins | Deterministic real-time DSP with prepared inputs | Their output deadlines and small state updates should not wait on model compilation or inference. |
| Cue/transition selection | Background/control work with bounded deadlines | Musical correctness depends on features and policy, not accelerator identity. |

A 256-frame block at 48 kHz is 5.33 ms; this is an illustrative deadline, not a measurement of the phone's actual buffer. No filesystem access, model lock, compilation, or uncertain driver wait belongs on that path.

Precompute transition windows plus model context before the deadline, reuse decoded PCM where appropriate, and cache by actual recording/window/sample alignment and model version. Keep contexts separate per model, shape, precision, runtime/compiler settings and compatible device. Preserve an ordinary CPU path and graceful musical fallback when preparation is unavailable.

The current vocal tensor `[1,2,2049,960]` alone occupies about 15 MiB in float32. Two stems for two 21.5 s stereo 48 kHz windows occupy about 31.5 MiB before dry buffers, model activations, reconstruction and renderer state. These are arithmetic examples, not measured process peaks. Model file size is not a memory budget.

The existing umxhq two-part path is the most concrete baseline. Beat This! also supplies smaller models [S15]. Compact separation networks can be evaluated later, but no real-time, thermal, memory, battery or quality number for this phone was established in this session. Do not conclude that all full-song separation is universally impossible, or that a desktop benchmark predicts mobile performance.

### 7.4 Proving actual acceleration

The dormant self-test marks QNN success after session creation while allowing CPU fallback, then considers a nonthrowing dummy run successful (`app/src/main/java/com/music/bitchord/playback/smart/NpuSelfTest.kt:112`). This cannot prove substantial HTP execution. Listing Skel libraries or loading a `.so` is also insufficient.

A future experiment should establish:

1. Exact device/firmware, runtime/provider versions, model/export hash, shape, precision and provider options.
2. A minimal official static model executing with HTP selected and CPU fallback disabled for the diagnostic.
3. Provider partition/operation assignments and an execution trace containing actual QNN/HTP work.
4. Finite/correct outputs, comparing the current CPU model, CPU execution of the candidate export, and HTP execution of that export.
5. Cold/warm preparation time, latency distribution, memory, thermal behavior, and energy through the complete pipeline during sustained playback.
6. Explicit fallback behavior for unsupported operations, context corruption, initialization failure and later execution failure.

Partial offload may be useful; it must be reported as partial. Strict fallback-disabled diagnostics distinguish dispatch from a misleading successful CPU fallback. Production can retain fallback, provided its actual use is recorded.

This work could begin with static floating-point/FP16 evaluation where supported, then compare quantization options. Do not assume A8W8 is mandatory or best. Qualcomm runtime redistribution terms and model-weight terms must be checked separately from ORT's license before delivery.

## 8. A focused next investigation, without another patch pile

These are proposed steps for a separately resumed implementation/testing phase. None was executed here.

1. **Freeze a real failing example.** Identify the recording versions, pair, retained queue context, audio route, current settings, and heard timestamp. Preserve failed attempts as evidence.
2. **Capture the whole handover.** Obtain synchronized output audio and execution data spanning before the mix, the overlap, and the return to decoder playback. Capture the final accepted script and actually used stem/effect path. A native offline render alone cannot reproduce every Media3/sink transition.
3. **Use two complementary isolation comparisons.** First, a same-recording A/A renderer/decoder handoff at unity rate/gain with optional processing disabled; compare timestamped positions and vary cleanup delay while initial splice alignment stays fixed. Second, the real failing pair with current cues versus human-validated phrase/downbeat cues under the same stripped processing. Then restore stretching, stems, filters and effects individually with cues held fixed. Passing A/A alone does not validate a two-song transition.
4. **Let the outcome choose the repair.** If a dry, musically correct plan still breaks exactly at handover, prioritize transport/DSP continuity. If continuous output truncates a lyric or abandons a build, prioritize planning. If artifacts appear only after separation, inspect its quality, alignment and endpoints. If preparation misses the deadline, profile that critical path.
5. **Restore measurement credibility.** Measure actual output rather than passing placeholder loudness/peak values. Represent unmeasured values explicitly. Examine waveform joins, repeated/dropped content, loudness envelopes, spectral/low-band changes, onset timing, peaks and tails; do not equate any one scalar with perceptual quality.
6. **Only then expand capability.** Trustworthy cues and conservative selection first; supported stems/loops/effects where they improve listening; NPU integration where measured preparation/energy benefit warrants it.

Preserve the handover invariants throughout any resumed work: no local Android builds, no user-data reset, no automatic resume of a user pause, no resetting the surviving deck while retiring the other, and one writer per shared file. The required final result is still actual listening on the user's device.

## 9. Listening evaluation that can support a quality claim

Use a small predeclared set containing the user's failures and contrasting material: steady instrumental rhythm, cold/dense vocals, large or half/double-tempo differences, sustained endings, and drifting or beatless passages. Include natural/no-effect transitions as an option. Keep declined mixes and preparation failures in the results.

Compare Apple and BitChord using the same recording/master where possible. Document Apple OS/AutoMix version, queue context, Sound Check/normalization, Atmos/spatial processing, EQ and output route. A different YouTube upload or mastering is a confound, not an algorithm difference.

Apply one constant level-match gain to each whole excerpt. Do not normalize every part independently, which would erase the loudness jumps under investigation. Randomize presentation, hide system identity, and include repeated items to assess consistency. Rate startling change, rhythmic stumble, vocal truncation, tonal continuity, separation artifacts and preservation of musical intent separately from overall preference.

Apple's mix is a comparator, not an ideal reference. ABX answers whether a difference is detectable, not which transition is better. A creative-transition preference study should not be called MUSHRA without the reference/anchor design and other requirements of the standard [S18]. No universal numerical threshold guarantees “smooth.” Set engineering thresholds from measured examples and listening sensitivity, and report worst cases and uncertainty alongside averages.

No blind level-matched comparison was completed. Apple parity remains unproven, and vc78 remains rejected by the user's listening feedback.

## 10. Sources and interpretation limits

All sources below were read through TinyFish in this session. Project websites describe their own tools, not Apple's private implementation.

| ID | Source | What was verified |
|---|---|---|
| S01 | [Apple iOS 26 announcement](https://www.apple.com/newsroom/2025/06/apple-elevates-the-iphone-experience-with-ios-26/) | Explicit “time stretching and beat matching.” |
| S02 | [Apple transition support](https://support.apple.com/en-us/105067), [iPhone transition guide](https://support.apple.com/guide/iphone/transition-songs-iphadf2fe1f4/ios) | Adaptive silence trim/crossfade, subscription/catalog and compatibility limits. |
| S03 | [Apple Music Mac playback settings](https://support.apple.com/guide/music/playback-settings-musdf855a1b/mac) | Transition timing based on key and tempo analysis. |
| S04 | [Apple 2026 services announcement](https://www.apple.com/newsroom/2026/06/apple-unveils-innovative-features-and-intelligence-experiences-across-services/) | Qualitative improved transitions, tvOS/HomePod; no detailed algorithm disclosure. |
| S05 | [BetaProfiles beta-5 report](https://forum.betaprofiles.com/t/whats-new-in-ios-27-beta-5/23226) | Reproduced Apple UI text: “including looping, reverb, delay, and more.” Secondary provenance. |
| S06 | [9to5Mac July 16 hands-on](https://9to5mac.com/2026/07/16/ios-27-makes-one-of-my-favorite-apple-music-features-even-better/) | Reported repeated intro/outro passages and smoother transitions; subjective report. |
| S07 | [Qualcomm Snapdragon 8s Gen 3 product brief, Rev D](https://docs.qualcomm.com/doc/87-73942-1/87-73942-1_REV_D_Snapdragon_8s_Gen_3_Mobile_Platform_Product_Brief.pdf) | SM8635, Hexagon NPU, listed precisions; no BitChord benchmark or exact deployment certification. |
| S08 | [WWDC26: Meet Music Understanding](https://developer.apple.com/videos/play/wwdc2026/253/) | Full transcript: on-device analysis, six dimensions, unlabelled structure ranges, Final Cut Pro usage. |
| S09 | [Music Understanding documentation](https://developer.apple.com/documentation/musicunderstanding), [fetched Markdown](https://developer.apple.com/tutorials/data/documentation/musicunderstanding.md) | Analysis/result semantics; no explicit AutoMix integration. |
| S10 | [Earlier ORT QNN provider documentation](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html) | Historical provider constraints and options; must be read with its version scope. |
| S11 | [QNN EP v2.8.0 release](https://github.com/onnxruntime/onnxruntime-qnn/releases/tag/v2.8.0), [version-pinned documentation](https://raw.githubusercontent.com/onnxruntime/onnxruntime-qnn/v2.8.0/docs/execution_providers/QNN-ExecutionProvider.md) | Android package, release dependencies, conditional precision/operator support, fixed shapes, profiling and fallback controls. |
| S12 | [Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) | Pitch/time processing, input/output latency, automation, preroll/seek/flush and computation scheduling. |
| S13 | [Rubber Band technical notes](https://breakfastquay.com/rubberband/technical.html) | R2 phase-vocoder/transient/coherence design; explicitly not a complete description of R3 or Apple. |
| S14 | [From raw audio to a seamless mix](https://link.springer.com/article/10.1186/s13636-018-0134-8) | Genre-specific DnB system integrating beat/downbeat/structure/EQ and subjective evaluation; limited generalization. |
| S15 | [Beat This! official repository](https://github.com/CPJKU/beat_this) | Beat/downbeat outputs, full/small models, MIT code/weights, warning against evaluation on training material. |
| S16 | [MSAF](https://github.com/urinieto/msaf), [algorithm documentation](https://msaf.readthedocs.io/en/latest/algorithms.html) | Boundary detection and label clustering as separate tasks. |
| S17 | [Android NNAPI migration guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide) | Deprecated in Android 15; this is not a statement of immediate removal. |
| S18 | [ITU-R BS.1534](https://www.itu.int/rec/R-REC-BS.1534) | Subjective assessment standard identity/status; this report does not claim a compliant MUSHRA experiment. |
| S19 | [Open-Unmix documentation](https://sigsep.github.io/open-unmix/) | Separation outputs and model family; distinguishes umxhq from noncommercial umxl weights. No target-phone performance claim. |
| S20 | [ITU-R BS.1770](https://www.itu.int/rec/R-REC-BS.1770/en) | Standard for programme loudness and true-peak level; the listing identifies BS.1770-5 as in force. No claim of BitChord compliance was established. |

## 11. Council record and reconciled recommendation

All four requested preset seats produced substantive reviews. The orchestrator ran the separate council synthesis after receiving them, checked the consequential source claims, and integrated the outcome here.

| Seat | Scope | Session |
|---|---|---|
| councillor-deepseek | Apple disclosure and evidence boundaries | `ses_e19a5a73cffenrd9OkJHKE3RKq` |
| councillor-glm | DSP, playback continuity, isolation experiments | `ses_e19a5a730ffehtLsD5A4UO54HO` |
| councillor-gemini | Snapdragon/NPU deployment and current backend | `ses_e19a5a727ffeUTVgfFenW0AClg` |
| councillor-kimi | Structure, musical planning, listening evaluation | `ses_e19a5a71cffezHLOq93iJsl5Tk` |
| council synthesis | Reconciliation and correction targets | `ses_e198d7112ffessIG1tc4tUTV2y` |

The seats agreed on the evidence boundaries and the need for a captured comparison. Their triage emphasis differed: the DSP review favored isolating playback continuity first; the structure review emphasized testing the musical plan alongside it. Section 8 resolves this with both the A/A handoff and current-versus-human-cue comparisons. No vote establishes the cause of an audible failure, and shared fetched sources are not independent confirmations.

The orchestrator's recommendation is to retain the existing CPU/DSP baseline, isolate one actual failure, and let the result select the next implementation. NPU acceleration and richer musical moves remain worthwhile development directions with their own quality and performance proof requirements.
