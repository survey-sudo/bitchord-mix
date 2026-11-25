#!/usr/bin/env python3
"""Host replay for an AE3 "Flag this mix" diagnostics bundle (B7.2).

Usage:
  replay_bundle.py <bundle.zip> --render-cli CLI --audio-dir DIR --renderer BIN --out replay.wav
                    [--work-dir DIR] [--out-track ID_OR_PATH] [--in-track ID_OR_PATH]
                    [--stem-pcm OUT:VOCALS=/absolute/region.f32 ...]
                    [--keep-work] [--check-only]
  replay_bundle.py <bundle.zip> --render-cli BIN --decision-trace trace.json

What it does:
  1. Unpacks `plan.json`, `script.json`, `events_5min.jsonl`,
     `telemetry_5min.jsonl`, `analysis_summary.json` (and `mix_capture.*` if present)
     from the zip written by `DiagnosticsBundler`.
  2. Checks manifest integrity, versions and cross-document identities. Captured
     event anomalies are diagnostic evidence; the tickless summary ring cannot
     establish complete reducer replay. --check-only does not run the core compiler.
  3. Resolves the outgoing / incoming tracks to audio files in `--audio-dir` by
     trackId (`plan.outTrackId` / `plan.inTrackId`, or the script's region trackId),
     unless `--out-track` / `--in-track` give an explicit path or id.
  4. Decodes each retained region with ffmpeg to headerless stereo f32 at the
     script's sample rate and checks frameCount. Delegates the bundle plus host
     PCM to the JVM CLI, which must validate with the core guard and frozen compiler
     before invoking the supplied native renderer via --render SCRIPT --out WAV.

Exit status: 0 on a clean replay, 1 on a validation or render failure, 2 on usage
or input errors. No device/decoder/native bit-exact parity is established. A trace
is never substituted for a requested WAV: actual PCM16 WAV bytes and the CLI
receipt must agree before publication. Repeat --stem-pcm DECK:KIND=/absolute/region.f32
for exactly the captured stem refs; finite raw stereo f32le must already match the
captured rate and retained region. OTHER-only decks still refuse native activation.
Trace delegation uses `replay --bundle <zip> --out <json>` without audio tools.
Legacy bundles without manifests support --check-only inspection only.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
import wave
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

AUDIO_EXTS = (".flac", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".aiff", ".aif", ".alac", ".wma", ".mp4")
SAMPLE_RATE = 48_000
REQUIRED_MEMBERS = ("plan.json", "script.json")
OPTIONAL_MEMBERS = ("events_5min.jsonl", "telemetry_5min.jsonl", "analysis_summary.json")
FLAG_MEMBERS = (
    "manifest.json", "plan.json", "recipe.json", "render_geometry.json", "script.json",
    "analysis_out.json", "analysis_in.json", "flags_snapshot.json", "taste_snapshot.json",
    "events_5min.jsonl", "telemetry_5min.jsonl", "analysis_summary.json",
)
EXPECTED_ENGINE = "3.0.0"
SHA256_RE = re.compile(r"^[a-fA-F0-9]{64}$")
STEM_PCM_RE = re.compile(r"(OUT|IN):(VOCALS|DRUMS|BASS|OTHER)=(/.+)")
STATUS_MEMBERS = set(FLAG_MEMBERS) - {"manifest.json"}
AUDIO_MEMBERS = {"mix_capture.wav", "mix_capture.flac", "mix_capture.pcm", "mix_capture.bin"}
MAX_TEXT_BYTES = 16 * 1024 * 1024
MAX_AUDIO_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 512 * 1024 * 1024
MAX_MEMBERS = len(FLAG_MEMBERS) + len(AUDIO_MEMBERS)

# Legal successor states per MixStateKind (B2). Telemetry records carry the state
# name as a string; unknown names are reported, not rejected.
LEGAL_NEXT: Dict[str, set] = {
    "IDLE": {"PREPARING", "IDLE"},
    "PREPARING": {"READY", "MOVED", "LAST_RESORT", "IDLE", "SUSPENDED", "FAILED", "PREPARING"},
    "READY": {"ARMED", "MOVED", "LAST_RESORT", "IDLE", "SUSPENDED", "FAILED", "READY"},
    "ARMED": {"RUNNING", "LAST_RESORT", "IDLE", "SUSPENDED", "FAILED", "MOVED", "ARMED"},
    "MOVED": {"PREPARING", "READY", "LAST_RESORT", "IDLE", "SUSPENDED", "FAILED", "MOVED"},
    "LAST_RESORT": {"RUNNING", "IDLE", "SUSPENDED", "FAILED", "LAST_RESORT"},
    "RUNNING": {"HANDING_OVER", "DONE", "SUSPENDED", "FAILED", "RUNNING"},
    "HANDING_OVER": {"DONE", "FAILED", "HANDING_OVER"},
    "DONE": {"IDLE", "PREPARING", "DONE"},
    "SUSPENDED": {"PREPARING", "READY", "ARMED", "RUNNING", "IDLE", "FAILED", "SUSPENDED"},
    "FAILED": {"IDLE", "PREPARING", "FAILED"},
}
KNOWN_STATES = set(LEGAL_NEXT)


class ReplayError(Exception):
    """Input, validation or render problem that stops the replay (exit 1)."""


class UsageError(Exception):
    """Missing or inconsistent command-line arguments (exit 2)."""


# ---------------------------------------------------------------------------
# Bundle
# ---------------------------------------------------------------------------

def unpack(bundle: Path, dest: Path) -> Dict[str, Path]:
    if not bundle.is_file():
        raise UsageError(f"bundle not found: {bundle}")
    if bundle.stat().st_size > MAX_TOTAL_BYTES + 1024 * 1024:
        raise ReplayError("ZIP file exceeds input size limit")
    if not zipfile.is_zipfile(bundle):
        raise UsageError(f"not a zip file: {bundle}")
    dest.mkdir(parents=True, exist_ok=True)
    out: Dict[str, Path] = {}
    actual_total = 0
    with zipfile.ZipFile(bundle) as zf:
        if len(zf.infolist()) > MAX_MEMBERS:
            raise ReplayError("too many ZIP members")
        names: Dict[str, str] = {}
        total = 0
        for member in zf.infolist():
            raw = member.filename
            if member.is_dir():
                raise ReplayError(f"unsafe zip member directory: {raw!r}")
            path = Path(raw)
            if path.is_absolute() or len(path.parts) != 1 or ".." in path.parts or "\\" in raw or raw != path.name:
                raise ReplayError(f"unsafe zip member path: {raw!r}")
            name = path.name
            if name in names:
                raise ReplayError(f"duplicate bundle member basename: {name}")
            names[name] = raw
            limit = MAX_AUDIO_BYTES if name in AUDIO_MEMBERS else MAX_TEXT_BYTES
            total += member.file_size
            if member.file_size > limit or total > MAX_TOTAL_BYTES:
                raise ReplayError(f"expanded ZIP size limit exceeded for {name}")
        if "manifest.json" in names:
            unknown = set(names) - set(FLAG_MEMBERS) - AUDIO_MEMBERS
            if unknown:
                raise ReplayError(f"unknown Flag bundle members: {sorted(unknown)}")
        for name in REQUIRED_MEMBERS:
            if name not in names:
                raise ReplayError(f"bundle is missing required member {name}")
        for name, member_name in names.items():
            if name in FLAG_MEMBERS or name in REQUIRED_MEMBERS + OPTIONAL_MEMBERS or name in AUDIO_MEMBERS:
                target = dest / name
                target.parent.mkdir(parents=True, exist_ok=True)
                if os.path.lexists(target):
                    raise ReplayError(f"refusing existing extraction target: {target.name}")
                # Exclusive creation cannot follow/overwrite an existing target symlink.
                with zf.open(member_name) as src, target.open("xb") as dst:
                    limit = MAX_AUDIO_BYTES if name in AUDIO_MEMBERS else MAX_TEXT_BYTES
                    written = 0
                    while chunk := src.read(1 << 16):
                        written += len(chunk)
                        actual_total += len(chunk)
                        if written > limit or actual_total > MAX_TOTAL_BYTES:
                            raise ReplayError(f"expanded ZIP size limit exceeded for {name}")
                        dst.write(chunk)
                out[name] = target
    validate_manifest(out)
    for name in OPTIONAL_MEMBERS:
        if name not in out:
            print(f"warning: bundle has no {name}", file=sys.stderr)
    return out


def validate_manifest(members: Dict[str, Path]) -> None:
    """Validate the integrity metadata emitted by the current Flag bundle writer.

    Legacy bundles without a manifest remain readable. If present, the manifest is
    authoritative: present entries must be listed as available and match byte count
    and SHA-256; unavailable entries must not exist in the archive.
    """
    path = members.get("manifest.json")
    if path is None:
        return
    manifest = load_json(path)
    fields = {"schema", "engine", "trigger", "createdAtMs", "planId", "generation", "outTrackId", "inTrackId",
              "entries", "assetSha256", "modelSha256", "recipeSha256", "warnings"}
    if set(manifest) != fields:
        raise ReplayError("manifest.json fields do not match the current Flag schema")
    if type(manifest.get("schema")) is not int or manifest["schema"] != 1:
        raise ReplayError(f"unsupported manifest schema: {manifest.get('schema')!r}")
    if manifest.get("engine") != EXPECTED_ENGINE:
        raise ReplayError(f"unsupported bundle engine: {manifest.get('engine')!r}; expected {EXPECTED_ENGINE}")
    if manifest.get("trigger") != "Flag" or type(manifest.get("createdAtMs")) is not int or manifest["createdAtMs"] < 0:
        raise ReplayError("manifest.json must have trigger=Flag and non-negative createdAtMs")
    if not isinstance(manifest["warnings"], list) or any(not isinstance(value, str) for value in manifest["warnings"]):
        raise ReplayError("manifest warnings must be a list of strings")
    for table_name in ("assetSha256", "modelSha256"):
        table = manifest[table_name]
        if not isinstance(table, dict) or any(
            not name.strip() or not isinstance(value, str) or not SHA256_RE.fullmatch(value)
            for name, value in table.items()
        ):
            raise ReplayError(f"manifest.json {table_name} must map names to SHA-256 hex digests")
    recipe_sha = manifest.get("recipeSha256")
    if recipe_sha is not None and (not isinstance(recipe_sha, str) or not SHA256_RE.fullmatch(recipe_sha)):
        raise ReplayError("manifest.json recipeSha256 must be a SHA-256 hex digest or null")
    entries = manifest.get("entries")
    if not isinstance(entries, dict) or set(entries) != STATUS_MEMBERS | {"audioCapture"}:
        raise ReplayError("manifest.json entries must contain the actual Flag status keys (including audioCapture)")
    declared_members = {"manifest.json"}
    for key, status in entries.items():
        if not isinstance(status, dict) or type(status.get("available")) is not bool:
            raise ReplayError(f"manifest entry status for {key} must contain boolean available")
        if set(status) != {"available", "entry", "bytes", "sha256", "detail"} or type(status.get("bytes")) is not int:
            raise ReplayError(f"manifest entry status fields/bytes are invalid: {key}")
        if status["available"]:
            name = status.get("entry")
            if (not isinstance(name, str) or (key == "audioCapture" and name not in AUDIO_MEMBERS)
                    or (key != "audioCapture" and name != key)):
                raise ReplayError(f"manifest entry mismatch for {key}: {name!r}")
            if name not in members:
                raise ReplayError(f"manifest available entry is missing: {name}")
            if status.get("detail") is not None:
                raise ReplayError(f"manifest available status has detail: {key}")
            if name in declared_members:
                raise ReplayError(f"manifest declares entry twice: {name}")
            declared_members.add(name)
        elif (status.get("entry") is not None or status.get("bytes") != 0 or status.get("sha256") is not None
              or not isinstance(status.get("detail"), str) or not status["detail"]):
            raise ReplayError(f"manifest invalid unavailable status: {key}")
    if declared_members != set(members):
        raise ReplayError("manifest does not declare exactly the present bundle members")
    for name in OPTIONAL_MEMBERS:
        if not entries[name]["available"]:
            raise ReplayError(f"current Flag writer always supplies {name}")
    for name, file_path in members.items():
        if name == "manifest.json":
            continue
        # Capture uses the logical key audioCapture rather than its filename.
        status = entries.get("audioCapture") if name.startswith("mix_capture.") else entries.get(name)
        if status is None:
            raise ReplayError(f"manifest has no status for present member {name}")
        if not isinstance(status, dict) or status.get("available") is not True:
            raise ReplayError(f"manifest marks present member {name} unavailable")
        declared = status.get("entry")
        if declared is not None and declared != name:
            raise ReplayError(f"manifest entry mismatch for {name}: {declared!r}")
        size = file_path.stat().st_size
        if type(status.get("bytes")) is not int or status.get("bytes") != size:
            raise ReplayError(f"manifest byte count mismatch for {name}: {status.get('bytes')!r} != {size}")
        digest = status.get("sha256")
        if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            raise ReplayError(f"manifest has invalid sha256 for {name}")
        actual = sha256(file_path)
        if actual.lower() != digest.lower():
            raise ReplayError(f"manifest sha256 mismatch for {name}: expected {digest}, got {actual}")
    if recipe_sha != entries["recipe.json"].get("sha256"):
        raise ReplayError("manifest recipeSha256 disagrees with recipe.json status")


def validate_flag_payload(plan: Dict[str, Any], script: Dict[str, Any], geometry: Optional[Dict[str, Any]]) -> List[str]:
    """Check cross-document identities and canonical render geometry when present."""
    errors: List[str] = []
    plan_id = plan.get("planId")
    if script.get("planId") != plan_id:
        errors.append(f"plan/script identity mismatch: {plan_id!r} != {script.get('planId')!r}")
    if plan.get("generation") != script.get("generation"):
        errors.append(f"plan/script generation mismatch: {plan.get('generation')!r} != {script.get('generation')!r}")
    if geometry is not None and script.get("renderGeometry") != geometry:
        errors.append("render_geometry.json does not match script.renderGeometry")
    return errors


def _strict_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _json_loads(text: str):
    def reject_constant(value):
        raise ValueError(f"non-finite JSON constant: {value}")
    def finite_float(value):
        result = float(value)
        if not math.isfinite(result):
            raise ValueError("non-finite JSON number")
        return result
    depth, in_string, escaped = 0, False, False
    for character in text:
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
        elif character == '"':
            in_string = True
        elif character in "[{":
            depth += 1
            if depth > 128:
                raise ValueError("JSON nesting exceeds 128")
        elif character in "]}":
            depth -= 1
    return json.loads(text, object_pairs_hook=_strict_pairs, parse_constant=reject_constant, parse_float=finite_float)


def load_json(path: Path) -> Dict[str, Any]:
    try:
        obj = _json_loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError) as exc:
        raise ReplayError(f"cannot read {path.name}: {exc}") from exc
    if not isinstance(obj, dict):
        raise ReplayError(f"{path.name} must be a JSON object")
    return obj


def read_jsonl(path: Optional[Path]) -> List[Dict[str, Any]]:
    if path is None or not path.is_file():
        return []
    rows: List[Dict[str, Any]] = []
    try:
        content = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise ReplayError(f"cannot read {path.name}: {exc}") from exc
    for index, line in enumerate(content.splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        try:
            o = _json_loads(line)
        except ValueError as exc:
            raise ReplayError(f"invalid JSONL record in {path.name}:{index}: {exc}") from exc
        if isinstance(o, dict):
            rows.append(o)
        else:
            raise ReplayError(f"JSONL record in {path.name} must be an object")
    return rows


# ---------------------------------------------------------------------------
# Event validation
# ---------------------------------------------------------------------------

def event_state(o: Dict[str, Any]) -> Optional[str]:
    """State name from a v3 record (`state`) or a legacy phase string, when it is one."""
    s = o.get("state")
    if isinstance(s, str) and s.upper() in KNOWN_STATES:
        return s.upper()
    return None


def event_kind(o: Dict[str, Any]) -> str:
    et = o.get("eventType") or o.get("event") or o.get("ev")
    if isinstance(et, str):
        if et.startswith("MIX_"):
            return "mix.last_resort" if et == "MIX_LAST_RESORT" else "mix." + et[4:].lower().replace("_", ".")
        return et
    return str(et or "")


def validate_events(events: List[Dict[str, Any]], telemetry: List[Dict[str, Any]], plan_id: str) -> Tuple[List[str], List[str]]:
    """Return (errors, warnings). Errors fail the replay; warnings are informational."""
    errors: List[str] = []
    warnings: List[str] = []
    rows = events + telemetry

    for o in rows:
        kind = event_kind(o)
        if kind == "mix.countdown.broken" or o.get("countdownBroken") is True:
            errors.append(f"countdown broken: {kind} at ts={o.get('timestampMs', o.get('ts', '?'))}")

    # The actual app ring records next-state summaries, excludes ticks, and may be
    # concatenated with retained records. A legal transition can therefore skip
    # ARMED/RUNNING in this ring. Never treat it as a complete reducer input log.
    if events and all(isinstance(row.get("event"), str) and "ts" in row and "generation" in row for row in events):
        warnings.append("Flag event summaries omit ticks, event payloads and initial state; complete reducer sequence cannot be validated")
        return errors, warnings

    per_plan: Dict[str, List[Tuple[int, str]]] = {}
    for idx, o in enumerate(events):
        pid = o.get("planId") or plan_id
        st = event_state(o)
        if st is None:
            continue
        per_plan.setdefault(str(pid), []).append((idx, st))

    for pid, seq in per_plan.items():
        last: Optional[str] = None
        saw_ready = False
        for index, (_, st) in enumerate(seq):
            if last is not None and st not in LEGAL_NEXT.get(last, set()):
                errors.append(f"illegal state sequence for plan {pid}: {last} -> {st}")
            if st in ("READY", "ARMED"):
                saw_ready = True
            if st == "RUNNING" and not saw_ready and pid != "":
                if index == 0:
                    # The 5-minute window can open in the middle of a transition
                    # for a plan that started before it; whatever came first is
                    # not in the bundle, so this is not a violation.
                    warnings.append(f"plan {pid} opens mid-transition (RUNNING) and has no earlier "
                                    f"state in the window; its sequence is checked from there")
                else:
                    errors.append(f"RUNNING without READY/ARMED for plan {pid}")
            last = st

    fired = [o for o in events if event_kind(o) == "mix.fire"]
    if not fired and events:
        warnings.append("no mix.fire event in the 5-minute window")
    if not events:
        warnings.append("events_5min.jsonl is empty; state sequence not validated")
    return errors, warnings


# ---------------------------------------------------------------------------
# Track resolution and decode
# ---------------------------------------------------------------------------

def resolve_track(track: str, audio_dir: Path, allow_explicit_path: bool = False) -> Path:
    """Only an explicit user override may be a path; archived IDs search the given library."""
    candidate = Path(track).expanduser()
    if allow_explicit_path and candidate.is_file():
        return candidate.resolve()
    if not audio_dir.is_dir():
        raise ReplayError(f"audio dir not found: {audio_dir}")
    exact: List[Path] = []
    partial: List[Path] = []
    for p in audio_dir.rglob("*"):
        if not p.is_file() or p.suffix.lower() not in AUDIO_EXTS:
            continue
        if p.stem == track:
            exact.append(p)
        elif track and track in p.name:
            partial.append(p)
    matches = exact or partial
    if not matches:
        raise ReplayError(f"no audio file for trackId {track!r} under {audio_dir}")
    if len(matches) > 1:
        names = ", ".join(str(m) for m in sorted(matches)[:5])
        raise ReplayError(f"trackId {track!r} is ambiguous: {names}")
    return matches[0].resolve()


def require_tool(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise ReplayError(f"{name} not found on PATH")
    return path


def decode_f32(src: Path, dst: Path, ffmpeg: str, cache_dir: Path,
               start_frame: int = 0, frame_count: Optional[int] = None,
               sample_rate_hz: int = SAMPLE_RATE) -> None:
    """Headerless interleaved stereo float32 for the retained deck region.

    Decodes are cached in `cache_dir` keyed by path, size and mtime, so repeated
    replays of the same library tracks skip ffmpeg.
    """
    if start_frame < 0 or (frame_count is not None and frame_count <= 0) or sample_rate_hz <= 0:
        raise ReplayError("invalid retained PCM region/rate")
    stamp = f"{src}|{src.stat().st_size}|{src.stat().st_mtime_ns}|{start_frame}|{frame_count}|{sample_rate_hz}"
    key = hashlib.sha256(stamp.encode("utf-8")).hexdigest()[:16]
    cache = cache_dir / f"{key}.f32"
    expected_bytes = frame_count * 2 * 4 if frame_count is not None else None
    if expected_bytes is not None and not 0 < expected_bytes <= MAX_AUDIO_BYTES:
        raise ReplayError("retained PCM exceeds the 256 MiB audio protocol limit")
    if not (cache.is_file() and cache.stat().st_size > 0 and
            (expected_bytes is None or cache.stat().st_size == expected_bytes)):
        cache_dir.mkdir(parents=True, exist_ok=True)
        tmp = cache.with_suffix(".part")
        trim = f"aresample={sample_rate_hz},atrim=start_sample={start_frame}"
        if frame_count is not None:
            trim += f":end_sample={start_frame + frame_count}"
        try:
            cmd = [
                ffmpeg, "-v", "error", "-nostdin", "-y", "-i", str(src),
                "-vn", "-af", trim, "-ac", "2", "-ar", str(sample_rate_hz), "-f", "f32le", "-acodec", "pcm_f32le",
                "-fs", str(MAX_AUDIO_BYTES), str(tmp),
            ]
            proc = subprocess.run(cmd, capture_output=True, text=True)
            if proc.returncode != 0 or not tmp.is_file() or tmp.stat().st_size == 0:
                raise ReplayError(f"ffmpeg failed for {src}: {proc.stderr.strip() or proc.returncode}")
            if expected_bytes is not None and tmp.stat().st_size != expected_bytes:
                raise ReplayError(f"PCM coverage mismatch: expected {expected_bytes} bytes, decoded {tmp.stat().st_size}")
            tmp.replace(cache)
        finally:
            tmp.unlink(missing_ok=True)
    shutil.copyfile(cache, dst)


def retained_pcm_region(script: Dict[str, Any], deck: str) -> Tuple[int, Optional[int], int]:
    geometry = script.get("renderGeometry")
    if geometry is None:
        return 0, None, SAMPLE_RATE  # Explicit compatibility path for legacy bundles.
    setup = geometry.get(deck) if isinstance(geometry, dict) else None
    rate = script.get("sampleRateHz")
    if not isinstance(setup, dict) or type(rate) is not int or rate <= 0:
        raise ReplayError("canonical script requires retained deck geometry and sampleRateHz")
    origin, count = setup.get("regionStartSec"), setup.get("frameCount")
    if (type(origin) not in (int, float) or not math.isfinite(origin) or origin < 0
            or not math.isfinite(origin * rate) or type(count) is not int or not 0 < count <= 2_147_483_647):
        raise ReplayError("invalid canonical regionStartSec/frameCount")
    return math.floor(origin * rate + 0.5), count, rate


# ---------------------------------------------------------------------------
# Script patch and render
# ---------------------------------------------------------------------------

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_stem_options(values: List[str]) -> Dict[Tuple[str, str], Path]:
    """Only this repeatable owner option supplies host stem paths."""
    result: Dict[Tuple[str, str], Path] = {}
    if len(values) > 8:
        raise UsageError("at most eight distinct deck/stem mappings are supported")
    for value in values:
        match = STEM_PCM_RE.fullmatch(value)
        if match is None or "\x00" in value:
            raise UsageError("--stem-pcm requires DECK:KIND=/absolute/region.f32 (OUT/IN and VOCALS/DRUMS/BASS/OTHER)")
        deck, kind, name = match.groups()
        key = deck, kind
        if key in result:
            raise UsageError(f"duplicate --stem-pcm mapping: {deck}:{kind}")
        try:
            result[key] = Path(name).resolve()
        except (OSError, RuntimeError) as exc:
            raise UsageError(f"invalid owner stem path: {exc}") from exc
    return result


def validate_stem_mappings(script: Dict[str, Any], mappings: Dict[Tuple[str, str], Path]) -> List[Dict[str, Any]]:
    refs = script.get("stems", [])
    if not isinstance(refs, list):
        raise ReplayError("script stems must be a list")
    needed = set()
    for ref in refs:
        if (not isinstance(ref, dict) or ref.get("deck") not in ("OUT", "IN")
                or ref.get("stem") not in ("VOCALS", "DRUMS", "BASS", "OTHER")):
            raise ReplayError("invalid typed captured stem identity")
        key = ref["deck"], ref["stem"]
        if key in needed:
            raise ReplayError("duplicate captured stem identity")
        needed.add(key)
        region = script["outRegion" if ref["deck"] == "OUT" else "inRegion"]
        if (any(ref.get(name) != region.get(name) for name in ("trackId", "startMs", "endMs"))
                or ref.get("sampleRateHz") != script["sampleRateHz"]):
            raise ReplayError("captured stem track/region/rate is misaligned")
    if set(mappings) - needed:
        raise UsageError(f"unknown --stem-pcm mappings: {sorted(set(mappings) - needed)}")
    if needed - set(mappings):
        raise ReplayError(f"missing explicit --stem-pcm mappings: {sorted(needed - set(mappings))}; archived paths are not inputs")
    for deck in ("OUT", "IN"):
        if {kind for side, kind in needed if side == deck} == {"OTHER"}:
            raise ReplayError(f"{deck} OTHER-only stems need a real captured separated base stem for native activation")
    return refs


def owner_pcm_receipt(path: Path, script: Dict[str, Any], deck: str, stem: Optional[str],
                      assets: Dict[str, str]) -> Dict[str, Any]:
    """Hash exact f32le bytes and each raw channel, never a re-encoded float value."""
    _, frames, rate = retained_pcm_region(script, "out" if deck == "OUT" else "inbound")
    if frames is None or not 0 < frames * 8 <= MAX_AUDIO_BYTES:
        raise ReplayError("owner PCM requires a bounded exact canonical frame count")
    expected_bytes = frames * 8
    if not path.is_file() or not os.access(path, os.R_OK) or path.stat().st_size != expected_bytes:
        raise ReplayError(f"owner PCM must be a readable regular f32le file with exactly {frames} aligned frames: {path}")
    hashes = [hashlib.sha256() for _ in range(3)]
    with path.open("rb") as source:
        remaining = expected_bytes
        while remaining:
            count = min(65536, remaining)
            chunk = source.read(count)
            if len(chunk) != count:
                raise ReplayError("owner PCM became shorter than its aligned region")
            for offset in range(0, count, 4):
                if (chunk[offset + 3] & 0x7f) == 0x7f and chunk[offset + 2] & 0x80:
                    raise ReplayError("owner PCM contains a non-finite f32le sample")
            left, right = bytearray(count // 2), bytearray(count // 2)
            for offset in range(0, count, 8):
                left[offset // 2:offset // 2 + 4] = chunk[offset:offset + 4]
                right[offset // 2:offset // 2 + 4] = chunk[offset + 4:offset + 8]
            for digest, data in zip(hashes, (chunk, left, right)):
                digest.update(data)
            remaining -= count
        if source.read(1):
            raise ReplayError("owner PCM became longer than its aligned region")
    interleaved_hash, left_hash, right_hash = (digest.hexdigest() for digest in hashes)
    base = f"prepared_{deck.lower()}_{stem.lower() if stem else 'region'}"
    verified = {}
    for key, actual in ((f"{base}_left_f32le", left_hash), (f"{base}_right_f32le", right_hash)):
        if key in assets:
            if assets[key].lower() != actual:
                raise ReplayError(f"owner PCM differs from captured prepared channel: {key}")
            verified[key] = actual
    region = script["outRegion" if deck == "OUT" else "inRegion"]
    identity = ("captured_prepared_channels_verified" if len(verified) == 2 else
                "owner_provided_partially_verified" if verified else "owner_provided_unverified")
    return {"deck": deck, "stem": stem, "trackId": region.get("trackId"), "startMs": region.get("startMs"),
            "endMs": region.get("endMs"), "sampleRateHz": rate, "frameCount": frames,
            "interleavedF32leSha256": interleaved_hash, "leftF32leSha256": left_hash, "rightF32leSha256": right_hash,
            "encoding": "f32le_interleaved_stereo", "source": "explicit_owner_override",
            "capturedAssetIdentity": identity, "verifiedCapturedAssetSha256": verified}


def validate_input_receipt(receipt: Dict[str, Any], inputs: List[Dict[str, Any]], assets: Dict[str, str]) -> None:
    fields = {"localPcmOverrides", "inputPcm", "unverifiedCapturedAssetHashKeys"}
    if not fields.intersection(receipt) and all(value["stem"] is None for value in inputs):
        # Schema-1 dry-only CLIs predate these additive fields. Local checks still bind supplied bytes.
        print("warning: older dry-only CLI receipt has no input identity metadata; wrapper checked owner PCM", file=sys.stderr)
        return
    verified = {key for value in inputs for key in value["verifiedCapturedAssetSha256"]}
    if (receipt.get("localPcmOverrides") is not True or receipt.get("inputPcm") != inputs
            or receipt.get("unverifiedCapturedAssetHashKeys") != sorted(set(assets) - verified)):
        raise ReplayError("djmix-render owner PCM override/hash/identity receipt mismatch")


def refuse_output_alias(destination: Path, inputs: List[Path]) -> None:
    if any(destination == source or (destination.exists() and source.exists() and os.path.samefile(destination, source))
           for source in inputs):
        raise UsageError("output must not overwrite bundle, source audio or dry/stem PCM (including aliases)")


def validate_current_payload(members, plan, script, geometry, events, telemetry) -> None:
    """Metadata only. The JVM core guard/compiler is the execution authority."""
    for name, payload, schema in (("plan.json", plan, 1), ("script.json", script, 1),
                                  ("recipe.json", load_json(members["recipe.json"]), 2)):
        if type(payload.get("schema")) is not int or payload["schema"] != schema:
            raise ReplayError(f"unsupported {name} schema")
    if not isinstance(geometry, dict):
        raise ReplayError("current Flag replay requires render_geometry.json")
    if type(plan.get("generation")) is not int or plan["generation"] < 0 or not isinstance(plan.get("planId"), str) or not plan["planId"]:
        raise ReplayError("invalid plan identity/generation")
    recipe = load_json(members["recipe.json"])
    if recipe.get("id") != plan.get("recipeId"):
        raise ReplayError("recipe/plan identity mismatch")
    for key, deck, track_key in (("outRegion", "OUT", "outTrackId"), ("inRegion", "IN", "inTrackId")):
        region = script.get(key)
        if not isinstance(region, dict) or region.get("deck") != deck or region.get("trackId") != plan.get(track_key):
            raise ReplayError("script/plan track or deck identity mismatch")
    summary = load_json(members["analysis_summary.json"])
    if (summary.get("engine") != EXPECTED_ENGINE or summary.get("planId") != plan["planId"]
            or summary.get("recipeId") != plan.get("recipeId")):
        raise ReplayError("analysis summary engine/identity mismatch")
    for name, track_key in (("analysis_out.json", "outTrackId"), ("analysis_in.json", "inTrackId")):
        if name in members:
            analysis = load_json(members[name])
            if type(analysis.get("schema")) is not int or analysis["schema"] != 3 or analysis.get("trackId") != plan.get(track_key):
                raise ReplayError("analysis schema/identity mismatch")
    for row in events + telemetry:
        if row.get("planId") == plan["planId"] and any(row.get(key) != plan.get(source)
                for key, source in (("generation", "generation"), ("outId", "outTrackId"), ("inId", "inTrackId"))):
            raise ReplayError("captured record identity mismatch")
    event_names = set("NEXT_KNOWN QUEUE_EDITED ANALYSIS_READY PLAN_FROZEN REGIONS_READY STEMS_READY RENDERER_PREPARED USER_SEEK USER_SKIP USER_PAUSE USER_RESUME AUDIO_FOCUS_LOST ROUTE_CHANGED BACKGROUNDED THERMAL_CHANGED RENDERER_ERROR HANDOVER_COMPLETE LEVEL_CHANGED REMIX_REQUESTED".split())
    for row in events:
        if (not {"ts", "event", "state", "generation"} <= set(row)
                or set(row) - {"ts", "event", "state", "generation", "planId", "outId", "inId"}
                or row.get("event") not in event_names or row.get("state") not in KNOWN_STATES
                or type(row.get("ts")) is not int or row["ts"] < 0
                or type(row.get("generation")) is not int or row["generation"] < 0):
            raise ReplayError("events_5min.jsonl must contain the actual tickless debug-summary dialect")
    for row in telemetry:
        if type(row.get("schema")) is not int or row["schema"] != 3 or row.get("engine") != EXPECTED_ENGINE:
            raise ReplayError("telemetry record must have engine=3.0.0, schema=3")
        if type(row.get("timestampMs")) is not int or row["timestampMs"] < 1_000_000_000_000 or row.get("state") not in KNOWN_STATES:
            raise ReplayError("invalid persisted telemetry epoch/state")


def cli_result(command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode == 2:
        raise UsageError(f"djmix-render input/usage error: {result.stderr.strip()}")
    if result.returncode != 0:
        raise ReplayError(f"djmix-render failed: {result.stderr.strip() or result.returncode}")
    return result


def validate_receipt(value, operation, plan) -> None:
    if (not isinstance(value, dict) or type(value.get("schema")) is not int or value["schema"] != 1
            or value.get("operation") != operation or value.get("engine") != EXPECTED_ENGINE
            or value.get("planId") != plan["planId"] or value.get("generation") != plan["generation"]
            or value.get("reducerReplayComplete") is not False):
        raise ReplayError("djmix-render protocol/operation/identity/coverage mismatch")


def inspect_wav(path: Path, expected_rate: int):
    try:
        if not path.is_file() or not 44 <= path.stat().st_size <= MAX_AUDIO_BYTES:
            raise ReplayError("renderer produced no bounded WAV output")
        with wave.open(str(path), "rb") as reader:
            if (reader.getnchannels(), reader.getsampwidth(), reader.getframerate(), reader.getcomptype()) != (2, 2, expected_rate, "NONE"):
                raise ReplayError("renderer output must be PCM16 stereo WAV at the script rate")
            expected = reader.getnframes() * 4
            digest, count = hashlib.sha256(), 0
            while chunk := reader.readframes(8192):
                digest.update(chunk)
                count += len(chunk)
            if count == 0 or count != expected:
                raise ReplayError("renderer WAV data is empty or truncated")
            return digest.hexdigest(), count // 2
    except (wave.Error, EOFError, OSError) as exc:
        raise ReplayError(f"renderer did not produce a valid WAV: {exc}") from exc


def publish(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=".replay-", suffix=".part", dir=str(target.parent))
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as output, source.open("rb") as input_file:
            shutil.copyfileobj(input_file, output)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def run(args: argparse.Namespace) -> int:
    bundle = Path(args.bundle).expanduser().resolve()
    audio_dir = Path(args.audio_dir).expanduser().resolve() if args.audio_dir else None
    renderer = Path(args.renderer).expanduser().resolve() if args.renderer else None
    out_wav = Path(args.out).expanduser().resolve() if args.out else None

    trace = Path(args.decision_trace).expanduser().resolve() if args.decision_trace else None
    stem_options = getattr(args, "stem_pcm", []) or []
    if args.check_only and any((trace, out_wav, args.render_cli, renderer, audio_dir, args.out_track, args.in_track, stem_options)):
        raise UsageError("--check-only cannot be combined with output, renderer or audio options")
    if trace and any((out_wav, renderer, audio_dir, args.out_track, args.in_track, stem_options)):
        raise UsageError("--decision-trace and WAV/audio options are mutually exclusive")
    if trace and trace.suffix.lower() != ".json":
        raise UsageError("--decision-trace requires a .json output path")
    if not args.check_only and not args.render_cli:
        raise UsageError("--render-cli is required for core-validated trace or WAV replay")
    if args.render_cli and (not Path(args.render_cli).is_file() or not os.access(args.render_cli, os.X_OK)):
        raise UsageError(f"djmix-render executable is not executable: {args.render_cli}")
    if not args.check_only and not trace:
        if audio_dir is None or out_wav is None or renderer is None:
            raise UsageError("--audio-dir, --out, --renderer and --render-cli are required for WAV replay")
        if out_wav.suffix.lower() != ".wav":
            raise UsageError("WAV --out must end in .wav")
        if renderer is not None and (not renderer.is_file() or not os.access(renderer, os.X_OK)):
            raise UsageError(f"renderer is not an executable file: {renderer}")
    destination = trace or out_wav
    stem_paths = parse_stem_options(stem_options)
    if destination is not None:
        refuse_output_alias(destination, [bundle, *stem_paths.values()])

    work_root = Path(args.work_dir).expanduser() if args.work_dir else Path(tempfile.gettempdir())
    work_root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="replay-", dir=str(work_root)))
    completed = False
    try:
        members = unpack(bundle, work / "bundle")
        plan = load_json(members["plan.json"])
        script = load_json(members["script.json"])
        geometry = load_json(members["render_geometry.json"]) if members.get("render_geometry.json") else None
        events = read_jsonl(members.get("events_5min.jsonl"))
        telemetry = read_jsonl(members.get("telemetry_5min.jsonl"))
        plan_id = str(plan.get("planId", ""))

        payload_errors = validate_flag_payload(plan, script, geometry)
        if payload_errors:
            raise ReplayError("invalid Flag bundle payload: " + "; ".join(payload_errors))
        errors, warnings = validate_events(events, telemetry, plan_id)
        if members.get("manifest.json"):
            manifest = load_json(members["manifest.json"])
            if manifest.get("planId") != plan_id:
                raise ReplayError(f"manifest planId mismatch: {manifest.get('planId')!r} != {plan_id!r}")
            for key in ("generation", "outTrackId", "inTrackId"):
                if manifest.get(key) != plan.get(key):
                    raise ReplayError(f"manifest {key} does not match plan")
            for name in ("recipe.json", "render_geometry.json"):
                if name not in members:
                    raise ReplayError(f"required current Flag context is unavailable: {name}")
            validate_current_payload(members, plan, script, geometry, events, telemetry)
            # Captured failures are diagnostic evidence, not malformed bundle data.
            warnings.extend(f"captured event anomaly: {error}" for error in errors)
            errors = []
        elif not args.check_only:
            raise ReplayError("legacy bundles without manifests support integrity inspection only, not core replay")
        for w in warnings:
            print(f"warning: {w}", file=sys.stderr)
        print(f"events: {len(events)} state/mix records, telemetry: {len(telemetry)}, "
              f"plan {plan_id} recipe={plan.get('recipeId', '?')} level={plan.get('djLevel', '?')}")

        if errors:
            for e in errors:
                print(f"FAIL: {e}", file=sys.stderr)
            if not args.allow_invalid_events:
                raise ReplayError(f"{len(errors)} event validation error(s); see above")
            print("warning: continuing despite event errors (--allow-invalid-events)", file=sys.stderr)

        if trace:
            staged = work / "trace.json"
            cli_result([args.render_cli, "replay", "--bundle", str(bundle), "--out", str(staged)])
            if not staged.is_file():
                raise ReplayError("djmix-render produced no decision trace")
            value = load_json(staged)
            validate_receipt(value, "decision_trace", plan)
            coverage = value.get("reducerCoverage")
            if (not isinstance(coverage, dict) or coverage.get("complete") is not False or coverage.get("equivalent") is not None
                    or coverage.get("replayedEventCount") != 0 or value.get("compiledScript") != script
                    or not isinstance(value.get("capturedScriptSha256"), str)
                    or value["capturedScriptSha256"].lower() != sha256(members["script.json"])
                    or not isinstance(value.get("compiledScriptSha256"), str)
                    or not SHA256_RE.fullmatch(value["compiledScriptSha256"]) or not value.get("matchingVariants")):
                raise ReplayError("djmix-render decision trace does not match the captured compiler contract")
            refuse_output_alias(trace, [bundle])
            publish(staged, trace)
            print(f"wrote decision/compiler trace {trace}; reducer replay unavailable")
            completed = True
            return 0

        if args.check_only:
            if errors:
                print(f"check-only: bundle unpacked, but {len(errors)} event validation "
                      f"error(s) were allowed by --allow-invalid-events")
            else:
                print("check-only: bundle integrity/metadata OK; core compilation and reducer replay were not run")
            completed = True
            return 0

        out_id = args.out_track or plan.get("outTrackId") or script.get("outRegion", {}).get("trackId")
        in_id = args.in_track or plan.get("inTrackId") or script.get("inRegion", {}).get("trackId")
        if not out_id or not in_id:
            raise ReplayError("cannot resolve outgoing/incoming track ids from plan or script")
        stem_refs = validate_stem_mappings(script, stem_paths)
        counts = {deck: retained_pcm_region(script, side)[1] for deck, side in (("OUT", "out"), ("IN", "inbound"))}
        if any(count is None or not 0 < count * 8 <= MAX_AUDIO_BYTES for count in counts.values()):
            raise ReplayError("each owner PCM region must fit the exact 256 MiB audio protocol")
        input_bytes = sum(count * 8 for count in counts.values()) + sum(counts[ref["deck"]] * 8 for ref in stem_refs)
        if input_bytes > MAX_TOTAL_BYTES:
            raise ReplayError("owner PCM exceeds the 512 MiB aggregate protocol limit")
        out_src = resolve_track(str(out_id), audio_dir, allow_explicit_path=bool(args.out_track))
        in_src = resolve_track(str(in_id), audio_dir, allow_explicit_path=bool(args.in_track))
        protected_inputs = [bundle, out_src, in_src, *stem_paths.values()]
        refuse_output_alias(out_wav, protected_inputs)
        print(f"out: {out_src}\nin:  {in_src}")

        # Raw stems are already aligned owner material: never decode/crop/repair archived paths.
        assets = manifest["assetSha256"]
        stem_receipts = [owner_pcm_receipt(stem_paths[ref["deck"], ref["stem"]], script, ref["deck"], ref["stem"], assets)
                         for ref in stem_refs]

        ffmpeg = require_tool("ffmpeg")
        pcm_dir = work / "pcm"
        pcm_dir.mkdir(parents=True, exist_ok=True)
        cache_dir = work_root / "replay-pcm-cache"
        out_pcm = pcm_dir / "out.f32"
        in_pcm = pcm_dir / "in.f32"
        decode_f32(out_src, out_pcm, ffmpeg, cache_dir, *retained_pcm_region(script, "out"))
        decode_f32(in_src, in_pcm, ffmpeg, cache_dir, *retained_pcm_region(script, "inbound"))
        input_receipts = [owner_pcm_receipt(out_pcm, script, "OUT", None, assets),
                          owner_pcm_receipt(in_pcm, script, "IN", None, assets), *stem_receipts]

        staged = work / "render.wav"
        command = [args.render_cli, "replay", str(bundle), "--out", str(staged), "--renderer-bin", str(renderer),
                   "--deck-a-pcm", str(out_pcm), "--deck-b-pcm", str(in_pcm), "--json"]
        for (deck, kind), path in stem_paths.items():
            command.extend(("--stem-pcm", f"{deck}:{kind}={path}"))
        result = cli_result(command)
        try:
            receipt = _json_loads(result.stdout)
        except ValueError as exc:
            raise ReplayError(f"djmix-render returned invalid render JSON: {exc}") from exc
        validate_receipt(receipt, "render", plan)
        validate_input_receipt(receipt, input_receipts, assets)
        digest, sample_count = inspect_wav(staged, script["sampleRateHz"])
        if (receipt.get("rendered") is not True or receipt.get("pcmSha256") != digest
                or receipt.get("sampleCount") != sample_count or receipt.get("sampleRateHz") != script["sampleRateHz"]
                or receipt.get("reducerEquivalent") is not None or receipt.get("bitExactParity") != "not_established"):
            raise ReplayError("WAV content/render receipt mismatch")
        refuse_output_alias(out_wav, [*protected_inputs, out_pcm, in_pcm])
        publish(staged, out_wav)
        print("localPcmOverrides=true inputPcm=" + json.dumps(input_receipts, separators=(",", ":")))
        print(f"wrote host WAV {out_wav}  pcmSha256={digest}; device/bit-exact parity not established")
        completed = True
        return 0
    finally:
        if not args.keep_work or not completed:
            shutil.rmtree(work, ignore_errors=True)
        else:
            print(f"work dir kept: {work}")


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bundle", help="diagnostics zip from DiagnosticsBundler")
    ap.add_argument("--audio-dir", help="directory holding the owner's library audio")
    ap.add_argument("--renderer", help="loop_mix_renderer_ab_main binary with --render")
    ap.add_argument("--render-cli", help="djmix-render executable; required for core-validated trace or WAV replay")
    ap.add_argument("--decision-trace", help="output decision/compiler trace JSON; requires --render-cli, no audio tools")
    ap.add_argument("--out", help="output replay WAV")
    ap.add_argument("--out-track", help="outgoing trackId or explicit audio path (overrides plan)")
    ap.add_argument("--in-track", help="incoming trackId or explicit audio path (overrides plan)")
    ap.add_argument("--stem-pcm", action="append", default=[], metavar="DECK:KIND=/absolute/region.f32",
                    help="repeat for exactly the captured OUT/IN VOCALS/DRUMS/BASS/OTHER refs; finite aligned stereo f32le")
    ap.add_argument("--work-dir", help="scratch dir (default: the system temp dir)")
    ap.add_argument("--keep-work", action="store_true", help="keep the scratch dir after success; failures always clean staged files")
    ap.add_argument("--check-only", action="store_true", help="validate the bundle and events, do not render")
    ap.add_argument("--allow-invalid-events", action="store_true",
                    help="render even when event validation fails (the result is not a valid replay)")
    args = ap.parse_args(argv)
    try:
        return run(args)
    except UsageError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except ReplayError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except (OSError, zipfile.BadZipFile, UnicodeError) as exc:
        print(f"error: bundle/tool I/O: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
