# Bundled NPU stack (vc90)

Mirrors Orchard mobile/android/npu-addon: the Maven onnxruntime-android AAR
carries no QNN execution provider, so BitChord builds ONNX Runtime 1.31.0
from source with the QNN EP linked in, and the QAIRT HTP libraries ride
along in the add-on build (see Packaging).

Unlike Orchard there is no separate download: the QNN-capable ORT build and
the QAIRT HTP libraries are produced here (see `build-ort.sh`).

## Full-ORT QNN build (required)

`build-ort.sh` builds ONNX Runtime 1.31.0 (checkout at the Orchard pin
`402979ac1bef89dcd55add41eaa7277c407599eb`) with the QNN execution provider
linked in via `--use_qnn static_lib`, from a **full** ONNX Runtime source tree.

`--include_ops_by_config` reduced builds MUST NOT be used unless the ops list
is regenerated from post-optimization `.ort` artifacts. The vc69 / vc70 reduced
builds dropped the ops ORT's optimizer introduces at load time
(`MatMulIntegerToFloat`, `DynamicQuantizeMatMul`), so both bundled models
failed on both backends; the full build keeps every operator ORT supports.

## QAIRT 2.51 library set

The Qualcomm zip is 2.8 GB; only these files are needed:

- lib/aarch64-android/libQnnHtp.so, libQnnSystem.so, libQnnHtpPrepare.so,
  libQnnHtpV73Stub.so
- lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so
- include/QNN/ for the ORT build

Download: https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.51.0.260929/v2.51.0.260929.zip

## Packaging

The QNN-capable ORT build must ship as a **separate signed add-on APK** built
in CI (`useLegacyPackaging = true`). It must NEVER be placed inside the main
APK: the main APK keeps exactly one `libonnxruntime.so` (34208672 B) and zero
QNN/HTP `.so` files, which `tools/audit-apk.sh` enforces.

## Runtime

Only Qualcomm devices attempt QNN (`NpuSupport.detectVendor` +
bundled backend path). Everything else, and any QNN open failure, runs on the
CPU execution provider: the NPU is an optimization, never a requirement.

Log tag BitChordNPU in logcat: init prints the SoC string, the bundled
backend path, and which Skel file(s) are present in nativeLibraryDir, so
the device proves the resolved arch; a QNN session failure prints a
warning through AnalysisLog.

## Beat QDQ export

`../npu-addon/export_qdq_beat.py` converts the BeatThis ONNX to static QDQ
INT8 at a fixed 6 s and 10 s shape (never 30 s -- that variant collapsed
downbeat F1 to ~32%) with uint16 activations / uint8 weights, and runs a
parity evaluation against the FP32 baseline (fail if the F1 delta exceeds 2
points).
