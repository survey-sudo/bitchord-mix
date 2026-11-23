#!/usr/bin/env python3
"""Calibration tensors for Stream A's static (QDQ) quantization of the stem graphs.

Writes model-input tensors exactly as the phone builds them (stft_reference.py == stem_dsp.cpp):

    --layout scnet : calib_XXXX.npy  float32 [1, 4, 2049, 476]  input name "mix_spec"
                     11 s chunks (485100 samples + 1300 zeros) of region-normalized audio
    --layout umx   : calib_XXXX.npy  float32 [1, 2, 2049, 960]  input name "mix_magnitude"

plus manifest.json {input_name, shape, files}. Feed the folder to tools/npu's QDQ step.

Data: use material you may legally process (your own library, or MUSDB18-HQ *train* mixtures
for research builds); 64-128 chunks across genres, loud and quiet, with and without vocals.
Audio is read with soundfile and resampled to 44.1 kHz with scipy's polyphase resampler.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import sys
from fractions import Fraction

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import stft_reference as ref  # noqa: E402


def load(path: str) -> np.ndarray:
    import soundfile as sf
    from scipy.signal import resample_poly

    audio, rate = sf.read(path, always_2d=True, dtype="float64")
    if audio.shape[1] == 1:
        audio = np.repeat(audio, 2, axis=1)
    audio = audio[:, :2].T
    if rate != ref.SAMPLE_RATE:
        ratio = Fraction(ref.SAMPLE_RATE, rate).limit_denominator(1000)
        audio = resample_poly(audio, ratio.numerator, ratio.denominator, axis=1)
    return audio


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--audio", required=True, help="folder of audio files (wav/flac/ogg)")
    ap.add_argument("--layout", required=True, choices=("scnet", "umx"))
    ap.add_argument("--out", required=True)
    ap.add_argument("--count", type=int, default=96)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    files = sorted(sum((glob.glob(os.path.join(args.audio, "**", ext), recursive=True)
                        for ext in ("*.wav", "*.flac", "*.ogg")), []))
    if not files:
        print("no audio files found")
        return 1
    rng = np.random.default_rng(args.seed)
    os.makedirs(args.out, exist_ok=True)
    chunk = ref.SCNET_SEGMENT if args.layout == "scnet" else ref.segment_samples(ref.UMX_FRAMES)
    written = []
    per_file = max(1, args.count // len(files))
    for path in files:
        audio = load(path)
        if audio.shape[1] < chunk:
            continue
        for _ in range(per_file):
            if len(written) >= args.count:
                break
            start = int(rng.integers(0, audio.shape[1] - chunk + 1))
            left, right = audio[0, start:start + chunk], audio[1, start:start + chunk]
            if args.layout == "scnet":
                # Region statistics, as on the phone (a transition region is ~20-40 s; one chunk
                # stands in for it here).
                left, right, _, _ = ref.normalize(left, right)
                tensor = ref.scnet_input(left, right)
            else:
                tensor = ref.umx_input(left, right)
            name = f"calib_{len(written):04d}.npy"
            np.save(os.path.join(args.out, name), tensor)
            written.append(name)
    manifest = {
        "input_name": "mix_spec" if args.layout == "scnet" else "mix_magnitude",
        "shape": [1, 4, ref.BINS, ref.SCNET_FRAMES] if args.layout == "scnet" else [1, 2, ref.BINS, ref.UMX_FRAMES],
        "files": written,
    }
    with open(os.path.join(args.out, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"wrote {len(written)} tensors to {args.out}")
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(main())
