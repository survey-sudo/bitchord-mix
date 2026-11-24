#!/usr/bin/env bash
# AE3 B6.5 soak test (2-hour scripted ADB session) and B15 battery / idle check.
#
# Drives a Festival queue on the phone through `cmd media_session`, injects a
# randomised stress action every 3-7 minutes, samples memory, collects telemetry
# and logcat crash/ANR buffers, and enforces the B6.5 pass criteria:
#   - 0 crashes or ANRs
#   - 0 mix.countdown.broken
#   - 100% of natural transitions produce an overlap
#   - memory growth < 50 MB (PSS and RSS, start to end)
#   - 0 stuck states (a mix still open > 10 s past its expected exit)
# With --battery-check it also enforces B15:
#   - battery drain <= 3 %/hour over the run (unplugged only)
#   - automix threads wake <= 1 per 5 s when idle
#
# Preconditions (the script never does these itself):
#   - The owner has loaded a ~40-track Festival queue and the phone is unlocked
#     by the owner. This script never unlocks, uninstalls, clears data or edits the queue.
#   - adb sees the device serial (default 1439ebea).
#
# Usage:
#   tools/soak/soak.sh [--serial SERIAL] [--duration-sec N] [--dry-run]
#                      [--battery-check] [--out-dir DIR] [--seed N]
#                      [--no-bluetooth] [--sample-sec N] [--idle-check-sec N]
#                      [--max-mix-sec N] [--automix-thread-regex RE] [--help]
#
# Exit status: 0 all gates pass (or dry run), 1 any gate fails, 2 usage or preflight error.

set -uo pipefail

PKG="com.music.bitchord.loopfix"
SERIAL="1439ebea"
DURATION_SEC=7200
DRY_RUN=0
BATTERY_CHECK=0
OUT_DIR=""
SEED=""
BLUETOOTH=1
SAMPLE_SEC=600
IDLE_CHECK_SEC=60
MAX_MIX_SEC=45
THREAD_RE="automix|djmix|prep|stem|analy"
TELEMETRY_DIR="/sdcard/Android/data/${PKG}/files"
TELEMETRY_FILES=("automix-telemetry-v3.jsonl" "automix-telemetry.jsonl")
MEM_GROWTH_LIMIT_KB=$((50 * 1024))
BATTERY_LIMIT_PCT_PER_HR="3"
STUCK_GRACE_SEC=10

usage() {
  sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'
}

die_usage() { echo "soak: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="${2:?--serial needs a value}"; shift 2 ;;
    --duration-sec) DURATION_SEC="${2:?--duration-sec needs a value}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --battery-check) BATTERY_CHECK=1; shift ;;
    --out-dir) OUT_DIR="${2:?--out-dir needs a value}"; shift 2 ;;
    --seed) SEED="${2:?--seed needs a value}"; shift 2 ;;
    --no-bluetooth) BLUETOOTH=0; shift ;;
    --sample-sec) SAMPLE_SEC="${2:?--sample-sec needs a value}"; shift 2 ;;
    --idle-check-sec) IDLE_CHECK_SEC="${2:?--idle-check-sec needs a value}"; shift 2 ;;
    --max-mix-sec) MAX_MIX_SEC="${2:?--max-mix-sec needs a value}"; shift 2 ;;
    --automix-thread-regex) THREAD_RE="${2:?--automix-thread-regex needs a value}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die_usage "unknown argument: $1 (see --help)" ;;
  esac
done

case "$DURATION_SEC" in ''|*[!0-9]*) die_usage "--duration-sec must be a positive integer" ;; esac
[ "$DURATION_SEC" -gt 0 ] || die_usage "--duration-sec must be > 0"
case "$SAMPLE_SEC" in ''|*[!0-9]*) die_usage "--sample-sec must be a positive integer" ;; esac
case "$IDLE_CHECK_SEC" in ''|*[!0-9]*) die_usage "--idle-check-sec must be a positive integer" ;; esac
case "$MAX_MIX_SEC" in ''|*[!0-9]*) die_usage "--max-mix-sec must be a positive integer" ;; esac

if [ -z "$SEED" ]; then SEED=$(( $(date +%s) % 100000 )); fi
RANDOM=$SEED

TS=$(date +%Y%m%d-%H%M%S)
if [ -z "$OUT_DIR" ]; then OUT_DIR="${TMPDIR:-/tmp}/bitchord-soak-${TS}"; fi
if [ "$DRY_RUN" -eq 0 ]; then
  mkdir -p "$OUT_DIR" || die_usage "cannot create $OUT_DIR"
fi
LOG="$OUT_DIR/actions.log"
SAMPLES="$OUT_DIR/samples.csv"

# Scale the action interval for short smoke runs so they still exercise actions.
# A 2-hour run uses the B6.5 window of 180-420 s.
MIN_INT=180; MAX_INT=420
if [ "$DURATION_SEC" -lt 1260 ]; then
  MIN_INT=$(( DURATION_SEC / 6 )); [ "$MIN_INT" -ge 5 ] || MIN_INT=5
  MAX_INT=$(( DURATION_SEC / 3 )); [ "$MAX_INT" -ge $(( MIN_INT + 1 )) ] || MAX_INT=$(( MIN_INT + 5 ))
fi

say() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*"; }

ADB_CMDS=()

# adb wrapper. Dry run prints the command and returns nothing.
adbq() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '[dry-run] adb -s %s %s\n' "$SERIAL" "$*"
    return 0
  fi
  adb -s "$SERIAL" "$@"
}

# True when the device reports external power. Deliberately not written as
# `adb ... | grep -q`: under `set -o pipefail` a `grep -q` that matches exits
# early, SIGPIPEs the producer, and turns the whole pipeline non-zero, so the
# charging guard would silently never fire.
device_charging() {
  local bat_state
  if [ "$DRY_RUN" -eq 1 ]; then return 1; fi
  bat_state=$(adb -s "$SERIAL" shell dumpsys battery 2>/dev/null | tr -d '\r' || true)
  grep -Eq '(AC|USB|Wireless) powered: true' <<<"$bat_state"
}

log_action() {
  local msg="$*"
  say "$msg"
  if [ "$DRY_RUN" -eq 0 ]; then printf '%s %s\n' "$(date +%s)" "$msg" >>"$LOG"; fi
}

sleep_s() {
  if [ "$DRY_RUN" -eq 1 ]; then return 0; fi
  sleep "$1"
}

now_s() { date +%s; }

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------

preflight() {
  if [ "$DRY_RUN" -eq 1 ]; then
    say "dry run: no device is touched; printing commands only (seed=$SEED)"
    return 0
  fi
  command -v adb >/dev/null 2>&1 || die_usage "adb not on PATH"
  local state
  state=$(adb -s "$SERIAL" get-state 2>/dev/null || true)
  [ "$state" = "device" ] || { echo "soak: device $SERIAL not in 'device' state (got '${state:-none}')" >&2; exit 2; }
  local pm_out
  pm_out=$(adb -s "$SERIAL" shell pm path "$PKG" 2>/dev/null | tr -d '\r' || true)
  case "$pm_out" in
    *package:*) ;;
    *)
      echo "soak: $PKG is not installed on $SERIAL; install with adb install -r first" >&2
      exit 2
      ;;
  esac
  local keyguard
  keyguard=$(adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -c 'mDreamingLockscreen=true\|isKeyguardShowing=true' || true)
  if [ "${keyguard:-0}" -gt 0 ]; then
    say "warning: lockscreen is showing; the script will not unlock. Owner should unlock before the soak."
  fi
  if [ "$BATTERY_CHECK" -eq 1 ] && device_charging; then
    echo "soak: --battery-check needs the phone unplugged (battery drain is meaningless while charging)" >&2
    exit 2
  fi
  mkdir -p "$OUT_DIR"
  adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' >"$OUT_DIR/device.txt" || true
  adb -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null \
    | grep -E 'versionCode|versionName' | head -2 >>"$OUT_DIR/device.txt" || true
}

# ---------------------------------------------------------------------------
# Sampling
# ---------------------------------------------------------------------------

# Prints "PSS_KB RSS_KB" for the app process group, or "n/a n/a".
mem_sample() {
  local out pss rss
  if [ "$DRY_RUN" -eq 1 ]; then echo "n/a n/a"; return; fi
  out=$(adb -s "$SERIAL" shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r')
  pss=$(printf '%s\n' "$out" | sed -n 's/.*TOTAL PSS: *\([0-9][0-9]*\).*/\1/p' | head -1)
  rss=$(printf '%s\n' "$out" | sed -n 's/.*TOTAL RSS: *\([0-9][0-9]*\).*/\1/p' | head -1)
  if [ -z "$pss" ]; then
    pss=$(printf '%s\n' "$out" | awk '$1=="TOTAL"{print $2; exit}')
  fi
  echo "${pss:-n/a} ${rss:-n/a}"
}

record_sample() {
  local label="$1" pss rss
  read -r pss rss <<<"$(mem_sample)"
  if [ "$DRY_RUN" -eq 1 ]; then
    say "[dry-run] sample $label (meminfo not read)"
    return
  fi
  printf '%s,%s,%s,%s\n' "$label" "$(now_s)" "$pss" "$rss" >>"$SAMPLES"
  say "mem $label pss_kb=$pss rss_kb=$rss"
}

battery_level() {
  if [ "$DRY_RUN" -eq 1 ]; then echo "n/a"; return; fi
  adb -s "$SERIAL" shell dumpsys battery 2>/dev/null | tr -d '\r' \
    | sed -n 's/^ *level: *\([0-9][0-9]*\).*/\1/p' | head -1
}

# Sum of scheduler timeslices across automix-looking threads of the app process.
# Prints "<sum of run timeslices> <thread count>" for automix-named threads.
# /proc/<pid>/task/<tid>/schedstat is "<on-cpu ns> <runqueue wait ns> <timeslices>",
# so field 3 counts how many times the scheduler ran the thread: the wakeup proxy.
automix_timeslices() {
  if [ "$DRY_RUN" -eq 1 ]; then echo "0 0"; return; fi
  local script
  script="pid=\$(pidof $PKG); [ -n \"\$pid\" ] || exit 0
for t in /proc/\$pid/task/*; do
  n=\$(cat \$t/comm 2>/dev/null)
  echo \"\$n \$(cut -d' ' -f3 \$t/schedstat 2>/dev/null)\"
done"
  adb -s "$SERIAL" shell "$script" 2>/dev/null | tr -d '\r' \
    | awk -v re="$THREAD_RE" 'NF>=2 && tolower($1) ~ re {s+=$2; c++} END{print s+0, c+0}'
}

# ---------------------------------------------------------------------------
# Stress actions (B6.5: skip, pause/resume during mix, Bluetooth, background, seek)
# ---------------------------------------------------------------------------

BT_DISABLED=0

restore_bluetooth() {
  if [ "$BT_DISABLED" -eq 1 ]; then
    adbq shell cmd bluetooth_manager enable >/dev/null 2>&1 || true
    BT_DISABLED=0
  fi
}

act_skip() {
  log_action "action skip"
  adbq shell cmd media_session dispatch next
}

act_pause_resume() {
  local hold=$(( 2 + RANDOM % 6 ))
  log_action "action pause/resume hold=${hold}s"
  adbq shell cmd media_session dispatch pause
  sleep_s "$hold"
  adbq shell cmd media_session dispatch play
}

act_bluetooth() {
  if [ "$BLUETOOTH" -eq 0 ]; then
    log_action "action bluetooth skipped (--no-bluetooth)"
    return
  fi
  log_action "action bluetooth toggle"
  adbq shell cmd bluetooth_manager disable
  BT_DISABLED=1
  sleep_s 5
  adbq shell cmd bluetooth_manager enable
  BT_DISABLED=0
  sleep_s 8
}

act_background() {
  local hold=$(( 4 + RANDOM % 8 ))
  log_action "action background/foreground hold=${hold}s"
  adbq shell input keyevent KEYCODE_HOME
  sleep_s "$hold"
  adbq shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
}

act_seek() {
  local n=$(( 2 + RANDOM % 4 ))
  log_action "action seek forward x${n} (media fast-forward keyevent)"
  local i
  for i in $(seq 1 "$n"); do
    adbq shell input keyevent KEYCODE_MEDIA_FAST_FORWARD
    sleep_s 1
  done
}

run_random_action() {
  case $(( RANDOM % 5 )) in
    0) act_skip ;;
    1) act_pause_resume ;;
    2) act_bluetooth ;;
    3) act_background ;;
    4) act_seek ;;
  esac
  log_action "action done"
}

# ---------------------------------------------------------------------------
# Battery and idle (B15)
# ---------------------------------------------------------------------------

idle_check() {
  log_action "idle check: pausing for ${IDLE_CHECK_SEC}s"
  adbq shell cmd media_session dispatch pause
  sleep_s 3
  local t1 n1 t2 n2 delta
  read -r t1 n1 <<<"$(automix_timeslices)"
  sleep_s "$IDLE_CHECK_SEC"
  read -r t2 n2 <<<"$(automix_timeslices)"
  if [ "$DRY_RUN" -eq 1 ]; then
    IDLE_RESULT="dry-run"
    return
  fi
  if [ "${n2:-0}" -eq 0 ]; then
    IDLE_RESULT="fail: no automix threads matched /$THREAD_RE/ (set --automix-thread-regex)"
    return
  fi
  # B15: no automix thread awake more than once per 5 s while idle.
  # Budget = (window / 5 s) wakeups per thread. Timeslice counts are the wakeup proxy.
  local delta=$(( t2 - t1 ))
  local allowed=$(( (IDLE_CHECK_SEC / 5) * n2 ))
  echo "idle timeslices_delta=$delta threads=$n2 window=${IDLE_CHECK_SEC}s allowed=$allowed" >>"$OUT_DIR/idle.txt"
  if [ "$delta" -le "$allowed" ]; then
    IDLE_RESULT="pass: $delta timeslices over ${IDLE_CHECK_SEC}s across $n2 threads (<= $allowed, 1 per 5 s per thread)"
  else
    IDLE_RESULT="fail: $delta timeslices over ${IDLE_CHECK_SEC}s across $n2 threads (> $allowed, more than 1 per 5 s per thread)"
  fi
  adbq shell cmd media_session dispatch play
}

# ---------------------------------------------------------------------------
# Collection and evaluation
# ---------------------------------------------------------------------------

# Pulls both telemetry files. The script never clears them, so the evaluator
# keeps only records at or after the run start.
pull_telemetry() {
  local f
  for f in "${TELEMETRY_FILES[@]}"; do
    adbq pull "$TELEMETRY_DIR/$f" "$OUT_DIR/$f" >/dev/null 2>&1 || rm -f "$OUT_DIR/$f"
  done
}

collect_logcat() {
  local since="$1" rc_run rc_crash
  adbq logcat -d -v threadtime -T "$since" >"$OUT_DIR/logcat-run.txt" 2>"$OUT_DIR/logcat-run.err"
  rc_run=$?
  adbq logcat -d -b crash -v threadtime -T "$since" >"$OUT_DIR/logcat-crash.txt" 2>"$OUT_DIR/logcat-crash.err"
  rc_crash=$?
  printf 'run=%s crash=%s\n' "$rc_run" "$rc_crash" >"$OUT_DIR/logcat-status.txt"
  if [ "$rc_run" -ne 0 ] || [ "$rc_crash" -ne 0 ]; then
    say "warning: logcat collection failed (run rc=$rc_run, crash rc=$rc_crash); the crash gate cannot pass on an unread buffer"
  fi
}

evaluate() {
  # Writes summary.json and summary.md; prints the gate table; returns 0 on all-pass.
  local start_pss="$1" start_rss="$2" end_pss="$3" end_rss="$4" elapsed="$5"
  local bat_start="$6" bat_end="$7" plugged="$8" idle_text="$9" start_epoch="${10}"
  python3 - "$OUT_DIR" "$PKG" "$DURATION_SEC" "$start_pss" "$start_rss" "$end_pss" "$end_rss" \
    "$elapsed" "$bat_start" "$bat_end" "$BATTERY_CHECK" "$idle_text" "$MEM_GROWTH_LIMIT_KB" \
    "$BATTERY_LIMIT_PCT_PER_HR" "$MAX_MIX_SEC" "$STUCK_GRACE_SEC" "$SEED" "$plugged" "$start_epoch" \
    "$(dirname "$0")/../analyze_telemetry_v3.py" <<'PY'
import importlib.util, json, os, re, sys

(out_dir, pkg, duration, sp, sr, ep, er, elapsed, bs, be, bc, idle_text,
 mem_limit, bat_limit, max_mix, grace, seed, plugged, start_epoch, analyzer) = sys.argv[1:]
duration, elapsed = int(duration), int(elapsed)
mem_limit, max_mix, grace = int(mem_limit), int(max_mix), int(grace)
bat_limit = float(bat_limit)
battery_check = bc == "1"
start_epoch = int(start_epoch)

def to_int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None

spec = importlib.util.spec_from_file_location("analyze_telemetry_v3", analyzer)
an = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = an  # dataclasses needs the module registered before exec
spec.loader.exec_module(an)

# Telemetry: prefer v3, fall back to the legacy file. Keep only records newer than the run start.
records = []
bad = 0
sources = []
for name in ("automix-telemetry-v3.jsonl", "automix-telemetry.jsonl"):
    path = os.path.join(out_dir, name)
    if os.path.isfile(path) and os.path.getsize(path) > 0:
        recs, b = an.load_jsonl(__import__("pathlib").Path(path))
        bad += b
        sources.append(name)
        records.extend(recs)
run_ms = start_epoch * 1000
records = [r for r in records if r.ts == 0 or r.ts >= run_ms - 5000]

natural_excluded = 0
# Injected stress actions (skip, pause, BT toggle, background, seek) disturb the
# transition that follows them. Mark records within 60 s after any action so the
# natural-transition gate measures only undisturbed transitions.
action_ms = []
log_path = os.path.join(out_dir, "actions.log")
if os.path.isfile(log_path):
    with open(log_path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            parts = line.strip().split(" ", 1)
            if len(parts) == 2 and parts[1].startswith("action ") and parts[1] != "action done":
                try:
                    action_ms.append(int(parts[0]) * 1000)
                except ValueError:
                    pass
for r in records:
    if any(0 <= r.ts - a <= 60000 for a in action_ms):
        r.stress = True
        natural_excluded += 1
summary = an.summarise(records, bad, ",".join(sources) or "none") if records else None

failures = []
checks = []

def check(name, passed, detail):
    checks.append({"check": name, "pass": bool(passed), "detail": detail})
    if not passed:
        failures.append(name)

# 1. crashes / ANRs
crash_hits = 0
anr_hits = 0
for fname in ("logcat-run.txt", "logcat-crash.txt"):
    p = os.path.join(out_dir, fname)
    if os.path.isfile(p):
        with open(p, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if "FATAL EXCEPTION" in line:
                    crash_hits += 1
                if f"ANR in {pkg}" in line:
                    anr_hits += 1
# An unreadable logcat buffer is not evidence of zero crashes: fail closed.
logcat_note = ""
status_path = os.path.join(out_dir, "logcat-status.txt")
if not os.path.isfile(status_path):
    logcat_note = "; logcat was never collected"
else:
    with open(status_path, encoding="utf-8", errors="replace") as fh:
        tokens = fh.read().split()
    bad = [t for t in tokens if "=" in t and not t.endswith("=0")]
    if bad:
        logcat_note = "; logcat not collected (" + ", ".join(sorted(bad)) + ")"
check("0 crashes (FATAL EXCEPTION)", crash_hits == 0 and not logcat_note,
      f"{crash_hits} hits" + logcat_note)
check("0 ANRs (ANR in " + pkg + ")", anr_hits == 0 and not logcat_note,
      f"{anr_hits} hits" + logcat_note)

# 2. countdown broken
broken = sum(1 for r in records if r.countdown_broken) if records else None
check("0 mix.countdown.broken", broken == 0 if broken is not None else False,
      "no telemetry" if broken is None else f"{broken} events")

# 3. natural transitions overlap
if summary and summary["natural"]["attempts"] > 0:
    ov = summary["natural"]["overlap_pct"]
    check("100% natural transitions with overlap", ov is not None and ov >= 100.0,
          f"{ov:.1f}% of {summary['natural']['attempts']} natural attempts; {len(action_ms)} actions, {natural_excluded} records excluded")
else:
    check("100% natural transitions with overlap", False, "no natural transitions in the run")

# 4. memory growth
spk, epk = to_int(sp), to_int(ep)
srk, erk = to_int(sr), to_int(er)
if spk is None or epk is None:
    check("memory growth < 50 MB (PSS)", False, "meminfo not sampled")
else:
    g = epk - spk
    check("memory growth < 50 MB (PSS)", g < mem_limit, f"{g/1024:.1f} MB ({spk} -> {epk} KB)")
if srk is None or erk is None:
    check("memory growth < 50 MB (RSS)", False, "meminfo RSS not sampled")
else:
    g = erk - srk
    check("memory growth < 50 MB (RSS)", g < mem_limit, f"{g/1024:.1f} MB ({srk} -> {erk} KB)")

# 5. stuck states: a fired mix must reach DONE/FAILED/SUSPENDED within expected exit + grace.
stuck = []
if records:
    by_plan = {}
    for r in records:
        if r.plan_id:
            by_plan.setdefault(r.plan_id, []).append(r)
    for pid, rs in by_plan.items():
        rs.sort(key=lambda r: r.ts)
        fire = next((r for r in rs if r.kind == "mix.fire"), None)
        if fire is None:
            continue
        end = next((r for r in rs if r.ts >= fire.ts and (
            r.kind in ("mix.done", "mix.handover") or r.state in ("DONE", "FAILED", "SUSPENDED"))), None)
        expected_ms = max_mix * 1000
        if end is None:
            stuck.append(f"{pid}: fired at {fire.ts} with no DONE")
        elif end.ts - fire.ts > expected_ms + grace * 1000:
            stuck.append(f"{pid}: {(end.ts - fire.ts)/1000:.1f}s after fire")
# Legacy watchdog events are direct evidence of a stuck state.
legacy_stuck = [r for r in (records or []) if r.schema == "legacy" and r.kind == "mix.watchdog"
                and "stuck" in (r.reason or "")]
check("0 stuck states (> 10 s past expected exit)", not stuck and not legacy_stuck,
      f"{len(stuck)} plans, {len(legacy_stuck)} legacy watchdog events" + (f": {stuck[:3]}" if stuck else ""))

# 6. battery (B15) and idle (B15)
if battery_check:
    bsi, bei = to_int(bs), to_int(be)
    if plugged == "1":
        check("battery <= 3%/h (unplugged)", False, "device was plugged in during the run")
    elif bsi is None or bei is None or elapsed <= 0:
        check("battery <= 3%/h", False, "battery level not sampled")
    else:
        hours = elapsed / 3600.0
        rate = (bsi - bei) / hours
        check("battery <= 3%/h", rate <= bat_limit,
              f"{bsi}% -> {bei}% over {elapsed/60:.1f} min = {rate:.2f} %/h (1% resolution)")
    ok = idle_text.startswith("pass")
    check("automix idle wakeups <= 1 per 5 s", ok, idle_text or "not run")
else:
    checks.append({"check": "battery and idle (B15)", "pass": None, "detail": "skipped (no --battery-check)"})

# Structured outputs.
result = {
    "passed": not failures,
    "failures": failures,
    "checks": checks,
    "duration_sec_requested": duration,
    "duration_sec_actual": elapsed,
    "seed": seed,
    "package": pkg,
    "telemetry_sources": sources,
    "telemetry_records": len(records),
    "summary": summary,
}
with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as fh:
    json.dump(result, fh, indent=2, default=str)
    fh.write("\n")

lines = ["# AE3 soak summary", "", f"- Result: **{'PASS' if not failures else 'FAIL'}**",
         f"- Duration: {elapsed} s (requested {duration} s), seed {seed}",
         f"- Telemetry sources: {', '.join(sources) or 'none'} ({len(records)} records in run)", "",
         "| Check | Result | Detail |", "|---|---|---|"]
for c in checks:
    status = "skipped" if c["pass"] is None else ("PASS" if c["pass"] else "FAIL")
    lines.append(f"| {c['check']} | {status} | {c['detail']} |")
with open(os.path.join(out_dir, "summary.md"), "w", encoding="utf-8") as fh:
    fh.write("\n".join(lines) + "\n")

for c in checks:
    status = "skip" if c["pass"] is None else ("PASS" if c["pass"] else "FAIL")
    print(f"{status:4}  {c['check']}: {c['detail']}")
print("RESULT:", "PASS" if not failures else "FAIL")
sys.exit(0 if not failures else 1)
PY
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

main() {
  preflight
  trap 'restore_bluetooth' EXIT
  trap 'say "interrupted"; exit 130' INT TERM

  local start_epoch device_time start_pss start_rss end_pss end_rss bat_start bat_end plugged
  start_epoch=$(now_s)
  device_time=""
  if [ "$DRY_RUN" -eq 0 ]; then
    # `adb shell` joins its arguments and hands the device a shell line, so the
    # date format must reach `date` as one quoted word; otherwise the device
    # errors out and logcat ends up filtered by host time.
    device_time=$(adb -s "$SERIAL" shell "date +'%m-%d %H:%M:%S.000'" 2>/dev/null | tr -d '\r')
    if [ -z "$device_time" ]; then
      device_time="$(date '+%m-%d %H:%M:%S').000"
      say "warning: could not read the device clock; filtering logcat by host time $device_time"
    fi
  fi
  if [ "$DRY_RUN" -eq 0 ]; then
    printf 'label,epoch_s,pss_kb,rss_kb\n' >"$SAMPLES"
    : >"$LOG"
  fi

  say "soak: serial=$SERIAL duration=${DURATION_SEC}s battery_check=$BATTERY_CHECK out=$OUT_DIR seed=$SEED interval=${MIN_INT}-${MAX_INT}s"
  adbq shell cmd media_session dispatch play
  log_action "queue started (Festival queue must already be loaded)"

  if [ "$DRY_RUN" -eq 0 ]; then
    adb -s "$SERIAL" shell dumpsys batterystats "$PKG" >"$OUT_DIR/batterystats-start.txt" 2>/dev/null || true
  fi
  bat_start=$(battery_level)
  plugged=0
  if [ "$DRY_RUN" -eq 0 ] && device_charging; then
    plugged=1
  fi
  record_sample start
  read -r start_pss start_rss <<<"$(mem_sample)"
  if [ "$DRY_RUN" -eq 1 ]; then start_pss=n/a; start_rss=n/a; fi

  local next_sample_at=$(( start_epoch + SAMPLE_SEC ))
  local end_at=$(( start_epoch + DURATION_SEC ))
  local virtual=0
  while :; do
    local now remaining wait
    if [ "$DRY_RUN" -eq 1 ]; then
      now=$(( start_epoch + virtual ))
    else
      now=$(now_s)
    fi
    remaining=$(( end_at - now ))
    [ "$remaining" -gt 0 ] || break

    wait=$(( MIN_INT + RANDOM % (MAX_INT - MIN_INT + 1) ))
    [ "$wait" -gt "$remaining" ] && wait=$remaining
    if [ "$DRY_RUN" -eq 1 ]; then
      virtual=$(( virtual + wait ))
      say "[dry-run] t+${virtual}s would wait ${wait}s then run a random action"
      run_random_action
      if [ "$virtual" -ge "$(( next_sample_at - start_epoch ))" ]; then
        say "[dry-run] t+${virtual}s would sample meminfo"
        next_sample_at=$(( next_sample_at + SAMPLE_SEC ))
      fi
      continue
    fi
    sleep "$wait"
    run_random_action
    if [ "$(now_s)" -ge "$next_sample_at" ]; then
      record_sample interval
      next_sample_at=$(( next_sample_at + SAMPLE_SEC ))
    fi
  done

  if [ "$DRY_RUN" -eq 1 ]; then
    say "[dry-run] would run the idle check (${IDLE_CHECK_SEC}s) and battery check: $BATTERY_CHECK"
    IDLE_RESULT="dry-run"
    say "[dry-run] would pull ${TELEMETRY_FILES[*]} from $TELEMETRY_DIR and collect logcat since device time"
    say "[dry-run] would run evaluation and write summary.json / summary.md to $OUT_DIR"
    say "dry run complete: no device state changed (exit 0)"
    return 0
  fi

  restore_bluetooth
  if [ "$BATTERY_CHECK" -eq 1 ]; then
    IDLE_RESULT=""
    idle_check
  else
    IDLE_RESULT="skipped"
  fi
  adbq shell cmd media_session dispatch pause
  sleep_s 2
  record_sample end
  read -r end_pss end_rss <<<"$(mem_sample)"
  bat_end=$(battery_level)
  adb -s "$SERIAL" shell dumpsys batterystats "$PKG" >"$OUT_DIR/batterystats-end.txt" 2>/dev/null || true
  local elapsed=$(( $(now_s) - start_epoch ))

  pull_telemetry
  collect_logcat "$device_time"

  evaluate "$start_pss" "$start_rss" "$end_pss" "$end_rss" "$elapsed" \
    "$bat_start" "$bat_end" "$plugged" "$IDLE_RESULT" "$start_epoch"
}

main "$@"
