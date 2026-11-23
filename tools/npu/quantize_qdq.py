#!/usr/bin/env python3
"""Static QDQ (w8a16) quantization of the fixed-shape Beat This! for the QNN HTP backend.

  python quantize_qdq.py --model build/beat_this_final0_fp32_500.onnx --calib build/calib_500.npz \
      --out ../../app/src/main/assets/beat_this_final0_qdq.onnx

uint16 activations / uint8 weights (QNN-recommended starting point). HTP MatMul supports
(u8,u8), (u8,u16), (u16,u8) but NOT (u16,u16), so for activation x activation MatMuls
(attention QK^T and PV) one operand is overridden to uint8: the Softmax output when it is
an operand (probabilities in [0,1] lose little at 8 bit), else input B.
"""
from __future__ import annotations

import argparse
import os

import numpy as np
import onnx
from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize
from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model


class NpzReader(CalibrationDataReader):
    def __init__(self, path: str, input_name: str):
        self.data = np.load(path)["mels"]
        self.name = input_name
        self.i = 0

    def get_next(self):
        if self.i >= len(self.data):
            return None
        x = self.data[self.i][None].astype(np.float32)
        self.i += 1
        return {self.name: x}

    def rewind(self):
        self.i = 0


def activation_matmul_overrides(model: onnx.ModelProto) -> dict:
    inits = {i.name for i in model.graph.initializer}
    producers = {o: n for n in model.graph.node for o in n.output}
    overrides = {}
    for n in model.graph.node:
        if n.op_type != "MatMul" or n.input[0] in inits or n.input[1] in inits:
            continue
        pick = n.input[1]
        for t in n.input:
            if t in producers and producers[t].op_type == "Softmax":
                pick = t
        overrides[pick] = [{"quant_type": QuantType.QUInt8}]
    return overrides


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--calib", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--per-channel", action="store_true", help="per-channel Conv/MatMul weights (try if parity fails)")
    ap.add_argument("--no-attn-u8", action="store_true")
    args = ap.parse_args()
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)

    pre = args.out.replace(".onnx", ".preproc.onnx")
    changed = qnn_preprocess_model(args.model, pre)  # fuses Erf-GELU -> Gelu, LayerNorm patterns
    src = pre if changed else args.model
    model = onnx.load(src)
    name = model.graph.input[0].name
    overrides = {} if args.no_attn_u8 else activation_matmul_overrides(model)
    print(f"preprocessed={changed}; activation-activation MatMul u8 overrides: {len(overrides)}")

    config = get_qnn_qdq_config(
        src,
        NpzReader(args.calib, name),
        activation_type=QuantType.QUInt16,
        weight_type=QuantType.QUInt8,
        per_channel=args.per_channel,
        init_overrides=overrides or None,
    )
    quantize(src, args.out, config)
    if changed and os.path.exists(pre):
        os.remove(pre)
    q = onnx.load(args.out)
    dyn = [n.op_type for n in q.graph.node if n.op_type in ("DynamicQuantizeLinear", "MatMulInteger", "DynamicQuantizeLSTM")]
    if dyn:
        raise SystemExit(f"dynamic quant ops present ({set(dyn)}): not HTP-runnable")
    print(f"QDQ model: {args.out} ({os.path.getsize(args.out)/1e6:.1f} MB, expected ~21-25 MB)")


if __name__ == "__main__":
    main()
