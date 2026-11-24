# ADR 0008: Deterministic floating-point flags for render bit-identity

## Context
Part B1.3 requires `TransitionScript` renders to be bit-identical (matching SHA-256) between the Android device renderer and the host CLI, and Part B13 requires `-Wall -Wextra` plus ASan/UBSan on `native/loopmix`. Wave 1B found that `bitchord_loopmix` and `bitchord_stretch` compile with `-O3 -ffast-math` (`app/src/main/cpp/CMakeLists.txt:69, :83`), whereas CI's host harness builds at `-O2` without `-ffast-math` (`.github/workflows/android-ci.yml:66-69`) and runs no sanitizers on `loopmix`, while `bitchord_analysis` already disables `-ffast-math` (`:44-46`).

## Decision
Remove `-ffast-math` from `bitchord_loopmix` and `bitchord_stretch` (`CMakeLists.txt:69, :83`) and compile both Android NDK and host CLI loopmix targets with deterministic IEEE-754 contraction flags (`-fno-fast-math -ffp-contract=off`, matching `bitchord_dsd:93`). Enable `-Wall -Wextra` on `bitchord_analysis`, `bitchord_loopmix`, and `bitchord_stretch`, and add ASan/UBSan plus `phrase_isolator_test` and `loop_mix_renderer_startup_probe` to `.github/workflows/android-ci.yml`.

## Consequences
Device and host CLI renders share identical FP rounding and contraction semantics so B1.3 SHA-256 bit-identity tests hold across toolchains. Removing `-ffast-math` trades away aggressive auto-vectorization; M4/M9 benchmarks must verify renderer callback CPU stays within B15.2 (`≤ 20%` of one big core at p95).
