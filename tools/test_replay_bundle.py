"""Tests for the host Flag-bundle replay validator."""

from __future__ import annotations

import hashlib
import io
import json
import subprocess
import tempfile
import unittest
import warnings
import zipfile
import wave
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

import replay_bundle


class ReplayBundleTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def payloads() -> dict[str, bytes]:
        geometry = {"out": {"regionStartSec": 1.25, "frameCount": 4},
                    "inbound": {"regionStartSec": 0.25, "frameCount": 4}, "handoverMixSec": 1.0,
                    "handoverTrackSec": 1.0, "incomingStartMixSec": 0.0,
                    "incomingStartTrackSec": 0.0, "highQuality": False}
        plan = {"schema": 1, "planId": "p-1", "generation": 2, "recipeId": "S5",
                "outTrackId": "out", "inTrackId": "in", "djLevel": "SUBTLE"}
        script = {"schema": 1, "planId": "p-1", "generation": 2, "renderGeometry": geometry, "sampleRateHz": 48_000,
                  "outRegion": {"deck": "OUT", "trackId": "out"}, "inRegion": {"deck": "IN", "trackId": "in"}}
        data = {
            "plan.json": json.dumps(plan).encode(),
            "script.json": json.dumps(script).encode(),
            "render_geometry.json": json.dumps(geometry).encode(),
            "recipe.json": b'{"schema":2,"id":"S5"}',
            "events_5min.jsonl": b'{"ts":1800000000000,"event":"PLAN_FROZEN","state":"PREPARING","generation":2,"planId":"p-1","outId":"out","inId":"in"}\n',
            "telemetry_5min.jsonl": b'{"schema":3,"engine":"3.0.0","timestampMs":1800000000000,"eventType":"MIX_PREP_STARTED","state":"PREPARING","generation":2,"planId":"p-1","outId":"out","inId":"in"}\n',
            "analysis_summary.json": b'{"engine":"3.0.0","planId":"p-1","recipeId":"S5","out":null,"in":null}',
        }
        entries = {
            name: {"available": True, "entry": name, "bytes": len(body),
                   "sha256": hashlib.sha256(body).hexdigest(), "detail": None}
            for name, body in data.items()
        }
        for name in ("analysis_out.json", "analysis_in.json", "flags_snapshot.json", "taste_snapshot.json", "audioCapture"):
            entries[name] = {"available": False, "entry": None, "detail": "not_supplied", "bytes": 0, "sha256": None}
        manifest = {"schema": 1, "engine": "3.0.0", "trigger": "Flag",
                    "createdAtMs": 1, "planId": "p-1", "generation": 2,
                    "outTrackId": "out", "inTrackId": "in", "recipeSha256": entries["recipe.json"]["sha256"],
                    "entries": entries, "assetSha256": {}, "modelSha256": {},
                    "warnings": []}
        data["manifest.json"] = json.dumps(manifest).encode()
        return data

    def write_bundle(self, mutate=None) -> Path:
        payloads = self.payloads()
        if mutate:
            mutate(payloads)
        path = self.root / "bundle.zip"
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, data in payloads.items():
                archive.writestr(name, data)
        return path

    def test_actual_flag_schema_passes_check_only(self) -> None:
        bundle = self.write_bundle()
        args = replay_bundle.main([str(bundle), "--check-only", "--work-dir", str(self.root)])
        self.assertEqual(0, args)

    def test_manifest_detects_content_tampering(self) -> None:
        bundle = self.write_bundle(lambda payloads: payloads.__setitem__("script.json", b'{"changed":true}'))
        with tempfile.TemporaryDirectory() as dest:
            with self.assertRaisesRegex(replay_bundle.ReplayError, "manifest (byte count|sha256) mismatch"):
                replay_bundle.unpack(bundle, Path(dest))

    def test_rejects_unsupported_engine(self) -> None:
        def change_engine(payloads: dict[str, bytes]) -> None:
            manifest = json.loads(payloads["manifest.json"])
            manifest["engine"] = "9.9.9"
            payloads["manifest.json"] = json.dumps(manifest).encode()
        with tempfile.TemporaryDirectory() as dest:
            with self.assertRaisesRegex(replay_bundle.ReplayError, "unsupported bundle engine"):
                replay_bundle.unpack(self.write_bundle(change_engine), Path(dest))

    def test_rejects_zip_path_traversal(self) -> None:
        bundle = self.root / "traversal.zip"
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("../plan.json", b"{}")
            archive.writestr("script.json", b"{}")
        with tempfile.TemporaryDirectory() as dest:
            with self.assertRaisesRegex(replay_bundle.ReplayError, "unsafe zip member"):
                replay_bundle.unpack(bundle, Path(dest))

    def test_legacy_bundle_without_manifest_remains_supported(self) -> None:
        bundle = self.root / "legacy.zip"
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("plan.json", b'{"planId":"p","generation":1}')
            archive.writestr("script.json", b'{"planId":"p","generation":1}')
        with tempfile.TemporaryDirectory() as dest:
            members = replay_bundle.unpack(bundle, Path(dest))
            self.assertIn("plan.json", members)
            self.assertNotIn("manifest.json", members)

    def test_detects_script_plan_identity_mismatch(self) -> None:
        errors = replay_bundle.validate_flag_payload(
            {"planId": "p", "generation": 1},
            {"planId": "other", "generation": 1}, None,
        )
        self.assertTrue(any("identity mismatch" in error for error in errors))

    @staticmethod
    def mutate_manifest(payloads, mutate) -> None:
        manifest = json.loads(payloads["manifest.json"])
        mutate(manifest)
        payloads["manifest.json"] = json.dumps(manifest).encode()

    def unpack_error(self, mutate, pattern) -> None:
        with tempfile.TemporaryDirectory() as dest:
            with self.assertRaisesRegex(replay_bundle.ReplayError, pattern):
                replay_bundle.unpack(self.write_bundle(mutate), Path(dest))

    def test_rejects_available_entry_missing_from_zip(self) -> None:
        self.unpack_error(lambda p: p.pop("recipe.json"), "available entry is missing")

    def test_rejects_incomplete_status_table(self) -> None:
        self.unpack_error(lambda p: self.mutate_manifest(p, lambda m: m["entries"].pop("audioCapture")),
                          "actual Flag status keys")

    def test_hashes_audio_capture_by_logical_key(self) -> None:
        def add_capture(payloads):
            payloads["mix_capture.pcm"] = b"\x00\x01\x02\x03"
            self.mutate_manifest(payloads, lambda m: m["entries"].__setitem__("audioCapture", {
                "available": True, "entry": "mix_capture.pcm", "bytes": 4,
                "sha256": hashlib.sha256(payloads["mix_capture.pcm"]).hexdigest(), "detail": None}))
        with tempfile.TemporaryDirectory() as dest:
            result = replay_bundle.unpack(self.write_bundle(add_capture), Path(dest))
            self.assertEqual(b"\x00\x01\x02\x03", result["mix_capture.pcm"].read_bytes())

    def test_rejects_recipe_digest_disagreement(self) -> None:
        self.unpack_error(lambda p: self.mutate_manifest(p, lambda m: m.__setitem__("recipeSha256", "a" * 64)),
                          "recipeSha256 disagrees")

    def test_rejects_non_boolean_available(self) -> None:
        self.unpack_error(lambda p: self.mutate_manifest(p, lambda m: m["entries"]["recipe.json"].__setitem__("available", 1)),
                          "boolean available")

    def test_rejects_duplicate_zip_entries(self) -> None:
        bundle = self.write_bundle()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(bundle, "a") as archive:
                archive.writestr("plan.json", b"{}")
        with self.assertRaisesRegex(replay_bundle.ReplayError, "duplicate bundle"):
            replay_bundle.unpack(bundle, self.root / "unpacked")

    def test_malformed_jsonl_is_not_skipped(self) -> None:
        path = self.root / "events.jsonl"
        path.write_text('{"state":"READY"}\n{broken}\n')
        with self.assertRaisesRegex(replay_bundle.ReplayError, "invalid JSONL"):
            replay_bundle.read_jsonl(path)

    def test_invalid_utf8_is_not_replaced(self) -> None:
        path = self.root / "events.jsonl"
        path.write_bytes(b'{"state":"\xff"}\n')
        with self.assertRaisesRegex(replay_bundle.ReplayError, "cannot read"):
            replay_bundle.read_jsonl(path)

    def test_detects_frozen_geometry_disagreement(self) -> None:
        errors = replay_bundle.validate_flag_payload({"planId": "p", "generation": 1},
            {"planId": "p", "generation": 1, "renderGeometry": {"highQuality": True}}, {"highQuality": False})
        self.assertEqual(["render_geometry.json does not match script.renderGeometry"], errors)

    def cli_fixture(self):
        executable = self.root / "djmix-render"
        executable.write_text("#!/bin/sh\nexit 99\n")
        executable.chmod(0o755)
        return self.write_bundle(), executable, self.root / "trace.json"

    def trace_receipt(self):
        script_bytes = self.payloads()["script.json"]
        return {"schema": 1, "operation": "decision_trace", "engine": "3.0.0", "planId": "p-1", "generation": 2,
                "reducerReplayComplete": False, "reducerCoverage": {"complete": False, "equivalent": None, "replayedEventCount": 0},
                "capturedScriptSha256": hashlib.sha256(script_bytes).hexdigest(), "compiledScriptSha256": "a" * 64,
                "compiledScript": json.loads(script_bytes), "matchingVariants": ["normal"]}

    def test_decision_cli_uses_real_contract_without_audio_tools(self) -> None:
        bundle, executable, trace = self.cli_fixture()
        def invoke(command, **kwargs):
            self.assertEqual([str(executable), "replay", "--bundle", str(bundle), "--out"], command[:5])
            self.assertNotEqual(trace, Path(command[5]), "CLI must stage output instead of trusting a stale destination")
            Path(command[5]).write_text(json.dumps(self.trace_receipt()))
            return subprocess.CompletedProcess(command, 0, "", "")
        with patch.object(replay_bundle.subprocess, "run", side_effect=invoke) as run, \
             patch.object(replay_bundle, "require_tool", side_effect=AssertionError("audio tool requested")), \
             redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(0, replay_bundle.main([str(bundle), "--render-cli", str(executable),
                "--decision-trace", str(trace), "--work-dir", str(self.root)]))
        self.assertEqual(1, run.call_count)
        self.assertEqual(self.trace_receipt(), json.loads(trace.read_text()))

    def test_decision_cli_requires_output_file(self) -> None:
        bundle, executable, trace = self.cli_fixture()
        trace.write_text("previous destination")
        with patch.object(replay_bundle.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "", "")), \
             redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(1, replay_bundle.main([str(bundle), "--render-cli", str(executable),
                "--decision-trace", str(trace), "--work-dir", str(self.root)]))
        self.assertEqual("previous destination", trace.read_text())

    def test_decision_cli_preserves_usage_exit_code(self) -> None:
        bundle, executable, trace = self.cli_fixture()
        with patch.object(replay_bundle.subprocess, "run", return_value=subprocess.CompletedProcess([], 2, "", "usage")), \
             redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(2, replay_bundle.main([str(bundle), "--render-cli", str(executable),
                "--decision-trace", str(trace), "--work-dir", str(self.root)]))

    def test_decision_cli_options_must_be_paired(self) -> None:
        bundle = self.write_bundle()
        with redirect_stderr(io.StringIO()):
            self.assertEqual(2, replay_bundle.main([str(bundle), "--decision-trace", str(self.root / "t.json")]))

    def test_duplicate_json_keys_are_rejected(self) -> None:
        path = self.root / "bad.json"
        path.write_text('{"planId":"a","planId":"b"}')
        with self.assertRaisesRegex(replay_bundle.ReplayError, "duplicate JSON key"):
            replay_bundle.load_json(path)

    def test_retained_region_coordinates_and_invalid_values(self) -> None:
        script = {"sampleRateHz": 48_000, "renderGeometry": {
            "out": {"regionStartSec": 1.25, "frameCount": 100}}}
        self.assertEqual((60_000, 100, 48_000), replay_bundle.retained_pcm_region(script, "out"))
        self.assertEqual((0, None, 48_000), replay_bundle.retained_pcm_region({}, "out"))
        for bad in ({}, {"regionStartSec": -1, "frameCount": 100},
                    {"regionStartSec": 0, "frameCount": 0}, {"regionStartSec": float("nan"), "frameCount": 100}):
            with self.subTest(bad=bad), self.assertRaises(replay_bundle.ReplayError):
                replay_bundle.retained_pcm_region({"sampleRateHz": 48_000, "renderGeometry": {"out": bad}}, "out")

    def test_pcm_decode_uses_retained_frames_and_cache(self) -> None:
        source, destination = self.root / "source.wav", self.root / "out.f32"
        source.write_bytes(b"source")
        def invoke(command, **kwargs):
            self.assertIn("aresample=48000,atrim=start_sample=10:end_sample=14", command)
            Path(command[-1]).write_bytes(bytes(32))
            return subprocess.CompletedProcess(command, 0, "", "")
        with patch.object(replay_bundle.subprocess, "run", side_effect=invoke) as run:
            replay_bundle.decode_f32(source, destination, "ffmpeg", self.root / "cache", 10, 4, 48_000)
            replay_bundle.decode_f32(source, destination, "ffmpeg", self.root / "cache", 10, 4, 48_000)
        self.assertEqual(bytes(32), destination.read_bytes())
        self.assertEqual(1, run.call_count)

    def test_pcm_decode_rejects_incomplete_frame_coverage(self) -> None:
        source = self.root / "source.wav"
        source.write_bytes(b"source")
        def invoke(command, **kwargs):
            Path(command[-1]).write_bytes(bytes(8))
            return subprocess.CompletedProcess(command, 0, "", "")
        with patch.object(replay_bundle.subprocess, "run", side_effect=invoke):
            with self.assertRaisesRegex(replay_bundle.ReplayError, "PCM coverage mismatch"):
                replay_bundle.decode_f32(source, self.root / "out.f32", "ffmpeg", self.root / "cache", 0, 4)

    def test_actual_summary_ring_does_not_invent_omitted_ticks(self) -> None:
        events = [
            {"ts": 1_800_000_000_000, "event": "RENDERER_PREPARED", "state": "READY", "generation": 2},
            {"ts": 1_800_000_000_001, "event": "HANDOVER_COMPLETE", "state": "DONE", "generation": 2},
        ]
        errors, messages = replay_bundle.validate_events(events, [], "p-1")
        self.assertEqual([], errors)
        self.assertTrue(any("omit ticks" in message for message in messages))

    @staticmethod
    def replace_json(payloads, name, value) -> None:
        payloads[name] = json.dumps(value).encode()
        manifest = json.loads(payloads["manifest.json"])
        manifest["entries"][name]["bytes"] = len(payloads[name])
        manifest["entries"][name]["sha256"] = hashlib.sha256(payloads[name]).hexdigest()
        if name == "recipe.json":
            manifest["recipeSha256"] = manifest["entries"][name]["sha256"]
        payloads["manifest.json"] = json.dumps(manifest).encode()

    def quiet_main(self, arguments):
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            return replay_bundle.main(arguments)

    def test_output_modes_are_mutually_exclusive_and_preserve_requested_wav(self) -> None:
        bundle, executable, trace = self.cli_fixture()
        wav = self.root / "out.wav"
        wav.write_bytes(b"original")
        with patch.object(replay_bundle.subprocess, "run", side_effect=AssertionError("must reject before tool invocation")):
            self.assertEqual(2, self.quiet_main([str(bundle), "--render-cli", str(executable), "--decision-trace", str(trace), "--out", str(wav)]))
            self.assertEqual(2, self.quiet_main([str(bundle), "--check-only", "--out", str(wav)]))
            self.assertEqual(2, self.quiet_main([str(bundle), "--render-cli", str(executable), "--decision-trace", str(wav)]))
        self.assertEqual(b"original", wav.read_bytes())

    def test_audio_requires_core_cli_instead_of_bypassing_guard(self) -> None:
        bundle, executable, _ = self.cli_fixture()
        with patch.object(replay_bundle, "require_tool", side_effect=AssertionError("must not start ffmpeg")):
            self.assertEqual(2, self.quiet_main([str(bundle), "--audio-dir", str(self.root), "--renderer", str(executable), "--out", str(self.root / "out.wav")]))

    def test_trace_protocol_and_compiled_script_are_checked(self) -> None:
        bundle, executable, trace = self.cli_fixture()
        for key, value in (("operation", "render"), ("planId", "other"), ("compiledScript", {}), ("reducerReplayComplete", True)):
            trace.write_text("previous")
            def invoke(command, **kwargs):
                result = self.trace_receipt()
                result[key] = value
                Path(command[-1]).write_text(json.dumps(result))
                return subprocess.CompletedProcess(command, 0, "", "")
            with self.subTest(field=key), patch.object(replay_bundle.subprocess, "run", side_effect=invoke):
                self.assertEqual(1, self.quiet_main([str(bundle), "--render-cli", str(executable), "--decision-trace", str(trace), "--work-dir", str(self.root)]))
            self.assertEqual("previous", trace.read_text())

    @staticmethod
    def make_wav(path):
        pcm = bytes(16)
        with wave.open(str(path), "wb") as output:
            output.setnchannels(2)
            output.setsampwidth(2)
            output.setframerate(48_000)
            output.writeframes(pcm)
        return hashlib.sha256(pcm).hexdigest()

    def audio_fixture(self, mutate=None):
        bundle = self.write_bundle(mutate)
        executable = self.root / "host-tool"
        executable.write_text("#!/bin/sh\nexit 99\n")
        executable.chmod(0o755)
        audio = self.root / "audio"
        audio.mkdir(exist_ok=True)
        self.make_wav(audio / "out.wav")
        self.make_wav(audio / "in.wav")
        target = self.root / "result.wav"
        arguments = [str(bundle), "--render-cli", str(executable), "--renderer", str(executable),
                     "--audio-dir", str(audio), "--out", str(target), "--work-dir", str(self.root)]
        return bundle, executable, target, arguments

    @staticmethod
    def fake_decode(source, target, ffmpeg, cache, *region):
        target.write_bytes(bytes(32))

    def render_receipt(self, digest):
        return {"schema": 1, "operation": "render", "engine": "3.0.0", "planId": "p-1", "generation": 2,
                "rendered": True, "reducerReplayComplete": False, "reducerEquivalent": None,
                "bitExactParity": "not_established", "pcmSha256": digest, "sampleRateHz": 48_000, "sampleCount": 8}

    def test_audio_delegates_supported_native_protocol_and_publishes_real_wav(self) -> None:
        bundle, executable, target, arguments = self.audio_fixture()
        def invoke(command, **kwargs):
            self.assertEqual([str(executable), "replay", str(bundle), "--out"], command[:4])
            self.assertEqual("--renderer-bin", command[5])
            self.assertEqual("--deck-a-pcm", command[7])
            self.assertEqual("--deck-b-pcm", command[9])
            self.assertEqual("--json", command[11])
            stage = Path(command[4])
            self.assertNotEqual(stage, target)
            digest = self.make_wav(stage)
            return subprocess.CompletedProcess(command, 0, json.dumps(self.render_receipt(digest)), "")
        with patch.object(replay_bundle, "require_tool", return_value="ffmpeg"), \
             patch.object(replay_bundle, "decode_f32", side_effect=self.fake_decode) as decode, \
             patch.object(replay_bundle.subprocess, "run", side_effect=invoke) as run:
            self.assertEqual(0, self.quiet_main(arguments))
        self.assertEqual(2, decode.call_count)
        self.assertEqual(1, run.call_count)
        with wave.open(str(target), "rb") as result:
            self.assertEqual(4, result.getnframes())

    def test_json_cannot_substitute_for_requested_wav(self) -> None:
        _, _, target, arguments = self.audio_fixture()
        target.write_bytes(b"previous WAV")
        def invoke(command, **kwargs):
            Path(command[4]).write_text(json.dumps(self.trace_receipt()))
            return subprocess.CompletedProcess(command, 0, json.dumps(self.render_receipt("a" * 64)), "")
        with patch.object(replay_bundle, "require_tool", return_value="ffmpeg"), \
             patch.object(replay_bundle, "decode_f32", side_effect=self.fake_decode), \
             patch.object(replay_bundle.subprocess, "run", side_effect=invoke):
            self.assertEqual(1, self.quiet_main(arguments))
        self.assertEqual(b"previous WAV", target.read_bytes())

    def test_render_receipt_must_match_actual_pcm_and_identity(self) -> None:
        _, _, target, arguments = self.audio_fixture()
        for key, value in (("pcmSha256", "a" * 64), ("operation", "decision_trace"), ("planId", "other"), ("sampleCount", 999)):
            target.write_bytes(b"previous")
            def invoke(command, **kwargs):
                digest = self.make_wav(Path(command[4]))
                receipt = self.render_receipt(digest)
                receipt[key] = value
                return subprocess.CompletedProcess(command, 0, json.dumps(receipt), "")
            with self.subTest(field=key), patch.object(replay_bundle, "require_tool", return_value="ffmpeg"), \
                 patch.object(replay_bundle, "decode_f32", side_effect=self.fake_decode), \
                 patch.object(replay_bundle.subprocess, "run", side_effect=invoke):
                self.assertEqual(1, self.quiet_main(arguments))
            self.assertEqual(b"previous", target.read_bytes())

    def test_stems_fail_clearly_before_audio_tools_without_being_removed(self) -> None:
        def add_stems(payloads):
            script = json.loads(payloads["script.json"])
            script["stems"] = [{"pcmPath": "/private/device/stem.f32"}]
            self.replace_json(payloads, "script.json", script)
        _, _, target, arguments = self.audio_fixture(add_stems)
        with patch.object(replay_bundle, "require_tool", side_effect=AssertionError("must reject stems before ffmpeg")):
            self.assertEqual(1, self.quiet_main(arguments))
        self.assertFalse(target.exists())

    def test_archive_track_id_is_not_an_implicit_host_path(self) -> None:
        outside = self.root / "private.wav"
        self.make_wav(outside)
        library = self.root / "library"
        library.mkdir()
        with self.assertRaisesRegex(replay_bundle.ReplayError, "no audio file"):
            replay_bundle.resolve_track(str(outside), library)
        self.assertEqual(outside, replay_bundle.resolve_track(str(outside), library, allow_explicit_path=True))

    def test_extraction_never_follows_existing_target_symlink(self) -> None:
        bundle = self.write_bundle()
        victim = self.root / "victim"
        victim.write_bytes(b"untouched")
        destination = self.root / "extract"
        destination.mkdir()
        (destination / "plan.json").symlink_to(victim)
        with self.assertRaisesRegex(replay_bundle.ReplayError, "existing extraction target"):
            replay_bundle.unpack(bundle, destination)
        self.assertEqual(b"untouched", victim.read_bytes())

    def test_member_size_count_and_unknown_entries_are_bounded(self) -> None:
        bundle = self.write_bundle()
        with patch.object(replay_bundle, "MAX_TEXT_BYTES", 16):
            with self.assertRaisesRegex(replay_bundle.ReplayError, "size limit"):
                replay_bundle.unpack(bundle, self.root / "oversize")
        self.unpack_error(lambda payloads: payloads.__setitem__("unknown.json", b"{}"), "unknown Flag bundle")
        def add_many(payloads):
            payloads.update({f"extra-{i}": b"" for i in range(20)})
        self.unpack_error(add_many, "too many ZIP members")

    def test_inspection_rejects_wrong_schema_and_track_identity(self) -> None:
        for field, value in (("schema", 9), ("outRegion", {"deck": "OUT", "trackId": "other"})):
            def mutate(payloads):
                script = json.loads(payloads["script.json"])
                script[field] = value
                self.replace_json(payloads, "script.json", script)
            with self.subTest(field=field):
                self.assertEqual(1, self.quiet_main([str(self.write_bundle(mutate)), "--check-only", "--work-dir", str(self.root)]))

    def test_deep_nonfinite_and_duplicate_escaped_json_are_rejected(self) -> None:
        for text in ("[" * 129 + "0" + "]" * 129, '{"number":1e999}', '{"x":1,"\\u0078":2}'):
            with self.subTest(text=text[:20]), self.assertRaises(ValueError):
                replay_bundle._json_loads(text)

    def test_captured_countdown_failure_is_diagnostic_not_corrupt_data(self) -> None:
        def mutate(payloads):
            row = json.loads(payloads["telemetry_5min.jsonl"])
            row["countdownBroken"] = True
            self.replace_json(payloads, "telemetry_5min.jsonl", row)
        self.assertEqual(0, self.quiet_main([str(self.write_bundle(mutate)), "--check-only", "--work-dir", str(self.root)]))

    def test_unproduced_reducer_event_dialect_is_not_silently_assumed(self) -> None:
        def mutate(payloads):
            self.replace_json(payloads, "events_5min.jsonl", {"mixEvent": {"type": "user_pause"}})
        self.assertEqual(1, self.quiet_main([str(self.write_bundle(mutate)), "--check-only", "--work-dir", str(self.root)]))


if __name__ == "__main__":
    unittest.main()
