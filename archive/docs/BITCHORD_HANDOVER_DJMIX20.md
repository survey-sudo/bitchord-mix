# BitChord Automix — djmix20 (vc62) Handover

> HONEST BOX (no audio claim yet):
> vc62 (2.2.3-djmix20) is BUILT on bk-android and verified (arm64-v8a, oldkey), but NOT installed — the phone is not adb-reachable right now (wireless `_adb-tls-connect` service published but `adb connect` → `Connection refused`; `adb devices -l` EMPTY). No `adb install`, `play`, `seek`, `uninstall` or `pm clear` was sent this cycle.
> Host gates are green: 2043 tests, 0 failures, 1 pre-existing skipped (`GeniusTest`); `:app:assembleProdRelease` BUILD SUCCESSFUL.
> NO audio verdict for vc62 yet — no internal-audio recording/listening has been reported.
> Status: **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**
> Next action is user-only (re-pair wireless adb if needed, then install -r, then the listening gate below); do not start phone playback from host until verdict is in.

## Live state

| Item | Value |
|---|---|
| Phone | wireless service `adb-1439ebea-05jQT0._adb-tls-connect._tcp` (peridot `1439ebea`) — published 2026-11-13 20:3x but `Connection refused`, `adb devices` EMPTY |
| Installed package | `com.music.bitchord.loopfix`, versionCode **61**, versionName `2.2.3-djmix19` (LAST KNOWN vc61; actual current on-device state UNKNOWN while unreachable) |
| vc62 APK (built, not installed) | `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix20-automix-arm64-v8a-oldkey.apk`, 87481971 B, sha256 `e2d0243c522cb84574c9f72f2fa3c6d361682f13495da606ac23246be55f47ce` |
| APK shape | 14 lib entries, ONLY `lib/arm64-v8a/`; apksigner Verifies v2 only, 1 signer, cert `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`; aapt `com.music.bitchord.loopfix vc62` |
| Source baseline | bk-android HEAD `dc78e0d`, `git diff --cached` 6085 lines (before + after) |
| Delta patch | `/home/hfzhkn/bitchord-djmix20.patch`, 833 lines, 48576 B, sha `6c042ca5d8ab1785917e5e9ace25cd9965e19c2e7b7783c3904169f97a1ca143` (9 files, vc61→vc62 delta) |
| Full patch | `/home/hfzhkn/bitchord-djmix20-full.patch`, 10425 lines, 535087 B, sha `f14f00c0f6a63f415a682a3633796b438f5e0c5719acdae0e7a960ed53be1cfd` (57 files from `dc78e0d`) |
| Proof dir | `/home/hfzhkn/bitchord-device-evidence/djmix20-proof/` (see inventory — install blocked) |
| Audio status | **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]** — NO audio claim for vc62 yet |

## What changed in vc62 vs vc61 (originates entirely on bk-android)

### P0 — Reliability

1. **Analyse-ahead (planner starvation).** `CrossfadeController.requestAnalysisAround` already supported next + next+1 queue lookahead, but a 12 s post-mix settle gate (`POST_MIX_ANALYSIS_DELAY_MS`) starved the incoming entry half (`phrase-null tried=0` / `before-smart-analysis-fallback-window`). Removed the gate: next and next+1 (`after` track) are now queued immediately every tick. `TrackAnalyzer.request` is a cheap no-op when already analysed/in-flight, and head work runs on its own single-thread executor, so eager queueing costs nothing when warm. HEAD analysis for next is already entry-usable at the planner level (`analysisReadyForTrack(requireFull=false)` for incoming); test `headAnalysisOfNextIsUsableForEntry` proves HEAD next yields `DJ_BLEND`, not fallback.
   - Next+1: already supported in vc61 (`val after = nextIndex + 1`); vc62 keeps it and removes its settle gate. No invasive new lookahead was needed.

2. **Decline-to-mix gate (Apple parity, conservative).** Enabled the previously `false` unmixable veto and retuned it to the djmix20 spec in `TransitionPlanner.shouldDeclineToMix`: post-fold tempo delta `>8%` AND (Camelot clash `>2 steps` via trusted-key `camelotSteps` OR dual-vocal high: both `vocalProbability >=0.62`). When true:
   - `TransitionPlanner` returns a short `TransitionStyle.EQUAL_POWER` crossfade ending at outgoing `content_end`, cueing incoming at its audible start, reason `decline-to-mix` (before-window `before-decline-to-mix`), policy reason `decline-to-mix` + detail (`delta`, `steps`, `key+dualvocal|key|dualvocal`).
   - `LoopMix.plan` and `LoopMix.bridge` return `null` (why `decline to mix`) so no loop/drop/blend/echo beatmix is forced.
   - `CrossfadeController.considerSmartTransition` skips ALL loop/bridge rungs (`LoopMix`, `PhrasePlanner`, `bridge`) for the same pairs and emits `mix.plan.rejected` (`decline` included in the rejected condition).
   - Scalars only: A-exit from A, B-entry from B, no sliding-window cross-track correlation, no raw PCM sharing.
   Compatibility proofs (new tests): Beautiful 99.93→104.5 (same key, delta ~4.6%) and folded Drip 78.89→UP 162.01 (fold→81.0, delta ~2.6%) both `shouldDeclineToMix=false` and still `DJ_BLEND`/`DJ_FILTER`. The unmixable fixture (120 C vocal .9 → 94 F♯ vocal .9, delta ~21%, steps 6) fires the gate and yields `EQUAL_POWER` at the audible start.

3. **Freeze/arm-death hardening (verified, no behaviour change).** vc61 idle-stall force-exit (`1.5 s` prepare-once, `2.0 s` force-bail, `outExhausted` immediate) + freeze dedup by `phase|outgoingId->incomingId` were re-asserted in `Djmix20AppTest.idleStallForceExitStillDeterministic` (and remain in `Djmix19AppTest`). Loop build-null path audited: `CrossfadeController.startLoopBuild` calls `loopFallBack(lateDeckReason())` immediately when `phase == LOOP_ARMING` and the native build returns `null` — the classic fallback fires on the arming tick instead of riding to EOF. Cold-loop yield (`LoopMix.shouldYieldColdLoop`) is asserted in `Djmix20AppTest.loopBuildNullFallbackDecisionIsSameTickWhenArming`.

### P1 — Taste (implemented cleanly; nothing deferred from the P0/P1 list was needed)

4. **Outro-loop → incoming drop.** `LoopMix.bridge` already preferred `dropPlan` (outgoing clean instrumental loop `outLoops=true`, incoming straight `inLoops=false`, `rung = "Drop"`, `nativeIncoming=true`) ahead of the beat blend when the folded start-rate is within 15% and both sides have grid confidence ≥0.48. vc62 keeps that preference and proves it: `outroLoopDropBridgeOutLoopsInStraight` shows an instrumental outro (vocal-mask window 0.02) returns `Drop` with `outLoops=true`/`inLoops=false`. Beautiful-like sung outro (mask 0.55) correctly does NOT produce a `Drop` loop — `sungOutroIsNotLoopableSoDropDoesNotFire`.

5. **Intro/outro phrase extension.** The renderer already repeats a short clean outro phrase to bridge a long incoming lead-in: `PhrasePlanner` offers `M2` when `exit.loopableRoom && roomBars < entry.leadInBars && leadInBars >= 8` (`loopK` repeats), `LoopMix.Clock` wraps the outgoing loop via `positionAt` (`(k % beats)`) while incoming plays straight, and the sung-veto (`cuts a sung bar`, `cuts the verse`) prevents repeating a sung phrase or cutting the first sung verse. vc62 proves it at the clock level: `clockLoopsShortOutroPhraseToBridgeLongLeadIn` (16 beats = 4 loops, incoming advances 8 s straight). Classic-path `DjMix.phraseExtend` (4-bar repeat → `LOOP_ROLL` only when `djEffect == NONE`) is preserved; it never overwrites a chosen `FLANGER` (see flanger note).

6. **Echo tail past EOF.** Audited LoopMix/Classic tails:
   - Loop path already covers `stopAt = releaseAt + 0.05 + echoHold(8 beats) + exitTailSeconds` in `LoopMixSession.build` and `regionBounds.outTo`, so the echo rings past `releaseAt`.
   - Gap fixed: `LoopMix.echoPlan` carried `exitTailSeconds = 0.0` (truncated at the dry end). It now carries `PhraseConfig.exitTailSeconds(outBeat)` (2 beats, 800 ms floor / 2 s cap), matching `PhraseMoves` for `M6`. `loopMixEchoPlanCarriesExitTail` + `echoLaneRingsPastLastBeatWithExitTail` prove the lane/re-plan bounds extend past the last beat. Classic `withExitTail` (16-knot dissolve) and `RetireGuard`/`Phase.TAIL` (800 ms planned / 350 ms emergency) are unchanged and re-asserted.

### Flangers (user-requested Apple-parity colour)

- **Audit:** Classic (dual-player) flanger was live end-to-end: `DjMix.blendColour` (50% rotation: `pairHash%4 in {0,1}` on low `vocalOverlap <0.18`, otherwise `ECHO`/`NONE`) → `DjMix.colour` (only for `DJ_BLEND` with `djEffect == NONE`, so tempo-gap `ECHO_RIDE` keeps `ECHO`/`REVERB`) → `CrossfadeController.rideColour` (progress `>0.30` → wet `0.42`) → `PlaybackService` `setColour` → `TransitionFilterProcessor` `DjColour` (8-beat LFO, 1–6 ms depth). The **loop-renderer path was dead**: `TransitionMoves.compile` defaulted `flanger=false`, `CrossfadeController` never passed `true`, and `PhraseScript.flanger` was `flanger && (M7 || M8)` only — so every loop beatmix was silent of flanger even when the pair rotation picked it.
- **Fix:** `TransitionMoves.compile(choice, outBpm, inBpm, outgoingId, incomingId)` now computes `autoFlanger` deterministically from the same `pairHash%4 in {0,1}` rotation when ids are supplied: low vocal (`vocalProduct <0.18f && vocalOverlapBeats <1.0`), eligible beatmix templates (`M1`/`M3`/`M7`/`M8`/`ROLLING`/`M2` — extends the old `M7`/`M8`-only gate), not a tempo-gap template (`M6`/`M10`/`CUT_ON_ONE`/`M11`/`RELAXED` excluded), and post-fold tempo within `TEMPO_HARD_DELTA`. `CrossfadeController.compilePhrase` now passes the real `current.mediaId`/`next.mediaId`. Without ids the legacy `M7`/`M8` behaviour is unchanged, so older call sites/tests are untouched.
- **Bounds kept:** wet `0.42` (`effectSettings(FLANGER)`), LFO `8 beats` (`PhraseConfig.FLANGER_LFO_BEATS = 8.0`), depth 1–6 ms (`FLANGER_MIN_MS/MAX_MS`, native renderer `setFlanger depth 0.002 s`). No audio-thread allocs/locks added (ids are strings, hash is scalar arithmetic).
- **Tests:** `flangerFiresOnEligibleLowVocalBeatmixForRightPairHash` (slot 0, `DJ_BLEND` + `M1`, proves both classic `DjMix` and loop `TransitionMoves` paths flange), `flangerNeverOnDualVocalClash` (vocalOverlap 0.85 / vocalProduct 0.9 never flanges), `flangerNeverReplacesTempoGapEchoOrReverb` (`ECHO_RIDE` gap stays `ECHO`/`REVERB`, `M6` stays silent).

### Showcase fixtures (user-cited Apple AutoMix wins — planner fixtures only, no device proof)

- `showcaseCalvinHarrisPairBeatmixesNotDeclines`: "This Is What You Came For" × "One Kiss" at ~124/124 BPM, same key — `DJ_BLEND`, not decline/filter (flanger/beatblend showcase).
- `showcaseTechnologicToGuessDoesNotDeclineAndStaysShort`: "Technologic" ~124 × "Guess" ~130 (folded delta ~4.8%) — not decline, short blend (`fadeSeconds ≤ 8 bars`).

### Stale assertions updated honestly (vc61 fixes preserved)

- `AdvancedAutomixPlannerTest.anUnmixablePairIsCrossfadedAtTheEndIntoTheIntro` now expects `EQUAL_POWER`/`decline-to-mix` (the unmixable veto is ON by design; the djmix9 comment "veto is off" is stale).
- `theFallbackGestureFollowsTheLowEnd` now uses untrusted keys (`keyConfidence 0.1`) so `trustedKey` suppresses decline while the raw-key `DjMix` clash filter still yields the `DJ_FILTER BASS_HANDOFF/SWEEP` gesture it tests.
- `twoVocalTracksOverrideTheGestureWithVocalSafeSweep` now uses 128 BPM (6.7% delta; dual-vocal cap 6%) so it stays a `DJ_FILTER` sweep without tripping the 8% decline gate. The underlying gesture logic is unchanged.
- No vc61 fix was reverted: roll anchor, `regionBounds` single-source, hold-aware start, volume floors, idle-stall all remain and are re-covered.

## Test / build table

| Suite | Result |
|---|---|
| `:shared:jvmTest` | 293 tests, 0 failures/errors |
| `:shared:testDebugUnitTest` | 235 tests, 0 failures/errors |
| `:shared:testReleaseUnitTest` | 235 tests, 0 failures/errors |
| `:shared:allTests` subtotal | 763 (was 721 in vc61; +42 = 14 new Djmix20 × 3 variants) |
| `:app:testProdDebugUnitTest` | 1280 tests, 0 failures, 0 errors, 1 pre-existing skipped (`GeniusTest`) (was 1276; +4 new Djmix20) |
| Grand total | 2043 tests, 0 failures, 0 errors, 1 pre-existing skipped |
| `Djmix20RegressionTest` | 14 tests, 0 failures (jvm) |
| `Djmix20AppTest` | 4 tests, 0 failures |
| Release build | `:app:assembleProdRelease` BUILD SUCCESSFUL, 39m 35s, DONE:0 |
| Note | First full run hit 1 timing flake `StructureAnalyzerTest.syntheticSongFindsTheChorusPreOutroAndFade` (`Timeout 5000 ms` under parallel load in release); it passes in isolation (1m 27s) and passed in the second full run. No timeout constant was raised. |

## Patch / APK ledger (verified on disk)

| Artifact | Lines / bytes | SHA-256 |
|---|---|---|
| `bitchord-djmix20.patch` | 833 lines / 48543 B | `6c042ca5d8ab1785917e5e9ace25cd9965e19c2e7b7783c3904169f97a1ca143` |
| `bitchord-djmix20-full.patch` | 10425 lines / 535087 B | `f14f00c0f6a63f415a682a3633796b438f5e0c5719acdae0e7a960ed53be1cfd` |
| `bitchord-2.2.3-djmix20-automix-arm64-v8a-oldkey.apk` | 87481971 B | `e2d0243c522cb84574c9f72f2fa3c6d361682f13495da606ac23246be55f47ce` |

APK detail: apksigner Verifies v2 only, 1 signer cert `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`; aapt package `com.music.bitchord.loopfix vc62`.

## Proof inventory — `djmix20-proof/`

| File | Status |
|---|---|
| `devices-check.txt` | `adb devices -l` EMPTY; mdns publishes `adb-1439ebea-05jQT0._adb-tls-connect._tcp 192.168.100.218:32777` |
| `install.log` | `adb connect` single-shot → `Connection refused` (no install sent) |
| `apk-sha.txt` | vc62 sha/size/cert/aapt (verified on bk-android) |
| telemetry/pulls/dumpsys/media_session/crash-log | NOT COLLECTED (device unreachable; honest gap, not zeros) |

## Exact next step — user re-pair + install + listening gate (user-only)

1. **Re-pair wireless adb** (phone: Settings → System → Developer options → Wireless debugging → Pair device with pairing code, then run `adb pair 192.168.100.218:XXXX <code>` on the host; or plug USB). Then: `adb -s 1439ebea install -r bitchord-2.2.3-djmix20-automix-arm64-v8a-oldkey.apk` → `Success`, harvest `dumpsys-after-install.txt` (vc62), `telemetry-pull1/2`, `crash-log.txt` (0 expected), `media_session-after-install.txt`.
2. **Listening gate — FOUR pairs in order (user-only, internal audio record for each):**
   1. **Beautiful (Bazzi) → Never Let Go (LNGSHOT)** — 8 Bars not 9; outro to basically empty; gap `<50ms`; dissolve `>=300ms`.
   2. **Drip (ATEEZ) → UP (ATEEZ)** — chipmunk/tempo/volume/early-UP stutter / 762 freeze / arm death regression.
   3. **This Is What You Came For (Calvin Harris ft. Rihanna) → One Kiss (Calvin Harris x Dua Lipa)** — flanger/beatblend showcase (~124/124). Queue them if not present (agent must NOT queue/manipulate).
   4. **Technologic (Daft Punk) → Guess (Charli XCX)** — robot-vocal blend (~124 / ~128-130). Queue them if not present (agent must NOT queue/manipulate).
3. Report verdict + fresh telemetry pull per pair. Until then **no audio claim** for `djmix20`.

## Constraints kept

- `HEAD dc78e0d`, `git diff --cached` 6085 before+after; `git add -N` only the 2 new test files; no `git reset`/`git add` of existing files.
- Version bump ONLY 61→62 (`bitchordVersionCode=62`, `bitchordVersionName="2.2.3-djmix20"`). arm64-v8a only, oldkey cert `20c66ad6…e3fe4c`. No `adb uninstall`/`pm clear`. No play/seek/queue changes by agent.
- Signalsmith headers untouched, Compose layout frozen, scalars-only cross-track scoring (US 8,280,539), no post-handover `setPlaybackSpeed`/`beginEase`/`holdPhase`, zero audio-thread allocs/locks.
