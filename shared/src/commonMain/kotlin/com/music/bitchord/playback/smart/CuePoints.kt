package com.music.bitchord.playback.smart

enum class ExitKind {
    OUTRO_START, OUTRO_PHRASE, LAST_CHORUS_END, POST_CHORUS_END,
    FADE_START, HARD_END, CONTENT_END, PRE_CHORUS_END,
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
    fun exits(map: SongMap): List<ExitCue> {
        val found = ArrayList<ExitCue>()
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
        val fade = map.sections.firstOrNull { it.fn == SectionFn.FADE_OUT }
        if (fade != null || map.endingKind == EndingKind.FADE_OUT) {
            val bar = fade?.startBar ?: (map.sections.lastOrNull()?.startBar ?: 0)
            val section = fade ?: map.sections.last()
            found.add(exit(map, ExitKind.FADE_START, bar, section))
        }
        val endBar = map.downbeats.indexOfLast { it <= map.contentEndSec + 1e-3 }.let { if (it < 0) map.sections.lastOrNull()?.endBar ?: 0 else it }
        val endSection = map.sectionAt((endBar - 1).coerceAtLeast(0)) ?: map.sections.lastOrNull()
        // A fade's usable end is the fade point, not the sample where the file hits silence.
        if (fade == null && endSection != null) {
            val kind = if (map.endingKind == EndingKind.HARD_END) ExitKind.HARD_END else ExitKind.CONTENT_END
            found.add(exit(map, kind, endBar, endSection))
        }
        val minBar = (map.downbeats.size * PhraseConfig.MIN_PLAY_FRACTION).toInt()
        return found
            .filter { it.releaseBar >= minBar || map.partial }
            .filter { passesExit(map, it) }
            .distinctBy { it.releaseBar to it.kind }
            .take(6)
    }

    fun entries(map: SongMap): List<EntryCue> {
        val found = ArrayList<EntryCue>()
        val landing = landingOf(map)
        found.add(entry(map, EntryKind.HEAD, 0, landing.first, landing.second))
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
        if (map.startKind == StartKind.ACAPELLA) {
            val beat = map.sections.firstOrNull { it.role == SectionRole.GROOVE || it.bass >= 0.25f }
            val land = beat?.startBar ?: landing.first
            found.add(entry(map, EntryKind.ACAPELLA, 0, land, SectionFn.INTRO))
        }
        if (map.startKind == StartKind.SPOKEN || map.speechLike) {
            found.add(entry(map, EntryKind.SPOKEN, 0, landing.first, landing.second))
        }
        return found
            .filter { passesEntry(map, it) }
            .distinctBy { it.startBar to it.landingBar to it.kind }
            .take(5)
    }

    private fun landingOf(map: SongMap): Pair<Int, SectionFn> {
        val vocal = map.firstVocalBar
        if (vocal != null) return vocal to (map.sectionAt(vocal)?.fn ?: SectionFn.VERSE)
        val event = map.sections.firstOrNull { it.fn == SectionFn.CHORUS || it.fn == SectionFn.DROP }
        if (event != null) return event.startBar to event.fn
        val intro = map.sections.firstOrNull { it.fn == SectionFn.INTRO }
        if (intro != null) return intro.endBar to SectionFn.INTRO
        return 0 to SectionFn.INTRO
    }

    private fun passesExit(map: SongMap, cue: ExitCue): Boolean {
        if (map.sungBars.getOrElse(cue.releaseBar) { false }) return false
        if (map.chorusCount >= 2) {
            val last = map.sections.lastOrNull { it.fn == SectionFn.CHORUS && (map.lastVocalBar == null || it.startBar <= map.lastVocalBar) }
            if (last != null && cue.releaseBar < last.endBar && cue.kind != ExitKind.PRE_CHORUS_END) return false
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
            (map.barTime(land) - map.pickupBeats * beat).coerceAtLeast(0.0)
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
