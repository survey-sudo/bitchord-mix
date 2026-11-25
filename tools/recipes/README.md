# Recipe data gate

Run from the repository root:

```sh
python3 tools/recipes/lint_recipes.py
python3 tools/recipes/lint_recipes.py --check-fallbacks
python3 tools/recipes/test_lint_recipes.py
```

The default CLI checks the 26 `RecipeV2` JSON documents in
`djmix-core/src/commonMain/resources/recipes/`, their Android asset mirrors in
`app/src/main/assets/recipes/`, and the zero-I/O registry payloads in
`BundledRecipesJson.kt`. It exits **0** on success and **1** on any violation.
Invalid CLI usage exits **2**. It uses only Python's standard library.
It also compares F1/F5's built-in descriptor tags against the primary effects
in both JSON variants. A capability fallback does not count as a primary effect.

Checks cover schema/field types, finite numbers, known anchors, every declared
length, both stem variants, resolved lane boundaries, low-band crossover,
per-level processor budgets, abrupt eligibility declarations, unique IDs, and
the exact Club/Festival FX family sets from A§5.11. Filter lanes count as sweep
coverage; fallback effects and disabled recipes do not count. Coverage is also
checked separately for the no-stem variants.

To lint a separate catalog or one recipe:

```sh
python3 tools/recipes/lint_recipes.py path/to/recipes
python3 tools/recipes/lint_recipes.py --no-coverage path/to/S1.json
```

The simulation follows `RecipeInterpreter` lane construction and `ChoreoGuard`
semantics: anchor clamping, coincident-point normalization, equal-power curves,
incoming neutral restoration, outgoing tail retirement, and 5 ms low-band scans.
Loop/roll/sweep/SVF configuration does not consume a processor slot in the
current guard. Brake/tape-stop/spinback share one slot, and riser/impact share one
slot. Static recipe checks validate jump declarations; the Kotlin test exercises
`PairPlanner`'s actual jump/cooldown eligibility. Audio content, exact frozen
geometry, phrase-end placement, and native FX behavior still require Kotlin,
`tools/mixq/`, and native audition evidence.

## F1/F5 regression coverage

`--check-fallbacks` must exit **0** for the bundled repository. It enumerates
every mixed combination of optional capability availability at every declared
length in both stem variants. The unit tests exercise both successful repository
CLI modes and a deliberately broken F1 fixture that fails only the fallback audit.

F1's incoming impact shares `requiredCap: RISER` with the build. With the riser
available, riser + impact share BUILD alongside GATE and ECHO (three slots).
Without it, the existing FLANGER substitution + GATE + ECHO use three slots;
the impact is omitted. The authored roll, sweep, gain/bass handoff and echo stay
intact. Removing the impact capability gate is a negative regression test.

F5 directly configures PHASER, REVERB, GATE and sweep. Its FLANGER is exclusively
the PHASER capability fallback, so the primary descriptor must omit FLANGER.
Tests compare both variants, all three JSON copies, and descriptor tags; a
matching core/asset pair cannot conceal stale embedded data or metadata.

### Coordination note for the embedded/catalog owners

The current source extraction places embedded JSON in `BundledRecipesJson.kt`,
not `RecipeRegistry.kt`. These two companion changes require those files' owner:

- Regenerate F1's embedded payload from core `F1.json` (both incoming IMPACT
  steps now contain `"requiredCap":"RISER"`).
- Remove only `RecipeFxType.FLANGER` from `BuiltInRecipeCatalog.F5.fxTags`.
  Keep F5's existing PHASER-to-FLANGER fallback in all JSON copies.

Until those owner changes land, repository checks intentionally fail with
`EMBEDDED_MISMATCH` / `DESCRIPTOR_MISMATCH`; subset checks do not hide that gap.
`followup_owner_sync.patch` contains exactly those two companion edits and passes
`git apply --check`; the restricted recipe/audition writer did not apply it.

## Remaining limits

- All six required Club families and all ten required Festival families occur
  directly in both normal and no-stem JSON variants. No recipe files are missing.
- The current guard exempts **S5 Simple fade** from its one-beat dual-bass test.
  The CLI preserves that exception and prints a notice. It still rejects
  simultaneous full lows. This does not establish the one-beat audio gate for
  S5; that requires the source-sensitive `mixq`/audition check.
- Abrupt runtime eligibility follows ADR 0010: a tempo delta above 10%, or
  above 8% with a genre change, healthy grid confidence, a clean phrase/section
  boundary, and an eight-transition cooldown. This is stricter than the master
  plan's original tempo-above-8%-or-genre wording. JSON records the jump flag;
  the planner enforces thresholds and the interpreter alone only gates the tier.
- `tools/audition/` loads all 26 canonical definitions and rejects unknown IDs.
  Its source-driven synthetic fixtures exercise all recipes' no-stem lanes/FX.
  Real auditions consume frozen JVM exports without recipe substitution or
  exporter-only geometry overlays. The optional Python fade is labeled as a
  synthetic control and cannot establish a recipe-quality pass. See that
  directory's README for the JVM producer/native integration contract and the
  remaining analysis/stem/listening gates.

`RecipeCoverageAndVarietyTest` in the app test source set is independent of the
legacy shared 20-recipe `RecipesTest`. It uses immutable `RecipeRegistry.builtIn`
definitions and does not install/reset overrides. It checks every sliding
six-transition window in a twelve-transition Festival sequence, using planner
history without exclusions or taste weights. Run the app JVM test through the
project's authorized `dvb` validation environment; local Python success alone
does not establish Kotlin compilation or planner-test success.
The F1 regression additionally invokes the real interpreter/guard across both
variants, all declared lengths, and every RISER/GATE availability combination.
