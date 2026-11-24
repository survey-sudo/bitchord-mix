# Automix (Part D2) dependency report

Scope files: **146** (HEAD `c1498781`). Tool: `tools/untangle/deps.py` (Python 3 stdlib; same scope as `skeleton.py`). File-level edges: **470** (Kotlin imports resolved: 205).

## 1. Layers and file inventory

| Layer (low -> high) | Rank | Files |
|---|---|---|
| `native/loopmix` | 0 | 7 |
| `native/analyzer` | 0 | 14 |
| `app/cpp` | 1 | 16 |
| `shared/smart` | 1 | 77 |
| `app/djmix` | 2 | 2 |
| `app/loopmix` | 2 | 3 |
| `app/analyzer` | 2 | 14 |
| `app/controller` | 3 | 6 |
| `app/service` | 4 | 1 |
| `app/settings_telemetry` | 4 | 2 |
| `app/ui` | 5 | 4 |

### `native/loopmix` (7 files)
- `native/loopmix/dj_fx.h`
- `native/loopmix/loop_mix_renderer.cpp`
- `native/loopmix/loop_mix_renderer.h`
- `native/loopmix/loop_mix_renderer_ab_main.cpp`
- `native/loopmix/loop_mix_renderer_startup_probe.cpp`
- `native/loopmix/phrase_isolator.h`
- `native/loopmix/phrase_isolator_test.cpp`

### `native/analyzer` (14 files)
- `native/analyzer/audio_analysis.cpp`
- `native/analyzer/audio_analysis.h`
- `native/analyzer/mel_spectrogram.cpp`
- `native/analyzer/mel_spectrogram.h`
- `native/analyzer/resampler.cpp`
- `native/analyzer/resampler.h`
- `native/analyzer/stem_dsp.cpp`
- `native/analyzer/stem_dsp.h`
- `native/analyzer/stem_dsp_test.cpp`
- `native/analyzer/tempo_analysis.cpp`
- `native/analyzer/vocal_spectrogram.cpp`
- `native/analyzer/vocal_spectrogram.h`
- `native/analyzer/vocal_spectrogram_test.cpp`
- `native/analyzer/window_calibration.cpp`

### `app/cpp` (16 files)
- `app/src/main/cpp/jni/analysis_jni.cpp`
- `app/src/main/cpp/dsd/dst/compat.h`
- `app/src/main/cpp/dsd/dst/decoder.c`
- `app/src/main/cpp/dsd/dst/decoder.h`
- `app/src/main/cpp/dsd/dsd_decimator.cpp`
- `app/src/main/cpp/bitperfect/exclusive_output.cpp`
- `app/src/main/cpp/dsd/dst/jni.cpp`
- `app/src/main/cpp/jni/loopmix_jni.cpp`
- `app/src/main/cpp/jni/mel_jni.cpp`
- `app/src/main/cpp/jni/npu_jni.cpp`
- `app/src/main/cpp/jni/stem_jni.cpp`
- `app/src/main/cpp/jni/stretch_jni.cpp`
- `app/src/main/cpp/bitperfect/usb_stream.h`
- `app/src/main/cpp/bitperfect/tests/usb_stream_test.cpp`
- `app/src/main/cpp/bitperfect/usb_transport.cpp`
- `app/src/main/cpp/jni/vocal_jni.cpp`

### `shared/smart` (77 files)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/AnalysisStore.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AutomixAnalysisSource.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AutomixStatus.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/AutomixV2Structure.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/BarFeatureExtractor.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/BeatFlow.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CatchUpPlanner.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CountdownStabilizer.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DeckBudget.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjColour.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/EchoAdmission.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/GridRepair.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/InterpolationAnalyzer.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/KickLock.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LocalBlendAdmission.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMixDuck.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopRegionCache.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/MelSpectrogram.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixGain.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixInspectorState.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixVerify.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MusicalHandoff.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NativeAnalysisLibrary.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuPolicy.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuQnnSession.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuSession.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/OrtStemEngine.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseGrid.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PrepLead.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/RegionalLoudness.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/ReleaseSplice.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/RetireGuard.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SeekAction.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMapEnrichment.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMaps.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StemAdapter.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StemBarsBuilder.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemCache.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StemClash.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemModelStore.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemSeparator.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureAdapterD.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureCues.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureLyrics.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureMix.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureTypes.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureV2.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/TrackFeatures.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionEvidence.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/VocalDepthFilter.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt`

### `app/djmix` (2 files)
- `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt`
- `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt`

### `app/loopmix` (3 files)
- `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixPort.kt`
- `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt`
- `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt`

### `app/analyzer` (14 files)
- `app/src/main/java/com/music/bitchord/playback/smart/AudioDecoder.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/AutomixModels.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoRendererBridge.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/LocalAudioSource.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/MixWaveform.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/NpuAndroid.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/NpuNative.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/NpuQdqSelfTest.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/NpuSelfTest.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/PhraseMaps.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/StemNative.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt`
- `app/src/main/java/com/music/bitchord/playback/smart/VersionAudioAligner.kt`

### `app/controller` (6 files)
- `app/src/main/java/com/music/bitchord/playback/AutoMixVisualFeed.kt`
- `app/src/main/java/com/music/bitchord/playback/BoundedContextPersistence.kt`
- `app/src/main/java/com/music/bitchord/playback/BoundedContextRequests.kt`
- `app/src/main/java/com/music/bitchord/playback/BoundedQueueEdits.kt`
- `app/src/main/java/com/music/bitchord/playback/BoundedQueuePlanner.kt`
- `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt`

### `app/service` (1 files)
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`

### `app/settings_telemetry` (2 files)
- `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt`
- `app/src/main/java/com/music/bitchord/playback/AutomixTelemetry.kt`

### `app/ui` (4 files)
- `app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MixViewPopup.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt`
- `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt`

## 2. Layer-to-layer edge table

File-pair counts aggregated per layer pair. Self-edges (within-layer) are listed but not drawn as arrows in SVG.

| From | To | File pairs | Kinds |
|---|---|---|---|
| `shared/smart` | `shared/smart` | 228 | fq-usage, import, same-package |
| `app/analyzer` | `shared/smart` | 53 | fq-usage, import, same-package |
| `app/controller` | `shared/smart` | 29 | fq-usage, import |
| `app/djmix` | `shared/smart` | 29 | fq-usage, import |
| `app/loopmix` | `shared/smart` | 17 | fq-usage, import |
| `app/analyzer` | `app/analyzer` | 16 | fq-usage, same-package |
| `native/analyzer` | `native/analyzer` | 9 | include |
| `app/ui` | `shared/smart` | 7 | fq-usage, import |
| `app/cpp` | `native/analyzer` | 6 | include |
| `app/service` | `app/controller` | 6 | same-package |
| `native/loopmix` | `native/loopmix` | 6 | include |
| `app/cpp` | `app/cpp` | 5 | include |
| `app/djmix` | `app/analyzer` | 4 | import |
| `app/settings_telemetry` | `shared/smart` | 4 | fq-usage, import |
| `app/analyzer` | `app/loopmix` | 3 | fq-usage, import |
| `app/controller` | `app/analyzer` | 3 | import |
| `app/controller` | `app/loopmix` | 3 | import |
| `app/cpp` | `shared/smart` | 3 | jni |
| `app/loopmix` | `app/loopmix` | 3 | same-package |
| `app/analyzer` | `app/settings_telemetry` | 2 | import |
| `app/controller` | `app/controller` | 2 | same-package |
| `app/controller` | `app/djmix` | 2 | fq-usage, import |
| `app/controller` | `app/settings_telemetry` | 2 | fq-usage, import, same-package |
| `app/cpp` | `app/analyzer` | 2 | jni |
| `app/djmix` | `app/djmix` | 2 | same-package |
| `app/djmix` | `app/loopmix` | 2 | import |
| `app/loopmix` | `app/analyzer` | 2 | import |
| `app/loopmix` | `app/djmix` | 2 | import |
| `app/service` | `app/analyzer` | 2 | fq-usage, import |
| `app/service` | `app/settings_telemetry` | 2 | import, same-package |
| `app/service` | `shared/smart` | 2 | fq-usage, import |
| `app/ui` | `app/settings_telemetry` | 2 | import |
| `app/ui` | `app/ui` | 2 | same-package |
| `app/analyzer` | `app/djmix` | 1 | import |
| `app/cpp` | `app/loopmix` | 1 | jni |
| `app/cpp` | `native/loopmix` | 1 | include |
| `app/djmix` | `app/settings_telemetry` | 1 | import |
| `app/loopmix` | `app/settings_telemetry` | 1 | fq-usage |
| `app/service` | `app/djmix` | 1 | fq-usage |
| `app/settings_telemetry` | `app/controller` | 1 | import |
| `app/ui` | `app/analyzer` | 1 | import |

## 3. File fan-in / fan-out (top 20 each)

Fan = number of DISTINCT neighbor files (not edge multiplicity).

### Fan-in (most depended-upon)

| Rank | File | Layer | Fan-in |
|---|---|---|---|
| 1 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | `shared/smart` | 28 |
| 2 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | `shared/smart` | 21 |
| 3 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | `shared/smart` | 16 |
| 4 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | `shared/smart` | 16 |
| 5 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | `shared/smart` | 16 |
| 6 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/smart` | 15 |
| 7 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | `shared/smart` | 15 |
| 8 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/smart` | 14 |
| 9 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | `shared/smart` | 13 |
| 10 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | `shared/smart` | 12 |
| 11 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | `shared/smart` | 11 |
| 12 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | `shared/smart` | 10 |
| 13 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | `shared/smart` | 10 |
| 14 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | `shared/smart` | 10 |
| 15 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` | `shared/smart` | 8 |
| 16 | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `app/settings_telemetry` | 7 |
| 17 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureTypes.kt` | `shared/smart` | 7 |
| 18 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/smart` | 6 |
| 19 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixInspectorState.kt` | `shared/smart` | 6 |
| 20 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | `shared/smart` | 6 |

### Fan-out (depends on most)

| Rank | File | Layer | Fan-out |
|---|---|---|---|
| 1 | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/controller` | 40 |
| 2 | `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/analyzer` | 33 |
| 3 | `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `app/djmix` | 25 |
| 4 | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `app/loopmix` | 23 |
| 5 | `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `app/analyzer` | 15 |
| 6 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/smart` | 14 |
| 7 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/service` | 13 |
| 8 | `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `app/djmix` | 13 |
| 9 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/smart` | 13 |
| 10 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/smart` | 11 |
| 11 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/smart` | 11 |
| 12 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | `shared/smart` | 10 |
| 13 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | `shared/smart` | 9 |
| 14 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/smart` | 8 |
| 15 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | `shared/smart` | 8 |
| 16 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt` | `shared/smart` | 8 |
| 17 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | `shared/smart` | 7 |
| 18 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` | `shared/smart` | 6 |
| 19 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/OrtStemEngine.kt` | `shared/smart` | 6 |
| 20 | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `app/settings_telemetry` | 5 |

## 4. Cycles (Tarjan SCCs)

### Layer-level SCCs (size > 1)

- **5 layers**: `app/analyzer`, `app/controller`, `app/djmix`, `app/loopmix`, `app/settings_telemetry`

### File-level SCCs (size > 1)

Count: 7 SCCs; self-loops: 0 (none expected; self-edges are dropped by construction).

- **19 files** (all `shared/smart` if uniform, else mixed):
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/BeatFlow.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CatchUpPlanner.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DeckBudget.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/EchoAdmission.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LocalBlendAdmission.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MusicalHandoff.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionEvidence.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/VocalDepthFilter.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/AutomixV2Structure.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureTypes.kt` (`shared/smart`)
- **9 files** (all `shared/smart` if uniform, else mixed):
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StemAdapter.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureAdapterD.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt` (`shared/smart`)
- **4 files** (all `app/djmix` if uniform, else mixed):
  - `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` (`app/djmix`)
  - `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` (`app/djmix`)
  - `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` (`app/loopmix`)
  - `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` (`app/analyzer`)
- **3 files** (all `shared/smart` if uniform, else mixed):
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuPolicy.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` (`shared/smart`)
- **2 files** (all `shared/smart` if uniform, else mixed):
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureMix.kt` (`shared/smart`)
- **2 files** (all `shared/smart` if uniform, else mixed):
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` (`shared/smart`)
  - `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` (`shared/smart`)
- **2 files** (all `app/ui` if uniform, else mixed):
  - `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MixViewPopup.kt` (`app/ui`)
  - `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt` (`app/ui`)

## 5. Volatility inversions

HARD = `shared/smart|native/* -> app/*`. SOFT-settings = `engine -> app/settings_telemetry` (global-settings reads). SOFT-ui/service = `engine -> app/ui|app/service`.

Counts: HARD=0, SOFT-settings=4, SOFT-ui/service=0.

| Type | From layer | To layer | File pairs |
|---|---|---|---|
| SOFT-settings | `app/analyzer` | `app/settings_telemetry` | 2 |
| SOFT-settings | `app/djmix` | `app/settings_telemetry` | 1 |
| SOFT-settings | `app/loopmix` | `app/settings_telemetry` | 1 |

### Inversion file edges

- [SOFT-settings] `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` -> `app/src/main/java/com/music/bitchord/playback/AutomixTelemetry.kt` kinds=import via=com.music.bitchord.playback.AutomixTelemetry
- [SOFT-settings] `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` -> `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` kinds=fq-usage via=com.music.bitchord.data.settings.AppSettings.automixWindowedStemsEnabled.value
- [SOFT-settings] `app/src/main/java/com/music/bitchord/playback/smart/AutomixModels.kt` -> `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` kinds=import via=com.music.bitchord.data.settings.AppSettings
- [SOFT-settings] `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` -> `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` kinds=import via=com.music.bitchord.data.settings.AppSettings

## 6. External dependencies

### Kotlin external imports (by 3-segment root; in-house `com.music.bitchord.*` outside the Part D2 scope is listed first and is NOT third-party)

| Root | Files importing | Sample full imports |
|---|---|---|
| `com.music.bitchord` | 189 | com.music.bitchord.AppIcon, com.music.bitchord.BuildConfig, com.music.bitchord.MainActivity, com.music.bitchord.R |
| `androidx.compose.ui` | 123 | androidx.compose.ui.Alignment, androidx.compose.ui.Modifier, androidx.compose.ui.composed, androidx.compose.ui.draw.alpha |
| `androidx.compose.foundation` | 99 | androidx.compose.foundation.Canvas, androidx.compose.foundation.Image, androidx.compose.foundation.ScrollState, androidx.compose.foundation.background |
| `androidx.compose.material` | 60 | androidx.compose.material.icons.Icons, androidx.compose.material.icons.automirrored.rounded.Notes, androidx.compose.material.icons.automirrored.rounded.VolumeDown, androidx.compose.material.icons.automirrored.rounded.VolumeOff |
| `androidx.compose.animation` | 45 | androidx.compose.animation.AnimatedContent, androidx.compose.animation.AnimatedVisibility, androidx.compose.animation.Crossfade, androidx.compose.animation.SizeTransform |
| `androidx.compose.runtime` | 39 | androidx.compose.runtime.Composable, androidx.compose.runtime.CompositionLocalProvider, androidx.compose.runtime.DisposableEffect, androidx.compose.runtime.LaunchedEffect |
| `kotlin.math.abs` | 36 | kotlin.math.abs |
| `kotlinx.coroutines.flow` | 35 | kotlinx.coroutines.flow.Flow, kotlinx.coroutines.flow.MutableSharedFlow, kotlinx.coroutines.flow.MutableStateFlow, kotlinx.coroutines.flow.SharedFlow |
| `java.util.concurrent` | 27 | java.util.concurrent.ConcurrentHashMap, java.util.concurrent.CopyOnWriteArraySet, java.util.concurrent.Executor, java.util.concurrent.ExecutorService |
| `androidx.media3.common` | 26 | androidx.media3.common.AudioAttributes, androidx.media3.common.C, androidx.media3.common.DeviceInfo, androidx.media3.common.FlagSet |
| `kotlin.math.max` | 26 | kotlin.math.max |
| `kotlin.math.min` | 25 | kotlin.math.min |
| `androidx.compose.material3` | 24 | androidx.compose.material3.AlertDialog, androidx.compose.material3.Button, androidx.compose.material3.ButtonDefaults, androidx.compose.material3.CircularProgressIndicator |
| `androidx.media3.exoplayer` | 19 | androidx.media3.exoplayer.DecoderReuseEvaluation, androidx.media3.exoplayer.DefaultLoadControl, androidx.media3.exoplayer.DefaultRenderersFactory, androidx.media3.exoplayer.ExoPlayer |
| `kotlin.math.roundToInt` | 17 | kotlin.math.roundToInt |
| `android.util.Log` | 14 | android.util.Log |
| `kotlin.math.PI` | 14 | kotlin.math.PI |
| `java.io.File` | 13 | java.io.File |
| `kotlin.math.ceil` | 12 | kotlin.math.ceil |
| `kotlin.math.cos` | 12 | kotlin.math.cos |
| `androidx.media3.session` | 12 | androidx.media3.session.CommandButton, androidx.media3.session.DefaultMediaNotificationProvider, androidx.media3.session.LibraryResult, androidx.media3.session.MediaConstants |
| `kotlin.math.sqrt` | 12 | kotlin.math.sqrt |
| `android.content.Context` | 11 | android.content.Context |
| `kotlin.math.sin` | 11 | kotlin.math.sin |
| `kotlinx.serialization.json` | 10 | kotlinx.serialization.json.Json, kotlinx.serialization.json.JsonArray, kotlinx.serialization.json.JsonObject, kotlinx.serialization.json.contentOrNull |
| `kotlin.math.pow` | 10 | kotlin.math.pow |
| `java.nio.file` | 10 | java.nio.file.AtomicMoveNotSupportedException, java.nio.file.Files, java.nio.file.LinkOption, java.nio.file.StandardCopyOption |
| `kotlin.math.floor` | 10 | kotlin.math.floor |
| `kotlin.math.roundToLong` | 8 | kotlin.math.roundToLong |
| `java.util.Locale` | 8 | java.util.Locale |
| `dev.chrisbanes.haze` | 8 | dev.chrisbanes.haze.ExperimentalHazeApi, dev.chrisbanes.haze.HazeState, dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi, dev.chrisbanes.haze.materials.HazeMaterials |
| `android.os.SystemClock` | 7 | android.os.SystemClock |
| `kotlinx.coroutines.Dispatchers` | 7 | kotlinx.coroutines.Dispatchers |
| `kotlinx.coroutines.launch` | 7 | kotlinx.coroutines.launch |
| `kotlin.math.exp` | 7 | kotlin.math.exp |
| `ai.onnxruntime.OrtEnvironment` | 7 | ai.onnxruntime.OrtEnvironment |
| `ai.onnxruntime.OrtSession` | 7 | ai.onnxruntime.OrtSession |
| `androidx.media3.datasource` | 6 | androidx.media3.datasource.DataSource, androidx.media3.datasource.DataSpec, androidx.media3.datasource.DefaultDataSource, androidx.media3.datasource.ResolvingDataSource |
| `kotlin.math.ln` | 6 | kotlin.math.ln |
| `kotlinx.coroutines.CoroutineScope` | 5 | kotlinx.coroutines.CoroutineScope |

### C++ angle includes (system)

- `<vector>` x26
- `<algorithm>` x17
- `<cmath>` x16
- `<jni.h>` x11
- `<cstdint>` x11
- `<cstdio>` x7
- `<cstring>` x5
- `<memory>` x5
- `<cstdlib>` x5
- `<cstddef>` x5
- `<string>` x5
- `<complex>` x5
- `<stdint.h>` x3
- `<atomic>` x2
- `<mutex>` x2
- `<stdexcept>` x2
- `<unordered_map>` x2
- `<new>` x2
- `<stdlib.h>` x2
- `<string.h>` x2
- `<array>` x2
- `<numeric>` x2
- `<limits>` x2
- `<oboe/Oboe.h>` x1
- `<sstream>` x1
- `<libusb.h>` x1
- `<sys/resource.h>` x1
- `<sys/time.h>` x1
- `<chrono>` x1
- `<condition_variable>` x1
- `<deque>` x1
- `<future>` x1
- `<thread>` x1
- `<limits.h>` x1
- `<stdio.h>` x1
- `<utility>` x1
- `<cctype>` x1
- `<fstream>` x1
- `<iostream>` x1

### Unresolved quoted includes (not in Part D2 scope)

- `app/src/main/cpp/jni/stretch_jni.cpp` includes `stretch/pitch_stretch.h` (not found in scope)
- `native/analyzer/stem_dsp.cpp` includes `../third_party/signalsmith-linear/fft.h` (not found in scope)
- `native/loopmix/loop_mix_renderer.h` includes `signalsmith-stretch/signalsmith-stretch.h` (not found in scope)

## 7. Method and limitations

- Kotlin imports resolved against an FQ symbol index built from top-level `class|interface|object|typealias|fun` declarations per package; longest-prefix match handles `Outer.Inner` imports. Aliases resolve via their FQ target.
- Same-package references scan comment/string-stripped code for simple names declared in the same package by OTHER files (one heuristic edge per file pair; a shared identifier could in principle be a local variable -- spot-check the evidence column). This is required because `app/`, `shared/ commonMain` and `shared/ jvmSharedMain` declare the SAME package `com.music.bitchord.playback.smart` in separate modules.
- C++ quoted includes resolve by path suffix, then unique basename; duplicates add edges to every candidate (flagged by basename ambiguity in manual review). JNI edges match `Java_<FQ>_ ` prefixes of top-level Kotlin classes; top-level `external fun` uses the `<File>Kt` facade. Inner-class JNI (`$`) is not covered.
- File-level SCCs therefore OVER-approximate true compile cycles: same-package name matches are heuristic. Layer-level cycles inherit that approximation. Treat cycles as coupling hotspots to inspect with `skeleton.txt`, not as build errors.

## Appendix A. Full file-edge list

| From | To | Kinds | Evidence (sample) |
|---|---|---|---|
| `app/src/main/cpp/bitperfect/tests/usb_stream_test.cpp` | `app/src/main/cpp/bitperfect/usb_stream.h` | include | usb_stream.h |
| `app/src/main/cpp/bitperfect/usb_transport.cpp` | `app/src/main/cpp/bitperfect/usb_stream.h` | include | usb_stream.h |
| `app/src/main/cpp/dsd/dst/decoder.c` | `app/src/main/cpp/dsd/dst/compat.h` | include | compat.h |
| `app/src/main/cpp/dsd/dst/decoder.c` | `app/src/main/cpp/dsd/dst/decoder.h` | include | decoder.h |
| `app/src/main/cpp/dsd/dst/jni.cpp` | `app/src/main/cpp/dsd/dst/decoder.h` | include | decoder.h |
| `app/src/main/cpp/jni/analysis_jni.cpp` | `native/analyzer/audio_analysis.h` | include | analyzer/audio_analysis.h |
| `app/src/main/cpp/jni/analysis_jni.cpp` | `native/analyzer/resampler.h` | include | analyzer/resampler.h |
| `app/src/main/cpp/jni/analysis_jni.cpp` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/TrackFeatures.kt` | jni | Java_com_music_bitchord_playback_smart_TrackFeatures |
| `app/src/main/cpp/jni/loopmix_jni.cpp` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` | jni | Java_com_music_bitchord_playback_loopmix_LoopMixRenderer |
| `app/src/main/cpp/jni/loopmix_jni.cpp` | `native/loopmix/loop_mix_renderer.h` | include | loopmix/loop_mix_renderer.h |
| `app/src/main/cpp/jni/mel_jni.cpp` | `native/analyzer/mel_spectrogram.h` | include | analyzer/mel_spectrogram.h |
| `app/src/main/cpp/jni/mel_jni.cpp` | `native/analyzer/resampler.h` | include | analyzer/resampler.h |
| `app/src/main/cpp/jni/mel_jni.cpp` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/MelSpectrogram.kt` | jni | Java_com_music_bitchord_playback_smart_MelSpectrogram |
| `app/src/main/cpp/jni/npu_jni.cpp` | `app/src/main/java/com/music/bitchord/playback/smart/NpuNative.kt` | jni | Java_com_music_bitchord_playback_smart_NpuNative |
| `app/src/main/cpp/jni/stem_jni.cpp` | `app/src/main/java/com/music/bitchord/playback/smart/StemNative.kt` | jni | Java_com_music_bitchord_playback_smart_StemNative |
| `app/src/main/cpp/jni/stem_jni.cpp` | `native/analyzer/stem_dsp.h` | include | analyzer/stem_dsp.h |
| `app/src/main/cpp/jni/vocal_jni.cpp` | `native/analyzer/vocal_spectrogram.h` | include | analyzer/vocal_spectrogram.h |
| `app/src/main/cpp/jni/vocal_jni.cpp` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt` | jni | Java_com_music_bitchord_playback_smart_VocalSpectrogram |
| `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `app/src/main/java/com/music/bitchord/playback/AutoMixVisualFeed.kt` | import | com.music.bitchord.playback.AutoMixVisualFeed |
| `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AutomixStatus.kt` | import | com.music.bitchord.playback.smart.AutomixStatus |
| `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixInspectorState.kt` | import | com.music.bitchord.playback.smart.MixInspectorFeed |
| `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | fq-usage | com.music.bitchord.playback.smart.AutomixV2Stems.enabled |
| `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | fq-usage | com.music.bitchord.playback.smart.choreo.AutomixV2Choreo.level, com.music.bitchord.playback.smart.choreo.DjLevel, com.music.bitchord.playback.smart.choreo.DjLevel.SUBTLE, com.music.bitchord.playback.smart.choreo.DjLevel.fromName |
| `app/src/main/java/com/music/bitchord/playback/BoundedContextPersistence.kt` | `app/src/main/java/com/music/bitchord/playback/BoundedQueuePlanner.kt` | same-package | BoundedQueuePlanner |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | fq-usage,import | com.music.bitchord.data.settings.AppSettings, com.music.bitchord.data.settings.AppSettings.smartTransitionWindow.value |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/AutoMixVisualFeed.kt` | same-package | AutoMixVisualFeed |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/AutomixTelemetry.kt` | same-package | AutomixTelemetry |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | fq-usage | com.music.bitchord.playback.djmix.PairPreparer, com.music.bitchord.playback.djmix.PairTimings, com.music.bitchord.playback.djmix.PrepRequest, com.music.bitchord.playback.djmix.PrepTrack, com.music.bitchord.playback.djmix.PreparedPair |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | import | com.music.bitchord.playback.djmix.StraightMixGeometry, com.music.bitchord.playback.djmix.StraightRateMode |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixPort.kt` | import | com.music.bitchord.playback.loopmix.LoopMixPort |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` | import | com.music.bitchord.playback.loopmix.LoopMixRenderer |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | import | com.music.bitchord.playback.loopmix.LoopMixSession, com.music.bitchord.playback.loopmix.LoopRegion |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/smart/MixWaveform.kt` | import | com.music.bitchord.playback.smart.MixWaveform |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/smart/PhraseMaps.kt` | import | com.music.bitchord.playback.smart.PhraseMaps |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | import | com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AutomixStatus.kt` | import | com.music.bitchord.playback.smart.AutomixStatus |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CatchUpPlanner.kt` | fq-usage | com.music.bitchord.playback.smart.CatchUpPins.dropOnNonCatchUp, com.music.bitchord.playback.smart.CatchUpPins.key, com.music.bitchord.playback.smart.CatchUpPins.reuse |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CountdownStabilizer.kt` | import | com.music.bitchord.playback.smart.CountdownStabilizer |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` | fq-usage | com.music.bitchord.playback.smart.CuePoints.exits |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DeckBudget.kt` | import | com.music.bitchord.playback.smart.DeckBudget |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | fq-usage | com.music.bitchord.playback.smart.DjEffect, com.music.bitchord.playback.smart.DjEffect.ECHO, com.music.bitchord.playback.smart.DjEffect.FLANGER, com.music.bitchord.playback.smart.DjEffect.LOOP_ROLL, com.music.bitchord.playback.smart.DjEffect.NONE |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/EchoAdmission.kt` | fq-usage | com.music.bitchord.playback.smart.EchoAdmission.musicalRefusal |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | import | com.music.bitchord.playback.smart.camelotCode, com.music.bitchord.playback.smart.camelotSteps |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LocalBlendAdmission.kt` | import | com.music.bitchord.playback.smart.LocalBlendAdmission |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | fq-usage,import | com.music.bitchord.playback.smart.LoopMix, com.music.bitchord.playback.smart.LoopMix.shouldYieldColdLoop |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixGain.kt` | fq-usage,import | com.music.bitchord.playback.smart.MixGain, com.music.bitchord.playback.smart.MixGain.headroomTrim |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixInspectorState.kt` | import | com.music.bitchord.playback.smart.MixInspectorFeed, com.music.bitchord.playback.smart.MixInspectorKey, com.music.bitchord.playback.smart.MixInspectorRemixAction |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixVerify.kt` | fq-usage | com.music.bitchord.playback.smart.MixVerify.verify |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | import | com.music.bitchord.playback.smart.PhraseConfig |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | fq-usage,import | com.music.bitchord.playback.smart.PhraseChoice, com.music.bitchord.playback.smart.PhrasePlanner |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PrepLead.kt` | fq-usage | com.music.bitchord.playback.smart.PrepLead.BASE_MS, com.music.bitchord.playback.smart.PrepLead.next |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/RetireGuard.kt` | import | com.music.bitchord.playback.smart.RetireGuard |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SeekAction.kt` | import | com.music.bitchord.playback.smart.SeekAction |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMaps.kt` | import | com.music.bitchord.playback.smart.SongMaps |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | fq-usage,import | com.music.bitchord.playback.smart.LyricCue |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | fq-usage,import | com.music.bitchord.playback.smart.BeatSide, com.music.bitchord.playback.smart.CurveWindow, com.music.bitchord.playback.smart.TrackAnalysis, com.music.bitchord.playback.smart.entryKeyConfidence, com.music.bitchord.playback.smart.entryKeyForMix |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | import | com.music.bitchord.playback.smart.TransitionAlternatives |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionEvidence.kt` | import | com.music.bitchord.playback.smart.TransitionEvidenceCache |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | fq-usage,import | com.music.bitchord.playback.smart.PhraseScript, com.music.bitchord.playback.smart.TransitionMoves, com.music.bitchord.playback.smart.TransitionMoves.fxSlot |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | import | com.music.bitchord.playback.smart.CrossfadeMode, com.music.bitchord.playback.smart.FilterTransitionVariant, com.music.bitchord.playback.smart.TransitionPlan, com.music.bitchord.playback.smart.TransitionStyle, com.music.bitchord.playback.smart.TransitionTrackInfo |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | fq-usage,import | com.music.bitchord.playback.smart.audibleTempoRate, com.music.bitchord.playback.smart.deckPlayedRate, com.music.bitchord.playback.smart.deckSpeed, com.music.bitchord.playback.smart.releasableRate |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/VocalDepthFilter.kt` | import | com.music.bitchord.playback.smart.VocalDepthFilter |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | fq-usage | com.music.bitchord.playback.smart.choreo.DjLevel, com.music.bitchord.playback.smart.choreo.DjLevel.CLUB, com.music.bitchord.playback.smart.choreo.DjLevel.FESTIVAL, com.music.bitchord.playback.smart.choreo.DjLevel.SUBTLE |
| `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | fq-usage | com.music.bitchord.playback.smart.choreo.RecipeId.fromCode |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | import | com.music.bitchord.data.settings.AppSettings, com.music.bitchord.data.settings.BitPerfectMode, com.music.bitchord.data.settings.EqualizerMode, com.music.bitchord.data.settings.OutputPcmMode, com.music.bitchord.data.settings.permits |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/AutoMixVisualFeed.kt` | same-package | AutoMixVisualFeed |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/AutomixTelemetry.kt` | same-package | AutomixTelemetry |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/BoundedContextPersistence.kt` | same-package | BoundedContextPersistence |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/BoundedContextRequests.kt` | same-package | BoundedContextRequests |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/BoundedQueueEdits.kt` | same-package | BoundedQueueEditState, BoundedQueueEdits |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/BoundedQueuePlanner.kt` | same-package | BoundedContextPlan, BoundedQueuePlanner |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` | same-package | CrossfadeController, shouldRestorePlayAfterFocusSwap |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | fq-usage | com.music.bitchord.playback.djmix.DefaultTransitionPlanSelector, com.music.bitchord.playback.djmix.PairPreparer |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | fq-usage | com.music.bitchord.playback.smart.TrackAnalyzer |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `app/src/main/java/com/music/bitchord/playback/smart/VersionAudioAligner.kt` | import | com.music.bitchord.playback.smart.VersionAudioAligner |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AutomixAnalysisSource.kt` | import | com.music.bitchord.playback.smart.AutomixAnalysisSource |
| `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | fq-usage | com.music.bitchord.playback.smart.DjEffect |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `app/src/main/java/com/music/bitchord/playback/AutomixTelemetry.kt` | import | com.music.bitchord.playback.AutomixTelemetry |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | same-package | StraightMixGeometry, StraightRateMode |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | import | com.music.bitchord.playback.loopmix.LoopMixSession, com.music.bitchord.playback.loopmix.LoopRegion |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/PhraseMaps.kt` | import | com.music.bitchord.playback.smart.PhraseMaps |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoRendererBridge.kt` | import | com.music.bitchord.playback.smart.choreo.ChoreoRendererBridge |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | import | com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | import | com.music.bitchord.playback.smart.camelotCode |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | import | com.music.bitchord.playback.smart.LoopMix |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixInspectorState.kt` | import | com.music.bitchord.playback.smart.MixInspectorFeed, com.music.bitchord.playback.smart.MixInspectorPhase, com.music.bitchord.playback.smart.MixInspectorRecipeScore, com.music.bitchord.playback.smart.MixInspectorState, com.music.bitchord.playback.smart.MixInspectorTrack |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | import | com.music.bitchord.playback.smart.PhraseConfig |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | import | com.music.bitchord.playback.smart.PhrasePlanner |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | import | com.music.bitchord.playback.smart.SongMap |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMaps.kt` | import | com.music.bitchord.playback.smart.SongMaps |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | import | com.music.bitchord.playback.smart.LyricCue |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | import | com.music.bitchord.playback.smart.StemSet |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | import | com.music.bitchord.playback.smart.TrackAnalysis, com.music.bitchord.playback.smart.entryKeyConfidence, com.music.bitchord.playback.smart.entryKeyForMix, com.music.bitchord.playback.smart.exitKeyConfidence, com.music.bitchord.playback.smart.exitKeyForMix |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | import | com.music.bitchord.playback.smart.PhraseScript, com.music.bitchord.playback.smart.TransitionMoves |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | import | com.music.bitchord.playback.smart.shouldDeclineToMix |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | import | com.music.bitchord.playback.smart.choreo.AutomixV2Choreo, com.music.bitchord.playback.smart.choreo.DjLevel |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | import | com.music.bitchord.playback.smart.choreo.ChoreoCaps, com.music.bitchord.playback.smart.choreo.ChoreoScript |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | import | com.music.bitchord.playback.smart.choreo.Candidate, com.music.bitchord.playback.smart.choreo.Choreographer |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | import | com.music.bitchord.playback.smart.choreo.ChoreoInputs, com.music.bitchord.playback.smart.choreo.TrackClock |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | fq-usage,import | com.music.bitchord.playback.smart.choreo.DeckTarget, com.music.bitchord.playback.smart.choreo.RecipeId, com.music.bitchord.playback.smart.choreo.RecipeStep.LaneCurve, com.music.bitchord.playback.smart.choreo.RecipeStep.SwapLows, com.music.bitchord.playback.smart.choreo.Recipes |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StemAdapter.kt` | import | com.music.bitchord.playback.smart.choreo.StemAdapter, com.music.bitchord.playback.smart.choreo.StemView |
| `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt` | fq-usage | com.music.bitchord.playback.smart.choreo.StructureView.best |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | same-package | PlanInput, PrepTrack, SelectedPlan |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | import | com.music.bitchord.playback.loopmix.LoopMixSession |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | import | com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | import | com.music.bitchord.playback.smart.LoopMix |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | import | com.music.bitchord.playback.smart.TrackAnalysis |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | import | com.music.bitchord.playback.smart.CrossfadeMode, com.music.bitchord.playback.smart.TransitionPlan, com.music.bitchord.playback.smart.TransitionStyle, com.music.bitchord.playback.smart.TransitionTrackInfo, com.music.bitchord.playback.smart.planTransition |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | import | com.music.bitchord.playback.smart.alignTempoOctave |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | fq-usage,import | com.music.bitchord.playback.smart.choreo.AutomixV2Choreo.history.recentRecipes, com.music.bitchord.playback.smart.choreo.DjLevel |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | import | com.music.bitchord.playback.smart.choreo.ChoreoCaps |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | import | com.music.bitchord.playback.smart.choreo.Choreographer |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | import | com.music.bitchord.playback.smart.choreo.ChoreoInputs |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | import | com.music.bitchord.playback.smart.choreo.RecipeId, com.music.bitchord.playback.smart.choreo.Recipes |
| `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt` | import | com.music.bitchord.playback.smart.choreo.StructureView |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixPort.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` | same-package | LoopMixRenderer |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | import | com.music.bitchord.playback.smart.StemKind, com.music.bitchord.playback.smart.StemSet |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | fq-usage | com.music.bitchord.data.settings.AppSettings.automixWindowedStemsEnabled.value |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `app/src/main/java/com/music/bitchord/playback/djmix/PairPreparer.kt` | import | com.music.bitchord.playback.djmix.PreparedPair |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | import | com.music.bitchord.playback.djmix.StraightMixGeometry, com.music.bitchord.playback.djmix.StraightRateMode |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixPort.kt` | same-package | LoopMixPort |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` | same-package | LoopMixRenderer |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoRendererBridge.kt` | import | com.music.bitchord.playback.smart.choreo.ChoreoRendererBridge |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | import | com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | import | com.music.bitchord.playback.smart.LoopMix |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMixDuck.kt` | import | com.music.bitchord.playback.smart.LoopMixDuck |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixGain.kt` | import | com.music.bitchord.playback.smart.MixGain |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MusicalHandoff.kt` | import | com.music.bitchord.playback.smart.HandoffStatus, com.music.bitchord.playback.smart.qualifyMusicalHandoff |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | import | com.music.bitchord.playback.smart.PhraseConfig |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | import | com.music.bitchord.playback.smart.MixTemplate |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/RegionalLoudness.kt` | import | com.music.bitchord.playback.smart.RegionalLoudness |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | import | com.music.bitchord.playback.smart.SongMap |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | import | com.music.bitchord.playback.smart.StemSet |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | import | com.music.bitchord.playback.smart.TrackAnalysis |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | import | com.music.bitchord.playback.smart.TransitionAlternatives |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | fq-usage,import | com.music.bitchord.playback.smart.Lane, com.music.bitchord.playback.smart.PhraseScript |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | fq-usage | com.music.bitchord.playback.smart.weightedSimultaneousVocal |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | import | com.music.bitchord.playback.smart.choreo.AutomixV2Choreo, com.music.bitchord.playback.smart.choreo.DjLevel |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | import | com.music.bitchord.playback.smart.choreo.ChoreoScript |
| `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | import | com.music.bitchord.playback.smart.choreo.RecipeId |
| `app/src/main/java/com/music/bitchord/playback/smart/AutomixModels.kt` | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | import | com.music.bitchord.data.settings.AppSettings |
| `app/src/main/java/com/music/bitchord/playback/smart/AutomixModels.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemModelStore.kt` | same-package | StemModelCoordinator, StemModelFile, StemModelSpec |
| `app/src/main/java/com/music/bitchord/playback/smart/MixWaveform.kt` | `app/src/main/java/com/music/bitchord/playback/smart/AudioDecoder.kt` | same-package | AudioDecoder |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuAndroid.kt` | `app/src/main/java/com/music/bitchord/playback/smart/NpuNative.kt` | same-package | NpuNative |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuAndroid.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuPolicy.kt` | same-package | NpuPolicy, NpuPolicyState, NpuPolicyStore |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuAndroid.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` | same-package | NpuRuntime, runtimeEnabled |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuAndroid.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | same-package | NpuSupport |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuAndroid.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuQnnSession.kt` | same-package | NpuQnnSession |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuNative.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` | same-package | NpuRuntime, runtimeEnabled |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuQdqSelfTest.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuPolicy.kt` | same-package | NpuPolicy |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuQdqSelfTest.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` | same-package | AutomixV2Npu, NpuRuntime, NpuVerdict, runtimeEnabled |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuQdqSelfTest.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | same-package | NpuSupport |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuQdqSelfTest.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` | same-package | BeatTracker |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuQdqSelfTest.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuQnnSession.kt` | same-package | NpuQnnSession, describe |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuSelfTest.kt` | `app/src/main/java/com/music/bitchord/playback/smart/NpuNative.kt` | same-package | NpuNative |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuSelfTest.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | same-package | NpuSupport |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuSelfTest.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` | same-package | BeatTracker |
| `app/src/main/java/com/music/bitchord/playback/smart/NpuSelfTest.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuSession.kt` | same-package | NpuSession |
| `app/src/main/java/com/music/bitchord/playback/smart/PhraseMaps.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | same-package | SongMap |
| `app/src/main/java/com/music/bitchord/playback/smart/StemNative.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `app/src/main/java/com/music/bitchord/playback/smart/StemNative.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NativeAnalysisLibrary.kt` | same-package | NativeAnalysisLibrary |
| `app/src/main/java/com/music/bitchord/playback/smart/StemNative.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/OrtStemEngine.kt` | same-package | StemDsp |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | import | com.music.bitchord.data.settings.AppSettings |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | fq-usage | com.music.bitchord.playback.loopmix.LoopRegion |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/AudioDecoder.kt` | same-package | AudioDecoder |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/AutomixModels.kt` | same-package | AutomixModels |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/LocalAudioSource.kt` | same-package | LocalAudioSource |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/MixWaveform.kt` | same-package | MixWaveform |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/NpuAndroid.kt` | same-package | NpuAndroid |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/NpuNative.kt` | fq-usage,same-package | NpuNative, com.music.bitchord.playback.smart.NpuNative.initBundledQnn |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/NpuQdqSelfTest.kt` | same-package | NpuQdqSelfTest |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/NpuSelfTest.kt` | same-package | NpuSelfTest |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/PhraseMaps.kt` | same-package | PhraseMaps |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `app/src/main/java/com/music/bitchord/playback/smart/StemNative.kt` | same-package | StemNative |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AutomixAnalysisSource.kt` | same-package | AutomixAnalysisSource |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/GridRepair.kt` | same-package | GridRepair |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopRegionCache.kt` | same-package | LoopRegionCache |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` | same-package | NpuRuntime, runtimeEnabled |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | fq-usage,same-package | NpuSupport, com.music.bitchord.playback.smart.NpuSupport.Vendor, com.music.bitchord.playback.smart.NpuSupport.Vendor.QUALCOMM, com.music.bitchord.playback.smart.NpuSupport.bundledBackendPath, com.music.bitchord.playback.smart.NpuSupport.detectVendor |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | same-package | SongMap |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMapEnrichment.kt` | same-package | SongMapEnrichment |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | same-package | AutomixV2Stems, StemProvider |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | same-package | StructureAnalyzer |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | BeatSide, CurveWindow, TrackAnalysis, levelFromRms |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | same-package | heuristicVocalOnModelScale |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/AnalysisStore.kt` | same-package | AnalysisStore |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` | same-package | BeatTracker |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/MelSpectrogram.kt` | same-package | MelSpectrogram |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/OrtStemEngine.kt` | same-package | StemEngines |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemCache.kt` | same-package | StemCache |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemModelStore.kt` | same-package | StemModelStore, StemModels |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemSeparator.kt` | same-package | LegacyTwoStem, StemSeparator |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/TrackFeatures.kt` | same-package | TrackFeatures |
| `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt` | same-package | VocalSpectrogram, VocalTracker |
| `app/src/main/java/com/music/bitchord/playback/smart/VersionAudioAligner.kt` | `app/src/main/java/com/music/bitchord/playback/smart/AudioDecoder.kt` | same-package | AudioDecoder |
| `app/src/main/java/com/music/bitchord/playback/smart/VersionAudioAligner.kt` | `app/src/main/java/com/music/bitchord/playback/smart/LocalAudioSource.kt` | same-package | LocalAudioSource |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoRendererBridge.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt` | import | com.music.bitchord.playback.loopmix.LoopMixRenderer |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoRendererBridge.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | import | com.music.bitchord.playback.smart.StemSet |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoRendererBridge.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | same-package | ChoreoCaps, ChoreoScript, DeckFx, Env, EnvKind |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `app/src/main/java/com/music/bitchord/playback/djmix/StraightMixGeometry.kt` | import | com.music.bitchord.playback.djmix.StraightRateMode |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt` | import | com.music.bitchord.playback.loopmix.LoopMixSession, com.music.bitchord.playback.loopmix.LoopRegion |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoRendererBridge.kt` | same-package | ChoreoRendererBridge |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | import | com.music.bitchord.playback.smart.LoopMix |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | import | com.music.bitchord.playback.smart.SongMap |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | import | com.music.bitchord.playback.smart.StemSet |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | import | com.music.bitchord.playback.smart.TrackAnalysis |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | import | com.music.bitchord.playback.smart.PhraseScript |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | same-package | AutomixV2Choreo, DjLevel |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | same-package | ChoreoCaps, ChoreoScript, Move |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | same-package | Choreographer |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | same-package | ChoreoInputs, PairContext, TrackClock |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | same-package | RecipeId, t |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StemAdapter.kt` | same-package | StemAdapter |
| `app/src/main/java/com/music/bitchord/playback/smart/choreo/ChoreoSessionGlue.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt` | same-package | StructureView |
| `app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt` | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | import | com.music.bitchord.data.settings.AppSettings |
| `app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixInspectorState.kt` | import | com.music.bitchord.playback.smart.MixInspectorState |
| `app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | import | com.music.bitchord.playback.smart.choreo.DjLevel |
| `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt` | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` | import | com.music.bitchord.data.settings.AppFont, com.music.bitchord.data.settings.AppSettings, com.music.bitchord.data.settings.BitPerfectMode, com.music.bitchord.data.settings.DownloadQuality, com.music.bitchord.data.settings.OutputPcmMode |
| `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt` | `app/src/main/java/com/music/bitchord/playback/smart/AutomixModels.kt` | import | com.music.bitchord.playback.smart.AutomixModels |
| `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | fq-usage | com.music.bitchord.playback.smart.choreo.DjLevel.CLUB, com.music.bitchord.playback.smart.choreo.DjLevel.FESTIVAL, com.music.bitchord.playback.smart.choreo.DjLevel.SUBTLE, com.music.bitchord.playback.smart.choreo.DjLevel.entries, com.music.bitchord.playback.smart.choreo.DjLevel.labelRes |
| `native/analyzer/audio_analysis.cpp` | `native/analyzer/audio_analysis.h` | include | audio_analysis.h |
| `native/analyzer/mel_spectrogram.cpp` | `native/analyzer/mel_spectrogram.h` | include | mel_spectrogram.h |
| `native/analyzer/resampler.cpp` | `native/analyzer/resampler.h` | include | resampler.h |
| `native/analyzer/stem_dsp.cpp` | `native/analyzer/stem_dsp.h` | include | stem_dsp.h |
| `native/analyzer/stem_dsp_test.cpp` | `native/analyzer/stem_dsp.h` | include | analyzer/stem_dsp.h |
| `native/analyzer/tempo_analysis.cpp` | `native/analyzer/audio_analysis.h` | include | audio_analysis.h |
| `native/analyzer/vocal_spectrogram.cpp` | `native/analyzer/vocal_spectrogram.h` | include | vocal_spectrogram.h |
| `native/analyzer/vocal_spectrogram_test.cpp` | `native/analyzer/vocal_spectrogram.h` | include | vocal_spectrogram.h |
| `native/analyzer/window_calibration.cpp` | `native/analyzer/vocal_spectrogram.h` | include | vocal_spectrogram.h |
| `native/loopmix/loop_mix_renderer.cpp` | `native/loopmix/loop_mix_renderer.h` | include | loopmix/loop_mix_renderer.h |
| `native/loopmix/loop_mix_renderer.h` | `native/loopmix/dj_fx.h` | include | loopmix/dj_fx.h |
| `native/loopmix/loop_mix_renderer.h` | `native/loopmix/phrase_isolator.h` | include | loopmix/phrase_isolator.h |
| `native/loopmix/loop_mix_renderer_ab_main.cpp` | `native/loopmix/loop_mix_renderer.h` | include | loopmix/loop_mix_renderer.h |
| `native/loopmix/loop_mix_renderer_startup_probe.cpp` | `native/loopmix/loop_mix_renderer.h` | include | loopmix/loop_mix_renderer.h |
| `native/loopmix/phrase_isolator_test.cpp` | `native/loopmix/phrase_isolator.h` | include | phrase_isolator.h |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/BeatFlow.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | EnergySample, TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CatchUpPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CatchUpPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | same-package | TransitionPlan, TransitionStyle, blocked |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CatchUpPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | same-package | alignedFold |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CountdownStabilizer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | same-package | TransitionPlan, TransitionStyle, blocked |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | same-package | LoopMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | same-package | EndingKind, PhraseSection, SectionFn, SectionRole, SongMap |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | same-package | sectionAt |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureMix.kt` | same-package | StructureMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DeckBudget.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | same-package | LoopMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjColour.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | same-package | DjEffect, EffectSettings, effectSettings |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | same-package | camelotCode, camelotCompatible, fittedBpm |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LocalBlendAdmission.kt` | same-package | LocalBlendAdmission |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | same-package | LyricCue, Section, SectionKind, energyRise, labelSections |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | TrackAnalysis, entryKeyConfidence, entryKeyForMix, exitKeyConfidence, exitKeyForMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | same-package | TransitionAlternatives |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | same-package | FilterTransitionVariant, TransitionPlan, TransitionStyle, blocked |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | same-package | orZero, stretchReachable, vocalActivityBetween |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/EchoAdmission.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MusicalHandoff.kt` | same-package | MusicalHandoff |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/EchoAdmission.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | BeatSide, TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | same-package | spread |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/InterpolationAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | same-package | SongMap |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/InterpolationAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | same-package | sectionAt |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LocalBlendAdmission.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | same-package | camelotCode, camelotCompatible, fittedBpm, joinBpm |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LocalBlendAdmission.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | same-package | LyricCue |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LocalBlendAdmission.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | TrackAnalysis, entryKeyConfidence, entryKeyForMix, exitKeyConfidence, exitKeyForMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LocalBlendAdmission.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | same-package | TransitionPlan, TransitionStyle, blocked |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LocalBlendAdmission.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | same-package | incomingRateBetween, stretchReachable |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DeckBudget.kt` | same-package | DeckBudget |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | same-package | pairHash |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/EchoAdmission.kt` | same-package | EchoAdmission |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | same-package | camelotCode |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixGain.kt` | same-package | MixGain |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MusicalHandoff.kt` | same-package | HandoffStatus, MusicalHandoff, nativeTiming, qualifyMusicalHandoff |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | BeatSide, CurveWindow, TrackAnalysis, entryKeyConfidence, entryKeyForMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | same-package | TransitionAlternatives |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | same-package | mixPlan, shouldDeclineToMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | same-package | alignedFold, vocalActivityBetween |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMixDuck.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StemClash.kt` | same-package | StemClash |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixInspectorState.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | import | com.music.bitchord.playback.smart.choreo.DjLevel |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MusicalHandoff.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | same-package | LoopMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MusicalHandoff.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuPolicy.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` | same-package | AutomixV2Npu, NpuVerdict |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuPolicy.kt` | same-package | NpuPolicy |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | same-package | NpuSupport |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` | same-package | NpuRuntime |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseGrid.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` | same-package | CuePoints, EntryCue, EntryKind, ExitCue, ExitKind |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/EchoAdmission.kt` | same-package | EchoAdmission |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | same-package | camelotSteps |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/InterpolationAnalyzer.kt` | same-package | InterpolationAnalyzer |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | same-package | LoopMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MusicalHandoff.kt` | same-package | HandoffPoint, HandoffStatus, MusicalHandoff, qualifyMusicalHandoff |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | same-package | EndingKind, SectionFn, SectionRole, SongMap, StartKind |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | same-package | sectionAt |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureMix.kt` | same-package | StructureMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | same-package | TransitionMoves |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | same-package | blocked |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | same-package | alignedFold |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/RetireGuard.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | same-package | sectionAt |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureTypes.kt` | import | com.music.bitchord.playback.smart.structure.StructureResult |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMapEnrichment.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | same-package | SongMap, StartKind |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMapEnrichment.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMaps.kt` | same-package | SongMaps |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMapEnrichment.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMapEnrichment.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/AutomixV2Structure.kt` | import | com.music.bitchord.playback.smart.structure.AutomixV2Structure |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMapEnrichment.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureV2.kt` | import | com.music.bitchord.playback.smart.structure.StructureV2 |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMaps.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMaps.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | same-package | EndingKind, PhraseSection, SectionFn, SectionRole, SongMap |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMaps.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | same-package | overlaps |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMaps.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | CurveWindow, EnergySample, TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | same-package | SongMap |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | EnergySample, TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | same-package | meanEnergy |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/AutomixV2Structure.kt` | import | com.music.bitchord.playback.smart.structure.AutomixV2Structure |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseGrid.kt` | same-package | PhraseGrid |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | same-package | EndingKind, PhraseSection, SectionFn, SectionRole, SongMap |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | same-package | SectionKind, cosine |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StemClash.kt` | same-package | clamp01 |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/AutomixV2Structure.kt` | import | com.music.bitchord.playback.smart.structure.AutomixV2Structure |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/BarFeatureExtractor.kt` | import | com.music.bitchord.playback.smart.structure.BarFeatureExtractor |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureTypes.kt` | import | com.music.bitchord.playback.smart.structure.StemBars, com.music.bitchord.playback.smart.structure.StructureResult, com.music.bitchord.playback.smart.structure.V2Bar |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureAnalyzer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureV2.kt` | import | com.music.bitchord.playback.smart.structure.StructureV2 |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` | same-package | EntryCue, ExitCue |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | same-package | PhraseSection, SectionFn, SongMap |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/StructureMix.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | same-package | sectionAt |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | same-package | camelotSteps |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/BeatFlow.kt` | same-package | BeatFlow |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/KickLock.kt` | same-package | KickLock |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | same-package | LoopMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixGain.kt` | same-package | MixGain |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | TrackAnalysis, entryKeyConfidence, exitKeyConfidence, regionalCamelotSteps |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionEvidence.kt` | same-package | TransitionEvidenceCache |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | same-package | TransitionPlan, TransitionStyle |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | same-package | alignTempoOctave, detectOctaveRelationship, incomingRateBetween, simultaneousVocalFraction, vocalActivityBetween |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionEvidence.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/BeatFlow.kt` | same-package | BeatFlow |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionEvidence.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionEvidence.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | same-package | TransitionAlternatives |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionEvidence.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | same-package | TransitionPlan |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionEvidence.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | same-package | alignTempoOctave |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CuePoints.kt` | same-package | ExitKind |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | same-package | pairHash |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | same-package | camelotCode, camelotCompatible |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | same-package | LoopMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixGain.kt` | same-package | MixGain |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MusicalHandoff.kt` | same-package | MusicalHandoff |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhrasePlanner.kt` | same-package | MixTemplate, PhraseChoice |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | TrackAnalysis, entryKeyConfidence, entryKeyForMix, exitKeyConfidence, exitKeyForMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | same-package | isConsecutiveAlbumPair, sameAlbum |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionMoves.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | same-package | alignedFold |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/BeatFlow.kt` | same-package | BeatFlow |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/CatchUpPlanner.kt` | same-package | CatchUpPlanner |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt` | same-package | DjEffect, DjMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | same-package | camelotCode, camelotCompatible, camelotSteps, fittedBpm, joinBpm |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LocalBlendAdmission.kt` | same-package | LocalBlendAdmission |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | same-package | LoopMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | same-package | LyricCue, meanEnergy |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | EnergySample, MixOutAnchor, TrackAnalysis, TransitionTier, entryKeyConfidence |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionAlternatives.kt` | same-package | TransitionAlternatives |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionEvidence.kt` | same-package | TransitionEvidenceCache, standardEvidence |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | same-package | alignTempoOctave, assessTransitionTier, audibleStartOf, clamp, incomingRateBetween |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/VocalDepthFilter.kt` | same-package | VocalDepthFilter |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/PhraseConfig.kt` | same-package | PhraseConfig |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | MixCandidate, MixOutAnchor, RankedMixCandidate, TrackAnalysis, TransitionPolicyVerdict |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/VocalDepthFilter.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/VocalDepthFilter.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | same-package | TransitionPlan, TransitionStyle |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | same-package | Move |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | same-package | RecipeId |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | same-package | DjLevel |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | same-package | Candidate |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | same-package | t |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | import | com.music.bitchord.playback.smart.LoopMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | fq-usage | com.music.bitchord.playback.smart.SectionKind.BUILD |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | same-package | AutomixV2Choreo, DjLevel |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | same-package | ChoreoCaps, ChoreoScript, Env, EnvKind, Move |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt` | same-package | Built, MoveCatalog |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | same-package | ChoreoInputs, GenreClass, Harmony, PairContext |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | same-package | Recipe, RecipeId, Recipes, t |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | import | com.music.bitchord.playback.smart.LoopMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | import | com.music.bitchord.playback.smart.SectionKind |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | same-package | AutomixV2Choreo, DjLevel |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | same-package | ChoreoCaps, DeckFx, Env, EnvBuilder, EnvKind |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Choreographer.kt` | same-package | ChoreoGuard, PairScorer |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | same-package | ChoreoInputs, GenreClass, PairContext |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | same-package | Anchor, CurveShape, DeckTarget, FxType, Recipe |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/MoveCatalog.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StemAdapter.kt` | same-package | StemView |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt` | import | com.music.bitchord.playback.smart.camelotCode |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | import | com.music.bitchord.playback.smart.LoopMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | import | com.music.bitchord.playback.smart.SectionKind |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | import | com.music.bitchord.playback.smart.TrackAnalysis, com.music.bitchord.playback.smart.entryKeyConfidence, com.music.bitchord.playback.smart.entryKeyForMix, com.music.bitchord.playback.smart.exitKeyConfidence, com.music.bitchord.playback.smart.exitKeyForMix |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | import | com.music.bitchord.playback.smart.alignTempoOctave |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | same-package | AutomixV2Choreo, DjLevel |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | same-package | Move |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | same-package | RecipeId, t |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StemAdapter.kt` | same-package | StemView |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt` | same-package | StructureView |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | same-package | DjLevel |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/ChoreoModel.kt` | same-package | ChoreoCaps, EnvKind, Move |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/PairContext.kt` | same-package | GenreClass |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StemAdapter.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | import | com.music.bitchord.playback.smart.StemKind, com.music.bitchord.playback.smart.StemSet |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StemAdapter.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | same-package | AutomixV2Choreo |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureAdapterD.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | import | com.music.bitchord.playback.smart.SongMap |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureAdapterD.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt` | same-package | StructureView |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | import | com.music.bitchord.playback.smart.SectionFn, com.music.bitchord.playback.smart.SongMap |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | import | com.music.bitchord.playback.smart.Section, com.music.bitchord.playback.smart.SectionKind, com.music.bitchord.playback.smart.labelSections |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | import | com.music.bitchord.playback.smart.TrackAnalysis |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/Recipes.kt` | same-package | t |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureView.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/StructureAdapterD.kt` | same-package | StructureAdapterD |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/AutomixV2Structure.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureTypes.kt` | same-package | StructureModel |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/BarFeatureExtractor.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureTypes.kt` | same-package | V2Bar |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/BarFeatureExtractor.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureV2.kt` | same-package | StructureTuning |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StemBarsBuilder.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureTypes.kt` | same-package | StemBars |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureCues.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongMap.kt` | import | com.music.bitchord.playback.smart.SongMap |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureCues.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | import | com.music.bitchord.playback.smart.SectionKind |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureCues.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureTypes.kt` | same-package | PhraseTag |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureLyrics.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | import | com.music.bitchord.playback.smart.LyricCue, com.music.bitchord.playback.smart.normalizeLyric |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureTypes.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | import | com.music.bitchord.playback.smart.SectionKind |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureV2.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/SongSections.kt` | import | com.music.bitchord.playback.smart.SectionKind |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureV2.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/AutomixV2Structure.kt` | same-package | AutomixV2Structure |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureV2.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/structure/StructureTypes.kt` | same-package | BarEvent, BarSpan, EdgeInfo, PhraseTag, StemBars |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/AnalysisStore.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/AnalysisStore.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | BeatSide, CurveWindow, EnergySample, MixCandidate, TrackAnalysis |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | same-package | NpuSupport |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/MelSpectrogram.kt` | same-package | MelSpectrogram |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuQnnSession.kt` | same-package | NpuQnnSession |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/MelSpectrogram.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NativeAnalysisLibrary.kt` | same-package | NativeAnalysisLibrary |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NativeAnalysisLibrary.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuQnnSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuQnnSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` | same-package | NpuRuntime, runtimeEnabled |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuQnnSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | same-package | NpuSupport |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuQnnSession.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuSession.kt` | same-package | NpuSession |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuRuntime.kt` | same-package | NpuRuntime |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuSession.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | same-package | NpuSupport |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/OrtStemEngine.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/OrtStemEngine.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | same-package | NpuSupport |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/OrtStemEngine.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | same-package | AutomixV2Stems |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/OrtStemEngine.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuSession.kt` | same-package | NpuSession |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/OrtStemEngine.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemModelStore.kt` | same-package | StemLayout, StemModelSpec, StemModelStore |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/OrtStemEngine.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemSeparator.kt` | same-package | RawStems, StemEngine |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemCache.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemCache.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | same-package | AutomixV2Stems, StemKind, StemMath, StemSet |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemModelStore.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemModelStore.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | same-package | AutomixV2Stems |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemSeparator.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemSeparator.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/Stems.kt` | same-package | AutomixV2Stems, StemKind, StemMath, StemProvider, StemSet |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemSeparator.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/MelSpectrogram.kt` | same-package | MelSpectrogram |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemSeparator.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemCache.kt` | same-package | StemCache |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/TrackFeatures.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/TrackFeatures.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | same-package | EnergySample, MixCandidate |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/TrackFeatures.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt` | same-package | orZero |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/TrackFeatures.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NativeAnalysisLibrary.kt` | same-package | NativeAnalysisLibrary |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt` | same-package | AnalysisLog |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/NpuSupport.kt` | same-package | NpuSupport |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/MelSpectrogram.kt` | same-package | MelSpectrogram |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/NpuSession.kt` | same-package | NpuSession |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MixViewPopup.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixInspectorState.kt` | import | com.music.bitchord.playback.smart.MixInspectorPhase, com.music.bitchord.playback.smart.MixInspectorState, com.music.bitchord.playback.smart.MixInspectorTrack |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MixViewPopup.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/choreo/AutomixV2Choreo.kt` | import | com.music.bitchord.playback.smart.choreo.DjLevel |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MixViewPopup.kt` | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt` | same-package | automixReasonLine |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AutomixStatus.kt` | import | com.music.bitchord.playback.smart.AutomixStatus |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt` | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixInspectorState.kt` | import | com.music.bitchord.playback.smart.MixInspectorPhase |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt` | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MixViewPopup.kt` | same-package | MixViewRequest |
