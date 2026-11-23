# BitChord DJMIX26 Handover — 2026-11-14 (vc68, BUILT + VERIFIED + MIRRORED, INSTALL PENDING)

> **Honest state in 5 lines.** `2.2.3-djmix26` (vc68) is **BUILT, SIGNED (`oldkey`), VERIFIED, MIRRORED, INSTALL PENDING** — phone `1439ebea` answered ping at `192.168.100.218` but refused ADB TLS `:42901` during build stage, so `install -r` was not run; `install-pending.txt` logged cleanly. APK `/home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix26-automix-arm64-v8a-oldkey.apk` (= `/home/hfzhkn/Downloads/…` via dir symlink, same inode): `87,531,123` B, SHA-256 `5a2c579a7affa1b64b9a94065eed2e83b8acb504ee9641ce9d476359b0ec6324`, 14 `arm64-v8a` `.so` libs, v2 cert `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`, `versionCode=68`, `versionName=2.2.3-djmix26`. Full gate **2,312** test runs, **1 known-flake failure (`QueueShuffleTierTest` benchmark, passes in isolation)**, **1 skipped (`GeniusTest`, pre-existing)**. vc67→vc68 shipped Task 2A (CountdownStabilizer + scrub robustness + smartTransitionWindow unification) and Task 2B (downbeat snapping + ramp-hold sync + vocal carve fix + DJ echo-out + bounded 4s filtered decline + key-clash blend), reconciled into one tree. **No audio claim** (`UNVERIFIED UNTIL USER EARS HEAR IT`).

---

## 1. Executive state [PROVEN]

| Item | Value |
|---|---|
| Built on | `bk-android` (`instabox-bk-android`, `host=instabox-bk-android user=hfzhkn`) — `2026-11-14`, `BUILD SUCCESSFUL in 40m 40s` (`:app:assembleProdRelease --offline --no-daemon`, no clean; first attempts died on the 60s exec timeout, run completed server-side via `nohup`) |
| Version | `2.2.3-djmix26`, **versionCode 68** (`aapt: versionCode='68' versionName='2.2.3-djmix26' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36'`) |
| APK | `/home/hfzhkn/Storage/Downloads/bitchord-2.2.3-djmix26-automix-arm64-v8a-oldkey.apk` (= `/home/hfzhkn/Downloads/…` via dir symlink) — **87,531,123 B**, SHA-256 `5a2c579a7affa1b64b9a94065eed2e83b8acb504ee9641ce9d476359b0ec6324` (authoritative, `install-pending.txt` + `apk-verify.txt`). 14 `lib/arm64-v8a/` `.so` libs only (`native-code: 'arm64-v8a'`), v2 cert `20c66ad6…e3fe4c` (oldkey, DN `CN=BitChord loop fix, O=BitChord loop fix, C=US`, `Verified using v2 scheme: true`), `vocals_umxhq_int8.onnx` (9,054,206 B) + `beat_this_final0_q8.onnx` (23,138,816 B) already bundled — **0 extra APK models added in vc68** |
| Installed on | `1439ebea` — **INSTALL PENDING** (ping OK at `192.168.100.218`, ADB TLS `:42901` refused at build stage; no `install -r` attempted, no uninstall/pm clear/force-stop/play-pause-seek-queue). Safe instruction in §4. |
| Phone proof | `djmix26-proof/` (`apk-verify.txt`, `test-results.txt`, `git-invariants.txt`, `mirror-report.md`, `install-pending.txt`) — build/verify/mirror/install-pending evidence only; no fake playback. No prior `djmix25-proof/`, `bitchord-djmix25*.patch`, `BITCHORD_HANDOVER_DJMIX*.md`/`VC67_WRAPUP.md` touched. |
| Listening verdict | **UNVERIFIED UNTIL USER EARS HEAR IT** — no audio claim from tests/unit geometry alone |

---

## 2. Version, tests & APK

| Gate | Result |
|---|---|
| `build.gradle.kts` | `bitchordVersionCode = 68`, `bitchordVersionName = "2.2.3-djmix26"` (root `build.gradle.kts` extras; `app/build.gradle.kts` reads them) |
| `:shared:jvmTest` | **367** tests, 0 failures/errors/skipped (fresh `--rerun-tasks` 15:47Z, `BUILD SUCCESSFUL in 5m 32s`) |
| `:shared:testDebugUnitTest` | **309** tests, 0 failures/errors/skipped (fresh 15:35Z) |
| `:shared:testReleaseUnitTest` | **309** tests, 0 failures/errors/skipped (fresh 15:35Z) |
| `:app:testProdDebugUnitTest` | **1,327** tests, **1 failure (`QueueShuffleTierTest` benchmark — known load flake, passes in isolation 1m 35s; full rerun reproduced)** , **1 skipped (`GeniusTest`, pre-existing)** (fresh 15:41Z) |
| Grand (four runs above) | **2,312** runs, **1 known-flake failure (isolated pass)**, 1 skipped — from live fresh XML totals on `bk-android` (`djmix26-proof/test-results.txt`, jvmTest `--rerun-tasks` 15:47Z) |
| New tests in delta | `Djmix26SharedTest.kt`, `Djmix26AppTest.kt` (+ updates to `AdvancedAutomixPlannerTest`, `Djmix16/18/20/21RegressionTest`, `Djmix18AppHandoffTest`) |
| `:app:assembleProdRelease` | `BUILD SUCCESSFUL` (40m 40s); native proved via APK listing (`libbitchord_analysis.so` 481,768 B, `libbitchord_loopmix.so` 438,616 B, `libbitchord_stretch.so` 410,304 B — unchanged vs vc67) |
| Git invariants on `bk-android` | `HEAD=dc78e0d`, `git diff --cached \| wc -l = 6085` (SHA `4a4c6821a4a7d463a8cfcb8be45752b5f0fa81f70bd40de141ff27f4efb804e3`), `git diff HEAD \| wc -l = 19630` (SHA `bf92922983632bb3703b961de3e03dc92ee710a6bec337879d9cfbf0f0da235b` = full patch). No `commit`/`reset`/`checkout --`. |

---

## 3. What shipped in vc68 (Task 2A + Task 2B, vc67 → vc68)

Delta patch (`bitchord-djmix26.patch`, 20 files): `CountdownStabilizer.kt`, `PrepLead.kt`, `DeckBudget.kt`, `DjMix.kt`, `LoopMix.kt`, `PhrasePlanner.kt`, `TransitionPlanner.kt`, `TransitionMoves.kt`, `SeekAction.kt`, `CrossfadeController.kt`, `LoopMixSession.kt`, `Djmix26*Test`, `build.gradle.kts` (version only).

### Task 2A — Stabilizer & seek robustness
- **CountdownStabilizer**: stabilises the mix countdown so late/declined planner decisions resolve deterministically instead of flapping between rungs.
- **Scrub robustness**: seeks/scrubs during a transition no longer break phase geometry (supersedes the vc67 `TAIL -> HONOUR` guard with full-path coverage).
- **smartTransitionWindow unification**: one transition window shared across planners (replaces per-planner ad-hoc windows).

### Task 2B — Sync, carve & exit geometry
- **Downbeat snapping**: mix entry/exit snapped to downbeats so blends land on musical boundaries.
- **Ramp-hold sync**: ramp holds stay phase-locked with the reference deck (no drift under tempo fold).
- **Vocal carve fix**: outgoing/incoming vocal carve no longer mushes overlapping vocals (carries forward the vc67 `-6 dB` carve line).
- **DJ echo-out**: declined/hard exits use the DJ echo-out tail instead of a hard cut.
- **Bounded 4s filtered decline**: the old unbounded decline path is now capped at a 4s filtered decline.
- **Key-clash blend**: key-clashing pairs blend through the key-aware path rather than forcing a whole-track clash decision (per-region `keyHead`/`keyTail` from vc67).

### Merge reconciliation
Task 2A and Task 2B were developed against the same planner stack and reconciled into a single tree — both delta sets are preserved; no planner file lost a fix (full patch 19,630 lines from `dc78e0d`; cached diff of 6,085 lines untouched). All cross-track scoring remains scalars-only; zero audio-thread allocations in `render()`/`renderDeck()`; GPL-3.0+ clean.

---

## 4. Artifacts & proof

| Artifact | Lines / Bytes | SHA-256 |
|---|---|---|
| APK `bitchord-2.2.3-djmix26-automix-arm64-v8a-oldkey.apk` (mirrored, install pending) | 87,531,123 B | `5a2c579a7affa1b64b9a94065eed2e83b8acb504ee9641ce9d476359b0ec6324` |
| `patches/bitchord-djmix26.patch` (vc68 delta, 20 files) | 10,571 lines, 552,816 B | `fa3d2b90c38cb75be604ef78d931c7a918133e9086a8c08f9a50a9e6b826415e` |
| `patches/bitchord-djmix26-full.patch` (83 files from `dc78e0d`) | 19,630 lines, 983,467 B | `bf92922983632bb3703b961de3e03dc92ee710a6bec337879d9cfbf0f0da235b` |
| Proof `evidence/bitchord-device-evidence/djmix26-proof/` (`apk-verify.txt` 46 lines, `test-results.txt`, `git-invariants.txt` 93 lines, `mirror-report.md`, `install-pending.txt`) | — | — |
| This handover | — | — |
| Wrap-up `docs/BITCHORD_HANDOVER_VC68_WRAPUP.md` | — | — |

---

## 5. Exact next steps (in order)

1. **Re-pair `1439ebea`** (Wireless Debugging ON) → read-only pre snapshots → single-shot `adb install -r` (full sequence in `djmix26-proof/install-pending.txt`). Verify `versionCode=68` / `2.2.3-djmix26` fresh `lastUpdateTime`. No `uninstall`/`pm clear`/`force-stop`/queue edits.
2. **Four-pair listening gate** — user queues/plays/records; agent stays hands-off. Per pair report: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? dead zone >8 bars or mid-vocal cut? Pairs: `This Is What You Came For` → `One Kiss`; `Beautiful` → `Never Let Go`; `Drip` → `UP`; `Technologic` → `Guess`.
3. **Hands-off vs touched playback test** — one Calvin playthrough without touching the device, then read-only telemetry pull; check whether `mix.pause.detected` still appears hands-off.
4. **Pull post-vc68 telemetry** into a new file in `djmix26-proof/` (never overwrite `djmix25-proof/`); correlate `mix.fire`/`mix.complete`/`mix.handoff.deferred`/`mix.verify`/`mix.plan` against what was heard.
5. **Never** claim audio quality without ears. `UNVERIFIED UNTIL USER EARS HEAR IT`.

*End of handover. vc68 is on the builder and staged — install it, then the verdict belongs to your ears.*
