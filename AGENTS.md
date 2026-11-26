# BitChord Mix agent entrypoint

- Read `REPOSITORY.md` first. The only active repository is
  `https://github.com/survey-sudo/bitchord-mix.git`; fork fetch/push remote is
  `origin`, default development branch is `ae3`.
- `bitchord-unified` is the former repository name. Do not create a competing
  repository or route pushes to the archived `bitchord-mix-legacy` predecessor.
- Preserve dirty files, worktrees, historical evidence, signing material and
  device data. Never use a forced push, destructive reset, uninstall or data
  clear as part of repository synchronization.
- Android/Gradle/NDK builds run on the cloud project `bitchord-mix`, target
  `bitchord-mix`, checkout `/home/boxes/bitchord-mix`; never build locally.
  Repository synchronization does not authorize a build or installation.
- User model preference: **never delegate to Gemini 4 Hydrogen**. Use the free
  `opencode/hy4-flash-free` model with its highest available variant (`high`
  as verified November 26, 2026), unless the user specifies another model.
  Look up supported IDs/variants before changing models; do not silently fall
  back to Hydrogen.
