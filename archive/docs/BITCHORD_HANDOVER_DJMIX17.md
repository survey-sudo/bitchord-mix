# BitChord DJMIX17 Handover — 2026-11-13 (supersedes brief snapshots; live log remains the append-only diary)

## Read first / honest state in 5 lines

> **2.2.3-djmix17 vc59 is BUILT & SIGNED (sha256 `0be66f3a…`, 87,481,971 B) but NOT installed — wireless ADB is OFFLINE (`adb mdns services` empty, `adb devices -l` empty; 0 ports advertised, so 0 `adb connect` attempts per the single-shot rule; no retry/poll this cycle). Last verified on-phone build is 2.2.2-djmix16 vc58 (installed 2026-11-13 04:24:28, pid 7842 steady, zero BitChord crashes; left PAUSED at `Never Let Go` idx0 0 ms). vc59 directly targets the one vc58 residual — the `beginLoop` Unit-return starvation that kept the literal `Never Let Go → S.A.D` pair plan-only even after the djmix16 gates opened — but there is no vc59 telemetry yet. Next step is strictly: user toggles Wireless debugging ON → agent in-place `install -r` vc59 → Proof 1 (flagship `Never Let Go → S.A.D` firing proof with `qualityA="full"` verification) → Proofs 2–3 → user internal-audio recording + listening verdict.**
>
> Status tags in this doc: **[PROVEN]** = verified on disk/device telemetry this cycle; **[UNPROVEN]** = coded/tested/build-passing but never observed on device audio. Never upgrade an `[UNPROVEN]` to proven without telemetry + a fresh internal-audio capture.

---

## 1. App / identity [PROVEN]

| Item | Value |
|---|---|
| App name | BitChord |
| Package | `com.music.bitchord.loopfix` |
| ABI | `arm64-v8a` only (verified again this cycle: `unzip -l` lists only `lib/arm64-v8a/*` natives; the two non-.so entries `yt_ejs/*.js` are bundled JS, not native libs) |
| Device | POCO peridot `24069PC21G`, Android 16 |
| Signing | **oldkey only**, cert SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`, DN `CN=BitChord loop fix` — **never generate a new key**. Local shell has no `aapt`/`apksigner`/`apkanalyzer`, so vc59's cert/package re-verification remains per the Tasks 11B–12 cloud build logs; locally re-verified are SHA-256 + size + arm64-only natives + patch `wc -l`/`sha256sum` |
| Install rule | In-place only: `adb install -r`, **no uninstall / no data clear** (would lose per-user state + telemetry) |
| Debuggable | No. PIN lock active during prior proofs (`Dozing` / `isKeyguardShowing=true`); introspection is via telemetry JSONL + dumpsys + logcat, not a debugger. |
| Live diary | `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` (append milestones; do not rewrite history) |
| This file | `/home/hfzhkn/BITCHORD_HANDOVER_DJMIX17.md` (new 2026-11-13; old handovers preserved untouched) |

---

## 2. Current state + version table

### 2.1 What is on the phone vs on disk

- **INSTALLED on phone (last verified): 2.2.2-djmix16 vc58** [PROVEN at install time 2026-11-13 04:24:28, `djmix16-proof/PROOF-REPORT.md` + `dumpsys-after-install.txt`; pid 7842 steady, zero BitChord crashes; left PAUSED at `Never Let Go` idx0 0 ms]. **Current state UNVERIFIED** — wireless ADB offline this cycle, so no `dumpsys` could be taken.
- **BUILT & SIGNED, now pending install: 2.2.3-djmix17 vc59** [PROVEN built; NOT installed]. Code+build milestone is `MILESTONE djmix17 CODE & BUILD (Tasks 11B–12)` in the live log; device proof is BLOCKED this cycle (Task 13 offline branch).
- **Offline evidence this cycle:** `/home/hfzhkn/bitchord-device-evidence/djmix17-proof/adb-mdns-offline.txt` (empty services list), `adb-devices-offline.txt` (empty), `adb-connect-offline.txt` (0 advertised ports → 0 connect attempts; stale `40601` ghost deliberately not retried), `apk-sha.txt` (vc59 hash + size). `djmix14-proof/`, `djmix15-proof/`, `djmix16-proof/` verified untouched (no writes there this cycle).

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
| djmix15 | 2.2.1 / vc57 | `/home/hfzhkn/Downloads/bitchord-2.2.1-djmix15-automix-arm64-v8a-oldkey.apk` | `ca90837f1528df8a749f88b3fe07ba663c8cf522692960da86fd04bdb950057c` | 87,481,971 B | Superseded by vc58 (was on phone until ~04:24) |
| djmix16 | 2.2.2 / vc58 | `/home/hfzhkn/Downloads/bitchord-2.2.2-djmix16-automix-arm64-v8a-oldkey.apk` | `a9e80e71248c4899ff58190a0742621f40a6ab81c02b2135a0a3f41403a44010` | 87,481,967 B | **YES — last verified on phone** |
| **djmix17** | **2.2.3 / vc59** | `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix17-automix-arm64-v8a-oldkey.apk` | `0be66f3a8f391c76696f5b7b76d8b701341356c4e41804ac4a5914b68c69c039` | **87,481,971 B** | **NO — built, awaiting wireless-ADB toggle + `install -r`** |

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
  4. `LoopMixSession` region re-decode over network routinely exceeded `LOOP_ARM_LEAD` (297× `outgoing decode 17203 ms over`, later 145× `stream 17 ms over` + 101× `splice 49012 ms over` — timing/stream races, not one decode signature).
  5. `phrase-null` starvation (1,849 lifetime) + head-vs-full analysis race.
  6. Deck deadlines mixed wall/media epochs → bogus overruns (`374320 / 388005 ms over`).
  7. Scrub past `transitionStart` → shown-countdown `0s` decoupled from execution → stuck `0s` dry play-to-EOF (19:25:50 video).

### 3.2 Architectural upgrades by release

- **djmix11 (vc53):** `withExitTail` single choke point + graceful-exit dissolve + exit-gate backstop + `Phase.TAIL` (800/350 ms). Crash-proven, audio-unproven.
- **djmix12 (vc54):** retire guard → `mix.exit.guard` + `mix.exit.violated` invariant + classic `mix.plan.exitTailSec` once per verdict + `mix.complete` in `finish()`.
- **djmix13 (vc55):** `DeckBudget` pause-proof deadlines + `lateStepReason` proven-only + `SeekAction CANCEL/HONOUR/EMERGENCY_TAIL` + `mix.fire {deltaMs,cueErrorMs}`.
- **djmix14 (vc56):** `CatchUpPlanner` (late-scrub re-anchor, 2–4 bar truncated fade) + `LoopRegionCache` (4-entry RAM PCM) + `CrossfadeController` scrub reset (`resetPlansForScrub`, `scrubEpoch`, `cueErrorMs` sec→ms) + prime lead 40 s + sticky clear + `PhrasePlanner` memo/dedup + 2-thread analyzer. **First `planned-fade-done` ×2** (late catch-ups `+22,717/+20,090`).
- **djmix15 (vc57):** (1) ungated priming (25 s window) so the standby warms instead of stalling; (2) `RetireGuard` loop BPMs/tail from `session.plan` + volume-mute-before-retire → `loop-complete`; (3) `NO_DEADLINE` sentinel + `clear()` + 120 s horizon; (4) `isNextEdit(handedOff,…)` queue-edit gate; (5) playhead-anchored catch-up (`:catch-up` exempt from re-timing) + universal late-scrub handling.
- **djmix16 (vc58):** (1a) `CatchUpPins` — any `:catch-up` plan pinned ONCE per `(pair, scrubEpoch)` and reused, killing the moving carrot (`fadeSec 4.59→3.99→2.26→1.2`, `remaining` stuck at +900 ms); (1b) `LoopMix.shouldYieldColdLoop` — cold loop yields with a full classic arm lead `max(8.5 s, blendArmLead, 25 s while next warms)` + `loopSuppressedPair` + `discardPrime`, replacing the `<4.5 s` gate; (1c) null build while `LOOP_ARMING` yields to classic immediately (`loopFallBack`), not only 400 ms before the splice; **Bug 2:** `DeckBudget.LOOP_BUDGET_HORIZON_MS=40 s` (was 120 s) + horizon-aware `provenOverrunMs(ended,deadline,now)` on per-span and final paths + wall-only outgoing-splice step (`now,now,deadline`); **Bug 3:** `RetireGuard.loopArmingSpareAudible` + `finish()` pre-takeover silence (volume 0 + `playWhenReady=false`) → 0 ms to IDLE instead of 800 ms `Phase.TAIL`. **On device:** gates opened on three analysis-ready pairs (3 classic fires `+211/+221/+122 cue 0` → `planned-fade-done` ×2 + `seek-emergency-tail` ×1; 5 `insufficient-cold-lead-using-classic` fallbacks), `CANCEL` preserved (92+ cancels, 0 ghost fires), **zero `ms over` overruns** and **zero `finish:LOOP_ARMING` tails** in the 375-row vc58 window; **but the literal `Never Let Go → S.A.D` (104.495→66.484) pair advanced plans-only on two natural attempts (45 plans, `qualityA="none"`), so the flagship pair never fired mix** (see §3.3).
- **djmix17 (vc59):** **Bug 1 root-cause class refined by vc58 evidence:** the never-yielding `beginLoop` starvation — `beginLoop` previously returned `Unit` silently on guard failures (`inPort == null`, `outPort == null`, etc.) while `considerSmartTransition` executed an unconditional `return`, starving classic `begin()` on **every** 4 Hz tick once `loopStartMs - pos <= blendArmLeadMs (20,000..35,000 ms)`. Fixes: (1) `beginLoop(...): Boolean` — guard-failure paths now log `mix.loop.fallback`, suppress the loop for the pair, clear `loop`/`plannedTransition`, and **fall straight through to classic `begin()` on the same tick**; (2) `LoopMix.shouldYieldColdLoop` closes the `building == true` hang hole by yielding slow/hung `primeJob` builds at **14 s cold / 10 s warm** before `loopStart`, leaving classic **>= 7–11 s** before `contentEnd`; (3) `CatchUpPins.dropOnNonCatchUp` drops stale interior-anchor pins on valid same-pair non-catch-up plans (the `seq367→seq368→seq369` interior→final anchor flip); (4) `AnalysisStore` persists `quality` (`"full"`) and infers `"full"` for legacy pre-djmix17 JSON entries with `energyCurve`/`tailBeats`, plus `startLoopBuild`/`driveLoopArming` `primedSession` handoff hardening and `begin()` `armDeath` telemetry. See §5.

### 3.3 What vc58 proves, what it does not [PROVEN / UNPROVEN]

- **[PROVEN on device telemetry]** In-place install, pid steady (7842), zero BitChord crashes.
- **[PROVEN]** Bug-1 machinery: cold-yield → classic fire → `planned-fade-done` → `handoff=true` on three pairs (`+211/+221/+122`, `cueErrorMs=0`); `CANCEL` preservation; `EMERGENCY_TAIL` mid-blend; catch-up re-anchor pinned ahead (planned 148,462 ahead of scrub 147,520); **zero overruns**; **zero `finish:LOOP_ARMING` tails** (no `LOOP_ARMING` entries — pre-takeover silence unexercised).
- **[PROVEN]** Bug-1 mechanism opens the gates on full/full pairs; the failure is upstream of the gate (`qualityA="none"` starves `armLoop`/classic `begin()`).
- **[UNPROVEN]** The literal `Never Let Go → S.A.D` (`104.495→66.484`) transition has never fired a mix in vc56, vc57, or vc58 (plans-only plain advance on all three builds; 45 plans frozen at `qualityA="none"` in vc58). **This is the flagship residual — the `beginLoop` fall-through in vc59 is designed to unblock it; device proof pending.**

---

## 4. djmix14–djmix17 root-cause map

| # | Failure | Root cause (code file) | Fixed in | Status |
|---|---|---|---|---|
| F1 | `0s → nothing`: UI countdown decoupled from execution; sticky-loop past-start deadlock; watchdog blindness after scrub; 90 s loop suppression divergence | `CrossfadeController.describeAutomix` + `loopPlanFor` `Hold.KEEP` ignoring `seekPast` + cleared `shownCountdownKey` + dual loop/classic arming clocks | djmix13–14 (`DeckBudget`, `SeekAction`, `CatchUpPlanner`, sticky clear, prime 40 s) | **[PROVEN]** catch-up + natural classics fire and complete on vc56/vc57/vc58 |
| F2 | Scrub flaws: mid-blend scrub desync; stale countdown ghosts; abrupt pre-takeover envelope; no catch-up at EOF | `onPositionDiscontinuity` → `bail()` freezing `ARMING`/`FADING`; `O_MIN_SEC 6.0` + `PLAN_LEAD 60 s` + 40 ms planner timeout → phrase-null 1849 | djmix13–14 (`SeekAction CANCEL/EMERGENCY`, `resetPlansForScrub`, `CatchUpPlanner`) | **[PROVEN]** `CANCEL` 92+ (vc58) + `EMERGENCY_TAIL` + pinned catch-up `+221` on vc58 |
| F3a | Moving-carrot catch-up (`fadeSec 4.59→3.99→2.26→1.2`, `remaining` stuck +900 ms) | `pos <= start+400` pin expiry + recompute as `playhead + lead` | **djmix16 (`CatchUpPins`)** → **djmix17 (`dropOnNonCatchUp`)** | **[PROVEN]** on vc58 (pinned ahead, `+221` catch-up fire); djmix17 closes the residual interior→final flip (`seq367→369`) |
| F3b | Cold loop starves classic (yield at 4.5 s left <4 s to buffer cold standby; `LOOP_ARMING` retry blocked `begin()`) + **`beginLoop` Unit-return starvation** (guard-failure paths silent; unconditional `return` in `considerSmartTransition` blocks classic every 4 Hz tick) | `<4.5 s` gate + 400 ms-only yield + complete-rendition requirement vs. never-downloaded streamed file; `beginLoop: Unit` never signals fall-through to classic | djmix16 (`shouldYieldColdLoop` + immediate yield) → **djmix17 (`beginLoop: Boolean` same-tick fall-through + 14 s/10 s slow-build yield + `AnalysisStore` quality persistence)** | **[PROVEN machinery] / [BLOCKED literal pair]**: 5 fallbacks + 3 fires on full/full pairs in vc58; literal 104→66 starved by `qualityA=none` upstream of the gate — the vc59 `beginLoop` fall-through is the unblocker, **device proof pending** |
| F4 | `374320` then `116245 ms over` wall-minus-media | Wall clock minus media positions; `0L` sentinel; 120 s horizon; span path without horizon | djmix15 + **djmix16 (40 s horizon + wall-only splice)** | **[HYGIENE PROVEN]** zero `ms over` on vc58 (375-row window) |
| F5 | `finish:LOOP_ARMING emergency-retire-guard` 800 ms tail ×8 | Prepared-but-silent spare (`volume=1.0`) read as audible via guard | **djmix16 (`loopArmingSpareAudible` + silence)** | **[UNEXERCISED]** zero `LOOP_ARMING` entered on vc58; zero tails by absence |
| F6 | Taste/DSP: tempo/key/vocal alignment, loop early-fire (−787), classic late-fire (+119…+221) | Single-clock native vs dual-Exo classic; missing limiter staging / vocal EQ / transient handling / reverb/roll; ±8% cap / 16-bar downbeat portable traits per djmix12-forensics | Open | **[UNPROVEN]** needs user ears |

---

## 5. Code & build provenance (Tasks 11B–12) [PROVEN on disk]

| Item | Value |
|---|---|
| Delta patch | `/home/hfzhkn/bitchord-djmix17.patch` (681 lines, 8 files, `sha256 31c313ccf982368237f8511e8801cec70cab6083a53c9b3f63736d72e3669ec8`) over djmix16 worktree |
| Full patch | `/home/hfzhkn/bitchord-djmix17-full.patch` (6085 lines, 45 files, `sha256 4a4c6821a4a7d463a8cfcb8be45752b5f0fa81f70bd40de141ff27f4efb804e3`) from `dc78e0d` |
| Files (delta) | `CrossfadeController.kt` (`beginLoop: Boolean` + fall-through), `LoopMix.kt` (`shouldYieldColdLoop` slow-build yield), `CatchUpPlanner.kt` (`dropOnNonCatchUp`), `AnalysisStore.kt` (quality persist + legacy inference), plus `primedSession` handoff hardening, `begin()` `armDeath` telemetry, `build.gradle.kts` (`vc59` / `2.2.3-djmix17`), `Djmix17RegressionTest.kt` (new; `grep -c "^diff --git"` = 8 / 45) |
| Tests | `Djmix17RegressionTest` (Bug 1 fall-through/cold-yield/pin-drop/quality-inference, incl. the vc58 `qualityA="none"` literal-pair case) + all suites green per cloud build logs |

Fix key (referenced as Bug 1/2/3 in this doc = vc58 residuals):

1. **`beginLoop(...): Boolean` — same-tick fall-through (the flagship fix).** Previously, loop guard-failure paths returned `Unit` silently while `considerSmartTransition` executed an unconditional `return`, starving classic `begin()` on every 4 Hz tick once `loopStartMs - pos <= blendArmLeadMs (20,000..35,000 ms)`. Now the guard failures log `mix.loop.fallback`, suppress the loop for the pair, clear `loop`/`plannedTransition`, and fall straight through to classic `begin()` **on the same tick** — so a cold-impossible pair always gets its classic arm instead of a frozen planner.
2. **Slow-build yield.** `LoopMix.shouldYieldColdLoop` now yields slow/hung `primeJob` builds at `14 s` cold / `10 s` warm before `loopStart`, leaving classic **≥ 7–11 s** before `contentEnd` (closes the `building == true` hang hole).
3. **`CatchUpPins.dropOnNonCatchUp`.** Drops stale interior-anchor pins on valid same-pair non-catch-up plans — fixes the `seq367(4.5935114) → seq368(9.135) → seq369(4.5935114)` interior→final anchor flip seen on the literal pair in vc58.
4. **`AnalysisStore` quality persistence.** Persists `quality` (`"full"`); legacy pre-djmix17 JSON entries with `energyCurve`/`tailBeats` infer `"full"` — so a streamed track that legitimately completed full analysis is not forever treated as `none` (the vc58 `qualityA="none"` 45-plan freeze). Plus `startLoopBuild`/`driveLoopArming` `primedSession` handoff hardening and `begin()` `armDeath` telemetry.
5. **Carried-forward hygiene (from djmix16, unregressed):** `CatchUpPins` pin-once, `DeckBudget 40 s horizon` + wall-only splice (zero overruns in vc58), `RetireGuard.loopArmingSpareAudible` (unexercised — no loop built).

---

## 6. Device proof summary (vc56/vc57/vc58 [PROVEN]; vc59 BLOCKED — no telemetry)

Full seq-by-seq tables are in `/home/hfzhkn/bitchord-device-evidence/djmix14-proof/PROOF-REPORT.md`, `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/PROOF-REPORT.md`, and `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/PROOF-REPORT.md`. Headlines below are carried forward for the vc55→vc56→vc57→vc58→vc59 comparison; **vc59 has NO proof rows yet** — the vc59 program (Proofs 1–3) is listed in §8 as pending.

### 6.1 vc56 (2.2.0-djmix14) — first `planned-fade-done` [PROVEN ON DEVICE TELEMETRY]

- Beautiful→Never LoopMix `fire L3781 seq17 delta −755` → `gate 0` → `complete handoff=true` (reason `emergency-retire-guard`, `bpm 0`, not `loop-complete`).
- `Never Let Go→S.A.D` plan-only plain advance WITHOUT fire (6 classic plans, zero fire) — residual natural-classic hole.
- S.A.D→LAST catch-up `fire +22,717 cue 0 → planned-fade-done → handoff=true` (HISTORIC 1st `planned-fade-done` lifetime); LAST→Good `+20,090 → planned-fade-done`; Good→intentions `+103 → planned-fade-done` (best classic accuracy).
- B2 `CANCEL` + B3 `EMERGENCY_TAIL` (`seek-emergency-tail`, `violated=0`) proven. Pulls 3762→3980, pid 1373 steady, 0 BitChord crashes.

### 6.2 vc57 (2.2.1-djmix15) — `loop-complete` + re-anchor + accuracy [PROVEN ON DEVICE TELEMETRY]

- **Proof 1A LoopMix (Beautiful→Never):** L4047 `seq63 fire loop −787` → L4048 `seq64 gate violations=0` → L4049 `seq65 complete LOOP_RUNNING handoff=true bpmA=99.987 bpmB=104.495 tailMs=0 reason=loop-complete`. No guard/tail/freeze/overrun in window.
- **Proof 1B Never→S.A.D:** L4075–L4080 `seq91–96 classic DJ_FILTER fade 9.13→1.20` → L4081 `seq97 M6`. **Zero fire/gate/tail/complete**; idx1→idx2 plain advance.
- **Proof 2 CatchUp (S.A.D→LAST):** L4128 `seq140 fire classic planned 194,271 actual 194,464 delta +193 cue 0` → L4129 `seq141 tail planned-fade-done driveFade-done` → L4130 `seq142 complete FADING handoff=true bpm 66.48/120.64`.
- **Proof 3A CANCEL:** L4216–L4232 `seq230–248 cancel (ARMING/LOOP_ARMING/IDLE)` + `scrub-cancelled` + one fresh plan each; **zero ghost fire**; playhead 130,617.
- **Proof 3B EMERGENCY_TAIL:** L4155 `seq171 fire FADING +127` → L4156 `seq172 FADING 1404→0 emergency_tail` → L4157 `seq173 tail seek-emergency-tail` → L4158 `seq174 complete TAIL handoff=true bpm 120.64/103.13`.
- **Bonus natural classic (Good→intentions):** L4252 `seq268 fire +119` → L4253 `seq269 tail planned-fade-done` → L4254 `seq270 complete FADING handoff=true`.
- **Health:** pid `32159` steady; crash 0 BitChord (1304 lines); `media_session-final.txt` `PAUSED Beautiful idx0 0 ms`.

**`mix.fire` accuracy (all fires, lifetime):** classic `cueErrorMs=0` on all six vc56/vc57 classic fires; loop `−426/−755` (vc56) then `−787` (vc57). Best classic accuracy remains vc56 `+103` / vc57 `+119`. Catch-up lateness fell from `+22,717/+20,090` (vc56) to `+193` (vc57). No fire meets the ±25 ms ideal; closest is `+103`, reported as-measured.

**vc55 → vc56 → vc57 comparison (fires / tails / gates / completes / seeks / freezes):** djmix15 PROOF-REPORT §7 has the full table. Salient: vc57 fires 4 in 304 lines (highest sustained rate); `violated` 0 in all three windows (13 lifetime gates all `0`); `freeze.queueEdit` 0 in vc57 (vs 2 in vc56 session); `374320/388005` 0 in vc57 (one `116245` residual); `planned-fade-done` 2 in vc57 (plus 1 `seek-emergency-tail`, plus 1 `loop-complete`).

### 6.3 vc58 (2.2.2-djmix16) — gates open, flagship pair still plan-only [PROVEN ON DEVICE TELEMETRY]

- **Proof 1' (Never Let Go → Setsuna Hanabi, adapted — Beautiful absent from queue):** seq54–90 FF `6,860→163,985`; **zero fire/complete/tail/fallback in seq1–97**; idx1 Setsuna Hanabi PLAYING @24,091 — plain advance.
- **Proof 2' (Setsuna→Back To Strangers, classic):** `seq118 fallback insufficient-cold-lead-using-classic` → **`seq123 fire classic planned 169249 actual 169460 +211 cue 0`** → **`seq124 tail planned-fade-done`** → **`seq125 complete handoff=true 118.451/85.238`**. Bug-1 machinery PROVEN on this pair.
- **Proof 3A' (BTS→next):** `seq139 fallback` → `seq140 ARMING cancel` → **`seq142 complete ARMING finish handoff=false tailMs=0`** (0 ms abort, no `finish:LOOP_ARMING` tail) → **`seq144 fire planned 148462 actual 148683 +221 cue 0`** (pinned catch-up ahead of scrub 147,520) → `seq145 FADING emergency_tail` → `seq146 seek-emergency-tail 800` → `seq147 TAIL handoff=false`.
- **Literal Never→S.A.D (104.495→66.484, seq327–371):** FF `1,949→159,636`; plans `fadeSec 4.5935/9.135` mixed; **ZERO fire/fallback/tail/complete — plain advance, `qualityA="none"` throughout (45 plans)**. Salvage: `seq281 fire +122` → `seq282 planned-fade-done` → `seq283 handoff=true 66.484/85.238`.
- **Scrub matrix:** `CANCEL` 92 IDLE cancels (Never 8× rewind `131,568→97,210`, BTS storm per `after-3b-bts`); `EMERGENCY_TAIL` = seq145–147 (FADING). **Zero `ms over`, zero `mix.exit.guard`/violated/arm.death/freeze; 0 loop fires; LoopMix never armed on this queue. Pulls 4487→4862, pid 7842 steady, 0 BitChord crashes, PAUSED idx0 Never Let Go @0.**
- **Verdict:** gates open on full/full pairs (Bug 1 machinery + catch-up + CANCEL proven); **food upstream of the gate absent** for Never outgoing (`qualityA="none"` → arm starvation); Bug 3 silence **unexercised** (no `LOOP_ARMING` entry). Full verdict in `djmix16-proof/PROOF-REPORT.md`.

### 6.4 vc59 (2.2.3-djmix17) — BLOCKED, no proof rows

There is **no vc59 telemetry line**: no `mix.fire`, no `mix.exit.tail`/gate/complete, no `mix.seek`, no `dumpsys` version proof. The expected vc59 assertions (once online) are in §8: the flagship `Never Let Go → S.A.D` firing proof with `qualityA="full"` verification, the second natural transition, the `:catch-up`/`CANCEL`/`EMERGENCY_TAIL` matrix, and the zero-overrun/false-guard invariant window. Nothing in §6.1–§6.3 should be read as a vc59 result.

---

## 7. Honesty Box & method

**[PROVEN ON DEVICE TELEMETRY]** (djmix16 PROOF-REPORT §§4–8): items in §3.3 / §6.3 above. **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**: acoustic `<50 ms` hole + dissolve audibility + beat-match/taste — still open (no agent has "heard" any fix; `adb screenrecord` is mic-only and no screenrecord was taken in any cycle).

**vc59 status:** **[UNPROVEN ON DEVICE]** — code-complete, regression-tested, built & signed, but never installed or observed. The `beginLoop` fall-through, slow-build yield, pin-drop, and `AnalysisStore` quality-inference fixes in §4–§5 are claims until the §8 proofs produce the `seq` lines.

**Queue watch-out (`vc58 lesson, [PROVEN on device]`):** the brief's index story ("`Beautiful` idx0") was **stale** — the phone's queue had changed (user listening + AutoPlay insertions + shuffle ON; ui_queue top-5 was `Never Let Go → Setsuna Hanabi → Back To Strangers → DEEP DIVE → 매일 밤이`). `media_session` `description` is the verbatim **current track** (titles verified per snapshot); never hardcode idx→track mappings — always read the session description + `hiqBPM` pair before assuming a proof's pair.

**Method constraints (same as vc56/vc57/vc58):** PIN-locked → UI taps impossible; all seeks via `cmd media_session dispatch FF (+15 s)/RW (−5 s)` (verified `DISCONTINUITY_REASON_SEEK` via `mix.seek`); hands-off single waits (no polling loops); `dumpsys media_session` positions are the source of truth for durations. Playback math used for §8: `Never Let Go` ≈ `193,641 ms`, `S.A.D` ≈ `202,921 ms` (buffered durations may read higher, as in vc57/vc58).

---

## 8. Next steps (strict order)

1. **User toggles Wireless debugging ON (same Wi-Fi).** Phone: Settings > Additional settings > Developer options > **Wireless debugging ON**. Then tell the agent to run the vc59 program. Port will differ from `39761`/`40601` after the toggle — re-read it from `adb mdns services`, never hardcode.
2. **Agent single-shot check + in-place install (no uninstall/clear).** `adb mdns services` + `adb devices -l` → ONE `adb connect` per distinct advertised port → on `device`: harvest `djmix17-proof/pull1` FIRST (telemetry + `dumpsys-before` + `media_session-before`), then `adb -s <serial> install -r /home/hfzhkn/Downloads/bitchord-2.2.3-djmix17-automix-arm64-v8a-oldkey.apk` (must read `Success`), verify `versionCode=59` / `versionName=2.2.3-djmix17`, launch via `monkey`, pull `pull2` (expect the `vc59 seq1` boundary line), then Proofs 1–3 below into `djmix17-proof/` without touching `djmix14-proof/`/`djmix15-proof/`/`djmix16-proof/`.
3. **Proof 1 (THE FLAGSHIP) — `Never Let Go → S.A.D`.** Start `PLAYING` on `Never Let Go` (duration ≈ 193,641 ms), FF (+15 s steps) to **~143,000–146,000 ms** (~48–50 s before EOF — same region as vc56/vc57/vc58), **HANDS OFF** single wait (~55 s) into `S.A.D`; pull `pull3` + check `media_session` idx1. **vc59 CLOSED requires:** `qualityA="full"` on the plans → **`mix.fire` → `mix.complete handoff=true`** (no `qualityA="none"` 45-plan freeze; no `beginLoop` starvation rows).
4. **Proof 2 — second natural transition (`S.A.D →` next, duration ≈ 202,921 ms).** FF to ~150,000 ms (~50 s before EOF), HANDS OFF (~56 s) into next track; pull `pull4`; require `mix.fire` → `mix.complete handoff=true`.
5. **Proof 3 — scrub matrix + invariant window.** 3A `:catch-up` (FF to ~7–8 s before duration, HANDS OFF ~13 s → fire + `handoff=true`, pull5); 3B `CANCEL` (pre-audible backward scrub, 8× rewind, wait ~10 s, 0 ghost fire → pull6); 3C `EMERGENCY_TAIL` (2 s AFTER `transitionStart` during `FADING`, 2× rewind, wait 3 s → pull7 with `seek-emergency-tail`, `violated=0`). **Bug hygiene requires ZERO `116245`/`374320`/`ms over` strings and a clean guard/false-guard record across the entire vc59 session.**
6. **Clean reset + health check.** Step back via `previous` to **idx0 `Never Let Go` at 0 ms** and PAUSE cleanly (phone ready for the user's listening test, `Never Let Go → S.A.D` queued first); pull `pull8` (`wc -l` + `sha256sum`), save `pid-end.txt` (must match `pid-start.txt`), `dumpsys-after.txt`, `crash-log.txt` (0 BitChord crashes), `exit-info.txt`, `media_session-final.txt`; write `djmix17-proof/PROOF-REPORT.md` (seq-by-seq for all pulls, vc55→vc59 table, `mix.fire` accuracy, pull SHA-256s, Honesty Box).
7. **User internal-audio recording + listening verdict (Definition of Done).** Phone left **PAUSED at `Never Let Go` idx0, 0 ms**. Unlock phone, start internal-audio screen recording (internal audio ON, as in `VID_20261112_151114`), play `Never Let Go → S.A.D`, stop after Song 2 settles, send the mp4. Gate check: silence gap < 50 ms + dissolve ≥ 300 ms on the envelope + explicit taste/beat-match verdict. If the gate fails, file the mp4 + telemetry pull and reopen the relevant Bug (§4).
8. **Do not claim audio success from telemetry.** Acceptance still requires the ears.

---

## 9. File inventory

### 9.1 APKs & patches — canonical

APKs all under `/home/hfzhkn/Downloads/` (10 preserved: djmix8–djmix17). Patches:

- `/home/hfzhkn/bitchord-djmix17.patch` (681 lines, 8 files, `31c313cc…`).
- `/home/hfzhkn/bitchord-djmix17-full.patch` (6085 lines, 45 files, `4a4c6821…`).
- Earlier: `/home/hfzhkn/bitchord-djmix16.patch` (683 lines, 7 files, `f0d08d4f…`), `/home/hfzhkn/bitchord-djmix16-full.patch` (5545 lines, 42 files, `f2143e39…`), `/home/hfzhkn/bitchord-djmix15.patch` (855 lines, 10 files, `67a95fa6…`), `/home/hfzhkn/bitchord-djmix15-full.patch` (5045 lines, 41 files, `a98173f7…`), `/home/hfzhkn/bitchord-djmix14.patch` (2108 lines, 19 files, `dbdbbb0a…`), `/home/hfzhkn/bitchord-djmix14-full.patch` (3986 lines, 35 files, `f645c223…`), plus all djmix9–djmix13 patches (see LIVE).

### 9.2 Evidence (djmix17 — offline branch only)

| Path | Role |
|---|---|
| `/home/hfzhkn/bitchord-device-evidence/djmix17-proof/adb-mdns-offline.txt` | `adb mdns services` — empty (0 advertised ports) |
| `/home/hfzhkn/bitchord-device-evidence/djmix17-proof/adb-devices-offline.txt` | `adb devices -l` — empty (device list) |
| `/home/hfzhkn/bitchord-device-evidence/djmix17-proof/adb-connect-offline.txt` | Single-shot record — 0 advertised ports → 0 `adb connect` attempts (stale `40601` ghost deliberately not retried) |
| `/home/hfzhkn/bitchord-device-evidence/djmix17-proof/apk-sha.txt` | vc59 sha256 + size (`0be66f3a…`, 87,481,971 B) |
| *(no `telemetry-pull*.jsonl`, `install.log`, `dumpsys*.txt` yet)* | Created only when online — Phases 1–4 (§8 steps 2–6). `djmix14-proof/`, `djmix15-proof/`, `djmix16-proof/` remain 100% untouched. |

### 9.3 Evidence (djmix14/djmix15/djmix16 — untouched references)

| Path | `wc -l` / `sha256sum` |
|---|---|
| `djmix16-proof/telemetry-pull1-preinstall.jsonl` | 4487 / `32fb8672292bb3fb…` |
| `djmix16-proof/telemetry-pull2-postinstall.jsonl` | 4489 / `74a3e24068fff078…` |
| `djmix16-proof/telemetry-pull3-beautiful-loop.jsonl` | 4584 / `3dbdbdae526309fb…` |
| `djmix16-proof/telemetry-pull4-neverletgo-sad.jsonl` | 4614 / `5e54d7c5666b11ca…` |
| `djmix16-proof/telemetry-pull5-catchup-and-loopabort.jsonl` | 4647 / `ed6a1b1cf1f60557…` |
| `djmix16-proof/telemetry-pull6-scrub-cancel.jsonl` | 4710 / `069681db6d787fc0…` |
| `djmix16-proof/telemetry-pull7-scrub-audible.jsonl` | 4758 / `2016af17a55c18ce…` |
| `djmix16-proof/telemetry-pull8-final.jsonl` | 4862 / `13d2e51922389e64…` |

### 9.4 Reports (primary)

| Path | Verdict |
|---|---|
| `/home/hfzhkn/bitchord-device-evidence/video-VID_20261112_151114/FINDINGS.md` | Day-one hole measured: 258.2 ms silence, ~15–20 ms fades. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-forensics/FORENSICS.md` | "(2)" = byte-identical pre-fix video; 586:15 rejection audit; BK portable traits. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-proof/PROOF-REPORT.md` | **PARTIAL**: guard proven; `planned-fade-done` never fired. |
| `/home/hfzhkn/bitchord-device-evidence/djmix13-proof/PROOF-REPORT.md` | **INTERIM** — build delivered, install pending (listener down). |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-forensics/FORENSICS-DJMIX14.md` | **Bug Classes 1–4 + video correlation** (3750-line vc55 baseline; plan→fire 2.2%, 0 `planned-fade-done`). |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-proof/PROOF-REPORT.md` | **CLASSIC `planned-fade-done` ×2 + Loop + B2 + B3 [PROVEN]** (pulls 3762→3980, pid 1373). |
| `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/PROOF-REPORT.md` | **Loop `loop-complete` + re-anchor + accuracy + B2/B3 [PROVEN]; NLG→S.A.D + 116245 noted** (pulls 3984→4288, pid 32159). |
| `/home/hfzhkn/bitchord-device-evidence/djmix16-proof/PROOF-REPORT.md` | **Bug-1 machinery + catch-up + CANCEL [PROVEN]; literal NLG→SAD (`qualityA=none`) + LOOP_ARMING + canonical 3B noted** (pulls 4487→4862, pid 7842). |
| `/home/hfzhkn/bitchord-device-evidence/djmix17-proof/` | **OFFLINE** — `adb mdns services` empty; 4 files (mdns/devices/connect/sha); no telemetry, no install. |
| `/home/hfzhkn/bitchord-device-evidence/djmix11-proof-20261112T0922Z/ANALYSIS.md` | djmix11 telemetry pass. |

### 9.5 Source / reference

| Path | Role |
|---|---|
| `bk-android:/data/workspace/bitchord-task2/work/BitChord` @ `dc78e0d` (+ staged + uncommitted deltas) | **BitChord source of truth** (box only). |
| `/home/hfzhkn/bkmasterplayer/app/rust/src/engine/{analysis,automix,mixer,deck}.rs` | BK reference only. |

---

## 10. Corrections found on disk vs the commissioning brief (2026-11-13 audit)

This handover re-verified everything below on disk before writing — corrections applied:

1. **Offline branch, not online proofs.** The brief's detailed Phases 1–5 assume a connected device. `adb mdns services` advertises **0 ports** and the TCP listener is down (`adb devices -l` empty). Per the brief's own Critical Rule 1 (at most one connect per distinct advertised port; none advertised), Phases 1–5 were skipped and only offline documentation was written. No `seq` telemetry lines exist for vc59; §6.4 and §8 carry the pending program instead of invented results.
2. **"vc58 currently installed" is last-verified, not live-verified.** The brief asks to mark vc58 as currently installed. Locally, that state was last verified by `djmix16-proof` on 2026-11-13 04:24:28 (vc58, pid 7842, PAUSED Never Let Go idx0 0 ms). This cycle could not take a `dumpsys`, so the exact wording is "last verified installed vc58; current state UNVERIFIED while offline" — the user may have changed the phone since.
3. **No port can be named this cycle.** Unlike the djmix16 offline cycle (which still advertised the stale `40601` record), this cycle `adb mdns services` advertises **nothing at all**. The `39761`/`40601` serials from the vc58 session are not known to be current; §8 re-derives the port from a fresh `adb mdns services` after the toggle. Do not hardcode either number.
4. **Verification limits stated honestly.** Local shell has no `aapt`/`apksigner`, so vc59's versionCode/cert/package proof is by-reference to the Tasks 11B–12 cloud build logs; locally re-verified are `sha256sum` (`0be66f3a…`), `stat` size (87,481,971 B), `grep -c "^diff --git"` (8/45), and arm64-v8a-only natives via `unzip -l`. All ten APK hashes match on-disk `sha256sum`; patch hashes match the brief.
5. **File counts + hashes re-verified.** djmix17 delta = **8 files / 681 lines** (`31c313ccf982368237f8511e8801cec70cab6083a53c9b3f63736d72e3669ec8`), full = **45 files / 6085 lines** (`4a4c6821a4a7d463a8cfcb8be45752b5f0fa81f70bd40de141ff27f4efb804e3`) via `grep -c "^diff --git"` + `sha256sum` + `wc -l`. LIVE was 475 lines at audit, now **481 lines** after the append-only milestone (no truncation). `DJMIX13/14/15/16` and `djmix14-proof/`/`djmix15-proof/`/`djmix16-proof/` untouched (hashes in §9; pull hashes in §9.3).

---

*End of handover. Next action belongs to the user (toggle Wireless debugging ON) followed by §8 steps 2–7 in strict order. Phone state unverified while offline; vc59 is built & signed and ready for in-place `install -r`.*

## MILESTONE djmix17 TASK 13 PARTIAL LIVE & HANDOVER SNAPSHOT (2026-11-13, STOP)
- Task 13-LIVE installed vc59 live (install.log Success, pid-start 30902, 0be66f3a…c039) and captured pull1..pull3 before user-requested stop (13-LIVE cancelled).
- pull3 (4927 lines, 85de964c…) proves: L4868 seq2 Never→SAD qualityA=full qualityB=full (Fix #4 LIVE vs vc58 seq378 none); L4902-4904 seq36-38 anchor-flip 4.5935→9.135→4.5935 (Fix #3 LIVE); L4910-4914 seq44-48 SAD→LAST GOODBYE: fallback insufficient-cold-lead-using-classic → fire classic +41ms cueError 0 → tail planned-fade-done → complete FADING handoff=true (sharpest ever).
- Smoking gun seq34..39: literal Never→SAD plain-advanced 193,641ms EOF with 0 loop.fallback + 0 arm.death → narrowed to CrossfadeController 1567-1785 (rendition mismatch loopStart>duration at :1596/:1652 + LoopMix :369, else :1724 markerOnly). Single-shot bk-android check confirms no clamp.
- adb at handover-stop check: empty → no pause/pull4 (0 attempts). Canonical: /home/hfzhkn/BITCHORD_HANDOVER_DETAILED.md (definitive). Honesty: telemetry-only, [AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT].
