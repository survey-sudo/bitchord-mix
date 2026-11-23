# Apple Music AutoMix parity checklist

Updated: **2026-11-17**, after vc82 wireless installation; phone work paused for gaming.
Latest handover (2026-11-18): **[AUTOMIX_HANDOVER_2026-11-18.md](AUTOMIX_HANDOVER_2026-11-18.md)**.

**Goal:** consistently natural, musically appropriate transitions on the user's Snapdragon 8s Gen 3 phone, with no accidental startling change, repeated/skipped fragment, or broken vocal phrase. Preserve both songs' intent and choose a simpler transition when it sounds better. Apple is the listening comparator; reproducing an undisclosed internal architecture is not a requirement.

**Current status: vc81 stability is user-approved; vc82 is installed for listening.** Current installed build: **vc82 / 2.2.3-djmix40**, source `60b9734711b3a0e3214a31f76eac7766c61ae873`, branch `vc82-overlap-listen`. Wireless `adb install -r` and package-manager checks confirmed the update on1439ebea. The user-approved stability baseline remains vc81 /2.2.3-djmix39, source `e7c8a3e`; its code and hosted APK remain preserved. Neither overall mix quality nor Apple AutoMix parity is established.

**Latest user result: “Okay I approve it's way stabler.”** Record this as acceptance of vc81's stability improvement following the one-song planning-stall repair. Preserve vc81 as the accepted baseline for subsequent work. Remaining priority: improve musical overlap and transition quality. The Touch the Sky → Higher Love dropout remains user-confirmed fixed on vc80; intermittent stutter/demuffle, overall mix quality and Apple AutoMix parity remain open. This feedback does not establish the original phone exception's exact cause or a measured long-run transition count.

The later “even better” feedback preceded the independently verified vc82
installation: the initial wireless snapshot still showed81 with no app process.
Do not attribute that feedback to measured vc82 playback. After installation one
activity launch restored Empty Box — ATEEZ with a Play control; no Play/seek/queue
command or recording was sent. A subsequent offline test-only characterization on
`offline-arming-readiness` (12 focused tests green on `cue`, zero production diff)
confirmed `driveArming()` filters solo `A` (~800 Hz) while `B` stays `BUFFERING`,
then `startFade()` reopens to `7000 Hz` on the first `FADING` tick
(`../automix-stutter-2026-11-17/ARMING_READINESS_CHARACTERIZATION.md`).
At handover time, phone `1439ebea` is disconnected from ADB and `cue` Tailscale SSH
requests an additional Tailscale browser auth check (`cue_status` HTTP remains UP).

The old `vc80-filter-catchup` working tree remains preserved and unverified; neither held patch was included in vc81. The DECLINE fader policy was subsequently reapplied and tested separately for vc82; the unfinished EOF catch-up/controller patch remains excluded. The accepted planning repair is isolated at `.slim/worktrees/automix-replan` on `vc81-automix-replan`, based on `2cb6e6f`. Evidence path: `../automix-stutter-2026-11-17/AUTOMIX_REPLAN_VERIFICATION.md` relative to the repository root.

Planning evidence: the injected-error regression fails on vc80 and the recovery
candidate passes23 focused tests. The lyric-boundary exception is reproduced
through realplanTransition on the original source and repaired in final
`e7c8a3e`. Final tests pass shared500/app1406 with zero failures/errors and one
existing app skip; native gate, signed v2+v3 APK, certificate/audit/alignment pass.
Final APK SHA256`00277861640fec25b374f9bfb145352f0d5936252a8e6e6e1d9aa688317b5ca6`.
It was downloaded, verified and publicly delivered; the user now approves the
stability improvement. Independent sustained-session telemetry remains open.
Musical transition quality has not been accepted by this stability feedback.

Historical vc79 defect: Higher Love cut off midmix. Both USB repeats and the
user's video contained a matching approximately 3.76s silent gap. vc80 repairs
finite-loop source availability and physical handover mapping, and the user
confirms the dropout is gone. The shorter 64–85ms gaps in the old USB capture
still have unresolved capture-versus-playback origin; do not equate them with
all remaining stutter.

**vc80 installed; dropout accepted, stutter unresolved:** finite-loop source/handover repair `c1091fc` plus the reviewed
consecutive-mix timestamp fix `2cb6e6f`, branch `vc80-loop-source`.
[Actions run 44016339991](https://github.com/survey-sudo/bitchord-mix/actions/runs/44016339991)
passed both jobs and all actual steps. HTML totals: shared **497 / 0 failures**,
app **1,399 / 0 failures / 1 ignored**; all 28 focused regressions passed. Full
native gate passed. Signing/certificate/alignment verification and USB update passed.
Signed SHA-256 `47163057210e0adebeb8fbb46ce69664576f2ed81b974d586040c1da328020e4`.
The short USB gaps have nonquiet
counterparts in the other take; their capture-versus-playback origin is unresolved.

`[x]` means the narrowly worded task has evidence. An implementation checkbox does not complete a separate sound-quality checkbox. Open items marked **implemented; validate** should not be rebuilt simply because they are unchecked. Priorities below order the work; they do not authorize a simultaneous broad patch.

Current delivery host: **[cue](CUE_ANDROID_BUILD_HOST.md)**, provisioned with the
existing signing material and vc80 source. Keep Gradle `-Xmx5g` and four workers;
preserve unsigned-build/CLI v2+v3 signing. Workstation USB ADB is tunneled to cue;
the phone is currently disconnected. Actions remains a CI/fallback option.

## Next work: planning continuity, listening quality and major capabilities

| Order | Deliverable | Current state / next proof |
|---|---|---|
| Baseline | **Preserve accepted vc81 stability** | User-approved improvement after the planning repair. Retain the regressions and observe sustained sessions; no exact device transition count or permanent absence of recurrence is claimed. |
| 1 | **Resolve remaining mix-quality complaints** | Early fade/sequential handoff remains reported; stutter and sudden demuffle are intermittent/unidentified. Capture an actual heard failure and distinguish plan choice, gain/filter behavior, repeated/dropped content, stretch, scheduling and output-route/capture effects. Retain the accepted vc80 dropout fix. |
| 2 | **Trustworthy musical timing and song-part awareness** | Reconcile measured beats/downbeats with structure timing; carry local confidence and coverage; validate intro, verse, prechorus, chorus, bridge, outro, builds/drops and recurring riffs/hooks. Preserve vocal sentences, pickups and musical endings. |
| 3 | **Clean vocal/instrumental and bass handoffs** | Validate the existing two-part separation on real transitions, resampling alignment, stem-to-dry transfer and low-end ownership. Evaluate independent bass/drum/other stems only when they improve comparative listening. |
| 4 | **Musically chosen phrase extensions and DJ effects** | Validate loop boundaries and release, tempo ramps, rolls, flanger, echo and reverb; choose each from actual arrangement opportunities and keep simpler transitions available. Existing code presence does not complete this milestone. |
| 5 | **Measured Snapdragon NPU acceleration** | Prove HTP dispatch on SM8635 and useful complete-pipeline latency/energy benefit with acceptable output; preserve the CPU route and keep inference off the audio callback. |
| 6 | **Demonstrated Apple AutoMix parity** | Use matched recordings/settings, blind level-matched listening, representative pairs and sustained device testing; retain all failures and fallbacks. |

The user authorized the next quality pass and requested a four-seat council.
All seats keep the unfinished EOF/controller patch excluded. The selected next
action is one offline comparison of accepted vc81 versus ONLY the classic
DECLINE faders, using production controller/filter code and retained per-deck
PCM. The offline checkpoint is `vc81-overlap-eval`; the same isolated worktree
`.slim/worktrees/automix-overlap-eval` now holds `vc82-overlap-listen` following
the user's instruction to proceed with a listening candidate. Accepted
`vc81-automix-replan` remains unchanged. Gain overlap alone cannot establish
audible recognition or relevance to a native-mode complaint. See
`../automix-stutter-2026-11-17/OVERLAP_COUNCIL_2026-11-17.md` for agreement,
disagreement, evidence budget and stop conditions.

**Offline pass complete; not promoted:** evaluation `2fb6b1f` passes15 app +21
shared focused checks and baseline/repeat render checks. Actual production-filter
synthetic PCM has stronger outgoing and incoming contributions, central summed
RMS +3.13dB multitone / +2.85dB percussion, and1.34s of both gain commands>=0.5
versus0s. Geometry/filter trajectories are identical; baseline repeats and the
compatible non-DECLINE control are bit-identical. No dry real-song sources or
current executed DECLINE failure were available. Musical benefit remains
inconclusive; keep accepted vc81 and the isolated candidate for a relevant
phone/song comparison. Evidence: `../automix-stutter-2026-11-17/overlap-eval/README.md`.

**User-authorized listening candidate:** vc82 / **2.2.3-djmix40**, source
`60b9734711b3a0e3214a31f76eac7766c61ae873`. Full cue checks pass shared500/app1410,
zero failures/errors and one existing app skip. Native gate, unsigned-build/CLI
v2+v3 signing, trusted certificate, payload audit and16KiB alignment pass.
Signed APK SHA256`a07617094ba02cd3fbd5d393468a52d35d226a96c2a1e1d11c9f98e64f9d3bc9`.
Web delivery is verified at
https://lossless.example.com/downloads/bitchord-vc82-djmix40.apk with matching full
public-download hash; approved vc81 remains available and unchanged. This
candidate changes only classic DECLINE fader behavior and does
not include the unfinished EOF recovery. Listening acceptance remains open.
See `../automix-stutter-2026-11-17/VC82_LISTENING_CANDIDATE.md`.

The dropout milestone is complete for the user's vc80 listening check. These
remaining priorities are the next roadmap, not additional work started by this
documentation update.

## Completed foundations

- [x] Convene all four council seats and reconcile their findings with primary-source checks. See [research report](AUTOMIX_RESEARCH_2026-11-17.md).
- [x] Establish Apple-confirmed beat matching, time stretching, key/tempo selection, and adaptive silence trimming/simple crossfades. Keep secondary looping/reverb/delay reports and undisclosed stems/flanger/NPU claims distinct.
- [x] Confirm Snapdragon 8s Gen 3 / SM8635 has a Hexagon NPU. Verify the current app disables QNN and uses ORT CPU inference; this is not a hardware absence.
- [x] Identify a current Android QNN deployment route in version-pinned official documentation. Device/model compatibility and benefit remain open below.
- [x] Establish the existing implementation inventory: beat/downbeat analysis, vocal estimation/two-part stems, structure heuristics, phrase planning, Signalsmith stretching, EQ bass handoff, loops, rolls, flanger, echo and reverb.
- [x] Repair the controlled native reverb wet-stop defect in vc78 and retain mutation-sensitive tail/preservation checks. This proves that boundary repair only.
- [x] Implement model-domain vocal/instrumental complement and bounded endpoint completion; retain tests and native calibration. Original playback-rate reconstruction and production listening remain open.
- [x] Reproduce the frozen-renderer-endpoint comparison with unmodified native code: initially aligned PCM can produce a false backward correction exceeding 150 ms. Preserve the genuine-drift control and block-size qualification.
- [x] Reproduce the separate finite incoming-loop defect on vc79: all six full-region native cases silence valid mapped PCM; straight/generous controls pass, and physical handover differs from the unwrapped final knot. Synthetic geometry is explicitly distinguished from the captured accepted plan.
- [x] Implement and review the vc80 finite-loop source/handover correction. Full native gate passes, including 63 finite-loop reference pairs and controls for physical mapping, real EOF, held playback and existing loop rolls. Old-renderer and raw-EOF mutation controls fail as required.
- [x] Obtain user confirmation on vc80 that the Touch the Sky → Higher Love mid-mix dropout is fixed. This acceptance applies to the reported dropout; stutter and broad transition quality remain open.
- [x] Deliver vc81 and obtain user approval of improved stability: “Okay I approve it's way stabler.” Preserve `e7c8a3e` as the accepted baseline; mix quality and independent sustained-session evidence remain separate.
- [x] Implement and review preserved sink timestamp offsets across consecutive mixes; all three production-sink regression cases passed in vc80 CI. Audible stutter acceptance remains open.
- [x] Implement and independently review the vc79 loop completion correction, including current presentation clocks, actual knot anchor, stale-generation rejection, publication races, explicit recovery, and spare-only retirement.
- [x] Author and execute controller, port and sink regressions exercising production cleanup: **17 new tests passed** (12 controller, 4 port, 1 sink).
- [x] Pass the required native gate for the candidate's unchanged native inputs: 16 success-path assertions, 11 calibration invariants, 14 renderer self-tests, and the production reverb-stop probe.
- [x] Establish lossless device playback-duplication capture tooling without a new app dependency. The vc79 USB recording captured two rejected loop transitions and matched the uploaded clip. vc80 capture1 completed successfully but recorded no transition events, with unchanged idle media snapshots; it does not independently verify the later user listening result.
- [x] Record the user's positive unchanged-vc78 listening feedback for **Far — SZA → BIRDS OF A FEATHER — Billie Eilish**. Recent telemetry shows classic fallback; this is not proof of repaired loop playback or general parity.
- [x] Preserve the working delivery route: Actions unsigned APK → existing-key signing on `bk-android` → `adb install -r`; no local Android build or key export.
- [x] Record cue as the new self-contained build/signing host and verify hostname SSH, current source/resources and the workstation ADB bridge. The user supplied matching native/unit/release results; no extra build was run for this update. Future delivery can use cue without `bk-android` cooldown.
- [x] Inspect cue's existing unit reports and CLI-signed vc80 artifact: counts match CI, certificate and v2/v3 verify. Record wrapper limits: signed APK currently only in `/tmp`, `native` omits calibration/sanitizers, and properties restore needs checking after an interrupted build. No host-wrapper changes or fresh builds performed.

## P0 — finish the current loop-handover candidate

Owner: orchestrator for reconciliation and artifacts; implementation specialist for focused repairs; user/device listening for perceptual acceptance.

- [x] Compile and execute the full required shared/app suites in Actions. vc80 downloaded HTML totals: **shared 497 / 0 failures / 0 ignored; app 1,399 / 0 failures / 1 ignored**. All 28 focused cleanup, source-domain and sink tests passed without skips.
- [x] Verify release native checks, assembly, audit and artifact upload individually for `2cb6e6f`. All passed; the job's `continue-on-error` did not mask a failed step.
- [x] Sign the exact passing source artifact and verify package/version, matching certificate, SHA-256 and 16 KiB alignment. Signed vc80 SHA-256: `47163057210e0adebeb8fbb46ce69664576f2ed81b974d586040c1da328020e4`, 67,552,565 bytes; all 1,592 original CI payload entries match.
- [x] Update the phone with `adb install -r` and confirm installed identity: serial `1439ebea`, version 80 / 2.2.3-djmix38. No uninstall or data clear; launch did not begin playback in the observed interval.
- [x] Record vc80 user listening acceptance of the specific dropout repair.
- [x] Reproduce and repair the lyric-boundary planner exception and ticker-death vulnerability; obtain user approval of vc81's stability improvement.
- [ ] Independently capture sustained vc81 planning/firing across successive tracks without restart. The user's stability approval is accepted; a measured transition count has not been captured.
- [ ] **Next priority:** obtain listening acceptance for musical overlap and remaining transition-quality complaints. The earlier universal-stutter description was superseded by intermittent/unreproducible feedback.
- [ ] Confirm queue/position/pause restoration in a controlled interval. Pre-install Angel Bite was deliberately paused; the later snapshot shows another song playing and a changed queue. No agent play command was sent; concurrent user interaction has not yet been distinguished from app restoration behavior.
- [ ] Capture an **actual loop-mode** transition through final decoder handover, with accepted plan/path, source IDs, seek events, clock observations and synchronized stereo output. Avoid mistaking a classic fallback for a loop test.
- [x] Obtain a real-device negative example: two LOOP-mode Touch the Sky → Higher Love repeats are inside a successful USB stereo capture, with no cleanup seek. Exact PCM-to-event synchronization and full accepted script remain to be established; the user reports “Still abrupt.”
- [ ] Verify a same-recording A/A handover at unity rate/gain through the relevant playback boundary. Native-only rendering is the preparatory control; it does not replace Android sink/decoder evidence.
- [ ] Demonstrate that aligned normal loop completion does not seek to a frozen endpoint, repeat/skip content, or reset the surviving decoder. Include delayed cleanup and the real device's clock-publication cadence.
- [ ] Verify genuine drift and unstarted/buffering recovery on device; distinguish recovery from a successful seamless completion. Exercise pause, seek, skip, focus loss and stalled playback without automatic user-pause resumption or indefinite waiting.
- [ ] Compare the failing pair using current versus human-validated cues under the same stripped processing, then restore processing one stage at a time if needed. This separates plan quality from transport quality.
- [ ] Confirm the classic fallback remains acceptable and obtain user listening acceptance of the candidate's loop path. Preserve the successful Far→Birds classic observation as a control.

**Gate:** a passing build and a small phase scalar do not complete P0. Require the intended runtime path, captured continuity evidence, and listening feedback. If capture or the actual loop path is unavailable, leave acceptance open.

## P1 — make continuity and preparation dependable

Owner: one playback/DSP writer per shared file, with focused tests and device captures of the changed boundary.

- [ ] Separate startup filter initialization from audible seek/flush continuity; measure the current cutoff/state snap and rapid abnormal survivor reopening before choosing a change.
- [ ] Verify processing equivalence across renderer→decoder transfer: sample position/rate, latency, regional gain, normalization, filters/isolator phase, stereo response and limiter recovery.
- [x] Reproduce and repair finite incoming-loop EOF and unwrapped-to-physical handover mapping; retain straight/hold/fold/short-decode/roll controls. User confirms the dropout is gone on vc80.
- [ ] Independently capture the repaired vc80 seam with its actual runtime path and verify that no corresponding long gap remains. The completed vc80 capture1 did not observe the user's replay.
- [ ] Locate and resolve remaining intermittent stutter with source/capture distinction. Cover the complete mix and continuing playback; short exact-zero runs alone do not identify the failing stage. The completed synthetic diagnostic did not reproduce persistent collapse under normal tested stimuli; it does not justify a native DSP change or establish device smoothness.
- [ ] **Implemented; validate beyond the vc78 probe:** wet-tail handling for scheduled reverb, ordinary echo, interruption and direct effect switching; retain tails without stale-state leakage or hard cutoffs.
- [ ] Replace placeholder output-quality inputs with real measurements or explicit “unmeasured” results. vc79 labels its verifier `phase-and-exit-gate-only`; it does not measure loudness/peak continuity.
- [ ] Characterize sample peaks versus true peaks, local loudness, low-band energy, onset alignment and dropped/repeated content at complete seams. Validate the native limiter's behavior across callback partitions rather than relying on its “true-peak” comment.
- [ ] Resolve real preparation failures seen in recent playback (`build-missed-splice`, `shift-bar-null`) using measured preparation distributions and sufficient lead. Do not force an elaborate late transition to increase loop counts.
- [ ] Make model-lock waits, cancellation and post-lock deadline checks compatible with playback deadlines; avoid repeating separation work after pair failure when it cannot finish in time.
- [ ] Isolate build timing and mutable decode metadata by generation, and verify prime/cache identity includes all rendering-relevant inputs.
- [ ] Validate buffering/stall recovery through its final audible state, including READY-but-stalled and intermittent stalls where relevant. A Boolean watchdog test alone does not establish liveness.

## P2 — improve musical decisions and structure confidence

Owner: analysis/planning specialist, checked against annotated excerpts and controlled listening.

- [ ] Use trustworthy measured beat/downbeat timestamps in structure/cue selection. Reconcile the synthetic zero-origin PCM grid with actual model grids and audit sparse head/tail indexing across unmeasured gaps.
- [ ] Represent confidence, provenance and coverage per feature and region. Keep unknown vocal/beat information distinct from measured silence or a stable grid.
- [ ] Recompute or invalidate labels, roles and loopability when new vocal evidence changes their premises. Existing enrichment updates occupancy while retaining some earlier conclusions.
- [ ] **Implemented heuristics; validate:** intro, verse, prechorus, chorus, bridge, instrumental and outro boundaries; build/breakdown/drop events. Treat riffs/hooks as motifs that can occur inside several section types.
- [ ] Use local tempo, key, energy, loudness, density and instrument occupancy at the proposed overlap; validate half/double-tempo interpretation and drifting/rubato recordings. Do not widen tempo caps to mask failures.
- [ ] Protect vocal sentence endings, pickups, sustained notes and deliberate endings/silence in the final rendered plan. Lyric lines and sung-bar flags are evidence, not exact phonetic boundaries.
- [ ] Score simple/natural transitions alongside more elaborate choices. Ensure rescue/fallback preserves meaningful confidence and musical vetoes rather than overriding them merely to return a plan.
- [ ] Select effects from detected musical opportunities and listener intent, beyond BPM range or pair-hash variety. Keep deliberate high-energy DJ transitions possible when they fit.
- [ ] Validate loop/phrase reuse using rhythmic, harmonic and waveform evidence; energy-plus-sung-bit fingerprints alone do not establish the same riff or a safe repeat.

## P3 — validate the requested DJ capabilities

Owner: DSP/stem specialist; completion requires device-rendered examples across suitable and unsuitable material.

| Capability | Current implementation state | Acceptance still required |
|---|---|---|
| Beatmatch and tempo adjustment | Signalsmith and beat-clock paths exist | No rhythmic stumble, smeared attacks or sudden tempo return over supported material; conservative fallback otherwise. |
| Sample/automation interpolation | Resampling, ramps and delay processing exist | Correct rate/latency/stereo handling; smooth automation; no conflation with generated musical content. |
| Vocal/instrumental handoff | umxhq estimate, complementary remainder and duck/acapella paths exist | Successful production separation, aligned recombination through real resampling, intelligible vocals and no watery entrance/exit. |
| Bass swap | Low-band EQ/isolator control exists | Musically timed low-end transfer without boom/hole; independent bass-instrument control is a separate capability. |
| Loops, intro/outro extension, rolls | Native loops and phrase/roll paths exist | Complete musical units, clean joins/release, no repeated partial words or unresolved harmony. |
| Flanger, echo, reverb, EDM build/drop moves | Processing and scheduling exist | Appropriate selection, beat-aware evolution, headroom and graceful release on actual recordings. |
| Independent bass/drum/other stems | Not established in the current two-part path | Evaluate only if those controls materially improve mixes; prove model quality, licensing, memory and preparation cost. |

- [ ] Validate the first six existing capabilities in that table; do not count code presence as quality parity.
- [ ] Add independent instrument stems only if comparative evidence justifies them. Preserve the useful two-part baseline and a simpler fallback.
- [ ] Choose any new separator/stretch engine by measured quality and device cost. The old blanket Demucs rejection and one deployment's memory figure are not universal conclusions; code and checkpoint terms need separate checks.

Flanger, full stem separation, and NPU use remain requested BitChord capabilities, not verified Apple AutoMix internals. Comparable listening quality is the parity target.

## P4 — use the Snapdragon NPU where it earns its place

Owner: isolated inference/deployment lane after the audible baseline is stable; it must not disturb the working CPU playback path.

- [ ] Pin a compatible current Android QNN/QAIRT/ORT combination and verify exact SM8635 firmware/runtime support. Evaluate the official Android package before reviving the old reduced-runtime recipe.
- [ ] Prove a minimal static sample executes on HTP, with provider assignments and execution traces; disable CPU fallback for this diagnostic. Session creation, `.so` loading and a “QNN” label are insufficient.
- [ ] Evaluate one real model at a time for fixed shapes, operator/type coverage and supported FP16/quantized options. Compare current CPU output, CPU execution of the candidate export, and HTP execution of that same export.
- [ ] Correct diagnostic truthfulness and model/context cache identity/reload; handle unsupported partitions, initialization failure, later execution failure and corrupt/incompatible caches explicitly.
- [ ] Measure cold/warm complete preparation time, memory, energy, thermal behavior and sustained playback—not inference milliseconds alone. Retain CPU fallback and keep model work off the audio callback.
- [ ] Accept NPU deployment only after it provides useful measured benefit with acceptable musical output. No speedup or battery-saving percentage is established yet.

## P5 — demonstrate parity, then claim it

Owner: orchestrator curates reproducible evidence; blind listeners/user establish preference and acceptance.

- [ ] Freeze a representative comparison set and its exact recordings/masters. Include known failures, the positive Far→Birds classic case, dense/cold vocals, steady instrumental material, tempo/key gaps, beatless/drifting material, and deliberate endings.
- [ ] Obtain comparable Apple AutoMix captures with OS/version, queue context, Sound Check, Atmos/spatial processing, EQ, quality and output route documented. Apple catalog eligibility and unavailable Hi-Res Lossless transitions affect the comparison.
- [ ] Capture BitChord with its actual mode/accepted plan and record all attempts, refusals, preparation failures and fallbacks. Do not cherry-pick successful demos.
- [ ] Run randomized blind, whole-excerpt constant-level-matched preference comparisons. Rate startling change, timing, vocal integrity, tonal continuity, artifacts and musical intent separately; keep repeats for consistency.
- [ ] Predeclare acceptance criteria and report uncertainty/worst cases. The older “20 pairs / ≥60%” proposal is not an established statistical parity gate and must not silently become one.
- [ ] Demonstrate sustained device reliability, acceptable memory/thermal/battery cost, and preserved pause/queue behavior over repeated transitions.
- [ ] Record user acceptance and a reproducible comparison report before describing BitChord as at-or-above Apple AutoMix quality.

## Evidence and update rules

Research: [AUTOMIX_RESEARCH_2026-11-17.md](AUTOMIX_RESEARCH_2026-11-17.md). Historical failures and constraints: [AUTOMIX_HANDOVER_2026-11-17.md](AUTOMIX_HANDOVER_2026-11-17.md). Current candidate/install status: [REMAINING_WORK.md](REMAINING_WORK.md).

Session artifacts are outside git under `../automix-seam-2026-11-17/` relative to the repository root: `EVIDENCE.md`, native reproduction/gate, capture logs, and CI artifacts. Update candidate checkboxes only against exact source/run/artifact evidence. Preserve negative and positive listening evidence together.

Retain these invariants in every phase: no workstation Android builds, no key export, no APKs/keystores in git, no user-data reset, preserve the queue, never automatically resume a user pause, and never reset the surviving deck while retiring the spare. Use cue's resource/signing/bridge contract for remote work. An emulator elsewhere can supplement checks; cue has no emulator route and the target phone remains required for the final result.
