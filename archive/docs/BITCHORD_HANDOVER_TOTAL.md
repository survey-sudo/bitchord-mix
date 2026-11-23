# BitChord Automix — TOTAL HANDOVER
## `djmix18` / `vc60` LIVE on phone · `djmix19` / `vc61` staged in worktree

**As-of:** 2026-11-13 ~15:20 local (08:20Z). **Author:** subagent handover, read-only against the phone and the `bk-android` worktree.
**Verification rule:** every SHA-256, byte count, line count and path below was checked directly by shell this session. The only non-reproducible items are explicitly marked `[DOCUMENTED, NOT CURRENTLY VERIFIABLE]`.
**Honesty box (5 lines):**

1. The phone `1439ebea` runs `com.music.bitchord.loopfix` **`2.2.3-djmix18` (`versionCode=60`)**; the installed `base.apk` SHA-256 is byte-identical to the delivered APK (`5f50d0d1…6798c`, `87,481,971 B`).
2. `djmix18` was heard by the user on the `Drip → UP` pair. The verdict is **"not so good but definitely a progress"** — audible chipmunk/roll artifact, unreliable tempo, very low mix volume, lingering early-`UP` stutter.
3. Telemetry proves the mechanical failures: **762× `mix.freeze.onInterruption` over 113.835 s**, **3× `mix.arm.death: "outgoing decode 0 ms over"`**, and a **`+3230 ms`** handover correction (`11,954 → 15,184 ms`) on the `Drip → UP` loop fire.
4. All 5 `djmix19` root causes were re-verified at the exact `vc60` `file:line`; the 5 fixes are **already applied as unstaged worktree edits** (not yet built, not installed). Six shared `Djmix19` tests pass; one older shared assertion is now stale and fails; the `:app` `Djmix19` tests and the `vc61` APK are still pending.
5. No audio-quality claim is made for `djmix19`. The listening gate remains user ears only: **`Beautiful` (Bazzi) → `Never Let Go` (LNGSHOT)** and **`Drip` → `UP` (ATEEZ)**.

---

## 1. Executive Live State

### 1.1 Phone — USB Xiaomi `peridot`, serial `1439ebea`

| Item | Verified value |
|---|---|
| Transport | `adb devices -l` → `1439ebea device usb:3-6 product:peridot_global model:24069PC21G device:peridot transport_id:4` |
| Android | `ro.product.device=peridot`, Android `16` |
| Package | `com.music.bitchord.loopfix` |
| Installed version | `versionCode=60 minSdk=26 targetSdk=36`, `versionName=2.2.3-djmix18`, `lastUpdateTime=2026-11-13 13:59:06` |
| Installed payload | `/data/app/~~BjGBYx77SpDR5lTt6ZlS1w==/com.music.bitchord.loopfix-uI8rqwlWvdlF1xqh_xuKZA==/base.apk` — SHA-256 `5f50d0d13292b21aef79fea52616b391114073b748ccc1fc1342b3e6d9a6798c`, `87,481,971 B` — **exact match to the delivered APK** |
| Install method | `adb -s 1439ebea install -r` → `Performing Streamed Install` / `Success` (`djmix18-proof/install.log`, 36 B, exit 0) |
| Process at listening test | `ownerPid=28509`, `ownerUid=10490` (`media_session-drip-up.txt:32`) |
| Process at this handover check | `pidof com.music.bitchord.loopfix` returned empty (app not running) — no playback action was sent |

### 1.2 Delivered APK and patches

| Artifact | Path | Lines | Bytes | SHA-256 |
|---|---|---:|---:|---|
| **djmix18 APK** | `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix18-automix-arm64-v8a-oldkey.apk` | — | **87,481,971** | **`5f50d0d13292b21aef79fea52616b391114073b748ccc1fc1342b3e6d9a6798c`** |
| djmix18 delta patch | `/home/hfzhkn/bitchord-djmix18.patch` | **2,763** | **148,390** | **`4f70169e66d859d9816dada55c2f23f3086cdf85e3e6f95fee68dc3e81a40196`** |
| djmix18 full patch | `/home/hfzhkn/bitchord-djmix18-full.patch` | **8,512** | **436,046** | **`189e7dafc377520fd6b4866dbdb71609bbf8aba9471b4693976d184058be74eb`** |
| djmix18 cert | signer #1 certificate SHA-256 (v2) | — | — | **`20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`** (`oldkey`) |

APK verification (this session):
- `unzip -l` → **14 entries, all `lib/arm64-v8a/`**; no `armeabi-v7a`, `x86`, `x86_64`.
- `apksigner verify --verbose --print-certs` on `bk-android` → **Verifies**, v2 scheme only, 1 signer, cert digest `20c66ad6…e3fe4c`.
- `aapt2 dump badging` → `package: name='com.music.bitchord.loopfix' versionCode='60' versionName='2.2.3-djmix18' minSdkVersion:'26' targetSdkVersion:'36'`.
- Remote build output SHA and size equal the local APK **bit-for-bit**.

### 1.3 Cloud build infrastructure

| Machine | ID | State (this check) | Role / truth |
|---|---|---|---|
| **`bk-android`** | `m_agq4fglq7npke2orqgt5j6leai` | **`running` / `desiredState=running`** | Source of truth. Repo `/data/workspace/bitchord-task2/work/BitChord`. `HEAD=dc78e0d` (djmix9). `git diff --cached \| wc -l` = **6085** (index = `djmix17-full`). Worktree = **djmix18 + djmix19**, unstaged `git diff \| wc -l` = **3689** at check. Persistent `/data` (`snapshotSeq=5`), Gradle 8.13, JDK 21, Android SDK at `/opt/android-sdk` (build-tools `36.0.0`, `apksigner`, `aapt2`). |
| **`tanyadf-standby`** | `m_agq2kr74g6g6wssjzizwcsakyy` | **`not_found`** via `instabox get`; not in `instabox list` | `[DOCUMENTED, NOT CURRENTLY VERIFIABLE]` Standby prepared 2026-11-13T06:12Z: `/data/workspace/bitchord-alt/staging/` with the `oldkey` keystore (700 perms), `build-vc60.sh`, warmed Gradle cache, JDK 21, `ANDROID_HOME=/data/android-sdk`. **Do not assume it exists** — recreate or use `bk-android`. The API key for that box was passed out-of-band by the user and is **never** stored in this or any doc. |

`bk-android` note: the running box has a live Gradle 8.13 daemon and Kotlin daemon (started ~07:36Z); the last recorded test work was the `Djmix19` shared run at **07:39Z**. No `vc61` APK exists yet (`app/build/outputs/apk/prod/release/` still contains the 06:56Z `vc60` APK, `5f50d0d1…`).

### 1.4 Evidence snapshot (`/home/hfzhkn/bitchord-device-evidence/djmix18-proof/`)

| File | Lines | Bytes | SHA-256 |
|---|---:|---:|---|
| `telemetry-pull3-drip-up.jsonl` | **6,563** | **1,108,434** | **`ddd233b6ff8dc93f23124fcc57162561dff28652b7bd56ad324fae5e9dd0349a`** |
| `telemetry-pull2-postinstall.jsonl` | 5,657 | 993,181 | `3de9a09d0a7a584fea3914c7a95d68401582ffd7b8818ded5da52d5581cac1bc` |
| `telemetry-pull1-preinstall.jsonl` | 5,590 | 980,919 | `3280980633bd5829ae1841dbd86a3a73c73862c82ba66ff5824d0c13bb5188b7` |
| `delta-digest-vc60.txt` | 145 | 33,894 | `c5b2043ae696cea0ad0a462367eae1c54e4846bdd96058b4626c117930fa0987` |
| `media_session-drip-up.txt` | 66 | 5,054 | `c828ed52ecd2305fbbc91258629c169e0b0032059f4346558496503aaa351207` |
| `logcat-drip-up-grep.txt` | 92 | 23,547 | `86437c57b46eeeaa12fdf9808be34319290744c1364d2f1b1c75904dffe33240` |
| `apk-sha.txt` | 3 | 229 | `7ae6e76314355cba12fbac40e87cc4e4660f5feff2bbda80337e0ba701749277` |
| `dumpsys-after-install.txt` | 3 | 111 | `ac615b180eddadffbaf932a02da7fcd8529ceda2a1c6cb58acc8f34e81f44f7f` |
| `install.log` | 2 | 36 | `Performing Streamed Install` / `Success` |
| `crash-log.txt` | **0** | **0** | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` (no AndroidRuntime/AutomixCrash lines) |
| `logcat-drip-up.txt` | 0 | 0 | full logcat buffer was empty at pull; grep file is the retained evidence |

**Pull-chain proof:** `telemetry-pull3` lines **1–5657** are byte-identical to `telemetry-pull2` (`cmp` clean, same SHA `3de9a09d…`). Therefore lines **5658–6563** are exactly the 906 new events of the `Drip → UP` listening session; `seq` is contiguous **1…906** with no gaps.

**On-device telemetry now:** `/sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl` = **6,787 lines**, SHA-256 `0689814a13b833396ec5126dabe01b070134bfc070f01f9d71288f458a7daa57` — i.e. the phone appended **224 lines** after the frozen 6,563-line proof snapshot. The proof file, not the live file, is the canonical vc60 listening evidence.

---

## 2. Hard Project Constraints (§2, verbatim & intact)

> Reproduced exactly from `BITCHORD_HANDOVER_DETAILED.md` §2. These are non-negotiable.

* Package `com.music.bitchord.loopfix`, `arm64-v8a` ONLY (verified `unzip -l` only `lib/arm64-v8a/`, `aapt2 native-code:'arm64-v8a'`).
* Signing `oldkey` SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` (v2, `apksigner verify --verbose --print-certs`).
* Install: `adb install -r` ONLY — never `adb uninstall` / `pm clear` (would wipe queue/analysis/DB and falsify proofs).
* Cloud build box `bk-android:/data/workspace/bitchord-task2/work/BitChord` via `instabox` (not MCP box). Pre-step: `mkdir -p shared/build/intermediates/aar_metadata_check/release/checkReleaseAarMetadata`. Long builds: `instabox exec --background bk-android -- ./gradlew :app:assembleProdRelease --offline --no-daemon > /tmp/release-build*.log 2>&1` + `instabox jobs wait` synchronously (no `sleep` loops). Binary transfer: stdout-only `instabox ssh bk-android -- "cat …apk" > local.apk` (no `2>&1`), verified by `sha256sum`+size bit-for-bit.
* Patch lineage: `HEAD=dc78e0d` (djmix9), `djmix16` staged in index (`git diff --cached`), `djmix17` in working tree (`git diff`). Delta `git diff`|base64, full `git diff HEAD`|base64. Historical patches (`djmix14/15/16`) immutable.
* DSP/patent: scalars-only across decks/blends (no raw PCM cross-deck sharing). GPL-only deps. Signalsmith (analysis C++) untouched in this sprint. No post-handover `setPlaybackSpeed` for mix tempo. Zero audio-thread allocs/locks. Compose UI layout frozen — no UI churn, fixes land in planner/controller only.

**Live lineage clarification (supersedes only the two lineage sentences above):** at `vc60`, index = `djmix17-full` (6,085 lines), worktree = `djmix18` delta (2,763 lines). At this check, worktree additionally contains the `djmix19` edits; unstaged `git diff` is 3,689 lines. `HEAD` is still `dc78e0d`. No `git reset`, no `git add` of existing files, `git add -N` only for new test files.

**Additional standing rules (from the project brief, enforced this cycle):**

* **US 8,280,539 avoidance (active until 2031-03-11):** exit candidates from Track A only from A's internal structure; entry candidates from Track B only from B's internal structure; score `(exit_A, entry_B)` with **scalar summary metrics only** (BPM ratio, phase offset at anchor, Camelot key distance, scalar vocal-activity share, scalar regional LUFS). **Never** compute a sliding-window frame-by-frame cross-track descriptor correlation across the overlap. No raw PCM cross-deck sharing.
* **Signalsmith headers untouched** (`native/loopmix/signalsmith-stretch/**`). No new GPL-incompatible deps.
* **Versioning:** `versionName` base frozen at `2.2.3-djmix<N>`; `versionCode` **+1 per build** (`vc59 → vc60 → vc61`). `versionCode` is the install-gate witness.
* **Zero audio-thread allocations/locks.** Native renderer remains lock-free on the audio path; `AtomicLong` only on the control side.
* **Compose UI frozen.** Fixes land in `shared/` planner + `app/` controller/loop-mix, not in layout.
* **No post-handover `setPlaybackSpeed`** for mix tempo; no post-handover `beginEase`/`holdPhase` restart.
* **Never edit/delete** prior handovers, prior patches, prior APKs, or prior proof directories. This document is additive.

---

## 3. Complete Shipped History — `djmix8` → `djmix18` / `vc50` → `vc60`

### 3.1 APK ledger (all in `/home/hfzhkn/Downloads/`, `arm64-v8a` only, `oldkey`)

| Build | versionName | vc | File | Bytes | SHA-256 |
|---|---|---:|---|---:|---|
| djmix8 | `1.9.1-djmix8` | 50 | `bitchord-loopfix-1.9.1-djmix8-arm64-v8a-oldkey.apk` | 87,582,011 | `4e7c4a0005a161eb5ffa00af3cd6ec92248933b968a3d85240e7850063aefd38` |
| djmix9 | `1.9.1-djmix9` | 51 | `bitchord-1.9.1-djmix9-automix-arm64-v8a-oldkey.apk` | 87,573,558 | `b0af10490acefc85c2be30376e7e6aade973b9c8d0e21d2ffdc4327deb681c47` |
| djmix10 | `2.0.0-djmix10` | 52 | `bitchord-2.0.0-djmix10-automix-arm64-v8a-oldkey.apk` | 87,465,587 | `82ebbbf5dec8b5194889cb8df450433ba09b1adbf8820635663a8cd875fe1889` |
| djmix11 | `2.0.1-djmix11` | 53 | `bitchord-2.0.1-djmix11-automix-arm64-v8a-oldkey.apk` | 87,465,587 | `357e281d34e9f7222b2fd43196eaec0733b8db717c2c6f43aee8444074bdf029` |
| djmix12 | `2.1.0-djmix12` | 54 | `bitchord-2.1.0-djmix12-automix-arm64-v8a-oldkey.apk` | 87,465,587 | `a25900e9647534511b36fdb70abfc914b1fb5eaceb1010b0a7660f4848a1eb8d` |
| djmix13 | `2.1.1-djmix13` | 55 | `bitchord-2.1.1-djmix13-automix-arm64-v8a-oldkey.apk` | 87,465,583 | `2237caa1dc72b4afb52c2633585debd8cb5cbe8f6e75fd40a806fedd8dd69f9b` |
| djmix14 | `2.2.0-djmix14` | 56 | `bitchord-2.2.0-djmix14-automix-arm64-v8a-oldkey.apk` | 87,465,587 | `dd8d49967f0826c5e9330368f4130a642a39686bb47b78216ce64828ba19f915` |
| djmix15 | `2.2.1-djmix15` | 57 | `bitchord-2.2.1-djmix15-automix-arm64-v8a-oldkey.apk` | 87,481,971 | `ca90837f1528df8a749f88b3fe07ba663c8cf522692960da86fd04bdb950057c` |
| djmix16 | `2.2.2-djmix16` | 58 | `bitchord-2.2.2-djmix16-automix-arm64-v8a-oldkey.apk` | 87,481,967 | `a9e80e71248c4899ff58190a0742621f40a6ab81c02b2135a0a3f41403a44010` |
| djmix17 | `2.2.3-djmix17` | 59 | `bitchord-2.2.3-djmix17-automix-arm64-v8a-oldkey.apk` | 87,481,971 | `0be66f3a8f391c76696f5b7b76d8b701341356c4e41804ac4a5914b68c69c039` |
| **djmix18** | **`2.2.3-djmix18`** | **60** | **`bitchord-2.2.3-djmix18-automix-arm64-v8a-oldkey.apk`** | **87,481,971** | **`5f50d0d13292b21aef79fea52616b391114073b748ccc1fc1342b3e6d9a6798c`** |

All eleven APKs were re-hashed on disk this session. No prior APK was modified or deleted.

### 3.2 Patch ledger (`/home/hfzhkn/`, all re-verified this session)

| Build | Delta patch | Lines / Bytes / SHA-256 | Full patch | Lines / Bytes / SHA-256 |
|---|---|---|---|---|
| djmix8 | — | `[no patch artifact on disk]` | — | — |
| djmix9 | — | `[HEAD commit dc78e0d is djmix9; no patch artifact]` | — | — |
| djmix10 | `bitchord-djmix10.patch` | 341 / 18,564 / `983a53050affca83ccaf3754e69b69e5d7542d3e3c61035e84e64af1bfc7be67` | — | — |
| djmix11 | `bitchord-djmix11-patch-20261112T1620Z.patch` (combined 10+11) | 1,886 / 89,160 / `c664746e077fe9813f566686f89d241f29c0500a3c77082f068926b638ce9f14` | — | — |
| djmix12 | `bitchord-djmix12.patch` | 567 / 28,549 / `32f825ade29cd692fe9abe57704136647bb754d72e56185f4b27fa1d63a1f8a0` | `bitchord-djmix12-full.patch` | 2,263 / 108,130 / `eef252364b4b493df31e05cf40b2760951fd9686e300d24a6904cd96ea44023b` |
| djmix13 | `bitchord-djmix13.patch` | 968 / 48,991 / `5fb2e2d300c5be748827dc24eb75310857faba802530ade15bd29f691628e96f` | `bitchord-djmix13-full.patch` | 2,642 / 127,368 / `e816bfd57a0eb34c44d4f748f30380815c31cf9b78bbf5860730e7d5b691cc43` |
| djmix14 | `bitchord-djmix14.patch` | 2,108 / 109,690 / `dbdbbb0ae32d108bbe25a47262d26b6efeacd99acab53a07453d92672456673d` | `bitchord-djmix14-full.patch` | 3,986 / 197,592 / `f645c223f8313ee8744576aded22b0d4631762c205799a96905d5c33b5cd6f96` |
| djmix15 | `bitchord-djmix15.patch` | 855 / 47,420 / `67a95fa614ba14d246e26668c54203701b2867c42b8a02e9d3e063b6e556dd7c` | `bitchord-djmix15-full.patch` | 5,045 / 252,483 / `a98173f718b9213b38de8cf65df2312413f0ed63750a90455557c2d6082e301c` |
| djmix16 | `bitchord-djmix16.patch` | 683 / 37,536 / `f0d08d4f34808c801a179525d6bd90676ecca35315ec82c9ed4b6f93d59086c7` | `bitchord-djmix16-full.patch` | 5,545 / 279,458 / `f2143e393139de281c1d8b1dbaa1427bd8d9dc214253020f3a4e402bdd2471fc` |
| djmix17 | `bitchord-djmix17.patch` | 681 / 35,418 / `31c313ccf982368237f8511e8801cec70cab6083a53c9b3f63736d72e3669ec8` | `bitchord-djmix17-full.patch` | 6,085 / 307,018 / `4a4c6821a4a7d463a8cfcb8be45752b5f0fa81f70bd40de141ff27f4efb804e3` |
| **djmix18** | **`bitchord-djmix18.patch`** | **2,763 / 148,390 / `4f70169e66d859d9816dada55c2f23f3086cdf85e3e6f95fee68dc3e81a40196`** | **`bitchord-djmix18-full.patch`** | **8,512 / 436,046 / `189e7dafc377520fd6b4866dbdb71609bbf8aba9471b4693976d184058be74eb`** |

Patch shape: djmix18 delta = **18 files, 1,681 insertions, 226 deletions** (`grep -c '^diff --git'` = 18); djmix18 full = **50 files, 6,199 insertions, 376 deletions** (50). The djmix18 patch contains the version bump exactly:
`-extra["bitchordVersionCode"] = 59` / `+… = 60`, `-extra["bitchordVersionName"] = "2.2.3-djmix17"` / `+… = "2.2.3-djmix18"`.

### 3.3 The 4 fixes shipped in `djmix18` / `vc60`

| # | Defect | Shipped change | Files |
|---|---|---|---|
| 1 | Deck speed could spike to absurd **999×/8×** on tempo jumps, producing a loud hyperspeed blip or a unity jump at handover | `CrossfadeController.safeDeckSpeed` clamp before the renderer; tempo-jump handling in `TransitionMoves` + native renderer | `CrossfadeController.kt`, `TransitionMoves.kt`, `native/loopmix/loop_mix_renderer.cpp` (17 `safeDeckSpeed` refs in patch) |
| 2 | Post-automix incoming intro jumped/stuttered from its intro | `LoopMix.Clock.incomingHoldSec` + hold-aware knots + pre-seek/guarded handover seek; **no** post-handover `beginEase`/`holdPhase` restart | `LoopMix.kt`, `LoopMixSession.kt`, `CrossfadeController.kt` (27 `incomingHoldSec` refs) |
| 3 | Outgoing song cut at **~60–70%** or ended abruptly instead of playing its tail | `MIN_PLAY_FRACTION=0.78` floor; `CuePoints` no longer forced `\|\| map.partial`; `StructureAnalyzer.fromPcm(fullTrackDurationSec)`; `TrackAnalyzer` 360 s cap; smooth `RELAXED`/`VOCAL_OVER_OUTRO` exit curve + `outTo` extension | `CuePoints.kt`, `StructureAnalyzer.kt`, `SongMaps.kt`, `PhrasePlanner.kt`, `LoopMix.kt`, `TrackAnalyzer.kt` (20 `playableOutDurationSec` refs) |
| 4 | Heavy beat-mixable tracks / `Beautiful → Never Let Go` failed to get a clean 8-bar mix | `LoopMix.firstClean` quiet-outro relaxation; 2-bar vocal cap removed in `blendPlan`; `TransitionPolicy`/`TransitionPlanner` ±8% steady-grid rescue; 28 s/10-bar cold-loop yield; `playableOutDurationSec` rendition clamp | `LoopMix.kt`, `TransitionPolicy.kt`, `TransitionPlanner.kt`, `TransitionMoves.kt`, `CrossfadeController.kt` |

**Version bump:** `bitchordVersionCode 59→60`, `bitchordVersionName 2.2.3-djmix17→2.2.3-djmix18`.

### 3.4 Test gates and lineage

| Gate | Result |
|---|---|
| `:shared:allTests` (vc60) | **697/697 pass** — jvmTest 271 + testDebugUnitTest 213 + testReleaseUnitTest 213, 0 failures |
| `:app:testProdDebugUnitTest` (vc60) | **1,271 tests, 0 failures, 0 errors, 1 skipped** across 114 TEST XML files (current artifacts: 1,263 in `testProdReleaseUnitTest` + 8 in the targeted `testProdDebugUnitTest` rerun) |
| `Djmix18RegressionTest.kt` (new, shared) | 6 tests, 0 failures, in each of jvmTest / testDebugUnitTest / testReleaseUnitTest |
| `Djmix18AppHandoffTest.kt` (new, app) | 8 tests, 0 failures — fresh XML `2026-11-13T06:57:51Z`, job `b7fb4e9a`, `BUILD SUCCESSFUL in 39s` |

Lineage discipline (verified): `git diff --cached | wc -l` = **6,085** before and after patch generation; `git add -N` only for new `Djmix18*` test files; no reset/add of existing files. The vc60 build ran from the worktree at 06:56Z; its remote APK is the local APK.

---

## 4. Live `vc60` Listening Test & Telemetry Evidence — `Drip` 78.92 BPM → `UP` 162.01 BPM

### 4.1 Test identity

| Item | Value |
|---|---|
| Outgoing | `Drip` (ATEEZ) — plan `bpmA = 78.88987153663085` |
| Incoming | `UP` (ATEEZ, `GOLDEN HOUR : Part.6`) — plan `bpmB = 162.01032877218537`; `media_session` duration `206,341 ms` |
| User's spoken shorthand | **"79 to 161 bpm"** (same pair; telemetry has 78.89 → 162.01) |
| Session | app restart boundary at `L5658 seq=1`; 906 events through `L6563 seq=906` |
| Nature of session | listening/scrub session: **26 `mix.seek`** (many `action=cancel`), **24 `mix.plan.rejected` (scrub-cancelled/phrase-null)**, **9 `mix.fire`** (3 loop + 6 classic) — not a clean hands-off run |

`media_session-drip-up.txt` confirms the active session: `BitChordPlayback/60`, `ownerPid=28509`, `state=PLAYING(3), position=126333, buffered position=206341, speed=1.0`, metadata `UP, ATEEZ, GOLDEN HOUR : Part.6`, `queueTitle=UP, size=5`.

### 4.2 User ear verdict on `vc60` (verbatim, spelling preserved)

> "Drip ATEEZ to UP ATEEZ 79 to 161 bpm and drip is sped up 999x during the mix (roll/filter iirc) and its unreliable too, at some point it just go 79 to 81 bpm beat blend (smoother this time, but 1. volume of mixxing is super low still; immature mixing and unnatural, amateur 2. the stutter during early part of UP still happens). overall: not so good but definitely a progress"

This is the only accepted audio-quality truth for `vc60`. Telemetry cannot prove timbre, "999×" magnitude, or loudness perception; the mechanisms below are the measurable correlates.

### 4.3 Telemetry snapshot — verified numerics

| Claim | Verification |
|---|---|
| File has **6,563 lines** | `wc -l` = 6,563 |
| SHA-256 **`ddd233b6…0349a`** | `sha256sum` exact |
| Slice **5658–6563** = **906 lines**, `seq 1–906` | Python assert: first `seq=1`, last `seq=906`, contiguous `1..906` |
| **144 non-freeze** events | 906 − 762 = 144; histogram below |
| **762× `mix.freeze.onInterruption`** | `grep -c` = 762 |
| Freeze span **113.835 s** | first `seq=103 ts=1794553519097`, last `seq=876 ts=1794553632932`; Δ = 113,835 ms |
| Pull3 prefix equals pull2 | lines 1–5657 byte-identical, same SHA `3de9a09d…` |
| **3× `mix.arm.death`** | `seq 85, 94, 111`, all `reason="outgoing decode 0 ms over"`, `phase="LOOP_ARMING"` |
| Final loop fire | `seq 900–904` (`expectedHandoverMs=15120`, `actualHeardMs=15184`, `targetMs=15184`, `driftMs=3230`, jump `11954→15184`) |
| `MediaCodec Drop` | `Drop:258` at `14:11:21.609`, `Drop:250` at `14:11:26.771`, pid `28509`, `c2.android.opus.decoder`, `Qin:254` |

### 4.4 Event histogram (slice 5658–6563)

| Event | Count |
|---|---:|
| `mix.freeze.onInterruption` | **762** |
| `mix.plan` | 44 |
| `mix.seek` | 26 |
| `mix.plan.rejected` | 24 |
| `mix.complete` | 13 |
| `mix.loop.fallback` | 12 |
| `mix.exit.tail` | 10 |
| `mix.fire` | 9 |
| `mix.arm.death` | **3** |
| `mix.exit.guard` | 2 |
| `mix.exit.gate` | 1 (with `violations:0`) |
| **Non-freeze total** | **144** |
| **Grand total** | **906** |

### 4.5 The 3 `outgoing decode 0 ms over` arm deaths — exact attribution

| Event | `msSincePlan` | Following `mix.complete` pair (`bpmA → bpmB`) | Reading |
|---|---:|---|---|
| `seq 85` `mix.arm.death` | 12,210 | `seq 87`: `85.23676298592028 → 78.91865521907009` | Preceding pair, **not** Drip→UP |
| `seq 94` `mix.arm.death` | 9,995 | `seq 96`: `78.91865521907009 → 162.01032877218537` | **Drip→UP** |
| `seq 111` `mix.arm.death` | 9,525 | `seq 113`: `78.91865521907009 → 162.01032877218537` | **Drip→UP** |

Every arm death is immediately followed by `mix.loop.fallback` with the same reason and `mix.complete handoff:false`; the mix then re-plans and falls back to classic (`seq 89`, `98`, `115` classic fires). This is the lockout that makes the loop path unreliable.

> **Attribution correction (raw-JSON check):** the `mix.arm.death` record has **no template field**. Two of the three deaths are on the `Drip→UP` (`M1`) pair; `seq 85` belongs to the preceding `85.24→78.92` transition, as proven by the `mix.complete` pair BPMs. So "3 arm deaths during the Drip→UP listen" is correct; "all 3 on M1" is not.

### 4.6 Final `Drip → UP` handover — `seq 900–904`

```json
L6557 seq=900 ev=mix.fire mode=loop plannedStartMs=148228 actualStartMs=147555 deltaMs=-673 prerollSec=1.0 spliceTargetMs=148228 trackIndex=1
L6558 seq=901 ev=mix.seek phase=LOOP_RUNNING reason=loop-handover-correct expectedHandoverMs=15120 actualHeardMs=15184 targetMs=15184 driftMs=3230
L6559 seq=902 ev=mix.seek phase=LOOP_RUNNING positionBeforeMs=11954 positionAfterMs=15184 action=handover-suppressed planAgeMs=52926
L6560 seq=903 ev=mix.exit.gate violations=0
L6561 seq=904 ev=mix.complete phase=LOOP_RUNNING handoff=true bpmA=78.91865521907009 bpmB=162.01032877218537 tailMs=1521 reason=loop-complete expectedHandoverMs=15120 actualHeardMs=15184 targetMs=15184
```

Plans immediately before the fire: `seq 898` `template=M1`, `seq 899` `template=classic` (same pair). The fire is `mode=loop`, `trackIndex=1`. The handover **completed** with `violations:0` and `handoff:true`; it was not a crash. But the controller had to jump the incoming player **+3,230 ms** (11.954 s → 15.184 s), which matches the user's "stutter during early part of UP". The `deltaMs=-673` on the fire means the loop started 673 ms before its planned splice target.

### 4.7 Decoder backpressure evidence

`logcat-drip-up-grep.txt`:

```
14:11:21.609 28509 20784 I MediaCodec: [mId: 57] [audio-debug-dec] state 6 mFlags 19456
  Stats 5001ms: Client:com.music.bitchord.loopfix, Component:c2.android.opus.decoder,
  Qin:254, DQin:0/0, Render:0, Drop:258, DQout:0/0
14:11:26.771 28509 20784 I MediaCodec: … Stats 5161ms … Qin:254 … Drop:250 …
```

Two ~5 s stat windows show ~250 dropped frames each on the Opus decoder while only 254 frames were queued. This is consistent with the late/short decode that produces `outgoing decode 0 ms over` and the audible stutter; it is **not** by itself proof of the perceptual cause.

### 4.8 What is proven / not proven

**Proven:** installed bits; pair BPMs; 762 freezes over 113.835 s; 3 arm deaths and their pair attribution; final loop handover with +3.230 s correction; `violations:0`; no crash; decoder drop counts; user verdict as quoted.

**Not proven:** subjective "999×" magnitude (the measured roll defect is 2.0× read + 95 ms loop; see Fix 1); exact perceptual loudness cause (multiple gain stages); that any proposed fix improves the sound until the user listens to `vc61`; that `Beautiful → Never Let Go` has been listened to on vc60 (the recorded vc60 listen is `Drip → UP`; the Beautiful pair remains the other required gate).

---

## 5. Exact `file:line` Root-Cause Diagnosis and `djmix19` (`vc61`) Blueprint

### 5.0 Verification method and current status

The `vc60` line numbers below were re-verified by reconstructing the exact vc60 source on `bk-android`:
`git checkout-index -a --prefix=/tmp/opencode/vc60/` (index = `djmix17-full`) + `patch -p1 < bitchord-djmix18.patch` → then `cat -n` at each cited line. The current worktree was checked separately for the applied `djmix19` edits.

**Status of the 5 fixes (critical):**

| Fix | `vc60` diagnosis verified | `djmix19` edit in worktree | Dedicated test | State |
|---|---|---|---|---|
| 1 — roll chipmunk | yes | yes (`DjColour.kt`, `DjMix.kt`) | `rollReadPointerAdvancesAtOneTimesSpeed`, `tempoGapNeverPicksLoopRoll` | **applied, shared test green** |
| 2 — cache mismatch | yes | yes (`LoopMix.kt`, `LoopMixSession.kt`) | `regionBoundsCoversStraightDeckStopAt` | **applied; one older shared assertion now stale/failing** |
| 3 — hold/drift | yes | yes (`LoopMixSession.kt`, `CrossfadeController.kt`) | `alignedSecondsIs12msNot1ms`, `hold18sMixStartsIncomingNearHoldNotAtLead` | **applied, shared tests green** |
| 4 — low volume | yes | yes (`MixGain.kt`, `CrossfadeController.kt`, `RegionalLoudness.kt`, `TransitionMoves.kt`) | `headroomFloorIs94AndLoudnessCutFloorIsMinus35`, `transitionMovesM1HighsAndM9InGainAreRaised`, `volumeFloorsFromAppSide` | **applied, shared tests green** |
| 5 — 762× freeze | yes | yes (`CrossfadeController.kt`) | **none yet** | **applied; add a regression test before shipping** |

`Djmix19RegressionTest.kt` = **6 tests**; `Djmix19AppTest.kt` = **4 tests**. Shared jvm run at **07:39Z**: `Djmix19RegressionTest` 6/6 pass. The same run reports **1 failure** in `Djmix14PlannerRescueTest.regionBoundsMatchBuildExactly` (expected `111.1`, actual `119.29926537830389`) — a stale expectation caused by Fix 2, not a new runtime defect. The `:app` `Djmix19` tests have **not** been run; no `vc61` APK exists.

### 5.1 Fix 1 — roll chipmunk: **2.0× read + 95 ms loop**, heard as "999×"

**vc60 evidence:**
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjColour.kt:238-260`
  - Line 240: `val read = rollPos - rollLen + rollCursor`
  - Line 249: `rollCursor++` every output frame inside the loop
  - Line 259: `rollPos++` every output frame, unconditionally
  - → the read pointer advances **2 frames per output frame = 2.0×** pitch/speed (chipmunk).
  - Lines 251-256 wrap `rollCursor` at `rollLen`, then halve `rollLen` every beat down to `beatFrames / 8`.
  - At 78.92 BPM / 48 kHz: beat = `60/78.92 = 0.7603 s = 36,494` frames; `beatFrames/8 = 4,562` frames = **95.0 ms**. That is the audible buzz/loop.
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/DjMix.kt:306-317`
  - `tempoGap` selects by `pairHash(outgoingId, incomingId) % 3`; branch `1` → `DjEffect.LOOP_ROLL`, `echoSeconds=0.0`.
- `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:5294`
  - `DjEffect.LOOP_ROLL -> if (progress > 0.35f) 1f else 0f` → full-wet roll for most of the transition.

**Fix (applied in worktree):**
- Freeze the read anchor at roll start: `rollAnchor = ((rollPos - rollLen) % frames + frames) % frames`; read `(rollAnchor + rollCursor) % frames`; never advance the anchor during the roll. Result: **1.0×**.
- Clamp `rollLen` at `beatFrames / 2` (no 95 ms floor).
- Remove `LOOP_ROLL` from `tempoGap` auto-rotation: only `REVERB` (`%3==2`) else `ECHO`.
- Files/lines now: `DjColour.kt:37-40, 87-94, 244-263`; `DjMix.kt` `tempoGap` (current form has `if (pairHash % 3 == 2) REVERB else ECHO`).

**Why this matches the ear:** the 2× read is a chipmunk transposition plus a 95 ms loop; both disappear at 1.0×/≥380 ms. The old `safeDeckSpeed` clamp (vc60) already prevents the separate 999× deck-speed class; the remaining "999×" perception is this roll path.

### 5.2 Fix 2 — 3× `outgoing decode 0 ms over` cache misses

**vc60 evidence:**
- `CrossfadeController.kt:1121-1131` — `isLoopFastReady()`:
  - gets `val b = LoopMix.regionBounds(plan)`;
  - asks `loopRegionCached(outItem, sampleRate, b.outFrom, b.outTo)` and the incoming equivalent.
- `LoopMixSession.kt:155-176` — `build()` computes its own **larger** `outTo` for straight outgoing decks (`outLoops == false`), extending past `bounds.outTo` to cover `stopAt`:
  - `outStopMixSec = clock.releaseAt + 0.05 + echoHoldEarly + exitTailSeconds`
  - `lastOutPos = out.loopStart + clock.beats * out.beatSec`
  - `pastElement = out.loopStart + clock.elementAt + max(echoHoldEarly, exitTailSeconds) + 0.5`
  - `outTo = maxOf(bounds.outTo, lastOutPos + tailTrack + 0.5, pastElement)`.
- `LoopRegionCache.kt:88-92` — `contains()` requires `reqStart >= stored.start - 1e-9 && reqEnd <= stored.end + 1e-9` (exact containment).

**Failure chain:** `isLoopFastReady` reports "ready" against the **smaller** `regionBounds` while the actual build requests the **larger** extended region. The cache contains the smaller region but not the larger one, so the controller takes the short 0.8 s lead and then the build has to decode; the outgoing decode is late by the time the loop arms → `mix.arm.death: "outgoing decode 0 ms over"` (3× in this run). `0 ms over` is the rounded/zeroed guard message; the real cost is the missed lead.

**Fix (applied):** make `LoopMix.regionBounds(plan)` the **single source of truth**, including the straight-deck tail (`stopAt`, echo hold, exit tail), and have `LoopMixSession.build()` use `bounds.outTo` without a second extension. Current `LoopMix.kt:1197-1222` does exactly this; `LoopMixSession.kt` no longer has the local `outTo` block. `isLoopFastReady`, the primer and the build now all request the same range.

**Consequence to fix before build (blocker):** `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/Djmix14PlannerRescueTest.kt:180` still asserts the old nominal `100 + 4*4*0.6 + 0.5 + 1.0 = 111.1`. The new correct value is **119.29926537830389** (dominated by `pastElement`: `100 + elementAt≈13.999… + 8-beat echo 4.8 + 0.5`). Update the assertion to the max-of-terms contract (or compare `regionBounds` against the build contract), do **not** revert Fix 2.

### 5.3 Fix 3 — `driftMs = +3230 ms` early-`UP` stutter

**vc60 evidence:**
- `app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt:79-96`
  - `planIncomingStart()`: `inStartAt = STARTUP_LEAD` (0.5 s) and `inStartTrack = max(0.0, handoverTrack - (clock.elementAt - inStartAt))` — **ignores `incomingHoldSec`**.
  - `offsetNow()`: `deck = handoverTrack - (clock.elementAt - m)` — ignores hold as well.
  - With an 18 s held incoming (9 prep bars at 120 BPM), the incoming player is started at the wrong timeline point; the 1:1 relationship to `handoverTrack` is broken until the correction.
- `LoopMixSession.kt:512` — `ALIGNED_SECONDS = 0.0015` (1.5 ms). Unachievable with AudioTrack/decoder scheduling latency; alignment chases an impossible target.
- `CrossfadeController.kt:4382-4418` — `startLoopAlignment()`:
  - assigns `loopAlignJob = scope.launch { … }`;
  - inside: `if (samples.size < LOOP_ALIGN_SAMPLES / 2) return@launch` — **returns without clearing `loopAlignJob`**;
  - after that, `driveLoopMix` only calls alignment when `loopAlignJob == null`, so one short sample window permanently locks out all later alignment.

**Telemetry correlate (seq 901-902):** expected handover `15,120 ms`, actual heard `15,184 ms`; the controller was at `11,954 ms` and corrected to `15,184 ms` (`driftMs=3230`). That is a 3.23 s jump at/after handover — the early-`UP` stutter.

**Fix (applied):**
- Hold-aware start: compute `base` from `incomingHoldSec` (start **1.5 s before** the hold point, capped at `elementAt - 3.0 s`, minimum `STARTUP_LEAD`), handle the negative-track case by starting at the earliest legal mix second; `inStartTrack` remains `max(0, handoverTrack - (elementAt - inStartAt))`. Current `planIncomingStart()` at `LoopMixSession.kt:79-94`.
- `ALIGNED_SECONDS = 0.012` (12 ms) and `LOOP_ALIGN_ROUNDS = 5`.
- `finally { if (loopAlignJob === coroutineContext[Job]) loopAlignJob = null }` so a short/aborted alignment can be retried.
- `offsetNow` stays `handoverTrack`-based; because `handoverTrack = inTrackTimeAt(elementAt)` now includes the hold, the 1:1 timeline is consistent.
- `LoopMixPort.kt` shift limits: `requestShift()` accumulates into `AtomicLong` with **no explicit cap**; `PrecisionAudioSink.kt:353-374` drains it per block with `minOf(shift, availableFrames)` / `minOf(-shift, capacityFrames)`, so it cannot overflow a buffer, but a very large pending shift would take multiple blocks. With the 12 ms tolerance the requests are bounded in practice. **Open item:** add an explicit max-pending-shift guard if future drift exceeds one audio block.

### 5.4 Fix 4 — super-low mix volume

**vc60 evidence (exact values):**

| Stage | File:line | vc60 value | Effect |
|---|---|---|---|
| Headroom floor | `shared/.../MixGain.kt:15` | `HEADROOM_FLOOR = 0.85f` | midpoint sum trimmed to −1.4 dB |
| Filter fall curve | `shared/.../MixGain.kt:38` | `fall(progress).pow(1.6f)` | fast, early level loss |
| Filter floor | `CrossfadeController.kt:6115` | `FILTER_FLOOR_HZ = 900.0` | removes body/upper bass |
| Filter shapes | `CrossfadeController.kt:6147-6148` | `FILTER_FALL_SHAPE = 1.6f`, `FILTER_RISE_SHAPE = 0.8f` | bass leaves before the fade is half over |
| Regional loudness | `shared/.../RegionalLoudness.kt:21` | `MIN_DB = -6.0` | a louder incoming is pulled down 6 dB |
| `M1`/`M3` mids | `shared/.../TransitionMoves.kt:155-166` | `inHigh` −6 dB → −3 dB; `inGain` −9 dB → −3 dB | incoming body withheld through the blend |
| `M9`/`RELAXED` | `TransitionMoves.kt:215-229` | `inGain = lane(…, mute, -4.0, -12f, 0.0, 0f)` | incoming sits 12 dB down for most of the overlap |

**Fix (applied):**

| Stage | New value |
|---|---|
| `MixGain.HEADROOM_FLOOR` | **0.94f** (max ≈ −0.54 dB trim) |
| `MixGain.filterFall` | **`pow(1.20f)`** |
| `CrossfadeController.FILTER_FLOOR_HZ` | **1500.0** |
| `FILTER_FALL_SHAPE` / `FILTER_RISE_SHAPE` | **1.15f / 0.65f** |
| `RegionalLoudness.MIN_DB` | **−3.5** (MAX_DB 4.5 unchanged) |
| `M1`/`M3` | `inHigh` **−2.5 → −1.0 dB**; `inGain` mid **−1.5 dB** (from −3) |
| `M9`/`RELAXED` | `inGain` **−3.5 → −1.5 dB** (from −12) |

All verified in the current worktree; the native `-1 dBTP` limiter remains the peak guard, so raising the floor does not add clipping.

### 5.5 Fix 5 — `762× mix.freeze.onInterruption` / 113.8 s hang

**vc60 evidence:**
- `CrossfadeController.kt:2674` — in the fade driver, every ~30 ms tick:
  `if (!handedOff && player.playbackState == Player.STATE_IDLE) return bail()`
- `CrossfadeController.kt:4904-4917` — `bail()`:
  - returns immediately if `phase == IDLE || BAILING`;
  - while armed (`FADING`, `TAIL`, `LOOP_ARMING`, `LOOP_RUNNING`, `ARMING`) logs `mix.freeze.onInterruption` **unconditionally** and returns;
  - therefore an incoming player that never leaves `STATE_IDLE` causes a log every 30 ms and **never exits the blend**.
- Measured: 762 logs, first `seq=103 ts=…519097`, last `seq=876 ts=…632932` → **113.835 s**.

**Fix (applied):**
- Track `idleStallSinceMs`; if the outgoing is within `HANDOFF_GUARD_MS` of running out, `bail(force = true)` immediately.
- After **1,500 ms** stalled: call `player.prepare()` **once**, then on the next tick force-exit if still idle.
- Dedup `mix.freeze.onInterruption` by `phase|outgoingId->incomingId` (`lastFreezeLogKey`) so a persistent stall logs once per pair/phase, not 762 times.
- Current code: `CrossfadeController.kt:678-683, 2680-2712, 4944-4962`.
- **Gap:** no dedicated `djmix19` test covers the stall force-exit or the freeze dedup. Add one before shipping.

### 5.6 `djmix19` version/test state at this handover

| Item | Value |
|---|---|
| `build.gradle.kts` | `bitchordVersionCode = 61`, `bitchordVersionName = "2.2.3-djmix19"` (lines 44-45) |
| New shared test | `shared/src/commonTest/kotlin/.../Djmix19RegressionTest.kt` — 263 lines, 6 tests |
| New app test | `app/src/test/java/.../Djmix19AppTest.kt` — 182 lines, 4 tests |
| Shared jvm result | `TEST-…Djmix19RegressionTest.xml`: **6 tests, 0 failures, 0 errors, 0 skipped**, `2026-11-13T07:39:28.972Z` |
| App result | **not run** (no `Djmix19` app XML) |
| Build | **not run** (no `vc61` APK; output dir still 06:56Z vc60) |
| Worktree diff | unstaged `git diff` = **3,689 lines** (djmix18 + djmix19); `HEAD` unchanged `dc78e0d` |
| Blocking test | `Djmix14PlannerRescueTest.kt:180` expected `111.1`, actual `119.29926537830389` |
| Missing tests | Fix 5 force-exit/dedup; optional alignment-job re-arm |

---

## 6. Exact Next-Session Opening Checklist

### 6.1 Preflight (no playback, no queue edits)

1. `adb devices -l` → expect `1439ebea device … device:peridot`. Do **not** uninstall, `pm clear`, or change queue.
2. `instabox list` → `bk-android` must be `running`. If `suspended`: `instabox resume bk-android --no-wait` **once**, then `instabox get bk-android`; never a `sleep` loop.
3. `instabox exec bk-android -- bash -lc 'cd /data/workspace/bitchord-task2/work/BitChord && git log --oneline -1 && git diff --cached | wc -l && git diff | wc -l'` → expect `dc78e0d`, **6085**, and **≈3689** (djmix18+djmix19). If the worktree is clean of djmix19, re-apply the 5 fixes below; if present, do **not** re-apply blindly.
4. Confirm `build.gradle.kts` = `bitchordVersionCode 61` / `2.2.3-djmix19`; if not, bump exactly +1.

### 6.2 Required code work before the build

1. **Keep/verify the 5 fixes** (all already applied; verify by `grep`):
   - `DjColour.kt`: `rollAnchor` frozen, `(rollAnchor + rollCursor)`, `rollLen` floor `beatFrames/2`.
   - `DjMix.kt` `tempoGap`: no `LOOP_ROLL` branch.
   - `LoopMix.kt` `regionBounds`: straight-tail `outTo` max; `LoopMixSession.build` uses `bounds.outTo` only.
   - `LoopMixSession`: hold-aware `planIncomingStart`, `ALIGNED_SECONDS=0.012`, `LOOP_ALIGN_ROUNDS=5`; `CrossfadeController.startLoopAlignment` has the `finally` clear.
   - `MixGain`: `0.94f`, `pow(1.20f)`; `FILTER_FLOOR_HZ=1500.0`, shapes `1.15f/0.65f`; `RegionalLoudness.MIN_DB=-3.5`; `M1` `inHigh -2.5/-1.0`, `inGain -1.5` midpoint; `RELAXED` `inGain -3.5/-1.5`; `bail` idle-stall + freeze dedup.
2. **Fix the stale test** `shared/src/commonTest/.../Djmix14PlannerRescueTest.kt:180`: update the expected `111.1` to the new `regionBounds` contract (`119.29926537830389` for that fixture, or assert `outTo == maxOf(nominal, lastOutPos + tailTrack + 0.5, pastElement)`). Do not revert Fix 2.
3. **Add the missing Fix 5 test**: incoming `STATE_IDLE` for >1.5 s must force-exit (not freeze forever) and must log `mix.freeze.onInterruption` at most once per `phase|pair`. Put it in `Djmix19RegressionTest.kt`/`Djmix19AppTest.kt` or a new `Djmix19StallTest.kt`.
4. Optional: a test that a short alignment sample window clears `loopAlignJob` and allows a later retry; and a `LoopMixPort` max-pending-shift guard if you add one.
5. `git add -N` only new test files if needed. **Never** `git reset` or `git add` existing files; `git diff --cached` must stay **6085** lines.

### 6.3 Test + build (cloud only, no `sleep` polling)

```bash
# targeted first
instabox exec bk-android -- bash -lc 'cd /data/workspace/bitchord-task2/work/BitChord && \
  ./gradlew :shared:jvmTest --tests "*Djmix19*" --offline --parallel && \
  ./gradlew :app:testProdDebugUnitTest --tests "*Djmix19*" --offline --parallel'

# full gate + release build, background job + synchronous wait
instabox exec --background bk-android -- bash -lc 'cd /data/workspace/bitchord-task2/work/BitChord && \
  ./gradlew :shared:allTests :app:testProdDebugUnitTest :app:assembleProdRelease --offline --no-daemon \
  > /tmp/djmix19-full.log 2>&1'
instabox jobs wait bk-android <job-id>
```

Expected: `Djmix19RegressionTest` 6/6, `Djmix19AppTest` 4/4, `Djmix14PlannerRescueTest` green after the expectation fix; full `:shared:allTests` and app suite green; `BUILD SUCCESSFUL`, `DONE:0`. If the build is already alive on resume, **await it** with `jobs wait`; do not kill a demonstrably running daemon.

### 6.4 Verify, pull, install

1. On `bk-android`:
   ```bash
   APK=app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk
   sha256sum "$APK"; stat -c%s "$APK"
   unzip -l "$APK" | grep 'lib/' | awk '{print $4}' | cut -d/ -f1-2 | sort -u   # only lib/arm64-v8a
   /opt/android-sdk/build-tools/36.0.0/apksigner verify --verbose --print-certs "$APK"
   /opt/android-sdk/build-tools/36.0.0/aapt2 dump badging "$APK" | head -3          # vc61 / 2.2.3-djmix19
   ```
   Must show: **v2**, signer cert `20c66ad6…e3fe4c`, 14 `lib/arm64-v8a/` entries, `versionCode='61'`, `versionName='2.2.3-djmix19'`.
2. Pull **new** names only:
   - APK → `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix19-automix-arm64-v8a-oldkey.apk`
   - full patch → `/home/hfzhkn/bitchord-djmix19-full.patch` (`git diff HEAD`, from `dc78e0d`)
   - delta patch → `/home/hfzhkn/bitchord-djmix19.patch` (vc60→vc61 only). Reliable method: reconstruct vc60 in `/tmp/opencode/vc60` (`git checkout-index -a --prefix=…` + `patch -p1 < bitchord-djmix18.patch`), `git init && git add -A && git commit`, copy the worktree's changed files over, then `git diff` = djmix19 delta. Do not use the combined 3,689-line unstaged diff as the delta.
   - `sha256sum` + `stat -c%s` + `wc -l` local vs remote must match bit-for-bit.
3. `adb -s 1439ebea install -r /home/hfzhkn/Downloads/bitchord-2.2.3-djmix19-automix-arm64-v8a-oldkey.apk` → `Success`.
4. `dumpsys package com.music.bitchord.loopfix` → `versionCode=61`, `versionName=2.2.3-djmix19`, fresh `lastUpdateTime`; pull `djmix19-proof/{install.log,dumpsys-after-install.txt,apk-sha.txt,media_session-after-install.txt,crash-log.txt}` and telemetry pull(s). **No** uninstall/clear; no play/pause/seek/queue manipulation.

### 6.5 User listening gate (ears only)

1. **`Beautiful` (Bazzi) → `Never Let Go` (LNGSHOT)** — shuffle OFF if possible; automix ON; play from the top; hands off; record internal audio.
2. **`Drip` (ATEEZ) → `UP` (ATEEZ)** — same conditions; this is the regression pair for the 762 freezes, 3 arm deaths, +3.230 s jump, chipmunk roll and low volume.
3. Report: silence gap `<50 ms`? dissolve `≥300 ms`? beat-match + taste? Any chipmunk/roll, tempo drift (`79→81`), early-`UP` stutter, or volume collapse? Send the recording + a fresh telemetry pull.
4. Until that verdict: **no audio claim** for `djmix19`; status is `[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]`.

### 6.6 Do-not list

- Do **not** `adb uninstall` / `pm clear` / change queue / toggle shuffle blindly / send play/pause/seek during a build or proof.
- Do **not** use MCP `box` for `bk-android`; it is an **Instabox** VM — use `instabox` CLI only.
- Do **not** use `sleep` loops for builds; use `instabox jobs wait`.
- Do **not** edit/delete `BITCHORD_HANDOVER_DETAILED.md`, `BITCHORD_HANDOVER_DJMIX13..18.md`, prior APKs/patches/proofs, or this file's predecessors.
- Do **not** store API keys/keystore passwords in docs; the `tanyadf` key is out-of-band only.
- Do **not** touch Signalsmith headers, add non-GPL deps, or implement sliding-window cross-track descriptor correlation (US 8,280,539).
- Do **not** post-handover `setPlaybackSpeed`, `beginEase`, or `holdPhase` restart.
- Do **not** change Compose layout; fixes belong in planner/controller.

---

## 7. File Inventory (key paths)

| Path | Status |
|---|---|
| `/home/hfzhkn/BITCHORD_HANDOVER_TOTAL.md` | **this file** (new; total handover) |
| `/home/hfzhkn/BITCHORD_AUTOMIX_HANDOVER_LIVE.md` | append-only live log; a concise `djmix19` entry is appended after this file |
| `/home/hfzhkn/BITCHORD_HANDOVER_DETAILED.md` | untouched; §2 reproduced verbatim above |
| `/home/hfzhkn/BITCHORD_HANDOVER_DJMIX13.md` … `DJMIX18.md` | untouched |
| `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix18-automix-arm64-v8a-oldkey.apk` | 87,481,971 B, `5f50d0d1…6798c` — **installed** on `1439ebea` |
| `/home/hfzhkn/bitchord-djmix18.patch` | 2,763 lines, 148,390 B, `4f70169e…40196` |
| `/home/hfzhkn/bitchord-djmix18-full.patch` | 8,512 lines, 436,046 B, `189e7daf…be74eb` |
| `/home/hfzhkn/bitchord-device-evidence/djmix18-proof/` | frozen vc60 proof; pull3 6,563 lines `ddd233b6…0349a`; pull2 prefix `3de9a09d…`; 0 crash lines |
| `bk-android:/data/workspace/bitchord-task2/work/BitChord` | HEAD `dc78e0d`, index 6,085 lines, worktree djmix18 + djmix19 (unstaged 3,689 lines), no vc61 APK |
| `/tmp/opencode/vc60/` on `bk-android` | reconstructed vc60 tree used to verify the `file:line` diagnosis; do not treat as a build tree |
| `tanyadf-standby` (`/data/workspace/bitchord-alt/staging/`) | documented standby; **not currently listed** by `instabox`; recreate if needed |

---

*End of total handover. Current shipping truth: phone `vc60` (`2.2.3-djmix18`) with user verdict "not so good but definitely a progress"; `djmix19`/`vc61` is code-complete in the worktree but has one stale shared test, no app-test run, no APK, and no install. The only remaining quality authority is the user's ears on `Beautiful → Never Let Go` and `Drip → UP`.*
