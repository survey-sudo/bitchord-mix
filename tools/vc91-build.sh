#!/usr/bin/env bash
# vc91 / 2.2.3-djmix49 release build + audit — runs ON the dvb box.
#
#   bash tools/vc91-build.sh              # build + audit
#   bash tools/vc91-build.sh --audit PATH # audit an existing APK only
#
# Gradle signs the release itself from keystore.properties + bitchord-loopfix.jks
# (both gitignored, both must be in the repo root). If either is missing the
# build silently produces *-unsigned.apk, which this script rejects.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

EXPECTED_CODE=91
EXPECTED_NAME="2.2.3-djmix49"
SDK="${ANDROID_HOME:-/home/boxes/android-sdk}"
AAPT2="$SDK/build-tools/36.0.0/aapt2"
SIGNED="app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk"

if [ "${1:-}" = "--audit" ]; then
  APK="${2:-$SIGNED}"
  bash tools/audit-apk.sh "$APK"
  exit $?
fi

[ -f keystore.properties ] || { echo "!! keystore.properties missing in repo root" >&2; exit 1; }
[ -f bitchord-loopfix.jks ] || { echo "!! bitchord-loopfix.jks missing in repo root" >&2; exit 1; }

echo "==> gradle :app:assembleProdRelease"
GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx3g -Dorg.gradle.daemon=false" \
  ./gradlew :app:assembleProdRelease -Pbitchord.bundleWear=false \
    --max-workers=2 --no-daemon

if [ ! -f "$SIGNED" ]; then
  echo "!! no signed APK at $SIGNED — build fell back to unsigned" >&2
  find app/build/outputs -name '*.apk' -printf '%TY-%Tm-%Td %TH:%TM  %s  %p\n' 2>/dev/null >&2 || true
  exit 1
fi

echo "==> verifying signature"
"$AAPT2" dump badging "$SIGNED" | sed -n '1p'
"$SDK/build-tools/36.0.0/apksigner" verify --print-certs "$SIGNED" | head -4

echo "==> audit"
set +e
bash tools/audit-apk.sh "$SIGNED"
AUDIT=$?
set -e

echo
echo "APK: $ROOT/$SIGNED"
ls -la "$SIGNED"
sha256sum "$SIGNED"
echo "audit exit: $AUDIT"
exit $AUDIT
