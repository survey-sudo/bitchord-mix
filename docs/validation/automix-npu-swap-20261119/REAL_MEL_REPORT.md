# BitChord vc84 real-mel CPU agreement — CUE only

**CPU agreement gate: FAIL**. QNN/beat-candidate flags remain OFF.
**HTP dispatch gate: NOT COLLECTED** (no phone/ADB/APK/ORT rebuild in this pass).

Host `2887726280f4`, Python ONNX Runtime `1.31.0`, CPUExecutionProvider only.
Fixture `stutter_demuffle_playback.wav`: 239.808000s, 2ch/48000Hz, SHA-256 `18e0f2ab24925444e7a722c73db26a9d74a96e7899f00a7e5a3fc646bab535c3`.
Mel: actual BitChord native resampler + frontend, 11991 × 128, 50 fps; short candidate chunks zero-pad to 1500 and crop back.

| Metric | Result | Predeclared gate |
|---|---:|---:|
| Beat peak F1 @ ±1 frame (20ms) | 0.805000 | ≥ 0.95 |
| Raw downbeat peak F1 @ ±1 frame | 0.642534 | diagnostic |
| Snapped downbeat grid F1 @ ±1 frame | 0.657407 | ≥ 0.95 |
| BPM delta | 0.218012 | ≤ 0.5 |

| Model | Beat peaks | Downbeat grid | BPM | Median beats/bar |
|---|---:|---:|---:|---:|
| beat_this_final0_q8.onnx | 398 | 116 | 116.028874 | 4 |
| nirovi_qnn.onnx | 402 | 100 | 116.246886 | 4 |

Beat/downbeat logit Pearson: 0.824884 / 0.684469.
Full grids, matched counts, per-chunk CPU timings, model hashes and logits: `/workspace/diagnostics/automix-npu-qnn-2026-11-18/vc84-cpu-agreement/real-mel`.

This is behavioral agreement to vc83, not accuracy against annotated beats; one playback recording is not a general MIR benchmark.
Nirovi is a different QDQ graph, not a quantization-only re-export of Beat This!; matching I/O alone is insufficient.

Before enabling QNN: collect an actual fixed-shape inference with `session.disable_cpu_ep_fallback=1`,
EPContext disabled, verbose ORT node-assignment evidence showing QNN/HTP compute (not CPU partitions),
and vendor HTP ARCH V73 logcat. Session creation, bundled Skel presence and Orchard Step A are not BitChord proof.
