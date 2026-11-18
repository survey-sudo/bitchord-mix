# Beat-only NPU candidate (vc84 / 2.2.3-djmix42)

**Dormant. No ORT/APK build or phone exercise in this pass.** Production still
ships the full Maven `onnxruntime-android:1.31.0` AAR; the prebuilt QNN directory
is **not** an app jniLibs source set. Do not drop Orchard's V73 add-on/APK into
BitChord: runtimes, model contracts and signatures are separate.

**2026-11-19 CUE result: CPU agreement FAIL.** Beat F1 at ±1 frame is 0.805000;
snapped downbeat-grid F1 is 0.657407 (predeclared minimum 0.95). BPM differs
by only 0.218012, but the grids are not interchangeable. Keep both flags OFF
and do not build/activate the QNN runtime for this candidate. See
`docs/validation/automix-npu-swap-20261119/REAL_MEL_REPORT.md` and the
authoritative CUE report under the diagnostics ROOT below.

## Model and runtime gates

- `NpuSupport.BEAT_QDQ_ENABLED=false` keeps `beat_this_final0_q8.onnx` and its
  original dynamic CPU chunk/crop path. Both beat assets remain bundled.
- The independent QDQ flag selects `nirovi_qnn.onnx` (8,024,820 bytes, copied
  from the staged Orchard model). It takes `input_spectrogram [1,1500,128]`
  and returns named `beat` / `downbeat [1,1500]`. Short chunks zero-pad to
  1500; only real frames are copied into the track grid. Matching I/O does
  **not** mean matching weights, architecture or beat behavior.
- `NpuSupport.QNN_ENABLED=false` still prevents QNN dlopen/session/probe work.
  Beat QNN is allowed only for the QDQ candidate. `vocals_umxhq_int8.onnx`
  remains on CPU, even if the beat path is later enabled; Syvenu is not used.
- The HTP provider explicitly uses `htp_arch=73`, burst mode and finalization
  mode 3. Orchard V73 Step A passed on the Poco F6; that is **not** BitChord
  HTP dispatch evidence.
- Before QAIRT opens, native preflight must `dlopen("libcdsprpc.so")` by
  soname in the app linker namespace. The manifest declaration is optional
  (`required=false`). A file in `/vendor` alone does not pass this gate.
  Failed preflight gives CPU fallback, not another HTP attempt.
- EPContext uses `beat-<SHA256>/ctx.onnx` under noBackupFilesDir. The key
  includes model bytes/I/O, ORT version and library bytes, QAIRT version and
  library bytes, HTP arch/options and Android firmware fingerprint. The
  strict probe bypasses EPContext and sets `session.disable_cpu_ep_fallback=1`;
  playback always keeps CPU fallback.

## CUE validation (before any source-built ORT)

Use the isolated CUE tree:
`/workspace/src/bitchord-mix/.slim/worktrees/automix-npu-swap`.
Run long commands through `cue_job` (SSH fallback: JSON on stdin to
`node /root/.cue-mcp/agent.mjs jobStart`), not a fragile SSH foreground build.
`tools/npu-cue-tests.sh` serializes Gradle with the other automix lanes and
retains its command, exit, source hashes, fresh XML and module totals.

The real-mel gate uses the already-staged 239.808s `stutter_demuffle_playback.wav`
on CUE. Compile **only the diagnostic frontend** on CUE:

```sh
g++ -std=c++17 -O2 -I native/analyzer tools/npu-beat-mel-probe.cpp \
  native/analyzer/mel_spectrogram.cpp native/analyzer/resampler.cpp \
  -o "$EV/mel-probe"
"$VENV/bin/python" tools/npu-beat-cpu-agreement.py \
  --wav "$ROOT/stutter_demuffle_playback.wav" --assets app/src/main/assets \
  --mel-probe "$EV/mel-probe" --output "$EV/real-mel" \
  --report "$ROOT/REAL_MEL_REPORT.md"
```

`ROOT=/workspace/diagnostics/automix-npu-qnn-2026-11-18`, and `VENV` is the
isolated `venv-ort131` under ROOT (numpy, onnx, onnxruntime==1.31.0).
The gate declares before inference: beat F1 ≥ 0.95 at ±1 frame, snapped
downbeat-grid F1 ≥ 0.95 at ±1 frame, BPM difference ≤ 0.5, nonempty valid
grids. It uses the actual native BitChord resampler/mel and Kotlin peak/tempo
rules. This is agreement to vc83 on one real recording, **not** annotated
ground-truth accuracy. A FAIL keeps the candidate/ORT build blocked; do not
relax thresholds or enable flags to hide disagreement.

## Reduced operators and NDK

On CUE, regenerate `ops.config` with:

```sh
"$VENV/bin/python" npu-addon/regenerate-ops.py --assets app/src/main/assets \
  --output "$ROOT/vc84-optimized-ops" --config npu-addon/ops.config
```

This invokes ORT 1.31's converter with **Fixed + Runtime optimization**, ARM
target and saved optimized ONNX/ORT graphs for both beat assets plus CPU
vocals. Union both generated configs (including optimizer-introduced
`MatMulIntegerToFloat` / `DynamicQuantizeMatMul`), retain vc83's CPU inventory,
and keep `MatMulInteger`, `DynamicQuantizeLinear`, `DynamicQuantizeLSTM`,
`DequantizeLinear` and `QuantizeLinear` compatibility guards. Do not generate
only from raw graphs: that caused the vc69 reduced-runtime regression.
The evidence manifest pins every model and optimized config/graph hash.
Regeneration is **not** an ORT build or permission to build it.

`build-ort.sh` now defaults to NDK **27.2.12479018**, matching the app and CUE.
The previous hard-coded 28.2.13676358 was absent on CUE. An explicit
`ANDROID_NDK_HOME` or `ANDROID_NDK_VERSION` override is supported; missing
NDK fails clearly, without installing a toolchain. The later recipe still
requires the pinned ORT 1.31 checkout and QAIRT 2.51.0.260929 SDK, cmake ≥3.28
and <4, ninja, flatbuffers and onnx. **Do not run it in this pass.**

## Later, user-authorized device gate

Before claiming BitChord NPU success, require all of:

1. Original QDQ graph probe, no cached context, CPU fallback disabled.
2. Actual inference with finite named `[1,1500]` beat/downbeat outputs.
3. Verbose ORT node-assignment log showing QNN/HTP compute, not CPU partitions.
4. Vendor HTP ARCH V73 logcat and successful linker-visible libcdsprpc gate.

Session creation, provider registration, bundled Skel names and Orchard's
separate app passing are not substitutes. No APK/sign/install/ADB is
authorized by this document or this candidate pass.
