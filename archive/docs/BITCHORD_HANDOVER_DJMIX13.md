# BitChord DJMIX13 Handover — 2026-11-12 (supersedes brief snapshots; live log remains the append-only diary)

## Read first / honest state in 5 lines

> **Phone has 2.1.0-djmix12 vc54 (last proven install 2026-11-12 18:25:52). 2.1.1-djmix13 vc55 is BUILT, signed oldkey, NOT installed — blocked only on the wireless-ADB listener being off. The classic fade-`done` tail (`mix.exit.tail reason=planned-fade-done`) has NEVER fired on device (telemetry-proven). No agent has "heard" any fix — `adb screenrecord` is mic-only; audio proof needs a user internal-audio recording. Next step is strictly: user toggles Wireless debugging ON → harvest → install vc55 → Proof A (classic completes) + Proof B (scrub cancel+replan).**

Status tags in this doc: **[PROVEN]** = verified on disk/device telemetry this 2026-11-12 sprint; **[UNPROVEN]** = coded/tested/build-passing but never observed on device audio. Never upgrade an `[UNPROVEN]` to proven without telemetry + a fresh internal-audio capture.

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
| Debuggable | No. No PIN lock involved. App is not debuggable; introspection is via telemetry JSONL + dumpsys + logcat, not a debugger. |
| Live diary | `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` (append milestones; do not rewrite history) |
| This file | `/home/hfzhkn/BITCHORD_HANDOVER_DJMIX13.md` (new 2026-11-12; old handovers preserved untouched) |

---

## 2. Current state + version table

### 2.1 What is on the phone vs on disk

- **INSTALLED on phone: 2.1.0-djmix12 vc54** [PROVEN at install time 2026-11-12 18:25:52, corroborated in `djmix12-proof/PROOF-REPORT.md` + dumpsys-before/after]. Installed via wireless ADB mDNS serial `adb-1439ebea-05jQT0`, port flipped `40601 → 43659` after the user re-toggled. **Not re-harvested this handover sprint** (listener currently down) — treat as last-known, re-verify with dumpsys on next connect before anything else. No BitChord crash observed (pid 17629 steady across the djmix12 session).
- **BUILT, NOT installed: 2.1.1-djmix13 vc55** [PROVEN built+signed, NOT on device]. Blocked only on the phone wireless-ADB listener being off. Build `:app:assembleProdRelease` SUCCESSFUL 8m11s, log `/tmp/release-build13.log` on the box. Tests TOTAL **1825 FAIL 0** incl. `DeckBudgetTest` 5 + `SeekActionTest` 3. No build-fix code changes were needed.
- **No classic `planned-fade-done` has ever fired.** djmix12's 7× classic `mix.exit.tail` were all the retire-guard emergency path inside `finish()` (LOOP_ARMING/LOOP_RUNNING), not a natural fade completion. djmix13 has not run on any device yet, so its fixes are `[UNPROVEN]`.

### 2.2 Version table (all hashes + sizes re-verified on disk 2026-11-12)

| Release | versionName / vc | APK path | SHA-256 | Size | Installed? |
|---|---|---|---|---|---|
| djmix9 | 1.9.1 / vc51 | `/home/hfzhkn/Downloads/bitchord-1.9.1-djmix9-automix-arm64-v8a-oldkey.apk` | `b0af10490acefc85c2be30376e7e6aade973b9c8d0e21d2ffdc4327deb681c47` | 87,573,558 B | No (preserved) |
| djmix10 | 2.0.0 / vc52 | `/home/hfzhkn/Downloads/bitchord-2.0.0-djmix10-automix-arm64-v8a-oldkey.apk` | `82ebbbf5dec8b5194889cb8df450433ba09b1adbf8820635663a8cd875fe1889` | 87,465,587 B | No (preserved) |
| djmix11 | 2.0.1 / vc53 | `/home/hfzhkn/Downloads/bitchord-2.0.1-djmix11-automix-arm64-v8a-oldkey.apk` | `357e281d34e9f7222b2fd43196eaec0733b8db717c2c6f43aee8444074bdf029` | 87,465,587 B | Superseded by vc54 |
| **djmix12** | **2.1.0 / vc54** | `/home/hfzhkn/Downloads/bitchord-2.1.0-djmix12-automix-arm64-v8a-oldkey.apk` | `a25900e9647534511b36fdb70abfc914b1fb5eaceb1010b0a7660f4848a1eb8d` | 87,465,587 B | **YES — current on phone** |
| **djmix13** | **2.1.1 / vc55** | `/home/hfzhkn/Downloads/bitchord-2.1.1-djmix13-automix-arm64-v8a-oldkey.apk` | `2237caa1dc72b4afb52c2633585debd8cb5cbe8f6e75fd40a806fedd8dd69f9b` | **87,465,583 B** | **NO — staged, ready** |

djmix13 cert re-verified oldkey, `arm64-v8a`-only, package `com.music.bitchord.loopfix`, sdk 26 / targetSdk 36. All earlier APKs preserved in `/home/hfzhkn/Downloads/` — never delete them; each is the rollback target for its generation.

---

## 3. Bug history / evidence (proven vs unproven)

### 3.1 The day-one bug — measured, not anecdotal [PROVEN measurement]

- **Symptom (user):** outgoing track cuts "like pause". **UI rung at the hole:** `Smooth fade · 3s · 106→109 · locked`, RELAXED, at **1:19 / 2:13** on "amoureux" — Alleh.
- **Measurement** (`/home/hfzhkn/bitchord-device-evidence/video-VID_20261112_151114/FINDINGS.md`, re-run 2026-11-12 ~16:25Z, full detail in `TASK1-VIDEO-REPORT.md` same dir): **~15 ms effective fade-out → 258.2 ms bit-exact silence** (239.1 ms exact-zero @ 0.1987–0.4378 s; sub-−40 dB 282 ms) **→ ~20 ms fade-in**. Graceful target is 300–800 ms. The measured fade is 20–50× shorter than even the advertised 120 ms swap ramp. One event only; rest of the 8.38 s clip is music-level.
- **Root causes (code, [PROVEN] by reading + telemetry):**
  1. `TransitionMoves` RELAXED branch: `outGain` lane **flat at unity** through the landing — never falls; `Envelope::at` holds 0 dB forever.
  2. Native `renderDeck` **hard stop at `stopAt`, no ramp** — silence is structural, not a timing accident.
  3. Classic `BAIL_MS` 120 ms path retired the outgoing deck **instantly**.
  4. Splice landed **one bar early** (hole ~2.3 s before the planned blend start; countdown "0s" had no audible/UI consequence).
- This video is an **internal-audio capture** (digital zeros, HEVC 720×1280 + AAC-LC 44.1 kHz stereo, 8.38 s) — the only trustworthy audio evidence class. `adb screenrecord` is **mic-only** and must never be quoted as hearing the app.

### 3.2 The "(2)" video proves NOTHING new [PROVEN — important]

- The user's re-sent `VID_20261112_151114 (2).mp4` is **byte-identical** to the original: same MD5 `8057417676168876f0b49cf2da0291d5`, same 518,637 bytes, same container `creation_time=2026-11-12T08:11:17Z` (= 15:11:17 +0700). Re-verified this sprint in `/home/hfzhkn/Downloads/`.
- It was filmed **before** the djmix11 install (djmix11 vc53 installed **16:20:56 local** on 2026-11-12, ~5 h after the video). It is the same day-one event re-sent, not a post-fix regression.
- **Consequence:** djmix11/12 have **not** been proven to fail on audio. A fresh **post-fix internal-audio** recording (user taps Side-key / screen-record with internal audio) of a transition is still missing and is part of Definition of Done (§9). Do not cite the "(2)" file as evidence about djmix11+.

### 3.3 Evidence dirs (all preserved, originals untouched)

| Path | What it holds |
|---|---|
| `/home/hfzhkn/bitchord-device-evidence/video-VID_20261112_151114/` | `FINDINGS.md` + `TASK1-VIDEO-REPORT.md` + `audio.wav`, waveforms, spectrogram, 1fps/3fps frames, crops. The day-one measurement corpus. |
| `/home/hfzhkn/bitchord-device-evidence/userplay-20261112/` | User-play session 15:21: dumpsys, logcat-post, media_session, `TASK3-ROOTCAUSE-TABLE.md`, telemetry pulls (pre + djmix11). |
| `/home/hfzhkn/bitchord-device-evidence/djmix11-proof-20261112T0922Z/` | `ANALYSIS.md` + 5 telemetry pulls + passive samples + screen/ui dumps. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-forensics/` | `FORENSICS.md` (the forensics report incl. portable BK traits, §3.4) + `newbug.mp4`/`bkrecord.mp4`, RMS npz, envelope CSVs/SVGs, frames. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-proof/` | `PROOF-REPORT.md` (verdict: PARTIAL) + `telemetry-pull1..6` (pull-1 pre-install 3507 lines), dumpsys-before/after, adb-connect/mdns logs, `apk-sha.txt`, install.log, crash/exit-info. |
| `/home/hfzhkn/bitchord-device-evidence/djmix13-proof/` | `PROOF-REPORT.md` (**interim**: adb-connect/devices/mdns logs only). All post-install pulls belong here, pull-1 = pre-install harvest. |

### 3.4 Inspiration / BK reference (verified, not folklore)

- Reference implementation: local **BK MasterPlayer** at `/home/hfzhkn/bkmasterplayer`, engines `app/rust/src/engine/{analysis,automix,mixer,deck}.rs` (+ `analysis_worker.rs`, `recommend.rs`, etc.). Verified present this sprint.
- BK YTM screenshots + screen recording in `/home/hfzhkn/Downloads/`: `Screenshot_2026-11-12-17-18-*io.github.playermusica.player_musica.ytm*` (DesOrden → amoureux pair) and `Screenrecorder-2026-11-12-17-22-05-805.mp4` ("Mixing: 2 bars, 109→106 BPM (−2.8%), 5A→9B clash/filter"). Screenshots confirmed on disk this sprint.
- **Portable traits** (verified file:line in `djmix12-forensics/FORENSICS.md` — read that section before re-deriving): **±8 % stretch cap**, **16-bar tempo return**, **downbeat snap ±25 ms**, **mid-band 200–2000 Hz P75 outro**, **bass swap bars/2**, **echo fb 0.55 past EOF**, **soft-clip**.
- **Constraint:** BK states the same patent-scalars rule BitChord follows (§7): only scalars (BPM/Camelot/LUFS/vocal-count) cross decks — never raw audio features. BK is reference-only; it is not BitChord source.

---

## 4. What each release did (and what it proved)

### 4.1 djmix11 (vc53, installed 16:20:56) — structural exit-tail [PROVEN built/installed; UNPROVEN on audio]

- **Code:** `withExitTail` (2 beats, floor 800 ms cap 2 s, emergency 350 ms, mute knot); native OUT `stopAt` extended + flanger wet 0.42 + exit gate; classic `Phase.TAIL` (`beginTailOrFinish`/`driveTail`); telemetry `mix.plan.exitTailSec` / `mix.exit.tail` / `mix.exit.gate`.
- **Device:** exactly **1 native M6** transition, gate `violations=0` (structural only, **no audio capture**). Classic TAIL **never fired** — classic plans died before FADING. The re-sent "(2)" video does not refute or confirm djmix11 (§3.2).

### 4.2 djmix12 (vc54, installed 18:25:52) — retire-guard + tempo discipline [PROVEN on device, PARTIAL]

- **Patch:** delta `/home/hfzhkn/bitchord-djmix12.patch` (567 lines, 10 files, `32f825ade29cd692fe9abe57704136647bb754d72e56185f4b27fa1d63a1f8a0`); cumulative `/home/hfzhkn/bitchord-djmix12-full.patch` (2,263 lines, **27 files**, vs `dc78e0d`, `eef252364b4b493df31e05cf40b2760951fd9686e300d24a6904cd96ea44023b`). Both hashes re-verified on disk this sprint.
- **Code:** classic **retire-guard** — `finish()` may only retire a silent deck; an audible retire is diverted to `Phase.TAIL` with the 350 ms emergency dissolve (`RetireGuard.kt`, `mix.exit.guard` / `mix.exit.violated` / `mix.complete`); `mix.plan` now emits for **classic** plans too; `exitTailSec` sharpened to **16 equal-power knots**; deduped fallback/reject telemetry; `PREP_TOLERANCE_MS=50`; shared `alignedFold()` (kills the 74/105→148 divergent folds and the LoopMix **+42.5 % lurch**); RELAXED **vetoed above 8 % tempo delta → echo/cut**; `rankMixOutCandidates` scalar vocal adjustment; `bail()` freeze now covers ARMING (not just FADING). Tests **1801/0** (`Djmix12TempoTest` 4/4, `RetireGuardTest` 5/5).
- **Device proof — PARTIAL [PROVEN]:** session shows classic `mix.plan` ×7 (all `exitTailSec 0.8`) + M6 ×1; `mix.exit.guard` ×7; **`mix.exit.tail` 7× @800 ms planned, 805–828 ms measured** (emergency-retire-guard inside `finish()` at LOOP_ARMING/LOOP_RUNNING); `mix.complete` ×7; `mix.exit.gate` ×1 `violations=0`; **`mix.exit.violated` absent (0 in whole file)**. Verdict in `djmix12-proof/PROOF-REPORT.md`: classic cut **structurally proven + guard device-proven, classic fade-complete still UNPROVEN** — **`planned-fade-done` NEVER fired**: classic plans died 6/8 in ARMING (3× watchdog, 2× splice-136 s / 49012-class, 1× splice-3648). Queue-edit/next/freeze behavior was freeze-then-dissolve, never an instant retire — but the engine never survived ARMING long enough to demonstrate a natural fade completion. Residual `mix.plan.rejected phrase-null` spam continues (~1/s when inconclusively cued).
- **Historic audit (context for djmix13 fixes):** deduped union showed rejected:plans **586:15 (97.5 %)**, fallbacks dominated by `outgoing stream 17 ms over` (145) + `outgoing splice 49012 ms over` (101) (`decode-17203` only 12/minute-class noise); **60 % of BPM deltas exceeded ±8 %** incl. four at +42.5 % (now vetoed in RELAXED). These bogus "over" numbers are the direct fingerprint of the wall/media-epoch mixing djmix13 fixes (§4.3).

### 4.3 djmix13 (vc55, BUILT not installed) — deadlines + scrub + firing instrumentation [UNPROVEN on device]

- **Patches (names corrected on disk — §10):** delta `/home/hfzhkn/bitchord-djmix13.patch` (**968 lines, 14 files**, `5fb2e2d300c5be748827dc24eb75310857faba802530ade15bd29f691628e96f`); cumulative `/home/hfzhkn/bitchord-djmix13-full.patch` (**2,642 lines, 31 files**, vs `dc78e0d`, `e816bfd57a0eb34c44d4f748f30380815c31cf9b78bbf5860730e7d5b691cc43`). Both re-verified this sprint; full = delta + staged djmix10/11 + djmix12 deltas, recounts are `grep -c "^diff --git"`.
- **Root causes found from djmix12 telemetry + code [PROVEN as code facts; device effect UNPROVEN]:**
  1. **Deck deadlines mixed wall-clock with media position** (`elapsedRealtime + (targetStartMs − positionMs)`): a paused/frozen position makes the deadline instant-passed → fake "17 / 49012 / 136249 ms over" verdicts (`CrossfadeController` ~L3202/3251/3449; `lateStepReason` `LoopMix.kt` ~L276). Dominant death families in djmix12 telemetry: 3× watchdog + 3× splice (6 of 8 classic plans died pre-FADING).
  2. **Scrub path:** `onPositionDiscontinuity(SEEK)` called `bail()`, which (in djmix12) **freezes ARMING/FADING** — so a mid-blend scrub desynced the blend geometry (abrupt/bad firing) and a **stale shown-countdown ghost-fired** past the scrub.
- **Fixes shipped in vc55:**
  - **Shared `DeckBudget`** (new `shared/.../DeckBudget.kt`, pure, unit-tested) — pause-proof deadlines at **3 call sites** (no budget while paused, speed-scaled), so frozen media time can no longer fabricate lateness.
  - **`lateStepReason` only claims provable lateness** (regression-tested) — "paused (no budget)" otherwise.
  - **Watchdog hardening:** suppresses the failed loop pair for **90 s** and **drops the shown countdown**, so the classic plan gets time to complete instead of immediately re-dying; new `mix.arm.death {reason, phase, msSincePlan}` on watchdog / arm-expired / loop-fallback.
  - **`SeekAction` scrub protocol** (new `shared/.../SeekAction.kt`): non-audible scrub = **CANCEL + replan** (marker cleared, no ghost fire); audible scrub = **emergency 350 ms tail** (never instant); `mix.seek` telemetry; forced `bail(force=true)` bypasses freeze only on the scrub-cancel path.
  - **`mix.fire {plannedStartMs, actualStartMs, deltaMs, cueErrorMs}`** at every classic blend start — the firing-jitter instrument (target cue accuracy ±25 ms; report the delta, do not pre-claim it).
  - Tests: `DeckBudgetTest` 5 + `SeekActionTest` 3; full suite **1825/0**. Build SUCCESSFUL 8m11s, `arm64-v8a`-only, oldkey cert match.
- **Honest status:** zero device behavior has been observed for vc55. Code + unit tests + build are proven; **scrub behavior, firing deltas, and any audio smoothness are entirely [UNPROVEN]** until §9 Proof A/B run.

---

## 5. Source / build

| Item | Value |
|---|---|
| Build box | `bk-android` (cloud). **No local BitChord source tree** — `bkmasterplayer` is reference only. |
| Tree | `/data/workspace/bitchord-task2/work/BitChord` @ `dc78e0d` + staged djmix10/11 + **uncommitted** djmix12/13 deltas. **CHECK `git status` first next session** before touching or re-patching; the worktree diff is the source of truth, not any single patch file. |
| Other boxes | `tanyadf-standby` is **absent/suspended** (not on the account; 5 boxes listed, all suspended). Do not route builds there. |
| Build mode | Cloud builds only, **background job + `/tmp/release-build*.log`** on the box; do not sleep-poll in loops. |
| Binary transfer | `instabox` `exec`/`files` truncate binaries — to retrieve APKs use **`instabox ssh ... cat`** (streams the full binary). This is how djmix12/djmix13 APKs reached `/Downloads/`. |
| Secrets | **Never write API keys/passwords to files.** Keystore (`keystore.properties` + `bitchord-loopfix.jks`) is used **as-is** — no new key, ever. |
| Release cadence | **Same oldkey + versionCode bump every release.** Current: vc55. Treat any "re-key" suggestion as a hard stop. |

---

## 6. Device / ADB

- **Transport:** wireless mDNS `adb-1439ebea-05jQT0._adb-tls-connect._tcp`. Last known IP **192.168.100.218**, ports seen **40601, 43659** (ports change; re-resolve via mDNS each connect). PC on **192.168.100.11/24**, same SSID required.
- **The listener keeps turning OFF** (after each power cycle / idle / dev-options reset). **Unblock:** user unlocks phone → same Wi-Fi → Developer options → **Wireless debugging ON** (off/on toggle if stale) → says "adb up". Then the agent runs `adb mdns` / `adb connect` and harvests. Do not hammer connects in a loop.
- **App constraints:** not debuggable, no PIN. `adb screenrecord` captures **mic only, NOT internal audio** — never claim to have heard a transition from it. Audio proof requires the user's **internal-audio screen recording** of a transition.

---

## 7. Telemetry (the only truth — release strips `android.util.Log`)

- **Path:** `/sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl`
- **Discipline:** **pull BEFORE install, preserve pull order** (pull-1 = pre-install harvest; post-install pulls are 2, 3, …). Hash + count every pull; never overwrite a pull. Pulls live in the djmix-proof dirs.
- **Key events to grep (in priority order):**
  `mix.plan` (incl. `exitTailSec`), `mix.exit.tail`, `mix.exit.gate`, `mix.exit.guard`, **`mix.exit.violated` (must be ABSENT — any hit is a kill-claim failure)**, `mix.complete`, **`mix.arm.death {reason,phase,msSincePlan}`** (new vc55), **`mix.fire {plannedStartMs,actualStartMs,deltaMs,cueErrorMs}`** (new vc55), `mix.seek` (new vc55), `mix.loop.fallback`, `mix.plan.rejected`.
- **Phase gates for proof:** classic success = `mix.exit.tail reason=planned-fade-done` + `mix.complete`, gate `violations=0`, `mix.exit.violated` absent, `mix.fire deltaMs` reported (target ±25 ms cue accuracy). Anything else is a named-cause emergency tail (still not a cut, but not the natural-fade proof).

---

## 8. Hard rules (do not violate)

1. **Loop crown:** the shown countdown always plays; **djmix6 eligibility / bar counts untouched** (djmix13 changes no loop-window sizes).
2. **Patent scalars-only:** A-exit from A, B-entry from B; only scalars (BPM / Camelot / LUFS / vocal-count) cross decks. Never port BK audio-feature tensors.
3. **GPL-only** licenses.
4. **Do not touch vendored Signalsmith headers.**
5. **No `setPlaybackSpeed` for mix tempo** (tempo via the render path, not the player rate).
6. **No audio-thread alloc/locks.**
7. **Same oldkey + versionCode bump every release.**
8. **UI layout on hold** — no layout changes without explicit user instruction.
9. **Preserve originals** — evidence files, APKs, patches, pulls are append-only.

---

## 9. User complaints still open (2026-11-12, latest) + Definition of Done

| # | Complaint | Status |
|---|---|---|
| 1 | **Scrubbing messes up automix** | Addressed in vc55 code (`SeekAction` CANCEL/HONOUR/EMERGENCY + `mix.seek`) — **[UNPROVEN on device]**. |
| 2 | **Tracks not firing properly, abrupt / not smooth** | Firing-delta instrumentation (`mix.fire`) ships in vc55; accuracy and smoothness **[UNPROVEN on device]**. |
| 3 | **Automix taste: "not impressed"** — wasted chances, tempo/key/vocal clashes, loading aborts | Open design-level complaint. djmix12 added the 8 % veto + scalar vocal ranking; djmix13 did not close taste. Expect iteration after proof. |

**Definition of Done (all required):** a classic fade completes on device (`mix.exit.tail reason=planned-fade-done` + `mix.complete`, gate 0, violated absent, `mix.fire delta` within target) **AND** no silence hole **≥50 ms** on a captured **internal-audio** waveform **AND** the **user ears confirm** (explicit listening verdict, e.g. Beautiful (Bazzi) → Never Let Go (LNGSHOT) pair). No single leg alone closes the bug.

---

## 10. Next steps (in order — do not skip)

1. **User toggles Wireless debugging ON** (Dev options; same Wi-Fi) and says "adb up". If `adb devices` is empty, re-resolve via `adb mdns` (`adb-1439ebea-05jQT0._adb-tls-connect._tcp`) — ports flip; do not loop connects. PC = 192.168.100.11/24.
2. **Harvest (pull-first):** dumpsys — expect **vc54 2.1.0-djmix12** (+ lastUpdate 2026-11-12 18:25:52 if unchanged); telemetry pull-1 into `/home/hfzhkn/bitchord-device-evidence/djmix13-proof/`; media_session; `adb devices`/`mdns` logs. Preserve pull order; hash + line-count the pull.
3. **Install:** `adb install -r /home/hfzhkn/Downloads/bitchord-2.1.1-djmix13-automix-arm64-v8a-oldkey.apk` → dumpsys must show **vc55 2.1.1-djmix13** → launch → pid steady + crash check (BitChord must NOT appear; Huawei HMS ClassNotFound is known noise).
4. **Proof A (classic completes):** seek near outro on the current track; require at least one classic `mix.plan` → `mix.fire` → **`mix.exit.tail reason=planned-fade-done`** + `mix.complete`, gate `violations=0`, `mix.exit.violated` **absent**, `mix.fire deltaMs` reported (target ±25 ms cue accuracy). Pull telemetry after; do not claim "heard".
5. **Proof B (scrub scenario):** arm a mix (seek near outro), then scrub away/back before it fires; require `mix.seek action=cancel`, **no ghost fire at the stale position**, **exactly one live plan** afterwards. Also exercise an audible-state scrub once (expect 350 ms emergency tail, never an instant cut).
6. **If classic still dies in ARMING:** read `mix.arm.death` dominant `reason/phase/msSincePlan`, fix as **djmix14** (same oldkey, vc bump). Do not claim fixed — a named emergency tail is progress, not Proof A.
7. **Ask the user for an internal-audio screen recording** of a transition + explicit listening verdict for **Beautiful (Bazzi) → Never Let Go (LNGSHOT)**. Only then measure the ≥50 ms-hole criterion and close the Definition of Done (§9).

---

## 11. File index

### 11.1 Handovers (never overwrite old ones)

| Path | Scope |
|---|---|
| `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` | Append-only live diary (milestones through djmix13 BUILD). |
| `/home/hfzhkn/BITCHORD_HANDOVER_FOR_NEXT_AI.md` | Prior consolidated handover (preserved). |
| `/home/hfzhkn/BITCHORD_HANDOVER_DJMIX13.md` | **This file.** |

### 11.2 Patches (all re-verified 2026-11-12)

| Path | Lines / files | SHA-256 |
|---|---|---|
| `/home/hfzhkn/bitchord-djmix10.patch` | — (19 KB) | — |
| `/home/hfzhkn/bitchord-djmix11-patch-20261112T1620Z.patch` | — (88 KB) | — |
| `/home/hfzhkn/bitchord-djmix12.patch` (delta over staged djmix11) | 567 / 10 | `32f825ade29cd692fe9abe57704136647bb754d72e56185f4b27fa1d63a1f8a0` |
| `/home/hfzhkn/bitchord-djmix12-full.patch` (from `dc78e0d`) | 2,263 / 27 | `eef252364b4b493df31e05cf40b2760951fd9686e300d24a6904cd96ea44023b` |
| `/home/hfzhkn/bitchord-djmix13.patch` (delta over djmix12 worktree) | 968 / 14 | `5fb2e2d300c5be748827dc24eb75310857faba802530ade15bd29f691628e96f` |
| `/home/hfzhkn/bitchord-djmix13-full.patch` (from `dc78e0d`) | 2,642 / 31 | `e816bfd57a0eb34c44d4f748f30380815c31cf9b78bbf5860730e7d5b691cc43` |
| `/home/hfzhkn/bitchord-expanded-patch-final-20261112T0700Z.patch` + `-0631Z` + `/home/hfzhkn/bitchord-paused-patch-20261112T0545Z.patch` | historical | — |

New djmix13 files in the tree: `shared/.../DeckBudget.kt`, `shared/.../SeekAction.kt`, `shared/.../DeckBudgetTest.kt`, `shared/.../SeekActionTest.kt` (all present in the full patch diff list).

### 11.3 Reports (primary)

| Path | Verdict |
|---|---|
| `/home/hfzhkn/bitchord-device-evidence/video-VID_20261112_151114/FINDINGS.md` | Day-one hole measured: 258.2 ms silence, ~15–20 ms fades. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-forensics/FORENSICS.md` | "(2)" = byte-identical pre-fix video; 586:15 rejection audit; BK portable traits. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-proof/PROOF-REPORT.md` | **PARTIAL**: guard proven (7× 800 ms tails, gate 0, violated 0); `planned-fade-done` never fired. |
| `/home/hfzhkn/bitchord-device-evidence/djmix13-proof/PROOF-REPORT.md` | **INTERIM** — build delivered, install pending (listener down). |
| `/home/hfzhkn/bitchord-device-evidence/djmix11-proof-20261112T0922Z/ANALYSIS.md` | djmix11 telemetry pass. |
| `/home/hfzhkn/bitchord-device-evidence/userplay-20261112/TASK3-ROOTCAUSE-TABLE.md` | Day-one root-cause table. |

### 11.4 Source / reference

| Path | Role |
|---|---|
| `bk-android:/data/workspace/bitchord-task2/work/BitChord` @ `dc78e0d` (+ staged + uncommitted deltas) | **BitChord source of truth** (box only). |
| `/home/hfzhkn/bkmasterplayer/app/rust/src/engine/{analysis,automix,mixer,deck}.rs` | BK reference only. |
| `/home/hfzhkn/Downloads/Screenshot_2026-11-12-17-18-*playermusica*` + `/home/hfzhkn/Downloads/Screenrecorder-2026-11-12-17-22-05-805.mp4` | BK YTM taste reference (DesOrden→amoureux; 2 bars, 109→106, 5A→9B). |

---

## 12. Corrections found on disk vs the commissioning brief (2026-11-12 audit)

This handover re-verified everything below on disk before writing — corrections applied:

1. **djmix13 patch filename:** the brief's worker note ("bitchord-djmij13.patch") is a **typo** — `ls /home/hfzhkn/*djmij*` = **no such file**. The actual delta file is **`/home/hfzhkn/bitchord-djmix13.patch`** (48 KB, 968 lines, 14 files). Written correctly throughout this file. SHA `5fb2e2d3…` matches the brief's worker-reported hash, so no content change — name only.
2. **File counts added (not in brief):** djmix13-full = **31 files** (adds `DeckBudget.kt`, `SeekAction.kt`, `DeckBudgetTest.kt`, `SeekActionTest.kt` vs djmix12-full's 27) — verifiable via `grep -c "^diff --git"`. djmix13 delta = 14 files; djmix12 delta = 10 files.
3. **APK sizes + full hashes:** all five APK hashes in the brief match on-disk `sha256sum` exactly, including djmix13 `2237caa1…` / **87,465,583 B** (`stat`). Earlier APKs preserved as briefed; additionally present on disk: `djmix8` (`bitchord-loopfix-1.9.1-djmix8` `4e7c4a00…`), `BitChord-loopfix.apk` (`a25900e9…` is djmix12; do not confuse with `BitChord-djmix{2,5,6}.apk` in home root — older generations).
4. **Video identity:** "(2)" = byte-identical re-verified this sprint (MD5 + `creation_time` confirmed via `ffprobe`); the **djmix11 install at 16:20:56 local** is corroborated in both the live handover and `djmix12-proof/PROOF-REPORT.md` dumpsys. Note: filename `15:11` local = `08:11Z` creation — the time zones agree; there is no second video.
5. **Install evidence:** the "installed vc54 at 18:25:52" claim is corroborated by `djmix12-proof/PROOF-REPORT.md` + `dumpsys-before.txt`/`dumpsys-after.txt` + `install.log` (port flip 40601→43659 documented). This sprint did **not** re-harvest the phone (listener down) — current on-device state is therefore stated as *last-known*, per §2.1. Nothing in the brief was found false on disk.
6. **Line counts** for the live diary (412 lines at audit) and evidence-dir layouts were confirmed by listing; `djmix13-proof/` currently holds only the interim report + adb logs — post-install pulls do not exist yet and must land there per §10.2.

---

*End of handover. Next action belongs to the user (toggle Wireless debugging ON) followed by §10 steps 2–7 in strict order.*
