# BitChord Plan-Swap Abort Trace — vc69 (`2.2.3-djmix27`, 6.78 s forensics) — 2026-11-15

**Scope:** transition controller only. Read-only trace on `bk-android` at `/data/workspace/bitchord-task2/work/BitChord` (`HEAD dc78e0d`, branch `bk-android`, state `running`). No code changed, no build, no install. Concurrent NPU worker files (`app/build.gradle.kts`, `npu_jni.cpp`, `NpuNative.kt`, `NpuSupport.kt`, `npu-addon/`) were not touched, but `CrossfadeController.kt` itself was `MM` (concurrent modification by the NPU work does not show in that file per `git status`; `CrossfadeController.kt` is `MM` from staged djmix27 lineage, not NPU — all reads were from the live worktree, 7285 lines).

**Evidence basis:** `BITCHORD_FORENSICS_VC69_20261115.md` (t=0–1.30 `Echo 8→7s 80 BPM`, t=1.30–1.40 switch to `Filter 14→9s 80→130`, t=1.485 filter snap AIR +19.2 dB centroid 1242→6001 Hz 10–90% ~43 ms RMS +2.2 dB, tempo 81 BPM stable both halves). All code citations below are from files actually read via `instabox exec bk-android`.

---

## 1. Who decides to replace a running render?

### 1.1 Definitions

Classic fill: `CrossfadeController.kt:627` `private var render = Render()` and `CrossfadeController.kt:649` `private class Render(...)`.
Builder: `CrossfadeController.kt:2374` `private fun renderFor(plan: TransitionPlan, advanced: Boolean) = Render(...)` — constructs from `plan.transitionStyle`, `plan.bassSwap`, `plan.filterSweep`, `plan.filterVariant`, `plan.echoSeconds`, etc. (lines 2374–2395).

Armed: `CrossfadeController.kt:2548` `private fun begin(...)` writes `CrossfadeController.kt:2583` `render = renderStyle` and `CrossfadeController.kt:2584` `planInFlight = plan`, then `CrossfadeController.kt:2658` `phase = Phase.ARMING`.

Preview: `CrossfadeController.kt:3496` `val planned = renderFor(plan, advanced)` in `publishMixPreview()` swaps `CrossfadeController.kt:3498` `render = planned` and restores `CrossfadeController.kt:3507` `render = running` in `finally`. This is a temporary inspection, not a live replacement — guarded by comment “`[render]` is only read by the blend, and no blend is running while there is a plan to preview.”

Restore: `CrossfadeController.kt:5684` `render = Render()` in `finish()`.

### 1.2 Every construction site

| Site | Line | Guarded by |
|---|---|---|
| `renderFor` in `begin(...)` | `CrossfadeController.kt:2358–2366` `renderStyle = renderFor(plan, advanced)` inside `considerSmartTransition()` | Reached only from `considerAutoTransition()` → `considerSmartTransition()` when `phase == Phase.IDLE` (see §1.3). |
| `renderFor` in `publishMixPreview()` | `CrossfadeController.kt:3496` | Only when preview popup open and blend not running; immediate restore. |
| `render = Render()` | `CrossfadeController.kt:5684` in `finish()` | After `finish()` has cleared `outgoing/incoming`, set `phase = Phase.IDLE`. |
| `render = Render()` | `CrossfadeController.kt:627` initial | Construction. |

`grep -n 'renderFor\|render ='` on the file returns only those four sites. There is **no other** `Render(...)` construction.

### 1.3 Every place `renderFor` is invoked during an in-progress transition

**None.** Both invocations are structurally IDLE-gated:

* `tick():1464` `Phase.IDLE -> considerAutoTransition()` dispatches planning only in IDLE. `tick()` is `1464–1471`:
  ```
  Phase.IDLE -> considerAutoTransition()
  Phase.ARMING -> driveArming()
  Phase.FADING -> driveFade()
  Phase.TAIL -> driveTail()
  Phase.SLEEP_FADE -> driveSleepFade()
  Phase.BAILING -> driveBail()
  Phase.LOOP_ARMING -> driveLoopArming()
  Phase.LOOP_RUNNING -> driveLoopMix()
  ```
  During `ARMING/FADING/TAIL/BAILING/LOOP_ARMING/LOOP_RUNNING` the planner (`considerSmartTransition`) is not called. The only planner calls inside non-IDLE are the fallback immediate replans in `loopFallBack()` after `loopBail()` has already driven `phase` back to IDLE (`CrossfadeController.kt:5229` `if (phase == Phase.IDLE && !inFallbackReplan)` → `considerSmartTransition(dur)`). That is a *post-teardown* replan, not a mid-flight swap.

* `beginLoop()` (loop native) writes `CrossfadeController.kt:4390` `phase = Phase.LOOP_ARMING` and never writes classic `render`. It writes `loopSession`, `loopTarget`, `loopPlanKey` instead.

* `publishMixPreview` is the sole exception and is explicitly not a live swap (save/restore around fader sampling).

### 1.4 Conclusion for Q1

A classic `Render` is never replaced while `phase != IDLE`. The Echo→Filter UI switch in the recording cannot be a classic→classic in-flight overwrite; it must be a teardown of a prior phase (LOOP or classic) back to IDLE, followed by a fresh `begin()`/ `beginLoop()` on the next IDLE tick. The only architectural path that *replaces* one live plan with another in the same tick is the loop rung fallback:

`considerSmartTransition` → `loopPlanFor`/`nativeBridge` yields `loop` → `beginLoop` arms `LOOP_ARMING`; `driveLoopArming`/`driveLoopMix` detecting failure → `loopFallBack` → `loopBail` (tears down session) → `finish` (returns to IDLE) → immediate `considerSmartTransition` for classic `begin()`. This is the loop→classic swap, and it is also the only place where a *new* `Render` is constructed in the same tick as the old session is torn down.

If the abandoned Echo was classic (dual-`ExoPlayer`) and the Filter is classic, the swap is still `bail → finish → IDLE → begin`, not an overwrite. No code path does `render = newRender` while `phase == FADING` or `LOOP_RUNNING`.

---

## 2. `bail()` vs `finish()` (`CrossfadeController.kt` ~5525 and ~5575)

### 2.1 Signatures

* `CrossfadeController.kt:5525` `private fun bail(force: Boolean = false)` — abandons whatever is in flight; handles the “both tracks audible” case with `filters.open()` + `Phase.BAILING` ramp (5563–5570).
* `CrossfadeController.kt:5575` `private fun finish()` — retires the spare, `filters.open()` + `filters.parkEchoes()`, resets `render = Render()` (5684), `incomingPlaybackRate/outgoingPlaybackRate = 1.0`, `phase = Phase.IDLE` (5732).

#### bail freeze (djmix10)

`CrossfadeController.kt:5526–5546`:
```
if (phase == Phase.IDLE || phase == Phase.BAILING) return
if (!force && (phase == Phase.FADING || phase == Phase.TAIL || phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING ||
    phase == Phase.ARMING)) { … "mix.freeze.onInterruption" … return }
if (phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING) { loopBail("interrupted", settle=false); return }
```
So an unforced `bail()` in any armed or audible phase is a no-op (frozen). Only `force=true` bypasses.

After the freeze, `bail` branches at `CrossfadeController.kt:5555`:
```
if (!handedOff && phase != Phase.FADING) { finish(); return }
filters.open()
if (handedOff) incoming?.volume = 1f else outgoing?.volume = 1f
bailFromGain = leavingOnBail()?.volume ?: 0f
phase = Phase.BAILING
```
Only when already `FADING` (or handed-off `BAILING` entry) does it take the audible ramp path; otherwise it delegates to `finish()`.

`finish()` at `CrossfadeController.kt:5609–5640` first checks the retire guard:
```
if (phase != IDLE && phase != TAIL && phase != BAILING) {
  val spare = if (handedOff) outgoing else incoming
  if (RetireGuard.audible(spare.volume)) { loopFinishAction…; beginTailOrFinish(spare, "emergency-retire-guard", …); return }
}
```
An audible spare diverts to `Phase.TAIL` (`CrossfadeController.kt:3201` `phase = Phase.TAIL`) and `finish` is re-entered after `driveTail`. So `finish` never retires an audible player directly.

### 2.2 Every caller — bail

| Caller line | Condition (user-/planner-visible) |
|---|---|
| `1090` `bail(force=true)` | `onPositionDiscontinuity` seek **cancel** (`SeekAction.Action.CANCEL`): user scrub that `SeekAction.shouldIgnoreSeek` + `SeekAction.decide` says must cancel. `force=true` bypasses the freeze, silent teardown, then `resetPlansForScrub()`. |
| `1129` `bail()` | Seek emergency-tail fallthrough when spare not audible: same discontinuity path, audible check failed. |
| `1150` `bail()` | `onMediaItemTransition(PLAYLIST_CHANGED)` — queue replaced (new album/search) while fading. |
| `1151` `bail()` | `onMediaItemTransition(SEEK)` — seek via queue change. |
| `1158` `bail()` | `onPlayerError` — decoder/network error. |
| `1182` `bail()` | `onPlayWhenReadyChanged(END_OF_MEDIA_ITEM)` when `heldAtEnd` ran out and `phase == ARMING`. |
| `1324` `bail()` | `dropForQueueEdit` when `phase != IDLE` and not audible — helper for `onPlaylistChanged` next-edit / standby divergence. |
| `1330–1333` `bail()` | `ranOutBeforeHandoff`: `ARMING/LOOP_ARMING/LOOP_RUNNING` or `FADING` with null players — outgoing exhausted before handoff. |
| `1428` `bail()` | `onSkipRequested()` — listener pressed skip. |
| `2766–2794` `bail()` ×3 | `driveArming`: null players, `!stillWorthFading()`, `!out.playWhenReady` (pause while armed), arm deadline expired after one grace extension (`mix.arm.expired`). |
| `2829–2830` `bail()` | `startFade`: null players. |
| `2893` `bail()` | `handOff`: `reconcileQueue` false (append diverged, not pure append). |
| `3021–3022` `bail()` | `driveFade`: null players. |
| `3083,3087` `bail()` | `driveFade` idle-stall `WAIT_AND_FREEZE` / `PREPARE_ONCE` (incoming `STATE_IDLE` 0–1.5 s). |
| `3092` `bail(force=true)` | Idle-stall `FORCE_BAIL` after 2 s when outgoing exhausted. |
| `5548` `loopBail(...)` inside `bail` | When `phase == LOOP_ARMING/LOOP_RUNNING` and freeze bypassed (force), delegates to `loopBail`. |

All unforced `bail()` during `FADING/TAIL/LOOP_ARMING/LOOP_RUNNING/ARMING` hit the freeze return at 5534. In practice the only audible-phase exits are via `force=true` (seek cancel, force stall) or via the `loopBail` / `loopFallBack` paths that bypass `bail` entirely.

### 2.3 Every caller — finish

| Caller line | Condition |
|---|---|
| `5557` `finish()` inside `bail` | `bail` when `!handedOff && phase != FADING` — inaudible abort, no ramp needed. |
| `3182,3216,3223` `finish()` | `driveTail` — end of graceful-exit dissolve or null target. |
| `3718,3730` `finish()` | `driveBail` / `finishSleepFade` / sleep-fade completion (BAILING ramp done, or sleep fade paused). |
| `4848` `finish()` | `driveLoopMix` when `loopSession == null` (already bailed). |
| `5114` `finish()` | `finishLoop` after renderer `exitGateViolations` handling. |
| `5180` `finish()` | `loopBail` after `loopSession = null` (emergency settle point). |
| `3223` (inside `driveBail` progress ≥1 via `finish`) | Bailing dissolve finished. |

Other retire-guard diversions (`beginTailOrFinish` at 5609) and `finishLoop()` at 5114 are sub-paths, not separate callers.

### 2.4 Which one fires on a *plan change*?

**Neither, directly.** A plan change (new `TransitionPlan` from `planTransition` `1804` or new `LoopMix.Plan` from `loopPlanFor` `1956` / `nativeBridge` `1963`) in IDLE only updates `lastSmartPlan`, `loopPlanned`, `loopPlanKey`, `smartTransitionWindow`, and (if lead reached) calls `begin`/`beginLoop`. It does not call `bail()` or `finish()`.

The *only* plan-change-driven teardown while a session exists is the loop rung failure path:

* `loopPlanFor()` Hold `TAKE/CLEAR` with `sungVeto` → `loopBail("sung veto")` at `3951` while `LOOP_ARMING`.
* `dropVetoedLoop(pair, disarm=true)` → `loopFallBack("sung veto")` at `3999`.
* `driveLoopArming` watchdog/build-missed → `loopFallBack("build-missed-splice")` at `4779`, `shiftLoopOrFallback` → `loopFallBack` at `4595–4599`.
* `checkShownWatchdog` (IDLE shown-but-unarmed, 1.2 s after its bar) → `loopFallBack` at `1532`, and LOOP_ARMING still-building → `loopFallBack` at `1558`.

Each calls `loopFallBack` → `loopBail(reason, settle=false)` → `finish()`. Those are classified as rung *failure/yield*, not a generic plan swap, and they carry telemetry `mix.loop.fallback` + `mix.arm.death`.

A classic `FADING` plan change never happens because the planner is not polled during `FADING` (§1.3). Queue-edited pairs that change `plannedNextId` while IDLE simply discard prime and replan; if `phase != IDLE`, `dropForQueueEdit` freezes unless audible, and if not audible it calls `bail()`.

Natural end fires `driveFade done` → `beginTailOrFinish` → `driveTail` → `finish`. Pause while armed fires `driveArming bail` (2772). Skip fires `onSkipRequested bail`. Seek fires `onPositionDiscontinuity` `force bail` or emergency-tail. Track skip / playlist change fires `onMediaItemTransition` bail. Plan reversal (analysis refines while IDLE) fires none of these until the next `begin`.

---

## 3. Is there any guard preventing a plan swap mid-transition?

### 3.1 Latches / flags searched

Search terms applied to both `CrossfadeController.kt` and shared planner: `transitionActive`, `inTransition`, `lock`, `armed`, `committed`, `isTransitioning`, `versionSwapActive`, `countdownStabilizer.committedFor`, `frozen`.

What exists:

* `CrossfadeController.kt:984` `fun isTransitioning(): Boolean = phase != Phase.IDLE` — a public query for `PlaybackService` quality-upgrade gating, not a planner latch.
* `CrossfadeController.kt:507` `private var phase = Phase.IDLE` + `enum Phase { IDLE, ARMING, FADING, TAIL, SLEEP_FADE, BAILING, LOOP_ARMING, LOOP_RUNNING }` — the state machine itself. `tick` gating (§1.3) is the de-facto “no new plan while not IDLE” guard.
* `CrossfadeController.kt:5525–5546` freeze-once-armed: unforced `bail()` is suppressed for `FADING/TAIL/LOOP_ARMING/LOOP_RUNNING/ARMING`. Freeze is deduped by `freezeDedupKey` and emits `mix.freeze.onInterruption` once per phase/pair.
* `CountdownStabilizer.kt:103–115` `consider()` / `stabilizeClassicPlan()` anti-oscillation lock: once `(pairKey, scrubEpoch)` is `shown/locked`, `targetStartMs` may not move `> MAX_DRIFT_MS` (1500 ms), family (`LOOP` vs `NATIVE_BRIDGE` vs `CLASSIC`) may not change, and past anchors stay pinned. `CanvasStabilizer` also suppresses failed families via `onRungFailed` (`CountdownStabilizer.kt:75–95`).
* `CrossfadeController.kt:1971–2001` loop anti-oscillation: if `committedBeforeLoop` is `LOOP/NATIVE_BRIDGE` locked within `PAST_GRACE_MS` and new candidate differs `> MAX_DRIFT_MS` with same pair, keep previous `loopPlanned`.
* `CrossfadeController.kt:2213` `if (phase == LOOP_ARMING || phase == LOOP_RUNNING) return` in `considerSmartTransition` — blocks classic planning while loop session is live.

What does **not** exist:

* No `transitionActive` / `inTransition` / `committedTransition` boolean that latches a plan and ignores later `lastSmartPlan` updates while `phase != IDLE`. `lastSmartPlan` and `loopPlanned` are still overwritten on every IDLE tick before arming; after arming they are not read until `finish` clears them. There is no `if (phase != IDLE) return` *inside* `considerSmartTransition` except the loop early-return — the IDLE gate is only in `tick`.
* `isTransitioning()` is not consulted inside `considerSmartTransition`, `loopPlanFor`, or `nativeBridge`. A direct call from `loopFallBack`’s immediate replan (`5229–5235`) intentionally re-enters planning in the same tick after `loopBail` set `phase = IDLE`.
* The bail freeze is bypassable: `force=true` (seek cancel `1090`, stall force `3092`) tears down `FADING` anyway. `loopBail` itself is not behind the freeze at all — it is called directly from `driveLoopArming/LoopMix` and watchdog paths without checking `phase` (e.g. `driveLoopMix:4804`, `driveLoopArming:4741`).
* `versionSwapActive()` (437) is the only interlock that returns early from planning (`1636`, `1601`), and it guards the version-swap owner of the standby player, not plan stability.

**Verdict:** There is a *coarse* guard (tick IDLE dispatch + bail freeze + CountdownStabilizer drift lock) that prevents a classic planner tick from running mid-`FADING`. There is **no commit latch** that prevents the loop→classic fallback from tearing down a live `LOOP_RUNNING` session once `onRungFailed` fires, and no guard that prevents `force` bail from tearing down a live `FADING` render on seek/stall. In other words, a plan that is already rendering can be abandoned by design.

---

## 4. Why did the tempo morph not execute?

### 4.1 What the UI promised

Forensics: `Filter · 14s → 9s · 80→130 · out — → in intro · locked` (lines 538–545 of the forensic file). The `80→130` text comes from the classic headline builder at `CrossfadeController.kt:5408–5432`:

```
val outBpm = analysisFor(item).bpm
val inBpm = next.let { analysisFor(it).bpm }
val bridge = if (outBpm > 0 && inBpm > 0) "${outBpm.roundToInt()}→${inBpm.roundToInt()}" else ""
```
`outBpm`/`inBpm` are the *raw* analysis BPMs, not folded, not stretched. The display does not encode a rate.

### 4.2 How tempo params reach the rate setter

* `TransitionPlan` carries `incomingPlaybackRate` (`TransitionPlanner.kt:220`) and `outgoingPlaybackRate` (`TransitionPlanner.kt:227`). For a beat-matched tier they are `incomingRateFor(mediaRatio, advanced)` / `outgoingRateFor(mediaRatio, advanced)` (`TransitionPlanner.kt:745–746`). `mediaRatio` is `1.0` when `tempoFar` (outside `stretchReachable` after folding) — the far-tempo (80 vs 130, 62% gap) case.
* `CrossfadeController.kt:2577–2578`:
  ```
  incomingPlaybackRate = audibleTempoRate(playbackRate)
  outgoingPlaybackRate = audibleTempoRate(outgoingRate)
  ```
  where `playbackRate = plan.incomingPlaybackRate`, `outgoingRate = plan.outgoingPlaybackRate` from `begin()` parameters (`CrossfadeController.kt:2358–2366`).
* `CrossfadeController.kt:2623` `into.setPlaybackSpeed(deckSpeedSafe(AppSettings.playbackSpeed.value, incomingPlaybackRate))` with `deckSpeedSafe` → `safeRateForDeck` → `safeDeckSpeed` clamped to `[0.92f, 1.08f]` (`CrossfadeController.kt:154–174`), and `audibleTempoRate` clamped to `[AUDIBLE_MIN_RATE, AUDIBLE_MAX_RATE]` (`TransitionPolicy.kt:99–107`, ceiling 1.09).
* `CrossfadeController.kt:2862` `out.setPlaybackSpeed(deckSpeedSafe(..., outgoingPlaybackRate))` in `startFade` catch-up; `CrossfadeController.kt:6634` `rampOutgoing` steps the outgoing player toward `outgoingPlaybackRate` over `rampSteps` beats before the fade; `CrossfadeController.kt:2911` `handOff` resets incoming to listener speed (`beatmatched=false`), and `CrossfadeController.kt:5703` `finish` re-asserts it.
* Echo render: `LoopMix.echoPlan()` sets `fold = 1.0`, `rampBars = 0`, `targetBpmOverride = outBpm` (`LoopMix.kt:1428–1433`). No pitch/tempo change; the echo rides at native tempo. Classic `ECHO_RIDE` variant likewise carries `echoSeconds > 0` but its rate is still 1.0 for a far pair.

### 4.3 Why the morph never ran

The 80→130 pair is far outside the beatmatch cap (`TEMPO_HARD_DELTA` ~8%, `AUDIBLE_MAX_RATE` 1.09, `safeDeckSpeed` 1.08). `TransitionPlanner` therefore classifies it as `DJ_FILTER`, not `DJ_BLEND`, and sets `incomingPlaybackRate = outgoingPlaybackRate = 1.0` (`mediaRatio = 1.0` when `tempoFar`). The filter swap keeps each deck at 1×; the “morph” is a *filter ride*, not a stretch. The abandoned Echo render’s rate state was 1.0, and the new Filter render’s rate state is also 1.0. Neither is stale.

No ramp state needed clearing because no ramp was ever scheduled:

* `rampSteps` is `0` when `beatMs == 0` or `rampStepsFor(outgoingRate) == 0` (`CrossfadeController.kt:2579`). For Echo (`rampBars = 0`) and for DJ_FILTER (`outgoingRate = 1.0`) this yields `rampSteps = 0`, `rampStartMs = endMs - fade`.
* On `bail`/`finish`, `render = Render()` resets `buildup=false`, `echo=false`; `incomingPlaybackRate/outgoingPlaybackRate` are zeroed to 1.0 (`CrossfadeController.kt:5725–5726`). `finish` also does `it.setPlaybackSpeed(..., beatmatched=false)` to pin the incoming player to listener speed (5703) and `beginEase` for a non-handoff outgoing rate (5712–5714). No stale 1.62× or similar rate survives.

The baseline is not “invalid.” `begin()` recomputes `rampStartMs` from the *new* plan’s `endMs/fade/beatMs` (`2579–2585`), not from the abandoned render’s clock. The morph did not execute because the planner never emitted a morph for this pair — the UI’s `80→130` is raw BPM display, and DJ_FILTER’s gesture is a low-pass sweep + bass handoff, not a `Clock.tempo` ramp. The native tempo-ramp (`Clock.tempo(k)` `LoopMix.kt:979–990`) only runs for `LoopMix` rungs; classic DJ_FILTER never consults it.

If the Filter were a native `Beat blend` (target 130), `Clock.tempo` would have ramped over `rampBars` (2–24 bars, `LoopMix.kt:500`). The recording shows no such ramp (autocorrelation 81 BPM both halves, no bend). That is consistent with a classic DJ_FILTER, not a native Beat blend.

---

## 5. What runs in the ~85–185 ms between the UI label change and the audio snap?

Forensic window: label `(1.30, 1.40]`, snap `1.485` → 85–185 ms.

In code, that window cannot be a single `filters.open()` glide (`GLIDE_RATE 0.05` per 64-frame block ≈ 30 ms, `TransitionFilterProcessor.kt:559` “~30 ms time constant”). The delay is the sum of discrete stages, all on the controller thread except the native build:

1. **Planner tick + compile** (`CrossfadeController.kt:1770–1804` `considerSmartTransition` → `planTransition` `1804`). `planTransition` runs `DjMix.annotate`, `TransitionPolicy`, `TransitionPlanner` adaptive overlap, and (if loop not suppressed) `loopPlanFor` (`1956`) + `nativeBridge` (`1963`/`2106`) + `phrasePlanFor`. `PLAN_TIMEOUT_MS = 40 ms` is the documented budget (handovers). This is the first tens of milliseconds.
2. **Loop yield / prime decision** (`2044–2158`): `isLoopFastReady`, `primeLoopDecks` (if within lead and cold) starts `startLoopBuild` coroutine (`4416`). The build is a background `LoopMixSession.build` decode (blocking, on `Dispatchers.Default`, `4485`) — not in the 85 ms foreground, but its `discardPrime()` and `primedSession?.close()` are synchronous. If a prior prime was still active, `discardPrime()` (`4571`) cancels `primeJob`.
3. **Teardown of the prior session** — the snap stage. For a Loop Echo session:
   * `loopFallBack` (`5197`) → `loopBail` (`5124`) → `finish()` (`5180`). `loopBail` disarms `outPort/inPort` (`5167–5170`) immediately and schedules `closeLater(renderer)` (`5182`, delay `LOOP_RENDERER_CLOSE_DELAY_MS = 1500` `7250`). The native renderer is therefore *disarmed* synchronously; the `nativeDestroy` is deferred 1.5 s.
   * `finish()` (`5643–5684`) does `discardPrime()`, `releaseHold()` (`5655`), `filters.open()` (`5676`) + `filters.parkEchoes()` (`5677`). `filters.open()` is `setCutoffs(OPEN_HZ, OFF_HZ)` (`TransitionFilterProcessor.kt:127`), which only sets volatile targets. The **audible** glide happens downstream in `TransitionFilterProcessor.filter()` (`TransitionFilterProcessor.kt:275–314`) at `GLIDE_RATE`. That maps to ~30 ms.
   * If the prior session was classic `FADING` with a `force` bail, `bail` itself calls `filters.open()` at `5563` before setting `Phase.BAILING`, then `driveBail` (`3722`) ramps `leavingOnBail()?.volume` over `BAIL_MS = 120` ms (`7200`). The snap is the filter glide, not the volume ramp (volume moves only +2.2 dB in the recording).
   * `PlaybackService` wiring (`1985–2022`) shows `filters.open()` fans out to both sinks: `activeFilter.setCutoffs(OPEN_HZ, OFF_HZ)` and `spareFilter.setCutoffs(...)`. The sink role swap (`activeFilter`/`spareFilter`) means the open reaches both decks.
4. **New arm** (`begin`/`beginLoop` only after teardown returns to IDLE): queue copy (`CrossfadeController.kt:2592–2603`), `into.setMediaItems` (`2631`), `into.prepare()` + `notePrep` (`2636`), `holdAtEnd` (`2609`). `setMediaItems` takes microseconds; `prepare` is async. `driveArming` then waits for `into.playbackState == STATE_READY` (40 ms tick `ARM_STEP_MS`, `1373`). The 85 ms window is dominated by stages 1–3, not by DSP.

Verdict: the snap is caused by the *swap itself* — specifically the `filters.open()` in the teardown of the prior session (`bail:5563` or `finish:5676`), whose glide (≈30 ms source) is measured as 43 ms through the AAC/analysis smear. The intermediate stages (compile, queue copy, `discardPrime`, `closeLater` scheduling) account for the 85–185 ms latency between the *label* (published from `lastSmartPlan`/`loopPlanned` in `describeAutomix` `5297` before arming) and the *audio* (published after teardown settles). A native session rebuild is not required for the snap; in fact the rebuild is deferred (`closeLater`) and the classic standby prepare is what is still buffering during the window — the snap precedes the new blend.

---

## 6. Native path — does `LoopMixSession` get torn down and rebuilt on a plan change?

**Yes, on any rung change that cannot keep the current session.** The native session is bound to a single `LoopMix.Plan` + `Clock` + `renderer` + `outPort/inPort`. Any of these forces teardown:

* `primeKey` (`CrossfadeController.kt:4560` `"${plan.rung}|${outId}->${inId}|${plan.out.loopStart}|...|$sampleRate"`) mismatch in `primeLoopDecks` → `discardPrime()` closes `primedSession` (`4571–4582`).
* `loopPlanFor` Hold `TAKE/CLEAR` (`3915–3951`) drops `loopPlanned/loopScript/loopPlanKey` and (if armed) calls `loopBail`/`loopFallBack`.
* Wall-clock shift (`shiftLoopOrFallback` `4591–4653`) disarms the old `loopSession` and builds a new one for the shifted bar.
* `loopBail` (`5124`) disarms `outPort`/`inPort` and nulls `loopSession` before `finish()`.

A classic plan change in IDLE that does not involve a loop (`loop == null`) does not touch the native session at all — it goes straight to classic `begin()`.

**Does teardown have a fade?** Only in two places:

* `LoopMixRenderer.setFadeOut` (`LoopMixRenderer.kt:81`) — “Fade `[index]` out over **25 ms** once it plays track second `[trackSeconds]` after its release.” Called once at build time to arm the incoming deck’s 25 ms tail (`LoopMixSession.kt:646` `renderer.setFadeOut(LoopMixRenderer.IN, handoverTrackSec)`), and in the seek-emergency-tail path (`CrossfadeController.kt:1106–1110` `setFadeOut(OUT, heardOut+0.35)` + `setFadeOut(IN, heardIn+0.35)` before `loopBail(..., deferDisarm=true)`). The 25 ms edge is a *native* per-deck tail, not a blend dissolve.
* Normal `loopBail` / `loopFallBack` does **not** call `setFadeOut` before disarming. It relies on the caller’s `finish()` path:
  * If a spare is audible, `finish` diverts to `Phase.TAIL` with `EXIT_TAIL_MIN_MS = 800 ms` (planned) or `EXIT_TAIL_EMERGENCY_MS = 350 ms` (emergency) (`CrossfadeController.kt:3196–3201`, `beginTailOrFinish`), then `driveTail` ramps `out.volume` with `MixGain.fall` (`3219–3223`). This is an `ExoPlayer` volume glide, not a native renderer fade.
  * If the session is pre-takeover (`LOOP_ARMING` without `tookOver`), `finish` silences the spare and finishes immediately (0 ms to IDLE) (`CrossfadeController.kt:5612–5618`).
  * `LoopMixSession.close()` (`LoopMixSession.kt:116–122`) only `disarm()`s ports and `renderer.close()` (440 kB native object, handle zeroed at `LoopMixRenderer.kt:86–89`). The close is deferred 1.5 s via `closeLater` (`CrossfadeController.kt:5238–5242`) to let the retired sink stop calling `render()`.

So on a plan change the native teardown is **disarm + volume dissolve + deferred native destroy**, with a 25 ms native fade only at the *end* of a normally-planned session (via the pre-armed `setFadeOut(IN)`) or on seek. A mid-flight `loopBail` relies on the 350 ms emergency tail (or 800 ms planned tail) volume ramp, which at `vol = 1.0` is a −3 dB midpoint dip (equal-power), not a spectral snap. The observed spectrum (AIR +19 dB, RMS +2.2 dB) is therefore **not** a LoopMixSession teardown signature; it is the classic `TransitionFilterProcessor` glide.

---

## 7. Is this reproducible by construction?

**Yes.** The artifact is an emergent property of the architecture: a *visible* classic plan (label) can be published in IDLE before the *audible* classic render is armed, and the prior session’s filter can be left in a non-open state whose `filters.open()` is then audible if the prior session is torn down while both decks are near full volume.

Minimal plan sequence that will *always* produce the artifact (deterministic, test-pinnable):

1. Two tracks A→B where analysis is `ANALYSED/REFINING` and `planTransition` returns `DJ_FILTER, filterVariant=SWEEP, filterSweep=1.0, echoSeconds=0` with `fadeSeconds ≈ 14 s`, `incomingCueTime` at B’s intro.
2. The same pair, on the *previous* tick, returned `LoopMix.Plan rung="Echo"` (`LoopMix.echoPlan`, `LoopMix.kt:1408–1435`, `echoTail=true`, `targetBpmOverride=outBpm`, `incomingHoldSec=bar`). The loop is `shown/locked` (countdown `> 0`) so `describeAutomix` shows `Echo · 8s · 80 BPM · locked`.
3. Playhead advances ~1 s; the next IDLE tick refines analysis (head→full, or lyric mask lands) and `LoopMix.plan` now errs on the side of veto or yields (`cold-loop` headroom <8 s, `LoopMix.shouldYieldColdLoop`, or sung veto). `countdownStabilizer.onRungFailed(..., Family.LOOP)` suppresses LOOP and `loop` becomes `null`. Classic `planTransition` still returns the DJ_FILTER plan above (its `transitionStart` is still ahead of the playhead and `markable`).
4. `describeAutomix` now shows `Filter · 14s · 80→130` (raw BPMs), and `begin()` for the classic transition is queued.

If steps 2–3 are ordered such that the Echo `LoopMixSession` has taken over (`LOOP_RUNNING`, `outPort.tookOver=true`) and the classic `begin()`’s `driveArming` still needs the standby to buffer, the teardown of the Echo session (step 3’s `loopFallBack → loopBail → finish`) calls `filters.open()` while the outgoing deck is still at `out.volume ≈ 1.0` (per `mixFall` hold at `full` until `fallFrom`). The parked low-pass (see Secondary) is whatever the Echo’s exit had left — for an Echo in `enterEnd..rampEnd` the outgoing low-pass is at its entry point 7 kHz → ~4 kHz (early progress), giving the measured AIR delta.

The same holds for any plan-to-plan swap where:

* prior render’s outgoing low-pass `targetLowPassHz < OPEN_HZ` (e.g., `filterSweep` ride at progress > 0 or native `Muffle 20000→700 Hz` envelope `LoopMixSession.kt:545–547`),
* prior render’s outgoing volume `> 0.5`,
* swap is `loop → classic` (or `classic → classic` via `finish` on a `force` path) while `handedOff == false` (or `true` with spare audible).

A test can pin this by: arming a classic DJ_FILTER with known `render.filterSweep`, advancing to progress ≈ 0.1–0.2, freezing `render` and `outgoing.volume = 1.0`, then calling `loopFallBack`-equivalent teardown (`finish()` after setting `phase = FADING` / `LOOP_RUNNING` and `handedOff = false`) and asserting that `TransitionFilterProcessor.isFiltering` was true before and that `filters.open()` was called while `outgoing.volume > 0.001`. Currently no test asserts that.

---

## Secondary inspections

### S1. Can `rideEcho` / echo leave the filter parked for a later `filters.open()`?

**No for `echo` in the classic sense; yes for the general `DJ_FILTER` sweep (which the Echo label can also be).**

`rideStyle` (`CrossfadeController.kt:5910–5942`) dispatches:
```
DJ_FILTER -> SWEEP/ECHO_RIDE -> rideFilterSweep(progress)  // 6144–6159
            BASS_HANDOFF -> rideFallbackBassHandoff
if (render.echo) rideEchoOut(progress)                     // 5941
```
`rideFilterSweep` (`6144`) sets:
```
val cutoff = glide(entry, floor, progress.pow(FILTER_SWEEP_SHAPE))  // entry 7000, floor 1500
tracks.outgoing(cutoff, bassCutoff(handover))
tracks.incoming(OPEN_HZ, max(entryCorner, bassCutoff(1-handover)))
```
`rideEchoOut` (`5980–5986`) then *overwrites* the outgoing side:
```
tracks.outgoing(OPEN_HZ, bassCutoff(handover))
if (p < render.echoFrom) return
tracks.outgoingEcho(echoSecondsF, send, echoDry)
```
The comment is explicit: “that track stays open — an echo of a muffled signal is only mud.” So for a classic echo (`render.echo == true`), the outgoing low-pass is forced to `OPEN_HZ` (20 kHz) on every fade tick after the first. The incoming high-pass is **not** touched by `rideEchoOut` and remains at `entryCorner` (≈1200 Hz → descending). `filters.open()` on such a render therefore opens the *incoming* high-pass (lows, not AIR) and the outgoing bass high-pass — it does not produce an AIR jump.

`rideEchoOut` also does not call `tracks.outgoing(OPEN_HZ, OFF_HZ)` after `echoAt`; `echoDry` (`5991`) fades the dry to 0 over `echoKill` (4 beats) while the master `out.volume` is held at 1.0 by `mixFall` echo branch (`6364`). The tail is carried by the echo ring. `finish`/`bail` then must `filters.parkEchoes()` to cut the ring (5677); `bail` does not park echoes (`5555–5570` only `filters.open()`), so a bailed echo can leave a ringing tail until the next `finish` parks it.

The parking hazard is therefore **not** `rideEcho`, but the plain `DJ_FILTER SWEEP` (and `MUFFLE`): at progress 0.125 the outgoing low-pass is ~6.8 kHz (computed above), at 0.5 it is ~2.2 kHz (`glide(7000,1500,0.5^1.8=0.287)` → `7000*(1500/7000)^0.287 ≈ 7000*0.637`? — exact: `exp(ln7000 +0.287*(ln1500-ln7000)) ≈ 4100 Hz`), at 1.0 it is 1500 Hz. All three are parked below `OPEN_HZ`. `filters.open()` from any of them is audible as a spectral reopening.

### S2. Is +19.2 dB AIR consistent with opening from a specific parked cutoff?

Estimate (Butterworth 2-stage, 24 dB/oct, geometric glide). Attenuation at frequency `f` above cutoff `fc` is approx `−24·log2(f/fc)` dB. For the measured broadband AIR (4–15 kHz) +19.2 dB:

* If the band were dominated by 10 kHz, `fc ≈ 10 kHz / 2^(19.2/24) ≈ 10/1.74 ≈ 5.7 kHz`.
* If dominated by 8 kHz, `fc ≈ 4.6 kHz`.
* If dominated by 6 kHz, `fc ≈ 3.4 kHz`.

The recording’s centroid moved 1242 → 6001 Hz, and the 5 ms hop showed +6.7 dB in one hop at the steepest point. The pre-step floor (1.40–1.48 s) was AIR −1.9…+3.3 dB, centroid 1078–1985 Hz — a muffle in the ~1–2 kHz region with musical notches (500–1 kHz) that defeat a naive −20 dB edge estimator (both pre/post returned 520 Hz in the forensic). The **post-step** spectrum was wideband to ≥12 kHz (15 kHz codec ceiling hides 20 kHz). So the only defensible statement is: muffle in the ~700 Hz–1 kHz region opening to wideband, step ~40 ms. Exact “700 Hz” vs “20 kHz” cannot be quoted from the recording (AAC 23 ms frames + 15–20 ms windows smear 30 ms to 43 ms).

Which render’s parked state fits?

* **Classic DJ_FILTER SWEEP at early progress (0.1–0.2):** predicted cutoff 6.8–4.8 kHz → AIR delta 13–22 dB at 10 kHz, centroid ≈ 2–4 kHz pre-step. Fits both the delta *and* the early-progress (Echo 8→7 s = 1 s into an ~14 s window, progress ≈ 0.07–0.12) position. This is the best fit.
* **Classic FILTER_FLOOR (1500 Hz) at late progress (0.9–1.0):** attenuation at 10 kHz ≈ −65 dB → delta >> 19 dB. Too deep; not observed.
* **MUFFLE_FLOOR (700 Hz) (`CrossfadeController.kt:6866` `MUFFLE_FLOOR_HZ = 700.0`, and native `LoopMixSession.kt:545` `20000→2800→700`):** attenuation at 10 kHz ≈ −92 dB. Not observed.
* **Native LoopMix `KIND_LOW_PASS_HZ` envelopes** (same numbers, same reasoning) — not observed as a Loop teardown, and Loop teardown does not call `filters.open()`.

The measured +19.2 dB therefore identifies the parked state as a **classic DJ_FILTER sweep early-progress cutoff (~5–7 kHz)**, not a muffle floor. That in turn means the abandoned render was a **classic SWEEP** (or `ECHO_RIDE` *before* `rideEchoOut` overwrote it — see §S1: at `p < echoFrom`, `rideEchoOut` still overwrites to `OPEN_HZ`, so ECHO_RIDE cannot be parked on the low-pass side; the parked state must be the SWEEP variant or the BASS_HANDOFF’s `outgoingTone`). The label “Echo” in the first 1.30 s may therefore be either the DjEffect echo throw (`mixStyle → echo`) on a DJ_FILTER SWEEP, or a native Echo whose session was superseded before its first `rideEchoOut` tick — both converge to the same audible outcome because the snap comes from the *classic* teardown’s `filters.open()`, not the native session’s close.

The HPF half (`BASS_SWAP_HZ = 200 Hz`, `CrossfadeController.kt:6880`, bass-cutoff glide `bassCutoff`) is **not** validated: SUB is flat (17.0→17.1 dB) in the forensic. The low-pass is the proven half.

---

## Call-graph sketch (plan-replacement path)

```
tick (1464 IDLE) ──> considerAutoTransition
  └──> considerSmartTransition (1749)
        ├── planTransition (1804) ──> TransitionPlan (DJ_FILTER SWEEP | DJ_BLEND | …)
        ├── loopPlanFor (1956) / nativeBridge (1963) ──> LoopMix.Plan (Loop | Echo | Beat blend | …)
        ├── countdownStabilizer.consider / stabilizeClassicPlan (stability lock, ≤1500 ms drift)
        ├── beginLoop (4390) ──> phase=LOOP_ARMING ──> armLoop ──> LOOP_RUNNING
        │     └── driveLoopArming / driveLoopMix ──> loopFallBack (5197) ──> loopBail (5124) ──> finish (5575)
        │                                                              ├── disarm ports, closeLater(renderer 1.5s)
        │                                                              ├── filters.open() + parkEchoes()
        │                                                              └── phase=IDLE
        │                                                                            └──> considerSmartTransition (immediate replan 5229)
        │                                                                                   └──> begin (2548) ──> phase=ARMING
        │                                                                                              ├── render = renderFor(plan) (2583)
        │                                                                                              └── into.setPlaybackSpeed(1.0) / setMediaItems / prepare
        └──> begin (2358) ──> phase=ARMING ──> driveArming ──> startFade (2829) ──> phase=FADING
                                     └── driveFade ──> handOff (2893) ──> driveFade done ──> beginTailOrFinish
                                                                                                  ├── TAIL (800/350 ms volume glide)
                                                                                                  └──> finish ──> IDLE
```

Bail edges (not on plan-change): seek cancel `bail(force)` (1090), stall force `bail(force)` (3092), queue edit, skip, error, arm-expired → `bail` → (if `!handedOff`) `finish` else `BAILING` (120 ms volume ramp + `filters.open()` at 5563).

---

## One-line root-cause statement (invariant violation)

**A classic transition render that has written a non-open `targetLowPassHz` to `TransitionFilterProcessor` was torn down (`bail`/`finish`) while both decks were at `volume ≈ 1.0` and `handedOff == false`, violating the invariant that `filters.open()` may only be performed when the outgoing deck is already inaudible (`volume ≤ 0.001`) or the target cutoffs are already open, so the stranded low-pass was reopened audibly in ~30 ms (≈43 ms measured).**

---

## Is the defect specific to Echo→Filter or any plan-to-plan swap?

**Any plan-to-plan swap.** The ingredients are architectural, not rung-specific:

* Any **classic DJ_FILTER SWEEP** (or `MUFFLE`, `BEATLESS_BRIDGE`, `DJ_BLEND` with `vocalOverlap > 0`, `BASS_HANDOFF` with `outgoingTone < OPEN_HZ`) parks the outgoing low-pass below `OPEN_HZ` for the majority of the overlap. `filters.open()` from that state is a broadband spectral reopening.
* Any **native → classic** fallback (`loopFallBack`) or **force bail** (`seek cancel`, `stall`) that tears down while `volume > 0.5` exposes it.

Echo→Filter is the *observed instance* because Echo carries a visible countable countdown (“8→7s”) that makes the subsequent Filter label salient, and because a DJ_FILTER’s early-progress cutoff (~5–7 kHz) happens to match the measured +19 dB. A SWEEP→SWEEP swap (e.g., analysis refines `filterSweep` or `transitionStart` after the sweep has begun) or a MUFFLE→SWEEP swap would produce the same snap, with a larger delta (1500 Hz or 700 Hz floor). A DROP_IN→anything swap (incoming already at full level, `rideDropIn` leaves filters open) would *not* produce it. The defect is therefore **plan-swap abort during an audible filtered render**, not “Echo.”

---

## Recommended fix shape (fits existing architecture; owning layer: `CrossfadeController`)

**Owning layer:** `CrossfadeController` (classic arm/teardown). No new subsystem, no planner change.

Keep the fix inside the existing `begin`/`bail`/`finish` contract and the existing `RetireGuard`/`Phase.TAIL` machinery:

1. **Do not arm a new `begin` while a prior render’s filter is still engaged and audible.** In `begin()` (`2548`), before `render = renderStyle` (`2583`), assert/route through `finish` or `beginTailOrFinish` when `filters.isFiltering` (`TransitionFilterProcessor.kt:132`) is true **and** `outgoing?.volume > 0.001` (the same `RetireGuard.audible` test `finish` already uses at `5615`). If audible, defer the new arm until the existing `driveTail`/`driveBail` has silenced it (the `finish` already does this via `beginTailOrFinish`; the missing piece is *not* overwriting `render`/`outgoing`/`incoming` before that). The cleanest line is to make `begin()` idempotent-safe: if `phase != IDLE`, `bail()`-freeze and return `armDeath` rather than clobbering `render`/`planInFlight`.
2. **Gate the loop→classic fallback on an inaudible spare.** `loopFallBack` (`5197`) already calls `loopBail` → `finish`, which will tail an audible spare. The gap is that `finish`’s `filters.open()` (`5676`) executes *before* the tail silences the spare (the call is unconditional, before the `if (handedOff)` volume reset at `5693`). Move the unconditional `filters.open()`/`parkEchoes()` to *after* the retire guard has diverted to `TAIL` (or make it conditional on `!RetireGuard.audible(spare.volume)`), so the parked cutoff is only reopened once the spare is at `volume ≤ 0.001`. The `bail` path (`5563`) has the same shape: `filters.open()` is called while the spare is still at `bailFromGain`. Defer it to `driveBail` completion or to `finish` after the tail.
3. **(Planner-visible, optional hardening)** In `CountdownStabilizer.stabilizeClassicPlan`, treat a *family* change `LOOP → CLASSIC` while `committed.shown && locked` as `MAX_DRIFT_MS`-bounded (already done for `targetStartMs`); today it only bounds drift within a family, not the transition between families. Keeping the same pin prevents the “Echo 7s → Filter 14s” flip-flop in IDLE.

Do **not** invent a new `PlanLock`/`TransitionArbiter`. The existing `phase` + `RetireGuard.audible` + `CountdownStabilizer` already own the invariant; the fix is to make `bail`/`finish` honor it during a swap (defer `filters.open()` until silence) and to make `begin` refuse to overwrite a live `render`.

---

## Existing test that should have caught it (and why it didn’t)

* **`Djmix26AppTest`** (task 2A): asserts classic plan stability (`stabilizeClassicPlan`, `CountdownStabilizer`, echo/scoop geometry) in IDLE. It never advances a `Render` to `progress > 0` with `isFiltering == true` and then exercises a teardown.
* **`Djmix22AppTest`**: asserts `loopFinishAction` routing (`finishLoop` vs emerge tail) via pure `loopFinishAction(...)` and `RetireGuard`. It models the *native* exit gate, not the *classic* filter-parked open. No test sets `outgoing.volume = 1.0`, `render.filterSweep > 0`, `phase = FADING`, then calls `bail()`/`finish()` and asserts no `filters.open()` while audible.
* **`Djmix18AppHandoffTest`**: asserts handover target math (`handoverTargetSec`, `loopFinishAction`). No filter-state assertions.
* **`Djmix25AppTest:268–271`**: reflects `finish` clears `tailDurationMs`. Asserts tail accounting, not spectral teardown.

Why it slipped: every classic-render test is a *pure planner* test (IDLE `TransitionPlan` → `LoopMix.Plan` / `TransitionPlan` shape), or a *pure routing* test (LoopMix session exit). **No test creates a mid-overlap `Render` with a closed low-pass, holds both deck volumes at 1.0, and then runs the swap teardown (`bail`/`loopFallBack`/`finish`).** The defect lives only in that intersection, which the suite never constructs. A single integration test that arms `begin(..., renderStyle=Render(style=DJ_FILTER, filterSweep=1.0))`, drives `rideStyle(0.1)` to park the outgoing low-pass at ~6.8 kHz, then invokes `finish()` with `handedOff=false, outgoing.volume=1.0` and asserts `targetLowPassHz` stays parked until the spare is silent would have caught it.

---

## File:line index (verified reads)

| File | Lines referenced |
|---|---|
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | 627, 649, 984, 1090, 1129, 1150–1158, 1182, 1324, 1330–1333, 1428, 1464, 1532, 1558, 1749, 1773, 1804, 1956, 1963, 2106, 2213, 2358, 2374, 2548, 2583, 2658, 2766–2794, 2829, 2893, 3021, 3083–3092, 3496–3507, 4390, 4560, 4571, 5197, 5229, 5297, 5408–5432, 5525, 5575, 5676, 5684, 5910, 5980, 6144, 6852, 6866, 6880 |
| `app/src/main/java/com/music/bitchord/playback/TransitionFilterProcessor.kt` | 127 (`open`), 132 (`isFiltering`), 559 (`GLIDE_RATE`), 275–314 (`filter()` glide) |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | 604–605, 628–629, 1985–2022 (filter wiring) |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | 116 (`close`), 200+ (`build`), 545 (`Muffle 20000→2800→700`), 646 (`setFadeOut IN`) |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` | 81 (`setFadeOut` 25 ms), 86 (`close`) |
| `shared/.../TransitionPlanner.kt` | 143 (`TransitionStyle`), 195 (`FilterVariant`), 220 (`incomingPlaybackRate`), 227 (`outgoingPlaybackRate`), 745 (`incomingRateFor`) |
| `shared/.../DjMix.kt` | 133 (`MUFFLE/DROP_IN/BRIDGE` passthrough), 287 (`clashFilter`), 320 (`ensureOverlap`), 357 (`tempoGap`) |
| `shared/.../LoopMix.kt` | 253 (`rung`), 500 (`rampBars`), 966 (`Clock`), 1155 (`Muffle`), 1244 (`Drop`), 1323 (`Beat blend`), 1428 (`Echo`) |
| `shared/.../CountdownStabilizer.kt` | 60 (`committedFor`), 75 (`onRungFailed`), 103 (`consider`), 215 (`stabilizeClassicPlan`) |
| `shared/.../TransitionPolicy.kt` | 99 (`audibleTempoRate`), 121 (`deckSpeed`) |
| `shared/.../TrackAnalysis.kt`, `PhraseConfig.kt` | (echo tail, exit tail constants; not load-bearing for this trace) |

All `CrossfadeController.kt` quotes were read live on `bk-android` (7285 lines). `LoopMixSession.kt` (993 lines) and `LoopMixRenderer.kt` (152 lines) line counts verified with `wc -l`. No file write was performed on `bk-android`; this report was written locally to `/home/hfzhkn/Bitchord/docs/session/BITCHORD_PLAN_SWAP_ABORT_TRACE_20261115.md`.

