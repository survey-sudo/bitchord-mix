# ADR 0002: Prepared-only execution — countdown only when READY or ARMED

## Context
djmix52 showed the countdown as soon as a plan existed, then fired a
+1.2 s watchdog (`checkShownWatchdog`) and `straightRescue` starting at
`positionMs + 0.45`. The bar hit zero with nothing running.

## Decision
The countdown appears only in READY/ARMED. At the commit point (4 bars
before the frozen start, min 6 s) the pair must be READY; otherwise the
transition MOVED to the next eligible phrase, or as last resort becomes a
musical exit on the last downbeat. No decode, inference or allocation on
the audio thread. `mix.countdown.broken` target is 0.

## Consequences
Needs the READY/ARMED/MOVED/LATE state machine (ADR 0005). A shown
countdown can no longer reach 0 without RUNNING within ±50 ms.

