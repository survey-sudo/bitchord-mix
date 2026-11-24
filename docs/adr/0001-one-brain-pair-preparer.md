# ADR 0001: One brain — PairPreparer decides, controller executes

## Context
djmix52 planned in two places: PairPreparer and CrossfadeController
(`planLoop` → `straightPreparedFor` → `nativeBridge` → `nativePlanAfterCrown`).
Either could reject or re-plan after the other had frozen a plan.

## Decision
PairPreparer is the only decider of exit, entry, geometry, recipe and stems.
vc91 geometry code becomes candidate generators inside the preparer, scored
together with recipes. The controller only arms and runs what was frozen,
identified by generation id. Handoff qualification moves into candidate
scoring, never build-time rejection.

## Consequences
Planning changes land in one module. The controller stops owning 51
fallback paths. Classic `startFade` runs only when no native renderer
exists, and every use is logged (`mix.classic.reason`).

