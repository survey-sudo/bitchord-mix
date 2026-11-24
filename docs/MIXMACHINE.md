# AE3 MixMachine

`MixMachine` is the transition state machine as a **pure reducer** in `djmix-core`
(`djmix-core/src/commonMain/kotlin/com/music/bitchord/djmix/core/machine/MixMachine.kt`),
per Part B2 and ADR 0005:

```kotlin
fun reduce(state: MixState, event: MixEvent, config: MixMachineConfig = MixMachineConfig.DEFAULT): Pair<MixState, List<MixEffect>>
```

No clocks, no I/O, no Android. Time arrives as events (`Tick(positionMs, isPlaying)`). Every effect the
executor must perform is returned by the reducer; the executor never mutates machine state directly.

## States (11)

Discriminator enum `MixStateKind` (`machine/MixContract.kt`), used by table tests and diagnostics:

| # | State | Payload | Meaning |
|---|---|---|---|
| 1 | `IDLE` | last position, playing, level | no upcoming mix |
| 2 | `PREPARING(progress)` | pair, generation, analysis levels, plan? (frozen early), regions/stems/renderer flags, `usingNoStemVariant()` | work started via `StartPrepare` |
| 3 | `READY(plan)` | frozen `TransitionPlanV1`, armed/commit timing | everything ready; countdown may show |
| 4 | `ARMED(plan)` | frozen plan, `armAtMs` | commit passed; fires on the downbeat |
| 5 | `RUNNING(plan, progress)` | `survivingDeck`, sync-pause flags, node list position | mix playing |
| 6 | `HANDING_OVER` | surviving deck | renderer hands audio back to the player |
| 7 | `DONE` | outcome | transition completed |
| 8 | `MOVED(plan, from→newExit)` | moved-from/new-exit/times, `movedReason` | commit pair not ready → moved to the next eligible phrase boundary |
| 9 | `LAST_RESORT(plan)` | — | no later boundary → musical exit (echo-out / filter-wash) at the last phrase downbeat |
| 10 | `SUSPENDED(reason)` | sticky pause / focus-lost flags | mix paused or held |
| 11 | `FAILED(reason)` | `FailureKind` | unrecoverable path taken; automix stays out of the way |

## Events (20)

`MixEventKind`: `NEXT_KNOWN`, `QUEUE_EDITED`, `ANALYSIS_READY`, `PLAN_FROZEN`, `REGIONS_READY`,
`STEMS_READY`, `RENDERER_PREPARED`, `TICK`, `USER_SEEK`, `USER_SKIP`, `USER_PAUSE`, `USER_RESUME`,
`AUDIO_FOCUS_LOST`, `ROUTE_CHANGED`, `BACKGROUNDED`, `THERMAL_CHANGED`, `RENDERER_ERROR`,
`HANDOVER_COMPLETE`, `LEVEL_CHANGED`, `REMIX_REQUESTED`.

Notable payloads: `AnalysisReady(trackId, level, generation?, usable, refusalReason?)`,
`StemsReady(planId, generation, stemsCompleted, stemsRequired, stemLatencyOutMs?, stemLatencyInMs?)`,
`RendererPrepared(planId, generation, usedNoStemVariant)`, `ThermalChanged(status: ThermalLevel)`.

## Effects

`StartPrepare(pair, generation)`, `CancelPrepare(generation)`, `PrepareRenderer(planId)`,
`Arm(planId, atMs)`, `Start(planId)`, `PauseSync`, `ResumeSync`, `Retire(deck)`, `Handover(planId)`,
`ShowUi(MixUiState)`, `Emit(MixTelemetryV3)`.

## `MixMachineConfig`

File: `machine/MixMachineConfig.kt` — the single source of truth for commit lead, countdown tolerance,
moved-banner duration and remix cutoff:

| Constant | Default | Meaning |
|---|---|---|
| `commitPointBars` | **4** | bars before the frozen exit at which the pair must be READY to arm |
| `minCommitLeadMs` | **6_000 ms** | commit lead is `max(commitPointBars · barDurationMs, minCommitLeadMs)` |
| `countdownToleranceMs` | **50 ms** | a shown countdown may only reach 0 when `Start` fires within ±50 ms; otherwise `mix.countdown.broken` |
| `movedBannerDurationMs` | **3_000 ms** | MOVED chip shows for 3 s, then READY text |
| `remixMinLeadMs` | **20_000 ms** | minimum remaining lead before the frozen exit for "Try another mix" (`MixUiState.canRemix`) |

`MixMachineConfig.DEFAULT` holds the defaults above; the config is injected into `reduce` and
`uiStateFor`, never read from globals.

## Safety invariants (encoded as reducer logic + tests)

These are all asserted by the table test
(`djmix-core/src/commonTest/.../machine/MixCoreMatrixAndContractsTest.kt` — 11 × 20 cells), the property
test (`MixMachinePropertyTest.kt`, 10,000 seed-logged random event sequences), and the replay test
(`MixMachineReplayTest.kt`, recorded on-device event logs run back through the reducer):

1. **Honest countdown.** A countdown is visible only in READY or ARMED (`MixUiState` `require`s it;
   `MixUiProjection` cannot produce seconds-to-mix in PREPARING — progress % only). A shown countdown can
   never reach 0 without RUNNING starting within ±50 ms (`mix.countdown.broken`, target 0).
2. **Prepared-only Start.** `Start` is only emitted after `RendererPrepared` for the same
   `(planId, generation)` — the renderer is prepared off the audio thread during READY, so arming only
   swaps a pointer. Never two RUNNING plans.
3. **Sticky pause.** `UserPause` sets a sticky pause (`USER_REQUEST`): both decks freeze in sync
   (`PauseSync` effect), and it is never auto-resumed — only `UserResume` clears it. While paused or
   focus-paused, ticks don't fire the mix (`canFireAt` gates on `userPaused || focusPaused || !isPlaying`).
4. **Surviving deck.** `Running.survivingDeck` and `HandingOver.survivingDeck` are pinned to
   `DeckTarget.IN` by `require` in `MixContract.kt`: the surviving session deck is never parked or reset
   while the spare retires (`Retire(deck)` only targets the spent deck).
5. **Thermal fallback.** `ThermalChanged(SEVERE or CRITICAL)` applies stem degradation
   (`applyStemDegradation`: `ThermalLevel.requiresStemDegradation`) — backgrounded or severe thermal
   switches to the cheaper no-stem variant of the same recipe, never to silence, and a running mix never
   stops. Heavy prep pauses at SEVERE and resumes at MODERATE (Part B15).
6. **Commit point / MOVED / LAST_RESORT.** At `commitLeadMs` before the frozen exit, the pair must be
   READY to arm; if not, the transition moves to the next eligible phrase boundary (`mix.moved`,
   `MOVED`), and if no later boundary exists, a musical last-resort exit runs
   (`LAST_RESORT(plan)` + `mix.last_resort`, `Commit point missed` ⇒ `LAST_RESORT` on
   `RefusalReason.COMMIT_DEADLINE_MISSED`). The transition never starts "at the playhead".
7. **vc80+ handover invariants.** Seek or skip during ARMED/RUNNING follows the surviving-deck and
   handover rules.
8. **Liveness.** Every PREPARING eventually reaches READY, MOVED, LAST_RESORT or IDLE (on cancel).

## Testing

- **Table test:** every (state, event) pair — 11 × 20 cells, each with an expected outcome.
- **Property test:** 10,000 random event sequences (seed-logged) asserting invariants 1, 2, 4 and 8.
- **Replay test:** recorded on-device event logs (Flag bundles, Part B7) replay through the reducer and
  must produce identical effects.
