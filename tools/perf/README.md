# AE3 B6.4 performance evidence

`collect_perf.py` uses Python 3.8+ and the standard library plus an existing `adb`
executable. It collects evidence from exactly one connected, authorized device;
zero devices, multiple entries (including offline/unauthorized devices), missing
app/build identity, and unrecognized device output fail closed. It never selects
a hard-coded phone. Treat reports as private device/app evidence before sharing.

```sh
python3 tools/perf/collect_perf.py --help
python3 tools/perf/collect_perf.py --identify-only
python3 tools/perf/collect_perf.py --report docs/perf/AE3-<build>.md
python3 tools/perf/collect_perf.py --report docs/perf/AE3-<build>.md \
  --baseline docs/perf/AE3-<previous-build>.md --input /path/to/existing-evidence.json
```

Replace the angle-bracket build placeholders before running. `--report` is
required for collection; existing reports are never overwritten. `--identify-only`
is a separate mode that prints identity without creating files or collecting
performance snapshots. `--package` defaults to `com.music.bitchord.loopfix`.

The only ADB commands are `adb devices`, then serial-pinned `adb shell getprop`
for model, Android release, build ID and fingerprint, `dumpsys package PACKAGE`
for versionCode/versionName, and (collection mode only) `dumpsys battery`,
`dumpsys batterystats PACKAGE`, `dumpsys thermalservice`, `dumpsys gfxinfo PACKAGE`.
Each has a 20-second timeout. No install, workload, reset, screen interaction,
logcat, network request, or soak is performed. Raw stdout/stderr and command
failures accompany parsed battery totals, thermal fields and available app frame
statistics in the single Markdown report. Snapshot text never becomes a numeric
macrobenchmark, a session battery rate, a prep peak, or renderer CPU timing.

## Existing numeric measurements

Any UTF-8 input file is copied verbatim with its SHA-256. Free-form logs/JSONL are
evidence only: the harness does not guess event meaning or fabricate durations.
To populate the six metric rows, supply a JSON summary of actual measurements
with schema `ae3-perf-input-v1`, an `identity` object copied from `--identify-only`,
and a `metrics` object keyed as follows:

| Key | Exact unit |
| --- | --- |
| `analysis_ms_per_track` | `ms` |
| `plan_ms` | `ms` |
| `stems_ms_per_window` | `ms` |
| `renderer_callback_cpu_pct` | `%` |
| `prep_to_ready_s` | `s` |
| `memory_peak_prep_kib` | `KiB` |

Each entry has `value`, `unit`, `statistic`, `context`, and `source`. `value` must
be an actual finite, nonnegative JSON number, never a numeric string or boolean.
`statistic` identifies the measured aggregation (for example `p95`); `context`
must identify the workload/protocol and relevant CPU/NPU or cold/warm variant;
`source` locates the original evidence/event/line or instrumentation output.
All three strings are required. Record distinct variants in separate reports;
the collector never combines CPU/NPU or cold/warm observations. Preserve other
percentiles and variants in the input evidence as needed. The harness copies
reported numbers without statistical aggregation or unit conversion, binds them
to the exact connected device/app identity and input hash, and does not claim to
authenticate user-supplied measurements.

An intentionally **unmeasured** entry looks like this; replace `null` only with an
actual measurement and provide its provenance:

```json
{
  "value": null,
  "unit": "ms",
  "statistic": "p95",
  "context": "Describe the actual workload and analysis backend",
  "source": "Path and location of the original measurement",
  "reason": "No owner-phone measurement collected"
}
```

Missing inputs, malformed/unsupported input schemas, identity mismatches, absent
fields, text values and incompatible units are `UNMEASURED` with the exact reason.
Failed/unsupported snapshot commands retain output and reasons. Neither a sample
value nor a UI frame percentile can fill a missing macrobenchmark measurement.

## Gate and baseline

Baselines must be previous reports from this harness with its versioned JSON
payload; arbitrary Markdown prose/tables are never parsed as passing evidence.
Baseline and current numbers require matching units, statistics, context and
device/OS/package identity, plus source and input SHA-256 provenance. App versions
are captured for both builds and may differ. A zero baseline gives `UNKNOWN`
because its percentage change is undefined.

All six metrics are lower-is-better. Regression is
`(current - baseline) / baseline * 100`. A regression **over 20% is FAIL**;
exactly 20% is allowed. PASS additionally requires `abs(regression) <= 20%`, so
improvements larger than 20% are conservatively UNKNOWN under this requested
absolute PASS rule. Missing/noncomparable data is UNKNOWN, never PASS on text.
Any FAIL fails the gate; otherwise all six must PASS for overall PASS.

Exit codes: **0** PASS or successful identification; **1** FAIL; **2**
usage/identity/input-file/baseline/output error; **3** UNKNOWN. A report with no
numeric evidence or no baseline is expected to exit 3. An identity failure writes
no report. This harness alone does not complete the owner-phone benchmark or
authorize release; six real, comparable measurements are still required.
