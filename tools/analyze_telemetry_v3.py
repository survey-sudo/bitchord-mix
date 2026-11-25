#!/usr/bin/env python3
"""Automix telemetry analyzer for AE3 (Part A §10 table, B7.3).

Accepts two JSONL dialects and normalises both into one record shape:

* v3  (`automix-telemetry-v3.jsonl`): one `MixTelemetryV3` object per line with
  `schema=3`, `engine`, `eventType` (enum name such as `MIX_FIRE`, or the wire
  name `mix.fire`), `planId`, `generation`, `state`, `timestampMs`, ...
* legacy (`automix-telemetry.jsonl`): `{"ts":..., "ev":"mix.timing"|"mix.fire"|
  "mix.countdown.*"|...}` records as written by vc91..vc94.

A file that mixes both dialects is supported: each transition is described
twice (MixMachine writes v3, CrossfadeController/PairPreparer legacy), so
fires and attempts are de-duplicated per pair before counting, and v3 fires
absorb the legacy `fx` / `exitVocal` fields the v3 schema cannot carry.

Fire records that carry a recipe id but no FX list (every v3 fire) get their
FX types derived from the bundled RecipeV2 catalog (`--recipes`, default the
repo's `app/src/main/assets/recipes`), mirroring `direct_fx()` in
tools/recipes/lint_recipes.py — that is what makes the Festival distinct-FX
§10 row measurable from v3-only telemetry.

`--before` accepts either a baseline JSON summary (for example
`evidence/ae3-m0/m0-metrics.json`) or a telemetry JSONL file; `--after` is the
v3 (or legacy) JSONL to score. `--out` writes the Markdown results table and a
sibling `.json` with the same numbers.

Exit status: 0 on success (gates reported, not enforced); 1 when `--strict` is
given and a Part A §10 gate fails or is not measured (a gate with no data cannot
pass); 2 on usage or input errors.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

LEVELS = ("SUBTLE", "CLUB", "FESTIVAL")
FROZEN_PATHS = {"PREPARER_FROZEN", "PREPARER_NO_STEM_FALLBACK", "v2-native", "v2-straight"}
LEGACY_FROZEN_HINT = {"v2-native", "v2-straight"}
FESTIVAL_WINDOW = 12

# A merged v3+legacy stream describes one transition twice: MixMachine writes
# the v3 records, CrossfadeController/PairPreparer the legacy ones. These
# windows bound what counts as "the same transition" when deduping.
FIRE_MATCH_WINDOW_MS = 15_000        # both records are written at execution time
ATTEMPT_MATCH_WINDOW_MS = 300_000    # legacy mix.timing lands at fire, v3 prep at start
INCIDENT_MATCH_WINDOW_MS = 2_000     # boolean incidents (moved / last resort / broken)

# `TelemetryEventType.wireName` values that keep an underscore; every other
# enum name maps mechanically to its dotted wire name.
WIRE_NAME_OVERRIDES = {"MIX_LAST_RESORT": "mix.last_resort"}

# Part A §10 targets (Festival/Club/Subtle rows use the same numbers).
GATES = {
    "overlap_pct": ("Transitions with an audible overlap", "== 100"),
    "countdown_broken": ("mix.countdown.broken", "== 0"),
    "ready_p50_s": ("Next-known -> READY p50", "<= 6"),
    "ready_p95_s": ("Next-known -> READY p95", "<= 15"),
    "analysis_cpu_p95_ms": ("Analysis ms per track (CPU) p95", "<= 1500"),
    "stem_p95_ms": ("Stem ms per needed window p95", "<= 8000"),
    "frozen_pct": ("Path = preparer-frozen", "== 100"),
    "exit_mid_vocal": ("Exit mid-vocal (Syvenu > 0.5 at exit)", "== 0"),
    "festival_recipes": ("Festival distinct recipes over 12", ">= 7"),
    "festival_fx": ("Festival distinct FX types over 12", ">= 7"),
}


# ---------------------------------------------------------------------------
# Normalised record
# ---------------------------------------------------------------------------

@dataclass
class Rec:
    kind: str                      # wire name, e.g. "mix.fire"
    ts: int
    schema: str                    # "v3" | "legacy"
    plan_id: Optional[str] = None
    generation: Optional[int] = None
    state: str = ""
    out_id: str = ""
    in_id: str = ""
    level: Optional[str] = None
    path: Optional[str] = None
    recipe: Optional[str] = None
    fx: List[str] = field(default_factory=list)
    analysis_ms: Optional[float] = None
    plan_ms: Optional[float] = None
    stem_out_ms: Optional[float] = None
    stem_in_ms: Optional[float] = None
    build_ms: Optional[float] = None
    countdown_broken: bool = False
    reason: Optional[str] = None
    detail: str = ""
    stress: bool = False
    exit_vocal: Optional[float] = None
    raw: Dict[str, Any] = field(default_factory=dict)


def num(v: Any) -> Optional[float]:
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return None
    f = float(v)
    return None if math.isnan(f) or math.isinf(f) else f


def text(v: Any) -> str:
    return "" if v is None else str(v).strip()


def wire_kind(raw: str) -> str:
    """`MIX_FIRE` -> `mix.fire`; already-dotted names pass through.

    [WIRE_NAME_OVERRIDES] holds the enum values whose wire name keeps an
    underscore (`TelemetryEventType.wireName` in djmix-core); everything else
    dotted-lowercases mechanically.
    """
    override = WIRE_NAME_OVERRIDES.get(raw)
    if override:
        return override
    if raw.startswith("MIX_"):
        return "mix." + raw[4:].lower().replace("_", ".")
    return raw


def normalise_v3(o: Dict[str, Any]) -> Rec:
    fx = o.get("fxTypes") or o.get("fx") or []
    if isinstance(fx, str):
        fx = [s for s in fx.replace(";", ",").split(",") if s.strip()]
    gen = o.get("generation")
    kind = wire_kind(text(o.get("eventType")))
    return Rec(
        kind=kind,
        ts=int(num(o.get("timestampMs")) or 0),
        schema="v3",
        plan_id=text(o.get("planId")) or None,
        generation=int(gen) if num(gen) is not None else None,
        state=text(o.get("state")),
        out_id=text(o.get("outId")),
        in_id=text(o.get("inId")),
        level=text(o.get("djLevel")).upper() or None,
        path=text(o.get("path")) or None,
        recipe=text(o.get("recipeId")) or None,
        fx=[text(x) for x in fx if text(x)],
        analysis_ms=num(o.get("analysisMs")),
        plan_ms=num(o.get("planMs")),
        stem_out_ms=num(o.get("stemMsOut")),
        stem_in_ms=num(o.get("stemMsIn")),
        build_ms=num(o.get("buildMs")),
        # The v3 MIX_COUNTDOWN_BROKEN event may carry countdownBroken=null; the
        # event type itself is the incident, exactly like the legacy ev name.
        countdown_broken=o.get("countdownBroken") is True or kind == "mix.countdown.broken",
        reason=text(o.get("reason")) or None,
        detail=text(o.get("detail")),
        stress=o.get("stress") is True or "stress" in text(o.get("detail")).lower(),
        exit_vocal=num(o.get("exitVocal") if o.get("exitVocal") is not None else o.get("syvenuAtExit")),
        raw=o,
    )


def normalise_legacy(o: Dict[str, Any]) -> Rec:
    fx = o.get("fx") or o.get("fxTypes") or []
    if isinstance(fx, str):
        fx = [s for s in fx.replace(";", ",").split(",") if s.strip()]
    ev = text(o.get("ev"))
    # vc94 fire records name the recipe in `choreo` ("F1:Riser drop"); timing records use `move`.
    recipe = text(o.get("recipeId")) or text(o.get("choreo")) or text(o.get("move")) or None
    level = text(o.get("level")).upper() or None
    # `mix.timing.watchdog` / countdown records carry the broken flag under
    # different names across builds; accept all of them.
    broken = ev == "mix.countdown.broken" or o.get("countdownBroken") is True
    return Rec(
        kind=ev,
        ts=int(num(o.get("ts")) or 0),
        schema="legacy",
        plan_id=text(o.get("planId")) or None,
        generation=None,
        state=text(o.get("phase")),
        out_id=text(o.get("outId")),
        in_id=text(o.get("inId")),
        level=level,
        path=text(o.get("path")) or None,
        recipe=recipe,
        fx=[text(x) for x in fx if text(x)],
        analysis_ms=num(o.get("analysisMs")),
        plan_ms=num(o.get("planMs")),
        stem_out_ms=num(o.get("stemMsOut")),
        stem_in_ms=num(o.get("stemMsIn")),
        build_ms=num(o.get("buildMs")),
        countdown_broken=broken,
        reason=text(o.get("reason") or o.get("fallbackReason")) or None,
        detail=text(o.get("detail") or o.get("ep")),
        stress="stress" in text(o.get("detail")).lower() or o.get("stress") is True,
        exit_vocal=num(o.get("exitVocal") if o.get("exitVocal") is not None else o.get("syvenuAtExit")),
        raw=o,
    )


def load_jsonl(path: Path) -> Tuple[List[Rec], int]:
    recs: List[Rec] = []
    bad = 0
    with path.open("r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                bad += 1
                continue
            if not isinstance(o, dict):
                bad += 1
                continue
            if o.get("schema") == 3 or "eventType" in o:
                recs.append(normalise_v3(o))
            elif "ev" in o:
                recs.append(normalise_legacy(o))
            else:
                bad += 1
    recs.sort(key=lambda r: (r.ts, r.kind))
    return recs, bad


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------

def pct(vals: List[float], q: float) -> Optional[float]:
    if not vals:
        return None
    s = sorted(vals)
    pos = (len(s) - 1) * q
    lo, hi = math.floor(pos), math.ceil(pos)
    return s[lo] if lo == hi else s[lo] + (s[hi] - s[lo]) * (pos - lo)


def transitions(recs: List[Rec]) -> List[Rec]:
    """Attempts: one per prepared transition (v3 prep start, else legacy mix.timing).

    On a merged v3+legacy stream both dialects describe the same preparation,
    so a legacy timing that duplicates a v3 prep for the same pair (within
    [ATTEMPT_MATCH_WINDOW_MS], generous because the legacy record lands at
    fire time while the v3 one lands at prep start) is dropped.
    """
    v3 = [r for r in recs if r.schema == "v3" and r.kind == "mix.prep.started"]
    legacy = [r for r in recs if r.schema == "legacy" and r.kind == "mix.timing"]
    if not v3 or not legacy:
        return v3 + legacy
    used: Set[int] = set()
    for r in v3:
        best, best_dt = None, None
        for i, l in enumerate(legacy):
            if i in used or l.out_id != r.out_id or l.in_id != r.in_id:
                continue
            dt = abs(l.ts - r.ts)
            if dt > ATTEMPT_MATCH_WINDOW_MS:
                continue
            if best is None or dt < best_dt:
                best, best_dt = i, dt
        if best is not None:
            used.add(best)
    return v3 + [l for i, l in enumerate(legacy) if i not in used]


def fires(recs: List[Rec]) -> List[Rec]:
    """Fired transitions: one entry per fire, across dialects.

    On a merged stream each v3 fire absorbs the nearest same-pair legacy fire
    within [FIRE_MATCH_WINDOW_MS] — inheriting `fx` and `exitVocal`, which the
    v3 schema cannot carry — and the absorbed legacy record is dropped.
    Unmatched legacy fires stay, so legacy-only input is unchanged.
    """
    v3 = [r for r in recs if r.schema == "v3" and r.kind == "mix.fire"]
    legacy = [r for r in recs if r.schema == "legacy" and r.kind == "mix.fire"]
    if not v3 or not legacy:
        return v3 + legacy
    used: Set[int] = set()
    for r in v3:
        best, best_dt = None, None
        for i, l in enumerate(legacy):
            if i in used or l.out_id != r.out_id or l.in_id != r.in_id:
                continue
            dt = abs(l.ts - r.ts)
            if dt > FIRE_MATCH_WINDOW_MS:
                continue
            if best is None or dt < best_dt:
                best, best_dt = i, dt
        if best is not None:
            used.add(best)
            l = legacy[best]
            if not r.fx and l.fx:
                r.fx = list(l.fx)
            if r.exit_vocal is None and l.exit_vocal is not None:
                r.exit_vocal = l.exit_vocal
    return v3 + [l for i, l in enumerate(legacy) if i not in used]


def cross_dialect_count(recs: List[Rec], window_ms: int = INCIDENT_MATCH_WINDOW_MS) -> int:
    """Count records of one incident across dialects without double counting.

    A merged stream writes the same incident (a `mix.moved`, a broken
    countdown, ...) once per dialect within milliseconds; a legacy record
    within [window_ms] of any v3 one is treated as its duplicate.
    """
    if not recs:
        return 0
    v3 = [r for r in recs if r.schema == "v3"]
    legacy = [r for r in recs if r.schema == "legacy"]
    if not v3 or not legacy:
        return len(recs)
    kept = [l for l in legacy if not any(abs(l.ts - r.ts) <= window_ms for r in v3)]
    return len(v3) + len(kept)


def frozen(r: Rec) -> Optional[bool]:
    if r.path is None:
        return None
    return r.path in FROZEN_PATHS


def ready_latencies(recs: List[Rec]) -> List[float]:
    """Next-known -> READY seconds, keyed by planId (v3 only; legacy has no READY)."""
    start: Dict[str, int] = {}
    out: List[float] = []
    for r in recs:
        if r.schema != "v3" or not r.plan_id:
            continue
        if r.kind == "mix.prep.started":
            start.setdefault(r.plan_id, r.ts)
        elif r.kind == "mix.ready" and r.plan_id in start:
            out.append((r.ts - start.pop(r.plan_id)) / 1000.0)
    return out


# ---------------------------------------------------------------------------
# Recipe catalog (FX derivation for records without an fx list)
# ---------------------------------------------------------------------------

def default_recipe_catalog() -> Path:
    """Bundled RecipeV2 JSONs in the repo the analyzer ships in."""
    return Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "recipes"


def load_recipe_fx(catalog_dir: Path) -> Dict[str, Set[str]]:
    """recipeId -> FX types the recipe uses, over `steps` + `noStemSteps`.

    Mirrors `direct_fx()` in tools/recipes/lint_recipes.py: a `configure_fx`
    step contributes its `fx`; a `lane_curve` of kind `HIGH_PASS_HZ`,
    `LOW_PASS_HZ` or `FILTER_KNOB` contributes `SWEEP`. Malformed files are
    skipped, never fatal.
    """
    out: Dict[str, Set[str]] = {}
    if not catalog_dir.is_dir():
        return out
    for path in sorted(catalog_dir.glob("*.json")):
        try:
            recipe = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError, UnicodeDecodeError):
            continue
        if not isinstance(recipe, dict):
            continue
        rid = text(recipe.get("id"))
        if not rid:
            continue
        fx: Set[str] = set()
        for variant in ("steps", "noStemSteps"):
            steps = recipe.get(variant)
            if not isinstance(steps, list):
                continue
            for step in steps:
                if not isinstance(step, dict):
                    continue
                if step.get("type") == "configure_fx" and isinstance(step.get("fx"), str):
                    fx.add(text(step["fx"]))
                elif step.get("type") == "lane_curve" and step.get("kind") in (
                    "HIGH_PASS_HZ", "LOW_PASS_HZ", "FILTER_KNOB",
                ):
                    fx.add("SWEEP")
        out[rid] = fx
    return out


def summarise(
    recs: List[Rec],
    bad_lines: int,
    source: str,
    recipe_fx: Optional[Dict[str, Set[str]]] = None,
) -> Dict[str, Any]:
    att = transitions(recs)
    fi = fires(recs)
    # Fires that carry a recipe id but no FX list (the v3 schema has no fx
    # field) get their FX derived from the recipe catalog, so the Festival
    # distinct-FX §10 row is measurable from v3-only telemetry.
    fx_derived = 0
    if recipe_fx:
        for r in fi:
            if not r.fx and r.recipe:
                rid = r.recipe.split(":", 1)[0].strip()
                catalog_fx = recipe_fx.get(rid)
                if catalog_fx:
                    r.fx = sorted(catalog_fx)
                    fx_derived += 1
    n_att = len(att) or len(fi)
    overlap = len(fi)
    # Transitions that reached a fire are audible overlaps; attempts that never
    # fired (failed / suspended without a fire for the same plan) count against it.
    overlap_pct = 100.0 * min(overlap, n_att) / n_att if n_att else None

    broken = cross_dialect_count([r for r in recs if r.countdown_broken])
    moved = cross_dialect_count([r for r in recs if r.kind == "mix.moved"])
    last_resort = cross_dialect_count([r for r in recs if r.kind == "mix.last_resort"])

    analysis_cpu, analysis_npu = [], []
    for r in recs:
        if r.analysis_ms is None or r.kind not in ("mix.prep.started", "mix.ready", "mix.timing"):
            continue
        ((analysis_npu if "QNN" in r.detail.upper() or "NPU" in r.detail.upper() else analysis_cpu)
         .append(r.analysis_ms))
    stem_windows = [v for r in recs for v in (r.stem_out_ms, r.stem_in_ms) if v and v > 0]

    flags = [frozen(r) for r in att + fi]
    known = [f for f in flags if f is not None]
    frozen_pct = 100.0 * sum(known) / len(known) if known else None

    exits = [r.exit_vocal for r in fi if r.exit_vocal is not None]
    exit_mid_vocal = sum(1 for v in exits if v > 0.5) if exits else None

    ready = ready_latencies(recs)

    def level_block(
        level: Optional[str],
        subset: List[Rec],
        attempts: List[Rec],
        fired: List[Rec],
    ) -> Dict[str, Any]:
        n = len(attempts) or len(fired)
        kn = [frozen(r) for r in attempts + fired if frozen(r) is not None]
        window = fired[:FESTIVAL_WINDOW]
        return {
            "level": level,
            "attempts": n,
            "fires": len(fired),
            "overlap_pct": 100.0 * min(len(fired), n) / n if n else None,
            "frozen_pct": 100.0 * sum(kn) / len(kn) if kn else None,
            "countdown_broken": sum(1 for r in subset if r.countdown_broken),
            "distinct_recipes": len({r.recipe for r in fired if r.recipe}),
            "distinct_fx": len({x for r in fired for x in r.fx}),
            "first12_distinct_recipes": len({r.recipe for r in window if r.recipe}),
            "first12_distinct_fx": len({x for r in window for x in r.fx}),
        }

    by_level = {}
    for lvl in LEVELS:
        by_level[lvl] = level_block(
            lvl,
            [r for r in recs if r.level == lvl],
            [r for r in att if r.level == lvl],
            [r for r in fi if r.level == lvl],
        )
    stress = [r for r in recs if r.stress]
    natural = [r for r in recs if not r.stress]
    fest = [r for r in fi if r.level == "FESTIVAL"]
    fest12 = fest[:FESTIVAL_WINDOW]

    return {
        "source": source,
        "records": len(recs),
        "bad_lines": bad_lines,
        "schema_counts": dict(Counter(r.schema for r in recs)),
        "event_counts": dict(Counter(r.kind for r in recs).most_common()),
        "engines": sorted({text(r.raw.get("engine")) for r in recs if r.raw.get("engine")}),
        "transitions": n_att,
        "fires": len(fi),
        "overlap_pct": overlap_pct,
        "countdown_broken": broken,
        "ready_p50_s": pct(ready, 0.5),
        "ready_p95_s": pct(ready, 0.95),
        "ready_n": len(ready),
        "analysis_cpu_p50_ms": pct(analysis_cpu, 0.5),
        "analysis_cpu_p95_ms": pct(analysis_cpu, 0.95),
        "analysis_npu_p50_ms": pct(analysis_npu, 0.5),
        "analysis_cpu_n": len(analysis_cpu),
        "analysis_npu_n": len(analysis_npu),
        "stem_p50_ms": pct(stem_windows, 0.5),
        "stem_p95_ms": pct(stem_windows, 0.95),
        "stem_windows": len(stem_windows),
        "frozen_pct": frozen_pct,
        "exit_mid_vocal": exit_mid_vocal,
        "exit_vocal_samples": len(exits),
        "mix_moved": moved,
        "last_resort": last_resort,
        "festival_recipes_first12": len({r.recipe for r in fest12 if r.recipe}),
        "festival_fx_first12": len({x for r in fest12 for x in r.fx}),
        "festival_recipes_all": len({r.recipe for r in fest if r.recipe}),
        "festival_fx_all": len({x for r in fest for x in r.fx}),
        "fx_derived_from_recipes": fx_derived,
        "recipe_fx_recipes": len(recipe_fx) if recipe_fx else 0,
        "levels": by_level,
        "stress": level_block(
            "STRESS", stress, [r for r in att if r.stress], [r for r in fi if r.stress],
        ),
        "natural": level_block(
            "NATURAL", natural, [r for r in att if not r.stress], [r for r in fi if not r.stress],
        ),
    }


def load_before(path: Path) -> Dict[str, Any]:
    """Baseline: a metrics JSON (m0-metrics.json style) or a telemetry JSONL."""
    if path.suffix.lower() == ".json":
        m = json.loads(path.read_text(encoding="utf-8"))
        ap = m.get("analysis_ms_per_track_telemetry", {}) or {}
        sw = m.get("stem_ms_per_window", {}) or {}
        return {
            "source": str(path),
            "transitions": m.get("transitions_mix_timing"),
            "fires": m.get("mix_fire"),
            "overlap_pct": num(m.get("audible_overlap_pct_of_fires_PROXY")),
            "countdown_broken": m.get("countdown_broken"),
            "ready_p50_s": None,
            "ready_p95_s": None,
            "analysis_cpu_p50_ms": num(ap.get("p50")),
            "analysis_cpu_p95_ms": num(ap.get("p95")),
            "stem_p50_ms": num(sw.get("out_p50")),
            "frozen_pct": num(m.get("path_preparer_frozen_pct")),
            "exit_mid_vocal": None,
            "mix_moved": m.get("mix_moved_count"),
            "last_resort": m.get("last_resort_count"),
            "festival_recipes_first12": m.get("festival_distinct_recipes"),
            "festival_fx_first12": m.get("festival_distinct_fx_types"),
            "levels": {},
        }
    recs, bad = load_jsonl(path)
    return summarise(recs, bad, str(path))


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def fmt(v: Any, digits: int = 1, suffix: str = "") -> str:
    if v is None:
        return "n/a"
    if isinstance(v, float):
        return f"{v:.{digits}f}{suffix}"
    return f"{v}{suffix}"


def cell(v: Any, digits: int = 1, suffix: str = "") -> str:
    """A Markdown table cell only ever shows a number.

    Baseline JSON (for example `evidence/ae3-m0/m0-metrics.json`) carries prose
    where a metric is absent, such as `"n/a (no mix.countdown.* events ...)"`.
    Printing that verbatim breaks the table, so anything non-numeric renders as
    `n/a`; the untouched string stays in the JSON sidecar.
    """
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return "n/a"
    return fmt(v, digits, suffix)


def npu_status(after: Dict[str, Any]) -> str:
    npu = after["analysis_npu_p50_ms"]
    cpu = after["analysis_cpu_p50_ms"]
    if npu is None or cpu is None:
        return "not measured"
    return "PASS" if npu < cpu else "FAIL"


def gate_status(key: str, value: Any) -> str:
    if value is None:
        return "not measured"
    if key == "overlap_pct":
        return "PASS" if value >= 100.0 else "FAIL"
    if key == "countdown_broken":
        return "PASS" if value == 0 else "FAIL"
    if key == "ready_p50_s":
        return "PASS" if value <= 6 else "FAIL"
    if key == "ready_p95_s":
        return "PASS" if value <= 15 else "FAIL"
    if key == "analysis_cpu_p95_ms":
        return "PASS" if value <= 1500 else "FAIL"
    if key == "stem_p95_ms":
        return "PASS" if value <= 8000 else "FAIL"
    if key == "frozen_pct":
        return "PASS" if value >= 100.0 else "FAIL"
    if key == "exit_mid_vocal":
        return "PASS" if value == 0 else "FAIL"
    if key in ("festival_recipes", "festival_fx"):
        return "PASS" if value >= 7 else "FAIL"
    return "?"


def gate_value(key: str, s: Dict[str, Any]) -> Any:
    return {
        "overlap_pct": s.get("overlap_pct"),
        "countdown_broken": s.get("countdown_broken"),
        "ready_p50_s": s.get("ready_p50_s"),
        "ready_p95_s": s.get("ready_p95_s"),
        "analysis_cpu_p95_ms": s.get("analysis_cpu_p95_ms"),
        "stem_p95_ms": s.get("stem_p95_ms"),
        "frozen_pct": s.get("frozen_pct"),
        "exit_mid_vocal": s.get("exit_mid_vocal"),
        "festival_recipes": s.get("festival_recipes_first12"),
        "festival_fx": s.get("festival_fx_first12"),
    }[key]


def before_value(key: str, b: Dict[str, Any]) -> Any:
    m = {
        "overlap_pct": "overlap_pct",
        "countdown_broken": "countdown_broken",
        "ready_p50_s": "ready_p50_s",
        "ready_p95_s": "ready_p95_s",
        "analysis_cpu_p95_ms": "analysis_cpu_p95_ms",
        "stem_p95_ms": "stem_p95_ms",
        "frozen_pct": "frozen_pct",
        "exit_mid_vocal": "exit_mid_vocal",
        "festival_recipes": "festival_recipes_first12",
        "festival_fx": "festival_fx_first12",
    }
    return b.get(m[key])


def render_markdown(after: Dict[str, Any], before: Optional[Dict[str, Any]]) -> str:
    L: List[str] = []
    L.append("# DJMIX53 results (Part A §10)")
    L.append("")
    L.append(f"- After source: `{after['source']}`")
    L.append(f"- Records: {after['records']} (bad lines skipped: {after['bad_lines']}); "
             f"schemas: {after['schema_counts']}; engines: {after['engines'] or 'n/a'}")
    if before:
        L.append(f"- Before source: `{before['source']}`")
    L.append("")
    L.append("## Results table")
    L.append("")
    L.append("| Metric | Target | Before (djmix52) | After (djmix53) | Status |")
    L.append("|---|---|---|---|---|")
    for key, (label, target) in GATES.items():
        av = gate_value(key, after)
        bv = before_value(key, before) if before else None
        if key == "stem_p95_ms":
            av_str = fmt(av, 0, " ms")
        elif key.endswith("_ms"):
            av_str = fmt(av, 0, " ms")
        elif key.endswith("_s"):
            av_str = fmt(av, 2, " s")
        elif key.endswith("pct"):
            av_str = fmt(av, 1, " %")
        else:
            av_str = fmt(av, 0)
        bv_str = cell(bv, 1)
        L.append(f"| {label} | {target} | {bv_str} | {av_str} | {gate_status(key, av)} |")
    npu = after["analysis_npu_p50_ms"]
    cpu = after["analysis_cpu_p50_ms"]
    npu_gate = npu_status(after)
    L.append(f"| Analysis ms per track p50, NPU vs CPU | NPU lower than CPU | "
             f"CPU {cell(before.get('analysis_cpu_p50_ms') if before else None, 0)} | "
             f"NPU {fmt(npu, 0, ' ms')} (n={after['analysis_npu_n']}) / CPU {fmt(cpu, 0, ' ms')} "
             f"(n={after['analysis_cpu_n']}) | {npu_gate} |")
    L.append(f"| `mix.moved` count | report | {cell(before.get('mix_moved') if before else None, 0)} | "
             f"{after['mix_moved']} | report |")
    L.append(f"| Last-resort count | report | {cell(before.get('last_resort') if before else None, 0)} | "
             f"{after['last_resort']} | report |")
    L.append(f"| Transitions (attempts / fired) | report | {cell(before.get('transitions') if before else None, 0)} | "
             f"{after['transitions']} / {after['fires']} | report |")
    L.append("")
    L.append("Notes:")
    L.append("- Overlap % = fired transitions / attempted transitions (prep started, or legacy `mix.timing`).")
    L.append("- Frozen % counts `PREPARER_FROZEN` / `PREPARER_NO_STEM_FALLBACK` (v3) and `v2-native`/`v2-straight` (legacy).")
    L.append("- Exit mid-vocal needs Syvenu values on fire records (`exitVocal` / `syvenuAtExit`); "
             "`n/a` means the field was absent.")
    L.append("- Festival distinct recipes / FX use the first 12 Festival fires; `all` totals are in the JSON sidecar.")
    if after.get("fx_derived_from_recipes"):
        L.append(f"- {after['fx_derived_from_recipes']} fire record(s) had FX derived from the recipe catalog "
                 f"({after.get('recipe_fx_recipes', 0)} recipes; the v3 schema carries no fx field).")
    L.append("")
    L.append("## Per level")
    L.append("")
    L.append("| Level | Attempts | Fires | Overlap % | Frozen % | Countdown broken | Recipes (first 12) | FX types (first 12) |")
    L.append("|---|---|---|---|---|---|---|---|")
    for lvl in LEVELS:
        b = after["levels"].get(lvl, {})
        L.append(f"| {lvl} | {b.get('attempts', 0)} | {b.get('fires', 0)} | {fmt(b.get('overlap_pct'), 1)} | "
                 f"{fmt(b.get('frozen_pct'), 1)} | {b.get('countdown_broken', 0)} | "
                 f"{b.get('first12_distinct_recipes', 0)} | {b.get('first12_distinct_fx', 0)} |")
    L.append("")
    L.append("## Natural vs stress")
    L.append("")
    L.append("| Group | Attempts | Fires | Overlap % | Frozen % | Countdown broken |")
    L.append("|---|---|---|---|---|---|")
    for key in ("natural", "stress"):
        b = after[key]
        L.append(f"| {key} | {b['attempts']} | {b['fires']} | {fmt(b['overlap_pct'], 1)} | "
                 f"{fmt(b['frozen_pct'], 1)} | {b['countdown_broken']} |")
    L.append("")
    L.append("## Event counts")
    L.append("")
    L.append("| Event | Count |")
    L.append("|---|---|")
    for k, v in after["event_counts"].items():
        L.append(f"| `{k}` | {v} |")
    L.append("")
    return "\n".join(L)


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--after", required=True, type=Path, help="v3 (or legacy) telemetry JSONL to score")
    ap.add_argument("--before", type=Path, help="baseline metrics JSON or telemetry JSONL")
    ap.add_argument("--out", type=Path, help="Markdown results file (also writes <out>.json)")
    ap.add_argument("--recipes", type=Path, default=None,
                    help="recipe catalog dir used to derive FX types for fire records that "
                         "carry a recipe id but no fx list (default: the repo's "
                         "app/src/main/assets/recipes; missing dir disables derivation)")
    ap.add_argument("--strict", action="store_true",
                    help="exit 1 when any Part A §10 gate fails or was not measured")
    args = ap.parse_args(argv)

    if not args.after.is_file():
        print(f"error: not a file: {args.after}", file=sys.stderr)
        return 2
    recs, bad = load_jsonl(args.after)
    if not recs:
        print(f"error: no telemetry records in {args.after} (bad lines: {bad})", file=sys.stderr)
        return 2
    catalog_dir = args.recipes or default_recipe_catalog()
    recipe_fx = load_recipe_fx(catalog_dir)
    if args.recipes and not recipe_fx:
        print(f"warning: no recipes loaded from {catalog_dir}; FX derivation disabled", file=sys.stderr)
    after = summarise(recs, bad, str(args.after), recipe_fx=recipe_fx)

    before = None
    if args.before:
        if not args.before.is_file():
            print(f"error: not a file: {args.before}", file=sys.stderr)
            return 2
        before = load_before(args.before)

    md = render_markdown(after, before)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(md + "\n", encoding="utf-8")
        args.out.with_suffix(args.out.suffix + ".json").write_text(
            json.dumps({"after": after, "before": before}, indent=2, default=str) + "\n", encoding="utf-8")
    else:
        print(md)

    failures = [k for k in GATES if gate_status(k, gate_value(k, after)) == "FAIL"]
    unmeasured = [k for k in GATES if gate_status(k, gate_value(k, after)) == "not measured"]
    if npu_status(after) == "FAIL":
        failures.append("npu_vs_cpu")
    elif npu_status(after) == "not measured":
        unmeasured.append("npu_vs_cpu")
    print(f"analyze_telemetry_v3: records={after['records']} transitions={after['transitions']} "
          f"fires={after['fires']} gate_failures={len(failures)} "
          f"gate_unmeasured={len(unmeasured)}", file=sys.stderr)
    if args.strict:
        if failures:
            print("strict: failed: " + ", ".join(failures), file=sys.stderr)
        if unmeasured:
            print("strict: no data: " + ", ".join(unmeasured), file=sys.stderr)
        # A gate with no data cannot pass: --strict only succeeds when every
        # Part A §10 metric was measured and met.
        if failures or unmeasured:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
