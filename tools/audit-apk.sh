#!/usr/bin/env bash
# Full release audit for BitChord 1.10-djmix52 (vc94).
#
#   ./audit-apk.sh path/to/app.apk
#
# This is the release gate, so a missing SDK tool is a FAILURE, not a downgrade:
# package identity, signature scheme and certificate, and zip alignment cannot be
# inspected without aapt2/apksigner/zipalign. `--structural-only` exists for early
# smoke checks: there tool absence degrades to a warning, but every payload defect
# (including ELF page alignment, which needs no SDK tool) still fails, and it never
# prints AUDIT PASSED and never exits 0.
set -uo pipefail

STRUCTURAL_ONLY=0
if [ "${1:-}" = "--structural-only" ]; then
  STRUCTURAL_ONLY=1
  shift
fi

APK="${1:?usage: audit-apk.sh [--structural-only] <apk>}"
fail=0
bad() { printf 'FAIL  %s\n' "$*"; fail=1; }
ok()  { printf 'ok    %s\n' "$*"; }
warn() { printf 'WARN  %s\n' "$*"; }

# A missing SDK tool is a hard failure in a release audit: identity, signature and
# alignment cannot be inspected some other way, and downgrading silently is exactly
# how a wrong-signed build would ship. --structural-only is the explicit opt-in to
# skip them, and it never prints AUDIT PASSED.
need_tool() {
  if [ "$STRUCTURAL_ONLY" -eq 1 ]; then
    warn "$1"
  else
    bad "$1"
  fi
}

[ -f "$APK" ] || { echo "no such file: $APK"; exit 2; }

# Release identity, pinned once.
EXPECTED_PACKAGE="com.music.bitchord.loopfix"
EXPECTED_VERSION_CODE="94"
EXPECTED_VERSION_NAME="1.10-djmix52"
# The published onnxruntime-android:1.31.0 AAR's arm64 core, after Gradle's
# default release native stripping. The companion libonnxruntime4j_jni.so is
# the Java bridge, NOT a second core.
EXPECTED_ORT_BYTES="34208672"
EXPECTED_ORT_SHA256="fcec92f4ef56964a2ba312cfcadbdbe03070a3165069712a41b4ce7115efa61f"
EXPECTED_CERT_DN="CN=BitChord loop fix"
EXPECTED_CERT_SHA256="20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c"

echo "=== $(basename "$APK") ==="
echo "size:   $(stat -c %s "$APK") bytes"
echo "sha256: $(sha256sum "$APK" | cut -d' ' -f1)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
AAPT=$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | tail -1)
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | tail -1)
ZIPALIGN=$(ls "$SDK"/build-tools/*/zipalign 2>/dev/null | tail -1)

if [ "$STRUCTURAL_ONLY" -eq 1 ]; then
  printf 'WARN  structural-only run: NOT a release audit; package identity and signature are NOT verified\n'
fi

# --- identity ---------------------------------------------------------------
if [ -n "${AAPT:-}" ] && [ -x "$AAPT" ]; then
  badging=$("$AAPT" dump badging "$APK" 2>/dev/null)
  pkg=$(printf '%s\n' "$badging" | sed -n 's/^package: name=.\([^'"'"']*\)..*versionCode=.\([^'"'"']*\)..*versionName=.\([^'"'"']*\)..*/\1 \2 \3/p' | head -1)
  set -- $pkg
  echo "package:     ${1:-?}"
  echo "versionCode: ${2:-?}"
  echo "versionName: ${3:-?}"
  [ "${1:-}" = "$EXPECTED_PACKAGE" ] && ok "package is $EXPECTED_PACKAGE" || bad "package is not $EXPECTED_PACKAGE (got ${1:-?})"
  [ "${2:-}" = "$EXPECTED_VERSION_CODE" ] && ok "versionCode $EXPECTED_VERSION_CODE" || bad "versionCode is not $EXPECTED_VERSION_CODE (got ${2:-?})"
  [ "${3:-}" = "$EXPECTED_VERSION_NAME" ] && ok "versionName $EXPECTED_VERSION_NAME" || bad "versionName is not $EXPECTED_VERSION_NAME (got ${3:-?})"
else
  need_tool "aapt2 not found under $SDK/build-tools — cannot verify package identity (set ANDROID_HOME)"
fi

# --- payload ----------------------------------------------------------------
listing=$(unzip -l "$APK") || { echo "unzip failed"; exit 2; }

# djmix52 ships no ONNX weights: all three are delivered and verified by the
# model coordinator (filesDir), never from the APK.
if printf '%s\n' "$listing" | grep -qiE '[^[:space:]]*\.onnx$'; then
  bad "ONNX file(s) still packaged:"
  printf '%s\n' "$listing" | grep -iE '[^[:space:]]*\.onnx$' | sed 's/^/        /'
else
  ok "no .onnx files anywhere in the APK"
fi

# Exactly one ORT core, arm64 only. A second copy means a stale ABI or a duplicated
# dependency; a wrong size/hash means the pinned 1.31.0 artifact was not the one used.
core_lines=$(printf '%s\n' "$listing" | grep -E '^[[:space:]]*[0-9]+[[:space:]].*/libonnxruntime\.so$')
core_count=$(printf '%s\n' "$core_lines" | grep -c 'libonnxruntime\.so$' || true)
[ "$core_count" -eq 1 ] && ok "exactly one libonnxruntime.so" || bad "$core_count libonnxruntime.so copies (expected 1)"
if [ "$core_count" -eq 1 ] && ! printf '%s\n' "$core_lines" | grep -q 'lib/arm64-v8a/libonnxruntime\.so$'; then
  bad "the single libonnxruntime.so is not under lib/arm64-v8a"
fi

if [ "$core_count" -eq 1 ]; then
  core_size=$(printf '%s\n' "$core_lines" | awk '{print $1}' | head -1)
  [ "$core_size" = "$EXPECTED_ORT_BYTES" ] && ok "ORT core is $EXPECTED_ORT_BYTES B" || bad "ORT core is ${core_size:-?} B, expected $EXPECTED_ORT_BYTES"
  core_tmp=$(mktemp "${TMPDIR:-/tmp}/bitchord-ort-core.XXXXXX")
  if unzip -p "$APK" 'lib/arm64-v8a/libonnxruntime.so' > "$core_tmp" 2>/dev/null; then
    core_sha=$(sha256sum "$core_tmp" | cut -d' ' -f1)
    [ "$core_sha" = "$EXPECTED_ORT_SHA256" ] && ok "ORT core SHA-256 matches 1.31.0" || bad "ORT core SHA-256 $core_sha, expected $EXPECTED_ORT_SHA256"
  else
    bad "could not extract lib/arm64-v8a/libonnxruntime.so"
  fi
  rm -f "$core_tmp"
fi

# The companion libonnxruntime4j_jni.so is the official Java JNI bridge,
# not a duplicate core: require it (post-strip size below).
if grep -q 'lib/arm64-v8a/libonnxruntime4j_jni\.so$' <<< "$listing"; then
  jni_size=$(printf '%s\n' "$listing" | awk '/lib\/arm64-v8a\/libonnxruntime4j_jni\.so$/{print $1}' | head -1)
  [ "$jni_size" = "92064" ] && ok "ORT Java JNI bridge present (92064 B)" || bad "libonnxruntime4j_jni.so is ${jni_size:-?} B, expected 92064"
else
  bad "libonnxruntime4j_jni.so MISSING (the Java API cannot load without it)"
fi

# No vendor NPU runtime in the shipped APK, at any ABI. Matched broadly on purpose:
# QNN (libQnnHtp*, libQnnSystem, the Hexagon skels), the Qualcomm DSP RPC bridge, and
# SNPE/HTA would each mean a vendor inference stack slipped back in.
qnn_libs=$(printf '%s\n' "$listing" | grep -iE '^[[:space:]]*[0-9]+[[:space:]].*/lib(qnn|snpe|hta|cdsprpc|adsprpc|dsp)[^/]*\.so$')
if [ -n "$qnn_libs" ]; then
  bad "vendor NPU .so files still under lib/:"
  printf '%s\n' "$qnn_libs" | sed 's/^/        /'
else
  ok "zero QNN/vendor NPU .so files under lib/"
fi

libs=$(printf '%s\n' "$listing" | grep -oE 'libbitchord_[a-z_]*\.so' | sort -u)
lib_count=$(printf '%s\n' "$libs" | grep -c . || true)
[ "$lib_count" -eq 7 ] && ok "seven libbitchord_*.so" || bad "$lib_count libbitchord_*.so (expected 7)"
printf '%s\n' "$libs" | sed 's/^/        /'

# grep -q WITHOUT a pipe: `grep -q` exits on the first match, which kills the
# writer with SIGPIPE, and under `pipefail` the whole pipeline then reports
# failure even though the string matched. Use a here-string so there is no
# writer to interrupt.
if grep -q 'lib/arm64-v8a/libbitchord_analysis\.so' <<< "$listing"; then
  ok "libbitchord_analysis.so present"
else
  bad "libbitchord_analysis.so MISSING"
fi

# --- signature --------------------------------------------------------------
if [ -n "${APKSIGNER:-}" ] && [ -x "$APKSIGNER" ]; then
  if "$APKSIGNER" verify --verbose --print-certs "$APK" >/tmp/bitchord-apksig.txt 2>&1; then
    ok "signature verifies"
    sed -n 's/^Signer #1 certificate DN: /        cert: /p' /tmp/bitchord-apksig.txt
    if grep -q "^Signer #1 certificate DN: .*CN=BitChord loop fix" /tmp/bitchord-apksig.txt; then
      ok "certificate DN is $EXPECTED_CERT_DN"
    else
      bad "certificate DN is not $EXPECTED_CERT_DN (signed with the wrong key?)"
    fi
    cert_sha=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' /tmp/bitchord-apksig.txt | head -1)
    [ "$cert_sha" = "$EXPECTED_CERT_SHA256" ] && ok "certificate SHA-256 matches the fork key" || bad "certificate SHA-256 ${cert_sha:-?}, expected $EXPECTED_CERT_SHA256"
    grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' /tmp/bitchord-apksig.txt \
      && ok "v2 signature scheme verified" || bad "v2 signature scheme NOT verified"
    grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' /tmp/bitchord-apksig.txt \
      && ok "v3 signature scheme verified" || bad "v3 signature scheme NOT verified"
  else
    bad "signature does not verify (unsigned or wrong key?)"
    sed -n '1,8p' /tmp/bitchord-apksig.txt
  fi
else
  need_tool "apksigner not found under $SDK/build-tools — cannot verify the signature (set ANDROID_HOME)"
fi

# --- 16 KiB page support ----------------------------------------------------
# Two separate requirements, and the zip one alone is NOT sufficient here: with
# useLegacyPackaging=true the .so entries are compressed and extracted at install,
# so zipalign has nothing to align and would pass trivially. The binding
# requirement in that mode is that each library's ELF PT_LOAD segments are
# aligned for 16 KiB pages (Android 15+ arm64 refuses a library that is not).
# So: check the ELF alignment of every packaged .so, and additionally require
# zipalign to pass (which covers the uncompressed case and any future switch).
so_tmp=$(mktemp -d "${TMPDIR:-/tmp}/bitchord-elf.XXXXXX")
so_list=$(printf '%s\n' "$listing" | grep -oE 'lib/[^ ]*\.so$' | sort -u)
align_ok=1
for so in $so_list; do
  dest="$so_tmp/$(printf '%s' "$so" | tr '/' '_')"
  if ! unzip -p "$APK" "$so" > "$dest" 2>/dev/null; then
    bad "could not extract $so for alignment check"
    align_ok=0
    continue
  fi
  if command -v readelf >/dev/null 2>&1; then
    worst=0
    while read -r tok; do
      [ -n "$tok" ] || continue
      dec=$((tok))
      [ "$dec" -gt "$worst" ] && worst=$dec
    done < <(readelf -lW "$dest" 2>/dev/null | awk '$1=="LOAD"{print $NF}')
  else
    worst=$(python3 - "$dest" <<'PY'
import struct, sys
data = open(sys.argv[1], "rb").read()
if data[:4] != b"\x7fELF" or data[4] != 2:
    print(0); raise SystemExit
e_phoff, = struct.unpack_from("<Q", data, 0x20)
e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
best = 0
for i in range(e_phnum):
    off = e_phoff + i * e_phentsize
    if struct.unpack_from("<I", data, off)[0] != 1:
        continue
    align, = struct.unpack_from("<Q", data, off + 48)
    best = max(best, align)
print(best)
PY
)
  fi
  if [ -z "${worst:-}" ] || [ "${worst:-0}" -lt 16384 ]; then
    bad "$so is not 16 KiB-page aligned (max PT_LOAD align ${worst:-0}, need >= 16384)"
    align_ok=0
  fi
done
rm -rf "$so_tmp"
[ "$align_ok" -eq 1 ] && ok "every packaged .so has 16 KiB-aligned PT_LOAD segments"

if [ -n "${ZIPALIGN:-}" ] && [ -x "$ZIPALIGN" ]; then
  if "$ZIPALIGN" -c -P 16 4 "$APK" >/tmp/bitchord-zipalign.txt 2>&1; then
    ok "zipalign -P 16 verified"
  else
    bad "zipalign -P 16 FAILED"
    sed -n '1,12p' /tmp/bitchord-zipalign.txt
  fi
else
  need_tool "zipalign not found under $SDK/build-tools — cannot verify zip alignment (set ANDROID_HOME)"
fi

echo ""
if [ "$fail" -ne 0 ]; then
  if [ "$STRUCTURAL_ONLY" -eq 1 ]; then
    echo "STRUCTURAL CHECKS FAILED — do not install."
    exit 1
  fi
  echo "AUDIT FAILED — do not install."
  exit 1
fi
if [ "$STRUCTURAL_ONLY" -eq 1 ]; then
  echo "STRUCTURAL CHECKS PASSED — this is NOT a release audit (package identity and signature were not verified)."
  exit 3
fi
echo "AUDIT PASSED."
