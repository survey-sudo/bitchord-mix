# AutoMix reliability observation: implementation and validation

**Status:** Implemented and independently reviewed in the isolated `automix/reliability-observation-20261119` lane. The final code review accepted the patch with zero blocking findings; the evidence audit is closed. The final focused and integrated cue gates both exited `0`. No commit, merge, APK, signing, install, playback, or listening acceptance is implied.

**Subsequent APK delivery:** At the user's explicit request, the reviewed code was packaged as vc85 / `2.2.3-djmix43`, signed with the existing app key, audited, and copied to Downloads. See [the APK release record](APK_RELEASE_VC85_2026-11-19.md). This does not add phone-installation or listening evidence to the source results below.

## Source and ownership

- Repository base: vc83 `170328bed4b78eaa68e7fce739ba8b121e4aa38c`.
- Local lane: `~/Bitchord/work/bitchord-mix/.slim/worktrees/automix-reliability-observation`.
- Inherited loop-recovery source: frozen v4 archive SHA-256 `64af26359edce7c6657d7df38f227b22fab649f97635c73184f0018bcab2f933`.
- Tested cue source: `/workspace/diagnostics/automix-loop-character-20261119/reliability-observation-v10/src`.
- Final evidence: the sibling `focused-r3/`, `integrated-final-r3/`, and `overlay-files.sha256` paths on cue. Copied locally to `~/Bitchord/work/automix-council-review-2026-11-19/reliability-final-r3/`.
- The active loop-character writer lane and full vc84 candidate were not edited. They may continue independently. This lane is not a merge of their later changes.

**The tested source is not vc83 Git HEAD.** It is an uncommitted composite of the frozen v4 source archive plus the six-file overlay. Checking out `170328b` alone cannot reproduce these results. The v4 archive includes inherited loop recovery and tests; the suite totals below cover that composite, not just the reliability delta.

Source comparison against the immutable v4 archive found exactly six changed source/test files, with no other additions under `app/src`, `shared/src`, `sharedUi/src`, or `native`. The overlay hashes match the retained manifest and were independently rechecked against the current worktree during the evidence audit. That check and `git diff --check` exit `0` are saved in `final-review-source-check.txt`. The shared loop policy, session knot code, renderer, and JNI sources remain those of v4.

The complete reproduction inputs now live in the local evidence directory, independently of cue or temporary storage:

| Retained input | SHA-256 |
| --- | --- |
| `base-v4-source.tar` | `64af26359edce7c6657d7df38f227b22fab649f97635c73184f0018bcab2f933` |
| `reliability-overlay.tar.gz` (exactly six regular files) | `3f05d4fd283347e7cf7937a6bfdd63d206be60acc814e194fae0c7d07034fa1d` |
| `overlay-files.sha256` | `d33864cf0139129e20bdeb9e034fc47461dd37c1e70531e6b2cf7233a300503e` |
| `composite-source-inputs.sha256` (1,566 regular snapshot files, including docs/assets) | `976686abb3ee504dddecf73a6fcd0e63215716f6ffaa75692241f6e7110c3c6d` |

`reproduction-identity.json` records the archive composition, current source check, and the larger change inventory relative to Git HEAD. Restore the base tar, then overlay the six files; check the resulting inputs against the composite manifest before provisioning the documented cue environment. Build outputs and dependency caches are not part of this source identity.

All current code matches the tested composite. Two local worktree documents have later edits relative to that composite: `docs/LOOP_CHARACTER_RECOVERY_2026-11-19.md` and `docs/validation/loop-character/verification-execution.md`. They are not compiled/test inputs; archive restoration uses their original bytes. Their separate hashes and the untracked documentation inventory are disclosed in `reproduction-identity.json`; the Git change inventory below is source-only.

## Changes

1. **Keep the initiating loop-exit cause.** `CrossfadeController.loopBail()` retains its first cause separately from `lastCompleteReason`. Guard and completion events can therefore report `initiatingExitReason=paused` alongside `reason=emergency-retire-guard`. The observation is a detected state/exit cause, not attribution of a pause command to a human or application caller.
2. **Keep fallback attribution coherent.** Internal native cleanup remains nonterminal through a deferred guard tail. The completion event is written before clearing native-attempt status and binding. A later classic execution keeps the opportunity identity and cause but does not inherit the old native buffer status or native build generation. A new native arm or opportunity resets the appropriate evidence.
3. **Distinguish splice from delegate consumption.** The existing `tookOver` marker keeps its original meaning. A separate, generation-tagged status tracks only the exact takeover splice buffer: `OFFERED_TO_DELEGATE`, `DELEGATE_FULLY_CONSUMED`, or `UNRESOLVED`. An unobserved status is `null`. Full consumption requires both a successful delegate return and no bytes remaining in that buffer. Partial/failed writes stay unresolved; stale-generation publications cannot revive old state.
4. **Read the signal before cleanup discards it.** Controller-side guard/completion telemetry captures the port status before disarming the session. The PCM callback publishes only numeric/enum state: it does not append logs or acquire a new lock.
5. **Complete the inherited telemetry binding.** The controller copy included coarse-decline metadata beyond the original v4 file. Its binding, call sites and reflection-based harness calls now use the same captured parameter and correct function signatures. This changes evidence fields, not the loop eligibility policy.

Modified files relative to frozen v4:

- `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt`
- `app/src/main/java/com/music/bitchord/playback/audio/PrecisionAudioSink.kt`
- `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixPort.kt`
- `app/src/test/java/com/music/bitchord/playback/LoopCrownControllerRecoveryTest.kt`
- `app/src/test/java/com/music/bitchord/playback/LoopHandoverCleanupTest.kt`
- `app/src/test/java/com/music/bitchord/playback/audio/LoopGeneratedTimestampTest.kt`

The retire guard, fade duration, PCM buffer retry behavior, loop-admission policy, FX palette, tempo bounds, models, and NPU flags were not deliberately changed by this reliability slice. It improves observation and nonterminal/terminal reporting; it does not diagnose or repair the eight unexplained archived loop exits.

## Verification and scope

Both runs used cue's existing Android/JDK environment, offline dependencies, two workers, no host-native JVM flags, and the same final source hashes.

| Gate | Result | Evidence scope |
| --- | --- | --- |
| Focused controller/cleanup/sink tests | Exit `0`; 27 tests, 0 failures, 0 errors, 0 skips | Production controller methods and sink logic with controlled player/renderer/delegate harnesses. |
| Shared JVM suite | Integrated exit `0`; 536 tests, 0 failures, 0 errors, 0 skips | Shared analysis/planning contracts. |
| App JVM suite | Integrated exit `0`; 1,440 tests, 0 failures, 0 errors, 4 skips | App/controller/sink regressions in the ordinary JVM environment. |
| Source identity | Current six file hashes match the retained manifest; the evidence-audit check and `git diff --check` exit `0` are retained | Evidence remains tied to the final source, not a later dirty tree. |

The focused totals are nine controller-recovery tests, fourteen handover-cleanup tests, and four generated-timestamp/backpressure tests. Coverage includes the same-buffer partial retry, eventual delegate consumption, unresolved writes when no bytes are accepted, stale-generation reset, pause-to-guard cause retention, nonterminal fallback through a tail, and classic fallback without native generation/status misattribution.

The 27 focused tests are a subset of the 1,440 app tests, not an additional population. The integrated invocation reused the focused test compilation (`compileProdDebugUnitTestKotlin UP-TO-DATE`) and executed the app tests again with the full suite. Its XML is retained separately from the focused run.

The four ordinary app skips are the live Genius scrape and the three host-native-only session tests. The XML records skips without their reasons; the reasons are established from `app/src/test/java/com/music/bitchord/GeniusTest.kt` (`LIVE_TESTS` network gate) and `app/src/test/java/com/music/bitchord/playback/loopmix/LoopCrownNativeSessionTest.kt:24–28` (dedicated native-required JVM gate). Those three native methods were **not executed** for this reliability slice. Existing v4 host-JNI and identical-native-source probe evidence remains a separate lower-level mechanics record, not proof that this new controller/sink instrumentation ran on Android hardware.

Focused invocation, after `source /etc/profile.d/android-dev.sh` (formatted and shell-quoted for copying; retained `command.txt` logs the same arguments without wildcard quotes):

```sh
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
  ./gradlew :app:testProdDebugUnitTest \
  --tests '*LoopCrownControllerRecoveryTest' \
  --tests '*LoopHandoverCleanupTest' \
  --tests '*LoopGeneratedTimestampTest' \
  --offline -Pbitchord.bundleWear=false --max-workers=2 --no-daemon --stacktrace
```

Final integrated invocation:

```sh
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
  ./gradlew :shared:jvmTest :app:testProdDebugUnitTest \
  --offline -Pbitchord.bundleWear=false --max-workers=2 --no-daemon --stacktrace
```

## Failed and superseded runs

The parent previously reported a 23-test success in `reliability-observation-v9`, then found that the intended transfer had timed out and the source was unmodified v4. That account is retained for context, but the v9 artifacts are not included in this evidence package and were not independently audited here. It **does not validate this patch** and contributes nothing to the final passing totals.

The first actual overlaid v10 run stopped at compilation because `loopPlanFor` referenced `analysisState` instead of its `state` parameter. The next run compiled and passed sink/cleanup tests but four reflection-based controller tests failed because the added telemetry argument changed their private helper signatures. The variable reference and harness argument lists were corrected without weakening assertions. `focused/` and `focused-r2/` retain logs and exit files only, not full command/XML provenance. Only the complete final `focused-r3/` and `integrated-final-r3/` outputs support the passing result above.

## Change inventory relative to vc83 HEAD

The six-file reliability delta above is relative to v4, not Git HEAD. Against vc83 HEAD, the composite also inherits these unchanged v4 source inputs:

- `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt`
- `app/src/test/java/com/music/bitchord/playback/loopmix/LoopCrownKnotQualificationTest.kt`
- `app/src/test/java/com/music/bitchord/playback/loopmix/LoopCrownNativeSessionTest.kt`
- `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/LoopCrownQualificationTest.kt`
- `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/LoopEligibilityRecoveryTest.kt`
- `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/LoopRoutingReplayTest.kt`

Some of the six overlay files already differed from HEAD in v4, and some inherited test files are untracked in the local worktree. Preserve the archive composition rather than treating `git diff HEAD` alone as the patch. `reproduction-identity.json` lists tracked and untracked source changes separately.

## Remaining boundary

`DELEGATE_FULLY_CONSUMED` means this delegate reported consuming that buffer; it does **not** establish AudioTrack presentation, route latency, beat phase, gap-free output, or audibility. A real-device reliability session still needs exact build/source/rendition identity, command provenance, captured output, and a capture-chain control. The original guard/pause causes and musical preference remain open. No phone state or queue was changed during this work.

## Independent final review

The independent source reviewer accepted this exact revision with no blocking P0/P1 finding. The separate evidence auditor verified the digests, archive composition, raw exits and XML results; its provenance/documentation findings have been addressed. The retained [independent review record](../work/automix-council-review-2026-11-19/reliability-final-r3/INDEPENDENT_REVIEW.md) records scope and limitations.

One nonblocking diagnostic edge is recorded: if a delegate exhausts the splice buffer and then throws, and execution retries without flush/reset, a later generated buffer can be mistaken for the splice's consumption. This sequence was not established for the production delegate. It is not included in test-coverage claims, and no code change was made for it in this accepted revision. A future hardening change needs a consume-then-throw/retry regression test and refreshed affected verification.
