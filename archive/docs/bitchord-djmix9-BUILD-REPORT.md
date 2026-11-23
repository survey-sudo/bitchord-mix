# BitChord djmix9 — Build Report (Task 2+3, 12 Nov 2026)

Built **and unit/offline-tested, unheard** — no physical-device listening yet. Sound quality is
NOT verified. Install it, listen on [Beautiful → Never Let Go], and report back.

## APK

- Local: **`/home/hfzhkn/Downloads/bitchord-1.9.1-djmix9-automix-arm64-v8a-oldkey.apk`**
- Cloud: `/data/workspace/bitchord-task2/bitchord-1.9.1-djmix9-automix-arm64-v8a-oldkey.apk`
- Size: 87,573,558 B (84 MB)
- SHA-256: `b0af10490acefc85c2be30376e7e6aade973b9c8d0e21d2ffdc4327deb681c47`
- Package: `com.music.bitchord.loopfix`, `versionCode 51`, `versionName 1.9.1-djmix9`,
  `platformBuildVersionCode 36`, `compileSdkVersion 36`, `targetSdkVersion 36`, `sdkVersion 26` (min).
- ABI: `lib/arm64-v8a/*` ONLY (no x86_64) ✓
- Assets: `beat_this_final0_q8.onnx` 23,138,816 B (SHA-256 `7801f9c1…`), `vocals_umxhq_int8.onnx`
  9,054,206 B (SHA-256 `a2be987b…`) — byte-identical to the djmix8 APK.
- Native: `libbitchord_loopmix.so` 427,512 B, `libbitchord_analysis.so`, `libbitchord_stretch.so`,
  `libonnxruntime.so` 28,637,280 B (all arm64-v8a).
- Signing: **OLD key**, signer DN `CN=BitChord loop fix, O=BitChord loop fix, C=US`, SHA-256
  `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` — **exactly matches the
  djmix8 oldkey APK**, so it installs **in place** over `1.9.1-djmix8` (`adb install -r` or normal
  Android update prompt; do NOT uninstall — settings are kept).
- `apksigner verify`: v2 TRUE, v3 TRUE, 1 signer.

## Install

Phone-only/offline: send/share the APK file, tap to install (over djmix8). ADB:
`adb install -r bitchord-1.9.1-djmix9-automix-arm64-v8a-oldkey.apk`.

## Source

- Work tree: `/data/workspace/bitchord-task2/work/BitChord` (HEAD `dc78e0d` on new repo).
- Base: djmix7 snapshot `b4716f0` (Drive `BITCHORD_FULL_HANDOVER_2026-11-11_part1_docs-source.zip`)
  overlaid on `Upstream v1.9.1 7f45c76` shape; `sharedUi/tv/wearos/web` restored from
  `djmix6-src` (absent in the snapshot zip); ONNX restored from djmix6-src assets (identical).
- AGP bumped 8.10.1 → 8.13.1 + Gradle 8.13 wrapper; `compileSdk` **36** (from 37: the box has no
  working `android-37` platform; see Limitations). R8 stays 8.13.23 (its Kotlin-2.3 metadata
  warnings are pre-existing and non-fatal).

## Files changed (djmix7 → djmix9)

1. `build.gradle.kts` (root): versionCode **51**, versionName **1.9.1-djmix9**, AGP 8.13.1.
2. `gradle/wrapper/gradle-wrapper.properties`: Gradle 8.13.
3. `gradle.properties`: `android.suppressUnsupportedCompileSdk=36,37`.
4. `app/build.gradle.kts`: `compileSdk = 36` (was 37); `tasks.configureEach` disables every
   `*AarMetadata` task (InnerTubeX AAR declares 37; bytecode uses no API-37 symbols).
   `AarMetadata` disables also added at end of `shared/build.gradle.kts` and
   `sharedUi/build.gradle.kts`.
5. `shared/…/smart/TransitionPlanner.kt`:
   - `MIN_SMART_DURATION_SECONDS` **45 → 20** (short tracks get a smart attempt, not `blocked`).
   - `UNMIXABLE_VETO_ENABLED = false` (new): the `isUnmixablePair` plain-crossfade veto is off —
     pairs fall to the DJ-assisted filter/echo rungs instead of a lazy end-of-song fade.
   - Analysis-not-ready fallback now forces an **8 s** crossfade (`max(standardFade, 8.0)`)
     instead of the bare standard fade.
6. `shared/…/smart/TransitionPolicy.kt`:
   - `BREAK_ENERGY_FRACTION = 0.35`, `BREAK_MIN_BARS = 4` (new consts).
   - `MIX_OUT_TYPE_SCORE["break_outro"] = 0.93`.
   - `detectBreakOutro()` (new, public, testable): scans the whole-track energy curve bar by bar;
     a run of ≥4 bars under 35 % of the 85th-percentile energy ending within 2 bars of the
     content end becomes a `break_outro` mix-out candidate (score 0.93) ahead of `content_end`.
     **This is the Beautiful case** — the empty break outro now anchors the mix ~8 bars (or more)
     before EOF instead of cutting at the file end.
   - `mixOutCandidatesOf` appends the break-outro candidate when present.
7. `shared/…/smart/RegionalLoudness.kt`: match clamp **−7…+5 dB → −3…+3 dB** (symmetric; B is
   never dragged 7 dB).
8. `shared/…/smart/MixGain.kt`: `HEADROOM_FLOOR = 0.85f`; `powerSum(p)` and
   `headroomTrim(in, out)` helpers (mid-blend dip ≤ ~1.5 dB instead of 3 dB).
9. `shared/…/smart/PhrasePlanner.kt` (`Lock`): `shown` flag + `noteShown()`; `consider` no longer
   replaces the held template once the countdown has been shown (`locked || shown`).
10. `shared/…/smart/AutomixStatus.kt`: fields `state`, `planType`, `bars`, `tempoPct`, `locked`
    (defaults — no call-site breakage).
11. `app/…/CrossfadeController.kt`:
    - `headroomFor` now delegates to `MixGain.headroomTrim` (0.85 floor).
    - Shown-lock: first published loop countdown calls `phraseLock.noteShown()` and records
      `shownCountdownKey`/`shownLoopStartMs`/`watchdogFired`.
    - `checkShownWatchdog()` (called from `tick()` after `publishAutomixDetail()`): if a shown
      countdown's bar passed 1.2 s with no mix armed and phase is IDLE, logs
      `AutomixCrash` and calls `loopFallBack(...)` → glide into the smart echo/crossfade path.
      **A shown countdown can never again die silently or count below 0.**
    - `describeAutomix` (all three branches) passes `state`/`planType`/`bars`/`tempoPct(private
      helper, octave-folded)`/`locked` into `AutomixStatus`; headlines gain `· locked` once the
      rung is frozen. Countdown strings already clamp at 0 (kept).
12. `shared/src/commonTest/…/AutomixGracefulTest.kt` (new, 6 tests): break-outro synthetic
    (105 BPM loud→quiet 8-bar tail ⇒ candidate at tail start), loud-tail rejection, headroom
    floor, equal-power power-sum flatness, ±3 dB loudness symmetry/reciprocity, short-track
    (22 s) plans.
13. `AdvancedAutomixPlannerTest.anUnmixablePairIsCrossfadedAtTheEndIntoTheIntro` — updated to
    djmix9 intent (pair now yields `DJ_FILTER`, not `EQUAL_POWER`).
14. `RegionalLoudnessTest.aLouderEntryIsTurnedDownAndTheGainStaysInsideTheClamp` — updated to
    ±3 dB bounds (0.708).

## Tests

- `:shared:allTests` — **BUILD SUCCESSFUL, 137 tests, 0 failures**
  (incl. the 6 new `AutomixGracefulTest` tests).
- `:app:testProdDebugUnitTest` — **BUILD SUCCESSFUL, 66 actionable tasks** (0 failures).
- `:app:assembleProdRelease` — **BUILD SUCCESSFUL in 7m 9s, 134 tasks**
  (R8 "kotlin metadata" warnings — pre-existing, non-fatal).
- Pre-existing failures in earlier debug run (4, incl. 2 from my new test file) were fixed;
  final runs above are clean. No on-device/offline-render harness ran (no suitable audio on the
  box — see test pair caveat below).

## Test pair: Beautiful (Bazzi) → Never Let Go (LNGSHOT)

Copyrighted commercial audio is **not on the box** (and must not be redistributed), so no real
render was run. The synthetic fixture in `AutomixGracefulTest.breakOutroPrefersQuietTail`
reproduces the documented shape (low-energy, vocal-free outro ≥4 bars): the planner now
anchors a `break_outro` candidate ~8 bars before EOF, giving the overlap the user hears in
bkmasterplayer. **On your phone, listen for**: Beautiful's break-outro must NOT cut abruptly;
the 8-bar blend into Never Let Go's intro should sit flat in volume (no mid-blend sink), tempo
should ease smoothly, and the countdown must never change style mid-way or go below 0.

## Known limitations / deferred

- **Unheard on a physical device** — sound/feel NOT verified. Unit tests + assemble only.
- **compileSdk 36** (not 37) on this box: the bk-android SDK ships only `platforms;android-37.0`,
  and AGP (8.10.1 **and** 8.13.1 **and** 9.0.0, all tested) cannot resolve a platform directory
  named `android-37` from it — every avenue (renamed copies, patched `package.xml`,
  `source.properties`, `build.prop`, `api-versions.xml`, extension-level, codename removal)
  was rejected by AGP's target lookup, while `avdmanager` lists it fine. InnerTubeX bytecode
  provably uses no API-37 APIs (it compiles against 36 cleanly, full test suite green).
  This needs a proper sdkmanager package `platforms;android-37` or an AGP with formal 37
  support — flagged for a future round. Runtime `targetSdk` remains 36 as the project declares.
- **No offline render harness** for real songs (no legal audio available on the box) — the
  `phrase_isolator_test.cpp` native test and a real-pair render remain desirable.
- Native 64-frame filter zipper, `holdPhase` pulsed corrections, WSOLA stretch on non-loop
  blends: untouched (scoped out; too risky for this round). Single-clock native mixes already
  carry loops/phrases.
- Phone-layout UI untouched per user's hold; the Automix line now carries locked state,
  plan/bars/tempo honestly.

## Provisioning notes (bk-android)

- `/data` cleanup: deleted `bkmasterplayer/app/build` + `.dart_tool` + bisect `jadx-out`,
  `unpacked`, `target.apk`, Gradle dists 8.11/9.1. Disk went 92% → 89% (post-build ~93%, 1.2 GB
  free — final APK only ~84 MB).
- `instabox exec bk-android -- "<cmd>"` (or `--background` + `instabox jobs ls/logs/wait`) is the
  working non-interactive pattern; `instabox ssh` is interactive-only.
- New work dir `/data/workspace/bitchord-task2/`; DIAGNOSIS.md copied there at task start.

## Test re-verification 2026-11-12 03:31 UTC

- Command (bk-android, tree `work/BitChord` dc78e0d): `./gradlew :shared:allTests :app:testProdDebugUnitTest --console=plain`
- Result: `BUILD SUCCESSFUL in 3m 20s, 90 actionable tasks: 71 executed, 19 up-to-date`
- Counts (from generated XML): `shared/build/test-results/testReleaseUnitTest` 137 tests / 0 failures / 0 errors (15 suites incl. `AutomixGracefulTest` 6/6: `shortTrackStillPlans`, `equalPowerSumsFlat`, `breakOutroRejectsLoudTail`, `breakOutroPrefersQuietTail`, `loudnessMatchIsSymmetric`, `headroomTrimStaysAboveFloor`); `jvmTest` 192/0/0; `testDebugUnitTest` 137/0/0; `app/build/test-results/testProdDebugUnitTest` 1263 tests / 0 failures / 0 errors / 1 skipped (`GeniusTest`) in 113 suites.
- Disk: freed only old Gradle caches before run (`caches/8.11.1`, `caches/9.1.0`); `/data` 518M (543059968 B) → 1.9G (2021285888 B), after build 1.7G (1778659328 B). Source, keys and `*.apk` untouched.
- APK unchanged: `b0af10490acefc85c2be30376e7e6aade973b9c8d0e21d2ffdc4327deb681c47` (local + cloud, 87573558 B). No source changes from this re-run (`git status` clean).
