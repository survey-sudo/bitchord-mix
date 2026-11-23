# BitChord AutoMix — Task 1 Diagnosis + Implementation Plan (djmix8, 12 Nov 2026)

Source basis: local files + bk-android source checkout. All line numbers are **djmix6-src** (`/data/workspace/bitchord-dissect/djmix6-src`, `build.gradle.kts` `bitchordVersionCode=48`, `1.9.1-djmix6`) unless stated — this is the closest full tree available. djmix8 = vc50 / `1.9.1-djmix8`, git revision `eeae9319...` (from APK `META-INF/version-control-info.textproto`); its tree is NOT on disk (see §1.F). Counts of evidence accordingly.

---

## 1. Input inventory (all 7 accounted for)

1. **Google Drive folder** `Grok bitchord handover` (`1-9CHqWj9TPxHc60TPviVdwEsg0wr9W6E`): **reachable unauthenticated** (`curl` HTTP 200, page title `Grok bitchord handover`). Listing parsed from HTML: 5 entries — `BITCHORD_FULL_HANDOVER_2026-11-11_part1_docs-source.zip` (drive id `1f2NdbMqNNRbtxpuTLIpR3DUd6iHq2V_H`), `BITCHORD_FULL_HANDOVER_2026-11-11_part2_apk.zip` (`1kFbmMaoEHFlko7pS5v6PEEJcA_KGkERN`), `BITCHORD_HANDOVER_part1_docs-source.zip` (`1GR6CZ_eW18NqDV__qOPlC49g67A6fGb7`), `BITCHORD_HANDOVER_part2_apk.zip` (`1Z3_CKmklssEIwmzpsjYjjc0MMH1lgeDc`), `BitChord-djmix7.apk` (`19C491ENem84s2TpuZQASViXeRGAm-E_8`). No download performed in Task 1 (light-local rule); Task 2 should fetch part1 (djmix7 snapshot b4716f0 → base for djmix8 delta). The test pair (Beautiful / Never Let Go) does NOT appear in the listing — they are user-known reference tracks, not Drive files.
2. **`/home/hfzhkn/Downloads/bitchord-cursor-convos.zip` (26 KB)**: unzipped to `/tmp/opencode/bitchord-task1/unzipped/bitchord-cursor-convos/` — 4 files read fully:
   - `01-bitchord-loop-fix-djmix8.md` — the djmix8 brief + agent reply. Root cause as fixed: plan swap after countdown shown (Beat Blend 31s → Loop Blend 20s), loop unarmable → loop plan skips blend path → fallback cleared plan → re-planned loop forever → A played to silence, `Mix: 0`, countdown negative. Fix claimed: shown-countdown locks rung, 1.2 s watchdog → echo → crossfade, countdown stops at 0, abort log (`AutomixScript`/`BitChordPhrase`/`AutomixCrash`). Harness 16/27 (0.593) → 27/27 (1.000). APK SHA-256 `1c67e12…7f8d`, 87,467,140 B, **new** cert `28065e7b…` (requires uninstall of djmix7).
   - `02-bitchord-private-fork-loop-mix-aborts.md` — original loop-abort bug (ATEEZ Adrenaline: `loop mix in 6s` → `filter mix in 27s · no loop: the decks took too long`); agent errored on usage limit, no fix landed there.
   - `03-bkmasterplayer-1.8.1-ytm.md` — BK fork + YTM source; YTM2 APK NOT produced (usage-limit stop after 15-file edit).
   - `04-automix-player-youtube-music.md` — separate Automix player experiment; user verdict "messy asf", "worse than both apps"; still useful as spec for transparency/performance-mode/beatomix model notes.
3. **Current APK** `bitchord-loopfix-1.9.1-djmix8-arm64-v8a-oldkey.apk`, 84 MiB on disk (87,582,011 B), SHA-256 `4e7c4a0005a161eb5ffa00af3cd6ec92248933b968a3d85240e7850063aefd38`. Contents (zip listing): `lib/arm64-v8a/` — `libbitchord_analysis.so` 471,896 B, `libbitchord_loopmix.so` 430,304 B, `libbitchord_stretch.so` 410,304 B, `libonnxruntime.so` 28,637,280 B, `libffmpegJNI.so`, `libconscrypt_jni.so`, etc. (arm64-v8a ONLY). Assets: `beat_this_final0_q8.onnx` 23,138,816 B + `vocals_umxhq_int8.onnx` 9,054,206 B (identical to upstream v1.9.1 sizes). `classes.dex` 10,425,076 B + `classes2.dex` 10,401,668 B + `classes3.dex` 4,486,144 B. Signing: `META-INF/BITCHORD.RSA` PKCS#7 cert Oct? Nov 9 2026, CN `BitChord loop fix`, SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` — **this IS the old key** (matches `BitChord-keystore.zip` `keystore_info.txt` exactly; serial `5671f7aa0660eabf`, RSA-2048, valid to 2054). So `-oldkey` = freshly built djmix8 **re-signed with the old keystore** (in-place update over djmix7 possible) — NOT the djmix8 agent's new-key APK (`28065e7b…`). `version-control-info.textproto` revision `eeae931952df98bb48edb1c2eee48cd3d222d482`. gradle plugin 8.10.1. aapt/apksigner not installed locally (no SDK on this poor PC) — versionName/Code proven from filename + HANDOVER + djmix8 transcript: `1.9.1-djmix8`, vc50.
4. **`/home/hfzhkn/Downloads/HANDOVER_MASTER.pdf` (328 KB)**: converted with `pdftotext` → `/tmp/opencode/bitchord-task1/HANDOVER_MASTER.txt` (565 lines), read fully. Covers djmix1–7 (vc40–49), ladder (gapless → loop → phrase-planner → djmix6 rungs), single-clock renderer notes, diagnostics (`u/g/l/v`, `AutomixScript` JSON), §5 backlog, §6 hard rules (licences, patent scalars-only, loop crown, no `setPlaybackSpeed` after handover, no audio-thread alloc, no Signalsmith header edits, same key + bump VC, cloud builds only, UI on hold), listening checklist, archive manifest. djmix7 completion: 1,449 tests passed; gated: native flanger off, isolator mid/high at unity, tempo policy a unbuilt, RTF unproven.
5. **`/home/hfzhkn/Downloads/BITCHORD_SESSION_EXPORT.zip` (2.0 MB)**: unzipped to `/tmp/opencode/bitchord-task1/unzipped/` — `BITCHORD_SESSION_CONVERSATION.md` (60,482 B), `BITCHORD_SESSION_FULL.md` (3,041,554 B), `BITCHORD_SESSION_RAW.jsonl` (5,862,479 B), `BITCHORD_HANDOVER.md` (72,907 B). Read headings + handover fully; FULL is the 3,213-line cloud-agent transcript through mid-djmix5. Key facts: upstream tag v1.9.1 commit `7f45c76` (merge `acf219a`), official vc39 package `com.music.bitchord`; fork `com.music.bitchord.loopfix`; Origin draft `hf-zh/tmp-1a194b77b9f0f8dd` branch main HEAD `b4716f0`.
6. **`/home/hfzhkn/Downloads/automix-research.md` (35 KB, 282 lines)**: read fully. TL;DR: Apple iOS 27 confirmed loops+reverb+delay (no flanger/stems/labels confirmed); Music Understanding framework (beats/bars/sections/phrases/key/LUFS/vocal-activity, unlabelled); BitChord already has Beat This final0 q8 + umxhq + Signalsmith + heydavidf/Orchard AGPL code; full stems not viable (HTDemucs ~2.2 GB); patent trap US 8,280,539 sliding-window. §3.2–3.3: analysis pipeline + 6-rung planner sketch (loop → phrase-beatmix 32/16/8/4 bars with cost `w1·vocalOverlap + w2·keyDist + …`, bass swap / vocal handoff / intro-outro extension, key-clash filter 4–8 bars, echo-out, 6–8 s equal-power, filter last resort). Copy for Task 2: ladder, cost function, section-via-lyrics-repetition + chroma self-similarity + per-bar energy/vocal-share (lend this to Beautiful-outro 8-bar logic), echo-out parameters.
7. **`/home/hfzhkn/Downloads/Apple Music AutoMix Technical Wiki.md` (21 KB, 284 lines)**: read fully. Selection model (not one effect), scope (catalog-only, no album/several-genre, no Hi-Res), iOS 26→27 evolution, pipeline (analyze→plan→render pitch-preserving stretch), open questions (analysis location, stretch algorithm/limits, planner rules), no-observed pitch-shift-to-fix-key, comparable systems. Use in Task 2 for selector behaviour, 2:1/1:2 octave folding + stretch caps, "Mixing" indicator, skip rules — do NOT copy unpublished Apple rules; treat as black-box evidence only.

**Source repo location.** BitChord canonical tree EXISTS on the cloud box: `/data/workspace/bitchord-dissect/djmix6-src` (djmix6 vc48) + `upstream-1.9.1` + `jadx-djmix6` + `djmix6.apk`. It is NOT a git checkout of the fork (HEAD `7f45c768 tv build fix` = upstream) — Task 2 must create a NEW working clone there (or fetch djmix7 snapshot from Drive part1 + apply djmix8 changes). No BitChord checkout exists on this poor local PC (verified by `find` for `build.gradle*` — only bkmasterplayer hits). Local `/home/hfzhkn/BitChord-djmix5.apk` / `djmix6.apk` exist (SHA-256 checked, `djmix2.apk` is 0 bytes).

**bkmasterplayer location.** Fully on disk BOTH places: local `/home/hfzhkn/bkmasterplayer` (git origin `https://github.com/thieggs/bkmasterplayer.git`, HEAD `554118b`, incl. `app/rust/src/engine/` `analysis.rs` 54,882 B / `automix.rs` 41,230 B / `mixer.rs` 65,994 B / `deck.rs`) AND `/data/home/bkmasterplayer` on bk-android (identical engine files; `app/assets/models/`: `beat_this_small.onnx` + `mel_spectrogram.onnx` — the small-model pair). MIT. Automix traits (from `automix.rs` grep): `max_tempo_change 0.08`, `preferred_bars 16 / min_bars 4`, `tempo_ramp_bars 16`, `bars` snap `[32,16,8,4,2,1]`, `offset_near` gate ±25 ms, `tempo_jump_plan` echo/cut on downbeat when BPM gap too large, outro/intro found via per-bar mid-band 200 Hz–2 kHz energy vs 75th-pct reference, key-clash → ≤8 bars + filter. This is reference ideal #1 (never abrupt: echo rings past file end).

**Test pair (Beautiful → Never Let Go).** No local audio files found for these tracks (search of Downloads for audio yielded unrelated). Expectation set: bkmasterplayer mixes 8 bars of Beautiful's break outro (relaxed, empty) into Never Let Go's intro (super-matching beat). In BitChord terms this needs: outro-break detection (low mid-band energy + low vocal share sustained ≥4 bars), phrase-aligned 8-bar overlap, ≤±8% stretch with ramp, bass handover, no hard cutoff at A's content-end, loudness-matched flat faders. Currently BitChord anchors A's exit at `content_end`/ranked mix-out (see §2.4) and B's entry at first sounding bar — so it will readily cut A's break mid-phrase and duck volume.

---

## 2. Bug-by-bug code diagnosis (djmix6-src, file:line)

### 2.1 Automation skipped ~half the time (and the djmix8 Bazzi "does nothing" failure)

Multiple independent gates, all reachable on a normal pop pair:

- `TransitionPlanner.kt:1567` `MIN_SMART_DURATION_SECONDS = 45.0` — any outgoing track < 45 s → `blocked()`. Shorts, ringtones, interludes silently skip smart mix.
- `TransitionPlanner.kt:1606` `analysisReadyForTrack` (`analysisReadyForTrack` at `1438`): `status != STATUS_READY` → `standardTransition("smart-analysis-fallback")`. `TrackAnalyzer.kt:243` single-thread `bitchord-smart-analysis` executor + `TrackAnalyzer.kt:254` `headExecutor` still serialize whole-track Pass-1 decode behind head analysis; slow network decode / lyric fetch timeout wins the race. djmix6 had 10 s analysis defer (`yieldAnalysis`), which also parks grids.
- `TransitionPolicy.kt:533` `assessTransitionTier`: BPM outside 40–220 → `PLAIN_CROSSFADE`; both confidences < 0.2 → `PLAIN_CROSSFADE`; either < 0.55 or `!stretchReachable` (rate outside 0.9–1.25 after octave fold) → `DJ_ASSISTED` (filter, not beatmatch). Very common on rap/drift tracks.
- `TransitionPlanner.kt:295` `isUnmixablePair`: tempo unreachable + (key clash OR both `vocalProbability ≥ VOCAL_CONFLICT_PROBABILITY`) → forced `EQUAL_POWER` at the real content end. This is the user-visible "lazy fade" half: it is deliberate code.
- `LoopMix.kt:458` loop validator: `quietestBar` (60th-pct reference) rejects quiet breaks; `Check.LYRICS`/`LYRICS_AND_MASK`/`MASK` (`LoopMix.kt:489`) — a sung-adlib loop fails on mask peak 0.22/mean 0.14; `MAX_SUNG_PEAK = 0.22` (`LoopMix.kt:70`). The research §2.1 notes GT truth: bkmasterplayer requires steady grid at both ends, which BitChord's `BeatTracker`/`GridRepair` also enforces.
- `LoopMixSession.kt:127` `build()` returns null on ANY region decode failure (`?: return null`) — the "decks took too long" abort from the ATEEZ convo. No retry/slide at build time (slide logic lives in planner, not session).
- djmix7+ override (ABSENT in this tree): `PhrasePlanner.kt`/`PhraseConfig.kt`/`PhraseGrid.kt`/`StructureAnalyzer.kt` with `PHRASE_PLANNER_ENABLED=true`, kill-switch + `PLAN_TIMEOUT_MS = 2000`, `ACT_TIMEOUT_MS = 40`, lock at T−25 s. djmix8 transcript: lock broken — a late Loop replaced a showing Beat Blend after countdown; loop unarmable → no fallback fired (infinite re-plan) → negative countdown to silence. This tree predates both fixes, so Task 2 MUST start from a djmix7/8 tree, not this one.

### 2.2 Stuttery / flam / gallop

Root RC-1 (confirmed in this tree): non-loop blends run on **two separate `ExoPlayer`s** (`CrossfadeController.kt` ~5,082 lines), not the single-clock native renderer. Influences: `ARM_STEP_MS = 40L` (`4989`) polling, `LOCK_START_MS = 300L` (`4744`), `holdPhase()` pulsed ±3% nudges (`4263`), `beginEase()` walking speed back AFTER handoff (`4408`, `3640`, `3650`) through `SonicAudioProcessor` WSOLA inside `setPlaybackSpeed` (`704`, `1567`, `1773`…), Opus seek jitter, `AudioTrack` start latency. djmix5 playbook (HANDOVER_MASTER §4): "999× speed burst"; djmix5 moved all rungs to the native single clock — but `CrossfadeController.kt` in djmix6-src STILL shows the dual-player path as the blend driver (unified renderer only carries loop/phrase mixes). Native: `loop_mix_renderer.cpp` (426 lines) per-sample gain ramp, `Biquad` RBJ filters, `TptLowpass`, `PingPong` echo, 8 ms seam crossfade; filters recomputed per 64-frame block (zipper risk on sweeps). TrackAnalyzer decode on `bitchord-smart-analysis` thread + `LoopMixSession.build` blocking decode collide with audio thread under Bluetooth. `rideFilters(incomingAt/outgoingAt)` (`~3790`) double-pass on mismatched lead — any DSP overshoot underruns.

### 2.3 Volume super low during mix

Equal-power math is correct (`MixGain.kt:14` `sin/cos`), but the staging pulls level down:

- `CrossfadeController.kt:1941` `player.volume = rise; out.volume = fall; onBlendHeadroom(headroomFor(...))`. `headroomFor` (`4546`) `1/sqrt(in+out)` dips to 0.707 (−3.01 dB) at midpoint by design (comment `~203`: "cost ≤1.5 dB" — that figure is wrong: `1/sqrt(1.41)=0.84` only if both faders at 1.0; with equal-power `sin+cos=1.41` peak the trim hits 0.707). Perceived dip is larger because loudness-mismatched pairs don't power-sum flat.
- `loudnessGain(in)/loudnessGain(out)` ratio (`2908`) applied to incoming deck: a −8 LUFS mismatch pushes B ~−8 dB before the fader even moves.
- `LoopMixSession.build` regional match `RegionalLoudness.matchGain` clamped to `[0.4467, 1.7783]` (= −7…+5 dB) — asymmetrical, favors cutting B.
- Filter rides (`DJ_FILTER` sweep/HP→1.8 kHz, `rideFilterSweep`) + echo send all attenuate the dry; brickwall-safe but subjectively "low".
- Two players × `player.volume` through platform mixer + BT AGC/Bluetooth packet loss accentuates it. bkmasterplayer avoids this by rendering one mixed sum at −1 dBTP in `mixer.rs` before output.

### 2.4 Abrupt ending of song 1 despite beat-match

- `mixOutCandidatesOf` (`TransitionPolicy.kt`): explicit comment — vocal boundaries deliberately NOT promoted to exit anchors; candidates = `energy_cliff`, `outro_start`, `content_end` (0.75). If analyzer's `outroStartTime`/`mixOutTime` are missing (common on streams), exit defaults to `contentEnd` = file end. `transitionEnd = mixAnchor = length` → the fade rides INTO EOF; `HANDOFF_GUARD_MS = 400L` (`4970`) only hands off 400 ms before end; `outRemaining` cutoff (`1926`) truncates at file end → hard stop.
- `maximumOverlap = min(…, mixEnd * 0.4, nextLength * 0.4)` (`TransitionPlanner.kt ~1820`) can push `transitionStart` to <60% of track on short overlaps, leaving no outro room.
- No break/outro-phrase detector in this tree (phrase planner is djmix7+). Beautiful's empty break outro scores as "content_end" → cut. `ECHO_RIDE` (echo past EOF) exists but only when `traits.tempoFar==true`; beat-matched pairs skip it.
- `TransitionStyle.EQUAL_POWER` plain crossfade at `contentEnd` (`smart-plain-crossfade`) guarantees abrupt on low-confidence pairs.

### 2.5 Tempo / pitch / speed matching

- Caps: planner window `MIN_INCOMING_RATE=0.9 .. MAX_INCOMING_RATE=1.25` (`TransitionPolicy.kt:69`), audible clamp `AUDIBLE 0.94–1.09` (`83`) + buildup `+0.06` (`91`). So >12.5% gaps never beatmatch (by rule, not by stretch quality).
- Non-loop stretch = ExoPlayer WSOLA `setPlaybackSpeed` (transient smearing, phasiness — Apple wiki §6.5), stepped on 40 ms ticks + pulse correction. Loop/phrase mixes = `SignalsmithStretch` knots in native (`knotTimes/knotPositions`), B at 1.000× before handover (djmix6+ intent, "policy b"). In THIS tree policy-b ramp is partial; policy-a (smootherstep) unbuilt. No tempo ramp across the blend for classic DJ_BLEND except `beginEase` after handoff → lurch.
- Beat grid: `TrackAnalyzer.kt:1312` `seamBeats` midpoints WITHOUT phase reconciliation; `GridRepair.kt:34` midpoint concat + `snapToOnsets(±35 ms)` (`126`); `GridRepair` is the bkmasterplayer port (multi-region fit + join_reconcile). `structure()` outro/intro via mid-band 200 Hz–2 kHz vs 75th-pct (bk `analysis.rs`) is NOT ported here — BitChord uses `mixOutCandidates`/energy alone.
- Octave folding exists (`alignTempoOctave`, `[0.5,1,2]`), but half/double grids with weak confidence often still demote to `DJ_ASSISTED` (confidence 0.55).

### 2.6 UI for automix (poor)

`AutomixStatus.kt`: `headline` (style+countdown+tempo/key), `detail` (diagnostics, expanded only), `progress` (negative = none). Wired in `CrossfadeController.kt:3423` `status()`, shown in `AndroidPlayerHost.kt` / `MainActivity.kt` (mix-view popup from `0.24` context waveform). No persistent transition inspector (the "messy inspector drawer" from the failed Automix-player experiment is the cautionary reference — BitChord keeps it diplomatic/single-line). The djmix8 bug persisted BECAUSE the countdown UI showed a plan the renderer never armed, with no watchdog/log line at the time.

---

## 3. bk-android readiness (PROVEN)

Non-interactive pattern that works: **`instabox exec bk-android -- "<cmd>"`** (no sudo needed, exit 0). Do NOT use `instabox ssh bk-android` interactively from this subagent (it hung on TTY). Also `instabox files bk-android [PATH]`, `instabox resize bk-android --disk <GiB>` (live grow).

Output snippet (actual):
```
Linux instabox-bk-android 6.18.60-instabox-android #1 SMP PREEMPT_DYNAMIC 2026-10-30 x86_64
4 vCPU, Mem 7.8Gi (1.2Gi used), Swap 4.0Gi, / 24G (15G used, 7.4G free), /tmp 2.0G
openjdk 21.0.12.1, ANDROID_HOME=/opt/android-sdk
platforms: android-34/35/36; build-tools 34.0.0/35.0.0/36.0.0; ndk 28.2.13676358; Gradle 8.7
/data/home (user hfzhkn, sudo ok); persistent work dir /data/workspace
WARNING: /data 92% full (1.6 GiB free) — free space or resize BEFORE long builds.
```

`/data/workspace/bitchord-dissect/` already holds `upstream-1.9.1/`, `djmix6-src/`, `djmix6.apk`, `jadx-djmix6/`, `all_patches.json`. Clean work root for Task 2: **create `/data/workspace/bitchord-task2/`** (new clone). bk-android also holds `/data/home/bkmasterplayer` (same tree) + `/data/home/BKmasterplayer-1.8.1-ytm-debug.apk`. Heavy work stays there; this local PC does no Gradle builds.

Build command (target, from HANDOVER): `./gradlew :shared:allTests :app:testProdDebugUnitTest :app:assembleRelease` (flavor: prod release; there is no `:app:testDebugUnitTest`). Verify `apksigner verify --print-certs` == `20c66ad6…` for in-place updates, or new cert + new package id if rotating.

---

## 4. Implementation plan (Task 2 scope — implementable by next worker)

### 4.1 Restore the tree first (P0)

1. On bk-android: `mkdir -p /data/workspace/bitchord-task2; git clone https://github.com/kushagrasinghx/BitChord.git` at tag `v1.9.1` (`7f45c76`) → baseline.
2. Download Drive part1 (`BITCHORD_FULL_HANDOVER_2026-11-11_part1_docs-source.zip`, id `1f2NdbMqNNRbtxpuTLIpR3DUd6iHq2V_H`) via `gdown --fuzzy` on bk-android; extract its `source/BitChord-loopfix-b4716f0/` (djmix7, 940 files) as overlay → commit `djmix7-base`.
3. Apply djmix8 delta from `01-…djmix8.md` description + APK `eeae9319` (if obtainable from Drive part2 or Cursor transcript): countdown lock + 1.2 s watchdog (echo → crossfade), stop-at-0, abort logging (`AutomixScript`/`BitChordPhrase`/`AutomixCrash`). Verify with the 27-case harness (baseline 16/27).
4. Restore ONNX weights byte-identical from upstream `app/src/main/assets/` (`beat_this_final0_q8.onnx` 23,138,816 B; `vocals_umxhq_int8.onnx` 9,054,206 B).
5. Signing: keep OLD key (`BitChord-keystore.zip` → `/data/workspace/bitchord-task2/key/`; `keystore.properties` untracked, never commit). Next release: `versionName 1.9.1-djmix9`, `versionCode 51`, `arm64-v8a` only (or add `x86_64` for BK box testing — dual-ABI if cheap). Document cert SHA-256.

### 4.2 Audio fixes (grounded in research + Apple wiki)

- **Never-abrupt guarantee (Beautiful fix):** add break-outro detector: per-bar mid-band (200 Hz–2 kHz) energy vs 75th-pct + vocal share ≤0.25 sustained ≥4 bars → treat as `outro_start` candidate even when `mixOutTime` absent; anchor mix end at breaker downbeat −1 bar; always start the overlap ≥8 bars BEFORE `contentEnd` (never ride to EOF); echo tail rings 6–8 beats past file end (bk `tempo_jump_plan` pattern) whenever the natural outro is < 4 bars clean. Gate: A-exit from A's structure only, B-entry from B's only, cross-track by scalars (patent hygiene).
- **Volume-flat overlaps:** unify all rungs onto the single-clock native renderer (kill dual-`ExoPlayer` blends — finish djmix5 RC-1); replace `headroomFor` 0.707 dip with per-pair loudness compensation: pre-measure exit/entry window LUFS (R128) + true-peak, apply static deck gains so exit+entry sums to ~0 dB relative, keep equal-power sin/cos, reserve the limiter (−1.0 dBTP, 1.5 ms lookahead, already in `loop_mix_renderer.cpp:419`) for peaks only. No post-fader volume ganging. Target: mid-blend total ≥ −1 dB vs ends.
- **Stutter elimination:** replace 40 ms polling with audio-clock-driven `progress` (native `t0` knots); remove pulsed `holdPhase` nudges for native mixes (residual phase measured once at mix point, gate ≤25 ms — bk `offset_near`); move `LoopMixSession.build` decode fully off audio thread (pre-arm at PLAN_LEAD 60 s, replan 5 s, lock 25 s); `TrackAnalyzer` two-executor split (head high-prio for next+next+1, whole-track background) — djmix6 partially has it (`243`/`254`); complete the split + lyric-timeout bypass (proceed on mask after 1.5 s).
- **Tempo matching:** native `SignalsmithStretch` knots for ALL blends; continuous ramp `bpmOut→bpmIn` over first 75% of overlap (bk ramp 16 bars max), B ends at 1.000×; window ±8% (vocal) / ±6% conservative, folding `[0.5,1,2]`; no `setPlaybackSpeed` after handover, ever. If gap >8%: bar-aligned echo-out/cut (both at own tempo), never camo-filter.
- **Anti-skip ladder (selector, not one effect):** gapless (same-album sequential, Apple-style skip) → loop mix (crown, unchanged gates) → phrase-beatmix 32/16/8/4 bars (default 16, cap 40 s, key clash ≤8 bars + filter — bk `automix.rs` numbers) with cost `w1·vocalOverlap + w2·camelotDist + w3·|speed−1| + w4·discarded + …` → echo-out (tempo far / vocal heavy) → 6–8 s equal-power → short bass-swap filter reserved for key clash. "Analysis not ready" → bar-aligned echo/cut, NEVER a lazy 12 s fade (lesson from Automix-player experiment). Every rung logs abandon reason.

### 4.3 files to change (Task 2)

Primary (planner): `shared/…/TransitionPlanner.kt`, `TransitionPolicy.kt`, `LoopMix.kt`, `TrackAnalysis.kt`, `DjMix.kt`/`DjColour.kt`, `GridRepair.kt` (already has bk port — harden seam join), new `StructureAnalyzer.kt`/`PhrasePlanner.kt` (djmix7 assets — retune outro-break scoring), `MixGain.kt` (staging), `RegionalLoudness.kt` (+LUFS pre-measure). Controller/session: `app/…/CrossfadeController.kt` (single-clock unify + watchdog), `loopmix/LoopMixSession.kt` (pre-arm + no-null rebuild), `loopmix/LoopMixRenderer.kt`/`LoopMixPort.kt`. Native: `native/loopmix/loop_mix_renderer.{cpp,h}`, `app/src/main/cpp/jni/loopmix_jni.cpp` (gain envelopes from LUFS, echo past EOF). Analyzer: `app/…/smart/TrackAnalyzer.kt`, `shared/…/BeatTracker.kt`/`VocalTracker.kt`. UI: `AutomixStatus.kt` + `AndroidPlayerHost.kt` (hold countdown semantics; keep layout per UI-on-hold rule).

### 4.4 Build / test / deliver on bk-android

`./gradlew :shared:allTests :app:testProdDebugUnitTest :app:assembleRelease` → `apksigner verify --print-certs` → `sha256sum` → copy APK + `keystore.properties` (separate artifact, never in git) → Drive link. Tests: planner unit (loop accept/reject incl. 1-bar outro → 4-bar intro 128→163 half-fold fixture from `04-…`), 27-case harness completion 27/27, offline render of CC tracks through native renderer (alignment <2 ms, −1 dBTP, no gaps), Beautiful→Never Let Go offline render (if audio obtainable) + listing checklist. Report honesty: "built and unit/offline-tested, unheard" until user listens.

### 4.5 Risks / unknowns

- djmix8 exact tree unavailable — reconstruction from djmix7 snapshot + transcript carries drift risk; mitigate by diffing `jadx-djmix6`/`all_patches.json` and decompiling the oldkey APK DEX for the watchdog patch.
- `/data` 92% full — must clean before build (`du -sh /data/workspace/bitchord-dissect/jadx*`).
- Stems (htdemucs) deliberately excluded (2.2 GB) — windowed umxhq vocal-out only. Flanger/tempo-policy-a/isolator-mid-high remain partial (HANDOVER_MASTER §5) — do not block djmix9 on them.
- "Never Let Go" intro tempo match is inferred from user+bkmasterplayer claim, not measured — verify on device logs before tuning thresholds priming around it.

---

## 5. Recommended Task 2 scope (one build)

1. Tree restore (4.1) + djmix9 version bump + old-key sign.
2. Never-abrupt break-outro anchor + past-EOF echo + LUFS-flat overlaps + single-clock unify (4.2 top three).
3. Anti-skip selector ladder + full abort logging + countdown lock/watchdog.
4. Harness + unit + offline renders; deliver APK + SHA-256 + cert + listening checklist (Beautiful→Never Let Go first).
5. UI: no layout changes; fix countdown/progress semantics only.

Acceptance checks met: 7/7 inputs accounted, repo + bkmasterplayer located with remote/HEAD, APK metadata (SHA-256, cert `20c66ad6…`, 87,582,011 B, arm64, `eeae9319`), per-bug file:line evidence, bk-android proven (`exec` snippet + SDK/Java/Gradle), this plan file at `/home/hfzhkn/bitchord-automix-DIAGNOSIS.md` (also copy to `/data/workspace/bitchord-task2/` in Task 2).
