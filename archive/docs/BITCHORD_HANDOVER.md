# BitChord Automix Fork — Complete Handover

*Compiled 11 November 2026, about 10:15 Asia/Jakarta (UTC+7). **Updated 11 Nov 2026, 18:30 WIB:** sections 11 (user field notes on djmix5), 12 (djmix6, vc48), 13 (external orchestrator sessions and consolidated worker-ready findings) and Appendix A were added. **Latest build: djmix6. Top open bugs: the queue force close (see 13.1) and the echo lag.** Written for a new engineer or AI agent with zero prior context. Facts below are taken from the user conversation, the cloud-agent transcript (3,213 lines, covering the first prompt through mid-djmix5), shipped APKs, and the research documents on disk. Do not treat unverified listening claims as measurements.*

---

## How to use this document

This is the single onboarding brief for the private **BitChord loop-fix** fork: the app, the user’s goal, Apple AutoMix parity, known bugs, every shipped build, references, legal constraints, and how APKs are delivered.

**Working rules that must not be quietly dropped:**

1. Loop mix stays Tier 1 / the “crown”. Never replace, shorten, or degrade a valid loop.
2. Do not build on the user’s Linux PC. It is low-end. Builds happen on the Cursor cloud agent.
3. Sign every release APK with the **same local key** and bump `versionCode` so it updates over the installed fork.
4. Upload the full APK to the user’s Google Drive (they are often on Android and cannot join split parts). Install on the phone with `adb install -r` when adb is authorized.
5. Licence and patent hygiene is mandatory (Section 7).
6. Most audible fixes are **unverified on a device**. Do not report them as confirmed in the wild.

**Primary cloud agent (source of truth for code):** [bc-10f3ffd2-4eec-569e-aafe-8bd118b340fe](https://cursor.com/agents/bc-10f3ffd2-4eec-569e-aafe-8bd118b340fe) — unpublished Cursor Origin draft **`hf-zh/tmp-1a194b77b9f0f8dd`** on `main`, no pull request. Git remote seen in the agent VM: `origin.cursor.com/git/hf-zh/tmp-1a194b77b9f0f8dd`. Conventional repo page: [cursor.com/codebase/hf-zh/tmp-1a194b77b9f0f8dd](https://cursor.com/codebase/hf-zh/tmp-1a194b77b9f0f8dd). Follow-ups go to *that* agent via reply (queue or steer). Do not launch a second agent on the same repo.

---

## 1. App overview

### 1.1 Upstream BitChord

[kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord) is a GPL-3.0-or-later Android music player / streamer written in Kotlin with Jetpack Compose (plus shared Kotlin Multiplatform code, C++/JNI, and a web cousin). The fork starts from **release tag v1.9.1**, git commit `7f45c76` (the v1.9.1 merge on upstream is `acf219a`). Official package id: `com.music.bitchord`. Official phone `versionCode` at 1.9.1 is **39**, `versionName` `1.9.1`.

It already shipped, at 1.9.1, the expensive parts of an on-device automix stack:

- **Beat This!** final0 int8 / q8 ONNX beat and downbeat model (`CPJKU/beat_this`, MIT).
- **open-unmix umxhq** vocal mask, int8 ONNX (`vocals_umxhq_int8.onnx`; umxhq weights are MIT — **not** umxl).
- **Signalsmith Stretch** (MIT), vendored under `native/loopmix`, used by the loop-mix renderer.
- A **loop mix** planner (`LoopMix`) that looks for clean instrumental 4/2/1-bar loops, checked against lyrics and/or the vocal mask, played on a shared beat clock with a tempo ramp.
- Transition styles already in the planner / renderer: `EQUAL_POWER`, `GAPLESS`, `DJ_BLEND`, `DJ_FILTER` (sweep, bass handoff, echo ride).
- Some AGPL-derived automix code (from heydavidf/automix and older Orchard-derived files). Combined under AGPL §13; headers live on `LoopMix.kt` and `TrackAnalysis.kt`. Keep those notices.

The app streams YouTube Music and other add-on sources, so there is **no analysis server**. Everything has to run on the phone.

### 1.2 This private fork

| Field | Value |
|---|---|
| App label | **BitChord loop fix** |
| `applicationId` | `com.music.bitchord.loopfix` (installs *beside* official BitChord) |
| Starting tree | Upstream v1.9.1 (`7f45c76`), GPL-3.0 and attribution kept |
| ABI | **arm64-v8a only** (phone APK ~83–83.4 MB). TV / Wear / desktop / universal (~186 MB) are not in these builds |
| Signing | A **new local key**, not upstream’s. Certificate SHA-256: `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`. The keystore is **not in git**. Reuse the key the cloud agent already has |
| Self-update | `AppUpdateChecker` must not query or download the official release, or it would overwrite the fork |
| Repo | Unpublished Origin draft **`hf-zh/tmp-1a194b77b9f0f8dd`**, branch `main`, no PR. Remote `origin.cursor.com/git/hf-zh/tmp-1a194b77b9f0f8dd`. Worked on only by cloud agent `bc-10f3ffd2-4eec-569e-aafe-8bd118b340fe` |
| Current shipped build | **djmix4**, `versionCode` **46**, `versionName` `1.9.1-djmix4` (11 Nov 2026, 23:07 Asia/Jakarta). **djmix5** is the in-progress iOS 27 parity round; no APK in artifacts as of this writing |

### 1.3 Automix architecture (baseline + fork)

High-level pipeline, unchanged in shape since 1.9.1:

1. **Analyze** the current and next tracks (head and tail windows): beats / downbeats, energy, key, vocal mask, lyrics.
2. **Plan** a transition with `TransitionPlanner` / `TransitionPolicy` (tier ladder). `LoopMix.plan` is the top rung when it qualifies.
3. **Arm** both decks in time (`CrossfadeController`, `LoopMixSession`).
4. **Render** the overlap: loop mixes go through the native single-clock `LoopMixRenderer`; historically, `DJ_BLEND` / `DJ_FILTER` still ran on **two ExoPlayer instances** (this is a root defect the djmix5 brief requires unifying).
5. **Hand over** to the incoming ExoPlayer and show the plan on the Automix status line / mix-view popup.

**Important Kotlin / C++ locations** (paths relative to the app repo):

| Area | Path |
|---|---|
| Transition planner | `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` |
| Policy / tier ladder | `…/smart/TransitionPolicy.kt` |
| Loop planner | `…/smart/LoopMix.kt` (tests: `LoopMixTest.kt`) |
| Track feature types | `…/smart/TrackAnalysis.kt` |
| Camelot / harmonic rules (fork) | `…/smart/HarmonicMix.kt` |
| Effect colour / rotation (fork) | `…/smart/DjColour.kt` |
| Beat tracker | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` |
| Vocal mask tracker | `…/smart/VocalTracker.kt` |
| Analysis orchestration | `app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt` |
| Playback / arming / UI line | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` |
| Loop session / decode | `app/…/playback/loopmix/LoopMixSession.kt` |
| JNI to native renderer | `app/…/playback/loopmix/LoopMixRenderer.kt`, `LoopMixPort.kt` |
| Filter audio processor | `app/…/playback/TransitionFilterProcessor.kt` |
| Playback service | `app/…/playback/PlaybackService.kt` |
| Pitch / stretch glue (fork) | `app/…/playback/audio/PitchStretch.kt`, `app/src/main/cpp/jni/stretch_jni.cpp` |
| Native loop mixer | `native/loopmix/loop_mix_renderer.cpp`, `loop_mix_renderer.h`, `app/src/main/cpp/jni/loopmix_jni.cpp` |
| Native analysis | `libbitchord_analysis.so` / tempo analysis under the analyzer native tree |

**Native libraries in the shipped arm64 APKs:** `libbitchord_loopmix.so`, `libbitchord_analysis.so`, `libonnxruntime.so`, `libffmpegJNI.so`, plus DSD/USB/exclusive helpers. From **automix2** onward there is also `libbitchord_stretch.so`.

**How a loop mix is supposed to work (the crown):**

- `LoopMix.plan` looks for lyric- and/or mask-clear instrumental 4/2/1-bar loops on the outgoing tail and incoming head, with tempo inside the loop-mix limits (including fold).
- Decks are built by `LoopMixSession.build` (open stream, decode both regions, resample) and rendered by the native `Renderer` on **one sample-exact clock** (`SignalsmithStretch`), then spliced out of / into ExoPlayer with a tight alignment budget (the djmix5 brief asks for ≤ 1.5 ms handover).
- If the plan is late, the fork (from the first loop-fix build) must **slide to the next valid bar** instead of dropping to a filter mix, and must name the late step and the millisecond overrun on the Automix line.

**Known architectural split (still true as of djmix4, and the main iOS 27 parity gap):** loop mixes use the native single clock; non-loop `DJ_BLEND` / `DJ_FILTER` still used two ExoPlayers, `ARM_STEP_MS = 40 ms` polling, Opus seek jitter, `AudioTrack` startup latency, and delayed `holdPhase` speed nudges. That is root cause RC-1 in the djmix5 playbook (Section 3).

---

## 2. The user’s goal and priorities

The user is **HF ZH**, on Android and a low-end Linux PC (machine `77890ea2-5034-4d18-8649-e40e1f18541d`), timezone Asia/Jakarta (UTC+7). Style: casual, wants installable APKs, not process theatre.

### 2.1 What they asked for, in their words

The original brief (first cloud-agent prompt) was: fork BitChord v1.9.1, fix the Automix loop being abandoned mid-countdown, and produce a side-by-side APK.

After the first APK worked, the direction became much larger. Key user statements:

> “dont overthink abt import from official bitchord, ive alr make up my mind. bkmasterplayer is a powerhouse for mobile automix, be all in ; use gitrepo morluto/rea skill ; improve bitchord to be superior in automix in all ways WHILE also keeping it performant”

On loop mix, the user (and the follow-ups sent on their behalf) was unambiguous: BitChord’s loop mix is better than anything bkmasterplayer does and must stay first. One coordinator follow-up put it as: the loop mix is BitChord’s **crown** and must remain unshaken. Transition priority they locked in:

1. BitChord loop mix whenever its existing eligibility passes; never replace, shorten, or degrade a valid loop because of new Camelot / drift logic. New work may only *improve* a loop (tighter grid, pitch-preserving stretch during the tempo ease).
2. bkmasterplayer-style beat-matched blend / bass swap when no loop qualifies but grids / keys fit.
3. Echo-on-the-bar when the tempo gap exceeds the stretch limit.
4. Filter mix last.

On the destination quality bar:

> the user’s final goal is “a high tier hi-quality dj level automix”

They asked for research into Apple AutoMix and open-source implementations, and for a result as good as **Apple Music AutoMix (iOS 26 / iOS 27)**: both songs playing together for several bars, blending almost like one song, with nothing ending abruptly.

Ideal, in the user’s own later bug report:

> “in ideal apple automix; both concurrency lasts several bars naturally and so much DJ like they almost blend to a single song since the mix is so good and nothing ends abrupt”

### 2.2 Explicit non-goals / deprioritised work

- **Data import from official BitChord** was investigated and partially implemented (commit `d05af9f` on `main`: welcome screen + Settings → Your data → “Import from official BitChord backup”, remapping official package paths into `com.music.bitchord.loopfix`). The user then said not to overthink it — they had already made up their mind. Do not spend more time on import unless they ask again. Keep the existing import code only if it still compiles and tests pass.
- Do **not** re-do InstaBox provisioning, APK reverse-engineering of djmix4, or emulator QA for the djmix5 round. The user said those are skippable because the cloud agent already has the djmix4 source, and they will test on a real phone. Quote from the 11 Nov 2026 orchestrator hand-off:

> “skip the part that u alr have major stakes on (the apk, instabox part; those are doable inside ur machines) : AND I do have adb connected rn, pls skip testing/qa part, just focus on the development. DO NOT do building things inside this machine inherently tho, this machine is pretty poor and lowend.”

### 2.3 Performance bar

Superior automix **and** a performant app: no added jank, reasonable battery / CPU / memory, nothing heavy on the UI thread. Analysis is incremental, on a background thread, cached per track. Mid-range Android budget in the research dossier (Snapdragon 7-class, 6–8 GB) still applies: two ORT intra-op threads, no NNAPI, thermal fallback to the small beat model, audio thread under 25% of one core during an overlap, no allocations or locks on the audio thread.

---

## 3. Apple AutoMix parity

### 3.1 What Apple actually does (evidence-graded)

Primary sources: `/workspace/apple-automix-wiki.md` (compiled 30 Oct 2026) and `/workspace/automix-research.md` (compiled 10 Nov 2026, UTC+7). Grades: **A** Apple primary, **B** established hands-on, **C** anecdotal, **I** inference, **?** unknown.

| When | What | Grade |
|---|---|---|
| iOS 26 (autumn 2025) | AutoMix announced as time-stretching and beat-matching, on by default, catalog-only. Picks a transition per pair, including silence trim or a plain crossfade. Skips albums and some genres; off under Hi-Res Lossless. Recurring “underwater” colour; sometimes reverb; vocals that linger after the bass fades; users say it often jumps to the next song’s chorus | A / B / C |
| iOS 26.1 | AutoMix over AirPlay | A |
| WWDC26 / iOS 27 (2026) | “Richer palette of effects – including **looping, reverb, delay, and more**.” Remixes / **repeats intro and outro phrases** to align tempo. New transition types; comes to tvOS and HomePod. Hands-on: vocal-chop / stutter-like edits in beta; the iOS 26 underwater sound mostly gone | A / B / C |
| WWDC26 | Public **Music Understanding** framework (on-device key, beats/bars, unlabelled sections/segments/phrases, pace, instrument activity including vocals, LUFS). Best public proxy for what AutoMix “knows”. Apple does **not** say AutoMix uses it | A |
| iOS 27.1 / 27.2 / 27.2.1 | No AutoMix changes in the notes | A / B |

Checked against claims that came up in research:

- DJ-level **looping**: confirmed (iOS 27).
- **Flanger**: not confirmed. Apple names looping, reverb, delay, “and more”.
- **Stem separation**: not confirmed for AutoMix; plausible (Apple Music Sing has had on-device vocal isolation since iOS 16.2). Reviewers hear lingering vocals; that can also be EQ or a loop.
- **Section labels** (intro, riff, pre-chorus, chorus, bridge, outro): partial. Section/phrase *awareness* is likely; labelled “riff” / “pre-chorus” are not confirmed. Music Understanding exposes *unlabelled* ranges.
- Quality: mixed. Many reviewers say iOS 27 is much better; failure reports continue (cut verses, a fragment looping then an abrupt end, silences, a stutter glitch).

**Behaviour worth copying:** skip genres where mixing hurts (classical, opera, spoken word); never mix inside an album; repeat phrases to extend a short intro or outro; rotate effects so mixes do not all sound the same; show a visible “Mixing” indicator.

**Apple failure modes to avoid:** cutting first verses; looping a fragment and then ending abruptly; leaving silences.

### 3.2 Target 6-rung ladder (djmix5 / iOS 27 playbook)

The research dossier and the later “Gemini 4 Carbon” playbook (`/workspace/ios27-automix-playbook.md`) agree on a deterministic ladder. Rungs 1–5 are supposed to render through **one** native single-clock `AutomixRenderer` (evolved from `LoopMixRenderer`). Dual ExoPlayer is emergency-only (DRM / video / unsupported sinks).

| Rung | Name | When | What it should sound like |
|---|---|---|---|
| **0** | `GAPLESS` | Same-album consecutive tracks (`respectAlbumGapless`, default on), or an acoustic seamless segue (live crowd / concept-album pad / DJ-mix split) | 0 ms fade, 1.0×, filters open |
| **1** | `LOOP_MIX` (crown, hardened) | Clean 2/4-bar (or high-confidence 1-bar) instrumental loops on **both** sides, verified by `VocalTracker` (`p_vocal_max < 0.22`, mean `< 0.14`) and not vetoed by tight lyrics; folded BPM delta ≤ 28% | Dual-loop bridge, onset-snapped seams, 8 ms equal-power wrap, optional gentle LPF/HPF if Camelot is dissonant |
| **2** | `OUTRO_LOOP_DROP` (iOS 27 signature) | Outgoing has a clean 2/4-bar instrumental loop; incoming has no clean loop (e.g. vocal pickup in bar 1–2) but a clear pickup / first downbeat; folded BPM delta ≤ 15% | Loop and tempo-ramp **only A**; B enters at **1.000×** native, pickup aligned, bass cut and a 1-bar echo/HPF wash on A’s last bar. Fixes the “53 sung (lyrics), 0 sung (mask)” starvation case |
| **3** | `BEAT_BLEND` | Coherent onset-snapped grids, phase residual `< ±25 ms`, folded BPM delta ≤ 10% (12% if vocal overlap is low). Replaces dual-ExoPlayer `DJ_BLEND` | 4–8 bars (8–16 s typical, never a rushed 2 bars unless < 6 s remain). Continuous knot-based tempo bridge so B is at **1.000× at handover**. Bass swap on the midpoint downbeat; vocal carving when both are singing |
| **4** | `ECHO_HANDOFF` | Folded BPM delta > 10%, or a hard key clash, or only one locked grid | A stays at 1.000× to a phrase-exit downbeat; dotted-eighth ping-pong echo rings **past EOF**; B enters at 1.000× under the wash. No stretch on incoming |
| **5** | `SMART_CROSSFADE` | Unanalyzed / beatless / DRM / no `LoopMixPort` | Silence-trimmed 4–6 s equal-power S-curve, vocal-safe if masks exist |

Supporting work the playbook also requires, on top of the rungs: onset-snapped multi-region beat grids (port bkmasterplayer `fit_grid` / `join_regions` / `snap_to_onsets`); lyrics as a **veto**, never a sole pass; dual analysis executors so “next: analysing…” does not last until the transition; TPT SVF filters, regional LUFS match, true-peak limiter (−1.0 dBTP, 1.5 ms lookahead); UI that *names the rung*.

### 3.3 Gap table — Apple / target vs djmix4 vs djmix5

| Capability | Apple / target | State as of **djmix4** (shipped, mostly unheard on a logged device) | What **djmix5** is meant to cover |
|---|---|---|---|
| Album / gapless guard | No mix inside an album; seamless live/concept segues stay gapless | Not documented as a first-class Rung 0 in the reports we have | Add `GAPLESS` guard (album id + track number, and acoustic segue) |
| Loop mix first | Apple now loops phrases; our loop mix must stay Tier 1 and actually *play* | Loop-first invariant is in the planner and covered by a regression test. In the field, loops are still skipped during countdown, and “clear by lyrics” can voucher a sung hook | Harden: lyrics are a veto only; require mask when available; 8 ms seam; onset-snapped loop points; next-bar shift must not be overridden |
| Outro loop + vocal pickup drop | iOS 27 repeats / remixes outro into the next intro | Not a named rung in djmix4 reports. Planner still collapsed some no-loop cases to a 2-bar filter chop | New Rung 2 `OUTRO_LOOP_DROP` |
| Several-bar beat-matched blend on **one clock** | Apple blends for several bars; no flam / drift | Blend / filter still on two ExoPlayers (RC-1). User heard only a split second of overlap, then song A gone | Unify Rungs 1–5 on `AutomixRenderer`; 4–8 bar blends; continuous tempo bridge; B at 1.000× at handover |
| Stretch limits / no chipmunk or lurch | Apple “never forces a bad stretch” | automix2 added pitch correction on the blend path; djmix4 **disabled** that corrector after a 999× burst (see Section 4). Policy still allowed wide incoming rates (playbook: `MAX_INCOMING_RATE = 1.25`, `MIN = 0.85`) on ExoPlayer WSOLA | Cap ±6% vocal / ±8% instrumental; 2:1 and 1:2 folding; knot-based bridge; no post-mix `beginEase` lurch |
| Onset-snapped, phase-coherent grids | Beats on the kick | Beat This windows were concatenated at midpoints (`seamBeats`) without onset snap or phase reconciliation | Port `snap_to_onsets`, `phase_coherence`, `join_regions` |
| Lyrics vs vocal mask | Apple unknown; *we* must not loop a sung hook or starve a clean pickup | “clear by lyrics” can pass a vocal hook; coarse LRC padding marks 53 bars “sung (lyrics)” with “0 sung (mask)” | Lyrics = veto; tighten `sungSpans`; allow 1–2 bar micro-loops when the mask is clean |
| Next-track analysis ready in time | Upcoming track prepared well before the mix | User screenshot: “this song: analysed … next: analysing…” right up to the transition. Single-thread `bitchord-smart-analysis` executor; whole-track decode of A can block `analyzeHead` of B. Indefinite “waiting on lyrics” can also block a plan | Dual executors; 1.5 s lyric timeout then plan with the mask; adaptive lead time (NullMusic-style, already started in the first djmix increment) |
| Echo tail past EOF, filters, limiter | iOS 27 delay/reverb wash; no loudness dip/clip | Playbook says `echoSeconds = 0.0` was disabled in the planner; renderer lacked LPF, echo-past-EOF, regional LUFS, true-peak limiter. automix2 *did* add echo-on-the-bar as a planning style — field quality unverified | Full DSP upgrade in `loop_mix_renderer.cpp` |
| UI names the rung and keeps the debug info | Apple shows a small “Mixing” chip; the user wants *neat and complete* | “Stats for nerds” one-liner is dense and hard to scan (see screenshots, Section 4.2). User asked for a neater Automix UI without losing information, and for a better djmix UI design | `describeAutomix` / mix view should name the rung (`loop mix`, `drop mix`, `beat blend`, `echo handoff`, `crossfade`, `gapless`), bars, BPM bridge, Camelot, splice/handover ms |
| Effects rotation | Apple iOS 26 was criticised for sameness; iOS 27 is more varied | First djmix increment started a deterministic effect rotation (`DjColour`). Flanger not confirmed at Apple; optional and rare if added | Tasteful, deterministic-per-pair rotation; do not ship a flanger as “what Apple does” |

---

## 4. User-reported issues and bugs

Most of these were heard by the user on a **djmix** (v1.9.1-djmix, vc43) build, then restated as still standing for later iterations. On-device verification of later fixes is **not** logged. Treat “fix applied” as “claimed in a subsequent build or specified for djmix5”, not as “user signed off”.

### 4.1 The original loop-abandon bug (first brief — fixed in loopfix, then regressed in spirit)

**Symptom (user recording, ATEEZ “Adrenaline”, Bluetooth earbuds, Opus 48 kHz 149 kbps).** At 3:01 the line said:

> “Automix - loop mix in 6 s: out 4-bar, clear by lyrics - in 4-bar, clear by lyrics - 126 → 130 BPM over 2 bars”

It counted down to “in 5 s” at 3:02. At 3:03 it switched to:

> “Automix - filter mix in 27 s: 2 bars - no loop: the decks took too long”

**Root cause (cloud agent, first run, verified in code, not re-heard on a device):** `driveLoopArming` in `CrossfadeController` started `LoopMixSession.build` only `LOOP_ARM_LEAD_MS` (12 s) before the splice and gave up after `LOOP_BUILD_TIMEOUT_MS` (8 s). 12 − 8 = 4 seconds before the loop, which matches the clip. An uncached streaming Opus tail / next-track head does not finish in that budget. The plan itself can exist for most of the song; the PCM was late. Lyrics and tempo checks were not what failed.

**Fix in the first loop-fix APK (vc 39, 9 Nov 2026):** prime decks as soon as the loop plan exists; if the incoming side is still unfinished within 400 ms of the bar, **shift to the next clean bar** (phrase-aligned when possible) with the same lyrics / mask / loudness / tempo checks; only fall back when the window runs out; replace “the decks took too long” with a named late step and the ms overrun (e.g. `incoming decode 1840 ms over`).

**User status:** they installed that APK and **confirmed it works**. Later, on djmix-era builds, they reported loops being skipped again even when a countdown was showing — see 4.3. That is a recurrence or a new cancellation path, not a denial of the first fix.

### 4.2 Automix UI (user screenshots, kept under `/workspace/ui1.png`, `ui2.jpg`, `ui3.png`)

The user asked:

> “now that ur at it give ideas on how to make this ui more delivered neatly without decreasing its info thats so benefitful”

and later:

> “twas from last build but the point stand bcs my comments was after using ur djmix iteration. ui for that needs better design”

All three shots are the same dark, reddish, slightly chromatic Now Playing overlay, two centered lines of small white text.

**ui1.png — analysis status, next track still spinning**

- Line 1: `Opus · 48.0 kHz · 140 kbps · Estéreo`
- Line 2: `Automix · this song: analysed (130 BPM C minor, model grid 0.95) · next: analysing...`

This is the “waiting / blocked on next-track analysis” state (RC-6). Useful numbers (BPM, key, grid confidence) are buried in one wrapping sentence.

**ui2.png / ui2.jpg — loop rejected, 2-bar filter mix**

- Line 1: same format string.
- Line 2: `Automix · filter mix in 5 s · 2 bars · no loop: incoming: 65 loops: 12 quiet, 53 sung (lyrics), 0 sung (mask), 0 unmeasured; lyrics out/in`

This is the smoking gun for loop starvation (RC-5): the mask says **zero** sung bars, lyrics say **53**. The planner still dropped to a rushed 2-bar filter mix 5 seconds out.

**ui3.png — a loop plan, cleared by lyrics only**

- Line 2: `Automix · loop mix in 9 s · out 4-bar, clear by lyrics · in 4-bar, clear by lyrics · 130 BPM`

This is the countdown state the user likes (the crown), and also the false-positive risk (RC-4): “clear by lyrics” with no mask confirmation.

**Design intent (do not throw information away):** keep format, countdown, style, bar counts, BPM / key / grid, why a loop was refused (quiet / sung lyrics / sung mask), and late-step / alignment ms. Present it as a scannable stack or chips, not one wrapping sentence. **djmix2 already shipped** a compact coloured-ring row (style + countdown + tempo + key) with tap-to-expand diagnostics; the user still called UI polish cosmetic and wanted more improvement. **djmix5** should also name the ladder rung, bars, BPM bridge, keys, and alignment ms.

### 4.3 Issue register

| # | User-facing issue | User’s words (abridged) | Root cause (as found or as specified) | Fix claimed | Build | Verified on device? |
|---|---|---|---|---|---|---|
| U1 | Planned loop abandoned mid-countdown | Clip + on-screen “loop mix in 6 s” then “no loop: the decks took too long” | Decks started only 12 s out, 8 s timeout, plan nulled 4 s early (`driveLoopArming`) | Prime immediately; slide to next bar; name the late step | **loopfix** (vc39) | **Yes** — user said the first APK works |
| U2 | Loop skipped even while its countdown is showing | “sometimes it works and not (just skipping it while loop mix alr in countdown, etc) … even when loop countdown alr there” | **Found in djmix2.** Once a loop was on screen, the planner rebuilt it as soon as the start was inside 9 seconds. That second plan required the loop to begin at least 15 seconds ahead, so the bar being counted down no longer qualified. Decks were thrown away and a filter mix took the slot, often without the reason staying on the line | **djmix2:** a loop stays until it plays; a hard failure shifts to the next clean bar; only then a fallback, with the late step and ms overrun on the line and in the log | Claimed in **djmix2**. After djmix2 the user still called mixes “sloppy, lazy, unnatural, stuttery + laggy” | **No** |
| U3 | “Waiting on lyrics” blocks the mix | “also the loop still has issu about: waiting on lyrics (super silly)” | The loop planner refused to run until the lyrics fetch returned. (The later playbook also cites a `outLyrics == null \|\| inLyrics == null` return-null path.) | **djmix2:** prefetch synced lyrics for the next track and the one after as soon as they are queued; until they arrive the vocal mask decides; a lyrics result can *add* a loop, it cannot cancel one already counting or armed. **djmix5** is tightening this further (1.5 s wait only near the 25 s window, and only when no vocal windows and no sticky plan) | Claimed in **djmix2**; being hardened in **djmix5** | **No** |
| U4 | Opening stutter, first 3–4 s of each automixed song | “also it has kinda stutter after every automixed song in the early 3-4 sec of the song.” After djmix2: still “stuttery + laggy” | **Found in djmix2.** Two things hit the new song at handover: (1) the ease back to normal speed stepped the pitch correction every beat and reset the stretch buffer each time, which drops out the audio; (2) the next track’s beat and vocal analysis also started in that same moment | **djmix2:** stretch-buffer reset removed; next-track analysis waits 12 s after the mix settles. User still reported stutter after that build. **djmix5** also aims for incoming already at 1.000× at handover | Claimed in **djmix2**, not signed off | **No** |
| U5 | Song A ends after a split-second overlap | “A only gets like split sec of bar played concurrently with B and B abruptly fulfill the scene” | **Found in djmix2.** A filter was planned as 8 beats (2 bars), and the fader progress jumped ahead when the outgoing file was already near its end, so song A was gone on the first tick. Dual-ExoPlayer blend (RC-1) and missing echo-past-EOF (RC-7) remain architectural | **djmix2:** beatmix 8–16 bars, filter 4–8 bars when the track has room; fade measured from the moment it actually starts; outgoing gain is a continuous curve with no step. **djmix5** still needs the single-clock 4–8 bar blend / drop / echo rungs | Claimed in **djmix2**; user still unhappy after it | **No** |
| U6 | Next song still “analysing…” at mix time | Screenshot ui1: “this song: analysed … next: analysing...” | Single-thread `bitchord-smart-analysis` executor; a whole-track decode of A can block `analyzeHead` of B | Adaptive lead (`PrepLead`, 20–35 s) landed in **djmix**. Dual executors + prefetch of next and next+1 are being written in **djmix5** | **djmix5** (in progress) | **No** |
| U7 | Loop refused because lyrics say “sung” when the mask says not | Screenshot ui2: “65 loops: 12 quiet, 53 sung (lyrics), 0 sung (mask)” then “filter mix in 5 s · 2 bars” | Lyric line ends that sit on the next line were marking the whole gap as sung (53 of 65 incoming loops, mask found none sung) | **djmix2:** those gaps are no longer certain singing; word ends are used when they exist; the mask still rejects a bar that is actually sung. **djmix5:** `LYRIC_OPEN_LINE_SEC = 1.5`, lyrics are a veto when vocal windows exist (`MAX_SUNG_PEAK = 0.22`), plus `OUTRO_LOOP_DROP` so a refused dual-loop does not collapse to a 2-bar filter chop | Partial in **djmix2**; rest in **djmix5** | **No** |
| U8 | Pitch / speed explosion (100× / 999×) | After djmix2: “wow the insane forced beatmatching thing (that make the pitch and tempo goes insane ike 100x still existing in latest build, djmix2)”. After djmix3: “the weird ass pitch tempo thing (it sped up to 999x) still happens. lot of problems still happen too.” | The automix2 pitch corrector could treat a normal audio block as a huge time-stretch (spreading one spectrum across later blocks), or shift pitch on audio already at the right length, so the stretcher saw ~1000×. Rates and raw BPMs were not hard-capped | **djmix3** tried a 0.5–2× / ±6–9% cap and stopped the corrector spreading one spectrum across later blocks — **not sufficient**. **djmix4:** corrector disabled; any rate outside 0.5–2× plays at the song’s own tempo; stored speeds outside 0.25–3× reset to 1×; a separate +6% buildup (max 1.15×) for EDM-style rises | **djmix4** (vc46) | **No** (shipped, not signed off) |
| U9 | Dense Automix debug line | “make this ui more delivered neatly without decreasing its info”; after djmix: “ui for that needs better design”; after djmix2: UI is “cosmetic”, “need improvement still” | One wrapping sentence, then a longer djmix line (style, sections, Camelot, effect) that was still hard to scan | **djmix2:** one compact line with a coloured ring for the style (loop, blend, filter, echo, roll, reverb), countdown filling that ring, tempo and key; tap opens the full diagnostic (loop counts, what cleared them, late step). Only that row reads the countdown, so the rest of the player is not rebuilt every second. **djmix5** should also name the ladder rung, bars, BPM bridge, keys, alignment ms | **djmix2** shipped the chip/row; user still called it needing improvement | **No** |
| U10 | Official-app data not in the fork | Raised once, then deprioritised: “dont overthink abt import from official bitchord, ive alr make up my mind” | Different signing key; no `sharedUserId` | Partial importer committed (`d05af9f`); no further work unless asked | Stopped by the user | n/a |
| U11 | Reverb / filter wash on almost every mix | “it loves to reverb wash/muffle things on everything too fast and too mcuh like sometimes it fits but lot of time it wastes the potential” | Effects were applied too often and too early (djmix defaults: plate wet 0.22, flanger on up to 1 in 5 blends) | **djmix3:** filter stays open through the first part of the fade and stops at 900 Hz; reverb only opens in the last quarter, quieter, and only on the song that is leaving; flanger or a one-beat echo only on an instrumental blend (wet 0.42); two singers do not get the flanger | **djmix3** | **No** |
| U12 | Want a graceful EDM buildup, not a runaway | “also learn to be graceful; edm song do can have extra pitch up and speed up to create a build up etc like flangers with it” | The 999× path was a runaway, not a buildup | **djmix4:** on a pre-chorus, a rising instrumental or bridge, or a climbing chorus, into a chorus, riff, or intro, the incoming song eases up by 6% across the overlap and the flanger opens with that rise. Two singers, a verse, and an outro do not get it. After handover, speed walks back over ~6.5 s. Hard cap 1.15× (nudge + rise) | **djmix4** | **No** |

### 4.4 Engineering defects specified for djmix5 (RC-1 … RC-7)

These are the playbook’s verified code-level causes, meant to be eliminated in the current cloud-agent round. They overlap the user issues above.

1. **RC-1** — Non-loop transitions flam, gallop, or drift: two ExoPlayers vs one native clock.
2. **RC-2** — Chipmunk / slurred vocals and a post-mix tempo lurch: incoming rate cap too wide, `beginEase` after handoff. (Related to U8.)
3. **RC-3** — Beat grid off the kick; loop wraps click; 22 s window seams flip downbeat phase: naive `seamBeats`.
4. **RC-4** — “clear by lyrics” can loop a sung hook (ui3).
5. **RC-5** — “53 sung (lyrics), 0 sung (mask)” → 2-bar filter chop (ui2).
6. **RC-6** — “next: analysing...” up to the transition (ui1); lyrics wait.
7. **RC-7** — Abrupt outgoing EOF; mud / loudness dip / clipping; echo disabled; no limiter.

---

## 5. Build history

All of these are `com.music.bitchord.loopfix`, arm64-v8a only, signed with the same local certificate `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`. Hashes and sizes were re-checked on the box on 11 Nov 2026. Timestamps are artifact mtimes in **Asia/Jakarta**.

There is **no shipped APK with `versionCode` 40**. Official BitChord 1.9.1 is 39; the first fork build kept 39 (safe because the package id changed). The next shipped build jumped to 41. An official-backup-import run was supposed to produce an APK and was stopped before it did.

| Name | versionCode | versionName | SHA-256 | Bytes | Built (Asia/Jakarta) | Box path | Cloud artifacts | Drive |
|---|---|---|---|---|---|---|---|---|
| **loopfix** | 39 | `1.9.1` | `90b2057daa7075b57f1347c773248a710d421b0727075c6ce102d5541e0eab64` | 86,939,994 | 9 Nov 2026, 18:26 | `/workspace/apk/BitChord-loopfix-1.9.1-arm64-v8a.apk` | `/cursor/stores/bc-10f3ffd2-4eec-569e-aafe-8bd118b340fe/artifacts/` (same name) | Not uploaded (was split into 20 MB parts for chat; obsolete) |
| **automix** | 41 | `1.9.1-automix` | `954448de718430db54efbe538bef97c4cdd1448309a5d94c7029d5bc510c1c9e` | 86,942,762 | 10 Nov 2026, 20:48 | (artifacts only; not copied into `/workspace/apk*`) | `…/BitChord-loopfix-1.9.1-automix-arm64-v8a.apk` | No |
| **automix2** | 42 | `1.9.1-automix2` | `09cdcb1cd452ccb0d138ca32c72bbde0b2905b64b777565af0d1f669840dbdde` | 87,368,832 | 10 Nov 2026, 21:05 | `/workspace/apk2/BitChord-loopfix-1.9.1-automix2-arm64-v8a.apk` | same name in artifacts | No (chat parts) |
| **djmix** | 43 | `1.9.1-djmix` | `6c40dbdc50e6d42b740c8a8b480ec987487a8584036ec32158f9b26ec28e7228` | 87,401,600 | 10 Nov 2026, 21:22 | `/workspace/apk3/…-djmix-arm64-v8a.apk` | artifacts | No (chat parts) |
| **djmix2** | 44 | `1.9.1-djmix2` | `f9d00798d2cf6c6d356285d95a89cedb872aa153727b5e989ae1693049e99f5d` | 87,417,988 | 10 Nov 2026, 21:55 | `/workspace/apk4/…-djmix2-arm64-v8a.apk` | artifacts | No (chat parts) |
| **djmix3** | 45 | `1.9.1-djmix3` | `e402cbb7c6ea83d3e3f06233384c729caaa48fc85ee7061c3223405de0a45f8a` | 87,417,984 | 10 Nov 2026, 22:42 | `/workspace/apk5/…-djmix3-arm64-v8a.apk` | artifacts | No (chat parts) |
| **djmix4** (current install target) | 46 | `1.9.1-djmix4` | `3e165a7464e284c13fde05c9070c128b69f9ddb44ec9bfc068ce204500c2ac1a` | 87,417,984 | 10 Nov 2026, 23:07 | `/workspace/apk6/…-djmix4-arm64-v8a.apk` | artifacts | **Yes** — [BitChord-djmix4.apk](https://drive.google.com/file/d/1opqdJulQo-BrKEsxBaB3mvPsjGtxoJE_/view?usp=drivesdk) (Drive id `1opqdJulQo-BrKEsxBaB3mvPsjGtxoJE_`, uploaded 11 Nov 2026, 08:38 Asia/Jakarta) |
| **djmix5** | planned **47** | planned `1.9.1-djmix5` | — | — | in progress on the cloud agent (target name `BitChord-loopfix-1.9.1-djmix5-arm64-v8a.apk`) | — | **not present** in artifacts as of 11 Nov 2026, ~10:15 Asia/Jakarta | — |

### 5.1 What each build changed

**loopfix (vc39).** First product. Root-cause fix for U1 (early deck timeout). Package rename, label “BitChord loop fix”, official self-update disabled, arm64-only. Tests added for next-bar shift, refused sung bars, phrase snap, window exhaustion, late-step message. Locale-string test repaired by copying four missing English strings. **Not device-tested by the agent**; the user later confirmed this APK worked. Files: `LoopMix.kt`, `LoopMixTest.kt`, `CrossfadeController.kt`, `TrackAnalyzer.kt`, `PlaybackService.kt`, `AppUpdateChecker.kt`, `app/build.gradle.kts`, `settings.gradle.kts`, label strings.

**Official-backup import (no APK).** Mid-run crash, then a resume, then the user changed priority. Commit `d05af9f` on `main` remains. Import accepts a v1.9.1 official JSON backup (`app` tag `bitchord` or `com.music.bitchord`) and remaps package paths, file-provider URIs, and component ids. Entry points: welcome screen and Settings → Your data. 1,263 app unit tests and 127 shared tests passed in that run. No extra opportunistic bug fixes. **Do not extend this** unless the user asks.

**automix (vc41).** First “all in on bkmasterplayer” increment, deliberately small. Ported Camelot wheel and “same number, or one step on the same ring” from bkmasterplayer `engine/analysis.rs` (MIT, notice in `THIRD_PARTY_NOTICES.md`) into `HarmonicMix.kt`. Key-clash advanced mixes are shortened and filtered; the line can say `camelot 8B→2B clash`. A wide interval-spread beat list is not stretched (`drifting beat grid`). Bass swap / filter / echo / blend styles were already in BitChord and were not rewritten. Loop timing, lyrics, and tempo checks unchanged. Tests: 1,263 app (1 skipped) + 131 shared, passing. **Not done here:** Signalsmith on non-loop paths, multi-region fitter, echo-on-the-bar.

**automix2 (vc42).** The real mixer increment, with the loop-first constraint locked in. From bkmasterplayer, without touching a valid loop: (1) piecewise beat fitting so a steady run at the join can still lock; (2) echo on the outgoing bar when the tempo ratio is outside 0.9–1.25, with plain filter only inside that window when the pair is not a blend; (3) Signalsmith, already vendored for loops, also on the blend path — playback rate still set the timeline, and an equal-length `1/tempo` transpose in the sink was used to cancel AudioTrack resample pitch (`libbitchord_stretch.so` appears in this APK). A 440 Hz sine test: 410.3 Hz vs 407.4 expected at 1.08×, 480.0 vs 478.3 at 0.92×. Signalsmith micro-benchmark on the **cloud VM** (not a phone): 48 kHz stereo, tempo 1.08, 8 s of audio at **129× realtime**. Tests: 134 shared + 1,263 app, 1 skipped, including a drifting-click case, the pitch check, and a regression that a lyrics-clear 4-bar pair **stays a loop** when keys clash and the grid drifts. **Agent warning, later vindicated by U8:** “If this float route already preserves pitch, the new transpose would shift it the wrong way.”

**djmix (vc43).** First implementation of the research dossier. The agent's completion report (transcript line 1821): Signalsmith and the umxhq vocal mask were already in the tree, so this build planned and coloured the mix with those instead of adding another model. The earlier note that BitChord only changes playback rate was wrong for the *loop* path; a beat-matched blend still uses playback rate for the timeline, with a second Signalsmith instance cancelling the pitch that rate adds. NNAPI, madmom models, umxl weights, and AI_DJ code are not in the build.

Implemented, from that report:

- Section labels from repeated synced lyric lines, energy, and intra-track self-similarity: intro, verse, pre-chorus, chorus, bridge, instrumental, outro. Each track is labelled from its own structure. The two songs are compared only by BPM, Camelot key, one energy number, and one vocal-share number (patent-safe).
- Planner under the loop: a phrase beatmix with bass swap, a short filter on a Camelot clash, then echo, loop roll, or reverb when the tempo is past the stretch limit, then the existing crossfade and filter mix. A cue that would skip a verse is pulled back when lyrics are present. A short lyric-free outro can repeat for 4 bars. Two singers with masks on the overlap get a vocal handoff (a mid cut from the umxhq mask, not a second stem model).
- Effects, with fixed defaults: echo on the bar (feedback 0.55), loop roll that halves from 1 beat, flanger (wet 0.28, 8-beat LFO, at most one blend in five), a small plate (wet 0.22), and the existing filter sweeps. Nothing is allocated in the audio callback after configure.
- Adaptive arm lead from 20 s to 35 s when preparation takes longer than 8 s (`PrepLead`). Loop decode still starts as soon as the plan exists.
- The Automix line shows the style, the outgoing and incoming section, the Camelot relation, and the effect.

Tests: shared JVM 143 passed; app 1,263 passed, 1 skipped; loop-first regression still present. Signalsmith micro-benchmark on the VM: 130× realtime at 48 kHz stereo, tempo 1.08 (same sine-pitch numbers as automix2).

Deferred in that report: real vocal-over-instrumental rendering (mix minus umxhq, or windowed htdemucs); a per-region key detector; a true-peak limiter and a separate LUFS match; a 25 ms phase gate; thermal fallback to a smaller beat model; rating buttons; a second stretch engine.

Main files touched: `CrossfadeController.kt`, `TransitionPlanner.kt`, `TransitionFilterProcessor.kt`, `PlaybackService.kt`, `TrackAnalysis.kt`, `VocalTracker.kt`, `HarmonicMix.kt`, `LoopMix.kt`, `BeatTracker.kt`, `AutomixPerformanceMode.kt`, `PrepLead.kt`, plus tests `DjMixTest.kt`, `PhraseBarAutomixTest.kt`, `LoopMixTest.kt`, `HarmonicMixTest.kt`, `AdvancedAutomixPlannerTest.kt`.

**This is the build the user listened to** when they filed U2–U5, U7 and the UI complaint. Not verified on a device by the agent (flanger / plate / loop-roll sound, whether this phone's AudioTrack already preserves pitch, section labels on real songs, whether the longer arm lead is enough for a slow stream).

**djmix2 (vc44).** Fix round for the four field failures, plus the Automix line redesign. Completion report at transcript line 2139:

- **Loop dropped during countdown (U2).** Root cause as in the issue register (9 s replan vs 15 s lead). A loop now stays until it plays; hard failure shifts to the next clean bar; only then a fallback, with the late step and ms overrun on the line and in the log.
- **Lyrics (U3, U7).** Prefetch next and next+1; plan with the mask until lyrics arrive; lyrics can add a loop, not cancel one already counting or armed. Gaps between lyric lines are no longer certain singing; word ends are used when they exist; the mask still rejects a sung bar.
- **Opening stutter (U4).** Stretch-buffer reset on every beat of the ease-back is gone. Next-track analysis waits 12 s after the mix settles.
- **Short overlap (U5).** Beatmix 8–16 bars, filter 4–8 bars when there is room; fade measured from the moment it actually starts; outgoing gain is a continuous curve with no step.
- **Automix line (U9).** One compact row: a coloured ring for the style (loop, blend, filter, echo, roll, reverb), countdown filling that ring, tempo, and key. Tap it for the full diagnostic (loop counts, what cleared them, late step). Only that row reads the countdown, so the rest of the player is not rebuilt every second.

Tests: shared 147 passed; app 1,263 passed, 1 skipped; loop-first regression still there. Main files: `CrossfadeController.kt`, `TransitionPlanner.kt`, `LoopMix.kt`, `PlayerControls.kt`, `PlayerHost.kt`, `AndroidPlayerHost.kt`, `AppSettings.kt`, `LyricLine.kt`, `native/stretch/pitch_stretch.cpp`, plus planner/loop tests.

Not verified on a phone, and the user did **not** sign this off: after installing a later iteration they still wrote that even after djmix2 it was “sloppy, lazy, unnatural, stuttery + laggy”, and that the 100× forced beat-match was “still existing in latest build, djmix2”.

**djmix3 (vc45).** Maturity / wash / rate-cap round. After djmix2 the user asked for a more robust, song-aware mix (instrumental, vocal, chorus / pre-chorus / riff / bridge / intro / outro) rather than more UI, and complained that it “loves to reverb wash/muffle things on everything too fast and too mcuh”. They also pointed at [VertexSelection/automix-engine](https://github.com/VertexSelection/automix-engine), LastWave-Native, AutoMixSuite, Convx, and (as a reference only) Orchard. Commit message: “Keep beatmatching inside a small nudge and mix on the phrase.” Completion report at transcript line 2626:

- A rate like 100×, a raw BPM, or anything outside half-speed to double-speed now plays at the song’s own tempo. A real nudge stays between 6% slow and 9% fast (so 111 against 120 still meets). The song walks back to its own speed over about 6.5 seconds in four steps instead of a click on every beat. The cap follows LastWave’s stretcher (±6%) and automix-engine’s 6.48 s lock release.
- A local beat grid more than 15% off the song’s own tempo is ignored, including a half-time or double-time count. The loop-mix deck refuses a knot rate outside 0.5–2. The pitch corrector uses the same window and no longer spreads one spectrum across later audio blocks (the first attempt at U8).
- Filter stays open through the first part of the fade and stops at 900 Hz. Reverb only opens in the last quarter, quieter, and only on the song that is leaving. An instrumental blend can flange (wet 0.42, over the last several bars) or throw a one-beat echo that leaves the dry signal in place. Two singers do not get the flanger. When synced lyrics say the exit is inside a verse, pre-chorus, chorus, bridge, or riff, the mix waits for the end of that phrase. Vocal windows count for the handoff when the mask array was never stored. Loop mix still wins first.

Tests: shared passed; app 1,263 passed, 1 skipped. Sound unverified on a phone. **The 999× burst still happened after this build** (user: “it sped up to 999x still happens”).

Main files: `CrossfadeController.kt`, `TransitionPlanner.kt`, `TransitionPolicy.kt`, `DjColour.kt`, `DjMix.kt`, `LoopMix.kt`, `SongSections.kt`, `TrackAnalysis.kt`, `HarmonicMix.kt`, `TransitionFilterProcessor.kt`, `LoopMixRenderer.kt`, `LoopMixSession.kt`, `PlaybackService.kt`, `AppSettings.kt`, `native/loopmix/loop_mix_renderer.cpp` / `.h`, `native/stretch/pitch_stretch.cpp` / `.h`, and tests. The run also edited the vendored `native/third_party/signalsmith-stretch/signalsmith-stretch.h` (and `signalsmith-linear/stft.h`) — treat those as sensitive; do not casually rewrite upstream Signalsmith.

**djmix4 (vc46) — current shipped.** The 999× burst was still in djmix3. Completion report at transcript line 2950; commit message: “Stop the thousand-times tempo runaway and add a real buildup.”

- The pitch corrector was shifting pitch on audio that was already playing at the right length, and that made the stretcher treat one block as about a thousand times faster. **That corrector is off.** A deck speed of 999, or any rate outside half-speed to double-speed, now plays at the song’s own tempo. A stored listening speed outside 0.25×–3× is saved as 1×.
- A buildup is a separate, small gesture (U12): on a pre-chorus, a rising instrumental or bridge, or a chorus that is still climbing, into a chorus, riff, or intro, the incoming song eases up by 6% across the overlap and the flanger opens with that rise. Two singers, a verse, and an outro do not get it. When the new song is on its own, speed walks back over about 6.5 seconds. The most a mix can play is 1.15× (nudge plus that rise).
- The rest of the mix is unchanged in intent from djmix3 (loop first, 15% local-grid ignore, filter/reverb/flanger rules, wait for the end of a phrase).

Tests: shared passed; app 1,263 passed, 1 skipped. Sound unverified on a phone. This is the APK in Drive and the baseline named by the iOS 27 playbook. The playbook still lists RC-1…RC-7 as open in this binary.

Main files: `CrossfadeController.kt`, `SongSections.kt`, `LoopMix.kt`, `DjMix.kt`, `TransitionPlanner.kt`, `TransitionPolicy.kt`, `TransitionFilterProcessor.kt`, `LoopMixSession.kt`, `LoopMixPort.kt`, `PlaybackService.kt`, `AppSettings.kt`, `PlayerControls.kt`, `native/loopmix/loop_mix_renderer.cpp`, `native/stretch/pitch_stretch.cpp`, `DjMixTest.kt`, and again the vendored Signalsmith header.

**djmix5 — in progress (no APK yet).** See Section 5.3.

### 5.2 Cloud-agent run notes

- The agent is a **new_repo Origin draft** on `main`, slug **`hf-zh/tmp-1a194b77b9f0f8dd`**. Git remote: `origin.cursor.com/git/hf-zh/tmp-1a194b77b9f0f8dd`. There is no GitHub mirror and no PR URL to invent.
- Follow-ups must be `reply` to this agent (queue if a run is in flight, steer to course-correct, interrupt only if the user wants work thrown away).
- The user has also started runs **directly on the agent page**. Completions of those arrive as “You did NOT start this run”. An aborted duplicate was recorded as **run 10**; stay silent on aborted duplicates unless asked. Runs 11 and 12 were user-started in the earlier chat.
- APK retrieval: completion-copy of the APK back to the coordinator often fails because the file is too big. Copy from `/cursor/stores/bc-10f3ffd2-4eec-569e-aafe-8bd118b340fe/artifacts/` and `sha256sum` it.
- The on-disk transcript at `/workspace/cloud-agent-transcripts/bc-10f3ffd2-4eec-569e-aafe-8bd118b340fe.jsonl` is **3,213 lines** and covers the first prompt through **mid-implementation of djmix5** (last thinking still drafting `LoopMix.bridge` helpers and about to run Gradle; no djmix5 APK yet). Completion reports used in Section 5.1 sit at lines 1821 (djmix), 2139 (djmix2), 2626 (djmix3), and 2950 (djmix4).

### 5.3 djmix5 progress (in flight, as of the 3,213-line transcript)

The playbook was adapted to this tree: skip Phases 1, 2 and 6; implement Phases 3–5 on the djmix4 source; keep djmix4 fixes (armed-loop persistence, lyric prefetch, stutter-buffer fix, pitch-corrector disable, rate clamps, UI row) unless the playbook supersedes them. Planned APK: `BitChord-loopfix-1.9.1-djmix5-arm64-v8a.apk`, `versionCode` **47**, same signing key, arm64 only. No artifact exists yet. The agent has **not** finished compiling or packing a release. Last recorded step was still writing planner/renderer code and preparing Gradle.

**Being written into the working tree** (files actually edited in this run: `TrackAnalyzer.kt`, `CrossfadeController.kt`, `LoopMix.kt`, `LoopMixTest.kt`, `loop_mix_renderer.cpp` / `.h`, `loopmix_jni.cpp`, `TransitionPlanner.kt`, `LoopMixSession.kt`, `LoopMixRenderer.kt`, `TrackAnalysis.kt`, `HarmonicMix.kt`, `build.gradle.kts`):

- Native renderer: 8 ms equal-power seam crossfade in `Deck::read`; RBJ low-pass biquad exposed as JNI envelope kind 3 (a full Cytomic TPT SVF bank was planned; what landed is this RBJ low-pass on the existing biquad); echo stage on the outgoing deck (`setEcho`); 1.5 ms lookahead limiter after the mix, with latency subtracted from `firstFrame`; a quality / `presetDefault` flag (`setQuality`).
- Planner: `LoopMix.bridge` with `dropPlan`, `blendPlan`, `echoPlan`. New `Plan` fields include `rung`, `outLoops`, `inLoops`, `nativeIncoming`, `echoTail`, `carve`, `clash`, `keys`, `totalBars`. Decision order after a loop fails: grid checks, then outro drop, then beat blend, then echo, else null (crossfade floor). Drop is gated to “outgoing clean, incoming not”. Beat blend ramps to the incoming tempo before handover and carves when both vocal scores exceed 0.25. Drop and echo keep the incoming song at 1.000×. Echo can run with only one valid grid (no stretch). Lyrics are a veto when vocal windows exist (`MAX_SUNG_PEAK = 0.22`); `Check.LYRICS` remains only if windows are empty, so existing lyrics-only tests still pass. `LYRIC_OPEN_LINE_SEC` tightened to 1.5 s. A 1.5 s lyric wait runs only inside the last 25 s, only when there is no sticky plan and no vocal windows. Soft loop refusals fall through to `nativeRung` instead of a 2-bar filter chop. `nativeRung` is skipped for gapless and hard refusals.
- Analysis: dual executors — head tasks on a `headExecutor`, full-track work on the existing executor, shared running set, `requestAnalysis` for next+1. `phaseCoherence` defaults to −1.0 (unknown, passes gates). A `GridRepair` helper (MIT notice for the bkmasterplayer idea) was being added for downbeat-phase seam reconciliation from beat times only. Full spectral-flux onset snapping needs PCM and was not finished.
- UI: the status headline is being switched to the plan’s rung name (e.g. “Beat blend”) rather than always “Loop”. Richer bars / BPM-bridge / alignment-ms layout is not confirmed as landed.
- Album `GAPLESS` for sequential same-album tracks is **already in the tree** from earlier work. Acoustic-segue detection (spectral centroid) was **explicitly deferred**, to avoid mixing two unrelated loud songs with a half-implemented check.
- Camelot clash during a shared loop: a gentle outgoing LPF at 2.8 kHz and HPF at 380 Hz was being wired.

**Still pending** (not done, or only planned, when the dump ended):

- Signed vc47 APK, `./gradlew` release build, and a commit on `main`.
- Confirming the new code compiles and that existing unit tests still pass; new tests for lyrics veto, bridge, and grid repair were being drafted.
- True regional LUFS match; a full TPT SVF filter bank (vs the RBJ low-pass that is going in).
- PCM onset snapping (`snapToOnsets` as a pure function was designed; wiring from real audio was incomplete).
- Acoustic gapless segue; a complete SMART_CROSSFADE silence-trim path (the headline rename to “crossfade” was discussed).
- End-to-end proof that every non-loop rung actually renders on the single native clock (`BEAT_BLEND` / drop / echo are being modelled as synthetic `LoopMix.Plan` objects and driven through the existing loop arming path — the intent is right, the wiring was unfinished).
- `holdLoop` KEEP can still preserve a loop that later-arriving lyrics would have vetoed — the agent flagged this residual risk and had not closed it.
- Device listening, as always.

When the APK appears in artifacts: verify SHA and `versionCode ≥ 47`, upload to Drive, `adb install -r` on the phone, keep a copy under `/workspace/apk7/` (or the next free slot).

---

## 6. References and ideas

### 6.1 Documents already on the box

| File | What it is |
|---|---|
| `/workspace/automix-research.md` | Research dossier, 10 Nov 2026. Apple timeline, open-source survey, recommended on-device design, licence/patent notes, P0–P2 roadmap. **This is the design spec the first djmix build was asked to implement.** |
| `/workspace/apple-automix-wiki.md` | Apple Music AutoMix technical wiki, 30 Oct 2026, evidence-graded. Attached to the cloud agent as food for thought mid-automix2. |
| `/workspace/ios27-automix-playbook.md` | “Gemini 4 Carbon” execution playbook for **djmix5**: seven root causes, 6-rung ladder, file-by-file blueprint, verification gates (gates are to be skipped per the user). Same text as the 11 Nov attachment. |
| `/workspace/ui1.png`, `ui2.jpg`, `ui3.png` | User’s Automix-line screenshots (Section 4.2). |
| `/workspace/bitchord-clip.mp4` | Original ATEEZ “Adrenaline” bug recording (U1). |
| `/workspace/research/` | Shallow clones used while writing the dossier (upstream BitChord, bkmasterplayer, beat_this, open-unmix, Signalsmith, opendj, NullMusic, etc.). These are **references**, not the fork. |
| `/workspace/rea/` | Local `morluto/rea` checkout from earlier research. |
| `/workspace/android-skills.zip` | Android / Compose performance skills pack already given to the cloud agent. |

### 6.2 Repositories (licence-checked in the dossier / playbook)

**Use (GPL-ok for this fork):**

| Project | Licence | Why it matters |
|---|---|---|
| [kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord) | GPL-3.0-or-later (AGPL parts) | Upstream |
| [thieggs/bkmasterplayer](https://github.com/thieggs/bkmasterplayer) | MIT | The mobile automix “powerhouse” the user said to go all in on. Ports already started from `engine/analysis.rs`, `automix.rs`, `mixer.rs`. Defensive planner, multi-region grid, 25 ms phase gate, 2:1 handling, echo-on-bar with tail, music-end rule, ±8% stretch cap, LUFS ideas |
| [morluto/rea](https://github.com/morluto/rea) | MIT | RE skill the user named. Useful for APK / `.so` evidence. **Skipped for djmix5** because the draft repo already has the source |
| [VertexSelection/automix-engine](https://github.com/VertexSelection/automix-engine) | LGPL-2.1 | Regional LUFS and vocal-safe cue concepts. The user called this “one excellent gitrepo alongside bkmasterplayer for a well established automix.” Its ~6.48 s lock release informed the djmix3 walk-back |
| Clash-Projects/LastWave-Native, cosmictaserdev-creator/Convx, congnghetinhtu/AutoMixSuite, JohanDevl/opendj (and [opendj at interdome](https://git.interdo.me/interdome/opendj)) | GPL-3.0 | Playbook-allowed references. **opendj** is the best open reference for vocal-clash avoidance and DJ effects (documented thresholds; HTDemucs is desktop-only) |
| [heydavidf/automix](https://github.com/heydavidf/automix) | AGPL-3 | Ancestor of BitChord’s loop mix |
| [NullCoreDeveloper/NullMusic](https://github.com/NullCoreDeveloper/NullMusic) | GPL-3 | Adaptive pre-arm lead time (20–35 s, grows when prep exceeds 8 s) |
| Mixxx AutoDJ | GPL-2+ | Intro/outro cue model; not rhythm-aware, but robust |
| [charbelmalo/opensonic](https://github.com/charbelmalo/opensonic) | MIT | Post-render verification gates (onset correlation, flutter, loudness, stem clash) — ideas only |
| [CPJKU/beat_this](https://github.com/CPJKU/beat_this) / [danigb/beat-this-rs](https://github.com/danigb/beat-this-rs) | MIT | Beat model already shipped; small model is the thermal fallback |
| [sigsep/open-unmix-pytorch](https://github.com/sigsep/open-unmix-pytorch) | MIT code; **umxhq MIT**, **umxl NC** | Vocal mask already shipped |
| [Signalsmith-Audio/signalsmith-stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) | MIT | Already vendored; keep as default stretcher. `presetDefault` vs `presetCheaper` is a djmix5 quality switch |
| [breakfastquay/rubberband](https://github.com/breakfastquay/rubberband) | GPL-2+ | Optional later HQ engine (P2) |
| SoundTouch | LGPL-2.1 | Ultra-low-end fallback only |
| [bungee-audio-stretch/bungee](https://github.com/bungee-audio-stretch/bungee) | MPL-2.0 | Worth A/B later; loops/reverse are easy |
| [mixxxdj/libkeyfinder](https://github.com/mixxxdj/libkeyfinder) | GPL-3 | Per-region key (P1) |
| [MTG/essentia](https://github.com/MTG/essentia) | AGPL-3 | Take algorithms, not the whole library |
| [urinieto/msaf](https://github.com/urinieto/msaf) | MIT | Cheap structure algorithms to port |
| [facebookresearch/demucs](https://github.com/facebookresearch/demucs) | MIT | Windowed, opt-in, 8 GB+ only (~2.2 GB RAM). Not real-time on a mid phone |
| libebur128 | MIT | LUFS / true peak |

**Do not use (licence or rights):**

- [CPJKU/madmom](https://github.com/CPJKU/madmom) **models** (CC BY-NC-SA). Code is BSD; do not ship the models.
- open-unmix **umxl** weights (CC BY-NC-SA).
- [SamAfshar2011/AI_DJ](https://github.com/SamAfshar2011/AI_DJ) — proprietary, all rights reserved. Design ideas only; **do not copy code**.
- Current `SFG5453/Orchard` `crates/orchard-adaptive-mix` under **PolyForm Shield 1.0.0**. Older AGPL Orchard-derived code already inside BitChord may be kept and modified under GPL-3.0+.
- NNAPI (deprecated from Android 15). Use XNNPACK / CPU EP; QNN is optional on Snapdragon.

**Research papers / stats (ideas, not code dumps):** Kim et al. ISMIR 2020 djmix-analysis; DJtransGAN (arXiv 2110.06525) for EQ/fader templates; dnb-autodj / AIDA Ghent for structure-aware planning.

### 6.3 The user’s own ideas (and what not to over-claim)

- Be all-in on **bkmasterplayer** and the **morluto/rea** skill, while staying performant.
- Loop mix is the crown and must stay first.
- The finish line is a **DJ-level automix at Apple iOS 27 AutoMix parity**: several bars of concurrent, natural blend; nothing abrupt.
- Keep the Automix line’s **information density**, but present it neatly. After using the djmix build, they said the UI still needs a better design.
- After djmix2 they pointed at more automix players to learn from: [VertexSelection/automix-engine](https://github.com/VertexSelection/automix-engine), [Clash-Projects/LastWave-Native](https://github.com/Clash-Projects/LastWave-Native), [congnghetinhtu/AutoMixSuite](https://github.com/congnghetinhtu/AutoMixSuite), [cosmictaserdev-creator/Convx](https://github.com/cosmictaserdev-creator/Convx), plus a GitHub search for “automix music”. They also sent [SFG5453/Orchard](https://github.com/SFG5453/Orchard) — **do not copy current Orchard PolyForm Shield code** (Section 7). LastWave’s ±6% stretcher cap and automix-engine’s 6.48 s lock release were cited in the djmix3 report.
- They want a **graceful EDM buildup** (small pitch/speed rise and flanger), not a runaway, and they do not want reverb/filter wash on every mix.
- They approved **Google Drive** for APK delivery after split files failed on Android.
- They have **adb** on the phone now and want APKs installed that way, without building on the PC.
- They explicitly do not want more energy spent on official-app import.

Apple-inspired product rules the research recommended, which the user did not contradict: skip classical / opera / spoken word; do not mix inside an album; repeat a clean phrase to extend a short intro or outro; rotate effects; show a mixing indicator; never skip a verse that has lyrics unless the user opts into a “radio edit”.

---

## 7. Constraints

### 7.1 Licence

The fork is GPL-3.0-or-later and already contains AGPL files. Anything you add must be GPL-compatible. Keep `THIRD_PARTY_NOTICES.md` and file headers up to date (especially MIT notices from bkmasterplayer and Signalsmith). Forbidden sources are listed in Section 6.2.

### 7.2 Patent caution — US 8,280,539 (not legal advice)

Spotify / ex-Echo Nest patent, **active until 2031-03-11**. It claims picking a transition window by **sliding-window comparison of both tracks’ descriptor sequences**. bkmasterplayer deliberately avoids this; BitChord’s planner must too:

- Choose track A’s exit **only** from A’s own structure, and track B’s entry **only** from B’s own structure.
- Score a pair with **scalars only**: BPM ratio, phase offset at an anchor, Camelot distance, a scalar vocal-activity share, a scalar regional LUFS, energy jump, “cuts a lyric line”, and so on.
- **Never** run a sliding-window similarity search across both tracks’ frame-by-frame descriptor sequences.

If this fork is ever distributed in the US, get a legal review rather than relaxing this rule.

### 7.3 Signing and versioning

- Always the same local signing key (certificate SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`).
- Always increase `versionCode`. Next must be **≥ 47**. Official BitChord is a different package, so it will not collide, but the fork must update over itself without uninstalling (settings stay).
- Do not commit the keystore.
- Verify with `apksigner verify --verbose` and `sha256sum` before delivery.

### 7.4 Where work may run

- **User PC** (`77890ea2-5034-4d18-8649-e40e1f18541d`): low-end. **Never** install Android / NDK / Gradle toolchains or build the APK there. Allowed: `adb` (already at `~/.local/bin/adb`), copying the APK, installing, logcat if the user wants.
- **Coordinator box** (this machine): delivery, hashing, Drive upload, adb orchestration, docs. Not the place to rebuild the app if the cloud agent can do it.
- **Cloud agent VM**: all repo work and release builds.
- **InstaBox / Android 14 x86_64 emulator:** described in the playbook; the user said to **skip** that for djmix5. If a future dual-ABI (`arm64-v8a` + `x86_64`) build is needed, that is a later, explicit decision — phone APKs so far are arm64-only to keep size down.

---

## 8. Delivery and operations

### 8.1 Standard release path

1. Cloud agent writes to the Origin draft and drops a signed APK in its artifacts folder: `/cursor/stores/bc-10f3ffd2-4eec-569e-aafe-8bd118b340fe/artifacts/`.
2. Copy it to a versioned folder under `/workspace/apkN/`.
3. `sha256sum` and record bytes, `versionCode`, `versionName`.
4. Upload the **single full APK** to the user’s Google Drive with `UploadFile` on connection `user-Google-drive` (plugin `45893413`, already installed and authed). Send the user the one Drive link. Drive allows multiple files with the same name; prefer a clear name like `BitChord-djmix5.apk`.
5. If the user’s Linux PC is online and the phone is authorized, also copy the APK to that PC and:

   ```bash
   ~/.local/bin/adb install -r /path/to/BitChord-….apk
   ```

   `-r` replaces the existing fork and **keeps settings**.

**Do not** split APKs into 20–22 MB chat parts any more.

> User: “I'm on Android rn and can't run the cat thing”
> Then: “So I approved u to @gdrive Upload the result there”
> Then: “Conectado Google-drive.”

Only **djmix4** is on Drive today: <https://drive.google.com/file/d/1opqdJulQo-BrKEsxBaB3mvPsjGtxoJE_/view?usp=drivesdk>.

### 8.2 Phone and adb

As of 11 Nov 2026, after the user tapped Allow on the USB debugging prompt:

| Item | Value |
|---|---|
| User Linux PC | machine id `77890ea2-5034-4d18-8649-e40e1f18541d` |
| adb | `~/.local/bin/adb` on that PC (installed there when the user said “install it”; it was not previously on PATH) |
| Phone | Xiaomi **24069PC21G**, serial **1439ebea**, USB |
| Packages present | official `com.music.bitchord` **and** `com.music.bitchord.loopfix` |

If adb is unauthorized again: unlock the phone and tap Allow on “Allow USB debugging?”, preferably with “Always allow”.

### 8.3 Coordinator chat conventions

- The user wants the APK (or a Drive link, and now an adb install), not a long process narrative.
- Offer to stop the cloud agent only if they start conflicting self-started runs and have not responded; do not nag.
- When a new build lands, hash, upload, install, send the link, and say what changed and what is still unverified.

---

## 9. Open items and next steps (priority order)

**P0 — finish and deliver djmix5 (in flight).**

1. Watch cloud agent `bc-10f3ffd2-4eec-569e-aafe-8bd118b340fe`. Do not start a second agent on this repo.
2. Scope, already sent: implement RC-1…RC-7 and the 6-rung ladder on the existing djmix4 tree. Skip InstaBox, skip RE, skip QA. Do not build on the user’s PC.
3. Invariant: every pair that earned a loop under 1.9.1 / loopfix rules still earns a loop, and a showing countdown must not be silently cancelled.
4. When the APK appears in artifacts: verify SHA and `versionCode ≥ 47`, upload to Drive, `adb install -r` on 1439ebea, send the link. Keep a copy under `/workspace/apk7/` (or next free slot).

**P0 — user listen test (cannot be faked).**

Once djmix5 is on the phone, the user needs to check, on real tracks:

- Does a loop that reaches countdown actually play?
- Is “waiting on lyrics” gone?
- Is the first 3–4 s stutter gone?
- Do song A and song B overlap for several bars with no abrupt ending?
- Does the new rung name on the UI match what they hear?
- Any return of the 999× / chipmunk / pitch lurch?

Forward whatever they say back to the same cloud agent.

**P1 — UI polish**, without dropping information. The neater Automix line / mix view is still an open product request, including after the djmix iteration.

**P1 — after djmix5 ships.** Watch for residual risks the current run already flagged (`holdLoop` KEEP vs a late lyric veto; whether non-loop rungs truly run on the native clock; onset snapping; LUFS / TPT SVF). Then get the user’s listen test (Section 9 P0). Origin slug is now known (`hf-zh/tmp-1a194b77b9f0f8dd`); djmix–djmix4 changelogs have been filled from the 3,213-line transcript.

**P2 — dossier items not required to ship djmix5, still on the roadmap.**

- Lyric-repetition / self-similarity section labels used for exits after the last chorus and for never skipping a sung verse (partially started in djmix).
- Per-region key (libkeyfinder-style).
- Windowed stems / acapella-over-instrumental (opt-in, 8 GB+ or a licensed lightweight model).
- Learned EQ/fader templates; optional 👍/👎 weight tuning.
- Optional HQ stretch A/B (Rubber Band R3 or Bungee).
- Dual-ABI only if emulator testing is ever re-enabled.

**Out of scope unless the user reopens it:** official BitChord backup import, InstaBox QA theatre, split-file chat delivery.

---

## 10. Quick orientation for a new agent

You are taking over a private, side-by-side Android fork of BitChord 1.9.1 whose only job, in the user’s eyes, is to become a **DJ-level on-device automix** that beats Apple Music AutoMix, with BitChord’s **loop mix kept as the first and best trick**, while staying fast on a phone.

The code lives in one Origin draft. The builder is one cloud agent. The phone is ready on adb. The current binary is **djmix4** (vc46) in Drive. The current work is **djmix5**. The user will tell you if it actually sounds good. Until they do, every mix-quality claim is unverified.

---

*End of handover. Sources: user conversation (quotes as cited), cloud-agent transcript `/workspace/cloud-agent-transcripts/bc-10f3ffd2-4eec-569e-aafe-8bd118b340fe.jsonl` (3,213 lines, through mid-djmix5), APK manifests and sha256 of every shipped artifact, `/workspace/automix-research.md`, `/workspace/apple-automix-wiki.md`, `/workspace/ios27-automix-playbook.md`, Automix UI screenshots, and the djmix4 Drive listing. Compiled 11 November 2026, Asia/Jakarta.*

---

## 11. Addendum, 2026-11-11 11:48 (WIB, UTC+7): user field notes on djmix5 (vc47, installed on device)

The user asked for these to be **recorded only, not fixed** by the coordinator. They are open items for the next owner.

- **Overall:** "the current state is like so good with all its rough edges." djmix5 is a clear step up.
- **Echo/flanger transition:** the flanger/echo effect is audible and well received ("the flanger sounds in! wow ok!"), but it is "not so smooth", "kinda lowkey lagged a bit". This is likely modulation/echo timing or smoothing in the ECHO_HANDOFF DSP (dotted-eighth ping-pong, wet/dry ramp) or render latency. Needs tuning, not a redesign.
- **Early-incoming-track stutter (still present, minor):** in the first part of the automixed-in song there is still a stutter-ish glitch. It is much less severe than earlier builds but audible. Example: Echo, 4 bars, 136 BPM, 5A→10A, Opus 48 kHz 149 kbps. A djmix6 round (vc48) to diagnose this was queued to the cloud agent at 11:37 WIB, before this note.
- **Performance/stability bug (unresolved):** managing the queue (reordering, sorting and similar edits) causes the app to **force close**. This is a priority crash. Suspects: queue mutation racing the automix planner/analysis (next and next+1 prefetch) or the native renderer's next-track handle, index invalidation, or main-thread work on large queues.
- **Now-playing info UI: ON HOLD.** Four redesign mockups (icon chips, spec card, mix timeline, expandable ticker; `/workspace/ui-mockups/`) were judged "too extra". Do not change the info UI until the user asks. The user's standing principle: clean means a cleaner visual, never fewer details.

## 12. djmix6 (vc48), built 2026-11-11 11:56 WIB (UTC+7)

- **Root cause of the early-incoming stutter:** on straight-through rungs (Echo, Drop, Beat blend; loop length 0), the fade that hands the native deck back to the player still used the looping formula ("run on from the loop end"). That put the fade about one mix length late (~7 s for Echo, 4 bars, 136 BPM). The Signalsmith deck (a reconstruction even at 1.000×) and the player's own decode played the same bars together and flammed, until the deck's safety stop 2 s past the splice.
- **Fix:** the straight-deck fade now uses the handover-time region sample, so the deck is silent 25 ms after handover. A native mix no longer calls `setPlaybackSpeed` at the splice, which had reset AudioTrack params under a live sample. Analysis pauses for 10 s when the incoming decoder starts, at the mid-mix handoff, and at the audible splice, and the head-analysis thread is no longer above normal priority.
- **New diagnostics in the expanded Automix line:** `u` = AudioTrack underruns, `g` = incoming timeline gaps over 20 ms, `l` = mix blocks more than 8 ms over budget.
- **Ruled out:** the 8 ms seam (loops only), a stretcher reset at rate 1, a limiter reset, per-block allocation, and a double-resample path for Opus 48 kHz.
- Commit `0d3260bfc4a99b2fa75ec1965ebda10ee6a4b1b3`. SHA-256 `702c7f0a14504be02a4195ee8152980dcf3f5f900373c48af537cbe70c581cde`, 87,417,988 bytes, same signing cert. Drive: https://drive.google.com/file/d/1AX98hkuwilVoPKhRSHPUzjby4U5jJLRD/view
- **Still open (not addressed in djmix6):** the flanger/echo feels slightly laggy, and **the app force closes after queue management/sort**.

## 13. External orchestrator sessions (Gemini 4 Carbon in opencode, on the user's PC), 2026-11-11

The user ran a separate orchestrator AI that was told to write an exact `.md` spec for a worker AI. **Neither session produced the spec.** Their findings are captured below, and the full mined analysis is in Appendix A.

**Session 1:** `ses_e3708bc5affezsvz2jmV0XDANm`, titled "Bitchord worker AI spec MEOWMEOW", outcome *interrupted*. 211 messages, about 564 tool calls, 10 compactions.
- It used an InstaBox Android VM (`bk-android`), verified the djmix5 and djmix6 APKs and the signing cert, and decompiled both with JADX.
- It **rebuilt the exact djmix6 source by replaying all 395 session patches onto upstream v1.9.1 (395/395 clean)**, so its code reads are of real fork source.
- It found that upstream `main` has no newer fixes to pull.
- It found that `PlayerQueue.kt`, `PlayerConnection.kt` and `QueueShuffle.kt` have zero fork edits. The queue crash therefore comes from how the fork drives upstream queue code.

**Session 2:** `ses_e3642ef38ffeW8fJ6Pq2E8SZK5`, titled "Markdown output from ses_e3708…json", outcome *failed*. 43 messages and 76 tool calls. It was asked to resume session 1 and deliver the md.
- It re-read session 1's reasoning, this handover, the session export, and `~/AGENT_INSTRUCTIONS_BITCHORD_IOS27_AUTOMIX.md` on the user's PC.
- It then re-checked the reconstructed djmix6 source: `PlayerQueue.kt`, `ReorderPlaylistPage.kt` (the `settle()`/`order` logic), `PlaybackService.kt`, `CrossfadeController.kt`, `TransitionFilterProcessor.kt`, `LoopMix.kt`, `LoopMixSession.kt`, `loop_mix_renderer.cpp/.h`, `DjColour.kt`, `DjMix.kt`, `QueueShuffle.kt`, `PrecisionAudioSink.kt` and `LoopMixRenderer.kt`.
- Its one new explicit conclusion **re-confirms session 1's queue desync finding**. `CrossfadeController`'s comments claim the listener handles queue edits, but it never overrides `onTimelineChanged`. Playlist reorders and removals fire `onTimelineChanged`, not `onMediaItemTransition`, so the controller neither bails nor updates its primed job and keeps a stale queue snapshot during active crossfades.
- It was planning a "djmix7 orchestrator guide" when it died: first on the provider's daily free-quota limit, then on a connection reset, then when the free model was paused. No file was written.

### 13.1 Consolidated findings (worker-ready, ordered by priority)

1. **P0, queue force close: closing an unattached primed session.**
   - `LoopMixSession.close()` calls `getOutPort()/getInPort()`. These are `lateinit` and are only set by `attach()` in `armLoop`.
   - A session that is primed but never armed (phase IDLE or LOOP_ARMING) is closed by `discardPrime()` on a queue edit. That throws `UninitializedPropertyAccessException`.
   - **Fix:** null-safe or `isInitialized`-guarded close, and idempotent teardown.
   - *Confidence: medium-high, from reading the code. There is no stack trace yet.*
2. **P0, queue desync during transitions: `onTimelineChanged` is not handled.**
   - `reconcileQueue` assumes the listener already bailed on edits that aren't appends. It never did, because only `onMediaItemTransition` is handled.
   - **Fix:** handle `onTimelineChanged` (reason PLAYLIST_CHANGED). Bail or re-prime the armed or running transition, and rebuild the standby queue. Guard every index against the current timeline. *Confidence: medium, confirmed by both sessions.*
3. **P0/P1, playlist reorder crash on duplicate tracks.**
   - The `ReorderPlaylistPage` `LazyColumn` uses `key = setVideoId ?: videoId`, so the same track twice gives a duplicate Compose key and an `IllegalArgumentException`.
   - **Fix:** use a unique key per row, such as the position plus the id, or the playlist set-item id. *Confidence: medium-high.*
4. **P1, echo feels laggy or not smooth (what the user heard as "flanger").**
   - On the Echo rung, the native `PingPong` delay in `Renderer::setEcho` runs at a constant wet 0.55 and feedback 0.44 with no send envelope.
   - It is fed the deck's full dry signal from pre-roll (~0.4 s), so it rings throughout instead of opening on the exit phrase.
   - `LoopMix.echoPlan` sets `outStart = outDuration − 4·bar − 0.3` with no downbeat or phrase snap.
   - **Fix:** a beat-synced send envelope that opens on the last 1 to 2 beats of the exit phrase, a feedback ramp, a downbeat-snapped `outStart`, and a tempo-locked delay time with smoothed changes. *Confidence: medium.*
5. **P2, fallback `DjColour` path** (only used when the native bridge is unavailable).
   - `flange()` truncates the LFO delay with `depth.toInt()`, which causes zipper noise because there is no fractional interpolation.
   - `set()` steps its parameters without per-sample smoothing.
   - `rideColour` snaps the flanger wet on at progress above 0.30.
   - **Fix:** fractional-delay interpolation and parameter smoothing. *Probably not what the user heard.*
6. **Structural:** the native renderer has **no flanger**, so a smooth, beat-synced flanger on LOOP_MIX or BEAT_BLEND would be new native DSP work. Its biquad coefficients update every 64 frames (`kControlFrames`). Consider per-sample interpolation for sweeps.
7. **Do first: get evidence.** Capture `adb logcat -b crash` and `-b main` from the user's phone while reproducing the queue force close. Neither orchestrator ever had a device trace. Every crash claim above is static inference.

**Doubtful or minor claims:** its "−0.45 s pre-roll" figure is slightly off; the actual `preroll` is 0.4. Its concern about `rideBuildup` repeatedly calling `setPlaybackSpeed` is overstated, because changes are guarded in 1% steps. The InstaBox `bk-android` workflow and its reconstructed line numbers are approximate cross-references only. The cloud agent works on the real repo.

**Consistency:** both sessions correctly modeled djmix6's 25 ms handover-fade fix and the `u`/`g`/`l` counters. Nothing above was made stale by djmix6.

---

# Appendix A: Mined orchestrator findings (full)

### Orchestrator Session — Mined Findings (for a worker AI)

*Source: `/workspace/orch/orchestrator_session.json` — an interrupted opencode run of "Gemini 4 Carbon" (session `ses_e3708bc5affezsvz2jmV0XDANm`, title "Bitchord worker AI spec MEOWMEOW"). It was told to act as orchestrator and emit an `.md` spec for a worker AI. It never wrote that file (outcome = `interrupted`; the last several assistant turns are empty). This document extracts the real findings/thinking it produced before it died, cross-checked against our `/workspace/BITCHORD_HANDOVER.md` and djmix6 (vc48, commit `0d3260b`). Reasoning "parts" in the JSON are summarizer boilerplate and were skipped; findings here come from its tool inputs/outputs and compaction summaries.*

---

### TL;DR

- The orchestrator did a genuine forensic dissection: it SSH'd into an InstaBox Android VM (`bk-android`), verified both APKs, decompiled djmix5 + djmix6 with JADX, cloned upstream `kushagrasinghx/BitChord` v1.9.1 (`7f45c76`), and — most usefully — **reconstructed the exact djmix6 source tree by replaying all 395 `search_replace` patches from `BITCHORD_SESSION_RAW.jsonl` onto upstream (395/395 clean, 0 failed)**. So its code reads are of real fork source, not just decompiled bytecode.
- **It produced NO spec and NO partial .md draft.** It ran out of turns during analysis. Everything below is analysis, not a deliverable. A worker still needs the spec written.
- **Queue force-close — primary hypothesis (medium-high confidence):** `LoopMixSession.close()` calls `getOutPort()/getInPort()`, which are `lateinit`/non-null-checked and only initialized by `attach()` (called in `armLoop`). A **primed-but-never-armed** session (set as `primedSession` while phase is IDLE or LOOP_ARMING) has null ports, so when a queue edit triggers `discardPrime()` → `primedSession.close()` → `getOutPort().disarm()` it throws `UninitializedPropertyAccessException` and crashes.
- **Queue correctness — second finding (medium confidence):** `CrossfadeController.reconcileQueue` assumes the `listener` already bailed any non-append queue edit, but the listener only reacts to `onMediaItemTransition` (fires on the *current* item), not `onTimelineChanged` (fires on upcoming reorder/remove). A reorder of upcoming items during an armed/running transition is neither bailed nor reconciled → stale standby queue / index drift.
- **Queue/playlist crash — third finding (medium-high confidence):** `ReorderPlaylistPage.kt` `LazyColumn` uses `key = { _, song -> song.setVideoId ?: song.videoId }`; a playlist containing the same track twice (common) yields duplicate Compose keys → `IllegalArgumentException`.
- **Echo/flanger lag — native path (the one the user actually heard, medium confidence):** On the Echo rung the effect is the **native `PingPong` delay**, not a flanger. `Renderer::setEcho` sets a constant `wet=0.55`/`feedback=0.44` with **no send-level envelope**, and the echo is fed the deck's full dry signal (`echo.tap(l,r,...)` before the gain ramp) from the deck's pre-roll (~0.4 s before loop start). So the echo is "on" and ringing the whole time rather than opening on the exit phrase — plausibly the "not so smooth / lowkey lagged" the user reported. `LoopMix.echoPlan` also places `outStart = outDuration - 4*bar - 0.3` with no downbeat/phrase snapping.
- **Echo/flanger lag — fallback path (lower relevance):** The dual-ExoPlayer `DjColour` fallback (used only when native bridge is unavailable) has real defects too: `flange()` truncates its LFO delay with `depth.toInt()` (integer/zipper, no fractional interpolation), `set()` steps `amount`/`scoop`/`beatFrames` with no per-sample smoothing, and `rideColour` snaps flanger wet on at `progress > 0.30`. These matter only if a transition falls back off the native renderer.
- **Native renderer has NO flanger at all** — `LOOP_MIX`/`BEAT_BLEND` can never get a beat-synced flanger on the native (primary) path; biquad coefficients also only update every `kControlFrames = 64` frames.
- **Consistency with djmix6:** The orchestrator correctly understood djmix6's handover-fade stutter fix (`kFadeSeconds = 0.025`, 25 ms incoming-deck fade; `u`/`g`/`l` counters) and correctly noted djmix6 left the flanger lag and queue crash untouched. Its findings are NOT made stale by djmix6.
- **Never reached:** the actual written spec, any device stack trace (adb showed no device from the VM), confirmation of which exact queue action throws, and whether the native-echo smoothness theory matches the recording.

---

### Environment it worked in (so a worker can reproduce)

- Remote VM `instabox ssh bk-android` (Firecracker, 4 vCPU / 8 GiB, `m_agq4fglq7npke2orqgt5j6leai`), Android SDK build-tools 34/35/36, JADX 1.5.1 at `/data/workspace/tools/jadx/bin/jadx`. (This is the InstaBox path our own orchestrator playbook skips — the worker on the Cursor cloud agent does not need it.)
- Verified artifacts:
  - djmix5 = `/home/hfzhkn/BitChord-djmix5.apk`, vc47, SHA-256 `099fcfec…20c70`, 87,417,988 B (Drive `1FTCPjT…`).
  - djmix6 = `/home/hfzhkn/BitChord-djmix6.apk`, vc48, `1.9.1-djmix6`, SHA-256 `702c7f0a…81cde` (Drive `1AX98hk…`).
  - Signing cert confirmed: `CN=BitChord loop fix`, SHA-256 `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c`.
- Native libs in the APK: `libbitchord_loopmix.so`, `libbitchord_stretch.so`, `libbitchord_analysis.so` (+ dsd/dst/exclusive/usb/ffmpeg/onnxruntime/quickjs).
- Upstream tip: v1.9.1 `7f45c768` is the current tip of `main` (`acf219a7` merge) — i.e. no upstream fixes exist to pull for these bugs.
- **djmix6 source reconstruction:** all 395 session patches replayed cleanly onto upstream at `bk-android:/data/workspace/bitchord-dissect/djmix6-src/` and mirrored to `/tmp/opencode/djmix6-src/` with `/tmp/opencode/djmix6-diff.patch`. Fork edit hot-spots it counted: `CrossfadeController.kt` 122 edits, `LoopMix.kt` 23, `TransitionPlanner.kt` 22, `TrackAnalyzer.kt` 16, `TransitionFilterProcessor.kt` 13, `loop_mix_renderer.cpp` 13, `DjMix.kt` 11, `DjColour.kt` 10, `PrecisionAudioSink.kt` 7, `PlaybackService.kt` 7. It confirmed `PlayerQueue.kt`, `PlayerConnection.kt`, `QueueShuffle.kt` had **zero fork edits** (so the queue crash is in how the fork drives upstream queue code, not in modified queue code).

---

### Confirmed / likely root causes with evidence

### A. Queue force-close (P0 crash) — PRIMARY: close() on an unattached primed session
**Confidence: medium-high (code-grounded, but no stack trace).**

Evidence chain (line numbers are the reconstructed djmix6 source unless noted):
- `LoopMixSession` has `lateinit var outPort/inPort`, set only in `attach(out, inn)` (`LoopMixSession.kt` ~line 39, `attach` ~`fun attach`). Decompiled djmix5 `LoopMixSession.java` makes the hazard explicit: `getOutPort()`/`getInPort()` call `Intrinsics.throwUninitializedPropertyAccessException("outPort"/"inPort")` when null.
- `close()` does `getOutPort().disarm(); getInPort().disarm(); renderer.close()` — so calling `close()` before `attach()` throws.
- `attach()` is only called inside `armLoop()` (`CrossfadeController.kt` line 3134: `session.attach(outPort, inPort)`).
- But `startLoopBuild` can store a session as `primedSession` **without arming/attaching** when `phase == Phase.IDLE` (line 2971: `phase == Phase.IDLE -> primedSession = session`) or when still LOOP_ARMING but ports/nextIndex not yet resolvable (line 2961).
- `discardPrime()` (line 3006) does `primedSession?.close()` — i.e. closes a session whose ports were never attached ⇒ throw.
- `discardPrime()` is reachable from `finish()` (line 3588), `loopBail()` (line 3355), `bail()` (via loopBail), and `considerSmartTransition`/`primeLoopDecks` (line 2838). A queue edit that fires `onMediaItemTransition(PLAYLIST_CHANGED/SEEK)` → `bail()`, or any path through `finish()`, while a primed (unattached) session exists, triggers the crash.

The orchestrator's own words (compaction @ msg 43): *"When a user reorders the queue, a tick triggers discardPrime or a stale background build finishes, forcing a session close. Because outPort is never initialized on those primed sessions, calling close throws an uninitialized property exception, instantly crashing the app."*

**Suggested fix direction (worker):** guard `LoopMixSession.close()` to no-op disarm when ports aren't initialized (e.g. make `outPort/inPort` nullable or add `isAttached`), OR never call `close()` on a session that wasn't attached. Wrap `discardPrime()` session teardown defensively. Add a `CoroutineExceptionHandler`/try-catch is a band-aid; fixing the lateinit contract is the real fix.

### B. Queue correctness — reconcileQueue vs. missing onTimelineChanged in CrossfadeController.listener
**Confidence: medium.**

- `CrossfadeController.listener` (line 726) overrides `onPositionDiscontinuity`, `onMediaItemTransition`, `onPlayerError`, `onPlayWhenReadyChanged` — but **not** `onTimelineChanged`.
- `onMediaItemTransition` only fires when the *playing* item changes. A reorder/remove of *upcoming* queue items fires `onTimelineChanged(TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)` instead, which the controller never sees.
- `reconcileQueue` (line 1863) explicitly assumes: *"Only a pure append is reconciled. Anything else — a queue replaced, an item removed or moved — … [listener] has already bailed the transition for it."* That assumption is false for upcoming-item edits during ARMING/LOOP_ARMING/FADING, because the listener didn't bail.
- Result: the standby/incoming player holds a stale copy taken at arm time (`armLoop` snapshots `items` at line 3112; `into.setMediaItems(items, nextIndex, …)`), so `nextIndex`/mediaItem can mismatch the real queue after a reorder — a correctness bug and a plausible secondary crash/desync vector.

**Fix direction:** add `onTimelineChanged` handling to the controller's listener that bails (or properly reconciles) non-append edits during an active/armed transition; or have `reconcileQueue` detect non-append diffs and bail instead of assuming.

### C. Queue/playlist force-close — duplicate Compose LazyColumn keys
**Confidence: medium-high.**

- `ReorderPlaylistPage.kt` line 258: `itemsIndexed(order, key = { _, song -> song.setVideoId ?: song.videoId })`. A playlist/queue with the same track present twice (same `videoId`, no distinct `setVideoId`) yields duplicate keys → Compose throws `IllegalArgumentException: Key "…" was already used`. Reorder/sort of such a list crashes.
- Contrast: `DetailScreen.kt` (line 775) uses composite keys like `"${entry.index}_${entry.value.videoId}"` and the inline queue uses per-entry `it.key`, which are unique — so the vulnerable surface is specifically `ReorderPlaylistPage` (and anywhere else using a bare videoId key).

**Fix direction:** composite key with index or queueEntryId (e.g. `"$index:${song.setVideoId ?: song.videoId}"`).

### D. Echo "lag"/roughness — native PingPong has no send envelope (the path the user heard)
**Confidence: medium.**

- The Echo rung is native: `echoPlan` sets `nativeIncoming = true`, `echoTail = true` (`LoopMix.kt` line 946-947), so the audible effect is the C++ `PingPong` delay, not DjColour's flanger. (So "the flanger sounds in" is most likely this ping-pong echo.)
- `Renderer::setEcho(index, delaySeconds, feedback, wet)` (loop_mix_renderer.cpp line 208) just calls `echo.setup(sr, delay, 0.44, 0.55)` — a **fixed** wet/feedback, no ramp. Invoked once in `LoopMixSession` as `renderer.setEcho(OUT, plan.out.beatSec * 0.75, 0.44f, 0.55f)` (dotted-eighth).
- In `renderDeck` (line ~397) the echo is fed the deck's full post-filter **dry** signal `echo.tap(l, r, …)` and then `mixLeft_[n] += l*g + wetL` — the echo input ignores the gain envelope `g`, so the delay line is charged at full level from the deck's pre-roll (`preroll = 0.4`, subtracted in `firstFrame()`), i.e. before the exit phrase. The echo therefore rings continuously across all bars instead of opening on the final beats — a smear/pre-ring that fits "not smooth / laggy".
- `echoPlan` outStart = `outDuration - 4*bar - 0.3`, **not snapped** to an outgoing downbeat/phrase or `contentEndTime`, so the echo window can sit off-grid.
- Biquad filter coefficients in `renderDeck` only update every `kControlFrames = 64` frames (stepped, not per-sample).

**Fix direction:** give `setEcho` a send/dry envelope so the wet opens over the beat before the exit and the dry is handed off (mirror what the fallback `rideEchoOut` does with `outgoingEcho(send, dry)`); gate the echo tap so the delay line isn't charged during pre-roll; snap `echoPlan.outStart` to a downbeat.

### E. Flanger roughness — fallback DjColour path (only if native bridge unused)
**Confidence: medium that the defects exist; low that they are what the user heard.**

- `DjColour.flange()` (line 154): `val delay = depth.toInt().coerceIn(...)` — integer delay, no fractional interpolation → zipper/stepping on the LFO sweep. Delay tap reads a single integer-indexed sample.
- `DjColour.set()` (line 75) writes `effect/amount/scoop/beatFrames` with no `@Volatile` and no per-sample smoothing, so parameter changes step between blocks.
- `CrossfadeController.rideColour` (line 3860) snaps flanger `amount` to full `settings.wet` at `progress > 0.30` (step, not ramp) unless `render.buildup`.
- `CrossfadeController.rideBuildup` (line 4316) repeatedly calls `into.setPlaybackSpeed(target)` mid-overlap (though it is guarded to ~1% steps via `if (abs(target - buildupSpeed) < 0.008f) return`, so this one is milder than the orchestrator implied — see doubtful claims).

**Fix direction:** fractional (interpolated) flanger delay; smoothed parameter ramps; ramp flanger wet in rather than snapping at 0.30.

### F. Native renderer lacks a beat-synced flanger entirely
**Confidence: high (structural).** The C++ `Renderer`/`Deck` has `PingPong` echo + biquads but no flanger stage, so `LOOP_MIX`/`BEAT_BLEND` on the native (primary) path can never apply a smooth fractional flanger. If flanger is wanted on loop/blend rungs, it must be added natively.

---

### Proposed fixes / spec items, prioritized (as implied by its analysis; it never wrote them up)

1. **P0 — Queue force-close.** Fix the `LoopMixSession.close()` lateinit-port crash (finding A); add `onTimelineChanged` bail/reconcile to the controller listener (B); composite keys in `ReorderPlaylistPage` (C). Target: reorder/sort/remove/shuffle never crashes, idle or mid-transition.
2. **P1 — Echo/flanger smoothness.** Native echo send/dry envelope + pre-roll gating + downbeat-snapped `echoPlan.outStart` (D). Fallback-path flanger fractional delay + smoothing (E) as secondary.
3. **P2 — Native flanger** for loop/blend rungs (F), if desired; biquad per-sample (or finer) coefficient smoothing.
4. Build/release invariants it kept restating: package `com.music.bitchord.loopfix`, arm64-v8a, same cert, `versionCode ≥ 49` (it called the next build "djmix7"); LOOP_MIX stays Tier 1; info UI ON HOLD (keep compact line + tap-to-expand incl. `u`/`g`/`l`); GPL-3.0 hygiene (no madmom/umxl/AI_DJ/Orchard PolyForm); patent US 8,280,539 (per-track structure + scalar pair scoring only). These are all already in our handover — no new constraint.

---

### Things it explored (context, mostly confirming what we know)

- Decode of the full djmix5 loop/blend/drop/echo planner (`LoopMix.bridge`, `dropPlan`, `blendPlan`, `echoPlan`, `Clock`), the native `LoopMixRenderer`/`LoopMixSession`/`LoopMixPort` chain, `PrecisionAudioSink` splice/handover (`spliceLoop`, `generateLoop`, `silenceUntilHandover`, `HANDOVER_SECONDS`), `MixDebug` u/g/l counters, `TrackAnalyzer.yieldToPlayback`, regional K-weighted loudness match (gain clamp 0.4467–1.7783 ≈ −7..+5 dB), −1 dBTP in-block lookahead limiter (1.5 ms), ±6%/±8% stretch caps, the `deckPlayedRate`/`BUILDUP_RISE = 0.06` tempo clamps (the fix for the 999× bug).
- Confirmed djmix6's actual edits: `kFadeSeconds = 0.025`, handover fade for straight decks, `yieldToPlayback` (10 s analysis yield, head thread dropped to NORM_PRIORITY), `noteIncomingTimeline` gap counter, MixDebug `u`/`g`/`l`.
- It loaded a `full-output-enforcement` skill (demanding zero-placeholder output) — part of why it over-invested in reading every line and never got to writing.

---

### Stale or doubtful claims (flag these)

- **"PingPong::tap runs from −0.45 s pre-roll":** the actual `preroll` constant is `0.4` (so ~−0.4 s, plus stretch latency). Minor number drift; the substance (echo charged during pre-roll, no send envelope) is correct.
- **"rideBuildup repeatedly calls setPlaybackSpeed mid-overlap" as a defect:** true that it calls `into.setPlaybackSpeed`, but it is guarded to ~1% steps (`if (abs(target - buildupSpeed) < 0.008f) return`) and is squared-eased — it's deliberately gentle, so this is weaker evidence of "lag" than stated.
- **DjColour flanger as the cause of the user's symptom:** doubtful. The user heard the effect on an **Echo** transition, which is the native PingPong path; DjColour only runs on the dual-ExoPlayer fallback. Fix the native echo first (D), treat DjColour (E) as secondary.
- **Native "flanger" defects on loop/blend:** the native renderer has no flanger at all, so there is nothing to "smooth" there — it would be new work, not a fix. Don't let the spec imply a regression.
- **InstaBox/`bk-android` dependency:** the whole decompile+reconstruct workflow is InstaBox-only; our own project explicitly skips InstaBox and builds on the Cursor cloud agent. A worker on our repo does not need any of the `bk-android` paths — they already have the real source. Treat the reconstructed-source line numbers as approximate cross-refs, not gospel.
- **No stack trace anywhere.** adb from the VM showed "List of devices attached" (empty). Every crash claim is static-analysis inference, not an observed exception. The lateinit-close theory (A) is the strongest but still unproven on device — a `logcat` during an actual force-close would confirm which of A/B/C fires.

---

### Open questions it never resolved

- Which exact queue action throws, and the real exception class/stack (needs on-device `adb logcat -b crash`).
- Whether finding A, B, or C is the actual force-close (could be more than one).
- Whether the native-echo pre-roll/envelope theory (D) matches the recording, or the lag is elsewhere (stretch output latency, limiter, control-block stepping).
- It never wrote the deliverable `.md` spec, never produced verification tests, and never produced a build/signing/delivery checklist. All of that is still TODO for the worker.

---

### Appendix — verbatim key excerpts

**Its task prompt (msg 0):**
> do: all the files are in : /home/hfzhkn/Downloads/BITCHORD_SESSION_EXPORT + the apk itself at https://drive.google.com/file/d/1FTCPjT_BPMj_2xNDLCAy0l5IyNVRPLhI/view // [YOU ARE THE ORCHESTRATOR, OUTPUT RESULT MUST BE an .md FILE WITH COMPLETE EXACT SPEC AND DETAIL FOR WHAT THE AGENTIC WORKER AI SHOULD DO AND BUILD; YOU *ARE NOT* THE BUILDER YOURSELF] // go.

**Its own crash-path statement (compaction @ msg 43):**
> I see the exact crash path now. When a user reorders the queue, a tick triggers discardPrime or a stale background build finishes, forcing a session close. Because outPort is never initialized on those primed sessions, calling close throws an uninitialized property exception, instantly crashing the app.

**Its listener/reconcile finding (reasoning @ msg 179):**
> Examining `CrossfadeController.kt`. Discovered … `reconcileQueue` assumes `listener` has bailed out on non-append changes (queue replacement, removal, moving), BUT `listener` doesn't even override `onTimelineChanged`! It only overrides `onMediaItemTransition`, which only fires for currently playing item changes, missing upcoming queue manipulations.

**The native echo wiring (reconstructed source):**
```
// LoopMixSession.build:
if (plan.echoTail) renderer.setEcho(LoopMixRenderer.OUT, plan.out.beatSec * 0.75, 0.44f, 0.55f)
// loop_mix_renderer.cpp:
void Renderer::setEcho(int index, double delaySeconds, float feedback, float wet) {
  decks_[index].echo.setup(sampleRate_, delaySeconds, feedback, wet);   // constant, no envelope
}
// renderDeck inner loop (gain g applied to dry only; echo gets full dry):
float wetL = 0, wetR = 0;
deck.echo.tap(l, r, wetL, wetR, sr);
mixLeft_[n]  += l * g + wetL;
mixRight_[n] += r * g + wetR;
```

**echoPlan outStart (not downbeat-snapped), LoopMix.kt:**
```
val bar = outBeat * 4
val outStart = (outDuration - 4 * bar - 0.3).coerceAtLeast(earliestOutSec)
```

**Fallback flanger integer delay, DjColour.kt:**
```
val depth = sampleRate * (0.001 + 0.005 * (0.5 + 0.5 * sin(flangePhase)))
val delay = depth.toInt().coerceIn(1, flange.size / channels - 2)   // integer, no interpolation
```

**ReorderPlaylistPage duplicate-key risk:**
```
itemsIndexed(order, key = { _, song -> song.setVideoId ?: song.videoId }) { _, song -> ... }
```

**LoopMixSession lateinit close hazard (decompiled djmix5 LoopMixSession.java):**
```
public final void close() { getOutPort().disarm(); getInPort().disarm(); this.renderer.close(); }
public final LoopMixPort getOutPort() {
    LoopMixPort p = this.outPort;
    if (p != null) return p;
    Intrinsics.throwUninitializedPropertyAccessException("outPort");  // <-- crash if never attached
    throw null;
}
```
