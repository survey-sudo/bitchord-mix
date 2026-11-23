package com.music.bitchord.playback.smart

enum class ExitKind {
    OUTRO_START, OUTRO_PHRASE, LAST_CHORUS_END, POST_CHORUS_END,
    FADE_START, HARD_END, CONTENT_END, PRE_CHORUS_END,
    // section-boundary exits. VERSE_END exits right after a verse;
    // BRIDGE_END exits at the end of a bridge (good for echo-outs).
    VERSE_END, BRIDGE_END,
}

enum class EntryKind { HEAD, INTRO_TRIM, BUILD, ACAPELLA, SPOKEN }

data class ExitCue(
    val kind: ExitKind,
    val releaseBar: Int,
    val releaseSec: Double,
    val roomBars: Int,
    val vocalRoomBars: Int,
    val tailBars: Int,
    val sectionFn: SectionFn,
    val role: SectionRole,
    val energyAtRelease: Float,
    val energyLevel: Int,
    val loopableRoom: Boolean,
    val cutsSungBars: Int,
    val cutsBars: Int,
)

data class EntryCue(
    val kind: EntryKind,
    val startBar: Int,
    val startSec: Double,
    val landingBar: Int,
    val landingFn: SectionFn,
    val landingRole: SectionRole,
    val leadInBars: Int,
    val leadInVocal: Float,
    val leadInLoopable: Boolean,
    val energyAtLanding: Float,
    val energyLevel: Int,
    val trimmedBars: Int,
    val pickupBeats: Float,
)

/**
 * Cues from one track's own [SongMap]. No other track is consulted.
 */
object CuePoints {
    fun exits(
        map: SongMap,
        playableOutDurationSec: Double = map.durationSec,
        analysis: TrackAnalysis? = null,
    ): List<ExitCue> {
        val effectiveDuration = if (playableOutDurationSec > 1.0) minOf(map.durationSec, playableOutDurationSec) else map.durationSec
        val musicEnd = minOf(
            effectiveDuration,
            (analysis?.contentEndTime?.takeIf { it > 1.0 } ?: map.contentEndSec).takeIf { it > 1.0 } ?: effectiveDuration,
        )
        val minSec = if (effectiveDuration > 90.0) {
            maxOf(effectiveDuration * PhraseConfig.MIN_PLAY_FRACTION, musicEnd - 30.0)
                .coerceAtMost((musicEnd - 8.0).coerceAtLeast(0.0))
        } else {
            minOf(
                effectiveDuration * PhraseConfig.MIN_PLAY_FRACTION,
                maxOf(effectiveDuration * 0.65, musicEnd - 40.0),
            )
        }
        val earlyOutroMinSec = effectiveDuration * 0.68
        val coveredEndSec = map.barTime((map.sections.lastOrNull()?.endBar ?: map.downbeats.size).coerceAtLeast(0))
        val mapCoversTail = !map.partial || coveredEndSec >= effectiveDuration * PhraseConfig.MIN_PLAY_FRACTION - 1.0

        val found = ArrayList<ExitCue>()
        if (mapCoversTail) {
            val outro = map.sections.firstOrNull { it.fn == SectionFn.OUTRO || it.fn == SectionFn.FADE_OUT }
            val lastChorus = map.sections.lastOrNull { it.fn == SectionFn.CHORUS }
            if (outro != null && outro.fn == SectionFn.OUTRO) {
                found.add(exit(map, ExitKind.OUTRO_START, outro.startBar, outro))
                var phrases = 0
                var bar = outro.startBar
                while (bar < outro.endBar && phrases < 3) {
                    if (bar > outro.startBar && map.isPhraseDownbeat(bar)) {
                        found.add(exit(map, ExitKind.OUTRO_PHRASE, bar, outro))
                        phrases++
                    }
                    bar++
                }
                if (outro.endBar > outro.startBar) found.add(exit(map, ExitKind.OUTRO_PHRASE, outro.endBar, outro))
            }
            if (lastChorus != null) {
                found.add(exit(map, ExitKind.LAST_CHORUS_END, lastChorus.endBar, lastChorus))
                val after = map.sections.firstOrNull {
                    it.startBar == lastChorus.endBar && (it.fn == SectionFn.POST_CHORUS || it.fn == SectionFn.DROP)
                }
                if (after != null) found.add(exit(map, ExitKind.POST_CHORUS_END, after.endBar, after))
                val pre = map.sections.lastOrNull { it.fn == SectionFn.PRE_CHORUS || it.fn == SectionFn.BUILD }
                if (pre != null && map.chorusCount >= PhraseConfig.LIFT_MIN_CHORUSES) {
                    found.add(exit(map, ExitKind.PRE_CHORUS_END, pre.endBar, pre))
                }
            }
            // VERSE_END / BRIDGE_END exits. Each VERSE end is a
            // musical seam (a verse boundary, never mid-verse); each BRIDGE
            // end is an echo-out candidate. The minSec + passesExit filters
            // below keep only the tail-plausible ones.
            for (verse in map.sections.filter { it.fn == SectionFn.VERSE }) {
                found.add(exit(map, ExitKind.VERSE_END, verse.endBar, verse))
            }
            for (bridge in map.sections.filter { it.fn == SectionFn.BRIDGE }) {
                found.add(exit(map, ExitKind.BRIDGE_END, bridge.endBar, bridge))
            }
            val fade = map.sections.firstOrNull { it.fn == SectionFn.FADE_OUT }
            if (fade != null || map.endingKind == EndingKind.FADE_OUT) {
                val bar = fade?.startBar ?: (map.sections.lastOrNull()?.startBar ?: 0)
                val section = fade ?: map.sections.last()
                found.add(exit(map, ExitKind.FADE_START, bar, section))
            }
            val endCap = if (playableOutDurationSec > 1.0 && playableOutDurationSec < map.durationSec - 0.5) effectiveDuration - 0.5 else musicEnd
            val endBar = map.downbeats.indexOfLast { it <= endCap + 1e-3 }
                .let { if (it < 0) map.sections.lastOrNull()?.endBar ?: 0 else it }
            val endSection = map.sectionAt((endBar - 1).coerceAtLeast(0)) ?: map.sections.lastOrNull()
            // A fade's usable end is the fade point, not the sample where the file hits silence.
            if (fade == null && endSection != null) {
                val kind = if (map.endingKind == EndingKind.HARD_END) ExitKind.HARD_END else ExitKind.CONTENT_END
                found.add(exit(map, kind, endBar, endSection))
            }
        }

        val renditionClamped = playableOutDurationSec > 1.0 && playableOutDurationSec < map.durationSec - 0.5
        val maxReleaseSec = if (renditionClamped) effectiveDuration - 0.5 + 1e-6 else effectiveDuration + 1e-6
        val filtered = found
            .filter { it.releaseSec <= maxReleaseSec }
            .filter { cue ->
                cue.releaseSec >= minSec - 1e-6 ||
                    (mapCoversTail && cue.releaseSec >= earlyOutroMinSec - 1e-6 &&
                        (cue.kind == ExitKind.OUTRO_START || cue.kind == ExitKind.OUTRO_PHRASE || cue.kind == ExitKind.FADE_START) &&
                        isGenuineQuietOutro(map, cue.releaseBar))
            }
            .filter { passesExit(map, it) }
            .distinctBy { it.releaseBar to it.kind }
            .take(10) // Headroom so VERSE_END/BRIDGE_END exits are never crowded out.

        if (filtered.isNotEmpty()) return filtered
        val synthesized = synthesizeTailExits(map, effectiveDuration, musicEnd, minSec, analysis)
        if (synthesized.isNotEmpty()) return synthesized
        if (effectiveDuration > 1.0) {
            val sec = minOf(musicEnd, effectiveDuration - 0.5).coerceAtLeast(0.0)
            val barSec = map.barSec.takeIf { it > 0.2 }
                ?: analysis?.beatInterval?.takeIf { it > 0.05 }?.let { it * 4.0 }
                ?: analysis?.bpm?.takeIf { it > 1.0 }?.let { 240.0 / it }
                ?: 2.0
            val bar = kotlin.math.round(sec / barSec).toInt().coerceAtLeast(1)
            val section = map.sectionAt((bar - 1).coerceAtLeast(0)) ?: map.sections.lastOrNull()
            return listOf(
                ExitCue(
                    kind = ExitKind.CONTENT_END,
                    releaseBar = bar,
                    releaseSec = sec,
                    roomBars = 8,
                    vocalRoomBars = 0,
                    tailBars = 0,
                    sectionFn = section?.fn ?: SectionFn.OUTRO,
                    role = section?.role ?: SectionRole.GROOVE,
                    energyAtRelease = section?.energy ?: 0.4f,
                    energyLevel = section?.energyLevel ?: 4,
                    loopableRoom = false,
                    cutsSungBars = 0,
                    cutsBars = 0,
                ),
            )
        }
        return emptyList()
    }

    private fun isGenuineQuietOutro(map: SongMap, startBar: Int): Boolean {
        val tailSections = map.sections.filter { it.endBar > startBar }
        if (tailSections.isEmpty()) return false
        val allOutroLow = tailSections.all {
            (it.fn == SectionFn.OUTRO || it.fn == SectionFn.FADE_OUT || it.fn == SectionFn.SILENCE) &&
                it.energyLevel <= 3
        }
        if (!allOutroLow) return false
        for (b in startBar until map.sungBars.size) {
            if (map.sungBars[b]) return false
        }
        return true
    }

    private fun synthesizeTailExits(
        map: SongMap,
        effectiveDuration: Double,
        musicEnd: Double,
        minSec: Double,
        analysis: TrackAnalysis?,
    ): List<ExitCue> {
        val barSec = map.barSec.takeIf { it > 0.2 }
            ?: analysis?.beatInterval?.takeIf { it > 0.05 }?.let { it * 4.0 }
            ?: analysis?.bpm?.takeIf { it > 1.0 }?.let { 240.0 / it }
            ?: return emptyList()
        val maxSec = (effectiveDuration - 0.5).coerceAtLeast(0.0)
        if (maxSec < minSec) return emptyList()
        val tailDownbeats = analysis?.tailBeats?.downbeats?.filter { it.isFinite() && it >= 0.0 }.orEmpty()
        val allDownbeats = (map.downbeats.toList() + tailDownbeats + (analysis?.downbeats ?: emptyList()))
            .filter { it.isFinite() && it >= 0.0 && it <= maxSec }
            .distinct()
            .sorted()

        fun snapToPhraseBar(sec: Double): Pair<Int, Double> {
            val snappedSec = if (allDownbeats.any { it in minSec..maxSec }) {
                allDownbeats.filter { it in minSec..maxSec }.minByOrNull { kotlin.math.abs(it - sec) } ?: sec
            } else {
                val origin = allDownbeats.lastOrNull() ?: 0.0
                val steps = kotlin.math.round((sec - origin) / barSec)
                (origin + steps * barSec).coerceIn(minSec, maxSec)
            }
            val rawBar = kotlin.math.round(snappedSec / barSec).toInt().coerceAtLeast(1)
            val localAnchor = map.anchors.lastOrNull { it <= rawBar } ?: map.phraseAnchor
            val delta = rawBar - localAnchor
            val rem = ((delta % 4) + 4) % 4
            var alignedBar = if (rem <= 2) rawBar - rem else rawBar + (4 - rem)
            var alignedSec = if (alignedBar < map.downbeats.size && map.downbeats[alignedBar] <= maxSec) {
                map.downbeats[alignedBar]
            } else {
                alignedBar * barSec
            }
            if (alignedSec > maxSec) {
                alignedBar = (alignedBar - 4).coerceAtLeast(localAnchor)
                alignedSec = if (alignedBar < map.downbeats.size) map.downbeats[alignedBar] else alignedBar * barSec
            }
            if (alignedSec < minSec - 1e-6 && alignedBar + 4 >= 0) {
                val nextBar = alignedBar + 4
                val nextSec = if (nextBar < map.downbeats.size) map.downbeats[nextBar] else nextBar * barSec
                if (nextSec <= maxSec) {
                    alignedBar = nextBar
                    alignedSec = nextSec
                }
            }
            return alignedBar to alignedSec.coerceIn(0.0, maxSec)
        }

        val targets = ArrayList<Pair<ExitKind, Double>>()
        analysis?.outroStartTime?.takeIf { it in minSec..maxSec }?.let { targets.add(ExitKind.OUTRO_START to it) }
        analysis?.mixOutTime?.takeIf { it in minSec..maxSec }?.let { targets.add(ExitKind.OUTRO_PHRASE to it) }
        for (barsBack in intArrayOf(4, 8, 16)) {
            val t = (musicEnd - barsBack * barSec).coerceIn(minSec, maxSec)
            targets.add(ExitKind.OUTRO_PHRASE to t)
        }
        targets.add(ExitKind.OUTRO_PHRASE to minOf(musicEnd, maxSec))

        val out = ArrayList<ExitCue>()
        for ((kind, targetSec) in targets) {
            val (bar, sec) = snapToPhraseBar(targetSec)
            if (sec > maxSec + 1e-6 || sec < minSec - 1e-6) continue
            if (bar < map.sungBars.size && map.sungBars[bar]) continue
            if (!aligned(map, bar)) continue
            val tailBars = kotlin.math.round((musicEnd - sec) / barSec).toInt().coerceAtLeast(0)
            val measuredRoom = if (bar <= map.sungBars.size) {
                var r = 0
                var b = bar - 1
                while (b >= 0 && !map.sungBars[b]) { r++; b-- }
                r
            } else {
                // Partial map didn't decode the tail: assume 8-bar outro phrase room unless vocal windows say otherwise.
                8
            }
            val room = if (measuredRoom >= 4) measuredRoom else 8
            val section = map.sectionAt((bar - 1).coerceAtLeast(0)) ?: map.sections.lastOrNull()
            out.add(
                ExitCue(
                    kind = kind,
                    releaseBar = bar,
                    releaseSec = sec,
                    roomBars = room,
                    vocalRoomBars = 0,
                    tailBars = tailBars,
                    sectionFn = section?.fn ?: SectionFn.OUTRO,
                    role = section?.role ?: SectionRole.GROOVE,
                    energyAtRelease = section?.energy ?: 0.4f,
                    energyLevel = section?.energyLevel ?: 4,
                    loopableRoom = room >= 4,
                    cutsSungBars = 0,
                    cutsBars = tailBars,
                ),
            )
        }
        if (out.isNotEmpty()) return out.distinctBy { it.releaseBar }.take(6)

        val (bar, sec) = snapToPhraseBar(minOf(musicEnd, maxSec))
        if (sec > maxSec + 1e-6) return emptyList()
        val tailBars = kotlin.math.round((musicEnd - sec) / barSec).toInt().coerceAtLeast(0)
        val section = map.sectionAt((bar - 1).coerceAtLeast(0)) ?: map.sections.lastOrNull()
        var vocalRoom = 0
        var vb = bar - 1
        while (vb >= 0 && map.sungBars.getOrElse(vb) { false }) {
            vocalRoom++
            vb--
        }
        var cutsSung = 0
        for (i in bar until map.sungBars.size) {
            if (map.sungBars[i]) cutsSung++
        }
        val room = if (bar <= map.sungBars.size) {
            var r = 0
            var b = bar - 1
            while (b >= 0 && !map.sungBars[b]) { r++; b-- }
            if (r >= 4) r else 8
        } else {
            8
        }
        return listOf(
            ExitCue(
                kind = ExitKind.CONTENT_END,
                releaseBar = bar,
                releaseSec = sec,
                roomBars = room,
                vocalRoomBars = vocalRoom,
                tailBars = tailBars,
                sectionFn = section?.fn ?: SectionFn.OUTRO,
                role = section?.role ?: SectionRole.GROOVE,
                energyAtRelease = section?.energy ?: 0.4f,
                energyLevel = section?.energyLevel ?: 4,
                loopableRoom = false,
                cutsSungBars = cutsSung,
                cutsBars = tailBars,
            ),
        )
    }

    fun entries(map: SongMap, analysis: TrackAnalysis? = null): List<EntryCue> {
        val found = ArrayList<EntryCue>()
        var headStartBar = map.sections.firstOrNull { it.fn != SectionFn.SILENCE }?.startBar ?: 0
        if (analysis != null) {
            val audibleFloor = maxOf(analysis.audibleStartTime?.takeIf { it.isFinite() && it > 0.05 } ?: 0.0, LoopMix.firstAudibleTime(analysis))
            if (audibleFloor > 0.25) {
                val totalBars = (map.sections.lastOrNull()?.endBar ?: map.downbeats.size)
                var b = headStartBar
                while (b < totalBars && map.barTime(b) < audibleFloor - 0.15) b++
                if (b < totalBars - 4) headStartBar = b
            }
        }
        val landing = landingOf(map)
        found.add(entry(map, EntryKind.HEAD, headStartBar, landing.first.coerceAtLeast(headStartBar), landing.second))
        val intro = map.sections.firstOrNull { it.fn == SectionFn.INTRO || it.fn == SectionFn.BUILD }
        if (intro != null && intro.endBar - intro.startBar > 16 && intro.vocal < 0.2f) {
            var bar = intro.startBar
            while (bar < intro.endBar) {
                val lead = landing.first - bar
                if (bar > 0 && map.isPhraseDownbeat(bar) && lead >= 8 && bar <= intro.endBar / 2) {
                    found.add(entry(map, EntryKind.INTRO_TRIM, bar, landing.first, landing.second))
                }
                bar++
            }
        }
        if (map.startKind == StartKind.INTRO_BUILD) {
            val build = map.sections.firstOrNull { it.fn == SectionFn.BUILD }
            if (build != null) found.add(entry(map, EntryKind.BUILD, build.startBar, build.endBar, map.sectionAt(build.endBar)?.fn ?: SectionFn.CHORUS))
        }
        // structure-aware: a labelled head buildup/pre-chorus is its own
        // entry cue whatever the start kind, so the planner can play B's
        // buildup under A's outro and land the peak at handover.
        val headBuild = StructureMix.headBuildup(map)
        if (headBuild != null && found.none { it.startBar == headBuild.startBar && it.kind == EntryKind.BUILD }) {
            found.add(entry(map, EntryKind.BUILD, headBuild.startBar, headBuild.endBar,
                map.sectionAt(headBuild.endBar)?.fn ?: SectionFn.CHORUS))
        }
        if (map.startKind == StartKind.ACAPELLA) {
            val beat = map.sections.firstOrNull { it.role == SectionRole.GROOVE || it.bass >= 0.25f }
            val land = beat?.startBar ?: landing.first
            found.add(entry(map, EntryKind.ACAPELLA, 0, land, SectionFn.INTRO))
        }
        if (map.startKind == StartKind.SPOKEN || map.speechLike) {
            found.add(entry(map, EntryKind.SPOKEN, 0, landing.first, landing.second))
        }
        val valid = found
            .filter { passesEntry(map, it) }
            .distinctBy { it.startBar to it.landingBar to it.kind }
            .take(5)
        if (valid.isNotEmpty()) return valid
        if (map.downbeats.isEmpty()) return emptyList()
        // bkmasterplayer::bar_entry fallback when strict section filtering dropped all entries.
        val firstDown = maxOf(map.downbeats.indexOfFirst { it.isFinite() && it >= 0.0 }, headStartBar).coerceAtLeast(0)
        val totalBars = map.sections.lastOrNull()?.endBar ?: map.downbeats.size
        val introLand = if (totalBars >= 16 && (0 until 8).none { map.sungBars.getOrElse(it) { false } }) {
            8
        } else if (totalBars >= 8 && (0 until 4).none { map.sungBars.getOrElse(it) { false } }) {
            4
        } else {
            firstDown
        }
        return listOf(entry(map, EntryKind.HEAD, firstDown, introLand.coerceAtLeast(firstDown), SectionFn.INTRO))
    }

    private fun landingOf(map: SongMap): Pair<Int, SectionFn> {
        val vocal = map.firstVocalBar
        if (vocal != null) return vocal to (map.sectionAt(vocal)?.fn ?: SectionFn.VERSE)
        val event = map.sections.firstOrNull { it.fn == SectionFn.CHORUS || it.fn == SectionFn.DROP }
        if (event != null) return event.startBar to event.fn
        val intro = map.sections.firstOrNull { it.fn == SectionFn.INTRO }
        if (intro != null) return intro.endBar to SectionFn.INTRO
        val totalBars = map.sections.lastOrNull()?.endBar ?: map.downbeats.size
        if (totalBars >= 16 && (0 until 8).none { map.sungBars.getOrElse(it) { false } }) {
            return 8 to SectionFn.INTRO
        }
        if (totalBars >= 8 && (0 until 4).none { map.sungBars.getOrElse(it) { false } }) {
            return 4 to SectionFn.INTRO
        }
        return 0 to SectionFn.INTRO
    }

    private fun passesExit(map: SongMap, cue: ExitCue): Boolean {
        // VERSE_END/BRIDGE_END are scored in PhrasePlanner.
        // The release bar is the first bar of the NEXT section, which may
        // legitimately be sung (verse -> sung bridge), so the sung-bar
        // veto would discard every verse followed by sung material.
        // Offer them; the scorer prices the radio-edit alternative.
        if (cue.kind != ExitKind.VERSE_END && cue.kind != ExitKind.BRIDGE_END &&
            map.sungBars.getOrElse(cue.releaseBar) { false }) return false
        if (map.chorusCount >= 2) {
            val last = map.sections.lastOrNull { it.fn == SectionFn.CHORUS && (map.lastVocalBar == null || it.startBar <= map.lastVocalBar) }
            // VERSE_END/BRIDGE_END are scored in PhrasePlanner (not
            // hard-vetoed here), so they reach the scorer; the chorus
            // discard penalty there prices the radio-edit alternative.
            if (last != null && cue.releaseBar < last.endBar &&
                cue.kind != ExitKind.PRE_CHORUS_END &&
                cue.kind != ExitKind.VERSE_END && cue.kind != ExitKind.BRIDGE_END
            ) return false
        }
        val section = map.sectionAt((cue.releaseBar - 1).coerceAtLeast(0))
        if (section?.fn == SectionFn.VERSE && cue.releaseBar in (section.startBar + 1) until section.endBar) return false
        if (!aligned(map, cue.releaseBar) && cue.kind != ExitKind.CONTENT_END) return false
        return true
    }

    private fun passesEntry(map: SongMap, cue: EntryCue): Boolean {
        val vocal = map.firstVocalBar
        if (!PhraseConfig.ALLOW_RADIO_EDIT && vocal != null) {
            val pickupBars = kotlin.math.ceil(map.pickupBeats / map.beatsPerBar.coerceAtLeast(1).toDouble()).toInt()
            if (cue.startBar > vocal - pickupBars) return false
            if (cue.landingBar > vocal) return false
        }
        val cold = map.startKind == StartKind.COLD_VOCAL || map.startKind == StartKind.CHORUS_FIRST
        if (map.sungBars.getOrElse(cue.startBar) { false } && cue.kind != EntryKind.ACAPELLA && !cold) return false
        return true
    }

    private fun aligned(map: SongMap, bar: Int): Boolean {
        if (map.isPhraseDownbeat(bar)) return true
        return map.anchors.any { it == bar }
    }

    private fun exit(map: SongMap, kind: ExitKind, bar: Int, section: PhraseSection): ExitCue {
        var room = 0
        var vocalRoom = 0
        var b = bar - 1
        while (b >= 0 && !map.sungBars.getOrElse(b) { false }) {
            room++
            b--
        }
        b = bar - 1
        while (b >= 0 && map.sungBars.getOrElse(b) { false }) {
            vocalRoom++
            b--
        }
        val tail = (section.endBar - bar).coerceAtLeast(0)
        var cuts = 0
        var cutsSung = 0
        for (i in bar until map.sungBars.size) {
            cuts++
            if (map.sungBars[i]) cutsSung++
        }
        return ExitCue(
            kind = kind,
            releaseBar = bar,
            releaseSec = map.barTime(bar),
            roomBars = room,
            vocalRoomBars = vocalRoom,
            tailBars = tail,
            sectionFn = section.fn,
            role = section.role,
            energyAtRelease = section.energy,
            energyLevel = section.energyLevel,
            loopableRoom = section.loopable && room >= 1,
            cutsSungBars = cutsSung,
            cutsBars = cuts,
        )
    }

    private fun entry(map: SongMap, kind: EntryKind, start: Int, landing: Int, fn: SectionFn): EntryCue {
        val land = landing.coerceAtLeast(start)
        var vocal = 0
        for (b in start until land) if (map.sungBars.getOrElse(b) { false }) vocal++
        val len = (land - start).coerceAtLeast(1)
        val section = map.sectionAt(land) ?: map.sectionAt((land - 1).coerceAtLeast(0))
        val beat = if (map.bpm > 1) 60.0 / map.bpm else 0.5
        val startSec = if (start == 0) {
            if (land == 0 || map.startKind == StartKind.COLD_VOCAL) {
                (map.barTime(0) - map.pickupBeats * beat).coerceAtLeast(0.0)
            } else {
                map.barTime(0)
            }
        } else {
            map.barTime(start)
        }
        return EntryCue(
            kind = kind,
            startBar = start,
            startSec = startSec,
            landingBar = land,
            landingFn = fn,
            landingRole = section?.role ?: SectionRole.GROOVE,
            leadInBars = land - start,
            leadInVocal = vocal.toFloat() / len,
            leadInLoopable = map.sections.any { it.startBar >= start && it.endBar <= land && it.loopable },
            energyAtLanding = section?.energy ?: 0.5f,
            energyLevel = section?.energyLevel ?: 5,
            trimmedBars = start,
            pickupBeats = map.pickupBeats,
        )
    }
}
