#!/usr/bin/env bash
# Verifies every fork hook in upstream-owned files survived a merge.
# hooks.txt format:  <hook-id>|<file>|<min occurrences of "FORK-HOOK(<hook-id>)">
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
FAIL=0
while IFS='|' read -r id file min; do
  [[ -z "${id// }" || "$id" == \#* ]] && continue
  n=$(grep -c "FORK-HOOK($id)" "$file" 2>/dev/null || true)
  if [ "${n:-0}" -ge "$min" ]; then echo "ok   $id ($file: $n)"; else echo "LOST $id ($file: ${n:-0} < $min)"; FAIL=1; fi
done < tools/upstream-sync/hooks.txt
if grep -rqs "kushagrasinghx/BitChord/releases" app/src/main/java/com/music/bitchord/data 2>/dev/null; then
  echo "WARN updater may point at upstream releases (grep kushagrasinghx/BitChord/releases): check the fork's update checker"
fi
if git grep -nE '^(<<<<<<<|>>>>>>>)' -- . ':!*.md' >/dev/null 2>&1; then echo "LOST conflict markers present"; FAIL=1; fi
exit $FAIL
