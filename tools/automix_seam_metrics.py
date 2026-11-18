#!/usr/bin/env python3
"""Small dependency-free metrics report for a rendered stereo WAV."""
import argparse
import math
import struct
import wave


def read_wav(path):
    with wave.open(path, "rb") as w:
        rate, channels, width, count = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        raw = w.readframes(count)
    if width != 2 or channels not in (1, 2):
        raise ValueError(f"{path}: expected PCM16 mono/stereo")
    vals = struct.unpack("<%dh" % (len(raw) // 2), raw)
    if channels == 1:
        vals = tuple(x for s in vals for x in (s, s))
    return rate, [x / 32768.0 for x in vals]


def rms(values):
    return math.sqrt(sum(x * x for x in values) / max(1, len(values)))


def db(x):
    return 20.0 * math.log10(max(1e-12, x))


def true_peak(values):
    peak = 0.0
    for i in range(0, len(values) - 2, 2):
        for c in (0, 1):
            a, b = values[i + c], values[i + 2 + c]
            peak = max(peak, abs(a), abs(a * 0.25 + b * 0.75),
                       abs(a * 0.5 + b * 0.5), abs(a * 0.75 + b * 0.25))
    return peak


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wav")
    ap.add_argument("--reference")
    ap.add_argument("--boundary-sec", type=float, default=3.0)
    args = ap.parse_args()
    rate, data = read_wav(args.wav)
    mono = [(data[i] + data[i + 1]) * 0.5 for i in range(0, len(data), 2)]
    integrated = db(rms(mono)) - 0.691
    peak_db = db(true_peak(data))
    boundary = min(len(mono) - 1, max(1, int(args.boundary_sec * rate)))
    step = max(abs(mono[boundary] - mono[boundary - 1]),
               abs(data[2 * boundary] - data[2 * boundary - 2]),
               abs(data[2 * boundary + 1] - data[2 * boundary - 1]))
    window = max(1, int(0.1 * rate))
    before = rms(mono[max(0, boundary - window):boundary])
    after = rms(mono[boundary:min(len(mono), boundary + window)])
    jump = db(after / max(1e-12, before))
    lag = 0
    if args.reference:
        rr, ref = read_wav(args.reference)
        if rr != rate:
            raise ValueError("reference sample rate differs")
        refm = [(ref[i] + ref[i + 1]) * 0.5 for i in range(0, len(ref), 2)]
        n = min(len(mono), len(refm), 2 * rate)
        best = -1e30
        for candidate in range(-200, 201):
            s = max(0, -candidate)
            e = min(n, n - candidate)
            if e <= s:
                continue
            score = sum(mono[i] * refm[i + candidate] for i in range(s, e))
            if score > best:
                best, lag = score, candidate
    print("metrics wav=%s rate=%d frames=%d rms_approx_lufs=%.3f true_peak_dbtp=%.3f "
          "boundary_step=%.9f rms_jump_db=%.3f cross_correlation_lag_samples=%d" %
          (args.wav, rate, len(mono), integrated, peak_db, step, jump, lag))


if __name__ == "__main__":
    main()
