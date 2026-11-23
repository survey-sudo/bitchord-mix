#!/usr/bin/env python3
"""Export Beat This! final0 to a FIXED-shape FP32 ONNX for the QNN HTP path.

Input  spect   float32 [1, FRAMES, 128]   (log-mel from mel.py / mel_spectrogram.cpp)
Output beat    float32 [1, FRAMES]         logits
       downbeat float32 [1, FRAMES]        logits   (same order BeatTracker reads: 0 = beat, 1 = downbeat)

HTP graphs are static, so the time dimension is pinned. Default 500 frames (10 s): vc91's
NOTES report the 1500-frame (30 s) QDQ variant collapsed downbeat F1, so 10 s / 6 s are the
supported windows; --frames 1500 is available for evaluation only. BeatTracker must feed
exactly FRAMES-frame chunks on this path (orchestrator hook H2 in work/A/NOTES.md).

  python export_beat_this.py --frames 500 --out build/beat_this_final0_fp32_500.onnx
"""
from __future__ import annotations

import argparse
import os

import numpy as np
import onnx
import torch

from einsum_to_matmul import rewrite


class Wrapper(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, spect):
        out = self.model(spect)
        return out["beat"], out["downbeat"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", default="final0")
    ap.add_argument("--frames", type=int, default=500)
    ap.add_argument("--opset", type=int, default=17)
    ap.add_argument("--out", default="build/beat_this_final0_fp32_500.onnx")
    args = ap.parse_args()
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)

    from beat_this.inference import load_model

    model = load_model(args.checkpoint, device="cpu").eval()
    wrapper = Wrapper(model).eval()
    x = torch.from_numpy(np.random.default_rng(0).uniform(0, 6, (1, args.frames, 128)).astype(np.float32))
    with torch.no_grad():
        ref_beat, ref_down = wrapper(x)
        torch.onnx.export(
            wrapper, (x,), args.out, input_names=["spect"], output_names=["beat", "downbeat"],
            opset_version=args.opset, do_constant_folding=True, dynamic_axes=None, dynamo=False,
        )

    m = onnx.load(args.out)
    m, n, skipped = rewrite(m)
    m = onnx.shape_inference.infer_shapes(m)
    onnx.checker.check_model(m)
    onnx.save(m, args.out)
    ops = sorted({node.op_type for node in m.graph.node})
    print(f"einsum rewritten: {n}; left: {skipped}")
    print("ops:", ", ".join(ops))
    for bad in ("Einsum", "LSTM", "GRU", "Loop", "If", "STFT"):
        if bad in ops:
            print(f"WARNING: {bad} present: HTP cannot run it (strict QNN check will fail)")

    import onnxruntime as ort

    sess = ort.InferenceSession(args.out, providers=["CPUExecutionProvider"])
    beat, down = sess.run(None, {"spect": x.numpy()})
    err = max(np.abs(beat - ref_beat.numpy()).max(), np.abs(down - ref_down.numpy()).max())
    print(f"onnx vs torch max abs logit diff: {err:.2e}  ({os.path.getsize(args.out)/1e6:.1f} MB)")
    if err > 1e-3:
        raise SystemExit("export parity failed")


if __name__ == "__main__":
    main()
