"""Focused regression checks for the AE3 mixq quality-gate boundaries."""

from __future__ import annotations

import math
from pathlib import Path
import sys
import tempfile
import unittest
import wave
from unittest.mock import patch

import numpy as np

sys.dont_write_bytecode = True
import mixq  # noqa: E402


class MixqGateTest(unittest.TestCase):
    def test_true_peak_limit_and_non_finite_audio_are_rejected(self):
        at_limit = np.full(4096, mixq.db_to_lin(-1.0), dtype=np.float64)
        over_limit = np.full(4096, mixq.db_to_lin(-0.9), dtype=np.float64)

        self.assertLessEqual(
            mixq.true_peak_db(at_limit, at_limit),
            mixq.TRUE_PEAK_LIMIT_DBTP + mixq.GATE_FLOAT_TOLERANCE,
        )
        self.assertGreater(mixq.true_peak_db(over_limit, over_limit), -1.0)
        bad = at_limit.copy()
        bad[100] = math.nan
        with self.assertRaisesRegex(ValueError, "NaN/Inf"):
            mixq.true_peak_db(bad, at_limit)

    def test_analyze_file_applies_the_true_peak_ceiling(self):
        t = np.arange(mixq.SR * 2, dtype=np.float64) / mixq.SR
        hot = 0.98 * np.sin(2.0 * np.pi * 1000.0 * t)
        meta = {
            "transitionStartSec": 0.0,
            "transitionEndSec": 2.0,
            "swapSec": 1.0,
            "bpmOut": 120.0,
            "bpmIn": 120.0,
            "beatTimesIn": list(np.arange(0.0, 2.0, 0.5)),
        }
        with patch.object(mixq, "load_audio", return_value=(hot, hot, 2.0)):
            entry = mixq.analyze_file("hot.wav", meta)

        self.assertGreater(entry["metrics"]["truePeakDb"], mixq.TRUE_PEAK_LIMIT_DBTP)
        self.assertFalse(entry["gates"]["truePeak"])

    def test_lufs_deviation_boundary_and_drop_window_exemption(self):
        times = np.arange(7, dtype=np.float64)
        baseline = np.full(7, -14.0)

        at_limit = baseline.copy()
        at_limit[3] = -12.0
        dev, _ = mixq.lufs_deviation_lu(times, at_limit, 0.0, 6.0, None, 3.0)
        self.assertAlmostEqual(2.0, dev)

        over_limit = baseline.copy()
        over_limit[3] = -11.9
        dev, _ = mixq.lufs_deviation_lu(times, over_limit, 0.0, 6.0, None, 3.0)
        self.assertGreater(dev, 2.0)

        exempt, detail = mixq.lufs_deviation_lu(times, over_limit, 0.0, 6.0, (2.5, 3.5), 3.0)
        self.assertEqual(0.0, exempt)
        self.assertEqual(1, detail["windowsExempted"])

    def test_shifted_post_swap_beats_fail_phase_continuity_budget(self):
        period = 0.5
        before = np.arange(0.0, 10.0, period)
        after = np.arange(10.0, 20.0, period) + 0.030
        median_ms, p95_ms = mixq.beat_phase_error_ms(
            np.concatenate((before, after)), period, 10.0, period
        )
        self.assertGreater(median_ms, 10.0)
        self.assertGreater(p95_ms, 25.0)

    def test_placement_gate_reports_outgoing_and_incoming_vocal_conflicts(self):
        metadata = {
            "level": "CLUB",
            "exitSec": 10.0,
            "entrySec": 2.0,
            "vocalOut": [[0.0, 0.0], [9.0, 0.9], [11.0, 0.9]],
            "vocalIn": [[0.0, 0.9], [4.0, 0.9]],
        }
        count, detail = mixq.placement_violations(metadata, beat_sec=0.5)
        self.assertEqual(2, count)
        self.assertCountEqual(["exit-mid-vocal", "entry-mid-vocal"], detail["rulesHit"])

    def test_transition_silence_gap_over_150ms_fails(self):
        left = np.full(48_000, 0.1, dtype=np.float64)
        right = left.copy()
        left[10_000:18_000] = 0.0
        right[10_000:18_000] = 0.0

        count, detail = mixq.silence_gap_count(left, right, trans_start=0.1, trans_end=0.5)
        self.assertEqual(1, count)
        self.assertGreater(detail["longestGapSec"], 0.150)

    def test_end_to_end_metadata_stem_vocal_conflict_fails_gate(self):
        sample_rate = mixq.SR
        seconds = 6
        t = np.arange(sample_rate * seconds, dtype=np.float64) / sample_rate
        signal = 0.1 * np.sin(2.0 * np.pi * 440.0 * t)
        interleaved = np.column_stack((signal, signal))
        pcm = np.round(interleaved * 32767.0).astype("<i2")

        metadata = {
            "transitionStartSec": 1.0,
            "transitionEndSec": 5.0,
            "swapSec": 3.0,
            "bpmOut": 120.0,
            "bpmIn": 120.0,
            "beatTimesIn": list(np.arange(0.0, 6.0, 0.5)),
            "level": "CLUB",
            "vocalOut": [[0.0, 0.9], [6.0, 0.9]],
            "vocalIn": [[0.0, 0.9], [6.0, 0.9]],
            "placementViolations": 1,
        }

        with tempfile.TemporaryDirectory(prefix="mixq-gate-") as directory:
            path = Path(directory) / "pair-C1.wav"
            with wave.open(str(path), "wb") as out:
                out.setnchannels(2)
                out.setsampwidth(2)
                out.setframerate(sample_rate)
                out.writeframes(pcm.tobytes())

            entry = mixq.analyze_file(str(path), metadata)

        self.assertFalse(entry["gates"]["vocalOverlap"])
        self.assertFalse(entry["gates"]["placement"])
        self.assertGreater(entry["metrics"]["vocalOverlapSec"], 2.0)
        self.assertEqual(1, entry["metrics"]["placementViolations"])

    def test_canonical_26_recipe_filenames_round_trip_through_pair_parser(self):
        root = Path(__file__).resolve().parents[2]
        recipe_dir = root / "djmix-core/src/commonMain/resources/recipes"
        ids = tuple(
            f"{prefix}{number}"
            for prefix, count in (("S", 6), ("C", 9), ("F", 11))
            for number in range(1, count + 1)
        )

        self.assertEqual(set(ids), {path.stem for path in recipe_dir.glob("*.json")})
        self.assertEqual(26, len(ids))
        for recipe_id in ids:
            with self.subTest(recipe=recipe_id):
                self.assertEqual(("pair-01", recipe_id), mixq.split_pair_recipe(f"pair-01_{recipe_id}.wav"))

    def test_source_placement_uses_source_seconds_while_overlap_uses_mix_seconds(self):
        source_out = [0.0] * (40 * 50)
        source_out[32 * 50] = 0.9
        metadata = {"level": "CLUB", "exitSec": 32.0, "entrySec": 0.0, "vocalFps": 50,
                    "vocalTimeline": "mix-seconds", "vocalOut": [[0, 0.9], [2, 0.9]],
                    "vocalIn": [[0, 0], [2, 0]], "placementVocalOut": source_out, "placementVocalIn": [0.0] * 100}
        count, detail = mixq.placement_violations(metadata, 0.5)
        self.assertEqual(1, count)
        self.assertIn("exit-mid-vocal", detail["rulesHit"])

    def test_gain_weighted_overlap_uses_fifty_fps_and_exact_duration(self):
        sample_rate = mixq.SR
        signal = 0.1 * np.sin(2 * np.pi * 440 * np.arange(sample_rate * 4) / sample_rate)
        metadata = {"transitionStartSec": 0.0, "transitionEndSec": 4.0, "swapSec": 2.0,
                    "vocalOut": [[0, 1], [4, 1]], "vocalIn": [[0, 1], [4, 1]],
                    "gainOut": [[0, 0.5], [4, 0.5]], "gainIn": [[0, 1], [4, 1]]}
        with patch.object(mixq, "load_audio", return_value=(signal, signal, 4.0)):
            entry = mixq.analyze_file("fixture.wav", metadata)
        self.assertEqual(2.0, entry["metrics"]["vocalOverlapSec"])
        self.assertEqual("syvenu-curves-gain-weighted", entry["methods"]["vocalOverlap"])
        self.assertTrue(entry["gates"]["vocalOverlap"])

    def test_nonfinite_reversed_or_zero_fps_curves_are_rejected(self):
        for metadata in ({"vocalOut": [[1, 0], [0, 1]]}, {"vocalOut": [[0, math.nan]]},
                         {"vocalOut": [0, 1], "vocalFps": 0}):
            with self.subTest(metadata=metadata), self.assertRaises(ValueError):
                mixq.get_curve_array(metadata, "vocalOut")

    def test_canonical_render_does_not_claim_complete_quality_from_proxies(self):
        signal = np.full(mixq.SR * 2, 0.1)
        with patch.object(mixq, "load_audio", return_value=(signal, signal, 2.0)):
            entry = mixq.analyze_file("frozen.wav", {"canonicalPlaybackGeometry": True, "transitionStartSec": 0, "transitionEndSec": 2})
        self.assertFalse(entry["gates"]["analysisEvidence"])
        self.assertFalse(entry["passOverall"])
        self.assertTrue(any("Nirovi" in warning for warning in entry["warnings"]))

    def test_canonical_single_file_metadata_keeps_nested_hashes_and_stem_windows(self):
        metadata = {"canonicalPlaybackGeometry": True, "transitionStartSec": 0, "transitionEndSec": 2,
                    "sourcePcmSha256": {"out": "a" * 64, "in": "b" * 64},
                    "stemWindows": {"in": {"startMs": 120_000, "endMs": 136_000, "paddingMs": 2_000}},
                    "vocalOut": [[0, 0.8], [2, 0.8]]}
        self.assertIs(metadata, mixq.resolve_meta_for_file("pair.wav", metadata))
        self.assertEqual(metadata, mixq.resolve_meta_for_file("pair.wav", {"files": {"pair.wav": metadata}}))


if __name__ == "__main__":
    unittest.main(verbosity=2)
