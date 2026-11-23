# BitChord vc86 / 2.2.3-djmix44

**Built, signed, audited, and delivered to computer and phone Downloads.** The phone-side size and SHA-256 match the signed artifact. No installation, app launch, app-data change, or listening test was performed.

- Filename: `bitchord-2.2.3-djmix44-automix-arm64-v8a-oldkey.apk`
- Computer: `~/Storage/Downloads/bitchord-2.2.3-djmix44-automix-arm64-v8a-oldkey.apk`
- Phone: `/sdcard/Download/bitchord-2.2.3-djmix44-automix-arm64-v8a-oldkey.apk`
- Package/version: `com.music.bitchord.loopfix`, versionCode `86`, versionName `2.2.3-djmix44`
- ABI: `arm64-v8a`
- Size: **67,568,949 bytes**
- SHA-256: `84c3294096aa511e9246942025a500c008df85e09d3908b55b3ffa1d36b8675d`
- Certificate SHA-256: `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` (existing app key)

## Consolidated source

The base is exact commit `4b60ffe36821354231fe5dd3af0e45b9b198e5c7`, preserving its loop recovery, opportunity dedup reset, duplicate-ID queue-occurrence separation, emitted-grid attribution, and broader terminal-outcome classification. Six source/test files add the reviewed vc85 reliability diagnostics: the first initiating loop-exit cause survives cleanup/fallback, and exact splice-buffer delegate consumption is distinguished from logical takeover. A consumed-then-throw regression prevents subsequent buffer reuse from upgrading unresolved telemetry.

The combined source is a frozen working-tree composite, not a new Git commit. Independent review found no blockers. One bounded nonblocking race can conservatively leave a diagnostic status unknown during arming; it does not change playback. The retire guard and loop-safety/fallback behavior remain. No model swap, effect quotas, song-specific rules, staged vc84 integration, or NPU activation was incorporated. The named song pair and musical/listening improvement remain unverified.

Frozen source archive SHA-256: `008aabb27acd11962751b081cb36a72d9c1d520760c76d9d20aa5e6d868f7141`. The 1,566-file manifest SHA-256 is `2354f3edeea522fe7274259b6aafa09b59683e7fc48f01e338001c14130eb9c1`. Source hashes matched before and after validation and release assembly.

## Validation and release evidence

| Gate | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| Combined focused, five classes | 55 | 0 | 0 | 0 |
| Required JNI | 4 | 0 | 0 | 0 |
| Shared JVM | 536 | 0 | 0 | 0 |
| Ordinary app JVM | 1,453 | 0 | 0 | 5 |

The focused cases overlap the full suite. The five ordinary app skips are one live Genius scrape and the four JNI cases that passed in the dedicated required-JNI run. All validation commands exited zero; retained XML was independently counted. The native/C++ inputs were unchanged across all 60 files, allowing the verified host JNI library and retained standalone native-probe evidence to be reused. Those standalone probes were not rerun.

Unsigned `:app:assembleProdRelease` exited zero. Existing-key CLI signing verified v2 and v3. Package/version, repository APK audit, ZIP integrity, 16 KiB ZIP alignment, and ELF LOAD alignment for all 14 native libraries passed. Both attribution and reliability DEX markers are present. Model hashes are frozen, ONNX Runtime is unchanged from vc85, QNN remains off, and there is no QNN/HTP native payload. Nonfatal R8 Kotlin-metadata and legacy native-packaging warnings remain in the build log.

The computer Downloads copy was published only after hash verification. ADB transferred the APK to a hidden partial path; its size and SHA-256 were verified before renaming, then verified again at the final phone path. Older APKs were preserved.

## Retained records

- Local release root: `~/Bitchord/work/automix-consolidated-vc86-20261119/`
- Source archive, manifest, binary patch, and review scope: release root
- Commands, exits, logs, XML, APK, and release summary: `validation-release/`
- Public evidence manifest: `validation-release/public-evidence.sha256` (316 files verified)
- Final publication/phone verification: `delivery.json`, `delivery-command-log.json`
- Cue root: `/workspace/diagnostics/automix-consolidated-vc86-20261119/`

The worker-stage `artifact.json` and `release-summary.json` preserve their pre-publication state. The parent `delivery.json` records final delivery. Two documented removals reclaimed only reproducible old build intermediates; prior source archives, signed APKs, build outputs/mapping, and validation evidence were preserved.
