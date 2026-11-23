# BitChord Forensics — ATEEZ Crescendo (164 BPM) → CLOSE YOUR EYES Bubble Tea (90 BPM) “3 Bars” messy transition

**Pair:** Outgoing `ATEEZ — Crescendo`, 164 BPM, 10A, 03:45, “Ashes to Light”. Incoming `CLOSE YOUR EYES — Bubble Tea`, 90 BPM, 5A, 02:43, “Snowy Summer”. UI label `3 Bars`.
**Scope:** Read-only. Code at `/data/workspace/bitchord-task2/work/BitChord` on `bk-android` (`instabox exec bk-android`). No build, no install, no code change.
**Device logs:** Checked `/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/vc70-device/` — contains only launch-crash / fixed-build verification (`vc70-device-report.md`: Poco F6, `bitchord-2.2.3-djmix28-fixed…`, NPU not observed, no playback-triggered analysis). `grep -rn Crescendo|Bubble Tea|TEEZ|CLOSE` returns 0 hits for this pair in that bundle. There is **no** `automix-telemetry.jsonl`, `mix.dump`, or `TransitionEvidenceCache` capture for this pair in evidence, so the branch is inferred from code + the `3 Bars` arithmetic + screenshot envelope shape, not from a logged plan. Items marked **[no-log]** cannot be confirmed without device logs or a replay of the stored analyses.

All code refs are `file:line` from the `bk-android` tree.

---

## 0. Arithmetic the rest of the report uses

- Raw ratio `164 / 90 = 1.822`.
- Planner fold: `alignTempoOctave(164, 90)` picks fold `0.5` → incoming folded to `180`. `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt:240` (`alignTempoOctave`), `TransitionPolicy.kt:144` (`stretchReachable`), `TransitionPolicy.kt:138` (`incomingRateBetween = outgoing / folded`).
- Post-fold delta `|180/164 − 1| = 0.0976` (9.8%).
- Required incoming rate to beatmatch `164 / 180 = 0.9111`.
- Outgoing beat `60/164 = 0.366 s`; bar `1.463 s`. Incoming bar at native `60/90×4 = 2.667 s`.
- Camelot distance `10A vs 5A = 5 steps` (same ring, `|10−5| = 5`). See `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt:74` (`camelotSteps`), `HarmonicMix.kt:53` (`camelotCompatible = same ring ≤1 or same number` → incompatible). For `TransitionPlanner`’s decline gate the inputs are key *names* via `trustedExitKey/trustedEntryKey`, passed to `camelotSteps` which re-codes them (`TransitionPlanner.kt:369`, `HarmonicMix.kt:83`).

---

## 1. H1 — Is 164 a real tempo or a double-time (octave) error?

### Verdict
**Cannot determine from code alone whether 164 is musically correct or a 2× 82-BPM octave error — but the pipeline can produce exactly that error and stores nothing that would let the planner recover from it.** `[no-log]` : without the track audio + stored `TrackAnalysis` (bpm, beatInterval, downbeats, beatConfidence, tail/head beats) for “Crescendo”, any claim of “genuine” vs “octave error” is speculation.

What *can* be determined:

| Question | Answer with evidence |
|---|---|
| Where is BPM computed? Two sources. | (a) Model: `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt:1584` → `BeatTracker.tempoFromBeats(beats)`; `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt:303` is `median(gaps)` re-filtered, `bpm = 60/interval` if in `MIN_TEMPO..MAX_TEMPO` (`BeatTracker.kt:241`, `BeatTracker.kt:314`). **No half/double disambiguation, no prior, no octave candidate.** If the Beat This! model fires on eighths, the median halves and the reported BPM doubles, verbatim. (b) DSP fallback: `native/analyzer/tempo_analysis.cpp:241` `AnalyzeTempo` — autocorrelation search 70–200 BPM (`tempo_analysis.cpp:263`), double-lag term `0.42 × Correlation(lag*2)` (`tempo_analysis.cpp:271`), then a metrical re-examination over `{0.5, 1.0, 2.0}` with `MetricalPrior(bpm)` (`tempo_analysis.cpp:284`, `tempo_analysis.cpp:301`) *can* move the winner by an octave. The code comment explicitly says the double-lag term previously “wins half-tempo” for 140/150/174 BPM and the re-exam was added to fix it (`tempo_analysis.cpp:276`). So the DSP path has an octave *choice*, the model path does not. The displayed 164 could come from either; `TrackAnalyzer.kt:1454` / `TrackAnalyzer.kt:960` take `grid?.bpm ?: features.bpm` (`leading?.bpm ?: features.bpm`), i.e. model first, DSP fallback. |
| Is a confidence / half-time candidate stored? | No. `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt:54` `bpm`, `TrackAnalysis.kt:56` `beatInterval`, `TrackAnalysis.kt:62` `beatConfidence` (0–1). No `bpmConfidence`, `halfTime`, `tempoOctave`, `beatConfidence` alternative. `grep halfTime|tempoOctave|bpmConfidence` across `shared/.../smart/*.kt` + `app/.../smart/*.kt` + `native/analyzer/*` returns 0 hits. Alternatives in `TransitionAlternatives.kt` are *transition families* (muffle/drop/bridge), not tempo candidates. `StructureAnalyzer.kt` / `InterpolationAnalyzer.kt` only read the scalar `bpm` (`StructureAnalyzer.kt:35`). |
| If 164 is 2× 82, what grid was laid down? | `beatInterval = 60/164 = 0.366 s` (or `60/82 = 0.732 s` if correct). The planner uses whichever interval is stored (`TransitionPlanner.kt:575`, `TransitionPlanner.kt:1896`, `TransitionPolicy.kt` passim) to snap edges (`alignedTransitionStart`, `onDownbeatBefore`), size overlaps in beats, and drive `beatSeconds`. A doubled grid is twice as dense: a “bar” is 1.46 s vs the true 2.93 s, so downbeat snapping lands on a half-bar and `fadeSeconds/(4×beatSeconds)` double-counts bars (see §2). I cannot verify from code which density “Crescendo”’s stored grid has without the run; the UI’s `164 BPM` string is `analysis.bpm` verbatim (`CrossfadeController.kt` status `label()` reads `analysis.bpm`, cf. `TransitionPlanner.kt:207` `TransitionPlan.outgoingBpm`). |
| Does anything reconcile a 2× ratio before planning? | **Only folding of the *incoming* tempo into the *outgoing* octave — never re-estimating the outgoing track’s own octave.** `TransitionPolicy.kt:240` `alignTempoOctave` picks fold `{1, 2, 0.5}` nearest the outgoing BPM on log distance. `TransitionPlanner.kt:475` `normalizedTempoRatio` folds with thresholds `1.5 / 0.67`. `TransitionPolicy.kt:144` `stretchReachable` checks the *folded* rate in `MIN_INCOMING_RATE..MAX_INCOMING_RATE` (`TransitionPolicy.kt:75`: `0.9..1.25`). That is the *entire* 2× mechanism. If the outgoing 164 is itself doubled, the fold still brings 90→180 and preserves the wrong octave: `180/164 = 1.098` is the ratio the planner sees either way. There is no code that tries `outgoing/2` vs `incoming` as a pair-level vote. |
| Why 164 *could* still be a plausible reading | The DSP prior favours ~120 BPM (`tempo_analysis.cpp:224` width `0.7` octave). `MetricalPrior(164) ≈ 0.81` vs `MetricalPrior(82) ≈ 0.74` — 164 is only slightly favoured, so a strong double-lag correlation can still tip the metrical vote to 164 even for 82-BPM material. The model path has no prior at all. Both paths can thus report 164 for 82 material; neither leaves a trace of the other candidate. |

**Bottom line for H1:** The app has **no pair-level octave reconciliation** — it only folds the *reported* incoming BPM to the *reported* outgoing BPM. A doubled outgoing grid (0.366 s beats) flows straight into bar math, downbeat snapping, and the `3 Bars` label, and nothing in `TrackAnalyzer.kt`, `BeatTracker.kt`, `StructureAnalyzer.kt`, `InterpolationAnalyzer.kt`, or `native/analyzer/tempo_analysis.cpp` stores an alternate to reconsider it. Whether “Crescendo” *is* 164 is `[no-log]`.

---

## 2. H2 — Which branch produced `3 Bars`, and why 3?

### 2.1 Where the label comes from
Classic (ExoPlayer) path only. `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:5421`:
```kotlin
val bars = if (plan.beatSeconds > 0) (plan.fadeSeconds / (4 * plan.beatSeconds)).roundToInt() else 0
val length = if (bars > 0) "$bars bars" else …
```
`CrossfadeController.kt:5422`. The detail is `"$summary. $length. …"`. The LoopMix/native path uses a different headline (`"${rung} · ${seconds}s · ${tempo}"`, `CrossfadeController.kt:5335` area / `LoopMixSession` describe) and never formats `$bars bars` this way. `AutomixStatus.bars` (`shared/.../AutomixStatus.kt:16`) is the same rounded value. So **`3 Bars` is `round(fadeSeconds / (4×beatSeconds))` from a classic `TransitionPlan`**.

### 2.2 Planner order (classic)
`CrossfadeController.kt:1803` `advanced = true`; `CrossfadeController.kt:1807` `planTransition(..., mode = SMART, advanced = true)`. Inside `TransitionPlanner.kt:1681` → `planTransitionInner` (`TransitionPlanner.kt:1772`):
1. `assessTransitionTier` (`TransitionPolicy.kt:638`) → `BEATMATCHED` or `DJ_ASSISTED` (or `PLAIN_CROSSFADE` only if both confidences `<0.2` or BPM outside `40..220`).
2. **Decline veto** (`TransitionPlanner.kt:1884`): `if (UNMIXABLE_VETO_ENABLED && isUnmixablePair(...))` (`TransitionPlanner.kt:96`, `TransitionPlanner.kt:402` → `shouldDeclineToMix`).
3. Phrase-bars (`phraseBars 8|16` only, `TransitionPlanner.kt:1966`), phrase-switch (`TransitionPlanner.kt:2002` → `TransitionPlanner.kt:1233`), then `mixPlan`/`adaptiveOverlap`.

### 2.3 The decline gate — key clash is *not* behind a tempo-match check; it is behind a tempo-*distance* check
`TransitionPlanner.kt:357` `shouldDeclineToMix`:
```kotlin
val outBpm = analysis.bpm.orZero(); val inBpm = nextAnalysis.bpm.orZero()
val folded = alignTempoOctave(outBpm, inBpm)
val delta = abs(folded / outBpm - 1.0)
if (delta <= PhraseConfig.TEMPO_HARD_DELTA) return false   // TransitionPlanner.kt:364, PhraseConfig.kt:89 = 0.08
val steps = camelotSteps(left, right)                       // TransitionPlanner.kt:369 (regional keys)
val keyClash = steps != null && steps > 2 && leftConf >= 0.5 && rightConf >= 0.5  // TransitionPlanner.kt:375
val dualVocal = … >= 0.62 both                              // TransitionPlanner.kt:376, VOCAL_CONFLICT_PROBABILITY TransitionPlanner.kt:1334
return keyClash || dualVocal
```
For this pair `delta = 0.0976 > 0.08`, so the first gate **passes** (decline is *only* considered when tempos are far after folding; tempo-matched pairs can never decline). `steps = 5 > 2`. Whether `keyClash` fires depends on `leftConf/rightConf ≥ 0.5` (native key confidences) `[no-log]`; or `dualVocal` if both `vocalProbability ≥ 0.62`. If either holds, `isUnmixablePair` is true. This is the correct reading of the gate: there is no “tempo match first, then key” ordering that would block this pair — the pair is tempo-far, so it clears the first hurdle.

### 2.4 Why exactly 3 bars
The decline branch is a **fixed 4-second fade**, not a bar count:
`TransitionPlanner.kt:1914` `val declineFade = 4.0.coerceAtMost(finalMixAnchor.coerceAtLeast(0.5))`, `TransitionPlanner.kt:1915` `transitionStart = finalMixAnchor − declineFade`, style `DJ_FILTER` (`TransitionPlanner.kt:1931`), `incomingPlaybackRate` unset → default `1.0` (`TransitionPlanner.kt:220`). At 164 BPM: `4.0 / (4×0.366) = 2.73 → roundToInt() = 3`. **The minimum is not “3 bars”; the constant is 4.0 s (comment: “2B bounded decline … 4 s filtered/carved DJ_FILTER … Lengthening or shortening it stays in 3.5–4.5 s” `TransitionPlanner.kt:1885`).** `3 Bars` is that 4 s expressed in 164-BPM bars.

Alternative branches do *not* give 3:
- `mixPlan`/`adaptiveOverlap` (`TransitionPlanner.kt:1485`, `TransitionPlanner.kt:1525`): tempoFar = `!stretchReachable` = false (0.911 ∈ `0.9..1.25`), but `sameBeatBlend` false because `foldedRateDelta = |1−0.911| = 0.089 > TEMPO_HARD_DELTA 0.08` (`TransitionPlanner.kt:2101`). Overlap 16 beats = `16×0.366 = 5.86 s` → `5.86/1.463 = 4 bars`. Classic `traits==null` path gives the same 16 (`TransitionPlanner.kt:1493`).
- Phrase-switch (`TransitionPlanner.kt:1233`): needs `harmonicallyCompatible` (true here on pitch distance: B-minor vs C-minor = 1 semitone ≤2, `TransitionPlanner.kt:493`) **and** `tier == BEATMATCHED` and `stretchRatio ∈ 0.9..1.25` and, if both-vocal, `|ratio−1| ≤ 0.06` (`TransitionPlanner.kt:1257`). `0.089 > 0.06` → refused when both-vocal; otherwise beats 4–16 could be 12 → 3 bars, but that would be `DJ_BLEND` with `filterSweep = 0.0` (`TransitionPlanner.kt:1275`), not the screenshot’s filter-closing lines, and it is only reached *after* the decline veto.
- `phraseBarPlan` (8/16 bar fixed): never 3 (`TransitionPlanner.kt:674`).
- Standard/plain fallbacks: `standardFade` 6–8 s (`TransitionPlanner.kt:1645`, `CrossfadeController.kt:1790`) → 4–5 bars; `PLAIN_CROSSFADE` 6 s → 4 bars.

So **the `3 Bars` label is the 4-second bounded-decline `DJ_FILTER`**, assuming the decline gate fired. If the key/vocal confidences did *not* clear 0.5/0.62, the next stop is `mixPlan`’s `DJ_FILTER` at 4 bars — the label would read `4 Bars`, not `3 Bars`. The user’s screenshot is therefore consistent with decline-fired and inconsistent with no-decline (subject to `[no-log]`: a `mixPlan` whose `fadeSeconds` was shrunk by `mixEnd×0.4` / `nextLength×0.4` clamps (`TransitionPlanner.kt:2120`) could also render 3 bars in other geometries, but no such clamp is in evidence for a 03:45 track).

### 2.5 Classic vs LoopMix native
**Classic (ExoPlayer).** `LoopMix.plan` returns null for declined pairs: `shared/.../LoopMix.kt:318` (`if (shouldDeclineToMix(out, inn)) return null … "decline to mix"`). The native `LoopMixSession` path (`LoopMixSession.kt`) therefore never runs for this pair; `loopPlanned` stays null and `describeAutomix` falls to the `lastSmartPlan` branch (`CrossfadeController.kt:5395`+) that emits the `$bars bars` string. Envelopes are the classic riders (§3), not the native clock.

---

## 3. H3 — Envelopes: were both decks “full-band”?

### 3.1 What ran
Decline branch → `TransitionStyle.DJ_FILTER`, `filterVariant = fallbackVariantFor(...)` (`SWEEP` or `BASS_HANDOFF`, `TransitionPlanner.kt:1916`, `TransitionPlanner.kt:409`), `filterSweep = FILTER_SWEEP = 1.0` (`TransitionPlanner.kt:879`), `bassSwap` only if `BASS_HANDOFF`. Renderer: `CrossfadeController.kt:5944` → `rideFilterSweep` (`CrossfadeController.kt:6207`) or `rideFallbackBassHandoff` (`CrossfadeController.kt:6240`). The screenshot’s two yellow lines (outgoing descending LP, incoming ascending HP) are `rideFilterSweep` verbatim: `tracks.outgoing(cutoff, bassCutoff(handover))`, `tracks.incoming(OPEN_HZ, maxOf(entryCorner, bassCutoff(1−handover)))` (`CrossfadeController.kt:6228`).

### 3.2 Envelope math for the 4.0-s window (progress 0→1)
Constants: `OPEN_HZ = 20000` / `OFF_HZ = 20` (`TransitionFilterProcessor.kt:526`), `FILTER_ENTRY_HZ = 7000`, `FILTER_FLOOR_HZ = 1500` (`CrossfadeController.kt:6924`, `CrossfadeController.kt:6931`), `FILTER_SWEEP_SHAPE = 1.8`, `ENTRY_HIGH_PASS_HZ = 1200`, `ENTRY_OPEN_BY = 0.6`, `ENTRY_SHAPE = 0.35` (`CrossfadeController.kt:7069`+). Gain: `MixGain.rise = sin`, `fall = cos` (`MixGain.kt:14`), shaped by `FILTER_RISE_SHAPE = 0.65`, `FILTER_FALL_SHAPE = 1.15` (`CrossfadeController.kt:6939`).

- **Outgoing LP** `glide(7000, 1500, progress^1.8)`: progress 0 → 7000 Hz; 0.5 → `7000×(1500/7000)^0.287 ≈ 4500 Hz`; 0.9 → ≈1950 Hz. It never goes below 1500 Hz. The vocal body (≈200–3000 Hz) is *inside* the passband for the whole fade; only air/presence above 1.5–7 kHz is progressively removed.
- **Incoming HP** `entryHighPass(progress)` from 1200 Hz to ~20 Hz by `0.6` (`CrossfadeController.kt:6280`): at 0.5 the corner is ≈174 Hz (geometric glide with `ENTRY_SHAPE 0.35`). So the incoming body is already present at the midpoint.
- **Gains**: at 0.5, shaped rise `sin(0.5^0.65×π/2) ≈ 0.84`, shaped fall `cos(0.5^1.15×π/2) ≈ 0.76` (`MixGain.kt:14`, `CrossfadeController.kt:6411`, `CrossfadeController.kt:6451`). Both decks are at **>70% gain simultaneously** across the middle; `powerSum ≈ 1` (equal-power, `MixGain.kt:18`) means total energy is flat while *both arrangements* play.
- **Bass**: `bassCutoff(handover)` swaps below 200 Hz (`BASS_SWAP_HZ`, `CrossfadeController.kt:6935`) at `filterSwapAt = 0.5` (`CrossfadeController.kt:6985` area) — only one kick plays, but mid/high is unseparated.

**Confirmed from math (not the image): both decks are wide-band and high-gain for ~60% of the 4 s.** The overlap band at the midpoint is roughly 174 Hz–4500 Hz — north of 4 octaves shared, exactly the vocal fundamentals, at comparable fader levels, at *different* tempi. That is the screenshot’s “both wide-band for a large portion” and the listener’s “overlapping”.

### 3.3 Why the vc70 decline-rescue families did not save it
vc70 alternatives are **nested *inside* the decline branch, after the veto**:
`TransitionPlanner.kt:1884` → `TransitionAlternatives.select(...)` (`TransitionPlanner.kt:1910`); if `select` returns a candidate the plan is that candidate (`muffle` / `drop-in` / `beatless-bridge`, `TransitionAlternatives.kt:460` registry), else the bounded `DJ_FILTER` decline above. The gates are *stricter* than the veto they rescue:

- **Muffle** (`TransitionAlternatives.kt:168`): needs non-empty `lowEnergyCurve` on both sides, **both `beatConfidence ≥ 0.55`** (`MIN_BEATMATCH_CONFIDENCE`, `TransitionPolicy.kt:51`, gate at `TransitionAlternatives.kt:178`), `foldedDelta ≤ MUFFLE_MAX_DELTA 0.10` (`TransitionAlternatives.kt:49`, gate `TransitionAlternatives.kt:183` — this pair *passes* at 0.0976, narrowly), a real vocal collision (`dualVocal ≥0.62` or simultaneous >0.25, `TransitionAlternatives.kt:186`), bass energy drop `≤5 dB` (`TransitionAlternatives.kt:49`), opening ≥2 s, body ≥0.3, and no vocal-trade at the swap (`TransitionAlternatives.kt:203`). Any one of confidence / no-curve / no-collision / energy / swap-voice nulls it.
- **Drop-in** (`TransitionAlternatives.kt:281`): same 0.55 + 0.10 gates, plus **simultaneous ≤0.25** (i.e. it refuses vocal collisions — the decline trigger itself, `TransitionAlternatives.kt:298`) and entry energy ≥0.5.
- **Beatless bridge** (`TransitionAlternatives.kt:365`): needs ≥4 s beatless runs on both edges (`TransitionAlternatives.kt:378`) and **refuses severe harmonic clash** — `harmonicSevereClash` (`TransitionAlternatives.kt:106`: steps >2 **and both confidences ≥0.65**) returns `"keys refuse the bridge"` (`TransitionAlternatives.kt:369`). For a 5-step clash with trusted keys, the bridge refuses *because* of the clash the decline was for.

So for a 9.8%-far, 5-step-clash pair, the bridge is self-defeating (clash → refuse), drop-in is self-defeating (vocal collision → refuse), and muffle survives only if the analyser also gave both tracks ≥0.55 beat confidence, full bass curves, and a measured collision. `[no-log]` which gate actually nulled it here; the telemetry `gates` map (`TransitionEvidence.kt:35`, emitted as `mix.dump` `CrossfadeController.kt:1919`) would name it, but no `mix.dump` for this pair exists in evidence (§5). **The structural point is `file:line`-certain: the rescue families live behind `isUnmixablePair` *and* then impose higher confidence thresholds (0.55/0.65 vs 0.5) plus harmonic/vocal vetoes that re-reject the very pairs the veto targets**, so a large class of declined pairs falls through to the generic 4-s `DJ_FILTER` decline — which is still two unsynced tempos played raw (§4).

*(If the veto itself did not fire — key confidences <0.5 and not dual-vocal — the alternatives are never even consulted, and the pair falls to the even longer 5.9-s `DJ_FILTER` `mixPlan`. Either way the rescue did not engage.)*

---

## 4. H4 — Was any stretch applied?

### 4.1 Decline path (the `3 Bars` path): **rate 1.0, no stretch**
The decline plan literal sets `incomingCueTime`, `beatSeconds = beatSec`, `filterSweep`, but no rate; default `incomingPlaybackRate = 1.0` (`TransitionPlanner.kt:220`). The rate-setting path for the blend it replaces — `incomingRateBetween` (`TransitionPolicy.kt:138`) → `incomingRateFor` clamping to `AUDIBLE_MIN_RATE..AUDIBLE_MAX_RATE` (`TransitionPlanner.kt:1325`, `TransitionPolicy.kt:89`: `0.94..1.09`) — is not invoked. **So two tempos, 164 vs 90, play at native speed against each other for 4 s.** That alone is the “grids colliding” sound. `[no-log]` cannot confirm the played rate without telemetry `mix.plan rate=` (`CrossfadeController.kt:1867`), but the code path for a decline is rate-1.0 by construction.

### 4.2 What a blend would have needed, and why the planner should have refused it
Required `0.911` is inside the *considered* window `MIN_INCOMING_RATE..MAX_INCOMING_RATE = 0.9..1.25` (`TransitionPolicy.kt:75`), so `stretchReachable = true` (`TransitionPolicy.kt:144`), yet **outside the *audible* window `0.94..1.09`**. `incomingRateFor` clamps `0.911 → 0.94` (`TransitionPlanner.kt:1325`), leaving a residual `(0.94×180 − 164)/164 = +3.2%` fast — over 4 s (≈11 beats) ≈ 0.35 beats of drift, i.e. a permanent flam. The planner already refuses the beatmatch on tempo grounds: `sameBeatBlend` requires `foldedRateDelta ≤ 0.08` (`TransitionPlanner.kt:2101`), and `0.089 > 0.08`, so style is `DJ_FILTER`, not `DJ_BLEND`. In other words, **the blend was correctly refused; the fallback that remains still plays the pair at unmatched tempo.** The `0.9..1.25` vs `0.94..1.09` gap is the trap: `stretchReachable` says “reachable” for 0.911 while the renderer cannot play it.

### 4.3 Outgoing rate
Always `1.0` by design (`TransitionPlanner.kt:1329` `outgoingRateFor = 1.0`; audible outgoing deck never tempo-shifted). No speed-up of the 164 deck toward 180, no slow-down of the 90 deck toward 164.

---

## 5. Also checked (telemetry / paths)

- **Classic vs native:** Classic ExoPlayer. See §2.1, §2.5. Native `LoopMixSession` (`app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt:30`) does not run for declined pairs (`LoopMix.kt:318`).
- **Evidence cache / `mix.dump`:** `TransitionEvidenceCache.record` is called for decline (`TransitionPlanner.kt:1927`), alternatives (`TransitionPlanner.kt:1909`), and standard plans (`TransitionPlanner.kt:1741`); the controller dumps `TransitionEvidenceCache.recent(1)` as `mix.dump` (`CrossfadeController.kt:1919`) into `automix-telemetry.jsonl` (`AutomixTelemetry.kt:12`). **No `automix-telemetry.jsonl` or `mix.dump` file for this pair is present** in `/home/hfzhkn/Bitchord/evidence/bitchord-device-evidence/vc70-device/`; the bundle is crash-verification only (see header). An on-device pull of `/sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl` (`AutomixTelemetry.kt:14`) is the artefact that would name `style/reason/gates` directly — **not available, request it instead of guessing.**
- **Listening-session logs:** `vc70-device/` has no working log capture for playback of these tracks (`vc70-device-report.md` §4: “Analysis hasn’t been triggered … media session state NONE”).

---

## 6. Root cause (one sentence)

The planner’s octave fold turns 164-vs-90 into a 9.8% gap that is simultaneously *inside* its 0.9–1.25 “reachable” window and *outside* its 8% blend cap and 0.94–1.09 playable window, so it declines to a fixed 4-second (`3 Bars` at 164 BPM) `DJ_FILTER` at native tempo (rate 1.0) whose 1500-Hz floor and equal-power faders leave both vocal arrangements wide-band and simultaneous for the whole overlap, while the muffle/drop-in/bridge rescues nested behind that decline impose higher (0.55/0.65) confidence, bass-curve, and anti-clash gates that re-reject this class of pair and fall through to the same decline.

---

## 7. One defect or several?

**Several independent defects**, each sufficient to keep this pair messy:

1. **Planner octave handling is one-directional.** Only the incoming BPM is folded to the outgoing (`TransitionPolicy.kt:240`); a doubled outgoing grid is never second-guessed and no half-time candidate is stored (`TrackAnalysis.kt:54`). A 2× error on the *outgoing* track therefore survives planning as a 9.8% “far” pair and a half-density bar grid.
2. **Decline gating mismatch.** The veto needs `keyConf ≥0.5` or dual-vocal (`TransitionPlanner.kt:375`); the rescues inside it need `beatConf ≥0.55` (`TransitionAlternatives.kt:178`) and `≥0.65` for harmony (`TransitionAlternatives.kt:106`), plus bass curves and beatless edges. A pair can be vetoed *or* missed by the veto, and in three of four combinations the rescues still refuse it (bridge refuses severe clash at `TransitionAlternatives.kt:369`; drop-in refuses the vocal collision at `TransitionAlternatives.kt:298`).
3. **Legibility gap between “reachable” and “playable”.** `stretchReachable` uses `0.9..1.25` (`TransitionPolicy.kt:75`) while the renderer clamps to `0.94..1.09` (`TransitionPolicy.kt:89`, `TransitionPlanner.kt:1325`). 0.911 is “reachable” on paper and unplayable in fact; the planner handles the contradiction by refusing the blend (`TransitionPlanner.kt:2101`) but then plays the *filter* fallback at raw tempo.
4. **Bounded-decline rendering is not a rescue.** The 4-s `DJ_FILTER` floor of 1500 Hz (`CrossfadeController.kt:6931`) with `FILTER_SWEEP_SHAPE 1.8` keeps vocals in-band and both faders >0.7 at the midpoint (§3.2). It bounds *duration*, not *collision*.

---

## 8. Fix shape (existing architecture, owning layer — no new subsystem)

**Owning layer: the planner/policy, not the analyser or the renderer.** Concretely `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` + `TransitionPolicy.kt` (with `TransitionAlternatives.kt` gates), which already own folding, tiering, and decline selection:

- *Pair-level octave check in the planner:* when scoring a pair, fold **both** directions (incoming→outgoing *and* outgoing/2→incoming, and outgoing→incoming/2) and prefer the fold whose post-fold delta is smallest, instead of always normalising incoming to the outgoing octave (`TransitionPolicy.kt:240`, `TransitionPlanner.kt:475`). This keeps a single call-site (the fold helpers) and mirrors what the planner already does for stretch; no analyser change needed to challenge a stored 164.
- *Single playable-window check:* gate blend-vs-filter on the **audible** window (`0.94..1.09`, `TransitionPolicy.kt:89`) and make `stretchReachable` (`TransitionPolicy.kt:144`) agree with `incomingRateFor` (`TransitionPlanner.kt:1325`), so a pair requiring 0.911 is classified tempo-far *before* the decline/alternative nest — and the decline head (`TransitionPlanner.kt:1884`) is evaluated for tempo-far + clash even when key/beat confidences are below 0.5, with the rescue nest consulted from that same gate rather than behind the stricter confidence veto.
- *Rescue-gate alignment:* relax or align `TransitionAlternatives.kt:178` (0.55) / `TransitionAlternatives.kt:106` (0.65) with `TransitionPlanner.kt:375` (0.5) and remove the self-rejecting checks (bridge’s `harmonicSevereClash` at `TransitionAlternatives.kt:369` should not refuse the clash it exists to bridge; drop-in’s vocal-collision refusal at `TransitionAlternatives.kt:298` should not refuse the dual-vocal case the veto targets). Which gate values are right is a listening decision the evidence dump (`mix.dump`, `CrossfadeController.kt:1919`) exists to inform — pull one before tuning.

Explicitly *not* a fix here: changing the 4.0-s decline constant (`TransitionPlanner.kt:1914`) in isolation, which only re-labels “3 Bars” to “2/4 Bars” without separating the vocals; the renderer already does the right *kind* of separation (`rideFilterSweep`, `CrossfadeController.kt:6207`) when the plan gives it a rescuable pair.

---

## 9. Which existing tests should have caught it, and why they didn’t

- **`Djmix20RegressionTest`** (`shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/Djmix20RegressionTest.kt:72`, `Djmix26SharedTest.kt:757`, `Djmix25SharedTest.kt:143`): assert `shouldDeclineToMix` for far-tempo + clash + dual-vocal fixtures. They use **high-confidence, fully-specified fixtures** (key confidences above 0.5, dual vocal above 0.62) where the veto fires cleanly. No fixture exercises the 0.911 edge — a pair *just inside* `0.9..1.25` / `0.10` (`TransitionAlternatives.kt:49`) but *outside* `0.08` (`PhraseConfig.kt:89`) and *outside* `0.94..1.09` (`TransitionPolicy.kt:89`) — so the “reachable but unplayable” contradiction is never asserted.
- **`TransitionAlternativesTest`** (`TransitionAlternativesTest.kt:102`): asserts `shouldDeclineToMix` must hold *before* the alternatives are tested, with muffle fixtures that already clear 0.55 + curves + collision. It never tests the fall-through where the veto holds but `muffleGates`/`dropInGates`/`beatlessBridgeGates` each return a distinct gate-rejection (`beat-confidence`, `tempo-distance`, `vocal-collision`, `harmonic-clash`, `no-beatless-edge`) and the plan degrades to generic decline — which is the path this pair took. No case covers the bridge-refuses-severe-clash loop (`TransitionAlternatives.kt:369`) or the drop-in-refuses-collision loop (`TransitionAlternatives.kt:298`) with a 5-step + dual-vocal pair.
- **`Djmix12TempoTest` / `AutomixFreezeTest.kt:120`** (“74 half-time against 105 folds to 148”): covers the *fold arithmetic* (`TransitionPolicy.kt:240`) for a half-time *incoming* track. No test covers a half-time / double-time *outgoing* track surviving the fold, because `TrackAnalysis` has no half-time field to test (`TrackAnalysis.kt:54`). The tests therefore lock in one-directional folding.
- **Envelope/gain tests** (`ExitLegTest`, `VocalDepthFilterTest`, `AutomixFreezeTest`): assert fader shapes and filter cutoffs in isolation. None assert the *combined* midpoint condition — outgoing LP ≥ vocals + incoming HP ≤ vocals + both gains ≈0.7 — over a 4-s window at 164 BPM, which is the audible “both wide-band” condition (§3.2). The renderer’s `FILTER_SWEEP_SHAPE 1.8` back-loads the sweep, so a 4-s ride *is* the short case where back-loading hurts most; no test pins vocal separation for short `fadeSeconds`.

In short: the tests prove each gate in isolation on cooperative fixtures; they do not cover the 0.089–0.098 edge where the three windows (`0.08`, `0.10`, `0.94/0.9`) disagree, nor the veto→rescue fall-through, nor the double-time outgoing grid.
