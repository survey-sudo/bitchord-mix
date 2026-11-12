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
        for (window in analysis.vocalWindows) mark(sung, window, bar, n, 0.22f)
        if (analysis.vocalWindows.isEmpty() && analysis.vocalActivityMask.isNotEmpty()) {
            val mask = analysis.vocalActivityMask
            for (b in 0 until n) {
                val t = b * bar
                val i = ((t / analysis.duration) * mask.size).toInt().coerceIn(0, mask.lastIndex)
                sung[b] = mask[i] > 0.22
            }
        }
        val first = sung.indexOfFirst { it }.let { if (it < 0) null else it }
        val last = sung.indexOfLast { it }.let { if (it < 0) null else it }
        val introEnd = barIndex(analysis.introEndTime, bar, n)
        val outroStart = barIndex(analysis.outroStartTime, bar, n).coerceAtLeast(introEnd)
        val sections = ArrayList<PhraseSection>()
        if (introEnd > 0) sections.add(phrase(0, introEnd, SectionFn.INTRO, SectionRole.GROOVE, 4, vocal = 0f))
        val bodyEnd = if (outroStart > introEnd) outroStart else n
        if (first != null && first < bodyEnd) {
            sections.add(phrase(introEnd, first.coerceAtLeast(introEnd + 1), SectionFn.INTRO, SectionRole.GROOVE, 4, 0f))
            sections.add(phrase(first, (last ?: first) + 1, SectionFn.VERSE, SectionRole.NARRATIVE, 6, 0.7f))
        } else if (bodyEnd > introEnd) {
            sections.add(phrase(introEnd, bodyEnd, SectionFn.INSTRUMENTAL, SectionRole.GROOVE, 5, 0f))
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
        for (i in window.values.indices) {
            if (window.values[i] <= gate) continue
            val t = window.startTime + i / window.frameRate
            val b = (t / bar).toInt()
            if (b in 0 until n) sung[b] = true
        }
    }

    private fun phrase(
        start: Int, end: Int, fn: SectionFn, role: SectionRole, energy: Int, vocal: Float, loopable: Boolean = false,
    ) = PhraseSection(
        startBar = start, endBar = end.coerceAtLeast(start + 1), fn = fn, role = role, conf = 0.5f,
        group = 0, repeatIndex = 0, energy = energy / 10f, energyLevel = energy, energyRel = 0f,
        energySlope = 0f, vocal = vocal, bass = 0.4f, loopable = loopable,
    )
}
