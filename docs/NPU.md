# AE3 NPU

How AE3 runs inference fast: the `ModelRegistry` + `InferenceBackend` layer, the Orchard Hexagon V73
add-on, the dlopen bridge, self-test parity rules, and measured numbers. Spec: Part A §6, Part B4,
ADR 0003. Source of truth on device: Settings → Automix → **NPU status row** and the analysis log line
`ep=QNN` (or why not).

## `ModelRegistry` and `models.json`

- Manifest: `app/src/main/assets/models.json` (`schema = 1`, top-level `minEngine = "3.0.0"`),
  overridable by a newer one in a models release. Code: `app/src/main/java/com/music/bitchord/playback/djmix/npu/ModelRegistry.kt`.
- Each entry: `id`, `version`, `fileName`, `bytes`, `sha256` (or `"checksumStatus": "addon-verified-pending"`
  for add-on assets), `source` (`asset:…` or `addon-asset:…`), `frontend` (e.g.
  `beat-mel-22k-1024-441-128` — 22,050 Hz / FFT 1024 / hop 441 / 128 Slaney 30–11,000 Hz bins),
  `io` (input/output names, shapes, dtypes), `backends`, `role`, `minEngine`.

| id | fileName | backends | role |
|---|---|---|---|
| `nirovi-cpu` | `nirovi_int8.ort` (7,948,784 B, sha256 `7454d98a…387`) | `cpu-ort` | beat |
| `nirovi-qnn` | `nirovi_qnn.onnx` (from the add-on) | `qnn-v73` | beat |
| `syvenu-cpu` | `syvenu.ort` (2,349,304 B) | `cpu-ort` | vocal |
| `syvenu-qnn` | `syvenu_qnn.onnx` (from the add-on) | `qnn-v73` | vocal |
| `scnet-dyn-int8` | `scnet_dyn_int8.onnx` | `cpu-ort` | stems |
| `scnet-int8` | `scnet.int8.onnx` | `cpu-ort` | stems fallback |
| `vocals-umxhq-int8` | `vocals_umxhq_int8.onnx` | `cpu-ort` | vocal (2-stem render fallback only) |
| `beat-this-q8` | `beat_this_final0_q8.onnx` | `cpu-ort` | beat fallback (downloaded on demand from `models-v1`) |

## `InferenceBackend` + backends

File: `app/src/main/java/com/music/bitchord/playback/djmix/npu/InferenceBackend.kt`.

```kotlin
interface InferenceBackend {
    val id: String                       // "cpu-ort", "qnn-v73"
    fun isAvailable(): Boolean
    fun open(model: ModelSpec): Session  // throws on failure; callers catch and fall back
    fun selfTest(model: ModelSpec): SelfTestResult
}
```

- **`CpuOrtBackend`** (`id = "cpu-ort"`): the app's bundled ONNX Runtime. Always available; always works
  offline.
- **`QnnAddonBackend`** (`id = "qnn-v73"`): only offered when the registry says the add-on is present and
  verified. `Session.run(...)` reports the real execution provider in `Session.ep` (`"QNN"` / `"CPU"`).

**Selection per model** (`ModelRegistry.select`): QNN is used only when the add-on is installed, its
self-test **passed for this (device, app version, add-on version) fingerprint**, and its benchmark is
faster than CPU; otherwise CPU. The choice is visible in the Mix Inspector and Developer settings.
Reason strings: `ep=QNN` (`npu self-test passed, ep=QNN`), or `ep=CPU (add-on not installed)`,
`ep=CPU (self-test not run)`, `ep=CPU (self-test failed: …)`, `ep=CPU (self-test for another device)`,
and so on. CPU fallback on any failure, always.

## Orchard V73 add-on and the dlopen bridge

- Bridge: `app/src/main/cpp/jni/npu_bridge.cpp` dlopens **`liborchard_qnn.so`** from the add-on's
  `nativeLibraryDir` (resolved through `PackageManager`, never an arbitrary path). C ABI:

  ```c
  void* orchard_qnn_open (const char* modelPath, const char* cacheDir, char* errBuf, size_t errCap);
  int   orchard_qnn_run  (void* ctx, const char* inputName, const float* data, const int64_t* shape,
                          int rank, float* out, size_t outCap, size_t* outLen);
  void  orchard_qnn_close(void* ctx);
  ```

- Only **known package names** are accepted, in preference order (B14):
  1. `com.music.bitchord.loopfix.npu.v73` (ours, `npu-addon/v73`),
  2. `dev.sfg.orchard.npu.v73` (Orchard's).
  The bridge opens
  `nirovi_qnn.onnx` and `syvenu_qnn.onnx` from the add-on's assets, with a per-device context cache
  (`cacheDir`), so QNN context compilation happens once per (device, model).
- **Our add-on (`npu-addon/v73`)** is a code-less standalone Gradle project (not in the root
  `settings.gradle.kts`): `applicationId = com.music.bitchord.loopfix.npu.v73`, jniLibs from the QAIRT
  runtime plus the runner (starting from Orchard's add-on contents — Qualcomm licensing applies, see
  `npu-addon/v73/README.md`), assets = the QNN models, signed with the fork key. The manual
  `.github/workflows/npu-addon.yml` builds it per arch (arm64; v69/v75/v79 when a device needs it) and
  publishes to the `npu-v73-N` release with an `npu-addons.json` manifest. The app prefers ours and
  accepts Orchard's if ours isn't installed.
- First enable: a one-time **"Preparing NPU…"** notice; then the Settings row reads
  **"NPU: Ready (Hexagon V73)"** and the analysis log shows `ep=QNN`.

## Self-test parity rules

`NpuSelfTestRunner.kt` (persisted verdict in `npu_selftest.json`, keyed by fingerprint):

- deterministic mel window: `FRAMES = 1500` × `MEL_BINS = 128`, a beat pulse every 50th frame
  (same 22,050 Hz / hop 441 / 128-bin mel front end);
- CPU Nirovi vs QNN Nirovi on the same window;
- **beat-peak agreement ≥ 95 %**, where a QNN peak must fall **within ±1 frame**
  (`PEAK_TOLERANCE_FRAMES = 1`, `MIN_AGREEMENT = 0.95`);
- latency: median of 7 timed runs after 2 warmups (`TIMED_RUNS = 7`, `WARMUPS = 2`), per model, on both
  backends;
- the verdict (passed? agreement? medians? device/add-on fingerprint?) is what gates QNN selection;
  a failed or stale fingerprint forces CPU.

## Measured numbers

All device figures below are `[PENDING DEVICE RUN]` — the M11 phone run fills them and Part B4.4
requires: per-model latency on CPU and NPU (median of 7 after 2 warmups), cold compile seconds and
cached open ms, self-test agreement, thermal status at the start and end of a 10-minute analysis soak.

| Measurement | CPU | NPU (QNN V73) | Gate |
|---|---|---|---|
| Nirovi per-1500-frame window median ms | `[PENDING DEVICE RUN]` | `[PENDING DEVICE RUN]` | NPU < CPU |
| Syvenu per-1500-frame window median ms | `[PENDING DEVICE RUN]` | `[PENDING DEVICE RUN]` | NPU < CPU |
| Full 4-min track analysis ms (beats + vocals + structure) | `[PENDING DEVICE RUN]` | `[PENDING DEVICE RUN]` | ≤ 1.5 s CPU, NPU lower |
| Cold QNN context compile (s) / cached open (ms) | — | `[PENDING DEVICE RUN]` | compile once per device |
| Self-test beat-peak agreement | — | `[PENDING DEVICE RUN]` | ≥ 95 % within ±1 frame |
| Analysis soak (10 min) thermal start → end | — | `[PENDING DEVICE RUN]` | prep pauses at SEVERE, resumes at MODERATE |

## Troubleshooting

| Symptom | Evidence to read | Likely cause / fix |
|---|---|---|
| Settings row says `ep=CPU (add-on not installed)` | Automix settings, `logcat` `BitChordNPU` tag | Install the add-on from the `npu-v73-N` release (owner taps install; same ABI `arm64-v8a`). |
| `ep=CPU (self-test failed: …)` | the persisted `npu_selftest.json` verdict | QNN disagreement or crash during self-test; the app keeps CPU and reports the reason. Re-run the self-test from Developer settings after the add-on updates. |
| `ep=CPU (self-test for another device)` | fingerprint in `npu_selftest.json` | OS or add-on changed; re-run the self-test (Developer settings → run self-tests). |
| QNN slower than CPU | `mixq`-style benchmark inside the self-test medians | Selection rule falls back to CPU automatically; report it — a regression here is an NPU.md `Gate: NPU analyses faster than CPU` violation. |
| Analysis stalls and the mixer still mixes fine | telemetry `analysis.ms model=…` | Beat fallback `beat-this-q8` (downloaded on demand from `models-v1`) took over; check the models manifest and network. |
| First mix after install takes longer | one-time "Preparing NPU…" | QNN context compilation; cached per device afterwards. |

Tasks (`tools/npu/`): `quantize_qdq.py`, `parity.py`, `make_context.py`, `einsum_to_matmul.py`,
`export_beat_this.py` — the export/quantize/parity pipeline for the models above
(see `tools/npu/README.md`).
