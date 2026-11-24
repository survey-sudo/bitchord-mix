# Untangle skill — how AE3 restructures the automix surface

> **Provenance:** This file was synthesized from Part D of the AE3 master
> plan (`BitChord Automix Engine 3 (AE3): the baseline master plan`,
> base `djmix52` `b340828`) because no separate `untangle-codebase`
> `SKILL.md` file was present on disk. It captures the methodology that
> Part D defines for all of Part B's restructuring (B1, B2, B13) and every
> later refactor. Where this file and Part D disagree, Part D wins.

## Core test

**Should these pieces change together?**

Apply it everywhere a structural question comes up: a module boundary, a
class split, a move of a planner, a deletion. If two pieces never need to
change together, a link between them is suspect. If two pieces always
change together but nothing shows it, that hidden coupling is the bug.

## Three failure modes

- **Hidden links** — pieces that must change together but nothing shows
  it. Example: the countdown headline (`loopPlanned`) vs. arming (the
  preparer) — two sources of truth for one state; `PhraseMaps` read by
  controller, preparer and DjMix independently; 48 distinct free-form
  fallback reason strings duplicated across telemetry, Inspector and logs.
- **Needless links** — pieces tied together that change for different
  reasons. Example: the controller calling `planLoop → straightPreparedFor
  → nativeBridge → nativePlanAfterCrown` while PairPreparer also plans;
  planning restart (`onAnalysisUpdated → trigger(force=true)`) tied to
  analysis refinement though they change for different reasons.
- **Surplus** — pieces with no reason of their own to change. Examples:
  vc93 leftovers (`NpuQnnSession`, `NpuQdqSelfTest`, `QNN_ENABLED`);
  `CountdownStabilizer` paths superseded by MixMachine; pass-through
  wrappers between `ChoreoSessionGlue` and `ChoreoRendererBridge`.

**Special cases** are counted on top of those three: swallowed exceptions,
retry/sleep/timeout constructs, fallback entry points, plan-identity
checks. They are the skill's main measure (see Done criteria).

## Five-step process

1. **Ask the core test** — should these pieces change together? Identify
   which of the three failure modes a suspect link falls into.
2. **Inventory first, no changes** — right after M0, before M1 starts.
   Write `docs/untangle/DECISION_LOG.md` listing every hidden link,
   needless link, surplus piece and special case with `file:line`, in the
   skill's format; use subagents to summarise each area; build a
   script-generated skeleton (function bodies stripped) so the whole
   surface fits in context; generate an import graph
   (`tools/untangle/deps.py` → `docs/untangle/deps.svg`) and a
   change-coupling report from git (`git log --name-only` over the last
   300 commits — files that change together). Both are evidence.
3. **Redesign** — for each group of problems, write the one fix that
   removes them, above them in the log. Most fixes are already the Part B
   design (see the D3 seed decision log); record any additional fix as an
   ADR.
4. **Fix inside the milestones**, not as a separate big-bang — M1 fixes
   contracts and MixMachine items, M3 fixes planner and preparer items, M4
   fixes recipes and renderer items, M9 fixes everything left. Each
   strangler slice (B1.2) removes the problems it covers and their special
   cases in the same PR.
5. **Check and repeat** — at the end of M9, run subagents over the scope
   looking for special cases, hidden links, needless links and surplus.
   Fix what they find and repeat until a sweep finds nothing worth
   fixing. Record each sweep's findings and fixes in the decision log.

## D1 — Options chosen by the owner

**Comments: YES.** Remove comments that only restate the code. Keep
comments about things outside the code (devices, vendors, upstream
behaviour, Android quirks). Comments explaining a workaround go when the
redesign removes the workaround. The 108 `// djmixNN` / `// vcNN`
patch-history comments go: history lives in git and `CHANGELOG.md`. Do it
inside each milestone's `refactor:` slice, with the code it described.

**Tests: YES, done LAST (inside M9, after the final untangle sweep).**

- Remove tests that repeat the implementation or cannot fail.
- Rewrite tests tied to internals so they depend on behaviour (contracts,
  MixMachine events and effects, rendered output, `mixq` metrics).
- Fix any that fail without a real bug.
- **Never remove** the invariant tests from B1.2 item 5, the native
  bit-identity probes, the MixMachine table/property/replay tests, or the
  soak test. Those are the safety net.
- Record each removed or rewritten test with a one-line reason in the
  decision log. Report before/after counts, and confirm the safety-net
  suites still fail on a seeded regression (mutate, see fail, revert).

Tests go last because they are the regression safety net during the
restructure. Do only the chosen options, exactly as the skill describes.

## D2 — Scope and order

**Scope:** the whole automix surface — `CrossfadeController.kt`,
`playback/djmix/**`, `playback/loopmix/**`, `playback/smart/**` (shared
and app), `native/loopmix/**`, `native/analyzer/**`,
`app/src/main/cpp/**`, and the automix parts of `PlaybackService`,
`TrackAnalyzer`, `AppSettings`, `SettingsSheet` and `MixViewPopup`, plus
their tests. Upstream-owned code outside these paths is out of scope —
we do not restructure upstream code, which keeps sync cheap (B10).

**Behaviour rule and commit separation.** The skill requires intended
behaviour to stay the same apart from bugs the refactor fixes. AE3 also
changes behaviour on purpose (Part A). Keep the two apart:

- untangle commits are tagged `refactor:` and must pass the soak test
  and the native bit-identity probes unchanged;
- intended behaviour changes are tagged `feat:` / `fix:`, are separate
  commits, and reference a Part A or B item.

Never mix both in one commit.

## D4 — Done criteria (added to C2)

- **Special-case counts** (tracked in `docs/AE3_LEDGER.md` Table 3 per
  milestone, starting from the D3 seed): swallowed exceptions in scope
  ≤ 10, each with a one-line reason why it must not propagate;
  retry/sleep/timeout constructs ≤ 5, all inside MixMachine rules or I/O
  clients; fallback entry points = 1 (the reducer's LAST_RESORT / classic
  rule); plan-identity checks = 1 (generation id).
- **A typical change lands in one place** — demonstrate with 3 examples:
  adding a recipe touches only `recipes/*.json` and its audition render;
  changing the commit-point bars touches only one constant in
  `MixMachine`; adding a telemetry field touches only `MixTelemetry v3`.
- **The import graph is clean:** `djmix-core` imports nothing; nothing
  imports anything more volatile than itself; no cycles. `deps.svg` is
  committed.
- **The final subagent sweep reports nothing worth fixing.**
- **Behaviour is preserved for `refactor:` commits:** soak, native
  bit-identity and MixMachine replay of recorded sessions all pass
  unchanged.

## How to use this file

Run the skill before any structural change to the automix surface. Start
from the decision log, apply the core test to each proposed move, and
check the special-case counts as you land each milestone. If a move
would add a hidden link or a special case, stop and redesign.
