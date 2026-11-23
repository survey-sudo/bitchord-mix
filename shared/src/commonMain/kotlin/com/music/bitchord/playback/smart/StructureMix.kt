package com.music.bitchord.playback.smart

/**
 * Structure-aware blend helpers. Everything here reads one track's
 * own scalars (bars, sections, sung flags) and returns scalars. No frame is
 * ever compared across tracks — the caller pairs two scalars and scores them,
 * which is the US 8,280,539-safe shape the planner already uses.
 *
 * Three jobs:
 *  - vocal-gap budget: how many continuous no-vocal bars a seam would play;
 *  - buildup detection: the quiet rising section at the head of B;
 *  - spotlight/energy checks: whether the landing is a real event.
 */
object StructureMix {

    /** One track's own bars, consulted independently. */
    data class GapResult(
        /** Trailing no-vocal bars on A inside the exit window. */
        val trailBars: Int,
        /** Leading no-vocal bars on B from the entry cue. */
        val leadBars: Int,
    ) {
        /** Continuous no-vocal run across the seam, in bars. */
        val totalBars: Int get() = trailBars + leadBars
    }

    /**
     * Trailing instrumental run on A: consecutive non-sung bars ending just
     * before [ExitCue.releaseBar], looking back at most [overlapBars]. If A
     * has no sung bar in the window the whole window is gap.
     */
    fun trailingNoVocalBars(out: SongMap, exit: ExitCue, overlapBars: Int): Int {
        val window = overlapBars.coerceAtLeast(0)
        // per-side evidence: a track that never sings has no vocal
        // gap — its outro is structure, not a dead zone after a vocal.
        if (out.firstVocalBar == null) return 0
        var trail = 0
        var b = exit.releaseBar - 1
        while (b >= 0 && trail < window) {
            if (out.sungBars.getOrElse(b) { false }) break
            trail++
            b--
        }
        return trail
    }

    /**
     * Leading instrumental run on B: consecutive non-sung bars from
     * [EntryCue.startBar] toward the landing, at most [overlapBars]. A first
     * sung bar (or a chorus/drop event, which the planner treats as landing)
     * stops the count.
     */
    fun leadingNoVocalBars(inn: SongMap, entry: EntryCue, overlapBars: Int): Int {
        val window = overlapBars.coerceAtLeast(0)
        // per-side evidence: a track that never sings has no intro
        // vocal gap — its intro is structure, not dead air before a vocal.
        if (inn.firstVocalBar == null) return 0
        val event = firstEventBarFromEntry(inn, entry)
        val cap = if (event != null) (event - entry.startBar).coerceAtLeast(0) else window
        val limit = minOf(window, cap)
        var lead = 0
        var b = entry.startBar
        while (b < entry.landingBar && lead < limit) {
            if (inn.sungBars.getOrElse(b) { false }) break
            lead++
            b++
        }
        return lead
    }

    /** No-vocal run across the seam for one (exit, entry) candidate. */
    fun vocalGap(out: SongMap, inn: SongMap, exit: ExitCue, entry: EntryCue, overlapBars: Int): GapResult =
        GapResult(
            trailBars = trailingNoVocalBars(out, exit, overlapBars),
            leadBars = leadingNoVocalBars(inn, entry, overlapBars),
        )

    /**
     * Bars between A's last sung bar before the release and B's first sung
     * bar at/after the entry cue. Positive: A ends that many bars before B
     * starts (a vocal handoff seam). Negative: the two overlap. Null when
     * either side carries no sung-bar evidence (pure instrumental pair),
     * so the seam logic stays out of the way entirely.
     */
    fun vocalSeamBars(out: SongMap, inn: SongMap, exit: ExitCue, entry: EntryCue): Int? {
        val aLast = (0 until exit.releaseBar).lastOrNull { out.sungBars.getOrElse(it) { false } }
        val bFirst = (entry.startBar until inn.sungBars.size).firstOrNull { inn.sungBars[it] }
        if (aLast == null || bFirst == null) return null
        return bFirst - (aLast + 1)
    }

    /**
     * Fraction of B's lead-in bars sitting in a quiet section
     * (energyLevel <= 2: near-silence or a hushed intro half). 0 when there
     * is no lead-in or no section covers it.
     */
    fun quietLeadFraction(inn: SongMap, entry: EntryCue): Float {
        val bars = entry.leadInBars
        if (bars <= 0) return 0f
        var quiet = 0
        for (b in entry.startBar until entry.landingBar) {
            val section = inn.sectionAt(b) ?: continue
            if (section.energyLevel <= 2) quiet++
        }
        return quiet.toFloat() / bars
    }

    /**
     * First meaningful event at/after the entry cue: the first sung bar, or
     * the first chorus/drop/build section start, whichever comes first.
     * Returns absolute bar, or null when B has neither.
     */
    fun firstEventBarFromEntry(inn: SongMap, entry: EntryCue): Int? {
        val sung = (entry.startBar until inn.sungBars.size).firstOrNull { inn.sungBars[it] }
        val event = inn.sections.firstOrNull {
            it.startBar >= entry.startBar &&
                (it.fn == SectionFn.CHORUS || it.fn == SectionFn.DROP || it.fn == SectionFn.BUILD)
        }
        return when {
            sung != null && event != null -> minOf(sung, event.startBar)
            sung != null -> sung
            event != null -> event.startBar
            else -> null
        }
    }

    /**
     * The head buildup on B: the first BUILD or PRE_CHORUS section within the
     * first 64 bars (a quiet intro rising into chorus/drop/first vocal).
     * [SongMap] sections carry per-section energy slope, so a rising energy
     * slope counts even when the label is only INTRO.
     */
    fun headBuildup(inn: SongMap): PhraseSection? {
        val labelled = inn.sections.firstOrNull {
            it.startBar < PhraseConfig.HEAD_STRUCTURE_BARS &&
                (it.fn == SectionFn.BUILD || it.fn == SectionFn.PRE_CHORUS)
        }
        if (labelled != null) return labelled
        // Unlabelled but rising: INTRO head with a positive energy slope.
        return inn.sections.firstOrNull {
            it.startBar == 0 && it.fn == SectionFn.INTRO && it.energySlope >= 0.12f &&
                inn.sections.any { s -> s.startBar >= it.endBar && (s.fn == SectionFn.CHORUS || s.fn == SectionFn.DROP) }
        }
    }

    /**
     * True when a landing on this [EntryCue] is a real spotlight: first
     * vocal, chorus, drop, or a buildup peak — not a quiet non-event intro.
     */
    fun isSpotlight(entry: EntryCue): Boolean {
        if (entry.leadInVocal >= 0.2f) return true
        return entry.landingFn == SectionFn.CHORUS ||
            entry.landingFn == SectionFn.DROP ||
            entry.landingFn == SectionFn.BUILD ||
            entry.landingFn == SectionFn.PRE_CHORUS
    }
}
