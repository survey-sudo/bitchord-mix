# ADR 0005: MixMachine — transition state as a pure reducer

## Context
CrossfadeController.kt is 9,835 lines with 255 functions and 178 mutable
fields. Every transition decision, fallback, countdown, watchdog and
handover lives in one class whose state cannot be enumerated.

## Decision
Add `fun reduce(state, event): Pair<State, List<Effect>>` in
`djmix-core`, with no clocks and no I/O; time arrives as events. States
(IDLE → PREPARING → READY → ARMED → RUNNING → HANDING_OVER → DONE, plus
MOVED, LAST_RESORT, SUSPENDED, FAILED) own the commit-point, countdown-
visibility, seek, pause, focus, route and thermal rules. MixExecutor
drives players and renderer from the effects.

## Consequences
Table (11×20), 10k-sequence property, and replay tests replace scattered
fallbacks with reducer rules. `CrossfadeController` shrinks to ≤2,500
lines; the last-resort/classic path is the single fallback entry point.

