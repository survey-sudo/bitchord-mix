#!/usr/bin/env bash
# Full release audit for BitChord 1.10-djmix53 (vc95) / AE 3.0.0.
#
#   ./audit-apk.sh path/to/app.apk
#
# This is the release gate, so a missing SDK tool is a FAILURE, not a downgrade:
# package identity, signature scheme and certificate, and zip alignment cannot be
# inspected without aapt2/apksigner/zipalign. `--structural-only` exists for early
# smoke checks: there tool absence degrades to a warning, but every payload defect
# (including ELF page alignment, which needs no SDK tool) still fails, and it never
# prints AUDIT PASSED and never exits 0.
#
# Expected versionName/versionCode default to the current release
# (1.10-djmix53 / 95) and can be overridden for the next cut:
#   AUDIT_EXPECTED_VERSION_NAME=1.10-djmix54 AUDIT_EXPECTED_VERSION_CODE=96 ./audit-apk.sh app.apk
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

# Release identity, pinned once. Defaults track the current release cut
# (AE3 ships 1.10-djmix53 / 95, ADR 0004); override via env after bumpDjmix.
EXPECTED_PACKAGE="com.music.bitchord.loopfix"
EXPECTED_VERSION_CODE="${AUDIT_EXPECTED_VERSION_CODE:-95}"
EXPECTED_VERSION_NAME="${AUDIT_EXPECTED_VERSION_NAME:-1.10-djmix53}"
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

# --- not a debug build ------------------------------------------------------
# A debuggable APK re-signed with the release key passes every other check and
# still ships the wrong thing: 38 dex files and 173 MB of unoptimised Kotlin, in
# which analysis and planning run several times slower than the release build.
# That is exactly how 1.10-djmix52's candidate reached the phone, so the shape
# of a debug build is now a hard release failure on its own.
if [ -n "${AAPT:-}" ] && [ -x "$AAPT" ]; then
  if "$AAPT" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null \
      | grep -qE 'debuggable.*\(type 0x12\)0xffffffff'; then
    bad "android:debuggable=true — debug build re-signed as a release"
  else
    ok "not debuggable"
  fi
else
  need_tool "aapt2 not found — cannot verify android:debuggable (set ANDROID_HOME)"
fi

dex_count=$(printf '%s\n' "$listing" | grep -cE 'classes[0-9]*\.dex$')
dex_bytes=$(printf '%s\n' "$listing" | awk '/classes[0-9]*\.dex$/{s+=$1} END{print s+0}')
echo "dex:         $dex_count file(s), $dex_bytes bytes"
[ "$dex_count" -ge 1 ] && [ "$dex_count" -le 6 ] \
  && ok "dex file count $dex_count <= 6" \
  || bad "dex file count $dex_count is not 1..6 (debug-shaped multidex)"
[ "$dex_bytes" -le 40000000 ] \
  && ok "dex payload $dex_bytes bytes <= 40000000" \
  || bad "dex payload $dex_bytes bytes exceeds 40000000"

# AE3 bundles its CPU models as APK assets (Part A section 3.1): Nirovi
# (beat) + Syvenu (vocal presence), verified against models.json. Still
# forbid stray .onnx anywhere under assets/: QNN weights belong in the
# add-on (Part B4.3), never in the main APK.
if printf '%s\n' "$listing" | grep -qiE '^[[:space:]]*[0-9]+[[:space:]].*assets/[^[:space:]]*\.onnx$'; then
  bad "stray .onnx file(s) under assets/:"
  printf '%s\n' "$listing" | grep -iE '^[[:space:]]*[0-9]+[[:space:]].*assets/[^[:space:]]*\.onnx$' | sed 's/^/        /'
else
  ok "no stray .onnx files under assets/"
fi

# The two pinned AE3 .ort assets (sha256 + bytes from models.json).
check_ort_asset() {
  # $1 = assets/<name>  $2 = expected bytes  $3 = expected sha256
  local name="$1" want_bytes="$2" want_sha="$3"
  if grep -q "assets/${name}\$" <<< "$listing"; then
    ok "bundled asset present: assets/$name"
  else
    bad "bundled asset MISSING: assets/$name"
    return
  fi
  local got_bytes
  got_bytes=$(printf '%s\n' "$listing" | awk -v a="assets/$name" '$NF==a{print $1}' | head -1)
  [ "$got_bytes" = "$want_bytes" ] && ok "assets/$name is $want_bytes B" || bad "assets/$name is ${got_bytes:-?} B, expected $want_bytes"
  local tmp
  tmp=$(mktemp "${TMPDIR:-/tmp}/bitchord-ort-asset.XXXXXX")
  if unzip -p "$APK" "assets/$name" > "$tmp" 2>/dev/null; then
    local got_sha
    got_sha=$(sha256sum "$tmp" | cut -d' ' -f1)
    [ "$got_sha" = "$want_sha" ] && ok "assets/$name SHA-256 matches models.json" || bad "assets/$name SHA-256 $got_sha, expected $want_sha"
  else
    bad "could not extract assets/$name"
  fi
  rm -f "$tmp"
}
check_ort_asset "nirovi_int8.ort" "7948784" "7454d98ab6a88c0b7fe5e91c556b4766ebfabfe3d31ebd61187a033fed489387"
check_ort_asset "syvenu.ort" "2349304" "1866a5955ed6b0e80f807cb49f56af530121064b8ea5322026566126eb3cd4f3"

# Any other .ort asset is stray (stems/vocal fallback models ship via
# the models-v1 release, not the APK).
extra_ort=$(printf '%s\n' "$listing" | grep -iE '^[[:space:]]*[0-9]+[[:space:]].*assets/[^[:space:]]*\.ort$' | grep -vE 'assets/(nirovi_int8|syvenu)\.ort$' || true)
if [ -n "$extra_ort" ]; then
  bad "unexpected .ort asset(s) under assets/:"
  printf '%s\n' "$extra_ort" | sed 's/^/        /'
else
  ok "no unexpected .ort assets"
fi

# Model registry + recipe library (Part B4.1/B5).
if grep -q 'assets/models\.json$' <<< "$listing"; then
  ok "model registry assets/models.json present"
else
  bad "model registry assets/models.json MISSING"
fi
recipe_count=$(printf '%s\n' "$listing" | grep -cE '^[[:space:]]*[0-9]+[[:space:]].*assets/recipes/[^[:space:]]*\.json$' || true)
[ "$recipe_count" -eq 26 ] && ok "26 recipe JSONs under assets/recipes/" || bad "$recipe_count recipe JSONs under assets/recipes/ (expected 26)"

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
check_elf_load_alignment() {
  local elf="$1" output token value count=0
  parse_alignment() {
    token="$1"
    if [[ "$token" =~ ^0[xX][0-9a-fA-F]+$ ]]; then
      value=$((16#${token:2}))
    elif [[ "$token" =~ ^[0-9]+$ ]]; then
      value=$((10#$token))
    else
      return 1
    fi
    [ "$value" -gt 0 ] && [ $((value & (value - 1))) -eq 0 ] && [ "$value" -ge 16384 ]
  }

  if [ "${AUDIT_FORCE_PYTHON_ELF:-0}" != 1 ] && command -v readelf >/dev/null 2>&1; then
    output=$(readelf -lW "$elf" 2>/dev/null) || return 1
    while IFS= read -r line; do
      [[ "$line" =~ ^[[:space:]]*LOAD[[:space:]] ]] || continue
      count=$((count + 1))
      token="${line##* }"
      parse_alignment "$token" || return 1
    done <<< "$output"
  elif [ "${AUDIT_FORCE_PYTHON_ELF:-0}" != 1 ] && command -v objdump >/dev/null 2>&1; then
    output=$(objdump -p "$elf" 2>/dev/null) || return 1
    while IFS= read -r line; do
      [[ "$line" =~ ^[[:space:]]*LOAD[[:space:]] ]] || continue
      count=$((count + 1))
      token=$(printf '%s\n' "$line" | sed -nE 's/.*[[:space:]]align[[:space:]]+([^[:space:]]+).*/\1/p')
      [ -n "$token" ] || return 1
      if [[ "$token" =~ ^2\*\*([0-9]+)$ ]]; then
        local exponent="${BASH_REMATCH[1]}"
        [ "$exponent" -ge 14 ] || return 1
      else
        parse_alignment "$token" || return 1
      fi
    done <<< "$output"
  else
    python3 - "$elf" <<'PY'
import struct
import sys

path = sys.argv[1]
try:
    data = open(path, "rb").read()
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF")
    elf_class = data[4]
    endian = "<" if data[5] == 1 else ">" if data[5] == 2 else None
    if endian is None or elf_class not in (1, 2):
        raise ValueError("unsupported ELF header")
    if elf_class == 2:
        if len(data) < 64:
            raise ValueError("truncated ELF header")
        e_phoff, = struct.unpack_from(endian + "Q", data, 32)
        e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 54)
        align_offset, min_size = 48, 56
    else:
        if len(data) < 52:
            raise ValueError("truncated ELF header")
        e_phoff, = struct.unpack_from(endian + "I", data, 28)
        e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 42)
        align_offset, min_size = 28, 32
    if e_phentsize < min_size:
        raise ValueError("invalid program-header size")
    count = 0
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if off < 0 or off + e_phentsize > len(data):
            raise ValueError("truncated program-header table")
        p_type, = struct.unpack_from(endian + "I", data, off)
        if p_type != 1:
            continue
        count += 1
        align, = struct.unpack_from(endian + ("Q" if elf_class == 2 else "I"), data, off + align_offset)
        if align < 16384 or align == 0 or (align & (align - 1)) != 0:
            raise ValueError("invalid PT_LOAD alignment")
    if count == 0:
        raise ValueError("no PT_LOAD segments")
except (OSError, ValueError, struct.error) as error:
    print(error, file=sys.stderr)
    raise SystemExit(1)
PY
    return $?
  fi
  [ "$count" -gt 0 ]
}

for so in $so_list; do
  dest="$so_tmp/$(printf '%s' "$so" | tr '/' '_')"
  if ! unzip -p "$APK" "$so" > "$dest" 2>/dev/null; then
    bad "could not extract $so for alignment check"
    align_ok=0
    continue
  fi
  if ! check_elf_load_alignment "$dest"; then
    bad "$so has a malformed, missing, non-power-of-two, or sub-16 KiB PT_LOAD alignment"
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
