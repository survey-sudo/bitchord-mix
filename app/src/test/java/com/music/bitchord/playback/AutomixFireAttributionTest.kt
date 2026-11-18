package com.music.bitchord.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixFireAttributionTest {
    @Test
    fun classicFireJoinsAcceptedPlanAndDumpWithInapplicableNulls() {
        AutomixControllerRig().use { rig ->
            rig.arm()
            rig.out.position = rig.startMs + 1L
            rig.pulse()
            val events = readEvents(rig)
            val plan = events.single { it["ev"]?.toString() == "\"mix.plan\"" && it["accepted"]?.toString() == "true" }
            val dump = events.single { it["ev"]?.toString() == "\"mix.dump\"" }
            val fire = events.single { it["ev"]?.toString() == "\"mix.fire\"" }
            assertJoin(plan, dump, fire)
            assertEquals("null", fire["spliceTargetMs"].toString())
            assertEquals("null", fire["rendererFirstFrame"].toString())
            assertEquals("null", fire["tookOverAtFire"].toString())
        }
    }

    @Test
    fun replayingSamePairGetsDistinctTransitionId() {
        val ids = listOf(
            AutomixControllerRig().use { rig -> fireId(rig) },
            AutomixControllerRig().use { rig -> fireId(rig) },
        )
        assertNotEquals(ids[0], ids[1])
    }

    private fun fireId(rig: AutomixControllerRig): String {
        rig.arm(); rig.out.position = rig.startMs + 1L; rig.pulse()
        return requireNotNull(readEvents(rig).firstOrNull { it["ev"]?.toString() == "\"mix.fire\"" }?.get("transitionId")?.toString())
    }

    private fun assertJoin(plan: JsonObject, dump: JsonObject, fire: JsonObject) {
        assertEquals(plan["transitionId"], dump["transitionId"])
        assertEquals(plan["transitionId"], fire["transitionId"])
        assertEquals(plan["planHash"], dump["planHash"])
        assertEquals(plan["planHash"], fire["planHash"])
        for (key in listOf("outId", "inId", "path", "style")) {
            assertEquals(plan[key], dump[key]); assertEquals(plan[key], fire[key])
        }
        assertNotNull(plan["planId"])
        assertTrue(fire["actualStartMs"] != null)
    }

    private fun readEvents(rig: AutomixControllerRig): List<JsonObject> {
        val field = AutomixTelemetry::class.java.getDeclaredField("dir").apply { isAccessible = true }
        val dir = field.get(AutomixTelemetry) as java.io.File
        val file = java.io.File(dir, "automix-telemetry.jsonl")
        return file.readLines().map { Json.parseToJsonElement(it).jsonObject }
    }
}
