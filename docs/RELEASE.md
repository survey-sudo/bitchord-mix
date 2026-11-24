# AE3 Release Engineering

Release pipeline, versioning, signing, and rollback. Spec: Part B11, checklist C3, ADR 0004.

## Versioning

- **Fork build:** `versionName = "<upstream>-djmix<N>"`, `versionCode` monotonic. AE3 target:
  **`1.10-djmix53` / versionCode 95** (current tree is still `1.10-djmix52` / 94 — `bumpDjmix` moves
  both together).
- **Engine:** `AE major.minor.patch` — `3.0.0` (`djmix-core` `EngineVersion.kt`); policy in
  [ENGINE_CONTRACTS.md](ENGINE_CONTRACTS.md).
- **Upstream base:** `extra["bitchordUpstreamBase"] = "1.10"` in `build.gradle.kts`, maintained by
  `tools/upstream-sync/sync.sh` (see [SYNC.md](SYNC.md)).
- **`./gradlew bumpDjmix`** (`build.gradle.kts` task): increments the `djmixNN` suffix and
  `bitchordVersionCode` together, **and refuses to run when the working tree is dirty** (it edits
  `build.gradle.kts` and tells you to commit).

## Signing

Release signs with the fork key from CI secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD` in `.github/workflows/release.yml` → `keystore.properties`); absent secrets leave the
build unsigned rather than failing. `app/build.gradle.kts` enables **`enableV2Signing = true` and
`enableV3Signing = true`** for the release signing config (Android v2 + v3 signature schemes; v3 keeps
the key-rotation lineage so future keys still install over old builds without uninstalling).
Signing stays in CI secrets and the devbox keystore — never in git (Part B14).

## The release pipeline (`.github/workflows/release.yml`)

Triggered manually (workflow_dispatch with optional `tag`/`draft` inputs) or by pushing a tag
**`ae3-*`** (e.g. `ae3-1.10-djmix53`):

1. `tools/upstream-sync/hooks-check.sh` + `tools/check_audiothread_alloc.py`;
2. native probes with the ADR-0008 deterministic FP flags
   (`-fno-fast-math -ffp-contract=off -Wall -Wextra`): `--selftest`, `--reverb-stop-probe`,
   `--loop-domain-probe`, `--lp-bypass-probe`, `--v2-probe`, phrase-isolator test, startup probe —
   and the same probes under **ASan/UBSan**;
3. `:djmix-core:jvmTest`, `:app:assembleProdRelease`, `tools/audit-apk.sh` (identity, ORT/model assets,
   signature schemes, 16 KiB alignment, expected versionName `1.10-djmix53`);
4. release notes from conventional commits + `CHANGELOG.md`;
5. GitHub Release with assets: the APK, **`models.json`** and the quality HTML
   (`docs/quality/AE3-<build>.html` when present in the tree).

Heavy work stays on the devbox (`make ae3-quality`): real-corpus `mixq`, audition, perf, soak — results
linked from the release notes. PRs always land via `hooks-check`; the M10 gate is: hooks green in CI,
dry-run sync ≤ 5 conflicts, pipeline produced the APK, all B12 docs present.

## Release checklist (Part C3 — copy of record)

1. Ledger: every `docs/AE3_LEDGER.md` item done or explicitly deferred with an ADR.
2. CI green: core tests, native gates, synthetic renders, APK audit.
3. Devbox quality: `mixq` corpus report, perf report, soak report.
4. Audition page uploaded and rated by the owner; gate met (≥ 3.5 average on Festival and Club, no
   "drop" votes on shipped-enabled recipes).
5. Version: `1.10-djmix53` / 95; engine `3.0.0`; `bitchordUpstreamBase = "1.10"`.
6. Sign, then **`adb install -r`**. Confirm with `dumpsys package` that versionCode is 95 and settings
   are preserved (DJ level, stems, NPU, learning).
7. Results: `docs/DJMIX53_RESULTS.md` (Part A §10 table, before and after), plus a 2-minute screen
   recording of Festival transitions with internal audio.
8. Release notes in `CHANGELOG.md`; GitHub release with the APK, `models.json` and the quality HTML.
9. Report to the owner in the Part A §10 format, plus links to the audition, recording and quality report.

## Installing on the phone

- **Always `adb install -r`** (reinstall keeping data). **Never** `adb uninstall`, never `pm clear`,
  never wipe the queue. versionCode only goes up.
- `adb install -r` **cannot downgrade** (Android rejects a lower versionCode); that failure mode
  protects the owner's data — see rollback below.

## Rollback: Safe mode + kill switches, never uninstall

Android won't install a lower versionCode with `install -r`, and we never uninstall. Rollback therefore
means **fix forward over a degraded-but-working config**:

- **`DjmixFlags`** (`app/src/main/java/com/music/bitchord/playback/djmix/diagnostics/DjmixFlags.kt`)
  is the single flag surface (`FlagsSnapshot`), set by: code defaults, an optional `flags.json` in the
  models release (fetched only on Wi-Fi, SHA-verified), and Developer-settings overrides.
- **Safe mode:** `FlagsSnapshot.safeMode = true` restricts recipes to straight blends only
  (`SAFE_MODE_RECIPE_IDS = {S1, S5}`) and forces `TransitionGeometry.STRAIGHT`.
- **Kill switches** (every v2 kill switch lives here): `"stems"`, `"analysis"`, `"recipe_lab"`,
  `"npu_qnn"` (`isKilled(name)`; e.g. `isNpuKilled()` forces `ep=CPU`).
- Developer settings (tap the version in About 7 times) can also force a level or recipe and export
  diagnostics; Safe mode is visible only in Developer settings.

Rollback playbook: enable Safe mode + the implicated kill switch → verify with a soak run → find and
fix the bug → release a new, higher versionCode. The last 3 signed APKs are kept in releases for
reference (do **not** attempt to sideload an older one over a newer install; Android will refuse).

## Flags for the autopilot agent

- Heavy devbox jobs (soak, real corpus, audition) never run inside CI.
- Nothing is reported "done" without evidence in `docs/AE3_LEDGER.md` (`docs/DJMIX53_RESULTS.md` is
  mandatory for the release, per A0.2).
- All phone interactions stay within C4: install taps, NPU add-on install, share-sheet sends.
