# AE3 progress ledger — BitChord Automix Engine 3

**Release target:** `1.10-djmix53` / versionCode **95**, engine version **AE 3.0.0**. This is not a claim that the current checkout or an APK has been bumped and accepted.
**Base:** `djmix52` `b3408283` → branch `ae3` (merged to `master` at release). This ledger lives on `ae3`.
**Master plan:** `BitChord Automix Engine 3 (AE3): the baseline master plan` (Part A = fix djmix52, Part B = mature baseline, Part C = milestones M0–M11, Part D = untangle pass).
**Handover:** `/home/hfzhkn/Bitchord/AE3_HANDOVER.md` (living doc; repo truth, runbook, remaining work).
**Rules:** one row per numbered item in Parts A and B (see Table 2); update with every commit; **an item without evidence is not done**; statuses strictly `todo` / `doing` / `done` / `blocked`; commit separation — `refactor:` preserves behaviour, `feat:` / `fix:` carry intentional Part A/B changes, `chore:` / `docs:` for docs, ledger and tooling.

## Table 1 — Milestone Gate Tracker

| milestone | status | commit | evidence | notes |
|---|---|---|---|---|
| M0 Baseline capture | blocked | `dce8dc16` | `docs/AE3_BASELINE.md`, `../Bitchord/evidence/ae3-m0/m0-metrics.json`, `../Bitchord/evidence/ae3-m0/analyze-djmix52-vc94-combined.md` | Partial vc94 (`1.10-djmix52`) Festival capture (23 timed transitions, 1,088 telemetry events since install): 95.7% overlap proxy (22/23), 39.1% `v2-native` (9/23 timing; 10/19 `mix.fire` classic), analysis p95 2,750 ms (live logcat `infer` mean 949 ms, CPU only), 5 Festival recipes / 9 FX types, `fxCount>=2` 78.3%, `dev.sfg.orchard.npu.v73` confirmed installed. **Blocked:** Club (0), Subtle (0), and 20s-stress (0) require in-app level switch/seek not exposed via headless ADB. |
| M0.5 Untangle inventory | done | this commit | `docs/untangle/DECISION_LOG.md`, `docs/untangle/deps.svg`, `docs/untangle/deps_report.md`, `docs/untangle/change_coupling.md`, `tools/untangle/deps.py`, `tools/untangle/skeleton.py`, `docs/adr/0008-deterministic-fp-bit-identity.md`, `docs/adr/0009-in-memory-phrase-songmap-persistence.md` | Part D2 steps 2–3 complete after M0 (`dce8dc16`) with 0 code changes: 146 scope files (`81,344` lines), 101 Kotlin test files (`771 @Test`, Bucket A=43/317, B=35/220, C=23/234) + 5 native test/probe files, 1 layer SCC (5 layers) + 7 file SCCs, 4 SOFT-settings inversions, 2 tight coupling clusters (`co-sum` 974 & 12), Table 3 populated, ADRs 0008–0009 added. |
| M1 Contracts + MixMachine | done | `4d92f84b` (`fbb7b0eb`, `fb39161e`, `35b36a39`) | `:djmix-core:jvmTest` 14 passed / 0 failed (matrix 3, property 2, replay 9), wall-clock 27 s, `hooks-check.sh` exit 0 | `djmix-core` pure KMP, B1.3 contracts, B2 reducer, B1.2#5 invariants; ADR 0010 musicality-first + ADR 0006 SQLite addendum; gate 100 percent table cells (220), 10k property green, core under 60 s. |
| M2 Analysis + store | doing | `28b67dcf` | Nirovi+Syvenu offline models bundled (`nirovi_int8.ort`, `syvenu.ort` sha `1866a5955ed6b0e80f807cb49f56af530121064b8ea5322026566126eb3cd4f3`), UMX removed from analysis path, `djmix.db` WAL store + migration, `QueueAnalysisWorker`; compiled `:shared:compileKotlinJvm` + `:app:compileProdDebugKotlin` exit 0; `:djmix-core:jvmTest` 23/23; `DjmixStoreContractTest` 6/6; `hooks-check.sh` exit 0; `djmix-core` purity 0 matches | Part B3 store and migration plus B3.2 queue job code landed. Gate pending: 4-min track at or under 1.5 s CPU (`<= 1.5 s` timing to verify on phone after M4/APK) and store-survives-restart verified on device. May run with M3. |
| M3 One brain | doing | `49d40a7f`, `b2c60e03`, `4934ab67`, `3af89502`, `832b54f7` | `djmix-core` musical planner (`PairPlanner`, ADR 0010 brake rationing) + `MixExecutor` driving `MixMachine`, One-Brain `PairPreparer` (containment/no-abort/2nd-executor `djmix-stems`, `requiredStems` overlap gating), `CrossfadeController` first strangler slice −770 lines (9,835 → 9,065 measured 2026-11-24); compiled `:shared:compileKotlinJvm` + `:app:compileProdDebugKotlin` exit 0; `:djmix-core:jvmTest` 23/23; `PairPreparerTest` 6/6; `StuckStateRecoveryTest` 4/4; `hooks-check.sh` exit 0; `plan ==` identity compares 0; `checkShownWatchdog`/`straightRescue` deleted (grep 0) | Gate pending: §10 rows 100 percent overlap, 0 broken, 100 percent frozen, 0 mid-vocal on a 24-transition device run at APK verification. |
| M4 Render + recipes v2 + audition | doing | `a481d915`, `0cabc409`, `e6260ebb`, `0f5b7bcd`, `30bdcfe4` | C++ DJ-FX rewrite per Part A §5, ADR 0008 FP flags, host `--render` CLI (`5/5` probes + ASan PASS), 26 `RecipeV2` JSONs, `LoopMixRenderer` M4 JNI + `@AudioThread` wired (`0f5b7bcd`), synthetic `S1,C2,F1` `mixq` **30/30 PASS** + negative shifts `-20/+15/+20 ms` FAIL (`30bdcfe4`), 6-pair real pack uploaded to `gdrive:djmix53-audition` (`171 objects, 288.513 MiB`); **Stage 8** `assembleProdDebug` built `libbitchord_loopmix.so` (`559,344` B) | Gate pending: Owner Gate C4 #1 ratings >= 3.5 / 0 drop on `gdrive:djmix53-audition`. |
| M5 Speed | todo | — | — | Partial inputs landed: `requiredStems` overlap gating + `LibraryAnalysisWorker` (`832b54f7`). Sc-net dynamic-int8 blocked: export prompt sent to owner; no `torch`/`onnx`/`onnxruntime` on local host or dvb. Gate READY p50 at or under 6 s / p95 at or under 15 s cold, warm at or under 3 s unmeasured. |
| M6 NPU | doing | `ba482d98`, `0f5b7bcd`, `30bdcfe4` | `ModelRegistry`, `InferenceBackend`, Orchard V73 `npu_bridge`, `NpuSelfTestRunner`, `TrackAnalyzer` M6 wiring (`0f5b7bcd`), `npu-addon/v73/` + `.github/workflows/npu-addon.yml` (`30bdcfe4`); **Stage 8** `assembleProdDebug` compiled `npu_bridge.cpp` into `libbitchord_analysis.so` (`611,832` B) and bundled `nirovi_int8.ort` + `syvenu.ort` | Gate pending: `ep=QNN` self-test PASS on POCO F6 (`1439ebea` currently disconnected), NPU ms below CPU, measured numbers in `docs/NPU.md`. May run with M5. |
| M7 UX | doing | `893c0a62`, `ec7251b6`, `dd86c746`, `f0ba8bb8` | Uncluttered AE3 Compose UI (`ui/djmix/*`) per Part A §7 chip + B9 Inspector/History/Settings (`893c0a62`), Compose/AppSettings/PlayerHost wiring (`ec7251b6`, Compose fixes `dd86c746`, `f0ba8bb8`, `hooks-check` 32/32 ok); **Stage 8** `:app:compileProdDebugKotlin` & `assembleProdDebug` PASS | Gate pending: owner screenshot review "uncluttered" + device verification. |
| M8 Diagnostics + learning | doing | `893c0a62`, `0f5b7bcd`, `30bdcfe4` | `TasteModel`, `TelemetryV3Writer`, `DiagnosticsBundler`, `DjmixFlags` (`893c0a62`), `MixExecutor` M8 wiring (`0f5b7bcd`), `tools/analyze_telemetry_v3.py` (14/14 smoke) + `tools/replay_bundle.py` (20/20 smoke) (`30bdcfe4`); **Stage 8** unit-test compile & APK build PASS | Gate pending: flagged bundle `[PENDING DEVICE RUN]` bit-exact replay on devbox; learning shifts a recipe weight after 3 thumbs-down. |
| M9 Hardening + untangle sweep | doing | `8c87f8c7`, `a065cb34`, `f7c3387a`, `b517ae51`, `c6b7b0a7`, `c6970dbc` | Blueprint: `/home/hfzhkn/Bitchord/work/m9-strangler-blueprint.md`. **Wave 1 extractions landed (14 files, every one at or under 1,500 lines, verified `wc -l` on `dvb` at `b517ae51`):** L-1..L-2 `LoopQualification.kt` 268 + `DeckKnotBuilder.kt` 545 out of `LoopMixSession.kt` (2,119 → **1,474**, `8c87f8c7`); P-1..P-4 `TransitionPlanSelector.kt` 566 + `PreparedSessionManager.kt` 223 + `StemWindowPlanner.kt` 993 + `ChoreoScriptBuilder.kt` 190 + `djmix/store/TrackStoreBridge.kt` 158 out of `PairPreparer.kt` (2,895 → **1,220**, `a065cb34`); T-1..T-5 `StemAvailability.kt` 415 + `HeadAnalyzer.kt` 362 + `AudioMath.kt` 261 + `WholeTrackPipeline.kt` 528 out of `TrackAnalyzer.kt` (2,623 → **1,053**, `f7c3387a`); `fix(m9)` `b517ae51` gives `headAnalyzer`/`wholeTrackPipeline` explicit property types. **Wave 2a C-1..C-2 landed (`c6b7b0a7`):** extracted `FadeRideCurves.kt` (**795** lines) + `EaseTempoEngine.kt` (**658** lines) out of `CrossfadeController.kt` (9,065 → **7,776** lines; net −1,289 lines); `FadeRideCurvesMaskTest` (2/2), `EaseTempoEngineCancellationTest` (10/10), ADR 0010 Festival fallback test fix in `RecipesTest` (F5/C2). **B13.1 vc93 NPU cleanup (`c6970dbc`):** deleted dormant `NpuQnnSession`, `NpuSelfTest`, `NpuQdqSelfTest`; routed `NpuSession` CPU diagnostics and descriptor. **`dvb` verification (`c6b7b0a7`+`c6970dbc`+`7e1b1241`):** Gate 1 `BUILD SUCCESSFUL in 1m 15s` (`:djmix-core:jvmTest` **38/38**, `:shared:jvmTest` **648/648**, `:app:compileProdDebugKotlin` & `:app:compileProdDebugUnitTestKotlin` clean); Gate 2 `BUILD SUCCESSFUL in 3m 44s` (`--rerun-tasks`, 8 suites, **49 tests: 45 passed, 0 failures, 0 errors, 4 skipped**). Invariants: `hooks-check.sh` 32/32, `check_audiothread_alloc.py` exit 0, 0 `CrossfadeController` imports in `playback/{loopmix,djmix,smart}`, 0 deleted NPU references | Remaining: `CrossfadeController.kt` 7,776 → at or under 2,500 (Wave 2 C-3..); B13.3/B13.4 dead-code removal; D2 step 5 sweeps; B6.5 soak + B6.4 perf + B15 budgets; D1 Bucket C test cleanup LAST. |
| M10 Sync + release engineering + docs | doing | `ec7251b6`, `0f5b7bcd`, `30bdcfe4`, `77a0fcaf`, `34eac976`, `dd86c746`, `f0ba8bb8`, `56b64546` | CI/release/`npu-addon` workflows, v1+v2+v3 signing, `bumpDjmix` (`34eac976`), `soak.sh` (22/22 smoke, `[PENDING DEVICE RUN]`), `check_audiothread_alloc.py` (exit 0), B12 docs + `CHANGELOG.md` (`77a0fcaf`), 0-conflict dry run (`21356206`), `hooks-check.sh` (32/32); **B11.1 fail-closed release gates (`56b64546`):** `tools/release/release-gates.sh` + `tools/release/test-release-gates.py` (**50/50 PASS**), strict signing secret checks, explicit single-APK & report hashing, untrusted tag rejection; **Stage 8 `dvb` Gates 1–3 (`f0ba8bb8`):** Gate 1 PASS (`1m 30s`, `:djmix-core:jvmTest` 23/23); Gate 2 `1,569/1,590` PASS (all AE3 suites green: `PairPreparerTest` 6/6, `DjmixStoreContractTest` 6/6, `StuckStateRecoveryTest` 4/4, `Djmix18–32` 88/88; 21 failures in 7 legacy Bucket C suites reserved for M9 D1); Gate 3 `:app:assembleProdDebug` PASS (`3m 54s`, `app-prod-arm64-v8a-debug.apk` `105,858,834` B) | Gate remaining: GitHub CI green after push; M9 cleanup + Bucket C tests LAST; `bumpDjmix` + release APK after M9. |
| M11 Release `1.10-djmix53` (AE 3.0.0) | todo | — | — | C3 checklist; gate owner final listening verdict. |

## 2026-11-25 latest integration checkpoint

This checkpoint supersedes the historical source locations and line counts below;
it does not promote unexecuted integration work to a passed milestone.

- `496b5469` and `19da5aac` landed the coordinator extraction and explicit host
  dispatch repair. Committed `CrossfadeController.kt` is **2,381** lines. Its helper
  size gate is satisfied in that committed scope; the broader M9/D4 and integrated
  runtime gates remain open. Subsequent service-lifecycle edits are a separate batch.
- R2's original ten-file/17-test source freeze was verified, then independent
  review found a native-user retirement race and frozen straight-timing drift.
  The same writer froze both repairs with 25 tests; all eleven source fingerprints
  matched and independent review statically closes the original P1/P2 findings.
  Review then exposed exact-string exclusions bypassing the early unavailable-
  analysis fallback. The bounded parent filter/result-guard repair now has four
  additive default/injected fallback cases and a twelve-path current freeze:
  **25 canonical + 4 lifetime tests**, still unexecuted. Review caught and the
  parent corrected a String/enum identity expression and invalid draft default-
  refusal expectations before compilation. The earlier freeze is historical for
  changed paths. Core last-resort selection also refuses instead of reviving
  excluded S4. App compilation/execution remains required.
- Core gate2 configures the corrected JavaExec classpath. Core gate3 actually runs
  recipe lint: **26 RecipeV2 definitions PASS**. Gate4 closes the CLI-test type
  failure: **47 core / 19 focused shared tests PASS**, shared JVM compile RC0,
  nonempty raw XML with zero failures/errors/skips. Parent independently parsed
  the XML. Unchanged inputs reuse lint evidence; no running job was duplicated.
- R4 source is refrozen (33 focused tests present, unexecuted): production uses
  the process owner/executor, exact generation/epoch contexts and canonical
  Inspector/remix identity. All eight controller delegates initialize before
  publication without synchronized lazy acquisition; current controller is
  **2,403** lines. Analyzer store reuse is also frozen with six new behavioral
  tests; verified analysis provenance and output capture remain unavailable seams.
- Both standalone host modules are now registered. Render's first Gradle attempt
  failed before compilation because the host lacked its declared JDK17 toolchain.
  The pinned JDK17 environment correction passed while preserving Java21 binary
  hashes and global alternatives. The byte-identical 359-file render snapshot now
  compiles, runs **28/28 tests**, and produces `installDist` (all RC0; parent parsed
  nonempty XML). The original worker then failed its JShell fixture smoke. A
  smoke-only Java launcher correction passed the installed CLI twice with identical
  trace bytes and the exact captured script; no Gradle/source rerun was needed.
  This is a **decision-trace** gate, not renderer WAV or complete reducer replay.
  Analysis has direct native-backed JUnit **21/21**, audition Python **21/21**,
  mixq Python **13/13**, and one exact-hash synthetic Nirovi/Syvenu smoke. Its direct
  compiler/dependency setup is not the pending Gradle boundary gate. The temporary
  playback source allowlist/decoder carrier must not become an app/shared dependency.
  ADR0012 selects real shared production geometry/types, concrete-plan/retiming and
  artifact ownership extraction after the runtime review/gates. Frozen-input export
  is not arbitrary-pair production audition or device bit-exact parity.
- Native host extraction/parser completion survived its exhausted session: five
  scoped plus three unchanged production hashes matched. Parent reconciled all
  91 saved command/RC/log receipts, five identical probe logs, and three 52-case
  malformed matrices (51 typed refusals with output preserved in each). All 51
  original malformed input bytes remain unchanged. The normal/sanitizer WAV hashes
  match. The historical transport fixture still truthfully reports
  `violations=1`; a separate completed-physical-fade telemetry correction now has
  focused positive/negative and unchanged-PCM validation in progress, not a quality PASS.
  That focused validation is now parent-accepted: **18/18** current normal and
  ASan/UBSan cases pass, the retained pre-fix source fails only the three intended
  completed-fade cases, focused PCM is identical across all three builds, and the
  canonical WAV remains byte-identical with the corrected counter zero. Ten host
  probe runs are RC0. This is telemetry correctness, not listening/quality acceptance.
- The eight captured audio/JNI deltas now have exact-HEAD static review with no
  new P0/P1/P2 finding; all eight source hashes still match. The inherited stretch
  configured/caller channel mismatch has a separate bounded native/JNI regression
  lane. Parent accepted its focused host evidence: configured/caller channel mismatch is rejected before pinning, pre-fix ASan reproduces a real heap-buffer overflow, four g++/clang++ normal/sanitizer suites pass, and legitimate mono/stereo PCM is byte-identical to direct `PitchStretch`. Android consumer compilation remains pending. Static review is not allocation profiling or device parity.
- B14/D4 tooling is refrozen and parent-checked: **55 invariant fixtures** and
  **15 scanner self-tests** pass. The actual invariant gate remains **RC1** with
  11 policy findings (one API-key-shaped constant and ten embedded machine paths).
  Current D4 measurements expose a lexical triage backlog; they are not the
  reasoned defect counts required by the plan. No threshold/suppression or CI
  wiring was added to disguise these open gates; D1 cleanup remains last.
- Analysis provenance was corrected after parent rejection. Verified: the Android
  content hash is a source/rendition metadata fingerprint, not PCM; the preparer's
  retained analysis projection carries empty provenance; current model constants
  are not per-analysis runtime identity; blank/unknown compatibility is legacy
  compatibility, not verified provenance. No provenance implementation was added.
- B14 secret-scan remediation map is read-only and complete for the 11 findings.
  The API-key literal was redacted from the parent-preserved artifact, and any
  future archival/native path cleanup must be a separately recorded intentional
  change rather than a byte-identity-preserving edit.
- Renderer capture reconnaissance recorded an honest B7 gap: the native 40-second
  ring is preallocated in `prepare()`, but a released atomic counter does not make
  non-atomic sample reads coherent; the JNI read allocates per call; the Kotlin
  lifetime prevents handle UAF but not a capture snapshot generation; no producer
  populates `RetainedMixContext.audioCaptureRef`; and no FLAC encoder exists.
  `capture=null` remains truthful. No capture implementation is claimed.
- This archival commit preserves **ten byte-identical historical payloads** and
  repairs active baseline/playbook/hook/ownership references. Parent whitespace
  check returned RC0 and hooks **32/32** after the reference repair. B12.12 remains
  **doing**: the retained upstream-sync playbook still requires its coordinated
  absorption/current-version cleanup, rather than pretending all old instructions
  are authoritative or gone.
- No new device, QNN, latency, soak, owner listening, release APK, or full-suite
  acceptance is claimed. D1 legacy-test cleanup stays **last**.

Evidence: `../Bitchord/work/ae3-batch-reconciliation-20261125.md`,
`../Bitchord/evidence/ae3-resume-20261125/r2-parent-hash-verification.json`,
`validation-core-gate2-owner-handoff.md`, `validation-core-gate3-owner-handoff.md`,
`recipe-cli-test-type-fix.json`, `archive-identity.json`, and
`archive-reference-checks.json` in that evidence directory. Older results below
apply only to their recorded immutable source snapshots.

Subsequent checked evidence in the same directory: `validation-core-gate4-parent-evidence-check.json`,
`validation-render-gate1-owner-handoff.md`, `r2-independent-review.md`,
`r4-production-composition-followup.md`, `r4-store-followup-parent-check.json`,
`store-owner-reuse.md`, `analysis-exporter-integration.md`, and
`analysis-parent-freeze-verification.json`. These updates promote no item to done.
Latest verified additions: `r2-reviewer-blocker-recheck.md`,
`parent-r2-native-freeze-check-20261125.json`, `parent-render-gate2-xml-evidence.json`,
`validation-render-gate2-cli-smoke-repair/result.json`,
`parent-cli-smoke-audio-freeze-check.json`, `audio-jni-independent-review.md`,
`audio-jni-exact-diff/manifest.json`, and `core-last-resort-exclusion-repair.md`.
Native raw-receipt reconciliation: `native-final/parent-historical-host-evidence-check.json`.
Current R2/tooling freeze: `r2-early-fallback-exclusion-tests.md`,
`parent-fallback-tooling-final-check/result.json`, and `b14-d4-tooling.txt`.

## 2026-11-25 historical evidence correction and earlier acceptance batch

The milestone and item statuses below remain gated statuses. File existence, component-local wiring,
an uploaded audition pack, and generated `.class` timestamps do not establish working production
integration. This checkpoint supersedes any implementation-only wording above that implies the
following missing runtime paths are merely awaiting device verification.

- **Current source batch verified & committed:**
  - `c6b7b0a7` (refactor): C-1/C-2 controller extraction (`CrossfadeController.kt` **7,776**,
    `FadeRideCurves.kt` **795**, `EaseTempoEngine.kt` **658**), cancellation hardening tests
    (10/10), mask tests (2/2), ADR 0010 Festival fallback test alignment in `RecipesTest` (F5/C2).
  - `c6970dbc` (refactor): B13.1 vc93 NPU deletion (`NpuQnnSession`, `NpuSelfTest`, `NpuQdqSelfTest`)
    and safe ORT session diagnostics routing.
  - `7e1b1241` (feat): ADR 0011 lossless canonical script geometry contracts, `RenderGeometryV1`,
    `FrozenRecipe` compiler, `ScriptExecutionGuard`, and core unit tests (38/38 PASS).
  - `56b64546` (ci): B11.1 fail-closed release gates (`test-release-gates.py` 50/50 PASS), signing
    validation, artifact hashing, and injection protection in `.github/workflows/release.yml`.
  - Verification: `dvb` Gate 1 PASS (`:djmix-core:jvmTest` 38/38, `:shared:jvmTest` 648/648),
    `dvb` Gate 2 PASS (`--rerun-tasks`: 49 tests across 8 suites, 45 passed, 4 skipped host-JNI,
    0 failures, 0 errors). Controller is down to **7,776** lines; still above **2,500** target (Wave 2 C-3..).
- **Recipe authority remains a code gap (M3/M4/B5):** `ChoreoScriptBuilder.kt:62–77` still chooses
  legacy choreography; `TransitionPlanSelector` maps the six new recipe IDs to legacy substitutes.
  A frozen JSON-recipe ID is not yet proof that its specified script executes. Required engine
  Gradle modules are also incomplete: `settings.gradle.kts` includes `:djmix-core`, with the
  `:djmix-analysis`, `:djmix-render`, and `:djmix-android` boundaries still outstanding.
- **Audition quality is not passed (M4/A§9/B6):** the saved companion artifact
  `../Bitchord/work/djmix53-audition/index.md:5` reports **2/79 passing**, not a passing real corpus.
  Its scripts are generated separately from the live Nirovi/Syvenu/planner/stem pipeline. The
  synthetic **30/30** result is separate evidence and does not cure these real-pipeline gaps.
  Correct shared-pipeline exports and objective quality gates must precede release acceptance.
- **Live services remain disconnected (M7/M8/B7/B8):** production `CrossfadeController` constructs
  `MixExecutor` with `store = null` (`:1161–1165`) and does not supply its optional taste, flags,
  telemetry, or diagnostics services. `AndroidPlayerHost.kt:159` calls `bundle()` without the
  transition context. History production, persisted learning that affects planning, complete Flag
  capture, and bit-exact replay are code/integration work, followed by their required proof.
- **Historical test-count correction:** retained Stage 8 Gate 2 logs establish **1,556 passed,
  13 skipped, 21 failed = 1,590**. The earlier `1,569/1,590 PASS` phrase counts skipped cases as
  non-failing; it must not be read as 1,569 executed passes. Wave 1 focused results remain **33
  passed, 4 skipped, 0 failures**. Wave 2 focused results on `dvb` are **45 passed, 4 skipped, 0 failures = 49**.
- **Release input checks repair (M10/B11.1):** `release.yml` and `tools/release/release-gates.sh`
  fail closed on missing secrets, unsigned APKs, missing quality reports, and untrusted tags.
  Validated with 50/50 automated test cases.

Evidence inspected: actual source/artifacts above, the read-only plan-to-runtime audit at base
`9ade9a25`, and `../Bitchord/evidence/ae3-resume-20261125/validation-phase1-readiness.md` plus its
raw logs/manifests. Remote readiness confirmed on `dvb` `/home/boxes/bitchord-mix`. Independent reviews,
one serialized validation owner, and the full master-plan gap audit govern the next acceptance batch.
D1 legacy-test cleanup stays **LAST**, after the final M9 untangle sweep; no safety-net test has been
removed by this checkpoint.

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
| A§2.1.1 Preparer only decider | doing | `49d40a7f` | `PairPreparer` owns exit/entry/geometry/recipe/stems; controller `decideTransition` deleted, `plan ==` identity compares 0 (only `plan == null` null-checks remain); `PairPreparerTest` 6/6 | Device gate: 24-transition run at APK verification (M3). |
| A§2.1.2 Geometry as generators | doing | `49d40a7f` | `PairPlanner` evaluates `LOOP`/`BRIDGE`/`STRAIGHT` candidates per recipe, scores and freezes best; `TransitionPlannerCoreTest` green (`:djmix-core:jvmTest` 23/23) | Device gate pending (M3). |
| A§2.1.3 Generation id, delete plan== | doing | `49d40a7f`, `832b54f7` | Generation ids carried through freeze; legacy identity compares deleted (`plan ==` identity compares 0 in controller/session) | `preparer.frozen` naming sweep continues in M9. |
| A§2.1.4 Handoff qualifier into scoring | doing | `49d40a7f` | `PlacementEvaluator.qualifyHandoff` + handoff PCM bounds check before freeze; `planLastResortMusicalExit` covered by test | Device gate pending (M3). |
| A§2.1.5 Classic only when unavailable | doing | `49d40a7f` | `classicFallbackOnRendererUnavailable`: classic runs strictly when native unavailable/failed; `mix.classic.reason` logged | Device gate pending (M3). |
| A§2.2.1 Commit point plus MOVED | doing | `49d40a7f` | Commit/MOVED semantics in `MixMachine` (B1.2#5); controller forwards `MixEvent.Tick`/commit through `MixExecutor` | Device gate pending (M3). |
| A§2.2.2 Last-resort musical exit | doing | `49d40a7f` | `PairPlanner.planLastResortMusicalExit` on last-phrase downbeat, no-stem recipe, never starts at now; test `lastResortMusicalExitPicksLastPhraseDownbeatNotPositionMs` | Device gate pending (M3). |
| A§2.2.3 Delete watchdog and rescue | done | `49d40a7f` | `checkShownWatchdog` (incl. 1.2 s rule) and `straightRescue` (incl. 0.45) deleted; `grep -c checkShownWatchdog\|straightRescue` = 0 | Hard watchdog (`checkHardWatchdog`) kept as the near-EOF guard; `StuckStateRecoveryTest` 4/4. |
| A§2.3.1 Countdown only READY / ARMED | doing | `49d40a7f` | `PairPreparer` `PrepState` gate: PREPARING shows progress only; READY-only countdown consumed in controller | Device gate pending (M3). |
| A§2.3.2 Audio thread does no work | doing | `49d40a7f`, `832b54f7` | Decode on `djmix-prep`, SCNet on dedicated `djmix-stems` single-thread executor with held `stemLock`; `PairPreparerTest` pins both thread names | Device gate pending (M3). |
| A§3.1.1 Bundle models as assets | doing | `28b67dcf` | `nirovi_int8.ort` (7,948,784 B, sha `7454d98a...fed489387`) + `syvenu.ort` (2,349,304 B, sha `1866a595...6126eb3cd4f3`) unpacked to `filesDir` + SHA-verified offline in `AutomixModels` | On-device offline-first still to verify at APK (M2 gate). |
| A§3.1.2 BeatTracker Nirovi | doing | `28b67dcf` | `NIROVI_MODEL_ASSET = "nirovi_int8.ort"`; Nirovi primary, Beat This download fallback kept | 1.5 s device timing pending (M4/APK). |
| A§3.1.3 VocalPresence Syvenu | doing | `28b67dcf` | `VocalPresence` Syvenu at 50 fps; UMX vocal removed from analysis ("Zero UMX work runs here"), kept only as 2-stem render fallback | 1.5 s device timing pending (M4/APK). |
| A§3.1.4 Gate 1.5 s | todo | — | — | 4-min track fully analysed in at or under 1.5 s CPU; log `analysis.ms model=nirovi`. |
| A§3.2.1 Plan on head / tail | doing | `49d40a7f` | `PairPreparer` plans on HEAD_TAIL, full refines; re-plan only on exit/entry move ≥ 1 bar or recipe invalid | Device gate pending (M3). |
| A§3.2.2 Never abort, containment | doing | `49d40a7f`, `832b54f7` | Containment-aware decode + stem slicing; obsolete stem run aborts cleanly on retarget (`generationBumpCancelsObsoleteStemRunBeforeStaleOverwrite` 6/6 suite) | Device gate pending (M3). |
| A§3.2.3 Start at next-known | doing | `49d40a7f` | `MixEvent.NextKnown` on track start / queue edit via `considerSmartTransition` | Device gate pending (M3). |
| A§3.2.4 Locks and second executor | done | `49d40a7f` | Separate model/stem locks (`stemLock` held during separation); SCNet on dedicated `djmix-stems` single-thread executor; `PairPreparerTest` pins thread names + lock | — |
| A§3.3.1 Dynamic-int8 SCNet | todo | — | — | Export `scnet_dyn_int8.onnx` via `tools/models/quantize.py`, pin sha; target at or under 3 s per 11 s chunk at 4 threads; audition decides int8 retention. |
| A§3.3.2 Required stems only | todo | — | — | Add `Recipe.requiredStems`; separate only overlap window plus-minus 1 bar; one pass gives 4 stems, savings in decks and window. |
| A§3.3.3 Separate ahead, disk cache | doing | uncommitted source | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemCache.kt`; `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | Existing containing-region cache has the 160 MiB disk default; atomic replacement hardening is an active independent implementation lane. Current integrated compilation and READY-speed/device gates remain pending. |
| A§3.3.4 No-stem variant at commit | todo | — | — | Same recipe no-stem variant (isolator EQ); no level downgrade, no move for stems. |
| A§4.1 Exit rules | doing | `49d40a7f` | `PairPlanner`/`PlacementEvaluator` exit rules: first boundary after last chorus/drop in outro else vocal < 0.25 next phrase; never mid-vocal at ±1 beat (except deliberate acapella recipes C3/F4); never in build; one phrase after swap | Device gate pending (M3). |
| A§4.2 Entry rules | doing | `49d40a7f` | Intro first downbeat / boundary before first vocal; drop downbeat recipes; pre-roll caps 32 Club / 64 Festival; silent intros skipped | Device gate pending (M3). |
| A§4.3 Pair score | doing | `49d40a7f` | `scoreRecipe` weights tempo/Camelot/energy/vocal-overlap-0/bass; variety last 3; `topScores` (top 3) for Inspector | Device gate pending (M3). |
| A§4.4 Hard rejects | doing | `49d40a7f`, `b2c60e03` | `REFUSAL_PRIORITY` list; mid-vocal exit/entry, bass clash, abrupt-without-jump refusals; pinned by `TransitionPlannerCoreTest` (23/23), incl. C3/F4 acapella exception codified | Device gate pending (M3). |
| A§4.5 Abrupt rationing | doing | `49d40a7f` | `checkRecipeEligibility`: F2/F7 Festival-only, jump > 10% (or > 8% + genre), phrase-end, 8-transition cooldown; Festival fallback F5/C2 (ADR 0010) | Device gate pending (M3). |
| A§5.1 Loop | todo | — | — | 1/2/4/8 bars on boundary, at or under 8 ms equal-power seam, zero drift vs knot clock; C5 Loop extend holds OUT outro 1–2 phrases. |
| A§5.2 Roll | todo | — | — | Last 2 bars 1, 1/2, 1/4, 1/8, 1/16 on beat, HPF 200 Hz to 2 kHz and plus 2 dB, hard stop on 1; replace `LoopMix.rollLoopBeats`. |
| A§5.3 Sweep (filter) | todo | — | — | Resonant HPF 20 Hz to 1.2–2 kHz over 8–16 bars exp, Q 0.7 to 1.6 no spike over plus 6 dB; mirror LPF demuffle on IN; keep muffle curve as reference. |
| A§5.4 Flanger | todo | — | — | Through-zero jet, LFO 1 per 2 bars tunable 1/2–4, depth 0.5–4 ms, feedback 0.55–0.7, stereo 90 degrees, wet 0 to 0.6 last 4 bars with HPF rise. |
| A§5.5 Phaser | todo | — | — | 6–8 all-pass stages, LFO 1 per bar tunable, feedback 0.6, stereo 90 degrees, wet 0 to 0.7 across tunnel. |
| A§5.6 Riser plus Impact | todo | — | — | 3 layers noise sweep 300 Hz to 8 kHz, uplifter octave, snare roll last 2 bars, 1-beat reverse-reverb tail, impact thump minus 6 dBFS on 1. Rewrite `dj_fx.h` Riser plus new Impact. |
| A§5.7 Brake plus tape-stop | todo | — | — | Exp rate 1.0 to 0 over 1–2 beats, pitch follows rate, LPF 18 kHz to 800 Hz, 10 ms fade; tape-stop variant with wow; phrase end only. Rewrite on pre-stretch pointer. |
| A§5.8 Spinback | doing | uncommitted source | `../Bitchord/evidence/ae3-resume-20261125/spinback-unit-implementation.md` | Compiled gap seconds now derive from the frozen beat clock, with enabled-only migration and pre-mutation renderer validation. Source-complete unit fix; integrated compilation and audition remain pending. |
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
| A§9.1 Host render CLI | done | `a481d915` | `native/loopmix/loop_mix_renderer_ab_main.cpp` `--render script.json --out file.wav`; 5/5 probe + ASan suites PASS; 30/30 synthetic WAVs rendered | Same `Renderer` C++ engine as Android JNI. |
| A§9.2 JVM exporter | doing | `a481d915`, `30bdcfe4` | `tools/audition/` + `tools/audition/generate_synthetic_pairs.py` (`30bdcfe4`) + real audition harness (`6A-2-v2`) | Real 6-pair audition exported & uploaded (`gdrive:djmix53-audition`); JVM `-Daudition=1` tie-in pending Stage 8. |
| A§9.3 Showcase six pairs | done | `6A-2-v2` (Drive upload) | 78/79 real WAVs (after deleting orphan `pair1_F2`) across 6 pairs uploaded (`171 objects, 288.513 MiB`) to `gdrive:djmix53-audition` | Ready for Owner Gate C4 #1 listening ratings. |
| A§9.4 Self-check | doing | `30bdcfe4` | Synthetic CI corpus `S1,C2,F1` **30/30 PASS** + negative shifts `-20/+15/+20 ms` FAIL (`beatPhase`); real corpus `mixq-results.json` uploaded | Real corpus includes MP3 `beatgrid.py` phase offsets flagged strictly by separated pre/post `mixq`. |
| A§9.5 Upload audition | doing | `6A-2-v2` (Drive upload) | `gdrive:djmix53-audition` (`171 objects, 288.513 MiB`, incl. `djmix53-audition.zip` + `index.md` + `mixq-report.html`) | Uploaded; awaiting Owner Gate C4 #1 ratings before tuning/APK cut. |
| A§10.1 Festival 12 | todo | — | — | Cold start, Festival, 12 natural transitions, skip to about 60 s before exit; telemetry plus logcat. |
| A§10.2 Club 12 | todo | — | — | Club under same protocol. |
| A§10.3 Subtle 12 | todo | — | — | Subtle under same protocol. |
| A§10.4 Stress run | todo | — | — | Skip to 20 s before exit, 3 times. |
| A§10.5 Results table | todo | — | — | `docs/DJMIX53_RESULTS.md` before djmix52 vs after djmix53; 9 metrics from `mix.timing`, `mix.fire`, `mix.countdown.*`; targets in master plan §10. |
| A§10.6 Screen recording | todo | — | — | 2-min with internal audio, 2 Festival transitions, one riser drop plus one flanger/phaser; upload with audition zip. |
| A§10.7 Owner report | todo | — | — | Short mobile-friendly 5 items: version/SHA/size/install; results table; audition plus recording links; weak points plus next change; NPU line. |
| B1.1 Modules plus dependency rules | doing | `4d92f84b` | `djmix-core/` pure KMP (commonMain purity grep 0 matches) | `djmix-core` done, depends nothing; `djmix-analysis`/`djmix-render`/`djmix-android` remain M3/M8; `app` UI-only wiring pending. |
| B1.2.1 MixExecutor owns transitions | doing | `49d40a7f` | `MixExecutor` owns arming/starting/running/handover/retiring via `MixMachine`; controller forwards `MixEvent.Tick`/`NextKnown`/`QueueEdited` | Device gate pending (M3). |
| B1.2.2 Controller keeps pre-automix only | doing | `49d40a7f` | First strangler slice landed (9,835 → 9,065); pre-automix decisions route via `MixExecutor`, classic only when native unavailable | Full cutover continues (M9 size gate). |
| B1.2.3 Strangler slices | doing | `49d40a7f` | Slice 1 moved planning to `djmix-core` + preparer, deleted `decideTransition`/`checkShownWatchdog`/`straightRescue` in the same commit; no dead-but-reachable legacy in touched paths | Soak (B6.5) + remaining slices in M9. |
| B1.2.4 Size targets | doing | `496b5469`, `19da5aac` | Committed controller **2,381** lines; coordinator helpers <=1,500; explicit host dispatch regression test added. Current R2 ten-file sizes/hashes verified in `../Bitchord/evidence/ae3-resume-20261125/r2-parent-hash-verification.json`. | Controller extraction is landed; final integrated size/dependency/D4 sweep and native host split remain open. Do not reuse the earlier 9,065-line measurement as current. |
| B1.2.5 Survive list as tests | done | `4d92f84b` | `:djmix-core:jvmTest` 14 passed, wall-clock 27 s | Sticky pause, surviving-deck-never-retired, no-stem-fallback-preserves-level, commit 4 bars/6 s encoded in `MixCoreMatrixAndContractsTest` + property/replay suites. |
| B1.3.1 TrackAnalysis v3 | done | `4d92f84b` | JSON round-trip in `MixCoreMatrixAndContractsTest` (14 passed, 27 s) | Beats/downbeats/confidence/BPM/key/loudness/energy/vocal 50 fps/structure/models shipped as `TrackAnalysisV3` (schema 3); planner/store/inspector wiring in M3. |
| B1.3.2 TransitionPlan v1 | done | `4d92f84b` | JSON round-trip in `MixCoreMatrixAndContractsTest` (14 passed, 27 s) | Frozen exit/entry/geometry/knots/swap/recipe+params/stems/reasons/top-3/generation shipped as `TransitionPlanV1` (schema 1). |
| B1.3.3 TransitionScript v1 | done | `4d92f84b` | JSON round-trip in `MixCoreMatrixAndContractsTest` (14 passed, 27 s) | Region refs/knots/loop bounds/envelopes/FX params/stem refs/loudness shipped as `TransitionScriptV1` (schema 1); envelope/Fx IDs 0..19 verified. |
| B1.3.4 MixEvent v1 / MixEffect v1 | done | `4d92f84b` | 11x20 matrix 220 cells + property/replay (14 passed, 27 s) | 20 events / 11 effects shipped in `MixContract.kt` as `MixMachine` inputs/outputs; executor wiring in M3. |
| B1.3.5 MixTelemetry v3 | done | `4d92f84b` | JSON round-trip, `engine == "3.0.0"` in `MixCoreMatrixAndContractsTest` | One schema `MixTelemetryV3` (`engine=3.0.0`, planId/generation/state) shipped; diagnostics consumers in M8. |
| B1.3.6 Golden bit-identical rule | doing | `4d92f84b` | Script contract done; device/host render comparison pending | `TransitionScript` bit-identity test (3 scripts, SHA-256) remains M4 audition/replay work. |
| B2.1 States | done | `4d92f84b` | 220-cell matrix + property/replay (14 passed, 27 s) | All 11 states in `MixContract.kt` (`MixStateKind` 11, exactly as spec). |
| B2.2 Events | done | `4d92f84b` | 220-cell matrix (14 passed, 27 s) | All 20 events in `MixContract.kt` (`MixEventKind` 20, exactly as spec). |
| B2.3 Effects | done | `4d92f84b` | 220-cell matrix (14 passed, 27 s) | All 11 effects (incl. PauseSync/ResumeSync/Arm) in `MixContract.kt`. |
| B2.4 Reducer rules | done | `4d92f84b` | Matrix invariants + B1.2#5 (14 passed, 27 s) | Commit (4 bars / 6 s) / MOVED / LAST_RESORT; countdown only READY/ARMED; sticky pause; survivor rule; background/thermal no-stem variant. |
| B2.5 Table test 11x20 | done | `4d92f84b` | `assertEquals(220, visited.size)` in matrix test (14 passed, 27 s) | Every (state, event) cell reduces cleanly with global invariants checked. |
| B2.6 Property test 10k | done | `4d92f84b` | `MixMachinePropertyTest` 2 tests, 10k sequences (14 passed, 27 s) | Seed-logged random sequences; never broken countdown, never unprepared Start, PREPARING drains. |
| B2.7 Replay test | done | `4d92f84b` | `MixMachineReplayTest` 9 tests incl. JSON round-trip replay | Deterministic `replay` + 8 end-to-end device replays; on-device log replay remains M8 diagnostics. |
| B3.1 Storage Room | doing | `28b67dcf` | `djmix.db` WAL store: `track_analysis`, `stem_index`, `transition_history`, `preference` + v3 mapper + migration; JSON/`PhraseMaps`/`StemCache` deletion pending | `DjmixStoreContractTest` 6/6; survives-restart on device pending. |
| B3.2.1 Queue job | doing | `28b67dcf` | `QueueAnalysisWorker` + `QueuePrepRegistry` wiring in `PlaybackService` (next 5 to FULL, background priority) | READY-speed gate in M5. |
| B3.2.2 Library job | doing | uncommitted source | `app/src/main/java/com/music/bitchord/playback/djmix/work/LibraryAnalysisWorker.kt` | Charging/idle, battery and thermal guards already exist. Local recent-track coverage and cancellation handling are an active independent implementation lane; newest-first precision must follow actual local metadata. Integrated compilation/device behavior remain pending. |
| B3.2.3 Cold pairs budget | todo | — | — | With both jobs running most transitions start READY; §3 budget becomes the cold-case budget. |
| B4.1 ModelRegistry | todo | — | — | Bundled `models.json`, overridable by `models-v1`; fields id/version/sha/bytes/source/frontend/io/backends/role/minEngine; 8 models listed in B4.1. |
| B4.2 Backend interface | todo | — | — | `InferenceBackend` with `CpuOrt` and `QnnAddon`; QNN only if available, self-test passed, benchmark faster; choice visible in Inspector and Developer settings. |
| B4.3 Own NPU add-on | todo | — | — | Code-less `npu-addon/v73` package, QAIRT jniLibs plus runner, QNN assets, fork-key signed; GHA per arch; `npu-addons.json`; prefer ours, accept Orchard. |
| B4.4 Measured gates | todo | — | — | `docs/NPU.md`: per-model CPU/NPU latency median of 7, cold compile and cached open, self-test agreement, thermal at 10-min soak start/end. |
| B5.1 Recipes as data | doing | existing implementation plus uncommitted integration | `djmix-core/src/commonMain/resources/recipes/`; `../Bitchord/evidence/ae3-resume-20261125/validation-core-gate4-parent-evidence-check.json` | The 26 RecipeV2 definitions and recorded lint evidence exist. Current production integration and real-corpus/listening gates remain open; historical snapshot results are not current release acceptance. |
| B5.2 Interpreter | doing | uncommitted source | `../Bitchord/evidence/ae3-resume-20261125/adr0012-analysis-artifact-cutover.md`; `spinback-unit-implementation.md` in the same evidence directory | Canonical compileFrozen is the compiler; app/host consume shared concrete-plan and geometry code. Shared mapper/artifact cutover is source-complete; latest clock/provenance/inference additions remain uncompiled. |
| B5.3 Linter | doing | recorded core gate plus uncommitted integration | `../Bitchord/evidence/ae3-resume-20261125/validation-core-gate4-parent-evidence-check.json` | The recorded core gate establishes 26 recipe definitions passing lint for that snapshot. Preserve that evidence; current integrated gates remain open, with no claim that pending runtime/audio behavior has passed. |
| B5.4 Recipe Lab | todo | — | — | Developer settings: pick 2 tracks plus recipe/Auto, exit/entry over waveforms, Preview renders 30 s offline; debug live reload from `Android/data/.../files/recipes/`. |
| B5.5 20 plus 6 library | todo | — | — | 20 fixed per §5; add C8 Loop roll out, C9 Filter ping-pong, F9 Backspin into drop, F10 Echo freeze, F11 Riser plus impact cut, S6 Breakdown blend; each audition render plus Inspector one-liner. |
| B6.1 Corpus | todo | — | — | `golden-pairs/` devbox only, never git, `pairs.json`; 40 pairs all genres with 6 jumps, 4 tempo over 8 percent, 4 album, 4 silent intros; owner adds via Flag. |
| B6.2 mixq metrics | doing | `a481d915`, `30bdcfe4` | `tools/mixq/mixq.py`: Part B6.2 gates enforced (`med<=10 ms`, `p95<=25 ms`, `<= -1 dBTP`); synthetic `S1,C2,F1` **30/30 PASS**, negative shifts (`-20/+15/+20 ms`) FAIL (`18.59/14.95/21.02 ms`) | HTML per-pair report integrated; real devbox golden corpus run pending. |
| B6.3 Listening gate | todo | — | — | `tools/audition/` HTML per pair, 1–5 stars, keep/fix/drop, notes to `ratings.json`; Drive `AE3-audition-<build>/`; gate average at least 3.5, no drop votes ship enabled. |
| B6.4 Perf benchmarks | todo | — | — | 6 macrobenchmark metrics on owner phone over ADB to `docs/perf/AE3-<build>.md`; regression over 20 percent fails gate. |
| B6.5 Soak test | doing | `30bdcfe4` | `tools/soak/soak.sh` verified 22/22 local smoke checks (charge guard, clock `-T`, exit handling) | `[PENDING DEVICE RUN]` 2-hour 40-track POCO F6 soak after M9/Stage 8 APK. |
| B6.6 CI | doing | `30bdcfe4` | `.github/workflows/android-ci.yml`: core tests, ASan/UBSan native probes, synthetic `S1,C2,F1` `mixq` (**30/30 PASS** locally), `hooks-check`, `@AudioThread`, APK audit | Stage 8 `dvb` local gates & GitHub CI run pending push. |
| B7.1 Flag bundle | doing | `893c0a62`, `0f5b7bcd`, `ec7251b6` | `DiagnosticsBundler` wired into `MixExecutor` (`0f5b7bcd`) and Flag UI (`ec7251b6`) | `[PENDING DEVICE RUN]` Flag export on POCO F6. |
| B7.2 Replay | doing | `30bdcfe4` | `tools/replay_bundle.py` verified 20/20 local smoke checks | `[PENDING DEVICE RUN]` Bit-exact devbox replay of a real Flag bundle. |
| B7.3 Telemetry v3 | doing | `893c0a62`, `0f5b7bcd`, `30bdcfe4` | `TelemetryV3Writer` wired into `MixExecutor` (`0f5b7bcd`); `tools/analyze_telemetry_v3.py` 14/14 smoke checks (`30bdcfe4`) | `[PENDING DEVICE RUN]` for real 36+10 transition §10 table. |
| B7.4 Developer settings | doing | `893c0a62`, `ec7251b6` | `DjmixDeveloperSettingsSheet` wired in `SettingsSheet.kt` (`ec7251b6`) | Pending Stage 8 compile + device check. |
| B8.1 Signals | todo | — | — | Explicit thumbs up/down; implicit skip within 15 s after mix minus 1, seek back into OUT minus 1, 3 consecutive full plays plus 0.2 each. |
| B8.2 Model bounded | todo | — | — | Per-recipe and per-(recipe, genre) weights in `preference`, bounded Beta/Elo so no recipe exceeds plus-minus 0.3 of score; separate inspectable scorer term. |
| B8.3 Controls | todo | — | — | Settings Automix Learn toggle default on, Reset learned preferences. |
| B8.4 Determinism | todo | — | — | Learning off or reset gives same pick for same pair/level fixed seed; Inspector shows taste offset e.g. plus 0.12 when on. |
| B9.1 Player chip only | doing | `893c0a62`, `ec7251b6` | `AutomixStatusChip` wired in `MiniPlayer.kt` / `PlayerControls.kt` (`ec7251b6`) | Pending owner screenshot review "uncluttered". |
| B9.2 Inspector final | doing | `893c0a62`, `ec7251b6` | `MixInspectorSheetV3` wired in `MixViewPopup.kt` / `AndroidPlayerHost.kt` (`ec7251b6`) | Pending Stage 8 + owner UI check. |
| B9.3 Mix History | doing | `893c0a62`, `ec7251b6` | `MixHistoryScreen` wired via `SettingsSheet.kt` (`ec7251b6`) | Pending Stage 8 + device run. |
| B9.4 Settings reorg | doing | `ec7251b6` | `SettingsSheet.kt` + `AppSettings.kt` reorganized for AE3 (`hooks-check` exit 0) | Pending Stage 8 + owner check. |
| B9.5 First run | doing | `893c0a62`, `ec7251b6` | `AutomixFirstRunDialog` wired in Stage 7 (`ec7251b6`) | Pending Stage 8 + device run. |
| B9.6 Accessibility | todo | — | — | Content descriptions, no color-only info, reduced-motion honoured for timeline. |
| B10.1 Fork hooks | doing | `30bdcfe4`, `ec7251b6` | `tools/upstream-sync/hooks-check.sh` exit 0, **32/32 hooks** ok across Stage 7 commits | Re-verified after Stage 7 UI & runtime commits. |
| B10.2 fork-owned.txt | done | `30bdcfe4` | `tools/upstream-sync/fork-owned.txt` updated with AE3 modules, docs, and tools; `hooks-check.sh` 32/32 ok | — |
| B10.3 Daily bot | todo | — | — | `upstream-sync.yml` enabled with `SYNC_TOKEN`; ask owner to create it. |
| B10.4 Dry run | done | `../Bitchord/work/ae3-sync-dryrun.md` | Worker 7F `sync.sh` dry-run vs `upstream/main` `21356206`: **0 conflicts** (gate `<=5` PASS) | 1.11 live merge still post-1.10-djmix53. |
| B10.5 Versioning base | doing | `30bdcfe4` | `app/build.gradle.kts` `ENGINE_VERSION = "3.0.0"`, v1+v2+v3 signing, `bitchordUpstreamBase = "1.10"` | Version bump to `1.10-djmix53` / vc95 via `bumpDjmix` at M10. |
| B11.1 Release pipeline | doing | `30bdcfe4`, `56b64546` | `.github/workflows/release.yml` fail-closed gates + `tools/release/release-gates.sh` + `tools/release/test-release-gates.py` (50/50 PASS) (`56b64546`) | Trigger on `ae3-*` tag at M10. |
| B11.2 bumpDjmix | doing | `30bdcfe4` | `bumpDjmix` Gradle task in `build.gradle.kts` (`30bdcfe4`) | Execute at M10 clean-tree release cut. |
| B11.3 CHANGELOG | doing | docs commit | `CHANGELOG.md` created for AE 3.0.0 (`1.10-djmix53`) | Final release notes updated at M10 tag. |
| B11.4 Rollback | todo | — | — | No downgrade via `install -r`, never uninstall; rollback is Safe mode plus flags plus fix-forward; keep last 3 signed APKs. |
| B11.5 Feature flags | doing | `893c0a62`, `0f5b7bcd` | `DjmixFlags` wired into `TrackAnalyzer` + `MixExecutor` (`0f5b7bcd`) | Safe-mode / remote `flags.json` exercise pending M11. |
| B12.1 ARCHITECTURE.md | done | docs commit | `docs/ARCHITECTURE.md` created in Stage 7 (`7C`) | Cross-checked against modules & ADRs. |
| B12.2 ENGINE_CONTRACTS.md | done | docs commit | `docs/ENGINE_CONTRACTS.md` created in Stage 7 (`7C`) | Schemas & versioning documented. |
| B12.3 MIXMACHINE.md | done | docs commit | `docs/MIXMACHINE.md` created in Stage 7 (`7C`) | 11x20 state/event table & invariants documented. |
| B12.4 RECIPES.md | done | docs commit | `docs/RECIPES.md` created in Stage 7 (`7C`) | 26 RecipeV2 entries & authoring guide documented. |
| B12.5 QUALITY.md | done | docs commit | `docs/QUALITY.md` synced with `7B-v3` telemetry/soak/replay tools & B6.2 `mixq` | Updated after `7B-v3` smoke suite (56/56 checks). |
| B12.6 NPU.md | doing | docs commit | `docs/NPU.md` created in Stage 7 (`7C`) | Pending on-device POCO F6 benchmark numbers (M6/M11 gate). |
| B12.7 SYNC.md | done | docs commit | `docs/SYNC.md` created in Stage 7 (`7C`) | Includes 0-conflict `upstream/main` dry-run workflow. |
| B12.8 RELEASE.md | done | docs commit | `docs/RELEASE.md` created in Stage 7 (`7C`) | C3 checklist, signing, rollback, `bumpDjmix`. |
| B12.9 TUNING.md | done | docs commit | `docs/TUNING.md` created in Stage 7 (`7C`) | Symptom-to-knob table updated for AE3. |
| B12.10 adr/ 0001–0011 | done | ae3 bootstrap, `7e1b1241` | ADRs 0001–0011 created (including ADR 0011 lossless canonical script geometry, `7e1b1241`) | Full ADR set covers architecture, contracts, and geometry. |
| B12.11 AE3_LEDGER.md | done | ae3 bootstrap | `docs/AE3_LEDGER.md` (this file) | Created this step with Tables 1–3 covering all M0–M11 and A/B items. |
| B12.12 Archive contradictions | doing | this archival commit | `archive/docs/AE3_B12_12_ARCHIVAL.md`; ten payloads byte-identical to HEAD originals (`../Bitchord/evidence/ae3-resume-20261125/archive-identity.json`); active reference repair with whitespace RC0 and hooks 32/32 (`archive-reference-checks.json`). | Root build guide, obsolete handover/parity/refactor/recovery narratives and grokbox kit archived; baseline/playbook/hook/ownership paths repaired. Retained upstream-sync instructions still need coordinated absorption/current-version cleanup; not full B12.12 acceptance. |
| B13.1 Delete vc93 NPU leftovers | done | `c6970dbc` | `NpuQnnSession.kt`, `NpuSelfTest.kt`, `NpuQdqSelfTest.kt` deleted; `NpuSession` routing & describe verified (`c6970dbc`) | Completed in Wave 2. |
| B13.2 Delete vc91 rungs | todo | — | — | Unreachable rungs after planner moves into `djmix-core`. |
| B13.3 Delete CountdownStabilizer | todo | — | — | Paths superseded by MixMachine. |
| B13.4 Delete rescue and watchdog | todo | — | — | `straightRescue`, plus 1.2 s watchdog. |
| B13.5 Thread annotations | doing | `0f5b7bcd`, `30bdcfe4` | `ThreadAnnotations.kt` + `@AudioThread` on `LoopMixRenderer.render` (`0f5b7bcd`); `python3 tools/check_audiothread_alloc.py` (`132 Kotlin files, 1 @AudioThread regions`, exit 0) | Full Stage 8 Gradle compile + additional M9 `@AudioThread` annotations pending. |
| B13.6 Native warnings/sanitizers | todo | — | — | `-Wall -Wextra` zero new warnings in `native/loopmix` and `native/analyzer`; ASan/UBSan on loopmix host probes as done for analyzer. |
| B13.7 Kotlin size and KDoc | doing | `8c87f8c7`, `a065cb34`, `f7c3387a`, `b517ae51`, `c6b7b0a7` | Wave 1 landed (all 14 files <= 1,500). Wave 2a C-1/C-2 landed (`c6b7b0a7`): `CrossfadeController.kt` reduced from 9,065 to 7,776 lines (`FadeRideCurves.kt` 795, `EaseTempoEngine.kt` 658) | `CrossfadeController` split continues toward <= 2,500 lines target in Wave 2 C-3.. |
| B14.1 No secrets in git | todo | — | — | Signing in CI secrets and devbox keystore only. |
| B14.2 Bundles only on Flag | todo | — | — | Diagnostics from owner device written only on owner tap, never automatically. |
| B14.3 Add-on known packages | todo | — | — | Load only from known packages ours-first then Orchard; `nativeLibraryDir` via PackageManager; never arbitrary paths. |
| B15.1 Battery budget | todo | — | — | Background analysis at or under 3 percent per hour playback via `dumpsys batterystats` 1-hour run. |
| B15.2 Renderer budget | todo | — | — | At or under 20 percent of one big core at p95 during a mix. |
| B15.3 Idle budget | todo | — | — | No automix thread awake more than once per 5 s when idle. |
| B15.4 Thermal budget | todo | — | — | Prep pauses at thermal at or above SEVERE, resumes at MODERATE; running mix never stops. |
| B16.1 Engine version rules | done | `4d92f84b` | `ENGINE_VERSION = "3.0.0"` + telemetry `engine` check in matrix test | Major/minor/patch rules applied; AE 3.0.0 frozen for M1 contracts. |
| B16.2 Version plumbing | doing | `4d92f84b` | Telemetry stamped (`MixTelemetryV3.engine`); About/bundles pending | Engine in telemetry done; Settings About + Flag bundles remain M7/M8; djmix bump independent. |
| Part D — UNTANGLE_SKILL.md | done | ae3 bootstrap | `docs/UNTANGLE_SKILL.md` | Synthesized from Part D (no separate SKILL.md on disk); captures core test, 3 failure modes, 5 steps, D1/D2/D4 as required. |
| D1 Options chosen | done | ae3 bootstrap | `docs/UNTANGLE_SKILL.md` D1 section | Comments YES (108 patch comments go inside `refactor:` slices); Tests YES done last in M9 with never-remove safety net and decision-log reasons. |
| D2.1 Scope definition | done | this commit | `tools/untangle/skeleton.py`, `tools/untangle/deps.py`, `docs/untangle/deps_report.md` | 146 automix scope files across 11 layers (`81,344` lines) + 101 test files (`23,531` lines) + 5 native test/probe files; upstream code excluded. |
| D2.2 Inventory first, no changes | done | this commit | `docs/untangle/DECISION_LOG.md`, `docs/untangle/deps.svg`, `docs/untangle/deps_report.md`, `docs/untangle/change_coupling.md`, `../Bitchord/work/ae3-untangle/inv_*.md`, `../Bitchord/work/ae3-untangle/skeleton.txt` | Full `file:line` inventory across 146 scope files + 101 test files + 300-commit coupling window; M0 baseline recorded in `dce8dc16`; 0 Kotlin/C++/Gradle/XML edits. |
| D2.3 Redesign (fixes + new ADRs) | done | this commit | `docs/untangle/DECISION_LOG.md`, `docs/adr/0008-deterministic-fp-bit-identity.md`, `docs/adr/0009-in-memory-phrase-songmap-persistence.md` | Every problem grouped under its single owning M1–M9 redesign fix with taxonomy tags; added ADR 0008 (`-ffast-math` removal / bit-identity) and ADR 0009 (in-memory `PhraseMaps`/`SongMaps` fold into `TrackAnalysis v3`). |
| D2.4 Fix inside milestones (M1–M9) | todo | — | — | Milestone slices land in M1 (contracts/MixMachine), M2 (store/models), M3 (preparer/planner), M4 (render/recipes), M6 (NPU cleanup), M7 (UI), M9 (rest/sweeps); `refactor:` vs `feat:`/`fix:` strictly separated. |
| D2.5 Check and repeat (M9 sweeps) | todo | — | — | Subagent sweeps at M9 end until nothing worth fixing remains; Bucket C test cleanup LAST after safety-net mutation check. |
| D3 Seed inventory (replaced by M0.5) | done | this commit | `docs/untangle/DECISION_LOG.md`, Table 3 below | Replaced 6-file seed counts (`109/47/108/51/8/9835`) with full 146-file M0.5 `file:line` inventory and reconciled Table 3 baseline. |
| D4 Done criteria | todo | — | — | Counts, 1-place change rule (3 to demonstrate), clean import graph, final sweep reports nothing worth fixing, behaviour preservation. |

## Table 3 — Part D4 Special-Case Count Tracker

D4 targets: swallowed exceptions at or under 10 (each reasoned); retry/sleep/timeout at or under 5 (MixMachine or I/O clients only); fallback entry points = 1 (LAST_RESORT / classic rule); plan-identity checks = 1 (generation id); `CrossfadeController` at or under 2,500 lines (from B1.2).

| Milestone | Swallowed exceptions | Retry/sleep/timeout | Fallback entry points | Plan-identity checks | CrossfadeController lines | Target met? |
|---|---|---|---|---|---|---|
| D3 seed (djmix52) | 109 | 47 | 51 | 8 | 9,835 | baseline |
| M0.5 inventory | 252 silent (`232` silent/default `runCatching` + `20` silent `catch`; 146-file raw: `256` rc / `90` catch / `165` getOrNull / `39` getOrDefault) | 42 automix constructs (`13` ctrl + `8` svc-automix + `6` UI + `15` engine; 146-file raw: `214`) | 127 sites (`62` ctrl abort calls / `4` verbs + `35` other ctrl + `30` engine; `65` ctrl + `30` eng reasons) | 12 structural (`11` lines) + 4 null | 9,835 (`274` fun, `177` member `var` / `196` total `var`) | baseline (replaces D3 seed; see `DECISION_LOG.md` §1A) |
| M2/M3 (2026-11-24) | to recount at M9 | to recount at M9 | to recount at M9 | plan-identity compares 0 in `CrossfadeController.kt` + `LoopMixSession.kt` (only `plan == null` null-checks remain); `checkShownWatchdog`/`straightRescue` deleted (grep 0) | 9,065 (9,835 → 9,065, −770) | progress only; full recount at M9 |
