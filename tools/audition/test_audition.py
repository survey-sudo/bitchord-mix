#!/usr/bin/env python3
"""Audition catalog/export smoke tests; no JVM, native build or real audio needed."""

from __future__ import annotations

from copy import deepcopy
from contextlib import redirect_stdout
import io
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import audition_render as audition  # noqa: E402
import generate_synthetic_pairs as gen  # noqa: E402
import recipe_catalog as catalog  # noqa: E402


def _option_values(command):
    values = {}
    index = 1
    while index < len(command):
        token = command[index]
        if token.startswith("--") and index + 1 < len(command) and not command[index + 1].startswith("--"):
            values[token[2:]] = command[index + 1]
            index += 2
        else:
            index += 1
    return values


class AuditionTest(unittest.TestCase):
    def setUp(self):
        audition.COMMAND_RUNNER = subprocess.run
        self.directory = tempfile.TemporaryDirectory(prefix="audition-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for deck in ("out", "in"):
            (self.root / f"{deck}.f32").write_bytes(bytes(4 * gen.SR * 2 * 4))
        self.pair = {
            "pairId": "pair1", "synthetic": True, "bpmOut": 120.0, "bpmIn": 120.0,
            "outDurationSec": 4.0, "inDurationSec": 4.0, "outTrackId": "out", "inTrackId": "in",
            "outF32": str(self.root / "out.f32"), "inF32": str(self.root / "in.f32"),
            "exitSec": 2.0, "entrySec": 0.0, "fadeSec": 2.0, "recipes": list(catalog.RECIPE_IDS),
        }

    def canonical_pair(self, rid="F1"):
        # Fabricated contract fixtures exercise the Python handoff, not JVM parity.
        script = gen.build_script(self.pair, rid)
        script["assetHashes"] = {str(self.root / f"{deck}.f32"): hashlib.sha256((self.root / f"{deck}.f32").read_bytes()).hexdigest()
                                 for deck in ("out", "in")}
        plan = {"schema": 1, "planId": script["planId"], "generation": script["generation"],
                "recipeId": rid, "djLevel": catalog.CATALOG[rid]["level"],
                "outTrackId": "out", "inTrackId": "in", "frozenExitMs": 2000,
                "frozenEntryMs": 0, "overlapDurationMs": 12_000, "barDurationMs": 2_000,
                "requiredStems": deepcopy(catalog.CATALOG[rid]["requirements"]["requiredStems"]),
                "placementReasons": ["contract test fixture"]}
        pair = dict(self.pair, synthetic=False, recipes=[rid], exports={rid: {"plan": plan, "script": script}})
        return pair

    def playback_export(self, rid="S1", requested=None):
        record = self.canonical_pair(rid)["exports"][rid]
        result = {}
        for kind, value in record.items():
            path = self.root / f"playback-{rid}.{kind}.json"
            path.write_text(json.dumps(value))
            result[kind] = str(path)
        return {requested or rid: result}

    def test_all_26_names_descriptions_and_defaults_match_recipev2_json(self):
        self.assertEqual(26, len(gen.RECIPES))
        self.assertEqual(list(catalog.RECIPE_IDS), gen.DEFAULT_RECIPES)
        self.assertLessEqual({"C8", "C9", "F9", "F10", "F11", "S6"}, gen.RECIPES.keys())
        for rid, recipe in gen.RECIPES.items():
            source = json.loads((catalog.RECIPE_DIR / f"{rid}.json").read_text())
            self.assertEqual(source["name"], recipe["name"])
            self.assertEqual(source["description"], recipe["desc"])
            self.assertEqual(source["level"], recipe["level"])

    def test_all_26_synthetic_fixtures_include_authored_lanes_and_fx(self):
        aliases = {name: value[0] for name, value in __import__("synthetic_script").FX.items()}
        aliases.update(LOOP_ROLL="roll")
        for rid, recipe in catalog.CATALOG.items():
            with self.subTest(recipe=rid):
                script = gen.build_script(self.pair, rid)
                self.assertIn(f"-{rid}", script["planId"])
                self.assertIn("renderGeometry", script)
                self.assertNotIn("audition", script)
                self.assertNotIn("inKnots", script)
                self.assertEqual([], script["knots"])
                self.assertEqual([], script["stems"])
                self.assertTrue(all(not lane["kind"].startswith("STEM_") for lane in script["envelopes"]))
                for step in recipe["noStemSteps"]:
                    if step["type"] == "configure_fx" and step["fx"] != "SVF_Q":
                        self.assertTrue(script["fxParams"][aliases[step["fx"]]]["enabled"])
                json.dumps(script, allow_nan=False)

    def test_f1_fallback_keeps_roll_echo_gate_without_impact(self):
        script = gen.build_script(dict(self.pair, missingCapabilities=["RISER"]), "F1")
        self.assertLessEqual({"flanger", "gate", "echo", "roll"}, script["fxParams"].keys())
        self.assertNotIn("impact", script["fxParams"])
        self.assertNotIn("riser", script["fxParams"])

    def test_f5_flanger_only_appears_when_phaser_is_unavailable(self):
        full = gen.build_script(self.pair, "F5")
        fallback = gen.build_script(dict(self.pair, missingCapabilities=["PHASER"]), "F5")
        self.assertIn("phaser", full["fxParams"])
        self.assertNotIn("flanger", full["fxParams"])
        self.assertIn("flanger", fallback["fxParams"])
        self.assertNotIn("phaser", fallback["fxParams"])

    def test_all_auto_levels_resolve_real_ids_and_reject_higher_tier(self):
        for level, rid in gen.AUTO_PICKS.items():
            script = gen.build_script(self.pair, f"AUTO-{level}")
            self.assertEqual(f"synthetic-pair1-{rid}", script["planId"])
        with self.assertRaisesRegex(ValueError, "requires this pair"):
            catalog.resolve_recipe("AUTO-CLUB")
        with self.assertRaisesRegex(ValueError, "higher-tier"):
            catalog.resolve_recipe("AUTO-SUBTLE", {"SUBTLE": "F1"})

    def test_unknown_empty_and_real_audio_python_compilation_fail(self):
        for selection in ("F12", "S0", "NOPE", "", "S1,", []):
            with self.subTest(selection=selection), self.assertRaises(ValueError):
                catalog.requested_recipes(selection)
        with self.assertRaises(ValueError):
            gen.build_script(self.pair, "F12")
        with self.assertRaisesRegex(ValueError, "synthetic=true"):
            gen.build_script(dict(self.pair, synthetic=False), "F1")
        self.assertEqual(list(catalog.RECIPE_IDS) + list(catalog.AUTO_IDS), catalog.requested_recipes("ALL,AUTO,S1"))

    def test_all_26_canonical_exports_preserve_exact_script_values(self):
        for rid in catalog.RECIPE_IDS:
            with self.subTest(recipe=rid):
                pair = self.canonical_pair(rid)
                expected = deepcopy(pair["exports"][rid]["script"])
                script, plan, recipe = catalog.exported_script(pair, rid, self.root)
                self.assertEqual(expected, script)
                self.assertEqual(rid, plan["recipeId"])
                self.assertEqual(rid, recipe["id"])
                self.assertEqual(expected, pair["exports"][rid]["script"])

    def test_canonical_auto_uses_exported_planner_pick_not_fixture_control(self):
        pair = self.canonical_pair("C9")
        pair["autoPicks"] = {"CLUB": "C9"}
        script, plan, _ = catalog.exported_script(pair, "AUTO-CLUB", self.root)
        self.assertEqual("C9", plan["recipeId"])
        self.assertTrue(script["planId"].endswith("-C9"))

    def test_canonical_cli_consumes_all_26_exports_without_recompilation(self):
        pair = dict(self.pair, synthetic=False, exports={})
        for rid in catalog.RECIPE_IDS:
            record = self.canonical_pair(rid)["exports"][rid]
            for key in ("plan", "script"):
                path = self.root / f"{rid}.{key}.json"
                path.write_text(json.dumps(record[key]))
                record[key] = path.name
            pair["exports"][rid] = record
        pair["autoPicks"] = {"SUBTLE": "S6", "CLUB": "C9", "FESTIVAL": "F10"}
        path = self.root / "canonical-pairs.json"
        path.write_text(json.dumps({"pairs": [pair]}))
        command = [sys.executable, audition.__file__, "--pairs", str(path), "--out", str(self.root / "canonical"),
                   "--recipes", "ALL,AUTO", "--scripts-only"]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("exported 29 scripts", result.stdout)
        summary = json.loads((self.root / "canonical/scripts.json").read_text())
        for record in summary["scripts"]:
            self.assertEqual("canonical-jvm-export", record["provenance"])
            actual = json.loads((self.root / "canonical" / record["script"]).read_text())
            expected = json.loads((self.root / f"{record['recipeId']}.script.json").read_text())
            self.assertEqual(expected, actual)

    def test_canonical_paths_refuse_stale_identity_geometry_and_short_pcm(self):
        for mutation, message in (("recipe", "exported plan"), ("generation", "generation"),
                                  ("geometry", "renderGeometry"), ("overlay", "overlays"),
                                  ("pcm", "frame count"), ("knots", "increasing"), ("nan", "JSON compliant")):
            pair = self.canonical_pair()
            record = pair["exports"]["F1"]
            if mutation == "recipe": record["plan"]["recipeId"] = "S1"
            elif mutation == "generation": record["script"]["generation"] += 1
            elif mutation == "geometry": del record["script"]["renderGeometry"]
            elif mutation == "overlay": record["script"]["inKnots"] = []
            elif mutation == "pcm": record["script"]["renderGeometry"]["out"]["frameCount"] += 1
            elif mutation == "knots": record["script"]["renderGeometry"]["out"]["knotFrames"] = [1, 1]
            elif mutation == "nan": record["script"]["loudness"]["targetLufs"] = float("nan")
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, message):
                catalog.exported_script(pair, "F1", self.root)
        with self.assertRaisesRegex(ValueError, "export missing"):
            catalog.exported_script({}, "F1", self.root)

    def test_short_stems_are_refused_without_padding(self):
        pair = self.canonical_pair()
        pcm = self.root / "short.f32"
        pcm.write_bytes(bytes(8))
        pair["exports"]["F1"]["script"]["stems"] = [{"deck": "IN", "trackId": "in", "pcmPath": str(pcm)}]
        with self.assertRaisesRegex(ValueError, "short stems are not padded"):
            catalog.exported_script(pair, "F1", self.root)

    def test_overlap_window_uses_recipe_requirements_at_long_track_offsets(self):
        plan = {"recipeId": "F1", "frozenExitMs": 181_000, "frozenEntryMs": 76_000,
                "overlapDurationMs": 24_000, "barDurationMs": 2_000,
                "requiredStems": [{"deck": "IN", "stem": kind} for kind in ("DRUMS", "BASS", "VOCALS")]}
        requirements = audition._required_recipe_stems("F1", plan)
        self.assertEqual([], requirements["out"])
        self.assertEqual(["DRUMS", "BASS", "VOCALS"], requirements["in"])
        frozen_metadata = {"stemWindows": {"in": {"startMs": 76_000, "endMs": 82_000, "paddingMs": 2_000}}}
        self.assertEqual((76_000, 82_000, 2_000), audition._stem_window(frozen_metadata, "in"))
        with self.assertRaisesRegex(ValueError, "no approximate geometry"):
            audition._stem_window(plan, "in")
        self.assertEqual({"out": [], "in": []}, audition._required_recipe_stems("F1", dict(plan, requiredStems=[])))

    def test_canonical_assets_are_hash_verified_even_when_lengths_match(self):
        pair = self.canonical_pair("S1")
        with (self.root / "out.f32").open("r+b") as out:
            out.write(b"\x01\x00\x00\x00")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            catalog.exported_script(pair, "S1", self.root)

    def test_arbitrary_new_pair_has_no_invented_live_geometry(self):
        with self.assertRaisesRegex(ValueError, "live geometry parity unavailable"):
            audition.build_jvm_export([{"pairId": "new-pair"}], ["S1"], self.root / "refused-new",
                                      analysis_command="analysis", driver_command="driver", binary="renderer",
                                      command_runner=lambda *_a, **_k: self.fail("No process should launch before frozen geometry is supplied"))

    def test_all_26_cli_scripts_only_exports_and_list_mode(self):
        path = self.root / "pairs.json"
        path.write_text(json.dumps({"pairs": [self.pair]}))
        script = Path(audition.__file__)
        result = subprocess.run([sys.executable, str(script), "--pairs", str(path), "--out", str(self.root / "export"),
                                 "--recipes", "ALL,AUTO", "--scripts-only"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("exported 29 scripts", result.stdout)
        summary = json.loads((self.root / "export/scripts.json").read_text())
        self.assertEqual(set(catalog.RECIPE_IDS), {entry["recipeId"] for entry in summary["scripts"]})
        self.assertFalse((self.root / "export/index.html").exists())
        listing = subprocess.run([sys.executable, str(script), "--list-recipes"], capture_output=True, text=True)
        self.assertEqual(0, listing.returncode, listing.stderr)
        self.assertEqual(26, len(listing.stdout.splitlines()))

    def test_skipped_checks_never_claim_a_quality_pass(self):
        item = dict(pairName="test", recipeId="F1", wav="fixture.wav", pairId="test", mixqStatus="skipped")
        result = audition.render_index_md([item], "test")
        self.assertNotIn("every render passes", result)
        self.assertIn("incomplete or skipped", result)

    def test_supplied_broken_renderer_does_not_use_stale_wav(self):
        wav = self.root / "stale.wav"
        wav.write_bytes(b"old output")
        with self.assertRaisesRegex(RuntimeError, "Host renderer failed"):
            audition.render_with_binary("/bin/false", str(self.root / "script.json"), str(wav))
        self.assertEqual(b"old output", wav.read_bytes())

    def test_jvm_engine_runs_ffmpeg_exporter_planner_renderer_then_mixq(self):
        request = self.root / "source-pairs.json"
        request.write_text(json.dumps({"pairs": [{
            "pairId": "pair1", "name": "out -> in", "outSrc": str(self.root / "out.mp3"),
            "inSrc": str(self.root / "in.mp3"), "outTrackId": "out", "inTrackId": "in", "recipes": ["S1"],
            "playbackExports": self.playback_export("S1"),
        }]}))
        binary = self.root / "renderer"
        binary.write_text("fixture executable placeholder")
        events = []

        def runner(command, **kwargs):
            events.append(command)
            if command[0] == "ffmpeg":
                destination = Path(command[-1])
                if "-f" in command:
                    destination.write_bytes((self.root / "out.f32").read_bytes())
                else:
                    destination.write_bytes(b"decoded wav fixture")
                return subprocess.CompletedProcess(command, 0, "", "")
            if "TrackAnalysisExporter" in command[0]:
                args = _option_values(command)
                Path(args["out"]).write_text('{"schema":3}')
                return subprocess.CompletedProcess(command, 0, "", "")
            if "AuditionPlanDriver" in command[0]:
                args = _option_values(command)
                record = self.canonical_pair("S1")["exports"]["S1"]
                script = deepcopy(record["script"])
                script["outRegion"]["pcmPath"] = args["out-pcm"]
                script["inRegion"]["pcmPath"] = args["in-pcm"]
                script["assetHashes"] = {args[f"{deck}-pcm"]: hashlib.sha256(Path(args[f"{deck}-pcm"]).read_bytes()).hexdigest() for deck in ("out", "in")}
                plan = record["plan"]
                Path(args["out"]).write_text(json.dumps(script))
                Path(args["out-plan"]).write_text(json.dumps(plan))
                Path(args["out-meta"]).write_text(json.dumps({"recipeId": "S1"}))
                return subprocess.CompletedProcess(command, 0, "", "")
            if "--render" in command:
                Path(command[command.index("--out") + 1]).write_bytes(b"fresh rendered wav")
                return subprocess.CompletedProcess(command, 0, "", "")
            if command[1:2] == [audition.MIXQ]:
                result_path = Path(command[command.index("-o") + 1])
                result_path.write_text(json.dumps({"results": [{"passOverall": True, "gates": {}, "basename": "pair1_S1.wav"}]}))
                return subprocess.CompletedProcess(command, 0, "", "")
            self.fail(f"unexpected command: {command}")

        status = audition.main([
            "--engine", "jvm", "--pairs", str(request), "--out", str(self.root / "jvm-out"),
            "--recipes", "S1", "--binary", str(binary),
            "--analysis-command", "TrackAnalysisExporter", "--driver-command", "AuditionPlanDriver",
        ], command_runner=runner)
        self.assertEqual(0, status)
        names = ["ffmpeg" if command[0] == "ffmpeg" else command[0] for command in events]
        self.assertEqual(["ffmpeg", "ffmpeg", "TrackAnalysisExporter", "ffmpeg", "ffmpeg",
                          "TrackAnalysisExporter", "AuditionPlanDriver", str(binary), sys.executable], names)
        self.assertIn("--input", events[2])
        self.assertEqual("pcm_f32", _option_values(events[2])["input-format"])
        self.assertTrue(_option_values(events[2])["input"].endswith(".f32"), "Analysis must hash the actual native PCM, not the quantized WAV copy")
        self.assertIn("--out-analysis", events[6])
        self.assertEqual(1, events[6].index("--out-analysis"))
        self.assertEqual(_option_values(events[6])["out"], events[7][2])
        self.assertEqual(_option_values(events[6])["out-meta"], events[8][events[8].index("--meta") + 1])
        self.assertTrue((self.root / "jvm-out/pair1_S1.wav").is_file())
        self.assertIn("| PASS |", (self.root / "jvm-out/index.md").read_text())

    def test_jvm_stems_are_separated_after_plan_only_for_required_deck_window(self):
        request = self.root / "stem-pairs.json"
        request.write_text(json.dumps({"pairs": [{
            "pairId": "pair1", "name": "out -> in", "outSrc": str(self.root / "out.mp3"),
            "inSrc": str(self.root / "in.mp3"), "outTrackId": "out", "inTrackId": "in", "recipes": ["F1"],
            "playbackExports": self.playback_export("F1"),
        }]}))
        events = []

        def runner(command, **kwargs):
            events.append(command)
            if command[0] == "ffmpeg":
                target = Path(command[-1])
                target.write_bytes((self.root / "out.f32").read_bytes() if "-f" in command else b"wav fixture")
                return subprocess.CompletedProcess(command, 0, "", "")
            if "TrackAnalysisExporter" in command[0]:
                args = _option_values(command)
                if "--stems-only" in command:
                    requested = args["required-stems"].split(",")
                    Path(args["out-stems"]).write_text(json.dumps({
                        "schema": 1, "trackId": args["track-id"], "available": True,
                        "windowStartMs": 0, "windowEndMs": 14_000, "requiredStems": requested,
                        "stems": [],
                    }))
                else:
                    Path(args["out"]).write_text('{"schema":3}')
                return subprocess.CompletedProcess(command, 0, "", "")
            if "AuditionPlanDriver" in command[0]:
                args = _option_values(command)
                record = self.canonical_pair("F1")["exports"]["F1"]
                script = deepcopy(record["script"])
                script["outRegion"]["pcmPath"] = args["out-pcm"]
                script["inRegion"]["pcmPath"] = args["in-pcm"]
                script["assetHashes"] = {args[f"{deck}-pcm"]: hashlib.sha256(Path(args[f"{deck}-pcm"]).read_bytes()).hexdigest() for deck in ("out", "in")}
                plan = record["plan"]
                Path(args["out"]).write_text(json.dumps(script))
                Path(args["out-plan"]).write_text(json.dumps(plan))
                Path(args["out-meta"]).write_text(json.dumps({"recipeId": "F1", "stemWindows": {"in": {
                    "startMs": 0, "endMs": 12_000, "paddingMs": 2_000}}}))
                return subprocess.CompletedProcess(command, 0, "", "")
            self.fail(f"unexpected command: {command}")

        status = audition.main([
            "--engine", "jvm", "--pairs", str(request), "--out", str(self.root / "jvm-stems"),
            "--recipes", "F1", "--scripts-only", "--stems",
            "--analysis-command", "TrackAnalysisExporter", "--driver-command", "AuditionPlanDriver",
        ], command_runner=runner)
        self.assertEqual(0, status)
        stem_commands = [command for command in events if "--stems-only" in command]
        self.assertEqual(1, len(stem_commands), "F1 requires incoming stems only")
        stem_args = _option_values(stem_commands[0])
        self.assertEqual("0", stem_args["window-start-ms"])
        self.assertEqual("12000", stem_args["window-end-ms"])
        self.assertEqual("2000", stem_args["window-padding-ms"])
        self.assertEqual("DRUMS,BASS,VOCALS", stem_args["required-stems"])
        self.assertFalse(any("--out-stems" in command for command in events if "AuditionPlanDriver" in command[0]))
        final_driver = [command for command in events if "AuditionPlanDriver" in command[0]][-1]
        self.assertIn("--in-stems", final_driver)

    def test_jvm_planner_refusal_stops_before_render_or_mixq(self):
        request = self.root / "refused-pair.json"
        request.write_text(json.dumps({"pairs": [{"pairId": "pair1", "outSrc": "out.mp3", "inSrc": "in.mp3", "recipes": ["S1"],
                                                  "playbackExports": self.playback_export("S1")}]}))
        binary = self.root / "renderer"
        binary.write_text("fixture")
        events = []

        def runner(command, **kwargs):
            events.append(command)
            if command[0] == "ffmpeg":
                target = Path(command[-1])
                target.write_bytes((self.root / "out.f32").read_bytes() if "-f" in command else b"wav")
                return subprocess.CompletedProcess(command, 0, "", "")
            if command[0] == "analysis":
                Path(_option_values(command)["out"]).write_text("{}")
                return subprocess.CompletedProcess(command, 0, "", "")
            if command[0] == "driver":
                return subprocess.CompletedProcess(command, 1, "", "planner refused: TEMPO_GAP_EXCEEDED")
            self.fail(f"planner refusal must stop before render/mixq: {command}")

        with self.assertRaisesRegex(RuntimeError, "planner refused.*TEMPO_GAP_EXCEEDED"):
            audition.main([
                "--engine", "jvm", "--pairs", str(request), "--out", str(self.root / "refused"),
                "--recipes", "S1", "--binary", str(binary), "--analysis-command", "analysis", "--driver-command", "driver",
            ], command_runner=runner)
        self.assertFalse(any("--render" in command for command in events))
        self.assertFalse(any(command[1:2] == [audition.MIXQ] for command in events))

    def test_jvm_dry_run_prints_commands_without_launching_processes(self):
        request = self.root / "dry-pair.json"
        request.write_text(json.dumps({"pairs": [{"pairId": "pair1", "outSrc": "out.mp3", "inSrc": "in.mp3", "recipes": ["AUTO-CLUB"],
                                                  "playbackExports": self.playback_export("C1", "AUTO-CLUB")}]}))
        binary = self.root / "renderer"
        binary.write_text("fixture")
        output = io.StringIO()
        with redirect_stdout(output):
            status = audition.main([
                "--engine", "jvm", "--pairs", str(request), "--out", str(self.root / "dry"),
                "--analysis-command", "analysis", "--driver-command", "driver", "--jvm-dry-run", "--binary", str(binary),
            ], command_runner=lambda *_args, **_kwargs: self.fail("dry-run invoked a command"))
        self.assertEqual(0, status)
        planned = json.loads(output.getvalue())["plannedCommands"]
        self.assertEqual(9, len(planned))
        self.assertTrue(any("driver" in command[0] for command in planned))
        self.assertTrue(any("--style" in command and "CLUB" in command for command in planned))
        self.assertTrue(any("--render" in command for command in planned))
        self.assertTrue(any(command[1:2] == [audition.MIXQ] for command in planned))


if __name__ == "__main__":
    unittest.main(verbosity=2)
