"""Data-driven synthetic fixtures, never a production planner/exporter.

Reuse the recipe linter's anchor/lane simulation for synthetic smoke coverage.
Real audio must arrive as a JVM compileFrozen export (recipe_catalog.py).
"""

from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "recipes"))
import lint_recipes as lint  # noqa: E402

from recipe_catalog import resolve_recipe

SR = 48_000
# These defaults are only fixture parameter filling; authored RecipeV2 params win.
FX = {
    "LOOP": ("loop", {"bars": 4, "seamCrossfadeMs": 8.0, "holdPhrases": 0}),
    "ROLL": ("roll", {"totalBeats": 8.0, "scheduleBeats": [4.0, 2.0, 1.0, 0.5, 0.25], "hpfStartHz": 200.0, "hpfEndHz": 2000.0, "boostDb": 2.0, "stopOnDownbeat": True}),
    "ECHO": ("echo", {"delayBeats": 0.5, "feedback": 0.6, "wet": 0.55, "wetHpfHz": 250.0, "wetLpfHz": 6000.0, "pingPong": False}),
    "REVERB": ("reverb", {"roomSize": 2.2, "damping": 0.55, "wet": 0.35}),
    "FLANGER": ("flanger", {"lfoCyclesPerBars": 2.0, "depthSec": 0.003, "feedback": 0.64, "wetPeak": 0.6, "stereoOffsetDeg": 90.0}),
    "PHASER": ("phaser", {"stages": 6, "rateBeats": 4.0, "depth": 0.7, "feedback": 0.6, "wetPeak": 0.7, "stereoOffsetDeg": 90.0}),
    "RISER": ("riser", {"lengthBeats": 16.0, "noiseSweepStartHz": 300.0, "noiseSweepEndHz": 8000.0, "noiseLevelPeak": 0.7, "uplifterOctaves": 1.0, "includeSnareRoll": True, "reverseReverbTailBeats": 1.0}),
    "IMPACT": ("impact", {"peakDbfs": -6.0, "lowThumpHz": 55.0, "noiseBurstMs": 180.0}),
    "GATE": ("gate", {"divisionStepsPerBeat": 4.0, "swing": 0.0, "isStutter": False, "depthPeak": 1.0, "rampBars": 2.0}),
    "PUMP": ("pump", {"fullBand": False}),
    "BRAKE": ("brake", {"endRate": 0.05, "lpfStartHz": 18000.0, "lpfEndHz": 800.0, "endFadeMs": 10.0}),
    "TAPE_STOP": ("tapeStop", {"wowDepth": 0.03, "wowRateHz": 5.0, "lpfStartHz": 18000.0, "lpfEndHz": 600.0, "endFadeMs": 10.0}),
    "SPINBACK": ("spinback", {"peakReverseSpeed": 3.0, "silenceGapBeats": 1.0}),
    "SWEEP": ("sweep", {"startHz": 20.0, "endHz": 1500.0, "qStart": 0.707, "qEnd": 1.2, "bars": 8}),
}


def build_fixture_script(pair: dict, requested: str, auto_picks: dict, rate_in: float) -> dict:
    if not pair.get("synthetic"):
        raise ValueError("Python fixture compilation requires synthetic=true; use a canonical JVM export for real audio")
    rid, recipe = resolve_recipe(requested, auto_picks)
    if not pair.get("outF32") or not pair.get("inF32"):
        raise ValueError("Synthetic script export requires raw f32 PCM; decode WAVs before rendering")
    bar = 240.0 / pair["bpmOut"]
    fade = float(pair.get("fadeSec", 4.0))
    start = max(0.0, pair["exitSec"] - fade / 2)
    drop = recipe["placementPrefs"].get("preferDropEntry", False) or recipe["placementPrefs"].get("swapRule", "").startswith("DROP")
    swap = fade if drop else fade / 2
    stop = fade + 0.2
    ctx = lint.Context(fade, swap, 0.0, stop, bar)
    missing = frozenset(pair.get("missingCapabilities", []))
    # Synthetic tracks carry no separated stems. Exercise the same ID's EQ variant.
    lanes, _ = lint.compile_lanes(recipe, "noStemSteps", ctx, missing)
    envelopes = [{"deck": deck, "kind": kind,
                  "timesSec": [start + t for t, _ in lane.points],
                  "values": [v for _, v in lane.points], "exponential": lane.exponential}
                 for (deck, kind), lane in lanes.items()]
    fx = {"isolate": any(s["type"] == "enable_isolator" for s in recipe["noStemSteps"])}
    for step in recipe["noStemSteps"]:
        if step["type"] != "configure_fx":
            continue
        active = step.get("fallbackFx") if step.get("requiredCap") in missing else step["fx"]
        if active is None:
            continue
        if active == "SVF_Q":
            fx["outSvfQ" if step["deck"] == "OUT" else "inSvfQ"] = step.get("params", {}).get("q", 0.9)
            continue
        if active == "LOOP_ROLL":
            active = "ROLL"
        key, defaults = FX[active]
        params = deepcopy(defaults)
        # Fallbacks intentionally use the target processor's defaults for unrelated
        # source parameters (e.g. RISER->FLANGER), as the Kotlin interpreter does.
        params.update({k: v for k, v in step.get("params", {}).items() if k in defaults})
        params.update(enabled=True, deck=step["deck"])
        if active == "LOOP":
            del params["deck"]  # LoopFxParams is a global loop declaration.
        for name in ("pingPong", "includeSnareRoll", "stopOnDownbeat", "isStutter", "fullBand"):
            if name in params:
                params[name] = bool(params[name]) or bool(step.get("flag", False) and name in {"pingPong", "isStutter", "fullBand"})
        for name in ("bars", "holdPhrases", "stages"):
            if name in params:
                params[name] = int(params[name])
        for name in ("wet", "wetPeak", "depthPeak", "noiseLevelPeak"):
            if name in params:
                params[name] *= lint.DEPTH[recipe["level"]]
        if active == "PHASER":
            params["depth"] *= lint.DEPTH[recipe["level"]]
        if active in {"RISER", "GATE", "PUMP", "ROLL"}:
            params["beatSec"] = bar / 4
        if active == "ECHO":
            params["delaySec"] = params["delayBeats"] * bar / 4
        if active == "FLANGER":
            params["lfoHz"] = 1.0 / (params["lfoCyclesPerBars"] * bar)
        if active == "IMPACT":
            params["triggerMixSec"] = start + ctx.resolve(step.get("startTime", {"anchor": "SWAP"}), step["deck"])
        if active in {"ROLL", "BRAKE", "TAPE_STOP", "SPINBACK", "SWEEP"}:
            default_start = max(0.0, swap - (2 * bar if active in {"ROLL", "SWEEP"} else 1.5 * bar / 4))
            t0 = ctx.resolve(step["startTime"], step["deck"]) if step.get("startTime") else default_start
            t1 = ctx.resolve(step["endTime"], step["deck"]) if step.get("endTime") else swap
            params["startMixSec"] = start + t0
            if active == "ROLL":
                params["releaseMixSec"] = start + t1
            elif active == "SWEEP":
                params.update(endMixSec=start + t1, isHighPass=not step.get("flag", False))
            else:
                params["lengthSec"] = max(0.05, t1 - t0)
        fx["incomingSweep" if active == "SWEEP" and step["deck"] == "IN" else key] = params

    release = start + fade
    out_stop = min(pair["outDurationSec"], start + stop)
    entry = float(pair.get("entrySec", 0.0))
    handover_track = entry + fade * rate_in
    regions, setups = {}, {}
    for deck, label, duration in (("OUT", "out", pair["outDurationSec"]), ("IN", "in", pair["inDurationSec"])):
        count = int(pair.get(f"{label}FrameCount", round(duration * SR)))
        incoming = deck == "IN"
        regions[label] = {"deck": deck, "trackId": pair[f"{label}TrackId"], "startMs": 0,
                          "endMs": round(duration * 1000), "sampleRateHz": SR,
                          "pcmPath": str(Path(pair[f"{label}F32"]).resolve()), "prerollMs": 25,
                          "stopAtMixMs": round((release if incoming else out_stop) * 1000),
                          "fadeOutTrackMs": round(handover_track * 1000) if incoming else None,
                          "levelGain": 1.0, "runOn": incoming}
        setups[label] = {"regionStartSec": 0.0, "frameCount": count,
                         "loopStartFrame": 0, "loopEndFrame": 0, "releaseAtFrame": None,
                         "knotFrames": [start * SR, (start + max(fade, duration / rate_in)) * SR] if incoming else [0.0, duration * SR],
                         "knotPositions": [entry * SR, entry * SR + max(fade, duration / rate_in) * rate_in * SR] if incoming else [0.0, duration * SR],
                         "runOn": incoming, "stopAtMixSec": release if incoming else out_stop,
                         "prerollSec": 0.025, "levelGain": 1.0,
                         "fadeOutTrackSec": handover_track if incoming else None}
    if "loop" in fx:
        loop_end = min(round(pair["exitSec"] * SR), setups["out"]["frameCount"])
        setups["out"].update(loopStartFrame=max(0, loop_end - round(fx["loop"]["bars"] * bar * SR)),
                             loopEndFrame=loop_end, releaseAtFrame=release * SR)
    return {"schema": 1, "planId": f"synthetic-{pair['pairId']}-{rid}", "generation": 1,
            "sampleRateHz": SR, "outRegion": regions["out"], "inRegion": regions["in"],
            "knots": [], "loopBounds": None, "renderGeometry": {
                "out": setups["out"], "inbound": setups["in"], "handoverMixSec": release,
                "handoverTrackSec": handover_track, "incomingStartMixSec": start,
                "incomingStartTrackSec": entry, "highQuality": True},
            "envelopes": envelopes, "fxParams": fx, "stems": [],
            "loudness": {"enabled": True, "targetLufs": -14.0, "truePeakLimiter": True,
                         "truePeakCeilingDbtp": -1.0, "dropBumpLu": 0.0},
            "dropWindowMs": {"startMs": round((start + swap) * 1000), "endMs": round((start + swap + bar) * 1000)} if drop else None}
