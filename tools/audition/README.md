# AE3 host analysis and audition

The host analysis module compiles the **actual playback sources** for native
features/resampling/mel, Nirovi, Syvenu, StructureV2, song-map enrichment, the V3
mapper, and SCNet. `djmix-analysis/build.gradle.kts` names the source seam. There
is no host RMS feature substitute, independent peak picker, or host STFT. The
host JNI target compiles the phone's C++ and JNI files unchanged.

This is a standalone JVM application during migration. It must not become an
app dependency while `:shared` still publishes the same playback classes.
Root registration is an integration change for the parent, not performed here.

## Current execution boundary

`AuditionPlanDriver --plan-only` uses the real core `PairPlanner` for new pairs.
It writes a plan and planning metadata, and does not write an empty script.

**Live geometry parity is unavailable for arbitrary new pairs until playback's
geometry materializer is extracted into a shared API.** Executable real-audio
exports therefore require both `--frozen-plan` and `--frozen-script` from the
same playback generation. The script supplies exact frozen render geometry and
region declarations. The driver checks identities, anchors and
`ScriptExecutionGuard.validateFrozen`, binds exact source PCM slices, then calls
`RecipeInterpreter.compileFrozen` and the same guard. It never invents geometry,
sets an incoming hold to zero, aliases a recipe, or changes generation.

No-stem execution preserves the frozen recipe, plan and geometry and compiles
that recipe's declared no-stem steps. `--assume-stems` is planning-only.

## Build and models

After the parent registers `:djmix-analysis`, the validator can run:

```sh
./gradlew :djmix-analysis:test :djmix-analysis:installDist
```

`test` and `run` depend on the module's host JNI CMake target. A distribution
launcher additionally needs its library path:

```sh
export JAVA_OPTS="-Djava.library.path=$PWD/djmix-analysis/build/host-native"
EXPORTER="$PWD/djmix-analysis/build/install/djmix-analysis/bin/djmix-analysis"
```

No model is downloaded. `--model-dir app/src/main/assets` uses the bundled,
size/SHA-256 verified Nirovi and Syvenu identities in `models.json`. Missing or
mismatched models, a missing native library, failed inference, a missing grid,
thermal deferral or a failed structure pass cause nonzero analysis export.
For tracks over 480 seconds, playback's head/tail model window policy is used;
the V3 mapper currently loses those separate vocal windows. Complete vocal
audition metadata for these long tracks is consequently unavailable.

## Decode once and analyze the renderer's exact PCM

```sh
ffmpeg -v error -i A.flac -ar 48000 -ac 2 -f f32le A.f32
ffmpeg -v error -i B.flac -ar 48000 -ac 2 -f f32le B.f32
"$EXPORTER" --input A.f32 --input-format pcm_f32 --sample-rate 48000 --channels 2 \
  --track-id A --model-dir app/src/main/assets --out A.analysis.json
"$EXPORTER" --input B.f32 --input-format pcm_f32 --sample-rate 48000 --channels 2 \
  --track-id B --model-dir app/src/main/assets --out B.analysis.json
"$EXPORTER" --audition-plan-driver \
  --out-analysis A.analysis.json --in-analysis B.analysis.json \
  --out-pcm A.f32 --in-pcm B.f32 --recipe S1 --plan-only --out planned.script.json
```

`contentHash` is SHA-256 of interleaved little-endian raw f32 stereo bytes. The
driver verifies that hash against both full source PCM files, not against a
quantized WAV copy. Model versions include weight hashes. Executable scripts
include hashes of every bound deck/stem file; the Python consumer verifies
them again, including changes that preserve the file length.

The previous `--out-pcm` analysis option remains a mono diagnostic dump. Use
the stereo decode above for renderer input; the mono dump is not a deck file.

## Render a playback-frozen generation

```sh
"$EXPORTER" --audition-plan-driver \
  --out-analysis A.analysis.json --in-analysis B.analysis.json \
  --out-pcm A.f32 --in-pcm B.f32 \
  --frozen-plan playback.plan.json --frozen-script playback.script.json \
  --out A_B.script.json --out-plan A_B.plan.json --out-meta A_B.meta.json
loop_mix_renderer_ab_main --render A_B.script.json --out A_B.wav
python3 tools/mixq/mixq.py check A_B.wav --meta A_B.meta.json
```

The full decoded sources must cover each frozen region exactly. The driver
writes region PCM without altering the supplied geometry. Hash checking proves
which bytes were used; it does not establish bit parity with a different phone
decoder. Device/host render hash parity is still a separate required gate.

## Overlap-only real SCNet

The driver planning metadata queries the **supplied frozen geometry** for the
actual required decks' source-frame extents. It follows held/looped coordinates
instead of treating overlap mix seconds as source seconds. Python consumes these
bounds and the frozen plan's `requiredStems` directly. It does not infer them
from recipe aliases or its catalog when a frozen selection is present.

Each stem-only command receives the required overlap and at most one bar of
padding on either side, bounded to decoded track frames and the 48-second
production cap. Missing required core audio is refused; excessive overlaps and
missing/failed models write an explicit unavailable manifest. Only required
decks run SCNet; one native/ORT pass supplies the needed kinds on that deck.
Only the pinned production SCNet identity is accepted. The currently unpinned
dynamic-int8 placeholder cannot silently replace it.

Separated PCM is sparse-zero aligned to its source track outside the declared
measured window. That exterior is never advertised as measured separation.
Manifests record exact window frames, source hash, model hash, aligned frame
count and every stem's hash. The driver checks window coverage before using
stems, then slices each stem identically to its frozen deck region. Unavailable
stems select the same recipe's no-stem steps; damaged/stale assets are refused.

## Python orchestration and listening pages

Input records for `--engine jvm` contain existing playback exports per request:

```json
{"pairs":[{"pairId":"pair1","outSrc":"A.flac","inSrc":"B.flac",
  "outTrackId":"A","inTrackId":"B","recipes":["S1"],
  "playbackExports":{"S1":{"plan":"playback.plan.json","script":"playback.script.json"}}}]}
```

```sh
python3 tools/audition/audition_render.py --engine jvm --pairs pairs.json \
  --out /tmp/ae3-audition --recipes S1 --jvm-command "$EXPORTER" \
  --model-dir app/src/main/assets --binary /path/to/loop_mix_renderer_ab_main
```

Use `--stems --scnet-model /path/to/scnet.int8.onnx` for separation. Relative
playback JSON paths resolve against the input manifest. Dry-run mode still
requires real playback JSON files and launches no process. Library discovery
alone cannot supply frozen geometry.

Legacy Python consumption of canonical exports checks geometry, identity,
frame counts and hashes without recompiling. Synthetic mode remains explicit:

```sh
python3 tools/audition/generate_synthetic_pairs.py --out /tmp/ae3-fixtures
python3 tools/audition/audition_render.py --pairs /tmp/ae3-fixtures/pairs.json \
  --out /tmp/ae3-fixture-scripts --recipes ALL,AUTO --scripts-only
```

Synthetic geometry and no-stem controls do not prove production analysis,
native FX quality, model parity or listening acceptance. Generic equal-power
fallback remains synthetic-only and must be explicitly enabled.

For real renders, the runner also invokes the configured `--analysis-command`
(or `--jvm-command`) on the completed native WAV using the same `--model-dir`.
It verifies the decoded stereo f32 PCM digest, duration, sample rate, channels,
model identities and an actual inference receipt before writing `beatTimesMix`
in seconds to the WAV's sibling `.meta.json`. The receipt must be written by the
exporter as `<analysis-output>.execution.json`, bind the input PCM and analysis
hashes, and attest whole-file Nirovi inference with its directly measured beat
times and actual backend. The current exporter has no such receipt API, so this
step remains explicitly unavailable until that producer integration lands;
model registry constants or V3 beat grids alone are insufficient. Failed or
unverified analysis retains the rendered WAV, atomically replaces metadata with
an unavailable reason and removes old mix beats. Synthetic controls never
receive production beat evidence. Missing two-source bass-overlap evidence
continues to prevent canonical quality acceptance independently.

Vocal overlap metadata uses mix seconds, sampling source Syvenu at
`sourceSeconds * 50`; placement uses separate source-timeline curves. Gain
weighting uses the compiled deck/vocal-stem lanes. `mixq` retains labelled
diagnostic proxies but canonical renders cannot receive an overall quality
PASS without measured Nirovi-on-mix beats and two-source bass-overlap evidence.
Real-corpus listening, CPU p95, phone NPU and owner ratings remain live gates.
