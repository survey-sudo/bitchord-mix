#!/usr/bin/env python3
"""Track N / Kite -- training data preparation for the beat & downbeat student.

This file is 100% original Python written from published methods and public
dataset documentation.  It does not copy, translate or import any source from a
PolyForm-Shield-licensed project.

Responsibilities
----------------
1. Fetch the Beat This! log-mel spectrogram corpus (Zenodo record 13922116,
   CC BY 4.0) and the CPJKU/beat_this_annotations (MIT) beat/downbeat labels.
2. Hold out every GTZAN track for evaluation only (never for training).
3. Cache teacher logits from Beat This! final0 (MIT) via a user-supplied
   adapter; no teacher weights or teacher code are vendored here.
4. Emit fixed [1500, 128] mel windows ready for ``train_kite.py``.

Mel recipe (identical to BitChord's native analyzer):
  22050 Hz mono, n_fft 1024, hop 441 (50 fps), periodic Hann, reflect pad,
  |STFT| / sqrt(n_fft), 128 Slaney triangles 30..11000 Hz (unit peak),
  log1p(1000 * max(energy, 1e-10)), float32 [frames, 128].
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional, Sequence, Tuple

import numpy as np

# ---------------------------------------------------------------------------
# Front-end constants (must match Bitchord's mel.py / native analyzer)
# ---------------------------------------------------------------------------
SAMPLE_RATE = 22050
N_FFT = 1024
HOP = 441
FPS = SAMPLE_RATE / HOP  # 50.0 frames per second
MELS = 128
WINDOW_FRAMES = 1500  # 30 s at 50 fps
MEL_MIN_HZ = 30.0
MEL_MAX_HZ = 11000.0
LOG_MULTIPLIER = 1000.0
FLOOR = 1e-10

ZENODO_RECORD = "13922116"  # Beat This! spectrograms, CC BY 4.0
ANNOTATION_DATASET = "CPJKU/beat_this_annotations"  # MIT

GTZAN_GENRES = (
    "blues", "classical", "country", "disco", "hiphop",
    "jazz", "metal", "pop", "reggae", "rock",
)


# ---------------------------------------------------------------------------
# Mel front end (numpy, original implementation of the published recipe)
# ---------------------------------------------------------------------------
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


def mel_filterbank(sample_rate: int = SAMPLE_RATE) -> np.ndarray:
    """Dense [N_FFT//2+1, MELS] matrix; triangular filters with unit peak."""
    bins = N_FFT // 2 + 1
    mel_min, mel_max = hz_to_mel(MEL_MIN_HZ), hz_to_mel(MEL_MAX_HZ)
    edges = [
        mel_to_hz(mel_min + (mel_max - mel_min) * i / (MELS + 1))
        for i in range(MELS + 2)
    ]
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


_FB: Optional[np.ndarray] = None


def log_mel(samples: np.ndarray, sample_rate: int = SAMPLE_RATE) -> np.ndarray:
    """Mono float32 waveform -> float32 [frames, MELS] log-mel spectrogram."""
    global _FB
    if abs(sample_rate - SAMPLE_RATE) > 1.0:
        raise ValueError(f"resample to {SAMPLE_RATE} Hz first (got {sample_rate})")
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
        _FB = mel_filterbank()
    energy = spec @ _FB
    return np.log1p(LOG_MULTIPLIER * np.maximum(energy, FLOOR)).astype(np.float32)


def load_audio_mono(path: Path) -> Tuple[np.ndarray, int]:
    """Read any soundfile-supported audio as mono float32, keep native rate."""
    import soundfile as sf

    data, rate = sf.read(str(path), dtype="float32", always_2d=True)
    return data.mean(axis=1), int(rate)


def resample_to_22050(x: np.ndarray, rate: int) -> np.ndarray:
    if rate == SAMPLE_RATE:
        return x.astype(np.float32)
    from scipy.signal import resample_poly

    g = math.gcd(int(rate), SAMPLE_RATE)
    return resample_poly(x, SAMPLE_RATE // g, int(rate) // g).astype(np.float32)


# ---------------------------------------------------------------------------
# Provenance: corpus download + license ledger
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class Source:
    name: str
    url: str
    license: str
    use: str


SOURCES: Tuple[Source, ...] = (
    Source(
        "Beat This! log-mel spectrograms",
        f"https://zenodo.org/records/{ZENODO_RECORD}",
        "CC BY 4.0",
        "Kite training inputs",
    ),
    Source(
        "CPJKU/beat_this_annotations",
        f"https://huggingface.co/datasets/{ANNOTATION_DATASET}",
        "MIT",
        "Kite beat / downbeat labels",
    ),
    Source(
        "GTZAN",
        "https://huggingface.co/datasets/marsyas/gtzan",
        "research corpus; rights vary per track",
        "EVALUATION ONLY -- excluded from all training",
    ),
    Source(
        "Beat This! final0",
        "https://github.com/CPJKU/beat_this",
        "MIT",
        "Kite teacher (logits only; no code or weights vendored)",
    ),
)


def zenodo_file_list(record: str = ZENODO_RECORD, timeout: float = 30.0) -> List[Dict[str, Any]]:
    """Query the public Zenodo API; no auth needed for an open record."""
    import requests

    url = f"https://zenodo.org/api/records/{record}"
    resp = requests.get(url, timeout=timeout)
    resp.raise_for_status()
    files = resp.json().get("files", [])
    return [
        {
            "key": f["key"],
            "url": f["links"]["self"],
            "size": f.get("size", 0),
        }
        for f in files
    ]


def download_zenodo(
    dest: Path,
    record: str = ZENODO_RECORD,
    extensions: Sequence[str] = (".npy", ".npz", ".wav", ".flac", ".mp3", ".ogg"),
    max_bytes: Optional[int] = None,
    timeout: float = 60.0,
) -> List[Path]:
    """Stream-download the open CC BY 4.0 spectrogram corpus into ``dest``."""
    import requests
    from tqdm import tqdm

    dest.mkdir(parents=True, exist_ok=True)
    written: List[Path] = []
    total = 0
    for info in zenodo_file_list(record, timeout=timeout):
        name = info["key"]
        if extensions and not name.lower().endswith(tuple(extensions)):
            continue
        if max_bytes is not None and total + int(info["size"]) > max_bytes:
            break
        target = dest / name
        if target.exists() and target.stat().st_size == int(info["size"]):
            written.append(target)
            total += target.stat().st_size
            continue
        with requests.get(info["url"], stream=True, timeout=timeout) as resp:
            resp.raise_for_status()
            with target.open("wb") as fh, tqdm(
                total=int(info["size"]), desc=name, unit="B", unit_scale=True
            ) as bar:
                for chunk in resp.iter_content(chunk_size=1 << 20):
                    if chunk:
                        fh.write(chunk)
                        bar.update(len(chunk))
                        total += len(chunk)
        written.append(target)
    return written


# ---------------------------------------------------------------------------
# Annotations + GTZAN holdout
# ---------------------------------------------------------------------------
@dataclass
class BeatAnnotation:
    beat_times: np.ndarray
    downbeat_times: np.ndarray
    meter: int = 4


def _first_present(mapping: Dict[str, Any], names: Sequence[str]) -> Any:
    for n in names:
        if n in mapping and mapping[n] is not None:
            return mapping[n]
    return None


def load_annotations(cache_dir: Optional[Path] = None) -> Dict[str, BeatAnnotation]:
    """Load MIT-licensed beat/downbeat annotations.

    Preferred path is the official HuggingFace dataset loader.  Offline users
    can drop one JSON file per track into ``cache_dir`` with keys
    ``track_id``, ``beats``, ``downbeats`` (times in seconds).
    """
    out: Dict[str, BeatAnnotation] = {}
    try:
        from datasets import load_dataset  # type: ignore

        ds = load_dataset(ANNOTATION_DATASET, split="train")
        for row in ds:
            key = _first_present(
                row, ("track_id", "track", "id", "audio_path", "path", "filename")
            )
            beats = _first_present(row, ("beats", "beat_times", "beat"))
            downs = _first_present(row, ("downbeats", "downbeat_times", "downbeat"))
            if key is None or beats is None:
                continue
            tid = str(key)
            out[tid] = BeatAnnotation(
                beat_times=np.asarray(beats, dtype=np.float64).reshape(-1),
                downbeat_times=(
                    np.asarray(downs, dtype=np.float64).reshape(-1)
                    if downs is not None
                    else np.zeros(0, dtype=np.float64)
                ),
                meter=int(_first_present(row, ("meter", "meter_n", "time_signature")) or 4),
            )
        if out:
            return out
    except Exception as exc:  # pragma: no cover - depends on network/deps
        print(f"[kite] HF annotation loader unavailable ({exc}); trying local JSON")

    if cache_dir is not None:
        for path in sorted(Path(cache_dir).glob("*.json")):
            obj = json.loads(path.read_text())
            beats = _first_present(obj, ("beats", "beat_times", "beat"))
            downs = _first_present(obj, ("downbeats", "downbeat_times", "downbeat"))
            if beats is None:
                continue
            tid = str(_first_present(obj, ("track_id", "track", "id")) or path.stem)
            out[tid] = BeatAnnotation(
                beat_times=np.asarray(beats, dtype=np.float64).reshape(-1),
                downbeat_times=(
                    np.asarray(downs, dtype=np.float64).reshape(-1)
                    if downs is not None
                    else np.zeros(0, dtype=np.float64)
                ),
            )
    if not out:
        raise RuntimeError(
            "No beat annotations found. Install `datasets` with network access, "
            "or supply --annotations DIR containing JSON files with beats/downbeats."
        )
    return out


def canonical_track_id(track_id: Any) -> str:
    """Normalize a filename/annotation key for cross-source matching."""
    return Path(str(track_id)).stem.strip().lower().replace(" ", "_")


def is_gtzan(track_id: str) -> bool:
    """True for GTZAN names such as ``blues.00000`` / ``jazz.00042.wav``."""
    stem = Path(str(track_id)).name.lower()
    for genre in GTZAN_GENRES:
        if stem.startswith(genre + ".") or stem.startswith(genre + "_"):
            return True
    return False


def split_tracks(track_ids: Iterable[str]) -> Tuple[List[str], List[str]]:
    """Deterministic split: GTZAN is ALWAYS evaluation, never training."""
    train, holdout = [], []
    for tid in sorted(set(str(t) for t in track_ids)):
        (holdout if is_gtzan(tid) else train).append(tid)
    return train, holdout


def labels_to_frames(
    ann: BeatAnnotation,
    n_frames: int,
    sigma_frames: float = 1.2,
) -> np.ndarray:
    """Frame-level soft targets [n_frames, 2] for beat and downbeat."""
    labels = np.zeros((n_frames, 2), dtype=np.float32)
    offsets = np.arange(-3, 4, dtype=np.int64)

    def paint(times: np.ndarray, col: int) -> None:
        for t in times:
            centre = float(t) * FPS
            idx = int(round(centre))
            kernels = np.exp(-0.5 * ((idx + offsets - centre) / sigma_frames) ** 2)
            locs = idx + offsets  # integer frame indices
            valid = (locs >= 0) & (locs < n_frames)
            np.maximum(labels[locs[valid], col], kernels[valid], out=labels[locs[valid], col])

    paint(ann.beat_times, 0)
    paint(ann.downbeat_times if ann.downbeat_times.size else ann.beat_times[::4], 1)
    return labels


# ---------------------------------------------------------------------------
# Teacher logits (Beat This! final0, MIT) -- accessed only through an adapter
# ---------------------------------------------------------------------------
TeacherFn = Callable[[np.ndarray], Tuple[np.ndarray, np.ndarray]]


@dataclass
class TeacherResult:
    beat: np.ndarray
    downbeat: np.ndarray


def load_teacher(spec: Optional[str]) -> Optional[TeacherFn]:
    """Import a user-provided ``module.path:callable`` adapter.

    The callable receives one float32 ``[1500, 128]`` mel window and returns
    ``(beat_prob, downbeat_prob)`` each shaped ``[1500]`` in ``[0, 1]``.  This
    scaffold deliberately contains no Beat This! implementation; install the
    MIT-licensed project separately and wrap it in a tiny adapter.
    """
    if not spec:
        return None
    module_name, _, attr = spec.partition(":")
    if not module_name or not attr:
        raise ValueError("--teacher must be 'module.path:callable'")
    fn = getattr(importlib.import_module(module_name), attr)
    if not callable(fn):
        raise TypeError(f"{spec} is not callable")
    return fn


def cache_teacher_logits(
    windows: np.ndarray,
    teacher: TeacherFn,
    out_path: Path,
    batch_size: int = 1,
) -> Path:
    """Run the adapter over every window and cache [N, 1500, 2] float32 logits."""
    n = windows.shape[0]
    logits = np.zeros((n, WINDOW_FRAMES, 2), dtype=np.float32)
    for i in range(0, n, batch_size):
        for j in range(i, min(i + batch_size, n)):
            beat, down = teacher(windows[j])
            b = np.asarray(beat, dtype=np.float32).reshape(-1)
            d = np.asarray(down, dtype=np.float32).reshape(-1)
            if b.size != WINDOW_FRAMES or d.size != WINDOW_FRAMES:
                raise ValueError(
                    f"teacher adapter returned {b.size}/{d.size} frames; expected {WINDOW_FRAMES}"
                )
            logits[j, :, 0] = b
            logits[j, :, 1] = d
    out_path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(out_path, teacher_logits=logits)
    return out_path


def copy_precomputed_teacher(src_dir: Path, out_path: Path, n: int) -> Path:
    """Admit precomputed teacher .npz produced by the official final0 CLI."""
    files = sorted(Path(src_dir).glob("*.npz"))
    if len(files) < n:
        raise FileNotFoundError(
            f"need >= {n} teacher .npz files in {src_dir}, found {len(files)}"
        )
    stacked = []
    for p in files[:n]:
        arr = np.load(p)
        key = "teacher_logits" if "teacher_logits" in arr else sorted(arr.files)[0]
        stacked.append(np.asarray(arr[key], dtype=np.float32))
    logits = np.stack(stacked)
    if logits.ndim != 3 or logits.shape[1:] != (WINDOW_FRAMES, 2):
        raise ValueError(f"teacher logits must be [N,{WINDOW_FRAMES},2], got {logits.shape}")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(out_path, teacher_logits=logits)
    return out_path


# ---------------------------------------------------------------------------
# Windowing + manifest
# ---------------------------------------------------------------------------
def make_windows(
    mel: np.ndarray,
    hop: int = 750,
    start: int = 0,
) -> np.ndarray:
    """Crop a [T,128] spectrogram into [N,1500,128] float32 windows."""
    if mel.ndim != 2 or mel.shape[1] != MELS:
        raise ValueError(f"expected [T,{MELS}] mel, got {mel.shape}")
    if mel.shape[0] < WINDOW_FRAMES:
        return np.zeros((0, WINDOW_FRAMES, MELS), dtype=np.float32)
    ends = range(start + WINDOW_FRAMES, mel.shape[0] + 1, hop)
    return np.stack([mel[s - WINDOW_FRAMES:s] for s in ends]).astype(np.float32)


def load_spectrogram(path: Path) -> np.ndarray:
    """Load a Zenodo spectrogram (.npy/.npz) or an audio file -> [T,128] mel."""
    suffix = path.suffix.lower()
    if suffix == ".npy":
        return np.load(path).astype(np.float32)
    if suffix == ".npz":
        arr = np.load(path)
        key = arr.files[0]
        return np.asarray(arr[key], dtype=np.float32)
    x, rate = load_audio_mono(path)
    return log_mel(resample_to_22050(x, rate), SAMPLE_RATE)


def sha256(path: Path, limit: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with Path(path).open("rb") as fh:
        h.update(fh.read(limit))
    return h.hexdigest()


def write_manifest(path: Path, **extra: Any) -> None:
    payload = {
        "task": "kite-beat-downbeat-student",
        "mel": {
            "sample_rate": SAMPLE_RATE,
            "n_fft": N_FFT,
            "hop": HOP,
            "mels": MELS,
            "window_frames": WINDOW_FRAMES,
        },
        "sources": [s.__dict__ for s in SOURCES],
        "gtzan_policy": "evaluation only; excluded from training and teacher caching",
        **extra,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, help="output dataset directory")
    ap.add_argument("--corpus-dir", type=Path, help="local spectrogram/audio dir (skip download)")
    ap.add_argument("--zenodo-record", default=ZENODO_RECORD)
    ap.add_argument("--max-bytes", type=int, default=None)
    ap.add_argument("--annotations", type=Path, help="local annotation JSON dir")
    ap.add_argument("--teacher", help="module.path:callable Beat This! final0 adapter")
    ap.add_argument("--teacher-npz-dir", type=Path, help="precomputed teacher outputs")
    ap.add_argument("--window-hop", type=int, default=750)
    ap.add_argument("--self-test", action="store_true", help="numpy-only smoke test")
    args = ap.parse_args(argv)

    if args.self_test:
        fb = mel_filterbank()
        tone = (0.1 * np.sin(2 * np.pi * 440.0 * np.arange(SAMPLE_RATE) / SAMPLE_RATE)).astype(np.float32)
        mel = log_mel(tone)
        win = make_windows(np.tile(mel, (40, 1)), hop=args.window_hop)
        ann = BeatAnnotation(
            beat_times=np.arange(0.0, 3.0, 0.5),
            downbeat_times=np.arange(0.0, 3.0, 2.0),
        )
        labels = labels_to_frames(ann, WINDOW_FRAMES)
        assert win.shape[0] > 0 and win.shape[1:] == (WINDOW_FRAMES, MELS)
        print(f"[kite self-test] filterbank={fb.shape} mel={mel.shape} "
              f"windows={win.shape} labels={labels.shape}")
        print(f"[kite self-test] GTZAN split: {split_tracks(['blues.00000', 'jazz.00001', 'track_007'])}")
        return 0
    if args.out is None:
        ap.error("--out is required unless --self-test is given")

    args.out.mkdir(parents=True, exist_ok=True)
    corpus_dir = args.corpus_dir
    if corpus_dir is None:
        print(f"[kite] downloading Zenodo record {args.zenodo_record} (CC BY 4.0)")
        corpus_dir = args.out / "zenodo_ccby4"
        paths = download_zenodo(corpus_dir, args.zenodo_record, max_bytes=args.max_bytes)
    else:
        paths = sorted(p for p in Path(corpus_dir).iterdir() if p.is_file())

    annotations = load_annotations(args.annotations)
    ann_by_canonical = {canonical_track_id(k): v for k, v in annotations.items()}
    train_ids, holdout_ids = split_tracks(annotations.keys())
    print(f"[kite] train tracks={len(train_ids)} GTZAN holdout={len(holdout_ids)}")

    windows_list, labels_list, ids = [], [], []
    for path in paths:
        tid = path.stem
        if is_gtzan(tid):
            continue  # hard guarantee: GTZAN never enters training
        mel = load_spectrogram(path)
        if mel.shape[1] != MELS:
            continue
        ann = ann_by_canonical.get(canonical_track_id(tid))
        if ann is None:
            continue
        w = make_windows(mel, hop=args.window_hop)
        if w.shape[0] == 0:
            continue
        if ann.beat_times.size:
            lab = np.stack([labels_to_frames(ann, WINDOW_FRAMES)] * w.shape[0])
            labels_list.append(lab)
        windows_list.append(w)
        ids.extend([tid] * w.shape[0])

    if not windows_list:
        raise RuntimeError("no training windows assembled; check corpus/annotation paths")
    windows = np.concatenate(windows_list, axis=0)
    labels = np.concatenate(labels_list, axis=0) if labels_list else np.zeros(
        (windows.shape[0], WINDOW_FRAMES, 2), dtype=np.float32
    )
    np.savez_compressed(args.out / "windows.npz", windows=windows, labels=labels)

    if args.teacher_npz_dir is not None:
        copy_precomputed_teacher(args.teacher_npz_dir, args.out / "teacher_logits.npz", len(windows))
    else:
        teacher = load_teacher(args.teacher)
        if teacher is None:
            raise RuntimeError(
                "Kite needs teacher logits: pass --teacher module.path:callable "
                "(Beat This! final0, MIT) or --teacher-npz-dir DIR."
            )
        cache_teacher_logits(windows, teacher, args.out / "teacher_logits.npz")

    write_manifest(
        args.out / "manifest.json",
        corpus_files=len(paths),
        train_tracks=len(train_ids),
        gtzan_holdout_tracks=holdout_ids,
        train_windows=int(windows.shape[0]),
        corpus_sha256_sample=sha256(paths[0]) if paths else None,
    )
    print(f"[kite] wrote {windows.shape[0]} windows to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
