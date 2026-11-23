# BitChord DJMIX16 Handover — 2026-11-13 (supersedes brief snapshots; live log remains the append-only diary)

## Read first / honest state in 5 lines

> **Phone has 2.2.2-djmix16 vc58 LIVE (in-place reinstall Success 2026-11-13 04:24:28, `dumpsys`-verified `versionCode=58`; pid 7842 steady, zero BitChord crashes). The djmix16 gates now OPEN: Bug 1 machinery is [PROVEN ON DEVICE TELEMETRY] on three analysis-ready pairs (`fallback insufficient-cold-lead ×5` → classic fires `+211/+221/+122 cue 0` → `planned-fade-done` ×2 → `handoff=true` ×2); catch-up re-anchor is PROVEN (`planned 148,462` ahead of scrub `147,520`, fires `+221`); `CANCEL` is PROVEN (94 cancels, 0 ghost fires); Bug 2 hygiene holds (zero `116245`/`374320`/`ms over`); `EMERGENCY_TAIL` machinery observed (`seek-emergency-tail 800 ms`, forward-seek) but canonical backward-rewind `handoff=true` NOT staged. The literal `Never Let Go (104.49) → S.A.D (66.48)` pair STILL plain-advances without fire (45 plans, `qualityA="none"` throughout) — the wall is now isolated to streamed-track full-rendition analysis readiness, not the gates. `LOOP_ARMING` never engaged (no loop built), so Bug 3's pre-takeover silence is UNEXERCISED. No agent has "heard" any fix — audio proof needs a user internal-audio recording. Next step is strictly: (a) user internal-audio recording + listening verdict, then (b) code work on `qualityA=none` full-rendition fetch.**

Status tags in this doc: **[PROVEN]** = verified on disk/device telemetry this cycle; **[UNPROVEN]** = coded/tested/build-passing but never observed on device audio. Never upgrade an `[UNPROVEN]` to proven without telemetry + a fresh internal-audio capture. This file supersedes the same-day offline `DJMIX16.md` (written when ADB was down); the offline branch is preserved in the live log.

---

## 1. App / identity [PROVEN]

| Item | Value |
|---|---|
| App name | BitChord |
| Package | `com.music.bitchord.loopfix` |
| ABI | `arm64-v8a` only (verified in every APK; djmix16 `unzip -l` native libs are distinct `arm64-v8a` only) |
| Device | POCO peridot `24069PC21G`, Android 16 |
| Signing | **oldkey only**, cert SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`, DN `CN=BitChord loop fix` — **never generate a new key**. Local shell has no `aapt`/`apksigner`, so djmix16 cert/package re-verification remains per Tasks 8–9 cloud logs; locally re-verified are SHA-256 + size + arm64-only natives + patch `wc -l`/`sha256sum` |
| Install rule | In-place only: `adb install -r`, **no uninstall / no data clear** (would lose per-user state + telemetry) |
| Debuggable | No. User is awake at the phone (screen unlocked, `LauncherDefault` foreground) this cycle; introspection is via telemetry JSONL + dumpsys + logcat, not a debugger. |
| Live diary | `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` (append milestones; do not rewrite history) |
| This file | `/home/hfzhkn/BITCHORD_HANDOVER_DJMIX16.md` (new 2026-11-13; old handovers preserved untouched) |

---

## 2. Current state + version table

### 2.1 What is on the phone vs on disk

- **INSTALLED on phone: 2.2.2-djmix16 vc58** [PROVEN at install time 2026-11-13 04:24:28, `djmix16-proof/PROOF-REPORT.md` + `dumpsys-after-install.txt`/`dumpsys-after.txt`; pid 7842 steady, zero BitChord crashes; left **PAUSED at `Never Let Go` idx0, 0 ms**]. Installed via wireless ADB serial `adb-1439ebea-05jQT0._adb-tls-connect._tcp` (both mDNS records were already `device` on arrival; no `adb connect` needed).
- **BUILT, now installed: 2.2.2-djmix16 vc58** [PROVEN installed]. Code+build milestone is `MILESTONE djmix16 CODE & BUILD (Tasks 8–9)`; the offline branch is `MILESTONE djmix16 CODE & BUILD (Tasks 8–9) … BLOCKED (wireless ADB offline)`; device proofs are `MILESTONE djmix16 DEVICE PROOF (Task 10 online)`.
- **LoopMix still never fired on vc58** (0 `LOOP_ARMING` / `LOOP_RUNNING` phases in 374 lines; every loop plan fell back from `IDLE`). Classic `planned-fade-done` fired **2×** on vc58 (on top of vc56's 2× + vc57's 2×). Scrub `CANCEL` (94×) + `EMERGENCY_TAIL` machinery (forward-seek) live-observed; canonical backward-rewind 3B not staged. Queue left dirty by design: `Never idx0 → S.A.D idx1 → Back To Strangers idx2 …` (shuffle cycle B), `Never` PAUSED at 0 ms.

### 2.2 Version table (all hashes + sizes re-verified on disk 2026-11-13)

| Release | versionName / vc | APK path | SHA-256 | Size | Installed? |
|---|---|---|---|---|---|
| djmix8 | 1.9.1 / vc50* | `/home/hfzhkn/Downloads/bitchord-loopfix-1.9.1-djmix8-arm64-v8a-oldkey.apk` | `4e7c4a0005a161eb5ffa00af3cd6ec92248933b968a3d85240e7850063aefd38` | 87,582,011 B | No (preserved) |
| djmix9 | 1.9.1 / vc51 | `/home/hfzhkn/Downloads/bitchord-1.9.1-djmix9-automix-arm64-v8a-oldkey.apk` | `b0af10490acefc85c2be30376e7e6aade973b9c8d0e21d2ffdc4327deb681c47` | 87,573,558 B | No (preserved) |
| djmix10 | 2.0.0 / vc52 | `/home/hfzhkn/Downloads/bitchord-2.0.0-djmix10-automix-arm64-v8a-oldkey.apk` | `82ebbbf5dec8b5194889cb8df450433ba09b1adbf8820635663a8cd875fe1889` | 87,465,587 B | No (preserved) |
| djmix11 | 2.0.1 / vc53 | `/home/hfzhkn/Downloads/bitchord-2.0.1-djmix11-automix-arm64-v8a-oldkey.apk` | `357e281d34e9f7222b2fd43196eaec0733b8db717c2c6f43aee8444074bdf029` | 87,465,587 B | Superseded (was on phone) |
| djmix12 | 2.1.0 / vc54 | `/home/hfzhkn/Downloads/bitchord-2.1.0-djmix12-automix-arm64-v8a-oldkey.apk` | `a25900e9647534511b36fdb70abfc914b1fb5eaceb1010b0a7660f4848a1eb8d` | 87,465,587 B | Superseded (was on phone) |
| djmix13 | 2.1.1 / vc55 | `/home/hfzhkn/Downloads/bitchord-2.1.1-djmix13-automix-arm64-v8a-oldkey.apk` | `2237caa1dc72b4afb52c2633585debd8cb5cbe8f6e75fd40a806fedd8dd69f9b` | 87,465,583 B | Superseded (was on phone) |
| djmix14 | 2.2.0 / vc56 | `/home/hfzhkn/Downloads/bitchord-2.2.0-djmix14-automix-arm64-v8a-oldkey.apk` | `dd8d49967f0826c5e9330368f4130a642a39686bb47b78216ce64828ba19f915` | 87,465,587 B | Superseded by vc57 (was on phone until ~00:55) |
| djmix15 | 2.2.1 / vc57 | `/home/hfzhkn/Downloads/bitchord-2.2.1-djmix15-automix-arm64-v8a-oldkey.apk` | `ca90837f1528df8a749f88b3fe07ba663c8cf522692960da86fd04bdb950057c` | 87,481,971 B | Superseded by vc58 (was on phone until 04:24) |
| **djmix16** | **2.2.2 / vc58** | `/home/hfzhkn/Downloads/bitchord-2.2.2-djmix16-automix-arm64-v8a-oldkey.apk` | `a9e80e71248c4899ff58190a0742621f40a6ab81c02b2135a0a3f41403a44010` | **87,481,967 B** | **YES — current on phone** |

* djmix8 vc inferred (pre-brief); preserved as rollback target. All APKs preserved — never delete; each is the rollback target for its generation.

---

## 3. Bug history / evidence (proven vs unproven)

### 3.1 The day-one bug — measured, not anecdotal [PROVEN measurement]

- **Symptom (user):** outgoing track cuts "like pause". **UI rung at the hole:** `Smooth fade · 3s · 106→109 · locked`, RELAXED, at **1:19 / 2:13** on "amoureux" — Alleh.
- **Measurement** (`/home/hfzhkn/bitchord-device-evidence/video-VID_20261112_151114/FINDINGS.md`): **~15 ms effective fade-out → 258.2 ms bit-exact silence** (239.1 ms exact-zero @ 0.1987–0.4378 s; sub-−40 dB 282 ms) **→ ~20 ms fade-in**. Graceful target is 300–800 ms. One event only.
- **Root causes (code, [PROVEN]):**
  1. `TransitionMoves` RELAXED branch: `outGain` lane **flat at unity** through the landing.
  2. Native `renderDeck` **hard stop at `stopAt`, no ramp**.
  3. Classic `BAIL_MS` 120 ms path retired the outgoing deck **instantly**.
  4. `LoopMixSession` region re-decode over network routinely exceeded `LOOP_ARM_LEAD` (297× `outgoing decode 17203 ms over`, later 145× `stream 17 ms over` + 101× `splice 49012 ms over`).
  5. `phrase-null` starvation (1,849 lifetime) + head-vs-full analysis race.
  6. Deck deadlines mixed wall/media epochs → bogus overruns (`374320 / 388005 ms over`).
  7. Scrub past `transitionStart` → shown-countdown `0s` decoupled from execution → stuck `0s` dry play-to-EOF (19:25:50 video).

### 3.2 Architectural upgrades by release

- **djmix11 (vc53):** `withExitTail` single choke point + graceful-exit dissolve + exit-gate backstop + `Phase.TAIL` (800/350 ms). Crash-proven, audio-unproven.
- **djmix12 (vc54):** retire guard → `mix.exit.guard` + `mix.exit.violated` invariant + classic `mix.plan.exitTailSec` once per verdict + `mix.complete` in `finish()`.
- **djmix13 (vc55):** `DeckBudget` pause-proof deadlines + `lateStepReason` proven-only + `SeekAction CANCEL/HONOUR/EMERGENCY_TAIL` + `mix.fire {deltaMs,cueErrorMs}`.
- **djmix14 (vc56):** `CatchUpPlanner` + `LoopRegionCache` + `CrossfadeController` scrub reset + prime 40 s + sticky clear + memo/dedup + 2-thread analyzer. **First `planned-fade-done` ×2** (`+22,717/+20,090`).
- **djmix15 (vc57):** ungated priming (25 s window); `RetireGuard` loop scalars → `loop-complete`; `NO_DEADLINE` sentinel + 120 s horizon; `isNextEdit(handedOff,…)` queue-edit gate; playhead-anchored catch-up.
- **djmix16 (vc58):** (1a) `CatchUpPins` pin-once (kills the moving carrot); (1b) `LoopMix.shouldYieldColdLoop` `max(8.5 s, blendspan, 25 s while warming)` + `loopSuppressedPair` + `discardPrime`; (1c) `LOOP_ARMING` null-build yields immediately; **Bug 2:** 40 s horizon + wall-only splice step; **Bug 3:** `loopArmingSpareAudible` + pre-takeover silence. See §4.

### 3.3 What vc58 proves, what it does not [PROVEN / UNPROVEN]

- **[PROVEN]** In-place install, pid steady, zero BitChord crashes.
- **[PROVEN]** Bug 1 machinery on full/full pairs: `Setsuna→BTS` (`fallback` → `fire +211 cue 0` → `planned-fade-done` → `handoff=true`) and `S.A.D→BTS` (`fire +122` → `planned-fade-done` → `handoff=true`), plus `BTS→DEEP` catch-up (`planned 148,462` +942 ahead of scrub, `fire +221`).
- **[PROVEN]** `CANCEL` (94 cancels, 0 ghost fires). Bug 2 hygiene (zero `ms over`).
- **[UNPROVEN]** Literal `Never Let Go → S.A.D` (104.49→66.48) still plain-advances without fire — **same wall, isolated**: `qualityA="none"` throughout (§5). djmix16 opened the gates; arming is starved by streamed analysis readiness.
- **[UNPROVEN]** Bug 3's `LOOP_ARMING` silence — zero `LOOP_ARMING` phases (no loop built). Zero `emergency-retire-guard` tails is absence, not exercised fix.
- **[UNPROVEN]** Canonical backward-rewind `EMERGENCY_TAIL handoff=true`. Machinery observed via forward-seek only.
- **[UNPROVEN]** `Beautiful → Never` LoopMix (no loop fire on vc58; `Beautiful` absent from queue) + acoustic `<50 ms` hole + taste/beat-match. Telemetry proves the state machine; the envelope has not been heard.

---

## 4. Code & build provenance (Tasks 8–9) [PROVEN on disk]

| Item | Value |
|---|---|
| Delta patch | `/home/hfzhkn/bitchord-djmix16.patch` (683 lines, 7 files, `sha256 f0d08d4f34808c801a179525d6bd90676ecca35315ec82c9ed4b6f93d59086c7`) over djmix15 worktree |
| Full patch | `/home/hfzhkn/bitchord-djmix16-full.patch` (5545 lines, 42 files, `sha256 f2143e393139de281c1d8b1dbaa1427bd8d9dc214253020f3a4e402bdd2471fc`) from `dc78e0d` |
| Files (delta) | `CrossfadeController.kt`, `build.gradle.kts` (`vc58` / `2.2.2-djmix16`), `CatchUpPlanner.kt` (+`CatchUpPins`), `DeckBudget.kt` (+`LOOP_BUDGET_HORIZON_MS`), `LoopMix.kt` (`shouldYieldColdLoop` + horizon-aware `lateStepReason`), `RetireGuard.kt` (`loopArmingSpareAudible`), `Djmix16RegressionTest.kt` (new; `grep -c "^diff --git"` = 7 / 42) |
| Tests | `Djmix16RegressionTest` (Bug 1a pin once/reuse/drop, Bug 1b cold-yield lead, Bug 2 wall-only incl. literal `116245` stale case, Bug 3 pre-takeover-never-tails) + all suites green per cloud logs |

Fix key (Bug 1/2/3 = vc57 residuals):

1. **Bug 1a — moving carrot:** `CatchUpPins.key/reuse/dropOnNonCatchUp` — any `:catch-up` plan for `(pair, scrubEpoch)` is pinned ONCE and reused every tick; `shouldStart` still flips at the pinned start. Proven: `planned 148,462` ahead of scrub, fires `+221`.
2. **Bug 1b — cold yield:** `LoopMix.shouldYieldColdLoop` — cold loop yields within `max(8.5 s, blendArmLead, 25 s while next warms)`; caller suppresses pair + `discardPrime()`. Proven: 5× `fallback insufficient-cold-lead` then classic fires.
3. **Bug 2 — wall is only compared to wall:** `LOOP_BUDGET_HORIZON_MS = 40_000` (was 120 s) + horizon-aware `provenOverrunMs(ended,deadline,now)` on per-span and final paths + wall-only splice step. Proven by absence (0 `ms over`).
4. **Bug 3 — pre-takeover silence:** `RetireGuard.loopArmingSpareAudible/canRetireLoopArmingSpare` + `finish()`/`bail()` pre-takeover silence (`volume=0`, `playWhenReady=false`). Held by absence (0 guard tails) but **unexercised** (no `LOOP_ARMING` entered).

---

## 5. Root-cause map (djmix14–djmix16)

| # | Failure | Root cause (code file) | Fixed in | Status |
|---|---|---|---|---|
| F1 | `0s → nothing`: countdown decoupled from execution; sticky-loop deadlock; watchdog blindness after scrub | `CrossfadeController.describeAutomix` + `loopPlanFor Hold.KEEP` + cleared `shownCountdownKey` + dual arming clocks | djmix13–14 | **[PROVEN]** catch-up + natural classics fire and complete on vc56/vc57/vc58 |
| F2 | Scrub flaws: mid-blend desync; stale countdown ghosts; abrupt envelope; no catch-up at EOF | `onPositionDiscontinuity` → `bail()` freezing `ARMING`/`FADING`; `O_MIN_SEC 6.0` + `PLAN_LEAD 60 s` + 40 ms timeout → phrase-null 1849 | djmix13–14 | **[PROVEN]** `CANCEL` 94× + catch-up `planned-fade-done` on vc58 |
| F3a | Moving-carrot catch-up (`fadeSec 4.59→3.99→2.26→1.2`, `remaining` stuck +900 ms) | `pos <= start+400` pin expiry + recompute as `playhead + lead` | **djmix16 (`CatchUpPins`)** | **[PROVEN]** on vc58 (pinned ahead, `+221` catch-up fire) |
| F3b | Cold loop starves classic (yield at 4.5 s left <4 s to buffer cold standby; `LOOP_ARMING` retry blocked `begin()`) | `<4.5 s` gate + 400 ms-only yield + complete-rendition requirement vs. never-downloaded streamed file | **djmix16 (`shouldYieldColdLoop` + immediate yield)** | **[PROVEN machinery] / [BLOCKED literal pair]**: 5 fallbacks + 3 fires on full/full pairs; literal 104→66 starved by `qualityA=none` upstream of the gate |
| F4 | `374320` then `116245 ms over` wall-minus-media | Wall clock minus media positions; `0L` sentinel; 120 s horizon; span path without horizon | djmix15 + **djmix16 (40 s horizon + wall-only splice)** | **[HYGIENE PROVEN]** zero `ms over` on vc58 |
| F5 | `finish:LOOP_ARMING emergency-retire-guard` 800 ms tail ×8 | Prepared-but-silent spare (`volume=1.0`) read as audible via guard | **djmix16 (`loopArmingSpareAudible` + silence)** | **[UNEXERCISED]** zero `LOOP_ARMING` entered; zero tails by absence |
| F6 | Taste/DSP: loop early-fire (−546…−787), classic +27…+221; missing limiter/vocal/transient/reverb | Single-clock native vs dual-Exo classic; incomplete DSP stack | Open | **[UNPROVEN]** needs user ears |

---

## 6. Device proof summary [PROVEN ON DEVICE TELEMETRY — exact seq lines]

Full seq-by-seq tables are in `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/PROOF-REPORT.md` (this sprint). Headlines (all in `telemetry-pull8-final.jsonl`, L4488–L4862 = vc58 seq1–375; `vc57-ext` = 199 user-played lines L4289–L4487):

- **6.1 Proof 1' (Never→Setsuna, adapted):** idx0 `Never Let Go` (PLAYING 0) → 10× FF to `164,001` → hands-off 55 s → idx1 `Setsuna Hanabi` at `24,091`. Telemetry seq2–97: **39 plans, 34 rejected, 22 seeks, ZERO fire/complete/tail/fallback**. Plain advance. `qualityA="none"` throughout.
- **6.2 Proof 2' (Setsuna→BTS):** L4605 `seq118 fallback insufficient-cold-lead` → L4610 `seq123 fire classic planned 169,249 actual 169,460 +211 cue 0` → L4611 `seq124 tail planned-fade-done` → L4612 `seq125 complete FADING handoff=true 118.45/85.24`. **Bug 1 machinery CLOSED for full/full pairs.**
- **6.3 Proof 3A' (BTS→DEEP):** L4626 `seq139 fallback` → L4628 `seq142 complete ARMING finish` (zero guard) → L4631 `seq144 fire planned 148,462 actual 148,683 +221 cue 0` (catch-up re-anchor +942 ahead of scrub `147,520`, no carrot) → L4632 `seq145 FADING emergency_tail seek` → L4633 `seq146 tail seek-emergency-tail 800` → L4634 `seq147 complete TAIL handoff=false` (forward-seek to EOF).
- **6.4 CANCEL (Never idx0):** 7× FF → `128,554`, 8× rewind → `97,221`, wait 10 s → `106,246`. Telemetry seq161–223: **15 `cancel` (IDLE), zero fire/complete**. Playhead preserved. Session total: **94 cancel seeks, 0 ghost fires**.
- **6.5 S.A.D→BTS (late-scrub, mid-session):** L4766 `seq279 fallback` → L4768 `seq281 fire planned 184,352 actual 184,474 +122 cue 0` → L4769 `seq282 tail planned-fade-done` → L4770 `seq283 complete handoff=true 66.48/85.24`. Third firing pair.
- **6.6 Literal Never→S.A.D (idx0→idx1):** 10× FF → `159,641`, hands-off 55 s → idx1 `S.A.D` at `21,503`. Telemetry seq325–369 (45 lines): plans M6/classic `fadeSec 9.13→4.59`, **`qualityA="none"` throughout**, **zero fire/fallback/tail/complete**. Plain advance. **Bug 1 NOT closed on the literal pair.**
- **6.7 vc57-extended accuracy (context, not vc58):** 10 fires including `+27/+39/+41/+71/+72/+88/+95/+103/+154` (classic, cue 0) + loop `−546`. Best classic accuracy so far: **`+27`**.
- **Health:** pid `7842` steady (`pid-start == pid-end`); crash 0 BitChord (158,159 lines); `exit-info` only `PACKAGE UPDATED` + pre-existing entries; `dumpsys-after.txt` vc58; `media_session-final.txt` PAUSED `Never Let Go` idx0 0 ms.

**`mix.fire` accuracy:** vc58 classic **+122/+211/+221** (all `cueErrorMs=0`); no loop fired. vc57-ext best classic `+27/+39/+41`. No fire meets the ±25 ms ideal; closest is `+27` (vc57-ext). Catch-up lateness fell from `+22,717/+20,090` (vc56) to `+221` (vc58).

**vc55 → vc56 → vc57 → vc58 comparison:** PROOF-REPORT §9 has the full table. Salient: vc58 fires 3 in 374 lines; `violated` 0 everywhere (13 lifetime gates all `0`); `freeze.queueEdit` 0 on vc57+vc58; `116245/374320` 0 on vc58 (no `ms over` at all); `planned-fade-done` 2 on vc58 (+ 1 `seek-emergency-tail`).

---

## 7. Honesty Box & method

**[PROVEN ON DEVICE TELEMETRY]** (PROOF-REPORT §12): items in §3.3 / §6 above. **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**: acoustic `<50 ms` hole + dissolve audibility + beat-match/taste — still open (no agent has "heard" any fix; `adb screenrecord` is mic-only and no screenrecord was taken in any cycle).

**vc58 status:** machinery + hygiene + catch-up + CANCEL proven; **literal pair + LOOP_ARMING + canonical 3B + loop fire = [NOT PROVEN]** (not failed for the gates; blocked by `qualityA=none` / never-built / unstageable respectively).

**Method constraints (same as vc56/vc57):** UI is live this cycle (user awake) but the agent never tapped the app (only `input tap` on `Up next` at the start to map the queue, `uiautomator dump`, media dispatch, dumpsys, pulls). All seeks via `cmd media_session dispatch FF (+15 s)/RW (−5 s)` (verified `DISCONTINUITY_REASON_SEEK`); hands-off single waits (no polling loops); all positions are `dumpsys media_session` readings.

---

## 8. Residual gaps (do not oversell)

### 8.1 `Never Let Go → S.A.D` — STILL OPEN (isolated to `qualityA="none"`)

- 45 plans for 104.49→66.48, zero fires. The outgoing streamed `Never` never reaches `full` analysis (`qualityA="none"` throughout), so neither `armLoop` nor classic `begin()` ever fires. djmix16's gates are proven open; the planner has nothing to arm. Root cause is now: `TrackAnalyzer.loopRegion` requires a COMPLETE rendition (`chooseRendition` `isComplete`), but the DB-cached streamed analysis never triggers the fetch inside the 40 s prime window → 2 ms null returns forever. Follow-up needs an explicit full-rendition fetch for streamed outgoing tracks (or a bounded-quality fallback arming path).

### 8.2 `LOOP_ARMING` never entered on vc58

- Every loop plan fell back from `IDLE` before arming (cold next track each time). Bug 3's silence path is untested; a future vc59 must first force a loop build (analysis-ready next track + complete rendition) to exercise it. Zero `emergency-retire-guard` tails is consistent with both hypotheses.

### 8.3 Canonical backward-rewind `EMERGENCY_TAIL handoff=true` not staged

- Catch-up fires land ~1–2 s after the FF storm's last seek; multi-FF + calculated waits overshoot `FADING`. Forward-seek machinery is proven; the canonical 3B needs a slower staging (single FF landing just before `plannedStart`, rewinding within a 2–3 s window).

### 8.4 Loop early-fire + taste

- Loop `−546…−787` and classic `+27…+221` are not ±25 ms (best overall `+27`, vc57-ext). Tamper-proof for handoff, but beat-match/taste cannot be claimed from telemetry.

---

## 9. Next steps (strict order)

1. **User internal-audio recording + listening verdict (Definition of Done).** Phone is **PAUSED at `Never Let Go` idx0, 0 ms** (`media_session-final.txt`). Unlock phone, start internal-audio screen recording (internal audio ON, as in `VID_20261112_151114`), play `Never Let Go (LNGSHOT) → S.A.D (XG)` (the literal pair, consecutive in the current shuffle cycle) or any full/full pair (e.g. `S.A.D → Back To Strangers`), stop after Song 2 settles, send the mp4. Gate check: silence gap < 50 ms + dissolve ≥ 300 ms on the envelope + explicit taste/beat-match verdict. If the gate fails, file the mp4 + telemetry pull and reopen the relevant Fix (§5/§8).
2. **In-place protocol (no uninstall/clear):** `adb install -r` only; pull telemetry **before** any install into a new sequential directory; keep `djmix14-proof/`, `djmix15-proof/`, `djmix16-proof/` 100% untouched; `-s adb-1439ebea-05jQT0._adb-tls-connect._tcp` after `adb mdns services` + `adb devices -l` re-verify (port flips on wireless-debugging toggle; both records were `device` this cycle).
3. **Forensics for `qualityA=none` on streamed `Never`:** full-rendition fetch timing vs. the 40 s prime window; `TrackAnalyzer` complete-rendition gate; whether a bounded-quality arm path is acceptable. Patch on cloud (`bk-android`) tree, **never locally**.
4. **Loop-exercise plan for Bug 3:** build a loop (`LOOP_ARMING` entry) on an analysis-ready pair, then abort pre-takeover and require zero guard tail. Only then can Bug 3 be called closed.
5. **Canonical 3B restaging:** single FF landing just before `plannedStart`, rewind within 2–3 s, require `handoff=true`.
6. **Regression suite:** keep `Djmix16RegressionTest` in the gate; do not ship a build that breaks it.
7. **Do not claim audio success from telemetry.** Burn-in satisfaction still requires the ears.

---

## 10. File inventory

### 10.1 APKs & patches — canonical

APKs all under `/home/hfzhkn/Downloads/` (9 preserved: djmix8–djmix16). Patches:

- `/home/hfzhkn/bitchord-djmix16.patch` (683 lines, 7 files, `f0d08d4f…`).
- `/home/hfzhkn/bitchord-djmix16-full.patch` (5545 lines, 42 files, `f2143e39…`).
- Earlier: `/home/hfzhkn/bitchord-djmix15.patch` (855 lines, 10 files, `67a95fa6…`), `/home/hfzhkn/bitchord-djmix15-full.patch` (5045 lines, 41 files, `a98173f7…`), `/home/hfzhkn/bitchord-djmix14.patch` (2108 lines, 19 files, `dbdbbb0a…`), `/home/hfzhkn/bitchord-djmix14-full.patch` (3986 lines, 35 files, `f645c223…`), plus all djmix9–djmix13 patches (see LIVE).

### 10.2 Evidence (djmix16)

| Path | Role |
|---|---|
| `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/PROOF-REPORT.md` | **Primary honesty-graded report** (this sprint). |
| `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/telemetry-pull1-preinstall.jsonl`–`pull8-final.jsonl` | Sequential pulls, never overwritten (4487→4862; vc58 boundary L4488). Hashes in §10.3. |
| `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/dumpsys-before.txt`, `dumpsys-after-install.txt`, `dumpsys-after.txt` | Package version proof (vc57→vc58). |
| `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/media_session-*.txt` | Position/queue proof (idx0–idx3 chain; final PAUSED `Never Let Go` idx0 0 ms). |
| `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/install.log`, `pid-*.txt`, `crash-log*.txt`, `exit-info.txt` | Health proof (pid 7842 steady; 0 BitChord crashes, 158k lines). |
| `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/adb-mdns.txt`, `adb-devices-before.txt`, `adb-connect.log`, `apk-sha.txt` | Reproducibility log (offline attempt + online harvest). |
| `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/adb-mdns-live.txt`, `adb-devices-live.txt` | Online harvest (39761 live + 40601 ghost both advertising, `adb devices -l` both `device`); offline files preserved untouched; identical content sha `cfcfa16a…` / `8232b781…`. |
| `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/media_session-live-state.txt`, `media_session-live-state2.txt` | UNKNOWN provenance snapshots (user/parallel session?), preserved, not proof inputs. |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-proof/*`, `djmix15-proof/*` | Untouched references (file counts 53/40; SHAs re-verified). |

### 10.3 Pull hashes (djmix16)

| Pull | Lines | SHA-256 |
|---|---|---|
| pull1 (pre-install, vc57) | 4487 | `32fb8672292bb3fb848b89c11fd61d95c351db63b63f840a59b968d9a5220e9b` |
| pull2 (post-install, vc58 boundary L4488) | 4489 | `74a3e24068fff0787c013f0b1d58026332179ff4d1947b7e499d7b00aa3c84ef` |
| pull3 (Never→Setsuna plain advance) | 4584 | `3dbdbdae526309fbcd07158e9381e8d9d68724b89f545c146bc88714dd3d31ad` |
| pull4 (Setsuna→BTS handoff) | 4614 | `5e54d7c5666b11ca97e43727b796c04e1415a8d3cd9a9f64cff121a4e494d855` |
| pull5 (catch-up + ARMING abort) | 4647 | `ed6a1b1cf1f605577c1c17660fe3b7cdd9913b9368960661012183223862ac9d` |
| pull6 (CANCEL on Never) | 4710 | `069681db6d787fc05f78160346c0a9637c7574f7d879d430cf13d57320ab83e4` |
| pull7 (rewind attempts, S.A.D idx1) | 4758 | `2016af17a55c18ced91cde3f9a67e628989c74895ee90fca39f0872388ed241b` |
| pull8 (final) | 4862 | `13d2e51922389e646b509119f7abce3d47ee883200d77e7c160246f5fbda00f9` |

### 10.4 Reports (primary)

| Path | Verdict |
|---|---|
| `/home/hfzhkn/bitchord-device-evidence/video-VID_20261112_151114/FINDINGS.md` | Day-one hole measured: 258.2 ms silence, ~15–20 ms fades. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-forensics/FORENSICS.md` | "(2)" = byte-identical pre-fix video; 586:15 rejection audit; BK portable traits. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-proof/PROOF-REPORT.md` | **PARTIAL**: guard proven; `planned-fade-done` never fired. |
| `/home/hfzhkn/bitchord-device-evidence/djmix13-proof/PROOF-REPORT.md` | **INTERIM** — build delivered, install pending (listener down). |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-forensics/FORENSICS-DJMIX14.md` | **Bug Classes 1–4 + video correlation** (3750-line vc55 baseline; plan→fire 2.2%, 0 `planned-fade-done`). |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-proof/PROOF-REPORT.md` | **CLASSIC `planned-fade-done` ×2 + Loop + B2 + B3 [PROVEN]** (pulls 3762→3980, pid 1373). |
| `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/PROOF-REPORT.md` | **Loop `loop-complete` + re-anchor + accuracy + B2/B3 [PROVEN]; NLG→S.A.D + 116245 noted** (pulls 3984→4288, pid 32159). |
| `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/PROOF-REPORT.md` | **Bug 1 machinery + catch-up + CANCEL [PROVEN]; literal NLG→SAD (`qualityA=none`) + LOOP_ARMING + canonical 3B noted** (pulls 4487→4862, pid 7842). |
| `/home/hfzhkn/bitchord-device-evidence/djmix11-proof-20261112T0922Z/ANALYSIS.md` | djmix11 telemetry pass. |

### 10.5 Source / reference

| Path | Role |
|---|---|
| `bk-android:/data/workspace/bitchord-task2/work/BitChord` @ `dc78e0d` (+ staged + uncommitted deltas) | **BitChord source of truth** (box only). |
| `/home/hfzhkn/bkmasterplayer/app/rust/src/engine/{analysis,automix,mixer,deck}.rs` | BK reference only. |

---

## 11. Corrections found on disk vs the commissioning brief (2026-11-13 audit)

This handover re-verified everything below on disk before writing — corrections applied:

1. **Offline branch superseded.** The brief's detailed Phases 1–4 assumed a connected device. mDNS advertised `192.168.100.218:40601` but refused the connection — per the brief's own Critical Rule 1, an offline `DJMIX16.md` was first written. When the user toggled wireless debugging ON, both mDNS records were already `device` and the full online program ran. This file is the canonical handover; the offline branch remains in the live log.
2. **Queue premise expired.** The brief's `Beautiful idx0 → Never idx1 → S.A.D idx2 …` chain never existed in this task: `dumpsys-before` showed the user replaced the queue with `Playing from Recents` (`Setsuna Hanabi idx4`, 17 items). No queue edits were made (no delete/enqueue/reorder); proofs were adapted to the pairs present. The literal `Never → S.A.D` pair was still found consecutively (idx0→idx1, cycle B) and tested literally (§6.6).
3. **"vc57 currently installed awaiting toggle" — last-verified.** The offline doc marked vc57 as currently installed. This cycle verified vc57 pre-install via `dumpsys-before` (pid 20468) — correct at install time; it is now superseded by vc58.
4. **Port is `40601` (plain) this cycle, not `41699`.** mDNS served `(2) 39761` + plain `40601`; the plain record was used as the serial. §9 re-derives the port from a fresh `adb mdns services` after the toggle; never hardcode.
5. **File counts + hashes re-verified.** djmix16 delta = **7 files / 683 lines** (`f0d08d4f34808c801a179525d6bd90676ecca35315ec82c9ed4b6f93d59086c7`), full = **42 files / 5545 lines** (`f2143e393139de281c1d8b1dbaa1427bd8d9dc214253020f3a4e402bdd2471fc`) via `grep -c "^diff --git"` + `sha256sum` + `wc -l`. APK = `a9e80e71…` / **87,481,967 B**. All nine APK hashes match on-disk `sha256sum`. vc58 telemetry is **374 lines** (pull8 4862 − pull2 4489 = 373 + boundary overlap; §10.3). LIVE was 447 lines at offline audit, now **463 lines** after two append-only milestones (no truncation).
6. **vc57-extended fires added to the lifetime table.** The brief's vc58-only framing missed 199 user-played vc57 lines (10 fires incl. `+27/+39/+41` and loop `−546`). They are reported in §6.7/PROOF-REPORT §8 as `vc57-ext`, not as vc58 results.

---

*End of handover. Next action belongs to the user (internal-audio recording + listening verdict) followed by §9 steps 3–7 in strict order. Phone left PAUSED (`Never Let Go` idx0 0 ms) on vc58, queue unmodified.*
