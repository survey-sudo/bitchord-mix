# AE3 Upstream Sync

This is the AE3 (Part B10) upstream-sync playbook, updated from
[UPSTREAM_SYNC_PLAYBOOK.md](UPSTREAM_SYNC_PLAYBOOK.md) (kept until its content is fully absorbed, then
archived under `docs/archive/`). Goal: keeping the fork current with upstream
`kushagrasinghx/BitChord` releases must stay cheap — a sync is minutes of machine time plus at most an
hour of agent/porting effort plus the soak test.

## Principles (unchanged)

1. **The fork owns the automix engine; upstream owns everything else.** The lists live in
   `tools/upstream-sync/`:
   - `fork-owned.txt` — git pathspecs of files the fork owns (all `djmix-*` sources —
     `djmix-core/`, `app/src/main/java/com/music/bitchord/playback/djmix/`, `.../playback/loopmix`,
     `.../playback/smart`, `shared/.../playback/smart`, `sharedUi/.../mix_inspector.xml`, `tools/train`,
     fork docs like `docs/DJMIX_CONTRACT.md`, and more). On every sync, **our version always wins** and
     upstream commits that touched these paths are written to `PORT_LIST.md` as rows to port by hand.
   - `hotspots.txt` — upstream-OWNED files that carry short, marked fork hooks; merged normally
     (upstream wins everywhere except the hook lines).
   - `fork-deleted.txt` — upstream files we keep deleted (their CI workflows etc.), re-deleted after
     every sync if upstream modified them.
2. **Merge upstream release tags** (`v1.10`, `v1.11` …), never branches. Never rebase or squash an
   upstream merge — the merge commit keeps the next merge base correct.
3. **Never force-push `master`.** Sync work runs on `sync/upstream-<tag>` and lands by PR.
4. **Keep the fork's version numbers, always increasing** — see [RELEASE.md](RELEASE.md)
   (`bumpDjmix`, ADR 0004).
5. **Phone rules:** install only with `adb install -r`; never uninstall or clear data.
6. **Keep sync and engine work in separate PRs.** A sync PR contains only the upstream merge, hook
   fixes and port-list work.

## Fork hooks: `FORK-HOOK(id)` and `hooks-check.sh`

- Every fork change inside an upstream-owned file is a single marked line or short marked block that
  calls into a fork-owned file, labelled `FORK-HOOK(<id>)`.
- `tools/upstream-sync/hooks.txt` declares each hook: `<hook-id>|<file>|<min occurrences of
  "FORK-HOOK(<hook-id>)">` (e.g. `service-wiring|app/src/main/java/com/music/bitchord/playback/PlaybackService.kt|1`,
  `version|build.gradle.kts|1`, `djmix-modules|settings.gradle.kts|1`, `mix-inspector|…|1`, …).
- `tools/upstream-sync/hooks-check.sh` verifies every declared hook is still present after a merge
  (grep-counts `FORK-HOOK(<id>)` per file), warns if the update checker may point at upstream releases
  (`kushagrasinghx/BitChord/releases`), and fails on leftover conflict markers. **CI runs it green
  before every merge** — this is an M10 exit gate.
- A hook loss after a sync is the signal that upstream refactored the hotspot; re-add the hook, don't
  inline fork code upstream.

## Current state

- Fork lineage: grafted onto upstream `v1.9.1` (`7f45c768…`) by `tools/upstream-sync/graft-once.sh`;
  `.upstream-base` = `v1.10`.
- `bitchordUpstreamBase = "1.10"` in `build.gradle.kts` (`extra["bitchordUpstreamBase"]`), maintained by
  `sync.sh`; release versionName follows **`<upstream>-djmix<N>`** (AE3 target: `1.10-djmix53`).
- Daily bot: `.github/workflows/upstream-sync.yml`, cron `0 1 * * *` (08:00 WIB daily), needs the
  `SYNC_TOKEN` secret (fine-grained PAT on this repo with Contents / Pull requests / Issues / Workflows
  read-write). It merges the latest upstream release, resolves hook conflicts, pushes a
  `sync/upstream-<tag>` branch + PR, and opens an issue with `CONFLICTS.txt` + `PORT_LIST.md` when
  something needs human/agent eyes.

## Running a sync

**Dry run (M10 gate: ≤ 5 conflicted hotspot files):**

```bash
bash tools/upstream-sync/sync.sh v1.11
```

`sync.sh <upstream-tag> [main-branch]`:

1. fetches upstream tags, reads the merge base from `.upstream-base`;
2. checks out `sync/upstream-<tag>` from the main branch;
3. keeps fork-owned paths (ours) while recording upstream commits inside them into `PORT_LIST.md`;
4. merges upstream; enables `git rerere` for repeated resolutions;
5. **exit 0** = merged clean; **exit 2** = conflicts left, listed in `CONFLICTS.txt` — resolve per
   §5 of `UPSTREAM_SYNC_PLAYBOOK.md` (resolve on the sync branch, preserving every
   `FORK-HOOK(id)` line), then `hooks-check.sh` must pass before the PR;
6. after merge, sync.sh updates `extra["bitchordUpstreamBase"]` so the version string stays
   `<upstream>-djmix<N>`;

Finish with: hook fixes → `hooks-check.sh` → port the `PORT_LIST.md` rows by hand (each is a
"read the upstream diff, re-implement the intent" task) → the soak test → a sync PR.

## Upgrading to a new upstream base (rare)

Re-run `sync.sh` against the newer tag after the current one is fully landed; bump
`bitchordUpstreamBase` via the merge result, not by hand, so `.upstream-base`, `build.gradle.kts` and
the versionName stay consistent.

## Related

- The module split into `:djmix-core` / fork-owned paths **was the footprint refactor** (B10.1): after
  AE3 ships, upstream conflicts should be near zero and every fork change in upstream files is a hook.
- M10 exit gate: `hooks-check` green in CI; dry-run sync ≤ 5 conflicts; docs (this set) present.
