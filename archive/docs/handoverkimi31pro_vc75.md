Title: vc75 handover — SIGSEGV root cause, fix, and build state
# BitChord Mix vc75 — Crash Root Cause, Fix, and Build Handover

**Written:** 2026-11-16, ~04:00 UTC
**Package:** `com.music.bitchord.loopfix`
**Target build:** versionCode 75 / `2.2.3-djmix33`
**Installed baseline:** versionCode 74 / `2.2.3-djmix32`, 67,431,730 B,
`sha256 c3215537daa1ea7a01322db27b3fac735dfc75f356a2b2094ec1f16ec9deae14`
(installed 2026-11-15 23:42:08)
**Device:** `1439ebea`, POCO `peridot_global` / 24069PC21G, Android 16,
build `BP2A.250605.031.A3` / `OS3.0.304.0.WNPIDXM`

---

## 1. TL;DR

- The vc74 crash loop is **solved and reproduced**. It is a guaranteed null
  pointer write in native code, not a race, not a memory-pressure problem.
- Root cause: `SeparateVocalWindow()` in `native/analyzer/vocal_spectrogram.cpp`
  sized a dead local `outputs[2]` array while the real outputs `out_left` /
  `out_right` stayed empty after `clear()`. The final normalization loop then
  wrote through a null `data()`.
- Introduced by commit `6aaf9cc` (djmix31/32, vc73/vc74). Every vocal-separation
  loop build in vc73 and vc74 crashed. It is not intermittent in cause, only in
  trigger timing — the app dies whenever playback reaches `startLoopBuild`.
- Fix is two lines. Verified standalone: crash before, clean after, clean under
  ASan/UBSan.
- **The fix is not yet in any signed APK.** It exists as an uncommitted patch in
  two trees; neither has produced a release artifact.
- The Simular box (`cb-fcfcd7a79078`, "main") cannot complete
  `assembleProdRelease` — 7.9 GiB RAM is not enough for Gradle daemon + Kotlin
  daemon + R8 + lintVital + NDK compile. It OOM-killed the daemon at
  `minifyProdReleaseWithR8`. **That box is no longer a viable builder.**
- `bk-android` is the only remaining builder and it has the tree but **not the
  native fix**.

---

## 2. The crash — on-device evidence

Pulled read-only from the phone. Nine crashes, not five. All nine are the same
process, the same signal, the same fault address, the same stack to within one
frame.

### 2.1 Crash list

| # | Timestamp (+0700) | PID / TID | `Process uptime` | Tombstone |
|---|---|---|---|---|
| 1 | 2026-11-15 23:55:44.068748419 | 5247 / 28425 | 1272s | tombstone_02 |
| 2 | 2026-11-16 06:23:11.859653480 | 6174 / 16069 | 885s | tombstone_06 |
| 3 | 2026-11-16 07:40:30.533208045 | 25571 / 27540 | 169s | tombstone_13 |
| 4 | 2026-11-16 07:43:40.475335472 | 28921 / 30524 | 304s | tombstone_14 |
| 5 | 2026-11-16 10:05:39.000453019 | 14275 / 19066 | 152s | tombstone_24 |
| 6 | 2026-11-16 10:10:31.450292179 | 19049 / 20743 | 319s | tombstone_25 |
| 7 | 2026-11-16 10:12:45.000586138 | 21453 / 28733 | 390s | tombstone_26 |
| 8 | 2026-11-16 10:16:29.909832250 | 22621 / 30068 | 459s | tombstone_27 |
| 9 | 2026-11-16 10:27:33.873719914 | 1475 / 8217 | 449s | tombstone_28 |

Crashes 1–4 predate the reported window; the reported five were only the tail of
a longer loop that had been running since install.

Note: dropbox `Process-Runtime` (ms) and tombstone `Process uptime` (s) disagree
in every entry — e.g. 11754 vs 449s. Both are recorded as printed. Do not treat
either as authoritative.

### 2.2 Verbatim tombstone (most recent, 10:27:34)

    Process: com.music.bitchord.loopfix
    PID: 1475
    UID: 10490
    Package: com.music.bitchord.loopfix v74 (2.2.3-djmix32)
    Foreground: Yes
    Build fingerprint: 'POCO/peridot_global/peridot:16/BP2A.250605.031.A3/OS3.0.304.0.WNPIDXM:user/release-keys'
    ABI: 'arm64'
    Cmdline: com.music.bitchord.loopfix
    pid: 1475, tid: 8217, name: DefaultDispatch  >>> com.music.bitchord.loopfix <<<

    signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000000
    Cause: null pointer dereference
        x11 0000000000000000  x14 0000000000000000  x24 0000000000000000
        lr  00000074786b70ec  sp  000000735b023630  pc  00000074786b72d8
    18 total frames
    backtrace:
          #00 pc 00000000000362d8  .../lib/arm64/libbitchord_analysis.so
          #01 pc 000000000003809c  .../lib/arm64/libbitchord_analysis.so
                (Java_com_music_bitchord_playback_smart_VocalSpectrogram_nativeSeparate+560)
          #03 ... VocalSpectrogram.separate+248
          #04 ... VocalTracker.separateWindow+1752
          #08 ... CrossfadeController$startLoopBuild$job$1$session$1.invokeSuspend+680

BuildId of both native frames: `5a24e749dfc4173604af47864fb9ff95a529b101`.

Only one native library appears in any frame: `libbitchord_analysis.so`.

### 2.3 The register proof

`pc = 0x362d8` is a private symbol in `libbitchord_analysis.so`. Pulled the
installed APK, disassembled that exact PC, and got:

    362d8:  str s0, [x14, x11, lsl #2]     ; x14 = 0, x11 = 0

That is `dst[index] = ...` in the final normalization loop of
`SeparateVocalWindow()`. Base register null, offset register zero, store size 4.
The fault address `0x0` and `x14 = 0` match it exactly. Confirmed from three
independent directions: the disassembly, the register dump, and a standalone
reproducer that crashes at the same line.

### 2.4 Other buffer contents (checked, nothing hidden)

- `data_app_native_crash`: 2 `com.aliucord` SIGSEGVs (same fault addr, different
  app, unrelated).
- `data_app_crash`: **no vc74 entry.** Three older `com.music.bitchord.loopfix`
  **v70** Java crashes on 2026-11-15:
  `RuntimeException: Unable to create service ... PlaybackService: NullPointerException:
  Attempt to read from field 'java.lang.String android.content.pm.ApplicationInfo.nativeLibraryDir'
  on a null object reference in method 'void ... TrackAnalyzer.<init>(PlaybackService)'`.
  Different bug, different build, predates the 23:42 install.
- `data_app_anr`: no ANR for the package.
- Main logcat buffer: already rotated past the fatal lines. Only `-b crash` and
  dropbox retained them.
- 22 of 31 tombstones belong to `com.aidc.immortal` (Lazada daemon), all
  `SIGABRT / SI_QUEUE`. Noise. Not BitChord.
- The 23:55 crash was also caught by the app's in-app crashpad handler
  (`-----BEGIN CRASHPAD MINIDUMP-----` present in the crash buffer).

### 2.5 Why "on-device acceptance said zero crashes"

`vc74-device/on-device-acceptance.md` recorded "zero crash lines". That was a
cold launch only. The crash only fires when playback reaches a loop build, so a
launch-only check cannot see it. Acceptance needs a **mid-playback** pass.

---

## 3. Root cause

`native/analyzer/vocal_spectrogram.cpp`, `SeparateVocalWindow()`.

Shipped (vc73/vc74) form:

    out_left.clear();
    out_right.clear();
    ... early returns ...
    const std::vector<float> inputs[2] = {left, right};
    std::vector<float> outputs[2];          // dead local, never read
    outputs[0].assign(input_length, 0.0f);  // sized the wrong vectors
    outputs[1].assign(input_length, 0.0f);
    ...
    for (size_t channel = 0; channel < 2; ++channel) {
      std::vector<float>& dst = (channel == 0) ? out_left : out_right;
      for (size_t index = 0; index < input_length; ++index) {   // <-- crash site
        const double denom = std::max(1e-6, win_sq[channel][pad + index]);
        dst[index] = static_cast<float>(ola[channel][pad + index] / denom);
      }
    }

`out_left` / `out_right` are cleared on entry and never resized. `data()` on an
empty `std::vector` is null, so `dst[index]` is a store through address
`0 + index*4`. First iteration, `index = 0`, faults at `0x0`.

Not a race. Not heap pressure. Not an OEM quirk. A deterministic null write on
every call that gets past the early returns — which, on a real transition
window, is every call.

Introduced in `6aaf9cc` ("djmix31/32: AutoMix regression fixes, stem rides,
analysis serialization", 2026-11-15 16:28:41 +0000). That commit touched
`native/analyzer/vocal_spectrogram.cpp`, `native/loopmix/loop_mix_renderer.cpp`,
and all four `app/src/main/cpp/jni/*.cpp`.

---

## 4. The fix

Two lines plus comment. `native/analyzer/vocal_spectrogram.cpp`, replacing the
dead local at the `inputs[2]` site:

    const std::vector<float> inputs[2] = {left, right};
    // Size the two real outputs here, after every early return and before the
    // overlap-add loop below, which writes them by index. Sizing a local pair
    // instead leaves out_left/out_right empty, and the first store in the final
    // loop then writes through the null data() of an empty vector: a store to
    // address 0x0, SIGSEGV mid-playback, the whole process gone.
    out_left.assign(input_length, 0.0f);
    out_right.assign(input_length, 0.0f);
    std::vector<std::vector<double>> ola(2);

Full diff as it stands on the box:

    diff --git a/native/analyzer/vocal_spectrogram.cpp b/native/analyzer/vocal_spectrogram.cpp
    index 0034052..d680d48 100644
    --- a/native/analyzer/vocal_spectrogram.cpp
    +++ b/native/analyzer/vocal_spectrogram.cpp
    @@ -156,9 +156,13 @@ bool SeparateVocalWindow(
       if (input_length <= pad + 1) return false;
       const auto window = HannWindow(kVocalSpectrogramFft);
       const std::vector<float> inputs[2] = {left, right};
    -  std::vector<float> outputs[2];
    -  outputs[0].assign(input_length, 0.0f);
    -  outputs[1].assign(input_length, 0.0f);
    +  // Size the two real outputs here, after every early return and before the
    +  // overlap-add loop below, which writes them by index. Sizing a local pair
    +  // instead leaves out_left/out_right empty, and the first store in the final
    +  // loop then writes through the null data() of an empty vector: a store to
    +  // address 0x0, SIGSEGV mid-playback, the whole process gone.
    +  out_left.assign(input_length, 0.0f);
    +  out_right.assign(input_length, 0.0f);
       std::vector<std::vector<double>> ola(2);
       std::vector<std::vector<double>> win_sq(2);
       for (size_t c = 0; c < 2; ++c) {

---

## 5. Reproduction and verification

Standalone, on the box, with a 0.5 s synthetic stereo input at 44.1 kHz and a
bin-major magnitude tensor of the exact shape the caller builds — i.e. the same
call the app makes, minus Android.

Harness (`/tmp/vsrepro/harness.cpp`):

    #include "vocal_spectrogram.h"
    #include <cstdio>
    #include <vector>
    #include <cmath>
    using namespace bitchord::smart;
    int main() {
      const size_t n = 22050;                 // 0.5 s at 44.1 kHz
      std::vector<float> l(n), r(n);
      for (size_t i = 0; i < n; ++i) {
        l[i] = 0.3f*std::sin(i*0.01);
        r[i] = 0.3f*std::sin(i*0.01+0.4);
      }
      const size_t frames =
        (n + 2*(kVocalSpectrogramFft/2) - kVocalSpectrogramFft)/kVocalSpectrogramHop + 1;
      std::vector<float> mag(kVocalSpectrogramChannels*kVocalSpectrogramBins*frames, 0.05f);
      std::vector<float> ol, orr;
      bool ok = SeparateVocalWindow(l, r, 44100.0, mag, frames, true, ol, orr);
      printf("ok=%d out_left=%zu out_right=%zu in=%zu\n", (int)ok, ol.size(), orr.size(), n);
      double peak = 0; for (float v : ol) peak = std::fmax(peak, std::fabs((double)v));
      printf("peak=%.6f\n", peak);
      return 0;
    }

Results:

| Build | Command | Result |
|---|---|---|
| Shipped (buggy) | `g++ -std=c++17 -O2 -I. harness.cpp buggy.cpp -o buggy_run` | `Segmentation fault`, **exit 139** |
| Fixed | `g++ -std=c++17 -O2 -I. harness.cpp fixed.cpp -o fixed_run` | `ok=1 out_left=22050 out_right=22050 in=22050`, `peak=0.009075`, **exit 0** |
| Fixed + ASan/UBSan | `g++ -std=c++17 -O1 -g -fsanitize=address,undefined -I. harness.cpp fixed.cpp -o fixed_asan` | `asan_exit=0`, no reports |

Compile check of the patched TU inside the vc75 tree:

    g++ -std=c++17 -O2 -c native/analyzer/vocal_spectrogram.cpp -I native/analyzer -o /tmp/vs.o
    -> COMPILE_OK, 21080 bytes

Constants that matter (`native/analyzer/vocal_spectrogram.h`):

    kVocalSpectrogramSampleRate = 44100
    kVocalSpectrogramChannels   = 2
    kVocalSpectrogramFft        = 4096
    kVocalSpectrogramBins       = 2049        (Fft/2 + 1)
    kVocalSpectrogramHop        = 1024

---

## 6. What this changes, audibly

Worth stating plainly because it will confuse a listening test otherwise.

vc73 and vc74 **never ran the Wiener stem path to completion.** Every call
crashed before returning. The surrounding `runCatching` never got a value, so
loop builds fell through to the dry-signal fallback. Net effect: the feature was
dead, and the app died with it.

With the fix, stem separation returns real output for the first time. Loop mixes
in vc75 will have live vocal rides on them that no previous build ever produced.
So vc75 is not "vc74 without the crash" — it is the first build where stem rides
actually happen. Judge it as new behavior, not as a regression.

`AUDIBLE_MAX_RATE = 1.09` stays untouched until a listening pair has been heard.

---

## 7. vc75 scope — the three Kotlin/build edits

Scope for vc75 is the thread-priority change only. Analysis-speed fixes are
deferred to vc76.

### 7.1 `app/src/main/java/com/music/bitchord/playback/audio/PrecisionAudioSink.kt`

Adds `import android.os.Process`, a `private var audioPrioritySet = false`, and
one guarded raise inside `handleBuffer()`, placed *above* the precision check so
delegate passthrough also gets it:

    private var audioPrioritySet = false

    // in handleBuffer(...):
    if (!audioPrioritySet) {
        audioPrioritySet = true
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
    }

Rationale: Media3 owns the thread that calls `handleBuffer()`, so priority cannot
be set from outside. Raising it from inside the first buffer costs one boolean
test in steady state. Everything on that thread is audio work. Some OEMs refuse
the call; `runCatching` means a denial cannot kill playback.

### 7.2 `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt`

Drops the `AutomixPerformanceMode` import and pins analysis to
`THREAD_PRIORITY_BACKGROUND` for the whole pass, in both places it was
conditional:

    - Process.setThreadPriority(
    -     if (AppSettings.automixPerformanceMode.value == AutomixPerformanceMode.EFFICIENT) {
    -         Process.THREAD_PRIORITY_BACKGROUND
    -     } else {
    -         Process.THREAD_PRIORITY_DEFAULT
    -     },
    - )
    + Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

Rationale: analysis tolerates latency, the render thread does not. A mel pass or
an inference batch at `DEFAULT` was landing on the same core at the moment the
sink needed to fill a buffer. Thread count remains the speed knob.

Verified: zero occurrences of `THREAD_PRIORITY_DEFAULT` left in the tree.

### 7.3 `build.gradle.kts`

    -extra["bitchordVersionCode"] = 74
    -extra["bitchordVersionName"] = "2.2.3-djmix32"
    +extra["bitchordVersionCode"] = 75
    +extra["bitchordVersionName"] = "2.2.3-djmix33"

### 7.4 The native fix (section 4) is the fourth change in the same tree.

Combined `git diff --stat` on the box tree:

     .../bitchord/playback/audio/PrecisionAudioSink.kt  | 18 +++++++++++++
     .../music/bitchord/playback/smart/TrackAnalyzer.kt | 31 ++++++++--------------
     build.gradle.kts                                   |  4 +--
     native/analyzer/vocal_spectrogram.cpp              | 10 ++++---
     4 files changed, 38 insertions(+), 25 deletions(-)

Total patch length: 141 lines. All four changes are **uncommitted**.

---

## 8. Test gates

Gates were run on the box tree at 2026-11-16T02:58 on the vc75 Kotlin edits,
**before** the native fix was added. The native change cannot move JVM test
counts, so the numbers still stand as a gate for the tree.

| Gate | Suites | Tests | Failures | Errors | Skipped | Exit |
|---|---|---|---|---|---|---|
| `:shared:jvmTest` | 53 | **443** | 0 | 0 | 0 | 0 |
| `:app:testProdDebugUnitTest` | 126 | **1,359** | 0 | 0 | 1 | 0 |

Baseline target from the handover was 443 and 1,355. Observed 443 and 1,359 —
four more app tests than the earlier baseline. No test was removed or weakened.

Logs: `/tmp/gates.log` (box), key lines:

    SHARED_EXIT=0
    suites=53 tests=443 failures=0 errors=0 skipped=0
    suites=126 tests=1359 failures=0 errors=0 skipped=1
    GATES_DONE shared=0 app=0

### Tests that touch the changed code and still pass

- `app/src/test/java/com/music/bitchord/playback/Djmix25AppTest.kt:251`
  `assertTrue(tracker.separateWindow(l, r, 44100.0, true) == null)` — the JVM
  safe-fallback contract. Holds: on JVM there is no native lib, so
  `VocalSpectrogram.available` is false and it returns null before reaching the
  patched code.
- `VocalSpectrogram.separate(l, r, 44100.0, FloatArray(0), 0, true)` → empty.
  Also holds; `target_frames == 0` returns false before the patch site.

Neither test exercises the crash and neither needs changing. **A real regression
test for `SeparateVocalWindow` does not exist yet** — see open items.

---

## 9. Builders — what happened to each

### 9.1 Simular box `main` (`cb-fcfcd7a79078`) — **DEAD FOR THIS JOB**

Toolchain was complete: JDK 21.0.13, SDK at `/home/user/Android/Sdk`, NDK
27.2.12479018 + 27.3.13750724, cmake 3.22.1, build-tools 35.0.0 + 36.0.0,
27 G free disk, 4 vCPU.

It is **7.9 GiB RAM with no swap**. Release assemble needs Gradle daemon
(`-Xmx4096m`) + Kotlin daemon (~1.3 GiB) + R8 + `lintVitalAnalyzeProdRelease` +
the NDK C++ build, concurrently. It died here:

    * What went wrong:
    Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)
    > Task :app:lintVitalAnalyzeProdRelease
    > Task :app:minifyProdReleaseWithR8
    ASSEMBLE_EXIT=1

Killing stale daemons reclaimed 4.5 GiB each cycle (memory went 7,486 → 3,112
used) and the release build still could not hold. A retry at `--max-workers=1`
with `-x :app:lintVitalAnalyzeProdRelease -x :app:lintVitalProdRelease` was
launched and had not finished when the box was called off; it was already
climbing back to 6,973 MiB used, so the same wall was coming.

**Conclusion: not a configuration problem. Insufficient RAM.** Do not retry this
on that box.

Also on that box and now lost with it: a stale Gradle daemon (`pid 18140`) and
Kotlin daemon (`pid 18215`) were resident; adb exists at
`/home/user/Android/Sdk/platform-tools/adb` (v37.0.1) but **no device attaches
to the box** — the phone is host-side only. `instabox` is host-only too; the box
has no `instabox` CLI and cannot reach `bk-android`.

### 9.2 `bk-android` — **THE SURVIVING BUILDER**

| | |
|---|---|
| Machine id | `m_agq4fglq7npke2orqgt5j6leai` |
| Template | `android` |
| JDK | 21.0.12.1 |
| SDK | `/opt/android-sdk` (5.5 G) |
| Platforms | 34, 35, 36, 37, 37.0 |
| Build-tools | 34, 35, 36 |
| NDK | 27.2, 28.2 |
| cmake | 3.22.1 |
| Gradle cache | 5.4 G |
| Disk | `/data` 20 G at ~89% |
| Build tree | `/data/workspace/vc75-build` |
| Keystore | present, mode 600 |
| `local.properties` | `sdk.dir=/opt/android-sdk` |
| Last job | `b60465c3` |

Job `b60465c3` result observed: **SHARED gate green, 443/443, 0 failures,
0 errors, 0 skipped, 53 suites.** The app gate was mid-flight at last
observation. **I have not seen it finish. Do not assume an APK exists there.**

SSH access: refresh cert first with `instabox ssh-cert bk-android`, then

    ssh -i ~/.config/instabox/ssh/id_ed25519 \
        -o CertificateFile=/path/to/id_ed25519-cert.pub -p 2222 ...

Known pressure: `oomKills: 22` on that box, last OOM a `java` process at 4.4 GB
RSS. `/data` at 89% is tight for another full Gradle output.

**Critical gap: the native fix is NOT in `/data/workspace/vc75-build`.** A write
attempt from the box failed:

    FileNotFoundError: [Errno 2] No such file or directory:
    '/data/workspace/vc75-build/native/analyzer/vocal_spectrogram.cpp'

That path is on `bk-android`, not on the box. It has to be pushed from the host
via `instabox push` + `instabox exec`.

### 9.3 grok-box — deprioritized

`100.127.220.40`, user `box`, host `grok-bot-vm-200061182`, Debian 13, 8 vCPU,
15.6 GiB, 92 G free, JDK `21.0.13+9` at `~/jdk/jdk-21.0.13+9`, SDK stub only.
Files were transferred and provisioning launched (pid `790257`, log
`/tmp/grok_build.log`), then deprioritized. The waiter process was killed. It
was flapping (`offline, last seen ...`) and only reachable over DERP at ~300 ms.
Treat as a possible second builder, not a current one.

---

## 10. Where the fix currently lives

| Tree | Native fix | Kotlin edits | Notes |
|---|---|---|---|
| Box `/workspace/vc75` | **applied**, `COMPILE_OK` | applied | **uncommitted**; box being retired |
| `bk-android` `/data/workspace/vc75-build` | **NOT applied** | applied | has keystore + SDK; pushed from tarball |
| Host `/home/hfzhkn/Bitchord/work/bitchord-mix` | applied (reported) | applied | **uncommitted**; host cannot build (no JDK/SDK) |

The host path was not reachable from this session's shell, so its exact state
could not be re-verified in the final pass. Trust the box copy as the canonical
diff — it is quoted in full in section 4 and was compile-checked.

**Nothing is committed anywhere. If a tree is wiped, the patch in section 4 is
the only surviving copy besides this document.**

---

## 11. Artifacts and secrets

| Item | Path | State |
|---|---|---|
| Clean vc75 source tarball | `/tmp/vc75-grok.tar.gz` (67 MB) | **pre-native-fix** — stale |
| Signing keystore | `/tmp/bitchord-loopfix.jks` | present |
| Keystore props | `/tmp/keystore.properties` | present |
| Host keystore dir | `/home/hfzhkn/.bitchord-secrets/` | `bitchord-loopfix.jks` + `keystore.properties` |
| Repo-root keystore | `<repo>/bitchord-loopfix.jks` | present, gitignored |
| Patch dump | `/tmp/vc75.patch` (box) | 141 lines, all four changes |
| Reproducer | `/tmp/vsrepro/` (box) | `harness.cpp`, `buggy.cpp`, `fixed.cpp`, `buggy_run`, `fixed_run`, `fixed_asan` |
| Gate logs | `/tmp/gates.log` (box) | green, 02:58 |
| Crash evidence | `/tmp/bkcrash/` (host) | 10 files, see list |

`/tmp/bkcrash/` contents: `devices.txt`, `logcat-crash.txt` (1787 lines),
`dropbox.txt` (164), `dropbox-native.txt` (686), `dropbox-anr.txt`,
`logcat-main-filtered.txt` (7725), `tombstones.txt`, `tombstone-index.txt`,
`processes.txt` (51), `packages.txt`.

**The tarball must be regenerated** — it was cut before the native fix landed. A
push of the stale tarball would ship vc75 without the crash fix.

Signing: release signing reads `keystore.properties` from the repo root
(`app/build.gradle.kts:169-229`), falls back to unsigned if the keystore is
absent. `applicationId` is `com.music.bitchord.loopfix`, matching
`defaultConfig`, so a `release`-signed build installs over vc74 with
`adb install -r`. CI secret names, if the workflow path is used:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
(`.github/workflows/android-ci.yml:122-125`).

Two BitChord packages are installed on the phone — `com.music.bitchord` (uid
10463, currently running as a foreground service) and
`com.music.bitchord.loopfix` (uid 10490, the crashing one). Do not confuse them
when reading process state.

---

## 12. APK audit checklist

Do not install without this pass. Script staged as `/tmp/audit_apk.sh` on the
box; re-create it anywhere.

    APK=$1
    SDK=/opt/android-sdk
    AAPT=$(ls $SDK/build-tools/*/aapt2 | tail -1)
    $AAPT dump badging "$APK" | grep -E "package:|sdkVersion|targetSdk"
    unzip -l "$APK" | grep -E "lib/arm64-v8a/.*\.so" | awk '{print $1, $4}' | sort -k2
    unzip -l "$APK" | grep -c "libonnxruntime.so"
    unzip -l "$APK" | grep -ci -E "qnn|htp|libQnn" || echo 0
    unzip -l "$APK" | grep -oE "libbitchord_[a-z_]*\.so" | sort -u
    $(ls $SDK/build-tools/*/apksigner | tail -1) verify --print-certs "$APK"
    ls -l "$APK"; sha256sum "$APK"

Expected:

- `versionCode=75`, `versionName=2.2.3-djmix33`, `package=com.music.bitchord.loopfix`
- exactly **one** `libonnxruntime.so` at **34,208,672 B**
- **zero** QNN / HTP entries
- seven `libbitchord_*.so`
- signature verifies against the loopfix cert
- record APK size and sha256 before install

Then:

    adb install -r <apk>
    adb shell dumpsys package com.music.bitchord.loopfix | grep versionCode
    adb logcat -b crash -d | grep -i bitchord     # expect nothing

---

## 13. Verification after install

The crash only fires during a loop build, so the check has to be mid-playback.

1. Launch, start a track, let it run into a transition that triggers a loop build
   (`CrossfadeController.startLoopBuild`). Confirm the mix is not silent — with
   the fix the stem path returns real audio.
2. Listen for vocal rides. They are new. Compare against vc74, where the path
   crashed and fell back to dry.
3. Let it run several transitions. Watch:

       adb logcat -b crash -d | grep -i -E "SIGSEGV|bitchord"
       adb shell dumpsys dropbox --print data_app_native_crash | grep -c loopfix

   Both should stay at zero new entries. The nine historical ones remain in the
   buffer; compare timestamps, not counts.
4. Check `Process-Runtime` growth to confirm no silent restart.

---

## 14. Open items

### 14.1 Before this ships

- [ ] Push the patched `vocal_spectrogram.cpp` to
      `bk-android:/data/workspace/vc75-build/native/analyzer/`
- [ ] Confirm the app gate finished green on `b60465c3` (or re-run it)
- [ ] `assembleProdRelease` on `bk-android`, watching OOM (22 kills already;
      `/data` at 89%)
- [ ] APK audit (section 12)
- [ ] `adb install -r`, then mid-playback verification (section 13)
- [ ] Regenerate the source tarball so it includes the native fix
- [ ] Commit the four changes — nothing is committed anywhere

### 14.2 Regression test — missing, should be added

There is no test that calls `SeparateVocalWindow` with a valid target magnitude
and asserts non-empty, correctly-sized outputs. The existing tests only cover the
fallback (`available == false`, `target_frames == 0`), which is why a null write
got past CI.

A native test is the right home: construct the harness from section 5 as a
ctest/gtest target and assert `ok == true`, `out_left.size() == input_length`,
`out_right.size() == input_length`. Must not touch the JVM gates, so the 443 /
1,359 counts stay intact.

### 14.3 vc76 — deferred, still open

- **Analysis speed fixes** were explicitly deferred out of vc75.
- **Second crash suspect, not yet proven:** a 250 ms main-thread tick takes the
  Media3 `SimpleCache` lock 4 times per second —
  `CrossfadeController.kt:1397-1418` → `AudioCache.kt:2130-2179`. That is
  stutter territory, not crash, but it has not been ruled out as a contributor
  to the audible hitching the user described ("keep force closing like insane
  mid playback"). vc74's symptom was partly crash, partly stutter; only the
  crash is explained so far.
- `AUDIBLE_MAX_RATE = 1.09` unchanged pending listening pairs.

---

## 15. File map

| File | Relevance |
|---|---|
| `native/analyzer/vocal_spectrogram.cpp` | **the crash and the fix.** `SeparateVocalWindow` at :138, patch site :158-165, final write loop :222-228 |
| `native/analyzer/vocal_spectrogram.h` | constants; `SeparateVocalWindow` decl :89 |
| `app/src/main/cpp/jni/vocal_jni.cpp` | JNI entry, calls `SeparateVocalWindow` at :114 |
| `shared/.../smart/VocalTracker.kt` | `separateWindow` :282, the `runCatching` wrapper |
| `app/.../smart/TrackAnalyzer.kt` | vc75 edit 2; `separateWindow` call site :1877; `wholeTrackExecutor` :71; `npuLibDir` :112 |
| `app/.../audio/PrecisionAudioSink.kt` | vc75 edit 1; `audioPrioritySet` :108, `handleBuffer` :310+; loop splice/generate :562-687 |
| `app/.../CrossfadeController.kt` | `startLoopBuild` :4497-4615 (invokes the crashing path); ticker :1394-1420; `closeLater` :5326 |
| `app/.../loopmix/LoopMixSession.kt` | `build()` :212+; stem separation :261-368; `close()` :129 |
| `app/src/test/.../Djmix25AppTest.kt` | :251 asserts `separateWindow(...) == null` — still passes |
| `docs/HANDOVER_vc75.md` | task spec; §4 the three edits and gates; §5 vc76 |
| `evidence/audio-stutter-review.md` | ranked cause shortlist; B5/B7 on analysis-vs-playback contention and the main-thread tick |

---

## 16. Unrelated finding — OpenCode "No tool named 'shell'"

Not a runtime fault. `~/.config/opencode/opencode.jsonc` denies `shell` and
`edit` for the orchestrator agent:

    { "action": "edit",  "resource": "*", "effect": "deny" },
    { "action": "shell", "resource": "*", "effect": "deny" }

So those tools are filtered out of that agent's surface, and a direct shell call
gets answered with `No tool named "shell"`. Observed in session
`ses_e1dcb065…`: `orchestrator`/`kimi-k3.1-pro` made 4 shell calls, all errored,
while the `copilot` subagent (`muse-ember-1.0`) made 17 and completed. The
handover's "orchestrator lost its edit tool" is the same thing — it never had it.
q
Fix if desired: drop the `shell` deny and the "Do not run shell commands
yourself" line, then reselect the agent. Otherwise keep delegating host work.

`adb` is at `/home/hfzhkn/.local/bin/adb`; phone `1439ebea` is on USB transport
(no wireless connect needed).

---

## 17. Next move, in order

1. Push the patched `native/analyzer/vocal_spectrogram.cpp` (section 4) into
   `bk-android:/data/workspace/vc75-build/native/analyzer/` from the host via
   `instabox push` + `instabox exec`.
2. Check whether `b60465c3` finished; if the app gate is green and an APK
   exists, audit it. If no APK, run `assembleProdRelease` on `bk-android`.
3. Audit, install, verify mid-playback.
4. Commit the four changes. Regenerate the source tarball from the fixed tree.
5. Add the native regression test from 14.2 so this cannot ship again.
6. Open vc76 with the analysis-speed work plus the 250 ms `SimpleCache`-lock
   tick.
That's every finding, every number, every path, and the two trees that still hold the only copies of the patch. Nothing committed anywhere — the diff in section 4 is the one that matters if a tree gets wiped.