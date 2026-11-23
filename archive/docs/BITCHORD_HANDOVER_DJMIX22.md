# BitChord DJMIX22 Handover — 2026-11-14 (vc64, BUILT + INSTALLED)

> **Honest state in 5 lines.** `2.2.3-djmix22` (vc64) is **BUILT, SIGNED (`oldkey`), AND INSTALLED ON `1439ebea`** 2026-11-14 ~05:40 local (`adb install -r` → `Success`, `versionCode=64`, `2.2.3-djmix22`). APK `d72edaf05f1cd63d3c7c9b0034530e9591236aca5bad092269e78022b0ce14d4`, 87,498,355 B, arm64-v8a only (14 libs), v2 cert `20c66ad6…e3fe4c`, 2,097 tests 0 failures. Key change vs vc63: **fixes the second song pausing itself on some beat blends** — the vc63 live Calvin pair (This Is What You Came For → One Kiss) tore the loop mix down 1.668 s after `mix.fire`, left the *incoming* song paused with no focus holder for ~70 s (`mix.complete handoff=true` + abandon/request in dumpsys), and only the user's manual play recovered it. **No audio claim:** listening verdict is the user's only. No play/pause/seek/queue was sent this cycle.

---

## 1. Executive state [PROVEN]

| Item | Value |
|---|---|
| Built on | `bk-android` `/data/workspace/bitchord-task2/work/BitChord` via Instabox CLI |
| Version | `2.2.3-djmix22`, **versionCode 64** (`build.gradle.kts:44-45`) |
| APK | `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix22-automix-arm64-v8a-oldkey.apk` — 87,498,355 B, SHA-256 `d72edaf05f1cd63d3c7c9b0034530e9591236aca5bad092269e78022b0ce14d4` |
| DEX/native ABI | 14 `lib/arm64-v8a/` entries only, `native-code: 'arm64-v8a'` |
| Signing | v2 only, 1 signer, cert `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` (`oldkey`, DN `CN=BitChord loop fix`) |
| Installed on | `1439ebea` (wireless `adb-1439ebea-05jQT0._adb-tls-connect._tcp`) — `lastUpdateTime=2026-11-14 05:40:17`, `Success`; telemetry 7884 lines pre and post; no new crash lines |
| Prior on phone | `2.2.3-djmix21` vc63 (`lastUpdateTime=2026-11-14 04:17:09`) — vc64 `install -r` over it |

---

## 2. What changed vs vc63

Three files + one new test file. Signalsmith untouched; Compose frozen; scalars-only scoring preserved; no `setPlaybackSpeed`/`beginEase`/`holdPhase` changes; zero audio-thread allocations in the new paths (scalar fields + a main-scoped coroutine, no new audio-callback work).

### 2.0 Trigger — traced from code + telemetry (not guessed)

Evidence: vc63 live telemetry, Calvin pair 124.341 → 124.249 (seq12–17). Timeline reconstruction:

| # | t (ms, seq ts) | Event | Read |
|---|---|---|---|
| 12 | …822983 | `mix.fire mode=loop` (takeover, `LOOP_RUNNING`) | mix on air |
| ~…822517 | ~0.5 s before seq17 | dumpsys **bare abandon** (adopt-time focus flip) | adoptPlayer ran at `handOff` (~1.5 s into the mix) |
| 13 | …824651 | `mix.exit.guard site=finish phase=LOOP_RUNNING` (+1.668 s) | `finish()` entered directly with the spare at volume 1.0 → retire-guard divert |
| 14 | …824652 | `mix.exit.tail 350ms retireSite=finish:LOOP_RUNNING` | the emergency-tail diversion |
| 15 | …824653 | `mix.seek phase=TAIL action=emergency_tail` (0→25 ms) | the settle seek (from `loopBail`) re-entered `onPositionDiscontinuity` mid-TAIL |
| 16 | …824653 | `mix.exit.tail retireSite=discontinuity:TAIL` | second teardown, off the plain non-loop branch (`loopSession` already nulled) |
| 17 | …825017 | `mix.complete phase=TAIL handoff=true reason=seek-emergency-tail` | mix declared complete; incoming song was never resumed |
| then | 04:20:24 abandon → 04:21:34 request | ~70 s with **no focus holder**; user press re-requested | the new session holder had been auto-paused and nothing restored it |

Why `finish()` got called mid-mix (silent of every candidate that emits telemetry first — no `mix.seek` precedes seq13 so it is not the seek path; no `mix.exit.gate` precedes seq13 so `finishLoop()` never ran; no `mix.freeze.*`/`mix.error`/`mix.watchdog`/`mix.arm.death` so not queue/error/watchdog/bail-frozen): the only remaining silent caller is **`driveLoopMix` `if (!sessionPlayer.playWhenReady) return loopBail("paused", settle = true)`** (`CrossfadeController.kt:4403`). The adopted session player (`incoming`, `handedOff=true`) had its `playWhenReady` set false by Media3's focus path after the bare abandon. Verified in Media3 1.11.0 bytecode on the box: `ExoPlayerImplInternal.setAudioAttributesInternal` posts to the player thread, calls `AudioFocusManager.setAudioAttributes(handleAudioFocus ? attrs : null)` then `updatePlayWhenReadyWithAudioFocus()`; `updateAudioFocus` **requests focus on the flip-to-true while playing, and returns `PLAYER_COMMAND_DO_NOT_PLAY (-1)` when the request is denied → `newPlayWhenReady = false` with change reason `AUDIO_FOCUS_LOSS`**; the flips post to *different* player threads, so the incoming request races the outgoing abandon. dumpsys proves the incoming request never held (no holder ~70 s). The bump from `loopBail('paused')` → `finish()` → retire-guard → 350 ms emergency tail → second discontinuity → `seek-emergency-tail` double teardown is all in the code (`finish()` :5008, guard :5030–5052, `loopBail` :4613 at the old numbering) and matches seq13–17 exactly.

*Honest caveat:* the only telemetry-silent alternative is `loopBail("a sink was flushed")` (renderer splice possibility). Nothing in seq12–17 distinguishes them — the new `mix.pause.detected` `changeReason` (reason 2 = `AUDIO_FOCUS_LOSS`) distinguishes them on the next occurrence. The abandon timing (134 ms before seq13) and the pair's successful handOff make the focus pause the consistent trigger.

The classic path from the same session (seq30–33) never paused: mixer faded the spare to silence before `finish()` (vol ≤ 0.001), so the guard never diverted, and classic `finish()` with `handedOff=true` never touches incoming `playWhenReady`. The loop path cannot do that — the renderer's gain lane, not `ExoPlayer.volume`, does the looping fade — so the spare sits at 1.0 and any direct `finish()` diverts.

### 2.1 LoopMix completion routing (`CrossfadeController.kt:244–268, 5171–5183`)

A `LOOP_RUNNING`/`LOOP_ARMING` mix that has **taken over and handed off** with a clean exit gate (`exitGateViolations == 0` — trivially true mid-mix: the native gate at `loop_mix_renderer.cpp:372` only counts decks that hit `stopAt`) now completes via **`finishLoop()`** — the renderer exit-gate path — instead of a bare `finish()` emergency-retire-guard. `finishLoop` mutes the retiring player while the port still holds the renderer's silence, disarms, and hands off at the heard position (drifting >150 ms → corrective handover seek). Pre-handoff, un-took-over, and dirty-gate cases keep the djmix12 emergency tail (the retiring plain sink can bleed after disarm; muting it pre-disarm mid-mix would instead duck the audible renderer through the shared audio path). `loopBail` paths preserved — its settle seek still runs afterwards (suppressed from scrub teardown by the `internalHandoverSeek`/1.2 s window).

Deliberately not wired mid-mix: muting the retiring player mid-loop also ducks the renderer (both decoded song and renderer frames pass `dspChain.process` and the final AudioTrack volume). The routing therefore targets the completion shape (handed-off), where the incoming player is the session and `finishLoop`'s handover seek is valid.

### 2.2 Atomic handoff (PlaybackService — `adoptPlayer`/`setSessionOwner`, `:3615–3688` + listener `:955–997`)

- The outgoing flip (owns = false) lands at once (bare abandon, as today). The **acquisition is scheduled `FOCUS_ACQUISITION_DELAY_MS` (150 ms, :9670)** later on the service scope (Dispatchers.Main) **after** the abandon is processed — the grant is then deterministic (no holder). Epoch token + `target === player` guard so a stale acquisition can never re-own a replaced player. Covers `adoptPlayerForVersionSwap` too (shares `setSessionOwner`).
- No bridge request: requesting before the abandon steals focus and Media3 pauses the *outgoing* tail (interrupting the audible blend — the exact fight the old code comment warned about).
- `userPausedPlayback` (`:3615`) tracks the listener's last intent from `onPlayWhenReadyChanged` (USER_REQUEST pause sticky; any play clears it). Never resurrects a user pause.
- Restore: if the session player is paused with `AUDIO_FOCUS_LOSS` inside the armed window (`FOCUS_SWAP_ARM_MS = 3_000L`, :9677) and `shouldRestorePlayAfterFocusSwap(wasPlaying, userPaused, focusHeld)` holds, `play()` is restored (`:972–994`); a same-check closes the gap right after the delayed acquisition runs (`:3674–3689`). Foreign loss outside the window is left alone.
- Documented residual: with the outgoing player no longer handling focus the app briefly holds none (~150 ms internal window) — the old comment claimed "never briefly holds none", which dumpsys disproved (+1.668 s abandon → 70 s no holder). The window is bounded and invisible in-app; unlike before, the acquisition cannot be denied against a still-held focus.

### 2.3 Telemetry (`mix.pause.detected`, new)

Emitted (scalars only) when the session player is found paused **within a mix** — `driveLoopMix` (`:4373`) and `driveLoopArming` (`:4504`) pre-bail sites — and **within 3 s after `mix.complete handoff=true`** (`checkHandoffPauseWatch`, `:1377–1401`, armed from `finish()` at `:5107`). Fields: `site, playWhenReady, playbackState, isPlaying, volume, changeReason, handedOff, tookOver, msSinceHandoff, positionMs`. `changeReason` is captured in the controller's `onPlayWhenReadyChanged` (`:1066–1068`) — the next occurrence is provable from telemetry alone (focus swap shows reason 2).

---

## 3. Tests

| Suite | Result |
|---|---|
| `:shared:jvmTest` | **307 tests, 0 failures** |
| `:shared:testDebugUnitTest` | **249 tests, 0 failures** |
| `:shared:testReleaseUnitTest` | **249 tests, 0 failures** |
| `:shared:allTests` | **805 tests, 0 failures** |
| `:app:testProdDebugUnitTest` | **1,292 tests, 0 failures, 1 pre-existing skipped** (`GeniusTest`) |
| **Total** | **2,097 tests, 0 failures** (vc63: 2,085) (+12 = `Djmix22AppTest`) |

- `Djmix22AppTest`: 12 tests — (i) playing-through-handoff is restored, user-paused never auto-resumed (both focus states), non-playing never started, no-focus no restore; (iii) loop finish routing: handed-off + took-over + clean gate → `FINISH_LOOP` (both phases), pre-handoff/un-took-over/dirty/unprovable gate → `EMERGENCY_TAIL`, classic audible spare → tail (djmix12 invariant kept), inaudible spare → `FINISH`.
- Full-gate note: the first run hit one unrelated load flake (`QueueShuffleTierTest` benchmark "restore < 50 ms", passes in isolation / same-day rerun — a box-load timing flake in a pre-existing test; untouched). The second full run is green.

---

## 4. Artifacts

| Artifact | Lines / Bytes | SHA-256 |
|---|---|---|
| `bitchord-djmix22.patch` (vc63→vc64 delta, 4 files) | 607 / 27,149 | `ffb62fc3aad8564f558ca57fe8a1f3092d582ec3c74e39429ad13dc35e0d0483` |
| `bitchord-djmix22-full.patch` (from `dc78e0d`, 61 files) | 11,880 / 607,284 | `73b950246c9a95e5375f222cc569cb33436c0385c5504d3010150ca20380a38b` |

Lineage: `HEAD=dc78e0d`, `git diff --cached` **6085** before and after (verified). `git add -N` only the new `Djmix22AppTest.kt`. Delta rebuilt via the vc62 reconstruction (`/tmp/vc62-tree` `be44155`, + djmix21 9-file overlay committed as vc63), overlaid with the 4 vc64 files — `git apply --check` clean on the vc63 tree; the 63→64 version lines verified.

---

## 5. User listening gate (ears only, no audio claimed yet)

Order:
1. **This Is What You Came For (Calvin Harris ft. Rihanna) → One Kiss (Calvin Harris x Dua Lipa)** — the vc63 bug pair. Song 2 must now *keep playing through the handoff* — no abrupt pause 1–2 s in, no ~70 s silence, incoming vocal *crisp from the first line, not muffled*.
2. **Beautiful (Bazzi) → Never Let Go (LNGSHOT)** — 8 bars, no "too early", outro plays out.
3. **Drip (ATEEZ) → UP (ATEEZ)** — no chipmunk, no stutter, no freeze hang.
4. **Technologic (Daft Punk) → Guess (Charli XCX)** — no decline, no vocal mush.

Queue them if absent (agent must NOT queue/manipulate). Record internal audio, report per-pair: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? Any dead zone >8 bars or mid-vocal cut? For pair 1 specifically: did song 2 keep playing past ~1.7 s after the blend starts (the vc63 failure point)?

Status: **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**.

---

## 6. Do-not list

- No `adb uninstall` / `pm clear` / queue edits; `install -r` only.
- No MCP `box` for `bk-android`; Instabox CLI only.
- No `sleep` loops for builds (use `instabox jobs wait`).
- Never edit/delete prior handovers/APKs/patches/proofs.
- Never touch Signalsmith headers; scalars-only cross-track scoring always.

*End of handover. vc64 is live on the phone — the verdict belongs to your ears.*
