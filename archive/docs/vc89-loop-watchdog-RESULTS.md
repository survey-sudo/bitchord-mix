# Loop start scheduling correction — validated candidate

## Result

Implemented the one-condition correction in the isolated `src/` snapshot.
The same regressions fail against untouched vc88 and pass after the correction.
All 70 selected tests pass, with no errors or skips. No release APK was built
or installed, and actual Android sink behavior and audible resolution remain
unverified.

## Field evidence and supported explanation

The installed APK hash matched the delivered vc88 artifact. Fresh telemetry
recorded two crown-Loop attempts with incoming handoff, a runtime error about
two seconds later, sink-flush exit, emergency tail, and stream recovery.
The user's Copy log identifies the immediate trigger in both cases:
`ERROR_CODE_TIMEOUT`, caused by `StuckPlayerException` reporting no player
position progress for 10,000 ms. The incoming positions were 1,684 and 1,662 ms.

Source review identified a scheduling deficit: the incoming player was allowed
to start at mix time 0.5 s, clamped to source zero, while its normal rate-1
timeline was still before the start of the song. Alignment can insert a long
stretch of silence to pay that deficit; the sink deducts inserted duration
from reported source position. The native renderer can still be playing while
that incoming-player clock appears frozen to Media3.

For the rounded captured plan, the old startup deficit is 19.372990177 s.
The corrected start is 19.872990177 s, source zero, with the same 20.3575 s
incoming source coordinate at the final join. Direct device measurements of
the insertion/offset sequence were not available, so this is a source- and
regression-supported upstream explanation, not a newly measured device trace.

## Production change

`app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt:225`

```diff
-            var inStartAt = if (hold > 1e-9 && tBase < 0.0 && handoverTrack >= 2.5) {
+            var inStartAt = if (tBase < 0.0 && handoverTrack >= 2.5) {
```

This extends existing negative-source-start deferral to eligible unheld plans.
It preserves the linear final-join target, native loop clock, 2.5-second runway
policy, latest-start clamp, existing deferred adoption, and all watchdog and
interruption settings. No timeout was lengthened or disabled. Handover source
coordinates below 2.5 s retain existing policy and are outside the universal
coverage claim of this bounded correction.

## Execution evidence

| Phase | Gradle exit | Classes | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| RED: frozen vc88 plus completed regression tests | 1 | 3 | 14 | 6 | 0 | 0 |
| GREEN: one-condition correction plus focused existing tests | 0 | 11 | 70 | 0 | 0 | 0 |

RED is `validation-evidence/red2/`; GREEN is `validation-evidence/green/`.
Their `argv.json`, Gradle logs, exit files, summary JSON, and raw JUnit XML are
retained. The three regression test files and their testcase identities were
identical across RED/GREEN; the production hash is the expected corrected hash.

The first baseline invocation completed the same 14 tests with six assertion
failures, but its Gradle client became terminal-job-control suspended. Those
outputs are preserved separately under `red-job-control/` and are not used as
the authoritative command-exit result. The authoritative rerun detached stdin
and process session, forced only test-task execution, reused compilation, and
returned the expected exit 1. GREEN used the same detached execution path.

### GREEN classes

| Class | Tests |
| --- | ---: |
| Djmix19AppTest | 5 |
| Djmix23AppTest | 7 |
| Djmix26AppTest | 12 |
| FadingStallWatchdogTest | 6 |
| LoopHandoverCleanupTest | 14 |
| LoopGeneratedTimestampTest | 7 |
| LoopSinkClockTest | 1 |
| LoopIncomingStartDetectorTest | 3 |
| LoopIncomingStartWatchdogTest | 5 |
| LoopMixPortClockTest | 4 |
| LoopSourceGeometryTest | 6 |

The detector tests use the actual Media3 class with modeled player positions
and explicit one-second listener events. The legacy-debt scenario triggers;
eligible corrected schedules do not; a genuinely frozen player still triggers
at 10,000 ms. They do not emulate Android Handler dispatch or execute the real
PrecisionAudioSink/delegate in those detector scenarios. Existing sink, clock,
cleanup, deferred-adoption, and timestamp tests pass independently.

## Scope and integrity

- Baseline: exact frozen vc88 archive, all 1,576 source files verified.
- Production scope: one changed condition in one file.
- Tests: two new files; one existing source-geometry assertion/comment updated
  to require the final-join invariant rather than pinning the old early start.
- 1,574 baseline files remain byte-identical; no baseline files were removed.
- No package, version, resources, signing, dependency, renderer, watchdog,
  release harness, or existing release/worktree modifications.
- `SOURCE_SCOPE.json` records the file hashes; `FINAL_VALIDATION.json` records
  results and evidence verification. All 41 captured evidence files passed
  local size/SHA-256 verification, and raw XML counts were rechecked locally.
- Evidence archive SHA-256:
  `cfedff2919bf34b934d621d36e5e38e1fa0b8d82dc3d44d35203557c19e564e4`.
- Full review patch: `LOOP_START_CORRECTION.patch`.

## Remaining boundary

These results establish the scheduling correction and the modeled watchdog
regression. They do not establish a signed release, actual corrected-device
playback, listening quality, or universal resolution of every Loop interruption.
A corrected release would need the separate release/provenance process; device
installation and playback testing are outside the current read-only device
authorization.
