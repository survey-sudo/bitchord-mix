package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

enum class MixTemplate {
    M1, M2, M3, M4, M5, M6, M7, M8, M9, M10, M11,
    ROLLING, CUT_ON_ONE, ENERGY_LIFT, RELAXED, VOCAL_OVER_OUTRO,
}

data class PhraseChoice(
    val template: MixTemplate,
    val rung: String,
    val score: Double,
    val exit: ExitCue,
    val entry: EntryCue,
    val overlapBars: Int,
    /** Bars relative to the landing. The bass swap sits here. −8 is eight bars before L. */
    val swapBar: Double,
    /** Beats after L before A is fully gone. */
    val tailBeats: Double,
    /** Extra loops of A's last phrase so a short outro covers B's lead-in. */
    val loopK: Int,
    /** Echo input is dry × send, so a send of 0 puts nothing into the delay. */
    val echoGated: Boolean,
    /** Peak of the two vocal masks multiplied, over the overlap. Log only. */
    val vocalProduct: Float,
    /** Bars of tempo ramp on A before B is heard, under policy b. */
    val tempoPrepBars: Int,
    val echoDelayBeats: Double,
    val echoDelayName: String,
    val swapRampMs: Int,
    val swapRampName: String,
    val tempoPolicy: String,
    val vocalOverlapBeats: Double,
    val energyDelta: Float,
    val struct: Double,
    val vocalScore: Double,
    val phraseScore: Double,
    val energyScore: Double,
    val lengthScore: Double,
    val keyScore: Double,
    val tempoScore: Double,
    val cost: Double,
    val legacyLoop: LoopMix.Plan? = null,
    val detail: String = "",
    val keyClash: Boolean = false,
    /**
     * Energy-weighted vocal collision over the overlap, 0..1, or null when the
     * pair carried no measurement to weigh.
     *
     * The renderer's stem-duck decision reads this: it is the number the whole
     * scoring rewrite was built to produce, and a duck decision taken from any
     * other signal would be a second, disagreeing copy of it. Null means
     * "not measured" and the renderer falls back to the legacy boolean gate.
     */
    val vocalClashEnergy: Double? = null,
    /** Selected on the source grids before M6 is admitted or scored. */
    val handoff: MusicalHandoff? = null,
) {
    /** Incoming deck rate at [beat] on the master clock, landing at 0. */
    fun incomingRate(beat: Double): Double = if (beat >= -PhraseConfig.B_UNITY_BARS_BEFORE_LANDING * 1.0) 1.0 else incomingRateBefore(beat)

    private fun incomingRateBefore(beat: Double): Double {
        if (PhraseConfig.TEMPO_POLICY == PhraseConfig.TEMPO_POLICY_BRIDGE) return 1.0
        return 1.0
    }
}

/**
 * Pairs two songs' own cues. The only inputs are scalars. A thrown error or a
 * missing map returns null so the caller keeps the djmix6 plan.
 */
object PhrasePlanner {
    class Timeout(message: String) : RuntimeException(message)
    @Volatile var lastVetoSummary: String = ""

    /**
     * Holds a chosen template once the countdown is inside the lock window.
     * A late miss may slide A's release to the next phrase boundary of the
     * same template, then echo, then crossfade. It does not change template
     * while locked except along that degradation.
     */
    class Lock {
        var choice: PhraseChoice? = null
            private set
        var locked: Boolean = false
            private set
        var slides: Int = 0
            private set
        var lockedAtSec: Double? = null
            private set
        private var lastPlanAtSec: Double = Double.NEGATIVE_INFINITY
        var shown: Boolean = false
            private set

        /** First-shown countdown locks the rung; only slide/echo degrade it. */
        fun noteShown() { shown = true }

        /**
         * [nowSec] is a monotonic clock. Replans are at least [PhraseConfig.REPLAN_SEC]
         * apart until [PhraseConfig.LOCK_LEAD_SEC] seconds remain, and after that
         * the template stays.
         */
        fun consider(nowSec: Double, secondsToRelease: Double, fresh: PhraseChoice?): PhraseChoice? {
            val held = choice
            if (held != null && (locked || shown)) {
                // djmix26: once locked or shown, never overwrite choice with a
                // fresh plan whose release time jumped by > 1.5 s or slipped
                // behind the held release (e.g. HEAD -> FULL or earliestBar drift).
                if (fresh != null && fresh.template == held.template &&
                    abs(fresh.exit.releaseSec - held.exit.releaseSec) <= 1.5 &&
                    fresh.exit.releaseSec >= held.exit.releaseSec - 0.5
                ) {
                    choice = fresh
                }
                return choice
            }
            if (fresh == null) {
                if (held != null && secondsToRelease <= PhraseConfig.LOCK_LEAD_SEC) lockAt(nowSec)
                return held
            }
            val tooSoon = held != null && nowSec - lastPlanAtSec < PhraseConfig.REPLAN_SEC &&
                secondsToRelease > PhraseConfig.LOCK_LEAD_SEC
            if (!tooSoon) {
                choice = fresh
                lastPlanAtSec = nowSec
            }
            if (secondsToRelease <= PhraseConfig.LOCK_LEAD_SEC) lockAt(nowSec)
            return choice
        }

        private fun lockAt(nowSec: Double) {
            if (!locked) lockedAtSec = nowSec
            locked = true
        }

        fun slide(next: PhraseChoice?): PhraseChoice? {
            val held = choice ?: return next
            if (!locked) return next ?: held
            slides++
            if (next != null && next.template == held.template) {
                choice = next
                return next
            }
            // A template copy has no source-grid or vocal admission for Echo.
            // Keep the admitted choice until a newly planned handoff is available.
            return held
        }
    }

    fun select(
        out: SongMap?,
        inn: SongMap?,
        camelotSteps: Int?,
        outBpm: Double,
        inBpm: Double,
        legacyLoop: LoopMix.Plan? = null,
        startedMs: Long = 0L,
        now: () -> Long = { 0L },
        earliestExitSec: Double = 0.0,
        playableOutDurationSec: Double = 0.0,
        outAnalysis: TrackAnalysis? = null,
        inAnalysis: TrackAnalysis? = null,
    ): PhraseChoice? {
        if (!PhraseConfig.PHRASE_PLANNER_ENABLED) return null
        if (out == null) return null
        if (legacyLoop != null && legacyLoop.outLoops && legacyLoop.inLoops) {
            return loopCrown(legacyLoop, out, inn)
        }
        if (inn == null) return null
        return try {
            choose(out, inn, camelotSteps, outBpm, inBpm, startedMs, now, earliestExitSec, playableOutDurationSec, outAnalysis, inAnalysis)
        } catch (_: Timeout) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun loopCrown(legacy: LoopMix.Plan, out: SongMap, inn: SongMap?): PhraseChoice {
        val exit = CuePoints.exits(out).firstOrNull { it.loopableRoom } 
        val improved = if (exit != null && inn != null) legacy else null
        val plan = improved ?: legacy
        if (plan.out.bars < legacy.out.bars || plan.inn.bars < legacy.inn.bars) {
            return stubLoop(legacy, out)
        }
        return stubLoop(plan, out)
    }

    private fun stubLoop(plan: LoopMix.Plan, out: SongMap): PhraseChoice {
        val exit = ExitCue(
            ExitKind.OUTRO_PHRASE, 0, plan.out.loopEnd, plan.out.bars, 0, 0,
            SectionFn.OUTRO, SectionRole.GROOVE, 0.5f, 5, true, 0, 0,
        )
        val entry = EntryCue(
            EntryKind.HEAD, 0, plan.inn.loopStart, 0, SectionFn.INTRO, SectionRole.GROOVE,
            0, 0f, plan.inLoops, 0.5f, 5, 0, 0f,
        )
        return PhraseChoice(
            template = MixTemplate.M2, rung = "Loop mix", score = 1.0, exit = exit, entry = entry,
            overlapBars = plan.totalBars, swapBar = 0.0, tailBeats = 0.0, loopK = 0, echoGated = false,
            vocalProduct = 0f, tempoPrepBars = 0, echoDelayBeats = 0.0, echoDelayName = "none",
            swapRampMs = PhraseConfig.SWAP_RAMP_MS, swapRampName = "SWAP_RAMP_MS",
            tempoPolicy = PhraseConfig.TEMPO_POLICY, vocalOverlapBeats = 0.0, energyDelta = 0f,
            struct = 1.0, vocalScore = 1.0, phraseScore = 1.0, energyScore = 1.0, lengthScore = 1.0,
            keyScore = 1.0, tempoScore = 1.0, cost = 0.0, legacyLoop = plan,
            detail = "loop crown ${plan.out.bars}/${plan.inn.bars}",
        )
    }

    private fun choose(
        out: SongMap,
        inn: SongMap,
        steps: Int?,
        outBpm: Double,
        inBpm: Double,
        startedMs: Long,
        now: () -> Long,
        earliestExitSec: Double = 0.0,
        playableOutDurationSec: Double = 0.0,
        outAnalysis: TrackAnalysis? = null,
        inAnalysis: TrackAnalysis? = null,
    ): PhraseChoice? {
        val limited = limitedTo(out, inn)
        // djmix14-review: rescue (and strict search) must not pick a past
        // exit. Filter to future exits; if none remain, strict/rescue both die.
        val effectivePlayable = if (playableOutDurationSec > 1.0) playableOutDurationSec else out.durationSec
        val exitsAll = CuePoints.exits(out, effectivePlayable, outAnalysis)
        val exits = if (earliestExitSec > 0.0) exitsAll.filter { it.releaseSec >= earliestExitSec } else exitsAll
        val entries = CuePoints.entries(inn, inAnalysis)
        var best: PhraseChoice? = null
        val vetoCounts = HashMap<String, Int>()
        var tried = 0
        var ratioSkip = 0
        for (exit in exits) {
            for (entry in entries) {
                checkPlanTime(startedMs, now)
                for (template in candidates(exit, entry, out, inn, steps)) {
                    if (limited != null && template !in limited) continue
                    tried++
                    val ratio = foldedRatio(outBpm, inBpm)
                    if (abs(ratio - 1.0) > 0.12 && template !in setOf(
                            MixTemplate.M6, MixTemplate.M10, MixTemplate.RELAXED,
                        )
                    ) {
                        ratioSkip++
                        continue
                    }
                    val handoff = if (template == MixTemplate.M6) {
                        scriptedEchoHandoff(exit, entry, out, inn, outBpm, inBpm, outAnalysis, inAnalysis)
                    } else null
                    val veto = if (template == MixTemplate.M6 && handoff == null) "echo handoff" else
                        veto(template, exit, entry, out, inn, steps, outBpm, inBpm, handoff)
                    if (veto != null) {
                        vetoCounts[veto] = (vetoCounts[veto] ?: 0) + 1
                        continue
                    }
                    val choice = score(template, exit, entry, out, inn, steps, outBpm, inBpm, handoff)
                    if (best == null || choice.score > best.score + 1e-6) best = choice
                }
            }
        }
        if (best == null || best.score < PhraseConfig.S_MIN) {
            lastVetoSummary = "no-choose tried=$tried ratioSkip=$ratioSkip vetoes=${vetoCounts.entries.joinToString(",") { it.key + "=" + it.value }}" +
                if (best != null) " lowScore=${"%.3f".format(best.score)}" else ""
            // A soft score miss may still leave a useful admitted candidate.
            // Rescue must not resurrect a hard-vetoed or uncovered geometry.
            val rescue = musicalRescue(out, inn, steps, outBpm, inBpm, exits, entries, outAnalysis, inAnalysis)
            if (rescue != null) {
                lastVetoSummary = "rescue ${rescue.template} tried=$tried ratioSkip=$ratioSkip vetoes=${vetoCounts.entries.joinToString(",") { it.key + "=" + it.value }}"
                return rescue
            }
            return null
        }
        lastVetoSummary = "chose ${best.template} tried=$tried ratioSkip=$ratioSkip vetoes=${vetoCounts.entries.joinToString(",") { it.key + "=" + it.value }}"
        return best
    }

    /** Rescue relaxes the soft score threshold, never strict admission. */
    internal fun musicalRescue(
        out: SongMap,
        inn: SongMap,
        steps: Int?,
        outBpm: Double,
        inBpm: Double,
        exits: List<ExitCue>,
        entries: List<EntryCue>,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
    ): PhraseChoice? {
        if (outBpm <= 1 || inBpm <= 1 || limitedTo(out, inn) != null) return null
        if (out.downbeats.isEmpty() || inn.downbeats.isEmpty()) return null
        if (out.coarse && inn.coarse && abs(foldedRatio(outBpm, inBpm) - 1.0) > 0.15) return null
        var best: PhraseChoice? = null
        for (exit in exits.sortedByDescending { it.releaseSec }) for (entry in entries) {
            for (template in listOf(MixTemplate.M1, MixTemplate.M6)) {
                val handoff = if (template == MixTemplate.M6) {
                    scriptedEchoHandoff(exit, entry, out, inn, outBpm, inBpm, outAnalysis, inAnalysis)
                        ?: continue
                } else null
                if (handoff != null && qualifyMusicalHandoff(handoff, outAnalysis, inAnalysis, null, null)
                        .status != HandoffStatus.QUALIFIED) continue
                // Unknown tail coverage cannot turn a synthesized eight-bar
                // room into an admitted rescue. Use only covered source bars.
                val overlap = overlapOf(template, exit, entry, outBpm, steps)
                val aStart = if (handoff != null) exit.releaseBar - 1 else exit.releaseBar - overlap
                val bEnd = if (handoff != null) entry.landingBar + 1 else entry.startBar + overlap
                if (!coveredBars(out, aStart, exit.releaseBar) || !coveredBars(inn, entry.startBar, bEnd)) continue
                if (template == MixTemplate.M1 && (overlap <= 0 || overlap > exit.roomBars || overlap > entry.leadInBars)) continue
                if (veto(template, exit, entry, out, inn, steps, outBpm, inBpm, handoff) != null) continue
                val candidate = score(template, exit, entry, out, inn, steps, outBpm, inBpm, handoff)
                    .let { it.copy(detail = "rescue-${template.name} ${it.detail}") }
                val compiled = TransitionMoves.compile(candidate, outBpm, inBpm)
                val clock = LoopMix.Clock(compiled.plan)
                if (clock.elementAt > PhraseConfig.O_MAX_SEC + 1e-6 ||
                    abs(compiled.plan.inn.loopStart - entry.startSec) > 1e-6) continue
                // Only the soft ranking threshold is relaxed. Keep the real
                // score and all strict structural, vocal and rate vetoes.
                if (best == null || candidate.score > best.score) best = candidate
            }
        }
        return best
    }

    private fun checkPlanTime(startedMs: Long, now: () -> Long) {
        if (startedMs <= 0L) return
        val t = now()
        if (t > 0L && t - startedMs > PhraseConfig.PLAN_TIMEOUT_MS) throw Timeout("planner timeout")
    }

    /** Speech, no beat, or a skit only get the relaxed fade or a crossfade. */
    private fun limitedTo(out: SongMap, inn: SongMap): Set<MixTemplate>? {
        val quiet = out.speechLike || inn.speechLike || out.startKind == StartKind.SPOKEN || inn.startKind == StartKind.SPOKEN ||
            out.activation < PhraseConfig.ACTIVATION_FLOOR || inn.activation < PhraseConfig.ACTIVATION_FLOOR
        return if (quiet) setOf(MixTemplate.RELAXED, MixTemplate.M11) else null
    }

    private fun candidates(
        exit: ExitCue, entry: EntryCue, out: SongMap, inn: SongMap, steps: Int?,
    ): List<MixTemplate> {
        val list = ArrayList<MixTemplate>()
        when (exit.kind) {
            ExitKind.OUTRO_START, ExitKind.OUTRO_PHRASE -> {
                val vocalEntry = inn.startKind == StartKind.VOCAL_PICKUP || inn.startKind == StartKind.COLD_VOCAL
                val extend = exit.loopableRoom && exit.roomBars < entry.leadInBars && entry.leadInBars >= 8
                if (extend) list.add(MixTemplate.M2)
                if (!vocalEntry && !extend && entry.leadInVocal < 0.2f) list.add(MixTemplate.M1)
                if (entry.kind == EntryKind.INTRO_TRIM) list.add(MixTemplate.M3)
                if (vocalEntry) list.add(MixTemplate.M4)
                if (out.endingKind == EndingKind.OUTRO_VOCAL) list.add(MixTemplate.M5)
                if (!vocalEntry) list.add(MixTemplate.ROLLING)
                if (entry.kind == EntryKind.ACAPELLA) list.add(MixTemplate.VOCAL_OVER_OUTRO)
            }
            ExitKind.LAST_CHORUS_END -> {
                list.add(MixTemplate.M6)
                if (exit.tailBars >= 4 && entry.leadInVocal < 0.2f) list.add(MixTemplate.ROLLING)
                if (exit.tailBars >= 4) list.add(MixTemplate.M1)
            }
            ExitKind.POST_CHORUS_END -> {
                list.add(MixTemplate.M7)
                list.add(MixTemplate.M4)
                list.add(MixTemplate.M1)
            }
            ExitKind.FADE_START -> list.add(MixTemplate.M9)
            ExitKind.PRE_CHORUS_END -> list.add(MixTemplate.ENERGY_LIFT)
            // djmix25 P1.3/P1.4: VERSE_END strongly preferred over CONTENT_END
            // for pop/rap; BRIDGE_END preferred for ECHO/CUT_ON_ONE.
            ExitKind.VERSE_END -> {
                // Beat blend when the entry is instrumental, otherwise echo.
                if (entry.leadInVocal < 0.2f) list.add(MixTemplate.M1)
                list.add(MixTemplate.M6)
                list.add(MixTemplate.CUT_ON_ONE)
            }
            ExitKind.BRIDGE_END -> {
                list.add(MixTemplate.M6)
                list.add(MixTemplate.CUT_ON_ONE)
                list.add(MixTemplate.M10)
                list.add(MixTemplate.M11)
            }
            ExitKind.HARD_END, ExitKind.CONTENT_END -> {
                val vocalEntryEnd = inn.startKind == StartKind.VOCAL_PICKUP || inn.startKind == StartKind.COLD_VOCAL
                val extendLike = exit.loopableRoom && exit.roomBars < entry.leadInBars && entry.leadInBars >= 8
                if (exit.kind == ExitKind.CONTENT_END && exit.roomBars >= 4 && entry.leadInVocal < 0.2f && !vocalEntryEnd && !extendLike) {
                    list.add(MixTemplate.M1)
                }
                list.add(MixTemplate.M6)
                list.add(MixTemplate.CUT_ON_ONE)
                list.add(MixTemplate.M10)
            }
        }
        if (inn.startKind == StartKind.INTRO_BUILD || entry.kind == EntryKind.BUILD) list.add(MixTemplate.M8)
        if (exit.kind == ExitKind.FADE_START || exit.sectionFn == SectionFn.FADE_OUT || exit.role == SectionRole.AMBIENT ||
            inn.startKind == StartKind.AMBIENT
        ) {
            list.add(MixTemplate.RELAXED)
        }
        list.add(MixTemplate.M6)
        list.add(MixTemplate.M11)
        if (out.coarse || inn.coarse) {
            // djmix10: a noisy grid still earns a beat blend (M1) when the
            // fold-corrected ratio is close; echo/cut-only turned every
            // low-confidence streamed pair into a wasted chance. The overlap
            // length is shaped by lengthScore, not by vetoing M1 here.
            return list.filter { it == MixTemplate.M1 || it == MixTemplate.M2 || it == MixTemplate.ROLLING || it == MixTemplate.M6 || it == MixTemplate.M10 || it == MixTemplate.M11 || it == MixTemplate.RELAXED || it == MixTemplate.CUT_ON_ONE }
        }
        return list.distinct()
    }

    private fun veto(
        template: MixTemplate,
        exit: ExitCue,
        entry: EntryCue,
        out: SongMap,
        inn: SongMap,
        steps: Int?,
        outBpm: Double,
        inBpm: Double,
        handoff: MusicalHandoff? = null,
    ): String? {
        if (template == MixTemplate.ENERGY_LIFT) {
            if (out.chorusCount < PhraseConfig.LIFT_MIN_CHORUSES) return "lift needs two choruses"
            val ballad = out.sections.filter { it.fn == SectionFn.CHORUS }.all { it.energyLevel <= PhraseConfig.LIFT_MIN_ENERGY }
            if (ballad) return "ballad"
            val drums = entry.leadInVocal < 0.05f && entry.energyLevel >= 4
            if ((steps ?: 0) > 1 && !drums) return "lift key"
        }
        val aRole = out.sectionAt((exit.releaseBar - 1).coerceAtLeast(0))?.role ?: exit.role
        val bRole = inn.sectionAt(entry.landingBar)?.role ?: entry.landingRole
        if (aRole == SectionRole.TENSION && bRole != SectionRole.RELEASE) {
            return "tension needs a release"
        }
        if (aRole == SectionRole.RELEASE && (bRole == SectionRole.TENSION || bRole == SectionRole.RELEASE) &&
            template != MixTemplate.M6 && template != MixTemplate.M10 && template != MixTemplate.M11 && template != MixTemplate.CUT_ON_ONE
        ) {
            return "chorus on chorus"
        }
        val verse = out.sectionAt((exit.releaseBar - 1).coerceAtLeast(0))
        if (verse?.fn == SectionFn.VERSE && exit.releaseBar in (verse.startBar + 1) until verse.endBar) return "mid verse"
        if (!carve(template)) {
            // Two different questions, and they get two different answers.
            //
            // The beat count is a veto on the *structure*: more than one beat of
            // straddled threshold means the shapes genuinely overlap, and on a
            // pair we cannot weigh that is reason enough to look elsewhere.
            //
            // The weighted energy is a veto on the *sound*. Where it was
            // measured, it overrides the count, because a count of four beats at
            // 0.09 weighted is a soft verse sharing a bar with a bed and it
            // sounds fine, while a count of one beat at 0.80 is two choruses
            // colliding and it does not. That inversion is the whole reason the
            // weighted measure exists, so the veto has to honour it.
            val overlap = overlapOf(template, exit, entry, outBpm, steps)
            val actual = handoff?.let { echoCollision(out, inn, it, 60.0 / outBpm) }
            val clash = actual?.beats ?: vocalClashBeats(out, inn, exit, entry, overlap)
            val clashEnergy = if (actual != null) actual.energy else vocalClashEnergy(out, inn, exit, entry, overlap)
            // Peak-aware: mean alone dilutes a single loud bar (0.81/8=0.101).
            // Restore the beat-count floor unless the measured energy is
            // clearly soft (<0.05); unmeasured pairs stay on the count.
            val blocked = (clashEnergy != null && clashEnergy > CLASH_VETO_ENERGY) ||
                (clashEnergy == null && clash > 1.0) ||
                (clashEnergy != null && clashEnergy > 0.05 && clash > 1.0)
            if (blocked) return "vocal clash"
        }
        val vocal = inn.firstVocalBar
        if (!PhraseConfig.ALLOW_RADIO_EDIT && vocal != null && entry.startBar > vocal) return "cuts the verse"
        val allowSungExit = (exit.kind == ExitKind.CONTENT_END || exit.kind == ExitKind.HARD_END || exit.kind == ExitKind.FADE_START ||
            exit.kind == ExitKind.VERSE_END || exit.kind == ExitKind.BRIDGE_END || exit.cutsBars <= 2) &&
            (carve(template) || template in setOf(MixTemplate.M1, MixTemplate.M6, MixTemplate.M10, MixTemplate.M11, MixTemplate.RELAXED, MixTemplate.CUT_ON_ONE))
        // djmix25 P1.10: reverb+echo (M6/M10/CUT_ON_ONE) is never an illegal
        // combo; the FX rotation must never be vetoed for producing it.
        if (out.sungBars.getOrElse(exit.releaseBar) { false } && !allowSungExit) return "cuts a sung bar"
        if (!out.isPhraseDownbeat(exit.releaseBar) && out.anchors.none { it == exit.releaseBar }) return "A off the phrase"
        if (!inn.isPhraseDownbeat(entry.landingBar) && inn.anchors.none { it == entry.landingBar }) return "B off the phrase"
        val ratio = foldedRatio(outBpm, inBpm)
        val cap = if (entry.leadInVocal > 0.2f || exit.vocalRoomBars > 0) 0.06 else 0.08
        if (template !in setOf(MixTemplate.M6, MixTemplate.M10, MixTemplate.M11, MixTemplate.RELAXED, MixTemplate.CUT_ON_ONE) &&
            abs(ratio - 1.0) > cap
        ) {
            return "tempo"
        }
        if (abs(ratio - 1.0) > 0.12 && template !in setOf(MixTemplate.M6, MixTemplate.M10, MixTemplate.M11, MixTemplate.RELAXED)) {
            return "tempo"
        }
        val dip = exit.energyLevel - entry.energyLevel
        val outroQuiet = exit.sectionFn == SectionFn.OUTRO || exit.sectionFn == SectionFn.FADE_OUT || exit.energyLevel <= PhraseConfig.OUTRO_ENERGY_MAX
        val fadeMove = template == MixTemplate.RELAXED && (outroQuiet || exit.kind == ExitKind.FADE_START)
        if (dip > PhraseConfig.ENERGY_DIP_LEVELS && !outroQuiet && !fadeMove && template != MixTemplate.M6) {
            return "energy dip"
        }
        val jump = entry.energyLevel - exit.energyLevel
        if (jump > PhraseConfig.ENERGY_JUMP_LEVELS && template != MixTemplate.ENERGY_LIFT && template != MixTemplate.M7 && template != MixTemplate.M8) {
            return "energy jump"
        }
        if (template == MixTemplate.VOCAL_OVER_OUTRO && ((steps ?: 9) > 1 || inn.gridConfidence < 0.8f)) return "vocal outro fit"
        // djmix21 vocal continuity: no long empty beatmatch across the seam.
        // Long blends hear the gap; short rungs (echo/cut/vocal-carve) do not,
        // so vetoing here re-routes to a trimmed entry or a short rung instead
        // of riding an awkward no-vocal dead zone. Pure instrumental pairs
        // (no sung-bar evidence either side) are never gapped.
        if (template in LONG_BLENDS && (out.firstVocalBar != null || inn.firstVocalBar != null)) {
            val overlap = overlapOf(template, exit, entry, outBpm, steps)
            val gap = StructureMix.vocalGap(out, inn, exit, entry, overlap).totalBars
            if (gap > PhraseConfig.VOCAL_GAP_HARD_BARS) return "vocal gap"
            // Quiet-part waste guard: a long blend into a >50% quiet lead-in
            // with no buildup/drop/vocal event inside the overlap does nothing.
            if (StructureMix.quietLeadFraction(inn, entry) > 0.5f) {
                val eventBar = StructureMix.firstEventBarFromEntry(inn, entry)
                if (eventBar == null || eventBar - entry.startBar > overlap) return "quiet intro"
            }
        }
        // djmix12 (BK tempo discipline): the relaxed long blend is not exempt
        // from the 8% cap. Post-fold deltas beyond it downgrade to echo/cut
        // (M6/M10/M11/CUT_ON_ONE), never a long lurching ramp.
        if (template == MixTemplate.RELAXED && abs(ratio - 1.0) > PhraseConfig.TEMPO_HARD_DELTA) {
            return "tempo"
        }
        return null
    }

    private fun carve(template: MixTemplate) = template == MixTemplate.M4 || template == MixTemplate.M5 ||
        template == MixTemplate.ROLLING || template == MixTemplate.VOCAL_OVER_OUTRO

    /**
     * djmix21: long blends that hear a no-vocal seam. Echo/cuts/crossfades and
     * the vocal-carve templates never hear it the same way.
     */
    private val LONG_BLENDS = setOf(
        MixTemplate.M1, MixTemplate.M3, MixTemplate.ROLLING, MixTemplate.M2,
        MixTemplate.M7, MixTemplate.RELAXED, MixTemplate.M9,
    )

    /**
     * Energy-weighted vocal collision across a proposed overlap, 0..1, or null
     * when the pair carries no measurement to weigh.
     *
     * Bars are the only clock the planner has here — the two songs are on
     * different timelines and the overlap maps one onto the other — so each bar
     * contributes the *product* of the two vocal energies. Product, not
     * fraction, is what makes a solo vocal free: one track singing over an
     * instrumental bed is the normal, desirable overlap, and it scores zero
     * because the other side has no vocal energy to collide with.
     *
     * Replaces [vocalClashBeats] for scoring. The boolean count is kept for
     * [veto], where the question genuinely is "did the mask cross a line".
     */
    private fun vocalClashEnergy(out: SongMap, inn: SongMap, exit: ExitCue, entry: EntryCue, overlapBars: Int = 0): Double? {
        // Zero dry overlap (echo/cut templates) has no simultaneous collision
        // to measure; lead-in bars are not dried over the outgoing tail.
        if (overlapBars == 0) return if (out.vocalEnergy.isNotEmpty() && inn.vocalEnergy.isNotEmpty()) 0.0 else null
        val bars = minOf(exit.roomBars, maxOf(entry.leadInBars, overlapBars), PhraseConfig.O_MAX_BARS)
        if (bars <= 0) return null
        if (out.vocalEnergy.isEmpty() || inn.vocalEnergy.isEmpty()) return null
        var numerator = 0.0
        var denominator = 0.0
        for (i in 0 until bars) {
            val a = exit.releaseBar - bars + i
            val b = entry.startBar + i
            // Weight by the outgoing section's own cross-track-comparable energy:
            // a collision on a loud bar is audible, the same presence over a
            // quiet bar is not. A map without section energy weighs every bar
            // the same, which degrades to an unweighted mean rather than to a
            // wrong answer.
            val w = (out.sectionAt(a)?.energy ?: 0.5f).toDouble().coerceIn(0.0, 1.0)
            numerator += w * out.vocalEnergyAt(a) * inn.vocalEnergyAt(b)
            denominator += w
        }
        if (denominator <= 0.0) return null
        return (numerator / denominator).coerceIn(0.0, 1.0)
    }

    private fun vocalClashBeats(out: SongMap, inn: SongMap, exit: ExitCue, entry: EntryCue, overlapBars: Int = 0): Double {
        if (overlapBars == 0) return 0.0
        val bars = minOf(exit.roomBars, maxOf(entry.leadInBars, overlapBars), PhraseConfig.O_MAX_BARS)
        var beats = 0
        for (i in 0 until bars) {
            val a = exit.releaseBar - bars + i
            val b = entry.startBar + i
            if (out.sungBars.getOrElse(a) { false } && inn.sungBars.getOrElse(b) { false }) beats += out.beatsPerBar
        }
        return beats.toDouble()
    }

    private fun foldedRatio(outBpm: Double, inBpm: Double): Double {
        if (outBpm <= 1.0 || inBpm <= 1.0) return 1.0
        return (inBpm / alignedFold(outBpm, inBpm)) / outBpm
    }

    private fun coveredBars(map: SongMap, from: Int, until: Int): Boolean =
        from >= 0 && until > from && until <= map.sungBars.size &&
            until < map.downbeats.size &&
            (map.vocalEnergy.isEmpty() || until <= map.vocalEnergy.size)

    /**
     * Preserve the selected cue. A lead-in normally runs after A's dry seam;
     * only a cue that actually starts the declared pickup may precede it.
     * The incoming landing comes from B's own grid, including tracked drift.
     */
    internal fun scriptedEchoHandoff(
        exit: ExitCue,
        entry: EntryCue,
        out: SongMap,
        inn: SongMap,
        outBpm: Double,
        inBpm: Double,
        outAnalysis: TrackAnalysis? = null,
        inAnalysis: TrackAnalysis? = null,
    ): MusicalHandoff? {
        if (!outBpm.isFinite() || !inBpm.isFinite() || outBpm <= 1 || inBpm <= 1) return null
        val outBeat = 60.0 / outBpm
        val inBeat = 60.0 / inBpm
        val cut = exit.releaseSec
        val start = out.barTime((exit.releaseBar - 1).coerceAtLeast(0))
        val cue = entry.startSec
        val landing = inn.barTime(entry.landingBar)
        if (listOf(start, cut, cue, landing).any { !it.isFinite() || it < 0.0 } ||
            cut <= start || cut > out.durationSec + 1e-6 || landing < cue || landing >= inn.durationSec) return null
        val dryEndMix = cut - start
        val fallMix = (dryEndMix - 0.75 * outBeat).coerceAtLeast(0.0)
        fun timing(hold: Double): MusicalHandoff = MusicalHandoff(
            outCaptureStart = HandoffPoint(start, 0.0),
            outSendClose = HandoffPoint(cut, dryEndMix),
            outDryFallStart = HandoffPoint(start + fallMix, fallMix),
            outDryEnd = HandoffPoint(cut, dryEndMix),
            inCueSourceSec = cue,
            inHoldMixSec = hold,
            inAudibleStart = HandoffPoint(cue, hold),
            inFullLevelMixSec = hold,
            inLanding = HandoffPoint(landing, hold + landing - cue),
            wetRetireMixSec = maxOf(dryEndMix, hold + landing - cue) +
                0.05 + outBeat * 8 + PhraseConfig.exitTailSeconds(outBeat),
        )
        // Bar flags are not word-level lyric certainty. Pass the actual local
        // analyses; missing masks stay UNKNOWN. Bar collision is checked below.
        fun qualification(h: MusicalHandoff) = qualifyMusicalHandoff(h, outAnalysis, inAnalysis, null, null)
        var handoff = timing(dryEndMix)
        val pickupDuration = landing - cue
        val pickupBudget = entry.pickupBeats.coerceAtLeast(0f) * inBeat
        val explicitPickup = inn.startKind == StartKind.VOCAL_PICKUP && pickupBudget > 0.0 &&
            pickupDuration > 0.0 && pickupDuration <= pickupBudget + 1e-6 &&
            abs(cue - (landing - pickupBudget).coerceAtLeast(0.0)) <= 1e-6
        if (explicitPickup && pickupDuration < dryEndMix) {
            val early = timing(dryEndMix - pickupDuration)
            // Unknown mask coverage cannot authorize an overlap, even if the
            // SongMap's bar labels look clean.
            val collision = echoCollision(out, inn, early, outBeat)
            if (qualification(early).status == HandoffStatus.QUALIFIED &&
                collision.beats <= 1.0 && (collision.energy ?: 1.0) <= CLASH_VETO_ENERGY) handoff = early
        }
        // The app refuses an ornamental Echo without measured capture/cut
        // coverage. Enforce the same gate before scoring or displaying M6.
        if (qualification(handoff).status != HandoffStatus.QUALIFIED ||
            EchoAdmission.musicalRefusal(handoff, outAnalysis, inAnalysis) != null) return null
        val reservation = TransitionMoves.echoReservationBars(handoff, outBeat) * 4 * outBeat
        val handback = LoopMix.SETTLE_BARS * 4 * outBeat
        if (reservation + handback > PhraseConfig.O_MAX_SEC + 1e-6) return null
        return handoff
    }

    private data class EchoCollision(val beats: Double, val energy: Double?, val peak: Float)

    /** Integrate the actually audible unity-rate overlap on both source grids. */
    private fun echoCollision(out: SongMap, inn: SongMap, h: MusicalHandoff, outBeat: Double): EchoCollision {
        val from = h.inAudibleStart.mixSec
        val to = h.outDryEnd.mixSec
        if (to <= from) return EchoCollision(0.0,
            if (out.vocalEnergy.isNotEmpty() && inn.vocalEnergy.isNotEmpty()) 0.0 else null, 0f)
        val outOrigin = h.outCaptureStart.sourceSec - h.outCaptureStart.mixSec
        val inOrigin = h.inCueSourceSec - h.inHoldMixSec
        val boundaries = (listOf(from, to) + out.downbeats.map { it - outOrigin } +
            inn.downbeats.map { it - inOrigin }).filter { it in from..to }.distinct().sorted()
        var clashSec = 0.0
        var energy = 0.0
        var peak = 0f
        var measured = out.vocalEnergy.isNotEmpty() && inn.vocalEnergy.isNotEmpty()
        for (i in 0 until boundaries.lastIndex) {
            val duration = boundaries[i + 1] - boundaries[i]
            val mid = (boundaries[i + 1] + boundaries[i]) * 0.5
            val a = out.downbeats.indexOfLast { it <= outOrigin + mid }
            val inSource = inOrigin + mid
            val b = inn.downbeats.indexOfLast { it <= inSource }.let {
                if (it < 0 && inn.pickupBeats > 0f && inSource >= 0.0 &&
                    inSource >= inn.barTime(0) - inn.pickupBeats * (60.0 / inn.bpm)) 0 else it
            }
            if (a !in out.sungBars.indices || b !in inn.sungBars.indices) {
                measured = false
                clashSec += duration // unmeasured overlap is never clean room
                continue
            }
            if (a !in out.vocalEnergy.indices || b !in inn.vocalEnergy.indices) measured = false
            val product = out.vocalEnergyAt(a) * inn.vocalEnergyAt(b)
            energy += duration * product
            peak = maxOf(peak, product.toFloat())
            if (out.sungBars[a] && inn.sungBars[b]) clashSec += duration
        }
        return EchoCollision(clashSec / outBeat, if (measured) energy / (to - from) else null, peak)
    }

    private fun score(
        template: MixTemplate,
        exit: ExitCue,
        entry: EntryCue,
        out: SongMap,
        inn: SongMap,
        steps: Int?,
        outBpm: Double,
        inBpm: Double,
        handoff: MusicalHandoff? = null,
    ): PhraseChoice {
        val overlap = overlapOf(template, exit, entry, outBpm, steps)
        val struct = structScore(template, exit, entry, out)
        val actual = handoff?.let { echoCollision(out, inn, it, 60.0 / outBpm) }
        val clashBeats = actual?.beats ?: vocalClashBeats(out, inn, exit, entry, overlap)
        // Energy-weighted collision where it was measured, and the old beat
        // count normalised onto the same 0..1 axis where it was not. The two are
        // not the same measurement, so each keeps its own meaning: the weighted
        // one is what the listener hears, the fallback is a conservative proxy
        // that reads a straddled threshold as a real collision.
        val clashEnergy = if (actual != null) actual.energy else vocalClashEnergy(out, inn, exit, entry, overlap)
        val clash = clashEnergy ?: (clashBeats / 4.0).coerceIn(0.0, 1.0)
        val vocalScore = if (carve(template)) 0.8 else 1.0 - clash
        val phrase = phraseScore(out, inn, exit.releaseBar, entry.landingBar)
        val delta = entry.energyAtLanding - exit.energyAtRelease
        val energy = if (template == MixTemplate.M10) 0.8 else energyScore(delta)
        val length = lengthScore(overlap, outBpm, entry, exit)
        val key = keyScore(steps, carve(template) || template == MixTemplate.M6)
        val ratio = foldedRatio(outBpm, inBpm)
        val cap = if (entry.leadInVocal > 0.2f) 0.06 else 0.08
        val tempo = (1.0 - abs(ratio - 1.0) / cap).coerceIn(0.0, 1.0)
        val loopRepeats = if (template == MixTemplate.M2) {
            val need = entry.leadInBars.coerceAtLeast(16)
            val extra = ((need - exit.roomBars).coerceAtLeast(0) / 4.0).roundToInt()
            (extra - 2).coerceAtLeast(0)
        } else 0
        val discardedSections = out.sections.filter { it.endBar > exit.releaseBar && it.fn != SectionFn.OUTRO && it.fn != SectionFn.FADE_OUT && it.fn != SectionFn.SILENCE }
        val vocalSectionPenalty = if (discardedSections.any { it.vocal > 0.20f }) 0.25 else 0.0
        val chorusReleasePenalty = if (discardedSections.any { it.fn == SectionFn.CHORUS || it.role == SectionRole.RELEASE }) 0.35 else 0.0
        // djmix21 structure scoring (scalars only, each from its own track):
        // - vocal gap above the soft budget costs, so a trimmable entry or a
        //   short rung beats a long empty beatmatch;
        // - a vocal handoff seam (A's last sung bar 1-2 before B's first) pays;
        // - a cue at the head buildup start pays, a cue missing the buildup
        //   (started inside it) costs, and a quiet non-spotlight landing costs.
        // Pure instrumental pairs (no sung-bar evidence either side) are never
        // gapped: vocal-gap costs/bonuses only apply when at least one side
        // has sung evidence (djmix21 instrumental-evidence guard).
        val hasVocalEvidence = out.firstVocalBar != null || inn.firstVocalBar != null
        val gapBars = if (hasVocalEvidence) StructureMix.vocalGap(out, inn, exit, entry, overlap).totalBars else 0
        // djmix21: gap above the soft budget is a tie-breaker only (capped at
        // 0.05) — the hard veto above does the real work for truly long seams.
        // Uncapped, the cost would cut A's own outro spotlight early to chase
        // a shorter (but same-landing) overlap.
        val gapCost = (0.02 * (gapBars - PhraseConfig.VOCAL_GAP_SOFT_BARS).coerceAtLeast(0)).coerceAtMost(0.05)
        val seam = if (hasVocalEvidence) StructureMix.vocalSeamBars(out, inn, exit, entry) else null
        val seamBonus = if (seam != null && seam in 1..2) 0.10 else 0.0
        val buildup = StructureMix.headBuildup(inn)
        val atBuildupStart = buildup != null && entry.startBar == buildup.startBar
        val insideBuildup = buildup != null && entry.startBar > buildup.startBar && entry.startBar < buildup.endBar
        val landingAtPeak = buildup != null && entry.landingBar in buildup.endBar..buildup.endBar + 1
        val buildupBonus = (if (atBuildupStart) 0.08 else 0.0) + (if (landingAtPeak) 0.05 else 0.0)
        val quietLanding = !StructureMix.isSpotlight(entry) && StructureMix.quietLeadFraction(inn, entry) > 0.5f
        // djmix25 P1.2: an exit at a repeated-bar index costs less (the
        // phrase can be loop-extended seamlessly), and P1.4: section
        // alignment (exit at a section boundary, entry landing at one) is
        // a stackable bonus.
        val repeatedExit = InterpolationAnalyzer.repeatedBars(out, exit.releaseBar) > 0
        val interpDiscount = if (repeatedExit) 0.04 else 0.0
        val exitAtBoundary = out.sections.any { it.endBar == exit.releaseBar }
        val entryAtBoundary = inn.sections.any { it.startBar == entry.startBar || it.startBar == entry.landingBar }
        val alignBonus = (if (exitAtBoundary) 0.03 else 0.0) + (if (entryAtBoundary) 0.03 else 0.0)
        // VERSE_END strongly preferred over CONTENT_END (pop/rap default);
        // BRIDGE_END preferred for ECHO/CUT (the echo-out seam).
        val bridgeVerseChop = (
            (exit.kind == ExitKind.VERSE_END || exit.kind == ExitKind.BRIDGE_END) &&
                exit.cutsBars > 8 && exit.cutsSungBars > 2 &&
                out.sections.lastOrNull()?.endBar != exit.releaseBar
            )
        val kindBonus = when {
            exit.kind == ExitKind.VERSE_END && template == MixTemplate.M1 && entry.leadInVocal < 0.2f && !bridgeVerseChop -> 0.08
            exit.kind == ExitKind.VERSE_END && template == MixTemplate.M6 && !bridgeVerseChop -> 0.05
            exit.kind == ExitKind.BRIDGE_END && template in setOf(MixTemplate.M6, MixTemplate.CUT_ON_ONE, MixTemplate.M10) && !bridgeVerseChop -> 0.06
            else -> 0.0
        }
        // Discarded vocal energy, integrated over the bars being thrown away
        // rather than counted. The old term charged 0.12 per sung bar cut, so
        // four sung bars cost 0.48 — more than the entire weighted signal and
        // enough to wipe out any advantage the vocal term had just scored.
        // Multiplying the section's own vocal level by the bar count keeps the
        // charge proportional to what the listener actually loses.
        val discardedVocalEnergy = discardedSections
            .filter { it.vocal > 0.0f }
            .sumOf { (it.vocal.coerceIn(0.0f, 1.0f).toDouble()) * (it.endBar - it.startBar).coerceAtLeast(0) }
        // Where the collision was measured it is charged here too, so a loud
        // clash cannot be laundered into a good score by a long overlap. The
        // coefficient is small because veto() already refuses anything above
        // CLASH_VETO_ENERGY; this is the tail below that line.
        val clashCost = if (clashEnergy != null) 0.30 * clashEnergy else 0.0
        val cost = 0.20 * discardedVocalEnergy + 0.01 * exit.cutsBars + 0.015 * entry.trimmedBars + 0.05 * loopRepeats +
            vocalSectionPenalty + chorusReleasePenalty + gapCost + clashCost +
            (if (insideBuildup && !atBuildupStart) 0.15 else 0.0) +
            (if (quietLanding) 0.15 else 0.0) - interpDiscount
        // Stacked format bonuses used to reach 0.37 while the largest possible
        // vocal penalty was 0.20: a dual-chorus collision could be outvoted by
        // seam + buildup + alignment + kind preferences, all of which simply
        // restate that the planner likes the shape of this exit. Capped at
        // 0.08 — bonuses may break ties, they may not overturn a measured
        // musical defect.
        val bonuses = (seamBonus + buildupBonus + alignBonus + kindBonus).coerceAtMost(0.08)
        val total = 0.18 * struct + 0.30 * vocalScore + 0.12 * phrase + 0.14 * energy +
            0.08 * length + 0.08 * key + 0.10 * tempo - cost + bonuses
        val (delayName, delay) = PhraseConfig.echoDelayBeats(
            if (template == MixTemplate.M6) outBpm else inBpm, template == MixTemplate.ROLLING)
        val (rampName, ramp) = PhraseConfig.swapRampMs(template.name)
        val swap = when (template) {
            MixTemplate.M1, MixTemplate.M3 -> -overlap / 2.0
            MixTemplate.M7 -> 0.0
            MixTemplate.M4 -> 0.0
            else -> 0.0
        }
        val tail = when (template) {
            MixTemplate.M4 -> 4.0
            MixTemplate.M1, MixTemplate.M3 -> 1.0
            else -> 0.0
        }
        val loopK = 0
        val prep = tempoPrepBars(outBpm, inBpm, entry.leadInVocal > 0.2f || exit.vocalRoomBars > 0)
        var product = 0f
        val bars = minOf(exit.roomBars, entry.leadInBars, overlap)
        for (i in 0 until bars) {
            val a = if (out.sungBars.getOrElse(exit.releaseBar - bars + i) { false }) 1f else 0f
            val b = if (inn.sungBars.getOrElse(entry.startBar + i) { false }) 1f else 0f
            if (a * b > product) product = a * b
        }
        return PhraseChoice(
            template = template,
            rung = rungOf(template),
            score = total,
            exit = exit,
            entry = entry,
            overlapBars = overlap,
            swapBar = swap,
            tailBeats = tail,
            loopK = loopK,
            echoGated = template == MixTemplate.M6 || template == MixTemplate.ROLLING || template == MixTemplate.M5,
            vocalProduct = actual?.peak ?: product,
            tempoPrepBars = prep,
            echoDelayBeats = delay,
            echoDelayName = delayName,
            swapRampMs = ramp,
            swapRampName = rampName,
            tempoPolicy = if (PhraseConfig.TEMPO_POLICY == "b") "TEMPO_POLICY=b" else "TEMPO_POLICY=a",
            vocalOverlapBeats = clashBeats,
            energyDelta = delta,
            struct = struct,
            vocalScore = vocalScore,
            phraseScore = phrase,
            energyScore = energy,
            lengthScore = length,
            keyScore = key,
            tempoScore = tempo,
            cost = cost,
            detail = "${template.name} ${exit.kind}→${entry.kind} O=$overlap",
            keyClash = (steps ?: 0) > 2,
            vocalClashEnergy = clashEnergy,
            handoff = handoff,
        )
    }

    private fun overlapOf(template: MixTemplate, exit: ExitCue, entry: EntryCue, bpm: Double, steps: Int? = null): Int {
        val natural = minOf(exit.roomBars, entry.leadInBars).coerceAtLeast(0)
        val clash = (steps ?: 0) > 2
        val snapped = if (clash) {
            listOf(8, 4).firstOrNull { it <= natural } ?: natural.coerceIn(4, 8)
        } else {
            listOf(32, 16, 8, 4).firstOrNull { it <= natural } ?: natural.coerceAtMost(4)
        }
        return when (template) {
            MixTemplate.M7 -> if (clash) 8 else 16
            MixTemplate.M4 -> if (clash) entry.leadInBars.coerceIn(4, 8) else entry.leadInBars.coerceAtLeast(0)
            MixTemplate.M2 -> if (clash) entry.leadInBars.coerceIn(4, 8) else entry.leadInBars.coerceAtMost(16).coerceAtLeast(exit.roomBars)
            MixTemplate.M6, MixTemplate.CUT_ON_ONE, MixTemplate.M10 -> 0
            MixTemplate.M9, MixTemplate.RELAXED -> if (clash) minOf(8, entry.leadInBars.coerceAtLeast(4)) else minOf(16, entry.leadInBars.coerceAtLeast(8))
            else -> snapped.coerceAtLeast(if (template == MixTemplate.M11) 0 else 4)
        }
    }

    private fun structScore(template: MixTemplate, exit: ExitCue, entry: EntryCue, out: SongMap? = null): Double {
        val outro = exit.kind == ExitKind.OUTRO_START || exit.kind == ExitKind.OUTRO_PHRASE
        // vc73 A3: never let VERSE_END/BRIDGE_END chop a full final
        // chorus/outro (>8 bars, >2 sung bars) early just to fire the kind
        // bonus. When the cut discards more than a phrase of sung music,
        // fall through to the neutral else so CONTENT_END/OUTRO_START wins.
        val chopsFinal = (
            (exit.kind == ExitKind.VERSE_END || exit.kind == ExitKind.BRIDGE_END) &&
                exit.cutsBars > 8 && exit.cutsSungBars > 2 &&
                out?.sections?.lastOrNull()?.endBar != exit.releaseBar
            )
        // djmix25 P1.4: BRIDGE_END is the natural echo/cut seam; VERSE_END
        // outranks a CONTENT_END fade for pop/rap-style vocal entries.
        if (!chopsFinal && exit.kind == ExitKind.BRIDGE_END && template in setOf(MixTemplate.M6, MixTemplate.CUT_ON_ONE, MixTemplate.M10, MixTemplate.M11)) return 0.95
        if (!chopsFinal && exit.kind == ExitKind.VERSE_END && template == MixTemplate.M1 && entry.leadInVocal < 0.2f) return 0.92
        if (!chopsFinal && exit.kind == ExitKind.VERSE_END && template == MixTemplate.M6) return 0.82
        return when {
            template == MixTemplate.M1 && (outro || (exit.kind == ExitKind.CONTENT_END && exit.roomBars >= 4)) && entry.leadInVocal < 0.2f -> 1.0
            template == MixTemplate.M4 && (entry.kind == EntryKind.HEAD) && exit.roomBars >= 4 -> 0.8
            template == MixTemplate.M6 -> 0.7
            template == MixTemplate.M9 && exit.kind == ExitKind.FADE_START -> 0.8
            template == MixTemplate.M2 -> 0.9
            template == MixTemplate.ENERGY_LIFT && exit.kind == ExitKind.PRE_CHORUS_END -> 0.85
            template == MixTemplate.ROLLING && outro -> 0.75
            template == MixTemplate.M7 -> 0.9
            template == MixTemplate.RELAXED -> 0.7
            template == MixTemplate.M11 -> 0.4
            else -> 0.55
        }
    }

    private fun phraseScore(out: SongMap, inn: SongMap, release: Int, landing: Int): Double {
        val a8 = out.isPhraseDownbeat(release, prefer8 = true) && (release - (out.anchors.lastOrNull { it <= release } ?: out.phraseAnchor)) % 8 == 0
        val b8 = inn.isPhraseDownbeat(landing, prefer8 = true) && (landing - (inn.anchors.lastOrNull { it <= landing } ?: inn.phraseAnchor)) % 8 == 0
        val a4 = out.isPhraseDownbeat(release)
        val b4 = inn.isPhraseDownbeat(landing)
        return when {
            a8 && b8 -> 1.0
            (a8 && b4) || (b8 && a4) -> 0.7
            a4 && b4 -> 0.5
            else -> 0.2
        }
    }

    private fun energyScore(delta: Float): Double = when {
        delta in -0.10f..0.25f -> 1.0
        delta < -0.10f -> (1.0 - ((-0.10 - delta) / 0.35)).coerceIn(0.0, 1.0)
        else -> (1.0 - ((delta - 0.25) / 0.70) * 0.5).coerceIn(0.5, 1.0)
    }

    private fun lengthScore(overlap: Int, bpm: Double, entry: EntryCue, exit: ExitCue): Double {
        val target = when {
            entry.leadInVocal < 0.2f && exit.vocalRoomBars == 0 && bpm >= 100 -> 16.0
            bpm < 100 -> 8.0
            exit.energyAtRelease < PhraseConfig.BALLAD_ENERGY -> 4.0
            else -> 8.0
        }
        if (target <= 0) return 1.0
        val x = (overlap - target) / target
        return exp(-x * x * 2.0)
    }

    private fun keyScore(steps: Int?, carved: Boolean): Double {
        val raw = when (steps) {
            null, 0, 1 -> 1.0
            2 -> 0.6
            else -> 0.2
        }
        return if (carved) raw.coerceAtLeast(0.6) else raw
    }

    private fun tempoPrepBars(outBpm: Double, inBpm: Double, vocal: Boolean): Int {
        if (PhraseConfig.TEMPO_POLICY != "b") return 0
        val delta = abs(foldedRatio(outBpm, inBpm) - 1.0)
        if (delta < 0.001) return 0
        val cap = if (vocal) 0.06 else 0.08
        val absorbed = minOf(delta, cap)
        val bars = kotlin.math.ceil(absorbed / PhraseConfig.TEMPO_STEP_PER_BAR).toInt()
        return bars.coerceIn(0, PhraseConfig.TEMPO_PREP_BARS)
    }

    fun rungOf(template: MixTemplate): String = when (template) {
        MixTemplate.M1, MixTemplate.M3 -> "Beat blend"
        MixTemplate.M2 -> "Loop mix"
        MixTemplate.M4, MixTemplate.VOCAL_OVER_OUTRO -> "Vocal blend"
        MixTemplate.M5 -> "Vocal blend"
        MixTemplate.M6 -> "Echo"
        MixTemplate.M7 -> "Drop mix"
        MixTemplate.M8, MixTemplate.ENERGY_LIFT -> "Lift"
        MixTemplate.M9, MixTemplate.RELAXED -> "Smooth fade"
        MixTemplate.M10, MixTemplate.CUT_ON_ONE -> "Cut"
        MixTemplate.M11 -> "Crossfade"
        MixTemplate.ROLLING -> "Rolling blend"
    }

    /**
     * Weighted vocal collision above which an overlap is refused outright.
     *
     * Deliberately not [VOCAL_CLASH_TOLERANCE], which is 0.05 and answers a
     * different question. That constant is "how much simultaneous vocal can a
     * transition carry and still count as clean" — a rendering target, and a
     * strict one. This is "how bad does a measured collision have to be before
     * searching for a different exit is worth more than reshaping this one".
     *
     * 0.20 is the point where the two measured cases separate cleanly: the soft
     * verse scores 0.090 and survives, the chorus collision scores 0.810 and is
     * refused. Anything between 0.10 and 0.79 gives the same verdict on those
     * two, so the value is set near the middle of that band rather than at an
     * edge — leaving room for a moderately loud pair to be reshaped rather than
     * rejected, because a reachable exit with a ducked vocal usually beats
     * jumping to a four-bar phrase boundary.
     */
    const val CLASH_VETO_ENERGY = 0.20
}
