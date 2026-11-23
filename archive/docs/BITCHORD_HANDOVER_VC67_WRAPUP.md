# BitChord vc67 Wrap-Up Handover — 2026-11-14 (ALL SHIPPED + MIRRORED, LISTEN PENDING)

> **Honest state in 5 lines.** `2.2.3-djmix25` (**vc67**) is **BUILT, SIGNED (`oldkey`), VERIFIED (2,226 test runs, 0 failures), AND MIRRORED** — phone `1439ebea` was **offline for ADB** (ping OK at `192.168.100.218`, `:42901`/`:36603` refused) during install stage, so no `adb install -r` was attempted; `djmix25-proof/install-pending.txt` records the safe single-shot. `vc66`→`vc67` shipped the post-vc66 forensic fixes (UTF-8 keys, Rung 4 Echo for declined pairs, phrase-length `EQUAL_POWER` floor, `mixFall` cos-fall + `TAIL->HONOUR`, `tailDurationMs` reset, prime-before-yield + streaming fallback) plus the P1–P2 on-device stack: full `VERSE`/`CHORUS`/`BRIDGE` labeling, `InterpolationAnalyzer` phrase reuse beyond 4 bars, `VERSE_END`/`BRIDGE_END` + boundary bonuses, on-device `SeparateVocalWindow` STFT/ISTFT Wiener separation (Mit `umxhq` only, 0 extra models) and `acapella` 0.88/0.12 carrier, opt-in `>=8 GB` windowed stems, per-region `libkeyfinder`-style `keyHead`/`keyTail` (`Krumhansl`+`Temperley`), `ReverbWash` stereo fix, learned 9-point `FaderParams`, `MixWeightTuner`/`MixVerify` + full `mix.plan` schema. **No audio claim** (`UNVERIFIED UNTIL USER EARS HEAR IT`). This doc is the single "what's left" backlog: **install → four-pair listening gate → hands-off vs touched pause test → DSP/template tuning from ears**.

---

## 1. Shipped state (vc66 → vc67 lineage)

| VC | Version | What it shipped | Status |
|---|---|---|---|
| vc61 | `2.2.3-djmix19` | Beat/loop foundations, phrase lanes | Superseded |
| vc62–vc66 | `2.2.3-djmix20`→`djmix24` | Loop-mix renderer, `mix.handoff.deferred` guard (`canAdoptSessionPlayer`), EDM buildup + stereo Jet Flanger + `4→2→1→0.5` loop roll, `BUILD`/`DROP`, TPT SVF, plate reverb, 1-beat 200 Hz bass swap, 1.5 ms limiter | Superseded (vc66 live until vc67 install) |
| **vc67** | **`2.2.3-djmix25`** | **Forensic R1–R4 fixes + P1 structure/interpolation + on-device Wiener `umxhq` separation + per-region keys + DSP/fader polish + P2 tuner/verify/schema** (detail in `BITCHORD_HANDOVER_DJMIX25.md` §3) | **BUILT + VERIFIED + MIRRORED, INSTALL PENDING** |

**No install yet:** Phone `1439ebea` answered ICMP ping but refused ADB TLS (`:42901`/`:36603` `Connection refused`, `adb devices -l` empty, `install-pending.txt` logged). APK is staged and verified; single-shot `adb install -r` is the only pending mutating step. Never `uninstall`/`pm clear`/`force-stop`/play/seek/queue. Both cloud builders parked (`bk-android` `suspended`, Simular `main` `hibernated`, 3,092 credits at mirror time).

---

## 2. Device telemetry ledger (all read-only pulls, no playback touched)

| Snapshot | Lines | SHA-256 (prefix) | Notes |
|---|---|---|---|
| `djmix24-proof/postinstall-telemetry.jsonl` (vc66 install time) | 8,951 | `4913e5d3…` | Baseline for vc66; first 8,190 prefix virgin `09ce8490…` |
| `djmix24-proof/postvc66-telemetry.jsonl` (after user played vc66) | **9,069** (+118) | `e9df2a83…` | Calvin pair `loop-complete`, `2× mix.pause.detected` `changeReason=1` |
| `djmix25-proof/automix-telemetry.jsonl` (= live `vc66` pull) | **9,463** (+394 vs 9,069) | `7397fcf9…d692` | Post-vc66 user playback window lines **8951–9463**: `22 classic / 1 loop` fires, `29× before-decline-to-mix → 6.0s EQUAL_POWER`, `23/29 cold-loop-headroom/insufficient-cold-lead` before `primeLoopDecks`, `mix.pause.detected` ×2 still open |

**Forensic takeaway (lines 9070–9463):**
- Song-A hard cuts: `render.echo` `out.volume=1f` → `gain=1.0`, `Phase.TAIL->EMERGENCY_TAIL` 4× cascade (lines 9178/9181/9184/9187), stale `tailDurationMs=800`, classic late fires `+497 ms` mean.
- Lazy crossfades: `22 classic/1 loop`, `29× decline→6s`, `23/29` cold-loop yields before prime, `source`/`isComplete` `loopRegion` rejects, `♯/♭` JNI strip (~42% keys).
- All map 1:1 to vc67 R1–R4 fixes; prove with post-vc67 telemetry after install.

`crash-log.txt` absent on device pull. No playback touched during pulls.

---

## 3. What's left (backlog, in priority order)

### 3.1 [P0 — EARS ONLY] Four-pair listening gate
Status: **[BUILT + MIRRORED — INSTALL PENDING, THEN AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**. No audio quality is claimed from code/tests/telemetry alone. Queue the pairs if absent (agent must NOT enqueue/manipulate), play **hands-off** through each blend, and report per pair: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? dead zone >8 bars or mid-vocal cut?
1. **`This Is What You Came For` → `One Kiss`** — no hard Song-A cut; `mixFall` cos-fall over echo tail; `mix.fire mode=loop` (not classic); incoming vocal crisp; `mix.handoff.deferred` if geometry needs it; song 2 keeps playing past handoff.
2. **`Beautiful` → `Never Let Go`** — 8-bar blend, no "too early", sung outro plays out; phrase-length floor (not 6s lazy).
3. **`Drip` → `UP`** — folded tempo (~2.6% delta) must beatmix; no chipmunk, no stutter, no freeze hang; `STREAM` fallback arms non-YouTube if needed.
4. **`Technologic` → `Guess`** — no decline, no vocal mush (`outMid −6 dB` carve, `inMid 0 dB`; `acapella` 0.88/0.12 if gated).
After the gate, pull fresh `automix-telemetry.jsonl` and correlate `mix.fire`/`mix.complete`/`mix.handoff.deferred`/`mix.pause.detected`/`mix.verify`/`mix.plan` (now full schema) against what was heard.

### 3.2 [P0 — INSTALL + TELEMETRY] Re-pair & single-shot install
- **Next:** Once Wireless Debugging is ON at `192.168.100.218`, `adb connect 192.168.100.218:<port>`, verify `versionCode` still 66, then single-shot: `adb -s 1439ebea install -r /home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix25-automix-arm64-v8a-oldkey.apk`. Verify `versionCode=67` / `2.2.3-djmix25` fresh `lastUpdateTime`. No `uninstall`/`pm clear`/`force-stop`/queue edits.
- Then re-pull `postvc67-telemetry.jsonl` (same non-mutating pulls) into a new file in `djmix25-proof/` — never overwrite `djmix24-proof/` or the 9,463-line `automix-telemetry.jsonl` pulled pre-install.

### 3.3 [P0 — REPRO] Hands-off vs touched pause test
- vc66 post-handoff `mix.pause.detected` (`changeReason=1` = `USER_REQUEST`) still open from vc66 wrap-up §3.2. vc67 closes ownership/pause paths but must still prove it: one Calvin playthrough **without touching the device**, then read-only pull. If `mix.pause.detected` disappears hands-off, prior events were user pause and ownership chain is correct. If it persists, file as residual ownership bug for vc68.

### 3.4 [P1 — STRUCTURE + INTERPOLATION] Post-install tuning
- vc67 ships `VERSE`/`CHORUS`/`BRIDGE`/`PRE`/`POST` + `InterpolationAnalyzer` + `VERSE_END`/`BRIDGE_END`. Next: validate passage preferences against ears + telemetry (`mix.plan.reason`, `exitKind`, `entryKind`, interpolation discount vs heard sections). Change one planner weight per vc-build thereafter. Keep scalars-only, `≥2` gradual gate for BUILD, never false-paint flat ballads.

### 3.5 [P1 — VOCAL/INSTRUMENTAL] Stem listening
- Now: MIT `umxhq` Wiener `SeparateVocalWindow` (`<=22.8s` worker windows, 0 extra models) + `acapella` 0.88/0.12 + opt-in `>=8 GB` instrumental evidence (`AppSettings.automixWindowedStemsEnabled` default `false`). Next (ears + telemetry): check `acapella` pairs crispness and `mix.verify` health; instrument `mix.plan` `fxSlot` rotations actually felt.
- `htdemucs`/Demucs remains **NOT implemented by design** (NC/proprietary, license + 2.2 GB RAM phone). No model downloads until P0 closed.

### 3.6 [P1 — DJ POLISH] DSP/template tuning from ears
- vc67 DSP constants tuned (`REVERB_*_EDM`, `echoFeedbackFor`, 9-point `FaderParams`, `swapBar` 1-beat club). Next: one-constant-at-a-time ear pass (flanger wet/feedback, HPF 500 Hz end, plate damping/wet, limiter knee, `faderSwapBeats`), re-run 4-pair gate each time. Keep `US 8,280,539` + Signalsmith/Compose freeze + zero audio-thread allocations.

### 3.7 [P2 — INFRA] Post-gate telemetry & feedback loop
- Pull post-gate telemetry into `djmix25-proof/postvc67-telemetry.jsonl` (never overwrite `djmix24-proof/` or pulled 9,463-line live file). Confirm `mix.plan` full-schema fields populate, `mix.verify` gates hold (`±3.0 LUFS`, `0.896` peak, `±25 ms`, `exitGates==0`), and `mix.fire mode=loop` ratio vs historic `22/1`. Keep `HEAD=dc78e0d`, `cached=6085`, `git add -N` only, `arm64-v8a`/`oldkey` only.
- `MixWeightTuner`/`MixVerify` live at neutral defaults with zero behaviour change until thumbs. Only then tune ±weights from listening.

---

## 4. Artifacts & proof index

| Artifact | Bytes / Lines | SHA-256 |
|---|---|---|
| APK `bitchord-2.2.3-djmix25-automix-arm64-v8a-oldkey.apk` (mirrored, install pending) | 87,531,123 B | `e40b17e075774b9b064e8adb8be371d146e2b9697f2bc03fd8e708bac5eb2718` |
| `patches/bitchord-djmix25-full.patch` (79 files from `dc78e0d`) | 16,562 lines, 837,066 B | `40ddbbbc14452cdec468ee3a8c1694d7df2bd1207ab87c6be551f2f6ddb40c85` |
| `patches/bitchord-djmix25.patch` (35 vc67 files) | 9,518 lines, 496,657 B | `15ebd70b4e7ed5704fc1ae8dc156f7f95c5fd4a3a8646a33986c6ff9a7773af6` |
| Proof `evidence/bitchord-device-evidence/djmix25-proof/` (`apk-verify.txt` `1751` lines, `test-results.txt` `342/284/284/1,316`, `git-invariants.txt`, `mirror-report.md`, `install-pending.txt`, `native-symbols.txt` incl. `nativeSeparate`, `automix-telemetry.jsonl` 9,463 lines) | — | `apk-verify.txt` `6d20897e…` |
| Handover `docs/BITCHORD_HANDOVER_DJMIX25.md` | 23,447 B | `57a6b36b…` |
| This wrap-up | — | — |
| Milestone diary `docs/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` | 172,148 B (prefix `168,852 B` SHA `3f449df5ff6e47e68e41892364d264155d0c8af3ab457d377cc350657dcd20a0` verified byte-identical pre-append) | — |

Lineage invariants carried forward: `HEAD=dc78e0d`, `git diff --cached | wc -l = 6085` (both builders), `vc67` full gate **2,226** runs (**1,942** unique) **0** failures, GPL-3.0+ clean (no `madmom`/`umxl` NC, `SamAfshar2011/AI_DJ`, or `Orchard`), scalars-only cross-track scoring, zero audio-thread allocations/locks. Simular `balance_credits: 3092`, `bk-android` `suspended`, Simular `main` `hibernated`.

---

## 5. Exact next steps (in order)

1. **Re-pair `1439ebea`** (Wireless Debugging ON) → read-only pre snapshots → single-shot `adb install -r` (§3.2).
2. **Four-pair listening gate** (§3.1) — user queues/plays/records; agent stays hands-off.
3. **Hands-off vs touched playback test** (§3.3) — one Calvin playthrough without touching the device, then read-only telemetry pull.
4. **vc68 scope decision** from ears + telemetry (likely DSP/template tuning from §3.4–3.6, one pillar per build).
5. **Never run** `uninstall`/`pm clear`/`force-stop`/builds on box w/o waking per protocol; never claim audio quality without ears.

*End of wrap-up. vc67 is on the builders and staged - install it, then the verdict belongs to your ears.*
