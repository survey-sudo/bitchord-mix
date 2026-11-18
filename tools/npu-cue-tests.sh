#!/usr/bin/env bash
# CUE-only unit/JVM validation. Invoke through cue_job (or its SSH agent backend).
# No APK, ORT rebuild, signing, secrets or device access.
set -euo pipefail
TREE=/workspace/src/bitchord-mix/.slim/worktrees/automix-npu-swap
EV=${1:-/workspace/diagnostics/automix-npu-qnn-2026-11-18/vc84-cue-tests}
test "$(hostname)" = 2887726280f4 || { echo "CUE host guard failed" >&2; exit 64; }
test "$(pwd -P)" = "$TREE" || { echo "Run in the isolated CUE NPU worktree" >&2; exit 64; }
test ! -f keystore.properties || { echo "Signing properties must not be staged" >&2; exit 64; }
mkdir -p "$EV/results/shared" "$EV/results/app" "$EV/renders"
trap 'rc=$?; printf "%s\n" "$rc" > "$EV/exit"; date -u +%FT%TZ > "$EV/finished-at.txt"' EXIT

# All automix lanes share this lock. Never kill a different lane's daemon/job.
exec 200>/workspace/diagnostics/automix-cue-gradle.lock
flock -w 3600 200
date -u +%FT%TZ > "$EV/started-at.txt"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export AUTOMIX_OVERLAP_OUTPUT_DIR="$EV/renders" AUTOMIX_OVERLAP_LABEL=vc84-npu
{ hostname; "$JAVA_HOME/bin/java" -version; printf "JAVA_HOME=%s ANDROID_HOME=%s\n" "$JAVA_HOME" "$ANDROID_HOME"; } > "$EV/env.txt" 2>&1
git rev-parse HEAD > "$EV/base.txt"
git status --porcelain=v1 -uall > "$EV/status-before.txt"
git diff --binary HEAD > "$EV/source.patch"
{ git diff --name-only HEAD; git ls-files --others --exclude-standard; } | sort -u |
    while IFS= read -r file; do test ! -f "$file" || sha256sum "$file"; done > "$EV/source.sha256"

# Fresh XML only, and only this isolated lane's outputs are removed.
rm -rf shared/build/test-results app/build/test-results
CMD=(./gradlew :shared:jvmTest :app:testProdDebugUnitTest --max-workers=4 -Dorg.gradle.jvmargs=-Xmx5g)
{ printf '%q ' "${CMD[@]}"; printf '\n'; } > "$EV/command.txt"
cat "$EV/command.txt"
set +e
"${CMD[@]}" > "$EV/gradle.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$EV/gradle.exit"
for module in shared app; do
    if [ -d "$module/build/test-results" ]; then
        find "$module/build/test-results" -name 'TEST-*.xml' -exec cp -a {} "$EV/results/$module/" \;
    fi
done
python3 - "$EV" <<'PY'
import json, pathlib, sys, xml.etree.ElementTree as ET
ev = pathlib.Path(sys.argv[1])
totals = {}
failures = []
for module in ('shared', 'app'):
    xmls = sorted((ev / 'results' / module).glob('TEST-*.xml'))
    row = dict(suites=len(xmls), tests=0, failures=0, errors=0, skipped=0)
    for path in xmls:
        root = ET.parse(path).getroot()
        for key in ('tests', 'failures', 'errors', 'skipped'):
            row[key] += int(root.attrib.get(key, 0))
        for case in root.iter('testcase'):
            for tag in ('failure', 'error'):
                for error in case.findall(tag):
                    failures.append(f"{module}: {case.get('classname')}.{case.get('name')}: {error.get('message', '')}")
    totals[module] = row
totals['total'] = {key: sum(row[key] for row in totals.values())
                   for key in ('suites', 'tests', 'failures', 'errors', 'skipped')}
(ev / 'xml-totals.json').write_text(json.dumps(totals, indent=2) + '\n')
(ev / 'xml-failures.txt').write_text('\n'.join(failures) + ('\n' if failures else ''))
print(json.dumps(totals, indent=2))
PY
git status --porcelain=v1 -uall > "$EV/status-after.txt"
tail -n 35 "$EV/gradle.log"
exit "$status"
