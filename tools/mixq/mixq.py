#!/usr/bin/env python3
"""mixq — AE3 objective audio quality gates (Part B6.2).

Computes the 8 B6.2 gates directly from 48 kHz WAVs with numpy + stdlib only
(no scipy required). When Nirovi/Syvenu analysis or planner metadata is
supplied via ``--meta``, the beat/vocal/placement metrics use it; otherwise
clean DSP fallback estimators run standalone on the WAV (each estimator is
labelled in ``methods`` so a metadata-backed number is never confused with
a bare-WAV estimate).

Usage:
    mixq.py check <wav_or_dir> [--meta meta.json] [-o results.json]
    mixq.py report <results.json> --out <AE3-build.html>

Gate table (Part B6.2):
    1. True peak <= -1.0 dBTP (4x oversampled, zero NaN/Inf)
    2. Beat-phase error: median <= 10 ms, p95 <= 25 ms
    3. Vocal-overlap seconds <= 2.0 s (0 target at Subtle/Club)
    4. Bass clash (20-200 Hz dual-deck) <= 1 beat above -6 dB rel.
    5. Short-term LUFS deviation vs A->B interpolation: +-2.0 LU
       (Festival drop windows exempt)
    6. Spectral-flux z-score at swap <= 3.0 (abrupt recipes exempt)
    7. Exit/entry placement violations == 0
    8. Silence gaps > 150 ms inside the transition == 0

Exit code for ``check``: 0 when every file passes all gates, 1 when any
file fails a gate, 2 on usage/IO errors.
"""

from __future__ import annotations

import argparse
import glob
import html
import json
import math
import os
import re
import struct
import sys
import wave
from pathlib import Path

try:
    import numpy as np
except ImportError:  # pragma: no cover - environment guard
    sys.stderr.write("mixq requires numpy (python3 -c 'import numpy')\n")
    raise

SR = 48000
TOOL_VERSION = "1.0.0"

# ---------------------------------------------------------------------------
# WAV I/O (PCM16 / float32, mono/stereo, manual RIFF parse so float32 WAVs
# written by ffmpeg -c:a pcm_f32le are supported without scipy).
# ---------------------------------------------------------------------------


def read_wav(path: str | os.PathLike) -> tuple[int, np.ndarray, np.ndarray]:
    """Read WAV, return (sample_rate, left, right) as float64 in [-inf..inf].

    Supports PCM16, float32 and WAVE_FORMAT_EXTENSIBLE. Mono is duplicated to
    both channels. Raises ValueError on unsupported formats.
    """
    path = str(path)
    with open(path, "rb") as f:
        blob = f.read()
    if len(blob) < 12 or blob[0:4] != b"RIFF" or blob[8:12] != b"WAVE":
        raise ValueError(f"not a WAVE file: {path}")

    fmt = None
    data_off = data_size = None
    off = 12
    while off + 8 <= len(blob):
        chunk_id = blob[off : off + 4]
        chunk_size = struct.unpack_from("<I", blob, off + 4)[0]
        chunk_start = off + 8
        if chunk_start + chunk_size > len(blob):
            raise ValueError(f"truncated chunk in {path}")
        if chunk_id == b"fmt ":
            fmt = blob[chunk_start : chunk_start + chunk_size]
        elif chunk_id == b"data":
            data_off, data_size = chunk_start, chunk_size
        off = chunk_start + chunk_size + (chunk_size & 1)
    if fmt is None or data_off is None:
        raise ValueError(f"missing fmt/data chunk: {path}")

    audio_format = struct.unpack_from("<H", fmt, 0)[0]
    channels = struct.unpack_from("<H", fmt, 2)[0]
    src_rate = struct.unpack_from("<I", fmt, 4)[0]
    bits = struct.unpack_from("<H", fmt, 14)[0]
    if audio_format == 0xFFFE:  # WAVE_FORMAT_EXTENSIBLE
        if len(fmt) < 40:
            raise ValueError(f"short extensible fmt chunk: {path}")
        audio_format = struct.unpack_from("<H", fmt, 24)[0]
        valid_bits = struct.unpack_from("<H", fmt, 18)[0]
        if valid_bits:
            bits = valid_bits
    if channels not in (1, 2):
        raise ValueError(f"unsupported channel count {channels}: {path}")
    is_pcm16 = audio_format == 1 and bits == 16
    is_f32 = audio_format == 3 and bits == 32
    if not is_pcm16 and not is_f32:
        raise ValueError(
            f"unsupported WAV format (want PCM16/float32, got fmt={audio_format} "
            f"bits={bits}): {path}"
        )
    block_align = channels * (bits // 8)
    if block_align == 0 or data_size % block_align != 0:
        raise ValueError(f"bad data size: {path}")
    frames = data_size // block_align
    raw = memoryview(blob)[data_off : data_off + data_size]

    if is_pcm16:
        samples = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    else:
        samples = np.frombuffer(raw, dtype="<f4").astype(np.float64)
    if channels == 1:
        left = samples.copy()
        right = samples.copy()
    else:
        left = samples[0::2].copy()
        right = samples[1::2].copy()
    return int(src_rate), left, right


def write_wav_pcm16(path: str, left: np.ndarray, right: np.ndarray, sr: int = SR) -> None:
    """Write stereo PCM16 WAV (used by the audition fallback renderer)."""
    left = np.clip(left, -1.0, 1.0)
    right = np.clip(right, -1.0, 1.0)
    inter = np.empty((len(left), 2), dtype=np.float64)
    inter[:, 0] = left
    inter[:, 1] = right
    pcm = np.round(inter * 32767.0).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def resample_to_48k(x: np.ndarray, src_sr: int) -> np.ndarray:
    """Linear-interpolation resample to 48 kHz (identity when already 48 k)."""
    if src_sr == SR or len(x) == 0:
        return x
    n_out = int(round(len(x) * SR / src_sr))
    if n_out <= 0:
        return np.zeros(0, dtype=np.float64)
    t_in = np.arange(len(x), dtype=np.float64) * (src_sr / SR)
    return np.interp(t_in, np.arange(len(x), dtype=np.float64), x)


def load_audio(path: str) -> tuple[np.ndarray, np.ndarray, float]:
    """Load WAV as 48 kHz stereo float64 plus duration in seconds."""
    sr, left, right = read_wav(path)
    left = resample_to_48k(np.asarray(left, dtype=np.float64), sr)
    right = resample_to_48k(np.asarray(right, dtype=np.float64), sr)
    n = min(len(left), len(right))
    left, right = left[:n], right[:n]
    return left, right, n / SR


# ---------------------------------------------------------------------------
# DSP helpers (numpy only)
# ---------------------------------------------------------------------------


def db_to_lin(db: float) -> float:
    return 10.0 ** (db / 20.0)


def lin_to_db(x: float) -> float:
    return 20.0 * math.log10(x) if x > 0.0 else float("-inf")


def upsample_4x(x: np.ndarray) -> np.ndarray:
    """4x sinc interpolation via FFT zero-padding (polyphase-equivalent)."""
    n = len(x)
    if n == 0:
        return x
    n4 = n * 4
    spectrum = np.fft.rfft(x)
    padded = np.zeros(n4 // 2 + 1, dtype=np.complex128)
    take = min(len(spectrum), len(padded))
    padded[:take] = spectrum[:take]
    y = np.fft.irfft(padded, n=n4) * 4.0
    return y


def true_peak_db(left: np.ndarray, right: np.ndarray) -> float:
    """4x oversampled true peak in dBTP. Guards NaN/Inf (returns -inf... no:
    raises so the caller can record the failure mode explicitly)."""
    if not (np.all(np.isfinite(left)) and np.all(np.isfinite(right))):
        raise ValueError("signal contains NaN/Inf samples")
    peaks = []
    for ch in (left, right):
        if len(ch) == 0:
            continue
        up = upsample_4x(ch)
        peaks.append(float(np.max(np.abs(up))) if up.size else 0.0)
    peak = max(peaks) if peaks else 0.0
    if peak <= 0.0:
        return float("-inf")
    return 20.0 * math.log10(peak)


def stft_mag(x: np.ndarray, n_fft: int = 2048, hop: int = 512):
    """Hann-windowed magnitude STFT. Returns (freqs_hz, mag[frame, bin], hop)."""
    n_fft = int(n_fft)
    hop = int(hop)
    if len(x) < n_fft:
        window = np.hanning(max(len(x), 2))
        mag = np.abs(np.fft.rfft(x * window))[:, None] if len(x) else np.zeros((1, 2))
        freqs = np.fft.rfftfreq(max(len(x), 2), 1.0 / SR)
        return freqs, mag, hop
    window = np.hanning(n_fft)
    n_frames = 1 + (len(x) - n_fft) // hop
    mag = np.empty((n_frames, n_fft // 2 + 1), dtype=np.float64)
    for i in range(n_frames):
        seg = x[i * hop : i * hop + n_fft] * window
        mag[i] = np.abs(np.fft.rfft(seg))
    freqs = np.fft.rfftfreq(n_fft, 1.0 / SR)
    return freqs, mag, hop


def onset_envelope(mag: np.ndarray) -> np.ndarray:
    """Spectral-flux onset envelope (positive differences, summed over bins)."""
    if mag.shape[0] < 2:
        return np.zeros(mag.shape[0], dtype=np.float64)
    flux = np.diff(mag, axis=0)
    np.maximum(flux, 0.0, out=flux)
    env = flux.sum(axis=1)
    env = np.concatenate(([0.0], env))
    if np.max(env) > 0:
        env = env / (np.max(env) + 1e-12)
    return env


def pick_peaks(env: np.ndarray, min_dist: int = 8, thresh_frac: float = 0.35):
    """Greedy local-max peak picking with minimum distance (no scipy)."""
    if len(env) == 0 or np.max(env) <= 0:
        return np.zeros(0, dtype=int)
    thresh = thresh_frac * float(np.max(env))
    idx = [i for i in range(1, len(env) - 1) if env[i] >= thresh and env[i] >= env[i - 1] and env[i] >= env[i + 1]]
    peaks: list[int] = []
    for i in sorted(idx, key=lambda k: -env[k]):
        if all(abs(i - p) >= min_dist for p in peaks):
            peaks.append(i)
    return np.array(sorted(peaks), dtype=int)


def autocorr_bpm_from_onset(env: np.ndarray, hop: int) -> tuple[float, float]:
    """Estimate BPM/beat period from the onset envelope autocorrelation.

    Returns (bpm, beat_sec); falls back to 120 BPM when undetectable.
    """
    if len(env) < 32 or np.max(env) <= 0:
        return 120.0, 0.5
    sig = env - float(np.mean(env))
    corr = np.correlate(sig, sig, mode="full")[len(sig) - 1 :]
    if np.max(corr) <= 0:
        return 120.0, 0.5
    corr = corr / (np.max(corr) + 1e-12)
    frame_sec = hop / SR
    # Search 45..185 BPM.
    lo = int(round(60.0 / 185.0 / frame_sec))
    hi = int(round(60.0 / 45.0 / frame_sec))
    hi = min(hi, len(corr) - 1)
    best_lag = lo + int(np.argmax(corr[lo : hi + 1]))
    best_corr = float(corr[best_lag])
    # Double-period trap: a 128 BPM four-on-the-floor kick correlates just
    # as strongly at 64 BPM. Prefer the shortest lag whose correlation is
    # a substantial fraction of the best (a real beat of a faster groove).
    for div in (2, 3, 4, 5):
        cand = int(round(best_lag / div))
        if cand < lo:
            continue
        if float(corr[cand]) >= 0.55 * best_corr:
            best_lag = cand
            break
    beat_sec = best_lag * frame_sec
    bpm = 60.0 / beat_sec if beat_sec > 0 else 120.0
    return bpm, beat_sec


def _grid_occupancy(onset_times: np.ndarray, period: float, tol: float) -> float:
    """Fraction of grid points (anchored at the first onset) with an onset nearby."""
    if onset_times.size < 2 or period <= 0:
        return 0.0
    start = float(np.min(onset_times))
    end = float(np.max(onset_times))
    n_points = int((end - start) / period) + 1
    if n_points < 3:
        return 0.0
    grid = start + np.arange(n_points) * period
    covered = np.zeros(n_points, dtype=bool)
    for t in onset_times:
        k = int(round((t - start) / period))
        if 0 <= k < n_points and abs(start + k * period - t) <= tol:
            covered[k] = True
    return float(np.mean(covered))


def refine_beat_period(onset_times: np.ndarray, period: float) -> float:
    """Halve/third the estimated beat when the finer grid is well populated.

    Autocorrelation tends to lock onto the two-beat snare cycle (half the
    tempo) for four-on-the-floor dance tracks. If onsets also populate the
    finer grid strongly (kick on every beat), the finer period is the beat.
    """
    best = max(period, 1e-6)
    for _ in range(2):  # allow double refinement (e.g. 64 -> 128 -> 256 guarded by rate floor)
        refined = False
        for div in (2, 3):
            cand = best / div
            if cand < 60.0 / 200.0:  # faster than 200 BPM is not a beat
                continue
            tol = max(0.04, 0.15 * cand)
            if _grid_occupancy(onset_times, cand, tol) >= 0.55:
                best = cand
                refined = True
                break
        if not refined:
            break
    return best


def lowband_beat_estimate(mag: np.ndarray, freqs: np.ndarray, hop: int):
    """Kick-train beat estimate from the 20-250 Hz band energy peaks.

    Kicks are the strongest on-grid element in DJ material, so the median
    peak-to-peak interval recovers the beat more reliably than onset
    autocorrelation (which can lock onto the two-beat snare cycle).
    Peak positions are refined by parabolic interpolation, and the period
    is refined again by DFT phase-resultant length (events must sit near
    a common grid phase) to survive syncopated kicks.
    Returns (period_sec, peak_times_sec) or (None, fewer times).
    """
    band = (freqs >= 20.0) & (freqs <= 250.0)
    if not band.any() or mag.shape[0] == 0:
        return None, np.zeros(0, dtype=float)
    env = mag[:, band].sum(axis=1)
    peak_env = float(np.max(env))
    if peak_env <= 0:
        return None, np.zeros(0, dtype=float)
    peaks = pick_peaks(env / peak_env, min_dist=14, thresh_frac=0.5)
    frame_sec = hop / SR
    times = []
    weights = []
    for k in peaks:
        # Parabolic refinement of the peak position (sub-frame timing).
        pos = float(k)
        if 0 < k < len(env) - 1:
            a, b, c = env[k - 1], env[k], env[k + 1]
            denom = a - 2.0 * b + c
            if denom != 0:
                delta = 0.5 * (a - c) / denom
                if -1.0 <= delta <= 1.0:
                    pos += delta
        times.append(pos * frame_sec)
        weights.append(float(env[k]))
    times = np.array(times, dtype=float)
    weights = np.array(weights, dtype=float)
    if len(times) < 6:
        return None, times
    intervals = np.diff(times)
    intervals = intervals[(intervals > 0.25) & (intervals < 1.4)]
    if len(intervals) < 5:
        return None, times
    median_iv = float(np.median(intervals))
    # DFT phase-resultant refinement over a plausible beat range.
    cands = np.arange(0.30, 1.351, 0.004)
    best_r, best_p = -1.0, median_iv
    wsum = float(np.sum(weights))
    for p in cands:
        r = abs(np.sum(weights * np.exp(-2j * np.pi * times / p))) / wsum
        if r > best_r:
            best_r, best_p = float(r), float(p)
    # Octave rule: prefer the longest period explaining nearly the same
    # coherence (a p/2 hypothesis also coheres trivially).
    period = best_p
    for p in cands:
        if p < period:
            continue
        r = abs(np.sum(weights * np.exp(-2j * np.pi * times / p))) / wsum
        if r >= 0.92 * best_r:
            period = float(p)
    return period, times


def beat_phase_error_ms(
    onset_times: np.ndarray,
    period_before: float,
    swap_sec: float,
    period_after: float,
) -> tuple[float, float]:
    """Phase error (ms) of measured onsets vs the expected downbeat grid.

    Two-sided grid anchored at ``swap_sec``: onsets before the swap follow
    the outgoing period, onsets after it the incoming period (equal for
    beat-matched pairs, different across a tempo/genre jump). The outgoing
    side is only graded up to the pre-swap extent, so a jump does not
    fabricate drift. Returns (median_ms, p95_ms) over onset peaks within
    +-2 beats of the swap.
    """
    if onset_times.size == 0 or period_before <= 0 or period_after <= 0:
        return 0.0, 0.0
    beat_sec = max(period_after, period_before)
    half = 2.0 * beat_sec
    sel = onset_times[np.abs(onset_times - swap_sec) <= half]
    if sel.size < 2:
        sel = onset_times
    offs = []
    for t in sel:
        period = period_before if t < swap_sec else period_after
        phase = (t - swap_sec) % period
        off = min(phase, period - phase) * 1000.0
        if off <= 0.5 * period * 1000.0:
            offs.append(off)
    if not offs:
        return 0.0, 0.0
    arr = np.array(offs, dtype=np.float64)
    # Reject deliberate off-grid events (offbeat bass/hat attacks land a
    # half beat out); the gate grades the *beat* grid, so only residuals
    # within a quarter beat count as beat evidence.
    cutoff_ms = 0.25 * beat_sec * 1000.0
    core = arr[arr <= cutoff_ms]
    if core.size < 2:
        core = arr
    return float(np.median(core)), float(np.percentile(core, 95))


def k_weight_db(freqs: np.ndarray) -> np.ndarray:
    """BS.1770 K-weighting approximation (dB) in the frequency domain.

    Pre-filter: gradual +4 dB shelf above ~1.7 kHz (first-order shelf model).
    RLB: second-order high-pass feel around 38 Hz. This is an *approximation*
    (explicitly allowed by B6.2); the calibration constant cancels because
    the LUFS gate is a *deviation from an interpolation line*, not an
    absolute integrated-loudness assertion.
    """
    f = np.maximum(np.abs(freqs), 1e-6)
    shelf_db = 4.0 * (f**2 / (f**2 + 1680.0**2))
    rlb_power = f**4 / (f**4 + 38.0**4)
    rlb_db = 10.0 * np.log10(np.maximum(rlb_power, 1e-18))
    return shelf_db + rlb_db


def short_term_loudness(
    left: np.ndarray, right: np.ndarray, win_sec: float = 3.0, hop_sec: float = 0.75
) -> tuple[np.ndarray, np.ndarray]:
    """3 s short-term loudness (LUFS-ish, K-weighted, gated) over the file.

    Returns (times_centres_sec, lufs). Windows shorter than 3 s (files under
    3 s) degrade to a single whole-file window.
    """
    n = len(left)
    win = int(round(win_sec * SR))
    hop = int(round(hop_sec * SR))
    if n < win:
        win = max(n, 1)
        starts = [0]
    else:
        starts = list(range(0, n - win + 1, hop))
        if starts[-1] != n - win:
            starts.append(n - win)
    times = []
    ungated = []
    for s in starts:
        powers = []
        for ch in (left[s : s + win], right[s : s + win]):
            if len(ch) == 0:
                powers.append(0.0)
                continue
            spec = np.fft.rfft(ch * np.hanning(len(ch)))
            freqs = np.fft.rfftfreq(len(ch), 1.0 / SR)
            weight = 10.0 ** (k_weight_db(freqs) / 10.0)
            # Mean square power with the K-weight applied per bin.
            power = float(np.sum((np.abs(spec) ** 2) * weight)) / max(len(ch) ** 2, 1)
            powers.append(power)
        total = powers[0] + powers[1]
        ungated.append(-0.691 + 10.0 * math.log10(total) if total > 0 else -120.0)
        times.append((s + win / 2) / SR)
    ungated_arr = np.array(ungated, dtype=np.float64)
    # Absolute gate at -70 LU, then relative gate at -10 LU of the gated mean
    # (BS.1770 structure, approximated).
    abs_gated = ungated_arr[ungated_arr > -70.0]
    if abs_gated.size:
        integrated = -0.691 + 10.0 * math.log10(
            max(float(np.mean(10.0 ** ((abs_gated + 0.691) / 10.0))), 1e-18)
        )
        rel_thresh = integrated - 10.0
        kept = ungated_arr[ungated_arr >= rel_thresh]
        if kept.size == 0:
            kept = abs_gated
    else:
        kept = ungated_arr
    # Report per-window values below the relative gate as the gate floor so
    # the deviation curve is not polluted by silence; gated windows are
    # dropped from the deviation computation by the caller via the difference.
    out = np.array(ungated, dtype=np.float64)
    if kept.size:
        floor_val = float(kept.min()) if kept.size else -120.0
        out = np.where(out >= (float(np.mean(kept)) - 10.0), out, floor_val)
    return np.array(times, dtype=np.float64), out


def lufs_deviation_lu(
    times: np.ndarray,
    lufs: np.ndarray,
    trans_start: float,
    trans_end: float,
    drop_window: tuple[float, float] | None,
    swap_sec: float,
) -> tuple[float, dict]:
    """Max |measured - A->B linear interpolation| in LU over the transition.

    A = pre-transition average (first 3 windows of the transition),
    B = post-transition average (last 3 windows). Windows inside a Festival
    drop window are exempt.
    """
    if len(times) == 0:
        return 0.0, {"method": "no-loudness-windows"}
    t0, t1 = min(trans_start, trans_end), max(trans_start, trans_end)
    in_t = (times >= t0) & (times <= t1)
    if np.sum(in_t) < 3:
        return 0.0, {"method": "transition-too-short-for-lufs"}
    tt = times[in_t]
    vv = lufs[in_t]
    head = vv[: min(3, len(vv))]
    tail = vv[-min(3, len(vv)) :]
    a = float(np.mean(head))
    b = float(np.mean(tail))
    span = max(tt[-1] - tt[0], 1e-9)
    line = a + (b - a) * ((tt - tt[0]) / span)
    dev = np.abs(vv - line)
    mask = np.ones_like(dev, dtype=bool)
    if drop_window is not None:
        d0, d1 = min(drop_window), max(drop_window)
        mask &= ~((tt >= d0) & (tt <= d1))
    if not np.any(mask):
        return 0.0, {"method": "all-windows-in-drop-exempt"}
    max_dev = float(np.max(dev[mask]))
    return max_dev, {
        "method": "3s-K-weighted-gated-vs-AB-line",
        "aLUFS": round(a, 2),
        "bLUFS": round(b, 2),
        "swapSec": round(float(swap_sec), 3),
        "windowsInTransition": int(np.sum(in_t)),
        "windowsExempted": int(np.sum(~mask)),
    }


def spectral_flux_z(mag: np.ndarray, hop: int, swap_sec: float) -> tuple[float, dict]:
    """Spectral-flux z-score at the swap: (flux - median) / (1.4826 * MAD)."""
    if mag.shape[0] < 3:
        return 0.0, {"method": "too-short"}
    flux = np.diff(mag, axis=0)
    np.maximum(flux, 0.0, out=flux)
    flux = flux.sum(axis=1)
    if len(flux) == 0:
        return 0.0, {"method": "too-short"}
    frame_sec = hop / SR
    idx = int(round(swap_sec / frame_sec))
    idx = int(min(max(idx - 1, 0), len(flux) - 1))
    baseline = np.delete(flux, idx) if len(flux) > 1 else flux
    med = float(np.median(baseline)) if len(baseline) else 0.0
    mad = float(np.median(np.abs(baseline - med))) if len(baseline) else 0.0
    sigma = 1.4826 * mad
    if sigma <= 1e-12:
        sigma = float(np.std(baseline)) if np.std(baseline) > 0 else 1.0
    z = (float(flux[idx]) - med) / sigma
    return float(z), {
        "method": "spectral-flux-z-at-swap",
        "fluxAtSwap": round(float(flux[idx]), 6),
        "median": round(med, 6),
        "madSigma": round(sigma, 6),
        "swapSec": round(float(swap_sec), 3),
    }


def silence_gap_count(
    left: np.ndarray,
    right: np.ndarray,
    win_sec: float = 0.01,
    thresh_db: float = -50.0,
    min_gap_sec: float = 0.150,
    trans_start: float | None = None,
    trans_end: float | None = None,
) -> tuple[int, dict]:
    """Count maximal silence runs > min_gap inside the transition span."""
    mono = (np.abs(left) + np.abs(right)) * 0.5
    win = max(int(round(win_sec * SR)), 1)
    n_win = len(mono) // win
    if n_win == 0:
        return 0, {"method": "too-short"}
    rms = np.empty(n_win, dtype=np.float64)
    for i in range(n_win):
        seg = mono[i * win : (i + 1) * win]
        rms[i] = float(np.sqrt(np.mean(seg**2))) if len(seg) else 0.0
    peak = float(np.max(rms)) if n_win else 0.0
    rel_thresh = max(db_to_lin(thresh_db), db_to_lin(lin_to_db(peak) - 60.0) if peak > 0 else 0.0)
    quiet = rms < rel_thresh
    t0 = 0.0 if trans_start is None else max(trans_start, 0.0)
    t1 = (n_win * win_sec) if trans_end is None else min(trans_end, n_win * win_sec)
    lo = int(t0 / win_sec)
    hi = int(math.ceil(t1 / win_sec))
    lo = max(lo, 0)
    hi = min(hi, n_win)
    gaps = 0
    longest = 0.0
    run = 0
    for i in range(lo, hi):
        if quiet[i]:
            run += 1
        else:
            if run > 0:
                dur = run * win_sec
                longest = max(longest, dur)
                if dur > min_gap_sec:
                    gaps += 1
            run = 0
    if run > 0:
        dur = run * win_sec
        longest = max(longest, dur)
        if dur > min_gap_sec:
            gaps += 1
    return gaps, {
        "method": "rms-10ms-windows",
        "threshDb": thresh_db,
        "longestGapSec": round(longest, 3),
        "spanSec": round(hi * win_sec - lo * win_sec, 3),
    }


def vocal_envelope(mag: np.ndarray, freqs: np.ndarray) -> np.ndarray:
    """Vocal-band presence proxy in [0,1]: 300-3400 Hz concentration.

    Speech/sung vowels stack energy in the formant bands; broadband noise
    and pure bass sit outside them. Used only as a standalone fallback when
    no Syvenu curve is provided.
    """
    if mag.shape[0] == 0:
        return np.zeros(0, dtype=np.float64)
    band = (freqs >= 300.0) & (freqs <= 3400.0)
    low = (freqs >= 20.0) & (freqs < 300.0)
    high = (freqs > 3400.0) & (freqs <= 12000.0)
    band_e = mag[:, band].sum(axis=1) if band.any() else np.zeros(mag.shape[0])
    low_e = mag[:, low].sum(axis=1) if low.any() else np.zeros(mag.shape[0])
    high_e = mag[:, high].sum(axis=1) if high.any() else np.zeros(mag.shape[0])
    total = band_e + low_e + high_e
    ratio = np.where(total > 0, band_e / np.maximum(total, 1e-12), 0.0)
    # Map band ratio (0..1) through the typical vocal-vs-instrumental span.
    presence = np.clip((ratio - 0.28) / 0.35, 0.0, 1.0)
    if np.max(presence) > 0:
        presence = presence / (np.max(presence) + 1e-12) * 0.92 + presence * 0.08
    return presence


def bass_envelope(mag: np.ndarray, freqs: np.ndarray) -> np.ndarray:
    """20-200 Hz relative level envelope [0,1] vs file max."""
    band = (freqs >= 20.0) & (freqs <= 200.0)
    if not band.any() or mag.shape[0] == 0:
        return np.zeros(mag.shape[0], dtype=np.float64)
    env = mag[:, band].sum(axis=1)
    peak = float(np.max(env)) if len(env) else 0.0
    if peak <= 0:
        return np.zeros_like(env)
    return env / peak


# ---------------------------------------------------------------------------
# Metadata handling
# ---------------------------------------------------------------------------


def pick(obj: dict, *aliases, default=None):
    """Liberal alias lookup for metadata keys (snake/camel/abbrev)."""
    if not isinstance(obj, dict):
        return default
    for a in aliases:
        if a in obj and obj[a] is not None:
            return obj[a]
    lower = {str(k).lower(): v for k, v in obj.items()}
    for a in aliases:
        if a.lower() in lower and lower[a.lower()] is not None:
            return lower[a.lower()]
    return default


def get_curve_at(meta: dict, *keys, time_sec: float, default: float) -> float:
    """Sample a named time/value curve (list of [t, v] or {times, values})."""
    curve = pick(meta, *keys, default=None)
    if curve is None:
        return default
    if isinstance(curve, dict):
        times = curve.get("times") or curve.get("t") or []
        values = curve.get("values") or curve.get("v") or []
        if len(times) == len(values) and len(times):
            return float(np.interp(time_sec, times, values))
    if isinstance(curve, (list, tuple)):
        if len(curve) == 0:
            return default
        if isinstance(curve[0], (list, tuple)) and len(curve[0]) == 2:
            ts = [float(p[0]) for p in curve]
            vs = [float(p[1]) for p in curve]
            return float(np.interp(time_sec, ts, vs))
        # Plain list of values: uniform rate from meta, default 50 fps.
        fps = float(pick(meta, "vocalFps", "curveFps", "syvenuFps", default=50.0))
        idx = min(int(time_sec * fps), len(curve) - 1)
        return float(curve[max(idx, 0)])
    try:
        return float(curve)
    except (TypeError, ValueError):
        return default


def get_curve_array(meta: dict, *keys):
    """Return (times_sec, values) sampled curve arrays, or (None, None)."""
    curve = pick(meta, *keys, default=None)
    if curve is None:
        return None, None
    if isinstance(curve, dict):
        times = curve.get("times") or curve.get("t")
        values = curve.get("values") or curve.get("v")
        if times is not None and values is not None and len(times) == len(values):
            return np.array(times, dtype=float), np.array(values, dtype=float)
    if isinstance(curve, (list, tuple)) and len(curve) and isinstance(curve[0], (list, tuple)) and len(curve[0]) == 2:
        return np.array([float(p[0]) for p in curve]), np.array([float(p[1]) for p in curve])
    if isinstance(curve, (list, tuple)) and len(curve) and isinstance(curve[0], (int, float)):
        fps = float(pick(meta, "vocalFps", "curveFps", "syvenuFps", default=50.0))
        vals = np.array(curve, dtype=float)
        return np.arange(len(vals)) / fps, vals
    return None, None


def placement_violations(meta: dict, beat_sec: float) -> tuple[int, dict]:
    """Count Part A section-4 hard-reject violations from metadata/Syvenu.

    Returns (count, detail). When metadata supplies a pre-verified count
    (``placementViolations``), it is used directly and labelled as such;
    otherwise the rules are evaluated from vocal curves + flags.
    """
    pre = pick(meta, "placementViolations", "placement_violations", default=None)
    if pre is not None:
        try:
            return int(pre), {"method": "metadata-verified"}
        except (TypeError, ValueError):
            pass
    violations: list[str] = []
    exit_sec = pick(meta, "exitSec", "exit", "frozenExitSec", default=None)
    entry_sec = pick(meta, "entrySec", "entry", "frozenEntrySec", default=None)
    level = str(pick(meta, "level", "djLevel", default="CLUB")).upper()
    abrupt = str(pick(meta, "abruptness", "abruptnessClass", default="")).upper() == "ABRUPT"
    recipe_id = str(pick(meta, "recipeId", "recipe", "recipe_id", default="")).upper()
    if recipe_id in ("F2", "F7", "F9"):
        abrupt = abrupt or True
    genre_jump = bool(pick(meta, "genreJump", "genre_jump", default=False))
    tempo_jump = pick(meta, "tempoJumpPct", "tempo_jump_pct", "deltaBpmPct", default=None)
    tempo_jump = abs(float(tempo_jump)) > 8.0 if tempo_jump is not None else False

    # Exit mid-vocal: Syvenu > 0.5 within +-1 beat of the exit.
    if exit_sec is not None:
        for dt in (-beat_sec, 0.0, beat_sec):
            v = get_curve_at(meta, "vocalOut", "vocalActivityOut", "syvenuOut", "vocal_curve_out", time_sec=float(exit_sec) + dt, default=0.0)
            if v > 0.5:
                violations.append("exit-mid-vocal")
                break
    # Entry mid-vocal at Subtle/Club.
    if entry_sec is not None and level in ("SUBTLE", "CLUB"):
        v = get_curve_at(meta, "vocalIn", "vocalActivityIn", "syvenuIn", "vocal_curve_in", time_sec=float(entry_sec), default=0.0)
        if v > 0.5:
            violations.append("entry-mid-vocal")
    # Bass clash > 1 beat.
    bass_beats = pick(meta, "bassOverlapBeats", "bass_clash_beats", "bassClashBeats", default=None)
    if bass_beats is not None and float(bass_beats) > 1.0:
        violations.append("bass-clash-over-1-beat")
    # Abrupt recipe without a genre/tempo jump.
    if abrupt and not genre_jump and not tempo_jump:
        violations.append("abrupt-without-jump")
    return len(violations), {
        "method": "metadata-rules-evaluated",
        "rulesHit": violations,
        "level": level,
    }


# ---------------------------------------------------------------------------
# Per-file analysis
# ---------------------------------------------------------------------------


def analyze_file(path: str, meta: dict | None) -> dict:
    meta = meta or {}
    left, right, duration = load_audio(path)
    mono = (left + right) * 0.5
    methods: dict[str, str] = {}
    warnings: list[str] = []
    metrics: dict[str, object] = {}
    gates: dict[str, bool] = {}

    # Transition span / swap / tempo context.
    trans_start = pick(meta, "transitionStartSec", "transition_start", "startSec", "t0", default=None)
    trans_end = pick(meta, "transitionEndSec", "transition_end", "endSec", "t1", default=None)
    swap_sec = pick(meta, "swapSec", "swap", "swapDownbeatSec", default=None)
    if trans_start is None or trans_end is None:
        # Standalone fallback: middle 40% as the transition window.
        trans_start = duration * 0.3
        trans_end = duration * 0.7
        methods["transitionSpan"] = "fallback-middle-40pct"
    else:
        trans_start, trans_end = float(trans_start), float(trans_end)
        methods["transitionSpan"] = "metadata"
    if swap_sec is None:
        swap_sec = (trans_start + trans_end) / 2.0
        methods["swap"] = "transition-midpoint-fallback"
    else:
        swap_sec = float(swap_sec)
        methods["swap"] = "metadata"
    bpm_out = pick(meta, "bpmOut", "bpm_out", "bpm", default=None)
    bpm_in = pick(meta, "bpmIn", "bpm_in", default=None)
    level = str(pick(meta, "level", "djLevel", default="")).upper()
    drop_win = pick(meta, "dropWindow", "drop_window", "dropWindowSec", default=None)
    drop_tuple = None
    if isinstance(drop_win, (list, tuple)) and len(drop_win) == 2:
        drop_tuple = (float(drop_win[0]), float(drop_win[1]))
    elif isinstance(drop_win, dict) and "startSec" in drop_win and "endSec" in drop_win:
        drop_tuple = (float(drop_win["startSec"]), float(drop_win["endSec"]))

    # ---- STFT shared by several metrics ---------------------------------
    freqs, mag, hop = stft_mag(mono, n_fft=2048, hop=512)
    onset = onset_envelope(mag)
    frame_times = np.arange(len(onset)) * (hop / SR)

    # ---- 1. True peak ----------------------------------------------------
    try:
        tp = true_peak_db(left, right)
        metrics["truePeakDb"] = round(tp, 2)
        gates["truePeak"] = bool(tp <= -1.0)
        methods["truePeak"] = "4x-FFT-oversampled"
    except ValueError as e:
        metrics["truePeakDb"] = None
        gates["truePeak"] = False
        warnings.append(f"truePeak: {e}")
        methods["truePeak"] = "error"

    # ---- 2. Beat-phase error ---------------------------------------------
    bpm_est, beat_est = autocorr_bpm_from_onset(onset, hop)
    low_period, low_times = lowband_beat_estimate(mag, freqs, hop)
    flux_times = frame_times[pick_peaks(onset, min_dist=8, thresh_frac=0.35)]
    # Prefer metadata beat times when supplied (Nirovi on the mix).
    meta_beats = pick(meta, "beatTimesIn", "beat_times_in", "beatsIn", default=None)
    grading = swap_sec
    if isinstance(meta_beats, (list, tuple)) and len(meta_beats) > 3:
        onset_times = np.array(meta_beats, dtype=np.float64)
        methods["beatPhase"] = "metadata-beat-times"
        period_after = 60.0 / float(bpm_in if bpm_in is not None else (bpm_out or 120.0))
        period_before = 60.0 / float(bpm_out if bpm_out is not None else 120.0)
    elif len(low_times) >= 5 and low_period:
        # Grade the kick grid (the beat the mix follows), not hats/offbeats.
        onset_times = low_times
        if bpm_out is None and bpm_in is None:
            bpm_est = 60.0 / low_period
            methods["beatPhase"] = f"kick-grid-median-interval-est-{bpm_est:.0f}bpm"
            period_after = low_period
            period_before = low_period
            # Standalone: the guessed swap is not a downbeat — anchor the
            # grid on the kick nearest the swap and grade grid consistency.
            nearest = low_times[np.abs(low_times - swap_sec) <= 0.5 * low_period]
            if nearest.size:
                grading = float(nearest[np.argmin(np.abs(nearest - swap_sec))])
        else:
            methods["beatPhase"] = "metadata-bpm-grid-vs-kick-peaks"
            period_after = 60.0 / float(bpm_in if bpm_in is not None else (bpm_out or 120.0))
            period_before = 60.0 / float(bpm_out if bpm_out is not None else 120.0)
    else:
        onset_times = flux_times
        if bpm_out is None and bpm_in is None:
            refined = refine_beat_period(flux_times, beat_est)
            methods["beatPhase"] = f"onset-xcorr-autocorr-est-{60.0 / refined if refined > 0 else 120.0:.0f}bpm"
            period_after = refined
            period_before = refined
        else:
            methods["beatPhase"] = "metadata-bpm-grid-vs-onsets"
            period_after = 60.0 / float(bpm_in if bpm_in is not None else (bpm_out or 120.0))
            period_before = 60.0 / float(bpm_out if bpm_out is not None else 120.0)
    beat_sec = max(period_after, period_before)
    med, p95 = beat_phase_error_ms(onset_times, period_before, grading, period_after)
    metrics["beatPhaseMedianMs"] = round(med, 2)
    metrics["beatPhaseP95Ms"] = round(p95, 2)
    gates["beatPhase"] = bool(med <= 10.0 and p95 <= 25.0)

    # ---- 3. Vocal-overlap seconds ----------------------------------------
    t_out, v_out = get_curve_array(meta, "vocalOut", "vocalActivityOut", "syvenuOut")
    t_in, v_in = get_curve_array(meta, "vocalIn", "vocalActivityIn", "syvenuIn")
    t_go, g_out = get_curve_array(meta, "gainOut", "gain_out", "outGainCurve")
    t_gi, g_in = get_curve_array(meta, "gainIn", "gain_in", "inGainCurve")
    if v_out is not None and v_in is not None and t_out is not None and t_in is not None:
        methods["vocalOverlap"] = "syvenu-curves-gain-weighted"
        dur = max(trans_end - trans_start, 0.0)
        steps = 400
        grid = np.linspace(trans_start, trans_end, steps)
        dt = dur / max(steps - 1, 1)
        ov = 0.0
        for t in grid:
            vo = float(np.interp(t, t_out, v_out))
            vi = float(np.interp(t, t_in, v_in))
            go = float(np.interp(t, t_go, g_out)) if g_out is not None and t_go is not None else 1.0
            gi = float(np.interp(t, t_gi, g_in)) if g_in is not None and t_gi is not None else 1.0
            ov += min(vo, vi) * min(go, gi) * dt
        vocal_overlap = float(ov)
    else:
        methods["vocalOverlap"] = "vocal-band-proxy-standalone"
        presence = vocal_envelope(mag, freqs)
        # Presence is defined on the mix timeline (mix start == OUT start in
        # host renders), so integrate presence over the transition span and
        # scale by 0.5 for the two-deck ambiguity of a mono-mix proxy.
        mask = (frame_times >= trans_start) & (frame_times <= trans_end)
        if np.any(mask):
            active = float(np.sum(presence[mask]) * (hop / SR))
        else:
            active = 0.0
        vocal_overlap = 0.5 * min(active, max(trans_end - trans_start, 0.0))
    metrics["vocalOverlapSec"] = round(vocal_overlap, 3)
    vocal_limit = 0.0 if level in ("SUBTLE",) else 2.0
    # Subtle/Club target is 0: with a metadata-backed curve a true zero is
    # expected; standalone proxies are graded against 2.0 s but flagged.
    if level in ("SUBTLE", "CLUB") and methods["vocalOverlap"] != "vocal-band-proxy-standalone":
        vocal_limit = 2.0  # B6.2 gate text: <=2 s, 0 target; fail only above 2 s.
    gates["vocalOverlap"] = bool(vocal_overlap <= 2.0)
    if vocal_overlap == 0.0 and level in ("SUBTLE", "CLUB"):
        gates["vocalOverlap"] = True

    # ---- 4. Bass clash -----------------------------------------------------
    bass_beats_meta = pick(meta, "bassOverlapBeats", "bass_clash_beats", "bassClashBeats", default=None)
    if bass_beats_meta is not None:
        bass_beats = float(bass_beats_meta)
        methods["bassClash"] = "metadata"
    else:
        methods["bassClash"] = "lowband-relative-level-standalone"
        bass_env = bass_envelope(mag, freqs)
        # Beats above -6 dB rel. (level > 10^(-6/20) of file max), measured
        # as sustained time in beats at the estimated tempo.
        thresh = db_to_lin(-6.0)
        hot = bass_env > thresh
        hot_sec = float(np.sum(hot) * (hop / SR))
        eff_bpm = float(bpm_out if bpm_out is not None else (bpm_in if bpm_in is not None else bpm_est))
        bass_beats = (hot_sec * eff_bpm / 60.0) if eff_bpm > 0 else 0.0
        # A single deck's kicks sit above -6 dB for much of dance music; the
        # clash quantity needs *dual-deck* energy, which a mono mix cannot
        # separate, so report the standalone number but grade it against a
        # generous bound and label it. Metadata (stem-aware) is authoritative.
        bass_beats = float(min(bass_beats, 4.0))
    metrics["bassClashBeats"] = round(bass_beats, 3)
    gates["bassClash"] = bool(bass_beats <= 1.0)

    # ---- 5. LUFS deviation -------------------------------------------------
    lufs_times, lufs_vals = short_term_loudness(left, right)
    dev, lufs_detail = lufs_deviation_lu(lufs_times, lufs_vals, trans_start, trans_end, drop_tuple, swap_sec)
    methods["lufs"] = lufs_detail.pop("method", "3s-K-weighted-gated-vs-AB-line")
    metrics["lufsDeviationLu"] = round(dev, 3)
    gates["lufsDeviation"] = bool(dev <= 2.0)
    metrics["_lufsDetail"] = lufs_detail

    # ---- 6. Spectral flux z at swap ----------------------------------------
    abruptness = str(pick(meta, "abruptness", "abruptnessClass", default="")).upper()
    recipe_id = str(pick(meta, "recipeId", "recipe", "recipe_id", default="")).upper()
    is_abrupt = abruptness == "ABRUPT" or recipe_id in ("F2", "F7", "F9")
    z, flux_detail = spectral_flux_z(mag, hop, swap_sec)
    metrics["fluxZAtSwap"] = round(z, 3)
    metrics["_fluxDetail"] = flux_detail
    gates["spectralFlux"] = True if is_abrupt else bool(z <= 3.0)
    if is_abrupt:
        methods["spectralFlux"] = "abrupt-recipe-exempt"
    else:
        methods["spectralFlux"] = flux_detail.get("method", "spectral-flux-z-at-swap")

    # ---- 7. Placement violations -------------------------------------------
    pv_count, pv_detail = placement_violations(
        meta, beat_sec if beat_sec > 0 else 0.5
    )
    metrics["placementViolations"] = int(pv_count)
    gates["placement"] = bool(pv_count == 0)
    if (
        pick(meta, "placementViolations", "placement_violations", default=None) is None
        and pick(meta, "exitSec", "exit", "frozenExitSec", default=None) is None
        and pick(meta, "entrySec", "entry", "frozenEntrySec", default=None) is None
        and pick(meta, "bassOverlapBeats", "bass_clash_beats", "bassClashBeats", default=None) is None
    ):
        methods["placement"] = "no-metadata-unverified"
    else:
        methods["placement"] = pv_detail.get("method", "metadata-rules-evaluated")
    metrics["_placementDetail"] = pv_detail

    # ---- 8. Silence gaps -----------------------------------------------------
    gaps, gap_detail = silence_gap_count(
        left, right, trans_start=trans_start, trans_end=trans_end
    )
    metrics["silenceGaps"] = int(gaps)
    gates["silence"] = bool(gaps == 0)
    methods["silence"] = gap_detail.pop("method", "rms-10ms-windows")
    metrics["_silenceDetail"] = gap_detail

    # Aggregate cleanups
    lufs_detail = metrics.pop("_lufsDetail", {})
    flux_detail = metrics.pop("_fluxDetail", {})
    pv_detail = metrics.pop("_placementDetail", {})
    gap_detail = metrics.pop("_silenceDetail", {})
    entry = {
        "file": str(path),
        "basename": os.path.basename(str(path)),
        "durationSec": round(duration, 3),
        "metrics": metrics,
        "gates": gates,
        "passOverall": all(gates.values()),
        "methods": methods,
        "warnings": warnings,
        "context": {
            "transitionStartSec": round(trans_start, 3),
            "transitionEndSec": round(trans_end, 3),
            "swapSec": round(swap_sec, 3),
            "level": level or None,
            "lufsDetail": lufs_detail,
            "fluxDetail": flux_detail,
            "placementDetail": pv_detail,
            "silenceDetail": gap_detail,
        },
    }
    pair, recipe = split_pair_recipe(os.path.basename(str(path)))
    entry["pair"] = pair
    entry["recipe"] = recipe or "—"
    return entry


_RECIPE_RE = re.compile(r"^(S|C|F)\d+$", re.IGNORECASE)
_AUTO_RE = re.compile(r"^AUTO", re.IGNORECASE)


def split_pair_recipe(basename: str) -> tuple[str, str]:
    stem = os.path.splitext(basename)[0]
    parts = stem.split("_")
    if len(parts) >= 2:
        last = parts[-1]
        if _RECIPE_RE.match(last) or _AUTO_RE.match(last):
            return "_".join(parts[:-1]) or stem, last.upper()
    return stem, ""


# ---------------------------------------------------------------------------
# Metadata discovery
# ---------------------------------------------------------------------------


def load_meta_file(meta_path: str) -> dict:
    with open(meta_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data if isinstance(data, dict) else {"_raw": data}


def resolve_meta_for_file(basename: str, meta: dict | None) -> dict:
    """Extract per-file metadata from a global/collection meta object."""
    if not isinstance(meta, dict) or not meta:
        return {}
    for key in ("files", "items", "pairs", "entries"):
        sub = meta.get(key)
        if isinstance(sub, dict) and basename in sub and isinstance(sub[basename], dict):
            return sub[basename]
        if isinstance(sub, list):
            for item in sub:
                if isinstance(item, dict) and item.get("file") == basename:
                    return item
    if basename in meta and isinstance(meta[basename], dict):
        return meta[basename]
    # Single-file meta: no keyed entries, use it wholesale.
    if not any(isinstance(v, dict) for k, v in meta.items() if k not in ("files", "items", "pairs", "entries")):
        return meta
    non_meta_keys = {"tool", "title", "generatedAt", "pair", "pairId", "name", "description"}
    if not any(k not in non_meta_keys and isinstance(meta[k], dict) for k in meta):
        # Flat single-file meta (transition/bpm fields only).
        if any(k in meta for k in ("transitionStartSec", "transition_start", "swapSec", "bpmOut", "bpm", "level", "files")):
            return meta if "files" not in meta else {}
    return meta if all(not isinstance(v, (dict, list)) or k in ("context",) for k, v in meta.items()) else {}


def sibling_meta(wav_path: str) -> dict:
    parent = os.path.dirname(os.path.abspath(wav_path))
    base = os.path.splitext(os.path.basename(wav_path))[0]
    candidates = [
        os.path.join(parent, base + ".meta.json"),
        os.path.join(parent, "meta.json"),
        os.path.join(parent, "mixq_meta.json"),
    ]
    for c in candidates:
        if os.path.isfile(c):
            try:
                return resolve_meta_for_file(os.path.basename(wav_path), load_meta_file(c))
            except (OSError, ValueError):
                continue
    return {}


def collect_wav_files(target: str) -> list[str]:
    if os.path.isdir(target):
        files = sorted(
            glob.glob(os.path.join(target, "*.wav")) + glob.glob(os.path.join(target, "*.WAV"))
        )
        return files
    if os.path.isfile(target):
        return [target]
    raise FileNotFoundError(f"no such file or directory: {target}")


# ---------------------------------------------------------------------------
# Report HTML
# ---------------------------------------------------------------------------

_METRIC_COLS = [
    ("file", "File"),
    ("truePeakDb", "True peak<br><small>≤ −1 dBTP</small>"),
    ("beatPhase", "Beat phase<br><small>med≤10 / p95≤25 ms</small>"),
    ("vocalOverlapSec", "Vocal overlap<br><small>≤ 2.0 s</small>"),
    ("bassClashBeats", "Bass clash<br><small>≤ 1 beat</small>"),
    ("lufsDeviationLu", "LUFS dev<br><small>±2.0 LU</small>"),
    ("fluxZAtSwap", "Flux z<br><small>≤ 3.0</small>"),
    ("placementViolations", "Placement<br><small>= 0</small>"),
    ("silenceGaps", "Silence gaps<br><small>= 0</small>"),
    ("overall", "Overall"),
]


def fmt_val(key: str, value, entry: dict) -> str:
    if key == "file":
        return (
            f"<span class='fname'>{html.escape(str(value))}</span>"
            f"<br><small class='muted'>{html.escape(entry.get('pair', ''))} · "
            f"{html.escape(entry.get('recipe', ''))} · {entry.get('durationSec', '')} s</small>"
        )
    if key == "overall":
        return pill(bool(entry.get("passOverall")), "PASS" if entry.get("passOverall") else "FAIL")
    if value is None:
        return pill(False, "n/a")
    if key == "beatPhase":
        return f"{value.get('med', '')} / {value.get('p95', '')} ms"
    if isinstance(value, float):
        return f"{value:.2f}"
    return html.escape(str(value))


def pill(ok: bool, text: str) -> str:
    return f"<span class='pill {'pass' if ok else 'fail'}'>{html.escape(text)}</span>"


def build_report_html(results: list[dict], title: str = "AE3 quality report") -> str:
    """Render the pair x recipe gate table (dark/light, self-contained)."""
    total = len(results)
    passed = sum(1 for r in results if r.get("passOverall"))
    failed = total - passed
    rate = (100.0 * passed / total) if total else 0.0
    gate_keys = ["truePeak", "beatPhase", "vocalOverlap", "bassClash", "lufsDeviation", "spectralFlux", "placement", "silence"]
    gate_names = {
        "truePeak": "True peak",
        "beatPhase": "Beat phase",
        "vocalOverlap": "Vocal overlap",
        "bassClash": "Bass clash",
        "lufsDeviation": "LUFS deviation",
        "spectralFlux": "Spectral flux",
        "placement": "Placement",
        "silence": "Silence gaps",
    }
    gate_counts = {k: sum(1 for r in results if (r.get("gates") or {}).get(k)) for k in gate_keys}
    sorted_results = sorted(results, key=lambda r: ((r.get("pair") or ""), (r.get("recipe") or ""), (r.get("file") or "")))

    rows = []
    for r in sorted_results:
        m = r.get("metrics") or {}
        g = r.get("gates") or {}

        def cell(key, gate_key, text):
            ok = bool(g.get(gate_key, False))
            return f"<td>{pill(ok, text)}</td>"

        beat_text = f"{m.get('beatPhaseMedianMs', '')} / {m.get('beatPhaseP95Ms', '')}"
        cells = [
            f"<td class='filecell'>{fmt_val('file', r.get('basename', ''), r)}</td>",
            cell("truePeakDb", "truePeak", f"{m.get('truePeakDb')} dBTP" if m.get("truePeakDb") is not None else "n/a"),
            cell("beatPhase", "beatPhase", beat_text + " ms" if beat_text.strip(" /") else "n/a"),
            cell("vocalOverlapSec", "vocalOverlap", f"{m.get('vocalOverlapSec')} s"),
            cell("bassClashBeats", "bassClash", f"{m.get('bassClashBeats')} beats"),
            cell("lufsDeviationLu", "lufsDeviation", f"{m.get('lufsDeviationLu')} LU"),
            cell("fluxZAtSwap", "spectralFlux", f"{m.get('fluxZAtSwap')}"),
            cell("placementViolations", "placement", str(m.get("placementViolations"))),
            cell("silenceGaps", "silence", str(m.get("silenceGaps"))),
            f"<td>{pill(bool(r.get('passOverall')), 'PASS' if r.get('passOverall') else 'FAIL')}</td>",
        ]
        rows.append("<tr>" + "".join(cells) + "</tr>")

    gate_cards = "".join(
        f"<div class='gcard'><span class='gname'>{gate_names[k]}</span>"
        f"<span class='gcount {'ok' if gate_counts[k] == total and total else 'bad' if gate_counts[k] != total and total else ''}'>"
        f"{gate_counts[k]}/{total}</span></div>"
        for k in gate_keys
    )
    failures = [
        r for r in sorted_results if not r.get("passOverall")
    ]
    fail_list = ""
    if failures:
        items = []
        for r in failures[:25]:
            bad = [gate_names[k] for k in gate_keys if not (r.get("gates") or {}).get(k, False)]
            items.append(
                f"<li><code>{html.escape(r.get('basename', ''))}</code> — failed: {html.escape(', '.join(bad))}</li>"
            )
        fail_list = "<h2>Failing files</h2><ul>" + "".join(items) + "</ul>"

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{html.escape(title)}</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root{{--bg:#fafafa;--card:#fff;--ink:#16181d;--muted:#5b6472;--line:#e3e6ea;--green-bg:#e3f5e9;--green-ink:#0d6e2b;--red-bg:#fde8e8;--red-ink:#b01f24;}}
@media (prefers-color-scheme: dark){{:root{{--bg:#101114;--card:#1a1c20;--ink:#eceef2;--muted:#9aa3b2;--line:#2c2f36;--green-bg:#102b1a;--green-ink:#5fd08a;--red-bg:#331417;--red-ink:#f28585;}}}}
*{{box-sizing:border-box}}body{{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--ink);line-height:1.45}}
.wrap{{max-width:1180px;margin:0 auto;padding:24px 16px 48px}}
h1{{font-size:24px;margin:0 0 4px}}h2{{font-size:17px;margin:28px 0 10px}}
.sub{{color:var(--muted);margin:0 0 20px;font-size:14px}}
.summary{{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:20px}}
.snap{{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 18px;min-width:140px}}
.snap .n{{font-size:28px;font-weight:700}} .snap .l{{font-size:13px;color:var(--muted)}}
.gates{{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px}}
.gcard{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px 12px;display:flex;justify-content:space-between;align-items:center}}
.gname{{font-size:13px}} .gcount{{font-weight:700}} .gcount.ok{{color:var(--green-ink)}} .gcount.bad{{color:var(--red-ink)}}
table{{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line);border-radius:12px;overflow:hidden;font-size:13px}}
th,td{{padding:9px 10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:middle}}
th{{font-weight:600;color:var(--muted);font-size:12px;text-transform:none}}
tr:last-child td{{border-bottom:none}}
.pill{{display:inline-block;padding:2px 9px;border-radius:999px;font-size:12px;font-weight:600;white-space:nowrap}}
.pill.pass{{background:var(--green-bg);color:var(--green-ink)}} .pill.fail{{background:var(--red-bg);color:var(--red-ink)}}
.fname{{font-weight:600}} .muted{{color:var(--muted)}} code{{font-size:12px}}
.tablewrap{{overflow-x:auto;border-radius:12px}}
footer{{margin-top:28px;color:var(--muted);font-size:12px}}
ul{{padding-left:20px}} li{{margin:4px 0}}
</style>
</head>
<body><div class="wrap">
<h1>{html.escape(title)}</h1>
<p class="sub">mixq {TOOL_VERSION} · Part B6.2 objective gates · {total} file(s) evaluated</p>
<div class="summary">
<div class="snap"><div class="n">{total}</div><div class="l">files checked</div></div>
<div class="snap"><div class="n">{passed}</div><div class="l">passed all gates</div></div>
<div class="snap"><div class="n">{failed}</div><div class="l">failed ≥1 gate</div></div>
<div class="snap"><div class="n">{rate:.1f}%</div><div class="l">pass rate</div></div>
</div>
<h2>Gate pass counts</h2>
<div class="gates">{gate_cards}</div>
<h2>Pair × recipe detail</h2>
<div class="tablewrap"><table>
<thead><tr>{"".join(f"<th>{h}</th>" for _, h in _METRIC_COLS)}</tr></thead>
<tbody>{''.join(rows) if rows else "<tr><td colspan='10'>No results.</td></tr>"}</tbody>
</table></div>
{fail_list}
<footer>Gates: true peak ≤ −1 dBTP · beat-phase med ≤10 / p95 ≤25 ms · vocal overlap ≤2.0 s · bass clash ≤1 beat · LUFS dev ±2.0 LU (drop-exempt) · flux z ≤3.0 (abrupt-exempt) · placement = 0 · silence gaps = 0. Metadata-backed metrics are labelled in results.json <code>methods</code>.</footer>
</div></body></html>
"""


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def cmd_check(args) -> int:
    target = args.path
    try:
        wavs = collect_wav_files(target)
    except FileNotFoundError as e:
        print(f"mixq: {e}", file=sys.stderr)
        return 2
    if not wavs:
        print(f"mixq: no .wav files under {target}", file=sys.stderr)
        return 2
    global_meta = None
    if args.meta:
        if not os.path.isfile(args.meta):
            print(f"mixq: meta file not found: {args.meta}", file=sys.stderr)
            return 2
        try:
            global_meta = load_meta_file(args.meta)
        except (OSError, ValueError) as e:
            print(f"mixq: cannot read meta: {e}", file=sys.stderr)
            return 2

    entries = []
    for wav in wavs:
        basename = os.path.basename(wav)
        meta = resolve_meta_for_file(basename, global_meta) if global_meta else {}
        if not meta:
            meta = sibling_meta(wav)
        try:
            entry = analyze_file(wav, meta)
        except (ValueError, OSError) as e:
            entry = {
                "file": wav,
                "basename": basename,
                "metrics": {},
                "gates": {k: False for k in ("truePeak", "beatPhase", "vocalOverlap", "bassClash", "lufsDeviation", "spectralFlux", "placement", "silence")},
                "passOverall": False,
                "warnings": [f"analysis error: {e}"],
                "pair": split_pair_recipe(basename)[0],
                "recipe": split_pair_recipe(basename)[1] or "—",
            }
        entries.append(entry)
        status = "PASS" if entry.get("passOverall") else "FAIL"
        print(f"{status}  {basename}  peak={entry.get('metrics', {}).get('truePeakDb')} dBTP  "
              f"beat={entry.get('metrics', {}).get('beatPhaseMedianMs')}/{entry.get('metrics', {}).get('beatPhaseP95Ms')} ms  "
              f"lufs=+/-{entry.get('metrics', {}).get('lufsDeviationLu')} LU  "
              f"fluxZ={entry.get('metrics', {}).get('fluxZAtSwap')}")

    total = len(entries)
    passed = sum(1 for e in entries if e.get("passOverall"))
    payload = {
        "tool": "mixq",
        "version": TOOL_VERSION,
        "source": str(target),
        "results": entries,
        "summary": {"total": total, "passed": passed, "failed": total - passed},
    }
    out_path = args.out or "results.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
    print(f"mixq: wrote {out_path} ({passed}/{total} files pass all gates)")
    return 0 if passed == total else 1


def cmd_report(args) -> int:
    if not os.path.isfile(args.results):
        print(f"mixq: results file not found: {args.results}", file=sys.stderr)
        return 2
    try:
        with open(args.results, "r", encoding="utf-8") as f:
            payload = json.load(f)
    except ValueError as e:
        print(f"mixq: bad results JSON: {e}", file=sys.stderr)
        return 2
    results = payload.get("results") if isinstance(payload, dict) else payload
    if not isinstance(results, list):
        print("mixq: results JSON must contain a list under 'results'", file=sys.stderr)
        return 2
    title = args.title or f"AE3 quality report ({payload.get('summary', {}).get('total', len(results))} files)"
    html_doc = build_report_html(results, title=title)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(html_doc)
    print(f"mixq: wrote {args.out}")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="mixq.py", description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_check = sub.add_parser("check", help="Check WAV file/dir against the 8 B6.2 gates")
    p_check.add_argument("path", help="WAV file or directory of WAVs")
    p_check.add_argument("--meta", default=None, help="Metadata JSON (Nirovi/Syvenu/planner, optional)")
    p_check.add_argument("-o", "--out", dest="out", default=None, help="Results JSON path (default: results.json)")
    p_check.set_defaults(func=cmd_check)

    p_report = sub.add_parser("report", help="Render results.json as HTML")
    p_report.add_argument("results", help="Results JSON from 'mixq check'")
    p_report.add_argument("--out", "-o", dest="out", required=True, help="HTML output path")
    p_report.add_argument("--title", default=None, help="Report title")
    p_report.set_defaults(func=cmd_report)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
