# Loop recovery validation record

Base source: `170328bed4b78eaa68e7fce739ba8b121e4aa38c`.
Branch: `automix/loop-character-20261119`.

## Tested source identity

The uncommitted v10 snapshot was tested on cue in
`/workspace/diagnostics/automix-loop-character-20261119/candidate-final-v10-86ade61b`.
Its source archive SHA-256 is
`86ade61bf9c6a24d14eab6cc3712c5761fe8e1e4179e82c169ac36b3917e860a`.

All production and test files in this commit match that tested snapshot.
Documentation subsequently records validation and the user's commit authorization.

## Results

| Check | Result |
|---|---|
| Baseline independent recovery test | Intended regression reproduced: two tests, one failure; clean control passed |
| Final shared JVM suite | 536 tests, zero failures/errors/skips |
| Final ordinary app unit suite | 1,437 tests, zero failures/errors, four skips |
| Dedicated real-JNI session suite | Three tests passed, zero skipped |
| Native renderer self-test | Passed six renders, zero exit-gate violations |
| Reverb-stop probe | Passed, including the expected zero-wet sensitivity failure |
| Loop-domain probe | Passed 63 finite-loop combinations: 54 exact-ramp, nine unity |
| Whitespace validation | `git diff --check` passed |

Ordinary suites ran with `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and
`_JAVA_OPTIONS` unset:

```sh
./gradlew :shared:jvmTest :app:testProdDebugUnitTest --offline \
  -Pbitchord.bundleWear=false --max-workers=2 --no-daemon --stacktrace
```

The real-JNI class ran separately in a fresh JVM with
`bitchord.loopmix.hostNativeRequired=true` and the verified host library on
`java.library.path`. Native availability is intentionally absent in the ordinary
app suite. Ordinary skips are not counted as successful native execution.

The nine synthetic planner witnesses used identical test bytes on baseline and
candidate. Crown candidates changed from four to five, native bridges from five
to four. The whole-track metadata conflict changed from an Echo bridge to a
two-sided crown; local sung, uncovered and quiet controls remained non-crowns,
and existing wide/folded references remained crowns. These are planner counts;
controller acceptance, preparation and takeover were not measured by this replay.

## Retained evidence

Full generated XML, logs, source archives and binaries remain outside this
commit in the cue evidence directory and local
`docs/validation/loop-character/candidate-final-v10/`. The local result manifest
SHA-256 is
`4f98067e2f8498a9a7704f7af08cc63babcff0b364ba60f2d34edd41f02b5d93`;
all 223 referenced artifacts were checked against its hashes.

Earlier frozen baseline details and fixture/history provenance are committed
alongside this record. The initial provenance JSON's runtime status describes
the freeze point; the executed baseline result is in `baseline-result.json`.

The independent full-diff audit is still running at commit time. A completed
partial review identified the native `loop-complete` terminal classification
defect; v10 fixes it and includes regression coverage. The focused review of
that fix reported no issue. Do not describe this as a completed full audit.

## Limits

This evidence does not establish Android controller/sink takeover, delegate
buffer acceptance, catalog-wide route prevalence, or musical listening quality.
The exact Adrenaline to Bloody Paradise recordings and original preferred build
remain unmapped. No APK, phone operation, model swap or NPU activation occurred.
