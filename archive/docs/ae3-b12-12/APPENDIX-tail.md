# B12.12 archival appendix — 2026-11-25

The four files below were moved byte-for-byte from their prior active paths.
`tools/upstream-sync/hooks.txt` was checked before moving the root build guide;
it contains no `BUILD_IN_GITHUB_ACTIONS.md` requirement. The existing release
and architecture guides are present at `docs/RELEASE.md` and
`docs/ARCHITECTURE.md`. README has no inbound links to these files, so no
README link replacements were needed. Their former paths in
`tools/upstream-sync/fork-owned.txt` now point at these archive locations.
This appendix records the later move and supersedes the earlier "Reviewed and
deliberately retained" rows in `archive/docs/AE3_B12_12_ARCHIVAL.md` for the
three moved historical documents. `docs/UPSTREAM_SYNC_PLAYBOOK.md` still names
the archived refactor guide, and `docs/AE3_BASELINE.md` still links to the
archived djmix52 baseline. Those inbound references were not changed because
the explicit write allow-list does not include either document.

| Former path | Archived path | SHA-256 | Rationale |
|---|---|---|---|
| `docs/AUTOMIX_ENGINE_REFACTOR.md` | `archive/docs/ae3-b12-12/AUTOMIX_ENGINE_REFACTOR.md` | `708afed8688a004e0404cf3e7a7469de707340e627041bcd3b136fb4e6d7c58b` | Historical upstream-hook extraction plan whose instructions describe a completed refactor and stale file ownership boundaries. |
| `docs/LOOP_CHARACTER_RECOVERY_2026-11-19.md` | `archive/docs/ae3-b12-12/LOOP_CHARACTER_RECOVERY_2026-11-19.md` | `d75541d9af78d54bfaa4159064af3ba1418d9de8518c3243df794c2a0d9328bb` | Dated recovery contract tied to the superseded isolated branch and historical source baseline; retained as evidence. |
| `docs/DJMIX52_BASELINE.md` | `archive/docs/ae3-b12-12/DJMIX52_BASELINE.md` | `b0621d309f778d8d146d6117d3158e905d3f130375cc4f6df93e43e101704d14` | Device measurements for the prior vc93 / djmix51 baseline; useful historical comparison, not the current release baseline. |
| `BUILD_IN_GITHUB_ACTIONS.md` | `archive/docs/ae3-b12-12/BUILD_IN_GITHUB_ACTIONS.md` | `02e6b025db42e2ef00df54ea70b40a6957fa5cea7c1217ad6c850ffe6cacd04a` | Build guide names vc91/djmix49 as checked-out base and directs pushes to that obsolete branch; current active release and architecture guides replace it. |

The digests above match the pre-move SHA-256 measurements. No content was
rewritten during archival.
