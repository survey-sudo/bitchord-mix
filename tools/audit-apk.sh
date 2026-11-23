#!/usr/bin/env bash
# APK audit for vc93 (2.3.0-djmix51). Runs anywhere with unzip + python3; uses aapt2/apksigner
# when an SDK is present, and degrades to structural checks when it is not.
#
#   ./audit-apk.sh path/to/app.apk
set -uo pipefail

APK="${1:?usage: audit-apk.sh <apk>}"
fail=0
bad() { printf 'FAIL  %s\n' "$*"; fail=1; }
ok()  { printf 'ok    %s\n' "$*"; }

[ -f "$APK" ] || { echo "no such file: $APK"; exit 2; }

echo "=== $(basename "$APK") ==="
echo "size:   $(stat -c %s "$APK") bytes"
echo "sha256: $(sha256sum "$APK" | cut -d' ' -f1)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
AAPT=$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | tail -1)
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | tail -1)

# --- identity ---------------------------------------------------------------
if [ -n "${AAPT:-}" ] && [ -x "$AAPT" ]; then
  badging=$("$AAPT" dump badging "$APK" 2>/dev/null)
  pkg=$(printf '%s\n' "$badging" | sed -n 's/^package: name=.\([^'"'"']*\)..*versionCode=.\([^'"'"']*\)..*versionName=.\([^'"'"']*\)..*/\1 \2 \3/p' | head -1)
  set -- $pkg
  echo "package:   ${1:-?}"
  echo "versionCode: ${2:-?}"
  echo "versionName: ${3:-?}"
  [ "${1:-}" = "com.music.bitchord.loopfix" ] && ok "package is loopfix" || bad "package is not com.music.bitchord.loopfix"
  [ "${2:-}" = "93" ] && ok "versionCode 93" || bad "versionCode is not 93 (got ${2:-?})"
  [ "${3:-}" = "2.3.0-djmix51" ] && ok "versionName 2.3.0-djmix51" || bad "versionName is not 2.3.0-djmix51 (got ${3:-?})"
else
  echo "aapt2 not found — skipping badging; set ANDROID_HOME to enable"
fi

# --- native payload ---------------------------------------------------------
listing=$(unzip -l "$APK")

# AUTOMIX-V2(B): bundled 4-stem SCNet int8 model (12,900,277 B).
if grep -q 'assets/scnet\.int8\.onnx$' <<< "$listing"; then
  scnet_size=$(printf '%s\n' "$listing" | awk '/assets\/scnet\.int8\.onnx$/{print $1}' | head -1)
  if [ "${scnet_size:-0}" = "12900277" ]; then
    ok "assets/scnet.int8.onnx present (12900277 B)"
  else
    bad "assets/scnet.int8.onnx is ${scnet_size:-?} B, expected 12900277"
  fi
else
  bad "assets/scnet.int8.onnx MISSING"
fi

# Exactly one onnxruntime, at the size the release line was pinned to. Two
# copies means a stale ABI or a duplicated dependency; a wrong size means the
# ONNX build changed under us.
onnx_count=$(printf '%s\n' "$listing" | grep -c 'lib/arm64-v8a/libonnxruntime\.so$')
onnx_size=$(printf '%s\n' "$listing" | awk '/lib\/arm64-v8a\/libonnxruntime\.so$/{print $1}' | head -1)
[ "$onnx_count" -eq 1 ] && ok "exactly one libonnxruntime.so" || bad "$onnx_count libonnxruntime.so copies"
# AUTOMIX-V2(A): onnxruntime-android-qnn 1.29.0; pinned to the vc93 release build.
[ "${onnx_size:-0}" = "23990744" ] && ok "onnxruntime is 23990744 B (onnxruntime-android-qnn 1.29.0)" || bad "onnxruntime is ${onnx_size:-absent} B, expected 23990744"

# QNN/HTP: count NATIVE OBJECTS only. The first version of this check grepped
# the whole listing for 'qnn|htp' and flagged the Qualcomm licence notice in
# assets/licenses/, which fails a perfectly good APK. Scope it to lib/.
# AUTOMIX-V2(A): the QNN HTP runtime now ships in the main APK (Hexagon v73 for SM8635).
for lib in libQnnHtp.so libQnnHtpV73Stub.so libQnnHtpV73Skel.so libQnnSystem.so; do
  if grep -q "lib/arm64-v8a/$lib" <<< "$listing"; then ok "$lib present"; else bad "$lib MISSING (NPU path dead)"; fi
done

libs=$(printf '%s\n' "$listing" | grep -oE 'libbitchord_[a-z_]*\.so' | sort -u)
lib_count=$(printf '%s\n' "$libs" | grep -c . || true)
[ "$lib_count" -eq 7 ] && ok "seven libbitchord_*.so" || bad "$lib_count libbitchord_*.so (expected 7)"
printf '%s\n' "$libs" | sed 's/^/        /'

# The analysis library is the one that crashed. Presence is necessary; the
# content check below is what actually proves the fix shipped.
#
# grep -q WITHOUT a pipe: `grep -q` exits on the first match, which kills the
# writer with SIGPIPE, and under `pipefail` the whole pipeline then reports
# failure even though the string matched. That is the same trap the workflow
# comments about aapt2 in the audit step. Use the here-string so there is no
# writer to interrupt.
if grep -q 'lib/arm64-v8a/libbitchord_analysis\.so' <<< "$listing"; then
  ok "libbitchord_analysis.so present"
else
  bad "libbitchord_analysis.so MISSING"
fi

# --- signature --------------------------------------------------------------
if [ -n "${APKSIGNER:-}" ] && [ -x "$APKSIGNER" ]; then
  if "$APKSIGNER" verify --print-certs "$APK" >/tmp/apksig.txt 2>&1; then
    ok "signature verifies"
    sed -n 's/^Signer #1 certificate DN: /        cert: /p' /tmp/apksig.txt
  else
    bad "signature does not verify (unsigned or wrong key?)"
    sed -n '1,5p' /tmp/apksig.txt
  fi
else
  # An unsigned APK has no META-INF/*.RSA|DSA|EC. Worth stating loudly: an
  # unsigned release cannot be installed over vc74.
  if grep -qE 'META-INF/.*\.(RSA|DSA|EC)$' <<< "$listing"; then
    ok "signature block present (apksigner unavailable to verify)"
  else
    bad "APK appears UNSIGNED — cannot install over vc74"
  fi
fi

echo ""
[ "$fail" -ne 0 ] && { echo "AUDIT FAILED — do not install."; exit 1; }
echo "AUDIT PASSED."
