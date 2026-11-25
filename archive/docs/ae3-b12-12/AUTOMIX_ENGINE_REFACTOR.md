# Refactor spec: shrink the fork's footprint in upstream files to marked hooks

Goal: after this refactor, `git diff <upstream-base> HEAD` on upstream-owned files shows only short `FORK-HOOK(...)`
lines that call into fork-owned files. Then upstream releases merge mechanically, and the automix engine evolves
freely inside fork-owned paths.

This is a **pure move. No behaviour change.** Build, test and install it before syncing any upstream release.

## 1. The ownership model (three kinds of files)

| Kind | Examples | On upstream sync |
|---|---|---|
| **Fork-owned** (`tools/upstream-sync/fork-owned.txt`) | the whole automix engine: `CrossfadeController.kt`, `playback/smart/**`, `playback/loopmix/**`, `native/**`, `app/src/main/cpp/**`, plus new `playback/djmix/**`, `DjMixSettings.kt`, `DjMixSettingsSection.kt`, `strings_djmix.xml`, `app/djmix.gradle.kts` | Ours is kept automatically. Upstream commits touching them go to `PORT_LIST.md`, and their intent is ported by hand. |
| **Upstream-owned with hooks** (`hotspots.txt`) | `PlaybackService.kt`, `SettingsSheet.kt`, `AppSettings.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `build.gradle.kts` | Normal merge, upstream wins. Only the `FORK-HOOK` lines are ours, and `hooks-check.sh` proves they survived. |
| **Upstream-owned** | everything else (UI, lyrics, library, Replay, Radio …) | Normal merge. The fork never edits these. |

Why not keep upstream's `CrossfadeController` alongside ours behind a toggle: the fork rewrote its whole dependency
web (LoopMix, planner, sinks, analyzer). Upstream's controller would not compile against the fork's engine without
maintaining a second automix stack. Owning the subsystem plus a port list is cheaper and safer.

## 2. The contract (the only coupling that matters)

The contract is the set of declarations inside fork-owned Kotlin files that upstream-owned code calls. Generate it
once, commit it as `docs/DJMIX_CONTRACT.md`, and regenerate it on every sync:

```bash
python3 - <<'PY' > docs/DJMIX_CONTRACT.md
import re, subprocess, pathlib
owned = [l.strip() for l in open("tools/upstream-sync/fork-owned.txt") if l.strip() and not l.startswith("#")]
files = subprocess.run(["git", "ls-files", "--", "*.kt"], capture_output=True, text=True).stdout.split()
isown = lambda f: any(f == o or f.startswith(o.rstrip("/") + "/") for o in owned)
decl = {}
for f in filter(isown, files):
    for m in re.finditer(r'^\s*(?:(?:public|internal|data|enum|sealed|open|abstract|object|const|inline|fun interface)\s+)*(?:class|object|interface|fun|val|var)\s+([A-Za-z_]\w*)', open(f, errors="ignore").read(), re.M):
        decl.setdefault(m.group(1), f)
print("# DJMIX contract: fork-owned symbols used by upstream-owned code\n")
for f in sorted(x for x in files if not isown(x)):
    t = open(f, errors="ignore").read()
    hits = sorted({n for n in decl if len(n) > 3 and re.search(r'\b' + re.escape(n) + r'\b', t)})
    if hits:
        print(f"## {f}\n" + "\n".join(f"- `{n}` ({decl[n]})" for n in hits) + "\n")
PY
```

Rule: when upstream changes how it calls a contract symbol (a compile error after a merge, or a port-list row), adapt
the **fork engine** to upstream's new call shape. Never edit the upstream caller. Expected contract surface: the
controller's construction and lifecycle calls from `PlaybackService`, `AutomixStatus` and the AutoMix visual state
read by the player UI, and `TrackAnalyzer` entry points.

## 3. Extractions (do in this order, one commit each)

### 3.1 PlaybackService.kt → `playback/djmix/DjMixServiceWiring.kt`
1. Run `git diff v1.9.1 HEAD -- app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`. Every added or changed
   hunk is fork work.
2. Move the logic of each hunk into functions on `object DjMixServiceWiring` (package
   `com.music.bitchord.playback.djmix`). The biggest one is the controller construction: build the fork
   `CrossfadeController` with all fork-only parameters there (separate/stem/prefetch/telemetry lambdas, loop-mix
   lyrics, loudness, NPU/stem wiring).
3. Pass the service's private fields in as arguments. Do not widen upstream visibility.
4. Leave in `PlaybackService.kt` only one-line calls, each ending with `// FORK-HOOK(service-wiring)`. The target
   is 10 or fewer call sites. Update the min count in `hooks.txt`.
5. Where upstream code at a hook site is unchanged from v1.9.1, the line itself is the hook (for example
   `crossfade = DjMixServiceWiring.createController(this, player, trackAnalyzer, ...) // FORK-HOOK(service-wiring)`).

### 3.2 AppSettings.kt → `data/settings/DjMixSettings.kt`
1. From `git diff v1.9.1 HEAD -- .../AppSettings.kt`, take only the **fork-only** settings: state flows, keys,
   load lines and setters. Examples: `automixDjLevel`, `automixStemsEnabled`, `automixNpuEnabled`,
   `automixWindowedStemsEnabled`, and any vc6x–vc9x automix keys that upstream v1.9.1 doesn't have.
2. Move them to `object DjMixSettings`.
3. **Keep every preference key string byte-identical**, so users keep their settings.
4. Use the same `SharedPreferences` instance: `DjMixSettings.load(prefs)`, called from one line in AppSettings'
   load function `// FORK-HOOK(settings-init)`.
5. Update callers. They are all in fork-owned files, except the settings UI (handled in 3.3).
6. Do **not** move settings upstream also has (`automixPerformanceMode`, `crossfadeSeconds`, `smartFadeEnabled`
   …). If the fork changed their defaults, make the change through `DjMixSettings.load` after upstream's load.

### 3.3 SettingsSheet.kt → `ui/screens/DjMixSettingsSection.kt`
1. Move the fork's Automix rows (DJ level, stem mixing, NPU, windowed stems, any other fork rows) into
   `@Composable fun DjMixAutomixRows(...)`.
2. Give the DJ-level picker its own sheet inside the fork file, so the upstream `SettingsChoiceKind` enum is not
   edited. Remove the `AUTOMIX_DJ_LEVEL` entry added in v2.
3. If `ChoiceGroup` or `SettingsRow` are `private` upstream, add a minimal copy inside the fork file rather than
   changing upstream visibility.
4. Leave one call in SettingsSheet at the Automix section `// FORK-HOOK(settings-section)`. If upstream's
   row-builder DSL forces it, use a short marked block.

### 3.4 Strings → `app/src/main/res/values/strings_djmix.xml`
Move every fork-only `<string>` out of `values/strings.xml` and the `values-*/strings.xml` translations, into
`strings_djmix.xml` (`values-xx/strings_djmix.xml` for translations). Android merges resource files, so these
never conflict again.

### 3.5 app/build.gradle.kts → `app/djmix.gradle.kts`
1. Add **one** line at the very end of `app/build.gradle.kts`:
   `apply(from = "djmix.gradle.kts") // FORK-HOOK(gradle-djmix)`. Placing it last makes its settings win.
2. In `djmix.gradle.kts` (script plugins have no type-safe accessors), put:
   ```kotlin
   import com.android.build.api.dsl.ApplicationExtension
   extensions.configure<ApplicationExtension>("android") {
       defaultConfig { applicationId = "com.music.bitchord.loopfix" /* + label resValue, abiFilters arm64-v8a */ }
       packaging { jniLibs { useLegacyPackaging = true /* + non-v73 QNN excludes from Stream A */ } }
       // signingConfigs from keystore.properties, if the fork's differ from upstream's
   }
   configurations.configureEach { exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android") }
   dependencies {
       "implementation"("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
       "implementation"("com.qualcomm.qti:qnn-runtime:2.42.0")
   }
   ```
3. Then revert the fork's inline edits in `app/build.gradle.kts` back to upstream's text.
4. Anything that truly cannot be expressed from the script plugin (rare: `buildFeatures`, `externalNativeBuild`
   if the fork's CMake differs) stays inline as a marked block `// FORK-HOOK(gradle-djmix) begin/end`.
5. Check with `./gradlew :app:dependencies --configuration prodReleaseRuntimeClasspath | grep onnxruntime`.
   Expect only `onnxruntime-android-qnn`.

### 3.6 Manifest and root gradle
- `AndroidManifest.xml`: mark each fork line `<!-- FORK-HOOK(manifest-npu) -->`, for example the `libcdsprpc.so`
  `uses-native-library`.
- `build.gradle.kts`: `sync.sh` maintains the version lines (`FORK-HOOK(version)`).

## 4. Done criteria
- `git diff --stat v1.9.1 HEAD -- $(grep -vE '^\s*(#|$)' tools/upstream-sync/hotspots.txt)` is roughly 80 changed
  lines or fewer in total, not counting `THIRD_PARTY_NOTICES.md`.
- `bash tools/upstream-sync/hooks-check.sh` passes.
- The native gates, `:shared:jvmTest`, `:app:testProdDebugUnitTest` and `assembleProdRelease` are green, and
  `docs/DJMIX_CONTRACT.md` is committed.
- On the phone, `adb install -r` works and settings are preserved (DJ level, stems, NPU switches keep their values).
  Behaviour is identical to the pre-refactor build: 3 transitions sound the same, and the same move names appear
  in logcat.
