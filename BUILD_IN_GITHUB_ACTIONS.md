# Building the APK in GitHub Actions

This repository is the unified BitChord source: the full git history and branches from the build host (`dvb:/home/boxes/bitchord-mix`), plus the supporting `archive/` material. No source changes are needed.

## Branches

| Branch | Meaning |
| --- | --- |
| `vc91-djmix49` | **Shipped build.** Commit `23adab5`, versionCode 91 / `2.2.3-djmix49` — the source that produced the installed APK. This is the checked-out base branch. |
| `vc92-djmix50` | **Active vc92 branch.** Holds the unified `vc92` work, descended cleanly from `vc91-djmix49` (no merge, no rewritten history). This is where vc92 changes land. |
| `vc92-wip-snapshot` | Preserved snapshot of the vc92 working tree from `dvb` (16 modified files + 6 new files), kept for reference only as the pre-unification state. Superseded by `vc92-djmix50`; do not build from it. |
| `master`, `vc80-*`, `automix/*`, … | Older history, kept for reference only. Do **not** use `vc80-filter-catchup` for release builds. |

## 1. Push to GitHub

```bash
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin vc91-djmix49
```

The default branch is `vc91-djmix49`. `.github/workflows/android-ci.yml` fires on pushes and PRs on **any** branch, so CI runs on whatever branch an agent works from. The older `master` branch is kept only for history — do not build from it.

Do not reuse the old `.git` from the vc80 archive — this repository already contains the correct history and branches.

## 2. Trim the workflows

Keep only `.github/workflows/android-ci.yml`. Delete the upstream leftovers:

```
android.yml  check.yml  close-issue.yml  contributors-image.yml
deploy-backend.yml  tv.yml  update-contributors.yml  web.yml
```

They target `main`, JDK 17 and secrets this fork doesn't use, and will only produce failing checks. They are not needed for the phone APK.

## 3. Run the build

Push, or run the workflow manually: Actions → **Android CI** → Run workflow.

It sets up JDK 21, compileSdk 36, build tools 36.0.0, NDK 27.2.12479018 and CMake 3.22.1, then runs:

```bash
./gradlew :app:assembleProdRelease -Pbitchord.bundleWear=false --max-workers=2 --no-daemon --stacktrace
```

The APK is uploaded as artifact **`bitchord-prod-arm64`** (30-day retention) from `app/build/outputs/apk/prod/release/*.apk`. Without signing secrets it is `app-release-unsigned.apk`; with them it is a signed release APK. The job also runs the native analyzer selftests and audits the APK (size, sha256, aapt2 badging, library counts).

No `local.properties` is needed — the runner provides `ANDROID_HOME`, and the build succeeds without API keys (LastFM / Listen Together keys only affect those features at runtime).

## 4. Optional: signed, installable APK

An unsigned APK cannot be installed over an existing install. To sign, add four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | base64 of the `.jks`: `base64 -w0 bitchord-loopfix.jks` |
| `KEYSTORE_PASSWORD` | `storePassword` |
| `KEY_ALIAS` | `keyAlias` |
| `KEY_PASSWORD` | `keyPassword` |

The values come from the original `~/Bitchord/secrets/BitChord-keystore.zip`, which is deliberately **not** in this repository. Extract it locally; it contains `BitChord-keystore/bitchord-loopfix.jks` plus `keystore.properties` and `keystore_info.txt` with the exact password/alias values. Then re-run the workflow — the signing step writes `keystore.properties` itself and the build signs automatically, with no workflow edit.

Never commit the keystore, `keystore.properties`, `local.properties`, or any passwords — all are gitignored.
