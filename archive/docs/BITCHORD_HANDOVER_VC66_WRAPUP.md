# BitChord vc66 Wrap-Up Handover — 2026-11-14 (ALL SHIPPED + INSTALLED, LISTEN PENDING)

> **Honest state in 5 lines.** `2.2.3-djmix24` (**vc66**) is **BUILT, SIGNED (`oldkey`), VERIFIED (1,889 tests, 0 failures), AND INSTALLED** on `1439ebea` (`versionCode=66`, `lastUpdateTime=2026-11-14 11:48:39`). `vc61`→`vc66` shipped the full EDM buildup + stereo Jet Flanger + tightening loop-roll stack, structure/beat/vocal awareness, and native club DSP — with **zero audio claims** (no ears yet). Post-`vc65` telemetry (761 new lines) proved the vc65 handoff guard firing live (`3× mix.handoff.deferred`, `0× mix.bail`); post-`vc66` telemetry (+118 lines) shows the Calvin pair handing off and completing, but with **2× `mix.pause.detected` post-handoff** (`changeReason=1`) that must be resolved by ear + further telemetry. This doc is the single "what's left" backlog: **listening gate → pause-after-handoff root cause → structure/beat/vocal interpolation tuning → DJ polish → optional stems**.

---

## 1. Shipped state (vc61 → vc66 lineage, all installed on `1439ebea`)

| VC | Version | What it shipped | Status |
|---|---|---|---|
| vc61 | `2.2.3-djmix19` | Beat/loop foundations, phrase lanes | Superseded |
| vc62–vc65 | `2.2.3-djmix20`→`djmix23` | Loop-mix renderer, `mix.handoff.deferred` guard (`canAdoptSessionPlayer`), song-2 self-pause forensics | Superseded |
| **vc66** | **`2.2.3-djmix24`** | **EDM buildup + stereo Jet Flanger + `4→2→1→0.5` loop roll (Bug A1–A4), `BUILD`/`DROP` painting, ±35 ms onset snap, outro phrase-loop extension, Rung 0 GAPLESS, lyric-gap + vocal handoff, TPT SVF, plate reverb, 1-beat 200 Hz bass swap, 1.5 ms limiter** | **BUILT + INSTALLED + LIVE** |

**Single-shot install (the only mutating step ever taken):** `adb -s 192.168.100.218:36603 install -r …/bitchord-2.2.3-djmix24-automix-arm64-v8a-oldkey.apk` → `Success` (`djmix24-proof/install.log`). Never `uninstall`/`pm clear`/`force-stop`/play/seek/queue. Both cloud builders parked (`bk-android` suspended, Simular `main` hibernated, 3,343 credits at mirror time).

---

## 2. Device telemetry ledger (all read-only pulls, no playback touched)

| Snapshot | Lines | SHA-256 (prefix) | Notes |
|---|---|---|---|
| `djmix23-proof/postinstall-telemetry.jsonl` (vc65 install time) | 8,190 | `09ce8490…` | Baseline; first-8,190 byte-identical everywhere |
| `djmix24-proof/preinstall-telemetry.jsonl` (pre-vc66, vc65 live) | **8,951** (+761) | `4913e5d3…` | Post-vc65: `216× mix.plan`, `32× mix.fire`, `3× mix.handoff.deferred`, `3× mix.pause.detected`, `0× mix.bail`; `crash-log.txt` absent |
| `djmix24-proof/postinstall-telemetry.jsonl` (immediately post-install) | 8,951 (unchanged) | `4913e5d3…` | No playback yet |
| `djmix24-proof/postvc66-telemetry.jsonl` (after user played vc66) | **9,069** (+118) | `e9df2a83…` | Post-vc66: `24× mix.plan` (classic 15 / M1 9), `4× mix.complete`, `1× mix.handoff.deferred`, **`2× mix.pause.detected`**, `exit.gate violations=0` |

**Calvin-pair post-vc66 trace (lines 8969–8976, the key open evidence):**
- `mix.handoff.deferred` `mMs=968, inStartAtMs=1907, handoffAtMs=965` — identical geometry to vc65 line 8204; guard fires again on vc66.
- `mix.pause.detected` site=`driveLoopMix`, `handedOff=true, tookOver=true, positionMs=9603, loopIncomingStarted=true, changeReason=1 (USER_REQUEST)` — session player adopted, song 2 advanced to ~9.6 s, then paused.
- `mix.exit.gate violations=0`, and `mix.complete` `loop-complete` (`124.34→124.25, handoff=true`).
- **Interpretation (no claim yet):** handoff ownership transferred and the blend completed; the post-handoff pause at ~9.6 s with `USER_REQUEST` is consistent with the user pausing manually, **but** it is also consistent with a residual ownership bug — ears + one more telemetry pull after a deliberate no-touch playback are required to close this. See §3.1.

---

## 3. What's left (backlog, in priority order)

### 3.1 [P0 — EARS ONLY] Four-pair listening gate
Status: **[INSTALLED — AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**. No audio quality is claimed from code/tests/telemetry alone. Queue the pairs if absent (agent must NOT enqueue/manipulate), play **hands-off** through each blend, and report per pair: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? dead zone >8 bars or mid-vocal cut?
1. **`This Is What You Came For` → `One Kiss`** — audible 8-beat stereo Jet sweep (`~0.258 Hz`) + `4→2→1→0.5` tightening roll + HPF sweep into full-bass drop; song 2 keeps playing past the handoff; incoming vocal crisp.
2. **`Beautiful` → `Never Let Go`** — 8-bar blend, no "too early", sung outro plays out.
3. **`Drip` → `UP`** — no chipmunk, no stutter, no freeze hang.
4. **`Technologic` → `Guess`** — no decline, no dual-vocal mush (`outMid −6 dB` carve, `inMid 0 dB`).
After the gate, pull fresh `automix-telemetry.jsonl` and correlate `mix.fire`/`mix.complete`/`mix.handoff.deferred`/`mix.pause.detected` against what else was actually heard.

### 3.2 [P0 — ROOT CAUSE] Post-handoff `mix.pause.detected` on the Calvin pair
- vc66 still emits `mix.pause.detected` (`changeReason=1`, `positionMs=9603/9633`, `loopIncomingStarted=true`) ~1 s (§2) after `mix.handoff.deferred`. vc65 telemetry shows the same pattern (`positionMs=17189/19254/0`).
- **Next:** reproduce with a hands-off playback + immediate read-only telemetry pull. If `mix.pause.detected` disappears when the user never touches the device, it is `USER_REQUEST` (user pause) and the vc65/66 ownership chain is correct. If it persists untouched, treat as residual `driveLoopMix` ownership bug → **vc67** investigation (session-player adoption + `driveLoopMix` pause path; prior forensics in `djmix23-forensics/FINDINGS.md`).

### 3.3 [P1 — STRUCTURE + BEAT AWARENESS] Song-structure depth
- vc66 paints `BUILD`/`DROP` (`SongSections`/`SongMaps`/`StructureAnalyzer`) and scores `BUILD→DROP` alignments, but full `VERSE`/`CHORUS`/`BRIDGE` awareness and **song interpolations** (mashup-style re-use of non-adjacent sections) are not yet done.
- **Next:** extend section vocabulary only where energy/flux/vocal evidence is strong (keep the ≥2-increment gradual gate; never false-paint flat ballads), and validate against the 4 target pairs before any planner change.

### 3.4 [P1 — VOCAL/INSTRUMENTAL] Separation quality
- Now: `umxhq` vocal windows + lyrics + LR4 isolator mid/high handoff (Pillar C). `0× mix.bail` everywhere, but separation is mask-grade, not stem-grade.
- **Next (optional, opt-in only):** `htdemucs` 4-stem separation behind an explicit ≥8 GB-RAM gate — **NOT default** (2.2 GB RAM phone, license/ feasibility review required). No model downloads until P0/P1 closed.

### 3.5 [P1 — DJ-LEVEL MIXING] Polish & tuning
- vc66 DSP is wired (Jet Flanger, loop roll, SVF Q 0.95 sweeps, plate wash, limiter) but gains/ratios are test-pinned constants, not ear-tuned. Expect a vc67 tuning pass after the listening gate: flanger wet/feedback, HPF sweep end (500 Hz default), reverb damping/wet, limiter knee, bass-swap beat length, FX-rotation weighting.
- **Next:** change one DSP constant per vc-build, re-run the 4-pair gate, keep `US 8,280,539` scalars-only safety + zero audio-thread allocations/locks + Signalsmith/Compose freeze invariants.

### 3.6 [P2 — INFRA] Telemetry & release hygiene for vc67
- Pull post-gate telemetry into a new `djmix25-proof/` (never overwrite `djmix24-proof/`). Keep `HEAD=dc78e0d`, `git diff --cached | wc -l = 6085`, `git add -N` only for new test files, and next APK as `arm64-v8a`-only `oldkey` in `~/Downloads/`.
- No new cloud builds until the listening verdict defines what vc67 must fix.

---

## 4. Artifacts & proof index

| Artifact | Bytes / Lines | SHA-256 |
|---|---|---|
| APK `bitchord-2.2.3-djmix24-automix-arm64-v8a-oldkey.apk` (installed) | 87,498,355 B | `0443f3bfabf0dd2bd3bba0d3d065674fb6009c87d49b3ed8ba61e2db3c57e257` |
| `patches/bitchord-djmix24-full.patch` (65 files from `dc78e0d`) | 14,288 lines | `6e6b104a…204d1` |
| `patches/bitchord-djmix24.patch` (15 vc66 files) | 6,757 lines | `09cfecef…ee2bb` |
| Proof `evidence/bitchord-device-evidence/djmix24-proof/` (18 files: pre/post dumpsys, telemetry ×3, delta, install.log, apk-verify, test-results, mirror-report, regressions, native-symbols) | — | `postvc66-telemetry.jsonl` 9,069 lines, SHA `e9df2a83…` |
| This wrap-up | — | — |
| Milestone diary `docs/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` | 167,802 B (appended at install; prefix `165,936 B` SHA `3e1a971c…` intact) | — |

Lineage invariants carried forward: `HEAD=dc78e0d`, `git diff --cached | wc -l = 6085` (both builders), `vc65`/`vc66` handoff guard live (`mix.handoff.deferred` observed 4× across snapshots), GPL-3.0+ clean (no `madmom`/`umxl` NC, `SamAfshar2011/AI_DJ`, or `Orchard`), scalars-only cross-track scoring, zero audio-thread allocations/locks.

---

## 5. Exact next steps (in order)

1. **Four-pair listening gate** (§3.1) — user queues/plays/records; agent stays hands-off.
2. **Hands-off vs touched playback test** (§3.2) — one full Calvin-pair playthrough without touching the device, then read-only telemetry pull; classify the `mix.pause.detected` as user-pause or vc67 bug.
3. **vc67 scope decision** from the verdict (likely DSP tuning and/or handoff fix); then structure/interpolation/vocal work (§3.3–3.5) one pillar per build.
4. **Never run** `uninstall`/`pm clear`/`force-stop`/builds on box w/o waking per protocol; never claim audio quality without ears.

*End of wrap-up. vc66 is on the phone and under test — your ears close P0.*
