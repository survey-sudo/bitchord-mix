# DJMIX52 — Step 1 baseline: "Before (vc93)" on-device evidence

Captured 2026-11-24 (WIB) on POCO F6 / SM8635, serial `adb-1439ebea`,
package `com.music.bitchord.loopfix`, **vc93 = versionCode 93 / versionName 2.3.0-djmix51**
(`lastUpdateTime=2026-11-24 03:43:03`). No uninstall, no `pm clear`, no git or source-tree writes.
No fast-forward was used — both transitions happened naturally.

## Files (all under `/home/hfzhkn/Bitchord/evidence/`)

| File | Lines | Bytes | Role |
|---|---:|---:|---|
| `vc93-existing-telemetry.jsonl` | 1,628 | 778,535 | pre-existing backup (prior sessions) |
| `vc93-baseline-telemetry.jsonl` | 1,726 | 828,699 | pulled after this run; **63 new lines** (1,664–1,726) |
| `vc93-baseline-logcat.txt` | 37 | 4,512 | filtered logcat (tags below) |
| `vc93-baseline-monitor.txt` | — | — | 16 live polling checks (media_session + telemetry tail) |

Phone telemetry count: **1,663** immediately before force-stop → **1,726** at pull.

## Step 1 — logcat TAG constants (source of truth, differs from task brief)

Verified by grep in `/home/hfzhkn/bitchord-unified/` (read-only):

- `CrossfadeController.kt:8256` → `const val TAG = "BitChordCrossfade"` (**not** `CrossfadeController`)
- `TrackAnalyzer.kt:2370` → `const val TAG = "BitChordTrackAnalyzer"` (**not** `TrackAnalyzer`)

Other relevant tags: `BitChordChoreo` (ChoreoSessionGlue/ChoreoRendererBridge), `BitChordLoopMix`
(LoopMixSession/LoopMixRenderer), `BitChordPhrase`, `BitChordNPU`, `BitChordTransitionFilter`,
`AutomixScript`, `AutomixCrash`, `BitChordLocalAudio`, `BitChordAudioDecoder`,
`VersionAudioAligner`, `BitChord`. `AnalysisLog` is not a log tag: it is installed as
`{ msg, err -> Log.w(TAG, msg, err) }` in `TrackAnalyzer.kt:261`, so its messages (including
`stem chunk i/n ... ms`) land under **`BitChordTrackAnalyzer`** as `W`. `BitChordSmart` and
`BitChordFade` do not exist as TAG constants in this tree; they were included in the filter anyway.

All of these were captured. **Release ProGuard strips `Log.v/d/e`; only `Log.i/w` survive**, so
the 37-line logcat is expected and the telemetry JSONL is the primary quantitative source.
Consequently `buildStart` and `modelLock` wait lines do not exist as observable events in this
release build (no `buildStart` key in 1,726 events; no `modelLock` line in logcat). Their effects
are measured below through the surviving proxies.

## Capture timeline (epoch ms / local time)

| Event | Epoch ms | Local |
|---|---:|---|
| Baseline telemetry count (1,663) + volume → 1 + `logcat -c` + force-stop | 1795472137462 | 05:15:37.462 |
| Background logcat started (`adb logcat -v threadtime -s …`) | 1795472142xxx | 05:15:42 |
| Cold start (`monkey -p … -c android.intent.category.LAUNCHER 1`) | 1795472152261 | 05:15:52.261 |
| `dispatch play` | 1795472155308 | 05:15:55.308 |
| Cold-start telemetry `seq=1` | 1795472152007 | 05:15:52.007 |
| **Transition 1 fire** (`seq=24`, classic) | 1795472235793 | 05:17:15.793 |
| **Transition 2 fire** (`seq=47`, classic) | 1795472431724 | 05:20:31.724 |
| `dispatch pause` + volume → 4 + logcat stopped + telemetry pulled | ~1795472560000 | 05:22:40 |

Final state verified: `PAUSED(2)`, `description=Cold Water (feat. Justin Bieber & MØ), Major Lazer`,
media volume index **4**.

## Cold-start session (telemetry lines 1664–1726, `seq=1..63`, attempt `84469215-…`)

Tracks: `s1ovNoLD_mo` "Rescue Me (Vigel Club Mix)" (186.121 s) →
`I6etAHfECXc` "Stay" (210.101 s) → `jEBKFudxGTs` "Cold Water" (185.361 s) → `lP4oM-YEvXk`.

**Transition 1** (`s1ovNoLD_mo → I6etAHfECXc`):
- `seq=11` crown-loop refused: `outgoing: 77 loops: 0 quiet, 0 sung (lyrics), 0 sung (mask), 77 unmeasured`; `seq=12` `phrase-null`.
- `seq=14` native-bridge plan **accepted** at 1795472190838.
- `seq=17` native-bridge refused at **stage=prepared**, `buildGeneration=97`, `reason=handoff_pcm_before_protected_event` after 13.8 s.
- `seq=19` `phase=LOOP_ARMING` → `seq=20` `mix.complete … [fallback]` after **4,923 ms** in LOOP_ARMING.
- `seq=21` classic accepted → `seq=22 ARMING` → `seq=24` **fire**: `deltaMs=+77`, `plannedStartMs=176721`, `actualStartMs=176798`, `mode=classic`, **`choreo` absent**; 9,485 ms from classic accept to fire.
- `seq=27 exit.tail mode=planned reason=planned-fade-done`; `seq=28 complete [terminal]`.

**Transition 2** (`I6etAHfECXc → jEBKFudxGTs`):
- `seq=30` crown-loop refused: `outgoing: 75 loops: 2 quiet, 70 sung (lyrics), 3 sung (mask), 0 unmeasured`; `seq=31` `phrase-null`.
- `seq=33` native-bridge accepted at 1795472243976; `seq=41` refused at prepared, `buildGeneration=107`, `handoff_pcm_before_protected_event` (~162 s later).
- `seq=42 LOOP_ARMING` → `seq=43 complete [fallback]` after **7,770 ms**.
- `seq=44` classic accepted → `seq=45 ARMING` → `seq=47` **fire**: `deltaMs=+16`, `planned=195871`, `actual=195887`, `mode=classic`, **`choreo` absent**; 2,545 ms accept→fire.
- `seq=50 exit.tail planned`, `seq=51 complete [terminal]`.

After that, `seq=53..59` is a new attempt for `jEBKFudxGTs → lP4oM-YEvXk` (`waiting on analysis`,
`natural-end:before-smart-analysis-fallback-window`, `phrase-null`, then a crown-loop plan accepted)
but it was paused at 130 s and produced no fire. **0 `mix.arm.death` in the cold-start session** —
the two failed native arms surfaced as `mix.plan.rejected` + `mix.complete [fallback]`, not as arm deaths.

## Logcat evidence (all surviving `I`/`W` lines)

| Signal | n | Values (ms) |
|---|---:|---|
| `stem chunk 1/1 (scnet-musdb18-int8w)` | 4 | 9,079 / 9,010 / 9,008 / 9,089 (mean **9,046.5**) |
| `stems scnet-musdb18-int8w: 1 chunk(s) … in Nms` | 4 | 9,097 / 9,030 / 9,028 / 9,106 (mean **9,065.2**) |
| `mel Nms (1113/1488 frames)` | 13 | 52–70 (mean **55.4**) |
| `infer Nms` | 13 | 1,275–2,104 (mean **1,354.4**; 12× ~1.28 s, 1× 2,104) |
| `loop build produced no decks` | 6 | long decodes 8,045 / 7,908 / 8,917 / 9,514 / 9,417 / 9,191 (mean **8,832**) |
| `handoff status=UNKNOWN reason=handoff-mask-coverage` | 6 | `outCoverage`/`inCoverage` null on one side in every case |

Also `BitChordNPU disabled (QNN_ENABLED=false): CPU execution provider` and
`selftest … verdict=SKIPPED … detail=backgrounded` — NPU inference was never active;
all analysis ran on CPU under `modelLock`.

## Prior sessions (whole pulled file, 1,726 events)

- **Session 4** (`44404844-…`, lines 65–321, 127 attempt-bearing events): 3 fires — 2 `crown-loop`/`loop`/rung `Loop` (`deltaMs` −775, −863) and 1 `native-bridge`/`loop`/`Natural handoff` (−964); **1 `mix.arm.death` = `scrub-cancelled`** (`phase=ARMING`, `msSincePlan=1,590`). `waiting on analysis` ×19; `before-smart-beatmix-fallback-window` ×7. `choreo` on **0/3** fires.
- **Session 5** (`a7a4540a-…`, lines 324–734, 208 events): 10 fires — 7 `native-bridge`/`loop` (rungs Drop 1, Natural handoff 4, Beat blend 2; `deltaMs` −710…−887) and 3 `classic` (`+68/+75/+86`); **0 `mix.arm.death`**, but 2 `emergency-retire-guard` and 2 `handoff_out-cut-measured-vocal` fallback completes. `waiting on analysis` ×9; `natural-end:before-decline-to-mix` ×14; `choreo` on **0/10** fires.
- **Global**: 28 fires (13 `classic`/classic, 13 `native-bridge`/loop, 2 `crown-loop`/loop) = **21.7 fires per 100 `mix.dump`** (129 dumps); fired "Natural handoff" rung **5/28 = 17.9 %**.
- `planLoop`/loop-plan null reasons (349 `mix.plan.rejected`): natural-end/guard-window 86, scrub 84, sung/unmeasured loop veto 59, **waiting on analysis 56**, `phrase-null` 32, `handoff_pcm_before_protected_event` 4, `handoff_out-cut-measured-vocal` 3, plus `next is … plan wanted …` queue drift. No literal `waiting on lyrics`, `sung veto`, `no later bar`, or `loop too near playhead` strings appear in this release's telemetry; the equivalent sung/lyrics evidence is the `outgoing: N loops: … sung (lyrics/mask) …` veto family.
- `mix.arm.death` reasons (5 total): `build-missed-splice` ×2, `scrub-cancelled` ×1, `arm-expired` ×1, `outgoing-ran-on-while-arming` ×1. Plus a separate `mix.arm.expired` event ×1.
- **`choreo` key appears 0 times in all 1,726 events**, so executed-move attribution is absent in vc93.

## Before (vc93) — baseline table for D2–D5

Mapping follows Master Plan §0 definitions: **D2** = no-mix / arm deaths / build failures (and loop-plan rejections); **D3** = SCNet chunk timings on analysis thread, build latency, and analysis contention; **D4** = `choreo` move attribution; **D5** = transition mode/path split (classic vs loop vs v2-choreographed) and deadline-vs-arming.

| ID | Diagnostic (Master Plan §0) | vc93 baseline (measured) | Evidence |
|---|---|---|---|
| **D2** | No-mix rate, arm deaths & build failures | **28 fires / 129 `mix.dump` (21.7 per 100 dumps)**; **6/6 loop builds `"loop build produced no decks"`**; **349 `mix.plan.rejected`** (`planLoop == null`: guard/natural-end 86, scrub 84, sung/unmeasured loop veto 59, `waiting on analysis` 56, `phrase-null` 32, `handoff_pcm_before_protected_event` 4, `handoff_out-cut-measured-vocal` 3, plus queue drift). Cold start: **0/2 native arms succeeded** — both `native-bridge` arms (`buildGeneration` 97, 107) rejected at `stage=prepared` (`handoff_pcm_before_protected_event` after 13.8 s / ~162 s) and fell back to classic after **4,923 ms** / **7,770 ms** in `LOOP_ARMING` (`mix.complete [fallback]`; **0 `mix.arm.death` recorded** for those); `crown-loop` refused 3/3 (77 unmeasured; 75 loops 70 sung; 1 `waiting on analysis`) + 2 `phrase-null`. Global **`mix.arm.death` = 5** (`build-missed-splice` 2, `scrub-cancelled` 1 [Session 4 `phase=ARMING`, `msSincePlan=1,590`], `arm-expired` 1, `outgoing-ran-on-while-arming` 1) + **1 `mix.arm.expired`**; Session 5 also hit 2 `emergency-retire-guard` and 2 `handoff_out-cut-measured-vocal` fallback completes; 6 `handoff status=UNKNOWN reason=handoff-mask-coverage` (`outCoverage`/`inCoverage` null on one side). | logcat lines 15–18, 26, 28, 31–35; telemetry `mix.dump`, `mix.plan.rejected`, `mix.arm.death`, `mix.arm.expired`, `mix.complete`, `mix.state`, `rung=phrase-null` |
| **D3** | SCNet chunk timing on analysis thread, build latency & analysis contention | SCNet `stem chunk 1/1 (scnet-musdb18-int8w)` **9,008–9,089 ms** (`n=4`: 9,079 / 9,010 / 9,008 / 9,089; mean **9,046.5 ms**; 4/4 only **1 chunk**, not 3); full stem build `stems scnet-musdb18-int8w` **9,028–9,106 ms** (`n=4`: 9,097 / 9,030 / 9,028 / 9,106; mean **9,065.2 ms**). Long-decode / build latency (`loop build produced no decks`): **7,908–9,514 ms** (`n=6`: 8,045 / 7,908 / 8,917 / 9,514 / 9,417 / 9,191; mean **8,832 ms**); cold-start classic accept→fire: 9,485 ms (T1), 2,545 ms (T2). Analysis thread contention: **`waiting on analysis` 56/349 (16.0 %) plan rejections** (2 in cold start, 19 Session 4, 9 Session 5); CPU-only inference (`BitChordNPU disabled (QNN_ENABLED=false)`, `selftest … verdict=SKIPPED … detail=backgrounded`) serialized under `modelLock`; `mel` **52–70 ms** (`n=13`, mean **55.4 ms**) + `infer` **1,275–2,104 ms** (`n=13`, mean **1,354.4 ms**; 12× ~1.28 s, 1× 2,104 ms). ProGuard release caveat: `buildStart` and `modelLock` wait lines are stripped (`Log.d` removed; no `buildStart` key in 1,726 events) — `plannedStartMs/actualStartMs/buildGeneration` and `waiting on analysis` are the surviving proxies. | logcat lines 2–6, 12–24, 26, 28, 30–37; telemetry `mix.plan.rejected`, `mix.fire` |
| **D4** | Executed-move attribution (`choreo`) | **`choreo` on 0/28 fires** (`choreo` key appears **0 times in all 1,726 telemetry events**: 0/3 Session 4, 0/10 Session 5, 0/2 cold-start; `choreo` absent on both cold-start classic fires `seq=24` and `seq=47`); no `BitChordChoreo` move lines in logcat. | telemetry `mix.fire` (1,726 events); logcat (`BitChordChoreo` 0 lines) |
| **D5** | Mode/path split (classic vs v2-choreographed) & deadline-vs-arming | **13/28 classic** (`classic`/classic, `deltaMs` +12…+285 overall, incl. +77 [T1 `seq=24`, `plannedStartMs=176721`, `actualStartMs=176798`] and +16 [T2 `seq=47`, `planned=195871`, `actual=195887`] in cold start), **13/28 `native-bridge`/loop** (`deltaMs` −710…−964; Session 5 rungs: Drop 1, Natural handoff 4, Beat blend 2; overall "Natural handoff" rung **5/28 = 17.9 %**), **2/28 `crown-loop`/loop** (`deltaMs` −775, −863; rung `Loop` in Session 4); **0/28 v2-choreographed**. Cold-start exit legs: both classic fires completed cleanly (`exit.tail planned` → `complete [terminal]`). | telemetry `mix.fire`, `mix.complete`, `mix.state` |

## Caveats / chain of custody

- `vc93-baseline-telemetry.jsonl` contains all prior sessions plus the new one; the cold-start run is lines **1,664–1,726** (`ts >= 1795472152007`). Prior backup `vc93-existing-telemetry.jsonl` (1,628 lines) was not modified.
- `adb` used: `/home/hfzhkn/.local/bin/adb`. Device never wiped; app never uninstalled.
- Volume was set to index 1 before capture and restored to index 4 after; playback left paused.
- Background logcat was terminated cleanly by PID (an initial `pkill -f` pattern matched its own shell and returned 143; no capture data was lost — verified process dead and file complete).
- No source file inside `/home/hfzhkn/bitchord-unified/` was modified; no git commands were run there.
