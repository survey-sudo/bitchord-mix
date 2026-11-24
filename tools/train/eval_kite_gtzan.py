#!/usr/bin/env python3
"""Track N / Kite -- GTZAN holdout evaluation gate (70 ms tolerance).

100% original code.  Uses ``mir_eval`` for beat/downbeat F1 and ONNX Runtime
for inference; contains no teacher implementation, only an ONNX/logit adapter.

Acceptance: student F1 must be within 0.01 of Beat This! final0 on the GTZAN
holdout at 70 ms for BOTH beat and downbeat; inference <= 100 ms per 30 s.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from data_prep_kite import FPS, WINDOW_FRAMES

TOLERANCE_S = 0.07
F1_DELTA_LIMIT = 0.01
INFER_LIMIT_MS = 100.0
HTP_COMPILE_LIMIT_S = 60.0


# ---------------------------------------------------------------------------
# ONNX inference
# ---------------------------------------------------------------------------
def _session(path: Path):
    import onnxruntime as ort

    opts = ort.SessionOptions()
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(str(path), opts, providers=["CPUExecutionProvider"])


def run_student(sess, window: np.ndarray, apply_sigmoid: bool = True) -> np.ndarray:
    """[1500,128] -> [1500,2] beat/downbeat probabilities.

    KiteTCN emits logits, so sigmoid is applied by default; pass
    ``apply_sigmoid=False`` only if the exported graph already includes it.
    """
    x = np.asarray(window, dtype=np.float32)[None]
    y = sess.run(None, {sess.get_inputs()[0].name: x})[0]
    y = np.asarray(y, dtype=np.float32)
    if y.shape[-1] == 1:
        y = np.concatenate([y, y], axis=-1)
    if y.ndim == 2 and y.shape[0] == 1:
        y = y[0]
    if apply_sigmoid:
        y = sigmoid(y)
    return np.clip(y[:, :2], 0.0, 1.0)


def run_teacher(sess, window: np.ndarray, apply_sigmoid: bool = False) -> np.ndarray:
    x = np.asarray(window, dtype=np.float32)[None]
    outputs = sess.run(None, {sess.get_inputs()[0].name: x})
    if len(outputs) >= 2:
        b = np.asarray(outputs[0], dtype=np.float32).reshape(-1)
        d = np.asarray(outputs[1], dtype=np.float32).reshape(-1)
    else:
        arr = np.asarray(outputs[0], dtype=np.float32)
        if arr.ndim == 3:
            b, d = arr[0, :, 0], arr[0, :, 1]
        else:
            b = d = arr.reshape(-1)
    stacked = np.stack([b, d], axis=-1)
    if apply_sigmoid:
        stacked = sigmoid(stacked)
    return np.clip(stacked, 0.0, 1.0)


def sigmoid(x: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(x, -50.0, 50.0)))


# ---------------------------------------------------------------------------
# Window stitching + peak picking + F1
# ---------------------------------------------------------------------------
def stitch(
    windows: np.ndarray,
    starts: np.ndarray,
    probs: np.ndarray,
) -> Tuple[np.ndarray, np.ndarray]:
    """Average overlapping window predictions into a per-clip frame timeline."""
    total = int(max(np.asarray(starts) + WINDOW_FRAMES))
    acc = np.zeros((total, 2), dtype=np.float64)
    count = np.zeros(total, dtype=np.float64)
    for s, p in zip(np.asarray(starts, dtype=int), probs):
        e = s + WINDOW_FRAMES
        acc[s:e] += p[: max(0, min(WINDOW_FRAMES, total - s))]
        count[s:e] += 1.0
    return acc / np.maximum(count[:, None], 1.0), count


def peak_times(prob: np.ndarray, threshold: float = 0.5,
               min_gap_s: float = 0.1) -> np.ndarray:
    """Non-maximum suppression on a 50 fps frame probability curve."""
    p = np.asarray(prob, dtype=np.float64)
    if p.size == 0:
        return np.zeros(0)
    pad = np.concatenate([[0.0], p, [0.0]])
    peaks = np.where((p >= threshold) & (p >= pad[:-2]) & (p > pad[2:]))[0]
    peaks = peaks[np.argsort(p[peaks])[::-1]]
    min_gap = max(1, int(round(min_gap_s * FPS)))
    kept: List[int] = []
    for idx in peaks:
        if all(abs(int(idx) - k) >= min_gap for k in kept):
            kept.append(int(idx))
    return np.sort(np.asarray(kept, dtype=np.float64)) / FPS


def f1_at_70ms(reference: Sequence[float], estimate: Sequence[float]) -> float:
    import mir_eval.beat

    ref, est = np.asarray(reference, dtype=float), np.asarray(estimate, dtype=float)
    if ref.size == 0 or est.size == 0:
        return 0.0
    score, _, _ = mir_eval.beat.f_measure(ref, est, TOLERANCE_S)
    return float(score)


def _load_eval_cache(path: Path) -> Dict[str, np.ndarray]:
    with np.load(str(path), allow_pickle=True) as z:
        return {k: z[k] for k in z.files}


def build_eval_index(cache: Dict[str, np.ndarray]) -> Dict[str, List[int]]:
    ids = cache.get("clip_ids")
    if ids is None:
        return {"all": list(range(len(cache["windows"])))}
    index: Dict[str, List[int]] = {}
    for i, clip in enumerate(np.asarray(ids)):
        index.setdefault(str(clip), []).append(i)
    return index


def _ref_times(cache: Dict[str, np.ndarray], idx: int, key: str) -> np.ndarray:
    arr = cache.get(key)
    if arr is None:
        return np.zeros(0)
    return np.asarray(arr[idx], dtype=float).reshape(-1)


# ---------------------------------------------------------------------------
# Gate
# ---------------------------------------------------------------------------
def evaluate(
    student_onnx: Path,
    teacher_onnx: Optional[Path],
    teacher_npz: Optional[Path],
    eval_cache: Path,
    out_report: Optional[Path],
    htp_compile_s: Optional[float],
    student_is_prob: bool = False,
    teacher_is_logit: bool = False,
) -> Dict[str, object]:
    cache = _load_eval_cache(eval_cache)
    windows = np.asarray(cache["windows"], dtype=np.float32)
    starts = np.asarray(
        cache.get("window_starts", np.zeros(len(windows), dtype=np.int64)),
        dtype=np.int64,
    )
    student = _session(student_onnx)
    teacher = _session(teacher_onnx) if teacher_onnx else None
    teacher_cache = np.load(str(teacher_npz))["teacher_logits"] if teacher_npz else None

    student_probs, teacher_probs = [], []
    t0 = time.perf_counter()
    for w in windows:
        student_probs.append(run_student(student, w, apply_sigmoid=not student_is_prob))
    infer_total_ms = (time.perf_counter() - t0) * 1000.0 / max(1, len(windows))
    for i, w in enumerate(windows):
        if teacher is not None:
            teacher_probs.append(run_teacher(teacher, w, apply_sigmoid=teacher_is_logit))
        elif teacher_cache is not None:
            teacher_probs.append(np.clip(teacher_cache[i], 0.0, 1.0))
        else:
            raise RuntimeError("need --teacher-onnx or --teacher-npz for the F1 gate")

    index = build_eval_index(cache)
    f1_rows: List[Dict[str, object]] = []
    beat_student, beat_teacher, down_student, down_teacher = [], [], [], []
    for clip, idxs in index.items():
        s_timeline, _ = stitch(windows, starts[idxs], np.stack([student_probs[i] for i in idxs]))
        t_timeline, _ = stitch(windows, starts[idxs], np.stack([teacher_probs[i] for i in idxs]))
        i0 = idxs[0]
        refs_b = _ref_times(cache, i0, "beat_times")
        refs_d = _ref_times(cache, i0, "downbeat_times")
        sb = f1_at_70ms(refs_b, peak_times(s_timeline[:, 0], 0.5))
        tb = f1_at_70ms(refs_b, peak_times(t_timeline[:, 0], 0.5))
        sd = f1_at_70ms(refs_d, peak_times(s_timeline[:, 1], 0.5))
        td = f1_at_70ms(refs_d, peak_times(t_timeline[:, 1], 0.5))
        f1_rows.append({"clip": clip, "beat_student": sb, "beat_teacher": tb,
                        "downbeat_student": sd, "downbeat_teacher": td})
        beat_student.append(sb); beat_teacher.append(tb)
        down_student.append(sd); down_teacher.append(td)

    def mean(xs: List[float]) -> float:
        return float(np.mean(xs)) if xs else 0.0

    d_beat = mean(beat_student) - mean(beat_teacher)
    d_down = mean(down_student) - mean(down_teacher)
    # "within 0.01" is two-sided: student must track final0, not drift away.
    f1_ok = abs(d_beat) <= F1_DELTA_LIMIT and abs(d_down) <= F1_DELTA_LIMIT
    infer_ok = infer_total_ms <= INFER_LIMIT_MS
    htp_ok = htp_compile_s is None or htp_compile_s <= HTP_COMPILE_LIMIT_S
    report: Dict[str, object] = {
        "tolerance_s": TOLERANCE_S,
        "clips": len(index),
        "mean_beat_f1_student": mean(beat_student),
        "mean_beat_f1_teacher": mean(beat_teacher),
        "mean_downbeat_f1_student": mean(down_student),
        "mean_downbeat_f1_teacher": mean(down_teacher),
        "delta_beat": d_beat,
        "delta_downbeat": d_down,
        "delta_limit": F1_DELTA_LIMIT,
        "infer_ms_per_30s": infer_total_ms,
        "infer_limit_ms": INFER_LIMIT_MS,
        "htp_compile_s": htp_compile_s,
        "htp_compile_limit_s": HTP_COMPILE_LIMIT_S,
        "gate": "PASS" if (f1_ok and infer_ok and htp_ok) else "FAIL",
        "clips_detail": f1_rows,
    }
    text = json.dumps(report, indent=2) + "\n"
    print(text)
    if out_report:
        out_report.parent.mkdir(parents=True, exist_ok=True)
        out_report.write_text(text)
    return report


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--eval-cache", type=Path, required=True,
                    help="NPZ: windows [N,1500,128], window_starts [N], clip_ids, *_times")
    ap.add_argument("--student", type=Path, required=True, help="kite_qnn.onnx")
    ap.add_argument("--teacher-onnx", type=Path, help="Beat This! final0 exported to ONNX")
    ap.add_argument("--teacher-npz", type=Path, help="cached teacher_logits.npz")
    ap.add_argument("--out", type=Path, help="write JSON report")
    ap.add_argument("--htp-compile-seconds", type=float,
                    help="measured QNN/HTP compile time; omit to skip that gate")
    ap.add_argument("--student-probs", action="store_true",
                    help="student ONNX already outputs probabilities (no sigmoid)")
    ap.add_argument("--teacher-logits", action="store_true",
                    help="teacher ONNX outputs logits (apply sigmoid)")
    args = ap.parse_args(argv)
    report = evaluate(args.student, args.teacher_onnx, args.teacher_npz,
                      args.eval_cache, args.out, args.htp_compile_seconds,
                      student_is_prob=args.student_probs,
                      teacher_is_logit=args.teacher_logits)
    return 0 if report["gate"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
