# vc84 default-off beat NPU candidate — CUE validation

Task date: 2026-11-19. Base: `170328bed4b78eaa68e7fce739ba8b121e4aa38c`.
Branch: `automix/npu-swap-20261119`.

- Local source worktree: `/home/hfzhkn/Bitchord/work/bitchord-mix/.slim/worktrees/automix-npu-swap`.
- Isolated CUE worktree: `/workspace/src/bitchord-mix/.slim/worktrees/automix-npu-swap`.
- Evidence ROOT: `/workspace/diagnostics/automix-npu-qnn-2026-11-18`.
- CUE host: `2887726280f4`; JDK 21; Android SDK `/opt/android-sdk`; NDK `27.2.12479018`.

**Unit/JVM gate PASS; model-agreement gate FAIL; HTP gate NOT COLLECTED.**
Both `QNN_ENABLED` and `BEAT_QDQ_ENABLED` remain false. Version is 84 /
`2.2.3-djmix42`, but **no vc84 APK was built, signed, audited or installed**.
No ORT source/runtime rebuild, adb, phone exercise, or original-checkout reset
was performed. Source edits/transfers used the workstation; every compile,
test, inference, model conversion and validation ran on CUE.

## Durable execution and test totals

CUE MCP was not exposed in this subagent's tool catalog. Durable work used
the same `cue_job` backend over SSH: JSON on stdin to
`node /root/.cue-mcp/agent.mjs jobStart`, with results from `jobPoll` (and
the registered `cue_job_poll` MCP via stdio during a freeze). Two SSH 255
transport failures were recovered with the existing auto-wake path; failed
connections did not launch Gradle, and surviving jobs were not restarted.

Exact Gradle command, run by `tools/npu-cue-tests.sh` in the isolated CUE tree:

```sh
./gradlew :shared:jvmTest :app:testProdDebugUnitTest --max-workers=4 -Dorg.gradle.jvmargs=-Xmx5g
```

All passes preserved fresh XML, exit, command, source hashes and logs. The
runner uses `/workspace/diagnostics/automix-cue-gradle.lock` across lanes.

| Pass | Durable job | Exit | Evidence subdirectory under ROOT |
|---|---|---:|---|
| Initial implementation | `vc84-npu-tests-mwmmecc5-3893` | 0 | `vc84-cue-tests` |
| Final preflight guards | `vc84-npu-tests-final-mwmmv0hx-6c12` | 0 | `vc84-cue-tests-final` |
| Retained AGPL notices | `vc84-npu-validated-mwmn5g7d-8e08` | 0 | `vc84-cue-tests-validated` |
| Final API-26-safe named output access | `vc84-npu-api26-final-mwmnbrcv-e511` | 0 | `vc84-cue-tests-api26-final` |

Final pass: `BUILD SUCCESSFUL in 1m 14s`, 68 tasks (13 executed, 55 up-to-date).
Its CUE-clock timestamps are `2026-11-18T21:41:20Z` through
`2026-11-18T21:42:35Z` (recorded verbatim, not converted to the task timezone).

| XML module | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| shared | 65 | 528 | 0 | 0 | 0 |
| app | 135 | 1415 | 0 | 0 | 1 |
| total | 200 | 1943 | 0 | 0 | 1 |

New tests cover default-off switches, V73 provider options, strict probe
options, linker preflight truth table, padding/cropping, malformed outputs,
flag-off input/shape/logit equivalence to the frozen vc83 chunk loop, and
EPContext invalidation for model/runtime/QAIRT/arch/options/firmware changes.
The final named-head access uses Optional's API-24 Supplier overload, not
the newer no-argument Java-10 overload. The app's minSdk is 26; no Android-26
device execution is claimed.

## Real mel, native syntax and optimized operator inventory

Pinned CPU venv setup was durable job `vc84-npu-cpu-venv-mwmmgnrn-254f`, exit 0:

```sh
python3 -m venv /workspace/diagnostics/automix-npu-qnn-2026-11-18/venv-ort131
/workspace/diagnostics/automix-npu-qnn-2026-11-18/venv-ort131/bin/pip install numpy onnx onnxruntime==1.31.0
```

Combined diagnostic job `vc84-real-mel-and-ops-mwmmnjdk-e8ab` ran
`bash tools/npu-cue-agreement.sh`, exit **2** (behavioral agreement failure,
not transport failure). Exact constituent CUE commands and exits:

```sh
# Exit 0: syntax only; no Android binary linked or phone exercised.
/opt/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang++ -std=c++17 -fsyntax-only app/src/main/cpp/jni/npu_jni.cpp

# Exit 0: diagnostic frontend only, not ORT or APK.
g++ -std=c++17 -O2 -I native/analyzer tools/npu-beat-mel-probe.cpp native/analyzer/mel_spectrogram.cpp native/analyzer/resampler.cpp -o /workspace/diagnostics/automix-npu-qnn-2026-11-18/vc84-cpu-agreement/mel-probe

# Exit 2: completed inference and wrote the FAIL report.
/workspace/diagnostics/automix-npu-qnn-2026-11-18/venv-ort131/bin/python tools/npu-beat-cpu-agreement.py --wav /workspace/diagnostics/automix-npu-qnn-2026-11-18/stutter_demuffle_playback.wav --assets app/src/main/assets --mel-probe /workspace/diagnostics/automix-npu-qnn-2026-11-18/vc84-cpu-agreement/mel-probe --output /workspace/diagnostics/automix-npu-qnn-2026-11-18/vc84-cpu-agreement/real-mel --report /workspace/diagnostics/automix-npu-qnn-2026-11-18/REAL_MEL_REPORT.md

# Exit 0: optimized model/config generation, NOT an ORT runtime build.
/workspace/diagnostics/automix-npu-qnn-2026-11-18/venv-ort131/bin/python npu-addon/regenerate-ops.py --assets app/src/main/assets --output /workspace/diagnostics/automix-npu-qnn-2026-11-18/vc84-optimized-ops --config npu-addon/ops.config
```

The last command invokes ORT 1.31's official converter with
`--optimization_style Fixed Runtime --target_platform arm --save_optimized_onnx_model`.
Both modes converted all three models successfully. Generated config/graph
hashes and the exact inner command are in `vc84-optimized-ops/ops-union-manifest.json`.
The union retains vc83 CPU compatibility and adds QDQ plus optimized fused
kernels, including `DynamicQuantizeMatMul` and `MatMulIntegerToFloat`.

The real recording is 239.808s stereo 48 kHz; the actual native resampler/mel
produced 11991 × 128 frames at 50 fps. Both models ran on CPU only, nine
chunks each. Results:

- Beat peak F1 at ±1 frame: **0.805000** (322 matches, 398 reference / 402 candidate).
- Raw downbeat F1: **0.642534** (71 matches, 120 / 101).
- Snapped downbeat grid F1: **0.657407** (71 matches, 116 / 100).
- BPM: **116.028874** / **116.246886**, delta **0.218012**; median 4 beats/bar for both.
- Predeclared parity gates: beat and snapped downbeat F1 ≥ 0.95, BPM delta ≤ 0.5.

See [REAL_MEL_REPORT.md](REAL_MEL_REPORT.md). This is agreement to vc83 on
one real recording, not ground-truth MIR accuracy. A close BPM is not enough
to call these grids interchangeable. CUE CPU timings (12,103.6ms reference /
184.6ms candidate) are diagnostic only, not an HTP or phone throughput claim.

## Pinned data and remaining gate

| File | Bytes | SHA-256 |
|---|---:|---|
| `beat_this_final0_q8.onnx` (retained) | 23138816 | `7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd` |
| `nirovi_qnn.onnx` (new, default-off) | 8024820 | `703b4e5bb9acb8456a95715dbf738928403d6cc34b248b0a890312dcb8ca8439` |
| `vocals_umxhq_int8.onnx` (retained, CPU) | 9054206 | `a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb` |

- WAV SHA-256: `18e0f2ab24925444e7a722c73db26a9d74a96e7899f00a7e5a3fc646bab535c3`.
- Native mel SHA-256: `1865cbff13e6be9b5da800432fd51d378d3f8f495960194ba0dcbbb776f4d500`.
- `ops.config` SHA-256: `84b0eb722f885224b4bfb2cc348d26c94b89eb97864c433f4aab7a069bb7ae5c`.

CUE `git diff --check` passed, the JNI declaration was verified private
static/native, and the original CUE checkout stayed at `60b9734` with its
single preserved `DeclineOverlapRenderTest.kt` diff. No other lane was reset.

**Do not build/activate ORT for this candidate while parity fails.** A later,
user-authorized device gate still needs actual fixed-shape inference with
CPU fallback disabled and EPContext off, finite named outputs, verbose
QNN/HTP compute-node assignment (not CPU partitions), vendor HTP ARCH V73
logcat and a linker-visible libcdsprpc pass. Session open and Orchard's
separate V73 app succeeding do not establish BitChord dispatch.
