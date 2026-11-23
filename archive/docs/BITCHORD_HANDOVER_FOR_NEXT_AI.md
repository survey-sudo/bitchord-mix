# BitChord AutoMix — Complete Handover for Next AI

## 1. One-paragraph issue

BitChord is a private Android fork (`com.music.bitchord.loopfix`) created mainly because of one day-one bug that still regresses: **AutoMix sometimes blends well, then the outgoing song is cut off like pause — no fade/flanger/echo dissolve — so the mix collapses.** The user’s video `VID_20261112_151114.mp4` proves it: **~15ms fade → 258ms digital silence → fade-in**, mid-song, on rung “Smooth fade · 3s · 106→109 · locked”. A later build (`2.0.1-djmix11`) implements a graceful-exit tail and has **structural proof on the native path**, but the **classic path filmed in the video is still untested on device**, and there is still no after-audio A/B. Do not claim “fixed” until a classic transition fires `mix.exit.tail` on device and the envelope/telemetry shows a ≥300ms dissolve with no silence hole.

User also reported, beyond the cut: **wasted chances** (good windows missed), **unnatural/abnormal mixes** (tempo lurch, vocal/key/energy clashes), and **loading aborts mixing**. Fix those too, not only the named cut.

## 2. User expectations / behavior rules (read this first)

- User is dissatisfied with hotfix drips. Work toward **one major, measured release**, not tiny patches.
- “If I name a bug, don’t fix only that” — audit beyond the named bug using handover/references below.
- Nothing is “fixed” by code or unit tests alone. Measure with telemetry/DSP and be explicit when unheard.
- Do **not** use counterproductive cloud polling (`sleep 400; instabox exec ...`, build-log loops). Checkpoint locally after every milestone.
- Local PC is poor for Android builds. Heavy builds go on instabox cloud boxes only. Short local ADB work is fine.
- Preserve originals. Never generate a new signing key. New APKs use the **old key** for in-place updates.
- If blocked, say exactly what blocked, what was tried, and the exact manual step needed from user. Do not fake hearing, captures, or “verified”.

## 3. Identity, references, test material

- App: BitChord, package `com.music.bitchord.loopfix`, arm64-v8a only.
- Current installed on phone: **2.0.1-djmix11, vc53**.
- Reference ideal DJ case: **“Beautiful” by Bazzi → “Never Let Go” by LNGSHOT**; ideal is an **8-bar blend on Beautiful’s relaxed/empty outro break** into Never Let Go’s intro, beat/tempo matched, graceful. Tracks are **streamed Opus** on device, not local files; queue during proof was “UN DIA”, so the preferred pair was not available then.
- References to re-read before coding:
  - `/home/hfzhkn/Downloads/HANDOVER_MASTER.pdf`
  - `/home/hfzhkn/Downloads/automix-research.md`
  - `/home/hfzhkn/Downloads/Apple Music AutoMix Technical Wiki.md`
  - `/home/hfzhkn/Downloads/BITCHORD_SESSION_EXPORT.zip`
  - `/home/hfzhkn/Downloads/bitchord-cursor-convos.zip`
  - Google Drive handover folder: `https://drive.google.com/drive/folders/1-9CHqWj9TPxHc60TPviVdwEsg0wr9W6E?usp=drive_link`
  - bkmasterplayer reference: `/home/hfzhkn/bkmasterplayer` (`app/rust/src/engine/{analysis,automix,mixer,deck}.rs`)

## 4. Delivered APKs (local, verified hashes)

- djmix9: `/home/hfzhkn/Downloads/bitchord-1.9.1-djmix9-automix-arm64-v8a-oldkey.apk`
  - vc51, SHA256 `b0af10490acefc85c2be30376e7e6aade973b9c8d0e21d2ffdc4327deb681c47`
- djmix10 major: `/home/hfzhkn/Downloads/bitchord-2.0.0-djmix10-automix-arm64-v8a-oldkey.apk`
  - vc52, SHA256 `82ebbbf5dec8b5194889cb8df450433ba09b1adbf8820635663a8cd875fe1889`
  - **Important:** the user’s video was filmed **after manually installing djmix10**, so djmix10’s changes were live when the cut happened.
- djmix11: `/home/hfzhkn/Downloads/bitchord-2.0.1-djmix11-automix-arm64-v8a-oldkey.apk`
  - vc53, SHA256 `357e281d34e9f7222b2fd43196eaec0733b8db717c2c6f43aee8444074bdf029`
  - oldkey cert SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`
  - arm64-v8a only, in-place update compatible.

## 5. Machines / environment

### bk-android (old cloud box) — CLOUD-BLOCKED
- Previously used as `instabox exec bk-android -- "<cmd>"` / `instabox ssh bk-android`.
- Free-tier cooldown: can sleep ~2 hours. Do not wait/poll.
- Source tree there: `/data/workspace/bitchord-task2/work/BitChord`, base `dc78e0d` + local diffs.
- If it wakes: pull/copy source, confirm `git log --oneline -3` + `git status`, verify patch state before editing.

### tanyadf-standby (new alternative box) — SET UP, PREFERRED
- Sandbox: `tanyadf-standby`, id `m_agq2kr74g6g6wssjzizwcsakyy` (4 vCPU / 8 GB / 20 GB disk).
- User provided an `ibk_...` API key in chat. **Do not hardcode it in files.** Every command needs:
  - `export INSTABOX_API_KEY='<key from user>'`
  - `instabox exec tanyadf-standby -- "<cmd>"`
  - Upload stdin with `--stdin`: `cat file | instabox exec tanyadf-standby --stdin -- "cat > /data/workspace/bitchord-alt/file"`
- `instabox exec` default timeout is 60s, max 600s. Long Gradle builds need `--background` + job logs, never `sleep` loops.
- Installed on it: JDK 21.0.12.1, `ANDROID_HOME=/data/android-sdk`, platform-tools 37.0.1, `platforms;android-36`, `build-tools;36.0.0`, NDK `28.2.13676358`, CMake 3.22.1, Gradle 8.7 (`/data/gradle-8.7`), `adb`/`aapt2`/`apksigner` verified.
- Workspace: `/data/workspace/bitchord-alt` (`evidence/`, `apks/`), containing the live handover + all local `bitchord-*patch*.patch`.
- **Still needed before building there:** copy/fetch the full BitChord source tree onto `tanyadf-standby` (patches alone are not a tree).

## 6. Device / ADB

- Phone: POCO peridot `24069PC21G`, Android 16 / SDK 36, arm64-v8a.
- Wireless ADB last seen: `192.168.100.218:40601` (port can change). Phone + PC must share Wi-Fi.
- Pairing flow (Android 16): Settings → Additional settings → Developer options → Wireless debugging → “Pair device with pairing code” → `adb pair <ip>:<pairPort> <code>` → `adb connect <ip>:<connectPort>` (pair port ≠ connect port).
- Verify: `adb devices -l`, `adb shell dumpsys package com.music.bitchord.loopfix | grep -E 'versionName|versionCode'`.
- App is not debuggable (`run-as` denied). Telemetry is pulled from the app’s telemetry JSONL; preserve pull order (`pull1...pullN`) and analyze existing pulls **before** driving new playback.
- No PIN available to agent. `adb screenrecord` captures mic, not internal audio. For audio A/B, use the user’s internal-audio screen recording (like `VID_20261112_151114.mp4`) or state clearly that only telemetry/envelope proof exists.

## 7. What the video proves (baseline failure)

Video: `/home/hfzhkn/Downloads/VID_20261112_151114.mp4`, evidence in `/home/hfzhkn/bitchord-device-evidence/video-VID_20261112_151114/` (`FINDINGS.md`, `TASK1-VIDEO-REPORT.md`, `audio.wav`, spectra/waveform, frames).

Measured:
- 8.38s, 720×1280 HEVC, internal-capture AAC.
- Music → **fade-out ~15ms to about −40dB** → **258.2ms bit-exact digital silence (0.183–0.441s)** → ~20ms fade-in.
- UI: “amoureux — Alleh”, Opus 48kHz 151kbps streamed, position 1:19→1:26 of 2:13, monotonic, no seek/track change. Rung: **“Smooth fade · 3s · 106→109 · locked”**, RELAXED 8b, A-instrumental @bar 51, B-intro @bar 4, `SWAP_RAMP_BLEND_MS 120`, `ECHO_DELAY_BEATS 0.75`.
- Hole at **1:19 of 2:13 (54s left — not EOF)**. Bar math: planned mix-start bar 1:21.5; hole at 1:19.2 = **exactly one bar earlier** → splice/abort at mix-start, not blend end. Countdown “0s” had no consequence.

Root cause (code-evidenced):
- `TransitionMoves.sheet()` for RELAXED sets outgoing lane flat at unity (`outGain = lane(-o*4, 0f, 0.0, 0f)`), so it never falls; `Envelope::at` holds 0dB past landing until buffer/`stopAt` kills it.
- Native `renderDeck` hard-stops OUT at `stopAt` with no ramp; OUT PCM region extends only ~0.5s past phrase; classic `BAIL_MS = 120ms`, instant `retire()` = `stop()` mid-audible; decode overrun → `loopFallBack` seek-settle can create the silence shape.
- Early fix that regressed: djmix2 “fade measured from the moment it actually starts; outgoing gain is a continuous curve with no step”; djmix3 “instrumental blend can flange (wet 0.42…)”. Native flanger was OFF (`HANDOVER_MASTER.pdf:411`). The RELAXED flat-unity lane was written later without the fall.

## 8. What djmix11 changed (and proof status)

Patch: `/home/hfzhkn/bitchord-djmix11-patch-20261112T1620Z.patch` (88K, 24 files + 2 new: `AutomixTelemetry.kt`, `ExitTailTest.kt`). Design: `/home/hfzhkn/bitchord-gracefulexit-design-20261112.md`. Envelope sim: `/home/hfzhkn/bitchord-gracefulexit-envelope-sim-20261112T1628Z.txt`.

Implemented:
- Shared: `PhraseConfig.exitTailSeconds` = 2 beats (floor 800ms, cap 2s), `LoopMix.Plan.exitTailSeconds`, `TransitionMoves.compile` sets it, single choke point **`withExitTail`** extends every outGain lane with a mute knot at lastBeat+tail (equal-power fall; fixes flat RELAXED/VOCAL). Emergency floor 350ms.
- Native: OUT PCM region/`stopAt` extended by tail; fractional-delay flanger on OUT (8-beat LFO, wet 0.42); exit-gate counter (deck silent while audible at `stopAt` = violation, telemetry-readable).
- Classic: new **`Phase.TAIL`** + `beginTailOrFinish`/`driveTail`; `finish()` may only retire a silent player, dissolve 800ms planned / 350ms emergency; `BAIL_MS` floored to 350ms; freeze guard includes TAIL.
- Telemetry: `mix.plan.exitTailSec`, `mix.exit.tail`, `mix.exit.gate`, UI detail `exit tail Xs`.

Tests/build: **1,774 executions, 0 failures** — shared 511 (jvm 207 + debug 152 + release 152) incl. new `ExitTailTest` (4×3: floor/cap math, RELAXED lane ends muted, all Templates end muted, no knot-step >6dB) + app 1,263. Envelope sim: before 40ms to −40dB (fail); after 800ms/1.132s/350ms (pass ≥300ms, monotone, mute at stopAt).

Device proof (evidence: `/home/hfzhkn/bitchord-device-evidence/djmix11-proof-20261112T0922Z/`, `ANALYSIS.md`, telemetry pulls 1–5):
- T1 **UN DIA → Se Vienen Cositas (item8→item9)**, native LoopMix M6 (88.93→126.70 BPM), `exitTailSec=1.349` (exact 2-beat rule), **`mix.exit.gate violations=0`**, no fallback/freeze/error. Verdict: **GRACEFUL-BY-STRUCTURAL-PROOF** (≥1.349s decay window vs 15–20ms video baseline, ~70–90× longer). No PCM captured, so confidence is HIGH for silent-before-stop, MEDIUM for full audible envelope.
- T2 Se Vienen Cositas → item10: user woke phone, item10 paused at 0, no gate observed. Verdict: **NO-VERDICT (interrupted)**.
- **Classic `Phase.TAIL` has never fired on device.** That is the exact rung in the user’s video. `mix.exit.tail` remains unobserved at runtime.
- **No after-audio envelope.** No user-ears confirmation yet.

## 9. Telemetry audit (do not repeat old mistakes)

Full file: 916 lines (`automix-telemetry-pull5.jsonl`). Corrections to earlier claims:
- Lines 1–911 are **djmix10-era** (0 `exitTailSec` on 13 RELAXED plans). djmix11 session starts 09:21:16Z; 5 events.
- **Correction:** “all 297 fallbacks are `outgoing decode 17203ms over`” is **FALSE**. Re-verified: decode-17203 = 12/297 (4.0%). Dominant fallbacks are `outgoing stream 17ms over` (145) + `outgoing splice 49012ms over` (101) = **82.8% timing/stream races**, not one decode signature.
- Audit: **rejected 547 vs plans 15 (97.3%)**; 511 identical before-window ticks = log spam; phrase-null 35 = tried=2 “off the phrase”; plans 13 all RELAXED, 12/13 unlocked; BPM deltas up to +42.5%/−15.2% (taste gap intact — exit now dissolves a still-mismatched jump); freeze 45+6, watchdog 3, error 2.

## 10. Immediate next steps (in order)

1. **Read before touching code:** this handover + `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` (full milestone log) + video `FINDINGS.md` + djmix11 `ANALYSIS.md` in the proof evidence dir.
2. **Verify device state:** `adb devices -l` (wireless `192.168.100.218:40601` last seen), `dumpsys package` (expect vc53 `2.0.1-djmix11`), pull telemetry first and preserve pull order.
3. **Drive the missing proof — classic path:** play a pair that the planner sends to classic Smooth fade (or ask user to play a pair + internal-audio screen-record through the mix). Require on device: `mix.plan.exitTailSec` present, **`mix.exit.tail` fired**, **`mix.exit.gate violations=0`**, and (if audio exists) **no silence gap >50ms + dissolve ≥300ms**. Compare directly against the video baseline (15ms fade, 258ms hole).
4. **Fix the next taste blocker:** streamed region-build overruns / timing races (`outgoing decode 159246ms over`; stream-17ms + splice-49012ms dominate fallbacks). Most sessions fall back to classic; now graceful, but slow. Use telemetry timestamps to pinpoint whether the race is decode scheduling, buffer availability, or `LoopMixSession.build` region timing.
5. **Fix wasted chances:** candidate scoring currently picks by score+type only; `break_outro` (quiet) can lose to `content_end` (loud). Persist/rank candidates with vocal/key/tempo/energy + log vetoes; reduce before-window tick spam; reconsider phrase-null when `tried=2`.
6. **Only then build/sign/install:** fetch/copy full source to `tanyadf-standby`, apply djmix11 patch (or verify already applied), run tests, **one** release build, sign with oldkey, `apksigner verify --print-certs` must equal `20c66ad6...e3fe4c`, arm64-only, `adb install -r`, verify vc bump.
7. **Definition of done:** classic transition on device with tail fired and measured dissolve (telemetry + ideally audio), no abrupt silence, musical window choice on the tested pairs, user has listened or explicitly says so. Until then, report as **“built and unit/telemetry-tested, unheard”**.

## 11. Hard rules (from HANDOVER_MASTER §6 + project history)

- Loop crown: shown countdown always plays; eligibility/bar counts follow djmix6.
- Patent scalars-only: A-exit from A, B-entry from B; compare only BPM/Camelot/LUFS/vocal-count scalars; never cross-track descriptor windows.
- GPL-only code + notices. Do not touch vendored Signalsmith headers.
- No `setPlaybackSpeed` for mix tempo post-handover (native deck only). No audio-thread alloc/locks.
- Same old signing key + versionCode bump; arm64-v8a only.
- Cloud builds only; do not run heavy Gradle builds on the poor local PC.
- UI layout on hold; only fix countdown/progress semantics if needed.
- Keep evidence local and checkpointed; cloud boxes can sleep ~2h.

## 12. Key files quick index (local)

- Live master log: `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md`
- Diagnosis: `/home/hfzhkn/bitchord-automix-DIAGNOSIS.md`
- djmix9 report: `/home/hfzhkn/bitchord-djmix9-BUILD-REPORT.md`
- djmix10 expanded patch: `/home/hfzhkn/bitchord-expanded-patch-final-20261112T0700Z.patch`
- djmix11 patch: `/home/hfzhkn/bitchord-djmix11-patch-20261112T1620Z.patch`
- Graceful-exit design: `/home/hfzhkn/bitchord-gracefulexit-design-20261112.md`
- Envelope sim: `/home/hfzhkn/bitchord-gracefulexit-envelope-sim-20261112T1628Z.txt`
- Video evidence: `/home/hfzhkn/bitchord-device-evidence/video-VID_20261112_151114/FINDINGS.md`
- djmix11 proof: `/home/hfzhkn/bitchord-device-evidence/djmix11-proof-20261112T0922Z/ANALYSIS.md`
- All device evidence: `/home/hfzhkn/bitchord-device-evidence/`

## 13. Honest current state (say this if asked)

- Installed on phone: djmix11 (vc53).
- Native M6 exit: structurally graceful on one real transition (1.349s tail, gate 0 violations).
- Classic Smooth-fade exit (the filmed failure): **implemented, unit-tested, installed, but not yet observed on device.**
- Audio: **no after-audio; not heard.**
- Next bottleneck: classic-path live proof + streamed region/timing overruns + wasted-chance scoring.
