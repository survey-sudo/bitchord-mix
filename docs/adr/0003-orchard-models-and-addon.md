# ADR 0003: Orchard models and add-on — Nirovi + Syvenu

## Context
djmix52 shipped no NPU backend: no `.ort` assets, no add-on, and analysis
still ran the UMX vocal LSTM. Beat This! (~1.35 s / 30 s chunk) and FP32
SCNet (~9.0 s / 11 s chunk) made next-track-known → READY too slow.

## Decision
Bundle `nirovi_int8.ort` and `syvenu.ort` as APK assets (CPU, offline).
BeatTracker switches to Nirovi (1500-frame, border 6, zero-pad, 2 threads);
Syvenu runs on the same beat spectrogram for vocal presence, replacing UMX
in analysis (UMX stays as the 2-stem render fallback). For NPU, use the
installed Orchard add-on (`dev.sfg.orchard.npu.v73`) via a dlopen bridge
and `nirovi_qnn.onnx` / `syvenu_qnn.onnx`, with a ≥95% beat-peak self-test
before switching to `ep=QNN`. CPU fallback on any failure.

## Consequences
Full-track analysis budget is ≤1.5 s CPU. NPU work stays gated on parity
plus latency. Our own add-on module ships later behind the same registry.

