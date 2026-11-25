# AE3 M0 Baseline: 1.10-djmix52 (vc94), before-column for djmix53

**Status: DRAFT, uncommitted.** Wave 1C commits it. Matrix below is partial: Club, Subtle and the stress run were not measured.

## 1. Environment and device state

| Item | Value |
|---|---|
| Device | POCO F6, model `24069PC21G`, SM8635, serial `1439ebea` (USB, `adb devices -l`; mDNS `10.17.38.242:33817` also advertised) |
| Package | `com.music.bitchord.loopfix` |
| Build under test | **versionCode 94, versionName `1.10-djmix52`**, `lastUpdateTime=2026-11-24 12:52:40 WIB`; `minSdk=26`, `targetSdk=36` |
| Source checkout | `/home/hfzhkn/bitchord-unified`, branch `ae3`, HEAD `c1498781` |
| Install in M0 | **none.** The already-installed vc94 build was tested. No uninstall, no `pm clear`, no data wipe, no private-prefs edits. |
| Power / display | `mWakefulness=Awake` at capture end (was `Dozing` in the handover) |
| Lock state | **Unlocked.** `isKeyguardShowing=false`, `mKeyguardOccluded=false`, `mDreamingLockscreen=false`, user `Hafizh Haqqani` `deviceLocked=0`. Lockscreen was not bypassed. |
| Foreground | `ai.manus.agents/.launcher.CueBlueGear` (the app was not foregrounded during capture; playback ran in the background via the media session) |
| Media volume | Set to index `1` for capture, then `23` afterward. The readback is inconsistent: `cmd media_session volume --get` printed `150`, so the restored level is **unverified**. |

### Orchard add-on status (critical for M6)

- `dev.sfg.orchard.mobile`: **installed**
- `dev.sfg.orchard.npu.v73`: **installed**
- Inference in the vc94 live run used CPU (`ep=CPU` per handover; no NPU timing lines appeared in the filtered logcat). Whether `QNN_ENABLED` is false in this build was not checked.

## 2. Local audio on device (for M4 golden-pair planning)

Paths only, read-only. The library is large. The listing was capped at 100 entries (`head -100`), so the total count is unknown. It includes `/sdcard/Music` and subfolders `Telegram`, `Nagram`, `SpotiFLAC`, `ATEEZ`. Examples:
- `/sdcard/Music/Papillon - NMIXX.flac`, `/sdcard/Music/Good Days - SZA.flac`, `/sdcard/Music/Ash - ATEEZ.flac`, `/sdcard/Music/Racecar - izna.flac`, `/sdcard/Music/Psycho - Red Velvet.flac`, `/sdcard/Music/Enhorabuena - Manuel Turizo.flac`, `/sdcard/Music/Se Presta - Rawayana.flac`, `/sdcard/Music/Moonstruck - ENHYPEN.flac`
- Full listing was printed by `find /sdcard/Music /sdcard/Download /sdcard/Android/media -maxdepth 4` (first 100 entries).

## 3. Telemetry: what was captured

| Source | File | Lines / note |
|---|---|---|
| Pre-existing phone telemetry, pulled before live run | `/home/hfzhkn/Bitchord/evidence/ae3-m0/pre-existing-vc94-telemetry.jsonl` | 1,197 lines, sha256 `3c090f55...9314e7`. Contains vc93 events (179 before install) plus vc94 events (1,018 after install `1795499560000`). |
| vc94 slice (ts ≥ install) | `/home/hfzhkn/Bitchord/evidence/ae3-m0/vc94-slice-telemetry.jsonl` | 1,018 events |
| Live capture pull (after Festival run) | `/home/hfzhkn/Bitchord/evidence/ae3-m0/live-vc94-automix-telemetry.jsonl` | 1,267 lines total on phone at pull |
| **Combined vc94 since install** | `/home/hfzhkn/Bitchord/evidence/ae3-m0/vc94-combined-telemetry.jsonl` | 1,088 events (1,018 + 70 live). Used for all metrics below. |
| Live logcat (filtered tags) | `/home/hfzhkn/Bitchord/evidence/ae3-m0/live-logcat-raw.txt` | 43 lines |
| Metrics JSON | `/home/hfzhkn/Bitchord/evidence/ae3-m0/m0-metrics.json` | produced by `compute_m0_metrics.py` |
| Analyzer report | `/home/hfzhkn/Bitchord/evidence/ae3-m0/analyze-djmix52-vc94-combined.md` | `docs/tools/analyze_telemetry.py --djmix52` |

The pre-existing file was archived before any remote action. The remote file was **not cleared**: vc93 events remain in it, and vc94 was bounded by install timestamp instead.

## 4. What was run, and what was not

| Protocol item (Part A §10) | Status | Evidence |
|---|---|---|
| Cold start + Festival, 12 natural transitions | **Partial.** 23 timed transitions in the vc94 window, all FESTIVAL. The mix was a mix of pre-existing and live-run transitions, not a clean cold start. Fewer than 12 were from one controlled run. | telemetry + live logcat |
| Club, 12 natural transitions | **Blocked: not measured.** No headless level switch was found: `cmd media_session` exposes only dispatch/volume, and the level UI was not driven (no in-app navigation was attempted). Owner must switch the level. | none (0 CLUB events) |
| Subtle, 12 natural transitions | **Blocked: not measured.** Same as Club. | none (0 SUBTLE events) |
| Stress run, skip to ~20 s before exit ×3 | **Blocked: not measured.** `cmd media_session` supports `dispatch play/pause/next/previous/fast-forward/rewind` but no absolute seek. Seek is only available through the app UI. | none |
| Telemetry + filtered logcat | Done for Festival only | files in §3 |

**ADB playback behavior observed:**
- `cmd media_session dispatch play` started playback. `dispatch pause` paused it. Volume changes by `cmd media_session volume --stream 3 --set N` take effect.
- The app advanced through tracks on its own in the background. The session reached `IDLE` (`isPlaying=false`, `playbackState=3`) at the end of the playlist, not from a skip.
- `dispatch next` / `previous` / `fast-forward` were not used during the live run. Their effect on the app's mix pipeline is unverified.
- `dumpsys media_session` listed YouTube Music as the first session (state `ERROR`); the Bitchord session was not obviously listed in the output I captured, so the session's `PlaybackState` was read from the app's own `mix.state` telemetry, not from media_session.

## 5. Part A §10 baseline table (vc94 `1.10-djmix52`, 23 timed transitions, all FESTIVAL)

Source: `m0-metrics.json` and `analyze-djmix52-vc94-combined.md`. "n/a" means the vc94 build does not emit the field, or the value is not measured.

| Metric | Target (djmix53) | vc94 baseline | Source / gap |
|---|---|---|---|
| Transitions with an audible overlap | 100% | **95.7%** (22/23). Analyzer counts 1 truly zero-overlap (`natural_fallback`). Legacy "zero_overlap_failed" aggregate = 14 (classic-path entries, not truly silent). | `analyze-djmix52-vc94-combined.md` D2. Overlap is a proxy from path/fallback fields, not measured audio. |
| `mix.countdown.broken` | 0 | **n/a: not instrumented.** 0 `mix.countdown.*` events in vc94. | No such event in vc94. Gap. |
| Next-known → READY p50 / p95 | ≤ 6 s / ≤ 15 s | **n/a.** No next-known or READY event in vc94. | Gap. |
| Analysis ms per track (CPU / NPU) | ≤ 1.5 s CPU / lower than CPU | Telemetry `analysisMs` (n=23): p50 0 ms, p95 2,750 ms, max 4,255 ms. Logcat `infer` (live, n=18): mean 949 ms, p95 1,202 ms; `mel` mean 65.7 ms. **CPU only** (`ep=CPU`); NPU n/a. | telemetry `mix.timing` + live logcat. The p50 of 0 comes from timing records that did not measure analysis. |
| Stem ms per needed window | ≤ 8 s | **n/a.** `stemMsOut`/`stemMsIn` = 0 for all 23 transitions; no `stem chunk` logcat lines in live run. | Gap. (vc93 had SCNet `stem chunk` ~9,046 ms, which is not vc94 data.) |
| Path = preparer-frozen | 100% (classic only if renderer unavailable) | **39.1%** (9/23 `v2-native`); classic **60.9%** (14/23). Fire-level: 9/19 (47.4%). | `mix.timing.path`. Failure: most transitions are classic fallback. |
| Exit mid-vocal (Syvenu > 0.5 at exit ± 1 beat) | 0 | **n/a (no Syvenu in djmix52).** UMX vocal proxy is not logged as an exit-mid-vocal metric. | Explicitly not measurable in djmix52. |
| `mix.moved` count / last-resort count | report both | `mix.moved`: **0** (no such event key). Last-resort: **0** (no matching reason string). | Event keys absent in vc94. |
| Festival: distinct recipes over 12 / distinct FX types | ≥ 7 / ≥ 7 | **5 distinct recipes** over 23 timings (`F1:Riser drop`, `F5:Tunnel`, `F8:Double drop`, `EDM_BUILD_DROP`, `PHASER_DISSOLVE`). **9 distinct FX types** (`BassSwap`, `Echo`, `Filter`, `Gate`, `Phaser`, `Reverb`, `Riser`, `Stutter`, `VocalScoop`). | recipes fail (5 < 7); FX passes (9 ≥ 7). |

### Additional context from the same run

- `mix.fire`: 19 (`v2-native` 9, `classic` 10). Watchdog 9 (`stuck-building-8s` 8, plus 1 `natural-override`). `mix.arm.death`: 4.
- Back-to-back repeated `move`: 1. Back-to-back repeated `choreo`: 3.
- `fxCount >= 2` on 78.3% of Festival transitions (target ≥ 80%: **FAIL**).
- Club and Subtle: 0 transitions. Stress: 0 transitions.

## 6. Reconciliation with the 1.10-djmix52 plan claims

- Part A §1 context ("SCNet ~9.0 s per 11 s chunk", "Beat This ~1.35 s per 30 s chunk"): the vc94 live run emitted **no SCNet stem-chunk timing** (stems were not built in the v2-native path). The Beat This `infer` timing in vc94 is ~0.95 s mean (live), not 1.35 s. vc93 numbers stay in the [archived DJMIX52 baseline](../archive/docs/ae3-b12-12/DJMIX52_BASELINE.md).
- `waiting on analysis` plan rejections were common in vc94 (234 `mix.plan.rejected` in the vc94 slice, mostly preparer-planning gaps). The exact counts are in the analyzer output.

## 7. Known gaps and next steps (owner action)

1. **Club and Subtle levels** need a manual switch by the owner on an unlocked phone. Then rerun the 12-transition protocol for each.
2. **Stress run** (skip to 20 s before exit ×3) needs an in-app seek. Headless `dispatch next` is not equivalent.
3. **Natural Festival 12**: the live run produced fewer than 12 clean transitions, and the vc94 run is not a clean cold start. A fresh cold-start Festival run is needed for a fair comparison.
4. **Metrics not instrumented** in vc94: `mix.countdown.*`, next-known/READY timing, stem timing in the v2 path, `mix.moved` and last-resort. These will read 0 or n/a until djmix53 adds them. Do not treat missing events as zero.
5. Media volume readback was inconsistent (`set 23` read back `150`). Verify manually before the next session.

## 8. Chain of custody

- No APK was installed. No Gradle run. No source file in `bitchord-unified` modified. No git commit.
- Remote telemetry was not cleared. Pre-existing file archived first and verified non-empty (1,197 lines).
- Playback was started with `cmd media_session dispatch play` and paused with `dispatch pause` after the run. Logcat collector killed by PID.
- Helper: `/home/hfzhkn/Bitchord/evidence/ae3-m0/compute_m0_metrics.py`, read-only, produces `m0-metrics.json`.
