package com.music.bitchord.playback.smart

/**
 * A [SongMap] from the analysis the app already stores. The heavy PCM pass
 * upgrades this when it finishes; the planner can run on this alone.
 */
object SongMaps {
    fun fromAnalysis(trackKey: String, analysis: TrackAnalysis, partial: Boolean = false): SongMap? {
        if (!analysis.isUsable || analysis.duration <= 1.0) return null
        val bpm = analysis.bpm.coerceIn(40.0, 220.0)
        val beat = if (analysis.beatInterval > 0.05) analysis.beatInterval else 60.0 / bpm
        val bar = beat * 4.0
        val bars = (analysis.duration / bar).toInt().coerceIn(4, 512)
        val downbeats = if (analysis.downbeats.size >= 4) {
            analysis.downbeats.filter { it.isFinite() && it >= 0.0 }.toDoubleArray()
        } else {
            DoubleArray(bars + 1) { it * bar }
        }
        val n = bars
        val sung = BooleanArray(n)
        val vocalEnergy = buildVocalBars(analysis, bar, n, sung)
        val first = sung.indexOfFirst { it }.let { if (it < 0) null else it }
        val last = sung.indexOfLast { it }.let { if (it < 0) null else it }
        val introEnd = barIndex(analysis.introEndTime, bar, n)
        val rawOutroStart = barIndex(analysis.outroStartTime, bar, n).coerceAtLeast(introEnd)
        val outroStart = if (analysis.outroStartTime > 0.0 && rawOutroStart < n) {
            (((rawOutroStart + 2) / 4) * 4).coerceIn(introEnd, n)
        } else {
            rawOutroStart
        }
        // BUILD/DROP mapping: per-bar energy slope (scalars only).
        // Build a tiny scalar energy array from vocalWindows levels coverage
        // is absent — use sung flags + section position as fallback, but
        // when energyCurve exists compute bar energies for slope detection.
        val barEnergy = DoubleArray(n) { b ->
            // Proxy from sung flag inversion is weak; use section heuristic
            // only when no curve. When curve exists, sample it directly.
            if (analysis.energyCurve.isNotEmpty()) {
                val t = b * bar
                val sample = analysis.energyCurve.minByOrNull { kotlin.math.abs(it.time - t) }
                sample?.energy ?: 1.0
            } else {
                if (sung[b]) 7.0 else 4.0
            }
        }
        val sortedE = barEnergy.toList().sorted()
        val q75 = sortedE[minOf(sortedE.lastIndex, (sortedE.size * 0.75).toInt())]
        fun slopePctB(fromB: Int, untilB: Int): Double {
            val nB = (untilB - fromB).coerceAtLeast(1)
            val midB = fromB + nB / 2
            var earlyB = 0.0; var ecB = 0
            for (b in fromB until midB) { earlyB += barEnergy[b]; ecB++ }
            var lateB = 0.0; var lcB = 0
            for (b in midB until untilB) { lateB += barEnergy[b]; lcB++ }
            if (ecB == 0 || lcB == 0) return 0.0
            earlyB /= ecB; lateB /= lcB
            if (earlyB <= 1e-6) return if (lateB > 1e-6) 0.5 else 0.0
            return (lateB - earlyB) / earlyB
        }
        // Collect BUILD -> DROP pairs (4-bar DROP, 4-16 bar BUILD before it).
        val buildDrops = ArrayList<Pair<IntRange, IntRange>>()
        for (dropBar in 4 until n - 4) {
            var prevMean = 0.0
            for (b in maxOf(0, dropBar - 4) until dropBar) prevMean += barEnergy[b]
            prevMean /= 4.0
            var curMean = 0.0
            for (b in dropBar until dropBar + 4) curMean += barEnergy[b]
            curMean /= 4.0
            val delta = if (prevMean > 1e-6) (curMean - prevMean) / maxOf(prevMean, 1e-6) else 0.0
            val isDrop = (delta >= 0.16 && curMean >= q75 * 0.9) || (curMean >= q75 * 1.15 && delta >= 0.05)
            if (!isDrop) continue
            var bestLoss: IntRange? = null
            for (len in 4..16) {
                val fromB = maxOf(0, dropBar - len)
                if (dropBar - fromB < 4 || dropBar - fromB > 16) continue
                if (slopePctB(fromB, dropBar) < 0.12) continue
                var risesB = 0
                for (b in fromB until dropBar - 1) {
                    if (barEnergy[b] > 1e-6 && barEnergy[b + 1] > barEnergy[b] * 1.05) risesB++
                }
                if (risesB < 2) continue
                // Low vocal in BUILD: no sung bars.
                var sungInBuild = false
                for (b in fromB until dropBar) if (sung[b]) { sungInBuild = true; break }
                if (sungInBuild) continue
                var bMean = 0.0
                for (b in fromB until dropBar) bMean += barEnergy[b]
                bMean /= (dropBar - fromB)
                if (bMean >= curMean * 0.98) continue
                if (bestLoss == null || (dropBar - fromB) > (bestLoss.last - bestLoss.first)) bestLoss = fromB until dropBar
            }
            if (bestLoss != null) buildDrops.add(bestLoss to (dropBar until dropBar + 4))
        }
        val sections = ArrayList<PhraseSection>()
        if (introEnd > 0) sections.add(phrase(0, introEnd, SectionFn.INTRO, SectionRole.GROOVE, 4, vocal = 0f))
        // Insert BUILD/DROP as INSTRUMENTAL variants with correct fn so
        // StructureMix.headBuildup + PhrasePlanner score them.
        val buildDropBars = HashMap<Int, SectionFn>()
        for ((bRange, dRange) in buildDrops) {
            for (b in bRange) buildDropBars[b] = SectionFn.BUILD
            for (b in dRange) buildDropBars[b] = SectionFn.DROP
        }
        val bodyEnd = if (outroStart > introEnd) outroStart else n
        // VERSE/CHORUS/BRIDGE/POST/PRE painting with StructureAnalyzer
        // parity (scalars only: per-bar energy + sung flags). Chorus = repeated
        // high-energy sung blocks (>=4 bars, >=2 hits, similar length/energy);
        // VERSE = sung body before the first chorus; BRIDGE = a one-off block
        // after the 2nd chorus in the tail half with lower energy and no
        // repetition; POST = short high-energy block after a chorus; PRE =
        // rising block before a chorus (slope >= 1.10, rises >= 2, matching
        // paintBuildDrop's gate so the two labeling paths agree on BUILD).
        fun meanE(fromB: Int, untilB: Int): Double {
            var s = 0.0; var c = 0
            for (b in fromB until untilB) { s += barEnergy[b]; c++ }
            return if (c == 0) 0.0 else s / c
        }
        fun risesIn(fromB: Int, untilB: Int): Int {
            var r = 0
            for (b in fromB until untilB - 1) {
                if (barEnergy[b] > 1e-6 && barEnergy[b + 1] > barEnergy[b] * 1.05) r++
            }
            return r
        }
        val chorusBlocks = ArrayList<IntRange>()
        if (q75 > 1e-6 && bodyEnd - introEnd >= 16) {
            val cand = ArrayList<IntRange>()
            var cb = introEnd
            while (cb < bodyEnd) {
                if (sung[cb] && barEnergy[cb] >= q75 * 0.90) {
                    var ce = cb + 1
                    while (ce < bodyEnd && sung[ce] && barEnergy[ce] >= q75 * 0.90) ce++
                    if (ce - cb >= 4) cand.add(cb until ce)
                    cb = ce
                } else cb++
            }
            if (cand.size >= 2) {
                // Group by length bucket + energy bucket; the group with most
                // hits (>=2) and highest mean energy is the chorus.
                val byKey = HashMap<Pair<Int, Int>, MutableList<IntRange>>()
                for (r in cand) {
                    val len = r.last - r.first + 1
                    val bucket = when {
                        len <= 5 -> 0; len <= 8 -> 1; len <= 12 -> 2; else -> 3
                    }
                    val mean = meanE(r.first, r.last + 1)
                    val eBucket = when {
                        q75 <= 1e-6 -> 0
                        mean >= q75 * 1.15 -> 2; mean >= q75 * 0.90 -> 1; else -> 0
                    }
                    byKey.getOrPut(bucket to eBucket) { ArrayList() }.add(r)
                }
                val best = byKey.values.filter { it.size >= 2 }
                    .maxByOrNull { list -> list.size * 1_000_000 + meanE(list[0].first, list[0].last + 1).toInt() }
                if (best != null) chorusBlocks.addAll(best.sortedBy { it.first })
            }
        }
        if (chorusBlocks.isNotEmpty()) {
            // Pre / Post / Bridge assignment (bar -> fn overlay; BUILD/DROP wins).
            val overlay = HashMap<Int, SectionFn>()
            fun assign(fromB: Int, untilB: Int, fn: SectionFn) {
                for (b in fromB until untilB) if (b !in buildDropBars) overlay[b] = fn
            }
            for (r in chorusBlocks) assign(r.first, r.last + 1, SectionFn.CHORUS)
            // PRE_CHORUS: immediately preceding each chorus with rising energy.
            for (r in chorusBlocks) {
                val preTo = r.first
                for (len in 8 downTo 4) {
                    val fromB = preTo - len
                    if (fromB < introEnd) continue
                    if (slopePctB(fromB, preTo) >= 0.10 && risesIn(fromB, preTo) >= 2) {
                        var overlaps = false
                        for (b in fromB until preTo) if (b in buildDropBars || overlay.containsKey(b)) { overlaps = true; break }
                        if (!overlaps) assign(fromB, preTo, SectionFn.PRE_CHORUS)
                        break
                    }
                }
            }
            // POST_CHORUS: short high-energy block immediately after chorus.
            for (r in chorusBlocks) {
                val postFrom = r.last + 1
                if (postFrom >= bodyEnd) continue
                var postTo = postFrom
                while (postTo < bodyEnd && postTo - postFrom < 4 && barEnergy[postTo] >= q75 * 0.90 && postTo !in buildDropBars && postTo !in overlay) postTo++
                if (postTo - postFrom >= 2) assign(postFrom, postTo, SectionFn.POST_CHORUS)
            }
            // BRIDGE: one-off block after the 2nd chorus, tail half, lower energy.
            if (chorusBlocks.size >= 2) {
                val after2 = chorusBlocks[1].last + 1
                val tailHalf = (introEnd + bodyEnd) / 2
                var bb = maxOf(after2, tailHalf)
                var bridgeDone = false
                while (bb < bodyEnd - 3 && !bridgeDone) {
                    if (bb in buildDropBars || overlay.containsKey(bb) || bb in buildDropBars) { bb++; continue }
                    var be = bb + 1
                    while (be < bodyEnd && (be !in buildDropBars) && (be !in overlay) && be - bb < 12) {
                        // Bridge has no lyric/energy repetition by construction (single block).
                        be++
                        // Stop the block at the next chorus start.
                        if (be in chorusBlocks.map { it.first }.toSet()) break
                    }
                    val len = be - bb
                    if (len >= 4) {
                        val m = meanE(bb, be)
                        val sungShare = (bb until be).count { sung[it] }.toFloat() / len
                        // Lower energy than chorus, some vocal (narrative), not high-energy.
                        if (m < q75 * 0.90 && sungShare >= 0.25f) {
                            assign(bb, be, SectionFn.BRIDGE)
                            bridgeDone = true
                        } else bb = be
                    } else bb++
                }
            }
            // VERSE: every remaining sung span in the body before the first
            // chorus is VERSE; sung spans elsewhere (not chorus/pre/post/
            // bridge/build/drop) are VERSE too.
            var vb = introEnd
            while (vb < bodyEnd) {
                if (sung[vb] && vb !in buildDropBars && vb !in overlay) {
                    var ve = vb + 1
                    while (ve < bodyEnd && sung[ve] && ve !in buildDropBars && ve !in overlay) ve++
                    assign(vb, ve, SectionFn.VERSE)
                    vb = ve
                } else vb++
            }
            var b = introEnd
            while (b < bodyEnd) {
                val fn = buildDropBars[b] ?: overlay[b] ?: if (sung[b] && (first != null && b >= first && (last == null || b <= last))) SectionFn.VERSE else SectionFn.INSTRUMENTAL
                var e = b + 1
                while (e < bodyEnd && (buildDropBars[e] ?: overlay[e] ?: if (sung[e] && (first != null && e >= first && (last == null || e <= last))) SectionFn.VERSE else SectionFn.INSTRUMENTAL) == fn) e++
                val lvl = when (fn) {
                    SectionFn.CHORUS -> 8; SectionFn.POST_CHORUS -> 8; SectionFn.PRE_CHORUS -> 6
                    SectionFn.DROP -> 8; SectionFn.BUILD -> 5; SectionFn.VERSE -> 6; SectionFn.BRIDGE -> 5
                    SectionFn.INTRO -> 4; SectionFn.OUTRO -> 4; else -> 5
                }
                val role = when (fn) {
                    SectionFn.CHORUS -> SectionRole.RELEASE; SectionFn.POST_CHORUS -> SectionRole.RELEASE
                    SectionFn.PRE_CHORUS -> SectionRole.TENSION; SectionFn.BUILD -> SectionRole.TENSION
                    SectionFn.DROP -> SectionRole.RELEASE; SectionFn.VERSE -> SectionRole.NARRATIVE
                    SectionFn.BRIDGE -> SectionRole.NARRATIVE; else -> SectionRole.GROOVE
                }
                val vocal = if (fn == SectionFn.CHORUS || fn == SectionFn.VERSE || fn == SectionFn.PRE_CHORUS || fn == SectionFn.BRIDGE) 0.7f else 0f
                sections.add(phrase(b, e, fn, role, lvl, vocal))
                b = e
            }
        } else if (first != null && first < bodyEnd) {
            sections.add(phrase(introEnd, first.coerceAtLeast(introEnd + 1), SectionFn.INTRO, SectionRole.GROOVE, 4, 0f))
            sections.add(phrase(first, (last ?: first) + 1, SectionFn.VERSE, SectionRole.NARRATIVE, 6, 0.7f))
        } else if (bodyEnd > introEnd) {
            // Split body by BUILD/DROP when present, else single INSTRUMENTAL.
            if (buildDropBars.isEmpty()) {
                sections.add(phrase(introEnd, bodyEnd, SectionFn.INSTRUMENTAL, SectionRole.GROOVE, 5, 0f))
            } else {
                var b = introEnd
                while (b < bodyEnd) {
                    val fn = buildDropBars[b]
                    var e = b + 1
                    while (e < bodyEnd && buildDropBars[e] == fn) e++
                    val lvl = if (fn == SectionFn.DROP) 8 else if (fn == SectionFn.BUILD) 5 else 5
                    val role = if (fn == SectionFn.DROP) SectionRole.RELEASE else if (fn == SectionFn.BUILD) SectionRole.TENSION else SectionRole.GROOVE
                    sections.add(phrase(b, e, fn ?: SectionFn.INSTRUMENTAL, role, lvl, 0f))
                    b = e
                }
            }
        }
        if (outroStart < n) sections.add(phrase(outroStart, n, SectionFn.OUTRO, SectionRole.GROOVE, 4, 0f, loopable = true))
        val cleaned = sections.filter { it.endBar > it.startBar }.ifEmpty {
            listOf(phrase(0, n, SectionFn.INSTRUMENTAL, SectionRole.GROOVE, 5, 0f))
        }
        val speech = analysis.vocalProbability > 0.85 && analysis.beatConfidence < 0.35
        return SongMap(
            trackKey = trackKey,
            durationSec = analysis.duration,
            bpm = bpm,
            beatsPerBar = 4,
            downbeats = downbeats,
            gridConfidence = analysis.beatConfidence.toFloat().coerceIn(0f, 1f),
            tempoStable = analysis.beatConfidence >= 0.45,
            phraseAnchor = 0,
            phraseLen = 8,
            sections = cleaned,
            sungBars = sung,
            vocalEnergy = vocalEnergy,
            firstVocalBar = first,
            lastVocalBar = last,
            pickupBeats = if (first == 1) 1f else 0f,
            startKind = when {
                speech -> StartKind.SPOKEN
                first == null -> StartKind.INTRO_INSTRUMENTAL
                first == 0 -> StartKind.COLD_VOCAL
                first == 1 -> StartKind.VOCAL_PICKUP
                else -> StartKind.INTRO_INSTRUMENTAL
            },
            endingKind = if (outroStart < n) EndingKind.OUTRO_INSTRUMENTAL else EndingKind.UNKNOWN,
            contentEndSec = analysis.contentEndTime.takeIf { it > 1.0 } ?: analysis.duration,
            labelConfidence = 0.5f,
            structMs = 0,
            partial = partial,
            coarse = analysis.beatConfidence < PhraseConfig.GRID_COARSE,
            activation = if (analysis.beatConfidence < 0.2 && analysis.bpm <= 0) PhraseConfig.ACTIVATION_FLOOR else 1f,
            speechLike = speech,
        )
    }

    private fun barIndex(time: Double, bar: Double, n: Int): Int {
        if (!time.isFinite() || time <= 0.0 || bar <= 0.0) return 0
        return (time / bar).toInt().coerceIn(0, n)
    }

    private fun mark(sung: BooleanArray, window: CurveWindow, bar: Double, n: Int, gate: Float) {
        if (window.values.isEmpty() || bar <= 0.0 || window.frameRate <= 0.0) return
        val sum = DoubleArray(n)
        val count = IntArray(n)
        val above = IntArray(n)
        val peak = FloatArray(n)
        for (i in window.values.indices) {
            val v = window.values[i]
            val t = window.startTime + i / window.frameRate
            val b = (t / bar).toInt()
            if (b !in 0 until n) continue
            sum[b] += v.toDouble()
            count[b]++
            if (v > gate) above[b]++
            if (v > peak[b]) peak[b] = v
        }
        for (b in 0 until n) {
            if (count[b] == 0) continue
            val mean = sum[b] / count[b]
            // Reject single-frame transients (snare/synth spike <= 0.30 with low bar mean).
            if (peak[b] > gate && (mean > 0.08 || above[b] >= 2 || peak[b] >= 0.35f)) {
                sung[b] = true
            }
        }
    }

    /**
     * Fill [sung] and [vocalEnergy] from whatever vocal evidence the analysis
     * carries.
     *
     * The two answer different questions and are deliberately built in one pass
     * so they cannot disagree about which bars were measured:
     *
     *  * [sung] is the presence question — is anyone singing in this bar. It
     *    keeps the gate and the transient rejection exactly as they were,
     *    because [CuePoints] cuts gaps with it and a false positive there
     *    produces a chop through a held note.
     *
     *  * [vocalEnergy] is the magnitude question — how much vocal is heard. No
     *    gate, no rejection: a bar averaging 0.30 is quiet singing and a bar
     *    averaging 0.90 is loud singing, and the planner needs to tell those
     *    apart. Kept on the analyzer's own calibrated scale (chorus 0.57-0.71,
     *    soft verse 0.2-0.4, instrumental 0.02-0.15) and deliberately NOT
     *    re-normalised to the track peak: a track whose loudest bar is a quiet
     *    verse would otherwise rescale its whispers to chorus level.
     *
     * With no measurement at all, [vocalEnergy] stays empty and
     * [SongMap.vocalEnergyAt] falls back to the boolean, which is exactly the
     * old behaviour.
     */
    private fun buildVocalBars(
        analysis: TrackAnalysis,
        bar: Double,
        n: Int,
        sung: BooleanArray,
    ): FloatArray {
        val raw = DoubleArray(n)
        var measured = false

        for (window in analysis.vocalWindows) {
            mark(sung, window, bar, n, VOCAL_BAR_GATE)
            accumulateCurve(window, bar, n, raw)
            measured = true
        }

        if (analysis.vocalWindows.isEmpty() && analysis.vocalActivityMask.isNotEmpty()) {
            val mask = analysis.vocalActivityMask
            val times = analysis.energyCurve
            if (times.size == mask.size && times.isNotEmpty()) {
                // Resample the mask onto bar centres through its own timestamps
                // rather than assuming they are evenly spaced.
                for (b in 0 until n) {
                    val t = (b + 0.5) * bar
                    val i = nearestSample(times, t)
                    val v = mask[i].coerceIn(0.0, 1.0)
                    if (v > VOCAL_BAR_GATE) sung[b] = true
                    raw[b] = v
                }
            } else {
                val span = analysis.duration
                if (span > 0.0) {
                    for (b in 0 until n) {
                        val t = (b + 0.5) * bar
                        val i = ((t / span) * mask.size).toInt().coerceIn(0, mask.lastIndex)
                        val v = mask[i].coerceIn(0.0, 1.0)
                        if (v > VOCAL_BAR_GATE) sung[b] = true
                        raw[b] = v
                    }
                }
            }
            measured = true
        }

        if (!measured) return FloatArray(0)
        val out = FloatArray(n)
        for (b in 0 until n) {
            out[b] = raw[b].coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    /** Bar-mean of one vocal window, ignoring frames that fall outside the track. */
    private fun accumulateCurve(window: CurveWindow, bar: Double, n: Int, into: DoubleArray) {
        if (window.values.isEmpty() || bar <= 0.0 || window.frameRate <= 0.0) return
        val sum = DoubleArray(n)
        val count = IntArray(n)
        for (i in window.values.indices) {
            val t = window.startTime + i / window.frameRate
            val b = (t / bar).toInt()
            if (b !in 0 until n) continue
            sum[b] += window.values[i].toDouble().coerceIn(0.0, 1.0)
            count[b]++
        }
        for (b in 0 until n) {
            if (count[b] == 0) continue
            val mean = sum[b] / count[b]
            // Overlapping windows are averaged rather than summed, so two passes
            // over the same bar cannot invent energy the audio does not have.
            into[b] = if (into[b] > 0.0) (into[b] + mean) / 2.0 else mean
        }
    }

    /** Index of the sample nearest [time] in an ascending time series. */
    private fun nearestSample(times: List<EnergySample>, time: Double): Int {
        var lo = 0
        var hi = times.size - 1
        if (time <= times[lo].time) return lo
        if (time >= times[hi].time) return hi
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (times[mid].time <= time) lo = mid else hi = mid
        }
        return if (kotlin.math.abs(times[lo].time - time) <= kotlin.math.abs(times[hi].time - time)) lo else hi
    }

    /**
     * Presence gate for [SongMap.sungBars] only.
     *
     * Sits just under [VOCAL_ACTIVE_THRESHOLD] on purpose. The analysis mask and
     * the vocal windows are already stored on the model's scale, where a soft
     * verse reads 0.2-0.4 and an instrumental break 0.02-0.15 (see the
     * [VOCAL_ACTIVE_THRESHOLD] doc). Moving this number up to the Wiener
     * separator's half-energy point would be a category error: the separator's
     * mask is a gain, this one is a detector, and they are different scales.
     * This gate is a detector and belongs where the detector's scale puts it.
     */
    internal const val VOCAL_BAR_GATE = 0.22f

    private fun phrase(
        start: Int, end: Int, fn: SectionFn, role: SectionRole, energy: Int, vocal: Float, loopable: Boolean = false,
    ) = PhraseSection(
        startBar = start, endBar = end.coerceAtLeast(start + 1), fn = fn, role = role, conf = 0.5f,
        group = 0, repeatIndex = 0, energy = energy / 10f, energyLevel = energy, energyRel = 0f,
        energySlope = 0f, vocal = vocal, bass = 0.4f, loopable = loopable,
    )
}
