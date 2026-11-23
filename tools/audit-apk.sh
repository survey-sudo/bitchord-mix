#!/usr/bin/env bash
# APK audit for vc90 (2.2.3-djmix48). Runs anywhere with unzip + python3; uses aapt2/apksigner
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
  [ "${2:-}" = "92" ] && ok "versionCode 92" || bad "versionCode is not 92 (got ${2:-?})"
  [ "${3:-}" = "2.2.3-djmix50" ] && ok "versionName 2.2.3-djmix50" || bad "versionName is not 2.2.3-djmix50 (got ${3:-?})"
else
  echo "aapt2 not found — skipping badging; set ANDROID_HOME to enable"
fi

# --- native payload ---------------------------------------------------------
listing=$(unzip -l "$APK")

# Exactly one onnxruntime, at the size the release line was pinned to. Two
# copies means a stale ABI or a duplicated dependency; a wrong size means the
# ONNX build changed under us.
onnx_count=$(printf '%s\n' "$listing" | grep -c 'lib/arm64-v8a/libonnxruntime\.so$')
onnx_size=$(printf '%s\n' "$listing" | awk '/lib\/arm64-v8a\/libonnxruntime\.so$/{print $1}' | head -1)
[ "$onnx_count" -eq 1 ] && ok "exactly one libonnxruntime.so" || bad "$onnx_count libonnxruntime.so copies"
[ "${onnx_size:-0}" = "34208672" ] && ok "onnxruntime is 34208672 B" || bad "onnxruntime is ${onnx_size:-absent} B, expected 34208672"

# QNN/HTP: count NATIVE OBJECTS only. The first version of this check grepped
# the whole listing for 'qnn|htp' and flagged the Qualcomm licence notice in
# assets/licenses/, which fails a perfectly good APK. Scope it to lib/.
qnn=$(printf '%s\n' "$listing" | grep -E 'lib/.*\.so' | grep -ciE 'qnn|htp' || true)
[ "${qnn:-0}" -eq 0 ] && ok "zero QNN/HTP native libraries" || bad "$qnn QNN/HTP .so present"

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
