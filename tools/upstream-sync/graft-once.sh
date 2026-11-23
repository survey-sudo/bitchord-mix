#!/usr/bin/env bash
# One-time: make git know that this fork started from upstream BitChord v1.9.1, WITHOUT changing a byte.
#   bash tools/upstream-sync/graft-once.sh [main-branch]
# After this, `git merge v1.10` uses v1.9.1 as the merge base, so only upstream's 1.9.1→1.10 changes
# are replayed onto the fork instead of a 270-commit unrelated-history mess.
set -euo pipefail
MAIN="${1:-master}"
UP_BASE_TAG="v1.9.1"
UP_BASE_SHA="7f45c768a461fd7e68bf4613f71e8a767e643d4c"
cd "$(git rev-parse --show-toplevel)"
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream https://github.com/kushagrasinghx/BitChord.git
git fetch upstream --tags --force --no-recurse-submodules
[ "$(git rev-parse "$UP_BASE_TAG^{commit}")" = "$UP_BASE_SHA" ] || { echo "upstream $UP_BASE_TAG is not $UP_BASE_SHA; stop and tell the user"; exit 1; }
git checkout -q "$MAIN"
if git merge-base --is-ancestor "$UP_BASE_SHA" HEAD; then
  echo "$UP_BASE_TAG is already an ancestor of $MAIN; nothing to graft."
else
  echo "== sanity: files that differ between upstream $UP_BASE_TAG and $MAIN, by top-level dir =="
  git diff --name-only "$UP_BASE_SHA" HEAD | cut -d/ -f1-2 | sort | uniq -c | sort -rn | head -25
  echo "(expect the bulk under app/src, shared/src, native, docs, tools; UI dirs should be small)"
  TREE_BEFORE=$(git rev-parse HEAD^{tree})
  git merge --allow-unrelated-histories -s ours --no-edit \
    -m "fork: record upstream BitChord $UP_BASE_TAG ($UP_BASE_SHA) as merge base (tree unchanged)" "$UP_BASE_SHA"
  [ "$(git rev-parse HEAD^{tree})" = "$TREE_BEFORE" ] || { echo "tree changed, aborting"; git reset --hard HEAD~1; exit 1; }
fi
if [ "$(cat .upstream-base 2>/dev/null || true)" != "$UP_BASE_TAG" ]; then
  echo "$UP_BASE_TAG" > .upstream-base
  git add .upstream-base
  git commit -q -m "fork: track last synced upstream release in .upstream-base ($UP_BASE_TAG)"
fi
git config rerere.enabled true
git config rerere.autoupdate true
echo "Done. Push with: git push origin $MAIN"
