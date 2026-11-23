#!/usr/bin/env python3
"""Calibration set for static QDQ: real-music log-mel chunks of exactly FRAMES frames.

  python make_calibration.py --audio ~/Music/calib --frames 500 --per-track 4 --out build/calib_500.npz

Use 40-100 varied tracks (genres, loudness, tempo, intros/breakdowns); ~200-400 chunks total.
Chunks are drawn at deterministic offsets (seeded) across each track, skipping near-silence.
"""
from __future__ import annotations

import argparse
import glob
import os

import numpy as np

from mel import MELS, load_audio, log_mel

EXT = (".wav", ".flac", ".ogg", ".aiff", ".aif")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--audio", required=True, help="folder of wav/flac/ogg files (mp3: convert with ffmpeg first)")
    ap.add_argument("--frames", type=int, default=500)
    ap.add_argument("--per-track", type=int, default=4)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--out", default="build/calib_500.npz")
    args = ap.parse_args()
    rng = np.random.default_rng(args.seed)
    files = sorted(f for f in glob.glob(os.path.join(args.audio, "**", "*"), recursive=True) if f.lower().endswith(EXT))
    if not files:
        raise SystemExit("no audio files found")
    chunks = []
    for path in files:
        try:
            mel = log_mel(load_audio(path))
        except Exception as error:  # noqa: BLE001
            print("skip", path, error)
            continue
        if mel.shape[0] < args.frames:
            continue
        starts = rng.integers(0, mel.shape[0] - args.frames + 1, size=args.per_track * 3)
        taken = 0
        for s in starts:
            c = mel[s:s + args.frames]
            if c.mean() < 0.3:  # near-silence: useless for activation ranges
                continue
            chunks.append(c)
            taken += 1
            if taken >= args.per_track:
                break
        print(f"{os.path.basename(path)}: {taken} chunks")
    data = np.stack(chunks).astype(np.float32)
    assert data.shape[1:] == (args.frames, MELS)
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    np.savez_compressed(args.out, mels=data)
    print(f"{data.shape[0]} chunks -> {args.out}")


if __name__ == "__main__":
    main()
