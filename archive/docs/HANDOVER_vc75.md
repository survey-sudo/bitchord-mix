# HANDOVER — BitChord Mix: vc74 → vc75

**Written:** 2026-11-16
**Source session:** `ses_e2199b47effezQcayCs7fCDjzh` — "Delegating Bitchord vc67 to gemini-4-hydrogen"
**Session state at handover:** idle (outcome `succeeded`) at ~09:25, **waiting on a user decision**, not crashed.
**Current installed build:** `vc74` / `2.2.3-djmix32`
**Next build:** `vc75` / `2.2.3-djmix33` — designed, **not applied, not built**.

---

## 1. TL;DR

- The audio diagnosis is done: **micro-stutters are caused by the audio render thread never being raised to audio priority, while analysis runs at default priority.** Fix is three lines of intent plus a version bump.
- `vc75` was never applied. The orchestrator lost its `edit` tool, then lost code transfer to the build box, and stopped to ask the user how to proceed.
- **The single blocker is getting the vc74 source (`dfc90d0`) onto a machine that can build.** The private GitHub repo exists and is current; the build box has no GitHub credentials.
- Everything after that is queued: `vc75` stutter fix → `vc76` analysis speed → the four-pair listening gate → infra/backlog.

---

## 2. Verified current state

| Item | State |
|---|---|
| Host repo | `/home/hfzhkn/Bitchord/work/bitchord-mix` |
| Host HEAD | `dfc90d0` — "docs: record CI parity vs vc74, step timings, and Last.fm credential gap" |
| Host working tree | **clean** |
| Host version | `versionCode = 74`, `versionName = "2.2.3-djmix32"` (`build.gradle.kts:44-45`) |
| Host history | 8 commits; root `dc78e0d`, then vc73/74 work in `6aaf9cc` |
| GitHub | `github.com/survey-sudo/bitchord-mix` — **private**, `master = dfc90d0` |
| Build box | box `main` (`cb-fcfcd7a79078`), `/workspace/BitChord` |
| Box HEAD | `dc78e0d` (one-commit snapshot) |
| Box working tree | **dirty, 66 files**, version `67` / `2.2.3-djmix25` |
| Box scratch | `/workspace/vc75` — empty `git init`, nothing copied |
| Box toolchain | Java 21.0.13, SDK, NDK `27.2.12479018`, Gradle cache 2.3 G, 28 GB free — **ready** |
| Box network | OK (github.com and api.github.com both HTTP 200) |
| Box GitHub auth | **none** — private repo cannot be fetched |
| Box keystore | `bitchord-loopfix.jks`, SHA-256 `eaf46cc5eb1de1d513a26105396893c22eed6dd4e8e994a714d6c2fea60cf4a7` — **matches host backup exactly** |
| Host build ability | **no** — no Java, no Android SDK, 22 GB free (inspection only) |
| Phone | wireless ADB device `1439ebea`; `vc74` installed (last seen connected earlier; may need WiFi ADB re-enabled) |
| Verified vc74 APK | `67,431,730 B`, SHA-256 `c3215537daa1ea7a01322db27b3fac735dfc75f356a2b2094ec1f16ec9deae14` |

### Why the session stopped

Five subagent launches died on provider quota (HTTP 429, ~8 h reset). The orchestrator then worked directly with `read`, `execute`, and `box` tools, but:

- the host has the code and the `gh` token, but cannot build;
- the box can build and holds the correct keystore, but has no credentials to fetch the private repo;
- the box file tools cannot copy files out of the host, and `instabox exec --stdin` requires a shell the orchestrator no longer had.

It ended by asking the user to choose a transfer route (options A–D below).

---

## 3. The blocker — get vc74 source onto a builder

### Option A — token on the box for one fetch
Paste a GitHub PAT into the box (or the chat), use:

```bash
git -c credential.helper= \
    -c http.extraheader="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$TOKEN" | base64 -w0)" \
    clone https://github.com/survey-sudo/bitchord-mix.git /workspace/vc75
```

Risks: token lands in the chat log and on a remote box; must be revoked/rotated afterwards. The token has `repo` + `workflow` scope, so it can read/write every private repo.

### Option B — move the source through another channel
- Build a `git bundle` on the host, upload to a temporary URL / cloud bucket, `curl` it on the box.
- No token ever touches the box.
- This is the credential-free equivalent of A, but needs a reachable transfer service.

### Option C — wait for quota (~7 h from handover)
Then relaunch the worker on the normal, credential-free path. Nothing is lost; the ticket is fully written down in this document.

### Option D — patch locally, push, let CI build (recommended)
- Apply the `vc75` patch in the host repo (section 4).
- User commits and pushes. CI (`.github/workflows/android-ci.yml`) builds it; proven green in `17m41s`.
- **Caveat:** CI output is **unsigned** until the four signing secrets are added, so it cannot install over `vc74`. Either add the secrets first (section 7), or build on a box that has the keystore.
- Cleanest: no credential handling on the box, and the host remains the source of truth.

> Recommendation: **D**, with the signing secrets added in the same step if the build must be installable today.

---

## 4. Immediate task — `vc75` stutter fix

### Scope: one variable only

The original ticket had two fixes. The second was **investigated and rejected** — see section 8. `vc75` is the thread-priority change only.

### Edit 1 — raise the audio render thread

File:
`app/src/main/java/com/music/bitchord/playback/audio/PrecisionAudioSink.kt`

- Add `import android.os.Process`.
- Add a field near `processCounter`:

```kotlin
/**
 * Media3 creates and owns the thread that calls handleBuffer(), so its
 * priority cannot be set from outside. Raise it from inside the first
 * buffer instead: one boolean test in steady state. Every operation on
 * this thread is audio (decode, DSP, PCM encode, loop render, delegate
 * write), so URGENT_AUDIO is the correct class. Some OEMs refuse the
 * call; a denial must not kill playback.
 */
private var audioPrioritySet = false
```

- At the **very top** of `override fun handleBuffer(...)`, before the `isPrecisionActive` early return (the delegate path is still audio):

```kotlin
if (!audioPrioritySet) {
    audioPrioritySet = true
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
}
```

Design constraints:
- Set it **once**, not per buffer.
- Do **not** let a `SecurityException`/OEM refusal crash the render path.
- If code review shows the thread is shared with substantial non-audio work, use `THREAD_PRIORITY_AUDIO` instead. Previous review concluded the thread is audio-only.

### Edit 2 — demote analysis below audio

File:
`app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt`

Two sites currently restore/choose `THREAD_PRIORITY_DEFAULT` for the normal (non-EFFICIENT) mode:

- `:559` — the priority restore at the end of `playbackFirst()`
- `:681` — the executor task start

Change the `else` branch at both sites to `Process.THREAD_PRIORITY_BACKGROUND`, i.e. analysis **always** runs below audio, not only in `EFFICIENT` mode. Update the surrounding comments so they no longer describe EFFICIENT as the only yielding mode.

Rationale: `EFFICIENT` already used `BACKGROUND` and was never reported broken. Analysis tolerates latency; audio does not.

### Edit 3 — version bump

File: `build.gradle.kts:44-45`

```kotlin
extra["bitchordVersionCode"] = 75
extra["bitchordVersionName"] = "2.2.3-djmix33"
```

### Gates

Known baselines: **443** (`:shared:jvmTest`) and **1,355** (`:app:testProdDebugUnitTest`). Zero new failures; no test weakened.

```bash
./gradlew :shared:jvmTest --stacktrace
./gradlew :app:testProdDebugUnitTest --stacktrace
```

### Build

```bash
./gradlew :app:assembleProdRelease -Pbitchord.bundleWear=false --stacktrace
```

- Expected duration on the box: ~30 min; on CI: ~14–18 min.
- Signing requires `keystore.properties` + `bitchord-loopfix.jks` in the repo root, or the four CI secrets.
- Audit the APK: one `libonnxruntime.so` at `34,208,672 B`, zero QNN/HTP libs, all seven `libbitchord_*`, version `75`.

### Install and verify

```bash
adb install -r app/build/outputs/apk/prod/release/app-prod-release.apk
adb shell dumpsys package com.music.bitchord | grep -E 'versionCode|versionName|lastUpdateTime'
adb shell monkey -p com.music.bitchord -c android.intent.category.LAUNCHER 1
```

Then check, on device:

- [ ] Micro-stutters before / during / after a mix
- [ ] No new crashes in logcat
- [ ] Analysis still completes (priority demotion must not starve it)
- [ ] Existing 443 / 1,355 gates still hold

If stutters persist, the next suspect is the **250 ms main-thread tick taking Media3's `SimpleCache` monitor lock 4×/s** (`CrossfadeController.kt` ~1397-1418 → `AudioCache.kt` ~2130-2179). That was deliberately left out of `vc75`.

---

## 5. Next build — `vc76` analysis speed

User complaint: **"analysing song took forever."**

What is already true in `vc74` (do not re-diagnose):

- Analysis is **lazy**: current / next / next+1 only, re-queued every 250 ms.
- Results **do persist** per track in `filesDir/smart_analysis`, schema 5.
- Warm measurement on the phone: `mel 85 ms`, `infer 1,887 ms`, `vocal mask 958 frames`, `structure exceeded 0`, crashes 0.

What still makes it slow:

- ONNX sessions are **closed when the analysis queue drains** and reopened per track, including `ALL_OPT` graph optimisation on ~32 MB of models.
- `setCPUArenaAllocator(false)` and `setMemoryPatternOptimization(false)` are set, so every run allocates fresh.
- Each window computes its **own STFT** for both models.
- Up to **5 stereo window decodes** (2 head + 3 tail) and **10 ONNX calls** per track, where 2 would do.
- `yieldToPlayback()` pauses analysis for **10 seconds** at a time.
- The **250 ms main-thread tick** takes Media3's cache lock 4×/s.

Ranked fixes (win per line changed):

1. **Keep ONNX sessions alive** for the process lifetime, or release on a long idle timer (60 s+), not per drain. Deletes the reload + `ALL_OPT` cost.
2. **Re-enable arena + memory-pattern optimisation.** Two booleans.
3. **Share one STFT per window between both models.** Halves the DSP work.
4. **Drop or lazify the window count** — analyse head only until the transition actually needs the tail.
5. **Shorten `yieldToPlayback`** once the render thread is genuinely protected.
6. **Move the 250 ms tick off `Dispatchers.Main`** and stop taking the cache lock 4×/s.

Do **not** bundle these into `vc75`; one variable at a time so listening results are attributable.

---

## 6. P0 — the listening gate (still unproven)

Nothing about mix quality is proven. `vc74` is currently unpleasant to listen to because of stutters, so judge mixes on `vc75` (or later), not `vc74`.

Four pairs:

1. `Beautiful` → `Never Let Go`
2. `Drip` → `UP`
3. `This Is What You Came For` → `One Kiss`
4. `Technologic` → `Guess`

Additional steady pair: `Keyveatz`, `75.949 BPM`.

Per pair, check:

- [ ] Song A reaches near its **real end** — not cut early
- [ ] Song B enters on **music**, not its silent intro
- [ ] Both decks **beatmatch** — no flam, correct phase
- [ ] **No muffle/demuffle** on vocals; instrumental/bass swap is acceptable
- [ ] Clean **≤200 Hz bass handover**

### Tempo-policy decision (after listening)

`AUDIBLE_MAX_RATE = 1.09` (`shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt:90`).

The last observed pair was `134.83 → 148.61 BPM` = ratio `1.1022`, correctly declined to `DJ_FILTER` by the current ceiling. Library-wide, that ceiling lets only **45.4 %** of analysed pairs beatmatch; `1.12` would raise it to **54.3 %**. Real DJ gear allows ±8 % (vinyl) to ±16 % (CDJ).

Do not change it until the in-range pairs have been heard, so a real regression is not masked by a wider window.

### Why `vc73`/`vc74` may already have fixed the original complaints (unverified)

Commit `6aaf9cc` ("djmix31/32") contains the code fixes for:

- **Song A cut early** — outro-aware `LoopMix.firstClean`, `max(0.78*dur, musicEnd-30)` floor, phrase scoring gated on cuts, capped exit span.
- **Song B silent intro** — `CuePoints` now consults `SectionFn.SILENCE` / `audibleStartTime`; loop and planner paths skip leading silence.
- **No beatmatch** — parallel OUT/IN decode, real incoming rate in alternatives, corrected loop alignment.
- **Muffle** — windowed `umxhq` stem rides on by default (≥6 GB / memoryClass ≥256), dual-stem cosine rides, zero-alloc 1,500 Hz vocal dip, clean ≤200 Hz bass swap.

These are **code-complete, ear-unverified**. If a pair still fails, treat it as a new regression with telemetry, not as "never fixed".

---

## 7. Pending infrastructure / decisions

### 7.1 Signing secrets for CI — user decision pending

CI is green but emits `app-release-unsigned.apk`. Unsigned APKs cannot be installed at all; differently-signed APKs cannot replace the installed app (`adb install -r` fails on signature mismatch; uninstall is off the table).

Add four repository secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Keystore: `/home/hfzhkn/.bitchord-secrets/bitchord-loopfix.jks` (SHA-256 above). `keystore.properties` values: `storePassword=<redacted>`, `keyAlias=bitchord`, `keyPassword=<redacted>`.

The workflow already handles absence gracefully; no workflow change is needed once the secrets exist. This is the user's security call and was not taken unilaterally.

### 7.2 Last.fm credentials absent from the installed build

`buildConfigField("String", "LASTFM_API_KEY", ...)` reads `local.properties`, falling back to the environment. The installed `vc74` has both fields **empty** — proven by dex parity with the CI build, which has no `local.properties`. Any Last.fm-backed feature is inert on-device until keys are supplied to a build.

### 7.3 `compileSdk` 36 vs InnerTubeX's declared 37

`innertubex-android` declares `minCompileSdk = 37`; `app`, `shared`, `sharedUi` disable their `*AarMetadata` tasks. The platform (`android-37`) is now installed on the build box and installable in CI, so the proper fix is available:

1. Bump `compileSdk` to 37 in all three modules.
2. Delete the `AarMetadata` disables.
3. Re-verify on device.

Deferred because it changes the build target while the current APK is awaiting a listening test. The disables also require each library script to register `prepareAarMetadataCheckDirs`, or a clean checkout fails Gradle input validation.

### 7.4 Box / CI sync

- `bk-android` was the original build source of truth; it sits at `4e19a2a`, at least several commits behind `origin/master = dfc90d0`. When reachable: `git pull --ff-only origin master`.
- `grok-box` is **offline** on the tailnet (`tx 780 rx 0`). Bootstrap kit is ready at `docs/tools/grokbox/` (`bootstrap.sh --check`, `migrate-repo.md`, `verify-build.md`). Migration counts as done only when the same source produces the same APK bytes.
- The "main" box (`cb-fcfcd7a79078`) is the one with `/workspace/BitChord`, the keystore, and the working toolchain. It is currently the only ready builder.
- Minor: `survey-sudo` is fully private (9/9 repos), but `syabkahzh` and `imamuhf` have 12 public repos between them. The user asked "make sure all private" earlier; the question about those unrelated accounts was never answered.

---

## 8. Already done / closed — do not redo or re-litigate

| Item | Status |
|---|---|
| CI workflow | **Proven green** — run `43767187882`; unit tests `8m48s`, release APK `17m41s`, parallel run `17m45s`; `assembleProdRelease` alone `14m21s` |
| CI payload parity | **1,584 / 1,592 entries SHA-256 identical** to the verified box `vc74` APK; all three `classes*.dex` byte-identical. The 8 differences are `version-control-info.textproto` and 20 bytes of `.note.gnu.build-id` in each of the seven native libs — expected, not a defect |
| CI native audit | Exactly one `libonnxruntime.so` at `34,208,672 B` (hash-identical), zero QNN/HTP libs, seven `libbitchord_*`, version 74 |
| Private GitHub repo | Created, all 9 `survey-sudo` repos private, README + AutoMix section pushed |
| `vc74` build/install | Built, APK audited, installed, launched COLD in 581 ms, zero crash lines |
| `vc74` analysis | Measured fixed: `mel 85 ms`, `infer 1,887 ms` (was 12,304), vocal mask present, `structure exceeded` 0 |
| Loudness "de-boxing" | **Debunked, dropped.** `LoudnessProcessor.targetGain()` returns primitive `Float`; only two tiny objects per block at ~12 blocks/sec. Not a GC drip, not the stutter. Do not ship it as a fix |
| "Whole-library scan / no cache" theory | **Wrong.** Analysis is lazy and persists per track. The one-time re-analysis was the schema 4→5 bump |
| NPU | **Deliberately off**, not a flag flip. See backlog |
| Demucs / `htdemucs` | Rejected on licence + ~2.2 GB RAM. Only bundled `umxhq` is used |

---

## 9. Product backlog (lower priority, in rough order)

### P2 — NPU (why it is off, and what it would take)

`NpuSupport.QNN_ENABLED = false`. It has **never** executed an inference on the device. Two independent defects:

1. QAIRT `QNN_DEVICE_ERROR_INVALID_CONFIG` before partitioning. Bundled Hexagon **V73 and V75** because `SM8635` could not be pinned from QAIRT 2.51; likely fix is an explicit `htp_arch` in `NpuSupport.qnnProviderOptions`.
2. The reduced ORT build's `npu-addon/ops.config` omits optimizer-introduced ops (`MatMulIntegerToFloat`, `DynamicQuantizeMatMul`), so models fail on **both** backends (this is why `vc69` had no working analysis).

Re-enable order:

1. Add optimizer-introduced ops to `ops.config`.
2. Rebuild ORT 1.31.0 with QNN EP (`npu-addon/build-ort.sh`) — needs the 2.8 GB QAIRT SDK subset + ORT source.
3. Re-add `npu-addon/prebuilt/jniLibs` as a jniLibs source set with `pickFirsts`.
4. Prove one inference via `NpuSelfTest`.
5. Re-bundle ~100 MB of QAIRT libs and re-check APK size.

Cost/benefit: models are small (22 MB + 8.6 MB), analysis is one-time per track and ~2 s warm. The NPU buys seconds nobody sees, in exchange for 100 MB and a vendor stack that has never worked. **Do it after the audio is right.**

### P3 — features not built

| Rung / item | Status |
|---|---|
| Rung 0 `GAPLESS` | implemented |
| Rung 1 `LOOP_MIX` | implemented (flagship) |
| Rung 2 `OUTRO_LOOP_DROP` | **not implemented** (0 files) — asymmetric phrase extension / vocal-pickup drop, the iOS 27 signature move |
| Rung 3 `BEAT_BLEND` | covered by `DJ_BLEND` + beatmatch |
| Rung 4 `ECHO_HANDOFF` | **partial** — `ECHO_RIDE` exists; beat-synced echo-out for tempo jumps is not a real template |
| Rung 5 `SMART_CROSSFADE` | covered by `EQUAL_POWER` / `GAPLESS` |

Existing: `TransitionStyle` = `EQUAL_POWER, GAPLESS, DJ_BLEND, DJ_FILTER, MUFFLE, DROP_IN, BEATLESS_BRIDGE`; filter variants = `SWEEP, BASS_HANDOFF, ECHO_RIDE, DECLINE`; `MixTemplate` = `M1–M11, ROLLING, CUT_ON_ONE, ENERGY_LIFT, RELAXED, VOCAL_OVER_OUTRO`.

Other debt:

- **Native DSP** — per-sample filter coefficient smoothing (the 64-frame zipper), native flanger, policy-a curve, isolator lanes live, stems dry-run harness.
- **Dual-ABI + emulator gates** — `abiFilters` is `arm64-v8a` only; no `x86_64`, no emulator runtime verification.
- **Key accuracy** — `Bubble Tea` `4B`, `Crescendo` `10A` vs `10B` still unconfirmed by ears or telemetry.
- **Last.fm** — see 7.2.

---

## 10. Ordered checklist for the next agent

- [ ] 1. Get user's decision on transfer route (A/B/C/D). Default recommendation: **D**.
- [ ] 2. Get `dfc90d0` onto a builder with the loopfix keystore (or add the four CI signing secrets).
- [ ] 3. Apply the three `vc75` edits + version bump in section 4.
- [ ] 4. Run `:shared:jvmTest` and `:app:testProdDebugUnitTest`; hold 443 / 1,355.
- [ ] 5. Build `:app:assembleProdRelease -Pbitchord.bundleWear=false`; audit APK.
- [ ] 6. Install over `vc74` with `-r`; launch; check logcat.
- [ ] 7. Have the user listen for micro-stutters pre/during/post mix.
- [ ] 8. If stutters remain, attack the 250 ms main-thread tick / cache lock.
- [ ] 9. Build `vc76` with the analysis-speed fixes (one variable group at a time).
- [ ] 10. Run the four-pair listening gate on the fixed build; record results against section 6.
- [ ] 11. Decide `AUDIBLE_MAX_RATE` only after the in-range pairs are heard.
- [ ] 12. Clear infra decisions: keystore secrets, Last.fm keys, `compileSdk` 37, box sync.
- [ ] 13. Backlog: `OUTRO_LOOP_DROP`, `ECHO_HANDOFF`, DSP debt, ABI/emulator, key accuracy, NPU.

---

## 11. Quick reference

```text
Host repo:        /home/hfzhkn/Bitchord/work/bitchord-mix
Host HEAD:        dfc90d0 (clean) — vc74 / 2.2.3-djmix32
GitHub:           github.com/survey-sudo/bitchord-mix (private)
Backlog doc:      docs/REMAINING_WORK.md (predates vc75/vc76)
Parity doc:       docs/ci-parity-vc74.md
Keystore:         /home/hfzhkn/.bitchord-secrets/bitchord-loopfix.jks
Keystore SHA256:  eaf46cc5eb1de1d513a26105396893c22eed6dd4e8e994a714d6c2fea60cf4a7
vc74 APK SHA256:  c3215537daa1ea7a01322db27b3fac735dfc75f356a2b2094ec1f16ec9deae14
Build box:        box "main" cb-fcfcd7a79078, /workspace/BitChord (vc67, dirty)
Empty scratch:    /workspace/vc75
CI run (green):   43767187882
Test gates:       443 / 1,355
Tempo ceiling:    AUDIBLE_MAX_RATE = 1.09 (TransitionPolicy.kt:90)
```

---

*This handover was reconstructed from the session transcript, the host git tree, and a live check of the build box. Claims are marked measured, verified, or unverified; nothing unverified is presented as passed.*
