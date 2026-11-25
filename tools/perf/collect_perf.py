#!/usr/bin/env python3
"""Collect existing AE3 performance evidence without driving the device (stdlib only)."""

import argparse
import datetime
import hashlib
import json
import math
import re
import shlex
import subprocess
import sys
from fractions import Fraction
from pathlib import Path


PACKAGE = "com.music.bitchord.loopfix"
SCHEMA = "ae3-perf-report-v1"
INPUT_SCHEMA = "ae3-perf-input-v1"
BEGIN = "<!-- AE3_PERF_JSON_V1_BEGIN -->"
END = "<!-- AE3_PERF_JSON_V1_END -->"
METRICS = (
    ("analysis_ms_per_track", "Analysis per track (select CPU or NPU in context)", "ms"),
    ("plan_ms", "Planning", "ms"),
    ("stems_ms_per_window", "Stems per window", "ms"),
    ("renderer_callback_cpu_pct", "Renderer callback CPU / buffer period", "%"),
    ("prep_to_ready_s", "Prep-to-READY (select cold or warm in context)", "s"),
    ("memory_peak_prep_kib", "Peak memory during prep", "KiB"),
)
DEVICE_KEYS = ("serial", "model", "android_release", "build_id", "build_fingerprint", "package")
IDENTITY_KEYS = DEVICE_KEYS + ("version_code", "version_name")
MISSING_REASONS = {
    "analysis_ms_per_track": "No scoped CPU/NPU analysis duration supplied; ADB snapshots do not measure per-track analysis.",
    "plan_ms": "No scoped planning duration supplied; ADB snapshots do not measure plan execution.",
    "stems_ms_per_window": "No scoped stem-window duration supplied; ADB snapshots do not measure stem processing.",
    "renderer_callback_cpu_pct": "No renderer callback CPU/buffer-period measurement supplied; gfxinfo frame timing is not audio callback CPU.",
    "prep_to_ready_s": "No scoped cold/warm prep-to-READY measurement supplied; no preparation is started or timed.",
    "memory_peak_prep_kib": "No measured prep memory peak supplied; a process snapshot cannot establish a prep peak.",
}


class CollectionError(Exception):
    pass


def numeric(value):
    """Reject booleans, numeric strings, NaN, infinities and negative measurements."""
    return (type(value) in (int, float) and value >= 0
            and (type(value) is int or math.isfinite(value)))


def text(value):
    return isinstance(value, str) and bool(value.strip())


def strict_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key: " + key)
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValueError("non-finite JSON constant: " + value)

    return json.loads(raw, object_pairs_hook=unique, parse_constant=invalid_constant)


def read_evidence(path):
    try:
        data = path.read_bytes()
        raw = data.decode("utf-8")
    except (OSError, UnicodeError) as exc:
        raise CollectionError("Cannot read UTF-8 evidence {}: {}".format(path, exc)) from exc
    return {"path": str(path.resolve()), "sha256": hashlib.sha256(data).hexdigest(), "text": raw}


def run_adb(arguments, evidence):
    command = ["adb"] + arguments
    record = {"command": shlex.join(command), "returncode": None, "stdout": "", "stderr": ""}
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=20, check=False)
        record.update(returncode=result.returncode,
                      stdout=result.stdout.decode("utf-8", errors="backslashreplace"),
                      stderr=result.stderr.decode("utf-8", errors="backslashreplace"))
    except subprocess.TimeoutExpired as exc:
        record.update(stdout=(exc.stdout or b"").decode("utf-8", errors="backslashreplace"),
                      stderr=(exc.stderr or b"").decode("utf-8", errors="backslashreplace"),
                      error="ADB read timed out after 20 seconds; output may be partial")
    except OSError as exc:
        record["error"] = "Cannot execute adb: " + str(exc)
    evidence.append(record)
    return record


def checked_output(record):
    if record["returncode"] != 0:
        raise CollectionError(record["command"] + ": " +
                              record.get("error", record["stderr"].strip() or
                                         "exit " + str(record["returncode"])))
    return record["stdout"]


def identify(package, evidence):
    devices = checked_output(run_adb(["devices"], evidence))
    lines = [line.strip() for line in devices.splitlines() if line.strip()]
    if not lines or lines[0] != "List of devices attached":
        raise CollectionError("Unrecognized adb devices output; refusing to select a device")
    entries = [line.split() for line in lines[1:]]
    if len(entries) != 1 or len(entries[0]) != 2 or entries[0][1] != "device":
        raise CollectionError("Expected exactly one attached device in state 'device'; adb devices returned: "
                              + repr(lines[1:]))
    serial = entries[0][0]
    identity = {"serial": serial, "package": package}
    for key, prop in (("model", "ro.product.model"),
                      ("android_release", "ro.build.version.release"),
                      ("build_id", "ro.build.id"),
                      ("build_fingerprint", "ro.build.fingerprint")):
        value = checked_output(run_adb(["-s", serial, "shell", "getprop", prop], evidence)).strip()
        if not value or "\n" in value:
            raise CollectionError("Missing or ambiguous device identity: " + prop)
        identity[key] = value
    package_dump = checked_output(run_adb(["-s", serial, "shell", "dumpsys", "package", package], evidence))
    for key, pattern in (("version_code", r"^\s*versionCode=(\d+)\b"),
                         ("version_name", r"^\s*versionName=(.+?)\s*$")):
        values = set(re.findall(pattern, package_dump, flags=re.MULTILINE))
        if len(values) != 1 or next(iter(values)) in ("", "null"):
            raise CollectionError("Missing or ambiguous app identity from dumpsys package: " + key)
        identity[key] = values.pop()
    return identity


def unmeasured(key, unit, reason=None):
    return {"value": None, "unit": unit, "statistic": None, "context": None,
            "source": None, "evidence_sha256": None, "reason": reason or MISSING_REASONS[key]}


def input_metrics(supplied, identity):
    metrics = {key: unmeasured(key, unit) for key, _, unit in METRICS}
    if supplied is None:
        return metrics
    try:
        document = strict_json(supplied["text"])
    except ValueError as exc:
        why = "Supplied input retained verbatim but not parsed as metric JSON: " + str(exc)
        return {key: unmeasured(key, unit, why) for key, _, unit in METRICS}
    if not isinstance(document, dict) or document.get("schema") != INPUT_SCHEMA:
        why = "Supplied input retained verbatim; numeric import requires schema " + INPUT_SCHEMA
        return {key: unmeasured(key, unit, why) for key, _, unit in METRICS}
    source_identity = document.get("identity")
    mismatches = [key for key in IDENTITY_KEYS if not isinstance(source_identity, dict)
                  or source_identity.get(key) != identity[key]]
    if mismatches:
        why = "Supplied metric identity missing or different from connected device/app: " + ", ".join(mismatches)
        return {key: unmeasured(key, unit, why) for key, _, unit in METRICS}
    values = document.get("metrics")
    if not isinstance(values, dict):
        why = "Supplied input has no metrics object"
        return {key: unmeasured(key, unit, why) for key, _, unit in METRICS}
    for key, _, unit in METRICS:
        item = values.get(key)
        if not isinstance(item, dict):
            metrics[key]["reason"] = "Supplied input has no metric object for " + key
        elif not numeric(item.get("value")):
            metrics[key]["reason"] = "Supplied value is absent or not a finite nonnegative JSON number"
            if text(item.get("reason")):
                metrics[key]["reason"] += ": " + item["reason"]
        elif item.get("unit") != unit:
            metrics[key]["reason"] = "Supplied unit must be exactly " + unit + "; no unit conversion is inferred"
        elif any(not text(item.get(field)) for field in ("statistic", "context", "source")):
            metrics[key]["reason"] = "Numeric input requires nonempty statistic, context and source for provenance/comparison"
        else:
            metrics[key] = {field: item[field] for field in ("value", "unit", "statistic", "context", "source")}
            metrics[key].update(evidence_sha256=supplied["sha256"], reason=None)
    return metrics


def load_baseline(evidence):
    if evidence is None:
        return None
    # Only our explicitly delimited, versioned payload is comparable, never table prose.
    pattern = r"(?m)^" + re.escape(BEGIN) + r"\n```json\n(.*?)\n```\n" + re.escape(END) + r"$"
    matches = re.findall(pattern, evidence["text"].replace("\r\n", "\n"), flags=re.DOTALL)
    if len(matches) != 1:
        raise CollectionError("Baseline must contain one " + SCHEMA + " machine-readable block")
    try:
        result = strict_json(matches[0])
    except ValueError as exc:
        raise CollectionError("Invalid baseline JSON: " + str(exc)) from exc
    if not isinstance(result, dict) or result.get("schema") != SCHEMA:
        raise CollectionError("Unsupported baseline schema")
    if not isinstance(result.get("identity"), dict) or not isinstance(result.get("metrics"), dict):
        raise CollectionError("Baseline has no identity or metrics object")
    return result


def measured(item, unit):
    return (isinstance(item, dict) and numeric(item.get("value")) and item.get("unit") == unit
            and item.get("reason") is None
            and all(text(item.get(field)) for field in ("statistic", "context", "source"))
            and isinstance(item.get("evidence_sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", item["evidence_sha256"]) is not None)


def compare(metrics, identity, baseline):
    results = {}
    for key, _, unit in METRICS:
        current = metrics[key]
        previous = baseline["metrics"].get(key) if baseline else None
        result = {"status": "UNKNOWN", "regression_pct": None,
                  "baseline_value": previous["value"] if measured(previous, unit) else None, "reason": ""}
        if not measured(current, unit):
            result["reason"] = "Current: " + current["reason"]
        elif baseline is None:
            result["reason"] = "No baseline report supplied"
        elif any(not text(baseline["identity"].get(field)) or baseline["identity"][field] != identity[field]
                 for field in DEVICE_KEYS):
            result["reason"] = "Baseline device/OS/package identity differs or is incomplete"
        elif any(not text(baseline["identity"].get(field)) for field in ("version_code", "version_name")):
            result["reason"] = "Baseline app build identity is incomplete"
        elif not measured(previous, unit):
            result["reason"] = "Baseline has no measured numeric value with matching unit and provenance"
        elif any(previous[field] != current[field] for field in ("statistic", "context")):
            result["reason"] = "Baseline statistic or workload/backend/cold-warm context differs"
        elif previous["value"] == 0:
            result["reason"] = "Baseline is zero; percentage regression is undefined"
        else:
            # Exact rational comparison avoids failing an exact 20% decimal change
            # because of binary floating-point rounding at the gate boundary.
            old = Fraction(str(previous["value"]))
            regression = (Fraction(str(current["value"])) - old) / old * 100
            try:
                display_percent = float(regression)
            except OverflowError:
                display_percent = None
            result["regression_pct"] = display_percent
            if regression > 20:
                result.update(status="FAIL", reason="Regression exceeds 20% (lower is better)")
            elif abs(regression) <= 20:
                result.update(status="PASS", reason="Both measured numbers are comparable; absolute change is at most 20%")
            else:
                result["reason"] = "Improvement exceeds 20%; outside the required absolute PASS window"
            if display_percent is None:
                result["reason"] += "; percentage exceeds finite display range (gate used exact arithmetic)"
        results[key] = result
    states = [item["status"] for item in results.values()]
    gate = "FAIL" if "FAIL" in states else ("PASS" if all(state == "PASS" for state in states) else "UNKNOWN")
    return results, gate


def cell(value):
    return str(value).replace("&", "&amp;").replace("<", "&lt;").replace(
        ">", "&gt;").replace("|", "&#124;").replace("\r", "\\r").replace("\n", "<br>")


def fenced(raw):
    # Preserve supplied evidence even when it contains Markdown fences or report markers.
    longest = max((len(run) for run in re.findall(r"`+", raw)), default=0)
    fence = "`" * max(3, longest + 1)
    return fence + "text\n" + raw + ("" if raw.endswith("\n") else "\n") + fence


def snapshot_lines(record, pattern):
    if record["returncode"] != 0:
        return "UNMEASURED: " + record.get("error", "ADB exit " + str(record["returncode"]) +
                                                 "; stderr=" + repr(record["stderr"]))
    unavailable = [line.strip() for line in record["stdout"].splitlines()
                   if re.search(r"No process found|Can't find service|Permission Denial|Permission denied", line, re.I)]
    if unavailable:
        return "UNMEASURED: " + "; ".join(unavailable)
    lines = [line.strip() for line in record["stdout"].splitlines() if re.search(pattern, line, re.I)]
    if not lines:
        return "UNMEASURED: command returned no recognized fields; exact stdout/stderr retained below"
    return "\n".join(lines)


def render_report(identity, metrics, comparisons, gate, commands, snapshots, supplied, baseline_evidence):
    report = {"schema": SCHEMA, "captured_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "identity": identity, "metrics": metrics, "comparisons": comparisons, "gate": gate,
              "input": ({key: supplied[key] for key in ("path", "sha256")} if supplied else None),
              "baseline": ({key: baseline_evidence[key] for key in ("path", "sha256")} if baseline_evidence else None)}
    lines = ["# AE3 performance evidence — " + cell(identity["version_name"]), "",
             "Gate: **" + gate + "**", "", "Captured at (host UTC): " + report["captured_at_utc"], "",
             "This is an evidence collector, not a benchmark workload. It does not launch, reset, install or drive the app. "
             "ADB snapshots are cumulative or instantaneous, not scoped benchmark measurements. "
             "Supplied numeric measurements are attributed to the input file, not independently verified by this harness.", "",
             "## Fixed device and installed build identity", "", "| Field | ADB value |", "| --- | --- |"]
    lines.extend("| {} | {} |".format(key, cell(identity[key])) for key in IDENTITY_KEYS)
    lines += ["", "## Six macrobenchmark metrics", "",
              "All six are lower-is-better. Regression = (current − baseline) / baseline × 100. "
              "A regression >20% fails. PASS requires two finite measured numbers, the same unit/statistic/context/device/OS, "
              "a nonzero baseline and absolute change ≤20%. Larger improvements are UNKNOWN under this strict PASS rule. "
              "Any FAIL fails the overall gate; otherwise any UNKNOWN keeps it UNKNOWN. Text never passes.", "",
              "| Metric | Current | Unit | Baseline | Regression % | Result | Evidence / exact reason |",
              "| --- | --- | --- | --- | --- | --- | --- |"]
    for key, label, unit in METRICS:
        item, comparison = metrics[key], comparisons[key]
        provenance = ("source={}; statistic={}; context={}; input SHA-256={}".format(
            item["source"], item["statistic"], item["context"], item["evidence_sha256"])
            if item["value"] is not None else "UNMEASURED: " + item["reason"])
        values = (label + " (`" + key + "`)", item["value"] if item["value"] is not None else "UNMEASURED",
                  unit, comparison["baseline_value"] if comparison["baseline_value"] is not None else "UNMEASURED",
                  comparison["regression_pct"] if comparison["regression_pct"] is not None else "UNMEASURED",
                  comparison["status"], provenance + "; " + comparison["reason"])
        lines.append("| " + " | ".join(cell(value) for value in values) + " |")
    lines += ["", "## Supporting ADB snapshots", "",
              "These fields are copied from returned output without conversion or session-rate/peak inference. "
              "Battery totals are not a playback drain rate; thermal readings and UI frames are not renderer callback CPU."]
    patterns = (
        ("battery", "Battery fields (raw units)", r"^\s*(?:AC powered|USB powered|Wireless powered|Dock powered|status|health|present|level|scale|voltage|temperature|Charge counter|Max charging current|Max charging voltage):"),
        ("batterystats", "Batterystats cumulative totals / accounting", r"^\s*(?:Statistics since|Time on battery|Total run time|Start clock time|Discharge|Estimated power use|Capacity:|Battery time remaining)|\b(?:computed drain|actual drain)\b"),
        ("thermal", "Thermal snapshot", r"Thermal Status|Temperature\{|Current temperatures|Thermal HAL|IsStatusOverride"),
        ("frames", "App process frame statistics (when exposed)", r"Total frames rendered|Janky frames|\d+(?:th|st|nd|rd) percentile|Number (?:Missed|High|Slow|Frame)|Total GPU|No process found"),
    )
    for key, title, pattern in patterns:
        lines += ["", "### " + title, "", fenced(snapshot_lines(snapshots[key], pattern))]
    lines += ["", "## Input and baseline provenance", ""]
    for label, evidence in (("Supplied telemetry/log/metric input", supplied), ("Baseline report", baseline_evidence)):
        lines.append(("{}: {}; SHA-256 `{}`".format(label, cell(evidence["path"]), evidence["sha256"]))
                     if evidence else label + ": not supplied")
    if supplied:
        lines += ["", "### Supplied input (verbatim UTF-8)", "", fenced(supplied["text"])]
    lines += ["", "## Exact ADB command evidence", "",
              "Each command is read-only. Nonzero exits, stderr and timeouts are retained; no missing value becomes zero."]
    for index, record in enumerate(commands, 1):
        lines += ["", "### Command " + str(index), "", fenced(record["command"]), "",
                  "Return code: " + str(record["returncode"])]
        if record.get("error"):
            lines += ["", cell(record["error"])]
        lines += ["", "stdout:", "", fenced(record["stdout"]), "", "stderr:", "", fenced(record["stderr"])]
    # Last block is authoritative; JSON escapes angle brackets so evidence cannot spoof delimiters.
    payload = json.dumps(report, ensure_ascii=True, allow_nan=False, indent=2).replace("<", "\\u003c").replace(
        ">", "\\u003e").replace("`", "\\u0060")
    lines += ["", "## Machine-readable baseline payload", "", BEGIN, "```json", payload, "```", END, ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        "No device workload is run. Exit: 0 PASS/identification, 1 FAIL, 2 collection/usage error, 3 UNKNOWN. "
        "Normal collection requires --report; --identify-only prints identity and runs only adb devices, getprop and dumpsys package."))
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--report", type=Path, help="New Markdown report path, e.g. docs/perf/AE3-<build>.md (never overwritten)")
    mode.add_argument("--identify-only", action="store_true", help="Print fixed identity as JSON; no snapshots, report or gate")
    parser.add_argument("--baseline", type=Path, help="Prior report generated by this harness")
    parser.add_argument("--input", type=Path, help="Existing UTF-8 telemetry/log evidence or ae3-perf-input-v1 numeric JSON")
    parser.add_argument("--package", default=PACKAGE, help="Installed package (default: %(default)s)")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.package):
        parser.error("--package must be a package identifier, not shell syntax")
    if args.identify_only and (args.input or args.baseline):
        parser.error("--identify-only does not read --input or --baseline")
    try:
        if args.report and args.report.exists():
            raise CollectionError("Report already exists; choose a new --report path: " + str(args.report))
        supplied = read_evidence(args.input) if args.input else None
        baseline_evidence = read_evidence(args.baseline) if args.baseline else None
        baseline = load_baseline(baseline_evidence)
        commands = []
        identity = identify(args.package, commands)
        if args.identify_only:
            print(json.dumps(identity, indent=2, ensure_ascii=True))
            return 0
        snapshots = {}
        for key, service in (("battery", ["battery"]), ("batterystats", ["batterystats", args.package]),
                             ("thermal", ["thermalservice"]), ("frames", ["gfxinfo", args.package])):
            snapshots[key] = run_adb(["-s", identity["serial"], "shell", "dumpsys"] + service, commands)
        metrics = input_metrics(supplied, identity)
        comparisons, gate = compare(metrics, identity, baseline)
        output = render_report(identity, metrics, comparisons, gate, commands, snapshots, supplied, baseline_evidence)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(output)
        print("{}: {}".format(gate, args.report))
        return {"PASS": 0, "FAIL": 1, "UNKNOWN": 3}[gate]
    except (CollectionError, OSError, ValueError) as exc:
        print("collect_perf: " + str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
