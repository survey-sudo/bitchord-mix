#!/usr/bin/env bash
# vc92 / 2.2.3-djmix50 release build + audit — runs ON the dvb box.
#
#   bash tools/vc92-build.sh              # build + audit
#   bash tools/vc92-build.sh --audit PATH # audit an existing APK only
#
# The Android SDK and its build-tools version are discovered at run time
# (ANDROID_HOME / ANDROID_SDK_ROOT / the usual host layouts), never hardcoded.
# The expected version comes from build.gradle.kts via tools/audit-apk.sh.
# Gradle signs the release itself from keystore.properties + bitchord-loopfix.jks
# (both gitignored, both must be in the repo root). If either is missing the
# build silently produces *-unsigned.apk, which this script rejects.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() { echo "usage: vc92-build.sh [--audit [APK]]" >&2; }

APK=""
if [ "$#" -gt 0 ]; then
  [ "${1:-}" = "--audit" ] || { usage; exit 2; }
  APK="${2:-}"
  [ "$#" -le 2 ] || { usage; exit 2; }
fi

# --- SDK discovery ----------------------------------------------------------
discover_sdk() {
  local cand
  for cand in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
              /home/boxes/android-sdk "${HOME:-}/android-sdk" /opt/android-sdk; do
    [ -n "$cand" ] && [ -d "$cand/build-tools" ] && { printf '%s\n' "$cand"; return 0; }
  done
  return 0
}
pick_tool() { # $1 = sdk root, $2 = tool name; highest installed build-tools wins
  local sdk="$1" tool="$2" dir
  [ -d "$sdk/build-tools" ] || return 0
  while IFS= read -r dir; do
    if [ -x "$dir/$tool" ]; then printf '%s\n' "$dir/$tool"; return 0; fi
  done < <(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -Vr)
  return 0
}

SDK="$(discover_sdk)"
if [ -z "$SDK" ]; then
  echo "!! no Android SDK found (looked at ANDROID_HOME, ANDROID_SDK_ROOT," >&2
  echo "   /home/boxes/android-sdk, \$HOME/android-sdk, /opt/android-sdk)" >&2
  echo "   Set ANDROID_HOME to a SDK containing build-tools/ to continue." >&2
  [ -n "$APK" ] || exit 1
fi
AAPT2="$(pick_tool "$SDK" aapt2)"
APKSIGNER="$(pick_tool "$SDK" apksigner)"
ZIPALIGN="$(pick_tool "$SDK" zipalign)"
if [ -n "$SDK" ]; then
  echo "SDK: $SDK (aapt2: ${AAPT2:-none}, apksigner: ${APKSIGNER:-none}, zipalign: ${ZIPALIGN:-none})"
fi

# With the arm64 ABI split, Gradle writes
# app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk.
# Keep the old flat name as a fallback and, failing both, discover whatever
# signed APK the release task actually produced.
find_apk() {
  local c f
  local -a found=()
  for c in app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk \
           app/build/outputs/apk/prod/release/app-prod-release.apk; do
    [ -f "$c" ] && { printf '%s\n' "$c"; return 0; }
  done
  while IFS= read -r f; do found+=("$f"); done < <(
    find app/build/outputs/apk/prod -type f -name '*.apk' ! -name '*-unsigned.apk' 2>/dev/null | sort
  )
  [ "${#found[@]}" -gt 0 ] && printf '%s\n' "${found[0]}"
  return 0
}

if [ -n "$APK" ]; then
  [ -f "$APK" ] || { echo "!! no such APK: $APK" >&2; exit 2; }
  bash tools/audit-apk.sh "$APK"
  exit $?
fi

[ -f keystore.properties ] || { echo "!! keystore.properties missing in repo root" >&2; exit 1; }
[ -f bitchord-loopfix.jks ] || { echo "!! bitchord-loopfix.jks missing in repo root" >&2; exit 1; }

echo "==> gradle :app:assembleProdRelease"
GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.daemon=false" \
  ./gradlew :app:assembleProdRelease -Pbitchord.bundleWear=false \
    --max-workers=2 --no-daemon

APK="$(find_apk)"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "!! no signed APK under app/build/outputs/apk/prod/ — build fell back to unsigned" >&2
  find app/build/outputs -name '*.apk' -printf '%TY-%Tm-%Td %TH:%TM  %s  %p\n' 2>/dev/null >&2 || true
  exit 1
fi
case "$APK" in
  *-unsigned.apk) echo "!! $APK is unsigned" >&2; exit 1 ;;
esac
echo "APK: $APK"

# --- zip alignment (zipalign -c -P 16) --------------------------------------
if [ -z "$ZIPALIGN" ]; then
  echo "!! zipalign not found in $SDK/build-tools/* — install build-tools or set ANDROID_HOME" >&2
  exit 1
fi
echo "==> zipalign -c -P 16"
"$ZIPALIGN" -c -P 16 4 "$APK" || { echo "!! zipalign check failed" >&2; exit 1; }

# --- signature / badging ----------------------------------------------------
if [ -n "$AAPT2" ]; then
  echo "==> badging"
  "$AAPT2" dump badging "$APK" | sed -n '1,2p'
else
  echo "note: aapt2 unavailable — skipping badging (audit will use structural checks)"
fi
if [ -n "$APKSIGNER" ]; then
  echo "==> apksigner verify"
  "$APKSIGNER" verify --print-certs "$APK" | sed -n '1,4p'
else
  echo "note: apksigner unavailable — audit falls back to v1/v2/v3 signature markers"
fi

# --- native probes ----------------------------------------------------------
if command -v g++ >/dev/null 2>&1; then
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/vc92-native.XXXXXX")"
  trap 'rm -rf "$WORK"' EXIT
  echo "==> native: phrase isolator"
  g++ -std=c++17 -O2 native/loopmix/phrase_isolator_test.cpp -o "$WORK/phrase_isolator_test"
  "$WORK/phrase_isolator_test" | tail -3
  echo "==> native: loopmix startup probe"
  g++ -std=c++17 -O2 -I native -I native/third_party \
      native/loopmix/loop_mix_renderer.cpp \
      native/loopmix/loop_mix_renderer_startup_probe.cpp -o "$WORK/startup_probe"
  "$WORK/startup_probe" > "$WORK/startup.log"
  tail -2 "$WORK/startup.log"
  echo "==> native: stretch bench"
  g++ -std=c++17 -O2 -I native -I native/third_party \
      native/stretch/pitch_stretch.cpp native/stretch/bench_main.cpp -o "$WORK/stretch_bench"
  "$WORK/stretch_bench" | tail -3
else
  echo "note: g++ not found — skipping native probes"
fi

# --- full audit -------------------------------------------------------------
echo "==> audit (version comes from build.gradle.kts)"
set +e
bash tools/audit-apk.sh "$APK"
AUDIT=$?
set -e

echo
echo "APK: $ROOT/$APK"
ls -la "$APK"
sha256sum "$APK"
echo "audit exit: $AUDIT"
exit "$AUDIT"
