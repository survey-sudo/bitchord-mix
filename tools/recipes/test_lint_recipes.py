#!/usr/bin/env python3
"""Regression tests for the AE3 recipe CLI; no Gradle or third-party packages."""

from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

# Keep imports independent of the caller's current working directory.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import lint_recipes as lint  # noqa: E402


class RecipeLintTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.recipes, issues = lint.load_paths([lint.ROOT / lint.CORE_RECIPES])
        if issues:
            raise AssertionError(issues)
        cls.by_id = {recipe["id"]: recipe for recipe in cls.recipes}

    def recipe(self, rid="S1"):
        return deepcopy(self.by_id[rid])

    def assertViolation(self, recipe, fragment, *, check_fallbacks=False):
        issues = lint.lint_recipe(recipe, check_fallbacks=check_fallbacks)
        self.assertTrue(issues, "Mutation should fail lint")
        self.assertIn(fragment, "\n".join(str(issue) for issue in issues))

    def test_all_26_bundled_recipes_mirrors_and_embedded_payloads_pass(self):
        for check_fallbacks in (False, True):
            with self.subTest(check_fallbacks=check_fallbacks):
                recipes, issues = lint.lint_repository(check_fallbacks=check_fallbacks)
                self.assertEqual(set(lint.EXPECTED_IDS), {recipe["id"] for recipe in recipes})
                self.assertEqual(26, len(recipes))
                self.assertEqual([], issues, "\n".join(map(str, issues)))

    def test_required_families_are_present_at_each_exact_level_and_variant(self):
        for variant in ("steps", "noStemSteps"):
            for level, expected in (("CLUB", lint.CLUB_FX), ("FESTIVAL", lint.FESTIVAL_FX)):
                with self.subTest(variant=variant, level=level):
                    actual = set().union(*(lint.direct_fx(r, variant) for r in self.recipes if r["level"] == level))
                    self.assertLessEqual(expected, actual)

    def test_invalid_top_level_shapes_and_schemas_fail_without_crashing(self):
        for invalid in (None, [], "recipe", 26, {"id": []}):
            with self.subTest(invalid=invalid):
                self.assertTrue(lint.lint_recipe(invalid))
                self.assertTrue(lint.lint_catalog([invalid]))
        for schema in (1, 3, True, 2.0, "2"):
            recipe = self.recipe()
            recipe["schema"] = schema
            self.assertViolation(recipe, "SCHEMA")

    def test_required_metadata_and_positive_integer_lengths(self):
        for key, value in (("id", " "), ("name", ""), ("description", ""),
                           ("level", "LOUD"), ("family", "fade"), ("abruptness", None),
                           ("enabled", 1), ("lengthOptionsBars", []),
                           ("lengthOptionsBars", [0]), ("lengthOptionsBars", [8.0]),
                           ("lengthOptionsBars", [True])):
            with self.subTest(key=key, value=value):
                recipe = self.recipe()
                recipe[key] = value
                self.assertViolation(recipe, "SCHEMA")

    def test_invalid_requirements_and_preferences_fail(self):
        for key, value in (("minGridConfidence", 1.1), ("minStemCount", -1),
                           ("minOverlapBars", 0), ("requiredStems", [{"deck": "B", "stem": "BASS"}]),
                           ("requiresBeatMatch", "true"), ("requiredCapabilities", ["MAGIC"]),
                           ("genreAffinity", {"DANCE": float("nan")})):
            with self.subTest(key=key):
                recipe = self.recipe()
                recipe["requirements"][key] = value
                self.assertViolation(recipe, "SCHEMA")
        recipe = self.recipe()
        recipe["placementPrefs"]["avoidBuildExit"] = 1
        self.assertViolation(recipe, "placementPrefs.avoidBuildExit")

    def test_unknown_keys_remain_forward_compatible(self):
        recipe = self.recipe()
        recipe["futureMetadata"] = {"author": "fixture"}
        self.assertEqual([], lint.lint_recipe(recipe))

    def test_unknown_step_deck_lane_shape_and_effect_fail(self):
        for field, value in (("type", "unknown"), ("deck", "BOTH"),
                             ("kind", "VOLUME"), ("shape", "BEZIER"), ("steps", 2.5)):
            with self.subTest(field=field):
                recipe = self.recipe()
                recipe["steps"][0][field] = value
                self.assertViolation(recipe, "SCHEMA")
        recipe = self.recipe("S4")
        next(step for step in recipe["steps"] if step["type"] == "configure_fx")["fx"] = "UNKNOWN"
        self.assertViolation(recipe, "SCHEMA")

    def test_all_anchor_names_resolve_including_bounds_and_offsets(self):
        ctx = lint.Context(release=16.0, swap=8.0, audible=2.0, stop=16.2)
        for anchor in lint.ANCHORS:
            with self.subTest(anchor=anchor):
                time = {"anchor": anchor, "param": 0.5, "offsetBars": 0.5,
                        "offsetBeats": -0.5, "offsetSec": 0.1, "minAt": "ZERO", "maxAt": "OUT_STOP"}
                self.assertGreaterEqual(ctx.resolve(time, "OUT"), 0.0)
                self.assertLessEqual(ctx.resolve(time, "OUT"), ctx.stop)
        self.assertEqual(5.0, ctx.anchor("PRE_FRAC", 0.5))
        self.assertEqual(12.0, ctx.anchor("POST_FRAC", 0.5))
        self.assertEqual(8.0, ctx.anchor("OVERLAP_FRAC", 0.5))
        self.assertEqual(4.0, ctx.anchor("SWAP_BACK_A", 2.0))
        self.assertEqual(12.0, ctx.anchor("FADE_START"))
        self.assertEqual(16.0, ctx.resolve({"anchor": "OUT_STOP"}, "IN"))

    def test_unknown_anchors_and_non_finite_values_fail(self):
        for field, value in (("anchor", "MISSING"), ("minAt", "MISSING"),
                             ("maxAt", "MISSING"), ("offsetSec", float("inf")),
                             ("offsetBars", "2"), ("param", float("nan"))):
            with self.subTest(field=field):
                recipe = self.recipe()
                recipe["steps"][0]["points"][0]["time"][field] = value
                self.assertViolation(recipe, "SCHEMA")
        recipe = self.recipe()
        recipe["steps"][0]["points"][0]["value"] = float("nan")
        self.assertViolation(recipe, "SCHEMA")
        recipe = self.recipe("S4")
        next(step for step in recipe["steps"] if step["type"] == "configure_fx")["params"]["wet"] = float("inf")
        self.assertViolation(recipe, "SCHEMA")

    def test_empty_variant_points_or_missing_gain_fails(self):
        for variant in ("steps", "noStemSteps"):
            recipe = self.recipe()
            recipe[variant] = []
            self.assertViolation(recipe, "must be non-empty")
        recipe = self.recipe()
        recipe["steps"][0]["points"] = []
        self.assertViolation(recipe, "waypoint list")
        recipe = self.recipe()
        recipe["steps"] = [s for s in recipe["steps"] if s.get("deck") != "OUT"]
        self.assertViolation(recipe, "Missing OUT/IN GAIN")

    def test_splice_checks_each_lane_and_incoming_mute(self):
        recipe = self.recipe()
        recipe["steps"].append({"type": "lane_curve", "deck": "OUT", "kind": "FILTER_KNOB",
                                "points": [{"time": {"anchor": "ZERO"}, "value": 0.4}]})
        self.assertViolation(recipe, "non-neutral at splice")
        recipe = self.recipe()
        incoming = next(s for s in recipe["steps"] if s.get("deck") == "IN")
        incoming["points"][0]["value"] = 0.5
        self.assertViolation(recipe, "IN GAIN not muted")
        recipe = self.recipe()
        recipe["steps"][0]["points"][0]["value"] = 0.5
        self.assertViolation(recipe, "OUT GAIN non-neutral at splice")

    def test_handover_and_outgoing_gain_must_retire(self):
        recipe = self.recipe()
        recipe["steps"][0]["points"][-1]["value"] = 0.25
        self.assertViolation(recipe, "OUT GAIN not zero")
        recipe = self.recipe()
        recipe["noStemSteps"].append({"type": "lane_curve", "deck": "IN", "kind": "FILTER_KNOB",
                                      "ensureNeutralAtReleaseForIn": False,
                                      "points": [{"time": {"anchor": "RELEASE"}, "value": 0.4}]})
        self.assertViolation(recipe, "non-neutral at handover")

    def test_interpreter_incoming_neutral_restoration_is_supported(self):
        recipe = self.recipe()
        recipe["steps"].append({"type": "lane_curve", "deck": "IN", "kind": "FILTER_KNOB",
                                "holdNeutralAtStart": False,
                                "points": [{"time": {"anchor": "ZERO"}, "value": -0.4}]})
        self.assertEqual([], lint.lint_recipe(recipe))

    def test_outgoing_wet_tail_and_resonance_cap(self):
        recipe = self.recipe("S4")
        echo = next(s for s in recipe["steps"] if s.get("fx") == "ECHO")
        echo["wetCurve"][-1]["value"] = 0.3
        self.assertViolation(recipe, "tail not retired")
        recipe = self.recipe()
        recipe["steps"].append({"type": "lane_curve", "deck": "OUT", "kind": "FILTER_RES",
                                "points": [{"time": {"anchor": "ZERO"}, "value": 1.0},
                                           {"time": {"anchor": "SWAP"}, "value": 6.1}]})
        self.assertViolation(recipe, "safe cap 6")

    def test_full_bass_doubling_and_sustained_overlap_are_rejected(self):
        recipe = self.recipe("S2")
        swap = next(s for s in recipe["steps"] if s["type"] == "swap_lows")
        swap.update(inLowBeforeDb=0.0, outLowAfterDb=0.0)
        self.assertViolation(recipe, "simultaneous full lows")
        recipe = self.recipe("S2")
        recipe["noStemSteps"] = [s for s in recipe["noStemSteps"] if s["type"] != "swap_lows" and s.get("kind") != "LOW_DB"]
        incoming = next(s for s in recipe["noStemSteps"] if s.get("deck") == "IN" and s.get("kind") == "GAIN")
        incoming["points"][-1]["time"] = {"anchor": "RELEASE"}
        self.assertViolation(recipe, "dual lows above -6 dB")

    def test_s5_exception_matches_runtime_but_does_not_exempt_other_ids(self):
        recipe = self.recipe("S5")
        self.assertEqual([], lint.lint_recipe(recipe))
        recipe["id"] = "custom-fade"
        self.assertViolation(recipe, "dual lows above -6 dB")

    def test_no_stem_variant_cannot_target_stems(self):
        recipe = self.recipe()
        next(s for s in recipe["noStemSteps"] if s["type"] == "swap_lows")["useBassStems"] = True
        self.assertViolation(recipe, "uses bass stems")
        recipe = self.recipe()
        recipe["noStemSteps"][0]["kind"] = "STEM_BASS"
        self.assertViolation(recipe, "uses a stem lane")

    def test_fx_budget_counts_processors_not_tags_or_loop_roll_aliases(self):
        # C6 has sweep + phaser + flanger but only two counted processor slots.
        self.assertEqual([], lint.lint_recipe(self.recipe("C6")))
        self.assertEqual([], lint.lint_recipe(self.recipe("C8")))
        recipe = self.recipe()
        recipe["steps"] += [{"type": "configure_fx", "deck": "OUT", "fx": fx} for fx in ("ECHO", "REVERB")]
        self.assertViolation(recipe, "FX budget exceeded")

    def test_festival_riser_and_impact_share_the_build_slot(self):
        self.assertEqual([], lint.lint_recipe(self.recipe("F1")))
        recipe = self.recipe("F1")
        recipe["steps"].append({"type": "configure_fx", "deck": "OUT", "fx": "PUMP"})
        self.assertViolation(recipe, "FX budget exceeded")

    def test_f1_riser_and_impact_share_capability_and_fallback_budget(self):
        recipe = self.recipe("F1")
        for variant in ("steps", "noStemSteps"):
            steps = recipe[variant]
            self.assertEqual("FLANGER", next(s for s in steps if s.get("fx") == "RISER")["fallbackFx"])
            self.assertEqual("RISER", next(s for s in steps if s.get("fx") == "IMPACT")["requiredCap"])
            for bars in recipe["lengthOptionsBars"]:
                ctx = lint.Context(bars * 2.0, bars * 2.0, 0, bars * 2.0 + 0.2)
                for missing, expected in ((frozenset(), {"BUILD", "GATE", "ECHO"}),
                                          (frozenset({"RISER"}), {"FLANGER", "GATE", "ECHO"}),
                                          (frozenset({"RISER", "GATE"}), {"FLANGER", "ECHO"})):
                    with self.subTest(variant=variant, bars=bars, missing=missing):
                        _, slots = lint.compile_lanes(recipe, variant, ctx, missing)
                        self.assertEqual(expected, slots)
        self.assertEqual([], lint.lint_recipe(recipe, check_fallbacks=True))

    def test_strict_fallback_audit_rejects_ungated_f1_impact_regression(self):
        recipe = self.recipe("F1")
        next(s for s in recipe["steps"] if s.get("fx") == "IMPACT").pop("requiredCap", None)
        self.assertViolation(recipe, "missing=['RISER']", check_fallbacks=True)
        self.assertEqual([], lint.lint_recipe(self.recipe("F2"), check_fallbacks=True))

    def test_f5_flanger_is_a_phaser_fallback_not_a_primary_effect(self):
        recipe = self.recipe("F5")
        for variant in ("steps", "noStemSteps"):
            phaser = next(s for s in recipe[variant] if s.get("fx") == "PHASER")
            self.assertEqual(("PHASER", "FLANGER"), (phaser["requiredCap"], phaser["fallbackFx"]))
            self.assertEqual({"PHASER", "REVERB", "GATE", "SWEEP"}, lint.direct_fx(recipe, variant))
        self.assertEqual([], lint.lint_recipe(recipe, check_fallbacks=True))
        source = (lint.ROOT / lint.DESCRIPTORS).read_text()
        self.assertEqual([], lint.lint_descriptors(source, self.by_id))
        drifted = source
        start = drifted.index("val F5 = RecipeCatalogEntry(")
        drifted = drifted[:start] + drifted[start:].replace("fxTags = setOf(", "fxTags = setOf(RecipeFxType.FLANGER, ", 1)
        issues = lint.lint_descriptors(drifted, self.by_id)
        self.assertTrue(any(i.recipe_id == "F5" and i.code == "DESCRIPTOR_MISMATCH" for i in issues))

    def test_fx_interval_must_resolve_forward(self):
        recipe = self.recipe("F2")
        brake = next(s for s in recipe["steps"] if s.get("fx") == "BRAKE")
        brake["startTime"] = {"anchor": "RELEASE"}
        brake["endTime"] = {"anchor": "ZERO"}
        self.assertViolation(recipe, "empty or reversed")

    def test_abrupt_class_level_and_jump_declaration_are_required(self):
        for rid in ("F2", "F7", "F9"):
            for section, key, value in ((None, "level", "CLUB"), (None, "abruptness", "PUNCHY"),
                                        ("requirements", "requiresTempoOrGenreJump", False),
                                        ("requirements", "requiresBeatMatch", True)):
                with self.subTest(rid=rid, key=key):
                    recipe = self.recipe(rid)
                    (recipe if section is None else recipe[section])[key] = value
                    self.assertViolation(recipe, "ABRUPT_GATE")
        recipe = self.recipe()
        recipe["steps"].append({"type": "configure_fx", "deck": "OUT", "fx": "SPINBACK"})
        self.assertViolation(recipe, "ABRUPT_GATE")

    def test_coverage_does_not_count_fallbacks_disabled_or_other_levels(self):
        recipes = deepcopy(self.recipes)
        club = next(r for r in recipes if r["id"] == "C6")
        club["enabled"] = False
        issues = lint.lint_catalog(recipes)
        self.assertTrue(any(i.code == "FX_COVERAGE" and "CLUB steps missing PHASER" in i.message for i in issues))
        # Festival PHASER cannot satisfy Club, and a fallback tag is not coverage.
        recipe = self.recipe("C5")
        recipe["steps"][0]["fallbackFx"] = "PHASER"
        self.assertNotIn("PHASER", lint.direct_fx(recipe))
        self.assertIn("SWEEP", lint.direct_fx(recipe))

    def test_duplicate_recipe_ids_are_rejected(self):
        issues = lint.lint_catalog([self.recipe(), self.recipe()], coverage=False)
        self.assertTrue(any(i.code == "DUPLICATE_ID" for i in issues))

    def test_malformed_step_list_cannot_crash_catalog_coverage(self):
        for malformed in (None, "steps", [None], [42], [{}]):
            recipe = self.recipe()
            recipe["steps"] = malformed
            self.assertTrue(lint.lint_catalog([recipe]))
        recipe = self.recipe("S4")
        next(s for s in recipe["steps"] if s.get("fx") == "ECHO")["fx"] = []
        self.assertTrue(lint.lint_catalog([recipe]))

    def test_normalization_and_interpolation_match_interpreter_rules(self):
        self.assertEqual([(0.0, 0.0), (0.999, 0.5), (1.0, 1.0)],
                         lint.normalize([(0, 0), (1, 0.5), (1, 0.5), (1, 1)], 1.0))
        self.assertAlmostEqual(200.0, lint.Lane([(0, 20), (2, 2000)], exponential=True).at(1))
        self.assertEqual(0.5, lint.Lane([(0, 0), (2, 1)]).at(1))

    def test_missing_empty_invalid_duplicate_key_and_filename_paths_fail(self):
        with tempfile.TemporaryDirectory(prefix="recipe-lint-") as directory:
            root = Path(directory)
            for path in (root, root / "missing.json"):
                self.assertTrue(lint.load_paths([path])[1])
            recipe_path = root / "S1.json"
            for text in ('{', '{"id":"S1","id":"S2"}'):
                recipe_path.write_text(text)
                self.assertTrue(lint.load_paths([recipe_path])[1])
            wrong_path = root / "wrong.json"
            wrong_path.write_text(json.dumps(self.recipe()))
            self.assertEqual("FILENAME_ID", lint.load_paths([wrong_path])[1][0].code)

    def test_cli_exit_status_for_valid_and_invalid_inputs(self):
        script = Path(lint.__file__)
        with tempfile.TemporaryDirectory(prefix="recipe-lint-") as directory:
            path = Path(directory) / "S1.json"
            path.write_text(json.dumps(self.recipe()))
            valid = subprocess.run([sys.executable, str(script), "--no-coverage", str(path)], capture_output=True, text=True, check=False)
            self.assertEqual(0, valid.returncode, valid.stderr)
            self.assertIn("PASS", valid.stdout)
            path.write_text('{"schema":1}')
            invalid = subprocess.run([sys.executable, str(script), "--no-coverage", str(path)], capture_output=True, text=True, check=False)
            self.assertEqual(1, invalid.returncode)
            self.assertIn("SCHEMA", invalid.stderr)
            self.assertNotIn("Traceback", invalid.stderr)

    def test_repository_mirror_missing_id_and_missing_embedded_fail(self):
        # A tiny isolated repository is sufficient to exercise mirror diagnostics;
        # core schema/coverage simulation has independent positive evidence above.
        with tempfile.TemporaryDirectory(prefix="recipe-lint-") as directory:
            root = Path(directory)
            core = root / lint.CORE_RECIPES
            assets = root / lint.APP_RECIPES
            core.mkdir(parents=True)
            assets.mkdir(parents=True)
            recipe = self.recipe()
            (core / "S1.json").write_text(json.dumps(recipe))
            recipe["name"] = "Drifted asset"
            (assets / "S1.json").write_text(json.dumps(recipe))
            _, issues = lint.lint_repository(root)
            codes = {issue.code for issue in issues}
            self.assertLessEqual({"BUNDLED_IDS", "ASSET_MISMATCH", "EMBEDDED"}, codes)

    def test_embedded_payload_drift_cannot_hide_behind_matching_assets(self):
        source = (lint.ROOT / lint.EMBEDDED).read_text()
        drifted = source.replace('"name":"Smooth blend"', '"name":"Drifted blend"', 1)
        self.assertNotEqual(source, drifted)
        with tempfile.TemporaryDirectory(prefix="recipe-lint-") as directory:
            embedded = Path(directory) / "BundledRecipesJson.kt"
            embedded.write_text(drifted)
            with patch.object(lint, "EMBEDDED", embedded):
                _, issues = lint.lint_repository()
            self.assertTrue(any(i.code == "EMBEDDED_MISMATCH" and i.recipe_id == "S1" for i in issues))

    def test_main_default_and_strict_fixture_have_distinct_exit_status(self):
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(0, lint.main([]))
            self.assertEqual(0, lint.main(["--check-fallbacks"]))
            with tempfile.TemporaryDirectory(prefix="recipe-lint-") as directory:
                recipe = self.recipe("F1")
                next(s for s in recipe["steps"] if s.get("fx") == "IMPACT").pop("requiredCap", None)
                path = Path(directory) / "F1.json"
                path.write_text(json.dumps(recipe))
                self.assertEqual(0, lint.main(["--no-coverage", str(path)]))
                self.assertEqual(1, lint.main(["--no-coverage", "--check-fallbacks", str(path)]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
