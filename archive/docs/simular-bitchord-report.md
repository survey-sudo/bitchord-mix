# Simular × BitChord Report

Full run report | Saturday, 14 November 2026 | Asia/Jakarta (WIB)

How a question about an MCP server turned, in one morning, into a second Android build box — and what the Chrome investigation, the InstaBox cooldown autopsy, and the still-unfinished vc65 gate mean for the next build.

> **Bottom line:** Simular Box `main` (cb-fcfcd7a79078) is connected, provisioned as an Android builder, stripped of Chrome, fitted with moli 1.1.24, and hibernated ready for the mirror. It solves the InstaBox cooldown problem. It does *not* solve the RAM problem — both builders have about 8 GB. Everything in BitChord still gates on shipping vc65 (2.2.3-djmix23) and passing a four-pair listening check. No vc66 code was written, on purpose.

## Status snapshot — as last verified

| Item | State | Note |
|---|---|---|
| Simular MCP | **Connected and verified** | 36 tools listed; personal-key route; folio account only |
| Simular Box main | **Hibernated, provisioned** | Image sbxdesk:v19 (v22 available, left alone); 32 GB disk free |
| Credits (Simular) | **3,414** | Same before and after provisioning — no observed charge |
| InstaBox bk-android | **In cooldown until 10:13:56 WIB** | Suspended 08:03:55 WIB; ~130-min lock, 3rd-step bucket |
| InstaBox df | Also in cooldown | Until 10:21:32 WIB on the same morning |
| Phone 1439ebea | On vc64 (2.2.3-djmix22) | Per forwarded report; not independently verified here |
| vc65 / djmix23 | Claimed code-complete | **Unverified** — worktree was asleep inside bk-android |
| vc66 code | **Held** | Explicit decision: no code until after the mirror |
| Chrome on Simular | **Uninstalled** | Replaced by moli 1.1.24; browser tools expected broken |

**How to read this report:** facts marked **verified live** were checked against a live source during the run. Facts marked **reported** come from the forwarded AutoMix report and are reproduced as claims, not as confirmed findings — the distinction matters, and Section 4 keeps them separate.

- **Accounts:** Simular folio account (Box main); second alternate-account Box is a separate account and does not appear under this key
- **Endpoint:** https://mcp.box.simular.ai/mcp
- **Builders compared:** InstaBox bk-android (8 GB) vs Simular main (7.7 GiB, 4 CPU, 40 GB)
- **Next hard time:** 10:14 WIB — mirror window when bk-android unlocks

## 1. The morning in order

Nothing here was planned as one job. Each step created the next: connecting the MCP made the provisioning possible, the provisioning surfaced the memory number, and the memory number turned out to be Chrome.

| Time (WIB) | Event |
|---|---|
| ~08:16 | Question: do you know Simular MCP? Answer: yes — remote MCP at mcp.box.simular.ai, Box folio exists, but MCP itself not yet connected. |
| 08:17–08:20 | Instruction to connect it. OAuth attempt rejected by Simular (see Section 2). |
| 08:40–08:42 | Two secure-vault saves fail with the same generic error. Screenshots show the Spanish error "Se produjo un problema. Vuelve a intentarlo." |
| ~08:52 | New connector slug (`custom.simular-box-key`) succeeds. Verified with real calls: 36 tools, box_list shows only folio main, hibernated. |
| ~08:46–08:48* | Forwarded report on bk-android suspensions + full AutoMix todolist arrives and is checked live (Sections 3–4). *Interleaved in the thread; the verification reply follows it. |
| ~09:08 | Decision: "Yes, provision Simular now — hold vc66 code until the mirror." |
| 09:08–09:21 | Worker provisions the box: JDK 21, SDK 36, NDK 27, CMake 3.22. Verified end to end, box re-hibernated. |
| ~09:22 | Challenge to the 4.8 GiB idle-desktop figure. Live process check ordered. |
| ~09:23 | Correction: desktop is Xfce, not GNOME. Process table shows Chrome as the eater. |
| ~09:24 | All 21 Chrome processes killed. Available RAM 3.0 to 4.4 GiB. Box re-hibernated. |
| 09:29 | Chrome fully uninstalled, moli 1.1.24 installed and fetch-tested. This report requested. |

## 2. Connecting the Simular MCP — OAuth failed, a personal key worked

Simular MCP is a single remote MCP server that gives an agent a cloud Linux computer of its own: a full Ubuntu desktop, Chrome, a shell, and screen control, so the agent can browse, click, type, read the screen, and run commands without using the user's own machine. For coding agents the documented entry point is `npx @simular-ai/box@1.2.1 connect`.

### What failed

The first route was OAuth. Simular rejected the sign-in outright:

> **Simular's rejection, verbatim in substance:** "Can't connect this agent. This sign-in request could not be accepted. Reason: client_id is neither a client metadata document URL nor a client this server registered."
>
> Simular's own registration endpoint was checked and was working normally, so the mismatch was in how the agent presented its OAuth identity — not something fixable from the user side in this run.

The OpenClaw instructions the user pasted (`npx @simular-ai/box@1.2.1 connect --client openclaw`, then `openclaw mcp login box`) are a different path: they connect a Box to the OpenClaw app on the user's own computer, not to this agent. They were not used.

### What worked

1. Fallback to the route Simular documents for apps that cannot do OAuth sign-in: a **personal key**, created at box.simular.ai/access under Keys and devices, used as `Authorization: Bearer`.

2. The first two attempts to save that key in the secure vault failed with the same generic error. The key itself was never shown to be wrong — the save step failed, possibly from a stray space or newline, or a glitch in the save page. Keys are shown only once, so a fresh key was the clean retry.

3. Because the first connector appeared to be stuck holding the failed OAuth scheme, a **new connector under a different slug**, `custom.simular-box-key`, was created. The save succeeded there.

4. Verification was by real calls, not by the save screen: list-tools returned **36 tools**, and box_list returned the folio Box **main (cb-fcfcd7a79078), hibernated**.

> **Scope note:** This key sees only the folio account. The second Box, created 13 Nov under user@example.com (cb-b369f4420af1, 2 vCPU / 3.8 GiB / 20 GB), is a different Simular account and does not appear under this key. Only a small allowlist of tools is enabled in the client (box_list, box_status, billing, and — added during provisioning — box_ensure, shell, list_dir, read_file, box_hibernate); all other tools stay blocked until reviewed.

## 3. Why bk-android keeps suspending

The forwarded report blamed recurring InstaBox suspensions for killing builds — including job `ca408eef` for djmix23, mid-build. Checked live, the core of that account holds, with one material correction.

### Verified live

- **Cooldown end is exact:** bk-android suspended with `cooldownUntil 2026-11-14T03:13:56Z` — 10:13:56 WIB. It had been suspended at 08:03:55 WIB, so the lock itself ran about 130 minutes.

- **The escalation ladder is real** and is in InstaBox's own lifecycle documentation (updated 13 Nov 2026):

| Consecutive max-run hit | Lockout |
|---|---|
| 1st | 5–20 minutes |
| 2nd | 60–75 minutes |
| 3rd and later | 120–135 minutes — the bucket this lock falls in |

- **The reset is also documented:** the consecutive-hit count restarts when a run ends with no cooldown owed — an idle pause, or the user pausing or stopping the machine. A forked machine starts again at the first step.

- **Simular's side checked out:** billing returned plan free with 3,414 credits; box_status showed main hibernated on sbxdesk:v19, with v22 available as an upgrade.

> **Correction to the forwarded report:** it describes a "120-minute cap" per run. That is the *starting* value, not the ceiling. A run extends while it is in use, up to 480 minutes in a single run — and the API showed bk-android at `maxRunMinutes: 480`. So djmix23 was not killed at 120 minutes; it ran up to the extended 8-hour maximum. The practical rule still follows, but it must be computed from `runStartedAt` plus the current `maxRunMinutes` from the API, not from a fixed 120.

### Two flags the forwarded report did not mention

- **19 OOM kills on bk-android.** The most recent was 12 Nov: a Java process at 3.6 GB RSS on an 8 GB box. Gradle is already memory-tight on the primary builder — which is why the Simular box's RAM (Section 6) matters as much as its lack of cooldowns.

- **df was also locked** that morning, until 10:21:32 WIB. The suspension pattern was not confined to the Android builder.

- Simular's credit burn rate for a running box was — and remains — **unmeasured**. Provisioning showed no charge (Section 6), but that is an observation about one run, not a rate.

### The four proposed fixes, assessed

| Fix | Assessment |
|---|---|
| 1. Suspend/resume timer reset on bk-android | Sound and free. Before any build, check remaining run time; if under ~55 minutes remain, suspend and resume first so a ~40-minute gate never starts on a short clock. Suspend immediately after pulling APK and patches instead of burning clock during phone listening. |
| 2. Simular as hot mirror builder | **Done in this run** (provisioning, Section 6). Mirror itself still pending the 10:14 window. |
| 3. Fork bk-android to bk-android-standby | Plausible and cheap: fork clones the /data disk (SDK, Gradle cache, oldkey, worktree); a fork starts at step 1 of the ladder. Not yet done; it needs bk-android awake, so it belongs in the same 10:14 window. |
| 4. Upgrade to InstaBox Pro | Optional. Would allow no runtime cap and a resize to 8 vCPU / 16 GiB, cutting the full gate from ~40 to ~15 minutes by the report's estimate. Fixes 1–3 already stop the lockouts without paying; Pro is a speed purchase, not a fix. |

## 4. BitChord state: what is claimed, what is confirmed

BitChord is the app under development; AutoMix is its DJ-style automatic transition feature, and the djmix builds are its successive iterations. The version in the user's hands and the version in the worktree are one step apart.

| Thing | Claim | Status |
|---|---|---|
| Phone 1439ebea | Running vc64 — 2.2.3-djmix22 | Reported; not re-checked in this run |
| vc65 / djmix23 | Code-complete, targeted tests green, in the bk-android worktree | **Unverified** |
| Worktree HEAD | dc78e0d, with an exact 6,085-entry staged index to preserve in the mirror | **Unverified** |
| Source worktree path | /data/workspace/bitchord-task2/work/BitChord on bk-android, plus oldkey | Reported |
| Mirror target | /workspace/BitChord on Simular main | Agreed; not yet copied |

> **Why vc65 stays marked unverified:** every one of those claims lives inside the bk-android worktree, and bk-android was asleep in cooldown for the whole of this run. There was no way to inspect the worktree, its HEAD, or its staged index from here. The report does not upgrade them to facts, and the mirror step must verify HEAD and the staged count *before* anything is built on the copy.

### The immediate gate — ship vc65, then listen

1. **Finish the vc65 build after 10:13:56 WIB:** run the full gate — `:shared:allTests :app:testProdDebugUnitTest :app:assembleProdRelease`.

2. Verify **14 arm64-v8a libraries** and the **oldkey v2 certificate** on the release APK.

3. Pull the APK to `~/Storage/Downloads/`; write `bitchord-djmix23.patch` and `bitchord-djmix23-full.patch` to `~/Bitchord/patches/`.

4. `adb install -r` onto phone 1439ebea.

5. **Four-pair listening verdict — ears only.** No quality is claimed for vc65 until the user hears it:
   - *This Is What You Came For to One Kiss* — the unstarted-adopt fix: no self-pause at ~1.6s, deferred handoff holds cleanly, buildup + flanger, crisp vocal entry.
   - *Beautiful to Never Let Go* — 8 bars, sung outro plays out cleanly.
   - *Drip to UP* — no chipmunk pitch, stutter, or freeze.
   - *Technologic to Guess* — no decline, no vocal mush.

## 5. The decision taken in this run

> **Provision Simular now. Hold vc66.**
> Use the cooldown wait productively by making Simular build-ready, so the mirror is a copy job at 10:14 rather than a setup job. Do not start vc66 module code (onset snapping, multi-region grids, section painting, mask gating, limiter) yet: that code ports against the worktree, and writing it blind risks colliding with djmix23's staged work. vc66 preparation resumes after the mirror, against the mirrored tree.

That division — infrastructure now, feature code after the mirror — is the organising decision of the whole run, and it is why the provisioning worker was explicitly instructed: no vc66 code, nothing mirrored, no image upgrade, no boxes created or deleted.

## 6. Provisioning the Simular box as an Android builder

Target: only the folio Box **main, cb-fcfcd7a79078** — box_list confirmed it is the only box under this key. The box was inspected first: user `user` (uid 1000), passwordless sudo working, /workspace present, user-owned and writable. It already held `bkmasterplayer`, `flows`, `gos`, and `simular-vps-test` from 12 Nov; all were left untouched.

### Installed and verified — first try, no retries

| Component | Version | Location / evidence |
|---|---|---|
| JDK | OpenJDK 21.0.13.0.3 | /usr/lib/jvm/java-21-openjdk-amd64; openjdk-21-jdk from Ubuntu noble-updates; java -version confirms 21 |
| Android SDK | cmdline-tools 12.0 | $HOME/Android/Sdk (/home/user/Android/Sdk); all licences accepted |
| Platform | platforms;android-36 | .../platforms/android-36 |
| Build-tools | build-tools;36.0.0 | .../build-tools/36.0.0 |
| NDK | ndk;27.3.13750724 | Latest 27.x listed by sdkmanager; .../ndk/27.3.13750724 |
| CMake | cmake;3.22.1 | cmake --version: 3.22.1-g37088a8; .../cmake/3.22.1 |

`sdkmanager --list_installed` shows all four SDK components; SDK total size 2.5 GB. Environment is wired in two places: `/workspace/android-env.sh` on the box (exports JAVA_HOME, ANDROID_HOME, ANDROID_SDK_ROOT, and adds cmdline-tools and platform-tools to PATH) and the same block appended to `~/.bashrc`. Verification was done by sourcing the file in a fresh shell: java 21.0.13, sdkmanager 12.0, ANDROID_HOME resolving correctly.

| Measure | Before | After |
|---|---|---|
| Disk free (of 40 GB) | 35 GB (12% used) | **32 GB (19% used)** |
| Credits | 3,414 | **3,414** — no observed charge; billing may lag, so this is the observed value, not a guarantee of a zero rate |
| Box image | sbxdesk:v19 | Still v19 — v22 upgrade available, deliberately left alone |
| Final state | Hibernated | **Hibernated** again, to save credits until the mirror |

> **Two caveats carried forward from provisioning:** (1) Wake took **about 3 minutes**, not the "few seconds" in the docs — budget for that at 10:14. (2) At idle the desktop already used 4.8 GiB, leaving **2.9 GiB available** with no swap — the observation that triggered the investigation in the next section.

### What the mirror still needs at 10:14

1. Wake the box (box_ensure) — allow ~3 minutes.

2. Kill Chrome first if it has respawned (see Section 8 — after the uninstall, confirm whether anything returns).

3. Copy the BitChord worktree and oldkey into `/workspace/BitChord`, preserving HEAD dc78e0d and the staged index — and verify both on arrival.

4. Source `/workspace/android-env.sh` (or rely on ~/.bashrc) before running Gradle.

## 7. The memory investigation: it was Chrome, not the desktop

The provisioning caveat — "the idle desktop eats 4.8 GiB" — drew an immediate and justified challenge. The first explanation offered was wrong in a specific way: it named **GNOME**. The user corrected it: the box uses **Xfce**. A live check confirmed the user: `XDG_CURRENT_DESKTOP=XFCE`.

That correction made the number harder, not easier, to explain — Xfce is a lightweight desktop. So the box was woken and its processes listed live. The table below is that listing, as presented in the run.

| Process | PID | RSS | %MEM | Since |
|---|---:|---:|---:|---|
| chrome renderer | 14925 | 635 MiB | 8.0% | Nov 13 |
| chrome main | 13801 | 560 MiB | 7.0% | Nov 13 |
| cocoonbox-agent serve | 377 | 458 MiB | 5.7% | Nov 11 |
| chrome renderer (extension) | 14576 | 356 MiB | 4.4% | Nov 13 |
| cocoonbox-agent toolhost | 5409 | 207 MiB | 2.6% | Nov 12 |
| chrome renderer | 13924 | 191 MiB | 2.4% | Nov 13 |
| chrome gpu-process | 13855 | 187 MiB | 2.3% | Nov 13 |
| opencode serve | 11308 | 149 MiB | 1.8% | Nov 12 |

### Grouped totals

| Group | Processes | Total RSS |
|---|---:|---:|
| Chrome | 21 | **~3,976 MiB** |
| cocoonbox-agent (Simular's own agent) | 3 | ~645 MiB |
| Whole Xfce stack (xfwm4, panel, Xorg, etc.) | 8 | ~344 MiB |

Machine total: 7.7 GiB RAM, 4.7 GiB used, 3.0 GiB available, swap 0.

The eater was Simular's own managed Chrome: running since 13 Nov with a restored session, on remote-debugging port 9222 — left over, in effect, from earlier sessions, alongside an opencode server still running since 12 Nov. The desktop itself, at 344 MiB for the entire Xfce stack, was never the problem.

> **Reading the numbers honestly:** RSS totals double-count shared pages, so the group totals do not sum exactly to memory used. That accounting detail matters in the next section: killing a process whose RSS sums to ~3,976 MiB does not free 3,976 MiB. Chrome was still the eater by far — but the recoverable amount was always going to be smaller than the headline sum.

Context from the user's standing notes sharpens the picture: the folio Box is 4 CPU / 7.7 GiB / 40 GB on a free plan (1,000 credits/day, resetting 00:00 UTC), it hibernates when idle, and it has no public IP or port forwarding. It was already in use as an Android builder candidate precisely because the alternatives were worse — it is borderline against Android Studio's 8 GB minimum, with no emulator headroom.

## 8. Killing Chrome, then uninstalling it for moli

### Step 1 — kill (09:24 WIB)

On the instruction to kill Chrome, all **21 Chrome processes** were killed, including the crashpad handler, and re-checked 10 seconds later: **0 remaining, no respawn**. The box was then hibernated again immediately, so nothing could return before the mirror window.

- Available RAM: **3.0 GiB to 4.4 GiB** — a real gain of 1.4 GiB, not the ~3.9 GiB the raw RSS sum suggested, because most of Chrome's RSS was shared memory counted once per process.

- Gradle's headroom therefore improves from 3.0 to 4.4 GiB. Better, but still tight next to bk-android's history of Java OOM kills at 3.6 GB RSS.

- At kill time it was genuinely unknown whether Simular would restart Chrome on the next wake or when the live view is opened. That question is now moot for Chrome itself (it is uninstalled) but worth re-checking for any successor process.

### Step 2 — uninstall and replace with moli (09:29 WIB)

On the instruction to uninstall Chrome and replace it with moli (github.com/lexmount/moli):

| Action | Result, verified |
|---|---|
| Chrome package | google-chrome-stable 157.0.8103.38-1 **purged**; apt source removed |
| Chrome profiles | Both profile directories deleted, including the 254 MB cocoonbox-chrome profile |
| Chrome remnants | 0 processes, no package, no binary |
| moli install | **moli 1.1.24** at ~/.local/bin/moli (107 MB); PATH wired into ~/.bashrc |
| Installer safety | Installer script read first: it only downloads the release tarball from GitHub and drops the binary into ~/.local/bin |
| moli verification | Real fetch passed: `moli fetch --dump markdown https://example.com` returned the page |
| Memory after swap | Still 4.4 GiB available; box hibernated again |

moli is a headless Rust browser. For a build box that only occasionally needs to fetch a page, it replaces Chrome's ~21-process resident footprint with a single on-demand binary — which is the entire point of the swap.

> **Known cost, stated at the time:** Simular's `browser_*` MCP tools drive Chrome, so those tools will break on this box, and the live view will have no browser in it either. That trade was accepted explicitly because this box's job is building, not browsing. Any future task that needs the agent to browse *through* this box must either use moli's fetch path, use a different box, or reinstall a browser — it cannot silently assume Chrome is still there.

### Where the two builders now stand

|  | bk-android (InstaBox) | Simular main |
|---|---|---|
| RAM | 8 GB; 19 OOM kills on record | 7.7 GiB; 4.4 GiB available after Chrome purge; no swap |
| Runtime limit | Max-run ladder with escalating cooldowns (now at step 3) | No 2-hour cooldown lockouts; hibernates when idle |
| Android toolchain | Established (SDK, Gradle cache, worktree, oldkey in place) | Freshly provisioned: JDK 21 + SDK 36 + NDK 27 + CMake 3.22; no worktree yet |
| Cost signal | Free plan | Free plan; 3,414 credits; running burn rate unmeasured |
| Wake / resume | Blocked until 10:13:56 WIB in this run | ~3 minutes observed |

## 9. Everything still left on the AutoMix roadmap (1 of 2)

This and the next section reproduce the forwarded master todolist in condensed form, preserving every open item. "Already done" lines are the report's own summary of shipped work and were not audited in this run.

### Block 1 — Beat awareness, tempo grids and zero-flam phase lock (bkmasterplayer P0 port)

*Already done per report: Beat This! final0_q8, [0.5, 1.0, 2.0] folding, safe speed clamps, incomingHoldSec knots, 2-thread TrackAnalyzer with next / next+1 prefetch.*

- **Onset snapping:** spectral-flux `snap_to_onsets` (+/-35 ms) so Beat This! timestamps sit on real kick/snare transients instead of 15–30 ms off the attack (source: bkmasterplayer analysis.rs).

- **Multi-region tempo grid:** `fit_grid` + `join_regions` with phase_coherence >= 0.42; stop concatenating 22-second Beat This! windows blindly (which flips bar/downbeat phase across seams), preserve genuine TempoRegions, reject hallucinated grids on ambient intros/outros.

- **25 ms phase-error gate** (`offset_near`) at the mix anchor: re-check residual at the chosen exit/entry; step down a rung rather than flam if error > 25 ms.

- **Unify DJ_BLEND / DJ_FILTER** onto the single-clock C++ renderer; retire the dual-ExoPlayer path (40 ms polling, Opus sync-seek jitter, holdPhase nudges) except as emergency fallback when LoopMixPort cannot attach.

- **Dedicated nextTrackHeadExecutor + AdaptivePreArmManager:** separate fast 35s head analysis from 35 MB whole-track decodes; widen pre-arm lead dynamically (20s to 35s) when prep exceeds 8s.

### Block 2 — Song structure, non-abrupt endings and interpolations

*Already done per report: MIN_PLAY_FRACTION = 0.78, RetireGuard dissolve tail, StructureMix 8/16-bar vocal-gap budget, placeOnStructure +/-6s guard, break_outro detection, BUILD/DROP enum slots.*

- **Active section labelling (SongSections.kt):** port MSAF's Foote checkerboard novelty kernel (beat-sync chroma + 3-band energy + MFCC-lite) plus opendj's thin-mids rule (mid & high < 0.7 x P75) so labelSections actually paints INTRO, VERSE, PRE_CHORUS, CHORUS, BRIDGE, INST/riff, BUILD, DROP and OUTRO even without synced lyrics. Today BUILD and DROP exist as enum values from djmix21 but are never painted.

- **Rung 0 — gapless guard:** skip DJ mixing for consecutive same-album tracks (trackNumber + 1), classical / spoken-word / live tracks, and acoustically continuous segues (edge energy > -34 dBFS with spectral centroid delta < 450 Hz).

- **Final-hit preservation:** port the music_end detector so cymbal/reverb tails and final hits are not clipped; enforce at most 1 final chorus discarded (outgoing) and never skip a sung verse (incoming; chorus-jump only over >32-bar beatless intros).

- **Per-region harmonic bridging:** per-region Camelot keys (libkeyfinder / Essentia edma on tail vs head separately); interpolation/motif-bridge scoring that rewards a hook handing into a matched riff, pre-chorus or vocal pickup; complementary filter sculpting (A low-pass 2.8 kHz + B high-pass 380 Hz) when keys clash by Camelot >= 3.

### Block 3 — Vocal and instrumental separation

*Already done per report: vocals_umxhq_int8.onnx mask, TransitionMoves M1 outgoing mid-carve -3 to -6 dB while incoming sings, crisp incoming highs >= -1 dB.*

- **Lyrics = veto only, acoustic mask required** (LoopMix.kt / CrossfadeController.kt): require umxhq clearance (p_vocal_max < 0.22, p_vocal_mean < 0.14) to kill false-positive loops from lyrics alone; stop bridging lyric-line gaps >= 1.5s to fix loop starvation (reported symptom: 53 sung by lyrics, 0 sung by mask).

- **Tier 1 — windowed soft-mask vocal-out:** run the bundled umxhq model over the transition window only, split vocals vs instrumental (mix minus vocals, 5 ms stem-to-mix crossover), fade Track A's vocal out 1 bar before Track B's singer enters while A's drums and bass keep playing underneath. No new model size.

- **Tier 2 (P2, opt-in, 8 GB+ RAM / charging) — 4-stem acapella-over-instrumental:** windowed htdemucs over at most the last 32 bars of A and first 32 bars of B, cached as Opus, for true mashup blends in matching Camelot keys.

## 10. Everything still left on the AutoMix roadmap (2 of 2)

### Block 4 — Looping, flanger, native C++ DSP and DJ-level FX rotation

*Already done per report: native PingPong echo with past-EOF ring-out, native 8-beat Flanger with deterministic slot % 4 and buildup trigger, PhraseIsolator LR4 EQ, LoopMix.dropPlan initial outro-loop drop, DjColour frozen-anchor roll on the classic processor.*

- **Clickless loop-wrap:** gate loop candidates on boundary spectral cosine similarity >= 0.80 and RMS step < 3.5 dB; verify and harden the 8 ms equal-power post-loop guard crossfade on every wrap in Deck::sampleAt.

- **Intro/outro phrase extension (LoopMix.Clock):** automatically repeat a clean 2/4/8-bar intro or outro phrase when the natural phrase is too short for an 8- or 16-bar blend.

- **TPT SVF bank + bass swap:** port bkmasterplayer's mixer.rs Svf (low-pass, high-pass, 200 Hz bass-kill shelf, Cytomic zero-delay-feedback design) with an exact 0.5-beat complementary downbeat bass swap so two kicks never double or hollow out.

- **Master limiter + regional loudness:** stereo-linked 1.5 ms lookahead true-peak limiter at -1.0 dBTP at the end of Renderer::render, with zero audio-thread allocations, plus gated EBU R128 (libebur128) loudness matching over the exact exit/entry bars.

- **Complete native FX palette, per-pair rotation (loop_mix_renderer.cpp):**
  - Stereo plate reverb wash-out (300 Hz high-passed send, decaying past outgoing EOF).
  - Native single-clock beat-synced loop roll / stutter (1 to 1/2 to 1/4 to 1/8 beat, slip-mode buildup/handoff) with 3 ms seam crossfades landing on Track B's beat 1 — today the roll exists only on the Kotlin AudioProcessor path.
  - Flanger polish: stereo 90-degree L/R LFO spread, >250 Hz high-passed comb feedback, dynamic buildup wet-ramp 0 to 0.45 cutting clean on the drop downbeat.
  - Deterministic FX rotation across consecutive pairs — never the same FX move twice in a row.

- **Post-plan verification gate (P2):** dry-run sanity check of the planned overlap before arming — gain/EQ/phase envelopes within <=10 ms drift, <=3 LU jump, zero clipping.

> **Where vc66 fits:** the proposed vc66 P0 module set — snap_to_onsets, multi-region grids, active BUILD/DROP painting, mask-required loop gating, and the -1 dBTP limiter — is drawn from Blocks 1–4 above. It is the next slice, not the whole roadmap, and it is the slice explicitly held until after the mirror (Section 5).

## 11. Risks and unknowns that survive this run

| Risk / unknown | Why it matters |
|---|---|
| RAM on both builders | Simular gives 4.4 GiB available after the purge; bk-android OOM-killed a 3.6 GB Java process on 12 Nov. A full Gradle gate can still OOM on either box. Mitigations available: keep Chrome/moli resident footprint at zero during builds, consider Gradle heap tuning — neither applied yet. |
| Simular credit burn rate | Provisioning showed no charge, but no sustained-build measurement exists. A long gate on Simular costs an unknown number of the 3,414 credits. |
| Unverified worktree state | HEAD dc78e0d and the 6,085-entry staged index are claims. If either differs on inspection, the mirror plan and the vc65 gate both change. |
| browser_* tools broken | Accepted cost of uninstalling Chrome. Any workflow that assumed agent browsing through this box must be rerouted. |
| Wake latency | ~3 minutes observed vs "a few seconds" documented. Timelines for the mirror and for any hot-standby use should use 3 minutes. |
| Standby fork not yet created | Fix 3 (bk-android-standby) remains a proposal until bk-android is awake to fork from. |

## 12. Next steps — in the order they should happen

1. **At 10:14 WIB, when bk-android unlocks:** check runStartedAt and maxRunMinutes first. Apply the suspend/resume reset rule before starting anything long — never launch the ~40-minute gate on a short clock again.

2. **Mirror to Simular:** wake main (allow ~3 min), confirm no browser process has respawned, copy the BitChord worktree + oldkey to /workspace/BitChord, then verify HEAD and staged-index count on the copy before trusting it.

3. **Optionally fork the standby** (bk-android-standby) while bk-android is awake, and leave it suspended.

4. **Ship vc65:** full gate, 14-library and oldkey v2 cert checks, APK + both patch files pulled, adb install -r to 1439ebea, then suspend bk-android immediately instead of burning run clock during listening.

5. **Four-pair listening verdict.** vc65 is not "good" until the user says so on those four transitions. Log the verdict per pair.

6. **Only then, vc66:** start the P0 module code against the mirrored, verified tree — onset snapping, multi-region grids, BUILD/DROP painting, mask-required loop gating, -1 dBTP limiter.

7. **Measure what is still unmeasured:** Simular credit burn over one real Gradle gate, and Gradle peak memory on Simular with Chrome gone. Both numbers decide whether Simular becomes the primary builder or stays the cooldown-proof backup.

### What "done" means for this report's subject

The Simular side of the job is done: MCP connected by a verified route, box provisioned and verified, memory hog identified from live process data — after correcting the desktop environment from GNOME to Xfce — Chrome killed, then removed entirely, moli installed and tested, box back in hibernation with credits unchanged.

The BitChord side is deliberately not done: vc65 has not shipped, the mirror has not happened, and the AutoMix roadmap in Sections 9–10 remains open in full. Those were never this morning's deliverable — this morning's deliverable was making sure that when 10:14 arrives, nothing about the infrastructure is still in the way.

> **One-line summary for the record:** Cooldown problem — diagnosed, corrected, and routed around with a provisioned Simular mirror. RAM problem — named honestly on both builders, improved from 3.0 to 4.4 GiB available by removing Chrome, not solved. Code problem — untouched by design until vc65 ships.

---

*Sources and basis: the live conversation of 14 Nov 2026 (MCP connection, verification replies, provisioning handoff, process listing, kill and uninstall confirmations), the forwarded AutoMix report reproduced in Sections 3–4 and 9–10, InstaBox lifecycle documentation as cited in the verification reply (updated 13 Nov 2026), and the user's standing Simular notes (Box inventory, specs, referral and billing history). Times are WIB (Asia/Jakarta, UTC+7) unless marked UTC. Version and process figures are reproduced exactly as verified in the run; claims that could not be checked are labelled reported or unverified wherever they appear.*
