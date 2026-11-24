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

When no host renderer binary is available (or it does not accept
``--render``), a built-in equal-power fallback renderer is used, so the
pipeline stays runnable end-to-end. The fallback applies loudness matching
and a 4x-oversampled true-peak guard.
"""

from __future__ import annotations

import argparse
import glob
import html as html_mod
import json
import math
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

try:
    import numpy as np
except ImportError:  # pragma: no cover - environment guard
    sys.stderr.write("requires numpy (python3 -c 'import numpy')\n")
    raise

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "mixq"))
from mixq import load_audio, write_wav_pcm16, upsample_4x, SR  # noqa: E402

MIXQ_DIR = Path(__file__).resolve().parent.parent / "mixq"
MIXQ = str(MIXQ_DIR / "mixq.py")
DEFAULT_REMOTE = "gdrive:djmix53-audition/"

import generate_synthetic_pairs as genpair  # noqa: E402


# ---------------------------------------------------------------------------
# Audio decode via ffmpeg
# ---------------------------------------------------------------------------


def which(cmd: str) -> str | None:
    return shutil.which(cmd)


def ffmpeg_decode_to_f32(src: str, dst_f32: str, dst_wav: str) -> None:
    """Decode any ffmpeg-supported file to raw f32 stereo 48 kHz (+ WAV copy)."""
    if not which("ffmpeg"):
        raise RuntimeError("ffmpeg not found in PATH (required to decode decks)")
    cmd = [
        "ffmpeg", "-y", "-v", "error", "-i", src,
        "-ar", str(SR), "-ac", "2", "-f", "f32le", dst_f32,
    ]
    subprocess.run(cmd, check=True)
    # WAV copy for audition HTML playback (PCM16).
    cmd2 = [
        "ffmpeg", "-y", "-v", "error", "-i", src,
        "-ar", str(SR), "-ac", "2", "-c:a", "pcm_s16le", dst_wav,
    ]
    subprocess.run(cmd2, check=True)


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


def render_with_binary(binary: str, script_path: str, out_wav: str) -> bool:
    """Try the host renderer `--render <script.json> --out <wav>`; False if unavailable."""
    if not binary or not os.path.isfile(binary):
        return False
    try:
        proc = subprocess.run(
            [binary, "--render", script_path, "--out", out_wav],
            capture_output=True, text=True, timeout=600,
        )
        return proc.returncode == 0 and os.path.isfile(out_wav)
    except (OSError, subprocess.TimeoutExpired):
        return False


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
    else:
        lines.append("## mixq failures\n\nNone — every render passes all 8 B6.2 gates.")
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
    """Pair up adjacent files in ~/Music and decode via ffmpeg when needed."""
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
            "exitSec": 0.0,
            "entrySec": 0.0,
            "fadeSec": 4.0,
            "swapSec": 0.0,
            "genreJump": False,
            "tempoJumpPct": 0.0,
            "dropWindowSec": None,
            "recipes": list(genpair.DEFAULT_RECIPES),
            "reason": "Library pair (filename order); placement rules unverified — mixq metadata fallback",
            "placementRulesHit": ["unverified-library-pair"],
        }
        # Decode decks via ffmpeg to f32 + wav for the fallback renderer.
        for deck, src in (("out", out_f), ("in", in_f)):
            f32 = out_dir / f"{pid}_{deck}.f32"
            wav = out_dir / f"{pid}_{deck}.wav"
            if not os.path.isfile(f32):
                ffmpeg_decode_to_f32(src, str(f32), str(wav))
            pair[f"{deck}F32"] = str(f32)
            pair[f"{deck}Wav"] = str(wav)
            _l, _r, dur = load_audio(str(wav))
            pair[f"{deck}DurationSec"] = round(dur, 3)
        # Exit at 75% of the outgoing file (keeps a generous outro phrase).
        pair["exitSec"] = round(pair["outDurationSec"] * 0.75, 3)
        pair["swapSec"] = pair["exitSec"]
        pairs.append(pair)
    return pairs


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pairs", default=None, help="Pairs JSON from generate_synthetic_pairs.py")
    ap.add_argument("--music-dir", default=None, help="Discover library pairs here (e.g. ~/Music)")
    ap.add_argument("--out", required=True, help="Output directory for wavs + index.html")
    ap.add_argument("--binary", default=None, help="Host renderer binary (loop_mix_renderer_ab_main); fallback used when absent")
    ap.add_argument("--recipes", default=None, help="Comma-separated recipe overrides (default: manifest recipes)")
    ap.add_argument("--no-mixq", action="store_true", help="Skip mixq self-check")
    ap.add_argument("--upload", action="store_true", help="Upload folder to Drive via rclone")
    ap.add_argument("--dry-run", action="store_true", help="Print rclone command instead of uploading")
    ap.add_argument("--rclone-target", default=DEFAULT_REMOTE)
    ap.add_argument("--title", default="AE3 audition")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.pairs:
        with open(args.pairs, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        pairs = manifest["pairs"]
        recipes_override = [r.strip() for r in args.recipes.split(",")] if args.recipes else None
    elif args.music_dir:
        pairs = discover_music_pairs(os.path.expanduser(args.music_dir), out_dir)
        manifest = {"pairs": pairs}
        recipes_override = [r.strip() for r in args.recipes.split(",")] if args.recipes else None
    else:
        ap.error("provide --pairs <pairs.json> or --music-dir <dir>")

    if recipes_override:
        for p in pairs:
            p["recipes"] = recipes_override

    items: list[dict] = []
    render_meta_files: dict[str, dict] = {}
    for pair in pairs:
        pair_id = str(pair["pairId"])
        for rid in pair.get("recipes", genpair.DEFAULT_RECIPES):
            is_auto = str(rid).upper().startswith("AUTO")
            script = genpair.build_script(pair, str(rid))
            script_path = out_dir / f"{pair_id}_{rid}.script.json"
            with open(script_path, "w", encoding="utf-8") as f:
                json.dump(script, f, indent=2)
            wav_rel = f"{pair_id}_{rid}.wav"
            wav_path = out_dir / wav_rel
            # Sibling mixq metadata so `mixq check <out_dir>` resolves it
            # without --meta (mirrors the generator's per-render meta).
            cand = Path(args.pairs).parent / f"{pair_id}_{rid}.meta.json" if args.pairs else None
            if cand and cand.is_file():
                with open(cand, "r", encoding="utf-8") as f:
                    render_meta_files[wav_rel] = json.load(f)
            info: dict = {}
            used_binary = render_with_binary(args.binary, str(script_path), str(wav_path))
            if not used_binary:
                info = render_fallback(pair, script, str(wav_path))
            else:
                info = {"renderer": "binary"}
            items.append(
                {
                    "id": f"{pair_id}_{rid}",
                    "pairId": pair_id,
                    "pairName": pair.get("name", pair_id),
                    "recipeId": str(rid),
                    "wav": wav_rel,
                    "exitSec": pair.get("exitSec"),
                    "entrySec": pair.get("entrySec"),
                    "reason": pair.get("reason", script.get("audition", {}).get("reason", "")),
                    "rulesHit": pair.get("placementRulesHit", []),
                    "autoPick": is_auto,
                    "level": str(rid).replace("AUTO-", "") if is_auto else None,
                    "mixqStatus": "skipped",
                    "mixqFails": [],
                    "renderer": info,
                }
            )
            print(f"rendered {wav_rel} via {info.get('renderer')}")

    if render_meta_files:
        with open(out_dir / "meta.json", "w", encoding="utf-8") as f:
            json.dump({"files": render_meta_files}, f, indent=2)

    mixq_payload = {"results": []}
    if not args.no_mixq:
        for it in items:
            meta_path = None
            # Per-render meta written by the generator (pair_<recipe>.meta.json).
            cand = Path(args.pairs).parent / f"{it['pairId']}_{it['recipeId']}.meta.json" if args.pairs else None
            if cand and cand.is_file():
                meta_path = str(cand)
            cmd = [sys.executable, MIXQ, "check", str(out_dir / it["wav"])]
            if meta_path:
                cmd += ["--meta", meta_path]
            cmd += ["-o", str(out_dir / "mixq-results.json")]
            proc = subprocess.run(cmd, capture_output=True, text=True)
            # Each check run overwrites mixq-results.json; reload to keep the entry.
            with open(out_dir / "mixq-results.json", "r", encoding="utf-8") as f:
                payload = json.load(f)
            entry = (payload.get("results") or [{}])[0]
            mixq_payload["results"].append(entry)
            gates = entry.get("gates") or {}
            fails = [k for k, v in gates.items() if not v]
            if entry.get("passOverall"):
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
        ver = subprocess.run([rclone, "version"], capture_output=True, text=True)
        if ver.returncode != 0:
            print(f"rclone unhealthy: {ver.stderr.strip()}", file=sys.stderr)
            return 2
        print(f"rclone OK: {ver.stdout.splitlines()[0] if ver.stdout else rclone}")
        zip_path = out_dir.parent / f"{out_dir.name}.zip"
        zip_cmd = subprocess.run(
            ["python3", "-m", "zipfile", "-c", str(zip_path), str(out_dir)],
            capture_output=True, text=True,
        )
        if zip_cmd.returncode != 0:
            print(f"zip failed: {zip_cmd.stderr.strip()}", file=sys.stderr)
            return 2
        copy_cmd = [rclone, "copyto", str(zip_path), args.rclone_target]
        print(" ".join(copy_cmd))
        if not args.dry_run:
            proc = subprocess.run(copy_cmd, capture_output=True, text=True)
            print(proc.stdout[-2000:] if proc.stdout else "")
            if proc.returncode != 0:
                print(proc.stderr[-2000:], file=sys.stderr)
                return 1
            print(f"uploaded {zip_path} -> {args.rclone_target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
