# cue Android build and signing host

Updated: **2026-11-17**. Operational source of truth is now the global
[`cue-android-box` skill](/home/user/.config/opencode/skills/cue-android-box/SKILL.md),
including the Manus wake procedure. This handover supersedes the older requirement
to build only on Actions and sign only on `bk-android`. **Do not run Android builds
on the workstation.** Use cue for the next authorized build/signing work; retain
GitHub Actions as an independent CI route or fallback.

The user provisioned and verified cue separately, then supplied this handover.
The orchestrator subsequently checked reachability, resources, repository identity,
the bridge, wrapper source and existing reports/artifacts read-only. No build,
test suite, signing, install or playback was run as part of incorporating this
handover; `apksigner verify` inspected an existing artifact. Dropout acceptance
and outstanding widespread stutter remain as recorded in
[REMAINING_WORK.md](REMAINING_WORK.md).

## Snapshot freezes and waking cue

cue is a Manus/E2B VM. Snapshot freezes can suspend access for several minutes;
uptime, background jobs and builds survive. Do not relaunch a build merely because
SSH or a health probe fails. The previous in-VM keep-warm/liveness approach did
not prevent freezes and was removed; do not reintroduce it.

Note (2026-11-18): if SSH or `cue_exec` returns `# Tailscale SSH requires an additional check`, open the printed `https://login.tailscale.com/a/...` approval URL before starting remote Gradle/APK commands. Also note `/workspace/src/bitchord-mix` on `cue` was switched through `vc81-automix-replan` (`e7c8a3e`), `vc81-overlap-eval` (`2fb6b1f`), and `vc82-overlap-listen` (`60b9734`), and currently holds the uncommitted test-only `DeclineOverlapRenderTest.kt` characterization diff (`production.diff` empty); reset/clean before a release build.

The global runbook documents the existing edge watchdog and Manus `task.sendMessage`
wake. A manual wake during vc81 validation returned HTTP200 and cue resumed with
completed reports intact. This verifies the wake request/resumption, not a measured
reduction in every freeze's duration. Keep credentials in their existing protected
files; no key values belong in diagnostics.

The `cue` MCP now exposes status, auto-waking command execution, tests, build,
audit, device and wake tools. Check status first. Do not start another heavy build
through those tools while an SSH-launched build is already running.

## Access and current checkout

| Item | Value |
|---|---|
| Host | Manus/E2B sandbox `cue` |
| Preferred SSH | `ssh cue` or `ssh root@cue.example.ts.net` |
| Tailnet IP | `100.125.76.84` |
| User | `root` |
| OS | Ubuntu 24.04.4, per provisioning handover |
| Repository | `/workspace/src/bitchord-mix` |
| Installed baseline branch/source | `vc80-loop-source` / `2cb6e6fe69b63176614925e8f5a4c6b5725060f8` |
| Current repair branch | `vc81-automix-replan` (isolated planning repair; see current verification record) |
| Git worktree at verification | Clean |
| Wrapper | `/usr/local/bin/cue-android` |

FQDN/alias SSH was verified working. A direct-IP connection initially lacked an
accepted trust record; do not disable host-key checking or replace known keys to
work around this. Use the configured hostname/alias and investigate a genuine
changed-key warning.

Synchronize exact committed source before a new build. The workstation contains
uncommitted handover/research/checklist/status documents that are not automatically
present in cue's clean checkout. Do not overwrite either checkout or force-reset
work to make them match.

## Resources and limits

The supplied provisioning record describes 8 vCPU, approximately 23 GiB RAM,
70 GB disk and approximately 59 GB free at that time. Later read-only checks found:

- 8 CPUs.
- `MemTotal=24,788,940 KiB`, approximately **23.64 GiB visible RAM**.
- `SwapTotal=0`.
- Filesystem total `74,906,734,592` bytes, approximately **69.76 GiB**.
- Free space `55,246,897,152` bytes, approximately **51.45 GiB** at that check.
- No `/dev/kvm`; no Docker executable.

These are guest-visible resources, not evidence of dedicated versus shared host
hardware. Free memory and disk are changing observations, not reserved budgets.

The focused review confirmed earlyoom configured with memory thresholds **5%,
3%** for TERM/KILL respectively and no swap. Builds are not excluded from its
victim selection. A pre-existing idle Gradle daemon was observed using about
7.2 GB RSS; check current headroom instead of assuming an idle host.
Preserve the settings used by the verified builds:

- Gradle JVM heap: **`-Xmx5g`**.
- Gradle concurrency: **`--max-workers=4`**.
- Avoid simultaneous heavy Gradle builds and unbounded native build concurrency.
- Check available memory and disk before build work; do not tune the host or
  disable earlyoom as a routine build workaround.

There is no emulator route on this host. JVM/unit tests and native harnesses are
available; instrumented `androidTest` and emulator runtime validation are not.
The real phone remains necessary for playback quality and final acceptance.

## Toolchain

The provisioning handover records this installed toolchain:

| Tool | Version / path |
|---|---|
| OpenJDK | 21.0.13, `/usr/lib/jvm/java-21-openjdk-amd64` |
| Android SDK | `/opt/android-sdk` |
| Platform tools | 37.0.1 |
| Compile platform | `platforms;android-36` |
| Build tools | `36.0.0` |
| NDK | `27.2.12479018` |
| CMake | `3.22.1` |
| Command-line tools | 19.0 |
| Other tools | ninja 1.11.1, rsync, tmux, socat, jq, g++ 13.3, ffmpeg |

`/etc/profile.d/android-dev.sh` exports `JAVA_HOME`, `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, `PATH` and `GRADLE_OPTS`. The remote repo's `local.properties`
points to `/opt/android-sdk`. Use a login shell and the existing wrapper rather
than re-provisioning the SDK or copying a workstation SDK/cache.

The wrapper exposes `test`, `native`, `apk`, `audit`, `install`, `adb <cmd>` and
`status`. Its source independently confirms four workers for `test` and `apk`,
and `-Pbitchord.bundleWear=false` for `apk`. It exports SDK/Java paths but inherits
the heap setting from the login profile; do not assume a non-login invocation
receives `GRADLE_OPTS`.

`native` compiles the vocal-spectrogram test and runs the renderer selftest,
reverb-stop probe and loop-domain probe. **It does not invoke window calibration
or ASan/UBSan.** Run the project's required native gate and explicit sanitizer
checks when the changed scope requires them; wrapper `native` alone is not
evidence of all those checks.

`status` exits nonzero when the configured phone is absent; that alone does not
indicate a broken toolchain. Use the snapshot/wake procedure above for temporary
VM freezes. Do not depend on keep-warm timers to prevent them.

## Signing and credentials

Signing files already exist on cue at:

```text
/root/.bitchord-secrets/bitchord-loopfix.jks
/root/.bitchord-secrets/keystore.properties
```

The user verified both as mode `0600`; copies at the repository root are
gitignored. Preserve them. Do not print passwords, read secrets into chat/logs,
commit key material, or copy it back to the workstation/another host.

**Keep the wrapper's unsigned-build → apksigner CLI flow.** The user reports
Gradle's signing configuration emitted v2 only in this AGP 8.13.1 setup; the
Gradle-signed artifact no longer survives, so this distinction was not independently
reverified. Existing CLI output independently verifies as **v2+v3**.

The wrapper moves the repo's **`keystore.properties`**, not the keystore itself,
to `/tmp/kp.hold` before Gradle configuration, preventing creation of the Gradle
release signing configuration. It reads signing properties there for CLI signing
and restores the file through an EXIT trap. Do not replace this with Gradle
signing merely because an APK appears installable.

The temporary properties file remains secret material and must not be printed.
The trap cannot recover from SIGKILL or a sandbox reset. Before a later build,
check file presence only: repo properties should exist and `/tmp/kp.hold` should
not. If that state is interrupted, reconcile it carefully against the protected
master without displaying values. Do not run concurrent wrapper builds: they
share the same temporary filename and output tree.

CLI signing uses the ignored repo copies. Before accepting a build, verify the
result's certificate against the trusted digest below; never assume a copy has
not drifted. The wrapper does not itself complete all alignment/audit checks;
execute those explicitly before installation.

Expected signing certificate SHA-256:

```text
20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c
```

After any new build, verify package/version, certificate, v2/v3, 16 KiB alignment,
native payload and artifact SHA-256. Keep APKs and signing files out of git.

### Current artifact location — not wrapper-install-ready

At the read-only verification, the signed APK was **`/tmp/signed.apk`**, while
the release output directory contained only
`app-prod-arm64-v8a-release-unsigned.apk`. `cue-android audit` and `install` target
the missing signed file in that output directory; they cannot currently be used
as if a completed wrapper build had populated it.

The existing `/tmp/signed.apk` was independently checked:

- Size **67,552,565 bytes**.
- SHA-256 `857042a9a2875f83ab53c4d0c168ebe0a7b342a1d563f82a1d3fea647ca163ee`.
- `apksigner verify --verbose --print-certs`: exit **0**, v2/v3 true and the
  trusted certificate listed above.

This is cue's separately built vc80 artifact, not the identical file installed
from CI (`47163057…`). Matching size/certificate does not establish byte identity
or a new audio fix. Do not rebuild merely to repair the current artifact path;
first verify/protect the existing artifact and deliberately choose its destination
if installation is later requested. `/tmp` is not durable artifact storage.

GitHub authentication is already configured as `survey-sudo`, with a token file
under `/root/.config/gh-token`, a profile loader and `gh auth git-credential`.
Use the existing credential path. Never display or copy the token, dump the
profile that loads it, or include secret environment values in diagnostics.

## USB phone access through the workstation

The phone is USB-only with wireless debugging disabled. It must be plugged into
the **workstation**, not cue. Workstation user-systemd unit
`adb-bridge-cue.service` maintains this reverse SSH tunnel:

```text
cue 127.0.0.1:5037 → workstation 127.0.0.1:5037
```

The service uses `Restart=always`. The orchestrator verified it active/running and
verified a listener on cue's loopback port 5037. `cue-android status` reached ADB
but reported no phone; local `adb devices -l` was also empty.

Before device work:

1. Connect/unlock phone `1439ebea` on the workstation and accept any debugging
   prompt.
2. Verify local ADB identity and `adb-bridge-cue.service` state.
3. Use the wrapper's bridge-aware `adb` command to confirm the same serial from
   cue. Do not start a competing remote ADB server or expose port 5037 publicly.
4. Use `adb install -r` only after artifact checks. Preserve data/queue and sticky
   user pause; do not issue playback controls merely to prove connectivity.

vc80 / `2.2.3-djmix38` is already installed. Use a new version code/name for the
next candidate and update the gate/audit expectations consistently. A rebuilt
vc80 is not a new accepted playback fix.

## Network and transfer policy

The user measured a relayed tailnet path through DERP Singapore, approximately
2.5 MB/s, with UDP blocked. Do not assume a direct tailnet connection. Avoid
bulk-syncing `~/.gradle`, SDKs, or build output across this path. Build in cue's
existing checkout and retrieve only needed reports, logs, excerpts and final
artifacts. The host's self-contained signing path avoids `bk-android` cooldown
as a required step in future delivery.

## Existing verification evidence and its limits

The user reports all of these completed on cue at `2cb6e6f`, matching CI run
[44016339991](https://github.com/survey-sudo/bitchord-mix/actions/runs/44016339991):

- Native gate, analyzer/window calibration, ASan/UBSan, loop renderer selftest,
  reverb-stop probe and all **63** finite-loop probe pairs passed.
- Shared JVM: **497 tests, 0 failures/errors/skips**.
- App unit: **1,399 tests, 0 failures/errors, 1 skipped**.
- End-to-end release build and CLI signature checks passed: **67,552,565 bytes**,
  matching certificate, v2+v3, `tools/audit-apk.sh`, and `zipalign -c -P 16`.
- Payload: one ORT library at **34,208,672 bytes**, zero QNN/HTP libraries, seven
  `libbitchord_*.so` libraries.
- Cue unsigned APK versus CI: **1,585/1,592 entries byte-identical**; only seven
  native libraries differed, at the same sizes, with differing GNU build IDs.

The orchestrator independently parsed existing XML reports: shared **497 / 0
failures / 0 errors / 0 skipped** across 58 report files, app **1,399 / 0 failures /
0 errors / 1 skipped** across 132 report files. Existing build logs also record
successful release/unsigned builds. These are inspections of prior results, not
new executions.

No retained cue native/sanitizer execution log was located in the focused review.
Their earlier success remains user-supplied evidence, not a newly observed run or
a claim they were never executed. The wrapper's narrower `native` command must
remain distinct from the reported full verification.

Native binary differences must remain distinct from byte identity. This host
readiness does not change the user's audio verdict: the dropout is fixed on vc80,
widespread stutter remains, and Apple AutoMix parity is unproven.
