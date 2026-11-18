#!/usr/bin/env python3
"""CUE-only, real-mel behavioral agreement gate; NOT ground-truth/NPU accuracy proof."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import socket
import subprocess
import time
import wave

import numpy as np
import onnxruntime as ort

CHUNK = 1500
BORDER = 6
FPS = 50.0
# Declared before inference; never relax these after seeing the fixture.
THRESHOLDS = {"beat_f1_at_1_frame": 0.95, "snapped_downbeat_f1_at_1_frame": 0.95, "bpm_delta_max": 0.5}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def median(values):
    # Kotlin's sorted[size / 2] is the upper median, not numpy's even-count mean.
    return sorted(values)[len(values) // 2] if values else 0.0


def pick_peaks(logits):
    peaks = []
    for index in range(len(logits)):
        if logits[index] <= 0:
            continue
        if all(logits[other] <= logits[index]
               for other in range(max(0, index - 3), min(len(logits), index + 4))):
            peaks.append(index)
    deduped = []
    index = 0
    while index < len(peaks):
        mean, count = float(peaks[index]), 1
        while index + 1 < len(peaks) and peaks[index + 1] - mean <= 1:
            index += 1
            count += 1
            mean += (peaks[index] - mean) / count
        deduped.append(math.floor(mean + 0.5))  # Kotlin roundToInt, nonnegative indices.
        index += 1
    refined = []
    for frame in deduped:
        if frame == 0 or frame + 1 >= len(logits):
            refined.append(float(frame))
            continue
        left, centre, right = map(float, logits[frame - 1:frame + 2])
        denominator = left - 2 * centre + right
        offset = 0.0 if abs(denominator) <= 1e-9 else max(-0.5, min(0.5, 0.5 * (left - right) / denominator))
        refined.append(frame + offset)
    return refined


def tempo(beats):
    if len(beats) < 8:
        return 0.0
    seconds = [frame / FPS for frame in beats]
    gaps = [right - left for left, right in zip(seconds, seconds[1:])]
    rough = median(gaps)
    if rough <= 0:
        return 0.0
    kept = [gap for gap in gaps if abs(gap - rough) <= rough * 0.2]
    interval = median(kept if len(kept) >= 4 else gaps)
    bpm = 60.0 / interval if interval > 0 else 0.0
    return bpm if 40.0 <= bpm <= 220.0 else 0.0


def grid(beat, downbeat):
    beats = pick_peaks(beat)
    raw_downbeats = pick_peaks(downbeat)
    indices = sorted(set(min(range(len(beats)), key=lambda i: abs(beats[i] - frame))
                         for frame in raw_downbeats)) if beats else []
    snapped = [beats[index] for index in indices]
    return {
        "bpm": tempo(beats), "beat_count": len(beats), "raw_downbeat_count": len(raw_downbeats),
        "downbeat_count": len(snapped), "beats_frames": beats, "beats_seconds": [frame / FPS for frame in beats],
        "raw_downbeats_frames": raw_downbeats, "downbeats_frames": snapped,
        "downbeats_seconds": [frame / FPS for frame in snapped], "downbeat_beat_indices": indices,
        "median_downbeat_period_seconds": median([right / FPS - left / FPS for left, right in zip(snapped, snapped[1:])]),
        "median_beats_per_downbeat": median([right - left for left, right in zip(indices, indices[1:])]),
    }


def match_peaks(reference, candidate, tolerance=1.0):
    # One-to-one, maximum-cardinality matching of ordered event intervals.
    left = right = 0
    distances = []
    while left < len(reference) and right < len(candidate):
        difference = candidate[right] - reference[left]
        if abs(difference) <= tolerance:
            distances.append(abs(difference))
            left += 1
            right += 1
        elif difference < -tolerance:
            right += 1
        else:
            left += 1
    matches = len(distances)
    precision = matches / len(candidate) if candidate else 0.0
    recall = matches / len(reference) if reference else 0.0
    return {
        "tolerance_frames": tolerance, "matches": matches, "reference_count": len(reference),
        "candidate_count": len(candidate), "precision": precision, "recall": recall,
        "f1": 2 * matches / (len(reference) + len(candidate)) if reference or candidate else 0.0,
        "matched_mean_delta_frames": float(np.mean(distances)) if distances else None,
        "matched_max_delta_frames": max(distances) if distances else None,
    }


def infer(model, mel, use_qdq, out_dir):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.enable_cpu_mem_arena = False
    options.enable_mem_pattern = False
    session = ort.InferenceSession(str(model), sess_options=options, providers=["CPUExecutionProvider"])
    inputs, outputs = session.get_inputs(), session.get_outputs()
    assert inputs[0].name == "input_spectrogram"
    assert {output.name for output in outputs} >= {"beat", "downbeat"}
    if use_qdq:
        assert inputs[0].shape == [1, CHUNK, 128], inputs[0].shape
    frames = len(mel)
    beat = np.zeros(frames, dtype=np.float32)
    downbeat = np.zeros(frames, dtype=np.float32)
    written = np.zeros(frames, dtype=np.int32)
    chunks = []
    start = 0
    while start < frames:
        length = min(CHUNK, frames - start)
        if length <= 2 * BORDER and start > 0:
            break
        chunk = mel[start:start + length].copy()
        input_frames = CHUNK if use_qdq else length
        if input_frames > length:
            chunk = np.pad(chunk, ((0, input_frames - length), (0, 0)), mode="constant")
        tensor = np.ascontiguousarray(chunk[None, :, :], dtype=np.float32)
        before = time.perf_counter()
        # CPU flag-off keeps positional outputs; candidate requests named heads only.
        result = session.run(["beat", "downbeat"] if use_qdq else None, {inputs[0].name: tensor})
        elapsed = (time.perf_counter() - before) * 1000
        assert result[0].shape == result[1].shape == (1, input_frames)
        assert np.isfinite(result[0]).all() and np.isfinite(result[1]).all()
        keep_from = 0 if start == 0 else BORDER
        keep_to = length if start + length >= frames else length - BORDER
        beat[start + keep_from:start + keep_to] = result[0][0, keep_from:keep_to]
        downbeat[start + keep_from:start + keep_to] = result[1][0, keep_from:keep_to]
        written[start + keep_from:start + keep_to] += 1
        chunks.append({"start": start, "real_frames": length, "input_frames": input_frames,
                       "keep_from": keep_from, "keep_to": keep_to, "cpu_ms": elapsed})
        if start + length >= frames:
            break
        start += CHUNK - 2 * BORDER
    assert np.all(written == 1), "Chunk crop left missing/duplicated frames"
    np.savez(out_dir / f"{model.stem}-logits.npz", beat=beat, downbeat=downbeat)
    return {
        "path": str(model), "bytes": model.stat().st_size, "sha256": sha256(model),
        "providers": session.get_providers(), "inputs": [{"name": i.name, "shape": i.shape, "type": i.type} for i in inputs],
        "outputs": [{"name": o.name, "shape": o.shape, "type": o.type} for o in outputs],
        "chunks": chunks, "cpu_inference_ms": sum(chunk["cpu_ms"] for chunk in chunks),
        "grid": grid(beat, downbeat),
    }, beat, downbeat


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wav", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--mel-probe", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if socket.gethostname() != "2887726280f4":
        parser.error("CUE-only validation; do not run on the workstation")
    if ort.__version__ != "1.31.0":
        parser.error(f"Use the pinned ORT 1.31.0 CPU venv, got {ort.__version__}")
    args.output.mkdir(parents=True, exist_ok=True)
    with wave.open(str(args.wav), "rb") as wav:
        channels, rate, samples, width = wav.getnchannels(), wav.getframerate(), wav.getnframes(), wav.getsampwidth()
        assert channels == 2 and rate == 48000 and width == 2
        assert abs(samples / rate - 239.808) < 1 / rate, "Wrong staged audio fixture"
        pcm = np.frombuffer(wav.readframes(samples), dtype="<i2").astype(np.float32).reshape(-1, channels) / np.float32(32768)
    mono = pcm.mean(axis=1, dtype=np.float32)
    mono_path, mel_path = args.output / "stutter-mono48k.f32", args.output / "stutter-native-mel.f32"
    mono.astype("<f4").tofile(mono_path)
    command = [str(args.mel_probe.resolve()), str(mono_path.resolve()), str(rate), str(mel_path.resolve())]
    metadata = json.loads(subprocess.check_output(command, text=True))
    mel = np.fromfile(mel_path, dtype="<f4").reshape(metadata["frames"], metadata["mels"])
    assert np.isfinite(mel).all()
    reference, reference_beat, reference_down = infer(args.assets / "beat_this_final0_q8.onnx", mel, False, args.output)
    candidate, candidate_beat, candidate_down = infer(args.assets / "nirovi_qnn.onnx", mel, True, args.output)
    agreement = {
        "beat": match_peaks(reference["grid"]["beats_frames"], candidate["grid"]["beats_frames"]),
        "raw_downbeat": match_peaks(reference["grid"]["raw_downbeats_frames"], candidate["grid"]["raw_downbeats_frames"]),
        "snapped_downbeat": match_peaks(reference["grid"]["downbeats_frames"], candidate["grid"]["downbeats_frames"]),
        "bpm_delta": abs(reference["grid"]["bpm"] - candidate["grid"]["bpm"]),
        "beat_pearson": float(np.corrcoef(reference_beat, candidate_beat)[0, 1]),
        "downbeat_pearson": float(np.corrcoef(reference_down, candidate_down)[0, 1]),
        "beat_argmax_delta_frames": abs(int(reference_beat.argmax()) - int(candidate_beat.argmax())),
        "downbeat_argmax_delta_frames": abs(int(reference_down.argmax()) - int(candidate_down.argmax())),
    }
    passed = (
        agreement["beat"]["f1"] >= THRESHOLDS["beat_f1_at_1_frame"]
        and agreement["snapped_downbeat"]["f1"] >= THRESHOLDS["snapped_downbeat_f1_at_1_frame"]
        and agreement["bpm_delta"] <= THRESHOLDS["bpm_delta_max"]
        and all(model["grid"]["bpm"] > 0 and model["grid"]["beat_count"] >= 8 and model["grid"]["downbeat_count"] >= 2
                for model in (reference, candidate))
    )
    result = {
        "host": socket.gethostname(), "ort_version": ort.__version__, "numpy_version": np.__version__,
        "fixture": {"path": str(args.wav), "sha256": sha256(args.wav), "bytes": args.wav.stat().st_size,
                    "duration_seconds": samples / rate, "rate": rate, "channels": channels},
        "frontend": {**metadata, "command": command, "mel_sha256": sha256(mel_path),
                     "source": "native/analyzer/resampler.cpp + mel_spectrogram.cpp"},
        "reference": reference, "candidate": candidate, "agreement": agreement, "thresholds": THRESHOLDS,
        "cpu_agreement_gate": "PASS" if passed else "FAIL", "htp_dispatch_gate": "NOT COLLECTED (phone forbidden)",
    }
    (args.output / "real-mel-agreement.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    report = [
        "# BitChord vc84 real-mel CPU agreement — CUE only", "",
        f"**CPU agreement gate: {'PASS' if passed else 'FAIL'}**. QNN/beat-candidate flags remain OFF.",
        "**HTP dispatch gate: NOT COLLECTED** (no phone/ADB/APK/ORT rebuild in this pass).", "",
        f"Host `{result['host']}`, Python ONNX Runtime `{ort.__version__}`, CPUExecutionProvider only.",
        f"Fixture `{args.wav.name}`: {samples / rate:.6f}s, {channels}ch/{rate}Hz, SHA-256 `{result['fixture']['sha256']}`.",
        f"Mel: actual BitChord native resampler + frontend, {metadata['frames']} × {metadata['mels']}, 50 fps; short candidate chunks zero-pad to 1500 and crop back.", "",
        "| Metric | Result | Predeclared gate |", "|---|---:|---:|",
        f"| Beat peak F1 @ ±1 frame (20ms) | {agreement['beat']['f1']:.6f} | ≥ 0.95 |",
        f"| Raw downbeat peak F1 @ ±1 frame | {agreement['raw_downbeat']['f1']:.6f} | diagnostic |",
        f"| Snapped downbeat grid F1 @ ±1 frame | {agreement['snapped_downbeat']['f1']:.6f} | ≥ 0.95 |",
        f"| BPM delta | {agreement['bpm_delta']:.6f} | ≤ 0.5 |", "",
        "| Model | Beat peaks | Downbeat grid | BPM | Median beats/bar |", "|---|---:|---:|---:|---:|",
    ]
    for model in (reference, candidate):
        g = model["grid"]
        report.append(f"| {Path(model['path']).name} | {g['beat_count']} | {g['downbeat_count']} | {g['bpm']:.6f} | {g['median_beats_per_downbeat']} |")
    report += ["", f"Beat/downbeat logit Pearson: {agreement['beat_pearson']:.6f} / {agreement['downbeat_pearson']:.6f}.",
               f"Full grids, matched counts, per-chunk CPU timings, model hashes and logits: `{args.output}`.", "",
               "This is behavioral agreement to vc83, not accuracy against annotated beats; one playback recording is not a general MIR benchmark.",
               "Nirovi is a different QDQ graph, not a quantization-only re-export of Beat This!; matching I/O alone is insufficient.", "",
               "Before enabling QNN: collect an actual fixed-shape inference with `session.disable_cpu_ep_fallback=1`,",
               "EPContext disabled, verbose ORT node-assignment evidence showing QNN/HTP compute (not CPU partitions),",
               "and vendor HTP ARCH V73 logcat. Session creation, bundled Skel presence and Orchard Step A are not BitChord proof."]
    args.report.write_text("\n".join(report) + "\n")
    print(json.dumps({"cpu_agreement_gate": result["cpu_agreement_gate"], "agreement": agreement,
                      "bpms": [reference["grid"]["bpm"], candidate["grid"]["bpm"]], "report": str(args.report)}, indent=2))
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
