# BitChord fork: upstream sync playbook (for the build agent)

Purpose: keep `survey-sudo/bitchord-unified` current with upstream `kushagrasinghx/BitChord` releases while the fork's
automix engine keeps evolving. Run the phases below top to bottom the first time. After that, only §8 repeats for each
upstream release.

## 0. Principles (do not break)

1. **The fork owns the automix engine; upstream owns everything else.** The lists are in `tools/upstream-sync/`:
   - `fork-owned.txt`: our version always wins, and upstream changes become `PORT_LIST.md` rows that are ported by hand.
   - `hotspots.txt`: upstream files that carry short `FORK-HOOK(id)` lines.
   - `fork-deleted.txt`: upstream files we keep deleted.
2. **Merge upstream release tags** (`v1.10`, `v1.11` …), never upstream branches. Never rebase or squash an upstream
   merge, because the merge commit is what keeps the next merge base correct.
3. **Never force-push `master`.** Sync work happens on `sync/upstream-<tag>` and lands by PR.
4. **Keep the fork's own version numbers, always increasing.** The current scheme is versionCode 93+ and versionName
   `2.x.y-djmixN`. `sync.sh` bumps both and records the upstream base in `extra["bitchordUpstreamBase"]`. Do not
   rename the fork to upstream's numbers: the in-app updater treats `-suffix` names as older than a clean release.
5. **Phone rules from `MASTER_PLAN.md` still apply:** install only with `adb install -r`, never uninstall or clear
   data, and make no Apple-parity claims without the user's listening sign-off.
6. **Keep sync and engine work in separate PRs.** A sync PR contains only the upstream merge, the hook fixes and the
   port-list items. New automix features go in their own PRs.

## 1. Prerequisites
1. **vc93 (Automix v2) is accepted by the user** and its branch `automix-v2` is the fork's main line.
   ```bash
   cd ~/bitchord-unified && git fetch --all --prune
   git merge-base --is-ancestor origin/master automix-v2 && git checkout master && git merge --ff-only automix-v2 && git push origin master
   ```
   If the `--is-ancestor` check fails, **stop and ask the user** which branch should become `master`. Do not
   force-push. Also make sure `android-ci.yml` triggers on `master` pushes and on PRs.
2. **The user adds the repository secret `SYNC_TOKEN`:** GitHub → Settings → Developer settings → Fine-grained token,
   scoped to `survey-sudo/bitchord-unified` only, with Contents, Pull requests, Issues and Workflows set to Read and
   write. Then paste it into repo → Settings → Secrets and variables → Actions → New secret `SYNC_TOKEN`.

## 2. Install the kit
```bash
cd ~/bitchord-unified && git checkout -b fork/sync-kit master
cp -r ~/av2/upstream-sync-kit/tools/upstream-sync tools/
cp ~/av2/upstream-sync-kit/.github/workflows/upstream-sync.yml .github/workflows/
cp ~/av2/upstream-sync-kit/docs/AUTOMIX_ENGINE_REFACTOR.md docs/
cp ~/av2/upstream-sync-kit/UPSTREAM_SYNC_PLAYBOOK.md docs/UPSTREAM_SYNC_PLAYBOOK.md
chmod +x tools/upstream-sync/*.sh
git add -A && git commit -m "fork: upstream sync kit (scripts, workflow, playbook)"
```
**DONE when:** the commit exists. Do not push yet.

## 3. Give git the shared starting point (once)
```bash
bash tools/upstream-sync/graft-once.sh fork/sync-kit
```
- If `v1.9.1` is already an ancestor, the script says so and only writes `.upstream-base`.
- Otherwise it records upstream `v1.9.1` (`7f45c76`) as a parent with an `-s ours` merge, so the tree is unchanged
  (the script verifies this).

Check the printed sanity table. Most fork differences should be under `app/src/main/java/.../playback`,
`shared/src`, `native` and `docs`. **Stop and tell the user** if `sharedUi/` or `app/src/main/java/.../ui/` show
hundreds of changed files: that would mean the fork did not start from exactly v1.9.1.

**DONE when:** `git merge-base --is-ancestor 7f45c768a461fd7e68bf4613f71e8a767e643d4c HEAD` exits 0, and
`.upstream-base` reads `v1.9.1`.

## 4. Shrink the fork footprint (once; no behaviour change)
Follow `docs/AUTOMIX_ENGINE_REFACTOR.md` §3.1–3.6, making one commit per extraction. Then:
```bash
bash tools/upstream-sync/hooks-check.sh
git diff --stat v1.9.1 HEAD -- $(grep -vE '^\s*(#|$)' tools/upstream-sync/hotspots.txt)
```
Then build and test exactly as in `MASTER_PLAN.md` §5–§6: native gates, `:shared:jvmTest`,
`:app:testProdDebugUnitTest`, `assembleProdRelease`. Bump versionCode by 1 (`-djmix52`), sign, `adb install -r`, and
check that 3 transitions behave the same as on vc93.

**DONE when** §4 of the refactor spec is met and the user confirms it sounds the same. Then push and open the PR
`fork/sync-kit → master` ("No-behaviour-change refactor + sync kit") and merge it after CI is green.

## 5. First real sync: upstream v1.10
```bash
git checkout master && git pull --ff-only
bash tools/upstream-sync/sync.sh v1.10 master ; echo "exit=$?"
```
- **exit 0:** clean. Go to "Port list" below.
- **exit 2:** resolve every file in `CONFLICTS.txt` using these rules, then:
  ```bash
  git add -A && git commit -m "Merge upstream BitChord v1.10" && bash tools/upstream-sync/hooks-check.sh
  ```

Conflict rules by file class:

| File class | Resolution |
|---|---|
| Fork-owned | Should never conflict, because the script takes ours. If one does, run `git checkout HEAD -- <path>`. |
| Hotspot (upstream-owned with hooks) | Start from upstream: `git checkout --theirs -- <file>`. Re-insert each `FORK-HOOK(id)` line at the equivalent place in upstream's new code. If upstream moved or renamed the surrounding code, put the hook where the same event now happens. `hooks-check.sh` must pass. |
| `build.gradle.kts` (root) | Take upstream. Then restore the fork's three `extra[...]` version lines (versionCode, versionName, bitchordUpstreamBase) with the values `sync.sh` printed. |
| `app/build.gradle.kts` | Take upstream. Keep only the `FORK-HOOK(gradle-djmix)` line or block. Fork-specific build config lives in `app/djmix.gradle.kts`. If upstream renamed an AGP DSL property that `djmix.gradle.kts` sets, update `djmix.gradle.kts`. |
| Strings / translations | Take upstream. Fork strings live in `strings_djmix.xml` and don't conflict. |
| Any other upstream-owned file | Take upstream (`git checkout --theirs -- <file>`). If that breaks compilation in fork-owned code, fix the fork side. |

Then build. Compile errors in fork-owned code caused by upstream API changes are fixed on the **fork** side, adapting
to upstream's new shape (see the contract in refactor §2). Regenerate `docs/DJMIX_CONTRACT.md`.

### Port list
Work through `PORT_LIST.md`. Every row must end up either ported or marked "N/A" with a reason. For v1.10 these rows
are already known:

| Upstream change | What to port into the fork engine |
|---|---|
| `438994c` feat(automix): publish AutoMix visual state and interruption cancellation | The fork `CrossfadeController` must publish the **same** visual-state object/flow upstream's controller now publishes (read the type and emit points with `git show 438994c`). Drive it from our real mix progress: loop-mix renderer `heard()` and classic fade progress. It must also honour the same interruption cancellation: seek, skip and pause during a mix. Otherwise upstream's new artwork-reveal UI shows nothing during our mixes. |
| `dd102c2` fix(player): smooth AutoMix artwork, Spotify Canvas handling, opt-in toggles | Controller parts only (the UI parts merged by themselves): advance the reveal from the smoothed audio clock rather than the controller tick; hold the reveal until the incoming artwork is prepared when a seek arms the mix late; draw the player glass flat while a mix is armed or running. |
| "prefetch next track artwork for AutoMix transitions" | Call upstream's artwork prefetch when the fork planner commits a plan, at the `primeLoopDecks` / plan-ready point. |
| PR #1554 merge (find with `git log v1.9.1..v1.10 --merges -- app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt`) | Inspect it and port any non-UI behaviour. |
| `1db2b0c` reduce apk size (TrackAnalyzer + native) | Adopt upstream's mechanism: the Automix model downloads the first time Automix is turned on. Route **all** fork models through the same downloader with SHA-256 pins (`beat_this_final0_q8.onnx`, `vocals_umxhq_int8.onnx`, `scnet.int8.onnx`, `beat_this_final0_qdq.onnx`). Host them on a fork release (`survey-sudo/bitchord-unified`, tag `models-v1`) and remove them from `app/src/main/assets`. `StemModelStore` already supports `url` + `sha256`. Expect the APK to drop by about 35–45 MB. |
| shared/sharedUi "keep the AutoMix dissolve when skipping onto the mix" | UI side, merged normally. Verify it works with the visual state the fork now publishes. |

**Known 1.10 runtime caveat (not a merge problem):** the new **Google Drive backup** signs in with an OAuth client tied
to upstream's package and signing key. On the fork (`com.music.bitchord.loopfix`, fork key) it will fail until the user
creates their own Google Cloud OAuth client (Android type, package `com.music.bitchord.loopfix`, SHA-1 of the fork
keystore) and the fork's client ID is wired in. Report it, and don't block the sync on it.

## 6. Verify the sync build
1. Run the gates in `MASTER_PLAN.md` §5–§6 (native, JVM tests, release build, APK audit), then
   `bash tools/upstream-sync/hooks-check.sh`.
2. Check that the updater doesn't point at upstream: `git grep -n "kushagrasinghx/BitChord/releases" -- app/src/main` must
   not be wired into the fork's update check. The fork must not prompt users to "update" to upstream.
3. Sign and `adb install -r`. versionCode must be higher than the installed one (check with `dumpsys package`).
4. Phone smoke test:
   - upstream 1.10 headline features: Replay, Radio tab, Best Part button, artwork transitions, queue swipe/undo;
   - playback, lyrics, downloads;
   - Settings → Automix: the fork rows are present and values are preserved.
5. Automix:
   - 6 or more natural transitions at each DJ level;
   - `adb logcat -s BitChordChoreo` shows move names, and `mix.fire` has `"choreo"`;
   - the artwork reveal now animates during our mixes;
   - first-enable model download works on a fresh state (test on a second device or emulator, **never** by clearing
     the user's phone).
6. Open the PR `sync/upstream-v1.10 → master` with the `PR_BODY.md` checklist plus `PORT_LIST.md`. The user listens
   and approves, then merge.

## 7. Turn on the automation
Push `.github/workflows/upstream-sync.yml` to `master` (it came in with §4's PR). Then go to Actions →
"Upstream sync" → Run workflow with an empty tag. It should report `skip=1`, because `.upstream-base` is already
`v1.10`. From then on it checks daily at 08:00 WIB.

## 8. Every new upstream release (the routine)
- **The bot opens a PR** (clean merge): the agent works the port list, then runs §6 (verify) and gets user sign-off.
- **The bot opens an issue "Upstream vX needs a manual merge"**: on the devbox, run
  `bash tools/upstream-sync/sync.sh vX master`, resolve per §5, push `sync/upstream-vX`, open the PR, then §6.
  Conflicted syncs run on the devbox because git's `rerere` memory of past resolutions lives there.
- Each sync PR bumps versionCode and the djmix number, and records the new `bitchordUpstreamBase`.
- Add newly found fork hooks to `hooks.txt`, and new fork-owned paths to `fork-owned.txt`, in the same PR.

## 9. Upstreaming (optional, shrinks the fork for good)
Offer upstream (GPL-3.0 both sides) small, generic fixes discovered during syncs that touch upstream-owned code. For
example:
- a crash fix;
- the loudness and true-peak limiter (if he wants it);
- the Beat This! static-QDQ NPU path, once it has proven itself on devices.

Anything he merges disappears from our hook and port lists. Engine-level features stay in the fork unless he asks
for them.

## 10. Report back after each sync (format)
1. Upstream tag, fork branch and commit SHA, versionCode/versionName, APK SHA-256 and size.
2. Conflicts and how each was resolved (one line each).
3. Port-list rows: ported or N/A, with reasons.
4. Gates and test totals, plus the `hooks-check` output.
5. Smoke and automix results, with the user's listening notes verbatim.
