# Remaining work

Current task list: **[Apple Music AutoMix parity checklist](AUTOMIX_PARITY_CHECKLIST.md)**.
Latest full changelog and next-session handover (2026-11-18): **[AUTOMIX_HANDOVER_2026-11-18.md](AUTOMIX_HANDOVER_2026-11-18.md)**.
Its completed/open marks supersede older feature-status tables below. Historical
records are retained for evidence; they are not the current implementation plan.

## Current build/signing host — cue

Use **[cue's build-host handover](CUE_ANDROID_BUILD_HOST.md)** for subsequent
authorized builds: root SSH `cue.example.ts.net`, repo
`/workspace/src/bitchord-mix`. Read-only checks confirmed 8 CPUs, approximately
23.64 GiB visible RAM, zero swap, approximately 51.45 GiB currently free disk,
clean branch `vc80-loop-source` at `2cb6e6f`, and working hostname SSH.

Keep **`-Xmx5g` / `--max-workers=4`**, respect earlyoom, and preserve the wrapper's
**unsigned build → apksigner CLI v2+v3** flow. Signing material is already in place
on cue; do not export it or revert to Gradle signing. Actions remains CI/fallback;
`bk-android` cooldown is no longer a prerequisite for future releases. Android
builds on this workstation remain prohibited.

The workstation's `adb-bridge-cue.service` is active and cue's loopback ADB listener
was verified. The USB phone is currently disconnected. No KVM/Docker means no
emulator/instrumented tests on cue. Existing unit XML reports independently match
vc80 CI counts; the existing CLI-signed `/tmp/signed.apk` verifies with matching
certificate and v2/v3. No tests/build were rerun to record this handover.

Operational corrections: the wrapper's signed output path is currently absent,
so `audit/install` are not ready merely because `/tmp/signed.apk` exists. Wrapper
`native` also omits window calibration and sanitizers. Its unsigned-build switch
temporarily moves `keystore.properties` to `/tmp/kp.hold`, with an EXIT restore
trap; check recovery state after SIGKILL/reset and do not run concurrent builds.
These limitations are recorded in the cue handover; the wrapper was not changed.
vc81 / 2.2.3-djmix39 was delivered through lossless.example.com and the user now approves
its stability improvement. Preserve source `e7c8a3e` as the accepted baseline.
Musical overlap and transition quality are the next open priority. The last ADB
package check was vc80; vc81 feedback comes from the user's own testing.

## Latest result — vc81 stability approved

After receiving the hosted vc81 APK and its expected-behavior explanation, the
user reports: **“Okay I approve it's way stabler.”** This accepts the stability
improvement. It does not accept the mix's musical quality, establish the exact
exception from the old phone capture, or supply a measured sustained-session
transition count. No new build or audio-policy change is needed to record this
acceptance. Held DECLINE/EOF candidates remain excluded. Next work is the reported
early-fade/sequential-handoff quality, preserving the accepted stability fixes.

The user reports that force-closing and reopening restores a mix, then Automix
stops again after one song. Capture `../automix-stutter-2026-11-17/one-song-stall-085702/`
records PID30702 completing a native loop at1794905555344, then an IDLE seek at
1794905576567 and no further plan by1794905822018. Playback continued on FAITH —
ATEEZ, queue index1/25, with next analysis shown ready. No playback controls were
sent. The phone disconnected after this capture.

The source contains an unguarded ticker coroutine; a recoverable exception ends
that child while the service's SupervisorJob and player survive. Its error
handler uses Log.e, which release R8 rules remove. On cue, the injected-error
regression fails on vc80 and all23 focused candidate tests pass. Ordinary classic
handoff/seek controls pass on both sources, so handoff alone is not a reproduced
cause. First candidate26ee0c2 passes fullapp1406/shared497/native/signing gates.
It has not been installed.

A concrete reachable planner exception was then found: SongSections accepts a
lyric starting within300ms of contentEnd, then coerceIn receives a lower bound
past that end and throws. DjMix.annotate calls it on the real planning path.
The ticker guard alone cannot make progress if that input persists. The final
build includes the narrow boundary fix and real-planner regression.
The captured phone exception remains unknown; do not call the candidate input
defect the proven device cause until runtime evidence identifies it.

Planning work is isolated on `vc81-automix-replan` at
`.slim/worktrees/automix-replan`, baseline2cb6e6f, with candidate stamp81/djmix39.
The original `vc80-filter-catchup` checkout preserves both held policy candidates:
DECLINE paired faders and an unfinished near-EOF catch-up change. They are not
part of this repair, have not been executed, and are not installed. Verification
owners and acceptance limits are recorded in
`../automix-stutter-2026-11-17/AUTOMIX_REPLAN_VERIFICATION.md`.

**Delivered build; stability user-approved:** source
`e7c8a3e2df09b9a3f801f3d6dde856a607711d60`. The old lyric code fails two of three
new tests with the exact empty-range exception, including the production
planTransition→DjMix→labelSections stack; all3 pass after the fix. Final cue
tests pass shared500/app1406, zero failures/errors, one existing app skip. Native
gate, unsigned-build/CLI v2+v3 signing, trusted certificate, APK audit and16KiB
alignment all pass. Signed APK67552565 bytes, SHA256
`00277861640fec25b374f9bfb145352f0d5936252a8e6e6e1d9aa688317b5ca6`.
The APK and downloaded/parsed reports are under
`../automix-stutter-2026-11-17/vc81-validation/final/`; local digest matches cue.
Published download: https://lossless.example.com/downloads/bitchord-vc81-djmix39.apk.
The full public download matches the signed hash. No agent installation or
playback controls were sent; the user installed/tested it independently and
approves greater stability. Independent successive-transition telemetry and
musical listening acceptance remain open. No build job running.

## Council-selected overlap evaluation — completed, not promoted

All four requested council seats reviewed the quality complaint. The selected
bounded pass evaluates only classic DECLINE faders from accepted vc81. Candidate
`2fb6b1f` on `vc81-overlap-eval` is isolated at
`.slim/worktrees/automix-overlap-eval`; no controller/native/filter/timing/gate or
version change. The old unfinished EOF candidate remains excluded, including a
source-reviewed conditional watchdog risk and invalid reset-between-ticks test.

Cue focused checks pass15 app +21 shared, zero failures/errors/skips; baseline4
and repeat4 render checks pass. Real controller handoff/retirement/B→C planning
and production filter PCM are exercised. The fader change provides1.34s with
both gain commands>=0.5 versus0s, and central summed RMS increases3.13dB on
multitone /2.85dB on synthetic120/87 percussion. Accepted/candidate geometry and
filter trajectories match; all baseline repeats and the compatible control are
bit-identical. No dry song sources/current relevant failure were available;
this does not prove recognizable musical overlap or acceptable vocal/rhythm
clash. Outcome: mechanism supported, musical quality inconclusive, no promotion.
No new APK/install/playback control. Accepted vc81 is unchanged. Full evidence:
`../automix-stutter-2026-11-17/overlap-eval/README.md` and council record beside it.

## Next delivery — vc82 overlap listening candidate

Following the completed offline pass, the user instructed **“then go”**. Build
vc82 /2.2.3-djmix40 from `60b9734711b3a0e3214a31f76eac7766c61ae873`, branch
`vc82-overlap-listen` in the overlap worktree. The isolated accepted vc81 branch
and its hosted APK remain unchanged. Runtime scope is only the classic DECLINE
paired faders; old EOF/catch-up, native envelopes, filter curves, cue placement,
span, rates and gates are unchanged.

Final cue full checks pass shared500/app1410, zero failures/errors, one existing
app skip. Native gate, CLI signing, trusted v2+v3 certificate, payload audit and
16KiB alignment pass; signing properties recovered and source is clean. APK
67552565 bytes, SHA256
`a07617094ba02cd3fbd5d393468a52d35d226a96c2a1e1d11c9f98e64f9d3bc9`.
Retrieved and publicly hosted at
https://lossless.example.com/downloads/bitchord-vc82-djmix40.apk; the full HTTPS download
matches hash/size and the vc81 hosted artifact is unchanged. No agent
install/playback control sent. This is a listening candidate, not accepted mix
quality. Evidence:
`../automix-stutter-2026-11-17/VC82_LISTENING_CANDIDATE.md`.

**Latest device state:** wireless ADB verified1439ebea. Initial package was81;
`adb install -r` then succeeded and package manager confirms82/djmix40. One app
launch restored Empty Box — ATEEZ with a Play control; no playback or queue
controls were sent and no capture started. The user is now gaming and explicitly
requests no interruption. All phone interaction is paused until they are ready.
The earlier “even better” feedback preceded verifiedvc82 installation; keep vc82
listening acceptance open. Evidence: sibling `device-vc82-wifi-142720/`.

Subsequent check (`device-vc82-live-150709/`) confirmed `versionCode=82` /
`2.2.3-djmix40` with no active media session (`pidof` exited 1); ADB later
disconnected and `cue` SSH currently requires a Tailscale browser auth check.

## Authorized offline work while gaming

The user requested useful work with a four-seat council while phone interaction
remains paused. Seats split2–2 between executed-plan fire attribution and a
test-only characterization of delayed incoming readiness. Synthesis selects the
test-only pass first; additive telemetry and audio-policy changes are deferred.

Branch `offline-arming-readiness` at `.slim/worktrees/automix-arming-diagnostic`
starts from installed60b9734. Only the existing app test fixture may change.
Compare immediate readiness versus B buffering two media seconds into the
192–196s classic DECLINE plan. Observe solo-A filtering, possible reopening
after startFade re-anchors, production-filter synthetic PCM, one genuine handoff,
spare-only retirement and continued B→C planning. No controller/lifecycle field
reset, no deadline/grace claim from the JVM clock stub, no release build or
phone operation.

**Completed (`offline-arming-readiness`):** cue focused run passed **12 tests / 0
failures / 0 errors / 0 skipped** (`DeclineOverlapRenderTest` 5,
`AutomixTickerRecoveryTest` 5, `AutomixReplanLifecycleTest` 2) with zero
production diff. Confirmed `driveArming()` commands ~800 Hz outgoing LP while A
plays alone at volume `1.0` and B stays `STATE_BUFFERING` (`-82.25 dBFS` vs
`-21.43 dBFS` solo reference on the synthetic 5 kHz tone), then `startFade()`
re-anchors and reopens to `7000 Hz` on the first `FADING` tick. See
`../automix-stutter-2026-11-17/ARMING_READINESS_CHARACTERIZATION.md` and
[AUTOMIX_HANDOVER_2026-11-18.md](AUTOMIX_HANDOVER_2026-11-18.md).

## Resumed 2026-11-17 — focused seam investigation

**Latest user result on vc80:** “okay that dropout is fixed!!!! but there are
still stutter everywhere. note this alongside the next big features”

The Touch the Sky → Higher Love mid-mix dropout is **user-confirmed fixed**.
Widespread stutter is the highest-priority remaining audible defect; overall
smoothness and Apple AutoMix parity are not accepted. Preserve the dropout fix
while isolating stutter across the complete mix and continuing playback.
The major-feature roadmap is now listed beside this blocker in
[AUTOMIX_PARITY_CHECKLIST.md](AUTOMIX_PARITY_CHECKLIST.md): trustworthy musical
timing/structure, clean stem/bass handoffs, appropriate loops/effects/tempo
control, measured NPU acceleration, then controlled parity comparisons.

Current candidate: **vc80 / 2.2.3-djmix38**, commit `2cb6e6f` on branch
`vc80-loop-source`. [Actions run 44016339991](https://github.com/survey-sudo/bitchord-mix/actions/runs/44016339991)
passed both jobs and every required build/test step. The finite-loop correction is `c1091fc`; the
reviewed generated-buffer timestamp follow-up is `2cb6e6f`. Full native gate
passed, including the new 63-pair source-domain probe. Downloaded HTML totals:
**shared 497 / 0 failures / 0 ignored; app 1,399 / 0 failures / 1 ignored**.
All 28 focused regressions passed, including the 11 new vc80 tests. vc80 is
installed on `1439ebea` over USB. User listening accepts the dropout repair;
stutter remains unresolved.

Unsigned artifact: **67,417,626 bytes**, SHA-256
`98b0869ee46735b5a07e098be5433c03599acc3249bb04d8693b416d589e65d5`,
retained under `../automix-seam-2026-11-17/ci/44016339991/` relative to repo root.

Signing box cooldown currently ends **2026-11-17T05:18:58.951754Z**
(12:18:59 UTC+7). The existing key remains there; no key export is permitted.
The scheduled 12:19 wake/sign attempt failed before staging because instabox's
authentication refresh hit a TLS handshake timeout. At 12:23 the cooldown had
elapsed; a fresh status request succeeded and a direct resume/sign retry was
launched. That retry succeeded: vc80 signing, matching certificate verification,
APK audit and 16 KiB alignment passed. Signed APK is **67,552,565 bytes**, SHA-256
`47163057210e0adebeb8fbb46ce69664576f2ed81b974d586040c1da328020e4`.
USB `1439ebea` reconnected. Local signature/certificate/version/alignment checks
passed and all 1,592 unsigned CI payload entries matched the signed APK.
`adb install -r` returned Success; package manager confirms version80/djmix38.
Post-launch PID26897 showed no fatal/ANR in the saved snapshot. Restored media
state was NONE, speed0, Angel Bite at49129ms,12queue items; no play command sent.
The original queue contents were not enumerated, so full queue identity is not
claimed. The six-minute USB capture completed with capture/pull exit0, but no
transition events fell in its window; both media snapshots stayed idle at the
same Angel Bite position. It therefore does not independently verify the user's
subsequent dropout/stutter feedback. Artifacts `device-vc80-install/` and
`device-vc80-capture1/` under the sibling evidence directory. No recording or
playback-control job is running.
The native reproduction established premature EOF for a finite incoming loop
despite valid mapped samples, plus an unwrapped-loop/physical-song handover
coordinate mismatch. The shared/native/session correction and mandatory native
regression probe are committed and installed as vc80. Micro-stutters remain a
separate open item; the sink timestamp
fix addresses a confirmed consecutive-mix bug, not proven micro-stutter causality.

**vc79 failed listening:** two recorded LOOP repeats of Touch the Sky → Higher
Love completed without a corrective seek or abnormal cleanup, but the user
reported **“Still abrupt.”** The clock correction is not the complete audible
fix. Investigate the captured PCM and repeated incoming renderer endpoint
10835ms versus handover anchor22975ms before adding another patch. Artifacts:
`../automix-seam-2026-11-17/device-vc79-capture1/` relative to repo root.

The user clarified Higher Love cuts off midmix and both mixes micro-stutter,
then supplied a 17.74s video. Offline analysis confirms matching 3.75–3.76s
silence in the video and both USB repeats, plus shorter 64–85ms USB gaps. The
short gaps do not recur at the same content positions, so capture versus playback
remains unresolved; they are not automatically the reported micro-stutters.
Native reproduction confirms the coordinate/EOF defects under controlled
geometry. `heard=10835ms` is not established as a frozen endpoint in these runs.

Reviewed candidate: **vc79 / 2.2.3-djmix37**, commit `f71f9b8` on
`vc79-handover-clock`. [Actions run 43985488318](https://github.com/survey-sudo/bitchord-mix/actions/runs/43985488318)
passed both jobs and their actual build/test steps. It is installed on `1439ebea`
over USB; its captured loop transitions failed listening acceptance as recorded above.
The loop cleanup now compares current, generation-valid presentation clocks at
one monotonic instant instead of seeking back to a frozen native `heard(IN)`
endpoint. Genuine drift and unstarted incoming decoders have explicit recovery;
pause/seek intent and spare-only retirement remain protected. New controller,
port and sink tests cover the actual cleanup and publication races. CLASSIC
fades, planning, effects, stems and QNN selection are unchanged.

The required local native gate passed (exit 0): success-path 16, calibration 11,
renderer selftest 14 and production reverb probe. Kotlin/Android validation
passed in Actions: downloaded HTML reports show shared **495 / 0 failures / 0
ignored**, app **1,390 / 0 failures / 1 ignored**; all **17 new regressions** passed
without skips. Unsigned APK SHA-256
`a55af6a424df33b9da355ec8e3447f114ca8796198e926f48f074b36ee8cab67`,
67,416,714 bytes. These checks do not establish audible acceptance.

Signed on `bk-android` with the existing certificate; v2/v3 verification, APK
audit and 16 KiB alignment passed. Signed APK: **67,552,565 bytes**, SHA-256
`21cb9f820dc02bd1bcd8455eedb364bc5c0b0963592ac9561a639f117e9abe10`.
Artifact and logs are under
`../automix-seam-2026-11-17/ci/43985488318/signed/` relative to the repository root.
Wi-Fi ADB dropped, then the user connected USB. `adb install -r` succeeded and
package manager confirms **79 / 2.2.3-djmix37**. The pre-install track was paused;
later playback and a changed queue were observed without an agent play command.
Queue/pause restoration is therefore not yet established in an isolated interval.
The USB capture completed and contains the two rejected loop transitions above.
Launch/capture snapshots showed no fatal exception/ANR for the app PID; this does
not establish uninterrupted audio. No capture or playback-control job is running.

The user renewed work with “Ok then go” after the council research in
[AUTOMIX_RESEARCH_2026-11-17.md](AUTOMIX_RESEARCH_2026-11-17.md). The first scope is
reproducing the actual handover failure, separating transport from musical-plan
quality, and repairing demonstrated defects. No new candidate is accepted yet.
The historical stop and failed vc78 listening evidence below remain authoritative
about that build's quality; the new instruction authorizes resumed work.

During the unchanged-vc78 baseline, the user reported Far → BIRDS OF A FEATHER
was smoother. Fresh telemetry shows recent replays used classic fallback after
loop preparation failed, whereas the historical failure used the loop renderer.
No code change or install caused this improvement. The first audio capture missed
the seam and lost Wi-Fi ADB after 15 seconds; positive listening feedback and the
path difference are recorded separately from the reproduced loop-clock defect.

## Historical stop — vc78 failed the user's listening check

**2026-11-17 local / 2026-11-16 UTC: stop all implementation and playback testing
at the user's request.** `vc78` remains installed. The user tried three tracks
and reported sudden, startling changes, not smooth transitions. Passing CI and
the native reverb probe did not resolve the end-to-end complaint. Do not ship,
claim Apple Music parity, or treat the reverb cutoff as the sole root cause.

Full restart context: [AUTOMIX_HANDOVER_2026-11-17.md](AUTOMIX_HANDOVER_2026-11-17.md).
The vc78 log capture was explicitly stopped (exit 143); final telemetry was
saved to `~/Bitchord/work/vc78-ci/43955519403/device-after/telemetry-at-stop.jsonl`.
No further source changes, builds, installs, or playback controls are authorized
by the stop request. Resume substantive work only when the user starts it again.

## vc78 candidate — reproduced wet-stop defect

Installed candidate: **78 / 2.2.3-djmix36**, based on `vc77`.
**Both vc77 and vc78 failed listening.** The measurements below establish the
narrow native reverb repair, not graceful transitions on the phone.

- Added native `--reverb-stop-probe` using the production send/release/stop
  geometry and deterministic broadband transients, with no artificial decay gap.
  Unmodified `vc77` fails: standard wash late-50 ms RMS **−49.174 dBFS**, only
  **26.239 dB** below the active window, boundary step **0.003066**. The dry-gain
  exit gate still reports zero violations. This demonstrates a wet cutoff, but
  does not establish that every reported abrupt transition has this cause.
- Candidate retires actual reverb output with a smooth envelope ending at
  `stopAt`; zero send stops new input rather than instantly zeroing stored wet
  output. Original probe passes: standard late RMS **−99.897 dBFS**, drop
  **76.962 dB**, step below threshold. Removing retirement makes the probe fail.
- Wet-only preservation passes: early post-release RMS **−28.985 / −29.844 dBFS**,
  within 0.5 dB of the old renderer. Deliberately muting reverb fails preservation;
  removing retirement restores the boundary failure. Scheduled echo behavior
  remains unchanged. Focused review found no remaining blocker for the reproduced
  reverb geometry; full native gate passed after reconciliation (exit 0).
  Production probe is required by both Actions jobs and `tools/vc75-gate.sh`.
  [Actions run 43955519403](https://github.com/survey-sudo/bitchord-mix/actions/runs/43955519403)
  passed both jobs at `c2c7f32180b3e9c28922b384fc6c20a34135171f`, including the
  production probe; shared **495 tests / 0 failures**, app **1,373 / 0 failures /
  1 ignored**. Downloaded HTML totals and artifact hash independently checked.
- Signed on `bk-android`; certificate matched existing
  `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`.
  APK audit and 16 KiB alignment passed. Signed size **67,548,469 B**, SHA-256
  `3fdb79a6a97a1e1cd302c1b42e302d3c26151608c096eff59be993f60bfeb6e9`.
  Artifact/reports remain outside git in `~/Bitchord/work/vc78-ci/43955519403/`.
- Wi-Fi ADB `install -r` succeeded on `1439ebea`; package manager confirms
  **78 / 2.2.3-djmix36**, launched with PID 30553. Pre-update telemetry saved.
  Log capture stopped early at user request. Final snapshot has 51 new events:
  two loop fires, one normal verified loop completion (including a −186 ms
  corrective handover seek), and one user-pause-triggered tail completion.
  The user reports abrupt, startling transitions across three tracks. Exact
  association between each heard defect and these events has not been established.
- Pause-reason hypothesis withdrawn: shipped Media3 1.11.0 bytecode confirms
  `USER_REQUEST=1`, `END_OF_MEDIA_ITEM=5`. The replay pause is not evidence of
  an internal end-of-item pause. No auto-resume or user-pause behavior change
  is planned for this candidate.

---

Historical vc77 snapshot, 2026-11-17 (local date). **vc77 / 2.2.3-djmix35
was signed and installed, failed listening, and was replaced by vc78.**
Earlier records below are historical where they conflict with the stop status.

- Source: branch `vc77-ci`, commit `39290def8024a66840d1caa993a83f4d1c440f97`.
- [Actions run 43912914370](https://github.com/survey-sudo/bitchord-mix/actions/runs/43912914370)
  passed both jobs, including the actual release assemble/audit/upload steps
  (the job's `continue-on-error` did not mask a failure).
- Downloaded HTML reports independently checked: shared **495 tests, 0 failures,
  0 ignored**; app **1,373 tests, 0 failures, 1 ignored** (live Genius test).
  No XML was uploaded by this workflow.
- Latest unsigned APK SHA-256:
  `2cc665fac3fc6b4161eec897c02e703fcb7fd03d1becd9078382ac871d57e66f`.
  Artifact and reports are outside the repo under
  `~/Bitchord/work/vc77-followup-ci/43912914370/`.
- Follow-ups include prime stem retention, accepted-only phrase script/detail
  commits, abnormal-exit survivor filter recovery, stale-tail cleanup, a
  buffering watchdog, and native-floor stem sizing with bounded endpoint
  completion. These passed CI; audible behavior remains unverified.
- Remaining review items: active-sweep flush continuity, shift retry lead,
  per-build timing isolation, inference deadline after a model-lock wait, and
  a production-envelope reverb-tail test. The timing fix is a proposal only.
- Signed on `bk-android` after its cooldown, without uploading the key to GitHub.
  Certificate SHA-256 matches
  `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`;
  v2/v3 signature verification, 16 KiB alignment check, and APK audit passed.
  Signed APK: **67,548,469 B**, SHA-256
  `485dd2aa978902d6a6887b958f34c6a5524cd78903c6458f9221d239d6752909`,
  retained under the artifact directory's `signed/` folder.
- Wi-Fi ADB update on `1439ebea`: `adb install -r` succeeded; package manager
  confirms **77 / 2.2.3-djmix35**; launched successfully (PID 17782 at check).
  Existing app data/23-item queue retained and pre-update telemetry saved outside
  the repo. First 15-minute capture completed: **2 loop transitions**, both
  `mix.verify.ok=true` and `exitGateViolations=0`; handover heard minus expected
  offsets **+58 ms / +60 ms**. One `stem-duck` decision logged measured
  `clashE=0.14529196781328904` (0 null-clash decisions in this small sample).
  This proves a measured duck decision, not successful ONNX separation or
  perceptual stem quality. No `mix.arm.death`, `shift-bar-null`, or fatal exception
  was observed. Neither targeted reverb-tail coverage nor a rare-failure rate
  is established by two transitions. Playback was paused at the final check;
  it was left paused. Logs/fresh telemetry are under the artifact's `device-after/`.
  `vc76` reproduced the demuffle/reverb zap and null stem-clash metric;
  **Listening result: user reports the zap is still present, abrupt and not
  graceful. The vc77 audible-fix claim is refuted; release remains blocked.**
  A subsequent manual replay logged a user-request pause during a live loop,
  then `mix.exit.guard` / 350 ms emergency tail at full retiree gain. Its
  causal relationship to the heard zap is not established. A focused lifecycle
  trace and production-envelope native probe are in progress. Blind A/B and
  targeted seek/reverb/abnormal-exit checks remain open; Apple Music parity is unproven.

Historical baseline: `vc76` / `2.2.3-djmix34`, signed APK `67,433,006 B`,
SHA-256 `02920d8278003c5fe9b492a3f0a0dc052ddb4b48a93d8e8ff2e2b84cb1e0ca79`,
signed with cert SHA-256
`20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` on
`bk-android` (`app-prod-arm64-v8a-release.apk`), pulled to
`~/Bitchord/work/vc76-release/`. Prior `vc75` (`78d006d7...`, `b7375e8`)
remains superseded before device verification.

The earlier pre-install baseline below is retained for reference; the latest
device findings and pending candidate are recorded above.

Test & gate record on `bk-android` at `vc76`: `:shared:jvmTest` **479/479**
pass; `:app:testProdDebugUnitTest` **1358/1359** pass (1 skipped), with only
unrelated env-dependent `QueueShuffleTierTest` `< 50ms` timing benchmark
failing under contention and passing **27/27** (`shuffle: 2.42ms`,
`restore: 3.55ms`) isolated; `./tools/vc75-gate.sh` fixed (`fail` check moved
after calibration) and passing (16 native + 11 calibration invariants).

---

## Historical automix overhaul (council-built, 2026-11-16)

The D1–D6 states below describe that earlier snapshot. The current checklist
separates implemented features from on-device and listening acceptance. In
particular, D6's proposed pair count/preference threshold is not a validated
statistical parity criterion, and current device connectivity differs.

Two council rounds (3 of 4 seats, then 4 of 4). Verdict: stems are the unfair
advantage and were unused by default; the clash measurement now decides
ranking, veto, and the render duck. (`StemClash.rank` remains pinned-only /
unused in production.)

| # | Deliverable | Status |
|---|---|---|
| D1 | Energy-weighted clash core (`f59044a` + Pass-2 `SongMapEnrichment.enrich` + `SongMap` equals/hashCode + `CrossfadeController.memoKey` + `PhrasePlanner.vocalClashEnergy` `\|\|` unmeasured guard): fixed Pass-1 `PhraseMaps` bypass where `StructureAnalyzer.fromPcm(..., mask=null)` left `vocalEnergy` empty and `sungBars` all-false in production; `SongMap.vocalEnergy`, `PhrasePlanner.vocalClashEnergy`, veto on measured `E > 0.20`, weights `0.18/0.30/0.12/0.14/0.08/0.08/0.10`, cut as `0.20×discardedVocalEnergy + 0.30×clashE`, bonuses capped `0.08` | **Landed, unit-tested (479/479 `:shared:jvmTest`), awaits on-device logs.** |
| D2 | Stem-duck default-on (`d87faae` + `LoopMixDuck.decide` + `StemClash.duckGainFor` floor wiring): trigger `E > 0.12`, asymmetric, floor `0.15f`, boolean fallback when unmeasured, `PhraseChoice.vocalClashEnergy` plumbed; extracted pure `LoopMixDuck.decide()` and wired `duckGainFor` (floor `0.15f`) into outgoing and incoming stem duck; pinned with `LoopMixDuckTest` and off-device `native/loopmix/loop_mix_renderer_ab_main.cpp --selftest` (`duck-off -10.434 dB`, `duck-gain(0.18) -12.555 dB`, `fixed-0.15 / duck-gain(0.60) -12.749 dB`, `exitGateViolations=0`) | **Landed & verified off-device, awaits on-device listen.** |
| D4 | `BeatFlow.rankScore` coverage-lerped `w = 0.18 + 0.27×coverage` (`b6d1d95`), penalty `0.7/0.3/0.2` | **Landed, unit-tested (479/479), awaits on-device listen.** |
| D5 | `MixWeightTuner` deleted (`d87faae`) + its 3 `Djmix25` tests | **Landed** |
| D3 | Seam-local tempo/key/energy; `1.09→1.06` cap | **Deferred**: needs per-bar key/BPM fields `SongMap` doesn't carry; cap ships only bundled with it (council, 3/3) |
| D5b | `MixTemplate` 16→4 | **Deferred** until D6 shows which rungs win (council, 3/3) |
| D6 | Blind A/B gate: 20 pairs, stem-on vs stem-off vs Apple, need ≥60% on clash pairs | **Blocked** on phone (`adb` disconnected) + Apple Automix WAV captures; off-device WAV A/B harness (`native/loopmix/loop_mix_renderer_ab_main.cpp`) is ready for stem-on vs stem-off renders |

Not raised, on purpose: the detector threshold stays at `0.25`. It is a
detector on the analyzer's calibrated scale (chorus `0.57–0.71`, soft verse
`0.2–0.4`), not the separator's gain; `0.50` would sit inside the chorus band
and blind the planner to the soft verse the whole overhaul exists to catch.
Pinned in `StemClashTest.detectorThresholdMustStayOnTheDetectorScale`.

Build rule: Gradle runs on GitHub Actions or `bk-android`, never on this PC.
The current path builds unsigned on Actions and signs with the existing key
on `bk-android`; APKs and keystores stay outside the repository.

---

## Measured: the stem gain curve (calibration harness, 2026-11-16)

`native/analyzer/window_calibration.cpp` reads the Wiener transfer function out
of the shipped code rather than out of the comment, and pins it:

| mask value `r` | 0.10 | 0.25 | 0.35 | 0.50 | 0.62 | 0.80 | 1.00 |
|---|---|---|---|---|---|---|---|
| energy kept | 1.2% | 10.0% | 22.5% | 50.0% | 72.7% | 94.1% | 100% |

The curve is `r²/(r²+(1−r)²)`, so **every decision threshold in the tree sits
below 50%**: `VOCAL_ACTIVE_THRESHOLD = 0.25` keeps 10% of energy,
`VocalDepthFilter.VOICE_FLOOR = 0.35` keeps 22.5%, `vocalProbability ≥ 0.62`
keeps 72.7%. A threshold is not a gain, and none of the three has been checked
against a listening test.

Also pinned, and previously only asserted in a comment:

- `vocals + instrumental` reconstructs dry to `2.5e-09` RMS at dry RMS `0.255`
  — the complement identity the single-pass `dry − vocals` design rests on.
- `(dry − vocals)` matches the complement-mask stem to `2.9e-09`.
- Best alignment lag is **0 samples** across a ±64 search, so the local
  subtraction cannot comb-filter.
- Boolean frame-count clash reports 1.000 for both a quiet verse and a loud
  chorus; the energy-weighted mean reports 0.090 and 0.810.

---

## Historical P0 — earlier vc74 listening gate

Superseded as a current checklist by [AUTOMIX_PARITY_CHECKLIST.md](AUTOMIX_PARITY_CHECKLIST.md).
Later loop/classic runs and positive Far→Birds feedback are recorded above.

**Nothing about audio quality is proven.** The `vc74` analysis fixes are measured
(`mel 85 ms`, `infer 1,887 ms`, `vocal mask` present, `structure exceeded` 0), but the
mix behaviour is not.

| Item | State |
|---|---|
| Phrase exits not cutting Song A early | **UNVERIFIED** — no phrase plan ran |
| Song B entering past silence | **UNVERIFIED** |
| Two-deck beatmatch | **UNVERIFIED** |
| No muffle / demuffle on vocals | **UNVERIFIED** |
| Clean ≤200 Hz bass handover | **UNVERIFIED** |

Why no phrase plan ran on the last session: the only analysed pair was
`134.83 → 148.61 BPM` = ratio `1.1022`, above `AUDIBLE_MAX_RATE = 1.09`, so it was
correctly declined to `DJ_FILTER`. The next pair had `qualityB: none`.

Listening gate:

- `Beautiful` → `Never Let Go`
- `Drip` → `UP`
- `This Is What You Came For` → `One Kiss`
- `Technologic` → `Guess`

Per pair: (a) Song A reaches near its real end, (b) Song B enters on music,
(c) both decks beatmatch, (d) no underwater muffle on vocals, clean low-end handover.

---

## Historical P1 — infrastructure

The current source of truth is the checked-out git commit and its Actions run.
`bk-android` is the existing-key signing host; the old sync/migration instructions
below are historical and are not prerequisites for the current candidate.

### Sync `bk-android` with origin

`bk-android` is the build source of truth and currently sits at `4e19a2a`, while
origin is at `8ba485d` (CI + grok-box kit). After the cooldown lifts:

```bash
cd /data/workspace/bitchord-task2/work/BitChord
git pull --ff-only origin master
git log --oneline -3
```

Only `chop.py` is untracked, so this fast-forwards cleanly.

### grok-box migration

Blocked: `grok-box` is `offline, last seen 40m ago` on the tailnet
(`tx 780 rx 0`). Kit is ready in `docs/tools/grokbox/`. When it answers:

1. `bootstrap.sh --check` and compare against the measured table.
2. `migrate-repo.md` — ~165 M transfer, excludes rebuildable output.
3. `verify-build.md` — rebuild the same commit and compare the APK.

Migration counts as done only when the same source produces the same bytes.

### CI

`.github/workflows/android-ci.yml` replaces upstream's broken `android.yml`
(wrong branch, no `bundleWear` flag, no pinned NDK). Two parallel jobs: unit tests
and the arm64 release APK.

Signing is optional — with no secrets it emits `app-release-unsigned.apk`. To get a
signed, installable APK, add four repository secrets:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

**Current decision:** use unsigned Actions output and sign on `bk-android`;
no signing secrets need to be uploaded to GitHub. The latest run is recorded
in the status snapshot above; older timing records below are historical.

Latest CI record: run `43847603785` at `0157ee5` (`Unit tests` passed in `8m 24s`; `Release APK (arm64)` failed at `51m 1s` when the hosted runner lost communication). `.github/workflows/android-ci.yml` has since been hardened (`-Xmx3g`, `--max-workers=2 --no-daemon`, pre-build `df -h`, failure diagnostics upload, and `continue-on-error: true` on `release` until two consecutive green runs).

Historical `vc74` CI baseline (`5fbe776`, run `43767187882`, 2026-11-15): `Unit tests` finished in `8m 48s` (`528 s`), `Release APK (arm64)` finished in `17m 41s` (`1,061 s`), and the parallel run concluded in `17m 45s` (`1,065 s`). `assembleProdRelease` alone took `14m 21s` (`861 s`).

- **Payload parity (`docs/ci-parity-vc74.md`):** **1,584 of 1,592 entries** are
  SHA-256 identical to the verified build-box `vc74` APK, including all three
  `classes*.dex` files (`classes.dex`, `classes2.dex`, `classes3.dex`). The 8
  differences are `META-INF/version-control-info.textproto` (records the git
  commit: `dc78e0d` vs `5fbe776`) and 20 bytes inside `.note.gnu.build-id` in each
  of the seven `libbitchord_*.so` — a host-path hash from `lld` that survives into
  the build-id but does not touch `.text`, `.rodata`, `.data`, or symbols.
- **Native audit of the CI artifact:** exactly one `libonnxruntime.so` at
  `34,208,672 B` (SHA-256 identical to the box build), zero QNN/HTP libraries, all
  seven `libbitchord_*` present, version `74` / `2.2.3-djmix32`.
- **SDK caching overhead:** `Install SDK packages` took only `40 s` (release) /
  `44 s` (unit tests), while Gradle cache restore is already `26 s` / `34 s` and
  `Free disk space` costs `63 s` / `73 s`. Caching the Android SDK/NDK is not
  worth it; the compile (`14m 21s`) is the cost.
- **Signing status (verified fact):** the CI artifact (`67,411,246 B`, SHA-256
  `00c877c55162fa2202a8b00aab2ae38afc58611ccbd5f8ff6e9cd9f34aa022db`) is
  **unsigned** (zero `META-INF/*.RSA`/`*.SF` entries and no `APK Sig Block 42`), so
  it cannot be installed on the phone, and a differently-signed APK cannot replace
  the installed app (`adb install -r` fails on signature mismatch; uninstalling is
  off the table). Adding the four repository signing secrets makes CI emit an
  identical APK signed with the same keystore so it installs over the current one
  with `-r`.

### Known debt: `compileSdk` 36 vs InnerTubeX's declared 37

`innertubex-android` declares `minCompileSdk = 37`, so `app`, `shared` and
`sharedUi` all disable their `*AarMetadata` tasks. This project compiles against
36: AGP 8.13.1's maximum recommended value, and the target the release was built
and verified against. InnerTubeX's bytecode uses no API-37 symbols, so the app
runs, but the check is a real safety net that is off.

**The platform is no longer the blocker.** `bk-android` now has `android-37` and
`android-37.0` installed, and CI can install `platforms;android-37`. The proper
fix is therefore available: bump `compileSdk` to 37 in all three modules, delete
the disables, and re-verify on device. It is deferred only because it changes the
build target, and the current APK is the one awaiting a listening test.

Because the checks are disabled, each library build script registers
`prepareAarMetadataCheckDirs`, which creates the directory AGP's bundle tasks
declare as an input. Without it, a clean checkout fails Gradle input validation —
that broke the first two CI release runs (2026-11-16).

---

## Historical P2 — earlier NPU attempt

Superseded deployment plan: see P4 in [AUTOMIX_PARITY_CHECKLIST.md](AUTOMIX_PARITY_CHECKLIST.md)
and the version-pinned research. Current official QNN documentation includes an
Android package and hardware-dependent floating-point support. The earlier
reduced-runtime recipe, architecture guesses, package-size estimates and
cost/benefit claims below are not the current acceptance criteria. The existing
`NpuSelfTest` also cannot prove HTP dispatch while allowing CPU fallback.

`NpuSupport.QNN_ENABLED = false`. This is not a preference — the path has never
executed an inference on the device.

Two independent defects:

1. **QAIRT device config** — `QNN_DEVICE_ERROR_INVALID_CONFIG` before partitioning.
   The bundled stack carries Hexagon **V73 and V75** because `SM8635` could not be
   pinned from the QAIRT 2.51 sources, and no `htp_arch` provider option is set.
   Likely fix: set `htp_arch` explicitly in `NpuSupport.qnnProviderOptions`.
2. **Reduced ORT build** — `npu-addon/ops.config` lists only the ops present in the
   two models, so the build drops the ops ORT's optimizer introduces at load time
   (`MatMulIntegerToFloat`, `DynamicQuantizeMatMul`). Both models then fail on
   **both** backends, which is why `vc69` had no working analysis at all.

To re-enable, in order:

1. Add the optimizer-introduced ops to `npu-addon/ops.config`.
2. Rebuild ORT 1.31.0 with QNN EP (`npu-addon/build-ort.sh`) — needs the 2.8 GB
   QAIRT SDK subset and an ORT source checkout.
3. Re-add `npu-addon/prebuilt/jniLibs` as a jniLibs source set with pickFirsts.
4. Prove one inference on the Hexagon via `NpuSelfTest` before shipping.
5. Re-bundle ~100 MB of QAIRT libs, and re-check APK size.

Cost/benefit: the models are small (`beat_this` 22 MB q8, `umxhq` 8.6 MB int8) and
analysis is one-time per track, currently ~2 s. The NPU buys seconds nobody sees, in
exchange for 100 MB and a vendor stack that has never worked. **Do this after the
audio is right, not before.**

---

## Historical P3 — old feature inventory (superseded)

This table predates the current source review. It must not be used to conclude
that native flanger, phrase extension, echo rescue, isolator lanes or the A/B
harness are absent. Current implementation and validation status are in P3 of
[AUTOMIX_PARITY_CHECKLIST.md](AUTOMIX_PARITY_CHECKLIST.md).

The earlier vision playbook's iOS 27 parity ladder:

| Rung | Name | Status |
|---|---|---|
| 0 | `GAPLESS` | implemented |
| 1 | `LOOP_MIX` | implemented |
| 2 | `OUTRO_LOOP_DROP` | **not implemented** (0 files) |
| 3 | `BEAT_BLEND` | covered by `DJ_BLEND` + beatmatch |
| 4 | `ECHO_HANDOFF` | **partial** — `ECHO_RIDE` exists, tempo-jump rescue does not |
| 5 | `SMART_CROSSFADE` | covered by `EQUAL_POWER` / `GAPLESS` |

What exists today: `TransitionStyle` = `EQUAL_POWER, GAPLESS, DJ_BLEND, DJ_FILTER,
MUFFLE, DROP_IN, BEATLESS_BRIDGE`; filter variants = `SWEEP, BASS_HANDOFF,
ECHO_RIDE, DECLINE`; `MixTemplate` = `M1–M11, ROLLING, CUT_ON_ONE, ENERGY_LIFT,
RELAXED, VOCAL_OVER_OUTRO`.

### Native DSP debt

Deferred since djmix11: per-sample filter coefficient smoothing (the 64-frame zipper),
native flanger, policy-a curve, isolator lanes live, stems dry-run harness.

### Dual-ABI and emulator gates

`abiFilters` is `arm64-v8a` only. No `x86_64` build, no emulator runtime verification.
The vision playbook's Gate 2 and Gate 3 are unmet.

### Key accuracy

`Bubble Tea` `4B`, `Crescendo` `10A` vs `10B` — still unconfirmed by ears or telemetry.

### Last.fm credentials absent from the installed build

`buildConfigField("String", "LASTFM_API_KEY", ...)` in app/build.gradle.kts reads
`local.properties`, falling back to the `LASTFM_API_KEY` environment variable. The
`vc74` APK installed on the phone has both fields empty (proven by dex parity: the
CI build, which has no local.properties, produced byte-identical dex). So any
Last.fm-backed feature is inert on the device right now, and nothing about it can be
tested until those values are supplied to a build.

---

## Policy tunable, not a bug

`AUDIBLE_MAX_RATE = 1.09` (`TransitionPolicy.kt:90`). The `134.83 → 148.61` pair sits
at `1.1022`. Raising the ceiling to `1.11`–`1.12` would let it beatmatch. Real DJ gear
allows ±8 % (vinyl) to ±16 % (CDJ). Change only after the in-range pairs have been
heard, so a real regression is not masked by a wider window.

---

## Explicit non-goals

- **Broad model/DSP replacement during the vc79 clock repair** — outside this
  candidate. Later model choices require checkpoint-specific terms, device
  measurements and listening evidence; the earlier blanket Demucs rejection and
  a single deployment's memory figure are not universal conclusions.
- **QNN as a hard requirement** — CPU fallback stays on. The NPU is an optimisation,
  never a dependency.
