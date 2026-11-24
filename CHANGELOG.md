# Changelog

One short section per build (Part B11.3). Install with `adb install -r`
only — never uninstall, never clear data. versionCode only goes up.

## 1.10-djmix53 (vc95 / AE 3.0.0)

The AE3 baseline: automix rebuilt around one decision-maker, an honest
countdown, and measurable quality gates.

- **One brain.** Every transition is planned once by the preparer and
  frozen; the controller only executes it. No more watchdog rescue
  starting "right now".
- **Honest countdown.** Seconds are shown only when the mix is READY;
  the next-track → READY budget is ≤ 15 s cold, ≤ 3 s warm.
- **Faster analysis.** Nirovi (beats) + Syvenu (vocals) run on CPU in
  ≤ 1.5 s per track; stems separate only what the recipe needs.
- **Better placement & effects.** Exits leave vocal lines, entries start
  at the intro, and all 10 effects (loop, roll, sweep, flanger, phaser,
  riser, brake, spinback, echo, gate) are tuned per recipe — 26 recipes.
- **NPU route.** CPU by default; the Hexagon V73 add-on
  (`com.music.bitchord.loopfix.npu.v73`) is the path to `ep=QNN` once
  its payload is staged.
- **Quality gates.** Every release must pass native probes (deterministic
  FP, ASan/UBSan), synthetic `mixq` renders, the fork-hooks check, the
  `@AudioThread` alloc lint, and this APK audit.

### Phone verification — [PENDING VERIFICATION] until M11

The Part A §10 table below is filled from real telemetry on the owner's
phone during the release run. Until then, every row is a placeholder.

| Metric | Target | Result |
|---|---|---|
| Transitions with an audible overlap | 100% | [PENDING VERIFICATION] |
| `mix.countdown.broken` | 0 | [PENDING VERIFICATION] |
| Next-known → READY p50 / p95 | ≤ 6 s / ≤ 15 s | [PENDING VERIFICATION] |
| Analysis ms per track (CPU / NPU) | ≤ 1.5 s / < CPU | [PENDING VERIFICATION] |
| Stem ms per needed window | ≤ 8 s | [PENDING VERIFICATION] |
| Path = preparer-frozen | 100% | [PENDING VERIFICATION] |
| Exit mid-vocal (Syvenu > 0.5 at exit ± 1 beat) | 0 | [PENDING VERIFICATION] |
| `mix.moved` / last-resort count | report both | [PENDING VERIFICATION] |
| Festival distinct recipes / FX types over 12 | ≥ 7 / ≥ 7 | [PENDING VERIFICATION] |
