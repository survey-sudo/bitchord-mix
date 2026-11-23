# BitChord DJMIX15 Handover — 2026-11-13 (supersedes brief snapshots; live log remains the append-only diary)

## Read first / honest state in 5 lines

> **Phone has 2.2.1-djmix15 vc57 LIVE (in-place reinstall Success 2026-11-13 00:55:45, dumpsys-verified; pid 32159 steady, zero BitChord crashes). LoopMix completes with the clean `loop-complete` telemetry (`LOOP_RUNNING`, `handoff=true`, BPM >0) for the first time; catch-up `plannedStartMs` is now truly re-anchored ahead of the scrub (`+193 ms` vs vc56 `+22,717 ms`); classic fires are accurate (+119/+127/+193 ms). Fixes #2, #4, #5 [PROVEN ON DEVICE TELEMETRY]; Fix #3 partially proven (374320 gone, one 116245 residual); Fix #1 not proven on `Never Let Go → S.A.D` (still plan-only plain advance). No agent has "heard" any fix — `adb screenrecord` is mic-only; audio proof needs a user internal-audio recording. Next step is strictly: user internal-audio recording of a transition (<50 ms hole check) + explicit listening verdict for Beautiful (Bazzi) → Never Let Go (LNGSHOT), from PAUSED Beautiful idx0 at 0 ms.**

Status tags in this doc: **[PROVEN]** = verified on disk/device telemetry this 2026-11-13 sprint; **[UNPROVEN]** = coded/tested/build-passing but never observed on device audio. Never upgrade an `[UNPROVEN]` to proven without telemetry + a fresh internal-audio capture.

---

## 1. App / identity [PROVEN]

| Item | Value |
|---|---|
| App name | BitChord |
| Package | `com.music.bitchord.loopfix` |
| ABI | `arm64-v8a` only (verified in every APK below) |
| Device | POCO peridot `24069PC21G`, Android 16 |
| Signing | **oldkey only**, cert SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`, DN `CN=BitChord loop fix` — **never generate a new key** |
| Install rule | In-place only: `adb install -r`, **no uninstall / no data clear** (would lose per-user state + telemetry) |
| Debuggable | No. PIN lock active during proofs (`Dozing` / `isKeyguardShowing=true`); introspection is via telemetry JSONL + dumpsys + logcat, not a debugger. |
| Live diary | `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` (append milestones; do not rewrite history) |
| This file | `/home/hfzhkn/BITCHORD_HANDOVER_DJMIX15.md` (new 2026-11-13; old handovers preserved untouched) |

---

## 2. Current state + version table

### 2.1 What is on the phone vs on disk

- **INSTALLED on phone: 2.2.1-djmix15 vc57** [PROVEN at install time 2026-11-13 00:55:45, corroborated in `djmix15-proof/PROOF-REPORT.md` + dumpsys-before/after-install/after]. Installed via wireless ADB mDNS serial `192.168.100.218:41699` (re-verified per-phase). No BitChord crash observed (pid 32159 steady across all proofs; crash-log 0 bitchord hits).
- **BUILT, now installed: 2.2.1-djmix15 vc57** [PROVEN installed]. Code+build milestone is `MILESTONE djmix15 CODE & BUILD (Tasks 5–6)` in the live log; device proofs are `MILESTONE djmix15 DEVICE PROOF (Task 7)`.
- **LoopMix `loop-complete` has now fired on device** (Beautiful→Never, `LOOP_RUNNING`, `handoff=true`, BPM 99.98/104.49). djmix14 gave `emergency-retire-guard` + `bpm 0`; djmix11–13 gave `0 planned-fade-done` lifetime. Classic `planned-fade-done` is now **2× on vc57** (S.A.D catch-up + Good→intentions) on top of vc56's 2×. Scrub protocol `CANCEL` (72×) + `EMERGENCY_TAIL` (1×) live-proven with playhead preservation.

### 2.2 Version table (all hashes + sizes re-verified on disk 2026-11-13)

| Release | versionName / vc | APK path | SHA-256 | Size | Installed? |
|---|---|---|---|---|---|
| djmix8 | 1.9.1 / vc50* | `/home/hfzhkn/Downloads/bitchord-loopfix-1.9.1-djmix8-arm64-v8a-oldkey.apk` | `4e7c4a0005a161eb5ffa00af3cd6ec92248933b968a3d85240e7850063aefd38` | 87,582,011 B | No (preserved) |
| djmix9 | 1.9.1 / vc51 | `/home/hfzhkn/Downloads/bitchord-1.9.1-djmix9-automix-arm64-v8a-oldkey.apk` | `b0af10490acefc85c2be30376e7e6aade973b9c8d0e21d2ffdc4327deb681c47` | 87,573,558 B | No (preserved) |
| djmix10 | 2.0.0 / vc52 | `/home/hfzhkn/Downloads/bitchord-2.0.0-djmix10-automix-arm64-v8a-oldkey.apk` | `82ebbbf5dec8b5194889cb8df450433ba09b1adbf8820635663a8cd875fe1889` | 87,465,587 B | No (preserved) |
| djmix11 | 2.0.1 / vc53 | `/home/hfzhkn/Downloads/bitchord-2.0.1-djmix11-automix-arm64-v8a-oldkey.apk` | `357e281d34e9f7222b2fd43196eaec0733b8db717c2c6f43aee8444074bdf029` | 87,465,587 B | Superseded by vc57 |
| djmix12 | 2.1.0 / vc54 | `/home/hfzhkn/Downloads/bitchord-2.1.0-djmix12-automix-arm64-v8a-oldkey.apk` | `a25900e9647534511b36fdb70abfc914b1fb5eaceb1010b0a7660f4848a1eb8d` | 87,465,587 B | Superseded by vc57 |
| djmix13 | 2.1.1 / vc55 | `/home/hfzhkn/Downloads/bitchord-2.1.1-djmix13-automix-arm64-v8a-oldkey.apk` | `2237caa1dc72b4afb52c2633585debd8cb5cbe8f6e75fd40a806fedd8dd69f9b` | 87,465,583 B | Superseded by vc57 |
| djmix14 | 2.2.0 / vc56 | `/home/hfzhkn/Downloads/bitchord-2.2.0-djmix14-automix-arm64-v8a-oldkey.apk` | `dd8d49967f0826c5e9330368f4130a642a39686bb47b78216ce64828ba19f915` | 87,465,587 B | Superseded by vc57 (was on phone until ~00:55) |
| **djmix15** | **2.2.1 / vc57** | `/home/hfzhkn/Downloads/bitchord-2.2.1-djmix15-automix-arm64-v8a-oldkey.apk` | `ca90837f1528df8a749f88b3fe07ba663c8cf522692960da86fd04bdb950057c` | **87,481,971 B** | **YES — current on phone** |

* djmix8 vc inferred (pre-brief); preserved as rollback target. All djmix15 cert re-verified oldkey, `arm64-v8a`-only, package `com.music.bitchord.loopfix`, sdk 26 / targetSdk 36. All earlier APKs preserved — never delete; each is the rollback target for its generation.

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
- **djmix15 (vc57):** (1) ungated priming (25 s window) so the standby warms instead of stalling; (2) `RetireGuard` loop BPMs/tail from `session.plan` + volume-mute-before-retire → `loop-complete`; (3) `NO_DEADLINE` sentinel + `clear()` + 120 s horizon; (4) `isNextEdit(handedOff,…)` queue-edit gate; (5) playhead-anchored catch-up (`:catch-up` exempt from re-timing) + universal late-scrub handling. See §4.

### 3.3 What vc57 proves, what it does not [PROVEN / UNPROVEN]

- **[PROVEN]** In-place install, pid steady, zero BitChord crashes.
- **[PROVEN]** Beautiful→Never LoopMix `loop-complete` (`LOOP_RUNNING`, `handoff=true`, BPM 99.98/104.49, `gate 0`). No `guard`/`tail` on that path.
- **[PROVEN]** Catch-up re-anchor S.A.D→LAST (`planned 194,271 actual 194,464 +193`, `planned-fade-done`, `handoff=true`). 118× better than vc56's `+22,717`.
- **[PROVEN]** Classic natural completions LAST→Good (`+127`) + Good→intentions (`+119`, `planned-fade-done`, `handoff=true`). All classic `cueErrorMs=0`.
- **[PROVEN]** Scrub `CANCEL` (72×) + `EMERGENCY_TAIL` (1×) with playhead preservation, `violated=0` file-wide.
- **[UNPROVEN]** `Never Let Go → S.A.D` still plan-only plain advance without fire (§7.1). djmix15's ungating did not unblock this 104→66 pair (quality `none` throughout).
- **[UNPROVEN]** `116245 ms over` residual wall-minus-media overrun (§7.2). `374320/388005` gone; class reduced, not closed.
- **[UNPROVEN]** Acoustic `<50 ms` hole + taste/beat-match. Telemetry proves the state machine; the envelope has not been heard.

---

## 4. Code & build provenance (Tasks 5–6) [PROVEN on disk]

| Item | Value |
|---|---|
| Delta patch | `/home/hfzhkn/bitchord-djmix15.patch` (855 lines, 10 files, `sha256 67a95fa614ba14d246e26668c54203701b2867c42b8a02e9d3e063b6e556dd7c`) over djmix14 worktree |
| Full patch | `/home/hfzhkn/bitchord-djmix15-full.patch` (5045 lines, 41 files, `sha256 a98173f718b9213b38de8cf65df2312413f0ed63750a90455557c2d6082e301c`) from `dc78e0d` |
| Files (delta) | `CrossfadeController.kt`, `CatchUpPlanner.kt`, `DeckBudget.kt`, `DjMix.kt`, `LoopMix.kt`, `RetireGuard.kt`, `SeekAction.kt`, `TransitionPlanner.kt`, `Djmix15RegressionTest.kt`, `build.gradle.kts` (`grep -c "^diff --git"` = 10 / 41) |
| Tests | `Djmix15RegressionTest` (5 vc56 live faults: freeze asymmetry, loop scalars, 374320 sentinel, queue-edit gate, catch-up re-anchor) + all suites green per cloud build logs |
| Build | `:app:assembleProdRelease` SUCCESS; cert oldkey match; `arm64-v8a` only; package `com.music.bitchord.loopfix`; sdk 26 / target 36 |

Fix key (referenced as Fix #1–#5 in PROOF-REPORT):

1. **Prime deadlock:** removed `nextStreamReady` gate on `primeLoopDecks` (priming streams via `copyToRead`) and on classic `begin()` (25 s window).
2. **Loop scalars:** `RetireGuard.loopOutBpm/loopInBpm/loopTailMs` read `session.plan`; mute retiring deck before retire so the guard does not divert a finished loop to `emergency-retire-guard`.
3. **Overrun sentinel:** `DeckBudget.NO_DEADLINE_MS` (never `0L`), `DeckTiming.clear()` before build, 120 s horizon in `lateStepReason`, `provenOverrunMs` `<=0` guard.
4. **Queue-edit:** `SeekAction.isNextEdit(handedOff,…)` + standby-snapshot gate after handoff.
5. **Catch-up re-anchor:** playhead-anchored lead (0.6–1.5 s) + `:catch-up` exempt from `DjMix.annotate`/`clashFilter` re-timing + universal `DeckBudget` late-scrub handling.

---

## 5. Device proof summary (Task 7) [PROVEN ON DEVICE TELEMETRY — exact seq lines]

Full seq-by-seq tables are in `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/PROOF-REPORT.md`. Headlines (all in `telemetry-pull8-final.jsonl`, L3985–L4288 = vc57 seq1–304):

- **5.1 Proof 1A LoopMix (Beautiful→Never):** L4047 `seq63 fire loop -787 (planned 170,120 actual 169,333, preroll 1.0, splice 170,120)` → L4048 `seq64 gate violations=0` → L4049 `seq65 complete LOOP_RUNNING handoff=true bpmA=99.987 bpmB=104.495 tailMs=0 reason=loop-complete`. No guard/tail/freeze/overrun in window.
- **5.2 Proof 1B Never→S.A.D:** L4075–L4080 `seq91–96 classic DJ_FILTER fade 9.13→1.20` → L4081 `seq97 M6` (next pair). **Zero fire/gate/tail/complete**; idx1→idx2 plain advance.
- **5.3 Proof 2 CatchUp (S.A.D→LAST):** L4128 `seq140 fire classic planned 194,271 actual 194,464 delta +193 cue 0` → L4129 `seq141 tail planned-fade-done driveFade-done` → L4130 `seq142 complete FADING handoff=true bpm 66.48/120.64`.
- **5.4 Proof 3A CANCEL:** L4216–L4232 `seq230–248 cancel (ARMING/LOOP_ARMING/IDLE)` + `scrub-cancelled` + one fresh plan each; **zero ghost fire**; playhead 130,617.
- **5.5 Proof 3B EMERGENCY_TAIL:** L4155 `seq171 fire FADING +127` → L4156 `seq172 FADING 1404→0 emergency_tail` → L4157 `seq173 tail seek-emergency-tail` → L4158 `seq174 complete TAIL handoff=true bpm 120.64/103.13`.
- **5.6 Bonus natural classic (Good→intentions):** L4252 `seq268 fire +119` → L4253 `seq269 tail planned-fade-done` → L4254 `seq270 complete FADING handoff=true`. Second `planned-fade-done` on vc57.
- **Health:** pid `32159` steady (`pid-start.txt`==`pid-end.txt`); crash 0 BitChord (1304 lines, grep 0); `exit-info.txt` only `PACKAGE UPDATED` (30026) + pre-existing `LOW_MEMORY` (1373); `dumpsys-after.txt` vc57; `media_session-final.txt` `PAUSED Beautiful idx0 0 ms`; `screen_off_timeout` untouched at 30 s.

**`mix.fire` accuracy (all fires, lifetime):** classic `cueErrorMs=0` on all three vc57 fires (`+193/+127/+119`); loop `−787` (no `cueErrorMs` by design). Best classic accuracy remains vc56 `+103` / vc57 `+119` (comparable; both ~4× the ±25 ms ideal, reported as-measured). Catch-up lateness fell from `+22,717/+20,090` (vc56) to `+193` (vc57).

**vc55 → vc56 → vc57 comparison (fires / tails / gates / completes / seeks / freezes):** PROOF-REPORT §7 has the full table. Salient: vc57 fires 4 in 304 lines (highest sustained rate); `violated` 0 in all three windows; `freeze.queueEdit` 0 in vc57 (vs 2 in vc56 session, 17 lifetime); `374320/388005` 0 in vc57 (one 116245 residual); `planned-fade-done` 2 in vc57 (plus 1 `seek-emergency-tail`, plus 1 `loop-complete`).

---

## 6. Honesty Box & method

**[PROVEN ON DEVICE TELEMETRY]** (PROOF-REPORT §10): items in §3.3 / §5 above. **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**: acoustic `<50 ms` hole + dissolve audibility + beat-match/taste. `adb screenrecord` is mic-only; **no screenrecord was taken**. The Apple-pledged Definition of Done (tail + no hole + ears) keeps legs 2–3 open.

**Method constraints (same as vc56):** PIN-locked (`Dozing` / `isKeyguardShowing=true`) → UI taps impossible; all seeks via `cmd media_session dispatch FF (+15 s)/RW (−5 s)` (verified `DISCONTINUITY_REASON_SEEK`); hands-off single sleeps (no polling loops); screen Dozing during loops. Playback positions are `dumpsys media_session` readings, not telemetry-inferred.

---

## 7. Residual gaps (do not oversell)

### 7.1 `Never Let Go → S.A.D` (Fix #1) — STILL OPEN

- The pair that motivated Fix #1 (104.49 → 66.48 BPM) still plain-advances without fire in vc57. Classic arms and fires on LAST→Good and Good→intentions, so the unlock is not globally inert, but this pair's `quality none` conditions still block arming. Root-cause work remains (phrase/loop arming conditions vs analysis readiness for this tempo gap).

### 7.2 `116245 ms over` (Fix #3) — REDUCED, NOT CLOSED

- L4242–L4243 `seq258/259` is the span-path wall-minus-media subtract (no horizon check in `provenOverrunMs`). The 120 s horizon in `lateStepReason` only guards the final path. The `374320` literal is gone in 304 lines, but the class persists at 116 k.

### 7.3 8× `emergency-retire-guard` in LOOP_ARMING aborts

- Expected pre-takeover aborts (scrub-cancelled loop builds) still tail with the guard. The completed-loop path no longer does (§5.1). Count is in §8 for context.

### 7.4 Loop cue tightness + taste

- Loop `deltaMs=-787` (early ~0.8 s) and classic `+119…+193` (late ~0.1–0.2 s) are not ±25 ms. Tamper-proof for handoff, but beat-match/taste cannot be claimed from telemetry.

---

## 8. Next steps (strict order)

1. **User internal-audio recording + listening verdict (Definition of Done).** Phone is **PAUSED at `Beautiful` idx0, 0 ms** (`media_session-final.txt`). Unlock phone, start internal-audio screen recording (Side-key / screen-record **internal audio ON**, e.g. as in `VID_20261112_151114`), play `Beautiful (Bazzi/Camila) → Never Let Go (LNGSHOT)`, stop after Song 2 settles, send the mp4. Gate check: silence gap < 50 ms + dissolve ≥ 300 ms on the envelope + explicit taste/beat-match verdict. If the gate fails, file the mp4 + telemetry pull and reopen the relevant Fix (§7).
2. **In-place protocol (no uninstall/clear):** `adb install -r` only; pull telemetry **before** any install into a new sequential directory; keep `djmix14-proof/` and `djmix15-proof/` 100% untouched; `-s 192.168.100.218:41699` after `adb mdns services` + `adb devices -l` re-verify (port flips on wireless-debugging toggle).
3. **Residual forensics for NLG→S.A.D + 116245:** full-analysis head-vs-full timing for this pair, loop build staging under `quality none`, and `provenOverrunMs` span-path horizon. Patch on cloud (`bk-android`) tree, **never locally**.
4. **Regression suite:** keep `Djmix15RegressionTest` in the gate; do not ship a build that breaks it.
5. **Do not claim audio success from telemetry.** Burn-in satisfaction still requires the ears.

---

## 9. File inventory

### 9.1 APKs & patches — canonical

APKs all under `/home/hfzhkn/Downloads/` (8 preserved: djmix8–djmix15). Patches:

- `/home/hfzhkn/bitchord-djmix15.patch` (855 lines, 10 files, `67a95fa6…`).
- `/home/hfzhkn/bitchord-djmix15-full.patch` (5045 lines, 41 files, `a98173f7…`).
- Earlier: `/home/hfzhkn/bitchord-djmix14.patch` (2108 lines, 19 files, `dbdbbb0a…`), `/home/hfzhkn/bitchord-djmix14-full.patch` (3986 lines, 35 files, `f645c223…`), plus all djmix9–djmix13 patches (see LIVE).

### 9.2 Evidence (djmix15)

| Path | Role |
|---|---|
| `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/PROOF-REPORT.md` | **Primary honesty-graded report** (this sprint). |
| `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/telemetry-pull1-preinstall.jsonl`–`pull8-final.jsonl` | Sequential pulls, never overwritten (3984→4288; vc57 boundary L3985). Hashes in §10. |
| `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/dumpsys-before.txt`, `dumpsys-after-install.txt`, `dumpsys-after.txt` | Package version proof (vc56→vc57). |
| `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/media_session-*.txt` | Position/queue proof (idx0–idx5 chain; final PAUSED Beautiful). |
| `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/install.log`, `launch.log`, `pid-*.txt`, `crash-log.txt`, `exit-info.txt` | Health proof (pid 32159 steady; 0 BitChord crashes). |
| `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/adb-mdns.txt`, `adb-devices-before.txt`, `apk-sha.txt`, `pull*.log` | Reproducibility log. |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-proof/telemetry-pull7-final.jsonl` (`d3d76955…`, 3980) | vc56 final (untouched reference; `djmix14-proof/` intact). |

### 9.3 Reports (primary)

| Path | Verdict |
|---|---|
| `/home/hfzhkn/bitchord-device-evidence/video-VID_20261112_151114/FINDINGS.md` | Day-one hole measured: 258.2 ms silence, ~15–20 ms fades. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-forensics/FORENSICS.md` | "(2)" = byte-identical pre-fix video; 586:15 rejection audit; BK portable traits. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-proof/PROOF-REPORT.md` | **PARTIAL**: guard proven; `planned-fade-done` never fired. |
| `/home/hfzhkn/bitchord-device-evidence/djmix13-proof/PROOF-REPORT.md` | **INTERIM** — build delivered, install pending (listener down). |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-forensics/FORENSICS-DJMIX14.md` | **Bug Classes 1–4 + video correlation** (3750-line vc55 baseline; plan→fire 2.2%, 0 `planned-fade-done`). |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-proof/PROOF-REPORT.md` | **CLASSIC `planned-fade-done` ×2 + Loop + B2 + B3 [PROVEN]** (pulls 3762→3980, pid 1373). |
| `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/PROOF-REPORT.md` | **Loop `loop-complete` + re-anchor + accuracy + B2/B3 [PROVEN]; NLG→S.A.D + 116245 noted** (pulls 3984→4288, pid 32159). |
| `/home/hfzhkn/bitchord-device-evidence/djmix11-proof-20261112T0922Z/ANALYSIS.md` | djmix11 telemetry pass. |
| `/home/hfzhkn/bitchord-device-evidence/userplay-20261112/TASK3-ROOTCAUSE-TABLE.md` | Day-one root-cause table. |

### 9.4 Source / reference

| Path | Role |
|---|---|
| `bk-android:/data/workspace/bitchord-task2/work/BitChord` @ `dc78e0d` (+ staged + uncommitted deltas) | **BitChord source of truth** (box only). |
| `/home/hfzhkn/bkmasterplayer/app/rust/src/engine/{analysis,automix,mixer,deck}.rs` | BK reference only. |

---

## 10. Corrections found on disk vs the commissioning brief (2026-11-13 audit)

This handover re-verified everything below on disk before writing — corrections applied:

1. **Pre-state pull1 is 3984, not 3980.** Lines 1–3980 are byte-identical to djmix14 final. L3981–L3982 are two post-final vc56 plans; L3983–L3984 are a two-line vc56 restart from the pre-install monkey (pid 30026). vc57 boundary is L3985 (pid 32159). Documented in PROOF-REPORT §1; no history rewritten.
2. **Pull2 shell `wc` 3986 vs pulled 3985.** One vc57 line (`seq2 plan`) landed during the transfer. Pull2 is preserved at 3985 as the boundary snapshot; `seq2` is in pull3 onward and in the 304-line vc57 delta.
3. **Proof 1B frame.** The brief's `125,000–132,000` assumed duration `~172,000`; buffered is `193,641`. Landing at `143,156` (T-50.5 s to buffered) preserves the 40–47 s invariant under the buffered-duration reading. The pair still plain-advances without fire.
4. **116245 residual.** Brief's Fix #3 expected "ZERO `374320` over". That literal is zero, but one `116245 over` (span path, §7.2) is the same class. Reported as partially proven, not closed.
5. **3B staging.** The cleanly-staged second 3B attempt landed post-handoff (`IDLE cancel`, as in vc56). The counted 3B window is the first FADING interruption (`seq171–174`); the second attempt is preserved in pulls but does not count.
6. **File counts + hashes re-verified.** djmix15 delta = **10 files / 855 lines** (`67a95fa6…`), full = **41 files / 5045 lines** (`a98173f7…`) via `grep -c "^diff --git"` + `sha256sum` + `wc -l`. APK = `ca90837f…` / **87,481,971 B** (`stat`). All eight APK hashes match on-disk `sha256sum`.
7. **Line counts.** LIVE was 434 lines at audit, now **447 lines** after append-only milestones (no truncation). Evidence pulls 3984→4288, all hashes in PROOF-REPORT §9.

---

*End of handover. Next action belongs to the user (internal-audio recording + listening verdict) followed by §8 steps 2–5 in strict order. Phone left PAUSED (Beautiful idx0 0 ms) on vc57, queue unmodified.*
