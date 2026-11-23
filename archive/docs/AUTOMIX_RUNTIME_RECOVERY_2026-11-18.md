# AutoMix offline runtime recovery — 2026-11-18

**STOPPED by the user.** The plan below is historical, not authorization to
continue. See [STOP handoff](AUTOMIX_STOP_HANDOFF_2026-11-18.md). Source writers
were cancelled; the uncommitted repairs have not been compiled or accepted.

## Pass goal and limits

Make the existing build → prime → arm → retire path ownership-safe and deadline-aware,
and make its tests establish actual controller behavior. This is an unshipped
development pass over `8807469`, not a musical-policy redesign or listening approval.
Installed vc82 (`60b9734`), accepted vc81 (`e7c8a3e`) and vc80 dropout repair
(`2cb6e6f`) remain protected. No ADB, phone controls, APK replacement or release
while the user is away. Native DSP, crown eligibility, vocal safeguards, gain
curves, lyric/ticker recovery and the established handover geometry are unchanged.

Baseline cue evidence: app compilation completed, 29 focused tests / 4 failures /
9 XML suites / exit 1. Shared focused pass remains valid for unchanged shared
inputs. Native ASan/UBSan compile/selftest/reverb/Echo/full63 probes all exited 0;
parent inspected raw evidence and independently matched all 41 native input hashes.
Full shared/app suites remain pending.

## Frozen writer boundaries

1. **Controller/session owner:** `CrossfadeController.kt`, `loopmix/LoopMixSession.kt`,
   `PlaybackService.kt` callback wiring, new Android-free work/attempt/spec types,
   `LoopBuildGenerationTest.kt` and separate ownership/adoption/cache tests.
2. **Analyzer owner:** `smart/TrackAnalyzer.kt`, `TrackAnalyzerDeadlineTest.kt` and
   separate region-budget/model-lifecycle tests. Narrow optional abort callbacks
   in shared `BeatTracker.kt` / `VocalTracker.kt` and their focused tests are now
   explicitly authorized; no tracker algorithm/model changes or app-type imports
   into shared. No service/common-type edits.
3. **Rig/attribution owner:** `AutomixArmingLivenessTest.kt`,
   `AutomixFireAttributionTest.kt`, and their shared rig if split into its own file.
   No production edits. Record directed filter/mid-gain recovery, not merely `open()`.

The controller owner also owns only necessary mechanical reflection updates in
`LoopHandoverCleanupTest.kt`, preserving all 12 scenarios/assertions. Append the
new controller seams trailing with defaults so the existing positional call stays
compatible. Read-only impact mapping found no other external old callback users.

All writers use the same isolated integration tree with disjoint file ownership;
parent alone commits/reconciles and freezes source for serialized cue execution.
No writer runs the full suite or changes the remote checkout during another job.

## Frozen interface (oracle design gate approved)

Use one explicit attempt-bound, nonblocking budget/progress view in public
Android-free `com.music.bitchord.playback.TransitionWork`:

```kotlin
data class WorkBudget(val deadlineMs: Long, val cancelled: Boolean)
interface TransitionWork {
    fun budget(): WorkBudget
    fun step(name: String)
}
```

`TransitionWork.NONE` returns `(DeckBudget.NO_DEADLINE_MS, false)` and reports no
steps. Only that sentinel is unlimited; other zero/negative deadlines are expired.
The parent created the contract file before dispatch; controller owner maintains it.

Append required `work: TransitionWork` to the controller `loopRegion`,
`separateTransitionWindow`, `separateTransitionBothWindows`, and
`separateTransitionPair` callback parameters, preserving return types and
nullable/default-null behavior. The region default gains one ignored parameter.
Service forwards the work object explicitly, including the pair alias.

Analyzer `loopRegion` replaces trailing `onStep` with
`work: TransitionWork = TransitionWork.NONE`. Both analyzer separator methods
replace trailing `deadlineMs, shouldAbort` with this same parameter/default.
Session closures supply attempt/deck-specific views. Callback owners do not edit
one another's files. No compatibility thread-local path is retained.

The controller owns atomic budget updates and binds each deck's progress callback
to its attempt. A paused prime's resumed deadline updates that same attempt, never
a replacement generation/context. Pass the work view explicitly through existing
region/separator callbacks; eliminate both thread-locals and the mutable service
`crossfade?.markDeckStep()` route. The analyzer only reads the view.

Inject a controller monotonic `nowMs` callback (production default unchanged) and
a narrow builder/dispatcher test seam. Rig tests control the same clock and one
tick boundary; scheduler time must not be mistaken for Android elapsed time.

## Required runtime changes

- One build attempt owns generation/scrub epoch, frozen complete rendered inputs,
  timing, budget, job and every unadopted result. Store the worker result before
  crossing back to Main; cancellation must close it exactly once unless ownership
  has explicitly transferred. Renderer setup must be exception-safe.
- One pre-mutation adoption gate covers cached prime, fresh build, completed prime
  and ticker pickup. Check attempt, pair/source/player/port identities, applicable
  settings/output rate and live media lead to the frozen splice. Permit appends
  after the incoming item. Reject late/wrong-pair results via existing recovery.
- Cache content equality and diagnostic/fire identity derive from the same frozen
  plan/script/analysis/lyrics/level/quality/stem specification. Same-length content
  changes matter; array identity, partial hashes and live cache rereads are invalid.
- Attachment transfers cleanup ownership before sink mutation. Partial attachment
  rolls back through existing bounded, clickless retirement; cleanup must survive
  cancellation of the playback scope. Never destroy an audible renderer as an orphan.
- Check live deadlines/cancellation during bounded lock waits and after acquisition.
  Playback-yield waits occur outside inference ownership. Serialize all model
  release with inference; shutdown refuses new work without blocking Main on an
  in-flight native call or dropping its eventual cleanup.
- The app wrapper alone cannot prevent later shared BeatTracker chunks or lazy
  initialization after preprocessing. Parent inspected the actual shared call
  sites and authorized an appended `shouldAbort: () -> Boolean = { false }` on
  applicable shared tracker methods, propagated into the beat chunk loop and
  checked before lazy session initialization and every new native run. This
  predicate never blocks or sleeps; default behavior/algorithms remain unchanged.
  Already-entered native work may finish. The app inference owner still serializes
  init/run/release; the callback is not a replacement for ownership.
- Do not duplicate synchronous decoding when an asynchronous decode is interrupted
  or cancelled. Both decode tasks share cooperative cancellation and the attempt budget.

## Evidence and fixture requirements

Drive the production controller through queued-Main cancellation, A/B out-of-order
completion, paused-prime resume under contention, all-route late/wrong-pair adoption,
partial attachment, setup failure and cleanup. Assert no publication/port preparation,
one destruction, survivor/queue/sticky-pause preservation and unchanged replacement
timing. Prove a later queue append remains allowed. Mutate only fold/ramp/script or
same-length grid/mask data to reject stale reuse, and mutate caches after acceptance
to prove frozen fire attribution. Worker failures must propagate to JUnit.

The shared rig owns/restores settings, telemetry and clocks. Keep the valid directed
survivor recovery invariant; an audible deck must not force generic `filters.open()`.
Exercise initial grace and terminal expiry with real controlled ticks. Add terminal
failed-arm suppression only if a clock-correct production regression proves harmful
immediate rearming; do not add a policy flag merely to satisfy a multi-tick fixture.
Replay the same A→B pair on one live controller using player/queue operations after
completion, not separate-controller UUIDs or private handoff-state resets.

Each lane supplies focused test names and static/source evidence. Parent reconciles,
then the existing cue runner executes affected checks and the final full
`:shared:jvmTest` / `:app:testProdDebugUnitTest` once on frozen source with exact
argv, exit status, raw logs and XML. A failed required gate blocks a candidate.
Source/test success cannot establish Android timing or musical acceptance.

## Evidence references and open questions

Council synthesis completed with GLM, Kimi and DeepSeek: majority support for
these offline boundaries, preserved safeguards and validation before promotion.
Gemini's first attempt and single retry both failed upstream HTTP/2; that seat is
unavailable. No four-seat agreement or missing opinion is claimed. The exact app contract is
an oracle/parent decision; the narrow shared checkpoint extension is a subsequent
parent-authorized scope adjustment, not attributed to absent council coverage.
Rearm suppression remains conditional on clock- and media-consistent evidence.

- `work/automix-swarm-2026-11-18/VERIFICATION.md` and
  `checkpoint-8807469-cue-verified/app/` retain the executed failing baseline.
- `work/automix-regression-2026-11-18/passive-vc82-045114/offline-seam-localization/`
  retains sample-local metrics, conditional clock ranges, commands and hashes.
  It captured WHIP→Kerosene, not the reported Adrenaline→Bloody Paradise pair.
- No strict 5 ms/-60 dBFS both-channel gap was found in that short active span;
  partial level losses and listening suitability remain open. Peaks occur before
  the coarse classic-fire range under the stated model, not proof of renderer cause.
- Exact-pair listening, sustained device reliability, pause cause, candidate
  acceptance and Apple parity remain unresolved. No new limiter or policy tuning
  is justified by these capture metrics.

Status: majority council synthesis supports the pass; oracle pre-writer design
gate approved the frozen app contract; parent
subsequently authorized the narrow shared abort-checkpoint scope after source
inspection. Candidate acceptance remains blocked on implementation and executed
regressions, including default/cancel controls and final shared revalidation.
