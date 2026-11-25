# ADR 0012 — Shared production geometry, not a second audition materializer
Status: accepted; extraction follows the current frozen R2 review and validation gates.
Context: headless audition duplicates geometry, while recompiling app/shared sources creates conflicting ownership.
Decision: use existing `:shared` as the temporary pure production geometry owner; add only `:shared → :djmix-core`.
Move existing knots, incoming/coverage/gain/startup and canonical projections without changing formulas or guards.
Promote session-owned types and update imports; no nested-type aliases, shadow sessions/decoders or duplicate FQNs.
Retain app platform/lifecycle facades, explicit shared config inputs and the sole core `RecipeInterpreter` compiler.
New-pair parity also requires sharing existing concrete-plan construction and preparation retiming, not a V1 adapter.
Consume shared artifacts, retain full production analysis evidence, and remove external source recompilation.
Until parity gates pass, accept only matching playback-produced frozen inputs and explicitly limit audition coverage.
