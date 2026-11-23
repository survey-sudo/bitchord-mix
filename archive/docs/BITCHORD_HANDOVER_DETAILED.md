# BitChord Automix — Definitive Detailed Handover (djmix17 / vc59 + Task 13-STOP)

Date: 2026-11-13. Author: AI handover (Task 13-STOP, user-requested immediate stop, 0 waits).
Scope: self-contained engineering truth for every future session. If this file
contradicts an older handover, this file wins. All claims below are telemetry-backed
or explicitly marked `[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]`.

## §1 Executive State Summary (5-Line Honest Box)

1. **`2.2.3-djmix17` (`vc59`) is BUILT, SIGNED (`oldkey`), and INSTALLED LIVE ON PHONE** (`pid-start=30902`, APK `sha256 0be66f3a8f391c76696f5b7b76d8b701341356c4e41804ac4a5914b68c69c039`, `87,481,971 B`; `install.log = Performing Streamed Install → Success`).
2. **`[PROVEN ON DEVICE TELEMETRY vc56–vc59]`:** `AnalysisStore quality="full"` persistence (vc59 `L4868 seq2 qualityA="full" qualityB="full"` vs vc58 `L4865 seq378 qualityA="none"`), `CatchUpPins.dropOnNonCatchUp` anchor-flip (vc59 `seq36 4.5935s → seq37 9.135s → seq38 4.5935s`), cold-yield → classic fire (`vc59 seq44 mix.loop.fallback insufficient-cold-lead-using-classic → seq46 mix.fire classic +41 ms, cueErrorMs=0`, sharpest ever), `mix.exit.violated=0` across all builds, 0 crashes.
3. **`[PARTIALLY CAPTURED vc59 before user stop]`:** `telemetry-pull1/2/3` only (4866/4866/4927 lines, Task 13-LIVE cancelled after `pull3`); phone now `[PAUSED-UNKNOWN — adb empty at handover check]`; no `pull4`; literal `Never Let Go → S.A.D` pair fired on S.A.D→LAST GOODBYE but the literal pair itself (`seq34..39`) plain-advanced to `193,641 ms` EOF with `0 loop.fallback + 0 arm.death` (see §6).
4. **`[OPEN / NEXT SPRINT]`:** literal `Never Let Go → S.A.D` firing (narrowed to rendition-mismatch + `markerOnly` gates, §6) + remaining vc59 scrub proofs (CANCEL/EMERGENCY_TAIL canonical, loop-abort) + `djmix18` if needed (15-min fix plan, §6).
5. **`[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]`:** acoustic `<50 ms` gap, `>=300 ms` dissolve, musical taste — telemetry cannot prove these; user records internal audio from PAUSED state on next connect. No audio claim is made anywhere in this handover.

## §2 Hard Project & Architecture Constraints

* Package `com.music.bitchord.loopfix`, `arm64-v8a` ONLY (verified `unzip -l` only `lib/arm64-v8a/`, `aapt2 native-code:'arm64-v8a'`).
* Signing `oldkey` SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` (v2, `apksigner verify --verbose --print-certs`).
* Install: `adb install -r` ONLY — never `adb uninstall` / `pm clear` (would wipe queue/analysis/DB and falsify proofs).
* Cloud build box `bk-android:/data/workspace/bitchord-task2/work/BitChord` via `instabox` (not MCP box). Pre-step: `mkdir -p shared/build/intermediates/aar_metadata_check/release/checkReleaseAarMetadata`. Long builds: `instabox exec --background bk-android -- ./gradlew :app:assembleProdRelease --offline --no-daemon > /tmp/release-build*.log 2>&1` + `instabox jobs wait` synchronously (no `sleep` loops). Binary transfer: stdout-only `instabox ssh bk-android -- "cat …apk" > local.apk` (no `2>&1`), verified by `sha256sum`+size bit-for-bit.
* Patch lineage: `HEAD=dc78e0d` (djmix9), `djmix16` staged in index (`git diff --cached`), `djmix17` in working tree (`git diff`). Delta `git diff`|base64, full `git diff HEAD`|base64. Historical patches (`djmix14/15/16`) immutable.
* DSP/patent: scalars-only across decks/blends (no raw PCM cross-deck sharing). GPL-only deps. Signalsmith (analysis C++) untouched in this sprint. No post-handover `setPlaybackSpeed` for mix tempo. Zero audio-thread allocs/locks. Compose UI layout frozen — no UI churn, fixes land in planner/controller only.

## §3 Complete APK & Patch Ledger (djmix8 → djmix17 / vc50 → vc59)

### APKs (`/home/hfzhkn/Downloads/`, `oldkey`, `arm64-v8a` only)

| Build | File | Bytes | SHA-256 |
|---|---|---:|---|
| djmix8 | `bitchord-loopfix-1.9.1-djmix8-arm64-v8a-oldkey.apk` | 87582011 | `4e7c4a0005a161eb5ffa00af3cd6ec92248933b968a3d85240e7850063aefd38` |
| djmix9 | `bitchord-1.9.1-djmix9-automix-arm64-v8a-oldkey.apk` | 87573558 | `b0af10490acefc85c2be30376e7e6aade973b9c8d0e21d2ffdc4327deb681c47` |
| djmix10 | `bitchord-2.0.0-djmix10-automix-arm64-v8a-oldkey.apk` | 87465587 | `82ebbbf5dec8b5194889cb8df450433ba09b1adbf8820635663a8cd875fe1889` |
| djmix11 | `bitchord-2.0.1-djmix11-automix-arm64-v8a-oldkey.apk` | 87465587 | `357e281d34e9f7222b2fd43196eaec0733b8db717c2c6f43aee8444074bdf029` |
| djmix12 | `bitchord-2.1.0-djmix12-automix-arm64-v8a-oldkey.apk` | 87465587 | `a25900e9647534511b36fdb70abfc914b1fb5eaceb1010b0a7660f4848a1eb8d` |
| djmix13 | `bitchord-2.1.1-djmix13-automix-arm64-v8a-oldkey.apk` | 87465583 | `2237caa1dc72b4afb52c2633585debd8cb5cbe8f6e75fd40a806fedd8dd69f9b` |
| djmix14 | `bitchord-2.2.0-djmix14-automix-arm64-v8a-oldkey.apk` | 87465587 | `dd8d49967f0826c5e9330368f4130a642a39686bb47b78216ce64828ba19f915` |
| djmix15 | `bitchord-2.2.1-djmix15-automix-arm64-v8a-oldkey.apk` | 87481971 | `ca90837f1528df8a749f88b3fe07ba663c8cf522692960da86fd04bdb950057c` |
| djmix16 | `bitchord-2.2.2-djmix16-automix-arm64-v8a-oldkey.apk` | 87481967 | `a9e80e71248c4899ff58190a0742621f40a6ab81c02b2135a0a3f41403a44010` |
| **djmix17 vc59** | **`bitchord-2.2.3-djmix17-automix-arm64-v8a-oldkey.apk`** | **87481971** | **`0be66f3a8f391c76696f5b7b76d8b701341356c4e41804ac4a5914b68c69c039`** |

vc59 remote: `/data/workspace/bitchord-task2/work/BitChord/app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk` (same bytes/SHA).

### Patches (`/home/hfzhkn/`)

* `bitchord-djmix14.patch` 109690 `dbdbbb0ae32d108bbe25a47262d26b6efeacd99acab53a07453d92672456673d`
* `bitchord-djmix14-full.patch` 197592 `f645c223f8313ee8744576aded22b0d4631762c205799a96905d5c33b5cd6f96`
* `bitchord-djmix15.patch` 47420 `67a95fa614ba14d246e26668c54203701b2867c42b8a02e9d3e063b6e556dd7c`
* `bitchord-djmix15-full.patch` 252483 `a98173f718b9213b38de8cf65df2312413f0ed63750a90455557c2d6082e301c`
* `bitchord-djmix16.patch` 37536 `f0d08d4f34808c801a179525d6bd90676ecca35315ec82c9ed4b6f93d59086c7`
* `bitchord-djmix16-full.patch` 279458 `f2143e393139de281c1d8b1dbaa1427bd8d9dc214253020f3a4e402bdd2471fc`
* **djmix17 delta** `bitchord-djmix17.patch` 35418 `31c313ccf982368237f8511e8801cec70cab6083a53c9b3f63736d72e3669ec8` (681 lines, 8 files)
* **djmix17 full** `bitchord-djmix17-full.patch` 307018 `4a4c6821a4a7d463a8cfcb8be45752b5f0fa81f70bd40de141ff27f4efb804e3` (6085 lines, 45 files)
* Staging: `HEAD=dc78e0d` → index = djmix16 (`git diff --cached --stat` 42 files) → worktree = djmix17 (`git diff --stat` 8 files).

## §4 Chronological Engineering & Telemetry Journey (vc55 Forensics → vc59)

* `vc55 forensics`: `Never Let Go (193.641s) → S.A.D` plans-only (`mix.plan`, 0 `mix.fire`/`mix.arm`/`mix.exit`), `phase=IDLE + loop!=null` 159–182s. Root: cold loop shadowed classic.
* **vc56 CatchUpPlanner + CatchUpPins** (`CatchUpPlanner.kt`, `CrossfadeController.kt:1495-1535` pin at `playbackTime+lead`): moving-carrot `transitionStart 187.5→188.9→190.7` shrink; fixed by pin-once reuse until pair/epoch change.
* **vc57 LoopRegionCache + 40s prime + ungated priming** (`LoopRegionCache.kt`, `LOOP_PRIME_LEAD_MS=40_000L CrossfadeController:6122`, `primeLoopDecks:3621`): `loopRegion` needs COMPLETE rendition (`chooseRendition isComplete`) but DB-cached analysis meant S.A.D never downloaded; removed `nextStreamReady` gate so priming IS the warm. Telemetry: fixes proven, literal pair still blocked (`qualityA=none`).
* **vc58 DeckBudget** (`DeckBudget.kt` `NO_DEADLINE=MAX/4`, 40s horizon, wall-only `provenOverrunMs`): killed `374320 ms over` (boot-uptime-as-deadline from `deadlineMs==0` sentinel) and `116245 ms over` (media `mixSeconds` subtracted from wall clock) — `mixSeconds` splice lateness now wall-only (`CrossfadeController:4372`).
* **vc56–58 SeekAction/RetireGuard** (`SeekAction.isNextEdit`, `RetireGuard.loopTailMs`, `finishLoop()` mute/BPMs, `loopArmingSpareAudible` 0ms pre-takeover retire): killed false `mix.freeze.queueEdit` and 8× `emergency-retire-guard` LOOP_ARMING tails; scrub/abort now 0ms to IDLE; genuine audible still tails. Telemetry: 0 `ms over`, 0 `finish:LOOP_ARMING` tails.
* **vc58 coldyield** `LoopMix.shouldYieldColdLoop` (`LoopMix.kt:318`): non-building `max(8.5s, blendArmLead 20-35s, 25s if !ready)`; building hung-prime yield `8.5/12s` → **vc59 `10/14s`** (`djmix17-harden`) so `contentEnd` 3s early still leaves 7/11s.
* **vc59 beginLoop fall-through** (`CrossfadeController:3689` Boolean): old Unit silent `return` + unconditional caller `return` starved classic even with `begin()` telemetry ready; now every refuse logs `mix.loop.fallback` + `LOOP_SUPPRESSION_MS=90s`, caller clears `loop/loopPlanned/loopScript/loopPlanKey/plannedTransition` and falls into classic `begin()` same tick. `begin():1970` + `armDeath()` logs `mix.arm.death` per guard.
* **vc59 CatchUpPins.dropOnNonCatchUp** (`CrossfadeController:1508`): any valid non-catch-up drops pin (interior→final flip `seq367 164.6 → seq368 181.6 → seq369 182.9`); blocked plans keep pin.
* **vc59 AnalysisStore quality** (`AnalysisStore.kt:16` + legacy inference): `quality="full"` persisted; legacy entries with `energyCurve/tailBeats` infer `full` — proves Fix #4 live (vc59 `seq2 full/full`).
* **vc59 primedSession hardening** (Task 12): `startLoopBuild` LOOP_ARMING non-`armWhenReady` now arms/shifts immediately (no stash-strand); `driveLoopArming` picks up stashed `primedSession`; `beginLoop` prime-active recomputes missing deadline.

## §5 Device Telemetry Evidence Summary (vc56–vc59)

* **Fire evolution:** `+22,717 ms` (early) → `+193 ms` → `+122/+211/+221 ms` (vc58) → **`+41 ms` (vc59 `seq46`, planned 181199 actual 181240, cueErrorMs=0)** — sharpest classic fire across vc55–vc59, ~1 tick (40ms `ARM_STEP`) of ideal. `cueErrorMs=0` on every classic fire.
* **Invariants:** `mix.exit.violated=0` (all builds, all pulls), `0` `BitChord` crashes (crash-log only unrelated `reportKitInfos` FATALs), `0` `ms over` (vc58+), `0` pool violations/freeze/watchdog/arm.death on proved pairs (watchdog present but unfired on proved pairs).
* **vc59 proof dir** `/home/hfzhkn/bitchord-device-evidence/djmix17-proof/` (Task 13-STOP):
  * `install.log` (2 lines): `Performing Streamed Install` / `Success` — vc59 LIVE.
  * `pid-start.txt`: `30902` (single line). `apk-sha.txt`: `0be66f3a…c039` + `87481971`.
  * `telemetry-pull1-preinstall.jsonl` 4866 `9d9e9d955a5172d41ce7345df6670cb9e444cdd54c80f804232ec75a091d8203` (vc58 tail, `L4865 seq378 qualityA="none"`).
  * `telemetry-pull2-postinstall.jsonl` 4866 same SHA (byte-identical; install appended 0 lines at pull time).
  * `telemetry-pull3-neverletgo-sad.jsonl` 4927 `85de964cc68b192c0e6b64bcf456b9058d49cc140a686f5b4caea36951f3d46d` (vc59: `L4867 seq1` boot → `L4868 seq2 Never→S.A.D full/full 104.495→66.484` → `L4902-4904 seq36-38` anchor-flip → `L4910-4914 seq44-48` S.A.D→LAST GOODBYE fire `+41 ms`).
  * `media_session-before/postinstall/after-p1` + `dumpsys-before/after-install` (vc59 installed, PAUSED snapshots). No `pull4` — adb empty at handover check, skipped per protocol.
* Older pulls: `djmix14-proof/`, `djmix15-proof/`, `djmix16-proof/` (see `BITCHORD_AUTOMIX_HANDOVER_LIVE.md` LIVE tail; per-build pull SHAs there). `mix.exit.violated` grep = 0 in every pull.

## §6 Exact Next-Session Diagnosis & 15-Minute Fix Plan (Literal `Never Let Go → S.A.D`)

### What `vc59 seq34..39 → seq39 plain advance` proves

`seq34..38` (`mix.plan`, `qualityA="full"`, `qualityB="full"`, `bpmA=104.495 bpmB=66.484`, fade `4.59/9.13`, `DJ_FILTER`) then `seq39 mix.plan` (S.A.D→next pair) with **ZERO `mix.loop.fallback` and ZERO `mix.arm.death` to `193,641 ms` EOF**. Since vc59 now logs both on *every* `beginLoop`/`begin()` guard failure, neither function was ever reached on the literal pair — the remaining gate is upstream of both, in `CrossfadeController.kt:1567–1785`:

1. **Rendition-duration mismatch (primary suspect, `CrossfadeController:1596,1652` + `LoopMix.kt:369 OUT`):** `loop.out.loopStart` is chosen as greatest clean loop with `outEnd=outDuration-0.5` (`outDuration` from cached `TrackAnalysis.durationMs`). If the cached analysis came from a longer rendition (e.g. ~215–225s MV) than the playing `193.641s` stream, `loopStartMs>214,000 ms > player.duration 193,641 ms`. Then every tick: `shouldYieldColdLoop=false` when `fastReady||building` (or `remainingMs` still > deadline), and `CrossfadeController:1652 if(loopStartMs-pos>blendArmLeadMs(20-35s)) return` returns before `beginLoop` — classic never arms, ExoPlayer dry-advances at `193,641 ms`. Single-shot check on `bk-android` (2026-11-13): `LoopMix.kt:369 outEnd=outDuration-0.5` has no clamp to playable `duration`; `considerSmartTransition:1596 loopStartMs=(loop.out.loopStart*1000).roundToLong()` likewise never clamps to `duration`. Exact lines: `CrossfadeController.kt:1596-1652`, `LoopMix.kt:369`.
2. **`if(plan.blocked||markerOnly) return (:1724)` (secondary):** if `loop` nulled post-`seq34`, only unlogged gates before `begin()` are `:1724` (`markerOnly`, e.g. `DirectSourceGate`/`MusicOnlyPlayback` marker path `considerSmartTransition(markerOnly=true)` from idle tick) and `:1783 remaining>effectiveLead+ramp`. In `pull3`, `seq34..38` are plans (not blocked) so `:1724` would need a `markerOnly=true` tick to be the thief; grep next session for `media_session` `DirectSourceGate` state at `193s`.

### 15-minute fix plan (do in order, no playback until green)

1. Clamp: in `loopPlanFor`/`nativeBridge` after sticky selection, `if(plan.out.loopStart*1000 > durationMs-STEP) reject plan (return null)`. (2 min, 1 file)
2. Same clamp defensively at `beginLoop` entry: `if(loopTargetStartMs>durationMs) { loopFallBack("loop-start-beyond-duration"); return false }`. (1 min; reuses existing fallback telemetry — now emits, proving the hole.)
3. Add `mix.diag.rendition` event at `:1596`: `outDurationMs/durationMs/loopStartMs/planRung` (2 min).
4. Unit: `LoopMix` clamp + `Djmix17RegressionTest` case `loopStart>duration → no loop`. (3 min)
5. `Djmix18` build `vc60` + `install -r` + flagship literal-pair proof + scrub proofs. (7 min)
6. **User internal-audio verification** (user-only, not AI): screen-record internal audio of literal handoff; verdict on `<50 ms` gap, `>=300 ms` dissolve, musical taste. Telemetry alone cannot close this.

### Next-session opening checklist

* `[WAITING ADB]` — check `adb devices -l`; if empty, report offline, inspect `pull3` offline only.
* Re-anchor queue truth: `adb shell dumpsys media_session` + `/sdcard/ui_queue.xml` (shuffle ON, `Never Let Go` idx varies).
* Flagship: `Never Let Go (104.495, 193,641ms) → S.A.D (66.484)` natural play to EOF — expect `mix.fire` (classic or loop) + `mix.exit.tail` + `mix.complete handoff=true`.
* Then `S.A.D → next` natural + `CANCEL` (FF+rewind) + `EMERGENCY_TAIL` (navigate to FADING then seek) + clean reset PAUSED `Never Let Go` 0ms.

## Appendix: Handover Check (Task 13-STOP)

* `adb devices -l` at 2026-11-13 handover: **empty** (List of devices attached, 0 lines) → **no pause/pull sent**, 0 connect attempts (protocol-compliant). Phone state unknown while offline; last known = vc59 installed, `pid 30902`.
* `bk-android 1567–1785` single-shot: confirmed no `loopStart>duration` clamp (`LoopMix.kt:369`, `CrossfadeController.kt:1596/1652`); `:1724 markerOnly` gate noted. See §6.
* Files written: `/home/hfzhkn/BITCHORD_HANDOVER_DETAILED.md` (this file) + `/home/hfzhkn/BITCHORD_HANDOVER_DJMIX17.md` appended + milestone appended to `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` (prior lines + DJMIX13/14/15/16 untouched).
