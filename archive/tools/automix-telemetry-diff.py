#!/usr/bin/env python3
"""Compare BitChord automix telemetry between two builds.

Usage:
  python3 tools/automix-telemetry-diff.py BASELINE.jsonl CANDIDATE.jsonl

Prints a side-by-side table of the metrics that vc91 is supposed to move, plus
the raw distribution of reasons/refusals so regressions are visible.

Telemetry is the JSONL the app writes to
  /sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl
Pull it with:
  adb -s <serial> pull <path> ./automix-telemetry.jsonl
"""
import json
import sys
from collections import Counter


def load(path):
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


def counts(events, field, predicate=None):
    counter = Counter()
    for event in events:
        if predicate is not None and not predicate(event):
            continue
        value = event.get(field)
        if value is None or value == "":
            continue
        counter[str(value)] += 1
    return counter


def share(counter, key):
    total = sum(counter.values())
    if not total:
        return 0.0
    return 100.0 * counter.get(key, 0) / total


def pct(value):
    return f"{value:5.1f}%"


def plan_age_stats(events):
    all_ages = [
        e["planAgeMs"]
        for e in events
        if isinstance(e.get("planAgeMs"), (int, float))
    ]
    if not all_ages:
        return "n/a"
    sentinel = sum(1 for a in all_ages if a < 0)
    ages = [a for a in all_ages if a >= 0]
    if not ages:
        return f"n=0 (sentinel={sentinel})"
    insane = sum(1 for a in ages if a > 3_600_000)
    return f"n={len(ages)} (sentinel={sentinel}) max={max(ages)} >1h={insane}"


def is_queue_advance(event):
    """Plain track-end advance, not a transition.

    `executionScope == "normal-queue-advance-no-overlap"` rows carry
    `technique:"natural"` / `transitionStyle:"NATURAL"` but never overlap two
    decks; counting them as NATURAL transitions inflated the vc90 headline to
    49-78% while `mix.fire` shows 0/88 real NATURAL mixes.
    """
    return (
        event.get("executionScope") == "normal-queue-advance-no-overlap"
        or event.get("technique") == "natural"
    )


def summary(name, events):
    dump = [e for e in events if e.get("ev") == "mix.dump"]
    styles_dump = counts(events, "style", lambda e: e.get("ev") == "mix.dump")
    plans_acc = [e for e in events if e.get("ev") == "mix.plan" and e.get("stage") == "accepted"]
    plans_trans = [e for e in plans_acc if not is_queue_advance(e)]
    queue_advances = sum(1 for e in plans_acc if is_queue_advance(e))
    styles_acc = counts(plans_trans, "transitionStyle")
    # Final verdict per attemptId (deduped across accepted/prepared revisions;
    # provisional startup NATURAL entries overwritten by reactive replans drop
    # out, and queue advances are excluded because they are not transitions).
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
    styles_final = Counter()
    for e in final_plans.values():
        key = e.get("transitionStyle") or e.get("technique") or "unknown"
        styles_final[str(key)] += 1
    rejected = [e for e in events if e.get("ev") == "mix.plan.rejected"]
    refusals = counts(rejected, "refusalCode")
    reasons = counts(rejected, "reason")
    complete = [e for e in events if e.get("ev") == "mix.complete"]
    complete_term = [e for e in complete if e.get("stage") == "terminal"]
    complete_attempts = {e.get("attemptId") or f"seq:{e.get('seq')}": e for e in complete}
    dup_ratio = (len(complete) / max(1, len(complete_attempts))) if complete else 1.0
    clean_complete = sum(
        1 for e in complete_term
        if e.get("outcome") == "completed" and e.get("reason") != "handoff_pcm_before_protected_event"
    )
    fired = [e for e in events if e.get("ev") == "mix.fire"]
    fire_techniques = counts(fired, "technique")
    fire_styles = counts(fired, "transitionStyle")
    plan_techniques = counts(events, "technique", lambda e: e.get("ev") == "mix.plan")

    natural_dump = share(styles_dump, "NATURAL")
    natural_acc = share(styles_acc, "NATURAL") if sum(styles_acc.values()) else 0.0
    natural_final = share(styles_final, "NATURAL") if sum(styles_final.values()) else 0.0
    fire_natural = sum(1 for e in fired if e.get("transitionStyle") == "NATURAL" or e.get("technique") == "natural")
    # Attempts whose final plan was the natural handoff but whose terminal row
    # is a refusal: chosen-then-aborted, invisible in the fire-only headline.
    terminal_by_attempt = {
        e.get("attemptId"): e for e in complete_term if e.get("attemptId")
    }
    chosen_then_aborted = 0
    for att, plan in final_plans.items():
        if str(plan.get("rung") or "") != "Natural handoff":
            continue
        term = terminal_by_attempt.get(att)
        if term is not None and term.get("outcome") != "completed":
            chosen_then_aborted += 1
    pcm_events = [
        e for e in events
        if e.get("refusalCode") == "handoff_pcm_before_protected_event"
        or e.get("reason") == "handoff_pcm_before_protected_event"
    ]
    pcm_abort = len(pcm_events)
    pcm_attempts = len({e.get("attemptId") for e in pcm_events if e.get("attemptId")})
    pcm_terminal = sum(1 for e in complete_term if e.get("reason") == "handoff_pcm_before_protected_event")
    heard = [e.get("actualHeardMs", -1) for e in complete]
    heard_ok = sum(1 for h in heard if isinstance(h, (int, float)) and h > 0)
    dump_n = len(dump)
    fire_rate = (100.0 * len(fired) / dump_n) if dump_n else 0.0
    heard_rate = (100.0 * heard_ok / dump_n) if dump_n else 0.0
    plan_age_over_1h = sum(
        1
        for e in events
        if isinstance(e.get("planAgeMs"), (int, float)) and e["planAgeMs"] > 3_600_000
    )

    print(f"\n=== {name} ===")
    print(f"events                : {len(events)}")
    print(f"mix.dump (context)    : {dump_n}  styles={dict(styles_dump)}")
    print(f"queue advances        : {queue_advances} (technique=natural, no overlap; not transitions)")
    print(f"accepted transitions  : {len(plans_trans)}  NATURAL={pct(natural_acc)}  {dict(styles_acc)}")
    print(f"final verdict/attempt : {len(final_plans)}  NATURAL={pct(natural_final)}  {dict(styles_final)}")
    print(f"mix.plan.rejected     : {len(rejected)}")
    print(f"mix.fire (HEADLINE)   : {len(fired)} ({fire_rate:.1f}/100 dumps)  NATURAL={fire_natural}/{len(fired)}  "
          f"tech={dict(fire_techniques)} style={dict(fire_styles)}")
    print(f"chosen natural aborts : {chosen_then_aborted} (final rung=Natural handoff, terminal!=completed)")
    print(f"mix.plan techniques   : {dict(plan_techniques)}")
    print(f"mix.complete          : {len(complete)} rows ({len(complete_attempts)} attempts, {dup_ratio:.2f}x dup)  "
          f"terminal={len(complete_term)} clean={clean_complete} heard>0(loop-only)={heard_ok} ({heard_rate:.1f}/100 dumps)")
    print(f"handoff_pcm aborts    : {pcm_attempts} attempts / {pcm_abort} events (terminal={pcm_terminal})")
    print(f"planAgeMs             : {plan_age_stats(events)}")
    print("refusalCode top       :")
    for code, n in refusals.most_common(8):
        print(f"    {n:5d}  {code}")
    print("rejected reason top   :")
    for reason, n in reasons.most_common(8):
        print(f"    {n:5d}  {reason}")
    return {
        "natural": natural_dump,
        "natural_acc": natural_acc,
        "natural_final": natural_final,
        "fire_natural": fire_natural,
        "chosen_then_aborted": chosen_then_aborted,
        "queue_advances": queue_advances,
        "pcm_abort": pcm_abort,
        "pcm_attempts": pcm_attempts,
        "pcm_terminal": pcm_terminal,
        "rejected": len(rejected),
        "fired": len(fired),
        "fire_rate": fire_rate,
        "clean_complete": clean_complete,
        "heard_ok": heard_ok,
        "heard_rate": heard_rate,
        "dump": dump_n,
        "plan_age_over_1h": plan_age_over_1h,
    }


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    base = summary("BASELINE", load(sys.argv[1]))
    cand = summary("CANDIDATE", load(sys.argv[2]))

    print("\n=== DELTA (candidate - baseline) ===")
    print(f"mix.fire NATURAL      : {base['fire_natural']}/{base['fired']} -> {cand['fire_natural']}/{cand['fired']}  (HEADLINE)")
    print(f"NATURAL final/attempt : {base['natural_final']:.1f}% -> {cand['natural_final']:.1f}%  "
          f"(transitions only; {base['queue_advances']} -> {cand['queue_advances']} queue advances excluded)")
    print(f"chosen natural aborts : {base['chosen_then_aborted']} -> {cand['chosen_then_aborted']}")
    print(f"handoff_pcm (att/ev)  : {base['pcm_attempts']}/{base['pcm_abort']} -> {cand['pcm_attempts']}/{cand['pcm_abort']}")
    print(f"mix.fire (/100 dumps) : {base['fired']} ({base['fire_rate']:.1f}) -> "
          f"{cand['fired']} ({cand['fire_rate']:.1f})")
    print(f"clean complete        : {base['clean_complete']} -> {cand['clean_complete']}  "
          f"(loop heard>0: {base['heard_ok']} -> {cand['heard_ok']})")
    print(f"planAgeMs > 1h        : {base['plan_age_over_1h']} -> {cand['plan_age_over_1h']}")

    targets = []
    if cand["dump"] < 4:
        targets.append(f"insufficient candidate sample (mix.dump={cand['dump']} < 4)")
    fire_natural_share = (100.0 * cand["fire_natural"] / cand["fired"]) if cand["fired"] else 0.0
    if fire_natural_share > 15.0:
        targets.append(
            f"fired NATURAL share {fire_natural_share:.1f}% above the 15% target "
            f"({cand['fire_natural']}/{cand['fired']})"
        )
    if cand["chosen_then_aborted"] > 0:
        targets.append(f"{cand['chosen_then_aborted']} natural handoffs chosen then aborted")
    if cand["pcm_attempts"] > 0 or cand["pcm_terminal"] > 0:
        targets.append("handoff_pcm_before_protected_event still firing")
    if cand["fire_rate"] <= base["fire_rate"]:
        targets.append("fire rate per 100 dumps did not improve over baseline")
    if cand["plan_age_over_1h"] > 0:
        targets.append("planAgeMs > 1h outlier still present")
    if targets:
        print("\nNOT MET:")
        for item in targets:
            print(f"  - {item}")
        return 1
    print("\nAll vc91 targets met.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
