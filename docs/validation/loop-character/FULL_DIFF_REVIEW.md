# Independent full-diff review

The user requested a complete independent review using
`xingchiapi/gpt-6.1-sol#max` after the default review model exhausted its quota.
The reviewer read all 1,380 production diff lines against
`170328bed4b78eaa68e7fce739ba8b121e4aa38c`, the associated lifecycle code, focused
tests, and retained v10 validation evidence. This completed review is distinct
from the earlier partial terminal-outcome review.

The reviewed implementation is committed as
`689285faea7d1e015e74d833ea3feb0ff10a60c6`.

## Findings

| Severity | Finding | Required correction |
|---|---|---|
| P2 | Legacy outer event-dedup guards survive a new opportunity and can suppress its evidence before attempt-scoped dedup runs. | Reset or scope event-only outer guards when creating an opportunity; test the guarded callers across two opportunities. |
| P3 | Pending, unexecuted duplicate-ID queue occurrences can share an attempt through natural IDLE auto-advance. | End the previous incomplete telemetry opportunity at the actual item boundary while retaining identity through internal retries and fallback. |
| P3 | Prepared/executed grid-source fields are derived from planner anchors rather than actual session-knot generator branches. | Separate planned anchor evidence from actual emitted-knot source metadata; carry actual source choices in the built session. |

The reviewer found no additional issue in nominal reciprocal rates, ramp budget,
bounded clock allocation, whole-loop release math, actual-knot validation before
decode/JNI, repeat boundaries, initial holds, native-rate settle, local vetoes,
crown/bridge and committed-plan precedence, captured analysis/lyrics, stale build
guards, configured loop flags, or the terminal-outcome fix.

## Follow-up scope

The three findings have been repaired in a separate attribution-only change.
The same independent reviewer read all 647 lines of the frozen follow-up diff,
the current source and all nine new tests, and closed all three findings with
no additional issues. Scope is two production files and three test files.
Loop admission policy, source-coordinate arrays, clock generation, DSP,
readiness, decoding, deadlines and physical PCM safeguards are unchanged.

The frozen repair snapshot is based on `689285f`; source archive SHA-256:
`84c123b3fe00d36bc2c0c6d4ab9430b8c2aae4f5f9945a39ffe574714a0a36cb`.
It is staged on cue at
`/workspace/diagnostics/automix-loop-character-20261119/attribution-84c123b3`.
The 647-line follow-up diff SHA-256 is
`728085bf293d57e88f52b9214e4ed3168b497388ba02922192186c1ab4a06de8`.

- Outer event guards now reset when a new opportunity starts, including the
  phrase event guards; same-attempt retries still deduplicate.
- Natural AUTO/REPEAT callbacks end only IDLE telemetry opportunities, with one
  unknown terminal under the original pair identity. Active attempts, planning
  and build state are preserved.
- Planner source evidence uses `plannedOutGridSource` and `plannedInGridSource`.
  Actual source fields use immutable values from the emitted-knot branches:
  `unknown`, `tracked`, `clock` or `native-clock`. Manual sessions stay unknown.

The focused cue phase exercises the new attribution regressions and existing
controller, knot and source-geometry tests. A required-host-JNI phase verifies
the built session's immutable metadata alongside actual rendering. A final
ordinary shared/app invocation validates the reconciled source with native
options unset. Each phase copies its own XML immediately before another Gradle
invocation can overwrite reports.

All three runtime phases passed on the exact frozen repair snapshot:

| Phase | Result |
|---|---|
| Focused controller, knot and source geometry | 33 tests, zero failures/errors/skips |
| Required-host-JNI session | Four tests, zero failures/errors/skips |
| Final ordinary shared JVM | 536 tests, zero failures/errors/skips |
| Final ordinary app unit | 1,446 tests, zero failures/errors; five skips |

The five ordinary skips include the four dedicated host-JNI methods; these
methods passed with no skips in their separate required-native invocation.
The original renderer/JNI sources are unchanged, so the existing native probe
results remain applicable. No probe thresholds or expected results changed.

Exact commands, source hashes, phase exits, test totals and report-manifest
hashes are recorded in `attribution-followup-result.json`. Each phase's copied
XML and logs were independently checked against its result manifest before
recording this receipt. The v10 results remain historical evidence for the
initial recovery commit; the follow-up receipt validates this repair.

## Remaining limits

No APK, installation, merge, model swap or NPU activation is part of this review
follow-up. Real Android controller/sink takeover, delegate-buffer acceptance,
named-recording provenance and musical listening remain unverified.
