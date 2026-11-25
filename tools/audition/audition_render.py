#!/usr/bin/env python3
"""Audition renderer + static HTML rater (Part A section 9, B6.3, B6.6).

Takes a pairs JSON (from ``generate_synthetic_pairs.py``) or discovers the
owner's library in ``~/Music``, decodes decks via ffmpeg to raw f32 48 kHz
stereo, builds a ``script.json`` per recipe (compatible with
``loop_mix_renderer_ab_main --render``, i.e. ``TransitionScriptV1``), invokes
the host renderer binary ``--render``, runs ``tools/mixq`` self-checks on
every WAV, and writes:

    index.md      recipe, exit/entry times, reason, rules hit, mixq failures
    index.html    self-contained static audition rater: embedded <audio>
                  player per pair x recipe, 1-5 stars, keep / fix / drop
                  toggles, notes, localStorage persistence and one-click
                  Download ``ratings.json``

It also supports ``--upload`` to ``rclone copy`` the audition folder to
``gdrive:djmix53-audition/`` (rclone is located/verified safely; use
``--dry-run`` to preview without uploading).

Usage:
    audition_render.py --pairs pairs.json --out DIR [--binary PATH]
                       [--recipes S1,C2,F1] [--no-mixq] [--upload]
                       [--dry-run] [--rclone-target gdrive:djmix53-audition/]
    audition_render.py --music-dir ~/Music --out DIR [--binary PATH]

Real audio consumes canonical JVM exports with complete renderGeometry. The
Python generator is limited to labeled synthetic smoke fixtures. An optional
generic equal-power renderer is available only for synthetic controls via
``--allow-generic-fallback``; it cannot establish recipe/listening quality.

``--engine jvm`` runs the per-deck JVM exporter and real canonical plan driver.
Pass ``--jvm-command './gradlew :djmix-analysis:run'`` or separate launcher
commands; Gradle launchers receive their arguments through ``--args=...``.
The default legacy mode remains for existing synthetic/canonical export tests.
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import html as html_mod
import json
import math
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    import numpy as np
except ImportError:  # pragma: no cover - environment guard
    sys.stderr.write("requires numpy (python3 -c 'import numpy')\n")
    raise

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "mixq"))
from mixq import read_wav, write_wav_pcm16, upsample_4x, SR  # noqa: E402

MIXQ_DIR = Path(__file__).resolve().parent.parent / "mixq"
MIXQ = str(MIXQ_DIR / "mixq.py")
DEFAULT_REMOTE = "gdrive:djmix53-audition/"
COMMAND_RUNNER = subprocess.run

# Pinned production identities the exporter itself verifies (bytes + SHA-256)
# before inference. A rendered-mix analysis that does not carry these exact
# modelVersions strings is not accepted as Nirovi/Syvenu evidence.
NIROVI_PIN = "nirovi_int8.ort:sha256=7454d98ab6a88c0b7fe5e91c556b4766ebfabfe3d31ebd61187a033fed489387"
SYVENU_PIN = "syvenu.ort:sha256=1866a5955ed6b0e80f807cb49f56af530121064b8ea5322026566126eb3cd4f3"
STRUCTURE_PIN = "playback-StructureV2"
# Longer exports use playback's partial head/tail inference policy.
MAX_WHOLE_MIX_SECONDS = 480
BEAT_TIMES_MIN_COUNT = 8

import generate_synthetic_pairs as genpair  # noqa: E402
from recipe_catalog import AUTO_IDS, CATALOG, RECIPE_IDS, exported_script, requested_recipes, resolve_recipe  # noqa: E402


# ---------------------------------------------------------------------------
# Audio decode via ffmpeg
# ---------------------------------------------------------------------------


def which(cmd: str) -> str | None:
    return shutil.which(cmd)


def ffmpeg_decode_to_f32(src: str, dst_f32: str, dst_wav: str, command_runner=None) -> None:
    """Decode any ffmpeg-supported file to raw f32 stereo 48 kHz (+ WAV copy)."""
    run = command_runner or COMMAND_RUNNER
    if not which("ffmpeg"):
        raise RuntimeError("ffmpeg not found in PATH (required to decode decks)")
    cmd = [
        "ffmpeg", "-y", "-v", "error", "-i", src,
        "-ar", str(SR), "-ac", "2", "-f", "f32le", dst_f32,
    ]
    run(cmd, check=True)
    # WAV copy for audition HTML playback (PCM16).
    cmd2 = [
        "ffmpeg", "-y", "-v", "error", "-i", src,
        "-ar", str(SR), "-ac", "2", "-c:a", "pcm_s16le", dst_wav,
    ]
    run(cmd2, check=True)


def read_f32(path: str) -> tuple[np.ndarray, np.ndarray]:
    data = np.fromfile(path, dtype=np.float32)
    if len(data) % 2 != 0:
        data = data[: len(data) - (len(data) % 2)]
    inter = data.reshape(-1, 2).astype(np.float64)
    return inter[:, 0].copy(), inter[:, 1].copy()


# ---------------------------------------------------------------------------
# Fallback renderer (equal-power crossfade + loudness match + true-peak guard)
# ---------------------------------------------------------------------------


def render_fallback(pair: dict, script: dict, out_wav: str) -> dict:
    """Python equal-power render of a TransitionScriptV1 (fallback path)."""
    out_l, out_r = read_f32(pair["outF32"])
    in_l, in_r = read_f32(pair["inF32"])
    out_dur = len(out_l) / SR
    exit_sec = float(pair.get("exitSec", out_dur * 0.75))
    fade_sec = float(pair.get("fadeSec", 4.0))
    fade_start = max(exit_sec - fade_sec / 2.0, 0.0)
    fade_end = fade_start + fade_sec
    total = len(out_l)
    n = total
    out_gain = np.ones(n, dtype=np.float64)
    in_gain = np.zeros(n, dtype=np.float64)
    i0, i1 = int(round(fade_start * SR)), int(round(fade_end * SR))
    i0 = min(max(i0, 0), n)
    i1 = min(max(i1, i0 + 1), n)
    idx = np.arange(i0, i1, dtype=np.float64)
    frac = (idx - i0) / max(i1 - i0, 1)
    # Follow the script's GAIN envelope shape (cos/sin equal-power).
    out_gain[i0:i1] = np.cos(frac * math.pi / 2.0)
    in_gain[i0:i1] = np.sin(frac * math.pi / 2.0)
    out_gain[i1:] = 0.0
    in_gain[:i0] = 0.0

    mix_l = out_l * out_gain
    mix_r = out_r * out_gain
    in_n = min(len(in_l), n)
    in_l_pad = np.zeros(n)
    in_r_pad = np.zeros(n)
    in_l_pad[:in_n] = in_l[:in_n]
    in_r_pad[:in_n] = in_r[:in_n]

    # Loudness match: scale IN so its RMS in the overlap matches the fading
    # OUT RMS there, keeping LUFS deviation small (A->B line stays flat-ish).
    ov0, ov1 = max(i0, 1), min(i1, n - 1)
    if ov1 > ov0:
        rms_out = float(np.sqrt(np.mean((out_l[ov0:ov1] * 0.5 + out_r[ov0:ov1] * 0.5) ** 2)))
        rms_in = float(np.sqrt(np.mean((in_l_pad[ov0:ov1] * 0.5 + in_r_pad[ov0:ov1] * 0.5) ** 2)))
        if rms_in > 1e-9 and rms_out > 1e-9:
            scale = np.clip(rms_out / rms_in, 0.5, 2.0)
            in_l_pad *= scale
            in_r_pad *= scale
    mix_l += in_l_pad * in_gain
    mix_r += in_r_pad * in_gain

    # True-peak guard: 4x oversampled peak must stay <= -1.2 dBTP before
    # PCM16 quantisation, so mixq's -1.0 dBTP gate passes with margin.
    ceiling = 10.0 ** (-1.2 / 20.0)
    for _ in range(2):
        peak = 0.0
        for ch in (mix_l, mix_r):
            up = upsample_4x(ch)
            if up.size:
                peak = max(peak, float(np.max(np.abs(up))))
        if peak <= ceiling or peak <= 0:
            break
        factor = (ceiling / peak) * 0.999
        mix_l *= factor
        mix_r *= factor
    write_wav_pcm16(out_wav, mix_l, mix_r, SR)
    return {
        "renderer": "python-fallback",
        "durationSec": round(n / SR, 3),
        "fadeStartSec": round(fade_start, 3),
        "fadeEndSec": round(fade_end, 3),
        "truePeakGuardDb": round(20.0 * math.log10(peak) if peak > 0 else -999, 2) if peak > 0 else None,
    }


def write_json_atomic(path: Path, payload: dict) -> None:
    """Replace a JSON file atomically; readers never observe a half-written mix meta."""
    text = json.dumps(payload, indent=2, allow_nan=False) + "\n"
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _decoded_pcm_identity(wav_path: Path) -> tuple[int, int, int, str]:
    """(rate, channels, frames, sha256) over interleaved LE f32, exactly PcmIdentity.stereo."""
    rate, left, right = read_wav(str(wav_path))
    blob = wav_path.read_bytes()
    channels = 1
    offset = 12
    while offset + 8 <= len(blob):
        length = int.from_bytes(blob[offset + 4:offset + 8], "little")
        if blob[offset:offset + 4] == b"fmt ":
            channels = int.from_bytes(blob[offset + 10:offset + 12], "little")
            break
        offset += 8 + length + (length & 1)
    frames = min(len(left), len(right))
    interleaved = np.empty((frames, 2), dtype="<f4")
    interleaved[:, 0] = np.asarray(left[:frames], dtype=np.float32)
    interleaved[:, 1] = np.asarray(right[:frames], dtype=np.float32)
    if not np.all(np.isfinite(interleaved)):
        raise ValueError("rendered WAV contains non-finite PCM")
    if frames <= 0:
        raise ValueError("rendered WAV contains no PCM frames")
    return int(rate), int(channels), int(frames), hashlib.sha256(interleaved.tobytes(order="C")).hexdigest()


def _beat_times_mix(wav_path: Path, analysis_path: Path, track_id: str, identity: dict) -> dict:
    """Validate exporter output against the exact rendered bytes; fail closed on any gap.

    Returns a status record. ``status == "measured-nirovi-on-mix"`` is only set
    when the analysis is bound to this WAV by content hash *and* a sibling
    execution receipt attests that the pinned Nirovi/Syvenu inference actually
    ran. Model-file pins alone are not treated as proof inference executed.
    """
    rate = identity["sampleRateHz"]
    channels = identity["channels"]
    frames = identity["frameCount"]
    pcm_sha256 = identity["pcmSha256"]
    analysis_bytes = analysis_path.read_bytes()
    analysis_sha256 = hashlib.sha256(analysis_bytes).hexdigest()
    record: dict = {
        "renderedWav": str(wav_path),
        "renderedWavSha256": identity["wavSha256"],
        "renderedWavDecodedPcmSha256": pcm_sha256,
        "renderedWavFrames": frames,
        "renderedWavSampleRateHz": rate,
        "renderedWavChannels": channels,
        "analysis": str(analysis_path),
        "analysisSha256": analysis_sha256,
        "trackId": track_id,
    }

    def unavailable(code: str, reason: str, **extra) -> dict:
        return {"status": "unavailable", "code": code, "reason": reason, **record, **extra}

    if rate != SR:
        return unavailable("render-rate-mismatch", f"canonical renderer output is {SR} Hz, decoded {rate} Hz")
    if channels != 2:
        return unavailable("render-channel-mismatch", f"canonical renderer output is stereo, decoded {channels} channel(s)")
    try:
        analysis = json.loads(analysis_bytes)
    except ValueError as exc:
        return unavailable("analysis-unreadable", f"analysis JSON did not decode: {exc}")
    if not isinstance(analysis, dict):
        return unavailable("analysis-unreadable", "analysis JSON is not an object")
    if analysis.get("schema") != 3:
        return unavailable("analysis-schema-mismatch", f"analysis schema is {analysis.get('schema')!r}, expected 3")
    if analysis.get("trackId") != track_id:
        return unavailable("analysis-track-mismatch", f"analysis trackId is {analysis.get('trackId')!r}, expected {track_id!r}")
    if analysis.get("contentHash") != pcm_sha256:
        return unavailable("analysis-content-hash-mismatch",
                           "analysis contentHash does not equal the decoded rendered WAV PCM identity; refusing a stale or foreign analysis")
    duration_ms = analysis.get("durationMs")
    expected_ms = frames * 1000.0 / rate
    if not isinstance(duration_ms, int) or isinstance(duration_ms, bool) or abs(duration_ms - expected_ms) > 1.0:
        return unavailable("analysis-duration-mismatch", f"analysis durationMs {duration_ms!r} does not match decoded {expected_ms:.3f} ms")
    if duration_ms > MAX_WHOLE_MIX_SECONDS * 1000:
        return unavailable("render-exceeds-whole-file-window",
                           f"rendered mix is {duration_ms / 1000.0:.1f} s; playback's >{MAX_WHOLE_MIX_SECONDS} s head/tail policy "
                           "yields window beats, not a mix-wide beat grid")
    if analysis.get("analysisLevel") != "FULL":
        return unavailable("analysis-not-whole-file", f"analysisLevel is {analysis.get('analysisLevel')!r}; only FULL whole-mix analysis is a beat-grid source")
    bpm = analysis.get("bpm")
    grid_confidence = analysis.get("gridConfidence")
    if not isinstance(bpm, (int, float)) or isinstance(bpm, bool) or not math.isfinite(bpm) or bpm <= 0:
        return unavailable("analysis-bpm-invalid", f"analysis bpm is {bpm!r}")
    if not isinstance(grid_confidence, (int, float)) or isinstance(grid_confidence, bool) \
            or not math.isfinite(grid_confidence) or not 0 <= grid_confidence <= 1:
        return unavailable("analysis-confidence-invalid", f"analysis gridConfidence is {grid_confidence!r}")
    versions = analysis.get("modelVersions")
    if not isinstance(versions, dict) or versions.get("beat") != NIROVI_PIN or versions.get("vocal") != SYVENU_PIN \
            or versions.get("structure") != STRUCTURE_PIN:
        return unavailable("model-identity-mismatch", "analysis modelVersions are not the pinned Nirovi/Syvenu/StructureV2 identities")
    receipt_path = Path(str(analysis_path) + ".execution.json")
    record["executionReceipt"] = str(receipt_path)
    if not receipt_path.is_file():
        return unavailable(
            "execution-evidence-missing",
            "the exporter did not attest the executed inference backend; pinned model files and modelVersions constants "
            "are not proof that Nirovi ran, so rendered-mix beats remain unavailable",
            bpm=bpm, gridConfidence=grid_confidence, analysisLevel=analysis.get("analysisLevel"),
        )
    receipt_sha256 = _sha256_file(receipt_path)
    record["executionReceiptSha256"] = receipt_sha256
    try:
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    except ValueError as exc:
        return unavailable("execution-evidence-invalid", f"execution receipt did not decode: {exc}")
    if not isinstance(receipt, dict):
        return unavailable("execution-evidence-invalid", "execution receipt is not an object")
    def executed_backend(value) -> bool:
        if not isinstance(value, str):
            return False
        value = value.strip().lower()
        return bool(value) and not value.startswith((
            "unknown", "unavailable", "not-run", "not_run", "none", "no sessions opened",
        ))

    checks = {
        "schema": type(receipt.get("schema")) is int and receipt["schema"] == 1,
        "inputSha256": receipt.get("inputSha256") == pcm_sha256,
        "analysisSha256": receipt.get("analysisSha256") == analysis_sha256,
        "trackId": receipt.get("trackId") == track_id,
        "sampleRateHz": type(receipt.get("sampleRateHz")) is int and receipt["sampleRateHz"] == rate,
        "channels": type(receipt.get("channels")) is int and receipt["channels"] == channels,
        "frameCount": type(receipt.get("frameCount")) is int and receipt["frameCount"] == frames,
        "beatModelTag": receipt.get("beatModelTag") == "nirovi",
        "vocalModelTag": receipt.get("vocalModelTag") == "syvenu",
        "beatModelSha256": receipt.get("beatModelSha256") == NIROVI_PIN.split("sha256=", 1)[1],
        "vocalModelSha256": receipt.get("vocalModelSha256") == SYVENU_PIN.split("sha256=", 1)[1],
        "inference": receipt.get("inference") == "completed",
        "coverage": receipt.get("coverage") == "whole-file",
        "beatBackend": executed_backend(receipt.get("beatBackend")),
        "vocalBackend": executed_backend(receipt.get("vocalBackend")),
    }
    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        return unavailable("execution-evidence-invalid", f"execution receipt failed checks: {', '.join(failed)}")
    # V3 beatsMs may retain head/tail windows or reconstruct beats from
    # downbeats. Only the executed full-file Nirovi backend's own returned
    # times qualify here; never promote a mapper grid or source-deck grid.
    beats = receipt.get("beatTimesSec")
    if not isinstance(beats, list) or len(beats) < BEAT_TIMES_MIN_COUNT:
        return unavailable("beat-grid-missing", "execution receipt lacks at least eight measured Nirovi beatTimesSec")
    if any(type(value) not in (int, float) or not math.isfinite(value) for value in beats):
        return unavailable("beat-grid-invalid", "execution beatTimesSec must contain finite numeric seconds")
    if any(later <= earlier for earlier, later in zip(beats, beats[1:])):
        return unavailable("beat-grid-invalid", "execution beatTimesSec must be strictly increasing")
    if beats[0] < 0 or beats[-1] > frames / rate:
        return unavailable("beat-grid-out-of-range", "execution beatTimesSec falls outside the rendered PCM duration")
    if _sha256_file(wav_path) != identity["wavSha256"]:
        return unavailable("render-changed-during-analysis", "rendered WAV bytes changed while the exporter ran")
    return {
        "status": "measured-nirovi-on-mix",
        "reason": "",
        **record,
        "durationMs": duration_ms,
        "bpm": bpm,
        "gridConfidence": grid_confidence,
        "analysisLevel": analysis.get("analysisLevel"),
        "beatCount": len(beats),
        "beatTimes": beats,
        "modelVersions": versions,
        "execution": {
            "beatModelTag": receipt["beatModelTag"],
            "vocalModelTag": receipt["vocalModelTag"],
            "beatBackend": receipt["beatBackend"],
            "vocalBackend": receipt["vocalBackend"],
            "inference": receipt["inference"],
            "coverage": receipt["coverage"],
            "receiptSha256": receipt_sha256,
        },
    }


def analyze_rendered_mix(
    wav_path: Path,
    analysis_path: Path,
    track_id: str,
    *,
    model_dir: str | None,
    analysis_command: str | None,
    command_runner=None,
) -> dict:
    """Run the configured TrackAnalysisExporter on this exact rendered mix WAV."""
    run = command_runner or COMMAND_RUNNER
    if not model_dir:
        return {"status": "unavailable", "code": "model-directory-missing",
                "reason": "--model-dir is not configured; rendered-mix Nirovi analysis cannot run"}
    if not os.path.isdir(os.path.expanduser(model_dir)):
        return {"status": "unavailable", "code": "model-directory-missing",
                "reason": f"configured model directory does not exist: {model_dir}"}
    if not analysis_command:
        return {"status": "unavailable", "code": "analysis-command-missing",
                "reason": "no TrackAnalysisExporter command is configured"}
    try:
        wav_hash = _sha256_file(wav_path)
        rate, channels, frames, pcm_hash = _decoded_pcm_identity(wav_path)
        if rate != SR or channels != 2 or frames / rate > MAX_WHOLE_MIX_SECONDS:
            raise ValueError("rendered-mix analysis requires stereo 48000 Hz PCM and whole-file coverage of at most 480 seconds")
        if _sha256_file(wav_path) != wav_hash:
            raise ValueError("rendered WAV changed while its PCM identity was measured")
        identity = {"wavSha256": wav_hash, "pcmSha256": pcm_hash,
                    "sampleRateHz": rate, "channels": channels, "frameCount": frames}
        # Exporter failure leaves prior analysis files and the rendered WAV
        # intact. Fresh, unique outputs also prevent reuse of a stale receipt.
        with tempfile.TemporaryDirectory(prefix=".mix-analysis-", dir=analysis_path.parent) as temporary:
            staged = Path(temporary) / analysis_path.name
            arguments = ["--input", str(wav_path), "--input-format", "wav", "--out", str(staged),
                         "--track-id", track_id, "--duration", repr(frames / rate),
                         "--model-dir", os.path.expanduser(model_dir)]
            command = _jvm_command(analysis_command, arguments)
            proc = run(command, capture_output=True, text=True)
            if proc.returncode != 0:
                return {"status": "unavailable", "code": "exporter-failed",
                        "reason": f"rendered-mix exporter exited {proc.returncode}: {(proc.stderr or '').strip()[-1500:]}",
                        "command": command, "renderedWavSha256": wav_hash}
            if not staged.is_file():
                return {"status": "unavailable", "code": "exporter-no-output",
                        "reason": "rendered-mix exporter returned success without its requested analysis JSON",
                        "command": command, "renderedWavSha256": wav_hash}
            result = _beat_times_mix(wav_path, staged, track_id, identity)
            result["command"] = command
            # Unverified output is never promoted over a prior verified file.
            # Its status and diagnostic hashes remain in the fresh mix meta.
            if result["status"] == "measured-nirovi-on-mix":
                receipt_path = Path(str(analysis_path) + ".execution.json")
                os.replace(staged, analysis_path)
                os.replace(Path(str(staged) + ".execution.json"), receipt_path)
                result["analysis"] = str(analysis_path)
                result["executionReceipt"] = str(receipt_path)
            else:
                result.pop("analysis", None)
                result.pop("executionReceipt", None)
            return result
    except (ValueError, OverflowError, OSError, subprocess.SubprocessError) as exc:
        return {"status": "unavailable", "code": "analysis-validation-failed",
                "reason": f"rendered-mix analysis failed validation: {exc}"}


def record_rendered_mix(source_meta: dict, meta_path: Path, result: dict, *, synthetic: bool) -> dict:
    """Attach measured mix beats to sibling mixq metadata, or record why they are absent.

    A stale ``beatTimesMix`` is always removed before writing so a failed or
    unverifiable analysis can never inherit beats from an earlier render.
    """
    meta = dict(source_meta)
    meta.pop("beatTimesMix", None)
    # Deck aliases must not be mistaken for rendered-mix beats by mixq's
    # backward-compatible metadata reader.
    for key in ("beatTimesIn", "beat_times_in", "beatsIn"):
        meta.pop(key, None)
    if synthetic:
        meta["synthetic"] = True
    else:
        meta["canonicalPlaybackGeometry"] = True
    meta["renderedMixAnalysis"] = dict(result)
    if not synthetic and result.get("status") == "measured-nirovi-on-mix":
        meta["beatTimesMix"] = result["beatTimes"]
    write_json_atomic(meta_path, meta)
    return meta


def render_with_binary(binary: str, script_path: str, out_wav: str, command_runner=None) -> bool:
    """Render atomically; a supplied renderer's failure must never become a fade."""
    run = command_runner or COMMAND_RUNNER
    if not binary:
        return False
    if not os.path.isfile(binary):
        raise RuntimeError(f"Host renderer does not exist: {binary}")
    fd, temporary = tempfile.mkstemp(prefix=".audition-", suffix=".wav", dir=Path(out_wav).parent)
    os.close(fd)
    os.unlink(temporary)
    try:
        proc = run(
            [binary, "--render", script_path, "--out", temporary],
            capture_output=True, text=True, timeout=600,
        )
        if proc.returncode != 0 or not os.path.isfile(temporary):
            raise RuntimeError(f"Host renderer failed (exit {proc.returncode}): {proc.stderr[-2000:].strip()}")
        os.replace(temporary, out_wav)
        return True
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError(f"Host renderer failed: {exc}") from exc
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


# ---------------------------------------------------------------------------
# Rater HTML / index.md
# ---------------------------------------------------------------------------


def render_rater_html(items: list[dict], title: str) -> str:
    """Self-contained audition rater: players, stars, verdicts, notes, export."""
    entries = []
    for it in items:
        auto_badge = "<span class='badge'>AUTO PICK</span>" if it.get("autoPick") else ""
        entries.append(
            f"""
    <section class="card" data-id="{html_mod.escape(it['id'])}">
      <header>
        <div class="title">{html_mod.escape(it['pairName'])} <small>·</small>
          <span class="recipe">{html_mod.escape(it['recipeId'])}</span> {auto_badge}</div>
        <div class="reason">{html_mod.escape(it.get('reason', ''))}</div>
      </header>
      <audio controls preload="none" src="{html_mod.escape(it['wav'])}"></audio>
      <div class="meta">
        <span>exit {it.get('exitSec', '')}s</span><span>entry {it.get('entrySec', '')}s</span>
        <span>mixq: {html_mod.escape(it.get('mixqStatus', ''))}</span>
      </div>
      <div class="row stars" role="radiogroup" aria-label="Stars">
        {"".join(f"<button type='button' data-star='{s}' aria-label='{s} star'>{'★'}</button>" for s in range(1, 6))}
      </div>
      <div class="row verdict">
        <button type="button" data-verdict="keep">keep</button>
        <button type="button" data-verdict="fix">fix</button>
        <button type="button" data-verdict="drop">drop</button>
      </div>
      <textarea placeholder="Notes for this mix…" aria-label="Notes"></textarea>
    </section>"""
        )
    data_json = json.dumps({it["id"]: it for it in items})
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{html_mod.escape(title)}</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root{{--bg:#101114;--card:#1a1c20;--ink:#eceef2;--muted:#9aa3b2;--line:#2c2f36;--accent:#6cb6ff}}
@media (prefers-color-scheme: light){{:root{{--bg:#f4f5f7;--card:#fff;--ink:#16181d;--muted:#5b6472;--line:#e3e6ea;--accent:#0d6e2b}}}}
*{{box-sizing:border-box}}body{{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--ink)}}
.toolbar{{position:sticky;top:0;background:var(--card);border-bottom:1px solid var(--line);padding:10px 16px;display:flex;gap:12px;align-items:center;z-index:5}}
.toolbar h1{{font-size:16px;margin:0;flex:1}} .toolbar button{{font:inherit;padding:8px 14px;border-radius:8px;border:1px solid var(--line);background:var(--accent);color:#fff;cursor:pointer}}
.toolbar .count{{color:var(--muted);font-size:13px}}
main{{max-width:960px;margin:0 auto;padding:16px 16px 60px;display:grid;gap:14px}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px}}
.title{{font-weight:600}} .recipe{{color:var(--accent)}} .reason{{color:var(--muted);font-size:13px;margin-top:2px}}
.badge{{font-size:10px;background:var(--accent);color:#fff;border-radius:999px;padding:2px 8px;vertical-align:middle}}
audio{{width:100%;margin:10px 0 6px}} .meta{{display:flex;gap:14px;flex-wrap:wrap;color:var(--muted);font-size:12px}}
.row{{display:flex;gap:8px;margin-top:8px;flex-wrap:wrap}}
.row button{{font:inherit;padding:6px 14px;border-radius:999px;border:1px solid var(--line);background:transparent;color:var(--ink);cursor:pointer}}
.stars button{{font-size:22px;border:none;padding:2px 4px;color:var(--muted);background:transparent}}
.stars button.on{{color:#f2b01e}} .verdict button.on{{background:var(--accent);color:#fff;border-color:var(--accent)}}
textarea{{width:100%;min-height:56px;margin-top:10px;background:transparent;border:1px solid var(--line);border-radius:8px;color:var(--ink);font:inherit;padding:8px}}
.small{{color:var(--muted);font-size:12px}}
</style>
</head>
<body>
<div class="toolbar"><h1>{html_mod.escape(title)}</h1>
<span class="count" id="count"></span>
<button id="download" type="button">Download ratings.json</button></div>
<main>
<p class="small">Rate each mix 1–5 stars, then keep / fix / drop. Everything saves in this browser automatically; download the JSON when you are done and hand it back. WAV files sit next to this page (<code>index.html</code>) — keep the folder together.</p>
{''.join(entries)}
</main>
<script>
const ITEMS = {data_json};
const KEY = 'audition-ratings-v1';
let ratings = {{}};
try {{ ratings = JSON.parse(localStorage.getItem(KEY) || '{{}}'); }} catch (e) {{ ratings = {{}}; }}
function save() {{ localStorage.setItem(KEY, JSON.stringify(ratings)); updateCount(); }}
function updateCount() {{
  const done = Object.values(ratings).filter(r => (r.stars && r.stars > 0) || (r.verdict)).length;
  document.getElementById('count').textContent = done + ' / ' + Object.keys(ITEMS).length + ' rated';
}}
function paint(card, id) {{
  const r = ratings[id] || {{}};
  card.querySelectorAll('.stars button').forEach(b => {{
    const s = parseInt(b.dataset.star, 10);
    b.classList.toggle('on', !!r.stars && s <= r.stars);
  }});
  card.querySelectorAll('.verdict button').forEach(b => {{
    b.classList.toggle('on', r.verdict === b.dataset.verdict);
  }});
  const ta = card.querySelector('textarea');
  if (document.activeElement !== ta) ta.value = r.notes || '';
}}
document.querySelectorAll('.card').forEach(card => {{
  const id = card.dataset.id;
  paint(card, id);
  card.querySelectorAll('.stars button').forEach(b => b.addEventListener('click', () => {{
    ratings[id] = ratings[id] || {{}}; ratings[id].stars = parseInt(b.dataset.star, 10); save(); paint(card, id);
  }}));
  card.querySelectorAll('.verdict button').forEach(b => b.addEventListener('click', () => {{
    ratings[id] = ratings[id] || {{}}; ratings[id].verdict = b.dataset.verdict; save(); paint(card, id);
  }}));
  card.querySelector('textarea').addEventListener('input', e => {{
    ratings[id] = ratings[id] || {{}}; ratings[id].notes = e.target.value; save();
  }});
}});
document.getElementById('download').addEventListener('click', () => {{
  const payload = {{ exportedAt: new Date().toISOString(), ratings: ratings }};
  const blob = new Blob([JSON.stringify(payload, null, 2)], {{ type: 'application/json' }});
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'ratings.json';
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}});
updateCount();
</script>
</body></html>
"""


def render_index_md(items: list[dict], title: str) -> str:
    lines = [f"# {title}", "", "Audition renders for AE3. Rate them in `index.html` (keeps audio + ratings together).", ""]
    lines.append("| # | Pair | Recipe | Exit (s) | Entry (s) | Reason | Rules hit | mixq | Files |")
    lines.append("|---|---|---|---|---|---|---|---|---|")
    for i, it in enumerate(items, 1):
        lines.append(
            f"| {i} | {it['pairName']} | {it['recipeId']} | {it.get('exitSec', '')} | "
            f"{it.get('entrySec', '')} | {it.get('reason', '')} | "
            f"{', '.join(it.get('rulesHit', []))} | {it.get('mixqStatus', '')} | `{it['wav']}` |"
        )
    lines.append("")
    failures = [it for it in items if "FAIL" in it.get("mixqStatus", "")]
    if failures:
        lines.append("## mixq failures (see `mixq-results.json` + quality HTML for detail)")
        for it in failures:
            lines.append(f"- `{it['wav']}`: {', '.join(it.get('mixqFails', []))}")
    elif items and all(it.get("mixqStatus") == "PASS" for it in items):
        lines.append("## mixq failures\n\nNone — every render passes all 8 B6.2 gates.")
    else:
        lines.append("## mixq status\n\nChecks are incomplete or skipped; no quality-pass claim is established.")
    lines.append("")
    lines.append("## Auto picks")
    for it in items:
        if it.get("autoPick"):
            lines.append(f"- {it['pairId']} @ {it['level']} → `{it['wav']}` ({it['recipeId']})")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# Discovery mode (~/Music)
# ---------------------------------------------------------------------------


def discover_music_pairs(music_dir: str, out_dir: Path) -> list[dict]:
    """Build source requests only; the JVM exporter owns analysis and placement."""
    exts = ("*.mp3", "*.flac", "*.m4a", "*.ogg", "*.wav", "*.opus")
    files: list[str] = []
    for pat in exts:
        files.extend(glob.glob(os.path.join(music_dir, pat)))
        files.extend(glob.glob(os.path.join(music_dir, pat.upper())))
    files = sorted(set(files))
    if len(files) < 2:
        raise RuntimeError(f"need at least 2 audio files in {music_dir}, found {len(files)}")
    pairs = []
    for i in range(0, len(files) - 1, 2):
        out_f, in_f = files[i], files[i + 1]
        pid = f"music{i // 2 + 1}"
        pair = {
            "pairId": pid,
            "name": f"{Path(out_f).stem} -> {Path(in_f).stem}",
            "outSrc": out_f,
            "inSrc": in_f,
            "recipes": list(genpair.DEFAULT_RECIPES),
        }
        pairs.append(pair)
    return pairs


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def _jvm_command(prefix: str, arguments: list[str], *, driver: bool = False) -> list[str]:
    command = shlex.split(prefix)
    if not command:
        raise ValueError("A JVM launcher command is required for --engine jvm")
    if any(Path(part).name in {"gradle", "gradlew"} or Path(part).name.startswith("gradlew.") for part in command):
        if driver and ":djmix-analysis:run" in command and arguments and arguments[0] != "--audition-plan-driver":
            arguments = ["--audition-plan-driver"] + arguments
        return command + ["--args=" + shlex.join(arguments)]
    return command + arguments


def _required_recipe_stems(requested_id: str, plan: dict | None = None) -> dict[str, list[str]]:
    """The actual frozen selection wins, including an explicitly empty no-stem set."""
    resolved = (plan or {}).get("recipeId") if requested_id.startswith("AUTO-") else requested_id
    recipe = CATALOG.get(resolved or "")
    requirements = recipe.get("requirements", {}) if recipe else {}
    grouped = {"out": [], "in": []}
    selected = plan["requiredStems"] if plan is not None else requirements.get("requiredStems", [])
    for requirement in selected:
        deck = str(requirement.get("deck", "")).upper()
        stem = str(requirement.get("stem", "")).upper()
        target = "out" if deck == "OUT" else "in" if deck == "IN" else None
        if not target or stem not in {"VOCALS", "DRUMS", "BASS", "OTHER"}:
            raise ValueError("Invalid frozen requiredStems deck or kind")
        if stem not in grouped[target]:
            grouped[target].append(stem)
    return grouped


def _stem_window(metadata: dict, deck: str) -> tuple[int, int, int]:
    """Consume bounds queried from the exact playback geometry by the JVM driver."""
    window = metadata.get("stemWindows", {}).get(deck)
    if not isinstance(window, dict):
        raise ValueError(f"{deck} playback-frozen stem window unavailable; no approximate geometry is allowed")
    start, end, padding = (window.get(key) for key in ("startMs", "endMs", "paddingMs"))
    if any(type(value) is not int for value in (start, end, padding)) or start < 0 or end <= start or padding < 0:
        raise ValueError(f"{deck} frozen stem window is invalid")
    return start, end, padding


def build_jvm_export(
    pairs: list[dict], requested: list[str], export_dir: Path, *, analysis_command: str,
    driver_command: str, binary: str, command_runner=None, dry_run: bool = False,
    model_dir: str | None = None, stems: bool = False, scnet_model: str | None = None,
    style_override: str | None = None, seed: int = 1, position_ms: int = 0,
    render_audio: bool = True, run_mixq: bool = True,
    input_base: Path | None = None,
) -> tuple[Path, dict]:
    """Run the real per-deck analysis and canonical planner; never synthesize real scripts."""
    run = command_runner or COMMAND_RUNNER
    export_dir.mkdir(parents=True, exist_ok=True)
    exported_pairs = []
    command_log = []
    for pair_index, source_pair in enumerate(pairs):
        pair = dict(source_pair)
        pair_id = str(pair.get("pairId", ""))
        if not pair_id or Path(pair_id).name != pair_id or pair_id in {".", ".."}:
            raise ValueError("pairId must be a single safe filename component")
        playback_exports = pair.get("playbackExports", {})
        if any(not isinstance(playback_exports.get(rid), dict) for rid in requested):
            raise ValueError(f"{pair_id}: live geometry parity unavailable for arbitrary new pairs; playbackExports needs the frozen plan/script for every requested recipe")
        for deck in ("out", "in"):
            source = pair.get(f"{deck}Src") or pair.get(f"{deck}Wav")
            if not source:
                raise ValueError(f"{pair_id}: --engine jvm needs {deck}Src or {deck}Wav")
            source = os.path.abspath(os.path.expanduser(source))
            raw_path = export_dir / f"{pair_id}_{deck}.f32"
            wav_path = export_dir / f"{pair_id}_{deck}.wav"
            analysis_path = export_dir / f"{pair_id}_{deck}.analysis.json"
            if dry_run:
                command_log.extend([
                    ["ffmpeg", "-y", "-v", "error", "-i", source, "-ar", str(SR), "-ac", "2", "-f", "f32le", str(raw_path)],
                    ["ffmpeg", "-y", "-v", "error", "-i", source, "-ar", str(SR), "-ac", "2", "-c:a", "pcm_s16le", str(wav_path)],
                ])
            else:
                ffmpeg_decode_to_f32(source, str(raw_path), str(wav_path), command_runner=run)
            analysis_args = ["--input", str(raw_path), "--input-format", "pcm_f32", "--sample-rate", str(SR), "--channels", "2",
                             "--track-id", str(pair.get(f"{deck}TrackId") or Path(source).stem),
                             "--out", str(analysis_path)]
            if model_dir:
                analysis_args += ["--model-dir", model_dir]
            command = _jvm_command(analysis_command, analysis_args)
            command_log.append(command)
            if not dry_run:
                proc = run(command, capture_output=True, text=True)
                if proc.returncode != 0:
                    raise RuntimeError(f"TrackAnalysisExporter failed for {pair_id}/{deck} (exit {proc.returncode}): {proc.stderr[-2000:].strip()}")
                if not analysis_path.is_file():
                    raise RuntimeError(f"TrackAnalysisExporter returned success without {analysis_path}")

        pair["outTrackId"] = pair.get("outTrackId") or pair.get("outTrackId") or Path(pair["outSrc"] if pair.get("outSrc") else pair["outWav"]).stem
        pair["inTrackId"] = pair.get("inTrackId") or pair.get("inTrackId") or Path(pair["inSrc"] if pair.get("inSrc") else pair["inWav"]).stem
        pair["synthetic"] = False
        pair["exports"] = {}
        pair["refusals"] = {}
        for requested_id in requested:
            playback = playback_exports[requested_id]
            frozen_paths = {}
            for kind in ("plan", "script"):
                value = playback.get(kind)
                if not isinstance(value, str):
                    raise ValueError(f"{pair_id}/{requested_id}: playback {kind} must name an existing JSON file")
                frozen_path = Path(value).expanduser()
                frozen_path = frozen_path if frozen_path.is_absolute() else (input_base or Path.cwd()) / frozen_path
                if not frozen_path.is_file():
                    raise ValueError(f"Missing playback-frozen {kind}: {frozen_path}")
                frozen_paths[kind] = frozen_path.resolve()
            frozen_plan = json.loads(frozen_paths["plan"].read_text(encoding="utf-8"))
            level = style_override or frozen_plan["djLevel"]
            plan_path = export_dir / f"{pair_id}_{requested_id}.plan.json"
            script_path = export_dir / f"{pair_id}_{requested_id}.script.json"
            meta_path = export_dir / f"{pair_id}_{requested_id}.meta.json"
            driver_args = [
                "--out-analysis", str(export_dir / f"{pair_id}_out.analysis.json"),
                "--in-analysis", str(export_dir / f"{pair_id}_in.analysis.json"),
                "--out-pcm", str(export_dir / f"{pair_id}_out.f32"),
                "--in-pcm", str(export_dir / f"{pair_id}_in.f32"),
                "--sample-rate", str(SR), "--style", level, "--recipe", requested_id,
                "--frozen-plan", str(frozen_paths["plan"]), "--frozen-script", str(frozen_paths["script"]),
                "--out", str(script_path), "--out-plan", str(plan_path), "--out-meta", str(meta_path),
            ]
            required = _required_recipe_stems(requested_id, frozen_plan)
            needs_stem_probe = stems and any(required.values())
            probe_plan = export_dir / f"{pair_id}_{requested_id}.window.plan.json"
            probe_script = export_dir / f"{pair_id}_{requested_id}.window.script.json"
            probe_meta = export_dir / f"{pair_id}_{requested_id}.window.meta.json"
            if needs_stem_probe:
                probe_args = driver_args + ["--plan-only", "--assume-stems"]
                probe_args[probe_args.index("--out") + 1] = str(probe_script)
                probe_args[probe_args.index("--out-plan") + 1] = str(probe_plan)
                probe_args[probe_args.index("--out-meta") + 1] = str(probe_meta)
                command = _jvm_command(driver_command, probe_args, driver=True)
                command_log.append(command)
                if not dry_run:
                    proc = run(command, capture_output=True, text=True)
                    if proc.returncode != 0:
                        raise RuntimeError(f"AuditionPlanDriver refused {pair_id}/{requested_id} (exit {proc.returncode}): {proc.stderr[-2000:].strip()}")
                    if not probe_plan.is_file():
                        raise RuntimeError(f"AuditionPlanDriver returned success without {probe_plan}")
                    probe = json.loads(probe_plan.read_text(encoding="utf-8"))
                    probe_metadata = json.loads(probe_meta.read_text(encoding="utf-8"))
                else:
                    probe = frozen_plan
                required = _required_recipe_stems(requested_id, probe)
                if requested_id.startswith("AUTO-") and not dry_run:
                    driver_args[driver_args.index("--recipe") + 1] = probe["recipeId"]
                stem_manifest_args = []
                for deck in ("out", "in"):
                    if not required[deck]:
                        continue
                    if dry_run:
                        window_start, window_end, padding = f"<{deck}-frozen-first-frame-ms>", f"<{deck}-frozen-end-frame-ms>", "<barDurationMs>"
                    else:
                        window_start, window_end, padding = _stem_window(probe_metadata, deck)
                    stem_args = [
                        "--input", str(export_dir / f"{pair_id}_{deck}.f32"), "--input-format", "pcm_f32", "--sample-rate", str(SR), "--channels", "2",
                        "--out", str(export_dir / f"{pair_id}_{deck}.analysis.json"),
                        "--track-id", str(pair.get(f"{deck}TrackId") or Path(pair.get(f"{deck}Src") or pair[f"{deck}Wav"]).stem),
                        "--stems", "--stems-only", "--out-stems", str(export_dir / f"{pair_id}_{requested_id}_{deck}.stems.json"),
                        "--window-start-ms", str(window_start), "--window-end-ms", str(window_end),
                        "--window-padding-ms", str(padding), "--required-stems", ",".join(required[deck]),
                    ]
                    if scnet_model:
                        stem_args += ["--scnet-model", scnet_model]
                    elif model_dir:
                        stem_args += ["--model-dir", model_dir]
                    stem_command = _jvm_command(analysis_command, stem_args)
                    command_log.append(stem_command)
                    if not dry_run:
                        proc = run(stem_command, capture_output=True, text=True)
                        if proc.returncode != 0:
                            raise RuntimeError(f"TrackAnalysisExporter stem separation failed for {pair_id}/{deck} (exit {proc.returncode}): {proc.stderr[-2000:].strip()}")
                        manifest_path = export_dir / f"{pair_id}_{requested_id}_{deck}.stems.json"
                        if not manifest_path.is_file():
                            raise RuntimeError(f"TrackAnalysisExporter returned success without {manifest_path}")
                    stem_manifest_args += [f"--{deck}-stems", str(export_dir / f"{pair_id}_{requested_id}_{deck}.stems.json")]
                driver_args += stem_manifest_args
            command = _jvm_command(driver_command, driver_args, driver=True)
            command_log.append(command)
            if not dry_run:
                proc = run(command, capture_output=True, text=True)
                if proc.returncode != 0:
                    raise RuntimeError(f"AuditionPlanDriver refused {pair_id}/{requested_id} (exit {proc.returncode}): {proc.stderr[-2000:].strip()}")
                missing = [str(path) for path in (plan_path, script_path, meta_path) if not path.is_file()]
                if missing:
                    raise RuntimeError(f"AuditionPlanDriver returned success without required artifacts: {', '.join(missing)}")
            if dry_run and render_audio:
                wav_path = export_dir.parent / f"{pair_id}_{requested_id}.wav"
                command_log.append([binary, "--render", str(script_path), "--out", str(wav_path)])
                if model_dir:
                    command_log.append(_jvm_command(analysis_command, [
                        "--input", str(wav_path), "--out", str(export_dir.parent / f"{pair_id}_{requested_id}.mix.analysis.json"),
                        "--track-id", f"{pair_id}_{requested_id}.mix", "--model-dir", model_dir,
                    ]))
                if run_mixq:
                    command_log.append([sys.executable, MIXQ, "check", str(wav_path), "--meta", str(meta_path),
                                        "-o", str(export_dir.parent / f"{pair_id}_{requested_id}.mixq.json")])
            pair["exports"][requested_id] = {"plan": str(plan_path), "script": str(script_path)}
        exported_pairs.append(pair)
    result_path = export_dir / "pairs.json"
    manifest = {"pairs": exported_pairs, "engine": "3.0.0"}
    if not dry_run:
        result_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    else:
        print(json.dumps({"plannedCommands": command_log}, indent=2))
    return result_path, manifest


def main(argv: list[str] | None = None, command_runner=None) -> int:
    global COMMAND_RUNNER
    if command_runner is not None:
        COMMAND_RUNNER = command_runner
    run = COMMAND_RUNNER
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--engine", choices=("legacy", "jvm"), default="legacy",
                    help="legacy uses explicit synthetic/export fixtures; jvm invokes the real TrackAnalysisV3 planner pipeline")
    ap.add_argument("--pairs", default=None, help="Pairs JSON from generate_synthetic_pairs.py")
    ap.add_argument("--music-dir", default=None, help="Discover library pairs here (e.g. ~/Music)")
    ap.add_argument("--out", help="Output directory for scripts, WAVs and index.html")
    ap.add_argument("--binary", default=None, help="Host renderer binary (required for recipe audio)")
    ap.add_argument("--recipes", default=None, help="ALL, AUTO or comma-separated canonical recipe IDs (default: manifest recipes)")
    ap.add_argument("--list-recipes", action="store_true", help="List all 26 canonical recipe names and descriptions")
    ap.add_argument("--scripts-only", action="store_true", help="Export/validate scripts without rendering audio or writing a listening rater")
    ap.add_argument("--exporter", help="JVM exporter command; receives --pairs REQUEST --out DIR --recipes IDS and writes DIR/pairs.json")
    ap.add_argument("--jvm-command", help="Common JVM launcher (for example './gradlew :djmix-analysis:run')")
    ap.add_argument("--analysis-command", help="TrackAnalysisExporter launcher; defaults to --jvm-command")
    ap.add_argument("--driver-command", help="AuditionPlanDriver launcher; defaults to --jvm-command")
    ap.add_argument("--model-dir", help="Directory containing Nirovi/Syvenu and optional scnet.int8.onnx models")
    ap.add_argument("--stems", action="store_true", help="Request real SCNet stems; absent model uses the declared no-stem variant")
    ap.add_argument("--scnet-model", help="Explicit SCNet ONNX file (defaults to <model-dir>/scnet.int8.onnx)")
    ap.add_argument("--style", choices=("SUBTLE", "CLUB", "FESTIVAL"), help="Planner style override for all JVM requests")
    ap.add_argument("--seed", type=int, default=1, help="Deterministic JVM generation seed")
    ap.add_argument("--position-ms", type=int, default=0, help="Planner current-position input in milliseconds")
    ap.add_argument("--jvm-dry-run", action="store_true", help="Print complete ffmpeg/exporter/planner commands without executing the JVM pipeline")
    ap.add_argument("--allow-generic-fallback", action="store_true", help="Explicit synthetic-only equal-power control; no recipe quality claim")
    ap.add_argument("--no-mixq", action="store_true", help="Skip mixq self-check")
    ap.add_argument("--upload", action="store_true", help="Upload folder to Drive via rclone")
    ap.add_argument("--dry-run", action="store_true", help="Print rclone command instead of uploading")
    ap.add_argument("--rclone-target", default=DEFAULT_REMOTE)
    ap.add_argument("--title", default="AE3 audition")
    args = ap.parse_args(argv)

    if args.list_recipes:
        for rid in RECIPE_IDS:
            print(f"{rid}\t{CATALOG[rid]['name']}\t{CATALOG[rid]['description']}")
        return 0
    if not args.out:
        ap.error("--out is required unless --list-recipes is used")
    try:
        recipes_override = requested_recipes(args.recipes) if args.recipes else None
    except ValueError as exc:
        ap.error(str(exc))
    out_dir = Path(args.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    analysis_command = args.analysis_command or args.jvm_command

    if args.pairs:
        manifest_path = Path(args.pairs).resolve()
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        pairs = manifest["pairs"]
    elif args.music_dir:
        if args.engine != "jvm" and not args.exporter:
            ap.error("--music-dir requires --exporter for real shared analysis, scoring, stems and compileFrozen")
        pairs = discover_music_pairs(os.path.expanduser(args.music_dir), out_dir)
        manifest = {"pairs": pairs}
        manifest_path = out_dir / "export-request.json"
        manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    else:
        ap.error("provide --pairs <pairs.json> or --music-dir <dir>")

    if args.engine == "jvm":
        if args.exporter:
            ap.error("--exporter is the legacy batch-export seam; use --analysis-command/--driver-command with --engine jvm")
        common = args.jvm_command
        analysis_command = args.analysis_command or common
        driver_command = args.driver_command or common
        if not analysis_command or not driver_command:
            ap.error("--engine jvm requires --jvm-command or both --analysis-command and --driver-command")
        if args.position_ms < 0 or args.seed < 0:
            ap.error("--position-ms and --seed must be non-negative")
        if not args.scripts_only and not args.binary:
            ap.error("--engine jvm audio rendering requires --binary (or use --scripts-only)")
        requested = recipes_override or requested_recipes(
            ",".join(token for pair in pairs for token in pair.get("recipes", genpair.DEFAULT_RECIPES))
        )
        requested = list(dict.fromkeys(requested))
        export_dir = out_dir / "jvm-pipeline"
        manifest_path, manifest = build_jvm_export(
            pairs, requested, export_dir, analysis_command=analysis_command, driver_command=driver_command,
            binary=args.binary or "", command_runner=run, dry_run=args.jvm_dry_run,
            model_dir=args.model_dir, stems=args.stems, scnet_model=args.scnet_model,
            style_override=args.style, seed=args.seed, position_ms=args.position_ms,
            render_audio=not args.scripts_only, run_mixq=not args.no_mixq,
            input_base=manifest_path.parent,
        )
        if args.jvm_dry_run:
            return 0
        pairs = manifest["pairs"]
        for pair in pairs:
            auto_picks = dict(pair.get("autoPicks", {}))
            for auto_id in AUTO_IDS:
                record = pair.get("exports", {}).get(auto_id)
                if not record:
                    continue
                plan = json.loads(Path(record["plan"]).read_text(encoding="utf-8"))
                auto_picks[auto_id.removeprefix("AUTO-")] = plan["recipeId"]
            pair["autoPicks"] = auto_picks

    if recipes_override:
        for p in pairs:
            p["recipes"] = recipes_override
    if args.exporter:
        request_path = out_dir / "export-request.json"
        request_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        export_dir = out_dir / "canonical-export"
        command = shlex.split(args.exporter) + ["--pairs", str(request_path), "--out", str(export_dir),
                                              "--recipes", ",".join(recipes_override or RECIPE_IDS)]
        run(command, check=True)
        manifest_path = export_dir / "pairs.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        pairs = manifest["pairs"]
        if recipes_override:
            for pair in pairs:
                pair["recipes"] = recipes_override

    items: list[dict] = []
    script_exports = []
    refusals = []
    render_meta_files: dict[str, dict] = {}
    for pair in pairs:
        pair_id = str(pair["pairId"])
        if not pair_id or Path(pair_id).name != pair_id or pair_id in {".", ".."}:
            raise ValueError("pairId must be a single safe filename component")
        synthetic = pair.get("synthetic") is True
        if synthetic:
            for deck in ("out", "in"):
                if not pair.get(f"{deck}F32"):
                    f32, wav = out_dir / f"{pair_id}_{deck}.f32", out_dir / f"{pair_id}_{deck}.wav"
                    ffmpeg_decode_to_f32(pair[f"{deck}Wav"], str(f32), str(wav), command_runner=run)
                    pair[f"{deck}F32"] = str(f32)
        for rid in requested_recipes(pair.get("recipes", genpair.DEFAULT_RECIPES)):
            is_auto = str(rid).upper().startswith("AUTO")
            if synthetic:
                resolved_id, recipe = resolve_recipe(rid, pair.get("autoPicks", genpair.AUTO_PICKS))
                script = genpair.build_script(pair, rid)
                plan = None
            else:
                refusal = pair.get("refusals", {}).get(rid)
                if refusal:
                    refusals.append({"pairId": pair_id, "requestedRecipeId": rid, "refusal": refusal})
                    continue
                script, plan, recipe = exported_script(pair, rid, manifest_path.parent)
                resolved_id = recipe["id"]
            if args.engine == "jvm":
                export_record = pair.get("exports", {}).get(rid)
                if not isinstance(export_record, dict) or not export_record.get("script"):
                    raise ValueError(f"{pair_id}/{rid}: JVM plan export has no script path")
                script_path = Path(export_record["script"])
                if not script_path.is_absolute():
                    script_path = manifest_path.parent / script_path
                script_path = script_path.resolve()
                if not script_path.is_file():
                    raise ValueError(f"{pair_id}/{rid}: JVM script disappeared before render: {script_path}")
                script_display = os.path.relpath(script_path, out_dir)
            else:
                script_path = out_dir / f"{pair_id}_{rid}.script.json"
                with open(script_path, "w", encoding="utf-8") as f:
                    json.dump(script, f, indent=2, allow_nan=False)
                script_display = script_path.name
            script_exports.append({"pairId": pair_id, "requestedRecipeId": rid, "recipeId": resolved_id,
                                   "recipeName": recipe["name"], "description": recipe["description"],
                                   "script": script_display, "provenance": "synthetic-fixture" if synthetic else "canonical-jvm-export"})
            if args.scripts_only:
                continue
            wav_rel = f"{pair_id}_{rid}.wav"
            wav_path = out_dir / wav_rel
            info: dict = {}
            used_binary = render_with_binary(args.binary, str(script_path), str(wav_path), command_runner=run)
            if not used_binary:
                if not synthetic or not args.allow_generic_fallback:
                    raise RuntimeError("A host --binary is required for recipe audio; generic fallback is an explicit synthetic control only")
                info = render_fallback(pair, script, str(wav_path))
            else:
                info = {"renderer": "binary"}
            # Keep the driver's metadata intact. Publish fresh metadata beside
            # the WAV, invalidating prior mix beats before downstream analysis.
            source_meta_path = manifest_path.parent / f"{pair_id}_{rid}.meta.json"
            mix_meta_path = wav_path.with_suffix(".meta.json")
            source_meta = {}
            source_meta_reason = ""
            try:
                source_meta = json.loads(source_meta_path.read_text(encoding="utf-8"))
                if not isinstance(source_meta, dict):
                    raise ValueError("source metadata is not an object")
            except (OSError, ValueError) as exc:
                source_meta = {}
                source_meta_reason = f"source-analysis metadata unavailable: {exc}"
            mix_result = {
                "status": "unavailable", "code": "synthetic-fixture" if synthetic else "analysis-not-completed",
                "reason": "synthetic controls do not supply production measured beats" if synthetic
                          else "rendered-mix inference has not completed",
            }
            metadata_ready = False
            try:
                mix_meta = record_rendered_mix(source_meta, mix_meta_path, mix_result, synthetic=synthetic)
                metadata_ready = True
            except (OSError, ValueError) as exc:
                print(f"WARNING: {wav_rel}: cannot invalidate prior mix metadata: {exc}; mixq will be skipped", file=sys.stderr)
            if used_binary and not synthetic and metadata_ready:
                try:
                    mix_result = analyze_rendered_mix(
                        wav_path, out_dir / f"{pair_id}_{rid}.mix.analysis.json",
                        f"{pair_id}_{rid}.mix", model_dir=args.model_dir,
                        analysis_command=analysis_command, command_runner=run,
                    )
                except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as exc:
                    mix_result = {"status": "unavailable", "code": "analysis-internal-error",
                                  "reason": f"rendered-mix analysis failed: {exc}"}
                if source_meta_reason:
                    mix_result["sourceMetadataReason"] = source_meta_reason
                try:
                    mix_meta = record_rendered_mix(source_meta, mix_meta_path, mix_result, synthetic=False)
                except (OSError, ValueError) as exc:
                    metadata_ready = False
                    print(f"WARNING: {wav_rel}: metadata replacement failed: {exc}; mixq will be skipped", file=sys.stderr)
            info["renderedMixAnalysis"] = mix_result["status"]
            info["renderedMixAnalysisReason"] = mix_result["reason"]
            info["beatTimesMix"] = "measured" if metadata_ready and mix_result["status"] == "measured-nirovi-on-mix" else "unavailable"
            if not synthetic and info["beatTimesMix"] != "measured":
                print(f"WARNING: {wav_rel}: {mix_result['reason']}; no measured beatTimesMix attached", file=sys.stderr)
            if metadata_ready:
                render_meta_files[wav_rel] = mix_meta
            items.append(
                {
                    "id": f"{pair_id}_{rid}",
                    "pairId": pair_id,
                    "pairName": pair.get("name", pair_id),
                    "recipeId": resolved_id,
                    "requestedRecipeId": rid,
                    "wav": wav_rel,
                    "mixMeta": str(mix_meta_path) if metadata_ready else None,
                    "synthetic": synthetic,
                    "exitSec": plan["frozenExitMs"] / 1000 if plan else pair.get("exitSec"),
                    "entrySec": plan["frozenEntryMs"] / 1000 if plan else pair.get("entrySec"),
                    "reason": "SYNTHETIC CONTROL · " + recipe["description"] if synthetic else " · ".join(plan.get("placementReasons", [])),
                    "rulesHit": ["synthetic-unverified-placement"] if synthetic else plan.get("placementReasons", []),
                    "autoPick": is_auto,
                    "level": str(rid).replace("AUTO-", "") if is_auto else recipe["level"],
                    "mixqStatus": "UNVERIFIED generic synthetic control" if info.get("renderer") == "python-fallback" else "skipped",
                    "mixqFails": [],
                    "renderer": info,
                }
            )
            print(f"rendered {wav_rel} via {info.get('renderer')}")

    (out_dir / "scripts.json").write_text(json.dumps({"scripts": script_exports, "refusals": refusals}, indent=2), encoding="utf-8")
    if args.scripts_only:
        print(f"exported {len(script_exports)} scripts; {len(refusals)} typed refusals; no audio rendered")
        return 0
    write_json_atomic(out_dir / "meta.json", {"files": render_meta_files})

    mixq_payload = {"results": []}
    if not args.no_mixq:
        for it in items:
            if it["renderer"].get("renderer") == "python-fallback":
                it["mixqStatus"] = "UNVERIFIED generic synthetic control"
                continue
            # Use only this run's successfully replaced WAV-sibling metadata.
            meta_path = it.get("mixMeta")
            if not meta_path:
                it["mixqStatus"] = "UNVERIFIED mix metadata unavailable"
                continue
            cmd = [sys.executable, MIXQ, "check", str(out_dir / it["wav"])]
            if meta_path:
                cmd += ["--meta", meta_path]
            with tempfile.TemporaryDirectory(prefix=".mixq-", dir=out_dir) as temporary:
                result_path = Path(temporary) / "result.json"
                proc = run(cmd + ["-o", str(result_path)], capture_output=True, text=True)
                if proc.returncode not in (0, 1) or not result_path.is_file():
                    it["mixqStatus"] = "FAIL mixq execution"
                    it["mixqFails"] = [proc.stderr[-1000:].strip() or f"mixq exited {proc.returncode} without a result"]
                    continue
                payload = json.loads(result_path.read_text(encoding="utf-8"))
            entry = (payload.get("results") or [{}])[0]
            mixq_payload["results"].append(entry)
            gates = entry.get("gates") or {}
            fails = [k for k, v in gates.items() if not v]
            if it["synthetic"]:
                it["mixqStatus"] = "UNVERIFIED synthetic fixture diagnostics"
                entry["auditionEvidence"] = "synthetic-fixture; no production quality acceptance"
            elif it["renderer"].get("beatTimesMix") != "measured":
                it["mixqStatus"] = "UNVERIFIED rendered-mix inference unavailable"
                it["mixqFails"] = list(dict.fromkeys(fails + ["analysisEvidence"]))
            elif proc.returncode == 0 and entry.get("passOverall"):
                it["mixqStatus"] = "PASS"
            else:
                it["mixqStatus"] = "FAIL"
                it["mixqFails"] = fails
            print(f"mixq {entry.get('basename')}: {it['mixqStatus']} fails={fails}")
        with open(out_dir / "mixq-results.json", "w", encoding="utf-8") as f:
            json.dump(mixq_payload, f, indent=2)

    with open(out_dir / "index.md", "w", encoding="utf-8") as f:
        f.write(render_index_md(items, args.title))
    with open(out_dir / "index.html", "w", encoding="utf-8") as f:
        f.write(render_rater_html(items, args.title))
    print(f"wrote {out_dir / 'index.html'} and {out_dir / 'index.md'} ({len(items)} renders)")

    if args.upload:
        rclone = which("rclone")
        if not rclone:
            print("rclone not found in PATH; cannot upload", file=sys.stderr)
            return 2
        # Verify rclone safely (version check, no side effects) before copying.
        ver = run([rclone, "version"], capture_output=True, text=True)
        if ver.returncode != 0:
            print(f"rclone unhealthy: {ver.stderr.strip()}", file=sys.stderr)
            return 2
        print(f"rclone OK: {ver.stdout.splitlines()[0] if ver.stdout else rclone}")
        zip_path = out_dir.parent / f"{out_dir.name}.zip"
        zip_cmd = run(
            ["python3", "-m", "zipfile", "-c", str(zip_path), str(out_dir)],
            capture_output=True, text=True,
        )
        if zip_cmd.returncode != 0:
            print(f"zip failed: {zip_cmd.stderr.strip()}", file=sys.stderr)
            return 2
        copy_cmd = [rclone, "copyto", str(zip_path), args.rclone_target]
        print(" ".join(copy_cmd))
        if not args.dry_run:
            proc = run(copy_cmd, capture_output=True, text=True)
            print(proc.stdout[-2000:] if proc.stdout else "")
            if proc.returncode != 0:
                print(proc.stderr[-2000:], file=sys.stderr)
                return 1
            print(f"uploaded {zip_path} -> {args.rclone_target}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
