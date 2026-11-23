# Echo selection and musical handoff: council findings

## Verdict

All four independent council reviews identify separate problems in musical selection and cut/entry timing that the vc87 envelope patch did not address. A smoother outgoing fade does not establish that Echo was the best candidate, that the outgoing lyric finished, or that the incoming pickup reached its intended landing.

The current final decision is made by handwritten planner rules using estimated beat, vocal, key, structure, and energy evidence. The names `clash` and `unmixable` do not certify that the recordings cannot form a musically acceptable transition.

This was a read-only review. No new code, APK, models, device playback, or queue changes were made. The phone was read to capture version, telemetry, and existing diagnostic logs. The user requested review of frequent inappropriate and abrupt Echo transitions; the original vc86 clip was not successfully heard or identity-mapped by the council.

## Source and device identity

- Installed app: vc87 / `2.2.3-djmix45`, package `com.music.bitchord.loopfix`.
- Installed APK SHA-256: `18e79528f2670e2d9b048068f89fa9c9bafb196df91df31fec27313c78a62a74`.
- Reviewed source: `work/bitchord-mix/.slim/worktrees/automix-echo-smoothing-vc87/`, the frozen composite, not Git HEAD alone.
- Source manifest SHA-256: `118b3e659d4a2a1bcdd11ba30457836c1c84bd408f42230c4d1b9939d2d0fdee`.
- Fresh evidence: `work/echo-policy-council-20261120/device-20261120T021906Z/`.
- Raw telemetry SHA-256: `b158ef35e3ab8b0bdd572bd5c0b96c45dfd941e0ed9581b844166cf49da9889b` (2,170 rows; 76 after verified vc87 installation).
- Detailed event classification: `work/echo-policy-council-20261120/postinstall-evidence-20261120.md`.

## Who decides whether to use Echo

1. An executable, locally clean two-sided loop has priority. Local lyric/mask and physical-rate/PCM protections remain authoritative. The vc86 recovery permits such a crown to overcome a coarse whole-track decline.
2. Without a crown, the coarse decline predicate uses folded tempo difference above 8%, plus either key distance greater than two Camelot steps with both confidences at least 0.5, or whole-track vocal scores of at least 0.62 on both sides. See `TransitionPlanner.kt:366`.
3. That predicate can bypass phrase planning before it examines candidate-specific vocal overlap and cues (`CrossfadeController.kt:413,4705`). Declined bridges try registered alternatives, then the Echo base; ordinary drop/blend selection is skipped (`LoopMix.kt:1221-1303`).
4. Nondeclined pairs can use phrase candidate scoring. If that route supplies nothing, the legacy native bridge attempts Drop, Beat blend, then Echo. Grid confidence, tempo preference, local evidence, and available room each constrain those attempts.
5. Capability and preparation constraints are separate: recording/output incompatibility, missing analysis, insufficient decode lead, or build/splice failure can select another renderer/fallback. These must not be reported as proof that the songs clash.
6. Echo is also an optional colour chosen by deterministic hashes within some paths. An audible echo alone does not identify an incompatible-pair fallback. Scriptless native Echo, scripted M6, classic echo-out, and `DjColour.ECHO` are distinct implementations.

Key and tempo are useful evidence, but the coarse vocal predicate does not establish two simultaneous leads at the actual join. Missing local information must remain unknown; it must not certify either instrumental room or a clash.

## Findings confirmed against source

### Early whole-track preference can prevent local comparison

The global vocal/tempo decline remains effective on lower-rung bridges even when a specific nonrepeating local overlap might work. Restoring qualified loop priority did not address that bridge decision. A witness must distinguish a song that cannot provide a safely repeatable loop from a song that can still participate in a straight handoff.

A global tempo mismatch is also different from the actual advancing rates emitted to a renderer. Retaining rate/PCM limits is essential; deleting the coarse gate or raising a number globally would not repair the candidate decision.

### Native Echo checks a setup position instead of its actual dry cut

`LoopMix.echoPlan` chooses roughly `contentEnd - 2 bars - 0.3 seconds`, snaps that start, then schedules the dry handoff one bar later. `snapOutStart` checks certain lyrics near the start and can revert to the raw target when the snapped point is sung. Neither step verifies the later fade/cut interval against lyric completion or musical resolution. Straight-plan retained lyric admission explicitly differs from repeatable-loop admission.

The current 0.75-beat dry fade can therefore be smooth while still removing the wrong part of the song. The incoming cue is likewise consumed while gain and low-pass are opening; preserving the source cue alone does not establish preservation of a cold vocal consonant or first kick.

References: `LoopMix.kt:1520-1641,1059-1075,1987-2050`; `LoopMixSession.kt:1331-1410`.

### Scripted M6 scoring and playback disagree

Ordinary M6 receives zero nominal overlap and zero simultaneous vocal collision in the phrase scorer. Compilation nevertheless creates a one-bar execution window, a zero incoming hold, and an incoming gain lane containing only 0 dB values. Native envelopes hold their first value before the first point, so incoming dry audio can play before the declared landing. Unequal incoming/outgoing bar durations can also put the source landing at a different time from the scored cue.

This is an independent source-level discrepancy; the two executed Echo cases in the fresh capture use the scriptless route, so the capture does not prove that M6 caused those listening complaints.

References: `PhrasePlanner.kt:579-613,787-800`; `TransitionMoves.kt:173-249,292-302,356-367`; `LoopMixSession.kt:932-967`; `native/loopmix/loop_mix_renderer.cpp:145-156`.

### Broadening phrase eligibility alone would be unsafe

The musical rescue path can return a candidate after strict candidates were rejected without rerunning the complete strict admission rules against its executable timing. Some uncertain key evidence influences phrase scores without the confidence treatment used by the coarse gate; some effect/cue logic can treat unmeasured vocal space as clean. These need explicit coverage/constraint handling before removing the early bypass.

References: `PhrasePlanner.kt:259-357`; `TrackAnalysis.kt:251-289`; `DjMix.kt:434-447`; `CuePoints.kt:255-278`.

## What the current phone records prove

There are two actual post-install Echo fires in the preserved capture, both native bridges with `coarseDecline=true`. Their adjacent bounded-pair dump records say `steps=? dualvocal`: no trusted key distance was available to produce the key branch, and the coarse vocal branch was active. There were no captured post-install prepared-stage rejections, arm deaths, or loop fallbacks forcing these selections.

Outgoing loop search rejected all 53 and 68 candidates on lyric/mask evidence. That supports retaining those loop protections. It does not establish that every nonrepeating join was unsuitable. One Echo completed; the other was interrupted by a seek and emergency tail. A third accepted Echo was cancelled by a queue edit before firing. Candidate counts must not be treated as executed mixes or field prevalence.

`loop-complete`, delegate consumption, phase verification, and zero exit-gate violations do not establish word completion, phrase fit, preserved attacks, or listening quality. This small capture supplies no song-title mapping or verified comparison with Apple Music.

## Recommended correction and review gates

Use one explicit handoff contract for the candidate being scored and rendered: outgoing capture interval and dry-fall interval; incoming source cue, hold, first audible time and musical landing; effect send closure and wet retirement; source-grid and evidence coverage. Select the musical cut first, then derive preparation timing backward.

Keep the first implementation bounded:

1. Reproduce wrong-but-smooth native cuts and scripted M6 timing disagreement with exact source-coordinate witnesses.
2. Repair native Echo cut/entry admission and make scripted scoring, compilation, and rendering use the same audible intervals. Preserve valid EOF during effect drain while rejecting/reconciling insufficient PCM before the dry handoff.
3. Reconsider the early decline only through locally qualified candidates whose actual vocal, rate, PCM, recording, and deadline checks pass. A coarse preference can remain when local evidence is missing. Reuse existing candidate facilities before attempting a wholesale ranking rewrite.
4. Require rescue candidates and optional effects to satisfy the same hard local constraints. A hash may select among eligible colours; it must not create eligibility.

Witnesses must include a lyric spanning the actual cut but not the setup point, a protected incoming pickup/cold attack, global/local metadata disagreement without an eligible crown, a true local vocal clash, unequal tempi in M6, tracked-grid drift, missing coverage, and outgoing PCM ending before versus after the contracted handoff. Preserve known successful crown/blend and cancellation/queue controls.

Actual music acceptance should compare the same recording and analysis at explicit cue times, including the reported bad pairs and controls. Reject amputated words, lost first attacks, unwanted rhythmic collisions, or a handback discontinuity even when scalar render metrics pass. The existing 25 focused, six JNI, 536 shared, and 1,457 app results are not evidence of musical acceptance.

The four reviews differ mainly in implementation order: GLM favors the demonstrated scriptless native cue defect first; Gemini prioritizes the M6 scoring/rendering contract before wider selection work; Deepseek and Kimi favor correcting local-candidate admission and timing coherently. The final synthesis resolves this as native cut/entry contract first, immediate M6/rescue consistency repair, then specific locally qualified nonlooping candidates past the coarse decline. Hard measured-vocal and execution checks must be enforced before eligibility is broadened. All reject another isolated fade tweak as a sufficient response and reject simply forcing loops or broadly disabling safety gates.

## Council provenance

- Deepseek: `ses_e0940cf57ffeg1Tf610qxRe7vE`.
- GLM: `ses_e0940cd12ffeIMTwA6PcPGULFH`.
- Gemini: `ses_e0940cc54ffeiQ2pZKOQ05J4sn`.
- Kimi: `ses_e0940cba5ffejFTI6uiMQQcE54`.
- Synthesis: `ses_e0938a251ffeMKXypeEuCyGyCR`.

Apple's published descriptions support adapting the transition to the music, including a simple crossfade or silence trimming when appropriate, and describe audio-feature analysis, time stretching, and beat matching. They do not disclose the thresholds or ranking internals. Apple AutoMix is the requested listening benchmark; source comments invoking parity are not evidence that it has been achieved.
