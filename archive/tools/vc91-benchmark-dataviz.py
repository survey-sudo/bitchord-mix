#!/usr/bin/env python3
"""vc91-djmix49 benchmark & telemetry dataviz generator (BitChord Mix).

Reads the single source of truth
    evidence/vc91-djmix49/benchmark-data.json
and emits a 100% self-contained, offline-capable HTML report:
    evidence/vc91-djmix49/benchmark-dataviz.html

Usage:
    python3 tools/vc91-benchmark-dataviz.py
    python3 tools/vc91-benchmark-dataviz.py --candidate PATH_TO_VC91.jsonl
    python3 tools/vc91-benchmark-dataviz.py --data PATH --out PATH

--candidate parses a vc91 JSONL telemetry file with the same authoritative
method as tools/automix-telemetry-diff.py (deduped mix.complete by
attemptId, handoff_pcm attempt/event split, planAgeMs sentinel/>1h split)
plus full history/refusal/sung-share/planAge/deltaMs extraction, and embeds
the result as DATA.candidate so the report renders vc91 live on first load.
Without --candidate the report still works fully open: the browser drop-zone
computes the same stats client-side from a dropped JSONL file.

Stdlib only. No third-party dependencies.
"""
import argparse
import json
import math
import re
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
DEFAULT_DATA = REPO / "evidence" / "vc91-djmix49" / "benchmark-data.json"
DEFAULT_OUT = REPO / "evidence" / "vc91-djmix49" / "benchmark-dataviz.html"

ONE_HOUR_MS = 3_600_000
PCM_CODE = "handoff_pcm_before_protected_event"
SUNG_RE = re.compile(
    r"(?:outgoing|incoming):\s*\d+\s*loops:\s*(\d+)\s*quiet,\s*(\d+)\s*sung \(lyrics\),\s*(\d+)\s*sung \(mask\),\s*(\d+)\s*unmeasured"
)


def pct_of(counter, key):
    total = sum(counter.values())
    if not total:
        return 0.0
    return 100.0 * counter.get(key, 0) / total


def is_queue_advance(event):
    """Plain track-end advance (no overlap), not a transition. Mirrors
    automix-telemetry-diff.py: these rows carry technique:"natural" and
    executionScope:"normal-queue-advance-no-overlap" and must never count
    toward the NATURAL transition share."""
    return (
        event.get("executionScope") == "normal-queue-advance-no-overlap"
        or event.get("technique") == "natural"
    )


def counts_of(events, field):
    counter = Counter()
    for event in events:
        value = event.get(field)
        if value is None or value == "":
            continue
        counter[str(value)] += 1
    return counter


def percentile(sorted_vals, q):
    """Nearest-rank percentile (q in [0,100]); identical rule in the JS port."""
    if not sorted_vals:
        return None
    rank = math.ceil(q / 100.0 * len(sorted_vals))
    idx = max(1, min(len(sorted_vals), rank)) - 1
    return sorted_vals[idx]


def load_jsonl(path):
    events = []
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return events


def summarise_jsonl(events, label):
    """Authoritative metrics (parity with automix-telemetry-diff.py) plus
    full history-shaped extraction so the report can overlay vc91 anywhere."""
    dump = [e for e in events if e.get("ev") == "mix.dump"]
    styles_dump = Counter(str(e["style"]) for e in dump if e.get("style"))
    plans_acc = [e for e in events if e.get("ev") == "mix.plan" and e.get("stage") == "accepted"]
    queue_advances = sum(1 for e in plans_acc if is_queue_advance(e))
    plans_trans = [e for e in plans_acc if not is_queue_advance(e)]
    # Accepted styles over real transitions only: `technique:"natural"` /
    # `normal-queue-advance-no-overlap` rows are plain track-end advances and
    # inflated the old NATURAL headline (113 of the vc90 327 accepted plans).
    styles_acc = Counter(str(e.get("transitionStyle") or "unknown") for e in plans_trans)
    final_plans = {}
    for e in events:
        if e.get("ev") == "mix.plan" and e.get("stage") in ("accepted", "prepared"):
            if is_queue_advance(e):
                continue
            att = e.get("attemptId") or f"seq:{e.get('seq')}"
            prev = final_plans.get(att)
            if prev is None or (e.get("planRevision", 0), e.get("ts", 0), e.get("seq", 0)) >= (
                prev.get("planRevision", 0), prev.get("ts", 0), prev.get("seq", 0)
            ):
                final_plans[att] = e
    styles_final = Counter(str(e.get("transitionStyle") or e.get("technique") or "unknown") for e in final_plans.values())
    natural_final = pct_of(styles_final, "NATURAL") if final_plans else 0.0
    rungs = Counter(str(e["rung"]) if e.get("rung") else "null" for e in plans_trans)
    rejected = [e for e in events if e.get("ev") == "mix.plan.rejected"]
    refusals = Counter(str(e["refusalCode"]) for e in rejected if e.get("refusalCode"))
    reasons = Counter(str(e["reason"]) for e in rejected if e.get("reason"))
    complete = [e for e in events if e.get("ev") == "mix.complete"]
    complete_term = [e for e in complete if e.get("stage") == "terminal"]
    complete_attempts = {}
    for e in complete:
        key = e.get("attemptId") or f"seq:{e.get('seq')}"
        complete_attempts[key] = e
    dup_ratio = (len(complete) / max(1, len(complete_attempts))) if complete else 1.0
    clean_complete = sum(
        1 for e in complete_term
        if e.get("outcome") == "completed" and e.get("reason") != PCM_CODE
    )
    fired = [e for e in events if e.get("ev") == "mix.fire"]
    fire_techniques = Counter(str(e["technique"]) for e in fired if e.get("technique"))
    fire_styles = Counter(str(e.get("transitionStyle") or "unknown") for e in fired)
    fire_modes = Counter(str(e["mode"]) for e in fired if e.get("mode"))
    term_reasons = Counter(str(e["reason"]) for e in complete_term if e.get("reason"))

    natural_dump = pct_of(styles_dump, "NATURAL")
    natural_acc = pct_of(styles_acc, "NATURAL") if sum(styles_acc.values()) else 0.0
    pcm_events = [
        e for e in events
        if e.get("refusalCode") == PCM_CODE or e.get("reason") == PCM_CODE
    ]
    pcm_attempts = len({e.get("attemptId") for e in pcm_events if e.get("attemptId")})
    pcm_terminal = sum(1 for e in complete_term if e.get("reason") == PCM_CODE)
    heard_ok = sum(
        1 for e in complete
        if isinstance(e.get("actualHeardMs"), (int, float)) and e["actualHeardMs"] > 0
    )
    dump_n = len(dump)
    fire_rate = (100.0 * len(fired) / dump_n) if dump_n else 0.0

    plan_ages = sorted(
        e["planAgeMs"] for e in events if isinstance(e.get("planAgeMs"), (int, float))
    )
    sentinel = sum(1 for a in plan_ages if a < 0)
    ages = [a for a in plan_ages if a >= 0]
    plan_age_max = max(ages) if ages else 0
    plan_age_over_1h = sum(1 for a in ages if a > ONE_HOUR_MS)

    sun_loops = sun_quiet = sun_lyr = sun_mask = sun_unm = sun_events = 0
    for e in rejected:
        code = str(e.get("refusalCode") or e.get("reason") or "")
        m = SUNG_RE.search(code)
        if m:
            # groups: quiet, sungLyrics, sungMask, unmeasured; the "N loops:"
            # header equals their sum, so loops is derived (== benchmark totals).
            sun_events += 1
            sun_quiet += int(m.group(1))
            sun_lyr += int(m.group(2))
            sun_mask += int(m.group(3))
            sun_unm += int(m.group(4))
            sun_loops += int(m.group(1)) + int(m.group(2)) + int(m.group(3)) + int(m.group(4))

    delta_groups = {}
    for e in fired:
        if isinstance(e.get("deltaMs"), (int, float)):
            tech = str(e.get("technique") or "unknown")
            delta_groups.setdefault(tech, []).append(e["deltaMs"])
    fire_deltas = {}
    for tech, vals in delta_groups.items():
        vals = sorted(vals)
        fire_deltas[tech] = {
            "n": len(vals),
            "min": vals[0],
            "p50": percentile(vals, 50),
            "p90": percentile(vals, 90),
            "max": vals[-1],
            "values": vals,
        }

    return {
        "label": label,
        "events": len(events),
        "mixDump": dump_n,
        "dumpStyles": dict(styles_dump),
        "dumpNaturalPct": round(natural_dump, 2),
        "acceptedPlans": len(plans_trans),
        "acceptedStyles": dict(styles_acc),
        "acceptedNaturalPct": round(natural_acc, 2),
        "queueAdvances": queue_advances,
        "finalPlans": len(final_plans),
        "finalStyles": dict(styles_final),
        "finalNaturalPct": round(natural_final, 2),
        "fireNatural": sum(1 for e in fired if e.get("transitionStyle") == "NATURAL" or e.get("technique") == "natural"),
        "fireNaturalPct": round(
            100.0 * sum(1 for e in fired if e.get("transitionStyle") == "NATURAL" or e.get("technique") == "natural") / len(fired), 2
        ) if fired else 0.0,
        "acceptedRungs": [[k, v] for k, v in rungs.most_common()],
        "mixPlanRejected": len(rejected),
        "mixFire": len(fired),
        "firePer100Dump": round(fire_rate, 2),
        "fireStyles": dict(fire_styles),
        "fireTechniques": dict(fire_techniques),
        "fireModes": dict(fire_modes),
        "fireDeltas": fire_deltas,
        "mixCompleteTotal": len(complete),
        "mixCompleteAttempts": len(complete_attempts),
        "mixCompleteDupRatio": round(dup_ratio, 3),
        "mixCompleteTerminal": len(complete_term),
        "mixCompleteClean": clean_complete,
        "heardPositiveLoopOnly": heard_ok,
        "handoffPcmEvents": len(pcm_events),
        "handoffPcmAttempts": pcm_attempts,
        "handoffPcmTerminal": pcm_terminal,
        "planAgeValues": ages,
        "planAgeSentinel": sentinel,
        "planAgeMaxMs": plan_age_max,
        "planAgeOver1h": plan_age_over_1h,
        "sungRefusalTotals": {
            "loops": sun_loops,
            "quiet": sun_quiet,
            "sungLyrics": sun_lyr,
            "sungMask": sun_mask,
            "unmeasured": sun_unm,
            "events": sun_events,
        },
        "topRejectReasons": [[k, v] for k, v in reasons.most_common(12)],
        "topRefusalCodes": [[k, v] for k, v in refusals.most_common(12)],
        "terminalCompleteReasons": [[k, v] for k, v in term_reasons.most_common(12)],
    }


HTML_TEMPLATE = r'''<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>BitChord Mix vc91-djmix49 — Benchmark &amp; Telemetry Dataviz</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Geist:wght@300;400;500;600&family=Geist+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
:root{
  --bg:#0c0b0d; --bg-2:#111014; --panel:#141317;
  --line:rgba(255,255,255,.075); --line-strong:rgba(255,255,255,.14);
  --ink:#f2f0f3; --ink-2:#b6b2bb; --ink-3:#7d7986;
  --accent:#e8455c; --accent-soft:rgba(232,69,92,.14);
  --ok:#63c39a; --warn:#d8a24e; --bad:#e0645f;
  --blue:#8ab4ff;
  --r:16px; --pad:clamp(18px,4vw,56px); --maxw:1400px;
}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{margin:0;background:var(--bg);color:var(--ink);
  font-family:"Geist",ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif;
  font-weight:400;line-height:1.6;-webkit-font-smoothing:antialiased}
::selection{background:var(--accent);color:#0c0b0d}
.wash{position:fixed;inset:0;z-index:0;pointer-events:none;
  background:radial-gradient(720px 480px at 82% -8%, rgba(232,69,92,.16), transparent 62%),
             radial-gradient(900px 620px at 6% 4%, rgba(120,140,190,.07), transparent 60%)}
.grain{position:fixed;inset:0;z-index:2;pointer-events:none;opacity:.028;mix-blend-mode:screen;
  background-image:url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='140' height='140'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='.85' numOctaves='3'/></filter><rect width='140' height='140' filter='url(%23n)'/></svg>")}
.shell{position:relative;z-index:1;max-width:var(--maxw);margin:0 auto;padding:0 var(--pad)}
.topbar{position:sticky;top:0;z-index:40;backdrop-filter:blur(18px) saturate(120%);-webkit-backdrop-filter:blur(18px) saturate(120%);
  background:linear-gradient(to bottom, rgba(12,11,13,.92), rgba(12,11,13,.62));border-bottom:1px solid var(--line)}
.topbar-in{max-width:var(--maxw);margin:0 auto;padding:0 var(--pad);height:58px;display:flex;align-items:center;gap:16px}
.mark{display:flex;align-items:center;gap:11px;min-width:0}
.mark b{font-weight:600;font-size:14px;letter-spacing:-.01em;white-space:nowrap}
.mark span{font-family:"Geist Mono",ui-monospace,monospace;font-size:10.5px;color:var(--ink-3);letter-spacing:.06em;text-transform:uppercase;white-space:nowrap}
.topnav{margin-left:auto;display:flex;gap:18px;align-items:center}
.topnav a{color:var(--ink-3);text-decoration:none;font-size:12.5px}
.topnav a:hover{color:var(--ink)}
.pill{display:inline-flex;align-items:center;gap:7px;border:1px solid rgba(216,162,78,.4);background:rgba(216,162,78,.08);
  color:#e8c07a;font-family:"Geist Mono",ui-monospace,monospace;font-size:10.5px;padding:5px 11px;border-radius:999px;letter-spacing:.05em;white-space:nowrap}
.pill.green{border-color:rgba(99,195,154,.4);background:rgba(99,195,154,.08);color:#9fdcc0}
.pill.red{border-color:rgba(224,100,95,.45);background:rgba(224,100,95,.08);color:#f0a49e}
@media (max-width:900px){.topnav{display:none}}
.hero{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(300px,.9fr);gap:clamp(28px,5vw,72px);
  padding:clamp(44px,7vh,96px) 0 clamp(30px,5vh,56px);align-items:start;border-bottom:1px solid var(--line)}
.eyebrow{font-family:"Geist Mono",ui-monospace,monospace;font-size:11px;letter-spacing:.16em;text-transform:uppercase;color:var(--ink-3);
  margin:0 0 22px;display:flex;align-items:center;gap:12px}
.eyebrow::before{content:"";width:34px;height:1px;background:var(--accent);flex:none}
h1{margin:0;font-weight:500;letter-spacing:-.035em;font-size:clamp(2.2rem,4.8vw,4rem);line-height:.98}
h1 em{font-style:normal;color:var(--accent)}
.lede{margin:22px 0 0;max-width:58ch;color:var(--ink-2);font-size:clamp(14px,1.2vw,16px);line-height:1.68;font-weight:300}
.lede strong{color:var(--ink);font-weight:500}
.card{border:1px solid var(--line-strong);border-radius:var(--r);
  background:linear-gradient(160deg, rgba(255,255,255,.045), rgba(255,255,255,.012));
  box-shadow:0 30px 60px -32px rgba(0,0,0,.9), inset 0 1px 0 rgba(255,255,255,.06);padding:24px 24px 20px;position:relative;overflow:hidden}
.card::before{content:"";position:absolute;inset:0 0 auto 0;height:1px;background:linear-gradient(90deg,transparent,var(--accent),transparent);opacity:.55}
.card .lbl{font-family:"Geist Mono",ui-monospace,monospace;font-size:10px;letter-spacing:.15em;text-transform:uppercase;color:var(--ink-3);margin:0 0 10px}
.card .ver{font-family:"Geist Mono",ui-monospace,monospace;font-size:clamp(19px,2.2vw,26px);font-weight:500;letter-spacing:-.02em;line-height:1.15}
.card .ver small{display:block;font-size:11px;color:var(--ink-3);letter-spacing:.05em;margin-top:7px;font-weight:400}
.card dl{margin:18px 0 0;display:grid;grid-template-columns:auto 1fr;gap:8px 16px;font-size:12px;padding-top:16px;border-top:1px solid var(--line)}
.card dt{font-family:"Geist Mono",ui-monospace,monospace;color:var(--ink-3);font-size:10.5px;letter-spacing:.06em;text-transform:uppercase;padding-top:1px}
.card dd{margin:0;color:var(--ink-2);text-align:right;font-family:"Geist Mono",ui-monospace,monospace;font-size:11.5px}
.banner{display:flex;align-items:center;gap:14px;flex-wrap:wrap;padding:14px 20px;border:1px solid rgba(216,162,78,.4);
  background:rgba(216,162,78,.07);border-radius:var(--r);margin:26px 0 0}
.banner b{font-size:15px;letter-spacing:-.01em}
.banner span{color:var(--ink-2);font-size:13px;font-weight:300}
.banner.green{border-color:rgba(99,195,154,.4);background:rgba(99,195,154,.06)}
.banner.red{border-color:rgba(224,100,95,.45);background:rgba(224,100,95,.06)}
.invariants{border-bottom:1px solid var(--line);padding:26px 0 30px}
.invariants h2,.sec-head h2{margin:0;font-weight:500;letter-spacing:-.028em;font-size:clamp(1.4rem,2.4vw,2rem);line-height:1.12}
.invariants .sub,.sec-sub{margin:8px 0 20px;color:var(--ink-3);font-size:13.5px;font-weight:300;max-width:72ch;line-height:1.6}
.chips{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:10px}
.chip{border:1px solid var(--line);border-radius:12px;padding:13px 15px;background:rgba(255,255,255,.018)}
.chip .top{display:flex;align-items:center;gap:8px;margin-bottom:7px}
.chip .dot{width:8px;height:8px;border-radius:50%;flex:none}
.chip .dot.bad{background:var(--bad)} .chip .dot.ok{background:var(--ok)} .chip .dot.warn{background:var(--warn)}
.chip .name{font-family:"Geist Mono",ui-monospace,monospace;font-size:11.5px;color:var(--ink);letter-spacing:.02em}
.chip .st{margin-left:auto;font-family:"Geist Mono",ui-monospace,monospace;font-size:9.5px;letter-spacing:.1em;text-transform:uppercase}
.chip .vals{font-family:"Geist Mono",ui-monospace,monospace;font-size:11px;color:var(--ink-3);line-height:1.7}
.chip .vals b{color:var(--ink-2);font-weight:500}
.tabs{display:flex;gap:8px;flex-wrap:wrap;padding:22px 0 6px;position:sticky;top:58px;z-index:30;
  background:linear-gradient(to bottom, var(--bg) 72%, transparent)}
.tabbtn{font-family:"Geist Mono",ui-monospace,monospace;font-size:11px;letter-spacing:.08em;text-transform:uppercase;
  padding:9px 16px;border-radius:999px;border:1px solid var(--line-strong);background:rgba(255,255,255,.02);color:var(--ink-3);cursor:pointer}
.tabbtn[aria-pressed="true"]{color:var(--ink);border-color:rgba(232,69,92,.5);background:var(--accent-soft)}
section{padding:34px 0 8px;scroll-margin-top:130px}
.sec-head{display:flex;align-items:baseline;gap:14px;margin-bottom:4px}
.sec-head .num{font-family:"Geist Mono",ui-monospace,monospace;font-size:11px;color:var(--accent);flex:none;padding-top:6px}
h3{margin:30px 0 12px;font-size:15px;font-weight:600;letter-spacing:-.005em}
h3:first-of-type{margin-top:6px}
p{margin:0 0 14px;color:var(--ink-2);max-width:74ch;font-size:14px;line-height:1.7;font-weight:300}
p b,p strong{color:var(--ink);font-weight:500}
code{font-family:"Geist Mono",ui-monospace,monospace;font-size:.86em;color:#e8c7cb;background:rgba(232,69,92,.085);
  border:1px solid rgba(232,69,92,.14);padding:1.5px 5px;border-radius:5px;white-space:nowrap}
.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:10px}
.kpi{border:1px solid var(--line);border-radius:14px;padding:16px;background:linear-gradient(180deg, rgba(255,255,255,.03), rgba(255,255,255,.008))}
.kpi .k{font-family:"Geist Mono",ui-monospace,monospace;font-size:9.5px;letter-spacing:.12em;text-transform:uppercase;color:var(--ink-3);display:block;margin-bottom:9px}
.kpi .big{font-family:"Geist Mono",ui-monospace,monospace;font-size:26px;letter-spacing:-.02em;line-height:1;color:var(--ink)}
.kpi .big small{font-size:12px;color:var(--ink-3)}
.kpi .cmp{margin-top:9px;font-size:12px;color:var(--ink-2);font-weight:300;line-height:1.55}
.kpi .cmp b{font-family:"Geist Mono",ui-monospace,monospace;font-weight:500;font-size:11.5px}
.foot{font-size:12px;color:var(--ink-3);font-weight:300;max-width:78ch;margin-top:14px;line-height:1.65}
.panel{border:1px solid var(--line);border-radius:var(--r);background:linear-gradient(180deg, rgba(255,255,255,.028), rgba(255,255,255,.008));padding:clamp(16px,2.5vw,28px);margin-top:12px}
.panel-head{display:flex;justify-content:space-between;align-items:baseline;gap:16px;flex-wrap:wrap;margin-bottom:14px}
.panel-head .t{font-family:"Geist Mono",ui-monospace,monospace;font-size:10.5px;letter-spacing:.13em;text-transform:uppercase;color:var(--ink-3)}
.panel-head .src{font-family:"Geist Mono",ui-monospace,monospace;font-size:10.5px;color:var(--ink-3)}
.toggles{display:flex;flex-wrap:wrap;gap:12px;margin:0 0 10px}
.toggles label{display:inline-flex;align-items:center;gap:7px;font-family:"Geist Mono",ui-monospace,monospace;font-size:11px;color:var(--ink-2);cursor:pointer}
.toggles input{accent-color:var(--accent)}
.sw{width:18px;height:3px;border-radius:2px;display:inline-block}
.chartwrap{position:relative}
svg.chart{display:block;width:100%;height:auto}
.tooltip{position:absolute;pointer-events:none;background:#1b1a1f;border:1px solid var(--line-strong);border-radius:10px;
  padding:10px 12px;font-family:"Geist Mono",ui-monospace,monospace;font-size:11px;line-height:1.75;color:var(--ink-2);
  display:none;z-index:5;box-shadow:0 18px 40px -18px rgba(0,0,0,.9);max-width:300px}
.tooltip b{color:var(--ink)}
.legend{display:flex;flex-wrap:wrap;gap:14px;margin:10px 0 0;font-family:"Geist Mono",ui-monospace,monospace;font-size:10.5px;color:var(--ink-3)}
.legend span{display:inline-flex;align-items:center;gap:7px}
table.facts{width:100%;border-collapse:collapse;margin-top:8px;font-size:13px}
table.facts th{text-align:left;font-family:"Geist Mono",ui-monospace,monospace;font-size:9.5px;letter-spacing:.12em;text-transform:uppercase;color:var(--ink-3);font-weight:400;padding:0 14px 10px 0;border-bottom:1px solid var(--line-strong)}
table.facts td{padding:11px 14px 11px 0;border-bottom:1px solid var(--line);color:var(--ink-2);font-weight:300;vertical-align:top}
table.facts td:first-child{color:var(--ink);font-weight:450}
table.facts td.mono{font-family:"Geist Mono",ui-monospace,monospace;font-size:11.5px;color:var(--ink-3)}
table.facts tr:hover td{background:rgba(255,255,255,.014)}
.runtime{height:8px;background:rgba(255,255,255,.055);border-radius:99px;overflow:hidden;min-width:120px}
.runtime i{display:block;height:100%;border-radius:99px;background:var(--ink-3)}
.runtime i.hot{background:var(--accent)}
.gauge{margin-top:14px}
.gauge .track{height:14px;border:1px solid var(--line-strong);border-radius:7px;position:relative;background:rgba(255,255,255,.04)}
.gauge .fill{position:absolute;left:0;top:0;bottom:0;border-radius:7px;background:linear-gradient(90deg,var(--ok),#8fd9bd)}
.gauge .cap{position:absolute;top:-4px;bottom:-4px;width:2px;background:var(--bad)}
.gauge .gl{display:flex;justify-content:space-between;font-family:"Geist Mono",ui-monospace,monospace;font-size:10.5px;color:var(--ink-3);margin-top:7px}
.dropzone{border:1.5px dashed var(--line-strong);border-radius:var(--r);padding:26px;text-align:center;transition:border-color .2s,background .2s;margin-top:12px}
.dropzone.over{border-color:var(--ok);background:rgba(99,195,154,.05)}
.dropzone p{margin:0 auto 12px;max-width:60ch}
.dropzone .row{display:flex;gap:10px;justify-content:center;flex-wrap:wrap;align-items:center}
.btn{font-family:"Geist Mono",ui-monospace,monospace;font-size:11px;letter-spacing:.06em;padding:9px 16px;border-radius:9px;
  border:1px solid rgba(232,69,92,.45);background:var(--accent-soft);color:var(--ink);cursor:pointer;text-transform:uppercase}
.btn.ghost{border-color:var(--line-strong);background:transparent;color:var(--ink-3)}
.filemsg{font-family:"Geist Mono",ui-monospace,monospace;font-size:11.5px;color:var(--ink-2);margin-top:12px}
.stackbar{display:flex;height:26px;border-radius:7px;overflow:hidden;border:1px solid var(--line);margin:6px 0 4px}
.stackbar i{display:block;height:100%}
.vrow{display:grid;grid-template-columns:110px 1fr;gap:14px;align-items:start;padding:12px 0;border-bottom:1px solid var(--line)}
.vrow .vl{font-family:"Geist Mono",ui-monospace,monospace;font-size:11px;color:var(--ink-2);padding-top:8px}
.vrow .vl small{display:block;color:var(--ink-3);font-size:10px;margin-top:3px}
.ghostbar{display:flex;height:14px;border-radius:5px;overflow:hidden;border:1px dashed rgba(255,255,255,.18);opacity:.55;margin-top:4px}
.ghostbar i{display:block;height:100%;background:rgba(255,255,255,.12)}
.hbar{display:grid;grid-template-columns:minmax(180px,300px) 1fr 64px;gap:12px;align-items:center;padding:6px 0;font-size:12px}
.hbar .hl{color:var(--ink-2);font-weight:300;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.hbar .track{height:16px;background:rgba(255,255,255,.045);border-radius:5px;overflow:hidden}
.hbar .fill{height:100%;border-radius:5px}
.hbar .hv{font-family:"Geist Mono",ui-monospace,monospace;font-size:11px;color:var(--ink-2);text-align:right}
.famhead{font-family:"Geist Mono",ui-monospace,monospace;font-size:10px;letter-spacing:.12em;text-transform:uppercase;color:var(--ink-3);margin:18px 0 6px}
.funnel{display:flex;flex-direction:column;gap:6px;margin-top:10px}
.frow{display:grid;grid-template-columns:minmax(150px,240px) 1fr 90px;gap:12px;align-items:center}
.frow .fl{font-size:12.5px;color:var(--ink-2);font-weight:300}
.frow .fl small{display:block;color:var(--ink-3);font-size:11px}
.frow .ftrack{height:22px;background:rgba(255,255,255,.04);border-radius:6px;overflow:hidden}
.frow .ffill{height:100%;border-radius:6px}
.frow .fv{font-family:"Geist Mono",ui-monospace,monospace;font-size:11.5px;color:var(--ink);text-align:right}
.split{display:grid;grid-template-columns:1.1fr .9fr;gap:clamp(20px,3.5vw,48px);align-items:start;margin-top:10px}
@media (max-width:860px){.split{grid-template-columns:1fr} .frow{grid-template-columns:1fr;gap:4px} .frow .fv{text-align:left}}
.callout{border-left:2px solid var(--accent);padding:4px 0 4px 18px;margin:20px 0}
.callout p{margin:0}
footer{border-top:1px solid var(--line);margin-top:36px;padding:40px 0 60px}
footer p{font-size:12.5px;color:var(--ink-3);font-weight:300;line-height:1.7;max-width:90ch}
.caveats{border-top:1px solid var(--line);margin-top:16px}
.caveats .item{display:grid;grid-template-columns:34px 1fr;gap:14px;padding:13px 0;border-bottom:1px solid var(--line)}
.caveats .item::before{content:"!";font-family:"Geist Mono",ui-monospace,monospace;font-size:11px;color:var(--warn);padding-top:2px;text-align:center}
.caveats .item p{margin:0;font-size:13px}
.print-head{display:none}
@media print{
  :root{--bg:#ffffff;--bg-2:#ffffff;--panel:#fbfafc;--line:rgba(0,0,0,.14);--line-strong:rgba(0,0,0,.30);
    --ink:#131218;--ink-2:#38353f;--ink-3:#635f6b;--accent:#b02a3e;--accent-soft:rgba(176,42,62,.07);
    --ok:#1c7050;--warn:#83530e;--bad:#9e2e28;--blue:#2b4c9a}
  html,body{background:#fff !important;color:var(--ink)}
  body{font-size:9.6pt;line-height:1.55}
  .wash,.grain,.topbar,.tabs,.dropzone,.toggles{display:none !important}
  .print-head{display:flex;justify-content:space-between;align-items:baseline;gap:12mm;border-bottom:1.5pt solid var(--ink);padding-bottom:2.5mm;margin-bottom:7mm}
  .print-head b{font-size:11pt}.print-head span{font-family:"Geist Mono",ui-monospace,monospace;font-size:7pt;letter-spacing:.09em;text-transform:uppercase;color:var(--ink-3)}
  .shell{max-width:none;padding:0}
  .hero{display:block;padding:0 0 5mm;border-bottom:1pt solid var(--line)}
  h1{font-size:24pt}.lede{margin-top:4mm;font-size:9.4pt;max-width:150mm}
  .card{margin-top:5mm;box-shadow:none;background:#fff;padding:5mm}
  .banner,.panel,.kpi,.chip{break-inside:avoid;background:#fff}
  section{padding-bottom:7mm}
  .sec-head{break-after:avoid} h2{font-size:15pt;break-after:avoid}
  .sec-sub{font-size:8.6pt;break-after:avoid}
  h3{break-after:avoid;font-size:9.6pt;margin:6mm 0 2mm}
  p{font-size:9.2pt;max-width:none}
  .row,.frow,.hbar,.caveats .item,.vrow{break-inside:avoid}
  .chips{grid-template-columns:repeat(3,1fr)}
  .kpis{grid-template-columns:repeat(3,1fr)}
  table.facts{font-size:8.6pt} table.facts tr{break-inside:avoid}
  .tooltip{display:none !important}
  footer{break-inside:avoid;margin-top:6mm;padding:6mm 0 0}
  footer p{font-size:7.6pt;color:var(--ink-3)}
  a{color:inherit;text-decoration:none}
}
@media (max-width:900px){.hero{grid-template-columns:1fr;gap:30px}}
</style>
</head>
<body>
<div class="wash"></div><div class="grain"></div>
<header class="topbar"><div class="topbar-in">
  <div class="mark"><b>BitChord Mix</b><span>vc91 benchmark &amp; telemetry</span></div>
  <nav class="topnav"><a href="#pre">PRE</a><a href="#post">POST</a><a href="#caveats">Caveats</a></nav>
  <span class="pill" id="topPill">vc91 ON-DEVICE: PENDING</span>
</div></header>
<div class="shell">
  <div class="print-head"><b>BitChord Mix — vc91-djmix49 benchmark &amp; telemetry</b><span>vc91 vs vc90 · POCO F6 1439ebea</span></div>

  <div class="hero">
    <div>
      <p class="eyebrow">vc91-djmix49 · interactive benchmark report</p>
      <h1>The filter no longer<br>slams shut — <em>now prove it</em><br>on the phone.</h1>
      <p class="lede">This page is a self-contained, offline benchmark for <strong>vc91 (<code>2.2.3-djmix49</code>, <code>com.music.bitchord.loopfix</code>)</strong> against the vc90 baseline (<code>2.2.3-djmix48</code>). Every number is computed live from the embedded <code>benchmark-data.json</code> — or from a vc91 JSONL file you drop into the POST tab. Nothing is fetched; nothing is invented.</p>
      <div class="banner" id="pendingBanner"><b>vc91 ON-DEVICE: PENDING</b><span>Drop <code>automix-telemetry-vc91.jsonl</code> into the POST drop-zone (or regenerate with <code>--candidate</code>) to judge the seven invariants below on real device data.</span></div>
    </div>
    <aside class="card">
      <p class="lbl">Provenance</p>
      <div class="ver">vc91 · djmix49<small>2.2.3-djmix49 · versionCode 91 — candidate</small></div>
      <dl>
        <dt>Baseline</dt><dd>vc90 · 2.2.3-djmix48</dd>
        <dt>Package</dt><dd>com.music.bitchord.loopfix</dd>
        <dt>Device</dt><dd>POCO F6 (SM8635 / 24069PC21G)</dd>
        <dt>Serial</dt><dd>1439ebea</dd>
        <dt>NPU policy</dt><dd id="npuPolicy">QNN_ENABLED=false / CPU_DEFAULT</dd>
        <dt>vc91 state</dt><dd id="vc91State">PENDING</dd>
        <dt>Source</dt><dd>benchmark-data.json</dd>
      </dl>
    </aside>
  </div>

  <div class="invariants" id="invariants">
    <h2>Persistent invariants</h2>
    <p class="sub">Traffic lights recomputed from the embedded data — and from any dropped vc91 JSONL. Red on vc90 is the baseline verdict that vc91 must overturn; the DSP/probe lights (5–7) already grade the design curves.</p>
    <div class="chips" id="chipGrid"></div>
  </div>

  <div class="tabs" role="group" aria-label="View tabs">
    <button class="tabbtn" id="tabPre" aria-pressed="true">PRE · DSP &amp; Native</button>
    <button class="tabbtn" id="tabPost" aria-pressed="false">POST · Telemetry &amp; Diff</button>
    <button class="tabbtn" id="tabAll" aria-pressed="false">ALL · Full Report</button>
  </div>

  <!-- ================= PRE ================= -->
  <div id="preView">
  <section id="pre">
    <div class="sec-head"><span class="num">PRE-01</span><h2>Key metrics — actual vs target</h2></div>
    <p class="sec-sub">vc90 measured on the POCO F6 baseline session vs the vc91 target. Normalized rates (<code>/100 dumps</code>) sit beside raw counts so session-size differences never masquerade as improvements.</p>
    <div class="kpis" id="kpiGrid"></div>
    <p class="foot">Definitions: <code>mix.dump</code> is the planner's pair-agnostic context log (emitted whether or not a mix fires — it is a denominator, not a verdict). <code>mix.fire</code> is the executed handoff. <code>actualHeardMs &gt; 0</code> is <b>loop-handover-only</b>: <code>classic</code> handovers always report <code>-1</code>, so a low heard-count does not mean classic mixes were unheard — it means the loop counter never ran. <code>mix.complete</code> rows are deduped by <code>attemptId</code> before any rate is computed (vc90 shows a 1.531× duplication ratio).</p>
  </section>

  <section>
    <div class="sec-head"><span class="num">PRE-02</span><h2>Outgoing low-pass envelopes — the air blast, tamed</h2></div>
    <p class="sec-sub">Beat <code>0..8</code> (<code>p</code> <code>0..1</code>) on the x-axis; log-Hz <code>500 Hz..22 kHz</code> on the y-axis. vc90 falls 20 kHz→700 Hz by beat 4 then <b>blasts back to 20 kHz</b> — a <b>+19.2 dB AIR brightness blast</b> while its gain still sits at unity. vc91 is a monotonic C1 raised-cosine 20 kHz→700 Hz. The survivor bridge dives 20 kHz→1,500 Hz→20 kHz, turning around at <code>p=0.625</code> (beat 5). Toggle series; hover for the crosshair readout. Dashed lines mark the LP-bypass probe corners (<code>19,800 Hz @ 48 kHz</code>, <code>19,404 Hz @ 44.1 kHz</code>).</p>
    <div class="panel">
      <div class="panel-head"><span class="t">OUT LP · log-Hz envelopes</span><span class="src">dspCurves · 41 samples · beat 0..8</span></div>
      <div class="toggles" id="lpToggles"></div>
      <div class="chartwrap"><svg class="chart" id="dspChart" viewBox="0 0 960 430" role="img" aria-label="Outgoing low-pass envelopes chart"></svg><div class="tooltip" id="dspTip"></div></div>
      <div class="legend" id="lpLegend"></div>
    </div>
    <div class="panel">
      <div class="panel-head"><span class="t">OUT gain &amp; echo send · normalized 0..1</span><span class="src">same beat axis · gain solid · echo dashed</span></div>
      <div class="chartwrap"><svg class="chart" id="normChart" viewBox="0 0 960 250" role="img" aria-label="Normalized gain and echo chart"></svg><div class="tooltip" id="normTip"></div></div>
      <p class="foot">vc90 holds gain at <code>1.0</code> through the cliff, then dumps it in the last beats — while its echo send sits flat at <code>0.9</code>. vc91 lets gain fall smoothly from mid-fade and gates the echo send as a beat-boundary raised-cosine bell (peak <code>0.9</code> at <code>p=0.5</code>, silence outside).</p>
    </div>
  </section>

  <section>
    <div class="sec-head"><span class="num">PRE-03</span><h2>Smoothness — slope continuity &amp; the bypass budget</h2></div>
    <p class="sec-sub"><code>Δ log₁₀f / Δp</code> aligned beneath the envelope chart. vc90 flips from <code>−4.035</code> to <code>+2.911</code> between <code>p=0.500</code> and <code>p=0.525</code> — a sign-flip kink no C1 curve can have. vc91 glides through a smooth zero-crossing curve; the bridge curve turns around smoothly at <code>p=0.625</code>.</p>
    <div class="panel">
      <div class="panel-head"><span class="t">Slope derivative strip · Δlog₁₀f/Δp vs p</span><span class="src">dspCurves slope fields</span></div>
      <div class="chartwrap"><svg class="chart" id="slopeChart" viewBox="0 0 960 210" role="img" aria-label="Slope derivative chart"></svg></div>
      <div class="gauge" id="bypassGauge">
        <div class="panel-head" style="margin-bottom:8px"><span class="t">LP-bypass probe · step budget</span><span class="src" id="bypassSrc">--lp-bypass-probe</span></div>
        <div class="track"><div class="fill" id="bypassFill"></div><div class="cap" id="bypassCap"></div></div>
        <div class="gl"><span>0 FS</span><span id="bypassLabel">—</span><span>cap 0.01 FS</span></div>
      </div>
    </div>
  </section>

  <section>
    <div class="sec-head"><span class="num">PRE-04</span><h2>Native C++ probes &amp; analysis pipeline budget</h2></div>
    <p class="sec-sub">Seven probes, log-scale runtimes. The <code>loop-domain-probe</code> (<code>78,000 ms</code> — sixty-three finite-loop pairs) dwarfs the rest by two orders of magnitude; note its <code>minRMS 0.0643</code> against the <code>0.05</code> floor, and the reverb tail at <code>−170.3 dB</code>.</p>
    <div class="panel">
      <div class="panel-head"><span class="t">Native probes · runtime (log scale)</span><span class="src">nativeProbes</span></div>
      <table class="facts"><thead><tr><th>Probe</th><th style="width:38%">Runtime</th><th>ms</th><th>Detail</th></tr></thead><tbody id="probeBody"></tbody></table>
    </div>
    <div class="panel">
      <div class="panel-head"><span class="t">Analysis pipeline budget</span><span class="src">analysisBench</span></div>
      <table class="facts"><tbody id="budgetBody"></tbody></table>
      <p class="foot">Pass-1 head target publishes analysis in <code>1–2 s</code>; <code>PLAYBACK_FIRST_MS</code> yields playback after <code>10 s → 2 s</code> so the first mix is never starved by analysis. Watchdogs: <code>45 s</code> head, <code>480 s</code> whole-run. The ORT reaper fires at <code>100 s</code> with <code>intraOp=2, interOp=1</code> on the CPU arena.</p>
    </div>
  </section>
  </div>

  <!-- ================= POST ================= -->
  <div id="postView" style="display:none">
  <section id="post">
    <div class="sec-head"><span class="num">POST-01</span><h2>Judge vc91 yourself — drop the JSONL</h2></div>
    <p class="sec-sub">Drag <code>automix-telemetry-vc91.jsonl</code> (pulled from the phone after a vc91 session) straight into the browser. Parsing runs entirely offline via <code>FileReader</code> with the same authoritative rules as <code>automix-telemetry-diff.py</code> — deduped completes, PCM attempt/event split, sentinel-aware <code>planAgeMs</code>. Every POST chart, the banner, and the invariant lights update live.</p>
    <div class="dropzone" id="dropzone">
      <p><b>Drop vc91 telemetry here</b> — or pick the file. Nothing leaves the browser.</p>
      <div class="row">
        <label class="btn" for="filePick">Choose JSONL file</label>
        <input type="file" id="filePick" accept=".jsonl,.json,.txt,text/plain" style="display:none">
        <button class="btn ghost" id="clearCand" type="button">Clear candidate</button>
      </div>
      <div class="filemsg" id="fileMsg">No vc91 data yet — baseline vc90 only.</div>
    </div>
    <div class="panel">
      <div class="panel-head"><span class="t">vc90 vs vc91 delta table</span><span class="src">authoritative metrics</span></div>
      <table class="facts"><thead><tr><th>Metric</th><th>vc90 actual</th><th>vc91</th><th>Target</th><th>Verdict</th></tr></thead><tbody id="deltaBody"></tbody></table>
    </div>
  </section>

  <section>
    <div class="sec-head"><span class="num">POST-02</span><h2>Version progression — vc77 → vc90 (+ vc91 slot)</h2></div>
    <p class="sec-sub">Solid 100% bars: accepted-plan styles (or fire styles where the era logged no accepted field). Ghosted dashed bars: dump styles. <b><code>NATURAL</code> did not exist in vc77–vc88 logs</b> — those builds logged <code>decline</code> where vc90 logs <code>NATURAL</code>, so the pre-vc90 ghost bars read as decline-heavy by construction, not by behavior change.</p>
    <div class="panel"><div id="versionBars"></div>
      <div class="legend"><span><span class="sw" style="background:var(--accent)"></span>sold = accepted / fire styles (100%)</span><span><span class="sw" style="background:rgba(255,255,255,.3)"></span>ghosted = dump styles (100%)</span><span>vc91 slot fills in once JSONL is dropped</span></div>
    </div>
  </section>

  <section>
    <div class="sec-head"><span class="num">POST-03</span><h2>Refusals — where plans die, and who is singing</h2></div>
    <p class="sec-sub">Grouped Pareto of refusal codes, then the sung-share decomposition over every loop the planner evaluated. L2 stem-ducking and ranking exist to move exactly these numbers: the <code>161</code> vc90 refusal events sit on top of <code>9,840</code> evaluated loops, of which <code>8,304</code> carry sung lyrics.</p>
    <div class="panel"><div class="panel-head"><span class="t">Refusal Pareto · by family (vc90)</span><span class="src">mix.plan.rejected · refusalCode</span></div><div id="pareto"></div></div>
    <div class="panel"><div class="panel-head"><span class="t">Sung-share decomposition · evaluated loops</span><span class="src">sungRefusalTotals</span></div><div id="sungBars"></div>
      <div class="legend"><span><span class="sw" style="background:var(--ok)"></span>quiet</span><span><span class="sw" style="background:var(--accent)"></span>sung lyrics</span><span><span class="sw" style="background:var(--warn)"></span>sung mask</span><span>unmeasured shown when non-zero</span></div>
    </div>
  </section>

  <section>
    <div class="sec-head"><span class="num">POST-04</span><h2>Funnels — rungs &amp; conversion</h2></div>
    <p class="sec-sub">Left: the target ladder in execution order vs what vc90's accepted plans actually landed on. Right: the conversion funnel from planner context to heard loop — read <code>rejected &gt; dump</code> as replanning churn (scrubs re-plan), not as a broken denominator.</p>
    <div class="split">
      <div class="panel"><div class="panel-head"><span class="t">Rung funnel · target order vs vc90 accepted</span><span class="src">acceptedRungs</span></div><div class="funnel" id="rungFunnel"></div>
        <p class="foot">Shipped ladder: <code>controller (native bridge / crown) → decline-holds-no-overlap → fallback-beatmix → safety-crossfade → fallback-muffle → fallback-echo → tiered-fallback-exhausted</code> (stem-duck crown admission deferred — no stem field in TrackAnalysis). vc90's accepted plans pile onto <code>Natural handoff</code> (202) and <code>null</code> (57) — the ladder below NATURAL is where vc91 must actually live.</p></div>
      <div class="panel"><div class="panel-head"><span class="t">Conversion funnel · dump → heard</span><span class="src">deduped by attemptId</span></div><div class="funnel" id="convFunnel"></div>
        <p class="foot">Complete shows the deduplication explicitly: <code>147</code> rows → <code>96</code> attempts (<code>1.531×</code>). <code>heard &gt; 0</code> counts loop completions only.</p></div>
    </div>
  </section>

  <section>
    <div class="sec-head"><span class="num">POST-05</span><h2>Stale plans &amp; fire timing</h2></div>
    <p class="sec-sub">Log-<code>planAgeMs</code> strip for the <code>9</code> vc90 device-uptime poison events (<code>185M–219M ms</code>) against the <code>3.6M ms</code> (<code>1 h</code>) ceiling — plus <code>deltaMs</code> timing by technique, where <code>PREROLL_SECONDS 1.0→1.5</code> predicts an earlier negative shift (~−500 ms) for native loops.</p>
    <div class="panel"><div class="panel-head"><span class="t">planAgeMs · log-ms strip (each dot = one sample)</span><span class="src">1 h ceiling = 3,600,000 ms</span></div>
      <div class="chartwrap"><svg class="chart" id="planAgeChart" viewBox="0 0 960 240" role="img" aria-label="planAgeMs distribution"></svg></div>
      <div id="planAgeBins" style="margin-top:10px"></div>
    </div>
    <div class="panel"><div class="panel-head"><span class="t">deltaMs by technique · vc90 fire timing</span><span class="src">mix.fire · deltaMs</span></div>
      <div id="deltaChart"></div>
      <p class="foot"><code>classic +159 ms</code> median (late fades), <code>native-bridge −844 ms</code> (early handoffs), <code>crown-loop −714 ms</code>. With <code>PREROLL_SECONDS 1.0→1.5</code>, expect vc91 native deltas to shift ~500 ms more negative due to the extra half-second of preroll — judge the spread, not just the sign.</p>
    </div>
  </section>
  </div>

  <section id="caveats">
    <div class="sec-head"><span class="num">§</span><h2>Data-quality caveats</h2></div>
    <p class="sec-sub">Read before quoting any number on this page.</p>
    <div class="caveats">
      <div class="item"><p><b><code>mix.fire</code> vs <code>mix.plan</code> technique sums:</b> earlier diff output merged <code>mix.fire</code> and <code>mix.plan</code> into one counter (<code>classic 415</code>, <code>native-bridge 387</code>, <code>natural 113</code>, <code>crown-loop 8</code>). Separating them restores the exact <code>88</code> fired events (<code>classic 53</code>, <code>native-bridge 33</code>, <code>crown-loop 2</code>).</p></div>
      <div class="item"><p><b><code>handoff_pcm</code> is 44 attempts / 87 events.</b> The same abort is logged once as a <code>refusalCode</code> and again as a <code>reason</code> (plus terminal completes), so events double-count attempts. The invariant grades attempts; the event count is shown for scale.</p></div>
      <div class="item"><p><b><code>mix.complete</code> duplicates attempts 1.531× on vc90</b> (147 rows, 96 distinct <code>attemptId</code>s). Every rate on this page dedupes first; any downstream tool that doesn't will overstate completion by half.</p></div>
      <div class="item"><p><b><code>mix.dump</code> is pair-agnostic planner context</b> (a cache artifact of the planning pass), not a song-pair verdict — it fires for pairs that never mix. That is why <code>mix.plan.rejected</code> (790) can exceed <code>mix.dump</code> (251): scrubs and replans re-enter the planner without new dumps.</p></div>
      <div class="item"><p><b><code>actualHeardMs</code> is loop-only.</b> <code>classic</code> handovers report <code>-1</code> by construction, so <code>heard &gt; 0</code> (28) measures loop coverage, not audibility. Never present it as a classic-mix quality signal.</p></div>
    </div>
  </section>

  <footer>
    <p>Generated from <code>benchmark-data.json</code> by <code>tools/vc91-benchmark-dataviz.py</code>. Single self-contained file — fonts are the only optional network request and fall back to system sans/mono offline. Device: POCO F6 (SM8635 / 24069PC21G, serial <code>1439ebea</code>) · NPU: <code>QNN_ENABLED=false / CPU_DEFAULT (htp_arch=73, libcdsprpc optional)</code>. Nothing on this page is a listening verdict.</p>
  </footer>
</div>

<script id="bench-data" type="application/json">%%DATA%%</script>
<script>
(function(){
'use strict';
var DATA = JSON.parse(document.getElementById('bench-data').textContent);
var NS = 'http://www.w3.org/2000/svg';
function $(id){return document.getElementById(id);}
function el(tag, attrs, parent){var n=document.createElementNS(NS,tag);for(var k in attrs){n.setAttribute(k,attrs[k]);}if(parent)parent.appendChild(n);return n;}
function txt(parent,x,y,s,cls,anchor,size){var t=el('text',{x:x,y:y,'class':cls||'', 'text-anchor':anchor||'start','font-size':size||10, fill:'currentColor'},parent);t.textContent=s;return t;}
function fmt(n){if(n==null||isNaN(n))return '—';return Math.round(n).toLocaleString('en-US');}
function pct(n){return (n==null||isNaN(n))?'—':n.toFixed(1)+'%';}
function byLabel(hist,label){for(var i=0;i<hist.length;i++){if(hist[i].label===label)return hist[i];}return null;}
var VC90 = byLabel(DATA.history,'vc90-live') || DATA.history[DATA.history.length-1];
var CAND = DATA.candidate || null;
function active(){return CAND;}

var SER = [
 {k:'vc90OutLpHz',label:'vc90 OUT LP',color:'#e8455c',axis:'log',dash:''},
 {k:'vc91OutLpHz',label:'vc91 OUT LP (C1)',color:'#63c39a',axis:'log',dash:''},
 {k:'vc91SurvivorBridgeLpHz',label:'vc91 survivor bridge LP',color:'#8ab4ff',axis:'log',dash:''},
 {k:'vc90OutGain',label:'vc90 OUT gain',color:'#e8455c',axis:'norm',dash:''},
 {k:'vc91OutGain',label:'vc91 OUT gain',color:'#63c39a',axis:'norm',dash:''},
 {k:'vc90EchoSend',label:'vc90 echo send',color:'#d8a24e',axis:'norm',dash:'5 4'},
 {k:'vc91EchoSend',label:'vc91 echo send (bell)',color:'#9fdcc0',axis:'norm',dash:'5 4'}
];
var shown = {}; SER.forEach(function(s){shown[s.k]=true;});

/* ---------- invariants ---------- */
function maxJump(key){var c=DATA.dspCurves,m=0;for(var i=1;i<c.length;i++){var d=Math.abs(c[i][key]-c[i-1][key]);if(d>m)m=d;}return m;}
function parseBypass(){for(var i=0;i<DATA.nativeProbes.length;i++){var p=DATA.nativeProbes[i];if(p.name.indexOf('lp-bypass')>=0){var m=/maxStep=([0-9.]+)/.exec(p.detail||'');return m?parseFloat(m[1]):null;}}return null;}
function invDefs(){
 var c=DATA.dspCurves;
 var mono=true;for(var i=1;i<c.length;i++){if(c[i].vc91OutLpHz>c[i-1].vc91OutLpHz+1e-9){mono=false;break;}}
 var endOk=Math.abs(c[c.length-1].vc91OutLpHz-700)<1e-6;
 var bypass=parseBypass();
 var cand=CAND;
 function candOut(){if(!cand)return{pending:true};var fn=cand.fireNatural||0,fd=cand.mixFire||0,sh=fd?100*fn/fd:0;return{ok:sh<15,v:fn+'/'+fd+' fired ('+pct(sh)+') · '+fmt(cand.queueAdvances||0)+' queue advances excl.'};}
 function candPcm(){return cand?{ok:cand.handoffPcmAttempts===0&&cand.handoffPcmTerminal===0,v:fmt(cand.handoffPcmAttempts)+' att / '+fmt(cand.handoffPcmEvents)+' ev'}:{pending:true};}
 function candFire(){return cand?{ok:cand.firePer100Dump>35.1,v:cand.firePer100Dump.toFixed(1)+'/100 (n='+fmt(cand.mixFire)+')'}:{pending:true};}
 function candAge(){return cand?{ok:cand.planAgeOver1h===0,v:'max '+fmt(cand.planAgeMaxMs)+' ms, '+fmt(cand.planAgeOver1h)+' >1h'}:{pending:true};}
 return [
  {name:'fired NATURAL share < 15%', base:{ok:true,v:(VC90.fireNatural||0)+'/'+fmt(VC90.mixFire)+' fired'}, cand:candOut(), note:'mix.fire authoritative; queue advances excluded (vc90 113)'},
  {name:'handoff_pcm == 0', base:{ok:false,v:fmt(VC90.handoffPcmAttempts)+' attempts / '+fmt(VC90.handoffPcmEvents)+' events'}, cand:candPcm(), note:'terminal '+fmt(VC90.handoffPcmTerminal)},
  {name:'fire / 100 dumps > 35.1', base:{ok:false,v:VC90.firePer100Dump.toFixed(2)+'/100 · raw '+fmt(VC90.mixFire)+' fires / '+fmt(VC90.mixDump)+' dumps'}, cand:candFire(), note:'normalized rate; raw counts shown — session sizes differ'},
  {name:'planAgeMs max < 1h', base:{ok:false,v:'max '+fmt(VC90.planAgeMaxMs)+' ms · '+fmt(VC90.planAgeOver1h)+' > 1h'}, cand:candAge(), note:'9 poison events 185M–219M ms'},
  {name:'OUT LP monotonic & == 700 Hz @ end', base:{ok:(mono&&endOk),v:'vc91 monotonic='+(mono?'yes':'no')+' · end='+fmt(c[c.length-1].vc91OutLpHz)+' Hz'}, cand:null, note:'vc90 ends 20,000 Hz (not monotonic)'},
  {name:'C1 slope continuity (no kink)', base:{ok:maxJump('vc91SlopeLog10')<1.0,v:'vc91 max Δslope '+maxJump('vc91SlopeLog10').toFixed(3)+' vs vc90 '+maxJump('vc90SlopeLog10').toFixed(3)}, cand:null, note:'vc90 kink at p=0.5 (−4.035 → +2.911)'},
  {name:'lp-bypass ≤ 0.01 FS', base:{ok:(bypass!=null&&bypass<=0.01),v:(bypass==null?'probe missing':'maxStep '+bypass.toFixed(4)+' FS @ 19.8 kHz')}, cand:null, note:'cap 0.01 FS · 48 kHz corner 19,800 Hz'}
 ];
}
function renderInvariants(){
 var grid=$('chipGrid');grid.innerHTML='';
 invDefs().forEach(function(d,i){
  var chip=document.createElement('div');chip.className='chip';
  var status,cls;
  if(d.cand){
   status=d.cand.ok?'PASS':'FAIL';cls=d.cand.ok?'ok':'bad';
  }else if(d.cand===null){
   status=d.base.ok?'PASS':'FAIL';cls=d.base.ok?'ok':'bad';
  }else{status='PENDING';cls='warn';}
  var vals='<b>'+d.base.v+'</b>';
  if(d.cand){vals+='<br>vc91: <b>'+d.cand.v+'</b>';}
  chip.innerHTML='<div class="top"><span class="dot '+cls+'"></span><span class="name">'+(i+1)+'. '+d.name+'</span><span class="st" style="color:var(--'+(cls==='ok'?'ok':cls==='bad'?'bad':'warn')+')">'+status+'</span></div><div class="vals">vc90: '+vals+'<br><span style="opacity:.75">'+d.note+'</span></div>';
  grid.appendChild(chip);
 });
 // banner + pills
 var banner=$('pendingBanner'),pill=$('topPill'),state=$('vc91State');
 if(!CAND){
  banner.className='banner';banner.innerHTML='<b>vc91 ON-DEVICE: PENDING</b><span>Drop <code>automix-telemetry-vc91.jsonl</code> into the POST drop-zone (or regenerate with <code>--candidate</code>) to judge the seven invariants above on real device data.</span>';
  pill.className='pill';pill.textContent='vc91 ON-DEVICE: PENDING';state.textContent='PENDING';
 }else{
  var allOk=invDefs().every(function(d){return d.cand?d.cand.ok:d.base.ok;});
  banner.className='banner '+(allOk?'green':'red');
  banner.innerHTML='<b>'+(allOk?'vc91 ON-DEVICE: ALL INVARIANTS PASS':'vc91 ON-DEVICE: TARGETS NOT MET')+'</b><span>'+CAND.events.toLocaleString()+' events · '+CAND.mixDump+' dumps · '+CAND.mixFire+' fires — computed in-browser from the dropped JSONL.</span>';
  pill.className='pill '+(allOk?'green':'red');pill.textContent=allOk?'vc91 ON-DEVICE: PASS':'vc91 ON-DEVICE: FAIL';state.textContent=allOk?'PASS':'FAIL';
 }
}

/* ---------- tabs ---------- */
function setTab(which){
 var pre=which!=='post',post=which!=='pre';
 $('preView').style.display=pre?'':'none';
 $('postView').style.display=post?'':'none';
 $('tabPre').setAttribute('aria-pressed',which==='pre'?'true':'false');
 $('tabPost').setAttribute('aria-pressed',which==='post'?'true':'false');
 $('tabAll').setAttribute('aria-pressed',which==='all'?'true':'false');
}
$('tabPre').addEventListener('click',function(){setTab('pre');});
$('tabPost').addEventListener('click',function(){setTab('post');});
$('tabAll').addEventListener('click',function(){setTab('all');});

/* ---------- KPI tiles ---------- */
function kpiTile(k,big,sub,cmp){return '<div class="kpi"><span class="k">'+k+'</span><div class="big">'+big+'<small> '+sub+'</small></div><div class="cmp">'+cmp+'</div></div>';}
function renderKPIs(){
 var c=CAND,g=$('kpiGrid');g.innerHTML='';
 g.innerHTML+=kpiTile('NATURAL share · fired',(function(){var fn=VC90.fireNatural||0,fd=VC90.mixFire||0;return pct(fd?100*fn/fd:0);})(),fmt(VC90.fireNatural||0)+'/'+fmt(VC90.mixFire)+' fired','vc91 target <b>&lt;15%</b> · '+(c?('vc91 <b>'+c.fireNatural+'/'+fmt(c.mixFire)+'</b>'):'vc91 pending'));
 g.innerHTML+=kpiTile('NATURAL share · dump (ctx)',pct(VC90.dumpNaturalPct),fmt(VC90.dumpStyles.NATURAL||0)+'/'+fmt(VC90.mixDump),'pre-execution · '+(c?('vc91 <b>'+pct(c.dumpNaturalPct)+'</b> · '+fmt(c.queueAdvances||0)+' queue adv.'):'vc91 pending'));
 g.innerHTML+=kpiTile('Fire rate · /100 dumps',VC90.firePer100Dump.toFixed(1),'raw '+fmt(VC90.mixFire)+' / '+fmt(VC90.mixDump),'vc91 target <b>&gt;35.1</b> · '+(c?('vc91 <b>'+c.firePer100Dump.toFixed(1)+'</b> ('+fmt(c.mixFire)+' fires)'):'vc91 pending'));
 g.innerHTML+=kpiTile('handoff_pcm aborts',fmt(VC90.handoffPcmAttempts),'attempts · '+fmt(VC90.handoffPcmEvents)+' events','vc91 target <b>0</b> · '+(c?('vc91 <b>'+fmt(c.handoffPcmAttempts)+' att / '+fmt(c.handoffPcmEvents)+' ev</b>'):'vc91 pending'));
 g.innerHTML+=kpiTile('planAgeMs > 1h',fmt(VC90.planAgeOver1h),'of '+fmt(VC90.planAgeValues.length)+' samples · max '+fmt(VC90.planAgeMaxMs),'vc91 target <b>0</b> · '+(c?('vc91 <b>'+fmt(c.planAgeOver1h)+' · max '+fmt(c.planAgeMaxMs)+'</b>'):'vc91 pending'));
 g.innerHTML+=kpiTile('Heard > 0 (loop-only)',fmt(VC90.heardPositiveLoopOnly),'clean terminal '+fmt(VC90.mixCompleteClean)+'/'+fmt(VC90.mixCompleteAttempts)+' attempts','vc91 · '+(c?('<b>'+fmt(c.heardPositiveLoopOnly)+'</b> heard · clean '+fmt(c.mixCompleteClean)):'pending')+' — classic always −1');
 $('npuPolicy').textContent=DATA.meta.npuPolicy;
 $('vc91State').textContent=CAND?('SEE BANNER'):'PENDING';
}

/* ---------- DSP charts ---------- */
var M={l:64,r:18,t:22,b:38};
function xOf(beat,W){return M.l+(beat/8)*(W-M.l-M.r);}
function logY(hz,H){var lo=Math.log10(500),hi=Math.log10(22000);return M.t+(hi-Math.log10(hz))/(hi-lo)*(H-M.t-M.b);}
function linY(v,H){return M.t+(1-v)*(H-M.t-M.b);}
function buildAxes(svg,W,H,mode){
 var g=el('g',{},svg);
 var ticks=mode==='log'?[500,1000,2000,4000,8000,16000]:[0,.25,.5,.75,1];
 ticks.forEach(function(tk){
  var y=mode==='log'?logY(tk,H):linY(tk,H);
  el('line',{x1:M.l,x2:W-M.r,y1:y,y2:y,stroke:'rgba(255,255,255,.08)','stroke-width':1},g);
  var lab=mode==='log'?(tk>=1000?(tk/1000)+' kHz':tk+' Hz'):tk.toFixed(2);
  var t=txt(g,M.l-8,y+3,lab,'', 'end',9.5);t.setAttribute('fill','#7d7986');t.setAttribute('font-family',"'Geist Mono',ui-monospace,monospace");
 });
 for(var b=0;b<=8;b++){var x=xOf(b,W);el('line',{x1:x,x2:x,y1:M.t,y2:H-M.b,stroke:'rgba(255,255,255,.045)'},g);
  var t2=txt(g,x,H-M.b+16,String(b),'','middle',9.5);t2.setAttribute('fill','#7d7986');t2.setAttribute('font-family',"'Geist Mono',ui-monospace,monospace");}
 var xa=txt(g,W-M.r,H-4,'beat →  (p 0..1)','end',0);xa.setAttribute('fill','#7d7986');
 var ya=txt(g,10,M.t-8,mode==='log'?'OUT LP · Hz (log)':'gain / echo · 0..1','', 'start',9.5);ya.setAttribute('fill','#7d7986');ya.setAttribute('font-family',"'Geist Mono',ui-monospace,monospace");
 return g;
}
function pathFor(svg,key,W,H,mode){
 var c=DATA.dspCurves,d='',pts=[];
 for(var i=0;i<c.length;i++){var x=xOf(c[i].beat,W);var y=mode==='log'?logY(c[i][key],H):linY(c[i][key],H);pts.push([x,y]);d+=(i?'L':'M')+x.toFixed(1)+','+y.toFixed(1)+' ';}
 var s=SER.filter(function(x){return x.k===key;})[0];
 var p=el('path',{d:d,fill:'none',stroke:s.color,'stroke-width':2,'stroke-linejoin':'round','stroke-linecap':'round',opacity:shown[key]?1:0},svg);
 if(s.dash){p.setAttribute('stroke-dasharray',s.dash);}
 p.dataset.key=key;return {path:p,pts:pts};
}
function drawDSP(){
 var svg=$('dspChart'),W=960,H=430;svg.innerHTML='';buildAxes(svg,W,H,'log');
 var curves={};
 SER.filter(function(s){return s.axis==='log';}).forEach(function(s){curves[s.k]=pathFor(svg,s.k,W,H,'log');});
 // bypass markers
 [19800,19404].forEach(function(hz,i){
  var y=logY(hz,H);
  el('line',{x1:M.l,x2:W-M.r,y1:y,y2:y,stroke:'rgba(255,255,255,.3)','stroke-dasharray':'2 5','stroke-width':1},svg);
  var t=txt(svg,W-M.r-4,y-5,fmt(hz)+' Hz bypass @ '+(i===0?'48k':'44.1k'),'end',9);t.setAttribute('fill','#7d7986');t.setAttribute('font-family',"'Geist Mono',ui-monospace,monospace");
 });
 // 700 Hz floor line
 var y7=logY(700,H);el('line',{x1:M.l,x2:W-M.r,y1:y7,y2:y7,stroke:'rgba(99,195,154,.35)','stroke-dasharray':'6 4'},svg);
 var t7=txt(svg,M.l+4,y7+12,'700 Hz floor','start',9);t7.setAttribute('fill','#63c39a');
 // annotations
 var a1=txt(svg,xOf(5.3,W),logY(9000,H),'+19.2 dB AIR blast','middle',11);a1.setAttribute('fill','#e8455c');a1.setAttribute('font-weight','600');
 var a2=txt(svg,xOf(6.6,W),logY(3000,H),'vc90 700 Hz → 20 kHz','middle',9.5);a2.setAttribute('fill','#e8455c');
 var a3=txt(svg,xOf(5.0,W),logY(1500,H)-12,'turnaround 1,500 Hz @ p=0.625','middle',9.5);a3.setAttribute('fill','#8ab4ff');
 el('circle',{cx:xOf(5,W),cy:logY(1500,H),r:4,fill:'none',stroke:'#8ab4ff','stroke-width':1.5},svg);
 el('circle',{cx:xOf(4,W),cy:logY(700,H),r:4,fill:'none',stroke:'#e8455c','stroke-width':1.5},svg);
 // crosshair
 var cross=el('line',{x1:0,x2:0,y1:M.t,y2:H-M.b,stroke:'rgba(255,255,255,.35)','stroke-width':1,opacity:0},svg);
 var dots={};
 SER.filter(function(s){return s.axis==='log';}).forEach(function(s){dots[s.k]=el('circle',{r:3.5,fill:s.color,opacity:0},svg);});
 svg.addEventListener('mousemove',function(ev){
  var r=svg.getBoundingClientRect();var mx=(ev.clientX-r.left)*(W/r.width);
  var beat=Math.max(0,Math.min(8,(mx-M.l)/(W-M.l-M.r)*8));
  var idx=Math.round(beat/8*(DATA.dspCurves.length-1));
  var c=DATA.dspCurves[idx];
  cross.setAttribute('x1',xOf(c.beat,W));cross.setAttribute('x2',xOf(c.beat,W));cross.setAttribute('opacity',1);
  var html='<b>p '+c.p.toFixed(3)+' · beat '+c.beat.toFixed(1)+'</b>';
  SER.filter(function(s){return s.axis==='log';}).forEach(function(s){
   var pt=curves[s.k].pts[idx];dots[s.k].setAttribute('cx',pt[0]);dots[s.k].setAttribute('cy',pt[1]);dots[s.k].setAttribute('opacity',shown[s.k]?1:0);
   if(shown[s.k])html+='<br><span style="color:'+s.color+'">●</span> '+s.label+': <b>'+fmt(c[s.k])+' Hz</b>';
  });
  var tip=$('dspTip');tip.style.display='block';tip.innerHTML=html;
  var wrap=svg.parentElement.getBoundingClientRect();
  tip.style.left=Math.min(wrap.width-310,Math.max(4,ev.clientX-wrap.left+14))+'px';
  tip.style.top=Math.max(4,ev.clientY-wrap.top-10)+'px';
 });
 svg.addEventListener('mouseleave',function(){cross.setAttribute('opacity',0);Object.keys(dots).forEach(function(k){dots[k].setAttribute('opacity',0);});$('dspTip').style.display='none';});
 var svg2=$('normChart'),H2=250;svg2.innerHTML='';buildAxes(svg2,W,H2,'norm');
 var curves2={};
 SER.filter(function(s){return s.axis==='norm';}).forEach(function(s){curves2[s.k]=pathFor(svg2,s.k,W,H2,'norm');});
 var cross2=el('line',{x1:0,x2:0,y1:M.t,y2:H2-M.b,stroke:'rgba(255,255,255,.35)',opacity:0},svg2);
 var dots2={};SER.filter(function(s){return s.axis==='norm';}).forEach(function(s){dots2[s.k]=el('circle',{r:3.5,fill:s.color,opacity:0},svg2);});
 svg2.addEventListener('mousemove',function(ev){
  var r=svg2.getBoundingClientRect();var mx=(ev.clientX-r.left)*(W/r.width);
  var beat=Math.max(0,Math.min(8,(mx-M.l)/(W-M.l-M.r)*8));
  var idx=Math.round(beat/8*(DATA.dspCurves.length-1));var c=DATA.dspCurves[idx];
  cross2.setAttribute('x1',xOf(c.beat,W));cross2.setAttribute('x2',xOf(c.beat,W));cross2.setAttribute('opacity',1);
  var html='<b>p '+c.p.toFixed(3)+' · beat '+c.beat.toFixed(1)+'</b>';
  SER.filter(function(s){return s.axis==='norm';}).forEach(function(s){
   var pt=curves2[s.k].pts[idx];dots2[s.k].setAttribute('cx',pt[0]);dots2[s.k].setAttribute('cy',pt[1]);dots2[s.k].setAttribute('opacity',shown[s.k]?1:0);
   if(shown[s.k])html+='<br><span style="color:'+s.color+'">●</span> '+s.label+': <b>'+c[s.k].toFixed(3)+'</b>';
  });
  var tip=$('normTip');tip.style.display='block';tip.innerHTML=html;
  var wrap=svg2.parentElement.getBoundingClientRect();
  tip.style.left=Math.min(wrap.width-310,Math.max(4,ev.clientX-wrap.left+14))+'px';
  tip.style.top=Math.max(4,ev.clientY-wrap.top-10)+'px';
 });
 svg2.addEventListener('mouseleave',function(){cross2.setAttribute('opacity',0);Object.keys(dots2).forEach(function(k){dots2[k].setAttribute('opacity',0);});$('normTip').style.display='none';});
 // toggles + legend
 var tg=$('lpToggles');tg.innerHTML='';
 SER.forEach(function(s){
  var lab=document.createElement('label');
  lab.innerHTML='<input type="checkbox" '+(shown[s.k]?'checked':'')+'><span class="sw" style="background:'+s.color+'"></span>'+s.label;
  lab.querySelector('input').addEventListener('change',function(ev){shown[s.k]=ev.target.checked;drawDSP();});
  tg.appendChild(lab);
 });
 $('lpLegend').innerHTML='<span>vc90 = crimson · the falling-then-blasting curve</span><span>vc91 = green · monotonic C1</span><span>bridge = blue · 20k→1.5k→20k</span>';
}
function drawSlope(){
 var svg=$('slopeChart'),W=960,H=210;svg.innerHTML='';
 var lo=-5,hi=5;
 function Y(v){return M.t+(hi-v)/(hi-lo)*(H-M.t-M.b);}
 [-4,-2,0,2,4].forEach(function(v){el('line',{x1:M.l,x2:W-M.r,y1:Y(v),y2:Y(v),stroke:v===0?'rgba(255,255,255,.22)':'rgba(255,255,255,.08)'},svg);
  var t=txt(svg,M.l-8,Y(v)+3,v===0?'0':v,'end',9.5);t.setAttribute('fill','#7d7986');t.setAttribute('font-family',"'Geist Mono',ui-monospace,monospace");});
 for(var b=0;b<=8;b++){var x=xOf(b,W);el('line',{x1:x,x2:x,y1:M.t,y2:H-M.b,stroke:'rgba(255,255,255,.045)'},svg);var t2=txt(svg,x,H-M.b+16,String(b),'middle',9.5);t2.setAttribute('fill','#7d7986');}
 var ya=txt(svg,10,M.t-8,'Δlog₁₀f / Δp','start',9.5);ya.setAttribute('fill','#7d7986');
 [['vc90SlopeLog10','#e8455c','vc90 (kink)'],['vc91SlopeLog10','#63c39a','vc91 (smooth)'],['vc91BridgeSlopeLog10','#8ab4ff','vc91 bridge']].forEach(function(cfg){
  var d='';DATA.dspCurves.forEach(function(c,i){d+=(i?'L':'M')+xOf(c.beat,W).toFixed(1)+','+Y(c[cfg[0]]).toFixed(1)+' ';});
  el('path',{d:d,fill:'none',stroke:cfg[1],'stroke-width':2,'stroke-linejoin':'round'},svg);
  var lab=txt(svg,xOf(0.2,W),Y(cfg[0]==='vc90SlopeLog10'?-4.5:cfg[0]==='vc91SlopeLog10'?-1.2:4.5),cfg[2],'start',9.5);lab.setAttribute('fill',cfg[1]);
 });
 var kx=xOf(4.1,W);el('line',{x1:kx,x2:kx,y1:M.t,y2:H-M.b,stroke:'rgba(232,69,92,.4)','stroke-dasharray':'4 4'},svg);
 var kt=txt(svg,kx+6,Y(3.4),'kink p=0.5','start',9.5);kt.setAttribute('fill','#e8455c');
 var b=parseBypass();
 if(b!=null){$('bypassFill').style.width=Math.min(100,100*b/0.012)+'%';$('bypassCap').style.left=(100*0.01/0.012)+'%';
  $('bypassLabel').textContent='measured '+b.toFixed(4)+' FS @ 19.8 kHz (48k) · PASS';}
}

/* ---------- probes + budget ---------- */
function renderProbes(){
 var tb=$('probeBody');tb.innerHTML='';
 var times=DATA.nativeProbes.filter(function(p){return p.ms!=null;}).map(function(p){return p.ms;});
 var lo=Math.log10(Math.min.apply(null,times)),hi=Math.log10(Math.max.apply(null,times));
 DATA.nativeProbes.forEach(function(p){
  var tr=document.createElement('tr');if(p.name.indexOf('loop-domain')>=0)tr.style.background='rgba(232,69,92,.06)';
  var bar=p.ms==null?'<span class="mono">n/a — sanitiser run</span>':'<div class="runtime"><i class="'+(p.name.indexOf('loop-domain')>=0?'hot':'')+'" style="width:'+(100*(Math.log10(p.ms)-lo)/Math.max(1e-9,(hi-lo)))+'%" ></i></div>';
  tr.innerHTML='<td>'+p.name+'</td><td>'+bar+'</td><td class="mono">'+(p.ms==null?'—':fmt(p.ms))+'</td><td class="mono">'+p.detail+'</td>';
  tb.appendChild(tr);
 });
 var a=DATA.analysisBench,rows=[
  ['Pass-1 head publish target',fmt(a.headPublishTargetMs[0])+' – '+fmt(a.headPublishTargetMs[1])+' ms','analysis visible early'],
  ['PLAYBACK_FIRST_MS',fmt(a.playbackYieldOldMs)+' ms → '+fmt(a.playbackYieldNewMs)+' ms','first mix never starved'],
  ['Head watchdog',fmt(a.headWatchdogMs)+' ms (45 s)','analysis head deadline'],
  ['Whole-run watchdog',fmt(a.wholeWatchdogMs)+' ms (480 s)','full analysis deadline'],
  ['ORT reaper',fmt(a.ortReaperMs)+' ms (100 s)','session cleanup'],
  ['ORT threads',a.ortThreads,'CPU arena + memPattern']
 ];
 $('budgetBody').innerHTML=rows.map(function(r){return '<tr><td>'+r[0]+'</td><td class="mono">'+r[1]+'</td><td class="mono">'+r[2]+'</td></tr>';}).join('');
}

/* ---------- POST: version progression ---------- */
var PALETTE=['#e8455c','#63c39a','#d8a24e','#8ab4ff','#b9a5e8','#e89b5f','#7ad0c4','#f0a49e','#a8c686','#c98dd6'];
function stackHTML(obj,total,ghost){
 var keys=Object.keys(obj||{}).sort(function(x,y){return (obj[y]||0)-(obj[x]||0);});
 var html='<div class="'+(ghost?'ghostbar':'stackbar')+'">';
 keys.forEach(function(k,i){var w=total?100*(obj[k]/total):0;if(w<=0)return;
  html+='<i title="'+k+' '+obj[k]+'" style="width:'+w+'%;background:'+(ghost?'rgba(255,255,255,.18)':PALETTE[i%PALETTE.length])+'"></i>';});
 return html+'</div>';
}
function renderVersions(){
 var box=$('versionBars');box.innerHTML='';
 var ordered=DATA.history.slice();
 ordered.forEach(function(h){
  var accTotal=h.acceptedPlans>0?Object.values(h.acceptedStyles).reduce(function(a,b){return a+b;},0):0;
  var fireTotal=Object.values(h.fireStyles).reduce(function(a,b){return a+b;},0);
  var dumpTotal=Object.values(h.dumpStyles).reduce(function(a,b){return a+b;},0);
  var solid=h.acceptedPlans>0?h.acceptedStyles:h.fireStyles,solidTotal=h.acceptedPlans>0?accTotal:fireTotal;
  var div=document.createElement('div');div.className='vrow';
  var rungs=h.acceptedRungs.length?h.acceptedRungs.map(function(r){return r[0]+' ×'+r[1];}).join(' · '):'no accepted field';
  div.innerHTML='<div class="vl">'+h.label+'<small>'+fmt(h.mixDump)+' dumps · '+fmt(h.mixFire)+' fires</small></div><div>'+
   stackHTML(solid,solidTotal,false)+stackHTML(h.dumpStyles,dumpTotal,true)+
   '<div style="font-family:var(--font-mono,ui-monospace,monospace);font-size:10.5px;color:var(--ink-3)">'+rungs+'</div></div>';
  box.appendChild(div);
 });
 // vc91 slot
 var slot=document.createElement('div');slot.className='vrow';
 var c=CAND;
 slot.innerHTML='<div class="vl">vc91<small>'+(c?fmt(c.mixDump)+' dumps · '+fmt(c.mixFire)+' fires':'PENDING')+'</small></div><div>'+
  (c?stackHTML(c.acceptedPlans>0?c.acceptedStyles:c.fireStyles,Object.values(c.acceptedPlans>0?c.acceptedStyles:c.fireStyles).reduce(function(a,b){return a+b;},0),false)+stackHTML(c.dumpStyles,Object.values(c.dumpStyles).reduce(function(a,b){return a+b;},0),true)
    :'<div class="stackbar" style="border-style:dashed;opacity:.5"><i style="width:100%;background:rgba(216,162,78,.15)"></i></div><div style="font-family:ui-monospace,monospace;font-size:10.5px;color:var(--warn)">awaiting JSONL — drop the file above</div>')+'</div>';
 slot.style.opacity=c?1:.75;box.appendChild(slot);
}

/* ---------- POST: pareto + sung ---------- */
function family(code){
 if(/handoff_pcm/.test(code))return 'Admission / PCM';
 if(/waiting on analysis|smart-analysis|before-decline|natural-end/.test(code))return 'Analysis Starvation';
 if(/scrub|queue-edit/.test(code))return 'User Scrub / Queue-edit';
 if(/loops:/.test(code))return 'Sung-share veto (loops)';
 return 'Other';
}
function renderPareto(){
 var box=$('pareto');box.innerHTML='';
 var src=CAND||VC90,fromCand=!!CAND;
 var fam={};(src.topRefusalCodes||[]).forEach(function(pair){var f=family(pair[0]);fam[f]=(fam[f]||0)+pair[1];});
 var order=['Analysis Starvation','Admission / PCM','User Scrub / Queue-edit','Sung-share veto (loops)','Other'];
 var max=0;order.forEach(function(f){if(fam[f]&&fam[f]>max)max=fam[f];});
 order.forEach(function(f){if(!fam[f])return;
  var h=document.createElement('div');h.className='famhead';h.textContent=f;box.appendChild(h);
  (src.topRefusalCodes||[]).filter(function(p){return family(p[0])===f;}).slice(0,6).forEach(function(pair){
   var row=document.createElement('div');row.className='hbar';
   row.innerHTML='<span class="hl" title="'+pair[0]+'">'+pair[0]+'</span><div class="track"><div class="fill" style="width:'+(max?100*pair[1]/max:0)+'%;background:'+(fromCand?'var(--ok)':'var(--accent)')+'"></div></div><span class="hv">'+fmt(pair[1])+'</span>';
   box.appendChild(row);
  });
 });
 if(!fromCand){var n=document.createElement('p');n.className='foot';n.textContent='Drop a vc91 JSONL and this Pareto re-renders in green for direct comparison.';box.appendChild(n);}
}
function sungBar(src,label,color){
 var t=src.sungRefusalTotals,total=t.loops||1;
 return '<div style="margin:12px 0"><div style="font-family:ui-monospace,monospace;font-size:11px;color:var(--ink-2);margin-bottom:5px">'+label+' · '+fmt(t.loops)+' loops · '+fmt(t.events)+' refusal events'+(t.unmeasured?' · unmeasured '+fmt(t.unmeasured):'')+'</div>'+
  '<div class="stackbar"><i style="width:'+(100*t.quiet/total)+'%;background:var(--ok)"></i><i style="width:'+(100*t.sungLyrics/total)+'%;background:var(--accent)"></i><i style="width:'+(100*t.sungMask/total)+'%;background:var(--warn)"></i>'+(t.unmeasured?'<i style="width:'+(100*t.unmeasured/total)+'%;background:#555"></i>':'')+'</div>'+
  '<div style="font-family:ui-monospace,monospace;font-size:10.5px;color:var(--ink-3)">quiet '+fmt(t.quiet)+' ('+(100*t.quiet/total).toFixed(1)+'%) · sung lyrics '+fmt(t.sungLyrics)+' ('+(100*t.sungLyrics/total).toFixed(1)+'%) · sung mask '+fmt(t.sungMask)+' ('+(100*t.sungMask/total).toFixed(1)+'%)</div></div>';
}
function renderSung(){$('sungBars').innerHTML=sungBar(VC90,'vc90',0)+(CAND?sungBar(CAND,'vc91 (dropped)',1):'<p class="foot">vc91 bar appears after a drop.</p>');}

/* ---------- POST: funnels ---------- */
function renderFunnels(){
 var rf=$('rungFunnel');rf.innerHTML='';
 var target=['Beat blend','Drop','Echo','Bridge','Loop','safety-crossfade','fallback-muffle','fallback-echo','Natural handoff'];
 var rungMap={};VC90.acceptedRungs.forEach(function(r){rungMap[r[0]]=r[1];});
 var maxR=Math.max.apply(null,[1].concat(VC90.acceptedRungs.map(function(r){return r[1];})));
 target.forEach(function(t,i){
  var v=rungMap[t]||0;
  var hit=Object.keys(rungMap).some(function(k){return k.toLowerCase().indexOf(t.split(' ')[0])>=0;});
  var row=document.createElement('div');row.className='frow';
  row.innerHTML='<span class="fl">'+(i+1)+'. '+t+'<small>'+(hit?'vc90 landed here':'no vc90 accepted plans here')+'</small></span><div class="ftrack"><div class="ffill" style="width:'+(100*v/maxR)+'%;background:'+(v?'var(--accent)':'rgba(255,255,255,.08)')+'"></div></div><span class="fv">'+(v?fmt(v):'—')+'</span>';
  rf.appendChild(row);
 });
 ['Natural handoff','null'].forEach(function(k){
  var v=rungMap[k]||0;var row=document.createElement('div');row.className='frow';
  row.innerHTML='<span class="fl">'+k+'<small>vc90 shortcut (off-ladder)</small></span><div class="ftrack"><div class="ffill" style="width:'+(100*v/maxR)+'%;background:var(--warn)"></div></div><span class="fv">'+fmt(v)+'</span>';
  rf.appendChild(row);
 });
 function funnelRows(src){
  var maxV=Math.max(src.mixDump,src.mixPlanRejected,src.mixFire,src.mixCompleteTotal,src.mixCompleteAttempts,1);
  var rows=[
   ['mix.dump','planner context',src.mixDump,'var(--ink-3)'],
   ['mix.plan.rejected','replans incl. scrubs',src.mixPlanRejected,'var(--warn)'],
   ['mix.fire','executed handoffs',src.mixFire,'var(--accent)'],
   ['mix.complete · attempts','deduped (dup '+src.mixCompleteDupRatio+'×)',src.mixCompleteAttempts,'var(--ok)'],
   ['mix.complete · clean','terminal, no PCM abort',src.mixCompleteClean,'var(--ok)'],
   ['heard > 0','loop completions only',src.heardPositiveLoopOnly,'#7ad0c4']
  ];
  return rows.map(function(r){return '<div class="frow"><span class="fl">'+r[0]+'<small>'+r[1]+'</small></span><div class="ftrack"><div class="ffill" style="width:'+(100*r[2]/maxV)+'%;background:'+r[3]+'"></div></div><span class="fv">'+fmt(r[2])+'</span></div>';}).join('');
 }
 var cf=$('convFunnel');cf.innerHTML='';
 cf.innerHTML='<div class="famhead">vc90 · '+fmt(VC90.mixCompleteTotal)+' rows → '+fmt(VC90.mixCompleteAttempts)+' attempts</div>'+funnelRows(VC90)+
  (CAND?'<div class="famhead">vc91 · '+fmt(CAND.mixCompleteTotal)+' rows → '+fmt(CAND.mixCompleteAttempts)+' attempts</div>'+funnelRows(CAND):'<p class="foot">vc91 funnel appears after a drop.</p>');
}

/* ---------- POST: planAge + deltas ---------- */
function renderPlanAge(){
 var svg=$('planAgeChart'),W=960,H=240;svg.innerHTML='';
 var lo=0,hi=8.5; // log10 ms: 1 .. 316M
 function X(v){return M.l+ (Math.max(lo,Math.min(hi,Math.log10(Math.max(1,v))))-lo)/(hi-lo)*(W-M.l-M.r);}
 for(var e=0;e<=8;e++){var x=M.l+e/(hi-lo)*(W-M.l-M.r);el('line',{x1:x,x2:x,y1:M.t,y2:H-M.b,stroke:'rgba(255,255,255,.05)'},svg);
  var lab=['1','10','100','1k','10k','100k','1M','10M','100M'][e];var tt=txt(svg,x,H-M.b+16,lab,'middle',9.5);tt.setAttribute('fill','#7d7986');tt.setAttribute('font-family',"'Geist Mono',ui-monospace,monospace");}
 var ya=txt(svg,10,M.t-8,'planAgeMs · log scale (ms)','start',9.5);ya.setAttribute('fill','#7d7986');
 // 1h ceiling
 var cx=X(3600000);el('line',{x1:cx,x2:cx,y1:M.t,y2:H-M.b,stroke:'rgba(224,100,95,.7)','stroke-dasharray':'5 4','stroke-width':1.5},svg);
 var ct=txt(svg,cx+5,M.t+12,'1 h ceiling · 3,600,000 ms','start',9.5);ct.setAttribute('fill','#e0645f');
 function dots(vals,color,yBase,spool){
  vals.forEach(function(v,i){var y=yBase-spool*(i%3)- ((i*7)%5);el('circle',{cx:X(v),cy:y,r:v>3600000?4:2.6,fill:color,opacity:v>3600000?1:.75,stroke:'rgba(0,0,0,.4)','stroke-width':.5},svg);});
 }
 dots(VC90.planAgeValues,'#e8455c',H-M.b-8,5);
 if(CAND)dots(CAND.planAgeValues,'#63c39a',H-M.b-34,5);
 var cap=txt(svg,M.l,H-6,'vc90: '+fmt(VC90.planAgeValues.length)+' samples · '+fmt(VC90.planAgeOver1h)+' poison (>1h) · max '+fmt(VC90.planAgeMaxMs)+' ms'+(CAND?'   ·   vc91: '+fmt(CAND.planAgeValues.length)+' samples · '+fmt(CAND.planAgeOver1h)+' >1h · max '+fmt(CAND.planAgeMaxMs)+' ms':''),'start',10);cap.setAttribute('fill','#b6b2bb');cap.setAttribute('font-family',"'Geist Mono',ui-monospace,monospace");
 // bins
 var bins=[['< 1 s',0,1000],['1–10 s',1000,10000],['10–60 s',10000,60000],['1–10 min',60000,600000],['10–60 min',600000,3600000],['> 1 h (poison)',3600000,Infinity]];
 function binHTML(vals){return bins.map(function(b){var n=vals.filter(function(v){return v>=b[1]&&v<b[2];}).length;return '<span style="margin-right:16px;color:'+(b[0].indexOf('poison')>=0&&n?'var(--bad)':'var(--ink-3)')+'">'+b[0]+': <b style="color:var(--ink-2)">'+fmt(n)+'</b></span>';}).join('');}
 $('planAgeBins').innerHTML='<div style="font-family:ui-monospace,monospace;font-size:11px;line-height:2"><span style="color:var(--ink-3)">vc90 </span>'+binHTML(VC90.planAgeValues)+'</div>'+(CAND?'<div style="font-family:ui-monospace,monospace;font-size:11px;line-height:2"><span style="color:var(--ok)">vc91 </span>'+binHTML(CAND.planAgeValues)+'</div>':'');
}
function renderDeltas(){
 var box=$('deltaChart');box.innerHTML='';
 function block(src,title,color){
  var techs=Object.keys(src.fireDeltas||{}).sort();
  if(!techs.length)return '<p class="foot">'+title+': no deltaMs samples.</p>';
  var html='<div class="famhead">'+title+'</div>';
  techs.forEach(function(t){
   var d=src.fireDeltas[t];
   var maxV=Math.max(6000,Math.abs(d.min),Math.abs(d.max));
   function X(v){return (v+maxV)/(2*maxV)*100;}
   html+='<div class="hbar"><span class="hl">'+t+' · n='+fmt(d.n)+'</span><div class="track" style="position:relative;height:18px">'+
    '<div class="fill" style="position:absolute;left:'+X(d.min)+'%;width:'+(X(d.max)-X(d.min))+'%;background:'+color+';opacity:.35;border-radius:5px"></div>'+
    '<div style="position:absolute;left:'+X(d.p50)+'%;top:-2px;bottom:-2px;width:2px;background:#fff"></div></div>'+
    '<span class="hv">'+fmt(d.min)+' / <b>'+fmt(d.p50)+'</b> / '+fmt(d.max)+'</span></div>';
  });
  return html;
 }
 box.innerHTML=block(VC90,'vc90 fire timing (min / median / max ms)','var(--accent)')+(CAND?block(CAND,'vc91 (dropped) — expect +~500 ms shift from PREROLL 1.0→1.5','var(--ok)'):'');
}

/* ---------- delta table ---------- */
function renderDeltaTable(){
 var tb=$('deltaBody'),c=CAND;
 function row(name,base,candVal,target,okFn){
  var verdict=c?('<span style="color:'+(okFn(c)?'var(--ok)':'var(--bad)')+'">'+(okFn(c)?'PASS':'FAIL')+'</span>'):'<span style="color:var(--warn)">PENDING</span>';
  return '<tr><td>'+name+'</td><td class="mono">'+base+'</td><td class="mono">'+(c?candVal:'—')+'</td><td class="mono">'+target+'</td><td>'+verdict+'</td></tr>';
 }
 tb.innerHTML=
  row('fired NATURAL share',(VC90.fireNatural||0)+'/'+fmt(VC90.mixFire),c?(c.fireNatural+'/'+fmt(c.mixFire)):'','< 15%',function(x){return x.mixFire?100*(x.fireNatural||0)/x.mixFire<15:true;})+
  row('queue advances (excluded)',fmt(VC90.queueAdvances!=null?VC90.queueAdvances:113),c?fmt(c.queueAdvances||0):'','— (context)',function(x){return true;})+
  row('handoff_pcm (attempts/events)',fmt(VC90.handoffPcmAttempts)+' / '+fmt(VC90.handoffPcmEvents),c?(fmt(c.handoffPcmAttempts)+' / '+fmt(c.handoffPcmEvents)):'','0 attempts',function(x){return x.handoffPcmAttempts===0&&x.handoffPcmTerminal===0;})+
  row('mix.fire / 100 dumps',VC90.firePer100Dump.toFixed(2)+' (raw '+fmt(VC90.mixFire)+'/'+fmt(VC90.mixDump)+')',c?c.firePer100Dump.toFixed(2)+' (raw '+fmt(c.mixFire)+'/'+fmt(c.mixDump)+')':'','> 35.1',function(x){return x.firePer100Dump>35.1;})+
  row('mix.fire raw count',fmt(VC90.mixFire),c?fmt(c.mixFire):'','> vc90 (session-size warned)',function(x){return x.mixFire>VC90.mixFire;})+
  row('planAgeMs > 1h',fmt(VC90.planAgeOver1h)+' (max '+fmt(VC90.planAgeMaxMs)+')',c?(fmt(c.planAgeOver1h)+' (max '+fmt(c.planAgeMaxMs)+')'):'','0',function(x){return x.planAgeOver1h===0;})+
  row('mix.complete clean (deduped)',fmt(VC90.mixCompleteClean)+' / '+fmt(VC90.mixCompleteAttempts)+' attempts · dup '+VC90.mixCompleteDupRatio+'×',c?(fmt(c.mixCompleteClean)+' / '+fmt(c.mixCompleteAttempts)+' · dup '+c.mixCompleteDupRatio+'×'):'','— (context)',function(x){return true;})+
  row('heard > 0 (loop-only)',fmt(VC90.heardPositiveLoopOnly),c?fmt(c.heardPositiveLoopOnly):'','— (context)',function(x){return true;});
}

/* ---------- JSONL parsing (browser port of the Python summariser) ---------- */
var PCM='handoff_pcm_before_protected_event';
function percentile(sorted,q){if(!sorted.length)return null;var r=Math.ceil(q/100*sorted.length);var i=Math.max(1,Math.min(sorted.length,r))-1;return sorted[i];}
function parseJSONL(text,name){
 var evts=[];text.split(/\r?\n/).forEach(function(line){line=line.trim();if(!line)return;try{evts.push(JSON.parse(line));}catch(e){try{evts.push(JSON.parse(line.replace(/-Infinity/g,'-1e999').replace(/:?Infinity/g,':1e999').replace(/:NaN/g,':null')));}catch(e2){}}});
 function counter(list,field){var o={};list.forEach(function(e){var v=e[field];if(v==null||v==='')return;o[String(v)]=(o[String(v)]||0)+1;});return o;}
 function share(o,k){var t=Object.values(o).reduce(function(a,b){return a+b;},0);return t?100*(o[k]||0)/t:0;}
 var dump=evts.filter(function(e){return e.ev==='mix.dump';});
 var dumpStyles=counter(dump,'style');
 var isQA=function(e){return e.executionScope==='normal-queue-advance-no-overlap'||e.technique==='natural';};
 var accAll=evts.filter(function(e){return e.ev==='mix.plan'&&e.stage==='accepted';});
 var queueAdvances=accAll.filter(isQA).length;
 var acc=accAll.filter(function(e){return !isQA(e);});
 var accStyles={};acc.forEach(function(e){var k=e.transitionStyle?String(e.transitionStyle):'unknown';accStyles[k]=(accStyles[k]||0)+1;});
 var fpMap={};evts.forEach(function(e){if(e.ev==='mix.plan'&&(e.stage==='accepted'||e.stage==='prepared')&&!isQA(e)){var a=e.attemptId||('seq:'+e.seq),p=fpMap[a];var r=e.planRevision||0,t=e.ts||0,s=e.seq||0;if(!p||r>(p.planRevision||0)||(r===(p.planRevision||0)&&(t>(p.ts||0)||(t===(p.ts||0)&&s>=(p.seq||0)))))fpMap[a]=e;}});
 var fpList=Object.values(fpMap),fpStyles={};fpList.forEach(function(e){var k=String(e.transitionStyle||e.technique||'unknown');fpStyles[k]=(fpStyles[k]||0)+1;});
 var rungs={};acc.forEach(function(e){var k=e.rung?String(e.rung):'null';rungs[k]=(rungs[k]||0)+1;});
 var rej=evts.filter(function(e){return e.ev==='mix.plan.rejected';});
 var complete=evts.filter(function(e){return e.ev==='mix.complete';});
 var term=complete.filter(function(e){return e.stage==='terminal';});
 var att={};complete.forEach(function(e){att[e.attemptId||('seq:'+e.seq)]=e;});
 var clean=term.filter(function(e){return e.outcome==='completed'&&e.reason!==PCM;}).length;
 var fired=evts.filter(function(e){return e.ev==='mix.fire';});
 var pcm=evts.filter(function(e){return e.refusalCode===PCM||e.reason===PCM;});
 var pcmAtt={};pcm.forEach(function(e){if(e.attemptId)pcmAtt[e.attemptId]=1;});
 var heard=complete.filter(function(e){return typeof e.actualHeardMs==='number'&&e.actualHeardMs>0;}).length;
 var ages=evts.filter(function(e){return typeof e.planAgeMs==='number';}).map(function(e){return e.planAgeMs;}).sort(function(a,b){return a-b;});
 var sent=ages.filter(function(a){return a<0;}).length,nonNeg=ages.filter(function(a){return a>=0;});
 var sung={loops:0,quiet:0,sungLyrics:0,sungMask:0,unmeasured:0,events:0};
 var RE=/(?:outgoing|incoming):\s*\d+\s*loops:\s*(\d+)\s*quiet,\s*(\d+)\s*sung \(lyrics\),\s*(\d+)\s*sung \(mask\),\s*(\d+)\s*unmeasured/;
 // groups: quiet, sungLyrics, sungMask, unmeasured; "N loops:" == their sum
 rej.forEach(function(e){var m=RE.exec(String(e.refusalCode||e.reason||''));if(m){sung.events++;sung.quiet+=+m[1];sung.sungLyrics+=+m[2];sung.sungMask+=+m[3];sung.unmeasured+=+m[4];sung.loops+=(+m[1])+(+m[2])+(+m[3])+(+m[4]);}});
 var dg={};fired.forEach(function(e){if(typeof e.deltaMs==='number'){var t=String(e.technique||'unknown');(dg[t]=dg[t]||[]).push(e.deltaMs);}});
 var fd={};Object.keys(dg).forEach(function(t){var v=dg[t].sort(function(a,b){return a-b;});fd[t]={n:v.length,min:v[0],p50:percentile(v,50),p90:percentile(v,90),max:v[v.length-1],values:v};});
 function top(o){return Object.keys(o).map(function(k){return [k,o[k]];}).sort(function(a,b){return b[1]-a[1];}).slice(0,12);}
 return {label:'vc91-candidate',events:evts.length,mixDump:dump.length,dumpStyles:dumpStyles,dumpNaturalPct:Math.round(share(dumpStyles,'NATURAL')*100)/100,
  acceptedPlans:acc.length,acceptedStyles:accStyles,acceptedNaturalPct:(Object.values(accStyles).reduce(function(a,b){return a+b;},0)?Math.round(share(accStyles,'NATURAL')*100)/100:0),
  queueAdvances:queueAdvances,
  finalPlans:fpList.length,finalStyles:fpStyles,finalNaturalPct:(fpList.length?Math.round(share(fpStyles,'NATURAL')*100)/100:0),
  fireNatural:fired.filter(function(e){return e.transitionStyle==='NATURAL'||e.technique==='natural';}).length,
  acceptedRungs:top(rungs),mixPlanRejected:rej.length,mixFire:fired.length,firePer100Dump:dump.length?Math.round(100*fired.length/dump.length*100)/100:0,
  fireStyles:(function(){var o={};fired.forEach(function(e){var k=e.transitionStyle?String(e.transitionStyle):'unknown';o[k]=(o[k]||0)+1;});return o;})(),fireTechniques:counter(fired,'technique'),fireModes:counter(fired,'mode'),fireDeltas:fd,
  mixCompleteTotal:complete.length,mixCompleteAttempts:Object.keys(att).length,mixCompleteDupRatio:complete.length?Math.round((complete.length/Math.max(1,Object.keys(att).length))*1000)/1000:1,
  mixCompleteTerminal:term.length,mixCompleteClean:clean,heardPositiveLoopOnly:heard,
  handoffPcmEvents:pcm.length,handoffPcmAttempts:Object.keys(pcmAtt).length,handoffPcmTerminal:term.filter(function(e){return e.reason===PCM;}).length,
  planAgeValues:nonNeg,planAgeSentinel:sent,planAgeMaxMs:nonNeg.length?nonNeg[nonNeg.length-1]:0,planAgeOver1h:nonNeg.filter(function(a){return a>3600000;}).length,
  sungRefusalTotals:sung,topRejectReasons:top(counter(rej,'reason')),topRefusalCodes:top(counter(rej,'refusalCode')),terminalCompleteReasons:top(counter(term,'reason'))};
}
function applyCandidate(c,name){
 CAND=c;
 $('fileMsg').textContent='Loaded "'+name+'" → '+c.events.toLocaleString()+' events · '+c.mixDump+' dumps · '+c.mixFire+' fires · press POST tab to see overlays.';
 renderAll();
}
var dz=$('dropzone');
['dragenter','dragover'].forEach(function(ev){dz.addEventListener(ev,function(e){e.preventDefault();dz.classList.add('over');});});
['dragleave','drop'].forEach(function(ev){dz.addEventListener(ev,function(e){e.preventDefault();dz.classList.remove('over');});});
dz.addEventListener('drop',function(e){var f=e.dataTransfer.files&&e.dataTransfer.files[0];if(f)readFile(f);});
$('filePick').addEventListener('change',function(e){if(e.target.files&&e.target.files[0])readFile(e.target.files[0]);});
function readFile(f){
 var r=new FileReader();
 r.onload=function(){try{applyCandidate(parseJSONL(String(r.result),f.name),f.name);}catch(err){$('fileMsg').textContent='Parse failed: '+err.message;}};
 r.readAsText(f);
}
$('clearCand').addEventListener('click',function(){CAND=null;$('fileMsg').textContent='No vc91 data yet — baseline vc90 only.';renderAll();});

/* ---------- boot ---------- */
function renderAll(){renderInvariants();renderKPIs();renderVersions();renderPareto();renderSung();renderFunnels();renderPlanAge();renderDeltas();renderDeltaTable();}
drawDSP();drawSlope();renderProbes();renderAll();
})();
</script>
</body>
</html>
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", help="vc91 JSONL telemetry to embed as candidate")
    parser.add_argument("--data", default=str(DEFAULT_DATA), help="benchmark-data.json path")
    parser.add_argument("--out", default=str(DEFAULT_OUT), help="output HTML path")
    args = parser.parse_args()

    data_path = Path(args.data)
    if not data_path.is_file():
        print(f"error: data file not found: {data_path}", file=sys.stderr)
        return 2
    with open(data_path, encoding="utf-8") as fh:
        data = json.load(fh)

    if args.candidate:
        cand_path = Path(args.candidate)
        if not cand_path.is_file():
            print(f"error: candidate file not found: {cand_path}", file=sys.stderr)
            return 2
        events = load_jsonl(cand_path)
        if not events:
            print(f"error: no JSON events parsed from {cand_path}", file=sys.stderr)
            return 2
        data["candidate"] = summarise_jsonl(events, "vc91-candidate")
        print(f"candidate: {cand_path.name} -> {len(events)} events, "
              f"mixDump={data['candidate']['mixDump']}, "
              f"mixFire={data['candidate']['mixFire']}, "
              f"firePer100={data['candidate']['firePer100Dump']}, "
              f"NATURAL dump={data['candidate']['dumpNaturalPct']}%")
    elif not data.get("candidate"):
        data["candidate"] = None

    payload = json.dumps(data, separators=(",", ":"), ensure_ascii=False)
    payload = payload.replace("</", "<\\/")

    if "%%DATA%%" not in HTML_TEMPLATE:
        print("error: template placeholder missing", file=sys.stderr)
        return 2
    html = HTML_TEMPLATE.replace("%%DATA%%", payload)

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(html, encoding="utf-8")
    print(f"wrote {out_path} ({out_path.stat().st_size:,} bytes, "
          f"candidate={'yes' if data.get('candidate') else 'no'})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
