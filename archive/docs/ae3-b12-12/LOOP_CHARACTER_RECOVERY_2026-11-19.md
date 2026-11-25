# Loop-character recovery: bounded vc83 contract

## Status and scope

Implementation contract for the user-approved isolated branch
`automix/loop-character-20261119`, based on
`170328bed4b78eaa68e7fce739ba8b121e4aa38c` (documented installed-source vc83).
The independent regression is frozen. The isolated cue baseline reproduced the
intended vc83 admission failure: two tests, one failure, zero errors/skips; the
clean control passed and only the whole-track-metadata crown assertion failed.
The initial recovery is committed as `689285f`. The complete independent audit
found three attribution issues; their bounded follow-up repair is independently
reviewed and validated. Current results: shared 536 tests, app 1,446 tests with
five skips, and a separate four-test required-JNI session run; no failures/errors.
See `validation/loop-character/FULL_DIFF_REVIEW.md` and
`validation/loop-character/attribution-followup-result.json` for the final repair
identity and evidence. Android controller/sink takeover and musical listening
remain unproven.

This is not the historical vc49 work order in `/home/hfzhkn/opus5.5.md`.
The user subsequently authorized committing this isolated branch. Merges, APK
packaging/signing, phone operation, dependency/model downloads, NPU activation
and release remain outside this pass.

Keep dirty vc80, stopped82 WIP and unvalidated vc84 untouched. Retain accepted
finite-loop/dropout, ticker/lyric-boundary and vc83 readiness/ARMING protections.
Build/test only on the provisioned cue/Android host, in an isolated source
snapshot; cue's dirty default `vc82-overlap-listen` checkout is not the input.

## Behavioral target and uncertainty

A genuinely two-sided, locally clean and executable crown loop takes priority
over conservative whole-track musical preferences. `outLoops && inLoops` is
necessary: a cached bridge is not a crown. Do not restore loops by relabelling
bridges, forcing unsafe audio, changing effects rotation or imposing diversity
quotas.

The user reports Adrenaline (ATEEZ) -> Bloody Paradise (ENHYPEN) losing its
original loop, and many current Echo/Filter labels. Neither the named recording
pair nor current catalog-wide routing distribution is identity-mapped yet.
The initial repair targets a source-backed general admission regression, not a
proven cause of every reported fallback or a claim of matched listening quality.

The available historical reference is **djmix9 source reconstruction** at
`dc78e0d449d7bdf51e821f8e8c1de223bff8bf5a`, not verified stock/djmix6 behavior.
Blob hashes and independent literal geometry are recorded in
`validation/loop-character/fixture-provenance.json`.

## Settled admission policy

The council's majority adopts the renderer's existing **0.75-1.35x advancing
execution envelope**, not a newly imported +/-8% crown restriction. GLM's
tighter musical/confidence proposal remains a documented dissent; it is not
evidence of a hard renderer limit. Historical and current tests retain wider
`120->134`, `111->169` (fold 2) and `170->80` (fold 0.5) loop ramps.

For `q = (incoming local BPM / fold) / outgoing local BPM`, qualify both decks:
OUT endpoints `{1, q}` and IN endpoints `{1/q, 1}`. Reject invalid/non-finite
geometry and rates outside the established execution envelope. Preserve folds
`{0.5, 1, 2}`, existing grid/refinement rules and accepted constant-grid fallback.
Do not add a fused confidence score or another crown threshold.

The **unclamped** ramp requirement must fit the existing 2.5 BPM/bar and 2-24
bar constraints. Preserve whole-incoming-loop release rounding. Do not silently
clamp an excessive requirement into a different timing contract.

Validate the actual emitted `LoopMixSession.buildDeckKnots` arrays before
decoding/native construction, not merely a transport-only shared helper:
`rate = sampleRate * deltaSourceSeconds / deltaOutputFrames`. Check advancing
segments, repeated-loop boundaries and incoming native-rate settle. Only
existing declared initial silent holds are exempt. Do not alter knot generation
or depend on native clamping to make a rejected plan executable.

## Invariant precedence

1. Existing renderer/output, recording, playback-mode and queue/seek restrictions
   remain authoritative.
2. Local lyric certainty, vocal-mask coverage/share/run/peak and loudness/silence
   checks remain unchanged. Unknown evidence is not proven instrumental audio.
3. Apply crown qualification to new plans, shifted plans, KEEP and committed
   restoration. A rate/geometry-invalid crown cannot be resurrected by a cache.
4. A healthy committed crown stays sticky on unsuccessful refresh while its
   accepted recording/bounds remain valid. Contradictory local safety evidence
   invalidates it; changed whole-track preference alone does not.
5. A newly available crown does not retroactively displace a shown/locked bridge
   or classic countdown. Keep effective family and cache classification coherent.
6. Preserve complete physical stereo PCM, finite-loop/seam/release/EOF behavior,
   cancellation and one-way cold/decode/lead fallback. No readiness relaxation.
7. Without a qualified crown, keep the existing native bridge and classic decline
   policies. Confident whole-track key disagreement is not, by itself, crown
   execution infeasibility; musical acceptability remains a listening question.

## Write boundaries

Three behavioral production files:

- `shared/.../smart/LoopMix.kt`: nested pure
  `qualifyCrown(plan) -> CrownQualification`, explicit refusal reasons, unchanged
  clean-loop search and shift integration.
- `app/.../playback/CrossfadeController.kt`: new/KEEP/committed qualification,
  genuine-crown classification and existing-event attribution. Remove only the
  unused obsolete crown-decline variable/comment, not lower-rung decline gates.
- `app/.../playback/loopmix/LoopMixSession.kt`: actual-knot qualification before
  decode/native construction; preserve physical coverage and renderer lifetimes.

`TransitionPlanner.kt` may receive a comment-only correction explaining that its
coarse decline governs lower-rung routing, not qualified crown feasibility. Its
predicate and non-crown policies remain functionally unchanged.

Tests and fixture/provenance documents are separate write lanes. The initial
108-line `LoopEligibilityRecoveryTest.kt` is frozen at SHA-256
`278bb895415a888d714d700de7f288a1c279aca95970f0092b35b6e992406d3d`.
Do not weaken its positive metadata-conflict expectation or regenerate goldens
from the patched planner.

## Existing-surface routing evidence

The parent approves narrowly enriching existing persistent `AutomixTelemetry`
events. No new log file, UI, settings, analysis schema, dependency or full-map
stream. Unknown rendition/analysis provenance remains explicitly unknown.

Use immutable original pair and transition-opportunity identity. One opportunity
survives loop->bridge->classic fallback and decode retries; revisions/build
generations are separate from opportunity identity. Capture context before
asynchronous work and cleanup; do not re-read another pair's mutable analysis or
lyrics in a previously launched build.

Separate **candidate**, **accepted**, **prepared** and **executed** stages.
The early classic plan is a candidate, not the final adopted route. Native
takeover is a controlled execution marker, not proof of delegate buffer
acceptance, acoustic output or listening quality. Record actual built-session
technique and configured loop geometry, not just `mode=loop` or a stale local
candidate variable.

Deduplicate refusal codes by opportunity/stage/technique rather than changing
elapsed-time text. Keep global decline, local evidence rejection, missing
capability, invalid geometry, decode/PCM failure, cold yield and fallback
destination distinct. Optional stem unavailability can be dry rendering, not
automatic route rejection.

## Evidence budget and owners

| Claim | Owner | Minimum evidence |
|---|---|---|
| Early admission regression reproduced | Parent remote executor + snapshot verifier | Unmodified vc83: 2 fixed tests, control passes, metadata-conflict crown assertion fails; exact command/exit/XML |
| General crown policy corrected | Shared writer + parent executor | Frozen regression green; wider fold fixtures retained; reciprocal rate/ramp/geometry and vocal/coverage negatives |
| Controller policy and attribution coherent | Controller writer + parent executor | New/KEEP/restoration, committed bridge, late veto, repeated IDs, queue/seek and linked cold-fallback scenarios |
| Actual session executes within bounds | Session writer + parent executor | Real emitted knots, pre-decode refusal and isolated real-JNI build/process controls; nonzero valid PCM and correct release/resume |
| Accepted mechanics retained | Parent integration executor | Ordinary shared/app/UI checks and existing native selftest, reverb-stop and 63-pair loop-domain modes |
| Routing change explained | Fixed-input replay owner | Before/after candidate/accepted/prepared/executed outcomes and refusal chains, not arbitrary loop-frequency targets |

## Initial recovery candidate results (v10)

The bounded controller attribution addendum records coarse-decline/local-reason
and grid-source candidate fields plus immutable decline context through prepared
and executed native events. Terminal telemetry classifies normal native completion
only when execution and handoff were observed. The final v10 source archive
SHA-256 is `86ade61bf9c6a24d14eab6cc3712c5761fe8e1e4179e82c169ac36b3917e860a`.
This is a source/input snapshot digest, not a commit.

The final ordinary command exited `0`:

```sh
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
  ./gradlew :shared:jvmTest :app:testProdDebugUnitTest --offline \
  -Pbitchord.bundleWear=false --max-workers=2 --no-daemon --stacktrace
```

Evidence is retained under `validation/loop-character/candidate-final-v10/`.
Parsed XML totals: shared 536 tests, 0 failures/errors/skips; app 1,437 tests,
0 failures/errors, 4 skips. The separate required-host-JNI session run passed
3/3 tests with no skips. Focused checks passed: shared fixed
recovery/qualification/routing replay, app controller/knot checks, and the
isolated knot-boundary rerun.

Host probes were built from unchanged renderer sources and each exited `0`:
`--selftest` passed six renders with zero exit-gate violations; `--reverb-stop-probe`
passed production wet-preservation and stop thresholds (its deliberate zero-wet
sensitivity control failed as expected); `--loop-domain-probe` passed all 63
finite-loop combinations (54 exact-ramp, 9 unity), with minRMS 0.06433075,
maxReferenceDifference 0, minUnityCorrelation 1.0, postFadePeak 0. The host JNI
shared library built successfully; isolated `LoopCrownNativeSessionTest` exited
`0`, 3/3 passed, 0 skipped, including real native stereo output, loop wrap,
release/resume, configuration-vs-requested flags, and pre-decode refusal.

The planner-level fixed replay was run with identical test bytes on baseline and
v10 candidate. On nine synthetic opportunities, the selected candidate crown reach
changed 4->5; native bridge reach changed 5->4. The whole-track-conflict witness
changed `native-bridge:Echo` -> `native-crown`; clean/safety controls and the three
wide/folded crown witnesses retained their classifications. This is a synthetic
planner delta only. Its output explicitly marks controller acceptance,
preparation, controller takeover, delegate acceptance, and listening as
unobserved; it does not quantify the user's catalog or Echo/Filter prevalence.

The v10 ordinary integrated run and dedicated host-JNI session run both passed.
Terminal-outcome classification also has a focused regression test. These
results identify the initial recovery snapshot. The separately frozen audit
follow-up has newer regression totals and source hashes in its own receipt.

Baseline command, in an isolated cue snapshot after sourcing the provisioned
Android environment:

```sh
./gradlew :shared:jvmTest --tests '*LoopEligibilityRecoveryTest' --offline \
  -Pbitchord.bundleWear=false --max-workers=2 --no-daemon --stacktrace
```

Baseline red is established only by the intended crown assertion failure, with
successful compilation and a passing control. A different failure is blocked
evidence, not regression reproduction.

The actual baseline Gradle exit was `1`, intentionally reproducing the regression
after successful production/test compilation. The decisive stack frame is
`LoopEligibilityRecoveryTest.kt:86` (`assertNotNull`), reached from the conflict
test at line 69. Exact input/archive hashes, command, environment, XML and raw
log are retained in `validation/loop-character/baseline-result.json` and
`validation/loop-character/baseline-vc83/`. This establishes the synthetic
eligibility seam only; it does not replay the user's recordings or renderer.

After writer reconciliation, freeze an exact source/input digest and run the
required ordinary integrated checks once. Keep host-JNI availability isolated
from the ordinary app suite, whose decline harness requires native absence.
Dedicated native checks must fail, not silently skip, when loading/building
fails. Invoke native `--selftest`, `--reverb-stop-probe` and
`--loop-domain-probe` separately and retain numerical/case evidence.

Use a small fixed-input routing corpus with warm/cold and unsafe/uncovered
controls. Stage-reach counts do not necessarily sum to input count because one
opportunity can traverse several routes; report terminal outcome and fallback
chain separately. Missing best-effort telemetry is unknown, not zero executions.
Fixtures do not measure the user's entire catalog.

Every check retains exact command, source/input identity, exit and relevant raw
log/report. Later edits invalidate affected evidence. Stop on unexpected
disagreement; do not regenerate expected results or relax safety to get green.

## Deferred gates

If accepted crowns still execute Echo/Filter on the fixed inputs, eligibility
alone did not solve the reproduced problem. Scope the actual preparation failure
separately; do not relax another musical gate or abort deadline blindly.

Named-pair restoration, original sonic character and broad musical improvement
require exact recordings/analysis provenance, matched lifecycle evidence and
later authorized listening. Neural feature/ranking experiments and same-model
CPU/HTP acceleration remain separate; production model/NPU flags stay unchanged.
