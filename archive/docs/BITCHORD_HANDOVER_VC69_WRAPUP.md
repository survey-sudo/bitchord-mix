# BitChord vc69 Wrap-Up Handover — 2026-11-15 (ALL SHIPPED + MIRRORED, LISTEN + NPU FIELD PENDING)

> **Honest state in 5 lines.** `2.2.3-djmix27` (**vc69**) is **BUILT (`BUILD SUCCESSFUL in 8m 23s`), SIGNED (`oldkey`), VERIFIED (1,760 fresh runs, 0 failures, 1 pre-existing `GeniusTest` skip), AND MIRRORED** — phone `1439ebea` was **offline for ADB** (ping 100% loss at `192.168.100.218`, `adb devices -l` empty) during the read-only probe, so no `adb install -r` was attempted; `djmix27-proof/install-pending.txt` records the safe single-shot. `vc68`→`vc69` shipped **P0 decline rescues** (muffle / drop-in / beatless bridge + kick-lock anchors), **P1 vocal-depth filter + release-splice + R128 sum limiter**, **P2 beat-flow ranking + transition evidence dump**, and **NPU QNN execution-provider wiring for Beat This + umxhq** (CPU fallback kept, **no QNN libs bundled** — HTP backend expected from device firmware). **No audio claim** (`UNVERIFIED UNTIL USER EARS HEAR IT`) and **no NPU-dispatch claim** (`UNVERIFIED UNTIL FIELD TEST` — no Snapdragon log proves QNN opened). This doc is the single "what's left" backlog: **install → four-pair listening gate → telemetry + NPU log check → DSP/template tuning from ears**. Continuity: `docs/BITCHORD_HANDOVER_VC68_WRAPUP.md` is the vc68 record; nothing in it was rewritten.

---

## 1. Shipped state (vc68 → vc69 lineage)

| VC | Version | What it shipped | Status |
|---|---|---|---|
| vc61 | `2.2.3-djmix19` | Beat/loop foundations, phrase lanes | Superseded |
| vc62–vc66 | `2.2.3-djmix20`→`djmix24` | Loop-mix renderer, `mix.handoff.deferred` guard, EDM buildup + stereo Jet Flanger + loop roll, `BUILD`/`DROP`, TPT SVF, plate reverb, 1-beat 200 Hz bass swap, 1.5 ms limiter | Superseded |
| vc67 | `2.2.3-djmix25` | Forensic R1–R4 fixes + P1 structure/interpolation + on-device Wiener `umxhq` separation + per-region keys + DSP/fader polish + P2 tuner/verify/schema | Superseded (vc67 live until vc68 install; see `BITCHORD_HANDOVER_VC67_WRAPUP.md`) |
| vc68 | `2.2.3-djmix26` | Task 2A stabilizer/scrub/window unification + Task 2B sync/carve/echo-out/4s-decline/key-clash blend | Superseded (vc68 live until vc69 install; see `BITCHORD_HANDOVER_VC68_WRAPUP.md`) |
| **vc69** | **`2.2.3-djmix27`** | **P0 muffle/drop-in/beatless-bridge rescues + P1 vocal-depth/kick-lock/release-splice/R128 + P2 beat-flow/evidence + NPU QNN EP** (detail in `BITCHORD_HANDOVER_DJMIX27.md` §3) | **BUILT + VERIFIED + MIRRORED, INSTALL PENDING** |

**No install yet:** Phone `1439ebea` did not answer ping and `adb devices -l` was empty in the read-only probe, so no `install -r` was attempted; `install-pending.txt` records the safe single-shot. APK is staged and verified. Never `uninstall`/`pm clear`/`force-stop`/play/seek/queue. Build constraints honoured: no Gradle clean, no source/test edits beyond the version bump, no commit/reset/checkout, `HEAD=dc78e0d`, cached diff 6,085 lines preserved (one `git add -N` for KickLock.kt; cached count/SHA unchanged). Both cloud builders parked (`bk-android` `suspended`, Simular `main` `hibernated`, 2,485 credits at park time).

---

## 2. What's left (backlog, in priority order)

### 2.1 [P0 — EARS ONLY] Four-pair listening gate
Status: **[BUILT + MIRRORED — INSTALL PENDING, THEN AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**. No audio quality is claimed from code/tests/telemetry alone. Queue the pairs if absent (agent must NOT enqueue/manipulate), play **hands-off** through each blend, and report per pair: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? decline rescued (muffle/drop-in/bridge) vs hard cut? dead zone >8 bars or mid-vocal cut?
1. **`This Is What You Came For` → `One Kiss`** — no hard Song-A cut; declined pairs rescued via muffle/drop-in/bridge instead of raw overlap; kick-locked anchor lands the swap on the kick (1/8 beat); incoming vocal crisp via vocal-depth filter; `mix.handoff.deferred` if geometry needs it; song 2 keeps playing past handoff.
2. **`Beautiful` → `Never Let Go`** — 8-bar blend, no "too early", sung outro plays out; beat-flow ranking prefers the overlap whose beat survives the handoff.
3. **`Drip` → `UP`** — folded tempo (~2.6% delta) must beatmix; kick-lock keeps the swap on the kick, no chipmunk, no stutter, no freeze hang; R128 sum limiter holds summed peaks at −0.5 dBFS.
4. **`Technologic` → `Guess`** — no decline, no vocal mush (vocal-depth scales the filter travel by measured presence; release-splice handback has no click); evidence record shows which gates/scores decided the point.
After the gate, pull fresh `automix-telemetry.jsonl` and correlate `mix.fire`/`mix.complete`/`mix.handoff.deferred`/`mix.pause.detected`/`mix.verify`/`mix.plan` + new `TransitionEvidence` records against what was heard.

### 2.2 [P0 — INSTALL + TELEMETRY] Re-pair & single-shot install
- **Next:** Once Wireless Debugging is ON and the phone is on LAN, `adb connect 192.168.100.218:<port>`, verify `versionCode` still 68, then single-shot: `adb -s 1439ebea install -r /home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix27-automix-arm64-v8a-oldkey.apk`. Verify `versionCode=69` / `2.2.3-djmix27` fresh `lastUpdateTime`. No `uninstall`/`pm clear`/`force-stop`/queue edits.
- Then pull `postvc69-telemetry.jsonl` (same non-mutating pulls) into a new file in `djmix27-proof/` — never overwrite `djmix26-proof/` or the 9,463-line `automix-telemetry.jsonl` pulled pre-vc67.

### 2.3 [P0 — NPU FIELD + EARS] Snapdragon log check (new in vc69)
- On a Snapdragon device, run one analysis pass with log capture: confirm QNN session open (absence of "NPU QNN open failed; falling back to CPU") and compare analysis time/quality vs CPU before claiming anything about the NPU. Off Snapdragon (MediaTek/Pixel) the path is CPU by design — NPU is not claimed there. Full statement in `djmix27-proof/npu-status.md`.

### 2.4 [P0 — REPRO] Hands-off vs touched pause test
- vc66/vc67 carried the open `mix.pause.detected` (`changeReason=1` = `USER_REQUEST`) question. vc69 adds evidence records that can now answer it directly: one Calvin playthrough **without touching the device**, then read-only pull. If `mix.pause.detected` disappears hands-off, prior events were user pause and the ownership chain is correct. If it persists, file as residual ownership bug for vc70.

### 2.5 [P1 — DSP] Tuning from ears
- vc69 geometry (decline rescues, kick-lock, vocal-depth travel, release-splice, R128 ceiling) is shipped but untuned on device. Next: one-constant-at-a-time ear pass (muffle lead beats, drop-in echo tail, bridge span bounds, kick tolerance, vocal-depth floors, splice width, R128 ceiling), re-run the 4-pair gate each time. Keep `US 8,280,539` + Signalsmith/Compose freeze + zero audio-thread allocations.

### 2.6 [P2 — INFRA] Post-gate telemetry & feedback loop
- Pull post-gate telemetry into `djmix27-proof/postvc69-telemetry.jsonl` (never overwrite prior files). Confirm `mix.plan` full-schema fields populate, `mix.verify` gates hold (`±3.0 LUFS`, `0.896` peak, `±25 ms`, `exitGates==0`), evidence records replay decisions offline, and `mix.fire mode=loop` ratio vs historic `22/1`. Keep `HEAD=dc78e0d`, `cached=6085`, `git add -N` only, `arm64-v8a`/`oldkey` only.
- `MixWeightTuner`/`MixVerify` remain at neutral defaults until thumbs; only then tune ±weights from listening. No model downloads (`htdemucs`/Demucs stays NOT implemented by design). Re-run stale `:shared:testDebugUnitTest` + `:shared:testReleaseUnitTest` (15:35Z, 309 each) if a 4-way grand is needed.

---

## 3. Artifacts & proof index

| Artifact | Bytes / Lines | SHA-256 |
|---|---|---|
| APK `bitchord-2.2.3-djmix27-automix-arm64-v8a-oldkey.apk` (mirrored, install pending) | 87,551,587 B | `abb336daf8017235df12dce4e40c2952c3235674feaa9305b97290e79909a73a` |
| `patches/bitchord-djmix27-full.patch` (102 files from `dc78e0d`) | 22,862 lines | `3db58c05ffd858648776f836d31b1fffcc29b22245cc68b650df4fbdc186ddef` |
| `patches/bitchord-djmix27.patch` (vc69 delta, 28 files) | 9,749 lines | `5bf91488fef249abc5c2a6f833e4dc3974f9cdde952a5e2921dd94c218c85792` |
| Proof `evidence/bitchord-device-evidence/djmix27-proof/` (`apk-verify.txt` 56 lines, `test-results.txt` fresh 1760/0/1, `git-invariants.txt` 112 lines, `mirror-report.md`, `npu-status.md`, `install-pending.txt`) | — | `apk-verify.txt` `1dbaafda…` |
| Handover `docs/BITCHORD_HANDOVER_DJMIX27.md` | — | — |
| This wrap-up | — | — |
| Prior vc68 record | `docs/BITCHORD_HANDOVER_VC68_WRAPUP.md` + `docs/BITCHORD_HANDOVER_DJMIX26.md` | untouched |

Lineage invariants carried forward: `HEAD=dc78e0d`, `git diff --cached | wc -l = 6085` (SHA `4a4c6821…`), `vc69` fresh gate **1,760** runs, **0 failures**, 1 pre-existing skip, GPL-3.0+ clean, scalars-only cross-track scoring, zero audio-thread allocations/locks, `arm64-v8a`/`oldkey` only, **no QNN libs bundled** (HTP from device firmware; dispatch UNVERIFIED).

---

## 4. Exact next steps (in order)

1. **Re-pair `1439ebea`** (Wireless Debugging ON, phone on LAN) → read-only pre snapshots → single-shot `adb install -r` (§2.2).
2. **Four-pair listening gate** (§2.1) — user queues/plays/records; agent stays hands-off.
3. **Telemetry + NPU log check** (§2.3) — pull evidence records; on Snapdragon, capture the QNN open log.
4. **Hands-off vs touched playback test** (§2.4) — one Calvin playthrough without touching the device, then read-only telemetry pull.
5. **vc70 scope decision** from ears + telemetry (likely DSP tuning from §2.5, one pillar per build).
6. **Never run** `uninstall`/`pm clear`/`force-stop`/builds on box w/o waking per protocol; never claim audio quality or NPU dispatch without ears + field test.

*End of wrap-up. vc69 is on the builders and staged — install it, then the verdict belongs to your ears.*
