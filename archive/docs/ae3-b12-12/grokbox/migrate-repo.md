# Migrating the BitChord tree to a new build box

The repo has **no `origin` that predates this migration** — it now has
`https://github.com/survey-sudo/bitchord-mix` (private), but the working tree on
`bk-android` still carries state that is not on GitHub, so treat the tree as the
source of truth until the first parity check passes.

Measured on `bk-android`, 2026-11-15:

| Path | Size | Migrate? |
|---|---|---|
| `/data/workspace/bitchord-task2/work/BitChord/.git` | 55 M | **yes** — history + index |
| `.../BitChord` working tree, source only | ~110 M | **yes** |
| `.../BitChord/app/build` | 939 M | **no** — rebuildable |
| `.../BitChord/.gradle` | 39 M | **no** — rebuildable |
| `.../BitChord/npu-addon/prebuilt` | 131 M | **no** — not a build dependency (comments only in `app/build.gradle.kts`); ignored by `npu-addon/.gitignore` |
| `/data/workspace/bitchord-task2/baseline-vc69.apk` | 87 M | optional — keep on the old box |
| `.../BitChord/bitchord-loopfix.jks` | 2.6 K | **yes, out of band** |
| `.../BitChord/keystore.properties` | 103 B | **yes, out of band** |

Expected transfer: **~165 M** (55 M `.git` + ~110 M source).

---

## 1. Repo transfer

From a host that can reach both boxes (this one, `hfzhkn`), either use
`instabox pull` / `push`, or plain `rsync` if `grok-box` is reachable:

```bash
rsync -aH --info=progress2 \
  --exclude 'app/build/' \
  --exclude 'build/' \
  --exclude '.gradle/' \
  --exclude '.cxx/' \
  --exclude 'npu-addon/prebuilt/' \
  --exclude '*.jks' \
  --exclude 'keystore.properties' \
  bk-android:/data/workspace/bitchord-task2/work/BitChord/ \
  grok-box:/data/workspace/bitchord-task2/work/BitChord/
```

`--exclude '*.jks'` and `keystore.properties` are deliberate: secrets move in
step 2 so they are never in a bulk copy that might be logged or cached.

If `rsync` is unavailable between the boxes, stage through this host:

```bash
instabox pull bk-android /data/workspace/bitchord-task2/work/BitChord /tmp/opencode/bitchord-tree
instabox push /tmp/opencode/bitchord-tree grok-box:/data/workspace/bitchord-task2/work/
```

---

## 2. Secrets, out of band

The keystore is the only copy and is **never** committed (`.gitignore` covers
`*.jks` and `keystore.properties`). A verified backup already exists at
`/home/hfzhkn/.bitchord-secrets/` (mode `700`, files `600`).

```bash
# on the new box, then lock down
install -m 600 bitchord-loopfix.jks  /data/workspace/bitchord-task2/work/BitChord/
install -m 600 keystore.properties   /data/workspace/bitchord-task2/work/BitChord/
```

Verify the keystore is byte-identical before building a release with it:

```bash
sha256sum bitchord-loopfix.jks
# expected: eaf46cc5eb1de1d513a26105396893c22eed6dd4e8e994a714d6c2fea60cf4a7
```

---

## 3. Parity check

Both ends must agree before the new box is trusted:

```bash
cd /data/workspace/bitchord-task2/work/BitChord
git rev-parse HEAD          # must match on both boxes
git status --porcelain | wc -l
git diff | wc -l
git remote -v               # expect origin -> survey-sudo/bitchord-mix
```

`HEAD` must be identical. `git status --porcelain` may differ by ignored build
output only; if it differs by anything else, stop and compare file by file.

---

## 4. First build

```bash
cd /data/workspace/bitchord-task2/work/BitChord
nohup ./gradlew :app:assembleProdRelease --offline --no-daemon \
  -Pbitchord.bundleWear=false > /tmp/build-first.log 2>&1 &
echo $! > /tmp/build-first.pid
```

Drop `--offline` on the very first build so Gradle can populate `~/.gradle`;
after that, `--offline` is the normal mode. A cold first build downloads
roughly **1.5–2.5 G** of Maven artifacts on top of the SDK.

Then run `verify-build.md`.
