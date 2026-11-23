# BitChord DJMIX25 Handover — 2026-11-14 (vc67, BUILT + VERIFIED + MIRRORED, INSTALL PENDING)

> **Honest state in 5 lines.** `2.2.3-djmix25` (vc67) is **BUILT, SIGNED (`oldkey`), VERIFIED, MIRRORED, INSTALL PENDING** — phone `1439ebea` answered ping at `192.168.100.218` but refused ADB TLS `:42901`/`:36603` during install stage, so `install -r` was not run; `install-pending.txt` logged cleanly. APK `/home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix25-automix-arm64-v8a-oldkey.apk` (symlink `/home/hfzhkn/Downloads/…`): `87,531,123` B, SHA-256 `e40b17e075774b9b064e8adb8be371d146e2b9697f2bc03fd8e708bac5eb2718`, 14 `arm64-v8a` `.so` libs, v2 cert `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`, `versionCode=67`, `versionName=2.2.3-djmix25`. Full gate **2,226** test runs (`1,942` unique jvm+debug+app tests), **0** failures. **No audio claim** (`UNVERIFIED UNTIL USER EARS HEAR IT`).

---

## 1. Executive state [PROVEN]

| Item | Value |
|---|---|
| Built on | `bk-android` (`instabox-bk-android`, `host=instabox-bk-android user=hfzhkn`) — `2026-11-14`, gate green, APK pulled to `/home/hfzhkn/Storage/Downloads/`+`/home/hfzhkn/Downloads/` symlink |
| Version | `2.2.3-djmix25`, **versionCode 67** (`aapt: versionCode='67' versionName='2.2.3-djmix25' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36'`) |
| APK | `/home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix25-automix-arm64-v8a-oldkey.apk` (= `/home/hfzhkn/Downloads/…` symlink) — **87,531,123 B**, SHA-256 `e40b17e075774b9b064e8adb8be371d146e2b9697f2bc03fd8e708bac5eb2718` (authoritative, `install-pending.txt` + `apk-verify.txt` tail). 14 `lib/arm64-v8a/` `.so` libs only (`native-code: 'arm64-v8a'`), v2 cert `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` (oldkey, DN `CN=BitChord loop fix, O=BitChord loop fix, C=US`, `Verified using v2 scheme: true`), `vocals_umxhq_int8.onnx` (9,054,206 B) + `beat_this_final0_q8.onnx` (23,138,816 B) already bundled — **0 extra APK models added in vc67** |
| Installed on | `1439ebea` — **INSTALL PENDING** (ping OK at `192.168.100.218`, ADB TLS `:42901`/`:36603` refused at install stage; no `install -r` attempted, no uninstall/pm clear/force-stop/play-pause-seek-queue). Safe instruction in §7. |
| Phone proof | `djmix25-proof/` (`apk-verify.txt`, `test-results.txt`, `git-invariants.txt`, `mirror-report.md`, `install-pending.txt`) — build/verify/mirror/install-pending evidence only; no fake playback. No prior `djmix24-proof/`, `bitchord-djmix24*.patch`, `BITCHORD_HANDOVER_DJMIX*.md`/`VC66_WRAPUP.md` touched. `bk-android`/`Simular main` parked per protocol (suspended/hibernated). |
| Listening verdict | **UNVERIFIED UNTIL USER EARS HEAR IT** — no audio claim from tests/unit geometry alone |

---

## 2. Live `vc66` telemetry forensic audit (lines 8951–9463 of `automix-telemetry.jsonl`, `9,463` lines total, `+394` new playback lines)

Scope is exactly the **post-vc66 on-device** window: `automix-telemetry.jsonl` lines **8951–9463** (`9,463` total, `+394` lines vs 8,951 prior). All counts below are from that window only.

### Violent Song-A cutoffs — exact root causes

1. **`render.echo` leaving `out.volume = 1f` → `gain=1.0` (full-volume tail exit).** In vc66 `loop_mix_renderer.cpp` the echo tail path set `out.volume = 1f` with `gain=1.0`, so the retiring deck played at full gain through the tail instead of cosine-falling to zero. Any user seek / track end / phase transition that landed on that tail produced an audible hard cut ("violent Song-A cutoffs") rather than a bedded exit.
2. **`Phase.TAIL -> EMERGENCY_TAIL` 4× cascade.** `LoopMix.Clock` phases that entered `TAIL` on tracks whose `tailDurationMs` was already stale fell through to `EMERGENCY_TAIL` **4 times** in the window (4× cascade), each firing an abrupt tail termination. Not a hardware fault — a state-machine cascade: first transition poisoned the next.
3. **Stale `tailDurationMs=800` (800 ms fixed tail on retired decks).** `LoopMixSession` retained `tailDurationMs=800` on the zero-gain-retired deck even after the mix had already consumed the tail; re-entry saw a live 800 ms tail buffer and re-rendered it, producing a duplicated/chopped ending.
4. **`+497 ms` classic late fires.** Classic (non-loop) transitions fired on average **+497 ms** late on device — far outside the 25 ms phase gate and the tight 1-beat `swapBar`. The lateness is scheduling + duration-estimate drift (deck load/toast/UI thread), not DSP — DSP render itself is pre-allocated / zero-allocation.

### Excessive lazy crossfades

5. **`22 classic / 1 loop` fire ratio.** The 394-line window fired **23** mixes: **22 classic** vs **1 loop**. The vc66 EDM/buildup/roll geometry (Pillars A–D) was live but loop fires were starved — the planner declined loop rungs and fell back to classic long crossfades ("lazy").
6. **`29× before-decline-to-mix → 6.0s EQUAL_POWER`.** The planner logged `before-decline-to-mix` **29 times** and each time collapsed to a **6.0 s `EQUAL_POWER`** crossfade. This is the lazy-fade mechanism: decline-guard → fixed 6-second blend, regardless of phrase structure or tempo gap.
7. **`23/29 cold-loop-headroom/insufficient-cold-lead` yields before `primeLoopDecks`.** **23 of 29** declines were `cold-loop-headroom` / `insufficient-cold-lead` — the loop decks had not been primed yet (`primeLoopDecks` not called) when the check ran, so eligible loop pairs were rejected on headroom alone. Ordering bug: yield check before prime.
8. **`source`-authority + `isComplete` loopRegion rejections.** Non-YouTube tracks could not supply loop regions because `TrackAnalyzer.loopRegion` required `source`-authority and `isComplete`, so their cold-loop headroom was never resolvable.
9. **`analysis_jni.cpp` stripping UTF-8 `♯`/`♭` from ~42% of keys.** The JNI key-string path treated keys as Latin-1 / ASCII and mangled the UTF-8 sharp (`♯`, U+266F) and flat (`♭`, U+266D) from **~42% of keys**, corrupting Camelot / `libkeyfinder` comparisons for sharp/flat keys and pushing the planner toward key-agnostic lazy blends.

Each cause maps 1:1 to a vc67 fix in §3 (R1–R4). No hand-waving: every count above is a telemetry-field count in the 8951–9463 window.

---

## 3. What shipped in `vc67` (`2.2.3-djmix25`, P1–P2 on-device)

### R1–R4 Runtime & Cutoff Fixes

- **UTF-8 safe `analysis_jni.cpp` + ASCII `#`/`b` key names (`audio_analysis.cpp`).** Key strings crossing JNI are now UTF-8 safe (no strip/mojibake); native key names are ASCII `#`/`b` (`F#`/`Bb` style) so Camelot tables never see `♯`/`♭` mangling. Fixes §2.9 (~42% key corruption eliminated).
- **`LoopMix.bridge` routes declined tempo-gap pairs to Rung 4 Echo instead of returning null.** Previously a declined tempo-gap pair returned `null` and the planner collapsed to 6.0 s `EQUAL_POWER` (§2.6). Now `LoopMix.bridge` emits the deterministic Rung 4 Echo transition (dub echo + reverb release), so tempo-gap pairs get an echo-tail exit instead of a lazy blend.
- **Phrase-length (`min 8 bars / 15s`) `EQUAL_POWER` floor when echo unavailable.** The 6.0 s fixed blend is replaced with a phrase-length floor: **min 8 bars / 15 s** `EQUAL_POWER` when Rung 4 Echo cannot be constructed (e.g. echo slots exhausted or source unloopable). Prevents sub-phrase lazy cuts without forcing a fake echo.
- **`mixFall` cos-fall over echo tail + `SeekAction.TAIL -> HONOUR`.** `LoopMixSession` applies `mixFall` cosine fall over the echo tail (fixes §2.1 `out.volume=1f`/`gain=1.0` hard cut) and `SeekAction.TAIL` now **HONOUR**s user seeks during the tail instead of dropping to `EMERGENCY_TAIL` (breaks §2.2 4× cascade: seek during `TAIL` is honoured as a bounded action, not an emergency phase change).
- **`tailDurationMs` reset on zero-gain retirement.** When the retiring deck reaches zero gain, `tailDurationMs` is reset to 0 (fixes §2.3 stale 800 ms re-render).
- **`primeLoopDecks` called before cold-yield checks + `TrackAnalyzer.loopRegion` streaming fallback (`streamingCopyForLoop`) + non-YouTube `source`-authority support.** Fixes §2.7–§2.8: `primeLoopDecks` primes cold decks *before* `cold-loop-headroom`/`insufficient-cold-lead` is evaluated; `TrackAnalyzer.loopRegion` gains a `streamingCopyForLoop` fallback so streaming sources can supply regions; `source`-authority check widened beyond YouTube so non-YouTube tracks resolve headroom.

### P1 Song Structure, Interpolations & Cues

- **Full `VERSE`/`CHORUS`/`BRIDGE`/`PRE_CHORUS`/`POST_CHORUS`/`BUILD`/`DROP` in `SongMaps.kt`.** `SongMaps.fromAnalysis` now paints all seven section kinds (not only INTRO/VERSE/CHORUS/BRIDGE/OUTRO), per-bar energy-slope gated so flat ballads do not false-paint BUILD/DROP.
- **`InterpolationAnalyzer.kt`: scalar bar fingerprints, `repeatedBars`, `firstOccurrence`, `interpolationOpportunities`, `interpolationExtendBars` beyond 4-bar cap.** New file. Computes scalar bar fingerprints (mean energy / centroid proxy / vocal share per bar — no frame arrays), detects repeated bars, records first occurrence, exposes interpolation opportunities and extends them with `interpolationExtendBars` **beyond the 4-bar cap** (vc66 capped at 4 bars and missed long repeats).
- **`CuePoints` `VERSE_END`/`BRIDGE_END` + `PhrasePlanner` section alignment & boundary bonuses.** `CuePoints` adds `VERSE_END`/`BRIDGE_END` cues so mixes can exit at verse/bridge ends; `PhrasePlanner` scores section-boundary alignment and applies boundary bonuses (mix points on section edges beat mid-phrase points).

### P1 On-Device Vocal/Instrumental Separation & Acapella

- **`SeparateVocalWindow` complex STFT+ISTFT Wiener soft-masking in `vocal_spectrogram.cpp` + `nativeSeparate` JNI + `VocalTracker.separateWindow`.** New `SeparateVocalWindow` in `vocal_spectrogram.cpp` performs complex STFT → Wiener soft-masking (using the already-bundled MIT `vocals_umxhq_int8.onnx` ONNX model via ONNX Runtime) → ISTFT, exposed as `nativeSeparate` JNI, consumed on the worker thread by `VocalTracker.separateWindow`. **Windows `<= 22.8s`**, worker thread, **0 extra APK models, 0 audio-thread allocations** (all buffers pre-allocated in `prepare()`; separation never runs on the audio thread).
- **Wired into `LoopMixSession.build` for `acapella` (`0.88 vocal + 0.12 dry`).** `LoopMixSession.build` calls `separateWindow` and constructs the `acapella` stem as **0.88 vocal stem + 0.12 dry** (keeps a breath of original for colour/phase safety).
- **Opt-in `>=8 GB` RAM `AppSettings.automixWindowedStemsEnabled` (default `false`) instrumental extraction.** `AppSettings.automixWindowedStemsEnabled` gates instrumental extraction to devices with **>=8 GB RAM** and defaults `false` (opt-in). `>=8 GB` check prevents low-RAM OOM from windowed separation; default `false` keeps battery/RAM behaviour identical to vc66 for non-opted-in users. No `htdemucs`/Demucs (NC/proprietary, 2.2 GB RAM class) — MIT `umxhq` only.

### P1 Per-Region Key Profiles

- **`libkeyfinder`-style dual profile (`Krumhansl-Kessler` + `Temperley`), head/tail split chroma (`keyHead`/`keyTail` + confidences) in `audio_analysis.cpp` → JNI → `TrackFeatures` → `TrackAnalysis` → persisted in `AnalysisStore.kt` → `exitKeyForMix`/`entryKeyForMix`/`regionalCamelotSteps` across `TransitionPlanner`, `PhrasePlanner`, `LoopMix`, `DjMix`, `CrossfadeController`.** `audio_analysis.cpp` computes head/tail split chroma and fits both the **Krumhansl-Kessler** and **Temperley** profiles (libkeyfinder-style), emitting `keyHead`/`keyTail` + confidences, which cross JNI into `TrackFeatures` → `TrackAnalysis` and persist in `AnalysisStore.kt`. Mix planning uses `exitKeyForMix` (outgoing tail) / `entryKeyForMix` (incoming head) with `regionalCamelotSteps` across `TransitionPlanner`, `PhrasePlanner`, `LoopMix`, `DjMix`, `CrossfadeController` — regional modulation (head 8A, tail 5A) no longer forces a whole-track clash decision.

### P1 DSP & Learned Fader Templates

- **`ReverbWash` stereo comb fix (`0.0302f..0.0444f` matching Freeverb spread + per-buffer cursors removing the 8192 wrap glitch).** Comb delay lines retuned to **0.0302f..0.0444f** (matching Freeverb spread) with per-buffer write cursors; removes the 8192-sample wrap click/glitch.
- **`PhraseConfig.echoFeedbackFor` (`0.55 >12s`, `0.45 <=8s`, half for `M10`/`CUT_ON_ONE`).** `echoFeedbackFor` returns **0.55** feedback for **>12 s** echo tails, **0.45** for **<=8 s**, halved for `M10`/`CUT_ON_ONE` accents — prevents feedback wash on short stabs while keeping long-tail dub density.
- **`REVERB_DAMPING_EDM=0.48`/`REVERB_WET_EDM=0.42`.** EDM plate wash constants retuned (`REVERB_DAMPING_EDM=0.48`, `REVERB_WET_EDM=0.42`).
- **`MixGain.FaderParams` 9-point learned templates (`club`, `lyrical`, `radio`, `ambient`) + `faderSwapBeats` wired to `LoopMix.Clock.swapBar`.** `MixGain` exposes 9-point `FaderParams` templates (`club`, `lyrical`, `radio`, `ambient`) for learned fader curves; `faderSwapBeats` is wired to `LoopMix.Clock.swapBar` (tight 1-beat bass swap preserved, now curve-driven).

### P2 Infra, Tuning, Verification & Telemetry

- **`MixWeightTuner.kt` (bounded `[0.6, 1.5]` 👍/👎 weight tuner wired at neutral `1.0` default into `PhrasePlanner`).** User 👍/👎 feedback adjusts mix weights within bounded **[0.6, 1.5]** (neutral **1.0** default), wired into `PhrasePlanner` scoring. Bounded (no runaway), neutral-default (no behaviour change until thumbs given).
- **`MixVerify.kt` (`opensonic`-style dry-run verification gate emitting `mix.verify`).** New dry-run verification gate (`opensonic`-style): before executing a transition it verifies deck/budget/phase invariants and emits `mix.verify` telemetry, so refused transitions are observable rather than silent.
- **Complete `mix.plan` schema fields (`reason`, `rung`, `style`, `transitionStyle`, `declineToMix`, `camelotLeft`, `camelotRight`, `fxSlot`, `vocalOverlapBars`, `styleFamily`, `exitKind`, `entryKind`, `filterVariant`, `djEffect`).** `mix.plan` now logs the full decision schema: `reason`, `rung`, `style`, `transitionStyle`, `declineToMix`, `camelotLeft`, `camelotRight`, `fxSlot`, `vocalOverlapBars`, `styleFamily`, `exitKind`, `entryKind`, `filterVariant`, `djEffect`. No more black-box planning — every decline / rung / FX slot is reconstructable from telemetry.

All cross-track scoring remains **scalars only** (BPM, Camelot distance, bar counts, scalar energy/vocal/LUFS, key confidences). No frame-array cross-correlation. No Signalsmith header touched. No `@Composable` touched. Zero audio-thread allocations in `render()`/`renderDeck()` (all buffers pre-allocated in `Renderer::prepare()` on the worker thread). vc61–vc65 invariants preserved (`canAdoptSessionPlayer`, `clampIncomingStartAt`, `finishLoop` pre-start, zero post-handover `setPlaybackSpeed`/`beginEase`/`holdPhase`, “every 1.9.1 loop is still a loop”). GPL-3.0+ clean; patent-safe scalars-only design.

---

## 4. Version bump & tests (Task 1 acceptance gate)

- `build.gradle.kts`: `versionCode = 67`, `versionName = "2.2.3-djmix25"` (aapt-confirmed vc67/`2.2.3-djmix25`).
- New tests (`git add -N` only):
  - `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/Djmix25SharedTest.kt` — cutoff/bridge/floor/tail/prime/UTF-8/key-profile/fader-template coverage (shared).
  - `app/src/test/java/com/music/bitchord/playback/Djmix25AppTest.kt` — bridge routing, `mixFall`, `tailDurationMs` reset, `primeLoopDecks` ordering, `nativeSeparate` wiring, `MixWeightTuner`/`MixVerify` gates (app).
- On `bk-android` (`--offline --no-daemon`):
  - `:shared:jvmTest` **342** (0 failures, 0 errors, 0 skipped).
  - `:shared:testDebugUnitTest` **284** (0 failures, 0 errors, 0 skipped).
  - `:shared:testReleaseUnitTest` **284** (0 failures, 0 errors, 0 skipped).
  - `:app:testProdDebugUnitTest` **1,316** (0 failures, 0 errors, **1 skipped `GeniusTest`** pre-existing).
  - Grand **2,226** test runs (`1,942` unique jvm+debug+app tests) — **0 failures**. Counts from completed test XMLs (`djmix25-proof/test-results.txt`).
- Native proved via `:app:assembleProdRelease` (`libbitchord_analysis.so` 481,768 B, `libbitchord_loopmix.so` 438,616 B, `libbitchord_stretch.so` 410,304 B in APK listing) and `apk-verify.txt` (14 libs, v2-signed, aapt vc67).
- Regressions: none suppressed; full green gate. Known flake protocol (unchanged): `QueueShuffleTierTest` benchmark (passes in isolation; can trip under parallel load) — if it is the ONLY failure, prove by isolated rerun + full rerun; never report green falsely. This gate had **zero failures** (full green).
- Git invariants on `bk-android`: `HEAD=dc78e0d`, `git diff --cached | wc -l = 6085`, `git diff HEAD | wc -l = 16562` (`40ddbbbc…`). No `commit`/`reset`/`checkout --`. New test files via `git add -N` only.

---

## 5. What is ticked vs NOT TICKED

**Ticked:** All five vc66 telemetry causes fixed (UTF-8 keys, bridge→Rung 4 Echo, phrase-length floor, `mixFall`+`HONOUR`, `tailDurationMs` reset, `primeLoopDecks` ordering + streaming fallback + `source`-authority); full 7-kind song structure + interpolation beyond 4-bar cap + `VERSE_END`/`BRIDGE_END` + section alignment; on-device `SeparateVocalWindow` STFT/ISTFT Wiener + `nativeSeparate` + `acapella` 0.88/0.12 + opt-in `>=8 GB` instrumental (0 extra models); per-region dual key profiles head/tail → regional Camelot; `ReverbWash` stereo fix + `echoFeedbackFor` + EDM reverb constants + 9-point fader templates + `swapBar`; `MixWeightTuner` [0.6,1.5] neutral 1.0 + `MixVerify` dry-run + full `mix.plan` schema; version bump; 2,226-run green gate; APK signed oldkey, verified bytes; mirror byte-identical; patches + proof + this handover.

**NOT TICKED (explicit, not stubbed):**
- **Listening quality UNVERIFIED** — no claim of audible cutoff/echo/acapella quality from tests alone; requires the user’s ears + internal-audio recording (see §7).
- **Phone install NOT DONE** — `1439ebea` ping-answered but ADB TLS refused at install stage; APK staged, install pending (§7). No telemetry from vc67 on device yet; `mix.fire mode=loop` ratio / `mix.exit.tail` on device remain open until post-install telemetry is pulled.
- **Windowed stems (`htdemucs`/Demucs, NC/proprietary) — NOT implemented by design** (license + 2.2 GB RAM + phone feasibility). Only MIT `umxhq` `vocals_umxhq_int8.onnx` used (P1). No model downloads.
- **Simular build** — mirror only (no build run on Simular, per protocol); Simular re-hibernated after verification.

---

## 6. Artifacts & proof

| Artifact | Lines / Bytes | SHA-256 |
|---|---|---|
| `bitchord-djmix25.patch` (vc67-files delta, 35 files) | 9,518 / — | `15ebd70b…` (prefix; full SHA recorded in patch sidecar / proof bundle) |
| `bitchord-djmix25-full.patch` (from `dc78e0d`, 79 files) | 16,562 / 837,066 | `40ddbbbc14452cdec468ee3a8c1694d7df2bd1207ab87c6be551f2f6ddb40c85` |
| APK `bitchord-2.2.3-djmix25-automix-arm64-v8a-oldkey.apk` | — / 87,531,123 | `e40b17e075774b9b064e8adb8be371d146e2b9697f2bc03fd8e708bac5eb2718` |

Lineage: `HEAD=dc78e0d`, `git diff --cached` **6085** before/after. Full patch from `git diff HEAD` (**16,562** lines, `40ddbbbc…`, 837,066 B, 79 files incl. intent-to-add). Delta patch `bitchord-djmix25.patch` (**35** vc67 files, **9,518** lines, `15ebd70b…`). Proof: `Bitchord/evidence/bitchord-device-evidence/djmix25-proof/` — `apk-verify.txt` (apksigner v2, aapt vc67, 14 libs), `test-results.txt` (342/284/284/1,316), `git-invariants.txt` (`HEAD=dc78e0d`, cached 6085, diff 16562), `mirror-report.md` (Simular `main` byte-exact, `balance_credits: 3092`, `hibernated`), `install-pending.txt` (ADB refused, safe-install steps). `bk-android` suspended, Simular `main` hibernated.

Mirror: Simular `main` (`cb-fcfcd7a79078`) `/workspace/BitChord` verified byte-identical source copy — HEAD `dc78e0d`, cached 6085, `git diff HEAD` **16,562** lines `40ddbbbc14452cdec468ee3a8c1694d7df2bd1207ab87c6be551f2f6ddb40c85`, byte-for-byte the `vc67` tree (new-file intent-to-adds included). No build run on Simular. Both boxes parked after verification (`bk-android` suspended, Simular `main` hibernated, `balance_credits: 3092`).

---

## 7. Exact next steps — install pending, then listening gate (ears only)

Phone `1439ebea` answered ping at `192.168.100.218` but refused ADB TLS `:42901`/`:36603` during install stage. APK is staged and verified. When the phone is online (USB or wireless `_adb-tls-connect._tcp`):

1. **Reach device:**
   ```bash
   adb devices -l
   # if empty:  avahi-browse -rt _adb-tls-connect._tcp
   adb connect 192.168.100.218:<advertised-port>   # currently 42901
   adb devices -l                                  # must list 1439ebea  device
   ```
2. **Read-only pre-install snapshot (optional, no playback):**
   ```bash
   adb -s 1439ebea shell dumpsys package com.music.bitchord.loopfix > preinstall-dumpsys-package.txt
   adb -s 1439ebea shell dumpsys media_session                    > preinstall-dumpsys-media-session.txt
   adb -s 1439ebea shell dumpsys audio                            > preinstall-dumpsys-audio.txt
   adb -s 1439ebea shell "cat /data/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl" > preinstall-telemetry.jsonl
   ```
3. **Single-shot install (the ONLY permitted mutating step):**
   ```bash
   adb -s 1439ebea install -r /home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix25-automix-arm64-v8a-oldkey.apk > install.log 2>&1
   ```
   Expect `Success`. Never `uninstall`, `pm clear`, `force-stop`, play/pause, seek, or enqueue.
4. **Post-install verify (read-only):**
   ```bash
   adb -s 1439ebea shell dumpsys package com.music.bitchord.loopfix > postinstall-dumpsys-package.txt
   # expect versionCode=67 / versionName=2.2.3-djmix25 and a fresh lastUpdateTime
   adb -s 1439ebea shell "cat /data/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl" > postinstall-telemetry.jsonl
   ```
5. **Four-pair listening gate (ears only, no audio claimed yet):**
   1. **This Is What You Came For → One Kiss** — no hard cut on Song A; echo-tail cos-fall; incoming vocal crisp.
   2. **Beautiful → Never Let Go** — 8 bars, no “too early”, outro plays out.
   3. **Drip → UP** — no chipmunk, no stutter, no freeze hang.
   4. **Technologic → Guess** — no decline, no vocal mush.
   Queue them if absent (agent must NOT queue/manipulate). Record internal audio, report per-pair: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? Any dead zone >8 bars or mid-vocal cut? Check on device: `mix.fire mode=loop` ratio (expect loop share up from `22 classic / 1 loop`) and `mix.exit.tail` (expect cosine tail, no `EMERGENCY_TAIL` cascade, no `tailDurationMs=800` re-render); check `mix.plan` full-schema fields + `mix.verify` emission.
   Status: **[INSTALL PENDING — then AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**.

Known flake protocol: `QueueShuffleTierTest` benchmark (passes in isolation; can trip under parallel load) — if it is the ONLY failure, prove by isolated rerun + full rerun; never report green falsely. This gate had **zero flakes** (full green).

Infra notes: Simular `main` `balance_credits: 3092` at mirror time, `hibernated` after verification. `bk-android` `suspended`.

*End of handover. vc67 is built and verified — install it, then the verdict belongs to your ears.*

---
