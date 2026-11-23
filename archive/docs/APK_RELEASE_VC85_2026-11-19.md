# BitChord vc85 / 2.2.3-djmix43 APK

**Built, signed, audited, and delivered to Downloads.** Phone installation and listening have not been performed.

- File: `~/Storage/Downloads/bitchord-2.2.3-djmix43-automix-arm64-v8a-oldkey.apk`
- Package: `com.music.bitchord.loopfix`
- Version: `2.2.3-djmix43`, versionCode `85`
- ABI: `arm64-v8a`
- Size: **67,568,949 bytes**
- SHA-256: `7e78c52e9045ffa0950b21467ab4ac672284a780c4afeb632022c002b6510119`
- Signing certificate SHA-256: `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` (existing app key)

## Contents

This APK packages the independently reviewed v4 loop-character recovery plus the six-file reliability overlay. It restores admission of locally qualified two-sided loops without weakening the local vocal, physical-PCM, ramp/rate, release, and handover requirements. It also retains initiating loop-exit causes through guard/fallback telemetry and distinguishes logical splice takeover from delegate buffer consumption.

The reviewed production code was not changed for packaging. Release-only edits set the root version and the APK audit expectations to vc85/djmix43. The full staged vc84 sound-polish/NPU integration and independently advancing writer lane were not incorporated. NPU flags remain off. Version 84 remains reserved for that separate staged lineage.

## Release checks

- Gradle `:app:assembleProdRelease`: **exit 0**, `BUILD SUCCESSFUL`.
- Built unsigned in an isolated cue source directory, then signed with `apksigner` on cue. Signing credentials were not copied off the host.
- `apksigner verify`: **v2 and v3 verified**, certificate matched the existing app key.
- Package, versionCode and versionName matched the intended update.
- `tools/audit-apk.sh`: **AUDIT PASSED**; seven custom native libraries, one expected ONNX Runtime library, no QNN/HTP native payload.
- ZIP alignment: **16 KiB check passed**.
- ELF alignment: **all 14 packaged native libraries passed** the 16 KiB load-segment checks.
- Release DEX contains the reliability telemetry keys/status.
- Reviewed overlay hashes matched before and after the release build.
- Downloaded APK and the final Downloads copy both matched the recorded size and SHA-256.

Prior source validation remains the recorded 27 focused tests and integrated 536 shared / 1,440 app tests with zero failures/errors and four expected ordinary app skips. These establish bounded source/harness behavior, not phone output or listening preference. The release build emitted Kotlin metadata warnings from existing dependencies but completed successfully; the raw build log is retained.

## Retained artifacts

- Local release evidence and archive copy: `~/Bitchord/work/automix-release-vc85-20261119/`
- Cue build/signing/audit evidence: `/workspace/diagnostics/automix-release-vc85-20261119/`
- Public verification records: `artifact.json`, `artifact.sha256`, `signature.log`, `audit.log`, `elf-alignment.json`, `zipalign-check.log`, `build.log`, source/version manifests.

The initial transfer was interrupted after a partial copy. A resumed transfer completed, and only the checksum-verified file was published to Downloads. No existing APK was overwritten with different bytes, and no app or queue data was cleared.
