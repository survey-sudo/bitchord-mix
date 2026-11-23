#!/usr/bin/env python3
"""Export one Open-Unmix UMX-HQ target (MIT weights, Zenodo record 3370489) to static-shape ONNX
with the STFT/iSTFT outside the graph -- the same contract as the vocals graph the APK ships
(app/src/main/assets/vocals_umxhq_int8.onnx):

    input  mix_magnitude    float32 [1, 2, 2049, 960]  |STFT|, periodic Hann, unnormalized, centred
    output target_magnitude float32 [1, 2, 2049, 960]  the target's magnitude estimate

The phone applies the estimates as ratio masks over the mixture phase plus one Wiener EM
iteration (open-unmix's niter=1), in native/analyzer/stem_dsp.cpp.

UMX-L is refused on purpose: its weights are CC BY-NC-SA 4.0, incompatible with shipping.

    pip install -r tools/models/requirements.txt
    for t in drums bass other; do
      python tools/models/export_umx.py --target $t --out build/stems
      python tools/models/quantize.py --model build/stems/${t}_umxhq_fp32.onnx \
          --out build/stems/${t}_umxhq_int8.onnx --layout umx
    done
"""
from __future__ import annotations

import argparse
import hashlib
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import stft_reference as ref  # noqa: E402

TARGETS = ("vocals", "drums", "bass", "other")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", required=True, choices=TARGETS)
    ap.add_argument("--model", default="umxhq", help="only umxhq (MIT) is accepted")
    ap.add_argument("--frames", type=int, default=ref.UMX_FRAMES)
    ap.add_argument("--out", required=True)
    ap.add_argument("--opset", type=int, default=17)
    args = ap.parse_args()
    if args.model != "umxhq":
        print("refusing: only umxhq weights are MIT; umxl is CC BY-NC-SA 4.0")
        return 2

    import openunmix
    import torch

    separator = openunmix.umxhq(targets=[args.target], device="cpu", niter=0)
    network = separator.target_models[args.target].eval()

    class Target(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, mix_magnitude):
            return self.m(mix_magnitude)

    wrapped = Target(network).eval()
    rng = np.random.default_rng(7)
    t = np.arange(ref.segment_samples(args.frames)) / ref.SAMPLE_RATE
    left = 0.3 * np.sin(2 * np.pi * 220 * t) + 0.05 * rng.standard_normal(t.size)
    right = 0.3 * np.sin(2 * np.pi * 330 * t) + 0.05 * rng.standard_normal(t.size)
    mag = ref.umx_input(left, right, args.frames)

    os.makedirs(args.out, exist_ok=True)
    out_path = os.path.join(args.out, f"{args.target}_umxhq_fp32.onnx")
    dummy = torch.from_numpy(mag)
    kwargs = dict(input_names=["mix_magnitude"], output_names=["target_magnitude"],
                  opset_version=args.opset, do_constant_folding=True)
    try:
        torch.onnx.export(wrapped, (dummy,), out_path, dynamo=False, **kwargs)
    except TypeError:
        torch.onnx.export(wrapped, (dummy,), out_path, **kwargs)

    import onnxruntime as ort

    session = ort.InferenceSession(out_path, providers=["CPUExecutionProvider"])
    got = session.run(None, {"mix_magnitude": mag})[0]
    with torch.no_grad():
        want = wrapped(dummy).numpy()
    err = np.abs(got - want).max() / max(np.abs(want).max(), 1e-12)
    print(f"onnxruntime vs torch: max relative diff {err:.2e}, shape {got.shape}")
    if got.shape != mag.shape or err > 1e-4:
        print("FAIL")
        return 1
    digest = hashlib.sha256(open(out_path, "rb").read()).hexdigest()
    print(f"OK {out_path} {os.path.getsize(out_path)} bytes sha256 {digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
