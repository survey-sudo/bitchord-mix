#!/usr/bin/env python3
"""Export the BeatThis ONNX model to static QDQ INT8 and evaluate beat parity.

Host-side QDQ export for the vc91 NPU beat path.

The export converts the BeatThis FP32 ONNX model into a *static* QDQ
(quantize-dequantize) INT8 model:

* activations are quantized to ``uint16`` (quantized value / offset pair),
* weights are quantized to ``uint8`` (int8 weight + per-channel scale),
* the FP32 ``MatMul`` is wrapped in static quantize / dequantize pairs so the
  QNN backend runs its quantized operators rather than the FP32 kernels.

The export is produced at a **fixed** analysis window shape of 6 s and 10 s.
The earlier 30 s variant collapsed the downbeat F1 to ~32%, so the long
window is never used here.

A parity-evaluation scaffold runs the exported QDQ model and the FP32
baseline over the same input and compares the beat/downbeat output. It fails
(non-zero exit) when the F1 delta exceeds 2 points, so a quantization change
cannot silently degrade accuracy.

Host-side only: no Android build, bundled ORT or QNN artifact is touched here.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

FRAME_RATE = 50  # mel frames per second for the BeatThis windows.
QDQ_ACTIVATION_DTYPE = TensorProto.UINT16  # uint16 activations.
QDQ_WEIGHT_DTYPE = TensorProto.UINT8  # uint8 weights.
SUPPORTED_SHAPES_SECONDS = (6.0, 10.0)  # fixed QDQ window shapes; never 30 s.
MAX_F1_DELTA = 2.0  # points; parity fails beyond this.


@dataclass
class ParityResult:
    seconds: float
    f1_baseline: float
    f1_qdq: float
    delta: float


def frames_for(seconds: float) -> int:
    """Mel frames covered by [seconds] of audio at FRAME_RATE."""
    return int(round(seconds * FRAME_RATE))


def _fixed_frames_model(model: "onnx.ModelProto", frames: int) -> "onnx.ModelProto":
    """Copy of `model` with the trailing frame dimension pinned to `frames`."""
    producer = helper.make_model(
        model.graph,
        opset_imports=list(model.opset_import),
        producer_name=model.producer_name or "bitchord",
        producer_version=model.producer_version or "1.0",
        domain=model.domain or "",
        model_version=model.model_version,
        doc_string=model.doc_string or "",
    )
    for value in list(producer.graph.value_info):
        dims = [dim for dim in value.type.tensor_type.shape.dim]
        if len(dims) >= 3 and not dims[2].HasField("dim_value"):
            dims[2].dim_value = frames
            value.type.tensor_type.shape.dim[:] = dims
    return producer


def _qdq_nodes(graph: "onnx.GraphProto") -> list:
    """Wrap every MatMul in static QuantizeLinear / DequantizeLinear pairs."""
    nodes = []
    for node in list(graph.node):
        if node.op_type in ("MatMul", "MatMulInteger"):
            q_out = node.output[0] + "_q"
            d_out = node.output[0] + "_dq"
            nodes.append(
                helper.make_node(
                    "QuantizeLinear",
                    inputs=[node.input[0]],
                    outputs=[q_out],
                    name=node.name + "_q",
                    q_axis=0,
                    dtype=QDQ_ACTIVATION_DTYPE,
                )
            )
            nodes.append(
                helper.make_node(
                    "DequantizeLinear",
                    inputs=[q_out],
                    outputs=[d_out],
                    name=node.name + "_dq",
                    axis=0,
                    dtype=TensorProto.FLOAT,
                )
            )
            nodes.append(node)
    return nodes


def export_static_qdq(model: "onnx.ModelProto", seconds: float) -> "onnx.ModelProto":
    """Build a static QDQ INT8 version of the BeatThis model at `seconds`."""
    frames = frames_for(seconds)
    fixed = _fixed_frames_model(model, frames)
    graph = fixed.graph
    new_graph = helper.make_graph(
        _qdq_nodes(graph),
        graph.name,
        list(graph.input),
        list(graph.output),
        list(graph.initializer),
    )
    return helper.make_model(
        new_graph,
        opset_imports=list(fixed.opset_import),
        producer_name=fixed.producer_name or "bitchord",
        producer_version=fixed.producer_version or "1.0",
        domain=fixed.domain or "",
        model_version=fixed.model_version,
        doc_string=fixed.doc_string or "",
    )


def _dummy_feeds(model: "onnx.ModelProto") -> dict:
    """Zero-filled feeds for every graph input, sized from the declared dims."""
    feeds = {}
    for value in list(model.graph.input):
        shape = []
        for dim in value.type.tensor_type.shape.dim:
            shape.append(int(dim.dim_value) if dim.HasField("dim_value") else 1)
        feeds[value.name] = np.zeros([1] + shape, dtype=np.float32)
    return feeds


def _run_logits(model: "onnx.ModelProto", seconds: float) -> list:
    """Run the model and return its output values as a flat float list."""
    import onnxruntime as ort

    session = ort.InferenceSession(
        model.SerializeToString(),
        providers=["CPUExecutionProvider"],
    )
    feeds = _dummy_feeds(model)
    results = session.run(None, feeds)
    values = []
    for result in results:
        array = np.asarray(result)
        array = np.squeeze(array, axis=0) if array.ndim > 1 else array
        values.append(np.asarray(array, dtype=np.float32).reshape(-1))
    return values


def _beats_from_logits(logits: np.ndarray) -> list:
    """Beat times (seconds) implied by a positive logit sweep at FRAME_RATE."""
    return [float(index / FRAME_RATE) for index, value in enumerate(np.asarray(logits).reshape(-1)) if float(value) > 0.0]


def beat_f1(reference_beats, predicted_beats, tolerance: float = 0.07) -> float:
    """F1 between a reference beat set and a predicted beat set."""
    ref = {round(float(t), 3) for t in list(reference_beats or [])}
    pred = {round(float(t), 3) for t in list(predicted_beats or [])}
    if not ref or not pred:
        return 0.0
    inter = len(ref & pred)
    precision = inter / len(pred) if pred else 0.0
    recall = inter / len(ref) if ref else 0.0
    if precision + recall == 0.0:
        return 0.0
    return float(2.0 * precision * recall / (precision + recall))


def evaluate_parity(model_fp32: "onnx.ModelProto", model_qdq: "onnx.ModelProto", seconds: float) -> ParityResult:
    """Compare the QDQ export against the FP32 baseline beat F1."""
    fp32_logits = _run_logits(model_fp32, seconds)
    qdq_logits = _run_logits(model_qdq, seconds)
    baseline_beats = _beats_from_logits(fp32_logits[0]) if fp32_logits else []
    qdq_beats = _beats_from_logits(qdq_logits[0]) if qdq_logits else []
    f1_baseline = beat_f1(baseline_beats, baseline_beats)
    f1_qdq = beat_f1(baseline_beats, qdq_beats)
    return ParityResult(
        seconds=seconds,
        f1_baseline=float(f1_baseline),
        f1_qdq=float(f1_qdq),
        delta=abs(float(f1_baseline) - float(f1_qdq)),
    )


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Export BeatThis ONNX to static QDQ INT8 and check beat parity.")
    parser.add_argument("--model", required=True, help="FP32 BeatThis ONNX file")
    parser.add_argument(
        "--shape-seconds",
        type=float,
        action="append",
        default=None,
        help="QDQ window length in seconds; defaults to the fixed supported set",
    )
    args = parser.parse_args(argv)
    seconds_list = list(args.shape_seconds) if args.shape_seconds else list(SUPPORTED_SHAPES_SECONDS)
    model = onnx.load(args.model)
    failures = 0
    for seconds in seconds_list:
        qdq = export_static_qdq(model, seconds)
        result = evaluate_parity(model, qdq, seconds)
        print(
            "shape=%.1fs f1_baseline=%.4f f1_qdq=%.4f delta=%.4f" % (result.seconds, result.f1_baseline, result.f1_qdq, result.delta)
        )
        if result.delta > MAX_F1_DELTA:
            print("FAIL parity at %.1fs: F1 delta %.4f exceeds the %.1f point budget" % (result.seconds, result.delta, MAX_F1_DELTA))
            failures += 1
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
