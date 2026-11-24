# Automix change coupling (300-commit window)

HEAD: `c14987810eb6e4c488a1436e14f9a392ae960a88 chore: bootstrap ae3 branch, progress ledger, untangle method, and core ADRs`
Window inspected: newest `2026-11-24`, oldest `2026-11-03` (300 commits via `git log -n 300 --name-only --pretty=format:"COMMIT:%H %s"`).

Window composition: 300 commits, 69 merges. Plain `--name-only` prints no file list for merge commits, so merges contribute 0 file changes (see sensitivity note). Scope-touching commits: 93 including merges (93 non-merge). Distinct scope files touched: 147 (non-merge view / all view: 147).

Jaccard = co / (fa+fb-co); lift = co*N / (fa*fb), N = scope-touching non-merge commits (93). co = commits touching both files. Scope = Part D2 patterns (same as deps.py/skeleton.py).

## Top 40 co-changing pairs (by co-count, then Jaccard)

| # | File A | File B | co | fa | fb | Jaccard | lift |
|---|---|---|---|---|---|---|---|
| 1 | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt` | 12 | 19 | 17 | 0.500 | 3.46 |
| 2 | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | 12 | 23 | 15 | 0.462 | 3.23 |
| 3 | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | 10 | 23 | 38 | 0.196 | 1.06 |
| 4 | `native/loopmix/loop_mix_renderer.cpp` | `native/loopmix/loop_mix_renderer.h` | 8 | 9 | 8 | 0.889 | 10.33 |
| 5 | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | 8 | 19 | 38 | 0.163 | 1.03 |
| 6 | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `native/loopmix/loop_mix_renderer.h` | 7 | 15 | 8 | 0.438 | 5.42 |
| 7 | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | 7 | 15 | 9 | 0.412 | 4.82 |
| 8 | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `native/loopmix/loop_mix_renderer.cpp` | 7 | 15 | 9 | 0.412 | 4.82 |
| 9 | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | 7 | 23 | 9 | 0.280 | 3.14 |
| 10 | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `native/loopmix/loop_mix_renderer_ab_main.cpp` | 6 | 15 | 7 | 0.375 | 5.31 |
| 11 | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | 6 | 15 | 8 | 0.353 | 4.65 |
| 12 | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | 6 | 23 | 6 | 0.261 | 4.04 |
| 13 | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `native/loopmix/loop_mix_renderer.h` | 6 | 23 | 8 | 0.240 | 3.03 |
| 14 | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | 6 | 23 | 8 | 0.240 | 3.03 |
| 15 | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `native/loopmix/loop_mix_renderer.cpp` | 6 | 23 | 9 | 0.231 | 2.70 |
| 16 | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt` | 6 | 19 | 17 | 0.200 | 1.73 |
| 17 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | 6 | 38 | 15 | 0.128 | 0.98 |
| 18 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt` | 6 | 38 | 17 | 0.122 | 0.86 |
| 19 | `native/loopmix/loop_mix_renderer.h` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | 5 | 8 | 6 | 0.556 | 9.69 |
| 20 | `native/loopmix/loop_mix_renderer.cpp` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | 5 | 9 | 6 | 0.500 | 8.61 |
| 21 | `native/loopmix/loop_mix_renderer.cpp` | `native/loopmix/loop_mix_renderer_ab_main.cpp` | 5 | 9 | 7 | 0.455 | 7.38 |
| 22 | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | 5 | 15 | 5 | 0.333 | 6.20 |
| 23 | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | 5 | 6 | 15 | 0.312 | 5.17 |
| 24 | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | 5 | 15 | 6 | 0.312 | 5.17 |
| 25 | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | 5 | 19 | 9 | 0.217 | 2.72 |
| 26 | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | 5 | 19 | 23 | 0.135 | 1.06 |
| 27 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | 5 | 38 | 9 | 0.119 | 1.36 |
| 28 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | 4 | 4 | 4 | 1.000 | 23.25 |
| 29 | `app/src/main/cpp/jni/loopmix_jni.cpp` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` | 4 | 4 | 6 | 0.667 | 15.50 |
| 30 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | 4 | 4 | 6 | 0.667 | 15.50 |
| 31 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | 4 | 4 | 6 | 0.667 | 15.50 |
| 32 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | 4 | 5 | 6 | 0.571 | 12.40 |
| 33 | `app/src/main/cpp/jni/loopmix_jni.cpp` | `native/loopmix/loop_mix_renderer.h` | 4 | 4 | 8 | 0.500 | 11.62 |
| 34 | `native/loopmix/loop_mix_renderer.h` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | 4 | 8 | 4 | 0.500 | 11.62 |
| 35 | `native/loopmix/loop_mix_renderer.h` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | 4 | 8 | 4 | 0.500 | 11.62 |
| 36 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjColour.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | 4 | 4 | 8 | 0.500 | 11.62 |
| 37 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | 4 | 4 | 8 | 0.500 | 11.62 |
| 38 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMaps.kt` | 4 | 8 | 4 | 0.500 | 11.62 |
| 39 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | 4 | 8 | 4 | 0.500 | 11.62 |
| 40 | `app/src/main/cpp/jni/loopmix_jni.cpp` | `native/loopmix/loop_mix_renderer.cpp` | 4 | 4 | 9 | 0.444 | 10.33 |

## Per-file change frequency (top 25)

| # | File | Touches |
|---|---|---|
| 1 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | 38 |
| 2 | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | 23 |
| 3 | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | 19 |
| 4 | `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt` | 17 |
| 5 | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt` | 17 |
| 6 | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | 15 |
| 7 | `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | 9 |
| 8 | `native/loopmix/loop_mix_renderer.cpp` | 9 |
| 9 | `native/loopmix/loop_mix_renderer.h` | 8 |
| 10 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | 8 |
| 11 | `native/loopmix/loop_mix_renderer_ab_main.cpp` | 7 |
| 12 | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` | 6 |
| 13 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | 6 |
| 14 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | 5 |
| 15 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | 5 |
| 16 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | 5 |
| 17 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt` | 5 |
| 18 | `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | 4 |
| 19 | `app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt` | 4 |
| 20 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuSession.kt` | 4 |
| 21 | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MixViewPopup.kt` | 4 |
| 22 | `app/src/main/cpp/jni/loopmix_jni.cpp` | 4 |
| 23 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | 4 |
| 24 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | 4 |
| 25 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` | 4 |

## Hotspot clusters

Tight threshold: co>=3 and Jaccard>=0.30 (93 qualifying edges, 2 clusters). Looser threshold (co>=2, Jaccard>=0.15) yields 2 component(s), largest 70 files -- i.e. the automix change network is one weakly-connected blob at that level, so only the tight clusters are actionable.

### Cluster 1 (31 files, co-sum 974)
- `app/src/main/cpp/jni/loopmix_jni.cpp` (touches=4)
- `app/src/main/cpp/jni/npu_jni.cpp` (touches=3)
- `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` (touches=23)
- `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` (touches=6)
- `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` (touches=15)
- `app/src/main/java/com/music/bitchord/playback/smart/NpuNative.kt` (touches=3)
- `app/src/main/java/com/music/bitchord/playback/smart/NpuSelfTest.kt` (touches=3)
- `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` (touches=9)
- `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` (touches=4)
- `native/loopmix/loop_mix_renderer.cpp` (touches=9)
- `native/loopmix/loop_mix_renderer.h` (touches=8)
- `native/loopmix/loop_mix_renderer_ab_main.cpp` (touches=7)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjColour.kt` (touches=4)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` (touches=4)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` (touches=5)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` (touches=3)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` (touches=8)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` (touches=4)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMaps.kt` (touches=4)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` (touches=4)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` (touches=5)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` (touches=5)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` (touches=4)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` (touches=6)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` (touches=3)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` (touches=3)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` (touches=3)
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` (touches=4)
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuSession.kt` (touches=4)
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt` (touches=5)
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MixViewPopup.kt` (touches=4)

### Cluster 2 (2 files, co-sum 12)
- `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` (touches=19)
- `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt` (touches=17)

## Sensitivity: with vs without merges

Merge-inclusive top-5 pairs identical to non-merge top-5: True. Rationale: `git log --name-only` (no `-m`) prints no files for merges, so merges add no (file,commit) pairs; merge-inclusive metrics (fa, co, Jaccard) are therefore identical, only the lift denominator N changes (93 non-merge vs 93 incl.). Adding `-m` would split each merge into per-parent diffs and double-count pair evidence -- rejected as noisy, per brief.

## Caveats

- Renames appear under their new path only (no `--follow`); renamed-file coupling may be split across old/new names.
- Only files matching Part D2 scope patterns are counted; co-changes with out-of-scope shared files (e.g. other `playback/*.kt`, `data/settings/*` neighbours) are invisible to this analysis. Combine with deps_report.md for the structural (non-historical) view.
