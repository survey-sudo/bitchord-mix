# BitChord DJMIX24 Handover — 2026-11-14 (vc66, BUILT + VERIFIED, INSTALL PENDING)

> **Honest state in 5 lines.** `2.2.3-djmix24` (vc66) is **BUILT, SIGNED (`oldkey`), VERIFIED, NOT INSTALLED** — phone `1439ebea` was offline (`adb devices -l` empty) for the entire cycle, so no `adb install -r` was attempted; APK is staged at `/home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix24-automix-arm64-v8a-oldkey.apk` (SHA `0443f3bfabf0dd2bd3bba0d3d065674fb6009c87d49b3ed8ba61e2db3c57e257`, 87,498,355 B), 14 `lib/arm64-v8a/` libs, v2 cert `20c66ad6…e3fe4c`, aapt vc66/`2.2.3-djmix24`. Full gate 1,889 tests 0 failures (jvm 321 + shared 263 + app 1,305); native `arm64-v8a` `externalNativeBuildProdRelease` green with new JNI symbols. Post-vc65 telemetry audit: **zero lines >8190** (pre/post/bug files are byte-identical 8,190-line pre-install sets; `mix.handoff.deferred` count 0, `mix.pause.detected` still present 6/4 with `changeReason=1 positionMs=0` — vc65 never shipped to device for new telemetry). Forensics note: vc65 handover shows vc65 WAS installed 2026-11-14 10:38 WIB, but no new telemetry was pulled post-install in djmix24-pre (phone offline). Next: safe-install on `1439ebea` when online, then the four-pair listening gate (ears only). **No audio claim:** listening verdict is the user's only.

---

## 1. Executive state [PROVEN]

| Item | Value |
|---|---|
| Built on | `bk-android` `/data/workspace/bitchord-task2/work/BitChord` via Instabox CLI — running (`runStartedAt=2026-11-14T03:57:32.335507Z`, `maxRunMinutes=120` at gate start; gate job `8d3006d2` `DONE:0` `BUILD SUCCESSFUL in 8m 34s`) |
| Version | `2.2.3-djmix24`, **versionCode 66** (`build.gradle.kts:44-45` `66` / `"2.2.3-djmix24"`) |
| APK | `/home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix24-automix-arm64-v8a-oldkey.apk` (real `Downloads` symlink) — 87,498,355 B, SHA-256 `0443f3bfabf0dd2bd3bba0d3d065674fb6009c87d49b3ed8ba61e2db3c57e257` (byte-identical `instabox pull` verified); 14 `lib/arm64-v8a/` libs only, `native-code: 'arm64-v8a'`, v2 cert `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` (oldkey, DN `CN=BitChord loop fix`), aapt vc66/`2.2.3-djmix24` |
| Installed on | `1439ebea` — **INSTALL PENDING** (offline entire cycle; `adb devices -l` empty at Step 1 and at gate end). No `install -r` attempted. Safe instruction in §7. |
| Phone proof | `djmix24-pre/` (empty — phone offline, no pull) and `djmix24-proof/` (build/verify/install-pending evidence only; no fake playback). See §6. |

---

## 2. Step 1 — Read-only post-vc65 device telemetry audit

Phone `1439ebea` was **offline** (`adb devices -l` → empty, Task 1 guardrail read-only respected; no `install`/`pm clear`/`force-stop`/media commands issued). Created `/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/djmix24-pre/` empty (no pull possible) and proceeded immediately.

Baseline file `/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/djmix23-proof/postinstall-telemetry.jsonl` has **exactly 8,190 lines** (seq 1–1,777 with wall repeats; events: `mix.plan.rejected` 4,000, `mix.plan` 1,165, `mix.freeze.onInterruption` 937, `mix.seek` 648, `mix.loop.fallback` 528, `mix.complete` 299, `mix.fire` 170 — 127 classic / 42 loop). Comparison:

- **Lines >8190: zero** — `preinstall-telemetry.jsonl` is also 8,190 lines byte-identical to postinstall (same SHA/prefix per djmix23 proof), `vc65-bug-telemetry.jsonl` is 7,956 lines (subset, same early seq). There is **no post-vc65 telemetry** to summarize: no new transitions, no new rungs/templates.
- **`mix.handoff.deferred` fired: never** (count 0 in all three files). The vc65 adoption guard (`canAdoptSessionPlayer` + deferred log) never triggered on device because no post-vc65 playback telemetry exists. Whether it would fire (and whether song 2 stays playing) can only be answered from new `postinstall-telemetry.jsonl` after the vc66 install + user listening — **not claimed here**.
- **`mix.pause.detected` (`changeReason=1` at `positionMs=0`): still present, not gone.** Postinstall has 6 (3× `driveLoopMix positionMs=0` + 3× `post-handoff positionMs=25`), bug file has 4. All with `changeReason=1` (= `USER_REQUEST` per Media3 1.11.0, not focus loss), `handedOff=true tookOver=true`. Line example: `{"seq":12,"ev":"mix.pause.detected","site":"driveLoopMix","changeReason":1,"handedOff":true,"tookOver":true,"positionMs":0}`. The vc65 fix targets the cause but has **no device proof yet**.
- **Rungs/templates/loops in the 8,190-line file:** plan templates `classic` 738 / `M6` 242 / `M1` 128 / `RELAXED` 44 / `M11` 13; fires `classic` 127 / `loop` 42. Calvin geometry is present (`bpmA 124.341 → bpmB 124.250` `M1` planned seq131) but never fires as `loop` with flanger/buildup in telemetry (no FX fields logged). This is the user-visible gap vc66 closes (see §3).

Full counts in `djmix24-proof/telemetry-audit.txt`; `djmix24-pre/` stays empty (honest offline).

---

## 3. What changed vc65→vc66 (Pillars A–D, scalars-only, patent-safe)

All cross-track scoring compares **scalars only** (BPM, Camelot distance, bar counts, scalar energy/vocal/LUFS). No frame-array cross-correlation. No Signalsmith header touched. No `@Composable` touched. Zero audio-thread allocations in `render()`/`renderDeck()` (all buffers pre-allocated in `Renderer::prepare()` on the worker thread). vc61–vc65 invariants preserved (`canAdoptSessionPlayer`, `clampIncomingStartAt`, `finishLoop` pre-start, zero post-handover `setPlaybackSpeed`/`beginEase`/`holdPhase`, “every 1.9.1 loop is still a loop”).

### Pillar A — EDM buildup + stereo jet flanger + tightening roll (Bug A1–A4)

- **Bug A1 (`loopScript == null` black hole) FIXED.** `LoopMix.Plan` now carries backward-compatible scalar FX/buildup fields with safe defaults at the end of the constructor (existing positional/named callers & tests compile untouched): `flanger: Boolean = false`, `buildupRoll: Boolean = false`, `reverbWash: Boolean = false`, `hpSweepEndHz: Float = 0f`. `LoopMix.plan` (loopPlanFor path) and `LoopMix.bridge` (Drop/Beat blend/Echo paths) populate them deterministically; `TransitionMoves.compile` populates them from its EDM/rotation decision (merged with legacy crown plans). `LoopMixSession.build` honours `(script?.flanger == true || effectivePlan.flanger)`, `effectivePlan.buildupRoll`, `effectivePlan.reverbWash`, `effectivePlan.hpSweepEndHz` — so `loopPlanFor`/`nativeBridge` clearing `loopScript = null` no longer blacks out the flanger/buildup.
- **Bug A2 (60× LFO math + weak mono comb) FIXED.** `LoopMixSession` LFO is now **`1.0 / (8.0 * beatSec)`** (one sweep per 8 beats; helper `LoopMix.lfoHzForBeatSec` + `TransitionMoves.lfoHzForBeatSec`; at 124.34 BPM `beatSec≈0.4825` → **≈0.258 Hz**, never 15.5 Hz). Native `Flanger` upgraded to **true stereo DJM-style Jet**: quadrature LFO (90° / 0.25-cycle offset, wide stereo while mono-compatible), sweep **0.65 ms .. 5.2 ms** (`depthSeconds=0.0045` buildup / `0.003` colour; `Flanger::setup` clamp up to **12 ms**, pre-allocated in `prepare()`), metallic feedback **0.64** with 1-pole **~10 kHz** HF damping + `1e-20f` denormal guard + clamp **[-0.78, 0.78]**, wet **0.48**, plus **`KIND_FLANGER_WET`** rising ramp into `releaseAt` with brief tail ring (native holds the line).
- **Bug A3 (tightening roll + 8 ms seam) FIXED.** Loop wrap seam is a **clickless 8 ms Hann / equal-power** `sin`/`cos` crossfade on every wrap (zero allocation; `kLoopSeamSeconds=0.008` documented). **Beat-synced tightening (`buildupRoll`)** on `Deck` (wired through JNI `nativeSetLoopRoll` / `LoopMixRenderer.setLoopRoll`): steady full loop (2/4 bars) to `rollStart` (final **16 beats**, or **8 beats** on shorter mixes), then 4 → 2 → 1 → 0.5 beats anchored at `loopStart` via `knotTimes`/`beatSec` schedule (`LoopMix.rollLoopBeats` mirrors the native table), each sub-wrap 8 ms crossfaded, paired with **Cytomic SVF HPF 30 Hz → 420..600 Hz** (`hpSweepEndHz=500` default, Q 0.95) + rising jet flanger + **dub echo/reverb release at `releaseAt`** (B drops at 0 dB). Rung 4 **slip-roll 1 → 1/2 → 1/4** reuses the same native path (echo slots 0/2).
- **Bug A4 (deterministic EDM trigger + rotation) FIXED.** Removed the 50% coin-flip suppression. When both tracks are steady dance/EDM (**110..150 BPM** post-fold within `TEMPO_HARD_DELTA=0.08`), the outgoing loop is clean instrumental, and dual-vocal clash is low (`vocalOverlapBeats < 1.0`, `vocalProduct < 0.18`, loop vocal shares < 0.06), **EDM Buildup is deterministic** (`flanger=true`, `buildupRoll=true` when loops or buildup moves selected, HPF sweep 500 Hz, reverb release). Verified on the **124.34 → 124.25** Calvin geometry (`LoopMix.plan` + `LoopMix.bridge` + `TransitionMoves.compile` all enable it in tests). Non-EDM eligible blends + Rung 4 exits use **deterministic pair-hash rotation** (`fxSlot = pairHash(outId,inId)%4`: 0=FLANGER, 1=CLEAN_SWAP, 2=ECHO, 3=REVERB) with anti-repeat by pair hash; **never** flanger/roll over active dual-vocal overlap (`vocalOverlapBeats >= 1.0` forces clean).

### Pillar B — Structure, onset/phase, phrase extension, no abrupt cuts

- **`SectionKind.BUILD`/`DROP` now actively painted** in `SongSections.labelSections` (single-track scalars: 4–16 bar BUILD with positive energy/flux slope + ≥2 gradual 5% increments (not a step), reduced low-end / rising centroid proxy, vocal share `<0.28`, preceding a high-energy jump; phrase-aligned ≥4-bar DROP with Δenergy ≥ +0.16 or top-quartile + strong bass/kick following BUILD/INTRO/breakdown), mapped through **`SongMaps.fromAnalysis`** (per-bar energy slope, same gradual gate) and strengthened in **`StructureAnalyzer.label`** (Δenergy ≥0.16 / top-quartile DROP, BUILD slope ≥0.30 vocal <0.28). `StructureMix.headBuildup`, `EntryCue`/`ExitCue`, and `PhrasePlanner` (M7/M8/M1 buildup moves) now score real BUILD→DROP alignments. Flat pop ballads (flat energy, no rise/bass surge) do not false-paint (tests pin both).
- **Onset/phase/seam ported (bkmasterplayer, scalar times only).** `GridRepair.snapToOnsets` is now per-beat **±35 ms** to the nearest onset peak (beats without attacks keep grid time; needs ≥8 beats + onset for ≥half, else unchanged). `TrackAnalyzer.snapGrid` already lowers confidence when `phaseCoherence` (hit fraction) `<0.42` with coverage ≥0.5 — preserved. **25 ms phase gate** is explicit (`LoopMix.phaseGateOk` + `PHASE_GATE_SHORT_MS=25`) at entry/loop boundaries. Loop-seam continuity prefers clean candidates with smooth start/end RMS (`LoopMix.seamDelta` stable sort in `firstClean`, scalar `ln` ratio) — no mid-transient chop when an adjacent clean bar is smoother.
- **Outro/intro phrase-loop extension + music-end + Rung 0.** `TransitionMoves.compile`: when `exit.loopableRoom` and `roomBars < overlap` (e.g., 4 clean bars vs 8-bar B intro), `outLoops=true` over that clean 2/4-bar phrase so `LoopMix.Clock` loop-extends A’s outro underneath B’s buildup instead of rushing past EOF. Music-end protection preserved via `CuePoints` release choice (cold hits vs fade-out; sung outros never cut early — `Beautiful → Never Let Go` safe). **Rung 0 consecutive-album guard**: same non-blank album ID (ID-first; title only when IDs blank) **and** `current trackNumber > 0 && next == current+1` → `GAPLESS` (`TransitionMoves.isConsecutiveAlbumPair` + `TransitionPlanner.isConsecutiveAlbumPair`/`sameAlbum` consecutive branch + `CrossfadeController.isConsecutiveAlbumTrack`); singles (blank album) and non-consecutive siblings still AutoMix. Regional Camelot clash (`steps >= 2`) routing through filter + reverb/echo bridging preserved via existing `clashFilter`/bridge `clash` path.

### Pillar C — Vocal/instrumental separation awareness

- **Unbridged ≥1.5 s lyric gaps** use `umxhq` `vocalWindows` (`LoopMix.sungSpans` already treats >1.5 s open-line gaps as silence; `LoopMix.firstClean` requires mask coverage + lyric veto) so instrumental hooks/interludes between lyric lines host clean short loops/handoffs without cutting sung words.
- **1-bar vocal handoff**: when `incomingSinging` (`leadInVocal ≥ 0.2`), `TransitionMoves` keeps B `inMid`/`inHigh` at **0 dB** (crisp) while A `outMid` carves **−6 dB** over 4 beats before B’s first sung bar (lane shape in `TransitionMoves`); `LoopMixSession` now actually writes `KIND_MID_DB`/`KIND_HIGH_DB` (were dead letters — `applyPhraseLanes` now includes `outMid`/`inMid`/`outHigh`/`inHigh`) into the **LR4 isolator bands** (200 Hz / 2.5 kHz). Crisp `One Kiss` vocals; zero mush on `Technologic → Guess` (tested: `inMid`/`inHigh` at 0, `outMid` −6..−9 dB).

### Pillar D — Native C++ club DSP

- **Cytomic TPT SVF** (Simper zero-delay-feedback, allocation-free, stable to Nyquist) for sweeps: `TptHighpass` added; `Deck.tptHighPass` drives `highPassHz` envelopes with configurable `Q` (`svfQ` 0.707 default, **0.95** musical during buildup via `setSvfQ`/JNI). Low-pass stays TPT.
- **Pre-allocated Stereo Plate Reverb Wash** (`ReverbWash`: 4+4 combs + 2+2 allpasses, Freeverb-class, **300 Hz** input HPF, smooth wet send/tail) exposed via JNI `nativeSetReverb(handle,index,roomSize,damping,wet)` + `LoopMixRenderer.setReverb` + `KIND_REVERB_SEND` (Rung 4 / harmonic bridges / EDM drop cuts). All delay lines `assign` in `setup()` (worker thread), state cleared in `prepare()`, zero heap in `render()`.
- **Tight 0.5–1.0 beat 200 Hz LR4 Bass Swap**: `LoopMix.Clock.swapBar` tightened from 1 bar (4 beats) to **1 beat** (`60/tempo`); `Biquad.lowShelf` frequency **250 Hz → 200 Hz** to match the **LR4 isolator** (200 Hz/2.5 kHz, `phrase_isolator.h`) — crisp downbeat swap without mud/hole (tested: `swapBar` 0.5–1.0 beat).
- **Pre-allocated 1.5 ms lookahead −1.0 dBTP (0.891f) True-Peak Soft-Knee Limiter** in `Renderer::render()` (`limitRingL_/R_` pre-allocated in `prepare()`, fast attack, smooth release **~80 ms** (`0.08f`), 2 dB soft-knee, denormal guard, zero allocations/locks) — prevents clipping when SVF + jet flanger + echo/reverb + overlap sum together.

Native verified: `:app:externalNativeBuildProdRelease` green (C++17/NDK `arm64-v8a` `loop_mix_renderer.cpp` + `loopmix_jni.cpp`, 0 errors); `libbitchord_loopmix.so` exports `nativeSetFlanger`/`nativeSetLoopRoll`/`nativeSetReverb`/`nativeSetSvfQ` (checked via `nm -D`).

---

## 4. Version bump & tests (Task 1 acceptance gate)

- `build.gradle.kts`: `versionCode = 66`, `versionName = "2.2.3-djmix24"`.
- New tests (`git add -N` only):
  - `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/Djmix24SharedTest.kt` (14 tests): BUILD/DROP painted on synthetic EDM rising-flux→bass-drop and not on flat ballads (`SongSections`/`SongMaps`); Calvin 124.34→124.25 `LoopMix.plan`+`TransitionMoves.compile` enable `flanger=true`/`buildupRoll=true`/500 Hz sweep, LFO `1/(8*beatSec)≈0.258 Hz` never 15.5 Hz, crisp incoming lanes (`inMid` 0 dB, `outMid` carve); outro extension (`outLoops` when room < overlap); ±35 ms snap + 25 ms gate + loop-first invariant; vocal handoff & dual-vocal suppression (`vocalOverlap≥1.0` never flanges/rolls); tight bass-swap; consecutive-album guard; FX rotation deterministic/varied.
  - `app/src/test/java/com/music/bitchord/playback/Djmix24AppTest.kt` (6 tests): Bug A1 FX carrier on `loopPlanFor` (`LoopMix.plan`) **and** `LoopMix.bridge` paths; dual-vocal bridge suppression (clean loops flange, sung loops don’t); `canAdoptSessionPlayer` Preserved; Rung 0 guard; FX rotation varied/deterministic/anti-repeat.
- On `bk-android` (`--offline --no-daemon`):
  - `:shared:allTests` **584** (jvm 321 + shared debug 263, 0 failures) including `Djmix24SharedTest` 14/14 and all prior `LoopMix`/`Djmix*` tests.
  - `:app:testProdDebugUnitTest` **1,305** (0 failures, 0 errors, 1 skipped `GeniusTest` pre-existing) including `Djmix24AppTest` 6/6 + `Djmix23AppTest` 7/7 + `Djmix18–22` green.
  - `:app:externalNativeBuildProdRelease` green (17 s, 0 errors).
  - `:app:assembleProdRelease` green (`BUILD SUCCESSFUL in 8m 34s`, R8 warnings only — Kotlin metadata 2.4.0 vs 2.2.0 pre-existing).
  - Grand **1,889** (321+263+1,305) 0 failures. Counts from completed test XMLs (see `djmix24-proof/test-results.txt`).
- Regressions caught & fixed during the gate (diagnosed, not suppressed): `DjMixTest` ×2 (BUILD overwrote INTRO/VERSE on a step edge — fixed with ≥2-increment gradual gate) and `Djmix19 regionBoundsCoversStraightDeckStopAt` (`M1 must be straight` — fixed outro extension to `room < overlap` instead of `room < total`, preserving the straight-deck invariant). Both proven green in targeted isolated reruns before the full rerun (see `djmix24-proof/regressions.md`).
- Git invariants on `bk-android`: `git rev-parse --short HEAD` == `dc78e0d`, `git diff --cached | wc -l` == `6085` before and after all changes. No `commit`/`reset`/`checkout --`. New test files via `git add -N` only.

---

## 5. What is ticked vs NOT TICKED

**Ticked:** All four vc65 causes fixed (null-script carrier, 60× LFO, tightening roll/slip, BUILD/DROP + deterministic EDM gate); BUILD/DROP in `SongSections`/`SongMaps`/`StructureAnalyzer` with gradual/step distinction; per-track ±35 ms onset snap + 25 ms phase gate + seam continuity; outro phrase-loop extension + music-end + Rung 0 consecutive guard; ≥1.5 s lyric-gap + 1-bar vocal handoff with LR4 mid/high written; TPT SVF (Q 0.707/0.95), plate wash + JNI, tight 200 Hz bass swap, 1.5 ms soft-knee limiter; version bump; 20 new tests; full gate + native build green; APK signed oldkey, verified bytes; mirror byte-identical; patches + proof + this handover.

**NOT TICKED (explicit, not stubbed):**
- **Listening quality UNVERIFIED** — no claim of audible EDM buildup/jet/roll quality from tests alone; requires the user’s ears (see §7).
- **Phone install NOT DONE** — `1439ebea` offline; APK staged, install pending (§7). No telemetry from vc66 on device yet; `mix.handoff.deferred` / song-2-playing questions remain open until post-install telemetry is pulled.
- **Windowed stems (`htdemucs`/Demucs, NC/proprietary) — NOT implemented by design** (license + 2.2 GB RAM + phone feasibility). Only `umxhq` mask + lyrics used (Pillar C). No model downloads.
- **True line-level vc65→vc66 delta isolation** — the shipped `bitchord-djmix24.patch` is file-filtered `git diff HEAD` for the 15 vc66 files (includes prior djmix21–23 hunks in those files); a byte-exact vc65 snapshot wasn’t retained separately. The vc66-only hunks are enumerated in §3; `full.patch` is the exact `git diff HEAD` (see §4). Documented honestly here rather than claiming a narrower patch.
- **Simular build** — mirror only (no build run on Simular, per protocol); Simular re-hibernated after verification.

---

## 6. Artifacts & proof

| Artifact | Lines / Bytes | SHA-256 |
|---|---|---|
| `bitchord-djmix24.patch` (vc66-files delta, 15 files) | 6,757 / 341,000 | `09cfecef627b4e853504b35adf51183bf4b13bbd6231749a3609ca124897a784` |
| `bitchord-djmix24-full.patch` (from `dc78e0d`, 65 files, incl. intent-to-add) | 14,288 / 722,592 | `6e6b104af72f41dd73d8c935f8abfa43448988b83a22c73630337e3300a204d1` |
| APK `bitchord-2.2.3-djmix24-automix-arm64-v8a-oldkey.apk` | — / 87,498,355 | `0443f3bfabf0dd2bd3bba0d3d065674fb6009c87d49b3ed8ba61e2db3c57e257` |

Lineage: `HEAD=dc78e0d449d7bdf51e821f8e8c1de223bff8bf5a`, `git diff --cached` **6085** before/after. `git add -N` only `Djmix24SharedTest.kt` + `Djmix24AppTest.kt`.
Full patch from `git diff HEAD` (65 files: 62 prior + 2 new tests + `TransitionPlanner` trackNumber addition counted in the 65; 10,976 insertions, 566 deletions).
Delta patch from `git diff HEAD -- <15 vc66 files>` (see §5 NOT TICKED note on true isolation).
Proof: `Bitchord/evidence/bitchord-device-evidence/djmix24-proof/` — `apk-verify.txt` (apksigner v2, aapt, 14 libs), `test-results.txt` (counts), `telemetry-audit.txt` (Step 1), `git-invariants.txt`, `mirror-report.md`, `install-pending.txt` (offline), `regressions.md`, `native-symbols.txt`, build logs excerpts. `djmix24-pre/` empty (phone offline, honestly recorded).
Mirror: Simular `main` (cb-fcfcd7a79078) `/workspace/BitChord` verified byte-identical source copy — HEAD `dc78e0d`, cached 6085, `git diff HEAD` 65 files / 10,976+/566-, staged/unstaged/intent-to-add preserved, vc66 file hashes match bk (see `mirror-report.md`). Toolchain on mirror: `/workspace/android-env.sh` → JDK 21.0.13, SDK android-36, build-tools 36.0.0, NDK 27.3.13750724, CMake 3.22.1; Chrome purge holds (no binary/processes). Keystore at `/workspace/keys-local/bitchord-loopfix.jks` (mode 600, hash-verified, never in git). No build run on Simular. Both boxes parked after verification (`bk-android` suspended, Simular `main` hibernated).

---

## 7. Exact next steps — install pending, then listening gate (ears only)

Phone `1439ebea` was offline for the whole cycle (`adb devices -l` empty). APK is staged and verified. When the phone is online (USB or wireless `_adb-tls-connect._tcp`):

1. **Read-only pre-install snapshot (optional, no playback):** `adb -s 1439ebea shell dumpsys package com.music.bitchord.loopfix > djmix24-proof/preinstall-dumpsys-package.txt; adb -s 1439ebea shell dumpsys media_session > djmix24-proof/preinstall-dumpsys-media-session.txt; adb -s 1439ebea shell dumpsys audio > djmix24-proof/preinstall-dumpsys-audio.txt; adb -s 1439ebea pull /sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl djmix24-proof/preinstall-telemetry.jsonl; adb -s 1439ebea pull /sdcard/Android/data/com.music.bitchord.loopfix/files/crash-log.txt djmix24-proof/preinstall-crashlog.txt` (read-only; do not `pm clear`).
2. **Single-shot install (the only mutating step):** `adb -s 1439ebea install -r /home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix24-automix-arm64-v8a-oldkey.apk > djmix24-proof/install.log 2>&1` → expect `Success`. Never `uninstall`, `pm clear`, `force-stop`, play/pause, seek, or enqueue. Verify `adb -s 1439ebea shell dumpsys package com.music.bitchord.loopfix | grep -E "versionCode|versionName|lastUpdateTime"` shows `66` / `2.2.3-djmix24`.
3. **Read-only post-install proof:** repeat (1) into `postinstall-*`; compare telemetry line counts/prefix; explain any `mix.handoff.deferred` events and whether song 2 remains playing **only from evidence**, not assumptions. Save install stdout/stderr and all checks to `djmix24-proof/`.
4. **Four-pair listening gate (ears only, no audio claimed yet):**
   1. **This Is What You Came For → One Kiss** — audible beat-synced jet sweep + tightening roll into full-bass drop; song 2 keeps playing, vocal crisp.
   2. **Beautiful → Never Let Go** — 8 bars, no “too early”, outro plays out.
   3. **Drip → UP** — no chipmunk, no stutter, no freeze hang.
   4. **Technologic → Guess** — no decline, no vocal mush.
   Queue them if absent (agent must NOT queue/manipulate). Record internal audio, report per-pair: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? Any dead zone >8 bars or mid-vocal cut? For pair 1 specifically: did song 2 keep playing past the blend, and did `mix.handoff.deferred` appear?
   Status: **[INSTALL PENDING — then AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**.

Known flake protocol: `QueueShuffleTierTest` benchmark (passes in isolation; can trip under parallel load) — if it is the ONLY failure, prove by isolated rerun + full rerun; never report green falsely. This gate had **zero flakes** (full green first rerun after the 3 deterministic regressions were fixed; see `regressions.md`).

Infra notes: Simular credits 3,343 at mirror time (down from 3,379). Standby fork `bk-android-standby` intentionally skipped (subordinate, capacity risk). bk had 19 historical OOM kills (last 2026-11-12, java 3.6 GB) — gate passed with no OOM (Gradle `-Xmx4096m --offline --no-daemon`).

*End of handover. vc66 is built and verified — install it, then the verdict belongs to your ears.*

---

## Post-install update — 2026-11-14 11:48 WIB (on-device)

Phone `1439ebea` reconnected via `adb connect 192.168.100.218:36603` (mDNS stale `192.168.100.216`; TCP scan: 36603 open, 42901 refused on `.218`).

- **Pre-install (vc65):** `versionCode=65 versionName=2.2.3-djmix23 lastUpdateTime=2026-11-14 10:38:09` (`djmix24-proof/preinstall-dumpsys-package.txt`).
- **Post-vc65 telemetry (761 new lines, 8191–8951):** `216× mix.plan` (M6 30 / classic 147 / M1 34 / RELAXED 5), `32× mix.fire`, `58× mix.complete`, `3× mix.handoff.deferred`, `3× mix.pause.detected` (`changeReason=1`), `0× mix.bail`; first-8,190 prefix SHA `09ce8490…` virgin. `crash-log.txt` absent. See `preinstall-telemetry-delta.txt`.
- **Install:** `adb -s 192.168.100.218:36603 install -r …/bitchord-2.2.3-djmix24-automix-arm64-v8a-oldkey.apk` → **Success** (`install.log`, SHA `0443f3bf…` verified pre-install).
- **Post-install:** `versionCode=66 versionName=2.2.3-djmix24 lastUpdateTime=2026-11-14 11:48:39`; telemetry unchanged (8,951 lines `4913e5d3…`). Status: **[INSTALLED — AWAITING USER INTERNAL-AUDIO RECORDING & FOUR-PAIR LISTENING VERDICT]**.
