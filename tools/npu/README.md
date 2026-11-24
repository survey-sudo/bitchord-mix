# tools/npu — Beat This! on the Snapdragon Hexagon NPU (QNN HTP v73)

> **Dormant in djmix52.** This directory is research tooling, not part of the build.
> The release ships only the official CPU `onnxruntime-android:1.31.0`; there is no QNN
> runtime, no NPU model and no `assets/*.onnx` in the APK (see `npu-addon/README.md` and
> `NpuRuntime.RUNTIME_AVAILABLE`). **Do not write the outputs below into
> `app/src/main/assets/`** — `tools/audit-apk.sh` fails any APK containing a `.onnx` file.
> If a future licensed release revives this route, deliver the weights through the model
> coordinator with a pinned SHA-256 instead, and write them somewhere outside the source tree
> (the `build/` paths below already are).

Produces `beat_this_final0_qdq.onnx` (static QDQ, uint16 activations /
uint8 weights, fixed `[1, 500, 128]` input; **expected ~21–25 MB**) and, optionally,
`beat_this_final0_qdq_ctx.onnx` (HTP v73 / soc_model 68 precompiled EP context, ~25–40 MB).
Both are produced on a dev box (or the build agent), never in the app build. A QNN-capable
release would open only these two names on QNN; the dynamic-int8 `beat_this_final0_q8.onnx`
stays the CPU model (DynamicQuantizeLinear / MatMulInteger cannot run on HTP). SCNet and
Open-Unmix stay CPU-only (LSTM is not an HTP op).

```bash
cd tools/npu
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
mkdir -p build

# 1. FP32, fixed 500-frame (10 s) window, Einsum -> MatMul, parity vs torch (<1e-3)
python export_beat_this.py --frames 500 --out build/beat_this_final0_fp32_500.onnx

# 2. calibration chunks from REAL music (mel identical to native/analyzer/mel_spectrogram.cpp)
python make_calibration.py --audio ~/Music/calib --frames 500 --per-track 4 --out build/calib_500.npz

# 3. QNN QDQ (qnn_preprocess_model + get_qnn_qdq_config, QUInt16 act / QUInt8 weights,
#    u8 override on one operand of every activation x activation MatMul)
python quantize_qdq.py --model build/beat_this_final0_fp32_500.onnx --calib build/calib_500.npz \
    --out build/beat_this_final0_qdq.onnx

# 4. parity gate on HELD-OUT tracks: beat / downbeat F drop <= 2 points vs FP32
python parity.py --fp32 build/beat_this_final0_fp32_500.onnx \
    --qdq build/beat_this_final0_qdq.onnx --audio ~/Music/holdout

# 5. OPTIONAL (Windows, an onnxruntime-qnn build matching the target QAIRT): offline v73 context
python make_context.py --qdq build/beat_this_final0_qdq.onnx \
    --out build/beat_this_final0_qdq_ctx.onnx
```

Window length: 500 frames (10 s) by default; 300 (6 s) is the fallback. vc91 notes report that a
1500-frame (30 s) QDQ variant collapsed downbeat F1. That result came from vc91's
`npu-addon/export_qdq_beat.py`, which is a scaffold: its hand-made QDQ nodes have invalid
attributes, it pins dim 2 (mels) instead of dim 1 (time), and it evaluates on zero input.
This pipeline replaces it. Re-measure 1500 with `--frames 1500` before trusting either number.

On the phone, `NpuQdqSelfTest` would run this model on QNN with `session.disable_cpu_ep_fallback=1`
and on CPU, then log one line:
`BitChordNPU selftest ep=QNN ms=… cpuMs=… maxAbsDiff=… verdict=PASS|SLOW|MISMATCH|QNN_FAILED`.
That probe is dormant in djmix52 (it returns at `NpuRuntime.runtimeEnabled()`), and production
beat analysis uses the CPU execution provider unconditionally.

If parity fails: add `--per-channel`, use more or more varied calibration audio, or keep the
model on CPU (just don't ship the `_qdq` asset). The app needs no code change for any of these.
