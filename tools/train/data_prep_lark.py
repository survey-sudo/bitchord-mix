#!/usr/bin/env python3
"""Track N / Lark -- training data preparation for the vocal-presence student.

100% original Python.  No code from any PolyForm-Shield-licensed project is
used; no research-only separation dataset is ever downloaded or loaded.

License policy (enforced in code, not just documentation)
---------------------------------------------------------
  ALLOW : FMA-full tracks whose metadata license is CC-BY or CC0 only.
  REFUSE: FMA tracks with NC / ND / SA / non-commercial / research terms.
  REFUSE: MUSDB18-HQ and every other research-only corpus (hard guard below).

Lark learns the frame-level vocal energy share produced by UMX-HQ (MIT) on
commercially clean audio, then is distilled/quantized for on-device inference.
"""

from __future__ import annotations

import argparse
import importlib
import json
import math
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional, Sequence, Tuple

import numpy as np

from data_prep_kite import (
    HOP,
    MELS,
    N_FFT,
    SAMPLE_RATE,
    WINDOW_FRAMES,
    load_audio_mono,
    log_mel,
    make_windows,
    resample_to_22050,
)

# ---------------------------------------------------------------------------
# License policy -- hard constants
# ---------------------------------------------------------------------------
ALLOWED_LICENSES: Tuple[str, ...] = (
    "CC BY",
    "CC BY 3.0",
    "CC BY 4.0",
    "CC-BY",
    "CC-BY 3.0",
    "CC-BY 4.0",
    "CC0",
    "CC0 1.0",
    "PUBLIC DOMAIN",
)

DENY_TOKENS: Tuple[str, ...] = (
    "NC", "NONCOMMERCIAL", "NON-COMMERCIAL", "ND", "SA",
    "RESEARCH", "PERSONAL", "MUSDB",
)

REFUSED_DATASETS: Tuple[str, ...] = (
    "MUSDB18", "MUSDB18-HQ", "MUSDB18-7", "SDXF", "DSD100",
)

VocalTeacher = Callable[[np.ndarray], np.ndarray]


def assert_not_refused(dataset_name: str) -> None:
    """Fail immediately if a research-only corpus is requested."""
    name = dataset_name.strip().upper()
    for refused in REFUSED_DATASETS:
        if refused in name:
            raise PermissionError(
                f"REFUSED: {dataset_name!r} is research-only / non-commercial. "
                "Lark may be trained ONLY on commercially clean CC-BY / CC0 FMA-full audio."
            )


def normalize_license(value: Any) -> str:
    s = str(value or "").strip().upper()
    for a, b in (("-", " "), ("  ", " ")):
        s = s.replace(a, b)
    return s.strip()


def is_commercial_clean(license_value: Any) -> bool:
    """Strict allowlist: CC-BY / CC0 only; deny-list checked first."""
    s = normalize_license(license_value)
    if not s or s in {"NAN", "NONE", "NULL", "UNKNOWN"}:
        return False
    if any(tok in s for tok in DENY_TOKENS):
        return False
    return s in {normalize_license(x) for x in ALLOWED_LICENSES}


def _flatten_columns(cols: Sequence[Any]) -> List[str]:
    out = []
    for c in cols:
        if isinstance(c, tuple):
            out.append(".".join(str(x) for x in c if str(x) and not str(x).startswith("Unnamed")))
        else:
            out.append(str(c))
    return out


def filter_fma_tracks(tracks_csv: Path) -> Tuple[Any, Dict[str, int]]:
    """Read FMA ``tracks.csv`` and keep ONLY CC-BY / CC0 tracks."""
    import pandas as pd

    df = pd.read_csv(str(tracks_csv), header=[0, 1], index_col=0, low_memory=False)
    flat = _flatten_columns(df.columns)
    license_candidates = [
        "track.license", "track.License", "license", "License", "track.licence",
    ]
    license_col = next((c for c in license_candidates if c in flat), None)
    if license_col is None:
        raise RuntimeError(
            "could not find a license column in FMA tracks.csv; "
            f"flattened columns sample: {flat[:12]}"
        )
    df.columns = flat
    before = len(df)
    keep = df[license_col].map(is_commercial_clean)
    filtered = df.loc[keep, [license_col]].copy()
    filtered.index.name = "track_id"
    stats = {
        "tracks_before_filter": before,
        "tracks_cc_by_cc0_only": int(keep.sum()),
        "tracks_refused": int((~keep).sum()),
        "license_column": license_col,
    }
    return filtered, stats


def find_fma_audio(fma_root: Path, track_id: Any) -> Optional[Path]:
    """Locate an FMA-full audio file for a track id (id is zero-padded)."""
    root = Path(fma_root)
    candidates = []
    try:
        tid = int(track_id)
        for name in (f"{tid:06d}.mp3", f"{tid:06d}.wav", f"{tid:06d}.flac"):
            candidates += [root / "fma_full" / name, root / name]
    except (TypeError, ValueError):
        pass
    candidates.append(root / f"{track_id}")
    for p in candidates:
        if p.is_file():
            return p
    return None


# ---------------------------------------------------------------------------
# UMX-HQ (MIT) vocal-share teacher -- adapter only, no teacher code vendored
# ---------------------------------------------------------------------------
def load_vocal_teacher(spec: Optional[str]) -> Optional[VocalTeacher]:
    """Import ``module.path:callable(audio_22050_mono) -> vocal_22050_mono``."""
    if not spec:
        return None
    module_name, _, attr = spec.partition(":")
    if not module_name or not attr:
        raise ValueError("--teacher must be 'module.path:callable'")
    fn = getattr(importlib.import_module(module_name), attr)
    if not callable(fn):
        raise TypeError(f"{spec} is not callable")
    return fn


def vocal_share_target(mix: np.ndarray, vocal: np.ndarray) -> np.ndarray:
    """50 fps frame-level vocal energy share from a separated vocal stem.

    Uses centred windowed energy with N_FFT=1024 / hop=441, a short temporal
    smoother, and clips to [0,1].  Returns float32 [frames].
    """
    x = np.asarray(mix, dtype=np.float64).reshape(-1)
    v = np.asarray(vocal, dtype=np.float64).reshape(-1)
    n = min(x.size, v.size)
    if n < N_FFT:
        return np.zeros(0, dtype=np.float32)
    pad = N_FFT // 2
    xp = np.pad(x[:n], pad, mode="reflect")
    vp = np.pad(v[:n], pad, mode="reflect")
    frames = (xp.size - N_FFT) // HOP + 1
    idx = np.arange(N_FFT)[None, :] + HOP * np.arange(frames)[:, None]
    e_mix = np.sum(xp[idx] ** 2, axis=1)
    e_voc = np.sum(vp[idx] ** 2, axis=1)
    share = e_voc / np.maximum(e_mix, 1e-12)
    share = np.clip(share, 0.0, 1.0)
    k = 5
    smooth = np.convolve(np.pad(share, (k // 2, k // 2), mode="edge"),
                         np.ones(k) / k, mode="valid")
    return np.clip(smooth, 0.0, 1.0).astype(np.float32)


def window_targets(share: np.ndarray, hop: int = 750) -> np.ndarray:
    """Crop a [T] share curve into [N,1500] targets aligned with mel windows."""
    if share.ndim != 1 or share.size < WINDOW_FRAMES:
        return np.zeros((0, WINDOW_FRAMES), dtype=np.float32)
    return np.stack([
        share[i:i + WINDOW_FRAMES]
        for i in range(0, share.size - WINDOW_FRAMES + 1, hop)
    ]).astype(np.float32)


def load_stem(stems_dir: Path, track_id: Any) -> Optional[np.ndarray]:
    root = Path(stems_dir)
    for suffix in (".wav", ".flac", ".mp3", ".npy"):
        p = root / f"{track_id}{suffix}"
        if not p.is_file():
            try:
                p = root / f"{int(track_id):06d}{suffix}"
            except (TypeError, ValueError):
                pass
        if p.is_file():
            if p.suffix == ".npy":
                return np.load(p).astype(np.float32)
            audio, rate = load_audio_mono(p)
            return resample_to_22050(audio, rate)
    return None


# ---------------------------------------------------------------------------
# Dataset assembly
# ---------------------------------------------------------------------------
def write_manifest(path: Path, **extra: Any) -> None:
    payload = {
        "task": "lark-vocal-presence-student",
        "license_policy": {
            "allowed": list(ALLOWED_LICENSES),
            "deny_tokens": list(DENY_TOKENS),
            "refused_datasets": list(REFUSED_DATASETS),
            "note": "MUSDB18-HQ is REFUSED: research-only, not commercially clean.",
        },
        "teacher": "UMX-HQ (MIT), accessed through an adapter; no weights/code vendored",
        **extra,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path)
    ap.add_argument("--fma-root", type=Path, help="directory holding FMA-full audio")
    ap.add_argument("--tracks-csv", type=Path, help="FMA tracks.csv")
    ap.add_argument("--stems-dir", type=Path, help="precomputed UMX-HQ vocal stems")
    ap.add_argument("--teacher", help="module.path:callable UMX-HQ vocal adapter")
    ap.add_argument("--max-tracks", type=int, default=5000)
    ap.add_argument("--window-hop", type=int, default=750)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        for name in ("MUSDB18-HQ", "MUSDB18"):
            try:
                assert_not_refused(name)
            except PermissionError:
                pass
            else:
                raise AssertionError(f"{name} must be refused")
        assert is_commercial_clean("CC BY 4.0")
        assert is_commercial_clean("CC0")
        assert not is_commercial_clean("CC BY-NC-SA 4.0")
        assert not is_commercial_clean("CC BY-SA 4.0")
        mix = np.random.RandomState(0).randn(SAMPLE_RATE).astype(np.float32)
        vocal = 0.5 * mix
        share = vocal_share_target(mix, vocal)
        # energy share of a 0.5x stem is ~0.25; tile to exercise windowing
        windows = window_targets(np.tile(share, 40))
        print(f"[lark self-test] share={share.shape} mean={float(share.mean()):.3f} "
              f"windows={windows.shape}")
        assert 0.2 < float(share.mean()) < 0.3
        assert windows.shape[0] > 0 and windows.shape[1] == WINDOW_FRAMES
        return 0
    if args.out is None or args.tracks_csv is None:
        ap.error("--out and --tracks-csv are required unless --self-test is given")

    assert_not_refused(str(args.fma_root or ""))
    tracks, stats = filter_fma_tracks(args.tracks_csv)
    print(f"[lark] FMA CC-BY/CC0 filter: {stats['tracks_cc_by_cc0_only']} kept, "
          f"{stats['tracks_refused']} refused")

    teacher = load_vocal_teacher(args.teacher)
    if teacher is None and args.stems_dir is None:
        raise RuntimeError(
            "Lark needs UMX-HQ vocal targets: pass --teacher module.path:callable "
            "or --stems-dir DIR of precomputed vocal stems."
        )

    args.out.mkdir(parents=True, exist_ok=True)
    windows_all, targets_all, used_ids = [], [], []
    for track_id in list(tracks.index)[: args.max_tracks]:
        audio_path = find_fma_audio(Path(args.fma_root), track_id) if args.fma_root else None
        if audio_path is None:
            continue
        audio, rate = load_audio_mono(audio_path)
        audio = resample_to_22050(audio, rate)
        mel = log_mel(audio, SAMPLE_RATE)
        vocal = load_stem(args.stems_dir, track_id) if args.stems_dir else None
        if vocal is None and teacher is not None:
            vocal = teacher(audio)
        if vocal is None:
            continue
        vocal = resample_to_22050(np.asarray(vocal, dtype=np.float32), SAMPLE_RATE)
        share = vocal_share_target(audio, vocal[: audio.size])
        w = make_windows(mel, hop=args.window_hop)
        t = window_targets(share, hop=args.window_hop)
        if w.shape[0] == 0 or w.shape[0] != t.shape[0]:
            continue
        windows_all.append(w)
        targets_all.append(t)
        used_ids.append(str(track_id))
        if len(used_ids) >= args.max_tracks:
            break

    if not windows_all:
        raise RuntimeError("no Lark windows assembled; check --fma-root and stems/teacher")
    windows = np.concatenate(windows_all, axis=0)
    targets = np.concatenate(targets_all, axis=0)
    np.savez_compressed(
        args.out / "windows.npz", windows=windows, targets=targets,
        clip_ids=np.asarray(used_ids),
    )
    write_manifest(
        args.out / "manifest.json",
        filter_stats=stats,
        used_tracks=used_ids,
        train_windows=int(windows.shape[0]),
        mel={"sample_rate": SAMPLE_RATE, "n_fft": N_FFT, "hop": HOP, "mels": MELS},
    )
    print(f"[lark] wrote {windows.shape[0]} CC-BY/CC0 windows to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
