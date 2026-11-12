package com.music.bitchord.playback.smart

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

    return collapse(slots, end)
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
