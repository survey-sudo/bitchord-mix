# BitChord Automix — changelog and next-session handover (2026-11-18)

Written: **2026-11-18**. Continues from [AUTOMIX_HANDOVER_2026-11-17.md](AUTOMIX_HANDOVER_2026-11-17.md) (which ended at `vc78` / `c2c7f32`).

**Later STOP (13:18:55 +07:00):** the user rejected the musical result and ended
the session. See [stopped WIP handoff](../.slim/worktrees/automix-82-integration/docs/AUTOMIX_STOP_HANDOFF_2026-11-18.md).
New repairs are uncommitted/uncompiled and were not installed. Do not build,
promote, resume agents or follow older next steps without new user authorization.

## 1. Executive state at handover

- **Installed on target phone (`1439ebea`, model `24069PC21G`):** **vc82 / `2.2.3-djmix40`** (`com.music.bitchord.loopfix`), source `60b9734711b3a0e3214a31f76eac7766c61ae873`, branch `vc82-overlap-listen`. Installed via wireless TLS ADB `install -r` (`device-vc82-wifi-142720/install.json` exit `0`; `device-vc82-live-150709/package.txt` confirms `versionCode=82`, `versionName=2.2.3-djmix40`).
- **User-accepted stability baseline:** **vc81 / `2.2.3-djmix39`**, source `e7c8a3e2df09b9a3f801f3d6dde856a607711d60`, branch `vc81-automix-replan`. User approved: *"Okay I approve it's way stabler."* Preserve vc81's ticker/lyric fixes and vc80's dropout fix in every subsequent build.
- **User-accepted dropout fix:** **vc80 / `2.2.3-djmix38`**, source `2cb6e6fe69b63176614925e8f5a4c6b5725060f8`, resolved the Touch the Sky → Higher Love mid-mix dropout (*"okay that dropout is fixed!!!!"*).
- **What is NOT accepted or proven yet:**
  - **vc82 listening quality:** **open.** The user's *"even better"* remark preceded wireless ADB installation of vc82 (initial device snapshot still showed `versionCode=81` with no running BitChord session). At the last check (`device-vc82-live-150709/`), BitChord had no running session and ADB was subsequently disconnected (`adb devices -l` empty).
  - **Sequential-overlap / early-fade root cause:** **unproven in live audio.** vc82 changes only the classic `DECLINE` faders; native transitions (`LoopMixSession`) use separate renderer envelopes, and no live failing vc82 transition has been captured yet.
  - **Intermittent stutter / sudden demuffle:** **open, currently unreproduced.** No currently repeatable failing pair is identified.
  - **Apple AutoMix parity:** **unproven.** Never claim parity.

## 2. Changelog since vc78 (`c2c7f32` → `60b9734` + offline characterization)

All builds below are linear descendants of `master` (`2d502985d0ce7a97bb30bbaf9f28a4815be6db31`, vc76) and `vc78` (`c2c7f32`):

### vc79 / `2.2.3-djmix37` — `f71f9b8` (`vc79-handover-clock`, pushed to `origin/vc79-handover-clock`)
- **Changed:** `finishLoop()` handover cleanup now compares current, generation-valid presentation clocks (`LoopMixPort`, `PrecisionAudioSink`, `LoopMixRenderer`, `CrossfadeController`) at one monotonic instant instead of seeking back to a frozen `heard(IN)` endpoint; added explicit drift/unstarted-decoder recovery, spare-only retirement, pause/seek preservation, and 17 new unit tests (`LoopHandoverCleanupTest` 12, `LoopMixPortClockTest` 4, `LoopSinkClockTest` 1).
- **CI:** Run `43985488318` passed (shared 495/0/0/0, app 1390/0/0/1, native gate pass).
- **Device outcome:** **Failed listening** (*"Still abrupt"*). USB capture reproduced a ~3.75–3.76 s mid-mix silence on Touch the Sky → Higher Love despite no abnormal seek.

### vc80 / `2.2.3-djmix38` — `c1091fc` + `2cb6e6f` (`vc80-loop-source`, pushed to `origin/vc80-loop-source`)
- **Changed (`c1091fc`):** Fixed finite incoming-loop source coverage (`LoopMix`, `LoopMixSession`, `native/loopmix/loop_mix_renderer.cpp/.h`) where full-region finite loops muted valid mapped PCM and unwrapped-loop vs physical-song handover coordinates diverged; added 63-pair `--loop-domain-probe` and `LoopSourceGeometryTest`/`LoopSourceDomainTest`.
- **Changed (`2cb6e6f`):** Preserved alignment offsets in generated loop timestamps across consecutive mixes (`PrecisionAudioSink`, `LoopMixRenderer`, `LoopGeneratedTimestampTest`).
- **CI & cue:** Actions run `44016339991` passed (shared 497/0/0/0, app 1399/0/0/1; full native gate + ASan/UBSan + calibration + 63-pair probe). Signed SHA-256 `47163057210e0adebeb8fbb46ce69664576f2ed81b974d586040c1da328020e4` (CI/bk-android), 67,552,565 B. (`cue` separately held `/tmp/signed.apk` `857042a9…` from provisioning checks.)
- **Diagnostics run on vc80:**
  - **Native stutter probe (`../automix-stutter-2026-11-17/native/`):** 56 host x86_64 cases / 90,202 callbacks; unchanged production renderer; did not reproduce persistent collapse under normal tested stimuli, no cue callback deadline overrun. No native stretch/DSP edit was made.
  - **Demuffle capture (`../automix-stutter-2026-11-17/device-demuffle-capture1/`):** Recorded Hollow Victory → Drip Drip, which actually ran native Echo (`mode=loop`, `deltaMs=-3979`, normal `loop-complete`). User answered **"nope"** to whether the demuffle occurred there; kept as a control.
  - **Saved fade telemetry analysis (`../automix-seam-2026-11-17/fade-telemetry/`):** Found 21/41 fires ended in `emergency-retire-guard` (an investigation lead, not proof of audible cuts because native `player.volume=1.0` is pinned and `deltaMs` is generated-vs-target, not presented clock). Corrected raw finding: `mix.dump` *does* contain pair IDs; missing is reliable correlation between fired transition and heard interval.
  - **One-song planning stall report:** User reported *"mind u rn NONE of automix even working or making any plan at all"* → force-close restored one song → stopped again. Capture `../automix-stutter-2026-11-17/one-song-stall-085702/` recorded PID `30702` completing one loop (`seq 11`) followed by an `IDLE` scrub cancel (`seq 12`) and 4 minutes of telemetry silence while playing `FAITH — ATEEZ` with next track analysis ready (`81 BPM Bb major, model grid 0.93`).
- **Device outcome:** Touch the Sky → Higher Love **dropout user-confirmed fixed**; stutter/demuffle intermittent; planning continuity failed after one song.

### vc81 / `2.2.3-djmix39` — `26ee0c2` + `e7c8a3e` (`vc81-automix-replan`, local worktree + cue, not pushed to `origin`)
- **Changed (`26ee0c2`):** Guarded `CrossfadeController.start()` tick loop (`catch (Exception)` per iteration with explicit `CancellationException` rethrow, no `Error` catch, 250 ms yield, burst-deduped `mix.tick.error` + `mix.tick.recovered` on persistent `AutomixTelemetry` because release ProGuard strips `Log.e` including `PlaybackService`'s `CoroutineExceptionHandler`); added state-change + 30 s heartbeat `mix.state` snapshot isolated *after* core phase work. Added `AutomixTickerRecoveryTest` (5 tests) and `AutomixReplanLifecycleTest` (2 tests).
- **Changed (`e7c8a3e`):** Fixed `SongSections.labelSections()` (`shared/.../SongSections.kt:45-52`): a lyric cue starting within 300 ms of `contentEndTime` passed `startSec < end` and threw `IllegalArgumentException: Cannot coerce value to an empty range` in `coerceIn(start + 0.3, end)` on every `planTransition → DjMix.annotate → labelSections` tick. Now skips cues unless `!(minimumEnd <= end)` (also rejecting NaN bounds). Added `SongSectionsTest` (3 tests, proving failure on vc80 through `planTransition` and pass on vc81).
- **Verification on cue (`../automix-stutter-2026-11-17/vc81-validation/`):** Shared **500/0/0/0**, App **1406/0/0/1**, native gate pass, unsigned build → CLI `apksigner` v2+v3 (`20c66ad6…`), `audit-apk.sh` pass, `zipalign -c -P 16` pass. APK: 67,552,565 B, SHA-256 `00277861640fec25b374f9bfb145352f0d5936252a8e6e6e1d9aa688317b5ca6`.
- **Hosting:** `https://lossless.example.com/downloads/bitchord-vc81-djmix39.apk` (public full download verified 200 / matching SHA-256).
- **Device outcome:** User installed and **approved stability** (*"Okay I approve it's way stabler"*). Exact original phone throw site remains unconfirmed from vc80's stripped log.

### vc82 / `2.2.3-djmix40` — `2fb6b1f` + `60b9734` (`vc82-overlap-listen`, local worktree + cue, not pushed to `origin`)
- **Context:** Four-seat council (`../automix-stutter-2026-11-17/OVERLAP_COUNCIL_2026-11-17.md`) evaluated the user's complaint (*"lot of mixxing just being first song faded out too early then next song come in"*) and kept the old dirty EOF catch-up candidate held due to test resets (`handedOff` manually reset between ticks) and a conditional sink-lead watchdog hang risk.
- **Changed (`2fb6b1f`, offline eval):** In `TransitionAlternatives.kt`, changed classic `DECLINE` faders (`declineInGainAt` / `declineOutGainAt`) from sequential exponents (`0.5` / `1.9`; midpoint gains `0.444/0.409`, power sum `0.364`) to complementary equal-power curves at common linear progress (`MixGain.rise` / `MixGain.fall`; midpoint `0.707/0.707`, power sum `1.0`). 4 s duration, `content_end` anchor, cues, rates, filters (~800 Hz out LP / ~440 Hz in HP at midpoint), bass handoff, native engine, and all other styles stayed unchanged. Added `DeclineOverlapRenderTest` (4 tests, zero-lead production `TransitionFilterProcessor` synthetic renders + unequal 80/160 ms lead routing test) and updated `TransitionAlternativesTest` + `Vc70DeclineRideAppTest`.
- **Offline render measurements (`../automix-stutter-2026-11-17/overlap-eval/`):** Baseline vs repeat and unaffected `DJ_BLEND` control were bit-identical. Both `DECLINE` synthetic probes gained `1.34 s` with both gain commands `>= 0.5` (vs `0.0 s`) and `+3.13 dB` (multitone) / `+2.85 dB` (120/87 percussion) central summed RMS after actual filtering and `0.94` headroom trim. *Does not establish vocal/rhythm acceptability.*
- **Changed (`60b9734`, vc82 release stamp):** Version bump `82 / 2.2.3-djmix40` in `build.gradle.kts`, `tools/vc75-gate.sh`, `tools/audit-apk.sh`, plus comment-only accuracy updates in `CrossfadeController.kt` and `TransitionAlternatives.kt`. Zero runtime code change from `2fb6b1f`.
- **Verification on cue (`../automix-stutter-2026-11-17/vc82-validation/`):** Shared **500/0/0/0**, App **1410/0/0/1**, native gate pass, CLI v2+v3 signing/cert/audit/16 KiB alignment pass. APK: 67,552,565 B, SHA-256 `a07617094ba02cd3fbd5d393468a52d35d226a96c2a1e1d11c9f98e64f9d3bc9`. (Note: `verified-summary.json` recorded `"installed_by_agent": false` before the later wireless install.)
- **Hosting & install:** Hosted at `https://lossless.example.com/downloads/bitchord-vc82-djmix40.apk` (public download SHA-256 verified 200; vc81 URL kept intact). Installed on `1439ebea` via wireless TLS ADB (`adb install -r`, exit 0, no data clear); restored paused UI on `Empty Box — ATEEZ`.

### Post-vc82 test-only characterization (unshipped, no production diff)
- **Branch / worktree:** `offline-arming-readiness` at `.slim/worktrees/automix-arming-diagnostic` (over `60b9734`).
- **Changed:** Only `app/src/test/java/com/music/bitchord/playback/DeclineOverlapRenderTest.kt` (`+154 / -6`, SHA-256 `2893d4c174a2fda81614ca205756c353bae1dddf01cba7a35964487472c68442`) adding `lateIncomingReadinessCharacterizesSoloFilterAndReopen`.
- **Cue focused run (`../automix-stutter-2026-11-17/arming-readiness/`):** **12 tests, 0 failures/errors/skips** (`DeclineOverlapRenderTest` 5, `AutomixTickerRecoveryTest` 5, `AutomixReplanLifecycleTest` 2), `production.diff` = 0 bytes.
- **Observed behavior in deterministic zero-lead rig:** When incoming track `B` stays `STATE_BUFFERING` (`playWhenReady=false`, volume `0`) through `A=194.000 s` (halfway through a `192–196 s` DECLINE plan), `CrossfadeController.driveArming()` leaves phase in `ARMING` with `A` at volume `1.0` while already commanding `A`'s low-pass to `799.9 Hz` (~800 Hz; synthetic 5 kHz tone measured `-82.25 dBFS` vs `-21.43 dBFS` solo reference, while 173 Hz stayed within `0.30 dB`). When `B` becomes `STATE_READY` at `194 s`, `startFade()` anchors `fadeOutOriginMs=194000`, so the first `FADING` tick reopens `A`'s LP command to `7000 Hz` (`5 kHz` recovers to `-30.41 dBFS` over 20–100 ms before closing again).
- **Limits:** Proves command/filter trajectory in a zero-lead fake-clock fixture only; does *not* establish Android pipeline timing, real deadline/grace behavior (stubbed `SystemClock`), or whether this caused any user complaint.

## 3. Current repository, worktree, cue, and hosting state

### Git worktrees and branches (local workstation `/home/user/Bitchord/work/bitchord-mix`)

| Path | Branch | HEAD | Clean? | Purpose / rule |
|---|---|---|---|---|
| `/home/user/Bitchord/work/bitchord-mix` | `vc80-filter-catchup` | `2cb6e6f` | **Dirty** | **DO NOT BUILD OR PROMOTE.** Contains old held `CrossfadeController.kt` EOF catch-up + `Vc70ExitLegAppTest.kt`, obsolete held copy of `TransitionAlternatives*`/`Vc70DeclineRideAppTest.kt`, `.gitignore`, `.ignore`, and docs (`docs/` edits are current). |
| `.slim/worktrees/automix-replan` | `vc81-automix-replan` | `e7c8a3e` | Clean | Accepted stability baseline (`vc81 / 2.2.3-djmix39`). |
| `.slim/worktrees/automix-overlap-eval` | `vc82-overlap-listen` | `60b9734` | Clean | Currently installed listening candidate (`vc82 / 2.2.3-djmix40`). Also holds offline checkpoint `2fb6b1f`. |
| `.slim/worktrees/automix-arming-diagnostic` | `offline-arming-readiness` | `60b9734` | Test-only dirty | Holds unshipped `DeclineOverlapRenderTest.kt` characterization (`2893d4c…`). |

- `master` / `origin/master` are still at `2d502985` (`vc76`). None of `vc81-automix-replan`, `vc81-overlap-eval`, `vc82-overlap-listen`, or `offline-arming-readiness` has been pushed to `origin`; only local bundles in `/tmp/opencode/` exist.
- Prunable stale worktree entry `/tmp/cleanwt` still appears in `git worktree list`.

### Remote build host (`cue`)
- **Health / access blocker at handover:** HTTP health (`cue_status`) is **UP** (uptime ~53,263 s, load `0.03`, `54.3 GiB` free), **but both `cue_exec` and `ssh cue` currently fail with a Tailscale SSH browser-auth check** (`# Tailscale SSH requires an additional check. To authenticate, visit: https://login.tailscale.com/a/...`). Complete that Tailscale SSH approval before running remote commands.
- **Repo state on `cue` (`/workspace/src/bitchord-mix`):** Branch `vc82-overlap-listen` at `60b9734`, with uncommitted test-only patch `M app/src/test/java/com/music/bitchord/playback/DeclineOverlapRenderTest.kt` from the arming-readiness run. Discard that test diff (`git checkout -- app/src/test/...`) before any release build if not wanted, or check `git status` first.
- **Build contract:** `-Xmx5g` and `--max-workers=4`; `cue-android apk` builds unsigned then signs with `apksigner` CLI v2+v3 (do not switch to Gradle signing; never display/export `/root/.bitchord-secrets/` or `keystore.properties`; check `/tmp/kp.hold` absent). No workstation Android/Gradle builds.

### Phone (`1439ebea`) and web hosting (`df` instabox)
- **ADB status at handover:** `adb devices -l` is empty (wireless TLS ADB `192.168.100.218:32909` disconnected after earlier checks; USB disconnected; `adb-bridge-cue.service` active on workstation).
- **Hosted verified APKs on `lossless.example.com` (instabox `df`, `/var/www/html/downloads/`):**
  - `https://lossless.example.com/downloads/bitchord-vc81-djmix39.apk` (67,552,565 B, SHA-256 `00277861640fec25b374f9bfb145352f0d5936252a8e6e6e1d9aa688317b5ca6`)
  - `https://lossless.example.com/downloads/bitchord-vc82-djmix40.apk` (67,552,565 B, SHA-256 `a07617094ba02cd3fbd5d393468a52d35d226a96c2a1e1d11c9f98e64f9d3bc9`)
- **Evidence directory (outside git):** `/home/user/Bitchord/work/automix-stutter-2026-11-17/` (captures, validation XMLs/APKs, `capture_vc82_wifi.py`, `overlap-eval/`, `arming-readiness/`, council notes). Earlier seam/CI artifacts are in `/home/user/Bitchord/work/automix-seam-2026-11-17/`.

## 4. Non-negotiable invariants for next session

1. Preserve `vc80` finite-loop/handover fixes (`c1091fc`, `2cb6e6f`) and `vc81` ticker-recovery + lyric-boundary fixes (`26ee0c2`, `e7c8a3e`).
2. Never uninstall/reset app data or wipe queue; use `adb install -r` with a bumped `versionCode`/`versionName` (plus matching `tools/vc75-gate.sh` and `tools/audit-apk.sh`).
3. Never auto-resume a sticky user pause (`USER_REQUEST = 1`), and never park/reset the surviving session deck while retiring the spare.
4. Never build/promote from the dirty `vc80-filter-catchup` checkout, and keep the old EOF catch-up patch held unless its `incomingProgress >= 1` vs `!incomingReady` watchdog deadlock and fake `handedOff` test resets are resolved.
5. Separate mechanism probes (synthetic tones, scalar gains) from real-song listening evidence; do not claim audible improvement or Apple parity without matched device captures and listening acceptance.

## 5. Prioritized next steps

1. **Capture and evaluate `vc82` on `1439ebea` (highest priority once phone is active & ADB reconnected):**
   - Reconnect wireless TLS ADB (or USB) and verify `versionCode=82`. Do not force playback/queue edits.
   - Use `../automix-stutter-2026-11-17/capture_vc82_wifi.py` (or USB `capture_device.py`) over ≥2 natural transitions when the user is listening. Collect `automix-telemetry.jsonl`, `logcat`, media state, and user timestamps/track names for any good or bad mix.
   - Verify two operational proofs in telemetry: (a) sustained `mix.state`/`mix.plan`/`mix.fire`/`mix.complete` across multiple transitions (and check whether any `mix.tick.error` fires), and (b) whether the user's early-fade/sequential complaint improved on classic `DECLINE` pairs or actually occurred on native Echo/other styles.
2. **Implement the council-deferred executed-plan attribution (`mix.fire` enrichment, offline or next build):**
   - Today, `mix.dump` records *candidate* planning before native arbitration (`CrossfadeController.kt:2113`), while classic/native `mix.fire` omits pair IDs, executed style/variant/rung, frozen geometry, and built script identity.
   - Add primitive/nullable fields to existing `mix.fire` from frozen `render`/`planInFlight` (classic) and `session.plan`/`session.clock`/built script identity (native, *not* mutable caches), plus focused attribution tests (`AutomixReplanLifecycleTest` / `LoopHandoverCleanupTest` patterns). Avoid per-tick logging.
3. **Decide on `driveArming()` pre-fade outgoing filtering (`CrossfadeController.kt:3006-3019`):**
   - Only act if live telemetry/audio implicates delayed `STATE_READY` carving solo `A` and reopening at `startFade()`; remember outgoing sink-lead filtering before the speaker is partly intentional, so any readiness gate must avoid shifting the filter late by sink latency.
4. **Continue [AUTOMIX_PARITY_CHECKLIST.md](AUTOMIX_PARITY_CHECKLIST.md) P1→P5:**
   - Isolate any reproduced stutter/demuffle capture; fix remaining P1 continuity gaps (build-generation timing contamination, flush startup-vs-seek distinction, real loudness/peak measurements); then validate musical phrasing (P2), two-part stems/DJ moves (P3), optional SM8635 HTP NPU acceleration (P4), and blind level-matched Apple comparisons (P5).
