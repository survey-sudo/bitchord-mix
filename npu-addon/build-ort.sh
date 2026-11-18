#!/usr/bin/env bash
# Builds the QNN-capable ONNX Runtime the BitChord APK bundles (vc70 NPU).
#
# Mirrors Orchard mobile/android/npu-addon/build-ort.sh: ONNX Runtime 1.31.0
# (checkout at the Orchard pin 402979ac1bef89dcd55add41eaa7277c407599eb)
# with the QNN execution provider linked in, reduced to ops.config, then
# bundled with the QAIRT 2.51.0.260929 HTP libraries in prebuilt/jniLibs.
# The Maven AAR JNI glue (libonnxruntime4j_jni.so) links directly to
# OrtSessionOptionsAppendExecutionProvider_Nnapi with BIND_NOW, so the
# reduced lib must export it. BitChord does not use NNAPI (CPU + QNN only),
# so an Android stub in provider_registration.cc provides the symbol;
# the full NNAPI EP would duplicate NodeAttrHelper with statically-linked
# QNN and fail to link. ops.config limits ops, not EP symbols, so it is kept.
# app/build.gradle.kts adds that directory as a jniLibs source set and the
# local libonnxruntime.so wins over the AAR copy via jniLibs pickFirsts.
#
# Needs: ORT_SRC (onnxruntime v1.31.0 checkout), QNN_SDK (extracted QAIRT
# 2.51.0.260929 subset with lib/, include/), ANDROID_HOME with NDK
# 27.2.12479018 (same as app/build.gradle.kts and CUE), and a venv with
# cmake>=3.28,<4 ninja flatbuffers onnx. Do NOT build until REAL_MEL_REPORT.md passes.
set -euo pipefail
ORT_SRC=${ORT_SRC:?path to onnxruntime v1.31.0 checkout}
QNN_SDK=${QNN_SDK:?path to extracted qairt/2.51.0.260929}
SDK=${ANDROID_HOME:-$HOME/.local/share/android/sdk}
# The old recipe hard-coded 28.2, which CUE does not have. No silent SDK
# install: default to the app's pin, with an explicit path/version override.
NDK=${ANDROID_NDK_HOME:-$SDK/ndk/${ANDROID_NDK_VERSION:-27.2.12479018}}
test -f "$NDK/source.properties" || { echo "missing NDK: $NDK" >&2; exit 64; }
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-${XDG_CACHE_HOME:-$HOME/.cache}/bitchord-ort-qnn}
JNI=$HERE/prebuilt/jniLibs/arm64-v8a
ARCHES="v73 v75"
python "$ORT_SRC/tools/ci_build/build.py" --build_dir "$OUT" --config MinSizeRel --parallel "$(nproc)" \
  --skip_tests --build_shared_lib --android --android_abi arm64-v8a --android_api 31 \
  --android_sdk_path "$SDK" --android_ndk_path "$NDK" --disable_ml_ops --no_telemetry \
  --use_qnn static_lib --qnn_home "$QNN_SDK" --include_ops_by_config "$HERE/ops.config" \
  --compile_no_warning_as_error --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF
mkdir -p "$JNI"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
  -o "$JNI/libonnxruntime.so" "$OUT/MinSizeRel/libonnxruntime.so"
for lib in libQnnHtp.so libQnnSystem.so libQnnHtpPrepare.so; do
  cp "$QNN_SDK/lib/aarch64-android/$lib" "$JNI/"
done
for arch in $ARCHES; do
  A=${arch^^}
  cp "$QNN_SDK/lib/aarch64-android/libQnnHtp${A}Stub.so" "$JNI/"
  cp "$QNN_SDK/lib/hexagon-$arch/unsigned/libQnnHtp${A}Skel.so" "$JNI/"
done
echo "bundled NPU stack in $JNI:"
echo "total jniLibs bytes: $(du -sb "$JNI" | cut -f1)"
ls -lh "$JNI"
