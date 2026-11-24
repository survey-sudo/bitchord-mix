# AE3 Recipes

Recipes are data: each recipe is a JSON document with `schema = 2`, bundled in the APK from
`djmix-core/src/commonMain/resources/recipes/*.json`, loaded by `RecipeRegistry` and interpreted by
`RecipeInterpreter` into a `TransitionScriptV1` for a given pair context (Part B5, ADR 0007).
The catalog is exactly **26 recipes**: `S1..S6` (Subtle), `C1..C9` (Club), `F1..F11` (Festival);
`BundledRecipesJson.orderedIds` fixes the canonical order.

## Anatomy of `RecipeV2`

Top-level fields: `schema`, `id`, `name`, `description`, `level` (`SUBTLE | CLUB | FESTIVAL`),
`family` (`BLEND | SWAP | CUT | FX | DROP`), `abruptness` (`SMOOTH | PUNCHY | ABRUPT`),
`requirements`, `lengthOptionsBars`, `placementPrefs`, `steps`, `noStemSteps`, `energyDelta`, `enabled`.

- `requirements` (`RecipeRequirementsV2`): `maxTempoGap`, `requiresBeatMatch`, `requiresHarmony`,
  `genreAffinity` (map), `requiresDrops`, `requiresOutDrop`, `requiresOutLoop`, `requiredStems`
  (list of `{deck, stem}`), `minStemCount`, `minGridConfidence` (default 0.45), `minOverlapBars`
  (default 2.0), `requiredCapabilities` (renderer `FxCapability` bits), `requiresTempoOrGenreJump`.
- `steps` / `noStemSteps`: ordered steps of three kinds —
  `lane_curve` (`deck`, `kind`: envelope lane, `shape`, `points`: `WaypointV2 {time, value,
  scaleByFxDepth, depthBase}`, `steps`, `holdNeutralAtStart`, `ensureNeutralAtReleaseForIn`),
  `swap_lows` (`at`, `widthBeats`, `inLowBeforeDb`, `outLowAfterDb`, `useBassStems`),
  `configure_fx` (`deck`, `fx`, `requiredCap`, `fallbackFx`, `params`, `flag`, `startTime`,
  `endTime`, `wetCurve`, `fallbackWetCurve`).
- Times are anchor-relative (`StepTimeV2`): `anchor` = one of `ZERO, IN_AUDIBLE, SWAP, RELEASE,
  OUT_STOP, PRE_FRAC, POST_FRAC, OVERLAP_FRAC, A_PLUS_CAPPED_S, S_PLUS_CAPPED_R, SWAP_BACK_A,
  SWAP_BACK_ZERO, RELEASE_BACK_S, VOCAL_ENTRY, FADE_START`, plus `param` (for `*_FRAC` anchors),
  `offsetBars` / `offsetBeats` / `offsetSec`, and optional `minAt` / `maxAt` clamps.

Minimal example (excerpt of `S1.json`):

```json
{
  "schema": 2, "id": "S1", "name": "Smooth blend", "description": "Beat-matched equal-power blend with gentle low-end handoff",
  "level": "SUBTLE", "family": "BLEND", "abruptness": "SMOOTH",
  "requirements": { "maxTempoGap": 0.03, "requiresBeatMatch": true, "requiredStems": [], "minGridConfidence": 0.45, "minOverlapBars": 2.0 },
  "lengthOptionsBars": [8, 16],
  "placementPrefs": { "swapRule": "LAST_PHRASE", "preferOutroExit": true, "preferIntroEntry": true },
  "steps": [
    { "type": "lane_curve", "deck": "OUT", "kind": "GAIN", "shape": "EQUAL_POWER_FALL",
      "points": [ { "time": { "anchor": "IN_AUDIBLE" }, "value": 1.0 },
                  { "time": { "anchor": "RELEASE" }, "value": 0.0 } ], "steps": 16 },
    { "type": "swap_lows", "at": { "anchor": "SWAP" }, "widthBeats": 0.25,
      "inLowBeforeDb": -24.0, "outLowAfterDb": -24.0, "useBassStems": true }
  ],
  "noStemSteps": [ "…same with useBassStems:false…" ],
  "energyDelta": 0.0, "enabled": true
}
```

Author also **every** recipe must carry a `noStemSteps` block (the linter enforces non-empty `steps`
and `noStemSteps`), so thermal/backgrounded degradation and low-stem pairs can always run the same
recipe's no-stem variant.

## Linting (`RecipeLinter` + `ChoreoGuard`)

- `djmix-core/recipes/RecipeLinter.kt` runs in the unit tests AND as a CLI-check on every registry
  load; checks: `BAD_SCHEMA` (schema must be 2), `BLANK_DESCRIPTION`, `EMPTY_STEPS`,
  `EMPTY_NO_STEM_STEPS`, `DUPLICATE_ID`, abruptness rules — `ABRUPT_FLAG_MISSING`,
  `ABRUPT_NOT_FESTIVAL` (abrupt recipes must be FESTIVAL), `ABRUPT_NO_JUMP_FLAG`
  (`requiresTempoOrGenreJump = true`), `ABRUPT_BEATMATCH` (abrupt must set `requiresBeatMatch = false`)
  — plus `NON_FINITE_REQ`, `EMPTY_POINTS`, `NON_FINITE_WAYPOINT`, `BAD_SWAP_LOWS`,
  finite anchor times, no stem steps inside `noStemSteps` (`forbidStems = true`).
- `djmix-core/recipes/ChoreoGuard.kt` validates every compiled `TransitionScriptV1` against its plan:
  schema/knots/loop bounds sanity, `levelGain` finiteness, ORDER of OUT stop vs release, one lane per
  `(deck, kind)` (no duplicate envelope lanes), OUT and IN `GAIN` lanes present, boundary neutrality
  (no lane non-neutral at the splice/handover), bass-clash check (`checkBassClash`), FX params sanity.
  `guardChoreo(...)` wraps it as a typed `MixOutcome`.
- Placement rules (ADR 0010) live in `planner/PlacementEvaluator.kt`: exit/entry mid-vocal hard rejects
  (Syvenu > 0.5 within ±1 beat), exit-inside-build reject, abrupt-on-non-jump reject — abrupt recipes
  (`F2 Brake slam`, `F7 Spinback cut`, `F9 Backspin into drop`) never fire as a fallback and are
  rationed to |ΔBPM| > 8 % (or > 10 % without a genre change) at a phrase end; the low-confidence
  fallback is `F5 Tunnel` (0.40) or `C2 Filter wash + echo` (0.32), never F2/F5-slam.

## Live preview (Recipe Lab) and auditioning

- **Recipe Lab** (Developer settings): pick two tracks from the library and a recipe (or Auto), see the
  planned exit/entry over both waveforms, and press **Preview** — a 30 s render around the swap plays
  offline (`onRecipeLabPreview` in `DjmixDeveloperSettingsSheet`; the trigger is gated by the
  `"recipe_lab"` kill switch in `DjmixFlags`).
- **Live reload:** in debug builds, recipe JSON overrides load at runtime (no rebuild). The runtime
  files live under `Android/data/com.music.bitchord.loopfix/files/recipes/`; drop a modified
  `RECIPEID.json` there and re-select it — `RecipeRegistry.overrideFromJson` parses the override,
  lints it, and swaps it into the active registry (invalid overrides are refused with the lint issue).
- **Audition (devbox, offline, ears before APK):** `tools/audition/audition_render.py` takes a
  `pairs.json`, decodes both decks via ffmpeg to f32 48 kHz stereo, builds a `script.json` per recipe
  (unique `TransitionScriptV1`, same schema as on-device), invokes the host renderer
  `native/loopmix/loop_mix_renderer_ab_main --render <script.json> --out <wav>`, runs `tools/mixq`
  self-checks on every WAV, then writes `index.md` + a self-contained `index.html` rater
  (`index.html` has an `<audio>` player per pair × recipe, 1–5 stars, keep / fix / drop toggles,
  notes, and a one-click `ratings.json` download). Example:

  ```bash
  python3 tools/audition/audition_render.py --pairs pairs.json --out audition/AE3-build \
      --binary native/loopmix/loop_mix_renderer_ab_main --recipes F1,C7,S1 --upload
  ```

  Ratings the owner hands back gate the release: see [QUALITY.md](QUALITY.md).

## Per-level effect coverage (asserted by unit test, Part A §5)

- Club: loop, roll, sweep, flanger, phaser, echo — each in at least one Club recipe.
- Festival: all of those, plus riser, brake, spinback and gate.
- Variety: at Festival, over any 6 consecutive transitions, ≥ 4 distinct recipes and ≥ 5 distinct FX types.

## The complete 26-recipe table

| ID | Level | Family | Abruptness | FX / lanes used | Stems required | Description |
|---|---|---|---|---|---|---|
| S1 | SUBTLE | BLEND | SMOOTH | — (GAIN isolator lows swap) | none | Smooth blend — beat-matched equal-power blend with gentle low-end handoff |
| S2 | SUBTLE | SWAP | SMOOTH | — (bass-stem swap) | OUT BASS, IN BASS | Bass swap — equal-power blend trading bass stems cleanly on the downbeat |
| S3 | SUBTLE | FX | SMOOTH | LPF muffle (`LOW_PASS_HZ`, `SVF_Q`) | none | Muffle-in — incoming track opens from a warm resonant low-pass muffle |
| S4 | SUBTLE | CUT | PUNCHY | echo (`ECHO_SEND`) | none | Echo out — tempo-synced ping-pong echo throw releasing into incoming bar 1 |
| S5 | SUBTLE | BLEND | SMOOTH | — | none | Simple fade — restrained equal-power fade for ballads and drifting tempi |
| S6 | SUBTLE | BLEND | SMOOTH | — | none | Breakdown blend — clean dry blend landing into the incoming breakdown |
| C1 | CLUB | SWAP | SMOOTH | echo, HPF, stem lanes | OUT+IN DRUMS/BASS | Drum swap — drums and bass trade cleanly across bars with an echo throw |
| C2 | CLUB | FX | PUNCHY | filter (`FILTER_RES`, HPF/LPF), echo, reverb | none | Filter wash + echo — resonant high-pass wash and tempo echo bridging any tempo gap |
| C3 | CLUB | SWAP | SMOOTH | echo, HPF, stem lanes | OUT VOCALS + BASS | Acapella ride — outgoing vocals ride over the incoming groove then echo out |
| C4 | CLUB | SWAP | SMOOTH | HPF, stem lanes | OUT VOCALS + BASS | Instrumental bed — outgoing instrumental ducks for the incoming vocal entry |
| C5 | CLUB | BLEND | SMOOTH | loop + HPF | none | Loop extend — beat-locked outgoing outro loop holds under the incoming intro |
| C6 | CLUB | FX | SMOOTH | flanger + phaser | none | Phaser dissolve — tempo-synced phaser and stereo flanger dissolve into the mix |
| C7 | CLUB | DROP | PUNCHY | roll (`LOOP_ROLL`, `ROLL`), sweep, riser, HPF | none (rollover) | Drop on the beat — tightening snare roll and sweep landing right on the incoming drop |
| C8 | CLUB | FX | PUNCHY | loop roll + HPF | none | Loop roll out — beat-locked loop tightens into a roll and cuts cleanly on bar 1 |
| C9 | CLUB | FX | PUNCHY | alternating HPF/LPF + flanger + echo | none | Filter ping-pong — bar-locked HPF/LPF sweeps with jet flanger accent |
| F1 | FESTIVAL | DROP | PUNCHY | riser, gate, loop roll, filter, echo, impact, stems | IN DRUMS/BASS/VOCALS | Riser drop — multi-layer riser, beat gate, and loop roll launching into the drop |
| F2 | FESTIVAL | CUT | ABRUPT | brake (`BRAKE`), reverb, riser | none (requiresTempoOrGenreJump) | Brake slam — exponential turntable brake with LPF roll-off slamming on the 1 |
| F3 | FESTIVAL | SWAP | SMOOTH | stem lanes, echo, flanger | OUT+IN DRUMS/BASS | Stem shuffle — staggered drum, bass, and vocal stem swap with echo and flanger |
| F4 | FESTIVAL | SWAP | SMOOTH | stem lanes, pump (`PUMP_DEPTH`), reverb, echo | OUT VOCALS + BASS | Acapella mashup — outgoing vocal mashed over incoming beat with pump, reverb, and echo |
| F5 | FESTIVAL | FX | PUNCHY | phaser, reverb, opposing filters, gate | none | Tunnel — phaser, reverb, and opposing filter tunnel bridging tempo or grid gaps |
| F6 | FESTIVAL | FX | SMOOTH | pump + flanger | none | Pump rider — beat-locked sidechain pump and jet flanger across a long phrase blend |
| F7 | FESTIVAL | CUT | ABRUPT | spinback (`SPINBACK`), echo | none (requiresTempoOrGenreJump) | Spinback cut — reverse platter spinback and echo throw into the incoming 1 |
| F8 | FESTIVAL | DROP | PUNCHY | riser, gate, echo, impact, stems | IN DRUMS/BASS, OUT BASS | Double drop — aligned drops collide on bar 1 with riser, gate, and echo |
| F9 | FESTIVAL | CUT | ABRUPT | spinback + impact + echo | none (requiresTempoOrGenreJump) | Backspin into drop — accelerating backspin releasing with impact onto the incoming groove |
| F10 | FESTIVAL | FX | PUNCHY | echo freeze stutter, gate, loop roll, HPF | none | Echo freeze — stutter-freeze echo on the final beat launching cleanly into the drop |
| F11 | FESTIVAL | DROP | PUNCHY | riser + impact + roll + filter | none | Riser + impact cut — tempo-synced riser build cutting into an impact hit on the drop |

`energyDelta` values of note: S6 = −0.1 (intentional energy dip), the punchy Club recipes C7 = 0.25, C8 = 0.15, C9 = 0.1, F1 = 0.25, F9 = 0.2, F10 = 0.25, F11 = 0.3, F8 = 0.4. Recipe availability additionally depends
on `DjmixFlags` (Safe mode pins `S1`/`S5` only) and `enabled = false` on recipes voted "drop"
([RELEASE.md](RELEASE.md)).

## Adding a recipe (the "one place" test, Part D4)

1. Write `djmix-core/src/commonMain/resources/recipes/<ID>.json` (`schema = 2`).
2. Add the catalog entry to `BuiltInRecipeCatalog` (one-liner reasons + genre affinity) and the id to
   `BundledRecipesJson.orderedIds`.
3. `djmix-core` tests: linter green, per-level FX coverage still holds, interpreter turns it into a
   valid choreography for a synthetic pair.
4. Audition render for the recipe across the showcase pairs; tune by ear before shipping.
