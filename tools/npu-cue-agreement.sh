#!/usr/bin/env bash
# Run AFTER npu-cue-tests.sh, through cue_job, in the isolated CUE NPU worktree.
# CPU frontend/inference and optimized-op inventories only; never builds ORT/APK or accesses a device.
set -euo pipefail
ROOT=/workspace/diagnostics/automix-npu-qnn-2026-11-18
EV=${1:-$ROOT/vc84-cpu-agreement}
VENV=$ROOT/venv-ort131
test "$(hostname)" = 2887726280f4 || { echo "CUE host guard failed" >&2; exit 64; }
test "$(pwd -P)" = /workspace/src/bitchord-mix/.slim/worktrees/automix-npu-swap || exit 64
mkdir -p "$EV"
trap 'rc=$?; printf "%s\n" "$rc" > "$EV/exit"; date -u +%FT%TZ > "$EV/finished-at.txt"' EXIT
date -u +%FT%TZ > "$EV/started-at.txt"

# Syntax-check the Android-only pre-gate without building/linking an APK.
CLANG=/opt/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang++
JNI_CMD=("$CLANG" -std=c++17 -fsyntax-only app/src/main/cpp/jni/npu_jni.cpp)
{ printf '%q ' "${JNI_CMD[@]}"; printf '\n'; } > "$EV/jni-command.txt"
set +e
"${JNI_CMD[@]}" > "$EV/jni-syntax.log" 2>&1
jni_status=$?
set -e
printf '%s\n' "$jni_status" > "$EV/jni-syntax.exit"
test "$jni_status" = 0 || { cat "$EV/jni-syntax.log"; exit "$jni_status"; }

COMPILE=(g++ -std=c++17 -O2 -I native/analyzer tools/npu-beat-mel-probe.cpp
    native/analyzer/mel_spectrogram.cpp native/analyzer/resampler.cpp -o "$EV/mel-probe")
{ printf '%q ' "${COMPILE[@]}"; printf '\n'; } > "$EV/compile-command.txt"
set +e
"${COMPILE[@]}" > "$EV/compile.log" 2>&1
compile_status=$?
set -e
printf '%s\n' "$compile_status" > "$EV/compile.exit"
test "$compile_status" = 0 || { cat "$EV/compile.log"; exit "$compile_status"; }

CPU=("$VENV/bin/python" tools/npu-beat-cpu-agreement.py --wav "$ROOT/stutter_demuffle_playback.wav"
    --assets app/src/main/assets --mel-probe "$EV/mel-probe" --output "$EV/real-mel" --report "$ROOT/REAL_MEL_REPORT.md")
OPS=("$VENV/bin/python" npu-addon/regenerate-ops.py --assets app/src/main/assets
    --output "$ROOT/vc84-optimized-ops" --config npu-addon/ops.config)
{ printf '%q ' "${CPU[@]}"; printf '\n'; } > "$EV/cpu-command.txt"
{ printf '%q ' "${OPS[@]}"; printf '\n'; } > "$EV/ops-command.txt"
set +e
"${CPU[@]}" > "$EV/cpu-agreement.log" 2>&1
cpu_status=$?
printf '%s\n' "$cpu_status" > "$EV/cpu-agreement.exit"
# Inventory regeneration is safe even if behavioral agreement fails; this is
# NOT an ORT build. The failed gate still prevents enabling/building the candidate.
"${OPS[@]}" > "$EV/ops-regeneration.log" 2>&1
ops_status=$?
printf '%s\n' "$ops_status" > "$EV/ops-regeneration.exit"
set -e
sha256sum app/src/main/assets/*onnx npu-addon/ops.config > "$EV/models-and-ops.sha256"
printf 'jni-syntax=%s frontend-compile=%s CPU-agreement=%s ops-regeneration=%s\n' "$jni_status" "$compile_status" "$cpu_status" "$ops_status"
cat "$EV/cpu-agreement.log"
tail -n 20 "$EV/ops-regeneration.log"
test "$ops_status" = 0 || exit "$ops_status"
exit "$cpu_status"
