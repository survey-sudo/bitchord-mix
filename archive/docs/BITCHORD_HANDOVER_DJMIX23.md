# BitChord DJMIX23 Handover — 2026-11-14 (vc65, BUILT + VERIFIED + INSTALLED)

> **Honest state in 5 lines.** `2.2.3-djmix23` (vc65) is **BUILT, SIGNED (`oldkey`), VERIFIED, AND INSTALLED ON `1439ebea`** 2026-11-14 ~10:38 WIB (`adb install -r` → `Success`, `versionCode=65`, `versionName=2.2.3-djmix23`, `lastUpdateTime=2026-11-14 10:38:09` via wireless serial `adb-1439ebea-05jQT0 (2)._adb-tls-connect._tcp` 192.168.100.218:39361). Full gate 2,104 tests 0 failures; APK `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix23-automix-arm64-v8a-oldkey.apk` (SHA `783732010afecf4898b24d7538024129963d1e8fba3b1c1f0d2d96c087cfb423`, 87,498,355 B), 14 arm64 libs, v2 cert `20c66ad6…e3fe4c`; telemetry 8,190 lines byte-identical pre/post install (prefix MATCH, no playback sent); crash-log 586 lines unchanged. Forensics proved the vc64 fix targeted the wrong cause: `mix.pause.detected changeReason=1` is **`Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST`** (Media3 1.11.0), not `AUDIO_FOCUS_LOSS` (2) — the pause is an **app-initiated `pause()`**, not a focus loss. Root cause: the loop clock's hold geometry schedules the incoming decoder start (`inStartAt`) **after** the session handoff (`handoffAt`), so `handOff` adopts a never-started (`playWhenReady=false` since `armLoop`, READY, position 0) player; the next tick's paused-check bails to `loopBail` and `finishLoop` completes `loop-complete` onto a paused song. Next: run the four-pair listening gate (ears only). **No audio claim:** listening verdict is the user's only. No play/pause/seek/queue was sent this cycle.

---

## 1. Executive state [PROVEN]

| Item | Value |
|---|---|
| Built on | `bk-android` `/data/workspace/bitchord-task2/work/BitChord` via Instabox CLI — running at build time (`runStartedAt=2026-11-14T03:14:23.558529Z`, `maxRunMinutes=120` → run window ~05:14 UTC; the gate job `ca408eef` survived the cooldown suspend with processes preserved and finished after resume) |
| Version | `2.2.3-djmix23`, **versionCode 65** (`build.gradle.kts:44-45`) |
| APK | `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix23-automix-arm64-v8a-oldkey.apk` (realpath `/home/hfzhkn/Storage/Downloads/...`) — 87,498,355 B, SHA-256 `783732010afecf4898b24d7538024129963d1e8fba3b1c1f0d2d96c087cfb423`; pulled byte-identical (`instabox pull` verified). 14 `lib/arm64-v8a/` libs only, `native-code: 'arm64-v8a'`, v2 cert `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` (oldkey, DN `CN=BitChord loop fix`). |
| Installed on | `1439ebea` — **INSTALLED `2.2.3-djmix23` vc65** (`versionCode=65`, `lastUpdateTime=2026-11-14 10:38:09`) via wireless `adb-1439ebea-05jQT0 (2)._adb-tls-connect._tcp` 192.168.100.218:39361, `adb install -r` → `Success`. Prior phone version `2.2.3-djmix22` vc64 (`lastUpdateTime=2026-11-14 05:40:17`). Telemetry 8,190 lines pre/post byte-identical (`09ce8490…`, prefix MATCH). Crash-log 586 lines unchanged. No play/seek/queue sent. |

---

## 2. Forensic evidence (video + telemetry)

- Both uploaded videos are **bit-identical** (`sha256 1f0a814f…ab84`, 606,527 B). HEVC 720×1280, 3.6 s + AAC mono.
- Envelope: full level to 1.545 s; ~45 ms fade to bit-zero at 1.60 s; 2.05 s digital silence to clip end, no resume. UI goes from "This Is What You Came For — Beat blend · looping" ∥ (playing, 3:25→3:27) to "One Kiss" 0:00 ▶ paused, frozen to video end. The 1.5 s of audio is the native loop renderer; the cut is the session adopt.
- Telemetry (pull SHA `94be5b2c…0bb`, 7956 lines), **two identical failures** on the Calvin pair 124.341 → 124.249:
  - Recorded: `mix.fire loop planned 206529 actual 205969 delta -560` @06:23:58.326 seq50 → `mix.pause.detected driveLoopMix playWhenReady=false state=3 reason=1 handedOff tookOver positionMs=0` @06:23:59.923 seq51 → `mix.complete LOOP_RUNNING handoff=true loop-complete` seq53 → `post-handoff paused reason=1 positionMs=25` @06:24:00.178. Video↔telemetry lock: 58.326 + 1.545 s + ~50 ms recorder ≈ 59.92 = pause time.
  - Mirror: fire @06:23:07.260 seq11 → same pause seq12 → complete seq14 → post-handoff seq17.
- `dumpsys audio`: `abandonAudioFocus` 06:23:08.707 and 06:23:59.854 (the adopts), **no `requestAudioFocus` after either** until the user acted later — focus provably uninvolved. `logcat -d` covers the window (152,717 lines) but release strips app `Log`.
- Full evidence: `Bitchord/evidence/bitchord-device-evidence/djmix23-forensics/` (FINDINGS.md incl. probe postscript, envelope, 36 dense frames).

### Root cause (traced from code + telemetry, not guessed)

The paused player is `READY` at position 0 with reason USER_REQUEST — exactly an `ExoPlayer` that `armLoop` set to `playWhenReady=false`, `prepare()`d, and never started. `driveLoopMix` starts the incoming player at `m >= inStartAt` and hands the session over at `m >= handoffAt`, ordered but unbounded: the loop clock's hold branch (`LoopMixSession.planIncomingStart`: `hold > 0 && tBase < 0 && handoverTrack >= 2.5`) pushes `inStartAt` to `elementAt − handoverTrack` (≈ the hold horizon), which exceeds the mid-ramp `handoffAt`. A real-planner probe (`TransitionMoves.compile` + `LoopMix.Clock`, 3 BPM pairs × M1 overlap 1–16 × prep 0–12) found **`inStartAt > handoffAt` in 624/624 plans** (e.g. ov=1/prep=0 @124.34→124.25: hold=1.93, elem=7.73, handover=5.80, handoffAt=0.97, inStart=1.93). Live `handoverTrack=11615` + `targetMs=25/heard=25` (the heard validity check forces `inn.loopStart ≤ 0.075`) pins `loopStart≈0`, so `inStartTrack==loopStart≈0` and position 0 is the unstarted player at its start. The vc64 focus fix (`reason == AUDIO_FOCUS_LOSS`) can never fire for this bug; the abandons are handoff side-effects.

---

## 3. What changed vs vc64 (in the worktree, targeted-green)

Two files + version + one new test file. `vc64` focus machinery untouched; no `setPlaybackSpeed`/`beginEase` changes; scalars-only telemetry.

### 3.1 Adoption guard (`CrossfadeController.kt`)

`driveLoopMix` only runs `handOff(out, into)` when `canAdoptSessionPlayer(loopIncomingStarted)` holds (new pure predicate next to `loopFinishAction`, :279). While unstarted, it logs `mix.handoff.deferred` once per loop session (`mMs`/`inStartAtMs`/`handoffAtMs`) and waits — the renderer keeps the mix audible, so the delayed adoption is inaudible. On-time plans (`inStartAt < handoffAt`) are byte-for-byte unchanged.

### 3.2 Start clamp + `finishLoop` pre-start

- `LoopMixSession.planIncomingStart` delegates to the extracted pure `incomingStartFor(clock)` (same math, no native instance needed); `clampIncomingStartAt` keeps the start strictly before `elementAt` with a `STARTUP_LEAD` decode head-room, so the start cannot fall off the end of the mix (order (ii): `elementAt <= pre-clamp inStartAt`).
- `finishLoop` pre-starts the incoming player first when `!loopIncomingStarted` and the outgoing was still `playWhenReady` (mixed was playing; a user pause — `playWhenReady` already false — is never second-guessed), then `handOff(out, into)` when not handed off — so the end-of-mix path can never land the session on an unstarted player either.
- `mix.pause.detected` (all three sites) now also reports `loopIncomingStarted`/`inStartAtMs`/`handoffAtMs`, making any residual pause self-diagnosing.

### 3.3 Tests

- `Djmix23AppTest`: 7 tests — adoption guard (unstarted never adopted / started adopted); order (i) `handoffAt < inStartAt < elementAt` pinned against the real `LoopMix.Clock` + `incomingStartFor` (Calvin BPMs, M1 ov=1/prep=0); order (ii) `clampIncomingStartAt` past-end cases + a **624-plan grid sweep** (start < elementAt with decode lead, `inStartTrack` in range); user-pause-never-resumed ×2.
- Targeted on box: `Djmix23AppTest` **7/7**, `Djmix22AppTest` 12/12, `Djmix19AppTest` 5/5 (job `aa5efd12`, `TARGETED_DONE:0`). Full gate job `ca408eef` **finished green after the cooldown resume** (`DONE:0`, `BUILD SUCCESSFUL in 2h 37m 08s` — the wall time includes the suspend freeze; completion is ~03:17 UTC): `:shared:allTests` **805** (jvm 307 + debug 249 + release 249, 0 failures), `:app:testProdDebugUnitTest` **1,299** (0 failures, 0 errors, 1 skipped `GeniusTest` pre-existing), grand **2,104** (vc64: 2,097 + 7 `Djmix23AppTest`). Counts from completed test XMLs on the box. No flake needed a rerun.

---

## 4. Artifacts

| Artifact | Lines / Bytes | SHA-256 |
|---|---|---|
| `bitchord-djmix23.patch` (vc64→vc65 delta, 4 files) | 433 / 19,987 | `adbd740eab450a2c2e90a003de272a9bc1b605e750a7c7cc8ea38f095ec68b7c` |
| `bitchord-djmix23-full.patch` (from `dc78e0d`, 62 files) | 12,217 / 622,848 | `989b0adb64edc0d9f31e7f5c0944af1d48e0c382b0c9ec638897b787861c04c6` |

APK: `Downloads/bitchord-2.2.3-djmix23-automix-arm64-v8a-oldkey.apk` 87,498,355 B, SHA `783732010afecf4898b24d7538024129963d1e8fba3b1c1f0d2d96c087cfb423` (byte-identical pull), 14 arm64 libs, v2 oldkey cert `20c66ad6…e3fe4c`, aapt vc65/`2.2.3-djmix23`.

Lineage: `HEAD=dc78e0d`, `git diff --cached` **6085** before and after (verified post-gate). `git add -N` only the new `Djmix23AppTest.kt`. Full patch from `git diff HEAD` (62 files incl. intent-to-add). Delta rebuilt via the vc64 reconstruction (dc78e0d `HEAD` archive + `bitchord-djmix22-full.patch`, version 64/`2.2.3-djmix22` verified), overlaid with the 4 vc65 files fetched from the live worktree — `git apply --check` clean on the vc64 tree; the 64→65 / djmix22→djmix23 version lines verified.

Mirror: Simular `main` (cb-fcfcd7a79078) `/workspace/BitChord` verified byte-identical source copy — HEAD `dc78e0d`, cached 6085, `git diff HEAD` 62 files / 9204+/526-, staged/unstaged/intent-to-add preserved, 4 vc65 file hashes match bk. Toolchain on mirror: `/workspace/android-env.sh` → JDK 21.0.13, SDK android-36, build-tools 36.0.0, NDK 27.3.13750724, CMake 3.22.1; Chrome purge holds (no binary/processes). Keystore at `/workspace/keys-local/bitchord-loopfix.jks` (mode 600, hash verified, never in git history or in this repo transfer log). No build run on Simular. Full detail: `Bitchord/evidence/bitchord-device-evidence/djmix23-proof/mirror-report.md` + `apk-verify.txt`.

No install was previously blocked; install completed 2026-11-14 10:38:09 WIB (see §7) with pre/post proof in `djmix23-proof/` (`install.log` Success, `postinstall-telemetry.jsonl` 8,190 lines `09ce8490…`, prefix MATCH vs preinstall, crash-log 586 lines unchanged).

---

## 5. User listening gate (ears only, no audio claimed yet) — after the build lands

Order:
1. **This Is What You Came For (Calvin Harris ft. Rihanna) → One Kiss (Calvin Harris x Dua Lipa)** — the vc64 bug pair. Song 2 must now *keep playing through the handoff* — no abrupt pause ~1.6 s in, no `mix.handoff.deferred` followed by silence — incoming vocal *crisp from the first line, not muffled*.
2. **Beautiful (Bazzi) → Never Let Go (LNGSHOT)** — 8 bars, no "too early", outro plays out.
3. **Drip (ATEEZ) → UP (ATEEZ)** — no chipmunk, no stutter, no freeze hang.
4. **Technologic (Daft Punk) → Guess (Charli XCX)** — no decline, no vocal mush.

Queue them if absent (agent must NOT queue/manipulate). Record internal audio, report per-pair: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? Any dead zone >8 bars or mid-vocal cut? For pair 1 specifically: did song 2 keep playing past ~1.6 s after the blend starts (the vc64 failure point), and did `mix.handoff.deferred` appear in telemetry (the guard holding the adoption)?

Status: **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT — after the build lands]**.

---

## 6. Do-not list

- No `adb uninstall` / `pm clear` / queue edits; `install -r` only.
- No MCP `box` for `bk-android`; Instabox CLI only.
- No `sleep` loops for builds (use `instabox jobs wait`).
- Never edit/delete prior handovers/APKs/patches/proofs.
- Never touch Signalsmith headers; scalars-only cross-track scoring always.

---

## 7. Exact next steps — install DONE; listening gate pending

The cooldown window was used as designed: `instabox resume bk-android --no-wait` at 03:14:11 UTC (past `cooldownUntil=2026-11-14T03:13:56Z`), exec-confirmed ready; `runStartedAt=2026-11-14T03:14:23.558529Z`, `maxRunMinutes=120`. Job `ca408eef` was found still `running` (processes preserved across suspend) and was awaited to `DONE:0` instead of firing a duplicate. Build/verify/pull/patches/mirror DONE (see §4). Wireless adb was re-enabled by the user; single-shot install completed:

1. ~~Re-pair wireless adb on phone `1439ebea`~~ — DONE (user toggled Wireless Debugging on; `adb devices -l` showed `adb-1439ebea-05jQT0 (2)._adb-tls-connect._tcp` `device` at 192.168.100.218:39361).
2. ~~Single-shot install~~ — DONE 2026-11-14 10:38:09 WIB: `adb -s "adb-1439ebea-05jQT0 (2)._adb-tls-connect._tcp" install -r /home/hfzhkn/Downloads/bitchord-2.2.3-djmix23-automix-arm64-v8a-oldkey.apk` → `Success` (`djmix23-proof/install.log`). Never uninstalled/cleared/force-stopped; never played/paused/sought.
3. ~~Read-only post-install proof~~ — DONE in `Bitchord/evidence/bitchord-device-evidence/djmix23-proof/`: `preinstall-dumpsys-package.txt` (vc64), `postinstall-dumpsys-package.txt` (vc65 `versionName=2.2.3-djmix23`), `preinstall/postinstall-telemetry.jsonl` 8,190 lines byte-identical (`09ce8490…`, prefix MATCH), `preinstall/postinstall-crashlog.txt` 586 lines unchanged, `postinstall-dumpsys-media-session.txt`, `postinstall-dumpsys-audio.txt`.
4. REMAINING (user ears only): Run the §5 four-pair listening gate and record internal audio verdicts. Both cloud builders were already parked (`bk-android` suspended, Simular `main` hibernated) — no runtime burn during listening. Start `vc66` P0 only after the four-pair verdict.

Known flake (not hit this gate): `QueueShuffleTierTest` benchmark (passes 27/27 in isolation; on parallel load it can trip) and `StructureAnalyzerTest` timeout flake — if either is the ONLY failure next time, prove it by targeted isolated rerun and rerun the full gate; never report green falsely.

Infra notes: Simular credits 3,379 at 02:47 UTC (down from 3,414 reported at 09:29 WIB — burn rate unmeasured, no fabricated rate). Standby fork `bk-android-standby` intentionally skipped (optional/subordinate; capacity risk vs release). bk had 19 historical OOM kills (last 2026-11-12, java 3.6 GB) — gate passed at 4.8 GB peak, no OOM this run.

*End of handover. vc64 is live on the phone — the verdict belongs to your ears.*
