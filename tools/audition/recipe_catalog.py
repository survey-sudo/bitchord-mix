"""Canonical recipe selection and lossless consumption of JVM audition exports.

No planning or production recipe compilation belongs in this module. Real exports
come from the shared planner/materializer/RecipeInterpreter.compileFrozen seam.
"""

from __future__ import annotations

from copy import deepcopy
import json
import math
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RECIPE_DIR = ROOT / "djmix-core/src/commonMain/resources/recipes"
RECIPE_IDS = tuple(f"{prefix}{i}" for prefix, count in (("S", 6), ("C", 9), ("F", 11)) for i in range(1, count + 1))
LEVELS = ("SUBTLE", "CLUB", "FESTIVAL")
AUTO_IDS = tuple(f"AUTO-{level}" for level in LEVELS)


def load_catalog(directory: Path = RECIPE_DIR) -> dict[str, dict]:
    catalog = {}
    for rid in RECIPE_IDS:
        recipe = json.loads((directory / f"{rid}.json").read_text(encoding="utf-8"))
        if recipe.get("id") != rid or recipe.get("schema") != 2:
            raise ValueError(f"Invalid canonical RecipeV2 {rid}")
        catalog[rid] = recipe
    return catalog


CATALOG = load_catalog()


def requested_recipes(value: str | list[str] | tuple[str, ...]) -> list[str]:
    tokens = value.split(",") if isinstance(value, str) else value
    result = []
    for token in tokens:
        rid = str(token).strip().upper()
        if not rid:
            raise ValueError("Recipe selection contains an empty ID")
        expanded = RECIPE_IDS if rid == "ALL" else AUTO_IDS if rid == "AUTO" else (rid,)
        for selected in expanded:
            if selected not in CATALOG and selected not in AUTO_IDS:
                raise ValueError(f"Unknown recipe ID {selected!r}; use ALL or S1..S6, C1..C9, F1..F11")
            if selected not in result:
                result.append(selected)
    if not result:
        raise ValueError("Select at least one recipe")
    return result


def resolve_recipe(requested: str, auto_picks: dict | None = None) -> tuple[str, dict]:
    selected = requested_recipes([requested])
    if len(selected) != 1:
        raise ValueError("Resolve exactly one recipe at a time")
    rid = selected[0]
    if rid in AUTO_IDS:
        level = rid.removeprefix("AUTO-")
        rid = (auto_picks or {}).get(level)
        if rid not in CATALOG:
            raise ValueError(f"{requested} requires this pair's planner autoPicks.{level}")
        if LEVELS.index(CATALOG[rid]["level"]) > LEVELS.index(level):
            raise ValueError(f"{requested} resolved to ineligible higher-tier recipe {rid}")
    return rid, deepcopy(CATALOG[rid])


def _document(value, base: Path, label: str) -> dict:
    if isinstance(value, (str, Path)):
        path = Path(value)
        value = json.loads((path if path.is_absolute() else base / path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object or JSON file path")
    # Reject NaN/infinity rather than passing non-standard JSON to the native CLI.
    json.dumps(value, allow_nan=False)
    return deepcopy(value)


def exported_script(pair: dict, requested: str, base: Path) -> tuple[dict, dict, dict]:
    """Read and validate identity; return an unchanged canonical script and plan.

    The JVM producer must run ScriptExecutionGuard.validateFrozen before export.
    These handoff checks detect stale/missing files; they do not replace that guard.
    """
    rid, recipe = resolve_recipe(requested, pair.get("autoPicks"))
    exports = pair.get("exports", {})
    record = exports.get(requested, exports.get(rid))
    if not isinstance(record, dict):
        raise ValueError(f"{pair.get('pairId')}/{requested}: canonical JVM export missing; no recipe substitution is allowed")
    plan = _document(record.get("plan"), base, "TransitionPlanV1")
    script = _document(record.get("script"), base, "TransitionScriptV1")
    if plan.get("recipeId") != rid:
        raise ValueError(f"Requested {rid}, but exported plan uses {plan.get('recipeId')!r}")
    for key in ("outTrackId", "inTrackId"):
        if key in pair and pair[key] != plan.get(key):
            raise ValueError(f"Canonical plan {key} differs from this pair")
    if plan.get("djLevel") not in LEVELS or LEVELS.index(plan["djLevel"]) < LEVELS.index(recipe["level"]):
        raise ValueError(f"{rid}: exported plan has an ineligible DJ level")
    if script.get("schema") != 1 or plan.get("schema", 1) != 1:
        raise ValueError("Canonical audition requires schema=1 plans and scripts")
    if (script.get("planId"), script.get("generation")) != (plan.get("planId"), plan.get("generation")):
        raise ValueError("Canonical script planId/generation do not match the frozen plan")
    if not isinstance(script.get("planId"), str) or type(script.get("generation")) is not int:
        raise ValueError("Canonical script identity is missing")
    if script.get("sampleRateHz") != 48_000:
        raise ValueError("Audition PCM must be stereo f32 at 48 kHz")
    if any(key in script for key in ("inKnots", "audition")) or script.get("knots") or script.get("loopBounds"):
        raise ValueError("Canonical geometry must be in renderGeometry, without exporter-only overlays")
    geometry = script.get("renderGeometry")
    if not isinstance(geometry, dict):
        raise ValueError("Canonical script renderGeometry is missing")
    required_geometry = {"out", "inbound", "handoverMixSec", "handoverTrackSec", "incomingStartMixSec", "incomingStartTrackSec", "highQuality"}
    required_deck = {"regionStartSec", "frameCount", "loopStartFrame", "loopEndFrame", "releaseAtFrame", "knotFrames", "knotPositions", "runOn", "stopAtMixSec", "prerollSec", "levelGain", "fadeOutTrackSec"}
    if not required_geometry <= geometry.keys():
        raise ValueError("Canonical renderGeometry is incomplete")
    for setup_key, region_key, deck, track_key in (("out", "outRegion", "OUT", "outTrackId"), ("inbound", "inRegion", "IN", "inTrackId")):
        setup, region = geometry[setup_key], script.get(region_key, {})
        if not isinstance(setup, dict) or not required_deck <= setup.keys():
            raise ValueError(f"Canonical {setup_key} deck setup is incomplete")
        if region.get("deck") != deck or region.get("trackId") != plan.get(track_key):
            raise ValueError(f"Canonical {deck} track/deck identity differs from the plan")
        frames, positions = setup["knotFrames"], setup["knotPositions"]
        if not isinstance(frames, list) or not isinstance(positions, list) or len(frames) < 2 or len(frames) != len(positions):
            raise ValueError(f"Canonical {deck} knot arrays are incomplete")
        if any(type(v) not in (int, float) or not math.isfinite(v) for v in frames + positions):
            raise ValueError(f"Canonical {deck} knots must be finite numbers")
        if any(a >= b for a, b in zip(frames, frames[1:])):
            raise ValueError(f"Canonical {deck} knot clock is not strictly increasing")
        frame_count = setup["frameCount"]
        pcm = Path(region.get("pcmPath") or "")
        if type(frame_count) is not int or frame_count <= 0 or not pcm.is_absolute() or not pcm.is_file():
            raise ValueError(f"Canonical {deck} requires an absolute decoded raw-f32 PCM path and positive frameCount")
        if pcm.stat().st_size != frame_count * 2 * 4:
            raise ValueError(f"Canonical {deck} PCM frame count differs from frozen geometry")
    for stem in script.get("stems", []):
        pcm = Path(stem.get("pcmPath") or "")
        deck = stem.get("deck")
        if deck not in {"OUT", "IN"}:
            raise ValueError("Canonical stem deck must be OUT or IN")
        setup, region = (geometry["out"], script["outRegion"]) if deck == "OUT" else (geometry["inbound"], script["inRegion"])
        if stem.get("trackId") != region["trackId"] or stem.get("sampleRateHz", 48_000) != 48_000:
            raise ValueError("Canonical stem track/rate differs from its deck")
        if not pcm.is_absolute() or not pcm.is_file() or pcm.stat().st_size != setup["frameCount"] * 2 * 4:
            raise ValueError("Canonical stems require aligned full-length raw-f32 PCM; short stems are not padded")
    assets = [script["outRegion"]["pcmPath"], script["inRegion"]["pcmPath"]] + [stem["pcmPath"] for stem in script.get("stems", [])]
    hashes = script.get("assetHashes")
    if not isinstance(hashes, dict) or any(path not in hashes for path in assets):
        raise ValueError("Canonical export requires SHA-256 assetHashes for every deck and stem PCM")
    for path in assets:
        with open(path, "rb") as source:
            actual = hashlib.file_digest(source, "sha256").hexdigest()
        if hashes[path] != actual:
            raise ValueError(f"Canonical PCM SHA-256 mismatch: {path}")
    return script, plan, recipe
