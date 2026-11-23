"""NumPy log-mel front end, bit-for-bit the same recipe as native/analyzer/mel_spectrogram.cpp.

22050 Hz mono, n_fft 1024, hop 441 (50 fps), periodic Hann, centre reflect padding,
|STFT| / sqrt(n_fft), 128 Slaney-scale triangular filters 30..11000 Hz with unit peak
(no area normalisation, triangles evaluated at bin centre frequencies exactly as the C++),
log1p(1000 * max(energy, 1e-10)). Output: float32 [frames, 128].
"""
from __future__ import annotations

import math

import numpy as np

SAMPLE_RATE = 22050
N_FFT = 1024
HOP = 441
MELS = 128
MIN_HZ = 30.0
MAX_HZ = 11000.0
LOG_MULTIPLIER = 1000.0
FLOOR = 1e-10


def hz_to_mel(hz: float) -> float:
    f_sp = 200.0 / 3.0
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = math.log(6.4) / 27.0
    if hz >= min_log_hz:
        return min_log_mel + math.log(hz / min_log_hz) / logstep
    return hz / f_sp


def mel_to_hz(mel: float) -> float:
    f_sp = 200.0 / 3.0
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = math.log(6.4) / 27.0
    if mel >= min_log_mel:
        return min_log_hz * math.exp(logstep * (mel - min_log_mel))
    return f_sp * mel


def filterbank(sample_rate: float = SAMPLE_RATE) -> np.ndarray:
    """Dense [bins, MELS] matrix with the same weights the C++ sparse filters hold."""
    bins = N_FFT // 2 + 1
    mel_min, mel_max = hz_to_mel(MIN_HZ), hz_to_mel(MAX_HZ)
    edges = [mel_to_hz(mel_min + (mel_max - mel_min) * i / (MELS + 1)) for i in range(MELS + 2)]
    fb = np.zeros((bins, MELS), dtype=np.float64)
    for m in range(MELS):
        left, centre, right = edges[m], edges[m + 1], edges[m + 2]
        first = max(0, int(math.floor(left * N_FFT / sample_rate)))
        last = min(bins - 1, int(math.ceil(right * N_FFT / sample_rate)))
        for b in range(first, last + 1):
            hz = b * sample_rate / N_FFT
            rising = (hz - left) / (centre - left) if centre > left else 0.0
            falling = (right - hz) / (right - centre) if right > centre else 0.0
            fb[b, m] = max(0.0, min(rising, falling))
    return fb


_FB = None


def log_mel(samples: np.ndarray, sample_rate: float = SAMPLE_RATE) -> np.ndarray:
    global _FB
    if abs(sample_rate - SAMPLE_RATE) > 1.0:
        raise ValueError("resample to 22050 Hz first")
    x = np.asarray(samples, dtype=np.float32).astype(np.float64)
    pad = N_FFT // 2
    if x.size <= pad + 1:
        return np.zeros((0, MELS), dtype=np.float32)
    x = np.pad(x, pad, mode="reflect")
    frames = (x.size - N_FFT) // HOP + 1
    window = 0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(N_FFT) / N_FFT)
    idx = np.arange(N_FFT)[None, :] + HOP * np.arange(frames)[:, None]
    spec = np.abs(np.fft.rfft(x[idx] * window[None, :], axis=1)) / math.sqrt(N_FFT)
    if _FB is None:
        _FB = filterbank()
    energy = spec @ _FB
    return np.log1p(LOG_MULTIPLIER * np.maximum(energy, FLOOR)).astype(np.float32)


def load_audio(path: str) -> np.ndarray:
    """Mono float32 at 22050 Hz."""
    import soundfile as sf
    from scipy.signal import resample_poly

    data, rate = sf.read(path, dtype="float32", always_2d=True)
    mono = data.mean(axis=1)
    if rate != SAMPLE_RATE:
        g = math.gcd(int(rate), SAMPLE_RATE)
        mono = resample_poly(mono, SAMPLE_RATE // g, int(rate) // g).astype(np.float32)
    return mono
