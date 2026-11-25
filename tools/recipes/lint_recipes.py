#!/usr/bin/env python3
"""Dependency-free AE3 B5.3 recipe checks.

Run from any directory: python3 tools/recipes/lint_recipes.py
The default checks all 26 core JSONs, Android mirrors, and embedded registry JSON.
Pass files/directories to lint another catalog; --no-coverage allows a subset.

The simulation follows RecipeInterpreter anchor resolution, lane normalization,
depth scaling, low swaps, and ChoreoGuard boundaries/budgets. It exercises each
declared length and both stem variants. --check-fallbacks additionally exercises
every combination of optional capability fallbacks. Like ChoreoGuard, the
one-beat bass-overlap gate exempts S5's simple fade; full doubled lows still fail.
This is a recipe-data gate, not an audio-quality test or a replacement for Kotlin frozen
geometry tests, tools/mixq, or the native audition renderer.
"""

from __future__ import annotations

import argparse
from bisect import bisect_right
from dataclasses import dataclass
import itertools
import json
import math
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
CORE_RECIPES = Path("djmix-core/src/commonMain/resources/recipes")
APP_RECIPES = Path("app/src/main/assets/recipes")
EMBEDDED = Path("djmix-core/src/commonMain/kotlin/com/music/bitchord/djmix/core/recipes/BundledRecipesJson.kt")
DESCRIPTORS = Path("djmix-core/src/commonMain/kotlin/com/music/bitchord/djmix/core/planner/BuiltInRecipeCatalog.kt")
EXPECTED_IDS = tuple(f"{prefix}{n}" for prefix, count in (("S", 6), ("C", 9), ("F", 11)) for n in range(1, count + 1))
CLUB_FX = frozenset({"LOOP", "ROLL", "SWEEP", "FLANGER", "PHASER", "ECHO"})
FESTIVAL_FX = CLUB_FX | {"RISER", "BRAKE", "SPINBACK", "GATE"}
FX_TYPES = FESTIVAL_FX | {"IMPACT", "TAPE_STOP", "REVERB", "PUMP", "LOOP_ROLL", "SVF_Q"}
CAPABILITIES = {"STEMS", "FILTER_RES", "FILTER_KNOB", "PHASER", "GATE", "STUTTER", "RISER", "PUMP", "BRAKE", "SPINBACK", "LOUDNESS", "TRUE_PEAK"}
ANCHORS = {"ZERO", "IN_AUDIBLE", "SWAP", "RELEASE", "OUT_STOP", "PRE_FRAC", "POST_FRAC", "OVERLAP_FRAC", "A_PLUS_CAPPED_S", "S_PLUS_CAPPED_R", "SWAP_BACK_A", "SWAP_BACK_ZERO", "RELEASE_BACK_S", "VOCAL_ENTRY", "FADE_START"}
DEPTH = {"SUBTLE": 0.6, "CLUB": 0.85, "FESTIVAL": 1.0}
MAX_FX = {"SUBTLE": 1, "CLUB": 2, "FESTIVAL": 3}
STEMS = {"VOCALS", "DRUMS", "BASS", "OTHER"}
WET_LANES = {"ECHO": "ECHO_SEND", "REVERB": "REVERB_SEND", "FLANGER": "FLANGER_WET", "PHASER": "PHASER_WET", "GATE": "GATE_DEPTH", "RISER": "RISER_LEVEL", "PUMP": "PUMP_DEPTH", "BRAKE": "BRAKE", "TAPE_STOP": "BRAKE", "SPINBACK": "BRAKE"}
NEUTRAL = dict.fromkeys({"LOW_DB", "MID_DB", "HIGH_DB", "FILTER_KNOB", *WET_LANES.values()}, 0.0)
NEUTRAL.update({"GAIN": 1.0, "HIGH_PASS_HZ": 20.0, "LOW_PASS_HZ": 20_000.0, "FILTER_RES": 1.0})
NEUTRAL.update({f"STEM_{stem}": 1.0 for stem in STEMS})
FX_SLOTS = {fx: fx for fx in ("ECHO", "REVERB", "FLANGER", "PHASER", "GATE", "PUMP")}
FX_SLOTS.update(dict.fromkeys({"BRAKE", "TAPE_STOP", "SPINBACK"}, "PLATTER"))
FX_SLOTS.update(dict.fromkeys({"RISER", "IMPACT"}, "BUILD"))


@dataclass(frozen=True)
class Issue:
    recipe_id: str
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.recipe_id}: {self.code}: {self.message}"


class InvalidRecipe(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise InvalidRecipe(message)


def number(value: object) -> bool:
    return type(value) in (int, float) and math.isfinite(value)


def fields(obj: object, path: str, required: set[str] = frozenset(), **spec) -> None:
    """Validate known Kotlin fields, allowing unknown keys like RecipeRegistry.json."""
    require(isinstance(obj, dict), f"{path} must be an object")
    require(required <= obj.keys(), f"{path} missing {sorted(required - obj.keys())}")
    for key, check in spec.items():
        if key in obj:
            require(check(obj[key]), f"{path}.{key} has an invalid value: {obj[key]!r}")


def enum(values):
    return lambda value: isinstance(value, str) and value in values


def integer(value: object) -> bool:
    return type(value) is int


def numeric_map(value: object) -> bool:
    return isinstance(value, dict) and all(isinstance(k, str) and number(v) for k, v in value.items())


def validate_time(value: object, path: str) -> None:
    fields(value, path, {"anchor"}, anchor=enum(ANCHORS), param=number,
           offsetBars=number, offsetBeats=number, offsetSec=number,
           minAt=lambda v: v is None or enum(ANCHORS)(v),
           maxAt=lambda v: v is None or enum(ANCHORS)(v))


def validate_points(points: object, path: str, allow_empty: bool = False) -> None:
    require(isinstance(points, list) and (allow_empty or bool(points)), f"{path} must be a non-empty waypoint list")
    for i, point in enumerate(points):
        label = f"{path}[{i}]"
        fields(point, label, {"time", "value"}, value=number, depthBase=number,
               scaleByFxDepth=lambda v: type(v) is bool)
        validate_time(point["time"], f"{label}.time")


def validate_recipe(recipe: object) -> None:
    fields(recipe, "recipe", {"schema", "id", "name", "level", "family", "abruptness", "steps", "noStemSteps"},
           schema=lambda v: type(v) is int and v == 2,
           id=lambda v: isinstance(v, str) and bool(v.strip()),
           name=lambda v: isinstance(v, str) and bool(v.strip()),
           description=lambda v: isinstance(v, str) and bool(v.strip()),
           level=enum(DEPTH), family=enum({"BLEND", "SWAP", "CUT", "FX", "DROP"}),
           abruptness=enum({"SMOOTH", "PUNCHY", "ABRUPT"}), energyDelta=number,
           enabled=lambda v: type(v) is bool,
           lengthOptionsBars=lambda v: isinstance(v, list) and bool(v) and all(integer(n) and n > 0 for n in v))
    require(bool(recipe.get("description", "").strip()), "recipe.description must not be blank")
    req = recipe.get("requirements", {})
    fields(req, "requirements", maxTempoGap=lambda v: number(v) and v >= 0,
           minGridConfidence=lambda v: number(v) and 0 <= v <= 1,
           minOverlapBars=lambda v: number(v) and v > 0,
           minStemCount=lambda v: integer(v) and v >= 0,
           genreAffinity=numeric_map, requiredStems=lambda v: isinstance(v, list),
           requiredCapabilities=lambda v: isinstance(v, list) and all(enum(CAPABILITIES)(n) for n in v),
           **{key: lambda v: type(v) is bool for key in ("requiresBeatMatch", "requiresHarmony", "requiresDrops", "requiresOutDrop", "requiresOutLoop", "requiresTempoOrGenreJump")})
    for stem in req.get("requiredStems", []):
        fields(stem, "requiredStems[]", {"deck", "stem"}, deck=enum({"OUT", "IN"}), stem=enum(STEMS))
    fields(recipe.get("placementPrefs", {}), "placementPrefs",
           swapRule=lambda v: isinstance(v, str),
           maxEntrySkipBars=lambda v: integer(v) and v >= 0,
           preferPostSwapPhraseBars=lambda v: integer(v) and v >= 0,
           **{key: lambda v: type(v) is bool for key in ("preferOutroExit", "preferIntroEntry", "preferDropEntry", "avoidVocalExit", "avoidVocalEntry", "avoidBuildExit")})
    for variant in ("steps", "noStemSteps"):
        steps = recipe[variant]
        require(isinstance(steps, list) and bool(steps), f"{variant} must be non-empty")
        for i, step in enumerate(steps):
            label = f"{variant}[{i}]"
            fields(step, label, {"type"}, type=enum({"lane_curve", "swap_lows", "enable_isolator", "configure_fx"}))
            if step["type"] == "lane_curve":
                fields(step, label, {"deck", "kind", "points"}, deck=enum({"OUT", "IN"}), kind=enum(NEUTRAL),
                       shape=enum({"LINEAR", "EXPONENTIAL", "EQUAL_POWER_FALL", "EQUAL_POWER_RISE"}),
                       steps=integer, holdNeutralAtStart=lambda v: type(v) is bool,
                       ensureNeutralAtReleaseForIn=lambda v: type(v) is bool)
                validate_points(step["points"], f"{label}.points")
                require(variant != "noStemSteps" or not step["kind"].startswith("STEM_"), f"{label} uses a stem lane")
            elif step["type"] == "swap_lows":
                fields(step, label, widthBeats=lambda v: number(v) and v > 0,
                       inLowBeforeDb=number, outLowAfterDb=number, useBassStems=lambda v: type(v) is bool)
                validate_time(step.get("at", {"anchor": "SWAP"}), f"{label}.at")
                require(variant != "noStemSteps" or not step.get("useBassStems", True), f"{label} uses bass stems")
            elif step["type"] == "configure_fx":
                fields(step, label, {"deck", "fx"}, deck=enum({"OUT", "IN"}), fx=enum(FX_TYPES),
                       requiredCap=lambda v: v is None or enum(CAPABILITIES)(v),
                       fallbackFx=lambda v: v is None or enum(FX_TYPES)(v), params=numeric_map,
                       flag=lambda v: type(v) is bool)
                for key in ("startTime", "endTime"):
                    if step.get(key) is not None:
                        validate_time(step[key], f"{label}.{key}")
                for key in ("wetCurve", "fallbackWetCurve"):
                    if step.get(key) is not None:
                        validate_points(step[key], f"{label}.{key}", allow_empty=True)


@dataclass(frozen=True)
class Context:
    release: float
    swap: float
    audible: float
    stop: float
    bar: float = 2.0

    def anchor(self, anchor: str, param: float = 0.0) -> float:
        r, s, a, b = self.release, self.swap, self.audible, self.bar
        fraction = min(1.0, max(0.0, param))
        return {
            "ZERO": 0.0, "IN_AUDIBLE": a, "SWAP": s, "RELEASE": r,
            "OUT_STOP": self.stop, "PRE_FRAC": a + fraction * max(0.0, s - a),
            "POST_FRAC": s + fraction * max(0.0, r - s), "OVERLAP_FRAC": fraction * r,
            "A_PLUS_CAPPED_S": min(s, a + param * b), "S_PLUS_CAPPED_R": min(r, s + param * b),
            "SWAP_BACK_A": max(a, s - param * b), "SWAP_BACK_ZERO": max(0.0, s - param * b),
            "RELEASE_BACK_S": max(s, r - param * b), "VOCAL_ENTRY": s,
            "FADE_START": max(0.0, min(max(0.0, r - 0.2), r - min(r, max(min(0.5, r), 2 * b)))),
        }[anchor]

    def resolve(self, time: dict, deck: str, ceiling: float | None = None) -> float:
        t = (self.anchor(time["anchor"], time.get("param", 0.0)) + time.get("offsetBars", 0.0) * self.bar
             + time.get("offsetBeats", 0.0) * self.bar / 4 + time.get("offsetSec", 0.0))
        if time.get("minAt") is not None:
            t = max(t, self.anchor(time["minAt"]))
        if time.get("maxAt") is not None:
            t = min(t, self.anchor(time["maxAt"]))
        return min(self.release if deck == "IN" else self.stop, max(0.0, t)) if ceiling is None else min(ceiling, max(0.0, t))


def is_neutral(kind: str, value: float) -> bool:
    if kind in {"LOW_DB", "MID_DB", "HIGH_DB"}:
        return abs(value) <= 0.25
    if kind == "HIGH_PASS_HZ":
        return value <= 25
    if kind == "LOW_PASS_HZ":
        return value >= 19_000
    if kind == "FILTER_RES":
        return abs(value - 1) <= 0.05
    if kind in WET_LANES.values():
        return -0.0001 <= value <= 0.02
    return abs(value - NEUTRAL[kind]) <= 0.02


def normalize(points: list[tuple[float, float]], ceiling: float) -> list[tuple[float, float]]:
    # RecipeInterpreter.normalizeStrictTimes: keep authored order, coalesce identical
    # points, then separate clamped coincident waypoints by one millisecond.
    result = []
    previous = 0.0
    for time, value in points:
        time = min(ceiling, max(previous, time, 0.0))
        previous = time
        if result and abs(time - result[-1][0]) <= 1e-6 and abs(value - result[-1][1]) <= 1e-6:
            continue
        result.append([time, value])
    for i in range(len(result) - 2, -1, -1):
        if result[i][0] >= result[i + 1][0] - 1e-6:
            result[i][0] = max(0.0, result[i + 1][0] - 0.001)
    for i in range(1, len(result)):
        if result[i][0] <= result[i - 1][0] + 1e-6:
            result[i][0] = min(ceiling, result[i - 1][0] + 0.001)
    strict = []
    for i, point in enumerate(result):
        if not strict or point[0] > strict[-1][0] + 1e-9:
            strict.append(tuple(point))
        elif i == len(result) - 1 and len(strict) >= 2:
            strict[-1] = (strict[-1][0], point[1])
    return strict


@dataclass(frozen=True)
class Lane:
    points: list[tuple[float, float]]
    exponential: bool = False

    def at(self, time: float) -> float:
        i = bisect_right(self.points, (time, math.inf)) - 1
        if i < 0:
            return self.points[0][1]
        if i >= len(self.points) - 1:
            return self.points[-1][1]
        (t0, v0), (t1, v1) = self.points[i:i + 2]
        u = min(1.0, max(0.0, (time - t0) / (t1 - t0)))
        if self.exponential and v0 > 0 and v1 > 0:
            return v0 * math.exp(math.log(v1 / v0) * u)
        return v0 + (v1 - v0) * u


def build_lane(step: dict, ctx: Context, depth: float) -> Lane:
    deck, kind = step["deck"], step["kind"]
    ceiling = ctx.release if deck == "IN" or kind == "GAIN" else ctx.stop
    points = [(ctx.resolve(wp["time"], deck, ceiling),
               wp.get("depthBase", 0.0) + depth * (wp["value"] - wp.get("depthBase", 0.0))
               if wp.get("scaleByFxDepth", False) else wp["value"]) for wp in step["points"]]
    if points[0][0] > 0.002 and (step.get("holdNeutralAtStart", True) or (deck == "IN" and kind == "GAIN")):
        points.insert(0, (0.0, 0.0 if deck == "IN" and kind == "GAIN" else NEUTRAL[kind]))
    # Do not silently repair a non-neutral OUT gain at zero: frozen compilation
    # rejects it even though the legacy compatibility path used to overwrite it.
    if deck == "IN" and step.get("ensureNeutralAtReleaseForIn", True) and not is_neutral(kind, points[-1][1]):
        points.append((ctx.release, NEUTRAL[kind]))
    points = normalize(points, ceiling)
    shape = step.get("shape", "LINEAR")
    if shape in {"EQUAL_POWER_FALL", "EQUAL_POWER_RISE"}:
        expanded = [points[0]]
        count = min(32, max(2, step.get("steps", 12)))
        for (t0, v0), (t1, v1) in zip(points, points[1:]):
            if t1 - t0 <= 0.005 or abs(v1 - v0) <= 1e-4:
                expanded.append((t1, v1))
                continue
            for k in range(1, count + 1):
                u = k / count
                weight = math.sin(u * math.pi / 2) if shape == "EQUAL_POWER_RISE" else 1 - math.cos(u * math.pi / 2)
                expanded.append((t0 + (t1 - t0) * u, v0 + (v1 - v0) * weight))
        points = expanded
    return Lane(points, shape == "EXPONENTIAL" or kind in {"HIGH_PASS_HZ", "LOW_PASS_HZ"})


def db_to_linear(db: float) -> float:
    return 0.0 if db <= -48 else 10 ** (db / 20)


def compile_lanes(recipe: dict, variant: str, ctx: Context, missing_caps: frozenset[str]) -> tuple[dict, set[str]]:
    lanes, slots = {}, set()
    depth = DEPTH[recipe["level"]]
    for step in recipe[variant]:
        kind = step["type"]
        if kind == "lane_curve":
            lanes[step["deck"], step["kind"]] = build_lane(step, ctx, depth)
        elif kind == "swap_lows":
            center = ctx.resolve(step.get("at", {"anchor": "SWAP"}), "OUT", ctx.release)
            half = max(0.005, step.get("widthBeats", 0.25) * ctx.bar / 8)
            end = min(ctx.release, center + half)
            start = max(0.0, end - 2 * half)
            if ctx.release > 0.02 and start < 0.001:
                start = 0.001
                end = min(ctx.release, max(end, start + 0.005))
            stem = variant == "steps" and step.get("useBassStems", True)
            before, after = step.get("inLowBeforeDb", -24.0), step.get("outLowAfterDb", -24.0)
            lane_kind = "STEM_BASS" if stem else "LOW_DB"
            out_after = (0.0 if after <= -23 else min(1.0, db_to_linear(after))) if stem else after
            in_before = min(1.0, db_to_linear(before)) if stem else before
            neutral = 1.0 if stem else 0.0
            lanes["OUT", lane_kind] = Lane([(0.0, neutral), (start, neutral), (end, out_after)])
            lanes["IN", lane_kind] = Lane([(0.0, in_before), (start, in_before), (end, neutral)])
        elif kind == "configure_fx":
            missing = step.get("requiredCap") in missing_caps
            fx = step.get("fallbackFx") if missing else step["fx"]
            if fx is None:
                continue
            if fx in FX_SLOTS:
                slots.add(FX_SLOTS[fx])
            curve = step.get("fallbackWetCurve") if missing else None
            if curve is None:
                curve = step.get("wetCurve")
            if curve and fx in WET_LANES:
                lane_kind = WET_LANES[fx]
                wet_step = dict(deck=step["deck"], kind=lane_kind, points=curve,
                                holdNeutralAtStart=step["deck"] == "OUT",
                                ensureNeutralAtReleaseForIn=step["deck"] == "IN")
                lanes[step["deck"], lane_kind] = build_lane(wet_step, ctx, 1.0 if lane_kind == "BRAKE" else depth)
            if fx in {"BRAKE", "TAPE_STOP", "SPINBACK", "ROLL", "LOOP_ROLL", "SWEEP"}:
                default_start = max(0.0, ctx.swap - (2 * ctx.bar if fx in {"ROLL", "LOOP_ROLL", "SWEEP"} else 1.5 * ctx.bar / 4))
                start = ctx.resolve(step["startTime"], step["deck"]) if step.get("startTime") else default_start
                end = ctx.resolve(step["endTime"], step["deck"]) if step.get("endTime") else ctx.swap
                require(end > start, f"{variant}: {fx} has empty or reversed resolved interval ({start}, {end})")
    return lanes, slots


def low_level(lanes: dict, deck: str, time: float) -> float:
    def value(kind, default):
        lane = lanes.get((deck, kind))
        return lane.at(time) if lane else default
    gain = value("GAIN", 1.0)
    if gain <= 0.01:
        return 0.0
    return (min(1.5, max(0.0, gain)) * min(1.5, max(0.0, value("STEM_BASS", 1.0)))
            * min(1.5, db_to_linear(value("LOW_DB", 0.0))))


def check_lanes(lanes: dict, slots: set[str], recipe: dict, ctx: Context) -> None:
    require(len(slots) <= MAX_FX[recipe["level"]], f"FX budget exceeded: {len(slots)} slots {sorted(slots)} > {MAX_FX[recipe['level']]}")
    require(("OUT", "GAIN") in lanes and ("IN", "GAIN") in lanes, "Missing OUT/IN GAIN lane")
    for (deck, kind), lane in lanes.items():
        require(all(math.isfinite(t) and math.isfinite(v) for t, v in lane.points), f"Non-finite {deck} {kind} lane")
        require(all(a[0] < b[0] for a, b in zip(lane.points, lane.points[1:])), f"Non-monotonic {deck} {kind} lane")
        if kind == "FILTER_RES":
            require(all(v <= 6 for _, v in lane.points), f"{deck} FILTER_RES exceeds safe cap 6")
        if deck == "OUT":
            require(is_neutral(kind, lane.at(0.0)), f"OUT {kind} non-neutral at splice: {lane.at(0.0)}")
            if kind == "BRAKE":
                require(all(-0.0001 <= v <= 1.02 for _, v in lane.points), "BRAKE progress outside [0,1]")
            if kind in set(WET_LANES.values()) - {"BRAKE"}:
                require(lane.at(ctx.stop) <= 0.02 and lane.points[-1][1] <= 0.02, f"OUT {kind} tail not retired at stop")
        else:
            require(is_neutral(kind, lane.at(ctx.release)) and is_neutral(kind, lane.points[-1][1]), f"IN {kind} non-neutral at handover")
    require(lanes["IN", "GAIN"].at(0.0) <= 0.001, "IN GAIN not muted at splice")
    require(lanes["OUT", "GAIN"].at(ctx.release) <= 0.001 and lanes["OUT", "GAIN"].points[-1][1] <= 0.001, "OUT GAIN not zero at release")
    # Match ChoreoGuard's 5 ms scan and also inspect every waypoint so a short
    # full-bass collision between scan ticks is not silently missed.
    critical = {t for lane in lanes.values() for t, _ in lane.points if 0 <= t <= ctx.release}
    critical.update((a + b) / 2 for a, b in zip(sorted(critical), sorted(critical)[1:]))
    for t in critical:
        require(not (low_level(lanes, "OUT", t) >= 0.9 and low_level(lanes, "IN", t) >= 0.9), f"Bass clash: simultaneous full lows at {t:.3f}s")
    consecutive = 0.0
    for tick in range(math.floor(ctx.release / 0.005) + 1):
        t = tick * 0.005
        out, incoming = low_level(lanes, "OUT", t), low_level(lanes, "IN", t)
        require(not (out >= 0.9 and incoming >= 0.9), f"Bass clash: simultaneous full lows at {t:.3f}s")
        consecutive = consecutive + 0.005 if out > 0.5 and incoming > 0.5 else 0.0
        if recipe["id"].upper() != "S5":
            require(consecutive <= max(0.1, ctx.bar / 4) + 0.005 + 1e-9, f"Bass clash: dual lows above -6 dB for {consecutive:.3f}s (> one beat)")


def direct_fx(recipe: dict, variant: str = "steps") -> set[str]:
    result = set()
    steps = recipe.get(variant, [])
    if not isinstance(steps, list):
        return result
    for step in steps:
        if not isinstance(step, dict):
            continue
        if step.get("type") == "configure_fx" and step.get("fx") in FX_TYPES:
            result.add(step["fx"])
        elif step.get("type") == "lane_curve" and step.get("kind") in {"HIGH_PASS_HZ", "LOW_PASS_HZ", "FILTER_KNOB"}:
            result.add("SWEEP")
    return result


def lint_recipe(recipe: object, check_fallbacks: bool = False) -> list[Issue]:
    rid = str(recipe.get("id", "unknown")) if isinstance(recipe, dict) else "unknown"
    try:
        validate_recipe(recipe)
    except (InvalidRecipe, OverflowError) as exc:
        return [Issue(rid, "SCHEMA", str(exc))]
    req = recipe.get("requirements", {})
    fx = direct_fx(recipe) | direct_fx(recipe, "noStemSteps")
    abrupt = recipe["abruptness"] == "ABRUPT" or rid in {"F2", "F7", "F9"} or bool(fx & {"BRAKE", "TAPE_STOP", "SPINBACK"})
    if abrupt and not (recipe["abruptness"] == "ABRUPT" and recipe["level"] == "FESTIVAL"
                       and req.get("requiresTempoOrGenreJump", False) and not req.get("requiresBeatMatch", True)):
        return [Issue(rid, "ABRUPT_GATE", "Brake/spinback/abrupt recipes must be Festival ABRUPT, require a tempo/genre jump, and disable beat matching")]
    issues = []
    prefs = recipe.get("placementPrefs", {})
    drop = prefs.get("preferDropEntry", False) or prefs.get("swapRule", "").startswith("DROP")
    ratios = (1.0,) if drop else ((0.5, 1.0) if prefs.get("swapRule", "").startswith("CUT") else (0.5,))
    for variant in ("steps", "noStemSteps"):
        caps = sorted({s["requiredCap"] for s in recipe[variant] if s.get("requiredCap")})
        # Exercise mixed availability, not only all-or-none fallback selection.
        masks = ([frozenset(c) for n in range(len(caps) + 1) for c in itertools.combinations(caps, n)]
                 if check_fallbacks else [frozenset()])
        for bars, ratio in itertools.product(recipe.get("lengthOptionsBars", [8, 16]), ratios):
            release, swap = bars * 2.0, bars * 2.0 * ratio
            tail = any(s.get("type") == "configure_fx" and s["deck"] == "OUT" and
                       (s["fx"] in {"ECHO", "REVERB"} or s.get("fallbackFx") == "ECHO") for s in recipe[variant])
            stop = max(release, swap + 0.20) if tail and swap + 0.15 > release else release
            ctx = Context(release, swap, 0.0, stop)
            for missing in masks:
                try:
                    lanes, slots = compile_lanes(recipe, variant, ctx, missing)
                    check_lanes(lanes, slots, recipe, ctx)
                except (InvalidRecipe, OverflowError, ValueError) as exc:
                    issues.append(Issue(rid, "SIMULATION", f"{variant}, bars={bars}, swap={ratio}, missing={sorted(missing)}: {exc}"))
                    # One actionable diagnostic per variant/geometry is sufficient.
                    break
    return issues


def lint_catalog(recipes: list, coverage: bool = True, check_fallbacks: bool = False) -> list[Issue]:
    issues, seen, schema_valid = [], set(), []
    for recipe in recipes:
        rid = str(recipe.get("id", "unknown")) if isinstance(recipe, dict) else "unknown"
        if rid in seen:
            issues.append(Issue(rid, "DUPLICATE_ID", "Recipe id appears more than once"))
        seen.add(rid)
        recipe_issues = lint_recipe(recipe, check_fallbacks=check_fallbacks)
        issues.extend(recipe_issues)
        if not any(issue.code == "SCHEMA" for issue in recipe_issues):
            schema_valid.append(recipe)
    if coverage:
        for variant in ("steps", "noStemSteps"):
            for level, required in (("CLUB", CLUB_FX), ("FESTIVAL", FESTIVAL_FX)):
                tags = set().union(*(direct_fx(r, variant) for r in schema_valid if r.get("level") == level and r.get("enabled", True)))
                missing = required - tags
                if missing:
                    issues.append(Issue("CATALOG", "FX_COVERAGE", f"{level} {variant} missing {', '.join(sorted(missing))}"))
    return issues


def reject_duplicate_keys(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key {key!r}")
        result[key] = value
    return result


def load_paths(paths: list[Path]) -> tuple[list, list[Issue]]:
    recipes, issues = [], []
    files = []
    for path in paths:
        if path.is_dir():
            found = sorted(path.glob("*.json"))
            if not found:
                issues.append(Issue(str(path), "NO_RECIPES", "Directory contains no recipe JSONs"))
            files.extend(found)
        else:
            files.append(path)
    for path in files:
        try:
            recipe = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicate_keys)
            if isinstance(recipe, dict) and recipe.get("id") != path.stem:
                issues.append(Issue(str(path), "FILENAME_ID", f"Filename does not match id {recipe.get('id')!r}"))
            recipes.append(recipe)
        except (OSError, UnicodeError, ValueError) as exc:
            issues.append(Issue(str(path), "PARSE", str(exc)))
    return recipes, issues


def lint_descriptors(source: str, core_by_id: dict) -> list[Issue]:
    """F1/F5 tags describe primary steps; optional fallback FX are not coverage."""
    issues = []
    for rid in ("F1", "F5"):
        block = re.search(rf"\bval {rid}\s*=\s*RecipeCatalogEntry\((.*?)\n    \)", source, re.DOTALL)
        tags = re.search(r"fxTags\s*=\s*setOf\((.*?)\)", block.group(1), re.DOTALL) if block else None
        if tags is None:
            issues.append(Issue(rid, "DESCRIPTOR", "Missing built-in descriptor or fxTags"))
            continue
        advertised = set(re.findall(r"RecipeFxType\.(\w+)", tags.group(1)))
        recipe = core_by_id.get(rid)
        if recipe is None:
            continue  # Missing recipe diagnostics are emitted by BUNDLED_IDS.
        for variant in ("steps", "noStemSteps"):
            primary = direct_fx(recipe, variant)
            if advertised != primary:
                issues.append(Issue(rid, "DESCRIPTOR_MISMATCH",
                                    f"{variant} primary FX differ from built-in descriptor: "
                                    f"advertised-only={sorted(advertised - primary)}, JSON-only={sorted(primary - advertised)}"))
    return issues


def lint_repository(root: Path = ROOT, check_fallbacks: bool = False) -> tuple[list, list[Issue]]:
    recipes, issues = load_paths([root / CORE_RECIPES])
    assets, asset_issues = load_paths([root / APP_RECIPES])
    issues.extend(asset_issues)
    issues.extend(lint_catalog(recipes, check_fallbacks=check_fallbacks))
    for label, documents in (("core", recipes), ("assets", assets)):
        ids = {r.get("id") for r in documents if isinstance(r, dict) and isinstance(r.get("id"), str)}
        if ids != set(EXPECTED_IDS) or len(documents) != len(EXPECTED_IDS):
            issues.append(Issue(label, "BUNDLED_IDS", f"Expected 26 recipes; missing={sorted(set(EXPECTED_IDS) - ids)}, extra={sorted(ids - set(EXPECTED_IDS))}, count={len(documents)}"))
    core_by_id = {r["id"]: r for r in recipes if isinstance(r, dict) and isinstance(r.get("id"), str)}
    for asset in assets:
        if not isinstance(asset, dict) or not isinstance(asset.get("id"), str):
            issues.append(Issue("assets", "SCHEMA", "Recipe must be an object with a string id"))
        elif core_by_id.get(asset["id"]) != asset:
            issues.append(Issue(asset["id"], "ASSET_MISMATCH", "Android asset differs from core recipe"))
    try:
        source = (root / EMBEDDED).read_text(encoding="utf-8")
        payloads = re.findall(r'"([^"\n]+)"\s+to\s+"""(.*?)"""', source, flags=re.DOTALL)
        embedded = {}
        for rid, payload in payloads:
            require(rid not in embedded, f"Duplicate embedded id {rid}")
            embedded[rid] = json.loads(payload, object_pairs_hook=reject_duplicate_keys)
        require(set(embedded) == set(EXPECTED_IDS), "Embedded registry must contain exactly the canonical 26 ids")
        order = re.search(r"val orderedIds.*?listOf\((.*?)\)", source, flags=re.DOTALL)
        require(order is not None and tuple(re.findall(r'"([SCF]\d+)"', order.group(1))) == EXPECTED_IDS, "Embedded registry orderedIds differs from canonical order")
        for rid, recipe in embedded.items():
            if core_by_id.get(rid) != recipe:
                issues.append(Issue(rid, "EMBEDDED_MISMATCH", "Embedded registry JSON differs from core recipe"))
    except (OSError, UnicodeError, ValueError) as exc:
        issues.append(Issue("registry", "EMBEDDED", str(exc)))
    try:
        issues.extend(lint_descriptors((root / DESCRIPTORS).read_text(encoding="utf-8"), core_by_id))
    except (OSError, UnicodeError) as exc:
        issues.append(Issue("catalog", "DESCRIPTOR", str(exc)))
    return recipes, issues


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="*", type=Path, help="Recipe JSON files or directories (default: bundled repository)")
    parser.add_argument("--no-coverage", action="store_true", help="Skip catalog coverage for an explicitly supplied subset")
    parser.add_argument("--check-fallbacks", action="store_true", help="Also lint mixed capability fallback combinations (stricter than the current Kotlin linter)")
    args = parser.parse_args(argv)
    if args.no_coverage and not args.paths:
        parser.error("--no-coverage requires explicit recipe paths")
    if args.paths:
        recipes, issues = load_paths(args.paths)
        issues.extend(lint_catalog(recipes, coverage=not args.no_coverage, check_fallbacks=args.check_fallbacks))
    else:
        recipes, issues = lint_repository(check_fallbacks=args.check_fallbacks)
    for issue in issues:
        print(issue, file=sys.stderr)
    if issues:
        print(f"FAIL: {len(issues)} violation(s), {len(recipes)} recipe(s)", file=sys.stderr)
        return 1
    print(f"PASS: {len(recipes)} RecipeV2 definitions; schema, anchors, boundaries, lows, FX budgets, abrupt gates" + (", tier FX coverage" if not args.no_coverage else ""))
    if not args.paths:
        print("PASS: Android assets and embedded RecipeRegistry payloads match all 26 core JSONs")
        print("PASS: F1/F5 descriptor tags match primary FX in both stem variants (fallbacks excluded)")
        print("NOTE: S5 retains ChoreoGuard's simple-fade one-beat bass-overlap exemption; rendered-audio checks remain in mixq")
        if args.check_fallbacks:
            print("PASS: All optional capability combinations stay within tier FX budgets")
        else:
            print("NOTE: Use --check-fallbacks to audit optional capability paths as well")
    return 0


if __name__ == "__main__":
    sys.exit(main())
