package com.music.bitchord.playback.smart

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One synced lyric line. Text is what repetition is judged on; times are
 * seconds on this track's own timeline.
 */
data class LyricCue(val startSec: Double, val endSec: Double, val text: String)

enum class SectionKind {
    INTRO,
    VERSE,
    PRE_CHORUS,
    CHORUS,
    BRIDGE,
    INST,
    OUTRO,
    // djmix21: appended so existing ordinals (and the slot coding below)
    // never shift. djmix24: now actively painted by labelSections (EDM
    // buildup -> bass-drop; see paintBuildDrop below).
    BUILD,
    DROP,
}

data class Section(val kind: SectionKind, val start: Double, val end: Double)

/**
 * Labels one track from its own lyrics, energy, and self-similarity.
 *
 * Nothing here looks at the other song. Chorus is a block of lyric lines
 * that repeats, or an energy shape that repeats inside this track. The first
 * sung block is the verse, the bars just before a chorus with rising energy
 * are the pre-chorus, a one-off block after the second chorus is the bridge,
 * a lyric gap with energy is an instrumental, and the thin edges are the
 * intro and the outro.
 */
fun labelSections(analysis: TrackAnalysis, lyrics: List<LyricCue> = emptyList()): List<Section> {
    val duration = analysis.duration.takeIf { it > 1.0 }
        ?: analysis.contentEndTime.takeIf { it > 1.0 }
        ?: return emptyList()
    val end = analysis.contentEndTime.takeIf { it in 1.0..duration + 0.01 } ?: duration
    val cues = lyrics.mapNotNull { cue ->
        val text = normalizeLyric(cue.text)
        if (text.isEmpty() || cue.startSec >= end) null
        else LyricCue(cue.startSec.coerceAtLeast(0.0), cue.endSec.coerceIn(cue.startSec + 0.3, end), text)
    }.sortedBy { it.startSec }

    val chorus = ArrayList<Pair<Double, Double>>()
    val lineCounts = cues.groupingBy { it.text }.eachCount()
    for (cue in cues) {
        if ((lineCounts[cue.text] ?: 0) >= 2) chorus += cue.startSec to cue.endSec
    }
    val blocks = lyricBlocks(cues)
    val blockCounts = blocks.groupingBy { it.signature }.eachCount()
    for (block in blocks) {
        if (block.signature.length >= 8 && (blockCounts[block.signature] ?: 0) >= 2) {
            chorus += block.start to block.end
        }
    }
    markRepeatedEnergy(analysis, end, chorus)

    val slots = IntArray((end / SLOT).toInt() + 1)
    fun paint(kind: SectionKind, from: Double, until: Double) {
        if (until - from < 0.4) return
        val code = kind.ordinal + 1
        val rank = PRIORITY[code]
        var i = (from / SLOT).toInt().coerceAtLeast(0)
        val last = ((until / SLOT) - 1e-6).toInt().coerceAtMost(slots.lastIndex)
        while (i <= last) {
            if (rank >= PRIORITY[slots[i]]) slots[i] = code
            i++
        }
    }

    val firstLyric = cues.firstOrNull()?.startSec
    val introEnd = when {
        firstLyric != null -> firstLyric
        analysis.introEndTime in 1.0..(end - 1) -> analysis.introEndTime
        else -> energyRise(analysis, end)
    }.coerceIn(0.0, end.coerceAtMost(45.0))
    if (introEnd >= 1.0) paint(SectionKind.INTRO, 0.0, introEnd)

    val lastChorus = mergeRanges(chorus).maxOfOrNull { it.second }
    val outroStart = when {
        lastChorus != null && lastChorus < end - 2.0 -> lastChorus
        analysis.outroStartTime in 1.0..end && cues.isEmpty() -> analysis.outroStartTime
        else -> energyFall(analysis, end)
    }
    if (outroStart < end - 1.5) paint(SectionKind.OUTRO, outroStart, end)

    for ((from, until) in mergeRanges(chorus)) paint(SectionKind.CHORUS, from, until)

    if (cues.isNotEmpty()) {
        val choruses = mergeRanges(chorus)
        for (cue in cues) {
            if (choruses.none { cue.startSec < it.second && cue.endSec > it.first }) {
                paint(SectionKind.VERSE, cue.startSec, cue.endSec)
            }
        }
        if (choruses.size >= 2) {
            val after = choruses[1].second
            val nextChorus = choruses.getOrNull(2)?.first ?: outroStart
            val bridge = cues.filter { it.startSec >= after - 0.2 && it.endSec <= nextChorus + 0.2 }
            if (bridge.isNotEmpty()) {
                val signature = bridge.joinToString(" ") { it.text }
                val repeats = blocks.count { it.signature == signature }
                if (repeats <= 1) paint(SectionKind.BRIDGE, bridge.first().startSec, bridge.last().endSec)
            }
        }
        for ((from, until) in choruses) {
            val preFrom = from - 6.0
            if (preFrom > introEnd && energyRising(analysis, preFrom, from)) {
                paint(SectionKind.PRE_CHORUS, preFrom, from)
            }
        }
        var cursor = introEnd
        val gaps = (cues.map { it.startSec to it.endSec } + (end to end)).sortedBy { it.first }
        for ((from, _) in gaps) {
            if (from - cursor > 6.0 && meanEnergy(analysis, cursor, from) > 0.55 * medianEnergy(analysis)) {
                paint(SectionKind.INST, cursor, from)
            }
            cursor = maxOf(cursor, cues.firstOrNull { it.startSec >= from }?.endSec ?: from)
        }
    }

    paintBuildDrop(analysis, end, slots)
    return collapse(slots, end)
}

/**
 * djmix24 BUILD / DROP painting (single-track scalars only, patent-safe).
 *
 * BUILD: 4-16 bar span with positive energy/flux slope, reduced low-end
 * weight or rising centroid/high-band (proxied by energy curvature + flux
 * acceleration), low vocal share (< 0.28), immediately preceding a
 * high-energy section jump.
 * DROP: phrase-aligned (>= 4 bars, priority 7) high-energy arrival
 * (delta-energy >= +0.16 or top-quartile energy + strong bass/kick)
 * following a BUILD, INTRO, or breakdown (BREAK/INST).
 *
 * Flat pop ballads (no rising flux, no bass surge) never paint — the
 * 0.28 vocal gate + slope gates reject them.
 */
private fun paintBuildDrop(analysis: TrackAnalysis, end: Double, slots: IntArray) {
    if (analysis.energyCurve.size < 8) return
    val curve = analysis.energyCurve
    val sortedEnergies = curve.map { it.energy }.filter { it.isFinite() }.sorted()
    if (sortedEnergies.isEmpty()) return
    val q75 = sortedEnergies[minOf(sortedEnergies.lastIndex, (sortedEnergies.size * 0.75).toInt())]
    val median = sortedEnergies[sortedEnergies.size / 2].coerceAtLeast(1e-6)
    // Bar grid: derive barSec from BPM (scalar); fall back to 2s.
    val beatSec = if (analysis.beatInterval > 0.05) analysis.beatInterval else if (analysis.bpm > 1.0) 60.0 / analysis.bpm else 0.5
    val barSec = (beatSec * 4.0).coerceAtLeast(0.5)
    fun barEnergy(bar: Int): Double {
        val from = bar * barSec
        val until = (bar + 1) * barSec
        return meanEnergy(curve, from, until)
    }
    fun barVocal(bar: Int): Double {
        val from = bar * barSec
        val until = (bar + 1) * barSec
        return vocalShare(analysis, from, until)
    }
    val totalBars = (end / barSec).toInt().coerceAtMost(512)
    if (totalBars < 8) return
    val barE = DoubleArray(totalBars) { barEnergy(it) }
    // Flux proxy: absolute energy difference per bar (rising edge = positive slope).
    // Energy slope over a window: (late - early) / early.
    fun slopePct(fromBar: Int, untilBar: Int): Double {
        val n = (untilBar - fromBar).coerceAtLeast(1)
        val mid = fromBar + n / 2
        var early = 0.0; var ec = 0
        for (b in fromBar until mid) { early += barE[b]; ec++ }
        var late = 0.0; var lc = 0
        for (b in mid until untilBar) { late += barE[b]; lc++ }
        if (ec == 0 || lc == 0) return 0.0
        early /= ec; late /= lc
        if (early <= 1e-6) return if (late > 1e-6) 0.5 else 0.0
        return (late - early) / early
    }
    // Find high-energy arrivals (DROP candidates): bar with energy >= q75
    // and delta vs previous 4-bar mean >= +0.16 (normalized) or top-quartile.
    // Then look back 4-16 bars for a low-vocal, rising BUILD before it.
    fun paint(kind: SectionKind, fromBar: Int, untilBar: Int) {
        if (untilBar - fromBar < 4) return
        val from = fromBar * barSec
        val until = untilBar * barSec
        // Reuse priority paint: delegate to slots by code.
        val code = kind.ordinal + 1
        val rank = PRIORITY[code]
        var i = (from / SLOT).toInt().coerceAtLeast(0)
        val last = ((until / SLOT) - 1e-6).toInt().coerceAtMost(slots.lastIndex)
        while (i <= last) {
            if (rank >= PRIORITY[slots[i]]) slots[i] = code
            i++
        }
    }
    for (dropBar in 4 until totalBars - 4) {
        // Check DROP arrival at dropBar (4-bar phrase).
        var prevMean = 0.0; var pc = 0
        for (b in maxOf(0, dropBar - 4) until dropBar) { prevMean += barE[b]; pc++ }
        if (pc == 0) continue
        prevMean /= pc
        var curMean = 0.0
        for (b in dropBar until dropBar + 4) { curMean += barE[b] }
        curMean /= 4.0
        val deltaNorm = if (prevMean > 1e-6) (curMean - prevMean) / maxOf(prevMean, median) else 0.0
        val isDropEnergy = (deltaNorm >= 0.16 && curMean >= q75 * 0.9) || (curMean >= q75 * 1.15 && deltaNorm >= 0.05)
        // Strong bass/kick presence proxy: bass/lowEnergy curve or level at drop > median*1.2.
        // Fall back to energy curvature when no bass curve exists.
        val bassStrong = run {
            if (analysis.lowEnergyCurve.isNotEmpty()) {
                val b = meanEnergy(analysis.lowEnergyCurve, dropBar * barSec, (dropBar + 4) * barSec)
                val pm = meanEnergy(analysis.lowEnergyCurve, (dropBar - 4) * barSec, dropBar * barSec)
                b > pm * 1.10 || b > median * 1.1
            } else {
                // No bass curve: require clear energy jump + low vocal arrival.
                deltaNorm >= 0.25 || curMean >= q75 * 1.2
            }
        }
        if (!isDropEnergy || !bassStrong) continue
        // Arrival vocal must be low at the start (instrumental drop) or
        // musical (chorus/drop overlap allowed). Reject sung arrivals
        // unless bass is very strong (EDM vocal drop still counts).
        val arrivalVocal = (0 until 4).map { barVocal(dropBar + it) }.average()
        if (arrivalVocal > 0.55 && !bassStrong) continue
        // Look back for BUILD: 4-16 bars before dropBar, rising slope,
        // vocal < 0.28, preceding the jump.
        var bestBuild: Pair<Int, Int>? = null
        for (len in 4..16) {
            val from = maxOf(0, dropBar - len)
            if (dropBar - from < 4) continue
            // BUILD must end at/just before the drop (within 2 bars).
            val buildFrom = from
            val buildUntil = dropBar
            if (buildUntil - buildFrom < 4 || buildUntil - buildFrom > 16) continue
            val slope = slopePct(buildFrom, buildUntil)
            // Rising: need positive energy slope (flux proxy). Bell-ballads have ~0.
            if (slope < 0.12) continue
            // djmix24 gradual (not a step): a cold 0.2->1.0 edge has one
            // jump; a true EDM buildup rises bar-by-bar. Require >=2
            // increments of >=5% so DjMixTest step intros (0.2 flat then
            // 1.0 flat) never paint BUILD.
            var rises = 0
            for (b in buildFrom until buildUntil - 1) {
                if (barE[b] > 1e-6 && barE[b + 1] > barE[b] * 1.05) rises++
            }
            if (rises < 2) continue
            // Low vocal share in BUILD.
            var vSum = 0.0; var vc = 0
            for (b in buildFrom until buildUntil) { vSum += barVocal(b); vc++ }
            if (vc == 0) continue
            val vMean = vSum / vc
            if (vMean >= 0.28) continue
            // Reduced low-end or rising centroid proxy: BUILD energy below
            // DROP energy (build is quieter) — always true for a real build.
            var bMean = 0.0
            for (b in buildFrom until buildUntil) bMean += barE[b]
            bMean /= (buildUntil - buildFrom)
            if (bMean >= curMean * 0.98) continue
            // Pick the longest qualifying build.
            if (bestBuild == null || (buildUntil - buildFrom) > (bestBuild.second - bestBuild.first)) {
                bestBuild = buildFrom to buildUntil
            }
        }
        if (bestBuild != null) {
            paint(SectionKind.BUILD, bestBuild.first, bestBuild.second)
            paint(SectionKind.DROP, dropBar, dropBar + 4)
        }
    }
}

fun sectionAt(sections: List<Section>, time: Double): Section? =
    sections.filter { time >= it.start && time < it.end }.maxByOrNull { it.start }

/**
 * Mean vocal activity over [from, until).
 *
 * The mask aligned to the energy curve wins when it covers the window. The
 * open-unmix windows are the same measurement where that mask was never
 * stored, which is what a loop already trusts. 0 when neither covers it.
 */
fun vocalShare(analysis: TrackAnalysis, from: Double, until: Double): Double {
    if (until <= from) return 0.0
    val curve = analysis.energyCurve
    val mask = analysis.vocalActivityMask
    if (curve.isNotEmpty() && mask.size == curve.size) {
        var sum = 0.0
        var count = 0
        for (i in curve.indices) {
            val t = curve[i].time
            if (t < from || t >= until) continue
            sum += mask[i]
            count++
        }
        if (count > 0) return sum / count
    }
    var sum = 0.0
    var count = 0
    for (window in analysis.vocalWindows) {
        if (window.frameRate <= 0.0 || window.endTime <= from || window.startTime >= until) continue
        val first = ((from - window.startTime) * window.frameRate).toInt().coerceAtLeast(0)
        val last = ((until - window.startTime) * window.frameRate).toInt().coerceAtMost(window.values.size)
        for (i in first until last) {
            sum += window.values[i]
            count++
        }
    }
    return if (count == 0) 0.0 else sum / count
}

fun normalizeLyric(text: String): String =
    text.lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private const val SLOT = 0.5

/** Higher wins when two labels cover the same moment. */
private val PRIORITY = intArrayOf(
    0,
    2, // INTRO
    5, // VERSE
    6, // PRE_CHORUS
    7, // CHORUS
    6, // BRIDGE, same rank as pre-chorus; painted later so it can sit in a gap
    3, // INST
    4, // OUTRO
    6, // BUILD (djmix21, never painted)
    7, // DROP (djmix21, never painted)
)

private data class LyricBlock(val start: Double, val end: Double, val signature: String)

private fun lyricBlocks(cues: List<LyricCue>): List<LyricBlock> {
    if (cues.isEmpty()) return emptyList()
    val out = ArrayList<LyricBlock>()
    var start = cues.first().startSec
    var end = cues.first().endSec
    val words = ArrayList<String>()
    words += cues.first().text
    for (i in 1 until cues.size) {
        val cue = cues[i]
        if (cue.startSec - end > 6.0) {
            out += LyricBlock(start, end, words.joinToString(" "))
            words.clear()
            start = cue.startSec
        }
        words += cue.text
        end = cue.endSec
    }
    out += LyricBlock(start, end, words.joinToString(" "))
    return out
}

private fun mergeRanges(ranges: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedBy { it.first }
    val out = ArrayList<Pair<Double, Double>>()
    var from = sorted.first().first
    var until = sorted.first().second
    for (i in 1 until sorted.size) {
        val (s, e) = sorted[i]
        if (s <= until + 1.0) until = maxOf(until, e) else {
            out += from to until
            from = s
            until = e
        }
    }
    out += from to until
    return out
}

/**
 * Intra-track only: an 8 second energy shape that comes back later, and is
 * not just a flat line, marks both copies as chorus when one of them already
 * is, or both when nothing else has claimed a chorus.
 */
private fun markRepeatedEnergy(analysis: TrackAnalysis, end: Double, chorus: MutableList<Pair<Double, Double>>) {
    val curve = analysis.energyCurve
    if (curve.size < 16) return
    val width = 8.0
    val step = 2.0
    var best = 0.0
    var bestPair: Pair<Pair<Double, Double>, Pair<Double, Double>>? = null
    var t = 0.0
    while (t + width < end) {
        var u = t + 12.0
        while (u + width < end) {
            val left = windowEnergy(curve, t, t + width)
            val right = windowEnergy(curve, u, u + width)
            val score = cosine(left, right)
            if (score > 0.90 && score > best) {
                best = score
                bestPair = (t to t + width) to (u to u + width)
            }
            u += step
        }
        t += step
    }
    val pair = bestPair ?: return
    val known = chorus.isNotEmpty()
    val hitsKnown = chorus.any { overlaps(it, pair.first) || overlaps(it, pair.second) }
    if (hitsKnown || !known) {
        chorus += pair.first
        chorus += pair.second
    }
}

private fun overlaps(a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean =
    a.first < b.second && b.first < a.second

private fun windowEnergy(curve: List<EnergySample>, from: Double, until: Double): DoubleArray {
    val out = DoubleArray(8)
    val step = (until - from) / out.size
    for (i in out.indices) {
        val a = from + i * step
        out[i] = meanEnergy(curve, a, a + step)
    }
    return out
}

private fun cosine(left: DoubleArray, right: DoubleArray): Double {
    if (left.size != right.size || left.isEmpty()) return 0.0
    if (spread(left) < 1e-3 || spread(right) < 1e-3) return 0.0
    var dot = 0.0
    var ll = 0.0
    var rr = 0.0
    for (i in left.indices) {
        dot += left[i] * right[i]
        ll += left[i] * left[i]
        rr += right[i] * right[i]
    }
    if (ll <= 1e-9 || rr <= 1e-9) return 0.0
    return dot / sqrt(ll * rr)
}

private fun spread(values: DoubleArray): Double {
    var mean = 0.0
    for (v in values) mean += v
    mean /= values.size
    var acc = 0.0
    for (v in values) {
        val d = v - mean
        acc += d * d
    }
    return sqrt(acc / values.size)
}

private fun meanEnergy(curve: List<EnergySample>, from: Double, until: Double): Double {
    var sum = 0.0
    var count = 0
    for (sample in curve) {
        if (sample.time < from || sample.time >= until) continue
        if (!sample.energy.isFinite()) continue
        sum += sample.energy
        count++
    }
    return if (count == 0) 0.0 else sum / count
}

private fun meanEnergy(analysis: TrackAnalysis, from: Double, until: Double): Double =
    meanEnergy(analysis.energyCurve, from, until)

private fun medianEnergy(analysis: TrackAnalysis): Double {
    val values = analysis.energyCurve.map { it.energy }.filter { it.isFinite() && it >= 0 }.sorted()
    if (values.isEmpty()) return 1.0
    return values[values.size / 2].coerceAtLeast(1e-6)
}

private fun energyRise(analysis: TrackAnalysis, end: Double): Double {
    val median = medianEnergy(analysis)
    val curve = analysis.energyCurve
    if (curve.size < 4) return 0.0
    for (sample in curve) {
        if (sample.time > 45.0) break
        if (sample.energy > median * 0.7 && sample.time > 1.0) return sample.time
    }
    return 0.0
}

private fun energyFall(analysis: TrackAnalysis, end: Double): Double {
    val median = medianEnergy(analysis)
    val curve = analysis.energyCurve
    var fallen: Double? = null
    for (sample in curve) {
        if (sample.time < end - 40.0) continue
        if (sample.energy < median * 0.55) {
            if (fallen == null) fallen = sample.time
        } else if (sample.time < end - 4.0) {
            fallen = null
        }
    }
    return fallen ?: end
}

/** Late energy over early energy across [from, until). 1 when the window is flat or empty. */
fun energyRise(analysis: TrackAnalysis, from: Double, until: Double): Double {
    if (until - from < 0.5) return 1.0
    val mid = (from + until) / 2.0
    val early = meanEnergy(analysis, from, mid)
    val late = meanEnergy(analysis, mid, until)
    if (early <= 1e-6) return if (late > 1e-6) 1.5 else 1.0
    return late / early
}

private fun energyRising(analysis: TrackAnalysis, from: Double, until: Double): Boolean {
    val early = meanEnergy(analysis, from, from + (until - from) / 2)
    val late = meanEnergy(analysis, from + (until - from) / 2, until)
    return early > 0 && late > early * 1.08
}

private fun collapse(slots: IntArray, end: Double): List<Section> {
    val out = ArrayList<Section>()
    var i = 0
    while (i < slots.size) {
        val kind = slots[i]
        if (kind == 0) {
            i++
            continue
        }
        var j = i + 1
        while (j < slots.size && slots[j] == kind) j++
        val sectionEnd = minOf(end, j * SLOT)
        val sectionStart = i * SLOT
        if (sectionEnd - sectionStart >= 0.8) {
            out += Section(SectionKind.entries[kind - 1], sectionStart, sectionEnd)
        }
        i = j
    }
    return out
}
