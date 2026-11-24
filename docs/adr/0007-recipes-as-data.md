# ADR 0007: Recipes as data — JSON v2 with interpreter and linter

## Context
The 20 djmix52 recipes live as hardcoded data in `Recipes.kt`. Fixed only
on the rare v2 path, nobody had listened to a rendered recipe, and adding
one meant touching Kotlin plus the renderer call sites.

## Decision
Each recipe becomes a bundled `recipes/*.json` document (schema recipe
v2: requirements, length options, placement, steps, no-stem variant,
abruptness). `djmix-core/recipes` interprets a recipe plus pair into a
`TransitionScript`; ChoreoGuard validates. A linter checks schema,
anchors, splice neutrality, bass doubling, FX budget and coverage.
Adding a recipe touches `recipes/*.json` plus its audition only.

## Consequences
Six recipes ship at release (C8, C9, F9, F10, F11, S6). Recipe Lab
previews in debug builds with live reload. Every shipped recipe needs an
audition render and an Inspector one-liner.

