# BitChord vc70 Wrap-Up Handover — 2026-11-15 (ALL SHIPPED + MIRRORED, LISTEN + NPU FIELD PENDING)

> **Honest state in 5 lines.** `2.2.3-djmix28` (**vc70**) is **BUILT (`BUILD SUCCESSFUL in 33m 2s`, exit 0), SIGNED (`oldkey`), VERIFIED (1,762 fresh runs, 0 failures, 1 pre-existing `GeniusTest` skip), AND MIRRORED** — no `adb install -r` was attempted; `djmix28-proof/install-pending.txt` records the safe single-shot for `1439ebea`, and delivery is via **LAN `http://10.212.240.111:8088/bitchord-2.2.3-djmix28-automix-arm64-v8a-oldkey.apk`** (verified HTTP 200 + full SHA match; external hosts catbox/litterbox/0x0.st all failed — see `transfer.md`). `vc69`→`vc70` shipped **Seam A teardown invariant** (defer `filters.open()` until silent), **Seam B `exitBeats` + two-phase muffle/bridge rides** (full-band returns over explicit beats), **native `DROP_IN` arm**, **family registry**, and **R128 deletion**, plus **NPU two-generation bundled QAIRT** (V73+V75 hedge, ORT 1.31.0 from source, 21 libs). **No audio claim** (`UNVERIFIED UNTIL USER EARS HEAR IT`) and **no NPU-dispatch claim** (`UNVERIFIED UNTIL FIELD TEST` — no log proves QNN opened or which Skel loaded). This doc is the single "what's left" backlog: **install → four-pair listening gate → telemetry + NPU log check → DSP/template tuning from ears**. Continuity: `docs/BITCHORD_HANDOVER_VC69_WRAPUP.md` is the vc69 record; nothing in it was rewritten.

---

## 1. Shipped state (vc69 → vc70 lineage)

| VC | Version | What it shipped | Status |
|---|---|---|---|
| vc61 | `2.2.3-djmix19` | Beat/loop foundations, phrase lanes | Superseded |
| vc62–vc66 | `2.2.3-djmix20`→`djmix24` | Loop-mix renderer, `mix.handoff.deferred` guard, EDM buildup + stereo Jet Flanger + loop roll, `BUILD`/`DROP`, TPT SVF, plate reverb, 1-beat 200 Hz bass swap, 1.5 ms limiter | Superseded |
| vc67 | `2.2.3-djmix25` | Forensic R1–R4 fixes + P1 structure/interpolation + on-device Wiener `umxhq` separation + per-region keys + DSP/fader polish + P2 tuner/verify/schema | Superseded (see `BITCHORD_HANDOVER_VC67_WRAPUP.md`) |
| vc68 | `2.2.3-djmix26` | Task 2A stabilizer/scrub/window unification + Task 2B sync/carve/echo-out/4s-decline/key-clash blend | Superseded (see `BITCHORD_HANDOVER_VC68_WRAPUP.md`) |
| vc69 | `2.2.3-djmix27` | P0 decline rescues + P1 vocal-depth/kick-lock/release-splice/R128 + P2 beat-flow/evidence + NPU QNN EP (code-only, no libs bundled) | Superseded (see `BITCHORD_HANDOVER_VC69_WRAPUP.md`) |
| **vc70** | **`2.2.3-djmix28`** | **Seam A teardown invariant + Seam B `exitBeats`/two-phase rides + native DROP_IN arm + family registry + R128 deletion + NPU two-gen bundled QAIRT** (detail in `BITCHORD_HANDOVER_DJMIX28.md` §3) | **BUILT + VERIFIED + MIRRORED, INSTALL PENDING** |

**No install yet:** No ADB mutation was attempted; APK is staged and verified. Single-shot `adb -s 1439ebea install -r /home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix28-automix-arm64-v8a-oldkey.apk` is the only pending mutating step (delivery via the LAN URL, full-hash verified; host must stay awake, phone on same LAN). Never `uninstall`/`pm clear`/`force-stop`/play/seek/queue. Build constraints honoured: disk pre-clean freed 2.0G→3.6G by deleting only regenerable build outputs (`npu-addon/prebuilt` untouched), no Gradle clean, no source/test edits beyond the version bump (69→70), no commit/reset/checkout, `HEAD=dc78e0d`, cached diff 6,085 lines preserved (SHA `4a4c6821…`). Both cloud builders park after this stage (`bk-android` suspend, Simular `main` hibernate).

---

## 2. What's left (backlog, in priority order)

### 2.1 [P0 — EARS ONLY] Four-pair listening gate
Status: **[BUILT + MIRRORED — INSTALL PENDING, THEN AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**. No audio quality is claimed from code/tests/telemetry alone. Queue the pairs if absent (agent must NOT enqueue/manipulate), play **hands-off** through each blend, and report per pair: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? decline rescued (muffle/drop-in/bridge) vs hard cut? dead zone >8 bars or mid-vocal cut? **vc70 seals to listen for**: muffle/bridge must return to full-band (no lingering 700/1500 Hz park at handoff), teardown must never crack a parked filter audible, DROP_IN must enter unfiltered.
1. **`This Is What You Came For` → `One Kiss`** — no hard Song-A cut; declined pairs rescued via muffle/drop-in/bridge instead of raw overlap; kick-locked anchor lands the swap on the kick (1/8 beat); incoming vocal crisp via vocal-depth filter; `mix.handoff.deferred` if geometry needs it; song 2 keeps playing past handoff.
2. **`Beautiful` → `Never Let Go`** — 8-bar blend, no "too early", sung outro plays out; beat-flow ranking prefers the overlap whose beat survives the handoff.
3. **`Drip` → `UP`** — folded tempo (~2.6% delta) must beatmix; kick-lock keeps the swap on the kick, no chipmunk, no stutter, no freeze hang; native −1 dBTP limiter holds summed peaks (R128 removed, no double-limit artifacts expected).
4. **`Technologic` → `Guess`** — no decline, no vocal mush (vocal-depth scales the filter travel by measured presence; release-splice handback has no click); evidence record shows which gates/scores decided the point.
After the gate, pull fresh `automix-telemetry.jsonl` and correlate `mix.fire`/`mix.complete`/`mix.handoff.deferred`/`mix.pause.detected`/`mix.verify`/`mix.plan` + `TransitionEvidence` records against what was heard.

### 2.2 [P0 — INSTALL + TELEMETRY] Re-pair & single-shot install
- **Next:** Bring the phone onto the host LAN, download the APK from `http://10.212.240.111:8088/bitchord-2.2.3-djmix28-automix-arm64-v8a-oldkey.apk` (verify 108,019,024 B / `6a2dd0bb…ce91`), then single-shot: `adb -s 1439ebea install -r /home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix28-automix-arm64-v8a-oldkey.apk`. Verify `versionCode=70` / `2.2.3-djmix28` fresh `lastUpdateTime`. No `uninstall`/`pm clear`/`force-stop`/queue edits.
- Then pull `postvc70-telemetry.jsonl` (same non-mutating pulls) into a new file in `djmix28-proof/` — never overwrite `djmix27-proof/` or the 9,463-line `automix-telemetry.jsonl` pulled pre-vc67.

### 2.3 [P0 — NPU FIELD + EARS] Snapdragon log check (vc70 hedge settlement)
- On the Snapdragon device (Poco F6 / SM8635 target), run one analysis pass with log capture and confirm QNN session open (absence of "NPU QNN open failed; falling back to CPU") **and settle V73 vs V75**: `adb -s 1439ebea logcat -d BitChordTrackAnalyzer:I BitChordNPU:I *:S | grep -i npu`. The `skels=` field proves both are present; the loaded arch is the field question. Compare analysis time/quality vs CPU before claiming anything about the NPU. Off Snapdragon (MediaTek/Pixel) the path is CPU by design — NPU is not claimed there. Full statement in `djmix28-proof/npu-status.md`.

### 2.4 [P0 — REPRO] Hands-off vs touched pause test
- vc66/vc67 carried the open `mix.pause.detected` (`changeReason=1` = `USER_REQUEST`) question. vc69 evidence records can answer it directly: one Calvin playthrough **without touching the device**, then read-only pull. If `mix.pause.detected` disappears hands-off, prior events were user pause and the ownership chain is correct. If it persists, file as residual ownership bug for vc71.

### 2.5 [P1 — DSP] Tuning from ears
- vc70 seam geometry (`exitBeats` 2/4, muffle 700 Hz floor, bridge 1,500 Hz park, DROP_IN echo 0.75 s, Seam A guard) is shipped but untuned on device. Next: one-constant-at-a-time ear pass (exit-beat counts, floor/park frequencies, drop-in overlap, bridge span bounds, kick tolerance, vocal-depth floors, splice width), re-run the 4-pair gate each time. Keep `US 8,280,539` + Signalsmith/Compose freeze + zero audio-thread allocations.

### 2.6 [P2 — INFRA] Post-gate telemetry & feedback loop
- Pull post-gate telemetry into `djmix28-proof/postvc70-telemetry.jsonl` (never overwrite prior files). Confirm `mix.plan` full-schema fields populate (including `exitBeats`/`alternativeKind`), `mix.verify` gates hold (`±3.0 LUFS`, `0.896` peak, `±25 ms`, `exitGates==0`), evidence records replay decisions offline, and `mix.fire mode=loop` ratio vs historic `22/1`. Keep `HEAD=dc78e0d`, `cached=6085`, `git add -N` only, `arm64-v8a`/`oldkey` only.
- `MixWeightTuner`/`MixVerify` remain at neutral defaults until thumbs; only then tune ±weights from listening. No model downloads (`htdemucs`/Demucs stays NOT implemented by design). Re-run stale `:shared:testDebugUnitTest` + `:shared:testReleaseUnitTest` (2026-11-14T15:35Z, 309 each) if a 4-way grand is needed.

---

## 3. Artifacts & proof index

| Artifact | Bytes / Lines | SHA-256 |
|---|---|---|
| APK `bitchord-2.2.3-djmix28-automix-arm64-v8a-oldkey.apk` (mirrored, install pending) | 108,019,024 B | `6a2dd0bbdfdcc2a52e0f06800558d9e8b13dc3d4c6c6852799c0cb56c965ce91` |
| `patches/bitchord-djmix28-full.patch` (114 files from `dc78e0d`) | 26,072 lines, 1,303,808 B | `93b658747ef549f45c1224a1e23ee03563e64e4502267d5339037ad33fc66a47` |
| `patches/bitchord-djmix28.patch` (vc70 delta, 30 files incl. version bump + R128 deletions) | 11,908 lines, 618,287 B | `5f0e2300eb9d4778a7144a2c0e3a10b81308767487c023136ee2a535ee1ae8a9` |
| Proof `evidence/bitchord-device-evidence/djmix28-proof/` (`apk-verify.txt` 85 lines, `test-results.txt` fresh 1762, `git-invariants.txt` 126 lines, `mirror-report.md`, `transfer.md`, `npu-status.md`, `install-pending.txt`) | — | `apk-verify.txt` `05184089…` |
| Handover `docs/BITCHORD_HANDOVER_DJMIX28.md` | — | — |
| This wrap-up | — | — |
| Prior vc69 record | `docs/BITCHORD_HANDOVER_VC69_WRAPUP.md` + `docs/BITCHORD_HANDOVER_DJMIX27.md` | untouched |

Lineage invariants carried forward: `HEAD=dc78e0d`, `git diff --cached | wc -l = 6085` (SHA `4a4c6821…`), `vc70` fresh gate **1,762** runs (420 shared + 1342 app), **0 failures**, 1 pre-existing skip, GPL-3.0+ clean, scalars-only cross-track scoring, zero audio-thread allocations/locks, `arm64-v8a`/`oldkey` only, **bundled two-gen QNN stack** (V73+V75 hedge; dispatch UNVERIFIED).

---

## 4. Exact next steps (in order)

1. **Transfer + install `1439ebea`** (phone on host LAN, host awake) → read-only pre snapshots → single-shot `adb install -r` (§2.2).
2. **Four-pair listening gate** (§2.1) — user queues/plays/records; agent stays hands-off. Watch the vc70 seals (full-band returns, silent teardown, unfiltered DROP_IN).
3. **Telemetry + NPU log check** (§2.3) — pull evidence records; on Snapdragon, run the logcat command to settle V73 vs V75 + QNN open.
4. **Hands-off vs touched playback test** (§2.4) — one Calvin playthrough without touching the device, then read-only telemetry pull.
5. **vc71 scope decision** from ears + telemetry (likely DSP tuning from §2.5 or dropping the proven-unused Skel generation, one pillar per build).
6. **Never run** `uninstall`/`pm clear`/`force-stop`/builds on box w/o waking per protocol; never claim audio quality or NPU dispatch without ears + field test.

*End of wrap-up. vc70 is on the builders and staged — install it, then the verdict belongs to your ears (and, for the NPU, to a Snapdragon field log).*
