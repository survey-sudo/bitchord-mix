# Frozen-snapshot execution plan

## Ownership and limits

The parent owned remote cue execution and interpretation. Child writers performed
scoped static checks; the snapshot worker owns immutable local packaging. The
final candidate pass is complete on frozen v10; no production/test edits were
made after the final integrated run. The earlier vc83 baseline-red evidence
remains valid for its frozen inputs.

The user authorized a commit after validation. No source/toolchain/dependency
downloads, APK tasks, merges or device operations. An offline cache or missing
native/toolchain failure is blocked
evidence, not permission to provision or retry without approval. Use unique
directories under `/workspace/diagnostics/automix-loop-character-20261119/`;
never run these commands in cue's dirty default checkout.

The project-specific ordinary gate is the shared JVM and prodDebug app unit
suite (`.github/workflows/android-ci.yml:104-108`). That CI file does not prescribe
a UI, lint or connected-device gate for this slice. The scoped loop renderer
gate uses three separate modes (`android-ci.yml:66-74`). Do not execute unrelated
analysis or release jobs just to imitate all of CI.

## Candidate focused pass

Focused checks on the final production source passed: shared three-class filter
for `LoopEligibilityRecoveryTest`, `LoopCrownQualificationTest`, and
`LoopRoutingReplayTest`; app filter for `LoopCrownControllerRecoveryTest` and
`LoopCrownKnotQualificationTest`. The isolated 10-test knot test was rerun after
correcting its synthetic tracked-beat fixture. Exact full commands, logs and
reports are in the cue candidate evidence root referenced above.

Keep `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS` unset for ordinary
JVM checks. Host JNI must not be discoverable through default library paths.
`DeclineOverlapRenderTest.kt:747` explicitly requires native unavailability.

## Host native artifact and checks

From the same frozen source, create a unique `hostnative` directory in its cue
evidence root, then build the existing host library:

```sh
g++ -std=c++17 -O2 -fPIC -shared \
  -I native -I native/third_party \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  native/loopmix/loop_mix_renderer.cpp app/src/main/cpp/jni/loopmix_jni.cpp \
  -o "$EVIDENCE/hostnative/libbitchord_loopmix.so"
```

Record compiler identity, full command/exit and source/library digests. The host
artifact uses the unchanged JNI/renderer, not a test substitute. It is not an
Android APK or an HTP/NPU build; `-O2` matches the existing host probe convention.

Build the existing standalone probe once:

```sh
g++ -std=c++17 -O2 -I native -I native/third_party \
  native/loopmix/loop_mix_renderer.cpp native/loopmix/loop_mix_renderer_ab_main.cpp \
  -o "$EVIDENCE/hostnative/loop_mix_renderer_ab_main"
"$EVIDENCE/hostnative/loop_mix_renderer_ab_main" --selftest
"$EVIDENCE/hostnative/loop_mix_renderer_ab_main" --reverb-stop-probe
"$EVIDENCE/hostnative/loop_mix_renderer_ab_main" --loop-domain-probe
```

Each mode has its own raw log and exit. Retain the six-render selftest results,
reverb-stop preservation/drop and sensitivity-control numbers, and 63 finite-loop
pairs (54 exact-ramp, nine unity), RMS/reference/unity-correlation/post-fade data.
The deliberately failing sensitivity mutation is not a failing whole probe.

Run only the inspected real-native session test in a separate, fresh JVM with
inline `JAVA_TOOL_OPTIONS`. The routing replay is a shared JVM test and must be
run through `:shared:jvmTest`, not the app task. No native lifecycle/controller
test class was added; the sink takeover path remains explicitly unobserved.

```sh
JAVA_TOOL_OPTIONS="-Dbitchord.loopmix.hostNativeRequired=true -Djava.library.path=$EVIDENCE/hostnative" \
  ./gradlew :app:testProdDebugUnitTest \
  --tests '*LoopCrownNativeSessionTest' \
  --offline -Pbitchord.bundleWear=false --max-workers=2 --no-daemon \
  --rerun-tasks --stacktrace
```

`--rerun-tasks` prevents the external JVM property change from reusing a prior
property-absent test result. Required native methods must load/build/render or
fail; ordinary-suite property-absent skips are not dedicated native evidence.
Never run the decline suite in this JVM.

## Comparable replay

Freeze the new routing test and literal input manifest before either replay.
Overlay those identical test-only bytes on unmodified vc83 and on the candidate;
retain distinct source snapshot digests. Use the same unchanged native artifact
only after confirming identical renderer/JNI source hashes. The baseline and
candidate commands are:

```sh
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
  ./gradlew :shared:jvmTest --tests '*LoopRoutingReplayTest' --offline \
  -Pbitchord.bundleWear=false --max-workers=2 --no-daemon --stacktrace
```

The replay records planner selection, pure cold-yield decisions and real
session/renderer preparation/output where exercised. These are not controller
acceptance, sink takeover, delegate-buffer acceptance or listening. Such stages
remain unknown when not exercised. Keep stage reaches and one terminal outcome
per replay scenario separate; synthetic corpus counts are not queue prevalence.

## Final ordinary integrated pass

After focused/native evidence was green, one ordinary integrated invocation was
run on the final v10 source digest with native JVM options unset. The v10 integrated
run completed successfully:

```sh
./gradlew :shared:jvmTest :app:testProdDebugUnitTest --offline \
  -Pbitchord.bundleWear=false --max-workers=2 --no-daemon --stacktrace
```

Its exact command, environment, exit, raw log and XML reports are retained under
`validation/loop-character/candidate-final-v10/` and the cue candidate evidence
root. Parsed totals are shared 536/0/0/0 and app 1437/0/0/4
(tests/failures/errors/skips). No release or named-pair listening claim follows
from these checks.
