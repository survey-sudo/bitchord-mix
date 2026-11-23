#!/usr/bin/env python3
"""CPU dynamic-int8 variant of an exported stem graph, with a quality gate.

ONNX Runtime dynamic quantization: int8 weights (per channel) and int8 activations quantized at
run time, i.e. real MatMulInteger / ConvInteger / DynamicQuantizeLSTM kernels -- the format of
the vocals_umxhq_int8.onnx the app already ships. ConvTranspose stays float (no integer kernel),
and --keep-float keeps the most sensitive tail of the network float: audiojs measured that
quantizing SCNet's last decoder layers alone moves the output 27-39 dB under its power.

The gate compares int8 against fp32 on music-like input built by stft_reference.py and fails
(exit 1) under --min-snr dB, so a degraded export can never be pinned into StemModels.

NPU (QNN HTP) builds are NOT made here: hand the fp32 graph and calibration tensors
(make_calibration.py) to Stream A's tools/npu, which produces `*_qdq.onnx`.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import stft_reference as ref  # noqa: E402

DEFAULT_KEEP_FLOAT = {
    "scnet": r"decoder\.2|decoder/decoder\.2",
    "umx": r"$^",  # nothing: matches the existing vocals_umxhq_int8 recipe
}


def probe_input(layout: str) -> dict:
    rng = np.random.default_rng(99)
    if layout == "scnet":
        t = np.arange(ref.SCNET_SEGMENT) / ref.SAMPLE_RATE
        left = 0.3 * np.sin(2 * np.pi * 98 * t) * (1 + 0.5 * np.sin(2 * np.pi * 2 * t)) + 0.08 * rng.standard_normal(t.size)
        right = 0.3 * np.sin(2 * np.pi * 147 * t + 0.3) + 0.08 * rng.standard_normal(t.size)
        left, right, _, _ = ref.normalize(left, right)
        return {"mix_spec": ref.scnet_input(left, right)}
    t = np.arange(ref.segment_samples(ref.UMX_FRAMES)) / ref.SAMPLE_RATE
    left = 0.3 * np.sin(2 * np.pi * 196 * t) + 0.05 * rng.standard_normal(t.size)
    right = 0.3 * np.sin(2 * np.pi * 294 * t) + 0.05 * rng.standard_normal(t.size)
    return {"mix_magnitude": ref.umx_input(left, right)}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True, help="fp32 ONNX from export_scnet.py / export_umx.py")
    ap.add_argument("--out", required=True)
    ap.add_argument("--layout", required=True, choices=("scnet", "umx"))
    ap.add_argument("--keep-float", default=None, help="regex of node names left in float")
    ap.add_argument("--min-snr", type=float, default=30.0)
    args = ap.parse_args()

    import onnx
    import onnxruntime as ort
    from onnxruntime.quantization import QuantType, quantize_dynamic

    pattern = re.compile(args.keep_float if args.keep_float is not None else DEFAULT_KEEP_FLOAT[args.layout])
    graph = onnx.load(args.model)
    excluded = [n.name for n in graph.graph.node if n.name and pattern.search(n.name)]
    print(f"keeping {len(excluded)} node(s) float")

    quantize_dynamic(
        args.model,
        args.out,
        weight_type=QuantType.QInt8,
        per_channel=True,
        reduce_range=False,
        op_types_to_quantize=["MatMul", "Gemm", "Conv", "LSTM"],
        nodes_to_exclude=excluded,
    )

    feeds = probe_input(args.layout)
    fp32 = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"]).run(None, feeds)[0]
    int8 = ort.InferenceSession(args.out, providers=["CPUExecutionProvider"]).run(None, feeds)[0]
    noise = float(np.sum((fp32 - int8) ** 2))
    snr = float("inf") if noise == 0 else 10 * np.log10(float(np.sum(fp32 ** 2)) / noise)
    print(f"int8 vs fp32 output SNR {snr:.1f} dB (gate {args.min_snr} dB)")
    if snr < args.min_snr:
        print("FAIL: quantized graph too far from fp32; widen --keep-float")
        os.remove(args.out)
        return 1
    digest = hashlib.sha256(open(args.out, "rb").read()).hexdigest()
    print(f"OK {args.out} {os.path.getsize(args.out)} bytes sha256 {digest}")
    print("Pin it: StemModels.<spec>.files[..].sha256 / bytes in StemModelStore.kt, and MODELS.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
