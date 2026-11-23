# BitChord DJMIX18 Handover — 2026-11-13 (vc60, BUILT + INSTALLED)

> **Honest state in 5 lines.** `2.2.3-djmix18` (vc60) is **BUILT, SIGNED (`oldkey`), PULLED, AND INSTALLED ON `1439ebea`** 2026-11-13 ~06:56Z (build) / 13:59 local (install). APK `5f50d0d13292b21aef79fea52616b391114073b748ccc1fc1342b3e6d9a6798c`, 87,481,971 bytes, cert SHA-256 `20c66ad6…e3fe4c` verified, `arm64-v8a` only. Phone `dumpsys` now `versionCode=60`, `versionName=2.2.3-djmix18`. Targeted `*Djmix18*` tests green (8 app + 6 shared×3, 0 failures). Sound quality **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]** on **`Beautiful` by Bazzi → `Never Let Go` by LNGSHOT** (NOT `Never Let Go × S.A.D`) — no play/pause/seek/queue was sent this cycle.

---

## 1. Executive state [PROVEN]

| Item | Value |
|---|---|
| Phone installed | `2.2.3-djmix18` vc60 — `dumpsys-after-install.txt`: `versionCode=60 minSdk=26 targetSdk=36`, `versionName=2.2.3-djmix18`, `lastUpdateTime=2026-11-13 13:59:06` |
| ADB transport | USB `1439ebea` `device` (`product:peridot_global model:24069PC21G device:peridot transport_id:4`) |
| bk-android (build box) | `m_agq4fglq7npke2orqgt5j6leai` — resumed 06:49Z, build job `ddebee4b` exited 0, APK at `/data/workspace/bitchord-task2/work/BitChord/app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk` |
| djmix18 code state | Task 2A (`shared/` + `native/loopmix/`) + Task 2B (`:app`, vc60) — `git diff --cached` stayed `djmix17-full` 6085 lines for the whole cycle (intent-to-add only for the 2 new test files; lineage never altered) |
| djmix18 APK | `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix18-automix-arm64-v8a-oldkey.apk` — 87,481,971 bytes, SHA-256 `5f50d0d13292b21aef79fea52616b391114073b748ccc1fc1342b3e6d9a6798c` |
| djmix18 patches | `/home/hfzhkn/bitchord-djmix18.patch` (2763 lines, 148,390 bytes, `4f70169e…40196`) + `/home/hfzhkn/bitchord-djmix18-full.patch` (8512 lines, 436,046 bytes, `189e7daf…be74eb`) |
| Install status | **`adb -s 1439ebea install -r` → `Success`** (`djmix18-proof/install.log`, 36 bytes, exit 0) |
| Listening verdict | **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]** — required pair is **`Beautiful` (Bazzi) → `Never Let Go` (LNGSHOT)**. Do NOT substitute `Never Let Go × S.A.D`. No audio claim is made until the user listens. |

---

## 2. The 4 djmix18 fixes shipped in vc60

All four fixes verified present in `/home/hfzhkn/bitchord-djmix18.patch` (grep: `safeDeckSpeed` ×17, `incomingHoldSec` ×27, `playableOutDurationSec` ×20, `firstClean` present, `bitchordVersionCode 59→60`, `bitchordVersionName 2.2.3-djmix17→2.2.3-djmix18`).

### Fix 1 — 999×/8× hyperspeed & unity tempo-jump fix
- **Problem:** deck speed could spike to absurd 999×/8× on tempo jumps, producing a loud hyperspeed blip or a unity jump at handover.
- **Fix:** `TransitionMoves.kt` + `native/loopmix/loop_mix_renderer.cpp` tempo-jump handling + `CrossfadeController.safeDeckSpeed` guard so requested deck speed is clamped before it reaches the renderer.
- **Files:** `shared/.../TransitionMoves.kt`, `native/loopmix/loop_mix_renderer.cpp`, `app/.../CrossfadeController.kt` (test: `safeDeckSpeedNeverAllows194or999`, `deckSpeedSafeStacksListenerSpeedOverClampedRate` in `Djmix18AppHandoffTest.kt`).

### Fix 2 — Post-automix incoming intro jump/stutter fix
- **Problem:** after automix handover, the incoming track jumped/stuttered from its intro.
- **Fix:** `LoopMix.Clock.incomingHoldSec` + hold-aware knots in `LoopMixSession` + `CrossfadeController` pre-seek/guarded handover seek, with **no** post-handover `beginEase`/`holdPhase` restart (the handover timestamp is shared once).
- **Files:** `shared/.../LoopMix.kt`, `app/.../loopmix/LoopMixSession.kt`, `app/.../CrossfadeController.kt` (tests: `clockWithIncomingHoldSecSharesOneHandoverTimestamp`, `handoverTargetPrefersValidHeardElseClock`).

### Fix 3 — 60–70% song-eating & abrupt-ending fix
- **Problem:** the outgoing song was cut at ~60–70% (eaten) or ended abruptly instead of playing out its tail.
- **Fix:** `MIN_PLAY_FRACTION=0.78` floor, `CuePoints` no longer forced `|| map.partial`, `StructureAnalyzer.fromPcm(fullTrackDurationSec)` so structure covers the whole track, `TrackAnalyzer` 360s analysis cap, smooth `RELAXED`/`VOCAL_OVER_OUTRO` exit curve + `outTo` extension so the out covers the stop without truncation.
- **Files:** `shared/.../CuePoints.kt`, `shared/.../StructureAnalyzer.kt`, `shared/.../SongMaps.kt`, `shared/.../PhrasePlanner.kt`, `shared/.../LoopMix.kt`, `app/.../smart/TrackAnalyzer.kt` (tests: `head120MapOn210TrackKeepsFullDurationAndPartial`, `straightOutDeckOutToCoversStopAtWithoutTruncation`).

### Fix 4 — Heavy beat-mixable tracks / `Beautiful → Never Let Go` 8-bar fix
- **Problem:** heavy beat-mixable tracks (e.g. `Beautiful` by Bazzi → `Never Let Go` by LNGSHOT) failed to get a clean 8-bar mix.
- **Fix:** `LoopMix.firstClean` quiet-outro relaxation, removing the 2-bar vocal cap in `blendPlan`, `TransitionPolicy`/`TransitionPlanner` ±8% steady-grid rescue, `CrossfadeController` 28s/10-bar cold-loop yield, and `playableOutDurationSec` rendition clamp.
- **Files:** `shared/.../LoopMix.kt`, `shared/.../TransitionPolicy.kt`, `shared/.../TransitionPlanner.kt`, `shared/.../TransitionMoves.kt`, `app/.../CrossfadeController.kt` (tests: `coldLoopYieldLeaves28sOr10BarsBeforeContentEnd`, `coldLoopYieldNeverYieldsInThePastOrBeforeLead`).

**Version bump (this cycle, in-patch verified):** `build.gradle.kts` `bitchordVersionCode 59→60`, `bitchordVersionName 2.2.3-djmix17→2.2.3-djmix18`.

**Hard constraints obeyed:** `arm64-v8a` only; oldkey SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`; package `com.music.bitchord.loopfix`; `adb install -r` only (never uninstall/pm clear); no play/pause/seek/queue manipulation; `djmix8..17` artifacts untouched.

---

## 3. Tests

| Suite | Result |
|---|---|
| `:shared:allTests` | **697/697 pass** (jvmTest 271 + testDebugUnitTest 213 + testReleaseUnitTest 213, all 0 failures — summed from `shared/build/test-results/*/TEST-*.xml`) |
| `:app:testProdDebugUnitTest` (full) | **1271 tests, 0 failures, 0 errors, 1 skipped** (114 TEST xml files) |
| `Djmix18RegressionTest.kt` (shared, new) | **6 tests, 0 failures/errors/skipped** in each of jvmTest / testDebugUnitTest / testReleaseUnitTest |
| `Djmix18AppHandoffTest.kt` (app, new) | **8 tests, 0 failures/errors/skipped** — targeted rerun this cycle: `./gradlew :app:testProdDebugUnitTest --tests "*Djmix18*" --offline --parallel` → **BUILD SUCCESSFUL in 39s, DONE:0** (fresh XML timestamp 06:57:51Z) |

Git lineage: `git diff --cached` = 6085 lines before **and after** patch generation (verified). `git add -N` was applied ONLY to the two new Djmix18 test files. No `git reset`, no `git add` on existing files.

---

## 4. Reference rules used (bkmasterplayer, local `/home/hfzhkn/bkmasterplayer/app/rust/src/engine/{analysis,automix,mixer,deck}.rs`)

1. **±8% stretch cap** — B accelerated/stretched at most 8%; requests beyond the cap become bar-aligned echo/cut, never a >8% camo-filter.
2. **2:1 fold** — 0.75/1.5 + 0.67/1.5 octave folds only; out-of-fold pairs demote to echo/filter instead of a phony tempo match.
3. **`music_end` / `max_seconds=40`** — mix window capped at 40 s; ends by score (outro/break anchors ranked above content-end).
4. **Pre-filled echo** — echo tail is primed before the handoff window; if the outgoing is short, the echo renders **past file end** instead of truncating.
5. **`TempoMap` 1.0× on tempo-jump** — when `tempoFar` forces a tempo jump, the TempoMap resets to 1.0× and the echo covers the seam (no running-speed jump at handoff).

These are the same five reference rules recorded in `BITCHORD_HANDOVER_DETAILED.md`. No Signalsmith headers touched; DSP remains scalars-only across decks/blends.

---

## 5. Build / patch / install status

### Build — SUCCESS
- **What actually ran:** the pre-existing background job `ddebee4b` (`./gradlew :shared:allTests :app:testProdDebugUnitTest :app:assembleProdRelease --offline --no-daemon > /tmp/djmix18-full.log 2>&1`) was still alive on resume (was suspended with the box, resumed with it — at 06:52Z its daemon showed 85% CPU, and `/tmp/djmix18-full.log` showed it deep in `:app:minifyProdReleaseWithR8`, with `seeds.txt`/`usage.txt` already written). **Decision:** rather than killing it per the "if hung" branch (it was demonstrably not hung), it was awaited synchronously via `instabox jobs wait bk-android ddebee4b` (no sleep loop). **Result: BUILD SUCCESSFUL in 2h 20m 28s** (204 tasks: 43 executed, 161 up-to-date), `DONE:0` in `/tmp/djmix18-full.log`. One wrinkle: `:shared:allTests` and `:app:testProdDebugUnitTest` were UP-TO-DATE in that job — they had already executed pre-suspend and were green before the suspend (TEST xml timestamps 03:55–04:34Z prove it, plus the explicit 06:57 targeted rerun above). No time was wasted re-running `:shared:allTests`.
- **Fast targeted test (STEP 1, explicit):** job `b7fb4e9a` (`:app:testProdDebugUnitTest --tests "*Djmix18*" --offline --parallel`) → **BUILD SUCCESSFUL in 39s** (see §3). Full log: `/tmp/vc60_djmix18_test.log` on bk-android.
- **APK:** `/data/workspace/bitchord-task2/work/BitChord/app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk` (Nov 13 06:56, 84M).

### APK verification (remote, then re-verified local)
- `unzip -l … | grep 'lib/'` → **14 entries, all `lib/arm64-v8a/`** (no `armeabi-v7a`/`x86`/`x86_64`).
- `apksigner verify --verbose --print-certs` → **Verifies**, v2 scheme, `Signer #1 certificate SHA-256 digest: 20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` ✓.
- `aapt2 dump badging | head` → `package: name='com.music.bitchord.loopfix' versionCode='60' versionName='2.2.3-djmix18' …` ✓.
- Remote: `sha256sum 5f50d0d13292b21aef79fea52616b391114073b748ccc1fc1342b3e6d9a6798c`, `stat -c%s 87481971`.

### Patches (remote `/tmp/…` → local, `instabox cp` pull, bit-for-bit)
| File | Lines (`wc -l`) | Bytes | SHA-256 |
|---|---|---|---|
| `/tmp/bitchord-djmix18.patch` → `/home/hfzhkn/bitchord-djmix18.patch` | 2763 | 148,390 | `4f70169e66d859d9816dada55c2f23f3086cdf85e3e6f95fee68dc3e81a40196` |
| `/tmp/bitchord-djmix18-full.patch` → `/home/hfzhkn/bitchord-djmix18-full.patch` | 8512 | 436,046 | `189e7dafc377520fd6b4866dbdb71609bbf8aba9471b4693976d184058be74eb` |

Local `sha256sum` + `stat -c%s` + `wc -l` matched remote exactly for all three artifacts (APK too). Local `unzip -l` re-confirmed arm64-v8a-only. (Local `apksigner`/`aapt2` not installed — remote verification stands.)

### `git diff --stat` — djmix18 delta (`git diff`, 18 files, 1681 insertions, 226 deletions)
```
 .../music/bitchord/playback/CrossfadeController.kt | 328 +++++++++++++++--
 .../bitchord/playback/loopmix/LoopMixSession.kt    |  84 ++++-
 .../music/bitchord/playback/smart/TrackAnalyzer.kt |  30 +-
 .../bitchord/playback/Djmix18AppHandoffTest.kt     | 253 +++++++++++++
 build.gradle.kts                                   |   4 +-
 native/loopmix/loop_mix_renderer.cpp               | 155 ++++++--
 native/loopmix/loop_mix_renderer.h                 |   1 +
 .../com/music/bitchord/playback/smart/CuePoints.kt | 242 ++++++++++--
 .../com/music/bitchord/playback/smart/LoopMix.kt   | 204 ++++++++---
 .../music/bitchord/playback/smart/PhraseConfig.kt  |   3 +-
 .../music/bitchord/playback/smart/PhrasePlanner.kt |  29 +-
 .../com/music/bitchord/playback/smart/SongMaps.kt  |  28 +-
 .../bitchord/playback/smart/StructureAnalyzer.kt   |  17 +-
 .../bitchord/playback/smart/TransitionMoves.kt     |  75 +++-
 .../bitchord/playback/smart/TransitionPlanner.kt   |  26 +-
 .../bitchord/playback/smart/TransitionPolicy.kt    |  14 +-
 .../playback/smart/Djmix14PlannerRescueTest.kt     |   6 +-
 .../playback/smart/Djmix18RegressionTest.kt        | 408 +++++++++++++++++++++
```

### `git diff HEAD --stat` — full (staged djmix17-full + djmix18 delta, 50 files, 6199 insertions, 376 deletions)
```
 app/src/main/cpp/jni/loopmix_jni.cpp               |   12 +
 .../music/bitchord/playback/AutomixTelemetry.kt    |   94 ++
 .../music/bitchord/playback/CrossfadeController.kt | 1339 ++++++++++++++++++--
 .../com/music/bitchord/playback/PlaybackService.kt |    4 +
 .../bitchord/playback/loopmix/LoopMixRenderer.kt   |    9 +
 .../bitchord/playback/loopmix/LoopMixSession.kt    |  218 +++-
 .../music/bitchord/playback/smart/TrackAnalyzer.kt |   55 +-
 .../bitchord/playback/Djmix18AppHandoffTest.kt     |  253 ++++
 build.gradle.kts                                   |    4 +-
 native/loopmix/loop_mix_renderer.cpp               |  261 +++-
 native/loopmix/loop_mix_renderer.h                 |   32 +
 patch_djmix10_a.py                                 |  140 ++
 patch_djmix10_b.py                                 |  166 +++
 patch_djmix10_c.py                                 |   43 +
 patch_djmix10_d.py                                 |   54 +
 .../bitchord/playback/smart/CatchUpPlanner.kt      |  212 ++++
 .../com/music/bitchord/playback/smart/CuePoints.kt |  242 +++-
 .../bitchord/playback/smart/DeckBudget.kt          |   78 ++
 .../com/music/bitchord/playback/smart/DjMix.kt     |   14 +
 .../com/music/bitchord/playback/smart/LoopMix.kt   |  318 ++++-
 .../bitchord/playback/smart/LoopRegionCache.kt     |  103 ++
 .../music/bitchord/playback/smart/MixGain.kt       |   21 +
 .../music/bitchord/playback/smart/PhraseConfig.kt  |   32 +-
 .../music/bitchord/playback/smart/PhrasePlanner.kt |  143 ++-
 .../bitchord/playback/smart/RegionalLoudness.kt    |    7 +-
 .../music/bitchord/playback/smart/RetireGuard.kt   |   81 ++
 .../music/bitchord/playback/smart/SeekAction.kt    |   47 +
 .../com/music/bitchord/playback/smart/SongMaps.kt  |   28 +-
 .../bitchord/playback/smart/StructureAnalyzer.kt   |   17 +-
 .../music/bitchord/playback/smart/TrackAnalysis.kt |   16 +-
 .../bitchord/playback/smart/TransitionMoves.kt     |  115 +-
 .../bitchord/playback/smart/TransitionPlanner.kt   |  154 ++-
 .../bitchord/playback/smart/TransitionPolicy.kt    |   63 +-
 .../bitchord/playback/smart/AutomixFreezeTest.kt   |  167 +++
 .../bitchord/playback/smart/AutomixGracefulTest.kt |   10 +-
 .../bitchord/playback/smart/DeckBudgetTest.kt      |   49 +
 .../bitchord/playback/smart/Djmix12TempoTest.kt    |   78 ++
 .../playback/smart/Djmix14PlannerRescueTest.kt     |  184 +++
 .../playback/smart/Djmix14ScrubAndCatchUpTest.kt   |  174 +++
 .../playback/smart/Djmix15RegressionTest.kt        |  273 ++++
 .../playback/smart/Djmix16RegressionTest.kt        |  243 ++++
 .../playback/smart/Djmix17RegressionTest.kt        |  192 +++
 .../playback/smart/Djmix18RegressionTest.kt        |  408 ++++++
 .../music/bitchord/playback/smart/ExitTailTest.kt  |  139 ++
 .../bitchord/playback/smart/LoopRegionCacheTest.kt |   73 ++
 .../playback/smart/RegionalLoudnessTest.kt         |    6 +-
 .../bitchord/playback/smart/RetireGuardTest.kt     |   63 +
 .../bitchord/playback/smart/SeekActionTest.kt      |   33 +
 .../music/bitchord/playback/smart/AnalysisStore.kt |   16 +
 .../bitchord/playback/smart/Djmix17QualityTest.kt  |   92 ++
```

### Install — SUCCESS
- `adb -s 1439ebea install -r /home/hfzhkn/Downloads/bitchord-2.2.3-djmix18-automix-arm64-v8a-oldkey.apk` → **`Performing Streamed Install / Success`**, exit 0, log at `djmix18-proof/install.log` (36 bytes).
- `dumpsys-after-install.txt`: `versionCode=60 minSdk=26 targetSdk=36`, `versionName=2.2.3-djmix18`, `lastUpdateTime=2026-11-13 13:59:06` ✓ (was vc59 `2.2.3-djmix17` in `dumpsys-before.txt`).
- `apk-sha.txt`: local SHA-256 `5f50d0d1…6798c` + 87,481,971 bytes + cert digest line.
- `media_session-after-install.txt`: 52 lines.
- `telemetry-pull2-postinstall.jsonl`: 970K / 993,181 bytes pulled.
- `crash-log.txt`: **0 lines** (`logcat -d -t 500 -s AndroidRuntime AutomixCrash` — no crashes).
- No play/pause/seek/queue actions were sent.

---

## 6. Device telemetry context (pre-install harvest — vc59, NOT vc60)

- **Source:** `/home/hfzhkn/bitchord-device-evidence/djmix18-proof/telemetry-pull1-preinstall.jsonl` — **5590 lines**, `sha256 3280980633bd5829ae1841dbd86a3a73c73862c82ba66ff5824d0c13bb5188b7`. `dumpsys-before.txt` = vc59; `media_session-before.txt` = full `MEDIA SESSION SERVICE` dump (BitChord not foreground at pull time).
- **Tail `seq62–724` (task-log counts):** `phrase-null` ×64, `insufficient-cold-lead-using-classic` ×36, outgoing-decode arm deaths (`mix.arm.death` + `mix.exit.gate`), `mix.fire` `deltaMs −4368…+981` (10/42 `>1000 ms`), `loop-complete` 5 vs `planned-fade-done` 26.
- **File-wide `ev` histogram (5590 lines):** `mix.plan.rejected` 3615, `mix.plan` 579, `mix.loop.fallback` 409, `mix.seek` 348, `mix.freeze.onInterruption` 162, `mix.complete` 146, `mix.exit.tail` 114, `mix.fire` 66, `mix.exit.guard` 60, `mix.arm.death` 43, `mix.exit.gate` 22, `mix.freeze.queueEdit` 17, `mix.watchdog` 7, `mix.error` 2.
- Post-install telemetry (`telemetry-pull2-postinstall.jsonl`) is pulled and archived; its `ev` histogram was not recomputed this cycle (phone only just installed — no post-install mix activity yet, by design).

---

## 7. Next step — user listening test (Definition of Done, user ears only)

vc60 is built + `adb install -r` verified on `1439ebea` — now:
1. Queue **`Beautiful` by Bazzi → `Never Let Go` by LNGSHOT** (shuffle OFF for this check if possible; do NOT use `Never Let Go × S.A.D`), automix ON, play from the top, hands off.
2. Record **internal audio** (screen-record with internal audio ON) of the handoff; stop after Song 2 settles.
3. Report: silence gap `<50 ms`? dissolve `≥300 ms`? beat-match + taste verdict? Send the mp4 + a fresh telemetry pull if anything sounds wrong.
4. Until that verdict: **no audio claim** for djmix18 — status is **unheard / `[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]`**.

---

## 8. File inventory

| Path | Status |
|---|---|
| `/home/hfzhkn/BITCHORD_HANDOVER_DJMIX18.md` | **this file** (updated post-install; DJMIX13/14/15/16/17 handovers untouched) |
| `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` | `MILESTONE djmix18 BUILT & INSTALLED` appended (append-only) |
| `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix18-automix-arm64-v8a-oldkey.apk` | 87,481,971 bytes, `5f50d0d1…6798c` |
| `/home/hfzhkn/bitchord-djmix18.patch` | 2763 lines, 148,390 bytes, `4f70169e…40196` |
| `/home/hfzhkn/bitchord-djmix18-full.patch` | 8512 lines, 436,046 bytes, `189e7daf…be74eb` |
| `djmix18-proof/install.log` | 36 bytes (`Success`) |
| `djmix18-proof/dumpsys-after-install.txt` | vc60 `versionCode=60 versionName=2.2.3-djmix18` |
| `djmix18-proof/apk-sha.txt` | SHA + size + cert |
| `djmix18-proof/media_session-after-install.txt` | 52 lines |
| `djmix18-proof/telemetry-pull2-postinstall.jsonl` | 970K pulled |
| `djmix18-proof/crash-log.txt` | 0 lines (no crashes) |
| `djmix18-proof/telemetry-pull1-preinstall.jsonl` | 5590 lines, `32809806…18b7` (pre-existing, preserved) |
| `djmix18-proof/dumpsys-before.txt` | vc59 (preserved) |
| `djmix18-proof/media_session-before.txt` | preserved |

*End of handover. Remaining item: user internal-audio recording + listening verdict on `Beautiful` (Bazzi) → `Never Let Go` (LNGSHOT).*
