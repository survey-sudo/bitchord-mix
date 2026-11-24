# Dormant NPU research directory (djmix52)

## Status: not built, not packaged, not reachable

djmix52 ships exactly one inference runtime: the official CPU execution
provider from `com.microsoft.onnxruntime:onnxruntime-android:1.31.0`. This
directory is kept on disk for future work and is deliberately **not** a
`jniLibs` source set in either `app/build.gradle.kts` or `tv/build.gradle.kts`,
so nothing here can reach an APK by accident.

Three independent gates hold the NPU route closed in this release, and all
three must change before anything below becomes live:

| Gate | Location | Value |
| --- | --- | --- |
| `NpuRuntime.RUNTIME_AVAILABLE` | shared `NpuRuntime.kt` | `false` |
| `NpuSupport.QNN_ENABLED` | shared `NpuSupport.kt` | `false` |
| `AutomixV2Npu.enabled` | shared `NpuRuntime.kt` | `false` |

`NpuRuntime.runtimeEnabled()` returns `false` immediately while
`RUNTIME_AVAILABLE` is `false`, so no policy, persisted opt-in, SoC detection or
self-test verdict can activate a QNN session. QDQ resolution also
refuses to resolve (unpack) a QDQ file behind the same gate, and
`NpuNative.nativeInitAdspPath` (see `app/src/main/cpp/jni/npu_jni.cpp`) is a
no-op returning `JNI_FALSE` — no `dlopen`, no `setenv`, no DSP search path.

The CPU paths are the shipped behaviour: `NpuSession.openCpu` builds an
`OrtSession.SessionOptions` with the official Java API — intra-op threads
clamped to 4, one inter-op thread, `ALL_OPT`, CPU arena allocator and memory
pattern optimisation on, and no NNAPI or vendor delegate.

## What is here

- `build-ort.sh` — builds ONNX Runtime from source with `--use_qnn static_lib`.
  Not run for djmix52; the app takes the Maven AAR instead.
- `export_qdq_beat.py` — converts the Beat This! ONNX to static QDQ INT8 at a
  fixed 6 s and 10 s shape (never 30 s: that variant collapsed downbeat F1 to
  ~32%) with uint16 activations / uint8 weights, and runs a parity evaluation
  against the FP32 baseline (fails if the F1 delta exceeds 2 points).
- `ops.config` — the reduced operator list used by the source build. **Must not**
  be used unless regenerated from post-optimization `.ort` artifacts: the vc69 /
  vc70 reduced builds dropped `MatMulIntegerToFloat` and `DynamicQuantizeMatMul`,
  which ORT's optimizer introduces at load time, so both models failed on both
  backends. A full build keeps every operator ORT supports.

## Requirements for a future NPU release

1. A separately licensed Qualcomm AI Runtime (QAIRT) and its redistributable
   library set: `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpPrepare.so`,
   `libQnnHtpV73Stub.so`, `lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so`.
   Qualcomm owns these; review their terms before any redistribution.
2. A full (not reduced) ORT build with the QNN execution provider, produced
   from a checkout matching the pinned ORT version.
3. Verified QDQ weights delivered through the model coordinator with a pinned
   SHA-256, never as an unrestored APK asset.
4. A native runner implemented against the official ORT C API
   (`OrtGetApiBase` / `OrtApi::CreateSession`), not a copied third-party runner.
5. Flip the three gates above **in the same change** that reinstates the audit
   expectations in `tools/audit-apk.sh`, which currently requires exactly one
   `libonnxruntime.so`, zero QNN `.so` files under `lib/`, and zero `.onnx`
   files anywhere in the APK.

Until all five are done, `tools/audit-apk.sh` fails any APK that carries a QNN
library or an embedded model, which is the intended safety net.
