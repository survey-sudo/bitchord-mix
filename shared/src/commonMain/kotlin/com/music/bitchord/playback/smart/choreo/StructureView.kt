package com.music.bitchord.playback.smart.choreo

import com.music.bitchord.playback.smart.Section
import com.music.bitchord.playback.smart.SectionFn
import com.music.bitchord.playback.smart.SectionKind
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.labelSections

/**
 * One track's form as the choreographer reads it, in that track's seconds.
 * Built from stream D's SongMap additions when present ([StructureAdapterD]),
 * else from the existing SongMap sections, else from [labelSections] on the
 * stored analysis. Every list may be empty; nothing here is required.
 */
class StructureView(
    val sections: List<Section>,
    val drops: List<Double>,
    val builds: List<ClosedFloatingPointRange<Double>>,
    val chorusStarts: List<Double>,
    val phraseStarts: List<Double>,
    /** 0..1, how far phrase boundaries can be trusted. */
    val phraseConfidence: Double,
    /** Where stream D's structure v2 contributed, for the log. */
    val source: String,
) {
    fun kindAt(t: Double): SectionKind? = sections.firstOrNull { t >= it.start && t < it.end }?.kind

    /** Nearest drop to [t] within [tol] seconds, or null. */
    fun dropNear(t: Double, tol: Double): Double? = drops.filter { kotlin.math.abs(it - t) <= tol }.minByOrNull { kotlin.math.abs(it - t) }

    /** True when [t] sits within [tol] of a section start, phrase start, drop or chorus start. */
    fun boundaryNear(t: Double, tol: Double): Boolean =
        sections.any { kotlin.math.abs(it.start - t) <= tol } ||
            phraseStarts.any { kotlin.math.abs(it - t) <= tol } ||
            drops.any { kotlin.math.abs(it - t) <= tol } ||
            chorusStarts.any { kotlin.math.abs(it - t) <= tol }

    /** End of the last chorus that ends at or before [t], or null. */
    fun chorusEndBefore(t: Double): Double? =
        sections.filter { it.kind == SectionKind.CHORUS && it.end <= t + 1e-6 }.maxOfOrNull { it.end }

    companion object {
        val EMPTY = StructureView(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0.0, "none")

        /** From stored analysis only (always available). */
        fun fromAnalysis(analysis: TrackAnalysis?): StructureView {
            if (analysis == null) return EMPTY
            val sections = runCatching { labelSections(analysis) }.getOrDefault(emptyList())
            val drops = sections.filter { it.kind == SectionKind.DROP }.map { it.start }
            val builds = sections.filter { it.kind == SectionKind.BUILD && it.end > it.start }.map { it.start..it.end }
            val chorus = sections.filter { it.kind == SectionKind.CHORUS }.map { it.start }
            val phrases = analysis.phraseBoundaries.filter { it.isFinite() }
            val conf = when {
                phrases.size >= 4 && analysis.beatConfidence >= 0.6 -> 0.6
                phrases.isNotEmpty() -> 0.4
                else -> 0.2
            }
            return StructureView(sections, drops, builds, chorus, phrases, conf, "sections")
        }

        /** From an existing (pre-D) SongMap: its phrase sections in bars. */
        fun fromSongMapSections(map: SongMap): StructureView {
            val sections = ArrayList<Section>()
            for (s in map.sections) {
                val kind = kindOf(s.fn) ?: continue
                val start = map.barTime(s.startBar)
                val end = map.barTime(s.endBar)
                if (end > start) sections += Section(kind, start, end)
            }
            val drops = map.sections.filter { it.fn == SectionFn.DROP }.map { map.barTime(it.startBar) }
            val builds = map.sections.filter { it.fn == SectionFn.BUILD && it.endBar > it.startBar }
                .map { map.barTime(it.startBar)..map.barTime(it.endBar) }
            val chorus = map.sections.filter { it.fn == SectionFn.CHORUS }.map { map.barTime(it.startBar) }
            val phrases = ArrayList<Double>()
            val bars = map.downbeats.size
            for (b in 0 until bars) if (map.isPhraseStart(b)) phrases += map.barTime(b)
            val conf = (map.gridConfidence.toDouble() * 0.5 + map.labelConfidence.toDouble() * 0.5).coerceIn(0.0, 1.0)
            return StructureView(sections, drops, builds, chorus, phrases, conf, "songmap")
        }

        /** Best available view: D v2 fields > SongMap sections > analysis sections. */
        fun best(map: SongMap?, analysis: TrackAnalysis?): StructureView {
            val base = fromAnalysis(analysis)
            if (map == null) return base
            val fromMap = runCatching { fromSongMapSections(map) }.getOrNull() ?: return base
            val v2 = runCatching { StructureAdapterD.overlay(map, fromMap) }.getOrNull()
            val chosen = v2 ?: fromMap
            return if (chosen.sections.isEmpty()) {
                StructureView(base.sections, chosen.drops.ifEmpty { base.drops }, chosen.builds.ifEmpty { base.builds },
                    chosen.chorusStarts.ifEmpty { base.chorusStarts }, chosen.phraseStarts.ifEmpty { base.phraseStarts },
                    maxOf(chosen.phraseConfidence, base.phraseConfidence), chosen.source)
            } else chosen
        }

        private fun kindOf(fn: SectionFn): SectionKind? = when (fn) {
            SectionFn.INTRO -> SectionKind.INTRO
            SectionFn.VERSE -> SectionKind.VERSE
            SectionFn.PRE_CHORUS -> SectionKind.PRE_CHORUS
            SectionFn.CHORUS, SectionFn.POST_CHORUS -> SectionKind.CHORUS
            SectionFn.BRIDGE -> SectionKind.BRIDGE
            SectionFn.BREAK, SectionFn.INSTRUMENTAL -> SectionKind.INST
            SectionFn.BUILD -> SectionKind.BUILD
            SectionFn.DROP -> SectionKind.DROP
            SectionFn.OUTRO, SectionFn.FADE_OUT -> SectionKind.OUTRO
            else -> null
        }
    }
}
