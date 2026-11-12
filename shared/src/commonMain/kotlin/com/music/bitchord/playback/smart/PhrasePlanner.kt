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
                if (fresh != null && fresh.template == held.template) choice = fresh
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
            val echo = held.copy(template = MixTemplate.M6, rung = "Echo", score = held.score * 0.9)
            choice = echo
            return echo
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
    ): PhraseChoice? {
        if (!PhraseConfig.PHRASE_PLANNER_ENABLED) return null
        if (out == null) return null
        if (legacyLoop != null && legacyLoop.outLoops && legacyLoop.inLoops) {
            return loopCrown(legacyLoop, out, inn)
        }
        if (inn == null) return null
        return try {
            choose(out, inn, camelotSteps, outBpm, inBpm, startedMs, now)
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
    ): PhraseChoice? {
        val limited = limitedTo(out, inn)
        val exits = CuePoints.exits(out)
        val entries = CuePoints.entries(inn)
        var best: PhraseChoice? = null
        for (exit in exits) {
            for (entry in entries) {
                checkPlanTime(startedMs, now)
                for (template in candidates(exit, entry, out, inn, steps)) {
                    if (limited != null && template !in limited) continue
                    val ratio = foldedRatio(outBpm, inBpm)
                    if (abs(ratio - 1.0) > 0.12 && template !in setOf(
                            MixTemplate.M6, MixTemplate.M10, MixTemplate.RELAXED,
                        )
                    ) {
                        continue
                    }
                    val veto = veto(template, exit, entry, out, inn, steps, outBpm, inBpm)
                    if (veto != null) continue
                    val choice = score(template, exit, entry, out, inn, steps, outBpm, inBpm)
                    if (best == null || choice.score > best.score + 1e-6) best = choice
                }
            }
        }
        if (best == null || best.score < PhraseConfig.S_MIN) return null
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
            ExitKind.HARD_END, ExitKind.CONTENT_END -> {
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
            return list.filter { it == MixTemplate.M6 || it == MixTemplate.M10 || it == MixTemplate.M11 || it == MixTemplate.RELAXED || it == MixTemplate.CUT_ON_ONE }
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
            val clash = vocalClashBeats(out, inn, exit, entry)
            if (clash > 1.0) return "vocal clash"
        }
        val vocal = inn.firstVocalBar
        if (!PhraseConfig.ALLOW_RADIO_EDIT && vocal != null && entry.startBar > vocal) return "cuts the verse"
        if (out.sungBars.getOrElse(exit.releaseBar) { false }) return "cuts a sung bar"
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
        return null
    }

    private fun carve(template: MixTemplate) = template == MixTemplate.M4 || template == MixTemplate.M5 ||
        template == MixTemplate.ROLLING || template == MixTemplate.VOCAL_OVER_OUTRO

    private fun vocalClashBeats(out: SongMap, inn: SongMap, exit: ExitCue, entry: EntryCue): Double {
        val bars = minOf(exit.roomBars, entry.leadInBars, PhraseConfig.O_MAX_BARS)
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
        var ratio = inBpm / outBpm
        while (ratio > 1.5) ratio /= 2.0
        while (ratio < 0.75) ratio *= 2.0
        return ratio
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
    ): PhraseChoice {
        val overlap = overlapOf(template, exit, entry, outBpm)
        val struct = structScore(template, exit, entry)
        val clash = vocalClashBeats(out, inn, exit, entry)
        val vocalScore = if (carve(template)) 0.8 else 1.0 - (clash / 4.0).coerceIn(0.0, 1.0)
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
        val cost = 0.04 * exit.cutsSungBars + 0.01 * exit.cutsBars + 0.015 * entry.trimmedBars + 0.05 * loopRepeats
        val total = 0.25 * struct + 0.20 * vocalScore + 0.15 * phrase + 0.15 * energy +
            0.10 * length + 0.10 * key + 0.05 * tempo - cost
        val (delayName, delay) = PhraseConfig.echoDelayBeats(inBpm, template == MixTemplate.ROLLING)
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
        val loopK = if (template == MixTemplate.M2) {
            val need = entry.leadInBars.coerceAtLeast(16)
            val loopLen = 4
            ((need - exit.roomBars).coerceAtLeast(0) + loopLen - 1) / loopLen
        } else 0
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
            vocalProduct = product,
            tempoPrepBars = prep,
            echoDelayBeats = delay,
            echoDelayName = delayName,
            swapRampMs = ramp,
            swapRampName = rampName,
            tempoPolicy = if (PhraseConfig.TEMPO_POLICY == "b") "TEMPO_POLICY=b" else "TEMPO_POLICY=a",
            vocalOverlapBeats = clash,
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
        )
    }

    private fun overlapOf(template: MixTemplate, exit: ExitCue, entry: EntryCue, bpm: Double): Int {
        val natural = minOf(exit.roomBars, entry.leadInBars).coerceAtLeast(0)
        val snapped = listOf(32, 16, 8, 4).firstOrNull { it <= natural } ?: natural.coerceAtMost(4)
        return when (template) {
            MixTemplate.M7 -> 16
            MixTemplate.M4 -> entry.leadInBars.coerceAtLeast(0)
            MixTemplate.M2 -> entry.leadInBars.coerceAtMost(16).coerceAtLeast(exit.roomBars)
            MixTemplate.M6, MixTemplate.CUT_ON_ONE, MixTemplate.M10 -> 0
            MixTemplate.M9, MixTemplate.RELAXED -> minOf(16, entry.leadInBars.coerceAtLeast(8))
            else -> snapped.coerceAtLeast(if (template == MixTemplate.M11) 0 else 4)
        }
    }

    private fun structScore(template: MixTemplate, exit: ExitCue, entry: EntryCue): Double {
        val outro = exit.kind == ExitKind.OUTRO_START || exit.kind == ExitKind.OUTRO_PHRASE
        return when {
            template == MixTemplate.M1 && outro && entry.leadInVocal < 0.2f -> 1.0
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
}
