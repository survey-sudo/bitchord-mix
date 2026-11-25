package com.music.bitchord.djmix.analysis.fixtures

import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import kotlinx.serialization.json.Json
import java.io.File

object FixtureWriter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun write(analysis: TrackAnalysisV3, out: File) {
        out.parentFile?.mkdirs()
        out.writeText(json.encodeToString(TrackAnalysisV3.serializer(), analysis))
    }

    fun toJson(analysis: TrackAnalysisV3): String =
        json.encodeToString(TrackAnalysisV3.serializer(), analysis)
}
