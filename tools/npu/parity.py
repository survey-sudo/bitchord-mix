#!/usr/bin/env python3
"""Parity gate: QDQ (CPU EP, fake-quant numerics) vs FP32 on real audio, BeatTracker-style.

Chunks exactly like the BeatTracker v2 hook (fixed FRAMES window, 6-frame borders discarded,
last chunk aligned to the end), peak-picks like BeatTracker.pickPeaks (positive local maximum
over 7 frames), then reports beat / downbeat F-measure at +-70 ms and max abs logit diff.
Fails (exit 1) when either F drops more than --max-f-drop points vs FP32.

  python parity.py --fp32 build/beat_this_final0_fp32_500.onnx \
      --qdq ../../app/src/main/assets/beat_this_final0_qdq.onnx --audio ~/Music/holdout
"""
from __future__ import annotations

import argparse
import glob
import os

import numpy as np
import onnxruntime as ort

from mel import load_audio, log_mel

BORDER = 6
FPS = 50.0


def infer(sess, mel, frames):
    name = sess.get_inputs()[0].name
    n = mel.shape[0]
    beat, down = np.zeros(n, np.float32), np.zeros(n, np.float32)
    if n < frames:
        pad = np.zeros((frames, mel.shape[1]), np.float32)
        pad[:n] = mel
        b, d = sess.run(None, {name: pad[None]})
        return b[0, :n], d[0, :n]
    stride = frames - 2 * BORDER
    start = 0
    while True:
        s = min(start, n - frames)
        b, d = sess.run(None, {name: mel[s:s + frames][None]})
        keep_from = 0 if s == 0 else BORDER
        keep_to = frames if s + frames >= n else frames - BORDER
        beat[s + keep_from:s + keep_to] = b[0, keep_from:keep_to]
        down[s + keep_from:s + keep_to] = d[0, keep_from:keep_to]
        if s + frames >= n:
            break
        start += stride
    return beat, down


def peaks(logits, window=7):
    half = window // 2
    out = []
    for i, v in enumerate(logits):
        if v <= 0:
            continue
        lo, hi = max(0, i - half), min(len(logits), i + half + 1)
        if v >= logits[lo:hi].max():
            if out and i - out[-1] <= 1:
                continue
            out.append(i)
    return np.array(out, dtype=np.float64) / FPS


def fmeasure(ref, est, tol=0.07):
    if len(ref) == 0 and len(est) == 0:
        return 1.0
    if len(ref) == 0 or len(est) == 0:
        return 0.0
    used = np.zeros(len(est), bool)
    hits = 0
    for r in ref:
        d = np.abs(est - r)
        d[used] = np.inf
        k = int(np.argmin(d))
        if d[k] <= tol:
            used[k] = True
            hits += 1
    p, rc = hits / len(est), hits / len(ref)
    return 0.0 if p + rc == 0 else 2 * p * rc / (p + rc)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fp32", required=True)
    ap.add_argument("--qdq", required=True)
    ap.add_argument("--audio", required=True, help="held-out tracks (NOT the calibration set)")
    ap.add_argument("--max-f-drop", type=float, default=2.0, help="points (vc91 gate: 2)")
    args = ap.parse_args()
    so = ort.SessionOptions()
    fp = ort.InferenceSession(args.fp32, so, providers=["CPUExecutionProvider"])
    qd = ort.InferenceSession(args.qdq, so, providers=["CPUExecutionProvider"])
    frames = qd.get_inputs()[0].shape[1]
    assert isinstance(frames, int), "QDQ model must have a fixed time dimension"
    fb, fd, mx = [], [], 0.0
    files = sorted(f for f in glob.glob(os.path.join(args.audio, "**", "*"), recursive=True)
                   if f.lower().endswith((".wav", ".flac", ".ogg")))
    for path in files:
        mel = log_mel(load_audio(path))
        b32, d32 = infer(fp, mel, frames)
        bq, dq = infer(qd, mel, frames)
        mx = max(mx, float(np.abs(b32 - bq).max()), float(np.abs(d32 - dq).max()))
        fb.append(fmeasure(peaks(b32), peaks(bq)))
        fd.append(fmeasure(peaks(d32), peaks(dq)))
        print(f"{os.path.basename(path)}: beatF={fb[-1]:.3f} downF={fd[-1]:.3f}")
    beat_f, down_f = 100 * np.mean(fb), 100 * np.mean(fd)
    print(f"QDQ vs FP32 (FP32 as reference): beat F={beat_f:.1f}  downbeat F={down_f:.1f}  max|dlogit|={mx:.3f}")
    if 100 - beat_f > args.max_f_drop or 100 - down_f > args.max_f_drop:
        raise SystemExit("PARITY FAIL: retry with --per-channel, more calibration audio, or keep CPU")
    print("PARITY PASS")


if __name__ == "__main__":
    main()
