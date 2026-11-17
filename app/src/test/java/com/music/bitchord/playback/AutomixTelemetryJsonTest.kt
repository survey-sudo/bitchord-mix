package com.music.bitchord.playback

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixTelemetryJsonTest {
    @Test
    fun nonFiniteValuesAndNestedValuesAreValidJson() {
        val field = AutomixTelemetry::class.java.getDeclaredField("dir").apply { isAccessible = true }
        val previous = field.get(AutomixTelemetry)
        val directory = Files.createTempDirectory("automix-json").toFile()
        try {
            field.set(AutomixTelemetry, directory)
            AutomixTelemetry.event("json.test", mapOf(
                "nan" to Double.NaN,
                "positiveInfinity" to Float.POSITIVE_INFINITY,
                "negativeInfinity" to Double.NEGATIVE_INFINITY,
                "nullValue" to null,
                "text" to "quote=\" slash=\\",
                "nested" to mapOf("list" to listOf(1, Double.NaN, null)),
            ))
            val line = File(directory, "automix-telemetry.jsonl").readLines().single()
            val json = Json.parseToJsonElement(line).jsonObject
            assertTrue(json["nan"]!!.toString() == "null")
            assertEquals("null", json["positiveInfinity"].toString())
            assertEquals("null", json["negativeInfinity"].toString())
            assertEquals("null", json["nullValue"].toString())
            assertEquals("\"quote=\\\" slash=\\\\\"", json["text"].toString())
        } finally {
            field.set(AutomixTelemetry, previous)
            directory.deleteRecursively()
        }
    }
}
