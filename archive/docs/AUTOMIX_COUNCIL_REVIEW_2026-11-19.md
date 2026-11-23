# BitChord AutoMix: council review and vc83 evidence

**Decision:** Preserve BitChord's preference for an instrumental loop when the local audio and playback conditions warrant it. Verify the narrow loop-character recovery on its own. Do not promote the vc84 integration, remove the classic fallback, globally narrow the native tempo window, or tune effects from these reports. Apple AutoMix is a listening benchmark, not the product specification.

The four council seats agreed on that decision after checking the reports against source. They prioritized different follow-ups: GLM wanted the retire-guard join; DeepSeek wanted a classic rate-contract audit; Gemini and Kimi wanted to verify the loop-character work at a stable checkpoint. The archived join and v4 source-checkpoint verification are now done. Playback characterization and musical preference are still separate tasks.

## Reviewed inputs and version pins

- [Fable51 technical audit](fable51_analysis.md): useful architectural questions, several incorrect or outdated implementation claims.
- [Astra6max review](astra6max_analysis.md): strongest evidence framework; update its interpretation of the timing and pause counts from the raw records below.
- [Fable51 Apple research](fable51applemusicautomix.md): useful account of observable behavior if its version and evidence labels are preserved. Not proof of Apple's implementation.
- [Nov 19 handover](HANDOVER_2026-11-19.md) and [loop-character recovery](LOOP_CHARACTER_RECOVERY_2026-11-19.md): release history, reported listening, and the current project goal. Source overrides historical architecture descriptions; archived telemetry and an APK hash do not establish today's phone state.

| Role | Verified local identity |
| --- | --- |
| vc83 shipped-source reference | `.slim/worktrees/automix-readiness-repair` at `170328b`, clean. The retained signed vc83 artifact's SHA-256 matches the handover (`eb2a3f188147e9b91bb3350bcaec6cbd4d4618698d746494280a0bd4094659d4`). |
| vc84 integrated candidate | `.slim/worktrees/automix-vc84-integration` at `b140313`, with an existing untracked handover. Staged only per the release evidence. PHRASE takeover double-counting, M7 stop-gain, and straight-roll resume remain open release-review items, not measured device failures. |
| Readiness-only reference | `baf2b1a` is based directly on vc83. Its counterpart `7c8a347` is on the NPU base. Review the standalone version if cold preparation is the isolated problem; do not pull the integration into loop-character by default. |
| Other trees | `work/bitchord-mix` itself is a dirty vc80 root at `2cb6e6f`. The loop-character worktree is dirty on vc83 with production/test WIP. Neither is a clean reference or available for cleanup. |

No phone was visible to local `adb`. No production code, writer lane, original report, signing state, or queue was changed by this review. No app was rebuilt or heard.

## Loop-character v4 evidence checkpoint

After the original report was written, the loop writer published a frozen v4 source archive to cue. Independently inspected artifacts identify base vc83 `170328b` and SHA-256 `64af26359edce7c6657d7df38f227b22fab649f97635c73184f0018bcab2f933`. The specified files match the frozen manifest. This is an **isolated source candidate**, not a Git commit, APK, signed release, installed build, or user listening session. The active source worktree remains dirty and untouched. Full artifact interpretation is in `docs/LOOP_CHARACTER_RECOVERY_2026-11-19.md` (`Frozen candidate results`); raw commands and XML are under cue's `candidate-039c7024-v4/`.

| Check | Inspected evidence | Scope |
| --- | --- | --- |
| Frozen source archive | source digest above; enumerated behavioral/test paths match v4 manifest | Candidate identity; not an APK. |
| Final ordinary Gradle invocation | Exact command retained; exit `0`; XML totals: shared 536 tests / 0 failures / 0 errors / 0 skips; app 1,436 / 0 / 0 / 4 | Three skips are the ordinary-suite host-native-required tests; the remaining skip is unrelated live Genius search. Native test is accounted separately below. |
| Dedicated v4 host JNI test | `JAVA_TOOL_OPTIONS` required native loading; test ran from v4 source archive; exit `0`; fresh live XML 3 tests / 0 failures / 0 errors / 0 skips | Confirms generated stereo render, crown loop/release/resume, actual flags, and pre-decode refusal on generated fixture PCM. Not the Android Media3 delegate, DAC output, the reported songs, or listening. |
| Fixed-input routing replay | Identical test bytes and input manifest; baseline and v4 each run successfully. Crown candidates 4→5 of 9; one metadata-conflict witness changes Echo bridge→crown | Planner candidate selection only. `accepted`, `prepared`, native takeover, delegate acceptance, output and listening are marked unobserved in the replay itself. Not catalog routing frequency. |
| Standalone renderer probes | Probe implementation and renderer sources in v2 are byte-identical to v4; retained v2 selftest / reverb-stop / 63-pair loop-domain probes exit `0` | Reused identical-source evidence, not a new v4 probe invocation. The deliberate reverb sensitivity control is expected to fail. |

**Decision impact:** The frozen patch and retained checks support a bounded synthetic **admission and mechanics** candidate, not a production-quality verdict. Do not repeat those green suites without a source/input change or a specific audit gap. Do not infer on-device delegate acceptance from the JNI suite; the executed route trace stops short of that boundary.

The four ordinary app-suite skips are now identified: the three `LoopCrownNativeSessionTest` methods require a separate JVM with the explicit native-required flag, and the unrelated live Genius scrape is the fourth. The dedicated v4 JNI lane has a separate live XML with three passes and no skips. The older standalone probes were not rerun on v4; their v2 renderer and probe inputs match the v4 native tree byte-for-byte, so that is unchanged-source evidence, not a v4 invocation.

The later reliability patch is deliberately in the separate `automix/reliability-observation-20261119` vc83-based worktree, not the active loop writer or v4 archive. It now carries the first loop-abort cause through guard cleanup and reports a separate status for full consumption of the *specific* takeover splice buffer by the downstream `AudioSink` delegate. That signal is not presentation/audibility. The final hash-matched cue run passed 27 focused tests, then the integrated shared/app suite (536 shared and 1,440 app tests, zero failures/errors; four expected ordinary app skips). See [the reliability implementation and validation record](AUTOMIX_RELIABILITY_VALIDATION_2026-11-19.md) for commands, source identity, prior failed/superseded runs, and limits. No APK/device work.

The final independent source review has accepted that reliability revision with zero blocking findings. The independent evidence audit is closed, and the complete v4 base plus six-file overlay is retained locally for reproduction. A nonblocking consume-then-throw/retry diagnostic edge is explicitly documented; no source changed during review. Device output and listening remain separate, unperformed gates.

### Sink acceptance caveat

An additional source check narrows the word “takeover.” `PrecisionAudioSink.spliceLoop()` returns true when a processed block crosses frame 0. `handleBuffer()` then sets `loop.tookOver = true` before calling `delegate.handleBuffer(...)`; if the delegate refuses or partially consumes the same buffer, `handleBuffer()` returns false and the identical buffer is retried (`app/.../audio/PrecisionAudioSink.kt:425–480`). Therefore `mix.fire` is a **logical sink splice / takeover observation**, not proof that the Android output sink accepted the takeover buffer or that sound reached the hardware. The frozen v4 source explicitly labels its fire marker “takeover-only-not-delegate-buffer-or-listening-proof”; it does not add a later per-opportunity downstream-acceptance record. Preserve this distinction in telemetry and any later device analysis. Keep the spelling “one observed sequence span”: sequence resets are not authenticated process boundaries, and `mix.fire` is not a persistent unique transition ID in the older vc83 archive.

## Source corrections that change the recommendation

All source references here use the vc83 tree; paths below are relative to the app playback or shared smart-planning packages unless otherwise noted.

| Claim | Verified interpretation |
| --- | --- |
| Only classic mixing shipped; native arrived in vc84 | vc83 already contains and exercised two-deck Signalsmith rendering on one clock for eligible native transitions. `loopmix/LoopMixRenderer.kt:5` defines that path; `audio/PrecisionAudioSink.kt:565` splices it into playback. The native overlap is one generated stream, but taking over and resuming decoded playback are still lifecycle boundaries. `TrackAnalyzer.kt:1998` decodes and resamples both regions to the requested render rate. Classic stays separate. |
| Classic plays as much as +25% and always uses Sonic | `TransitionPolicy.kt:75` allows consideration of folded rates `0.90..1.25`; `TransitionPlanner.kt:1352` limits the nominal incoming blend rate to `0.94..1.09`; `CrossfadeController.kt:156` clamps the classic beatmatch component to `0.92..1.08`. User speed and buildup are separate. `PlaybackService.kt:6999` configures both Sonic and the AudioTrack playback-parameter path; active behavior depends on the output configuration. A wider requested rate can be admitted but not executed as requested: audit alignment and the tier that was actually used. A clamp is not a guarantee of beatmatch quality. |
| Classic cutoff motion is literally a 20 ms step; DSP is 64-bit throughout | `TransitionFilterProcessor.kt:248` processes Float32 samples and glides LP/HP/mid targets in PCM sub-blocks (`:276`). `audio/AudioBlock.kt:27` stores samples in a `FloatArray`. That does not say every scalar is Float32, nor prove the timing or smoothing of player gain and rate. Measure those at the output before changing automation ownership. |
| Key is merely a score; the planner needs to start committing plans | `TransitionPlanner.kt:365` has a conditional key/tempo decline; `:1272` has a phrase-switch veto. `PhrasePlanner.kt:787` shortens several overlaps on a clash and `DjMix.kt:342` can rewrite a blend to a filtered handoff. Classic does recompute while idle, but `CrossfadeController.kt:2453` stabilizes the cue, `:2786` snapshots the armed plan, and `:4496` memoizes the phrase selection before locking/committing. There may be redundant computation or unclear invalidation; there is not an absence of commitment or harmonic policy. |
| Add regional loudness matching and a native sum limiter | Regional matching already uses K-weighting and gated 400 ms blocks (`RegionalLoudness.kt:4`); native has a sample-peak sum limiter (`native/loopmix/loop_mix_renderer.cpp:1024`), not verified true-peak protection through every path and splice. Measure post-filter per-deck and summed loudness, low-frequency energy and oversampled true peak. The prior +3.13 dB summed RMS render demonstrates a level change, not a preference. |
| The rejected NPU scores show weak downbeats or a hardware limitation | `REAL_MEL_REPORT.md` in the vc84 tree records `0.805` beat / `0.657` snapped-downbeat agreement against the incumbent (gate `0.95`), comparing two different graphs on CPU on one recording. Neither accuracy against annotations nor HTP dispatch was measured. Keep both flags off; measure deadline, energy, and thermal costs before taking on acceleration. |

The code is substantial and the fallback ladder is valuable. That does not establish reliable delivery across routes or libraries. Unifying renderer clocks would address relative scheduling *if* classic timing is a dominant audible problem. It would not fix local/grid selection, PCM availability, source identity, exit ownership, or pauses; it would also put a working fallback behind a new preparation path. A common timing and lifecycle contract gives useful evidence without that risk.

## Retire-guard correlation

Recovered `/tmp/opencode/telem.jsonl` and preserved a byte-identical local copy under `work/automix-council-review-2026-11-19/`. The input has 4,367 rows (1,331,913 bytes) and SHA-256 `4f1a03da12b28cacaccbff8857425e6a1f30fdf5eaf224eda77dd194511f027d`. Filtering at handover's vc83 boundary (`ts >= 1795014294000`) gives 721 rows in five sequence spans. Join in file order *within a span*, bounded by the previous `mix.complete`, with a unique `mix.fire` and an incoming ID observed as active. Do not join on BPM or globally on `seq` (it resets), and do not infer track or rendition names. The source writer emits bare `-Infinity` for zero-gain dB in two vc83 rows; the analyzer treats non-finite tokens as missing in its derived view, preserving the raw archive.

There are **30 loop-mode fires and 2 classic fires**, against **36 completion records**. The other four were interrupted, missed the splice deadline, or were scrub-cancelled before any observed fire. Of the 30 loop-mode fires, 21 ended `loop-complete` and nine went through `emergency-retire-guard` (9/30 in this archive; 9/36 across all completions). All nine cluster in one observed sequence span, which also contains seven normal loop completions. Do not extrapolate either fraction to field prevalence. The 43 `DJ_FILTER` plans among 74 plan records emphatically do not mean 43 filtered mixes executed. Neither does `mode=loop` prove a two-sided loop rather than a native bridge or phrase transition.

All rows in the following table are from the same observed sequence span. The older vc83 `mix.fire` is emitted when the outgoing port reports `tookOver`; it is a logical splice marker, not proof of delegate-buffer consumption. Every guard runs in `LOOP_RUNNING`, sees a commanded spare volume of `1.0`, then starts a nominal 350 ms `TAIL`. None reports `mix.exit.gate` or `mix.verify` for that attempt. The candidate dump identifies a prior classic decision for the same pair, not the native technique that ran.

| Fire seq | Guard seq | Tail seq | Complete seq | Earlier same-pair candidate dump | Other evidence |
| ---: | ---: | ---: | ---: | --- | --- |
| 79 | 84 | 85 | 87 | 74 `DJ_FILTER` | Handoff deferred (81) |
| 96 | 99 | 100 | 102 | 91 `decline` | |
| 111 | 114 | 115 | 117 | 105 `decline` | |
| 141 | 144 | 145 | 147 | 134 `decline` | |
| 166 | 169 | 170 | 172 | No matching dump | Pair sourced from `mix.state` |
| 180 | 183 | 184 | 186 | 175 `decline` | |
| 195 | 199 | 200 | 202 | 190 `DJ_FILTER` | Handoff deferred (197) |
| 258 | 261 | 262 | 264 | 254 `decline` | |
| 272 | 278 | 279 | 283 | 268 `DJ_FILTER` | Pause (277), settle-position seek (280), pause watch (285) |

**What this localizes:** It is not a classic-path retirement, nor a pre-takeover arm timeout. The telemetry represents the controller entering a native sink splice; the downstream output acceptance and exact native technique/geometry are absent. For the last case, the incoming session was observed paused in `driveLoopMix()`, which calls `loopBail("paused")`; the 350 ms guard and seek follow, and the idle watch observes the pause again 253 ms after cleanup. Those two pause records belong to one episode, not two independent commands. Media3 reason `1` does not identify who initiated it. For the other eight, the initiating exit reason is unresolved: `loopBail()` sets a cause, but `finish()` can replace the public completion reason with `emergency-retire-guard`. This shows why attribution is lost, not a common cause. Commanded player volume is not PCM measurement and does not prove audibility.

### Timing correction

| Measured quantity | Samples | Min / median / max (ms) | Meaning |
| --- | ---: | --- | --- |
| `actualHeardMs - expectedHandoverMs`, including missing sentinels | 36 | -1 / +53.5 / +66 | Reproduces the rounded +54 ms handover figure, but conflates missing with measured. |
| Same subtraction, valid endpoint pairs only | 21 | +50 / +60 / +66 | Generated native endpoint relative to the planned track position. `renderer.heard(IN)` is not the output onset (`CrossfadeController.kt:5536`). |
| `mix.verify.phaseErrorMs` | 21 | -1.336 / -0.144 / +0.733 | Incoming sink position minus its mapped target at native cleanup (`:5579`). Only the normal path produced these scoped measurements, not all loop fires. |

Neither metric measures relative beat phase in the audible overlap. The verifier passes zeros for loudness and peak inputs and labels its scope `phase-and-exit-gate-only` (`CrossfadeController.kt:5598`). The 64-85 ms USB capture gaps documented elsewhere still lack attribution.

## Apple comparison

[Apple's 2025 announcement](https://www.apple.com/newsroom/2025/06/apple-services-deliver-powerful-features-and-intelligent-updates-to-users-this-fall/) confirms AI audio-feature analysis, beat matching and time stretching. The [iPhone guide](https://support.apple.com/guide/iphone/transition-songs-iphadf2fe1f4/ios) describes key and tempo analysis and says simpler crossfades, silence removal or no transition can be appropriate. [Current support](https://support.apple.com/en-us/105067) lists Apple-silicon Mac support, content and Hi-Res Lossless restrictions, and updated TV/HomePod usage; [Apple's 2026 announcement](https://www.apple.com/newsroom/2026/06/apple-unveils-innovative-features-and-intelligence-experiences-across-services/) confirms AutoMix enhancements and expansion to tvOS and HomePod. The older platform exclusions cannot be used as current evidence for an ANE requirement. None of these sources establishes catalog-wide server pre-analysis, the server/device division, Neural Engine dispatch, a specific stretcher or mixer, preset tempo tolerances, or better results in controlled listening. Do not infer a product requirement from one of those hypotheses.

## Ordered next work

1. **v4 source-candidate checkpoint: complete for the bounded policy/mechanics slice.** The frozen baseline XML has a passing local-loop control and one expected failure: changing only whole-track BPM/vocal metadata blocks identical clean local 1x loop geometry. On v4 this witness selects a two-sided crown while negative safety cases, three wide/folded planner witnesses, actual knot-rate checks, controller qualification/attribution harnesses, and a dedicated host JNI session pass. The paired planner replay is planner-only; the separate JNI session is generated PCM and not the Android delegate. The exact Adrenaline→Bloody Paradise recording is not replayed; field routing distribution, delegate acceptance, capture, and listening remain unknown. Keep this only as a frozen experiment pending the later device/output decision—no APK or merge was part of this checkpoint.
2. **Prepare a versioned target-device reliability experiment before release.** Preserve the initiating loop-abort cause separately from the final retire action. Identify each opportunity/deck, built crown-versus-bridge geometry, readiness, port state, and downstream delegate consumption/partial retry. Treat the existing logical splice marker as before delegate acceptance; do not label it audible presentation. Track session-command provenance and retirement. Keep instrumentation on the existing telemetry surface and off the PCM callback's allocation/blocking path. A marked session with calibrated capture and a capture-chain control should separate real gaps/premature filtering from buffer/ownership events and USB artifacts. Keep vc83 as baseline and leave full vc84/P1s outside this experiment.
3. **Audit the classic rate contract separately.** Replay fixed boundary ratios and folds. Trace requested, planned and finally applied rates, chosen renderer/tier, any buildup, actual phase and fallback reason. A classic correction the renderer cannot realize must be rerouted, downgraded or explicitly justified; changing the native loop window will not resolve this. Consider a shared-clock classic prototype only if a measured audible problem survives the bounded ownership and rate fixes, and require safe fallback, supported routes and no new deadline or continuity regression.
4. **Measure taste separately from reliability.** Build a small held-out set from the target library covering safe loops, borderline mixes, and pairs that should fall back. Compare BitChord baseline/candidate, plain crossfade and Apple AutoMix where matched masters are available. Record OS/device/route and whether each system mixed, faded or left it alone. Let adaptive engines pick their own cues. Blind level-matched excerpts with enough song context and do not normalize away the overlap itself. Score phrase continuity, vocal/bass clashes, stretch artifacts, worst cases and overall preference. Calibrate beat/downbeat and vocal confidence against annotated local material before adding templates or training another model. Listener acceptance applies to the tested build, route and library, not all Android devices or Apple parity.

## Reproduction

```bash
cd ~/Bitchord/work/automix-council-review-2026-11-19
sha256sum vc83-archive.jsonl
node --check analyze-vc83.mjs
node analyze-vc83.mjs vc83-archive.jsonl
```

The analyzer prints the version-boundary provenance, per-episode event references, both timing definitions, and counts. It does not infer names, probe the phone, or modify the archive. The ordinary source/renderer gates and a listening run were not executed in this review.
