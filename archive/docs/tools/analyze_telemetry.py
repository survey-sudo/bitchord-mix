#!/usr/bin/env python3
"""Automix telemetry correlation and diagnostic analyzer for BitChord.

Parses automix-telemetry.jsonl, correlates planning/dump/execution/verification
events into transition episodes and per-pair (outId -> inId) summaries, and
computes exact breakdowns for D1 (drifting beat grid), D3 (decline), D4 (Camelot
A/B key distribution), D5 (octave BPM pairs), and loop stability.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


# Portable default: preserves the historical evidence tail path relative to
# the current user's home directory, without embedding a developer-home literal
# in tracked source. Callers can override with --input.
DEFAULT_INPUT = str(
    Path.home()
    / "Bitchord"
    / "evidence"
    / "bitchord-device-evidence"
    / "vc71-device"
    / "automix-telemetry-latest.jsonl"
)
DEFAULT_VC71_SINCE_TS = 1794730998000

STEPS_RE = re.compile(r"\bsteps=([^\s}]+)")
WATCHDOG_KEY_RE = re.compile(r"^([^|>]+)->([^|>]+)\|")


def fmt_ts(ts: Optional[int]) -> str:
    if ts is None:
        return "n/a"
    dt = datetime.fromtimestamp(ts / 1000.0, tz=timezone.utc)
    return dt.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3] + "Z"


def fmt_num(val: Any, digits: int = 2) -> str:
    if val is None or not isinstance(val, (int, float)):
        return "-"
    if math.isnan(val) or math.isinf(val):
        return str(val)
    return f"{val:.{digits}f}"


def sanitize_md(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def pair_label(out_id: str, in_id: str) -> str:
    return f"{out_id} -> {in_id}"


def bpm_sig(
    bpm_a: Any, bpm_b: Any, cam_l: str = "", cam_r: str = ""
) -> Optional[Tuple[float, float, str, str]]:
    if not isinstance(bpm_a, (int, float)) or not isinstance(bpm_b, (int, float)):
        return None
    if bpm_a <= 0.0 and bpm_b <= 0.0:
        return None
    return (round(float(bpm_a), 3), round(float(bpm_b), 3), cam_l or "", cam_r or "")


def bpm_only_sig(bpm_a: Any, bpm_b: Any) -> Optional[Tuple[float, float]]:
    if not isinstance(bpm_a, (int, float)) or not isinstance(bpm_b, (int, float)):
        return None
    if bpm_a <= 0.0 and bpm_b <= 0.0:
        return None
    return (round(float(bpm_a), 3), round(float(bpm_b), 3))


@dataclass
class TelemetryRecord:
    line_no: int
    raw: Dict[str, Any]
    ts: int
    seq: int
    ev: str
    session_id: int = 0
    resolved_pair: Optional[Tuple[str, str]] = None


@dataclass
class Episode:
    episode_id: int
    session_id: int
    pair: Optional[Tuple[str, str]]
    start_ts: int
    end_ts: int
    plans: List[TelemetryRecord] = field(default_factory=list)
    dumps: List[TelemetryRecord] = field(default_factory=list)
    rejects: List[TelemetryRecord] = field(default_factory=list)
    fallbacks: List[TelemetryRecord] = field(default_factory=list)
    arm_deaths: List[TelemetryRecord] = field(default_factory=list)
    fires: List[TelemetryRecord] = field(default_factory=list)
    verifies: List[TelemetryRecord] = field(default_factory=list)
    completes: List[TelemetryRecord] = field(default_factory=list)
    watchdogs: List[TelemetryRecord] = field(default_factory=list)
    seeks: List[TelemetryRecord] = field(default_factory=list)
    others: List[TelemetryRecord] = field(default_factory=list)
    cancelled: bool = False
    closed: bool = False

    def pair_str(self) -> str:
        if self.pair:
            return pair_label(self.pair[0], self.pair[1])
        for p in reversed(self.plans):
            ba = p.raw.get("bpmA", 0.0)
            bb = p.raw.get("bpmB", 0.0)
            if ba or bb:
                return f"<unmapped:{fmt_num(ba, 1)}->{fmt_num(bb, 1)}>"
        return "<unmapped>"

    def add(self, rec: TelemetryRecord) -> None:
        self.end_ts = max(self.end_ts, rec.ts)
        ev = rec.ev
        if ev == "mix.plan":
            self.plans.append(rec)
        elif ev == "mix.dump":
            self.dumps.append(rec)
        elif ev == "mix.plan.rejected":
            self.rejects.append(rec)
        elif ev == "mix.loop.fallback":
            self.fallbacks.append(rec)
        elif ev == "mix.arm.death":
            self.arm_deaths.append(rec)
        elif ev == "mix.fire":
            self.fires.append(rec)
        elif ev == "mix.verify":
            self.verifies.append(rec)
        elif ev == "mix.complete":
            self.completes.append(rec)
        elif ev == "mix.watchdog":
            self.watchdogs.append(rec)
        elif ev == "mix.seek":
            self.seeks.append(rec)
        else:
            self.others.append(rec)


def load_records(input_path: Path) -> Tuple[List[TelemetryRecord], int, int]:
    records: List[TelemetryRecord] = []
    total_lines = 0
    bad_lines = 0
    with input_path.open("r", encoding="utf-8") as fh:
        for idx, line in enumerate(fh, start=1):
            s = line.strip()
            if not s:
                continue
            total_lines += 1
            try:
                obj = json.loads(s)
            except json.JSONDecodeError:
                bad_lines += 1
                continue
            ts = int(obj.get("ts") or 0)
            seq = int(obj.get("seq") or 0)
            ev = str(obj.get("ev") or "<unknown>")
            records.append(TelemetryRecord(line_no=idx, raw=obj, ts=ts, seq=seq, ev=ev))

    # Assign session IDs by detecting seq resets (seq <= prev_seq).
    session_id = 0
    prev_seq: Optional[int] = None
    for rec in records:
        if prev_seq is not None and rec.seq <= prev_seq:
            session_id += 1
        rec.session_id = session_id
        prev_seq = rec.seq

    return records, total_lines, bad_lines


def correlate_episodes(records: List[TelemetryRecord]) -> List[Episode]:
    # Pass 1: Build lookup maps from mix.dump + adjacent mix.plan, and mix.watchdog.
    session_sig_to_pair: Dict[Tuple[int, Tuple[float, float, str, str]], Tuple[str, str]] = {}
    global_sig_to_pair: Dict[Tuple[float, float, str, str], Tuple[str, str]] = {}
    global_bpm_to_pair: Dict[Tuple[float, float], Tuple[str, str]] = {}
    dump_to_plans: Dict[int, List[TelemetryRecord]] = defaultdict(list)

    # Track recent mix.plan records within a session to bind them to the next mix.dump.
    recent_plans: List[TelemetryRecord] = []
    cur_sess = -1
    for idx, rec in enumerate(records):
        if rec.session_id != cur_sess:
            recent_plans.clear()
            cur_sess = rec.session_id

        if rec.ev == "mix.plan":
            recent_plans.append(rec)
        elif rec.ev == "mix.dump":
            out_id = str(rec.raw.get("outId") or "")
            in_id = str(rec.raw.get("inId") or "")
            if out_id and in_id:
                pair = (out_id, in_id)
                rec.resolved_pair = pair
                # Bind preceding plans within 2500ms belonging to this burst.
                matched_plans: List[TelemetryRecord] = []
                for p in reversed(recent_plans):
                    if rec.ts - p.ts > 2500:
                        break
                    if not matched_plans:
                        matched_plans.append(p)
                    else:
                        ref = matched_plans[0].raw
                        if (
                            round(float(p.raw.get("bpmA") or 0.0), 3)
                            == round(float(ref.get("bpmA") or 0.0), 3)
                            and round(float(p.raw.get("bpmB") or 0.0), 3)
                            == round(float(ref.get("bpmB") or 0.0), 3)
                        ):
                            matched_plans.append(p)
                        else:
                            break
                for p in matched_plans:
                    p.resolved_pair = pair
                    dump_to_plans[rec.line_no].append(p)
                    sig = bpm_sig(
                        p.raw.get("bpmA"),
                        p.raw.get("bpmB"),
                        str(p.raw.get("camelotLeft") or ""),
                        str(p.raw.get("camelotRight") or ""),
                    )
                    if sig is not None:
                        session_sig_to_pair[(rec.session_id, sig)] = pair
                        global_sig_to_pair[sig] = pair
                    bsig = bpm_only_sig(p.raw.get("bpmA"), p.raw.get("bpmB"))
                    if bsig is not None:
                        global_bpm_to_pair[bsig] = pair
                recent_plans.clear()
        elif rec.ev == "mix.watchdog":
            m = WATCHDOG_KEY_RE.match(str(rec.raw.get("key") or ""))
            if m:
                rec.resolved_pair = (m.group(1), m.group(2))

    # Resolve remaining mix.plan and mix.plan.rejected via BPM/Camelot fingerprints.
    for rec in records:
        if rec.resolved_pair is not None:
            continue
        if rec.ev in ("mix.plan", "mix.plan.rejected", "mix.complete"):
            sig = bpm_sig(
                rec.raw.get("bpmA"),
                rec.raw.get("bpmB"),
                str(rec.raw.get("camelotLeft") or ""),
                str(rec.raw.get("camelotRight") or ""),
            )
            if sig is not None:
                rec.resolved_pair = session_sig_to_pair.get(
                    (rec.session_id, sig)
                ) or global_sig_to_pair.get(sig)
            if rec.resolved_pair is None:
                bsig = bpm_only_sig(rec.raw.get("bpmA"), rec.raw.get("bpmB"))
                if bsig is not None:
                    rec.resolved_pair = global_bpm_to_pair.get(bsig)

    # Pass 2: Build transition episodes chronologically within each session.
    episodes: List[Episode] = []
    ep_counter = 0
    current: Optional[Episode] = None

    def new_episode(rec: TelemetryRecord, pair: Optional[Tuple[str, str]]) -> Episode:
        nonlocal ep_counter
        ep_counter += 1
        ep = Episode(
            episode_id=ep_counter,
            session_id=rec.session_id,
            pair=pair,
            start_ts=rec.ts,
            end_ts=rec.ts,
        )
        episodes.append(ep)
        return ep

    for rec in records:
        if current is not None and current.session_id != rec.session_id:
            current.closed = True
            current = None

        ev = rec.ev
        pair = rec.resolved_pair

        # Decide when a new planning event starts a distinct episode.
        if ev in ("mix.plan.rejected", "mix.plan", "mix.dump"):
            if ev == "mix.plan.rejected" and rec.raw.get("reason") == "scrub":
                if current is None:
                    current = new_episode(rec, pair)
                current.add(rec)
                current.cancelled = True
                current.closed = True
                continue

            # If current episode already fired/completed or had a scrub/error, open fresh.
            if current is None or current.closed or current.fires or current.completes:
                current = new_episode(rec, pair)
            elif pair is not None and current.pair is not None and pair != current.pair:
                # Pair changed (user skipped / previewed another next track); supersede old.
                current.closed = True
                current = new_episode(rec, pair)
            elif pair is not None and current.pair is None:
                # Only attach resolved pair if compatible with existing BPMs in current.
                cur_bpm_sigs = {
                    bpm_only_sig(p.raw.get("bpmA"), p.raw.get("bpmB"))
                    for p in current.plans
                } - {None}
                rec_bsig = bpm_only_sig(rec.raw.get("bpmA"), rec.raw.get("bpmB"))
                if not cur_bpm_sigs or rec_bsig is None or rec_bsig in cur_bpm_sigs:
                    current.pair = pair
                else:
                    current.closed = True
                    current = new_episode(rec, pair)
            elif (
                ev == "mix.plan"
                and current.dumps
                and (rec.ts - current.dumps[-1].ts > 1500)
            ):
                # Same pair re-planned >1.5s later without changing pair: keep same episode
                # if it hasn't fired yet; wait—keeping same pair episode avoids splitting
                # lock/re-plan attempts before mix.fire into artificial duplicate rows.
                pass

            current.add(rec)
            continue

        # Execution / teardown / seek events attach to the current episode.
        if current is None:
            current = new_episode(rec, pair)

        if ev == "mix.watchdog" and pair is not None and current.pair is None:
            current.pair = pair
        if ev == "mix.complete" and current.pair is None and pair is not None:
            current.pair = pair

        current.add(rec)

        if ev == "mix.seek" and rec.raw.get("action") == "cancel":
            current.cancelled = True
        if ev in ("mix.complete", "mix.error", "mix.freeze.queueEdit", "mix.freeze.onInterruption"):
            current.closed = True

    # Propagate episode-level resolved pair back to constituent records (e.g., mix.verify).
    for ep in episodes:
        if ep.pair is not None:
            for r in (
                ep.plans
                + ep.dumps
                + ep.rejects
                + ep.fallbacks
                + ep.arm_deaths
                + ep.fires
                + ep.verifies
                + ep.completes
                + ep.watchdogs
                + ep.seeks
                + ep.others
            ):
                if r.resolved_pair is None:
                    r.resolved_pair = ep.pair

    return episodes


def match_pair_filter(pair: Optional[Tuple[str, str]], pair_filter: Optional[str]) -> bool:
    if not pair_filter:
        return True
    if pair is None:
        return False
    norm = pair_filter.replace("→", "->").strip()
    lbl = pair_label(pair[0], pair[1])
    if "->" in norm:
        parts = [p.strip() for p in norm.split("->", 1)]
        return pair[0] == parts[0] and pair[1] == parts[1]
    return norm in lbl


def filter_episodes_for_window(
    episodes: List[Episode], since_ts: Optional[int], pair_filter: Optional[str]
) -> List[Episode]:
    out = []
    for ep in episodes:
        if since_ts is not None and ep.end_ts < since_ts:
            continue
        if not match_pair_filter(ep.pair, pair_filter):
            continue
        out.append(ep)
    return out


def filter_records_for_window(
    records: List[TelemetryRecord], since_ts: Optional[int], pair_filter: Optional[str]
) -> List[TelemetryRecord]:
    out = []
    for rec in records:
        if since_ts is not None and rec.ts < since_ts:
            continue
        if pair_filter and not match_pair_filter(rec.resolved_pair, pair_filter):
            continue
        out.append(rec)
    return out


def analyze_window(
    title: str,
    records: List[TelemetryRecord],
    episodes: List[Episode],
    since_ts: Optional[int],
) -> Dict[str, Any]:
    ev_counts = Counter(r.ev for r in records)
    sessions = sorted({r.session_id for r in records})
    ts_vals = [r.ts for r in records if r.ts > 0]
    min_ts = min(ts_vals) if ts_vals else None
    max_ts = max(ts_vals) if ts_vals else None
    span_min = ((max_ts - min_ts) / 60000.0) if (min_ts and max_ts) else 0.0
    since_span_min = (
        ((max_ts - since_ts) / 60000.0)
        if (since_ts is not None and max_ts is not None and max_ts >= since_ts)
        else None
    )

    # Per-pair aggregation.
    pair_stats: Dict[str, Dict[str, Any]] = {}

    def get_ps(key: str) -> Dict[str, Any]:
        if key not in pair_stats:
            pair_stats[key] = {
                "pair": key,
                "episodes": 0,
                "plans": 0,
                "dumps": 0,
                "rejects": 0,
                "fallbacks": 0,
                "arm_deaths": 0,
                "fires_loop": 0,
                "fires_classic": 0,
                "verifies_ok": 0,
                "verifies_fail": 0,
                "completes_handoff": 0,
                "completes_fail": 0,
                "templates": Counter(),
                "styles": Counter(),
                "dump_reasons": Counter(),
                "gates": Counter(),
                "reject_reasons": Counter(),
                "fallback_reasons": Counter(),
                "death_reasons": Counter(),
                "complete_reasons": Counter(),
                "bpms": set(),
                "camelots": set(),
                "qualities": set(),
                "statuses": set(),
                "decline_flags": 0,
                "decline_variant": 0,
            }
        return pair_stats[key]

    for ep in episodes:
        key = ep.pair_str()
        ps = get_ps(key)
        ps["episodes"] += 1
        ps["plans"] += len(ep.plans)
        ps["dumps"] += len(ep.dumps)
        ps["rejects"] += len(ep.rejects)
        ps["fallbacks"] += len(ep.fallbacks)
        ps["arm_deaths"] += len(ep.arm_deaths)
        for p in ep.plans:
            ps["templates"][str(p.raw.get("template") or "-")] += 1
            ba = p.raw.get("bpmA")
            bb = p.raw.get("bpmB")
            if isinstance(ba, (int, float)) and isinstance(bb, (int, float)) and (ba > 0 or bb > 0):
                ps["bpms"].add(f"{ba:.2f}->{bb:.2f}")
            cl = str(p.raw.get("camelotLeft") or "")
            cr = str(p.raw.get("camelotRight") or "")
            if cl or cr:
                ps["camelots"].add(f"{cl or '?'}->{cr or '?'}")
            qa = str(p.raw.get("qualityA") or "")
            qb = str(p.raw.get("qualityB") or "")
            if qa or qb:
                ps["qualities"].add(f"{qa}/{qb}")
            sa = str(p.raw.get("statusA") or "")
            sb = str(p.raw.get("statusB") or "")
            if sa or sb:
                ps["statuses"].add(f"{sa}/{sb}")
            if p.raw.get("decline") or p.raw.get("declineToMix"):
                ps["decline_flags"] += 1
            if p.raw.get("filterVariant") == "DECLINE":
                ps["decline_variant"] += 1
        for d in ep.dumps:
            ps["styles"][str(d.raw.get("style") or "-")] += 1
            ps["dump_reasons"][str(d.raw.get("reason") or "-")] += 1
            ps["gates"][str(d.raw.get("gates") or "-")] += 1
        for r in ep.rejects:
            ps["reject_reasons"][str(r.raw.get("reason") or "-")] += 1
        for fb in ep.fallbacks:
            ps["fallback_reasons"][str(fb.raw.get("reason") or "-")] += 1
        for ad in ep.arm_deaths:
            ps["death_reasons"][str(ad.raw.get("reason") or "-")] += 1
        for f in ep.fires:
            mode = str(f.raw.get("mode") or "")
            if mode == "loop":
                ps["fires_loop"] += 1
            elif mode == "classic":
                ps["fires_classic"] += 1
        for v in ep.verifies:
            if v.raw.get("ok") is True:
                ps["verifies_ok"] += 1
            else:
                ps["verifies_fail"] += 1
        for c in ep.completes:
            ps["complete_reasons"][str(c.raw.get("reason") or "-")] += 1
            if c.raw.get("handoff") is True:
                ps["completes_handoff"] += 1
            else:
                ps["completes_fail"] += 1

    # D1: All distinct gates and drifting beat grid occurrences.
    gates_counter = Counter()
    drifting_rows: List[Dict[str, Any]] = []
    for ep in episodes:
        for d in ep.dumps:
            if since_ts is not None and d.ts < since_ts:
                continue
            g = str(d.raw.get("gates") or "")
            gates_counter[g] += 1
            if "drifting beat grid" in g:
                # Find nearest preceding/sibling mix.plan in this episode.
                best_plan = None
                for p in reversed(ep.plans):
                    if p.ts <= d.ts:
                        best_plan = p
                        break
                if best_plan is None and ep.plans:
                    best_plan = ep.plans[0]
                fired_modes = [str(f.raw.get("mode") or "") for f in ep.fires]
                comp_reasons = [
                    f"{c.raw.get('reason')}(handoff={c.raw.get('handoff')})"
                    for c in ep.completes
                ]
                fb_reasons = [str(fb.raw.get("reason")) for fb in ep.fallbacks]
                ad_reasons = [str(ad.raw.get("reason")) for ad in ep.arm_deaths]
                drifting_rows.append(
                    {
                        "ts": d.ts,
                        "seq": d.seq,
                        "pair": ep.pair_str(),
                        "gates": g,
                        "style": d.raw.get("style", ""),
                        "reason": d.raw.get("reason", ""),
                        "confidence": d.raw.get("confidence"),
                        "flowScore": d.raw.get("flowScore"),
                        "bpmA": best_plan.raw.get("bpmA") if best_plan else None,
                        "bpmB": best_plan.raw.get("bpmB") if best_plan else None,
                        "qualityA": best_plan.raw.get("qualityA") if best_plan else "",
                        "qualityB": best_plan.raw.get("qualityB") if best_plan else "",
                        "statusA": best_plan.raw.get("statusA") if best_plan else "",
                        "statusB": best_plan.raw.get("statusB") if best_plan else "",
                        "templates": sorted({str(p.raw.get("template")) for p in ep.plans}),
                        "fired": fired_modes,
                        "fallbacks": fb_reasons,
                        "arm_deaths": ad_reasons,
                        "completes": comp_reasons,
                        "cancelled": ep.cancelled,
                    }
                )

    # D3: Decline breakdown.
    plan_records = [r for r in records if r.ev == "mix.plan"]
    dump_records = [r for r in records if r.ev == "mix.dump"]
    decline_true_cnt = sum(1 for r in plan_records if r.raw.get("decline") is True)
    decline_to_mix_true_cnt = sum(
        1 for r in plan_records if r.raw.get("declineToMix") is True
    )
    filter_variant_decline_cnt = sum(
        1 for r in plan_records if r.raw.get("filterVariant") == "DECLINE"
    )
    filter_variants_on_decline = Counter(
        str(r.raw.get("filterVariant") or "<empty>")
        for r in plan_records
        if r.raw.get("decline") is True or r.raw.get("declineToMix") is True
    )
    templates_on_decline = Counter(
        str(r.raw.get("template") or "<empty>")
        for r in plan_records
        if r.raw.get("decline") is True or r.raw.get("declineToMix") is True
    )
    decline_dumps = [
        r for r in dump_records if "decline-to-mix" in str(r.raw.get("gates") or "")
    ]
    steps_breakdown = Counter()
    for r in decline_dumps:
        m = STEPS_RE.search(str(r.raw.get("gates") or ""))
        steps_breakdown[m.group(1) if m else "<absent>"] += 1

    decline_fired_episodes: List[Dict[str, Any]] = []
    decline_fired_modes = Counter()
    for ep in episodes:
        ep_has_decline = any(
            p.raw.get("decline") is True
            or p.raw.get("declineToMix") is True
            or p.raw.get("filterVariant") == "DECLINE"
            for p in ep.plans
        ) or any("decline-to-mix" in str(d.raw.get("gates") or "") for d in ep.dumps)
        if ep_has_decline and ep.fires:
            for f in ep.fires:
                mode = str(f.raw.get("mode") or "")
                decline_fired_modes[mode] += 1
            decline_fired_episodes.append(
                {
                    "ep_id": ep.episode_id,
                    "ts": ep.fires[0].ts,
                    "pair": ep.pair_str(),
                    "modes": [str(f.raw.get("mode") or "") for f in ep.fires],
                    "templates": [str(p.raw.get("template") or "") for p in ep.plans],
                    "filterVariants": sorted(
                        {str(p.raw.get("filterVariant") or "") for p in ep.plans if p.raw.get("filterVariant")}
                    ),
                    "gates": sorted({str(d.raw.get("gates") or "") for d in ep.dumps}),
                    "completes": [
                        f"{c.raw.get('reason')}(handoff={c.raw.get('handoff')})"
                        for c in ep.completes
                    ],
                }
            )

    # D4: Camelot key distribution across distinct track IDs and mix.plan occurrences.
    track_keys: Dict[str, Counter] = defaultdict(Counter)
    event_cam_left = Counter()
    event_cam_right = Counter()
    for ep in episodes:
        for p in ep.plans:
            if since_ts is not None and p.ts < since_ts:
                continue
            cl = str(p.raw.get("camelotLeft") or "").strip()
            cr = str(p.raw.get("camelotRight") or "").strip()
            if cl:
                event_cam_left[cl] += 1
            if cr:
                event_cam_right[cr] += 1
            if ep.pair:
                out_id, in_id = ep.pair
                if cl:
                    track_keys[out_id][cl] += 1
                if cr:
                    track_keys[in_id][cr] += 1

    track_key_primary: Dict[str, str] = {}
    track_key_conflicts: Dict[str, Dict[str, int]] = {}
    track_ab_counts = Counter()
    track_exact_counts = Counter()
    for tid, kc in sorted(track_keys.items()):
        primary, _ = kc.most_common(1)[0]
        track_key_primary[tid] = primary
        if len(kc) > 1:
            track_key_conflicts[tid] = dict(kc)
        track_exact_counts[primary] += 1
        if primary.endswith("A"):
            track_ab_counts["A (minor)"] += 1
        elif primary.endswith("B"):
            track_ab_counts["B (major)"] += 1
        else:
            track_ab_counts["other"] += 1

    # D5: Octave relationships and raw bpmA/bpmB near 0.5 or 2.0 (+/-15%).
    octave_gate_rows = [
        r
        for r in records
        if "octave-relationship" in str(r.raw.get("gates") or "").lower()
        or "octave" in str(r.raw.get("gates") or "").lower()
        or "octave" in str(r.raw.get("reason") or "").lower()
    ]
    near_octave_pairs: Dict[str, Dict[str, Any]] = {}
    for ep in episodes:
        for p in ep.plans:
            if since_ts is not None and p.ts < since_ts:
                continue
            ba = p.raw.get("bpmA")
            bb = p.raw.get("bpmB")
            if (
                isinstance(ba, (int, float))
                and isinstance(bb, (int, float))
                and ba > 0.0
                and bb > 0.0
            ):
                ratio = ba / bb
                off_half = abs(ratio - 0.5) / 0.5
                off_double = abs(ratio - 2.0) / 2.0
                min_off = min(off_half, off_double)
                if min_off <= 0.15:
                    target = 0.5 if off_half <= off_double else 2.0
                    k = f"{ep.pair_str()}|{ba:.2f}|{bb:.2f}"
                    if k not in near_octave_pairs:
                        near_octave_pairs[k] = {
                            "pair": ep.pair_str(),
                            "bpmA": ba,
                            "bpmB": bb,
                            "ratio": ratio,
                            "target": target,
                            "pct_off": min_off * 100.0,
                            "within_8pct": min_off <= 0.08,
                            "gates": sorted(
                                {str(d.raw.get("gates") or "") for d in ep.dumps}
                            ),
                            "fired": [str(f.raw.get("mode") or "") for f in ep.fires],
                        }

    # Loop stability: arm.death, loop.fallback, watchdog, verify ok:false, error.
    arm_death_reasons = Counter(
        (str(r.raw.get("reason") or ""), str(r.raw.get("phase") or ""))
        for r in records
        if r.ev == "mix.arm.death"
    )
    fallback_reasons = Counter(
        (str(r.raw.get("reason") or ""), str(r.raw.get("phase") or ""))
        for r in records
        if r.ev == "mix.loop.fallback"
    )
    watchdog_rows = [r for r in records if r.ev == "mix.watchdog"]
    verify_rows = [r for r in records if r.ev == "mix.verify"]
    verify_ok_cnt = sum(1 for r in verify_rows if r.raw.get("ok") is True)
    verify_fail_rows = [r for r in verify_rows if r.raw.get("ok") is not True]
    error_rows = [r for r in records if r.ev == "mix.error"]

    return {
        "title": title,
        "since_ts": since_ts,
        "record_count": len(records),
        "episode_count": len(episodes),
        "session_count": len(sessions),
        "min_ts": min_ts,
        "max_ts": max_ts,
        "span_min": span_min,
        "since_span_min": since_span_min,
        "ev_counts": ev_counts,
        "pair_stats": pair_stats,
        "gates_counter": gates_counter,
        "drifting_rows": drifting_rows,
        "decline": {
            "plan_decline_true": decline_true_cnt,
            "plan_declineToMix_true": decline_to_mix_true_cnt,
            "plan_filterVariant_DECLINE": filter_variant_decline_cnt,
            "dump_gates_decline_to_mix": len(decline_dumps),
            "filter_variants_on_decline": filter_variants_on_decline,
            "templates_on_decline": templates_on_decline,
            "steps_breakdown": steps_breakdown,
            "fired_modes": decline_fired_modes,
            "fired_episodes": decline_fired_episodes,
        },
        "camelot": {
            "distinct_tracks": len(track_key_primary),
            "track_ab_counts": track_ab_counts,
            "track_exact_counts": track_exact_counts,
            "track_key_primary": track_key_primary,
            "track_key_conflicts": track_key_conflicts,
            "event_cam_left": event_cam_left,
            "event_cam_right": event_cam_right,
        },
        "octave": {
            "gate_rows": octave_gate_rows,
            "near_octave_pairs": sorted(
                near_octave_pairs.values(), key=lambda x: x["pct_off"]
            ),
        },
        "stability": {
            "arm_death_reasons": arm_death_reasons,
            "fallback_reasons": fallback_reasons,
            "watchdog_rows": watchdog_rows,
            "verify_total": len(verify_rows),
            "verify_ok": verify_ok_cnt,
            "verify_fail_rows": verify_fail_rows,
            "error_rows": error_rows,
        },
        "episodes": episodes,
    }


def render_window_markdown(
    data: Dict[str, Any], include_episode_timeline: bool = False
) -> List[str]:
    lines: List[str] = []
    lines.append(f"## {data['title']}")
    lines.append("")
    lines.append("### Overview")
    lines.append("")
    lines.append("| Metric | Value |")
    lines.append("|---|---|")
    lines.append(f"| Records | {data['record_count']} |")
    lines.append(f"| Transition episodes | {data['episode_count']} |")
    lines.append(f"| App sessions (`seq` resets) | {data['session_count']} |")
    lines.append(
        f"| First timestamp | `{data['min_ts']}` ({fmt_ts(data['min_ts'])}) |"
    )
    lines.append(
        f"| Last timestamp | `{data['max_ts']}` ({fmt_ts(data['max_ts'])}) |"
    )
    lines.append(f"| Recorded span | {data['span_min']:.2f} min |")
    if data["since_span_min"] is not None:
        lines.append(
            f"| Elapsed since `--since-ts` (`{data['since_ts']}`) | {data['since_span_min']:.2f} min |"
        )
    lines.append("")

    lines.append("### Event Counts")
    lines.append("")
    lines.append("| Event (`ev`) | Count |")
    lines.append("|---|---|")
    for ev, cnt in data["ev_counts"].most_common():
        lines.append(f"| `{ev}` | {cnt} |")
    lines.append("")

    # Per-pair table.
    lines.append("### Per-Pair Correlation (`outId -> inId`)")
    lines.append("")
    lines.append(
        "| Pair (`outId -> inId`) | Eps | Plans | Dumps | Rej | Fb | ArmD | Fire(L/C) | Verify(OK/Fail) | Complete(H/F) | BPMs | Camelot | Styles / Templates | Gates |"
    )
    lines.append("|---|---:|---:|---:|---:|---:|---:|---|---|---|-----|---|---|---|---|")
    sorted_pairs = sorted(
        data["pair_stats"].values(),
        key=lambda ps: (
            -(ps["fires_loop"] + ps["fires_classic"]),
            -ps["episodes"],
            ps["pair"],
        ),
    )
    for ps in sorted_pairs:
        styles_str = ", ".join(
            f"{k}:{v}" for k, v in ps["styles"].most_common(4)
        ) or ", ".join(f"{k}:{v}" for k, v in ps["templates"].most_common(4))
        gates_str = "; ".join(f"{k}({v})" for k, v in ps["gates"].most_common(3))
        bpms_str = ", ".join(sorted(ps["bpms"]))[:40]
        cams_str = ", ".join(sorted(ps["camelots"]))
        lines.append(
            "| {pair} | {ep} | {pl} | {du} | {rj} | {fb} | {ad} | {fl}/{fc} | {vo}/{vf} | {ch}/{cf} | {bpms} | {cams} | {sty} | {gt} |".format(
                pair=sanitize_md(ps["pair"]),
                ep=ps["episodes"],
                pl=ps["plans"],
                du=ps["dumps"],
                rj=ps["rejects"],
                fb=ps["fallbacks"],
                ad=ps["arm_deaths"],
                fl=ps["fires_loop"],
                fc=ps["fires_classic"],
                vo=ps["verifies_ok"],
                vf=ps["verifies_fail"],
                ch=ps["completes_handoff"],
                cf=ps["completes_fail"],
                bpms=sanitize_md(bpms_str or "-"),
                cams=sanitize_md(cams_str or "-"),
                sty=sanitize_md(styles_str or "-"),
                gt=sanitize_md(gates_str or "-"),
            )
        )
    lines.append("")

    if include_episode_timeline:
        lines.append("### Chronological Episode Timeline")
        lines.append("")
        lines.append(
            "| Ep | Time (UTC) | Pair (`outId -> inId`) | Plans (templates) | Dump (style / reason / gates) | Rejects / Fallbacks / ArmDeaths | Fired | Verify | Complete |"
        )
        lines.append("|---:|---|---|---|---|---|---|---|---|")
        for ep in data["episodes"]:
            tpls = ", ".join(
                f"{p.raw.get('template')}{'(L)' if p.raw.get('locked') else ''}"
                for p in ep.plans
            )
            dumps_s = "; ".join(
                f"{d.raw.get('style')}/{d.raw.get('reason')} {d.raw.get('gates')}"
                for d in ep.dumps
            )
            issues = []
            if ep.rejects:
                issues.append(
                    "rej:" + ",".join(str(r.raw.get("reason")) for r in ep.rejects)
                )
            if ep.fallbacks:
                issues.append(
                    "fb:" + ",".join(str(f.raw.get("reason")) for f in ep.fallbacks)
                )
            if ep.arm_deaths:
                issues.append(
                    "death:" + ",".join(str(a.raw.get("reason")) for a in ep.arm_deaths)
                )
            fired_s = ",".join(str(f.raw.get("mode")) for f in ep.fires) or "-"
            ver_s = (
                ",".join(
                    f"ok={v.raw.get('ok')}({fmt_num(v.raw.get('phaseErrorMs'), 2)}ms)"
                    for v in ep.verifies
                )
                or "-"
            )
            comp_s = (
                ",".join(
                    f"{c.raw.get('reason')}(handoff={c.raw.get('handoff')})"
                    for c in ep.completes
                )
                or ("cancelled" if ep.cancelled else "-")
            )
            lines.append(
                "| {id} | {ts} | {pair} | {tpl} | {dmp} | {iss} | {fir} | {ver} | {cmp} |".format(
                    id=ep.episode_id,
                    ts=fmt_ts(ep.start_ts),
                    pair=sanitize_md(ep.pair_str()),
                    tpl=sanitize_md(tpls or "-"),
                    dmp=sanitize_md(dumps_s or "-"),
                    iss=sanitize_md("; ".join(issues) or "-"),
                    fir=sanitize_md(fired_s),
                    ver=sanitize_md(ver_s),
                    cmp=sanitize_md(comp_s),
                )
            )
        lines.append("")

    # D1
    lines.append("### D1 — Distinct `gates` Strings & `policy=drifting beat grid`")
    lines.append("")
    lines.append("| Distinct `gates` String | `mix.dump` Count |")
    lines.append("|---|---:|")
    for g, cnt in data["gates_counter"].most_common():
        lines.append(f"| `{sanitize_md(g or '<empty>')}` | {cnt} |")
    lines.append("")

    drifting = data["drifting_rows"]
    lines.append(f"Total `drifting beat grid` `mix.dump` rows: **{len(drifting)}**")
    lines.append("")
    if drifting:
        lines.append(
            "| Timestamp | Seq | Pair (`outId -> inId`) | `bpmA -> bpmB` | Quality (`A/B`) | Status (`A/B`) | Style | Reason | `gates` | Templates | What Fired / Outcome |"
        )
        lines.append("|---|---:|---|---|---|---|---|---|---|---|---|")
        for row in drifting:
            outcome_parts = []
            if row["fired"]:
                outcome_parts.append("fire=" + ",".join(row["fired"]))
            if row["fallbacks"]:
                outcome_parts.append("fb=" + ",".join(row["fallbacks"]))
            if row["arm_deaths"]:
                outcome_parts.append("death=" + ",".join(row["arm_deaths"]))
            if row["completes"]:
                outcome_parts.append("complete=" + ",".join(row["completes"]))
            if not outcome_parts:
                outcome_parts.append("cancelled" if row["cancelled"] else "unfired/superseded")
            lines.append(
                "| `{ts}` ({utc}) | {seq} | {pair} | {ba} -> {bb} | {qa}/{qb} | {sa}/{sb} | {sty} | {rsn} | `{gt}` | {tpl} | {out} |".format(
                    ts=row["ts"],
                    utc=fmt_ts(row["ts"]),
                    seq=row["seq"],
                    pair=sanitize_md(row["pair"]),
                    ba=fmt_num(row["bpmA"], 3),
                    bb=fmt_num(row["bpmB"], 3),
                    qa=row["qualityA"] or "-",
                    qb=row["qualityB"] or "-",
                    sa=row["statusA"] or "-",
                    sb=row["statusB"] or "-",
                    sty=row["style"] or "-",
                    rsn=row["reason"] or "-",
                    gt=sanitize_md(row["gates"]),
                    tpl=",".join(row["templates"]) or "-",
                    out=sanitize_md("; ".join(outcome_parts)),
                )
            )
        lines.append("")

    # D3
    dec = data["decline"]
    lines.append("### D3 — `decline` / `declineToMix` / `filterVariant=DECLINE`")
    lines.append("")
    lines.append("| Signal | Count |")
    lines.append("|---|---:|")
    lines.append(f"| `mix.plan` `decline == true` | {dec['plan_decline_true']} |")
    lines.append(f"| `mix.plan` `declineToMix == true` | {dec['plan_declineToMix_true']} |")
    lines.append(
        f"| `mix.plan` `filterVariant == \"DECLINE\"` | {dec['plan_filterVariant_DECLINE']} |"
    )
    lines.append(
        f"| `mix.dump` `gates` containing `decline-to-mix` | {dec['dump_gates_decline_to_mix']} |"
    )
    lines.append(
        f"| Episodes w/ decline signal that FIRED (`classic`) | {dec['fired_modes'].get('classic', 0)} |"
    )
    lines.append(
        f"| Episodes w/ decline signal that FIRED (`loop`) | {dec['fired_modes'].get('loop', 0)} |"
    )
    lines.append("")
    if dec["templates_on_decline"]:
        lines.append(
            "- `mix.plan.template` when `decline`/`declineToMix` true: "
            + ", ".join(f"`{k}`={v}" for k, v in dec["templates_on_decline"].most_common())
        )
    if dec["filter_variants_on_decline"]:
        lines.append(
            "- `mix.plan.filterVariant` when `decline`/`declineToMix` true: "
            + ", ".join(
                f"`{k}`={v}" for k, v in dec["filter_variants_on_decline"].most_common()
            )
        )
    if dec["steps_breakdown"]:
        lines.append(
            "- `steps=` token in `decline-to-mix` gates: "
            + ", ".join(f"`{k}`={v}" for k, v in dec["steps_breakdown"].most_common())
        )
    lines.append("")
    if dec["fired_episodes"]:
        lines.append("| Ep | Fire UTC | Pair | Fired Mode(s) | Planned Templates | `filterVariant` | Outcomes |")
        lines.append("|---:|---|---|---|---|---|---|")
        for fe in dec["fired_episodes"]:
            lines.append(
                "| {ep} | {ts} | {pair} | {modes} | {tpls} | {fv} | {cmp} |".format(
                    ep=fe["ep_id"],
                    ts=fmt_ts(fe["ts"]),
                    pair=sanitize_md(fe["pair"]),
                    modes=",".join(fe["modes"]),
                    tpls=",".join(fe["templates"]),
                    fv=",".join(fe["filterVariants"]) or "<empty>",
                    cmp=sanitize_md(",".join(fe["completes"]) or "-"),
                )
            )
        lines.append("")

    # D4
    cam = data["camelot"]
    lines.append("### D4 — Camelot Key Distribution (`*A` Minor vs `*B` Major)")
    lines.append("")
    lines.append(
        f"Distinct track IDs with non-empty `camelotLeft` / `camelotRight`: **{cam['distinct_tracks']}**"
    )
    lines.append("")
    lines.append("| Mode Group | Distinct Track Count |")
    lines.append("|---|---:|")
    for k, v in cam["track_ab_counts"].most_common():
        lines.append(f"| {k} | {v} |")
    lines.append("")
    if cam["track_exact_counts"]:
        lines.append(
            "- Distinct tracks per exact Camelot key: "
            + ", ".join(f"`{k}`={v}" for k, v in cam["track_exact_counts"].most_common())
        )
    if cam["event_cam_left"] or cam["event_cam_right"]:
        left_a = sum(v for k, v in cam["event_cam_left"].items() if k.endswith("A"))
        left_b = sum(v for k, v in cam["event_cam_left"].items() if k.endswith("B"))
        right_a = sum(v for k, v in cam["event_cam_right"].items() if k.endswith("A"))
        right_b = sum(v for k, v in cam["event_cam_right"].items() if k.endswith("B"))
        lines.append(
            f"- `mix.plan` occurrences: `camelotLeft` `*A`={left_a}, `*B`={left_b}; `camelotRight` `*A`={right_a}, `*B`={right_b}"
        )
    if cam["track_key_conflicts"]:
        lines.append(
            f"- Track IDs observed with >1 Camelot value: {cam['track_key_conflicts']}"
        )
    if cam["track_key_primary"]:
        lines.append("")
        lines.append("| Track ID | Observed Primary Camelot Key |")
        lines.append("|---|---|")
        for tid, key in sorted(cam["track_key_primary"].items()):
            lines.append(f"| `{tid}` | `{key}` |")
    lines.append("")

    # D5
    octv = data["octave"]
    lines.append("### D5 — Octave Relationship & Raw `bpmA/bpmB` within 15% of `0.5` or `2.0`")
    lines.append("")
    lines.append(
        f"- Events mentioning `octave-relationship` / `octave`: **{len(octv['gate_rows'])}**"
    )
    lines.append(
        f"- Distinct BPM pairs within 15% of `0.5` or `2.0`: **{len(octv['near_octave_pairs'])}**"
    )
    lines.append("")
    if octv["near_octave_pairs"]:
        lines.append(
            "| Pair | `bpmA` | `bpmB` | `bpmA/bpmB` | Nearest Target | Off (%) | Within 8% Detector? | Fired | Gates |"
        )
        lines.append("|---|---:|---:|---:|---:|---:|---|---|---|")
        for npair in octv["near_octave_pairs"]:
            lines.append(
                "| {pair} | {ba:.3f} | {bb:.3f} | {rat:.4f} | {tgt:.1f} | {off:.2f}% | {w8} | {fir} | {gt} |".format(
                    pair=sanitize_md(npair["pair"]),
                    ba=npair["bpmA"],
                    bb=npair["bpmB"],
                    rat=npair["ratio"],
                    tgt=npair["target"],
                    off=npair["pct_off"],
                    w8="YES" if npair["within_8pct"] else "no",
                    fir=",".join(npair["fired"]) or "-",
                    gt=sanitize_md("; ".join(npair["gates"]) or "-"),
                )
            )
        lines.append("")

    # Loop Stability
    stab = data["stability"]
    lines.append("### Loop Stability (`mix.arm.death`, `mix.loop.fallback`, `mix.watchdog`, `mix.verify`, `mix.error`)")
    lines.append("")
    lines.append(
        f"- `mix.verify`: total=**{stab['verify_total']}**, `ok:true`=**{stab['verify_ok']}**, `ok:false`=**{len(stab['verify_fail_rows'])}**"
    )
    lines.append(f"- `mix.watchdog`: **{len(stab['watchdog_rows'])}**")
    lines.append(f"- `mix.error`: **{len(stab['error_rows'])}**")
    lines.append("")
    if stab["arm_death_reasons"]:
        lines.append("| `mix.arm.death` Reason | Phase | Count |")
        lines.append("|---|---|---:|")
        for (rsn, ph), cnt in stab["arm_death_reasons"].most_common():
            lines.append(f"| `{sanitize_md(rsn)}` | `{ph}` | {cnt} |")
        lines.append("")
    if stab["fallback_reasons"]:
        lines.append("| `mix.loop.fallback` Reason | Phase | Count |")
        lines.append("|---|---|---:|")
        for (rsn, ph), cnt in stab["fallback_reasons"].most_common():
            lines.append(f"| `{sanitize_md(rsn)}` | `{ph}` | {cnt} |")
        lines.append("")
    if stab["watchdog_rows"]:
        lines.append("| `mix.watchdog` TS | Seq | Key | `pos` | `start` |")
        lines.append("|---|---:|---|---:|---:|")
        for r in stab["watchdog_rows"]:
            lines.append(
                f"| `{r.ts}` ({fmt_ts(r.ts)}) | {r.seq} | `{sanitize_md(str(r.raw.get('key')))}` | {r.raw.get('pos')} | {r.raw.get('start')} |"
            )
        lines.append("")
    if stab["verify_fail_rows"]:
        lines.append("| `mix.verify` `ok:false` TS | Seq | Pair | PhaseErr (ms) | ExitViol | Reasons |")
        lines.append("|---|---:|---|---:|---:|---|")
        for r in stab["verify_fail_rows"]:
            pair = pair_label(r.resolved_pair[0], r.resolved_pair[1]) if r.resolved_pair else "-"
            lines.append(
                f"| `{r.ts}` ({fmt_ts(r.ts)}) | {r.seq} | {sanitize_md(pair)} | {fmt_num(r.raw.get('phaseErrorMs'), 3)} | {r.raw.get('exitGateViolations')} | `{sanitize_md(str(r.raw.get('reasons')))}` |"
            )
        lines.append("")
    if stab["error_rows"]:
        lines.append("| `mix.error` TS | Seq | Phase | Error |")
        lines.append("|---|---:|---|---|")
        for r in stab["error_rows"]:
            lines.append(
                f"| `{r.ts}` ({fmt_ts(r.ts)}) | {r.seq} | `{r.raw.get('phase')}` | `{sanitize_md(str(r.raw.get('error')))}` |"
            )
        lines.append("")

    return lines


def build_report(
    input_path: Path,
    total_lines: int,
    bad_lines: int,
    records: List[TelemetryRecord],
    episodes: List[Episode],
    since_ts: Optional[int],
    pair_filter: Optional[str],
) -> Tuple[str, Dict[str, Any], Optional[Dict[str, Any]]]:
    all_records = filter_records_for_window(records, None, pair_filter)
    all_episodes = filter_episodes_for_window(episodes, None, pair_filter)
    all_data = analyze_window("All-Time Telemetry", all_records, all_episodes, None)

    post_data: Optional[Dict[str, Any]] = None
    if since_ts is not None:
        post_records = filter_records_for_window(records, since_ts, pair_filter)
        post_episodes = filter_episodes_for_window(episodes, since_ts, pair_filter)
        post_data = analyze_window(
            f"vc71 Post-Install Window (`ts >= {since_ts}`)",
            post_records,
            post_episodes,
            since_ts,
        )

    md: List[str] = [
        "# BitChord Automix Telemetry Correlation Report",
        "",
        f"- **Input:** `{input_path}`",
        f"- **Total non-empty JSONL lines:** {total_lines} (parse errors: {bad_lines})",
        f"- **Pair filter:** `{pair_filter}`" if pair_filter else "- **Pair filter:** none",
        "",
    ]

    if post_data is not None:
        md.extend(render_window_markdown(post_data, include_episode_timeline=True))
        md.append("---")
        md.append("")
    md.extend(render_window_markdown(all_data, include_episode_timeline=False))

    return "\n".join(md) + "\n", all_data, post_data


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Correlate and diagnose BitChord automix-telemetry.jsonl."
    )
    parser.add_argument(
        "--input",
        default=DEFAULT_INPUT,
        help="Path to automix-telemetry JSONL file.",
    )
    parser.add_argument(
        "--since-ts",
        type=int,
        default=None,
        help="Include a focused window for ts >= SINCE_TS alongside All-Time.",
    )
    parser.add_argument(
        "--pair",
        default=None,
        help="Filter to a pair ('outId->inId' or track ID substring).",
    )
    parser.add_argument(
        "--markdown-out",
        default=None,
        help="Optional output path to write the full Markdown report.",
    )
    args = parser.parse_args(argv)

    input_path = Path(args.input)
    if not input_path.is_file():
        print(f"ERROR: input file not found: {input_path}", file=sys.stderr)
        return 2

    records, total_lines, bad_lines = load_records(input_path)
    episodes = correlate_episodes(records)
    report_md, all_data, post_data = build_report(
        input_path=input_path,
        total_lines=total_lines,
        bad_lines=bad_lines,
        records=records,
        episodes=episodes,
        since_ts=args.since_ts,
        pair_filter=args.pair,
    )

    if args.markdown_out:
        out_path = Path(args.markdown_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(report_md, encoding="utf-8")

    # Print concise stdout summary.
    print(f"Input: {input_path} ({total_lines} lines, {bad_lines} bad)")
    for label, d in ([("ALL-TIME", all_data)] + ([("POST-SINCE", post_data)] if post_data else [])):
        dec = d["decline"]
        cam = d["camelot"]
        stab = d["stability"]
        print(
            f"[{label}] records={d['record_count']} episodes={d['episode_count']} "
            f"span={d['span_min']:.2f}m"
            + (f" (since={d['since_span_min']:.2f}m)" if d["since_span_min"] is not None else "")
            + f" | D1_drift={len(d['drifting_rows'])}"
            + f" | D3_decline(plan={dec['plan_decline_true']},variant={dec['plan_filterVariant_DECLINE']},fired_classic={dec['fired_modes'].get('classic',0)},fired_loop={dec['fired_modes'].get('loop',0)})"
            + f" | D4_tracks={cam['distinct_tracks']}({dict(cam['track_ab_counts'])})"
            + f" | D5_oct_gates={len(d['octave']['gate_rows'])},near15%={len(d['octave']['near_octave_pairs'])}"
            + f" | verify={stab['verify_ok']}/{stab['verify_total']} arm_death={sum(stab['arm_death_reasons'].values())} fallback={sum(stab['fallback_reasons'].values())}"
        )
    if args.markdown_out:
        print(f"Wrote Markdown report: {args.markdown_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
