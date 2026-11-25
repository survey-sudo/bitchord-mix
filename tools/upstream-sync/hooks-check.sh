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
# git grep omits untracked files and only checked two marker families. Scan the
# complete working tree's tracked and untracked text sources, while excluding
# generated/build output, binary assets, and Markdown (which may document the
# marker syntax itself). The manifest check above remains the 32-row upstream
# hook contract; this is an independent clean-tree check.
is_excluded() {
  local path="$1"
  case "/$path/" in
    */.git/*|*/build/*|*/.gradle/*|*/.cxx/*|*/node_modules/*|*/out/*) return 0 ;;
  esac
  case "$path" in
    *.md|*.png|*.jpg|*.jpeg|*.gif|*.webp|*.bmp|*.ico|*.pdf|*.zip|*.jar|*.aar|*.so|*.a|*.o|*.class|*.onnx|*.ort|*.mp3|*.wav|*.flac|*.mp4|*.mov|*.ttf|*.otf) return 0 ;;
  esac
  return 1
}

while IFS= read -r -d '' path; do
  [ -f "$path" ] || continue
  is_excluded "$path" && continue
  if ! grep -Iq . "$path" 2>/dev/null; then
    continue
  fi
  if grep -nE '^[[:space:]]*(<<<<<<<|=======|>>>>>>>)' "$path" >/dev/null 2>&1; then
    echo "LOST conflict markers present: $path"
    grep -nE '^[[:space:]]*(<<<<<<<|=======|>>>>>>>)' "$path" | sed 's/^/     /'
    FAIL=1
  fi
done < <(git ls-files --cached --others --exclude-standard -z)
exit $FAIL
