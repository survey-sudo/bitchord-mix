# BitChord DJMIX21 Handover — 2026-11-14 (vc63, BUILT + INSTALLED)

> **Honest state in 5 lines.** `2.2.3-djmix21` (vc63) is **BUILT, SIGNED (`oldkey`), AND INSTALLED ON `1439ebea`** 2026-11-14 ~04:17 local (`adb install -r` → `Success`, `versionCode=63`, `2.2.3-djmix21`). APK `fd267f9045f75ddbc8b88f3e54ce9f18d4d691f8cd20c55659098933b6f1d040`, 87,498,355 B, arm64-v8a only (14 libs), v2 cert `20c66ad6…e3fe4c`, 2,085 tests 0 failures. Key change vs vc62: **structure-aware automix** — vocal-gap budget (no awkward dead zones), buildup-aware blends with flanger ride, outgoing mid-carve + incoming high kept crisp (vocal/instrumental separation). Phone was on vc62 at install (user had installed it manually). **No audio claim:** listening verdict is the user's only. No play/pause/seek/queue was sent this cycle.

---

## 1. Executive state [PROVEN]

| Item | Value |
|---|---|
| Built on | `bk-android` `/data/workspace/bitchord-task2/work/BitChord` via Instabox CLI (autowake 00:03 WIB; 2h cooldown) |
| Version | `2.2.3-djmix21`, **versionCode 63** (`build.gradle.kts:44-45`) |
| APK | `/home/hfzhkn/Downloads/bitchord-2.2.3-djmix21-automix-arm64-v8a-oldkey.apk` — 87,498,355 B, SHA-256 `fd267f9045f75ddbc8b88f3e54ce9f18d4d691f8cd20c55659098933b6f1d040` |
| DEX/native ABI | 14 `lib/arm64-v8a/` entries only |
| Signing | v2 only, 1 signer, cert `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` (`oldkey`, DN `CN=BitChord loop fix`) |
| Installed on | `1439ebea` (wireless `adb-1439ebea-05jQT0._adb-tls-connect._tcp`) — `lastUpdateTime=2026-11-14 04:17:09`, `Success`, crash-log 0 lines, telemetry unchanged post-install (7822 lines) |
| Prior on phone | `2.2.3-djmix20` vc62 (`versionCode=62`, `lastUpdateTime=2026-11-13 21:11:24`) — user had installed vc62 manually before adb came back; vc63 `install -r` over it |

---

## 2. What changed vs vc62

All changes are planner/controller-level; Signalsmith untouched; Compose layout frozen; scalars-only cross-track scoring preserved (US 8,280,539).

### 2.1 Structure-aware engine (`StructureMix.kt`, new)
- Per-track scalars only: trailing/leading no-vocal bars, vocal-seam proximity (A's last sung bar → B's first sung bar), quiet-lead fraction, head buildup detection (`BUILD`/`PRE_CHORUS` or rising INTRO), spotlight check.
- `PhraseConfig`: `VOCAL_GAP_SOFT_BARS=8`, `VOCAL_GAP_HARD_BARS=16`.

### 2.2 Vocal continuity (no awkward dead zones, no mid-vocal amputation)
- `phrasePlanner` veto for long blends {M1, M3, ROLLING, M2, M7, RELAXED, M9} when seam gap > 16 bars ("vocal gap") + quiet-intro waste guard (>50% quiet lead-in with no event inside the overlap).
- Score: gap cost above soft budget (capped at 0.05 — tie-breaker only, so A's outro spotlight is never cut early to chase a shorter overlap), +0.10 bonus for 1–2-bar vocal-handoff seams (A's voice ends, B's starts), +buildup alignment / missed-buildup / quiet-landing costs.
- **Per-side evidence gating:** a track that never sings contributes no vocal gap (fixes 2 classic `PhrasePlannerTest` regressions that the naive counter caused).
- `placeOnStructure`: mid-line exit now extends to line/section end only within +6s, else moves the exit **before** the line (previously unbounded extension).

### 2.3 Buildup-aware blends + flanger
- `SectionKind` gains `BUILD`/`DROP` (appended; existing ordinals unshifted); head buildup (`BUILD`/`PRE_CHORUS`, or rising INTRO) is always its own `EntryKind.BUILD` cue.
- `buildupFits` accepts BUILD/DROP as lifts; `annotate` adds a B-side check (cue inside B's rising INTRO/BUILD/PRE_CHORUS ⇒ `buildup=true` ⇒ **FLANGER ride** at wet 0.42 / 8-beat LFO, unchanged wiring).

### 2.4 Vocal/instrumental separation (vocal crisp rule)
Audit finding: the classic path already kept incoming filters open; the muffle risk was **loop-renderer lanes**. Fixed in `TransitionMoves` (conditional on `entry.leadInVocal >= 0.2`): M1 outgoing mid carves −3→−6 dB while B sings, incoming high never below −1 dB, M7/ROLLING incoming mid −8 → −1.5 dB. Instrumental lanes byte-identical to vc61.

### 2.5 Also in this build (carried from vc62, for the record)
Analyse-ahead (next/next+1 immediately), decline-to-mix gate (>8% folded AND (Camelot >2 OR dual-vocal ≥0.62) → `EQUAL_POWER`), loop echo past-EOF tail, deterministic flanger on low-vocal beatmixes.

---

## 3. Tests

| Suite | Result |
|---|---|
| `:shared:jvmTest` | **307 tests, 0 failures** |
| `:shared:testDebugUnitTest` | **249 tests, 0 failures** |
| `:shared:testReleaseUnitTest` | **249 tests, 0 failures** |
| `:shared:allTests` | **805 tests, 0 failures** |
| `:app:testProdDebugUnitTest` | **1,280 tests, 0 failures, 1 pre-existing skipped** (`GeniusTest`) |
| **Total** | **2,085 tests, 0 failures** (vc62: 2,043) (+42 = `Djmix21RegressionTest` 14×3) |

- `Djmix21RegressionTest`: 14 tests — vocal-gap budget, mid-line amputation, buildup alignment + classic flanger, quiet-intro guard, crisp lanes, vocal-seam preference, clash still guarded, Beautiful 8-bar `DJ_BLEND`, Calvin 124 `DJ_BLEND`, Technologic→Guess, decline gate, BUILD/DROP lift.

---

## 4. Artifacts

| Artifact | Lines / Bytes | SHA-256 |
|---|---|---|
| `bitchord-djmix21.patch` (vc62→vc63 delta, 9 files) | 977 / 53,558 | `270856f9121fa58c426cc89848029a98d8b3292a7698619e5eb7b8df6a944fce` |
| `bitchord-djmix21-full.patch` (from `dc78e0d`, 60 files) | 11,330 / 583,275 | `2bf989d73440129e75a82587226f163adece7c88d0b4c3d25366a7788931df26` |

Lineage: `HEAD=dc78e0d`, `git diff --cached` **6085** before and after (verified). `git add -N` only the new `Djmix21*` files / `StructureMix.kt`.

---

## 5. User listening gate (ears only, no audio claimed yet)

Order:
1. **Beautiful (Bazzi) → Never Let Go (LNGSHOT)** — 8 bars, no "too early", outro plays out.
2. **Drip (ATEEZ) → UP (ATEEZ)** — no chipmunk, no stutter, no freeze hang.
3. **This Is What You Came For (Calvin Harris ft. Rihanna) → One Kiss (Calvin Harris x Dua Lipa)** — buildup conjoining + flanger showcase; incoming vocal must be *crisp from the first line, not muffled*.
4. **Technologic (Daft Punk) → Guess (Charli XCX)** — no decline, no vocal mush.

Queue them if absent (agent must NOT queue/manipulate). Record internal audio, report per-pair: silence gap <50 ms? dissolve ≥300 ms? buildup/drop aligned? incoming vocal crisp? Any dead zone >8 bars or mid-vocal cut?

Status: **[AWAITING USER INTERNAL-AUDIO RECORDING & LISTENING VERDICT]**.

---

## 6. Do-not list
- No `adb uninstall` / `pm clear` / queue edits; `install -r` only.
- No MCP `box` for `bk-android`; Instabox CLI only.
- No `sleep` loops for builds (use `instabox jobs wait`).
- Never edit/delete prior handovers/APKs/patches/proofs.
- Never touch Signalsmith headers; scalars-only cross-track scoring always.

*End of handover. vc63 is live on the phone — the verdict belongs to your ears.*
