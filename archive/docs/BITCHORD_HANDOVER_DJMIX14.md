# BitChord DJMIX14 Handover — 2026-11-12 (supersedes brief snapshots; live log remains the append-only diary)

## Read first / honest state in 5 lines

> **Phone has 2.2.0-djmix14 vc56 LIVE (in-place reinstall Success 2026-11-12 22:21:10, dumpsys-verified; pid 1373 steady, zero BitChord crashes). The classic fade-`done` tail (`mix.exit.tail reason=planned-fade-done`) fired TWICE on device (S.A.D→LAST GOODBYE +22717/+20090 — first ever in lifetime; vc55 had 0). Proof A (LoopMix + catch-up Classic) + B2 (CANCEL) + B3 (EMERGENCY_TAIL) all [PROVEN ON DEVICE TELEMETRY]; one natural Classic (Never Let Go→S.A.D) still advanced plainly without fire (residual hole). No agent has "heard" any fix — `adb screenrecord` is mic-only; audio proof needs a user internal-audio recording. Next step is strictly: user internal-audio recording of a transition (<50 ms hole check) + explicit listening verdict for Beautiful (Bazzi) → Never Let Go (LNGSHOT).**

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
| Debuggable | No. No PIN lock involved at forensics time (PIN reappeared during proofs — see §6). App is not debuggable; introspection is via telemetry JSONL + dumpsys + logcat, not a debugger. |
| Live diary | `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` (append milestones; do not rewrite history) |
| This file | `/home/hfzhkn/BITCHORD_HANDOVER_DJMIX14.md` (new 2026-11-12; old handovers preserved untouched) |

---

## 2. Current state + version table

### 2.1 What is on the phone vs on disk

- **INSTALLED on phone: 2.2.0-djmix14 vc56** [PROVEN at install time 2026-11-12 22:21:10, corroborated in `djmix14-proof/PROOF-REPORT.md` + dumpsys-before/after-install/after]. Installed via wireless ADB mDNS serial `192.168.100.218:41699` (re-verified per-phase). **Anomaly documented in PROOF-REPORT §1:** dumpsys-before already showed vc56 (`lastUpdateTime 2026-11-12 22:19:13`) before this subagent's first command — this subagent's `install -r` was therefore vc56→vc56 in-place (Success, new codePath, new pid 1373). No BitChord crash observed (pid 1373 steady across all proofs; crash-log 0 bitchord hits).
- **BUILT, now installed: 2.2.0-djmix14 vc56** [PROVEN built+signed+installed]. Build `:app:assembleProdRelease` (Task 3, cloud `bk-android`, log on box); patch delta + full in §4.3. No build-fix code changes beyond the djmix14 delta.
- **Classic `planned-fade-done` has now fired TWICE on device.** djmix11–13 lifetime 0; vc56 session 2 (both `handoff=true`, `bpm>0`, `phase=FADING`, `driveFade-done`). Loop handoff + B2/B3 also proven. The filmed 0s→nothing bug is fixed on telemetry for late-scrub geometry; one natural Classic still fails (§4.3).

### 2.2 Version table (all hashes + sizes re-verified on disk 2026-11-12)

| Release | versionName / vc | APK path | SHA-256 | Size | Installed? |
|---|---|---|---|---|---|
| djmix8 | 1.9.1 / vc50* | `/home/hfzhkn/Downloads/bitchord-loopfix-1.9.1-djmix8-arm64-v8a-oldkey.apk` | `4e7c4a0005a161eb5ffa00af3cd6ec92248933b968a3d85240e7850063aefd38` | 87,582,011 B | No (preserved) |
| djmix9 | 1.9.1 / vc51 | `/home/hfzhkn/Downloads/bitchord-1.9.1-djmix9-automix-arm64-v8a-oldkey.apk` | `b0af10490acefc85c2be30376e7e6aade973b9c8d0e21d2ffdc4327deb681c47` | 87,573,558 B | No (preserved) |
| djmix10 | 2.0.0 / vc52 | `/home/hfzhkn/Downloads/bitchord-2.0.0-djmix10-automix-arm64-v8a-oldkey.apk` | `82ebbbf5dec8b5194889cb8df450433ba09b1adbf8820635663a8cd875fe1889` | 87,465,587 B | No (preserved) |
| djmix11 | 2.0.1 / vc53 | `/home/hfzhkn/Downloads/bitchord-2.0.1-djmix11-automix-arm64-v8a-oldkey.apk` | `357e281d34e9f7222b2fd43196eaec0733b8db717c2c6f43aee8444074bdf029` | 87,465,587 B | Superseded by vc56 |
| djmix12 | 2.1.0 / vc54 | `/home/hfzhkn/Downloads/bitchord-2.1.0-djmix12-automix-arm64-v8a-oldkey.apk` | `a25900e9647534511b36fdb70abfc914b1fb5eaceb1010b0a7660f4848a1eb8d` | 87,465,587 B | Superseded by vc56 |
| djmix13 | 2.1.1 / vc55 | `/home/hfzhkn/Downloads/bitchord-2.1.1-djmix13-automix-arm64-v8a-oldkey.apk` | `2237caa1dc72b4afb52c2633585debd8cb5cbe8f6e75fd40a806fedd8dd69f9b` | 87,465,583 B | Superseded by vc56 (was on phone until ~22:19) |
| **djmix14** | **2.2.0 / vc56** | `/home/hfzhkn/Downloads/bitchord-2.2.0-djmix14-automix-arm64-v8a-oldkey.apk` | `dd8d49967f0826c5e9330368f4130a642a39686bb47b78216ce64828ba19f915` | **87,465,587 B** | **YES — current on phone** |

* djmix8 vc inferred (pre-brief); preserved as rollback target. All djmix14 cert re-verified oldkey, `arm64-v8a`-only, package `com.music.bitchord.loopfix`, sdk 26 / targetSdk 36. All earlier APKs preserved — never delete; each is the rollback target for its generation.

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

- The user's re-sent `VID_20261112_151114 (2).mp4` is **byte-identical** to the original: same MD5 `8057417676168876f0b49cf2da0291d5`, same 518,637 bytes, same container `creation_time=2026-11-12T08:11:17Z` (= 15:11:17 +0700). Re-verified in `/home/hfzhkn/Downloads/`.
- It was filmed **before** the djmix11 install (djmix11 vc53 installed **16:20:56 local** on 2026-11-12, ~5 h after the video). It is the same day-one event re-sent, not a post-fix regression.
- **Consequence:** djmix11/12/13/14 have **not** been proven to fail on audio. A fresh **post-fix internal-audio** recording (user taps Side-key / screen-record with internal audio) of a transition is still missing and is part of Definition of Done (§9). Do not cite the "(2)" file as evidence about djmix11+.

### 3.3 The 19:25:50 video (vc55, forensics) [PROVEN measurement]

- `Screenrecorder-2026-11-12-19-25-50-750.mp4` (5.07 s, `056fa970…`, 720×1280 HEVC + AAC-LC mono 44.1 kHz internal capture): Beautiful `2:48/−0:11 → 2:53/−0:06` (2:59 total), `Beat blend · 2s→1s→0s · 100→104 · locked` then **stuck `0s` ~3.4 s to video end**, no finger/scrub-bar in clip, audio envelope shows single-track dynamics only (loud 0.15–0.95, decay 1.0–2.6, swell 2.65–3.2 `RMS 0.246`, decay 3.25–5.05; **zero overlap**, Track B never started).
- Telemetry video window (19:25:50.000–57.000): only `mix.complete seq60` + `mix.plan seq61 classic DJ_BLEND 22.17` — **zero `mix.fire`**. Leading 10 min: 112 events, 17 seeks (all `cancel`), 0 `planned-fade-done` lifetime. Full forensics: `/home/hfzhkn/bitchord-device-evidence/djmix14-forensics/FORENSICS-DJMIX14.md`.

### 3.4 Evidence dirs (all preserved, originals untouched)

| Path | What it holds |
|---|---|
| `/home/hfzhkn/bitchord-device-evidence/video-VID_20261112_151114/` | `FINDINGS.md` + `TASK1-VIDEO-REPORT.md` + `audio.wav`, waveforms, spectrogram, frames. Day-one measurement. |
| `/home/hfzhkn/bitchord-device-evidence/userplay-20261112/` | User-play 15:21: dumpsys, logcat, media_session, `TASK3-ROOTCAUSE-TABLE.md`, telemetry pulls. |
| `/home/hfzhkn/bitchord-device-evidence/djmix11-proof-20261112T0922Z/` | `ANALYSIS.md` + 5 pulls + passive samples + screen/ui dumps. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-forensics/` | `FORENSICS.md` + `newbug.mp4`/`bkrecord.mp4`, RMS npz, envelope CSVs/SVGs, frames. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-proof/` | `PROOF-REPORT.md` (PARTIAL) + `telemetry-pull1..6`, dumpsys, logs. |
| `/home/hfzhkn/bitchord-device-evidence/djmix13-proof/` | `PROOF-REPORT.md` (interim: adb logs only; vc55 installed later outside this dir). |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-forensics/` | `FORENSICS-DJMIX14.md` (Bug Classes 1–4 + video correlation) + telemetry 3750 (`298fe0d1…`), `dumpsys-package.txt` (vc55), `media_session.txt`, `crash-pre.txt` (0 BitChord), video frames/wav. |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-proof/` | `PROOF-REPORT.md` (this sprint verdict) + `telemetry-pull1..7` (3762→3980), `dumpsys-before/after-install/after`, `install.log`, `pid-*.txt`, `media_session-*.txt`, `ui-*.xml`, `crash-log.txt`, `exit-info.txt`. |

### 3.5 Inspiration / BK reference (verified, not folklore)

- Reference implementation: local **BK MasterPlayer** at `/home/hfzhkn/bkmasterplayer`, engines `app/rust/src/engine/{analysis,automix,mixer,deck}.rs`. Verified present in djmix13 sprint.
- BK YTM screenshots + screen recording in `/home/hfzhkn/Downloads/`: `Screenshot_2026-11-12-17-18-*io.github.playermusica.player_musica.ytm*` (DesOrden → amoureux pair) and `Screenrecorder-2026-11-12-17-22-05-805.mp4` ("Mixing: 2 bars, 109→106 BPM (−2.8%), 5A→9B clash/filter"). Confirmed on disk in djmix13 sprint.
- **Portable traits** (verified file:line in `djmix12-forensics/FORENSICS.md`): **±8 % stretch cap**, **16-bar tempo return**, **downbeat snap ±25 ms**, **mid-band 200–2000 Hz P75 outro**, **bass swap bars/2**, **echo fb 0.55 past EOF**, **soft-clip**.
- **Constraint:** BK states the same patent-scalars rule BitChord follows (§7): only scalars (BPM/Camelot/LUFS/vocal-count) cross decks — never raw audio features. BK is reference-only; it is not BitChord source.

---

## 4. What each release did (and what it proved)

### 4.1 djmix11 (vc53, installed 16:20:56) — structural exit-tail [PROVEN built/installed; UNPROVEN on audio]

- **Code:** `withExitTail` (2 beats, floor 800 ms cap 2 s, emergency 350 ms, mute knot); native OUT `stopAt` extended + flanger wet 0.42 + exit gate; classic `Phase.TAIL` (`beginTailOrFinish`/`driveTail`); telemetry `mix.plan.exitTailSec` / `mix.exit.tail` / `mix.exit.gate`.
- **Device:** exactly **1 native M6** transition, gate `violations=0` (structural only, **no audio capture**). Classic TAIL **never fired** — classic plans died before FADING. The re-sent "(2)" video does not refute or confirm djmix11 (§3.2).

### 4.2 djmix12 (vc54, installed 18:25:52) — retire-guard + tempo discipline [PROVEN on device, PARTIAL]

- **Patch:** delta `/home/hfzhkn/bitchord-djmix12.patch` (567 lines, 10 files, `32f825ade29cd692fe9abe57704136647bb754d72e56185f4b27fa1d63a1f8a0`); cumulative `/home/hfzhkn/bitchord-djmix12-full.patch` (2,263 lines, **27 files**, vs `dc78e0d`, `eef252364b4b493df31e05cf40b2760951fd9686e300d24a6904cd96ea44023b`).
- **Code:** classic **retire-guard** — `finish()` may only retire a silent deck; audible retire diverted to `Phase.TAIL` 350 ms emergency dissolve (`RetireGuard.kt`, `mix.exit.guard` / `mix.exit.violated` / `mix.complete`); `mix.plan` emits for **classic** too; `exitTailSec` sharpened to **16 equal-power knots**; deduped fallback/reject telemetry; `PREP_TOLERANCE_MS=50`; `alignedFold()` (kills 74/105→148 divergent folds and LoopMix **+42.5 % lurch**); RELAXED **vetoed above 8 % → echo/cut**; `rankMixOutCandidates` scalar vocal adjustment; `bail()` freeze covers ARMING. Tests **1801/0**.
- **Device proof — PARTIAL [PROVEN]:** classic `mix.plan` ×7 (all `exitTailSec 0.8`) + M6 ×1; `mix.exit.guard` ×7; **`mix.exit.tail` 7× @800 ms planned** (emergency-retire-guard inside `finish()`); `mix.complete` ×7; `mix.exit.gate` ×1 `violations=0`; **`mix.exit.violated` absent (0)**. Verdict: `planned-fade-done` **NEVER** fired (classic died 6/8 in ARMING: 3 watchdog + 3 splice). Residual `phrase-null` spam continues.

### 4.3 djmix13 (vc55, installed 19:17:24) — deadlines + scrub + firing instrumentation [PROVEN built, partially proven by forensics]

- **Patches:** delta `/home/hfzhkn/bitchord-djmix13.patch` (**968 lines, 14 files**, `5fb2e2d300c5be748827dc24eb75310857faba802530ade15bd29f691628e96f`); cumulative `/home/hfzhkn/bitchord-djmix13-full.patch` (**2,642 lines, 31 files**, vs `dc78e0d`, `e816bfd57a0eb34c44d4f748f30380815c31cf9b78bbf5860730e7d5b691cc43`).
- **Code:** shared `DeckBudget` (3 sites pause-proof), `lateStepReason` proven-only, watchdog suppress-loop 90 s + `mix.arm.death`, `SeekAction` scrub protocol (`CANCEL/HONOUR/EMERGENCY_TAIL` + `mix.seek`), `mix.fire {deltaMs,cueErrorMs}`. Tests **1825/0**.
- **Device (forensics harvest, 3750 lines):** `mix.fire` **1** (`+7161 / 4995` — 7.1 s late, 5.0 s cue error); `mix.exit.tail` 22 (all `emergency-retire-guard`); `mix.complete` 26 (all `handoff=false`, `finish`); `mix.seek` 17 (all `cancel`); rejected 3167 (`phrase-null 1849`, `classic-fallback 1301`); fallback 326 (`stream 17 over` 145 + `splice 49012 over` 101 dominant). The `cueErrorMs` bug (`seconds-minus-ms` → `5000−5=4995`) was present in vc55 — fixed in vc56 (now `0`). **Zero device `planned-fade-done`** — the state this handover departs from.

### 4.4 djmix14 (vc56, installed 22:21:10) — catch-up + region cache + scrub reset **[PROVEN on device telemetry, PARTIAL on audio]**

- **Patches:** delta `/home/hfzhkn/bitchord-djmix14.patch` (**2,108 lines, 19 files**, `dbdbbb0ae32d108bbe25a47262d26b6efeacd99acab53a07453d92672456673d`); cumulative `/home/hfzhkn/bitchord-djmix14-full.patch` (**3,986 lines, 35 files**, vs `dc78e0d`, `f645c223f8313ee8744576aded22b0d4631762c205799a96905d5c33b5cd6f96`). New files vs djmix13-full: `CatchUpPlanner.kt`, `LoopRegionCache.kt`, `Djmix14PlannerRescueTest.kt`, `Djmix14ScrubAndCatchUpTest.kt`, `LoopRegionCacheTest.kt`, `RegionalLoudnessTest.kt`, `AutomixGracefulTest.kt` (counts via `grep -c "^diff --git"`).
- **Root causes (from `FORENSICS-DJMIX14.md`, [PROVEN] code facts):**
  1. **Countdown 0s→nothing** (Bug 1): UI clamp vs execution lead decoupled; sticky-loop past-start deadlock (`Hold.KEEP` ignores `seekPast`); watchdog blindness when `seconds=0` after scrub.
  2. **Scrub flaws** (Bug 2): `CANCEL` did not clear `loopPlanned` → stale loop resurrected; no catch-up at EOF (12 s loop lead + 20 s classic lead both exceed remaining after a T−11 s scrub).
  3. **Rejection/decode races** (Bug 3): head-vs-full race (outgoing head-only fails `ANALYSED`); `PhrasePlanner` 40 ms timeout + `coarse` grid → `phrase-null` 1849; `LoopMixSession` network re-decode routinely >12 s → fallback 326.
  4. **Taste/DSP** (Bug 4): no limiter staging / vocal EQ / transient / reverb-roll (reference §3.5).
- **Fixes shipped in vc56:**
  - **`CatchUpPlanner`** (new) — late-scrub re-anchor: when playhead already past `transitionStart`, re-anchor to `currentPosition + lead`, truncate fade to remaining overlap (2–4 bar), pin plan (`pinnedCatchUpKey/Plan` + `scrubEpoch`) so countdown does not drift; `:catch-up` reason path (schema: identified on device by late-fire signature, not a literal `reason` field).
  - **`LoopRegionCache`** (new) — 4-entry in-RAM PCM (~24 MB), sub-slice covering entries, no network re-decode; loop decks decode from RAM.
  - **Full scrub reset** (`resetPlansForScrub`, `scrubEpoch`) — every plan/lock/countdown dropped on `CANCEL`, so next tick replans from new playhead; pre-takeover scrub is silent teardown, instant replan (no 350 ms dip).
  - **Fast budget gate** — `<4.5 s` to loop start = cold loop impossible → fall back to classic immediately (avoids 13 s build silence).
  - **Prime lead** 12–20 s → **40 s** (`LOOP_PRIME_LEAD_MS`), matching brief's natural-prime window.
  - **`cueErrorMs` fix** — was `seconds−ms` (`5000−5=4995`), now `0` on all vc56 classic fires.
  - **Completion telemetry** — capture real `handoff/bpmA/bpmB/phase/reason` (`planned-fade-done`, `loop-complete`, `seek-emergency-tail`) before clearing (`lastCompleteReason`); classic `driveFade-done` now carries `bpm>0, phase=FADING` (emergency `finish` still logs `bpm 0` — path-specific, see §4.4).
  - **Sticky clear** — past-start sticky loop cleared so UI never reads a dead plan; loop vs classic divergence closed.
  - **Phrase dedup/memo** — one `phrase-null` line per `(pairKey, detail)` (was ~1/s, 1849 lifetime); `PhrasePlanner.select` memoized.
  - **`PhraseConfig`**: `STRUCT_TIMEOUT 2000→5000 ms`; rescue/strict search must not pick past; coarse+extreme-tempo veto; musical fallback rung.
  - **`TrackAnalyzer`**: single-thread → 2-thread bounded FIFO (playing-track priority); `loopRegionCached` fast-ready check (pinned to 4 sites).
  - **`RegionalLoudness`**: symmetric ±3 dB → peak-safe **−6.0..+4.5 dB**.
  - **Taste**: club echo-out dotted-eighth (0.75 beat, 0.5 above 120 BPM), vocal-pocket dual-vocal carve; native pre-alloc no-audio-thread alloc.
  - **5 review hardening fixes**: scrub-epoch + pinned catch-up anti-drift; single fast-ready check; smooth-finish (`planned-fade-done` already at EOF) guard; rescue past-guard; `loopStart` decode-lead ahead check; manual scrub authoritative (never overwritten by replan); countdown lock on `seconds>=0` (not `>0`).
- **Device proof — [PROVEN ON DEVICE TELEMETRY] (see `djmix14-proof/PROOF-REPORT.md` for seq/ts):**
  - **A1 Loop:** Beautiful→Never `fire loop −755` → `gate 0` → `handoff=true` (reason `emergency-retire-guard`, `bpm 0`, not `loop-complete` — Loop still emergency-path).
  - **A1 Classic caveat:** Never Let Go→S.A.D **plan-only plain advance without fire** (6 classic plans, 0 fire/gate/tail/complete) — residual natural-classic hole, pair-dependent.
  - **A2/B1 catch-up ×2:** S.A.D→LAST GOODBYE `fire classic +22717 cue 0` → `tail planned-fade-done driveFade-done` → `complete handoff=true bpm 66.48/120.64 phase FADING`; LAST GOODBYE→Good Girls `+20090 cue 0` → same completion. **First `planned-fade-done` in lifetime.**
  - **Accurate fire:** Good Girls→intentions `+103 cue 0` (69× better than vc55 `+7161`).
  - **B2 CANCEL:** 21× `cancel + scrub-cancelled`, single fresh plan, zero ghost fire, playhead preserved.
  - **B3 EMERGENCY_TAIL:** `FADING 14720→9720 emergency_tail` → `tail seek-emergency-tail` → `handoff=true`, `violated 0` file-wide.
  - **Hygiene (vc56-only 230 lines vs vc55 3750):** plan→fire 2.2%→7.0%, watchdog 7→0, `phrase-null` 1849→4 (biased window), `gate` all 0, `violated` 0. **Splice `over` still huge** (`374320/388005 ms`) but no longer blocks classic — classic fires after loop fallback.
- **Honest status:** code + unit tests + build + in-place install + pid/crash are proven; **scrub behavior, `planned-fade-done`, firing deltas, and handoff telemetry are proven on this session**; **acoustic smoothness (`<50 ms` hole), tempo/key/vocal alignment after +20 s-late catch-ups, and DJ taste are entirely [UNPROVEN]** until §9 internal-audio + ears.

---

## 5. Source / build

| Item | Value |
|---|---|
| Build box | `bk-android` (cloud). **No local BitChord source tree** — `bkmasterplayer` is reference only. Worktree `/data/workspace/bitchord-task2/work/BitChord` @ `dc78e0d` + staged djmix10/11 + uncommitted djmix12–14 deltas. **CHECK `git status` first next session** before touching or re-patching. |
| Other boxes | `tanyadf-standby` absent/suspended (djmix13). Do not route builds there without re-verifying. |
| Build mode | Cloud builds only, **background job + `/tmp/release-build*.log`** on the box; do not sleep-poll in loops. |
| Binary transfer | `instabox` `exec`/`files` truncate binaries — retrieve APKs via **`instabox ssh ... cat`** (streams full binary). This is how djmix14 APK reached `/Downloads/`. |
| Secrets | **Never write API keys/passwords to files.** Keystore (`keystore.properties` + `bitchord-loopfix.jks`) used **as-is** — no new key, ever. |
| Release cadence | **Same oldkey + versionCode bump every release.** Current: vc56. Treat any "re-key" suggestion as a hard stop. |

---

## 6. Device / ADB

- **Transport:** wireless mDNS `adb-1439ebea-05jQT0._adb-tls-connect._tcp`. This sprint used explicit serial **`192.168.100.218:41699`** (re-resolved via `adb mdns services` + `adb devices -l` before every phase; mDNS aliases include a `(2)` space — always pass `-s 192.168.100.218:<port>`). PC on **192.168.100.11/24**, same SSID required.
- **The listener keeps turning OFF** (power cycle / idle / dev-options reset). **Unblock:** user unlocks phone → same Wi-Fi → Developer options → **Wireless debugging ON** (off/on toggle if stale) → says "adb up". Then `adb mdns` / `adb connect` and harvest. Do not hammer connects in a loop.
- **PIN lock (new this sprint):** After `22:21` the phone locked to `Introducir PIN o usar huella digital para desbloquear` (`deviceLocked=1`, `UNTRUSTED`) — `wm dismiss-keyguard` shows PIN, `ui-*.xml` = SystemUI only. UI taps were impossible; proofs used `cmd media_session dispatch fast-forward (+15 s)` / `rewind (−5 s)` (verified true `DISCONTINUITY_REASON_SEEK` via `mix.seek`). Next sprint: ask user to keep phone unlocked (screen timeout / stay-awake) or use tap seeks if PIn cleared.
- **App constraints:** not debuggable, no PIN in app. `adb screenrecord` captures **mic only, NOT internal audio** — never claim to have heard a transition from it. Audio proof requires **internal-audio screen recording** (§9).

---

## 7. Telemetry (the only truth — release strips `android.util.Log`)

- **Path:** `/sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl`
- **Discipline:** **pull BEFORE install, preserve pull order** (pull-1 = pre-reinstall harvest; post-install pulls are 2, 3, …). Hash + count every pull; never overwrite a pull. Pulls live in the djmix-proof dirs. This sprint discovered pull1 was already post-vc56 (see PROOF-REPORT §1) — order preserved, anomaly documented, not renamed.
- **Key events to grep (in priority order):**
  `mix.plan` (incl. `exitTailSec`), `mix.exit.tail` (grouped `planned-fade-done` vs `emergency-retire-guard` vs `seek-emergency-tail`), `mix.exit.gate`, `mix.exit.guard`, **`mix.exit.violated` (must be ABSENT — any hit is a kill-claim failure)**, `mix.complete` (`handoff`, `bpmA/B`, `phase`, `reason`), **`mix.arm.death {reason,phase,msSincePlan}`**, **`mix.fire {mode,plannedStartMs,actualStartMs,deltaMs,cueErrorMs}`**, `mix.seek` (`cancel` vs `emergency_tail`), `mix.loop.fallback`, `mix.plan.rejected` (`phrase-null`).
- **Phase gates for proof:** classic success = `mix.exit.tail reason=planned-fade-done` + `mix.complete handoff=true bpm>0 phase≠IDLE`, gate `violations=0`, `mix.exit.violated` absent, `mix.fire deltaMs/cueErrorMs` reported (target ±25 ms cue accuracy for *natural* fires; catch-up `deltaMs` is lateness by definition). B2 = `CANCEL + scrub-cancelled` + zero ghost `mix.fire` + single fresh plan. B3 = `EMERGENCY_TAIL` + `seek-emergency-tail` tail + `violated 0` + playhead near target.

---

## 8. Hard rules (do not violate)

1. **Loop crown:** the shown countdown always plays; **djmix6 eligibility / bar counts untouched** (djmix14 changes no loop-window sizes except prime lead).
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
| 1 | **Scrubbing messes up automix** | Addressed in vc56 code (`resetPlansForScrub` + `CatchUpPlanner` + `SeekAction`) — **[PROVEN on telemetry]** (B2 `CANCEL` + B3 `EMERGENCY_TAIL` + catch-up `planned-fade-done` ×2). Taste of scrubbed blends still `[UNPROVEN]`. |
| 2 | **Tracks not firing properly, abrupt / not smooth** | Firing-delta + catch-up instrumentation proves classic now **fires** (was 0 `planned-fade-done` → 2) — **[PROVEN on telemetry]**; firing **accuracy** is pair-dependent (`+103` best, `+22 s` catch-ups late) and smoothness **[UNPROVEN on audio]**. |
| 3 | **Automix taste: "not impressed"** — wasted chances, tempo/key/vocal clashes, loading aborts | Open design-level complaint. djmix14 improved region cache / 2-thread analysis / Loudness −6/+4.5 / echo-out / vocal-pocket, but did not close taste. **Never Let Go→S.A.D plain advance** shows a wasted chance still exists. Expect iteration after proof. |

**Definition of Done (all required):** a classic fade completes on device (`mix.exit.tail reason=planned-fade-done` + `mix.complete handoff=true`) **AND** no silence hole **≥50 ms** on a captured **internal-audio** waveform **AND** the **user ears confirm** (explicit listening verdict, e.g. Beautiful (Bazzi) → Never Let Go (LNGSHOT) pair). Leg 1 is **now [PROVEN]** (×2). Legs 2–3 are **still open**. No single leg alone closes the bug.

---

## 10. Next steps (in order — do not skip)

1. **User toggles Wireless debugging ON** (Dev options; same Wi-Fi) and keeps the phone **unlocked** (or says "adb up" + "screen stays on") and says "adb up". If `adb devices` is empty, re-resolve via `adb mdns` (`adb-1439ebea-05jQT0._adb-tls-connect._tcp`) — ports flip; do not loop connects. PC = 192.168.100.11/24.
2. **Harvest (pull-first):** dumpsys — expect **vc56 2.2.0-djmix14** (+ lastUpdate 2026-11-12 22:21:10 if unchanged); telemetry pull-1 into `/home/hfzhkn/bitchord-device-evidence/djmix15-proof/` (new dir for next cycle); media_session; `adb devices`/`mdns` logs. Preserve pull order; hash + line-count the pull.
3. **Install:** if a new vc57 APK is built, `adb install -r /home/hfzhkn/Downloads/<new>.apk` → dumpsys must show new vc → launch → pid steady + crash check (BitChord must NOT appear; Huawei HMS ClassNotFound is known noise). If no new build, reinstall vc56 in-place is unnecessary — use the live vc56 session.
4. **Proof A (classic completes + Loop crown):** seek near outro on the current track; require at least one classic `mix.plan` → `mix.fire` → **`mix.exit.tail reason=planned-fade-done`** + `mix.complete handoff=true`, gate `violations=0`, `mix.exit.violated` **absent**, `mix.fire deltaMs` reported. Also require a Loop `mix.fire mode="loop"` + `gate 0` + `handoff=true` (accept `emergency-retire-guard` today; track `loop-complete` as a future telemetry ideal). Pull telemetry after; do not claim "heard".
5. **Proof B (scrub scenarios):** arm a mix (seek near outro), then scrub away/back before it fires; require `mix.seek action=cancel`, **no ghost fire at the stale position**, **exactly one live plan** afterwards. Also exercise an audible-state scrub once (expect `emergency_tail` + `seek-emergency-tail` tail, never an instant cut, `violated 0`). For tap-precise positions, prefer UI seeks (seekbar `[85,1713][1135,1849]` in this layout); `cmd dispatch FF (+15 s)/RW (−5 s)` is acceptable fallback when PIN-locked, with positions reported as-measured.
6. **If classic still plain-advances for some natural pairs:** read `mix.arm.death` + `mix.loop.fallback` + `mix.watchdog` dominant `reason/phase/msSincePlan` for the failed pair (e.g. Never→S.A.D in vc56) and fix as **djmix15** (same oldkey, vc bump). Do not claim fixed — a named emergency tail is progress, not Proof A.
7. **Ask the user for an internal-audio screen recording** of a transition + explicit listening verdict for **Beautiful (Bazzi) → Never Let Go (LNGSHOT)** (and, if possible, **S.A.D → LAST GOODBYE** catch-up pair). Only then measure the ≥50 ms-hole criterion and close the Definition of Done (§9).

---

## 11. File index

### 11.1 Handovers (never overwrite old ones)

| Path | Scope |
|---|---|
| `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` | Append-only live diary (milestones through djmix14 DEVICE PROOF). |
| `/home/hfzhkn/BITCHORD_HANDOVER_FOR_NEXT_AI.md` | Prior consolidated handover (preserved). |
| `/home/hfzhkn/BITCHORD_HANDOVER_DJMIX13.md` | djmix13 handover (preserved; vc55 built-not-installed at write time — now superseded). |
| `/home/hfzhkn/BITCHORD_HANDOVER_DJMIX14.md` | **This file.** |

### 11.2 Patches (all re-verified 2026-11-12)

| Path | Lines / files | SHA-256 |
|---|---|---|
| `/home/hfzhkn/bitchord-djmix12.patch` (delta over staged djmix11) | 567 / 10 | `32f825ade29cd692fe9abe57704136647bb754d72e56185f4b27fa1d63a1f8a0` |
| `/home/hfzhkn/bitchord-djmix12-full.patch` (from `dc78e0d`) | 2,263 / 27 | `eef252364b4b493df31e05cf40b2760951fd9686e300d24a6904cd96ea44023b` |
| `/home/hfzhkn/bitchord-djmix13.patch` (delta over djmix12 worktree) | 968 / 14 | `5fb2e2d300c5be748827dc24eb75310857faba802530ade15bd29f691628e96f` |
| `/home/hfzhkn/bitchord-djmix13-full.patch` (from `dc78e0d`) | 2,642 / 31 | `e816bfd57a0eb34c44d4f748f30380815c31cf9b78bbf5860730e7d5b691cc43` |
| `/home/hfzhkn/bitchord-djmix14.patch` (delta over djmix13 worktree) | **2,108 / 19** | `dbdbbb0ae32d108bbe25a47262d26b6efeacd99acab53a07453d92672456673d` |
| `/home/hfzhkn/bitchord-djmix14-full.patch` (from `dc78e0d`) | **3,986 / 35** | `f645c223f8313ee8744576aded22b0d4631762c205799a96905d5c33b5cd6f96` |
| `/home/hfzhkn/bitchord-djmix10.patch` | — (19 KB) | — |
| `/home/hfzhkn/bitchord-djmix11-patch-20261112T1620Z.patch` | — (88 KB) | — |
| `/home/hfzhkn/bitchord-expanded-patch-final-20261112T0700Z.patch` + `-0631Z` + `/home/hfzhkn/bitchord-paused-patch-20261112T0545Z.patch` | historical | — |

New djmix14 files in the tree: `shared/.../CatchUpPlanner.kt`, `shared/.../LoopRegionCache.kt`, `shared/.../Djmix14PlannerRescueTest.kt`, `shared/.../Djmix14ScrubAndCatchUpTest.kt`, `shared/.../LoopRegionCacheTest.kt`, `shared/.../RegionalLoudnessTest.kt` (all present in the full patch diff list; counts `grep -c "^diff --git"`).

### 11.3 Reports (primary)

| Path | Verdict |
|---|---|
| `/home/hfzhkn/bitchord-device-evidence/video-VID_20261112_151114/FINDINGS.md` | Day-one hole measured: 258.2 ms silence, ~15–20 ms fades. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-forensics/FORENSICS.md` | "(2)" = byte-identical pre-fix video; 586:15 rejection audit; BK portable traits. |
| `/home/hfzhkn/bitchord-device-evidence/djmix12-proof/PROOF-REPORT.md` | **PARTIAL**: guard proven (7× 800 ms tails, gate 0, violated 0); `planned-fade-done` never fired. |
| `/home/hfzhkn/bitchord-device-evidence/djmix13-proof/PROOF-REPORT.md` | **INTERIM** — build delivered, install pending (listener down). |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-forensics/FORENSICS-DJMIX14.md` | **Bug Classes 1–4 + video correlation** (3750-line vc55 baseline; plan→fire 2.2%, 0 `planned-fade-done`). |
| `/home/hfzhkn/bitchord-device-evidence/djmix14-proof/PROOF-REPORT.md` | **CLASSIC `planned-fade-done` ×2 + Loop + B2 + B3 [PROVEN ON DEVICE TELEMETRY]** (pulls 3762→3980, pid 1373 steady). |
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

1. **Pre-state was vc56, not vc55.** The brief said "expected `vc55 / 2.1.1-djmix13`." `dumpsys-before.txt` shows `versionCode=56 / 2.2.0-djmix14 / lastUpdateTime 22:19:13` and pull1 already contains 12 vc56 lines (`seq6 fire loop -426`). The install in this sprint was vc56→vc56 in-place. Documented in PROOF-REPORT §1 and LIVE milestone; no history rewritten.
2. **File counts + hashes re-verified.** djmix14 delta = **19 files / 2,108 lines** (`dbdbbb0a…`), full = **35 files / 3,986 lines** (`f645c223…`) via `grep -c "^diff --git"` + `sha256sum` + `wc -l`. The brief's "5 review hardening fixes" are visible as `djmix14-review:` comments in the delta (scrub epoch/pin, fast-ready, smooth-finish, rescue past-guard, decode-lead).
3. **APK hashes + sizes.** All seven hashes in §2.2 match on-disk `sha256sum` exactly, including djmix14 `dd8d4996…` / **87,465,587 B** (`stat`). Earlier APKs preserved; `djmix8` (`4e7c4a00…`, 87,582,011 B) additionally present and listed (vc50 inferred).
4. **PIN lock + dispatch substitution.** Brief's seeking tip assumes tap seeks. Phone PIN-locked after `22:21` (`deviceLocked=1`), so proofs used `cmd media_session dispatch FF (+15 s)/RW (−5 s)` (verified true `DISCONTINUITY_REASON_SEEK` via `mix.seek`). B2 landed `66.4 s` (spec `40–60 s`), B3 `9.7 s` (spec `45 s`) — mechanics proven, exact targets missed by granularity. Reported as-measured in PROOF-REPORT §§5–6.
5. **Catch-up `reason` field.** Brief says `mix.plan reason ending in :catch-up`. vc56 `mix.plan`/`mix.fire` schema carries no `reason` string for classic plans — catch-up is identifiable by late-fire signature (`planned 178,046 vs actual 200,763` + `cueErrorMs 0` + `driveFade-done`). PROOF-REPORT §4 notes this schema delta instead of inventing a field.
6. **Line counts.** LIVE was **413 lines** (`a5d0f31b…`) at audit, now **434 lines** after append-only milestones (no truncation). Evidence pulls 3762→3980, all hashes in PROOF-REPORT §9.

---

*End of handover. Next action belongs to the user (internal-audio recording + listening verdict) followed by §10 steps 2–7 in strict order. Phone left PAUSED (intentions idx5) on vc56, queue unmodified.*
