# grok-box bootstrap kit

Everything needed to turn a fresh Debian/Ubuntu box into a BitChord build box
that can take over from `bk-android` (an instabox VM that suspends itself every
few hours, which is why this migration exists).

| File | Purpose |
|---|---|
| `bootstrap.sh` | installs JDK 21, Android cmdline-tools, and the exact SDK packages. `--check` reports without changing anything. |
| `migrate-repo.md` | moves the working tree + `.git` + the two secret files, then parity-checks both ends. |
| `verify-build.md` | proves the migration by rebuilding the same commit and auditing the APK. |

Run in that order.

## Target toolchain (measured on `bk-android`, 2026-11-15)

| Component | Version / path |
|---|---|
| OS | Debian 13 (trixie) |
| CPU / RAM | 4 vCPU / 7.8 GiB |
| JDK | OpenJDK `21.0.12.1` — `/usr/lib/jvm/java-21-openjdk-amd64` |
| `ANDROID_HOME` | `/opt/android-sdk` (5.5 G) |
| platforms | `android-34`, `35`, `36`, `37`, `37.0` |
| build-tools | `34.0.0`, `35.0.0`, `36.0.0` |
| NDK | `27.2.12479018` (2.0 G) — also `28.2.13676358` present |
| CMake | `3.22.1` (SDK-bundled; not on `PATH` as `cmake`) |
| Gradle | `8.13` via wrapper (`276 M` dist cached) |
| Gradle cache | `/data/home/.gradle`, 5.4 G |

## What does NOT need to migrate

- **`npu-addon/prebuilt/`** (131 M) — referenced only in comments in
  `app/build.gradle.kts`; not a `jniLibs` source set; ignored by
  `npu-addon/.gitignore`.
- **`app/build/`** (939 M) and **`.gradle/`** (39 M) — regenerated.
- **ONNX Runtime** — resolves from Maven as
  `com.microsoft.onnxruntime:onnxruntime-android:1.31.0`, so the 34 MB
  `libonnxruntime.so` is downloaded, not copied.

## Download budget

| Item | Size |
|---|---|
| Android SDK (platform-36, build-tools 36.0.0, NDK 27, CMake, platform-tools) | ~2.6–3.0 G installed |
| Gradle 8.13 distribution | 276 M |
| Maven dependencies (cold) | ~1.5–2.5 G |
| **Total, first build** | **~4.5–5.5 G** |

Free disk on the target must be at least **8 G** before starting, with more
headroom preferred: `bk-android` runs at 89 % on `/data` before a release build
and needs a regenerables cleanup pass each cycle.

## Status

`grok-box` (`100.127.220.40`) was **offline** on the tailnet as of
2026-11-15 — `active; relay "iad"; offline, last seen 3m ago, tx 780 rx 0`.
Nothing here has been executed against it yet. Once it answers
`ssh box@grok-box`, run `bootstrap.sh --check` first and compare the output
against the table above.
