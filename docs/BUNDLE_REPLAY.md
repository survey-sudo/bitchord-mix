# Flag bundle replay

`djmix-render` is a JVM-only Kotlin module depending on canonical `:djmix-core`
types. It has no Android, shared/app, JNI, coroutines or decoder dependency. Root
plugins supply Kotlin 2.3.20; serialization JSON 1.11.0 and JDK 17 match the
existing host module. No new library or local plugin version is introduced.

The module is **not included in settings yet**. The parent must apply
`evidence/ae3-resume-20261125/wave3-settings-include.patch` only after its current
validation snapshot. No Gradle command was run here. Kotlin source and tests
remain **uncompiled and unrun** until the sole dvb owner performs that gate.

The only handler is `com.music.bitchord.djmix.render.cli.ReplayCli`; the
application main class is `com.music.bitchord.djmix.render.cli.ReplayCliKt`.

## Distinct output protocols

```sh
djmix-render --help
djmix-render replay --bundle flag.zip
djmix-render replay --bundle flag.zip --out trace.json
python3 tools/replay_bundle.py flag.zip --check-only
python3 tools/replay_bundle.py flag.zip --render-cli /path/to/djmix-render --decision-trace trace.json
```

The `--bundle` form writes deterministic JSON to stdout or atomically to a `.json`
file. It checks the complete typed script against `RecipeInterpreter.compileFrozen`
using the exact archived recipe, plan, geometry, regions, stems and drop window.
Core `ScriptExecutionGuard.validate` and `validateFrozen` are authoritative: the
codec has no competing geometry/envelope validator and never repairs values.

Trace protocol: `schema=1`, `operation=decision_trace`, engine, plan/generation,
source manifest, stored/canonical script hashes, matching variants, reported
compiler capabilities, captured decisions/telemetry, compiled script, coverage,
warnings and limitations. The Python wrapper checks operation, identity,
coverage, captured hash and the entire compiled script before publishing a trace.
`--check-only` checks ZIP integrity and metadata only; it does not execute core.

Explicit host audio is a separate positional protocol:

```sh
djmix-render replay flag.zip --out host.wav --renderer-bin /path/to/loop_mix_renderer_ab_main \
  --deck-a-pcm /owner/out.f32 --deck-b-pcm /owner/in.f32 --json
python3 tools/replay_bundle.py flag.zip --render-cli /path/to/djmix-render \
  --renderer /path/to/loop_mix_renderer_ab_main --audio-dir /owner/library --out host.wav
# For a script whose captured stem refs are exactly OUT:VOCALS and IN:BASS:
djmix-render replay flag.zip --out host.wav --renderer-bin /path/to/loop_mix_renderer_ab_main \
  --deck-a-pcm /owner/out.f32 --deck-b-pcm /owner/in.f32 \
  --stem-pcm OUT:VOCALS=/owner/out-vocals.f32 --stem-pcm IN:BASS=/owner/in-bass.f32 --json
python3 tools/replay_bundle.py flag.zip --render-cli /path/to/djmix-render \
  --renderer /path/to/loop_mix_renderer_ab_main --audio-dir /owner/library --out host.wav \
  --stem-pcm OUT:VOCALS=/owner/out-vocals.f32 --stem-pcm IN:BASS=/owner/in-bass.f32
```

All PCM files must be readable regular files containing finite raw interleaved
stereo **f32le**, with the exact retained deck frame count. Each stem option is
case-sensitive `--stem-pcm DECK:KIND=/absolute/region.f32`, repeated once for every
captured stem reference; `DECK` is `OUT`/`IN` and `KIND` is
`VOCALS`/`DRUMS`/`BASS`/`OTHER`. Duplicate, extra or missing deck/kind mappings
refuse. Stem data must already cover the owning deck's captured source region at
the script sample rate; no crop, padding, resampling or stem substitution occurs.
Headerless PCM cannot independently prove its source time or rate: the owner
asserts them, and captured prepared channel hashes bind exact bytes when available.
The Python wrapper uses ffmpeg to crop/resample the owner's source audio to those
regions, then calls the positional JVM protocol. It cannot bypass the local core
guard or compiler, even if the native host parser is permissive. The JVM adapter
invokes only the user-supplied executable with `--render SCRIPT --out WAV`.
The wrapper never delegates an audio request to the JSON-trace form.

The original archive passes the core guard and exact frozen compiler equality
check before execution overrides. Only `outRegion.pcmPath`, `inRegion.pcmPath`
and each captured stem's `pcmPath` change in a separate execution script, pointing
to private validated PCM snapshots. The archived contract/trace remains original.
No recipe, variant, stem requirement, track, timeline, anchor, region or geometry
is changed. `OTHER` remains a native residual: an OTHER-only deck still refuses;
an extra uncaptured base stem cannot be added to make it activate.

Audio is staged in a private directory. The JVM independently reads the actual
bounded PCM16 stereo WAV, verifies rate and canonical native render duration,
and hashes only its data chunk before publication. Python also checks the WAV
against the JSON render receipt (`operation=render`, engine, identity,
`rendered`, rate, sample count and PCM hash). Missing WAV, JSON-as-WAV, wrong
receipt, short PCM or optional expected-hash mismatch fails without replacing an
existing output. Bundle/dry/stem/source output collisions (including symlink and
hardlink aliases) and mixed output modes are refused. PCM is limited to 256 MiB
per file and 512 MiB in aggregate; the execution script and WAV retain the 16 MiB
and 256 MiB protocol bounds. Failed stages are removed; Python `--keep-work`
retains scratch only after success.

Captured device PCM paths are never opened or forwarded. Captured track IDs
search the explicitly supplied library; only user track overrides may name paths.
Legacy manifestless bundles support inspection only. Existing dry-only CLI calls
and the six-argument `BundleReplayEngine.render` API continue to work. The new
overload accepts `stemPcm = listOf(StemPcmInput(DeckTarget.OUT, StemKind.VOCALS,
Path.of("/owner/out-vocals.f32")))`, with `expectedSha256` still optional.

The schema-1 render receipt keeps existing fields and adds `localPcmOverrides`,
`inputPcm` and `unverifiedCapturedAssetHashKeys`. Each input identifies its deck,
optional stem, original track/region/rate/frame count, explicit owner override,
interleaved/left/right f32le SHA-256 and verified captured hash keys. Identity is
`owner_provided_unverified` when no applicable hash exists,
`owner_provided_partially_verified` for one channel, or
`captured_prepared_channels_verified` for both. All available recognized hashes
must match. The Python wrapper checks these fields against the actual supplied
bytes for stem replay; older dry-only receipts remain accepted with local byte
checks and a warning. Returned API fields have backward-compatible defaults.

Exit codes: **0** success/help, **1** invalid bundle/core/compiler/render/audio/hash
failure, **2** usage or file I/O error. Audio success means a verified host WAV was
published. It does **not** establish device, decoder, native or bit-exact parity.
`samplePeak` is a PCM16 sample peak, not a claimed true-peak measurement.

## Actual archive and hash semantics

`DiagnosticsBundler` writes a root-only ZIP with `manifest.json`: schema 1,
engine `3.0.0`, trigger `Flag`. Its status keys are:

```text
plan.json                 recipe.json             render_geometry.json
script.json               analysis_out.json       analysis_in.json
flags_snapshot.json       taste_snapshot.json     events_5min.jsonl
telemetry_5min.jsonl        analysis_summary.json   audioCapture
```

Every status supplies `available`, `entry`, `bytes`, `sha256`, `detail`. Unavailable
payloads are explicit; both JSONL rings (possibly empty) and the summary always
exist. `audioCapture` names `mix_capture.wav`, `.flac`, `.pcm` or `.bin`. The
manifest does not hash itself and is not in its own status map.

Hashes are **unsalted SHA-256 of exact entry bytes**, including whitespace and
newlines. `recipeSha256` equals the recipe status hash. Captured prepared PCM
hashes use `prepared_{out|in}_region_{left|right}_f32le` and
`prepared_{out|in}_{vocals|drums|bass|other}_{left|right}_f32le`. Audio replay
checks these against the deinterleaved raw little-endian channel bytes, preserving
signed zero; it never compares a planar hash to an interleaved file hash.
Unknown/unmapped asset keys are explicitly reported as unverified, and model
hashes remain syntax-only because those bytes are not supplied. Matching channel
hashes establish those prepared bytes only, not device/native bit identity.
The release flags loader also hashes raw `flags.json` bytes using the first token
of its sidecar; the ZIP instead contains the already merged `flags_snapshot.json`.
No salt is captured or invented and no track identity is rewritten.

Plan/script schema is 1; recipe schema is 2; telemetry/analysis schema is 3.
Geometry, flags and taste have no invented schema fields. Replay requires the
frozen recipe/plan/script/geometry; it never substitutes a current registry item.
The reader rejects unknown/unsafe/duplicate entries, missing/extra status records,
bad sizes/hashes, unsupported versions, malformed UTF-8/JSON, duplicate JSON keys,
nesting over 128 and cross-document identity mismatches. The JVM extracts nothing.
Python only creates exclusive root targets in its private temporary directory.
Limits: 16 MiB per text entry, 256 MiB per audio entry, 512 MiB expanded total,
and 513 MiB for the input ZIP file.

## Reducer coverage

The real app event ring excludes ticks and stores only event kind plus resulting
state/identity. Telemetry re-stamps non-epoch positions at write time. Initial
`MixState`, full `MixEvent` payloads and machine configuration are absent.
Consequently the archive reports `reducerReplayComplete=false` and coverage
`complete=false`, `equivalent=null`, `replayedEventCount=0`. Captured countdown
failures remain diagnostic evidence, not fabricated reducer-input mismatches.
No unproduced full-event ZIP dialect is accepted. Callers that possess genuine
complete inputs may use `replayReducer(initial, events, config)` and receive core
`ReplayStep` values.

The archive also lacks the selected no-stem bit and native capability mask. The
compiler reports a full core capability set and every variant whose complete
typed script exactly matches; it never guesses a variant. Stored JSON and
canonical re-encoded hashes may differ because formatting differs. Ring order is
preserved without inventing a cross-ring chronology.

R-next: capture complete reducer inputs/checkpoint/config, native capabilities
and variant decisions at their producer boundary. Historical planner/taste
reconstruction, external bytes and device/native audio parity remain separate
gates. This lane does not modify producer, core or native implementations.
