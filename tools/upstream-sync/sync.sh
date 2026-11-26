#!/usr/bin/env bash
# Merge an upstream BitChord release into the fork.
#   bash tools/upstream-sync/sync.sh <upstream-tag e.g. v1.11> [main-branch]
# Exit 0 = merged and committed on branch sync/upstream-<tag> (no conflicts left)
# Exit 2 = conflicts left in the working tree; list in CONFLICTS.txt; resolve per UPSTREAM_SYNC_PLAYBOOK.md §5
# Always writes PORT_LIST.md: upstream commits inside fork-owned paths that must be PORTED by hand.
set -euo pipefail
TAG="${1:?upstream tag, e.g. v1.11}"
MAIN="${2:-ae3}"
cd "$(git rev-parse --show-toplevel)"
KIT=tools/upstream-sync
readlist() { grep -vE '^\s*(#|$)' "$1" || true; }

git remote get-url upstream >/dev/null 2>&1 || git remote add upstream https://github.com/kushagrasinghx/BitChord.git
git fetch upstream --tags --force --prune --no-recurse-submodules
git rev-parse -q --verify "refs/tags/$TAG^{commit}" >/dev/null || { echo "upstream tag $TAG not found"; exit 1; }
BASE=$(cat .upstream-base 2>/dev/null || true)
[ -n "$BASE" ] || { echo ".upstream-base missing: run $KIT/graft-once.sh first"; exit 1; }
git checkout -q "$MAIN"
git merge-base --is-ancestor "$BASE" HEAD || { echo "$BASE is not an ancestor of $MAIN: run $KIT/graft-once.sh"; exit 1; }
if git merge-base --is-ancestor "$TAG" HEAD; then echo "$TAG is already merged into $MAIN"; exit 0; fi
git config rerere.enabled true
git config rerere.autoupdate true

BR="sync/upstream-$TAG"
git checkout -q -B "$BR" "$MAIN"

# ---- port list: upstream work inside paths the fork owns (we keep ours, then port intent) ----
mapfile -t OWNED < <(readlist "$KIT/fork-owned.txt")
{
  echo "# Port list: upstream $BASE..$TAG inside fork-owned paths"
  echo
  echo "Each commit below changed a file the fork owns. The merge KEPT the fork's version. For every row,"
  echo "read the upstream diff and re-implement its intent in the fork code (or tick 'not applicable' with a reason)."
  echo
  git log --no-merges --reverse --format='- [ ] `%h` %s (%an, %ad)' --date=short "$BASE..$TAG" -- "${OWNED[@]}" || true
  echo
  echo '## Files'
  echo '```'
  git diff --stat=160 "$BASE" "$TAG" -- "${OWNED[@]}" | tail -n 60 || true
  echo '```'
  echo "Show one: git show <sha> -- <path>    All: git diff $BASE $TAG -- <path>"
} > PORT_LIST.md

echo "== upstream $BASE..$TAG touching fork HOOK files (merged normally; hooks must survive) =="
mapfile -t HOT < <(readlist "$KIT/hotspots.txt")
git diff --stat "$BASE" "$TAG" -- "${HOT[@]}" | tail -n 25 || true

# ---- the merge ----
set +e
git merge --no-ff --no-commit "$TAG"
set -e

# Fork-owned paths: always the fork's version (upstream intent goes to PORT_LIST.md)
for p in "${OWNED[@]}"; do
  git ls-tree -r --name-only HEAD -- "$p" >/dev/null 2>&1 || continue
  if [ -n "$(git ls-tree -r --name-only HEAD -- "$p")" ]; then
    git checkout -q HEAD -- "$p" 2>/dev/null || true
    git add -A -- "$p" 2>/dev/null || true
  fi
  # files upstream ADDED inside an owned dir are kept but listed for review
done
# Fork-deleted paths (upstream CI etc.): keep them deleted
while read -r p; do git rm -q -r --ignore-unmatch -- "$p" 2>/dev/null || true; done < <(readlist "$KIT/fork-deleted.txt")

# Version: keep the fork's own monotonic scheme; record the upstream base; bump versionCode + djmix number
python3 - "$TAG" <<'PY'
import re, subprocess, sys
tag = sys.argv[1].lstrip("v")
p = "build.gradle.kts"
t = open(p).read()
if "<<<<<<<" in t:
    print("build.gradle.kts has conflict markers; resolve version lines by hand (keep fork values)"); sys.exit(0)
head = subprocess.run(["git", "show", "HEAD:" + p], capture_output=True, text=True).stdout
code = int(re.search(r'extra\["bitchordVersionCode"\]\s*=\s*(\d+)', head).group(1))
name = re.search(r'extra\["bitchordVersionName"\]\s*=\s*"([^"]+)"', head).group(1)
m = re.search(r"djmix(\d+)", name)
new_name = name[:m.start(1)] + str(int(m.group(1)) + 1) + name[m.end(1):] if m else name
t = re.sub(r'extra\["bitchordVersionCode"\]\s*=\s*\d+', f'extra["bitchordVersionCode"] = {code + 1}', t)
t = re.sub(r'extra\["bitchordVersionName"\]\s*=\s*"[^"]+"', f'extra["bitchordVersionName"] = "{new_name}"', t)
if 'extra["bitchordUpstreamBase"]' in t:
    t = re.sub(r'extra\["bitchordUpstreamBase"\]\s*=\s*"[^"]*"', f'extra["bitchordUpstreamBase"] = "{tag}"', t)
else:
    t = t.replace(f'extra["bitchordVersionName"] = "{new_name}"',
                  f'extra["bitchordVersionName"] = "{new_name}"\n// FORK-HOOK(version): upstream BitChord release this build is synced to\nextra["bitchordUpstreamBase"] = "{tag}"', 1)
open(p, "w").write(t)
print(f"version: {code}->{code + 1}, {name}->{new_name}, upstream base {tag}")
PY
git add build.gradle.kts 2>/dev/null || true
echo "$TAG" > .upstream-base
git add .upstream-base

UNMERGED=$(git diff --name-only --diff-filter=U || true)
NEWFILES=$(git diff --cached --name-only --diff-filter=A "$MAIN" -- "${OWNED[@]}" 2>/dev/null || true)
if [ -n "$NEWFILES" ]; then { echo; echo "## Upstream ADDED files inside fork-owned paths (kept; review)"; echo "$NEWFILES" | sed 's/^/- /'; } >> PORT_LIST.md; fi

if [ -n "$UNMERGED" ]; then
  { echo "Upstream $TAG merge: conflicts to resolve (see UPSTREAM_SYNC_PLAYBOOK.md §5):"; echo "$UNMERGED" | sed 's/^/- /'; } > CONFLICTS.txt
  cat CONFLICTS.txt
  echo "After resolving: git add -A && git commit -m \"Merge upstream BitChord $TAG\" && bash $KIT/hooks-check.sh"
  exit 2
fi
git commit -q -m "Merge upstream BitChord $TAG" -m "Fork-owned paths kept; see PORT_LIST.md for upstream changes to port."
bash "$KIT/hooks-check.sh"
echo "Merged $TAG on $BR. Next: work through PORT_LIST.md, then build/test per the playbook §6."
