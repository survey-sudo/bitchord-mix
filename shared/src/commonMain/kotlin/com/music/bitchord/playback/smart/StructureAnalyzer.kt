package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bar features to a [SongMap]. Frame extraction is pure Kotlin so the JVM
 * tests run it; the phone may swap in the native front end and still call
 * [analyseBars]. A timeout or a thrown error is the caller's signal to keep
 * the djmix6 plan.
 */
object StructureAnalyzer {
    private data class ReconciledGrid(val times: DoubleArray, val coverage: BooleanArray, val stable: Boolean)

    private fun reconcileBarGrid(measured: DoubleArray, barSec: Double, duration: Double): ReconciledGrid {
        val source = measured.filter { it.isFinite() && it >= 0.0 }.distinct().sorted()
        if (source.isEmpty()) {
            val count = max(1, kotlin.math.ceil(duration / barSec.coerceAtLeast(0.01)).toInt())
            return ReconciledGrid(DoubleArray(count) { it * barSec }, BooleanArray(count), false)
        }
        val step = if (source.size >= 2) (source[1] - source[0]).coerceAtLeast(0.01) else barSec
        val out = ArrayList<Double>()
        val covered = ArrayList<Boolean>()
        var first = source.first()
        while (first - step >= 0.0) {
            first -= step
        }
        var t = first
        while (t < source.first() - step * 0.2) {
            out += t
            covered += false
            t += step
        }
        for (value in source) {
            out += value
            covered += true
        }
        t = source.last() + step
        while (t < duration) {
            out += t
            covered += false
            t += step
        }
        // Keep a phase-preserving local interpolation for measured non-uniform grids.
        val stable = source.zipWithNext().all { (a, b) -> abs((b - a) - step) <= step * 0.08 }
        return ReconciledGrid(out.toDoubleArray(), covered.toBooleanArray(), stable)
    }

    class Timeout(message: String) : RuntimeException(message)

    class Bar(
        val mfcc: FloatArray,
        val chroma: FloatArray,
        val rmsDb: Float,
        val low: Float,
        val flux: Float,
        val bass: Float,
    )

    fun analyseBars(
        trackKey: String,
        durationSec: Double,
        bpm: Double,
        beatsPerBar: Int,
        downbeats: DoubleArray,
        bars: List<Bar>,
        mask: FloatArray?,
        lyricPrior: FloatArray?,
        gridConfidence: Float,
        tempoStable: Boolean,
        activation: Float,
        speechLike: Boolean,
        partial: Boolean,
        startedMs: Long = 0L,
        now: () -> Long = { 0L },
        gridMeasured: Boolean = false,
        gridCoverage: BooleanArray = BooleanArray(0),
    ): SongMap {
        checkTime(startedMs, now)
        val n = bars.size
        if (n == 0) return empty(trackKey, durationSec, bpm, beatsPerBar, downbeats, partial)
        val sung = sungBars(n, downbeats, bpm, beatsPerBar, mask, durationSec)
        val novelty = novelty(bars, sung, startedMs, now)
        val bounds = PhraseGrid.boundaries(novelty)
        val anchor = PhraseGrid.anchor(novelty)
        val anchors = PhraseGrid.reanchors(novelty, bounds, anchor)
        val groups = group(bars, bounds)
        val sections = label(bars, bounds, groups, sung, lyricPrior, downbeats, bpm)
        val firstVocal = sung.indexOfFirst { it }.let { if (it < 0) null else it }
        val lastVocal = sung.indexOfLast { it }.let { if (it < 0) null else it }
        val contentEnd = contentEnd(bars, downbeats, durationSec, bpm, beatsPerBar)
        val ending = endingKind(sections, bars, contentEnd, downbeats, bpm, beatsPerBar)
        val start = startKind(sections, firstVocal)
        val conf = if (sections.isEmpty()) 0f else {
            val w = sections.sumOf { (it.endBar - it.startBar).coerceAtLeast(1).toDouble() }.toFloat()
            sections.sumOf { (it.conf * (it.endBar - it.startBar)).toDouble() }.toFloat() / w.coerceAtLeast(1f)
        }
        val coarse = beatsPerBar != 4 || gridConfidence < PhraseConfig.GRID_COARSE || conf < 0.4f
        val wall = if (startedMs > 0L) (now() - startedMs).toInt().coerceAtLeast(0) else 0
        return SongMap(
            trackKey = trackKey,
            durationSec = durationSec,
            bpm = bpm,
            beatsPerBar = beatsPerBar,
            downbeats = downbeats,
            gridConfidence = gridConfidence,
            tempoStable = tempoStable,
            phraseAnchor = anchor,
            phraseLen = 8,
            anchors = anchors,
            sections = sections,
            sungBars = sung,
            firstVocalBar = firstVocal,
            lastVocalBar = lastVocal,
            pickupBeats = if (firstVocal == 1) 1f else 0f,
            startKind = start,
            endingKind = ending,
            contentEndSec = contentEnd,
            labelConfidence = conf,
            structMs = wall,
            structWallMs = wall,
            partial = partial,
            coarse = coarse,
            activation = activation,
            speechLike = speechLike,
            chorusCount = sections.count { it.fn == SectionFn.CHORUS },
            vocalCoverage = BooleanArray(n) { mask != null && mask.isNotEmpty() },
            gridMeasured = gridMeasured,
            gridCoverage = gridCoverage,
            provenance = if (gridMeasured) "pcm:model-grid" else "pcm:unverified-grid",
        )
    }

    /**
     * PCM at any rate. Resampled to 22.05 kHz inside. [mask] is per-second
     * vocal probability, or null when the mask has not run. Lyrics never set
     * a vocal flag on their own when a mask is present.
     */
    fun fromPcm(
        trackKey: String,
        pcm: FloatArray,
        sampleRate: Int,
        bpm: Double,
        mask: FloatArray? = null,
        lyricPrior: FloatArray? = null,
        partial: Boolean = false,
        startedMs: Long = 0L,
        now: () -> Long = { 0L },
        fullTrackDurationSec: Double = 0.0,
        downbeats: DoubleArray? = null,
        beatsPerBar: Int = 4,
        gridConfidence: Float? = null,
        tempoStable: Boolean? = null,
        gridMeasured: Boolean = false,
    ): SongMap {
        val mono = if (sampleRate == RATE) pcm else resample(pcm, sampleRate, RATE)
        val frames = frames(mono)
        checkTime(startedMs, now)
        val pcmDurationSec = mono.size / RATE.toDouble()
        val effectiveDuration = if (fullTrackDurationSec > 0.0) maxOf(pcmDurationSec, fullTrackDurationSec) else pcmDurationSec
        val effectivePartial = partial || (fullTrackDurationSec > pcmDurationSec + 1.0)
        val beatSec = 60.0 / bpm.coerceAtLeast(1.0)
        val meter = beatsPerBar.coerceAtLeast(1)
        val barSec = beatSec * meter
        val grid = reconcileBarGrid(downbeats ?: DoubleArray(0), barSec, pcmDurationSec)
        val starts = grid.times.filter { it < pcmDurationSec }.toDoubleArray().ifEmpty { doubleArrayOf(0.0) }
        val bars = beatSync(frames, starts, barSec)
        val measured = !downbeats.isNullOrEmpty()
        val fluxPeak = frames.maxOfOrNull { it.flux } ?: 0f
        val activation = if (fluxPeak <= 1e-6f) 0f else (frames.map { it.flux }.average() / fluxPeak).toFloat()
        val map = analyseBars(
            trackKey, effectiveDuration, bpm, meter, starts, bars,
            mask, lyricPrior, gridConfidence = (gridConfidence ?: if (measured) 0.5f else 0.3f)
                .coerceIn(0f, if (measured) 1f else 0.35f),
            tempoStable = tempoStable ?: (measured && grid.stable), activation = activation, speechLike = false, partial = effectivePartial,
            startedMs = startedMs, now = now,
            gridMeasured = measured, gridCoverage = BooleanArray(starts.size) { measured && grid.coverage.getOrElse(it) { false } },
        )
        return if (fullTrackDurationSec > pcmDurationSec + 1.0) {
            map.copy(contentEndSec = effectiveDuration)
        } else {
            map
        }
    }

    private fun empty(
        key: String, duration: Double, bpm: Double, beats: Int, downs: DoubleArray, partial: Boolean,
    ) = SongMap(
        trackKey = key, durationSec = duration, bpm = bpm, beatsPerBar = beats, downbeats = downs,
        gridConfidence = 0f, tempoStable = true, phraseAnchor = 0, phraseLen = 8,
        sections = emptyList(), sungBars = BooleanArray(0), firstVocalBar = null, lastVocalBar = null,
        pickupBeats = 0f, startKind = StartKind.UNKNOWN, endingKind = EndingKind.UNKNOWN,
        contentEndSec = duration, labelConfidence = 0f, structMs = 0, partial = partial, coarse = true,
    )

    private fun checkTime(startedMs: Long, now: () -> Long) {
        if (startedMs <= 0L) return
        val t = now()
        if (t > 0L && t - startedMs > PhraseConfig.STRUCT_TIMEOUT_MS) {
            throw Timeout("structure exceeded ${PhraseConfig.STRUCT_TIMEOUT_MS} ms")
        }
    }

    private fun sungBars(
        n: Int, downbeats: DoubleArray, bpm: Double, beatsPerBar: Int, mask: FloatArray?, duration: Double,
    ): BooleanArray {
        val out = BooleanArray(n)
        if (mask == null || mask.isEmpty()) return out
        val barSec = if (bpm > 1) beatsPerBar * 60.0 / bpm else duration / n
        for (b in 0 until n) {
            val t0 = if (b < downbeats.size) downbeats[b] else b * barSec
            val t1 = if (b + 1 < downbeats.size) downbeats[b + 1] else t0 + barSec
            var sum = 0.0
            var peak = 0f
            var count = 0
            val a = (t0 / duration.coerceAtLeast(0.001) * mask.size).toInt().coerceIn(0, mask.size)
            val z = (t1 / duration.coerceAtLeast(0.001) * mask.size).toInt().coerceIn(a, mask.size)
            for (i in a until z) {
                sum += mask[i]
                if (mask[i] > peak) peak = mask[i]
                count++
            }
            val mean = if (count == 0) 0f else (sum / count).toFloat()
            out[b] = peak > 0.22f && mean > 0.14f
        }
        return out
    }

    private fun novelty(bars: List<Bar>, sung: BooleanArray, startedMs: Long, now: () -> Long): DoubleArray {
        val n = bars.size
        val s = similarity(bars)
        checkTime(startedMs, now)
        enhance(s, n)
        val narrow = foote(s, n, PhraseConfig.NOVELTY_KERNEL_HALF_BARS)
        val wide = foote(s, n, PhraseConfig.NOVELTY_KERNEL_HALF_BARS_WIDE)
        val energy = energyNovelty(bars)
        val vocal = vocalNovelty(sung)
        val bass = bassNovelty(bars)
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val ssm = 0.5 * (narrow[i] + wide[i])
            out[i] = PhraseConfig.NOV_SSM * ssm +
                PhraseConfig.NOV_ENERGY * energy[i] +
                PhraseConfig.NOV_VOCAL * vocal[i] +
                PhraseConfig.NOV_BASS * bass[i]
        }
        var peak = 1e-9
        for (v in out) if (v > peak) peak = v
        for (i in out.indices) out[i] = (out[i] / peak).coerceIn(0.0, 1.0)
        return out
    }

    private fun similarity(bars: List<Bar>): DoubleArray {
        val n = bars.size
        val s = DoubleArray(n * n)
        for (i in 0 until n) {
            for (j in i until n) {
                val chroma = chromaSim(bars[i].chroma, bars[j].chroma)
                val timbre = cosine(bars[i].mfcc, bars[j].mfcc).coerceIn(0.0, 1.0)
                val energy = (1.0 - abs(bars[i].rmsDb - bars[j].rmsDb) / 24.0).coerceIn(0.0, 1.0)
                val v = PhraseConfig.SSM_CHROMA * chroma + PhraseConfig.SSM_TIMBRE * timbre + PhraseConfig.SSM_ENERGY * energy
                s[i * n + j] = v
                s[j * n + i] = v
            }
        }
        return s
    }

    /** Max over 12 rotations, with a penalty when the rotation is not zero. */
    private fun chromaSim(a: FloatArray, b: FloatArray): Double {
        var best = 0.0
        for (k in 0 until 12) {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in 0 until 12) {
                val av = a.getOrElse(i) { 0f }.toDouble()
                val bv = b.getOrElse((i + k) % 12) { 0f }.toDouble()
                dot += av * bv
                na += av * av
                nb += bv * bv
            }
            val denom = sqrt(na * nb)
            val cos = if (denom < 1e-9) 0.0 else (dot / denom).coerceIn(0.0, 1.0)
            val penalised = if (k == 0) cos else cos - PhraseConfig.CHROMA_ROTATION_PENALTY
            if (penalised > best) best = penalised
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = sqrt(na * nb)
        return if (d < 1e-9) 0.0 else dot / d
    }

    /** Average each cell with the next few diagonal steps. In place, via a copy. */
    private fun enhance(s: DoubleArray, n: Int) {
        val copy = s.copyOf()
        val k = PhraseConfig.DIAGONAL_PATH_BARS
        for (i in 0 until n) {
            for (j in 0 until n) {
                var sum = 0.0
                var c = 0
                for (d in 0 until k) {
                    val x = i + d
                    val y = j + d
                    if (x >= n || y >= n) break
                    sum += copy[x * n + y]
                    c++
                }
                if (c > 0) s[i * n + j] = sum / c
            }
        }
    }

    private fun foote(s: DoubleArray, n: Int, half: Int): DoubleArray {
        val out = DoubleArray(n)
        val sigma = 2.0
        for (b in 0 until n) {
            var acc = 0.0
            var w = 0.0
            for (i in -half..half) {
                for (j in -half..half) {
                    if (i == 0 || j == 0) continue
                    val x = b + i
                    val y = b + j
                    if (x !in 0 until n || y !in 0 until n) continue
                    val sign = if ((i > 0) == (j > 0)) 1.0 else -1.0
                    val g = exp(-(i * i + j * j) / (2.0 * sigma * sigma))
                    acc += sign * g * s[x * n + y]
                    w += g
                }
            }
            out[b] = if (w > 0) (acc / w).coerceAtLeast(0.0) else 0.0
        }
        norm01(out)
        return out
    }

    private fun energyNovelty(bars: List<Bar>): DoubleArray {
        val n = bars.size
        val out = DoubleArray(n)
        for (b in 0 until n) {
            val before = meanRms(bars, b - 4, b)
            val after = meanRms(bars, b, b + 4)
            out[b] = (abs(after - before) / 6.0).coerceIn(0.0, 1.0)
        }
        return out
    }

    private fun meanRms(bars: List<Bar>, from: Int, to: Int): Double {
        var sum = 0.0
        var c = 0
        for (i in from until to) {
            if (i !in bars.indices) continue
            sum += bars[i].rmsDb
            c++
        }
        return if (c == 0) bars.getOrNull(from.coerceIn(0, bars.lastIndex))?.rmsDb?.toDouble() ?: -40.0 else sum / c
    }

    private fun vocalNovelty(sung: BooleanArray): DoubleArray {
        val filtered = BooleanArray(sung.size)
        for (i in sung.indices) {
            var votes = 0
            for (d in -1..1) if (sung.getOrElse(i + d) { false }) votes++
            filtered[i] = votes >= 2
        }
        val out = DoubleArray(sung.size)
        for (i in 1 until sung.size) if (filtered[i] != filtered[i - 1]) out[i] = 1.0
        return out
    }

    private fun bassNovelty(bars: List<Bar>): DoubleArray {
        val out = DoubleArray(bars.size)
        for (i in 1 until bars.size) out[i] = abs(bars[i].bass - bars[i - 1].bass).toDouble().coerceIn(0.0, 1.0)
        return out
    }

    private fun norm01(v: DoubleArray) {
        var peak = 1e-9
        for (x in v) if (x > peak) peak = x
        for (i in v.indices) v[i] = (v[i] / peak).coerceIn(0.0, 1.0)
    }

    private fun group(bars: List<Bar>, bounds: IntArray): IntArray {
        val segs = bounds.size - 1
        val ids = IntArray(segs) { it }
        var next = 0
        val rep = IntArray(segs) { -1 }
        for (s in 0 until segs) {
            var joined = -1
            for (g in 0 until s) {
                if (segmentSim(bars, bounds, s, g) >= PhraseConfig.GROUP_SIM) {
                    joined = ids[g]
                    break
                }
            }
            ids[s] = if (joined >= 0) joined else next++
            rep[s] = ids[s]
        }
        return ids
    }

    private fun segmentSim(bars: List<Bar>, bounds: IntArray, a: Int, b: Int): Double {
        val a0 = bounds[a]
        val a1 = bounds[a + 1]
        val b0 = bounds[b]
        val b1 = bounds[b + 1]
        val len = min(a1 - a0, b1 - b0)
        if (len <= 0) return 0.0
        var best = 0.0
        for (off in intArrayOf(0, 4)) {
            if (off >= len) continue
            var sum = 0.0
            var c = 0
            for (k in 0 until len - off) {
                val left = bars[a0 + k]
                val right = bars[b0 + k + off]
                sum += PhraseConfig.SSM_CHROMA * chromaSim(left.chroma, right.chroma) +
                    PhraseConfig.SSM_TIMBRE * cosine(left.mfcc, right.mfcc).coerceIn(0.0, 1.0)
                c++
            }
            if (c > 0 && sum / c > best) best = sum / c
        }
        return best
    }

    private fun label(
        bars: List<Bar>,
        bounds: IntArray,
        groups: IntArray,
        sung: BooleanArray,
        lyricPrior: FloatArray?,
        downbeats: DoubleArray,
        bpm: Double,
    ): List<PhraseSection> {
        val segs = bounds.size - 1
        val vocal = FloatArray(segs)
        val energyRel = FloatArray(segs)
        val slope = FloatArray(segs)
        val bass = FloatArray(segs)
        val lufs = FloatArray(segs)
        val onsets = FloatArray(segs)
        val median = median(bars.map { it.rmsDb })
        for (s in 0 until segs) {
            val a = bounds[s]
            val b = bounds[s + 1]
            var sungN = 0
            var rms = 0.0
            var low = 0.0
            var flux = 0.0
            for (i in a until b) {
                if (sung.getOrElse(i) { false }) sungN++
                rms += bars[i].rmsDb
                low += bars[i].bass
                flux += bars[i].flux
            }
            val len = (b - a).coerceAtLeast(1)
            vocal[s] = sungN.toFloat() / len
            val mean = (rms / len).toFloat()
            energyRel[s] = ((mean - median) / 6f).coerceIn(-1f, 1f)
            slope[s] = slopeOf(bars, a, b)
            bass[s] = (low / len).toFloat().coerceIn(0f, 1f)
            lufs[s] = mean
            onsets[s] = (flux / len / 0.5).toFloat().coerceIn(0f, 12f)
        }
        val counts = HashMap<Int, Int>()
        for (g in groups) counts[g] = (counts[g] ?: 0) + 1
        val fn = Array(segs) { SectionFn.INSTRUMENTAL }
        val conf = FloatArray(segs) { 0.4f }
        val firstVocal = sung.indexOfFirst { it }.let { if (it < 0) segs else it }
        val lastVocal = sung.indexOfLast { it }
        for (s in 0 until segs) {
            val meanR = meanRms(bars, bounds[s], bounds[s + 1])
            if (meanR < -45.0) {
                fn[s] = SectionFn.SILENCE
                conf[s] = 0.9f
            }
        }
        // Fade before intro/outro so a fading tail is not labelled only as outro.
        val fadeAt = fadeStart(bars, median)
        for (s in 0 until segs) {
            if (fadeAt >= 0 && bounds[s] >= fadeAt && slope[s] < -0.6f) {
                fn[s] = SectionFn.FADE_OUT
                conf[s] = 0.8f
            }
        }
        for (s in 0 until segs) {
            if (fn[s] == SectionFn.FADE_OUT || fn[s] == SectionFn.SILENCE) continue
            val end = bounds[s + 1]
            if (lastVocal >= 0 && bounds[s] > lastVocal) fn[s] = SectionFn.OUTRO
            else if (end <= firstVocal && fn[s] != SectionFn.BUILD) fn[s] = SectionFn.INTRO
        }
        // Chorus: repeated vocal groups, highest energy score, count >= 2.
        var bestGroup = -1
        var bestScore = 0f
        val groupEnergy = HashMap<Int, Float>()
        for (s in 0 until segs) {
            if (vocal[s] < 0.4f) continue
            val g = groups[s]
            val prev = groupEnergy[g] ?: -99f
            if (energyRel[s] > prev) groupEnergy[g] = energyRel[s]
        }
        val ranked = groupEnergy.entries.sortedByDescending { it.value }
        for ((index, entry) in ranked.withIndex()) {
            val count = counts[entry.key] ?: 0
            if (count < 2) continue
            val prior = lyricPriorOf(lyricPrior, bounds, groups, entry.key)
            val score = 0.35f * (1f - index / ranked.size.coerceAtLeast(1).toFloat()) +
                0.25f * min(1f, count / 3f) + 0.25f * prior + 0.15f
            if (score > bestScore) {
                bestScore = score
                bestGroup = entry.key
            }
        }
        if (bestGroup >= 0) {
            for (s in 0 until segs) if (groups[s] == bestGroup && vocal[s] >= 0.4f) {
                fn[s] = SectionFn.CHORUS
                conf[s] = bestScore.coerceIn(0.4f, 0.95f)
            }
        }
        val chorusEnergy = sectionsEnergy(fn, energyRel, SectionFn.CHORUS)
        for (s in 0 until segs) {
            if (fn[s] == SectionFn.CHORUS || vocal[s] < 0.4f) continue
            val count = counts[groups[s]] ?: 1
            if (count >= 2 && energyRel[s] < chorusEnergy) {
                fn[s] = SectionFn.VERSE
                conf[s] = 0.6f
            }
        }
        for (s in 0 until segs) {
            if (fn[s] != SectionFn.CHORUS) continue
            if (s == 0) continue
            val prev = s - 1
            val len = bounds[prev + 1] - bounds[prev]
            if (len in 2..8 && groups[prev] != groups[s] && fn[prev] != SectionFn.CHORUS &&
                (slope[prev] > 0.15f || (energyRel[prev] > -0.2f && energyRel[prev] < chorusEnergy))
            ) {
                fn[prev] = SectionFn.PRE_CHORUS
                conf[prev] = 0.55f
            }
            if (s + 1 < segs && fn[s + 1] != SectionFn.CHORUS && vocal[s + 1] < 0.3f && energyRel[s + 1] >= 0.3f) {
                fn[s + 1] = if (bass[s + 1] >= 0.35f && onsets[s + 1] >= 4f) SectionFn.DROP else SectionFn.POST_CHORUS
                conf[s + 1] = 0.55f
            }
        }
        // djmix24 DROP painting: phrase-aligned (>=4 bars) high-energy
        // arrival with delta-energy >= +0.16 (energyRel jump) or
        // top-quartile energy + strong bass/kick, following BUILD/INTRO/
        // BREAK. Overwrites POST_CHORUS where the arrival is a true drop.
        val sortedRel = energyRel.toList().sorted()
        val q75Rel = sortedRel[min(sortedRel.lastIndex, (sortedRel.size * 0.75).toInt())]
        for (s in 0 until segs) {
            val len = bounds[s + 1] - bounds[s]
            if (len < 4) continue
            val prevRel = if (s > 0) energyRel[s - 1] else energyRel[s]
            val delta = energyRel[s] - prevRel
            val strongBass = bass[s] >= 0.45f && onsets[s] >= 4f
            val dropEnergy = (delta >= 0.16f && energyRel[s] >= q75Rel * 0.9f) ||
                (energyRel[s] >= q75Rel * 1.15f && delta >= 0.08f && strongBass)
            val afterBuildOrBreak = s > 0 && (fn[s - 1] == SectionFn.BUILD || fn[s - 1] == SectionFn.INTRO || fn[s - 1] == SectionFn.BREAK || fn[s - 1] == SectionFn.BUILD)
            if (dropEnergy && strongBass && (afterBuildOrBreak || fn[s] == SectionFn.POST_CHORUS || fn[s] == SectionFn.INSTRUMENTAL)) {
                if (fn[s] != SectionFn.CHORUS && fn[s] != SectionFn.FADE_OUT && fn[s] != SectionFn.SILENCE) {
                    fn[s] = SectionFn.DROP
                    conf[s] = 0.62f
                }
            }
            // BUILD: 4-16 bars, positive slope, low vocal, preceding jump.
            if (len in 4..16 && slope[s] >= 0.30f && vocal[s] < 0.28f) {
                val nextRel = if (s + 1 < segs) energyRel[s + 1] else energyRel[s]
                if ((nextRel >= 0.4f || (s + 1 < segs && fn[s + 1] == SectionFn.DROP)) && fn[s] != SectionFn.CHORUS) {
                    fn[s] = SectionFn.BUILD
                    conf[s] = 0.6f
                }
            }
            if (energyRel[s] <= -0.4f && vocal[s] < 0.3f && s in 1 until segs - 1 && fn[s] == SectionFn.INSTRUMENTAL) {
                fn[s] = SectionFn.BREAK
            }
        }
        val chorusSeen = fn.count { it == SectionFn.CHORUS }
        if (chorusSeen >= 2) {
            for (s in 0 until segs) {
                if ((counts[groups[s]] ?: 1) == 1 && vocal[s] >= 0.3f && fn[s] != SectionFn.CHORUS &&
                    fn.copyOfRange(0, s).count { it == SectionFn.CHORUS } >= 2
                ) {
                    fn[s] = SectionFn.BRIDGE
                    conf[s] = 0.45f
                }
            }
        }
        for (s in 0 until segs) {
            if (fn[s] == SectionFn.INSTRUMENTAL && vocal[s] >= 0.3f) {
                fn[s] = SectionFn.VERSE
                conf[s] = 0.3f
            }
        }
        return List(segs) { s ->
            val level = energyLevel(lufs[s], onsets[s], bass[s], energyRel[s])
            val e01 = ((level - 1) / 9f).coerceIn(0f, 1f)
            PhraseSection(
                startBar = bounds[s],
                endBar = bounds[s + 1],
                fn = fn[s],
                role = roleOf(fn[s], energyRel[s], vocal[s]),
                conf = conf[s],
                group = groups[s],
                repeatIndex = repeatIndex(groups, s),
                energy = e01,
                energyLevel = level,
                energyRel = energyRel[s],
                energySlope = slope[s],
                vocal = vocal[s],
                bass = bass[s],
                loopable = vocal[s] < 0.2f && bounds[s + 1] - bounds[s] >= 4,
                lufs = lufs[s],
                onsetRate = onsets[s],
            )
        }
    }

    private fun repeatIndex(groups: IntArray, s: Int): Int {
        var n = 0
        for (i in 0 until s) if (groups[i] == groups[s]) n++
        return n
    }

    private fun lyricPriorOf(prior: FloatArray?, bounds: IntArray, groups: IntArray, group: Int): Float {
        if (prior == null) return 0f
        var hits = 0
        for (s in groups.indices) {
            if (groups[s] != group) continue
            var sum = 0f
            for (b in bounds[s] until bounds[s + 1]) sum += prior.getOrElse(b) { 0f }
            if (sum >= 0.4f) hits++
        }
        return if (hits >= 2) 0.4f else 0f
    }

    private fun sectionsEnergy(fn: Array<SectionFn>, rel: FloatArray, want: SectionFn): Float {
        var best = -1f
        for (i in fn.indices) if (fn[i] == want && rel[i] > best) best = rel[i]
        return best
    }

    private fun slopeOf(bars: List<Bar>, from: Int, to: Int): Float {
        val n = to - from
        if (n < 2) return 0f
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            val y = bars[from + i].rmsDb.toDouble()
            sx += i
            sy += y
            sxx += i * i
            sxy += i * y
        }
        val d = n * sxx - sx * sx
        if (abs(d) < 1e-6) return 0f
        return ((n * sxy - sx * sy) / d).toFloat()
    }

    private fun fadeStart(bars: List<Bar>, median: Float): Int {
        if (bars.size < 8) return -1
        val last = bars.last().rmsDb
        if (last > median - 12f) return -1
        for (b in bars.size - 4 downTo 0) {
            val slope = slopeOf(bars, b, min(bars.size, b + 8))
            if (slope < -0.6f && bars[b].rmsDb < median - 3f) return b
        }
        return -1
    }

    private fun energyLevel(lufs: Float, onsetRate: Float, bass: Float, highRel: Float): Int {
        fun clamp01(v: Float) = v.coerceIn(0f, 1f)
        val e = 0.45f * clamp01((lufs + 20f) / 12f) +
            0.30f * clamp01(onsetRate / 3f) +
            0.15f * (if (bass >= 0.25f) 1f else 0f) +
            0.10f * clamp01(highRel / 10f + 0.5f)
        return (1 + (9f * e).roundToInt()).coerceIn(1, 10)
    }

    private fun roleOf(fn: SectionFn, rel: Float, vocal: Float): SectionRole = when (fn) {
        SectionFn.SILENCE -> SectionRole.NONE
        SectionFn.INTRO -> if (rel < -0.5f) SectionRole.AMBIENT else SectionRole.GROOVE
        SectionFn.VERSE, SectionFn.BRIDGE -> SectionRole.NARRATIVE
        SectionFn.PRE_CHORUS, SectionFn.BUILD -> SectionRole.TENSION
        SectionFn.CHORUS, SectionFn.DROP -> SectionRole.RELEASE
        SectionFn.POST_CHORUS -> if (vocal >= 0.3f) SectionRole.RELEASE else SectionRole.GROOVE
        SectionFn.BREAK, SectionFn.FADE_OUT -> SectionRole.AMBIENT
        SectionFn.OUTRO, SectionFn.INSTRUMENTAL -> if (vocal >= 0.3f) SectionRole.NARRATIVE else SectionRole.GROOVE
    }

    private fun startKind(sections: List<PhraseSection>, firstVocal: Int?): StartKind {
        if (sections.isEmpty()) return StartKind.UNKNOWN
        if (firstVocal != null && firstVocal <= 0) return StartKind.COLD_VOCAL
        if (firstVocal == 1) return StartKind.VOCAL_PICKUP
        val firstVocalSection = sections.firstOrNull { it.vocal >= 0.4f }
        if (firstVocalSection?.fn == SectionFn.CHORUS) return StartKind.CHORUS_FIRST
        if (sections.first().fn == SectionFn.BUILD) return StartKind.INTRO_BUILD
        if (sections.first().energyRel < -0.5f) return StartKind.AMBIENT
        if (sections.first().fn == SectionFn.INTRO && sections.first().vocal >= 0.7f && sections.first().bass < 0.15f) {
            return StartKind.ACAPELLA
        }
        return StartKind.INTRO_INSTRUMENTAL
    }

    private fun endingKind(
        sections: List<PhraseSection>, bars: List<Bar>, contentEnd: Double, downbeats: DoubleArray, bpm: Double, beats: Int,
    ): EndingKind {
        if (sections.any { it.fn == SectionFn.FADE_OUT }) return EndingKind.FADE_OUT
        val last = sections.lastOrNull() ?: return EndingKind.UNKNOWN
        val bar = if (bpm > 1) beats * 60.0 / bpm else 2.0
        val lastDown = downbeats.lastOrNull() ?: 0.0
        if (last.energyRel >= 0f && contentEnd - lastDown < bar) return EndingKind.HARD_END
        if (last.fn == SectionFn.OUTRO && last.endBar - last.startBar >= 4 && last.loopable) return EndingKind.LOOPABLE_OUTRO
        if (last.vocal >= 0.3f) return EndingKind.OUTRO_VOCAL
        if (last.endBar - last.startBar >= 4) return EndingKind.OUTRO_INSTRUMENTAL
        if (bars.size >= 2 && bars.takeLast(2).all { it.rmsDb < -45f }) return EndingKind.SILENCE_TAIL
        return EndingKind.UNKNOWN
    }

    private fun contentEnd(bars: List<Bar>, downbeats: DoubleArray, duration: Double, bpm: Double, beats: Int): Double {
        val bar = if (bpm > 1) beats * 60.0 / bpm else duration / bars.size.coerceAtLeast(1)
        for (i in bars.indices.reversed()) {
            if (bars[i].rmsDb > -50f) {
                return if (i < downbeats.size) min(duration, downbeats[i] + bar) else duration
            }
        }
        return duration
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return -20f
        val s = values.sorted()
        return s[s.size / 2]
    }

    // ---- PCM front end (tests and the fallback when native features are absent) ----

    private const val RATE = 22050
    private const val FRAME = 2048
    private const val HOP = 512

    private class Frame(val mfcc: FloatArray, val chroma: FloatArray, val rmsDb: Float, val low: Float, val flux: Float)

    private fun frames(mono: FloatArray): List<Frame> {
        if (mono.size < FRAME) return listOf(Frame(FloatArray(13), FloatArray(12), -80f, 0f, 0f))
        val fft = Fft(FRAME)
        val window = FloatArray(FRAME) { (0.5f - 0.5f * cos(2.0 * PI * it / (FRAME - 1))).toFloat() }
        val mel = melBank()
        val out = ArrayList<Frame>(mono.size / HOP)
        var prev = FloatArray(40)
        var havePrev = false
        var at = 0
        while (at + FRAME <= mono.size) {
            val real = FloatArray(FRAME) { mono[at + it] * window[it] }
            val imag = FloatArray(FRAME)
            fft.forward(real, imag)
            val mag = FloatArray(FRAME / 2)
            var energy = 0.0
            var low = 0.0
            for (k in mag.indices) {
                val p = real[k] * real[k] + imag[k] * imag[k]
                mag[k] = p.toFloat()
                energy += p
                val hz = k * RATE.toDouble() / FRAME
                if (hz in 30.0..150.0) low += p
            }
            val rms = if (energy <= 1e-12) -80f else (10.0 * ln(energy / FRAME) / ln(10.0)).toFloat()
            val melE = FloatArray(40)
            for (b in 0 until 40) {
                var s = 0.0
                val row = mel[b]
                for (k in row.indices) s += row[k] * mag[k]
                melE[b] = ln(1e-6 + s).toFloat()
            }
            var flux = 0f
            if (havePrev) {
                for (b in 0 until 40) {
                    val d = melE[b] - prev[b]
                    if (d > 0) flux += d
                }
            }
            prev = melE
            havePrev = true
            val chroma = if (rms < -50f) FloatArray(12) else chromaOf(mag)
            out.add(Frame(mfccOf(melE), chroma, rms, if (energy > 0) (low / energy).toFloat() else 0f, flux))
            at += HOP
        }
        return out
    }

    private fun beatSync(frames: List<Frame>, downbeats: DoubleArray, barSec: Double): List<Bar> {
        val bars = ArrayList<Bar>(downbeats.size)
        for (b in downbeats.indices) {
            val t0 = downbeats[b]
            val t1 = if (b + 1 < downbeats.size) downbeats[b + 1] else t0 +
                if (b > 0) t0 - downbeats[b - 1] else barSec
            val from = (t0 * RATE / HOP).toInt().coerceIn(0, frames.size)
            val to = (t1 * RATE / HOP).toInt().coerceIn(minOf(from + 1, frames.size), frames.size)
            val slice = frames.subList(from, to.coerceAtMost(frames.size))
            if (slice.isEmpty()) {
                bars.add(Bar(FloatArray(13), FloatArray(12), -80f, 0f, 0f, 0f))
                continue
            }
            val mfcc = FloatArray(13)
            val chroma = FloatArray(12)
            var rms = 0.0
            var low = 0.0
            var flux = 0.0
            for (f in slice) {
                for (i in 0 until 13) mfcc[i] += f.mfcc.getOrElse(i) { 0f }
                for (i in 0 until 12) chroma[i] += f.chroma.getOrElse(i) { 0f }
                rms += f.rmsDb
                low += f.low
                flux += f.flux
            }
            val n = slice.size.toFloat()
            for (i in mfcc.indices) mfcc[i] /= n
            var cn = 0.0
            for (v in chroma) cn += v * v
            val norm = sqrt(cn).toFloat().coerceAtLeast(1e-6f)
            for (i in chroma.indices) chroma[i] /= norm
            val bass = (low / slice.size).toFloat()
            bars.add(Bar(mfcc, chroma, (rms / slice.size).toFloat(), bass, (flux / slice.size).toFloat(), bass))
        }
        zscore(bars)
        return bars
    }

    private fun zscore(bars: List<Bar>) {
        if (bars.isEmpty()) return
        for (c in 0 until 13) {
            var mean = 0.0
            for (b in bars) mean += b.mfcc[c]
            mean /= bars.size
            var v = 0.0
            for (b in bars) v += (b.mfcc[c] - mean) * (b.mfcc[c] - mean)
            val sd = sqrt(v / bars.size).toFloat().coerceAtLeast(1e-4f)
            for (b in bars) b.mfcc[c] = ((b.mfcc[c] - mean) / sd).toFloat()
        }
    }

    private fun mfccOf(mel: FloatArray): FloatArray {
        val out = FloatArray(13)
        for (k in 0 until 13) {
            var s = 0.0
            for (n in mel.indices) s += mel[n] * cos(PI * k * (n + 0.5) / mel.size)
            out[k] = s.toFloat()
        }
        return out
    }

    private fun chromaOf(mag: FloatArray): FloatArray {
        val chroma = FloatArray(12)
        for (k in 1 until mag.size) {
            val hz = k * RATE.toDouble() / FRAME
            if (hz < 65.0 || hz > 2093.0) continue
            val pitch = 12.0 * ln(hz / 440.0) / ln(2.0) + 69.0
            val pc = ((pitch.roundToInt() % 12) + 12) % 12
            chroma[pc] += mag[k]
        }
        var n = 0.0
        for (v in chroma) n += v * v
        val d = sqrt(n).toFloat().coerceAtLeast(1e-6f)
        for (i in chroma.indices) chroma[i] /= d
        return chroma
    }

    private fun melBank(): Array<FloatArray> {
        val bins = FRAME / 2
        val bank = Array(40) { FloatArray(bins) }
        fun hzToMel(hz: Double) = 2595.0 * ln(1.0 + hz / 700.0) / ln(10.0)
        fun melToHz(mel: Double) = 700.0 * (exp(mel * ln(10.0) / 2595.0) - 1.0)
        val low = hzToMel(30.0)
        val high = hzToMel(8000.0)
        val points = DoubleArray(42) { melToHz(low + (high - low) * it / 41.0) }
        for (m in 0 until 40) {
            val left = points[m] * FRAME / RATE
            val center = points[m + 1] * FRAME / RATE
            val right = points[m + 2] * FRAME / RATE
            for (k in 0 until bins) {
                val w = when {
                    k < left || k > right -> 0.0
                    k < center -> (k - left) / (center - left)
                    else -> (right - k) / (right - center)
                }
                bank[m][k] = w.toFloat().coerceAtLeast(0f)
            }
        }
        return bank
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from <= 0 || input.isEmpty()) return input
        val n = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        val ratio = from.toDouble() / to
        for (i in 0 until n) {
            val x = i * ratio
            val j = x.toInt()
            val f = (x - j).toFloat()
            val a = input[j.coerceIn(0, input.lastIndex)]
            val b = input[(j + 1).coerceIn(0, input.lastIndex)]
            out[i] = a + (b - a) * f
        }
        return out
    }

    private class Fft(private val n: Int) {
        private val rev = IntArray(n)
        init {
            var bits = 0
            var v = n
            while (v > 1) {
                bits++
                v = v shr 1
            }
            for (i in 0 until n) {
                var x = i
                var y = 0
                for (b in 0 until bits) {
                    y = (y shl 1) or (x and 1)
                    x = x shr 1
                }
                rev[i] = y
            }
        }

        fun forward(real: FloatArray, imag: FloatArray) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) {
                    val tr = real[i]
                    real[i] = real[j]
                    real[j] = tr
                    val ti = imag[i]
                    imag[i] = imag[j]
                    imag[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wlenR = cos(ang)
                val wlenI = sin(ang)
                var i = 0
                while (i < n) {
                    var wr = 1.0
                    var wi = 0.0
                    for (j in 0 until len / 2) {
                        val u = i + j
                        val v = u + len / 2
                        val tr = wr * real[v] - wi * imag[v]
                        val ti = wr * imag[v] + wi * real[v]
                        real[v] = (real[u] - tr).toFloat()
                        imag[v] = (imag[u] - ti).toFloat()
                        real[u] = (real[u] + tr).toFloat()
                        imag[u] = (imag[u] + ti).toFloat()
                        val nwr = wr * wlenR - wi * wlenI
                        wi = wr * wlenI + wi * wlenR
                        wr = nwr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
