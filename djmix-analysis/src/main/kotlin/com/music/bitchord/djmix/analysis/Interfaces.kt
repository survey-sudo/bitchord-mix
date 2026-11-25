package com.music.bitchord.djmix.analysis

import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3

interface ThermalPolicy {
    fun isCool(): Boolean
}

val NoOpThermalPolicy: ThermalPolicy = object : ThermalPolicy { override fun isCool(): Boolean = true }

interface StemSeparator {
    fun separate(pcm: FloatArray, sampleRate: Double): Map<String, FloatArray>
}

interface AnalysisStore {
    fun save(trackId: String, analysis: TrackAnalysisV3)
    fun load(trackId: String): TrackAnalysisV3?
}

interface LogSink {
    fun debug(msg: String) {}
    fun info(msg: String) {}
    fun warn(msg: String, t: Throwable? = null) {}
}

object StdLogSink : LogSink {
    override fun debug(msg: String) { println("DEBUG: $msg") }
    override fun info(msg: String) { println("INFO: $msg") }
    override fun warn(msg: String, t: Throwable?) { println("WARN: $msg${t?.let { " " + it } ?: ""}") }
}
