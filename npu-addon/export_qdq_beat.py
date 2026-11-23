#!/usr/bin/env python3
"""Export the BeatThis ONNX model to static QDQ INT8 and evaluate beat parity.

Host-side QDQ export for the vc92 NPU beat path (Wave 2 rewrite).

What this does
--------------
``quantize_static`` from ``onnxruntime.quantization`` converts an **FP32**
BeatThis ONNX checkpoint into a *static* QDQ (quantize-dequantize) model:

* activations are quantized to ``uint16`` (``ActivationType.QUInt16``),
* weights are quantized to ``uint8`` (``WeightType.QUInt8``), per channel,
* ``QuantFormat.QDQ`` wraps the real operators in ``QuantizeLinear`` /
  ``DequantizeLinear`` pairs so the QNN HTP backend runs its quantized
  kernels rather than the FP32 ones.

The full graph and every initializer are preserved: the quantizer rewrites the
existing graph in place. Nothing here rebuilds the graph by hand (the old toy
``_qdq_nodes`` stub is gone -- it produced a graph that could not run).

Fixed windows only
------------------
The export is shape-specialized at **6 s and 10 s** only -- ``300`` and ``500``
mel frames at 50 frames/s (``FRAME_RATE``). The 30 s variant collapsed the
downbeat F1 to ~32 %, so 30 s is rejected outright rather than quietly
produced. Static QDQ calibration needs concrete shapes, which is the other
reason the window is pinned.

FP32 checkpoint required
------------------------
Static QDQ calibration observes *float* activation ranges. The bundled
``beat_this_final0_q8.onnx`` is already **dynamic-INT8** (``DynamicQuantizeLinear``
/ ``MatMulInteger`` with quantized weights): there are no float activations to
calibrate and the graph already carries its own dynamic quantization. Running
``quantize_static`` on it cannot produce a meaningful QDQ model, so this script
detects that case up front, prints an explicit diagnostic, and exits non-zero
instead of writing silent garbage. Export / obtain the FP32 checkpoint
(``beat_this_final0.onnx``) and pass that instead.

Parity gate
-----------
The exported QDQ model and the FP32 baseline are run over the same
deterministic synthetic mel input. Beat/downbeat logit deltas and the beat-set
F1 are reported; the run fails (non-zero exit) when the F1 delta exceeds
``MAX_F1_DELTA`` (2.0 points).

Host-side only: no Android build, bundled ORT or QNN artifact is touched here.
"""

from __future__ import annotations

import argparse
import copy
import math
import os
import sys
import tempfile
from collections import Counter
from dataclasses import dataclass

import numpy as np
import onnx

FRAME_RATE = 50  # mel frames per second for the BeatThis windows.
SUPPORTED_SHAPES_SECONDS = (6.0, 10.0)  # fixed QDQ window shapes; never 30 s.
MAX_F1_DELTA = 2.0  # F1 points; parity fails beyond this.
DEFAULT_MELS = 128  # BeatThis mel bands (see MelSpectrogram.mels fallback).
DEFAULT_CALIBRATION_SAMPLES = 8  # deterministic synthetic calibration draws.
BEAT_TOLERANCE_SECONDS = 0.07  # ~3.5 frames at 50 fps, the usual beat tolerance.
MIN_BEAT_SPACING_FRAMES = 3  # peak-picking guard, in frames.

# Ops that only appear once a model has already been quantized dynamically.
DYNAMIC_INT8_OPS = (
    "DynamicQuantizeLinear",
    "MatMulInteger",
    "ConvInteger",
    "DynamicQuantizeLSTM",
    "DynamicQuantizeMatMul",
    "MatMulIntegerToFloat",
)
# Ops that carry float weights and are what a QDQ rewrite targets.
FLOAT_WEIGHT_OPS = ("MatMul", "Gemm", "Conv", "ConvTranspose", "Attention")

EXIT_OK = 0
EXIT_PARITY_FAILED = 1
EXIT_NOT_FP32 = 2


# --------------------------------------------------------------------------- #
# Shapes
# --------------------------------------------------------------------------- #


def frames_for(seconds: float) -> int:
    """Mel frames covered by ``seconds`` of audio at ``FRAME_RATE``."""
    return int(round(seconds * FRAME_RATE))


def _mel_bands(model: "onnx.ModelProto") -> int:
    """Mel bands per frame: the model's static last input dim, else ``DEFAULT_MELS``."""
    for value in model.graph.input:
        dims = value.type.tensor_type.shape.dim
        if len(dims) >= 2:
            last = dims[-1]
            if last.HasField("dim_value") and last.dim_value > 0:
                return int(last.dim_value)
    return DEFAULT_MELS


def _pin_input_shapes(model: "onnx.ModelProto", frames: int) -> "onnx.ModelProto":
    """Copy of ``model`` with the BeatThis input pinned to ``[1, frames, mels]``.

    Layout is the documented BeatThis contract: ``[batch, frames, mels]`` (see
    ``BeatTracker`` chunking and ``MelSpectrogram``). Only dynamic dimensions are
    pinned; an already-static dimension is left exactly as the checkpoint
    declared it. Non-float (index / metadata) inputs keep their dtype and get
    zeros at inference time.
    """
    pinned = copy.deepcopy(model)
    mels = _mel_bands(model)
    for value in pinned.graph.input:
        dims = value.type.tensor_type.shape.dim
        if not dims:
            continue
        for index, dim in enumerate(dims):
            if dim.HasField("dim_value") and dim.dim_value > 0:
                continue
            dim.ClearField("dim_param")
            if index == 0:
                dim.dim_value = 1
            elif index == len(dims) - 1:
                dim.dim_value = mels
            else:
                dim.dim_value = frames
    return pinned


def _input_signature(model: "onnx.ModelProto") -> list:
    """``[(name, dtype, shape)]`` for every graph input of a pinned model."""
    signature = []
    for value in model.graph.input:
        tensor_type = value.type.tensor_type
        shape = [int(dim.dim_value) if dim.HasField("dim_value") else 1 for dim in tensor_type.shape.dim]
        signature.append((value.name, int(tensor_type.elem_type), shape))
    return signature


# --------------------------------------------------------------------------- #
# Dynamic-INT8 guard
# --------------------------------------------------------------------------- #


def detect_dynamic_int8(model: "onnx.ModelProto") -> tuple:
    """``(is_dynamic_int8, detail)`` for an input checkpoint.

    Flags a model that already carries dynamic INT8 quantization: any
    ``DynamicQuantizeLinear`` / ``MatMulInteger``-family node, or a compute op
    whose weights are already 8-bit with no FP32 counterpart. Such a graph has
    no float activations for static QDQ calibration to observe.
    """
    op_counts = Counter(node.op_type for node in model.graph.node)
    present = [op for op in DYNAMIC_INT8_OPS if op_counts.get(op)]
    initializers = {init.name: init for init in model.graph.initializer}

    quantized_weight_ops = 0
    float_weight_ops = 0
    for node in model.graph.node:
        if node.op_type not in FLOAT_WEIGHT_OPS:
            continue
        weight = initializers.get(node.input[1]) if len(node.input) > 1 else None
        if weight is None:
            float_weight_ops += 1
            continue
        if weight.data_type in (onnx.TensorProto.INT8, onnx.TensorProto.UINT8):
            quantized_weight_ops += 1
        elif weight.data_type in (onnx.TensorProto.FLOAT, onnx.TensorProto.FLOAT16):
            float_weight_ops += 1

    detail = {
        "dynamic_ops": {op: op_counts[op] for op in present},
        "quantized_weight_ops": quantized_weight_ops,
        "float_weight_ops": float_weight_ops,
        "producer": "%s %s" % (model.producer_name or "unknown", model.producer_version or ""),
    }
    already_quantized = bool(present) or (quantized_weight_ops > 0 and float_weight_ops == 0)
    return already_quantized, detail


def _print_dynamic_int8_diagnostic(path: str, detail: dict) -> None:
    """Explain, loudly, why this checkpoint cannot be QDQ-calibrated."""
    print("=" * 78, file=sys.stderr)
    print("ERROR: %s is already a dynamic-INT8 model; static QDQ cannot be calibrated." % path, file=sys.stderr)
    print("=" * 78, file=sys.stderr)
    ops = detail["dynamic_ops"]
    if ops:
        print("  dynamic quantization ops found:", file=sys.stderr)
        for op, count in sorted(ops.items()):
            print("    %-24s x%d" % (op, count), file=sys.stderr)
    print(
        "  8-bit weight operands: %d   float weight operands: %d"
        % (detail["quantized_weight_ops"], detail["float_weight_ops"]),
        file=sys.stderr,
    )
    print("  producer: %s" % detail["producer"], file=sys.stderr)
    print("", file=sys.stderr)
    print("Why this is refused:", file=sys.stderr)
    print(
        "  quantize_static(QDQ) calibrates by observing *float* activation ranges and then\n"
        "  rewrites float ops into QuantizeLinear/DequantizeLinear pairs. A dynamic-INT8\n"
        "  graph has no float activations left to observe and already quantizes at run time\n"
        "  (DynamicQuantizeLinear / MatMulInteger), so calibrating it would either fail or\n"
        "  emit a double-quantized graph whose logits mean nothing. This script exits\n"
        "  non-zero rather than write that artifact.",
        file=sys.stderr,
    )
    print("", file=sys.stderr)
    print("What to do:", file=sys.stderr)
    print(
        "  Export or obtain the FP32 BeatThis checkpoint (beat_this_final0.onnx) and pass\n"
        "  it with --model. QDQ is the *replacement* for dynamic INT8 on the HTP backend,\n"
        "  not a transform applied on top of it.",
        file=sys.stderr,
    )
    print("", file=sys.stderr)


# --------------------------------------------------------------------------- #
# Synthetic calibration / evaluation data
# --------------------------------------------------------------------------- #


class SyntheticMelCalibrationReader:
    """Deterministic, non-zero, mel-like calibration feed for ``quantize_static``.

    Every sample is drawn from a fixed LCG so the export is reproducible on any
    host. Values sit in the log-mel magnitude range the model was trained on
    (``log1p(1000 * magnitude)`` lands around 0..7), with a per-band loudness
    tilt and per-frame envelope so the observed activation ranges span what real
    audio produces. All-zero data is never used: it collapses every activation
    range and hides the quantization error the parity gate is meant to catch.

    Duck-typed against ``onnxruntime.quantization.CalibrationDataReader`` so the
    module imports cleanly without onnxruntime installed (the host CI check is
    ``py_compile``).
    """

    def __init__(self, signature: list, samples: int = DEFAULT_CALIBRATION_SAMPLES, seed: int = 0x5EED):
        self._signature = signature
        self._samples = max(1, int(samples))
        self._seed = int(seed)
        self._index = 0

    def get_next(self):
        if self._index >= self._samples:
            return None
        feed = _synthetic_feed(self._signature, self._seed + self._index * 7919)
        self._index += 1
        return feed

    def rewind(self):
        self._index = 0


def _synthetic_feed(signature: list, seed: int) -> dict:
    """One deterministic feed: mel-like floats for float inputs, zeros otherwise."""
    rng = np.random.default_rng(seed)
    feed = {}
    for name, elem_type, shape in signature:
        dims = [int(dim) if dim and dim > 0 else 1 for dim in shape]
        if elem_type == onnx.TensorProto.FLOAT:
            feed[name] = _mel_like(rng, dims)
        elif elem_type == onnx.TensorProto.DOUBLE:
            feed[name] = _mel_like(rng, dims).astype(np.float64)
        elif elem_type in (onnx.TensorProto.INT64, onnx.TensorProto.INT32):
            feed[name] = np.zeros(dims, dtype=np.int64 if elem_type == onnx.TensorProto.INT64 else np.int32)
        else:
            feed[name] = np.zeros(dims, dtype=np.float32)
    return feed


def _mel_like(rng, dims: list) -> np.ndarray:
    """Non-zero log-mel-like tensor: band tilt + frame envelope + noise, clipped to 0..7."""
    base = rng.random(dims, dtype=np.float32) * 2.2 + 0.15
    if len(dims) >= 2:
        bands = dims[-1]
        tilt = np.linspace(0.35, 1.6, bands, dtype=np.float32)
        base = base * tilt.reshape((1,) * (len(dims) - 1) + (bands,))
        frames = dims[-2]
        envelope = 0.55 + 0.45 * np.abs(
            np.sin(np.linspace(0.0, math.pi * 3.0, frames, dtype=np.float32))
        )
        base = base * envelope.reshape((1,) * (len(dims) - 2) + (frames, 1))
    return np.clip(base, 0.0, 7.0).astype(np.float32)


# --------------------------------------------------------------------------- #
# Quantization
# --------------------------------------------------------------------------- #


def _quantization_api():
    """Import the quantizer lazily and return the pieces used here."""
    try:
        from onnxruntime.quantization import (  # noqa: WPS433 (lazy by design)
            CalibrationMethod,
            QuantFormat,
            QuantType,
            quantize_static,
        )
        import onnxruntime.quantization as quantization
    except ImportError as error:  # pragma: no cover - host env without ORT
        raise SystemExit(
            "onnxruntime.quantization is required for the QDQ export "
            "(pip install onnxruntime onnx numpy): %s" % error
        )
    # Older releases expose the activation enum as ActivationType; newer ones
    # use QuantType for both. Prefer the explicit name when it exists.
    activation_type = getattr(quantization, "ActivationType", QuantType)
    return CalibrationMethod, QuantFormat, QuantType, quantize_static, activation_type


def export_static_qdq(model: "onnx.ModelProto", seconds: float, samples: int, seed: int) -> bytes:
    """Quantize ``model`` to static QDQ at ``seconds`` and return the serialized bytes."""
    CalibrationMethod, QuantFormat, QuantType, quantize_static, activation_type = _quantization_api()
    frames = frames_for(seconds)
    pinned = _pin_input_shapes(model, frames)
    pinned = onnx.shape_inference.infer_shapes(pinned)
    signature = _input_signature(pinned)
    reader = SyntheticMelCalibrationReader(signature, samples=samples, seed=seed)

    with tempfile.TemporaryDirectory(prefix="bitchord-qdq-") as workdir:
        source = os.path.join(workdir, "fp32_%ds.onnx" % int(seconds))
        target = os.path.join(workdir, "qdq_%ds.onnx" % int(seconds))
        onnx.save_model(pinned, source)
        quantize_static(
            model_input=source,
            model_output=target,
            calibration_data_reader=reader,
            quant_format=QuantFormat.QDQ,
            activation_type=activation_type.QUInt16,
            weight_type=QuantType.QUInt8,
            per_channel=True,
            reduce_range=False,
            calibrate_method=CalibrationMethod.MinMax,
            extra_options={
                "ActivationSymmetric": False,
                "WeightSymmetric": True,
                "DedicatedQDQPair": False,
                "QDQOpTypePerChannelSupportToAxis": {"MatMul": 0},
            },
        )
        with open(target, "rb") as handle:
            return handle.read()


def _qdq_node_counts(model_bytes: bytes) -> Counter:
    quantized = onnx.load_model_from_string(model_bytes)
    return Counter(node.op_type for node in quantized.graph.node)


# --------------------------------------------------------------------------- #
# Parity evaluation
# --------------------------------------------------------------------------- #


@dataclass
class ParityResult:
    seconds: float
    frames: int
    f1_beat: float
    f1_downbeat: float
    delta_points: float
    max_beat_logit_delta: float
    max_downbeat_logit_delta: float
    beats_fp32: int
    beats_qdq: int

    @property
    def passed(self) -> bool:
        return self.delta_points <= MAX_F1_DELTA


def _session(model_bytes: bytes):
    import onnxruntime as ort

    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    return ort.InferenceSession(model_bytes, sess_options=options, providers=["CPUExecutionProvider"])


def _run_outputs(session, signature: list, seed: int) -> dict:
    """Run the session on one deterministic synthetic feed, keyed by output name."""
    feed = _synthetic_feed(signature, seed)
    names = [output.name for output in session.get_outputs()]
    values = session.run(names, feed)
    return {name: np.asarray(value, dtype=np.float32) for name, value in zip(names, values)}


def _pick_channel(outputs: dict, index: int, hint: str) -> np.ndarray:
    """Flat float vector for the beat (hint ``beat``) or downbeat output.

    Prefers an output whose name carries the hint -- ``beat`` must not match
    ``downbeat`` -- and falls back to positional output order.
    """
    for name, value in outputs.items():
        lowered = name.lower()
        if hint not in lowered:
            continue
        if hint == "beat" and "downbeat" in lowered:
            continue
        return value.reshape(-1)
    ordered = list(outputs.values())
    if index < len(ordered):
        return ordered[index].reshape(-1)
    raise SystemExit("model has no output for %r (outputs: %s)" % (hint, sorted(outputs)))


def _beats_from_logits(logits: np.ndarray) -> list:
    """Beat times (seconds) from local-maximum peaks above zero, with a spacing guard."""
    values = np.asarray(logits, dtype=np.float32).reshape(-1)
    if values.size == 0:
        return []
    threshold = max(0.0, float(values.mean() + 0.5 * values.std()))
    beats = []
    last_frame = -MIN_BEAT_SPACING_FRAMES - 1
    for index in range(values.size):
        value = float(values[index])
        left = float(values[index - 1]) if index > 0 else -math.inf
        right = float(values[index + 1]) if index + 1 < values.size else -math.inf
        if value <= threshold or value < left or value < right:
            continue
        if index - last_frame < MIN_BEAT_SPACING_FRAMES:
            continue
        beats.append(index / FRAME_RATE)
        last_frame = index
    return beats


def _f1(reference: list, predicted: list, tolerance: float = BEAT_TOLERANCE_SECONDS) -> float:
    """Greedy one-to-one F1 between two beat sets within ``tolerance`` seconds."""
    if not reference or not predicted:
        return 0.0
    ref = sorted(reference)
    pred = sorted(predicted)
    used = [False] * len(pred)
    matches = 0
    for target in ref:
        best_index = -1
        best_delta = tolerance
        for index, candidate in enumerate(pred):
            if used[index]:
                continue
            delta = abs(candidate - target)
            if delta <= best_delta:
                best_delta = delta
                best_index = index
        if best_index >= 0:
            used[best_index] = True
            matches += 1
    if matches == 0:
        return 0.0
    precision = matches / len(pred)
    recall = matches / len(ref)
    return float(2.0 * precision * recall / (precision + recall))


def _max_abs_delta(a: np.ndarray, b: np.ndarray) -> float:
    if a.shape != b.shape:
        return math.inf
    return float(np.max(np.abs(a - b))) if a.size else 0.0


def evaluate_parity(fp32_bytes: bytes, qdq_bytes: bytes, seconds: float, seed: int) -> ParityResult:
    """Compare the QDQ export against the FP32 baseline: logits, F1, delta in points."""
    fp32_model = onnx.load_model_from_string(fp32_bytes)
    signature = _input_signature(fp32_model)
    fp32_outputs = _run_outputs(_session(fp32_bytes), signature, seed)
    qdq_outputs = _run_outputs(_session(qdq_bytes), signature, seed)

    beat_fp32 = _pick_channel(fp32_outputs, 0, "beat")
    beat_qdq = _pick_channel(qdq_outputs, 0, "beat")
    down_fp32 = _pick_channel(fp32_outputs, 1, "downbeat")
    down_qdq = _pick_channel(qdq_outputs, 1, "downbeat")

    beats_fp32 = _beats_from_logits(beat_fp32)
    beats_qdq = _beats_from_logits(beat_qdq)
    downs_fp32 = _beats_from_logits(down_fp32)
    downs_qdq = _beats_from_logits(down_qdq)

    f1_beat = _f1(beats_fp32, beats_qdq)
    f1_downbeat = _f1(downs_fp32, downs_qdq)
    # Points are the *worst* channel's shortfall from perfect parity, on a 0..100
    # scale, so the 2.0-point budget is meaningful (a 0..1 fraction never was).
    delta_points = (1.0 - min(f1_beat, f1_downbeat)) * 100.0

    return ParityResult(
        seconds=seconds,
        frames=frames_for(seconds),
        f1_beat=f1_beat,
        f1_downbeat=f1_downbeat,
        delta_points=delta_points,
        max_beat_logit_delta=_max_abs_delta(beat_fp32, beat_qdq),
        max_downbeat_logit_delta=_max_abs_delta(down_fp32, down_qdq),
        beats_fp32=len(beats_fp32),
        beats_qdq=len(beats_qdq),
    )


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #


def _output_path(base: str, seconds: float, single: bool) -> str:
    """Artifact path: exact for one shape, ``<stem>-<n>s.qdq.onnx`` for many."""
    if single:
        return base
    root, extension = os.path.splitext(base)
    if not extension:
        extension = ".onnx"
    return "%s-%ds%s" % (root, int(seconds), extension)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Export an FP32 BeatThis ONNX checkpoint to static QDQ INT8 and check beat parity."
    )
    parser.add_argument("--model", required=True, help="FP32 BeatThis ONNX checkpoint (not the dynamic-INT8 build)")
    parser.add_argument(
        "--output",
        default=None,
        help="write the exported .qdq.onnx artifact here (one shape) or use it as a stem (several shapes)",
    )
    parser.add_argument(
        "--shape-seconds",
        type=float,
        action="append",
        default=None,
        help="QDQ window length in seconds; only 6 and 10 are supported",
    )
    parser.add_argument(
        "--calibration-samples",
        type=int,
        default=DEFAULT_CALIBRATION_SAMPLES,
        help="deterministic synthetic calibration draws (default %d)" % DEFAULT_CALIBRATION_SAMPLES,
    )
    parser.add_argument("--seed", type=int, default=0x5EED, help="calibration/evaluation seed")
    args = parser.parse_args(argv)

    seconds_list = list(args.shape_seconds) if args.shape_seconds else list(SUPPORTED_SHAPES_SECONDS)
    for seconds in seconds_list:
        if float(seconds) not in SUPPORTED_SHAPES_SECONDS:
            parser.error(
                "unsupported window %.1fs: only %s are supported (30 s collapsed downbeat F1 to ~32%%)"
                % (seconds, ", ".join("%.0fs" % value for value in SUPPORTED_SHAPES_SECONDS))
            )

    model = onnx.load(args.model)
    already_quantized, detail = detect_dynamic_int8(model)
    if already_quantized:
        _print_dynamic_int8_diagnostic(args.model, detail)
        return EXIT_NOT_FP32

    if not model.graph.node:
        print("ERROR: %s has an empty graph" % args.model, file=sys.stderr)
        return EXIT_NOT_FP32

    single = len(seconds_list) == 1
    failures = 0
    for seconds in seconds_list:
        frames = frames_for(seconds)
        print("== QDQ export %s at %.0fs (%d mel frames)" % (os.path.basename(args.model), seconds, frames))
        qdq_bytes = export_static_qdq(model, seconds, args.calibration_samples, args.seed)
        counts = _qdq_node_counts(qdq_bytes)
        print(
            "   graph preserved: nodes=%d QuantizeLinear=%d DequantizeLinear=%d"
            % (sum(counts.values()), counts.get("QuantizeLinear", 0), counts.get("DequantizeLinear", 0))
        )
        if not counts.get("QuantizeLinear") or not counts.get("DequantizeLinear"):
            print("FAIL no QDQ pairs were inserted; the export is not a QDQ model", file=sys.stderr)
            failures += 1
            continue

        fp32_bytes = _fp32_bytes(model, seconds)
        result = evaluate_parity(fp32_bytes, qdq_bytes, seconds, args.seed)
        print(
            "   parity f1_beat=%.4f f1_downbeat=%.4f delta=%.2f points "
            "maxBeatLogitDelta=%.6f maxDownbeatLogitDelta=%.6f beats=%d/%d"
            % (
                result.f1_beat,
                result.f1_downbeat,
                result.delta_points,
                result.max_beat_logit_delta,
                result.max_downbeat_logit_delta,
                result.beats_fp32,
                result.beats_qdq,
            )
        )
        if not result.passed:
            print(
                "FAIL parity at %.0fs: F1 delta %.2f points exceeds the %.1f point budget"
                % (seconds, result.delta_points, MAX_F1_DELTA),
                file=sys.stderr,
            )
            failures += 1
            continue

        if args.output:
            path = _output_path(args.output, seconds, single)
            directory = os.path.dirname(os.path.abspath(path))
            if directory:
                os.makedirs(directory, exist_ok=True)
            with open(path, "wb") as handle:
                handle.write(qdq_bytes)
            print("   wrote %s (%d bytes)" % (path, len(qdq_bytes)))
        else:
            print("   (no --output; artifact not written)")

    return EXIT_PARITY_FAILED if failures else EXIT_OK


def _fp32_bytes(model: "onnx.ModelProto", seconds: float) -> bytes:
    """Serialized fixed-shape FP32 baseline for the parity run."""
    pinned = _pin_input_shapes(model, frames_for(seconds))
    pinned = onnx.shape_inference.infer_shapes(pinned)
    return pinned.SerializeToString()


if __name__ == "__main__":
    raise SystemExit(main())
