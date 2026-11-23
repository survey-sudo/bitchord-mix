package com.music.bitchord.playback.smart.structure

import com.music.bitchord.playback.smart.SectionKind
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** AUTOMIX-V2(D): every number StructureV2 tunes on, in one place. */
object StructureTuning {
    const val MIN_BARS = 12
    const val MAX_BARS = 512
    const val ROT_PENALTY = 0.08
    const val SSM_NOV_SCALE = 0.14
    const val TAU_MIN = 0.10
    const val TAU_MAX = 0.35
    const val TAU_MADS = 4.0
    const val LEN_W = 0.6
    const val NON_PEAK_PENALTY = 0.15
    const val CLUSTER_SIM = 0.80
    const val CLUSTER_MIN_SLACK = 0.06
    const val CLUSTER_MAX_DE_DB = 3.5
    const val PRE_RISE_DB = 1.5
    const val STRENGTH_SPAN = 0.35
    const val DROP_LOW_JUMP_DB = 7.0
    const val DROP_PRE_LOW_DB = -8.0
    const val BUILD_SLOPE = 0.08
    const val BUILD_HALF_RISE = 0.5
    const val BREAK_LOW_DB = -12.0
    const val BREAK_E_DB = -6.0
    const val VOCAL_BAR = 0.22
    const val CLARITY_ATONAL = 2.2f
    const val GRID_MARGIN = 1.15
}

/**
 * AUTOMIX-V2(D): structure analysis on the downbeat grid.
 *
 * Pipeline (all per bar, O(n^2) at most):
 *  1. bar features normalised inside the track (dB relative to the 90th percentile, z-scored timbre);
 *  2. self-similarity: key-invariant chroma correlation + timbre + energy + low band + vocal;
 *  3. novelty: Foote checkerboard (4 and 8 bar halves, exact-bar kernel) + feature steps;
 *  4. boundaries: dynamic program over novelty with a phrase-length prior (4/8/16 free);
 *  5. events: drops (bass + energy re-entry on a downbeat), builds (rising onsets/highs with
 *     the low end held back, ending on the drop), breakdowns (bass-light interior stretches);
 *  6. repetition families by average-linkage segment similarity along the diagonal;
 *  7. labels: chorus = most repeated loud (vocal) family, then hook, intro/outro, pre-chorus,
 *     verse, bridge, instrumental; rekordbox tags, mode and mood;
 *  8. confidences: per boundary, per section, [StructureResult.phraseConfidence],
 *     [StructureResult.labelConfidence].
 * Deterministic: no randomness, ties resolve to the lower index.
 */
object StructureV2 {

    fun analyze(
        bars: List<V2Bar>,
        downbeats: DoubleArray,
        bpm: Double,
        beatsPerBar: Int = 4,
        vocal: FloatArray? = null,
        lyricPrior: FloatArray? = null,
        gridConfidence: Float = 0.8f,
        stems: StemBars? = null,
        model: StructureModel? = AutomixV2Structure.model,
    ): StructureResult? {
        val n = bars.size
        if (n < StructureTuning.MIN_BARS || n > StructureTuning.MAX_BARS) return null
        val barSec = if (bpm > 1.0) beatsPerBar.coerceAtLeast(1) * 60.0 / bpm else 2.0

        // ---- 1. normalisation
        val e = DoubleArray(n) { bars[it].rmsDb.toDouble() }
        val l = DoubleArray(n) { bars[it].lowDb.toDouble() }
        val h = DoubleArray(n) { bars[it].highDb.toDouble() }
        val onset = DoubleArray(n) { bars[it].onsetRate.toDouble() }
        relativeTo90(e)
        relativeTo90(l)
        relativeTo90(h)
        val tdim = bars.minOf { it.timbre.size }
        val tz = Array(n) { DoubleArray(tdim) }
        for (c in 0 until tdim) {
            var m = 0.0
            for (b in bars) m += b.timbre[c]
            m /= n
            var v = 0.0
            for (b in bars) v += (b.timbre[c] - m) * (b.timbre[c] - m)
            val sd = max(sqrt(v / n), 1e-3)
            for (i in 0 until n) tz[i][c] = (bars[i].timbre[c] - m) / sd
        }
        val voc = vocalPerBar(n, vocal, stems)
        val vm = voc != null
        val vv = voc ?: DoubleArray(n)
        val lyric = DoubleArray(n) { (lyricPrior?.getOrNull(it) ?: 0f).toDouble().coerceIn(0.0, 1.0) }

        // ---- 2. self-similarity
        val wC: Double
        val wT: Double
        val wE: Double
        val wL: Double
        val wV: Double
        if (vm) {
            wC = 0.38; wT = 0.28; wE = 0.18; wL = 0.08; wV = 0.08
        } else {
            wC = 0.41; wT = 0.30; wE = 0.20; wL = 0.09; wV = 0.0
        }
        val centred = Array(n) { centredChroma(bars[it].chroma) }
        val s = DoubleArray(n * n)
        for (i in 0 until n) {
            s[i * n + i] = 1.0
            for (j in i + 1 until n) {
                val c = chromaCorr(centred[i], centred[j])
                val t = (cosine(tz[i], tz[j]) + 1.0) * 0.5
                val es = (1.0 - abs(e[i] - e[j]) / 8.0).coerceIn(0.0, 1.0)
                val ls = (1.0 - abs(l[i] - l[j]) / 10.0).coerceIn(0.0, 1.0)
                val vs = 1.0 - abs(vv[i] - vv[j])
                val v = wC * c + wT * t + wE * es + wL * ls + wV * vs
                s[i * n + j] = v
                s[j * n + i] = v
            }
        }

        // ---- 3. novelty
        val f4 = foote(s, n, 4)
        val f8 = foote(s, n, 8)
        val nov = DoubleArray(n + 1)
        for (b in 1 until n) {
            val taper = (min(b, n - b) / 3.0).coerceIn(0.0, 1.0)
            val ssm = taper * ((0.6 * f4[b] + 0.4 * f8[b]) / StructureTuning.SSM_NOV_SCALE).coerceIn(0.0, 1.0)
            val es = step(e, b, 2, 6.0)
            val ls = step(l, b, 2, 9.0)
            val os = step(onset, b, 2, 1.5)
            val vs = if (vm) step(vv, b, 2, 0.5) else 0.0
            val mx = max(max(es, ls), max(os, vs))
            val avg = (es + ls + os + vs) / (if (vm) 4.0 else 3.0)
            nov[b] = 0.55 * ssm + 0.25 * mx + 0.20 * avg
        }
        val modelOut = runCatching { model?.predict(bars, stems, bpm) }.getOrNull()
        if (modelOut != null && modelOut.boundary.size == n + 1) {
            for (b in 1 until n) nov[b] = 0.5 * nov[b] + 0.5 * modelOut.boundary[b].toDouble().coerceIn(0.0, 1.0)
        }

        // ---- 4. boundaries
        val tau = threshold(nov, n)
        var bounds = phraseDp(nov, n, tau)

        // ---- 5. events
        val drops = findDrops(e, l, n)
        val tension = DoubleArray(n) { onset[it] / 1.5 + h[it] / 6.0 }
        val builds = findBuilds(drops, tension, l, n)
        val inBuild = BooleanArray(n)
        for (span in builds) for (i in span.startBar until span.endBar) inBuild[i] = true
        val breakdownsRaw = findBreakdowns(e, l, inBuild, n)
        val breakdowns = breakdownsRaw.map { BarSpan(snap(it.startBar, bounds), snap(it.endBar, bounds), it.confidence) }
            .filter { it.endBar - it.startBar >= 4 }
        bounds = withEvents(bounds, drops, builds, breakdowns, n)

        // ---- 6. families
        val segStart = IntArray(bounds.size - 1) { bounds[it] }
        val segEnd = IntArray(bounds.size - 1) { bounds[it + 1] }
        val m = segStart.size
        val clusters = ArrayList<ArrayList<Int>>()
        val cid = IntArray(m) { -1 }
        for (i in 0 until m) {
            var bestC = -1
            var bestV = 0.0
            val eI = mean(e, segStart[i], segEnd[i])
            for (c in clusters.indices) {
                var acc = 0.0
                var worst = Double.POSITIVE_INFINITY
                var cE = 0.0
                for (j in clusters[c]) {
                    val sim = segSim(s, n, segStart[i], segEnd[i], segStart[j], segEnd[j])
                    acc += sim
                    worst = min(worst, sim)
                    cE += mean(e, segStart[j], segEnd[j])
                }
                // average linkage, guarded against chaining (complete-linkage floor) and level jumps
                if (worst < StructureTuning.CLUSTER_SIM - StructureTuning.CLUSTER_MIN_SLACK) continue
                if (abs(eI - cE / clusters[c].size) > StructureTuning.CLUSTER_MAX_DE_DB) continue
                val v = acc / clusters[c].size
                if (v > bestV) {
                    bestV = v
                    bestC = c
                }
            }
            if (bestC >= 0 && bestV >= StructureTuning.CLUSTER_SIM) {
                clusters[bestC].add(i)
                cid[i] = bestC
            } else {
                clusters.add(arrayListOf(i))
                cid[i] = clusters.size - 1
            }
        }
        val size = IntArray(m) { clusters[cid[it]].size }
        val sE = DoubleArray(m) { mean(e, segStart[it], segEnd[it]) }
        val sV = DoubleArray(m) { mean(vv, segStart[it], segEnd[it]) }
        val sLy = DoubleArray(m) { mean(lyric, segStart[it], segEnd[it]) }

        // ---- 7. labels
        val kind = arrayOfNulls<SectionKind>(m)
        val conf = DoubleArray(m) { 0.5 }
        val dropBars = HashSet<Int>()
        for (d in drops) dropBars.add(d.bar)
        for (i in 0 until m) {
            for (span in builds) if (segStart[i] >= span.startBar && segEnd[i] <= span.endBar) {
                kind[i] = SectionKind.BUILD
                conf[i] = 0.7
            }
            for (span in breakdowns) if (segStart[i] >= span.startBar && segEnd[i] <= span.endBar && kind[i] == null) {
                kind[i] = SectionKind.BREAKDOWN
                conf[i] = 0.65
            }
            if (segStart[i] in dropBars && (!vm || sV[i] < 0.3)) {
                kind[i] = SectionKind.DROP
                conf[i] = 0.75
            }
        }
        val eMed = sE.sorted()[m / 2]
        var vocalBars = 0
        for (x in vv) if (x >= 0.3) vocalBars++
        val vocalCov = vocalBars.toDouble() / n
        val mode = if ((drops.size >= 2 || (drops.isNotEmpty() && builds.isNotEmpty())) && (!vm || vocalCov < 0.45)) {
            StructureMode.DANCE
        } else {
            StructureMode.POP
        }
        if (mode == StructureMode.POP) {
            for (i in 0 until m) if (kind[i] == SectionKind.DROP && vm && sV[i] >= 0.3) kind[i] = null
        }

        // chorus family: most repeated loud (vocal, lyric-repeated) family
        var bestScore = Double.NEGATIVE_INFINITY
        var secondScore = 0.0
        var bestCluster = -1
        for (c in clusters.indices) {
            val members = clusters[c]
            if (members.size < 2) continue
            if (members.any { kind[it] == SectionKind.BUILD || kind[it] == SectionKind.BREAKDOWN }) continue
            var ce = 0.0
            var cv = 0.0
            var cl = 0.0
            var cover = 0
            for (j in members) {
                ce += sE[j]
                cv += sV[j]
                cl += sLy[j]
                cover += segEnd[j] - segStart[j]
            }
            ce /= members.size
            cv /= members.size
            cl /= members.size
            if (ce < eMed - 0.5) continue
            if (cover.toDouble() / n > 0.6) continue
            var sc = 0.40 * ((ce + 12.0) / 12.0).coerceIn(0.0, 1.0) +
                0.20 * ((members.size - 1) / 2.0).coerceIn(0.0, 1.0) +
                (if (vm) 0.20 * (cv / 0.6).coerceIn(0.0, 1.0) else 0.10) +
                0.15 * (cl / 0.5).coerceIn(0.0, 1.0)
            if (0 in members) sc -= 0.15
            if (vm && cv < 0.25 && members.none { kind[it] == SectionKind.DROP }) sc -= 0.25
            if (sc > bestScore) {
                secondScore = if (bestCluster >= 0) bestScore else secondScore
                bestScore = sc
                bestCluster = c
            } else if (sc > secondScore) {
                secondScore = sc
            }
        }
        val families = ArrayList<ArrayList<Int>>()
        var chorusE = Double.NaN
        val famSet = HashSet<Int>()
        if (bestCluster >= 0) {
            var fam = ArrayList(clusters[bestCluster])
            var famMinE = Double.POSITIVE_INFINITY
            for (j in fam) famMinE = min(famMinE, sE[j])
            val base = ArrayList(fam)
            for (i in 0 until m) {
                if (i in base || kind[i] == SectionKind.BUILD || kind[i] == SectionKind.BREAKDOWN) continue
                var acc = 0.0
                for (j in base) acc += segSim(s, n, segStart[i], segEnd[i], segStart[j], segEnd[j])
                if (acc / base.size >= StructureTuning.CLUSTER_SIM - 0.06 && sE[i] >= famMinE - 1.5) fam.add(i)
            }
            fam.sort()
            var cover = 0
            for (j in fam) cover += segEnd[j] - segStart[j]
            if (cover.toDouble() / n > 0.6) fam = ArrayList(base)
            val fconf = (0.45 + (bestScore - secondScore) + 0.1 * (fam.size - 2)).coerceIn(0.4, 0.95)
            var acc = 0.0
            for (j in fam) {
                if (kind[j] != SectionKind.DROP || mode == StructureMode.POP) kind[j] = SectionKind.CHORUS
                conf[j] = max(conf[j], fconf)
                acc += sE[j]
                famSet.add(j)
            }
            chorusE = acc / fam.size
            families.add(fam)
        }
        // hook: short repeated loud block right after a chorus
        if (!chorusE.isNaN()) {
            for (c in clusters.indices) {
                val members = clusters[c]
                if (members.size < 2) continue
                if (members.any { it in famSet || kind[it] != null }) continue
                var maxLen = 0
                var after = 0
                var ce = 0.0
                for (j in members) {
                    maxLen = max(maxLen, segEnd[j] - segStart[j])
                    if ((j - 1) in famSet) after++
                    ce += sE[j]
                }
                ce /= members.size
                if (maxLen <= 8 && after * 2 >= members.size && ce >= chorusE - 2.5) {
                    for (j in members) {
                        kind[j] = SectionKind.HOOK
                        conf[j] = 0.6
                    }
                    families.add(ArrayList(members.sorted()))
                }
            }
        }
        // intro / outro
        var firstEvent = m
        for (i in 0 until m) {
            val k = kind[i]
            if (k == SectionKind.CHORUS || k == SectionKind.DROP || k == SectionKind.BUILD ||
                k == SectionKind.BREAKDOWN || k == SectionKind.HOOK
            ) {
                firstEvent = i
                break
            }
        }
        for (i in 0 until m) {
            if (i >= firstEvent) break
            if (mode == StructureMode.DANCE && kind[i] == null && firstEvent < m) {
                kind[i] = SectionKind.INTRO
                conf[i] = 0.7
                continue
            }
            val quiet = !chorusE.isNaN() && sE[i] <= chorusE - 3.0
            if (i == 0 || (vm && sV[i] < 0.25) ||
                (!vm && quiet && (size[i] == 1 || cid[i] == cid[0] || cid[i] == cid[m - 1]))
            ) {
                kind[i] = SectionKind.INTRO
                conf[i] = if (i == 0) 0.7 else 0.55
            } else {
                break
            }
        }
        var lastEvent = -1
        for (i in 0 until m) {
            val k = kind[i]
            if (k == SectionKind.CHORUS || k == SectionKind.DROP || k == SectionKind.HOOK) lastEvent = i
        }
        for (i in m - 1 downTo 0) {
            if (i <= lastEvent) break
            val k = kind[i]
            if (k != null && k != SectionKind.BREAKDOWN) break
            if (mode == StructureMode.DANCE && lastEvent >= 0) {
                kind[i] = SectionKind.OUTRO
                conf[i] = 0.7
                continue
            }
            val quiet = !chorusE.isNaN() && sE[i] <= chorusE - 3.0
            if (i == m - 1 || (vm && sV[i] < 0.25) || (!vm && (size[i] == 1 || quiet))) {
                kind[i] = SectionKind.OUTRO
                conf[i] = if (i == m - 1) 0.7 else 0.55
            } else {
                break
            }
        }
        // pre-chorus
        for (j in famSet.sorted()) {
            val p = j - 1
            if (p < 0 || kind[p] != null) continue
            val len = segEnd[p] - segStart[p]
            val repeatsBefore = size[p] >= 2 && clusters[cid[p]].all { (it + 1) in famSet }
            // A short bass-cut gap that ends on a drop is the pop "pre-chorus drop-out".
            val preDropGap = len <= 4 && segEnd[p] in dropBars
            val rise = mean(e, segStart[p] + len / 2, segEnd[p]) - mean(e, segStart[p], segStart[p] + len / 2)
            if (len <= 8 && (rise >= StructureTuning.PRE_RISE_DB || (p > 0 && sE[p] >= sE[p - 1] + 1.0) || preDropGap || repeatsBefore)) {
                val targets: List<Int> = if (size[p] >= 2) clusters[cid[p]] else listOf(p)
                for (q in targets) if (kind[q] == null) {
                    kind[q] = SectionKind.PRE_CHORUS
                    conf[q] = 0.6
                }
            }
        }
        // verse / bridge / instrumental
        val famSorted = famSet.sorted()
        val secondChorus = if (famSorted.size >= 2) famSorted[1] else -1
        val lastChorus = famSorted.lastOrNull() ?: -1
        val firstChorus = famSorted.firstOrNull() ?: -1
        for (i in 0 until m) {
            if (kind[i] != null) continue
            if (secondChorus >= 0 && i > secondChorus && i < lastChorus && size[i] == 1) {
                kind[i] = SectionKind.BRIDGE
                conf[i] = 0.55
                continue
            }
            if ((vm && sV[i] >= 0.3) || (!vm && size[i] >= 2) || (!vm && firstChorus >= 0 && i < firstChorus)) {
                kind[i] = SectionKind.VERSE
                conf[i] = if (size[i] >= 2) 0.6 else 0.45
            } else {
                kind[i] = SectionKind.INST
                conf[i] = 0.4
            }
        }
        // learned labels override weak DSP labels (never the energy events)
        if (modelOut != null) applyModelLabels(modelOut, kind, conf, segStart, segEnd, n)

        // ---- 8. boundary confidence, sections, confidences
        val bconfAt = DoubleArray(n + 1)
        for (b in 1 until n) bconfAt[b] = ((nov[b] - tau * 0.5) / StructureTuning.STRENGTH_SPAN).coerceIn(0.0, 1.0)
        for (d in drops) bconfAt[d.bar] = max(bconfAt[d.bar], d.strength.toDouble())
        bconfAt[0] = 1.0
        bconfAt[n] = 1.0

        val kinds = Array(m) { kind[it] ?: SectionKind.INST }
        val repeatSeen = HashMap<Int, Int>()
        val sections = ArrayList<V2Section>()
        var i = 0
        while (i < m) {
            var j = i + 1
            var c = conf[i]
            while (j < m && kinds[j] == kinds[i] && (cid[j] == cid[i] || mergeable(kinds[i]) || nov[segStart[j]] < tau * 1.5)) {
                c = max(c, conf[j])
                j++
            }
            val fam = cid[i]
            val rep = repeatSeen[fam] ?: 0
            repeatSeen[fam] = rep + 1
            sections.add(
                V2Section(
                    startBar = segStart[i],
                    endBar = segEnd[j - 1],
                    kind = kinds[i],
                    conf = c.toFloat(),
                    family = fam,
                    repeatIndex = rep,
                    tag = tagOf(kinds[i], mode),
                    startConfidence = bconfAt[segStart[i]].toFloat(),
                ),
            )
            i = j
        }

        val interior = ArrayList<Int>()
        for (b in bounds) if (b in 1 until n) interior.add(b)
        var reg4 = 0
        var reg = 0
        for (k in 0 until m) {
            val len = segEnd[k] - segStart[k]
            if (len % 4 == 0) reg4 += len
            if (len == 4 || len == 8 || len == 12 || len == 16 || len == 24 || len == 32) reg += len
        }
        var strength = 0.0
        for (b in interior) strength += bconfAt[b]
        strength = if (interior.isEmpty()) 0.0 else strength / interior.size
        val g = (gridConfidence / 0.7).toDouble().coerceIn(0.35, 1.0)
        val phraseConfidence = if (interior.isEmpty()) 0.0 else {
            (g * strength * (0.55 + 0.30 * reg4 / n + 0.15 * reg / n)).coerceIn(0.0, 1.0)
        }
        var lc = 0.0
        for (k in 0 until m) lc += conf[k] * (segEnd[k] - segStart[k])
        lc /= n
        lc *= 0.6 + 0.4 * strength
        if (famSet.isEmpty() && drops.isEmpty()) lc *= 0.7

        val reanchors = ArrayList<Int>()
        var local = 0
        for (b in interior) {
            if ((b - local) % 4 != 0 && bconfAt[b] >= 0.5) {
                reanchors.add(b)
                local = b
            }
        }

        val familyStarts = families.map { f -> IntArray(f.size) { segStart[f[it]] } }
        val boundsArr = bounds
        val bconf = FloatArray(boundsArr.size) { bconfAt[boundsArr[it]].toFloat() }
        val mood = moodOf(mode, bpm, onset, l)
        val intro = edgeInfo(sections, true, n, vm, vv, onset, l, e, bars, stems)
        val outro = edgeInfo(sections, false, n, vm, vv, onset, l, e, bars, stems)
        return StructureResult(
            version = AutomixV2Structure.VERSION,
            downbeats = downbeats,
            barSec = barSec,
            sections = sections,
            boundaries = boundsArr,
            boundaryConfidence = bconf,
            novelty = FloatArray(n + 1) { nov[it].toFloat() },
            drops = drops,
            builds = builds,
            breakdowns = breakdowns,
            families = familyStarts,
            reanchors = reanchors.toIntArray(),
            phraseConfidence = phraseConfidence,
            labelConfidence = lc.coerceIn(0.0, 1.0),
            mode = mode,
            mood = mood,
            intro = intro,
            outro = outro,
            vocalMeasured = vm,
            hasChorusFamily = famSet.isNotEmpty(),
        )
    }

    /**
     * Recompute the intro/outro edge info with new vocal evidence (Pass 2
     * windows), keeping every other field. Bars outside [sung] keep "no vocal".
     */
    fun refreshEdges(result: StructureResult, sung: BooleanArray): StructureResult {
        val n = result.bars
        if (n <= 0 || sung.isEmpty()) return result
        val introEnd = edgeBars(result.sections, true, n)
        val outroStart = n - edgeBars(result.sections, false, n)
        var vfIn = 0
        while (vfIn < n && !sung.getOrElse(vfIn) { false }) vfIn++
        var vfOut = 0
        while (vfOut < n && !sung.getOrElse(n - 1 - vfOut) { false }) vfOut++
        val intro = EdgeInfo(
            result.intro.bars, vfIn, result.intro.beatBars, result.intro.drumsOnly,
            mixability(introEnd, vfIn, result.intro.beatBars, result.intro.drumsOnly, 1.0),
        )
        val outro = EdgeInfo(
            result.outro.bars, vfOut, result.outro.beatBars, result.outro.drumsOnly,
            mixability(n - outroStart, vfOut, result.outro.beatBars, result.outro.drumsOnly, 1.0),
        )
        return result.withEdges(intro, outro)
    }

    // ---------------------------------------------------------------- helpers

    private fun relativeTo90(v: DoubleArray) {
        val ref = percentile(v, 0.9)
        for (i in v.indices) v[i] -= ref
    }

    private fun percentile(v: DoubleArray, q: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val i = kotlin.math.round(q * (s.size - 1)).toInt().coerceIn(0, s.size - 1)
        return s[i]
    }

    private fun mean(v: DoubleArray, from: Int, until: Int): Double {
        val a = max(0, from)
        val b = min(v.size, until)
        if (b <= a) return 0.0
        var acc = 0.0
        for (i in a until b) acc += v[i]
        return acc / (b - a)
    }

    private fun slope(v: DoubleArray, from: Int, until: Int): Double {
        val len = until - from
        if (len < 2 || from < 0 || until > v.size) return 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until len) {
            val y = v[from + i]
            sx += i
            sy += y
            sxx += i.toDouble() * i
            sxy += i * y
        }
        val d = len * sxx - sx * sx
        return if (abs(d) < 1e-9) 0.0 else (len * sxy - sx * sy) / d
    }

    private fun centredChroma(c: FloatArray): DoubleArray {
        val out = DoubleArray(12)
        var m = 0.0
        for (k in 0 until 12) m += c.getOrElse(k) { 0f }
        m /= 12.0
        var norm = 0.0
        for (k in 0 until 12) {
            out[k] = c.getOrElse(k) { 0f } - m
            norm += out[k] * out[k]
        }
        norm = sqrt(norm)
        if (norm < 1e-9) return DoubleArray(12)
        for (k in 0 until 12) out[k] /= norm
        return out
    }

    /** Key-invariant chroma correlation; a transposition costs [StructureTuning.ROT_PENALTY]. */
    private fun chromaCorr(a: DoubleArray, b: DoubleArray): Double {
        var best = 0.0
        for (k in 0 until 12) {
            var dot = 0.0
            for (i in 0 until 12) dot += a[i] * b[(i + k) % 12]
            val c = if (k == 0) dot else dot - StructureTuning.ROT_PENALTY
            if (c > best) best = c
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        val len = min(a.size, b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until len) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = sqrt(na * nb)
        return if (d < 1e-9) 0.0 else dot / d
    }

    /**
     * Gaussian checkerboard novelty. Past = bars [b-half, b), future = [b, b+half),
     * so a change between bar b-1 and bar b peaks exactly at b (the vc80 kernel
     * skips bar b and ties b-1 with b).
     */
    private fun foote(s: DoubleArray, n: Int, half: Int): DoubleArray {
        val out = DoubleArray(n + 1)
        val sig = half / 2.0
        val w = 2 * half
        val g = DoubleArray(w * w)
        for (a in 0 until w) for (c in 0 until w) {
            val di = a - half + 0.5
            val dj = c - half + 0.5
            g[a * w + c] = exp(-(di * di + dj * dj) / (2.0 * sig * sig))
        }
        for (b in 1 until n) {
            var acc = 0.0
            var wsum = 0.0
            for (a in 0 until w) {
                val x = b + a - half
                if (x < 0 || x >= n) continue
                for (c in 0 until w) {
                    val y = b + c - half
                    if (y < 0 || y >= n || x == y) continue
                    val same = (a < half) == (c < half)
                    val gw = g[a * w + c]
                    acc += if (same) gw * s[x * n + y] else -gw * s[x * n + y]
                    wsum += gw
                }
            }
            out[b] = if (wsum > 0.0) max(0.0, acc / wsum) else 0.0
        }
        return out
    }

    private fun step(v: DoubleArray, b: Int, w0: Int, scale: Double): Double {
        val n = v.size
        var w = w0
        if (b - w < 0 || b + w > n) {
            w = min(b, n - b)
            if (w < 1) return 0.0
        }
        return (abs(mean(v, b, b + w) - mean(v, b - w, b)) / scale).coerceIn(0.0, 1.0)
    }

    /** Noise floor of the novelty curve: median + k * MAD, clamped. */
    private fun threshold(nov: DoubleArray, n: Int): Double {
        val inner = DoubleArray(n - 1) { nov[it + 1] }
        val sorted = inner.sorted()
        val med = sorted[sorted.size / 2]
        val dev = sorted.map { abs(it - med) }.sorted()
        val mad = dev[dev.size / 2]
        return (med + StructureTuning.TAU_MADS * mad).coerceIn(StructureTuning.TAU_MIN, StructureTuning.TAU_MAX)
    }

    private fun lenCost(len: Int): Double = when {
        len < 2 -> 1e9
        len == 4 || len == 8 || len == 16 -> 0.0
        len == 12 -> 0.02
        len == 32 -> 0.04
        len == 2 || len == 6 || len == 24 -> 0.12
        len > 64 -> 1e9
        len % 2 == 0 -> if (len < 32) 0.2 else 0.25
        else -> 0.3
    }

    /** Boundaries maximising sum(novelty - tau) - LEN_W * lenCost; always includes 0 and n. */
    private fun phraseDp(nov: DoubleArray, n: Int, tau: Double): IntArray {
        val dp = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val prev = IntArray(n + 1) { -1 }
        dp[0] = 0.0
        for (end in 2..n) {
            val ge = if (end >= n) 0.0 else {
                var gg = nov[end] - tau
                if (nov[end] < nov[end - 1] || (end + 1 < n && nov[end] < nov[end + 1])) gg -= StructureTuning.NON_PEAK_PENALTY
                gg
            }
            for (st in max(0, end - 64) until end - 1) {
                if (dp[st] == Double.NEGATIVE_INFINITY) continue
                val sc = dp[st] + ge - StructureTuning.LEN_W * lenCost(end - st)
                if (sc > dp[end]) {
                    dp[end] = sc
                    prev[end] = st
                }
            }
        }
        if (prev[n] < 0) return intArrayOf(0, n)
        val out = ArrayList<Int>()
        var at = n
        while (at > 0) {
            out.add(at)
            val p = prev[at]
            if (p < 0 || p >= at) return intArrayOf(0, n)
            at = p
        }
        out.add(0)
        out.sort()
        return out.toIntArray()
    }

    /** Bass and energy re-entry on a downbeat after at least two bass-light bars. */
    private fun findDrops(e: DoubleArray, l: DoubleArray, n: Int): List<BarEvent> {
        val cand = ArrayList<Int>()
        for (b in 2 until n - 1) {
            val before = mean(l, b - 2, b)
            val jump = mean(l, b, min(n, b + 2)) - before
            if (jump < StructureTuning.DROP_LOW_JUMP_DB) continue
            if (before > StructureTuning.DROP_PRE_LOW_DB) continue
            if (mean(l, b, min(n, b + 4)) < -7.0) continue
            val eAfter = mean(e, b, min(n, b + 4))
            if (eAfter < -5.0) continue
            if (eAfter - mean(e, b - 2, b) < 1.0 && jump < 10.0) continue
            cand.add(b)
        }
        val drops = ArrayList<BarEvent>()
        var i = 0
        while (i < cand.size) {
            var j = i
            while (j + 1 < cand.size && cand[j + 1] - cand[j] <= 1) j++
            var bestB = cand[i]
            var bestV = Double.NEGATIVE_INFINITY
            for (k in i..j) {
                val b = cand[k]
                val v = (l[b] - l[b - 1]) + 0.5 * (e[b] - e[b - 1])
                if (v > bestV) {
                    bestV = v
                    bestB = b
                }
            }
            val jump = mean(l, bestB, min(n, bestB + 2)) - mean(l, bestB - 2, bestB)
            if (drops.isEmpty() || bestB - drops.last().bar > 2) {
                drops.add(BarEvent(bestB, (jump / 12.0).coerceIn(0.0, 1.0).toFloat()))
            }
            i = j + 1
        }
        return drops
    }

    /**
     * Longest of 16/8/4 bars ending on a drop where tension (onset density + highs)
     * rises steadily through both halves while the low end stays held back.
     */
    private fun findBuilds(drops: List<BarEvent>, ten: DoubleArray, l: DoubleArray, n: Int): List<BarSpan> {
        val out = ArrayList<BarSpan>()
        for (d in drops) {
            val db = d.bar
            for (len in intArrayOf(16, 8, 4)) {
                val st = db - len
                if (st < 0) continue
                if (drops.any { it.bar in (st + 1) until db }) continue
                if (out.isNotEmpty() && st < out.last().endBar) continue
                val th = StructureTuning.BUILD_SLOPE * (if (len < 8) 8.0 / len else 1.0)
                val rise = slope(ten, st, db)
                val half = mean(ten, db - len / 2, db) - mean(ten, st, st + len / 2)
                val dropLow = mean(l, db, min(n, db + 4))
                val lowGap = dropLow - mean(l, st, db)
                val lowGapHead = dropLow - mean(l, st, st + max(2, len / 2))
                var ok = rise >= th && half >= StructureTuning.BUILD_HALF_RISE * (if (len == 4) 1.6 else 1.0) &&
                    lowGap >= 5.0 && lowGapHead >= 4.0
                if (ok && len >= 8) {
                    ok = slope(ten, st, st + len / 2) >= 0.5 * th && slope(ten, st + len / 2, db) >= 0.5 * th
                }
                if (ok) {
                    out.add(BarSpan(st, db, (0.5 + half / 4.0).coerceIn(0.5, 0.95).toFloat()))
                    break
                }
            }
        }
        return out
    }

    private fun findBreakdowns(e: DoubleArray, l: DoubleArray, inBuild: BooleanArray, n: Int): List<BarSpan> {
        val out = ArrayList<BarSpan>()
        var b = 1
        while (b < n - 1) {
            if (l[b] <= StructureTuning.BREAK_LOW_DB && e[b] <= StructureTuning.BREAK_E_DB && !inBuild[b]) {
                var c = b
                var depth = 0.0
                while (c < n && l[c] <= StructureTuning.BREAK_LOW_DB && e[c] <= StructureTuning.BREAK_E_DB && !inBuild[c]) {
                    depth += -l[c]
                    c++
                }
                if (c - b >= 4 && c < n) {
                    out.add(BarSpan(b, c, (0.4 + depth / (c - b) / 40.0).coerceIn(0.4, 0.9).toFloat()))
                }
                b = c
            } else {
                b++
            }
        }
        return out
    }

    private fun snap(x: Int, bounds: IntArray): Int {
        for (y in bounds) if (abs(y - x) <= 1) return y
        return x
    }

    /** Drops pin their exact bar (replacing a novelty boundary one bar off); builds and breakdowns add edges. */
    private fun withEvents(bounds: IntArray, drops: List<BarEvent>, builds: List<BarSpan>, breakdowns: List<BarSpan>, n: Int): IntArray {
        val set = ArrayList<Int>()
        for (b in bounds) set.add(b)
        val pinned = HashSet<Int>()
        for (d in drops) {
            set.removeAll { abs(it - d.bar) == 1 && it != 0 && it != n }
            if (d.bar !in set) set.add(d.bar)
            pinned.add(d.bar)
        }
        for (span in builds) if (set.all { abs(it - span.startBar) > 1 }) set.add(span.startBar)
        for (span in breakdowns) {
            if (set.all { abs(it - span.startBar) > 1 }) set.add(span.startBar)
            if (set.all { abs(it - span.endBar) > 1 }) set.add(span.endBar)
        }
        val filtered = set.filter { x -> x in 0..n && builds.none { x > it.startBar && x < it.endBar } }.distinct().sorted()
        val clean = ArrayList<Int>()
        clean.add(0)
        for (x in filtered) {
            if (x <= 0) continue
            if (x >= n) break
            if (x - clean.last() >= 2) {
                clean.add(x)
            } else if (x in pinned && clean.last() != 0 && clean.last() !in pinned) {
                clean[clean.size - 1] = x
            }
        }
        if (n - clean.last() < 2 && clean.size > 1 && clean.last() !in pinned) clean.removeAt(clean.size - 1)
        clean.add(n)
        return clean.toIntArray()
    }

    /** Mean of the diagonal stripe between two segments, start- or end-aligned. */
    private fun segSim(s: DoubleArray, n: Int, a0: Int, a1: Int, b0: Int, b1: Int): Double {
        val la = a1 - a0
        val lb = b1 - b0
        val len = min(la, lb)
        if (len <= 0) return 0.0
        var best = 0.0
        val alignments = if (la == lb) 1 else 2
        for (o in 0 until alignments) {
            val sa = if (o == 0) a0 else a1 - len
            val sb = if (o == 0) b0 else b1 - len
            var acc = 0.0
            for (k in 0 until len) acc += s[(sa + k) * n + (sb + k)]
            val v = acc / len
            if (v > best) best = v
        }
        val ratio = len.toDouble() / max(la, lb)
        if (ratio < 0.5) best *= 0.85 else if (ratio < 0.75) best *= 0.92
        return best
    }

    private fun vocalPerBar(n: Int, vocal: FloatArray?, stems: StemBars?): DoubleArray? {
        val out = DoubleArray(n)
        var any = false
        if (vocal != null) {
            for (i in 0 until n) {
                val v = vocal.getOrElse(i) { 0f }.toDouble()
                out[i] = if (v.isNaN()) 0.0 else v.coerceIn(0.0, 1.0)
                if (out[i] > 0.2) any = true
            }
        }
        if (stems != null) {
            for (i in 0 until n) {
                if (!stems.covered(i)) continue
                if (vocal != null && i < vocal.size && !vocal[i].isNaN()) continue
                val pv = dbToPow(stems.vocalsDb[i])
                val total = pv + dbToPow(stems.drumsDb[i]) + dbToPow(stems.bassDb.getOrElse(i) { Float.NaN }) +
                    dbToPow(stems.otherDb.getOrElse(i) { Float.NaN })
                if (total <= 1e-12) continue
                out[i] = ((pv / total - 0.05) / 0.3).coerceIn(0.0, 1.0)
                if (out[i] > 0.2) any = true
            }
        }
        return if (any) out else null
    }

    private fun dbToPow(db: Float): Double =
        if (db.isNaN() || db < -150f) 0.0 else kotlin.math.exp(db / 10.0 * kotlin.math.ln(10.0))

    private fun applyModelLabels(
        out: StructureModelOutput, kind: Array<SectionKind?>, conf: DoubleArray, segStart: IntArray, segEnd: IntArray, n: Int,
    ) {
        for (i in kind.indices) {
            val k = kind[i]
            if (k == SectionKind.DROP || k == SectionKind.BUILD || k == SectionKind.BREAKDOWN) continue
            if (conf[i] >= 0.6) continue
            var bestKind: SectionKind? = null
            var bestP = 0.6
            for ((label, probs) in out.labels) {
                if (probs.size < n) continue
                var acc = 0.0
                for (b in segStart[i] until segEnd[i]) acc += probs[b]
                val p = acc / (segEnd[i] - segStart[i]).coerceAtLeast(1)
                if (p > bestP) {
                    bestP = p
                    bestKind = label
                }
            }
            if (bestKind != null) {
                kind[i] = bestKind
                conf[i] = bestP.coerceIn(0.0, 0.9)
            }
        }
    }

    private fun mergeable(k: SectionKind): Boolean =
        k == SectionKind.INTRO || k == SectionKind.OUTRO || k == SectionKind.BUILD || k == SectionKind.BREAKDOWN

    /** rekordbox phrase tag for a v2 kind in this structure mode. */
    fun tagOf(kind: SectionKind, mode: StructureMode): PhraseTag = when (kind) {
        SectionKind.INTRO -> PhraseTag.INTRO
        SectionKind.OUTRO -> PhraseTag.OUTRO
        SectionKind.VERSE -> if (mode == StructureMode.DANCE) PhraseTag.UP else PhraseTag.VERSE
        SectionKind.PRE_CHORUS, SectionKind.BUILD -> PhraseTag.UP
        SectionKind.CHORUS, SectionKind.DROP, SectionKind.HOOK -> PhraseTag.CHORUS
        SectionKind.BRIDGE -> if (mode == StructureMode.DANCE) PhraseTag.DOWN else PhraseTag.BRIDGE
        SectionKind.BREAKDOWN -> PhraseTag.DOWN
        SectionKind.INST -> if (mode == StructureMode.DANCE) PhraseTag.UP else PhraseTag.BRIDGE
    }

    private fun moodOf(mode: StructureMode, bpm: Double, onset: DoubleArray, l: DoubleArray): StructureMood {
        var o = 0.0
        for (x in onset) o += x
        o /= onset.size.coerceAtLeast(1)
        val lowMed = l.sorted()[l.size / 2]
        return when {
            bpm in 1.0..92.0 || o < 0.9 -> StructureMood.LOW
            mode == StructureMode.DANCE && bpm >= 112.0 && o >= 1.3 -> StructureMood.HIGH
            bpm >= 118.0 && o >= 2.0 && lowMed >= -6.0 -> StructureMood.HIGH
            else -> StructureMood.MID
        }
    }

    private fun edgeBars(sections: List<V2Section>, head: Boolean, n: Int): Int {
        if (sections.isEmpty()) return 0
        return if (head) {
            var end = 0
            for (sct in sections) {
                if (sct.kind != SectionKind.INTRO) break
                end = sct.endBar
            }
            end
        } else {
            var start = n
            for (k in sections.indices.reversed()) {
                val sct = sections[k]
                if (sct.kind != SectionKind.OUTRO) break
                start = sct.startBar
            }
            n - start
        }
    }

    private fun edgeInfo(
        sections: List<V2Section>, head: Boolean, n: Int, vm: Boolean, voc: DoubleArray, onset: DoubleArray,
        l: DoubleArray, e: DoubleArray, bars: List<V2Bar>, stems: StemBars?,
    ): EdgeInfo {
        val len = edgeBars(sections, head, n)
        if (len <= 0) return EdgeInfo.NONE
        val from = if (head) 0 else n - len
        val until = if (head) len else n
        var vf = -1
        if (vm) {
            vf = 0
            if (head) {
                while (vf < n && voc[vf] < StructureTuning.VOCAL_BAR) vf++
            } else {
                while (vf < n && voc[n - 1 - vf] < StructureTuning.VOCAL_BAR) vf++
            }
        }
        var beat = 0
        var clarity = 0.0
        var onsets = 0.0
        for (b in from until until) {
            if (onset[b] >= 0.75 && l[b] >= -15.0) beat++
            clarity += bars[b].clarity
            onsets += onset[b]
        }
        clarity /= len
        onsets /= len
        var drumsOnly = clarity < StructureTuning.CLARITY_ATONAL && onsets >= 1.0
        if (stems != null) {
            var covered = 0
            var drumHeavy = 0
            for (b in from until until) {
                if (!stems.covered(b)) continue
                covered++
                val d = dbToPow(stems.drumsDb[b])
                val total = d + dbToPow(stems.vocalsDb[b]) + dbToPow(stems.bassDb.getOrElse(b) { Float.NaN }) +
                    dbToPow(stems.otherDb.getOrElse(b) { Float.NaN })
                if (total > 1e-12 && d / total >= 0.6 && dbToPow(stems.vocalsDb[b]) / total < 0.05) drumHeavy++
            }
            if (covered >= 2) drumsOnly = drumHeavy * 2 >= covered
        }
        var sd = 0.0
        val me = mean(e, from, until)
        for (b in from until until) sd += (e[b] - me) * (e[b] - me)
        sd = sqrt(sd / len)
        val steady = (1.0 - sd / 6.0).coerceIn(0.0, 1.0)
        return EdgeInfo(len, vf, beat, drumsOnly, mixability(len, vf, beat, drumsOnly, steady))
    }

    private fun mixability(len: Int, vocalFree: Int, beatBars: Int, drumsOnly: Boolean, steady: Double): Float {
        if (len <= 0) return 0f
        val usable = if (vocalFree >= 0) min(len, vocalFree) else len
        val lengthScore = (usable / 16.0).coerceIn(0.0, 1.0)
        val beatScore = (beatBars.toDouble() / len).coerceIn(0.0, 1.0)
        val texture = if (drumsOnly) 1.0 else 0.8
        return (lengthScore * (0.4 + 0.6 * beatScore) * texture * (0.5 + 0.5 * steady)).toFloat().coerceIn(0f, 1f)
    }
}
