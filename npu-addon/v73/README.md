# BitChord NPU add-on — Hexagon V73 (code-less)

Package: `com.music.bitchord.loopfix.npu.v73` (SM8635 / Hexagon V73).

This is our own NPU add-on (Part B4.3), built **after** Part A §6 works
with Orchard's `dev.sfg.orchard.npu.v73` add-on. The app prefers this
add-on when installed and accepts Orchard's otherwise; CPU is the
fallback either way (Part B4.2).

## Status: scaffold, no payload yet

- `src/main/jniLibs/arm64-v8a/` — empty (`.gitkeep`). The QAIRT runtime
  was **not** redistributed: Qualcomm owns it. Before shipping anything
  here, review the QAIRT redistribution terms and stage the reviewed
  library set (start from Orchard's add-on contents).
- `src/main/assets/` — empty (`.gitkeep`). The QNN models
  (`nirovi_qnn.onnx`, `syvenu_qnn.onnx`) are **not** checked in. They are
  verified by the app's `ModelRegistry` (SHA-256) and delivered through
  the `models-v1` release, matching `models.json`
  (`checksumStatus: addon-verified-pending` until then).
- No code: no Java/Kotlin sources, no runner binary.

## Build (manual, separate from the app build)

This directory is a standalone Gradle project. It is **not** included in
the root `settings.gradle.kts` (Part B4.3: separate build).

```bash
cd ~/bitchord-mix
./gradlew -p npu-addon/v73 :assembleRelease
```

Signing: the `npu-addon.yml` workflow configures the fork key from
secrets when present (same `KEYSTORE_*` secrets as the app release);
without them the add-on assembles unsigned.

## Publish

The manual workflow (`.github/workflows/npu-addon.yml`) builds this
project and publishes:

- release `npu-v73-N` (one per manual run),
- the add-on APK,
- `npu-addons.json` — the manifest the app (or a downloader) reads:
  package, version, ABI, file name, SHA-256, size, and the QNN model
  files it carries.

## Safety (Part B14)

- Loaded only from this known package name, resolved through
  PackageManager (`nativeLibraryDir`) — never from arbitrary paths.
- The app runs a self-test (parity vs CPU Nirovi, beat-peak agreement
  ≥ 95% within ±1 frame) before any `ep=QNN` use, and falls back to CPU
  on any failure.
- The main APK's audit (`tools/audit-apk.sh`) still requires **zero**
  QNN `.so` files under `lib/` in the shipped app APK — this add-on is
  the only place the vendor stack lives.
