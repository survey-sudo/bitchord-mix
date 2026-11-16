#!/usr/bin/env bash
# Pre-build gate for vc75. Needs only g++ — no Android SDK, no JDK, no Gradle.
#
#   ./vc75-gate.sh [tree]        default tree: .
#
# Exit 0 = the crash fix is present, the success-path test passes, and the
# version stamp is what we think. Anything else = do not build.
#
# Two classes of check, deliberately separated:
#
#   HARD  A safety property. The fix is missing, or the success path is broken.
#         No scope decision makes these acceptable: an APK built from such a
#         tree installs over vc74 and dies exactly the way vc74 died.
#
#   SCOPE Reported, never fatal. Which behaviour edits are present. vc75 is
#         allowed to ship narrowed (crash fix only) or full (crash fix plus the
#         scheduling edits), so this cannot be a failure — it exists so the APK
#         can be matched against the diff that was actually reviewed.
set -uo pipefail

REPO="${1:-.}"
cd "$REPO" || { echo "no such tree: $REPO"; exit 2; }

fail=0
ok()   { printf 'ok    %s\n' "$*"; }
bad()  { printf 'FAIL  %s\n' "$*"; fail=1; }
note() { printf 'scope %s\n' "$*"; }
say()  { printf '%s\n' "$*"; }

# Build output goes to a writable dir on real disk. A full tmpfs makes g++
# fail to write an 80 KB binary, which reads exactly like a broken test — a
# false failure this gate produced once and should never produce again.
WORK="${VC75_GATE_TMP:-${HOME}/.cache/vc75-gate}"
mkdir -p "$WORK" 2>/dev/null || WORK="${TMPDIR:-/tmp}"
trap 'rm -f "$WORK/vst_bin" "$WORK/vst.out" "$WORK/vst.build.log"' EXIT

say "=== tree: $(pwd) ==="
say "HEAD: $(git rev-parse --short HEAD 2>/dev/null || echo 'not a git tree')"

NATIVE="native/analyzer/vocal_spectrogram.cpp"
TEST="native/analyzer/vocal_spectrogram_test.cpp"

# --- HARD: the crash fix -----------------------------------------------------
if [ ! -f "$NATIVE" ]; then
  bad "$NATIVE missing"
else
  if grep -q 'std::vector<float> outputs\[2\]' "$NATIVE"; then
    bad "dead local 'outputs[2]' still present — this tree SHIPS THE CRASH"
  else
    ok "dead local outputs[2] is gone"
  fi

  if grep -q 'out_left\.assign(input_length' "$NATIVE" \
     && grep -q 'out_right\.assign(input_length' "$NATIVE"; then
    ok "out_left/out_right are sized with input_length"
  else
    bad "out_left/out_right are NOT sized — the outputs stay empty"
  fi

  # Sizing has to precede the loop that writes by index, or it is decoration.
  size_line=$(grep -n 'out_left\.assign(input_length' "$NATIVE" | head -1 | cut -d: -f1)
  loop_line=$(grep -n 'std::vector<float>& dst = (channel == 0)' "$NATIVE" | head -1 | cut -d: -f1)
  if [ -n "${size_line:-}" ] && [ -n "${loop_line:-}" ] && [ "$size_line" -lt "$loop_line" ]; then
    ok "sizing (line $size_line) precedes the write loop (line $loop_line)"
  else
    bad "sizing is not before the write loop (sizing=${size_line:-none} loop=${loop_line:-none})"
  fi
fi

# --- HARD: version stamp -----------------------------------------------------
# versionCode 75 spent on a crashing build is unrecoverable on device: vc74 can
# no longer be installed over it, and the way back is an uninstall.
grep -q 'extra\["bitchordVersionCode"\] = 75' build.gradle.kts \
  && ok "versionCode is 75" || bad "versionCode is not 75"
grep -q 'extra\["bitchordVersionName"\] = "2.2.3-djmix33"' build.gradle.kts \
  && ok "versionName is 2.2.3-djmix33" || bad "versionName is not 2.2.3-djmix33"

# --- SCOPE: which behaviour edits are in this tree ---------------------------
say ""
say "--- scope (reported, not enforced) ---"
if grep -q 'THREAD_PRIORITY_DEFAULT' \
     app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt 2>/dev/null; then
  note "analysis priority left conditional — vc76 scope, absent here"
else
  note "analysis pinned to BACKGROUND — scheduling edit present"
fi
if grep -q 'audioPrioritySet' \
     app/src/main/java/com/music/bitchord/playback/audio/PrecisionAudioSink.kt 2>/dev/null; then
  note "render-thread priority raise present"
else
  note "no render-thread priority raise — vc76 scope, absent here"
fi

# --- HARD: the success-path test --------------------------------------------
# Compiled the way CI compiles it — two translation units, the test and the
# implementation — so green here means green there, not something adjacent.
say ""
if [ ! -f "$TEST" ]; then
  bad "$TEST missing — the success path is still untested"
else
  if g++ -std=c++17 -O2 -I native/analyzer "$TEST" "$NATIVE" \
        -o "$WORK/vst_bin" 2>"$WORK/vst.build.log"; then
    if "$WORK/vst_bin" >"$WORK/vst.out" 2>&1; then
      ok "success-path test: $(grep -c '^ok' "$WORK/vst.out") assertions passed"
    else
      rc=$?
      if [ "$rc" -eq 139 ]; then
        bad "success-path test SEGFAULTED (exit 139) — this is the vc74 crash"
      else
        bad "success-path test FAILED (exit $rc)"
      fi
      sed -n '1,20p' "$WORK/vst.out"
    fi
  else
    bad "test did not compile — see $WORK/vst.build.log"
    sed -n '1,12p' "$WORK/vst.build.log"
  fi
fi

say ""
if [ "$fail" -ne 0 ]; then
  say "GATE FAILED — do not build, do not push, do not install."
  exit 1
fi
say "GATE PASSED — tree is safe to build."
