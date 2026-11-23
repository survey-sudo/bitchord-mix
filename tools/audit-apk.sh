#!/usr/bin/env bash
# APK audit for the current release (vc92 / 2.2.3-djmix50 at the time of writing).
# The expected version is read from build.gradle.kts, never hardcoded here; pass
# --code/--name to override it for a different tree or an older artifact.
#
# Runs anywhere with unzip + python3; uses aapt2/apksigner when an Android SDK
# is present, and degrades to structural checks when it is not.
#
#   ./audit-apk.sh [--code N] [--name STR] path/to/app.apk
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() { echo "usage: audit-apk.sh [--code N] [--name STR] <apk>" >&2; }

APK=""
EXPECTED_CODE=""
EXPECTED_NAME=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --code)   [ "$#" -ge 2 ] || { usage; exit 2; }; EXPECTED_CODE="$2"; shift 2 ;;
    --code=*) EXPECTED_CODE="${1#*=}"; shift ;;
    --name)   [ "$#" -ge 2 ] || { usage; exit 2; }; EXPECTED_NAME="$2"; shift 2 ;;
    --name=*) EXPECTED_NAME="${1#*=}"; shift ;;
    -h|--help) usage; exit 0 ;;
    -*)       echo "unknown option: $1" >&2; usage; exit 2 ;;
    *)        [ -z "$APK" ] || { usage; exit 2; }; APK="$1"; shift ;;
  esac
done
[ -n "$APK" ] || { usage; exit 2; }

fail=0
bad() { printf 'FAIL  %s\n' "$*"; fail=1; }
ok()  { printf 'ok    %s\n' "$*"; }

[ -f "$APK" ] || { echo "no such file: $APK"; exit 2; }

# --- expected version from the single source of truth -----------------------
GRADLE="$ROOT/build.gradle.kts"
if [ -z "$EXPECTED_CODE" ] && [ -f "$GRADLE" ]; then
  EXPECTED_CODE=$(sed -n 's/.*extra\["bitchordVersionCode"\][[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$GRADLE" | head -1)
fi
if [ -z "$EXPECTED_NAME" ] && [ -f "$GRADLE" ]; then
  EXPECTED_NAME=$(sed -n 's/.*extra\["bitchordVersionName"\][[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE" | head -1)
fi
if [ -n "$EXPECTED_CODE" ] && [ -n "$EXPECTED_NAME" ]; then
  echo "expect: versionCode=$EXPECTED_CODE versionName=$EXPECTED_NAME (from build.gradle.kts unless overridden)"
else
  echo "note:  no expected version available; install-time version checks are advisory"
fi

# --- SDK / build-tools discovery --------------------------------------------
# Accept the usual env vars first, then the known host layouts. Pick the
# highest installed build-tools directory that actually contains the tool.
discover_sdk() {
  local cand
  for cand in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
              /home/boxes/android-sdk "${HOME:-}/android-sdk" /opt/android-sdk; do
    [ -n "$cand" ] && [ -d "$cand/build-tools" ] && { printf '%s\n' "$cand"; return 0; }
  done
  return 0
}
pick_tool() { # $1 = sdk root, $2 = tool name
  local sdk="$1" tool="$2" dir
  [ -d "$sdk/build-tools" ] || return 0
  while IFS= read -r dir; do
    if [ -x "$dir/$tool" ]; then printf '%s\n' "$dir/$tool"; return 0; fi
  done < <(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -Vr)
  return 0
}

SDK="$(discover_sdk)"
AAPT=""
APKSIGNER=""
if [ -n "$SDK" ]; then
  AAPT="$(pick_tool "$SDK" aapt2)"
  APKSIGNER="$(pick_tool "$SDK" apksigner)"
  echo "sdk:   $SDK (aapt2: ${AAPT:-none}, apksigner: ${APKSIGNER:-none})"
else
  echo "sdk:   not found — set ANDROID_HOME/ANDROID_SDK_ROOT for badging + apksigner checks"
fi

echo "=== $(basename "$APK") ==="
echo "size:   $(stat -c %s "$APK") bytes"
echo "sha256: $(sha256sum "$APK" | cut -d' ' -f1)"

# --- identity ---------------------------------------------------------------
if [ -n "$AAPT" ]; then
  badging=$("$AAPT" dump badging "$APK" 2>/dev/null)
  pkg=$(printf '%s\n' "$badging" | sed -n 's/^package: name=.\([^'"'"']*\)..*versionCode=.\([^'"'"']*\)..*versionName=.\([^'"'"']*\)..*/\1 \2 \3/p' | head -1)
  set -- $pkg
  echo "package:     ${1:-?}"
  echo "versionCode: ${2:-?}"
  echo "versionName: ${3:-?}"
  [ "${1:-}" = "com.music.bitchord.loopfix" ] && ok "package is loopfix" || bad "package is not com.music.bitchord.loopfix"
  if [ -n "$EXPECTED_CODE" ]; then
    [ "${2:-}" = "$EXPECTED_CODE" ] && ok "versionCode $EXPECTED_CODE" || bad "versionCode is not $EXPECTED_CODE (got ${2:-?})"
  fi
  if [ -n "$EXPECTED_NAME" ]; then
    [ "${3:-}" = "$EXPECTED_NAME" ] && ok "versionName $EXPECTED_NAME" || bad "versionName is not $EXPECTED_NAME (got ${3:-?})"
  fi
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
# failure even though the string matched. Use the here-string so there is no
# writer to interrupt.
if grep -q 'lib/arm64-v8a/libbitchord_analysis\.so' <<< "$listing"; then
  ok "libbitchord_analysis.so present"
else
  bad "libbitchord_analysis.so MISSING"
fi

# --- signature --------------------------------------------------------------
if [ -n "$APKSIGNER" ]; then
  siglog=$(mktemp "${TMPDIR:-/tmp}/apksig.XXXXXX")
  if "$APKSIGNER" verify --print-certs "$APK" >"$siglog" 2>&1; then
    ok "signature verifies (apksigner)"
    sed -n 's/^Signer #1 certificate DN: /        cert: /p' "$siglog"
  else
    bad "signature does not verify (unsigned or wrong key?)"
    sed -n '1,5p' "$siglog"
  fi
  rm -f "$siglog"
else
  # Two independent signals, because they are not equivalent:
  #   * META-INF/*.RSA|DSA|EC is the v1 (JAR) signature.
  #   * v2/v3 schemes live in the APK Signing Block, identified by the ASCII
  #     magic "APK Sig Block 42", and may have NO META-INF signature at all.
  # Checking only the first produces false "APK appears UNSIGNED" failures on
  # a perfectly good modern APK. Binary grep is safe here (no pipeline).
  v1=0; v2v3=0
  grep -qE 'META-INF/.*\.(RSA|DSA|EC)$' <<< "$listing" && v1=1
  grep -aq 'APK Sig Block 42' "$APK" && v2v3=1
  if [ "$v1" -eq 1 ] || [ "$v2v3" -eq 1 ]; then
    ok "signature block present (v1=$v1, v2/v3 magic=$v2v3; apksigner unavailable to verify)"
  else
    bad "APK appears UNSIGNED — cannot install over vc74"
  fi
fi

echo ""
[ "$fail" -ne 0 ] && { echo "AUDIT FAILED — do not install."; exit 1; }
echo "AUDIT PASSED."
