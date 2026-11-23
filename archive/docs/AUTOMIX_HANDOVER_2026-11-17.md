# BitChord Automix — complete handover at user-requested stop

Written: **2026-11-17 local (UTC+7), approximately 04:36**, which is **2026-11-16 21:36 UTC**.

## 1. Read this first: the work is stopped and the audible defect persists

Latest user response, verbatim:

> Its still extremely disappointing how not smooth the transition of em. Tried on three different tracks and they surprised/jumpscared me of the sudden abrupt change. Stop here. Write total handover so I can start new session with you guys

This overrides the earlier authorizations to implement/build/install/test. This handover is the final authorized task for this session. **Do not autonomously resume implementation, builds, installation, or playback testing from this file.** Wait for the user's renewed instruction in the new session.

The user is right that the end result has not met the goal. Both `vc77` and `vc78` passed tests but failed listening. `vc78` remains installed on the phone. We have fixed at least one objectively reproduced native reverb cutoff, but **we have not established the cause of all the abrupt transitions the user hears**, and the latest build did not make them acceptable. Do not present CI success, `mix.verify.ok`, the native probe, or a reviewer endorsement as proof of graceful audio.

Earlier `vc77` listening feedback was also explicit:

> Zap still present, It still do, it sounds abrupt and not graceful

The original goal was Automix quality at or above Apple Music, particularly fixing abrupt demuffling/reverb "zap" and the stem issue. **Apple Music parity is unproven and must not be claimed.** No blind, level-matched A/B against Apple was completed.

## 2. Exact repository, branch, and installed build

| Item | Value |
|---|---|
| Repository | `/home/user/Bitchord/work/bitchord-mix` |
| Remote | `https://github.com/survey-sudo/bitchord-mix.git` (private) |
| Current branch | `vc77-ci` — despite the name, its HEAD is vc78 |
| Current source HEAD | `c2c7f32180b3e9c28922b384fc6c20a34135171f` |
| Remote `origin/vc77-ci` | Same `c2c7f32180b3e9c28922b384fc6c20a34135171f` at last check |
| `master` / `origin/master` | `2d502985d0ce7a97bb30bbaf9f28a4815be6db31` (vc76) |
| Merge status | Follow-up branch pushed; no master push or merge performed |
| Installed package | `com.music.bitchord.loopfix` |
| Installed version | `versionCode=78`, `versionName=2.2.3-djmix36` |
| Installed source | `c2c7f32180b3e9c28922b384fc6c20a34135171f`, CI run `43955519403` |
| Installed signed APK SHA-256 | `3fdb79a6a97a1e1cd302c1b42e302d3c26151608c096eff59be993f60bfeb6e9` |
| Signed APK size | `67,548,469` bytes |
| Signing certificate SHA-256 | `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` |
| Last launch PID | `30553` — historical only; do not reuse without checking |

Source is committed. At the stop, the only previously dirty tracked file was `docs/REMAINING_WORK.md`, containing post-CI install/listening evidence. This handover adds `docs/AUTOMIX_HANDOVER_2026-11-17.md` and updates the stop status in `REMAINING_WORK.md`. **Those documentation changes are intentionally left uncommitted and unpushed.** No production-code edits were made after the stop request.

### Commit sequence

| Commit | Purpose |
|---|---|
| `2d502985d0ce7a97bb30bbaf9f28a4815be6db31` | vc76: Pass-2 SongMap enrichment, dynamic stem duck, earlier Automix work; installed baseline with audible defects |
| `08cbc86d4f8071a76687afcd2caca5cb05cfd13d` | Initial vc77 changes: clash metrics, model-domain stems, filters/reverb/controller changes, version/gates |
| `39290def8024a66840d1caa993a83f4d1c440f97` | vc77 follow-ups: prime retention, atomic phrase acceptance, abnormal-exit recovery/watchdog, bounded native stem endpoints |
| `c2c7f32180b3e9c28922b384fc6c20a34135171f` | vc78: native wet retirement, production reverb probe, CI enforcement, version/audit updates |

## 3. User requirements and invariants to preserve

- Work on the actual device `1439ebea` over Wi-Fi ADB for the final result. Android unit tests alone are insufficient.
- Preserve Seam A: never park/reset the surviving session deck as part of retiring the spare. Filter/renderer ownership must be evaluated against physical active/spare roles and pre-/post-handoff track roles.
- **Important unresolved tension:** vc77 follow-up deliberately added an audible survivor-filter reopening on abnormal exit, described in code as a roughly 30 ms glide. That does not establish a graceful musical return. Its test verifies routing, not perceptual continuity. Re-examine it rather than assuming the invariant is satisfied merely because the spare is silent.
- No local Gradle/Android builds on this PC. Native C++ harness compilation and file/static checks are allowed locally.
- Builds now run on GitHub Actions. Signing runs on `bk-android` with the existing key. The key was never uploaded to GitHub or copied off the signing box.
- Keep APKs and keystores out of the repository. All release/test artifacts are under sibling `/home/user/Bitchord/work/` directories.
- Preserve existing app data and queue. Install updates with `adb install -r`; no uninstall/data reset was performed or authorized.
- Do not auto-resume a user pause. Numeric pause reasons were misread once; the correct dependency constants are documented below.
- Do not keep asking permission for already-authorized routine work, but the final explicit **stop** is authoritative.

## 4. What was implemented in vc77

### Shared planning and DSP

- `PhrasePlanner`: `musicalRescue()` now computes and returns `vocalClashEnergy` and `vocalOverlapBeats` instead of hard-coded null/zero. Both clash helpers accept actual `overlapBars`. Measurement uses `minOf(exit.roomBars, maxOf(entry.leadInBars, overlapBars), O_MAX_BARS)`, so zero-lead HEAD entries can measure the planned overlap. Veto and score pass the same overlap.
- `DjColour`: added smoothed `reverbWet` with per-sample coefficient based on 150 ms; `NONE` and zero-amount REVERB continue processing decay. This is an exponential time constant, **not a promise that all tails are silent after exactly 150 ms**.
- `VocalTracker.separateBothWindows`: one vocal inference/separation, instrumental complement computed at 44.1 kHz before resampling both parts back. Pure helper `complementAtModelRate()` added.
- Follow-up size validation now matches native floor sizing. A second helper, `completeDualStemEndpoint()`, accepts a bounded 0–2 missing samples at 48 kHz/44.1 kHz double-floor roundtrip, holds the final vocal sample, and fills instrumental tail with `dry - vocal`. Larger/mismatched/oversized/empty outputs are rejected.
- This endpoint repair establishes reconstruction on the completed tail. It does **not** prove the entire resampled pair is sample-identical to the original 48 kHz dry signal for arbitrary music. Tests use a linear stand-in for the native resampler; real native alignment/perceptual quality remains a separate question.

### Controller, filters, and loop session

- Added retiree-only audibility checks and spare-filter neutralization; `finish()` mutes the outgoing spare after handoff.
- `armLoop()` opens the physical spare filters before raising player volumes; role/order assumptions still require care.
- Classic fade completion requires incoming filter progress to catch up (`>=0.999`) unless outgoing is near EOF, with `rideFilters(1,1)` clamping at completion.
- Added 2.5-second continuous-BUFFERING watchdog when progress is already 1 and incoming is not ready. It calls existing forced bail, preserving the selected survivor. It does not cover all READY-but-stalled states or cumulative intermittent stalls.
- REVERB controller amount ramps over progress 0.75→1.0. `releaseColour()` replaced hard `parkColour()` calls in ordinary echo cleanup; hard park remains for reset.
- `TransitionFilterProcessor.onFlush()` was initially changed not to snap current cutoffs to targets, then **reverted** after LPF/HPF startup tests failed. Final code still unconditionally snaps current→target and zeroes states. The test still has a misleading `...WithoutSnapping...` name while asserting the snap. This is unresolved, not a completed no-zap fix.
- `LoopMixSession` prefers a dual vocal/instrumental pair, with single-stem fallback. Added a weighted-clash fallback for unscripted paths and fixed one fade-start coordinate mixup.
- Added a linear reverb-send taper ending at `outStopMixSec`. This proved insufficient: sending zero is not equivalent to fading ringing output.
- Prime builds no longer permanently drop all stem functions solely because `wholeTrackBusy()` is true; arm-time contention protection remains. Live deadline checks still exist, but a blocking `modelLock` acquisition can outlast the check.
- Phrase compilation now produces `PhraseCandidate`; accepted plan, script, detail and logs are committed only after acceptance. This fixes rejected-candidate side effects. Legacy non-phrase bridge paths intentionally clear `loopScript`; blindly removing all clears would be wrong.
- Added abnormal-exit survivor recovery via track-role mapping. It returns the survivor to open filters after retiree silence; **this can still audibly reopen the survivor rapidly**.
- Cleared `tailTarget` at final cleanup, after gates/retirement, preventing an old retired player's volume reset from poisoning later audibility checks.
- Shift fallback still has the one extra straight-phrase attempt accepting a start only 150 ms ahead. It has not been redesigned to guarantee enough build lead or one retry over the whole mix lifecycle.

### Tests introduced/extended

- `PhrasePlannerTest`: rescue and zero-lead HEAD clash coverage, reverb decay behavior.
- `VocalTrackerStemTest`: model-domain complement, floor sizing, malformed output rejection, endpoint completion.
- `Vc70ExitLegAppTest`: spare/survivor routing, reverb ramp, incoming completion, abnormal exits, stale tail.
- `Djmix31AppTest`: flush/reset behavior; beware the misleading no-snap name.
- `Djmix32AppTest`: prime-versus-arm stem contention policy.
- `FadingStallWatchdogTest`: pure watchdog gate cases.
- Atomic phrase acceptance has no direct end-to-end rejection/acceptance regression test; lack of a controller harness was incorrectly claimed by one worker even though reflection/proxy controller tests exist.

## 5. What vc78 actually changed and proved

Changed runtime file: `native/loopmix/loop_mix_renderer.cpp`. No new Kotlin pause handling was introduced.

- `ReverbWash::apply` no longer returns early solely when send is near zero. Zero send gates new input; stored comb energy continues to advance.
- `wetRetireStart()` chooses the reverb release point from a genuinely decreasing final send-envelope suffix. Constant/increasing sends fall back to a sustained dry-gain zero or a bounded final 1-second window. Incoming `runOn` and unbounded-stop decks bypass scheduled retirement.
- `wetRetireMul()` applies a Hann-shaped output fade from that release point to `stopAt`, reaching zero per sample even when a render block straddles the stop.
- Rendered reverb output is multiplied by scheduled retirement and existing emergency cancellation. Emergency cancellation now also scales echo wet output; **ordinary scheduled echo shape was restored unchanged** after review.
- No new audio-thread allocations or locks were introduced by the helper scans/multipliers.
- `tools/vc75-gate.sh` now expects vc78/djmix36 and requires `--reverb-stop-probe`; both Actions jobs invoke the probe before Android build/test work.

### Native evidence

The older `--selftest` reverb case had send zero at 3 s, gain zero at 4 s, stop at 5 s. It could pass while production stopped a still-ringing tail. It remains in the suite but is not the decisive regression test.

The new `--reverb-stop-probe` uses deterministic broadband transients and representative production geometry: beat 0.5 s, release 6 s, stop 7.05 s, send `{open, release, stop} -> {0,0.65,0}`, and dry gain zero at release.

| Measurement | Old renderer | vc78 candidate |
|---|---:|---:|
| Standard active RMS | −22.935 dBFS | −22.935 dBFS |
| Standard final-50 ms RMS | −49.174 dBFS | −99.897 dBFS |
| Active-to-final drop | 26.239 dB (fails >40) | 76.962 dB (passes) |
| Standard sample cut step | 0.003066 (fails <0.001) | Below threshold, printed 0.000000 |
| Early wet-only W1/W2 | −28.901 / −29.422 dBFS | −28.985 / −29.844 dBFS |
| EDM final drop | 25.699 dB (fails) | 76.436 dB (passes) |
| Dry exit-gate violations | 0 | 0 |

Mutation checks: removing the wet-retirement multiplier restores the failure. Muting reverb makes the boundary look quiet but fails the added wet-only preservation floor (−60 dBFS). Both standard and EDM cases pass for vc78. Candidate/base `--selftest` both pass. Parent independently ran the final gate successfully.

**Limitation:** this proves a native reverb boundary repair under controlled input. It does not prove that the user's actual failing transition selected `reverbWash`, nor cover renderer-to-decoder tonal changes, corrective seeks, filter reopening, stem artifacts, all tempos, every device block size, or perceived musical phrasing. User testing of vc78 still failed across three tracks.

## 6. Fresh CI, artifact, and install evidence

| Candidate | Source | CI run | Shared | App | Device result |
|---|---|---|---|---|---|
| Initial vc77 | `08cbc86` | `43900627231` dispatched; not the final device source | Earlier cloud 486 passed | Earlier cloud full 1363 had timing benchmark failures; isolated pass | Initial signed APK retained only |
| vc77 follow-up | `39290de` | [43912914370](https://github.com/survey-sudo/bitchord-mix/actions/runs/43912914370) | 495, 0 failures, 0 ignored | 1373, 0 failures, 1 ignored | Installed; user reports zap still abrupt |
| vc78 | `c2c7f32` | [43955519403](https://github.com/survey-sudo/bitchord-mix/actions/runs/43955519403) | 495, 0 failures, 0 ignored | 1373, 0 failures, 1 ignored | Installed; user reports abrupt/jumpscare changes on three tracks |

Latest counts were independently parsed from downloaded HTML reports. The workflow uploads HTML, **not XML**. The single ignored test is the live Genius test. Do not conflate these clean fresh runs with the earlier cloud suite that failed a `<50 ms` shuffle benchmark before passing in isolation.

The Actions release job still has `continue-on-error: true`. Actual native, assemble, audit and upload steps were individually checked successful for the two final runs; overall workflow success alone is not enough.

Observed vc78 job durations: unit tests 10m05s, release 21m41s. Earlier vc76 release compile on Actions was 17m41s versus 55m18s on the box; initial vc77 box release was 57m45s. Earlier claims that GitHub definitely had 16 GiB RAM or that network disk was conclusively the bottleneck were unsupported; do not repeat those as established facts.

### Signed/unsigned artifacts (outside git)

| Build | Location / SHA-256 |
|---|---|
| vc78 signed, installed | `/home/user/Bitchord/work/vc78-ci/43955519403/signed/app-prod-arm64-v8a-release.apk`; `3fdb79a6a97a1e1cd302c1b42e302d3c26151608c096eff59be993f60bfeb6e9`; 67,548,469 B |
| vc78 unsigned | `/home/user/Bitchord/work/vc78-ci/43955519403/bitchord-prod-arm64/app-prod-arm64-v8a-release-unsigned.apk`; `e759ef212535141cdda0b58aecf69a2ba1d320e5de9e68fdc799c81e445002db`; 67,416,298 B |
| vc77 follow-up signed | `/home/user/Bitchord/work/vc77-followup-ci/43912914370/signed/app-prod-arm64-v8a-release.apk`; `485dd2aa978902d6a6887b958f34c6a5524cd78903c6458f9221d239d6752909`; 67,548,469 B |
| vc77 follow-up unsigned | `/home/user/Bitchord/work/vc77-followup-ci/43912914370/bitchord-prod-arm64/app-prod-arm64-v8a-release-unsigned.apk`; `2cc665fac3fc6b4161eec897c02e703fcb7fd03d1becd9078382ac871d57e66f` |
| Initial vc77 box build, not latest | `/home/user/Bitchord/work/vc77-release/app-prod-arm64-v8a-release.apk`; `f9b8adb544004c77553cfb3d9c734227ce1d5b7ece8359c5f44f1b850a16f606`; 67,435,254 B |
| vc76 baseline | `/home/user/Bitchord/work/vc76-release/app-prod-arm64-v8a-release.apk`; `02920d8278003c5fe9b492a3f0a0dc052ddb4b48a93d8e8ff2e2b84cb1e0ca79`; 67,433,006 B |

Each final CI artifact root contains `test-reports/`; each signed folder contains `sign.log`, `verify.log`, `audit.log`. Verification established correct package/version, v2/v3 signature, matching cert, 16 KiB zip alignment, one `libonnxruntime.so` of 34,208,672 B, no QNN/HTP libraries, and seven `libbitchord_*.so` libraries.

## 7. Device evidence: what happened and what did not get proved

Device: serial `1439ebea`, model `24069PC21G`, `peridot_global`. ADB executable `/home/user/.local/bin/adb`.

Last full ADB identifier: `adb-1439ebea-05jQT0._adb-tls-connect._tcp`. Last mDNS address `192.168.100.218:45715`; address/port and transport IDs are ephemeral. Phone reconnected before vc78 installation. Last install used `adb install -r`; no uninstall or data clear. Launcher component is `com.music.bitchord.loopfix/com.music.bitchord.LauncherDefault`.

Telemetry is append/ring-truncated JSONL at:

`/sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl`

It can be pulled without root; `run-as` fails on this non-debuggable release. Sequence numbers reset with app runs; compare `ts` and saved baselines, not `seq` alone. R8 strips many debug logs, but info lines such as `BitChordLoopMix: stem-duck ...` **were observed**. A recon claim that all relevant release logcat was unavailable was wrong.

### vc77 first capture

Root: `/home/user/Bitchord/work/vc77-followup-ci/43912914370/`.

- `device-before/automix-telemetry.jsonl`: 5398 parsed historical records at that snapshot.
- `device-after/vc77-logcat.txt`: timed 15-minute capture, exit 124 from timeout is expected.
- `device-after/automix-telemetry.jsonl` and `fresh-telemetry.json`: 31 new records after baseline.
- Two `mode=loop` transitions completed with `mix.verify.ok=true`, exit-gate 0. Heard minus expected handover +58/+60 ms. Tiny reported alignment errors are scalar checks, not end-to-end audible phase proof.
- Exactly one `stem-duck` info event: `clashE=0.14529196781328904`, floors 0.34413466, `duckIn=true`. Zero null-clash events among that one decision. **A decision log occurs before attempted separation; it does not prove inference succeeded or stems sounded good.**
- No arm-death/shift-bar-null/fatal event in this small capture. Two transitions cannot establish rare-failure rates or satisfy the originally discussed seven-pair gate.
- Playback was paused at final check; it was left paused.

### vc77 failed listening replay

Files: `device-after/telemetry-after-listening.jsonl`, `media-after-listening.txt`.

Manual seek moved queue index 8 to roughly 160040 ms. Loop fired at 171609 ms for target 172081 ms. Later `mix.pause.detected` with reason 1 and `playWhenReady=false` was followed by `mix.exit.guard` and a 350 ms emergency tail from full player gain, then TAIL completion.

**Corrected interpretation:** reason 1 is `USER_REQUEST`, not end-of-item. This event cannot establish that an internal pause caused the previously heard zap. The user may have paused in reaction to bad audio. No synchronized audio/timestamp evidence separates those possibilities.

### vc78 final capture at explicit stop

Root: `/home/user/Bitchord/work/vc78-ci/43955519403/`.

- `device-before/automix-telemetry.jsonl`: pre-vc78 baseline (max `ts=1794862031621`).
- `device-after/vc78-logcat.txt`: capture started for PID 30553, then **explicitly stopped** at user request by terminating its verified `timeout` parent; exit 143 is intentional. 3639 lines saved; no fatal exception or stem-duck line in this partial capture.
- `device-after/telemetry-at-stop.jsonl`: final read-only pull, 1,593,176 bytes at capture.
- `device-after/fresh-telemetry-at-stop.json`: 51 parsed records newer than baseline, saved for straightforward replay analysis.
- `device-after/media-at-stop.txt`: final read-only session dump. It did not show a current BitChord media session. Do not treat an unrelated player's error in this dump as a BitChord error. We did not issue any playback control during the stop/handover task.

Fresh event counts: 17 plan rejections, 5 plans, 5 dumps, 10 seeks, 2 loop fires, 1 exit-gate, 1 verify, 4 completes (including two arming cancellations), 1 freeze, 1 fallback, 2 pause detections, 1 exit guard, 1 exit tail.

Notable sequence:

1. Queue index 8: target 172081 ms, actual fire 171501 ms, preroll 1 s. At finish, `loop-handover-correct` recorded `expected=4440`, `heard=4488`, `target=4488`, `drift=-186 ms`; the session decoder was at 4674 ms and was sought backward to 4488. Listener discontinuity was tagged `handover-suppressed`. Exit gate 0 and `mix.verify.ok=true` followed, then normal `LOOP_RUNNING` completion.
2. Additional manual seeks/cancellations occurred. One `cold-loop-headroom-using-classic` fallback is recorded. Do not equate every `mix.complete` with a completed audible mix.
3. Queue index 9: target 201540 ms, actual fire 200940 ms. A reason-1 pause at incoming position 5867 ms led to full-gain exit guard and an 800 ms tail (`mode=planned`, reason `emergency-retire-guard`), then TAIL completion. The guard tail's duration classification is an observation, not a root-cause finding.

Track IDs appearing in `mix.dump`:

- `lyNbWD07o1Q -> WKZO-CWeOVA`
- `WKZO-CWeOVA -> -KrC-gqKTMg`
- `-KrC-gqKTMg -> sMCmIw8q9fQ`

`WKZO-CWeOVA` was shown as **BIRDS OF A FEATHER — Billie Eilish** in the earlier session. `aaC0DFzcFc8` was **party 4 u — Charli xcx**. Do not invent titles for the other IDs. The user said three different tracks, not three fully captured normal transitions; exact heard-defect-to-event association is unconfirmed.

The classic `mix.plan`/`mix.dump` entries often describe a decline fallback while `mix.fire` ultimately says `mode=loop`. **Do not infer that the final renderer used REVERB, DECLINE filters, or a particular script merely from an earlier fallback plan.** The current logs do not establish which audible effect path caused every complaint.

## 8. Unresolved issues and useful investigation targets (not new work authorization)

These are review findings/hypotheses, not a license to implement all of them. A new session should reproduce the user's actual failing seam before choosing a change.

1. **Renderer-to-decoder handover continuity.** The latest successful-loop telemetry includes a −186 ms corrective seek immediately before disarm. It could affect repetition, timing or timbre, but causality is not yet established. Read `finishLoop()` and `PrecisionAudioSink`/`LoopMixPort` splice behavior together. `phaseErrorMs` and `MixVerify` are not a rendered PCM continuity measurement.
2. **Survivor-filter opening remains abrupt by design.** `openSurvivorFiltersWhenRetireeSilent()` opens the audible survivor after an abnormal exit, explicitly accepting the processor's short glide. Routing tests do not prove this sounds natural. Do not call it Seam-A-safe solely on that basis.
3. **Active filter flush snap.** Final `onFlush()` still assigns current cutoffs/mid gain from targets and clears state. Startup initialization and audible seek continuity need distinct evidence. Prior oscillation between removing snap and rewriting tests to restore it did not solve the musical contract.
4. **Physical filter roles and silent renderer/decoder handover.** Inspect `armLoop()` unconditional spare opening, `tracks` mapping and `PlaybackService.adoptPlayer` together. No proven role-mapping bug was isolated, but a surviving decoder inheriting filtered/processed state remains a plausible class of symptom.
5. **Stems: decision versus actual separation.** Measured clash is now observed once, but no production success/fallback evidence for the resulting stem pair was captured. Pair failure can invoke single-stem separation again, increasing inference cost. Single-stem `copyOf` sizing remains; dual endpoint completion is bounded but uses synthetic-resampler tests.
6. **Analysis lock/deadline.** Prime stem retention can wait on `TrackAnalyzer.modelLock`; checking deadline before blocking does not guarantee completion after lock acquisition. No deadline-aware lock recheck was implemented.
7. **Build-generation timing contamination.** `DeckTiming` remains shared; old cancelled blocking decode work may append or close spans into a new build. A generation-owned timing proposal was returned by a worker but **not applied**. `deckSide` also remains shared across parallel decodes.
8. **Shift retry lead/call lifecycle.** The extra phrase retry still accepts +0.15 s despite fast/cold preparation leads around 0.8/4.5 s and runs selection on the controller path. One attempt per function call is not proof of one retry per mix. No final timing redesign was implemented.
9. **Prime-key completeness.** Reviewers questioned whether the primed-session cache key covers changed script/hold/clash parameters. Script atomicity was fixed; cache-key adequacy was not established or changed.
10. **Other effect-release behavior.** Classic `DjColour` reverb decay is processed under NONE/REVERB, but changing directly to FLANGER/LOOP_ROLL/ECHO may retain stale reverb state. Native echo ordinary stop remains its prior design; only reverb scheduled retirement was addressed in vc78.
11. **Watchdog end state.** The new buffering watchdog can leave the chosen incoming survivor buffering after transition cleanup; no comprehensive controller end-state test proves recovery. A pure Boolean watchdog test does not prove audible liveness.
12. **Musical planning remains unvalidated.** Phrase timing, vocal overlap, bass handover and restraint on unsuitable pairs need listening evidence across recordings, not just geometry/unit assertions. More effect templates or larger models are not established remedies for abrupt seams.

### Correct Media3 constants — do not repeat the retracted claim

Verified from the cached shipped `media3-common-1.11.0` `Player.class` using `javap -constants`:

```text
PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST = 1
PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS = 2
PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY = 3
PLAY_WHEN_READY_CHANGE_REASON_REMOTE = 4
PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM = 5
PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG = 6
```

The oracle initially inverted the user/end mapping, then retracted it. No proposed auto-resume-on-1 change landed. `PlaybackService` intentionally keeps a user pause sticky.

## 9. Source map at c2c7f32

Line numbers below are current snapshot pointers; prefer symbol searches after any edit.

| File | Relevant symbols / locations |
|---|---|
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shouldSkipStemAtLaunch` ~380; `driveFade` ~3135; `compilePhrase` ~4405; `commitPhrase` ~4438; `startLoopBuild` ~4651; `primeKey` ~4829; `shiftLoopOrFallback` ~4857; `driveLoopMix` ~5165; `finishLoop` ~5326; `loopBail` ~5446; survivor recovery ~5907; `finish` ~5971; `tailTarget=null` ~6143; nested `DeckTiming` ~7703 |
| `app/src/main/java/com/music/bitchord/playback/TransitionFilterProcessor.kt` | `releaseColour` ~243; `onFlush` ~335; `onReset` ~350; `GLIDE_RATE=0.05f` ~653 |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | User pause/focus reason handling ~955–992; filter wiring ~1985–2036; separation callbacks ~2095; adoptPlayer active/spare swap ~3586 (search symbol) |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | Stem-duck and pair acceptance ~317–462; stop geometry ~257/~539; reverb send ~677–691 |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixPort.kt` | Arm/disarm/generation and sink ownership |
| `app/src/main/java/com/music/bitchord/playback/audio/PrecisionAudioSink.kt` | Find actual path with `rg --files`; generateLoop, output handover and flush are relevant |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `musicalRescue`, `vocalClashEnergy`, `vocalClashBeats`, veto/score |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | Bridge/rung selection, shift, clock geometry, knot mapping |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | PhraseScript compilation, plan/effect semantics |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjColour.kt` | `process` ~134; `reverb` ~215; decay and effect switching |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt` | `complementAtModelRate` ~112; `completeDualStemEndpoint` ~167; single separation ~395; dual ~455 |
| `native/analyzer/resampler.cpp` | Floor sizing ~117/~207 |
| `native/loopmix/loop_mix_renderer.cpp` | `wetRetireStart` ~49; `wetRetireMul` ~101; `ReverbWash::apply` ~207; stopAt gate ~714; wet output summation ~995–1002 |
| `native/loopmix/loop_mix_renderer_ab_main.cpp` | `--selftest`, `--reverb-stop-probe`, preservation and mute sensitivity |
| `.github/workflows/android-ci.yml` | Two jobs, required new probe, HTML reports, optional signing, release continue-on-error |
| `tools/vc75-gate.sh`, `tools/audit-apk.sh`, `build.gradle.kts` | All currently expect vc78 / 2.2.3-djmix36 |

## 10. Build/signing operations and constraints

The accepted pipeline is **Actions unsigned build -> existing-key signing on bk-android -> local ADB install**. An unsigned CI artifact does not require rebuilding on the signing box. Earlier statements that lack of CI signing secrets forced the whole build onto the box were wrong.

Box identity: `bk-android`, ID `m_agq4fglq7npke2orqgt5j6leai`; guest user `user`; 4 vCPU / 8192 MiB RAM, persistent `/data` 20 GiB. Old build workspace `/data/workspace/vc75-build` is a pushed file tree, not a reliable checked-out git SHA. Do not treat it as source of truth.

Protected files under that workspace: `bitchord-loopfix.jks`, `keystore.properties`, `local.properties`. **Never print passwords or copy key material off the box.** Existing signing Python reads properties there and passes passwords to apksigner through environment-variable indirection, never command-line secret values.

Cloud signing roots:

- `/data/workspace/vc77-ci-43912914370/`
- `/data/workspace/vc78-ci-43955519403/`

Each has unsigned/signed APK, helper script and logs. `.idsig` sidecars were not pulled locally. Toolchain: Java 21 `/usr/lib/jvm/java-21-openjdk-amd64`, SDK `/opt/android-sdk`, build-tools `36.0.0`; workflow also pins NDK `27.2.12479018` and CMake `3.22.1`.

The free box paused mid-run with cooldown until **2026-11-16T19:05:38.973078Z**. The local date had already become Nov17 in UTC+7; that did not mean the UTC cooldown elapsed. A single scheduled resume succeeded after the deadline. Subsequent signing worked. Free-plan resizing was a no-op. Check actual current state/cooldown rather than assuming it is still awake.

Disk policy for any renewed build work: require adequate space (historically >=3.2 GiB for full build), never `gradlew clean`, never delete signing files or `npu-addon/prebuilt/`. Earlier agents deleted only verified regenerable `app/build/intermediates`, `app/build/tmp`, mapping/native-debug output to free space. Signing alone needs far less space and does not justify another full build cleanup.

### Commands for a future authorized verification run

These are reference commands, **not an instruction to resume now**. Run from the repository root.

```bash
git status --short
git log -4 --oneline
git diff --check
VC75_GATE_TMP=/tmp/opencode/vc78-gate bash tools/vc75-gate.sh .
g++ -std=c++17 -O2 -I native -I native/third_party native/loopmix/loop_mix_renderer.cpp native/loopmix/loop_mix_renderer_ab_main.cpp -o /tmp/opencode/loopmix_ab
/tmp/opencode/loopmix_ab --selftest
/tmp/opencode/loopmix_ab --reverb-stop-probe
```

Android suite commands, **cloud/CI only**:

```bash
./gradlew :shared:jvmTest :app:testProdDebugUnitTest --max-workers=2 --no-daemon --stacktrace
./gradlew :app:assembleProdRelease -Pbitchord.bundleWear=false --max-workers=2 --no-daemon --stacktrace
```

Read-only device checks after renewed authorization:

```bash
/home/user/.local/bin/adb devices -l
/home/user/.local/bin/adb mdns services
/home/user/.local/bin/adb -s adb-1439ebea-05jQT0._adb-tls-connect._tcp shell dumpsys package com.music.bitchord.loopfix
/home/user/.local/bin/adb -s adb-1439ebea-05jQT0._adb-tls-connect._tcp shell dumpsys media_session
```

The app supports transport media keys and certain deep links, but no supported shell hook was found to construct an exact two-track queue or seek directly to an exact timestamp. Opening a track link can replace the retained queue. Do not invent custom shell intents for Binder-only SessionCommands or private bounded-context tokens. Preserve user state; use UI or a justified test affordance if exact replay becomes necessary and authorized.

## 11. Reviewers, useful continuation sessions, and trust limits

User explicitly requested council, **Muse Ember `#max` as oracle**, and **DeepSeek Free via 100MillionSeek**. The exact resolved models were `meta/muse-ember-1.0#max` and `100millionseek/deepseek-v4.1-flash-free`. Look them up again if availability changes; do not silently substitute a different provider/model.

| Session | Work / result |
|---|---|
| `ses_e1af3cc2dffeXl7X0l6znPGWh1` | Git/CI commits and Actions artifacts through vc78; complete |
| `ses_e1a9d7264ffeB92hQ1kVPjTDxV` | Cloud signing vc77/vc78; complete |
| `ses_e1b3368c9ffeHuzuxbau1kAshV` | Earlier cloud test/build initial vc77; complete and superseded |
| `ses_e1ac9208dffe5TwjzCUR3Rgcwe` | Follow-up cloud test attempt failed compilation and box cooldown; superseded by clean Actions run |
| `ses_e1adf961fffebhz1kuSPwy1LmX` | VocalTracker floor/endpoint fix and tests; complete |
| `ses_e1adf9651ffe2z65uuHvpHI1AA` | Prime stem-retention fix; complete |
| `ses_e1adf9644ffeGiI4pJNiziVP8c` | Initial plan/script atomicity; complete |
| `ses_e1adf9629ffep5U58q6EvZtkMm` | Abnormal survivor-filter recovery; complete |
| `ses_e1acf5883ffe8Pgrxw0OoG3wKB` | Stale tailTarget clear; complete |
| `ses_e1acf5878ffej6wNbqmZfzKS13` | Buffering watchdog; complete |
| `ses_e1acf585dffeBnuPKnSCk3d0up` | PhraseCandidate/detail/log commit refactor; complete |
| `ses_e1acf5866ffeQ1WgUR70ZLT8DZ` | Generation-safe DeckTiming proposal; **no edits applied** |
| `ses_e1aed9abaffeB1ThyJjy61Dc69` | Initial Muse oracle audit |
| `ses_e1add3077ffe8D321ld4eBZNYz` | Muse full audit + targeted pause trace; corrected/retracted reason mapping; complete |
| `ses_e1ac87284ffe5M4VVHWCCcr6FW` | 100MillionSeek DeepSeek review; complete; its claim of no device evidence became stale |
| `ses_e1af250b9ffeEFfO2Y9GUzcI3M` | First usable GLM state-transition review |
| `ses_e1af2e51affexGqbU0oA5YbwHx` | Recon packet with source paths |
| `ses_e1a3efff5ffeLy2j3mjKpLhoQ8` | Device replay hook recon; includes overstatements about logcat availability |
| `ses_e19e19d02ffewG9e2rCLt5xRRt` | Production native reverb probe + wet-preservation test; complete |
| `ses_e19de7668ffeVvqf33f4Msjx04` | vc78 runtime/gate wet-retirement fix; complete |
| `ses_e19dbbc19ffev9ToUO1G6KYF1e` | Narrow native review; complete, conditional approval before final checks |
| `ses_e1ae5d66bffeHMp1KC7cAnuLgb` | Council synthesis; had incomplete attached evidence and no exact line table; not an independent proof |

Council availability was uneven: Kimi's preset model was missing on retries; Gemini hit daily quota; later GLM and DeepSeek preset seats hit free limits. Initial GLM, Muse, explorer and later requested 100MillionSeek review produced useful findings. **Do not describe this as an uncontested four-model consensus or treat repeated summaries as independent confirmations.**

Operational mistakes to avoid repeating:

- Multiple workers were initially assigned the same `CrossfadeController.kt` write scope concurrently. They were told to stop writes and preserve/report hunks; subsequent integration/tests passed, but that was poor coordination. New work must have one writer per shared file or isolated worktrees with explicit reconciliation.
- `git diff --check` was called "syntactically clean" in an earlier update. It only checks whitespace/diff errors; compilation is separate.
- A test-only duplicate local `bigInst` caused cloud test compilation failure; it was renamed `oversizedInstrumental` and then compiled/passed in Actions. That failure is resolved.
- The floor/round sizing bug was caught only after a strict guard had been written; the full double-floor roundtrip was then examined and endpoint completion added. Keep tests faithful to the native contract.
- Repeated reviewer labels of "confirmed" sometimes proved only the code path, not perceptual impact or causality. The final user feedback is stronger evidence about acceptance than those labels.
- Do not equate `clashE!=null` with stem inference success, a quiet wet stop with graceful whole-song handover, or a low phase scalar with no audible seek/repetition.

## 12. State at stop and recommended restart boundary

- No new build or install is running. All relevant implementation/build/signing/review lanes above reported completion.
- Scheduled cooldown resume completed earlier; no delayed install or wake task remains queued from that workflow.
- vc77 log capture ended by its 900-second timeout (124). vc78 capture was stopped at the user's explicit request (143); its ADB logcat child is no longer running.
- The phone retains vc78 and its data. No pause/resume/skip/seek command was sent during handover preparation. A final telemetry pull and session dump preserved evidence only.
- The signing box was left as it was; no destructive stop, reset, resize, or signing-file cleanup was performed for this handover.
- Code HEAD remains `c2c7f32`; only documentation is being changed for this handover. No commit/push of the final documentation is performed unless separately requested.

For the next session, after the user renews the task: read this file, read the stopped status in `REMAINING_WORK.md`, inspect actual git status, then review the saved vc78 final capture. Agree on a repeatable, timestamped failing transition and distinguish musical-plan choice, processing, and renderer-to-player handover. A waveform/audio capture spanning that exact seam would be stronger evidence than another layer of state-gate assertions. Existing native controls can isolate dry, reverb, and stem effects, but no end-to-end audible capture was obtained here.

Do not start with another broad council loop or a larger patch pile. The concrete unresolved requirement is the user's heard experience: **no sudden, startling change across the transition, while preserving both songs' musical intent.** The evidence so far supports narrower engineering improvements and decisively refutes the claim that the overall problem is fixed.
