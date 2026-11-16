#!/usr/bin/env bash
# Pre-build gate for vc78 (2.2.3-djmix36). File kept as vc75-gate.sh for history; it now gates vc78. Needs only g++ — no Android SDK, no JDK, no Gradle.
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
trap 'rm -f "$WORK/vst_bin" "$WORK/vst.out" "$WORK/vst.build.log" "$WORK/wc_bin" "$WORK/wc.out" "$WORK/wc.build.log" "$WORK/lm_bin" "$WORK/lm.out" "$WORK/lm.probe.out" "$WORK/lm.build.log"' EXIT

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
grep -q 'extra\["bitchordVersionCode"\] = 78' build.gradle.kts \
  && ok "versionCode is 78" || bad "versionCode is not 78"
grep -q 'extra\["bitchordVersionName"\] = "2.2.3-djmix36"' build.gradle.kts \
  && ok "versionName is 2.2.3-djmix36" || bad "versionName is not 2.2.3-djmix36"

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

# --- HARD: window-path calibration -----------------------------------------
# The stem path is new working code as of vc75, so the invariants the automix
# heuristics stand on are checked rather than assumed: the gain curve the
# shipped code realises, the complement identity that legitimises
# "instrumental = dry - vocals", and sample alignment.
say ""
CAL="native/analyzer/window_calibration.cpp"
if [ ! -f "$CAL" ]; then
  bad "$CAL missing — the stem invariants are unverified"
else
  if g++ -std=c++17 -O2 -I native/analyzer "$CAL" "$NATIVE" \
        -o "$WORK/wc_bin" 2>"$WORK/wc.build.log"; then
    if "$WORK/wc_bin" >"$WORK/wc.out" 2>&1; then
      ok "window calibration: $(grep -c '^ok' "$WORK/wc.out") invariants held"
    else
      bad "window calibration FAILED (exit $?) — stem invariants do not hold"
      sed -n '1,25p' "$WORK/wc.out"
    fi
  else
    bad "window calibration did not compile — see $WORK/wc.build.log"
    sed -n '1,12p' "$WORK/wc.build.log"
  fi
fi

# --- HARD: loopmix A/B selftest + reverb-stop probe (vc78 reverb-tail) --------
say ""
LM_IMPL="native/loopmix/loop_mix_renderer.cpp"
LM_MAIN="native/loopmix/loop_mix_renderer_ab_main.cpp"
if [ ! -f "$LM_MAIN" ] || [ ! -f "$LM_IMPL" ]; then
  bad "loopmix A/B selftest missing — $LM_MAIN / $LM_IMPL not present"
else
  if g++ -std=c++17 -O2 -I native -I native/third_party "$LM_IMPL" "$LM_MAIN" \
        -o "$WORK/lm_bin" 2>"$WORK/lm.build.log"; then
    if "$WORK/lm_bin" --selftest >"$WORK/lm.out" 2>&1; then
      ok "loopmix A/B selftest passed: $(grep -c '^ok' "$WORK/lm.out") assertions passed"
    else
      rc=$?
      bad "loopmix A/B selftest FAILED (exit $rc)"
      sed -n '1,25p' "$WORK/lm.out"
    fi
    # vc78 HARD: production-faithful reverb-stop probe (same binary).
    # Thresholds live in the harness and are unchanged; this invocation
    # exists so the hard-cut defect cannot silently regress.
    if "$WORK/lm_bin" --reverb-stop-probe >"$WORK/lm.probe.out" 2>&1; then
      ok "loopmix reverb-stop probe passed"
    else
      rc=$?
      bad "loopmix reverb-stop probe FAILED (exit $rc)"
      sed -n '1,40p' "$WORK/lm.probe.out"
    fi
  else
    bad "loopmix A/B selftest did not compile — see $WORK/lm.build.log"
    sed -n '1,12p' "$WORK/lm.build.log"
  fi
fi

if [ "$fail" -ne 0 ]; then
  say "GATE FAILED — do not build, do not push, do not install."
  exit 1
fi

say "GATE PASSED — tree is safe to build."
