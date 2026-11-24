# ADR 0004: Version scheme — upstream base + djmix + engine

## Context
The fork tracks upstream BitChord 1.10 while automix iterates faster than
upstream. Releases must stay installable (`adb install -r`, versionCode
only up, never uninstall) and attributable to a baseline.

## Decision
versionName is `<upstream>-djmix<N>` (AE3 ships `1.10-djmix53`),
versionCode 95, and `bitchordUpstreamBase = "1.10"` maintained by
`sync.sh`. Engine version is independent: `AE 3.0.0`, shown in Settings →
About and stamped into every telemetry record and diagnostics bundle.
`./gradlew bumpDjmix` increments the djmix number and versionCode together
and refuses on a dirty tree.

## Consequences
Rollback cannot go to a lower versionCode, so Safe mode plus flags plus a
fix-forward build is the rollback path (B11.4). Old APKs are kept for
reference.

