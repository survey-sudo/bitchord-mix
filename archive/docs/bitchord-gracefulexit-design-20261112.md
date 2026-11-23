# GRACEFUL-EXIT DESIGN DOC — BitChord day-one cut bug (prepared LOCALLY, no cloud)
# Sprint checkpoint SPRINT-2, 2026-11-12T16:27Z. Implementation artifact (prior session): /home/hfzhkn/bitchord-djmix11-patch-20261112T1620Z.patch (89K, 28 files incl. 2 NEW: AutomixTelemetry.kt + ExitTailTest.kt)
# Base tree: cloud /data/workspace/bitchord-task2/work/BitChord @ dc78e0d + djmix10 diff (vc52, 2.0.0-djmix10). Bumps to vc53 / 2.0.1-djmix11. [CLOUD-BLOCKED] to apply/build.

## 1. Local source situation (reconstruction verdict)
- NO full BitChord source clone exists locally (checked /home/hfzhkn: only .patch files, APKs, PDFs, zips; bkmasterplayer Rust reference at /home/hfzhkn/bkmasterplayer is reference-only, not BitChord).
- Local usable sources: (a) /home/hfzhkn/bitchord-djmix11-patch-20261112T1620Z.patch — the graceful-exit implementation as a complete diff (code-ready, needs cloud tree to apply); (b) /home/hfzhkn/bitchord-expanded-patch-final-20261112T0700Z.patch (djmix10, the base the djmix11 patch builds on); (c) /home/hfzhkn/bitchord-paused-patch-20261112T0545Z.patch (thin precursor); (d) /home/hfzhkn/Downloads/HANDOVER_MASTER.pdf text (565 lines extracted); (e) /home/hfzhkn/Downloads/bitchord-cursor-convos.zip + BITCHORD_SESSION_EXPORT.zip (BITCHORD_SESSION_CONVERSATION.md, BITCHORD_HANDOVER.md with U1-U12 history); (f) /home/hfzhkn/bkmasterplayer (MIT reference for echo-past-EOF / tempo_jump_plan).
- Code-ready NOW: djmix11 patch hunks + ExitTailTest (shared unit tests, compiled+passed on cloud per TRACE-DONE: shared 511 + app 1263 = 1774 execs, 0 failures — build job was running when cloud dropped; release APK NOT yet produced).
- Needs cloud tree to apply: verify patch --dry-run against dc78e0d+djmix10 tree, run Gradle (can't run here per handover rule + no cloud), build/sign/install/telemetry proof. ALL [CLOUD-BLOCKED], see handover SPRINT-5.

## 2. Early-fix history (what the original fix was, and what regressed it)
- djmix2 (vc44, 10 Nov 2026) U5 fix: "fade measured from the moment it actually starts; outgoing gain is a continuous curve with no step" (BITCHORD_SESSION_CONVERSATION.md:402; BITCHORD_HANDOVER.md:334). Also fixed short overlap: beatmix 8-16 bars, filter 4-8 bars when the track has room. User still unhappy ("sloppy, lazy, unnatural, stuttery + laggy"; 100x beat-match "still existing in latest build, djmix2" — BITCHORD_HANDOVER.md:339). RC-1 (dual-player blend) + RC-7 (abrupt outgoing EOF, no echo-past-EOF) stayed "architectural", exactly as HANDOVER.md U5 predicted.
- djmix3 (vc45) effect rules (BITCHORD_HANDOVER.md:345): "An instrumental blend can flange (wet 0.42, over the last several bars) or throw a one-beat echo that leaves the dry signal in place. Two singers do not get the flanger." Later HANDOVER_MASTER.pdf: "The native flanger is off; only the smoothed fallback flanger plays" (pdf lines 411, 424) — the djmix3 flanger rule SURVIVED in the classic chain but the NATIVE path (LoopMixRenderer) never got it; native renderDeck stops at stopAt with NO ramp (only deck IN has 25ms fadeOutAt).
- What regressed it (this sprint's video + TRACE-DONE code find): TransitionMoves.sheet() for M9/RELAXED sets outGain = lane(-o*4, 0f, 0.0, 0f) — outgoing lane FLAT AT UNITY through landing L, never falls; every other template ends mute at 0.0. Envelope::at holds values.back() (=0dB full level) forever, so the outgoing plays at unity until buffer/stopAt kills it. Combined with classic SWAP_RAMP_BLEND_MS=120ms swap ramp + phraseLock replans/slides breaking the "fade measured from actual start" anchor assumption, the djmix2 continuous-gain guarantee does not hold on the RELAXED/phrase path that produced the filmed hole. Native path has no exit dissolve at all (IN-only 25ms fadeOutAt).

## 3. Graceful-exit requirement (from sprint brief)
Outgoing NEVER audible->silence in <300ms on ANY rung (echo/loop/phrase/filter/classic) incl. bail/fallback. Scheduled at handoff: equal-power fade tail (800ms-2 beats) + tempo-synced echo throw and/or flanger dissolve on outgoing wet lane, tail kept alive past EOF until <-60dB or max ~1-2 bars, only then stop/release. Single choke-point function if possible.

## 4. How djmix11 patch implements it (per-file, file:line vs handover/audit)
- shared/.../smart/PhraseConfig.kt: exitTailSeconds(beatSec) = 2 beats floored 800ms capped 2s (EXIT_TAIL_MIN_MS 800, EXIT_TAIL_EMERGENCY_MS 350).
- shared/.../smart/LoopMix.kt: Plan.exitTailSeconds + TransitionMoves.sheet() + withExitTail(lane, tailBeats) helper extends EVERY outGain lane (incl. flat RELAXED/VOCAL) with final mute knot at landing+tail (equal-power fall). Single choke point for the shared/stateful layer. (TransitionMoves.compile sets it; LoopMixSession extends OUT PCM region + stopAt by tail.)
- shared/.../smart/ExitTailTest.kt (NEW, 4 tests): proves RELAXED/VOCAL outGain lanes mute over >=2-beat tail with no >6dB steps; exitTailSeconds 2-beat floored/capped. Covers: no gain step > threshold in 50ms; tail scheduled per rung; emergency dissolve floor 350ms.
- native/loopmix/loop_mix_renderer.{cpp,h} + app/src/main/cpp/jni/loopmix_jni.cpp + LoopMixRenderer.kt: OUT PCM region + stopAt extended by tail; fractional-delay flanger on OUT (script.flanger, 8-beat LFO, wet 0.42 — djmix3 instrumental rule; existing classic-chain flanger untouched); exit-gate backstop counter (audible at stopAt → telemetry). Tail kept alive past EOF until <-60dB (gate) or cap.
- app/.../playback/CrossfadeController.kt: new Phase.TAIL + beginTailOrFinish/driveTail (800ms planned dissolve, 350ms emergency; finish() may only retire a silent player) + BAIL_MS floor 350ms + freeze guard includes TAIL + mix.plan.exitTailSec + mix.exit.tail/mix.exit.gate telemetry + detail line "exit tail Xs". Classic dual-player path (the filmed rung) covered here.
- app/.../playback/loopmix/LoopMixSession.kt: plan.out.loopEnd + 0.5 + exitTailSeconds; stopAt = releaseAt + 0.05 + echoHold + exitTailSeconds; exit-dressing flanger only where plan flagged (instrumental rule preserved).
- Tempo-synced echo throw: existing ECHO_DELAY_BEATS 0.75 / DjMix echo (wet 0.4 fb 0.55 1 beat) retained; djmix11 adds tail PAST EOF (inTail = echoTail ? TAIL_SECONDS + beatSec*8) so outgoing echoes ring through the tail instead of cutting at file end (fixes T12 inside-file truncation, 0.3s-before-EOF anchor).
- Telemetry: mix.plan.exitTailSec + mix.exit.tail (tailMs/mode/gain) + mix.exit.gate (violations) + existing freeze/reject/wrap counters → verify on device via automix-telemetry.jsonl.

## 5. What remains [CLOUD-BLOCKED] (see handover SPRINT-5 for exact resume list)
Apply patch -> build (vc53, oldkey 20c66ad6...) -> sign -> install via wireless -> drive transition -> telemetry + screenrecord envelope A/B proof (258ms/15ms -> >=800ms dissolve).

## ADDENDUM [PROOF-1] 2026-11-12T09:28Z — device verdict + precise next-patch diagnosis (no cloud touched)
Measured on djmix11 (vc53) with zero worker taps (passive monitor while the phone played the user's own queue):
unearthed TWO natural transitions — T1 UN DIA->Se Vienen Cositas (native M6, mix.plan exitTailSec=1.349s exact 2 beats
@88.93BPM + FIRST mix.exit.gate violations=0 at 09:23:54.015Z) and T2 Se Vienen Cositas->item10 (native M6,
exitTailSec=0.947s exact @126.70BPM; no gate — user paused at handoff). Evidence:
bitchord-device-evidence/djmix11-proof-20261112T0922Z/ANALYSIS.md (+ telemetry pulls 1-5, passive-samples.txt).
Corrections to SPRINT-4 in the live handover: (a) "all 297 fallbacks are outgoing decode 17203 ms over" is FALSE
(12/297 = 4.0%; dominant outgoing stream 17ms over 145 + outgoing splice 49012 ms over 101 — timing/stream races,
NOT one decode signature); (b) the 07:08-08:10Z 911-line telemetry bulk is djmix10-era, djmix11 session starts
09:21:16Z. Verdict T1 = GRACEFUL-BY-STRUCTURAL-PROOF (HIGH confidence silent-before-stop via the in-renderer gate;
MEDIUM for full audible envelope — no PCM measured; scrcpy absent). T2 = NO-VERDICT (interrupted).

### Why mix.exit.tail is (correctly) absent — NOT a wiring gap
- `mix.exit.tail` is emitted ONLY in `CrossfadeController.beginTailOrFinish` (patch hunk ~274):
  `AutomixTelemetry.event("mix.exit.tail", mapOf("tailMs" to ..., "mode" to ..., "gain" to vol))`, gated on
  out.volume > 0.001; if vol <= 0.001, `finish()` runs with NO event. This is the CLASSIC dual-player path
  (Phase.TAIL, 800ms planned / 350ms emergency dissolve, BAIL_MS floor max(...,350)).
- Both observed transitions are NATIVE LoopMix (template M6): their exit proof is `mix.exit.gate` read from
  `session?.renderer?.exitGateViolations()` (patch hunk ~340, `?: -1` null-session default), emitted at loop
  completion in the "player is now the only source" branch. A native transition can NEVER emit mix.exit.tail.
- Consequence: the filmed bug's rung (classic "Smooth fade RELAXED") is the one path with ZERO djmix11 device
  evidence. The classic Phase.TAIL fix is proven only by offline ExitTailTest (shared) + code. A classic
  transition must be driven and required to emit mix.exit.tail planned + take no silence hole; if it emits nothing
  while audible-retire happens, the bug is: (1) `beginTailOrFinish` arming — the call site `handOff(out, player)`
  then `beginTailOrFinish(out)` (patch hunk ~250) ignores nothing, but if `fadeMs > 0` is false it takes the
  emergency 350ms (fine); (2) `driveTail` is polled only if `phase == Phase.TAIL` is checked in the tick loop —
  verify the tick poll covers TAIL (freeze guard includes TAIL, but confirm the drive branch exists); (3) if
  `finish()` is reached from another site (bail/error/watchdog) WITHOUT going through beginTailOrFinish, the retire
  is uninstrumented and un-dissolved — audit all `finish()` call sites for Phase.TAIL coverage (the bail hunk ~307
  floors BAIL_MS at 350ms, but other call sites may not).

### Precise next-patch candidates (diagnose-only this sprint — DO NOT build without cloud)
1. CLASSIC proof gap (primary): force one classic transition on djmix11 (pair the planner routes to classic,
   e.g. a RELAXED classic pair like the filmed one). Require mix.exit.tail (planned 800ms, gain>0) + zero hole.
   If mix.exit.tail absent: is the transition really classic (template field distinguishes LoopMix vs classic choix)?
   If classic + no tail event + audible retire: audit `beginTailOrFinish` call-site coverage of all `finish()` sites
   (CrossfadeController.kt:2590 bail branch + 3579 loop-completion branch + handOff path — confirm the 2590 bail
   branch also disses via beginTailOrFinish and not just a floor).
2. Emit-point causality (T1 gate 09:23:54.015 BEFORE its plan 09:23:54.414, plan lags handoff ~15s): `mix.plan` is
   emitted inside the phrase-line builder guarded by `logKey` change (patch hunk ~332, `if (logKey != phraseLogKey)`).
   If that builder is only invoked at status/UI time (describeLoop state tick), the FIRST call may lag the actual lock
   by up to the UI tick; and the gate+plan 0.4s spacing suggests both flushed at session teardown. Decide whether
   "plan ts" is usable as lock time; if not, emit at compile/lock inside the plan function instead. Affects
   duration attribution, not exit correctness.
3. Interrupted completion (T2): user pause at position 0 on handoff emitted NO gate and NO fallback/freeze — the
   session teardown path for user-pause-during-blend is uninstrumented. Consider an explicit
   `mix.session.aborted` (reason: user-pause) so gate=absent becomes distinguishable from gate=0-skipped. Also
   T2's pause-at-0 deserves code audit: did finish() retire the WRONG player (classic finish-keeps-outgoing
   comment, hunk ~250: "what [finish] keeps" — incoming must win)?
4. Planner spam/waste (not exit-related, same telemetry): 511 identical before-window rejects (no backoff), phrase-null
   tried=2 twice-off-phrase vetoes, quality none/none trusted via status ready (T1/T2 plans). Unchanged from M4.
5. Taste remains: relaxed/native M6 tolerates +42.5% BPM delta (T1 88.93->126.70) with no beatmatch — the graceful
   exit now dissolves a jump that musically still jumps. Exit fix does not close the taste gap (M5 table stands).
