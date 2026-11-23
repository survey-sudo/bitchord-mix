#!/usr/bin/env bash
# vc91 device verification — runs on the workstation with the phone on USB.
#
#   bash tools/vc91-device-verify.sh /path/to/bitchord-2.2.3-djmix49.apk
#
# Installs, clears the previous telemetry, waits for the app to be exercised,
# pulls the new log and diffs it against the vc90 baseline.
set -euo pipefail

APK="${1:?usage: vc91-device-verify.sh <apk>}"
SERIAL="${SERIAL:-1439ebea}"
PKG=com.music.bitchord.loopfix
REMOTE=/sdcard/Android/data/$PKG/files/automix-telemetry.jsonl
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$HERE/evidence/vc91-djmix49/device-vc91"
BASE="$HERE/evidence/vc91-djmix49/device-baseline/automix-telemetry-vc90.jsonl"
mkdir -p "$OUT"

A() { adb -s "$SERIAL" "$@"; }

echo "==> device"
A wait-for-device
A shell getprop ro.product.model
A shell dumpsys package "$PKG" | grep -E 'versionCode|versionName' | head -2 || true

echo "==> install"
A install -r "$APK"
A shell dumpsys package "$PKG" | grep -E 'versionCode|versionName|lastUpdateTime' | head -3

echo "==> clear previous telemetry (previous log archived first)"
if A shell "[ -f $REMOTE ]"; then
  ARCHIVE="$OUT/automix-telemetry-pre-vc91-$(date -u +%Y%m%dT%H%M%SZ).jsonl"
  A pull "$REMOTE" "$ARCHIVE" >/dev/null
  if [ ! -s "$ARCHIVE" ]; then
    echo "!! archive pull failed or empty at $ARCHIVE — aborting before delete" >&2
    exit 1
  fi
  echo "    archived $(wc -l < "$ARCHIVE") lines -> $ARCHIVE"
  A shell "run-as $PKG true" >/dev/null 2>&1 || true
  A shell "rm -f $REMOTE" || A shell "rm -f $REMOTE"
fi
A shell "ls -la $REMOTE" 2>&1 || echo "(telemetry file absent — good)"

echo "==> launch"
A shell am force-stop "$PKG"
A shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 5
A shell dumpsys activity activities | grep -m2 -E 'mResumedActivity|topResumedActivity' || true

cat <<'EOF'

==> now exercise the app on the phone:
    1. Play a playlist or queue of at least 6 tracks with automix ON.
    2. Let at least 4 transitions happen end to end (do not skip).
    3. Watch the status line under the player — it should name a rung
       ("Beat blend", "Safety crossfade", ...) rather than "Natural handoff".
    4. Come back here and press Enter.

EOF
read -r _

echo "==> pull"
A pull "$REMOTE" "$OUT/automix-telemetry-vc91.jsonl"
wc -l "$OUT/automix-telemetry-vc91.jsonl"

echo "==> diff vs vc90"
set +e
python3 "$HERE/tools/automix-telemetry-diff.py" "$BASE" "$OUT/automix-telemetry-vc91.jsonl"
RC=$?
set -e

echo
echo "raw log: $OUT/automix-telemetry-vc91.jsonl"
echo "diff exit: $RC  (0 = every vc91 target met)"
exit $RC
