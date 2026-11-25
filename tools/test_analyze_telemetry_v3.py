#!/usr/bin/env python3
"""Unit tests for tools/analyze_telemetry_v3.py (AE3 Part A §10, B7.3).

Standard library only — mirrors tools/recipes/test_lint_recipes.py: run with
`python3 tools/test_analyze_telemetry_v3.py` (or `python3 -m unittest discover
tools`). Every one of the 9 §10 metric rows is pinned: overlap %, countdown
broken, next-known->READY p50/p95, analysis ms CPU/NPU, stem ms, frozen %,
exit mid-vocal, moved/last-resort counts, and Festival distinct recipes/FX.
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import analyze_telemetry_v3 as an  # noqa: E402

EPOCH = 1_795_500_000_000  # any plausible epoch-ms base


def v3_raw(kind: str, ts: int, **kw) -> dict:
    o = {"schema": 3, "engine": "3.0.0", "timestampMs": ts, "eventType": kind, "state": "PREPARING"}
    o.update(kw)
    return o


def lg_raw(ev: str, ts: int, **kw) -> dict:
    o = {"ts": ts, "ev": ev}
    o.update(kw)
    return o


def v3_recs(*raws) -> list:
    return [an.normalise_v3(o) for o in raws]


def lg_recs(*raws) -> list:
    return [an.normalise_legacy(o) for o in raws]


class WireKindTest(unittest.TestCase):
    def test_enum_names_map_to_dotted_wire_names(self):
        self.assertEqual("mix.fire", an.wire_kind("MIX_FIRE"))
        self.assertEqual("mix.prep.started", an.wire_kind("MIX_PREP_STARTED"))
        self.assertEqual("mix.countdown.broken", an.wire_kind("MIX_COUNTDOWN_BROKEN"))

    def test_last_resort_keeps_its_underscore(self):
        # TelemetryEventType.wireName is "mix.last_resort" — a mechanical
        # underscore-to-dot mapping would silently drop the §10 last-resort row.
        self.assertEqual("mix.last_resort", an.wire_kind("MIX_LAST_RESORT"))

    def test_dotted_names_pass_through(self):
        self.assertEqual("mix.timing", an.wire_kind("mix.timing"))
        self.assertEqual("mix.fire", an.wire_kind("mix.fire"))
        self.assertEqual("mix.last_resort", an.wire_kind("mix.last_resort"))


class NormaliseTest(unittest.TestCase):
    def test_v3_fields_map(self):
        r = an.normalise_v3(v3_raw("MIX_FIRE", EPOCH, planId="p1", generation=7, state="RUNNING",
                                   outId="o1", inId="i1", djLevel="FESTIVAL",
                                   path="PREPARER_FROZEN", recipeId="F1",
                                   analysisMs=120, stemMsOut=800, stemMsIn=900))
        self.assertEqual("v3", r.schema)
        self.assertEqual("mix.fire", r.kind)
        self.assertEqual(EPOCH, r.ts)
        self.assertEqual("p1", r.plan_id)
        self.assertEqual(7, r.generation)
        self.assertEqual("FESTIVAL", r.level)
        self.assertEqual("PREPARER_FROZEN", r.path)
        self.assertEqual("F1", r.recipe)
        self.assertEqual(120.0, r.analysis_ms)
        self.assertEqual(800.0, r.stem_out_ms)

    def test_v3_fx_from_string_or_list(self):
        self.assertEqual(["Echo", "Gate"], an.normalise_v3(v3_raw("MIX_FIRE", 0, fx="Echo,Gate")).fx)
        self.assertEqual(["Echo", "Gate"], an.normalise_v3(v3_raw("MIX_FIRE", 0, fxTypes=["Echo", "Gate"])).fx)
        self.assertEqual(["A", "B"], an.normalise_v3(v3_raw("MIX_FIRE", 0, fx="A;B")).fx)

    def test_v3_countdown_broken_from_flag_or_event_type(self):
        self.assertTrue(an.normalise_v3(v3_raw("MIX_COUNTDOWN_BROKEN", 0)).countdown_broken)
        self.assertTrue(an.normalise_v3(v3_raw("MIX_FIRE", 0, countdownBroken=True)).countdown_broken)
        self.assertFalse(an.normalise_v3(v3_raw("MIX_FIRE", 0, countdownBroken=False)).countdown_broken)

    def test_v3_exit_vocal_aliases(self):
        self.assertEqual(0.7, an.normalise_v3(v3_raw("MIX_FIRE", 0, exitVocal=0.7)).exit_vocal)
        self.assertEqual(0.7, an.normalise_v3(v3_raw("MIX_FIRE", 0, syvenuAtExit=0.7)).exit_vocal)
        self.assertIsNone(an.normalise_v3(v3_raw("MIX_FIRE", 0)).exit_vocal)

    def test_v3_stress_from_detail(self):
        self.assertTrue(an.normalise_v3(v3_raw("MIX_FIRE", 0, detail="post-stress window")).stress)

    def test_legacy_fields_map(self):
        r = an.normalise_legacy(lg_raw("mix.fire", EPOCH, outId="o1", inId="i1", level="festival",
                                       path="v2-native", choreo="F1:Riser drop", fx="Echo,Gate",
                                       analysisMs=250, fallbackReason="no-stems"))
        self.assertEqual("legacy", r.schema)
        self.assertEqual("mix.fire", r.kind)
        self.assertEqual("FESTIVAL", r.level)
        self.assertEqual("v2-native", r.path)
        self.assertEqual("F1:Riser drop", r.recipe)
        self.assertEqual(["Echo", "Gate"], r.fx)
        self.assertEqual("no-stems", r.reason)

    def test_legacy_countdown_broken_from_event_name_or_flag(self):
        self.assertTrue(an.normalise_legacy(lg_raw("mix.countdown.broken", 0)).countdown_broken)
        self.assertTrue(an.normalise_legacy(lg_raw("mix.timing", 0, countdownBroken=True)).countdown_broken)
        self.assertFalse(an.normalise_legacy(lg_raw("mix.timing", 0)).countdown_broken)

    def test_num_rejects_non_numbers_and_nan(self):
        self.assertIsNone(an.num("120"))
        self.assertIsNone(an.num(True))
        self.assertIsNone(an.num(float("nan")))
        self.assertEqual(120.0, an.num(120))


class LoadJsonlTest(unittest.TestCase):
    def test_dispatch_and_bad_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mixed.jsonl"
            lines = [
                json.dumps(v3_raw("MIX_FIRE", EPOCH)),
                json.dumps(lg_raw("mix.timing", EPOCH)),
                json.dumps({"ts": 1}),                # neither ev nor eventType -> bad
                "not json at all",
                json.dumps([1, 2]),                   # not an object
                "",                                    # skipped silently
            ]
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            recs, bad = an.load_jsonl(path)
        self.assertEqual(2, len(recs))
        self.assertEqual(3, bad)
        self.assertEqual({"v3": 1, "legacy": 1}, dict(__import__("collections").Counter(r.schema for r in recs)))

    def test_records_sorted_by_ts(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "unsorted.jsonl"
            path.write_text(
                json.dumps(v3_raw("MIX_FIRE", EPOCH + 5000)) + "\n" +
                json.dumps(v3_raw("MIX_PREP_STARTED", EPOCH)) + "\n",
                encoding="utf-8",
            )
            recs, _ = an.load_jsonl(path)
        self.assertEqual([EPOCH, EPOCH + 5000], [r.ts for r in recs])


class PercentileTest(unittest.TestCase):
    def test_linear_interpolation(self):
        vals = [float(i) for i in range(1, 101)]
        self.assertAlmostEqual(50.5, an.pct(vals, 0.5))
        self.assertAlmostEqual(95.05, an.pct(vals, 0.95))
        self.assertAlmostEqual(1.0, an.pct(vals, 0.0))
        self.assertAlmostEqual(100.0, an.pct(vals, 1.0))

    def test_empty_is_none(self):
        self.assertIsNone(an.pct([], 0.5))


class TransitionsTest(unittest.TestCase):
    def test_v3_prep_started_preferred_over_legacy_timing(self):
        recs = v3_recs(v3_raw("MIX_PREP_STARTED", EPOCH, outId="a", inId="b")) + \
            lg_recs(lg_raw("mix.timing", EPOCH + 1000, outId="a", inId="b"))
        self.assertEqual(1, len(an.transitions(recs)))

    def test_legacy_only_falls_back_to_timing(self):
        recs = lg_recs(lg_raw("mix.timing", EPOCH, outId="a", inId="b"),
                       lg_raw("mix.timing", EPOCH + 10, outId="b", inId="c"))
        self.assertEqual(2, len(an.transitions(recs)))

    def test_merged_stream_dedupes_same_pair_attempts(self):
        # v3 prep at t0, legacy timing at t0+90s (fire time): same transition.
        recs = v3_recs(v3_raw("MIX_PREP_STARTED", EPOCH, outId="a", inId="b")) + \
            lg_recs(lg_raw("mix.timing", EPOCH + 90_000, outId="a", inId="b"))
        self.assertEqual(1, len(an.transitions(recs)))

    def test_merged_stream_keeps_legacy_only_attempts(self):
        recs = v3_recs(v3_raw("MIX_PREP_STARTED", EPOCH, outId="a", inId="b")) + \
            lg_recs(lg_raw("mix.timing", EPOCH + 1000, outId="x", inId="y"))
        self.assertEqual(2, len(an.transitions(recs)))


class FiresTest(unittest.TestCase):
    def test_merged_stream_dedupes_and_enriches(self):
        recs = v3_recs(v3_raw("MIX_FIRE", EPOCH, outId="a", inId="b", djLevel="FESTIVAL")) + \
            lg_recs(lg_raw("mix.fire", EPOCH + 2000, outId="a", inId="b", level="FESTIVAL",
                           fx="Echo,Gate", exitVocal=0.2))
        fired = an.fires(recs)
        self.assertEqual(1, len(fired))
        self.assertEqual(["Echo", "Gate"], fired[0].fx)
        self.assertEqual(0.2, fired[0].exit_vocal)

    def test_merged_stream_keeps_unmatched_legacy_fires(self):
        recs = v3_recs(v3_raw("MIX_FIRE", EPOCH, outId="a", inId="b")) + \
            lg_recs(lg_raw("mix.fire", EPOCH + 1000, outId="x", inId="y"))
        self.assertEqual(2, len(an.fires(recs)))

    def test_merged_stream_far_apart_same_pair_are_distinct(self):
        # Same pair fired twice, an hour apart: two transitions.
        recs = v3_recs(v3_raw("MIX_FIRE", EPOCH, outId="a", inId="b")) + \
            lg_recs(lg_raw("mix.fire", EPOCH + 3_600_000, outId="a", inId="b"))
        self.assertEqual(2, len(an.fires(recs)))

    def test_single_dialect_passthrough(self):
        recs = lg_recs(lg_raw("mix.fire", EPOCH, outId="a", inId="b"),
                       lg_raw("mix.fire", EPOCH + 60_000, outId="b", inId="c"))
        self.assertEqual(2, len(an.fires(recs)))


class CrossDialectCountTest(unittest.TestCase):
    def test_merged_incident_counted_once(self):
        recs = v3_recs(v3_raw("MIX_MOVED", EPOCH, outId="a", inId="b")) + \
            lg_recs(lg_raw("mix.moved", EPOCH + 100, outId="a", inId="b"))
        self.assertEqual(1, an.cross_dialect_count(recs))

    def test_distinct_incidents_counted_separately(self):
        recs = v3_recs(v3_raw("MIX_MOVED", EPOCH)) + \
            lg_recs(lg_raw("mix.moved", EPOCH + 60_000))
        self.assertEqual(2, an.cross_dialect_count(recs))

    def test_single_dialect_count(self):
        recs = lg_recs(lg_raw("mix.moved", EPOCH), lg_raw("mix.moved", EPOCH + 100))
        self.assertEqual(2, an.cross_dialect_count(recs))


class ReadyLatencyTest(unittest.TestCase):
    def test_prep_to_ready_by_plan(self):
        recs = v3_recs(
            v3_raw("MIX_PREP_STARTED", EPOCH, planId="p1"),
            v3_raw("MIX_READY", EPOCH + 4000, planId="p1"),
            v3_raw("MIX_PREP_STARTED", EPOCH + 10_000, planId="p2"),
            v3_raw("MIX_READY", EPOCH + 10_000 + 9000, planId="p2"),
        )
        self.assertEqual([4.0, 9.0], an.ready_latencies(recs))

    def test_first_start_wins_for_repeated_plan(self):
        recs = v3_recs(
            v3_raw("MIX_PREP_STARTED", EPOCH, planId="p1"),
            v3_raw("MIX_PREP_STARTED", EPOCH + 1000, planId="p1"),
            v3_raw("MIX_READY", EPOCH + 3000, planId="p1"),
        )
        self.assertEqual([3.0], an.ready_latencies(recs))

    def test_ready_without_start_ignored(self):
        recs = v3_recs(v3_raw("MIX_READY", EPOCH, planId="orphan"))
        self.assertEqual([], an.ready_latencies(recs))


class RecipeCatalogTest(unittest.TestCase):
    def test_load_recipe_fx_mirrors_direct_fx(self):
        with tempfile.TemporaryDirectory() as tmp:
            catalog = Path(tmp)
            (catalog / "F1.json").write_text(json.dumps({
                "id": "F1",
                "steps": [
                    {"type": "configure_fx", "fx": "RISER"},
                    {"type": "lane_curve", "kind": "HIGH_PASS_HZ"},
                    {"type": "lane_curve", "kind": "GAIN"},
                ],
                "noStemSteps": [{"type": "configure_fx", "fx": "ECHO"}],
            }), encoding="utf-8")
            (catalog / "broken.json").write_text("{not json", encoding="utf-8")
            (catalog / "not_a_recipe.json").write_text(json.dumps([1, 2]), encoding="utf-8")
            fx = an.load_recipe_fx(catalog)
        self.assertEqual({"F1"}, set(fx))
        self.assertEqual({"RISER", "ECHO", "SWEEP"}, fx["F1"])

    def test_missing_dir_gives_empty_catalog(self):
        self.assertEqual({}, an.load_recipe_fx(Path("/nonexistent-recipes")))

    def test_default_catalog_points_at_bundled_assets(self):
        default = an.default_recipe_catalog()
        self.assertTrue(default.is_dir(), f"bundled recipe catalog missing: {default}")
        fx = an.load_recipe_fx(default)
        self.assertIn("F1", fx)
        self.assertTrue(fx["F1"], "F1 must declare at least one FX type")


def festival_session(n_fires: int = 12, broken: int = 0, unfired: int = 0) -> list:
    """A clean synthetic v3 Festival session: n fires + matching preps."""
    raws = []
    recipes = ["F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8"]
    for i in range(n_fires + unfired):
        t = EPOCH + i * 120_000
        pid = f"plan-{i}"
        raws.append(v3_raw("MIX_PREP_STARTED", t, planId=pid, outId=f"o{i}", inId=f"o{i+1}"))
        raws.append(v3_raw("MIX_READY", t + 3000, planId=pid))
        if i < n_fires:
            raws.append(v3_raw("MIX_FIRE", t + 5000, planId=pid, state="RUNNING",
                               outId=f"o{i}", inId=f"o{i+1}", djLevel="FESTIVAL",
                               path="PREPARER_FROZEN", recipeId=recipes[i % len(recipes)]))
            raws.append(v3_raw("MIX_DONE", t + 30_000, planId=pid, state="DONE"))
    for i in range(broken):
        raws.append(v3_raw("MIX_COUNTDOWN_BROKEN", EPOCH + 1_000_000 + i, state="ARMED"))
    return v3_recs(*raws)


class SummariseSection10Test(unittest.TestCase):
    """Every §10 row, computed on a controlled synthetic session."""

    def setUp(self):
        self.catalog = {"F1": {"RISER", "GATE"}, "F2": {"ECHO"}, "F3": {"FLANGER"},
                        "F4": {"BRAKE"}, "F5": {"SPINBACK"}, "F6": {"LOOP"},
                        "F7": {"SWEEP"}, "F8": {"PUMP"}}

    def summarise(self, recs, recipe_fx=None):
        return an.summarise(recs, 0, "synthetic", recipe_fx=recipe_fx)

    # 1. Transitions with an audible overlap
    def test_overlap_100_when_every_attempt_fires(self):
        s = self.summarise(festival_session(n_fires=12))
        self.assertEqual(12, s["transitions"])
        self.assertEqual(12, s["fires"])
        self.assertEqual(100.0, s["overlap_pct"])

    def test_overlap_counts_unfired_attempts(self):
        s = self.summarise(festival_session(n_fires=11, unfired=1))
        self.assertEqual(12, s["transitions"])
        self.assertEqual(11, s["fires"])
        self.assertAlmostEqual(100.0 * 11 / 12, s["overlap_pct"])

    # 2. mix.countdown.broken
    def test_countdown_broken_counted(self):
        s = self.summarise(festival_session(n_fires=2, broken=3))
        self.assertEqual(3, s["countdown_broken"])

    def test_countdown_broken_zero_on_clean_session(self):
        self.assertEqual(0, self.summarise(festival_session())["countdown_broken"])

    # 3. Next-known -> READY p50 / p95
    def test_ready_percentiles(self):
        s = self.summarise(festival_session(n_fires=12))
        self.assertEqual(12, s["ready_n"])
        self.assertAlmostEqual(3.0, s["ready_p50_s"])
        self.assertAlmostEqual(3.0, s["ready_p95_s"])

    # 4. Analysis ms CPU / NPU
    def test_analysis_ms_split_by_backend_hint(self):
        raws = [
            v3_raw("MIX_PREP_STARTED", EPOCH, planId="p1", analysisMs=1000, detail="cpu"),
            v3_raw("MIX_PREP_STARTED", EPOCH + 1000, planId="p2", analysisMs=400, detail="QNN npu session"),
            v3_raw("MIX_PREP_STARTED", EPOCH + 2000, planId="p3", analysisMs=600, detail="cpu"),
        ]
        s = self.summarise(v3_recs(*raws))
        self.assertEqual(2, s["analysis_cpu_n"])
        self.assertEqual(1, s["analysis_npu_n"])
        self.assertAlmostEqual(800.0, s["analysis_cpu_p50_ms"])
        self.assertAlmostEqual(400.0, s["analysis_npu_p50_ms"])

    # 5. Stem ms
    def test_stem_ms_from_both_windows(self):
        raws = [
            v3_raw("MIX_TIMING", EPOCH, stemMsOut=1000, stemMsIn=3000),
            v3_raw("MIX_TIMING", EPOCH + 1000, stemMsOut=2000, stemMsIn=4000),
        ]
        s = self.summarise(v3_recs(*raws))
        self.assertEqual(4, s["stem_windows"])
        self.assertAlmostEqual(2500.0, s["stem_p50_ms"])
        # Linear interpolation: pos = 3 * 0.95 = 2.85 between 3000 and 4000.
        self.assertAlmostEqual(3850.0, s["stem_p95_ms"])

    # 6. Path = preparer-frozen %
    def test_frozen_pct(self):
        recs = v3_recs(
            v3_raw("MIX_FIRE", EPOCH, outId="a", inId="b", djLevel="CLUB", path="PREPARER_FROZEN"),
            v3_raw("MIX_FIRE", EPOCH + 1000, outId="b", inId="c", djLevel="CLUB", path="PREPARER_NO_STEM_FALLBACK"),
        )
        self.assertEqual(100.0, self.summarise(recs)["frozen_pct"])
        mixed = recs + v3_recs(
            v3_raw("MIX_FIRE", EPOCH + 2000, outId="c", inId="d", djLevel="CLUB", path="LAST_RESORT_MUSICAL_EXIT"),
        )
        self.assertAlmostEqual(100.0 * 2 / 3, self.summarise(mixed)["frozen_pct"])

    def test_frozen_pct_legacy_paths_count(self):
        recs = lg_recs(lg_raw("mix.fire", EPOCH, outId="a", inId="b", path="v2-native"))
        self.assertEqual(100.0, self.summarise(recs)["frozen_pct"])

    # 7. Exit mid-vocal
    def test_exit_mid_vocal_counts_syvenu_over_half(self):
        recs = v3_recs(
            v3_raw("MIX_FIRE", EPOCH, outId="a", inId="b", exitVocal=0.9),
            v3_raw("MIX_FIRE", EPOCH + 1000, outId="b", inId="c", exitVocal=0.2),
        )
        self.assertEqual(1, self.summarise(recs)["exit_mid_vocal"])

    def test_exit_mid_vocal_absent_is_not_measured(self):
        s = self.summarise(festival_session())
        self.assertEqual(0, s["exit_vocal_samples"])
        self.assertIsNone(s["exit_mid_vocal"])

    # 8. mix.moved / last-resort counts
    def test_moved_and_last_resort_counts(self):
        recs = festival_session(n_fires=2) + v3_recs(
            v3_raw("MIX_MOVED", EPOCH + 999_000),
            v3_raw("MIX_MOVED", EPOCH + 999_100),
            v3_raw("MIX_LAST_RESORT", EPOCH + 999_200),
        )
        s = self.summarise(recs)
        self.assertEqual(2, s["mix_moved"])
        self.assertEqual(1, s["last_resort"])

    # 9. Festival distinct recipes / FX over 12
    def test_festival_first12_recipes_and_fx(self):
        s = self.summarise(festival_session(n_fires=12), recipe_fx=self.catalog)
        self.assertEqual(8, s["festival_recipes_first12"])
        # 8 recipes; F1 contributes two FX types, so the union is 9.
        self.assertEqual(9, s["festival_fx_first12"])
        self.assertGreater(s["fx_derived_from_recipes"], 0)

    def test_festival_fx_unmeasurable_without_catalog_or_fx_field(self):
        s = self.summarise(festival_session(n_fires=12))
        self.assertEqual(8, s["festival_recipes_first12"])
        self.assertEqual(0, s["festival_fx_first12"])

    def test_festival_window_is_first_12_fires(self):
        # 15 fires cycling 8 recipes: the first 12 cover all 8.
        s = self.summarise(festival_session(n_fires=15), recipe_fx=self.catalog)
        self.assertEqual(8, s["festival_recipes_first12"])
        self.assertEqual(9, s["festival_fx_first12"])
        self.assertEqual(8, s["festival_recipes_all"])

    def test_recipe_id_with_display_name_suffix_resolves(self):
        recs = lg_recs(lg_raw("mix.fire", EPOCH, outId="a", inId="b", level="FESTIVAL",
                              choreo="F1:Riser drop"))
        s = self.summarise(recs, recipe_fx=self.catalog)
        self.assertEqual({"RISER", "GATE"}, set(recs[0].fx))

    # Merged-dialect §10 correctness
    def test_merged_session_counts_transitions_once(self):
        v3s = festival_session(n_fires=3)
        # The legacy stream describes the same 3 transitions: timing lands at
        # fire time (within the 5-minute attempt window), fire within seconds
        # of the v3 fire (within the 15-second fire window).
        legacy = lg_recs(
            lg_raw("mix.timing", EPOCH + 90_000, outId="o0", inId="o1", level="FESTIVAL",
                   path="v2-native", choreo="F1:Riser drop", analysisMs=900),
            lg_raw("mix.fire", EPOCH + 5_500, outId="o0", inId="o1", level="FESTIVAL",
                   path="v2-native", choreo="F1:Riser drop", fx="Echo,Gate"),
            lg_raw("mix.timing", EPOCH + 210_000, outId="o1", inId="o2", level="FESTIVAL",
                   path="v2-native", analysisMs=800),
            lg_raw("mix.fire", EPOCH + 125_500, outId="o1", inId="o2", level="FESTIVAL",
                   path="v2-native"),
            lg_raw("mix.timing", EPOCH + 330_000, outId="o2", inId="o3", level="FESTIVAL",
                   path="v2-native", analysisMs=700),
            lg_raw("mix.fire", EPOCH + 245_500, outId="o2", inId="o3", level="FESTIVAL",
                   path="v2-native"),
        )
        s = self.summarise(v3s + legacy)
        self.assertEqual(3, s["transitions"])
        self.assertEqual(3, s["fires"])
        self.assertEqual(100.0, s["overlap_pct"])
        # v3 fires absorbed legacy fx where the legacy record had one; only
        # the two fx-less fires fall back to the (absent) recipe catalog.
        self.assertEqual(0, s["fx_derived_from_recipes"])

    def test_levels_and_natural_stress_blocks(self):
        recs = festival_session(n_fires=3) + v3_recs(
            v3_raw("MIX_FIRE", EPOCH, outId="s0", inId="s1", djLevel="CLUB",
                   path="PREPARER_FROZEN", detail="stress run"),
        )
        s = self.summarise(recs)
        self.assertEqual(3, s["levels"]["FESTIVAL"]["fires"])
        self.assertEqual(1, s["levels"]["CLUB"]["fires"])
        self.assertEqual(1, s["stress"]["fires"])
        self.assertEqual(3, s["natural"]["fires"])


class GatesTest(unittest.TestCase):
    def test_all_ten_gate_rows_exist(self):
        self.assertEqual(10, len(an.GATES))
        for key in ("overlap_pct", "countdown_broken", "ready_p50_s", "ready_p95_s",
                    "analysis_cpu_p95_ms", "stem_p95_ms", "frozen_pct", "exit_mid_vocal",
                    "festival_recipes", "festival_fx"):
            self.assertIn(key, an.GATES)

    def test_gate_status_pass_fail_not_measured(self):
        self.assertEqual("PASS", an.gate_status("overlap_pct", 100.0))
        self.assertEqual("FAIL", an.gate_status("overlap_pct", 99.0))
        self.assertEqual("not measured", an.gate_status("overlap_pct", None))
        self.assertEqual("PASS", an.gate_status("countdown_broken", 0))
        self.assertEqual("FAIL", an.gate_status("countdown_broken", 2))
        self.assertEqual("PASS", an.gate_status("ready_p50_s", 6))
        self.assertEqual("FAIL", an.gate_status("ready_p50_s", 6.1))
        self.assertEqual("PASS", an.gate_status("ready_p95_s", 15))
        self.assertEqual("PASS", an.gate_status("analysis_cpu_p95_ms", 1500))
        self.assertEqual("FAIL", an.gate_status("analysis_cpu_p95_ms", 1501))
        self.assertEqual("PASS", an.gate_status("stem_p95_ms", 8000))
        self.assertEqual("PASS", an.gate_status("frozen_pct", 100.0))
        self.assertEqual("PASS", an.gate_status("exit_mid_vocal", 0))
        self.assertEqual("PASS", an.gate_status("festival_recipes", 7))
        self.assertEqual("FAIL", an.gate_status("festival_recipes", 6))
        self.assertEqual("PASS", an.gate_status("festival_fx", 7))

    def test_gate_value_pulls_every_row_from_summary(self):
        s = {"overlap_pct": 1, "countdown_broken": 2, "ready_p50_s": 3, "ready_p95_s": 4,
             "analysis_cpu_p95_ms": 5, "stem_p95_ms": 6, "frozen_pct": 7, "exit_mid_vocal": 8,
             "festival_recipes_first12": 9, "festival_fx_first12": 10}
        for key in an.GATES:
            self.assertIsNotNone(an.gate_value(key, s), key)


class LoadBeforeTest(unittest.TestCase):
    def test_metrics_json_mapping(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "m0-metrics.json"
            path.write_text(json.dumps({
                "transitions_mix_timing": 23, "mix_fire": 19,
                "audible_overlap_pct_of_fires_PROXY": 100.0,
                "countdown_broken": "n/a (no events)",
                "analysis_ms_per_track_telemetry": {"p50": 10.0, "p95": 2750.4},
                "stem_ms_per_window": {"out_p50": 120.0},
                "path_preparer_frozen_pct": 39.1,
                "mix_moved_count": 4, "last_resort_count": 1,
                "festival_distinct_recipes": 5, "festival_distinct_fx_types": 9,
            }), encoding="utf-8")
            b = an.load_before(path)
        self.assertEqual(23, b["transitions"])
        self.assertEqual(100.0, b["overlap_pct"])
        self.assertEqual(2750.4, b["analysis_cpu_p95_ms"])
        self.assertEqual(120.0, b["stem_p50_ms"])
        self.assertEqual(39.1, b["frozen_pct"])
        self.assertEqual(5, b["festival_recipes_first12"])

    def test_before_jsonl_reuses_summarise(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "before.jsonl"
            path.write_text(json.dumps(lg_raw("mix.timing", EPOCH, analysisMs=100)) + "\n",
                            encoding="utf-8")
            b = an.load_before(path)
        self.assertEqual(1, b["transitions"])


class MainCliTest(unittest.TestCase):
    def write_session(self, tmp: str) -> Path:
        path = Path(tmp) / "after.jsonl"
        raws = []
        recipes = ["F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8"]
        for i in range(12):
            t = EPOCH + i * 120_000
            raws.append(v3_raw("MIX_PREP_STARTED", t, planId=f"p{i}", outId=f"o{i}", inId=f"o{i+1}",
                               analysisMs=900, detail="cpu"))
            raws.append(v3_raw("MIX_READY", t + 3000, planId=f"p{i}"))
            raws.append(v3_raw("MIX_FIRE", t + 5000, planId=f"p{i}", state="RUNNING",
                               outId=f"o{i}", inId=f"o{i+1}", djLevel="FESTIVAL",
                               path="PREPARER_FROZEN", recipeId=recipes[i % 8],
                               stemMsOut=1000, stemMsIn=2000, exitVocal=0.1))
        # NPU-side analysis samples so the NPU-vs-CPU row is measured (and
        # passes: 300 ms < 900 ms CPU).
        for i in range(4):
            raws.append(v3_raw("MIX_TIMING", EPOCH + 50_000 + i * 1000,
                               analysisMs=300, detail="QNN session"))
        path.write_text("\n".join(json.dumps(r) for r in raws) + "\n", encoding="utf-8")
        return path

    def test_end_to_end_writes_md_and_json_with_all_section10_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            after = self.write_session(tmp)
            out = Path(tmp) / "results.md"
            rc = self.run_main(["--after", str(after), "--out", str(out)])
            self.assertEqual(0, rc)
            md = out.read_text(encoding="utf-8")
            for label in (
                "Transitions with an audible overlap",
                "mix.countdown.broken",
                "Next-known -> READY p50",
                "Next-known -> READY p95",
                "Analysis ms per track (CPU) p95",
                "Analysis ms per track p50, NPU vs CPU",
                "Stem ms per needed window p95",
                "Path = preparer-frozen",
                "Exit mid-vocal (Syvenu > 0.5 at exit)",
                "Festival distinct recipes over 12",
                "Festival distinct FX types over 12",
                "`mix.moved` count",
                "Last-resort count",
            ):
                self.assertIn(label, md, f"§10 row missing from results table: {label}")
            sidecar = json.loads(out.with_suffix(".md.json").read_text(encoding="utf-8"))
            self.assertIn("after", sidecar)
            self.assertEqual(12, sidecar["after"]["transitions"])
            # FX derived from the bundled catalog (default --recipes).
            self.assertGreaterEqual(sidecar["after"]["festival_fx_first12"], 1)

    def run_main(self, argv) -> int:
        """main() with stdout/stderr captured so test output stays readable."""
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            return an.main(argv)

    def test_strict_fails_when_gates_unmeasured(self):
        with tempfile.TemporaryDirectory() as tmp:
            after = Path(tmp) / "after.jsonl"
            after.write_text(json.dumps(lg_raw("mix.timing", EPOCH)) + "\n", encoding="utf-8")
            self.assertEqual(1, self.run_main(["--after", str(after), "--strict"]))

    def test_strict_passes_on_clean_measured_session(self):
        with tempfile.TemporaryDirectory() as tmp:
            after = self.write_session(tmp)
            self.assertEqual(0, self.run_main(["--after", str(after), "--strict"]))

    def test_missing_after_file_is_usage_error(self):
        self.assertEqual(2, self.run_main(["--after", "/nonexistent/telemetry.jsonl"]))

    def test_empty_after_file_is_usage_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            after = Path(tmp) / "empty.jsonl"
            after.write_text("\n\n", encoding="utf-8")
            self.assertEqual(2, self.run_main(["--after", str(after)]))

    def test_explicit_bad_recipes_dir_warns_but_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            after = self.write_session(tmp)
            rc = self.run_main(["--after", str(after), "--recipes", str(Path(tmp) / "no-such-dir")])
            self.assertEqual(0, rc)


if __name__ == "__main__":
    unittest.main()
