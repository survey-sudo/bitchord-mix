# One BitChord Mix repository

Owner decision, November 26, 2026: **BitChord Mix** is the project name and
`survey-sudo/bitchord-mix` is the only active GitHub repository.

| Item | Canonical value |
| --- | --- |
| Git URL | `https://github.com/survey-sudo/bitchord-mix.git` |
| Default development branch | `ae3` |
| Local reference/sync mirror | `/home/hfzhkn/Bitchord/source/bitchord-mix` |
| Local convenience path | `/home/hfzhkn/bitchord-mix` (same checkout) |
| Cloud project / target | `bitchord-mix` / `bitchord-mix` |
| Primary Android development checkout | `/home/boxes/bitchord-mix` |
| Fork remote for fetch and push | `origin` |
| Original upstream | `upstream`, `kushagrasinghx/BitChord` |

Owner cloud-only decision, November 26, 2026: all Android source edits,
dependency installation, builds, tests and signing happen inside DVB. The
PC keeps reference source/docs, evidence and ADB; do not recreate local
Android toolchains, build caches or development worktrees.

The newer GitHub repository was renamed from `bitchord-unified` to
`bitchord-mix`. Its branches, tags and `models-v1` release assets stay together.
The old private repository was renamed to `bitchord-mix-legacy` and archived;
it remains private. It is historical storage, not a development or release
destination. No private-only history was copied into the public repository.

Existing local `bitchord-unified` paths are compatibility symlinks, not a
second source tree. Historical evidence can retain the names used when it
was recorded. New clones, releases, model URLs, handovers and build snapshots
must use `survey-sudo/bitchord-mix`. Do not recreate the former repository
name or restore a separate salvage remote.

Before starting or handing off work, verify `git remote get-url origin`,
`git branch --show-current`, `git rev-parse HEAD`, and `git status --short`.
Use an explicit feature branch for new implementation work and integrate
through `ae3`; do not reset or overwrite another worker's changes.

This consolidation changes repository identity and URLs. It does not establish
that the installed APK's analysis, transitions or performance gates pass.
