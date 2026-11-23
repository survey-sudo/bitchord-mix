"""Reference STFT / iSTFT for BitChord's stem models, bit-for-bit the convention of
native/analyzer/stem_dsp.cpp (and of torch.stft/istft with center=True, reflect padding).

Used by the exporters' verification and by make_calibration.py, so every tensor a tool
produces is the tensor the phone will produce.

    SCNet : rectangular window, normalized (x 1/sqrt(4096)), CaC planes L.re, L.im, R.re, R.im
    UMX   : periodic Hann window, unnormalized, magnitude planes L, R

All arrays are float64 here; cast to float32 at the boundary.
"""
from __future__ import annotations

import numpy as np

N_FFT = 4096
HOP = 1024
BINS = N_FFT // 2 + 1
SAMPLE_RATE = 44100

# Static graph geometry (keep in sync with StemModels in StemModelStore.kt).
SCNET_FRAMES = 476
SCNET_SEGMENT = 485_100  # 11 s of audio; the rest of the 486400-sample STFT segment is zeros
UMX_FRAMES = 960


def window(kind: str) -> np.ndarray:
    if kind == "rect":
        return np.ones(N_FFT)
    if kind == "hann":
        n = np.arange(N_FFT)
        return 0.5 - 0.5 * np.cos(2.0 * np.pi * n / N_FFT)  # periodic, = torch.hann_window(4096)
    raise ValueError(kind)


def segment_samples(frames: int) -> int:
    return (frames - 1) * HOP


def stft(x: np.ndarray, frames: int, kind: str, normalized: bool, valid: int | None = None) -> np.ndarray:
    """Complex STFT [BINS, frames] of a 1-D segment of segment_samples(frames) samples.

    Samples at or beyond `valid` are zeros (SCNet's zero tail), then the segment is
    reflect-padded by N_FFT/2 at both ends, exactly like the native ForwardFrame.
    """
    samples = segment_samples(frames)
    seg = np.zeros(samples)
    v = min(len(x), samples) if valid is None else min(valid, len(x), samples)
    seg[:v] = x[:v]
    padded = np.pad(seg, (N_FFT // 2, N_FFT // 2), mode="reflect")
    w = window(kind)
    scale = 1.0 / np.sqrt(N_FFT) if normalized else 1.0
    out = np.empty((BINS, frames), dtype=np.complex128)
    for t in range(frames):
        out[:, t] = np.fft.rfft(padded[t * HOP: t * HOP + N_FFT] * w * scale)
    return out


def istft(spec: np.ndarray, kind: str, normalized: bool) -> np.ndarray:
    """torch.istft(center=True): returns segment_samples(frames) samples."""
    frames = spec.shape[1]
    w = window(kind)
    length = (frames - 1) * HOP + N_FFT
    ola = np.zeros(length)
    env = np.zeros(length)
    scale = np.sqrt(N_FFT) if normalized else 1.0
    for t in range(frames):
        ola[t * HOP: t * HOP + N_FFT] += np.fft.irfft(spec[:, t] * scale, N_FFT) * w
        env[t * HOP: t * HOP + N_FFT] += w * w
    half = N_FFT // 2
    return (ola / np.maximum(env, 1e-11))[half: half + segment_samples(frames)]


def scnet_input(left: np.ndarray, right: np.ndarray, valid: int = SCNET_SEGMENT) -> np.ndarray:
    """[1, 4, 2049, 476] float32 CaC tensor for one SCNet chunk (already normalized audio)."""
    planes = []
    for ch in (left, right):
        s = stft(ch, SCNET_FRAMES, "rect", True, valid)
        planes += [s.real, s.imag]
    return np.stack(planes)[None].astype(np.float32)


def umx_input(left: np.ndarray, right: np.ndarray, frames: int = UMX_FRAMES) -> np.ndarray:
    """[1, 2, 2049, frames] float32 magnitude tensor for one Open-Unmix chunk."""
    return np.stack([np.abs(stft(ch, frames, "hann", False)) for ch in (left, right)])[None].astype(np.float32)


def normalize(left: np.ndarray, right: np.ndarray) -> tuple[np.ndarray, np.ndarray, float, float]:
    """Music-Source-Separation-Training inference.normalize: mono mean / unbiased std."""
    mono = 0.5 * (left + right)
    mean = float(mono.mean())
    std = float(mono.std(ddof=1))
    if std < 1e-6:
        return left - mean, right - mean, mean, 1.0
    return (left - mean) / std, (right - mean) / std, mean, std


def _self_test() -> None:
    rng = np.random.default_rng(0)
    frames = 32
    x = rng.standard_normal(segment_samples(frames)) * 0.1
    for kind, norm in (("rect", True), ("hann", False)):
        y = istft(stft(x, frames, kind, norm), kind, norm)
        err = np.abs(y - x).max()
        assert err < 1e-9, (kind, err)
    try:
        import torch

        t = torch.from_numpy(x)
        ref = torch.stft(t, N_FFT, HOP, N_FFT, window=None, center=True, normalized=True, return_complex=True)
        ours = stft(x, frames, "rect", True)
        assert np.abs(ref.numpy() - ours).max() < 1e-6
        ref = torch.stft(t, N_FFT, HOP, N_FFT, window=torch.hann_window(N_FFT, dtype=torch.float64),
                         center=True, normalized=False, return_complex=True)
        ours = stft(x, frames, "hann", False)
        assert np.abs(ref.numpy() - ours).max() < 1e-6
        print("matches torch.stft")
    except ImportError:
        print("torch not installed; numpy round trip only")
    print("stft_reference self-test passed")


if __name__ == "__main__":
    _self_test()
