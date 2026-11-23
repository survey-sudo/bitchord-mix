package com.music.bitchord.playback.smart.choreo

import com.music.bitchord.playback.smart.SongMap

/*
 * AUTOMIX-V2(E) <- D adapter. The ONLY file in the choreographer that reads
 * stream D's SongMap additions (contract: drops, builds, chorusRepeats,
 * phraseConfidence). A late rename in SongMap.kt is a fix here only.
 * `breakdowns` is deliberately not read: its element type is not fixed by the
 * contract, and the choreographer gets breakdowns from sections anyway.
 */
object StructureAdapterD {
    /** [base] (from the map's sections) with D's v2 fields laid over it; null when D found nothing. */
    fun overlay(map: SongMap, base: StructureView): StructureView? {
        val drops: List<Double> = map.drops.filter { it.isFinite() && it >= 0.0 }
        val builds: List<ClosedFloatingPointRange<Double>> = map.builds.filter { it.endInclusive > it.start }
        val chorus: List<Double> = map.chorusRepeats.flatten().filter { it.isFinite() && it >= 0.0 }
        val conf: Double = map.phraseConfidence
        if (drops.isEmpty() && builds.isEmpty() && chorus.isEmpty() && !(conf > 0.0)) return null
        return StructureView(
            sections = base.sections,
            drops = (drops + base.drops).distinct().sorted(),
            builds = if (builds.isNotEmpty()) builds else base.builds,
            chorusStarts = (chorus + base.chorusStarts).distinct().sorted(),
            phraseStarts = base.phraseStarts,
            phraseConfidence = if (conf.isFinite() && conf > 0.0) conf.coerceIn(0.0, 1.0) else base.phraseConfidence,
            source = "structure-v2",
        )
    }
}
