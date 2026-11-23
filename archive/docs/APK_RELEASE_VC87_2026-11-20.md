# BitChord vc87 / 2.2.3-djmix45: Echo handoff

**Built, signed, audited, delivered, and installed on the phone.** The phone-side size and SHA-256 match the signed artifact. After explicit user authorization, `adb install -r` updated vc86 in place and returned `Success`; the installed package was verified as vc87 / `2.2.3-djmix45`. No app-data reset or app launch was performed. Listening acceptance remains open.

- Filename: `bitchord-2.2.3-djmix45-automix-arm64-v8a-oldkey.apk`
- Computer: `~/Storage/Downloads/bitchord-2.2.3-djmix45-automix-arm64-v8a-oldkey.apk`
- Phone: `/sdcard/Download/bitchord-2.2.3-djmix45-automix-arm64-v8a-oldkey.apk`
- Package/version: `com.music.bitchord.loopfix`, versionCode `87`, versionName `2.2.3-djmix45`
- ABI: `arm64-v8a`
- Size: **67,573,045 bytes**
- SHA-256: `18e79528f2670e2d9b048068f89fa9c9bafb196df91df31fec27313c78a62a74`
- Certificate SHA-256: `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` (existing app key)

## Change

The user reports abrupt outgoing-song cuts during Echo transitions and confirms the recording was from vc86. This update retains the complete vc86 loop recovery, attribution corrections, and reliability diagnostics.

Straight native Echo previously faded outgoing dry gain to zero in 100 ms while the incoming track rose over 0.75 outgoing beat. The outgoing dry gain and bass shelf now follow that existing incoming rise window: 450 ms at 100 BPM. The outgoing fade fits to actual decoded EOF when less audio is available. Incoming cue, echo/reverb sends, feedback, wet retirement, and handover timing are preserved.

Classic Echo previously muted the whole outgoing player at overlap completion even when its scheduled echo repeats still had time to play. Its equal-power fader now runs through that existing tail deadline, capped by known file EOF. Short overlaps close the processor's dry path by overlap completion. Processor parking remains a hard reset after the playing deck has faded; the aborted draft that tried to fade only during parking was removed.

Scripted M6 and the ancillary `DjColour.ECHO` throw use separate paths and are unchanged. The clip was not successfully played by the council, and its exact route was not established. The source changes and generated-audio regressions do not establish that every reported Echo occurrence is resolved or that the listening result is accepted.

## Verification

| Gate | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| Focused Echo/controller/processor | 25 | 0 | 0 | 0 |
| Required JNI Echo + loop handoff | 6 | 0 | 0 | 0 |
| Shared JVM | 536 | 0 | 0 | 0 |
| Ordinary app JVM | 1,457 | 0 | 0 | 7 |

The seven ordinary app skips are the live Genius test and six native-required tests that passed separately. Focused cases overlap the full suite. The native Echo tests render generated stereo PCM through a real session, including short outgoing PCM with a tracked beat grid. They check outgoing dry contribution through the early incoming rise, incoming timing, dry retirement, wet decay, stop continuity, and zero exit-gate violations. Classic regressions drive production controller ticks with the real processor. These are bounded host tests, not Android sink or listening tests.

All final validation and unsigned assembly commands exited zero. Existing-key signing verified both v2 and v3. Package/version, repository APK audit, ZIP integrity, 16 KiB ZIP alignment, and ELF LOAD alignment for all 14 native libraries passed. Both model assets and ONNX Runtime match vc86. QNN remains disabled and no QNN/HTP native payload is present. Existing attribution/reliability DEX markers remain.

The parent independently counted the retained XML, verified all 329 public-evidence checksums, inspected signature/audit logs, checked the downloaded APK SHA-256 and ZIP/ELF structure, and verified all frozen source files. The Downloads file was published only after its checksum matched the signed artifact. ADB then copied it to a hidden partial path on USB device `1439ebea`; its size and SHA-256 were verified before renaming and again at the final phone path. Older APKs were preserved.

## Source and evidence

- Source tree: `~/Bitchord/work/bitchord-mix/.slim/worktrees/automix-echo-smoothing-vc87/`
- Base: exact `4b60ffe36821354231fe5dd3af0e45b9b198e5c7`, plus all released vc86 changes.
- Current source is a frozen working-tree composite, not a new Git commit.
- Archive SHA-256: `4d0f0a5b8a63d038bf8e883d5474ed24ba749551197f2ae4bc4b847d12b5275b`
- Manifest SHA-256: `118b3e659d4a2a1bcdd11ba30457836c1c84bd408f42230c4d1b9939d2d0fdee` (1,567 files)
- Delta from vc86 SHA-256: `dacb54c3fb7dd7f766b6b5445a3d4f299904a6dca762f30672d5c907a177fa2e`
- Local records: `~/Bitchord/work/automix-echo-smoothing-vc87-20261120/`
- Exact commands, exits, logs, XML, APK, and release summary: `validation-release/`
- Final publication/phone status: `delivery.json` in the local release root.
- Authorized installation command, result, and installed-version verification: `installation.json` in the local release root.
- Cue persistent records: `/workspace/diagnostics/automix-echo-smoothing-vc87-20261120/`

All 60 native/C++ inputs match vc86, allowing reuse of its verified host JNI library and retained standalone native-probe evidence. Current Kotlin-to-JNI tests ran; unchanged standalone probes were not rerun. The build checkout uses `/dev/shm` to avoid filling cue's disk, while source snapshots, signed artifacts, and logs remain persistent.

Attempt 1 failed during test compilation because of unavailable `DoubleArray.indexOf` calls. Three lookups in the new test were corrected to `indexOfFirst`; production source did not change. The original snapshot and logs were retained. After successful assembly in attempt 2, the release harness failed while serializing a path outside its diagnostics root. The successful unsigned APK and assembly logs were preserved and signing/audits resumed from that output without another Gradle invocation. Both failed attempts remain in the evidence records.
