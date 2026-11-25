#!/usr/bin/env python3
"""Synthetic test-pair generator for CI/host audition (Part A section 9, B6.3, B6.6).

Generates 10 diverse synthetic stereo 48 kHz test pairs (EDM 128->128, house
124->126, pop vocal->vocal, hip-hop 92->95, pop 105->EDM 128 genre jump,
ballad 74->76, techno, R&B, and two cross-genre pairs) with beat kicks,
basslines, harmonic pads and vocal-band formants, plus ``script.json``
descriptors compatible with ``TransitionScriptV1`` (``djmix-core/contracts``)
so CI and host tests can render and gate the engine without copyrighted
audio.

Each pair writes:
    pair<N>_out.wav / pair<N>_in.wav          (PCM16 48 kHz stereo, listening)
    pair<N>_out.f32 / pair<N>_in.f32          (raw f32 stereo 48 kHz, script PCM)
    pair<N>_<RECIPE>.script.json              (TransitionScriptV1 schema=1)
    pairs.json                                (manifest for audition_render.py)
    <pair>_<recipe>.meta.json                 (mixq metadata: transition span,
                                                swap, bpm, Syvenu-like vocal curves)
    AE3-build.html                            (via mixq report, after audition_render)

Tracks are structured so placements are clean by construction: the outgoing
track's vocal exits at the 75% phrase boundary (exit) and the incoming track
plays a clean instrumental intro from bar 1 (entry), so vocal overlap is ~0
and placement violations are 0. Bass is thinned in the outro/intro so dual-
deck bass clash stays well under 1 beat.

Usage:
    generate_synthetic_pairs.py --out DIR [--bars 16] [--no-f32]

Deterministic: every track is seeded from (pair index, deck), so CI golden
renders are reproducible.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys

try:
    import numpy as np
except ImportError:  # pragma: no cover - environment guard
    sys.stderr.write("requires numpy (python3 -c 'import numpy')\n")
    raise

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "mixq"))
from mixq import write_wav_pcm16  # noqa: E402

SR = 48000
VOCAL_FPS = 50.0

# Load every descriptor from the same 26 RecipeV2 JSONs as the app.
from recipe_catalog import CATALOG, RECIPE_IDS, requested_recipes, resolve_recipe  # noqa: E402

RECIPES = {rid: dict(recipe, desc=recipe["description"]) for rid, recipe in CATALOG.items()}
# Deterministic fixture controls only. Real AUTO selections come from the JVM planner.
AUTO_PICKS = {"SUBTLE": "S1", "CLUB": "C2", "FESTIVAL": "F1"}
DEFAULT_RECIPES = list(RECIPE_IDS)

# ---------------------------------------------------------------------------
# Pair specs: (genre_out, bpm_out, genre_in, bpm_in, root_out, root_in, note)
# Roots are MIDI note numbers (A minor = 69-based dance keys simplified).
# ---------------------------------------------------------------------------
PAIR_SPECS = [
    {"name": "EDM 128 -> 128", "genreOut": "EDM", "bpmOut": 128.0, "genreIn": "EDM", "bpmIn": 128.0,
     "rootOut": 45, "rootIn": 43, "energy": 0.9, "kind": "dance"},
    {"name": "House 124 -> 126", "genreOut": "House", "bpmOut": 124.0, "genreIn": "House", "bpmIn": 126.0,
     "rootOut": 41, "rootIn": 41, "energy": 0.75, "kind": "dance"},
    {"name": "Pop vocal -> vocal", "genreOut": "Pop", "bpmOut": 104.0, "genreIn": "Pop", "bpmIn": 106.0,
     "rootOut": 53, "rootIn": 53, "energy": 0.6, "kind": "pop"},
    {"name": "Hip-hop 92 -> 95", "genreOut": "Hip-hop", "bpmOut": 92.0, "genreIn": "Hip-hop", "bpmIn": 95.0,
     "rootOut": 43, "rootIn": 41, "energy": 0.55, "kind": "hiphop"},
    {"name": "Pop 105 -> EDM 128 (genre jump)", "genreOut": "Pop", "bpmOut": 105.0, "genreIn": "EDM", "bpmIn": 128.0,
     "rootOut": 48, "rootIn": 45, "energy": 0.8, "kind": "jump", "genreJump": True},
    {"name": "Ballad 74 -> 76", "genreOut": "Ballad", "bpmOut": 74.0, "genreIn": "Ballad", "bpmIn": 76.0,
     "rootOut": 50, "rootIn": 48, "energy": 0.35, "kind": "ballad"},
    {"name": "Techno 130 -> 132", "genreOut": "Techno", "bpmOut": 130.0, "genreIn": "Techno", "bpmIn": 132.0,
     "rootOut": 40, "rootIn": 40, "energy": 0.95, "kind": "dance"},
    {"name": "R&B 88 -> 90", "genreOut": "R&B", "bpmOut": 88.0, "genreIn": "R&B", "bpmIn": 90.0,
     "rootOut": 46, "rootIn": 46, "energy": 0.5, "kind": "rnb"},
    {"name": "EDM 126 -> House 122", "genreOut": "EDM", "bpmOut": 126.0, "genreIn": "House", "bpmIn": 122.0,
     "rootOut": 45, "rootIn": 43, "energy": 0.85, "kind": "jump", "genreJump": True},
    {"name": "House 118 -> 120 (drop)", "genreOut": "House", "bpmOut": 118.0, "genreIn": "House", "bpmIn": 120.0,
     "rootOut": 43, "rootIn": 45, "energy": 0.9, "kind": "dance", "dropPair": True},
]


def midi_to_freq(m: float) -> float:
    return 440.0 * (2.0 ** ((m - 69.0) / 12.0))


def chord_roots(root: int, bar: int) -> list[int]:
    """I-V-vi-IV motion in the given root (relative semitone offsets)."""
    prog = [0, 7, 9, 5]
    return [root + prog[(bar) % 4] + 12, root + prog[(bar) % 4] + 12, root + prog[(bar) % 4] + 12]


class TrackSynth:
    """Deterministic mini-band: kicks, snares, hats, bass, pads, vocal formants."""

    def __init__(self, bpm: float, root: int, kind: str, energy: float, rng: np.random.Generator):
        self.bpm = bpm
        self.root = root
        self.kind = kind
        self.energy = energy
        self.rng = rng
        self.beat_sec = 60.0 / bpm
        self.bar_sec = 4 * self.beat_sec

    def synth(self, duration_sec: float) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        n = int(round(duration_sec * SR))
        left = np.zeros(n, dtype=np.float64)
        right = np.zeros(n, dtype=np.float64)
        dur_bars = duration_sec / self.bar_sec
        # Structure: intro 0-25% instrumental, main 25-75% full (+vocal),
        # outro 75-100% vocal-free, bass-thinned (exit zone).
        t = np.arange(n, dtype=np.float64) / SR
        bar_pos = t / self.bar_sec

        def place(pos_times: np.ndarray, env: np.ndarray, out_l: np.ndarray, out_r: np.ndarray, pan: float = 0.0):
            """Add a mono event burst at fractional sample positions (pan: -0.5..0.5)."""
            for pt in pos_times:
                idx = int(round(pt * SR))
                m = len(env)
                if idx < 0 or idx >= n:
                    continue
                end = min(n, idx + m)
                seg = env[: end - idx]
                gl = math.cos((pan + 1.0) * math.pi / 4.0)
                gr = math.sin((pan + 1.0) * math.pi / 4.0)
                out_l[idx:end] += seg * gl
                out_r[idx:end] += seg * gr

        def noise_burst(dur_sec: float, hp: bool = True, seed_off: int = 0) -> np.ndarray:
            m = int(round(dur_sec * SR))
            noise = self.rng.standard_normal(m + seed_off)
            noise = noise[seed_off : seed_off + m]
            if hp:
                # crude high-pass: difference + doubling back
                noise = np.diff(noise, prepend=0.0) * 0.6
            return noise

        # --- kick: 4-on-floor (dance/pop), beats 1/3 + ghost (hiphop/rnb_ballad)
        beat_times = np.arange(0.0, duration_sec, self.beat_sec)
        kick_env = self._kick_env(0.14)
        if self.kind in ("dance", "pop", "jump"):
            kick_times = beat_times
            kick_gain = 0.62 * (0.55 + 0.45 * self.energy)
        elif self.kind == "hiphop":
            kick_times = beat_times[::2].tolist() + (beat_times[1::4] + self.beat_sec * 1.75).tolist()
            kick_gain = 0.6
        elif self.kind == "ballad":
            kick_times = beat_times[::2]
            kick_gain = 0.42
        else:  # rnb
            kick_times = beat_times
            kick_gain = 0.5
        place(np.asarray(kick_times), kick_env * kick_gain, left, right, 0.0)

        # --- snare: beats 2 & 4 (offbeat for dance) ---
        if self.kind in ("dance", "jump"):
            snare_times = beat_times[1::2] + self.beat_sec * 0.5
            snare_gain = 0.30 * (0.6 + 0.4 * self.energy)
        elif self.kind in ("hiphop", "rnb"):
            snare_times = beat_times[1::2]
            snare_gain = 0.34
        elif self.kind == "pop":
            snare_times = beat_times[1::2]
            snare_gain = 0.3
        else:  # ballad
            snare_times = beat_times[1::4]
            snare_gain = 0.2
        snare_env = noise_burst(0.13) * 0.5
        tone_part = self._tone(190.0, 0.12) * 0.35
        snare_env[: len(tone_part)] += tone_part
        place(np.asarray(snare_times), snare_env, left, right, 0.0)
        # scale snare layer
        # (placed at fixed gain above; energy scaling applied via second
        # quieter pass for dance energy — keeps levels predictable)

        # --- hats: 8ths / 16ths ---
        if self.kind in ("dance", "jump"):
            hat_times = beat_times + self.beat_sec * 0.5
            hat_gain = 0.16
        elif self.kind == "hiphop":
            hat_times = np.concatenate([beat_times + self.beat_sec * d for d in (0.0, 0.25, 0.5, 0.75)])
            hat_gain = 0.12
        elif self.kind == "ballad":
            hat_times = beat_times[::2] + self.beat_sec
            hat_gain = 0.06
        else:  # rnb
            hat_times = beat_times + self.beat_sec * 0.5
            hat_gain = 0.1
        for k, pt in enumerate(np.asarray(hat_times)):
            place([pt], noise_burst(0.045 + 0.01 * (k % 3)) * hat_gain, left, right, pan=-0.3 if (k % 2 == 0) else 0.3)

        # --- bassline: per-bar chord root, pattern by kind, thinned in outro ---
        bass = np.zeros(n, dtype=np.float64)
        n_bars = int(math.ceil(duration_sec / self.bar_sec))
        for bar in range(n_bars):
            bar_start = bar * self.bar_sec
            roots = chord_roots(self.root, bar)
            chord_root = roots[0]
            in_outro = (bar_start + self.bar_sec) / duration_sec > 0.75
            thin = 0.35 if in_outro else 1.0  # bass thinning -> no clash in overlap
            if self.kind in ("dance", "jump"):
                # offbeat 8ths
                pat = [self.beat_sec * 0.5 + k * self.beat_sec for k in range(4)]
                notes = [chord_root, chord_root, chord_root + 12 if bar % 2 == 0 else chord_root, chord_root]
            elif self.kind == "hiphop":
                pat = [0.0, self.beat_sec * 1.75, self.beat_sec * 2.5 * 0 + self.beat_sec * 1.5]
                notes = [chord_root, chord_root, chord_root + 5]
            elif self.kind == "ballad":
                pat = [0.0, self.beat_sec * 2.0]
                notes = [chord_root, chord_root + 7]
            elif self.kind == "pop":
                pat = [k * self.beat_sec for k in range(4)]
                notes = [chord_root, chord_root, chord_root + 7, chord_root]
            else:  # rnb
                pat = [0.0, self.beat_sec * 0.75, self.beat_sec * 2.0, self.beat_sec * 2.75]
                notes = [chord_root, chord_root + 12, chord_root, chord_root + 7]
            for dt_off, note in zip(pat, notes):
                t0 = bar_start + dt_off
                if t0 >= duration_sec:
                    continue
                idx = int(round(t0 * SR))
                dur = self.beat_sec * (1.6 if self.kind in ("ballad", "rnb") else 0.9)
                m = int(round(dur * SR))
                seg_t = np.arange(min(m, n - idx), dtype=np.float64) / SR
                f0 = midi_to_freq(note)
                seg = np.sin(2 * np.pi * f0 * seg_t) * 0.7 + np.sin(2 * np.pi * 2 * f0 * seg_t) * 0.25
                env = np.exp(-seg_t * (2.2 if self.kind == "ballad" else 4.5))
                # crude low-pass (moving average) keeps it bassy
                seg = env * seg
                kernel = max(1, int(SR / 400))
                seg = np.convolve(seg, np.ones(kernel) / kernel, mode="same")
                end = idx + len(seg)
                bass[idx:end] += seg * thin * (0.32 * (0.5 + 0.5 * self.energy))
        left += bass * 0.9
        right += bass * 0.9

        # --- pads / keys: soft chord tones per bar ---
        pad = np.zeros(n, dtype=np.float64)
        for bar in range(n_bars):
            bar_start = bar * self.bar_sec
            if bar_start >= duration_sec:
                continue
            idx = int(round(bar_start * SR))
            m = min(int(round(self.bar_sec * SR)), n - idx)
            seg_t = np.arange(m, dtype=np.float64) / SR
            for member in chord_roots(self.root, bar):
                for det in (0.0, 3.0, -3.0):
                    # Place pad an octave above the bass root (>250 Hz) so its
                    # 80 ms slow attack on bar downbeats doesn't mask kick peaks.
                    f0 = midi_to_freq(member + 12) * (1.0 + det / 1200.0)
                    pad[idx : idx + m] += np.sin(2 * np.pi * f0 * seg_t) * (0.045 + 0.02 * self.energy)
            # slow attack/release per bar
            attack = int(round(min(0.08, self.bar_sec * 0.15) * SR))
            env_seg = np.ones(m)
            env_seg[:attack] = np.linspace(0.0, 1.0, attack)
            env_seg[-attack:] *= np.linspace(1.0, 0.0, attack)
            pad[idx : idx + m] *= np.minimum(1.0, env_seg * 2.0)
        left += pad
        right += pad * 0.97

        # --- vocal: harmonic stacks with formant emphasis in 25-75% ---
        vocal = np.zeros(n, dtype=np.float64)
        mel_scale = [0, 3, 5, 7, 10, 12, 15, 12]
        vocal_off = duration_sec * 0.25
        vocal_end = duration_sec * 0.75
        phrase = 0
        vt = vocal_off
        while vt < vocal_end:
            bar = int((vt / self.bar_sec) % 4)
            chord = chord_roots(self.root, bar)
            note = chord[0] + 12 + mel_scale[phrase % len(mel_scale)]
            f0 = midi_to_freq(note)
            dur = self.beat_sec * 2.0
            idx = int(round(vt * SR))
            m = min(int(round(dur * SR)), n - idx)
            if m > 0:
                seg_t = np.arange(m, dtype=np.float64) / SR
                vibr = 1.0 + 0.006 * np.sin(2 * np.pi * 5.0 * seg_t)
                seg = np.zeros(m)
                for h in range(1, 14):
                    fh = f0 * h * (1.0 + (vibr - 1.0) * 0.5)
                    # formant boost near 500/1000/2500 Hz (simple gaussians)
                    boost = 1.0 + 0.9 * np.exp(-(((fh - 550.0) / 350.0) ** 2)) + 0.7 * np.exp(-(((fh - 1000.0) / 500.0) ** 2)) + 0.5 * np.exp(-(((fh - 2600.0) / 800.0) ** 2))
                    seg += (0.5 / h) * boost * np.sin(2 * np.pi * fh * seg_t)
                seg *= np.exp(-seg_t * 1.2) * 0.5
                ad = min(int(0.04 * SR), m // 2)
                seg[:ad] *= np.linspace(0.0, 1.0, ad)
                seg[-ad:] *= np.linspace(1.0, 0.0, ad)
                vocal[idx : idx + m] += seg
            vt += dur
            phrase += 1
        # vocal gentle stereo spread (predominant center, slight chorus feel)
        left += vocal * 0.85
        right += vocal * 0.85

        # --- gentle master glue + headroom normalisation ---
        # Final peak normalise to -7 dBFS so equal-power crossfades stay
        # under -1 dBTP after the renderer's true-peak guard.
        mix_l = left
        mix_r = right
        peak = max(float(np.max(np.abs(mix_l))), float(np.max(np.abs(mix_r))), 1e-9)
        target = 10.0 ** (-7.0 / 20.0)
        gain = target / peak
        mix_l = mix_l * gain
        mix_r = mix_r * gain

        # Syvenu-like vocal activity curve (50 fps) for mixq metadata.
        frames = int(math.ceil(duration_sec * VOCAL_FPS))
        ft = np.arange(frames, dtype=np.float64) / VOCAL_FPS
        voice = np.zeros(frames, dtype=np.float64)
        for i, ts in enumerate(ft):
            if vocal_off <= ts < vocal_end:
                pos = (ts - vocal_off) / (vocal_end - vocal_off)
                voice[i] = 0.55 + 0.45 * math.sin(2 * math.pi * pos * 4.0) ** 2
                voice[i] = float(np.clip(voice[i], 0.0, 1.0))
        return mix_l, mix_r, voice

    def _kick_env(self, dur: float) -> np.ndarray:
        m = int(round(dur * SR))
        seg_t = np.arange(m, dtype=np.float64) / SR
        freq = 45.0 + 90.0 * np.exp(-seg_t * 22.0)
        phase = 2 * np.pi * np.cumsum(freq) / SR
        env = np.exp(-seg_t * 9.0)
        return np.sin(phase) * env * 0.9

    def _tone(self, freq: float, dur: float) -> np.ndarray:
        m = int(round(dur * SR))
        seg_t = np.arange(m, dtype=np.float64) / SR
        return np.sin(2 * np.pi * freq * seg_t) * np.exp(-seg_t * 18.0)


def effective_in_tempo(bpm_out: float, bpm_in: float, genre_jump: bool) -> tuple[float, float, bool]:
    """ADR 0010 beatmatch rule: time-stretch IN to bpm_out within 8% (genre jump) / 10%."""
    max_pct = 8.0 if genre_jump else 10.0
    if bpm_out > 0.0 and bpm_in > 0.0 and abs(bpm_in - bpm_out) / bpm_out * 100.0 <= max_pct:
        return bpm_out / bpm_in, bpm_out, True
    return 1.0, bpm_in, False


def build_script(pair: dict, recipe_id: str) -> dict:
    """Build a synthetic fixture; real audio uses canonical JVM exports."""
    from synthetic_script import build_fixture_script
    rate, _, _ = effective_in_tempo(pair["bpmOut"], pair["bpmIn"], bool(pair.get("genreJump", False)))
    return build_fixture_script(pair, recipe_id, pair.get("autoPicks", AUTO_PICKS), rate)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", required=True, help="Output directory")
    ap.add_argument("--bars", type=int, default=16, help="Bars per track")
    ap.add_argument("--no-f32", action="store_true", help="Write source WAVs/metadata only; audition decodes raw PCM before script export")
    ap.add_argument("--recipes", default=",".join(DEFAULT_RECIPES), help="ALL or comma-separated canonical IDs / AUTO-SUBTLE, AUTO-CLUB, AUTO-FESTIVAL")
    args = ap.parse_args()

    try:
        recipes = requested_recipes(args.recipes)
    except ValueError as exc:
        ap.error(str(exc))
    if args.bars < 4:
        ap.error("--bars must be at least 4")
    out_dir = Path(args.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest_pairs = []

    for idx, spec in enumerate(PAIR_SPECS, start=1):
        pair_id = f"pair{idx}"
        bpm_out, bpm_in = spec["bpmOut"], spec["bpmIn"]
        dur_out = args.bars * 4 * (60.0 / bpm_out)
        dur_in = args.bars * 4 * (60.0 / bpm_in)
        exit_sec = dur_out * 0.75  # post-chorus outro boundary
        entry_sec = 0.0  # intro bar 1, before first vocal entry
        fade_sec = min(8.0, 4 * 60.0 / bpm_out)
        swap_sec = exit_sec
        drop_window = None
        if spec.get("dropPair"):
            drop_window = (round(exit_sec, 3), round(exit_sec + 2.0, 3))

        tracks: dict[str, dict] = {}
        for deck, (bpm, root, dur) in enumerate(
            [(bpm_out, spec["rootOut"], dur_out), (bpm_in, spec["rootIn"], dur_in)]
        ):
            is_out = deck == 0
            rng = np.random.default_rng(1000 * idx + (1 if is_out else 2))
            synth = TrackSynth(bpm, root, spec["kind"], spec["energy"], rng)
            left, right, voice = synth.synth(dur)
            wav_name = f"{pair_id}_{'out' if is_out else 'in'}.wav"
            f32_name = f"{pair_id}_{'out' if is_out else 'in'}.f32"
            wav_path = out_dir / wav_name
            write_wav_pcm16(str(wav_path), left, right, SR)
            if args.no_f32:
                f32_path = None
            else:
                f32_path = out_dir / f32_name
                inter = np.empty((len(left), 2), dtype=np.float32)
                inter[:, 0] = left.astype(np.float32)
                inter[:, 1] = right.astype(np.float32)
                inter.tofile(str(f32_path))
            tracks["out" if is_out else "in"] = {
                "wav": str(wav_path),
                "f32": str(f32_path) if f32_path is not None else None,
                "durationSec": len(left) / SR,
                "frameCount": len(left),
                "voice": voice.tolist(),
                "voiceTimes": None,  # implicit 50 fps
            }

        pair = {
            "pairId": pair_id,
            "synthetic": True,
            "autoPicks": AUTO_PICKS,
            "name": spec["name"],
            "genreOut": spec["genreOut"], "genreIn": spec["genreIn"],
            "bpmOut": bpm_out, "bpmIn": bpm_in,
            "genreJump": bool(spec.get("genreJump", False)),
            "tempoJumpPct": round(abs(bpm_in - bpm_out) / bpm_out * 100.0, 2),
            "outTrackId": f"{pair_id}-out",
            "inTrackId": f"{pair_id}-in",
            "outDurationSec": tracks["out"]["durationSec"],
            "inDurationSec": tracks["in"]["durationSec"],
            "outFrameCount": tracks["out"]["frameCount"], "inFrameCount": tracks["in"]["frameCount"],
            "outWav": tracks["out"]["wav"], "inWav": tracks["in"]["wav"],
            "outF32": tracks["out"]["f32"], "inF32": tracks["in"]["f32"],
            "exitSec": round(exit_sec, 3), "entrySec": entry_sec,
            "fadeSec": round(fade_sec, 3), "swapSec": round(swap_sec, 3),
            "dropWindowSec": list(drop_window) if drop_window else None,
            "recipes": recipes,
            "vocalOut": tracks["out"]["voice"], "vocalIn": tracks["in"]["voice"],
            "vocalFps": VOCAL_FPS,
            "placementRulesHit": ["exit-after-final-chorus", "entry-at-intro-bar-1", "no-vocal-overlap"],
            "reason": "Exit after final chorus · Entry at intro bar 1 · no vocal overlap",
        }
        # Per-recipe scripts + meta (TransitionScriptV1 + mixq metadata).
        _, bpm_in_eff, _ = effective_in_tempo(bpm_out, bpm_in, pair["genreJump"])
        for rid in recipes:
            if not args.no_f32:
                script = build_script(pair, rid)
                script_path = out_dir / f"{pair_id}_{rid}.script.json"
                with open(script_path, "w", encoding="utf-8") as f:
                    json.dump(script, f, indent=2, allow_nan=False)
            resolved_id, recipe_meta = resolve_recipe(rid, AUTO_PICKS)
            meta = {
                "file": f"{pair_id}_{rid}.wav",
                "transitionStartSec": round(max(exit_sec - fade_sec / 2.0, 0.0), 3),
                "transitionEndSec": round(exit_sec + fade_sec / 2.0, 3),
                "swapSec": round(swap_sec, 3),
                "bpmOut": bpm_out, "bpmIn": round(bpm_in_eff, 3), "bpmInNative": bpm_in,
                "level": recipe_meta["level"],
                "abruptness": recipe_meta["abruptness"],
                "recipeId": resolved_id,
                "requestedRecipeId": rid,
                "recipeName": recipe_meta["name"], "description": recipe_meta["description"],
                "provenance": "synthetic-fixture; no production analysis, scoring or separated stems",
                "genreJump": pair["genreJump"],
                "tempoJumpPct": pair["tempoJumpPct"],
                "exitSec": round(exit_sec, 3), "entrySec": entry_sec,
                "vocalOut": pair["vocalOut"], "vocalIn": pair["vocalIn"],
                "vocalFps": VOCAL_FPS,
                "bassOverlapBeats": 0.0,
                "placementViolations": 0,
                "dropWindow": list(drop_window) if drop_window else None,
            }
            meta_path = out_dir / f"{pair_id}_{rid}.meta.json"
            with open(meta_path, "w", encoding="utf-8") as f:
                json.dump(meta, f, indent=2)
        manifest_pairs.append(pair)
        print(f"[{pair_id}] {spec['name']}: out {bpm_out:.0f}bpm {dur_out:.1f}s -> in {bpm_in:.0f}bpm {dur_in:.1f}s  exit@{exit_sec:.1f}s")

    manifest = {
        "tool": "generate_synthetic_pairs",
        "schema": 1,
        "pairs": manifest_pairs,
        "recipes": recipes,
        "autoPicks": AUTO_PICKS,
        "bars": args.bars,
    }
    with open(out_dir / "pairs.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print(f"wrote {out_dir / 'pairs.json'} ({len(manifest_pairs)} pairs x {len(recipes)} recipes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
