# BitChord Automix — djmix19 (vc61) Handover

> HONEST BOX (no audio claim yet):
> vc61 (2.2.3-djmix19) is INSTALLED on peridot 1439ebea via `install -r Success` — no play, no seek, no uninstall/clear sent.
> Host gates are green: 1997 tests, 0 failures/errors, 1 pre-existing skipped (GeniusTest); `:app:assembleProdRelease` BUILD SUCCESSFUL.
> NO audio verdict for vc61 yet — no internal-audio recording/listening has been reported.
> Status: **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**
> Next action is user-only (listening gate below); do not start phone playback from host until verdict is in.

## Live state

| Item | Value |
|---|---|
| Phone | wireless serial `adb-1439ebea-05jQT0._adb-tls-connect._tcp` (peridot `1439ebea`) |
| Installed package | `com.music.bitchord.loopfix`, versionCode `61`, versionName `2.2.3-djmix19` |
| Install times | firstInstallTime `2026-11-09 18:39:21`, lastUpdateTime `2026-11-13 18:15:39` |
| Install method | `adb install -r` → `Success` (no uninstall/clear, no play/seek sent) |
| APK | `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix19-automix-arm64-v8a-oldkey.apk`, 87481971 B, sha256 `84aea9b63777d6f1a35d829bd094152f3bed74870e7258146d4836883488772b` |
| APK shape | 14 lib entries, ONLY `lib/arm64-v8a/`; apksigner Verifies v2 only, 1 signer, cert `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`; aapt `com.music.bitchord.loopfix vc61` |
| Source baseline | bk-android HEAD `dc78e0d`, `git diff --cached` 6085 lines (before + after) |
| Delta patch | `/home/hfzhkn/bitchord-djmix19.patch`, 1532 lines, 79195 B, sha `b2b93664abe587f69901b7349e6f196e8e6969a3ef9e995ff425216ee305494a` (21 files, vc60→vc61 delta) |
| Full patch | `/home/hfzhkn/bitchord-djmix19-full.patch`, 9693 lines, 492698 B, sha `779ac97d4f5660adeafd83ba29fde64467463745a1cfccfa237ba2dc488b7a61` (55 files from `dc78e0d`) |
| Proof dir | `/home/hfzhkn/bitchord-device-evidence/djmix19-proof/` (see inventory) |
| Audio status | **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]** — NO audio claim for vc61 yet |

## What changed in vc61 vs vc60

### 1. DjColour / roll anchor
- `DjColour` frozen: rollAnchor `1.0x`, rollLen floor `beatFrames/2`.
- `DjMix` tempoGap: no `LOOP_ROLL` (removes chipmunk/tempo artifact path).

### 2. LoopMix region single-source
- `LoopMix.regionBounds` is the single source for `outTo` (kills duplicate/bypass region math).

### 3. LoopMixSession hold-aware start + align
- Hold-aware start.
- `ALIGNED_SECONDS 0.012`, `LOOP_ALIGN_ROUNDS 5`.
- `CrossfadeController`: `finally` clears `loopAlignJob` (no leaked align job across transitions).

### 4. Volume / loudness / moves
- `MixGain 0.94`, `pow 1.20`, `FILTER_FLOOR_HZ 1500`, shapes `1.15/0.65`.
- `RegionalLoudness MIN_DB -3.5`.
- `TransitionMoves` M1/M9 gains retuned.

### 5. Idle-stall force-exit + finish hygiene
- Idle-stall force-exit: prepare `1.5s`, force `2.0s`, outExhausted immediate.
- Freeze dedup.
- `finish()` resets `idleStall`/handover fields (no stale stall state into next track).

### 6. Beautiful → Never Let Go (timing/overlap/outro)
- `TransitionPlanner`: removed `+8 beats` past `incomingDropTime` and `outgoingArrangementOverlap`.
- `preferEarlier=false` → 8 bars not 9 bars for this pair.
- `DjMix.ensureOverlap`: short-intro floor.
- `TransitionPolicy`: `eof_holdback <=1bar/2.5s` only if no quiet tail; sung outro `>=0.15` not truncated.
- `CuePoints`: entry `startSec` bar0 + `CONTENT_END` fallback.
- `PhrasePlanner`: sung `CONTENT_END` veto exemption.
- `TransitionMoves.inStartSec`: lead-in alignment.
- Tests: `Djmix14` stale fixed; `AutomixFreeze` eof `198.0`; `Djmix19Regression` 8 tests incl Beautiful window; `Djmix19AppTest` Fix5.

## Test / build table

| Suite | Result |
|---|---|
| `:shared:allTests` | 721 (279 jvm + 221 debug + 221 release) |
| `:app:testProdDebugUnitTest` | 1276 (`Djmix19AppTest` 5/5) |
| Grand total | 1997 tests, 0 failures, 0 errors, 1 pre-existing skipped (`GeniusTest`) |
| Release build | `:app:assembleProdRelease` BUILD SUCCESSFUL, 44m54s, DONE:0 |

## Patch / APK ledger (verified on disk)

| Artifact | Lines / bytes | SHA-256 |
|---|---|---|
| `bitchord-djmix19.patch` | 1532 lines / 79195 B | `b2b93664abe587f69901b7349e6f196e8e6969a3ef9e995ff425216ee305494a` |
| `bitchord-djmix19-full.patch` | 9693 lines / 492698 B | `779ac97d4f5660adeafd83ba29fde64467463745a1cfccfa237ba2dc488b7a61` |
| `bitchord-2.2.3-djmix19-automix-arm64-v8a-oldkey.apk` | 87481971 B | `84aea9b63777d6f1a35d829bd094152f3bed74870e7258146d4836883488772b` |

APK detail: apksigner Verifies v2 only, 1 signer cert `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`; aapt package `com.music.bitchord.loopfix vc61`.

## Proof inventory — `djmix19-proof/`

| File | Size / lines | SHA-256 |
|---|---|---|
| `telemetry-pull1-preinstall.jsonl` | 6926 lines / 1175899 B | `15272be11f859ff6d89103b7e8d97b93d2b609200c7de7add174361ba62500ff` |
| `telemetry-pull2-postinstall.jsonl` | 6987 lines / 1187531 B | `5387d2e647cf82bf0fb140f255fe9e15c556bb164a6bdc0d865e325e1faa2028` |
| `install.log` | `Success` | — |
| `dumpsys-after-install` | vc61 | — |
| `crash-log.txt` | 0 lines | — |
| `media_session-after-install.txt` | 69 lines | — |

Verified: pull1 (6926 lines) is a prefix of pull2 (`head -n 6926 pull2 | cmp pull1` → MATCH).

## Exact next step — user listening gate (user-only)

**(a) Beautiful (Bazzi) → Never Let Go (LNGSHOT):**
Shuffle OFF if possible, automix ON, play from top, hands off, internal audio record.
Check: 8 Bars not 9; no "too early" drop; outro plays to basically empty; gap `<50ms`; dissolve `>=300ms`.

**(b) Drip → UP (ATEEZ) regression:**
Check chipmunk / tempo / volume / early-UP stutter / 762 freeze / arm death.
Report verdict + fresh telemetry pull.

## Do-not list (short)

- Do NOT edit/delete prior handovers (`BITCHORD_HANDOVER_TOTAL.md`, `BITCHORD_HANDOVER_DETAILED.md`, `BITCHORD_HANDOVER_DJMIX13..18.md`), prior APKs/patches, or djmix11-18 proof dirs.
- Do NOT touch bk-android source or phone (no `adb install`/`play`/`seek`) until user verdict lands.
- Do NOT claim audio fixed for vc61 — status stays **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**.
