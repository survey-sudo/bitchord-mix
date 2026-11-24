# AE3 progress ledger — BitChord Automix Engine 3

**Release:** `1.10-djmix53` / versionCode **95**, engine version **AE 3.0.0** (shown in Settings → About and in every telemetry record).
**Base:** `djmix52` `b3408283` → branch `ae3` (merged to `master` at release). This ledger lives on `ae3`.
**Master plan:** `BitChord Automix Engine 3 (AE3): the baseline master plan` (Part A = fix djmix52, Part B = mature baseline, Part C = milestones M0–M11, Part D = untangle pass).
**Rules:** one row per numbered item in Parts A and B (see Table 2); update with every commit; **an item without evidence is not done**; statuses strictly `todo` / `doing` / `done` / `blocked`; commit separation — `refactor:` preserves behaviour, `feat:` / `fix:` carry intentional Part A/B changes, `chore:` / `docs:` for docs, ledger and tooling.

## Table 1 — Milestone Gate Tracker

| milestone | status | commit | evidence | notes |
|---|---|---|---|---|
| M0 Baseline capture | blocked | this commit | `docs/AE3_BASELINE.md`, `../Bitchord/evidence/ae3-m0/m0-metrics.json`, `../Bitchord/evidence/ae3-m0/analyze-djmix52-vc94-combined.md` | Partial vc94 (`1.10-djmix52`) Festival capture (23 timed transitions, 1,088 telemetry events since install): 95.7% overlap proxy (22/23), 39.1% `v2-native` (9/23 timing; 10/19 `mix.fire` classic), analysis p95 2,750 ms (live logcat `infer` mean 949 ms, CPU only), 5 Festival recipes / 9 FX types, `fxCount>=2` 78.3%, `dev.sfg.orchard.npu.v73` confirmed installed. **Blocked:** Club (0), Subtle (0), and 20s-stress (0) require in-app level switch/seek not exposed via headless ADB. |
| M0.5 Untangle inventory | todo | — | — | Part D2 steps 2–3 inventory first, no code changes; fills Table 3 M0.5 row, writes `docs/untangle/DECISION_LOG.md` and `deps.svg`. |
| M1 Contracts + MixMachine | todo | — | — | `djmix-core`, B1.3 contracts, B2 reducer, B1.2 item 5 invariants; gate 100 percent table cells, 10k property green, core tests under 60 s. |
| M2 Analysis + store | todo | — | — | Part A §3.1 plus B3 store and migration, B3.2 queue job; gate 4-min track at or under 1.5 s CPU, store survives restart. May run with M3. |
| M3 One brain | todo | — | — | Part A §2 plus §3.2 plus §4 in preparer on `djmix-core` planner, `MixExecutor` driving `MixMachine`; gate §10 rows 100 percent overlap, 0 broken, 100 percent frozen, 0 mid-vocal on 24 transitions. |
| M4 Render + recipes v2 + audition | todo | — | — | Part A §5, B5 JSON, linter, 6 new recipes, Part A §9 plus B6.3; gate audition uploaded, ratings received, `mixq` green. |
| M5 Speed | todo | — | — | Part A §3.3 stems, B3.2 library job; gate READY p50 at or under 6 s / p95 at or under 15 s cold, warm at or under 3 s. |
| M6 NPU | todo | — | — | Part A §6 with Orchard add-on, B4 registry and backends; gate `ep=QNN` self-test PASS, NPU ms below CPU, `docs/NPU.md`. May run with M5. |
| M7 UX | todo | — | — | Part A §7 chip, B9 Inspector with timeline, History, Settings, first-run; gate owner screenshot review uncluttered. |
| M8 Diagnostics + learning | todo | — | — | B7 Flag / replay / telemetry v3 / Developer settings, B8 learning; gate flagged bundle replays bit-exact, learning shifts after 3 thumbs-down. |
| M9 Hardening + untangle sweep | todo | — | — | `CrossfadeController` at or under 2,500 lines, B13 dead-code removal, D2 step 5 sweeps, B6.5 soak, B6.4 perf, B15 budgets; gate soak plus perf plus size plus D4 targets. Tests done last here. |
| M10 Sync + release engineering + docs | todo | — | — | B10, B11, B12; gate `hooks-check` green, dry-run at or under 5 conflicts, release pipeline APK, all B12 docs present. |
| M11 Release `1.10-djmix53` (AE 3.0.0) | todo | — | — | C3 checklist; gate owner final listening verdict. |

## Table 2 — Item Ledger (Parts A and B, plus Part D)

Every numbered / lettered / bulleted item is a row. This bootstrap step marks only ledger/skill/ADR items done; all else stays `todo`.

| item | status | commit | evidence | notes |
|---|---|---|---|---|
| A§0.1 Ears before APK | todo | — | — | Run audition harness §9 before any owner APK; upload WAVs to Drive; fix bad offline recipes first. |
| A§0.2 Numbers or it did not happen | todo | — | — | `docs/DJMIX53_RESULTS.md` mandatory, §10 table from real telemetry; never done with an empty row. |
| A§0.3 One brain | todo | — | — | Every decision from PairPreparer §2; adding a fallback that re-plans in CrossfadeController is the removed bug. |
| A§1.1 Planning too long | todo | — | — | Evidence recorded: full-analysis wait, restart on every update, SCNet FP32 9.0 s per 11 s chunk, Beat This 1.35 s per 30 s chunk, UMX still in analysis. |
| A§1.2 Countdown zero, nothing happens | todo | — | — | Evidence: shown on plan exists, watchdog plus 1.2 s, rescue at playhead or classic. |
| A§1.3 Wrong part / abrupt / sudden | todo | — | — | Evidence: vc91 ladder first at transition time, handoff rejects at prepared, rescue at playhead, Festival low-confidence fallback to F2. |
| A§1.4 Effects lacking | todo | — | — | Recipes only on rare v2 path, thin riser/brake/roll/flanger, nobody listened to a render. |
| A§1.5 Visuals cluttered | todo | — | — | MixViewPopup plus 650 lines; headline packs rung, seconds, tempo, lock tags. See §7. |
| A§1.6 NPU not implemented | todo | — | — | No Nirovi/Syvenu, no add-on, `ep=CPU (djmix52; NPU runtime unavailable)`; built from Kite/Lark text. |
| A§1.7 What works, keep untouched | todo | — | — | Muffle-in (S3) and the 1.10 merge per owner listening. |
| A§2.1.1 Preparer only decider | todo | — | — | Preparer alone chooses exit, entry, geometry, recipe, stems; controller never calls planners to decide. |
| A§2.1.2 Geometry as generators | todo | — | — | Crown loop, bridge, straight propose candidates; preparer scores with recipes and freezes best. |
| A§2.1.3 Generation id, delete plan== | todo | — | — | Replace snapshotForBuild / preparedStraightFor with `preparer.frozen(outId, inId, generation)`; delete identity compares. |
| A§2.1.4 Handoff qualifier into scoring | todo | — | — | `qualifyMusicalHandoff`, `handoff_pcm_*` move into preparer scoring; reject before freeze, never at build. |
| A§2.1.5 Classic only when unavailable | todo | — | — | `startFade` only without native renderer; log `mix.classic.reason` every use. |
| A§2.2.1 Commit point plus MOVED | todo | — | — | 4 bars before frozen start, min 6 s; must be READY or move to next eligible phrase boundary and re-freeze. Telemetry `mix.moved`. |
| A§2.2.2 Last-resort musical exit | todo | — | — | No later boundary: echo-out or filter-wash on last downbeat of last phrase, IN on intro downbeat, no-stem recipe. Never start at now. |
| A§2.2.3 Delete watchdog and rescue | todo | — | — | Delete `checkShownWatchdog` plus 1.2 s rule and `straightRescue` position plus 0.45. |
| A§2.3.1 Countdown only READY / ARMED | todo | — | — | PREPARING shows progress only; `mix.countdown.broken` target 0, RUNNING within plus-minus 50 ms of 0. |
| A§2.3.2 Audio thread does no work | todo | — | — | No decode, inference, allocation at transition time; `nativePrepare` on prep thread during READY, arming swaps pointer. |
| A§3.1.1 Bundle models as assets | todo | — | — | `nirovi_int8.ort` sha `7454d98a...fed489387`, 7,948,784 B; `syvenu.ort` 2,349,304 B pin sha after download; source Orchard repo. |
| A§3.1.2 BeatTracker Nirovi | todo | — | — | `MODEL_ASSET nirovi_int8.ort`, 1500-frame border 6 zero-padded, 2 intra-op threads; mel already identical; keep Beat This download fallback. |
| A§3.1.3 VocalPresence Syvenu | todo | — | — | Same beat spectrogram, 1500-frame 250 borders, 0..1 at 50 fps; feeds mask, energy, StructureV2; remove UMX vocal from analysis, keep UMX as 2-stem fallback. |
| A§3.1.4 Gate 1.5 s | todo | — | — | 4-min track fully analysed in at or under 1.5 s CPU; log `analysis.ms model=nirovi`. |
| A§3.2.1 Plan on head / tail | todo | — | — | Plan as soon as head/tail exists; full refines only; re-plan only if exit or entry moves at least 1 bar or recipe invalid. |
| A§3.2.2 Never abort, containment | todo | — | — | Let running decode / SCNet finish; reuse regions and stems by containment slice, not decode. |
| A§3.2.3 Start at next-known | todo | — | — | Track start, queue edit, or 50 percent if queue was unknown; not at 15 s timeout. |
| A§3.2.4 Locks and second executor | todo | — | — | Keep separate model / stem locks; SCNet on second single-thread executor. |
| A§3.3.1 Dynamic-int8 SCNet | todo | — | — | Export `scnet_dyn_int8.onnx` via `tools/models/quantize.py`, pin sha; target at or under 3 s per 11 s chunk at 4 threads; audition decides int8 retention. |
| A§3.3.2 Required stems only | todo | — | — | Add `Recipe.requiredStems`; separate only overlap window plus-minus 1 bar; one pass gives 4 stems, savings in decks and window. |
| A§3.3.3 Separate ahead, disk cache | todo | — | — | Separate likely next pair once frozen; 160 MB cache keyed `trackId + window`. |
| A§3.3.4 No-stem variant at commit | todo | — | — | Same recipe no-stem variant (isolator EQ); no level downgrade, no move for stems. |
| A§4.1 Exit rules | todo | — | — | First boundary after last chorus/drop in outro else vocal under 0.25 for next phrase; never mid-vocal at plus-minus 1 beat; never in build; leave one phrase after swap. |
| A§4.2 Entry rules | todo | — | — | Intro first downbeat or boundary before first vocal; drop downbeat for F1/C7/F8; pre-roll caps 32 Club, 64 Festival; skip silent intros. |
| A§4.3 Pair score | todo | — | — | Weights tempo, Camelot, energy, vocal overlap target 0, bass; penalty per broken rule; variety last 3; Inspector top reason. |
| A§4.4 Hard rejects | todo | — | — | Mid-vocal exit; mid-vocal entry at Subtle/Club; both basses above minus 6 dB over 1 beat; abrupt without genre / tempo jump. |
| A§4.5 Abrupt rationing | todo | — | — | F2/F7 only at absolute BPM change over 8 percent or genre change, at phrase end, never fallback; Festival fallback F2/F5 goes to F5 Tunnel or C2. |
| A§5.1 Loop | todo | — | — | 1/2/4/8 bars on boundary, at or under 8 ms equal-power seam, zero drift vs knot clock; C5 Loop extend holds OUT outro 1–2 phrases. |
| A§5.2 Roll | todo | — | — | Last 2 bars 1, 1/2, 1/4, 1/8, 1/16 on beat, HPF 200 Hz to 2 kHz and plus 2 dB, hard stop on 1; replace `LoopMix.rollLoopBeats`. |
| A§5.3 Sweep (filter) | todo | — | — | Resonant HPF 20 Hz to 1.2–2 kHz over 8–16 bars exp, Q 0.7 to 1.6 no spike over plus 6 dB; mirror LPF demuffle on IN; keep muffle curve as reference. |
| A§5.4 Flanger | todo | — | — | Through-zero jet, LFO 1 per 2 bars tunable 1/2–4, depth 0.5–4 ms, feedback 0.55–0.7, stereo 90 degrees, wet 0 to 0.6 last 4 bars with HPF rise. |
| A§5.5 Phaser | todo | — | — | 6–8 all-pass stages, LFO 1 per bar tunable, feedback 0.6, stereo 90 degrees, wet 0 to 0.7 across tunnel. |
| A§5.6 Riser plus Impact | todo | — | — | 3 layers noise sweep 300 Hz to 8 kHz, uplifter octave, snare roll last 2 bars, 1-beat reverse-reverb tail, impact thump minus 6 dBFS on 1. Rewrite `dj_fx.h` Riser plus new Impact. |
| A§5.7 Brake plus tape-stop | todo | — | — | Exp rate 1.0 to 0 over 1–2 beats, pitch follows rate, LPF 18 kHz to 800 Hz, 10 ms fade; tape-stop variant with wow; phrase end only. Rewrite on pre-stretch pointer. |
| A§5.8 Spinback | todo | — | — | Reverse to about 3x over 1/2 beat, 1-beat gap, IN on 1. |
| A§5.9 Echo out | todo | — | — | Ping-pong 1/2 or 3/4 beat, feedback 55–70 percent, wet HPF 250 Hz LPF 6 kHz, throw on last beat. |
| A§5.10 Gate / stutter | todo | — | — | 1/8 to 1/16 trance gate, swing 0–10 percent, depth 0 to 1 over 2 bars, release on 1. |
| A§5.11 FX coverage unit test | todo | — | — | Club needs loop, roll, sweep, flanger, phaser, echo; Festival adds riser, brake, spinback, gate; test asserts coverage. |
| A§5.12 Festival variety | todo | — | — | Over any 6 Festival transitions at least 4 distinct recipes and at least 5 distinct FX types. |
| A§6.1 Orchard add-on | todo | — | — | Use installed `dev.sfg.orchard.npu.v73` when present; own add-on later. |
| A§6.2 Bridge dlopen | todo | — | — | `npu_bridge.cpp` dlopens `liborchard_qnn.so` from add-on `nativeLibraryDir` (`orchard_qnn_open / run / close`). |
| A§6.3 QNN models | todo | — | — | Open `nirovi_qnn.onnx` and `syvenu_qnn.onnx` copied from add-on assets. |
| A§6.4 Per-device cache | todo | — | — | Context cache per device. |
| A§6.5 Self-test parity | todo | — | — | Parity vs CPU Nirovi on real window, beat-peak agreement at least 95 percent within plus-minus 1 frame. |
| A§6.6 Notice and status | todo | — | — | One-time Preparing NPU notice; Settings shows NPU Ready Hexagon V73; log shows `ep=QNN`. |
| A§6.7 CPU fallback | todo | — | — | CPU on any failure. |
| A§6.8 Gate NPU below CPU | todo | — | — | NPU per-track ms lower than CPU and outputs agree. Done after §3.1 green. |
| A§7.1 Player chip states | todo | — | — | One chip: PREPARING Preparing mix 62 percent; READY/ARMED recipe plus mm:ss; MOVED Moved to 1:12 3 s; RUNNING Mixing; LATE Ending with echo-out. |
| A§7.2 Remove tags, no buttons | todo | — | — | Remove rung, tempo, locked tags from headline; no new player buttons. |
| A§7.3 Inspector sheet | todo | — | — | Order: titles, level, recipe plus reason; readiness bar Analysis / Plan / Stems / Renderer; timings; fallback reason; Try another mix only in PREPARING / READY with at least 20 s left. Details hides rest. |
| A§7.4 Settings Automix | todo | — | — | DJ level, Stem mixing, NPU status row, Inspector on/off; nothing else new. |
| A§8.1 Version bump | todo | — | — | `1.10-djmix53`, versionCode 95, `bitchordUpstreamBase 1.10`. |
| A§8.2 Install rule | todo | — | — | `adb install -r` only; never uninstall or clear data. |
| A§8.3 Keep invariants | todo | — | — | vc80 loop / handover, sticky pause, surviving-deck rule, bit-identical native probes when v2 setters unused. |
| A§9.1 Host render CLI | todo | — | — | Extend `native/loopmix/loop_mix_renderer_ab_main.cpp` with `--render script.json --out file.wav`; same renderer code as app. |
| A§9.2 JVM exporter | todo | — | — | `:shared:auditionExport` or test with `-Daudition=1`; ffmpeg decode, same analysis and scoring, force recipes, SCNet stems. |
| A§9.3 Showcase six pairs | todo | — | — | EDM-EDM, house-house, pop-pop vocals, hip-hop-hip-hop, pop-EDM jump, ballad-ballad; every allowed recipe plus auto; `pair3_F1_riser-drop.wav` naming plus `index.md`. |
| A§9.4 Self-check | todo | — | — | True peak at or under minus 1 dBTP; no NaN; LUFS within plus-minus 2 except Festival drops; Syvenu vocal overlap at or under 2 s. List failures in `index.md`. |
| A§9.5 Upload audition | todo | — | — | Zip to Drive as `djmix53-audition/`; report link; owner picks; tune, re-render, then build APK. |
| A§10.1 Festival 12 | todo | — | — | Cold start, Festival, 12 natural transitions, skip to about 60 s before exit; telemetry plus logcat. |
| A§10.2 Club 12 | todo | — | — | Club under same protocol. |
| A§10.3 Subtle 12 | todo | — | — | Subtle under same protocol. |
| A§10.4 Stress run | todo | — | — | Skip to 20 s before exit, 3 times. |
| A§10.5 Results table | todo | — | — | `docs/DJMIX53_RESULTS.md` before djmix52 vs after djmix53; 9 metrics from `mix.timing`, `mix.fire`, `mix.countdown.*`; targets in master plan §10. |
| A§10.6 Screen recording | todo | — | — | 2-min with internal audio, 2 Festival transitions, one riser drop plus one flanger/phaser; upload with audition zip. |
| A§10.7 Owner report | todo | — | — | Short mobile-friendly 5 items: version/SHA/size/install; results table; audition plus recording links; weak points plus next change; NPU line. |
| B1.1 Modules plus dependency rules | todo | — | — | `djmix-core` KMP pure, `djmix-analysis`, `djmix-render`, `djmix-android`, `app` UI only; `djmix-core` depends nothing; app reads `StateFlow MixUiState` and intents only; only `djmix-android` talks to players and renderer. |
| B1.2.1 MixExecutor owns transitions | todo | — | — | New `djmix-android` owner: arming, starting, running, handover, retiring. |
| B1.2.2 Controller keeps pre-automix only | todo | — | — | Crossfade seconds, gapless, manual skips; every automix decision via `MixExecutor`. |
| B1.2.3 Strangler slices | todo | — | — | Each slice moves one responsibility, soaks (B6.5), deletes old path same PR; no dead-but-reachable legacy. |
| B1.2.4 Size targets | todo | — | — | `CrossfadeController.kt` at or under 2,500 lines; no `djmix-*` file over 1,500 lines except Recipes data; 178 fields become `MixMachine.State` plus handles. |
| B1.2.5 Survive list as tests | todo | — | — | vc80 loop/handover, vc81 ticker/lyric, sticky USER_REQUEST 1, surviving deck never parked, bit-identical probes, 1.10 visual state; encode as tests before moving. |
| B1.3.1 TrackAnalysis v3 | todo | — | — | Beats, downbeats, confidence, BPM, key plus confidence, loudness, energy, vocal 50 fps, structure, model versions; consumers planner, store, inspector. |
| B1.3.2 TransitionPlan v1 | todo | — | — | Frozen exit/entry, geometry, knots, swap downbeat, recipe id plus params, required stems, reasons, top-3 scores, generation id; consumers executor, inspector, diagnostics. |
| B1.3.3 TransitionScript v1 | todo | — | — | Region refs, knots, loop bounds, envelope lanes, FX params, stem refs, loudness; consumers renderer, replay, audition. |
| B1.3.4 MixEvent v1 / MixEffect v1 | todo | — | — | Inputs and outputs of `MixMachine`; consumers executor, tests. |
| B1.3.5 MixTelemetry v3 | todo | — | — | One schema for all automix telemetry; consumers diagnostics, results tables. |
| B1.3.6 Golden bit-identical rule | todo | — | — | `TransitionScript` renders bit-identically device vs host CLI given same PCM; test renders 3 scripts both sides, compares SHA-256. |
| B2.1 States | todo | — | — | IDLE, PREPARING(progress), READY(plan), ARMED(plan), RUNNING(plan, progress), HANDING_OVER, DONE, MOVED(plan, from), LAST_RESORT(plan), SUSPENDED(reason), FAILED(reason). |
| B2.2 Events | todo | — | — | 20: NextKnown, QueueEdited, AnalysisReady, PlanFrozen, RegionsReady, StemsReady, RendererPrepared, Tick, UserSeek, UserSkip, UserPause, UserResume, AudioFocusLost, RouteChanged, Backgrounded, ThermalChanged, RendererError, HandoverComplete, LevelChanged, RemixRequested. |
| B2.3 Effects | todo | — | — | StartPrepare, CancelPrepare, PrepareRenderer, Arm, Start, Retire, Handover, ShowUi, Emit. |
| B2.4 Reducer rules | todo | — | — | Commit/MOVED/LAST_RESORT; countdown only READY/ARMED; seek/skip handover invariants; pause keeps sync; background/thermal cheaper variant never silence. |
| B2.5 Table test 11x20 | todo | — | — | Every (state, event) pair cell with expected outcome. |
| B2.6 Property test 10k | todo | — | — | 10,000 random sequences seed-logged; never broken countdown, never two RUNNING, never Start without RendererPrepared, PREPARING always resolves. |
| B2.7 Replay test | todo | — | — | Recorded on-device event logs replay through reducer, same effects. |
| B3.1 Storage Room | todo | — | — | `djmix.db`: `track_analysis`, `stem_index`, `transition_history`, `preference`; invalidation lazy queue-first; stems capped 500 MB LRU; migrate `AnalysisStore` JSON, `PhraseMaps`, `StemCache` then delete. |
| B3.2.1 Queue job | todo | — | — | On queue change analyse next 5 to FULL, plan plus stem next 2 pairs; background priority while playing. |
| B3.2.2 Library job | todo | — | — | Charging and idle: liked, downloads, recent, newest first; stop under 30 percent battery or thermal at or above MODERATE. |
| B3.2.3 Cold pairs budget | todo | — | — | With both jobs running most transitions start READY; §3 budget becomes the cold-case budget. |
| B4.1 ModelRegistry | todo | — | — | Bundled `models.json`, overridable by `models-v1`; fields id/version/sha/bytes/source/frontend/io/backends/role/minEngine; 8 models listed in B4.1. |
| B4.2 Backend interface | todo | — | — | `InferenceBackend` with `CpuOrt` and `QnnAddon`; QNN only if available, self-test passed, benchmark faster; choice visible in Inspector and Developer settings. |
| B4.3 Own NPU add-on | todo | — | — | Code-less `npu-addon/v73` package, QAIRT jniLibs plus runner, QNN assets, fork-key signed; GHA per arch; `npu-addons.json`; prefer ours, accept Orchard. |
| B4.4 Measured gates | todo | — | — | `docs/NPU.md`: per-model CPU/NPU latency median of 7, cold compile and cached open, self-test agreement, thermal at 10-min soak start/end. |
| B5.1 Recipes as data | todo | — | — | `recipes/*.json` schema recipe v2: id, name, level, family, requirements, length options, placement, steps, no-stem variant, energy delta, abruptness. |
| B5.2 Interpreter | todo | — | — | `djmix-core/recipes` recipe plus context to `TransitionScript`; ChoreoGuard validates every script. |
| B5.3 Linter | todo | — | — | Unit test plus CLI: schema, anchors, splice/handover neutrality, lows never doubled, FX budget, abrupt only where allowed, per-level coverage. |
| B5.4 Recipe Lab | todo | — | — | Developer settings: pick 2 tracks plus recipe/Auto, exit/entry over waveforms, Preview renders 30 s offline; debug live reload from `Android/data/.../files/recipes/`. |
| B5.5 20 plus 6 library | todo | — | — | 20 fixed per §5; add C8 Loop roll out, C9 Filter ping-pong, F9 Backspin into drop, F10 Echo freeze, F11 Riser plus impact cut, S6 Breakdown blend; each audition render plus Inspector one-liner. |
| B6.1 Corpus | todo | — | — | `golden-pairs/` devbox only, never git, `pairs.json`; 40 pairs all genres with 6 jumps, 4 tempo over 8 percent, 4 album, 4 silent intros; owner adds via Flag. |
| B6.2 mixq metrics | todo | — | — | `tools/mixq/` Python on host WAVs; 8 gates per B6.2; `mixq report` HTML per pair/recipe plus aggregate to `docs/quality/AE3-<build>.html`. |
| B6.3 Listening gate | todo | — | — | `tools/audition/` HTML per pair, 1–5 stars, keep/fix/drop, notes to `ratings.json`; Drive `AE3-audition-<build>/`; gate average at least 3.5, no drop votes ship enabled. |
| B6.4 Perf benchmarks | todo | — | — | 6 macrobenchmark metrics on owner phone over ADB to `docs/perf/AE3-<build>.md`; regression over 20 percent fails gate. |
| B6.5 Soak test | todo | — | — | `tools/soak/soak.sh` 2-hour 40-track Festival autoplay with random seek/skip/pause/edit/level/BT/foreground; 0 crashes/ANRs, 0 broken, 100 percent overlap, under 50 MB, no stuck. |
| B6.6 CI | todo | — | — | Core tests, native gates incl `--v2-probe` identity, 10 synthetic in-repo pairs SHA plus `mixq`, APK audit; real corpus via `make ae3-quality` on devbox. |
| B7.1 Flag bundle | todo | — | — | Inspector plus History row menu; zip to `.../files/djmix-reports/<ts>.zip` with Plan, Script, 5-min events, analysis, 40 s FLAC ring capture; share sheet to Drive. |
| B7.2 Replay | todo | — | — | `djmix-render replay <bundle.zip> --out replay.wav` bit-exact given source PCM; MixEvent log through MixMachine. |
| B7.3 Telemetry v3 | todo | — | — | One JSONL schema `engine=3.0.0`, planId, generation, state; rotation 10 MB x 3; `tools/analyze_telemetry.py` produces §10 table. |
| B7.4 Developer settings | todo | — | — | 7-tap About: force level/recipe, grid, Details, diagnostics export, model/backend status plus self-tests plus clear stem cache, Safe mode S1/S5 rollback. |
| B8.1 Signals | todo | — | — | Explicit thumbs up/down; implicit skip within 15 s after mix minus 1, seek back into OUT minus 1, 3 consecutive full plays plus 0.2 each. |
| B8.2 Model bounded | todo | — | — | Per-recipe and per-(recipe, genre) weights in `preference`, bounded Beta/Elo so no recipe exceeds plus-minus 0.3 of score; separate inspectable scorer term. |
| B8.3 Controls | todo | — | — | Settings Automix Learn toggle default on, Reset learned preferences. |
| B8.4 Determinism | todo | — | — | Learning off or reset gives same pick for same pair/level fixed seed; Inspector shows taste offset e.g. plus 0.12 when on. |
| B9.1 Player chip only | todo | — | — | Single chip from A§7; no other automix UI on player. |
| B9.2 Inspector final | todo | — | — | Header, Timeline (waveforms, overlap shade, swap mark, FX lanes, live playhead), Readiness row, Why plus taste, Actions (Try another, thumbs, Flag), Details hidden. |
| B9.3 Mix History | todo | — | — | Settings Automix History: last 100 with time, pair, recipe, level, rating, flag; tap summary, long-press flag/rate. |
| B9.4 Settings reorg | todo | — | — | 7 items: on/off, level with one-liners, Stem mixing, NPU row plus add-on prompt, Learn, History; Safe mode Developer only. |
| B9.5 First run | todo | — | — | One-screen explainer of 3 levels plus chip, no tour. |
| B9.6 Accessibility | todo | — | — | Content descriptions, no color-only info, reduced-motion honoured for timeline. |
| B10.1 Fork hooks | todo | — | — | Every fork change in upstream-owned files is `FORK-HOOK(id)`; `hooks-check.sh` passes in CI. |
| B10.2 fork-owned.txt | todo | — | — | Lists `djmix-*` modules plus fork docs and tools. |
| B10.3 Daily bot | todo | — | — | `upstream-sync.yml` enabled with `SYNC_TOKEN`; ask owner to create it. |
| B10.4 Dry run | todo | — | — | `sync.sh` vs upstream `main` HEAD dry run; report conflicts at or under 5 hotspots; 1.11 under an hour plus soak. |
| B10.5 Versioning base | todo | — | — | `bitchordUpstreamBase` maintained by `sync.sh`; versionName `<upstream>-djmix<N>`. |
| B11.1 Release pipeline | todo | — | — | `release.yml` manual tag `ae3-*`: build, gates, sign, changelog from conventional commits, attach APK + `models.json` + quality HTML. Heavy work stays devbox, linked. |
| B11.2 bumpDjmix | todo | — | — | `./gradlew bumpDjmix` bumps djmix number and versionCode together, refuses dirty tree. |
| B11.3 CHANGELOG | todo | — | — | `CHANGELOG.md` in owner language, one short section per build. |
| B11.4 Rollback | todo | — | — | No downgrade via `install -r`, never uninstall; rollback is Safe mode plus flags plus fix-forward; keep last 3 signed APKs. |
| B11.5 Feature flags | todo | — | — | One `DjmixFlags`: defaults, optional `flags.json` in models release Wi-Fi only SHA-verified, Developer overrides; every v2 kill switch moves here. |
| B12.1 ARCHITECTURE.md | todo | — | — | Modules, data flow, threads, dependency rules, one diagram. |
| B12.2 ENGINE_CONTRACTS.md | todo | — | — | All schemas with examples, versioning policy. |
| B12.3 MIXMACHINE.md | todo | — | — | State and event table with invariants. |
| B12.4 RECIPES.md | todo | — | — | Author, lint, preview, audition; full recipe table. |
| B12.5 QUALITY.md | todo | — | — | Corpus, metrics, gates; how to run `mixq`, audition, perf, soak. |
| B12.6 NPU.md | todo | — | — | Backends, add-on, measured numbers, troubleshooting. |
| B12.7 SYNC.md | todo | — | — | Upstream sync playbook updated. |
| B12.8 RELEASE.md | todo | — | — | Release checklist C3, versioning, rollback. |
| B12.9 TUNING.md | todo | — | — | Symptom to knob table, updated for AE3. |
| B12.10 adr/ 0001–0007 | done | ae3 bootstrap | `docs/adr/0001-one-brain-pair-preparer.md`, `0002-prepared-only-execution.md`, `0003-orchard-models-and-addon.md`, `0004-version-scheme.md`, `0005-mixmachine-reducer.md`, `0006-room-analysis-store.md`, `0007-recipes-as-data.md` | 7 ADRs created this step (10–20 lines each, Context/Decision/Consequences). Full ADR set grows via decisions as needed (B12 requires at least these 7). |
| B12.11 AE3_LEDGER.md | done | ae3 bootstrap | `docs/AE3_LEDGER.md` (this file) | Created this step with Tables 1–3 covering all M0–M11 and A/B items. |
| B12.12 Archive contradictions | todo | — | — | Delete or archive anything contradicting B12 docs, including old handover and parity vc80-era statements. |
| B13.1 Delete vc93 NPU leftovers | todo | — | — | `NpuQnnSession`, `NpuQdqSelfTest`, Maven QNN remnants once replacement ships. |
| B13.2 Delete vc91 rungs | todo | — | — | Unreachable rungs after planner moves into `djmix-core`. |
| B13.3 Delete CountdownStabilizer | todo | — | — | Paths superseded by MixMachine. |
| B13.4 Delete rescue and watchdog | todo | — | — | `straightRescue`, plus 1.2 s watchdog. |
| B13.5 Thread annotations | todo | — | — | `@AudioThread` / `@PrepThread` / `@MainThread` in `djmix-render` and `djmix-android`; lint forbids allocation APIs and `Log` inside audio functions. |
| B13.6 Native warnings/sanitizers | todo | — | — | `-Wall -Wextra` zero new warnings in `native/loopmix` and `native/analyzer`; ASan/UBSan on loopmix host probes as done for analyzer. |
| B13.7 Kotlin size and KDoc | todo | — | — | No `djmix-*` file over 1,500 lines (Recipes data excepted); public APIs get KDoc. |
| B14.1 No secrets in git | todo | — | — | Signing in CI secrets and devbox keystore only. |
| B14.2 Bundles only on Flag | todo | — | — | Diagnostics from owner device written only on owner tap, never automatically. |
| B14.3 Add-on known packages | todo | — | — | Load only from known packages ours-first then Orchard; `nativeLibraryDir` via PackageManager; never arbitrary paths. |
| B15.1 Battery budget | todo | — | — | Background analysis at or under 3 percent per hour playback via `dumpsys batterystats` 1-hour run. |
| B15.2 Renderer budget | todo | — | — | At or under 20 percent of one big core at p95 during a mix. |
| B15.3 Idle budget | todo | — | — | No automix thread awake more than once per 5 s when idle. |
| B15.4 Thermal budget | todo | — | — | Prep pauses at thermal at or above SEVERE, resumes at MODERATE; running mix never stops. |
| B16.1 Engine version rules | todo | — | — | Major schema break or state change; minor new recipes/effects/models; patch tuning/fixes. |
| B16.2 Version plumbing | todo | — | — | Engine version in About, stamped into telemetry and bundles; djmix builds increment independently. |
| Part D — UNTANGLE_SKILL.md | done | ae3 bootstrap | `docs/UNTANGLE_SKILL.md` | Synthesized from Part D (no separate SKILL.md on disk); captures core test, 3 failure modes, 5 steps, D1/D2/D4 as required. |
| D1 Options chosen | done | ae3 bootstrap | `docs/UNTANGLE_SKILL.md` D1 section | Comments YES (108 patch comments go inside `refactor:` slices); Tests YES done last in M9 with never-remove safety net and decision-log reasons. |
| D2 Scope and order | todo | — | — | Scope automix surface only, upstream out; inventory first M0.5 with DECISION_LOG, skeleton, deps.svg, coupling report; redesign, milestone fixes, M9 sweeps. |
| D3 Seed inventory | todo | — | — | Measured seed baseline in Table 3; full M0.5 inventory replaces with exact `file:line` entries. |
| D4 Done criteria | todo | — | — | Counts, 1-place change rule (3 to demonstrate), clean import graph, final sweep reports nothing worth fixing, behaviour preservation. |

## Table 3 — Part D4 Special-Case Count Tracker

D4 targets: swallowed exceptions at or under 10 (each reasoned); retry/sleep/timeout at or under 5 (MixMachine or I/O clients only); fallback entry points = 1 (LAST_RESORT / classic rule); plan-identity checks = 1 (generation id); `CrossfadeController` at or under 2,500 lines (from B1.2).

| Milestone | Swallowed exceptions | Retry/sleep/timeout | Fallback entry points | Plan-identity checks | CrossfadeController lines | Target met? |
|---|---|---|---|---|---|---|
| D3 seed (djmix52) | 109 | 47 | 51 | 8 | 9,835 | baseline |
| M0.5 inventory | TBD | TBD | TBD | TBD | TBD | pending |
