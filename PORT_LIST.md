# Port list: upstream v1.9.1..v1.10 inside fork-owned paths

Each commit below changed a file the fork owns. The merge KEPT the fork's version. For every row,
read the upstream diff and re-implement its intent in the fork code (or tick 'not applicable' with a reason).

- [x] `6dedd1c9` fix(player): outside shuffle buttons use the app's shuffle, not ExoPlayer's (Uvesh Menpur, 2026-11-06) — **Ported**: set `into.shuffleModeEnabled = false` in `CrossfadeController.begin()` and `CrossfadeController.armLoop()` (`PlaybackService.kt` half merged via hotspot merge).
- [x] `1db2b0c1` reduce apk size (Kushagra Singh, 2026-11-17) — **N/A / Deferred to Step 7 (`models-v1`) per Step 2 scope**: Step 2 retains `onnxruntime-android-qnn:1.29.0` + `qnn-runtime:2.42.0` and bundled `.onnx` assets (`TrackAnalyzer.unpackedAsset`, `BeatTracker`, `VocalTracker`, `app/build.gradle.kts`) so automix/NPU/SCNet stay working for the Step 2 gate. `AutomixModels.kt` is merged in-tree with `BUNDLED_ASSETS_ACTIVE = true` and `RELEASE_URL` pointed at `survey-sudo/bitchord-unified/releases/download/models-v1/model_` ready for Step 7 (`BASIC_OPT` fallback in `BeatTracker`/`VocalTracker` is N/A while full QNN ORT 1.29.0 ships).
- [x] `a637ba32` feat(player): prefetch next track artwork for AutoMix transitions (Galaxyyss, 2026-11-17) — **Ported**: added `onArmAutoMixIncoming: (MediaItem) -> Unit = {}` parameter to `CrossfadeController`, invoked at `begin()` and `armLoop()`, wired to `PlaybackService::prefetchAutoMixIncoming`.
- [x] `438994ca` feat(automix): publish AutoMix visual state and interruption cancellation (Galaxyyss, 2026-11-17) — **Ported**: added visual generation tracking, `armAutoMixVisual`, `clearAutoMixVisual`, `checkAutoMixVisualTimeline`, `cancelAutoMixVisual`, `effectiveFadeSpan` (preserving `djmix52`'s `fourBarsMs` cap), classic (`begin`/`startFade`/`handOff`/`driveFade`) and LoopMix (`armLoop`/`driveLoopMix`/`finishLoop` with `session.clock.releaseAt`) feed emissions, and interruption cancellation in `SeekAction` (`CANCEL`/`EMERGENCY_TAIL`), `onMediaItemTransition`, `onSkipRequested`, `tick`, `loopBail`, `release`, `finish`, and `bail(force)` (placed after the `!force` freeze guard).
- [x] `e7d10c0e` radio page (Kushagra Singh, 2026-11-18) — **N/A in fork-owned paths**: Radio UI merged normally in upstream-owned files; the only touch in `fork-owned` paths was `AutomixModels.RELEASE_URL`, which is superseded by `survey-sudo/bitchord-unified` `models-v1` + `BUNDLED_ASSETS_ACTIVE = true` in Step 2.
- [x] `dd102c2d` fix(player): smooth AutoMix artwork, Spotify Canvas handling, opt-in toggles (Kushagra Singh, 2026-11-21) — **Ported**: `CrossfadeController.driveFade()` now reads `outClock.read(out).toLong()` instead of raw `out.currentPosition` (`AutoMixRevealClock`, `AutoMixCover`, `AutoMixCanvas`, and `PlayerControls` glass-frost hold merged cleanly in `sharedUi`).

## Files
```
 app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt              | 162 +++++++++++++++++++++++++++++++++++++++++++++----
 app/src/main/java/com/music/bitchord/playback/smart/AutomixModels.kt              | 232 ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
 app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt              |  25 ++------
 shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt  |  43 ++++++++-----
 shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt |  32 ++++++----
 5 files changed, 435 insertions(+), 59 deletions(-)
```
Show one: git show <sha> -- <path>    All: git diff v1.9.1 v1.10 -- <path>

## Upstream ADDED files inside fork-owned paths (kept; review)
- `app/src/main/java/com/music/bitchord/playback/smart/AutomixModels.kt` — **Reviewed & kept compiling**: set `BUNDLED_ASSETS_ACTIVE = true` for Step 2 and pointed `RELEASE_URL` to `survey-sudo/bitchord-unified/releases/download/models-v1/model_` so no upstream download occurs before Step 7.
