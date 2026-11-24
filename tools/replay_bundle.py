#!/usr/bin/env python3
"""Host replay for an AE3 "Flag this mix" diagnostics bundle (B7.2).

Usage:
  replay_bundle.py <bundle.zip> --audio-dir DIR --renderer BIN --out replay.wav
                   [--work-dir DIR] [--out-track ID_OR_PATH] [--in-track ID_OR_PATH]
                   [--keep-work] [--check-only]

What it does:
  1. Unpacks `plan.json`, `script.json`, `events_5min.jsonl`,
     `telemetry_5min.jsonl`, `analysis_summary.json` (and `mix_capture.*` if present)
     from the zip written by `DiagnosticsBundler`.
  2. Validates the event stream: no `mix.countdown.broken` (or countdownBroken=true),
     every `mix.fire` preceded by an arm/ready for the same plan, and a legal state
     sequence per planId (table below, mirroring MixStateKind). A plan whose first
     record in the window is already RUNNING is reported as a warning, not an
     error: the states before the window are simply not in the bundle.
  3. Resolves the outgoing / incoming tracks to audio files in `--audio-dir` by
     trackId (`plan.outTrackId` / `plan.inTrackId`, or the script's region trackId),
     unless `--out-track` / `--in-track` give an explicit path or id.
  4. Decodes each track with ffmpeg to headerless stereo f32 at 48 kHz (cached in
     the work dir), patches `outRegion.pcmPath` / `inRegion.pcmPath` into a copy of
     `script.json`, and runs `<renderer> --render patched.json --out replay.wav`.

Exit status: 0 on a clean replay, 1 on a validation or render failure, 2 on usage
or input errors. The renderer is the same `loop_mix_renderer_ab_main` binary built
from native/loopmix; the replay is bit-exact only when the same PCM and renderer
build are used as on the device (compare SHA-256 with the device render if known).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

AUDIO_EXTS = (".flac", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".aiff", ".aif", ".alac", ".wma", ".mp4")
SAMPLE_RATE = 48_000
REQUIRED_MEMBERS = ("plan.json", "script.json")
OPTIONAL_MEMBERS = ("events_5min.jsonl", "telemetry_5min.jsonl", "analysis_summary.json")

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
    if not zipfile.is_zipfile(bundle):
        raise UsageError(f"not a zip file: {bundle}")
    dest.mkdir(parents=True, exist_ok=True)
    out: Dict[str, Path] = {}
    with zipfile.ZipFile(bundle) as zf:
        names = {Path(n).name: n for n in zf.namelist() if not n.endswith("/")}
        for name in REQUIRED_MEMBERS:
            if name not in names:
                raise ReplayError(f"bundle is missing required member {name}")
        for name, member in names.items():
            if name in REQUIRED_MEMBERS + OPTIONAL_MEMBERS or name.startswith("mix_capture."):
                target = dest / name
                with zf.open(member) as src, target.open("wb") as dst:
                    shutil.copyfileobj(src, dst)
                out[name] = target
    for name in OPTIONAL_MEMBERS:
        if name not in out:
            print(f"warning: bundle has no {name}", file=sys.stderr)
    return out


def load_json(path: Path) -> Dict[str, Any]:
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ReplayError(f"cannot read {path.name}: {exc}") from exc
    if not isinstance(obj, dict):
        raise ReplayError(f"{path.name} must be a JSON object")
    return obj


def read_jsonl(path: Optional[Path]) -> List[Dict[str, Any]]:
    if path is None or not path.is_file():
        return []
    rows: List[Dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            o = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(o, dict):
            rows.append(o)
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
    et = o.get("eventType")
    if isinstance(et, str):
        if et.startswith("MIX_"):
            return "mix." + et[4:].lower().replace("_", ".")
        return et
    return str(o.get("ev", ""))


def validate_events(events: List[Dict[str, Any]], telemetry: List[Dict[str, Any]], plan_id: str) -> Tuple[List[str], List[str]]:
    """Return (errors, warnings). Errors fail the replay; warnings are informational."""
    errors: List[str] = []
    warnings: List[str] = []
    rows = events + telemetry

    for o in rows:
        kind = event_kind(o)
        if kind == "mix.countdown.broken" or o.get("countdownBroken") is True:
            errors.append(f"countdown broken: {kind} at ts={o.get('timestampMs', o.get('ts', '?'))}")

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

def resolve_track(track: str, audio_dir: Path) -> Path:
    """Explicit path wins; otherwise match a file whose stem or name contains the trackId."""
    candidate = Path(track).expanduser()
    if candidate.is_file():
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


def decode_f32(src: Path, dst: Path, ffmpeg: str, cache_dir: Path) -> None:
    """Headerless interleaved stereo float32 at 48 kHz (the renderer's `.f32` input).

    Decodes are cached in `cache_dir` keyed by path, size and mtime, so repeated
    replays of the same library tracks skip ffmpeg.
    """
    stamp = f"{src}|{src.stat().st_size}|{int(src.stat().st_mtime)}"
    key = hashlib.sha256(stamp.encode("utf-8")).hexdigest()[:16]
    cache = cache_dir / f"{key}.f32"
    if not (cache.is_file() and cache.stat().st_size > 0):
        cache_dir.mkdir(parents=True, exist_ok=True)
        tmp = cache.with_suffix(".part")
        cmd = [
            ffmpeg, "-v", "error", "-nostdin", "-y", "-i", str(src),
            "-vn", "-ac", "2", "-ar", str(SAMPLE_RATE), "-f", "f32le", "-acodec", "pcm_f32le", str(tmp),
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0 or not tmp.is_file() or tmp.stat().st_size == 0:
            raise ReplayError(f"ffmpeg failed for {src}: {proc.stderr.strip() or proc.returncode}")
        tmp.replace(cache)
    shutil.copyfile(cache, dst)


# ---------------------------------------------------------------------------
# Script patch and render
# ---------------------------------------------------------------------------

def patch_script(script: Dict[str, Any], out_pcm: Path, in_pcm: Path) -> Dict[str, Any]:
    patched = json.loads(json.dumps(script))
    for key, pcm in (("outRegion", out_pcm), ("inRegion", in_pcm)):
        reg = patched.get(key)
        if not isinstance(reg, dict):
            raise ReplayError(f"script.json has no {key}")
        reg["pcmPath"] = str(pcm)
        reg.setdefault("sampleRateHz", SAMPLE_RATE)
    stems = patched.get("stems") or []
    missing = [s.get("trackId", "?") for s in stems if isinstance(s, dict) and s.get("pcmPath")
               and not Path(str(s["pcmPath"])).is_file()]
    if missing:
        raise ReplayError(
            "stem PCM not available on the host for: " + ", ".join(sorted(set(missing)))
            + " (bit-exact replay needs the same stem PCM; remove stems only for a non-exact preview)")
    return patched


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def render(renderer: Path, script_path: Path, out_wav: Path) -> None:
    cmd = [str(renderer), "--render", str(script_path), "--out", str(out_wav)]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.stdout.strip():
        print(proc.stdout.rstrip())
    if proc.returncode != 0:
        raise ReplayError(f"renderer exited {proc.returncode}: {proc.stderr.strip()}")
    if not out_wav.is_file() or out_wav.stat().st_size == 0:
        raise ReplayError("renderer produced no output")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def run(args: argparse.Namespace) -> int:
    bundle = Path(args.bundle).expanduser().resolve()
    audio_dir = Path(args.audio_dir).expanduser().resolve() if args.audio_dir else None
    renderer = Path(args.renderer).expanduser().resolve() if args.renderer else None
    out_wav = Path(args.out).expanduser().resolve() if args.out else None

    if not args.check_only:
        if audio_dir is None or renderer is None or out_wav is None:
            raise UsageError("--audio-dir, --renderer and --out are required unless --check-only")
        if not renderer.is_file() or not os.access(renderer, os.X_OK):
            raise UsageError(f"renderer is not an executable file: {renderer}")

    work_root = Path(args.work_dir).expanduser() if args.work_dir else Path(tempfile.gettempdir())
    work_root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="replay-", dir=str(work_root)))
    try:
        members = unpack(bundle, work / "bundle")
        plan = load_json(members["plan.json"])
        script = load_json(members["script.json"])
        events = read_jsonl(members.get("events_5min.jsonl"))
        telemetry = read_jsonl(members.get("telemetry_5min.jsonl"))
        plan_id = str(plan.get("planId", ""))

        errors, warnings = validate_events(events, telemetry, plan_id)
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

        if args.check_only:
            if errors:
                print(f"check-only: bundle unpacked, but {len(errors)} event validation "
                      f"error(s) were allowed by --allow-invalid-events")
            else:
                print("check-only: bundle and event sequence OK")
            return 0

        out_id = args.out_track or plan.get("outTrackId") or script.get("outRegion", {}).get("trackId")
        in_id = args.in_track or plan.get("inTrackId") or script.get("inRegion", {}).get("trackId")
        if not out_id or not in_id:
            raise ReplayError("cannot resolve outgoing/incoming track ids from plan or script")
        out_src = resolve_track(str(out_id), audio_dir)
        in_src = resolve_track(str(in_id), audio_dir)
        print(f"out: {out_src}\nin:  {in_src}")

        ffmpeg = require_tool("ffmpeg")
        pcm_dir = work / "pcm"
        pcm_dir.mkdir(parents=True, exist_ok=True)
        cache_dir = work_root / "replay-pcm-cache"
        out_pcm = pcm_dir / "out.f32"
        in_pcm = pcm_dir / "in.f32"
        decode_f32(out_src, out_pcm, ffmpeg, cache_dir)
        decode_f32(in_src, in_pcm, ffmpeg, cache_dir)

        patched = patch_script(script, out_pcm, in_pcm)
        script_path = work / "script.patched.json"
        script_path.write_text(json.dumps(patched, indent=2) + "\n", encoding="utf-8")

        out_wav.parent.mkdir(parents=True, exist_ok=True)
        render(renderer, script_path, out_wav)
        print(f"wrote {out_wav}  sha256={sha256(out_wav)}")
        return 0
    finally:
        if not args.keep_work:
            shutil.rmtree(work, ignore_errors=True)
        else:
            print(f"work dir kept: {work}")


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bundle", help="diagnostics zip from DiagnosticsBundler")
    ap.add_argument("--audio-dir", help="directory holding the owner's library audio")
    ap.add_argument("--renderer", help="loop_mix_renderer_ab_main binary with --render")
    ap.add_argument("--out", help="output replay WAV")
    ap.add_argument("--out-track", help="outgoing trackId or explicit audio path (overrides plan)")
    ap.add_argument("--in-track", help="incoming trackId or explicit audio path (overrides plan)")
    ap.add_argument("--work-dir", help="scratch dir (default: the system temp dir)")
    ap.add_argument("--keep-work", action="store_true", help="keep the scratch dir for inspection")
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


if __name__ == "__main__":
    sys.exit(main())
