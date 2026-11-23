# APK comparison: 4b60ffe vc83 and reliability vc85

## Conclusion

These are divergent diagnostic variants of the same loop-recovery/mixing code, not successive DSP upgrades. The higher vc85 version metadata does not make it a superset of the 4b60ffe build. Neither APK contains both sets of follow-up diagnostics. No listening A/B was performed.

For a consolidated next build, use committed `4b60ffe` as the source baseline and carry the independently reviewed abort/delegate observations onto it, then validate the combined source and assign fresh update metadata. That integration was not performed as part of this comparison.

## Artifact identities

| | 4b60ffe APK | Reliability APK |
| --- | --- | --- |
| Source | Commit `4b60ffe36821354231fe5dd3af0e45b9b198e5c7` | Frozen v4 plus reviewed six-file overlay; not a Git commit |
| Metadata | vc83 / `2.2.3-djmix41` | vc85 / `2.2.3-djmix43` |
| Package | `com.music.bitchord.loopfix` | Same |
| Signing certificate | Existing key, SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` | Same |
| Size | 67,568,949 bytes | 67,568,949 bytes |
| APK SHA-256 | `e693554e8d63ecad5931fdd3a2c903dc893ba92b566c8a989e4b6c1e2d0050d4` | `7e78c52e9045ffa0950b21467ab4ac672284a780c4afeb632022c002b6510119` |
| Source manifest | `work/loop-character-apk-4b60ffe-20261119/source-manifest.json` | `work/automix-council-review-2026-11-19/reliability-final-r3/composite-source-inputs.sha256` |

Both files were read and hashed locally. The supplied vc83 file matches its retained signed-artifact record. Both have recorded successful v2/v3 signature, repository payload, and 16 KiB alignment audits.

## What matches

- Shared `LoopMix.kt` and `TransitionPlanner.kt` hashes are identical. Both contain locally qualified crown admission and the same nominal execution limits and lower-rung policy.
- Native renderer, stretch, JNI, filters, and model source inputs match. The two ONNX assets inside the actual APKs are byte-identical.
- Both APKs have the same 1,595 regular ZIP entry paths. Resources and other ordinary assets match; this is not a UI or model replacement.
- Seven custom native library files have different whole-file hashes, but their inspected ELF sections differ only in `.note.gnu.build-id`; executable `.text` and constants `.rodata` match. The other seven native libraries match in full. The changed file hashes are not evidence of a DSP algorithm change.

`LoopMixSession.kt` differs because 4b60ffe carries metadata identifying which branch generated the actual knots. Inspecting its diff found no changed knot coordinate calculations or audio envelopes.

## What is unique to each

### 4b60ffe

The commit follows `170328b → 689285f → 4b60ffe` and adds independently reviewed attribution corrections:

- Resets outer event deduplication guards for a new opportunity, preventing valid new-attempt events being suppressed.
- Expires an incomplete, unexecuted IDLE telemetry opportunity at natural auto-advance/repeat boundaries, including repeated media IDs. It does not change playback state.
- Separates planned anchor/grid evidence from immutable actual emitted-knot source fields in a built session (`tracked`, `clock`, `native-clock`, or unknown).
- Correctly classifies normal native/classic terminal reasons rather than recognizing only the generic `finish` case.

Its recorded follow-up validation is 33 focused tests, four required-host-JNI passes, 536 shared tests, and 1,446 app tests (five ordinary skips), with no reported failures/errors. Those populations differ from the reliability lane; counts alone do not establish better listening quality.

### vc85 reliability variant

- Retains `initiatingExitReason` separately from a final `emergency-retire-guard` reason.
- Observes consumption of the exact takeover splice buffer separately from the existing logical `tookOver` flag, with arm-generation tagging and stale-publication rejection.
- Captures the native attempt's observation before cleanup, preserves nonterminal fallback classification through a guard tail, and avoids assigning an old native build generation to a later classic completion.

Its retained validation is 27 focused tests plus the integrated 536 shared / 1,440 app population, zero failures/errors and four ordinary app skips. The focused population is included in the app population. The independent source/evidence reviews accepted this isolated revision. A nonblocking consume-then-throw/retry diagnostic edge is disclosed in the reliability validation report.

The vc85 variant does not contain the later 4b60ffe opportunity/grid-source/terminal-reporting corrections. The 4b60ffe APK lacks the vc85 abort-cause and splice-buffer diagnostic fields.

## Actual APK payload comparison

All three DEX entries differ, and the packaged diagnostic markers agree with the claimed distinction:

- vc83/4b60ffe contains `media-occurrence-ended`, `plannedOutGridSource`, and `natural-auto-advance`; it lacks `initiatingExitReason` and `takeoverSpliceBufferDelegateStatus`.
- vc85 contains `initiatingExitReason`, `takeoverSpliceBufferDelegateStatus`, and `DELEGATE_FULLY_CONSUMED`.

The other differing ZIP entries are the Android manifest, signature-related META-INF entries, DEX optimization profiles, and the seven native build-ID notes discussed above. Identical total file size is coincidental; the APKs are not duplicates.

## Practical interpretation

There is no source-backed reason to expect one to sound better on the same material: the mixing policy, DSP code, and model assets match. That is not a proof of audible equivalence; neither received a paired listening/capture comparison here.

The 4b60ffe build is the cleaner committed baseline for subsequent integration and has the newer opportunity/grid-source bookkeeping. The vc85 build is more useful for investigating the original guard/buffer-consumption question. Use neither version number nor test count as a listening-quality score.

Both retain the same package and signing key; the vc83 file's metadata remains 83. If selecting a future combined update, assign a fresh version rather than presenting it as identical to either tested source.

The earlier wireless transfer ended with an ADB EOF. The destination copy has not been verified, and the phone is currently absent from ADB discovery. No APK was installed or app data cleared during this comparison.
