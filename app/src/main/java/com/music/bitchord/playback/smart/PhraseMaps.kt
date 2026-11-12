package com.music.bitchord.playback.smart

import java.util.concurrent.ConcurrentHashMap

/**
 * Song maps built off the analysis thread. The planner reads them and falls
 * back to [SongMaps.fromAnalysis] when a track has not been through the PCM pass.
 */
object PhraseMaps {
    private val maps = ConcurrentHashMap<String, SongMap>()

    fun put(trackId: String, map: SongMap) {
        if (trackId.isBlank()) return
        if (maps.size > 24) maps.clear()
        maps[trackId] = map
    }

    fun get(trackId: String): SongMap? = maps[trackId]
}
