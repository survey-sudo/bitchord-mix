/*
 * BitChord: the Automix loop mix, a DJ transition on two beat-tracked songs.
 * Ported from the web player's playback/automix/engine/loopMix.ts (which
 * builds on AutoMix by David Finnegan, AGPL-3.0; see that folder's NOTICE.md).
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version. Combined into BitChord (GPL-3.0-or-later) under
 * section 13 of the AGPL.
 */

package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

import com.music.bitchord.playback.smart.LoopMix.BUILDUP_HP_END_HZ
import com.music.bitchord.playback.smart.LoopMix.Check
import com.music.bitchord.playback.smart.LoopMix.ENTER_BARS
import com.music.bitchord.playback.smart.LoopMix.EXIT_BARS
import com.music.bitchord.playback.smart.LoopMix.IN_SEARCH_SEC
import com.music.bitchord.playback.smart.LoopMix.LOOP_BARS
import com.music.bitchord.playback.smart.LoopMix.LOOP_LENGTHS
import com.music.bitchord.playback.smart.LoopMix.LYRIC_GUARD_SEC
import com.music.bitchord.playback.smart.LoopMix.MAX_BPM_PER_BAR
import com.music.bitchord.playback.smart.LoopMix.MAX_RAMP_BARS
import com.music.bitchord.playback.smart.LoopMix.MIN_LOUDNESS
import com.music.bitchord.playback.smart.LoopMix.MIN_RAMP_BARS
import com.music.bitchord.playback.smart.LoopMix.OUT_SEARCH_SEC
import com.music.bitchord.playback.smart.LoopMix.Pick
import com.music.bitchord.playback.smart.LoopMix.Plan
import com.music.bitchord.playback.smart.LoopMix.SETTLE_BARS
import com.music.bitchord.playback.smart.LoopMix.SILENT_RMS
import com.music.bitchord.playback.smart.LoopMix.Span
import com.music.bitchord.playback.smart.LoopMix.cueOf
import com.music.bitchord.playback.smart.LoopMix.firstAudibleTime
import com.music.bitchord.playback.smart.LoopMix.fxSlotForPair
import com.music.bitchord.playback.smart.LoopMix.isEdmPair
import com.music.bitchord.playback.smart.LoopMix.maskRejects
import com.music.bitchord.playback.smart.LoopMix.maskStats
import com.music.bitchord.playback.smart.LoopMix.qualifyCrown
import com.music.bitchord.playback.smart.LoopMix.trackedAnchors

/** Pure loop search and bridge placement behind the stable LoopMix facade. */
internal object LoopMixPlanner {
    private fun straightPick(start: Double, beatSec: Double, bpm: Double) = Pick(
        loopStart = start,
        loopEnd = start,
        bars = 4,
        beatSec = beatSec,
        bpm = bpm,
        vocal = 0.0,
        checkedBy = Check.MASK,
    )

    private fun camelotPair(left: String, right: String): String {
        val a = camelotCode(left) ?: return ""
        val b = camelotCode(right) ?: return ""
        return "$a→$b"
    }

    fun mixPlan(
        out: TrackAnalysis,
        outDuration: Double,
        inn: TrackAnalysis,
        inDuration: Double,
        outFirst: Double,
        shift: Boolean,
        sungOut: List<Span>?,
        sungIn: List<Span>?,
        outVideo: Boolean,
        inVideo: Boolean,
        why: StringBuilder?,
    ): Plan? {
        if (outVideo || inVideo) return null.also { why?.append("a music video") }
        val outGrid = constantGrid(out) ?: return null.also { why?.append("no grid on the outgoing song") }
        val inGrid = constantGrid(inn) ?: return null.also { why?.append("no grid on the incoming song") }
        val outCount = Rejections()
        val inCount = Rejections()

        // outgoing: the latest instrumental loop that ends before the song does,
        // or, when shifting, the next one after the start that was missed
        val outEnd = outDuration - 0.5
        val outroFloor = maxOf(
            outDuration * PhraseConfig.MIN_PLAY_FRACTION,
            out.outroStartTime.takeIf { it > 0.0 } ?: (outDuration - 32.0),
        )
        // vc73 A1: outro preference is for the latest-first plan search.
        // A shift walks forward to the next bar, so it keeps forward order
        // and must not be dragged to the latest outro loop.
        val effectiveOutroFrom = if (shift) Double.POSITIVE_INFINITY else outroFloor
        val outPick = firstClean(
            candidates = { bars ->
                val loops = loopsBetween(out.tailBeats, outGrid, outFirst, outEnd, bars)
                if (shift) preferNextPhrase(loops, out.phraseBoundaries, outGrid.beatSec) else loops.asReversed()
            },
            analysis = out,
            sung = sungOut,
            beatSec = outGrid.beatSec,
            count = outCount,
            outroFrom = effectiveOutroFrom,
        ) ?: return null.also { why?.append("outgoing: ")?.append(outCount) }

        // incoming: the earliest instrumental loop in its opening
        val inEnd = min(IN_SEARCH_SEC + LOOP_BARS * inGrid.barSec, inDuration - 20)
        val inFloor = maxOf(0.0, inn.audibleStartTime?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0, firstAudibleTime(inn))
        val inFirst = inFloor
        val inSearchFrom = if (inFirst > inEnd - 8.0) 0.0 else inFloor
        val inPick = firstClean(
            candidates = { bars -> loopsBetween(inn.headBeats, inGrid, inSearchFrom, inEnd, bars) },
            analysis = inn,
            sung = sungIn,
            beatSec = inGrid.beatSec,
            count = inCount,
        ) ?: return null.also { why?.append("incoming: ")?.append(inCount) }

        val outBeat = (outPick.end - outPick.start) / (outPick.bars * 4)
        val inBeat = (inPick.end - inPick.start) / (inPick.bars * 4)
        val outBpm = 60 / outBeat
        val inBpm = 60 / inBeat

        val fold = alignedFold(outBpm, inBpm)
        val target = inBpm / fold
        val rampBars = ceil(abs(target - outBpm) / MAX_BPM_PER_BAR).toInt().coerceIn(MIN_RAMP_BARS, MAX_RAMP_BARS)
        // the incoming loop lets go only at its own loop boundary: round the mix up to whole incoming loops
        val inLoopOutBars = inPick.bars / fold
        val totalBars = (ceil((ENTER_BARS + rampBars + EXIT_BARS) / inLoopOutBars) * inLoopOutBars).roundToInt()

        fun pick(clean: Clean, beatSec: Double, bpm: Double, anchors: List<Double>? = null) = Pick(
            loopStart = clean.start,
            loopEnd = clean.end,
            bars = clean.bars,
            beatSec = beatSec,
            bpm = bpm,
            vocal = clean.vocal,
            checkedBy = clean.checkedBy,
            anchorBeats = anchors,
        )
        // djmix24 Bug A4: deterministic EDM buildup. Both sides dance tempo,
        // post-fold delta within TEMPO_HARD_DELTA, clean instrumental loops
        // (firstClean already guarantees low vocal share), low dual-vocal
        // clash. Then flanger + tightening roll + HPF sweep. Non-EDM pairs
        // use deterministic pair-hash FX rotation (scalars only).
        val dualVocal = (outPick.vocal + inPick.vocal) > 0.06
        val edm = isEdmPair(outBpm, inBpm, fold) && !dualVocal
        val (fxFlanger, fxRoll, fxReverb, fxEcho) = if (edm) {
            // EDM: jet flanger + tightening roll + reverb release.
            Quad(true, true, true, false)
        } else {
            // Non-EDM rotation: FLANGER / CLEAN_SWAP / ECHO / REVERB, never
            // flanging over dual-vocal overlap.
            if (dualVocal) Quad(false, false, false, false)
            else when (fxSlotForPair(out.trackId, inn.trackId)) {
                0 -> Quad(true, false, false, false)
                1 -> Quad(false, false, false, false)
                2 -> Quad(false, false, false, true)
                else -> Quad(false, false, true, false)
            }
        }
        // 2B tracked-beat anchors: slice each side's own BeatSide.beats over
        // its chosen crown loop. Null (safe synthetic path) when the grid does not
        // cover the loop monotonically — see [trackedAnchors]. Crown-loop
        // geometry ([loopsBetween]/[firstClean]) is untouched.
        val outAnchors = trackedAnchors(out.tailBeats, outPick.start, outPick.end, outPick.bars)
        val inAnchors = trackedAnchors(inn.headBeats, inPick.start, inPick.end, inPick.bars)
        val candidate = Plan(
            out = pick(outPick, outBeat, outBpm, outAnchors),
            inn = pick(inPick, inBeat, inBpm, inAnchors),
            fold = fold,
            enterBars = ENTER_BARS,
            rampBars = rampBars,
            exitBars = totalBars - ENTER_BARS - rampBars,
            totalBars = totalBars,
            echoTail = fxEcho,
            flanger = fxFlanger,
            buildupRoll = fxRoll && outPick.bars > 0,
            reverbWash = fxReverb,
            hpSweepEndHz = if (fxRoll) BUILDUP_HP_END_HZ else 0f,
        )
        val qualification = qualifyCrown(candidate)
        if (!qualification.accepted) return null.also { why?.append("crown: ")?.append(qualification.refusal) }
        return candidate
    }

    private data class Quad(val a: Boolean, val b: Boolean, val c: Boolean, val d: Boolean)

    /** The fallback constant grid: a beat length, and a bar line to count from. */
    private class Grid(val beatSec: Double, val anchor: Double) {
        val barSec: Double get() = beatSec * 4
    }

    private fun constantGrid(analysis: TrackAnalysis): Grid? {
        val beat = analysis.beatInterval.takeIf { it.isFinite() && it > 0 }
            ?: analysis.bpm.takeIf { it.isFinite() && it > 0 }?.let { 60 / it }
            ?: return null
        val anchor = analysis.downbeats.firstOrNull { it.isFinite() } ?: analysis.firstBeat
        if (!anchor.isFinite()) return null
        return Grid(beat, anchor)
    }

    private open class Loop(val start: Double, val end: Double)

    private class Clean(start: Double, end: Double, val bars: Int, val vocal: Double, val checkedBy: Check) :
        Loop(start, end)

    /**
     * [bars]-bar loops lying in [from]..[to]. With the model's downbeats, each
     * loop runs from a detected beat 1 to the detected beat 1 [bars] bars on, so
     * its seam is the music's own bar line however the tempo wanders; one whose
     * end lands more than a quarter beat from [bars] bars on (a missed or
     * misplaced downbeat between) is skipped. Without them (or where the model
     * found too few), the constant grid.
     */
    private fun loopsBetween(side: BeatSide?, grid: Grid, from: Double, to: Double, bars: Int): List<Loop> {
        val loops = ArrayList<Loop>()
        if (side != null && side.bpm > 0 && side.downbeats.size >= bars + 1) {
            val beat = 60 / side.bpm
            val downs = side.downbeats
            for (start in downs) {
                if (start < from) continue
                val target = start + bars * 4 * beat
                val end = nearest(downs, target)
                if (abs(end - target) > beat / 4 || end > to) continue
                loops += Loop(start, end)
            }
            if (loops.isNotEmpty()) return loops
        }
        if (to < from) return loops
        val bar = grid.barSec
        val length = bars * bar
        var t = grid.anchor + ceil((from - grid.anchor) / bar) * bar
        while (t + length <= to) {
            loops += Loop(t, t + length)
            t += bar
        }
        return loops
    }

    /**
     * Puts a loop that starts on the next phrase boundary first, when one
     * falls within a bar of the earliest candidate — the same tolerance the
     * blend planner snaps a transition to a phrase with. Anything that phrase
     * loop fails (lyrics, mask, loudness) stays in the list behind it, so the
     * bar underneath is still tried.
     */
    private fun preferNextPhrase(loops: List<Loop>, phrases: List<Double>, beatSec: Double): List<Loop> {
        if (loops.isEmpty() || phrases.isEmpty()) return loops
        val tolerance = max(1.0, beatSec * 4)
        val earliest = loops.first().start
        val phrase = phrases
            .filter { it.isFinite() && it + 1e-9 >= earliest && it - earliest <= tolerance + 1e-9 }
            .minOrNull() ?: return loops
        val snapped = loops.filter { abs(it.start - phrase) <= beatSec / 4 }
        if (snapped.isEmpty()) return loops
        val rest = loops.filter { abs(it.start - phrase) > beatSec / 4 }
        return snapped + rest
    }

    private fun nearest(sorted: List<Double>, target: Double): Double {
        var low = 0
        var high = sorted.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] < target) low = mid + 1 else high = mid
        }
        val after = sorted[low]
        val before = sorted.getOrNull(low - 1) ?: return after
        return if (abs(before - target) <= abs(after - target)) before else after
    }

    /**
     * The first loop (in the order [candidates] gives) that is instrumental and
     * grooves, trying 4 bars, then 2, then 1. Null when there is none.
     *
     * Grooves: every bar at least [MIN_LOUDNESS] of the 60th-percentile loop in
     * the search. Instrumental: with synced lyrics, no sung line overlaps the
     * loop or the [LYRIC_GUARD_SEC] before it; without them, the vocal model's
     * windows cover the loop and the beat before it and find almost nothing
     * sung there.
     */
    /** What turned a side's candidate loops down, for the log and stats when none survives. */
    private class Rejections {
        var loops = 0
        var quiet = 0
        var lyrics = 0
        var unmasked = 0
        var sung = 0

        override fun toString() = if (loops == 0) {
            "no bar lines in the search"
        } else {
            "$loops loops: $quiet quiet, $lyrics sung (lyrics), $sung sung (mask), $unmasked unmeasured"
        }
    }

    /**
     * djmix24 loop-seam continuity (scalar RMS only, patent-safe): RMS just
     * before the loop end vs just after the loop start. Smaller is smoother.
     * NaN when windows don't reach (treated as neutral).
     */
    private fun seamDelta(analysis: TrackAnalysis, loop: Loop): Double {
        val pre = meanRms(analysis.levelWindows, loop.end - 0.25, loop.end)
        val post = meanRms(analysis.levelWindows, loop.start, loop.start + 0.25)
        if (pre.isNaN() || post.isNaN()) return 0.0
        val lo = minOf(pre, post).coerceAtLeast(1e-6)
        val hi = maxOf(pre, post)
        return if (lo <= 1e-6) 0.0 else kotlin.math.abs(kotlin.math.ln(hi / lo))
    }

    private fun firstClean(
        candidates: (Int) -> List<Loop>,
        analysis: TrackAnalysis,
        sung: List<Span>?,
        beatSec: Double,
        count: Rejections,
        outroFrom: Double = Double.POSITIVE_INFINITY,
    ): Clean? {
        for (bars in LOOP_LENGTHS) {
            val raw = candidates(bars)
            // djmix24: prefer seam-continuous loops first (no mid-transient
            // chop when an adjacent clean bar is smoother). Stable sort keeps
            // the original order (latest-out / earliest-in) within ties.
            // vc73 A1: when searching the outro (outgoing), never let tiny
            // seamDelta noise in an earlier verse/chorus outrank clean outro
            // loops inside outroFrom; bucket seamDelta coarsely (0.15) so
            // latest-first order survives within a bucket.
            val loops = if (raw.size > 1 && analysis.levelWindows.isNotEmpty()) {
                if (outroFrom.isFinite()) {
                    raw.sortedWith(
                        compareBy<Loop>(
                            { if (it.start >= outroFrom) 0 else 1 },
                            { kotlin.math.floor(seamDelta(analysis, it) / 0.15).toInt() },
                        ),
                    )
                } else {
                    raw.sortedWith(compareBy<Loop>({ seamDelta(analysis, it) }))
                }
            } else if (outroFrom.isFinite() && raw.size > 1) {
                raw.sortedBy { if (it.start >= outroFrom) 0 else 1 }
            } else raw
            if (loops.isEmpty()) continue
            count.loops += loops.size
            val louds = DoubleArray(loops.size) { quietestBar(analysis.levelWindows, loops[it], bars) }
            val known = louds.filter { !it.isNaN() }.sorted()
            val typical = if (known.isEmpty()) Double.NaN else known[min(known.size - 1, floor(known.size * 0.6).toInt())]
            for ((index, loop) in loops.withIndex()) {
                val loud = louds[index]
                val inOutro = loop.start >= outroFrom
                val minLoud = if (inOutro) 0.22 else MIN_LOUDNESS
                val minSilent = if (inOutro) 0.0056234 else SILENT_RMS
                val hasSilentDrop = inOutro && hasSilentChunk(analysis.levelWindows, loop.start, loop.end, minSilent)
                if (!loud.isNaN() && (loud < typical * minLoud || loud <= minSilent || hasSilentDrop)) {
                    count.quiet++
                    continue
                }
                if (sung != null) {
                    val from = loop.start - LYRIC_GUARD_SEC
                    val touching = sung.filter { it.start < loop.end && it.end > from }
                    if (touching.any { it.start < loop.end && it.certainUntil > from }) {
                        count.lyrics++
                        continue
                    }
                    val lyricOverlap = touching.isNotEmpty()
                    // Lyrics are a veto. When the vocal mask exists it also has
                    // to agree: a bar the lyrics never mention can still be sung.
                    if (analysis.vocalWindows.isNotEmpty()) {
                        val mask = maskStats(analysis.vocalWindows, loop.start - beatSec, loop.end)
                        if (mask == null) {
                            count.unmasked++
                            continue
                        }
                        if (maskRejects(mask, hasLyricOverlap = lyricOverlap)) {
                            count.sung++
                            continue
                        }
                        return Clean(loop.start, loop.end, bars, mask.share, Check.LYRICS_AND_MASK)
                    }
                    if (!lyricOverlap) return Clean(loop.start, loop.end, bars, 0.0, Check.LYRICS)
                    // Only the guessed tails of open lines reach it, and there is
                    // no mask to decide them.
                    val mask = maskStats(analysis.vocalWindows, loop.start - beatSec, loop.end)
                    if (mask == null) {
                        count.unmasked++
                        continue
                    }
                    if (maskRejects(mask, hasLyricOverlap = true)) {
                        count.sung++
                        continue
                    }
                    return Clean(loop.start, loop.end, bars, mask.share, Check.LYRICS_AND_MASK)
                }
                val mask = maskStats(analysis.vocalWindows, loop.start - beatSec, loop.end)
                if (mask == null) {
                    count.unmasked++
                    continue
                }
                if (maskRejects(mask, hasLyricOverlap = false)) {
                    count.sung++
                    continue
                }
                return Clean(loop.start, loop.end, bars, mask.share, Check.MASK)
            }
        }
        return null
    }

    /** True when any 0.5 s slice inside [from]..[to] drops to dead silence (e.g. a fade-out hitting 0). */
    private fun hasSilentChunk(windows: List<CurveWindow>, from: Double, to: Double, floorRms: Double): Boolean {
        if (windows.isEmpty() || to <= from) return false
        var t = from
        while (t + 0.25 < to) {
            val end = min(to, t + 0.5)
            val rms = meanRms(windows, t, end)
            if (!rms.isNaN() && rms <= floorRms) return true
            t += 0.5
        }
        return false
    }

    /** The RMS of the quietest of a loop's bars, from the level windows; NaN where they don't reach. */
    private fun quietestBar(windows: List<CurveWindow>, loop: Loop, bars: Int): Double {
        if (windows.isEmpty()) return Double.NaN
        var quietest = Double.POSITIVE_INFINITY
        val bar = (loop.end - loop.start) / bars
        for (b in 0 until bars) {
            val rms = meanRms(windows, loop.start + b * bar, loop.start + (b + 1) * bar)
            if (rms.isNaN()) return Double.NaN
            quietest = min(quietest, rms)
        }
        return quietest
    }

    fun meanRms(windows: List<CurveWindow>, from: Double, to: Double): Double {
        var sumSquares = 0.0
        var count = 0
        for (window in windows) {
            val a = max(0, ceil((from - window.startTime) * window.frameRate).toInt())
            val b = min(window.values.size, floor((to - window.startTime) * window.frameRate).toInt())
            for (i in a until b) {
                val rms = rmsFromLevel(window.values[i].toDouble())
                sumSquares += rms * rms
                count++
            }
        }
        return if (count == 0) Double.NaN else sqrt(sumSquares / count)
    }

    /**
     * A transition the loop mix declined, on the same clock: a drop, a beat
     * blend, or an echo. Null when neither song has a grid worth mixing on,
     * which is when the dual player crossfade is the floor.
     *
     * Stretch is capped at 6% when either song is vocal and 8% when both are
     * instrumental, after folding half and double time. The beat blend's ramp
     * arrives before the handover, so the incoming deck is already at 1.000x.
     * The drop and the echo play the incoming song at 1.000x the whole way.
     */
    fun bridge(
        out: TrackAnalysis,
        outDuration: Double,
        inn: TrackAnalysis,
        inDuration: Double,
        earliestOutSec: Double,
        sungOut: List<Span>? = null,
        sungIn: List<Span>? = null,
        outVideo: Boolean = false,
        inVideo: Boolean = false,
        playableOutDurationSec: Double = outDuration,
    ): Plan? {
        if (outVideo || inVideo) return null
        // djmix25: decline-to-mix skips drop/blend but still gets an echo-out
        // (bkmasterplayer tempo_jump_plan doctrine: large gap -> echo on the
        // bar, not a forced blend). Only the Rung 4 echo/cut handles it; if
        // that is null the caller falls to the phrase-length EQUAL_POWER.
        val declinedToMix = shouldDeclineToMix(out, inn)
        val effectiveOutDuration = if (playableOutDurationSec > 1.0) minOf(outDuration, playableOutDurationSec) else outDuration
        if (effectiveOutDuration < 8.0 || inDuration < 8.0) return null
        if (out.beatConfidence < 0.25 && inn.beatConfidence < 0.25) return null
        val outGrid = constantGrid(out)
        val inGrid = constantGrid(inn)
        if (outGrid == null && inGrid == null) return null
        val prelimRate = if (outGrid != null && inGrid != null && out.bpm > 0 && inn.bpm > 0) {
            val f = alignedFold(out.bpm, inn.bpm)
            (f * inGrid.beatSec) / outGrid.beatSec
        } else Double.NaN
        val steadyGridMatch = outGrid != null && inGrid != null &&
            out.downbeats.size >= 8 && inn.downbeats.size >= 8 &&
            prelimRate.isFinite() && abs(prelimRate - 1.0) <= PhraseConfig.TEMPO_HARD_DELTA
        val outOk = outGrid != null && out.bpm > 0 &&
            (out.beatConfidence >= 0.45 || (steadyGridMatch && out.beatConfidence >= MIN_DJ_CONFIDENCE))
        val inOk = inGrid != null && inn.bpm > 0 &&
            (inn.beatConfidence >= 0.45 || (steadyGridMatch && inn.beatConfidence >= MIN_DJ_CONFIDENCE))
        // djmix25 P1.9: regional keys (outgoing tail vs incoming head).
        val keys = camelotPair(exitKeyForMix(out), entryKeyForMix(inn))
        val steps = regionalCamelotSteps(out, inn)
        // djmix25 P1.6: acapella-over-instrumental rung. Same key + both
        // confidences >= 0.6 + incoming instrumental intro + outgoing vocal.
        // Routes through the normal drop/blend/echo picks with the carrier on.
        if (acapellaAllowed(out, inn)) {
            // Try the beatmatched picks first; fall to echo with the carrier.
            // (Reuses the out/in grids already computed above.)
        }
        if (declinedToMix) {
            // vc73 D1: keep FAMILIES precedence (drop/muffle/beatless-bridge
            // ahead of echoBase). Umxhq vocal-separation muffle is owned by
            // the Part 2 LoopMixSession flag; this bridge change deliberately
            // keeps the registry order so declined curves-less pairs stay Echo.
            // djmix25 R2: declined pairs take the Echo rung only (no drop/blend).
            // vc69 P0: the alternative families still fit some declined
            // pairs (muffle over the 8..10 percent band, beatless bridge on
            // beatless edges, drop-in on high-energy entries). Each requires
            // bass-curve evidence, so curve-less pairs keep the Echo rung
            // exactly as before.
            val decBeatSec = out.beatInterval.takeIf { it.isFinite() && it > 0.05 }
                ?: if (out.bpm.isFinite() && out.bpm > 1.0) 60.0 / out.bpm else 0.0
            val decAnchor = out.contentEndTime.takeIf { it.isFinite() && it > 0.0 && it <= outDuration } ?: outDuration
            // Family registry: fitting alternatives decide the base geometry
            // (drop vs echo) and the loop-plan flags; a new family only adds
            // a registry entry, never an edit here. Precedence (as before):
            // drop geometry first, then muffle, then bridge. A drop gate that
            // fits but whose dropPlan is null falls through to the next
            // fitting family instead of degrading to an unflagged echo.
            val fitting = if (decBeatSec > 0.0) TransitionAlternatives.FAMILIES
                .sortedWith(compareBy({ if (it.usesDropBase) 0 else 1 }, { it.priority }))
                .filter { it.fits(out, inn, decAnchor, decBeatSec) } else emptyList()
            var echoLazy: LoopMix.Plan? = null
            var echoBuilt = false
            fun echoBase(forAlternative: Boolean = false): LoopMix.Plan? {
                if (!echoBuilt) {
                    echoLazy = echoPlan(out, inn, effectiveOutDuration, earliestOutSec, outGrid, inGrid, keys, sungIn, sungOut,
                        musicalHandoff = !forAlternative)
                    echoBuilt = true
                }
                return echoLazy
            }
            for (family in fitting) {
                if (family.usesDropBase) {
                    if (outGrid == null) continue
                    val dropped = dropPlan(
                        out, inn, effectiveOutDuration, earliestOutSec, sungOut, sungIn,
                        outGrid, 60.0 / (inn.bpm.takeIf { it > 0 } ?: 120.0), inn.bpm, 60.0 / outGrid.beatSec,
                        out.bpm, alignedFold(out.bpm, inn.bpm), keys,
                    )
                    if (dropped == null) continue
                    val flagged = family.applyLoop(dropped)
                    return if (acapellaAllowed(out, inn)) flagged.copy(acapella = true) else flagged
                } else {
                    val e = echoBase(forAlternative = true) ?: return null
                    val flagged = family.applyLoop(e)
                    return if (acapellaAllowed(out, inn)) flagged.copy(acapella = true) else flagged
                }
            }
            val e = echoBase() ?: return null
            return if (e.alternativeKind != "natural-end" && acapellaAllowed(out, inn)) e.copy(acapella = true) else e
        }
        if (outOk && inOk) {
            val outBeat = outGrid.beatSec
            val inBeat = inGrid.beatSec
            val outBpm = 60.0 / outBeat
            val inBpm = 60.0 / inBeat
            val fold = alignedFold(outBpm, inBpm)
            val startRate = fold * inBeat / outBeat
            val bar = outBeat * 4.0
            val outTailVocal = vocalActivityBetween(out, max(0.0, effectiveOutDuration - 8 * bar), effectiveOutDuration)
            val inCue = cueOf(inn, sungIn)
            val inHeadVocal = vocalActivityBetween(inn, inCue, inCue + 8 * inBeat * 4)
            val windowClean = outTailVocal != null && inHeadVocal != null &&
                outTailVocal < VOCAL_ACTIVE_THRESHOLD && inHeadVocal < VOCAL_ACTIVE_THRESHOLD
            val vocal = out.vocalProbability >= 0.45 || inn.vocalProbability >= 0.45
            val cap = if (vocal && !windowClean) 0.06 else PhraseConfig.TEMPO_HARD_DELTA
            val bothSing = out.vocalProbability > 0.25 && inn.vocalProbability > 0.25 && !windowClean
            val outKeyConf = exitKeyConfidence(out)
            val inKeyConf = entryKeyConfidence(inn)
            val isKeyClash = steps != null && steps > 2 && outKeyConf >= 0.5 && inKeyConf >= 0.5
            // Key-clash pairs route to the filtered Beat blend (clash carrier,
            // 4–8 bars + bass swap), never to the hard-cut Drop which has no
            // clash filtering. Drop priority stands for non-clash pairs.
            if (!isKeyClash && abs(startRate - 1.0) <= 0.15 && out.beatConfidence >= 0.48 && inn.beatConfidence >= 0.48) {
                val dropped = dropPlan(
                    out, inn, effectiveOutDuration, earliestOutSec, sungOut, sungIn,
                    outGrid, inBeat, inBpm, outBeat, outBpm, fold, keys,
                )
                if (dropped != null) return if (acapellaAllowed(out, inn)) dropped.copy(acapella = true) else dropped
            }
            if (abs(startRate - 1.0) <= cap) {
                val blended = blendPlan(
                    out, outGrid, effectiveOutDuration, earliestOutSec, outBeat, inBeat, outBpm, inBpm, fold,
                    vocal, bothSing, windowClean, steps, keys, inn, sungIn, sungOut,
                )
                if (blended != null) return if (acapellaAllowed(out, inn)) blended.copy(acapella = true) else blended
            }
        }
        val finalEcho = echoPlan(out, inn, effectiveOutDuration, earliestOutSec, outGrid, inGrid, keys, sungIn, sungOut)
        return if (finalEcho != null && finalEcho.alternativeKind != "natural-end" && acapellaAllowed(out, inn)) finalEcho.copy(acapella = true) else finalEcho
    }

    private fun dropPlan(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outDuration: Double,
        earliestOutSec: Double,
        sungOut: List<Span>?,
        sungIn: List<Span>?,
        outGrid: Grid,
        inBeat: Double,
        inBpm: Double,
        outBeat: Double,
        outBpm: Double,
        fold: Double,
        keys: String,
    ): Plan? {
        val outEnd = outDuration - 0.5
        val outFirst = max(earliestOutSec, outDuration - OUT_SEARCH_SEC)
        val outroFloor = maxOf(outDuration * PhraseConfig.MIN_PLAY_FRACTION, out.outroStartTime.takeIf { it > 0.0 } ?: (outDuration - 32.0))
        val clean = firstClean(
            candidates = { bars ->
                if (bars < 2) emptyList()
                else loopsBetween(out.tailBeats, outGrid, outFirst, outEnd, bars).asReversed()
            },
            analysis = out,
            sung = sungOut,
            beatSec = outGrid.beatSec,
            count = Rejections(),
            outroFrom = outroFloor,
        ) ?: return null
        val bars = clean.bars.coerceIn(2, 4)
        val rampBars = (bars - 2).coerceAtLeast(1)
        val exitBars = (bars - 1 - rampBars).coerceAtLeast(0)
        val dropEdm = isEdmPair(outBpm, inBpm, fold) && clean.vocal < 0.06
        return Plan(
            out = Pick(clean.start, clean.end, clean.bars, outBeat, outBpm, clean.vocal, clean.checkedBy),
            inn = straightPick(cueOf(inn, sungIn), inBeat, inBpm),
            fold = fold,
            enterBars = 1,
            rampBars = rampBars,
            exitBars = exitBars,
            totalBars = 1 + rampBars + exitBars,
            outLoops = true,
            inLoops = false,
            nativeIncoming = true,
            rung = "Drop",
            keys = keys,
            flanger = dropEdm,
            buildupRoll = dropEdm,
            reverbWash = dropEdm,
            hpSweepEndHz = if (dropEdm) BUILDUP_HP_END_HZ else 0f,
        )
    }

    private fun blendPlan(
        out: TrackAnalysis,
        outGrid: Grid,
        outDuration: Double,
        earliestOutSec: Double,
        outBeat: Double,
        inBeat: Double,
        outBpm: Double,
        inBpm: Double,
        fold: Double,
        vocal: Boolean,
        bothSing: Boolean,
        windowClean: Boolean,
        steps: Int?,
        keys: String,
        inn: TrackAnalysis,
        sungIn: List<Span>?,
        sungOut: List<Span>? = null,
    ): Plan? {
        val bar = outBeat * 4
        val close = steps == null || steps <= 1
        val outKeyConf = exitKeyConfidence(out)
        val inKeyConf = entryKeyConfidence(inn)
        val keyClash = steps != null && steps > 2 && outKeyConf >= 0.5 && inKeyConf >= 0.5
        val room = (outDuration - earliestOutSec - 0.3).coerceAtLeast(0.0)
        var bars = when {
            keyClash && (bothSing || !windowClean) -> 4
            keyClash -> 8
            !bothSing && (windowClean || !vocal || close) -> 8
            else -> 4
        }
        while (bars > 4 && (bars * bar > 24.0 || bars * bar > room)) bars -= 2
        if (bars >= 4 && (bars * bar > 40.0 || room < 4 * bar)) {
            bars = if (room >= 2 * bar) 2 else return null
        }
        val needTempoRamp = abs((inBpm / fold) - outBpm) > 0.25
        val enter = if (bars <= 2 && needTempoRamp) 0 else 1
        val exit = 1
        val ramp = (bars - enter - exit).coerceAtLeast(if (bars <= 2 && !needTempoRamp) 0 else 1)
        val total = enter + ramp + exit
        val need = total * bar
        val effectiveEnd = out.contentEndTime.takeIf { it.isFinite() && it > earliestOutSec + need * 0.75 + 0.5 && it <= outDuration }
            ?: outDuration
        val rawStart = (effectiveEnd - need - 0.3).coerceAtLeast(earliestOutSec)
        val maxStart = min(outDuration - 0.5, outDuration - need * 0.75)
        val outStart = snapOutStart(out, outGrid, rawStart, earliestOutSec, maxStart, sungOut)
        if (outStart >= outDuration - 0.5 || outDuration - outStart < need * 0.75) return null
        // djmix24 Bug A4 (bridge path): deterministic EDM buildup on clean
        // instrumental blends; rotation otherwise. Never flange dual-vocal or key-clash.
        val blendEdm = !bothSing && !keyClash && windowClean && isEdmPair(outBpm, inBpm, fold)
        val blendFx = if (blendEdm) Quad(true, true, false, false)
        else if (bothSing || keyClash) Quad(false, false, false, false)
        else when (fxSlotForPair(keys, "$outBpm|$inBpm")) {
            0 -> Quad(true, false, false, false)
            1 -> Quad(false, false, false, false)
            2 -> Quad(false, false, false, false)
            else -> Quad(false, false, true, false)
        }
        return Plan(
            out = straightPick(outStart, outBeat, outBpm),
            inn = straightPick(cueOf(inn, sungIn), inBeat, inBpm),
            fold = fold,
            enterBars = enter,
            rampBars = ramp,
            exitBars = exit,
            totalBars = total,
            outLoops = false,
            inLoops = false,
            carve = bothSing || keyClash,
            clash = keyClash,
            rung = "Beat blend",
            keys = keys,
            flanger = blendFx.a,
            buildupRoll = blendFx.b,
            reverbWash = blendFx.c,
            hpSweepEndHz = if (blendFx.b) BUILDUP_HP_END_HZ else 0f,
            faderSwapBeats = 1.0,
        )
    }

    /**
     * djmix25 P1.6 gate (scalar mirror of TransitionMoves.shouldAcapella,
     * kept local so LoopMix stays acyclic): same Camelot, confident keys,
     * instrumental incoming intro, vocal outgoing.
     */
    private fun acapellaAllowed(out: TrackAnalysis, inn: TrackAnalysis): Boolean {
        val left = camelotCode(exitKeyForMix(out)) ?: return false
        val right = camelotCode(entryKeyForMix(inn)) ?: return false
        if (left != right) return false
        val conf = minOf(exitKeyConfidence(out), entryKeyConfidence(inn))
        if (conf < 0.6) return false
        if (inn.vocalProbability >= 0.45) return false
        if (out.vocalProbability < 0.45) return false
        return true
    }

    /**
     * djmix25 P1.5: mean instrumental share over [from]..[to]. Prefers the
     * explicit [TrackAnalysis.instrumentalWindows]; falls back to
     * 1 - vocal over [TrackAnalysis.vocalWindows]. Null when neither exists.
     */
    fun instrumentalShare(analysis: TrackAnalysis, from: Double, to: Double): Double? {
        val windows = analysis.instrumentalWindows
        if (windows.isNotEmpty()) {
            var sum = 0.0
            var count = 0
            for (w in windows) {
                for (i in w.values.indices) {
                    val time = w.startTime + i / w.frameRate
                    if (time < from || time > to) continue
                    sum += w.values[i].toDouble()
                    count++
                }
            }
            if (count > 0) return sum / count
        }
        val vocal = maskStats(analysis.vocalWindows, from, to)
        if (vocal != null) return 1.0 - vocal.mean
        return null
    }

    private fun echoPlan(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outDuration: Double,
        earliestOutSec: Double,
        outGrid: Grid?,
        inGrid: Grid?,
        keys: String,
        sungIn: List<Span>? = null,
        sungOut: List<Span>? = null,
        musicalHandoff: Boolean = true,
    ): Plan? {
        val outBeat = outGrid?.beatSec ?: inGrid?.beatSec ?: return null
        val inBeat = inGrid?.beatSec ?: outBeat
        val outBpm = 60.0 / outBeat
        val inBpm = 60.0 / inBeat
        val bar = outBeat * 4
        val effectiveEnd = out.contentEndTime.takeIf {
            it.isFinite() && it > earliestOutSec + bar + 0.5 && it <= outDuration
        } ?: outDuration
        // Registered muffle/beatless alternatives own their existing geometry
        // and DSP. They must not inherit the Echo handoff contract.
        if (!musicalHandoff) {
            val rawStart = (effectiveEnd - 2 * bar - 0.3).coerceAtLeast(earliestOutSec)
            val maxStart = outDuration - bar - 0.2
            val start = if (outGrid != null) snapOutStart(out, outGrid, rawStart, earliestOutSec, maxStart, sungOut)
                else out.downbeats.lastOrNull { it.isFinite() && it >= earliestOutSec && it <= maxStart && it <= rawStart + bar * 0.5 }
                    ?: rawStart
            if (start >= outDuration - 0.5 || start + bar > outDuration - 0.2) return null
            val cue = if (inGrid != null) cueOf(inn, sungIn)
                else inn.audibleStartTime?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            return Plan(straightPick(start, outBeat, outBpm), straightPick(cue, inBeat, inBpm),
                fold = 1.0, enterBars = 1, rampBars = 0, exitBars = 1, totalBars = 2,
                outLoops = false, inLoops = false, nativeIncoming = true, echoTail = true,
                rung = "Echo", keys = keys, targetBpmOverride = outBpm, incomingHoldSec = bar,
                exitTailSeconds = PhraseConfig.exitTailSeconds(outBeat), buildupRoll = false, reverbWash = true)
        }
        val selectedCue = if (inGrid != null) {
            cueOf(inn, sungIn)
        } else {
            inn.audibleStartTime?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        }
        val inCue = minOf(selectedCue,
            inn.pickupTime?.takeIf { it.isFinite() && it >= 0.0 } ?: selectedCue,
            inn.audibleStartTime?.takeIf { it.isFinite() && it >= 0.0 } ?: selectedCue)
        val fade = 0.75 * outBeat
        val minCut = maxOf(earliestOutSec + bar, effectiveEnd - 4 * bar)
        val targetCut = effectiveEnd - bar
        val candidates = (out.phraseBoundaries + out.mixOutCandidates.map { it.time } +
            listOf(out.mixOutTime) + out.tailBeats?.downbeats.orEmpty() + out.downbeats +
            sungOut.orEmpty().filter { it.end == it.certainUntil }.map { it.end + 0.08 + fade } +
            listOf(effectiveEnd))
            .filter { it.isFinite() && it in minCut..effectiveEnd }
            .distinct().sortedWith(compareBy<Double> { abs(it - targetCut) }.thenByDescending { it })
            .take(128)
        fun timing(cut: Double, natural: Boolean = false): MusicalHandoff? {
            val start = if (natural) cut - 2 * bar - 0.3 else cut - bar
            val endMix = cut - start
            return nativeTiming(start, maxOf(start, cut - fade - 1.75 * outBeat),
                if (natural) cut else cut - fade, cut, inCue, inCue, inCue,
                if (natural) endMix else bar,
                incomingOpenSec = 0.0,
                wetDrainSec = if (natural) 0.0 else bar + 0.05 + outBeat * 8 + PhraseConfig.exitTailSeconds(outBeat))
        }
        // Evaluate the actual fade and capture interval. Completed sung phrases
        // may feed the delay; this is straight playback, never a repeated loop.
        val measured = candidates.firstNotNullOfOrNull { cut ->
            timing(cut)?.takeIf {
                qualifyMusicalHandoff(it, out, inn, sungOut, sungIn).status == HandoffStatus.QUALIFIED &&
                    EchoAdmission.musicalRefusal(it, out, inn) == null
            }
        }
        // If no measured musical exit exists, play through the physical end.
        // A zero-width dry end cannot amputate a word or claim unknown is clean.
        val handoff = measured ?: outDuration.takeIf {
            it.isFinite() && it >= earliestOutSec + bar
        }?.let { timing(it, natural = true) } ?: return null
        val outStart = handoff.outDryEnd.sourceSec - handoff.outDryEnd.mixSec
        // Reserve the straight clock through effect retirement as well as the
        // dry endpoint and B's landing. Session stop/tail geometry is derived
        // from releaseAt, so a qualified Echo cannot retire beyond its clock.
        // Natural-end handoffs carry no wet retirement and preserve physical EOF.
        val requiredEnd = maxOf(handoff.outDryEnd.mixSec, handoff.inLanding.mixSec, handoff.wetRetireMixSec)
        val neededBars = ceil(requiredEnd / bar - 1e-9)
        val maxBars = (PhraseConfig.O_MAX_SEC / bar).toInt() - SETTLE_BARS
        if (!neededBars.isFinite() || maxBars < 2 || neededBars > maxBars) return null
        val totalBars = maxOf(2, neededBars.toInt())
        return Plan(
            out = straightPick(outStart, outBeat, outBpm),
            inn = straightPick(inCue, inBeat, inBpm),
            fold = 1.0,
            enterBars = 1,
            rampBars = 0,
            exitBars = totalBars - 1,
            totalBars = totalBars,
            outLoops = false,
            inLoops = false,
            nativeIncoming = true,
            echoTail = measured != null,
            rung = if (measured != null) "Echo" else "Natural handoff",
            alternativeKind = if (measured != null) "" else "natural-end",
            keys = keys,
            targetBpmOverride = outBpm,
            incomingHoldSec = handoff.inHoldMixSec,
            exitTailSeconds = PhraseConfig.exitTailSeconds(outBeat),
            buildupRoll = false,
            reverbWash = measured != null,
            handoff = handoff,
        )
    }

    private fun snapOutStart(
        out: TrackAnalysis,
        outGrid: Grid,
        targetStart: Double,
        minStart: Double,
        maxStart: Double,
        sungOut: List<Span>? = null,
    ): Double {
        if (maxStart < minStart) return targetStart.coerceAtLeast(minStart)
        val candidates = ArrayList<Double>()
        val tailDowns = out.tailBeats?.downbeats.orEmpty()
        for (d in tailDowns) {
            if (d.isFinite() && d in minStart..maxStart) candidates += d
        }
        if (candidates.isEmpty()) {
            for (d in out.downbeats) {
                if (d.isFinite() && d in minStart..maxStart) candidates += d
            }
        }
        if (candidates.isEmpty()) {
            val tailBeats = out.tailBeats?.beats.orEmpty().filter { it.isFinite() }
            if (tailBeats.size >= 4) {
                var i = 0
                while (i < tailBeats.size) {
                    val b = tailBeats[i]
                    if (b in minStart..maxStart) candidates += b
                    i += 4
                }
            }
        }
        if (candidates.isEmpty() && outGrid.barSec > 0.1) {
            val bar = outGrid.barSec
            var t = outGrid.anchor + ceil((minStart - outGrid.anchor) / bar) * bar
            while (t <= maxStart) {
                if (t >= minStart) candidates += t
                t += bar
            }
        }
        if (candidates.isEmpty()) return targetStart.coerceIn(minStart, maxStart)
        fun isSung(c: Double): Boolean {
            if (sungOut == null) return false
            val from = c - LYRIC_GUARD_SEC
            return sungOut.any { it.start < c && it.certainUntil > from }
        }
        val barTol = outGrid.barSec * 0.25
        val preferred = candidates.filter { it <= targetStart + barTol }
        var snapped = (preferred.minByOrNull { abs(it - targetStart) }
            ?: candidates.minByOrNull { abs(it - targetStart) })!!
        // Never snap forward past the raw point: if the nearest
        // candidate is after the raw point, fall back to the last
        // candidate at or before the raw point (snap BACK only).
        if (snapped > targetStart + 1e-9) {
            val back = candidates.filter { it <= targetStart + 1e-9 }
            if (back.isNotEmpty()) {
                snapped = back.maxOrNull() ?: snapped
            }
        }
        // Refuse a sung snap entirely (keep raw) — never skip a verse/lyric
        // by scavenging an earlier bar.
        if (isSung(snapped)) return targetStart.coerceIn(minStart, maxStart)
        return snapped
    }
}
